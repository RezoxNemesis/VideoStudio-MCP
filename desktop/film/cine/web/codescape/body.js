// CODESCAPE BODY MOTION — the whole body, moved the way a body moves: weight over a foot before the other one lifts,
// feet that stay where they land, a pelvis that rides over the standing leg, a chest that turns against the hips.
//
//   stand(p, x, z, yaw, t, o)          a person at rest, alive: breath, sway, weight on one leg (o.weight -1…1)
//   idleWeight(t, seed)                a slow change of the standing leg, for stand's o.weight
//   planWalk(points, o) → plan         a walk along a path, from a stand to a stand, as footfalls in time
//   planTurn(at, yaw0, yaw1, o) → plan a turn on the spot, stepped round (nobody pivots like a statue)
//   chain(planA, planB) → plan         one plan after another
//   stepper(p, plan, t, o)             the body at time t of a plan (feet, pelvis, chest, arms, head)
//   sit(p, seat, k, t, o)              sitting down (k 0 → 1) and, with o.up, standing up
//   gesture(p, s, kind, k, t, o)       a hand that talks: 'beat', 'offer', 'chest', 'point', 'stop'
//   namaste(p, k, t)                   both palms together at the chest
//   passSheet(giver, gs, taker, ts, L, k, o)   a card or letter handed from one person to another
//   bodyReport(p)                      what review.js measures: sliding feet, a leg out of reach, weight off its feet
//   playClip(p, clip, t, o)            captured motion (a clip from tools/mocap) on this rig
//
// Rules these follow (and review.js checks): a planted foot does not move; a knee never locks; the weight goes over the standing foot
// BEFORE the other one lifts; the heel lands first and the foot rolls to the toe; the arm swings against its own leg, a little late.
import { THREE, V3, clamp, lerp, sm, sm2, seg } from './engine.js';
import { looseHand, pinchSheet, snap, blendHand } from './motion.js';

const UP = new V3(0, 1, 0), PI = Math.PI;
const fwdOf = yaw => new V3(Math.sin(yaw), 0, Math.cos(yaw)), leftOf = yaw => new V3(Math.cos(yaw), 0, -Math.sin(yaw));
const yawOf = d => Math.atan2(d.x, d.z);
const wrap = a => { while (a > PI) a -= 2 * PI; while (a < -PI) a += 2 * PI; return a; };
const wp = (o, out = new V3()) => { o.updateWorldMatrix(true, false); return out.setFromMatrixPosition(o.matrixWorld); };
/** finger curls [index, middle, ring, little, thumb] on a refined hand (motion.js refineHands) or on the rig's plain one */
const curls = (p, s, c, spread = 0) => { if (p.fingers) { p.fingers(s, c.slice(0, 4), spread); if (p.thumbRest) p.thumbRest(s, c[4] ?? 0.2); } else p.grip(s, c, spread); };
const loose = (p, s, t, o) => { if (p.fingers) looseHand(p, s, t, o); else p.grip(s, [0.3, 0.4, 0.48, 0.56, 0.3], 0.3); };

/** a smooth curve through [t, v] keys that never overshoots a key (monotone cubic) */
export function through(keys, t) {
  const n = keys.length; if (!n) return 0; if (t <= keys[0][0]) return keys[0][1]; if (t >= keys[n - 1][0]) return keys[n - 1][1];
  let i = 0; while (i < n - 2 && t > keys[i + 1][0]) i++;
  const slope = j => (keys[j + 1][1] - keys[j][1]) / Math.max(1e-6, keys[j + 1][0] - keys[j][0]);
  const tangent = j => { if (j === 0 || j === n - 1) return 0; const a = slope(j - 1), b = slope(j); return a * b <= 0 ? 0 : 2 * a * b / (a + b); };
  const h = keys[i + 1][0] - keys[i][0], u = (t - keys[i][0]) / h, m0 = tangent(i) * h, m1 = tangent(i + 1) * h, u2 = u * u, u3 = u2 * u;
  return (2 * u3 - 3 * u2 + 1) * keys[i][1] + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * keys[i + 1][1] + (u3 - u2) * m1;
}

/** a path through [x, z] points, measured in metres: at(s) → { p, dir } */
export function pathOf(points) {
  const P = points.map(q => new V3(q[0], 0, q[1])); if (P.length < 2) P.push(P[0].clone().add(new V3(0, 0, 0.001)));
  const cr = (a, b, c, d, u) => { const u2 = u * u, u3 = u2 * u; return a.clone().multiplyScalar(-0.5 * u3 + u2 - 0.5 * u).addScaledVector(b, 1.5 * u3 - 2.5 * u2 + 1).addScaledVector(c, -1.5 * u3 + 2 * u2 + 0.5 * u).addScaledVector(d, 0.5 * u3 - 0.5 * u2); };
  const S = [], N = 24; for (let i = 0; i < P.length - 1; i++) for (let k = 0; k < N; k++) S.push(cr(P[Math.max(0, i - 1)], P[i], P[i + 1], P[Math.min(P.length - 1, i + 2)], k / N)); S.push(P[P.length - 1].clone());
  const L = [0]; for (let i = 1; i < S.length; i++) L.push(L[i - 1] + S[i].distanceTo(S[i - 1])); const len = L[L.length - 1];
  const at = s => { s = clamp(s, 0, len); let i = 1; while (i < L.length - 1 && L[i] < s) i++; const u = (s - L[i - 1]) / Math.max(1e-9, L[i] - L[i - 1]); const a = Math.max(1, Math.min(L.length - 1, i)); const j0 = Math.max(0, a - 2), j1 = Math.min(S.length - 1, a + 1);
    return { p: S[i - 1].clone().lerp(S[i], u), dir: new V3().subVectors(S[j1], S[j0]).setY(0).normalize() }; };
  return { len, at };
}

// ---- the foot on the ground. G is the ground point under the ankle of a flat foot; the foot pivots on its ball (heel up) or its heel (toe up)
const FOOT = { ball: 0.118, heel: 0.05 };
function ankleOf(G, d, pitch, p, lift = 0) {
  const ah = p.L.ankle, b = FOOT.ball * p.size, h = FOOT.heel * p.size; let fx = 0, y = ah;
  if (pitch < 0) { const a = -pitch; fx = b * (1 - Math.cos(a)) + ah * Math.sin(a); y = b * Math.sin(a) + ah * Math.cos(a); }
  else if (pitch > 0) { const a = pitch; fx = -h * (1 - Math.cos(a)) - ah * Math.sin(a); y = h * Math.sin(a) + ah * Math.cos(a); }
  return new V3(G.x + d.x * fx, G.y + y + lift, G.z + d.z * fx);
}
const restFeet = (x, z, yaw, p, o = {}) => { const f = fwdOf(yaw), l = leftOf(yaw), w = (o.width ?? 0.085) * p.size, toe = o.toe ?? 0.12; const at = sg => ({ G: new V3(x, 0, z).addScaledVector(l, sg * w).addScaledVector(f, sg * 0.02 * p.size), d: f.clone().addScaledVector(l, sg * toe).normalize() }); return { l: at(1), r: at(-1) }; };

/** the highest the pelvis can be with both ankles still in reach. A leg at 99.7% of its length has its knee bent 9°: straight to the eye, never locked.
 *  (The knee angle is steep in this: at 98.5% it is already bent 20°, a crouch. STAND is the pelvis height of a person standing easy: knees about 10°.) */
const STAND = 0.822, STRIDE = 0.816;
function pelvisCap(p, C, yaw, roll, ankles, want) {
  const Lmax = (p.L.thigh + p.L.shin) * 0.997, l = leftOf(yaw); let y = want;
  for (const s of ['l', 'r']) { const sg = s === 'l' ? 1 : -1, a = ankles[s]; const hx = C.x + l.x * sg * p.L.hipW, hz = C.z + l.z * sg * p.L.hipW, dh = Math.hypot(a.x - hx, a.z - hz);
    const top = a.y + Math.sqrt(Math.max(0.01, Lmax * Lmax - dh * dh)) + 0.02 * p.size - sg * roll * p.L.hipW; y = Math.min(y, top); }
  return y;
}

// ---- standing ---------------------------------------------------------------------------------------------------------------
/** the leg the weight rests on, changing slowly: -1 (right) … 1 (left). Holds a side for 5–9 s, changes over about 1.3 s */
export function idleWeight(t, seed = 1) {
  const h = n => { const x = Math.sin(n * 127.1 + seed * 311.7) * 43758.5453; return x - Math.floor(x); };
  let t0 = -3 * h(0.5), i = 0, side = h(0) > 0.5 ? 1 : -1; while (i < 200) { const d = 5 + 4 * h(i + 1); if (t < t0 + d) return lerp(-side, side, sm2(clamp((t - t0) / 1.3, 0, 1))) * 0.8; t0 += d; side = -side; i++; }
  return 0;
}

/** a person standing at (x, z) facing yaw — breathing, swaying, the weight on one leg (o.weight -1 right … 1 left): the pelvis goes over
 *  that foot, the free hip drops, the chest tips back the other way, the free knee eases. o: weight, crouch, lean, twist, chestLean, chestTwist, pf */
export function stand(p, x, z, yaw, t, o = {}) {
  p.place(x, z, yaw); const f = p.fwd(), l = p.left(), sz = p.size, w = clamp(o.weight ?? 0, -1, 1);
  const br = Math.sin(t * 1.6) * 0.004, sway = Math.sin(t * 0.5) * 0.008, sh = w * 0.05 * sz + sway + (o.shift || 0);
  const F = restFeet(x, z, yaw, p, o), feet = {}, ank = {};
  for (const s of ['l', 'r']) { const sg = s === 'l' ? 1 : -1, free = clamp(-w * sg, 0, 1), pitch = -0.16 * free * free; ank[s] = ankleOf(F[s].G, F[s].d, pitch, p); feet[s] = { p: ank[s], dir: F[s].d, pitch }; }
  const roll = -sh * 0.9 / sz, C = new V3(x, 0, z).addScaledVector(l, sh).addScaledVector(f, o.pf || 0);
  C.y = pelvisCap(p, C, yaw, roll, ank, STAND * sz + br - (o.crouch || 0) - Math.abs(w) * 0.006 * sz);
  const brc = Math.sin(t * 1.6 + 0.5);
  p.body({ pel: C, lean: o.lean ?? 0.03, chestLean: (o.chestLean || 0) - 0.012 * brc, side: -roll * 0.5, chestSide: -roll * 0.55 + 0.006 * Math.sin(t * 0.83 + 1.3), twist: (o.twist || 0) + 0.025 * Math.sin(t * 0.37 + 0.7), chestTwist: (o.chestTwist || 0) + 0.015 * Math.sin(t * 0.61 + 2.1), pelRoll: roll, feet });
  note(p, t, { l: { G: F.l.G, flat: feet.l.pitch > -0.03 }, r: { G: F.r.G, flat: feet.r.pitch > -0.03 } }, C);
  return { feet: F, pelvis: C };
}

/** an arm at rest by the side, alive (a small sway, fingers that are not frozen) */
export function restArm(p, s, t, o = {}) { p.hang(s, (o.swing || 0) + 0.03 * Math.sin(t * 0.8 + (s === 'l' ? 0 : 2)), o.bend ?? 0.28, o.out ?? 0.12); loose(p, s, t); }

// ---- plans: footfalls in time ----------------------------------------------------------------------------------------------
/** A walk from a stand to a stand along [x, z] points. o: size (the person's p.size), speed (m/s, 1.15), step (m between footfalls, 0.62),
 *  first ('l' | 'r'), t0 (when it starts), yaw0 (facing at the start; default along the path), face (facing at the end: a turn is added if it differs).
 *  → { t0, t1, steps, pel, rest0, rest1, footfalls: [{ t, s, p }] } — give it to stepper() each frame; footfalls time the footstep sounds */
export function planWalk(points, o = {}) {
  const sz = o.size ?? 1, path = pathOf(points), D = path.len, t0 = o.t0 ?? 0, w = (o.width ?? 0.085) * sz;
  const L0 = (o.step ?? 0.62) * sz, speed = (o.speed ?? 1.15) * sz;
  const n = Math.max(2, Math.round(D / L0 + 1.25)), L = D / (n - 1.25), Ts = L / speed, lead = o.lead ?? 0.32, settle = o.settle ?? 0.42, swing = 0.78;
  const a0 = path.at(0), a1 = path.at(D), yaw0 = o.yaw0 ?? yawOf(a0.dir), yaw1 = yawOf(a1.dir);
  const spot = (s, sg, yaw) => { const c = path.at(s), d = yaw == null ? c.dir : fwdOf(yaw), lf = new V3(d.z, 0, -d.x); return { G: c.p.clone().addScaledVector(lf, sg * w), d: d.clone().addScaledVector(lf, sg * 0.1).normalize(), s }; };
  const rest0 = { l: spot(0, 1, yaw0), r: spot(0, -1, yaw0) }, rest1 = { l: spot(D, 1), r: spot(D, -1) };
  // the first foot to go: the one on the inside of the first bend, or as asked
  const bend = wrap(yawOf(path.at(Math.min(D, L * 2)).dir) - yawOf(a0.dir)), first = o.first || (bend > 0.1 ? 'l' : 'r');
  const steps = [], at = { l: rest0.l, r: rest0.r }, pel = [{ t: t0, s: 0, side: 0 }];
  for (let i = 1; i <= n; i++) { const s = (i % 2 === 1) === (first === 'l') ? 'l' : 'r', sg = s === 'l' ? 1 : -1, other = s === 'l' ? 'r' : 'l';
    const sTo = i >= n - 1 ? D : (i - 0.25) * L, to = i >= n - 1 ? rest1[s] : spot(sTo, sg), a = t0 + lead + (i - 1) * Ts, b = a + swing * Ts * (i === 1 || i === n ? 1.12 : 1);
    steps.push({ s, a, b, from: at[s], to, len: to.G.distanceTo(at[s].G), lift: (0.055 + 0.05 * Math.min(1, L / (0.62 * sz))) * sz });
    // as one foot passes the other the pelvis is a little AHEAD of the standing foot, so that at the heel strike it is midway between the two
    // (behind that, the leading leg has to stretch and pulls the hips down 7 cm a step); it has already fallen forward into the first step, and is still arriving in the last
    pel.push({ t: (a + b) / 2, s: i === 1 ? 0.2 * L : i === n ? D - 0.2 * L : clamp(at[other].s + 0.14 * L, 0, D - 0.2 * L), side: -sg });
    at[s] = to; }
  const t1 = steps[n - 1].b + settle; pel.push({ t: t1, s: D, side: 0 });
  let plan = { t0, t1, steps, rest0, rest1, size: sz, path, D, L, Ts, yaw0, yaw1, pel, kind: 'walk', sway: 0.3 * w };
  plan.footfalls = steps.map(k => ({ t: k.b, s: k.s, p: [k.to.G.x, 0, k.to.G.z] }));
  if (o.face != null && Math.abs(wrap(o.face - yaw1)) > 0.3) plan = chain(plan, planTurn([a1.p.x, a1.p.z], yaw1, o.face, { size: sz, t0: t1 - 0.2, width: o.width }));
  return plan;
}

/** A turn on the spot, stepped round: the foot on the side of the turn opens out first, the other comes round to it. Up to 90° in one pair of steps.
 *  o: size, t0, pace (seconds a step, 0.42) */
export function planTurn(at, yaw0, yaw1, o = {}) {
  const sz = o.size ?? 1, t0 = o.t0 ?? 0, d = wrap(yaw1 - yaw0), pairs = Math.max(1, Math.ceil(Math.abs(d) / (PI / 2 + 0.05))), Ts = o.pace ?? 0.42, lead = 0.26;
  const x = at[0], z = at[1], P = { size: sz, L: { ankle: 0.075 * sz } }, rest = y => restFeet(x, z, y, P, o);
  const steps = [], pel = [{ t: t0, x, z, yaw: yaw0 }]; let cur = rest(yaw0), ya = yaw0, t = t0 + lead;
  const lead1 = d > 0 ? 'l' : 'r';
  for (let k = 1; k <= pairs; k++) { const yb = yaw0 + d * k / pairs, nxt = rest(yb);
    for (const s of [lead1, lead1 === 'l' ? 'r' : 'l']) { const other = s === 'l' ? 'r' : 'l', a = t, b = t + Ts * 0.8;
      steps.push({ s, a, b, from: cur[s], to: nxt[s], len: nxt[s].G.distanceTo(cur[s].G), lift: 0.045 * sz, turn: true });
      const stay = s === lead1 ? cur[other] : nxt[other]; pel.push({ t: (a + b) / 2, x: lerp(x, stay.G.x, 0.55), z: lerp(z, stay.G.z, 0.55), yaw: s === lead1 ? lerp(ya, yb, 0.35) : lerp(ya, yb, 0.85) });
      cur = { ...cur, [s]: nxt[s] }; t += Ts; }
    ya = yb; }
  const t1 = t + 0.3; pel.push({ t: t1, x, z, yaw: yaw1 });
  return { t0, t1, steps, rest0: rest(yaw0), rest1: rest(yaw1), size: sz, pel, kind: 'turn', yaw0, yaw1, footfalls: steps.map(k => ({ t: k.b, s: k.s, p: [k.to.G.x, 0, k.to.G.z] })) };
}

/** one plan, then another (the second should start where the first ends) */
export function chain(A, B) {
  const shift = Math.max(0, A.t1 - 0.2 - B.t0), mv = k => ({ ...k, a: k.a + shift, b: k.b + shift });
  return { t0: A.t0, t1: B.t1 + shift, size: A.size, rest0: A.rest0, rest1: B.rest1, kind: 'chain', parts: [A, { ...B, t0: B.t0 + shift, t1: B.t1 + shift, steps: B.steps.map(mv), pel: B.pel.map(k => ({ ...k, t: k.t + shift })) }], steps: [...A.steps, ...B.steps.map(mv)],
    footfalls: [...A.footfalls, ...B.footfalls.map(f => ({ ...f, t: f.t + shift }))], yaw0: A.yaw0, yaw1: B.yaw1 };
}

const TOE = 0.72;   // how far the heel is up (radians) when the toe leaves the floor in a full stride: this, not a deeper knee, is what keeps the hips from sinking between steps
// where a foot is at time t of a plan: planted (heel down first, then flat, then up on its ball before it leaves) or in the air
function footAt(plan, s, t, p) {
  const mine = plan.steps.filter(k => k.s === s); let prev = null, cur = null, next = null;
  for (const k of mine) { if (t >= k.b) prev = k; else if (t >= k.a) cur = k; else if (!next) next = k; }
  if (cur) { const u = (t - cur.a) / (cur.b - cur.a), big = clamp(cur.len / (0.5 * plan.size), 0, 1), p0 = cur.turn ? -0.1 : -TOE * big - 0.06, p1 = cur.turn ? 0 : 0.2 * big;
    const A = ankleOf(cur.from.G, cur.from.d, p0, p), B = ankleOf(cur.to.G, cur.to.d, p1, p), e = sm2(u), pos = A.lerp(B, e); pos.y += cur.lift * Math.pow(Math.sin(PI * u), 1.3) * (1 - 0.25 * u);
    const d = cur.from.d.clone().lerp(cur.to.d, sm(u)).normalize(); return { p: pos, dir: d, pitch: lerp(p0, p1, sm(clamp(u * 1.15, 0, 1))), flat: false, G: null, air: Math.sin(PI * u) }; }
  const here = prev ? prev.to : plan.rest0[s]; let pitch = 0;
  if (prev && !prev.turn) { const big = clamp(prev.len / (0.5 * plan.size), 0, 1); pitch = 0.2 * big * (1 - sm(clamp((t - prev.b) / 0.11, 0, 1))); }   // heel first, then the foot rolls flat
  if (next) { const big = next.turn ? 0.25 : clamp(next.len / (0.5 * plan.size), 0, 1), w = next.turn ? 0.2 : 0.3, k = sm(clamp((t - (next.a - w)) / w, 0, 1)); if (k > 0 && pitch < 0.02) pitch = -(next.turn ? 0.1 : TOE * big + 0.06) * k; }   // the heel peels up before the foot leaves
  return { p: ankleOf(here.G, here.d, pitch, p), dir: here.d, pitch, flat: Math.abs(pitch) < 0.03, G: here.G, air: 0 };
}

function pelvisAt(plan, t) {
  if (plan.kind === 'walk') { const s = through(plan.pel.map(k => [k.t, k.s]), t), side = through(plan.pel.map(k => [k.t, k.side]), t), c = plan.path.at(s), lf = new V3(c.dir.z, 0, -c.dir.x);
    const k0 = sm(clamp((t - plan.t0) / 0.5, 0, 1)), k1 = sm(clamp((plan.t1 - t) / 0.5, 0, 1)); let yaw = yawOf(c.dir); yaw = plan.yaw0 + wrap(yaw - plan.yaw0) * k0;
    return { C: c.p.clone().addScaledVector(lf, side * plan.sway), yaw, s, going: Math.min(k0, k1) }; }
  const x = through(plan.pel.map(k => [k.t, k.x]), t), z = through(plan.pel.map(k => [k.t, k.z]), t); let ya = plan.pel[0].yaw; const ky = plan.pel.map(k => { const y = ya + wrap(k.yaw - ya); ya = y; return [k.t, y]; });
  return { C: new V3(x, 0, z), yaw: through(ky, t), s: 0, going: 0 };
}
const partAt = (plan, t) => { if (plan.kind !== 'chain') return plan; for (const q of plan.parts) if (t < q.t1 - 0.1) return q; return plan.parts[plan.parts.length - 1]; };

/** The body at time t of a plan. o: look (a point for the eyes; default ahead), arms (false to leave the arms to you, or { l: false } for one), lean, crouch.
 *  → { done, moving, yaw, pelvis, feet, ground (where the body is along its path, without the bob: aim a following lens at this),
 *      strike (the foot that has just landed, for one frame's worth of sound or dust) } */
export function stepper(p, plan, t, o = {}) {
  const q = partAt(plan, t), sz = p.size, T = clamp(t, plan.t0, plan.t1);
  const pose = tt => { const f = { l: footAt(plan, 'l', tt, p), r: footAt(plan, 'r', tt, p) }, P = pelvisAt(partAt(plan, tt), clamp(tt, plan.t0, plan.t1)); return { f, P }; };
  const { f, P } = pose(T), yaw = P.yaw, fw = fwdOf(yaw), lf = leftOf(yaw);
  // how far each foot is ahead of the pelvis: this turns the hips, swings the arms and tips the pelvis
  const ahead = ps => ({ l: new V3().subVectors(ps.f.l.p, ps.P.C).dot(fwdOf(ps.P.yaw)), r: new V3().subVectors(ps.f.r.p, ps.P.C).dot(fwdOf(ps.P.yaw)) });
  const stride = Math.max(0.2, (q.L || 0.5 * sz)), A = ahead({ f, P }), split = clamp((A.l - A.r) / stride, -1.2, 1.2);
  const late = pose(T - 0.09), Al = ahead(late), splitLate = clamp((Al.l - Al.r) / stride, -1.2, 1.2);
  const vel = (pelvisAt(q, clamp(T + 0.05, plan.t0, plan.t1)).s - pelvisAt(q, clamp(T - 0.05, plan.t0, plan.t1)).s) / 0.1, acc = q.kind === 'walk' ? clamp(((pelvisAt(q, clamp(T + 0.2, plan.t0, plan.t1)).s - P.s) - (P.s - pelvisAt(q, clamp(T - 0.2, plan.t0, plan.t1)).s)) / 0.04, -2, 2) : 0;
  // at rest it is stand(): the same breath and sway, so a film can go from one to the other without a pop
  const still = (1 - clamp(Math.abs(vel) / (0.3 * sz), 0, 1)) * (1 - Math.max(f.l.air, f.r.air)), br = Math.sin(t * 1.6) * 0.004 * still, sway = Math.sin(t * 0.5) * 0.008 * still, brc = Math.sin(t * 1.6 + 0.5) * still;
  const roll = 0.075 * (f.r.air - f.l.air) - sway * 0.9 / sz, pelYaw = -0.13 * split * (q.kind === 'walk' ? 1 : 0.4);   /* the swing side's hip drops a little more, the pelvis turns with the leading leg */
  // height: under the cap of both legs' reach, smoothed over three moments so it rides rather than snaps
  const goW = clamp(Math.abs(vel) / (0.6 * sz), 0, 1), lat = 0.022 * sz * (f.r.air - f.l.air) * goW;   /* the body rides over the foot that is down: a side-to-side sway of about 2 cm (the height cap knows it) */
  const capAt = ps => pelvisCap(p, ps.P.C.clone().addScaledVector(leftOf(ps.P.yaw), lat), ps.P.yaw + pelYaw, roll, { l: ps.f.l.p, r: ps.f.r.p }, (lerp(STRIDE, STAND, still) - (o.crouch || 0)) * sz + br);
  const capNow = capAt({ f, P }), y = Math.min(capNow, (capAt(pose(T - 0.045)) + 2 * capNow + capAt(pose(T + 0.045))) / 4);   /* eased over three moments, but never above what the legs reach now */
  const C = P.C.clone().addScaledVector(lf, sway + lat); C.y = y;
  p.place(C.x, C.z, yaw);
  p.body({ pel: C, lean: (o.lean ?? 0.03) + 0.055 * clamp(vel / (1.2 * sz), 0, 1.3) + 0.022 * acc, chestLean: -0.012 * brc + 0.012 * goW * (f.l.air + f.r.air), twist: -pelYaw * 0.7 + 0.025 * Math.sin(t * 0.37 + 0.7) * still, chestTwist: -pelYaw * 1.35 + 0.015 * Math.sin(t * 0.61 + 2.1) * still, side: -roll * 0.5, chestSide: -roll * 0.55 + 0.006 * Math.sin(t * 0.83 + 1.3) * still, pelYaw, pelRoll: roll,
    feet: { l: { p: f.l.p, dir: f.l.dir, pitch: f.l.pitch }, r: { p: f.r.p, dir: f.r.dir, pitch: f.r.pitch } } });
  // arms: each against its own leg, a little late, the elbow folding as it comes forward
  const go = clamp(Math.abs(vel) / (0.9 * sz), 0, 1);
  for (const s of ['l', 'r']) { if (o.arms === false || (o.arms && o.arms[s] === false)) continue; const sw = (s === 'l' ? -1 : 1) * splitLate * (o.swing ?? 0.6) * go; p.hang(s, sw + 0.03 * Math.sin(t * 0.8 + (s === 'l' ? 0 : 2)), lerp(0.26, 0.1 + 0.55 * Math.max(0, sw), go) + 0.04 * go, 0.1 + 0.03 * go); loose(p, s, t, { curl: 0.3 }); }   /* a free swing from the shoulder: nearly straight behind, the elbow folding as it comes forward */
  if (o.look !== false) { const eye = o.look || (q.kind === 'walk' ? q.path.at(Math.min(q.D, P.s + 4 * sz)).p.clone().add(new V3(0, 1.45 * sz, 0)).addScaledVector(fw, 0.5) : C.clone().addScaledVector(fw, 3).setY(1.45 * sz)); p.look(eye, 0.7); }
  note(p, t, { l: { G: f.l.G, flat: f.l.flat }, r: { G: f.r.G, flat: f.r.flat } }, C, Math.abs(vel));
  const strike = plan.footfalls.find(k => t >= k.t && t < k.t + 1 / 24);
  return { done: t >= plan.t1, moving: go, yaw, pelvis: C, feet: f, strike: strike ? strike.s : null, vel, ground: q.kind === 'walk' ? q.path.at(P.s).p : new V3(P.C.x, 0, P.C.z) };
}

// ---- sitting ----------------------------------------------------------------------------------------------------------------
/** Sitting down on a seat (k 0 standing in front of it → 1 seated), or with o.up standing up from it (k 0 seated → 1 standing).
 *  seat: { x, z (the middle of the seat, where the hips land), yaw (the way the sitter faces), h (seat height) }.
 *  The hips go back and the chest comes forward together (the weight stays over the feet), the hands come to the thighs.
 *  o: up, hands (false to leave the arms to you), feet (how far ahead of the seat's middle the feet stand; default from the thigh) */
export function sit(p, seat, k, t, o = {}) {
  const sz = p.size, yaw = seat.yaw || 0, f = fwdOf(yaw), l = leftOf(yaw), down = o.up ? 1 - clamp(k, 0, 1) : clamp(k, 0, 1);
  p.place(seat.x, seat.z, yaw);
  const reachF = o.feet ?? (p.L.thigh * 0.96), base = new V3(seat.x, 0, seat.z), mid = base.clone().addScaledVector(f, reachF);
  const feet = {}, ank = {}; for (const s of ['l', 'r']) { const sg = s === 'l' ? 1 : -1, G = mid.clone().addScaledVector(l, sg * 0.1 * sz), d = f.clone().addScaledVector(l, sg * 0.14).normalize(); ank[s] = ankleOf(G, d, 0, p); feet[s] = { p: ank[s], dir: d, pitch: 0, G }; }
  // standing up: the chest goes forward first (nose over toes), then the hips leave the seat; sitting: the hips go back as the chest comes forward
  const back = o.up ? sm2(seg(down, 0.18, 1)) : sm2(seg(down, 0.02, 0.8)), drop = o.up ? sm2(seg(down, 0.1, 0.95)) : sm2(seg(down, 0.1, 0.92));
  const fold = Math.pow(Math.sin(PI * clamp(o.up ? down * 0.9 + 0.1 * sm(down) : down, 0, 1)), o.up ? 0.8 : 1.1);
  const seatY = seat.h + 0.105 * sz, standY = STAND * sz, br = Math.sin(t * 1.6) * 0.004;
  const C = mid.clone().lerp(base, back); C.y = lerp(standY, seatY, drop) + br * (1 - drop * 0.6);
  C.y = Math.min(C.y, pelvisCap(p, C, yaw, 0, ank, 9));
  p.body({ pel: C, pelPitch: 0.42 * fold - 0.06 * down, lean: 0.03 + 0.34 * fold + 0.02 * down, chestLean: -0.012 * Math.sin(t * 1.6 + 0.5), twist: 0.02 * Math.sin(t * 0.37), feet: { l: { p: feet.l.p, dir: feet.l.dir, knee: f.clone().addScaledVector(l, 0.22) }, r: { p: feet.r.p, dir: feet.r.dir, knee: f.clone().addScaledVector(l, -0.22) } } });
  if (o.hands !== false) for (const s of ['l', 'r']) { const sg = s === 'l' ? 1 : -1, A = p.arm[s]; p.hang(s, 0.03 * Math.sin(t * 0.8 + sg), 0.28); loose(p, s, t); const a = snap(p, s);
    const kn = wp(p.leg[s].sn), hp = wp(p.leg[s].hip), th = new V3().subVectors(kn, hp), along = th.clone().normalize(), up = new V3().crossVectors(along, l).normalize(); if (up.y < 0) up.negate();
    const T = hp.clone().addScaledVector(th, 0.62).addScaledVector(up, 0.082 * sz).addScaledVector(l, sg * 0.012); const out = l.clone().multiplyScalar(sg);
    p.reach(s, T, out.clone().multiplyScalar(0.9).add(new V3(0, -0.3, 0)).addScaledVector(f, -0.6), along.clone().addScaledVector(out, -0.22).normalize(), up.clone().negate()); curls(p, s, [0.2, 0.24, 0.28, 0.32, 0.25], 0.5); const b = snap(p, s);
    blendHand(p, s, a, b, sm2(seg(down, 0.35, 0.85)), { via: a.T.clone().lerp(b.T, 0.5).addScaledVector(f, 0.07).addScaledVector(out, 0.04) }); }
  note(p, t, { l: { G: feet.l.G, flat: true }, r: { G: feet.r.G, flat: true } }, C, 0, true);   /* going down and coming up the chest is forward of the hips: the pelvis alone does not say where the weight is */
  return { seated: down, pelvis: C, feet };
}

// ---- hands that talk --------------------------------------------------------------------------------------------------------
/** A speech gesture with hand s, risen by k (0 at rest … 1 out), from wherever the arm already is (pose the arm at rest first).
 *  kind: 'beat' (a small open hand that marks a word: o.hit 0…1 drops it on the stress), 'offer' (an open palm held out: "here", "you see"),
 *  'chest' (the palm to the speaker's own chest: "I", "mine"), 'point' (o.at: a world point), 'stop' (the palm raised, facing out).
 *  The hand leaves late and arrives on an arc; fingers are never a flat paddle. */
export function gesture(p, s, kind, k, t, o = {}) {
  if (k <= 0) return; const A = p.arm[s], sg = A.sg, sz = p.size, f = p.fwd(), out = p.left().multiplyScalar(sg), S = wp(A.up), a = snap(p, s), hit = clamp(o.hit || 0, 0, 1);
  const pole = out.clone().multiplyScalar(0.75).add(new V3(0, -0.75, 0)).addScaledVector(f, -0.35);
  let T, fd, pd, curl = [0.14, 0.18, 0.24, 0.3, 0.2], spread = 0.6;
  if (kind === 'beat') { T = S.clone().addScaledVector(f, 0.25 * sz).addScaledVector(out, 0.035 * sz).add(new V3(0, (-0.215 - 0.035 * hit) * sz, 0)); fd = f.clone().addScaledVector(out, 0.28).add(new V3(0, 0.1 - 0.16 * hit, 0)); pd = new V3(0, 0.62, 0).addScaledVector(out, -0.78); curl = [0.16, 0.24, 0.32, 0.4, 0.3]; spread = 0.25; }
  else if (kind === 'offer') { T = S.clone().addScaledVector(f, 0.33 * sz).addScaledVector(out, 0.07 * sz).add(new V3(0, -0.165 * sz, 0)); fd = f.clone().addScaledVector(out, 0.3).add(new V3(0, -0.04, 0)); pd = new V3(0, 0.93, 0).addScaledVector(out, -0.36); curl = [0.08, 0.12, 0.17, 0.24, 0.22]; spread = 0.3; }
  else if (kind === 'chest') { const c = wp(p.sp2).add(new V3(0, 0.1 * sz, 0)); T = c.clone().addScaledVector(f, 0.125 * sz).addScaledVector(out, 0.075 * sz); fd = out.clone().multiplyScalar(-1).add(new V3(0, 0.42, 0)); pd = f.clone().multiplyScalar(-1).addScaledVector(out, 0.1); curl = [0.12, 0.14, 0.18, 0.24, 0.25]; spread = 0.2; }
  else if (kind === 'wave') { const w = o.sway || 0, hi = o.high ?? 1; T = S.clone().addScaledVector(f, 0.1 * sz).addScaledVector(out, (0.2 + 0.075 * w) * sz).add(new V3(0, (0.05 + 0.2 * hi) * sz, 0)); fd = new V3(0, 1, 0).addScaledVector(out, 0.18 + 0.5 * w).addScaledVector(f, 0.08); pd = f.clone().add(new V3(0, -0.06, 0)); curl = [0.03, 0.03, 0.04, 0.07, 0.12]; spread = 0.55; }
  else if (kind === 'stop') { T = S.clone().addScaledVector(f, 0.27 * sz).addScaledVector(out, 0.05 * sz).add(new V3(0, -0.04 * sz, 0)); fd = new V3(0, 1, 0).addScaledVector(f, 0.28).addScaledVector(out, 0.08); pd = f.clone().add(new V3(0, -0.2, 0)); curl = [0.04, 0.04, 0.06, 0.1, 0.2]; spread = 0.25; }
  else { const at = o.at || S.clone().addScaledVector(f, 2); const d = new V3().subVectors(at, S).normalize(); T = S.clone().addScaledVector(d, 0.4 * sz).add(new V3(0, -0.07 * sz, 0)).addScaledVector(out, 0.02); fd = new V3().subVectors(at, T).normalize(); pd = out.clone().multiplyScalar(-1).add(new V3(0, -0.35, 0)); curl = [0.02, 0.85, 0.92, 0.95, 0.75]; spread = 0; }
  p.reach(s, T, pole, fd.normalize(), pd.normalize()); curls(p, s, curl, spread); const b = snap(p, s);
  blendHand(p, s, a, b, k, { via: a.T.clone().lerp(b.T, 0.5).addScaledVector(out, 0.05 * sz).addScaledVector(f, 0.05 * sz) });
}

/** both palms together at the chest, fingers up, the head bowing a little with it (k 0 … 1) */
export function namaste(p, k, t = 0) {
  if (k <= 0) return; const f = p.fwd(), sz = p.size, c = wp(p.sp2).add(new V3(0, 0.075 * sz, 0)).addScaledVector(f, 0.15 * sz);
  for (const s of ['l', 'r']) { const A = p.arm[s], out = p.left().multiplyScalar(A.sg), a = snap(p, s); const T = c.clone().addScaledVector(out, 0.016 * sz).add(new V3(0, -0.075 * sz, 0)).addScaledVector(f, -0.012 * sz);
    p.reach(s, T, out.clone().add(new V3(0, -0.85, 0)).addScaledVector(f, -0.1), new V3(0, 1, 0).addScaledVector(f, 0.3).normalize(), out.clone().multiplyScalar(-1)); curls(p, s, [0.02, 0.02, 0.03, 0.05, 0.05], 0.05); const b = snap(p, s);
    blendHand(p, s, a, b, k, { via: a.T.clone().lerp(b.T, 0.5).addScaledVector(out, 0.07 * sz).addScaledVector(f, 0.06 * sz) }); }
  p.neck.rotation.x += 0.1 * k; p.head.rotation.x += 0.12 * k;
}

// ---- a hand-over ------------------------------------------------------------------------------------------------------------
/** A sheet (card, letter, note) passed from giver's hand gs to taker's hand ts. YOU move the sheet (place it each frame, held out between them);
 *  this does the hands: the giver holds it, the taker's hand comes in already open to its own edge and closes, both hold it for a beat,
 *  the giver's fingers open and the hand goes back. Pose both arms at rest before calling. k: 0 … 1 over the whole hand-over.
 *  o.give / o.take: pinchSheet settings ({ at, into, flex, pole }) for each hand. → { held: 'giver' | 'both' | 'taker' } */
export function passSheet(giver, gs, taker, ts, L, k, o = {}) {
  const inT = sm2(seg(k, 0.08, 0.5)), let_ = sm(seg(k, 0.62, 0.74)), away = sm2(seg(k, 0.72, 1));
  const restT = snap(taker, ts), restG = snap(giver, gs);
  const g = pinchSheet(giver, gs, L, { th: 0.003, ...(o.give || {}), open: let_ }); const heldG = snap(giver, gs);
  if (away > 0) blendHand(giver, gs, heldG, restG, away, { via: heldG.T.clone().lerp(restG.T, 0.4).add(new V3(0, -0.03, 0)) });
  const tk = pinchSheet(taker, ts, L, { th: 0.003, ...(o.take || {}), open: 1 - sm(seg(k, 0.44, 0.56)) }); const heldT = snap(taker, ts);
  if (inT < 1) blendHand(taker, ts, restT, heldT, inT, { via: restT.T.clone().lerp(heldT.T, 0.55).add(new V3(0, 0.04, 0)) });
  return { held: k < 0.5 ? 'giver' : k < 0.68 ? 'both' : 'taker', give: g, take: tk };
}

// ---- what review.js reads --------------------------------------------------------------------------------------------------
function note(p, t, feet, C, speed = 0, seated = false) {
  const prev = p._body, r = { t, slide: 0, reach: 0, off: 0 }; const Lmax = p.L.thigh + p.L.shin;
  for (const s of ['l', 'r']) { const a = wp(p.leg[s].an), h = wp(p.leg[s].hip); r.reach = Math.max(r.reach, a.distanceTo(h) / Lmax);
    const now = { flat: feet[s].flat, x: a.x, z: a.z }; if (prev && prev.f[s].flat && now.flat && Math.abs(t - prev.t) < 0.08 && t !== prev.t) r.slide = Math.max(r.slide, Math.hypot(a.x - prev.f[s].x, a.z - prev.f[s].z)); r[s] = now; }
  // weight: standing still, the pelvis must be over the ground between (or on) the feet
  if (!seated && speed < 0.05 * p.size && feet.l.G && feet.r.G) { const A = feet.l.G, B = feet.r.G, ab = new V3().subVectors(B, A), u = clamp(new V3().subVectors(C, A).setY(0).dot(ab) / Math.max(1e-6, ab.lengthSq()), 0, 1), near = A.clone().addScaledVector(ab, u); r.off = Math.hypot(C.x - near.x, C.z - near.z); }
  p._body = { t, f: { l: r.l, r: r.r } }; p.bodyRep = r; return r;
}
/** the last body measurement of a person: { t, slide (m a planted foot moved since the frame before), reach (the longer leg, as a share of its length), off (m the weight is off its feet when standing still) } */
export const bodyReport = p => p.bodyRep || null;

// ---- captured motion -------------------------------------------------------------------------------------------------------
// A clip is what tools/mocap/video2clip.py writes from a video of a person: { fps, frames: [[x, y, z] × 33 body points], vis: [[lwr, rwr, lan, ran]],
// seen: { legs } } — metres, y up, the hips at the origin, the performer's left on +x when they face +z (the film's own axes).
// The bones are the performer's, not the rig's, so only DIRECTIONS are taken: each limb is pointed the way the performer's was, on the rig's own
// bone lengths. Legs that the camera did not see (a waist-up take) are not guessed: the rig stands, and only its hips turn.
const J = { nose: 0, lear: 7, rear: 8, lsh: 11, rsh: 12, lel: 13, rel: 14, lwr: 15, rwr: 16, lpk: 17, rpk: 18, lix: 19, rix: 20, lhip: 23, rhip: 24, lkn: 25, rkn: 26, lan: 27, ran: 28, lhe: 29, rhe: 30, lto: 31, rto: 32 };
/** Make a clip playable: smooths it (o.smooth seconds, 0.1), measures the performer's leg, notes what was seen. → { fps, n, dur, legs, at(t) } or null for no data */
export function clipOf(data, o = {}) {
  if (!data || !Array.isArray(data.frames) || !data.frames.length) return null;
  const fps = data.fps || 30, F = data.frames, n = F.length, w = Math.max(0, Math.round((o.smooth ?? 0.1) * fps / 2)), VV = data.vis || null;
  const avg = (get, i) => { let a = 0, b = 0; for (let q = -w; q <= w; q++) { const m = get(clamp(i + q, 0, n - 1)); if (m == null) continue; const wt = w + 1 - Math.abs(q); a += m * wt; b += wt; } return b ? a / b : 0; };
  const S = F.map((fr, i) => fr.map((_, j) => new V3(avg(k => F[k][j] && F[k][j][0], i), avg(k => F[k][j] && F[k][j][1], i), avg(k => F[k][j] && F[k][j][2], i))));
  const V = F.map((_, i) => [0, 1, 2, 3].map(c => (VV ? avg(k => VV[k][c], i) : 1)));
  const leg = S.reduce((a, fr) => a + fr[J.lhip].distanceTo(fr[J.lkn]) + fr[J.lkn].distanceTo(fr[J.lan]), 0) / n;
  const at = t => { const x = clamp(t * fps, 0, n - 1), i = Math.floor(x), u = x - i, k = Math.min(n - 1, i + 1); return { P: S[i].map((v, j) => v.clone().lerp(S[k][j], u)), vis: V[i].map((v, c) => lerp(v, V[k][c], u)) }; };
  return { fps, n, dur: (n - 1) / fps, at, leg, legs: data.seen ? data.seen.legs !== false : true, name: data.source || '' };
}
/** Captured motion on the rig at time t of the clip. o: at [x, z] and yaw (where the performance stands in the film and the way it faces),
 *  legs (default: only if the camera saw them), arms / head (false to keep yours), fist (0 loose … 1 closed hands: a body-pose capture does not see fingers),
 *  mirror (swap left and right). An arm the camera lost goes back to rest, eased, rather than flailing. */
export function playClip(p, clip, t, o = {}) {
  if (!clip) return null;
  const fr = clip.at(t), P0 = fr.P, yaw0 = o.yaw || 0, sz = p.size, cy = Math.cos(yaw0), sy = Math.sin(yaw0), ox = (o.at || [0, 0])[0], oz = (o.at || [0, 0])[1], mir = o.mirror ? -1 : 1;
  const nm = n => (o.mirror ? (n[0] === 'l' ? 'r' + n.slice(1) : n[0] === 'r' ? 'l' + n.slice(1) : n) : n);
  const R = v => new V3((v.x * mir) * cy + v.z * sy, v.y, -(v.x * mir) * sy + v.z * cy);   /* the clip's axes turned to the way the film wants it to face */
  const Q = n => R(P0[J[nm(n)]]), dir = (a, b) => Q(b).sub(Q(a)).normalize();
  const acrossH = Q('lhip').sub(Q('rhip')).setY(0).normalize(), hipYaw = Math.atan2(-acrossH.z, acrossH.x), acrossS = Q('lsh').sub(Q('rsh')).setY(0).normalize(), shYaw = Math.atan2(-acrossS.z, acrossS.x);
  const useLegs = (o.legs ?? clip.legs) && fr.vis[2] > 0.5 && fr.vis[3] > 0.5, k = (p.L.thigh + p.L.shin) / Math.max(0.3, clip.leg);
  let C, bodyYaw, pelYaw = 0, feet = {}, ank = {}, roll = 0;
  if (useLegs) {   // the performer's own feet, put on the floor: the lower foot IS the floor (hips-at-origin clips are on the spot)
    bodyYaw = hipYaw; const floor = Math.min(P0[J.lhe].y, P0[J.rhe].y, P0[J.lto].y, P0[J.rto].y), hipsY = (P0[J.lhip].y + P0[J.rhip].y) / 2;
    const Wp = n => { const v = Q(n); return new V3(ox + v.x * k, (P0[J[nm(n)]].y - floor) * k, oz + v.z * k); };
    p.place(ox, oz, bodyYaw); const f = p.fwd(); roll = clamp(Math.atan2(Q('lhip').y - Q('rhip').y, 0.19), -0.3, 0.3);
    for (const s of ['l', 'r']) { const a = Wp(s + 'an'), he = Wp(s + 'he'), to = Wp(s + 'to'); let d = new V3().subVectors(to, he).setY(0); if (d.lengthSq() < 1e-6) d = f.clone(); d.normalize();
      const tilt = clamp(Math.atan2(to.y - he.y, Math.hypot(to.x - he.x, to.z - he.z)), -0.7, 0.5), low = Math.min(he.y, to.y), lift = Math.max(0, low - 0.012 * sz), flat = lift < 0.012 * sz && Math.abs(tilt) < 0.12, G = new V3(a.x, 0, a.z);
      ank[s] = ankleOf(G, d, flat ? 0 : tilt, p, lift); feet[s] = { p: ank[s], dir: d, pitch: flat ? 0 : tilt, knee: dir(s + 'hip', s + 'kn').add(f.clone().multiplyScalar(0.35)), G, flat }; }
    C = new V3(ox, 0, oz); C.y = Math.min((hipsY - floor) * k + 0.02 * sz, pelvisCap(p, C, bodyYaw, roll, ank, 9));
  } else {         // a waist-up take: the rig stands where it is put, feet planted; the hips turn over them as the performer's did
    bodyYaw = yaw0; pelYaw = clamp(wrap(hipYaw - yaw0), -0.6, 0.6); p.place(ox, oz, yaw0); const F0 = restFeet(ox, oz, yaw0, p);
    for (const s of ['l', 'r']) { ank[s] = ankleOf(F0[s].G, F0[s].d, 0, p); feet[s] = { p: ank[s], dir: F0[s].d, pitch: 0, G: F0[s].G, flat: true }; }
    C = new V3(ox, 0, oz); C.y = pelvisCap(p, C, yaw0 + pelYaw, 0, ank, STAND * sz + Math.sin(t * 1.6) * 0.004);   /* the hips have turned: measure the legs from where the hip joints now are */
  }
  // the spine: how the shoulders stand over the hips, in the hips' own frame
  const hy = bodyYaw + pelYaw, f = fwdOf(hy), l = leftOf(hy), spine = Q('lsh').add(Q('rsh')).sub(Q('lhip')).sub(Q('rhip')).normalize();
  const lean = clamp(Math.atan2(spine.dot(f), spine.y), -0.5, 1.1), side = clamp(-Math.atan2(spine.dot(l), spine.y), -0.5, 0.5), twist = clamp(wrap(shYaw - hipYaw), -0.9, 0.9);
  p.body({ pel: C, lean, side, twist: twist * 0.5, chestTwist: twist * 0.5, pelYaw, pelRoll: roll, feet: { l: feet.l, r: feet.r } });
  if (o.arms !== false) for (const s of ['l', 'r']) { const A = p.arm[s], seen = sm(clamp((fr.vis[nm(s + 'wr') === 'lwr' ? 0 : 1] - 0.06) / 0.2, 0, 1));   /* a hand behind the body or the head is still placed well by the model; only one it has truly lost (under 6%) goes to rest */
    p.hang(s, 0.03 * Math.sin(t * 0.8 + A.sg), 0.28); loose(p, s, t); if (seen <= 0) continue; const rest = snap(p, s);
    const S0 = wp(A.up), up = dir(s + 'sh', s + 'el'), fo = dir(s + 'el', s + 'wr'), E = S0.clone().addScaledVector(up, p.L.upper), T = E.clone().addScaledVector(fo, p.L.fore);
    // the hand: a body-pose capture gives three rough points on it. The fingers run on from the forearm, bent toward where the knuckles were; the palm faces as wrist → index and wrist → little finger say
    const wr = Q(s + 'wr'), ix = Q(s + 'ix').sub(wr), pk = Q(s + 'pk').sub(wr), kn = ix.clone().add(pk); let fd = fo.clone(); if (kn.lengthSq() > 1e-6) { fd = fo.clone().lerp(kn.normalize(), 0.5).normalize(); }
    let pd = new V3().crossVectors(ix, pk); if (pd.lengthSq() < 1e-8) pd = l.clone().multiplyScalar(-A.sg); pd.normalize(); if ((s === 'l') !== !!o.mirror) pd.negate(); pd.addScaledVector(fd, -pd.dot(fd)).normalize();
    p.reach(s, T, E.clone().sub(S0.clone().add(T).multiplyScalar(0.5)).normalize(), fd, pd); const c = clamp(o.fist ?? 0.3, 0, 1); curls(p, s, [lerp(0.2, 1, c), lerp(0.26, 1.05, c), lerp(0.32, 1.1, c), lerp(0.38, 1.12, c), lerp(0.2, 0.9, c)], 0.3 * (1 - c));
    if (seen < 1) blendHand(p, s, rest, snap(p, s), seen); }
  if (o.head !== false) { const d = Q('nose').sub(Q('lear').add(Q('rear')).multiplyScalar(0.5)).normalize(); p.look(wp(p.head).add(new V3(0, 0.1 * sz, 0)).addScaledVector(d, 3), 0.9); }
  note(p, t, { l: { G: feet.l.G, flat: feet.l.flat }, r: { G: feet.r.G, flat: feet.r.flat } }, C, useLegs ? 1 : 0);
  return { yaw: hy, pelvis: C, feet, legs: useLegs };
}
