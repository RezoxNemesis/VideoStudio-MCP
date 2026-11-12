// The 2D library inside films: stickers and effects over a shot, 2.5D backgrounds whose painted layers slide with the
// camera, and cut-out characters (rigs of parts) that walk, gesture, blink and speak their lines.
import fs from 'node:fs';
import path from 'node:path';
import { Image, createCanvas } from '@napi-rs/canvas';
import { Scene } from '../engine/scene.js';
import { V, clamp } from '../engine/core.js';
import { mouthAt } from './acting.js';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const DIRS = () => [process.env.VS_LIBRARY2D, path.join(HERE, '..', 'library2d', 'out')].filter(Boolean);
let IDX = null, ROOT = null;
/** The library's index: { count, cats, assets: [{ id, name, cat, sub, tags, w, h, file, layers?, parts?, … }] } */
export function library2d() {
  if (IDX) return IDX;
  for (const d of DIRS()) { const f = path.join(d, 'index.json'); if (fs.existsSync(f)) { ROOT = d; IDX = JSON.parse(fs.readFileSync(f, 'utf8')); IDX.byId = new Map(IDX.assets.map((a) => [a.id, a])); return IDX; } }
  throw new Error('The 2D library is not built here (node film/library2d/build.mjs).');
}
export const asset2d = (id) => { const a = library2d().byId.get(id); if (!a) throw new Error(`No 2D asset ${id}. Find ids with code.assets or library2d().assets.`); return a; };
const imgs = new Map();
/** A picture of the library, decoded once (SVG → bitmap at its own size × scale), cropped to what is drawn. */
let bytes = 0;
const BUDGET = 900e6;
/** Scales come in steps of a quarter octave, so a zoom redraws a picture a handful of times, not on every frame. */
export const qscale = (s) => Math.pow(2, Math.round(Math.log2(Math.max(0.05, s)) * 4) / 4);
export function image2d(file, scale = 1) {
  scale = qscale(scale);
  const key = file + '@' + scale; let r = imgs.get(key); if (r) { imgs.delete(key); imgs.set(key, r); return r; }
  library2d();
  const im = new Image(); im.src = fs.readFileSync(path.join(ROOT, file));
  const w = Math.max(1, Math.round(im.width * scale)), h = Math.max(1, Math.round(im.height * scale)), cv = createCanvas(w, h), g = cv.getContext('2d'); g.drawImage(im, 0, 0, w, h);
  // the box that holds anything (most parts of a rig are a small piece of a large page)
  const d = g.getImageData(0, 0, w, h).data; let x0 = w, y0 = h, x1 = -1, y1 = -1;
  for (let y = 0; y < h; y += 2) for (let x = 0; x < w; x += 2) if (d[(y * w + x) * 4 + 3] > 8) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  if (x1 < 0) { x0 = 0; y0 = 0; x1 = 1; y1 = 1; }
  x0 = Math.max(0, x0 - 3); y0 = Math.max(0, y0 - 3); x1 = Math.min(w - 1, x1 + 3); y1 = Math.min(h - 1, y1 + 3);
  const cw = x1 - x0 + 1, chh = y1 - y0 + 1, c2 = createCanvas(cw, chh); c2.getContext('2d').drawImage(cv, -x0, -y0);
  r = { cv: c2, x0: x0 / scale, y0: y0 / scale, w: cw / scale, h: chh / scale, scale, full: [im.width, im.height] };
  imgs.set(key, r); bytes += cw * chh * 4;
  while (bytes > BUDGET && imgs.size > 1) { const [k0, v0] = imgs.entries().next().value; imgs.delete(k0); bytes -= v0.cv.width * v0.cv.height * 4; }
  return r;
}
/** Draw an asset with its anchor (default its centre; props stand on their base) at picture position x, y. o: { w | h | scale (px of the 1080p frame), rot (deg), alpha, blend, flip, anchor } */
export function drawAsset(g, id, x, y, o = {}) {
  const a = asset2d(id), k = g.canvas ? g.canvas.height / 1080 : 1;
  const s = (o.w ? o.w / a.w : o.h ? o.h / a.h : o.scale ?? 1) * k;
  const im = image2d(a.file, Math.min(2, Math.max(0.12, s * 1.15)));
  const an = o.anchor ?? a.anchor ?? [0.5, 0.5];
  g.save(); g.translate(x, y); if (o.rot) g.rotate((o.rot * Math.PI) / 180); g.scale((o.flip ? -1 : 1) * s, s);
  if (o.alpha != null) g.globalAlpha *= clamp(o.alpha); if (o.blend ?? a.blend) g.globalCompositeOperation = blendOf(o.blend ?? a.blend);
  g.drawImage(im.cv, im.x0 - a.w * an[0], im.y0 - a.h * an[1], im.w, im.h);
  g.restore();
}
const blendOf = (b) => ({ screen: 'screen', multiply: 'multiply', add: 'lighter', lighten: 'lighten', overlay: 'overlay' })[b] ?? 'source-over';

/**
 * A sticker or effect over the shot — use as `over` or `top`, or inside them. o: { x, y (0…1 of the picture) | at: [x, y, z]
 * (a point in the world) with size (metres tall), w | h | scale, rot, flip, blend, t0, t1, fade (s), anim: (u, t) → { dx, dy, scale, rot, alpha } }
 */
export function sticker(id, o = {}) {
  return (g, cam, sc, t) => {
    if (o.t0 != null && t < o.t0) return; if (o.t1 != null && t > o.t1) return;
    const span = o.t1 != null && o.t0 != null ? o.t1 - o.t0 : null, u = o.t0 != null ? t - o.t0 : t, fd = o.fade ?? 0;
    let alpha = o.alpha ?? 1; if (fd && o.t0 != null) alpha *= clamp(u / fd); if (fd && o.t1 != null) alpha *= clamp((o.t1 - t) / fd);
    const an = o.anim ? o.anim(u, t, span) ?? {} : {};
    let x, y, scale = o.scale ?? 1, w = o.w, h = o.h;
    if (o.at) { const p = typeof o.at === 'function' ? o.at(t) : o.at, c = cam.toCam(p); if (c[2] < 0.2) return; const s = cam.project(p); x = s[0]; y = s[1]; if (o.size) { h = (o.size * cam.f) / c[2] * (1080 / cam.H); w = undefined; } }
    else { x = (o.x ?? 0.5) * cam.W; y = (o.y ?? 0.5) * cam.H; if (w && w <= 4) w *= 1920; if (h && h <= 4) h *= 1080; }
    drawAsset(g, id, x + (an.dx ?? 0) * cam.W, y + (an.dy ?? 0) * cam.H, { w: w && (an.scale ?? 1) * w, h: h && (an.scale ?? 1) * h, scale: (an.scale ?? 1) * scale, rot: (o.rot ?? 0) + (an.rot ?? 0), flip: o.flip, blend: o.blend, alpha: alpha * (an.alpha ?? 1), anchor: o.anchor });
  };
}
/** An effect that pops: grows past its size, settles, then fades (impacts, sparks, emotion marks). */
export function pop(id, t0, o = {}) {
  const dur = o.dur ?? 0.5;
  return sticker(id, { ...o, t0, t1: t0 + dur, anim: (u) => { const k = u < 0.07 ? 0.4 + (u / 0.07) * 0.8 : u < 0.14 ? 1.2 - ((u - 0.07) / 0.07) * 0.2 : 1; return { scale: k * (o.grow ? 1 + u * o.grow : 1), rot: (o.spin ?? 0) * u * 360, alpha: u > dur * 0.6 ? 1 - (u - dur * 0.6) / (dur * 0.4) : 1 }; } });
}
/** Several stickers / effects as one over() (drawn in order). */
export const layered = (...fns) => (g, cam, sc, t, ctx) => { for (const f of fns) if (f) f(g, cam, sc, t, ctx); };

/**
 * A 2.5D place from the library: its painted layers stand as cards at their depths in front of a reference camera, so
 * they slide past each other when the camera moves (truck, pan, dolly) — the multiplane camera. → a Scene for set().
 * o: { ref: { pos, target, fov } (the view the layers fill; default eye height looking along +z), scale (overscan of a
 * card, 1.06), depthScale, sun, wind, grade }. The scene also has stand(x, y, h, layer): where a character stands on a
 * layer — x, y (0…1 of the reference picture: where its feet are) and h (its height as a share of the picture height) →
 * { at, height } for cutoutActor, so it moves with the ground it stands on when the camera moves.
 */
export function parallax(bgId, o = {}) {
  const a = asset2d(bgId); if (!a.layers) throw new Error(`${bgId} is not a layered background.`);
  const ref = o.ref ?? { pos: [0, 1.6, -8], target: [0, 1.6, 0], fov: 38 };
  const F = V.norm(V.sub(ref.target, ref.pos)), R = V.norm(V.cross(F, [0, 1, 0])), U = V.cross(R, F), tanH = Math.tan(((ref.fov ?? 38) * Math.PI) / 360), aspect = o.aspect ?? 16 / 9;
  const sc = new Scene({ fog: { color: '#ffffff', dist: 1e9, max: 0 }, sun: o.sun ?? [-0.45, 0.7, 0.5], wind: o.wind ?? null, grade: o.grade ?? {} });
  const depthOf = (L) => L.depth * (o.depthScale ?? 1);
  const card = (L) => {
    const D = depthOf(L), hh = D * tanH * (o.scale ?? 1.06), hw = hh * (a.w / a.h), C = V.add(ref.pos, V.mul(F, D));
    return {
      p: C, r: hw * 2,
      draw(g, cam) {
        // a card and its mirror images to each side (a mirrored copy always meets the card without a seam), so a long truck never runs off the painting
        const k0 = Math.floor(-(o.tiles ?? 2)), k1 = o.tiles ?? 2;
        for (let k = k0; k <= k1; k++) {
          const off = V.mul(R, k * hw * 2), mir = k % 2 !== 0;
          const TL = V.add(V.add(V.sub(C, V.mul(R, hw)), V.mul(U, hh)), off), TR = V.add(V.add(V.add(C, V.mul(R, hw)), V.mul(U, hh)), off), BL = V.add(V.sub(V.sub(C, V.mul(R, hw)), V.mul(U, hh)), off);
          const c0 = cam.toCam(TL), c1 = cam.toCam(TR), c2 = cam.toCam(BL); if (c0[2] < 0.2 || c1[2] < 0.2 || c2[2] < 0.2) continue;
          const p0 = cam.project(TL), p1 = cam.project(TR), p2 = cam.project(BL);
          const xs = [p0[0], p1[0], p2[0], p1[0] + p2[0] - p0[0]], ys = [p0[1], p1[1], p2[1], p1[1] + p2[1] - p0[1]];
          if (Math.max(...xs) < 0 || Math.min(...xs) > cam.W || Math.max(...ys) < 0 || Math.min(...ys) > cam.H) continue;
          const scr = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]) / a.w, im = image2d(L.file, Math.min(1.5, Math.max(0.12, scr * 1.1)));
          g.save(); g.transform((p1[0] - p0[0]) / a.w, (p1[1] - p0[1]) / a.w, (p2[0] - p0[0]) / a.h, (p2[1] - p0[1]) / a.h, p0[0], p0[1]);
          if (mir) { g.translate(a.w, 0); g.scale(-1, 1); }
          g.drawImage(im.cv, im.x0, im.y0, im.w, im.h); g.restore();
        }
      },
    };
  };
  for (const L of a.layers) {
    const it = card(L);
    if (L.name === 'sky') sc.add('sky', it); else if (L.name === 'far' || L.name === 'middle') sc.add('far', it); else if (L.name === 'near') sc.add('ground', it); else sc.add('front', it);
  }
  sc.stand = (x, y, h = 0.5, layer = 'near') => standOn(bgId, x, y, h, layer, o);
  sc.ref = ref; sc.asset = a;
  return sc;
}

/**
 * Where a character stands on a layer of a library background (the same as parallax(bg).stand, without the scene):
 * x, y — where its feet are in the reference picture (0…1); h — its height as a share of the picture → { at, height }.
 */
export function standOn(bgId, x, y, h = 0.5, layer = 'near', o = {}) {
  const a = asset2d(bgId), ref = o.ref ?? { pos: [0, 1.6, -8], target: [0, 1.6, 0], fov: 38 };
  const F = V.norm(V.sub(ref.target, ref.pos)), R = V.norm(V.cross(F, [0, 1, 0])), U = V.cross(R, F), tanH = Math.tan(((ref.fov ?? 38) * Math.PI) / 360), aspect = o.aspect ?? 16 / 9;
  const L = (a.layers ?? []).find((q) => q.name === layer) ?? { depth: 15 }, D = L.depth * (o.depthScale ?? 1);
  const d = V.add(V.add(V.mul(R, (x - 0.5) * aspect * 2 * tanH), V.mul(U, -(y - 0.5) * 2 * tanH)), F);
  return { at: V.add(ref.pos, V.mul(d, D)), height: h * 2 * tanH * D };
}

/**
 * Painted distance round a 3D set: the far layers of a library background (mountains, a skyline, a tree line) stand
 * as a ring of cards far out round the place, mirrored card to card so they meet without a seam, the art's horizon at
 * eye height — the way anime puts painted backgrounds behind its 3D sets. → the same Scene.
 * o: { layers: ['far', 'middle'], center: [x, z], radius: { far: 320, middle: 110 }, cards: 8, turn (deg), eye (m), horizon (0…1 of the art) }
 */
export function backdrop(sc, bgId, o = {}) {
  const a = asset2d(bgId); if (!a.layers) throw new Error(`${bgId} is not a layered background.`);
  const names = o.layers ?? ['far', 'middle'], N = Math.max(4, o.cards ?? 8), c = o.center ?? [0, 0], eye = o.eye ?? 1.6, hz = o.horizon ?? 0.6, turn = ((o.turn ?? 0) * Math.PI) / 180;
  const RAD = { sky: 900, far: 320, middle: 110, near: 45, ...(o.radius ?? {}) };
  for (const name of names) {
    const L = a.layers.find((q) => q.name === name); if (!L) continue;
    const R = RAD[name] ?? 200, half = Math.PI / N, W = 2 * R * Math.tan(half), Hc = W * (a.h / a.w) * (o.stretch ?? 1), yb = eye - Hc * (1 - hz), strips = 10;
    const item = {
      p: [c[0], 0, c[1]], depth: R,
      draw(g, cam) {
        for (let k = 0; k < N; k++) {
          const ang = turn + (k / N) * Math.PI * 2, n = [Math.sin(ang), 0, Math.cos(ang)], t = [Math.cos(ang), 0, -Math.sin(ang)], mid = [c[0] + n[0] * R, 0, c[1] + n[2] * R];
          if (V.dot(V.norm(V.sub(mid, cam.pos)), cam.F) < 0.05) continue; // behind the lens
          let im = null; const mir = k % 2 === 1;
          for (let sIdx = 0; sIdx < strips; sIdx++) {
            const u0 = sIdx / strips, u1 = (sIdx + 1) / strips;
            const P = (u, y) => [mid[0] + t[0] * (u - 0.5) * W, y, mid[2] + t[2] * (u - 0.5) * W];
            const TL = P(u0, yb + Hc), TR = P(u1, yb + Hc), BL = P(u0, yb);
            const z0 = cam.toCam(TL)[2], z1 = cam.toCam(TR)[2]; if (z0 < 1 || z1 < 1) continue;
            const p0 = cam.project(TL), p1 = cam.project(TR), p2 = cam.project(BL);
            const xs = Math.min(p0[0], p1[0], p2[0]), xe = Math.max(p0[0], p1[0], p2[0]); if (xe < -50 || xs > cam.W + 50) continue;
            if (!im) { const scr = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]) * strips / a.w; im = image2d(L.file, Math.min(1.2, Math.max(0.15, scr * 1.1))); }
            // the strip of the art this piece of the card shows (mirrored cards read the art backwards)
            const a0 = (mir ? 1 - u1 : u0) * a.w, a1 = (mir ? 1 - u0 : u1) * a.w, sx0 = Math.max(a0, im.x0), sx1 = Math.min(a1, im.x0 + im.w); if (sx1 <= sx0) continue;
            const sw = a1 - a0;
            g.save(); g.transform((p1[0] - p0[0]) / sw, (p1[1] - p0[1]) / sw, (p2[0] - p0[0]) / a.h, (p2[1] - p0[1]) / a.h, p0[0], p0[1]);
            if (mir) { g.translate(sw, 0); g.scale(-1, 1); }
            const k2 = im.cv.width / im.w; g.drawImage(im.cv, (sx0 - im.x0) * k2, 0, (sx1 - sx0) * k2, im.cv.height, sx0 - a0 - 0.5, im.y0, sx1 - sx0 + 1, im.h);
            g.restore();
          }
        }
      },
    };
    // the painted distance goes behind everything the set already has far away (nearest ring last)
    const layer = sc.layers[name === 'sky' ? 'sky' : 'far'], at = layer.findIndex((it) => it._ring && it._ring < R);
    item._ring = R; if (at < 0) { const firstSet = layer.findIndex((it) => !it._ring); layer.splice(firstSet < 0 ? layer.length : firstSet, 0, item); } else layer.splice(at, 0, item);
  }
  return sc;
}

// ---------------------------------------------------------------- cut-out characters
const ORDER_FALLBACK = ['hair_back', 'thigh_R', 'shin_R', 'foot_R', 'upperarm_R', 'forearm_R', 'hand_R', 'thigh_L', 'shin_L', 'foot_L', 'torso', 'skirt', 'upperarm_L', 'forearm_L', 'hand_L', 'head', 'eyes', 'mouth', 'hair_front'];
/**
 * Draw a cut-out rig. pose: { rot: { part: degrees }, sy / sx: { part: stretch } (a leg lifted towards the lens looks
 * shorter: sy 0.85 on its thigh), eyes: 'open' | 'half' | 'closed' | 'happy' | 'angry' | 'sad' | 'surprised',
 * mouth: 'rest' | 'A' | 'E' | 'I' | 'O' | 'U' | 'M' | 'F' | 'smile' | 'grin' | 'frown' | 'shout', bob (px), lean (deg), squash }.
 * x, y: where the feet stand in the picture; h: height in picture px (1080p frame); o.flip: face the other way.
 */
export function drawCutout(g, id, x, y, h, pose = {}, o = {}) {
  const a = asset2d(id); if (!a.parts) throw new Error(`${id} is not a cut-out rig.`);
  const k = g.canvas ? g.canvas.height / 1080 : 1, top = a.rig.build === 'chibi' ? 70 : 60, s = (h * k) / (a.rig.ground - top), parts = a.parts, rot = pose.rot ?? {}, SY = pose.sy ?? {}, SX = pose.sx ?? {}, Z = pose.z ?? {};
  // every part: `bone` turns its children, `full` also stretches the part itself (a stretch is not passed down: a
  // shortened thigh lifts the shin, it does not squash it)
  const bone = new Map(), full = new Map();
  const solve = (name) => {
    if (full.has(name)) return full.get(name);
    const p = parts[name]; if (!p) return null;
    const [px, py] = p.pivot, ang = ((rot[name] ?? 0) + (name === 'torso' ? pose.lean ?? 0 : 0)) * Math.PI / 180, c = Math.cos(ang), sn = Math.sin(ang), kx = SX[name] ?? 1, ky = SY[name] ?? 1;
    let base = [1, 0, 0, 1, 0, 0];
    if (p.parent && parts[p.parent]) { const pf = solve(p.parent), pb = bone.get(p.parent), q = apply(pf, px, py); base = mul([1, 0, 0, 1, q[0], q[1]], mul([pb[0], pb[1], pb[2], pb[3], 0, 0], [1, 0, 0, 1, -px, -py])); }
    const turn = [c, sn, -sn, c, px - c * px + sn * py, py - sn * px - c * py];
    const A = c * kx, B = sn * kx, C = -sn * ky, D = c * ky, str = [A, B, C, D, px - A * px - C * py, py - B * px - D * py];
    bone.set(name, mul(base, turn)); const m = mul(base, str); full.set(name, m); return m;
  };
  const eyes = `eyes_${pose.eyes ?? 'open'}`, mouth = `mouth_${pose.mouth ?? 'rest'}`, zOf = (n) => Z[n] ?? parts[n].z;
  const list = Object.keys(parts).filter((n) => !n.startsWith('eyes_') && !n.startsWith('mouth_')).concat([parts[eyes] ? eyes : 'eyes_open', parts[mouth] ? mouth : 'mouth_rest']).filter((n) => parts[n]).sort((p, q) => zOf(p) - zOf(q) || ORDER_FALLBACK.indexOf(p) - ORDER_FALLBACK.indexOf(q));
  const sq = pose.squash ?? 0;
  g.save(); g.translate(x, y + (pose.bob ?? 0) * k); g.scale((o.flip || pose.flip ? -s : s) * (1 + sq * 0.5), s * (1 - sq)); g.translate(-300, -a.rig.ground);
  if (o.alpha != null) g.globalAlpha *= o.alpha;
  const res = Math.min(1.5, Math.max(0.12, s * 1.15));
  for (const n of list) {
    const m = solve(n.startsWith('eyes_') || n.startsWith('mouth_') ? 'head' : n); if (!m) continue;
    const im = image2d(parts[n].file, res);
    g.save(); g.transform(m[0], m[1], m[2], m[3], m[4], m[5]); g.drawImage(im.cv, im.x0, im.y0, im.w, im.h); g.restore();
  }
  g.restore();
}
const mul = (A, B) => [A[0] * B[0] + A[2] * B[1], A[1] * B[0] + A[3] * B[1], A[0] * B[2] + A[2] * B[3], A[1] * B[2] + A[3] * B[3], A[0] * B[4] + A[2] * B[5] + A[4], A[1] * B[4] + A[3] * B[5] + A[5]];
const apply = (M, x, y) => [M[0] * x + M[2] * y + M[4], M[1] * x + M[3] * y + M[5]];
/**
 * Walking, for a puppet that faces the lens: the lifted leg comes towards the camera (it looks shorter and its knee
 * bends out a little), the hips sway over the standing leg, the body rises on the passing step and the arms swing
 * against the legs. o: { rate (steps a second, 1.8), swing (1), side (true for a puppet seen from the side: legs swing) }
 */
export function walkPose(t, o = {}) {
  const f = (o.rate ?? 1.8) / 2, ph = t * f * Math.PI * 2, sw = o.swing ?? 1, s = Math.sin(ph), c = Math.cos(ph);
  const liftL = Math.max(0, s), liftR = Math.max(0, -s);
  if (o.side) return { rot: { thigh_L: 26 * s * sw, shin_L: Math.max(0, 40 * c) * sw, foot_L: -10 * s, thigh_R: -26 * s * sw, shin_R: Math.max(0, -40 * c) * sw, foot_R: 10 * s, upperarm_L: -22 * s * sw, forearm_L: -14, upperarm_R: 22 * s * sw, forearm_R: 14, torso: 3 }, bob: -7 * Math.abs(c) * sw };
  return {
    rot: { thigh_L: -5 * liftL * sw, shin_L: 9 * liftL * sw, foot_L: -6 * liftL, thigh_R: 5 * liftR * sw, shin_R: -9 * liftR * sw, foot_R: 6 * liftR, torso: 1.8 * s * sw, head: -1.4 * s * sw, upperarm_L: (-6 - 7 * s) * sw, forearm_L: -10 - 6 * liftR, upperarm_R: (6 - 7 * s) * sw, forearm_R: 10 + 6 * liftL },
    sy: { thigh_L: 1 - 0.13 * liftL * sw, shin_L: 1 - 0.05 * liftL * sw, thigh_R: 1 - 0.13 * liftR * sw, shin_R: 1 - 0.05 * liftR * sw },
    bob: -8 * Math.abs(Math.cos(ph)) * sw + 3,
  };
}
/** Standing, breathing, small sways. */
export function idlePose(t, seed = 0) { const b = Math.sin(t * 1.6 + seed); return { rot: { torso: 1.2 * b, head: 2 * Math.sin(t * 0.7 + seed * 2), upperarm_L: -4 + 2 * b, upperarm_R: 4 - 2 * b, forearm_L: -6, forearm_R: 6 }, bob: 2 * b }; }
/** Blinks now and then (a closed eye for 0.12 s every few seconds). */
export const blinkAt = (t, seed = 1) => { const p = 3.3 + (seed % 7) * 0.31, u = (t + seed * 0.77) % p; return u < 0.12 ? 'closed' : u < 0.18 ? 'half' : null; };
/** Mix two poses (u 0 → a, 1 → b): for going from one key pose to the next. */
export function mixPose(a, b, u) {
  const out = { rot: {}, sy: {}, sx: {} };
  for (const key of ['rot', 'sy', 'sx']) { const d = key === 'rot' ? 0 : 1, A = a[key] ?? {}, B = b[key] ?? {}; for (const n of new Set([...Object.keys(A), ...Object.keys(B)])) out[key][n] = (A[n] ?? d) + ((B[n] ?? d) - (A[n] ?? d)) * u; }
  for (const key of ['bob', 'lean', 'squash']) out[key] = (a[key] ?? 0) + ((b[key] ?? 0) - (a[key] ?? 0)) * u;
  out.eyes = u < 0.5 ? a.eyes : b.eyes; out.mouth = u < 0.5 ? a.mouth : b.mouth; return out;
}
/** Key poses over time: keys [[t, pose], …] → (t) => pose, eased between keys (the puppet moves pose to pose). */
export function posesOver(keys, ease = (u) => u * u * (3 - 2 * u)) {
  return (t) => { if (t <= keys[0][0]) return keys[0][1]; for (let i = 1; i < keys.length; i++) if (t < keys[i][0]) { const [t0, a] = keys[i - 1], [t1, b] = keys[i]; return mixPose(a, b, ease((t - t0) / (t1 - t0))); } return keys[keys.length - 1][1]; };
}
/**
 * Named poses for cut-out puppets. Degrees turn a part round its joint (positive = clockwise on the picture). The
 * character's right arm (_R) hangs on the picture's left, so it opens outwards with positive degrees and the left arm
 * with negative ones. z moves a part in front of or behind the body (the torso is 10, the far arm 4, the near arm 16).
 */
export const CUTOUT_POSES = {
  stand: { rot: { upperarm_R: 4, forearm_R: -6, upperarm_L: -4, forearm_L: 6 } },
  wave: { rot: { upperarm_R: 128, forearm_R: 28, hand_R: 8, upperarm_L: -4, forearm_L: 6 }, mouth: 'smile' },
  point: { rot: { upperarm_L: -84, forearm_L: -4, upperarm_R: 4, forearm_R: -6 } },
  cheer: { rot: { upperarm_R: 150, forearm_R: 16, upperarm_L: -150, forearm_L: -16 }, eyes: 'happy', mouth: 'grin', bob: -6 },
  think: { rot: { upperarm_L: -6, forearm_L: 152, hand_L: 10, upperarm_R: 4, forearm_R: -6, head: 5 }, sy: { upperarm_L: 0.58 }, eyes: 'half' },
  shrug: { rot: { upperarm_R: 14, forearm_R: 66, upperarm_L: -14, forearm_L: -66, head: -6 }, sy: { forearm_R: 0.8, forearm_L: 0.8 }, eyes: 'half', mouth: 'frown' },
  hips: { rot: { upperarm_R: 40, forearm_R: -78, upperarm_L: -40, forearm_L: 78 } },
  surprised: { rot: { upperarm_R: 34, forearm_R: 128, upperarm_L: -34, forearm_L: -128 }, sy: { upperarm_R: 0.8, upperarm_L: 0.8 }, eyes: 'surprised', mouth: 'O', squash: -0.03 },
  crossed: { rot: { upperarm_R: 8, forearm_R: -100, upperarm_L: -8, forearm_L: 100 }, sy: { upperarm_R: 0.78, upperarm_L: 0.78 }, z: { forearm_R: 17, hand_R: 18, upperarm_R: 4, forearm_L: 19, hand_L: 20 }, mouth: 'frown' },
  sad: { rot: { head: 8, upperarm_R: 2, forearm_R: -3, upperarm_L: -2, forearm_L: 3 }, eyes: 'sad', mouth: 'frown', lean: 3, bob: 6 },
  angry: { rot: { upperarm_R: 16, forearm_R: 8, upperarm_L: -16, forearm_L: -8 }, eyes: 'angry', mouth: 'shout', lean: -2, squash: 0.02 },
  run: { rot: { upperarm_R: 18, forearm_R: -72, upperarm_L: -22, forearm_L: 64, thigh_L: -6, shin_L: 14 }, sy: { forearm_R: 0.62, forearm_L: 0.62, thigh_L: 0.75 }, z: { forearm_R: 17, hand_R: 18, forearm_L: 19, hand_L: 20 }, lean: 4, bob: -4 },
};
/** The mouth shape of a speaking character at time t (from the film's lip data for `who`'s lines). */
export function mouthShape(cues, who, t, lang = 'en') {
  const m = mouthAt(cues ?? [], who, t, lang); if (!m) return 'rest';
  if (m.open < 0.07) return 'M'; if (m.open > 0.62) return 'A'; if (m.wide > 0.55) return m.open > 0.3 ? 'E' : 'I'; if (m.open > 0.35) return 'O'; return m.open > 0.18 ? 'U' : 'rest';
}
/**
 * A cut-out character as a thing of the scene — in a shot's or sequence's dyn: (t, ctx) => [cutoutActor(id, { …, t })]
 * (pass t, the film's clock, so its poses and lips follow the story): it stands at a point of the world (sorted by depth
 * with everything else) or of the picture. o: { at: [x, y, z] | (t) => …, height (metres) — or standOn(bg, x, y, h) —,
 * screen: { x, y } (0…1) with height (share of the picture), pose: (t) => pose, who + cues (lips), flip, shadow }
 */
export function cutoutActor(id, o = {}) {
  const item = {
    p: o.at ? (typeof o.at === 'function' ? o.at : () => o.at) : () => [0, 0, 5],
    draw(g, cam, sc, tDraw) {
      const t = o.t ?? tDraw; let x, y, h;
      if (o.screen) { const sp = typeof o.screen === 'function' ? o.screen(t) : o.screen; x = sp.x * cam.W; y = sp.y * cam.H; h = (o.height ?? 0.6) <= 4 ? (o.height ?? 0.6) * 1080 : o.height; }
      else { const p = item.p(t), c = cam.toCam(p); if (c[2] < 0.3) return; const s = cam.project(p); x = s[0]; y = s[1]; h = ((o.height ?? 1.7) * cam.f) / c[2] * (1080 / cam.H); if (o.shadow !== false) { g.save(); g.fillStyle = 'rgba(20,16,30,0.25)'; g.beginPath(); g.ellipse(x, y, h * 0.14 * (cam.H / 1080), h * 0.03 * (cam.H / 1080), 0, 0, Math.PI * 2); g.fill(); g.restore(); } }
      const pose = { ...(o.pose ? o.pose(t) : idlePose(t, id.length)) };
      if (!pose.eyes) pose.eyes = blinkAt(t, id.length) ?? 'open';
      if (o.who && !pose.mouth) pose.mouth = mouthShape(typeof o.cues === 'function' ? o.cues() : o.cues, o.who, t + (o.tOff ?? 0), o.lang ?? 'en');
      drawCutout(g, id, x, y, h, pose, { flip: typeof o.flip === 'function' ? o.flip(t) : o.flip });
    },
  };
  return item;
}
