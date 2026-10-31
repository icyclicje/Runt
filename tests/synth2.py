"""Synthetic camera footage of cards, for testing the scanner without a camera.

A Scene has a fixed background (table, cloth, playmat, clutter), optional cards
lying on the table, and a camera model (resolution, focus, noise, exposure,
compression, black-and-white sensor with its own colour response). Cards are
rendered at any pose with realistic trouble: fingers that move with the card,
holo shimmer, glossy glare, sleeves and toploaders, and motion blur from movement.
"""
from __future__ import annotations

import math

import cv2
import numpy as np

ASPECT = 63 / 88


# ---------------------------------------------------------------- card appearance

class CardLook:
    """One physical card: its image plus the things that make it hard to read."""

    def __init__(self, img_bgr: np.ndarray, rng: np.random.Generator, hard: bool = False):
        h = 420
        w = int(round(h * ASPECT))
        self.img = cv2.resize(img_bgr, (w, h), interpolation=cv2.INTER_CUBIC).astype(np.float32)
        a = np.ones((h, w), np.float32)
        r = int(w * 0.045)                                  # rounded corners
        a[:r, :r] = a[:r, w - r:] = a[h - r:, :r] = a[h - r:, w - r:] = 0
        for cx, cy in ((r, r), (w - 1 - r, r), (r, h - 1 - r), (w - 1 - r, h - 1 - r)):
            cv2.circle(a, (cx, cy), r, 1.0, -1)
        self.alpha = a
        self.holo = rng.random() < (0.45 if hard else 0.3)
        self.reverse = rng.random() < 0.25
        self.holo_phase = rng.uniform(0, 6.28)
        self.holo_amp = rng.uniform(0.06, 0.2 if hard else 0.12)   # foil shimmer strength
        self.sleeve = rng.choice(['none', 'none', 'none', 'penny', 'toploader'] if not hard else ['none', 'penny', 'toploader'])
        n_fingers = rng.choice([0, 1, 1, 2, 2, 3] if hard else [0, 1, 1, 2])
        self.fingers = []
        for _ in range(n_fingers):
            edge = int(rng.integers(4))
            self.fingers.append((edge, rng.uniform(0.2, 0.8), rng.uniform(0.12, 0.2), rng.uniform(0.08, 0.2),
                                 rng.uniform([95, 125, 170], [150, 175, 230])))
        self.glare = rng.random() < (0.6 if hard else 0.4)
        self.glare_pos = rng.uniform(0.2, 0.8, 2)
        self.glare_size = rng.uniform(0.12, 0.35 if hard else 0.25)


def _holo_pattern(w, h, t, phase):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    ang = 0.7 + 0.3 * math.sin(t * 0.8 + phase)
    bands = np.sin((x * math.cos(ang) + y * math.sin(ang)) / 9.0 + t * 3.0 + phase)
    return bands


def card_texture(look: CardLook, t: float, tilt: float) -> tuple[np.ndarray, np.ndarray]:
    """The card's face (BGR float, in card pixels) with shimmer, glare, sleeve and fingers drawn in."""
    img = look.img.copy()
    h, w = img.shape[:2]
    alpha = look.alpha.copy()
    if look.holo or look.reverse:
        pat = _holo_pattern(w, h, t, look.holo_phase)[:, :, None]
        mask = np.zeros((h, w, 1), np.float32)
        art = (slice(int(h * 0.1), int(h * 0.5)), slice(int(w * 0.07), int(w * 0.93)))
        if look.holo:
            mask[art] = 1
        if look.reverse:
            inv = np.ones((h, w, 1), np.float32)
            inv[art] = 0
            mask = np.maximum(mask, inv)
        img = img * (1 + look.holo_amp * pat * mask) + 110 * look.holo_amp * pat * mask
    if look.glare:
        g = np.zeros((h, w), np.float32)
        gx = (look.glare_pos[0] + 0.25 * math.sin(tilt * 3 + t)) * w
        gy = (look.glare_pos[1] + 0.15 * math.cos(tilt * 2)) * h
        cv2.ellipse(g, (int(gx), int(gy)), (int(w * look.glare_size), int(h * look.glare_size * 0.35)),
                    30 + 40 * math.sin(tilt), 0, 360, 1.0, -1)
        g = cv2.GaussianBlur(g, (0, 0), w * 0.06) * 0.85
        img = img * (1 - g[:, :, None]) + 255 * g[:, :, None]
    if look.sleeve != 'none':
        sheen = np.linspace(0, 1, w, dtype=np.float32)[None, :] * np.linspace(1, 0.3, h, dtype=np.float32)[:, None]
        img = img * (1 - 0.12 * sheen[:, :, None]) + 255 * 0.12 * sheen[:, :, None]
    # fingers (drawn on a padded canvas so they can hang over the card's edge)
    pad = int(w * 0.3)
    big = np.zeros((h + 2 * pad, w + 2 * pad, 3), np.float32)
    big_a = np.zeros((h + 2 * pad, w + 2 * pad), np.float32)
    big[pad:pad + h, pad:pad + w] = img
    big_a[pad:pad + h, pad:pad + w] = alpha
    if look.sleeve == 'toploader':
        # a clear rigid holder a little bigger than the card: faint plastic plus bright edges
        m = int(w * 0.05)
        ys, xs = slice(pad - m, pad + h + m), slice(pad - m, pad + w + m)
        outside = big_a[ys, xs] < 0.5
        big[ys, xs][outside] = (205, 208, 212)
        big_a[ys, xs][outside] = 0.22
        cv2.rectangle(big, (pad - m, pad - m), (pad + w + m, pad + h + m), (230, 232, 235), 3)
        cv2.rectangle(big_a, (pad - m, pad - m), (pad + w + m, pad + h + m), 0.9, 3)
    for edge, pos, fw, depth, col in look.fingers:
        if edge == 0:
            c = (pad + pos * w, pad + depth * h * 0.4)
            ang = 90
        elif edge == 1:
            c = (pad + w - depth * w * 0.6, pad + pos * h)
            ang = 0
        elif edge == 2:
            c = (pad + pos * w, pad + h - depth * h * 0.4)
            ang = 90
        else:
            c = (pad + depth * w * 0.6, pad + pos * h)
            ang = 0
        axes = (int(w * 0.35), int(w * fw * 0.5))
        fm = np.zeros(big_a.shape, np.float32)
        cv2.ellipse(fm, (int(c[0]), int(c[1])), axes, ang, 0, 360, 1.0, -1)
        shade = cv2.GaussianBlur(fm, (0, 0), w * 0.03)
        fm = cv2.GaussianBlur(fm, (0, 0), 1.5)
        skin = np.float32(col)[None, None, :] * (0.75 + 0.25 * shade[:, :, None])
        big = big * (1 - fm[:, :, None]) + skin * fm[:, :, None]
        big_a = np.maximum(big_a, fm)
    return big, big_a, pad


# ---------------------------------------------------------------- scene & camera

class Scene:
    def __init__(self, rng: np.random.Generator, size=(720, 1280), art_pool=(), table_cards=(), hard=False, top_down=False):
        self.rng = rng
        self.h, self.w = size
        self.hard = hard
        self.bg = self._background(art_pool)
        self.table: list[tuple[np.ndarray, np.ndarray]] = []    # (look, quad) of cards lying around
        for img in table_cards:
            look = CardLook(img, rng)
            look.fingers, look.glare = [], False
            hgt = rng.uniform(0.22, 0.35) * self.h
            for _ in range(20):
                c = (rng.uniform(0.1, 0.9) * self.w, rng.uniform(0.15, 0.85) * self.h)
                q = pose_quad(c, hgt, rng.uniform(-40, 40), rng.uniform(-0.05, 0.05, (4, 2)))
                if all(_quad_dist(q, o) > hgt * 0.75 for _, o in self.table):
                    self.table.append((look, q))
                    break
        # camera model
        self.mono = rng.dirichlet([2, 2, 2]).astype(np.float32) if rng.random() < 0.6 else np.float32([0.114, 0.587, 0.299])
        self.gamma = rng.uniform(0.75, 1.3)
        self.noise = rng.uniform(1.5, 6 if not hard else 10)
        self.focus = rng.uniform(0, 1.2 if not hard else 2.0)
        self.lowres = rng.random() < (0.3 if not hard else 0.5)
        self.jpeg = int(rng.uniform(50, 90))
        self.vignette = rng.uniform(0, 0.35)
        self.exposure = rng.uniform(0.7, 1.2)
        gy, gx = np.mgrid[0:self.h, 0:self.w].astype(np.float32)
        a, b = rng.uniform(-1, 1, 2)
        light = 1 + rng.uniform(0.05, 0.4) * (a * (gx / self.w - 0.5) + b * (gy / self.h - 0.5))
        r = np.hypot(gx / self.w - 0.5, gy / self.h - 0.5) / 0.7
        self.light = (light * (1 - self.vignette * r ** 2)).astype(np.float32)

    def _background(self, art_pool):
        rng, h, w = self.rng, self.h, self.w
        kinds = ['wood', 'cloth', 'plain', 'playmat', 'clutter'] + (['noise'] if self.hard else [])
        kind = rng.choice(kinds)
        if kind == 'wood':
            y, x = np.mgrid[0:h, 0:w].astype(np.float32)
            ring = np.sin((x + 40 * np.sin(y / rng.uniform(60, 140))) / rng.uniform(6, 14))
            base = rng.uniform(70, 160, 3)
            bg = base[None, None, :] * (0.85 + 0.15 * ring[:, :, None])
        elif kind == 'cloth' or kind == 'noise':
            n = rng.normal(0, 1, (h // (16 if kind == 'cloth' else 6) + 1, w // (16 if kind == 'cloth' else 6) + 1, 1)).astype(np.float32)
            n = cv2.resize(n, (w, h), interpolation=cv2.INTER_CUBIC)
            if n.ndim == 2:
                n = n[:, :, None]
            bg = rng.uniform(40, 190, 3)[None, None, :] + n * rng.uniform(10, 35 if kind == 'cloth' else 50)
        elif kind == 'playmat' and len(art_pool):
            art = art_pool[rng.integers(len(art_pool))].astype(np.float32)
            bg = cv2.resize(art, (w, h), interpolation=cv2.INTER_CUBIC)
            bg = cv2.GaussianBlur(bg, (0, 0), 3) * 0.55 + 20
        elif kind == 'clutter':
            bg = np.full((h, w, 3), rng.uniform(60, 150, 3), np.float32)
            for _ in range(int(rng.integers(4, 10))):
                x0, y0 = rng.integers(0, w), rng.integers(0, h)
                cv2.rectangle(bg, (int(x0), int(y0)), (int(x0 + rng.integers(40, 400)), int(y0 + rng.integers(30, 250))),
                              rng.uniform(20, 240, 3).tolist(), -1)
            bg = cv2.GaussianBlur(bg, (0, 0), 1.2)
        else:
            bg = np.full((h, w, 3), rng.uniform(15, 240, 3), np.float32)
        return np.clip(bg, 0, 255).astype(np.float32)

    def render(self, cards, t: float = 0.0) -> np.ndarray:
        """cards: [(CardLook, quad 4x2 in frame px, velocity (dx, dy) px/frame, tilt)] -> grey frame."""
        if self.table and not getattr(self, '_table_drawn', False):
            for look, q in self.table:                           # cards lying still: drawn into the background once
                self._paste(self.bg, look, q, (0, 0), 0.0, 0.0)
            self._table_drawn = True
        img = self.bg.copy()
        for look, q, vel, tilt in cards:
            self._paste(img, look, q, vel, tilt, t)
        img = img * self.light[:, :, None] * self.exposure
        gray = img @ self.mono
        gray = 255 * (np.clip(gray, 0, 255) / 255) ** self.gamma
        if self.focus > 0.3:
            gray = cv2.GaussianBlur(gray, (0, 0), self.focus)
        gray = gray + self.rng.standard_normal(gray.shape, dtype=np.float32) * np.float32(self.noise)
        gray = np.clip(gray, 0, 255).astype(np.uint8)
        if self.lowres:
            gray = cv2.resize(cv2.resize(gray, (640, 360), interpolation=cv2.INTER_AREA), (self.w, self.h))
        ok, enc = cv2.imencode('.jpg', gray, [cv2.IMWRITE_JPEG_QUALITY, self.jpeg])
        return cv2.imdecode(enc, cv2.IMREAD_GRAYSCALE)

    def _paste(self, img, look, quad, vel, tilt, t):
        tex, alpha, pad = card_texture(look, t, tilt)
        ch, cw = look.img.shape[:2]
        src = np.float32([[pad, pad], [pad + cw - 1, pad], [pad + cw - 1, pad + ch - 1], [pad, pad + ch - 1]])
        m = cv2.getPerspectiveTransform(src, np.float32(quad))
        # work only inside the card's bounding box (fingers and blur included)
        corners = cv2.perspectiveTransform(np.float32([[0, 0], [tex.shape[1], 0], [tex.shape[1], tex.shape[0]],
                                                       [0, tex.shape[0]]]).reshape(-1, 1, 2), m).reshape(4, 2)
        speed = float(np.hypot(*vel))
        k = int(min(41, speed)) | 1 if speed > 1.5 else 0
        x0 = int(max(0, corners[:, 0].min() - k - 2))
        y0 = int(max(0, corners[:, 1].min() - k - 2))
        x1 = int(min(self.w, corners[:, 0].max() + k + 3))
        y1 = int(min(self.h, corners[:, 1].max() + k + 3))
        if x1 <= x0 or y1 <= y0:
            return
        shift = np.float64([[1, 0, -x0], [0, 1, -y0], [0, 0, 1]]) @ m
        size = (x1 - x0, y1 - y0)
        layer = cv2.warpPerspective(tex, shift, size, flags=cv2.INTER_LINEAR)
        a = cv2.warpPerspective(alpha, shift, size, flags=cv2.INTER_LINEAR)
        if k:                                                    # motion blur along the movement
            ker = np.zeros((k, k), np.float32)
            ker[k // 2, :] = 1.0 / k
            rot = cv2.getRotationMatrix2D((k / 2 - 0.5, k / 2 - 0.5), math.degrees(math.atan2(vel[1], vel[0])), 1.0)
            ker = cv2.warpAffine(ker, rot, (k, k))
            ker /= max(1e-6, ker.sum())
            layer = cv2.filter2D(layer * a[:, :, None], -1, ker)
            a = cv2.filter2D(a, -1, ker)
            layer = layer / np.maximum(a[:, :, None], 1e-3)
        roi = img[y0:y1, x0:x1]
        roi *= (1 - a[:, :, None])
        roi += layer * a[:, :, None]


def pose_quad(center, height, angle_deg, jitter=None) -> np.ndarray:
    w = height * ASPECT
    base = np.array([[-w / 2, -height / 2], [w / 2, -height / 2], [w / 2, height / 2], [-w / 2, height / 2]])
    if jitter is not None:
        base = base + np.asarray(jitter) * [w, height]
    t = math.radians(angle_deg)
    rot = np.array([[math.cos(t), -math.sin(t)], [math.sin(t), math.cos(t)]])
    return (base @ rot.T + np.asarray(center)).astype(np.float32)


def _quad_dist(a, b):
    return float(np.linalg.norm(a.mean(0) - b.mean(0)))


# ---------------------------------------------------------------- movement

def handheld_path(rng, size, hold_frames=24, hard=False):
    """Per-frame (center, height, angle, perspective jitter, tilt) of a card brought up to the
    camera, held (with hand tremor and drift), then taken away."""
    h, w = size
    height = rng.uniform(0.42, 0.8) * h if not hard else rng.uniform(0.28, 0.85) * h
    hold = np.array([rng.uniform(0.3, 0.7) * w, rng.uniform(0.42, 0.58) * h])
    ang0 = rng.uniform(-22, 22) + (rng.choice([0, 0, 0, 180]) if not hard else rng.choice([0, 90, 180, 270]))
    persp = rng.uniform(-0.07, 0.07, (4, 2)) * (1.6 if hard else 1.0)
    side = rng.integers(4)
    off = {0: (-0.6 * w, 0), 1: (0.6 * w, 0), 2: (0, 0.9 * h), 3: (0, -0.9 * h)}[int(side)]
    enter = int(rng.integers(5, 10))
    leave = int(rng.integers(5, 9))
    frames = []
    total = enter + hold_frames + leave
    drift = rng.normal(0, 1.2, 2)
    for i in range(total):
        if i < enter:
            f = 1 - (i + 1) / enter
            f = f * f
            c = hold + np.array(off) * f
        elif i < enter + hold_frames:
            j = i - enter
            c = hold + drift * j + rng.normal(0, 2.0 if not hard else 3.5, 2)
        else:
            f = (i - enter - hold_frames + 1) / leave
            c = frames[enter + hold_frames - 1][0] + np.array(off) * f * f
        ang = ang0 + 4 * math.sin(i / 9) + rng.normal(0, 0.6)
        tilt = math.sin(i / 7)
        frames.append((c, height, ang, persp + 0.02 * math.sin(i / 8), tilt, i >= enter and i < enter + hold_frames))
    return frames
