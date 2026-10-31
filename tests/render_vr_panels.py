"""Renders the VR panels from a live app state, to look at them without a headset.

usage: python -m tests.render_vr_panels <cache_dir> <video.mp4> <out_dir> [--set sv3pt5]
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('video')
    ap.add_argument('out')
    ap.add_argument('--set', default='sv3pt5')
    ap.add_argument('--port', type=int, default=47731)
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    os.makedirs(out, exist_ok=True)
    base = f'http://127.0.0.1:{a.port}'
    dd = tempfile.mkdtemp(prefix='scanner-vr-')
    shutil.copytree(os.path.join(a.cache, 'data'), os.path.join(dd, 'data'))
    shutil.copytree(os.path.join(a.cache, 'refs'), os.path.join(dd, 'refs'))
    with open(os.path.join(dd, 'settings.json'), 'w') as f:
        json.dump({'active_set': a.set, 'refresh_hours': 0, 'sound': False, 'vr_enabled': False, 'currency': 'CAD'}, f)
    proc = subprocess.Popen([sys.executable, os.path.join(ROOT, 'run.py'), '--headless', '--camera',
                             'file:' + os.path.abspath(a.video), '--data-dir', dd, '--port', str(a.port)],
                            cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        st = None
        t0 = time.time()
        while time.time() - t0 < 90:
            try:
                with urllib.request.urlopen(base + '/api/state', timeout=5) as r:
                    st = json.loads(r.read())
                if st['session']['scans'] >= 7:
                    break
            except Exception:
                pass
            time.sleep(1)
        import cv2
        import numpy as np
        from app import hud
        from app.config import Settings
        from app.currency import Rates
        rates = Rates(dd)
        fmt = lambda usd: rates.fmt(usd, 'CAD')
        images = hud.ImageCache()
        urls = [st['last']['card']['img'], st['last']['card']['imgLarge']] + [s['card']['img'] for s in st['pack']['scans']]
        for u in urls:
            images.get(u)
        t1 = time.time()
        while time.time() - t1 < 20 and any(images.get(u) is None for u in urls):
            time.sleep(0.3)
        with urllib.request.urlopen(base + '/frame.jpg?w=480', timeout=5) as r:
            cam = cv2.imdecode(np.frombuffer(r.read(), np.uint8), cv2.IMREAD_COLOR)
        settings = Settings(os.path.join(dd, 'settings.json'))

        def save(img, name):
            bg = hud.Image.new('RGBA', img.size, (52, 56, 64, 255))     # stand-in for the room behind
            bg.alpha_composite(img)
            bg.save(os.path.join(out, name))
        im, btns = hud.hud(st, fmt, images, cam, None, True, hover='reverse')
        save(im, 'vr_scanner.png')
        print('scanner buttons:', [b[0] for b in btns])
        save(hud.pack_panel(st, fmt, images)[0], 'vr_pack.png')
        spot = dict(st['last'])
        spot['price'] = 412.55                         # show the jackpot look
        save(hud.spotlight(spot, fmt, images, 100, 50), 'vr_spotlight.png')
        save(hud.dashboard(st, fmt, images, settings, hover='next_pack')[0], 'vr_dashboard.png')
        print('saved to', out)
    finally:
        try:
            urllib.request.urlopen(urllib.request.Request(base + '/api/quit', data=b'{}', method='POST',
                                                          headers={'X-Scanner': '1'}), timeout=5)
            proc.wait(15)
        except Exception:
            proc.kill()
        shutil.rmtree(dd, ignore_errors=True)


if __name__ == '__main__':
    main()
