// A sequence: one continuous stretch of story in one place — its lines on a clock, one solved performance for
// everybody in it, and the shots (camera set-ups) that cut it up. Shots of a sequence share the performance, so
// positions, cloth and continuity carry across every cut.
import { lineDur, emotionAt, mouthAt, lookAt, saccade, VO } from './acting.js';
import { V } from '../engine/core.js';
import { ENV } from '../engine/env.js';

/**
 * def: { id, set(opts) → Scene, script: [['say', id, gap?, { vo }] | ['wait', s] | ['mark', name] | ['at', t]],
 *        motion(T) → bake spec (actors + steps) | null, bake (name of an existing bake to use instead),
 *        actors(T) → [{ id, design, who, emote, look, simple, … }], shots(T) → [{ id, t0, t1, cam(t, ctx, tLocal), … }], tail }
 */
export function makeSeq(def) {
  const cues = [], marks = {}; let cur = 0;
  for (const st of def.script ?? []) {
    if (st[0] === 'wait') cur += st[1];
    else if (st[0] === 'at') cur = st[1];
    else if (st[0] === 'mark') marks[st[1]] = cur;
    else if (st[0] === 'say') { const id = st[1], gap = st[2] ?? 0.35, o = st[3] ?? {}; const t0 = cur + gap, d = lineDur(id); cues.push({ id, who: ENV.script?.[id]?.who ?? VO.en[id]?.who ?? o.who, t0, t1: t0 + d, vo: !!o.vo, en: VO.en[id]?.seconds ?? d, hi: VO.hi[id]?.seconds ?? d }); cur = t0 + d; }
  }
  const T = {
    cues, marks, dur: cur + (def.tail ?? 0.8),
    t: (id) => { const c = cues.find((q) => q.id === id); if (!c) throw new Error(`${def.id}: no line ${id}`); return c.t0; },
    e: (id) => { const c = cues.find((q) => q.id === id); if (!c) throw new Error(`${def.id}: no line ${id}`); return c.t1; },
    m: (name) => { if (!(name in marks)) throw new Error(`${def.id}: no mark ${name}`); return marks[name]; },
  };
  const seq = { ...def, T, cues, bakeName: def.bake ?? def.id };
  seq.actorList = def.actors ? def.actors(T) : [];
  seq.shotList = def.shots(T).map((s) => ({ ...s }));
  // a shot shows moving lips if somebody in it speaks on screen during it
  for (const sh of seq.shotList) {
    const on = new Set(seq.actorList.filter((a) => !(sh.hide ?? []).includes(a.id) && (!sh.only || sh.only.includes(a.id))).map((a) => a.who).filter(Boolean));
    sh.lip = sh.lip ?? cues.some((c) => !c.vo && on.has(c.who) && c.t1 > sh.t0 && c.t0 < sh.t1);
  }
  return seq;
}

/**
 * One shot of a sequence as the shot runner wants it. off: start this many seconds into the shot (to look at a late
 * frame without drawing everything before it).
 */
export function runnerShot(seq, sh, off = 0) {
  const T0 = sh.t0 + off;
  const actors = seq.actorList.filter((a) => !(sh.hide ?? []).includes(a.id) && (!sh.only || sh.only.includes(a.id))).map((a) => {
    const emo = typeof a.emote === 'function' ? a.emote(seq.T) : a.emote, looks = typeof a.look === 'function' ? a.look(seq.T) : a.look;
    return {
      ...a, ...(sh.actor?.[a.id] ?? {}), bake: a.bake ?? seq.bakeName, t0: (a.t0 ?? 0) + T0,
      face: (t, ctx) => {
        const ts = T0 + t, f = emotionAt(emo, ts), m = a.who ? mouthAt(seq.cues, a.who, ts, ctx.o.lang ?? 'en') : null;
        if (m) { f.mouthOpen = Math.max(f.mouthOpen ?? 0, m.open * (a.mouth ?? 0.6)); f.mouthWide = (f.mouthWide ?? 0) + m.wide * 0.4; }
        // the eyes: on a target if one is set for this moment, else small movements of their own
        let tg = null;
        if (looks) for (const [kt, target] of looks) { if (ts >= kt) tg = target; else break; }
        if (tg) {
          const hd = ctx.chars[a.id].frame(ctx.poses[a.id]).head;
          const p = tg === 'cam' ? (ctx.cam?.pos ?? null) : typeof tg === 'string' ? (ctx.poses[tg] ? V.add(ctx.poses[tg].j.head, [0, 0.1, 0]) : null) : tg;
          if (p) f.look = lookAt(hd, p);
        } else f.look = saccade(ts + (seq.id.length * 3.7), a.id.charCodeAt(0) + a.id.length * 17);
        if (a.faceFx) a.faceFx(f, ts, ctx);
        return f;
      },
    };
  });
  const post = { ...(seq.post ?? {}), ...(sh.post ?? {}) };
  if (off > 0 && post.fadeIn) post.fadeIn = Math.max(0, post.fadeIn - off) || undefined;
  return {
    ...sh, id: `${seq.id}_${sh.id}`, seq: seq.id, seqT: seq.T, dur: sh.t1 - T0, t0: T0, lip: sh.lip,
    set: (ctx) => seq.set({ ...(seq.setOpts ?? {}), ...(sh.setOpts ?? {}) }, ctx),
    actors,
    cam: (t, ctx) => sh.cam(T0 + t, ctx, t + off),
    dof: sh.dof ? (t, ctx) => sh.dof(T0 + t, ctx, t + off) : null,
    fx: (ctx) => [...(seq.fx ? seq.fx(ctx, sh, seq.T) : []), ...(sh.fx ? sh.fx(ctx) : [])],
    dyn: (seq.dyn || sh.dyn) ? (t, ctx) => [...(seq.dyn ? seq.dyn(T0 + t, ctx, sh) : []), ...(sh.dyn ? sh.dyn(T0 + t, ctx) : [])] : null,
    under: sh.under ? (g, cam, sc, t, ctx) => sh.under(g, cam, sc, T0 + t, ctx) : null,
    over: (sh.over || seq.over) ? (g, cam, sc, t, ctx) => { if (seq.over) seq.over(g, cam, sc, T0 + t, ctx, sh); if (sh.over) sh.over(g, cam, sc, T0 + t, ctx, t + off); } : null,
    top: sh.top ? (g, cam, sc, t, ctx) => sh.top(g, cam, sc, T0 + t, ctx, t + off) : null,
    post, warm: sh.warm ?? seq.warm ?? 2, settle: sh.settle ?? 0,
    // what the fingerprint of a shot is made from besides poses, camera and faces
    src: { setOpts: { ...(seq.setOpts ?? {}), ...(sh.setOpts ?? {}) }, set: String(seq.set), file: seq.file ?? null,
      hooks: [sh.over, sh.top, sh.under, sh.dyn, sh.fx, sh.subs, seq.over, seq.dyn, seq.fx].map((f) => (f ? String(f) : '')).join('\u0001') },
  };
}
/** The shots of a sequence as the shot runner wants them. */
export function runnerShots(seq) { return seq.shotList.map((sh) => runnerShot(seq, sh)); }

/* ---- camera helpers --------------------------------------------------------------------------------------------- */
import { clamp, lerp, smooth, smoother } from '../engine/core.js';
/** A point of an actor's body right now: 'head' | 'chest' | 'hips' | 'feet' | any joint name. */
export const at = (ctx, id, part = 'head', off = [0, 0, 0]) => {
  const j = ctx.poses[id].j, p = part === 'chest' ? j.spine02 : part === 'hips' ? j.root : part === 'feet' ? [j.root[0], 0, j.root[2]] : part === 'eyes' ? V.mid(j['eye.L'], j['eye.R']) : j[part];
  return V.add(p, off);
};
/** Move from one camera set-up to another between t0 and t1 with an ease. */
export const move = (a, b, t, t0, t1, ease = smoother) => { const u = ease(clamp((t - t0) / (t1 - t0))); return { pos: V.lerp(a.pos, b.pos, u), target: V.lerp(a.target, b.target, u), fov: lerp(a.fov ?? 35, b.fov ?? 35, u), roll: lerp(a.roll ?? 0, b.roll ?? 0, u) }; };
/** The breathing of a hand-held or long-lens camera: tiny drift of the aim. */
export const breathe = (c, t, amt = 0.012, seed = 0) => ({ ...c, target: [c.target[0] + Math.sin(t * 0.7 + seed) * amt, c.target[1] + Math.sin(t * 0.9 + seed * 2) * amt * 0.7, c.target[2] + Math.cos(t * 0.6 + seed) * amt] });
export { V, clamp, lerp, smooth, smoother };
