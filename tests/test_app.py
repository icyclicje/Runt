"""End-to-end check: run the real app headless on a demo video and inspect what it recorded.

usage: python -m tests.test_app <cache_dir> <video.mp4> [--exe dist\\MivimooseScanner.exe] [--set sv3pt5]

<video.mp4> comes from tests.make_demo_video (its .txt lists the cards in order).
The app is started with a fresh data folder seeded from <cache_dir> (card data and
the set's reference data), so it doesn't download anything.
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
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('video')
    ap.add_argument('--exe')
    ap.add_argument('--set', default='sv3pt5')
    ap.add_argument('--port', type=int, default=47720)
    a = ap.parse_args()
    base = f'http://127.0.0.1:{a.port}'

    def get(p):
        with urllib.request.urlopen(base + p, timeout=15) as r:
            return json.loads(r.read())

    def post(p, body):
        req = urllib.request.Request(base + p, data=json.dumps(body).encode(), method='POST',
                                     headers={'Content-Type': 'application/json', 'X-Scanner': '1'})
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read())

    dd = tempfile.mkdtemp(prefix='scanner-test-')
    shutil.copytree(os.path.join(a.cache, 'data'), os.path.join(dd, 'data'))
    shutil.copytree(os.path.join(a.cache, 'refs'), os.path.join(dd, 'refs'))
    with open(os.path.join(dd, 'settings.json'), 'w') as f:
        json.dump({'active_set': a.set, 'refresh_hours': 0, 'sound': False, 'vr_enabled': False}, f)
    cmd = [os.path.abspath(a.exe)] if a.exe else [sys.executable, os.path.join(ROOT, 'run.py')]
    cmd += ['--headless', '--camera', 'fileonce:' + os.path.abspath(a.video), '--data-dir', dd, '--port', str(a.port)]
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    failures = 0

    def check(name, ok, detail=''):
        nonlocal failures
        print(('PASS ' if ok else 'FAIL ') + name + (f'  ({detail})' if detail else ''))
        failures += not ok

    try:
        t0 = time.time()
        st = None
        while time.time() - t0 < 90:
            try:
                st = get('/api/state')
                if st['state'] == 'scanning':
                    break
            except Exception:
                pass
            time.sleep(0.5)
        check('app starts scanning', st is not None and st['state'] == 'scanning', st and st['message'])
        t1 = time.time()
        while time.time() - t1 < 240:
            time.sleep(0.5)
            if (get('/api/state').get('camera') or {}).get('status') == 'ended':
                break
        time.sleep(1.0)
        post('/api/action', {'action': 'pause', 'value': True})
        st = get('/api/state')
        print(f"video played in {time.time() - t1:.1f}s; scanning {st['perf']}")
        scans = list(reversed(st['recent']))
        for sc in scans:
            print(f"   pack {sc['pack_id']}  {'DUPE' if sc['dupe'] else 'new '}  {sc['card_key']:<36} ${sc['price']}")
        expected = open(a.video + '.txt', encoding='utf-8').read().split('\n')
        got = [s['card_key'] for s in scans]
        distinct = expected[:-2]
        check('every card scanned', set(distinct) <= set(got), f'missed {[k for k in distinct if k not in got]}')
        check('nothing scanned that was not shown', set(got) <= set(expected), f'extra {[k for k in got if k not in expected]}')
        check('no card counted twice in a pack',
              all(len({s['card_key'] for s in scans if s['pack_id'] == p}) == sum(1 for s in scans if s['pack_id'] == p)
                  for p in {s['pack_id'] for s in scans}))
        first = expected[0]
        check('re-shown owned card flagged duplicate', any(s['card_key'] == first and s['dupe'] for s in scans))
        packs = get('/api/packs')['packs']
        check('pack tracker closed a full pack', any(p['complete'] for p in packs), f"{[(p['cards'], p['size']) for p in packs]}")
        live = get('/api/live')
        check('live endpoint', 'state' in live)
        sid = scans[-1]['id']
        post('/api/action', {'action': 'set_variant', 'scan_id': sid, 'variant': 'reverse'})
        check('variant change', get('/api/state')['last']['variant'] == 'reverse')
        post('/api/action', {'action': 'undo'})
        check('undo', get('/api/state')['last']['id'] != sid)
        check('set progress', get(f'/api/set/{a.set}')['master_owned'] > 0)
        check('collection', get('/api/collection')['count'] > 0)
        check('search', len(get(f'/api/search?q=a&set={a.set}')['cards']) > 0)
        with urllib.request.urlopen(base + '/frame.jpg', timeout=5) as r:
            check('camera frame', len(r.read()) > 1000)
        with urllib.request.urlopen(base + '/', timeout=5) as r:
            check('web page', b'<html' in r.read())
        try:
            urllib.request.urlopen(urllib.request.Request(base + '/api/action', data=b'{"action":"undo"}', method='POST'), timeout=5)
            check('cross-site requests refused', False)
        except urllib.error.HTTPError as e:
            check('cross-site requests refused', e.code == 403)
        vr = get('/api/vr/check')
        check('VR check answers', 'steamvr_running' in vr, json.dumps(vr)[:120])
        post('/api/quit', {})
        proc.wait(timeout=20)
        check('clean exit', proc.returncode == 0)
    finally:
        if proc.poll() is None:
            proc.kill()
        log = os.path.join(dd, 'logs', 'scanner.log')
        if os.path.exists(log):
            bad = [l for l in open(log, encoding='utf-8').read().splitlines() if ' ERROR ' in l or 'Traceback' in l]
            check('no errors in the log', not bad, bad[:3])
        shutil.rmtree(dd, ignore_errors=True)
    print('\nall passed' if not failures else f'\n{failures} FAILED')
    return failures


if __name__ == '__main__':
    sys.exit(main())
