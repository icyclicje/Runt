"""Makes a test video of cards being held up to a camera, one at a time.

usage: python -m tests.make_demo_video <cache_dir> <set_id> <out.mp4> [--cards 12] [--repeat 2]

Each card is shown for ~1.5 s (with a little hand shake), then the frame is
empty for ~0.6 s. With --repeat, the first card is shown again at the end,
to check the scanner doesn't count it twice.
"""
from __future__ import annotations

import argparse
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.carddb import CardDB               # noqa: E402
from tests.bench_vision import color_images  # noqa: E402
from tests.synth import scene                # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('set_id')
    ap.add_argument('out')
    ap.add_argument('--cards', type=int, default=12)
    ap.add_argument('--fps', type=int, default=20)
    a = ap.parse_args()

    db = CardDB(a.cache)
    db.load(refresh='never')
    cs = db.get_set(a.set_id)
    rng = np.random.default_rng(11)
    cards = [c for c in cs.cards if c.img]
    pick = [cards[i] for i in rng.choice(len(cards), a.cards, replace=False)]
    imgs = color_images(pick)
    order = [c.key for c in pick if c.key in imgs]
    # The first card again (now owned from an earlier pack: a duplicate), then the
    # last card again (already in the current pack: must be skipped).
    order += [order[0], order[-1]]
    w = cv2.VideoWriter(a.out, cv2.VideoWriter_fourcc(*'mp4v'), a.fps, (1280, 720))
    bg = np.full((720, 1280, 3), 70, np.uint8)
    for key in order:
        # one random pose per card, small shake frame to frame
        seed = int(rng.integers(1 << 30))
        for f in range(int(a.fps * 1.5)):
            r = np.random.default_rng(seed)
            frame, _ = scene(imgs[key], r, pool=())
            dx, dy = rng.normal(0, 2, 2)
            m = np.float32([[1, 0, dx], [0, 1, dy]])
            frame = cv2.warpAffine(frame, m, (1280, 720), borderMode=cv2.BORDER_REPLICATE)
            w.write(cv2.cvtColor(frame, cv2.COLOR_GRAY2BGR))
        for f in range(int(a.fps * 0.6)):
            w.write(bg)
    w.release()
    with open(a.out + '.txt', 'w', encoding='utf-8') as f:
        f.write('\n'.join(order))
    print(f'wrote {a.out}: {len(order)} card showings ({len(set(order))} different)')


if __name__ == '__main__':
    main()
