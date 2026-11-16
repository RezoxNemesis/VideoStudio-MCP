// Balls in play (kit/ballplay.js): node devtests/ballplay.mjs        (exit 1 when a rule is broken)
// One ball, in one place at every moment: it reaches a catching hand exactly when the hand closes, falls the way things
// fall, comes to rest ON the ground, touches the ground under a dribbling hand, and never jumps between two frames.
import { ballPlay, arcAt, loose } from '../kit/ballplay.js';

const fails = []; let checks = 0;
const ok = (c, what, got) => { checks++; if (!c) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
const d3 = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);

// a performer: walks along +z at 1 m/s; hands in front of the chest; the right hand bounces (a dribble) between 3 and 5 s
const joints = (t) => {
  const z = t, yR = t >= 3 && t <= 5 ? 0.95 + 0.18 * (1 - Math.cos((t - 3) * 2 * Math.PI / 0.6)) / 2 : 1.1, j = {};
  const hand = (side, x, y) => { j[`wrist.${side}`] = [x, y, z + 0.3]; j[`finger2-1.${side}`] = [x + (side === 'R' ? 0.02 : -0.02), y - 0.01, z + 0.38]; j[`finger5-1.${side}`] = [x + (side === 'R' ? -0.03 : 0.03), y - 0.01, z + 0.37]; };
  hand('L', -0.15, 1.1); hand('R', 0.15, yR); j['foot.R'] = [0.1, 0.08, z]; j['toe1-1.R'] = [0.1, 0.03, z + 0.15]; j.root = [0, 1, z];
  return j;
};
const ctx = { poses: {}, jointsAt: (id, t) => joints(t) };
const at = (b, t) => { ctx.poses.a = { j: joints(t), height: 1.8 }; return b.at(t, ctx); };

// arcs and falling
{ const p0 = [0, 1, 0], p1 = [4, 1.5, 0], T = 1.2; ok(d3(arcAt(p0, p1, T, 0), p0) < 1e-9 && d3(arcAt(p0, p1, T, T), p1) < 1e-9, 'an arc leaves its start and arrives at its end on time', null);
  ok(arcAt(p0, p1, T, T / 2)[1] > 1.25 + 0.5 * 9.81 * 0.36 - 1e-6, 'an arc rises under gravity as a thrown thing does (½·g·s·(T−s) above the line)', arcAt(p0, p1, T, T / 2)[1]);
  const L = loose([0, 1, 0], [2, 3, 0], 6, 0.1); ok(Math.abs(L.p[1] - 0.1) < 1e-6 && Math.hypot(L.v[0], L.v[2]) < 1e-3, 'a thing let go falls, bounces, and comes to rest ON the ground', L);
  let below = false; for (let s = 0; s < 3; s += 0.01) if (loose([0, 1, 0], [1, 2, 0], s, 0.1).p[1] < 0.1 - 1e-6) below = true; ok(!below, 'it never sinks into the ground', null); }

const b = ballPlay({ kind: 'basket', life: [{ t: 0.5, fly: true, from: [0, 2, 8] }, { t: 1.5, hold: 'both', by: 'a' }, { t: 2.5, hold: 'R', by: 'a' }, { t: 3, dribble: 'R', by: 'a', until: 5 }, { t: 5.2, hold: 'both', by: 'a' }, { t: 6, away: { k: 1.2, lift: 2 } }] });
ok(at(b, 0.2) === null, 'before its first key the ball is nowhere (it is thrown in, it does not hang in the air)', at(b, 0.2));
{ const caught = at(b, 1.5), justBefore = at(b, 1.5 - 1 / 120); ok(d3(caught, justBefore) < 0.12, 'the flying ball arrives in the hands when they close (no jump at the catch)', d3(caught, justBefore)); }
{ const p = at(b, 2), j = joints(2), mid = [(j['wrist.L'][0] + j['wrist.R'][0]) / 2, (j['wrist.L'][1] + j['wrist.R'][1]) / 2, (j['wrist.L'][2] + j['wrist.R'][2]) / 2]; ok(d3(p, mid) < 0.16, 'held in both hands, it is between them', d3(p, mid)); }
{ let lo = 9; for (let t = 3; t <= 5; t += 1 / 120) lo = Math.min(lo, at(b, t)[1]); ok(Math.abs(lo - 0.12) < 0.02, 'dribbled, it touches the ground under the hand', lo); }
{ let worst = 0, prev = null, wt = 0; for (let t = 0.5; t <= 9; t += 1 / 60) { const p = at(b, t); if (prev && p) { const d = d3(p, prev); if (d > worst && Math.abs(t - 3) > 0.02 && Math.abs(t - 5.2) > 0.02) { worst = d; wt = t; } } prev = p; }
  ok(worst < 0.25, 'from frame to frame it moves as a ball can (no jumps, 60 fps)', [worst, wt]); }
{ const p = at(b, 12); ok(Math.abs(p[1] - 0.12) < 1e-3, 'thrown away, it ends at rest on the ground', p); }

const ft = ballPlay({ kind: 'soccer', life: [{ t: 0, lies: { by: 'a', foot: 'R', at: 2 } }, { t: 2, foot: 'R', by: 'a' }, { t: 2, away: { k: 1.1, lift: 2.5 } }] });
{ const p0 = at(ft, 0), p1 = at(ft, 1.99); ok(d3(p0, p1) < 1e-6 && Math.abs(p0[1] - 0.11) < 1e-6, 'a ball that lies waits on the ground where the foot will meet it', [p0, p1]);
  const p2 = at(ft, 2.3); ok(p2[2] > p1[2] + 0.3 && p2[1] > 0.3, 'struck, it goes off the way the foot went, into the air', p2); }
const bx = ballPlay({ kind: 'box', size: 0.3, life: [{ t: 0, lies: { by: 'a', hands: 'both', at: 1 } }, { t: 1, hold: 'both', by: 'a' }, { t: 3, away: { k: 0.3 } }] });
{ const p = at(bx, 0.5); ok(Math.abs(p[1] - 0.12) < 1e-6, 'a box lies on its bottom on the ground until taken', p); const q = at(bx, 8); ok(Math.abs(q[1] - 0.12) < 1e-3, 'let go, it comes down onto the ground', q); }

console.log(`${checks - fails.length}/${checks} ball rules hold`);
if (fails.length) { for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
