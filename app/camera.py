"""Camera sources: any webcam, or the headset's own camera through SteamVR.

Each source runs its own thread and keeps only the newest frame, so the scanner
never works on a stale image (webcam drivers like to queue several).
"""
from __future__ import annotations

import ctypes
import logging
import os
import threading
import time

import cv2
import numpy as np

log = logging.getLogger(__name__)


class CameraSource:
    kind = 'none'

    def __init__(self, name: str, rotate: int = 0, mirror: bool = False):
        self.name = name
        self.rotate = rotate
        self.mirror = mirror
        self.status = 'starting'
        self.error = ''
        self.size = (0, 0)
        self.fps = 0.0
        self._frame = None
        self._seq = 0
        self._cond = threading.Condition()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run_safe, name=f'camera-{self.kind}', daemon=True)

    def start(self):
        self._thread.start()
        return self

    def stop(self):
        self._stop.set()
        with self._cond:
            self._cond.notify_all()
        if self._thread.is_alive() and threading.current_thread() is not self._thread:
            self._thread.join(timeout=3)

    def get(self, after_seq: int = -1, timeout: float = 0.25):
        """Newest frame as (seq, BGR or grey image), waiting briefly for one newer than after_seq."""
        with self._cond:
            if self._seq <= after_seq and not self._stop.is_set():
                self._cond.wait(timeout)
            return self._seq, self._frame

    def _publish(self, frame: np.ndarray):
        if self.rotate:
            frame = cv2.rotate(frame, {90: cv2.ROTATE_90_CLOCKWISE, 180: cv2.ROTATE_180,
                                       270: cv2.ROTATE_90_COUNTERCLOCKWISE}[self.rotate])
        if self.mirror:
            frame = cv2.flip(frame, 1)
        with self._cond:
            self._frame = frame
            self._seq += 1
            self.size = (frame.shape[1], frame.shape[0])
            self._cond.notify_all()

    def _run_safe(self):
        try:
            self._run()
        except Exception as e:                      # never take the app down with a camera
            log.exception('camera crashed')
            self.status, self.error = 'error', str(e)

    def _run(self):
        raise NotImplementedError

    def info(self) -> dict:
        return {'name': self.name, 'kind': self.kind, 'status': self.status, 'error': self.error,
                'width': self.size[0], 'height': self.size[1], 'fps': round(self.fps, 1)}


class WebcamSource(CameraSource):
    kind = 'webcam'

    def __init__(self, index: int, name: str, width: int, height: int, fps: int, exposure: int = 0, **kw):
        super().__init__(name, **kw)
        self.index, self.want = index, (width, height, fps)
        self.exposure = exposure

    def _open(self):
        w, h, fps = self.want
        for api in (cv2.CAP_DSHOW, cv2.CAP_MSMF):
            cap = cv2.VideoCapture(self.index, api)
            if not cap.isOpened():
                cap.release()
                continue
            cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))   # needed for 720p+ at full speed
            cap.set(cv2.CAP_PROP_FRAME_WIDTH, w)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, h)
            cap.set(cv2.CAP_PROP_FPS, fps)
            cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
            if self.exposure:
                cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 0.25)
                cap.set(cv2.CAP_PROP_EXPOSURE, self.exposure)
            ok, frame = cap.read()
            if ok and frame is not None:
                return cap, frame
            cap.release()
        return None, None

    def _run(self):
        while not self._stop.is_set():
            cap, frame = self._open()
            if cap is None:
                self.status, self.error = 'error', 'Could not open this camera (in use by another app, or unplugged?)'
                self._stop.wait(2.0)
                continue
            self.status, self.error = 'live', ''
            self._publish(frame)
            n, t0 = 0, time.time()
            fails = 0
            while not self._stop.is_set():
                ok, frame = cap.read()
                if not ok or frame is None:
                    fails += 1
                    if fails > 30:
                        break
                    time.sleep(0.03)
                    continue
                fails = 0
                self._publish(frame)
                n += 1
                if n == 30:
                    now = time.time()
                    self.fps, n, t0 = 30 / max(1e-3, now - t0), 0, now
            cap.release()
            if not self._stop.is_set():
                self.status, self.error = 'error', 'Camera stopped sending frames, reconnecting…'
                self._stop.wait(1.0)


class SteamVRCameraSource(CameraSource):
    """The headset's passthrough camera, through SteamVR's tracked-camera interface.

    SteamVR delivers both eyes in one frame; `eye` picks which half to scan.
    Requires SteamVR to be running and its camera enabled (Settings > Video > Camera).
    """
    kind = 'steamvr'

    NO_FRAMES = ('SteamVR says the headset has a camera, but it isn\'t sending pictures to apps. '
                 'The Steam Frame doesn\'t share its cameras with PC apps yet, so use a webcam instead.')

    def __init__(self, vr, eye: str = 'left', **kw):
        super().__init__('Headset camera (SteamVR)', **kw)
        self.vr, self.eye = vr, eye
        self.failed = False                         # gave up: no frames ever arrived

    def _run(self):
        import openvr
        while not self._stop.is_set():
            if not self.vr.connected:
                self.status, self.error = 'waiting', 'Waiting for SteamVR…'
                self._stop.wait(1.0)
                continue
            try:
                cam = openvr.VRTrackedCamera()
                hmd = openvr.k_unTrackedDeviceIndex_Hmd
                if not cam.hasCamera(hmd):
                    self.status, self.error = 'error', 'SteamVR reports no headset camera. Use a webcam instead.'
                    self.failed = True
                    self._stop.wait(10.0)
                    continue
                ftype = openvr.VRTrackedCameraFrameType_Undistorted
                w, h, size = cam.getCameraFrameSize(hmd, ftype)
                handle = cam.acquireVideoStreamingService(hmd)
            except Exception as e:
                self.status, self.error = 'error', f'Headset camera unavailable ({e}). Use a webcam instead.'
                self.failed = True
                self._stop.wait(10.0)
                continue
            buf = (ctypes.c_uint8 * max(1, size))()
            last_seq = -1
            n, t0 = 0, time.time()
            self.status, self.error = 'starting', 'Waiting for the headset camera…'
            started = time.time()
            got_any = False
            try:
                while not self._stop.is_set() and self.vr.connected:
                    try:
                        hdr = cam.getVideoStreamFrameBuffer(handle, ftype, buf, size)
                    except Exception:               # no frame yet (spinning up), or never (Steam Frame)
                        if not got_any and time.time() - started > 5.0:
                            self.status, self.error = 'error', self.NO_FRAMES
                            self.failed = True
                            break
                        time.sleep(0.02)
                        continue
                    if not got_any:
                        got_any = True
                        self.failed = False
                        self.status, self.error = 'live', ''
                    if hdr.nFrameSequence == last_seq:
                        time.sleep(0.008)
                        continue
                    last_seq = hdr.nFrameSequence
                    bpp = hdr.nBytesPerPixel or 4
                    fw, fh = hdr.nWidth or w, hdr.nHeight or h
                    img = np.frombuffer(buf, np.uint8, count=fw * fh * bpp).reshape(fh, fw, bpp)
                    img = cv2.cvtColor(img, cv2.COLOR_RGBA2BGR) if bpp == 4 else img[:, :, :3][:, :, ::-1].copy()
                    self._publish(self._pick_eye(img))
                    n += 1
                    if n == 30:
                        now = time.time()
                        self.fps, n, t0 = 30 / max(1e-3, now - t0), 0, now
            finally:
                try:
                    cam.releaseVideoStreamingService(handle)
                except Exception:
                    pass
            if self.failed:
                self._stop.wait(15.0)               # check again now and then (a SteamVR update may enable it)

    def _pick_eye(self, img):
        if self.eye == 'full':
            return img
        h, w = img.shape[:2]
        first = self.eye == 'left'
        if h >= w * 1.3:                            # eyes stacked top / bottom
            return img[: h // 2] if first else img[h // 2:]
        if w >= h * 1.6:                            # eyes side by side
            return img[:, : w // 2] if first else img[:, w // 2:]
        return img


class FileSource(CameraSource):
    """A video or still image played in a loop (for trying the scanner without a camera)."""
    kind = 'file'

    def __init__(self, path: str, fps: int = 20, loop: bool = True, **kw):
        super().__init__(os.path.basename(path), **kw)
        self.path, self.rate, self.loop = path, fps, loop

    def _run(self):
        still = cv2.imread(self.path) if os.path.splitext(self.path)[1].lower() in ('.png', '.jpg', '.jpeg', '.webp', '.bmp') else None
        self.status = 'live'
        self.fps = float(self.rate)
        while not self._stop.is_set():
            if still is not None:
                self._publish(still.copy())
                self._stop.wait(1 / self.rate)
                continue
            cap = cv2.VideoCapture(self.path)
            t_next = time.time()
            while not self._stop.is_set():
                ok, frame = cap.read()
                if not ok:
                    break
                self._publish(frame)
                t_next += 1 / self.rate                 # keep real-time pace
                self._stop.wait(max(0.0, t_next - time.time()))
            cap.release()
            if not self.loop:
                self.status, self.error = 'ended', 'Video finished'
                return


_names_cache: tuple[float, list[str]] = (0.0, [])


def webcam_names() -> list[str]:
    """Video devices in DirectShow order (the same order OpenCV's indexes use)."""
    global _names_cache
    if time.time() - _names_cache[0] < 10:
        return _names_cache[1]
    names: list[str] = []
    try:
        import comtypes
        comtypes.CoInitialize()
        try:
            from pygrabber.dshow_graph import FilterGraph
            names = list(FilterGraph().get_input_devices())
        finally:
            comtypes.CoUninitialize()
    except Exception as e:
        log.warning('could not list cameras: %s', e)
    _names_cache = (time.time(), names)
    return names


def list_cameras(vr_connected: bool) -> list[dict]:
    cams = [{'id': 'auto', 'name': 'Automatic (headset camera if available, else first webcam)'}]
    cams += [{'id': f'webcam:{i}', 'name': n} for i, n in enumerate(webcam_names())]
    cams.append({'id': 'steamvr', 'name': 'Headset camera (SteamVR)' + ('' if vr_connected else ' — SteamVR not running')})
    return cams
