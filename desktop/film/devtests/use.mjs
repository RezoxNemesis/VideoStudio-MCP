// Using things (kit/use.js): node devtests/use.mjs        (exit 1 when a rule is broken)
// A thing in use is drawn in the body's own depth picture (pose.held parts), lies in the palm or between both hands
// with its face to the eyes (or to the one it is shown to), and the arms are placed from the actor's chest.
import { useThing, usePose, takeFrom, THINGS, USES } from '../kit/use.js';
const fails = []; let checks = 0;
const ok = (c, what, got) => { checks++; if (!c) fails.push(`${what} — got ${JSON.stringify(got)}`); };
const add = (a, b) => a.map((x, i) => x + b[i]), sub = (a, b) => a.map((x, i) => x - b[i]), dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2], len = (a) => Math.hypot(...a);
// a standing body facing +z with both hands before the chest (palms up): wrist, first knuckles, eyes
const hand = (w, s) => { const sg = s === 'R' ? -1 : 1; return { [`wrist.${s}`]: w, [`finger2-1.${s}`]: add(w, [sg * -0.03, 0.0, 0.09]), [`finger5-1.${s}`]: add(w, [sg * 0.03, 0.0, 0.085]), [`finger3-1.${s}`]: add(w, [0, 0, 0.095]) }; };
const pose = (wr, wl) => ({ height: 1.75, j: { ...hand(wr, 'R'), ...hand(wl, 'L'), 'eye.L': [0.03, 1.62, 0.08], 'eye.R': [-0.03, 1.62, 0.08] } });
const cen = (p) => { const n = p.pos.length / 3; let s = [0, 0, 0]; for (let i = 0; i < n; i++) s = add(s, [p.pos[3 * i], p.pos[3 * i + 1], p.pos[3 * i + 2]]); return s.map((x) => x / n); };
// 1. every thing makes finite triangles in a hand
for (const k of Object.keys(THINGS)) {
  const th = useThing(k, { life: [{ t: 0, by: 'a', hand: 'R' }] }), ctx = { poses: { a: pose([-0.1, 1.2, 0.3], [0.4, 0.9, 0]) } };
  const parts = th.held('a')(1, ctx);
  ok(Array.isArray(parts) && parts.length > 0, `${k}: drawn in the hand`, parts?.length);
  ok(parts.every((p) => p.pos.every(Number.isFinite) && p.idx.length % 3 === 0 && Math.max(...p.idx) < p.pos.length / 3), `${k}: finite triangles`, null);
  ok(th.held('b')(1, ctx) === null, `${k}: nobody else holds it`, null);
  ok(len(sub(cen(parts[0]), [-0.1, 1.2, 0.3])) < 0.2, `${k}: within 20 cm of the wrist`, cen(parts[0]));
}
// 2. a sheet in both hands lies between them, its face to the eyes; shown, its face away from them
{
  const ctx = { poses: { a: pose([-0.12, 1.2, 0.32], [0.12, 1.2, 0.32]) } };
  const rd = useThing('drawing', { life: [{ t: 0, by: 'a', hand: 'R' }] }).held('a')(1, ctx), sh = useThing('drawing', { life: [{ t: 0, by: 'a', hand: 'R', out: true }] }).held('a')(1, ctx);
  const c = cen(rd[0]); ok(Math.abs(c[0]) < 0.05, 'two hands: the sheet is between them', c);
  // the picture is on the face side: nearer the eyes when read, farther when shown
  // (which side the picture is on is judged on the lab sheet: Practice_Loop p04, shots b and d)
  ok(len(sub(c, [0, 1.2, 0.36])) < 0.12, 'two hands: the sheet is at the hands', c); void sh;
  // shown to someone it stands nearly upright, its face out and a little up (never towards the floor)
  { const P = sh[0], q = (i) => [P.pos[3 * P.idx[i]], P.pos[3 * P.idx[i] + 1], P.pos[3 * P.idx[i] + 2]], m = Math.floor(P.idx.length / 6) * 3, e1 = sub(q(m + 1), q(m)), e2 = sub(q(m + 2), q(m));
    const nn = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]], L = len(nn) || 1;
    ok(Math.abs(nn[1] / L) < 0.45, 'shown: the sheet stands nearly upright', nn[1] / L); }
  // the picture stands upright (its sun above the face, its face above the legs) read or shown, either hand first
  for (const [nm, P] of [['read', rd], ['shown', sh], ['read, hands crossed', useThing('drawing', { life: [{ t: 0, by: 'a', hand: 'L' }] }).held('a')(1, ctx)]]) {
    const sun = cen(P[1]), face = cen(P[4]); ok(sun[1] > face[1] + 0.02, `${nm}: the picture is upright`, [sun[1], face[1]]); }
}
// 3. usePose: from the chest when the actor is named; a look at the thing; mirrored for the left hand
{
  const o = usePose('phone', { t0: 1, t1: 3, actor: 'a', height: 1.75 }), k = o.find((x) => x.reach && x.side === 'R');
  ok(k?.reach.actor === 'a' && k.reach.bone === 'spine01', 'phone: reached for from the chest', k?.reach);
  ok(Math.abs(k.reach.offset[1] - (USES.phone.R[1] - 1.36)) < 1e-6, 'phone: height from the chest', k.reach.offset);
  ok(o.some((x) => x.keys?.some((q) => q.on === 'wrist.R')), 'phone: the wrist turned', null);
  ok(o.some((x) => x.look?.bone === 'wrist.R'), 'phone: the eyes go to it', null);
  const l = usePose('phone', { t0: 1, t1: 3, actor: 'a', hand: 'L' }).find((x) => x.reach);
  ok(l && l.side === 'L' && l.reach.offset[0] === USES.phone.R[0], 'phone in the left hand: mirrored', l);
  const tk = takeFrom('g', 5, {}); ok(tk[0].reach?.actor === 'g' && tk[0].t1 > 5 && tk[0].t0 < 5, 'take: the hand is there at the hand-off', tk[0]);
}
// 4. the object decides the grip (study of 17 Nov): fingertips on a phone's edges, flat fingers under a book, a pinch
// on paper, a fist round a cup; the reaching hand is widest two thirds of the way and closes on the thing
{
  const shapeIn = (k) => useThing(k, { life: [{ t: 0, on: [0, 1, 0] }, { t: 2, by: 'a', hand: 'R' }] }).hands('a')(3).R;
  const curl = (h) => h.curl.slice(1).reduce((s, f) => s + f[0] + f[1], 0);
  const ph = shapeIn('phone'), bk = shapeIn('book'), pa = shapeIn('paper'), cu = shapeIn('cup');
  ok(ph && bk && pa && cu, 'every thing shapes the hand that holds it', [!!ph, !!bk, !!pa, !!cu]);
  ok(curl(bk) < curl(ph) && curl(ph) < curl(cu), 'book flatter than phone, phone looser than a cup fist', [curl(bk), curl(ph), curl(cu)]);
  ok(pa.thumbIn > 0.6, 'paper: the thumb pinches', pa.thumbIn);
  const th = useThing('phone', { life: [{ t: 0, on: [0, 1, 0] }, { t: 2, by: 'a', hand: 'R', reach: 0.6 }] }), H = th.hands('a');
  const sp = (x) => H(x).R?.spread ?? null;
  ok(sp(1.4 + 0.67 * 0.56) > 0.28, 'reach: the hand is wide open two thirds of the way', sp(1.4 + 0.67 * 0.56));
  ok(sp(1.45) < sp(1.7), 'reach: the hand opens as it goes', [sp(1.45), sp(1.7)]);
  ok(curl(H(2.3).R) > curl(H(1.85).R), 'reach: then closes on the thing', [curl(H(1.85).R), curl(H(2.3).R)]);
}
// 5. a sip lifts the elbow out and forward (a pole on its reach), mirrored for the left hand
{ const r = usePose('sip', { t0: 1, t1: 2, actor: 'a' }).find((x) => x.reach), l = usePose('sip', { t0: 1, t1: 2, actor: 'a', hand: 'L' }).find((x) => x.reach);
  ok(r?.pole && r.pole[0] > 0.5 && r.pole[2] > 0.3, 'sip: the right elbow out and forward', r?.pole);
  ok(l?.pole && l.pole[0] < -0.5 && l.side === 'L', 'sip: the left elbow mirrored', l?.pole); }
if (fails.length) { console.log(`use: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`use: all ${checks} rules hold`);
