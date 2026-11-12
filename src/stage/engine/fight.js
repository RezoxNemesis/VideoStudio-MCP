// Fight kit: named sword positions, swings between them, two-handed grips, falls and hit-stop timing.
import { THREE, V3, clamp, lerp, ease, smooth } from './core.js';
import { reach, twist, pitch } from './shotkit.js';
import { pointProp } from './crowd.js';
const K = (h, d, e = [0, 0, 1]) => ({ h: V3(...h), d: V3(...d).normalize(), e: V3(...e) });
/** sword positions in the fighter's own space (x = their left, y up, z forward), for a 1.8 m body: h = right hand, d = blade direction */
export const SW = {
  guard: K([-0.06, 1.02, 0.36], [0.1, 0.55, 0.85]), low: K([-0.24, 0.84, 0.2], [0.15, -0.6, 0.78]), side: K([-0.3, 0.86, 0.04], [-0.05, -0.92, 0.38]),
  high: K([-0.12, 1.58, 0.02], [0.15, 0.9, -0.35]), hiR: K([-0.34, 1.56, -0.04], [-0.35, 0.8, -0.5]), midF: K([-0.04, 1.2, 0.5], [0.2, 0.2, 1]), loL: K([0.3, 0.78, 0.3], [0.75, -0.45, 0.5]),
  backR: K([-0.52, 1.15, -0.04], [-1, 0.1, 0.0], [0, 0, 1]), fwd: K([0.0, 1.15, 0.52], [0, 0.06, 1], [1, 0, 0]), outL: K([0.46, 1.12, 0.1], [1, 0.12, -0.1], [0, 0, -1]),
  chamber: K([-0.16, 1.14, 0.14], [0, 0.04, 1]), thrust: K([-0.04, 1.22, 0.7], [0, 0.02, 1]), parry: K([0.12, 1.52, 0.36], [1, 0.22, 0.1], [0, 1, 0]), block: K([-0.1, 1.25, 0.4], [0.5, 0.85, 0.15]),
  hiL: K([0.3, 1.5, 0.02], [0.4, 0.8, -0.45]), loR: K([-0.4, 0.76, 0.24], [-0.75, -0.45, 0.5]), point: K([-0.1, 1.32, 0.6], [0.05, -0.02, 1]), overhead: K([-0.05, 1.72, -0.1], [0.05, 0.6, -0.8]), smash: K([-0.02, 0.85, 0.55], [0.05, -0.35, 0.95]),
  hip: K([0.2, 0.98, 0.16], [-0.1, 0.15, 1]), drawn: K([-0.3, 1.2, 0.45], [-0.6, 0.5, 0.6]), rest: K([-0.26, 0.9, 0.1], [0.5, -0.55, 0.65]),
};
/** sequence of [time, poseName] -> interpolated {h, d, e, v}; pass a pose name alone for a hold */
export function swordAt(t, seq, e = ease.io) { if (typeof seq === 'string') return SW[seq]; let i = 0; while (i < seq.length - 2 && t > seq[i + 1][0]) i++; const [t0, a] = seq[i], [t1, b] = seq[Math.min(i + 1, seq.length - 1)]; const A = SW[a], B = SW[b]; const u = t1 > t0 ? clamp((t - t0) / (t1 - t0)) : 1; const k = (seq[i + 1] && seq[i + 1][2]) ? seq[i + 1][2](u) : e(u);
  return { h: A.h.clone().lerp(B.h, k), d: A.d.clone().lerp(B.d, k).normalize(), e: A.e.clone().lerp(B.e, k), v: u > 0 && u < 1 ? 1 : 0 }; }
/** put the sword (right hand, optional left hand on the hilt) at a pose. o: two, twist, prop */
export function applySword(a, P, o = {}) { const h = a.h, k = h.height / 1.8 * a.root.scale.y; const q = a.root.quaternion; const tw = clamp(P.h.x * 0.9 - (P.h.z < 0 ? 0.25 * Math.sign(P.h.x || -1) : 0), -0.75, 0.75) * (o.twist ?? 1); twist(a, 'spine05', tw * 0.35); twist(a, 'spine03', tw * 0.45); if (o.lean) pitch(a, 'spine05', o.lean);
  const hand = P.h.clone().multiplyScalar(k).applyQuaternion(q).add(a.root.position); if (o.shift) hand.add(o.shift); const dir = P.d.clone().applyQuaternion(q); const edge = P.e.clone().applyQuaternion(q);
  reach(a, 'R', hand.clone().addScaledVector(dir, -0.07 * k), V3(-0.6, -0.7, -0.4).applyQuaternion(q)); a.grip('R'); pointProp(a, 'R', dir, edge);
  if (o.two) { reach(a, 'L', hand.clone().addScaledVector(dir, -0.2 * k), V3(0.6, -0.7, -0.3).applyQuaternion(q)); a.grip('L'); }
  return { hand, dir }; }
/** fall backwards (reverse of a get-up) starting at t0; returns 0..1 progress. o: dur, push (m), dir (world V3), face-down: prone */
export function fall(a, t, t0, o = {}) { const u = clamp((t - t0) / (o.dur ?? 1.1)); const prone = !!o.prone; const clip = prone ? '139_16' : '139_18'; const c0 = prone ? 4.7 : 6.0, c1 = prone ? 1.2 : 1.0; const k = ease.out(u);
  a.mo.apply(clip, lerp(c0, c1, 1 - Math.pow(1 - u, 1.6)), { inPlace: true, face: prone ? 5.0 : 6.6 }); if (o.push) { const d = o.dir || V3(0, 0, -1).applyQuaternion(a.root.quaternion); a.root.position.addScaledVector(d, o.push * k); } return u; }
/** hit-stop: animation time that freezes for `hold` seconds at each hit time */
export function hitStop(t, hits) { let w = t; for (const [ti, hold] of hits) { if (t > ti) w -= Math.min(hold, t - ti); } return w; }
/** recoil of a body struck at time ti: returns 0..1 envelope */
export const struck = (t, ti, len = 0.5) => (t < ti ? 0 : Math.exp(-(t - ti) / len * 3) * smooth(ti, ti + 0.05, t));
