// Motion: play motion-capture clips on any character (retargeted by bone direction), loop walks, and solve arms/legs to targets.
import { THREE, clamp, lerp } from './core.js';
const _q = new THREE.Quaternion(), _q2 = new THREE.Quaternion(), _q3 = new THREE.Quaternion(), _v = new THREE.Vector3(), _v2 = new THREE.Vector3(), _v3 = new THREE.Vector3(), Yax = new THREE.Vector3(0, 1, 0);
const SWAP = (n) => n.startsWith('Left') ? 'Right' + n.slice(4) : n.startsWith('Right') ? 'Left' + n.slice(5) : n;

/** One motion-capture clip: global joint rotations (int16 quaternions) + root path at the library's frame rate. */
export class Clip {
  constructor(name, meta, lib, pack) {
    const subj = lib.subjects[String(meta.s)] || {};
    this.name = name; this.m = { ...meta, leg: meta.leg ?? subj.leg, rest: meta.rest ?? subj.rest }; this.fps = lib.fps; this.J = lib.joints.length; this.n = meta.n; this.dur = (meta.n - 1) / lib.fps;
    this.root = pack.f32('r:' + name); this.quat = pack.i16('q:' + name); this.con = pack.has('c:' + name) ? pack.u8('c:' + name) : null; this.mj = lib.joints.map(n => lib.joints.indexOf(SWAP(n)));
  }
  /** root position (source units) at time t; mirror flips left/right */
  rootAt(t, out = new THREE.Vector3(), mirror = false) { const f = clamp(t * this.fps, 0, this.n - 1), i = Math.min(this.n - 2, Math.floor(f)), a = f - i; const r = this.root; out.set(lerp(r[i * 3], r[i * 3 + 3], a), lerp(r[i * 3 + 1], r[i * 3 + 4], a), lerp(r[i * 3 + 2], r[i * 3 + 5], a)); if (mirror) out.x = -out.x; return out; }
  quatAt(t, j, out, mirror = false) { if (mirror) j = this.mj[j]; const f = clamp(t * this.fps, 0, this.n - 1), i = Math.min(this.n - 2, Math.floor(f)), a = f - i; const q = this.quat, o = (i * this.J + j) * 4, o2 = o + this.J * 4, k = 1 / 32767;
    out.set(q[o] * k, q[o + 1] * k, q[o + 2] * k, q[o + 3] * k); _q3.set(q[o2] * k, q[o2 + 1] * k, q[o2 + 2] * k, q[o2 + 3] * k); out.slerp(_q3, a); if (mirror) { out.y = -out.y; out.z = -out.z; } return out.normalize(); }
  /** foot contacts at time t: bit 1 = left planted, bit 2 = right planted (swapped when mirrored) */
  contactAt(t, mirror = false) { if (!this.con) return 0; const c = this.con[Math.round(clamp(t * this.fps, 0, this.n - 1))]; return mirror ? ((c & 1) << 1) | ((c & 2) >> 1) : c; }
  restDir(joint, mirror, out) { const r = this.m.rest[mirror ? SWAP(joint) : joint]; out.set(mirror ? -r[0] : r[0], r[1], r[2]); return out; }
}
/** The motion library seen by the engine: index + the subject packs loaded so far (see ../motion/library.ts). */
export class Mocap {
  constructor(lib) { this.lib = lib; this.idx = lib.index; this.cache = {}; this.jid = Object.fromEntries(lib.index.joints.map((n, i) => [n, i])); }
  has(name) { return !!this.idx.clips[name]; }
  clip(name) { let c = this.cache[name]; if (c) return c; const m = this.idx.clips[name]; if (!m) throw new Error(`No motion "${name}". Search the library with stage.motions.`); const pack = this.lib.packNow(m.s); if (!pack) throw new Error(`Motion "${name}" is not loaded yet.`); return this.cache[name] = new Clip(name, m, this.idx, pack); }
}
// source joint -> [target bone, child bone that defines the direction, or null to copy rotation as-is]
const MAP = [['Hips', 'root', null], ['LowerBack', 'spine05', null], ['Spine', 'spine03', null], ['Spine1', 'spine02', null], ['Neck', 'neck01', null], ['Neck1', 'neck02', null], ['Head', 'head', null],
  ['LeftShoulder', 'clavicle.L', null], ['LeftArm', 'upperarm01.L', 'lowerarm01.L'], ['LeftForeArm', 'lowerarm01.L', 'wrist.L'], ['LeftHand', 'wrist.L', 'finger3-1.L'],
  ['RightShoulder', 'clavicle.R', null], ['RightArm', 'upperarm01.R', 'lowerarm01.R'], ['RightForeArm', 'lowerarm01.R', 'wrist.R'], ['RightHand', 'wrist.R', 'finger3-1.R'],
  ['LeftUpLeg', 'upperleg01.L', 'lowerleg01.L'], ['LeftLeg', 'lowerleg01.L', 'foot.L'], ['LeftFoot', 'foot.L', 'toe2-1.L'], ['RightUpLeg', 'upperleg01.R', 'lowerleg01.R'], ['RightLeg', 'lowerleg01.R', 'foot.R'], ['RightFoot', 'foot.R', 'toe2-1.R']];

/** Binds one Human to the mocap library. */
export class Motion {
  constructor(human, mocap) {
    this.h = human; this.mc = mocap; const R = human.rest; this.G = new Map();
    this.legT = R['upperleg01.L'].head.distanceTo(R['lowerleg01.L'].head) + R['lowerleg01.L'].head.distanceTo(R['foot.L'].head);
    this.bindCache = {}; this.order = human.bones;   // parents first
    this.mapped = new Map(MAP.map(([s, t, c]) => [t, { s, c, j: mocap.jid[s] }]));
    this.gq = human.bones.map(() => new THREE.Quaternion()); this.pi = human.bones.map(b => (b.parent && b.parent.isBone) ? human.bones.indexOf(b.parent) : -1);
    this.ankleY = R['foot.L'].head.y;
  }
  _corr(clip, mirror = false) { // per clip (source rest directions) correction quaternions
    const key = mirror ? clip.name + '|m' : clip.name; if (this.bindCache[key]) return this.bindCache[key];
    const R = this.h.rest, C = {};
    for (const [s, t, c] of MAP) { const q = new THREE.Quaternion(); if (c) { const dt = _v.copy(R[c].head).sub(R[t].head).normalize(); const ds = clip.restDir(s, mirror, _v2); if (s.endsWith('Foot')) { ds.y *= 0.6; ds.normalize(); } q.setFromUnitVectors(dt, ds); } C[t] = q; }
    return this.bindCache[key] = C;
  }
  /** Pose the character from clip `name` at time t (seconds in clip).
   *  o: { ref: clip time whose root XZ is the origin (default 0), yaw: extra heading (rad), inPlace, align: face travel direction along +Z using [t0,t1],
   *       loop: [t0,t1] cycle window, scaleMove: stride scale, lift: metres, keep: {bone:true} bones to leave untouched, w: blend weight 0..1 } */
  apply(name, t, o = {}) {
    const clip = this.mc.clip(name), h = this.h, mir = !!o.mirror, C = this._corr(clip, mir), sc = this.legT / clip.m.leg * (o.scaleMove ?? 1);
    let tt = t, cycles = 0, per = 0;
    if (o.loop) { per = o.loop[1] - o.loop[0]; const k = Math.floor((t - o.loop[0]) / per); if (t >= o.loop[0]) { cycles = Math.max(0, k); tt = t - cycles * per; } }
    else tt = clamp(t, 0, clip.dur);
    let yawFix = o.align ? -this.heading(clip, o.align[0], o.align[1], mir) : 0;
    if (o.face != null && o.face !== false) { const hq = clip.quatAt(typeof o.face === 'number' ? o.face : tt, this.mc.jid.Hips, _q, mir); const f = (o.lying ? _v.set(0, 1, 0) : _v.set(0, 0, 1)).applyQuaternion(hq); yawFix = -Math.atan2(f.x, f.z) + (o.lying ? Math.PI : 0); }
    const yaw = yawFix + (o.yaw || 0);
    const qYaw = _q2.setFromAxisAngle(Yax, yaw).clone();
    const w = o.w ?? 1; const blendSeam = o.loop ? clamp((tt - (o.loop[1] - 0.25)) / 0.25) : 0;
    const sampleQ = (j, out) => { clip.quatAt(tt, j, out, mir); if (blendSeam > 0) { clip.quatAt(tt - per, j, _q, mir); out.slerp(_q, blendSeam); } return out; };
    // rotations
    const bones = this.order, gq = this.gq;
    for (let i = 0; i < bones.length; i++) {
      const b = bones[i], m = this.mapped.get(b.name), pi = this.pi[i]; const pg = pi >= 0 ? gq[pi] : null;
      if (m && !(o.keep && o.keep[b.name])) {
        const g = sampleQ(m.j, gq[i]); g.premultiply(qYaw).multiply(C[b.name]);
        _q.copy(g); if (pg) _q.premultiply(_q3.copy(pg).invert());
        if (w >= 1) b.quaternion.copy(_q); else { b.quaternion.slerp(_q, w); if (pg) gq[i].copy(pg).multiply(b.quaternion); else gq[i].copy(b.quaternion); }
      } else { if (pg) gq[i].copy(pg).multiply(b.quaternion); else gq[i].copy(b.quaternion); }
    }
    // root translation
    const r = clip.rootAt(tt, _v, mir); if (blendSeam > 0) { const r2 = clip.rootAt(tt - per, _v2, mir); r.y = lerp(r.y, r2.y, blendSeam); }
    const ref = clip.rootAt(o.ref ?? (o.loop ? o.loop[0] : 0), _v2, mir);
    let dx = r.x - ref.x, dz = r.z - ref.z;
    if (o.loop && cycles > 0) { const a = clip.rootAt(o.loop[0], _v3, mir), bx = a.x, bz = a.z; const e = clip.rootAt(o.loop[1], _v3, mir); dx += (e.x - bx) * cycles; dz += (e.z - bz) * cycles; }
    if (o.inPlace) { dx = 0; dz = 0; }
    const c = Math.cos(yaw), s = Math.sin(yaw); const rest = h.rest.root.head;
    const px = (dx * c + dz * s) * sc, pz = (-dx * s + dz * c) * sc, py = rest.y + ((r.y - clip.m.ground) - clip.m.stand) * this.legT / clip.m.leg + (o.lift || 0);
    if (w >= 1) h.B.root.position.set(rest.x + px, py, rest.z + pz); else h.B.root.position.lerp(_v3.set(rest.x + px, py, rest.z + pz), w);
    return this;
  }
  /** travel heading (rad, about Y) of the clip between two times */
  heading(clip, t0, t1, mirror = false) { if (typeof clip === 'string') clip = this.mc.clip(clip); const a = clip.rootAt(t0, _v, mirror), b = clip.rootAt(t1, _v2, mirror); return Math.atan2(b.x - a.x, b.z - a.z); }
  /** facing (rad, about Y) of the hips at clip time t */
  facing(clip, t, mirror = false) { if (typeof clip === 'string') clip = this.mc.clip(clip); const f = _v.set(0, 0, 1).applyQuaternion(clip.quatAt(t, this.mc.jid.Hips, _q, mirror)); return Math.atan2(f.x, f.z); }
  /** find a clean loop window [t0, t1] near tStart with period in [pmin, pmax] */
  findLoop(name, tStart, pmin = 0.8, pmax = 1.4) {
    const clip = this.mc.clip(name), J = ['LeftUpLeg', 'LeftLeg', 'RightUpLeg', 'RightLeg', 'LeftArm', 'RightArm', 'Spine'].map(n => this.mc.jid[n]); let best = [1e9, tStart, tStart + 1];
    const qa = new THREE.Quaternion(), qb = new THREE.Quaternion(), h0 = this.heading(clip, tStart, tStart + pmax);
    for (let t0 = tStart; t0 < tStart + 0.6; t0 += 1 / 30) for (let p = pmin; p <= pmax; p += 1 / 30) { if (t0 + p > clip.dur) break; let d = 0; for (const j of J) { clip.quatAt(t0, j, qa); clip.quatAt(t0 + p, j, qb); d += 1 - Math.abs(qa.dot(qb)); } d += Math.abs(clip.rootAt(t0, _v).y - clip.rootAt(t0 + p, _v2).y) * 0.02; if (d < best[0]) best = [d, t0, t0 + p]; }
    return [best[1], best[2]];
  }
  /** keep feet out of the ground: lift the root if the lowest ankle/toe is below groundY (world); also returns lowest point */
  /** Feet out of the ground; with snap, a standing body is set down on it (w: how much of a downward settle to take now). */
  groundFix(groundY = 0, snap = false, w = 1) {
    const h = this.h; h.root.updateMatrixWorld(true); let low = 1e9;
    for (const n of ['foot.L', 'foot.R']) { const y = h.wpos(n, _v).y - this.ankleY * h.root.scale.y; low = Math.min(low, y); }
    for (const n of ['toe2-1.L', 'toe2-1.R']) { const y = h.wpos(n, _v).y - h.rest[n].head.y * h.root.scale.y; low = Math.min(low, y); }
    let d = groundY - low; if (d < 0 && snap) d *= w;
    if (d > 0 || snap) { h.B.root.position.y += d / h.root.scale.y; h.root.updateMatrixWorld(true); }
    return low;
  }
}

/** two-bone IK: place `end` bone head at world target. chain names [upper, lower, end]; pole = world direction the joint (elbow/knee) should point to */
export function ik2(h, upper, lower, end, target, pole) {
  h.root.updateMatrixWorld(true);
  const A = h.wpos(upper, new THREE.Vector3()), s = h.root.scale.y; const R = h.rest; const l1 = R[upper].head.distanceTo(R[lower].head) * s, l2 = R[lower].head.distanceTo(R[end].head) * s;
  const to = _v.copy(target).sub(A); let d = to.length(); const dmax = (l1 + l2) * 0.999; if (d > dmax) { to.multiplyScalar(dmax / d); d = dmax; } d = Math.max(d, Math.abs(l1 - l2) + 1e-4);
  const dir = to.clone().normalize(); const a = (l1 * l1 - l2 * l2 + d * d) / (2 * d); const hgt = Math.sqrt(Math.max(0, l1 * l1 - a * a));
  const pl = _v2.copy(pole).addScaledVector(dir, -pole.dot(dir)); if (pl.lengthSq() < 1e-8) pl.set(0, 0, 1).addScaledVector(dir, -dir.z); pl.normalize();
  const elbow = A.clone().addScaledVector(dir, a).addScaledVector(pl, hgt); const tgt = A.clone().add(to);
  const inv = _q.copy(h.root.getWorldQuaternion(new THREE.Quaternion())).invert();
  h.aim(upper, elbow.clone().sub(A).applyQuaternion(inv), lower); h.root.updateMatrixWorld(true);
  h.aim(lower, tgt.sub(elbow).applyQuaternion(inv), end); h.root.updateMatrixWorld(true);
}
