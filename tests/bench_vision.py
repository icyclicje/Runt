"""Single-frame detection benchmark on synthetic camera frames.

usage: python -m tests.bench_vision <cache_dir> [set_id ...] [--n 120] [--hard] [--neg swsh12pt5]

Builds reference data for the given sets (downloading card scans the first time),
renders random camera frames of cards from the first set (see synth2.py), and
reports how often the card is found and identified in a single frame, at several
sighting strengths, plus how often cards that are NOT in the sets get mistaken for
one that is. (The sequence benchmark, bench_seq.py, measures whole scans.)
"""
from __future__ import annotations

import argparse
import collections
import concurrent.futures as cf
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app import vision                      # noqa: E402
from app.carddb import CardDB               # noqa: E402
from app.detector import Detector           # noqa: E402
from app.refs import RefStore, _decode, _download   # noqa: E402

LEVELS = (0.2, 0.35, 0.5, 0.7)


def color_images(cards):
    def get(c):
        try:
            return c.key, vision.flatten_alpha(_decode(_download(c.img)), 200)
        except Exception:
            return c.key, None
    with cf.ThreadPoolExecutor(16) as pool:
        return {k: v for k, v in pool.map(get, cards) if v is not None}


def run(det, items, rng, table_pool, art_pool, hard, label, mode='auto'):
    from tests import synth2
    rows = []
    t_total = 0.0
    for key, img in items:
        table = [table_pool[int(i)] for i in rng.choice(len(table_pool), int(rng.integers(0, 3)), replace=False)] \
            if table_pool and rng.random() < 0.4 else []
        scene = synth2.Scene(rng, art_pool=art_pool, table_cards=[v for _, v in table], hard=hard)
        path = synth2.handheld_path(rng, (scene.h, scene.w), hard=hard)
        held = [p for p in path if p[5]]
        c, hgt, ang, persp, tilt, _ = held[int(rng.integers(len(held)))]
        look = synth2.CardLook(img, rng, hard=hard)
        frame = scene.render([(look, synth2.pose_quad(c, hgt, ang, persp), (0, 0), tilt)], t=float(rng.uniform(0, 3)))
        t0 = time.perf_counter()
        dets = det.analyze(frame, mode=mode)
        t_total += time.perf_counter() - t0
        allowed = {k for k, _ in table}
        hit = max((d for d in dets if d.key == key), key=lambda d: d.strength, default=None)
        wrong = max((d.strength for d in dets if d.key != key and d.key not in allowed), default=0.0)
        rows.append((hit.strength if hit else 0.0, hit.source if hit else None, wrong))
    n = len(rows)
    print(f'[{label}] n={n}, {1000 * t_total / n:.1f} ms/frame')
    for lv in LEVELS:
        found = sum(1 for s, _, _ in rows if s >= lv)
        bad = sum(1 for _, _, w in rows if w >= lv)
        print(f'    strength >= {lv:.2f}: right card {found / n:6.1%}   wrong card {bad:3d} ({bad / n:.1%})')
    src = collections.Counter(s for v, s, _ in rows if v >= LEVELS[1])
    print('    found by:', dict(src))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('sets', nargs='*', default=['sv3pt5', 'base1'])
    ap.add_argument('--n', type=int, default=120)
    ap.add_argument('--hard', action='store_true')
    ap.add_argument('--neg', default='swsh12pt5')
    ap.add_argument('--mode', default='auto')
    ap.add_argument('--no-snap', action='store_true', help="don't snap keypoint outlines onto edges")
    ap.add_argument('--no-kp', action='store_true', help='outline method only')
    a = ap.parse_args()
    cv2.setNumThreads(4)

    db = CardDB(a.cache)
    db.load(refresh='never')
    store = RefStore(a.cache)
    ixs = []
    for sid in a.sets:
        t0 = time.time()
        ix = store.load(sid) or store.build(db.get_set(sid), progress=None)
        print(f'index {sid}: {len(ix.keys)} cards, {len(ix.missing)} missing, {time.time() - t0:.1f}s')
        ixs.append(ix)
    det = Detector()
    det.load(ixs)
    det.snap_edges = not a.no_snap
    if a.no_kp:
        det.findex = None
    rng = np.random.default_rng(7)

    cards = [c for c in db.get_set(a.sets[0]).cards if c.img]
    sample = [cards[i] for i in rng.choice(len(cards), min(a.n + 20, len(cards)), replace=False)]
    imgs = list(color_images(sample).items())
    neg = db.get_set(a.neg)
    ncards = [c for c in neg.cards if c.img]
    nimgs = list(color_images([ncards[i] for i in rng.choice(len(ncards), min(70, len(ncards)), replace=False)]).items())
    art_pool = [v for _, v in nimgs[:10]]
    table_pool = imgs[a.n:]
    run(det, imgs[:a.n], rng, table_pool, art_pool, a.hard, f'{a.sets[0]} in {"+".join(a.sets)}{" hard" if a.hard else ""}', a.mode)
    run(det, [('__not_in_set__', v) for _, v in nimgs[10:]], rng, table_pool, art_pool, a.hard,
        f'NOT IN THE SETS ({a.neg})', a.mode)


if __name__ == '__main__':
    main()
