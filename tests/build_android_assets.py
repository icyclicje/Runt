"""Data files bundled with the Android app (android/app/src/main/assets/data/):

  bundle.js.gz, pack-art.js.gz   snapshot of the site's card data, so the first start works offline
  tcg_groups.json                set id -> TCGplayer group id, printed set code and group name, found by
                                 asking TCGplayer which group one card of each set belongs to

usage: python -m tests.build_android_assets <cache>
"""
from __future__ import annotations

import concurrent.futures as cf
import gzip
import json
import os
import shutil
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.carddb import CardDB                      # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'android', 'app', 'src', 'main', 'assets', 'data')
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'


def details(pid: int):
    url = f'https://mp-search-api.tcgplayer.com/v1/product/{pid}/details'
    for i in range(3):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json'})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read())
        except Exception:
            time.sleep(1 + i)
    return None


def main():
    cache = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    db = CardDB(cache)
    db.load(refresh='auto')
    for name in ('bundle.js', 'pack-art.js'):
        src = os.path.join(cache, 'data', name)
        with open(src, 'rb') as f, gzip.open(os.path.join(OUT, name + '.gz'), 'wb', compresslevel=9) as g:
            shutil.copyfileobj(f, g)
    old = {}
    path = os.path.join(OUT, 'tcg_groups.json')
    if os.path.exists(path):
        with open(path, encoding='utf-8') as f:
            old = json.load(f)
    todo = []
    for s in db.sets.values():
        if s.first_edition or s.id in old:
            continue
        if s.id.startswith('tcg-') or s.id.startswith('ja-tcg-'):
            old[s.id] = {'g': int(s.id.rsplit('-', 1)[1])}
            continue
        ids = [c.tcg_id for c in s.cards if c.tcg_id]
        if ids:
            todo.append((s.id, ids[len(ids) // 2]))
    print(len(todo), 'sets to look up')

    def look(item):
        sid, pid = item
        d = details(pid)
        time.sleep(0.25)
        if not d or not d.get('setId'):
            return sid, None
        return sid, {'g': int(d['setId']), 'c': d.get('setCode') or '', 'n': d.get('setName') or ''}

    with cf.ThreadPoolExecutor(3) as pool:
        for sid, v in pool.map(look, todo):
            if v:
                old[sid] = v
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(old, f, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
    print('wrote', path, len(old), 'sets;', sum(os.path.getsize(os.path.join(OUT, n)) for n in os.listdir(OUT)) // 1024, 'KB total')


if __name__ == '__main__':
    main()
