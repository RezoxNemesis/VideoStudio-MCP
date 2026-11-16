// Holding, giving, taking, setting down: node devtests/hold.mjs        (exit 1 when a rule is broken)
// A thing in a hand lies IN the hand: across the palm, behind the knuckles, with the fingers closed round it — not
// beside the wrist with the fingers open. It is one thing through a whole sequence: it lies, is taken, is carried, is
// given from hand to hand, is set down — and at no moment is it nowhere or in two places, and it never jumps.
// (The Papa film: an open hand with a rose standing against it, a rose that vanished between shots, 16 Nov 2026.)
import { gripFrame, holdPose, holdShape, prop, wristFor } from '../kit/hold.js';
import { HANDS } from '../engine/body.js';
const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2], len = (a) => Math.hypot(...a), dist = (a, b) => len(sub(a, b));
// a right hand as the rig gives it (measured on a hanging arm, metres from the wrist), and its mirror image
const hand = (w, s) => ({ [`wrist.${s}`]: w, [`lowerarm01.${s}`]: add(w, [0.09 * (s === 'R' ? 1 : -1), 0.244, 0.044]), [`finger2-1.${s}`]: add(w, [-0.062 * (s === 'R' ? 1 : -1), -0.099, -0.003]), [`finger3-1.${s}`]: add(w, [-0.038 * (s === 'R' ? 1 : -1), -0.104, -0.019]), [`finger5-1.${s}`]: add(w, [0.006 * (s === 'R' ? 1 : -1), -0.101, -0.027]) });
const body = (wr, wl) => ({ height: 1.8, j: { ...hand(wr, 'R'), ...hand(wl, 'L') } });

// ---- 1. where a thing lies in a hand
{
  const P = body([-0.3, 0.9, 0], [0.3, 0.9, 0]), R = gripFrame(P, 'R'), L = gripFrame(P, 'L'), j = P.j;
  const kc = (s) => [0, 1, 2].map((i) => (j[`finger2-1.${s}`][i] + j[`finger5-1.${s}`][i]) / 2);
  for (const [F, s] of [[R, 'R'], [L, 'L']]) {
    ok(Math.abs(len(F.axis) - 1) < 1e-6 && Math.abs(len(F.palm) - 1) < 1e-6 && Math.abs(len(F.fwd) - 1) < 1e-6, `${s}: its directions are unit lengths`, [len(F.axis), len(F.palm), len(F.fwd)]);
    ok(Math.abs(dot(F.axis, F.palm)) < 1e-6 && Math.abs(dot(F.axis, F.fwd)) < 1e-6 && Math.abs(dot(F.palm, F.fwd)) < 1e-6, `${s}: across the palm, out of the palm and along the hand are square to each other`, [dot(F.axis, F.palm), dot(F.axis, F.fwd)]);
    ok(dot(F.axis, sub(j[`finger2-1.${s}`], j[`finger5-1.${s}`])) > 0.05, `${s}: "across" runs from the little finger to the index finger (a held stick comes out by the thumb)`, dot(F.axis, sub(j[`finger2-1.${s}`], j[`finger5-1.${s}`])));
    ok(dot(sub(F.c, kc(s)), F.palm) > 0.012 && dist(F.c, kc(s)) < 0.045, `${s}: the thing lies on the palm's side of the knuckles, within the closed fingers`, [dot(sub(F.c, kc(s)), F.palm), dist(F.c, kc(s))]);
    ok(dot(sub(F.c, j[`wrist.${s}`]), F.fwd) > 0.05, `${s}: nearer the knuckles than the wrist`, dot(sub(F.c, j[`wrist.${s}`]), F.fwd));
  }
  const mir = (v) => [-v[0], v[1], v[2]];
  ok(dist(mir(R.c), L.c) < 1e-6 && dist(mir(R.palm), L.palm) < 1e-6 && dist(mir(R.axis), L.axis) < 1e-6, 'the left hand is the mirror of the right (its palm faces the mirrored way)', [mir(R.c), L.c, mir(R.palm), L.palm]);
  // (the knuckles arch towards the BACK of the hand: the middle one stands out of the line from index to little finger)
  for (const [F, s] of [[R, 'R'], [L, 'L']]) ok(dot(sub(j[`finger3-1.${s}`], kc(s)), F.palm) < -0.003, `${s}: the palm faces away from the arch of the knuckles (it is the palm, not the back of the hand)`, dot(sub(j[`finger3-1.${s}`], kc(s)), F.palm));
  const big = gripFrame(P, 'R', { r: 0.03 }), thin = gripFrame(P, 'R', { r: 0.003 });
  ok(dot(sub(big.c, thin.c), R.palm) > 0.01, 'a thick thing lies further out of the palm than a thin one (the fingers go round it)', dot(sub(big.c, thin.c), R.palm));
  const small = gripFrame({ height: 0.9, j: Object.fromEntries(Object.entries(P.j).map(([k, v]) => [k, v.map((x) => x * 0.5)])) }, 'R', { r: 0 });
  ok(dist(small.c, gripFrame(P, 'R', { r: 0 }).c.map((x) => x * 0.5)) < 1e-6, 'a child\'s hand holds in the same place of a smaller hand', dist(small.c, R.c.map((x) => x * 0.5)));
}
// ---- 2. the thing itself, placed in that hand
{
  const P = body([-0.3, 0.9, 0], [0.3, 0.9, 0]), F = gripFrame(P, 'R', { r: 0.003 });
  const h = holdPose(F, { at: 0.12 });
  ok(Math.abs(len(h.dir) - 1) < 1e-6 && dist(add(h.p, h.dir.map((v) => v * 0.12)), F.c) < 1e-6, 'the place it is gripped at (0.12 m from its foot) lies in the grip', dist(add(h.p, h.dir.map((v) => v * 0.12)), F.c));
  ok(dot(h.dir, F.axis) > 0.999, 'it stands out of the thumb side of the fist', dot(h.dir, F.axis));
  ok(dot(holdPose(F, { at: 0.12, flip: true }).dir, F.axis) < -0.999, 'flip: out of the little-finger side (a thing carried head down)', dot(holdPose(F, { at: 0.12, flip: true }).dir, F.axis));
  const t = holdPose(F, { at: 0.12, tilt: [30, 0] });
  ok(Math.abs(Math.acos(dot(t.dir, F.axis)) * 180 / Math.PI - 30) < 0.01 && dot(t.dir, F.fwd) > 0.4 && dist(add(t.p, t.dir.map((v) => v * 0.12)), F.c) < 1e-6, 'tilt leans it towards the fingers by its degrees, still gripped at the same place', [Math.acos(dot(t.dir, F.axis)) * 180 / Math.PI, dot(t.dir, F.fwd)]);
}
// ---- 3. the fingers
{
  const sum = (sh) => sh.curl.slice(1).reduce((n, f) => n + f[0] + f[1] + f[2], 0);
  const thin = holdShape('fist', 0.003), thick = holdShape('fist', 0.03), pinch = holdShape('pinch', 0.003);
  ok(sum(thin) > sum(thick) + 60 && sum(thick) > sum(HANDS.relax) + 150, 'the fingers close further round a thin stem than round a thick handle, and both are closed hands', [sum(thin), sum(thick), sum(HANDS.relax)]);
  ok(thin.thumbIn >= 0.8 && thick.thumbIn >= 0.8, 'the thumb lies over the fingers', [thin.thumbIn, thick.thumbIn]);
  ok(pinch.curl[1][0] + pinch.curl[1][1] > 70 && pinch.curl[4][0] < pinch.curl[1][0] + 40 && pinch.thumbIn >= 0.7, 'a pinch holds between thumb and first fingers', pinch);
  ok(thin.curl.length === 5 && thin.curl.every((f) => f.length === 3 && f.every((d) => d >= 0 && d <= 110)), 'a shape the engine\'s hands can take (five fingers, three joints, degrees)', thin.curl);
}
// ---- 4. one thing through a sequence: it lies, is taken, is given, is set down
{
  // two people stand facing; a's right hand goes from her side to between them, b's right hand comes to meet it
  const lerp = (a, b, k) => a.map((v, i) => v + (b[i] - v) * Math.max(0, Math.min(1, k)));
  const ctxAt = (ts) => ({ poses: { a: body(lerp([-0.3, 0.75, 0], [-0.05, 1.0, 0.25], (ts - 1) / 1.5), [0.3, 0.9, 0]), b: body(ts < 4.2 ? lerp([0.25, 0.9, 0.9], [-0.02, 0.96, 0.33], (ts - 2.5) / 1.2) : lerp([-0.02, 0.96, 0.33], [0.66, 1.32, 0.86], (ts - 4.2) / 1.2), [-0.3, 0.9, 0.9]) } });
  // (it lies where her hand closes at the moment of taking — a film puts the thing where the hand really is, or aims the
  //  hand with wristFor — and he sets it down where his hand is at that moment)
  const gA = gripFrame(ctxAt(1.0).poses.a, 'R', { r: 0.003 }).c, aA = gripFrame(ctxAt(1.0).poses.a, 'R').axis, lie = (() => { const v = [aA[0], 0, aA[2]], l = Math.hypot(...v); return v.map((x) => x / l); })(), table = [gA[0] - lie[0] * 0.12, gA[1], gA[2] - lie[2] * 0.12], gB = gripFrame(ctxAt(5.6).poses.b, 'R', { r: 0.003 }).c, shelf = [gB[0], gB[1], gB[2] - 0.12], seen = [];
  const rose = prop({ id: 'rose', r: 0.003, at: 0.12, make: (p, dir) => ({ p, dir }), life: [
    { t: 0, on: table, dir: lie }, { t: 1.0, by: 'a', hand: 'R', over: 0.2 }, { t: 3.6, by: 'b', hand: 'R', over: 0.5 }, { t: 5.5, on: shelf, dir: [0, 0, 1], over: 0.3 } ] });
  let worst = 0, prev = null, missing = 0;
  for (let f = 0; f <= 7 * 30; f++) { const ts = f / 30, it = rose.item(ts, ctxAt(ts)); if (!it || !it.p || it.p.some((v) => !Number.isFinite(v))) { missing++; continue; } if (prev) { const d = dist(it.p, prev); if (d > 0.06 && process.env.SHOW) console.log('step', ts.toFixed(2), d.toFixed(3)); worst = Math.max(worst, d); } prev = it.p; seen.push(it); }
  ok(missing === 0, 'it is somewhere at every moment (it never vanishes)', missing);
  ok(worst < 0.09, 'it never jumps (no more than a hand moves between two pictures)', worst);
  ok(dist(rose.item(0.5, ctxAt(0.5)).p, table) < 1e-9 && rose.owner(0.5) === null, 'before it is taken it lies where it was put', rose.item(0.5, ctxAt(0.5)).p);
  { const ts = 2.4, F = gripFrame(ctxAt(ts).poses.a, 'R', { r: 0.003 }), it = rose.item(ts, ctxAt(ts)); ok(dist(add(it.p, it.dir.map((v) => v * 0.12)), F.c) < 1e-6 && rose.owner(ts) === 'a', 'carried, it is in her grip', dist(add(it.p, it.dir.map((v) => v * 0.12)), F.c)); }
  { const ts = 4.6, F = gripFrame(ctxAt(ts).poses.b, 'R', { r: 0.003 }), it = rose.item(ts, ctxAt(ts)); ok(dist(add(it.p, it.dir.map((v) => v * 0.12)), F.c) < 1e-6 && rose.owner(ts) === 'b', 'given, it is in his', dist(add(it.p, it.dir.map((v) => v * 0.12)), F.c)); }
  ok(dist(rose.item(6.5, ctxAt(6.5)).p, shelf) < 1e-9 && rose.owner(6.5) === null, 'set down, it stays where it was set', rose.item(6.5, ctxAt(6.5)).p);
  // the hands: hers closes as she takes it; in the hand-off his closes BEFORE hers opens (for a moment both hold it)
  const closed = (sh) => !!sh && typeof sh === 'object' && sh.curl && sh.curl[2][0] + sh.curl[2][1] > 90;
  const ha = rose.hands('a'), hb = rose.hands('b');
  ok(!ha(0.5)?.R && closed(ha(1.4).R) && closed(ha(3.0).R), 'her hand is her own until she takes it, then closed round it', [ha(0.5) ?? null, ha(1.4)]);
  let both = 0; for (let ts = 3.6; ts <= 4.2; ts += 1 / 30) if (closed(ha(ts)?.R) && closed(hb(ts)?.R)) both++;
  ok(both >= 3, 'in the hand-off both hands hold it for a moment (he has it before she lets go)', both);
  ok(!closed(ha(4.4)?.R) && closed(hb(4.4).R) && !hb(3.0)?.R, 'then hers opens and is her own again; his was his own before', [ha(4.4) ?? null, hb(3.0) ?? null]);
  ok(!closed(hb(6.2)?.R), 'and his opens when he has set it down', hb(6.2) ?? null);
  ok(ha(2)?.L === undefined && hb(4.6)?.L === undefined, 'the other hand is never touched', [ha(2), hb(4.6)]);
  const w = rose.where(2.4, ctxAt(2.4)); ok(w.owner === 'a' && w.hand === 'R' && dist(w.grip, gripFrame(ctxAt(2.4).poses.a, 'R', { r: 0.003 }).c) < 1e-6, 'a film can ask where it is and who has it (for cameras and reaching hands)', w);
}
// ---- 5. reaching: where the wrist must be for the grip to close on a point
{
  const P = body([-0.3, 0.9, 0], [0.3, 0.9, 0]), target = [0.1, 0.8, 0.3], w = wristFor(P, 'R', target, { r: 0.003 });
  const moved = { height: 1.8, j: Object.fromEntries(Object.entries(P.j).map(([k, v]) => [k, /\.R$/.test(k) ? add(v, sub(w, P.j['wrist.R'])) : v])) };
  ok(dist(gripFrame(moved, 'R', { r: 0.003 }).c, target) < 1e-6, 'with the wrist there (the hand turned as it is), the grip lies on the point', dist(gripFrame(moved, 'R', { r: 0.003 }).c, target));
}
if (fails.length) { console.error(`hold: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`hold: all ${checks} rules hold`);
