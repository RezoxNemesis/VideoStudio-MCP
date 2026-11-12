// What a baked sword fight tells the film: when every blow lands, so the cut, the sparks, the shake and the sound can hit with it.
import { ENV } from '../engine/env.js';
import fs from 'node:fs';
import { V, clamp } from '../engine/core.js';
import { sparks, burstSparks, puff, dust } from '../engine/fx.js';
import * as post from '../engine/post.js';

/** All the beats of all the fights in a bake, in order: [{ f (fight index), i, t, apex, by, do, hit, defend }], and the fights' own spans. */
export function fightBeats(bakeName) {
  let info = null;
  try { info = JSON.parse(fs.readFileSync(`${ENV.bakeDir}${bakeName}.json`, 'utf8')).info?.['stage.fight']; } catch { /* not baked yet */ }
  const fights = (info ?? []).map((F, f) => ({ f, t0: F.beats[0]?.t ?? 0, end: F.end, beats: F.beats.map((b) => ({ ...b, f })) }));
  return { fights, beats: fights.flatMap((F) => F.beats) };
}
/** Camera shake from the blows near time ts (seq time): returns { amp, freq }. */
export const blowShake = (beats, ts, k = 1) => { let a = 0; for (const b of beats) { const d = ts - b.apex; if (d > -0.02 && d < 0.45) a = Math.max(a, (b.hit ? 20 : b.defend === 'parry' || b.defend === 'block' ? 9 : 3) * (1 - d / 0.45)); } return { amp: a * k, freq: 17 }; };
/** The flash and the speed lines of a blow that lands (call from a shot's `top`). */
export function blowFlash(g, cam, beats, ts) {
  for (const b of beats) { const d = ts - b.apex; if (b.hit && d >= 0 && d < 0.34) { if (d < 0.085) { g.fillStyle = `rgba(255,255,255,${0.92 * (1 - d / 0.085)})`; g.fillRect(0, 0, cam.W, cam.H); } post.speedLines(g, cam.W, cam.H, { alpha: 0.42 * (1 - d / 0.34), n: 80, seed: Math.floor(b.apex * 100) + Math.floor(d * 30) }); } }
}
/** Sparks where blades meet, dust where a body lands: a particle system that fires itself on the beats. pair(f) → [idA, idB]. */
export function blowSparks(ctx, beats, pair) {
  const ps = sparks({ ground: 0 }), ds = dust({ color: '#d9cbb4' }), step = ps.step.bind(ps); let last = -1e9;
  ps.step = (dt, t, sc) => {
    const ts = ctx.shot.t0 + t;
    for (const b of beats) if (b.apex > last && b.apex <= ts) {
      const [ia, ib] = pair(b.f), A = ctx.poses[ia], B = ctx.poses[ib]; if (!A || !B) continue;
      const m = V.mid(A.j['wrist.R'], B.j['wrist.R']), at = [m[0], Math.max(1.0, m[1] + 0.25), m[2]];
      if (b.defend === 'parry' || b.defend === 'block') burstSparks(ps, at, 26, 1.3);
      if (b.hit) { const vic = b.by === 'a' ? B : A; burstSparks(ps, vic.j.spine02, 16, 1.0); puff(ds, [vic.j.root[0], 0, vic.j.root[2]], 1.2); }
    }
    last = ts; step(dt, t, sc); ds.step(dt, t, sc);
  };
  const items = ps.items.bind(ps); ps.items = (c) => [...items(c), ...ds.items(c)];
  return ps;
}
/** Sound for every beat: steel on steel, the whoosh of a miss, the blow that lands and the body that falls. */
export function blowSounds(beats, o = {}) {
  const ev = [];
  for (const b of beats) {
    const pan = o.pan ?? 0;
    ev.push({ t: b.apex - 0.22, sfx: 'swoosh', gain: 0.4, pitch: 1.0 + 0.25 * ((b.i * 7) % 3) / 3, pan });
    if (b.defend === 'parry' || b.defend === 'block') ev.push({ t: b.apex - 0.02, sfx: 'clash', gain: 0.75, pitch: 0.92 + 0.2 * ((b.i * 5 + b.f * 3) % 4) / 4, pan });
    else if (b.hit) { ev.push({ t: b.apex - 0.02, sfx: 'slash', gain: 0.85, pan }, { t: b.apex, sfx: 'impact', gain: 0.7, pan }); if (b.ko !== false) ev.push({ t: b.apex + 0.75, sfx: 'land', gain: 0.55, pan }); }
    else ev.push({ t: b.apex - 0.05, sfx: 'whoosh', gain: 0.45, pitch: 1.3, pan });
  }
  return ev;
}
