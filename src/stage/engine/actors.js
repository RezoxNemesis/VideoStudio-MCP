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
    const sw = a.props.sword = P.sword({}); const sc = a.props.scabbard = P.scabbard(); const pin = a.props.pin = P.crescent(0.9); pin.position.set(0, -0.15, 0.012); pin.rotation.z = Math.PI; sw.add(pin);
    attachRest(h, sc, 'root', V3(0.15, lm.hipY + 0.085, 0.04), V3(-0.12, -0.45, -0.88)); a.swordAt('hip'); },
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
  if (where === 'hand' || where === 'handL') this.hold(sw, where === 'handL' ? 'L' : 'R'); else if (where === 'hip') { const sc = this.props.scabbard; sc.add(sw); sw.position.set(0, -0.13, 0); sw.rotation.set(0, 0, 0); sw.quaternion.identity(); sw.position.y = -0.02; } };

/** kits: named sets of props, capes and hair tails (KITS.orez, KITS.soldier, ...) applied to an actor */
export const KITS = EXTRAS;
/** data: CharData from the character factory; mocap: the engine's Mocap; o.kit: a name in KITS; o.look: look overrides */
export function makeActor(name, data, mocap, o = {}) {
  const h = new Human(data, o); const mo = new Motion(h, mocap); const a = new Actor(h, mo, name);
  const ex = o.kit ? EXTRAS[o.kit] : null; if (ex) ex(a, o); return a;
}
export { relax, ik2 };
