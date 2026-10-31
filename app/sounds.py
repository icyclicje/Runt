"""Sound effects, synthesised at start-up (no audio files to ship).

Played through Windows' default output, which SteamVR switches to the headset
while it is running, so you hear them in VR.
"""
from __future__ import annotations

import logging
import os
import queue
import threading
import time
import wave

import numpy as np

log = logging.getLogger(__name__)
RATE = 44100


def _t(sec):
    return np.arange(int(RATE * sec)) / RATE


def _env(n, attack=0.005, decay=6.0):
    t = np.arange(n) / RATE
    a = np.minimum(1, t / max(attack, 1e-4))
    return a * np.exp(-decay * t)


def _bell(freq, sec, decay=5.0, bright=0.35):
    t = _t(sec)
    w = np.sin(2 * np.pi * freq * t) + bright * np.sin(2 * np.pi * freq * 2.01 * t) + 0.12 * np.sin(2 * np.pi * freq * 3.98 * t)
    return w * _env(len(t), 0.004, decay)


def _soft(freq, sec, decay=9.0):
    t = _t(sec)
    w = np.sin(2 * np.pi * freq * t) + 0.3 * np.sin(2 * np.pi * freq * 3 * t) / 3
    return w * _env(len(t), 0.01, decay)


def _sweep(f0, f1, sec, decay=3.0):
    t = _t(sec)
    f = np.geomspace(f0, f1, len(t))
    ph = 2 * np.pi * np.cumsum(f) / RATE
    return np.sin(ph) * _env(len(t), 0.02, decay)


def _sparkle(sec, density=40, seed=3):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(RATE * sec))
    for _ in range(int(density * sec)):
        f = rng.uniform(2500, 6000)
        start = rng.integers(0, max(1, len(out) - 4000))
        s = _bell(f, 0.09, 30, 0.1) * rng.uniform(0.05, 0.18)
        out[start:start + len(s)] += s[: len(out) - start]
    return out


def _mix(*parts, total=None):
    """parts: (offset seconds, signal)"""
    end = max(int(o * RATE) + len(s) for o, s in parts)
    out = np.zeros(max(end, int((total or 0) * RATE)))
    for o, s in parts:
        i = int(o * RATE)
        out[i:i + len(s)] += s
    return out


def _note(n):  # semitones from A4
    return 440.0 * 2 ** (n / 12)


C5, E5, G5, A5, C6, D6, E6, G6, C7 = (_note(x) for x in (3, 7, 10, 12, 15, 17, 19, 22, 27))


def build_sounds() -> dict[str, np.ndarray]:
    s = {}
    s['new'] = _mix((0, _bell(A5, 0.35, 9)), (0.08, _bell(E6, 0.6, 6)))
    s['dupe'] = _mix((0, _soft(_note(-5), 0.22, 14)), (0.13, _soft(_note(-9), 0.3, 12))) * 0.9
    s['hit'] = _mix(*[(i * 0.065, _bell(f, 0.5, 7)) for i, f in enumerate((C6, E6, G6, C7))], (0.1, _sparkle(0.7, 30)))
    s['big'] = _mix(
        (0, _bell(C5, 1.6, 1.8, 0.5) * 0.6), (0, _bell(E5, 1.6, 1.8, 0.5) * 0.5), (0, _bell(G5, 1.6, 1.8, 0.5) * 0.5),
        *[(0.18 + i * 0.07, _bell(f, 0.6, 5)) for i, f in enumerate((C6, E6, G6, C7, G6, C7))],
        (0.25, _sparkle(1.3, 45, 5)),
    )
    s['pack'] = _mix((0, _sweep(300, 1200, 0.35, 4) * 0.5), (0.3, _bell(G5, 0.5, 6)), (0.45, _bell(C6, 0.9, 3.5)),
                     (0.45, _bell(E6, 0.9, 3.5) * 0.6))
    s['milestone'] = _mix(*[(i * 0.14, _bell(f, 1.0, 3.2)) for i, f in enumerate((C5, E5, G5, C6))],
                          (0.56, _bell(E6, 1.2, 2.5) * 0.7), (0.56, _bell(G6, 1.2, 2.5) * 0.5), (0.5, _sparkle(1.0, 25, 9)))
    s['ignored'] = _soft(_note(3), 0.07, 40) * 0.5
    s['undo'] = _sweep(900, 380, 0.16, 12) * 0.7
    s['ready'] = _mix((0, _bell(E5, 0.25, 10) * 0.7), (0.09, _bell(A5, 0.4, 8) * 0.7))
    s['error'] = _mix((0, _soft(_note(-14), 0.15, 10)), (0.12, _soft(_note(-14), 0.2, 10)))
    for k, v in s.items():
        peak = float(np.abs(v).max()) or 1.0
        s[k] = v / peak * 0.85
    return s


class SoundPlayer:
    """Plays one sound at a time from a queue, so a card sound and a
    'pack complete' fanfare follow each other instead of cutting each other off."""

    def __init__(self, out_dir: str, settings):
        self.dir = os.path.join(out_dir, 'sounds')
        os.makedirs(self.dir, exist_ok=True)
        self.settings = settings
        self._sounds = build_sounds()
        self._volume = None
        self._q: queue.Queue = queue.Queue()
        self._durations: dict[str, float] = {}
        threading.Thread(target=self._loop, name='sounds', daemon=True).start()

    def _write(self, volume: float):
        for name, sig in self._sounds.items():
            data = (np.clip(sig * volume, -1, 1) * 32767).astype('<i2')
            path = os.path.join(self.dir, f'{name}.wav')
            with wave.open(path, 'wb') as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(RATE)
                w.writeframes(data.tobytes())
            self._durations[name] = len(sig) / RATE
        self._volume = volume

    def play(self, name: str, force: bool = False):
        if not force and not self.settings['sound']:
            return
        if self._q.qsize() > 3:                       # don't build up a backlog
            return
        self._q.put(name)

    def _loop(self):
        try:
            import winsound
        except ImportError:
            winsound = None
        while True:
            name = self._q.get()
            vol = float(self.settings['volume'])
            if self._volume != vol:
                try:
                    self._write(vol)
                except OSError as e:
                    log.warning('could not write sounds: %s', e)
                    continue
            if winsound is None or name not in self._durations or vol <= 0:
                continue
            try:
                winsound.PlaySound(os.path.join(self.dir, f'{name}.wav'),
                                   winsound.SND_FILENAME | winsound.SND_ASYNC | winsound.SND_NODEFAULT)
            except RuntimeError as e:
                log.debug('sound failed: %s', e)
            time.sleep(min(self._durations[name], 1.6) * 0.85)
