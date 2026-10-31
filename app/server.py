"""Local web server behind the desktop window: JSON API, live events (SSE) and the camera stream (MJPEG).

Listens on 127.0.0.1 only, unless "Allow other devices on my network" is on
(so the headset's own browser or a phone can open the dashboard).
"""
from __future__ import annotations

import json
import logging
import mimetypes
import os
import queue
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .camera import list_cameras
from .config import CHOICES, DEFAULTS, RANGES, SENSITIVITY, VERSION

log = logging.getLogger(__name__)
mimetypes.add_type('text/javascript', '.js')
mimetypes.add_type('image/svg+xml', '.svg')


class App:
    """Everything the handlers need."""

    def __init__(self, engine, settings, db, store, refs, rates, bus, web_dir, quit_cb):
        self.engine, self.settings, self.db, self.store, self.refs = engine, settings, db, store, refs
        self.rates, self.bus, self.web_dir, self.quit_cb = rates, bus, web_dir, quit_cb


class Handler(BaseHTTPRequestHandler):
    server_version = 'MivimooseScanner/' + VERSION
    protocol_version = 'HTTP/1.1'
    app: App = None

    def log_message(self, fmt, *args):
        log.debug('%s - %s', self.address_string(), fmt % args)

    # ---------------------------------------------------------------- helpers

    def _send(self, code: int, body: bytes, ctype: str, extra: dict | None = None):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    def json(self, obj, code: int = 200):
        self._send(code, json.dumps(obj, default=str).encode(), 'application/json; charset=utf-8')

    def error_json(self, code: int, msg: str):
        self.json({'error': msg}, code)

    def _host_ok(self) -> bool:
        """Blocks DNS-rebinding: only answer to our own host names."""
        if self.app.settings['lan']:
            return True
        host = (self.headers.get('Host') or '').split(':')[0].lower()
        return host in ('127.0.0.1', 'localhost', '')

    def _read_json(self) -> dict:
        n = int(self.headers.get('Content-Length') or 0)
        if n > 1_000_000:
            raise ValueError('request too large')
        raw = self.rfile.read(n) if n else b'{}'
        return json.loads(raw or b'{}')

    # ---------------------------------------------------------------- routing

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        if not self._host_ok():
            return self.error_json(403, 'forbidden host')
        u = urllib.parse.urlparse(self.path)
        q = {k: v[-1] for k, v in urllib.parse.parse_qs(u.query).items()}
        p = u.path
        a = self.app
        try:
            if p == '/api/state':
                return self.json(self._state())
            if p == '/api/live':
                return self.json(a.engine.live())
            if p == '/api/sets':
                owned = a.store.owned_by_set()
                cached = set(a.refs.cached_ids())
                sets = a.db.set_list()
                for s in sets:
                    s['owned'] = owned.get(s['id'], 0)
                    s['ready'] = s['id'] in cached
                return self.json({'sets': sets})
            if p.startswith('/api/set/'):
                d = a.engine.set_progress(urllib.parse.unquote(p[9:]))
                return self.json(d) if d else self.error_json(404, 'no such set')
            if p == '/api/packs':
                lim, off = int(q.get('limit', 40)), int(q.get('offset', 0))
                packs = a.store.packs(lim, off, q.get('set') or None)
                for pk in packs:
                    cs = a.db.get_set(pk['set_id'])
                    pk['setName'] = cs.name if cs else pk['set_id']
                    pk['profit'] = round(pk['value'] - pk['cost'], 2) if pk['cost'] is not None else None
                return self.json({'packs': packs, 'totals': a.store.pack_totals(q.get('set') or None)})
            if p.startswith('/api/pack/'):
                return self.json(a.engine.pack_dict(a.store.pack(int(p[10:]))))
            if p == '/api/search':
                cards = a.db.search(q.get('q', ''), q.get('set') or None, int(q.get('limit', 40)))
                return self.json({'cards': [c.to_dict() for c in cards]})
            if p == '/api/collection':
                return self.json(self._collection(q))
            if p == '/api/cameras':
                vr = a.engine.vr
                return self.json({'cameras': list_cameras(bool(vr and vr.connected))})
            if p == '/api/export.csv':
                body = a.store.export_csv().encode('utf-8-sig')
                return self._send(200, body, 'text/csv; charset=utf-8',
                                  {'Content-Disposition': f'attachment; filename="card-scans-{time.strftime("%Y%m%d")}.csv"'})
            if p == '/api/events':
                return self._sse()
            if p == '/video.mjpg':
                return self._mjpeg(int(q.get('w', 960)), float(q.get('fps', 15)))
            if p == '/frame.jpg':
                _, jpg = a.engine.preview(int(q.get('w', 960)))
                return self._send(200, jpg, 'image/jpeg') if jpg else self.error_json(404, 'no frame')
            return self._static(p)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
        except Exception as e:
            log.exception('GET %s failed', p)
            try:
                self.error_json(500, str(e))
            except Exception:
                pass

    def do_POST(self):
        if not self._host_ok():
            return self.error_json(403, 'forbidden host')
        # A custom header can't be sent cross-site without a CORS preflight (which we never grant),
        # so other web pages open in your browser can't drive the scanner.
        if self.headers.get('X-Scanner') != '1':
            return self.error_json(403, 'missing header')
        p = urllib.parse.urlparse(self.path).path
        try:
            body = self._read_json()
            if p == '/api/action':
                return self.json(self._action(body))
            if p == '/api/settings':
                changed = self.app.settings.update(body.get('changes') or {})
                self.app.engine.apply_settings(changed)
                return self.json({'changed': changed, 'settings': self.app.settings.values})
            if p == '/api/quit':
                self.json({'ok': True})
                threading.Timer(0.3, self.app.quit_cb).start()
                return
            self.error_json(404, 'not found')
        except ValueError as e:
            self.error_json(400, str(e))
        except Exception as e:
            log.exception('POST %s failed', p)
            self.error_json(500, str(e))

    # ---------------------------------------------------------------- endpoints

    def _state(self) -> dict:
        a = self.app
        st = a.engine.state()
        st['settings'] = a.settings.values
        st['choices'] = CHOICES
        st['ranges'] = RANGES
        st['defaults'] = DEFAULTS
        st['sensitivity'] = SENSITIVITY
        st['version'] = VERSION
        return st

    def _collection(self, q) -> dict:
        a = self.app
        include_dupes = a.settings['duplicates'] == 'count'
        owned = a.store.owned(include_dupes=include_dupes)
        rows = []
        total = 0.0
        for key, o in owned.items():
            c = a.db.card(key)
            if not c:
                continue
            d = c.to_dict()
            d['owned'] = o['n']
            d['variants'] = (o['variants'] or '').split(',')
            d['first'] = o['first']
            d['value'] = round(c.price * o['n'], 2)
            total += d['value']
            rows.append(d)
        sort = q.get('sort', 'value')
        keyf = {'value': lambda r: -r['value'], 'price': lambda r: -r['price'], 'name': lambda r: r['name'].lower(),
                'recent': lambda r: -r['first'], 'set': lambda r: (r['setName'], r['order'])}.get(sort, lambda r: -r['value'])
        rows.sort(key=keyf)
        flt = (q.get('q') or '').lower().strip()
        if flt:
            rows = [r for r in rows if flt in f"{r['name']} {r['setName']} {r['rarity']} {r['number']}".lower()]
        lim = int(q.get('limit', 300))
        return {'cards': rows[:lim], 'count': len(rows), 'value': round(total, 2), 'totals': a.store.totals()}

    def _action(self, b: dict) -> dict:
        e = self.app.engine
        act = b.get('action')
        if act == 'select_set':
            e.select_set(str(b['set_id']))
        elif act == 'prepare_set':
            e.prepare_sets([str(b['set_id'])])
        elif act == 'forget_set':
            self.app.refs.delete(str(b['set_id']))
            e.reload_matcher()
        elif act == 'next_pack':
            e.next_pack()
        elif act == 'undo':
            return {'ok': e.undo()}
        elif act == 'delete_scan':
            e.delete_scan(int(b['scan_id']))
        elif act == 'set_variant':
            e.set_variant(int(b['scan_id']), str(b['variant']))
        elif act == 'correct':
            e.correct_scan(int(b['scan_id']), str(b['card_key']))
        elif act == 'manual_add':
            return {'scan_id': e.manual_add(str(b['card_key']), b.get('variant'))}
        elif act == 'next_variant':
            e.set_next_variant(b.get('variant') or None)
        elif act == 'pause':
            e.toggle_pause(b.get('value'))
        elif act == 'reset_session':
            e.reset_session()
        elif act == 'delete_pack':
            self.app.store.delete_pack(int(b['pack_id']))
            e.bus.emit('state_changed')
        elif act == 'reload_data':
            e.reload_data()
        elif act == 'restart_camera':
            e.restart_camera()
        elif act == 'test_sound':
            e.sounds.play(str(b.get('sound', 'new')), force=True)
        elif act == 'test_voice':
            e.speaker.say('Charizard ex, ' + e.rates.spoken(42.5, self.app.settings['currency']))
        elif act == 'clear_cache':
            self.app.refs.delete(None)
            e.reload_matcher()
        elif act == 'reset_collection':
            if b.get('confirm') != 'DELETE':
                raise ValueError('confirmation required')
            self.app.store.reset_all()
            e.tracker.reset()
            e.bus.emit('state_changed')
        else:
            raise ValueError(f'unknown action {act!r}')
        return {'ok': True}

    def _sse(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'keep-alive')
        self.end_headers()
        q = self.app.bus.subscribe()
        try:
            self.wfile.write(b'retry: 1500\n\n')
            self.wfile.flush()
            while True:
                try:
                    ev = q.get(timeout=15)
                    data = json.dumps(ev, default=str)
                    self.wfile.write(f'data: {data}\n\n'.encode())
                except queue.Empty:
                    self.wfile.write(b': ping\n\n')
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        finally:
            self.app.bus.unsubscribe(q)
            self.close_connection = True

    def _mjpeg(self, width: int, fps: float):
        self.send_response(200)
        self.send_header('Content-Type', 'multipart/x-mixed-replace; boundary=frame')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        width = max(160, min(1920, width))
        gap = 1.0 / max(1.0, min(30.0, fps))
        last = -1
        try:
            while True:
                t0 = time.time()
                seq, jpg = self.app.engine.preview(width)
                if jpg is not None and seq != last:
                    last = seq
                    self.wfile.write(b'--frame\r\nContent-Type: image/jpeg\r\nContent-Length: '
                                     + str(len(jpg)).encode() + b'\r\n\r\n' + jpg + b'\r\n')
                    self.wfile.flush()
                time.sleep(max(0.005, gap - (time.time() - t0)))
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        finally:
            self.close_connection = True

    def _static(self, path: str):
        if path in ('', '/'):
            path = '/index.html'
        root = os.path.realpath(self.app.web_dir)
        full = os.path.realpath(os.path.join(root, path.lstrip('/')))
        if not full.startswith(root + os.sep) or not os.path.isfile(full):
            return self.error_json(404, 'not found')
        with open(full, 'rb') as f:
            body = f.read()
        ctype = mimetypes.guess_type(full)[0] or 'application/octet-stream'
        if ctype.startswith('text/') or ctype in ('application/javascript', 'image/svg+xml'):
            ctype += '; charset=utf-8'
        self._send(200, body, ctype)


class Server:
    def __init__(self, app: App, port: int, lan: bool):
        handler = type('BoundHandler', (Handler,), {'app': app})
        host = '0.0.0.0' if lan else '127.0.0.1'
        try:
            self.httpd = ThreadingHTTPServer((host, port), handler)
        except OSError:
            self.httpd = ThreadingHTTPServer((host, 0), handler)    # port taken: any free one
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        self.url = f'http://127.0.0.1:{self.port}/'
        threading.Thread(target=self.httpd.serve_forever, name='http', daemon=True).start()

    def stop(self):
        self.httpd.shutdown()
