"""Per-set reference fingerprints.

The first time a set is used, every card scan in it is downloaded once,
straightened to the scanner's 200x280 greyscale format and fingerprinted.
The result is cached on disk (one small .npz per set), so later sessions load
in a few milliseconds.
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

from . import vision

log = logging.getLogger(__name__)
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) MivimooseScanner/1.0'


class RefIndex:
    def __init__(self, set_id: str, keys: list[str], desc: np.ndarray, canon: np.ndarray, missing: list[str]):
        self.set_id = set_id
        self.keys = keys
        self.desc = desc
        self.canon = canon
        self.missing = missing


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
        return os.path.join(self.dir, f'{safe}.v{vision.DESC_VERSION}.npz')

    def cached(self, set_id: str) -> bool:
        return set_id in self._mem or os.path.exists(self._path(set_id))

    def cached_ids(self) -> list[str]:
        suffix = f'.v{vision.DESC_VERSION}.npz'
        return [f[:-len(suffix)] for f in os.listdir(self.dir) if f.endswith(suffix)]

    def load(self, set_id: str) -> RefIndex | None:
        if set_id in self._mem:
            return self._mem[set_id]
        p = self._path(set_id)
        if not os.path.exists(p):
            return None
        try:
            z = np.load(p, allow_pickle=False)
            ix = RefIndex(set_id, [str(k) for k in z['keys']], z['desc'].astype(np.float32), z['canon'],
                          [str(k) for k in z['missing']])
        except Exception as e:
            log.warning('bad reference cache %s: %s', p, e)
            os.remove(p)
            return None
        self._mem[set_id] = ix
        return ix

    def delete(self, set_id: str | None = None) -> None:
        for f in os.listdir(self.dir):
            if set_id is None or f.startswith(set_id + '.v'):
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
        # 1st Edition sets share their artwork with the base set
        base = None
        if card_set.first_edition and card_set.base_id:
            base = self.load(card_set.base_id)
        total = len(cards)
        results: dict[str, np.ndarray] = {}
        done = 0
        t0 = time.time()

        if base is not None:
            canon_by_base = dict(zip(base.keys, base.canon))
            for c in cards:
                bk = c.key.replace(card_set.id + '|', card_set.base_id + '|', 1)
                if bk in canon_by_base:
                    results[c.key] = canon_by_base[bk]
            done = len(results)

        def work(card):
            if cancel is not None and cancel.is_set():
                return card.key, None
            try:
                img = _decode(_download(card.img))
            except Exception as e:
                log.debug('image failed %s: %s', card.img, e)
                img = None
            return card.key, (vision.reference_canon(img) if img is not None else None)

        todo = [c for c in cards if c.key not in results]
        with cf.ThreadPoolExecutor(self.workers) as pool:
            for key, canon in pool.map(work, todo):
                done += 1
                if canon is not None:
                    results[key] = canon
                else:
                    missing.append(key)
                if progress and (done % 4 == 0 or done == total):
                    progress(done, total)
        if cancel is not None and cancel.is_set():
            raise RuntimeError('cancelled')

        keys = [c.key for c in cards if c.key in results]
        canon = np.stack([results[k] for k in keys]) if keys else np.zeros((0, vision.CANON_H, vision.CANON_W), np.uint8)
        desc = np.stack([vision.describe(c) for c in canon]) if keys else np.zeros((0, 1), np.float32)
        ix = RefIndex(card_set.id, keys, desc, canon, missing)
        tmp = self._path(card_set.id) + '.tmp.npz'
        np.savez_compressed(tmp, keys=np.array(keys), desc=desc.astype(np.float16), canon=canon, missing=np.array(missing))
        os.replace(tmp, self._path(card_set.id))
        self._mem[card_set.id] = ix
        log.info('prepared %s: %d cards (%d missing) in %.1fs', card_set.id, len(keys), len(missing), time.time() - t0)
        return ix
