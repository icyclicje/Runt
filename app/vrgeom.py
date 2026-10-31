"""Geometry for the in-room VR panels (pure numpy, no SteamVR needed, so it's testable).

Conventions follow OpenVR: metres, right-handed, +y up, -z forward. A panel's
local frame has +x to the right, +y up and +z out of its face (towards whoever
reads it). A pose is a 3x4 matrix [R | t] from panel-local to room coordinates.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np


def rot_x(deg: float) -> np.ndarray:
    a = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


def rot_y(deg: float) -> np.ndarray:
    a = math.radians(deg)
    return np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])


def pose_matrix(pos, yaw: float, pitch: float) -> np.ndarray:
    """3x4 pose of a panel at pos, turned by yaw (degrees, about +y) and tilted back by pitch."""
    return np.hstack([rot_y(yaw) @ rot_x(pitch), np.asarray(pos, float).reshape(3, 1)])


def compose(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """a ∘ b for 3x4 poses."""
    return np.hstack([a[:, :3] @ b[:, :3], (a[:, :3] @ b[:, 3] + a[:, 3]).reshape(3, 1)])


def facing(pos, viewer, pitch_from_view: bool = True) -> tuple[float, float]:
    """(yaw, pitch) that turns a panel at pos to face a viewer position."""
    d = np.asarray(viewer, float) - np.asarray(pos, float)
    yaw = math.degrees(math.atan2(d[0], d[2]))
    flat = math.hypot(d[0], d[2])
    pitch = -math.degrees(math.atan2(d[1], flat)) if pitch_from_view else 0.0
    return yaw, max(-60.0, min(60.0, pitch))


def in_front(head: np.ndarray, distance: float, height: float, side: float = 0.0, side_deg: float = 0.0):
    """A spot in front of the head pose (3x4), level with the floor: (pos, yaw, pitch) facing the head."""
    fwd = -head[:, 2].copy()
    fwd[1] = 0
    n = np.linalg.norm(fwd)
    fwd = fwd / n if n > 1e-3 else np.array([0, 0, -1.0])
    right = np.array([-fwd[2], 0, fwd[0]])
    if side_deg:
        a = math.radians(side_deg)
        fwd = fwd * math.cos(a) + right * math.sin(a)
        right = np.array([-fwd[2], 0, fwd[0]])
    eye = head[:, 3]
    pos = eye + fwd * distance + right * side + np.array([0, height, 0])
    yaw, pitch = facing(pos, eye)
    return pos, yaw, pitch


@dataclass
class Hit:
    u: float          # 0..1 across the panel (left to right)
    v: float          # 0..1 down the panel (top to bottom)
    depth: float      # metres in front of the face (negative = pushed through)

    @property
    def inside(self) -> bool:
        return 0 <= self.u <= 1 and 0 <= self.v <= 1


def point_on_panel(pose: np.ndarray, width: float, height: float, point) -> Hit:
    """Where a 3D point (a controller tip) is relative to a panel's face."""
    rel = pose[:, :3].T @ (np.asarray(point, float) - pose[:, 3])
    return Hit(rel[0] / width + 0.5, 0.5 - rel[1] / height, float(rel[2]))


def tip_of(controller: np.ndarray, offset: float) -> np.ndarray:
    """The point a controller pokes with: offset metres ahead of its origin (-z is forward)."""
    return controller[:, 3] - controller[:, 2] * offset


class Poker:
    """Turns a controller tip's distance from a panel into hover / press / release.

    A press happens when the tip crosses the panel's face from in front; it has to
    come back out (a couple of cm) before it can press again, so resting a hand
    against a panel doesn't repeat."""

    HOVER, PRESS_AT, RELEASE_AT = 0.07, 0.0, 0.02

    def __init__(self):
        self.armed = True
        self.prev_depth = None

    def update(self, hit: Hit | None) -> str | None:
        """Returns 'press' on the frame the tip pushes through, else None."""
        if hit is None or not hit.inside or hit.depth > 0.25 or hit.depth < -0.12:
            self.prev_depth, self.armed = None, True
            return None
        event = None
        if self.armed and self.prev_depth is not None and self.prev_depth > self.PRESS_AT >= hit.depth:
            event, self.armed = 'press', False
        elif hit.depth > self.RELEASE_AT:
            self.armed = True
        self.prev_depth = hit.depth
        return event

    @classmethod
    def hovering(cls, hit: Hit | None) -> bool:
        return hit is not None and hit.inside and -0.12 < hit.depth < cls.HOVER


class Grab:
    """Moving a panel with a controller: keeps the panel's offset from the controller
    while held, and turns it to face the viewer as it moves."""

    def __init__(self, panel_pos, ctrl_pos):
        self.offset = np.asarray(panel_pos, float) - np.asarray(ctrl_pos, float)
        self.still_since = None
        self.last = np.asarray(ctrl_pos, float)

    def follow(self, ctrl_pos, head_pos, now: float):
        pos = np.asarray(ctrl_pos, float) + self.offset
        moved = np.linalg.norm(np.asarray(ctrl_pos, float) - self.last)
        if moved > 0.01:
            self.still_since, self.last = None, np.asarray(ctrl_pos, float)
        elif self.still_since is None:
            self.still_since = now
        yaw, pitch = facing(pos, head_pos)
        return pos, yaw, pitch

    def settled(self, now: float, hold: float = 1.0) -> bool:
        """Held still long enough to let go (for grabbing without a button)."""
        return self.still_since is not None and now - self.still_since >= hold
