"""Synthetic camera frames of a card, for testing the recogniser without a camera.

Each frame puts a real card scan into a random scene: perspective tilt, any
rotation, busy or plain backgrounds, uneven light, glare, fingers over the
edges, blur, sensor noise, JPEG artefacts, and a monochrome sensor with its own
colour response (like the Steam Frame's cameras).
"""
from __future__ import annotations

import cv2
import numpy as np


def _background(rng, h, w, pool):
    kind = rng.integers(0, 4)
    if kind == 0:                                  # smooth noise (table / cloth)
        n = rng.normal(0, 1, (h // 16 + 1, w // 16 + 1, 3)).astype(np.float32)
        bg = cv2.resize(n, (w, h), interpolation=cv2.INTER_CUBIC)
        bg = (bg * rng.uniform(15, 45) + rng.uniform(40, 200, 3)).clip(0, 255)
    elif kind == 1:                                # wood-like stripes
        x = np.linspace(0, rng.uniform(10, 40), w, dtype=np.float32)
        s = (np.sin(x)[None, :] * 0.5 + 0.5) * rng.uniform(20, 60)
        bg = np.repeat(s[:, :, None], 3, 2).repeat(h, 0) + rng.uniform(60, 160, 3)
    elif kind == 2 and pool:                       # other cards scattered around (hard case)
        bg = np.full((h, w, 3), rng.uniform(20, 120), np.float32)
        for _ in range(3):
            im = pool[rng.integers(len(pool))]
            sc = rng.uniform(1.0, 2.2)
            im = cv2.resize(im, None, fx=sc, fy=sc)
            y, x = rng.integers(0, max(1, h - 50)), rng.integers(0, max(1, w - 50))
            ph, pw = min(im.shape[0], h - y), min(im.shape[1], w - x)
            bg[y:y + ph, x:x + pw] = im[:ph, :pw] * 0.7
    else:                                          # plain
        bg = np.full((h, w, 3), rng.uniform(10, 245, 3), np.float32)
    return bg.astype(np.float32)


def scene(card_bgr: np.ndarray, rng: np.random.Generator, pool=(), size=(720, 1280), hard=False):
    """Returns (grey frame uint8, true corner quad)."""
    h, w = size
    bg = _background(rng, h, w, pool)
    ch, cw = card_bgr.shape[:2]
    card_h = rng.uniform(0.38, 0.85) * h
    card_w = card_h * (63 / 88)
    ang = rng.uniform(-25, 25) + (rng.choice([0, 90, 180, 270]) if hard else rng.choice([0, 0, 0, 180]))
    cx = rng.uniform(card_w * 0.6, w - card_w * 0.6)
    cy = rng.uniform(h * 0.45, h * 0.55)
    if ang % 180 > 45 and ang % 180 < 135:          # sideways: keep it inside the frame
        card_h = min(card_h, w * 0.6 / (63 / 88))
        card_w = card_h * (63 / 88)
        card_h = min(card_h, h * 0.9 * (63 / 88) / (63 / 88))
    base = np.array([[-card_w / 2, -card_h / 2], [card_w / 2, -card_h / 2], [card_w / 2, card_h / 2], [-card_w / 2, card_h / 2]])
    jitter = rng.uniform(-0.07, 0.07, (4, 2)) * [card_w, card_h]
    t = np.deg2rad(ang)
    rot = np.array([[np.cos(t), -np.sin(t)], [np.sin(t), np.cos(t)]])
    dst = (base + jitter) @ rot.T
    # keep the whole card in view (a person scanning holds it inside the frame)
    lo, hi = dst.min(0), dst.max(0)
    span = hi - lo
    fit = min(1.0, (w * 0.96) / span[0], (h * 0.96) / span[1])
    dst *= fit
    lo, hi = dst.min(0), dst.max(0)
    cx = float(np.clip(cx, -lo[0] + w * 0.02, w * 0.98 - hi[0]))
    cy = float(np.clip(cy, -lo[1] + h * 0.02, h * 0.98 - hi[1]))
    dst = dst + [cx, cy]
    card_w *= fit
    src = np.float32([[0, 0], [cw - 1, 0], [cw - 1, ch - 1], [0, ch - 1]])
    m = cv2.getPerspectiveTransform(src, dst.astype(np.float32))
    card = card_bgr.astype(np.float32)
    warped = cv2.warpPerspective(card, m, (w, h), flags=cv2.INTER_LINEAR)
    mask = cv2.warpPerspective(np.ones((ch, cw), np.float32), m, (w, h))[:, :, None]
    img = bg * (1 - mask) + warped * mask

    # uneven light + exposure
    gy, gx = np.mgrid[0:h, 0:w].astype(np.float32)
    a, b = rng.uniform(-1, 1, 2)
    light = 1 + rng.uniform(0.1, 0.45) * (a * (gx / w - 0.5) + b * (gy / h - 0.5))
    img = img * light[:, :, None] * rng.uniform(0.6, 1.25)
    # glare spot
    if rng.random() < 0.5:
        g = np.zeros((h, w), np.float32)
        p = dst[rng.integers(4)] * 0.4 + np.array([cx, cy]) * 0.6
        cv2.ellipse(g, (int(p[0]), int(p[1])), (int(rng.uniform(20, 90)), int(rng.uniform(10, 50))),
                    rng.uniform(0, 180), 0, 360, 1.0, -1)
        g = cv2.GaussianBlur(g, (0, 0), 15) * rng.uniform(0.3, 0.8)
        img = img * (1 - g[:, :, None]) + 255 * g[:, :, None]
    # fingers over the edge
    for _ in range(rng.integers(0, 3)):
        e = rng.integers(4)
        p = dst[e] + (dst[(e + 1) % 4] - dst[e]) * rng.uniform(0.15, 0.85)
        col = rng.uniform([90, 120, 170], [150, 170, 230])
        cv2.ellipse(img, (int(p[0]), int(p[1])), (int(card_w * 0.09), int(card_w * 0.22)), rng.uniform(0, 180), 0, 360, col.tolist(), -1)
    # monochrome sensor with its own colour response (IR-ish cameras weigh red heavily)
    wts = rng.dirichlet([2, 2, 2]) if rng.random() < 0.6 else np.array([0.114, 0.587, 0.299])
    gray = (img @ wts.astype(np.float32))
    gamma = rng.uniform(0.75, 1.35)
    gray = 255 * (np.clip(gray, 0, 255) / 255) ** gamma
    # blur, noise, compression
    s = rng.uniform(0, 1.6 if not hard else 2.4)
    if s > 0.3:
        gray = cv2.GaussianBlur(gray, (0, 0), s)
    gray = gray + rng.normal(0, rng.uniform(1.5, 7 if not hard else 11), gray.shape)
    gray = np.clip(gray, 0, 255).astype(np.uint8)
    if rng.random() < 0.4:                         # lower-resolution camera
        gray = cv2.resize(cv2.resize(gray, (640, 360), interpolation=cv2.INTER_AREA), (w, h))
    ok, enc = cv2.imencode('.jpg', gray, [cv2.IMWRITE_JPEG_QUALITY, int(rng.uniform(45, 90))])
    gray = cv2.imdecode(enc, cv2.IMREAD_GRAYSCALE)
    return gray, dst.astype(np.float32)
