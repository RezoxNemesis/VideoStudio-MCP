"""Character builder: open MakeHuman base mesh -> morphed, dressed, rigged toon character for the web engine.
Units metres, y up, character faces +z, its left is +x."""
import json, gzip, os, struct, numpy as np
from scipy.spatial import cKDTree
from cinepaths import MPFB as D, CHARS as OUT
REG = dict(skin=0, top=1, bottom=2, shoe=3, sole=4, hair=5, eye=6, dark=7, sock=8, trim=9, top2=10)
_cache = {}

def load_base():
    if 'base' in _cache: return _cache['base']
    V = []; groups = {}; cur = None
    for l in open(D + '3dobjs/base.obj'):
        if l[:2] == 'v ': V.append([float(x) for x in l.split()[1:4]])
        elif l[:2] == 'g ': cur = l.split()[1]
        elif l[:2] == 'f ': groups.setdefault(cur, []).append([int(t.split('/')[0]) - 1 for t in l.split()[1:]])
    _cache['base'] = (np.array(V), groups)
    return _cache['base']

def target(path):
    if path in _cache: return _cache[path]
    idx = []; d = []
    for l in gzip.open(D + 'targets/' + path + '.target.gz', 'rt'):
        p = l.split()
        if len(p) == 4 and p[0].isdigit():
            idx.append(int(p[0])); d.append([float(p[1]), float(p[2]), float(p[3])])
    _cache[path] = (np.array(idx, int), np.array(d).reshape(-1, 3))
    return _cache[path]

def rig():
    if 'rig' in _cache: return _cache['rig']
    r = json.load(open(D + 'rigs/standard/rig.game_engine.json'))
    w = json.load(open(D + 'rigs/standard/weights.game_engine.json'))['weights']
    names = []; todo = dict(r)
    while todo:  # parents first
        for k in sorted(todo):
            p = todo[k].get('parent') or ''
            if p == '' or p in names: names.append(k); del todo[k]; break
    _cache['rig'] = (r, w, names)
    return _cache['rig']

def macro(gender='male', child=0.0, eth=(0.55, 0.45), weight=0.0, muscle=0.0):
    """child: 1 = ten years old, 0 = young adult. weight/muscle -1..1"""
    rec = {}
    for age, wa in (('child', child), ('young', 1 - child)):
        if wa <= 0: continue
        for e, we in zip(('caucasian', 'asian'), eth):
            rec[f'macrodetails/{e}-{gender}-{age}'] = wa * we
        wt = 'maxweight' if weight > 0 else 'minweight'; mu = 'maxmuscle' if muscle > 0 else 'minmuscle'
        aw, am = abs(weight), abs(muscle)
        if aw > 0: rec[f'macrodetails/universal-{gender}-{age}-averagemuscle-{wt}'] = wa * aw * (1 - am)
        if am > 0: rec[f'macrodetails/universal-{gender}-{age}-{mu}-averageweight'] = wa * am * (1 - aw)
        if aw > 0 and am > 0: rec[f'macrodetails/universal-{gender}-{age}-{mu}-{wt}'] = wa * aw * am
    return rec

# ---------- small mesh helpers ----------
def tri(faces):
    T = []
    for f in faces:
        for i in range(1, len(f) - 1): T.append([f[0], f[i], f[i + 1]])
    return np.array(T, int).reshape(-1, 3)

def normals(V, T):
    n = np.zeros_like(V); fn = np.cross(V[T[:, 1]] - V[T[:, 0]], V[T[:, 2]] - V[T[:, 0]])
    for i in range(3): np.add.at(n, T[:, i], fn)
    return n / (np.linalg.norm(n, axis=1, keepdims=True) + 1e-12)

def adjacency(n, faces):
    nb = [set() for _ in range(n)]
    for f in faces:
        for i in range(len(f)):
            a, b = f[i], f[(i + 1) % len(f)]; nb[a].add(b); nb[b].add(a)
    return nb

def boundary(faces):
    from collections import Counter
    c = Counter()
    for f in faces:
        for i in range(len(f)):
            a, b = f[i], f[(i + 1) % len(f)]; c[(min(a, b), max(a, b))] += 1
    s = set()
    for (a, b), k in c.items():
        if k == 1: s.add(a); s.add(b)
    return s

def smooth(V, faces, it=3, lam=0.5, keep=None, mask=None):
    nb = adjacency(len(V), faces); V = V.copy()
    ids = [i for i in range(len(V)) if nb[i] and (mask is None or mask[i])]
    for _ in range(it):
        N = V.copy()
        for i in ids:
            if keep is not None and i in keep:
                kn = [j for j in nb[i] if j in keep]
                if len(kn) >= 2: N[i] = V[i] + lam * 0.5 * (np.mean(V[kn], 0) - V[i])
                continue
            N[i] = V[i] + lam * (np.mean(V[list(nb[i])], 0) - V[i])
        V = N
    return V

def subdivide(V, faces, W=None):
    """one linear quad subdivision; returns new V, faces (, W)"""
    V = list(map(tuple, V)); em = {}; newf = []; Wn = list(W) if W is not None else None
    def mid(a, b):
        k = (min(a, b), max(a, b))
        if k not in em:
            em[k] = len(V); V.append(tuple((np.array(V[a]) + np.array(V[b])) / 2))
            if Wn is not None: Wn.append((Wn[a] + Wn[b]) / 2)
        return em[k]
    for f in faces:
        c = len(V); V.append(tuple(np.mean([V[i] for i in f], 0)))
        if Wn is not None: Wn.append(np.mean([Wn[i] for i in f], 0))
        m = [mid(f[i], f[(i + 1) % len(f)]) for i in range(len(f))]
        for i in range(len(f)): newf.append([f[i], m[i], c, m[i - 1]])
    return (np.array(V), newf, np.array(Wn)) if W is not None else (np.array(V), newf)

def seg_rad(P, a, b):
    ab = b - a; t = np.clip((P - a) @ ab / (ab @ ab), 0, 1); c = a + t[:, None] * ab
    r = P - c; d = np.linalg.norm(r, axis=1)
    return t, d, r / (d[:, None] + 1e-9), c

class Part:
    def __init__(s, V, faces, W, freg, name=''):
        s.V = np.asarray(V, float); s.faces = [list(f) for f in faces]; s.W = np.asarray(W, np.float32)
        s.freg = list(freg) if not np.isscalar(freg) else [freg] * len(faces); s.name = name
        s.attr = {}  # per-vertex float/vec3 attributes

def sub(Vall, Wall, faces, vmask=None):
    """extract faces (all verts in mask) -> local arrays and index map"""
    fs = [f for f in faces if vmask is None or all(vmask[i] for i in f)]
    ids = sorted({i for f in fs for i in f}); m = {g: l for l, g in enumerate(ids)}
    return np.array(ids, int), [[m[i] for i in f] for f in fs]

# ---------- the character ----------
def build(name, gender='male', child=0.0, weight=0.0, muscle=0.0, eth=(0.55, 0.45), scale=1.0,
          sleeve=0.5, shorts=False, hair='short', top_len=0.0, loose=1.0, extra=None, skirt=False):
    base, groups = load_base(); r, wj, bnames = rig()
    V = base.copy()
    rec = macro(gender, child, eth, weight, muscle)
    if extra: rec.update(extra)
    for k, w in rec.items():
        i, d = target(k); V[i] += w * d
    V *= 0.1
    def jpos(g): return V[sorted({i for f in groups[g] for i in f})].mean(0)
    V[:, 1] -= jpos('joint-ground')[1]
    nv = len(V); nb = len(bnames); bi = {b: i for i, b in enumerate(bnames)}
    head = np.zeros((nb, 3)); tail = np.zeros((nb, 3)); par = np.full(nb, -1)
    for b in bnames:
        for key, arr in (('head', head), ('tail', tail)):
            h = r[b][key]
            if h.get('strategy') == 'CUBE' and h.get('cube_name') in groups: arr[bi[b]] = jpos(h['cube_name'])
            else:
                x, y, z = h['default_position']; arr[bi[b]] = [x, z, -y]; print('  non-cube', b, key, h.get('strategy'))
        p = r[b].get('parent') or ''; par[bi[b]] = bi[p] if p else -1
    W = np.zeros((nv, nb), np.float32)
    for b, lst in wj.items():
        if b in bi and lst:
            a = np.array(lst); W[a[:, 0].astype(int), bi[b]] = a[:, 1]
    W /= np.maximum(W.sum(1, keepdims=True), 1e-9)
    dom = W.argmax(1)
    tb = np.zeros(nv)
    for b in range(nb):
        m = dom == b; ab = tail[b] - head[b]
        tb[m] = (V[m] - head[b]) @ ab / max(ab @ ab, 1e-9)
    H = V[[i for f in groups['body'] for i in f]][:, 1].max()
    S = H / 1.75  # size factor against a 1.75 m adult
    B = lambda *n: np.isin(dom, [bi[x] for x in n])
    y = V[:, 1]
    hipy = head[bi['thigh_l']][1]; pely = head[bi['pelvis']][1]
    hem_y = hipy - (0.035 + top_len) * S; waist_y = pely + 0.09 * S
    arm = B('upperarm_l', 'upperarm_r'); fore = B('lowerarm_l', 'lowerarm_r')
    torso = B('spine_01', 'spine_02', 'spine_03', 'clavicle_l', 'clavicle_r')
    low = B('pelvis', 'thigh_l', 'thigh_r'); calf = B('calf_l', 'calf_r'); thigh = B('thigh_l', 'thigh_r')
    neck = B('neck_01')
    def shirt_mask(mg):  # mg: margin (positive = shrink)
        m = torso | (neck & (tb < 0.22 - mg)) | (low & (y > hem_y + mg * 0.25)) | (arm & (tb < min(sleeve, 1.01) - mg))
        if sleeve > 1.0: m |= fore & (tb < (sleeve - 1.0) - mg)
        return m
    def pants_mask(mg):
        m = ((B('pelvis', 'spine_01') | thigh) & (y < waist_y - mg * 0.2))
        if shorts: m &= ~thigh | (tb < 0.78 - mg)
        else: m |= calf & (tb < 0.93 - mg)
        return m
    parts = []
    # ---- shirt ----
    tf = groups['helper-tights']
    ids, fs = sub(V, W, tf, shirt_mask(0))
    P = V[ids].copy(); Wp = W[ids]; d = dom[ids]; t = tb[ids]
    bnd = boundary(fs)
    ax_a = head[bi['pelvis']] * [0, 1, 0] + [0, 0, 0]; ax_a[0] = 0; ax_a[2] = head[bi['spine_02']][2]
    ax_b = head[bi['neck_01']].copy(); ax_b[0] = 0
    isT = np.isin(d, [bi[x] for x in ('spine_01', 'spine_02', 'spine_03', 'pelvis', 'thigh_l', 'thigh_r')])
    _, rr, rd, cc = seg_rad(P, ax_a, ax_b)
    th = np.arctan2(P[:, 2] - cc[:, 2], P[:, 0] - cc[:, 0]); nbins = 36
    bins = ((th + np.pi) / (2 * np.pi) * nbins).astype(int) % nbins
    ymid = head[bi['spine_03']][1]
    up = np.isin(d, [bi['spine_02'], bi['spine_03']]) & (P[:, 1] > head[bi['spine_02']][1])
    rref = np.zeros(nbins)
    for k in range(nbins):
        m = up & (bins == k); rref[k] = rr[m].max() if m.any() else 0
    rref = np.where(rref > 0, rref, rref[rref > 0].mean())
    rref = (np.roll(rref, 1) + rref * 2 + np.roll(rref, -1)) / 4
    lowm = isT & (P[:, 1] < ymid)
    f = np.clip((P[:, 1] - hem_y) / max(ymid - hem_y, 1e-3), 0, 1)
    tgt = rref[bins] * (0.9 + 0.08 * f) * (0.94 + 0.06 * loose)
    newr = np.where(lowm, np.maximum(rr + 0.008 * S, tgt), rr + 0.007 * S)
    P = np.where(isT[:, None], cc + rd * newr[:, None], P)
    for side in 'lr':  # sleeves: straight loose tubes
        for bn, t0 in ((f'upperarm_{side}', 0.22), (f'lowerarm_{side}', 0.0)):
            m = d == bi[bn]
            if not m.any(): continue
            tt, r2, rd2, c2 = seg_rad(P[m], head[bi[bn]], tail[bi[bn]])
            ref = np.median(r2[(tt > 0.1) & (tt < 0.35)]) if ((tt > 0.1) & (tt < 0.35)).any() else np.median(r2)
            if bn.startswith('lower'): ref *= 0.9
            nr = np.where(tt > t0, np.maximum(r2 + 0.006 * S, ref + 0.010 * S * loose), r2 + 0.006 * S)
            P[m] = c2 + rd2 * nr[:, None]
    for i in bnd:  # clean hems
        if d[i] in (bi['pelvis'], bi['thigh_l'], bi['thigh_r']): P[i, 1] = hem_y
    P = smooth(P, fs, 4, 0.5, keep=bnd)
    sh = Part(P, fs, Wp, REG['top'], 'shirt'); sh.attr['loose'] = np.clip((ymid - P[:, 1]) / max(ymid - hem_y, 1e-3), 0, 1) * isT + 0.6 * np.isin(d, [bi['upperarm_l'], bi['upperarm_r']]) * np.clip(t * 2, 0, 1)
    for k, f_ in enumerate(fs):  # trim band at hem, cuffs and collar
        if sum(1 for i in f_ if i in bnd) >= 2: sh.freg[k] = REG['trim']
    parts.append(sh)
    # ---- pants ----
    ids, fs = sub(V, W, tf, pants_mask(0))
    P = V[ids].copy(); Wp = W[ids]; d = dom[ids]; bnd = boundary(fs)
    for side in 'lr':
        a = head[bi[f'thigh_{side}']]; k = head[bi[f'calf_{side}']]; e = tail[bi[f'calf_{side}']]
        m = np.isin(d, [bi[f'thigh_{side}'], bi[f'calf_{side}']])
        if not m.any(): continue
        Q = P[m]
        t1, r1, d1, c1 = seg_rad(Q, a, k); t2, r2, d2, c2 = seg_rad(Q, k, e)
        use2 = (d[m] == bi[f'calf_{side}'])
        sl = np.where(use2, 0.5 + 0.5 * t2, 0.5 * t1); r0 = np.where(use2, r2, r1); dd = np.where(use2[:, None], d2, d1); c0 = np.where(use2[:, None], c2, c1)
        mt = (~use2) & (t1 > 0.3) & (t1 < 0.5)
        Rtop = np.median(r1[mt]) if mt.any() else np.median(r1)
        if shorts: tgt = Rtop * (1.08 + 0.10 * sl)
        else: tgt = Rtop * (1.0 - 0.36 * sl) * (0.92 + 0.08 * loose)
        nr = np.where(sl > 0.12, np.maximum(r0 + 0.005 * S, tgt), r0 + 0.005 * S)
        P[m] = c0 + dd * nr[:, None]
    other = ~np.isin(d, [bi[x] for x in ('thigh_l', 'thigh_r', 'calf_l', 'calf_r')])
    Nn = normals(P, tri(fs)); P[other] += Nn[other] * 0.004 * S
    P = smooth(P, fs, 3, 0.5, keep=bnd)
    pa = Part(P, fs, Wp, REG['bottom'], 'pants'); pa.attr['loose'] = np.zeros(len(P))
    parts.append(pa)
    # ---- skirt / kurta bottom ----
    if skirt:
        ids, fs = sub(V, W, groups['helper-skirt'])
        P = V[ids].copy(); keepm = P[:, 1] > hipy - 0.55 * (hipy - 0.08)
        ids2 = ids[keepm]; mm = {g: l for l, g in enumerate(ids2)}
        fs = [[mm[ids[i]] for i in f_] for f_ in fs if all(keepm[i] for i in f_)]
        P = V[ids2].copy(); sk = Part(P, fs, W[ids2], REG['top'], 'skirt'); sk.attr['loose'] = np.clip((hipy - P[:, 1]) / (hipy * 0.5), 0, 1)
        parts.append(sk)
    # ---- body (skin), hidden where covered ----
    cov = shirt_mask(0.07) | pants_mask(0.07)
    feet = B('foot_l', 'foot_r', 'ball_l', 'ball_r')
    ank = head[bi['foot_l']][1]
    covf = feet & (y < ank + 0.012)
    bf = [f_ for f_ in groups['body'] if not all(cov[i] for i in f_) and not all(covf[i] for i in f_)]
    ids, fs = sub(V, W, bf)
    bd = Part(V[ids], fs, W[ids], REG['skin'], 'body')
    bd.round_head = True
    sock = (dom[ids][:, None] == np.array([bi['calf_l'], bi['calf_r'], bi['foot_l'], bi['foot_r']])).any(1) & (V[ids][:, 1] < ank + 0.075 * S)
    for k, f_ in enumerate(fs):
        if all(sock[i] for i in f_): bd.freg[k] = REG['sock']
    # expression attributes on the skin
    def exp_attr(tn, sc=1.0):
        a = np.zeros((nv, 3))
        for t_ in tn:
            i, dl = target(t_); a[i] += dl * 0.1 * sc
        return a
    blink = exp_attr(['expression/units/caucasian/eye-left-closure', 'expression/units/caucasian/eye-right-closure'])
    smile = exp_attr(['expression/units/caucasian/mouth-corner-puller'], 0.9)
    i_, dl = target('expression/units/caucasian/mouth-eversion'); lipw = np.zeros(nv); mg = np.linalg.norm(dl, axis=1); lipw[i_] = np.clip(mg / mg.max() * 2.2 - 0.25, 0, 1)
    bd.attr['blink'] = blink[ids]; bd.attr['smile'] = smile[ids]; bd.attr['lip'] = lipw[ids]
    parts.append(bd)
    # ---- eyes, lashes ----
    for side, g in (('l', 'helper-l-eye'), ('r', 'helper-r-eye')):
        ids, fs = sub(V, W, groups[g]); P = V[ids]; c = P.mean(0)
        dn = (P - c); dn /= np.linalg.norm(dn, axis=1, keepdims=True)
        ey = Part(P, fs, W[ids], REG['eye'], 'eye'); ey.attr['eye'] = dn[:, 2] * 0.999 + 0.0; ey.attr['blink'] = blink[ids] * 0
        parts.append(ey)
    for g in groups:
        if 'eyelashes' in g:
            ids, fs = sub(V, W, groups[g]); la = Part(V[ids], fs, W[ids], REG['dark'], 'lash'); la.attr['blink'] = blink[ids]; parts.append(la)
    # ---- brows: ribbons hugging the skin ----
    bodyids = np.array(sorted({i for f_ in groups['body'] for i in f_})); BN = normals(V, tri(groups['body']))
    for side in ('left', 'right'):
        i_, dl = target(f'expression/units/caucasian/eyebrows-{side}-up'); mg = np.linalg.norm(dl, axis=1)
        sel = i_[mg > 0.55 * mg.max()]; sel = sel[np.isin(sel, bodyids)]; Q = V[sel]
        x0, x1 = np.percentile(Q[:, 0], [4, 96]); cy = np.polyfit(Q[:, 0], Q[:, 1], 2)
        A = np.c_[np.ones(len(Q)), Q[:, 0], Q[:, 1], Q[:, 0] ** 2, Q[:, 0] * Q[:, 1], Q[:, 1] ** 2]; cz = np.linalg.lstsq(A, Q[:, 2], rcond=None)[0]
        n = 12; xs = np.linspace(x0, x1, n); inner = xs[np.argmin(np.abs(xs))]
        pts = [];
        for x in xs:
            u = abs(x - inner) / max(abs(x1 - x0), 1e-6); hw = (0.0042 - 0.0026 * u ** 1.5) * S ** 0.5
            yc = np.polyval(cy, x) - 0.0015 * S + 0.002 * S * (u - 0.5)
            for yy in (yc + hw, yc - hw):
                zz = cz @ [1, x, yy, x * x, x * yy, yy * yy]; pts.append([x, yy, zz + 0.0016])
        pts = np.array(pts); fsb = [[2 * k, 2 * k + 1, 2 * k + 3, 2 * k + 2] if side == 'left' else [2 * k, 2 * k + 2, 2 * k + 3, 2 * k + 1] for k in range(n - 1)]
        if normals(pts, tri(fsb))[:, 2].mean() < 0: fsb = [f_[::-1] for f_ in fsb]
        wb = np.zeros((len(pts), nb), np.float32); wb[:, bi['head']] = 1
        br = Part(pts, fsb, wb, REG['dark'], 'brow'); parts.append(br)
    # ---- hair: a shell grown from the scalp, cut along a designed hairline ----
    eyec = (jpos('joint-l-eye') + jpos('joint-r-eye')) / 2; c0 = np.array([0, eyec[1] + 0.022 * S, eyec[2] - 0.088 * S])
    hm = np.isin(np.arange(nv), bodyids) & B('head', 'neck_01')
    dd = V - c0; rl = np.linalg.norm(dd, axis=1) + 1e-9; az = np.degrees(np.arctan2(np.abs(dd[:, 0]), dd[:, 2])); el = np.degrees(np.arcsin(np.clip(dd[:, 1] / rl, -1, 1)))
    emin = np.interp(az, [0, 30, 62, 80, 108, 135, 160, 180], [46, 43, 30, 22, 20, -12, -30, -33] if hair != 'bob' else [44, 42, 30, 22, 20, -14, -30, -33])
    scalp = hm & (el > emin) & (rl > 0.06 * S)
    hf = [f_ for f_ in groups['body'] if all(scalp[i] for i in f_)]
    ids, fs = sub(V, W, hf); P = V[ids].copy(); Wh = W[ids].copy(); Nh = BN[ids]; bnd = boundary(fs)
    tt = np.clip((el[ids] - emin[ids]) / 26.0, 0, 1); azl = az[ids]; x_ = P[:, 0] / S; fr = np.clip(1 - azl / 70.0, 0, 1)
    if hair == 'short':
        off = (0.0035 + 0.017 * tt ** 0.6 + 0.010 * tt * fr) * S
        P += Nh * off[:, None]
        lock = np.zeros(len(P))
        for sx, wd, ln in ((-0.052, 0.022, 0.6), (-0.024, 0.026, 1.0), (0.008, 0.028, 0.85), (0.040, 0.024, 0.95), (0.066, 0.02, 0.5)):
            lock = np.maximum(lock, np.clip(1 - np.abs(x_ - sx) / wd, 0, 1) ** 0.8 * ln)
        k = fr * (1 - tt) ** 1.5 * lock
        P[:, 1] -= k * 0.034 * S; P[:, 2] += k * 0.012 * S; P[:, 0] += k * 0.010 * S
        P[:, 0] += 0.004 * S * tt * fr  # slight side sweep
    elif hair == 'bob':
        off = (0.004 + 0.020 * tt ** 0.6) * S; P += Nh * off[:, None]
        low = (1 - tt) ** 1.3 * np.clip((azl - 62) / 40.0, 0, 1)
        P[:, 1] -= low * 0.105 * S; P[:, 0] *= 1 + 0.16 * low; P[:, 2] -= low * 0.012 * S * np.clip((azl - 120) / 40, 0, 1)
        k = fr * (1 - tt) ** 1.4; P[:, 1] -= k * 0.030 * S; P[:, 2] += k * 0.012 * S
    elif hair == 'bun':
        off = (0.003 + 0.012 * tt ** 0.6) * S; P += Nh * off[:, None]
    P = smooth(P, fs, 2, 0.4, keep=bnd)
    hp = Part(P, fs, Wh, REG['hair'], 'hair'); parts.append(hp)
    if hair == 'bun':
        u_, v_ = np.meshgrid(np.linspace(0, np.pi, 9), np.linspace(0, 2 * np.pi, 13)[:-1], indexing='ij')
        ctr = np.array([0, c0[1] + 0.035 * S, c0[2] - 0.115 * S]); sp = np.stack([np.sin(u_) * np.cos(v_), np.cos(u_), np.sin(u_) * np.sin(v_)], -1).reshape(-1, 3) * 0.048 * S + ctr
        fq = [[i * 12 + j, i * 12 + (j + 1) % 12, (i + 1) * 12 + (j + 1) % 12, (i + 1) * 12 + j] for i in range(8) for j in range(12)]
        if ((sp[tri(fq)].mean(1) - ctr) * np.cross(sp[tri(fq)[:, 1]] - sp[tri(fq)[:, 0]], sp[tri(fq)[:, 2]] - sp[tri(fq)[:, 0]])).sum() < 0: fq = [f_[::-1] for f_ in fq]
        wb = np.zeros((len(sp), nb), np.float32); wb[:, bi['head']] = 1; parts.append(Part(sp, fq, wb, REG['hair'], 'bun'))
    # ---- shoes: lofted sneakers ----
    sole_h = 0.014 * S
    for side in 'lr':
        fb, bb = bi[f'foot_{side}'], bi[f'ball_{side}']
        m = np.isin(dom, [fb, bb]) & np.isin(np.arange(nv), bodyids) & (y < ank + 0.02)
        Q = V[m]; fw = tail[bb] - head[fb]; fw[1] = 0; fw /= np.linalg.norm(fw); sd = np.cross([0, 1, 0], fw)
        s = Q @ fw; xq = Q @ sd; s0, s1 = s.min() - 0.006 * S, s.max() + 0.008 * S
        K = 18; M = 14; ring = []; ss = np.linspace(s0, s1, K)
        xa = []; xb = []; yt = []
        for sk in ss:
            mk = np.abs(s - sk) < (s1 - s0) / K * 1.3
            if mk.sum() < 3: mk = np.abs(s - sk) < (s1 - s0) / K * 3
            xa.append(xq[mk].min()); xb.append(xq[mk].max()); yt.append(Q[mk][:, 1].max())
        sm = lambda a: np.convolve(np.r_[a[0], a, a[-1]], [0.25, 0.5, 0.25], 'valid')
        xa, xb, yt = sm(np.array(xa)), sm(np.array(xb)), sm(np.array(yt))
        u = (ss - s0) / (s1 - s0); capf = np.clip(np.minimum(u / 0.08, (1 - u) / 0.10), 0.0, 1) ** 0.5 * 0.85 + 0.15
        pts = []
        for k in range(K):
            cx = (xa[k] + xb[k]) / 2; a = ((xb[k] - xa[k]) / 2 + 0.006 * S) * capf[k]
            ytop = min(yt[k], ank + 0.035 * S) + 0.007 * S; yb = -sole_h; b = (ytop - yb) / 2 * (0.6 + 0.4 * capf[k]); cyy = yb + b
            for j in range(M):
                ph = 2 * np.pi * j / M; c_, s_ = np.cos(ph), np.sin(ph)
                px = cx + a * np.sign(c_) * abs(c_) ** 0.55; py = cyy + b * np.sign(s_) * abs(s_) ** (0.45 if s_ < 0 else 0.75)
                pts.append(fw * ss[k] + sd * px + np.array([0, py, 0]))
        pts = np.array(pts); ci0 = len(pts); ci1 = ci0 + 1
        pts = np.vstack([pts, pts[:M].mean(0), pts[-M:].mean(0)])
        fsS = []; fr = []
        for k in range(K - 1):
            for j in range(M):
                a_, b_ = k * M + j, k * M + (j + 1) % M; fsS.append([a_, b_, b_ + M, a_ + M])
        for j in range(M): fsS.append([ci0, (j + 1) % M, j]); fsS.append([ci1, (K - 1) * M + j, (K - 1) * M + (j + 1) % M])
        T_ = tri(fsS); ctr = pts.mean(0); nn = np.cross(pts[T_[:, 1]] - pts[T_[:, 0]], pts[T_[:, 2]] - pts[T_[:, 0]])
        if ((pts[T_].mean(1) - ctr) * nn).sum() < 0: fsS = [f_[::-1] for f_ in fsS]
        kd = cKDTree(Q); _, nn_i = kd.query(pts); Ws = W[np.where(m)[0][nn_i]]
        for f_ in fsS:
            c = pts[f_].mean(0); uu = (c @ fw - s0) / (s1 - s0)
            fr.append(REG['sole'] if (c[1] < -sole_h + 0.016 * S or uu > 0.86) else REG['shoe'])
        parts.append(Part(pts, fsS, Ws, fr, 'shoe'))
    # ---- assemble ----
    lift = sole_h
    Vs = []; Ns = []; Ws_ = []; Rg = []; Ts = []; A = {k: [] for k in ('loose', 'blink', 'smile', 'lip', 'eye')}; base_i = 0
    for p in parts:
        T_ = tri(p.faces);
        if len(T_) == 0: continue
        Np = normals(p.V, T_)
        if getattr(p, 'round_head', False):  # one clean shadow shape on the face: normals leaned towards a ball
            hw_ = p.W[:, bi['head']][:, None]; sphn = p.V - c0; sphn /= np.linalg.norm(sphn, axis=1, keepdims=True) + 1e-9
            Np = Np * (1 - 0.6 * hw_) + sphn * 0.6 * hw_; Np /= np.linalg.norm(Np, axis=1, keepdims=True) + 1e-9
        fr = np.array([r_ for f_, r_ in zip(p.faces, p.freg) for _ in range(len(f_) - 2)])
        for rg in sorted(set(fr)):
            Tr = T_[fr == rg]; u, inv = np.unique(Tr, return_inverse=True)
            Vs.append(p.V[u]); Ns.append(Np[u]); Ws_.append(p.W[u]); Rg.append(np.full(len(u), rg)); Ts.append(inv.reshape(-1, 3) + base_i)
            for k in A:
                a = p.attr.get(k)
                A[k].append(a[u] if a is not None else np.zeros((len(u), 3) if k in ('blink', 'smile') else len(u)) + (-2.0 if k == 'eye' else 0))
            base_i += len(u)
    Vc = np.vstack(Vs) * scale; Vc[:, 1] += lift * scale; Nc = np.vstack(Ns); Wc = np.vstack(Ws_); Rc = np.concatenate(Rg); Tc = np.vstack(Ts)
    top4 = np.argsort(-Wc, 1)[:, :4]; w4 = np.take_along_axis(Wc, top4, 1); w4 /= w4.sum(1, keepdims=True)
    head_s = head * scale; head_s[:, 1] += lift * scale; tail_s = tail * scale; tail_s[:, 1] += lift * scale
    os.makedirs(OUT, exist_ok=True)
    blobs = [('pos', Vc.astype('<f4')), ('nor', Nc.astype('<f4')), ('si', top4.astype('<u2')), ('sw', w4.astype('<f4')), ('reg', Rc.astype('<f4')),
             ('loose', np.concatenate(A['loose']).astype('<f4')), ('blink', (np.vstack(A['blink']) * scale).astype('<f4')), ('smile', (np.vstack(A['smile']) * scale).astype('<f4')),
             ('lip', np.concatenate(A['lip']).astype('<f4')), ('eye', np.concatenate(A['eye']).astype('<f4')), ('idx', Tc.astype('<u4'))]
    meta = dict(name=name, bones=bnames, parent=par.tolist(), head=head_s.round(5).tolist(), tail=tail_s.round(5).tolist(), height=float(H * scale + lift * scale), S=float(S * scale), blobs={})
    off = 0; data = b''
    for k, a in blobs:
        b = a.tobytes(); meta['blobs'][k] = [off, len(b), list(a.shape)]; data += b; off += len(b)
        if off % 4: pad = 4 - off % 4; data += b'\0' * pad; off += pad
    open(OUT + name + '.bin', 'wb').write(data); json.dump(meta, open(OUT + name + '.json', 'w'))
    print(f'{name}: {len(Vc)} verts {len(Tc)} tris  height {meta["height"]:.3f} m  hip {head_s[bi["thigh_l"]][1]:.3f}  left x {head_s[bi["upperarm_l"]][0]:+.3f}')
    return meta

CAST = dict(
    hero10=dict(child=1.0, sleeve=0.5, shorts=True, weight=-0.2),
    hero16=dict(child=0.22, weight=-0.45, sleeve=0.5, scale=0.955),
    frB16x=dict(gender='female', child=0.2, sleeve=0.5, hair='bob', weight=-0.2),
    hero23=dict(child=0.0, weight=-0.2, muscle=0.15, sleeve=1.62, top_len=0.02),
    frA10=dict(child=1.0, sleeve=0.5, shorts=True, weight=0.3, scale=1.03),
    frA16=dict(child=0.2, weight=0.2, sleeve=0.5, scale=0.97),
    frA23=dict(child=0.0, weight=0.3, sleeve=1.62),
    frB10=dict(gender='female', child=1.0, sleeve=0.5, hair='bob', weight=-0.2, scale=1.07),
    frB16=dict(gender='female', child=0.2, sleeve=0.5, hair='bob', weight=-0.2),
    frB23=dict(gender='female', child=0.0, sleeve=1.62, hair='bob', weight=-0.1, top_len=0.10),
    manA=dict(child=0.0, weight=0.5, sleeve=0.5), manB=dict(child=0.0, weight=-0.5, sleeve=1.62, scale=1.04),
    womA=dict(gender='female', child=0.0, sleeve=1.2, hair='bun', top_len=0.16), teenC=dict(child=0.3, weight=-0.3, sleeve=0.5, scale=0.95),
)
if __name__ == '__main__':
    import sys
    for n in (sys.argv[1:] or CAST): build(n, **CAST[n])
