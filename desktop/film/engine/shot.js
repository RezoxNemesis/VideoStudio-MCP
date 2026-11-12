// A shot: a set, actors with their solved performances, a camera, effects — rendered frame by frame at 60 a second
// to a video file (or to a contact sheet for review).
import { createCanvas } from '@napi-rs/canvas';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import { makeCam, inView } from './camera.js';
import { ENV } from './env.js';
import { loadBake, poseAt } from './bake.js';
import { bodyOf } from './body.js';
import { Character } from './character.js';
import { V, clamp, hash, noise1 } from './core.js';
import * as post from './post.js';
import { dust, puff } from './fx.js';
import { jolt, postLook, resolveLook, setLook } from './look.js';
import { applyPaint } from '../kit/paint.js';

export const FPS = 60, FW = 1920, FH = 1080;

/** When an actor blinks: a steady, irregular rhythm of its own. Returns 0 (open) … 1 (shut). */
export function autoBlink(t, seed = 1) {
  const period = 3.4, i = Math.floor(t / period);
  let b = 0;
  for (const k of [i - 1, i]) {
    const t0 = k * period + hash(k * 7.3 + seed * 13.1) * period * 0.85, d = t - t0;
    if (d > 0 && d < 0.16) b = Math.max(b, Math.sin((d / 0.16) * Math.PI));
    if (hash(k * 3.1 + seed) > 0.72) { const d2 = t - t0 - 0.26; if (d2 > 0 && d2 < 0.14) b = Math.max(b, Math.sin((d2 / 0.14) * Math.PI)); }
  }
  return b;
}

const BREATH = { spine03: 0.3, spine02: 0.7, spine01: 1, neck01: 1, neck03: 1, head: 1, 'head:tail': 1, 'eye.L': 1, 'eye.R': 1, 'jaw:tail': 1, 'clavicle.L': 1, 'clavicle.R': 1, 'upperarm01.L': 1, 'upperarm01.R': 1, 'lowerarm01.L': 0.6, 'lowerarm01.R': 0.6, 'wrist.L': 0.25, 'wrist.R': 0.25 };
/** Breathing and the slow sway of balance, laid over a pose (so nobody is ever a statue). */
export function enliven(pose, t, seed, amp = 1) {
  const j = pose.j, k = pose.height / 1.8, ank = Math.min(j['foot.L'][1], j['foot.R'][1]), top = j.head[1] - ank;
  const sx = (noise1(t * 0.23 + seed, 1) * 0.012 + noise1(t * 0.63 + seed, 2) * 0.004) * amp * k, sz = (noise1(t * 0.19 + seed, 3) * 0.01 + noise1(t * 0.55 + seed, 4) * 0.003) * amp * k;
  const br = Math.sin(t * 2 * Math.PI * (0.21 + 0.06 * hash(seed)) + seed * 3), b = 0.0048 * amp * k;
  for (const name in j) {
    const p = j[name], u = clamp((p[1] - ank - 0.06 * k) / top), w = u * u * (3 - 2 * u);
    p[0] += sx * w; p[2] += sz * w;
    const bw = BREATH[name]; if (bw) p[1] += br * b * bw;
  }
}

export class ShotRun {
  /**
   * shot: { id, dur, set(ctx) → Scene, actors: [{ id, design, bake, as, t0, rate, xf, face(t, ctx), ground, rim, show(t), simple }],
   *         cam(t, ctx) → { pos, target, fov, roll, shake: { amp, freq } }, dof(t, ctx) → { near, far, blur } | null,
   *         fx(ctx) → [{ step(dt, t, scene), items() }], under/over(g, cam, scene, t, ctx), post: { vignette, grain, fadeIn, fadeOut, wash }, subs(t) → text }
   * cast: name → design
   */
  constructor(shot, cast, o = {}) {
    this.shot = shot; this.W = o.W ?? FW; this.H = o.H ?? FH; this.fps = o.fps ?? FPS;
    this.o = o;
    // the kind of animation it is drawn as (engine/look.js): the shot's own, else the film's
    this.look = resolveLook(shot.look ?? o.look ?? ENV.look); setLook(this.look);
    this.canvas = createCanvas(o.probe ? 2 : this.W, o.probe ? 2 : this.H); this.g = this.canvas.getContext('2d');
    this.ctx = { shot, t: 0, poses: {}, chars: {}, W: this.W, H: this.H, o };
    this.scene = o.probe ? null : shot.set(this.ctx); this.ctx.scene = this.scene;
    // a look that lives in the air (2.5D, 4D) thickens the haze with distance
    if (this.scene && this.look.fog) { this.scene.fogD /= this.look.fog; this.scene.fogMax = Math.min(0.94, this.scene.fogMax * 1.04); } // probe: poses, camera and faces only (for fingerprints)
    this.actors = (shot.actors ?? []).map((a) => {
      const design = typeof a.design === 'string' ? cast[a.design] : a.design;
      if (!design) throw new Error(`shot ${shot.id}: no design ${a.design}`);
      const ch = new Character(design);
      this.ctx.chars[a.id] = ch;
      return { ...a, ch, bk: loadBake(a.bake), was: 0 };
    });
    this.fx = !o.probe && shot.fx ? shot.fx(this.ctx) : [];
    this.dust = !o.probe && this.scene.o.dust ? dust({ color: this.scene.o.dust }) : null;
    if (this.dust) this.fx.push(this.dust);
    this.frame = 0;
    // let cloth and hair come to rest in the first pose before the shot starts
    // (the performance before the cut is played through, unseen, so a cape is already flying when the shot opens)
    const dt = 1 / this.fps;
    for (let i = 0; i < (o.probe ? 0 : 90); i++) { this.poseAll(-(90 - i) * dt); for (const a of this.actors) if (!a.simple) a.ch.step(a.pose, dt, -(90 - i) * dt, { wind: this.scene.wind, ground: a.gnd ?? 0 }); }
    this.poseAll(0);
    if (shot.warm && !o.probe) for (let i = 0; i < shot.warm * this.fps; i++) for (const f of this.fx) f.step(dt, -shot.warm + i * dt, this.scene);
  }
  poseAll(t0) {
    // story time: a shot may bend it (shot.warpT: speed ramps, hit-stop) — bodies, faces and cameras follow the bent clock
    const t = this.shot.warpT && t0 > 0 ? this.shot.warpT(t0) : t0; this.ctx.tw = t;
    // drawn animation holds each drawing: shot.hold = how many frames a character's drawing stays (2 = "on twos"); the
    // camera, cloth, hair and effects still move on every frame. A function of story time lets fast action go "on ones".
    // a look with its own stepping (paper, puppet and pixel animation) moves on that, whatever the shot asks
    const hold = this.look.hold ?? (typeof this.shot.hold === 'function' ? this.shot.hold(t, this.ctx) : (this.shot.hold ?? this.o.hold ?? 1));
    const th = hold > 1 && t0 >= 0 ? Math.floor(t * this.fps / hold + 1e-6) * hold / this.fps : t;
    for (const a of this.actors) {
      const t = a.hold === 1 ? this.ctx.tw : th; // (an actor can opt out: hold: 1)
      const xf = typeof a.xf === 'function' ? a.xf(t, this.ctx) : a.xf;
      a.pose = poseAt(a.bk, a.as ?? a.id, (a.t0 ?? 0) + t * (a.rate ?? 1), xf ?? null);
      // the real body of this actor, when the performance was solved with bodies (drawn by bodydraw.js)
      if (!a.simple && a.mesh !== false && !this.o.probe && !this.o.noMesh) { const B = bodyOf(a.bk, a.as ?? a.id); if (B) a.pose.body = { B, t: (a.t0 ?? 0) + t * (a.rate ?? 1), xf: xf ?? null, hands: typeof a.hands === 'function' ? a.hands(t, this.ctx) : a.hands }; }
      // a body that stands is never still: it breathes and its weight drifts over its feet
      if (a.alive !== 0 && !this.o.probe) {
        const r = a.pose.j.root, sp = a.lastRoot ? Math.hypot(r[0] - a.lastRoot[0], r[2] - a.lastRoot[2]) * this.fps : 0; a.lastRoot = [r[0], r[1], r[2]];
        a.still = (a.still ?? 1) + (clamp(1 - sp / 0.25) - (a.still ?? 1)) * 0.08;
        if (a.still > 0.02) enliven(a.pose, (a.t0 ?? 0) + t, a.id.length * 3.1 + a.id.charCodeAt(a.id.length - 1) * 0.37, (a.alive ?? 1) * a.still);
      }
      if (a.fit) a.fit(a.pose, t, this.ctx);
      a.gnd = typeof a.ground === 'function' ? a.ground(a.pose, t, this.ctx) : (a.gnd ?? 0); // the height of the ground under this actor (stairs, a dike)
      this.ctx.poses[a.id] = a.pose;
    }
  }
  /** What decides how the frame at t looks, without drawing it: poses, camera, focus, faces (probe mode). */
  probe(t) {
    const { shot, ctx } = this; ctx.t = t; this.poseAll(t);
    const c = shot.cam(t, ctx); ctx.cam = { pos: c.pos };
    const faces = {}, shown = {};
    for (const a of this.actors) { shown[a.id] = a.show ? !!a.show(t, ctx) : true; faces[a.id] = a.face ? a.face(t, ctx) : null; }
    return { cam: { pos: c.pos, target: c.target, fov: c.fov ?? 35, roll: c.roll ?? 0, shake: c.shake ?? null }, dof: shot.dof ? shot.dof(t, ctx) : null, faces, shown, poses: ctx.poses };
  }
  /** The world into g: the set, the people and everything in it, with the shot's focus (soft far and near zones). */
  drawWorld(g, cam, cp, t, dyn, dof) {
    const { W, H, shot, scene, ctx } = this, L = this.look;
    g.setTransform(1, 0, 0, 1, 0, 0); g.globalAlpha = 1; g.globalCompositeOperation = 'source-over';
    // paper and puppets: every painted layer lies a little above the one behind and casts a soft shadow on it
    if (L.layers) { const k = H / 1080; g.shadowColor = `rgba(36,26,14,${L.layers.a})`; g.shadowBlur = L.layers.blur * k; g.shadowOffsetX = L.layers.dx * k; g.shadowOffsetY = L.layers.dy * k; }
    if (dof && ((dof.blur ?? 0) > 0.5 || (dof.foreBlur ?? 0) > 0.5)) {
      const hw = W >> 1, hh = H >> 1;
      if (!this.bA) { this.bA = createCanvas(hw, hh); this.bB = createCanvas(hw, hh); }
      const ga = this.bA.getContext('2d'), gb = this.bB.getContext('2d'), camH = makeCam({ ...cp, dx: (cp.dx ?? 0) / 2, dy: (cp.dy ?? 0) / 2 }, hw, hh), split = [dof.near ?? 0, dof.far ?? 1e9];
      if ((dof.blur ?? 0) > 0.5) {
        scene.draw(ga, camH, t, dyn, 'back', split);
        gb.filter = `blur(${(dof.blur * (H / 1080) / 2).toFixed(1)}px)`; gb.drawImage(this.bA, 0, 0); gb.filter = 'none';
        g.drawImage(this.bB, 0, 0, W, H);
      } else scene.draw(g, cam, t, dyn, 'back', split);
      if (shot.under) shot.under(g, cam, scene, t, ctx);
      scene.draw(g, cam, t, dyn, 'mid', split);
      // what is nearer than the sharp zone: soft, over everything
      const fore = scene.layers.front.length || scene.layers.world.concat(dyn).some((it) => (it.zone ?? null) === 'fore' || ((it.depth ?? cam.toCam(typeof it.p === 'function' ? it.p(t) : it.p)[2] + (it.bias ?? 0)) < split[0] && !it.zone));
      if (fore) { const fb = dof.foreBlur ?? dof.blur * 1.3; ga.clearRect(0, 0, hw, hh); scene.draw(ga, camH, t, dyn, 'fore', split); gb.clearRect(0, 0, hw, hh); if (fb > 0.5) gb.filter = `blur(${(fb * (H / 1080) / 2).toFixed(1)}px)`; gb.drawImage(this.bA, 0, 0); gb.filter = 'none'; g.drawImage(this.bB, 0, 0, W, H); }
    } else {
      if (shot.under) { scene.draw(g, cam, t, [], 'back', [0, 1e9]); shot.under(g, cam, scene, t, ctx); scene.draw(g, cam, t, dyn, 'mid', [-1e9, 1e9]); scene.draw(g, cam, t, dyn, 'fore', [1e9, 2e9]); }
      else scene.draw(g, cam, t, dyn);
    }
    if (L.layers) { g.shadowColor = 'rgba(0,0,0,0)'; g.shadowBlur = 0; g.shadowOffsetX = 0; g.shadowOffsetY = 0; }
  }
  /** The camera of the frame at t: the shot's own, with its shake and (4D) the breathing of a held camera. */
  camParams(t) {
    const H = this.H, L = this.look, cp = { ...this.shot.cam(t, this.ctx) };
    if (cp.shake) { const s = cp.shake, k = H / 1080; cp.dx = (cp.dx ?? 0) + noise1(t * (s.freq ?? 9), 11) * s.amp * k; cp.dy = (cp.dy ?? 0) + noise1(t * (s.freq ?? 9), 23) * s.amp * k; cp.roll = (cp.roll ?? 0) + noise1(t * (s.freq ?? 9) * 0.7, 37) * s.amp * 0.012; }
    if (L.breathe) { const k = H / 1080 * L.breathe; cp.dx = (cp.dx ?? 0) + noise1(t * 0.31, 5) * 5 * k; cp.dy = (cp.dy ?? 0) + noise1(t * 0.27, 9) * 3.5 * k; }
    return cp;
  }
  /** Draw the frame for time t (seconds from the start of the shot). Frames must be asked for in order. */
  draw(t) {
    const { g, W, H, shot, scene, ctx } = this, dt = 1 / this.fps, L = this.look;
    ctx.t = t; setLook(L);
    this.poseAll(t);
    if (!this.settled) { // shot.settle: extra steps that let hair and cloth come to rest in the opening pose (for a shot that opens just after a jump in the motion)
      this.settled = true;
      const n = shot.settle ?? 0;
      for (const a of this.actors) if (!a.simple) for (let k = n; k > 0; k--) a.ch.step(a.pose, dt, t - k * dt, { wind: scene.wind, ground: a.gnd ?? 0 });
    }
    for (const a of this.actors) {
      if (!a.simple) a.ch.step(a.pose, dt, t, { wind: scene.wind, ground: a.gnd ?? 0 });
      // dust where a foot comes down fast
      if (this.dust) {
        const c = a.pose.contact, rise = c & ~a.was; a.was = c;
        if (rise && a.prevRoot) { const sp = V.dist(a.pose.j.root, a.prevRoot) / dt; if (sp > 1.6) { for (const [bit, jn] of [[1, 'foot.L'], [2, 'foot.R']]) if (rise & bit) puff(this.dust, [a.pose.j[jn][0], a.gnd ?? 0, a.pose.j[jn][2]], clamp((sp - 1) / 3, 0.25, 1.4)); } }
        a.prevRoot = a.pose.j.root;
      }
    }
    for (const f of this.fx) f.step(dt, t, scene);
    const cp = this.camParams(t), cam = makeCam(cp, W, H); ctx.cam = cam;
    const light = scene.light2(cam);
    const dyn = [];
    for (const a of this.actors) {
      if (a.show && !a.show(t, ctx)) continue;
      const pose = a.pose, face = { ...(a.face ? a.face(t, ctx) : {}) };
      face.blink = Math.max(face.blink ?? 0, a.noBlink ? 0 : autoBlink(t + (shot.start ?? 0), a.seed ?? (a.id.length * 7 + a.id.charCodeAt(0))));
      const jo = jolt(L, a.id, ctx.tw ?? t, this.fps, H);
      dyn.push({ p: pose.j.root, bias: a.bias ?? 0, zone: a.zone, actor: a.id, draw: (gg, cm, sc) => {
        if (jo[0] || jo[1]) { gg.save(); gg.translate(jo[0], jo[1]); try { return drawIt(gg, cm, sc); } finally { gg.restore(); } }
        return drawIt(gg, cm, sc);
      } });
      const drawIt = (gg, cm, sc) => {
        if (!inView(cm, pose.j)) { if (globalThis.__cullLog) globalThis.__cullLog('actor', a.id, cm, pose, t); if (!this.o.noCull) return; } // beside or behind the lens: nothing of it belongs in the picture
        const d = V.dist(cm.pos, pose.j.root);
        a.ch.draw(gg, cm, pose, face, a.lit ?? sc.litAt(d), { sun: scene.sun, light: a.light ?? light, rim: a.rim ?? sc.o.rim ?? null, pole: typeof a.pole === 'function' ? a.pole(t, ctx) : a.pole, ground: a.gnd ?? 0, shadow: a.shadow ?? sc.o.shadow !== false, shadowAlpha: sc.o.shadowAlpha ?? 0.28, shadowColor: sc.o.shadowColor });
        if (a.after) a.after(gg, cm, sc, t, pose, ctx);
      };
    }
    for (const f of this.fx) if (f.items) dyn.push(...f.items());
    if (shot.dyn) dyn.push(...shot.dyn(t, ctx));
    let dof = shot.dof ? shot.dof(t, ctx) : null;
    if (!dof && L.autoDof) {
      // 2.5D / 4D: the far world softens behind whoever is nearest
      let near = 1e9; for (const a of this.actors) if (!a.show || a.show(t, ctx)) near = Math.min(near, V.dist(cam.pos, a.pose.j.root));
      dof = { near: 0, far: (near < 1e8 ? near : 12) * L.autoDof, blur: L.dofBlur ?? 2.6 };
    }
    this.drawWorld(g, cam, cp, t, dyn, dof);
    if (shot.over) shot.over(g, cam, scene, t, ctx);
    // fixes painted over this shot in Animate (paint/<shot>.json): they follow the parts they were drawn on
    if (shot.paint && !this.o.noPaint) applyPaint(this, g, { cam, cp, t, dyn, dof });
    if (L.post) postLook(L, g, W, H, { canvas: this.canvas, cam, scene, t, frame: this.frame });
    const ps = { vignette: 0.32, grain: 0.055, ...(scene.o.post ?? {}), ...(shot.post ?? {}) };
    if (L.vignette !== undefined) ps.vignette *= L.vignette;
    if (L.grain !== undefined) ps.grain *= L.grain;
    if (ps.wash) for (const w of ps.wash) { const a = typeof w.a === 'function' ? w.a(t) : w.a; if (w.to) post.washGrad(g, W, H, w.color, w.to, a, w.op); else post.wash(g, W, H, w.color, a, w.op); }
    post.vignette(g, W, H, ps.vignette, ps.vignetteColor);
    if (!this.ctx.o.noGrain) post.grain(g, W, H, ps.grain, this.frame + (shot.seed ?? 0) * 977); // videos get their grain at the final encode
    if (shot.top) shot.top(g, cam, scene, t, ctx);
    if (ps.fadeIn && t < ps.fadeIn) post.fade(g, W, H, 1 - t / ps.fadeIn, ps.fadeColor);
    if (ps.fadeOut && t > shot.dur - ps.fadeOut) post.fade(g, W, H, (t - (shot.dur - ps.fadeOut)) / ps.fadeOut, ps.fadeOutColor ?? ps.fadeColor);
    if (shot.subs && !this.ctx.o.noSubs) { const s = shot.subs(t, ctx); if (s) post.subtitle(g, W, H, s.text ?? s, s); }
    this.frame++;
    return this.canvas;
  }
}

/** Render a shot to a video file. Returns { frames, ms }. */
export async function renderVideo(shot, cast, out, o = {}) {
  const run = new ShotRun(shot, cast, { noGrain: true, ...o }), n = Math.round(shot.dur * run.fps), t0 = Date.now();
  const tmp = out.replace(/\.mp4$/, '.part.mp4');
  const ff = spawn(ENV.ffmpeg, ['-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgba', '-s', `${run.W}x${run.H}`, '-r', String(run.fps), '-i', '-', '-c:v', 'libx264', '-preset', o.preset ?? 'veryfast', '-crf', String(o.crf ?? 13), '-pix_fmt', 'yuv420p', '-movflags', '+faststart', tmp], { stdio: ['pipe', 'inherit', 'inherit'] });
  const done = new Promise((res, rej) => { ff.on('close', (c) => (c === 0 ? res() : rej(new Error(`ffmpeg exit ${c}`)))); ff.on('error', rej); });
  for (let f = 0; f < n; f++) {
    const cv = run.draw(f / run.fps), buf = cv.data();
    if (!ff.stdin.write(buf)) await new Promise((r) => ff.stdin.once('drain', r));
    if (o.progress && f % 20 === 0) o.progress(f, n);
  }
  ff.stdin.end(); await done;
  fs.renameSync(tmp, out);
  return { frames: n, ms: Date.now() - t0 };
}

/** Render chosen moments of a shot into one contact sheet. times: seconds; cols; scale of each cell. */
export function renderSheet(shot, cast, out, times, o = {}) {
  const res = o.res ?? 0.5; // sheets are drawn at half size unless asked otherwise
  const run = new ShotRun(shot, cast, { ...o, W: Math.round(FW * res), H: Math.round(FH * res) }), cols = o.cols ?? 4, rows = Math.ceil(times.length / cols), sc = (o.cell ?? 0.5) / res;
  const cw = Math.round(run.W * sc), ch = Math.round(run.H * sc), sheet = createCanvas(cw * cols, ch * rows), sg = sheet.getContext('2d');
  const n = Math.round(Math.min(shot.dur, Math.max(...times) + 0.001) * run.fps);
  let ti = 0; const want = times.map((t) => Math.round(t * run.fps));
  for (let f = 0; f <= n && ti < want.length; f++) {
    const cv = run.draw(f / run.fps);
    while (ti < want.length && want[ti] === f) {
      sg.drawImage(cv, (ti % cols) * cw, Math.floor(ti / cols) * ch, cw, ch);
      sg.font = `bold ${Math.round(20 * sc * 2)}px sans-serif`; sg.lineWidth = 4; sg.strokeStyle = '#000'; sg.fillStyle = '#fff';
      const label = `${shot.id}  ${(f / run.fps).toFixed(2)}s`; sg.strokeText(label, (ti % cols) * cw + 8, Math.floor(ti / cols) * ch + 26); sg.fillText(label, (ti % cols) * cw + 8, Math.floor(ti / cols) * ch + 26);
      ti++;
    }
  }
  fs.writeFileSync(out, sheet.toBuffer('image/jpeg', o.q ?? 88));
  return out;
}
