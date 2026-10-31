"""Screenshots of every page of the desktop UI, with a realistic collection.

usage: python -m tests.screenshots <cache_dir> <video.mp4> <out_dir> [--set sv3pt5]

Starts the app headless on a demo video (so it scans a few packs), then captures
each page with headless Microsoft Edge (live streams off via ?nolive).
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
EDGE = r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('video')
    ap.add_argument('out')
    ap.add_argument('--set', default='sv3pt5')
    ap.add_argument('--port', type=int, default=47730)
    ap.add_argument('--size', default='1500,1000')
    a = ap.parse_args()
    base = f'http://127.0.0.1:{a.port}'
    a.out = os.path.abspath(a.out)
    os.makedirs(a.out, exist_ok=True)
    dd = tempfile.mkdtemp(prefix='scanner-shots-')
    shutil.copytree(os.path.join(a.cache, 'data'), os.path.join(dd, 'data'))
    shutil.copytree(os.path.join(a.cache, 'refs'), os.path.join(dd, 'refs'))
    with open(os.path.join(dd, 'settings.json'), 'w') as f:
        json.dump({'active_set': a.set, 'refresh_hours': 0, 'sound': False, 'vr_enabled': False, 'currency': 'CAD'}, f)
    proc = subprocess.Popen([sys.executable, os.path.join(ROOT, 'run.py'), '--headless', '--camera',
                             'file:' + os.path.abspath(a.video), '--data-dir', dd, '--port', str(a.port)],
                            cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        t0 = time.time()
        while time.time() - t0 < 90:
            try:
                with urllib.request.urlopen(base + '/api/state', timeout=5) as r:
                    if json.loads(r.read())['session']['scans'] >= 11:
                        break
            except Exception:
                pass
            time.sleep(1)
        for tab in ('scan', 'binder', 'packs', 'collection', 'settings'):
            shot = os.path.join(a.out, f'ui_{tab}.png')
            subprocess.run([EDGE, '--headless=new', '--disable-gpu', '--hide-scrollbars', f'--screenshot={shot}',
                            f'--window-size={a.size}', '--virtual-time-budget=8000',
                            f'--user-data-dir={os.path.join(dd, "edge")}', f'{base}/?nolive#{tab}'],
                           timeout=90, capture_output=True)
            print(tab, 'ok' if os.path.exists(shot) else 'FAILED')
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
