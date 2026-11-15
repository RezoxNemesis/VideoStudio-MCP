// Fight kit: named sword positions, swings between them, two-handed grips, falls and hit-stop timing.
import { THREE, V3, clamp, lerp, ease, smooth } from './core.js';
import { reach, twist, pitch } from './shotkit.js';
import { pointProp, gripPoint } from './crowd.js';
import { bellyShape, bellyClear } from './actors.js';
const K = (h, d, e = [0, 0, 1]) => ({ h: V3(...h), d: V3(...d).normalize(), e: V3(...e) });
/** sword positions in the fighter's own space (x = their left, y up, z forward), for a 1.8 m body: h = right hand, d = blade direction */
export const SW = {
  guard: K([-0.06, 1.02, 0.36], [0.1, 0.55, 0.85]), low: K([-0.24, 0.84, 0.2], [0.15, -0.6, 0.78]), side: K([-0.3, 0.86, 0.04], [-0.05, -0.82, 0.57]),
  high: K([-0.14, 1.84, 0.1], [0.12, 0.86, -0.5]), hiR: K([-0.38, 1.66, 0.04], [-0.35, 0.8, -0.5]), midF: K([-0.04, 1.2, 0.5], [0.2, 0.2, 1]), loL: K([0.3, 0.78, 0.3], [0.75, -0.45, 0.5]),
  backR: K([-0.52, 1.15, -0.04], [-1, 0.1, 0.0], [0, 0, 1]), fwd: K([0.0, 1.15, 0.52], [0, 0.06, 1], [1, 0, 0]), outL: K([0.46, 1.12, 0.1], [1, 0.12, -0.1], [0, 0, -1]),
  chamber: K([-0.16, 1.14, 0.14], [0, 0.04, 1]), thrust: K([-0.04, 1.22, 0.7], [0, 0.02, 1]), parry: K([0.12, 1.52, 0.36], [1, 0.22, 0.1], [0, 1, 0]), block: K([-0.1, 1.25, 0.4], [0.5, 0.85, 0.15]),
  hiL: K([0.3, 1.5, 0.02], [0.4, 0.8, -0.45]), loR: K([-0.4, 0.76, 0.24], [-0.75, -0.45, 0.5]), point: K([-0.1, 1.32, 0.6], [0.05, -0.02, 1]), overhead: K([-0.1, 1.9, -0.04], [0.05, 0.6, -0.8]), smash: K([-0.02, 0.85, 0.55], [0.05, -0.35, 0.95]),
  hip: K([0.2, 0.98, 0.16], [-0.1, 0.15, 1]), drawn: K([-0.3, 1.2, 0.45], [-0.6, 0.5, 0.6]), rest: K([-0.26, 0.9, 0.1], [0.5, -0.55, 0.65]),
  // the hand on the hilt of the sword in its scabbard at the left hip (the blade down the scabbard: back, down and
  // a little out) — where a draw begins; and the blade drawn out along the scabbard's own line as far as the arm takes
  // it, still pointing down it. These two are the standard body's: a fighter who wears a scabbard has their own, read
  // from where it hangs on them (scabbardKeys), and a sword sequence played on them (playSword) uses those.
  sheath: K([0.136, 1.092, 0.183], [0.239, -0.5, -0.832], [1, 0, 0]),
  unsheath: K([0.037, 1.3, 0.528], [0.239, -0.5, -0.832], [1, 0, 0]),
  // the way out of the scabbard (and back to it), round the front — never through the body: the blade swung out
  // level to the left as its point leaves the scabbard's mouth, then the draw-cut carried across the front to its end
  // (the arm out to the right, the blade level, its point forward and a little in)
  drawOut: K([0.03, 1.27, 0.5], [1, -0.1, 0.05], [0, 0, 1]), drawCut: K([-0.5, 1.28, 0.3], [0.3, 0, 0.95], [-1, 0, 0]),
};
// What lies between a sword in its scabbard and any other position, in order, with the share of the way each step is.
const _WAY = ['sheath', 'unsheath', 'drawOut', 'drawCut'], _SHARE = [0.3, 0.18, 0.24, 0.28];
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
export function swordAt(t, seq, e = ease.io, own = null) { const at = (n) => (own && own[n]) || SW[n];
  if (typeof seq === 'string') { const P = at(seq); return own ? { h: P.h, d: P.d, e: P.e, v: 0, from: seq, to: seq, k: 0 } : P; }
  let i = 0; while (i < seq.length - 2 && t > seq[i + 1][0]) i++; const [t0, a0] = seq[i], [t1, b0] = seq[Math.min(i + 1, seq.length - 1)]; const u = t1 > t0 ? clamp((t - t0) / (t1 - t0)) : 1; let k = (seq[i + 1] && seq[i + 1][2]) ? seq[i + 1][2](u) : e(u), a = a0, b = b0;
  // A sword leaves its scabbard — and goes home to it — by one way only: out along the scabbard's line, swung out to
  // the left, carried across the front. Between a place on that way and any other position, the rest of the way is
  // gone first (or last), each step its share of the time: a blade is never taken from the hip through the body.
  if (own) { const ia = _WAY.indexOf(a0), ib = _WAY.indexOf(b0), out = ia >= 0 && ia < 3 && ib < 0, home = ib >= 0 && ib < 3 && ia < 0;
    if (out || home) { const j0 = out ? ia : ib, names = _WAY.slice(j0), sh = _SHARE.slice(j0), tot = sh.reduce((x, y) => x + y, 0); let kk = (out ? k : 1 - k) * tot, j = 0; while (j < sh.length - 1 && kk > sh[j]) { kk -= sh[j]; j++; }
      const n0 = names[j], n1 = j + 1 < names.length ? names[j + 1] : (out ? b0 : a0), kl = clamp(kk / sh[j]); if (out) { a = n0; b = n1; k = kl; } else { a = n1; b = n0; k = 1 - kl; } } }
  const A = at(a), B = at(b);
  // along the scabbard's own line a blade goes straight (in or out): no arc
  if (own && own[a] && own[b]) return { h: A.h.clone().lerp(B.h, k), d: A.d.clone(), e: A.e.clone(), v: u > 0 && u < 1 ? 1 : 0, from: a, to: b, k };
  const ua = A.h.clone().sub(_SH), ub = B.h.clone().sub(_SH), ra = ua.length(), rb = ub.length();
  const h = ra < 1e-6 || rb < 1e-6 ? A.h.clone().lerp(B.h, k) : _round(ua.multiplyScalar(1 / ra), ub.multiplyScalar(1 / rb), k, new THREE.Vector3()).multiplyScalar(lerp(ra, rb, k)).add(_SH);
  return { h, d: _round(A.d, B.d, k, new THREE.Vector3()).normalize(), e: A.e.clone().lerp(B.e, k), v: u > 0 && u < 1 ? 1 : 0, from: a, to: b, k }; }
/** How far the chest is turned to the left in a draw (radians): with the right hand on the hilt, and when the blade has slid out. */
const DRAW_CHEST = [0.3, 0.5];
/**
 * The sword positions that belong to a fighter's own scabbard, in the terms of SW (their own space, a 1.8 m body), read
 * from where the scabbard hangs on this body now:
 *   sheath — the hand on the hilt of the sword in its scabbard; unsheath — the hand as far up the scabbard's own line
 *   as the arm draws the blade along it.
 * With them: slide (how far the hand travels from one to the other), back (how far the left hand draws the scabbard
 * back along its line meanwhile — both in the body's own metres), turn (radians: how far the left hand turns the
 * scabbard, so that the blade leaves it flat, its edge outward — the way the drawing hand holds it).
 * null for a fighter with no scabbard.
 */
export function scabbardKeys(a) {
  const sc = a.props?.scabbard, sw = a.props?.sword, u = sc?.userData; if (!sc || !sw || !u?.q0) return null;
  const h = a.h, kb = h.height / 1.8, k = kb * a.root.scale.y, qi = a.root.quaternion.clone().invert(), R = h.rest; h.root.updateMatrixWorld(true);
  const own = (pW) => pW.sub(a.root.position).applyQuaternion(qi).multiplyScalar(1 / k);
  // where it hangs (a draw in progress may have it turned and drawn back: that is not where it hangs)
  const qW = sc.parent.getWorldQuaternion(new THREE.Quaternion()).multiply(u.q0), dir = V3(0, 1, 0).applyQuaternion(qW).applyQuaternion(qi).normalize(), edge0 = V3(0, 0, 1).applyQuaternion(qW).applyQuaternion(qi);
  const home = own(sc.parent.localToWorld(u.pos0.clone())).addScaledVector(dir, -u.seat / kb); // the middle of the grip, the sword home
  // the drawing hand comes across the body: its knuckles — and so the edge of what it holds — face the fighter's left
  const e = V3(1, 0, 0); e.addScaledVector(dir, -e.dot(dir)).normalize();
  const turn = Math.atan2(new THREE.Vector3().crossVectors(edge0, e).dot(dir), edge0.dot(e));
  // How far up the line the arm can take the hilt: to where the hand is nine tenths of the arm's reach from its
  // shoulder — the shoulder where the chest's turn brings it at the end of the slide (the right side goes forward
  // with the blade).
  const len = (sw.userData.len ?? 0.74) / kb, arm = (R['upperarm01.R'].head.distanceTo(R['lowerarm01.R'].head) + R['lowerarm01.R'].head.distanceTo(R['wrist.R'].head)) / kb;
  const sh0 = own(h.wpos('upperarm01.R', new THREE.Vector3())), piv = own(h.wpos('spine03', new THREE.Vector3())), r = 0.9 * arm + 0.06, c = Math.cos(DRAW_CHEST[1]), sn = Math.sin(DRAW_CHEST[1]);
  const sx = sh0.x - piv.x, sz = sh0.z - piv.z, o = home.clone().sub(V3(piv.x + sx * c + sz * sn, sh0.y, piv.z + sz * c - sx * sn)), od = o.dot(dir), disc = od * od - o.lengthSq() + r * r;
  const slide = clamp(disc > 0 ? od + Math.sqrt(disc) : 0.12, 0.12, 0.56 * len);
  const back = clamp(len - slide - 0.1, 0, 0.26);
  const key = (out) => ({ h: home.clone().addScaledVector(dir, 0.02 - out), d: dir.clone(), e: e.clone() });
  return { sheath: key(0), unsheath: key(slide), slide: slide * kb, back: back * kb, turn, chest: DRAW_CHEST };
}
/**
 * Where a draw stands between two sword positions: sheathed (the sword still rides in its scabbard), slide (0…1: how
 * far it has slid out along it), give (0…1: how far the left hand has the scabbard turned and drawn back for it).
 * The same serves a sword going home: every part of it runs backwards.
 */
export function drawPhase(P) {
  const a = P.from, b = P.to, k = P.k ?? 0, i = _WAY.indexOf(a), j = _WAY.indexOf(b);
  if (i < 0 && j < 0) return { sheathed: false, slide: 0, give: 0 };
  if (i === 0 && j === 0) return { sheathed: true, slide: 0, give: 1 };
  if (i === 1 && j === 1) return { sheathed: true, slide: 1, give: 1 };
  if (i === 0 && j === 1) return { sheathed: true, slide: k, give: 1 };
  if (i === 1 && j === 0) return { sheathed: true, slide: 1 - k, give: 1 };
  // out of the scabbard: the left hand keeps it turned and drawn back until the point is clear, then lets it go home
  if ((i === 1 && j === 2) || (i === 2 && j === 1) || (i === 2 && j === 2)) return { sheathed: false, slide: 1, give: 1 };
  if (i === 2 && j === 3) return { sheathed: false, slide: 1, give: 1 - k };
  if (i === 3 && j === 2) return { sheathed: false, slide: 1, give: k };
  return { sheathed: false, slide: 1, give: 0 };
}
/**
 * The scabbard and the sword in it at a moment of a draw (own: scabbardKeys, ph: drawPhase, w: how far the draw has
 * taken over from what the body did before): the scabbard turned and drawn back in the left hand, the sword — while it
 * rides in it — slid out along it. Call with ph = null to put both back where they hang.
 */
export function wearDraw(a, own, ph, w = 1) {
  const sc = a.props?.scabbard, sw = a.props?.sword, u = sc?.userData; if (!sc || !u?.q0) return;
  if (!ph || !own) { a.scabbardSet(0, 0); if (sw && sw.parent === sc) sw.position.set(0, -u.seat, 0); return; }
  const back = own.back * (ph.sheathed ? ph.slide : ph.give) * w;
  a.scabbardSet(own.turn * ph.give * w, back);
  if (sw && sw.parent === sc) sw.position.set(0, -(u.seat + (own.slide * ph.slide) * w + back), 0);
}
/**
 * One moment of a sword sequence on a fighter — what a Stage sword overlay plays. A sword whose sequence begins or ends
 * in its scabbard ('sheath', 'unsheath') is drawn from where the scabbard hangs on this body: the right hand takes the
 * hilt across the belly (the chest turns to bring its shoulder round), the left takes the scabbard, turns it flat and
 * draws it back as the blade slides out, and the sword passes to the hand where the slide ends. Going home runs backwards.
 * o: two, twist, shift (as applySword), left: false — the left hand stays off the scabbard, w — how far this has taken
 * over from what the body did before (0…1). Returns { P, ph, own }.
 */
export function playSword(a, t, seq, o = {}) {
  const own = scabbardKeys(a), P = swordAt(t, seq, undefined, own), ph = own ? drawPhase(P) : null;
  if (ph) { const where = ph.sheathed ? 'hip' : 'hand'; if (a.swordPlace !== where) a.swordAt(where); wearDraw(a, own, ph, o.w ?? 1); }
  // (the chest: turned for the draw while the scabbard is in the left hand, the way it turns with any sword position otherwise)
  const turn = ph && ph.give > 0 ? lerp(chestTurn(P) * (o.twist ?? 1), lerp(own.chest[0], own.chest[1], ph.slide), ph.give) * (o.w ?? 1) + chestTurn(P) * (o.twist ?? 1) * (1 - (o.w ?? 1)) : undefined;
  applySword(a, P, { two: o.two, twist: o.twist, turn, shift: o.shift });
  if (ph && ph.give > 0 && o.left !== false && !o.two) {
    // (the left hand leaves the scabbard as the scabbard goes home: by degrees, not at once)
    const g = smooth(0, 1, ph.give), B = a.h.B, arm = g < 1 ? ['clavicle.L', 'shoulder01.L', 'upperarm01.L', 'upperarm02.L', 'lowerarm01.L', 'lowerarm02.L', 'wrist.L'].filter((n) => B[n]).map((n) => [B[n], B[n].quaternion.clone()]) : null;
    holdScabbard(a); if (arm) for (const [bone, q0] of arm) bone.quaternion.slerp(q0, 1 - g);
  }
  return { P, ph, own };
}
/** The left hand on the scabbard, a hand's breadth behind its mouth, the thumb towards the guard — where it steadies, turns and draws it back. */
export function holdScabbard(a) {
  const sc = a.props?.scabbard; if (!sc) return; const h = a.h, k = h.height / 1.8 * a.root.scale.y; h.root.updateMatrixWorld(true);
  const dir = V3(0, 1, 0).applyQuaternion(sc.getWorldQuaternion(new THREE.Quaternion())), at = sc.getWorldPosition(new THREE.Vector3()).addScaledVector(dir, 0.075 * k);
  holdAt(a, 'L', at, dir.clone().negate(), V3(0, -1, 0), null, null); a.grip('L', 0.8);
}
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
  const T = new THREE.Vector3(), W = new THREE.Vector3(), sh = new THREE.Vector3(), g = new THREE.Vector3(), w = new THREE.Vector3(), ax = new THREE.Vector3(), q = new THREE.Quaternion();
  // the whole of it for one place of the elbow: swung by `turn` about the line from shoulder to wrist, from where an elbow goes
  const solve = (turn) => {
    T.copy(grip).addScaledVector(dir, -0.05 * k);
    for (let it = 0; it < 5; it++) {
      h.root.updateMatrixWorld(true); h.wpos(U, sh); W.copy(T).sub(sh); const d = W.length(); if (d > d0) W.multiplyScalar((L - give * Math.exp(-(d - d0) / give)) / d); W.add(sh);
      const p = pole || elbowPole(a, side, W); if (turn) p.applyQuaternion(q.setFromAxisAngle(ax.copy(W).sub(sh).normalize(), turn));
      reach(a, side, W, p); if (!it) a.grip(side); pointProp(a, side, dir, edge, { prop });
      if (it < 4) T.copy(grip).sub(gripPoint(a, side, g)).add(h.wpos(Wr, w));
    }
  };
  solve(0);
  // A forearm does not pass through the body it belongs to (a hand sent across the belly to the other hip): where it
  // would, the elbow swings round — the way that frees it — just as far as it takes, and no further.
  const S = bellyShape(h); if (!S) return;
  const m = new THREE.Matrix4(), e = new THREE.Vector3(), c = new THREE.Vector3(), rest = R.root.head, sc = a.root.scale.y;
  // (how far the arm is clear of the body, less what each part of it needs: the elbow and forearm their own thickness
  // and the cloth on them; the upper arm may lie against the chest)
  const clear = () => {
    h.root.updateMatrixWorld(true); m.copy(h.B.root.matrixWorld).invert(); h.wpos(F, e); h.wpos(Wr, w); h.wpos(U, sh); let worst = Infinity;
    for (const [f, need] of [[0, 0.055], [0.25, 0.05], [0.5, 0.05], [0.75, 0.048], [1, 0.045]]) { c.copy(e).lerp(w, f).applyMatrix4(m).add(rest); worst = Math.min(worst, bellyClear(S, c.x, c.y, c.z) * sc - need * k); }
    for (const f of [0.6, 0.8]) { c.copy(sh).lerp(e, f).applyMatrix4(m).add(rest); worst = Math.min(worst, bellyClear(S, c.x, c.y, c.z) * sc - 0.035 * k); }
    return worst;
  }, need = 0;
  if (clear() >= need) return;
  solve(0.15); const up = clear(); solve(-0.15); const sgn = up >= clear() ? 1 : -1;
  let lo = 0, hi = 0, best = 0, bestC = -1;
  for (let t = 0.15; t <= 1.36; t += 0.15) { solve(sgn * t); const cl = clear(); if (cl > bestC) { bestC = cl; best = t; } if (cl >= need) { hi = t; lo = t - 0.15; break; } }
  if (!hi) { solve(sgn * best); return; }
  for (let i = 0; i < 4; i++) { const mid = (lo + hi) / 2; solve(sgn * mid); if (clear() >= need) hi = mid; else lo = mid; }
  solve(sgn * hi);
}
/** How far the chest turns with a sword hand at a position (radians about the upright, + to the fighter's left): towards the side the hand is on. */
export const chestTurn = (P) => clamp(P.h.x * 0.9 - 0.25 * clamp(P.h.x / 0.15, -1, 1) * clamp(-P.h.z / 0.12), -0.75, 0.75) * 0.8;
/** put the sword (right hand, optional left hand on the hilt) at a pose. o: two, twist (a factor on the chest's turn), turn (the chest's turn itself, radians), lean */
export function applySword(a, P, o = {}) { const h = a.h, k = h.height / 1.8 * a.root.scale.y; const q = a.root.quaternion; const tw = o.turn !== undefined ? o.turn / 0.8 : chestTurn(P) / 0.8 * (o.twist ?? 1);
  // the chest turns with the sword hand (every part of the turn eases in: a chest does not jump when the hand passes behind the shoulders)
  twist(a, 'spine05', tw * 0.35); twist(a, 'spine03', tw * 0.45); if (o.lean) pitch(a, 'spine05', o.lean);
  const hand = P.h.clone().multiplyScalar(k).applyQuaternion(q).add(a.root.position); if (o.shift) hand.add(o.shift); const dir = P.d.clone().applyQuaternion(q).normalize(); const edge = P.e.clone().applyQuaternion(q);
  // the right hand takes the hilt where the position says; a second hand takes it below, towards the pommel
  holdAt(a, 'R', hand.clone().addScaledVector(dir, -0.02 * k), dir, edge);
  if (o.two) holdAt(a, 'L', hand.clone().addScaledVector(dir, -0.12 * k), dir, edge, null, null);
  return { hand, dir }; }
/** fall backwards (reverse of a get-up) starting at t0; returns 0..1 progress. o: dur, push (m), dir (world V3), face-down: prone */
/**
 * The captured falls. A body that is struck down loses its balance, goes over and lands, and that takes about a
 * second: `back` goes over backwards onto its back (struck from the front), `face` — the knees give and it pitches
 * forward onto its face. in: the last moment standing, down: the body is on the ground, out: it has settled.
 */
export const FALLS = { back: { clip: '90_18', in: 0.45, down: 1.55, out: 2.7 }, face: { clip: '90_16', in: 2.25, down: 4.0, out: 5.2 } };
/**
 * The clip time of a fall that began at t0: forwards, at the capture's own speed. dur asks for the time it takes to
 * be down and is held to what a body can do (0.7 to 1.4 times the capture's speed); after `down` the body settles to
 * `out` and lies still.
 */
export function fallClock(t, t0, f, dur) {
  const natural = f.down - f.in, rate = dur ? clamp(natural / dur, 0.7, 1 / 0.7) : 1;
  return clamp(f.in + Math.max(0, t - t0) * rate, f.in, f.out);
}
/** A fall, from standing where the body stands: it goes over where a falling body goes (the hips travel, the feet stay). Returns 0…1, how far down it is. */
export function fall(a, t, t0, o = {}) {
  const f = o.prone ? FALLS.face : FALLS.back, ct = fallClock(t, t0, f, o.dur);
  a.mo.apply(f.clip, ct, { ref: f.in, face: f.in });
  return clamp((ct - f.in) / (f.down - f.in));
}
/** hit-stop: animation time that freezes for `hold` seconds at each hit time */
export function hitStop(t, hits) { let w = t; for (const [ti, hold] of hits) { if (t > ti) w -= Math.min(hold, t - ti); } return w; }
/** recoil of a body struck at time ti: returns 0..1 envelope */
export const struck = (t, ti, len = 0.5) => (t < ti ? 0 : Math.exp(-(t - ti) / len * 3) * smooth(ti, ti + 0.05, t));
