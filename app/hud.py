"""Panels shown inside the headset, drawn with Pillow.

hud()        the floating scanner panel: last card, price, pack and set progress, camera view
dashboard()  the bigger control panel in the SteamVR dashboard

Both return (RGBA image, [(button id, (x0, y0, x1, y1))]) so clicks from the
controller laser can be matched to buttons.
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
from PIL import Image, ImageDraw, ImageFont

BG = (9, 13, 30, 245)
PANEL = (20, 27, 52, 255)
PANEL2 = (27, 36, 71, 255)
LINE = (60, 72, 120, 255)
TEXT = (238, 241, 251, 255)
MUTED = (143, 153, 189, 255)
ACCENT = (255, 203, 5, 255)
GOOD = (62, 224, 161, 255)
BAD = (255, 107, 125, 255)
BLUE = (76, 125, 255, 255)
VIOLET = (139, 92, 246, 255)

_FONT_DIR = os.path.join(os.environ.get('WINDIR', r'C:\Windows'), 'Fonts')
_fonts: dict = {}


def font(size: int, weight: str = 'regular'):
    key = (size, weight)
    if key not in _fonts:
        files = {'regular': 'segoeui.ttf', 'semi': 'seguisb.ttf', 'bold': 'segoeuib.ttf', 'black': 'seguibl.ttf'}
        try:
            _fonts[key] = ImageFont.truetype(os.path.join(_FONT_DIR, files[weight]), size)
        except OSError:
            _fonts[key] = ImageFont.load_default(size)
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


def _text(d: ImageDraw.ImageDraw, xy, s, f, fill=TEXT, anchor='la', max_w=None):
    s = str(s)
    if max_w:
        while s and d.textlength(s, font=f) > max_w:
            s = s[:-2] + '…' if len(s) > 2 else ''
            if s.endswith('……'):
                s = s[:-2] + '…'
    d.text(xy, s, font=f, fill=fill, anchor=anchor)


def _bar(d, x, y, w, h, frac, fg=ACCENT, bg=PANEL2):
    d.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=bg)
    fw = int(w * max(0.0, min(1.0, frac)))
    if fw > h:
        d.rounded_rectangle((x, y, x + fw, y + h), radius=h // 2, fill=fg)


def _button(d, rect, label, buttons, bid, primary=False, active=False):
    x0, y0, x1, y1 = rect
    fill = ACCENT if primary else (VIOLET if active else PANEL2)
    ink = (27, 20, 0, 255) if primary else TEXT
    d.rounded_rectangle(rect, radius=14, fill=fill, outline=LINE if not primary else None, width=2)
    _text(d, ((x0 + x1) // 2, (y0 + y1) // 2), label, font(26, 'bold'), ink, anchor='mm')
    buttons.append((bid, rect))


def _paste_card(base: Image.Image, img: Image.Image | None, box, placeholder='?'):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    d = ImageDraw.Draw(base)
    if img is None:
        d.rounded_rectangle(box, radius=14, fill=PANEL2, outline=LINE, width=2)
        _text(d, ((x0 + x1) // 2, (y0 + y1) // 2), placeholder, font(64, 'bold'), MUTED, anchor='mm')
        return
    im = img.copy()
    im.thumbnail((w, h), Image.LANCZOS)
    mask = Image.new('L', im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.size[0] - 1, im.size[1] - 1), radius=12, fill=255)
    if im.mode == 'RGBA':
        mask = Image.fromarray(np.minimum(np.array(mask), np.array(im.split()[3])))
    base.paste(im, (x0 + (w - im.size[0]) // 2, y0 + (h - im.size[1]) // 2), mask)


def _cv_to_pil(frame: np.ndarray, size) -> Image.Image:
    img = frame if frame.ndim == 3 else cv2.cvtColor(frame, cv2.COLOR_GRAY2BGR)
    h, w = img.shape[:2]
    sc = min(size[0] / w, size[1] / h)
    img = cv2.resize(img, (max(1, int(w * sc)), max(1, int(h * sc))), interpolation=cv2.INTER_AREA)
    return Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB)).convert('RGBA')


STATE_COLORS = {'scanning': GOOD, 'paused': ACCENT, 'pack_done': ACCENT, 'no_camera': BAD, 'loading': BLUE,
                'preparing': BLUE, 'no_set': ACCENT}

HUD_SIZE = (1024, 600)


def hud(state: dict, fmt, images: ImageCache, camera_img: np.ndarray | None, toast: dict | None, show_camera: bool):
    W, H = HUD_SIZE
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, W - 1, H - 1), radius=28, fill=BG, outline=LINE, width=3)
    buttons: list = []

    # header
    st = state.get('state', 'loading')
    col = STATE_COLORS.get(st, MUTED)
    d.ellipse((28, 26, 48, 46), fill=col)
    _text(d, (60, 36), {'scanning': 'SCANNING', 'paused': 'PAUSED', 'pack_done': 'PACK DONE', 'no_camera': 'NO CAMERA',
                        'loading': 'LOADING', 'preparing': 'GETTING SET READY', 'no_set': 'PICK A SET'}.get(st, st.upper()),
          font(24, 'black'), col, anchor='lm')
    cs, pack = state.get('set'), state.get('pack')
    if cs:
        title = cs['name'] + (f"  ·  Pack {pack['number']}" if pack else '')
        _text(d, (W // 2 + 40, 36), title, font(24, 'semi'), TEXT, anchor='mm', max_w=440)
    if pack:
        _text(d, (W - 30, 36), f"{pack['count']} / {pack['size']} cards", font(26, 'bold'), ACCENT, anchor='rm')
    d.line((24, 68, W - 24, 68), fill=LINE, width=2)

    # last card
    last = state.get('last')
    card = (last or {}).get('card') or {}
    _paste_card(im, images.get(card.get('img')) if card else None, (28, 88, 268, 424), '?' if not last else '…')
    x = 296
    if last and card:
        badge, bcol = ('DUPLICATE', BAD) if last.get('dupe') else ('NEW', GOOD)
        bw = d.textlength(badge, font=font(22, 'black')) + 28
        d.rounded_rectangle((x, 90, x + bw, 124), radius=17, fill=bcol)
        _text(d, (x + bw / 2, 107), badge, font(22, 'black'), (10, 14, 30, 255), anchor='mm')
        if last.get('variant') and last['variant'] != 'normal':
            vb = last['variant'].upper()
            vw = d.textlength(vb, font=font(22, 'black')) + 28
            d.rounded_rectangle((x + bw + 10, 90, x + bw + 10 + vw, 124), radius=17, fill=VIOLET)
            _text(d, (x + bw + 10 + vw / 2, 107), vb, font(22, 'black'), TEXT, anchor='mm')
        _text(d, (x, 138), card.get('name', ''), font(46, 'bold'), TEXT, max_w=W - x - 30)
        _text(d, (x, 200), f"#{card.get('number', '')}  ·  {card.get('rarity') or ''}", font(24), MUTED, max_w=W - x - 30)
        _text(d, (x, 236), fmt(last.get('price')), font(64, 'black'), ACCENT)
    else:
        _text(d, (x, 150), state.get('message', ''), font(34, 'semi'), TEXT, max_w=W - x - 30)
        _text(d, (x, 200), 'Cards are scanned automatically.', font(24), MUTED)

    # pack + set progress
    y = 330
    if pack and not pack['count']:
        cost = pack.get('cost')
        _text(d, (x, y), f"New pack{'  ·  cost ' + fmt(cost) if cost is not None else ''}", font(26, 'semi'), MUTED)
    elif pack:
        cost = pack.get('cost')
        line = f"Pack value {fmt(pack['value'])}"
        if cost is not None:
            p = pack['value'] - cost
            line += f"   cost {fmt(cost)}   "
            _text(d, (x, y), line, font(26, 'semi'), TEXT)
            lw = d.textlength(line, font=font(26, 'semi'))
            _text(d, (x + lw, y), ('+' if p >= 0 else '−') + fmt(abs(p)), font(26, 'bold'), GOOD if p >= 0 else BAD)
        else:
            _text(d, (x, y), line, font(26, 'semi'), TEXT)
    prog = state.get('progress')
    if prog:
        y2 = 378
        pct = prog['main_pct']
        _text(d, (x, y2), f"Set {prog['main_owned']}/{prog['main_total']}  ·  {pct:.0f}%", font(24, 'semi'), TEXT)
        _text(d, (W - 30, y2), f"Master {prog['master_owned']}/{prog['master_total']}", font(22), MUTED, anchor='ra')
        _bar(d, x, y2 + 36, W - x - 30, 14, pct / 100)

    # camera view
    if show_camera and camera_img is not None:
        cam = _cv_to_pil(camera_img, (240, 135))
        cx, cy = 28, 440
        m = Image.new('L', cam.size, 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, cam.size[0] - 1, cam.size[1] - 1), radius=10, fill=255)
        im.paste(cam, (cx + (240 - cam.size[0]) // 2, cy), m)

    # live line: a card being read (with progress), a tip, or the latest event
    live = state.get('live') or {}
    box = (x, 446, W - 28, 494)
    if st == 'scanning' and live.get('pendingName') and live.get('progress', 0) > 0:
        d.rounded_rectangle(box, radius=14, fill=PANEL2, outline=ACCENT, width=2)
        _text(d, (x + 18, 470), f"Reading {live['pendingName']}…", font(24, 'semi'), TEXT, anchor='lm', max_w=W - x - 250)
        _bar(d, W - 228, 463, 180, 14, live['progress'])
    elif st == 'scanning' and live.get('hintText'):
        d.rounded_rectangle(box, radius=14, fill=PANEL2, outline=ACCENT, width=2)
        _text(d, (x + 18, 470), live['hintText'], font(24, 'semi'), ACCENT, anchor='lm', max_w=W - x - 70)
    elif toast and time.time() - toast['ts'] < 5:
        tcol = {'good': GOOD, 'bad': BAD, 'hit': ACCENT}.get(toast.get('level'), BLUE)
        d.rounded_rectangle(box, radius=14, fill=PANEL2, outline=tcol, width=2)
        _text(d, (x + 18, 470), toast['message'], font(24, 'semi'), TEXT, anchor='lm', max_w=W - x - 70)

    # buttons
    bx, by, bh = x, 512, 66
    bw = (W - 28 - bx - 3 * 12) // 4
    nv = state.get('next_variant')
    _button(d, (bx, by, bx + bw, by + bh), 'UNDO', buttons, 'undo')
    _button(d, (bx + (bw + 12), by, bx + 2 * bw + 12, by + bh), 'REVERSE' if nv != 'reverse' else 'REVERSE ✓',
            buttons, 'reverse', active=nv == 'reverse')
    _button(d, (bx + 2 * (bw + 12), by, bx + 3 * bw + 24, by + bh), 'NEXT PACK', buttons, 'next_pack', primary=st == 'pack_done')
    _button(d, (bx + 3 * (bw + 12), by, bx + 4 * bw + 36, by + bh), 'RESUME' if st == 'paused' else 'PAUSE',
            buttons, 'pause', active=st == 'paused')
    return im, buttons


DASH_SIZE = (1280, 800)


def dashboard(state: dict, fmt, images: ImageCache, settings):
    W, H = DASH_SIZE
    im = Image.new('RGBA', (W, H), BG)
    d = ImageDraw.Draw(im)
    buttons: list = []
    _text(d, (40, 44), 'MivimooseRips Scanner', font(40, 'black'), ACCENT, anchor='lm')
    _text(d, (W - 40, 44), state.get('message', ''), font(24, 'semi'), STATE_COLORS.get(state.get('state'), MUTED), anchor='rm', max_w=640)
    d.line((32, 84, W - 32, 84), fill=LINE, width=2)

    cs, pack, prog, sess = state.get('set'), state.get('pack'), state.get('progress'), state.get('session') or {}
    # left column: set + session
    x, y = 40, 108
    _text(d, (x, y), cs['name'] if cs else 'No set chosen', font(34, 'bold'), TEXT, max_w=560)
    if cs:
        _text(d, (x, y + 48), f"{cs['series']}  ·  {cs['release'][:4]}  ·  {cs['packSize']} cards a pack", font(22), MUTED, max_w=560)
    if prog:
        _text(d, (x, y + 96), f"Set {prog['main_owned']}/{prog['main_total']}   ({prog['main_pct']:.0f}%)", font(28, 'semi'))
        _bar(d, x, y + 138, 560, 18, prog['main_pct'] / 100)
        mp = 100 * prog['master_owned'] / max(1, prog['master_total'])
        _text(d, (x, y + 170), f"Master set {prog['master_owned']}/{prog['master_total']}", font(22), MUTED)
        _bar(d, x, y + 204, 560, 12, mp / 100, fg=VIOLET)
    sp = sess.get('packs') or {}
    y = 340
    _text(d, (x, y), 'This session', font(26, 'bold'), TEXT)
    rows = [('Packs opened', str(sp.get('packs', 0))), ('Cards scanned', str(sess.get('scans', 0))),
            ('Pulled value', fmt(sess.get('value', 0))), ('Spent on packs', fmt(sp.get('cost', 0))),
            ('Best card', fmt(sess.get('best', 0)))]
    for i, (k, v) in enumerate(rows):
        _text(d, (x, y + 42 + i * 36), k, font(24), MUTED)
        _text(d, (x + 560, y + 42 + i * 36), v, font(24, 'semi'), TEXT, anchor='ra')

    # right column: current pack
    x2 = 660
    _text(d, (x2, 108), f"Pack {pack['number']}" if pack else 'Pack', font(34, 'bold'), TEXT)
    if pack:
        _text(d, (W - 40, 112), f"{pack['count']}/{pack['size']}  ·  {fmt(pack['value'])}", font(28, 'bold'), ACCENT, anchor='ra')
        cw, ch, gap = 104, 145, 12
        for i, sc in enumerate(pack['scans'][-10:]):
            cx = x2 + (i % 5) * (cw + gap)
            cy = 160 + (i // 5) * (ch + 40)
            c = sc.get('card') or {}
            _paste_card(im, images.get(c.get('img')), (cx, cy, cx + cw, cy + ch))
            _text(d, (cx + cw // 2, cy + ch + 18), fmt(sc['price']), font(18, 'semi'), BAD if sc['dupe'] else TEXT, anchor='mm')
        for i in range(len(pack['scans'][-10:]), min(10, pack['size'])):
            cx = x2 + (i % 5) * (cw + gap)
            cy = 160 + (i // 5) * (ch + 40)
            d.rounded_rectangle((cx, cy, cx + cw, cy + ch), radius=12, outline=LINE, width=2)

    # controls
    st = state.get('state')
    nv = state.get('next_variant')
    y = 590
    bw, bh, gap = (W - 80 - 3 * 16) // 4, 76, 16
    row1 = [('undo', 'UNDO LAST', False, False), ('reverse', 'NEXT = REVERSE' if nv != 'reverse' else 'REVERSE ✓', False, nv == 'reverse'),
            ('next_pack', 'NEXT PACK', st == 'pack_done', False), ('pause', 'RESUME' if st == 'paused' else 'PAUSE', False, st == 'paused')]
    mode_names = {'world': 'IN FRONT', 'head': 'HEAD', 'left': 'LEFT HAND', 'right': 'RIGHT HAND'}
    row2 = [('hud_mode', 'PANEL: ' + mode_names.get(settings['hud_mode'], settings['hud_mode']).upper(), False, False),
            ('recenter', 'RECENTER PANEL', False, False),
            ('hud_camera', 'CAMERA VIEW ' + ('ON' if settings['hud_camera'] else 'OFF'), False, settings['hud_camera']),
            ('voice', 'VOICE: ' + {'off': 'OFF', 'price': 'PRICE', 'name_price': 'NAME+PRICE', 'hits': 'HITS ONLY'}[settings['voice']], False, settings['voice'] != 'off')]
    for r, row in enumerate((row1, row2)):
        for i, (bid, label, primary, active) in enumerate(row):
            bx = 40 + i * (bw + gap)
            by = y + r * (bh + 16)
            _button(d, (bx, by, bx + bw, by + bh), label, buttons, bid, primary=primary, active=active)
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
