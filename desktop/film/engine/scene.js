// A painted world for the camera: sky, far ranges, ground, built things and plants, drawn far-to-near with the
// colour of the air between them and the lens. Everything is placed in world metres and seen through the film camera.
import { V, clamp, lerp, hex, rgb, rgba, mixc, shade as shadeHex, hash, hash2, noise1, fbm, makeGrade, smooth } from './core.js';
import { smoothClosed, smoothOpen, poly } from './draw.js';
import { createCanvas } from '@napi-rs/canvas';

export class Scene {
  /** o: { grade: {amb, tint, tintAmt, sat, gain}, fog: {color, dist, max, pow, start}, sun: [x,y,z] towards the sun, wind } */
  constructor(o = {}) {
    this.o = o; this.grade = makeGrade(o.grade ?? {});
    const f = o.fog ?? {};
    this.haze = hex(f.color ?? '#cfd9e6'); this.fogD = f.dist ?? 260; this.fogMax = f.max ?? 0.9; this.fogPow = f.pow ?? 1; this.fogStart = f.start ?? 0;
    this.sun = V.norm(o.sun ?? [-0.45, 0.7, 0.5]);
    this.layers = { sky: [], far: [], ground: [], world: [], front: [] };
    this.cc = new Map(); this.lits = new Map();
    this.wind = o.wind ?? null; this.ink = o.ink ?? '#1a1722'; this.t = 0;
  }
  fog(d) { return this.fogMax * (1 - Math.exp(-Math.pow(Math.max(0, d - this.fogStart) / this.fogD, this.fogPow))); }
  /** a local colour as the lens sees it at a distance: lit by the shot's light, then lost in the air */
  col(c, d = 0) {
    const q = Math.round(this.fog(d) * 48), key = c + q;
    let r = this.cc.get(key);
    if (!r) { r = rgb(mixc(hex(this.grade(c)), this.haze, q / 48)); this.cc.set(key, r); }
    return r;
  }
  /** the shot's light as a colour function for a character at a distance (see character.draw) */
  litAt(d) {
    const q = Math.round(this.fog(d) * 16);
    if (!q) return this.grade;
    let f = this.lits.get(q);
    if (!f) { const cache = new Map(), a = q / 16; f = (c) => { let r = cache.get(c); if (!r) { r = rgb(mixc(hex(this.grade(c)), this.haze, a)); cache.set(c, r); } return r; }; this.lits.set(q, f); }
    return f;
  }
  add(layer, item) { this.layers[layer].push(item); return item; }
  /** the direction of the light in the picture (towards the light), for cel shading */
  light2(cam) { const x = V.dot(this.sun, cam.R), y = -V.dot(this.sun, cam.U), l = Math.hypot(x, y); return l < 0.15 ? [-0.5, -0.86] : [x / l, y / l]; }
  /**
   * Draw the world. dyn: extra depth-sorted things (actors, effects): { p: [x,y,z] | (t) => p, draw(g, cam, scene, t, z), bias }.
   * part: 'all' | 'back' (sky, far, ground and what is further than zSplit) | 'mid' | 'fore'; split: [zNear, zFar] of the sharp zone.
   */
  draw(g, cam, t, dyn = [], part = 'all', split = null) {
    this.t = t;
    if (part === 'all' || part === 'back') {
      // the sky and the far land change little: while the camera holds still they are painted once and kept
      const sig = [cam.pos, cam.F, cam.U].map((v) => v.map((x) => x.toFixed(4)).join()).join() + [cam.f, cam.cx, cam.cy].map((x) => x.toFixed(2)).join(), key = `${cam.W}x${cam.H}`;
      const c = (this.bg ??= {})[key] ?? (this.bg[key] = { cv: createCanvas(cam.W, cam.H), sig: null, n: 0 });
      if (c.sig !== sig || c.n >= 15) { const cg = c.cv.getContext('2d'); for (const it of this.layers.sky) it.draw(cg, cam, this, t); for (const it of this.layers.far) it.draw(cg, cam, this, t); c.sig = sig; c.n = 0; }
      c.n++;
      g.drawImage(c.cv, 0, 0);
      for (const it of this.layers.ground) it.draw(g, cam, this, t);
    }
    const list = [];
    for (const it of this.layers.world.concat(dyn)) {
      const p = typeof it.p === 'function' ? it.p(t) : it.p, z = it.depth ?? (cam.toCam(p)[2] + (it.bias ?? 0));
      if (z < -(it.r ?? 4)) continue;
      if (split) { const zone = it.zone ?? (z > split[1] ? 'back' : z < split[0] ? 'fore' : 'mid'); if (part !== 'all' && zone !== part) continue; }
      list.push([z, it]);
    }
    list.sort((a, b) => b[0] - a[0]);
    if (globalThis.__dbgWorld) globalThis.__dbgWorld(list, cam, t, part); // debugging hook: may filter the list in place
    for (const [z, it] of list) it.draw(g, cam, this, t, z);
    if (part === 'all' || part === 'fore') for (const it of this.layers.front) it.draw(g, cam, this, t);
  }
}

/* -------------------------------------------------------------------- lens helpers */

/** A world polygon as picture points, cut where it passes behind the lens. Returns [] when nothing is left. */
export function clipPoly(cam, pts, near = 0.08) {
  let cs = pts.map((p) => cam.toCam(p)), any = false;
  for (const c of cs) if (c[2] < near) { any = true; break; }
  if (any) {
    const out = [];
    for (let i = 0; i < cs.length; i++) {
      const a = cs[i], b = cs[(i + 1) % cs.length], ia = a[2] >= near, ib = b[2] >= near;
      if (ia) out.push(a);
      if (ia !== ib) { const u = (near - a[2]) / (b[2] - a[2]); out.push([a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, near]); }
    }
    cs = out;
  }
  if (cs.length < 3) return [];
  return cs.map((c) => [cam.cx + cam.f * c[0] / c[2], cam.cy - cam.f * c[1] / c[2], c[2]]);
}
/** A world line as two picture points, cut at the lens; null when it is all behind. */
export function clipSeg(cam, a, b, near = 0.08) {
  let ca = cam.toCam(a), cb = cam.toCam(b);
  if (ca[2] < near && cb[2] < near) return null;
  if (ca[2] < near) { const u = (near - ca[2]) / (cb[2] - ca[2]); ca = [ca[0] + (cb[0] - ca[0]) * u, ca[1] + (cb[1] - ca[1]) * u, near]; }
  else if (cb[2] < near) { const u = (near - cb[2]) / (ca[2] - cb[2]); cb = [cb[0] + (ca[0] - cb[0]) * u, cb[1] + (ca[1] - cb[1]) * u, near]; }
  return [[cam.cx + cam.f * ca[0] / ca[2], cam.cy - cam.f * ca[1] / ca[2], ca[2]], [cam.cx + cam.f * cb[0] / cb[2], cam.cy - cam.f * cb[1] / cb[2], cb[2]]];
}
/** Picture height of an elevation angle (radians above the horizon) straight ahead. */
export function yOfEl(cam, el) { const pitch = Math.asin(clamp(cam.F[1], -1, 1)); return cam.cy - cam.f * Math.tan(clamp(el - pitch, -1.45, 1.45)); }
/** Picture point of a direction at infinity; null behind the lens. */
export function projDir(cam, d) { const z = V.dot(d, cam.F); if (z < 0.05) return null; return [cam.cx + cam.f * V.dot(d, cam.R) / z, cam.cy - cam.f * V.dot(d, cam.U) / z, z]; }
export const dirOf = (azDeg, elDeg) => { const a = azDeg * Math.PI / 180, e = elDeg * Math.PI / 180, ce = Math.cos(e); return [Math.sin(a) * ce, Math.sin(e), Math.cos(a) * ce]; };

/* ----------------------------------------------------------------------------- sky */

/**
 * o: { stops: [[0..1, colour]…] from 57° up (0) to the horizon (1), sun: { az, el, r (deg), color, glow, glowR (deg), rays },
 *      moon: { az, el, r, color, glow }, stars: { n, amt, seed }, clouds: [{ az, el, w, h (deg), seed, color, shade, alpha, drift }],
 *      band: { color, el (deg), alpha } a glow hugging the horizon }
 */
export function sky(o) {
  const stars = [];
  if (o.stars) { const n = o.stars.n ?? 300; for (let i = 0; i < n; i++) { const a = hash(i * 3.1 + (o.stars.seed ?? 1)) * 360, e = Math.asin(hash(i * 7.7 + 2)) * 180 / Math.PI; stars.push([dirOf(a, e), 0.5 + hash(i * 1.3 + 5) * 1.3, hash(i * 9.1) * 6.28, 0.4 + 0.6 * hash(i * 4.7)]); } }
  const clouds = (o.clouds ?? []).map((c) => ({ ...c, lobes: cloudLobes(c) }));
  return {
    draw(g, cam, sc, t) {
      const W = cam.W, H = cam.H, y0 = yOfEl(cam, 0), y1 = yOfEl(cam, 1.0);
      const gr = g.createLinearGradient(0, y1, 0, y0);
      for (const [u, c] of o.stops) gr.addColorStop(u, c);
      g.fillStyle = gr; g.fillRect(0, 0, W, H);
      if (stars.length) {
        const amt = o.stars.amt ?? 1;
        g.fillStyle = o.stars.color ?? '#ffffff';
        for (const s of stars) { const p = projDir(cam, s[0]); if (!p || p[0] < 0 || p[0] > W || p[1] < 0 || p[1] > y0) continue; g.globalAlpha = clamp(amt * s[3] * (0.65 + 0.35 * Math.sin(t * 2.2 + s[2]))); const r = (0.9 + s[1]) * (H / 1080); g.fillRect(p[0] - r / 2, p[1] - r / 2, r, r); }
        g.globalAlpha = 1;
      }
      for (const body of [o.moon, o.sun]) {
        if (!body) continue;
        const p = projDir(cam, dirOf(body.az, body.el)); if (!p) continue;
        const k = cam.f * Math.PI / 180, r = (body.r ?? 1.2) * k;
        if (body.glow) {
          const R = (body.glowR ?? 18) * k, rg = g.createRadialGradient(p[0], p[1], r * 0.5, p[0], p[1], R);
          rg.addColorStop(0, body.glow); rg.addColorStop(1, 'rgba(0,0,0,0)');
          g.save(); g.globalCompositeOperation = 'screen'; g.fillStyle = rg; g.fillRect(p[0] - R, p[1] - R, R * 2, R * 2); g.restore();
        }
        g.fillStyle = body.color ?? '#fff6dc'; g.beginPath(); g.arc(p[0], p[1], r, 0, 7); g.fill();
        if (body.crescent) { g.fillStyle = body.crescent; g.beginPath(); g.arc(p[0] + r * 0.42, p[1] - r * 0.12, r * 0.86, 0, 7); g.fill(); }
      }
      if (o.band) {
        const yb = yOfEl(cam, (o.band.el ?? 9) * Math.PI / 180), bg = g.createLinearGradient(0, yb, 0, y0);
        bg.addColorStop(0, rgba(hex(o.band.color), 0)); bg.addColorStop(1, o.band.color);
        g.save(); g.globalAlpha = o.band.alpha ?? 1; g.fillStyle = bg; g.fillRect(0, yb, W, y0 - yb + 2); g.restore();
      }
      for (const c of clouds) drawCloud(g, cam, c, t, y0);
    },
  };
}
function cloudLobes(c) {
  const band = c.kind === 'band', n = c.n ?? (band ? 18 : 10), out = [];
  for (let i = 0; i < n; i++) {
    const u = (i + 0.5) / n, x = (u - 0.5) * 2, env = Math.sqrt(Math.max(0, 1 - x * x)), big = hash(i * 5.1 + c.seed * 2), j = (hash(i * 3.3 + c.seed) - 0.5) * 0.1;
    if (band) out.push([x * 0.94 + j, 0.12 + 0.3 * env * big, 0.14 + 0.22 * env * (0.4 + big), 1.6 + 1.2 * hash(i * 7.7 + c.seed)]);
    else out.push([x * 0.86 + j, 0.16 + env * (0.28 + 0.5 * big), 0.2 + 0.3 * env * (0.55 + 0.5 * big), 1.15 + 0.5 * hash(i * 7.7 + c.seed)]);
  }
  return out;
}
function drawCloud(g, cam, c, t, y0) {
  const p = projDir(cam, dirOf(c.az + (c.drift ?? 0) * t, c.el)); if (!p) return;
  const k = cam.f * Math.PI / 180, w = c.w * k / 2, h = c.h * k;
  if (p[0] < -w * 1.3 || p[0] > cam.W + w * 1.3 || p[1] - h * 1.4 > cam.H) return;
  const path = () => { g.beginPath(); for (const [x, y, r, st] of c.lobes) { const cx = p[0] + x * w, cy = p[1] - y * h, rr = r * h; g.moveTo(cx + rr * st, cy); g.ellipse(cx, cy, rr * st, rr, 0, 0, Math.PI * 2); } g.moveTo(p[0] + w * 0.95, p[1] - h * 0.1); g.ellipse(p[0], p[1] - h * 0.1, w * 0.95, h * 0.17, 0, 0, Math.PI * 2); };
  g.save(); g.globalAlpha = c.alpha ?? 0.95;
  const gr = g.createLinearGradient(0, p[1] - h * 1.05, 0, p[1] + h * 0.1);
  gr.addColorStop(0, c.color ?? '#ffffff'); gr.addColorStop(0.45, c.color ?? '#ffffff'); gr.addColorStop(1, c.shade ?? '#c9d3e4');
  path();
  if (c.rim) { g.fillStyle = c.rim; g.fill(); g.clip(); g.translate(0, Math.max(1.5, h * 0.07)); path(); }
  g.fillStyle = gr; g.fill();
  g.restore();
  void y0;
}

/* ------------------------------------------------------------------------ far land */

/**
 * A mountain range: o: { z (distance ahead along +z of `yaw`), yaw (deg, which way from the origin), span, base, h, seed, color,
 *   snow: { line (0..1 of h), color }, rough (0..1), peaks, n }. Seen through the air, so far ranges pale.
 */
export function range(o) {
  const n = o.n ?? 110, span = o.span ?? o.z * 3, yaw = (o.yaw ?? 0) * Math.PI / 180, cs = Math.cos(yaw), sn = Math.sin(yaw), pts = [], seed = o.seed ?? 1, pk = o.peaks ?? 5;
  for (let i = 0; i <= n; i++) {
    const u = i / n, x = (u - 0.5) * span;
    const soft = fbm(u * pk + seed * 3.7, 0.5, 3, seed), sharp = 1 - Math.abs(2 * fbm(u * pk * 2.1 + seed * 1.3, 2.5, 2, seed + 5) - 1);
    let hh = clamp((0.62 * soft + 0.38 * sharp * soft * 1.6 - 0.2) * 1.85);
    hh = Math.pow(hh, 1.15) * (0.6 + 0.4 * Math.sin(Math.PI * clamp(u * 1.06 - 0.03)));
    if (o.gap) hh *= 1 - o.gap[2] * Math.exp(-(((u - o.gap[0]) / o.gap[1]) ** 2)); // a pass in the range
    hh = clamp(hh + (o.rough ?? 0.5) * 0.03 * (hash(i * 1.7 + seed) - 0.5), 0.02, 1);
    const y = (o.base ?? 0) + o.h * hh, z = o.z + (o.bow ?? 0) * x * x / span;
    pts.push([x * cs + z * sn, y, -x * sn + z * cs, hh]);
  }
  const baseY = (o.base ?? 0) - (o.drop ?? 30);
  const foot = pts.map((p) => [p[0], baseY, p[2]]);
  // which way along the ridge is away from the sun: decided when drawn
  const axis = V.norm(V.sub(pts[n], pts[0]));
  return {
    draw(g, cam, sc) {
      const top = [], bot = [], idx = [];
      for (let i = 0; i <= n; i++) { const a = cam.project(pts[i]), b = cam.project(foot[i]); if (a[2] < 1 || b[2] < 1) { if (top.length > 1) break; continue; } top.push(a); bot.push(b); idx.push(i); }
      if (top.length < 2) return;
      const d = o.z, col = sc.col(o.color, d);
      const body = () => { g.beginPath(); g.moveTo(top[0][0], top[0][1]); for (const p of top) g.lineTo(p[0], p[1]); for (let i = bot.length - 1; i >= 0; i--) g.lineTo(bot[i][0], bot[i][1]); g.closePath(); };
      body(); g.fillStyle = col; g.fill();
      g.save(); g.clip();
      // the side of every peak that the sun does not reach
      if (o.shade) {
        const dirn = V.dot(axis, sc.sun) > 0 ? -1 : 1;
        g.fillStyle = sc.col(o.shade, d); g.beginPath();
        for (let k = 1; k < top.length - 1; k++) {
          const i = idx[k]; if (!(pts[i][3] > pts[i - 1][3] && pts[i][3] >= pts[i + 1][3])) continue;
          let j = k; while (j + dirn > 0 && j + dirn < top.length - 1 && pts[idx[j + dirn]][3] < pts[idx[j]][3]) j += dirn;
          const lean = (top[j][0] - top[k][0]) * 0.3;
          g.moveTo(top[k][0], top[k][1]); for (let q = k; q !== j + dirn; q += dirn) g.lineTo(top[q][0], top[q][1]);
          g.lineTo(bot[j][0], bot[j][1]); g.lineTo(bot[k][0] + lean, bot[k][1]); g.closePath();
        }
        g.fill();
      }
      if (o.snow) {
        // snow lies above a line that wanders along the slopes
        const line = o.snow.line ?? 0.55;
        g.beginPath(); g.moveTo(top[0][0], top[0][1]); for (const p of top) g.lineTo(p[0], p[1]);
        for (let k = top.length - 1; k >= 0; k--) { const i = idx[k], w = line + 0.09 * Math.sin(i * 0.83 + seed) + 0.06 * Math.sin(i * 2.1 + seed * 2), q = cam.project([pts[i][0], (o.base ?? 0) + o.h * w, pts[i][2]]); g.lineTo(q[0], q[1]); }
        g.closePath(); g.fillStyle = sc.col(o.snow.color ?? '#f2f5fb', d); g.fill();
        if (o.shade) { // snow in shade is blue
          const dirn = V.dot(axis, sc.sun) > 0 ? -1 : 1; g.save(); g.clip(); g.fillStyle = sc.col(o.snow.shade ?? '#b9c6e2', d); g.beginPath();
          for (let k = 1; k < top.length - 1; k++) { const i = idx[k]; if (!(pts[i][3] > pts[i - 1][3] && pts[i][3] >= pts[i + 1][3])) continue; let j = k; while (j + dirn > 0 && j + dirn < top.length - 1 && pts[idx[j + dirn]][3] < pts[idx[j]][3]) j += dirn; const lean = (top[j][0] - top[k][0]) * 0.3; g.moveTo(top[k][0], top[k][1]); for (let q = k; q !== j + dirn; q += dirn) g.lineTo(top[q][0], top[q][1]); g.lineTo(bot[j][0], bot[j][1]); g.lineTo(bot[k][0] + lean, bot[k][1]); g.closePath(); }
          g.fill(); g.restore();
        }
      }
      g.restore();
      if (o.mist) {
        const yb = Math.max(...bot.map((p) => p[1])), yt = Math.min(...top.map((p) => p[1])) + (yb - Math.min(...top.map((p) => p[1]))) * (1 - (o.mist.h ?? 0.5)), mg = g.createLinearGradient(0, yt, 0, yb);
        mg.addColorStop(0, rgba(hex(o.mist.color), 0)); mg.addColorStop(1, rgba(hex(o.mist.color), o.mist.alpha ?? 0.8));
        g.save(); body(); g.clip(); g.fillStyle = mg; g.fillRect(0, yt, cam.W, yb - yt + 1); g.restore();
      }
    },
  };
}

/* -------------------------------------------------------------------------- ground */

/**
 * Flat ground at height y. o: { y, near, far (colours), farAt (m), patch: { colors, cell, max, size: [a,b], amt }, tuft: { colors, cell, max, h: [a,b], amt },
 *   water: true (a still surface: streaks of light instead of patches) }
 */
export function ground(o) {
  const y = o.y ?? 0, farAt = o.farAt ?? 120;
  const cN = hex(o.near), cF = hex(o.far ?? o.near);
  return {
    draw(g, cam, sc, t) {
      const W = cam.W, H = cam.H, ch = cam.pos[1] - y, hy = yOfEl(cam, 0);
      if (ch < 0.05 || hy > H) return;
      const pitch = Math.asin(clamp(cam.F[1], -1, 1));
      const yAt = (d) => cam.cy + cam.f * Math.tan(clamp(Math.atan(ch / d) + pitch, -1.45, 1.45));
      const top = Math.max(-2, hy - 1), gr = g.createLinearGradient(0, top, 0, H);
      let last = -1;
      for (const d of [5000, 900, 350, 150, 70, 35, 16, 8, 3.5, 1.2]) {
        const u = clamp((yAt(d) - top) / (H - top)); if (u <= last) continue; last = u;
        gr.addColorStop(u, sc.col(rgb(mixc(cN, cF, smooth(d / farAt))), d));
      }
      g.fillStyle = gr; g.fillRect(0, top, W, H - top);
      const hd = Math.hypot(cam.F[0], cam.F[2]) || 1e-6, fx = cam.F[0] / hd, fz = cam.F[2] / hd;
      // what part of the ground the lens sees, out to a distance
      const each = (cell, max, fn) => {
        const cx0 = cam.pos[0] + fx * max * 0.5, cz0 = cam.pos[2] + fz * max * 0.5, r = max * 0.62;
        const x0 = Math.floor((cx0 - r) / cell), x1 = Math.ceil((cx0 + r) / cell), z0 = Math.floor((cz0 - r) / cell), z1 = Math.ceil((cz0 + r) / cell);
        const list = [];
        for (let ix = x0; ix <= x1; ix++) for (let iz = z0; iz <= z1; iz++) {
          const h1 = hash2(ix * 1.31 + 7, iz * 1.73 + 3), wx = (ix + 0.15 + 0.7 * h1) * cell, wz = (iz + 0.15 + 0.7 * hash2(ix * 2.17, iz * 0.91 + 11)) * cell;
          const dx = wx - cam.pos[0], dz = wz - cam.pos[2], zc = dx * cam.F[0] + (y - cam.pos[1]) * cam.F[1] + dz * cam.F[2];
          if (zc < 0.3) continue;
          const d = Math.hypot(dx, dz); if (d > max) continue;
          if (o.mask && !o.mask(wx, wz)) continue;
          const sx = cam.cx + cam.f * (dx * cam.R[0] + (y - cam.pos[1]) * cam.R[1] + dz * cam.R[2]) / zc;
          if (sx < -80 || sx > W + 80) continue;
          const sy = cam.cy - cam.f * (dx * cam.U[0] + (y - cam.pos[1]) * cam.U[1] + dz * cam.U[2]) / zc;
          if (sy > H + 60) continue;
          list.push([zc, sx, sy, d, h1, ix, iz, wx, wz]);
        }
        list.sort((a, b) => b[0] - a[0]);
        for (const q of list) fn(q);
      };
      if (o.patch) {
        const pc = o.patch, cols = pc.colors, sz = pc.size ?? [0.7, 2.4];
        each(pc.cell ?? 3.2, pc.max ?? 70, ([zc, sx, sy, d, h1, ix, iz]) => {
          if (h1 > (pc.amt ?? 0.6)) return;
          const h2 = hash2(ix * 0.77 + 5, iz * 1.37), r = lerp(sz[0], sz[1], h2) * cam.f / zc, sq = clamp(ch / Math.hypot(ch, d), 0.03, 1);
          g.fillStyle = sc.col(cols[Math.floor(h2 * 7.3) % cols.length], d);
          g.globalAlpha = (pc.alpha ?? 0.7) * clamp(1.4 - d / (pc.max ?? 70) * 1.4);
          g.beginPath(); g.ellipse(sx, sy, r * (0.8 + 0.6 * hash2(ix, iz * 3.1)), Math.max(0.6, r * sq), 0, 0, 7); g.fill();
        });
        g.globalAlpha = 1;
      }
      if (o.water) {
        // light lying on still water: short bright streaks that slide and wink
        const wc = o.water;
        each(wc.cell ?? 2.5, wc.max ?? 120, ([zc, sx, sy, d, h1, ix, iz]) => {
          const ph = t * (0.5 + h1) + ix * 1.7 + iz * 0.9, a = 0.5 + 0.5 * Math.sin(ph);
          if (a < 0.35) return;
          const w = (0.6 + 1.8 * h1) * cam.f / zc, hh = Math.max(0.7, 0.05 * cam.f / zc * clamp(ch / d, 0.05, 1) * 6);
          g.fillStyle = wc.color ?? '#ffffff'; g.globalAlpha = (wc.alpha ?? 0.35) * (a - 0.35) * 1.5 * clamp(1.3 - d / (wc.max ?? 120));
          g.fillRect(sx - w / 2 + Math.sin(ph * 0.7) * w * 0.3, sy, w, hh);
        });
        g.globalAlpha = 1;
      }
      if (o.tuft) {
        const tc = o.tuft, cols = tc.colors, hs = tc.h ?? [0.1, 0.28];
        each(tc.cell ?? 0.75, tc.max ?? 16, ([zc, sx, sy, d, h1, ix, iz, wx, wz]) => {
          if (h1 > (tc.amt ?? 0.7)) return;
          const h2 = hash2(ix * 0.37 + 9, iz * 1.11), hp = lerp(hs[0], hs[1], h2) * cam.f / zc, w = Math.max(0.8, hp * 0.16);
          const lean = (noise1(t * 1.1 + wx * 0.45 + wz * 0.2, 3) * 0.3 + (tc.lean ?? 0.12)) * hp;
          g.fillStyle = sc.col(cols[Math.floor(h2 * 5.9) % cols.length], d);
          g.beginPath();
          for (let b = -1; b <= 1; b++) { const bx = sx + b * w * 1.5, tipx = bx + lean * (1 + b * 0.35) + b * hp * 0.22, tipy = sy - hp * (1 - 0.22 * Math.abs(b)); g.moveTo(bx - w, sy); g.lineTo(tipx, tipy); g.lineTo(bx + w, sy); g.closePath(); }
          g.fill();
          if (tc.flower && h2 > 1 - tc.flower.amt) { g.fillStyle = sc.col(tc.flower.colors[Math.floor(h1 * 97) % tc.flower.colors.length], d); g.beginPath(); g.arc(sx + lean, sy - hp, Math.max(1, hp * 0.16), 0, 7); g.fill(); }
        });
      }
    },
  };
}

/** A flat shape lying on the ground (road, yard, field, pond): o: { pts: [[x, z]…], y, color, edge, far (colour far away), stripes } */
export function slab(o) {
  const y = o.y ?? 0.004, pts = o.pts.map(([x, z]) => [x, y, z]);
  let cx = 0, cz = 0; for (const p of pts) { cx += p[0]; cz += p[2]; } cx /= pts.length; cz /= pts.length;
  return {
    p: [cx, y, cz], bias: o.bias ?? 0, r: 1e6,
    draw(g, cam, sc) {
      if (cam.pos[1] < y) return;
      const s = clipPoly(cam, pts); if (s.length < 3) return;
      const d = V.dist(cam.pos, [cx, y, cz]);
      g.beginPath(); g.moveTo(s[0][0], s[0][1]); for (let i = 1; i < s.length; i++) g.lineTo(s[i][0], s[i][1]); g.closePath();
      if (o.far) { const ys = s.map((q) => q[1]), y0 = Math.min(...ys), y1 = Math.max(...ys), gr = g.createLinearGradient(0, y0, 0, Math.max(y0 + 1, y1)); gr.addColorStop(0, sc.col(o.far, d * 1.5)); gr.addColorStop(1, sc.col(o.color, Math.max(0, d * 0.5))); g.fillStyle = gr; } else g.fillStyle = o.raw ?? sc.col(o.color, d);
      if (o.alpha !== undefined) { g.globalAlpha = o.alpha; g.fill(); g.globalAlpha = 1; } else g.fill();
      if (o.edge) { g.lineWidth = clamp(cam.f / Math.max(1, d) * 0.03, 0.8, 3); g.strokeStyle = sc.col(o.edge, d); g.stroke(); }
      if (o.detail) { g.save(); g.clip(); o.detail(g, cam, sc, d); g.restore(); }
    },
  };
}

/* -------------------------------------------------------------------------- solids */

/** A built thing made of flat faces. faces: [{ p: [[x,y,z]…], c, detail(g, F), two, ink, k }]. Lit in three tones by the sun. */
export class Mesh {
  constructor(faces, o = {}) {
    this.faces = faces; this.o = o; this.bias = o.bias ?? 0; this.zone = o.zone;
    let cx = 0, cy = 0, cz = 0, n = 0;
    for (const f of faces) {
      const a = f.p[0], b = f.p[1], c = f.p[2];
      f.n = V.norm(V.cross(V.sub(b, a), V.sub(c, a)));
      f.m = [0, 0, 0]; for (const q of f.p) { f.m[0] += q[0]; f.m[1] += q[1]; f.m[2] += q[2]; cx += q[0]; cy += q[1]; cz += q[2]; n++; }
      f.m = V.mul(f.m, 1 / f.p.length);
    }
    this.p = o.p ?? [cx / n, 0, cz / n];
    let r = 0; for (const f of faces) for (const q of f.p) r = Math.max(r, V.dist(q, this.p)); this.r = r;
    void cy;
  }
  draw(g, cam, sc) {
    const vis = [];
    for (const f of this.faces) {
      const facing = V.dot(f.n, V.sub(cam.pos, f.m));
      if (facing <= 0 && !f.two) continue;
      const z = cam.toCam(f.m)[2]; if (z < -this.r) continue;
      vis.push([z + (f.k ?? 0), f, facing > 0 ? 1 : -1]);
    }
    vis.sort((a, b) => b[0] - a[0]);
    for (const [z, f, side] of vis) {
      const s = clipPoly(cam, f.p); if (s.length < 3) continue;
      const d = Math.max(0.5, V.dist(cam.pos, f.m)), l = V.dot(f.n, sc.sun) * side;
      const tone = f.flat ? f.c : l > 0.3 ? f.c : l > -0.12 ? shadeHex(f.c, -0.13) : shadeHex(f.c, -0.3);
      g.beginPath(); g.moveTo(s[0][0], s[0][1]); for (let i = 1; i < s.length; i++) g.lineTo(s[i][0], s[i][1]); g.closePath();
      const fill = sc.col(tone, d);
      g.fillStyle = fill; g.fill();
      const lw = clamp(cam.f / d * 0.018, 0, 2.2);
      if (f.ink !== false && lw > 0.35) { g.lineJoin = 'round'; g.lineWidth = lw; g.strokeStyle = sc.col(f.ink ?? shadeHex(tone, -0.45), d); g.stroke(); } else { g.lineWidth = 0.8; g.strokeStyle = fill; g.stroke(); }
      if (f.detail && cam.f / d > (f.lod ?? 6)) {
        // a point of the face by its own two edges: uv(0,0) = first corner, u along the first edge, v along the last
        const o0 = f.p[0], eu = V.sub(f.p[1], o0), ev = V.sub(f.p[f.p.length - 1], o0);
        const F = { uv: (u, v) => cam.project([o0[0] + eu[0] * u + ev[0] * v, o0[1] + eu[1] * u + ev[1] * v, o0[2] + eu[2] * u + ev[2] * v]), proj: (p) => cam.project(p), w: V.len(eu), h: V.len(ev), d, lw: Math.max(0.5, lw), col: (c) => sc.col(l > 0.3 ? c : l > -0.12 ? shadeHex(c, -0.13) : shadeHex(c, -0.3), d), tone, px: cam.f / d };
        if (s.every((q) => q[2] > 0.3)) { g.save(); g.clip(); f.detail(g, F); g.restore(); }
      }
    }
  }
}

/* helpers to build faces */
const rot = (p, yaw, o) => { const c = Math.cos(yaw), s = Math.sin(yaw); return [o[0] + p[0] * c + p[2] * s, o[1] + p[1], o[2] - p[0] * s + p[2] * c]; };
/** A box standing on `at` ([x, y, z] of the middle of its base): w along x, d along z, h up; yaw in degrees. cols: { side, top, front, back, left, right } */
export function box(at, w, h, d, yawDeg = 0, cols = {}, det = {}) {
  const yaw = yawDeg * Math.PI / 180, hw = w / 2, hd = d / 2, P = (x, y, z) => rot([x, y, z], yaw, at);
  const c = (k) => cols[k] ?? cols.side ?? '#999999';
  const f = [
    { p: [P(-hw, 0, hd), P(hw, 0, hd), P(hw, h, hd), P(-hw, h, hd)], c: c('front'), detail: det.front ?? det.side, tag: 'front' },
    { p: [P(hw, 0, -hd), P(-hw, 0, -hd), P(-hw, h, -hd), P(hw, h, -hd)], c: c('back'), detail: det.back ?? det.side, tag: 'back' },
    { p: [P(hw, 0, hd), P(hw, 0, -hd), P(hw, h, -hd), P(hw, h, hd)], c: c('right'), detail: det.right ?? det.side, tag: 'right' },
    { p: [P(-hw, 0, -hd), P(-hw, 0, hd), P(-hw, h, hd), P(-hw, h, -hd)], c: c('left'), detail: det.left ?? det.side, tag: 'left' },
  ];
  if (cols.top !== null) f.push({ p: [P(-hw, h, hd), P(hw, h, hd), P(hw, h, -hd), P(-hw, h, -hd)], c: cols.top ?? c('side'), detail: det.top, tag: 'top' });
  if (cols.bottom) f.push({ p: [P(-hw, 0, -hd), P(hw, 0, -hd), P(hw, 0, hd), P(-hw, 0, hd)], c: cols.bottom, tag: 'bottom' });
  return f;
}
/** A pitched roof over a w × d rectangle whose eaves are at height y: ridge runs along x. o: { rise, over (overhang), color, gable, under } */
export function gable(at, w, d, yawDeg, o) {
  const yaw = yawDeg * Math.PI / 180, ov = o.over ?? 0.5, hw = w / 2 + ov, hd = d / 2 + ov, rise = o.rise ?? d * 0.45, P = (x, y, z) => rot([x, y, z], yaw, at);
  const sag = o.sag ?? 0; // eaves that sweep up at the ends (temple roofs)
  const e = (x, z) => P(x, (Math.abs(x) / hw) ** 2 * sag, z);
  const f = [
    { p: [e(-hw, hd), e(hw, hd), P(hw * (o.hip ? 0.55 : 1), rise, 0), P(-hw * (o.hip ? 0.55 : 1), rise, 0)], c: o.color, detail: o.detail, tag: 'roof' },
    { p: [e(hw, -hd), e(-hw, -hd), P(-hw * (o.hip ? 0.55 : 1), rise, 0), P(hw * (o.hip ? 0.55 : 1), rise, 0)], c: o.color, detail: o.detail, tag: 'roof' },
    { p: [e(hw, hd), e(hw, -hd), P(hw * (o.hip ? 0.55 : 1), rise, 0)], c: o.hip ? o.color : (o.gable ?? o.color), tag: 'gable' },
    { p: [e(-hw, -hd), e(-hw, hd), P(-hw * (o.hip ? 0.55 : 1), rise, 0)], c: o.hip ? o.color : (o.gable ?? o.color), tag: 'gable' },
  ];
  if (o.under) f.push({ p: [e(-hw, -hd), e(hw, -hd), e(hw, hd), e(-hw, hd)], c: o.under, tag: 'under', ink: false });
  return f;
}
/** An upright post or column as a slim box. */
export const post = (at, r, h, color, yaw = 0) => box(at, r * 2, h, r * 2, yaw, { side: color });
/** A flat panel between two ground points, h tall from y0 (a wall segment, a fence, a banner). two-sided. */
export function panel(a, b, y0, h, color, o = {}) {
  return [{ p: [[a[0], y0, a[1]], [b[0], y0, b[1]], [b[0], y0 + h, b[1]], [a[0], y0 + h, a[1]]], c: color, two: true, detail: o.detail, ink: o.ink, flat: o.flat }];
}

/* ---- surface details: called with (g, F); F.uv(u, v) maps the face's own 0..1 square to the picture */
const seg = (g, F, u0, v0, u1, v1) => { const a = F.uv(u0, v0), b = F.uv(u1, v1); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); };
const quad = (g, F, u0, v0, u1, v1) => { const a = F.uv(u0, v0), b = F.uv(u1, v0), c = F.uv(u1, v1), d = F.uv(u0, v1); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.lineTo(c[0], c[1]); g.lineTo(d[0], d[1]); g.closePath(); };
export const DET = {
  /** boards: lines every `step` metres across (dir 'v': upright boards) */
  planks: (step = 0.3, dir = 'v', k = -0.28) => (g, F) => {
    g.beginPath(); const n = Math.max(1, Math.round((dir === 'v' ? F.w : F.h) / step));
    if (n > 60 || F.px * step < 3) return;
    for (let i = 1; i < n; i++) dir === 'v' ? seg(g, F, i / n, 0, i / n, 1) : seg(g, F, 0, i / n, 1, i / n);
    g.lineWidth = F.lw * 0.7; g.strokeStyle = F.col(shadeHex(F.tone, k)); g.stroke();
  },
  /** courses of stone: rows with staggered joints */
  stone: (rowH = 0.45, len = 0.9, k = -0.25) => (g, F) => {
    const rows = Math.max(1, Math.round(F.h / rowH)), cols = Math.max(1, Math.round(F.w / len));
    if (F.px * rowH < 3.5 || rows * cols > 900) return;
    g.beginPath();
    for (let r = 1; r < rows; r++) seg(g, F, 0, r / rows, 1, r / rows);
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) { const u = (c + (r % 2 ? 0.5 : 0) + 0.12 * (hash2(r, c) - 0.5)) / cols; if (u <= 0.01 || u >= 0.99) continue; seg(g, F, u, r / rows, u, (r + 1) / rows); }
    g.lineWidth = F.lw * 0.6; g.strokeStyle = F.col(shadeHex(F.tone, k)); g.stroke();
  },
  /** thatch or shingles: rows across the slope with a broken edge */
  thatch: (rowH = 0.5, k = -0.2) => (g, F) => {
    const rows = Math.max(2, Math.round(F.h / rowH)); if (F.px * rowH < 3) return;
    g.beginPath();
    for (let r = 1; r < rows; r++) { const n = Math.max(4, Math.round(F.w / 0.7)); for (let i = 0; i < n; i++) { const u0 = i / n, u1 = (i + 1) / n, dv = (hash2(r * 3.1, i * 1.7) - 0.5) * 0.35 / rows; seg(g, F, u0, r / rows + dv, u1, r / rows + dv * 0.3); } }
    g.lineWidth = F.lw * 0.8; g.strokeStyle = F.col(shadeHex(F.tone, k)); g.stroke();
  },
  /** roof tiles: rows and columns */
  tiles: (rowH = 0.4, colW = 0.35, k = -0.25) => (g, F) => {
    const rows = Math.max(2, Math.round(F.h / rowH)), cols = Math.max(2, Math.round(F.w / colW)); if (F.px * rowH < 3) return;
    g.beginPath();
    for (let r = 1; r < rows; r++) seg(g, F, 0, r / rows, 1, r / rows);
    if (F.px * colW > 4) for (let c = 1; c < cols; c++) seg(g, F, c / cols, 0, c / cols, 1);
    g.lineWidth = F.lw * 0.6; g.strokeStyle = F.col(shadeHex(F.tone, k)); g.stroke();
  },
  /** a list of marks: [kind, u0, v0, u1, v1, colour] — 'rect' filled, 'frame' outlined, 'line' */
  marks: (list) => (g, F) => {
    for (const [kind, u0, v0, u1, v1, c, extra] of list) {
      g.beginPath();
      if (kind === 'line') { seg(g, F, u0, v0, u1, v1); g.lineWidth = F.lw * (extra ?? 1); g.strokeStyle = F.col(c); g.stroke(); continue; }
      quad(g, F, u0, v0, u1, v1);
      if (kind === 'rect') { g.fillStyle = F.col(c); g.fill(); if (extra) { g.lineWidth = F.lw; g.strokeStyle = F.col(extra); g.stroke(); } }
      else if (kind === 'glow') { g.fillStyle = c; g.fill(); }
      else { g.lineWidth = F.lw * 1.2; g.strokeStyle = F.col(c); g.stroke(); }
    }
  },
  /** several details in order */
  all: (...fs) => (g, F) => { for (const f of fs) f(g, F); },
  /** a paper screen: a grid of thin bars */
  lattice: (nu, nv, c) => (g, F) => { if (F.px < 14) return; g.beginPath(); for (let i = 1; i < nu; i++) seg(g, F, i / nu, 0, i / nu, 1); for (let i = 1; i < nv; i++) seg(g, F, 0, i / nv, 1, i / nv); g.lineWidth = F.lw * 0.8; g.strokeStyle = F.col(c); g.stroke(); },
};

/* -------------------------------------------------------------------------- plants */

function blob(g, x, y, r, seed, n = 9) {
  const pts = [];
  for (let i = 0; i < n; i++) { const a = (i / n) * Math.PI * 2, rr = r * (0.84 + 0.3 * hash(i * 2.3 + seed)); pts.push([x + Math.cos(a) * rr, y + Math.sin(a) * rr * 0.92]); }
  smoothClosed(g, pts, 1);
}
/**
 * A tree. o: { p: [x, y, z], h, kind: 'leaf' | 'pine' | 'sakura' | 'bare' | 'bush', seed, leaf, leafLit, leafShade, trunk, snow }
 */
export function tree(o) {
  const seed = o.seed ?? 1, h = o.h ?? 6, kind = o.kind ?? 'leaf', p = o.p;
  const lobes = [];
  if (kind !== 'pine' && kind !== 'bare') {
    const n = kind === 'bush' ? 5 : (o.n ?? 9);
    for (let i = 0; i < n; i++) {
      const a = hash(i * 3.7 + seed) * 6.28, rr = Math.sqrt(hash(i * 1.9 + seed * 2)) * (kind === 'bush' ? 0.5 : 0.34) * h * (o.spread ?? 1), yy = kind === 'bush' ? 0.35 + 0.3 * hash(i * 5.3 + seed) : 0.52 + 0.4 * hash(i * 5.3 + seed);
      lobes.push([Math.cos(a) * rr, yy * h, Math.sin(a) * rr, (kind === 'bush' ? 0.3 : 0.2) * h * (0.75 + 0.5 * hash(i * 7.1 + seed)), i]);
    }
  }
  return {
    p, r: h,
    draw(g, cam, sc, t, z) {
      const b = cam.project(p); if (b[2] < 0.3) return;
      const d = V.dist(cam.pos, p), ppm = cam.f / b[2], L = sc.light2(cam);
      if (b[0] < -h * ppm || b[0] > cam.W + h * ppm) return;
      const sway = sc.wind ? noise1(t * 0.6 + p[0] * 0.13 + p[2] * 0.07, seed) * 0.012 * h : 0;
      const trunk = sc.col(o.trunk ?? '#5b4636', d), trunkSh = sc.col(shadeHex(o.trunk ?? '#5b4636', -0.3), d);
      const top = cam.project([p[0] + sway, p[1] + h * (kind === 'pine' ? 0.98 : kind === 'bush' ? 0.3 : 0.62), p[2]]);
      const tw = (kind === 'bush' ? 0.02 : 0.045) * h * ppm * (o.thick ?? 1);
      if (kind !== 'bush') {
        g.beginPath(); g.moveTo(b[0] - tw * 1.5, b[1]); g.quadraticCurveTo(b[0] - tw * 0.7, (b[1] + top[1]) / 2, top[0] - tw * 0.35, top[1]); g.lineTo(top[0] + tw * 0.35, top[1]); g.quadraticCurveTo(b[0] + tw * 0.7, (b[1] + top[1]) / 2, b[0] + tw * 1.5, b[1]); g.closePath();
        g.fillStyle = trunk; g.fill();
        g.save(); g.clip(); g.fillStyle = trunkSh; g.fillRect(b[0] - (L[0] < 0 ? -tw * 0.15 : tw * 2), top[1], tw * 2, b[1] - top[1]); g.restore();
      }
      if (kind === 'bare' || kind === 'sakura') {
        // branches
        g.strokeStyle = trunk; g.lineCap = 'round';
        const nb = kind === 'bare' ? 9 : 6;
        for (let i = 0; i < nb; i++) { const a = hash(i * 2.9 + seed) * 6.28, u = 0.45 + 0.5 * hash(i * 4.1 + seed), len = (0.22 + 0.2 * hash(i * 6.3 + seed)) * h; const s0 = cam.project([p[0] + sway * u, p[1] + h * 0.62 * u, p[2]]), s1 = cam.project([p[0] + Math.cos(a) * len + sway, p[1] + h * (0.62 * u + 0.22 + 0.16 * hash(i + seed)), p[2] + Math.sin(a) * len]); g.lineWidth = Math.max(0.8, tw * 0.5 * (1.1 - u)); g.beginPath(); g.moveTo(s0[0], s0[1]); g.quadraticCurveTo((s0[0] + s1[0]) / 2, s0[1] - (s0[1] - s1[1]) * 0.25, s1[0], s1[1]); g.stroke(); }
      }
      if (kind === 'pine') {
        const tiers = o.tiers ?? 6, leaf = sc.col(o.leaf ?? '#2f5a45', d), sh = sc.col(o.leafShade ?? shadeHex(o.leaf ?? '#2f5a45', -0.3), d), snow = o.snow ? sc.col(o.snow, d) : null;
        for (let i = 0; i < tiers; i++) {
          const u = i / tiers, y0 = h * (0.16 + 0.8 * u), y1 = h * (0.16 + 0.8 * (u + 1.45 / tiers)), w = h * 0.3 * (1 - u * 0.82) * (o.spread ?? 1);
          const a = cam.project([p[0] + sway * u, p[1] + y0, p[2]]), c = cam.project([p[0] + sway * (u + 0.2), p[1] + Math.min(h * 1.02, y1), p[2]]), wp = w * ppm;
          const jag = (k) => (hash(i * 3.1 + k * 1.7 + seed) - 0.5) * wp * 0.2;
          g.beginPath(); g.moveTo(c[0], c[1]); g.lineTo(a[0] + wp + jag(1), a[1] + jag(2) * 0.4); g.lineTo(a[0] + wp * 0.45, a[1] - wp * 0.12); g.lineTo(a[0] + jag(3), a[1] + wp * 0.08); g.lineTo(a[0] - wp * 0.45, a[1] - wp * 0.12); g.lineTo(a[0] - wp + jag(4), a[1] + jag(5) * 0.4); g.closePath();
          g.fillStyle = leaf; g.fill();
          g.save(); g.clip();
          g.fillStyle = sh; g.beginPath(); g.moveTo(c[0], c[1]); g.lineTo(a[0] + (L[0] < 0 ? wp : -wp) * 1.1, a[1] + wp * 0.2); g.lineTo(a[0] + (L[0] < 0 ? 0.1 : -0.1) * wp, a[1] + wp * 0.2); g.closePath(); g.fill();
          if (snow) { g.fillStyle = snow; g.beginPath(); g.moveTo(c[0], c[1] - 2); g.lineTo(a[0] + wp * 0.75, a[1] - wp * 0.32); g.lineTo(a[0] + wp * 0.3, a[1] - wp * 0.4); g.lineTo(a[0], a[1] - wp * 0.25); g.lineTo(a[0] - wp * 0.35, a[1] - wp * 0.42); g.lineTo(a[0] - wp * 0.8, a[1] - wp * 0.3); g.closePath(); g.fill(); }
          g.restore();
        }
        return;
      }
      if (!lobes.length) return;
      const leaf = sc.col(o.leaf ?? '#4f8a3c', d), lit = sc.col(o.leafLit ?? shadeHex(o.leaf ?? '#4f8a3c', 0.22), d), sh = sc.col(o.leafShade ?? shadeHex(o.leaf ?? '#4f8a3c', -0.28), d);
      const all = lobes.map((q) => { const s = cam.project([p[0] + q[0] + sway * (q[1] / h) * 1.5, p[1] + q[1], p[2] + q[2]]); return [s[2], s[0], s[1], q[3] * cam.f / Math.max(0.3, s[2]), q[4]]; });
      const ls = all.filter((q) => q[0] > 0.3).sort((a, b) => b[0] - a[0]); // a clump at or behind the lens plane would project as a smear
      if (ls.length < all.length && globalThis.__cullLog) globalThis.__cullLog('tree', p, cam, null, t);
      if (!ls.length) return;
      // the crown as one shape in shade, then each clump's lit side
      g.beginPath(); for (const q of ls) blob(g, q[1], q[2], q[3], q[4] + seed); g.fillStyle = sh; g.fill();
      for (const q of ls) { g.beginPath(); blob(g, q[1] + L[0] * q[3] * 0.16, q[2] + L[1] * q[3] * 0.16, q[3] * 0.86, q[4] + seed + 3); g.fillStyle = leaf; g.fill(); }
      if (ppm * h > 60) for (const q of ls) { g.beginPath(); blob(g, q[1] + L[0] * q[3] * 0.42, q[2] + L[1] * q[3] * 0.42, q[3] * 0.48, q[4] + seed + 7, 7); g.fillStyle = lit; g.fill(); }
      void z;
    },
  };
}

/** A rock: o: { p, r, h, seed, color } */
export function rock(o) {
  const seed = o.seed ?? 1, n = 8, sh = [];
  for (let i = 0; i < n; i++) { const a = Math.PI + (i / (n - 1)) * Math.PI; sh.push([Math.cos(a) * (0.8 + 0.4 * hash(i * 1.7 + seed)), -Math.sin(a) * (0.55 + 0.5 * hash(i * 3.3 + seed))]); }
  return {
    p: o.p, r: o.r,
    draw(g, cam, sc) {
      const b = cam.project(o.p); if (b[2] < 0.3) return;
      const d = V.dist(cam.pos, o.p), k = cam.f / b[2], rx = o.r * k, ry = (o.h ?? o.r * 0.8) * k, L = sc.light2(cam);
      const pts = sh.map(([x, y]) => [b[0] + x * rx, b[1] + y * ry]);
      g.beginPath(); poly(g, pts); g.fillStyle = sc.col(shadeHex(o.color ?? '#8a8f98', -0.25), d); g.fill();
      g.save(); g.clip(); g.beginPath(); poly(g, pts.map((q) => [q[0] + L[0] * rx * 0.22, q[1] + L[1] * ry * 0.3])); g.fillStyle = sc.col(o.color ?? '#8a8f98', d); g.fill();
      if (o.snow) { g.beginPath(); poly(g, pts.map((q) => [q[0], q[1] - ry * 0.55])); g.fillStyle = sc.col(o.snow, d); g.fill(); }
      g.restore();
      const lw = clamp(k * 0.014, 0, 2); if (lw > 0.4) { g.beginPath(); poly(g, pts); g.lineWidth = lw; g.lineJoin = 'round'; g.strokeStyle = sc.col(shadeHex(o.color ?? '#8a8f98', -0.55), d); g.stroke(); }
    },
  };
}

/** A banner on a pole, alive in the wind: o: { p, h, w, len, color, trim, emblem, pole, seed, dir } */
export function banner(o) {
  const seed = o.seed ?? 1;
  return {
    p: o.p, r: o.h,
    draw(g, cam, sc, t) {
      const b = cam.project(o.p); if (b[2] < 0.3) return;
      const d = V.dist(cam.pos, o.p), k = cam.f / b[2], top = cam.project([o.p[0], o.p[1] + o.h, o.p[2]]);
      const pw = Math.max(1, 0.035 * k);
      g.lineCap = 'round'; g.strokeStyle = sc.col(o.pole ?? '#3c3028', d); g.lineWidth = pw * 2; g.beginPath(); g.moveTo(b[0], b[1]); g.lineTo(top[0], top[1]); g.stroke();
      // the cloth: a strip from the pole that ripples away down wind
      const n = 8, len = (o.len ?? 1.6), wd = (o.w ?? 2.6), dir = o.dir ?? [1, 0, 0.2], upper = [], lower = [];
      const gust = sc.wind ? 0.75 + 0.25 * noise1(t * 0.5, seed) : 0.5;
      for (let i = 0; i <= n; i++) {
        const u = i / n, wave = Math.sin(t * 5.2 * gust - u * 5.5 + seed) * 0.16 * u * len + Math.sin(t * 2.3 + u * 2.1 + seed * 2) * 0.1 * u * len;
        const droop = (1 - gust) * u * u * wd * 0.5;
        const x = o.p[0] + dir[0] * u * len * gust + wave * dir[2], z = o.p[2] + dir[2] * u * len * gust - wave * dir[0], y = o.p[1] + o.h - 0.05 - droop;
        upper.push(cam.project([x, y + wave * 0.25, z])); lower.push(cam.project([x, y - wd * (1 - 0.06 * u) + wave * 0.4, z]));
      }
      if (upper.some((q) => q[2] < 0.3) || lower.some((q) => q[2] < 0.3)) { if (globalThis.__cullLog) globalThis.__cullLog('banner', o.p, cam, null, t); return; } // cloth that reaches the lens plane would project as a streak
      g.beginPath(); smoothOpen(g, upper, 1); smoothOpen(g, lower.reverse(), 1, false); g.closePath(); lower.reverse();
      g.fillStyle = sc.col(o.color, d); g.fill();
      if (k * wd > 14) {
        g.save(); g.clip();
        // folds: darker bands where the cloth turns away
        for (let i = 1; i < n; i += 2) { g.beginPath(); g.moveTo(upper[i][0], upper[i][1]); g.lineTo(upper[i + 1][0], upper[i + 1][1]); g.lineTo(lower[i + 1][0], lower[i + 1][1]); g.lineTo(lower[i][0], lower[i][1]); g.closePath(); g.fillStyle = sc.col(shadeHex(o.color, -0.22), d); g.fill(); }
        if (o.trim) { g.beginPath(); smoothOpen(g, lower, 1); g.lineWidth = Math.max(1.5, 0.22 * k); g.strokeStyle = sc.col(o.trim, d); g.stroke(); }
        if (o.emblem) { const m = Math.floor(n * 0.5), cx = (upper[m][0] + lower[m][0]) / 2, cy = (upper[m][1] + lower[m][1]) / 2, r = Math.abs(lower[m][1] - upper[m][1]) * 0.26; o.emblem(g, cx, cy, r, sc.col(o.trim ?? '#e9d9a8', d)); }
        g.restore();
      }
      const lw = clamp(k * 0.012, 0, 1.8); if (lw > 0.4) { g.beginPath(); smoothOpen(g, upper, 1); smoothOpen(g, [...lower].reverse(), 1, false); g.closePath(); g.lineWidth = lw; g.lineJoin = 'round'; g.strokeStyle = sc.col(shadeHex(o.color, -0.55), d); g.stroke(); }
    },
  };
}

/** A soft sheet of mist or smoke lying in the air: o: { p, w, h, color, alpha, drift: [x, z] (m/s), seed } */
export function mist(o) {
  return {
    p: o.p, r: o.w, bias: o.bias ?? 0, zone: o.zone,
    draw(g, cam, sc, t) {
      const q = [o.p[0] + (o.drift?.[0] ?? 0) * t + Math.sin(t * 0.13 + (o.seed ?? 0)) * 2, o.p[1], o.p[2] + (o.drift?.[1] ?? 0) * t];
      const s = cam.project(q); if (s[2] < 1) return;
      const k = cam.f / s[2], rx = o.w * k, ry = o.h * k;
      if (s[0] + rx < 0 || s[0] - rx > cam.W) return;
      const gr = g.createRadialGradient(s[0], s[1], 0, s[0], s[1], rx);
      gr.addColorStop(0, o.color); gr.addColorStop(0.55, rgba(hex(o.color), 0.5)); gr.addColorStop(1, rgba(hex(o.color), 0));
      g.save(); g.globalAlpha = (o.alpha ?? 0.5) * clamp(s[2] / 6) * (0.85 + 0.15 * Math.sin(t * 0.4 + (o.seed ?? 0) * 3)); g.translate(s[0], s[1]); g.scale(1, ry / rx); g.translate(-s[0], -s[1]);
      g.fillStyle = gr; g.fillRect(s[0] - rx, s[1] - rx, rx * 2, rx * 2); g.restore();
    },
  };
}
export { rot };
