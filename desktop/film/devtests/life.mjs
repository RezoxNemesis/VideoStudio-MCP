// Nobody in a film is a statue: node devtests/life.mjs        (exit 1 when a rule is broken)
// A body that stands, sits or kneels breathes: the chest fills and the shoulders ride up on it, the head stays level
// and is never quite still, the trunk sways a little over the hips. The feet and the legs do not move for it. Every
// body breathes at its own pace and in its own moment; a body out of breath heaves. It reaches the real body (the
// drawn mesh), not only the joints.
import { breathAt, lifeBends, enliven, stillness } from '../engine/life.js';
import { bodyAxes } from '../engine/body.js';
import { stillRuns } from '../sensei/motion.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };

/** a little body, standing, facing +z (its left is +x): hips, legs, the spine up to the head, collar bones, arms */
const NAMES = ['root', 'upperleg01.L', 'lowerleg01.L', 'foot.L', 'upperleg01.R', 'lowerleg01.R', 'foot.R', 'spine05', 'spine03', 'spine02', 'spine01', 'neck01', 'head',
  'clavicle.L', 'upperarm01.L', 'lowerarm01.L', 'wrist.L', 'clavicle.R', 'upperarm01.R', 'lowerarm01.R', 'wrist.R'];
const PARENT = [-1, 0, 1, 2, 0, 4, 5, 0, 7, 8, 9, 10, 11, 10, 13, 14, 15, 10, 17, 18, 19];
const AT = [[0, 1, 0], [0.1, 0.95, 0], [0.1, 0.5, 0], [0.1, 0.08, 0], [-0.1, 0.95, 0], [-0.1, 0.5, 0], [-0.1, 0.08, 0], [0, 1.05, 0], [0, 1.2, 0], [0, 1.3, 0], [0, 1.45, 0], [0, 1.6, 0], [0, 1.72, 0],
  [0.03, 1.52, 0.02], [0.2, 1.5, 0], [0.22, 1.2, 0], [0.23, 0.92, 0.03], [-0.03, 1.52, 0.02], [-0.2, 1.5, 0], [-0.22, 1.2, 0], [-0.23, 0.92, 0.03]];
function body() {
  const P = new Float64Array(NAMES.length * 3), Q = new Float64Array(NAMES.length * 4);
  AT.forEach((p, i) => { P.set(p, i * 3); Q[i * 4 + 3] = 1; });
  return { nb: NAMES.length, bi: Object.fromEntries(NAMES.map((n, i) => [n, i])), parent: Int16Array.from(PARENT), P, Q };
}
const poseOf = (withBody = true) => ({ height: 1.8, props: {}, q: Object.fromEntries(NAMES.map((n) => [n, [0, 0, 0, 1]])), j: Object.fromEntries(NAMES.map((n, i) => [n, [...AT[i]]])), ...(withBody ? { body: { B: body(), t: 0, xf: null } } : {}) });
const LEGS = /^(root|upperleg|lowerleg|foot|toe|pelvis)/;

// ---- breath: a pace of its own, in and out, never a jump
{
  const a = breathAt(0, 3), rates = [1, 2, 3, 4, 5, 6, 7, 8].map((s) => breathAt(0, s).rate);
  ok(rates.every((r) => r >= 0.18 && r <= 0.33), 'at rest a body breathes 11 to 20 times a minute', rates);
  ok(new Set(rates.map((r) => r.toFixed(3))).size >= 6, 'every body at its own pace', rates);
  let lo = 1, hi = -1, jump = 0, prev = a.b, up = 0, down = 0;
  for (let t = 1 / 60; t < 12; t += 1 / 60) { const b = breathAt(t, 3).b; lo = Math.min(lo, b); hi = Math.max(hi, b); jump = Math.max(jump, Math.abs(b - prev)); if (b > prev) up++; else down++; prev = b; }
  ok(lo < -0.9 && hi > 0.9 && lo >= -1.0001 && hi <= 1.0001, 'the chest goes from empty (−1) to full (+1)', [lo, hi]);
  ok(jump < 0.06, 'smoothly: no jump from one frame to the next', jump);
  ok(up < down * 0.85, 'breathing in is quicker than breathing out', [up, down]);
  // two bodies are not in step
  let same = 0, n = 0; for (let t = 0; t < 40; t += 0.1) { same += breathAt(t, 3).b * breathAt(t, 11).b; n++; }
  ok(Math.abs(same / n) < 0.35, 'two bodies do not breathe in step', same / n);
  ok(breathAt(5, 3, { amp: 2.5 }).rate > breathAt(5, 3).rate * 1.5, 'a body out of breath breathes faster', breathAt(5, 3, { amp: 2.5 }).rate);
}

// ---- the bends that make it: trunk, collar bones, neck — never the legs
{
  const names = new Set(); let maxDeg = 0, maxStep = 0, prev = null;
  for (let t = 0; t < 20; t += 1 / 24) {
    const bs = lifeBends(t, 5, 1), now = {};
    for (const b of bs) { names.add(b.bone); maxDeg = Math.max(maxDeg, Math.abs(b.deg)); now[`${b.bone}|${b.about}`] = (now[`${b.bone}|${b.about}`] ?? 0) + b.deg; }
    if (prev) for (const k in now) maxStep = Math.max(maxStep, Math.abs(now[k] - (prev[k] ?? 0)));
    prev = now;
  }
  ok([...names].every((n) => !LEGS.test(n)), 'life never bends the legs or moves the hips (the feet stay where they stand)', [...names]);
  ok(['spine03', 'spine02', 'clavicle.L', 'clavicle.R', 'neck01'].every((n) => names.has(n)), 'it is in the trunk, the collar bones and the neck', [...names]);
  ok(maxDeg > 0.6 && maxDeg < 4.5, 'by degrees one can see and nobody notices: more than half a degree, less than four and a half', maxDeg);
  ok(maxStep < 0.3, 'never more than a third of a degree between two frames', maxStep);
  ok(lifeBends(3, 5, 0).length === 0, 'a body whose life is switched off is left as it was performed', lifeBends(3, 5, 0).length);
  ok(JSON.stringify(lifeBends(7.25, 5, 1)) === JSON.stringify(lifeBends(7.25, 5, 1)), 'the same moment is always the same (frames can be drawn in any order)', 0);
}

// ---- laid over a pose: what is seen
{
  const range = (seed, amp, name, T = 16) => { const lo = [1e9, 1e9, 1e9], hi = [-1e9, -1e9, -1e9]; for (let t = 0; t < T; t += 1 / 12) { const p = poseOf(); enliven(p, t, seed, amp); const q = p.j[name]; for (let i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], q[i]); hi[i] = Math.max(hi[i], q[i]); } } return [hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]]; };
  const sh = range(2, 1, 'upperarm01.L'), hd = range(2, 1, 'head'), wr = range(2, 1, 'wrist.L'), ft = range(2, 1, 'foot.L'), kn = range(2, 1, 'lowerleg01.R'), rt = range(2, 1, 'root');
  ok(sh[1] > 0.006 && sh[1] < 0.02, 'the shoulders ride up and down with the breath: 6 to 20 mm on a grown body', sh[1]);
  ok(Math.hypot(...hd) > 0.006 && Math.hypot(...hd) < 0.05, 'the head is never quite still, and never wanders', Math.hypot(...hd));
  ok(Math.hypot(...wr) < 0.04, 'hanging hands go with the shoulders, they do not swing about', Math.hypot(...wr));
  ok(Math.hypot(...ft) === 0 && Math.hypot(...kn) === 0 && Math.hypot(...rt) === 0, 'feet, knees and hips stay exactly where they were performed', [ft, kn, rt]);
  const hv = range(2, 2.5, 'upperarm01.L');
  ok(hv[1] > sh[1] * 1.7, 'out of breath, the shoulders heave', [hv[1], sh[1]]);
  // the head stays level while the chest fills
  let tilt = 0; for (let t = 0; t < 16; t += 1 / 12) { const p = poseOf(); enliven(p, t, 2, 1); const q = p.q.head; tilt = Math.max(tilt, 2 * Math.asin(Math.min(1, Math.hypot(q[0], q[2]))) * 180 / Math.PI); }
  ok(tilt < 2.5, 'the head stays level (it does not nod with every breath)', tilt);
  // the real body is bent with the joints
  const p = poseOf(); enliven(p, 3.3, 2, 1);
  ok(Array.isArray(p.body.after) && p.body.after.length > 0 && p.body.after.every((op) => op.bend && !LEGS.test(op.bend.bone)), 'the real body (the drawn mesh) is bent the same way as the joints', p.body.after?.length ?? 0);
  // a figure with no real body still lives (the simple figures)
  const s0 = poseOf(false), s1 = poseOf(false); enliven(s0, 1.0, 2, 1); enliven(s1, 3.2, 2, 1);
  ok(Math.abs(s0.j['upperarm01.L'][1] - s1.j['upperarm01.L'][1]) > 0.001 && s0.j['foot.L'][1] === 0.08 && s1.j['foot.L'][1] === 0.08, 'a simple figure breathes too, on its feet', [s0.j['upperarm01.L'][1], s1.j['upperarm01.L'][1]]);
  void bodyAxes;
}

// ---- speaking: the head goes with the words (a speaker whose head does not move is a mask with a moving mouth)
{
  const pitchOf = (bs) => bs.filter((b) => b.bone === 'neck01' && b.about === 'side').reduce((n, b) => n + b.deg, 0);
  let most = 0, worst = 0;
  for (let t = 0; t < 10; t += 1 / 24) { const quiet = pitchOf(lifeBends(t, 4, 1)), loud = pitchOf(lifeBends(t, 4, 1, { speak: 1 })), d = loud - quiet; most = Math.max(most, Math.abs(d)); worst = Math.max(worst, Math.abs(loud)); }
  ok(most > 1.5, 'on a loud syllable the head dips or lifts by a degree and a half and more', most);
  ok(worst < 6.5, 'never by more than six: a head that bobs is as wrong as one that is frozen', worst);
  ok(JSON.stringify(lifeBends(2, 4, 1, { speak: 0 })) === JSON.stringify(lifeBends(2, 4, 1)), 'a silent body is as before', 0);
  const names = new Set(lifeBends(2, 4, 1, { speak: 1 }).map((b) => b.bone));
  ok([...names].every((n) => !LEGS.test(n)), 'speech never moves the legs either', [...names]);
}

// ---- the judge's measure: how still a body is over a stretch of time
{
  const frames = (alive) => { const out = []; for (let t = 0; t < 4; t += 1 / 12) { const p = poseOf(); if (alive) enliven(p, t, 2, 1); out.push(p.j); } return out; };
  const dead = stillness(frames(false)), live = stillness(frames(true));
  ok(dead.move < 1e-9, 'a body that holds one pose has not moved at all', dead.move);
  ok(live.move > 0.006, 'a living one has: its chest, shoulders or head travelled more than 6 mm against its hips', live.move);
  ok(typeof live.part === 'string' && live.part.length > 0, 'and the measure names the part that moved most', live.part);
}

// ---- the judge: a body that holds still for seconds is found, a living one is not
{
  const dt = 1 / 60, frames = (alive, secs, from = 0) => { const out = []; for (let i = 0; i < secs / dt; i++) { const p = poseOf(); if (alive) enliven(p, from + i * dt, 2, 1); out.push(p.j); } return out; };
  const dead = stillRuns(frames(false, 5), dt, { win: 2, limit: 0.003 });
  ok(dead.length === 1 && dead[0][0] === 0 && dead[0][1] >= 5 / dt - 2, 'a body that holds one pose for five seconds is one long stretch of stillness', dead);
  ok(stillRuns(frames(true, 5), dt, { win: 2, limit: 0.003 }).length === 0, 'a body that breathes is never found still', stillRuns(frames(true, 5), dt, { win: 2, limit: 0.003 }));
  const mixed = [...frames(true, 3), ...frames(false, 3), ...frames(true, 3, 6)], runs = stillRuns(mixed, dt, { win: 2, limit: 0.003 });
  ok(runs.length === 1 && Math.abs(runs[0][0] * dt - 3) < 0.6 && Math.abs(runs[0][1] * dt - 6) < 0.6, 'a stretch of stillness between two living ones is found where it is', runs.map((r) => [r[0] * dt, r[1] * dt]));
  ok(stillRuns(frames(false, 1.2), dt, { win: 2, limit: 0.003 }).length === 0, 'a hold shorter than the window is not a statue (a beat may be held)', 0);
  const gaps = frames(false, 5); for (let i = 60; i < 120; i++) gaps[i] = null;
  ok(stillRuns(gaps, dt, { win: 2, limit: 0.003 }).every(([a, b]) => a >= 120 || b < 60), 'moments in which the body is not there are not counted', stillRuns(gaps, dt, { win: 2, limit: 0.003 }));
}

if (fails.length) { console.error(`life: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`life: all ${checks} rules hold`);
