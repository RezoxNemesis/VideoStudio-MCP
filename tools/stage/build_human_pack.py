#!/usr/bin/env python3
"""Pack the MakeHuman base body (CC0 assets: hm08 mesh, default rig, weights, shape and expression targets) for the Stage.

usage: build_human_pack.py --mh /path/to/makehuman/makehuman/data --out human.pack
The app sculpts every character from this one file at run time (src/stage/human/factory.ts).
"""
import argparse, json, os, sys
import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from packlib import write_pack

KEEP_GROUPS = ['body', 'helper-tights', 'helper-skirt', 'helper-hair', 'helper-l-eye', 'helper-r-eye', 'helper-upper-teeth', 'helper-lower-teeth',
               'helper-l-eyelashes-1', 'helper-l-eyelashes-2', 'helper-r-eyelashes-1', 'helper-r-eyelashes-2', 'helper-tongue']
SKIP_TARGET_DIRS = ('genitals',)

REGION_ID = {'other': 0, 'torso': 1, 'upperarm': 2, 'forearm': 3, 'thigh': 4, 'shin': 5, 'foot': 6, 'hand': 7, 'head': 8}
REGION = {}
for b in ['root', 'spine01', 'spine02', 'spine03', 'spine04', 'spine05', 'pelvis.L', 'pelvis.R', 'clavicle.L', 'clavicle.R', 'breast.L', 'breast.R', 'neck01', 'neck02', 'neck03']:
    REGION[b] = 'torso'
for s in 'LR':
    for b in ['shoulder01', 'upperarm01', 'upperarm02']: REGION[f'{b}.{s}'] = 'upperarm'
    for b in ['lowerarm01', 'lowerarm02']: REGION[f'{b}.{s}'] = 'forearm'
    for b in ['upperleg01', 'upperleg02']: REGION[f'{b}.{s}'] = 'thigh'
    for b in ['lowerleg01', 'lowerleg02']: REGION[f'{b}.{s}'] = 'shin'
    REGION[f'foot.{s}'] = 'foot'; REGION[f'wrist.{s}'] = 'hand'
HEADB = ('head', 'jaw', 'levator', 'oris', 'oculi', 'orbicularis', 'risorius', 'temporalis', 'special', 'eye', 'tongue')


def region_of(n):
    if n in REGION: return REGION[n]
    if n.startswith('toe'): return 'foot'
    if n.startswith('finger') or n.startswith('metacarpal'): return 'hand'
    if n.startswith(HEADB): return 'head'
    return 'other'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--mh', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    MH = a.mh
    arrays = {}

    # ---- base mesh
    V = []; groups = {}; cur = None
    for line in open(f'{MH}/3dobjs/base.obj'):
        if line.startswith('v '): V.append([float(x) for x in line.split()[1:4]])
        elif line.startswith('g '): cur = line.split()[1]
        elif line.startswith('f '):
            ps = [int(p.split('/')[0]) - 1 for p in line.split()[1:]]
            if len(ps) == 3: ps.append(ps[2])
            groups.setdefault(cur, []).append(ps[:4])
    V = np.array(V, np.float32); N = len(V)
    arrays['v'] = V
    gmeta = {}
    for g in KEEP_GROUPS:
        q = np.array(groups[g], np.uint32); arrays[f'g:{g}'] = q; gmeta[g] = int(len(q))

    # ---- rig (parents first)
    sk = json.load(open(f'{MH}/rigs/default.mhskel')); wt = json.load(open(f'{MH}/rigs/default_weights.mhw'))['weights']
    order = []
    def visit(n):
        if n in order: return
        p = sk['bones'][n]['parent']
        if p: visit(p)
        order.append(n)
    for n in sk['bones']: visit(n)
    bidx = {n: i for i, n in enumerate(order)}
    jv = []; bones = []
    def joint(name):
        ids = sk['joints'][name]; off = len(jv); jv.extend(ids); return [off, len(ids)]
    for n in order:
        b = sk['bones'][n]
        bones.append({'name': n, 'parent': bidx.get(b['parent'], -1), 'head': joint(b['head']), 'tail': joint(b['tail'])})
    arrays['jointVerts'] = np.array(jv, np.uint32)

    # ---- weights: top 4 per vertex + the per-vertex sums the builder needs
    W = np.zeros((N, len(order)), np.float32)
    for b, lst in wt.items():
        arr = np.array(lst); W[arr[:, 0].astype(int), bidx[b]] = arr[:, 1]
    top = np.argsort(-W, axis=1)[:, :4]
    w4 = np.take_along_axis(W, top, axis=1); w4 /= np.maximum(w4.sum(1, keepdims=True), 1e-8)
    arrays['skinIndex'] = top.astype(np.uint16); arrays['skinWeight'] = w4.astype(np.float32)
    arrays['region'] = np.array([REGION_ID[region_of(order[d])] for d in top[:, 0]], np.uint8)
    def wsum(pref): return W[:, [i for i, n in enumerate(order) if n.startswith(pref)]].sum(1).astype(np.float32)
    for pref in ('risorius', 'orbicularis', 'jaw', 'oris'):
        arrays[f'w:{pref}'] = wsum(pref)

    # ---- targets (sparse, quantised)
    tidx = []; td = []; tmeta = {}; pos = 0
    troot = f'{MH}/targets'
    for root, _dirs, files in sorted(os.walk(troot)):
        rel = os.path.relpath(root, troot)
        if rel.split(os.sep)[0] in SKIP_TARGET_DIRS: continue
        for fn in sorted(files):
            if not fn.endswith('.target'): continue
            idx = []; d = []
            for line in open(os.path.join(root, fn)):
                if line.startswith('#') or not line.strip(): continue
                p = line.split(); idx.append(int(p[0])); d.append((float(p[1]), float(p[2]), float(p[3])))
            if not idx: continue
            d = np.array(d, np.float64); m = float(np.abs(d).max()) or 1.0
            q = np.round(d / m * 32767).astype(np.int16)
            name = (fn[:-7] if rel == '.' else f'{rel}/{fn[:-7]}').replace(os.sep, '/')
            tmeta[name] = [pos, len(idx), m / 32767]
            tidx.append(np.array(idx, np.uint16)); td.append(q); pos += len(idx)
    arrays['tIdx'] = np.concatenate(tidx); arrays['tD'] = np.concatenate(td).reshape(-1)

    expr = sorted(k.split('/')[-1] for k in tmeta if k.startswith('expression/units/caucasian/'))
    meta = {'kind': 'human', 'source': 'MakeHuman hm08 base mesh, default rig and targets (CC0 1.0)', 'verts': N, 'bodyVerts': 13380, 'groups': gmeta, 'bones': bones,
            'regions': REGION_ID, 'targets': tmeta, 'expressions': expr}
    size = write_pack(a.out, meta, arrays)
    print(f'{a.out}: {size / 1e6:.1f} MB, {N} verts, {len(bones)} bones, {len(tmeta)} targets, {pos} target entries')


if __name__ == '__main__':
    main()
