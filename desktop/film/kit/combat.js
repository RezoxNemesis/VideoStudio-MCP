// Action: bent time (speed ramps, hit-stop), blade arcs, clashes, blood, impact frames, camera kicks, and the sounds
// of all of it — driven by a list of events on the sequence's clock:
//   { t, kind: 'clash' | 'cut' | 'stab' | 'kill' | 'kick' | 'thud' | 'whoosh', by, on, at?: [x,y,z] | (ctx) → p, dir?: [x,y,z], power?: 1 }
import { V, clamp, lerp, smooth, hash, rng, qrot, hex, rgba, noise1 } from '../engine/core.js';
import { Particles, glow } from '../engine/fx.js';
import { loadBake, poseAt } from '../engine/bake.js';

/* ---- bent time ------------------------------------------------------------------------------------------------ */
/**
 * A shot's clock with slow moments in it. list: [{ at (story seconds from the shot's start: the moment itself),
 * slow (0 = a freeze … 1), len (film seconds the slow part lasts), rec (film seconds over which the lost time is
 * won back, default len × 1.2), lead (story seconds before `at` where the slow part begins, default 0.03) }].
 * Time is only bent, never lost: the shot ends when it would have. → warp(tFilm) → tStory, with warp.inv(tStory).
 */
export function ramps(dur, list) {
  const N = Math.ceil(dur * 240) + 2, dt = 1 / 240, tab = new Float32Array(N + 1);
  const segs = []; let shift = 0; // shift: film time already lost to earlier slow parts that is not yet won back (0 once recovered)
  for (const r of [...list].sort((a, b) => a.at - b.at)) {
    const a = Math.max(0, r.at - (r.lead ?? 0.03)), len = r.len ?? 0.4, rec = r.rec ?? len * 1.2, slow = r.slow ?? 0.15;
    segs.push({ a, b: a + len, v: slow }, { a: a + len, b: a + len + rec, v: 1 + (len * (1 - slow)) / rec });
  }
  void shift;
  let s = 0;
  for (let i = 0; i <= N; i++) {
    tab[i] = s; const t = i * dt; let v = 1;
    for (const g of segs) if (t >= g.a && t < g.b) { const e = Math.min(1, Math.min(t - g.a, g.b - t) / 0.03); v = lerp(1, g.v, g.v < 1 ? Math.min(1, e * 3) : e); }
    s += v * dt;
  }
  const k = dur / (tab[Math.min(N, Math.round(dur * 240))] || dur); // close the small error of the eased edges
  const warp = (t) => { const f = clamp(t, 0, dur) * 240, i = Math.min(N - 1, Math.floor(f)); return (tab[i] + (tab[i + 1] - tab[i]) * (f - i)) * k + Math.max(0, t - dur); };
  warp.inv = (ts) => { let lo = 0, hi = dur; for (let i = 0; i < 26; i++) { const m = (lo + hi) / 2; if (warp(m) < ts) lo = m; else hi = m; } return (lo + hi) / 2; };
  return warp;
}

/* ---- bodies: small tools for stunts ----------------------------------------------------------------------------- */
const qmul = (p, q) => [p[3] * q[0] + p[0] * q[3] + p[1] * q[2] - p[2] * q[1], p[3] * q[1] - p[0] * q[2] + p[1] * q[3] + p[2] * q[0], p[3] * q[2] + p[0] * q[1] - p[1] * q[0] + p[2] * q[3], p[3] * q[3] - p[0] * q[0] - p[1] * q[1] - p[2] * q[2]];
const axisQ = (ax, ang) => { const s = Math.sin(ang / 2); return [ax[0] * s, ax[1] * s, ax[2] * s, Math.cos(ang / 2)]; };
/** Move a whole body (and what it carries). */
export function shiftPose(pose, d) {
  for (const n in pose.j) { const p = pose.j[n]; pose.j[n] = [p[0] + d[0], p[1] + d[1], p[2] + d[2]]; }
  for (const n in pose.props) { const p = pose.props[n].pos; pose.props[n].pos = [p[0] + d[0], p[1] + d[1], p[2] + d[2]]; }
  if (pose.body) (pose.body.after ??= []).push({ shift: [d[0], d[1], d[2]] }); // the real body goes with its joints
  return pose;
}
/** Turn a whole body about a point (a wall-run, a dive, a body thrown through the air). axis: unit vector, ang: radians. */
export function turnPose(pose, pivot, axis, ang) {
  const q = axisQ(V.norm(axis), ang), r = (p) => V.add(pivot, qrot(q, V.sub(p, pivot)));
  for (const n in pose.j) pose.j[n] = r(pose.j[n]);
  for (const n in pose.q) pose.q[n] = qmul(q, pose.q[n]);
  for (const n in pose.props) { const pr = pose.props[n]; pr.pos = r(pr.pos); pr.q = qmul(q, pr.q); }
  if (pose.body) (pose.body.after ??= []).push({ turn: { pivot: [pivot[0], pivot[1], pivot[2]], axis: V.norm(axis), ang } });
  return pose;
}
/** A leap bigger than the body made it: lift = extra height at the top; between t0 and t1 (seconds), at time t. */
export const arc = (t, t0, t1, lift) => { const u = (t - t0) / (t1 - t0); return u <= 0 || u >= 1 ? 0 : 4 * lift * u * (1 - u); };

/** The blade of the sword an actor holds: { base, tip, ax } or null. */
export function bladeOf(pose, len = 0.78) {
  const sw = pose?.props?.sword; if (!sw || !sw.visible) return null;
  const k = pose.height / 1.8; if (Math.min(V.dist(sw.pos, pose.j['wrist.R']), V.dist(sw.pos, pose.j['wrist.L'])) > 0.3 * k) return null;
  const sc = pose.props.scabbard; if (sc && sc.visible && V.dist(sw.pos, sc.pos) < 0.09 * k) return null;
  const ax = qrot(sw.q, [0, 1, 0]);
  return { base: V.add(sw.pos, V.mul(ax, 0.14)), tip: V.add(sw.pos, V.mul(ax, 0.135 + len)), ax };
}
/** The body of a shot's actor at a story time other than the frame's (for arcs between frames). */
export function poseOf(a, ts, ctx) {
  const xf = typeof a.xf === 'function' ? a.xf(ts - ctx.shot.t0, ctx) : a.xf;
  const p = poseAt(loadBake(a.bake), a.as ?? a.id, (a.t0 ?? 0) + (ts - ctx.shot.t0) * (a.rate ?? 1), xf ?? null);
  if (a.fit) a.fit(p, ts - ctx.shot.t0, ctx);
  return p;
}

/* ---- the fight's effects ------------------------------------------------------------------------------------------ */
const BLOOD = '#8f0d14', BLOOD_DARK = '#5a070c', BLOOD_HI = '#c8161e';
const where = (e, ctx) => (typeof e.at === 'function' ? e.at(ctx) : e.at) ?? (ctx.poses[e.on] ? V.lerp(ctx.poses[e.on].j.spine02, ctx.poses[e.on].j.spine03 ?? ctx.poses[e.on].j.spine02, 0.5) : null) ?? [0, 1, 0];
const KICK = { clash: 13, cut: 15, stab: 14, kill: 24, kick: 16, thud: 9, whoosh: 0 };

/**
 * Everything a fight shows besides the bodies. events as above; o: { blades: [actor ids whose sword leaves an arc]
 * (default: every actor with a sword in the hand), arcColor, ground: y of the floor (or (x, z) → y), blood: true }.
 * → { fx(ctx) (give it to the sequence's fx), over(g, cam, sc, ts, ctx), top(g, cam, sc, ts, ctx), shake(ts, k) }
 */
export function combat(events, o = {}) {
  const ev = [...events].sort((a, b) => a.t - b.t);
  const groundAt = (x, z) => (typeof o.ground === 'function' ? o.ground(x, z) : o.ground ?? 0);
  const shake = (ts, k = 1) => {
    let amp = 0; for (const e of ev) { const d = ts - e.t; if (d >= 0 && d < 0.5) amp += (KICK[e.kind] ?? 8) * (e.power ?? 1) * Math.exp(-d / 0.11); }
    return amp > 0.3 ? { amp: Math.min(46, amp * k), freq: 31 } : null;
  };
  const fx = (ctx) => {
    const R = rng(ev.length * 7 + 3), r = () => R();
    const sparks = new Particles({ kind: 'streak', streak: 0.045, op: 'screen', alpha: 1, gravity: 1.4, drag: 1.6, fade: [0.0, 0.5], ground: null, colors: ['#fff6d8', '#ffd98a', '#ffb347', '#ffffff'], size: [0.006, 0.014], life: [0.16, 0.5], max: 900 });
    const drops = new Particles({ kind: 'streak', streak: 0.03, alpha: 0.96, gravity: 1.0, drag: 0.5, fade: [0.0, 0.9], colors: [BLOOD, BLOOD_DARK, BLOOD_HI, BLOOD], size: [0.011, 0.03], life: [0.5, 1.1], max: 2600, ground: 0 });
    const mist = new Particles({ kind: 'dab', alpha: 0.5, gravity: 0.05, drag: 2.6, grow: 1.9, fade: [0.05, 0.6], color: '#7a1016', size: [0.12, 0.3], life: [0.3, 0.7], max: 300 });
    const stains = [], glints = [], arcs = new Map(); let done = 0, last = null;
    const spawn = (e, tsNow) => {
      const p = where(e, ctx), pw = e.power ?? 1;
      if (e.kind === 'clash') {
        for (let i = 0; i < 26 * pw; i++) { const d = V.norm([r() - 0.5, r() - 0.2, r() - 0.5]); sparks.add(p, V.mul(d, (2 + r() * 7) * (0.6 + pw * 0.5))); }
        glints.push({ t: e.t, p, pw, kind: 'clash' });
      } else if (e.kind === 'cut' || e.kind === 'stab' || e.kind === 'kill') {
        if (o.blood === false) return;
        // the spray leaves along the cut; some of it falls short, a few drops fly far
        let dir = e.dir ? V.norm(e.dir) : null;
        if (!dir) { const a = ctx.poses[e.by], b = ctx.poses[e.on]; dir = a && b ? V.norm(V.add(V.sub(b.j.root, a.j.root), [0, 0.35, 0])) : [0, 1, 0]; dir = V.norm(V.add(dir, [0, 0.5, 0])); }
        const side = V.norm(V.cross(dir, [0, 1, 0])), n = (e.kind === 'kill' ? 150 : e.kind === 'stab' ? 60 : 90) * pw;
        for (let i = 0; i < n; i++) {
          const u = r(), sp = (1.2 + 5.2 * u * u) * (0.7 + 0.5 * pw), fan = (r() - 0.5) * (e.kind === 'stab' ? 0.5 : 1.3);
          const v = V.add(V.add(V.mul(dir, sp), V.mul(side, fan * sp * 0.55)), [0, (r() - 0.25) * 2.2, 0]);
          drops.add(V.add(p, [(r() - 0.5) * 0.08, (r() - 0.5) * 0.12, (r() - 0.5) * 0.08]), v);
        }
        for (let i = 0; i < 5 * pw; i++) mist.add(V.add(p, [(r() - 0.5) * 0.2, (r() - 0.5) * 0.2, (r() - 0.5) * 0.2]), V.mul(dir, 0.6 + r()));
        glints.push({ t: e.t, p, pw, kind: 'cut', dir });
        // what falls at once under the wound
        for (let i = 0; i < 4; i++) stains.push({ p: [p[0] + (r() - 0.5) * 0.7 + dir[0] * 0.5, 0, p[2] + (r() - 0.5) * 0.7 + dir[2] * 0.5], r: 0.05 + r() * 0.09, t: e.t + 0.25 + r() * 0.3, grow: 0 });
        if (e.kind === 'kill' && e.pool !== false) stains.push({ follow: e.on, r: 0.34 + 0.2 * r(), t: e.t + 1.1, grow: 3.5, p: [p[0], 0, p[2]] });
      } else if (e.kind === 'kick' || e.kind === 'thud') glints.push({ t: e.t, p, pw, kind: 'thud' });
      void tsNow;
    };
    // drops that reach the floor stay there
    drops.onGround = (q) => { if (stains.length < 900 && r() < 0.5) stains.push({ p: [q[0], 0, q[2]], r: 0.025 + r() * 0.06, t: -1, grow: 0, st: r() }); };
    const obj = {
      step(dt, t, scene) {
        const ts = ctx.shot.t0 + (ctx.tw ?? t), sdt = last === null ? dt : clamp(ts - last, 0, 0.05); last = ts;
        // a shot that opens in the middle of the fight: what was spilt before the cut is already on the floor
        if (done === 0 && !obj.primed) { obj.primed = true; for (const e of ev) if (e.t < ts - 0.6 && (e.kind === 'cut' || e.kind === 'kill' || e.kind === 'stab') && o.blood !== false) { const p = where(e, ctx); const q = rng(Math.round(e.t * 100)); for (let i = 0; i < 9; i++) stains.push({ p: [p[0] + (q() - 0.5) * 1.6, 0, p[2] + (q() - 0.5) * 1.6], r: 0.03 + q() * 0.1, t: -1, grow: 0 }); if (e.kind === 'kill') stains.push({ follow: e.on, r: 0.34 + 0.2 * q(), t: e.t + 1.1, grow: 3.5, p: [p[0], 0, p[2]] }); } while (done < ev.length && ev[done].t < ts - 0.6) done++; }
        while (done < ev.length && ev[done].t <= ts) spawn(ev[done++], ts);
        sparks.step(sdt, ts, scene); drops.step(sdt, ts, scene); mist.step(sdt, ts, scene);
        // blade arcs: where each sword was over the last moments, sampled between the frames
        const ids = o.blades ?? ctx.shot.actors.map((a) => a.id);
        for (const id of ids) {
          const a = ctx.shot.actors.find((q) => q.id === id); if (!a || !ctx.poses[id]) continue;
          if (a.show && !a.show(ts - ctx.shot.t0, ctx)) { arcs.delete(id); continue; }
          const now = bladeOf(ctx.poses[id], o.len?.[id]); if (!now) { arcs.delete(id); continue; }
          const N = 9, span = 0.085, pts = [];
          for (let i = 0; i <= N; i++) { const b = i === N ? now : bladeOf(poseOf(a, ts - span * (1 - i / N), ctx), o.len?.[id]); if (b) pts.push(b); }
          const sp = pts.length > 1 ? V.dist(pts[0].tip, pts[pts.length - 1].tip) / span : 0;
          arcs.set(id, { pts, sp, color: o.arcColor?.[id] ?? '#eaf4ff' });
        }
      },
      items() {
        const out = [...sparks.items(), ...drops.items(), ...mist.items()];
        // stains: flat on the floor, under the feet
        // (sorted like a figure standing there, a little further back, so the floor is under them and bodies are over them)
        if (stains.length) { const cells = new Map(); for (const st of stains) { const k = `${Math.round(st.p[0] / 1.5)},${Math.round(st.p[2] / 1.5)}`; (cells.get(k) ?? cells.set(k, []).get(k)).push(st); } for (const list of cells.values()) out.push({ p: [list[0].p[0], groundAt(list[0].p[0], list[0].p[2]), list[0].p[2]], bias: 0.9, draw: (g, cam) => drawStains(g, cam, list, last ?? 0, ctx, groundAt) }); }
        for (const [, a] of arcs) if (a.sp > 5.5) out.push({ p: a.pts[a.pts.length - 1].tip, bias: -0.3, draw: (g, cam) => drawArc(g, cam, a) });
        return out;
      },
      glints, stains,
    };
    combat.live = obj;
    return obj;
  };
  /** Over the picture: the flash of a clash where the blades meet, the slash line of a cut. */
  const over = (g, cam, sc, ts) => {
    const L = combat.live; if (!L) return;
    for (const q of L.glints) {
      const d = ts - q.t, life = q.kind === 'clash' ? 0.14 : q.kind === 'cut' ? 0.12 : 0.16; if (d < 0 || d > life) continue;
      const p = cam.project(q.p); if (p[2] < 0.2) continue;
      const ppm = cam.ppm(p[2]), u = d / life;
      g.save(); g.globalCompositeOperation = 'screen';
      if (q.kind === 'clash') {
        const R = (0.5 + 0.5 * q.pw) * ppm * (0.45 + 0.9 * Math.sqrt(u)) * 0.36, al = 1 - u * u;
        glow(g, p[0], p[1], R * 1.6, '#ffd590', 0.75 * al);
        // a four-pointed star, turning a little
        g.translate(p[0], p[1]); g.rotate(0.5 + q.t * 7); g.fillStyle = `rgba(255,252,240,${al})`;
        for (let i = 0; i < 4; i++) { g.rotate(Math.PI / 2); g.beginPath(); g.moveTo(0, -R * 0.09); g.quadraticCurveTo(R * 0.25, 0, R * (i % 2 ? 0.75 : 1.3) * (1 - 0.5 * u), 0); g.quadraticCurveTo(R * 0.25, 0, 0, R * 0.09); g.closePath(); g.fill(); }
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.beginPath(); g.arc(p[0], p[1], R * (0.4 + 1.4 * u), 0, 7); g.lineWidth = Math.max(1, R * 0.07 * (1 - u)); g.strokeStyle = `rgba(255,236,190,${0.8 * al})`; g.stroke();
      } else if (q.kind === 'cut') {
        // the line of the cut: a hard bright stroke through the body for a few frames
        const a = cam.project(V.add(q.p, V.mul(q.dir, -0.5))), b = cam.project(V.add(q.p, V.mul(q.dir, 0.6))), al = Math.pow(1 - u, 1.5);
        g.globalCompositeOperation = 'source-over'; g.lineCap = 'round';
        g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.lineWidth = Math.max(2, 0.05 * ppm * (1 - u)); g.strokeStyle = `rgba(190,14,24,${0.9 * al})`; g.stroke();
        g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.lineWidth = Math.max(1, 0.016 * ppm * (1 - u)); g.strokeStyle = `rgba(255,255,255,${al})`; g.stroke();
      } else {
        const R = 0.45 * q.pw * ppm * (0.3 + u), al = 1 - u;
        g.beginPath(); g.arc(p[0], p[1], R, 0, 7); g.lineWidth = Math.max(1, R * 0.12 * al); g.strokeStyle = `rgba(255,244,222,${0.7 * al})`; g.stroke();
      }
      g.restore();
    }
  };
  /** Last, over everything: a breath of light on the heaviest blows (never a white frame), impact lines for `big` events. */
  const top = (g, cam, sc, ts) => {
    for (const e of ev) {
      const d = ts - e.t; if (d < 0 || d > 0.14) continue;
      const pw = (e.power ?? 1) * (e.kind === 'kill' ? 1 : e.kind === 'clash' ? 0.7 : 0.5), al = 0.16 * pw * (1 - d / 0.14);
      if (al > 0.02) { g.save(); g.globalCompositeOperation = 'screen'; g.fillStyle = e.kind === 'clash' ? `rgba(255,236,200,${Math.min(0.3, al)})` : `rgba(255,214,214,${Math.min(0.26, al)})`; g.fillRect(0, 0, cam.W, cam.H); g.restore(); }
      if (e.big && d < 0.1) impactLines(g, cam, cam.project(where(e, { poses: {} })), 1 - d / 0.1, Math.round(e.t * 60));
    }
  };
  /** The sounds of the events, for the sequence's sound list. */
  const sounds = (extra = {}) => ev.flatMap((e, i) => {
    const pw = e.power ?? 1, pan = clamp((hash(i * 3.1) - 0.5) * 0.7, -0.5, 0.5), pitch = 0.88 + 0.3 * hash(i * 7.7), g = extra.gain ?? 1;
    if (e.quiet) return [];
    if (e.kind === 'clash') return [{ t: e.t - 0.16, sfx: 'swoosh', gain: 0.4 * g, pitch: pitch * 1.2, pan }, { t: e.t - 0.02, sfx: 'clash', gain: Math.min(1, 0.72 * pw) * g, pitch, pan }];
    if (e.kind === 'cut' || e.kind === 'kill') return [{ t: e.t - 0.17, sfx: 'swoosh', gain: 0.55 * g, pitch: pitch * 1.1, pan }, { t: e.t, sfx: 'slash', gain: Math.min(1, 0.8 * pw) * g, pitch, pan }, ...(e.kind === 'kill' ? [{ t: e.t + 0.02, sfx: 'impact', gain: 0.6 * g, pitch: 0.8, pan }, { t: e.t + 0.55 + 0.2 * hash(i), sfx: 'land', gain: 0.6 * g, pitch: 0.9 + 0.2 * hash(i * 1.3), pan }] : [])];
    if (e.kind === 'stab') return [{ t: e.t - 0.12, sfx: 'swoosh', gain: 0.45 * g, pitch: pitch * 1.3, pan }, { t: e.t, sfx: 'slash', gain: 0.8 * g, pitch: pitch * 1.25, pan }, { t: e.t + 0.01, sfx: 'impact', gain: 0.45 * g, pitch: 1.1, pan }];
    if (e.kind === 'kick') return [{ t: e.t - 0.14, sfx: 'swoosh', gain: 0.4 * g, pitch: 0.8, pan }, { t: e.t, sfx: 'impact', gain: 0.85 * g, pitch: 0.85, pan }];
    if (e.kind === 'thud') return [{ t: e.t, sfx: 'land', gain: 0.7 * g * pw, pitch, pan }];
    if (e.kind === 'whoosh') return [{ t: e.t, sfx: 'swoosh', gain: 0.5 * g * pw, pitch, pan }];
    return [];
  });
  return { fx, over, top, shake, sounds, events: ev };
}

/** The sweep of a fast blade: a crescent that is brightest at its leading edge and at the point. */
function drawArc(g, cam, a) {
  const n = a.pts.length; if (n < 3) return;
  const P = a.pts.map((b) => ({ o: cam.project(V.lerp(b.base, b.tip, 0.5)), t: cam.project(b.tip) }));
  if (P.some((q) => q.o[2] < 0.15 || q.t[2] < 0.15)) return;
  const k = clamp((a.sp - 5.5) / 9), c = hex(a.color);
  g.save(); g.globalCompositeOperation = 'screen';
  for (let i = 0; i < n - 1; i++) {
    const u = (i + 1) / (n - 1), al = k * 0.42 * u * u * u;
    g.beginPath(); g.moveTo(P[i].o[0], P[i].o[1]); g.lineTo(P[i].t[0], P[i].t[1]); g.lineTo(P[i + 1].t[0], P[i + 1].t[1]); g.lineTo(P[i + 1].o[0], P[i + 1].o[1]); g.closePath();
    g.fillStyle = rgba(c, al); g.fill();
  }
  // the bright line the point draws
  g.lineCap = 'round'; g.lineJoin = 'round';
  for (let i = Math.floor(n / 3); i < n - 1; i++) { const u = (i + 1) / (n - 1); g.beginPath(); g.moveTo(P[i].t[0], P[i].t[1]); g.lineTo(P[i + 1].t[0], P[i + 1].t[1]); g.lineWidth = Math.max(1.2, 0.012 * cam.ppm(P[i].t[2]) * u); g.strokeStyle = rgba([255, 255, 255], k * u); g.stroke(); }
  g.restore();
}

/** Blood on the floor: drops, smears and the slow pool under a body. */
function drawStains(g, cam, stains, ts, ctx, groundAt) {
  g.save();
  for (const s of stains) {
    if (s.t > ts) continue;
    let p = s.p;
    if (s.follow && ctx.poses[s.follow]) { const b = ctx.poses[s.follow].j; p = s.p = [lerp(s.p[0], (b.spine02[0] + b.root[0]) / 2, 0.05), 0, lerp(s.p[2], (b.spine02[2] + b.root[2]) / 2, 0.05)]; }
    const y = groundAt(p[0], p[2]) + 0.012, R = s.r * (s.grow ? 0.35 + 0.65 * smooth(clamp((ts - s.t) / s.grow)) : 1);
    const c = cam.project([p[0], y, p[2]]); if (c[2] < 0.3) continue;
    const ex = cam.project([p[0] + R, y, p[2]]), ez = cam.project([p[0], y, p[2] + R]);
    if (ex[2] < 0.2 || ez[2] < 0.2) continue;
    const ax = ex[0] - c[0], ay = ex[1] - c[1], bx = ez[0] - c[0], by = ez[1] - c[1];
    if (Math.abs(ax * by - ay * bx) < 0.6) continue;
    g.beginPath();
    const lobes = s.grow ? 9 : 5, sd = (s.st ?? s.r) * 91.7;
    for (let i = 0; i <= 24; i++) { const a = (i / 24) * Math.PI * 2, rr = 1 + 0.22 * Math.sin(a * lobes + sd) + 0.12 * Math.sin(a * 3 + sd * 2.3); const x = c[0] + (ax * Math.cos(a) + bx * Math.sin(a)) * rr, yy = c[1] + (ay * Math.cos(a) + by * Math.sin(a)) * rr; if (i) g.lineTo(x, yy); else g.moveTo(x, yy); }
    g.closePath(); g.fillStyle = s.grow ? '#4d060b' : '#6a0a10'; g.globalAlpha = s.grow ? 0.9 : 0.85; g.fill();
    if (s.grow && Math.hypot(ax, ay) > 14) { g.beginPath(); g.ellipse(c[0] - ax * 0.25, c[1] - Math.abs(by) * 0.3, Math.hypot(ax, ay) * 0.3, Math.max(1, Math.hypot(bx, by) * 0.16), Math.atan2(ay, ax), 0, 7); g.fillStyle = '#9a1820'; g.globalAlpha = 0.5; g.fill(); }
  }
  g.restore();
}

/** Lines that race outward from a blow (two or three frames; for the few blows that end something). */
export function impactLines(g, cam, p, k, seed = 1) {
  const R = rng(seed + 5), W = cam.W, H = cam.H, D = Math.hypot(W, H);
  g.save(); g.globalCompositeOperation = 'source-over'; g.translate(p[0], p[1]);
  for (let i = 0; i < 70; i++) {
    const a = R() * Math.PI * 2, r0 = D * (0.1 + 0.14 * R()) * (1.25 - 0.4 * k), w = 0.004 + 0.012 * R();
    g.beginPath(); g.moveTo(Math.cos(a - w) * D, Math.sin(a - w) * D); g.lineTo(Math.cos(a) * r0, Math.sin(a) * r0); g.lineTo(Math.cos(a + w) * D, Math.sin(a + w) * D); g.closePath();
    g.fillStyle = i % 3 ? `rgba(10,8,14,${0.85 * k})` : `rgba(255,255,255,${0.9 * k})`; g.fill();
  }
  g.restore();
}
void noise1; void smooth;

/**
 * The beats of solved sword fights (kit fightBeats) as events for combat(): a turned blow is a clash where the blades
 * meet, a blow that lands is a cut (a kill when it ends the fight). pair(f) → [attacker id, defender id] of fight f.
 */
export function fightEvents(beats, pair) {
  // the blow that ends a fight is the kill (the last one that lands, when the beats do not say)
  const last = {}; for (const b of beats) if (b.hit) last[b.f] = b;
  return beats.map((b) => {
    const [ia, ib] = pair(b.f), by = b.by === 'a' ? ia : ib, on = b.by === 'a' ? ib : ia;
    if (b.hit) return { t: b.apex, kind: b.ko ?? last[b.f] === b ? 'kill' : 'cut', by, on, power: b.power ?? 1 };
    if (b.defend === 'parry' || b.defend === 'block') return { t: b.apex, kind: 'clash', by, on, power: b.power ?? 1, at: (ctx) => { const A = bladeOf(ctx.poses[ia]), B = bladeOf(ctx.poses[ib]); if (A && B) return V.mid(V.lerp(A.base, A.tip, 0.45), V.lerp(B.base, B.tip, 0.45)); const pa = ctx.poses[ia], pb = ctx.poses[ib]; return pa && pb ? V.add(V.mid(pa.j['wrist.R'], pb.j['wrist.R']), [0, 0.25, 0]) : [0, 1.2, 0]; } };
    return { t: b.apex, kind: 'whoosh', by, on, power: 0.8 };
  });
}
