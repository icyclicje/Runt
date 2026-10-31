"""Card detection and recognition.

Pipeline for every camera frame:
  1. find the card's outline (a 4-sided shape with a card's proportions),
  2. straighten it into a fixed 200x280 greyscale image ("canon"),
  3. turn that into a fingerprint vector that ignores lighting changes,
  4. compare against the fingerprints of every card in the active sets with one
     matrix multiply, and break near-ties with ORB keypoint matching.

Everything works on greyscale, so the Steam Frame's monochrome cameras work
the same as a colour webcam.
"""
from __future__ import annotations

import cv2
import numpy as np

CANON_W, CANON_H = 200, 280
CARD_ASPECT = 63 / 88            # width / height of a standard card
DESC_VERSION = 3                 # bump when describe() changes, so cached indexes rebuild

_MARGIN = 0.035                  # trimmed off each side before describing (hides edge misalignment)
_W_INT, _W_ART, _W_HOG = 0.35, 0.20, 0.45
_ORB = None


# ---------------------------------------------------------------- helpers

def to_gray(img: np.ndarray) -> np.ndarray:
    if img.ndim == 2:
        return img
    if img.shape[2] == 4:
        return cv2.cvtColor(img, cv2.COLOR_BGRA2GRAY)
    return cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)


def flatten_alpha(img: np.ndarray, bg: int = 255) -> np.ndarray:
    """Composite a BGRA image onto a plain background (reference scans have transparent corners)."""
    if img.ndim == 3 and img.shape[2] == 4:
        a = img[:, :, 3:4].astype(np.float32) / 255.0
        rgb = img[:, :, :3].astype(np.float32)
        img = (rgb * a + bg * (1 - a)).astype(np.uint8)
    return img


def order_quad(pts: np.ndarray) -> np.ndarray:
    """Corners as TL, TR, BR, BL with the long side vertical (portrait)."""
    pts = np.asarray(pts, np.float32).reshape(4, 2)
    c = pts.mean(0)
    ang = np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0])
    pts = pts[np.argsort(ang)]                       # clockwise on screen (y points down)
    pts = np.roll(pts, -int(np.argmin(pts.sum(1))), axis=0)
    top = np.linalg.norm(pts[1] - pts[0]) + np.linalg.norm(pts[2] - pts[3])
    side = np.linalg.norm(pts[3] - pts[0]) + np.linalg.norm(pts[2] - pts[1])
    if top > side:                                   # card is lying sideways
        pts = np.roll(pts, -1, axis=0)
    return pts


def quad_aspect(q: np.ndarray) -> float:
    top = (np.linalg.norm(q[1] - q[0]) + np.linalg.norm(q[2] - q[3])) / 2
    side = (np.linalg.norm(q[3] - q[0]) + np.linalg.norm(q[2] - q[1])) / 2
    return float(top / side) if side > 0 else 0.0


def expand_quad(q: np.ndarray, f: float) -> np.ndarray:
    c = q.mean(0)
    return (c + (q - c) * f).astype(np.float32)


_DST = np.array([[0, 0], [CANON_W - 1, 0], [CANON_W - 1, CANON_H - 1], [0, CANON_H - 1]], np.float32)


def warp(gray: np.ndarray, quad: np.ndarray) -> np.ndarray:
    m = cv2.getPerspectiveTransform(np.asarray(quad, np.float32), _DST)
    return cv2.warpPerspective(gray, m, (CANON_W, CANON_H), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_REPLICATE)


def guide_quad(shape, size: float = 0.8) -> np.ndarray:
    """The fixed guide box in the middle of the frame (card-shaped, `size` of the frame height)."""
    h, w = shape[:2]
    ch = h * size
    cw = ch * CARD_ASPECT
    if cw > w * 0.95:
        cw = w * 0.95
        ch = cw / CARD_ASPECT
    x0, y0 = (w - cw) / 2, (h - ch) / 2
    return np.array([[x0, y0], [x0 + cw, y0], [x0 + cw, y0 + ch], [x0, y0 + ch]], np.float32)


def sharpness(canon: np.ndarray) -> float:
    return float(cv2.Laplacian(canon, cv2.CV_32F).var())


# ---------------------------------------------------------------- detection

def _same_quad(a: np.ndarray, b: np.ndarray, tol: float) -> bool:
    return float(np.abs(a - b).max()) < tol


_RNG = np.random.default_rng(1)


def _fit_side(pts: np.ndarray, p0: np.ndarray, p1: np.ndarray, band: float):
    """Robust straight line along one side of a rough quad, from the edge pixels near it.
    Returns (point, unit direction) or None when the side has too little visible edge."""
    d = p1 - p0
    length = float(np.hypot(*d))
    if length < 10:
        return None
    u = d / length
    nrm = np.array([-u[1], u[0]], np.float32)
    rel = pts - p0
    t = rel @ u / length
    dist = rel @ nrm
    sel = pts[(t > 0.06) & (t < 0.94) & (np.abs(dist) < band)]
    if len(sel) < max(12, 0.2 * length):
        return None
    # RANSAC, vectorised: 48 random lines at once, keep the one most edge pixels sit on
    k = 48
    i = _RNG.integers(0, len(sel), (k, 2))
    a, b = sel[i[:, 0]], sel[i[:, 1]]
    dirs = b - a
    n = np.linalg.norm(dirs, axis=1)
    ok = n > 0.15 * length
    if not ok.any():
        return None
    a, dirs, n = a[ok], dirs[ok], n[ok]
    normals = np.stack([-dirs[:, 1], dirs[:, 0]], 1) / n[:, None]
    res = np.abs(np.einsum('knd,kd->kn', sel[None, :, :] - a[:, None, :], normals))
    counts = (res < 1.6).sum(1)
    best = int(np.argmax(counts))
    inl = sel[res[best] < 1.6]
    if len(inl) < max(10, 0.15 * length):
        return None
    vx, vy, x0, y0 = cv2.fitLine(inl.astype(np.float32), cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
    return np.array([x0, y0], np.float32), np.array([vx, vy], np.float32)


def _intersect(l1, l2):
    (p, r), (q, s) = l1, l2
    den = r[0] * s[1] - r[1] * s[0]
    if abs(den) < 1e-6:
        return None
    t = ((q[0] - p[0]) * s[1] - (q[1] - p[1]) * s[0]) / den
    return p + t * r


def refine_quad(quad: np.ndarray, edge_pts: np.ndarray) -> np.ndarray:
    """Snap a rough quad onto the card's real edges: fit a line to each side's edge
    pixels and intersect them. Corners hidden under fingers or glare, and rounded
    corners, then land where the straight edges meet. A wide first pass catches
    sides that a finger pulled well off; a tight second pass settles them."""
    q = _refine_once(quad, edge_pts, 0.09)
    return _refine_once(q, edge_pts, 0.03)


def _refine_once(quad: np.ndarray, edge_pts: np.ndarray, band_frac: float) -> np.ndarray:
    size = float(np.sqrt(abs(cv2.contourArea(quad))))
    band = max(3.0, band_frac * size)
    lines = [_fit_side(edge_pts, quad[i], quad[(i + 1) % 4], band) for i in range(4)]
    if sum(l is not None for l in lines) < 3:
        return quad
    out = quad.copy()
    for i in range(4):                       # corner i joins side i-1 and side i
        la, lb = lines[i - 1], lines[i]
        if la is None or lb is None:
            continue
        p = _intersect(la, lb)
        if p is not None and np.hypot(*(p - quad[i])) < 0.25 * size:
            out[i] = p
    if not cv2.isContourConvex(out.astype(np.float32).reshape(-1, 1, 2)):
        return quad
    ratio = abs(cv2.contourArea(out)) / max(1.0, abs(cv2.contourArea(quad)))
    if not 0.75 < ratio < 1.3 or abs(quad_aspect(out) - CARD_ASPECT) / CARD_ASPECT > 0.3:
        return quad
    return out.astype(np.float32)


def find_cards(gray: np.ndarray, max_candidates: int = 5, min_area: float = 0.02):
    """Card-shaped quads in the frame, best-looking first: [(quad in frame pixels, shape score)].

    Several edge passes are tried (contrast-adaptive, normal, strict) because no
    single threshold works for both a pale card on a pale table and a card on a
    busy background. The caller picks between candidates by how well they match.
    """
    h, w = gray.shape[:2]
    scale = 640.0 / max(h, w) if max(h, w) > 640 else 1.0
    small = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale != 1.0 else gray
    sh, sw = small.shape[:2]
    frame_area = float(sh * sw)
    blur = cv2.GaussianBlur(small, (5, 5), 0)

    gx = cv2.Sobel(blur, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(blur, cv2.CV_32F, 0, 1, ksize=3)
    mag = cv2.magnitude(gx, gy)
    hi_auto = float(np.clip(np.percentile(mag, 90), 24, 400))
    passes = ((0.35 * hi_auto, hi_auto), (30, 90), (60, 170))

    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3))
    cands: list[tuple[np.ndarray, float]] = []
    edge_pts = None
    for lo, hi in passes:
        edges = cv2.Canny(blur, lo, hi, L2gradient=True)
        if edge_pts is None:                         # thin edges of the most sensitive pass, for refining
            ys, xs = np.nonzero(edges)
            edge_pts = np.stack([xs, ys], 1).astype(np.float32)
        edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel, iterations=2)
        contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for cnt in contours:
            area = cv2.contourArea(cnt)
            if area < min_area * frame_area or area > 0.97 * frame_area:
                continue
            hull = cv2.convexHull(cnt)
            hull_area = cv2.contourArea(hull)
            peri = cv2.arcLength(hull, True)
            quad = None
            for eps in (0.015, 0.025, 0.04, 0.06):
                approx = cv2.approxPolyDP(hull, eps * peri, True)
                if len(approx) == 4:
                    quad = approx.reshape(4, 2).astype(np.float32)
                    break
            if quad is None:                         # rounded or partly covered corners
                quad = cv2.boxPoints(cv2.minAreaRect(hull)).astype(np.float32)
            quad = order_quad(quad)
            qa = abs(cv2.contourArea(quad))
            if qa <= 0:
                continue
            fill = min(hull_area, qa) / max(hull_area, qa)   # how rectangular the outline is
            asp_err = abs(quad_aspect(quad) - CARD_ASPECT) / CARD_ASPECT
            if fill < 0.8 or asp_err > 0.3:
                continue
            score = (qa / frame_area) ** 0.35 * fill * (1 - asp_err)
            cands.append((quad, score))

    cands.sort(key=lambda c: -c[1])
    out: list[tuple[np.ndarray, float]] = []
    tol = 0.025 * max(sh, sw)
    for q, s in cands:
        if not any(_same_quad(q, o, tol) for o, _ in out):
            out.append((q, s))
            if len(out) >= max_candidates:
                break
    # Snap each candidate onto straight edges; keep the rough one too when they differ,
    # the recogniser will pick whichever matches better.
    refined: list[tuple[np.ndarray, float]] = []
    for q, s in out:
        r = refine_quad(q, edge_pts) if edge_pts is not None and len(edge_pts) else q
        refined.append((r, s))
        if not _same_quad(r, q, 0.006 * max(sh, sw)):
            refined.append((q, s * 0.98))
    return [(q / scale, s) for q, s in refined]


def find_card(gray: np.ndarray):
    """Best-looking card quad by shape alone: (quad, score) or (None, 0)."""
    c = find_cards(gray, 1)
    return c[0] if c else (None, 0.0)


# ---------------------------------------------------------------- fingerprints

def _unit(v: np.ndarray) -> np.ndarray:
    v = v.astype(np.float32).ravel()
    v = v - v.mean()
    n = float(np.linalg.norm(v))
    return v / n if n > 1e-6 else v


def _hog(img: np.ndarray, cells=(6, 8), bins=9) -> np.ndarray:
    gx = cv2.Sobel(img, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(img, cv2.CV_32F, 0, 1, ksize=3)
    mag, ang = cv2.cartToPolar(gx, gy)
    b = np.minimum((np.mod(ang, np.pi) * (bins / np.pi)).astype(np.int32), bins - 1)
    hh, ww = img.shape
    cy = np.arange(hh) * cells[1] // hh
    cx = np.arange(ww) * cells[0] // ww
    cell = cy[:, None] * cells[0] + cx[None, :]
    idx = (cell * bins + b).ravel()
    hist = np.bincount(idx, weights=mag.ravel(), minlength=cells[0] * cells[1] * bins).reshape(-1, bins)
    norms = np.linalg.norm(hist, axis=1, keepdims=True)
    hist = hist / (norms + 0.15 * float(norms.mean()) + 1e-6)   # per-cell contrast normalisation
    return hist


def describe(canon: np.ndarray) -> np.ndarray:
    """Unit-length fingerprint of a straightened card. dot(a, b) is a weighted correlation in [-1, 1]."""
    h, w = canon.shape[:2]
    mx, my = int(w * _MARGIN), int(h * _MARGIN)
    inner = canon[my:h - my, mx:w - mx]
    inner = cv2.GaussianBlur(inner, (3, 3), 0)
    ih = inner.shape[0]
    # whole card, low resolution: overall layout and tones
    a = cv2.resize(inner, (24, 34), interpolation=cv2.INTER_AREA)
    # the artwork window (top half) at a bit more detail
    art = cv2.resize(inner[int(ih * 0.07):int(ih * 0.52)], (36, 24), interpolation=cv2.INTER_AREA)
    # edge directions: survive changes in brightness, contrast and colour-to-grey mapping
    g = _hog(cv2.resize(inner, (96, 128), interpolation=cv2.INTER_AREA))
    parts = (_unit(a) * np.sqrt(_W_INT), _unit(art) * np.sqrt(_W_ART), _unit(g) * np.sqrt(_W_HOG))
    return np.concatenate(parts).astype(np.float32)


def query_descriptors(gray: np.ndarray, quad: np.ndarray, expands=(1.0, 1.05)) -> tuple[np.ndarray, np.ndarray]:
    """Fingerprints of the card upright and upside down, at a couple of crop sizes
    (absorbs a slightly-too-tight outline, e.g. when only the inner frame was found)."""
    descs, canon0 = [], None
    for f in expands:
        c = warp(gray, expand_quad(quad, f) if f != 1.0 else quad)
        if canon0 is None:
            canon0 = c
        descs.append(describe(c))
        descs.append(describe(cv2.rotate(c, cv2.ROTATE_180)))
    return np.stack(descs), canon0


def reference_canon(img: np.ndarray) -> np.ndarray:
    """A downloaded card scan, as the same 200x280 greyscale image the camera side produces."""
    img = flatten_alpha(img, 200)
    g = to_gray(img)
    return cv2.resize(g, (CANON_W, CANON_H), interpolation=cv2.INTER_AREA)


# ---------------------------------------------------------------- verification

def _orb():
    global _ORB
    if _ORB is None:
        _ORB = cv2.ORB_create(nfeatures=500, scaleFactor=1.2, nlevels=5, edgeThreshold=12, patchSize=17)
    return _ORB


_KP_CACHE: dict = {}


def orb_features(canon: np.ndarray, cache_key=None):
    if cache_key is not None and cache_key in _KP_CACHE:
        return _KP_CACHE[cache_key]
    img = cv2.equalizeHist(canon)
    kp, des = _orb().detectAndCompute(img, None)
    res = (np.float32([k.pt for k in kp]) if kp else np.zeros((0, 2), np.float32), des)
    if cache_key is not None:
        if len(_KP_CACHE) > 4000:
            _KP_CACHE.clear()
        _KP_CACHE[cache_key] = res
    return res


_BF = None


def orb_inliers(q_feat, r_feat) -> int:
    """Geometrically consistent keypoint matches between two straightened cards."""
    global _BF
    (qp, qd), (rp, rd) = q_feat, r_feat
    if qd is None or rd is None or len(qd) < 8 or len(rd) < 8:
        return 0
    if _BF is None:
        _BF = cv2.BFMatcher(cv2.NORM_HAMMING)
    pairs = _BF.knnMatch(qd, rd, k=2)
    good = [m for m in (p for p in pairs if len(p) == 2) if m[0].distance < 0.8 * m[1].distance]
    if len(good) < 8:
        return len(good) // 2
    src = np.float32([qp[m[0].queryIdx] for m in good])
    dst = np.float32([rp[m[0].trainIdx] for m in good])
    # Both images are already straightened to the same size, so the right card lines up
    # with a near-identity similarity transform. That is faster to fit than a full
    # homography and rejects chance matches harder.
    m, mask = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=6.0,
                                          maxIters=300, confidence=0.98)
    if m is None or mask is None:
        return 0
    scale = float(np.hypot(m[0, 0], m[1, 0]))
    if not 0.75 < scale < 1.33:
        return 0
    return int(mask.sum())


# ---------------------------------------------------------------- matcher

class Matcher:
    """Fingerprints of every card in the active sets, searched with one matrix multiply."""

    def __init__(self):
        self.keys: list[str] = []
        self.desc = np.zeros((0, 1), np.float32)
        self.mu = None
        self.canon: dict[str, np.ndarray] = {}

    def load(self, indexes) -> None:
        keys, descs, canon = [], [], {}
        for ix in indexes:
            keys.extend(ix.keys)
            descs.append(ix.desc.astype(np.float32))
            for k, c in zip(ix.keys, ix.canon):
                canon[k] = c
        desc = np.concatenate(descs) if descs else np.zeros((0, 1), np.float32)
        # Every card shares the same layout (border, text box, HP bar...). Removing the
        # average fingerprint leaves what makes each card different, which widens the
        # gap between the right card and look-alikes.
        self.mu = desc.mean(0, keepdims=True) if len(desc) >= 8 else None
        self.desc = self._center(desc)
        self.keys = keys
        self.canon = canon

    def _center(self, d: np.ndarray) -> np.ndarray:
        if self.mu is None or not len(d):
            return d
        d = d - self.mu
        return d / (np.linalg.norm(d, axis=1, keepdims=True) + 1e-6)

    def __len__(self):
        return len(self.keys)

    def match(self, qdesc: np.ndarray, topk: int = 5, with_rows: bool = False):
        """[(key, score)] best first; score is the best correlation over the query's
        orientations. with_rows also returns {key: index of the query row that matched}
        (even rows are upright, odd rows upside down)."""
        if not self.keys:
            return ([], {}) if with_rows else []
        sim = self._center(qdesc) @ self.desc.T
        scores = sim.max(axis=0)
        k = min(topk, len(scores))
        idx = np.argpartition(-scores, k - 1)[:k]
        idx = idx[np.argsort(-scores[idx])]
        out = [(self.keys[i], float(scores[i])) for i in idx]
        if with_rows:
            rows = sim[:, idx].argmax(axis=0)
            return out, {self.keys[i]: int(r) for i, r in zip(idx, rows)}
        return out

    def verify(self, q_canon: np.ndarray, cands, n: int = 3, rows: dict | None = None):
        """Count geometrically consistent keypoint matches for the top candidates.
        [(key, score, inliers)]. With `rows`, only the orientation the fingerprint picked is tried."""
        feats = {}

        def q(upside_down: bool):
            if upside_down not in feats:
                feats[upside_down] = orb_features(cv2.rotate(q_canon, cv2.ROTATE_180) if upside_down else q_canon)
            return feats[upside_down]
        out = []
        for key, score in cands[:n]:
            ref = self.canon.get(key)
            inl = 0
            if ref is not None:
                rf = orb_features(ref, cache_key=key)
                if rows is not None and key in rows:
                    inl = orb_inliers(q(rows[key] % 2 == 1), rf)
                else:
                    inl = max(orb_inliers(q(False), rf), orb_inliers(q(True), rf))
            out.append((key, score, inl))
        return out


class Recognition:
    """What the recogniser saw in one frame.

    score/margin come from the fingerprint search; inliers is the number of
    keypoints that line up between the camera image and the chosen card's scan
    (right card: usually 80-300; a different card: under ~15)."""
    __slots__ = ('quad', 'source', 'key', 'score', 'margin', 'cands', 'canon', 'sharp', 'inliers', 'rows')

    def __init__(self, quad, source, cands, canon, rows):
        self.quad, self.source, self.cands, self.canon, self.rows = quad, source, cands, canon, rows
        self.key, self.score = cands[0]
        self.margin = cands[0][1] - cands[1][1] if len(cands) > 1 else 1.0
        self.sharp = sharpness(canon)
        self.inliers = 0


def recognize(gray: np.ndarray, matcher: Matcher, mode: str = 'auto', guide_size: float = 0.8,
              max_candidates: int = 5, verify_top: int = 3, min_score: float = 0.3) -> Recognition | None:
    """Find and identify the card in a greyscale frame.

    mode: 'auto' (find the card's outline), 'guide' (use the fixed guide box),
    or 'both' (outline first, guide box as a fallback candidate).
    Every candidate outline is matched and the best-matching one wins, so a
    wrong outline (the card's inner frame, two cards merged) loses to the right
    one. The top few cards are then checked keypoint by keypoint, and the one
    with the most agreeing keypoints is the answer.
    """
    if not len(matcher):
        return None
    quads = []
    if mode in ('auto', 'both'):
        quads = [(q, 'edge', s) for q, s in find_cards(gray, max_candidates)]
    if mode == 'guide' or (mode == 'both'):
        quads.append((guide_quad(gray.shape, guide_size), 'guide', 0.0))
    best, best_rank = None, -9.0
    for q, src, geo in quads:
        qd, canon = query_descriptors(gray, q, expands=(1.0,))
        cands, rows = matcher.match(qd, 5, with_rows=True)
        if not cands:
            continue
        rank = cands[0][1] + 0.04 * geo - (0.04 if src == 'guide' else 0.0)
        if rank > best_rank:
            best, best_rank = (q, src, cands, canon, rows), rank
    if best is None:
        return None
    # the winning outline may be the card's inner frame: try it a little larger too
    q, src, cands, canon, rows = best
    q2 = expand_quad(q, 1.05)
    qd2, canon2 = query_descriptors(gray, q2, expands=(1.0,))
    cands2, rows2 = matcher.match(qd2, 5, with_rows=True)
    if cands2 and cands2[0][1] > cands[0][1] + 0.01:
        best = (q2, src, cands2, canon2, rows2)
    r = Recognition(*best)
    if r.score >= min_score and verify_top:
        ver = matcher.verify(r.canon, r.cands, verify_top, rows=r.rows)
        top = max(ver, key=lambda v: v[2])
        r.inliers = top[2]
        if top[0] != r.key and top[2] > ver[0][2]:
            r.key, r.score = top[0], top[1]           # keypoints prefer another candidate
    return r
