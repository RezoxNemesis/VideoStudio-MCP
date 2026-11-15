// Sensei sees the body as it is dressed: node devtests/worn.mjs        (exit 1 when a rule is broken)
// Rounds drawn from the joints cannot tell that a hand has sunk into a bagged tunic, or that a scabbard hangs through
// a coat: the body as it is dressed has its own outline. Sensei reads that outline from the real body (the trunk as a
// shape with an inside) and looks at arms, hands, a held blade and a worn scabbard against it.
import fs from 'node:fs';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(3) : JSON.stringify(got)}`); };
const G = await import('../sensei/geo.js').catch((e) => ({ error: String(e) }));
const POSE = JSON.parse(fs.readFileSync(new URL('./fixtures/pose_standing.json', import.meta.url), 'utf8'));
const J0 = POSE.j, sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k], len = (a) => Math.hypot(a[0], a[1], a[2]);
const joints = (ch = {}) => ({ ...J0, ...ch });

for (const f of ['trunkShape', 'trunkClear', 'trunkFrame', 'wornChecks']) ok(typeof G[f] === 'function', `sensei/geo.js gives ${f}`, G.error ?? typeof G[f]);
if (fails.length) { console.error(`worn: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }

// ---- a body of points alone, in its rest pose (x to its left, y up, z forward): hips 0.93 m up, a trunk 0.17 m wide
// to each side and 0.12 m deep before and behind, from under the seat to the armpits; and arms held out (no part of it)
const names = ['root', 'upperleg01.L', 'upperleg01.R', 'upperarm01.L', 'spine03'], bi = Object.fromEntries(names.map((n, i) => [n, i]));
const head = new Float32Array([0, 0.95, 0, 0.09, 0.93, 0, -0.09, 0.93, 0, 0.2, 1.43, 0, 0, 1.18, 0]);
const pts = [], si = [], sw = [], RX = 0.17, RZ = 0.12;
for (let y = -0.2; y <= 0.46; y += 0.02) for (let a = 0; a < 48; a++) { const an = a / 48 * 2 * Math.PI; pts.push(Math.cos(an) * RX, 0.93 + y, Math.sin(an) * RZ); si.push(y > 0.2 ? 4 : 0, 0, 0, 0); sw.push(1, 0, 0, 0); }
for (let x = 0.2; x <= 0.7; x += 0.02) { pts.push(x, 1.4, 0); si.push(3, 0, 0, 0); sw.push(1, 0, 0, 0); }
const B = { bi, head, height: POSE.height, parts: [{ name: 'tunic', n: pts.length / 3, pos: Float32Array.from(pts), si: Uint16Array.from(si), sw: Float32Array.from(sw) }] };
const S = G.trunkShape(B);

// ---- 1. the trunk as a shape with an inside
ok(!!S, 'a body with a trunk has a trunk shape', S);
ok(G.trunkClear(S, 0, 0.1, 0) < -0.08, 'the middle of the belly is inside it', G.trunkClear(S, 0, 0.1, 0));
ok(Math.abs(G.trunkClear(S, RX, 0.1, 0)) < 0.02 && Math.abs(G.trunkClear(S, 0, 0.1, RZ)) < 0.02, 'its outline is the body\'s own (the side, the front)', [G.trunkClear(S, RX, 0.1, 0), G.trunkClear(S, 0, 0.1, RZ)]);
ok(G.trunkClear(S, 0.45, 0.47, 0) > 0.2, 'an arm held out is no part of it', G.trunkClear(S, 0.45, 0.47, 0));
ok(G.trunkClear(S, 0, 0.9, 0) === Infinity && G.trunkClear(S, 0, -0.6, 0) === Infinity, 'above the armpits and below the seat there is no trunk to be inside of', [G.trunkClear(S, 0, 0.9, 0), G.trunkClear(S, 0, -0.6, 0)]);

// ---- the fixture's own directions (it stands turned): a point so far to its left, up and forward of the middle of its hips
const F = G.trunkFrame(J0);
ok(!!F && Math.abs(len(F.up) - 1) < 1e-6 && F.up[1] > 0.9, 'trunkFrame(joints): the middle of the hips and the body\'s own directions', F && F.up);
const at = (x, y, z, f = 0) => { const sd = F.side(f), fr = F.front(f); return add(F.pel, add(mul(sd, x), add(mul(F.up, y), mul(fr, z)))); };
const kinds = (L) => L.map((p) => `${p.kind}${p.side ? ':' + p.side : ''}`).join(' ');

// ---- 2. arms and hands
{
  ok(G.wornChecks(J0, {}, S, 1).length === 0, 'a body standing with its arms at its sides: nothing to say', kinds(G.wornChecks(J0, {}, S, 1)));
  // the right hand taken into the middle of the belly
  const belly = at(0, 0.12, 0), P1 = G.wornChecks(joints({ 'wrist.R': belly, 'finger3-1.R': add(belly, [0.03, 0, 0]) }), {}, S, 1);
  ok(P1.some((p) => p.kind === 'arm-in-body' && p.side === 'R' && p.depth > 0.05), 'a right hand inside the belly is found: the arm is in the body, and how deep', P1.map((p) => [p.kind, p.side, +p.depth?.toFixed(3)]));
  ok(!P1.some((p) => p.kind === 'arm-in-body' && p.side === 'L'), 'the left arm, which is where it was, is not', kinds(P1));
  ok(P1.every((p) => Array.isArray(p.at) && p.at.length === 3 && typeof p.part === 'string'), 'each finding says where it is and which part', P1);
  // a hand laid on the belly from outside (its middle a hand's thickness before the cloth), the forearm before the body
  const on = at(0, 0.12, RZ + 0.035), P2 = G.wornChecks(joints({ 'finger3-1.R': on, 'wrist.R': at(-0.09, 0.14, RZ + 0.05), 'lowerarm01.R': at(-0.27, 0.22, 0.1) }), {}, S, 1);
  ok(!P2.some((p) => p.kind === 'arm-in-body'), 'a hand laid on the belly from outside is no mistake', P2.map((p) => [p.kind, p.side, +p.depth?.toFixed(3)]));
  // a forearm that crosses the belly inside the cloth, the hand out at the other side (a hand sent to a hilt worn in the body)
  const P3 = G.wornChecks(joints({ 'lowerarm01.R': at(-0.2, 0.16, 0.02), 'wrist.R': at(0.12, 0.12, 0.06), 'finger3-1.R': at(0.2, 0.11, 0.08) }), {}, S, 1);
  ok(P3.some((p) => p.kind === 'arm-in-body' && p.side === 'R' && /forearm/.test(p.part)), 'a forearm that crosses inside the belly is found, though hand and elbow are outside', P3.map((p) => [p.kind, p.part, +p.depth?.toFixed(3)]));
}

// ---- 3. the blade of a sword in the hand, and a sword and scabbard worn at the hip
{
  // (a rotation taking +Y — a blade's line — to a direction)
  const qTo = (d) => { const l0 = len(d), u = [d[0] / l0, d[1] / l0, d[2] / l0], cx = [u[2], 0, -u[0]], w0 = 1 + u[1], l = Math.hypot(cx[0], cx[1], cx[2], w0) || 1; return [cx[0] / l, cx[1] / l, cx[2] / l, w0 / l]; };
  const w = J0['wrist.R'], toBody = sub(at(0, 0.15, 0), w);
  const through = G.wornChecks(J0, { sword: { pos: w, q: qTo(toBody), visible: true } }, S, 1);
  ok(through.some((p) => p.kind === 'blade-in-body'), 'a blade that points from the hand into the body is found', kinds(through));
  ok(!G.wornChecks(J0, { sword: { pos: w, q: qTo(mul(toBody, -1)), visible: true } }, S, 1).some((p) => p.kind === 'blade-in-body'), 'a blade that points away from the body is not', 0);
  // worn at the hip the way a sword is worn: the mouth before the left hip, the length back, down and out past the hip
  const ax = sub(at(0.43, -0.42, -0.57), at(0.2, 0.09, 0.13)), q = qTo(ax), n = len(ax), A = mul(ax, 1 / n), mouth = at(0.2, 0.09, 0.13);
  const good = G.wornChecks(J0, { scabbard: { pos: mouth, q, visible: true }, sword: { pos: sub(mouth, mul(A, 0.124)), q, visible: true } }, S, 1);
  ok(!good.some((p) => p.kind === 'worn-in-body' || p.kind === 'blade-in-body'), 'a sword worn outside the clothes at the hip is as it should be (and a sword home in its scabbard is no blade in a hand)', kinds(good));
  // the way it used to hang: the mouth inside the hip, the length back across behind the body
  const ax2 = sub(at(0.04, -0.28, -0.65), at(0.15, 0.085, 0.04)), q2 = qTo(ax2), A2 = mul(ax2, 1 / len(ax2)), m2 = at(0.15, 0.085, 0.04);
  const bad = G.wornChecks(J0, { scabbard: { pos: m2, q: q2, visible: true }, sword: { pos: sub(m2, mul(A2, 0.02)), q: q2, visible: true } }, S, 1);
  ok(bad.some((p) => p.kind === 'worn-in-body' && /scabbard/.test(p.part)), 'a scabbard that hangs through the body is found', bad.map((p) => [p.kind, p.part, +p.depth?.toFixed(3)]));
  ok(bad.some((p) => p.kind === 'worn-in-body' && /hilt/.test(p.part)), 'and so is a hilt that sits in the belly (no hand can take it)', bad.map((p) => [p.kind, p.part, +p.depth?.toFixed(3)]));
}

// ---- 4. a body that has no shape to read (a figure of rounds only) is passed over
ok(G.trunkShape({ bi: {}, head: new Float32Array(0), parts: [] }) === null && G.wornChecks(J0, {}, null, 1).length === 0, 'without a real body there is nothing to read and nothing is said', 0);

if (fails.length) { console.error(`worn: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`worn: ${checks} rules hold`);
