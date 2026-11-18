"""checks on the finished video: camera steadiness on background bands, flashes, cuts"""
import cv2, numpy as np, sys
v = cv2.VideoCapture(sys.argv[1]); fps = v.get(cv2.CAP_PROP_FPS); n = int(v.get(cv2.CAP_PROP_FRAME_COUNT)); print('frames', n, 'fps', fps)
CUTS = [0, 14, 28, 42, 56, 70, 79, 88, 98, 114, 128, 142, 154, 172, 188, 199, 215, 223]; INNER = [120.9, 176.6, 182.25, 193.7]
prev = None; mean = []; shifts = []; i = 0; pg = None; dif = []
while True:
    ok, fr = v.read()
    if not ok: break
    g = cv2.cvtColor(cv2.resize(fr, (640, 360), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY).astype(np.float32); mean.append(g.mean())
    dif.append(0.0 if pg is None else float(np.abs(g - pg).mean())); pg = g
    band = g[20:120]  # upper band: sky and far land, where only the camera moves
    if prev is not None:
        (dx, dy), resp = cv2.phaseCorrelate(prev, band, cv2.createHanningWindow((640, 100), cv2.CV_32F)); shifts.append((dx, dy, resp))
    else: shifts.append((0, 0, 0))
    prev = band; i += 1
mean = np.array(mean); sh = np.array(shifts); t = np.arange(len(mean)) / fps
iscut = np.zeros(len(mean), bool)
for c in CUTS + INNER: iscut[max(0, int(round(c * fps)) - 2):int(round(c * fps)) + 3] = True
d = np.abs(np.diff(mean, prepend=mean[0])); fl = np.where((d > 9) & ~iscut)[0]
print('flashes (brightness jumps away from cuts):', len(fl), [round(float(t[k]), 2) for k in fl[:12]])
# jitter: frame-to-frame change of the camera's own motion (acceleration), in 2K pixels
acc = np.diff(sh[:, :2], axis=0, prepend=sh[:1, :2]) * 4; good = (~iscut) & (sh[:, 2] > 0.15); good[1:] &= ~iscut[:-1]
a = np.linalg.norm(acc, axis=1)
for a0, a1 in zip(CUTS[:-1], CUTS[1:]):
    m = good & (t >= a0 + 0.2) & (t < a1 - 0.2)
    if m.sum() > 5: print(f'  {a0:5.0f}-{a1:<5.0f} camera jerk  median {np.median(a[m]):.2f} px  p95 {np.percentile(a[m], 95):.2f} px  max {a[m].max():.1f} px  (speed max {np.abs(sh[m, :2]).max() * 4:.1f} px/frame)')
print('black frames:', int((mean < 3).sum()), ' mean brightness per shot:', [int(mean[(t >= a0) & (t < a1)].mean()) for a0, a1 in zip(CUTS[:-1], CUTS[1:])])

# joins between separately rendered 5-second pieces: the change across a join against the usual change around it
dif = np.array(dif); DUR = [14, 14, 14, 14, 14, 9, 9, 10, 16, 14, 14, 12, 18, 16, 11, 16, 8]; base = 0; bad = []; worst = 0
for d in DUR:
    for k in range(150, d * 30, 150):
        j = base + k; around = np.r_[dif[j - 6:j - 1], dif[j + 2:j + 7]]; r = dif[j] / (np.median(around) + 1e-6); worst = max(worst, r)
        if r > 1.8 and dif[j] > 0.8: bad.append((round(j / fps, 2), round(float(r), 2), round(float(dif[j]), 2)))
    base += d * 30
print('piece joins: worst ratio %.2f; suspicious joins:' % worst, bad)
