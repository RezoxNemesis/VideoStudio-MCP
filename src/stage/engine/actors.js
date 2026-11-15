// Actors: a character plus its motion binding, hair/cloth simulation and props (sword, armour, staff...).
import { THREE, V3, clamp, lerp } from './core.js';
import { Human, relax } from './human.js';
import { Motion, ik2 } from './anim.js';
import { Cloth, Tail } from './sim.js';
import * as P from './props.js';
import { hairMaterial, clothMaterial } from './mat.js';
const _v = new THREE.Vector3();

/** place obj under a bone using rest-space position and an orthonormal basis (x,y,z axes in rest space) */
export function attachRest(h, obj, bone, pos, yAxis = null, zAxis = null) {
  h.B[bone].add(obj); obj.position.copy(pos).sub(h.rest[bone].head);
  if (yAxis) { const y = yAxis.clone().normalize(); let z = (zAxis || V3(0, 0, 1)).clone(); z.addScaledVector(y, -z.dot(y)).normalize(); const x = new THREE.Vector3().crossVectors(y, z).normalize(); obj.quaternion.setFromRotationMatrix(new THREE.Matrix4().makeBasis(x, y, z)); }
  return obj;
}
const _ARM = /arm|wrist|finger|metacarpal|clavicle|shoulder/;
/** Everything a body wears, and its skin, on the trunk and legs (not the arms), in the rest pose: [x, y, z, x, y, z, …]. */
export function trunkPoints(h) {
  if (h._trunk) return h._trunk; const out = [], bones = h.man.bones;
  for (const p of h.man.parts ?? []) { const sw = p.skinWeight, si = p.skinIndex; if (!sw || !si) continue;
    for (let i = 0; i < p.vcount; i++) { let b = 0; for (let c = 1; c < 4; c++) if (sw[i * 4 + c] > sw[i * 4 + b]) b = c; if (!_ARM.test(bones[si[i * 4 + b]].name)) out.push(p.pos[i * 3], p.pos[i * 3 + 1], p.pos[i * 3 + 2]); } }
  return (h._trunk = Float32Array.from(out));
}
const _BELLY = /^(root|spine0[1-5]|breast|pelvis|upperleg01)/;
/** The same for the trunk alone, from the tops of the thighs to the armpits: the part of the body that moves nearly as one with the hips. */
export function bellyPoints(h) {
  if (h._belly) return h._belly; const out = [], bones = h.man.bones;
  for (const p of h.man.parts ?? []) { const sw = p.skinWeight, si = p.skinIndex; if (!sw || !si) continue;
    for (let i = 0; i < p.vcount; i++) { let b = 0; for (let c = 1; c < 4; c++) if (sw[i * 4 + c] > sw[i * 4 + b]) b = c; if (_BELLY.test(bones[si[i * 4 + b]].name)) out.push(p.pos[i * 3], p.pos[i * 3 + 1], p.pos[i * 3 + 2]); } }
  return (h._belly = Float32Array.from(out));
}
/**
 * The belly and hips as a shape with an inside: level slices of bellyPoints, each the outline of the body (and its
 * clothes) round its own middle. Points alone cannot say that something is inside the body — a forearm lying deep in a
 * belly is far from every point of its surface.
 */
export function bellyShape(h) {
  if (h._bellyShape !== undefined) return h._bellyShape; const B = bellyPoints(h); if (!B.length) return (h._bellyShape = null);
  const dy = 0.02, NB = 32; let y0 = Infinity, y1 = -Infinity; for (let i = 1; i < B.length; i += 3) { y0 = Math.min(y0, B[i]); y1 = Math.max(y1, B[i]); }
  const n = Math.max(1, Math.ceil((y1 - y0) / dy)), box = Array.from({ length: n }, () => [Infinity, -Infinity, Infinity, -Infinity]), of = (y) => Math.min(n - 1, Math.max(0, Math.floor((y - y0) / dy)));
  for (let i = 0; i < B.length; i += 3) { const b = box[of(B[i + 1])]; b[0] = Math.min(b[0], B[i]); b[1] = Math.max(b[1], B[i]); b[2] = Math.min(b[2], B[i + 2]); b[3] = Math.max(b[3], B[i + 2]); }
  const cx = new Float32Array(n), cz = new Float32Array(n), R = new Float32Array(n * NB);
  for (let j = 0; j < n; j++) { const b = box[j]; cx[j] = b[0] < Infinity ? (b[0] + b[1]) / 2 : 0; cz[j] = b[2] < Infinity ? (b[2] + b[3]) / 2 : 0; }
  // (each point also counts for the slices next to its own and the directions next to its own: a slice of few points has no gaps)
  for (let i = 0; i < B.length; i += 3) { const j0 = of(B[i + 1]);
    for (let j = Math.max(0, j0 - 1); j <= Math.min(n - 1, j0 + 1); j++) { const x = B[i] - cx[j], z = B[i + 2] - cz[j], r = Math.hypot(x, z), k0 = Math.floor((Math.atan2(z, x) / (2 * Math.PI) + 0.5) * NB) % NB;
      for (const k of [(k0 + NB - 1) % NB, k0, (k0 + 1) % NB]) if (r > R[j * NB + k]) R[j * NB + k] = r; } }
  return (h._bellyShape = { y0, dy, n, NB, cx, cz, R });
}
/** How far a point (in the body's rest space) is outside the belly and hips: metres; negative inside; Infinity above or below them. */
export function bellyClear(S, x, y, z) {
  if (!S) return Infinity; const f = (y - S.y0) / S.dy; if (f < -1 || f > S.n) return Infinity;
  const j = Math.min(S.n - 1, Math.max(0, Math.floor(f))), dx = x - S.cx[j], dz = z - S.cz[j], a = (Math.atan2(dz, dx) / (2 * Math.PI) + 0.5) * S.NB - 0.5, k = Math.floor(a), t = a - k, NB = S.NB;
  return Math.hypot(dx, dz) - (S.R[j * NB + ((k % NB) + NB) % NB] * (1 - t) + S.R[j * NB + (((k + 1) % NB) + NB) % NB] * t);
}
/**
 * Where a scabbard rides at the left hip of a body as it is dressed: thrust through the sash, its mouth before the hip,
 * its length back, down and a little out past the hip — lying on the clothes, never through them, and the hilt far
 * enough before the belly for a hand to close on it. Found on the body itself: of everything worn (and the skin) that
 * the scabbard and the hilt pass, what stands furthest out sideways sets how far out their line lies.
 * Returns { pos (the mouth), dir (mouth → tip), edge (the way the blade's edge faces in it: up) } in the rest space.
 * o: out, down (degrees: how far the tip points out past the hip and down), len (the scabbard's length), hilt (the hilt's).
 */
export function scabbardRest(h, o = {}) {
  const lm = h.lm, k = h.height / 1.8, out = (o.out ?? 16) * Math.PI / 180, down = (o.down ?? 30) * Math.PI / 180, L = (o.len ?? 0.82) + 0.3, hilt = (o.hilt ?? 0.27) * k;
  const dir = V3(Math.sin(out) * Math.cos(down), -Math.sin(down), -Math.cos(out) * Math.cos(down)), N = V3(Math.cos(out), 0, Math.sin(out)), U = new THREE.Vector3().crossVectors(N, dir);
  const y0 = (lm.hipY ?? h.rest['upperleg01.L'].head.y) + 0.085, base = V3(0, y0, 0.05 * k), pts = trunkPoints(h), seat = 0.124 * k;
  // how far out sideways the line must lie for a mouth this far forward
  const sideAt = (bz) => {
    // (a body with nothing to measure — a bare skeleton: the width of the hips stands in for it)
    let side = pts.length ? -Infinity : h.rest['upperleg01.L'].head.x * 1.75 - 0.03;
    for (let i = 0; i < pts.length; i += 3) {
      const x = pts[i], y = pts[i + 1] - y0, z = pts[i + 2] - bz, t = x * dir.x + y * dir.y + z * dir.z, w = x * U.x + y * U.y + z * U.z, s = x * N.x + z * N.z;
      // (along its own length the scabbard needs its half thickness clear and a little air; the hilt, room for fingers;
      // and both are judged against what lies level with them — within a hand's breadth above or below their line)
      if (t > -0.03 && t < L && Math.abs(w) < 0.055 * k) side = Math.max(side, s + 0.03 * k);
      else if (t <= -0.03 && t > -hilt - 0.03 && Math.abs(w) < 0.07 * k) side = Math.max(side, s + 0.058 * k);
    }
    return side;
  };
  // The hilt stands before the belly far enough for the right forearm to come across to it: the belly's foremost
  // point at the height the forearm crosses, the forearm's own thickness and its sleeve, and the hand.
  let front = -Infinity; for (let i = 0; i < pts.length; i += 3) { const y = pts[i + 1] - y0; if (y > 0.02 * k && y < 0.2 * k && pts[i + 2] > front) front = pts[i + 2]; }
  let side = sideAt(base.z);
  if (front > -Infinity) for (let it = 0; it < 3; it++) { const gz = base.z + N.z * side - dir.z * seat, want = front + 0.09 * k; if (gz >= want - 1e-4) break; base.z += want - gz; side = sideAt(base.z); }
  const edge = V3(0, 1, 0).addScaledVector(dir, dir.y * -1).normalize();
  return { pos: base.addScaledVector(N, side), dir, edge };
}
/**
 * Wear a sword at the left hip: a scabbard made to its blade, hung where scabbardRest says, the sword in it up to the
 * guard. The scabbard can be turned about its own line and drawn back along it (a.scabbardSet), as the left hand does
 * to it in a draw.
 */
export function wearSword(a, sw, o = {}) {
  const h = a.h, k = h.height / 1.8, len = sw.userData.len ?? 0.74, R = scabbardRest(h, { len, ...o }), sc = a.props.scabbard = P.scabbard(len + 0.04);
  attachRest(h, sc, 'root', R.pos, R.dir, R.edge);
  // seat: how far before the scabbard's mouth the middle of the sword's grip lies when it is home (the guard on the mouth)
  sc.userData = { ...sc.userData, len: len + 0.04, seat: 0.124 * k, pos0: sc.position.clone(), q0: sc.quaternion.clone() };
  a.props.sword = sw; a.swordAt('hip'); return sc;
}
/** grip transform for the hand: returns {pos, y (blade direction), z (edge direction)} in rest space */
export function gripOf(h, side) { const H = h.hand[side], R = h.rest; const w = R[`wrist.${side}`].head; const k = h.height / 1.8; const pos = w.clone().addScaledVector(H.fwd, 0.085 * k).addScaledVector(H.back, -0.03 * k); const y = H.across.clone().multiplyScalar(-1); return { pos, y, z: H.fwd.clone() }; }

export class Actor {
  constructor(h, mo, name) { this.h = h; this.mo = mo; this.name = name; this.sims = []; this.props = {}; this.wind = V3(0, 0, 0); this.root = h.root; this.simOn = true; }
  /** call once per frame after posing */
  step(t, dt = 1 / 24) { this.h.root.updateMatrixWorld(true); if (!this.simOn) return; for (const s of this.sims) { s.wind = this.wind; s.step(t, dt); } }
  add(scene) { scene.add(this.h.root); for (const s of this.sims) scene.add(s.mesh); return this; }
  remove(scene) { scene.remove(this.h.root); for (const s of this.sims) scene.remove(s.mesh); }
  set visible(v) { this.h.root.visible = v; for (const s of this.sims) s.mesh.visible = v; }
  place(x, y, z, yaw = 0, scale = 1) { this.h.root.position.set(x, y, z); this.h.root.rotation.set(0, yaw, 0); this.h.root.scale.setScalar(scale); return this; }
  resetSims() { for (const s of this.sims) s.built = false, s.v.cons.length = 0; }
  /** hold a prop in a hand: fingers curl around it */
  hold(obj, side = 'R', o = {}) { (this.held ??= {})[side] = obj; const g = gripOf(this.h, side); attachRest(this.h, obj, `wrist.${side}`, g.pos, o.flip ? g.y.clone().negate() : g.y, g.z); if (o.tilt) obj.rotateZ(o.tilt); if (o.pitch) obj.rotateX(o.pitch); if (o.shift) obj.translateY(o.shift); return obj; }
  grip(side = 'R', amt = 0.97) { this.h.fingers(side, amt, 0.8); }
  /** blade tip / base in world space */
  blade(prop = 'sword') { const s = this.props[prop]; s.updateWorldMatrix(true, false); return { tip: s.localToWorld(s.userData.tip.clone()), base: s.localToWorld(s.userData.base.clone()) }; }
}

const EXTRAS = {
  orez(a, o) { const h = a.h, lm = h.lm, k = lm.kk || 1;
    a.sims.push(new Tail({ human: h, bone: 'head', pos: V3(0, lm.crown[1] - 0.012, lm.crown[2] - 0.035), dir: V3(0, -0.25, -1), length: 0.6, radius: 0.036, segments: 14, seed: 2, weight: 0.8, stiff: 0.25 }));
    if (o.cloak !== false) { const n = 9, anchors = []; for (let i = 0; i < n; i++) { const u = i / (n - 1) * 2 - 1; const bone = u < -0.45 ? 'clavicle.R' : u > 0.45 ? 'clavicle.L' : 'spine02'; anchors.push({ bone, pos: V3(u * 0.215, lm.neckY - 0.012 - Math.abs(u) * 0.045, -0.085 + Math.abs(u) * 0.075) }); }
      a.cloak = new Cloth({ human: h, cols: n, rows: 13, length: 1.0, width: 0.5, anchors, color: [0.42, 0.045, 0.05], worn: 0.9, flare: 1.5, seed: 4, drag: 1.5 }); a.sims.push(a.cloak); }
    const sw = a.props.sword = P.sword({}); const pin = a.props.pin = P.crescent(0.9); pin.position.set(0, -0.15, 0.012); pin.rotation.z = Math.PI; sw.add(pin);
    wearSword(a, sw); },
  teen(a) { const h = a.h, lm = h.lm; a.sims.push(new Tail({ human: h, bone: 'head', pos: V3(0, lm.crown[1] - 0.012, lm.crown[2] - 0.03), dir: V3(0, -0.2, -1), length: 0.3, radius: 0.03, segments: 9, seed: 3, stiff: 0.3 })); const sw = a.props.sword = P.sword({ wood: true, length: 0.7 }); a.hold(sw, 'R'); },
  boy(a) { const sw = a.props.sword = P.sword({ wood: true, length: 0.5 }); a.hold(sw, 'R'); sw.visible = false; },
  sena(a) { const h = a.h, lm = h.lm; const pin = a.props.pin = P.crescent(1.0); attachRest(h, pin, 'head', V3(0.075, lm.eyeL[1] + 0.05, -0.05), V3(0.3, 1, -0.2), V3(1, 0, 0)); },
  ruon(a) { const st = a.props.staff = P.staff(1.75); a.hold(st, 'R', { shift: 0.25 }); },
  elder(a) { const st = a.props.staff = P.staff(1.35); a.hold(st, 'R', { shift: 0.3 }); },
  garrick(a, o) { const h = a.h, lm = h.lm, k = h.height / 1.8; for (const s of ['L', 'R']) { const p = P.pauldron({ k: 1.0 * k, color: [0.26, 0.26, 0.29] }); attachRest(h, p, `clavicle.${s}`, h.rest[`upperarm01.${s}`].head.clone().add(V3(0, 0.03, 0))); if (s === 'R') p.scale.x = -1; }
    const n = 9, anchors = []; for (let i = 0; i < n; i++) { const u = i / (n - 1) * 2 - 1; anchors.push({ bone: u < -0.45 ? 'clavicle.R' : u > 0.45 ? 'clavicle.L' : 'spine02', pos: V3(u * 0.27 * k, lm.neckY - 0.03 - Math.abs(u) * 0.05, -0.11 * k + Math.abs(u) * 0.08) }); }
    a.cloak = new Cloth({ human: h, cols: n, rows: 14, length: 1.35 * k, width: 0.6, anchors, color: [0.10, 0.02, 0.025], worn: 0.5, flare: 1.6, seed: 9, drag: 1.2 }); a.sims.push(a.cloak);
    const sw = a.props.sword = P.sword({ big: true, length: 1.05, curve: 0.0, color: [0.4, 0.4, 0.43], grip: [0.12, 0.03, 0.03] }); a.hold(sw, 'R'); },
  vess(a) { soldierKit(a, { crest: true, cape: [0.40, 0.05, 0.05], sword: true }); },
  soldier(a, o) { soldierKit(a, o); },
  kaito(a) { soldierKit(a, { cape: [0.05, 0.14, 0.2], sword: true, noHelmet: true }); },
  aldric(a) { const h = a.h, lm = h.lm, k = h.height / 1.8; const fur = P.furCollar([0.22, 0.2, 0.19], k); attachRest(h, fur, 'spine01', V3(0, lm.neckY + 0.005, -0.015)); a.props.fur = fur; fur.visible = false; const cr = new THREE.Mesh(new THREE.CylinderGeometry(0.098, 0.092, 0.05, 16, 1, true), new THREE.MeshStandardMaterial({ color: 0x3a3a40, metalness: 1, roughness: 0.35, side: THREE.DoubleSide })); attachRest(h, cr, 'head', V3(0, lm.headTop - 0.035, 0.02)); for (let i = 0; i < 8; i++) { const sp = new THREE.Mesh(new THREE.ConeGeometry(0.012, 0.05, 4), cr.material); const an = i / 8 * 6.283; sp.position.set(Math.cos(an) * 0.095, 0.045, Math.sin(an) * 0.095); cr.add(sp); } },
};
function soldierKit(a, o = {}) { const h = a.h, lm = h.lm, k = h.height / 1.8;
  if (!o.noHelmet) { const hm = a.props.helmet = P.helmet({ k: 1.02 * (lm.kk || 1), crest: o.crest }); hm.name = 'helmet'; attachRest(h, hm, 'head', V3(0, lm.eyeL[1] + 0.078 * (lm.kk || 1), 0.03)); if (h.meshes.haircap) h.meshes.haircap.visible = false; }
  for (const s of ['L', 'R']) { const p = P.pauldron({ k: 0.85 * k }); p.name = 'pauldron'; attachRest(h, p, `clavicle.${s}`, h.rest[`upperarm01.${s}`].head.clone().add(V3(0, 0.02, 0))); if (s === 'R') p.scale.x = -1; }
  if (o.cape) { const n = 7, anchors = []; for (let i = 0; i < n; i++) { const u = i / (n - 1) * 2 - 1; anchors.push({ bone: u < -0.45 ? 'clavicle.R' : u > 0.45 ? 'clavicle.L' : 'spine02', pos: V3(u * 0.2 * k, lm.neckY - 0.03 - Math.abs(u) * 0.04, -0.1 * k + Math.abs(u) * 0.07) }); } a.cloak = new Cloth({ human: h, cols: n, rows: 10, length: 0.85 * k, width: 0.5, anchors, color: o.cape, worn: 0.4, flare: 1.4, seed: 7 }); a.sims.push(a.cloak); }
  if (o.sword) { const sw = a.props.sword = P.sword({ length: 0.72, curve: 0.0, color: [0.5, 0.5, 0.53] }); a.hold(sw, 'R'); }
  else if (o.spear !== false) { const sp = a.props.spear = P.spear(2.4); sp.name = 'spear'; a.hold(sp, 'R', { shift: 0.2 }); }
  if (o.shield !== false && !o.sword) { const sh = a.props.shield = P.shield(0.4 * k); sh.name = 'shield'; const R = h.rest; attachRest(h, sh, 'lowerarm01.L', R['lowerarm01.L'].head.clone().lerp(R['wrist.L'].head, 0.6).add(h.hand.L.back.clone().multiplyScalar(0.06)), h.hand.L.fwd.clone().negate(), h.hand.L.back); }
}
Actor.prototype.swordAt = function (where) { const sw = this.props.sword; if (!sw) return; this.swordPlace = where; if (sw.parent) sw.parent.remove(sw); sw.visible = where !== 'none';
  if (where === 'hand' || where === 'handL') this.hold(sw, where === 'handL' ? 'L' : 'R'); else if (where === 'hip') { const sc = this.props.scabbard; sc.add(sw); sw.rotation.set(0, 0, 0); sw.quaternion.identity(); sw.position.set(0, -(sc.userData.seat ?? 0.124), 0); } };
/**
 * The scabbard as the left hand has it in a draw: turned about its own line by `turn` (radians) and drawn back along
 * it by `back` (metres), from where it hangs. (0, 0) is where it hangs.
 */
Actor.prototype.scabbardSet = function (turn = 0, back = 0) { const sc = this.props.scabbard, u = sc?.userData; if (!u?.q0) return;
  sc.quaternion.copy(u.q0); if (turn) sc.quaternion.multiply(_qs.setFromAxisAngle(_ys, turn)); sc.position.copy(u.pos0); if (back) sc.position.addScaledVector(_vs.set(0, 1, 0).applyQuaternion(u.q0), back); };
const _qs = new THREE.Quaternion(), _ys = new THREE.Vector3(0, 1, 0), _vs = new THREE.Vector3();

/** kits: named sets of props, capes and hair tails (KITS.orez, KITS.soldier, ...) applied to an actor */
export const KITS = EXTRAS;
/** data: CharData from the character factory; mocap: the engine's Mocap; o.kit: a name in KITS; o.look: look overrides */
export function makeActor(name, data, mocap, o = {}) {
  const h = new Human(data, o); const mo = new Motion(h, mocap); const a = new Actor(h, mo, name);
  const ex = o.kit ? EXTRAS[o.kit] : null; if (ex) ex(a, o); return a;
}
export { relax, ik2 };
