# textures.py — every surface texture of the school ground, made from noise (no photos): earth, plaster, bark, leaves
import numpy as np, scipy.ndimage as nd
from PIL import Image, ImageDraw, ImageFilter
R = np.random.default_rng(7); OUT = __import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)), '..', 'web', 'codescape', 'tex') + '/'
def fbm(n, oct=7, base=4, gain=0.55, seed=0, tile=False):
    r = np.random.default_rng(seed); out = np.zeros((n, n)); a = 1.0; tot = 0
    for o in range(oct):
        k = base * 2 ** o
        if k > n: break
        g = r.standard_normal((k, k))
        if tile: g = np.pad(g, 2, mode='wrap'); up = nd.zoom(g, (n + 4 * n / k) / (k + 4), order=3); s = int(2 * n / k); up = up[s:s + n, s:s + n]
        else: up = nd.zoom(g, n / k, order=3)[:n, :n]
        out += a * up; tot += a; a *= gain
    out /= tot; return (out - out.mean()) / (out.std() + 1e-9)
def save(a, name, q=90):
    Image.fromarray(np.clip(a * 255, 0, 255).astype(np.uint8)).save(OUT + name, quality=q); print(name, a.shape)
def col(h): h = h.lstrip('#'); return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)]) / 255.0
# ---------------- the school ground: dry packed earth, 16 m across (2048 px = 7.8 mm a pixel)
N = 2048; big = fbm(N, 6, 3, 0.6, 1); mid = fbm(N, 7, 16, 0.5, 2); fine = fbm(N, 4, 256, 0.6, 3)
C = col('#b49470')[None, None] * np.ones((N, N, 3))
C = C * (1 + 0.07 * big[..., None]) + (col('#8f5f3c') - col('#b49470')) * np.clip(0.5 + 0.35 * mid, 0, 1)[..., None] * 0.35   # reddish patches
damp = np.clip((fbm(N, 5, 5, 0.5, 4) - 0.9) * 0.9, 0, 1); C = C * (1 - 0.18 * damp[..., None])
C = C * (1 + 0.05 * fine[..., None])
H = 0.5 + 0.12 * big + 0.1 * mid + 0.05 * fine
# pebbles and grit: small rounded stones with their own tone, a little raised
yy, xx = np.mgrid[0:N, 0:N]
for i in range(9000):
    x, y = R.integers(0, N, 2); r = R.uniform(1.0, 4.5) if R.uniform() < 0.9 else R.uniform(4, 9)
    x0, x1, y0, y1 = max(0, int(x - r - 2)), min(N, int(x + r + 3)), max(0, int(y - r - 2)), min(N, int(y + r + 3))
    d = np.sqrt((xx[y0:y1, x0:x1] - x) ** 2 + ((yy[y0:y1, x0:x1] - y) * R.uniform(0.7, 1.3)) ** 2) / r; m = np.clip(1 - d, 0, 1) ** 0.6
    tone = col(['#c2ae8e', '#9b8366', '#6e5a48', '#b09a78', '#a08a6a'][R.integers(0, 5)])
    C[y0:y1, x0:x1] = C[y0:y1, x0:x1] * (1 - m[..., None] * 0.85) + tone * m[..., None] * 0.85; H[y0:y1, x0:x1] += m * 0.18 * (r / 4) ** 0.5
# scuffs and shoe marks where children stand in lines (darker, smoother)
for i in range(260):
    x, y = R.uniform(0.2, 0.8) * N, R.uniform(0.35, 0.65) * N; a = R.uniform(0, np.pi); L = R.uniform(15, 45); w = R.uniform(5, 11)
    d = np.abs((xx - x) * np.cos(a) + (yy - y) * np.sin(a)); e = np.abs(-(xx - x) * np.sin(a) + (yy - y) * np.cos(a))
    if True:
        sl = (slice(max(0, int(y - L - 12)), min(N, int(y + L + 12))), slice(max(0, int(x - L - 12)), min(N, int(x + L + 12))))
        m = np.clip(1 - d[sl] / w, 0, 1) * np.clip(1 - e[sl] / L, 0, 1); m = m ** 1.5 * R.uniform(0.15, 0.35)
        C[sl] *= (1 - m[..., None] * 0.6); H[sl] -= m * 0.25
# lime chalk lines of the assembly rows (broken, rubbed)
img = Image.new('L', (N, N), 0); dr = ImageDraw.Draw(img)
for k in range(-1, 2):
    y = N * 0.5 + k * 0.85 / 16 * N * 1.0; xs = np.linspace(0.06 * N, 0.94 * N, 60)
    for a, b in zip(xs[:-1], xs[1:]):
        if R.uniform() < 0.6: dr.line([(a, y + R.normal(0, 1.5)), (b, y + R.normal(0, 1.5))], fill=int(R.uniform(40, 110)), width=int(R.uniform(4, 7)))
lime = np.asarray(img.filter(ImageFilter.GaussianBlur(1.6)), np.float32) / 255 * np.clip(0.6 + 0.5 * fine, 0, 1)
C = C * (1 - lime[..., None]) + col('#efe9dc') * lime[..., None]
# dry fallen leaves (neem / peepal), scattered more toward the edges
for i in range(900):
    x, y = R.integers(0, N, 2); edge = max(abs(x / N - 0.5), abs(y / N - 0.5))
    if R.uniform() > 0.25 + 1.6 * edge: continue
    r = R.uniform(4, 10); a = R.uniform(0, np.pi)
    sl = (slice(max(0, y - 14), min(N, y + 15)), slice(max(0, x - 14), min(N, x + 15)))
    X = xx[sl] - x; Y = yy[sl] - y; u = X * np.cos(a) + Y * np.sin(a); v = -X * np.sin(a) + Y * np.cos(a)
    m = np.clip(1 - (u / r) ** 2 - (v / (r * 0.42)) ** 2, 0, 1) ** 0.4
    tone = col(['#a87a3a', '#8a5a2a', '#c2a060', '#7a6a38'][R.integers(0, 4)])
    C[sl] = C[sl] * (1 - m[..., None] * 0.9) + tone * m[..., None] * 0.9; H[sl] += m * 0.05
H = nd.gaussian_filter(H, 0.8)
save(np.clip(C, 0, 1), 'ground.jpg'); save(np.clip((H - H.min()) / (H.max() - H.min()), 0, 1), 'ground_h.jpg', 88)
# outer ground: a tiling patch (4 m), same earth with sparse dry grass marks
N = 1024; b = fbm(N, 6, 4, 0.55, 11, tile=True); m2 = fbm(N, 6, 16, 0.5, 12, tile=True); f2 = fbm(N, 3, 256, 0.6, 13, tile=True)
C = col('#ad8d68') * np.ones((N, N, 3)); C = C * (1 + 0.08 * b[..., None] + 0.05 * f2[..., None]) + (col('#7d7a45') - col('#ad8d68')) * np.clip(0.4 * m2 - 0.2, 0, 1)[..., None]
save(np.clip(C, 0, 1), 'earth_tile.jpg'); save(np.clip(0.5 + 0.15 * b + 0.12 * m2 + 0.06 * f2, 0, 1), 'earth_tile_h.jpg', 85)
# ---------------- plaster: painted wall, weathered (tiling across, 2 m tall, 4 m wide)
W, Hh = 2048, 1024; p1 = fbm(W, 6, 8, 0.55, 21, tile=True)[:Hh]; p2 = fbm(W, 4, 128, 0.6, 22, tile=True)[:Hh]
yv = np.linspace(0, 1, Hh)[:, None]
grime = np.clip((1 - yv) ** 3 * 0.6 + 0.12 * fbm(W, 5, 6, 0.5, 23, tile=True)[:Hh], 0, 1) * 0
streak = np.clip(nd.gaussian_filter(R.standard_normal((Hh, W)) * (R.uniform(size=(1, W)) > 0.985), (60, 1.2)) * 40, 0, 1) * (yv < 0.55)
Pl = np.clip(0.5 + 0.18 * p1 + 0.12 * p2, 0, 1)
save(Pl, 'plaster_h.jpg', 88)
p3 = fbm(W, 5, 32, 0.5, 24, tile=True)[:Hh]
mask = np.clip(1.0 - 0.02 * p1[..., None] - 0.018 * p3[..., None] - 0.1 * streak[..., None] * (1 - yv[..., None]) - 0.03 * p2[..., None], 0, 1)
dirt = (1 - np.clip((yv - 0.78) / 0.22, 0, 1) * 0.0)
Image.fromarray(np.clip(mask[..., 0] * 255, 0, 255).astype(np.uint8)).save(OUT + 'plaster_ao.jpg', quality=88); print('plaster_ao.jpg')
# ---------------- bark
N = 512; bx = fbm(N, 6, 4, 0.6, 31, tile=True); ridges = np.abs(np.sin(np.linspace(0, 18 * np.pi, N)[None, :] + 2.5 * bx)) ** 0.5
B = col('#5e4a3a') * np.ones((N, N, 3)) * (0.75 + 0.35 * ridges[..., None]) * (1 + 0.1 * bx[..., None])
save(np.clip(B, 0, 1), 'bark.jpg'); save(np.clip(0.3 + 0.6 * ridges + 0.1 * bx, 0, 1), 'bark_h.jpg')
