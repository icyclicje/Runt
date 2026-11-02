"""Downloads the reference scan of every card once, as grey 240x336 JPEGs, for building the
Android app's built-in card index (android/core: AtlasBuild). Run again to fetch what's new.

usage: python -m tests.fetch_all_refs <cache> [--threads 12]
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import hashlib
import json
import os
import threading
import time
import urllib.request

import cv2
import numpy as np

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) MivimooseScanner/1.0'
IMG_BASE = 'https://images.pokemontcg.io/'
TCGP_BASE = 'https://tcgplayer-cdn.tcgplayer.com/product/'
TCGDEX_BASE = 'https://assets.tcgdex.net/'
REF_W, REF_H = 240, 336


def image_url(img):
    if img.startswith('sx:'):
        s, num = img[3:].split('/', 1)
        return f'https://images.scrydex.com/pokemon/{s}-{num}/small'
    if img.startswith('tcgp:'):
        return f'{TCGP_BASE}{img[5:]}_200w.jpg'
    if img.startswith('tcgdex:'):
        return f'{TCGDEX_BASE}{img[7:]}/low.webp'
    return f'{IMG_BASE}{img}.png'


def name_of(url):
    return hashlib.sha1(url.encode('utf-8')).hexdigest()[:20] + '.jpg'


def bundle_sets(path):
    text = open(path, encoding='utf8').read()
    dec = json.JSONDecoder()
    marker = 'PackData.addSet('
    i = text.find(marker)
    while i >= 0:
        s, end = dec.raw_decode(text, i + len(marker))
        yield s
        i = text.find(marker, end)


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read()


def to_grey(data):
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_UNCHANGED)
    if img is None:
        return None
    if img.ndim == 3 and img.shape[2] == 4:                # alpha corners onto grey, as the app does
        a = img[:, :, 3:4].astype(np.float32) / 255
        img = (img[:, :, :3] * a + 200 * (1 - a)).astype(np.uint8)
    grey = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    interp = cv2.INTER_AREA if grey.shape[0] >= REF_H else cv2.INTER_CUBIC
    return cv2.resize(grey, (REF_W, REF_H), interpolation=interp)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('--threads', type=int, default=12)
    a = ap.parse_args()
    out = os.path.join(a.cache, 'atlas', 'img')
    os.makedirs(out, exist_ok=True)
    todo = {}
    total = 0
    for s in bundle_sets(os.path.join(a.cache, 'data', 'bundle.js')):
        for c in s['cards']:
            img = c[5] if len(c) > 5 else None
            tcg = c[10] if len(c) > 10 else None
            if not img:
                continue
            total += 1
            url = image_url(img)
            if not os.path.exists(os.path.join(out, name_of(url))):
                todo[url] = f'{TCGP_BASE}{tcg}_200w.jpg' if tcg else None
    print(f'{total} cards with images; {len(todo)} images to fetch', flush=True)
    lock = threading.Lock()
    done = [0, 0]
    t0 = time.time()

    def one(item):
        url, fallback = item
        for u in filter(None, (url, fallback)):
            for attempt in range(2):
                try:
                    grey = to_grey(fetch(u))
                    if grey is None:
                        break
                    cv2.imwrite(os.path.join(out, name_of(url)), grey, [cv2.IMWRITE_JPEG_QUALITY, 90])
                    return True
                except urllib.error.HTTPError:
                    break
                except Exception:
                    time.sleep(1)
        return False

    with cf.ThreadPoolExecutor(a.threads) as pool:
        for ok in pool.map(one, todo.items()):
            with lock:
                done[0 if ok else 1] += 1
                n = done[0] + done[1]
                if n % 1000 == 0:
                    print(f'{n}/{len(todo)} ok={done[0]} failed={done[1]} {n / (time.time() - t0):.0f}/s', flush=True)
    print(f'DONE ok={done[0]} failed={done[1]} in {time.time() - t0:.0f}s', flush=True)


if __name__ == '__main__':
    main()
