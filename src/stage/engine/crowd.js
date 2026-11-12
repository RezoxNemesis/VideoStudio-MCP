// Crowds: hundreds of soldiers from one baked performance. A soldier is posed through motion-capture cycles once,
// every vertex position is stored in a texture, and instances play the cycle back with their own phase and tint.
import { THREE, V3, mulberry, clamp } from './core.js';
import { makeActor, relax } from './actors.js';
import { patch, GU } from './mat.js';
const _v = new THREE.Vector3(), _q = new THREE.Quaternion(), _q2 = new THREE.Quaternion();

/** orient a held prop so its +Y axis points along a world direction, by turning the wrist */
export function pointProp(a, side, dirWorld, edgeWorld = null) {
  const h = a.h, b = h.B[`wrist.${side}`]; h.root.updateMatrixWorld(true); const H = h.hand[side];
  const y0 = H.across.clone().negate(), z0 = H.fwd.clone();   // prop axes in the wrist's rest frame
  const y1 = dirWorld.clone().normalize(); let z1 = (edgeWorld || V3(0, 0, 1)).clone(); z1.addScaledVector(y1, -z1.dot(y1)); if (z1.lengthSq() < 1e-6) z1.set(1, 0, 0).addScaledVector(y1, -y1.x); z1.normalize();
  const m0 = new THREE.Matrix4().makeBasis(new THREE.Vector3().crossVectors(y0, z0).normalize(), y0, z0), m1 = new THREE.Matrix4().makeBasis(new THREE.Vector3().crossVectors(y1, z1).normalize(), y1, z1);
  const want = _q.setFromRotationMatrix(m1).multiply(_q2.setFromRotationMatrix(m0).invert());   // world rotation of the wrist
  b.parent.getWorldQuaternion(_q2); b.quaternion.copy(_q2.invert().multiply(want)); h.root.updateMatrixWorld(true);
}
/** soldier at attention with spear upright and shield forward (applied on top of a mocap pose) */
export function spearCarry(a) { const h = a.h; h.aim('upperarm01.R', V3(-0.16, -1, 0.1), 'lowerarm01.R').aim('lowerarm01.R', V3(-0.05, -0.35, 1), 'wrist.R'); a.grip('R'); pointProp(a, 'R', V3(0.03, 1, 0.1));
  h.aim('upperarm01.L', V3(0.22, -1, 0.15), 'lowerarm01.L').aim('lowerarm01.L', V3(-0.55, 0.05, 0.8), 'wrist.L'); h.fingers('L', 0.8, 0.6); }
/** spear levelled for the charge */
export function spearCharge(a) { const h = a.h; h.aim('upperarm01.R', V3(-0.2, -0.85, -0.2), 'lowerarm01.R').aim('lowerarm01.R', V3(0.1, -0.1, 1), 'wrist.R'); a.grip('R'); pointProp(a, 'R', V3(0.05, 0.12, 1));
  h.aim('upperarm01.L', V3(0.2, -0.9, 0.35), 'lowerarm01.L').aim('lowerarm01.L', V3(-0.5, 0.25, 0.8), 'wrist.L'); h.fingers('L', 0.8, 0.6); }

const PART_COL = { shield: [0.14, 0.02, 0.02], spear: [0.10, 0.075, 0.05], helmet: [0.085, 0.085, 0.10], pauldron: [0.085, 0.085, 0.10], sword: [0.35, 0.35, 0.38], tunic: [0.30, 0.03, 0.03], coat: [0.30, 0.03, 0.03], trousers: [0.035, 0.032, 0.032], boots: [0.03, 0.022, 0.018], cuirass: [0.09, 0.09, 0.105], vambrace: [0.09, 0.09, 0.105], greave: [0.09, 0.09, 0.105], skin: [0.42, 0.28, 0.2] };
/** bake a soldier. clips: {name: {clip, loop:[t0,t1] | null (auto), frames, pose(a)}}. returns a Crowd factory */
export async function bakeSoldiers(o = {}) {
  const a = await makeActor('soldier', { kit: 'soldier', spear: o.spear !== false, shield: o.shield !== false, sword: o.sword }); const h = a.h; a.place(0, 0, 0);
  const clips = o.clips || { march: { clip: '138_01', t0: 1.0, pose: spearCarry }, idle: { clip: '140_06', loop: [2.0, 5.0], pose: spearCarry, frames: 36 }, run: { clip: '127_03', t0: 0.5, pmin: 0.55, pmax: 0.9, pose: spearCharge } };
  // gather vertices
  h.resetPose(); relax(h); spearCarry(a); h.root.updateMatrixWorld(true);
  const src = []; h.root.traverse(m => { if (!m.isMesh || !m.visible) return; if (['eyes', 'teeth', 'tongue', 'lashes', 'haircap', 'beard', 'hair'].includes(m.name)) return; src.push(m); });
  const verts = [], tris = []; const cell = o.cell ?? 0.058; const cmap = new Map();
  for (const m of src) {
    const g = m.geometry, pos = g.attributes.position, idx = g.index; let pn = m.name; for (let pp = m; pp && !pn; pp = pp.parent) pn = pp.name; const col = PART_COL[pn] || PART_COL[m.name] || (m.material.metalness > 0.5 ? [0.10, 0.10, 0.115] : m.material.color ? [m.material.color.r, m.material.color.g, m.material.color.b] : [0.2, 0.15, 0.1]);
    const isSkin = m.name === 'skin'; const local = new Int32Array(pos.count).fill(-1);
    const idOf = (i) => { if (local[i] >= 0) return local[i]; if (m.isSkinnedMesh) m.getVertexPosition(i, _v); else _v.fromBufferAttribute(pos, i); _v.applyMatrix4(m.matrixWorld);
      const key = (m.material.metalness > 0.5 ? 'm' : m.name) + '|' + Math.round(_v.x / cell) + '|' + Math.round(_v.y / cell) + '|' + Math.round(_v.z / cell); let id = cmap.get(key); if (id === undefined) { id = verts.length; verts.push({ m, i, col }); cmap.set(key, id); } return local[i] = id; };
    const n = idx ? idx.count : pos.count;
    for (let k = 0; k < n; k += 3) { const i0 = idx ? idx.getX(k) : k, i1 = idx ? idx.getX(k + 1) : k + 1, i2 = idx ? idx.getX(k + 2) : k + 2;
      if (isSkin) { _v.fromBufferAttribute(g.attributes.aux, i0); const yy = _v.y / h.height; const head = yy > 0.84, hand = Math.abs(_v.x) > 0.30 * h.height && yy < 0.62; if (!head && !hand) continue; }
      const a0 = idOf(i0), a1 = idOf(i1), a2 = idOf(i2); if (a0 !== a1 && a1 !== a2 && a0 !== a2) tris.push(a0, a1, a2); }
  }
  // bake frames
  const rows = []; const info = {}; const nv = verts.length;
  for (const [name, c] of Object.entries(clips)) {
    const loop = c.loop || a.mo.findLoop(c.clip, c.t0 ?? 0.5, c.pmin ?? 0.8, c.pmax ?? 1.4); const frames = c.frames ?? Math.max(8, Math.round((loop[1] - loop[0]) * 24)); info[name] = { start: rows.length, count: frames, dur: loop[1] - loop[0] };
    const hd = a.mo.heading(c.clip, loop[0], loop[1]);
    for (let f = 0; f < frames; f++) { h.resetPose(); a.mo.apply(c.clip, loop[0] + (loop[1] - loop[0]) * f / frames, { loop, inPlace: true, yaw: -hd }); if (c.pose) c.pose(a); a.mo.groundFix(0); h.root.updateMatrixWorld(true);
      const row = new Float32Array(nv * 4); for (let k = 0; k < nv; k++) { const vt = verts[k]; if (vt.m.isSkinnedMesh) vt.m.getVertexPosition(vt.i, _v); else _v.fromBufferAttribute(vt.m.geometry.attributes.position, vt.i); _v.applyMatrix4(vt.m.matrixWorld); row[k * 4] = _v.x; row[k * 4 + 1] = _v.y; row[k * 4 + 2] = _v.z; row[k * 4 + 3] = 1; } rows.push(row); }
  }
  const data = new Float32Array(nv * rows.length * 4); rows.forEach((r, i) => data.set(r, i * nv * 4));
  const vat = new THREE.DataTexture(data, nv, rows.length, THREE.RGBAFormat, THREE.FloatType); vat.minFilter = vat.magFilter = THREE.NearestFilter; vat.needsUpdate = true;
  const geo = new THREE.BufferGeometry(); const p0 = new Float32Array(nv * 3), vid = new Float32Array(nv), colr = new Float32Array(nv * 3);
  for (let k = 0; k < nv; k++) { p0[k * 3] = rows[0][k * 4]; p0[k * 3 + 1] = rows[0][k * 4 + 1]; p0[k * 3 + 2] = rows[0][k * 4 + 2]; vid[k] = k; colr.set(verts[k].col, k * 3); }
  geo.setAttribute('position', new THREE.BufferAttribute(p0, 3)); geo.setAttribute('aVid', new THREE.BufferAttribute(vid, 1)); geo.setAttribute('color', new THREE.BufferAttribute(colr, 3)); geo.setIndex(tris); geo.computeVertexNormals();
  console.warn(`crowd soldier: ${nv} verts, ${tris.length / 3} tris, ${rows.length} frames`);
  return { geo, vat, info, nv, make: (n, opt) => new Crowd({ geo, vat, info }, n, opt) };
}

export class Crowd {
  constructor(bake, n, o = {}) {
    this.bake = bake; this.n = n; const g = bake.geo.clone(); const anim = this.anim = new Float32Array(n * 4);
    g.setAttribute('iAnim', new THREE.InstancedBufferAttribute(anim, 4));
    const u = this.u = { tVAT: { value: bake.vat }, uFps: { value: 24 } };
    const m = new THREE.MeshLambertMaterial({ vertexColors: true, flatShading: true });
    patch(m, { attrs: [], uniforms: u, key: 'crowd', vdecl: 'uniform sampler2D tVAT; uniform float uTime, uFps; attribute float aVid; attribute vec4 iAnim;',
      vmain: `{ float fr = iAnim.z + uTime*uFps*iAnim.w; float f0 = floor(mod(fr, iAnim.y)); float f1 = mod(f0 + 1., iAnim.y); float al = fract(fr);
        vec3 p0 = texelFetch(tVAT, ivec2(int(aVid), int(iAnim.x + f0)), 0).xyz; vec3 p1 = texelFetch(tVAT, ivec2(int(aVid), int(iAnim.x + f1)), 0).xyz; transformed = mix(p0, p1, al); }` });
    this.mesh = new THREE.InstancedMesh(g, m, n); this.mesh.frustumCulled = false; this.mesh.castShadow = false; this.mesh.receiveShadow = true;
    this.pos = new Float32Array(n * 3); this.yaw = new Float32Array(n); this.scale = new Float32Array(n).fill(1); this.r = mulberry(o.seed ?? 3); const col = new THREE.Color();
    for (let i = 0; i < n; i++) { const k = 0.7 + this.r() * 0.6; this.mesh.setColorAt(i, col.setRGB(k, k, k)); this.scale[i] = 0.95 + this.r() * 0.1; this.setAnim(i, o.clip || 'march', this.r() * 64, 0.92 + this.r() * 0.16); }
  }
  setAnim(i, clip, phase = null, speed = null) { const c = this.bake.info[clip]; const a = this.anim; a[i * 4] = c.start; a[i * 4 + 1] = c.count; if (phase !== null) a[i * 4 + 2] = phase; if (speed !== null) a[i * 4 + 3] = speed; this.mesh.geometry.attributes.iAnim.needsUpdate = true; }
  setAll(clip, sync = false) { for (let i = 0; i < this.n; i++) this.setAnim(i, clip, sync ? 0 : null); }
  /** formation: rows × cols block facing yaw, with jitter */
  block(cx, cz, cols, gapX, gapZ, yaw = 0, jitter = 0.15, heightFn = null) { const c = Math.cos(yaw), s = Math.sin(yaw); for (let i = 0; i < this.n; i++) { const col = i % cols, row = Math.floor(i / cols); const lx = (col - (cols - 1) / 2) * gapX + (this.r() - 0.5) * jitter, lz = -row * gapZ + (this.r() - 0.5) * jitter; const x = cx + lx * c + lz * s, z = cz - lx * s + lz * c; this.pos[i * 3] = x; this.pos[i * 3 + 2] = z; this.pos[i * 3 + 1] = heightFn ? heightFn(x, z) : 0; this.yaw[i] = yaw; } this.commit(); return this; }
  /** write instance matrices; optional per-frame offset (dx, dz) and height function */
  commit(dx = 0, dz = 0, heightFn = null) { const m = new THREE.Matrix4(), q = new THREE.Quaternion(), Y = V3(0, 1, 0), p = new THREE.Vector3(), sc = new THREE.Vector3(); for (let i = 0; i < this.n; i++) { const x = this.pos[i * 3] + dx, z = this.pos[i * 3 + 2] + dz; p.set(x, heightFn ? heightFn(x, z) : this.pos[i * 3 + 1], z); q.setFromAxisAngle(Y, this.yaw[i]); sc.setScalar(this.scale[i]); m.compose(p, q, sc); this.mesh.setMatrixAt(i, m); } this.mesh.instanceMatrix.needsUpdate = true; }
}
