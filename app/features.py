"""Find and identify cards anywhere in a frame by their artwork (keypoint matching).

Every card in the active sets contributes a few hundred ORB keypoints to one big
approximate-nearest-neighbour index (LSH). A frame's keypoints are looked up in
it, every match votes for the card it came from, and the best-voted cards are
confirmed with a homography. Projecting the card's corners through that
homography gives its exact outline in the frame, even when the card's edges
can't be seen (busy table, low contrast, fingers over the border, a sleeve).
Several cards can be found in the same frame.
"""
from __future__ import annotations

import threading

import cv2
import numpy as np

REF_W, REF_H = 240, 336          # reference images are stored at this size (the scans' native size)
FEAT_VERSION = 4                 # bump when keypoint extraction changes (recomputed locally, no download)

_tls = threading.local()


def _clahe():
    c = getattr(_tls, 'clahe', None)
    if c is None:
        c = _tls.clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    return c


def _ref_orb():
    o = getattr(_tls, 'ref_orb', None)
    if o is None:
        o = _tls.ref_orb = cv2.ORB_create(nfeatures=700, scaleFactor=1.2, nlevels=6, edgeThreshold=19,
                                          patchSize=31, fastThreshold=10)
    return o


def _frame_orb(n):
    o = getattr(_tls, 'frame_orb', None)
    if o is None or getattr(_tls, 'frame_n', 0) != n:
        o = _tls.frame_orb = cv2.ORB_create(nfeatures=n, scaleFactor=1.2, nlevels=8, edgeThreshold=19,
                                            patchSize=31, fastThreshold=8)
        _tls.frame_n = n
    return o


def _bucket(kps, shape, grid=(10, 8), keep=400):
    """Spread keypoints over the image: the strongest few per grid cell, so a busy
    background can't use up the whole keypoint budget."""
    if len(kps) <= keep:
        return kps
    h, w = shape[:2]
    per = max(2, int(keep * 1.6 / (grid[0] * grid[1])))
    arr = np.array([(k.pt[0], k.pt[1], k.response) for k in kps], np.float32)
    cx = np.minimum(grid[0] - 1, (arr[:, 0] * grid[0] / w).astype(np.int32))
    cy = np.minimum(grid[1] - 1, (arr[:, 1] * grid[1] / h).astype(np.int32))
    cell = cy * grid[0] + cx
    order = np.lexsort((-arr[:, 2], cell))                  # by cell, strongest first within each
    sc = cell[order]
    first = np.searchsorted(sc, sc, side='left')            # index of each cell's first entry
    rank = np.arange(len(sc)) - first
    keep_idx = order[rank < per]
    keep_idx = keep_idx[np.argsort(-arr[keep_idx, 2])][:keep]
    return [kps[i] for i in keep_idx]


def ref_features(ref: np.ndarray, max_kp: int = 360):
    """Keypoints of one reference card image (REF_H x REF_W grey): (points Nx2 float32, descriptors Nx32 uint8)."""
    eq = _clahe().apply(ref)
    orb = _ref_orb()
    kps = orb.detect(eq, None)
    kps = _bucket(kps, eq.shape, grid=(4, 6), keep=max_kp)
    kps, des = orb.compute(eq, kps)
    if des is None or not kps:
        return np.zeros((0, 2), np.float32), np.zeros((0, 32), np.uint8)
    return np.float32([k.pt for k in kps]), des


def frame_features(gray: np.ndarray, max_side: int = 960, budget: int = 2500):
    """Keypoints of a camera frame, in the frame's own pixel coordinates."""
    h, w = gray.shape[:2]
    s = max_side / max(h, w) if max(h, w) > max_side else 1.0
    small = cv2.resize(gray, None, fx=s, fy=s, interpolation=cv2.INTER_AREA) if s != 1.0 else gray
    eq = _clahe().apply(small)
    orb = _frame_orb(budget * 2)
    kps = orb.detect(eq, None)
    kps = _bucket(kps, eq.shape, grid=(12, 8), keep=budget)
    kps, des = orb.compute(eq, kps)
    if des is None or not kps:
        return np.zeros((0, 2), np.float32), None, s
    return np.float32([k.pt for k in kps]) / s, des, s


_REF_CORNERS = np.float32([[0, 0], [REF_W - 1, 0], [REF_W - 1, REF_H - 1], [0, REF_H - 1]]).reshape(-1, 1, 2)


class FeatHit:
    """A card found by keypoint matching.

    inliers: matches that agree with the homography; matched: matches that voted
    for this card; spread: share of the card's area the inliers cover."""
    __slots__ = ('key', 'quad', 'inliers', 'votes', 'H', 'spread', 'matched')

    def __init__(self, key, quad, inliers, votes, H, spread, matched=0):
        self.key, self.quad, self.inliers, self.votes, self.H, self.spread = key, quad, inliers, votes, H, spread
        self.matched = matched


def _quad_ok(q: np.ndarray, frame_shape) -> bool:
    if not cv2.isContourConvex(q.reshape(-1, 1, 2).astype(np.float32)):
        return False
    h, w = frame_shape[:2]
    area = abs(cv2.contourArea(q))
    if area < 0.004 * w * h or area > 1.6 * w * h:
        return False
    sides = np.linalg.norm(q - np.roll(q, -1, 0), axis=1)
    if sides.min() < 12:
        return False
    # opposite sides of a card seen in perspective stay within a factor ~2.5 of each other
    if max(sides[0] / sides[2], sides[2] / sides[0], sides[1] / sides[3], sides[3] / sides[1]) > 2.5:
        return False
    asp = (sides[0] + sides[2]) / (sides[1] + sides[3])
    return 0.4 < asp < 1.25


class FeatureIndex:
    """All reference keypoints of the active sets.

    Matching runs in two stages: a fast, rough LSH lookup votes for a short
    list of cards (the right one is in the top 15 essentially always), then
    exact brute-force matching against just those cards' keypoints. That is
    ~10x faster than exact matching against everything, at the same accuracy.
    """

    def __init__(self, keys: list[str], pts: list[np.ndarray], des: list[np.ndarray]):
        self.keys = keys
        self.index_of = {k: i for i, k in enumerate(keys)}
        sizes = np.array([len(d) for d in des], np.int64)
        self.off = np.zeros(len(keys) + 1, np.int64)
        self.off[1:] = np.cumsum(sizes)
        self.card_of = np.repeat(np.arange(len(keys), dtype=np.int32), sizes)
        self.pts = np.concatenate(pts) if pts else np.zeros((0, 2), np.float32)
        self.des = np.concatenate(des) if des else np.zeros((0, 32), np.uint8)
        self._lsh = None
        if len(self.des):
            m = cv2.FlannBasedMatcher(dict(algorithm=6, table_number=6, key_size=16, multi_probe_level=0),
                                      dict(checks=16))
            m.add([self.des])
            m.train()
            self._lsh = m
        self._lock = threading.Lock()
        self.min_spread = 0.06

    def __len__(self):
        return len(self.keys)

    def shortlist(self, qdes: np.ndarray, n: int = 15) -> list[int]:
        """Cards most of the frame's keypoints roughly point at (fast, approximate)."""
        with self._lock:                         # the FLANN object isn't safe to share between threads
            pairs = self._lsh.knnMatch(qdes, k=1)
        ids = [p[0].trainIdx for p in pairs if p and p[0].distance < 64]
        if not ids:
            return []
        votes = np.bincount(self.card_of[ids], minlength=len(self.keys))
        top = np.argsort(-votes)[:n]
        return [int(c) for c in top if votes[c] >= 3]

    def detect(self, gray: np.ndarray, max_cards: int = 6, min_inliers: int = 10, feats=None,
               extra: list[str] = ()) -> list[FeatHit]:
        """Cards visible in the frame. extra: card keys to consider in any case (e.g. from the outline matcher)."""
        if self._lsh is None:
            return []
        qpts, qdes, s = feats if feats is not None else frame_features(gray)
        if qdes is None or len(qdes) < 8:
            return []
        cand = self.shortlist(qdes)
        for k in extra:
            i = self.index_of.get(k)
            if i is not None and i not in cand:
                cand.append(i)
        if not cand:
            return []
        rows = np.concatenate([np.arange(self.off[c], self.off[c + 1]) for c in cand])
        sub_card = self.card_of[rows]
        pairs = _knn2(qdes, np.ascontiguousarray(self.des[rows]))
        qi, ti = [], []
        for p in pairs:
            if not p:
                continue
            m = p[0]
            if m.distance > 72:
                continue
            if len(p) > 1:
                n = p[1]
                # a near-tie between two different cards is ambiguous; within one card it's fine
                if sub_card[m.trainIdx] != sub_card[n.trainIdx] and m.distance >= 0.82 * n.distance:
                    continue
            qi.append(m.queryIdx)
            ti.append(rows[m.trainIdx])
        if len(qi) < min_inliers:
            return []
        qi = np.asarray(qi)
        ti = np.asarray(ti)
        cards = self.card_of[ti]
        votes = np.bincount(cards, minlength=len(self.keys))
        order = np.argsort(-votes)
        used = np.zeros(len(qpts), bool)
        hits: list[FeatHit] = []
        # homographies are fitted in the downscaled frame's pixels (thresholds there are stable)
        for c in order[: max_cards * 3]:
            if votes[c] < max(8, min_inliers * 0.7) or len(hits) >= max_cards:
                break
            sel = (cards == c) & ~used[qi]
            if sel.sum() < 8:
                continue
            src = self.pts[ti[sel]]
            dst = qpts[qi[sel]] * s
            H, mask = cv2.findHomography(src, dst, cv2.USAC_MAGSAC, 3.5, maxIters=800, confidence=0.995)
            if H is None or mask is None:
                continue
            inl = mask.ravel().astype(bool)
            n_in = int(inl.sum())
            if n_in < min_inliers:
                continue
            # the matches must cover a fair part of the card, not one small detail
            ip = src[inl]
            spread = float((np.ptp(ip[:, 0]) * np.ptp(ip[:, 1])) / (REF_W * REF_H))
            if spread < self.min_spread:
                continue
            quad = cv2.perspectiveTransform(_REF_CORNERS, H).reshape(4, 2) / s
            if not _quad_ok(quad * s, (gray.shape[0] * s, gray.shape[1] * s)):
                continue
            # a second hit on the same spot is the same physical card: keep the stronger one
            if any(_overlap(quad, h.quad) > 0.4 for h in hits):
                continue
            used[qi[sel][inl]] = True
            Hs = np.diag([1 / s, 1 / s, 1.0]) @ H
            hits.append(FeatHit(self.keys[c], quad.astype(np.float32), n_in, int(votes[c]), Hs, spread, int(sel.sum())))
        hits.sort(key=lambda h: -h.inliers)
        return hits


_knn_failures = 0


def _knn2(q: np.ndarray, t: np.ndarray):
    """Two nearest neighbours by Hamming distance. OpenCV's matcher has thrown an
    'unknown C++ exception' on rare inputs (not reproducible in 3000 random trials),
    so on failure the query is retried in small pieces and a bad piece is skipped
    instead of losing the whole frame."""
    global _knn_failures
    bf = cv2.BFMatcher(cv2.NORM_HAMMING)
    try:
        return bf.knnMatch(np.ascontiguousarray(q), t, k=2)
    except cv2.error:
        _knn_failures += 1
        if _knn_failures <= 3:
            import logging
            logging.getLogger(__name__).warning('matcher error on %s x %s descriptors; retrying in pieces', q.shape, t.shape)
    out = []
    for i in range(0, len(q), 256):
        part = np.ascontiguousarray(q[i:i + 256])
        try:
            res = bf.knnMatch(part, t, k=2)
        except cv2.error:
            continue
        for p in res:                       # keep query indexes relative to the whole frame
            for m in p:
                m.queryIdx += i
        out.extend(res)
    return out


def _overlap(a: np.ndarray, b: np.ndarray) -> float:
    """Intersection over the smaller area of two convex quads."""
    try:
        inter, _ = cv2.intersectConvexConvex(a.astype(np.float32), b.astype(np.float32))
    except cv2.error:
        return 0.0
    small = min(abs(cv2.contourArea(a)), abs(cv2.contourArea(b)))
    return float(inter / small) if small > 0 else 0.0
