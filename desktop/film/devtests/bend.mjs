// A film's own bends of a performed body: node devtests/bend.mjs        (exit 1 when a rule is broken)
// A bone can be turned about its own joint after the performance is posed (a rider's thighs lie along the horse, a
// soldier's forearm carries his spear upright): everything that hangs on the bone goes with it, nothing else moves.
import { bendBone, bodyAxes } from '../engine/body.js';
import { bendPose, plantSpear, swingArms } from '../kit/combat.js';
import { qrot } from '../engine/core.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
/** a little body: hips, two legs of thigh, shin and foot, a spine; standing, facing +z (its left is +x) */
function body() {
  const names = ['root', 'upperleg01.L', 'lowerleg01.L', 'foot.L', 'upperleg01.R', 'lowerleg01.R', 'foot.R', 'spine01'], parent = [-1, 0, 1, 2, 0, 4, 5, 0];
  const at = [[0, 1, 0], [0.1, 0.95, 0], [0.1, 0.5, 0], [0.1, 0.08, 0], [-0.1, 0.95, 0], [-0.1, 0.5, 0], [-0.1, 0.08, 0], [0, 1.1, 0]];
  const P = new Float64Array(names.length * 3), Q = new Float64Array(names.length * 4);
  at.forEach((p, i) => { P.set(p, i * 3); Q[i * 4 + 3] = 1; });
  return { nb: names.length, bi: Object.fromEntries(names.map((n, i) => [n, i])), parent: Int16Array.from(parent), P, Q };
}
const pos = (B, n) => Array.from(B.P.subarray(B.bi[n] * 3, B.bi[n] * 3 + 3)), near = (a, b, e = 1e-6) => a.every((v, i) => Math.abs(v - b[i]) < e);

{
  const B = body(), A = bodyAxes(B);
  ok(near(A.side, [1, 0, 0]) && near(A.up, [0, 1, 0]) && near(A.fwd, [0, 0, 1]), 'a body has its own axes: its left, up, the way it faces', A);
  // lift the left thigh forward a quarter turn: the knee comes to hip height in front, the shin and foot go with it
  bendBone(B, 'upperleg01.L', A.side, -Math.PI / 2);
  ok(near(pos(B, 'upperleg01.L'), [0.1, 0.95, 0]), 'a bent bone stays at its joint', pos(B, 'upperleg01.L'));
  ok(near(pos(B, 'lowerleg01.L'), [0.1, 0.95, 0.45]), 'what hangs on it turns about that joint (a thigh lifted forward: the knee in front of the hip)', pos(B, 'lowerleg01.L'));
  ok(near(pos(B, 'foot.L'), [0.1, 0.95, 0.87]), 'all the way down the chain', pos(B, 'foot.L'));
  ok(near(pos(B, 'lowerleg01.R'), [-0.1, 0.5, 0]) && near(pos(B, 'spine01'), [0, 1.1, 0]) && near(pos(B, 'root'), [0, 1, 0]), 'nothing else moves', [pos(B, 'lowerleg01.R'), pos(B, 'spine01')]);
  const q = Array.from(B.Q.subarray(B.bi['foot.L'] * 4, B.bi['foot.L'] * 4 + 4)), s = Math.SQRT1_2;
  ok(near(q, [-s, 0, 0, s]), 'the bones turn with their places (so the skin on them does)', q);
  ok(near(Array.from(B.Q.subarray(B.bi['foot.R'] * 4, B.bi['foot.R'] * 4 + 4)), [0, 0, 0, 1]), 'and the others keep theirs', 0);
  // then bend the knee back: the shin hangs down from the lifted knee
  bendBone(B, 'lowerleg01.L', A.side, Math.PI / 2);
  ok(near(pos(B, 'foot.L'), [0.1, 0.95 - 0.42, 0.45]), 'a knee bent after the thigh: the shin hangs from where the knee now is', pos(B, 'foot.L'));
  // out to the side: about the way the body faces
  const C = body(); bendBone(C, 'upperleg01.L', bodyAxes(C).fwd, Math.PI / 6);
  ok(pos(C, 'lowerleg01.L')[0] > 0.1 + 0.2 && Math.abs(pos(C, 'lowerleg01.L')[2]) < 1e-9, 'about the way it faces, a positive turn swings the leg out to the body\'s left', pos(C, 'lowerleg01.L'));
  bendBone(C, 'no-such-bone', [1, 0, 0], 1); ok(true, 'a bone the body does not have is left alone', 0);
  // a body turned to face +x has its axes turned with it
  const D = body(); for (let i = 0; i < D.nb; i++) { const x = D.P[i * 3], z = D.P[i * 3 + 2]; D.P[i * 3] = z; D.P[i * 3 + 2] = -x; }
  const AD = bodyAxes(D);
  ok(near(AD.fwd, [1, 0, 0]) && near(AD.side, [0, 0, -1]), 'the axes are the body\'s own, whichever way it stands', AD);
}

// ---- a pose bent by the film: its joints, what it carries, and the real body when it is drawn
{
  const B = body();
  const pose = { height: 1.8, props: { spear: { pos: [0.1, 0.3, 0.1], q: [0, 0, 0, 1] }, shield: { pos: [-0.3, 1.2, 0.2], q: [0, 0, 0, 1] } }, q: {},
    j: { root: [0, 1, 0], 'upperleg01.L': [0.1, 0.95, 0], 'lowerleg01.L': [0.1, 0.5, 0], 'foot.L': [0.1, 0.08, 0], 'toe2-3.L:tail': [0.1, 0.02, 0.2], 'upperleg01.R': [-0.1, 0.95, 0], 'lowerleg01.R': [-0.1, 0.5, 0], 'foot.R': [-0.1, 0.08, 0], spine01: [0, 1.1, 0] },
    body: { B, t: 0, xf: null } };
  bendPose(pose, [{ bone: 'upperleg01.L', about: 'side', deg: -90, props: ['spear'] }]);
  ok(near(pose.j['lowerleg01.L'], [0.1, 0.95, 0.45]) && near(pose.j['foot.L'], [0.1, 0.95, 0.87]) && near(pose.j['toe2-3.L:tail'], [0.1, 1.15, 0.93]), 'bendPose turns the joints that hang on the bone', [pose.j['lowerleg01.L'], pose.j['foot.L'], pose.j['toe2-3.L:tail']]);
  ok(near(pose.j['lowerleg01.R'], [-0.1, 0.5, 0]) && near(pose.j.spine01, [0, 1.1, 0]) && near(pose.j['upperleg01.L'], [0.1, 0.95, 0]), 'and leaves the rest', pose.j['lowerleg01.R']);
  ok(near(pose.props.spear.pos, [0.1, 1.05, 0.65]) && near(pose.props.shield.pos, [-0.3, 1.2, 0.2]), 'what is carried on it goes along when it is named, nothing else', [pose.props.spear.pos, pose.props.shield.pos]);
  ok(pose.body.after?.length === 1 && pose.body.after[0].bend?.bone === 'upperleg01.L' && pose.body.after[0].bend.about === 'side' && pose.body.after[0].bend.deg === -90, 'the real body is told to bend the same way when it is drawn', pose.body.after);
  // without a real body the limbs are still known by their names
  const plain = { height: 1.8, props: {}, q: {}, j: { 'upperleg01.L': [0.1, 0.95, 0], 'upperleg01.R': [-0.1, 0.95, 0], 'upperarm01.R': [-0.2, 1.4, 0], 'lowerarm01.R': [-0.2, 1.1, 0], 'wrist.R': [-0.2, 0.85, 0], 'wrist.L': [0.2, 0.85, 0] } };
  bendPose(plain, [{ bone: 'lowerarm01.R', about: 'side', deg: -90 }]);
  ok(near(plain.j['wrist.R'], [-0.2, 1.1, 0.25]) && near(plain.j['wrist.L'], [0.2, 0.85, 0]), 'a forearm raised: the hand comes forward, the other arm hangs', [plain.j['wrist.R'], plain.j['wrist.L']]);
}

// ---- a spear at rest stands on its butt: whatever the arm does, the shaft goes from the ground up through the hand
{
  const q45 = [Math.sin(Math.PI / 8), 0, 0, Math.cos(Math.PI / 8)]; // (leaning 45° forward: how a captured arm may hold it)
  const mk = () => ({ height: 1.8, j: { 'wrist.R': [-0.25, 1.02, 0.3], 'upperleg01.L': [0.1, 0.95, 0], 'upperleg01.R': [-0.1, 0.95, 0], root: [0, 1, 0] }, props: { spear: { pos: [-0.25, 1.16, 0.44], q: q45 } } });
  const p = mk(); plantSpear(p);
  const ax = qrot(p.props.spear.q, [0, 1, 0]), butt = p.props.spear.pos.map((v, i) => v - ax[i] * 1.2), w = p.j['wrist.R'];
  ok(Math.abs(butt[1]) < 1e-6, 'the butt stands on the ground', butt[1]);
  ok(ax[1] > Math.cos((8 * Math.PI) / 180), 'the shaft stands upright (within 8° of plumb)', (Math.acos(ax[1]) * 180) / Math.PI);
  const s = (w[0] - p.props.spear.pos[0]) * ax[0] + (w[1] - p.props.spear.pos[1]) * ax[1] + (w[2] - p.props.spear.pos[2]) * ax[2], on = p.props.spear.pos.map((v, i) => v + ax[i] * s);
  ok(Math.hypot(on[0] - w[0], on[1] - w[1], on[2] - w[2]) < 0.03, 'and still passes through the hand that holds it', Math.hypot(on[0] - w[0], on[1] - w[1], on[2] - w[2]));
  ok(Math.hypot(butt[0] - w[0], butt[2] - w[2]) < 0.15, 'its butt stands by the foot under the hand, not a stride away', Math.hypot(butt[0] - w[0], butt[2] - w[2]));
  const hi = mk(); plantSpear(hi, { ground: 0.5 }); const ah = qrot(hi.props.spear.q, [0, 1, 0]);
  ok(Math.abs(hi.props.spear.pos[1] - ah[1] * 1.2 - 0.5) < 1e-6, 'on higher ground it stands on that ground', hi.props.spear.pos[1] - ah[1] * 1.2);
  const fw = mk(); plantSpear(fw, { lean: [0, 0.1] }); const af = qrot(fw.props.spear.q, [0, 1, 0]), bf = fw.props.spear.pos.map((v, i) => v - af[i] * 1.2);
  ok(bf[2] > w[2] + 0.08 && Math.abs(bf[0] - w[0]) < 0.02, 'lean: [right, forward] is in the body\'s own directions (this one faces +z: forward is +z)', bf);
  const lean = mk(); plantSpear(lean, { lean: [0.2, 0] }); const al = qrot(lean.props.spear.q, [0, 1, 0]);
  ok(al[1] < ax[1] - 0.005 && al[1] > 0.9, 'a man can let it lean a little his own way', al[1]);
  const none = { height: 1.8, j: { 'wrist.R': [0, 1, 0] }, props: {} }; plantSpear(none);
  ok(Object.keys(none.props).length === 0, 'a body without a spear is left alone', 0);
}

// ---- a runner's arms: captured joggers carry both hands before the chest; a sprint is read from arms that swing
{
  // a body facing +z in mid-stride: which foot is forward decides which arm is
  const mk = (rf) => ({ height: 1.8, props: {}, q: {}, j: { root: [0, 1, 0], 'upperleg01.L': [0.1, 0.95, 0], 'upperleg01.R': [-0.1, 0.95, 0], 'foot.L': [0.1, 0.1, -0.4 * rf], 'foot.R': [-0.1, 0.1, 0.4 * rf],
    'upperarm01.L': [0.2, 1.45, 0], 'lowerarm01.L': [0.22, 1.17, 0.02], 'wrist.L': [0.14, 1.1, 0.24], 'upperarm01.R': [-0.2, 1.45, 0], 'lowerarm01.R': [-0.22, 1.17, 0.02], 'wrist.R': [-0.14, 1.1, 0.24] } });
  const a = mk(1), a0 = mk(1); swingArms(a);
  ok(a.j['wrist.L'][2] > a0.j['wrist.L'][2] + 0.05 && a.j['wrist.R'][2] < a0.j['wrist.R'][2] - 0.05, 'right foot forward: the left arm swings forward, the right arm back (each by 5 cm and more at the wrist)', [a.j['wrist.L'][2] - a0.j['wrist.L'][2], a.j['wrist.R'][2] - a0.j['wrist.R'][2]]);
  ok(a.j['wrist.R'][2] < 0.12, 'the hand that goes back comes down beside the hip, not held before the chest', a.j['wrist.R'][2]);
  const b = mk(-1), b0 = mk(-1); swingArms(b);
  ok(b.j['wrist.R'][2] > b0.j['wrist.R'][2] + 0.05 && b.j['wrist.L'][2] < b0.j['wrist.L'][2] - 0.05, 'left foot forward: the other way round', [b.j['wrist.L'][2] - b0.j['wrist.L'][2], b.j['wrist.R'][2] - b0.j['wrist.R'][2]]);
  const c = mk(0), c0 = mk(0); swingArms(c);
  ok(Math.abs(c.j['wrist.L'][2] - c0.j['wrist.L'][2]) < 1e-9 && Math.abs(c.j['wrist.R'][2] - c0.j['wrist.R'][2]) < 1e-9, 'feet passing each other: the arms are where the capture has them', c.j['wrist.L'][2] - c0.j['wrist.L'][2]);
  ok(['root', 'foot.L', 'foot.R', 'upperleg01.L', 'upperarm01.L'].every((n) => a.j[n].every((v, i) => Math.abs(v - a0.j[n][i]) < 1e-9)), 'legs, hips and shoulders are not moved', 0);
  const h = mk(1); swingArms(h, { amount: 0.5 });
  ok(h.j['wrist.L'][2] < a.j['wrist.L'][2] - 0.02 && h.j['wrist.L'][2] > a0.j['wrist.L'][2], 'amount: half as much', h.j['wrist.L'][2]);
  const r = mk(1); swingArms(r, { sides: 'R' });
  ok(Math.abs(r.j['wrist.L'][2] - a0.j['wrist.L'][2]) < 1e-9 && r.j['wrist.R'][2] < a0.j['wrist.R'][2] - 0.05, 'sides: one arm only (the other rests on a scabbard)', r.j['wrist.L'][2]);
}

if (fails.length) { console.log(`bend: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`bend: ${checks} rules hold`);
