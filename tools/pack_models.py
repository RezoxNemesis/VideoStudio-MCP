"""Copy the glTF models of openly licensed packs into the app's model library and write its catalogue.
usage: python3 -I packmodels.py <packs root> <out dir>      (reads the packs as data only)"""
import json, os, shutil, struct, sys, re
SRC, OUT = sys.argv[1], sys.argv[2]
PACKS = {  # folder → (id, title, what it is for)
    'kaykit-city-builder-bits-1.0': ('city', 'City', 'modern town: buildings, roads, cars, street things', 4.6),
    'kaykit-furniture-bits-1.0': ('furniture', 'Furniture', 'rooms: sofas, beds, tables, shelves, lamps, rugs', 0.72),
    'kaykit-dungeon-remastered-1.0': ('dungeon', 'Dungeon', 'castle and dungeon: walls, floors, doors, barrels, chests, torches, tables', 0.72),
    'kaykit-restaurant-bits-1.0': ('restaurant', 'Restaurant', 'kitchen and food: counters, stoves, plates, dishes, ingredients', 0.72),
    'kaykit-space-base-bits-1.0': ('space', 'Space base', 'science fiction: base modules, landers, cargo, solar panels, rocks', 4.5),
    'kaykit-medieval-hexagon-pack-1.0': ('medieval', 'Medieval', 'medieval world: houses, castles, towers, mills, ships, trees, terrain tiles', 6.0),
    'kaykit-prototype-bits-1.0': ('prototype', 'Prototype', 'simple shapes: boxes, ramps, pillars, targets, signs, dummies', 0.72),
    'kaykit-halloween-bits-1.0': ('halloween', 'Halloween', 'spooky: graves, pumpkins, fences, crypts, candles, dead trees', 0.72),
}
def bbox(g, base):
    lo, hi = [1e9] * 3, [-1e9] * 3
    for m in g.get('meshes', []):
        for p in m.get('primitives', []):
            a = g['accessors'][p['attributes']['POSITION']]
            if 'min' in a and 'max' in a:
                for i in range(3): lo[i] = min(lo[i], a['min'][i]); hi[i] = max(hi[i], a['max'][i])
    return [round(hi[i] - lo[i], 2) if hi[i] > lo[i] else 0 for i in range(3)]
index, total = [], 0
shutil.rmtree(OUT, ignore_errors=True)
for folder, (pid, title, about, scale) in PACKS.items():
    root = os.path.join(SRC, folder); n = 0
    lic = open(os.path.join(root, 'LICENSE.txt'), encoding='utf-8', errors='replace').read()
    assert 'Creative Commons Zero' in lic, folder
    dst = os.path.join(OUT, pid); os.makedirs(dst, exist_ok=True)
    seen = {}
    for dp, dn, fn in os.walk(root):
        if '.git' in dp.split(os.sep): dn[:] = []; continue
        for f in sorted(fn):
            glb = f.endswith('.glb')
            if not (f.endswith('.gltf') or glb): continue
            src = os.path.join(dp, f)
            try:
                if glb:
                    raw = open(src, 'rb').read()
                    assert raw[:4] == b'glTF'
                    n0 = struct.unpack('<I', raw[12:16])[0]
                    g = json.loads(raw[20:20 + n0].decode('utf-8'))
                else: g = json.load(open(src, encoding='utf-8'))
            except Exception as e: print('skip', src, e); continue
            ext = '.glb' if glb else '.gltf'
            name = re.sub(r'\.gltf$', '', re.sub(r'[^A-Za-z0-9_.-]', '_', f[:-len(ext)]))
            if name in seen: continue          # the same model in a second colour folder: keep the first
            files = [] if glb else [b['uri'] for b in g.get('buffers', []) if 'uri' in b] + [i['uri'] for i in g.get('images', []) if 'uri' in i]
            if any(u.startswith('data:') or '..' in u or u.startswith('/') for u in files): print('skip (odd uri)', src); continue
            ok = True; size = os.path.getsize(src)
            for u in files:
                s2 = os.path.join(dp, u)
                if not os.path.isfile(s2): ok = False; break
                d2 = os.path.join(dst, u); os.makedirs(os.path.dirname(d2), exist_ok=True)
                if not os.path.exists(d2): shutil.copyfile(s2, d2); total += os.path.getsize(s2)
                if u.endswith('.bin'): size += os.path.getsize(s2)
            if not ok: print('skip (missing file)', src); continue
            shutil.copyfile(src, os.path.join(dst, name + ext)); total += os.path.getsize(src)
            sub = os.path.relpath(dp, root).split(os.sep)
            cat = next((s for s in reversed(sub) if s.lower() not in ('gltf', 'glb', 'assets', 'addons') and not s.startswith('kaykit')), '')
            seen[name] = 1; n += 1
            index.append({'id': f'{pid}/{name}', 'pack': pid, 'name': name.replace('_', ' '), 'file': f'{pid}/{name}{ext}', 'group': cat.lower(), 'size': [round(v * scale, 2) for v in bbox(g, dp)], 'kb': round(size / 1024)})
    open(os.path.join(dst, 'LICENSE.txt'), 'w', encoding='utf-8').write(lic)
    print(f'{pid}: {n} models')
packs = [{'id': pid, 'title': title, 'about': about, 'scale': scale, 'count': sum(1 for m in index if m['pack'] == pid), 'licence': 'CC0 1.0 — KayKit by Kay Lousberg (kaylousberg.com)'} for (pid, title, about, scale) in PACKS.values()]
json.dump({'packs': packs, 'models': index}, open(os.path.join(OUT, 'index.json'), 'w'), separators=(',', ':'))
print(len(index), 'models,', round(total / 1e6, 1), 'MB')
