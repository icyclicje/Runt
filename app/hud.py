"""Panels shown inside the headset (Holo Vault style), drawn with Pillow.

  hud()         the scanner panel: last pull, live status, pack progress, buttons
  pack_panel()  the current pack as large card slots, to put on the table
  spotlight()   a big pop-up of the card you just pulled
  dashboard()   the control panel in the SteamVR dashboard

Each returns (RGBA image, [(button id, (x0, y0, x1, y1))]). The id 'handle' is the
grab bar across the top of a panel. `hover` highlights the button a controller is
touching.
"""
from __future__ import annotations

import io
import os
import threading
import time
import urllib.request
from collections import OrderedDict

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

BG = (11, 11, 18, 238)
SURF = (255, 255, 255, 14)
SURF2 = (255, 255, 255, 26)
SURF3 = (255, 255, 255, 44)
LINE = (255, 255, 255, 30)
TEXT = (243, 243, 247, 255)
TEXT2 = (186, 186, 203, 255)
MUTED = (138, 138, 160, 255)
TEAL = (110, 243, 214, 255)
BLUE = (138, 162, 255, 255)
PINK = (228, 139, 255, 255)
GOLD = (255, 207, 138, 255)
GOOD = (74, 222, 128, 255)
BAD = (251, 113, 133, 255)
WARN = (251, 191, 36, 255)
INK = (11, 11, 18, 255)
HOLO = (TEAL, BLUE, PINK, GOLD)

_FONT_DIR = os.path.join(os.environ.get('WINDIR', r'C:\Windows'), 'Fonts')
_fonts: dict = {}


def font(size: int, weight: str = 'regular', display: bool = False):
    """Segoe UI for text; Bahnschrift (a variable font) for headings and numbers."""
    key = (size, weight, display)
    if key not in _fonts:
        try:
            if display:
                f = ImageFont.truetype(os.path.join(_FONT_DIR, 'bahnschrift.ttf'), size)
                try:
                    f.set_variation_by_name({'regular': 'Regular', 'semi': 'SemiBold', 'bold': 'Bold',
                                             'black': 'Bold'}[weight])
                except Exception:
                    pass
            else:
                files = {'regular': 'segoeui.ttf', 'semi': 'seguisb.ttf', 'bold': 'segoeuib.ttf', 'black': 'seguibl.ttf'}
                f = ImageFont.truetype(os.path.join(_FONT_DIR, files[weight]), size)
        except OSError:
            f = ImageFont.load_default(size)
        _fonts[key] = f
    return _fonts[key]


class ImageCache:
    """Card images for the panels, downloaded in the background."""

    def __init__(self, limit: int = 80):
        self._c: OrderedDict = OrderedDict()
        self._pending: set = set()
        self._lock = threading.Lock()
        self.limit = limit
        self.on_ready = None

    def get(self, url: str | None):
        if not url:
            return None
        with self._lock:
            if url in self._c:
                self._c.move_to_end(url)
                return self._c[url]
            if url in self._pending:
                return None
            self._pending.add(url)
        threading.Thread(target=self._load, args=(url,), daemon=True).start()
        return None

    def _load(self, url):
        img = None
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 MivimooseScanner'})
            with urllib.request.urlopen(req, timeout=15) as r:
                img = Image.open(io.BytesIO(r.read())).convert('RGBA')
        except Exception:
            pass
        with self._lock:
            self._pending.discard(url)
            if img is not None:
                self._c[url] = img
                while len(self._c) > self.limit:
                    self._c.popitem(last=False)
        if img is not None and self.on_ready:
            self.on_ready()


# ---------------------------------------------------------------- drawing helpers

_grad_cache: dict = {}


def _holo(w: int, h: int) -> Image.Image:
    """The holo-foil gradient (teal -> blue -> pink -> gold, diagonal)."""
    key = (w, h)
    if key not in _grad_cache:
        y, x = np.mgrid[0:h, 0:w].astype(np.float32)
        t = np.clip((x / max(1, w) * 0.8 + y / max(1, h) * 0.2), 0, 1) * (len(HOLO) - 1)
        i = np.minimum(t.astype(int), len(HOLO) - 2)
        f = (t - i)[:, :, None]
        stops = np.array(HOLO, np.float32)
        rgba = stops[i] * (1 - f) + stops[i + 1] * f
        _grad_cache[key] = Image.fromarray(rgba.astype(np.uint8), 'RGBA')
        if len(_grad_cache) > 64:
            _grad_cache.pop(next(iter(_grad_cache)))
    return _grad_cache[key]


def _text(d: ImageDraw.ImageDraw, xy, s, f, fill=TEXT, anchor='la', max_w=None):
    s = str(s)
    if max_w and d.textlength(s, font=f) > max_w:
        while s and d.textlength(s + '…', font=f) > max_w:
            s = s[:-1]
        s += '…'
    d.text(xy, s, font=f, fill=fill, anchor=anchor)
    return s


def _holo_text(base: Image.Image, xy, s, f, anchor='la'):
    """Text filled with the holo gradient."""
    d = ImageDraw.Draw(base)
    box = [int(round(v)) for v in d.textbbox(xy, s, font=f, anchor=anchor)]
    w, h = max(1, box[2] - box[0]), max(1, box[3] - box[1])
    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).text((xy[0] - box[0], xy[1] - box[1]), s, font=f, fill=255, anchor=anchor)
    base.paste(_holo(w, h), (box[0], box[1]), mask)


def _panel_bg(W, H, radius=34):
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, W - 1, H - 1), radius=radius, fill=BG)
    # a holo hairline around the edge
    ring = Image.new('L', (W, H), 0)
    rd = ImageDraw.Draw(ring)
    rd.rounded_rectangle((0, 0, W - 1, H - 1), radius=radius, outline=150, width=3)
    im.paste(_holo(W, H), (0, 0), ring)
    return im, ImageDraw.Draw(im)


def _handle(d, W, buttons, title, hover, status=None, status_col=MUTED):
    """The grab bar across the top of a panel."""
    on = hover == 'handle'
    d.rounded_rectangle((W // 2 - 60, 12, W // 2 + 60, 20), radius=4, fill=TEXT2 if on else SURF3)
    _text(d, (30, 48), title, font(22, 'bold', True), TEXT2, anchor='lm')
    if status:
        d.ellipse((W - 30 - 14, 41, W - 30, 55), fill=status_col)
        _text(d, (W - 52, 48), status, font(20, 'semi'), status_col, anchor='rm')
    buttons.append(('handle', (0, 0, W, 76)))


def _bar(base, d, x, y, w, h, frac):
    d.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=SURF2)
    fw = int(w * max(0.0, min(1.0, frac)))
    if fw >= h:
        m = Image.new('L', (fw, h), 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, fw - 1, h - 1), radius=h // 2, fill=255)
        base.paste(_holo(fw, h), (x, y), m)


def _button(base, d, rect, label, buttons, bid, hover=None, primary=False, active=False):
    x0, y0, x1, y1 = rect
    on = hover == bid
    if primary:
        m = Image.new('L', (x1 - x0, y1 - y0), 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, x1 - x0 - 1, y1 - y0 - 1), radius=20, fill=255 if not on else 235)
        base.paste(_holo(x1 - x0, y1 - y0), (x0, y0), m)
        ink = INK
    else:
        fill = (228, 139, 255, 50) if active else (SURF3 if on else SURF2)
        d.rounded_rectangle(rect, radius=20, fill=fill, outline=PINK if active else (TEXT2 if on else LINE), width=3 if on else 2)
        ink = TEXT
    _text(d, ((x0 + x1) // 2, (y0 + y1) // 2), label, font(26, 'bold', True), ink, anchor='mm')
    buttons.append((bid, rect))


def _card(base: Image.Image, img: Image.Image | None, box, label='', glow=None):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    d = ImageDraw.Draw(base)
    if glow:
        g = Image.new('RGBA', base.size, (0, 0, 0, 0))
        ImageDraw.Draw(g).rounded_rectangle((x0 - 6, y0 - 6, x1 + 6, y1 + 6), radius=22, fill=glow)
        base.alpha_composite(g.filter(ImageFilter.GaussianBlur(14)))
    if img is None:
        d.rounded_rectangle(box, radius=14, fill=SURF, outline=LINE, width=2)
        if label:
            _text(d, ((x0 + x1) // 2, (y0 + y1) // 2), label, font(30, 'bold', True), MUTED, anchor='mm')
        return
    im = img.copy()
    im.thumbnail((w, h), Image.LANCZOS)
    mask = Image.new('L', im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.size[0] - 1, im.size[1] - 1), radius=max(6, im.size[0] // 22), fill=255)
    if im.mode == 'RGBA':
        mask = Image.fromarray(np.minimum(np.array(mask), np.array(im.split()[3])))
    base.paste(im, (x0 + (w - im.size[0]) // 2, y0 + (h - im.size[1]) // 2), mask)


def _cv_to_pil(frame: np.ndarray, size) -> Image.Image:
    img = frame if frame.ndim == 3 else cv2.cvtColor(frame, cv2.COLOR_GRAY2BGR)
    h, w = img.shape[:2]
    sc = min(size[0] / w, size[1] / h)
    img = cv2.resize(img, (max(1, int(w * sc)), max(1, int(h * sc))), interpolation=cv2.INTER_AREA)
    return Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB)).convert('RGBA')


STATE = {'scanning': ('SCANNING', GOOD), 'paused': ('PAUSED', WARN), 'pack_done': ('PACK DONE', WARN),
         'no_camera': ('NO CAMERA', BAD), 'loading': ('LOADING', BLUE), 'preparing': ('GETTING READY', BLUE),
         'no_set': ('PICK A SET', WARN)}


# ---------------------------------------------------------------- scanner panel

HUD_SIZE = (1024, 640)


def hud(state: dict, fmt, images: ImageCache, camera_img: np.ndarray | None, toast: dict | None, show_camera: bool,
        hover: str | None = None):
    W, H = HUD_SIZE
    im, d = _panel_bg(W, H)
    buttons: list = []
    st = state.get('state', 'loading')
    label, col = STATE.get(st, (st.upper(), MUTED))
    cs, pack = state.get('set'), state.get('pack')
    _handle(d, W, buttons, (cs['name'] if cs else 'MivimooseRips Scanner'), hover, label, col)

    last = state.get('last')
    card = (last or {}).get('card') or {}
    _card(im, images.get(card.get('img')) if card else None, (30, 92, 262, 416), '?' if not last else '…',
          glow=(228, 139, 255, 90) if last and last.get('price', 0) >= 5 else None)
    x = 292
    if last and card:
        tags = [('DUPLICATE', BAD) if last.get('dupe') else ('NEW', GOOD)]
        if last.get('variant') and last['variant'] != 'normal':
            tags.append((last['variant'].upper(), PINK))
        tx = x
        for tag, c in tags:
            tw = d.textlength(tag, font=font(20, 'bold', True)) + 28
            d.rounded_rectangle((tx, 96, tx + tw, 128), radius=16, fill=(c[0], c[1], c[2], 46))
            _text(d, (tx + tw / 2, 112), tag, font(20, 'bold', True), c, anchor='mm')
            tx += tw + 10
        _text(d, (x, 140), card.get('name', ''), font(46, 'bold', True), TEXT, max_w=W - x - 30)
        _text(d, (x, 198), f"#{card.get('number', '')}  ·  {card.get('rarity') or ''}", font(24), MUTED, max_w=W - x - 30)
        _holo_text(im, (x, 236), fmt(last.get('price')), font(78, 'bold', True))
    else:
        _text(d, (x, 140), state.get('message', ''), font(34, 'semi'), TEXT, max_w=W - x - 30)
        _text(d, (x, 190), 'Cards are scanned automatically.', font(24), MUTED)

    # pack + set progress
    prog = state.get('progress')
    if pack:
        _text(d, (x, 346), f"Pack {pack['number']}", font(24, 'semi', True), TEXT2)
        _text(d, (W - 30, 346), f"{pack['count']} / {pack['size']}", font(24, 'bold', True), TEXT, anchor='ra')
        _bar(im, d, x, 380, W - x - 30, 12, pack['count'] / max(1, pack['size']))
    if prog:
        _text(d, (x, 404), f"Set {prog['main_owned']}/{prog['main_total']}  ·  master {prog['master_owned']}/{prog['master_total']}",
              font(21), MUTED)

    # camera view
    if show_camera and camera_img is not None:
        cam = _cv_to_pil(camera_img, (232, 130))
        m = Image.new('L', cam.size, 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, cam.size[0] - 1, cam.size[1] - 1), radius=14, fill=255)
        im.paste(cam, (30 + (232 - cam.size[0]) // 2, 432), m)

    # live line: being read (with progress), a tip, or the latest event
    live = state.get('live') or {}
    box = (x, 440, W - 30, 492)
    if st == 'scanning' and live.get('pendingName') and live.get('progress', 0) > 0:
        d.rounded_rectangle(box, radius=16, fill=SURF2, outline=TEAL, width=2)
        _text(d, (x + 18, 466), f"Reading {live['pendingName']}…", font(24, 'semi'), TEXT, anchor='lm', max_w=W - x - 260)
        _bar(im, d, W - 230, 459, 180, 14, live['progress'])
    elif st == 'scanning' and live.get('hintText'):
        d.rounded_rectangle(box, radius=16, fill=SURF2, outline=WARN, width=2)
        _text(d, (x + 18, 466), live['hintText'], font(24, 'semi'), WARN, anchor='lm', max_w=W - x - 70)
    elif toast and time.time() - toast['ts'] < 5:
        tcol = {'good': GOOD, 'bad': BAD, 'hit': GOLD}.get(toast.get('level'), BLUE)
        d.rounded_rectangle(box, radius=16, fill=SURF2, outline=tcol, width=2)
        _text(d, (x + 18, 466), toast['message'], font(24, 'semi'), TEXT, anchor='lm', max_w=W - x - 70)

    # buttons
    bx, by, bh, gap = x, 516, 92, 12
    bw = (W - 30 - bx - 3 * gap) // 4
    nv = state.get('next_variant')
    labels = [('undo', 'UNDO', False, False), ('reverse', 'REVERSE ON' if nv == 'reverse' else 'REVERSE', False, nv == 'reverse'),
              ('pause', 'RESUME' if st == 'paused' else 'PAUSE', False, st == 'paused'),
              ('next_pack', 'NEXT PACK', True, False)]
    for i, (bid, lab, prim, act) in enumerate(labels):
        rx = bx + i * (bw + gap)
        _button(im, d, (rx, by, rx + bw, by + bh), lab, buttons, bid, hover, primary=prim, active=act)
    return im, buttons


# ---------------------------------------------------------------- pack panel

PACK_SIZE = (1024, 660)


def pack_panel(state: dict, fmt, images: ImageCache, hover: str | None = None):
    W, H = PACK_SIZE
    im, d = _panel_bg(W, H)
    buttons: list = []
    pack = state.get('pack')
    _handle(d, W, buttons, f"Pack {pack['number']}" if pack else 'Pack', hover,
            f"{pack['count']} / {pack['size']}" if pack else None, TEXT2)
    if not pack:
        _text(d, (W // 2, H // 2), 'Choose a set to start', font(32, 'semi'), MUTED, anchor='mm')
        return im, buttons
    cw, ch, gap = 168, 234, 22
    x0 = (W - (5 * cw + 4 * gap)) // 2
    hit = 5.0
    for i in range(min(10, max(pack['size'], pack['count']))):
        cx = x0 + (i % 5) * (cw + gap)
        cy = 92 + (i // 5) * (ch + 20)
        sc = pack['scans'][i] if i < len(pack['scans']) else None
        if sc:
            c = sc.get('card') or {}
            glow = (251, 113, 133, 120) if sc['dupe'] else ((255, 207, 138, 130) if (sc['price'] or 0) >= hit else None)
            _card(im, images.get(c.get('img')), (cx, cy, cx + cw, cy + ch), glow=glow)
        else:
            d.rounded_rectangle((cx, cy, cx + cw, cy + ch), radius=14, outline=SURF3, width=2)
            _text(d, (cx + cw // 2, cy + ch // 2), str(i + 1), font(34, 'bold', True), SURF3, anchor='mm')
    y = H - 64
    profit = pack.get('profit') if pack['count'] else None
    _text(d, (40, y), 'Pulled', font(22), MUTED, anchor='lm')
    _holo_text(im, (130, y), fmt(pack['value']), font(40, 'bold', True), anchor='lm')
    if pack.get('cost') is not None:
        _text(d, (W // 2 + 40, y), f"cost {fmt(pack['cost'])}", font(24), MUTED, anchor='lm')
    if profit is not None:
        _text(d, (W - 40, y), ('+' if profit >= 0 else '-') + fmt(abs(profit)), font(34, 'bold', True),
              GOOD if profit >= 0 else BAD, anchor='rm')
    return im, buttons


# ---------------------------------------------------------------- spotlight

SPOT_SIZE = (620, 900)


def spotlight(scan: dict, fmt, images: ImageCache, jackpot: float, big: float):
    """A big pop-up of the card you just pulled (no buttons)."""
    W, H = SPOT_SIZE
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = scan.get('card') or {}
    price = scan.get('price') or 0
    tier = 'JACKPOT!' if price >= jackpot else 'BIG HIT!' if price >= big else ('DUPLICATE' if scan.get('dupe') else 'NEW')
    tcol = {'JACKPOT!': GOLD, 'BIG HIT!': PINK, 'DUPLICATE': BAD}.get(tier, GOOD)
    glow = (255, 207, 138, 170) if price >= big else ((251, 113, 133, 110) if scan.get('dupe') else (110, 243, 214, 90))
    _card(im, images.get(c.get('imgLarge')) or images.get(c.get('img')), (80, 110, W - 80, 110 + int((W - 160) * 88 / 63)),
          '…', glow=glow)
    tw = d.textlength(tier, font=font(34, 'bold', True)) + 44
    d.rounded_rectangle((W / 2 - tw / 2, 30, W / 2 + tw / 2, 86), radius=28, fill=(11, 11, 18, 230), outline=tcol, width=3)
    _text(d, (W / 2, 58), tier, font(34, 'bold', True), tcol, anchor='mm')
    y = 110 + int((W - 160) * 88 / 63) + 18
    d.rounded_rectangle((40, y, W - 40, H - 20), radius=26, fill=(11, 11, 18, 235))
    _text(d, (W / 2, y + 40), c.get('name', ''), font(38, 'bold', True), TEXT, anchor='mm', max_w=W - 120)
    _holo_text(im, (W / 2, y + 100), fmt(price), font(64, 'bold', True), anchor='mm')
    return im


# ---------------------------------------------------------------- SteamVR dashboard

DASH_SIZE = (1280, 800)


def dashboard(state: dict, fmt, images: ImageCache, settings, hover: str | None = None):
    W, H = DASH_SIZE
    im = Image.new('RGBA', (W, H), BG)
    d = ImageDraw.Draw(im)
    buttons: list = []
    _holo_text(im, (40, 26), 'MivimooseRips Scanner', font(40, 'bold', True))
    label, col = STATE.get(state.get('state'), ('', MUTED))
    _text(d, (W - 40, 50), state.get('message', ''), font(22, 'semi'), col, anchor='rm', max_w=640)
    d.line((32, 92, W - 32, 92), fill=LINE, width=2)

    cs, pack, prog, sess = state.get('set'), state.get('pack'), state.get('progress'), state.get('session') or {}
    x, y = 40, 114
    _text(d, (x, y), cs['name'] if cs else 'No set chosen', font(34, 'bold', True), TEXT, max_w=560)
    if cs:
        _text(d, (x, y + 48), f"{cs['series']}  ·  {cs['release'][:4]}  ·  {cs['packSize']} cards a pack", font(21), MUTED, max_w=560)
    if prog:
        _text(d, (x, y + 92), f"Set {prog['main_owned']}/{prog['main_total']}  ({prog['main_pct']:.0f}%)", font(26, 'semi'))
        _bar(im, d, x, y + 132, 560, 16, prog['main_pct'] / 100)
        _text(d, (x, y + 160), f"Master set {prog['master_owned']}/{prog['master_total']}", font(21), MUTED)
        _bar(im, d, x, y + 194, 560, 10, prog['master_owned'] / max(1, prog['master_total']))
    sp = sess.get('packs') or {}
    y = 342
    _text(d, (x, y), 'This session', font(24, 'bold', True), TEXT2)
    rows = [('Packs opened', str(sp.get('packs', 0))), ('Cards scanned', str(sess.get('scans', 0))),
            ('Pulled value', fmt(sess.get('value', 0))), ('Spent on packs', fmt(sp.get('cost', 0))),
            ('Best card', fmt(sess.get('best', 0)))]
    for i, (k, v) in enumerate(rows):
        _text(d, (x, y + 40 + i * 34), k, font(22), MUTED)
        _text(d, (x + 560, y + 40 + i * 34), v, font(22, 'semi'), TEXT, anchor='ra')

    x2 = 660
    _text(d, (x2, 114), f"Pack {pack['number']}" if pack else 'Pack', font(34, 'bold', True), TEXT)
    if pack:
        _text(d, (W - 40, 120), f"{pack['count']}/{pack['size']}  ·  {fmt(pack['value'])}", font(26, 'bold', True), TEXT2, anchor='ra')
        cw, ch, gap = 104, 145, 12
        for i in range(min(10, max(pack['size'], pack['count']))):
            cx = x2 + (i % 5) * (cw + gap)
            cy = 166 + (i // 5) * (ch + 38)
            sc = pack['scans'][i] if i < len(pack['scans']) else None
            if sc:
                c = sc.get('card') or {}
                _card(im, images.get(c.get('img')), (cx, cy, cx + cw, cy + ch))
                _text(d, (cx + cw // 2, cy + ch + 17), fmt(sc['price']), font(17, 'semi'), BAD if sc['dupe'] else TEXT2, anchor='mm')
            else:
                d.rounded_rectangle((cx, cy, cx + cw, cy + ch), radius=12, outline=SURF3, width=2)

    st = state.get('state')
    nv = state.get('next_variant')
    y = 590
    bw, bh, gap = (W - 80 - 3 * 16) // 4, 78, 16
    names = {'world': 'IN ROOM', 'head': 'FOLLOW HEAD', 'left': 'LEFT HAND', 'right': 'RIGHT HAND'}
    row1 = [('undo', 'UNDO LAST', False, False), ('reverse', 'NEXT = REVERSE' if nv != 'reverse' else 'REVERSE ON', False, nv == 'reverse'),
            ('pause', 'RESUME' if st == 'paused' else 'PAUSE', False, st == 'paused'), ('next_pack', 'NEXT PACK', True, False)]
    row2 = [('hud_mode', 'PANEL: ' + names.get(settings['hud_mode'], settings['hud_mode'].upper()), False, False),
            ('recenter', 'BRING PANELS HERE', False, False),
            ('hud_camera', 'CAMERA VIEW ' + ('ON' if settings['hud_camera'] else 'OFF'), False, settings['hud_camera']),
            ('voice', 'VOICE: ' + {'off': 'OFF', 'price': 'PRICE', 'name_price': 'NAME+PRICE', 'hits': 'HITS'}[settings['voice']], False, settings['voice'] != 'off')]
    for r, row in enumerate((row1, row2)):
        for i, (bid, lab, prim, act) in enumerate(row):
            bx = 40 + i * (bw + gap)
            by = y + r * (bh + 16)
            _button(im, d, (bx, by, bx + bw, by + bh), lab, buttons, bid, hover, primary=prim, active=act)
    return im, buttons


def icon_image(size: int = 256) -> Image.Image:
    """Original app icon: a card in a scanner's corner brackets."""
    s = size
    im = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.22), fill=(9, 13, 30, 255))
    cw, ch = s * 0.42, s * 0.58
    x0, y0 = (s - cw) / 2, (s - ch) / 2
    d.rounded_rectangle((x0, y0, x0 + cw, y0 + ch), radius=int(s * 0.04), fill=(255, 203, 5, 255))
    d.rounded_rectangle((x0 + s * 0.035, y0 + s * 0.035, x0 + cw - s * 0.035, y0 + ch * 0.52), radius=int(s * 0.02), fill=(76, 125, 255, 255))
    for i in range(3):
        yy = y0 + ch * (0.62 + i * 0.1)
        d.rounded_rectangle((x0 + s * 0.05, yy, x0 + cw - s * (0.05 + 0.05 * i), yy + s * 0.025), radius=int(s * 0.012), fill=(27, 20, 0, 200))
    m, L, t = s * 0.12, s * 0.16, max(2, int(s * 0.045))
    col = (62, 224, 161, 255)
    for cx, cy, dx, dy in ((m, m, 1, 1), (s - m, m, -1, 1), (m, s - m, 1, -1), (s - m, s - m, -1, -1)):
        d.line((cx, cy, cx + dx * L, cy), fill=col, width=t)
        d.line((cx, cy, cx, cy + dy * L), fill=col, width=t)
    return im
