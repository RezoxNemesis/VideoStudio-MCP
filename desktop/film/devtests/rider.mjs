// A rider sits on the horse: node devtests/rider.mjs        (exit 1 when a rule is broken)
// A rider used to be seated by moving the joints of the simple figures; a real body knew nothing of it (it stood on
// the ground where it was, or its legs went through the horse). Now the whole performed body is carried to the saddle
// and bent into a seat: thighs out and forward along the barrel, lower legs hanging down its sides, hands at the reins.
import { Horse } from '../engine/horse.js';
import { seat } from '../kit/riders.js';
import { trunkHalfWidth } from '../engine/horsemesh.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
/** a little body standing somewhere, facing +x (its left is −z): joints, a tree, and a pose made from them */
function rider() {
  const names = ['root', 'spine01', 'upperleg01.L', 'lowerleg01.L', 'foot.L', 'upperleg01.R', 'lowerleg01.R', 'foot.R', 'upperarm01.L', 'lowerarm01.L', 'wrist.L', 'upperarm01.R', 'lowerarm01.R', 'wrist.R'];
  const parent = [-1, 0, 0, 2, 3, 0, 5, 6, 1, 8, 9, 1, 11, 12];
  // in the body's own frame: x forward, y up, z to its right
  const own = [[0, 1.0, 0], [0, 1.15, 0], [0, 0.95, -0.09], [0, 0.52, -0.09], [0, 0.09, -0.09], [0, 0.95, 0.09], [0, 0.52, 0.09], [0, 0.09, 0.09], [0, 1.45, -0.2], [0, 1.17, -0.2], [0, 0.92, -0.2], [0, 1.45, 0.2], [0, 1.17, 0.2], [0, 0.92, 0.2]];
  const at = own.map(([x, y, z]) => [3 + x, y, -2 + z]);
  const P = new Float64Array(names.length * 3), Q = new Float64Array(names.length * 4);
  at.forEach((p, i) => { P.set(p, i * 3); Q[i * 4 + 3] = 1; });
  const B = { nb: names.length, bi: Object.fromEntries(names.map((n, i) => [n, i])), parent: Int16Array.from(parent), P, Q };
  return { j: Object.fromEntries(names.map((n, i) => [n, at[i]])), q: Object.fromEntries(names.map((n) => [n, [0, 0, 0, 1]])), props: {}, height: 1.8, body: { B, t: 0, xf: null } };
}
const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2];

for (const [gait, d] of [['stand', 0], ['walk', 2.3]]) {
  const h = new Horse({}).place([1, 4 + d], 0.6, d, gait, d / 1.6), p = seat(rider(), h), s = h.saddle(), L = (q) => h.local(q);
  const hips = mid(p.j['upperleg01.L'], p.j['upperleg01.R']), lh = L(hips);
  ok(Math.abs(lh[2]) < 0.01 && lh[0] > -0.15 && lh[0] < 0.3, `${gait}: the rider sits on the middle of the back, behind the withers`, lh);
  ok(hips[1] > s.p[1] && hips[1] < s.p[1] + 0.14, `${gait}: on the saddle, not in the horse and not above it`, hips[1] - s.p[1]);
  const side = L(p.j['upperleg01.L'])[2] - L(p.j['upperleg01.R'])[2];
  ok(side > 0.15, `${gait}: facing the way the horse goes (the left hip on its left)`, side);
  for (const S of ['L', 'R']) {
    const sg = S === 'L' ? 1 : -1, hip = L(p.j[`upperleg01.${S}`]), kn = L(p.j[`lowerleg01.${S}`]), an = L(p.j[`foot.${S}`]);
    ok(kn[0] > hip[0] + 0.15 && kn[1] < hip[1] - 0.1, `${gait}: the ${S} thigh goes forward and down from the hip`, [kn[0] - hip[0], kn[1] - hip[1]]);
    const wk = trunkHalfWidth(kn[0], kn[1]);
    ok(sg * kn[2] > wk + 0.02 && sg * kn[2] < wk + 0.16, `${gait}: the ${S} knee lies on the barrel (not in it, not sticking out)`, [sg * kn[2], wk]);
    const wa = trunkHalfWidth(an[0], an[1]);
    ok(sg * an[2] > wa + 0.02 && sg * an[2] < Math.max(wa, 0.2) + 0.2, `${gait}: the ${S} lower leg hangs down the horse's side`, [sg * an[2], wa]);
    const shin = [an[0] - kn[0], an[1] - kn[1], an[2] - kn[2]], tilt = Math.acos(-shin[1] / Math.hypot(...shin)) * 180 / Math.PI;
    ok(tilt < 28, `${gait}: the ${S} lower leg hangs nearly straight down`, tilt);
    ok(an[1] > 0.6 && an[1] < kn[1] - 0.25, `${gait}: the ${S} foot hangs about level with the belly`, an[1]);
    const hand = L(p.j[`wrist.${S}`]), sh = L(p.j[`upperarm01.${S}`]);
    ok(hand[0] > sh[0] + 0.15 && hand[1] > lh[1] && hand[1] < sh[1] - 0.1 && Math.abs(hand[2]) < 0.2, `${gait}: the ${S} hand is at the reins, in front of the body above the withers (the hands nearer each other than the shoulders)`, hand);
  }
  const bends = (p.body.after ?? []).filter((a) => a.bend).map((a) => a.bend.bone);
  ok(['upperleg01.L', 'upperleg01.R', 'lowerleg01.L', 'lowerleg01.R'].every((b) => bends.includes(b)), `${gait}: the real body is bent the same way as its joints`, bends);
  ok((p.body.after ?? []).some((a) => a.turn) && (p.body.after ?? []).some((a) => a.shift), `${gait}: and carried to the saddle with them`, (p.body.after ?? []).map((a) => Object.keys(a)[0]));
}
{
  // without the reins (a rider who acts with his arms keeps them)
  const h = new Horse({}).place([0, 0], 0, 0, 'stand', 0), p = seat(rider(), h, { arms: false });
  ok(!(p.body.after ?? []).some((a) => a.bend && /arm/.test(a.bend.bone)), 'arms: false leaves the arms to the performance', 0);
}

if (fails.length) { console.error(`rider: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`rider: ${checks} rules hold`);
