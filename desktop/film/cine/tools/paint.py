"""Painted textures, all made here pixel by pixel / stroke by stroke. Output: web/tex/*.png (straight alpha)."""
import numpy as np, cv2, os, sys
from cinepaths import TEX as T
def save(name, img):
    img = np.clip(img, 0, 255).astype(np.uint8)
    if img.shape[2] == 4: cv2.imwrite(T + name + '.png', cv2.cvtColor(img, cv2.COLOR_RGBA2BGRA))
    else: cv2.imwrite(T + name + '.png', cv2.cvtColor(img, cv2.COLOR_RGB2BGR))
def fbm(h, w, base=4, octs=5, seed=0, tile=True, gain=0.5):
    r = np.random.RandomState(seed); out = np.zeros((h, w), np.float32); amp = 1; tot = 0
    for o in range(octs):
        n = base * 2 ** o; g = r.rand(max(2, int(n * h / max(h, w)) or 2), max(2, int(n * w / max(h, w)) or 2)).astype(np.float32)
        if tile: g = np.pad(g, ((0, 3), (0, 3)), mode='wrap'); gh, gw = g.shape; big = cv2.resize(g, (int(w * gw / (gw - 3)), int(h * gh / (gh - 3))), interpolation=cv2.INTER_CUBIC)[:h, :w]
        else: big = cv2.resize(g, (w, h), interpolation=cv2.INTER_CUBIC)
        out += big * amp; tot += amp; amp *= gain
    out /= tot; return (out - out.min()) / (out.max() - out.min() + 1e-9)
def ramp(v, stops):
    xs = [s[0] for s in stops]; cs = np.array([s[1] for s in stops], np.float32)
    return np.stack([np.interp(v, xs, cs[:, i]) for i in range(3)], -1)
def ground():
    r = np.random.RandomState(3); S = 1024
    # grass seen from above: layered strokes
    n = fbm(S, S, 6, 5, 1); g = ramp(n, [(0, (58, 92, 40)), (0.45, (86, 124, 50)), (0.75, (120, 150, 62)), (1, (150, 168, 80))]).astype(np.float32)
    for k in range(26000):
        x, y = r.randint(0, S, 2); ln = r.randint(5, 16); a = r.normal(-1.57, 0.7); dx, dy = int(np.cos(a) * ln), int(np.sin(a) * ln)
        c = g[y, x] * r.uniform(0.72, 1.32) + r.normal(0, 6, 3); c[0] += r.uniform(-4, 14)
        for ox in (-S, 0, S):
            for oy in (-S, 0, S):
                if -20 < x + ox < S + 20 and -20 < y + oy < S + 20: cv2.line(g, (x + ox, y + oy), (x + ox + dx, y + oy + dy), tuple(float(v) for v in c), 1 if r.rand() < 0.6 else 2, cv2.LINE_AA)
    save('grass', cv2.GaussianBlur(g, (0, 0), 0.6))
    n = fbm(S, S, 5, 6, 2); n2 = fbm(S, S, 40, 3, 4); d = ramp(n * 0.75 + n2 * 0.25, [(0, (112, 84, 60)), (0.5, (150, 118, 84)), (1, (186, 156, 116))]).astype(np.float32)
    for k in range(900):
        x, y = r.randint(0, S, 2); rad = r.randint(1, 4); c = d[y, x] * r.uniform(0.6, 1.25)
        cv2.circle(d, (x, y), rad, tuple(float(v) for v in c), -1, cv2.LINE_AA)
    save('dirt', cv2.GaussianBlur(d, (0, 0), 0.5))
    n = fbm(S, S, 8, 6, 5); n2 = fbm(S, S, 90, 2, 6); a = ramp(n * 0.6 + n2 * 0.4, [(0, (58, 60, 68)), (0.5, (82, 84, 92)), (1, (112, 112, 116))]).astype(np.float32)
    yy = np.arange(S)[:, None] / S; wear = np.exp(-((np.abs(yy - 0.5) * 9 - 1.15) / 0.42) ** 2) * 10; a += wear * (0.6 + 0.8 * fbm(S, S, 3, 3, 7))[..., None]
    for k in range(14):  # cracks
        x, y = r.randint(0, S), r.randint(0, S); ang = r.uniform(0, 6.28)
        for st in range(r.randint(60, 200)):
            ang += r.normal(0, 0.35); x2, y2 = x + np.cos(ang) * 4, y + np.sin(ang) * 4
            cv2.line(a, (int(x) % S, int(y) % S), (int(x2) % S, int(y2) % S), (40, 40, 46), 1, cv2.LINE_AA) if abs(int(x) % S - int(x2) % S) < 10 and abs(int(y) % S - int(y2) % S) < 10 else None; x, y = x2, y2
    save('road', a)
    save('var', np.stack([fbm(512, 512, 3, 4, 11), fbm(512, 512, 4, 4, 12), fbm(512, 512, 5, 3, 13)], -1) * 255)

# ---------------- painted cards ----------------
def dabs(img, pts, rad, cols, r, squash=0.6, rot=None):
    for (x, y), c in zip(pts, cols):
        a = r.uniform(0, 180) if rot is None else rot + r.normal(0, 25); ra = max(1.5, rad * r.uniform(0.6, 1.3))
        cv2.ellipse(img, (int(x), int(y)), (int(ra), max(1, int(ra * squash))), a, 0, 360, (float(c[0]), float(c[1]), float(c[2]), 255.0), -1, cv2.LINE_AA)
def limb(img, p0, p1, w0, w1, col, lit):
    p0, p1 = np.array(p0, float), np.array(p1, float); d = p1 - p0; n = np.array([-d[1], d[0]]) / (np.linalg.norm(d) + 1e-9)
    poly = np.array([p0 + n * w0, p1 + n * w1, p1 - n * w1, p0 - n * w0]).astype(np.int32)
    cv2.fillPoly(img, [poly], (*[float(v) for v in col], 255.0), cv2.LINE_AA)
    poly2 = np.array([p0 - n * w0, p1 - n * w1, p1 - n * w1 * 0.2, p0 - n * w0 * 0.2]).astype(np.int32)  # lit side (left)
    cv2.fillPoly(img, [poly2], (*[float(v) for v in lit], 255.0), cv2.LINE_AA)
def branches(img, p, ang, ln, w, depth, r, col, lit, tips, spread=0.55, shrink=0.72):
    q = (p[0] + np.cos(ang) * ln, p[1] - np.sin(ang) * ln); limb(img, p, q, w, w * 0.7, col, lit)
    if depth == 0 or w < 1.2: tips.append(q); return
    for k in range(2 if r.rand() < 0.75 else 3):
        branches(img, q, ang + r.uniform(-spread, spread) + (0.25 if k == 0 else -0.25), ln * shrink * r.uniform(0.8, 1.1), w * 0.68, depth - 1, r, col, lit, tips, spread, shrink)
def foliage(img, centers, r, pal, size=1.0, dens=1.0, flower=None):
    """pal: dark, mid, light, hi. light comes from upper-left"""
    cs = sorted(centers, key=lambda c: -c[1] + c[0] * 0.3)
    for (x, y, rr) in cs:   # shadow masses
        n = int(46 * dens); pts = np.c_[r.normal(x, rr * 0.5, n), r.normal(y, rr * 0.42, n)]
        dabs(img, pts, 13 * size, [np.array(pal[0]) * r.uniform(0.8, 1.15) for _ in range(n)], r)
    for (x, y, rr) in cs:   # mid leaves, shifted to the light
        n = int(60 * dens); pts = np.c_[r.normal(x - rr * 0.12, rr * 0.42, n), r.normal(y - rr * 0.14, rr * 0.34, n)]
        dabs(img, pts, 8 * size, [np.array(pal[1]) * r.uniform(0.82, 1.18) + r.normal(0, 5, 3) for _ in range(n)], r)
    for (x, y, rr) in cs:   # lit tops
        n = int(34 * dens); pts = np.c_[r.normal(x - rr * 0.26, rr * 0.3, n), r.normal(y - rr * 0.3, rr * 0.22, n)]
        dabs(img, pts, 6 * size, [np.array(pal[2]) * r.uniform(0.85, 1.15) for _ in range(n)], r)
        n = int(9 * dens); pts = np.c_[r.normal(x - rr * 0.36, rr * 0.2, n), r.normal(y - rr * 0.4, rr * 0.14, n)]
        dabs(img, pts, 4.5 * size, [np.array(pal[3]) * r.uniform(0.9, 1.1) for _ in range(n)], r)
        if flower is not None:
            n = int(26 * dens); pts = np.c_[r.normal(x - rr * 0.1, rr * 0.4, n), r.normal(y - rr * 0.3, rr * 0.25, n)]
            dabs(img, pts, 5 * size, [np.array(flower) * r.uniform(0.8, 1.15) for _ in range(n)], r)
GREEN = [(34, 62, 38), (62, 104, 48), (112, 150, 62), (176, 196, 96)]
GREEN2 = [(30, 56, 44), (52, 92, 58), (94, 136, 74), (150, 182, 104)]
BARK = (74, 56, 46); BARKL = (128, 100, 78)
def tree(kind, seed, W=1024, H=1280):
    r = np.random.RandomState(seed); img = np.zeros((H, W, 4), np.float32); tips = []; base = (W // 2, H - 6)
    if kind == 'neem':
        branches(img, base, 1.57 + r.uniform(-0.08, 0.08), H * 0.25, 34, 4, r, BARK, BARKL, tips)
        cs = [(x + r.normal(0, 30), y + r.normal(0, 30), r.uniform(90, 140)) for (x, y) in tips] + [(W / 2 + r.normal(0, 190), H * 0.36 + r.normal(0, 110), r.uniform(110, 160)) for _ in range(20)]
        cs = [(np.clip(x, 160, W - 160), np.clip(y, 170, H * 0.60), rr) for x, y, rr in cs]; foliage(img, cs, r, GREEN if seed % 2 else GREEN2, 1.15, 1.25)
    elif kind == 'banyan':
        for k in range(5): branches(img, (W / 2 + (k - 2) * 26, H - 6), 1.57 + (2 - k) * 0.16 + r.uniform(-0.05, 0.05), H * 0.24, 30 - abs(k - 2) * 6, 3, r, BARK, BARKL, tips, 0.7, 0.8)
        cs = [(x + r.normal(0, 30), y + r.normal(0, 25), r.uniform(70, 110)) for (x, y) in tips] + [(W / 2 + r.uniform(-400, 400), H * 0.36 + r.normal(0, 60), r.uniform(80, 130)) for _ in range(14)]
        cs = [(np.clip(x, 110, W - 110), np.clip(y, 140, H * 0.55), rr) for x, y, rr in cs]
        for k in range(26):  # aerial roots
            x = W / 2 + r.uniform(-380, 380); y0 = H * 0.5 + r.uniform(-40, 60); y1 = y0 + r.uniform(150, H * 0.48 - 60)
            cv2.line(img, (int(x), int(y0)), (int(x + r.normal(0, 6)), int(min(y1, H - 4))), (*[float(v * r.uniform(0.8, 1.2)) for v in BARK], 255.0), int(r.choice([1, 2, 2, 3, 5])), cv2.LINE_AA)
        foliage(img, cs, r, GREEN2, 1.05, 1.0)
    elif kind == 'gulmohar':
        branches(img, base, 1.57 + r.uniform(-0.1, 0.1), H * 0.26, 20, 4, r, BARK, BARKL, tips, 0.8, 0.78)
        cs = [(np.clip(x + r.normal(0, 30), 120, W - 120), np.clip(y, 180, H * 0.55) + r.normal(0, 20), r.uniform(70, 110)) for (x, y) in tips] + [(W / 2 + r.uniform(-330, 330), H * 0.36 + r.normal(0, 40), r.uniform(80, 120)) for _ in range(8)]
        foliage(img, cs, r, GREEN, 0.9, 0.85, flower=(232, 78, 38))
    elif kind == 'bare':
        branches(img, base, 1.57 + r.uniform(-0.1, 0.1), H * 0.26, 18, 6, r, (58, 52, 56), (104, 98, 100), tips, 0.6, 0.76)
    elif kind == 'bush':
        cs = [(W / 2 + r.uniform(-300, 300), H - 150 + r.normal(0, 50), r.uniform(80, 130)) for _ in range(9)]; cs = [(x, min(y, H - 90), rr) for x, y, rr in cs]; foliage(img, cs, r, GREEN if seed % 2 else GREEN2, 1.0, 1.0)
    elif kind == 'palm':
        x, y = W / 2, H - 6; lean = r.uniform(-0.22, 0.22); pts = []
        for k in range(41):
            t = k / 40; pts.append((x + np.sin(t * 1.4) * lean * H * 0.5, y - t * H * 0.60))
        for k in range(40):
            w = 20 - 9 * k / 40; limb(img, pts[k], pts[k + 1], w, w - 0.2, np.array(BARK) * (0.9 + 0.2 * (k % 2)), BARKL)
        top = pts[-1]
        fr = sorted([(r.uniform(-0.5, 3.64), r.uniform(0.7, 1.0)) for _ in range(17)], key=lambda a: -abs(a[0] - 1.57))
        for i, (a, ln) in enumerate(fr):
            L = ln * W * 0.52; rib = []
            for k in range(25):
                t = k / 24; rib.append((top[0] + np.cos(a) * L * t, top[1] - np.sin(a) * L * t * (1 - 0.35 * t) + 0.62 * L * t * t))
            sh = 0.75 + 0.5 * (i / len(fr)); cb = np.array(GREEN[1]) * sh; cl = np.array(GREEN[2]) * sh
            for k in range(1, 25):
                t = k / 24; d = np.array(rib[k]) - np.array(rib[k - 1]); n = np.array([-d[1], d[0]]) / (np.linalg.norm(d) + 1e-9); ll = L * 0.2 * (1 - t * 0.75) * (0.4 + min(t * 4, 0.6))
                for sgn in (-1, 1):
                    e = np.array(rib[k]) + n * sgn * ll * 0.7 + np.array([d[0] * 0.8, abs(ll) * 0.75])
                    c = cl if (sgn * n[1] < 0) else cb
                    cv2.line(img, (int(rib[k][0]), int(rib[k][1])), (int(e[0]), int(e[1])), (*[float(v * r.uniform(0.85, 1.15)) for v in c], 255.0), 3, cv2.LINE_AA)
            cv2.polylines(img, [np.array(rib, np.int32)], False, (*[float(v) for v in np.array(GREEN[0]) * 1.1], 255.0), 3, cv2.LINE_AA)
        for k in range(5): cv2.circle(img, (int(top[0] + r.normal(0, 14)), int(top[1] + 14 + r.normal(0, 6))), 10, (52.0, 60.0, 34.0, 255.0), -1, cv2.LINE_AA)
    img[..., :3] = cv2.GaussianBlur(img[..., :3], (0, 0), 0.7); return img
def trees():
    for kind, seeds, size in (('neem', (1, 2, 3), (1024, 1280)), ('banyan', (4,), (1536, 1280)), ('gulmohar', (5,), (1024, 1100)), ('bare', (6, 7), (1024, 1280)), ('bush', (8, 9), (1024, 512)), ('palm', (10, 11), (1200, 1500))):
        for i, s in enumerate(seeds): save(f'{kind}{i}', tree(kind, s, *size))
def grass_atlas():
    r = np.random.RandomState(21); C = 512; A = np.zeros((C, 4 * C, 4), np.float32)
    for k in range(4):
        x0 = k * C; n = [70, 46, 90, 60][k]
        for b in range(n):
            bx = x0 + C / 2 + r.normal(0, 0.18 * C); h = r.uniform(0.35, 0.92) * C * (0.75 if k == 2 else 1); lean = r.normal(0, 0.35); w = r.uniform(0.010, 0.0215) * C
            pts_l = []; pts_r = []
            for s in range(13):
                t = s / 12; x = bx + lean * h * t * t; y = C - 2 - h * t; ww = w * (1 - t) ** 0.8
                pts_l.append((x - ww, y)); pts_r.append((x + ww, y))
            x = np.clip(np.array(pts_l + pts_r[::-1]), [x0 + 3, 0], [x0 + C - 4, C - 1]).astype(np.int32)
            sh = r.uniform(0.55, 1.2); c = np.array([78, 128, 48]) * sh + r.normal(0, 8, 3); c[0] += r.uniform(0, 45) * (k == 1 or k == 3)
            cv2.fillPoly(A, [x], (float(c[0]), float(c[1]), float(c[2]), 255.0), cv2.LINE_AA)
        if k == 3:
            for f in range(9):
                fx, fy = x0 + C / 2 + r.normal(0, 0.2 * C), C - 1 - r.uniform(0.47, 0.84) * C; cv2.circle(A, (int(fx), int(fy)), 12, (250.0, 214.0, 64.0, 255.0), -1, cv2.LINE_AA); cv2.circle(A, (int(fx), int(fy)), 4, (200.0, 120.0, 30.0, 255.0), -1, cv2.LINE_AA)
    save('grassA', A)
def clouds():
    for i in range(6):
        W, H = 1024, 512; n = fbm(H, W, 3, 6, 100 + i, tile=False); n2 = fbm(H, W, 12, 4, 200 + i, tile=False)
        yy, xx = np.mgrid[0:H, 0:W].astype(np.float32); ex = ((xx - W / 2) / (W * 0.47)) ** 2 + ((yy - H * 0.56) / (H * 0.40)) ** 2
        flat = np.clip((yy - H * 0.60) / (H * 0.2), 0, 1)  # flat-ish base
        d = n * 1.1 + n2 * 0.22 - ex * 0.62 - flat * 0.45
        a = np.clip((d - 0.20) / 0.2, 0, 1); a = a * a * (3 - 2 * a)
        lit = np.clip(1.35 - (yy / H) * 1.45 + (n2 - 0.5) * 0.55 + (n - 0.5) * 0.45 - (xx / W - 0.5) * 0.2, 0, 1)
        lit = lit * lit * (3 - 2 * lit)
        img = np.zeros((H, W, 4), np.float32); img[..., 0] = img[..., 1] = img[..., 2] = lit * 255; img[..., 3] = a * 255
        save(f'cloud{i}', img)
    # soft mist bank and a round glow
    W, H = 1024, 256; n = fbm(H, W, 4, 5, 300, tile=False); yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    a = np.clip(n * 1.6 - 0.35 - ((yy - H / 2) / (H * 0.42)) ** 2 - ((xx - W / 2) / (W * 0.5)) ** 4, 0, 1)
    img = np.zeros((H, W, 4), np.float32); img[..., :3] = 255; img[..., 3] = a * 255; save('mist', img)
    yy, xx = np.mgrid[0:256, 0:256].astype(np.float32); d = np.sqrt((xx - 128) ** 2 + (yy - 128) ** 2) / 128
    g = np.clip(1 - d, 0, 1) ** 2.2; img = np.zeros((256, 256, 4), np.float32); img[..., :3] = 255; img[..., 3] = g * 255; save('glow', img)
    ring = np.clip(1 - np.abs(d - 0.8) / 0.12, 0, 1); img = np.zeros((256, 256, 4), np.float32); img[..., :3] = 255; img[..., 3] = ring * 255; save('ring', img)
def ridge(W, seed, base, amp, rough=5):
    n = fbm(8, W, rough, 6, seed, tile=True)[0]; n2 = fbm(8, W, rough * 9, 3, seed + 1, tile=True)[0]; return base + amp * (n - 0.5) * 2 + amp * 0.12 * (n2 - 0.5)
def hills():
    W, H = 4096, 512; yy = np.arange(H)[:, None].astype(np.float32)
    for v, (cols, peaks) in enumerate(((((150, 168, 190), (120, 144, 150), (92, 122, 104)), (0.30, 0.48, 0.66)), (((168, 176, 196), (140, 150, 170), (110, 128, 136)), (0.18, 0.40, 0.62)))):
        img = np.zeros((H, W, 4), np.float32)
        for k, (c, pk) in enumerate(zip(cols, peaks)):
            rd = ridge(W, 40 + v * 10 + k, H * pk, H * (0.16 - 0.03 * k), 4 + k * 2)[None, :]
            m = np.clip((yy - rd) / 2.5, 0, 1); tex = fbm(H, W, 30, 3, 60 + k)[..., None]
            col = np.array(c, np.float32) * (0.92 + 0.16 * tex) * (1 + 0.16 * np.clip(1 - (yy - rd) / 90, 0, 1))[..., None]  # lighter crest, mist pools below
            img[..., :3] = img[..., :3] * (1 - m[..., None]) + col * m[..., None]; img[..., 3] = np.maximum(img[..., 3], m * 255)
        save(f'hills{v}', img)
    # distant tree line
    r = np.random.RandomState(5); H = 384; img = np.zeros((H, W, 4), np.float32); x = 0
    while x < W:
        w = r.uniform(60, 190); h = r.uniform(110, 300) * (1.0 if r.rand() < 0.8 else 1.3); cx = x + w / 2; n = int(w * h / 260)
        pts = np.c_[r.normal(cx, w * 0.3, n), H - r.uniform(0, 1, n) ** 0.7 * h]
        dabs(img, pts, 20, [np.array((58, 88, 62)) * r.uniform(0.75, 1.1) for _ in range(n)], r)
        n2 = n // 2; pts = np.c_[r.normal(cx - w * 0.1, w * 0.26, n2), H - r.uniform(0.35, 1, n2) * h]
        dabs(img, pts, 13, [np.array((96, 128, 80)) * r.uniform(0.8, 1.15) for _ in range(n2)], r)
        if r.rand() < 0.16:  # a palm rising out
            px = cx + r.uniform(-20, 20); ph = h + r.uniform(40, 80); cv2.line(img, (int(px), H), (int(px + 8), int(H - ph)), (70.0, 62.0, 52.0, 255.0), 4, cv2.LINE_AA)
            for a in np.linspace(-0.4, 3.5, 9): cv2.line(img, (int(px + 8), int(H - ph)), (int(px + 8 + np.cos(a) * 46), int(H - ph - np.sin(a) * 30 + 22)), (54.0, 86.0, 52.0, 255.0), 4, cv2.LINE_AA)
        x += w * r.uniform(0.45, 0.9)
    img[..., :3] = cv2.GaussianBlur(img[..., :3], (0, 0), 1.2); save('treeline', img)

def city():
    """the far shore: towers, lit windows, palms. alpha silhouette, windows bright"""
    r = np.random.RandomState(77); W, H = 4096, 768; img = np.zeros((H, W, 4), np.float32); x = 0
    while x < W:
        w = int(r.uniform(40, 150)); hh = int(r.uniform(120, 420) * (1.5 if r.rand() < 0.14 else 1) * (0.55 + 0.45 * np.exp(-((x - W / 2) / (W * 0.33)) ** 2)))
        base = np.array([40, 44, 78]) * r.uniform(0.7, 1.25); y0 = H - hh
        img[y0:H, x:x + w, :3] = base; img[y0:H, x:x + w, 3] = 255
        if r.rand() < 0.3: tw = w // 3; th = int(hh * 0.18); img[y0 - th:y0, x + tw:x + 2 * tw, :3] = base * 0.9; img[y0 - th:y0, x + tw:x + 2 * tw, 3] = 255
        if r.rand() < 0.15: cv2.line(img, (x + w // 2, y0), (x + w // 2, y0 - int(r.uniform(30, 80))), (60.0, 60.0, 90.0, 255.0), 2)
        cw, chh = r.choice([7, 9, 12]), r.choice([9, 12, 16]); wc = [np.array(c) for c in ((255, 214, 150), (255, 170, 190), (150, 226, 255), (255, 240, 210))]; tint = wc[r.randint(4)]
        for yy in range(y0 + 8, H - 6, chh):
            for xx in range(x + 5, x + w - cw, cw):
                if r.rand() < 0.42: img[yy:yy + chh - 5, xx:xx + cw - 3, :3] = tint * r.uniform(0.6, 1.0)
        x += w + int(r.uniform(-6, 26))
    for k in range(46):  # palms along the shore
        px = int(r.uniform(0, W)); ph = r.uniform(60, 150)
        cv2.line(img, (px, H), (px + int(r.uniform(-12, 12)), int(H - ph)), (18.0, 20.0, 40.0, 255.0), 4, cv2.LINE_AA)
        for a in np.linspace(-0.5, 3.6, 10): cv2.line(img, (px, int(H - ph)), (int(px + np.cos(a) * 44), int(H - ph - np.sin(a) * 26 + 20)), (16.0, 22.0, 40.0, 255.0), 4, cv2.LINE_AA)
    save('city', img)

if __name__ == '__main__':
    for f in (sys.argv[1:] or ['ground']): globals()[f]()
