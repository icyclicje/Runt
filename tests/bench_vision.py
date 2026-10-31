"""Recognition benchmark on synthetic camera frames.

usage: python -m tests.bench_vision <cache_dir> [set_id ...] [--n 120] [--hard]

Builds reference indexes for the given sets (downloading card scans the first
time), renders random camera frames of cards from the first set, and reports
how often the card is found and identified, plus how strongly cards that are
NOT in the index get rejected (to choose the acceptance thresholds).
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app import vision                      # noqa: E402
from app.carddb import CardDB               # noqa: E402
from app.refs import RefStore, _decode, _download   # noqa: E402
from tests.synth import scene               # noqa: E402


def color_images(cards):
    def get(c):
        try:
            return c.key, vision.flatten_alpha(_decode(_download(c.img)), 200)
        except Exception:
            return c.key, None
    with cf.ThreadPoolExecutor(16) as pool:
        return {k: v for k, v in pool.map(get, cards) if v is not None}


THRESHOLDS = ((0.50, 0.03), (0.55, 0.04), (0.60, 0.05), (0.65, 0.06))


def run(matcher, items, rng, pool, hard, label, mode='auto'):
    det = top1 = 0
    rows, wrong = [], []
    t_total = 0.0
    for key, img in items:
        frame, truth = scene(img, rng, pool, hard=hard)
        t0 = time.perf_counter()
        r = vision.recognize(frame, matcher, mode=mode)
        t_total += time.perf_counter() - t0
        if r is None:
            continue
        det += 1
        ok = r.key == key
        top1 += ok
        rows.append((r.score, r.margin, ok))
        if not ok and key is not None:
            wrong.append((key, r.key, round(r.score, 3), round(r.margin, 3), r.source))
    n = len(items)
    print(f'[{label}] n={n} found={det / n:.1%} top1={top1 / n:.1%} avg {1000 * t_total / n:.1f} ms/frame')
    if rows:
        s = np.array([x[0] for x in rows])
        m = np.array([x[1] for x in rows])
        print(f'    score p10/p50/p90 = {np.percentile(s, 10):.3f}/{np.median(s):.3f}/{np.percentile(s, 90):.3f}  '
              f'margin p10/p50 = {np.percentile(m, 10):.3f}/{np.median(m):.3f}')
        for st, mt in THRESHOLDS:
            acc = [x for x in rows if x[0] >= st and x[1] >= mt]
            good = sum(1 for x in acc if x[2])
            print(f'    accept score>={st} margin>={mt}: accepted {len(acc) / n:.1%}, '
                  f'right {good / n:.1%}, WRONG {len(acc) - good}')
    for w in wrong[:6]:
        print('    wrong:', w)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('sets', nargs='*', default=['sv3pt5', 'base1'])
    ap.add_argument('--n', type=int, default=120)
    ap.add_argument('--hard', action='store_true')
    ap.add_argument('--neg', default='swsh12pt5')
    a = ap.parse_args()

    db = CardDB(a.cache)
    db.load(refresh='never')
    store = RefStore(a.cache)
    ixs = []
    for sid in a.sets:
        s = db.get_set(sid)
        t0 = time.time()
        ix = store.load(sid) or store.build(s, progress=None)
        print(f'index {sid}: {len(ix.keys)} cards, {len(ix.missing)} missing, {time.time() - t0:.1f}s')
        ixs.append(ix)
    m = vision.Matcher()
    m.load(ixs)
    rng = np.random.default_rng(7)

    target = db.get_set(a.sets[0])
    cards = [c for c in target.cards if c.img]
    sample = [cards[i] for i in rng.choice(len(cards), min(a.n, len(cards)), replace=False)]
    imgs = color_images(sample)
    pool = list(imgs.values())[:20]
    items = [(k, v) for k, v in imgs.items()]
    run(m, items, rng, pool, a.hard, f'{a.sets[0]} in {"+".join(a.sets)}')

    neg = db.get_set(a.neg)
    if neg is not None:
        ncards = [c for c in neg.cards if c.img]
        nsample = [ncards[i] for i in rng.choice(len(ncards), min(60, len(ncards)), replace=False)]
        nimgs = color_images(nsample)
        run(m, [(None, v) for v in nimgs.values()], rng, pool, a.hard, f'NOT-IN-INDEX ({a.neg})')


if __name__ == '__main__':
    main()
