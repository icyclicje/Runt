"""Per sequence: how soon do compressed fingerprints alone name the right card among the whole
catalog, and how often do they name something wrong? (Decides the app's built-in index.)"""
import collections
import os
import struct
import sys

import numpy as np

cache, benches = sys.argv[1], sys.argv[2].split(',')
K = int(sys.argv[3]) if len(sys.argv) > 3 else 128
PRE = int(sys.argv[4]) if len(sys.argv) > 4 else 300     # rows kept by the bit filter
d = os.path.join(cache, 'atlas')
with open(os.path.join(d, 'fp.bin'), 'rb') as f:
    n, dim = struct.unpack('>ii', f.read(8))
    fp = np.frombuffer(f.read(), dtype='<f4').reshape(n, dim)
rows = [l.split('\t') for l in open(os.path.join(d, 'rows.txt'), encoding='utf8').read().split('\n')]
key2row = {k: i for i, r in enumerate(rows) for k in r}


def nm(k):
    return k.split('|')[2].split('#')[0].lower()


row_names = [set(nm(k) for k in r) for r in rows]
mu = np.load(os.path.join(d, 'mu.npy'))
X = fp - mu
X /= np.linalg.norm(X, axis=1, keepdims=True) + 1e-6
P = np.load(os.path.join(d, 'pca.npy'))[:, :K]
Z = X @ P
Z /= np.linalg.norm(Z, axis=1, keepdims=True) + 1e-6
scale = 127 / np.percentile(np.abs(Z), 99.9)
Zi = np.clip(np.round(Z * scale), -127, 127).astype(np.float32) / scale
B = Z > 0
print(f'K={K}: index {K * n / 1e6:.1f} MB + matrix {K * dim * 4 / 1e6:.1f} MB; bit filter keeps {PRE}', flush=True)


def load_queries(name):
    b = open(os.path.join(d, f'q_{name}.bin'), 'rb').read()
    i = 0
    out = []
    while i < len(b):
        l = struct.unpack('>H', b[i:i + 2])[0]
        i += 2
        k = b[i:i + l].decode('utf8')
        i += l
        si, fi, oi = struct.unpack('>iii', b[i:i + 12])
        i += 12
        held = b[i] != 0
        i += 1
        r = np.frombuffer(b, dtype='<f4', count=dim * 2, offset=i).reshape(2, dim)
        i += dim * 2 * 4
        out.append((k, si, fi, oi, held, r))
    return out


def search(qrows):
    q = (qrows - mu)
    q = q / (np.linalg.norm(q, axis=1, keepdims=True) + 1e-6)
    q = q @ P
    q /= np.linalg.norm(q, axis=1, keepdims=True) + 1e-6
    keep = []
    for r in q:
        h = np.count_nonzero(B != (r > 0), axis=1)
        keep.append(np.argpartition(h, PRE)[:PRE])
    cand = np.unique(np.concatenate(keep))
    s = np.maximum(q[0] @ Zi[cand].T, q[1] @ Zi[cand].T)
    o = np.argsort(-s)[:40]
    top = cand[o]
    sc = s[o]
    t = top[0]
    m = 0.0
    for j, x in zip(top[1:], sc[1:]):
        if not (row_names[j] & row_names[t]):
            m = float(sc[0] - x)
            break
    # gap to the next image of any name: reprints of the same artwork are close
    gap = float(sc[0] - sc[1])
    return int(t), float(sc[0]), m, gap, top


rules = [(0.08, 2), (0.1, 2), (0.12, 2), (0.14, 2), (0.08, 3), (0.1, 3), (0.2, 1)]
for b in benches:
    qs = load_queries(b)
    seqs = collections.defaultdict(list)
    for k, si, fi, oi, held, r in qs:
        seqs[si].append((fi, oi, k, held, r))
    res = {r: [0, 0, 0, []] for r in rules}
    total = any1 = any40 = 0
    for si, items in sorted(seqs.items()):
        items.sort(key=lambda x: (x[0], x[1]))
        key = items[0][2]
        total += bool(key)
        frames = collections.defaultdict(list)
        first_held = min((x[0] for x in items if x[3]), default=0)
        tr = key2row.get(key) if key else None
        hit1 = hit40 = False
        for fi, oi, k, held, r in items:
            t, s, m, gap, top = search(r)
            frames[fi].append((t, s, m, gap))
            if key:
                hit1 |= nm(key) in row_names[t]
                hit40 |= tr in top
        any1 += hit1
        any40 += hit40
        for rule in rules:
            M, N = rule
            streak = 0
            last = None
            prev = -9
            for fi in sorted(frames):
                t, s, m, gap = max(frames[fi], key=lambda f: f[2])
                if m >= M:
                    same = last is not None and (row_names[t] & row_names[last]) and fi - prev <= 3
                    streak = streak + 1 if same else 1
                    last = t
                    prev = fi
                    if streak >= N:
                        if key and nm(key) in row_names[t]:
                            res[rule][0] += 1
                            res[rule][1] += key in rows[t]
                            res[rule][3].append(fi - first_held)
                        else:
                            res[rule][2] += 1
                            if rule == (0.12, 2) or rule == (0.14, 2):
                                print(f'     {rule} seq {si} truth "{key}": named {rows[t][0]} score {s:.2f} margin {m:.2f}')
                        break
    print(f'{b}: {len(seqs)} seqs; right name top-1 on some frame {any1}/{total}, right card in top-40 {any40}/{total}', flush=True)
    for rule, (ok, exact, bad, delays) in res.items():
        md = int(np.median(delays)) if delays else None
        print(f'     margin>={rule[0]} on {rule[1]} frames: right name {ok}/{total} (exact print {exact}), FALSE {bad}, median delay {md} frames', flush=True)
