"""Test material for the Android app's recognition (run by android/core's JVM tests).

Writes, under <cache>/android_bench/:
  refs_<set>.bin     the set's reference images (grey, as the desktop scanner stores them)
  <name>/NNN/        synthetic phone-camera sequences: a card brought up, held, taken
                     away (portrait 720x1280 JPEGs) plus truth.json

usage: python -m tests.export_android_bench <cache> [--sets sv3pt5,base1] [--n 30] [--name normal]
                                            [--rare] [--hard] [--seed 3] [--other sv4]
"""
from __future__ import annotations

import argparse
import json
import os
import struct
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.carddb import CardDB                      # noqa: E402
from app.refs import RefStore                      # noqa: E402
from tests import synth2                           # noqa: E402
from tests.bench_vision import color_images        # noqa: E402

SIZE = (1280, 720)          # portrait phone frame (h, w)


def export_refs(out_dir, set_id, ix):
    path = os.path.join(out_dir, f'refs_{set_id}.bin')
    n, h, w = ix.ref.shape
    with open(path, 'wb') as f:
        f.write(struct.pack('>iii', n, h, w))
        for key, img in zip(ix.keys, ix.ref):
            k = str(key).encode('utf-8')
            f.write(struct.pack('>H', len(k)))
            f.write(k)
            f.write(np.ascontiguousarray(img, np.uint8).tobytes())
    print('wrote', path, n, 'cards')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('--sets', default='sv3pt5,base1')
    ap.add_argument('--n', type=int, default=30)
    ap.add_argument('--name', default='normal')
    ap.add_argument('--rare', action='store_true')
    ap.add_argument('--hard', action='store_true')
    ap.add_argument('--seed', type=int, default=3)
    ap.add_argument('--other', default='sv4', help='set for background clutter (must not be loaded in the bench)')
    ap.add_argument('--phone', action='store_true', help='hold the card the way the phone scanner asks: centred, filling most of the frame')
    a = ap.parse_args()

    out = os.path.join(a.cache, 'android_bench')
    os.makedirs(out, exist_ok=True)
    db = CardDB(a.cache)
    db.load(refresh='never')
    store = RefStore(a.cache)
    sets = a.sets.split(',')
    for s in sets:
        ix = store.load(s) or store.build(db.get_set(s))
        export_refs(out, s, ix)

    rng = np.random.default_rng(a.seed)
    target_set = db.get_set(sets[0])
    cards = [c for c in target_set.cards if not c.no_image]
    if a.rare:
        cards = [c for c in cards if c.is_hit or 'holo' in c.rarity.lower()]
    picks = [cards[int(i)] for i in rng.choice(len(cards), min(a.n, len(cards)), replace=False)]
    imgs = color_images(picks, large=True)      # high-res scans: the printed text must be legible, as through a phone camera
    other = [c for c in db.get_set(a.other).cards if not c.no_image]
    art_pool = list(color_images([other[int(i)] for i in rng.choice(len(other), 12, replace=False)]).values())
    seq_root = os.path.join(out, a.name)
    os.makedirs(seq_root, exist_ok=True)
    n = 0
    for c in picks:
        if c.key not in imgs:
            continue
        scene = synth2.Scene(rng, size=SIZE, art_pool=art_pool, hard=a.hard)
        look = synth2.CardLook(imgs[c.key], rng, hard=a.hard, tex_h=1000 if a.phone else 420)
        if a.phone:
            scene.lowres = False                    # phones capture at 1080p, not a 640x360 webcam
        path = synth2.handheld_path(rng, SIZE, hold_frames=24, hard=a.hard)
        if a.phone:
            # recentre and resize the held part: card 55-75% of the frame height, near the middle
            hgt = rng.uniform(0.55, 0.75) * SIZE[0]
            cx, cy = rng.uniform(0.44, 0.56) * SIZE[1], rng.uniform(0.44, 0.52) * SIZE[0]
            held0 = next(i for i, p in enumerate(path) if p[5])
            c0 = path[held0][0]
            path = [((cx + (c[0] - c0[0]), cy + (c[1] - c0[1])), hgt, ang if not a.hard else ang, persp * 0.6, tilt, h)
                    for c, _, ang, persp, tilt, h in path]
        d = os.path.join(seq_root, f'{n:03d}')
        os.makedirs(d, exist_ok=True)
        held, prev = [], None
        for i, (cen, hgt, ang, persp, tilt, h) in enumerate(path):
            q = synth2.pose_quad(cen, hgt, ang, persp)
            vel = (0, 0) if prev is None else tuple(q.mean(0) - prev.mean(0))
            prev = q
            img = scene.render([(look, q, vel, tilt)], t=i / 20)
            cv2.imwrite(os.path.join(d, f'{i:03d}.jpg'), img, [cv2.IMWRITE_JPEG_QUALITY, 88])
            held.append(bool(h))
        for j in range(6):                          # empty frames after the card leaves
            img = scene.render([], t=(len(path) + j) / 20)
            cv2.imwrite(os.path.join(d, f'{len(path) + j:03d}.jpg'), img, [cv2.IMWRITE_JPEG_QUALITY, 88])
            held.append(False)
        with open(os.path.join(d, 'truth.json'), 'w', encoding='utf-8') as f:
            json.dump({'key': c.key, 'set': c.set_id, 'number': c.number, 'name': c.name, 'rarity': c.rarity,
                       'held': held}, f)
        n += 1
    print('wrote', n, 'sequences to', seq_root)


if __name__ == '__main__':
    main()
