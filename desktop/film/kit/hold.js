// Holding, giving, taking, setting down.
//
// A thing in a hand lies IN the hand: across the palm, just behind the knuckles, with the fingers closed round it and
// the thumb over them. It is ONE thing through a whole sequence — it lies somewhere, is taken, carried, given from
// hand to hand, set down — and at every moment it is in exactly one place; it never vanishes and never jumps.
//
//   const rose = prop({ id: 'rose', r: 0.003, at: 0.12, make: (p, dir, w) => roseMesh(p, dir), life: [
//     { t: 0, on: [x, y, z], dir: [1, 0, 0] },            // it lies there (its foot at `on`, pointing along `dir`)
//     { t: 4.2, by: 'beti', hand: 'R', over: 0.2 },        // her hand closes on it and takes it
//     { t: 9.0, by: 'papa', hand: 'R', over: 0.5 },        // given: he has it before she lets go
//     { t: 14, on: [x, y, z], over: 0.3 } ] });            // set down
//   sequence:  dyn: (ts, ctx) => [rose.item(ts, ctx)]
//   actors:    hands: (t) => ({ ...rose.hands('beti')(t) })     // the fingers close round it by themselves
//   Lay a thing the way the hand will take it (its `dir` along the taking hand's `axis`), or give the take more time
//   (`over`): a stem that lies the other way round is whipped about as it is lifted.
//   cameras, reaching hands:  rose.where(ts, ctx) → { owner, hand, grip, p, dir };  wristFor(pose, 'R', point) → where
//   the wrist must be for the grip to close on a point (aim a hand overlay there at the moment of taking).
// A held state can lean the thing in the hand (tilt: [towards the fingers°, out of the palm°] or (ts, ctx) => […]),
// carry it head down (flip: true), or hold it between thumb and fingers (style: 'pinch').
import { HANDS } from '../engine/body.js';

const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2], cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
const lerp = (a, b, k) => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];
const clamp01 = (v) => Math.max(0, Math.min(1, v)), ease = (k) => { k = clamp01(k); return k * k * (3 - 2 * k); };
/** One direction turned into another at an even rate (never through nothing, as a straight blend of two opposite ones would). */
const turn = (a, b, k) => { const c = Math.max(-1, Math.min(1, dot(a, b))), th = Math.acos(c); if (th < 1e-4) return norm(lerp(a, b, k)); let ax = cross(a, b); if (Math.hypot(...ax) < 1e-6) ax = Math.abs(a[1]) < 0.9 ? cross(a, [0, 1, 0]) : cross(a, [1, 0, 0]); ax = norm(ax); const w = th * k, s1 = Math.sin(w), c1 = Math.cos(w), d = dot(ax, a); return norm(add(add(mul(a, c1), mul(cross(ax, a), s1)), mul(ax, d * (1 - c1)))); };

/**
 * Where a thing lies in a hand. → { c: the middle of the grip, axis: across the palm from the little finger to the
 * index finger (a held stick comes out by the thumb), palm: out of the palm, fwd: along the hand to the fingers, k }.
 * o.r: the thing's radius where it is held (a thick thing lies further out of the palm: the fingers go round it).
 */
export function gripFrame(pose, hand = 'R', o = {}) {
  const j = pose?.j, W = j?.[`wrist.${hand}`], K2 = j?.[`finger2-1.${hand}`], K5 = j?.[`finger5-1.${hand}`]; if (!W || !K2 || !K5) return null;
  const k = (pose.height ?? 1.8) / 1.8, kc = lerp(K2, K5, 0.5), axis = norm(sub(K2, K5));
  let fwd = sub(kc, W); fwd = norm(sub(fwd, mul(axis, dot(fwd, axis))));
  const palm = hand === 'R' ? cross(axis, fwd) : cross(fwd, axis), r = o.r ?? 0.01;
  // in a closed hand the thing sits in the pocket between the first finger bones and the palm
  return { c: add(add(kc, mul(palm, 0.02 * k + r)), mul(fwd, -0.016 * k)), axis, palm, fwd, k, wrist: W, hand };
}
/** The thing itself in that grip: { p: its foot, dir: from foot to head }. o: { at: metres from its foot it is gripped, flip, tilt: [°, °] }. */
export function holdPose(F, o = {}) {
  let d = o.flip ? mul(F.axis, -1) : F.axis; const [a, b] = (o.tilt ?? [0, 0]).map((x) => (x * Math.PI) / 180);
  if (a) d = norm(add(mul(d, Math.cos(a)), mul(F.fwd, Math.sin(a))));
  if (b) d = norm(add(mul(d, Math.cos(b)), mul(F.palm, Math.sin(b))));
  return { p: sub(F.c, mul(d, o.at ?? 0)), dir: d };
}
/** Where the wrist must be (the hand turned as it is now) for the grip to lie on `point`. */
export function wristFor(pose, hand, point, o = {}) { const F = gripFrame(pose, hand, o); return F ? add(F.wrist, sub(point, F.c)) : null; }

const THIN = { curl: [[14, 26, 44], [80, 92, 44], [82, 94, 46], [84, 94, 46], [84, 92, 44]], spread: -0.3, thumbIn: 1, thumbOut: 0 };
const THICK = { curl: [[10, 20, 30], [55, 62, 40], [56, 64, 42], [58, 64, 42], [58, 62, 40]], spread: -0.1, thumbIn: 0.85, thumbOut: 0.1 };
const PINCH = { curl: [[18, 30, 36], [52, 58, 30], [58, 62, 34], [70, 80, 40], [74, 84, 42]], spread: -0.2, thumbIn: 0.85, thumbOut: 0.05 };
// (study of 17 Nov 2026: the object decides the grip.) A phone is held by the fingertips on its edges, the thumb free
// over its face; a book or tablet rests on flat, slightly hooked fingers with the thumb over the front (a support grip).
const EDGE = { curl: [[8, 18, 22], [38, 46, 30], [40, 48, 32], [44, 52, 34], [48, 56, 36]], spread: -0.15, thumbIn: 0.55, thumbOut: 0.25 };
const SUPPORT = { curl: [[6, 12, 10], [18, 22, 16], [20, 24, 18], [22, 26, 20], [26, 30, 22]], spread: 0.05, thumbIn: 0.75, thumbOut: 0.2 };
const mixShape = (A, B, k) => ({ curl: A.curl.map((f, i) => f.map((d, q) => d + (B.curl[i][q] - d) * k)), spread: A.spread + (B.spread - A.spread) * k, thumbIn: A.thumbIn + (B.thumbIn - A.thumbIn) * k, thumbOut: A.thumbOut + (B.thumbOut - A.thumbOut) * k });
/** The hand's shape round a thing of radius r (metres). style: 'fist' (power grip: through the closed hand, the thumb
 * pressing back) | 'pinch' (precision: between thumb and first fingers — paper, a card) | 'edge' (fingertips on the
 * edges — a phone) | 'support' (flat fingers under it, thumb over the front — a book, a tablet). */
export function holdShape(style = 'fist', r = 0.01) {
  const pick = { pinch: PINCH, edge: EDGE, cradle: EDGE, support: SUPPORT }[style];
  return pick ? mixShape(pick, pick, 0) : mixShape(THIN, THICK, clamp01((r - 0.003) / 0.027));
}

/**
 * One thing with its whole life in a sequence (see the top of this file). o: { id, r, at, style, make(p, dir, where) →
 * something with draw(g, cam, sc), life: [{ t, on, dir? } | { t, by, hand, flip?, tilt?, style?, over? }] }.
 * → { item(ts, ctx), hands(actor) → (ts) => shapes, where(ts, ctx), owner(ts) }
 */
export function prop(o) {
  const life = [...o.life].sort((a, b) => a.t - b.t), r = o.r ?? 0.01, at = o.at ?? 0, held = (S) => S && S.by !== undefined;
  const over = (S, P) => S.over ?? (held(S) && held(P) ? 0.45 : held(S) ? 0.15 : 0.25);
  const val = (v, ts, ctx) => (typeof v === 'function' ? v(ts, ctx) : v);
  /** the thing as state S has it: its grip point and direction */
  const pose = (S, ts, ctx) => {
    if (held(S)) { const F = gripFrame(ctx.poses[S.by], S.hand ?? 'R', { r }); if (!F) throw new Error(`${o.id ?? 'A thing'} is held by ${S.by}, who is not in this shot's sequence or has no such hand.`); const h = holdPose(F, { at, flip: S.flip, tilt: val(S.tilt, ts, ctx) }); return { grip: F.c, dir: h.dir }; }
    const d = norm(val(S.dir, ts, ctx) ?? [1, 0, 0]), foot = val(S.on, ts, ctx); return { grip: add(foot, mul(d, at)), dir: d };
  };
  const idx = (ts) => { let i = 0; for (let q = 0; q < life.length; q++) if (life[q].t <= ts) i = q; return i; };
  // when, within a change, the thing goes over: in a hand-off it stays with the giver until the taker has closed on it
  const cross01 = (S, P, u) => (held(S) && held(P) ? ease((u - 0.45) / 0.2) : ease(u));
  const where = (ts, ctx) => {
    const i = idx(ts), S = life[i], P = i > 0 ? life[i - 1] : null, now = pose(S, ts, ctx), ov = P ? over(S, P) : 0; let grip = now.grip, dir = now.dir;
    if (P && ts < S.t + ov) { const was = pose(P, ts, ctx), k = cross01(S, P, (ts - S.t) / ov); grip = lerp(was.grip, now.grip, k); dir = turn(was.dir, now.dir, k); }
    return { owner: S.by ?? null, hand: held(S) ? S.hand ?? 'R' : null, grip, p: sub(grip, mul(dir, at)), dir };
  };
  const shapeOf = (S) => holdShape(S.style ?? o.style ?? 'fist', r);
  // THE APERTURE (reach-to-grasp studies, Jeannerod): a reaching hand PRE-SHAPES to the thing — its own grip, opened
  // only a little wider than the thing (peak about 2–4 cm over its width, two thirds of the way there). A hand thrown
  // wide open for a pen or a glass reads as a grab at air.
  const aperture = Math.max(0.3, Math.min(1, (2 * r + 0.035) / 0.12));
  /** this actor's hands as the thing asks for them at ts: only the hand that reaches for, holds or lets go of it */
  const hands = (actor) => (ts) => {
    const out = {};
    life.forEach((S, i) => {
      if (S.by !== actor) return; const P = life[i - 1] ?? null, N = life[i + 1] ?? null, h = S.hand ?? 'R', sh = shapeOf(S), ov = P ? over(S, P) : 0, OPEN = mixShape(sh, HANDS.open, aperture);
      const same = (X) => held(X) && X.by === actor && (X.hand ?? 'R') === h; // (it only changes its lean in the same hand)
      // taking it: the hand opens as it reaches, then closes — from another hand a little later (the giver still has it)
      // (a reaching hand is widest about two thirds of the way there, then closes on the thing: S.reach = seconds the reach takes)
      const reach = same(P) ? -1e9 : S.t - (S.reach ?? (held(P) ? 0.2 : 0.3)), c0 = same(P) ? -1e9 : held(P) ? S.t + 0.2 * ov : S.t - 0.04, c1 = same(P) ? -1e9 : held(P) ? S.t + 0.35 * ov : S.t + 0.1;
      // letting go: to another hand only once that hand has it; set down, as it comes to rest
      const nv = N ? over(N, S) : 0, o0 = !N || same(N) ? 1e9 : held(N) ? N.t + 0.65 * nv : N.t + 0.5 * nv, o1 = !N || same(N) ? 1e9 : held(N) ? N.t + 0.85 * nv : N.t + nv, end = !N || same(N) ? 1e9 : o1 + 0.12;
      if (ts < reach || ts >= end) return;
      if (ts < c0) out[h] = mixShape(HANDS.relax, OPEN, ease((ts - reach) / Math.max(1e-6, (c0 - reach) * 0.67)));
      else if (ts < c1) out[h] = mixShape(OPEN, sh, ease((ts - c0) / (c1 - c0)));
      else if (ts < o0) out[h] = sh;
      else if (ts < o1) out[h] = mixShape(sh, OPEN, ease((ts - o0) / (o1 - o0)));
      else out[h] = mixShape(OPEN, HANDS.relax, ease((ts - o1) / 0.12));
    });
    return out;
  };
  return {
    id: o.id, life, where, hands, owner: (ts) => life[idx(ts)].by ?? null,
    item: (ts, ctx) => { const w = where(ts, ctx), made = o.make ? o.make(w.p, w.dir, w) : null; return { p: w.p, dir: w.dir, bias: o.bias ?? 0, draw: (g, cam, sc) => made?.draw?.(g, cam, sc) }; },
  };
}
