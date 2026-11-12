// Effects that live in the world: particles (dust, embers, snow, petals, sparks, smoke), fire, sword trails, light.
import { createCanvas } from '@napi-rs/canvas';
import { V, clamp, lerp, hex, rgba, rng, noise1, hash } from './core.js';

const sprites = new Map();
/** A soft round dab of one colour (drawn once, stamped many times). */
export function dab(color, hard = 0) {
  const key = color + hard;
  let s = sprites.get(key);
  if (s) return s;
  s = createCanvas(64, 64); const g = s.getContext('2d'), c = hex(color);
  const gr = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  gr.addColorStop(0, rgba(c, 1)); gr.addColorStop(hard * 0.8, rgba(c, 1)); gr.addColorStop(hard * 0.8 + (1 - hard * 0.8) * 0.5, rgba(c, 0.38)); gr.addColorStop(1, rgba(c, 0));
  g.fillStyle = gr; g.fillRect(0, 0, 64, 64);
  sprites.set(key, s);
  return s;
}
/** Stamp light into the picture: a glow at a picture point. */
export function glow(g, x, y, r, color, alpha = 1, op = 'screen') {
  if (r < 1 || alpha <= 0.003) return;
  g.save(); g.globalCompositeOperation = op; g.globalAlpha = clamp(alpha); g.drawImage(dab(color), x - r, y - r, r * 2, r * 2); g.restore();
}

/**
 * A cloud of small things. o: {
 *   rate (per second), max, spawn(rand, t) → { p, v, life, size, … } | null, gravity, drag, wind (how much the wind carries them),
 *   kind: 'dab' | 'disc' | 'streak' | 'petal' | 'flake', color | colors, alpha, fade: [in, out] parts of life, grow, op, ground, bounce }
 */
export class Particles {
  constructor(o) { this.o = o; this.ps = []; this.acc = 0; this.rand = rng(o.seed ?? 7); this.t = 0; }
  burst(n, make) { for (let i = 0; i < n; i++) { const p = make(this.rand, i); if (p) { p.age = 0; p.seed = this.rand(); this.ps.push(p); } } }
  step(dt, t, sc) {
    const o = this.o; this.t = t;
    if (o.rate && o.spawn) {
      this.acc += o.rate * dt;
      while (this.acc >= 1) { this.acc -= 1; if (this.ps.length >= (o.max ?? 1500)) continue; const p = o.spawn(this.rand, t); if (p) { p.age = 0; p.seed = this.rand(); this.ps.push(p); } }
    }
    const gy = (o.gravity ?? 0) * 9.81, drag = o.drag ?? 0, wk = o.wind ?? 0, ground = o.ground;
    for (const p of this.ps) {
      p.age += dt;
      let ax = 0, ay = -gy, az = 0;
      if (wk && sc?.wind) { const w = sc.wind(p.p, t); ax += (w[0] - p.v[0]) * wk; ay += (w[1] - p.v[1]) * wk * 0.5; az += (w[2] - p.v[2]) * wk; }
      if (o.swirl) { ax += noise1(t * 0.9 + p.seed * 40, 1) * o.swirl; az += noise1(t * 0.8 + p.seed * 70, 2) * o.swirl; ay += noise1(t * 1.1 + p.seed * 90, 3) * o.swirl * 0.4; }
      p.v[0] += (ax - p.v[0] * drag) * dt; p.v[1] += (ay - p.v[1] * drag) * dt; p.v[2] += (az - p.v[2] * drag) * dt;
      p.p[0] += p.v[0] * dt; p.p[1] += p.v[1] * dt; p.p[2] += p.v[2] * dt;
      if (ground !== undefined && p.p[1] < ground) { if (o.bounce) { p.p[1] = ground; p.v[1] = -p.v[1] * o.bounce; p.v[0] *= 0.6; p.v[2] *= 0.6; } else if (o.settle) { p.p[1] = ground; p.v = [0, 0, 0]; } else p.age = p.life + 1; }
    }
    if (this.ps.length) this.ps = this.ps.filter((p) => p.age < p.life);
  }
  /** Things to hand to Scene.draw: the cloud cut into depth slices so nearer bodies cover farther particles. */
  items(cuts = [3.5, 9, 22, 60]) {
    const out = [], edges = [0, ...cuts, 1e9];
    for (let i = 0; i < edges.length - 1; i++) { const a = edges[i], b = edges[i + 1]; out.push({ p: [0, 0, 0], depth: i === edges.length - 2 ? Math.min(a * 1.6, 400) : (a + b) / 2, draw: (g, cam) => this.draw(g, cam, a, b) }); }
    return out;
  }
  draw(g, cam, zNear = 0, zFar = 1e9) {
    const o = this.o;
    g.save();
    if (o.op) g.globalCompositeOperation = o.op;
    const fin = o.fade?.[0] ?? 0.1, fout = o.fade?.[1] ?? 0.4;
    for (const p of this.ps) {
      const s = cam.project(p.p), z = s[2];
      if (z < 0.2 || z < zNear || z >= zFar || s[0] < -50 || s[0] > cam.W + 50 || s[1] < -50 || s[1] > cam.H + 50) continue;
      const u = p.age / p.life, a = (p.alpha ?? o.alpha ?? 1) * Math.min(1, u / fin, (1 - u) / fout) * clamp(z / 0.8);
      if (a <= 0.004) continue;
      const size = Math.min((o.kind === 'streak' ? 0.03 : 0.25) * cam.H, (p.size ?? o.size ?? 0.05) * (1 + (o.grow ?? 0) * u) * cam.f / z); // a spark or a drop at the lens must not fill the picture
      const col = p.color ?? o.color ?? '#ffffff';
      g.globalAlpha = clamp(a);
      if (o.kind === 'streak') {
        const q = cam.project([p.p[0] - p.v[0] * (o.streak ?? 0.03), p.p[1] - p.v[1] * (o.streak ?? 0.03), p.p[2] - p.v[2] * (o.streak ?? 0.03)]);
        if (q[2] < 0.25 || z < 0.5) continue; // its tail is at or behind the lens plane: it would be drawn as a bar across the frame
        g.strokeStyle = col; g.lineWidth = Math.max(0.7, size); g.lineCap = 'round'; g.beginPath(); g.moveTo(s[0], s[1]); g.lineTo(q[0], q[1]); g.stroke();
      } else if (o.kind === 'petal') {
        const ang = p.seed * 6.28 + this.t * (2 + p.seed * 3), sq = 0.35 + 0.65 * Math.abs(Math.sin(this.t * (1.5 + p.seed * 2.5) + p.seed * 10));
        g.fillStyle = col; g.beginPath(); g.ellipse(s[0], s[1], Math.max(0.8, size), Math.max(0.5, size * 0.55 * sq), ang, 0, 7); g.fill();
      } else if (o.kind === 'disc' || size < 1.6) {
        g.fillStyle = col; g.beginPath(); g.arc(s[0], s[1], Math.max(0.6, size), 0, 7); g.fill();
      } else {
        g.drawImage(dab(col, o.hard ?? 0), s[0] - size, s[1] - size, size * 2, size * 2);
      }
    }
    g.restore();
  }
}

/* ready-made clouds ------------------------------------------------------------------------------------------------ */
const boxIn = (r, b) => [lerp(b[0][0], b[1][0], r()), lerp(b[0][1], b[1][1], r()), lerp(b[0][2], b[1][2], r())];
/** Falling snow in a box of air around the action: box [[x0,y0,z0],[x1,y1,z1]] */
export const snow = (box, o = {}) => new Particles({ rate: o.rate ?? 160, max: 2600, kind: 'disc', color: o.color ?? '#f4f7fd', alpha: o.alpha ?? 0.9, gravity: 0.012, drag: 1.2, wind: 1.4, swirl: 0.5, ground: box[0][1], fade: [0.05, 0.1], seed: o.seed ?? 3,
  spawn: (r) => ({ p: [lerp(box[0][0], box[1][0], r()), box[1][1], lerp(box[0][2], box[1][2], r())], v: [0, -0.7 - r() * 0.5, 0], life: 14, size: 0.012 + r() * 0.016 }) });
/** Blossom petals drifting from a tree or across a yard. */
export const petals = (box, o = {}) => new Particles({ rate: o.rate ?? 22, max: 900, kind: 'petal', colors: null, alpha: 0.95, gravity: 0.02, drag: 1.0, wind: 1.6, swirl: 0.9, ground: box[0][1], settle: false, fade: [0.05, 0.15], seed: o.seed ?? 5,
  spawn: (r) => ({ p: boxIn(r, [[box[0][0], box[1][1] * 0.6, box[0][2]], box[1]]), v: [0.2, -0.3, 0], life: 12, size: 0.022 + r() * 0.02, color: (o.colors ?? ['#ffd3e2', '#ffc1d6', '#fff0f5'])[Math.floor(r() * 3)] }) });
/** Sparks rising from a fire or a burning place. box = where they are born. */
export const embers = (box, o = {}) => new Particles({ rate: o.rate ?? 60, max: 1600, kind: 'dab', op: 'screen', hard: 0.3, alpha: 1, gravity: -0.035, drag: 0.5, wind: 0.8, swirl: 1.6, fade: [0.05, 0.5], seed: o.seed ?? 9,
  spawn: (r) => ({ p: boxIn(r, box), v: [(r() - 0.5) * 0.8, 1.2 + r() * 2.2, (r() - 0.5) * 0.8], life: 1.6 + r() * 3.2, size: (o.size ?? 0.03) * (0.5 + r()), color: ['#ffb347', '#ff7a2a', '#ffd98a'][Math.floor(r() * 3)] }) });
/** Smoke climbing from a fire. */
export const smoke = (box, o = {}) => new Particles({ rate: o.rate ?? 9, max: 500, kind: 'dab', alpha: o.alpha ?? 0.5, gravity: -0.012, drag: 0.35, wind: 0.5, swirl: 0.4, grow: 2.6, fade: [0.12, 0.6], seed: o.seed ?? 13,
  spawn: (r) => ({ p: boxIn(r, box), v: [(r() - 0.5) * 0.4, 1.4 + r() * 1.2, (r() - 0.5) * 0.4], life: 5 + r() * 4, size: (o.size ?? 0.9) * (0.7 + 0.6 * r()), color: (o.colors ?? ['#2a2630', '#3a343c', '#4a4248'])[Math.floor(r() * 3)] }) });
/** Dust kicked up at a point (a footfall, a landing, a slide): call .burst on it. */
export const dust = (o = {}) => new Particles({ kind: 'dab', alpha: o.alpha ?? 0.42, gravity: -0.004, drag: 2.4, grow: 2.2, fade: [0.08, 0.7], color: o.color ?? '#c9b99a', seed: o.seed ?? 17 });
export function puff(ps, at, amount = 1, dir = [0, 0, 0]) {
  ps.burst(Math.round(5 * amount), (r) => ({ p: [at[0] + (r() - 0.5) * 0.12, at[1] + 0.03, at[2] + (r() - 0.5) * 0.12], v: [dir[0] * 0.6 + (r() - 0.5) * 1.1 * amount, 0.25 + r() * 0.5 * amount, dir[2] * 0.6 + (r() - 0.5) * 1.1 * amount], life: 0.7 + r() * 0.6, size: (0.07 + r() * 0.07) * Math.sqrt(amount) }));
}
/** Sparks from steel on steel: call burstSparks(ps, at, n). */
export const sparks = (o = {}) => new Particles({ kind: 'streak', streak: 0.035, op: 'screen', alpha: 1, gravity: 0.9, drag: 1.2, fade: [0.0, 0.6], ground: o.ground ?? 0, bounce: 0.3, seed: o.seed ?? 21 });
export function burstSparks(ps, at, n = 18, power = 1, dir = null) {
  ps.burst(n, (r) => { const a = r() * 6.28, e = (r() - 0.3) * 1.6, sp = (2 + r() * 5) * power; return { p: [...at], v: [Math.cos(a) * Math.cos(e) * sp + (dir?.[0] ?? 0) * 3, Math.sin(e) * sp + 1, Math.sin(a) * Math.cos(e) * sp + (dir?.[2] ?? 0) * 3], life: 0.25 + r() * 0.45, size: 0.006 + r() * 0.008, color: ['#fff2c0', '#ffd27a', '#ffffff'][Math.floor(r() * 3)] }; });
}
/** Rain in a box of air. */
export const rain = (box, o = {}) => new Particles({ rate: o.rate ?? 900, max: 5000, kind: 'streak', streak: 0.03, color: o.color ?? '#cfe0f5', alpha: 0.5, gravity: 0, ground: box[0][1], fade: [0, 0.05], seed: 23,
  spawn: (r) => ({ p: [lerp(box[0][0], box[1][0], r()), box[1][1], lerp(box[0][2], box[1][2], r())], v: [1.5, -14 - r() * 3, 0.5], life: 2, size: 0.004 }) });

/**
 * A fire: flames that lick, a glow, and its light on the ground. o: { p, w, h, seed, tongues }
 * Add its item to the scene's world (or pass it with the actors) so bodies in front cover it.
 */
export function fire(o) {
  const seed = o.seed ?? 1, n = o.tongues ?? 5;
  return {
    p: o.p, r: o.h * 2, bias: o.bias ?? 0,
    draw(g, cam, sc, t) {
      const b = cam.project(o.p); if (b[2] < 0.3) return;
      const k = cam.f / b[2], w = o.w * k, h = o.h * k * (o.amt ? o.amt(t) : 1);
      if (h < 1) return;
      glow(g, b[0], b[1] - h * 0.45, h * 2.4, '#ff7a22', 0.55 * (0.85 + 0.15 * noise1(t * 9, seed)));
      const layer = (scale, color, lift) => {
        g.beginPath();
        for (let i = 0; i < n; i++) {
          const u = n === 1 ? 0.5 : i / (n - 1), bx = b[0] + (u - 0.5) * w * scale, edge = 1 - Math.abs(u - 0.5) * 1.1;
          const hh = h * scale * edge * (0.55 + 0.45 * (0.5 + 0.5 * noise1(t * 3.1 + i * 9.7, seed))), tw = w * scale * 0.24;
          const tx = bx + noise1(t * 2.3 + i * 5.3, seed + 1) * w * 0.22 + (sc.wind ? sc.wind(o.p, t)[0] * h * 0.05 : 0), ty = b[1] - hh - lift * h;
          g.moveTo(bx - tw, b[1]);
          g.bezierCurveTo(bx - tw * 1.25, b[1] - hh * 0.45, tx - tw * 0.3, ty + hh * 0.35, tx, ty);
          g.bezierCurveTo(tx + tw * 0.4, ty + hh * 0.4, bx + tw * 1.25, b[1] - hh * 0.4, bx + tw, b[1]);
          g.closePath();
        }
        g.fillStyle = color; g.fill();
      };
      layer(1, '#e2441a', 0); layer(0.74, '#ff8a1e', 0); layer(0.46, '#ffd35a', 0); layer(0.22, '#fff6cf', 0);
    },
  };
}

/** The ribbon of light a fast blade leaves: push(base, tip) every frame; it fades on its own. */
export class Trail {
  constructor(o = {}) { this.o = o; this.pts = []; this.max = o.len ?? 9; }
  push(base, tip, on = true) { this.pts.push({ a: [...base], b: [...tip], on }); if (this.pts.length > this.max) this.pts.shift(); }
  clear() { this.pts.length = 0; }
  draw(g, cam) {
    const n = this.pts.length; if (n < 2) return;
    g.save(); g.globalCompositeOperation = this.o.op ?? 'screen';
    for (let i = 1; i < n; i++) {
      const p0 = this.pts[i - 1], p1 = this.pts[i]; if (!p0.on || !p1.on) continue;
      const a0 = cam.project(V.lerp(p0.a, p0.b, this.o.from ?? 0.25)), b0 = cam.project(p0.b), a1 = cam.project(V.lerp(p1.a, p1.b, this.o.from ?? 0.25)), b1 = cam.project(p1.b);
      if (a0[2] < 0.2 || b0[2] < 0.2 || a1[2] < 0.2 || b1[2] < 0.2) continue;
      const u = i / (n - 1);
      g.globalAlpha = (this.o.alpha ?? 0.75) * u * u;
      g.fillStyle = this.o.color ?? '#eaf4ff';
      g.beginPath(); g.moveTo(a0[0], a0[1]); g.lineTo(b0[0], b0[1]); g.lineTo(b1[0], b1[1]); g.lineTo(a1[0], a1[1]); g.closePath(); g.fill();
    }
    g.restore();
  }
}

/** Shafts of light from a picture point (the sun) fanning down. */
export function rays(g, cam, at, o = {}) {
  const n = o.n ?? 7, t = o.t ?? 0, len = (o.len ?? 1.4) * cam.H;
  g.save(); g.globalCompositeOperation = 'screen';
  for (let i = 0; i < n; i++) {
    const a = (o.from ?? 0.25) + ((o.to ?? 2.9) - (o.from ?? 0.25)) * ((i + 0.5 * Math.sin(t * 0.2 + i)) / n), w = (o.spread ?? 0.05) * (0.6 + 0.8 * hash(i * 3.3 + (o.seed ?? 1)));
    const gr = g.createLinearGradient(at[0], at[1], at[0] + Math.cos(a) * len, at[1] + Math.sin(a) * len);
    gr.addColorStop(0, rgba(hex(o.color ?? '#ffe2b0'), (o.alpha ?? 0.16) * (0.6 + 0.4 * Math.sin(t * 0.5 + i * 2)))); gr.addColorStop(1, rgba(hex(o.color ?? '#ffe2b0'), 0));
    g.fillStyle = gr; g.beginPath(); g.moveTo(at[0], at[1]); g.lineTo(at[0] + Math.cos(a - w) * len, at[1] + Math.sin(a - w) * len); g.lineTo(at[0] + Math.cos(a + w) * len, at[1] + Math.sin(a + w) * len); g.closePath(); g.fill();
  }
  g.restore();
}
