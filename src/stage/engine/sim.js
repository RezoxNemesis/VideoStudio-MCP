// Secondary motion: cloth sheets (capes, scarves, coat tails) and hair tails, simulated with verlet particles pinned to bones.
import { THREE, V3, wob, clamp, lerp } from './core.js';
import { hairMaterial, clothMaterial } from './mat.js';
const _v = new THREE.Vector3(), _v2 = new THREE.Vector3(), _m = new THREE.Matrix4();

/** default collision spheres for a Human: [bone, rest-space centre offset from bone head, radius] */
export function bodyColliders(h) {
  const k = h.height / 1.8, R = h.rest; const mid = (a, b, t = 0.5) => R[a].head.clone().lerp(R[b].head, t);
  const list = [['head', R.head.head.clone().add(V3(0, 0.085 * k, 0.03 * k)), 0.108 * k], ['neck01', R.neck01.head.clone(), 0.065 * k], ['spine02', R.spine02.head.clone().add(V3(0, 0.02, 0.01)), 0.165 * k], ['spine04', R.spine04.head.clone(), 0.155 * k], ['root', R.root.head.clone().add(V3(0, -0.02, -0.02)), 0.17 * k]];
  for (const s of ['L', 'R']) { list.push([`upperleg01.${s}`, mid(`upperleg01.${s}`, `lowerleg01.${s}`, 0.25), 0.095 * k], [`upperleg01.${s}`, mid(`upperleg01.${s}`, `lowerleg01.${s}`, 0.7), 0.08 * k], [`lowerleg01.${s}`, mid(`lowerleg01.${s}`, `foot.${s}`, 0.4), 0.065 * k], [`upperarm01.${s}`, mid(`upperarm01.${s}`, `lowerarm01.${s}`, 0.1), 0.075 * k], [`clavicle.${s}`, R[`upperarm01.${s}`].head.clone(), 0.08 * k]); }
  return list.map(([bone, c, r]) => ({ bone: h.B[bone], off: c.sub(R[bone].head), r }));
}

class Verlet {
  constructor(n) { this.n = n; this.p = new Float32Array(n * 3); this.q = new Float32Array(n * 3); this.w = new Float32Array(n).fill(1); this.cons = []; this.gravity = -9.8; this.damp = 0.985; this.wind = V3(); this.gust = 0.5; this.drag = 1.0; this.ground = null; this.started = false; this.seed = 1; }
  link(a, b, k = 1) { const dx = this.p[a * 3] - this.p[b * 3], dy = this.p[a * 3 + 1] - this.p[b * 3 + 1], dz = this.p[a * 3 + 2] - this.p[b * 3 + 2]; this.cons.push([a, b, Math.hypot(dx, dy, dz), k]); }
  step(dt, t, colliders, iters = 8) {
    const p = this.p, q = this.q, n = this.n, w = this.w; const g = this.gravity * dt * dt;
    const gx = this.wind.x * (1 + this.gust * wob(t, this.seed, 0.9)) + this.gust * wob(t, this.seed + 3, 2.3) * 0.6 * this.wind.length(), gz = this.wind.z * (1 + this.gust * wob(t, this.seed + 1, 0.8)) + this.gust * wob(t, this.seed + 5, 2.1) * 0.6 * this.wind.length(), gy = this.wind.y + this.gust * wob(t, this.seed + 2, 1.7) * 0.25 * this.wind.length();
    for (let i = 0; i < n; i++) { if (w[i] === 0) continue; const o = i * 3; const vx = (p[o] - q[o]) * this.damp, vy = (p[o + 1] - q[o + 1]) * this.damp, vz = (p[o + 2] - q[o + 2]) * this.damp; q[o] = p[o]; q[o + 1] = p[o + 1]; q[o + 2] = p[o + 2];
      const fl = 0.6 + 0.8 * wob(t * 2.3 + i * 0.37, this.seed + 7, 1);   // each point feels the wind a little differently: flutter
      p[o] += vx + (gx * fl - vx / dt) * this.drag * dt * dt; p[o + 1] += vy + g + (gy * fl) * this.drag * dt * dt; p[o + 2] += vz + (gz * fl - vz / dt) * this.drag * dt * dt; }
    for (let it = 0; it < iters; it++) {
      for (const c of this.cons) { const a = c[0] * 3, b = c[1] * 3; let dx = p[b] - p[a], dy = p[b + 1] - p[a + 1], dz = p[b + 2] - p[a + 2]; const d = Math.hypot(dx, dy, dz) || 1e-6; const wa = w[c[0]], wb = w[c[1]], ws = wa + wb; if (ws === 0) continue; const diff = (d - c[2]) / d * c[3]; dx *= diff; dy *= diff; dz *= diff; p[a] += dx * wa / ws; p[a + 1] += dy * wa / ws; p[a + 2] += dz * wa / ws; p[b] -= dx * wb / ws; p[b + 1] -= dy * wb / ws; p[b + 2] -= dz * wb / ws; }
      if (colliders) for (const s of colliders) { const cx = s.c.x, cy = s.c.y, cz = s.c.z, r = s.r; for (let i = 0; i < n; i++) { if (w[i] === 0) continue; const o = i * 3; const dx = p[o] - cx, dy = p[o + 1] - cy, dz = p[o + 2] - cz; const d2 = dx * dx + dy * dy + dz * dz; if (d2 < r * r) { const d = Math.sqrt(d2) || 1e-6, k = r / d; p[o] = cx + dx * k; p[o + 1] = cy + dy * k; p[o + 2] = cz + dz * k; } } }
      if (this.ground !== null) for (let i = 0; i < n; i++) { const o = i * 3; if (p[o + 1] < this.ground) { p[o + 1] = this.ground; q[o] = lerp(q[o], p[o], 0.5); q[o + 2] = lerp(q[o + 2], p[o + 2], 0.5); } }
    }
  }
}

/** A rectangular cloth hanging from anchors. o: { human, cols, rows, width, length, anchors:[{bone, pos(rest space)}] (cols of them, top row), color, material, wind } */
export class Cloth {
  constructor(o) {
    const h = this.h = o.human; const cols = this.cols = o.cols ?? 8, rows = this.rows = o.rows ?? 12; const v = this.v = new Verlet(cols * rows); this.o = o; v.seed = o.seed ?? 3; v.damp = o.damp ?? 0.985; v.drag = o.drag ?? 1.2; v.gust = o.gust ?? 0.6;
    this.anchors = o.anchors.map(a => ({ bone: h.B[a.bone], off: a.pos.clone().sub(h.rest[a.bone].head) })); this.len = o.length; this.taper = o.taper ?? 1; this.flare = o.flare ?? 1;
    const g = this.geo = new THREE.BufferGeometry(); this.pos = new Float32Array(cols * rows * 3); const aux = new Float32Array(cols * rows * 3), mask = new Float32Array(cols * rows * 4), idx = [];
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) { const i = r * cols + c; aux[i * 3] = (c / (cols - 1) - 0.5) * (o.width ?? 0.5); aux[i * 3 + 1] = 1.2 - r / (rows - 1) * o.length; aux[i * 3 + 2] = -0.1; mask[i * 4] = r === rows - 1 ? 1 : (c === 0 || c === cols - 1) ? 0.35 : 0; v.w[i] = r === 0 ? 0 : 1 + r * 0.02;
      if (r < rows - 1 && c < cols - 1) idx.push(i, i + cols, i + 1, i + 1, i + cols, i + cols + 1); }
    g.setAttribute('position', new THREE.BufferAttribute(this.pos, 3)); g.setAttribute('aux', new THREE.BufferAttribute(aux, 3)); g.setAttribute('mask', new THREE.BufferAttribute(mask, 4)); g.setIndex(idx);
    const mat = o.material || clothMaterial(o.color || [0.3, 0.05, 0.05], h.lm, { worn: o.worn ?? 0.5, rough: 0.9 }); mat.side = THREE.DoubleSide;
    this.mesh = new THREE.Mesh(g, mat); this.mesh.frustumCulled = false; this.mesh.castShadow = true; this.mesh.receiveShadow = true; this.col = o.collide === false ? null : bodyColliders(h);
    this.built = false;
  }
  _anchorW(i, out) { const a = this.anchors[i]; return out.copy(a.off).applyMatrix4(a.bone.matrixWorld); }
  _init() { const v = this.v, cols = this.cols, rows = this.rows; this.h.root.updateMatrixWorld(true); const back = V3(0, 0, -1).applyQuaternion(this.h.root.getWorldQuaternion(new THREE.Quaternion()));
    for (let c = 0; c < cols; c++) { const a = this._anchorW(c, new THREE.Vector3()); const sp = (c / (cols - 1) - 0.5); for (let r = 0; r < rows; r++) { const i = (r * cols + c) * 3; const t = r / (rows - 1); const dl = this.len * t * this.h.root.scale.y; v.p[i] = a.x + back.x * dl * 0.25 + sp * (this.flare - 1) * t * 0.5 * (back.z); v.p[i + 1] = a.y - dl * 0.95; v.p[i + 2] = a.z + back.z * dl * 0.25 - sp * (this.flare - 1) * t * 0.5 * back.x; } }
    v.q.set(v.p);
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) { const i = r * cols + c; if (c < cols - 1) v.link(i, i + 1, 0.9); if (r < rows - 1) v.link(i, i + cols, 1); if (r < rows - 1 && c < cols - 1) { v.link(i, i + cols + 1, 0.5); v.link(i + 1, i + cols, 0.5); } if (r < rows - 2) v.link(i, i + cols * 2, 0.25); if (c < cols - 2) v.link(i, i + 2, 0.25); }
    this.built = true; }
  /** advance one frame. call after the character is posed and matrices are updated */
  step(t, dt = 1 / 24, sub = 2) {
    this.h.root.updateMatrixWorld(true); if (!this.built) { this._init(); for (let k = 0; k < (this.o.warm ?? 30); k++) this._sub(t, dt / sub, 6); }
    for (let k = 0; k < sub; k++) this._sub(t + k * dt / sub, dt / sub, this.o.iters ?? 8);
    this.pos.set(this.v.p); this.geo.attributes.position.needsUpdate = true; this.geo.computeVertexNormals();
  }
  _sub(t, dt, iters) { const v = this.v; for (let c = 0; c < this.cols; c++) { const a = this._anchorW(c, _v); v.p[c * 3] = a.x; v.p[c * 3 + 1] = a.y; v.p[c * 3 + 2] = a.z; }
    if (this.col) for (const s of this.col) s.c = (s.c || new THREE.Vector3()).copy(s.off).applyMatrix4(s.bone.matrixWorld), s.r0 ??= s.r, s.r = s.r0 * this.h.root.scale.y + 0.012;
    v.step(dt, t, this.col, iters); }
  set wind(w) { this.v.wind.copy(w); }
}

/** A hair tail / lock / beard strand: one simulated chain drawn as a tapering tube. o: { human, bone, pos (rest space root), dir (rest-space initial direction), length, radius, segments, color look } */
export class Tail {
  constructor(o) {
    const h = this.h = o.human; const n = this.n = o.segments ?? 12; const v = this.v = new Verlet(n); this.o = o; v.seed = o.seed ?? 5; v.damp = o.damp ?? 0.97; v.drag = o.drag ?? 1.6; v.gust = o.gust ?? 0.7; v.gravity = -9.8 * (o.weight ?? 1);
    this.bone = h.B[o.bone]; this.off = o.pos.clone().sub(h.rest[o.bone].head); this.dir = (o.dir || V3(0, -1, -0.3)).clone().normalize(); this.len = o.length; this.rad = o.radius ?? 0.03; this.ring = o.ring ?? 7; this.stiff = o.stiff ?? 0;
    const g = this.geo = new THREE.BufferGeometry(); const nv = n * this.ring; this.pos = new Float32Array(nv * 3); this.nor = new Float32Array(nv * 3); const aux = new Float32Array(nv * 3), idx = [];
    for (let i = 0; i < n; i++) for (let k = 0; k < this.ring; k++) { const j = i * this.ring + k; const a = k / this.ring * 6.283; aux[j * 3] = Math.cos(a) * 0.05; aux[j * 3 + 1] = 1.7 - i / (n - 1) * this.len; aux[j * 3 + 2] = Math.sin(a) * 0.05 - 0.02; if (i < n - 1) { const k2 = (k + 1) % this.ring; idx.push(j, i * this.ring + k2, j + this.ring, i * this.ring + k2, (i + 1) * this.ring + k2, j + this.ring); } }
    g.setAttribute('position', new THREE.BufferAttribute(this.pos, 3)); g.setAttribute('normal', new THREE.BufferAttribute(this.nor, 3)); g.setAttribute('aux', new THREE.BufferAttribute(aux, 3)); g.setIndex(idx);
    this.mesh = new THREE.Mesh(g, o.material || hairMaterial(o.look || h.look, { mask: false })); this.mesh.frustumCulled = false; this.mesh.castShadow = true; this.col = o.collide === false ? null : bodyColliders(h); this.built = false;
    this.profile = o.profile || ((t) => (0.55 + 0.9 * Math.sin(Math.min(1, t * 3.2) * Math.PI / 2)) * Math.pow(1 - t, 0.55));
  }
  _root(out) { return out.copy(this.off).applyMatrix4(this.bone.matrixWorld); }
  _init() { const v = this.v; this.h.root.updateMatrixWorld(true); const a = this._root(new THREE.Vector3()); const d = this.dir.clone().transformDirection(this.bone.matrixWorld); const s = this.h.root.scale.y; for (let i = 0; i < this.n; i++) { const l = this.len * s * i / (this.n - 1); v.p[i * 3] = a.x + d.x * l; v.p[i * 3 + 1] = a.y + d.y * l; v.p[i * 3 + 2] = a.z + d.z * l; } v.q.set(v.p); v.w[0] = 0; for (let i = 0; i < this.n - 1; i++) { v.link(i, i + 1, 1); if (i < this.n - 2) v.link(i, i + 2, 0.35 + this.stiff); } this.built = true; }
  step(t, dt = 1 / 24, sub = 2) {
    this.h.root.updateMatrixWorld(true); if (!this.built) { this._init(); for (let k = 0; k < 30; k++) this._sub(t, dt / sub); }
    for (let k = 0; k < sub; k++) this._sub(t + k * dt / sub, dt / sub);
    // tube
    const p = this.v.p, n = this.n, ring = this.ring; const T = new THREE.Vector3(), N = new THREE.Vector3(1, 0, 0), B = new THREE.Vector3(); const s = this.h.root.scale.y;
    for (let i = 0; i < n; i++) { const i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1); T.set(p[i1 * 3] - p[i0 * 3], p[i1 * 3 + 1] - p[i0 * 3 + 1], p[i1 * 3 + 2] - p[i0 * 3 + 2]).normalize(); B.crossVectors(T, N); if (B.lengthSq() < 1e-6) B.set(0, 0, 1); B.normalize(); N.crossVectors(B, T).normalize();
      const r = this.rad * s * this.profile(i / (n - 1)) + 0.0008;
      for (let k = 0; k < ring; k++) { const a = k / ring * 6.283, cx = Math.cos(a), sx = Math.sin(a) * (this.o.flat ?? 0.7); const j = (i * ring + k) * 3; const nx = N.x * cx + B.x * sx, ny = N.y * cx + B.y * sx, nz = N.z * cx + B.z * sx; this.pos[j] = p[i * 3] + nx * r; this.pos[j + 1] = p[i * 3 + 1] + ny * r; this.pos[j + 2] = p[i * 3 + 2] + nz * r; this.nor[j] = nx; this.nor[j + 1] = ny; this.nor[j + 2] = nz; } }
    this.geo.attributes.position.needsUpdate = true; this.geo.attributes.normal.needsUpdate = true;
  }
  _sub(t, dt) { const v = this.v; const a = this._root(_v); v.p[0] = a.x; v.p[1] = a.y; v.p[2] = a.z;
    if (this.stiff > 0) { const d = _v2.copy(this.dir).transformDirection(this.bone.matrixWorld); const l = this.len * this.h.root.scale.y / (this.n - 1); const k = this.stiff * 0.5; v.p[3] = lerp(v.p[3], a.x + d.x * l, k); v.p[4] = lerp(v.p[4], a.y + d.y * l, k); v.p[5] = lerp(v.p[5], a.z + d.z * l, k); }
    if (this.col) for (const s of this.col) s.c = (s.c || new THREE.Vector3()).copy(s.off).applyMatrix4(s.bone.matrixWorld), s.r0 ??= s.r, s.r = s.r0 * this.h.root.scale.y + this.rad * 0.6;
    v.step(dt, t, this.col, 8); }
  set wind(w) { this.v.wind.copy(w); }
}
