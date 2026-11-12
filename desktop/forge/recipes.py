# Recipes of the forge: each builds one kind of thing from a few numbers. Colours are '#rrggbb'. Sizes in metres.
import bpy, bmesh, math, random
from mathutils import Vector, Matrix
import forge_lib as F

def _rnd(P): return random.Random(int(P.get('seed', 1)))

def rock(P):
    """A boulder. size (1.2), seed, color, lumps (0.35), flat (0.75 = squashed)."""
    s = P.get('size', 1.2); bm = bmesh.new()
    F.ball(bm, (0, 0, 0), s / 2, sub=2, squash=(1, 0.85 + 0.3 * _rnd(P).random(), P.get('flat', 0.75)), rough=P.get('lumps', 0.35), seed=P.get('seed', 1))
    F.obj('rock', bm, F.mat(P.get('color', '#7d828a'), 0.95))

def rocks(P):
    """A cluster of boulders. count (5), spread (2.5), size (1), seed, color."""
    r = _rnd(P); bm = bmesh.new()
    for i in range(int(P.get('count', 5))):
        a = r.random() * math.tau; d = r.random() ** 0.7 * P.get('spread', 2.5); s = P.get('size', 1.0) * (0.35 + r.random() * 0.9)
        F.ball(bm, (math.cos(a) * d, math.sin(a) * d, s * 0.25), s / 2, sub=2, squash=(1, 0.8 + 0.4 * r.random(), 0.7), rough=0.35, seed=i + P.get('seed', 1) * 7)
    F.obj('rocks', bm, F.mat(P.get('color', '#7d828a'), 0.95))

def tree(P):
    """A tree. kind: round | pine | palm | dead | blossom; height (6), seed, leaf, trunk colours."""
    r = _rnd(P); h = P.get('height', 6.0); kind = P.get('kind', 'round'); bm = bmesh.new()
    leaf = P.get('leaf', {'round': '#4f9440', 'pine': '#2f6a4a', 'palm': '#4a9a4a', 'blossom': '#f0a8c0'}.get(kind, '#4f9440'))
    mats = [F.mat(P.get('trunk', '#5a4332'), 0.95), F.mat(leaf, 0.9), F.mat(P.get('leaf2', leaf), 0.9)]
    th = h * (0.9 if kind in ('palm', 'dead') else 0.45 if kind == 'pine' else 0.5)
    lean = Matrix.Rotation((r.random() - 0.5) * (0.35 if kind == 'palm' else 0.12), 4, 'X') @ Matrix.Rotation((r.random() - 0.5) * 0.12, 4, 'Y')
    F.cyl(bm, (0, 0, 0), h * 0.045, h * 0.022, th, seg=8, mi=0, tilt=lean)
    top = lean @ Vector((0, 0, th))
    if kind == 'pine':
        for i in range(4):
            z = h * (0.22 + i * 0.19); F.cyl(bm, (0, 0, z), h * (0.26 - i * 0.05), 0.0, h * 0.3, seg=8, mi=1 + i % 2)
    elif kind == 'palm':
        for i in range(8):
            a = i / 8 * math.tau + r.random() * 0.3; L = h * 0.42
            for k in range(4):
                t0 = k / 4; d0 = t0 * L; z0 = top.z + math.sin(t0 * 2.2) * L * 0.25 - t0 * t0 * L * 0.55
                F.box(bm, (top.x + math.cos(a) * (d0 + L / 8), top.y + math.sin(a) * (d0 + L / 8), z0), (L / 4, h * 0.05 * (1 - t0 * 0.6), 0.03), 1, rot_z=a)
    elif kind == 'dead':
        for i in range(6):
            a = r.random() * math.tau; z = th * (0.45 + r.random() * 0.5)
            F.cyl(bm, (0, 0, z), h * 0.016, h * 0.004, h * (0.2 + r.random() * 0.2), seg=5, mi=0, tilt=Matrix.Rotation(a, 4, 'Z') @ Matrix.Rotation(0.7 + r.random() * 0.5, 4, 'X'))
    else:
        for i in range(int(P.get('puffs', 7))):
            a = r.random() * math.tau; d = r.random() ** 0.6 * h * 0.22; rad = h * (0.16 + r.random() * 0.1)
            F.ball(bm, (top.x + math.cos(a) * d, top.y + math.sin(a) * d, top.z + (r.random() - 0.2) * h * 0.22), rad, sub=1, mi=1 + i % 2, squash=(1, 1, 0.85), rough=0.12, seed=i)
    F.obj('tree', bm, mats)

def bush(P):
    """A bush. size (1.2), seed, leaf colour, berries colour (optional)."""
    r = _rnd(P); s = P.get('size', 1.2); bm = bmesh.new(); mats = [F.mat(P.get('leaf', '#4f9440'), 0.9), F.mat(P.get('berries', '#c0392b'), 0.6)]
    for i in range(6):
        a = r.random() * math.tau; d = r.random() * s * 0.3
        F.ball(bm, (math.cos(a) * d, math.sin(a) * d, s * (0.25 + r.random() * 0.2)), s * (0.24 + r.random() * 0.12), sub=1, mi=0, rough=0.15, seed=i)
    if P.get('berries'):
        for i in range(14):
            a = r.random() * math.tau; e = r.random() * 1.2
            F.ball(bm, (math.cos(a) * math.cos(e) * s * 0.45, math.sin(a) * math.cos(e) * s * 0.45, s * 0.35 + math.sin(e) * s * 0.3), s * 0.035, sub=1, mi=1)
    F.obj('bush', bm, mats)

def terrain(P):
    """A piece of land. size (40), height (6), seed, res (48), grass / rock / snow / sand colours, snowline (0.75 of the height)."""
    n = int(P.get('res', 48)); S = P.get('size', 40.0); H = P.get('height', 6.0); seed = P.get('seed', 1); bm = bmesh.new()
    vs = [[bm.verts.new(((i / n - 0.5) * S, (j / n - 0.5) * S, 0)) for i in range(n + 1)] for j in range(n + 1)]
    for row in vs:
        for v in row:
            edge = max(abs(v.co.x), abs(v.co.y)) / (S / 2)
            v.co.z = max(0.0, (F.fbm(v.co.x * 2.2 / S, v.co.y * 2.2 / S, seed=seed) + 0.35) * H * (1 - edge ** 3) * (0.35 + 0.65 * P.get('rough', 1.0)))
    snow = P.get('snowline', 0.75) * H
    for j in range(n):
        for i in range(n):
            f = bm.faces.new((vs[j][i], vs[j][i + 1], vs[j + 1][i + 1], vs[j + 1][i]))
            f.normal_update(); z = f.calc_center_median().z
            f.material_index = 3 if z < H * 0.03 and P.get('sand') else 2 if z > snow else 1 if f.normal.z < 0.82 else 0
    F.obj('terrain', bm, [F.mat(P.get('grass', '#6a9a4a'), 0.95), F.mat(P.get('rock', '#7d7a74'), 0.95), F.mat(P.get('snow', '#f1f4fa'), 0.8), F.mat(P.get('sand', '#d9c48c') or '#d9c48c', 0.95)])

def mountain(P):
    """A single peak. height (30), radius (28), seed, rock / snow colours."""
    terrain({'size': P.get('radius', 28) * 2, 'height': P.get('height', 30), 'seed': P.get('seed', 3), 'res': P.get('res', 40), 'grass': P.get('rock', '#7d7a74'), 'rock': P.get('rock2', '#66635e'), 'snow': P.get('snow', '#f1f4fa'), 'snowline': P.get('snowline', 0.6)})

def building(P):
    """A building. style: block | house | tower | shop; floors (4), width (10), depth (8), wall / roof / window colours, lit (windows glow)."""
    st = P.get('style', 'block'); fl = int(P.get('floors', 2 if st in ('house', 'shop') else 8 if st == 'tower' else 4)); W = P.get('width', 6 if st == 'tower' else 10.0); D = P.get('depth', W if st == 'tower' else 8.0); fh = P.get('floor_height', 3.1); H = fl * fh
    glass = F.mat(P.get('window', '#5d7388'), 0.3, 0.2, emit=P.get('window_glow', '#ffd98a') if P.get('lit') else None, strength=1.2)
    mats = [F.mat(P.get('wall', '#b9b2a6'), 0.9), glass, F.mat(P.get('roof', '#5a5e66'), 0.85), F.mat(P.get('door', '#4a3a30'), 0.8), F.mat(P.get('trim', '#8a8c90'), 0.9)]
    bm = bmesh.new(); F.box(bm, (0, 0, H / 2), (W, D, H), 0)
    cols = max(2, int(W / 2.4)); rows = max(2, int(D / 2.4))
    for f in range(fl):
        z = f * fh + fh * 0.55
        for side, n, L in ((1, cols, W), (-1, cols, W)):
            for i in range(n):
                x = (i + 0.5) / n * L - L / 2
                if f == 0 and side == 1 and i == n // 2: F.box(bm, (x, -D / 2 - 0.02, 1.05), (1.1, 0.06, 2.1), 3); continue
                F.box(bm, (x, -side * (D / 2 + 0.02), z), (L / n * 0.55, 0.06, fh * 0.45), 1)
        for side in (1, -1):
            for i in range(rows):
                y = (i + 0.5) / rows * D - D / 2; F.box(bm, (side * (W / 2 + 0.02), y, z), (0.06, D / rows * 0.55, fh * 0.45), 1)
    if st in ('house', 'shop'):
        rise = P.get('roof_rise', 2.2); v = [bm.verts.new(p) for p in ((-W / 2 - 0.4, -D / 2 - 0.4, H), (W / 2 + 0.4, -D / 2 - 0.4, H), (W / 2 + 0.4, D / 2 + 0.4, H), (-W / 2 - 0.4, D / 2 + 0.4, H), (-W / 2 - 0.4, 0, H + rise), (W / 2 + 0.4, 0, H + rise))]
        for q in ((0, 1, 5, 4), (2, 3, 4, 5), (0, 4, 3), (1, 2, 5)): bm.faces.new([v[i] for i in q]).material_index = 2
        if st == 'shop': F.box(bm, (0, -D / 2 - 0.7, fh * 0.92), (W * 0.8, 1.4, 0.08), 2)
    else:
        F.box(bm, (0, 0, H + 0.2), (W + 0.4, D + 0.4, 0.4), 4)
        if st == 'tower': F.cyl(bm, (0, 0, H + 0.4), 0.15, 0.03, H * 0.18, seg=6, mi=4)
    F.obj('building', bm, mats)

def lathe(P):
    """Something turned on a wheel. shape: vase | barrel | column | bottle | goblet | bowl | pot, or profile [[radius, height] …]; height (1), color, segments (16)."""
    S = {'vase': [[0, 0], [0.18, 0], [0.3, 0.25], [0.26, 0.55], [0.12, 0.8], [0.17, 1.0], [0.14, 1.0], [0.09, 0.8]],
         'barrel': [[0, 0], [0.3, 0], [0.37, 0.3], [0.38, 0.5], [0.37, 0.7], [0.3, 1.0], [0, 1.0]],
         'column': [[0, 0], [0.2, 0], [0.2, 0.05], [0.14, 0.09], [0.125, 0.9], [0.17, 0.94], [0.2, 0.96], [0.2, 1.0], [0, 1.0]],
         'bottle': [[0, 0], [0.14, 0], [0.15, 0.55], [0.06, 0.75], [0.05, 0.97], [0.065, 1.0], [0, 1.0]],
         'goblet': [[0, 0], [0.16, 0], [0.15, 0.04], [0.03, 0.1], [0.03, 0.5], [0.16, 0.72], [0.19, 1.0], [0.17, 1.0], [0.13, 0.74], [0, 0.6]],
         'bowl': [[0, 0], [0.2, 0], [0.42, 0.7], [0.5, 1.0], [0.46, 1.0], [0.36, 0.6], [0, 0.2]],
         'pot': [[0, 0], [0.24, 0], [0.4, 0.4], [0.36, 0.8], [0.28, 0.92], [0.33, 1.0], [0.29, 1.0], [0.24, 0.9], [0, 0.9]]}
    prof = P.get('profile') or S.get(P.get('shape', 'vase'))
    if not prof: raise ValueError('shape: ' + ', '.join(S))
    h = P.get('height', 1.0); k = P.get('width', 1.0) * h
    bm = bmesh.new(); F.lathe(bm, [[r * (1 if P.get('profile') else k), z * (1 if P.get('profile') else h)] for r, z in prof], seg=int(P.get('segments', 16)))
    F.obj(P.get('shape', 'lathe'), bm, F.mat(P.get('color', '#b0603a'), P.get('rough', 0.7), P.get('metal', 0.0)), smooth=P.get('smooth', True))

def text(P):
    """Letters as a solid: a title, a sign, a logo. text, size (1 = letter height), depth (0.2), bevel (0.02), color, metal, glow."""
    bpy.ops.object.text_add(location=(0, 0, 0)); o = bpy.context.object
    o.data.body = str(P.get('text', 'TEXT')); o.data.size = P.get('size', 1.0); o.data.extrude = P.get('depth', 0.2) / 2; o.data.bevel_depth = P.get('bevel', 0.02); o.data.align_x = 'CENTER'
    if P.get('font') : o.data.font = bpy.data.fonts.load(P['font'])
    o.rotation_euler = (math.pi / 2, 0, 0)
    bpy.ops.object.convert(target='MESH')
    o.data.materials.append(F.mat(P.get('color', '#e0b040'), P.get('rough', 0.35), P.get('metal', 0.6), emit=P.get('glow'), strength=P.get('glow_strength', 3.0)))

def crystal(P):
    """A cluster of crystals. count (7), height (1.6), seed, color, glow (colour)."""
    r = _rnd(P); bm = bmesh.new(); H = P.get('height', 1.6)
    for i in range(int(P.get('count', 7))):
        a = r.random() * math.tau; d = r.random() * H * 0.25; h = H * (0.35 + r.random() * 0.65); w = h * (0.12 + r.random() * 0.06)
        tilt = Matrix.Rotation(a, 4, 'Z') @ Matrix.Rotation(r.random() * 0.5, 4, 'X')
        F.cyl(bm, (math.cos(a) * d, math.sin(a) * d, 0), w, w * 0.8, h * 0.75, seg=6, tilt=tilt)
        F.cyl(bm, tuple(Vector((math.cos(a) * d, math.sin(a) * d, 0)) + (tilt @ Vector((0, 0, h * 0.75)))), w * 0.8, 0.0, h * 0.25, seg=6, tilt=tilt)
    F.obj('crystal', bm, F.mat(P.get('color', '#7fd0e8'), 0.15, 0.1, emit=P.get('glow', P.get('color', '#7fd0e8')), strength=P.get('glow_strength', 0.45)))

def stairs(P):
    """A flight of steps. steps (8), width (2), rise (0.18), run (0.3), color."""
    n = int(P.get('steps', 8)); w = P.get('width', 2.0); ri = P.get('rise', 0.18); ru = P.get('run', 0.3); bm = bmesh.new()
    for i in range(n): F.box(bm, (0, i * ru + ru / 2, (i + 1) * ri / 2), (w, ru, (i + 1) * ri))
    F.obj('stairs', bm, F.mat(P.get('color', '#9a968e'), 0.9))

def arch(P):
    """A stone arch or a bridge. span (4), height (3.5), thickness (0.8), depth (1), color; deck: true makes it a bridge."""
    sp = P.get('span', 4.0); H = P.get('height', 3.5); th = P.get('thickness', 0.8); d = P.get('depth', 1.0); bm = bmesh.new(); seg = 14; r = sp / 2
    for sx in (-1, 1): F.box(bm, (sx * (r + th / 2), 0, (H - r) / 2), (th, d, max(0.01, H - r)))
    for i in range(seg):
        a0 = math.pi * i / seg; a1 = math.pi * (i + 1) / seg; pts = []
        for a, rr in ((a0, r), (a1, r), (a1, r + th), (a0, r + th)):
            for y in (-d / 2, d / 2): pts.append(bm.verts.new((math.cos(a) * rr, y, H - r + math.sin(a) * rr)))
        for q in ((0, 2, 4, 6), (1, 7, 5, 3), (0, 1, 3, 2), (4, 5, 7, 6), (2, 3, 5, 4), (0, 6, 7, 1)): bm.faces.new([pts[k] for k in q])
    if P.get('deck'): F.box(bm, (0, 0, H + th + 0.15), (sp + th * 2 + 3, d, 0.3))
    F.obj('arch', bm, F.mat(P.get('color', '#8f8a84'), 0.95))

def fence(P):
    """A run of fence or wall. length (6), height (1.1), style: rails | pickets | wall | hedge, color."""
    L = P.get('length', 6.0); H = P.get('height', 1.1); st = P.get('style', 'rails'); bm = bmesh.new()
    if st == 'wall': F.box(bm, (0, 0, H / 2), (L, 0.35, H)); [F.box(bm, (x, 0, H + 0.12), (0.5, 0.45, 0.24)) for x in [(-L / 2 + 0.3) + i * 1.0 for i in range(int(L))]]
    elif st == 'hedge':
        for i in range(int(L / 0.7) + 1): F.ball(bm, (-L / 2 + i * 0.7, 0, H * 0.5), H * 0.55, sub=1, squash=(1, 0.7, 1), rough=0.12, seed=i)
    else:
        n = max(1, round(L / (0.16 if st == 'pickets' else 1.6)))
        for i in range(n + 1): F.box(bm, (-L / 2 + L * i / n, 0, H / 2), (0.09 if st == 'pickets' else 0.1, 0.03 if st == 'pickets' else 0.1, H))
        for z in ((0.3, 0.75) if st == 'pickets' else (0.36, 0.72)): F.box(bm, (0, 0, H * z), (L, 0.05, 0.09))
    F.obj('fence', bm, F.mat(P.get('color', '#4a7a3a' if st == 'hedge' else '#8f8a84' if st == 'wall' else '#8a7052'), 0.9))

def planet(P):
    """A small world for a sky or a title: radius (5), seed, sea / land / ice colours, sea (0.5 = half sea)."""
    R = P.get('radius', 5.0); seed = P.get('seed', 1); bm = bmesh.new(); F.ball(bm, (0, 0, 0), R, sub=int(P.get('detail', 4)))
    for f in bm.faces:
        c = f.calc_center_median().normalized(); h = F.fbm(c.x * 1.6, c.y * 1.6, c.z * 1.6, seed=seed) + 0.1
        f.material_index = 2 if abs(c.z) > 0.86 else 0 if h < (P.get('sea', 0.5) - 0.5) * 0.9 + 0.1 else 1
    F.obj('planet', bm, [F.mat(P.get('sea_color', '#2f6fae'), 0.4), F.mat(P.get('land', '#5a9a4a'), 0.9), F.mat(P.get('ice', '#f1f4fa'), 0.7)], smooth=True)

def cloud(P):
    """A cloud to hang in a sky. size (8), seed, color."""
    r = _rnd(P); S = P.get('size', 8.0); bm = bmesh.new()
    for i in range(int(P.get('puffs', 9))): F.ball(bm, ((r.random() - 0.5) * S, (r.random() - 0.5) * S * 0.4, (r.random()) * S * 0.12), S * (0.14 + r.random() * 0.1), sub=2, squash=(1, 1, 0.6))
    F.obj('cloud', bm, F.mat(P.get('color', '#ffffff'), 1.0), smooth=True)

def furniture(P):
    """Simple furniture. kind: table | chair | bench | bed | shelf | crate; width, depth, height; color."""
    k = P.get('kind', 'table'); c = F.mat(P.get('color', '#8a6a48'), 0.85); c2 = F.mat(P.get('color2', '#d8d2c4'), 0.9); bm = bmesh.new()
    w = P.get('width', {'table': 1.4, 'chair': 0.45, 'bench': 1.6, 'bed': 1.1, 'shelf': 1.2, 'crate': 0.7}[k]); d = P.get('depth', {'table': 0.8, 'chair': 0.45, 'bench': 0.4, 'bed': 2.0, 'shelf': 0.35, 'crate': 0.7}[k]); h = P.get('height', {'table': 0.74, 'chair': 0.45, 'bench': 0.45, 'bed': 0.45, 'shelf': 1.9, 'crate': 0.7}[k])
    def legs(t=0.05):
        for sx in (-1, 1):
            for sy in (-1, 1): F.box(bm, (sx * (w / 2 - t), sy * (d / 2 - t), (h - 0.04) / 2), (t, t, h - 0.04))
    if k in ('table', 'chair', 'bench'):
        legs(); F.box(bm, (0, 0, h - 0.02), (w, d, 0.04))
        if k == 'chair' or (k == 'bench' and P.get('back', True)): F.box(bm, (0, d / 2 - 0.02, h + 0.25), (w, 0.04, 0.5))
    elif k == 'bed': F.box(bm, (0, 0, h * 0.35), (w, d, h * 0.7)); F.box(bm, (0, -0.1, h * 0.8), (w * 0.96, d * 0.8, h * 0.25), 1); F.box(bm, (0, d / 2 - 0.25, h * 0.85), (w * 0.6, 0.35, 0.12), 1); F.box(bm, (0, d / 2, h), (w, 0.06, h * 1.2))
    elif k == 'shelf':
        F.box(bm, (0, d / 2 - 0.01, h / 2), (w, 0.02, h))
        for sx in (-1, 1): F.box(bm, (sx * (w / 2 - 0.015), 0, h / 2), (0.03, d, h))
        for i in range(6): F.box(bm, (0, 0, i * (h - 0.03) / 5 + 0.015), (w, d, 0.03))
    else: F.box(bm, (0, 0, h / 2), (w, d, h)); [F.box(bm, (0, sy * d / 2, h / 2), (w * 1.02, 0.03, h * 0.12)) for sy in (-1, 1)]
    F.obj(k, bm, [c, c2])

RECIPES = {'rock': rock, 'rocks': rocks, 'tree': tree, 'bush': bush, 'terrain': terrain, 'mountain': mountain, 'building': building, 'lathe': lathe, 'text': text,
           'crystal': crystal, 'stairs': stairs, 'arch': arch, 'fence': fence, 'planet': planet, 'cloud': cloud, 'furniture': furniture}
ABOUT = {k: (v.__doc__ or '').strip() for k, v in RECIPES.items()}
if __name__ == '__main__': import json; print(json.dumps(ABOUT))
