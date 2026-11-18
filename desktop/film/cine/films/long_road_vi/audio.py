"""The film's sound, all synthesised here: score (kalimba-like plucks, pads, bass, drums), footsteps on the real foot strikes, weather, the gate, the crowd."""
import numpy as np, json, sys
from scipy.signal import lfilter, butter, sosfilt, fftconvolve
from scipy.io import wavfile
import os
from cinepaths import FILM
SR = 44100; DUR = 284.0; N = int(DUR * SR); R = np.random.RandomState(4)
# the first cut ran 223 s; the second cut adds the memories (154-186), a longer goodbye and the boulevard. Times written for
# the first cut are carried to where those moments now are (TM); new moments are written in the film's own seconds (putn).
def TM(t): return t if t < 154 else t + 32 if t < 199 else t + 45 if t < 215 else t + 61
SHOT = dict(s01=0, s02=14, s03=28, s04=42, s05=56, s06=70, s07=79, s08=88, s09=98, s10=114, s11=128, s12=142, m1=154, m2=164, s13=186, s14=204, s15=220, s16=244, s16b=260, s17=276)
BAR = 14 / 6; BEAT = BAR / 4
mus = np.zeros((N, 2)); fx = np.zeros((N, 2))
def f(m): return 440 * 2 ** ((m - 69) / 12)
def put(buf, sig, t, g=1.0, pan=0.0, raw=False):
    i = int((t if raw else TM(t)) * SR)
    if i >= N or i + len(sig) <= 0: return
    if i < 0: sig = sig[-i:]; i = 0
    n = min(len(sig), N - i); l = np.cos((pan + 1) * np.pi / 4) * 1.414; r = np.sin((pan + 1) * np.pi / 4) * 1.414
    buf[i:i + n, 0] += sig[:n] * g * l; buf[i:i + n, 1] += sig[:n] * g * r
def putn(buf, sig, t, g=1.0, pan=0.0): put(buf, sig, t, g, pan, raw=True)
def lp(x, fc, o=2): return sosfilt(butter(o, min(fc, SR * 0.45) / (SR / 2), output='sos'), x)
def hp(x, fc, o=2): return sosfilt(butter(o, fc / (SR / 2), 'high', output='sos'), x)
def bp(x, a, b): return sosfilt(butter(2, [a / (SR / 2), b / (SR / 2)], 'band', output='sos'), x)
def env(n, a=0.005, r=0.1):
    e = np.ones(n); na = max(1, int(a * SR)); nr = max(1, int(r * SR)); e[:na] = np.linspace(0, 1, na); e[-nr:] *= np.linspace(1, 0, nr) ** 2; return e
def pluck(m, dur=1.6, bright=0.5, damp=0.996):
    """Karplus-Strong string: a thumb-piano / guitar colour"""
    fr = f(m); P = int(SR / fr); n = int(dur * SR); x = np.zeros(n); x[:P] = lp(R.uniform(-1, 1, P + 64), 1500 + 9000 * bright)[64:]
    a = np.zeros(P + 2); a[0] = 1; a[P] = -0.5 * damp; a[P + 1] = -0.5 * damp
    y = lfilter([1], a, x); return y * env(n, 0.001, 0.25) / (np.abs(y).max() + 1e-9)
def saws(m, dur, det=0.012, fc=1400):
    n = int(dur * SR); t = np.arange(n) / SR; y = np.zeros(n)
    for d in (-det, 0, det): ph = (f(m) * (1 + d)) * t + R.rand(); y += 2 * (ph % 1) - 1
    return lp(y / 3, fc)
def pad(ms, dur, fc=1200, a=1.2, r=1.6):
    y = sum(saws(m, dur, 0.008 + 0.004 * R.rand(), fc) for m in ms) / len(ms); return y * env(len(y), a, r)
def bass(m, dur):
    n = int(dur * SR); t = np.arange(n) / SR; y = np.sin(2 * np.pi * f(m) * t) + 0.35 * lp(2 * ((f(m) * t) % 1) - 1, 500); return y * env(n, 0.006, 0.08) * np.exp(-t * 1.6)
def kick(g=1.0):
    t = np.arange(int(0.32 * SR)) / SR; return np.sin(2 * np.pi * (48 * t + 38 * (1 - np.exp(-t * 28)) / 28 * 4)) * np.exp(-t * 11) * g
def snare():
    n = int(0.22 * SR); t = np.arange(n) / SR; return (hp(R.randn(n), 1400) * np.exp(-t * 22) * 0.7 + np.sin(2 * np.pi * 190 * t) * np.exp(-t * 30) * 0.5)
def hat(d=0.05): n = int(d * SR); return hp(R.randn(n), 7000) * np.exp(-np.arange(n) / SR * (3 / d)) * 0.35
def clap(): n = int(0.16 * SR); t = np.arange(n) / SR; e = sum(np.exp(-np.maximum(t - d, 0) * 60) * (t >= d) for d in (0, 0.011, 0.023)); return bp(R.randn(n), 900, 3200) * e * 0.5
def tom(m): t = np.arange(int(0.4 * SR)) / SR; return np.sin(2 * np.pi * f(m) * t * (1 + 0.3 * np.exp(-t * 20))) * np.exp(-t * 9)
def noise(dur, a, b): return bp(R.randn(int(dur * SR)), a, b)
def sweep(dur, f0, f1, a=0.05, r=0.2): n = int(dur * SR); t = np.arange(n) / SR; fr = f0 * (f1 / f0) ** (t / dur); return np.sin(2 * np.pi * np.cumsum(fr) / SR) * env(n, a, r)
# ---------------- score ----------------
D, Bm, G, A, Em, Fsm = [50, 57, 62, 66, 69], [47, 54, 59, 62, 66], [43, 55, 59, 62, 67], [45, 57, 61, 64, 69], [40, 52, 55, 59, 64], [42, 54, 57, 61, 66]
MOT = [(66, 0, 1), (69, 1, 1), (74, 2, 1.5), (71, 3.5, .5), (69, 4, 2), (66, 6, 1), (64, 7, 1), (62, 8, 1), (66, 9, 1), (69, 10, 1.5), (67, 11.5, .5), (66, 12, 2), (64, 14, 2)]
def motif(t0, g=0.5, tr=0, bright=0.6, stretch=1.0, pan=0.15):
    for m, b, d in MOT: put(mus, pluck(m + tr, 1.2 + d * 0.5, bright), t0 + b * BEAT * stretch, g, pan * (1 if (b % 2) < 1 else -1))
def chords(t0, prog, g=0.16, fc=1100, oct_=0):
    for i, c in enumerate(prog): put(mus, pad([x + oct_ for x in c[1:]], BAR + 0.9, fc), t0 + i * BAR - 0.25, g)
def groove(t0, prog, drums=1.0, bassg=0.5, arp=0.22, hats=True):
    for i, c in enumerate(prog):
        tb = t0 + i * BAR
        for b in range(4):
            put(mus, bass(c[0] - (12 if c[0] > 46 else 0) + (0 if b != 3 else 7), BEAT * 0.9), tb + b * BEAT, bassg * (1 if b in (0, 2) else 0.7))
            if drums: put(mus, kick(), tb + b * BEAT, 0.55 * drums if b in (0, 2) else 0.0);
            if drums and b in (1, 3): put(mus, clap(), tb + b * BEAT, 0.5 * drums); put(mus, snare(), tb + b * BEAT, 0.22 * drums)
            for h in range(2):
                if hats: put(mus, hat(), tb + (b + h * 0.5) * BEAT, (0.5 if h == 0 else 0.3) * (drums + 0.3))
                if arp: put(mus, pluck(c[1 + (b * 2 + h) % 4] + 12, 0.7, 0.75), tb + (b + h * 0.5) * BEAT, arp, 0.5 * ((-1) ** (b + h)))
P1 = [D, Bm, G, A]
# A: the stone, the boy
chords(0, [D, D, G, A, D, Bm], 0.15, 900); motif(4.67, 0.5); put(mus, pluck(74, 2, 0.6), 2.33, 0.35); put(mus, pluck(69, 2, 0.6), 3.5, 0.3)
# B: childhood (shots 2, 3)
chords(14, P1 * 3, 0.13, 1300); groove(14, P1 * 3, 0.8, 0.45, 0.2)
for k in range(3): motif(14 + k * 4 * BAR, 0.5, 0 if k < 2 else 12, 0.7)
# C: monsoon
PC = [Bm, G, Em, Fsm, Bm, G]; chords(42, PC, 0.16, 800); groove(42, PC, 0.35, 0.4, 0.2, hats=False)
for i, m in enumerate([71, 74, 78, 74, 71, 69, 66, 69, 71, 74, 76, 74]): put(mus, pluck(m, 1.8, 0.35), 42 + i * BEAT * 2, 0.4, -0.2)
# D: the fork
PD = [G, A, Bm, Bm, G, A]; chords(56, PD, 0.2, 1000, 12); chords(56, PD, 0.14, 700); groove(56, PD, 0.0, 0.35, 0.0, hats=False)
motif(56 + BAR, 0.42, 0, 0.45, 1.25)
# E: into the sun
put(mus, pad([50, 57, 62, 66, 69, 74], 9.6, 1600, 3.5, 2.0), 69.8, 0.26)
for i in range(15): put(mus, bass(38, BEAT), 70 + i * BEAT, 0.3 + 0.02 * i); put(mus, pluck([62, 66, 69, 74, 78][i % 5] + (12 if i > 9 else 0), 1.2, 0.7), 70 + i * BEAT, 0.14 + 0.012 * i, 0.4 * (-1) ** i)
# F: fog and the hoarding
put(mus, pad([47, 54, 59, 61], 19.5, 600, 3, 3), 78.6, 0.2)
for i, m in enumerate([59, 62, 66, 61, 59, 57, 54, 59]): put(mus, pluck(m, 2.6, 0.3), 80 + i * BAR, 0.36, 0.1)
put(mus, pluck(66, 1.4, 0.4), 93.7, 0.45); put(mus, pluck(62, 2.2, 0.4), 94.35, 0.45); put(mus, pluck(57, 2.6, 0.35), 95.0, 0.4)
# G: the light; he runs
put(mus, pad([43, 55, 59, 62], 5.2, 700, 2, 1.5), 98, 0.16); put(mus, pad([55, 62, 67, 71, 74], 5.0, 1800, 3.2, 1.2), 102.4, 0.2); put(mus, pad([57, 64, 69, 73, 76], 3.2, 2200, 1.2, 1.0), 105.6, 0.22)
for i in range(22): put(mus, pluck([67, 71, 74, 79, 78, 74][i % 6] + (12 if i > 11 else 0), 0.9, 0.8), 102.6 + i * BEAT / 2 * 1.9, 0.10 + 0.008 * i, 0.5 * (-1) ** i)
T_RUN = 108.2; PG = [D, G, A, D]; chords(T_RUN, PG, 0.15, 1500); groove(T_RUN, PG, 1.0, 0.5, 0.22)
# H: the barrier: everything stops
put(mus, tom(45), 117.5, 0.7); put(mus, tom(38), 117.72, 0.8); put(mus, pad([38, 45, 50], 10.5, 420, 1.0, 3), 117.6, 0.22)
# I: it slides away; he sits
PI = [Bm, G, Bm, G, Em, Fsm]; chords(128, PI, 0.15, 760); motif(134.2, 0.36, -12, 0.3, 1.3)
# J: the wait
put(mus, pad([42, 54, 57, 61, 64], 12.6, 650, 2.5, 2.5), 141.8, 0.2)
for i, m in enumerate([66, 61, 64, 57, 66, 69]): put(mus, pluck(m, 2.8, 0.3), 143 + i * 1.9, 0.3, -0.1)
# K: they come back; everyone
chords(157.4, [D, D, G, A, D, Bm], 0.17, 1200); motif(159.0, 0.5, 0, 0.6)
for i in range(16): put(mus, kick(), 163.2 + i * BEAT * 0.94, 0.12 + 0.03 * i); put(mus, tom(50 - (i % 3) * 5), 163.2 + (i + 0.5) * BEAT * 0.94, 0.05 + 0.022 * i, 0.3 * (-1) ** i)
# L: the gate
put(mus, pad([81, 88], 10.4, 2600, 2.5, 0.4), 171.8, 0.1); put(mus, pad([38, 45], 10.4, 300, 2, 0.4), 171.8, 0.24)
for i in range(9): put(mus, kick(), 172.4 + i * 1.08, 0.5); put(mus, kick(0.7), 172.4 + i * 1.08 + 0.21, 0.4)
T_OPEN = 182.0; put(mus, pad([50, 57, 62, 66, 69, 74, 78], 6.4, 2600, 0.03, 2.2), T_OPEN, 0.34)
PL = [D, G]; groove(T_OPEN + 0.5, PL, 1.0, 0.5, 0.22)
# M: he looks back
put(mus, pad([50, 57, 62, 66], 11.4, 800, 2, 2.2), 187.6, 0.2); motif(189.2, 0.55, 0, 0.5, 1.12); put(mus, pad([62, 69, 74, 78], 5, 1500, 2.5, 1.5), 194.2, 0.17)
# N: through the gate; title
chords(199, P1 * 1 + [D, Bm], 0.17, 1700); groove(199, P1 + [D, Bm], 1.0, 0.55, 0.24); motif(199, 0.5, 12, 0.75); motif(199 + 4 * BAR, 0.32, 12, 0.6)
put(mus, pad([38, 50, 57, 62, 66, 69, 74], 5.2, 2400, 0.03, 4.0), 213.0, 0.3)
# O: the next stone
put(mus, pad([50, 57, 62], 8.2, 700, 1.5, 2.5), 215.0, 0.12); put(mus, pluck(69, 1.6, 0.6), 219.1, 0.45); put(mus, pluck(72, 2.4, 0.6), 219.6, 0.45); put(mus, pluck(62, 3.0, 0.5), 221.4, 0.4)
# ---- second cut: the memories ----
def box(m, dur=1.4):   # a music box: a bright, short, glassy pluck two octaves up
    n = int(dur * SR); t = np.arange(n) / SR; return (np.sin(2 * np.pi * f(m + 24) * t) + 0.35 * np.sin(2 * np.pi * f(m + 24) * 3.01 * t) * np.exp(-t * 9)) * np.exp(-t * 4.2) * env(n, 0.002, 0.2)
def chip(m, dur=0.09): n = int(dur * SR); t = np.arange(n) / SR; return np.sign(np.sin(2 * np.pi * f(m) * t)) * env(n, 0.002, 0.02) * 0.25
putn(mus, pad([42, 54, 57, 61], 11.5, 520, 2.5, 3), 153.4, 0.2)                                    # m1: the wait, lower and closer
for i, (m, b) in enumerate([(66, 0), (61, 1.6), (57, 3.4), (54, 5.4)]): putn(mus, pluck(m, 3.0, 0.25), 155 + b, 0.3, -0.1)
putn(mus, pad([62, 66, 69, 74], 4.0, 1800, 2.6, 0.8), 160.4, 0.16)                                  # light rising in the puddle
MB = [D, Bm, G, A, D, Bm, G, A, D]; BARM = 22 / 9.4
for i, c in enumerate(MB): putn(mus, pad(c[1:], BARM + 0.8, 1000 if i < 6 else 1500), 164.4 + i * BARM, 0.12)
for k in range(3):                                                                                 # the theme as a music box: 2013, 2016, 2023 (each time a little lower and slower)
    t0 = [165.0, 173.0, 179.6][k]
    for m, b, d in MOT[:9 if k < 2 else 13]: putn(mus, box(m - 12 * (k == 2), 1.2 + d * 0.4), t0 + b * BEAT * [0.9, 1.0, 1.15][k], [0.26, 0.22, 0.2][k], 0.3 * (1 if (b % 2) < 1 else -1))
for t0, T in ((164.8, 8.0), (173.0, 6.2)):                                                           # the game's own little tune from the television
    i = 0; t = t0
    while t < t0 + T: putn(mus, lp(chip([74, 78, 81, 86, 81, 78][i % 6] + 12 * ((i // 6) % 2)), 2600), t, 0.045, -0.5); t += BEAT / 2; i += 1
putn(mus, pad([38, 45, 50, 57], 5.5, 500, 0.05, 3.5), 180.3, 0.3)                                   # 2023: the trailer lands
putn(mus, pad([62, 66, 69, 74, 78], 3.6, 2400, 2.6, 0.6), 182.6, 0.2)
# ---- second cut: the goodbye is longer ----
putn(mus, pad([50, 57, 62, 66], 13.5, 800, 2.5, 2.5), 230.6, 0.2); 
for m, b, d in MOT: putn(mus, pluck(m, 1.6 + d * 0.5, 0.4), 232.0 + b * BEAT * 1.3, 0.42, 0.15 * (1 if (b % 2) < 1 else -1))
putn(mus, pad([55, 62, 67, 71], 4.5, 900, 1.5, 2.5), 239.6, 0.16)
# ---- second cut: the boulevard ----
PB = [G, A, Bm, D, G, A, D]; BARB = 16 / 7
for i, c in enumerate(PB): putn(mus, pad(c[1:], BARB + 0.9, 1400 + 250 * i), 260 + i * BARB - 0.2, 0.17); putn(mus, pad([x + 12 for x in c[1:]], BARB + 0.9, 2000 + 300 * i, 1.2, 1.2), 260 + i * BARB - 0.2, 0.05 + 0.02 * i)
for i, c in enumerate(PB[:6]):
    for b in range(4):
        putn(mus, bass(c[0] - (12 if c[0] > 46 else 0), BARB / 4 * 0.9), 260 + i * BARB + b * BARB / 4, 0.42 if b in (0, 2) else 0.3); putn(mus, kick(), 260 + i * BARB + b * BARB / 4, 0.45 if b in (0, 2) else 0.0)
        if b in (1, 3): putn(mus, clap(), 260 + i * BARB + b * BARB / 4, 0.36)
        for h in range(2): putn(mus, pluck(c[1 + (b * 2 + h) % 4] + 12, 0.7, 0.75), 260 + i * BARB + (b + h * 0.5) * BARB / 4, 0.2, 0.5 * ((-1) ** (b + h)))
for m, b, d in MOT: putn(mus, pluck(m + 12, 1.2 + d * 0.5, 0.75), 262.3 + b * BARB / 4, 0.45, 0.15 * (1 if (b % 2) < 1 else -1))
putn(mus, pad([38, 50, 57, 62, 66, 69, 74, 78], 6.0, 2600, 0.04, 4.2), 272.2, 0.3)                    # GTA 6 comes on
ir = R.randn(int(2.4 * SR), 2) * np.exp(-np.arange(int(2.4 * SR)) / SR * 2.6)[:, None] * 0.02
mus = mus + np.stack([fftconvolve(mus[:, c], ir[:, c])[:N] for c in (0, 1)], 1) * 0.9
# ---------------- sound of the world ----------------
def strikes(cname, track):
    from mocap import Char, R as Rot
    ch = Char(cname); a = np.fromfile(os.path.join(FILM, 'data', 'mo', track + '.bin'), '<f4'); F, nb = int(a[0]), int(a[1]); d = a[2:].reshape(F, 3 + nb * 4); root = d[:, :3]; q = d[:, 3:].reshape(F, nb, 4)
    G = [None] * nb; P = np.zeros((F, nb, 3))
    for i in range(nb):
        p = ch.par[i]
        if p < 0: G[i] = Rot.from_quat(q[:, i]); continue
        if ch.bones[i] == 'pelvis': G[i] = Rot.from_quat(q[:, i]); P[:, i] = root; continue
        G[i] = G[p] * Rot.from_quat(q[:, i]); P[:, i] = P[:, p] + G[p].apply(ch.head[i] - ch.head[p])
    out = []
    for sd in 'lr':
        y = P[:, ch.bi[f'ball_{sd}'], 1] - ch.head[ch.bi[f'ball_{sd}']][1]; thr = 0.045 * ch.S
        for i in range(1, F):
            if y[i] < thr <= y[i - 1]: out.append(i / 30)
    return sorted(out)
def step(wet=0.0, heavy=1.0):
    n = int(0.11 * SR); t = np.arange(n) / SR; y = bp(R.randn(n), 180, 1500) * np.exp(-t * 55) * 0.7 + np.sin(2 * np.pi * 85 * t) * np.exp(-t * 60) * 0.5 * heavy
    if wet: w = hp(R.randn(int(0.2 * SR)), 2500) * np.exp(-np.arange(int(0.2 * SR)) / SR * 16) * wet; y = np.pad(y, (0, len(w) - n)) + w
    return y
FOOT = [('s01', 'hero10', 's01_hero', 0.34, 0, (3.0, 14)), ('s02', 'hero10', 's02_hero', 0.42, 0, None), ('s02', 'frA10', 's02_frA', 0.3, 0, None), ('s02', 'frB10', 's02_frB', 0.3, 0, None), ('s03', 'hero10', 's03_hero', 0.26, 0, None), ('s03', 'frA10', 's03_frA', 0.2, 0, None),
        ('s04', 'hero16', 's04_hero', 0.45, 0.5, None), ('s04', 'frA16', 's04_frA', 0.32, 0.5, None), ('s04', 'frB16', 's04_frB', 0.32, 0.5, None), ('s05', 'hero16', 's05_hero', 0.3, 0.1, None), ('s06', 'hero16', 's06_hero', 0.3, 0, None), ('s07', 'hero23', 's07_hero', 0.16, 0, None),
        ('s08', 'hero23', 's08_hero', 0.3, 0, None), ('s09', 'hero23', 's09_hero', 0.42, 0, None), ('s10', 'hero23', 's10_hero', 0.5, 0, None), ('s11', 'hero23', 's11_hero', 0.3, 0, None), ('s13', 'hero23', 's13_hero', 0.3, 0, None), ('s13', 'frA23', 's13_frA', 0.34, 0, None),
        ('s13', 'frB23', 's13_frB', 0.3, 0, None), ('s14', 'hero23', 's14_hero', 0.4, 0, (10.2, 16)), ('s14', 'frA23', 's14_frA', 0.4, 0, (10.2, 16)), ('s15', 'hero23', 's15_hero', 0.34, 0, (0, 5.5)), ('s15', 'hero10', 's15_kid', 0.16, 0, (17.5, 24)), ('s16b', 'hero23', 's16b_hero', 0.3, 0, (0, 9)), ('s16b', 'frA23', 's16b_frA', 0.26, 0, (0, 9)), ('s16', 'hero23', 's16_hero', 0.3, 0, None), ('s16', 'frA23', 's16_frA', 0.3, 0, None), ('s17', 'hero23', 's17_hero', 0.34, 0, None)]
shots = sorted(SHOT.items(), key=lambda kv: kv[1]); ends = {k: (shots[i + 1][1] if i + 1 < len(shots) else DUR) for i, (k, v) in enumerate(shots)}
for sh, cn, tr, g, wet, win in FOOT:
    for i, t in enumerate(strikes(cn, tr)):
        if t >= ends[sh] - SHOT[sh] or (win and not (win[0] <= t <= win[1])): continue
        putn(fx, step(wet, 0.8 if '10' in cn else 1.0), SHOT[sh] + t, g * (0.85 + 0.3 * R.rand()), 0.25 * (-1) ** i)
def amb(t0, t1, sig_fn, g, fade=1.0, raw=False):
    if not raw: t0, t1 = TM(t0), TM(t1)
    n = int((t1 - t0) * SR); s = sig_fn(n); e = np.ones(n); k = int(fade * SR); e[:k] = np.linspace(0, 1, k); e[-k:] = np.linspace(1, 0, k)
    s = s * e[:, None] if s.ndim == 2 else np.stack([s * e, s * e], 1); i = int(t0 * SR); fx[i:i + n] += s[:N - i] * g
def wind(n): return np.stack([lp(R.randn(n), 380) * (0.6 + 0.4 * np.sin(np.arange(n) / SR * 0.5 + p)) for p in (0, 1.3)], 1)
def rain(n): return np.stack([hp(lp(R.randn(n), 7000), 900) for _ in (0, 1)], 1) * 0.5 + np.stack([lp(R.randn(n), 300) for _ in (0, 1)], 1) * 0.4
def birds(n):
    y = np.zeros((n, 2)); t = 0.3
    while t < n / SR - 0.5:
        k = R.randint(2, 6); f0 = R.uniform(2400, 4800); pan = R.uniform(-0.8, 0.8)
        for j in range(k):
            c = sweep(R.uniform(0.05, 0.11), f0 * R.uniform(0.9, 1.1), f0 * R.uniform(1.05, 1.4), 0.01, 0.03) * 0.5; i = int((t + j * R.uniform(0.09, 0.16)) * SR)
            if i + len(c) < n: y[i:i + len(c), 0] += c * (1 - pan) / 2; y[i:i + len(c), 1] += c * (1 + pan) / 2
        t += R.uniform(0.7, 2.6)
    return y
def crickets(n): t = np.arange(n) / SR; c = np.sin(2 * np.pi * 4300 * t) * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 38 * t))) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.7 * t) ** 2); return np.stack([c, np.roll(c, 5000)], 1) * 0.5
def murmur(n): return np.stack([bp(R.randn(n), 250, 1100) * (0.55 + 0.45 * lp(np.abs(R.randn(n)), 6) * 8) for _ in (0, 1)], 1)
amb(0, 42, wind, 0.10); amb(0, 42, birds, 0.07); amb(42, 56, rain, 0.16); amb(42, 56, wind, 0.1); amb(56, 79, wind, 0.1); amb(56, 70, birds, 0.04); amb(79, 98, wind, 0.14, 2)
amb(98, 128, wind, 0.09); amb(128, 172, crickets, 0.028, 2); amb(128, 172, wind, 0.07); amb(145.2, 149.2, rain, 0.15, 1.2); amb(172, 215, murmur, 0.05, 1.5); amb(172, 215, crickets, 0.014, 2); amb(215, 223, birds, 0.07); amb(215, 223, wind, 0.09)
put(fx, lp(R.randn(int(3.5 * SR)), 90) * env(int(3.5 * SR), 0.4, 2.5) * 6, 46.5, 0.5)  # thunder far off
# the first barrier: motor, clunk, bell; sign flap; the road stretching away
put(fx, saws(31, 1.0, 0.02, 300) * 0.6, 116.6, 0.35); put(fx, step(0, 2) * 1.5, 117.5, 0.9);
for k in range(5): put(fx, (np.sin(2 * np.pi * 1320 * np.arange(int(0.5 * SR)) / SR) + 0.5 * np.sin(2 * np.pi * 1980 * np.arange(int(0.5 * SR)) / SR)) * np.exp(-np.arange(int(0.5 * SR)) / SR * 7), 117.7 + k * 0.42, 0.14, 0.4)
def flap(t): put(fx, clap() * 1.2, t, 0.5); put(fx, clap(), t + 0.07, 0.35)
flap(120.1); put(fx, sweep(4.6, 420, 70, 0.6, 1.2) * 0.5 + noise(4.6, 200, 900) * env(int(4.6 * SR), 1.5, 1.5) * 0.35, 121.9, 0.3)
flap(130.3); put(fx, noise(4.5, 300, 2400) * env(int(4.5 * SR), 0.5, 3.0) * 0.5, 131.0, 0.22, 0.6); put(fx, sweep(1.5, 880, 300, 0.05, 0.5), 131.3, 0.07, 0.5)
for i in range(24): put(fx, hat(0.02) * 2 + sweep(0.02, 2200, 1600, 0.001, 0.01)[:int(0.02 * SR)], 142.2 + i * 0.5, 0.11 if i % 2 == 0 else 0.07, 0.3)   # the clock of a year
for i in range(11): put(fx, clap() * 0.6, 143.0 + i, 0.16, -0.4)                                                                  # the stone repainted, month after month
for k, tt in enumerate(range(5, 0, -1)):   # last five seconds
    b = np.sin(2 * np.pi * (660 + 0 * k) * np.arange(int(0.12 * SR)) / SR) * env(int(0.12 * SR), 0.004, 0.04); put(fx, b, 172 + 10 - tt, 0.2)
b = np.sin(2 * np.pi * 1320 * np.arange(int(0.7 * SR)) / SR) * env(int(0.7 * SR), 0.004, 0.5); put(fx, b, T_OPEN, 0.22); put(fx, saws(33, 1.8, 0.02, 320) * 0.6, T_OPEN, 0.3)
def cheer(t, dur, g):
    n = int(dur * SR); c = np.stack([bp(R.randn(n), 600, 3200) * (0.7 + 0.3 * lp(np.abs(R.randn(n)), 9) * 9) for _ in (0, 1)], 1) * env(n, 0.5, dur * 0.6)[:, None]; i = int(TM(t) * SR); fx[i:i + n] += c[:N - i] * g
    for k in range(7): put(fx, sweep(R.uniform(0.25, 0.5), R.uniform(1500, 2200), R.uniform(2600, 3400), 0.03, 0.1), t + 0.3 + R.uniform(0, dur * 0.6), g * 0.5, R.uniform(-0.8, 0.8))
cheer(T_OPEN + 0.1, 6.0, 0.16); cheer(199.2, 9.0, 0.08)
def boom(t, g):
    put(fx, lp(R.randn(int(1.2 * SR)), 140) * np.exp(-np.arange(int(1.2 * SR)) / SR * 4) * 5, t, g, R.uniform(-0.5, 0.5))
    n = int(1.3 * SR); cr = hp(R.randn(n), 3500) * (R.rand(n) > 0.985) * np.exp(-np.arange(n) / SR * 2.2) * 6; put(fx, cr, t + 0.25, g * 0.5, R.uniform(-0.6, 0.6))
for t in (6.2, 9.3, 13.6, 16.6): boom(188 + t + 1.1, 0.2)   # (188 is carried to 220, where the goodbye begins)
r2 = np.random.RandomState(8)
for i in range(22): boom(199 + 1.6 + i * 0.62 + 0.9 + r2.rand() * 0.3, 0.2)
# ---- second cut: effects ----
def drop(t, g=0.2): n = int(0.16 * SR); tt = np.arange(n) / SR; putn(fx, np.sin(2 * np.pi * (900 + 1600 * np.exp(-tt * 30)) * tt) * np.exp(-tt * 28), t, g, R.uniform(-0.5, 0.5))
for t in (155.3, 156.9, 158.2, 159.1, 160.4, 161.0, 162.2): drop(t)
amb(154, 164, crickets, 0.022, 1.5, raw=True); amb(154, 164, wind, 0.06, 1.5, raw=True)
def whoosh(t, g=0.3): n = int(0.9 * SR); putn(fx, noise(0.9, 400, 5000) * np.sin(np.pi * np.arange(n) / n) ** 2, t - 0.45, g)
whoosh(164.0, 0.22); whoosh(172.5, 0.3); whoosh(179.0, 0.3); whoosh(185.6, 0.22)
def roomtone(n): return np.stack([lp(R.randn(n), 160) for _ in (0, 1)], 1) * 0.6 + np.stack([bp(R.randn(n), 7000, 9000) for _ in (0, 1)], 1) * 0.05
amb(164, 186, roomtone, 0.05, 1.0, raw=True)
for t in np.arange(165.2, 172.0, 0.31): putn(fx, hat(0.012) * 1.5, t + R.uniform(-0.05, 0.05), 0.05, 0.2)   # thumbs on buttons
for t in np.arange(173.2, 178.6, 0.36): putn(fx, hat(0.012) * 1.5, t + R.uniform(-0.05, 0.05), 0.04, 0.2)
def laugh(t, g=0.08, hi=1.0):   # children's voices as little rising chirps (no words)
    for k in range(R.randint(3, 6)): putn(fx, sweep(R.uniform(0.07, 0.13), 700 * hi * R.uniform(0.9, 1.2), 1100 * hi * R.uniform(0.9, 1.3), 0.01, 0.04), t + k * R.uniform(0.11, 0.16), g, R.uniform(-0.6, 0.6))
for t in (166.6, 168.3, 170.2, 171.0): laugh(t, 0.07, 1.25)
laugh(176.4, 0.05, 0.85)
amb(220, 244, crickets, 0.012, 2, raw=True); amb(244, 276, murmur, 0.045, 1.5, raw=True)
n = int(0.5 * SR); putn(fx, saws(31, 0.5, 0.02, 260) * np.linspace(1, 0, n) * 0.7, 240.3, 0.3); putn(fx, step(0, 2), 240.75, 0.5)   # the old board hums and goes out
for i in range(19): putn(fx, hat(0.02) * 2 + sweep(0.03, 1800 + 60 * i, 2400 + 60 * i, 0.001, 0.015)[:int(0.02 * SR)], 268.4 + i * 3.2 / 19, 0.16, 0.5 * (-1) ** i)   # letters come on
n = int(0.9 * SR); putn(fx, (saws(45, 0.9, 0.03, 2400) * 0.5 + noise(0.9, 2000, 8000) * 0.3) * np.exp(-np.arange(n) / SR * 3.5), 272.2, 0.35)
def boomn(t, g):
    putn(fx, lp(R.randn(int(1.2 * SR)), 140) * np.exp(-np.arange(int(1.2 * SR)) / SR * 4) * 5, t, g, R.uniform(-0.5, 0.5)); n = int(1.3 * SR); putn(fx, hp(R.randn(n), 3500) * (R.rand(n) > 0.985) * np.exp(-np.arange(n) / SR * 2.2) * 6, t + 0.25, g * 0.5, R.uniform(-0.6, 0.6))
r3 = np.random.RandomState(12)
for i in range(16): boomn(260 + 7.6 + i * 0.5 + 1.0 + r3.rand() * 0.3, 0.16)
i0 = int(272.3 * SR); n = int(5 * SR); c = np.stack([bp(R.randn(n), 600, 3200) * (0.7 + 0.3 * lp(np.abs(R.randn(n)), 9) * 9) for _ in (0, 1)], 1) * env(n, 0.5, 3.0)[:, None]; fx[i0:i0 + n] += c[:N - i0] * 0.1
ir2 = R.randn(int(1.2 * SR), 2) * np.exp(-np.arange(int(1.2 * SR)) / SR * 5)[:, None] * 0.02
fx = fx + np.stack([fftconvolve(fx[:, c], ir2[:, c])[:N] for c in (0, 1)], 1) * 0.5
# ---------------- mix ----------------
mix = mus * 0.9 + fx * 1.0
k = int(1.2 * SR); mix[:k] *= np.linspace(0, 1, k)[:, None]; k = int(1.6 * SR); mix[-k:] *= np.linspace(1, 0, k)[:, None] ** 1.5
k0, k1 = int(TM(212.8) * SR), int(TM(214.99) * SR + 0.01 * SR); mix[k0:k1] *= np.linspace(1, 0.25, k1 - k0)[:, None]; k2 = int(TM(216.0) * SR); mix[k1:k2] *= np.linspace(0.25, 1, k2 - k1)[:, None]
mix = hp(mix[:, 0], 28), hp(mix[:, 1], 28); mix = np.stack(mix, 1)
pk = np.abs(mix).max(); rms = np.sqrt((mix ** 2).mean()); print('peak %.2f rms %.3f' % (pk, rms))
mix = np.tanh(mix / (pk * 0.62)) * 0.84
wavfile.write(os.path.join(FILM, 'audio.wav'), SR, (mix * 32767).astype(np.int16)); print('audio written', mix.shape[0] / SR, 's')
