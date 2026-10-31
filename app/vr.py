"""SteamVR overlays: the floating scanner panel and a control panel in the SteamVR dashboard.

Runs as an OpenVR *overlay* app, so it shows on top of SteamVR Home or any game
you have open. Waits quietly until SteamVR is running and reconnects if it restarts.
"""
from __future__ import annotations

import ctypes
import json
import logging
import math
import os
import subprocess
import sys
import threading
import time

import numpy as np

from . import hud, vrgeom
from .config import APP_ID, APP_NAME

log = logging.getLogger(__name__)

HUD_KEY = APP_ID + '.hud'
PACK_KEY = APP_ID + '.pack'
SPOT_KEY = APP_ID + '.spotlight'
DASH_KEY = APP_ID + '.dashboard'
_NO_WINDOW = 0x08000000


def steamvr_running() -> bool:
    try:
        out = subprocess.run(['tasklist', '/FI', 'IMAGENAME eq vrserver.exe', '/NH'], capture_output=True, text=True,
                             timeout=5, creationflags=_NO_WINDOW).stdout
        return 'vrserver.exe' in out.lower()
    except Exception:
        return False


def vr_check(out_dir: str | None, system=None, grab: bool = True, save: bool = True) -> dict:
    """What SteamVR offers this app: headset, controllers, and whether its camera can be read.

    Uses the given connection, or opens a temporary one in 'background' mode (which
    never launches SteamVR). Saves a test camera frame to out_dir when one arrives.
    """
    import openvr
    rep: dict = {'steamvr_running': steamvr_running(), 'connected': False}
    own = False
    try:
        if system is None:
            if not rep['steamvr_running']:
                rep['error'] = 'SteamVR is not running'
                return rep
            system = openvr.init(openvr.VRApplication_Background)
            own = True
        rep['connected'] = True
        hmd = openvr.k_unTrackedDeviceIndex_Hmd

        def prop(fn, dev, p):
            try:
                return fn(dev, p)
            except Exception as e:
                return f'? ({e})'
        rep['headset'] = {
            'model': prop(system.getStringTrackedDeviceProperty, hmd, openvr.Prop_ModelNumber_String),
            'maker': prop(system.getStringTrackedDeviceProperty, hmd, openvr.Prop_ManufacturerName_String),
            'driver': prop(system.getStringTrackedDeviceProperty, hmd, openvr.Prop_TrackingSystemName_String),
            'cameras': prop(system.getInt32TrackedDeviceProperty, hmd, openvr.Prop_NumCameras_Int32),
            'camera_layout': prop(system.getInt32TrackedDeviceProperty, hmd, openvr.Prop_CameraFrameLayout_Int32),
        }
        rep['controllers'] = [i for i in range(openvr.k_unMaxTrackedDeviceCount)
                              if system.getTrackedDeviceClass(i) == openvr.TrackedDeviceClass_Controller]
        cam = openvr.VRTrackedCamera()
        try:
            rep['camera_present'] = bool(cam.hasCamera(hmd))
        except Exception as e:
            rep['camera_present'] = False
            rep['camera_error'] = str(e)
        if rep['camera_present'] and grab:
            sizes = {}
            for name, ft in (('distorted', openvr.VRTrackedCameraFrameType_Distorted),
                             ('undistorted', openvr.VRTrackedCameraFrameType_Undistorted),
                             ('max_undistorted', openvr.VRTrackedCameraFrameType_MaximumUndistorted)):
                try:
                    sizes[name] = cam.getCameraFrameSize(hmd, ft)
                except Exception as e:
                    sizes[name] = f'? ({e})'
            rep['camera_sizes'] = sizes
            try:
                ft = openvr.VRTrackedCameraFrameType_Undistorted
                w, h, size = cam.getCameraFrameSize(hmd, ft)
                handle = cam.acquireVideoStreamingService(hmd)
                buf = (ctypes.c_uint8 * size)()
                hdr, t0 = None, time.time()
                while time.time() - t0 < 4:
                    try:
                        hdr = cam.getVideoStreamFrameBuffer(handle, ft, buf, size)
                        break
                    except Exception as e:
                        rep['camera_wait'] = str(e)
                        time.sleep(0.05)
                cam.releaseVideoStreamingService(handle)
                if hdr is not None:
                    fw, fh, bpp = hdr.nWidth or w, hdr.nHeight or h, hdr.nBytesPerPixel or 4
                    img = np.frombuffer(buf, np.uint8, count=fw * fh * bpp).reshape(fh, fw, bpp)
                    rep['camera_frame'] = {'width': fw, 'height': fh, 'bytes_per_pixel': bpp,
                                           'mean_brightness': float(img[:, :, :3].mean())}
                    if save and out_dir:
                        import cv2
                        path = os.path.join(out_dir, 'vr-camera-test.jpg')
                        cv2.imwrite(path, cv2.cvtColor(img, cv2.COLOR_RGBA2BGR) if bpp == 4 else img)
                        rep['camera_frame']['saved'] = path
                else:
                    rep['camera_frame'] = None
                    rep['camera_note'] = ('SteamVR says the headset has a camera but sends no pictures to apps '
                                          '(the Steam Frame does this; see github.com/ValveSoftware/openvr/issues/1926).')
            except Exception as e:
                rep['camera_error'] = str(e)
    except Exception as e:
        rep['error'] = str(e)
    finally:
        if own:
            try:
                openvr.shutdown()
            except Exception:
                pass
    return rep


def _mat34(rot: np.ndarray, pos) -> 'openvr.HmdMatrix34_t':
    import openvr
    m = openvr.HmdMatrix34_t()
    for r in range(3):
        for c in range(3):
            m.m[r][c] = float(rot[r, c])
        m.m[r][3] = float(pos[r])
    return m


class Panel:
    """One floating panel in the room."""

    def __init__(self, key: str, overlay_key: str, name: str, size, width_m: float, interactive: bool = True):
        self.key, self.overlay_key, self.name, self.size = key, overlay_key, name, size
        self.width_m = width_m
        self.interactive = interactive
        self.handle = None
        self.pose = None            # where it is in the room this frame (3x4), for touch
        self.buttons: list = []
        self.hover = None
        self.dirty = True
        self.visible = False
        self.placed_key = None
        self.last_render = 0.0
        self.pokers: dict = {}      # controller index -> Poker

    @property
    def height_m(self) -> float:
        return self.width_m * self.size[1] / self.size[0]

    def button_at(self, u: float, v: float):
        x, y = u * self.size[0], v * self.size[1]
        for bid, (x0, y0, x1, y1) in self.buttons:
            if x0 <= x <= x1 and y0 <= y <= y1:
                return bid
        return None


class VRManager:
    """The scanner's panels in your room, and the control panel in the SteamVR dashboard.

    Panels stay where you put them (saved in settings, in SteamVR's room coordinates).
    Touch a button with a controller to press it; grab a panel by its top bar (or with
    the grip button) to move it. Everything works from controller positions alone, so
    no button bindings are needed.
    """

    def __init__(self, engine, settings, bus, images: hud.ImageCache, fmt, icon_path: str):
        self.engine, self.settings, self.bus, self.images, self.fmt = engine, settings, bus, images, fmt
        self.icon_path = icon_path
        self.connected = False
        self.has_camera = False
        self.status = 'SteamVR is not running'
        self.ov = None
        self.system = None
        self.dash_handle = None
        self.thumb_handle = None
        self.dash_buttons: list = []
        self._dash_dirty = True
        self._toast: dict | None = None
        self._raw_ok = {}
        self._grab = None             # (panel, controller index, vrgeom.Grab, with_grip)
        self._spot_until = 0.0
        self._spot_scan = None
        self._recenter = True
        self._stop = threading.Event()
        self._events = bus.subscribe(400)
        self.panels = {
            'scanner': Panel('scanner', HUD_KEY, 'Card Scanner', hud.HUD_SIZE, settings['hud_width']),
            'pack': Panel('pack', PACK_KEY, 'Current Pack', hud.PACK_SIZE, 0.5),
            'spot': Panel('spot', SPOT_KEY, 'Latest Pull', hud.SPOT_SIZE, 0.3, interactive=False),
        }
        images.on_ready = self.mark_dirty
        threading.Thread(target=self._run, name='vr', daemon=True).start()

    def check(self, out_dir: str) -> dict:
        rep = vr_check(out_dir, self.system if self.connected else None)
        rep['app_status'] = self.status
        return rep

    def info(self) -> dict:
        return {'connected': self.connected, 'status': self.status, 'camera': self.has_camera,
                'enabled': self.settings['vr_enabled']}

    def mark_dirty(self):
        for p in self.panels.values():
            p.dirty = True
        self._dash_dirty = True

    def stop(self):
        self._stop.set()

    # ---------------------------------------------------------------- connection

    def _run(self):
        while not self._stop.is_set():
            if not self.settings['vr_enabled']:
                if self.connected:
                    self._disconnect('VR panel turned off')
                self.status = 'VR panel turned off in settings'
                self._stop.wait(1.0)
                continue
            if not self.connected:
                if not steamvr_running():
                    self.status = 'SteamVR is not running'
                    self._stop.wait(3.0)
                    continue
                self._connect()
                if not self.connected:
                    self._stop.wait(5.0)
                    continue
            try:
                self._tick()
                self._errors = 0
            except Exception as e:
                # A drawing/placement problem shouldn't drop the whole connection; only a
                # SteamVR that no longer answers does.
                self._errors = getattr(self, '_errors', 0) + 1
                if self._errors <= 3 or self._errors % 100 == 0:
                    log.exception('VR loop error')
                if not self._alive():
                    self._disconnect(f'SteamVR connection lost ({e})')
                    self._stop.wait(3.0)
                else:
                    self.status = f'Connected to SteamVR, but the panel hit an error: {e}'
                    self._stop.wait(0.5)
            self._stop.wait(1 / 45)
        if self.connected:
            self._disconnect('closing')

    def _alive(self) -> bool:
        try:
            self.ov.isDashboardVisible()
            return steamvr_running()
        except Exception:
            return False

    def _connect(self):
        import openvr
        try:
            self.system = openvr.init(openvr.VRApplication_Overlay)
            self.ov = openvr.VROverlay()
            for p in self.panels.values():
                p.handle = self.ov.createOverlay(p.overlay_key, p.name)
                self.ov.setOverlayInputMethod(p.handle, openvr.VROverlayInputMethod_Mouse)
                self.ov.setOverlayMouseScale(p.handle, self._vec2(*p.size))
                self.ov.setOverlaySortOrder(p.handle, 20 if p.key == 'spot' else 10)
                p.visible, p.placed_key, p.dirty, p.pokers = False, None, True, {}
            self.dash_handle, self.thumb_handle = self.ov.createDashboardOverlay(DASH_KEY, APP_NAME)
            self.ov.setOverlayWidthInMeters(self.dash_handle, 2.4)
            self.ov.setOverlayInputMethod(self.dash_handle, openvr.VROverlayInputMethod_Mouse)
            self.ov.setOverlayMouseScale(self.dash_handle, self._vec2(*hud.DASH_SIZE))
            if os.path.exists(self.icon_path):
                self.ov.setOverlayFromFile(self.thumb_handle, os.path.abspath(self.icon_path))
            # "has a camera" isn't enough (the Steam Frame says yes but never sends frames):
            # only count the headset camera once a real frame arrives
            self.has_camera = False
            threading.Thread(target=self._probe_camera, name='vr-camera-probe', daemon=True).start()
            self.connected = True
            self.status = 'Connected to SteamVR'
            self._recenter = not self.settings['vr_layout']
            self._grab = None
            self.mark_dirty()
            self._register_manifest()
            log.info('connected to SteamVR')
            self.bus.emit('vr', **self.info())
            self._notify_engine()
        except Exception as e:
            log.warning('SteamVR connect failed: %s', e)
            self.status = f'Could not connect to SteamVR: {e}'
            try:
                openvr.shutdown()
            except Exception:
                pass
            self.connected = False

    def _disconnect(self, why: str):
        import openvr
        self.connected = False
        self.has_camera = False
        try:
            openvr.shutdown()
        except Exception:
            pass
        self.ov = self.system = None
        self.status = why
        self.bus.emit('vr', **self.info())
        self._notify_engine()

    def _probe_camera(self):
        rep = vr_check(None, self.system, grab=True, save=False)
        usable = bool(rep.get('camera_frame'))
        if usable != self.has_camera:
            self.has_camera = usable
            log.info('headset camera usable by apps: %s (%s)', usable, rep.get('camera_error') or rep.get('camera_wait') or '')
            self._notify_engine()

    def _notify_engine(self):
        """Let the engine switch cameras; its problems are not connection problems."""
        try:
            self.engine.on_vr_change()
        except Exception:
            log.exception('camera switch after SteamVR change failed')

    @staticmethod
    def _vec2(x, y):
        import openvr
        v = openvr.HmdVector2_t()
        v.v[0], v.v[1] = float(x), float(y)
        return v

    def _register_manifest(self):
        """Lets SteamVR start the scanner automatically (SteamVR Settings > Startup / Shutdown)."""
        if not getattr(sys, 'frozen', False):
            return
        import openvr
        try:
            from .config import data_dir
            path = os.path.join(data_dir(), 'mivimoose_scanner.vrmanifest')
            manifest = {'source': 'builtin', 'applications': [{
                'app_key': APP_ID, 'launch_type': 'binary', 'binary_path_windows': sys.executable,
                'is_dashboard_overlay': True,
                'strings': {'en_us': {'name': APP_NAME, 'description': 'Scan Pokémon cards and track packs and set progress'}},
            }]}
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(manifest, f, indent=1)
            apps = openvr.VRApplications()
            apps.addApplicationManifest(path, False)
            apps.setApplicationAutoLaunch(APP_ID, bool(self.settings['vr_autostart']))
        except Exception as e:
            log.info('could not register with SteamVR: %s', e)

    # ---------------------------------------------------------------- per-frame work

    def _poses(self):
        """(head 3x4 or None, [(controller index, 3x4)]) in room coordinates."""
        import openvr
        poses = (openvr.TrackedDevicePose_t * openvr.k_unMaxTrackedDeviceCount)()
        self.system.getDeviceToAbsoluteTrackingPose(openvr.TrackingUniverseStanding, 0, poses)

        def mat(i):
            p = poses[i]
            if not p.bPoseIsValid:
                return None
            return np.array([[p.mDeviceToAbsoluteTracking.m[r][c] for c in range(4)] for r in range(3)])
        head = mat(openvr.k_unTrackedDeviceIndex_Hmd)
        ctrls = []
        for role in (openvr.TrackedControllerRole_LeftHand, openvr.TrackedControllerRole_RightHand):
            i = self.system.getTrackedDeviceIndexForControllerRole(role)
            if i < openvr.k_unMaxTrackedDeviceCount:
                m = mat(i)
                if m is not None:
                    ctrls.append((i, m))
        return head, ctrls

    def _tick(self):
        import openvr
        while not self._events.empty():
            self._on_app_event(self._events.get_nowait())
        ev = openvr.VREvent_t()
        while self.system.pollNextEvent(ev):
            if ev.eventType in (openvr.VREvent_Quit, openvr.VREvent_ProcessQuit):
                try:
                    self.system.acknowledgeQuit_Exiting()
                except Exception:
                    pass
                self._disconnect('SteamVR closed')
                return
        # laser-pointer clicks, when SteamVR provides them (always in the dashboard)
        targets = [(p.handle, p.buttons, p.size) for p in self.panels.values()]
        targets.append((self.dash_handle, self.dash_buttons, hud.DASH_SIZE))
        for handle, buttons, size in targets:
            ok, e = self.ov.pollNextOverlayEvent(handle, openvr.VREvent_t())
            while ok:
                if e.eventType == openvr.VREvent_MouseButtonDown:
                    x, y = e.data.mouse.x, e.data.mouse.y
                    if self.settings['vr_flip_y']:
                        y = size[1] - y
                    self._click(buttons, x, y)
                ok, e = self.ov.pollNextOverlayEvent(handle, openvr.VREvent_t())

        now = time.time()
        head, ctrls = self._poses()
        self._place(head, ctrls)
        if self.settings['vr_touch'] and not self.ov.isDashboardVisible():
            self._touch(head, ctrls, now)

        lv = self.engine.live()
        sig = (lv.get('pendingName'), round(lv.get('progress', 0), 1), lv.get('hint'), len(lv.get('lockedNames', [])))
        if sig != getattr(self, '_live_sig', None):          # reading progress or a tip changed
            self._live_sig = sig
            self.panels['scanner'].dirty = True
        sc = self.panels['scanner']
        if self.settings['hud_camera'] and self.engine.camera is not None \
                and now - sc.last_render > 1 / max(1, self.settings['hud_fps']):
            sc.dirty = True
        if self._spot_scan is not None and now > self._spot_until:
            self._spot_scan = None
            self._hide(self.panels['spot'])
        self._render(now)

    # ---------------------------------------------------------------- placement

    def _layout(self, key):
        v = (self.settings['vr_layout'] or {}).get(key)
        return (np.array(v[:3]), v[3], v[4]) if v and len(v) >= 5 else None

    def _save_layout(self, key, pos, yaw, pitch):
        lay = dict(self.settings['vr_layout'] or {})
        lay[key] = [round(float(x), 4) for x in pos] + [round(float(yaw), 2), round(float(pitch), 2)]
        self.settings.update({'vr_layout': lay})

    def _show(self, p: Panel):
        if not p.visible:
            self.ov.showOverlay(p.handle)
            p.visible = True
            p.dirty = True

    def _hide(self, p: Panel):
        if p.visible:
            self.ov.hideOverlay(p.handle)
            p.visible = False
        p.pose = None

    def _set_world(self, p: Panel, pos, yaw, pitch):
        import openvr
        m = vrgeom.pose_matrix(pos, yaw, pitch)
        self.ov.setOverlayTransformAbsolute(p.handle, openvr.TrackingUniverseStanding, _mat34(m[:, :3], m[:, 3]))
        p.pose = m

    def _place(self, head, ctrls):
        import openvr
        s = self.settings
        missing = [k for k in ('scanner', 'pack') if self._layout(k) is None]
        if (self._recenter or missing) and head is not None:
            # in front of you: the scanner panel ahead, the pack panel to the right and lower (like on a table).
            # 'Bring panels here' moves them all; otherwise only panels with no saved spot yet are placed.
            lay = dict(s['vr_layout'] or {})
            for key, args in (('scanner', (s['hud_distance'], s['hud_height'])),
                              ('pack', (s['hud_distance'] + 0.1, s['hud_height'] - 0.3, 0.0, 38))):
                if self._recenter or key in missing:
                    pos, yaw, pitch = vrgeom.in_front(head, *args)
                    lay[key] = [round(float(x), 4) for x in pos] + [round(yaw, 2), round(pitch, 2)]
            self._recenter = False
            s.update({'vr_layout': lay})
            for p in self.panels.values():
                p.placed_key = None
        grabbing = self._grab[0] if self._grab else None

        sc = self.panels['scanner']
        mode = s['hud_mode']
        sc.width_m = s['hud_width'] if mode in ('world', 'head') else min(s['hud_width'], 0.28)
        head_rel = vrgeom.pose_matrix((0, s['hud_height'], -s['hud_distance']), 0, -12)
        hand_rel = vrgeom.pose_matrix((0, 0.04, 0.09), 0, -55)    # above the controller, tilted like a watch
        key = (mode, sc.width_m, s['hud_opacity'], s['hud_distance'], s['hud_height'], str(self._layout('scanner')))
        if sc is not grabbing and sc.placed_key != key:
            if mode == 'world':
                lay = self._layout('scanner')
                if lay is not None:
                    self._set_world(sc, *lay)
            elif mode == 'head':
                self.ov.setOverlayTransformTrackedDeviceRelative(sc.handle, openvr.k_unTrackedDeviceIndex_Hmd,
                                                                 _mat34(head_rel[:, :3], head_rel[:, 3]))
            else:
                role = openvr.TrackedControllerRole_LeftHand if mode == 'left' else openvr.TrackedControllerRole_RightHand
                idx = self.system.getTrackedDeviceIndexForControllerRole(role)
                if idx >= openvr.k_unMaxTrackedDeviceCount:
                    key = None                                  # that controller isn't on yet: try again
                else:
                    self.ov.setOverlayTransformTrackedDeviceRelative(sc.handle, idx, _mat34(hand_rel[:, :3], hand_rel[:, 3]))
            self.ov.setOverlayWidthInMeters(sc.handle, sc.width_m)
            self.ov.setOverlayAlpha(sc.handle, s['hud_opacity'])
            sc.placed_key = key
        # where a head- or hand-attached panel is right now, for touch
        if mode == 'head':
            sc.pose = vrgeom.compose(head, head_rel) if head is not None else None
        elif mode in ('left', 'right'):
            role = openvr.TrackedControllerRole_LeftHand if mode == 'left' else openvr.TrackedControllerRole_RightHand
            m = dict(ctrls).get(self.system.getTrackedDeviceIndexForControllerRole(role))
            sc.pose = vrgeom.compose(m, hand_rel) if m is not None else None
        self._show(sc)

        pk = self.panels['pack']
        if s['vr_pack_panel']:
            lay = self._layout('pack')
            pkey = str(lay)
            if lay is not None and pk.placed_key != pkey and pk is not grabbing:
                self.ov.setOverlayWidthInMeters(pk.handle, pk.width_m)
                self.ov.setOverlayAlpha(pk.handle, s['hud_opacity'])
                self._set_world(pk, *lay)
                pk.placed_key = pkey
            if pk.pose is not None:
                self._show(pk)
        else:
            self._hide(pk)
            pk.placed_key = None

    def _place_spotlight(self, head):
        """Above the scanner panel when it's in the room, else in front of your face."""
        sp, sc = self.panels['spot'], self.panels['scanner']
        if sc.pose is not None and self.settings['hud_mode'] == 'world':
            pos = sc.pose[:, 3] + sc.pose[:, 1] * (sc.height_m / 2 + sp.height_m / 2 + 0.04)
            yaw, pitch = vrgeom.facing(pos, head[:, 3]) if head is not None else (0.0, 0.0)
        elif head is not None:
            pos, yaw, pitch = vrgeom.in_front(head, 0.65, 0.05)
        else:
            return
        self.ov.setOverlayWidthInMeters(sp.handle, sp.width_m)
        self._set_world(sp, pos, yaw, pitch)
        self._show(sp)

    # ---------------------------------------------------------------- touch & grab

    def _grip_down(self, idx) -> bool:
        """The grip button, if SteamVR lets an overlay read it (otherwise grabbing uses the top bar)."""
        import openvr
        try:
            ok, st = self.system.getControllerState(idx)
            return bool(ok) and bool(st.ulButtonPressed & (1 << openvr.k_EButton_Grip))
        except Exception:
            return False

    def _buzz(self, idx, us=1200):
        try:
            self.system.triggerHapticPulse(idx, 0, us)
        except Exception:
            pass

    def _touch(self, head, ctrls, now):
        s = self.settings
        if self._grab:
            p, idx, grab, with_grip = self._grab
            m = dict(ctrls).get(idx)
            if m is None or head is None:
                self._grab = None
                return
            pos, yaw, pitch = grab.follow(m[:, 3], head[:, 3], now)
            self._set_world(p, pos, yaw, pitch)
            released = (not self._grip_down(idx)) if with_grip else grab.settled(now, 1.0)
            if released:
                if p.key == 'scanner' and s['hud_mode'] != 'world':
                    s.update({'hud_mode': 'world'})
                self._save_layout(p.key, pos, yaw, pitch)
                p.placed_key = None
                p.hover = None
                p.dirty = True
                self._grab = None
                self._buzz(idx, 2500)
                if s['snd_ui']:
                    self.engine.sounds.play('click')
            return
        hovers = {}
        for idx, m in ctrls:
            tip = vrgeom.tip_of(m, s['vr_tip_offset'])
            best, best_hit = None, None
            for p in self.panels.values():
                if not (p.visible and p.interactive and p.pose is not None):
                    continue
                hit = vrgeom.point_on_panel(p.pose, p.width_m, p.height_m, tip)
                if hit.inside and -0.12 < hit.depth < 0.25 and (best_hit is None or abs(hit.depth) < abs(best_hit.depth)):
                    best, best_hit = p, hit
            for p in self.panels.values():                       # forget panels this hand isn't near
                if p is not best and idx in p.pokers:
                    p.pokers[idx].update(None)
            if best is None:
                continue
            bid = best.button_at(best_hit.u, best_hit.v)
            if vrgeom.Poker.hovering(best_hit) and bid:
                hovers[best.key] = bid
            if self._grip_down(idx) and abs(best_hit.depth) < 0.1:   # grip anywhere on a panel picks it up
                self._start_grab(best, idx, m, with_grip=True)
                return
            ev = best.pokers.setdefault(idx, vrgeom.Poker()).update(best_hit)
            if ev == 'press' and bid:
                self._buzz(idx)
                if bid == 'handle':
                    self._start_grab(best, idx, m, with_grip=False)
                    return
                self._action(bid)
        for p in self.panels.values():
            h = hovers.get(p.key)
            if h != p.hover:
                p.hover = h
                p.dirty = True

    def _start_grab(self, p: Panel, idx, m, with_grip: bool):
        if p.pose is None:
            return
        self._grab = (p, idx, vrgeom.Grab(p.pose[:, 3], m[:, 3]), with_grip)
        p.hover = 'handle'
        p.dirty = True
        if self.settings['snd_ui']:
            self.engine.sounds.play('click')

    # ---------------------------------------------------------------- app events & actions

    def _on_app_event(self, ev: dict):
        t = ev['type']
        f = self.fmt
        msg = None
        if t == 'pack_done':
            p = ev['pack']
            prof = '' if p.get('profit') is None else f"  ({'+' if p['profit'] >= 0 else '-'}{f(abs(p['profit']))})"
            msg = (f"Pack {p['number']} done: {f(p['value'])}{prof}", 'good')
        elif t == 'milestone':
            msg = (f"{ev['name']}: {'master set complete!' if ev['kind'] == 'master' else str(ev['pct']) + '% of the set!'}", 'hit')
        elif t == 'ignored':
            msg = (f"{ev['card']['name']} is already in this pack", 'info')
        elif t == 'undo':
            msg = ('Removed the last scan', 'info')
        elif t == 'scan' and self.settings['vr_spotlight'] and self.connected:
            sc = ev['scan']
            price = sc.get('price') or 0
            self._spot_scan = sc
            hold = 7.0 if price >= self.settings['jackpot_value'] else 5.0 if price >= self.settings['big_hit_value'] else 3.5
            self._spot_until = time.time() + hold
            c = sc.get('card') or {}
            self.images.get(c.get('imgLarge'))
            self.images.get(c.get('img'))
            head, _ = self._poses()
            self._place_spotlight(head)
        elif t == 'prep' and ev.get('running') is not False and ev.get('total'):
            msg = (f"Downloading {ev.get('name', 'set')}… {ev.get('done', 0)}/{ev['total']}", 'info')
        elif t == 'toast':
            msg = (ev['message'], ev.get('level', 'info'))
        elif t == 'vr_recenter':
            self._recenter = True
        if msg:
            self._toast = {'message': msg[0], 'level': msg[1], 'ts': time.time()}
        if t == 'settings':
            ch = ev.get('changed', {})
            if {'hud_mode', 'hud_width', 'hud_distance', 'hud_height', 'hud_opacity', 'vr_layout', 'vr_pack_panel'} & ch.keys():
                for p in self.panels.values():
                    p.placed_key = None
            if 'vr_autostart' in ch:
                self._register_manifest()
        self.mark_dirty()

    def _click(self, buttons, x, y):
        for bid, (x0, y0, x1, y1) in buttons:
            if x0 <= x <= x1 and y0 <= y <= y1:
                if bid != 'handle':
                    self._action(bid)
                return

    def _action(self, bid: str):
        e, s = self.engine, self.settings
        if s['snd_ui']:
            e.sounds.play('click')
        if bid == 'undo':
            e.undo()
        elif bid == 'reverse':
            e.set_next_variant(None if e.next_variant == 'reverse' else 'reverse')
        elif bid == 'next_pack':
            e.next_pack()
        elif bid == 'pause':
            e.toggle_pause()
        elif bid == 'hud_mode':
            modes = ['world', 'head', 'left', 'right']
            s.update({'hud_mode': modes[(modes.index(s['hud_mode']) + 1) % len(modes)]})
        elif bid == 'recenter':
            self._recenter = True
        elif bid == 'hud_camera':
            s.update({'hud_camera': not s['hud_camera']})
        elif bid == 'voice':
            order = ['off', 'price', 'name_price', 'hits']
            s.update({'voice': order[(order.index(s['voice']) + 1) % len(order)]})
        if bid in ('hud_mode', 'recenter'):
            for p in self.panels.values():
                p.placed_key = None
        self.bus.emit('settings', changed={})
        self.mark_dirty()

    # ---------------------------------------------------------------- drawing

    def _upload(self, handle, img):
        """Send an RGBA image to an overlay. Falls back to a temp PNG if raw upload is refused (size limit)."""
        w, h = img.size
        if self._raw_ok.get(handle, True):
            try:
                data = img.tobytes()
                buf = (ctypes.c_char * len(data)).from_buffer_copy(data)
                self.ov.setOverlayRaw(handle, buf, w, h, 4)
                self._raw_ok[handle] = True
                return
            except Exception as e:
                log.info('raw overlay upload refused (%s); using image files', e)
                self._raw_ok[handle] = False
        from .config import data_dir
        path = os.path.join(data_dir(), f'overlay_{handle}.png')
        img.save(path, compress_level=1)
        self.ov.setOverlayFromFile(handle, path)

    def _render(self, now):
        state = None
        for p in self.panels.values():
            if not (p.dirty and p.visible) or now - p.last_render < 1 / 30:
                continue
            if p.key == 'spot' and self._spot_scan is None:
                continue
            p.dirty = False
            p.last_render = now
            if state is None:
                state = self.engine.state()
            if p.key == 'scanner':
                cam = None
                if self.settings['hud_camera'] and self.engine.camera is not None:
                    seq, frame = self.engine.camera.get(-1, timeout=0)
                    if frame is not None:
                        cam = self.engine.annotate(frame, 480)
                img, p.buttons = hud.hud(state, self.fmt, self.images, cam, self._toast, self.settings['hud_camera'], p.hover)
            elif p.key == 'pack':
                img, p.buttons = hud.pack_panel(state, self.fmt, self.images, p.hover)
            else:
                img = hud.spotlight(self._spot_scan, self.fmt, self.images, self.settings['jackpot_value'],
                                    self.settings['big_hit_value'])
                p.buttons = []
            self._upload(p.handle, img)
        if self._dash_dirty and self.ov.isDashboardVisible():
            self._dash_dirty = False
            img, self.dash_buttons = hud.dashboard(state or self.engine.state(), self.fmt, self.images, self.settings)
            self._upload(self.dash_handle, img)


