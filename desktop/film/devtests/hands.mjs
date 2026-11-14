// Hands hold themselves the way real hands do:  node devtests/hands.mjs        (exit 1 when a rule is broken)
// The rest pose of the body has its fingers fanned and its thumb out to the side (it is a pose for skinning, not for
// acting). A hand that is merely "open" or "relaxed" must not look like that: fingers lie side by side, the thumb rests
// near the index finger. A film can still ask for a spread hand (spread) and a thumb held out (thumbOut).
import fs from 'node:fs';
import { bodyOf, poseBody, HANDS } from '../engine/body.js';

const FX = JSON.parse(fs.readFileSync(new URL('./fixtures/hand_bones.json', import.meta.url), 'utf8'));
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2], len = (a) => Math.hypot(a[0], a[1], a[2]);
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k], DEG = 180 / Math.PI;
const qrot = (Q, b, v) => { const i = b * 4, x = Q[i], y = Q[i + 1], z = Q[i + 2], w = Q[i + 3], tx = 2 * (y * v[2] - z * v[1]), ty = 2 * (z * v[0] - x * v[2]), tz = 2 * (x * v[1] - y * v[0]); return [v[0] + w * tx + y * tz - z * ty, v[1] + w * ty + z * tx - x * tz, v[2] + w * tz + x * ty - y * tx]; };

/**
 * A body that is only two hands, standing in its rest pose for one frame. `baked` (radians) bends every finger bone in the
 * performance itself, the way the Stage leaves them: backwards on the right hand, forwards on the left.
 */
let BAKED = 0;
function hands() {
  const nb = FX.bones.length, data = new Float32Array(nb * 7);
  FX.bones.forEach((b, i) => { data.set(b.head, i * 7); data[i * 7 + 6] = 1; });
  if (BAKED) FX.bones.forEach((b, i) => { const m = /^finger(\d)-(\d)\.(L|R)$/.exec(b.name); if (!m) return; const a = BAKED * +m[2] * (m[3] === 'R' ? -1 : 1) / 2; data[i * 7 + 3] = Math.sin(a) * 0.6; data[i * 7 + 4] = Math.sin(a) * 0.8; data[i * 7 + 6] = Math.cos(a); });
  return bodyOf({ actors: { a: { bodyMeta: { bones: FX.bones, parts: [], landmarks: {} }, rigMeta: { fps: 24, frames: 1, data: [0, data.byteLength] }, ab: data.buffer } } }, 'a');
}

/** What a posed hand looks like in numbers (degrees, metres), in the hand's own frame. */
function measure(hand, s) {
  const B = hands(); poseBody(B, 0, null, { L: hand, R: hand });
  const F = B.fingers[s], w = B.bi[`wrist.${s}`], ax = (v) => qrot(B.Q, w, v), along = ax(F.along), palm = ax(F.palm);
  const ac = ax(F.across), k = dot(ac, along), sd = sub(ac, mul(along, k)), across = mul(sd, 1 / len(sd)); // across the hand, square to its direction (the knuckle line itself is slanted)
  const P = (b) => [B.P[b * 3], B.P[b * 3 + 1], B.P[b * 3 + 2]], bone = (b) => qrot(B.Q, b, sub([B.tail[b * 3], B.tail[b * 3 + 1], B.tail[b * 3 + 2]], [B.head[b * 3], B.head[b * 3 + 1], B.head[b * 3 + 2]]));
  const tip = (ch) => add(P(ch[2]), bone(ch[2]));
  const inPlane = (d) => Math.atan2(dot(d, across), dot(d, along)) * DEG;                 // 0 = straight along the hand, + towards the little finger
  const outOf = (d) => Math.asin(Math.max(-1, Math.min(1, dot(d, palm) / (len(d) || 1)))) * DEG; // + = towards the palm side
  const fingers = F.chains.slice(1).map((ch) => ({ yaw: inPlane(bone(ch[0])), knuckle: outOf(bone(ch[0])), curl: outOf(sub(tip(ch), P(ch[0]))), tip: tip(ch), root: P(ch[0]), mid: P(ch[1]) }));
  const T = F.chains[0], tv = sub(tip(T), P(T[0])), iv = bone(F.chains[1][0]);
  // the thumb against the index finger: the angle between them seen flat on the palm, and how near the thumb's tip comes to the index finger
  const thumbYaw = inPlane(iv) - inPlane(tv);
  const a = fingers[0].root, b = fingers[0].mid, ab = sub(b, a), u = Math.max(0, Math.min(1, dot(sub(tip(T), a), ab) / dot(ab, ab))), gap = len(sub(tip(T), add(a, mul(ab, u))));
  // where each fingertip ends up: how far in front of the palm (the flat of wrist and knuckles), and how far beyond its own knuckle along the hand
  const wr = P(w), front = fingers.map((f) => dot(sub(f.tip, wr), palm)), reach = fingers.map((f) => dot(sub(f.tip, f.root), along));
  return { knuckle: fingers.map((f) => f.knuckle), front, reach, thumbFront: dot(sub(tip(T), wr), palm), yaw: fingers.map((f) => f.yaw), fan: fingers[3].yaw - fingers[0].yaw, gaps: [1, 2, 3].map((i) => fingers[i].yaw - fingers[i - 1].yaw), curl: fingers.map((f) => f.curl), thumbYaw, thumbFwd: outOf(tv), gap };
}

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(1) : JSON.stringify(got)}`); };
const within = (v, lo, hi) => v >= lo && v <= hi, r1 = (v) => +v.toFixed(1);

for (const s of ['L', 'R']) {
  const open = measure('open', s), relax = measure('relax', s), grip = measure('grip', s), fist = measure('fist', s), point = measure('point', s);
  if (process.argv.includes('--print')) console.log(s, JSON.stringify({ open: { fan: r1(open.fan), gaps: open.gaps.map(r1), thumbYaw: r1(open.thumbYaw), thumbFwd: r1(open.thumbFwd), gap: +open.gap.toFixed(3) }, relax: { fan: r1(relax.fan), gaps: relax.gaps.map(r1), thumbYaw: r1(relax.thumbYaw), thumbFwd: r1(relax.thumbFwd), gap: +relax.gap.toFixed(3) }, grip: { fan: r1(grip.fan) }, fist: { fan: r1(fist.fan) } }));

  // fingers side by side: a slight natural fan, never a star
  ok(within(open.fan, 5, 16), `${s} open hand: index to little finger fan out 5–16°`, open.fan);
  ok(open.gaps.every((g) => within(g, 0.8, 7)), `${s} open hand: neighbouring fingers 0.8–7° apart, in order`, open.gaps.map(r1));
  ok(within(relax.fan, 3, 14), `${s} relaxed hand: index to little finger fan out 3–14°`, relax.fan);
  ok(relax.gaps.every((g) => within(g, 0.5, 6)), `${s} relaxed hand: neighbouring fingers 0.5–6° apart, in order`, relax.gaps.map(r1));
  ok(within(grip.fan, -3, 9), `${s} gripping hand: fingers together (fan −3…9°)`, grip.fan);
  ok(within(fist.fan, -3, 9), `${s} fist: fingers together (fan −3…9°)`, fist.fan);
  ok(within(point.yaw[0], -8, 8), `${s} pointing hand: the index finger points straight along the hand (±8°)`, point.yaw[0]);

  // fingers close towards the palm, never backwards: a fist's fingertips lie in front of the palm, pulled back behind their knuckles
  const mm = (a) => a.map((v) => Math.round(v * 1000));
  ok(fist.front.every((v) => within(v, 0.004, 0.05)), `${s} fist: every fingertip in front of the palm (4–50 mm)`, mm(fist.front));
  ok(fist.reach.every((v) => v < 0.012), `${s} fist: every fingertip pulled back to its knuckle or behind it`, mm(fist.reach));
  ok(grip.front.every((v) => within(v, 0.01, 0.075)), `${s} grip: every fingertip in front of the palm (10–75 mm), wrapped round a handle`, mm(grip.front));
  ok(relax.front.every((v, i) => v > open.front[i] + 0.004), `${s} relaxed hand: fingertips further towards the palm than in an open hand`, [mm(open.front), mm(relax.front)]);
  ok(fist.thumbFront > 0.01, `${s} fist: the thumb closes in front of the palm, over the fingers`, fist.thumbFront * 1000);

  // a relaxed hand curls more towards the little finger, an open one is nearly flat
  ok(relax.curl[3] > relax.curl[0] + 4, `${s} relaxed hand: the little finger curls more than the index`, relax.curl.map(r1));
  ok(open.curl.every((c) => within(c, -6, 22)), `${s} open hand: fingers nearly flat (−6…22° towards the palm)`, open.curl.map(r1));

  // the thumb rests near the index finger, a little in front of the palm; it never sticks out sideways and never crosses the finger
  ok(within(relax.thumbYaw, 8, 38), `${s} relaxed hand: thumb 8–38° off the index finger`, relax.thumbYaw);
  ok(within(open.thumbYaw, 22, 52), `${s} open hand: thumb 22–52° off the index finger`, open.thumbYaw);
  ok(within(relax.thumbFwd, 4, 45), `${s} relaxed hand: thumb a little in front of the palm (4–45°)`, relax.thumbFwd);
  ok(relax.gap > 0.014 && open.gap > 0.014, `${s} open and relaxed hands: the thumb's tip stays clear of the index finger (> 14 mm)`, [relax.gap, open.gap].map((v) => +v.toFixed(3)));

  // a film can still ask for more: a spread hand, a thumb held out, and blends of two hands
  const wide = measure({ ...HANDS.open, spread: 1 }, s), out = measure({ ...HANDS.open, thumbOut: 1 }, s), half = measure(['open', 'fist', 0.5], s);
  ok(within(wide.fan, open.fan + 14, 48), `${s} spread: 1 fans the fingers well apart (at least 14° more than open, at most 48°)`, wide.fan);
  ok(out.thumbYaw > open.thumbYaw + 20, `${s} thumbOut: 1 holds the thumb out (at least 20° more than open)`, out.thumbYaw - open.thumbYaw);
  ok(half.knuckle[1] > open.knuckle[1] + 15 && half.knuckle[1] < fist.knuckle[1] - 15, `${s} a blend of open and fist bends the knuckles between the two`, [open.knuckle[1], half.knuckle[1], fist.knuckle[1]].map(r1));
}
// what the hand does is the film's choice: finger bends that came with the performance change nothing
for (const s of ['L', 'R']) for (const name of ['relax', 'grip']) {
  BAKED = 0; const clean = measure(name, s); BAKED = 1.1; const bent = measure(name, s); BAKED = 0;
  const off = Math.max(...clean.front.map((v, i) => Math.abs(v - bent.front[i])), ...clean.reach.map((v, i) => Math.abs(v - bent.reach[i])), Math.abs(clean.thumbFront - bent.thumbFront));
  ok(off < 0.001, `${s} ${name}: the same hand whatever finger bends the performance carries (within 1 mm)`, off * 1000);
}
// a film that poses one hand leaves the other to the engine: closed round what it carries, loose otherwise — never as the performance left it
{
  const { handsFor } = await import('../engine/bodydraw.js');
  const pose = { height: 1.8, j: { 'wrist.L': [0.3, 1, 0], 'wrist.R': [-0.3, 1, 0] }, props: { sword: { pos: [-0.3, 1.02, 0.05], q: [0, 0, 0, 1], visible: true } } };
  const got = typeof handsFor === 'function' ? handsFor(pose, { L: 'open' }) : null;
  ok(got && got.L === 'open' && got.R === 'grip', 'a film that poses only the left hand: the right hand still closes round the sword it holds', got);
  const none = typeof handsFor === 'function' ? handsFor({ ...pose, props: {} }, undefined) : null;
  ok(none && none.L === 'relax' && none.R === 'relax', 'a film that poses no hand: both hang relaxed', none);
}
// both hands are the same hand, mirrored
{ const l = measure('relax', 'L'), r = measure('relax', 'R'); ok(Math.abs(l.fan - r.fan) < 1.5 && Math.abs(l.thumbYaw - r.thumbYaw) < 1.5, 'left and right hands mirror each other (within 1.5°)', [l.fan - r.fan, l.thumbYaw - r.thumbYaw].map(r1)); }

if (fails.length) { console.error(`hands: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`hands: ${checks} rules hold`);
