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

from . import hud
from .config import APP_ID, APP_NAME

log = logging.getLogger(__name__)

HUD_KEY = APP_ID + '.hud'
DASH_KEY = APP_ID + '.dashboard'
_NO_WINDOW = 0x08000000


def steamvr_running() -> bool:
    try:
        out = subprocess.run(['tasklist', '/FI', 'IMAGENAME eq vrserver.exe', '/NH'], capture_output=True, text=True,
                             timeout=5, creationflags=_NO_WINDOW).stdout
        return 'vrserver.exe' in out.lower()
    except Exception:
        return False


def vr_check(out_dir: str, system=None) -> dict:
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
        if rep['camera_present']:
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
                    import cv2
                    path = os.path.join(out_dir, 'vr-camera-test.jpg')
                    cv2.imwrite(path, cv2.cvtColor(img, cv2.COLOR_RGBA2BGR) if bpp == 4 else img)
                    rep['camera_frame'] = {'width': fw, 'height': fh, 'bytes_per_pixel': bpp, 'saved': path,
                                           'mean_brightness': float(img[:, :, :3].mean())}
                else:
                    rep['camera_frame'] = None
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


def _rot_x(deg: float) -> np.ndarray:
    a = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


class VRManager:
    def __init__(self, engine, settings, bus, images: hud.ImageCache, fmt, icon_path: str):
        self.engine, self.settings, self.bus, self.images, self.fmt = engine, settings, bus, images, fmt
        self.icon_path = icon_path
        self.connected = False
        self.has_camera = False
        self.status = 'SteamVR is not running'
        self.ov = None
        self.system = None
        self.hud_handle = None
        self.dash_handle = None
        self.thumb_handle = None
        self.hud_buttons: list = []
        self.dash_buttons: list = []
        self._dirty = True
        self._dash_dirty = True
        self._toast: dict | None = None
        self._world_pose = None
        self._placed_mode = None
        self._raw_ok = {}
        self._stop = threading.Event()
        self._events = bus.subscribe(400)
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
        self._dirty = True
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
            self._stop.wait(1 / 30)
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
            self.hud_handle = self.ov.createOverlay(HUD_KEY, 'Card Scanner')
            self.ov.setOverlayInputMethod(self.hud_handle, openvr.VROverlayInputMethod_Mouse)
            self.ov.setOverlayMouseScale(self.hud_handle, self._vec2(*hud.HUD_SIZE))
            self.ov.setOverlaySortOrder(self.hud_handle, 10)
            self.dash_handle, self.thumb_handle = self.ov.createDashboardOverlay(DASH_KEY, APP_NAME)
            self.ov.setOverlayWidthInMeters(self.dash_handle, 2.4)
            self.ov.setOverlayInputMethod(self.dash_handle, openvr.VROverlayInputMethod_Mouse)
            self.ov.setOverlayMouseScale(self.dash_handle, self._vec2(*hud.DASH_SIZE))
            if os.path.exists(self.icon_path):
                self.ov.setOverlayFromFile(self.thumb_handle, os.path.abspath(self.icon_path))
            try:
                self.has_camera = bool(openvr.VRTrackedCamera().hasCamera(openvr.k_unTrackedDeviceIndex_Hmd))
            except Exception:
                self.has_camera = False
            self.connected = True
            self.status = 'Connected to SteamVR'
            self._world_pose = None
            self._placed_mode = None
            self.mark_dirty()
            self._register_manifest()
            log.info('connected to SteamVR (headset camera: %s)', self.has_camera)
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

    def _tick(self):
        import openvr
        # app events
        while not self._events.empty():
            self._on_app_event(self._events.get_nowait())
        # SteamVR events
        ev = openvr.VREvent_t()
        while self.system.pollNextEvent(ev):
            if ev.eventType in (openvr.VREvent_Quit, openvr.VREvent_ProcessQuit):
                try:
                    self.system.acknowledgeQuit_Exiting()
                except Exception:
                    pass
                self._disconnect('SteamVR closed')
                return
        for handle, buttons, size in ((self.hud_handle, self.hud_buttons, hud.HUD_SIZE),
                                      (self.dash_handle, self.dash_buttons, hud.DASH_SIZE)):
            ok, e = self.ov.pollNextOverlayEvent(handle, openvr.VREvent_t())
            while ok:
                if e.eventType == openvr.VREvent_MouseButtonDown:
                    x, y = e.data.mouse.x, e.data.mouse.y
                    if self.settings['vr_flip_y']:
                        y = size[1] - y
                    self._click(buttons, x, y)
                ok, e = self.ov.pollNextOverlayEvent(handle, openvr.VREvent_t())

        self._place()
        now = time.time()
        lv = self.engine.live()
        sig = (lv.get('pendingName'), round(lv.get('progress', 0), 1), lv.get('hint'), len(lv.get('lockedNames', [])))
        if sig != getattr(self, '_live_sig', None):          # reading progress or a tip changed
            self._live_sig = sig
            self._dirty = True
        if self.settings['hud_camera'] and self.engine.camera is not None:
            if now - getattr(self, '_last_cam', 0) > 1 / max(1, self.settings['hud_fps']):
                self._dirty = True
        if self._dirty:
            self._last_cam = now
            self._dirty = False
            self._render_hud()
        if self._dash_dirty and self.ov.isDashboardVisible():
            self._dash_dirty = False
            self._render_dash()

    def _on_app_event(self, ev: dict):
        t = ev['type']
        f = self.fmt
        msg = None
        if t == 'pack_done':
            p = ev['pack']
            prof = '' if p.get('profit') is None else f"  ({'+' if p['profit'] >= 0 else '−'}{f(abs(p['profit']))})"
            msg = (f"Pack {p['number']} done: {f(p['value'])}{prof}", 'good')
        elif t == 'milestone':
            msg = (f"{ev['name']}: {'master set complete!' if ev['kind'] == 'master' else str(ev['pct']) + '% of the set!'}", 'hit')
        elif t == 'ignored':
            msg = (f"{ev['card']['name']} is already in this pack", 'info')
        elif t == 'undo':
            msg = ('Removed the last scan', 'info')
        elif t == 'scan':
            sc = ev['scan']
            if sc.get('price', 0) >= self.settings['big_hit_value']:
                msg = (f"BIG HIT! {sc['card']['name']}", 'hit')
        elif t == 'prep' and ev.get('running') is not False and ev.get('total'):
            msg = (f"Downloading {ev.get('name', 'set')}… {ev.get('done', 0)}/{ev['total']}", 'info')
        elif t == 'toast':
            msg = (ev['message'], ev.get('level', 'info'))
        if msg:
            self._toast = {'message': msg[0], 'level': msg[1], 'ts': time.time()}
        if t == 'settings' and {'hud_mode', 'hud_width', 'hud_distance', 'hud_height', 'hud_opacity'} & ev['changed'].keys():
            self._placed_mode = None
            if 'hud_mode' in ev['changed'] or 'hud_distance' in ev['changed'] or 'hud_height' in ev['changed']:
                self._world_pose = None
        if t == 'settings' and 'vr_autostart' in ev['changed']:
            self._register_manifest()
        self.mark_dirty()

    def _click(self, buttons, x, y):
        for bid, (x0, y0, x1, y1) in buttons:
            if x0 <= x <= x1 and y0 <= y <= y1:
                self._action(bid)
                return

    def _action(self, bid: str):
        e, s = self.engine, self.settings
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
            self._placed_mode = None
            self._world_pose = None
        elif bid == 'recenter':
            self._world_pose = None
            self._placed_mode = None
        elif bid == 'hud_camera':
            s.update({'hud_camera': not s['hud_camera']})
        elif bid == 'voice':
            order = ['off', 'price', 'name_price', 'hits']
            s.update({'voice': order[(order.index(s['voice']) + 1) % len(order)]})
        self.bus.emit('settings', changed={})
        self.mark_dirty()

    # ---------------------------------------------------------------- placement

    def _place(self):
        import openvr
        s = self.settings
        mode = s['hud_mode']
        key = (mode, s['hud_width'], s['hud_opacity'], s['hud_distance'], s['hud_height'])
        if mode != 'world' and self._placed_mode == key:
            return
        if mode == 'world' and self._placed_mode == key and self._world_pose is not None:
            return
        h = self.hud_handle
        width = s['hud_width'] if mode in ('world', 'head') else min(s['hud_width'], 0.28)
        self.ov.setOverlayWidthInMeters(h, width)
        self.ov.setOverlayAlpha(h, s['hud_opacity'])
        if mode == 'head':
            self.ov.setOverlayTransformTrackedDeviceRelative(
                h, openvr.k_unTrackedDeviceIndex_Hmd, _mat34(_rot_x(-12), (0, s['hud_height'], -s['hud_distance'])))
        elif mode in ('left', 'right'):
            role = openvr.TrackedControllerRole_LeftHand if mode == 'left' else openvr.TrackedControllerRole_RightHand
            idx = self.system.getTrackedDeviceIndexForControllerRole(role)
            if idx >= openvr.k_unMaxTrackedDeviceCount:
                return                                  # controller not on yet; try again next tick
            # lies just above the controller, tilted back toward your face like a wrist display
            self.ov.setOverlayTransformTrackedDeviceRelative(h, idx, _mat34(_rot_x(-55), (0, 0.04, 0.09)))
        else:
            # pyopenvr only fills an array it's given (passing None returns None)
            poses = (openvr.TrackedDevicePose_t * openvr.k_unMaxTrackedDeviceCount)()
            self.system.getDeviceToAbsoluteTrackingPose(openvr.TrackingUniverseStanding, 0, poses)
            hp = poses[openvr.k_unTrackedDeviceIndex_Hmd]
            if not hp.bPoseIsValid:
                return
            m = np.array([[hp.mDeviceToAbsoluteTracking.m[r][c] for c in range(4)] for r in range(3)])
            pos = m[:, 3]
            fwd = -m[:, 2]
            fwd[1] = 0
            n = np.linalg.norm(fwd)
            fwd = fwd / n if n > 1e-3 else np.array([0, 0, -1.0])
            z = -fwd                                    # panel faces you
            y = np.array([0, 1.0, 0])
            x = np.cross(y, z)
            rot = np.stack([x, y, z], 1) @ _rot_x(-15)
            p = pos + fwd * s['hud_distance'] + np.array([0, s['hud_height'], 0])
            self.ov.setOverlayTransformAbsolute(h, openvr.TrackingUniverseStanding, _mat34(rot, p))
            self._world_pose = p
        self.ov.showOverlay(h)
        self._placed_mode = key

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

    def _render_hud(self):
        state = self.engine.state()
        cam = None
        if self.settings['hud_camera'] and self.engine.camera is not None:
            seq, frame = self.engine.camera.get(-1, timeout=0)
            if frame is not None:
                cam = self.engine.annotate(frame, 480)
        img, self.hud_buttons = hud.hud(state, self.fmt, self.images, cam, self._toast, self.settings['hud_camera'])
        self._upload(self.hud_handle, img)

    def _render_dash(self):
        img, self.dash_buttons = hud.dashboard(self.engine.state(), self.fmt, self.images, self.settings)
        self._upload(self.dash_handle, img)
