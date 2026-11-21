# LIPS — mouth shapes from a spoken line: what is said (Devanagari or Latin text) laid along how it was said (the sound).
# The text gives the order of the shapes (visemes); the sound gives when: its phrases (the silences between them) and
# its syllables (the peaks of loudness). Out: a track at 60 a second of [open, width, tongue] that a clay puppet's
# mouth follows (stage.js: lipAt). Plain Python, nothing to install; the voices are 16-bit .wav files.
#
#   lips.py lines.json lips.json        lines.json: { "id": { "file": "voices/R1.wav", "text": "what is said" }, … }
#   (file names are taken from the film's folder — the folder of lines.json — unless they are whole paths)
import sys, json, re, os, math, wave, array

V = {  # viseme → (open, width, tongue)
    'AA': (0.85, 1.0, 0), 'A': (0.5, 1.0, 0), 'E': (0.42, 1.18, 0), 'I': (0.22, 1.28, 0), 'O': (0.6, 0.72, 0), 'U': (0.28, 0.56, 0),
    'M': (0.0, 0.95, 0), 'F': (0.13, 1.0, 0), 'L': (0.3, 1.05, 1), 'S': (0.12, 1.15, 0), 'K': (0.36, 1.0, 0), 'R': (0.28, 0.86, 0.4), 'Y': (0.24, 1.15, 0), 'W': (0.2, 0.62, 0), '_': (0.0, 1.0, 0)}
VOW = {'अ': 'A', 'आ': 'AA', 'इ': 'I', 'ई': 'I', 'उ': 'U', 'ऊ': 'U', 'ए': 'E', 'ऐ': 'E', 'ओ': 'O', 'औ': 'O', 'ऑ': 'O', 'ऋ': 'I'}
MAT = {'ा': 'AA', 'ि': 'I', 'ी': 'I', 'ु': 'U', 'ू': 'U', 'े': 'E', 'ै': 'E', 'ो': 'O', 'ौ': 'O', 'ॉ': 'O', 'ृ': 'I', 'ॅ': 'E'}
CON = {}
for ch in 'पबभम': CON[ch] = 'M'
CON['फ'] = 'F'; CON['व'] = 'W'; CON['र'] = 'R'; CON['य'] = 'Y'
for ch in 'तथदधनटठडढणल': CON[ch] = 'L'
for ch in 'सशषचछजझ': CON[ch] = 'S'
for ch in 'कखगघहङञ': CON[ch] = 'K'
LAT = {'a': 'A', 'e': 'E', 'i': 'I', 'o': 'O', 'u': 'U', 'm': 'M', 'b': 'M', 'p': 'M', 'f': 'F', 'v': 'F', 'w': 'W', 'r': 'R', 'y': 'Y', 'l': 'L', 't': 'L', 'd': 'L', 'n': 'L', 's': 'S', 'z': 'S', 'c': 'S', 'j': 'S', 'x': 'S', 'k': 'K', 'g': 'K', 'h': 'K', 'q': 'K'}
VOWELS = ('AA', 'A', 'E', 'I', 'O', 'U')

def word_visemes(w):
    out = []; i = 0; n = len(w)
    while i < n:
        c = w[i]
        if c in VOW: out.append(VOW[c])
        elif c in CON:
            v = CON[c]; j = i + 1
            if j < n and w[j] == '़': j += 1; v = 'F' if c == 'फ' else 'S' if c == 'ज' else v
            out.append(v)
            if j < n and w[j] in MAT: out.append(MAT[w[j]]); j += 1
            elif j < n and w[j] == '्': j += 1
            elif j < n: out.append('A')          # the inherent vowel (dropped at a word's end, as it is spoken)
            i = j - 1
        elif c in 'ंँ':
            nx = w[i + 1] if i + 1 < n else ''
            if nx in 'पबभमफ': out.append('M')
        elif c.lower() in LAT: out.append(LAT[c.lower()])
        i += 1
    return out

def phrases(text):
    """→ [[viseme…] per phrase]: phrases are what the punctuation separates (the speaker breathes there)"""
    return [p for p in ([v for w in re.split(r'\s+', ph.strip()) for v in word_visemes(w) + ['|']] for ph in re.split(r'[…,?!।.\-–—:;"“”]+|\.\.\.', text)) if any(x != '|' for x in p)]

def read(path):
    with wave.open(path) as w:
        sr = w.getframerate(); ch = w.getnchannels(); sw = w.getsampwidth(); raw = w.readframes(w.getnframes())
    if sw != 2: raise SystemExit(f'{path}: give a 16-bit .wav (ffmpeg -i in -c:a pcm_s16le out.wav)')
    a = array.array('h'); a.frombytes(raw)
    if sys.byteorder == 'big': a.byteswap()
    return a, sr, ch

def pct(xs, p): s = sorted(xs); return s[min(len(s) - 1, int(p / 100 * len(s)))] if s else 0.0

def track(path, text, fps=60):
    a, sr, ch = read(path); hop = sr // 200; n = (len(a) // ch) // hop; dur = len(a) / ch / sr
    e = []                                                                       # the loudness, 200 times a second
    for i in range(n):
        seg = a[i * hop * ch:(i + 1) * hop * ch]; e.append(math.sqrt(sum(v * v for v in seg) / max(1, len(seg))) / 32768 + 1e-9)
    k = [0.5 - 0.5 * math.cos(2 * math.pi * i / 8) for i in range(9)]; ks = sum(k); es = [sum(e[min(n - 1, max(0, i + j - 4))] * k[j] for j in range(9)) / ks for i in range(n)]
    ref = pct(es, 95) + 1e-9; thr = 0.07 * ref; on = [v > thr for v in es]; segs = []; i = 0
    while i < n:                                                                 # the stretches of speech; a silence under 0.14 s is part of the word
        if on[i]:
            j = i
            while j < n and (on[j] or (j + 28 < n and any(on[j:j + 28]))): j += 1
            if j - i > 12: segs.append([i / 200, j / 200])
            i = j
        else: i += 1
    ph = phrases(text)
    if not segs or not ph: return {'fps': fps, 'dur': round(dur, 3), 'v': [], 'keys': []}
    wg = lambda v: 0 if v == '|' else 1.0 if v in ('AA', 'E', 'O', 'I', 'U') else 0.7 if v == 'A' else 0.5
    def cost(sg, pz):                                                            # how well the lengths of the stretches agree with the lengths of the phrases
        d = [b - a_ for a_, b in sg]; w = [sum(wg(v) for v in p) for p in pz]; sd, sw_ = sum(d), sum(w) or 1; return sum(abs(x / sd - y / sw_) for x, y in zip(d, w))
    while len(segs) > len(ph):                                                   # more stretches than phrases: join the two that make them agree best
        m = len(segs) - 1
        best = min(range(m), key=lambda g: cost(segs[:g] + [[segs[g][0], segs[g + 1][1]]] + segs[g + 2:], ph) if len(ph) == m else segs[g + 1][0] - segs[g][1])
        segs[best][1] = segs[best + 1][1]; del segs[best + 1]
    while len(ph) > len(segs):                                                   # more phrases than stretches: two phrases were said in one breath
        m = len(ph) - 1
        best = min(range(m), key=lambda g: cost(segs, ph[:g] + [ph[g] + ph[g + 1]] + ph[g + 2:]) if m == len(segs) else len(ph[g]) + len(ph[g + 1]))
        ph[best] = ph[best] + ph[best + 1]; del ph[best + 1]
    keys = []                                                                    # (time, viseme)
    for (s0, s1), vs in zip(segs, ph):
        while vs and vs[-1] == '|': vs = vs[:-1]
        tot = sum(wg(v) for v in vs) or 1; t = s0; times = []
        for v in vs: times.append(t + wg(v) * (s1 - s0) / tot / 2); t += wg(v) * (s1 - s0) / tot
        vow = [i for i, v in enumerate(vs) if v in VOWELS]
        # the syllables heard: peaks of loudness in this stretch; when there are about as many as vowels, each vowel goes to its peak
        i0, i1 = int(s0 * 200), min(n - 3, int(s1 * 200)); pk = [i for i in range(i0 + 2, i1 - 2) if es[i] >= es[i - 1] and es[i] > es[i + 1] and es[i] > 0.28 * ref and es[i] - min(es[max(i0, i - 14):i + 15]) > 0.1 * ref]
        mer = []
        for p in pk:
            if mer and p - mer[-1] < 18: mer[-1] = p if es[p] > es[mer[-1]] else mer[-1]
            else: mer.append(p)
        if vow and 0.75 <= len(mer) / len(vow) <= 1.34:
            tv = [mer[min(len(mer) - 1, int(round(q * (len(mer) - 1) / max(1, len(vow) - 1))))] / 200 for q in range(len(vow))]
            for q in range(1, len(tv)): tv[q] = max(tv[q], tv[q - 1] + 0.05)
            prev_t, prev_i = s0, -1
            for q, vi in enumerate(vow + [len(vs)]):
                t1 = tv[q] if q < len(tv) else s1; span = list(range(prev_i + 1, vi))
                for r, ci in enumerate(span): times[ci] = prev_t + (t1 - prev_t) * (r + 1) / (len(span) + 1)
                if q < len(tv): times[vi] = tv[q]
                prev_t, prev_i = t1, vi
        for v, tt in zip(vs, times):
            if v != '|': keys.append((min(s1, max(s0, tt)), v))
        keys.append((s1 + 0.05, '_'))
    keys.sort(); N = int(math.ceil(dur * fps)) + 1; out = [[0.0, 1.0, 0.0] for _ in range(N)]; first = segs[0][0]; j = 0
    for f in range(N):
        t = f / fps + 0.03                                                       # the mouth is a little ahead of the sound
        if t < first - 0.06 or t > keys[-1][0]: continue
        while j < len(keys) and keys[j][0] < t: j += 1
        a_ = V[keys[j - 1][1]] if j > 0 else V['_']; b_ = V[keys[min(len(keys) - 1, j)][1]]
        ta = keys[j - 1][0] if j > 0 else first - 0.06; tb = keys[min(len(keys) - 1, j)][0]; u = 0 if tb <= ta else (t - ta) / (tb - ta); u = u * u * (3 - 2 * u)
        lv = es[min(n - 1, max(0, int((t - 0.03) * 200)))] / ref
        out[f] = [(a_[0] + (b_[0] - a_[0]) * u) * min(1.12, max(0.5, 0.55 + 0.6 * lv)), a_[1] + (b_[1] - a_[1]) * u, a_[2] + (b_[2] - a_[2]) * u]   # a loud vowel opens wider
    for tt, v in keys:                                                           # lips that meet (m, b, p) are shut for two frames at least
        if v == 'M':
            f = int(round((tt - 0.03) * fps))
            for g in range(max(0, f - 1), min(N, f + 2)): out[g][0] = 0.0
    return {'fps': fps, 'dur': round(dur, 3), 'v': [[round(x, 3), round(y, 3), round(z, 2)] for x, y, z in out], 'keys': [[round(t, 3), v] for t, v in keys]}

if __name__ == '__main__':
    if len(sys.argv) < 3: raise SystemExit(__doc__ or 'lips.py lines.json lips.json')
    base = os.path.dirname(os.path.abspath(sys.argv[1])); lines = json.load(open(sys.argv[1], encoding='utf-8')); res = {}
    for k, l in lines.items():
        f = l['file'] if os.path.isabs(l['file']) else os.path.join(base, l['file'])
        res[k] = track(f, l['text']); print(k, 'shapes', len(res[k]['keys']), 'seconds', res[k]['dur'], ' '.join(f"{t:.2f}{v}" for t, v in res[k]['keys'][:60]))
    json.dump({k: {'fps': r['fps'], 'v': r['v']} for k, r in res.items()}, open(sys.argv[2] if os.path.isabs(sys.argv[2]) else os.path.join(base, sys.argv[2]), 'w'))
