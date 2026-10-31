"""The scanner: camera -> detection -> confirmed cards -> collection, packs, sounds.

Every frame, the detector finds the cards in view (several at once if they're
there) and the tracker builds up evidence for each over a few frames, so a card
waved past the camera doesn't register by accident. A confirmed card is then
"locked": holding it in view, or showing it again in the same pack, never adds
it twice. Cards you already own are flagged as duplicates.
"""
from __future__ import annotations

import collections
import logging
import queue
import threading
import time

import cv2
import numpy as np

from . import vision
from .detector import Detector
from .camera import FileSource, SteamVRCameraSource, WebcamSource, webcam_names
from .config import SENSITIVITY
from .tracker import MultiTracker

log = logging.getLogger(__name__)

MILESTONES = (25, 50, 75, 100)


class EventBus:
    def __init__(self):
        self._subs: set[queue.Queue] = set()
        self._lock = threading.Lock()

    def subscribe(self, maxsize: int = 200) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize)
        with self._lock:
            self._subs.add(q)
        return q

    def unsubscribe(self, q: queue.Queue):
        with self._lock:
            self._subs.discard(q)

    def emit(self, kind: str, **data):
        ev = {'type': kind, 'ts': time.time(), **data}
        with self._lock:
            subs = list(self._subs)
        for q in subs:
            try:
                q.put_nowait(ev)
            except queue.Full:
                pass


class Engine:
    def __init__(self, settings, db, refs, store, sounds, speaker, rates, bus: EventBus):
        self.settings, self.db, self.refs, self.store = settings, db, refs, store
        self.sounds, self.speaker, self.rates, self.bus = sounds, speaker, rates, bus
        self.vr = None                                # set by main once the VR manager exists
        self.lock = threading.RLock()
        self.detector = Detector()
        self.matcher_sets: list[str] = []
        self.tracker = MultiTracker()
        self.camera = None
        self.camera_id = None
        self.next_variant: str | None = None
        self.waiting_next_pack = False
        self.session_start = time.time()
        self.prep: dict | None = None
        self.data_ready = False
        self.data_error = ''
        self.vis: dict = {}
        self.last_alts: dict[int, list[str]] = {}
        self.proc_fps = 0.0
        self.proc_ms = 0.0
        self._stop = threading.Event()
        self._preview_cache: dict = {}
        self._prep_cancel = threading.Event()

    # ================================================================ lifecycle

    def start(self):
        threading.Thread(target=self._load_data, name='data', daemon=True).start()
        threading.Thread(target=self._scan_loop, name='scanner', daemon=True).start()

    def stop(self):
        self._stop.set()
        self._prep_cancel.set()
        if self.camera:
            self.camera.stop()

    def _load_data(self):
        s = self.settings
        try:
            self.db.site_url = s['site_url'].rstrip('/')
            self.db.load(refresh='auto', max_age_h=s['refresh_hours'] or 1e9,
                         progress=lambda msg: self.bus.emit('status', message=msg))
            self.data_ready = True
            self.bus.emit('data', status=self.db.status)
        except Exception as e:
            self.data_error = f'Could not load the card database: {e}'
            self.bus.emit('status', message=self.data_error)
            return
        if s['active_set'] and self.db.get_set(s['active_set']):
            self._activate(s['active_set'], keep_pack=True)
        self.restart_camera()
        self.sounds.play('ready')

    def reload_data(self):
        self.data_ready = False
        def run():
            try:
                self.db.load(refresh='force')
                self.data_ready = True
                self.bus.emit('data', status=self.db.status)
                self.reload_matcher()
            except Exception as e:
                self.data_ready = bool(self.db.sets)
                self.bus.emit('toast', message=f'Card data refresh failed: {e}', level='bad')
        threading.Thread(target=run, daemon=True).start()

    # ================================================================ camera

    def restart_camera(self):
        s = self.settings
        if self.camera:
            self.camera.stop()
            self.camera = None
        cam_id = s['camera']
        w, h = (int(x) for x in s['camera_res'].split('x'))
        kw = {'rotate': s['camera_rotate'], 'mirror': s['camera_mirror']}
        if cam_id == 'auto':
            cam_id = 'steamvr' if (self.vr and self.vr.connected and self.vr.has_camera) else 'webcam:0'
        if cam_id == 'steamvr':
            if self.vr is None:
                return
            src = SteamVRCameraSource(self.vr, eye=s['stereo_eye'], **kw)
        elif cam_id.startswith('file:') or cam_id.startswith('fileonce:'):
            once = cam_id.startswith('fileonce:')
            src = FileSource(cam_id.split(':', 1)[1], loop=not once, **kw)
        else:
            idx = int(cam_id.split(':', 1)[1]) if ':' in cam_id else 0
            names = webcam_names()
            if not names and cam_id == 'webcam:0' and s['camera'] == 'auto':
                self.camera_id = None
                self.bus.emit('camera', info={'status': 'error', 'error': 'No camera found. Plug in a webcam or start SteamVR.'})
                return
            name = names[idx] if idx < len(names) else f'Camera {idx}'
            src = WebcamSource(idx, name, w, h, s['camera_fps'], exposure=s['camera_exposure'], **kw)
        self.camera_id = cam_id
        self.camera = src.start()
        self.tracker.reset()
        self.bus.emit('camera', info=src.info())

    def on_vr_change(self):
        """SteamVR started or stopped: 'auto' may now want a different camera."""
        if self.settings['camera'] in ('auto', 'steamvr') and self.data_ready:
            want = 'steamvr' if (self.vr and self.vr.connected and self.vr.has_camera) else 'webcam:0'
            if self.settings['camera'] == 'steamvr' or want != self.camera_id:
                self.restart_camera()

    # ================================================================ sets & matcher

    def select_set(self, set_id: str):
        if not self.db.get_set(set_id):
            raise ValueError('unknown set')
        self.settings.update({'active_set': set_id})
        self._activate(set_id, keep_pack=False)
        if self.settings['snd_ui']:
            self.sounds.play('select')

    def _activate(self, set_id: str, keep_pack: bool):
        with self.lock:
            cur = self.store.current_pack()
            if cur and cur['set_id'] != set_id:
                if self.store.pack_scans(cur['id']):
                    self.store.close_pack(cur['id'], complete=False)
                else:
                    self.store.delete_pack(cur['id'])
                cur = None
            if not cur and not keep_pack:
                self._open_pack(set_id)
            self.waiting_next_pack = False
        self.tracker.reset()
        self.reload_matcher()
        self.bus.emit('set', set_id=set_id)

    def scope_set_ids(self) -> list[str]:
        s = self.settings
        active = s['active_set']
        if s['scope'] == 'prepared':
            ids = [i for i in self.refs.cached_ids() if i in self.db.sets]
        elif s['scope'] == 'selected':
            ids = [i for i in s['scope_sets'] if i in self.db.sets]
        else:
            ids = []
        if active and active not in ids:
            ids.insert(0, active)
        return ids

    def reload_matcher(self):
        ids = self.scope_set_ids()
        missing = [i for i in ids if not self.refs.cached(i)]
        ready = [ix for ix in (self.refs.load(i) for i in ids if i not in missing) if ix is not None]
        d = Detector()
        d.load(ready)
        d.preferred_sets = ids                  # a reprint is reported as the copy from the set being opened
        with self.lock:
            self.detector, self.matcher_sets = d, [ix.set_id for ix in ready]
        self.tracker.reset(keep_lock=True)
        if missing:
            self.prepare_sets(missing)
        self.bus.emit('matcher', sets=self.matcher_sets, cards=len(d))

    def prepare_sets(self, set_ids: list[str]):
        """Download & fingerprint sets in the background, then reload the matcher."""
        if self.prep and self.prep.get('running'):
            pending = self.prep.setdefault('queue', [])
            pending.extend(i for i in set_ids if i not in pending)
            return
        self._prep_cancel.clear()

        def run(queue_ids):
            self.prep = {'running': True, 'queue': list(queue_ids)}
            while self.prep['queue'] and not self._prep_cancel.is_set():
                sid = self.prep['queue'].pop(0)
                cs = self.db.get_set(sid)
                if cs is None or self.refs.cached(sid):
                    continue
                if cs.first_edition and cs.base_id and not self.refs.cached(cs.base_id):
                    self.prep['queue'].insert(0, sid)
                    sid, cs = cs.base_id, self.db.get_set(cs.base_id)
                self.prep.update(set_id=sid, name=cs.name, done=0, total=sum(1 for c in cs.cards if c.img))
                self.bus.emit('prep', **self.prep)

                def prog(done, total, sid=sid):
                    self.prep.update(done=done, total=total)
                    self.bus.emit('prep', **self.prep)
                try:
                    self.refs.build(cs, progress=prog, cancel=self._prep_cancel)
                except Exception as e:
                    log.warning('prepare %s failed: %s', sid, e)
                    self.bus.emit('toast', message=f'Could not download {cs.name}: {e}', level='bad')
            self.prep = None
            self.bus.emit('prep', running=False)
            self.reload_matcher()

        threading.Thread(target=run, args=(set_ids,), name='prepare', daemon=True).start()

    # ================================================================ scanning

    def _scan_loop(self):
        seq = -1
        times: collections.deque = collections.deque(maxlen=30)
        while not self._stop.is_set():
            cam = self.camera
            s = self.settings
            if cam is None or not self.data_ready:
                time.sleep(0.1)
                continue
            if cam.kind == 'steamvr' and getattr(cam, 'failed', False) and s['camera'] == 'auto':
                # 'Automatic' picked the headset camera but it sends no pictures (Steam Frame): use a webcam
                log.info('headset camera sends no frames; switching to a webcam')
                if self.vr:
                    self.vr.has_camera = False
                self.bus.emit('toast', message='The headset camera isn’t available to apps, so the webcam is used instead.',
                              level='info')
                self.restart_camera()
                continue
            seq2, frame = cam.get(seq, timeout=0.3)
            if frame is None or seq2 == seq:
                continue
            seq = seq2
            if s['paused'] or self.waiting_next_pack or not len(self.detector):
                self.vis = {'ts': time.time(), 'idle': True}
                time.sleep(0.05)
                continue
            t0 = time.perf_counter()
            try:
                self._process(frame)
            except Exception:
                log.exception('frame processing failed')
            dt = time.perf_counter() - t0
            times.append(time.time())
            self.proc_ms = self.proc_ms * 0.9 + dt * 1000 * 0.1
            if len(times) > 1:
                self.proc_fps = (len(times) - 1) / max(1e-3, times[-1] - times[0])
            budget = 1.0 / max(1, s['max_fps'])
            if dt < budget:
                time.sleep(budget - dt)

    def _process(self, frame: np.ndarray):
        s = self.settings
        gray = vision.to_gray(frame)
        scale = 1.0
        if gray.shape[1] > 1280:
            scale = 1280 / gray.shape[1]
            gray = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        det = self.detector
        dets = det.analyze(gray, mode=s['detect_mode'], guide_size=s['guide_size'],
                           use_keypoints=s['use_keypoints'], max_cards=s['max_cards'])
        threshold, min_sightings = SENSITIVITY.get(s['sensitivity'], SENSITIVITY['balanced'])
        now = time.time()
        confirmed = self.tracker.update(dets, now, threshold, 0.5, s['rescan_gap'], min_sightings)
        pend_key, pend_prog = self.tracker.pending(threshold)
        if pend_key and pend_key != getattr(self, '_last_pending', None) and s['snd_reading']                 and now - getattr(self, '_last_tick', 0) > 0.6:
            self.sounds.play('reading')                    # a new card has started being read
            self._last_tick = now
        self._last_pending = pend_key
        locked = set(self.tracker.locked)
        self.vis = {
            'ts': now,
            'dets': [{'quad': (d.quad / scale).tolist(), 'key': d.key, 'strength': d.strength,
                      'state': 'locked' if d.key in locked else 'pending'} for d in dets],
            'unknown': [(q / scale).tolist() for q, _, _ in det.last_unknown],
            'pending': pend_key, 'progress': pend_prog,
            'hint': self._hint(dets, det.last_unknown, gray.shape, now),
        }
        for key, d in confirmed:
            self._on_confirm(key, d)

    def _hint(self, dets, unknown, shape, now) -> str | None:
        """A tip for the person scanning when a card is in view but isn't being read well."""
        frame_area = shape[0] * shape[1]
        cand = None
        if dets and dets[0].key not in self.tracker.locked and dets[0].strength < 0.35:
            d = dets[0]
            cand = (d.area, d.sharp, d.glare, True)
        elif not dets and unknown:
            q, sharp, glare = unknown[0]
            cand = (abs(cv2.contourArea(np.float32(q))), sharp, glare, False)
        if cand is None:
            self._unknown_since = None
            return None
        area, sharp, glare, known = cand
        if area < 0.05 * frame_area:
            return 'closer'
        if sharp < 20:
            return 'steady'
        if glare > 0.06:
            return 'glare'
        if not known:
            # a clear, card-sized shape that matches nothing for a while: probably another set
            self._unknown_since = getattr(self, '_unknown_since', None) or now
            if now - self._unknown_since > 1.5:
                return 'unknown'
        return None

    # ================================================================ scans & packs

    def _pack_size(self, set_id: str) -> int:
        n = self.settings['pack_size']
        if n:
            return int(n)
        cs = self.db.get_set(set_id)
        return cs.pack_size if cs else 10

    def _pack_cost(self, set_id: str):
        c = self.settings['pack_cost']
        if c is not None:
            return float(c)
        cs = self.db.get_set(set_id)
        return cs.sealed_price if cs else None

    def _open_pack(self, set_id: str) -> dict:
        pid = self.store.open_pack(set_id, self._pack_size(set_id), self._pack_cost(set_id))
        if self.settings['snd_ui'] and self.data_ready:
            self.sounds.play('deal')                       # a fresh pack: the sound of cards being dealt
        return self.store.pack(pid)

    def _ensure_pack(self) -> dict | None:
        cur = self.store.current_pack()
        if cur:
            return cur
        sid = self.settings['active_set']
        return self._open_pack(sid) if sid else None

    def _on_confirm(self, key: str, rec):
        card = self.db.card(key)
        if card is None:
            return
        with self.lock:
            pack = self._ensure_pack()
            if pack and self.settings['same_in_pack'] == 'ignore':
                if any(sc['card_key'] == key for sc in self.store.pack_scans(pack['id'])):
                    self.bus.emit('ignored', card=card.to_dict(), reason='Already scanned in this pack')
                    if self.settings['snd_ignored']:
                        self.sounds.play('ignored')
                    return
            alts = [k for k, _ in rec.cands if k != key][:4] if rec is not None else []
            self._add(card, pack, score=rec.score if rec is not None else None, source='scan', alts=alts)

    def _add(self, card, pack: dict | None, score=None, source='scan', variant=None, alts=()):
        s = self.settings
        variant = variant or self.next_variant or s['default_variant']
        self.next_variant = None
        cs = self.db.get_set(card.set_id)
        before = self._progress_numbers(cs) if cs else None
        dupe = self.store.copies(card.key) > 0
        price = card.price_for(variant)
        # a card from another set while scanning this one still lands in the current pack
        sid = self.store.add_scan(card, variant, price, pack['id'] if pack else None, dupe, score, source)
        self.last_alts[sid] = list(alts)
        scan = self.scan_dict(self.store.scan(sid))
        self.bus.emit('scan', scan=scan)

        # sound + voice: the pull's value picks the sound (a valuable dupe still celebrates)
        snd = None
        if s['snd_hit'] and price >= s['jackpot_value']:
            snd = 'jackpot'
        elif s['snd_hit'] and price >= s['big_hit_value']:
            snd = 'big'
        elif s['snd_hit'] and price >= s['hit_value']:
            snd = 'hit'
        elif dupe:
            snd = 'dupe' if s['snd_dupe'] else None
        elif s['snd_new']:
            snd = 'new'
        if snd:
            self.sounds.play(snd)
        self._announce(card, price, dupe)

        # set milestones
        if cs and not dupe:
            after = self._progress_numbers(cs)
            for pct in MILESTONES:
                if before['main_pct'] < pct <= after['main_pct']:
                    self.bus.emit('milestone', set_id=cs.id, name=cs.name, pct=pct, kind='set')
                    if s['snd_milestone']:
                        self.sounds.play('milestone')
            if after['master_owned'] == after['master_total'] > before['master_owned']:
                self.bus.emit('milestone', set_id=cs.id, name=cs.name, pct=100, kind='master')
                if s['snd_milestone']:
                    self.sounds.play('milestone')

        # pack complete?
        if pack:
            n = len(self.store.pack_scans(pack['id']))
            if n >= pack['size']:
                self._finish_pack(pack, complete=True)
        return sid

    def _announce(self, card, price: float, dupe: bool):
        s = self.settings
        mode = s['voice']
        if mode == 'off':
            return
        p = self.rates.spoken(price, s['currency'])
        if mode == 'hits' and price < s['hit_value']:
            return
        name = card.name.replace(' EX', ' E X').replace(' GX', ' G X').replace(' VMAX', ' V max').replace(' VSTAR', ' V star')
        prefix = 'Duplicate. ' if dupe else ''
        self.speaker.say(f'{prefix}{name}, {p}' if mode in ('name_price', 'hits') else f'{prefix}{p}')

    def _finish_pack(self, pack: dict, complete: bool):
        self.store.close_pack(pack['id'], complete)
        summary = self.pack_dict(self.store.pack(pack['id']))
        self.bus.emit('pack_done', pack=summary)
        if complete and self.settings['snd_pack']:
            self.sounds.play('pack')
        if complete and self.settings['voice'] != 'off':
            self.speaker.say(f"Pack done. {self.rates.spoken(summary['value'], self.settings['currency'])}")
        if self.settings['auto_next_pack']:
            self._open_pack(pack['set_id'])
        else:
            self.waiting_next_pack = True
        self.tracker.reset(keep_lock=True)

    # ---------------------------------------------------------------- user actions

    def next_pack(self):
        """Close the current pack (even if short, e.g. a code card) and start a fresh one."""
        with self.lock:
            cur = self.store.current_pack()
            if cur and self.store.pack_scans(cur['id']):
                n = len(self.store.pack_scans(cur['id']))
                self._finish_pack(cur, complete=n >= cur['size'])
                if not self.settings['auto_next_pack']:
                    self._open_pack(cur['set_id'])
            elif not cur and self.settings['active_set']:
                self._open_pack(self.settings['active_set'])
            self.waiting_next_pack = False
        self.tracker.reset(keep_lock=True)
        self.bus.emit('pack', pack=self.current_pack_dict())

    def undo(self) -> bool:
        """Remove the most recent scan; reopens its pack if that scan had completed it."""
        with self.lock:
            last = self.store.last_scan()
            if not last:
                return False
            self.store.delete_scan(last['id'])
            pid = last['pack_id']
            cur = self.store.current_pack()
            if pid and (not cur or cur['id'] != pid):
                p = self.store.pack(pid)
                if p and p['ended'] is not None:
                    if cur and not self.store.pack_scans(cur['id']):
                        self.store.delete_pack(cur['id'])
                    self.store.reopen_pack(pid)
            self.waiting_next_pack = False
        self.tracker.reset()
        self.sounds.play('undo')
        self.bus.emit('undo', scan_id=last['id'])
        return True

    def delete_scan(self, scan_id: int):
        self.store.delete_scan(scan_id)
        self.tracker.reset()
        self.bus.emit('undo', scan_id=scan_id)

    def set_variant(self, scan_id: int, variant: str):
        sc = self.store.scan(scan_id)
        card = self.db.card(sc['card_key']) if sc else None
        if not card:
            return
        self.store.update_scan(scan_id, variant=variant, price=card.price_for(variant))
        self.bus.emit('scan_changed', scan=self.scan_dict(self.store.scan(scan_id)))

    def correct_scan(self, scan_id: int, card_key: str):
        """The scanner picked the wrong card: replace it with the right one."""
        sc = self.store.scan(scan_id)
        card = self.db.card(card_key)
        if not sc or not card:
            return
        dupe = self.store.copies(card.key, exclude_id=scan_id) > 0
        self.store.update_scan(scan_id, card_key=card.key, set_id=card.set_id, number=card.number, name=card.name,
                               rarity=card.rarity, price=card.price_for(sc['variant']), dupe=int(dupe), source='corrected')
        self.last_alts.pop(scan_id, None)
        self.tracker.reset(keep_lock=True)
        self.bus.emit('scan_changed', scan=self.scan_dict(self.store.scan(scan_id)))

    def manual_add(self, card_key: str, variant: str | None = None):
        card = self.db.card(card_key)
        if not card:
            raise ValueError('unknown card')
        with self.lock:
            pack = self._ensure_pack()
            return self._add(card, pack, source='manual', variant=variant)

    def set_next_variant(self, variant: str | None):
        self.next_variant = variant
        self.bus.emit('state_changed')

    def toggle_pause(self, value: bool | None = None):
        v = (not self.settings['paused']) if value is None else bool(value)
        self.settings.update({'paused': v})
        if self.settings['snd_ui']:
            self.sounds.play('pause' if v else 'resume')
        self.tracker.reset(keep_lock=True)
        self.bus.emit('state_changed')

    def reset_session(self):
        self.session_start = time.time()
        self.bus.emit('state_changed')

    def apply_settings(self, changed: dict):
        cam_keys = {'camera', 'camera_res', 'camera_fps', 'camera_rotate', 'camera_mirror', 'stereo_eye', 'camera_exposure'}
        if cam_keys & changed.keys() and self.data_ready:
            self.restart_camera()
        if {'scope', 'scope_sets'} & changed.keys():
            self.reload_matcher()
        if 'pack_size' in changed or 'pack_cost' in changed:
            cur = self.store.current_pack()
            if cur:
                self.store.update_pack(cur['id'], size=self._pack_size(cur['set_id']), cost=self._pack_cost(cur['set_id']))
        if 'site_url' in changed:
            self.db.site_url = changed['site_url'].rstrip('/')
            self.reload_data()
        if {'sensitivity', 'detect_mode'} & changed.keys():
            self.tracker.reset()
        self.bus.emit('settings', changed=changed)

    # ================================================================ views

    def scan_dict(self, sc: dict | None) -> dict | None:
        if not sc:
            return None
        card = self.db.card(sc['card_key'])
        d = dict(sc)
        d['card'] = card.to_dict() if card else None
        d['alts'] = [self.db.card(k).to_dict() for k in self.last_alts.get(sc['id'], []) if self.db.card(k)]
        return d

    def pack_dict(self, p: dict | None) -> dict | None:
        if not p:
            return None
        scans = [self.scan_dict(x) for x in self.store.pack_scans(p['id'])]
        value = sum(x['price'] or 0 for x in scans)
        best = max(scans, key=lambda x: x['price'] or 0) if scans else None
        cs = self.db.get_set(p['set_id'])
        number = self.store.one('SELECT COUNT(*) AS n FROM packs WHERE set_id=? AND id<=?', (p['set_id'], p['id']))['n']
        return {
            **p, 'number': number, 'setName': cs.name if cs else p['set_id'], 'scans': scans, 'count': len(scans),
            'value': round(value, 2), 'profit': round(value - (p['cost'] or 0), 2) if p['cost'] is not None else None,
            'best': best, 'new': sum(1 for x in scans if not x['dupe']), 'dupes': sum(1 for x in scans if x['dupe']),
            'hits': sum(1 for x in scans if (x['price'] or 0) >= self.settings['hit_value']),
        }

    def current_pack_dict(self):
        return self.pack_dict(self.store.current_pack())

    def _progress_numbers(self, cs) -> dict:
        owned = self.store.owned(cs.id)
        main_total = cs.main_count or len(cs.cards)
        main_owned = sum(1 for c in cs.cards if c.key in owned and (c.main_set or not cs.main_count))
        master_owned = sum(1 for c in cs.cards if c.key in owned)
        return {
            'main_owned': main_owned, 'main_total': main_total,
            'main_pct': 100.0 * main_owned / main_total if main_total else 0,
            'master_owned': master_owned, 'master_total': len(cs.cards),
        }

    def set_progress(self, set_id: str) -> dict | None:
        cs = self.db.get_set(set_id)
        if not cs:
            return None
        include_dupes = self.settings['duplicates'] == 'count'
        owned = self.store.owned(set_id)
        basis = self.settings['price_basis']
        cards = []
        owned_value = missing_value = 0.0
        by_rarity: dict[str, list] = {}
        for c in cs.cards:
            o = owned.get(c.key)
            n = (o['n'] if o else 0)
            d = c.to_dict()
            d['owned'] = n if include_dupes else min(n, 1)
            d['variants'] = (o['variants'] or '').split(',') if o else []
            cards.append(d)
            if n:
                copies = n if include_dupes else 1
                owned_value += (c.price * copies) if basis == 'market' else (o['value'] or 0)
            else:
                missing_value += c.price
            r = by_rarity.setdefault(c.rarity or 'Unknown', [0, 0])
            r[1] += 1
            r[0] += 1 if n else 0
        nums = self._progress_numbers(cs)
        packs = self.store.pack_totals(set_id)
        return {
            'set': cs.summary(), 'cards': cards, **nums,
            'owned_value': round(owned_value, 2), 'missing_value': round(missing_value, 2),
            'rarities': [{'rarity': k, 'owned': v[0], 'total': v[1]} for k, v in by_rarity.items()],
            'packs': packs, 'scannable': self.refs.cached(set_id),
        }

    def _status(self, cs) -> tuple[str, str]:
        s = self.settings
        if not self.data_ready:
            return 'loading', self.data_error or 'Loading the card database…'
        if not cs:
            return 'no_set', 'Choose the set you are opening'
        if self.prep and self.prep.get('running') and not len(self.detector):
            return 'preparing', f"Getting {self.prep.get('name', 'set')} ready to scan…"
        if self.camera is None or self.camera.status != 'live':
            return 'no_camera', (self.camera.error if self.camera else 'No camera selected') or 'Starting camera…'
        if s['paused']:
            return 'paused', 'Scanning paused'
        if self.waiting_next_pack:
            return 'pack_done', 'Pack complete. Press Next pack to keep going'
        return 'scanning', 'Hold a card up to the camera'

    HINTS = {
        'closer': 'Move the card closer to the camera',
        'steady': 'Hold the card still for a moment',
        'glare': 'Tilt the card a little: there is glare on it',
        'unknown': 'Card not recognised. Is it from the set you chose?',
    }

    def live(self) -> dict:
        """Small, cheap snapshot for the 'hold steady' indicator (polled several times a second)."""
        active = self.settings['active_set']
        cs = self.db.get_set(active) if active and self.data_ready else None
        st, msg = self._status(cs)
        v = self.vis or {}
        fresh = time.time() - v.get('ts', 0) < 0.8
        pk = v.get('pending') if fresh else None
        pc = self.db.card(pk) if pk else None
        locked = [self.db.card(k) for k in self.tracker.locked]
        in_view = [d for d in v.get('dets', [])] if fresh else []
        hint = v.get('hint') if fresh else None
        return {'state': st, 'message': msg, 'progress': round(v.get('progress', 0.0), 2) if pc else 0.0,
                'pendingName': pc.name if pc else None,
                'lockedNames': [c.name for c in locked if c][:6], 'cards': len(in_view),
                'hint': hint, 'hintText': self.HINTS.get(hint), 'fps': round(self.proc_fps, 1)}

    def state(self) -> dict:
        s = self.settings
        active = s['active_set']
        cs = self.db.get_set(active) if active and self.data_ready else None
        last = self.store.last_scan()
        prog = self._progress_numbers(cs) if cs else None
        st, msg = self._status(cs)
        return {
            'state': st, 'message': msg,
            'set': cs.summary() if cs else None, 'progress': prog,
            'pack': self.current_pack_dict() if cs else None,
            'last': self.scan_dict(last),
            'recent': [self.scan_dict(x) for x in self.store.recent(16)],
            'live': self.live(),
            'next_variant': self.next_variant,
            'session': {**self.store.totals(self.session_start), **{'packs': self.store.pack_totals(since=self.session_start)}},
            'camera': self.camera.info() if self.camera else None,
            'camera_id': self.camera_id,
            'vr': self.vr.info() if self.vr else {'connected': False},
            'prep': self.prep,
            'matcher': {'sets': self.matcher_sets, 'cards': len(self.detector)},
            'perf': {'fps': round(self.proc_fps, 1), 'ms': round(self.proc_ms, 1)},
            'data': {'status': self.db.status, 'updated': self.db.updated, 'ready': self.data_ready},
            'rates': self.rates.rates,
        }

    # ================================================================ preview

    def preview(self, max_w: int = 960, quality: int = 72) -> tuple[int, bytes | None]:
        """JPEG of the newest camera frame with what the scanner sees drawn on it."""
        cam = self.camera
        if cam is None:
            return -1, None
        seq, frame = cam.get(-1, timeout=0)
        if frame is None:
            return -1, None
        ck = (seq, max_w)
        if self._preview_cache.get('key') == ck:
            return seq, self._preview_cache['jpg']
        img = self.annotate(frame, max_w)
        ok, enc = cv2.imencode('.jpg', img, [cv2.IMWRITE_JPEG_QUALITY, quality])
        jpg = enc.tobytes() if ok else None
        self._preview_cache = {'key': ck, 'jpg': jpg}
        return seq, jpg

    def annotate(self, frame: np.ndarray, max_w: int) -> np.ndarray:
        img = frame if frame.ndim == 3 else cv2.cvtColor(frame, cv2.COLOR_GRAY2BGR)
        sc = min(1.0, max_w / img.shape[1])
        if sc < 1.0:
            img = cv2.resize(img, None, fx=sc, fy=sc, interpolation=cv2.INTER_AREA)
        else:
            img = img.copy()
        s = self.settings
        if s['detect_mode'] in ('guide', 'both'):
            g = vision.guide_quad(img.shape, s['guide_size'])
            cv2.polylines(img, [g.astype(np.int32)], True, (200, 200, 200), 1, cv2.LINE_AA)
        v = self.vis
        if v and time.time() - v.get('ts', 0) < 0.6:
            th = max(2, int(3 * sc + 1))
            for q in v.get('unknown', []):
                cv2.polylines(img, [(np.array(q) * sc).astype(np.int32)], True, (125, 107, 255), max(1, th - 1), cv2.LINE_AA)
            for d in v.get('dets', []):
                q = (np.array(d['quad']) * sc).astype(np.int32)
                if d['state'] == 'locked':
                    col = (129, 224, 62)                   # green: scanned
                elif d['strength'] >= 0.35:
                    col = (5, 203, 255)                    # yellow: confirming
                else:
                    col = (125, 107, 255)                  # red: not sure yet
                cv2.polylines(img, [q], True, col, th, cv2.LINE_AA)
                card = self.db.card(d['key'])
                if card and (d['state'] == 'locked' or d['strength'] >= 0.35):
                    label = f"{card.name}  #{card.number}"
                    p = q[np.argmin(q[:, 1])]
                    y = max(24, int(p[1]) - 10)
                    cv2.putText(img, label, (int(p[0]), y), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 0), 4, cv2.LINE_AA)
                    cv2.putText(img, label, (int(p[0]), y), cv2.FONT_HERSHEY_SIMPLEX, 0.7, col, 2, cv2.LINE_AA)
        return img
