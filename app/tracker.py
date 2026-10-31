"""Turns per-frame detections into confirmed scans, for any number of cards at once.

Each card the detector reports builds up evidence (the sum of its recent
sighting strengths, fading with a short half-life). It is confirmed once the
evidence passes a threshold, so a clear sighting confirms in two or three
frames while a doubtful one has to keep showing up. After that the card is
locked: holding it in view, or putting it down where the camera still sees it,
never scans it again; it unlocks after it has been out of view for a moment.

Swapping cards in the same spot works: a new card there can confirm as soon as
the old one stops being seen. A weaker reading of the *same* physical card (a
look-alike) can't confirm while the stronger one is still being seen there.
"""
from __future__ import annotations

import math

from .features import _overlap


class Track:
    __slots__ = ('key', 'evidence', 'recent', 'first_seen', 'last_seen', 'quad', 'best', 'sightings')

    def __init__(self, key, now):
        self.key = key
        self.evidence = 0.0      # decaying sum of sighting strengths
        self.recent = 0.0        # strength of the latest sightings (fast-moving average)
        self.first_seen = self.last_seen = now
        self.quad = None
        self.best = None
        self.sightings = 0


class MultiTracker:
    def __init__(self):
        self.tracks: dict[str, Track] = {}
        self.locked: dict[str, float] = {}       # key -> last time it was seen
        self._t = None

    def reset(self, keep_lock: bool = False):
        """keep_lock: forget partial evidence but remember cards still in view
        (so finishing a pack doesn't re-scan its last card into the next one)."""
        self.tracks.clear()
        if not keep_lock:
            self.locked.clear()

    def update(self, dets, now: float, threshold: float, half_life: float, gap: float, min_sightings: int = 2):
        """Feed one frame's detections. Returns [(key, Detection)] confirmed by this frame."""
        dt = 0.05 if self._t is None else max(0.0, min(1.0, now - self._t))
        self._t = now
        fade = math.exp(-math.log(2) * dt / max(0.05, half_life))
        quick = math.exp(-math.log(2) * dt / 0.15)
        seen = {d.key for d in dets}
        for tr in self.tracks.values():
            tr.evidence *= fade
            if tr.key not in seen:
                tr.recent *= quick
        for d in dets:
            tr = self.tracks.get(d.key)
            if tr is None:
                tr = self.tracks[d.key] = Track(d.key, now)
            tr.evidence += d.strength
            tr.recent = max(d.strength, tr.recent * quick + d.strength * (1 - quick))
            tr.last_seen = now
            tr.quad = d.quad
            tr.sightings += 1
            if tr.best is None or d.strength >= tr.best.strength:
                tr.best = d
            if d.key in self.locked and d.strength > 0.15:
                self.locked[d.key] = now                 # still in view
        for k, t in list(self.locked.items()):
            if now - t > gap:
                del self.locked[k]                       # gone long enough: may be scanned again
        confirmed = []
        for tr in sorted(self.tracks.values(), key=lambda t: -t.evidence):
            if tr.key in self.locked or tr.evidence < threshold or tr.sightings < min_sightings:
                continue
            if tr.last_seen < now - 0.3 or self._outshone(tr, now):
                continue
            self.locked[tr.key] = now
            confirmed.append((tr.key, tr.best))
            tr.evidence = 0.0
        for k in [k for k, t in self.tracks.items() if now - t.last_seen > 3.0]:
            del self.tracks[k]
        return confirmed

    def _outshone(self, tr: Track, now: float) -> bool:
        """Another card is being seen right now in the same spot, at least as clearly."""
        for o in self.tracks.values():
            if o is tr or o.quad is None or now - o.last_seen > 0.3:
                continue
            if o.recent >= tr.recent and _overlap(o.quad, tr.quad) > 0.5:
                return True
        return False

    def pending(self, threshold: float):
        """The most advanced unconfirmed card in view: (key, progress 0..1) or (None, 0)."""
        best, prog = None, 0.0
        for tr in self.tracks.values():
            if tr.key in self.locked or tr.recent < 0.05:
                continue
            p = min(0.99, tr.evidence / threshold)
            if p > prog:
                best, prog = tr.key, p
        return best, prog

    @property
    def locked_keys(self):
        return list(self.locked)
