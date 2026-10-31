"""Paths and settings (stored as JSON in %APPDATA%\\MivimooseScanner)."""
from __future__ import annotations

import json
import os
import sys
import threading

from .carddb import DEFAULT_SITE

APP_NAME = 'MivimooseRips Scanner'
APP_ID = 'mivimoose.scanner'
VERSION = '1.0.0'


def data_dir() -> str:
    base = os.environ.get('APPDATA') or os.path.expanduser('~')
    d = os.path.join(base, 'MivimooseScanner')
    os.makedirs(d, exist_ok=True)
    return d


def resource_dir() -> str:
    """Where bundled files (web UI, icon) live: next to the source, or inside the PyInstaller bundle."""
    if getattr(sys, 'frozen', False):
        return getattr(sys, '_MEIPASS', os.path.dirname(sys.executable))
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


SENSITIVITY = {
    # name: (evidence needed to confirm a card, minimum number of frames it was seen in)
    # Evidence is the fading sum of sighting strengths (0..1 each, see detector.strength_of),
    # so 'balanced' takes about two clear frames or three or four doubtful ones.
    'relaxed': (0.9, 1),
    'balanced': (1.5, 2),
    'strict': (2.4, 3),
}

DEFAULTS = {
    # camera
    'camera': 'auto',              # auto | webcam:<n> | steamvr
    'camera_res': '1280x720',
    'camera_fps': 30,
    'camera_rotate': 0,            # 0 / 90 / 180 / 270
    'camera_mirror': False,
    'stereo_eye': 'left',          # headset camera frames hold both eyes: which one to scan
    'camera_exposure': 0,          # 0 = auto, otherwise the driver's exposure value
    # scanning
    'paused': False,
    'detect_mode': 'auto',         # auto (find the card) | both (fall back to the guide box) | guide
    'use_keypoints': True,         # also find cards by their artwork (works without visible edges)
    'max_cards': 6,                # cards that can be scanned in one frame (laid out on a table)
    'guide_size': 0.8,
    'sensitivity': 'balanced',
    'scope': 'pack',               # pack (this set only) | selected | prepared (every set downloaded)
    'scope_sets': [],
    'rescan_gap': 1.2,             # seconds a card must be out of view before it can be scanned again
    'same_in_pack': 'ignore',      # ignore | allow  (the same card twice in one pack)
    'duplicates': 'count',         # count | ignore  (cards you already own, pulled again)
    'max_fps': 20,
    'show_preview': True,
    # packs
    'active_set': None,
    'pack_size': 0,                # 0 = the set's normal pack size
    'auto_next_pack': True,
    'pack_cost': None,             # None = the site's sealed pack price
    'default_variant': 'normal',   # normal | reverse | holo
    # sounds
    'sound': True,
    'volume': 0.8,
    'snd_new': True,
    'snd_dupe': True,
    'snd_hit': True,
    'snd_pack': True,
    'snd_milestone': True,
    'snd_ignored': False,
    'snd_reading': True,           # soft tick when a card starts being read
    'snd_ui': True,                # clicks and swishes in the app and on the VR panel
    'hit_value': 5.0,
    'big_hit_value': 50.0,
    'jackpot_value': 100.0,        # 'really expensive': plays the jackpot sound
    'jackpot_sound': 'gunshot',
    'voice': 'off',                # off | price | name_price | hits
    'voice_rate': 1,
    # display
    'currency': 'USD',
    'price_basis': 'market',       # market (today's price) | scan (price when scanned)
    # VR
    'vr_enabled': True,
    'hud_mode': 'world',           # world | head | left | right
    'hud_width': 0.45,
    'hud_distance': 0.75,
    'hud_height': -0.18,
    'hud_opacity': 0.96,
    'hud_camera': True,
    'hud_fps': 12,
    'vr_autostart': False,
    'vr_flip_y': True,             # SteamVR reports overlay clicks bottom-up; turn off if buttons respond upside down
    # data
    'site_url': DEFAULT_SITE,
    'refresh_hours': 12,
    'lan': False,
    'port': 47615,
}

CHOICES = {
    'camera_res': ['640x480', '1280x720', '1920x1080'],
    'camera_rotate': [0, 90, 180, 270],
    'stereo_eye': ['left', 'right', 'full'],
    'detect_mode': ['auto', 'both', 'guide'],
    'sensitivity': list(SENSITIVITY),
    'scope': ['pack', 'selected', 'prepared'],
    'same_in_pack': ['ignore', 'allow'],
    'duplicates': ['count', 'ignore'],
    'default_variant': ['normal', 'reverse', 'holo'],
    'voice': ['off', 'price', 'name_price', 'hits'],
    'jackpot_sound': ['gunshot', 'explosion', 'cash', 'airhorn', 'laser', 'fanfare'],
    'currency': ['USD', 'CAD', 'EUR', 'GBP', 'AUD', 'JPY', 'MXN', 'NZD'],
    'price_basis': ['market', 'scan'],
    'hud_mode': ['world', 'head', 'left', 'right'],
}

RANGES = {
    'camera_fps': (5, 60), 'guide_size': (0.3, 0.98), 'max_cards': (1, 10), 'rescan_gap': (0.2, 10), 'max_fps': (2, 60),
    'pack_size': (0, 40), 'volume': (0, 1), 'hit_value': (0, 100000), 'big_hit_value': (0, 100000), 'jackpot_value': (0, 1000000),
    'voice_rate': (-5, 5), 'hud_width': (0.15, 1.5), 'hud_distance': (0.3, 3), 'hud_height': (-1, 1),
    'hud_opacity': (0.2, 1), 'hud_fps': (2, 30), 'refresh_hours': (0, 720), 'port': (1024, 65535),
    'camera_exposure': (-13, 0),
}


class Settings:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()
        self.values = dict(DEFAULTS)
        self.transient: dict = {}       # command-line overrides: used, never saved
        try:
            with open(path, encoding='utf-8') as f:
                saved = json.load(f)
            for k, v in saved.items():
                if k in DEFAULTS:
                    self.values[k] = v
        except (OSError, ValueError):
            pass

    def __getitem__(self, key):
        return self.values[key]

    def get(self, key, default=None):
        return self.values.get(key, default)

    def update(self, changes: dict) -> dict:
        """Validate and apply changes; returns what actually changed."""
        changed = {}
        with self._lock:
            for k, v in changes.items():
                if k not in DEFAULTS:
                    continue
                v = self._coerce(k, v)
                if v is _BAD or self.values.get(k) == v:
                    continue
                self.values[k] = v
                changed[k] = v
            if changed:
                self._save()
        return changed

    def _coerce(self, k, v):
        d = DEFAULTS[k]
        try:
            if k in ('pack_cost', 'active_set') and v in (None, ''):
                return None
            if k == 'pack_cost':
                return max(0.0, float(v))
            if k == 'scope_sets':
                return [str(x) for x in v][:200]
            if isinstance(d, bool):
                return bool(v) if not isinstance(v, str) else v.lower() in ('1', 'true', 'on', 'yes')
            if isinstance(d, int) and not isinstance(d, bool):
                v = int(float(v))
            elif isinstance(d, float):
                v = float(v)
            elif d is None or isinstance(d, str):
                v = str(v)
        except (TypeError, ValueError):
            return _BAD
        if k in CHOICES and v not in CHOICES[k]:
            return _BAD
        if k in RANGES:
            lo, hi = RANGES[k]
            v = type(v)(min(hi, max(lo, v)))
        return v

    def override(self, key: str, value):
        """Use a value for this run only (e.g. --camera on the command line)."""
        self.transient[key] = self.values.get(key)
        self.values[key] = value

    def _save(self):
        out = dict(self.values)
        for k, original in self.transient.items():
            if out.get(k) == self.values.get(k):
                out[k] = original
        tmp = self.path + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(out, f, indent=1)
        os.replace(tmp, self.path)


_BAD = object()
