"""Per-set reference data.

The first time a set is used, every card scan in it is downloaded once and
stored as a 240x336 greyscale image (about the scans' native size). From those
images we derive, and cache alongside them:
  * fingerprints (vision.describe) for the outline-based matcher,
  * ORB keypoints (features.ref_features) for the keypoint matcher.
When the scanner's algorithms change, the derived data is recomputed from the
stored images in a second or two; nothing is downloaded again.
"""
from __future__ import annotations

import concurrent.futures as cf
import logging
import os
import threading
import time
import urllib.request

import cv2
import numpy as np

from . import features, vision

log = logging.getLogger(__name__)
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) MivimooseScanner/1.0'

IMG_VERSION = 2                                   # bump only if the stored images themselves change
DERIVED_VERSION = vision.DESC_VERSION * 1000 + features.FEAT_VERSION


class RefIndex:
    def __init__(self, set_id, keys, ref, desc, kp_pts, kp_des, kp_off, missing):
        self.set_id = set_id
        self.keys = keys
        self.ref = ref                            # N x REF_H x REF_W uint8
        self.desc = desc                          # fingerprints, N x D float32
        self.kp_pts, self.kp_des, self.kp_off = kp_pts, kp_des, kp_off
        self.missing = missing
        self._canon = None

    @property
    def canon(self) -> np.ndarray:
        """The images at the fingerprint size (computed on first use)."""
        if self._canon is None:
            self._canon = np.stack([cv2.resize(r, (vision.CANON_W, vision.CANON_H), interpolation=cv2.INTER_AREA)
                                    for r in self.ref]) if len(self.ref) else np.zeros((0, vision.CANON_H, vision.CANON_W), np.uint8)
        return self._canon

    def keypoints(self, i: int):
        a, b = self.kp_off[i], self.kp_off[i + 1]
        return self.kp_pts[a:b], self.kp_des[a:b]


def reference_image(img: np.ndarray) -> np.ndarray:
    """A downloaded card scan as the stored REF_H x REF_W greyscale image."""
    img = vision.flatten_alpha(img, 200)
    g = vision.to_gray(img)
    interp = cv2.INTER_AREA if g.shape[0] >= features.REF_H else cv2.INTER_CUBIC
    return cv2.resize(g, (features.REF_W, features.REF_H), interpolation=interp)


def derive(ref: np.ndarray):
    """Fingerprints and keypoints for a stack of reference images."""
    n = len(ref)
    if not n:
        return np.zeros((0, 1), np.float32), np.zeros((0, 2), np.float32), np.zeros((0, 32), np.uint8), np.zeros(1, np.int32)
    canon = [cv2.resize(r, (vision.CANON_W, vision.CANON_H), interpolation=cv2.INTER_AREA) for r in ref]
    with cf.ThreadPoolExecutor(8) as pool:
        desc = list(pool.map(vision.describe, canon))
        kps = list(pool.map(features.ref_features, ref))
    off = np.zeros(n + 1, np.int32)
    off[1:] = np.cumsum([len(p) for p, _ in kps])
    return (np.stack(desc).astype(np.float32), np.concatenate([p for p, _ in kps]).astype(np.float32),
            np.concatenate([d for _, d in kps]).astype(np.uint8), off)


def _download(url: str, timeout: float = 20, tries: int = 3) -> bytes:
    err = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:             # transient CDN errors are common; back off a little
            err = e
            time.sleep(0.4 * (i + 1))
    raise err


def _decode(data: bytes) -> np.ndarray | None:
    arr = np.frombuffer(data, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_UNCHANGED)
    if img is None:
        return None
    if img.ndim == 2:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
    return img


class RefStore:
    def __init__(self, cache_dir: str, workers: int = 16):
        self.dir = os.path.join(cache_dir, 'refs')
        os.makedirs(self.dir, exist_ok=True)
        self.workers = workers
        self._mem: dict[str, RefIndex] = {}
        self._lock = threading.Lock()
        self._building: dict[str, threading.Event] = {}

    def _path(self, set_id: str) -> str:
        safe = ''.join(ch if ch.isalnum() or ch in '-_.' else '_' for ch in set_id)
        return os.path.join(self.dir, f'{safe}.r{IMG_VERSION}.npz')

    def cached(self, set_id: str) -> bool:
        return set_id in self._mem or os.path.exists(self._path(set_id))

    def cached_ids(self) -> list[str]:
        suffix = f'.r{IMG_VERSION}.npz'
        return [f[:-len(suffix)] for f in os.listdir(self.dir) if f.endswith(suffix)]

    def load(self, set_id: str) -> RefIndex | None:
        if set_id in self._mem:
            return self._mem[set_id]
        p = self._path(set_id)
        if not os.path.exists(p):
            return None
        try:
            z = np.load(p, allow_pickle=False)
            keys = [str(k) for k in z['keys']]
            missing = [str(k) for k in z['missing']]
            ref = z['ref']
            if int(z['derived']) == DERIVED_VERSION:
                ix = RefIndex(set_id, keys, ref, z['desc'].astype(np.float32), z['kp_pts'], z['kp_des'], z['kp_off'], missing)
            else:                                   # algorithms changed: recompute from the stored images
                t0 = time.time()
                ix = RefIndex(set_id, keys, ref, *derive(ref), missing)
                self._save(ix)
                log.info('re-derived %s in %.1fs', set_id, time.time() - t0)
        except Exception as e:
            log.warning('bad reference cache %s: %s', p, e)
            try:
                os.remove(p)
            except OSError:
                pass
            return None
        self._mem[set_id] = ix
        return ix

    def _save(self, ix: RefIndex):
        tmp = self._path(ix.set_id) + '.tmp.npz'
        np.savez_compressed(tmp, keys=np.array(ix.keys), missing=np.array(ix.missing), ref=ix.ref,
                            derived=np.int64(DERIVED_VERSION), desc=ix.desc.astype(np.float16),
                            kp_pts=ix.kp_pts.astype(np.float32), kp_des=ix.kp_des, kp_off=ix.kp_off)
        os.replace(tmp, self._path(ix.set_id))

    def delete(self, set_id: str | None = None) -> None:
        for f in os.listdir(self.dir):
            if set_id is None or f.startswith(set_id + '.r') or f.startswith(set_id + '.v'):
                os.remove(os.path.join(self.dir, f))
        if set_id is None:
            self._mem.clear()
        else:
            self._mem.pop(set_id, None)

    def build(self, card_set, progress=None, cancel: threading.Event | None = None) -> RefIndex:
        """Download and fingerprint every card in the set. progress(done, total)."""
        with self._lock:
            ev = self._building.get(card_set.id)
            owner = ev is None
            if owner:
                ev = self._building[card_set.id] = threading.Event()
        if not owner:                                   # someone else is already building it
            ev.wait()
            ix = self.load(card_set.id)
            if ix is None:
                raise RuntimeError(f'could not prepare {card_set.name}')
            return ix
        try:
            return self._build(card_set, progress, cancel)
        finally:
            with self._lock:
                self._building.pop(card_set.id, None)
            ev.set()

    def _build(self, card_set, progress, cancel) -> RefIndex:
        cards = [c for c in card_set.cards if c.img]
        missing = [c.key for c in card_set.cards if not c.img]
        base = None                                     # 1st Edition sets share their artwork with the base set
        if card_set.first_edition and card_set.base_id:
            base = self.load(card_set.base_id)
        total = len(cards)
        results: dict[str, np.ndarray] = {}
        done = 0
        t0 = time.time()

        if base is not None:
            by_base = dict(zip(base.keys, base.ref))
            for c in cards:
                bk = c.key.replace(card_set.id + '|', card_set.base_id + '|', 1)
                if bk in by_base:
                    results[c.key] = by_base[bk]
            done = len(results)

        def work(card):
            if cancel is not None and cancel.is_set():
                return card.key, None
            try:
                img = _decode(_download(card.img))
            except Exception as e:
                log.debug('image failed %s: %s', card.img, e)
                img = None
            return card.key, (reference_image(img) if img is not None else None)

        todo = [c for c in cards if c.key not in results]
        with cf.ThreadPoolExecutor(self.workers) as pool:
            for key, ref in pool.map(work, todo):
                done += 1
                if ref is not None:
                    results[key] = ref
                else:
                    missing.append(key)
                if progress and (done % 4 == 0 or done == total):
                    progress(done, total)
        if cancel is not None and cancel.is_set():
            raise RuntimeError('cancelled')

        keys = [c.key for c in cards if c.key in results]
        ref = np.stack([results[k] for k in keys]) if keys else np.zeros((0, features.REF_H, features.REF_W), np.uint8)
        ix = RefIndex(card_set.id, keys, ref, *derive(ref), missing)
        self._save(ix)
        self._mem[card_set.id] = ix
        log.info('prepared %s: %d cards (%d missing) in %.1fs', card_set.id, len(keys), len(missing), time.time() - t0)
        return ix
