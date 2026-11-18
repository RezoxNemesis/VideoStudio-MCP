"""Captured motion (CMU BVH) -> retargeted onto our characters, looped, stitched, feet locked. 60 Hz inside, 30 Hz out."""
import json, os, numpy as np
from scipy.spatial.transform import Rotation as R, Slerp
from cinepaths import BVH, CHARS, FILMDATA as DATA
HALF = {75, 77, 88, 106, 139, 141, 143}
FPS = 60.0
MAP = dict(pelvis='Hips', spine_01='LowerBack', spine_02='Spine', spine_03='Spine1', neck_01='Neck1', head='Head',
           clavicle_l='LeftShoulder', upperarm_l='LeftArm', lowerarm_l='LeftForeArm', hand_l='LeftHand',
           clavicle_r='RightShoulder', upperarm_r='RightArm', lowerarm_r='RightForeArm', hand_r='RightHand',
           thigh_l='LeftUpLeg', calf_l='LeftLeg', foot_l='LeftFoot', ball_l='LeftToeBase',
           thigh_r='RightUpLeg', calf_r='RightLeg', foot_r='RightFoot', ball_r='RightToeBase')
_bvh = {}

def load_bvh(name):
    if name in _bvh: return _bvh[name]
    tok = open(BVH + name + '.bvh').read().split()
    names = []; parent = []; off = []; chans = []; end = {}; stack = []; i = 0; nch = 0
    while tok[i] != 'MOTION':
        t = tok[i]
        if t in ('ROOT', 'JOINT'):
            names.append(tok[i + 1]); parent.append(stack[-1] if stack else -1); stack.append(len(names) - 1); i += 2
        elif t == 'End': stack.append(-2 - (stack[-1])); i += 2
        elif t == 'OFFSET':
            v = [float(x) for x in tok[i + 1:i + 4]]
            if stack[-1] < -1: end[-2 - stack[-1]] = v
            else: off.append(v)
            i += 4
        elif t == 'CHANNELS':
            n = int(tok[i + 1]); chans.append(tok[i + 2:i + 2 + n]); nch += n; i += 2 + n
        elif t == '}': stack.pop(); i += 1
        else: i += 1
    nf = int(tok[i + 2]); dt = float(tok[i + 5]); data = np.array(tok[i + 6:i + 6 + nf * nch], float).reshape(nf, nch)
    subj = int(name.split('_')[0])
    if subj in HALF: dt *= 2
    step = max(1, int(round((1 / FPS) / dt))); data = data[2 * step::step]; dt *= step
    J = len(names); off = np.array(off); F = len(data)
    Rg = [None] * J; pos = np.zeros((F, J, 3)); c = 0
    for j in range(J):
        ch = chans[j]; tr = None
        if len(ch) == 6: tr = data[:, c:c + 3]; rc = ch[3:]; ang = data[:, c + 3:c + 6]
        else: rc = ch; ang = data[:, c:c + 3]
        c += len(ch)
        rl = R.from_euler(''.join(x[0] for x in rc), ang, degrees=True)
        if parent[j] < 0: Rg[j] = rl; pos[:, j] = tr
        else: Rg[j] = Rg[parent[j]] * rl; pos[:, j] = pos[:, parent[j]] + Rg[parent[j]].apply(off[j])
    child = {}
    for j in range(J):
        if parent[j] >= 0: child.setdefault(parent[j], []).append(j)
    _bvh[name] = dict(names=names, parent=parent, off=off, end=end, Rg=Rg, pos=pos, dt=dt, child=child, F=F)
    return _bvh[name]

def frame_align(d_t, n_t, d_s, n_s):
    def fr(d, n):
        d = d / np.linalg.norm(d); n = n - d * (n @ d); n /= np.linalg.norm(n); return np.c_[d, n, np.cross(d, n)]
    return R.from_matrix(fr(d_s, n_s) @ fr(d_t, n_t).T)

class Char:
    def __init__(s, name):
        m = json.load(open(CHARS + name + '.json')); s.name = name; s.bones = m['bones']; s.par = m['parent']
        s.head = np.array(m['head']); s.tail = np.array(m['tail']); s.bi = {b: i for i, b in enumerate(s.bones)}; s.nb = len(s.bones)
        s.S = m['S']; s.height = m['height']
        h = lambda b: s.head[s.bi[b]]
        s.leg = np.linalg.norm(h('calf_l') - h('thigh_l')) + np.linalg.norm(h('foot_l') - h('calf_l'))
        s.palm = {}
        for sd in 'lr':
            w = h(f'hand_{sd}'); n = np.cross(h(f'index_01_{sd}') - w, h(f'pinky_01_{sd}') - w); n /= np.linalg.norm(n)
            tip = s.tail[s.bi[f'middle_03_{sd}']] - h(f'middle_01_{sd}'); base = h(f'middle_01_{sd}') - w
            if n @ (tip - base * (tip @ base) / (base @ base)) < 0: n = -n
            s.palm[sd] = n

def retarget(clip, ch, look=(8.0, 5.0), lean=0.0):
    b = load_bvh(clip); ji = {n: i for i, n in enumerate(b['names'])}; F = b['F']
    G = [None] * ch.nb; ident = R.from_quat(np.tile([0, 0, 0, 1.0], (F, 1)))
    def sdir(j):
        while True:
            k = b['child'].get(j)
            v = b['off'][k[0]] if k else np.array(b['end'].get(j, [0, 0, 0]))
            if np.linalg.norm(v) > 1e-6: return v
            if not k: return np.array([1.0 if 'Left' in b['names'][j] else -1.0, 0, 0])
            j = k[0]
    h = ch.head; bi = ch.bi
    for bn in ch.bones:
        i = bi[bn]
        if bn not in MAP: G[i] = G[ch.par[i]] if ch.par[i] >= 0 else ident; continue
        j = ji[MAP[bn]]; g = b['Rg'][j]; C = None; sd = bn[-1]
        if bn.startswith(('upperarm', 'lowerarm', 'hand')):
            if bn.startswith('upperarm'):
                dt = h[bi[f'lowerarm_{sd}']] - h[i]; nt = h[bi[f'hand_{sd}']] - h[bi[f'lowerarm_{sd}']]; ns = np.array([0, 0, 1.0])
            elif bn.startswith('lowerarm'):
                dt = h[bi[f'hand_{sd}']] - h[i]; nt = ch.palm[sd]; ns = np.array([0, -1.0, 0])
            else:
                dt = h[bi[f'middle_01_{sd}']] - h[i]; nt = ch.palm[sd]; ns = np.array([0, -1.0, 0])
            C = frame_align(dt, nt, sdir(j), ns)
        elif bn.startswith(('thigh', 'calf')):
            dt = h[bi[f'calf_{sd}']] - h[i] if bn.startswith('thigh') else h[bi[f'foot_{sd}']] - h[i]
            C = frame_align(dt, np.array([0, 0, -1.0]), sdir(j), np.array([0, 0, -1.0]))
        G[i] = g * C if C is not None else g
    if lean:  # straighten (or bend) the whole upper body about the hips' side axis
        ax = G[bi['pelvis']].apply([1.0, 0, 0]); rw = R.from_rotvec(ax * np.radians(lean))
        def under(i):
            while i >= 0:
                if ch.bones[i] == 'spine_01': return True
                i = ch.par[i]
            return False
        for i in range(ch.nb):
            if under(i) and ch.bones[i] in MAP: G[i] = rw * G[i]
        for i in range(ch.nb):
            if ch.bones[i] not in MAP and ch.par[i] >= 0: G[i] = G[ch.par[i]]
    if look is not None:  # captured skeletons differ in how the head sits: bring the usual gaze level
        for bn, want in (('neck_01', look[0]), ('head', look[1])):
            i = bi[bn]; f = G[i].apply([0, 0, 1.0]); pit = np.degrees(np.arcsin(np.clip(-f[:, 1], -1, 1)))
            G[i] = G[i] * R.from_euler('x', -np.radians(np.median(pit) - want))
    src_leg = np.linalg.norm(b['off'][ji['LeftLeg']]) + np.linalg.norm(b['off'][ji['LeftFoot']])
    sc = ch.leg / src_leg
    root = b['pos'][:, 0] * sc
    Q = np.stack([g.as_quat() for g in G], 1)  # F, nb, 4
    Q, root = ground(ch, Q, root)
    return Q, root

def fk(ch, Q, root):
    F = len(Q); P = np.zeros((F, ch.nb, 3))
    for i in range(ch.nb):
        p = ch.par[i]
        if p < 0 or ch.bones[i] == 'pelvis': P[:, i] = root if ch.bones[i] == 'pelvis' else 0
        else: P[:, i] = P[:, p] + R.from_quat(Q[:, p]).apply(ch.head[i] - ch.head[p])
    return P

def foot_pts(ch, Q, P, sd):
    """ankle, ball, toe tip, heel world points"""
    f = ch.bi[f'foot_{sd}']; bl = ch.bi[f'ball_{sd}']
    toe = P[:, bl] + R.from_quat(Q[:, bl]).apply(ch.tail[bl] - ch.head[bl])
    heel_off = np.array([0, -(ch.head[f][1]) + 0.0, -0.05 * ch.S / 1.0]) + [0, 0.0, 0]
    heel = P[:, f] + R.from_quat(Q[:, f]).apply(heel_off)
    return P[:, f], P[:, bl], toe, heel

def ground(ch, Q, root):
    P = fk(ch, Q, root); lo = []
    for sd in 'lr':
        a, bl, toe, heel = foot_pts(ch, Q, P, sd); lo.append(np.minimum(bl[:, 1] - ch.head[ch.bi[f'ball_{sd}']][1], heel[:, 1]))
    lo = np.minimum(lo[0], lo[1]); root = root.copy(); root[:, 1] -= np.percentile(lo, 6)
    return Q, root

def smooth1(x, k):
    if k <= 1: return x
    w = np.hanning(k + 2)[1:-1]; w /= w.sum(); pad = k // 2
    xp = np.concatenate([np.repeat(x[:1], pad, 0), x, np.repeat(x[-1:], pad, 0)])
    return np.stack([np.convolve(xp[:, i], w, 'valid') for i in range(x.shape[1])], 1)[:len(x)] if x.ndim == 2 else np.convolve(xp, w, 'valid')[:len(x)]

def contacts(ch, Q, root, vthr=0.75, hthr=0.035):
    """per foot: support state per frame (0 none, 1 heel, 2 ball) with hysteresis"""
    P = fk(ch, Q, root); out = {}; F = len(Q)
    for sd in 'lr':
        a, bl, toe, heel = foot_pts(ch, Q, P, sd)
        gb = ch.head[ch.bi[f'ball_{sd}']][1]
        hb = bl[:, 1] - gb; hh = heel[:, 1]; ht = toe[:, 1] - ch.tail[ch.bi[f'ball_{sd}']][1]
        sp = lambda p: smooth1(np.linalg.norm(np.gradient(p[:, [0, 2]], axis=0), axis=1) * FPS, 3)
        vb, vh = sp(bl), sp(heel); st = np.zeros(F, int); cur = 0
        vt = vthr * max(ch.S, 0.6) ** 0.5; htr = hthr * ch.S
        for t in range(F):
            k = 1.7 if cur else 1.0
            cb = hb[t] < htr * (k if cur == 2 else 1) and vb[t] < vt * (k if cur == 2 else 1)
            chh = hh[t] < htr * (k if cur == 1 else 1) and vh[t] < vt * (k if cur == 1 else 1)
            if cur == 1 and chh and not (cb and hh[t] > hb[t] + 0.01): pass
            elif cb and (not chh or hb[t] <= hh[t] + 0.004 or cur == 2): cur = 2
            elif chh: cur = 1
            else: cur = 0
            st[t] = cur
        for t in range(1, F - 1):  # drop single-frame flickers
            if st[t] == 0 and st[t - 1] and st[t + 1]: st[t] = st[t - 1]
            if st[t] and not st[t - 1] and not st[t + 1]: st[t] = 0
        out[sd] = dict(st=st, c=st > 0, ball=bl, ankle=a, toe=toe, heel=heel, low=np.minimum(np.minimum(hb, hh), ht))
    return out, P

def rot_between(a, b):
    a = a / np.linalg.norm(a, axis=-1, keepdims=True); b = b / np.linalg.norm(b, axis=-1, keepdims=True)
    ax = np.cross(a, b); s = np.linalg.norm(ax, axis=-1, keepdims=True); c = (a * b).sum(-1, keepdims=True)
    ang = np.arctan2(s, c); ax = ax / np.maximum(s, 1e-9)
    return R.from_rotvec(ax * ang)

def footlock(ch, Q, root, report=None, tau=0.07):
    """a planted heel or ball stays exactly where it landed; the leg is re-solved; feet never go below ground"""
    Q = Q.copy(); con, P = contacts(ch, Q, root); F = len(Q); stats = {}
    for sd in 'lr':
        st = con[sd]['st']; ball = con[sd]['ball']; heel = con[sd]['heel']; ankle = con[sd]['ankle']
        th, cf, ft = ch.bi[f'thigh_{sd}'], ch.bi[f'calf_{sd}'], ch.bi[f'foot_{sd}']
        dl = np.zeros((F, 3)); d = np.zeros(3); pin = None; prev = 0; dec = np.exp(-1 / FPS / tau); mx = 0
        for t in range(F):
            s_ = st[t]
            if s_ == 0: d = d * dec
            else:
                pt = heel[t] if s_ == 1 else ball[t]
                if s_ != prev or pin is None: pin = pt + d
                d = pin - pt; d[1] = 0
                n = np.linalg.norm(d)
                if n > 0.15: d *= 0.15 / n; pin = pt + d
                mx = max(mx, n)
            prev = s_; dl[t] = d
        lift = smooth1(np.maximum(0, -con[sd]['low']), 3)
        newank = ankle + dl; newank[:, 1] += lift
        hip = P[:, th]; l1 = np.linalg.norm(ch.head[cf] - ch.head[th]); l2 = np.linalg.norm(ch.head[ft] - ch.head[cf])
        knee0 = P[:, cf]
        v = newank - hip; dist = np.linalg.norm(v, axis=1); dmax = (l1 + l2) * 0.9995
        over = dist > dmax; v = np.where(over[:, None], v / dist[:, None] * dmax, v); dist = np.minimum(dist, dmax)
        vn = v / dist[:, None]
        pole = knee0 - hip; pole = pole - vn * (pole * vn).sum(1, keepdims=True); pn = np.linalg.norm(pole, axis=1, keepdims=True)
        fwd = R.from_quat(Q[:, ch.bi['pelvis']]).apply([0, 0, 1.0]); fwd = fwd - vn * (fwd * vn).sum(1, keepdims=True); fwd /= np.linalg.norm(fwd, axis=1, keepdims=True)
        pole = np.where(pn > 0.02, pole / np.maximum(pn, 1e-9), fwd)
        ca = np.clip((l1 ** 2 + dist ** 2 - l2 ** 2) / (2 * l1 * dist), -1, 1); sa = np.sqrt(1 - ca ** 2)
        knee = hip + (vn * ca[:, None] + pole * sa[:, None]) * l1
        r1 = rot_between(knee0 - hip, knee - hip); Q[:, th] = (r1 * R.from_quat(Q[:, th])).as_quat()
        r2 = rot_between(ankle - knee0, hip + v - knee); Q[:, cf] = (r2 * R.from_quat(Q[:, cf])).as_quat()
        stats[sd] = (round(float(mx) * 100, 1), int((over & (st > 0)).sum()))
    if report is not None: report.update(stats)
    return Q

def leg_ik(ch, Q, P, sd, newank):
    th, cf, ft = ch.bi[f'thigh_{sd}'], ch.bi[f'calf_{sd}'], ch.bi[f'foot_{sd}']
    hip = P[:, th]; l1 = np.linalg.norm(ch.head[cf] - ch.head[th]); l2 = np.linalg.norm(ch.head[ft] - ch.head[cf]); knee0 = P[:, cf]; ankle = P[:, ft]
    v = newank - hip; dist = np.linalg.norm(v, axis=1); dmax = (l1 + l2) * 0.9995
    v = np.where((dist > dmax)[:, None], v / dist[:, None] * dmax, v); dist = np.minimum(dist, dmax); vn = v / dist[:, None]
    pole = knee0 - hip; pole = pole - vn * (pole * vn).sum(1, keepdims=True); pole /= np.maximum(np.linalg.norm(pole, axis=1, keepdims=True), 1e-9)
    ca = np.clip((l1 ** 2 + dist ** 2 - l2 ** 2) / (2 * l1 * dist), -1, 1); sa = np.sqrt(1 - ca ** 2)
    knee = hip + (vn * ca[:, None] + pole * sa[:, None]) * l1
    Q[:, th] = (rot_between(knee0 - hip, knee - hip) * R.from_quat(Q[:, th])).as_quat()
    Q[:, cf] = (rot_between(ankle - knee0, hip + v - knee) * R.from_quat(Q[:, cf])).as_quat()

def seatfeet(ch, Q, root):
    """sitting: both feet flat on the ground (the captured stool had a foot step)"""
    w = np.clip((0.68 - root[:, 1] / ch.leg) / 0.08, 0, 1); w = smooth1(w * w * (3 - 2 * w), 21)
    if w.max() < 0.01: return Q
    Q = Q.copy(); P = fk(ch, Q, root)
    for sd in 'lr':
        ft, bl = ch.bi[f'foot_{sd}'], ch.bi[f'ball_{sd}']; ank = P[:, ft].copy(); tgt = ank.copy(); tgt[:, 1] = ch.head[ft][1] + 0.004
        hold = smooth1(tgt[:, [0, 2]], 61); tgt[:, 0] = hold[:, 0]; tgt[:, 2] = hold[:, 1]
        newank = ank * (1 - w)[:, None] + tgt * w[:, None]; leg_ik(ch, Q, P, sd, newank)
        for b in (ft, bl):
            f = R.from_quat(Q[:, b]).apply([0, 0, 1.0]); yy = np.arctan2(f[:, 0], f[:, 2]); flat = R.from_rotvec(np.c_[np.zeros(len(yy)), yy, np.zeros(len(yy))]).as_quat()
            Q[:, b] = qblend(Q[:, [b]], flat[:, None], w)[:, 0]
    return Q

def heading(ch, Q):
    f = R.from_quat(Q[:, ch.bi['pelvis']]).apply([0, 0, 1.0]); return np.arctan2(f[:, 0], f[:, 2])

def yaw(Q, root, ang, pivot=None):
    r = R.from_euler('y', ang); Q2 = (r * R.from_quat(Q.reshape(-1, 4))).as_quat().reshape(Q.shape)
    pv = np.zeros(3) if pivot is None else pivot
    return Q2, r.apply(root - pv) + pv

def qblend(Qa, Qb, w):
    """per-frame weight w (F,) blend of quaternion arrays (F, nb, 4)"""
    d = (Qa * Qb).sum(-1, keepdims=True); Qb = np.where(d < 0, -Qb, Qb)
    q = Qa * (1 - w)[:, None, None] + Qb * w[:, None, None]
    return q / np.linalg.norm(q, axis=-1, keepdims=True)

def cycle(clip, ch, tmin=0.5, tmax=1.5, lo=None, hi=None, nb=10, look=(8.0, 5.0), lean=0.0):
    """extract one stride (two steps) as a seamless loop travelling along +z. returns Q, residual root, step vector length, frames"""
    Q, root = retarget(clip, ch, look, lean); F = len(Q); P = fk(ch, Q, root)
    rel = (P - P[:, [ch.bi['pelvis']]])[:, [ch.bi[x] for x in ('foot_l', 'foot_r', 'hand_l', 'hand_r', 'calf_l', 'calf_r')]]
    hd = heading(ch, Q); c, s = np.cos(-hd), np.sin(-hd)
    rx = rel[..., 0] * c[:, None] + rel[..., 2] * s[:, None]; rz = -rel[..., 0] * s[:, None] + rel[..., 2] * c[:, None]
    feat = np.concatenate([rx, rel[..., 1], rz], 1); vel = np.gradient(feat, axis=0) * 6
    feat = np.concatenate([feat, vel], 1)
    lo = nb + 2 if lo is None else max(nb + 2, int(lo * FPS)); hi = F - 2 if hi is None else min(F - 2, int(hi * FPS))
    best = None
    for i in range(lo, hi):
        for j in range(i + int(tmin * FPS), min(hi, i + int(tmax * FPS))):
            d = np.linalg.norm(feat[i] - feat[j]) + 0.3 * abs(root[i, 1] - root[j, 1])
            if best is None or d < best[0]: best = (d, i, j)
    _, i, j = best; L = j - i
    d = root[j] - root[i]; ang = np.arctan2(d[0], d[2])
    Q, root = yaw(Q, root, -ang, root[i]); d = root[j] - root[i]
    k = np.arange(L); res = root[i:j] - root[i] - np.outer(k / L, [d[0], 0, d[2]]); res[:, 1] = root[i:j, 1]
    Qc = Q[i:j].copy()
    w = np.clip((k - (L - nb)) / nb, 0, 1); w = w * w * (3 - 2 * w)
    pre = np.clip(i + k - L, 0, F - 1)
    Qc = qblend(Qc, Q[pre], w)
    resp = root[pre] - root[i] - np.outer((k - L) / L, [d[0], 0, d[2]]); resp[:, 1] = root[pre, 1]
    res = res * (1 - w)[:, None] + resp * w[:, None]
    return dict(Q=Qc, res=res, step=float(d[2]), L=L, clip=clip, speed=float(d[2] / (L / FPS)))

def unroll(cy, n, t0=0):
    """n frames of a cycle starting at cycle phase frame t0"""
    k = (np.arange(n) + t0); ph = k % cy['L']
    root = cy['res'][ph].copy(); root[:, 2] += (k - t0) / cy['L'] * cy['step'] + 0
    root[:, 2] += 0
    return cy['Q'][ph].copy(), root

def resample(Q, root, speed):
    """play faster (speed>1) or slower"""
    if abs(speed - 1) < 1e-3: return Q, root
    F = len(Q); t = np.arange(0, F - 1, speed); i0 = np.floor(t).astype(int); fr = t - i0
    return qblend(Q[i0], Q[np.minimum(i0 + 1, F - 1)], fr), root[i0] * (1 - fr)[:, None] + root[np.minimum(i0 + 1, F - 1)] * fr[:, None]

def curve(Q, root, total):
    n = len(Q); th = np.linspace(0, total, n); d = np.diff(root, axis=0, prepend=root[:1]); c, s_ = np.cos(th), np.sin(th)
    out = root.copy(); out[:, 0] = root[0, 0] + np.cumsum(d[:, 0] * c + d[:, 2] * s_); out[:, 2] = root[0, 2] + np.cumsum(-d[:, 0] * s_ + d[:, 2] * c)
    r = R.from_rotvec(np.c_[np.zeros(n), th, np.zeros(n)]); Q2 = np.stack([(r * R.from_quat(Q[:, b])).as_quat() for b in range(Q.shape[1])], 1)
    return Q2, out

class Seq:
    """stitch pieces end to end with matched heading/position and crossfades"""
    def __init__(s, ch): s.ch = ch; s.Q = None; s.root = None; s.tailQ = None; s.tailroot = None; s.ph = {}
    @property
    def t(s): return 0 if s.Q is None else len(s.Q) / FPS
    def add(s, Q, root, blend=0.3, tail=None, face=None):
        """tail = (Q, root) frames that continue past the end, for the next crossfade"""
        ch = s.ch
        if s.Q is None:
            if getattr(s, 'norm', False):
                a0 = -heading(ch, Q[:1])[0]; pv = root[0].copy(); Q, root = yaw(Q, root, a0, pv)
                if tail is not None: tail = yaw(tail[0], tail[1], a0, pv)
            s.Q, s.root = Q.copy(), root.copy()
        else:
            nbf = int(blend * FPS)
            # align new piece to the current end
            h_e = heading(ch, s.Q[-1:])[0]; h_s = heading(ch, Q[:1])[0]
            if face is not None: h_e = face
            d = h_e - h_s; Q2, r2 = yaw(Q, root, d, root[0]); off = s.root[-1] - r2[0]; off[1] = 0; r2 = r2 + off
            if s.tailQ is not None and nbf > 0:
                n = min(nbf, len(s.tailQ), len(Q2)); w = np.linspace(0, 1, n + 2)[1:-1]; w = w * w * (3 - 2 * w)
                tq, tr = s.tailQ[:n], s.tailroot[:n]
                Q2[:n] = qblend(tq, Q2[:n], w); r2[:n] = tr * (1 - w)[:, None] + r2[:n] * w[:, None]
            s.Q = np.concatenate([s.Q, Q2]); s.root = np.concatenate([s.root, r2])
            if tail is not None:
                tQ, tr = yaw(tail[0], tail[1], d, root[0]); tail = (tQ, tr + off)
        if tail is not None: s.tailQ, s.tailroot = tail
        else: s.tailQ = s.tailroot = None
        return s
    def loop(s, cy, secs, speed=1.0, blend=0.35, phase=None, turn=0.0, face=None):
        ph = s.ph.get(cy['clip'], 0) if phase is None else phase
        n = int(secs * FPS * speed) + 1; ext = int(0.6 * FPS * speed) + 2
        Q, root = unroll(cy, n + ext, int(ph)); Q, root = resample(Q, root, speed)
        if turn: Q, root = curve(Q, root, turn * len(Q) / max(1, int(secs * FPS)))
        n2 = int(secs * FPS); s.ph[cy['clip']] = (ph + n2 * speed) % cy['L']
        return s.add(Q[:n2], root[:n2], blend, tail=(Q[n2:], root[n2:]), face=face)
    def clip(s, name, t0, t1, speed=1.0, blend=0.35, face=None, look=(8.0, 5.0), lean=0.0):
        if s.Q is None: s.norm = True  # a performance that begins with a one-off action starts facing straight ahead
        Q, root = retarget(name, s.ch, look, lean); a, b = int(t0 * FPS), int(t1 * FPS); b = min(b, len(Q) - 1)
        Qp, rp = resample(Q[a:], root[a:], speed); n = int((b - a) / speed)
        return s.add(Qp[:n], rp[:n], blend, tail=(Qp[n:n + 40], rp[n:n + 40]), face=face)
    def hold(s, secs):
        n = int(secs * FPS); return s.add(np.repeat(s.Q[-1:], n, 0), np.repeat(s.root[-1:], n, 0), 0)
    def done(s, lock=True, fist=0.5, fists=None, rep=None):
        Q = seatfeet(s.ch, s.Q, s.root); Q = footlock(s.ch, Q, s.root, report=rep) if lock else Q
        return Q, s.root

def gait_report(ch, Q, root, label=''):
    con, P = contacts(ch, Q, root); F = len(Q); T = F / FPS
    sp = np.linalg.norm(root[-1, [0, 2]] - root[0, [0, 2]]) / T
    steps = 0; slides = [0]
    for sd in 'lr':
        st = con[sd]['st']; c = st > 0; steps += int(((c[1:]) & (~c[:-1])).sum()); i = 0
        while i < F:
            if st[i]:
                j = i
                while j < F and st[j] == st[i]: j += 1
                seg = (con[sd]['heel'] if st[i] == 1 else con[sd]['ball'])[i:j, [0, 2]]; slides.append(np.linalg.norm(seg - seg[0], axis=1).max()); i = j
            else: i += 1
    air = (~con['l']['c'] & ~con['r']['c']).mean(); both = (con['l']['c'] & con['r']['c']).mean()
    up = R.from_quat(Q[:, ch.bi['spine_02']]).apply([0, 1.0, 0]); lean = np.degrees(np.arccos(np.clip(up[:, 1], -1, 1))).mean()
    wr = np.linalg.norm(P[:, ch.bi['hand_l']] - P[:, ch.bi['hand_r']], axis=1)
    hy = P[:, ch.bi['head'], 1]; low = min(con['l']['low'].min(), con['r']['low'].min())
    print(f'{label:16s} {T:5.2f}s {sp:4.2f} m/s  steps/min {steps / T * 60:4.0f}  air {air * 100:3.0f}% both {both * 100:3.0f}%  lean {lean:4.1f}  slide {100 * max(slides):4.1f} cm  wrists {wr.min():.2f}-{wr.max():.2f}  bob {100 * (hy.max() - hy.min()):4.1f} cm  lowest {100 * low:5.1f} cm')

def finger_quats(ch, curl):
    """curl: dict side -> 0..1 (0 open, 1 fist). returns {bone index: local quat}"""
    out = {}
    for sd in 'lr':
        c = curl.get(sd, 0.4); n = ch.palm[sd]
        for fn, k in (('index', 1.0), ('middle', 1.05), ('ring', 1.1), ('pinky', 1.15)):
            for seg, ang in ((1, 12 + 62 * c), (2, 16 + 78 * c), (3, 8 + 50 * c)):
                i = ch.bi[f'{fn}_0{seg}_{sd}']; d = ch.tail[i] - ch.head[i]; ax = np.cross(d, n); ax /= np.linalg.norm(ax)
                out[i] = R.from_rotvec(ax * np.radians(ang * k)).as_quat()
        for seg, ang in ((1, 6 + 22 * c), (2, 6 + 30 * c), (3, 8 + 40 * c)):
            i = ch.bi[f'thumb_0{seg}_{sd}']; d = ch.tail[i] - ch.head[i]
            tow = ch.head[ch.bi[f'index_01_{sd}']] + 0.35 * n * 0.05 - ch.head[i]; ax = np.cross(d, tow); ax /= np.linalg.norm(ax)
            out[i] = R.from_rotvec(ax * np.radians(ang)).as_quat()
    return out

def export(ch, name, Q, root, curl=None, start=None):
    """60 Hz -> 30 Hz local quaternions; root positions relative to `start` (default first frame, ground level)"""
    Q = Q[::2]; root = root[::2].copy(); F = len(Q)
    o = root[0].copy() if start is None else np.array(start, float); o[1] = 0; root -= o
    loc = np.zeros((F, ch.nb, 4)); fq = finger_quats(ch, curl or {})
    for i in range(ch.nb):
        p = ch.par[i]
        if i in fq: loc[:, i] = fq[i]
        elif p < 0 or ch.bones[i] == 'pelvis': loc[:, i] = Q[:, i] if ch.bones[i] == 'pelvis' else [0, 0, 0, 1]
        else: loc[:, i] = (R.from_quat(Q[:, p]).inv() * R.from_quat(Q[:, i])).as_quat()
    arr = np.concatenate([root, loc.reshape(F, -1)], 1).astype('<f4')
    os.makedirs(DATA + 'mo', exist_ok=True)
    open(DATA + f'mo/{name}.bin', 'wb').write(np.array([F, ch.nb], '<f4').tobytes() + arr.tobytes())
    return F

if __name__ == '__main__':
    import sys
    b = load_bvh('16_46'); ji = {n: i for i, n in enumerate(b['names'])}
    for n in ('LeftArm', 'LeftForeArm', 'LeftHand', 'LeftFingerBase', 'RightForeArm', 'LowerBack', 'Spine', 'Neck', 'Head'): print(n, b['off'][ji[n]].round(2))
    for cn in ('hero23', 'hero10'):
        ch = Char(cn); print(cn, 'leg', round(ch.leg, 3), 'palm L', ch.palm['l'].round(2), 'palm R', ch.palm['r'].round(2))
        for clip in ('16_46', '16_55', '09_05', '127_08', '08_01', '16_47', '07_04', '35_22'):
            Q, root = retarget(clip, ch); gait_report(ch, Q, root, clip + ' raw')
            rep = {}; Q2 = footlock(ch, Q, root, report=rep); gait_report(ch, Q2, root, clip + ' locked'); print('    lock', rep)
