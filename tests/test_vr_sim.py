"""The VR panels against a simulated SteamVR: placement, touch-to-press, grab-to-move,
grip grabbing, the spotlight, and the Steam Frame's camera that sends no frames.

usage: python -m tests.test_vr_sim

The real openvr structures are used; the SteamVR calls are replaced by a fake
headset and two controllers that this test moves around.
"""
from __future__ import annotations

import os
import shutil
import sys
import tempfile
import threading
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import openvr                                  # noqa: E402

from app import vrgeom                          # noqa: E402

failures = 0


def check(name, cond, detail=''):
    global failures
    print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else f'  ({detail})'))
    failures += not cond


class Sim:
    """The pretend room: where the headset and controllers are, which buttons are held."""

    def __init__(self):
        self.lock = threading.Lock()
        self.head = vrgeom.pose_matrix((0, 1.6, 0), 0, 0)          # standing at the origin, looking down -z
        self.ctrl = {1: vrgeom.pose_matrix((-0.3, 1.0, -0.2), 0, 0), 2: vrgeom.pose_matrix((0.3, 1.0, -0.2), 0, 0)}
        self.grip = {1: False, 2: False}
        self.haptics = []
        self.overlays = {}                                          # handle -> dict of what was set
        self.next_handle = 100

    def put(self, idx, pos):
        with self.lock:
            self.ctrl[idx] = vrgeom.pose_matrix(pos, 0, 0)


SIM = Sim()


def _fill(mat_struct, m):
    for r in range(3):
        for c in range(4):
            mat_struct.m[r][c] = float(m[r, c])


class FakeSystem:
    def getDeviceToAbsoluteTrackingPose(self, origin, t, poses):
        with SIM.lock:
            for i, m in ((0, SIM.head), (1, SIM.ctrl[1]), (2, SIM.ctrl[2])):
                _fill(poses[i].mDeviceToAbsoluteTracking, m)
                poses[i].bPoseIsValid = True
        return poses

    def getTrackedDeviceIndexForControllerRole(self, role):
        return 1 if role == openvr.TrackedControllerRole_LeftHand else 2

    def getControllerState(self, idx):
        st = openvr.VRControllerState_t()
        st.ulButtonPressed = (1 << openvr.k_EButton_Grip) if SIM.grip.get(idx) else 0
        return True, st

    def triggerHapticPulse(self, idx, axis, us):
        SIM.haptics.append((idx, us))

    def pollNextEvent(self, ev):
        return False

    def acknowledgeQuit_Exiting(self):
        pass

    def getStringTrackedDeviceProperty(self, i, p):
        return 'Steam Frame (simulated)'

    def getInt32TrackedDeviceProperty(self, i, p):
        return 0

    def getTrackedDeviceClass(self, i):
        return openvr.TrackedDeviceClass_Controller if i in (1, 2) else 0


class FakeOverlay:
    def _new(self, key):
        SIM.next_handle += 1
        SIM.overlays[SIM.next_handle] = {'key': key, 'shown': False, 'pose': None, 'images': 0}
        return SIM.next_handle

    def createOverlay(self, key, name):
        return self._new(key)

    def createDashboardOverlay(self, key, name):
        return self._new(key), self._new(key + '.thumb')

    def setOverlayTransformAbsolute(self, h, origin, m):
        SIM.overlays[h]['pose'] = np.array([[m.m[r][c] for c in range(4)] for r in range(3)])

    def setOverlayTransformTrackedDeviceRelative(self, h, dev, m):
        SIM.overlays[h]['relative'] = dev

    def showOverlay(self, h):
        SIM.overlays[h]['shown'] = True

    def hideOverlay(self, h):
        SIM.overlays[h]['shown'] = False

    def setOverlayRaw(self, h, buf, w, hh, bpp):
        SIM.overlays[h]['images'] += 1
        SIM.overlays[h]['size'] = (w, hh)

    def pollNextOverlayEvent(self, h, ev):
        return False, ev

    def isDashboardVisible(self):
        return False

    def __getattr__(self, name):                  # every other setter: accept and ignore
        return lambda *a, **k: None


class FakeCamera:                                 # like the Steam Frame: "has a camera", never sends frames
    def hasCamera(self, i):
        return True

    def getCameraFrameSize(self, i, t):
        return 640, 480, 640 * 480 * 4

    def acquireVideoStreamingService(self, i):
        return 1

    def getVideoStreamFrameBuffer(self, *a):
        raise RuntimeError('VRTrackedCameraError_OperationFailed')

    def releaseVideoStreamingService(self, h):
        pass


class FakeEngine:
    def __init__(self):
        self.calls = []
        self.next_variant = None
        self.camera = None
        self.vr_changes = 0
        self.sounds = type('S', (), {'play': lambda s, n, force=False: self.calls.append(('sound', n))})()

    def live(self):
        return {'state': 'scanning', 'progress': 0, 'lockedNames': []}

    def state(self):
        return {'state': 'scanning', 'message': 'Hold a card up to the camera', 'set': None, 'last': None,
                'pack': {'number': 1, 'count': 0, 'size': 10, 'scans': [], 'value': 0.0, 'cost': 4.5, 'profit': None},
                'progress': None, 'session': {}, 'live': self.live(), 'next_variant': None}

    def annotate(self, frame, w):
        return frame

    def on_vr_change(self):
        self.vr_changes += 1

    def undo(self):
        self.calls.append('undo')

    def next_pack(self):
        self.calls.append('next_pack')

    def toggle_pause(self):
        self.calls.append('pause')

    def set_next_variant(self, v):
        self.calls.append(('variant', v))


def world_point(panel, bid, depth):
    """A point `depth` in front of the centre of one of a panel's buttons."""
    x0, y0, x1, y1 = dict(panel.buttons)[bid]
    u, v = (x0 + x1) / 2 / panel.size[0], (y0 + y1) / 2 / panel.size[1]
    local = np.array([(u - 0.5) * panel.width_m, (0.5 - v) * panel.height_m, depth])
    return panel.pose[:, :3] @ local + panel.pose[:, 3]


def wait(cond, timeout=5.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if cond():
            return True
        time.sleep(0.02)
    return False


def main():
    import app.vr as vr
    from app import hud
    from app.config import Settings
    from app.engine import EventBus
    openvr.init = lambda kind, *a: FakeSystem()
    openvr.shutdown = lambda: None
    openvr.VROverlay = FakeOverlay
    openvr.VRTrackedCamera = FakeCamera
    openvr.VRApplications = lambda: type('A', (), {'__getattr__': lambda s, n: (lambda *a, **k: None)})()
    vr.steamvr_running = lambda: True

    tmp = tempfile.mkdtemp()
    try:
        settings = Settings(os.path.join(tmp, 'settings.json'))
        settings.update({'snd_ui': True})
        bus = EventBus()
        eng = FakeEngine()
        tip = settings['vr_tip_offset']
        m = vr.VRManager(eng, settings, bus, hud.ImageCache(), lambda usd: f'${usd:.2f}', os.path.join(tmp, 'icon.png'))
        sc, pk, sp = m.panels['scanner'], m.panels['pack'], m.panels['spot']
        check('connects to (simulated) SteamVR', wait(lambda: m.connected), m.status)
        check('scanner and pack panels shown and drawn',
              wait(lambda: sc.visible and pk.visible and sc.buttons and pk.buttons and SIM.overlays[sc.handle]['images'] > 0))
        pos, yaw, pitch = vrgeom.in_front(SIM.head, settings['hud_distance'], settings['hud_height'])
        check('scanner panel placed in front of the head', np.allclose(sc.pose[:, 3], pos, atol=1e-3), (sc.pose[:, 3], pos))
        check('placement saved for next time', 'scanner' in settings['vr_layout'] and 'pack' in settings['vr_layout'])
        check('pack panel off to the side and lower', pk.pose[0, 3] > 0.2 and pk.pose[1, 3] < sc.pose[1, 3])
        check('headset camera with no frames is not used', wait(lambda: eng.vr_changes >= 1) and not m.has_camera)

        # touch the Undo button: bring the controller tip in from 6 cm, push 1 cm through
        for d in (0.06, 0.04, 0.02, 0.005, -0.01):
            p = world_point(sc, 'undo', d)
            SIM.put(2, p + np.array([0, 0, tip]))           # controller origin sits `tip` behind its tip
            time.sleep(0.08)
        check('touching Undo presses it once', eng.calls.count('undo') == 1, eng.calls)
        check('the controller buzzed', any(i == 2 for i, _ in SIM.haptics))
        check('hovered button highlighted', sc.hover == 'undo' or eng.calls.count('undo') == 1)
        for d in (-0.01, -0.005):                           # resting against the panel: no repeat
            SIM.put(2, world_point(sc, 'undo', d) + np.array([0, 0, tip]))
            time.sleep(0.08)
        check('resting a hand on it does not repeat', eng.calls.count('undo') == 1, eng.calls)
        SIM.put(2, (0.3, 1.0, -0.2))
        time.sleep(0.15)

        # grab the scanner panel by its top bar, move it 25 cm right, hold still to put it down
        start = sc.pose[:, 3].copy()
        for d in (0.05, 0.02, -0.005):
            SIM.put(1, world_point(sc, 'handle', d) + np.array([0, 0, tip]))
            time.sleep(0.08)
        check('touching the top bar picks the panel up', wait(lambda: m._grab is not None, 1.0))
        grab_start = SIM.ctrl[1][:, 3].copy()
        for k in range(1, 11):
            SIM.put(1, grab_start + np.array([0.025 * k, 0, 0]))
            time.sleep(0.04)
        check('the panel follows the hand', wait(lambda: abs(sc.pose[0, 3] - (start[0] + 0.25)) < 0.03, 1.0),
              (sc.pose[:, 3], start))
        check('holding still puts it down', wait(lambda: m._grab is None, 2.5))
        lay = settings['vr_layout']['scanner']
        check('its new spot is saved', abs(lay[0] - (start[0] + 0.25)) < 0.03, lay)
        SIM.put(1, (-0.3, 1.0, -0.2))
        time.sleep(0.2)

        # grab the pack panel with the grip button
        pstart = pk.pose[:, 3].copy()
        SIM.put(2, pstart + pk.pose[:, 2] * 0.03 + np.array([0, 0, tip]))
        time.sleep(0.1)
        SIM.grip[2] = True
        check('grip on a panel picks it up', wait(lambda: m._grab is not None and m._grab[0] is pk, 1.0))
        SIM.put(2, SIM.ctrl[2][:, 3] + np.array([0, 0.2, 0]))
        time.sleep(0.2)
        SIM.grip[2] = False
        check('letting go of grip puts it down', wait(lambda: m._grab is None, 1.0))
        check('the pack panel moved up 20 cm and stayed', abs(settings['vr_layout']['pack'][1] - (pstart[1] + 0.2)) < 0.03,
              settings['vr_layout']['pack'])

        # a pull pops up the spotlight above the scanner panel, then it goes away
        bus.emit('scan', scan={'id': 1, 'price': 3.0, 'dupe': False,
                               'card': {'name': 'Test Card', 'img': None, 'imgLarge': None}})
        check('spotlight appears for a pull', wait(lambda: sp.visible and SIM.overlays[sp.handle]['images'] > 0, 2.0))
        check('it sits above the scanner panel', sp.pose is not None and sp.pose[1, 3] > sc.pose[1, 3] + 0.15)
        check('it goes away by itself', wait(lambda: not sp.visible, 5.0))

        # bring everything back in front
        settings.update({'vr_layout': {}})
        bus.emit('vr_recenter')
        check('recenter puts the scanner back in front', wait(lambda: np.allclose(sc.pose[:, 3], pos, atol=1e-3), 2.0))
        m.stop()
    finally:
        time.sleep(0.2)
        shutil.rmtree(tmp, ignore_errors=True)
    print('\nall passed' if not failures else f'\n{failures} FAILED')
    return failures


if __name__ == '__main__':
    sys.exit(main())
