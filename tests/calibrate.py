"""Fits detector.strength_of: the probability that a sighting names the right card.

usage: python -m tests.calibrate <cache_dir> [--frames 900] [--seed 5]

Renders synthetic frames (normal and hard, cards in and out of the active sets,
cards lying on the table), records every sighting the detector makes with its
measurements, labels it right or wrong, and fits a logistic model per detection
method. Prints the coefficients to paste into detector.py, and how well the model
separates right from wrong sightings on frames it wasn't fitted on.
"""
from __future__ import annotations

import argparse
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.carddb import CardDB                       # noqa: E402
from app.detector import Detector, features_of      # noqa: E402
from app.refs import RefStore                       # noqa: E402
from tests import synth2                            # noqa: E402
from tests.bench_vision import color_images         # noqa: E402


def fit_logistic(X, y, l2=1e-2, iters=60):
    w = np.zeros(X.shape[1])
    for _ in range(iters):                          # Newton / IRLS
        p = 1 / (1 + np.exp(-X @ w))
        g = X.T @ (p - y) + l2 * w
        H = (X * (p * (1 - p))[:, None]).T @ X + l2 * np.eye(len(w))
        w -= np.linalg.solve(H, g)
    return w


def auc(p, y):
    order = np.argsort(p)
    r = np.empty(len(p))
    r[order] = np.arange(1, len(p) + 1)
    pos = y == 1
    n1, n0 = pos.sum(), (~pos).sum()
    return float((r[pos].sum() - n1 * (n1 + 1) / 2) / max(1, n1 * n0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('--frames', type=int, default=900)
    ap.add_argument('--seed', type=int, default=5)
    ap.add_argument('--sets', default='sv3pt5,base1')
    ap.add_argument('--neg', default='swsh12pt5,me1,sv8pt5')
    a = ap.parse_args()
    cv2.setNumThreads(4)
    db = CardDB(a.cache)
    db.load(refresh='never')
    store = RefStore(a.cache)
    sets = a.sets.split(',')
    det = Detector()
    det.load([store.load(s) or store.build(db.get_set(s)) for s in sets])
    rng = np.random.default_rng(a.seed)

    def sample(set_ids, n):
        cards = [c for s in set_ids for c in db.get_set(s).cards if c.img]
        return list(color_images([cards[i] for i in rng.choice(len(cards), min(n, len(cards)), replace=False)]).items())
    pos = sample(sets, 260)
    neg = sample(a.neg.split(','), 160)
    art = [v for _, v in neg[:8]]
    table_pool, pos = pos[:30], pos[30:]

    rows = {'kp': [], 'edge': []}
    for i in range(a.frames):
        hard = rng.random() < 0.4
        in_set = rng.random() < 0.65
        key, img = (pos if in_set else neg[8:])[int(rng.integers(len(pos) if in_set else len(neg) - 8))]
        table = [table_pool[int(j)] for j in rng.choice(len(table_pool), int(rng.integers(0, 3)), replace=False)] \
            if rng.random() < 0.35 else []
        scene = synth2.Scene(rng, art_pool=art, table_cards=[v for _, v in table], hard=hard)
        path = synth2.handheld_path(rng, (scene.h, scene.w), hard=hard)
        j = int(rng.integers(len(path)))
        c, hgt, ang, persp, tilt, _ = path[j]
        vel = tuple(np.subtract(path[j][0], path[max(0, j - 1)][0]))
        frame = scene.render([(synth2.CardLook(img, rng, hard=hard), synth2.pose_quad(c, hgt, ang, persp), vel, tilt)],
                             t=float(rng.uniform(0, 3)))
        ok_keys = ({key} if in_set else set()) | {k for k, _ in table}
        for d in det.analyze(frame, keep_all=True):
            src = 'kp' if d.source == 'kp' else 'edge'
            rows[src].append((features_of(d), float(d.key in ok_keys), i))
        if (i + 1) % 150 == 0:
            print(f'  {i + 1} frames: {len(rows["kp"])} keypoint sightings, {len(rows["edge"])} outline sightings', flush=True)

    np.savez(os.path.join(a.cache, f'calib_{a.seed}.npz'),
             **{f'{src}_{k}': np.array([r[i] for r in data]) for src, data in rows.items() for i, k in enumerate(('X', 'y', 'frame'))})
    for src, data in rows.items():
        X = np.array([r[0] for r in data])
        y = np.array([r[1] for r in data])
        fr = np.array([r[2] for r in data])
        train = fr % 4 != 0                          # every 4th frame held out
        w = fit_logistic(X[train], y[train])
        p = 1 / (1 + np.exp(-X[~train] @ w))
        yt = y[~train]
        print(f'\n[{src}] {len(y)} sightings ({int(y.sum())} right). weights: {np.round(w, 3).tolist()}')
        print(f'   held-out AUC {auc(p, yt):.4f}')
        for t in (0.5, 0.8, 0.9, 0.97):
            keep = p >= t
            print(f'   P>={t}: keeps {keep[yt == 1].mean():.1%} of right sightings, {int(keep[yt == 0].sum())} of '
                  f'{int((yt == 0).sum())} wrong ones')


if __name__ == '__main__':
    main()
