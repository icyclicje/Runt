"""Things that aren't cards, held up to the phone scanner: it must not scan them, and it
must not draw a card outline on them.

Writes <cache>/android_bench/<name>/NNN/ sequences like export_android_bench.py, with
truth.json {"key": "", "kind": ...}. Objects: booster packs (their art is on cards of the
loaded set), posters of a loaded card's artwork without the card around it, a printed
checklist (card names and numbers on paper), books, a phone, a keyboard, a card's back.

usage: python -m tests.export_android_negatives <cache> [--set sv3pt5] [--n 24] [--name negatives]
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.carddb import CardDB                      # noqa: E402
from tests import synth2                           # noqa: E402
from tests.bench_vision import color_images        # noqa: E402

SIZE = (1280, 720)
PACKS = {'sv3pt5': 504467, 'sv8pt5': 593294, 'me1': 644352, 'base1': 138130}
KINDS = ['pack', 'poster', 'checklist', 'book', 'phone', 'keyboard', 'cardback', 'pack', 'poster']


class ObjectLook:
    """Anything flat held in the hand: an image of any shape, with fingers and glare like a card."""

    def __init__(self, img_bgr, rng, alpha=None, height=900):
        h = height
        w = int(round(h * img_bgr.shape[1] / img_bgr.shape[0]))
        self.img = cv2.resize(img_bgr, (w, h), interpolation=cv2.INTER_AREA).astype(np.float32)
        self.alpha = np.ones((h, w), np.float32) if alpha is None else cv2.resize(alpha, (w, h)).astype(np.float32)
        self.aspect = w / h
        self.holo = self.reverse = False
        self.holo_phase, self.holo_amp = 0.0, 0.0
        self.sleeve = 'none'
        self.fingers = []
        for _ in range(int(rng.choice([0, 1, 1, 2]))):
            self.fingers.append((int(rng.integers(4)), rng.uniform(0.2, 0.8), rng.uniform(0.12, 0.2), rng.uniform(0.08, 0.2),
                                 rng.uniform([95, 125, 170], [150, 175, 230])))
        self.glare = rng.random() < 0.4
        self.glare_pos = rng.uniform(0.2, 0.8, 2)
        self.glare_size = rng.uniform(0.12, 0.25)


def pose(center, height, aspect, angle_deg, jitter):
    w = height * aspect
    base = np.array([[-w / 2, -height / 2], [w / 2, -height / 2], [w / 2, height / 2], [-w / 2, height / 2]])
    base = base + np.asarray(jitter) * [w, height]
    t = math.radians(angle_deg)
    rot = np.array([[math.cos(t), -math.sin(t)], [math.sin(t), math.cos(t)]])
    return (base @ rot.T + np.asarray(center)).astype(np.float32)


def rounded_alpha(h, w, r):
    a = np.zeros((h, w), np.float32)
    cv2.rectangle(a, (r, 0), (w - 1 - r, h - 1), 1.0, -1)
    cv2.rectangle(a, (0, r), (w - 1, h - 1 - r), 1.0, -1)
    for cx, cy in ((r, r), (w - 1 - r, r), (r, h - 1 - r), (w - 1 - r, h - 1 - r)):
        cv2.circle(a, (cx, cy), r, 1.0, -1)
    return a


def text(img, s, x, y, scale, color, thick=2):
    cv2.putText(img, s, (int(x), int(y)), cv2.FONT_HERSHEY_SIMPLEX, scale, color, thick, cv2.LINE_AA)


def make_pack(rng, cache):
    sid = rng.choice(list(PACKS))
    img = cv2.imread(os.path.join(cache, 'neg_src', f'pack_{PACKS[sid]}.jpg'))
    # crimped ends: zig-zag alpha at top and bottom
    h, w = img.shape[:2]
    a = np.ones((h, w), np.float32)
    for x in range(w):
        d = int(6 + 6 * abs(((x / 14) % 2) - 1))
        a[:d, x] = 0
        a[h - d:, x] = 0
    return img, a, f'pack {sid}'


def make_poster(rng, cards):
    key, img = cards[int(rng.integers(len(cards)))]
    h, w = img.shape[:2]
    art = img[int(h * 0.11):int(h * 0.52), int(w * 0.08):int(w * 0.92)]     # the artwork, no card frame
    if rng.random() < 0.5:                                                   # full-art card: crop the middle
        art = img[int(h * 0.12):int(h * 0.80), int(w * 0.05):int(w * 0.95)]
    aspect = rng.choice([0.75, 1.0, 1.33])
    ah, aw = art.shape[:2]
    if aw / ah > aspect:                                                     # crop to the poster's shape
        nw = int(ah * aspect); x0 = (aw - nw) // 2; art = art[:, x0:x0 + nw]
    else:
        nh = int(aw / aspect); y0 = (ah - nh) // 2; art = art[y0:y0 + nh]
    art = cv2.resize(art, (int(900 * aspect), 900), interpolation=cv2.INTER_CUBIC)
    m = 30
    out = np.full((art.shape[0] + 2 * m, art.shape[1] + 2 * m, 3), 245, np.uint8)
    out[m:-m, m:-m] = art
    return out, None, f'poster of {key}'


def make_checklist(rng, set_cards):
    w, h = 850, 1100
    img = np.full((h, w, 3), 250, np.uint8)
    text(img, 'Scarlet & Violet 151 checklist', 60, 90, 1.2, (20, 20, 20), 3)
    picks = [set_cards[int(i)] for i in rng.choice(len(set_cards), 22, replace=False)]
    for i, c in enumerate(picks):
        y = 160 + i * 42
        cv2.rectangle(img, (60, y - 22), (82, y), (40, 40, 40), 2)
        text(img, f'{c.number.zfill(3)}/165  {c.name}', 100, y, 0.85, (30, 30, 30), 2)
        text(img, c.rarity, 560, y, 0.7, (90, 90, 90), 1)
    return img, None, 'checklist'


def make_book(rng, art_pool):
    w, h = 660, 1000
    col = rng.uniform(30, 220, 3)
    img = np.full((h, w, 3), col, np.uint8)
    art = art_pool[int(rng.integers(len(art_pool)))]
    pic = cv2.resize(art[int(art.shape[0] * 0.1):int(art.shape[0] * 0.5)], (520, 380))
    img[300:680, 70:590] = pic
    ink = (255, 255, 255) if col.mean() < 128 else (20, 20, 20)
    text(img, rng.choice(['THE GUIDE', 'POCKET ATLAS', 'FIELD NOTES', 'COLLECTOR']), 60, 150, 1.9, ink, 5)
    text(img, 'Second Edition', 60, 220, 1.0, ink, 2)
    text(img, 'A. Author', 60, 900, 1.1, ink, 2)
    return img, None, 'book'


def make_phone(rng):
    w, h = 470, 1000
    img = np.full((h, w, 3), 18, np.uint8)
    scr = np.full((h - 60, w - 30, 3), rng.uniform(200, 250), np.uint8)
    for _ in range(9):
        x0, y0 = int(rng.integers(10, w - 120)), int(rng.integers(60, h - 160))
        cv2.rectangle(scr, (x0, y0), (x0 + int(rng.integers(60, 300)), y0 + int(rng.integers(20, 120))), rng.uniform(40, 230, 3).tolist(), -1)
    for i in range(6):
        text(scr, rng.choice(['Messages', 'Settings', 'Photos', 'Weather 21C', 'Calendar', 'Mail (3)']), 30, 40 + i * 150, 0.9, (40, 40, 40), 2)
    img[30:30 + scr.shape[0], 15:15 + scr.shape[1]] = scr
    return img, rounded_alpha(h, w, 60), 'phone'


def make_keyboard(rng):
    w, h = 1300, 480
    img = np.full((h, w, 3), 35, np.uint8)
    letters = 'QWERTYUIOPASDFGHJKLZXCVBNM1234567890'
    for r in range(5):
        for k in range(14):
            x0, y0 = 20 + k * 91, 20 + r * 91
            cv2.rectangle(img, (x0, y0), (x0 + 80, y0 + 80), (70, 70, 72), -1)
            text(img, letters[(r * 14 + k) % len(letters)], x0 + 25, y0 + 52, 1.2, (220, 220, 220), 2)
    return img, rounded_alpha(h, w, 20), 'keyboard'


def make_cardback(rng):
    w, h = 630, 880
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    swirl = np.sin(np.hypot(x - w / 2, y - h / 2) / 18 + np.arctan2(y - h / 2, x - w / 2) * 3)
    img = np.zeros((h, w, 3), np.float32)
    img[:] = (170, 80, 30)
    img += swirl[:, :, None] * np.float32([40, 30, 10])
    m = 28
    img[:m], img[-m:], img[:, :m], img[:, -m:] = (40, 130, 170), (40, 130, 170), (40, 130, 170), (40, 130, 170)
    cv2.circle(img, (w // 2, h // 2), 150, (40, 40, 220), -1)
    cv2.rectangle(img, (w // 2 - 150, h // 2), (w // 2 + 150, h // 2 + 150), (240, 240, 240), -1)
    cv2.circle(img, (w // 2, h // 2), 150, (20, 20, 20), 8)
    cv2.line(img, (w // 2 - 150, h // 2), (w // 2 + 150, h // 2), (20, 20, 20), 10)
    cv2.circle(img, (w // 2, h // 2), 45, (240, 240, 240), -1)
    cv2.circle(img, (w // 2, h // 2), 45, (20, 20, 20), 8)
    return np.clip(img, 0, 255).astype(np.uint8), rounded_alpha(h, w, 28), 'card back'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('--set', default='sv3pt5', help='a set the bench loads: its art on posters must not scan')
    ap.add_argument('--n', type=int, default=24)
    ap.add_argument('--name', default='negatives')
    ap.add_argument('--seed', type=int, default=11)
    a = ap.parse_args()

    rng = np.random.default_rng(a.seed)
    db = CardDB(a.cache)
    db.load(refresh='never')
    set_cards = [c for c in db.get_set(a.set).cards if not c.no_image]
    rares = [c for c in set_cards if c.is_hit]
    art_cards = color_images([rares[int(i)] for i in rng.choice(len(rares), 8, replace=False)], large=True)
    cards = list(art_cards.items())
    other = [c for c in db.get_set('sv4').cards if not c.no_image]
    art_pool = list(color_images([other[int(i)] for i in rng.choice(len(other), 12, replace=False)]).values())

    root = os.path.join(a.cache, 'android_bench', a.name)
    os.makedirs(root, exist_ok=True)
    for n in range(a.n):
        kind = KINDS[n % len(KINDS)]
        img, alpha, what = {
            'pack': lambda: make_pack(rng, a.cache),
            'poster': lambda: make_poster(rng, cards),
            'checklist': lambda: make_checklist(rng, set_cards),
            'book': lambda: make_book(rng, art_pool),
            'phone': lambda: make_phone(rng),
            'keyboard': lambda: make_keyboard(rng),
            'cardback': lambda: make_cardback(rng),
        }[kind]()
        look = ObjectLook(img, rng, alpha)
        scene = synth2.Scene(rng, size=SIZE, art_pool=art_pool)
        scene.lowres = False
        path = synth2.handheld_path(rng, SIZE, hold_frames=24)
        # held in the scanner's guide like a card, filling most of it
        size_h = rng.uniform(0.5, 0.8) * SIZE[0] if look.aspect < 1.1 else rng.uniform(0.35, 0.55) * SIZE[0]
        cx, cy = rng.uniform(0.44, 0.56) * SIZE[1], rng.uniform(0.44, 0.52) * SIZE[0]
        held0 = next(i for i, p in enumerate(path) if p[5])
        c0 = path[held0][0]
        d = os.path.join(root, f'{n:03d}')
        os.makedirs(d, exist_ok=True)
        held, prev = [], None
        for i, (cen, _, ang, persp, tilt, h) in enumerate(path):
            q = pose((cx + (cen[0] - c0[0]), cy + (cen[1] - c0[1])), size_h, look.aspect, ang, persp * 0.6)
            vel = (0, 0) if prev is None else tuple(q.mean(0) - prev.mean(0))
            prev = q
            frame = scene.render([(look, q, vel, tilt)], t=i / 20)
            cv2.imwrite(os.path.join(d, f'{i:03d}.jpg'), frame, [cv2.IMWRITE_JPEG_QUALITY, 88])
            held.append(bool(h))
        for j in range(6):
            frame = scene.render([], t=(len(path) + j) / 20)
            cv2.imwrite(os.path.join(d, f'{len(path) + j:03d}.jpg'), frame, [cv2.IMWRITE_JPEG_QUALITY, 88])
            held.append(False)
        with open(os.path.join(d, 'truth.json'), 'w', encoding='utf-8') as f:
            json.dump({'key': '', 'set': '', 'number': '', 'name': what, 'rarity': kind, 'kind': kind, 'held': held}, f)
        print(n, what)
    print('wrote', a.n, 'sequences to', root)


if __name__ == '__main__':
    main()
