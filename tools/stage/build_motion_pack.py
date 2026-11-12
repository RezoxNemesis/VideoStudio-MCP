#!/usr/bin/env python3
"""Pack the CMU Graphics Lab motion-capture database (BVH conversion, free for all uses) for the Stage.

usage: build_motion_pack.py --cmu /path/to/cmu-mocap --out <dir> [--subjects 2,135,...] [--jobs 2]
Writes <dir>/index.json and one <dir>/sNNN.pack per subject:
  r:<clip>  f32  frames x 3      root position (source units)
  q:<clip>  i16  frames x J x 4  global joint rotations (xyzw * 32767), 30 fps
  c:<clip>  u8   frames          contact bits: 1 left foot planted, 2 right foot planted
The index lists every clip with its description, tags, duration, speed, travel and turn, so motions can be searched.
"""
import argparse, json, os, re, sys, glob
from concurrent.futures import ProcessPoolExecutor
import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from packlib import write_pack

FPS = 30
KEEP = ['Hips', 'LowerBack', 'Spine', 'Spine1', 'Neck', 'Neck1', 'Head', 'LeftShoulder', 'LeftArm', 'LeftForeArm', 'LeftHand', 'RightShoulder', 'RightArm', 'RightForeArm', 'RightHand',
        'LeftUpLeg', 'LeftLeg', 'LeftFoot', 'LeftToeBase', 'RightUpLeg', 'RightLeg', 'RightFoot', 'RightToeBase']
CHILD = {'Hips': 'LowerBack', 'LowerBack': 'Spine', 'Spine': 'Spine1', 'Spine1': 'Neck', 'Neck': 'Neck1', 'Neck1': 'Head', 'LeftShoulder': 'LeftArm', 'LeftArm': 'LeftForeArm', 'LeftForeArm': 'LeftHand',
         'LeftHand': 'LeftFingerBase', 'RightShoulder': 'RightArm', 'RightArm': 'RightForeArm', 'RightForeArm': 'RightHand', 'RightHand': 'RightFingerBase', 'LeftUpLeg': 'LeftLeg', 'LeftLeg': 'LeftFoot',
         'LeftFoot': 'LeftToeBase', 'RightUpLeg': 'RightLeg', 'RightLeg': 'RightFoot', 'RightFoot': 'RightToeBase'}

TAGS = [
    ('walk', r'\bwalk|stroll|march|pace'), ('run', r'\brun|jog|sprint|dash'), ('jump', r'jump|hop\b|hops|leap|skip'), ('turn', r'turn|veer|pivot|spin'),
    ('idle', r'\bstand\b|idle|wait|rest\b|look around|balanc'), ('sit', r'\bsit|chair|stool|bench'), ('kneel', r'kneel|squat|crouch|bend'),
    ('fight', r'punch|kick|box|fight|sword|martial|karate|strike|violence|threat|bassai|empi|kata|shove|smash'), ('punch', r'punch|box|jab|hook'), ('kick', r'kick'),
    ('sword', r'sword|stab|slash'), ('fall', r'\bfall|stumble|trip\b|collapse|slip|knock'), ('getup', r'get up|getting up|stand up|rise|get-up'),
    ('dance', r'danc|salsa|charleston|pirouette|arabesque|jete|moonwalk|ballet|twist|macarena|lambada|polka|waltz'), ('gesture', r'wave|point|shrug|clap|salute|\bbow|gesture|nod|direct traffic|signal|high five|shake hands|hand'),
    ('talk', r'conversation|talk|quarrel|argu|explain|story|whisper|chat|convers|communicat'), ('emotion', r'laugh|cry|angry|sad|happy|scared|afraid|joy|depress|proud|shy|tired|drunk|cheer|nervous|excited|hurt|pain|comfort'),
    ('climb', r'climb|ladder|step up|stair|ledge|playground|hang|swing'), ('crawl', r'crawl|roll|duck|dodge|sneak|creep|cower|hide'),
    ('carry', r'carry|lift|pick|suitcase|push|pull|throw|catch|drag|box\b|bucket|broom|mop|object'), ('sport', r'basketball|soccer|football|golf|baseball|tennis|swim|skate|bowl|dribble|shoot|frisbee|yoga|stretch|exercise|jumping jacks'),
    ('acrobat', r'flip|cartwheel|handstand|acrobat|somersault|breakdance|gymnast|backflip'), ('interact', r'2 subjects|subject a\b|subject b\b'),
    ('everyday', r'drink|eat|phone|wash|sweep|clean|open|door|window|read|write|type|cook|coffee|soda|sandwich|dress|shoe|headphone|drive|yawn|sleep|wake'),
    ('animal', r'animal|chicken|monkey|dinosaur|bear|\bcat\b|\bdog\b|snake|bird|insect|elephant|gorilla|penguin|mantis|roadrunner|tiger|horse'),
    ('style', r'style|zombie|mummy|robot|swagger|strut|limp|wounded|\bold\b|macho|sexy|ghetto|cool|dragging|tiptoe|backwards|sideways|weird|mickey|alien|ghost|joy|childish|cocky|careful|drunk'),
    ('slow', r'slow|careful|sneak|tiptoe'), ('fast', r'fast|quick|sprint|dash|brisk'), ('start', r'start'), ('stop', r'stop|halt'),
]


def read_index(path):
    """{clip: description}, {subject: title} from cmu-mocap-index-text.txt"""
    desc = {}; subj = {}
    if not os.path.exists(path): return desc, subj
    for line in open(path, encoding='latin-1'):
        m = re.match(r'Subject #(\d+)\s*\((.*)\)\s*$', line.strip())
        if m: subj[int(m.group(1))] = m.group(2); continue
        m = re.match(r'(\d+_\d+)\s+(.*)$', line.strip())
        if m: desc[m.group(1)] = m.group(2).strip()
    return desc, subj


def parse(path):
    with open(path) as f: L = f.read().split('\n')
    joints = []; stack = []; i = 0; chans = 0
    while not L[i].startswith('MOTION'):
        t = L[i].split()
        if t and t[0] in ('ROOT', 'JOINT'):
            joints.append({'name': t[1], 'parent': stack[-1] if stack else -1, 'ch': [], 'chi': 0}); stack.append(len(joints) - 1)
        elif t and t[0] == 'End':
            joints.append({'name': joints[stack[-1]]['name'] + '_End', 'parent': stack[-1], 'ch': [], 'chi': 0}); stack.append(len(joints) - 1)
        elif t and t[0] == 'OFFSET': joints[stack[-1]]['off'] = np.array([float(x) for x in t[1:4]])
        elif t and t[0] == 'CHANNELS': joints[stack[-1]]['ch'] = t[2:]; joints[stack[-1]]['chi'] = chans; chans += int(t[1])
        elif t and t[0] == '}': stack.pop()
        i += 1
    n = int(L[i + 1].split()[1]); dt = float(L[i + 2].split()[2])
    D = np.array(' '.join(L[i + 3:i + 3 + n]).split(), dtype=np.float64).reshape(n, chans)
    return joints, D, dt


def rot(axis, a):
    c, s = np.cos(a), np.sin(a); z = np.zeros_like(a); o = np.ones_like(a)
    if axis == 'X': m = [[o, z, z], [z, c, -s], [z, s, c]]
    elif axis == 'Y': m = [[c, z, s], [z, o, z], [-s, z, c]]
    else: m = [[c, -s, z], [s, c, z], [z, z, o]]
    return np.moveaxis(np.array(m), 2, 0)


def mat2quat(M):
    """vectorised rotation matrices (n,3,3) -> quaternions (n,4) xyzw, sign-continuous"""
    n = len(M); q = np.zeros((n, 4))
    m00, m11, m22 = M[:, 0, 0], M[:, 1, 1], M[:, 2, 2]; t = m00 + m11 + m22
    c0 = t > 0; c1 = ~c0 & (m00 > m11) & (m00 > m22); c2 = ~c0 & ~c1 & (m11 > m22); c3 = ~c0 & ~c1 & ~c2
    s = np.sqrt(np.maximum(t[c0] + 1, 1e-12)) * 2
    q[c0] = np.stack([(M[c0, 2, 1] - M[c0, 1, 2]) / s, (M[c0, 0, 2] - M[c0, 2, 0]) / s, (M[c0, 1, 0] - M[c0, 0, 1]) / s, 0.25 * s], 1)
    s = np.sqrt(np.maximum(1 + m00[c1] - m11[c1] - m22[c1], 1e-12)) * 2
    q[c1] = np.stack([0.25 * s, (M[c1, 0, 1] + M[c1, 1, 0]) / s, (M[c1, 0, 2] + M[c1, 2, 0]) / s, (M[c1, 2, 1] - M[c1, 1, 2]) / s], 1)
    s = np.sqrt(np.maximum(1 + m11[c2] - m00[c2] - m22[c2], 1e-12)) * 2
    q[c2] = np.stack([(M[c2, 0, 1] + M[c2, 1, 0]) / s, 0.25 * s, (M[c2, 1, 2] + M[c2, 2, 1]) / s, (M[c2, 0, 2] - M[c2, 2, 0]) / s], 1)
    s = np.sqrt(np.maximum(1 + m22[c3] - m00[c3] - m11[c3], 1e-12)) * 2
    q[c3] = np.stack([(M[c3, 0, 2] + M[c3, 2, 0]) / s, (M[c3, 1, 2] + M[c3, 2, 1]) / s, 0.25 * s, (M[c3, 1, 0] - M[c3, 0, 1]) / s], 1)
    flip = np.cumprod(np.where(np.einsum('ij,ij->i', q[1:], q[:-1]) < 0, -1.0, 1.0))
    q[1:] *= flip[:, None]
    return q


def convert(path):
    joints, D, dt = parse(path)
    step = max(1, int(round(1 / (FPS * dt)))); D = D[::step]; n = len(D)
    name = {j['name']: i for i, j in enumerate(joints)}
    for k in KEEP + ['LeftFingerBase', 'RightFingerBase']:
        if k not in name: raise ValueError(f'no joint {k}')
    G = [None] * len(joints); Pw = [None] * len(joints)
    for i, j in enumerate(joints):
        R = np.tile(np.eye(3), (n, 1, 1))
        for k, c in enumerate(j['ch']):
            if c.endswith('rotation'): R = R @ rot(c[0], np.radians(D[:, j['chi'] + k]))
        if j['parent'] < 0:
            G[i] = R; Pw[i] = np.stack([D[:, j['chi'] + j['ch'].index(c)] for c in ('Xposition', 'Yposition', 'Zposition')], 1)
        else:
            p = j['parent']; G[i] = G[p] @ R; Pw[i] = Pw[p] + np.einsum('nij,j->ni', G[p], j['off'])
    def unit(o):
        l = np.linalg.norm(o); return (o / l).round(5).tolist() if l > 1e-9 else [0.0, 1.0, 0.0]
    rest = {k: unit(joints[name[CHILD[k]]]['off']) for k in CHILD}
    for k in ('Head', 'LeftToeBase', 'RightToeBase'): rest[k] = unit(joints[name[k + '_End']]['off'])
    leg = float(np.linalg.norm(joints[name['LeftLeg']]['off']) + np.linalg.norm(joints[name['LeftFoot']]['off']))
    Q = np.stack([mat2quat(G[name[k]]) for k in KEEP], 1)
    root = Pw[name['Hips']].astype(np.float32)
    feet = np.stack([Pw[name[k]] for k in ('LeftFoot', 'RightFoot', 'LeftToeBase', 'RightToeBase')], 1)
    ground = float(np.percentile(feet[:, 2:, 1].min(1), 5))
    ankle = float(np.percentile(feet[:, :2, 1].min(1), 5)) - ground
    stand = float(leg + abs(joints[name['LeftUpLeg']]['off'][1]) + ankle)
    # contacts: a foot is planted when its ankle is low and nearly still
    con = np.zeros(n, np.uint8)
    for bit, a in ((1, 0), (2, 1)):
        v = np.zeros(n)
        if n > 1: v[1:] = np.linalg.norm(np.diff(feet[:, a], axis=0), axis=1) * FPS; v[0] = v[1]
        low = feet[:, a, 1] - ground < ankle + 0.09 * leg
        con |= (bit * (low & (v < 0.45 * leg))).astype(np.uint8)
    m_per_unit = 0.85 / leg   # a 0.85 m leg (hip to ankle) is typical
    xz = root[:, [0, 2]].astype(np.float64)
    seg = np.linalg.norm(np.diff(xz, axis=0), axis=1) if n > 1 else np.zeros(0)
    fwd = np.einsum('nij,j->ni', G[name['Hips']], np.array([0.0, 0.0, 1.0])); yaw = np.unwrap(np.arctan2(fwd[:, 0], fwd[:, 2]))
    meta = {'n': int(n), 'dur': round((n - 1) / FPS, 3), 'ground': round(ground, 4), 'stand': round(stand, 4),
            'speed': round(float(np.median(seg) * FPS * m_per_unit) if n > 1 else 0.0, 2), 'path': round(float(seg.sum() * m_per_unit), 2),
            'travel': round(float(np.linalg.norm(xz[-1] - xz[0]) * m_per_unit), 2), 'turn': round(float(np.degrees(yaw[-1] - yaw[0])), 1),
            'air': round(float((con == 0).mean()), 2)}
    return meta, {'leg': round(leg, 5), 'rest': rest}, root, np.round(Q * 32767).astype(np.int16), con


def do_subject(args):
    subj, files, out, desc = args
    arrays = {}; clips = {}; skel = None; bad = []
    for f in files:
        nm = os.path.basename(f)[:-4]
        try:
            meta, sk, root, Q, con = convert(f)
        except Exception as e:
            bad.append(f'{nm}: {e}'); continue
        if meta['n'] < 8: bad.append(f'{nm}: too short'); continue
        if skel is None: skel = sk
        elif abs(sk['leg'] - skel['leg']) > 1e-3 or sk['rest'] != skel['rest']: meta['leg'] = sk['leg']; meta['rest'] = sk['rest']
        d = desc.get(nm, ''); meta['desc'] = d
        arrays[f'r:{nm}'] = root; arrays[f'q:{nm}'] = Q.reshape(-1); arrays[f'c:{nm}'] = con
        clips[nm] = meta
    if clips: write_pack(os.path.join(out, f's{subj:03d}.pack'), {'kind': 'motion', 'subject': subj, 'fps': FPS, 'joints': KEEP}, arrays)
    return subj, clips, skel, bad


def tags_of(text):
    t = text.lower(); return [name for name, rx in TAGS if re.search(rx, t)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--cmu', required=True); ap.add_argument('--out', required=True); ap.add_argument('--subjects', default=''); ap.add_argument('--jobs', type=int, default=2)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    desc, titles = read_index(os.path.join(a.cmu, 'cmu-mocap-index-text.txt'))
    only = {int(x) for x in a.subjects.split(',') if x.strip()}
    jobs = []
    for d in sorted(glob.glob(os.path.join(a.cmu, 'data', '*'))):
        try: subj = int(os.path.basename(d))
        except ValueError: continue
        if only and subj not in only: continue
        files = sorted(glob.glob(os.path.join(d, '*.bvh')))
        if files: jobs.append((subj, files, a.out, desc))
    index = {'fps': FPS, 'joints': KEEP, 'source': 'CMU Graphics Lab Motion Capture Database (mocap.cs.cmu.edu), BVH conversion by B. Hahne', 'subjects': {}, 'clips': {}}
    total = 0; allbad = []
    with ProcessPoolExecutor(a.jobs) as ex:
        for subj, clips, skel, bad in ex.map(do_subject, jobs):
            allbad += bad
            if not clips: continue
            title = titles.get(subj, '')
            index['subjects'][str(subj)] = {'title': title, 'leg': skel['leg'], 'rest': skel['rest'], 'clips': len(clips)}
            for nm, m in clips.items():
                m['s'] = subj; m['tags'] = tags_of(f"{m['desc']} | {title}"); index['clips'][nm] = m; total += m['dur']
            print(f'subject {subj:3d}: {len(clips):3d} clips  {title[:60]}', flush=True)
    json.dump(index, open(os.path.join(a.out, 'index.json'), 'w'), separators=(',', ':'))
    size = sum(os.path.getsize(p) for p in glob.glob(os.path.join(a.out, '*')))
    print(f'{len(index["clips"])} clips, {total / 60:.1f} min of motion, {size / 1e6:.1f} MB; skipped {len(allbad)}')
    for b in allbad[:20]: print('  skipped', b)


if __name__ == '__main__':
    main()
