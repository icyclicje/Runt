"""Scanning benchmark on synthetic video: does each card get scanned, how fast, and does
anything get scanned that shouldn't?

usage: python -m tests.bench_seq <cache_dir> [--sets sv3pt5,base1] [--other swsh12pt5]
                                 [--n 40] [--hard] [--multi] [--sens balanced] [--mode auto] [--seed 1]

Each trial: a fixed scene (table, playmat, clutter...; sometimes cards already lying
there), then a card is brought up to the camera, held for ~1.2 s with hand tremor and
drift, and taken away. The real detector and tracker run on every frame (20 fps).
--multi instead lays 3-5 new cards on the table at once and checks all get scanned.
"""
from __future__ import annotations

import argparse
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.carddb import CardDB                      # noqa: E402
from app.config import SENSITIVITY                 # noqa: E402
from app.detector import Detector                  # noqa: E402
from app.refs import RefStore                      # noqa: E402
from app.tracker import MultiTracker               # noqa: E402
from tests import synth2                           # noqa: E402
from tests.bench_vision import color_images        # noqa: E402

FPS = 20


def run_trial(det, rng, target, table_in, table_out, art_pool, hard, sens, mode, multi_imgs=None):
    thr, min_s = SENSITIVITY[sens][:2]
    tr = MultiTracker()
    scene = synth2.Scene(rng, art_pool=art_pool, table_cards=[img for _, img in table_in + table_out], hard=hard)
    confirmed, times = [], []
    frames = []
    if multi_imgs is not None:                  # several new cards laid on the table together
        looks = []
        for k, img in multi_imgs:
            look = synth2.CardLook(img, rng, hard=hard)
            look.fingers = []
            looks.append(look)
        quads = []
        hgt = rng.uniform(0.3, 0.42) * scene.h
        xs = np.linspace(0.15, 0.85, len(looks)) * scene.w
        for i, look in enumerate(looks):
            c = (xs[i] + rng.normal(0, 10), scene.h * rng.uniform(0.35, 0.65))
            quads.append(synth2.pose_quad(c, hgt, rng.uniform(-25, 25), rng.uniform(-0.04, 0.04, (4, 2))))
        for i in range(36):
            frames.append(([(look, q, (0, 0), 0.3 * np.sin(i / 6)) for look, q in zip(looks, quads)], True))
    else:
        look = synth2.CardLook(target[1], rng, hard=hard)
        for c, hgt, ang, persp, tilt, held in synth2.handheld_path(rng, (scene.h, scene.w), hold_frames=24, hard=hard):
            frames.append(([(look, synth2.pose_quad(c, hgt, ang, persp), None, tilt)], held))
        for i in range(1, len(frames)):          # velocity from consecutive centres
            cur = frames[i][0][0]
            prev = frames[i - 1][0][0]
            frames[i] = ([(cur[0], cur[1], tuple(cur[1].mean(0) - prev[1].mean(0)), cur[3])], frames[i][1])
        frames[0] = ([(frames[0][0][0][0], frames[0][0][0][1], (0, 0), frames[0][0][0][3])], frames[0][1])
        frames += [([], False)] * 6
    first_held = next((i for i, (_, h) in enumerate(frames) if h), 0)
    trace = []
    for i, (cards, held) in enumerate(frames):
        img = scene.render(cards, t=i / FPS)
        t0 = time.perf_counter()
        dets = det.analyze(img, mode=mode)
        for key, d in tr.update(dets, i / FPS, thr, 0.5, 1.2, min_s):
            confirmed.append((key, i - first_held))
        times.append(time.perf_counter() - t0)
        if held and target is not None:
            mine = [d for d in dets if d.key == target[0]]
            others = [d for d in dets if d.key != target[0]]
            trace.append((max((d.strength for d in mine), default=0.0), mine[0] if mine else None, others[:1], img))
    run_trial.trace = trace
    return confirmed, times


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('--sets', default='sv3pt5,base1')
    ap.add_argument('--other', default='swsh12pt5')
    ap.add_argument('--n', type=int, default=40)
    ap.add_argument('--hard', action='store_true')
    ap.add_argument('--multi', action='store_true')
    ap.add_argument('--sens', default='balanced')
    ap.add_argument('--mode', default='auto')
    ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--verbose', action='store_true', help='explain misses and save a frame of each')
    a = ap.parse_args()
    cv2.setNumThreads(4)                         # same as the app

    db = CardDB(a.cache)
    db.load(refresh='never')
    store = RefStore(a.cache)
    sets = a.sets.split(',')
    ixs = [store.load(s) or store.build(db.get_set(s)) for s in sets]
    det = Detector()
    det.load(ixs)
    rng = np.random.default_rng(a.seed)

    in_cards = [c for c in db.get_set(sets[0]).cards if c.img]
    out_cards = [c for c in db.get_set(a.other).cards if c.img]
    pick_in = [in_cards[i] for i in rng.choice(len(in_cards), min(len(in_cards), a.n + 30), replace=False)]
    pick_out = [out_cards[i] for i in rng.choice(len(out_cards), 30, replace=False)]
    imgs_in = list(color_images(pick_in).items())
    imgs_out = list(color_images(pick_out).items())
    art_pool = [v for _, v in imgs_out[:10]]

    found = lat = false = dup = 0
    lats, all_times, misses = [], [], []
    expected_multi = got_multi = 0
    for trial in range(a.n):
        target = imgs_in[trial]
        table_in = [imgs_in[a.n + int(j)] for j in rng.choice(30, int(rng.integers(0, 3)), replace=False)] if rng.random() < 0.4 else []
        table_out = [imgs_out[10 + int(j)] for j in rng.choice(20, int(rng.integers(0, 3)), replace=False)] if rng.random() < 0.4 else []
        multi = None
        if a.multi:
            multi = [imgs_in[(trial * 5 + j) % len(imgs_in)] for j in range(int(rng.integers(3, 6)))]
            table_in = []
        conf, times = run_trial(det, rng, target, table_in, table_out, art_pool, a.hard, a.sens, a.mode, multi)
        all_times += times
        allowed = {k for k, _ in table_in} | ({k for k, _ in multi} if multi else {target[0]})
        keys = [k for k, _ in conf]
        bad = [k for k in keys if k not in allowed]
        false += len(bad)
        if multi:
            want = {k for k, _ in multi}
            got = want & set(keys)
            expected_multi += len(want)
            got_multi += len(got)
            if len(got) < len(want):
                misses.append(('multi', len(want) - len(got)))
        else:
            hits = [f for k, f in conf if k == target[0]]
            if hits:
                found += 1
                lats.append(hits[0])
                dup += len(hits) > 1
            else:
                misses.append(target[0])
                if a.verbose:
                    tr_ = run_trial.trace
                    st = np.array([x[0] for x in tr_])
                    best = max(tr_, key=lambda x: x[0])
                    other = next((x[2][0] for x in tr_ if x[2]), None)
                    print(f'   MISS {target[0]}: held {len(st)} frames, seen in {int((st > 0).sum())}, '
                          f'max strength {st.max():.2f}, mean {st.mean():.2f}; best {best[1]!r}; also saw {other!r}')
                    cv2.imwrite(os.path.join(a.cache, f'miss_{trial}.jpg'), tr_[len(tr_) // 2][3])
        for b in bad:
            print('   FALSE SCAN:', b, '(target', target[0], ')')
    ms = 1000 * np.array(all_times)
    label = f'{"multi" if a.multi else "handheld"}{" hard" if a.hard else ""} [{a.sens}, {a.mode}] sets={a.sets}'
    if a.multi:
        print(f'{label}: scanned {got_multi}/{expected_multi} cards laid out together ({got_multi / max(1, expected_multi):.1%}), '
              f'false scans {false}')
    else:
        L = np.array(lats) if lats else np.array([np.nan])
        print(f'{label}: scanned {found}/{a.n} ({found / a.n:.1%}), false scans {false}, double scans {dup}, '
              f'latency frames p50 {np.nanmedian(L):.0f} p90 {np.nanpercentile(L, 90):.0f}')
    print(f'   time per frame: mean {ms.mean():.1f} ms, p90 {np.percentile(ms, 90):.1f} ms, max {ms.max():.0f} ms')
    if misses:
        print('   missed:', misses[:12])


if __name__ == '__main__':
    main()
