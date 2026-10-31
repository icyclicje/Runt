"""Checks for the in-room panel geometry and touch logic (no headset needed).

usage: python -m tests.test_vrgeom
"""
from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app import vrgeom as g    # noqa: E402

failures = 0


def check(name, cond, detail=''):
    global failures
    print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else f'  ({detail})'))
    failures += not cond


def main():
    # a 0.4 x 0.25 m panel 0.7 m ahead at 1.2 m, facing +z (towards someone standing at the origin)
    P = g.pose_matrix((0, 1.2, -0.7), 0, 0)
    h = g.point_on_panel(P, 0.4, 0.25, (0, 1.2, -0.65))
    check('centre, 5 cm in front', abs(h.u - .5) < 1e-9 and abs(h.v - .5) < 1e-9 and abs(h.depth - 0.05) < 1e-9, h)
    h = g.point_on_panel(P, 0.4, 0.25, (0.1, 1.25, -0.7))
    check('right and up -> u>0.5, v<0.5', abs(h.u - 0.75) < 1e-9 and abs(h.v - 0.3) < 1e-9, h)
    check('outside the panel is not inside', not g.point_on_panel(P, 0.4, 0.25, (0.3, 1.2, -0.7)).inside)

    # turned to face a viewer above and in front: a point along its normal is straight in front
    yaw, pitch = g.facing((0.3, 1.0, -0.6), (0, 1.6, 0))
    Q = g.pose_matrix((0.3, 1.0, -0.6), yaw, pitch)
    normal = Q[:, 2]
    to_viewer = np.array([0, 1.6, 0]) - np.array([0.3, 1.0, -0.6])
    check('facing() points the panel at the viewer', np.dot(normal, to_viewer / np.linalg.norm(to_viewer)) > 0.999,
          (yaw, pitch, normal))
    h = g.point_on_panel(Q, 0.4, 0.25, Q[:, 3] + normal * 0.03)
    check('along the normal: centre, 3 cm deep', abs(h.u - .5) < 1e-6 and abs(h.v - .5) < 1e-6 and abs(h.depth - 0.03) < 1e-6)

    # placing a panel in front of a head that looks down -z
    head = np.hstack([np.eye(3), np.array([[0], [1.7], [0]])])
    pos, yaw, pitch = g.in_front(head, 0.7, -0.2)
    check('in_front: 0.7 m ahead, 20 cm lower', np.allclose(pos, (0, 1.5, -0.7)), pos)
    check('in_front: faces back at the head', abs(yaw) < 1e-6 and pitch < 0, (yaw, pitch))
    # head turned 90 degrees to the left (looking down -x)
    head_l = np.hstack([g.rot_y(90), np.array([[0], [1.7], [0]])])
    pos, yaw, pitch = g.in_front(head_l, 1.0, 0)
    check('in_front follows where the head looks', np.allclose(pos, (-1, 1.7, 0), atol=1e-9), pos)
    pos, _, _ = g.in_front(head, 0.7, 0, side_deg=45)
    check('side_deg places it off to the right', pos[0] > 0.4 and pos[2] < -0.4, pos)

    # controller tip and pose composition
    ctrl = np.hstack([np.eye(3), np.array([[0.1], [1.0], [-0.3]])])
    check('tip is ahead of the controller', np.allclose(g.tip_of(ctrl, 0.06), (0.1, 1.0, -0.36)))
    A = g.pose_matrix((1, 0, 0), 90, 0)
    B = g.pose_matrix((0, 0, -1), 0, 0)
    C = g.compose(A, B)
    check('compose applies the inner pose first', np.allclose(C[:, 3], (0, 0, 0), atol=1e-9), C[:, 3])

    # poking: press once when pushing through, not again until pulled back out
    p = g.Poker()
    seq = [0.06, 0.03, 0.01, -0.004, -0.01, 0.005, -0.006, 0.03, -0.002]
    events = [p.update(g.Hit(0.5, 0.5, d)) for d in seq]
    check('one press per push', events == [None, None, None, 'press', None, None, None, None, 'press'], events)
    p = g.Poker()
    p.update(g.Hit(0.5, 0.5, 0.03))
    check('pushing through outside the panel does nothing', p.update(g.Hit(1.3, 0.5, -0.01)) is None)
    check('hovering only near the face', g.Poker.hovering(g.Hit(0.4, 0.4, 0.05)) and not g.Poker.hovering(g.Hit(0.4, 0.4, 0.2)))

    # grabbing: keeps the offset, turns to face the viewer, settles when still
    gr = g.Grab((0, 1.2, -0.7), (0.05, 1.1, -0.5))
    pos, yaw, pitch = gr.follow((0.25, 1.1, -0.5), (0, 1.6, 0), 0.0)
    check('grab keeps the panel\'s offset from the hand', np.allclose(pos, (0.2, 1.2, -0.7)), pos)
    gr.follow((0.25, 1.1, -0.5), (0, 1.6, 0), 0.5)
    check('not settled while just paused', not gr.settled(0.9))
    check('settled after holding still', gr.settled(1.6))

    print('\nall passed' if not failures else f'\n{failures} FAILED')
    return failures


if __name__ == '__main__':
    sys.exit(main())
