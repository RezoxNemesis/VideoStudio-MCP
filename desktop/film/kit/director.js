// The director: camera set-ups that read bodies (a face, over a shoulder, two people, a low angle, a chase), the
// performances that make people alive (standing, talking hands, gestures), and scene() — a whole sequence from a short
// description: the place, the people, what they say and do; the coverage is shot the way a film editor would want it
// (an establishing wide, shot and reverse shot over the shoulders on one side of the line, close-ups, reactions, a
// closing two-shot), with focus, breathing cameras, footsteps and the music bed.
import { V, clamp, lerp } from '../engine/core.js';
import { makeSeq, at, breathe } from './seq.js';
import { person, presetOf } from './people.js';
import { mainTitle, caption } from './titles.js';
import { ENV } from '../engine/env.js';

const D2R = Math.PI / 180;
const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];

/* ---------------------------------------------------------------------------------------- cameras that read bodies */
/** Which way a body faces (horizontal unit vector): from the back of the head to the eyes. */
export function facing(ctx, id) {
  const j = ctx.poses[id].j, e = V.mid(j['eye.L'], j['eye.R']), h = j.neck01 ?? j.head;
  const d = [e[0] - h[0], 0, e[2] - h[2]], l = Math.hypot(d[0], d[2]) || 1;
  return [d[0] / l, 0, d[2] / l];
}
/** A steady point near a body part: the hips carry it, the head only nudges it (no shiver from breathing). */
export function steady(ctx, id, part = 'eyes', off = [0, 0, 0], k = 0.35) {
  const p = at(ctx, id, part), hips = at(ctx, id, 'hips'), base = [hips[0], p[1], hips[2]];
  return V.add(V.lerp(base, p, k + (1 - k) * 0.5), off);
}
/** A shot of a face. o: { az (degrees round from straight in front), dist (m), up (m above the eyes), fov, tilt, off, roll } */
export function cu(ctx, id, o = {}) {
  const eye = steady(ctx, id, 'eyes', o.off ?? [0, 0, 0], o.k ?? 0.6), f = facing(ctx, id), dir = rotY(f, (o.az ?? 22) * D2R), d = o.dist ?? 1.25;
  return { pos: [eye[0] + dir[0] * d, eye[1] + (o.up ?? 0.03), eye[2] + dir[2] * d], target: [eye[0], eye[1] - (o.tilt ?? 0.04), eye[2]], fov: o.fov ?? 26, roll: o.roll ?? 0 };
}
/** Over the shoulder of `from`, onto the face of `to`. side 1 = over the right shoulder, -1 the left. */
export function ots(ctx, from, to, o = {}) {
  const a = steady(ctx, from, 'eyes'), b = steady(ctx, to, 'eyes', o.off ?? [0, 0, 0]);
  const d = V.norm([a[0] - b[0], 0, a[2] - b[2]]), side = [d[2], 0, -d[0]], s = (o.side ?? 1) * (o.wide ?? 0.42);
  const pos = [a[0] + d[0] * (o.back ?? 0.75) + side[0] * s, a[1] + (o.up ?? 0.12), a[2] + d[2] * (o.back ?? 0.75) + side[2] * s];
  return { pos, target: [b[0] - side[0] * s * 0.25, b[1] - (o.tilt ?? 0.06), b[2] - side[2] * s * 0.25], fov: o.fov ?? 30, roll: o.roll ?? 0 };
}
/** Two people side by side in the picture, from the side: dist m from the middle of them, az turns round. */
export function two(ctx, a, b, o = {}) {
  const pa = steady(ctx, a, o.part ?? 'chest'), pb = steady(ctx, b, o.part ?? 'chest'), m = V.mid(pa, pb), d = V.norm([pb[0] - pa[0], 0, pb[2] - pa[2]]);
  const n = rotY([d[2], 0, -d[0]], (o.az ?? 0) * D2R), k = o.flip ? -1 : 1, D = o.dist ?? 3.6;
  return { pos: [m[0] + n[0] * D * k, m[1] + (o.up ?? 0.1), m[2] + n[2] * D * k], target: [m[0], m[1] + (o.lift ?? 0), m[2]], fov: o.fov ?? 34, roll: o.roll ?? 0 };
}
/** A low shot looking up at someone (power), from the front-side. */
export function low(ctx, id, o = {}) {
  const c = steady(ctx, id, 'chest'), f = facing(ctx, id), dir = rotY(f, (o.az ?? 30) * D2R), d = o.dist ?? 2.6;
  return { pos: [c[0] + dir[0] * d, (o.h ?? 0.35), c[2] + dir[2] * d], target: [c[0], c[1] + (o.lift ?? 0.25), c[2]], fov: o.fov ?? 38, roll: o.roll ?? 0 };
}
/** A high shot looking down on someone (small, alone), from the front-side. */
export function high(ctx, id, o = {}) {
  const c = steady(ctx, id, 'chest'), f = facing(ctx, id), dir = rotY(f, (o.az ?? 25) * D2R), d = o.dist ?? 3.4;
  return { pos: [c[0] + dir[0] * d, c[1] + (o.h ?? 2.6), c[2] + dir[2] * d], target: [c[0], c[1] - 0.2, c[2]], fov: o.fov ?? 34, roll: o.roll ?? 0 };
}
/** Follow a moving body from the side or behind: az 90 = from its left side, 180 = from behind. */
export function chase(ctx, id, o = {}) {
  const c = at(ctx, id, 'hips'), f = facing(ctx, id), dir = rotY(f, (o.az ?? 90) * D2R), d = o.dist ?? 4.2, lead = o.lead ?? 0.8;
  return { pos: [c[0] + dir[0] * d + f[0] * lead, (o.h ?? 1.2), c[2] + dir[2] * d + f[2] * lead], target: [c[0] + f[0] * lead * 1.5, c[1] + (o.lift ?? 0.2), c[2] + f[2] * lead * 1.5], fov: o.fov ?? 40, roll: o.roll ?? 0 };
}
/** A wide shot of a group from one side of their line: everyone in, with room. */
export function wide(ctx, ids, o = {}) {
  const ps = ids.map((id) => steady(ctx, id, 'chest')), m = ps.reduce((q, p) => V.add(q, V.mul(p, 1 / ps.length)), [0, 0, 0]);
  const span = Math.max(1.2, ...ps.map((p) => Math.hypot(p[0] - m[0], p[2] - m[2]) * 2));
  const dir = V.norm(o.dir ?? (ids.length > 1 ? (() => { const d = V.norm([ps[1][0] - ps[0][0], 0, ps[1][2] - ps[0][2]]); return [d[2] * (o.side ?? 1), 0, -d[0] * (o.side ?? 1)]; })() : facing(ctx, ids[0])));
  const D = (o.dist ?? 1) * (span * 1.25 + 4.2), h = o.h ?? 1.75;
  return { pos: [m[0] + dir[0] * D, h, m[2] + dir[2] * D], target: [m[0], m[1] + (o.lift ?? 0.1), m[2]], fov: o.fov ?? 36, roll: o.roll ?? 0 };
}
/** Blend two camera set-ups with u (0…1). */
export const mixCams = (a, b, u) => ({ pos: V.lerp(a.pos, b.pos, u), target: V.lerp(a.target, b.target, u), fov: lerp(a.fov ?? 35, b.fov ?? 35, u), roll: lerp(a.roll ?? 0, b.roll ?? 0, u) });
const ease = (u) => { u = clamp(u); return u * u * (3 - 2 * u); };
/** A slow push in over a shot: the camera travels `by` of the way to its target between t0 and t1 (negative: pulls back). */
export function push(c, t, t0, t1, by = 0.15) { const u = ease((t - t0) / Math.max(1e-3, t1 - t0)) * by; return { ...c, pos: V.lerp(c.pos, c.target, u) }; }

/* ----------------------------------------------------------------------------------------- performances, gestures */
/** Standing in place, alive: a captured "standing still" started at a different moment for everybody (i). */
export function stand(dur, i = 0, o = {}) {
  const A = o.clip ?? '111_28', lo = o.lo ?? 2.6, hi = o.hi ?? 8.6, out = []; let t = o.t0 ?? 0, inn = lo + ((i * 1.37) % (hi - lo - 2.5)), m = i % 2 === 1;
  while (t < (o.t0 ?? 0) + dur + 0.5) { out.push({ t: +t.toFixed(2), clip: A, in: +inn.toFixed(2), out: hi, mirror: m, blend: t > (o.t0 ?? 0) ? 0.9 : (o.blend ?? 0) }); t += hi - inn; inn = lo; m = !m; }
  return out;
}
/** A hand goes to a place (metres [right, up, forward] from the actor's feet) and stays. */
export const handTo = (side, t0, t1, from, to, travel = 1.0) => ({ t0, t1, fade: 0.5, keys: [{ on: `hand.${side}`, keys: [[0, from], [travel, to, 'io'], [Math.max(travel + 0.1, t1 - t0), to]] }] });
/** Hands that talk: a captured conversation's arms over whatever the body does, for one line (an overlay). */
export const talk = (T, id, i = 0, o = {}) => ({ t0: T.t(id) - 0.25, t1: T.e(id) + 0.4, fade: 0.45, clip: i % 2 ? '19_08' : '18_08', mask: o.mask ?? 'arms', in: +(1.5 + ((i * 2.7) % 12)).toFixed(2), weight: o.weight ?? 0.8, mirror: !!o.mirror });
/** Captured gestures by name: [clip, in, out] (stage.motions finds more). */
export const GESTURES = {
  wave: ['111_37', 0.2, 2.5], hello: ['141_16', 0.1, 2.3], shrug: ['111_25', 0.1, 2.2], think: ['74_12', 0, 2.4], laugh: ['79_70', 0.4, 4.2], cry: ['79_72', 0.6, 5.2],
  bow: ['111_02', 0.2, 3.3], lookaround: ['77_04', 0.3, 5.5], angry: ['18_10', 1, 5.5], phone: ['79_36', 0, 4.2], drink: ['14_37', 0, 4.2], sit: ['143_18', 0, 3.1],
  comfort: ['22_07', 0, 2.4], handshake: ['79_06', 0.8, 3.6], pickup: ['26_09', 0.3, 4.2], explain: ['18_09', 0, 1.9],
};
const gestureClip = (g) => (Array.isArray(g) ? g : GESTURES[g] ?? [g, 0, 3]);

/* -------------------------------------------------------------------------------------------------------- scene */
const yawTo = (a, b) => (Math.atan2(b[0] - a[0], b[1] - a[1]) * 180) / Math.PI;
const letters = (i) => (i < 26 ? String.fromCharCode(97 + i) : `z${String.fromCharCode(97 + (i - 26))}`);

/**
 * A whole sequence from a description. def: {
 *   id, set: () => Scene, look?, hold?, side? (1 | -1: which side of the line the camera stays on), end? (seconds after the last beat),
 *   cast: [{ id, person: 'townsman_07' | design, who? (its name in script.json), at: [x, z], face? (degrees; default: towards the next one),
 *            preset?, props?, mood? (its first emotion), alive? ('clip': a captured standing instead of the Stage's idle) }],
 *   beats: [ { say: lineId, by?, to?, emote?, gesture?, gap?, vo?, hands? (talking hands, default true) }
 *          | { wait: s } | { walk: id, to: [x, z], gait?, speed?, face? } | { do: id, gesture } | { emote: { id: emotion | [emotion, blend] } }
 *          | { look: id, at: id | 'cam' | [x, y, z] } | { mark: name } ],
 *   coverage?: 'dialogue' | 'single' | (T, D) => shots,  extra?: (T, D) => [shots placed by their times],
 *   amb?: name | [names], music?: cue name, title?: { word, sub } (over the first shot), caption?: text | { text, sub },
 *   post?, fx?, dyn?, over?, top?, tail? }
 * → a sequence (makeSeq). D (for custom coverage): { ids, tOf(beat), side, ots(ctx, from, to), cu(ctx, id), wide(ctx), two(ctx) }.
 */
export function scene(def) {
  const cast = def.cast ?? [], ids = cast.map((c) => c.id), beats = (def.beats ?? []).map((b) => ({ ...b }));
  // the clock: lines, waits, and a mark before every other beat (so its time is known once the voices are)
  const script = []; let nb = 0;
  for (const b of beats) {
    if (b.say) { script.push(['say', b.say, b.gap ?? 0.35, ...(b.vo ? [{ vo: true }] : [])]); continue; }
    b._m = `_b${nb++}`; script.push(['mark', b._m]);
    if (b.wait != null) script.push(['wait', b.wait]);
  }
  script.push(['wait', def.end ?? 1.4], ['mark', 'end']);
  const tOf = (T, b) => (b.say ? T.t(b.say) : T.m(b._m));
  const byOf = (b) => b.by ?? cast.find((c) => c.who && c.who === ENV.script?.[b.say]?.who)?.id ?? null;
  // the line between the first two people, and the side of it the camera keeps to
  const A = cast[0], B = cast[1], sideSign = def.side ?? 1;
  const n = A && B ? (() => { const d = V.norm([B.at[0] - A.at[0], 0, B.at[1] - A.at[1]]); return [d[2] * sideSign, 0, -d[0] * sideSign]; })() : [0, 0, 1];
  const otsSide = (from, to) => { const a = cast.find((c) => c.id === from)?.at, b = cast.find((c) => c.id === to)?.at; if (!a || !b) return 1; const d = V.norm([a[0] - b[0], 0, a[1] - b[1]]); return Math.sign(d[2] * n[0] - d[0] * n[2]) || 1; };
  const cuAz = (id, to) => { const a = cast.find((c) => c.id === id)?.at, b = cast.find((c) => c.id === to)?.at; if (!a || !b) return 22; const f = V.norm([b[0] - a[0], 0, b[1] - a[1]]), r = rotY(f, 25 * D2R); return r[0] * n[0] + r[2] * n[2] > 0 ? 25 : -25; };
  const D = {
    ids, tOf, side: sideSign,
    ots: (ctx, from, to, o = {}) => ots(ctx, from, to, { side: otsSide(from, to), ...o }),
    cu: (ctx, id, to, o = {}) => cu(ctx, id, { az: cuAz(id, to ?? ids.find((x) => x !== id)), ...o }),
    wide: (ctx, o = {}) => wide(ctx, ids, { side: sideSign, ...o }),
    two: (ctx, o = {}) => (ids.length > 1 ? two(ctx, ids[0], ids[1], { flip: sideSign < 0, ...o }) : cu(ctx, ids[0], { dist: 2.2, fov: 34 })),
  };
  return makeSeq({
    ...def,
    id: def.id, script, tail: def.tail ?? 0.4,
    set: def.set,
    motion: (T) => {
      const steps = [];
      for (const [i, c] of cast.entries()) {
        const face = c.face ?? (cast.length > 1 ? yawTo(c.at, cast[(i + 1) % cast.length].at) : 0);
        // what this one does: walks and gestures at their beats; between them it stands, alive (captured standing)
        const busy = [];
        for (const b of beats) {
          const t = +tOf(T, b).toFixed(3);
          if (b.walk === c.id) { const from = busy.filter((x) => x.to).pop()?.to ?? c.at, dur = Math.hypot(b.to[0] - from[0], b.to[1] - from[1]) / (b.speed ?? 1.3); busy.push({ t0: t, t1: t + dur + 0.4, to: b.to, act: { t, go: b.to, gait: b.gait ?? 'walk', speed: b.speed ?? 1.3, ...(b.face != null ? { face: b.face } : {}), blend: 0.4 } }); }
          const g = b.do === c.id ? b.gesture : b.say && byOf(b) === c.id ? b.gesture : null;
          if (g) { const [clip, inn, out] = gestureClip(g), gt = b.say ? Math.max(0, t - 0.15) : t; busy.push({ t0: gt, t1: gt + (out - inn), act: { t: +gt.toFixed(3), clip, in: inn, out, blend: 0.4 } }); }
        }
        busy.sort((x, y) => x.t0 - y.t0);
        // standing between beats: the Stage's own idle (it breathes and its weight drifts); alive: 'clip' plays a captured standing
        const free = (t0, dur, seed) => (c.alive === 'clip' ? stand(dur, seed, { t0: +t0.toFixed(3), blend: t0 > 0 ? 0.6 : 0 }) : [{ t: +t0.toFixed(3), idle: true, ...(t0 > 0 ? { blend: 0.5 } : {}) }]);
        const clean = []; let cursor = 0;
        for (const [q, x] of busy.entries()) {
          if (x.t0 > cursor + 0.35) clean.push(...free(cursor, x.t0 - cursor, i + q * 2).filter((y) => y.t < x.t0 - 0.05));
          clean.push(x.act); cursor = Math.max(cursor, x.t1);
        }
        clean.push(...free(cursor, T.dur + 1 - cursor, i + 7));
        steps.push({ op: 'stage.do', actor: c.id, actions: clean });
        // heads: towards whoever speaks (or whom this one speaks to); talking hands on its own lines
        const ov = []; let k = 0;
        const lines = beats.filter((b) => b.say && !b.vo);
        let last = 0, cur = c.look0 ?? ids.find((x) => x !== c.id) ?? null;
        for (const b of lines) {
          const by = byOf(b), t0 = T.t(b.say) - 0.2, tgt = by === c.id ? (b.to ?? ids.find((x) => x !== c.id) ?? null) : by;
          if (tgt && tgt !== cur) { if (cur) ov.push({ t0: last, t1: t0, fade: 0.35, look: cur, head: 0.8 }); last = t0; cur = tgt; }
          if (by === c.id && b.hands !== false && !b.gesture) ov.push(talk(T, b.say, k++, { weight: c.talkWeight ?? 0.75 }));
        }
        for (const b of beats.filter((x) => x.look === c.id)) { const t = tOf(T, b); if (cur) ov.push({ t0: last, t1: t, fade: 0.35, look: cur, head: 0.8 }); last = t; cur = b.at; }
        if (cur) ov.push({ t0: last, t1: T.dur + 1, fade: 0.35, look: cur, head: 0.8 });
        if (ov.length) steps.push({ op: 'stage.overlay', actor: c.id, add: ov });
      }
      return { bodies: def.bodies ?? true, actors: cast.map((c, i) => ({ id: c.id, preset: c.preset ?? (typeof c.person === 'string' ? presetOf(c.person) : 'villager'), kit: c.kit ?? 'none', ...(c.props ? { props: c.props } : {}), at: c.at, yaw: c.face ?? (cast.length > 1 ? yawTo(c.at, cast[(i + 1) % cast.length].at) : 0) })), steps: [...steps, ...(def.steps ? def.steps(T) : [])] };
    },
    actors: (T) => cast.map((c) => {
      const emote = [[0, c.mood ?? 'calm']];
      for (const b of beats) {
        if (b.say && b.emote && byOf(b) === c.id) emote.push([T.t(b.say) - 0.15, ...(Array.isArray(b.emote) ? b.emote : [b.emote, 0.75])]);
        if (b.emote && !b.say && b.emote[c.id]) emote.push([tOf(T, b), ...(Array.isArray(b.emote[c.id]) ? b.emote[c.id] : [b.emote[c.id], 0.75])]);
      }
      emote.sort((x, y) => x[0] - y[0]);
      return { id: c.id, design: typeof c.person === 'string' ? person(c.person, c.changes ?? {}) : c.person, ...(c.who ? { who: c.who } : {}), emote, ...(c.actor ?? {}) };
    }),
    shots: (T) => {
      let shots = typeof def.coverage === 'function' ? def.coverage(T, D) : coverage(T, def, cast, beats, D, byOf);
      if (def.extra) shots = [...shots, ...def.extra(T, D)].sort((x, y) => x.t0 - y.t0);
      // the title or the place's caption on the first shot
      if (def.title && shots[0]) { const tt = def.title, t0 = shots[0].t0; shots[0] = { ...shots[0], top: (g, cam, sc, t, ctx) => mainTitle(g, cam.W, cam.H, t - t0, { word: tt.word, sub: tt.sub ?? 'A FILM BY VIKAS', small: tt.small, out: tt.out ?? Math.max(2.2, shots[0].t1 - t0 - 0.6) }) }; }
      else if (def.caption && shots[0]) { const cp = typeof def.caption === 'string' ? { text: def.caption } : def.caption, t0 = shots[0].t0; shots[0] = { ...shots[0], top: (g, cam, sc, t, ctx) => caption(g, cam.W, cam.H, t - t0, cp.text, { sub: cp.sub }) }; }
      return shots;
    },
    sound: (T) => {
      const ev = [], ambs = def.amb ? (Array.isArray(def.amb) ? def.amb : [def.amb]) : [];
      for (const a of ambs) ev.push({ t0: 0, t1: T.dur, amb: a, gain: 0.32 });
      if (def.music) ev.push({ t0: def.musicAt ?? 0.3, t1: T.dur, music: def.music, gain: def.musicGain ?? 0.55, fadeIn: 1.6, fadeOut: 2.2 });
      // footsteps under every walk
      for (const b of beats.filter((x) => x.walk)) {
        const c = cast.find((q) => q.id === b.walk); if (!c) continue;
        const t0 = tOf(T, b) + 0.3, dur = Math.hypot(b.to[0] - c.at[0], b.to[1] - c.at[1]) / (b.speed ?? 1.3), step = b.gait === 'run' || b.gait === 'jog' ? 0.32 : 0.52;
        for (let t = t0, i = 0; t < t0 + dur; t += step, i++) ev.push({ t, sfx: 'step', gain: 0.18, pitch: i % 2 ? 0.95 : 1.05 });
      }
      for (const b of beats.filter((x) => x.sfx)) ev.push({ t: tOf(T, b), sfx: b.sfx, gain: b.gain ?? 0.5 });
      return [...ev, ...(def.sounds ? def.sounds(T) : [])];
    },
  });
}

/** The coverage of a dialogue: establishing wide, then the speaker over the listener's shoulder or in close-up, reactions, a closing two-shot. */
function coverage(T, def, cast, beats, D, byOf) {
  const ids = D.ids, lines = beats.filter((b) => b.say && !b.vo), end = T.m('end') + (def.tail ?? 0.4) - 0.04;
  const single = def.coverage === 'single' || ids.length < 2, segs = [];
  segs.push({ t0: 0, t1: lines.length ? clamp(T.t(lines[0].say) - 0.15, Math.min(1.6, end), end) : end, kind: 'est' });
  for (const [li, b] of lines.entries()) {
    const by = byOf(b) ?? ids[0], to = b.to ?? ids.find((x) => x !== by) ?? null, next = lines[li + 1], prev = segs[segs.length - 1];
    const s0 = prev.t1, s1 = Math.max(s0 + 0.8, next ? T.t(next.say) - 0.15 : T.e(b.say) + 0.5);
    if (prev.kind === 'line' && prev.by === by) { prev.t1 = s1; continue; } // the same voice goes on: no cut
    segs.push({ t0: s0, t1: s1, kind: 'line', by, to });
    // a reaction of the listener when a pause follows and someone else answers
    if (!single && to && next && T.t(next.say) - T.e(b.say) > 1.1 && (byOf(next) ?? ids[0]) !== to) {
      const cur = segs[segs.length - 1], r0 = T.e(b.say) + 0.15;
      if (r0 - cur.t0 > 0.9 && s1 - r0 > 0.7) { cur.t1 = r0; segs.push({ t0: r0, t1: s1, kind: 'react', by: to, to: by }); }
    }
  }
  const last = segs[segs.length - 1];
  if (end - last.t1 >= 1.3) segs.push({ t0: last.t1, t1: end, kind: 'close' }); else last.t1 = Math.max(last.t1, end);
  const breathing = (f, seed) => (t, ctx) => breathe(f(t, ctx), t, 0.004, seed);
  let k = 0;
  return segs.filter((s) => s.t1 - s.t0 > 0.3).map((s, i) => {
    const id = letters(i), t0 = s.t0, t1 = s.t1;
    if (s.kind === 'est') return { id, t0, t1, note: 'establishing', cam: breathing((t, ctx) => push(D.wide(ctx), t, t0, t1, 0.12), 1), post: def.post?.first ?? { fadeIn: 0.8 } };
    if (s.kind === 'close') return { id, t0, t1, note: 'closing', cam: breathing((t, ctx) => push(single ? D.cu(ctx, ids[0], null, { dist: 2.6, fov: 34 }) : D.two(ctx, { dist: 4.2, fov: 34 }), t, t0, t1, -0.18), 21), post: def.post?.last ?? { fadeOut: 1.0 } };
    if (s.kind === 'react') return { id, t0, t1, note: `${s.by} listens`, cam: breathing((t, ctx) => D.cu(ctx, s.by, s.to, { fov: 25, dist: 1.4 }), 9 + i) };
    const useOts = !single && s.to && ids.includes(s.to) && k++ % 3 !== 2;
    const cam = useOts ? breathing((t, ctx) => push(D.ots(ctx, s.to, s.by, { fov: 28 }), t, t0, t1, 0.06), 2 + i) : breathing((t, ctx) => push(D.cu(ctx, s.by, s.to, { fov: 24, dist: 1.35 }), t, t0, t1, 0.08), 2 + i);
    return { id, t0, t1, note: `${s.by} speaks`, cam, dof: (t, ctx) => { const c = cam(t, ctx), d = V.dist(c.pos, steady(ctx, s.by, 'eyes')); return { near: Math.max(0.2, d - 0.45), far: d + 0.9, blur: useOts ? 4 : 3, foreBlur: 5 }; } };
  }).map((sh, i, all) => (i === all.length - 1 && !sh.post?.fadeOut ? { ...sh, post: { ...(sh.post ?? {}), fadeOut: 1.0 } } : sh));
}
