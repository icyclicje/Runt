"""Sound effects, synthesised at start-up (no audio files to ship).

Played through Windows' default output, which SteamVR switches to the headset
while it is running, so you hear them in VR.

Pulls play by value: a chime (new) or a soft double note (duplicate), sparkles
for a hit, a fanfare for a big hit, and a jackpot sound of your choice for a
really expensive card (gunshot by default).
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
JACKPOT_STYLES = ('gunshot', 'explosion', 'cash', 'airhorn', 'laser', 'fanfare')


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


def _sweep(f0, f1, sec, decay=3.0, shape='sine'):
    t = _t(sec)
    f = np.geomspace(f0, f1, len(t))
    ph = 2 * np.pi * np.cumsum(f) / RATE
    w = np.sin(ph) if shape == 'sine' else 2 * ((ph / (2 * np.pi)) % 1) - 1
    return w * _env(len(t), 0.003, decay)


def _noise(sec, seed=1):
    return np.random.default_rng(seed).standard_normal(int(RATE * sec))


def _lowpass(x, cutoff):
    """One-pole low-pass, as a short exponential kernel (one numpy call)."""
    a = np.exp(-2 * np.pi * cutoff / RATE)
    n = max(2, int(6 / max(1e-6, 1 - a)))  # until the response has died away
    h = (1 - a) * a ** np.arange(min(n, 4000))
    return np.convolve(x, h)[: len(x)]


def _highpass(x, cutoff):
    return x - _lowpass(x, cutoff)


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


# ---------------------------------------------------------------- jackpot styles

def _gunshot():
    crack = _noise(0.006, 11) * np.linspace(1, 0, int(RATE * 0.006)) * 1.6
    body = _lowpass(_noise(0.18, 12), 3800) * _env(int(RATE * 0.18), 0.0005, 32) * 1.3
    t = _t(0.22)
    thump = np.sin(2 * np.pi * np.cumsum(np.geomspace(110, 38, len(t))) / RATE) * _env(len(t), 0.001, 16) * 1.1
    tail = _lowpass(_noise(1.1, 13), 1400) * _env(int(RATE * 1.1), 0.02, 4.5) * 0.35
    echo = _lowpass(_noise(0.12, 14), 2000) * _env(int(RATE * 0.12), 0.001, 30) * 0.35
    s = _mix((0, crack), (0, body), (0, thump), (0.02, tail), (0.19, echo), (0.37, echo * 0.5))
    return np.tanh(s * 1.8)                 # a little saturation for punch


def _explosion():
    boom_t = _t(1.8)
    boom = np.sin(2 * np.pi * np.cumsum(np.geomspace(80, 25, len(boom_t))) / RATE) * _env(len(boom_t), 0.002, 3)
    rumble = _lowpass(_noise(2.0, 21), 500) * _env(int(RATE * 2.0), 0.01, 2.2) * 2.5
    crackle = _highpass(_noise(0.5, 22), 2500) * _env(int(RATE * 0.5), 0.001, 9) * 0.5
    return np.tanh(_mix((0, boom), (0, rumble), (0, crackle)) * 1.4)


def _cash():
    clink = _mix(*[(0.0 + 0.03 * i, _bell(f, 0.9, 4.5, 0.6) * 0.5) for i, f in enumerate((2093, 2637, 3136))])
    drawer = _lowpass(_noise(0.18, 31), 2500) * _env(int(RATE * 0.18), 0.002, 14) * 0.6
    ching = _mix((0, _bell(_note(31), 1.0, 3.2, 0.7)), (0, _bell(_note(38), 1.0, 3.2, 0.5) * 0.7))
    return _mix((0, drawer), (0.08, clink), (0.32, ching))


def _airhorn():
    t = _t(1.1)
    out = np.zeros(len(t))
    for f in (466, 554, 698):              # a bright chord with a wobble
        ph = 2 * np.pi * np.cumsum(f * (1 + 0.006 * np.sin(2 * np.pi * 6 * t))) / RATE
        out += 2 * ((ph / (2 * np.pi)) % 1) - 1
    env = np.minimum(1, t / 0.02) * np.where(t > 0.95, np.maximum(0, (1.1 - t) / 0.15), 1)
    return np.tanh(_lowpass(out, 3500) * env * 0.9)


def _laser():
    return _mix((0, _sweep(2600, 180, 0.45, 4, 'saw') * 0.6), (0.05, _sweep(3400, 300, 0.4, 5) * 0.5),
                (0.12, _sweep(2000, 120, 0.5, 4) * 0.4))


def _fanfare():
    return _mix(
        (0, _bell(C5, 1.6, 1.8, 0.5) * 0.6), (0, _bell(E5, 1.6, 1.8, 0.5) * 0.5), (0, _bell(G5, 1.6, 1.8, 0.5) * 0.5),
        *[(0.18 + i * 0.07, _bell(f, 0.6, 5)) for i, f in enumerate((C6, E6, G6, C7, G6, C7))],
        (0.25, _sparkle(1.3, 45, 5)),
    )


JACKPOTS = {'gunshot': _gunshot, 'explosion': _explosion, 'cash': _cash, 'airhorn': _airhorn, 'laser': _laser,
            'fanfare': _fanfare}


def build_sounds() -> dict[str, np.ndarray]:
    s = {}
    s['new'] = _mix((0, _bell(A5, 0.35, 9)), (0.08, _bell(E6, 0.6, 6)))
    s['dupe'] = _mix((0, _soft(_note(-5), 0.22, 14)), (0.13, _soft(_note(-9), 0.3, 12))) * 0.9
    s['hit'] = _mix(*[(i * 0.065, _bell(f, 0.5, 7)) for i, f in enumerate((C6, E6, G6, C7))], (0.1, _sparkle(0.7, 30)))
    s['big'] = _fanfare()
    for name, fn in JACKPOTS.items():
        s['jackpot_' + name] = fn()
    s['pack'] = _mix((0, _sweep(300, 1200, 0.35, 4) * 0.5), (0.3, _bell(G5, 0.5, 6)), (0.45, _bell(C6, 0.9, 3.5)),
                     (0.45, _bell(E6, 0.9, 3.5) * 0.6))
    s['milestone'] = _mix(*[(i * 0.14, _bell(f, 1.0, 3.2)) for i, f in enumerate((C5, E5, G5, C6))],
                          (0.56, _bell(E6, 1.2, 2.5) * 0.7), (0.56, _bell(G6, 1.2, 2.5) * 0.5), (0.5, _sparkle(1.0, 25, 9)))
    s['ignored'] = _soft(_note(3), 0.07, 40) * 0.5
    s['undo'] = _sweep(900, 380, 0.16, 12) * 0.7
    s['ready'] = _mix((0, _bell(E5, 0.25, 10) * 0.7), (0.09, _bell(A5, 0.4, 8) * 0.7))
    s['error'] = _mix((0, _soft(_note(-14), 0.15, 10)), (0.12, _soft(_note(-14), 0.2, 10)))
    s['reading'] = _bell(_note(24), 0.06, 45, 0.05) * 0.35          # a card started being read
    flick = lambda seed: _highpass(_noise(0.035, seed), 1800) * _env(int(RATE * 0.035), 0.001, 70) * 0.5
    s['deal'] = _mix(*[(i * 0.055, flick(40 + i)) for i in range(5)], (0.3, _bell(G5, 0.3, 9) * 0.5))
    s['select'] = _mix((0, _bell(G5, 0.3, 9) * 0.6), (0.07, _bell(D6, 0.45, 7) * 0.6))
    s['pause'] = _mix((0, _soft(G5, 0.12, 18)), (0.08, _soft(C5, 0.18, 14)))
    s['resume'] = _mix((0, _soft(C5, 0.12, 18)), (0.08, _soft(G5, 0.18, 14)))
    s['click'] = _bell(_note(19), 0.04, 70, 0.1) * 0.5
    for k, v in s.items():
        peak = float(np.abs(v).max()) or 1.0
        s[k] = v / peak * 0.85
    return s


class SoundPlayer:
    """Plays one sound at a time from a queue, so a card sound and a
    'pack complete' fanfare follow each other instead of cutting each other off."""

    URGENT = ('click', 'reading')            # short UI sounds skip the queue's wait

    def __init__(self, out_dir: str, settings):
        self.dir = os.path.join(out_dir, 'sounds')
        os.makedirs(self.dir, exist_ok=True)
        self.settings = settings
        self._sounds = None
        self._volume = None
        self._q: queue.Queue = queue.Queue()
        self._durations: dict[str, float] = {}
        threading.Thread(target=self._loop, name='sounds', daemon=True).start()

    def _write(self, volume: float):
        if self._sounds is None:
            self._sounds = build_sounds()
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
        if name == 'jackpot':
            style = self.settings.get('jackpot_sound', 'gunshot')
            name = 'jackpot_' + (style if style in JACKPOT_STYLES else 'gunshot')
        if self._q.qsize() > 3 and name not in self.URGENT:   # don't build up a backlog
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
            if name not in self.URGENT:
                time.sleep(min(self._durations[name], 1.6) * 0.85)
