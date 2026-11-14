// A film's own bends of a performed body: node devtests/bend.mjs        (exit 1 when a rule is broken)
// A bone can be turned about its own joint after the performance is posed (a rider's thighs lie along the horse, a
// soldier's forearm carries his spear upright): everything that hangs on the bone goes with it, nothing else moves.
import { bendBone, bodyAxes } from '../engine/body.js';
import { bendPose } from '../kit/combat.js';

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

if (fails.length) { console.log(`bend: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`bend: ${checks} rules hold`);
