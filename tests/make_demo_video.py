"""Makes a test video of cards being brought up to a camera, one at a time.

usage: python -m tests.make_demo_video <cache_dir> <set_id> <out.mp4> [--cards 11] [--hard]

Each card comes into view, is held ~1.2 s (with hand tremor), and is taken away
(see synth2.py). After the distinct cards, the first card is shown again (it is
then owned: a duplicate) and the last card again (already in the current pack:
must be skipped). The expected order is written to <out.mp4>.txt.
"""
from __future__ import annotations

import argparse
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.carddb import CardDB               # noqa: E402
from tests import synth2                    # noqa: E402
from tests.bench_vision import color_images  # noqa: E402

FPS = 20


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('set_id')
    ap.add_argument('out')
    ap.add_argument('--cards', type=int, default=11)
    ap.add_argument('--hard', action='store_true')
    ap.add_argument('--other', default='swsh12pt5', help='set whose art decorates the playmat')
    a = ap.parse_args()

    db = CardDB(a.cache)
    db.load(refresh='never')
    rng = np.random.default_rng(11)
    cards = [c for c in db.get_set(a.set_id).cards if c.img]
    imgs = color_images([cards[i] for i in rng.choice(len(cards), a.cards, replace=False)])
    other = [c for c in db.get_set(a.other).cards if c.img][:6]
    art = list(color_images(other).values())
    order = list(imgs)
    order += [order[0], order[-1]]
    scene = synth2.Scene(rng, art_pool=art, hard=a.hard)
    w = cv2.VideoWriter(a.out, cv2.VideoWriter_fourcc(*'mp4v'), FPS, (scene.w, scene.h))
    t = 0
    for key in order:
        look = synth2.CardLook(imgs[key], rng, hard=a.hard)
        prev = None
        for c, hgt, ang, persp, tilt, _ in synth2.handheld_path(rng, (scene.h, scene.w), hold_frames=24, hard=a.hard):
            q = synth2.pose_quad(c, hgt, ang, persp)
            vel = (0, 0) if prev is None else tuple(q.mean(0) - prev.mean(0))
            prev = q
            w.write(cv2.cvtColor(scene.render([(look, q, vel, tilt)], t=t / FPS), cv2.COLOR_GRAY2BGR))
            t += 1
        for _ in range(int(FPS * 0.5)):
            w.write(cv2.cvtColor(scene.render([], t=t / FPS), cv2.COLOR_GRAY2BGR))
            t += 1
    w.release()
    with open(a.out + '.txt', 'w', encoding='utf-8') as f:
        f.write('\n'.join(order))
    print(f'wrote {a.out}: {len(order)} card showings ({len(set(order))} different), {t} frames')


if __name__ == '__main__':
    main()
