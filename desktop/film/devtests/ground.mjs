// Nothing of a body goes under the ground: node devtests/ground.mjs        (exit 1 when a rule is broken)
// A captured roll, dive or fall is fitted to our body by its feet; the rest of it can end up IN the floor (the stunt
// lab's dive roll: hips at ground height — a hand's breadth under it — shoulder 8 cm under, 16 Nov 2026).
// grounded(pose) lifts the whole body just enough that hips, back, shoulders, head, elbows, knees and wrists lie ON it.
import { grounded } from '../kit/combat.js';
const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
const mk = (ys) => ({ height: 1.8, props: {}, body: {}, j: Object.fromEntries(Object.entries({ root: 0.95, spine03: 1.25, neck01: 1.5, head: 1.62, 'upperarm01.L': 1.45, 'upperarm01.R': 1.45, 'lowerarm01.L': 1.2, 'lowerarm01.R': 1.2, 'wrist.L': 0.95, 'wrist.R': 0.95, 'upperleg01.L': 0.92, 'upperleg01.R': 0.92, 'lowerleg01.L': 0.5, 'lowerleg01.R': 0.5, 'foot.L': 0.08, 'foot.R': 0.08, ...ys }).map(([k, y]) => [k, [0.3, y, -0.2]])) });
// a man standing: left as he is
{ const p = mk({}), before = p.j.root[1]; const q = grounded(p); ok(Math.abs(q.j.root[1] - before) < 1e-9 && !(q.body.after ?? []).length, 'a standing body is left alone', q.j.root[1] - before); }
// in a roll: hips at the height of the ground, a shoulder and the wrists under it
{ const p = mk({ root: 0.01, 'upperleg01.L': 0.03, 'upperarm01.R': -0.08, 'wrist.L': -0.04, 'wrist.R': -0.03, head: 0.2, 'foot.L': 0.5, 'foot.R': 0.6 }), y0 = Object.fromEntries(Object.entries(p.j).map(([k, v]) => [k, v[1]]));
  const q = grounded(p), lift = q.j.root[1] - y0.root;
  ok(q.j['upperarm01.R'][1] >= 0.055, 'the shoulder lies on the ground, not under it', q.j['upperarm01.R'][1]);
  ok(q.j.root[1] >= 0.095, 'the hips are their own thickness above it', q.j.root[1]);
  ok(q.j['wrist.L'][1] >= 0.02, 'the wrists too', q.j['wrist.L'][1]);
  ok(Object.keys(q.j).every((k) => Math.abs(q.j[k][1] - y0[k] - lift) < 1e-9), 'the whole body is lifted as one (nothing is bent)', lift);
  ok(lift < 0.16, 'and by no more than is needed', lift);
  ok((q.body.after ?? []).some((a) => a.shift && Math.abs(a.shift[1] - lift) < 1e-9), 'the drawn body goes with its joints', q.body.after ?? null);
}
// the feet are not its business (the solver stands them), and a small body needs less room
{ const p = mk({ 'foot.L': -0.02 }); ok(Math.abs(grounded(p).j.root[1] - 0.95) < 1e-9, 'a foot is left to the solver', grounded(mk({ 'foot.L': -0.02 })).j.root[1]);
  const kid = mk({ root: 0.01 }); kid.height = 0.9; const man = mk({ root: 0.01 }); ok(grounded(kid).j.root[1] < grounded(man).j.root[1], 'a child\'s hips are nearer the ground than a man\'s', [grounded(mk({ root: 0.01 })).j.root[1]]); }
// it comes on smoothly: a body sinking towards the ground is not jolted when it first touches
{ let prev = null, jump = 0; for (let y = 0.2; y >= -0.05; y -= 0.005) { const q = grounded(mk({ root: y })), out = q.j.root[1]; if (prev !== null) jump = Math.max(jump, Math.abs((out - prev) - (-0.005))); prev = out; } ok(jump <= 0.0051, 'no jolt where the lifting begins (it eases in)', jump); }
if (fails.length) { console.error(`ground: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`ground: all ${checks} rules hold`);
