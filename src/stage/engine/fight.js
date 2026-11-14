// Fight kit: named sword positions, swings between them, two-handed grips, falls and hit-stop timing.
import { THREE, V3, clamp, lerp, ease, smooth } from './core.js';
import { reach, twist, pitch } from './shotkit.js';
import { pointProp, gripPoint } from './crowd.js';
const K = (h, d, e = [0, 0, 1]) => ({ h: V3(...h), d: V3(...d).normalize(), e: V3(...e) });
/** sword positions in the fighter's own space (x = their left, y up, z forward), for a 1.8 m body: h = right hand, d = blade direction */
export const SW = {
  guard: K([-0.06, 1.02, 0.36], [0.1, 0.55, 0.85]), low: K([-0.24, 0.84, 0.2], [0.15, -0.6, 0.78]), side: K([-0.3, 0.86, 0.04], [-0.05, -0.82, 0.57]),
  high: K([-0.14, 1.84, 0.1], [0.12, 0.86, -0.5]), hiR: K([-0.38, 1.66, 0.04], [-0.35, 0.8, -0.5]), midF: K([-0.04, 1.2, 0.5], [0.2, 0.2, 1]), loL: K([0.3, 0.78, 0.3], [0.75, -0.45, 0.5]),
  backR: K([-0.52, 1.15, -0.04], [-1, 0.1, 0.0], [0, 0, 1]), fwd: K([0.0, 1.15, 0.52], [0, 0.06, 1], [1, 0, 0]), outL: K([0.46, 1.12, 0.1], [1, 0.12, -0.1], [0, 0, -1]),
  chamber: K([-0.16, 1.14, 0.14], [0, 0.04, 1]), thrust: K([-0.04, 1.22, 0.7], [0, 0.02, 1]), parry: K([0.12, 1.52, 0.36], [1, 0.22, 0.1], [0, 1, 0]), block: K([-0.1, 1.25, 0.4], [0.5, 0.85, 0.15]),
  hiL: K([0.3, 1.5, 0.02], [0.4, 0.8, -0.45]), loR: K([-0.4, 0.76, 0.24], [-0.75, -0.45, 0.5]), point: K([-0.1, 1.32, 0.6], [0.05, -0.02, 1]), overhead: K([-0.1, 1.9, -0.04], [0.05, 0.6, -0.8]), smash: K([-0.02, 0.85, 0.55], [0.05, -0.35, 0.95]),
  hip: K([0.2, 0.98, 0.16], [-0.1, 0.15, 1]), drawn: K([-0.3, 1.2, 0.45], [-0.6, 0.5, 0.6]), rest: K([-0.26, 0.9, 0.1], [0.5, -0.55, 0.65]),
};
// From one direction to another by way of the front: seen from straight behind (where neither a sword hand nor a
// blade's point passes) the directions are flattened onto a plane, carried straight across it, and lifted back. On the
// body that is an arc that bulges forward — a hand goes round its shoulder, a point comes through the front in a cut —
// and nothing whips: two directions nearly opposite each other still have one way between them, taken at a steady pace.
const _flat = (u) => { const w = Math.max(1 + u.z, 1e-3); return [u.x / w, u.y / w]; };
const _round = (A, B, k, out) => { const a = _flat(A), b = _flat(B), X = a[0] + (b[0] - a[0]) * k, Y = a[1] + (b[1] - a[1]) * k, s = X * X + Y * Y; return out.set(2 * X, 2 * Y, 1 - s).multiplyScalar(1 / (1 + s)); };
const _SH = V3(-0.2, 1.42, 0); // the sword arm's shoulder, for the 1.8 m body the positions are written for
/**
 * sequence of [time, poseName] -> interpolated {h, d, e, v}; pass a pose name alone for a hold.
 * Between two positions the hand travels round the shoulder in an arc (its direction from the shoulder by way of the
 * front, its distance evenly), and the blade turns by way of the front too.
 */
export function swordAt(t, seq, e = ease.io) { if (typeof seq === 'string') return SW[seq]; let i = 0; while (i < seq.length - 2 && t > seq[i + 1][0]) i++; const [t0, a] = seq[i], [t1, b] = seq[Math.min(i + 1, seq.length - 1)]; const A = SW[a], B = SW[b]; const u = t1 > t0 ? clamp((t - t0) / (t1 - t0)) : 1; const k = (seq[i + 1] && seq[i + 1][2]) ? seq[i + 1][2](u) : e(u);
  const ua = A.h.clone().sub(_SH), ub = B.h.clone().sub(_SH), ra = ua.length(), rb = ub.length();
  const h = ra < 1e-6 || rb < 1e-6 ? A.h.clone().lerp(B.h, k) : _round(ua.multiplyScalar(1 / ra), ub.multiplyScalar(1 / rb), k, new THREE.Vector3()).multiplyScalar(lerp(ra, rb, k)).add(_SH);
  return { h, d: _round(A.d, B.d, k, new THREE.Vector3()).normalize(), e: A.e.clone().lerp(B.e, k), v: u > 0 && u < 1 ? 1 : 0 }; }
const _REF = V3(-0.6, -0.3, 0.75).normalize(), _E0 = (() => { const e = V3(-0.5, -0.8, -0.35); return e.addScaledVector(_REF, -e.dot(_REF)).normalize(); })();
/**
 * Where the elbow goes for a hand at `target` (a direction for reach's pole). One rule for every place a hand can be:
 * the elbow's direction for a hand held low in front of its own shoulder (down, out and back), carried round with
 * the arm by the shortest turn. So the elbow is behind a hanging arm, below a thrust, out to the side under a raised
 * hand — and it never flips on the way from one to another: a fixed direction flips when the arm comes to lie along it
 * (it did, with the hand raised). The one place this rule has no answer is behind the other shoulder blade.
 */
export function elbowPole(a, side, target) {
  const m = side === 'L' ? -1 : 1, q = a.root.quaternion; a.h.root.updateMatrixWorld(true);
  const ref = V3(_REF.x * m, _REF.y, _REF.z).applyQuaternion(q), e0 = V3(_E0.x * m, _E0.y, _E0.z).applyQuaternion(q), dir = target.clone().sub(a.h.wpos(`upperarm01.${side}`, new THREE.Vector3()));
  return dir.lengthSq() < 1e-10 ? e0 : e0.applyQuaternion(new THREE.Quaternion().setFromUnitVectors(ref, dir.normalize()));
}
/**
 * Bring a hand to a grip: its grip (the middle of what it holds) to a point of the world, what it holds along `dir`.
 * The hand lies on its forearm the way a hand can (pointProp), so where the wrist must be is found in a few rounds:
 * reach, turn the hand, see where the grip lies from the wrist, and send the wrist to where that puts the grip right.
 * An arm near the end of its reach gives: over the last 7% it comes out ever more slowly, and is straight only well
 * beyond what it can reach (sent exactly to the edge of its reach, an elbow snaps open over the last centimetre).
 * pole: null = where an elbow goes (elbowPole). prop: null = the hand only (a second hand on a hilt).
 */
export function holdAt(a, side, grip, dir, edge, pole = null, prop = undefined) {
  const h = a.h, k = h.height / 1.8 * a.root.scale.y, R = h.rest, U = `upperarm01.${side}`, F = `lowerarm01.${side}`, Wr = `wrist.${side}`;
  const L = (R[U].head.distanceTo(R[F].head) + R[F].head.distanceTo(R[Wr].head)) * h.root.scale.y, d0 = L * 0.93, give = L - d0;
  const T = grip.clone().addScaledVector(dir, -0.05 * k), W = new THREE.Vector3(), sh = new THREE.Vector3(), g = new THREE.Vector3(), w = new THREE.Vector3();
  for (let it = 0; it < 5; it++) {
    h.root.updateMatrixWorld(true); h.wpos(U, sh); W.copy(T).sub(sh); const d = W.length(); if (d > d0) W.multiplyScalar((L - give * Math.exp(-(d - d0) / give)) / d); W.add(sh);
    reach(a, side, W, pole || elbowPole(a, side, W)); if (!it) a.grip(side); pointProp(a, side, dir, edge, { prop });
    if (it < 4) T.copy(grip).sub(gripPoint(a, side, g)).add(h.wpos(Wr, w));
  }
}
/** put the sword (right hand, optional left hand on the hilt) at a pose. o: two, twist, prop */
export function applySword(a, P, o = {}) { const h = a.h, k = h.height / 1.8 * a.root.scale.y; const q = a.root.quaternion; const tw = clamp(P.h.x * 0.9 - 0.25 * clamp(P.h.x / 0.15, -1, 1) * clamp(-P.h.z / 0.12), -0.75, 0.75) * (o.twist ?? 1); // (every part of it eases in: a chest does not jump when the hand passes behind the shoulders) twist(a, 'spine05', tw * 0.35); twist(a, 'spine03', tw * 0.45); if (o.lean) pitch(a, 'spine05', o.lean);
  const hand = P.h.clone().multiplyScalar(k).applyQuaternion(q).add(a.root.position); if (o.shift) hand.add(o.shift); const dir = P.d.clone().applyQuaternion(q).normalize(); const edge = P.e.clone().applyQuaternion(q);
  // the right hand takes the hilt where the position says; a second hand takes it below, towards the pommel
  holdAt(a, 'R', hand.clone().addScaledVector(dir, -0.02 * k), dir, edge);
  if (o.two) holdAt(a, 'L', hand.clone().addScaledVector(dir, -0.12 * k), dir, edge, null, null);
  return { hand, dir }; }
/** fall backwards (reverse of a get-up) starting at t0; returns 0..1 progress. o: dur, push (m), dir (world V3), face-down: prone */
export function fall(a, t, t0, o = {}) { const u = clamp((t - t0) / (o.dur ?? 1.1)); const prone = !!o.prone; const clip = prone ? '139_16' : '139_18'; const c0 = prone ? 4.7 : 6.0, c1 = prone ? 1.2 : 1.0; const k = ease.out(u);
  a.mo.apply(clip, lerp(c0, c1, 1 - Math.pow(1 - u, 1.6)), { inPlace: true, face: prone ? 5.0 : 6.6 }); if (o.push) { const d = o.dir || V3(0, 0, -1).applyQuaternion(a.root.quaternion); a.root.position.addScaledVector(d, o.push * k); } return u; }
/** hit-stop: animation time that freezes for `hold` seconds at each hit time */
export function hitStop(t, hits) { let w = t; for (const [ti, hold] of hits) { if (t > ti) w -= Math.min(hold, t - ti); } return w; }
/** recoil of a body struck at time ti: returns 0..1 envelope */
export const struck = (t, ti, len = 0.5) => (t < ti ? 0 : Math.exp(-(t - ti) / len * 3) * smooth(ti, ti + 0.05, t));
