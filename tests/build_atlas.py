"""Builds the Android app's built-in card index from every card's fingerprint.

Input (tests/.cache/atlas): fp.bin and rows.txt from android/core's AtlasTools test:
    python -m tests.fetch_all_refs tests/.cache
    android/build.ps1 :core:test --tests "*AtlasTools*" -Patlas=fingerprints
Output (android/app/src/main/assets/data):
    atlas.bin         mean, projection and one small signature per image of a card
    atlas_keys.txt.gz which cards show each image

usage: python -m tests.build_atlas <cache> [--k 128]
"""
from __future__ import annotations

import argparse
import gzip
import os
import struct

import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cache')
    ap.add_argument('--k', type=int, default=128)
    a = ap.parse_args()
    d = os.path.join(a.cache, 'atlas')
    out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'android', 'app', 'src', 'main', 'assets', 'data')
    with open(os.path.join(d, 'fp.bin'), 'rb') as f:
        n, dim = struct.unpack('>ii', f.read(8))
        fp = np.frombuffer(f.read(), dtype='<f4').reshape(n, dim)
    mu = fp.mean(0)
    x = fp - mu
    x /= np.linalg.norm(x, axis=1, keepdims=True) + 1e-6
    rng = np.random.default_rng(1)
    s = x[rng.choice(n, min(20000, n), replace=False)].astype(np.float64)
    vals, vecs = np.linalg.eigh(s.T @ s)
    p = vecs[:, np.argsort(-vals)[:a.k]].astype(np.float32)           # dim x k
    z = x @ p
    z /= np.linalg.norm(z, axis=1, keepdims=True) + 1e-6
    scale = float(127 / np.percentile(np.abs(z), 99.9))
    codes = np.clip(np.round(z * scale), -127, 127).astype(np.int8)
    with open(os.path.join(out, 'atlas.bin'), 'wb') as f:
        f.write(b'ATL1')
        f.write(struct.pack('>iiif', n, a.k, dim, scale))
        f.write(mu.astype('>f4').tobytes())
        f.write(p.T.astype('>f4').tobytes())                          # k rows of dim
        f.write(codes.tobytes())
    with open(os.path.join(d, 'rows.txt'), 'rb') as f, gzip.open(os.path.join(out, 'atlas_keys.txt.gz'), 'wb', 9) as g:
        g.write(f.read())
    for name in ('atlas.bin', 'atlas_keys.txt.gz'):
        print(name, round(os.path.getsize(os.path.join(out, name)) / 1e6, 2), 'MB')


if __name__ == '__main__':
    main()
