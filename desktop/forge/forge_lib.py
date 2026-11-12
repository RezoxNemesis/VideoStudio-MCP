# Helpers for forge recipes and for scripts written for the forge (they get this module as F).
# Units are metres, Z is up in Blender (the file is saved Y-up for the Stage); build things standing on z = 0.
import bpy, bmesh, math, random
from mathutils import Vector, Matrix, noise

def clear():
    """An empty scene (the start-up cube, light and camera are removed)."""
    for o in list(bpy.data.objects): bpy.data.objects.remove(o, do_unlink=True)
    for coll in (bpy.data.meshes, bpy.data.materials, bpy.data.curves):
        for b in list(coll):
            if b.users == 0: coll.remove(b)

def rgb(c):
    """'#rrggbb' or [r, g, b] (0..1) → linear (r, g, b, 1) for a material."""
    if isinstance(c, str):
        c = c.lstrip('#'); v = [int(c[i:i + 2], 16) / 255 for i in (0, 2, 4)]
        v = [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in v]
    else: v = list(c)[:3]
    return (v[0], v[1], v[2], 1.0)

_mats = {}
def mat(color='#888888', rough=0.85, metal=0.0, emit=None, strength=2.0, name=None):
    """A plain material of one colour (made once per colour). emit: a colour that glows."""
    key = (str(color), rough, metal, str(emit), strength)
    if key in _mats: return _mats[key]
    m = bpy.data.materials.new(name or ('m_' + str(color).strip('#')))
    m.use_nodes = True
    b = m.node_tree.nodes.get('Principled BSDF')
    b.inputs['Base Color'].default_value = rgb(color)
    b.inputs['Roughness'].default_value = rough
    b.inputs['Metallic'].default_value = metal
    if emit is not None:
        e = b.inputs.get('Emission Color') or b.inputs.get('Emission')
        if e: e.default_value = rgb(emit)
        s = b.inputs.get('Emission Strength')
        if s: s.default_value = strength
    _mats[key] = m
    return m

def obj(name, bm, mats, smooth=False, at=(0, 0, 0)):
    """Turn a bmesh into an object in the scene. mats: the materials its faces point at (face.material_index)."""
    me = bpy.data.meshes.new(name)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=0.0005)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(me); bm.free()
    for m in (mats if isinstance(mats, (list, tuple)) else [mats]): me.materials.append(m)
    for p in me.polygons: p.use_smooth = smooth
    o = bpy.data.objects.new(name, me)
    o.location = at
    bpy.context.collection.objects.link(o)
    return o

def _set(faces, mi):
    for f in faces:
        if isinstance(f, bmesh.types.BMFace): f.material_index = mi

def box(bm, center, size, mi=0, rot_z=0.0):
    """A box: center (x, y, z), size (x, y, z)."""
    r = bmesh.ops.create_cube(bm, size=1.0, matrix=Matrix.Translation(center) @ Matrix.Rotation(rot_z, 4, 'Z') @ Matrix.Diagonal((size[0], size[1], size[2], 1)))
    faces = set(f for v in r['verts'] for f in v.link_faces); _set(faces, mi); return faces

def cyl(bm, base, r1, r2, h, seg=10, mi=0, tilt=None):
    """A cylinder or cone standing on `base`: bottom radius r1, top radius r2, height h. tilt: a Matrix to lean it."""
    m = Matrix.Translation(base) @ (tilt or Matrix.Identity(4)) @ Matrix.Translation((0, 0, h / 2))
    r = bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=seg, radius1=max(r1, 1e-4), radius2=max(r2, 1e-4), depth=h, matrix=m)
    faces = set(f for v in r['verts'] for f in v.link_faces); _set(faces, mi); return faces

def ball(bm, center, radius, sub=2, mi=0, squash=(1, 1, 1), rough=0.0, seed=0):
    """A faceted ball (icosphere). rough: how lumpy (0..0.5 of the radius)."""
    r = bmesh.ops.create_icosphere(bm, subdivisions=sub, radius=radius, matrix=Matrix.Translation(center) @ Matrix.Diagonal((squash[0], squash[1], squash[2], 1)))
    if rough:
        c = Vector(center)
        for v in r['verts']:
            d = v.co - c
            v.co = c + d * (1 + rough * noise.noise(d.normalized() * 1.7 + Vector((seed * 3.1, seed * 1.7, seed))))
    faces = set(f for v in r['verts'] for f in v.link_faces); _set(faces, mi); return faces

def lathe(bm, profile, seg=16, mi=0):
    """Spin a profile [[radius, height], …] (bottom to top) round the Z axis: vases, barrels, columns, bottles."""
    vs = [bm.verts.new((max(r, 0.0), 0, z)) for r, z in profile]
    es = [bm.edges.new((vs[i], vs[i + 1])) for i in range(len(vs) - 1)]
    r = bmesh.ops.spin(bm, geom=vs + es, cent=(0, 0, 0), axis=(0, 0, 1), angle=math.tau, steps=seg, use_duplicate=False)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=0.0005)
    for f in bm.faces: f.material_index = mi

def fbm(x, y, z=0.0, octaves=5, seed=0):
    """Smooth random hills: about −1…1."""
    return noise.fractal(Vector((x + seed * 13.7, y - seed * 7.3, z + seed * 3.1)), 1.0, 2.0, octaves) * 0.6

def to_ground():
    """Move everything so the lowest point sits on z = 0 and the middle is over the origin."""
    lo = Vector((1e9, 1e9, 1e9)); hi = -lo; n = 0
    bpy.context.view_layer.update()
    for o in bpy.data.objects:
        if o.type != 'MESH': continue
        for c in o.bound_box:
            w = o.matrix_world @ Vector(c); n += 1
            lo = Vector((min(lo.x, w.x), min(lo.y, w.y), min(lo.z, w.z))); hi = Vector((max(hi.x, w.x), max(hi.y, w.y), max(hi.z, w.z)))
    if not n: return
    d = Vector((-(lo.x + hi.x) / 2, -(lo.y + hi.y) / 2, -lo.z))
    for o in bpy.data.objects:
        if o.parent is None: o.location += d

def import_any(path):
    """Bring a model file of another kind into the scene (fbx, obj, stl, ply, dae, usd, blend, gltf, glb)."""
    ext = path.lower().rsplit('.', 1)[-1]
    ops = bpy.ops
    if ext == 'fbx': ops.import_scene.fbx(filepath=path)
    elif ext == 'obj': (ops.wm.obj_import(filepath=path) if hasattr(ops.wm, 'obj_import') else ops.import_scene.obj(filepath=path))
    elif ext == 'stl': (ops.wm.stl_import(filepath=path) if hasattr(ops.wm, 'stl_import') else ops.import_mesh.stl(filepath=path))
    elif ext == 'ply': (ops.wm.ply_import(filepath=path) if hasattr(ops.wm, 'ply_import') else ops.import_mesh.ply(filepath=path))
    elif ext == 'dae': ops.wm.collada_import(filepath=path)
    elif ext in ('usd', 'usda', 'usdc', 'usdz'): ops.wm.usd_import(filepath=path)
    elif ext in ('gltf', 'glb'): ops.import_scene.gltf(filepath=path)
    elif ext == 'blend':
        with bpy.data.libraries.load(path, link=False) as (src, dst): dst.objects = list(src.objects)
        for o in dst.objects:
            if o is not None: bpy.context.collection.objects.link(o)
    else: raise ValueError('The forge cannot read .%s files (fbx, obj, stl, ply, dae, usd, blend, gltf, glb).' % ext)

def export(out):
    """Save the scene as one .glb file. → what is in it."""
    bpy.context.view_layer.update()
    tris = 0; lo = Vector((1e9, 1e9, 1e9)); hi = -lo; n = 0
    for o in bpy.data.objects:
        if o.type != 'MESH': continue
        n += 1; o.data.calc_loop_triangles(); tris += len(o.data.loop_triangles)
        for c in o.bound_box:
            w = o.matrix_world @ Vector(c)
            lo = Vector((min(lo.x, w.x), min(lo.y, w.y), min(lo.z, w.z))); hi = Vector((max(hi.x, w.x), max(hi.y, w.y), max(hi.z, w.z)))
    if not n: raise ValueError('Nothing was built: the scene has no mesh.')
    bpy.ops.export_scene.gltf(filepath=out, export_format='GLB', export_apply=True)
    return {'objects': n, 'triangles': tris, 'size': [round(hi.x - lo.x, 2), round(hi.z - lo.z, 2), round(hi.y - lo.y, 2)], 'materials': len([m for m in bpy.data.materials if m.users]), 'blender': bpy.app.version_string}
