"""Per-frame card detection: finds every card in a frame and says which card it is.

Two independent methods run on each frame and their findings are merged:

  keypoints  (features.py) the card's artwork is matched keypoint by keypoint
             against every card in the active sets. Works on busy backgrounds,
             in low contrast, with fingers over the edges or the card in a sleeve,
             and finds several cards at once.
  outline    (vision.py) the card's straight edges are found, the card is
             straightened, fingerprinted, and checked keypoint by keypoint.
             Catches cards with little texture (few keypoints) and blurry frames.

Every finding is then double-checked against the card it claims to be by
straightening it and comparing fingerprints, and gets a strength in 0..1 that
the tracker accumulates over frames.
"""
from __future__ import annotations

import concurrent.futures as cf
import math

import cv2
import numpy as np

from . import features, vision


class Detection:
    """One card seen in one frame."""
    __slots__ = ('key', 'quad', 'score', 'inliers', 'spread', 'source', 'strength', 'sharp', 'cands', 'glare', 'area',
                 'matched')

    def __init__(self, key, quad, score, inliers, spread, source, cands, sharp, glare, area):
        self.key, self.quad, self.score, self.inliers, self.spread = key, quad, score, inliers, spread
        self.source, self.cands, self.sharp, self.glare, self.area = source, cands, sharp, glare, area
        self.strength = 0.0
        self.matched = inliers

    def __repr__(self):
        return f'<{self.key} {self.source} s={self.strength:.2f} fp={self.score:.2f} inl={self.inliers}>'


def _clip01(x: float) -> float:
    return 0.0 if x < 0 else 1.0 if x > 1 else x


def features_of(d: Detection) -> list[float]:
    """Measurements of a sighting that tell right from wrong (inputs of strength_of)."""
    others = [s for k, s in (d.cands or []) if k != d.key]
    margin = d.score - max(others) if others else 0.5
    inl = math.log1p(max(0, d.inliers))
    if d.source == 'kp':
        return [1.0, inl, d.score, margin, d.spread, d.inliers / max(1, d.matched)]
    return [1.0, inl, d.score, margin]


# Logistic models of "this sighting names the right card", over features_of().
# Outline weights: fitted by tests/calibrate.py (stable across runs: AUC ~0.97).
# Keypoint weights: set by hand from the calibration data, because wrong keypoint
# sightings are too rare to fit (14 in 1200 frames): many agreeing keypoints carry
# the decision, and a fingerprint that clearly prefers another card vetoes it.
KP_WEIGHTS = np.array([-6.0, 2.0, 6.0, 20.0, 2.0, 0.0])     # 1, log(1+inliers), score, margin, spread, ratio
EDGE_WEIGHTS = np.array([-9.0, 1.9, 5.8, 20.0])            # 1, log(1+inliers), score, margin
EDGE_CAP = 0.75                                            # an outline-only card always needs 2+ sightings


def strength_of(d: Detection) -> float:
    """How convincing one sighting is, 0..1: the model's probability, rescaled so that
    anything below 25% counts for nothing."""
    w = KP_WEIGHTS if d.source == 'kp' else EDGE_WEIGHTS
    z = float(np.dot(w, features_of(d)))
    p = 1.0 / (1.0 + math.exp(-max(-30.0, min(30.0, z))))
    s = _clip01((p - 0.25) / 0.75)
    if d.source == 'kp':
        return s
    # An outline match only counts once keypoints back it up: during motion blur the
    # fingerprint alone can be fooled several frames running (seen as false scans).
    return min(s, EDGE_CAP) * _clip01((d.inliers - 8) / 16)


def _glare(canon: np.ndarray) -> float:
    """Share of the card that is blown out to white (shiny cards under a lamp)."""
    return float((canon >= 250).mean())


class Detector:
    def __init__(self):
        self.matcher = vision.Matcher()
        self.findex: features.FeatureIndex | None = None
        self.kidx: dict[str, int] = {}
        self._pool = cf.ThreadPoolExecutor(2, thread_name_prefix='detect')
        # card-shaped outlines in the last frame that matched nothing: [(quad, sharpness, glare)]
        self.last_unknown: list = []
        self.snap_edges = True

    def load(self, indexes) -> None:
        m = vision.Matcher()
        m.load(indexes)
        keys, pts, des = [], [], []
        for ix in indexes:
            for i, k in enumerate(ix.keys):
                p, d = ix.keypoints(i)
                keys.append(k)
                pts.append(p)
                des.append(d)
        self.findex = features.FeatureIndex(keys, pts, des) if keys else None
        self.kidx = {k: i for i, k in enumerate(m.keys)}
        self.matcher = m

    def __len__(self):
        return len(self.matcher)

    # ---------------------------------------------------------------- helpers

    def _fingerprint(self, gray, quad):
        """(score against each card, best-orientation rows, canon) for a quad."""
        qd, canon = vision.query_descriptors(gray, quad, expands=(1.0,))
        cands, rows = self.matcher.match(qd, 5, with_rows=True)
        return qd, cands, rows, canon

    def _score_for(self, qd: np.ndarray, key: str) -> float:
        i = self.kidx.get(key)
        if i is None:
            return 0.0
        return float((self.matcher._center(qd) @ self.matcher.desc[i]).max())

    def _outline_candidates(self, gray, mode, guide_size, edges_out=None):
        quads = []
        if mode in ('auto', 'both'):
            quads = [(q, 'edge', s) for q, s in vision.find_cards(gray, 5, edges_out=edges_out)]
        if mode in ('guide', 'both'):
            quads.append((vision.guide_quad(gray.shape, guide_size), 'guide', 0.0))
        out = []
        for q, src, geo in quads:
            qd, cands, rows, canon = self._fingerprint(gray, q)
            if cands:
                out.append((q, src, geo, qd, cands, rows, canon))
        return out

    # ---------------------------------------------------------------- main entry

    def analyze(self, gray: np.ndarray, mode: str = 'auto', guide_size: float = 0.8, use_keypoints: bool = True,
                max_cards: int = 6, keep_all: bool = False) -> list[Detection]:
        """keep_all: also return sightings too weak to count (for calibration)."""
        if not len(self.matcher):
            return []
        # The two methods are independent until they're merged: run them side by side
        # (OpenCV releases the interpreter lock, so this is real parallelism).
        kp_feats = self._pool.submit(features.frame_features, gray) if use_keypoints and self.findex else None
        edges: list = []
        outline = self._outline_candidates(gray, mode, guide_size, edges_out=edges)
        dets: list[Detection] = []

        if kp_feats is not None:
            extra = []
            for o in outline[:3]:
                extra.extend(k for k, _ in o[4][:2])
            hits = self.findex.detect(gray, max_cards=max_cards, feats=kp_feats.result(), extra=extra)
            for h in hits:
                quad = vision.order_quad(h.quad)
                qd, cands, rows, canon = self._fingerprint(gray, quad)
                score = self._score_for(qd, h.key)
                if edges and self.snap_edges:
                    # corners projected from keypoints in the artwork can drift a little:
                    # try the outline snapped onto the card's real edges, keep what matches better
                    q2 = vision.order_quad(edges[0].snap(quad))
                    if not np.allclose(q2, quad, atol=1.0):
                        qd2, cands2, rows2, canon2 = self._fingerprint(gray, q2)
                        s2 = self._score_for(qd2, h.key)
                        if s2 > score:
                            quad, qd, cands, rows, canon, score = q2, qd2, cands2, rows2, canon2, s2
                d = Detection(h.key, quad, score, h.inliers, h.spread, 'kp', cands, vision.sharpness(canon),
                              _glare(canon), abs(cv2.contourArea(quad)))
                d.matched = h.matched
                d.strength = strength_of(d)
                if d.strength > 0 or keep_all:
                    dets.append(d)

        # outline findings the keypoints didn't already explain
        unknown = []
        for q, src, geo, qd, cands, rows, canon in sorted(outline, key=lambda o: -o[4][0][1])[:3]:
            if any(features._overlap(q, d.quad) > 0.5 for d in dets):
                continue
            if cands[0][1] < 0.3:
                if src == 'edge' and geo > 0.3:
                    unknown.append((q, vision.sharpness(canon), _glare(canon)))
                continue
            q2 = vision.expand_quad(q, 1.05)             # the outline may be the card's inner frame
            qd2, cands2, rows2, canon2 = self._fingerprint(gray, q2)
            if cands2 and cands2[0][1] > cands[0][1] + 0.01:
                q, qd, cands, rows, canon = q2, qd2, cands2, rows2, canon2
            ver = self.matcher.verify(canon, cands, 3, rows=rows)
            key, score, inl = max(ver, key=lambda v: v[2])
            if key != cands[0][0] and inl <= ver[0][2]:
                key, score, inl = ver[0]
            d = Detection(key, q, score, inl, 0.0, src, cands, vision.sharpness(canon), _glare(canon),
                          abs(cv2.contourArea(q)))
            d.strength = strength_of(d)
            if d.strength > 0 or keep_all:
                dets.append(d)
            if d.strength <= 0 and src == 'edge' and geo > 0.3:
                unknown.append((q, d.sharp, d.glare))
        self.last_unknown = [u for u in unknown if not any(features._overlap(u[0], d.quad) > 0.5 for d in dets)]

        dets.sort(key=lambda d: -d.strength)
        out: list[Detection] = []
        for d in dets:                                    # one finding per physical card
            if not any(features._overlap(d.quad, o.quad) > 0.5 for o in out):
                out.append(d)
        return out[:max_cards]
