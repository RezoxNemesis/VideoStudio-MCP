// The choreographer: node devtests/choreo.mjs        (exit 1 when a rule is broken)
// kit/choreo.js keeps a catalogue of body movements — each a stretch of a captured clip with what it DOES measured from
// the motion library (kit/movefacts.js: how far, which way, how much it turns, how long) — and lays any chain of them out
// for one or two performers: when each begins, where the body will be, which way to turn it so it goes where the film
// wants, where a partner must stand so hands meet, and a camera for every beat. A film names movements; nothing is
// worked out by trial solves.
import fs from 'node:fs';
import * as KIT from '../kit/index.js';
import { MOVEMENTS, KINDS, FACTS, after, choreo, beatShots, beatLabel, footfallsOf } from '../kit/choreo.js';

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${JSON.stringify(got)}`); };
const near = (a, b, e = 1e-3) => Math.abs(a - b) <= e, D2R = Math.PI / 180;

/* 1. the catalogue */
const ids = Object.keys(MOVEMENTS);
ok(ids.length >= 70, 'the catalogue holds the movements of a body: at least seventy', ids.length);
ok(ids.every((x) => /^[a-z][a-z0-9-]*$/.test(x)), 'every movement has a plain name (lower case, hyphens)', ids.filter((x) => !/^[a-z][a-z0-9-]*$/.test(x)));
for (const k of ['travel', 'balance', 'hands', 'feeling', 'together', 'action']) ok(KINDS[k] && ids.filter((i) => MOVEMENTS[i].kind === k).length >= 6, `it has movements of the kind "${k}" (six or more)`, ids.filter((i) => MOVEMENTS[i].kind === k).length);
for (const id of ids) {
  const m = MOVEMENTS[id], f = FACTS[id];
  ok(typeof m.name === 'string' && m.name.length > 2 && KINDS[m.kind] && typeof m.clip === 'string', `${id}: a name, a kind and a clip`, [m.name, m.kind, m.clip]);
  ok((m.in ?? 0.05) >= 0.05, `${id}: does not begin on the capture's T-pose frame`, m.in);
  ok(!!f && f.dur > 0.3 && Array.isArray(f.course) && f.course.length >= 2, `${id}: measured (kit/movefacts.js)`, f && [f.dur, f.course?.length]);
  if (m.with) { const w = MOVEMENTS[m.with]; ok(!!w && w.with === id && w.kind === m.kind, `${id}: its partner ${m.with} names it back`, w?.with); ok(!!f?.pair && near(f.pair.durA, f.pair.durB, 0.05), `${id}: the two were captured together (the same length), and where the partner stands is measured`, f?.pair); }
}
for (const id of ['happy-walk', 'sad-walk', 'sneak', 'march', 'walk-back', 'jump', 'hopscotch', 'bow', 'stretch-yawn', 'balance-one-leg', 'sit-chair', 'lift-box', 'throw-catch', 'dribble', 'soccer-kick', 'boxing', 'wave', 'laugh', 'handshake', 'high-five', 'side-step-right', 'duck', 'dive-roll', 'cartwheel', 'backflip', 'jump-kick', 'banana-slip', 'get-up'])
  ok(ids.includes(id), `it has "${id}"`, ids.includes(id));
ok(!('person' in MOVEMENTS) && Object.keys(KIT).includes('choreo') && Object.keys(KIT).includes('MOVEMENTS'), 'the kit gives it to every film (choreo, MOVEMENTS)', Object.keys(KIT).filter((k) => /choreo|MOVEMENTS|beatShots/.test(k)));

/* 2. the stored measurements are what the library says (when the library is here) */
const { library, factsOf, pairOf } = await import('./clipfacts.mjs');
if (library()) {
  let bad = [];
  for (const id of ids) { const m = MOVEMENTS[id], f = factsOf(m.clip, m.in ?? 0.05, m.out, { mirror: m.mirror }), s = FACTS[id]; if (!f) { bad.push(`${id}: no clip ${m.clip}`); continue; } if (!(near(f.fwd, s.fwd) && near(f.side, s.side) && near(f.turn, s.turn, 0.11) && near(f.dur, s.dur))) bad.push(id); if (m.with) { const w = MOVEMENTS[m.with], p = pairOf(m.clip, m.in ?? 0.05, w.clip, w.in ?? 0.05); if (!(near(p.fwd, s.pair.fwd) && near(p.side, s.pair.side) && near(p.yaw, s.pair.yaw, 0.11))) bad.push(id + ' (pair)'); } }
  ok(bad.length === 0, 'kit/movefacts.js is what devtests/clipfacts.mjs --write measures now', bad);
}

/* 3. where a body is after a movement: as the Stage puts it (the clip's start facing on the body's facing, its path scaled by the leg) */
{
  const F = { fwd: 2, side: 0.5, turn: 30, dur: 2, course: [[0, 0], [0.25, 1], [0.5, 2]], step: 1 };
  const e0 = after({ x: 1, z: 1, yaw: 0 }, F, { leg: 1 });
  ok(near(e0.x, 1.5) && near(e0.z, 3) && near(e0.yaw, 30), 'facing +z (yaw 0): ahead is +z, the body\'s left is +x, it ends turned', e0);
  const e90 = after({ x: 0, z: 0, yaw: 90 }, F, { leg: 0.88 });
  ok(near(e90.x, 2 * 0.88) && near(e90.z, -0.5 * 0.88) && near(e90.yaw, 120), 'facing +x (yaw 90): ahead is +x, its left is −z; metres are legs × the body\'s leg', e90);
  const mid = after({ x: 0, z: 0, yaw: 0 }, F, { leg: 1, sec: 1 });
  ok(near(mid.x, 0.25) && near(mid.z, 1), 'part-way through, it is on the measured course', mid);
}

/* 4. a chain for one performer */
{
  const C = choreo({ leg: 0.88, area: { x: [-6, 6], z: [-3, 3] } });
  C.actor('a', { at: [-4, 0], yaw: 90 });
  const b1 = C.do('a', 'bow'), b2 = C.do('a', 'jump-forward'), b3 = C.do('a', 'happy-walk', { toward: 90 });
  ok(near(b1.t0, 0) && b2.t0 >= b1.t1 - 1e-6 && b3.t0 >= b2.t1 - 1e-6, 'movements of one body follow one another: none begins before the last has ended', [b1, b2, b3].map((b) => [b.t0, b.t1]));
  ok(near(b1.t1 - b1.t0, FACTS.bow.dur) && near(b2.t1 - b2.t0, FACTS['jump-forward'].dur), 'each lasts as long as its stretch of the clip', [b1.t1 - b1.t0, FACTS.bow.dur]);
  const dx = b3.to.x - b3.from.x, dz = b3.to.z - b3.from.z;
  ok(Math.abs(Math.atan2(dx, dz) / D2R - 90) < 1.5 && Math.hypot(dx, dz) > 3, 'toward: 90 — the movement TRAVELS towards +x, whichever way its performer walked in the capture', [dx, dz]);
  const st = C.steps(), acts = st.find((s) => s.actor === 'a').actions;
  ok(st.every((s) => s.op === 'stage.do') && acts.every((x, i) => i === 0 || x.t >= acts[i - 1].t), 'it gives the Stage one list of actions for each body, in order of time', acts.map((x) => x.t));
  ok(acts.filter((x) => x.clip).every((x) => x.in >= 0.05) && acts.some((x) => x.clip === MOVEMENTS.bow.clip), 'the actions are the catalogue\'s stretches of clips', acts.filter((x) => x.clip).map((x) => [x.clip, x.in, x.out]));
  ok(C.beats.length === 3 && C.beats.every((b, i) => b.n === i + 1 && b.name && b.kind && b.by.join() === 'a'), 'and a list of beats for the cameras and the lettering: number, name, kind, who, when, from where to where', C.beats.map((b) => [b.n, b.name]));
  ok(near(C.dur, b3.t1), 'its length is the end of the last movement', C.dur);
  // a pause: someone who has ended standing breathes; he is not held on the capture's last frame
  const C2 = choreo({ leg: 0.88 }); C2.actor('a', { at: [0, 0], yaw: 0 }); const w = C2.do('a', 'wave'); C2.wait(w.t1 + 2); C2.do('a', 'bow');
  const a2 = C2.steps()[0].actions; ok(a2.some((x) => x.idle && near(x.t, w.t1)), 'between two movements a standing body is given an idle (nobody is a statue)', a2.map((x) => [x.t, x.clip ?? (x.idle ? 'idle' : 'go')]));
}

/* 5. the floor: a body is kept on it */
{
  const C = choreo({ leg: 0.88, area: { x: [-6, 6], z: [-3, 3] } }); C.actor('a', { at: [4.5, 0], yaw: 90 });
  const b = C.do('a', 'happy-walk'); // 4.7 m ahead would leave the floor at x = 9
  ok(b.to.x <= 6 && b.to.x >= -6 && Math.abs(b.to.z) <= 3, 'a movement that would leave the floor is turned to travel across it instead', b.to);
  let threw = ''; try { const C3 = choreo({ leg: 0.88, area: { x: [-1, 1], z: [-1, 1] } }); C3.actor('a', { at: [0, 0], yaw: 0 }); C3.do('a', 'happy-walk'); } catch (e) { threw = e.message; }
  ok(/happy-walk/.test(threw) && /floor|area/.test(threw), 'and one that no turning can keep on it is refused by name', threw);
  const C4 = choreo({ leg: 0.88 }); C4.actor('a', { at: [0, 0], yaw: 0 });
  const g = C4.go('a', [3, 0], { gait: 'walk', speed: 1.3 }), d = C4.do('a', 'bow', { at: [-2, 1], toward: 0 });
  ok(g.t1 > 3 / 1.3 && near(d.from.x, -2) && near(d.from.z, 1) && d.t0 >= g.t1 + Math.hypot(5, 1) / 1.6, 'at: [x, z] — he walks to that mark first, and the movement waits until he is there', [g, d.t0, d.from]);
}

/* 6. two together */
{
  const C = choreo({ leg: 0.88 }); C.actor('a', { at: [-3, 0], yaw: 90 }); C.actor('b', { at: [3, 0], yaw: -90 });
  const p = C.pair('handshake', 'a', 'b', { at: [-1, 0], yaw: 90 }), F = FACTS.handshake.pair;
  const A = p.from, B = p.fromB;
  ok(near(A.x, -1) && near(A.z, 0) && near(B.x, -1 + F.fwd * 0.88, 0.01) && near(B.z, -F.side * 0.88, 0.01), 'a pair: the partner stands where the capture had him, seen from the first (so the hands meet)', [A, B, F]);
  ok(near(((B.yaw - (90 + F.yaw) + 540) % 360) - 180, 0, 0.2), 'and faces as he faced', [B.yaw, F.yaw]);
  const st = C.steps(), ta = st.find((s) => s.actor === 'a').actions.find((x) => x.clip === MOVEMENTS.handshake.clip), tb = st.find((s) => s.actor === 'b').actions.find((x) => x.clip === MOVEMENTS[MOVEMENTS.handshake.with].clip);
  ok(ta && tb && near(ta.t, tb.t) && near(ta.t, p.t0), 'both begin at the same moment', [ta?.t, tb?.t]);
  ok(C.beats.at(-1).by.join() === 'a,b', 'the beat belongs to both', C.beats.at(-1).by);
  const q = C.both('jump', ['a', 'b']);
  ok(q.t0 >= p.t1 - 1e-6 && C.steps().every((s) => s.actions.some((x) => x.clip === MOVEMENTS.jump.clip && near(x.t, q.t0))), 'both: the same movement by both at once', q);
}

/* 6b. two whose ways to their marks cross do not walk through each other */
{
  const C = choreo({ leg: 0.975 }); C.actor('a', { at: [2, 2], yaw: -90 }); C.actor('b', { at: [-2, 2], yaw: 90 });   // each must get to the other's side
  C.pair('handshake', 'a', 'b', { at: [-1, 2], yaw: 90 });
  let nearest = 9; for (let t = 0; t < C.beats[0].t0; t += 0.1) { const a = C.where('a', t), b = C.where('b', t); nearest = Math.min(nearest, Math.hypot(a.x - b.x, a.z - b.z)); }
  ok(nearest > 0.8, 'walking to their marks they pass each other at arm\'s length or more (the second goes round)', nearest);
}

/* 7. cameras and lettering for every beat */
{
  const C = choreo({ leg: 0.88 }); C.actor('a', { at: [-3, 0], yaw: 90 }); C.actor('b', { at: [3, 0], yaw: -90 });
  C.do('a', 'bow'); C.then(); C.do('b', 'sneak', { toward: -90 }); C.then(); C.pair('high-five', 'a', 'b', { at: [-1.2, 0.5], yaw: 90 }); C.then(); C.do('a', 'stretch-yawn'); C.then();
  const sh = beatShots(C, { id: 'm' });
  ok(sh.length >= C.beats.length && near(sh[0].t0, 0) && near(sh.at(-1).t1, C.dur) && sh.every((s, i) => i === 0 || near(s.t0, sh[i - 1].t1)), 'shots cover the whole performance without a gap', sh.map((s) => [s.t0, s.t1]));
  ok(sh.every((s) => s.t1 - s.t0 >= 1.2 && s.t1 - s.t0 <= 6.6), 'no shot is shorter than 1.2 s or longer than 6.6 s (a long beat is cut in two)', sh.map((s) => +(s.t1 - s.t0).toFixed(2)));
  ok(new Set(sh.map((s) => s.id)).size === sh.length && sh.every((s) => /^m_\d+/.test(s.id)), 'each has an id of its own', sh.map((s) => s.id));
  ok(sh.every((s, i) => i === 0 || s.view !== sh[i - 1].view), 'no two shots in a row are taken from the same side (a cut must change the view)', sh.map((s) => s.view));
  const ctx = { poses: Object.fromEntries(['a', 'b'].map((id, i) => [id, { j: { root: [i ? 2 : -2, 0.95, 0], head: [i ? 2 : -2, 1.62, 0], 'eye.L': [i ? 2 : -2, 1.62, 0.1], 'eye.R': [i ? 2 : -2, 1.62, 0.1], neck01: [i ? 2 : -2, 1.5, 0] } }])) };
  const c = sh[0].cam(sh[0].t0 + 0.5, ctx, 0.5);
  ok(Array.isArray(c.pos) && Array.isArray(c.target) && c.fov > 15 && c.fov < 60 && near(c.target[1], sh[0].cam(sh[0].t0 + 1, ctx, 1).target[1]), 'a camera looks at the performer, its height held still (a jump reads against a level frame)', c);
  { // everyone a shot frames is in the picture: its width at the performers' distance holds them with room to spare
    const C7 = choreo({ leg: 0.975, area: { x: [-8, 8], z: [0, 5] } }); C7.actor('a', { at: [-6, 1.5], yaw: 90 }); C7.actor('b', { at: [6, 3], yaw: -90 });
    C7.group(null, () => { C7.do('a', 'happy-walk', { toward: 90 }); C7.do('b', 'wave'); });
    const s7 = beatShots(C7, { id: 'w' })[0], pose = (id, t) => { const w = C7.where(id, t); return { j: { root: [w.x, 0.95, w.z] } }; };
    let worst = 0; for (let t = s7.t0; t < s7.t1; t += 0.25) { const ctx7 = { poses: { a: pose('a', t), b: pose('b', t) } }, c7 = s7.cam(t, ctx7), half = Math.hypot(c7.pos[0] - c7.target[0], c7.pos[2] - c7.target[2]) * Math.tan(c7.fov * D2R / 2) * (16 / 9); for (const id of ['a', 'b']) { const w = C7.where(id, t); worst = Math.max(worst, (Math.abs(w.x - c7.target[0]) + 0.6) / half); } }
    ok(worst < 1, 'two far apart: the lens stands back until both are in the picture, with room for their arms', worst);
  }
  const L = beatLabel(C, C.beats[0].t0 + 0.5);
  ok(L && L.n === 1 && L.name === MOVEMENTS.bow.name && L.kind === KINDS[MOVEMENTS.bow.kind], 'beatLabel: the number, the name and the kind of what is being done at a moment', L);
}

/* 7b. one beat for two doing different things; a run that does not stop */
{
  const C = choreo({ leg: 0.88 }); C.actor('a', { at: [-3, 0], yaw: 90 }); C.actor('b', { at: [3, 1], yaw: -90 });
  const g = C.group(null, () => { C.do('a', 'bow'); C.do('b', 'wave'); C.do('b', 'jump'); });
  ok(C.beats.length === 1 && g.by.join() === 'a,b' && near(g.t0, 0) && near(g.t1, Math.max(FACTS.bow.dur, FACTS.wave.dur + FACTS.jump.dur)) && /Bow/.test(g.name) && /Wave/.test(g.name) && g.parts.length === 3, 'group: what two do at the same time is ONE beat (its name names both, its length is the longer)', [C.beats.length, g.name, g.t0, g.t1]);
  C.then(); const r = C.go('a', [3, 0], { gait: 'run', through: true }), s2 = C.go('b', [-3, 1], { gait: 'run' });
  ok(r.t1 - r.t0 < s2.t1 - s2.t0 - 0.3 && C.where('a', r.t1).x < 3 && C.where('a', r.t1).x > 2.5, 'go … through: true — he is still running when the next movement takes him over, a little short of the mark', [r, C.where('a', r.t1)]);
}

/* 8. the sound of feet, from the performance itself (never from a guessed rhythm) */
{
  const fps = 30, c = new Uint8Array(150).fill(3); // standing
  for (let i = 30; i < 90; i++) c[i] = Math.floor((i - 30) / 10) % 2 ? 1 : 2;      // a walk: one foot, then the other, changing every third of a second
  for (let i = 100; i < 115; i++) c[i] = 0;                                         // in the air for half a second, then both feet
  const ev = footfallsOf(c, fps, { gain: 0.3 }), steps = ev.filter((e) => e.sfx === 'step'), lands = ev.filter((e) => e.sfx === 'land');
  ok(steps.length >= 5 && steps.every((e) => e.t > 0.9 && e.t < 3.2 && e.gain > 0 && e.gain <= 0.3), 'a step sounds each time a foot comes down in a walk', steps.map((e) => e.t));
  ok(lands.length === 1 && Math.abs(lands[0].t - 115 / fps) < 0.04 && lands[0].gain > steps[0].gain, 'coming down from the air is a landing, louder than a step', lands);
  ok(!ev.some((e) => e.t < 0.9), 'standing still makes no sound', ev.filter((e) => e.t < 0.9));
  ok(steps.some((e) => e.pitch !== steps[0].pitch), 'left and right do not sound alike', steps.map((e) => e.pitch));
}

console.log(`choreo: ${checks} rules checked, ${fails.length} broken (${ids.length} movements)`);
for (const f of fails) console.log('  ✗ ' + f);
process.exit(fails.length ? 1 : 0);
