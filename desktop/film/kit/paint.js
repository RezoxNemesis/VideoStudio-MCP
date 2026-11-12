// Fixes painted over a shot (Animate → Fix a film shot): strokes that follow a part of a body — or stay where they
// were drawn — and erasing that shows what is behind, put on every frame of their span when the shot is drawn.
//
// A shot's fixes live in the film's own folder, paint/<shot id>.json (they are part of the film, not of its cache):
//   { v: 1, on: true, fixes: [{ id, name, on, anchor: { kind: 'part', actor, part } | { kind: 'screen' } | { kind: 'world', p },
//     follow: 'full' | 'move+scale' | 'move', span: [t0, t1] | null (seconds in the shot), fade (s), ref: { t, ang, ppmH },
//     strokes: [Stroke], erase: [Stroke], eraseMode: 'behind' | 'fill' }] }
// Stroke points are in the anchor's own units: metres along and across the part (or shares of the picture height for
// 'screen'), so a fix keeps its place, turn and size on the part however the part moves and the camera frames it.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { createCanvas } from '@napi-rs/canvas';
import { V, clamp } from '../engine/core.js';

/* ---------------------------------------------------------------------------------------------------- the anchors */
const M = (a, b) => [a, b];
const side = (s) => ({
  [`shoulder.${s}`]: { o: `upperarm01.${s}`, a: `clavicle.${s}`, b: `upperarm01.${s}` },
  [`upperarm.${s}`]: { o: M(`upperarm01.${s}`, `lowerarm01.${s}`), a: `upperarm01.${s}`, b: `lowerarm01.${s}` },
  [`elbow.${s}`]: { o: `lowerarm01.${s}`, a: `upperarm01.${s}`, b: `lowerarm01.${s}` },
  [`forearm.${s}`]: { o: M(`lowerarm01.${s}`, `wrist.${s}`), a: `lowerarm01.${s}`, b: `wrist.${s}` },
  [`hand.${s}`]: { o: M(`wrist.${s}`, `finger3-1.${s}`), a: `wrist.${s}`, b: `finger3-1.${s}` },
  [`fingers.${s}`]: { o: `finger3-1.${s}`, a: `wrist.${s}`, b: `finger3-3.${s}:tail` },
  [`thigh.${s}`]: { o: M(`upperleg01.${s}`, `lowerleg01.${s}`), a: `upperleg01.${s}`, b: `lowerleg01.${s}` },
  [`knee.${s}`]: { o: `lowerleg01.${s}`, a: `upperleg01.${s}`, b: `lowerleg01.${s}` },
  [`shin.${s}`]: { o: M(`lowerleg01.${s}`, `foot.${s}`), a: `lowerleg01.${s}`, b: `foot.${s}` },
  [`foot.${s}`]: { o: M(`foot.${s}`, `toe2-1.${s}`), a: `foot.${s}`, b: `toe2-3.${s}:tail` },
});
/** The parts a fix can hold on to: where its origin is, and the line (a → b) that gives its turn. */
export const ANCHORS = {
  head: { o: 'head', a: 'neck03', b: 'head:tail' },
  face: { o: M('eye.L', 'eye.R'), a: 'neck03', b: 'head:tail' },
  eyes: { o: M('eye.L', 'eye.R'), a: 'eye.R', b: 'eye.L' },
  jaw: { o: 'jaw:tail', a: 'neck03', b: 'head:tail' },
  neck: { o: 'neck01', a: 'spine01', b: 'head' },
  chest: { o: 'spine02', a: 'spine03', b: 'neck01' },
  belly: { o: 'spine03', a: 'root', b: 'spine02' },
  hips: { o: 'root', a: 'root', b: 'spine03' },
  ...side('L'), ...side('R'),
};
export const ANCHOR_NAMES = Object.keys(ANCHORS);
const jointAt = (j, k) => (Array.isArray(k) ? (j[k[0]] && j[k[1]] ? V.mid(j[k[0]], j[k[1]]) : null) : j[k] ?? null);

/** Where a part is in the picture: origin x, y (px), turn (radians), pixels per metre there, how much it lies across the picture (0…1). */
export function anchorFrame(pose, cam, name) {
  const A = ANCHORS[name]; if (!A || !pose) return null;
  const o = jointAt(pose.j, A.o), a = jointAt(pose.j, A.a), b = jointAt(pose.j, A.b); if (!o || !a || !b) return null;
  const co = cam.toCam(o); if (co[2] < 0.15) return null;
  const so = cam.project(o), sa = cam.project(a), sb = cam.project(b), ppm = cam.f / co[2];
  const L3 = V.dist(a, b), L2 = Math.hypot(sb[0] - sa[0], sb[1] - sa[1]);
  return { x: so[0], y: so[1], ang: Math.atan2(sb[1] - sa[1], sb[0] - sa[0]), ppm, vis: L3 > 1e-6 ? clamp(L2 / (L3 * ppm)) : 1, z: co[2] };
}
const wrap = (a) => Math.atan2(Math.sin(a), Math.cos(a));
const sstep = (a, b, x) => { const u = clamp((x - a) / (b - a)); return u * u * (3 - 2 * u); };

/**
 * The transform of a fix at this frame: { x, y, ang, scale } (scale = pixels per anchor unit), or null when its part
 * is not in the picture. poses: actor id → pose; cam: the frame's camera; H: the picture height.
 */
export function fixTransform(fix, poses, cam, H) {
  const an = fix.anchor ?? { kind: 'screen' }, ref = fix.ref ?? {};
  if (an.kind === 'screen') return { x: 0, y: 0, ang: 0, scale: H };
  if (an.kind === 'world') { const c = cam.toCam(an.p); if (c[2] < 0.15) return null; const s = cam.project(an.p); return { x: s[0], y: s[1], ang: 0, scale: fix.follow === 'move' ? (ref.ppmH ?? 0.1) * H : cam.f / c[2] }; }
  const f = anchorFrame(poses?.[an.actor], cam, an.part); if (!f) return null;
  const refAng = ref.ang ?? f.ang;
  // a part pointing at the lens has no clear turn in the picture: the drawing keeps the turn it was drawn with
  const turn = fix.follow === 'full' || !fix.follow ? refAng + wrap(f.ang - refAng) * sstep(0.12, 0.35, f.vis) : refAng;
  const scale = fix.follow === 'move' ? (ref.ppmH ?? f.ppm / H) * H : f.ppm;
  return { x: f.x, y: f.y, ang: turn, scale };
}
/** Picture point → anchor units (for strokes drawn at a frame whose transform is tr). */
export function toLocal(tr, x, y) { const dx = x - tr.x, dy = y - tr.y, c = Math.cos(-tr.ang), s = Math.sin(-tr.ang); return [(dx * c - dy * s) / tr.scale, (dx * s + dy * c) / tr.scale]; }
/** Anchor units → picture point. */
export function toPicture(tr, u, v) { const c = Math.cos(tr.ang), s = Math.sin(tr.ang); return [tr.x + (u * c - v * s) * tr.scale, tr.y + (u * s + v * c) * tr.scale]; }
/** How much of a fix shows at t (its span and fades; 0 = not at all). */
export function fixAlpha(fix, t) {
  if (fix.on === false) return 0;
  const sp = fix.span; if (!sp) return 1;
  if (t < sp[0] - 1e-6 || t > sp[1] + 1e-6) return 0;
  const fd = fix.fade ?? 0; return fd > 0 ? clamp(Math.min((t - sp[0]) / fd, (sp[1] - t) / fd) + 0.0001) : 1;
}

/* --------------------------------------------------------------------------------------- strokes (as in Animate) */
const PRESSURE = { pen: [0.6, 0.35], ink: [1, 0.08], brush: [0.9, 0.15], pencil: [0.3, 0.6], marker: [0, 1], airbrush: [0.5, 0.5], eraser: [0.3, 0.6] };
function outline(s) {
  const p = s.pts, n = p.length / 3; if (!n) return [];
  const [amt, min] = PRESSURE[s.tool] ?? PRESSURE.ink;
  const width = (i) => { const pr = p[i * 3 + 2] ?? 0.5, tin = s.tool === 'ink' || s.tool === 'brush' ? Math.min(1, (i + 1) / 4) : 1, tout = s.tool === 'ink' || s.tool === 'brush' ? Math.min(1, (n - i) / 4) : 1; return (s.size / 2) * (1 - amt + amt * Math.max(min, pr)) * Math.min(tin, tout); };
  const left = [], right = [];
  for (let i = 0; i < n; i++) {
    const x = p[i * 3], y = p[i * 3 + 1], a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
    let dx = p[b * 3] - p[a * 3], dy = p[b * 3 + 1] - p[a * 3 + 1]; const len = Math.hypot(dx, dy) || 1; dx /= len; dy /= len;
    const w = width(i); left.push(x - dy * w, y + dx * w); right.push(x + dy * w, y - dx * w);
  }
  for (let i = right.length - 2; i >= 0; i -= 2) left.push(right[i], right[i + 1]);
  return left;
}
function tracePolygon(c, poly) {
  c.beginPath(); const n = poly.length / 2; if (n < 2) return;
  c.moveTo(poly[0], poly[1]);
  for (let i = 1; i < n; i++) { const x0 = poly[i * 2], y0 = poly[i * 2 + 1], j = (i + 1) % n; c.quadraticCurveTo(x0, y0, (x0 + poly[j * 2]) / 2, (y0 + poly[j * 2 + 1]) / 2); }
  c.closePath();
}
function traceCenter(c, p) {
  c.beginPath(); const n = p.length / 3; c.moveTo(p[0], p[1]);
  for (let i = 1; i < n - 1; i++) c.quadraticCurveTo(p[i * 3], p[i * 3 + 1], (p[i * 3] + p[(i + 1) * 3]) / 2, (p[i * 3 + 1] + p[(i + 1) * 3 + 1]) / 2);
  if (n > 1) c.lineTo(p[(n - 1) * 3], p[(n - 1) * 3 + 1]);
}
function dab(c, s, x, y, pr) {
  const r = Math.max(0.5, (s.size / 2) * (s.tool === 'airbrush' ? 1.4 : 0.5 + pr * 0.5));
  if (s.tool === 'airbrush') { const g = c.createRadialGradient(x, y, 0, x, y, r); g.addColorStop(0, s.color); g.addColorStop(1, 'rgba(0,0,0,0)'); c.fillStyle = g; c.globalAlpha = Math.min(1, c.globalAlpha) * 0.18; }
  else c.fillStyle = s.color;
  c.beginPath(); c.arc(x, y, r, 0, Math.PI * 2); c.fill();
}
/** Draw one stroke whose points are already in picture pixels (the same drawing as Animate's brushes). */
export function drawStroke(c, s) {
  const n = s.pts.length / 3; if (!n) return;
  c.save(); c.globalAlpha *= s.opacity ?? 1;
  if (s.tool === 'eraser') c.globalCompositeOperation = 'destination-out';
  if (s.closed && s.fill) { traceCenter(c, s.pts); c.closePath(); c.fillStyle = s.fill; c.fill(); if (s.size > 0) { c.lineWidth = s.size; c.lineJoin = 'round'; c.strokeStyle = s.color; c.stroke(); } c.restore(); return; }
  if (n === 1 || s.tool === 'airbrush') {
    const step = Math.max(1, s.size * 0.18); let carry = 0;
    for (let i = 0; i < n; i++) {
      const x = s.pts[i * 3], y = s.pts[i * 3 + 1], pr = s.pts[i * 3 + 2] ?? 0.5;
      if (i > 0) { const px = s.pts[(i - 1) * 3], py = s.pts[(i - 1) * 3 + 1], d = Math.hypot(x - px, y - py); for (let t = step - carry; t < d; t += step) dab(c, s, px + ((x - px) * t) / d, py + ((y - py) * t) / d, pr); carry = (carry + d) % step; }
      else dab(c, s, x, y, pr);
    }
    c.restore(); return;
  }
  if (s.tool === 'marker') { traceCenter(c, s.pts); c.lineWidth = s.size; c.lineCap = 'round'; c.lineJoin = 'round'; c.strokeStyle = s.color; c.globalAlpha *= 0.75; c.stroke(); c.restore(); return; }
  tracePolygon(c, outline(s)); c.fillStyle = s.color; if (s.tool === 'pencil') c.globalAlpha *= 0.85; c.fill(); c.restore();
}
/** A stroke in anchor units moved onto the picture with the transform tr. */
export function placeStroke(s, tr) {
  const pts = new Array(s.pts.length);
  for (let i = 0; i < s.pts.length; i += 3) { const q = toPicture(tr, s.pts[i], s.pts[i + 1]); pts[i] = q[0]; pts[i + 1] = q[1]; pts[i + 2] = s.pts[i + 2]; }
  return { ...s, pts, size: s.size * tr.scale };
}

/* ----------------------------------------------------------------------------------------------- erasing, filling */
/**
 * Fill the masked pixels of a region from the pixels around them (push-pull: averages that skip the masked pixels,
 * made coarser until every hole is covered, then brought back down) — a smooth fill for what has no "behind".
 */
export function inpaint(img, mask, w, h) {
  let L = { w, h, col: new Float32Array(w * h * 3), wt: new Float32Array(w * h) };
  for (let i = 0; i < w * h; i++) { L.wt[i] = mask[i] > 127 ? 0 : 1; L.col[i * 3] = img[i * 4]; L.col[i * 3 + 1] = img[i * 4 + 1]; L.col[i * 3 + 2] = img[i * 4 + 2]; }
  const levels = [L];
  // push: each coarser level averages only the known pixels under it
  while (L.w > 1 || L.h > 1) {
    const nw = Math.max(1, Math.ceil(L.w / 2)), nh = Math.max(1, Math.ceil(L.h / 2)), N = { w: nw, h: nh, col: new Float32Array(nw * nh * 3), wt: new Float32Array(nw * nh) }, sum = new Float32Array(nw * nh);
    for (let y = 0; y < L.h; y++) for (let x = 0; x < L.w; x++) { const i = y * L.w + x, a = L.wt[i]; if (a <= 0) continue; const j = (y >> 1) * nw + (x >> 1); sum[j] += a; N.col[j * 3] += L.col[i * 3] * a; N.col[j * 3 + 1] += L.col[i * 3 + 1] * a; N.col[j * 3 + 2] += L.col[i * 3 + 2] * a; }
    for (let j = 0; j < nw * nh; j++) if (sum[j] > 0) { N.col[j * 3] /= sum[j]; N.col[j * 3 + 1] /= sum[j]; N.col[j * 3 + 2] /= sum[j]; N.wt[j] = Math.min(1, sum[j]); }
    levels.push(N); L = N;
  }
  // pull: what a level does not know it takes from the coarser one, sampled smoothly (bilinear)
  for (let k = levels.length - 2; k >= 0; k--) {
    const A = levels[k], C = levels[k + 1];
    for (let y = 0; y < A.h; y++) for (let x = 0; x < A.w; x++) {
      const i = y * A.w + x, a = A.wt[i]; if (a >= 1) continue;
      const fx = Math.min(C.w - 1, Math.max(0, (x + 0.5) / 2 - 0.5)), fy = Math.min(C.h - 1, Math.max(0, (y + 0.5) / 2 - 0.5)), x0 = Math.floor(fx), y0 = Math.floor(fy), x1 = Math.min(C.w - 1, x0 + 1), y1 = Math.min(C.h - 1, y0 + 1), u = fx - x0, v = fy - y0;
      for (let c = 0; c < 3; c++) {
        const q = C.col[(y0 * C.w + x0) * 3 + c] * (1 - u) * (1 - v) + C.col[(y0 * C.w + x1) * 3 + c] * u * (1 - v) + C.col[(y1 * C.w + x0) * 3 + c] * (1 - u) * v + C.col[(y1 * C.w + x1) * 3 + c] * u * v;
        A.col[i * 3 + c] = A.col[i * 3 + c] * a + q * (1 - a);
      }
      A.wt[i] = 1;
    }
  }
  const F = levels[0];
  for (let i = 0; i < w * h; i++) if (mask[i] > 127) { img[i * 4] = F.col[i * 3]; img[i * 4 + 1] = F.col[i * 3 + 1]; img[i * 4 + 2] = F.col[i * 3 + 2]; }
}

/* ------------------------------------------------------------------------------------------ applying, reading */
let scratch = null, scratch2 = null;
const canvasOf = (c, W, H) => (c && c.width === W && c.height === H ? c : createCanvas(W, H));
/**
 * Paint a shot's fixes over the frame in g. run: the ShotRun (its poses, its world drawing for erasing); fr: { cam, cp,
 * t, dyn, dof } of this frame. Erasing comes first (what is behind the part is drawn again without it, or the hole is
 * filled from around it), then the strokes.
 */
export function applyPaint(run, g, fr) {
  const data = run.shot.paint; if (!data || data.on === false || !data.fixes?.length) return;
  const { W, H } = run, poses = run.ctx.poses;
  for (const fix of data.fixes) {
    const alpha = fixAlpha(fix, fr.t); if (alpha <= 0) continue;
    const tr = fixTransform(fix, poses, fr.cam, H); if (!tr) continue;
    if (fix.erase?.length) {
      scratch = canvasOf(scratch, W, H); const mg = scratch.getContext('2d');
      mg.setTransform(1, 0, 0, 1, 0, 0); mg.globalCompositeOperation = 'source-over'; mg.globalAlpha = 1; mg.clearRect(0, 0, W, H);
      for (const s of fix.erase) drawStroke(mg, { ...placeStroke(s, tr), tool: s.tool === 'eraser' ? 'brush' : s.tool, color: '#ffffff', opacity: 1 });
      if ((fix.eraseMode ?? 'behind') === 'behind' && fix.anchor?.actor) {
        // the picture as it would be without this person, kept only where the mask is
        scratch2 = canvasOf(scratch2, W, H); const bg = scratch2.getContext('2d');
        bg.setTransform(1, 0, 0, 1, 0, 0); bg.clearRect(0, 0, W, H);
        run.drawWorld(bg, fr.cam, fr.cp, fr.t, fr.dyn.filter((it) => it.actor !== fix.anchor.actor), fr.dof);
        mg.globalCompositeOperation = 'source-in'; mg.drawImage(scratch2, 0, 0); mg.globalCompositeOperation = 'source-over';
        g.save(); g.globalAlpha = alpha; g.drawImage(scratch, 0, 0); g.restore();
      } else {
        // fill from around: only the box round the mask is worked on
        const md = mg.getImageData(0, 0, W, H).data; let x0 = W, y0 = H, x1 = -1, y1 = -1;
        for (let y = 0; y < H; y += 2) for (let x = 0; x < W; x += 2) if (md[(y * W + x) * 4 + 3] > 20) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
        if (x1 >= 0) {
          const pad = Math.round(H * 0.03); x0 = Math.max(0, x0 - pad); y0 = Math.max(0, y0 - pad); x1 = Math.min(W - 1, x1 + pad); y1 = Math.min(H - 1, y1 + pad);
          const w = x1 - x0 + 1, h = y1 - y0 + 1, id = g.getImageData(x0, y0, w, h), mk = new Uint8Array(w * h), mr = mg.getImageData(x0, y0, w, h).data;
          for (let i = 0; i < w * h; i++) mk[i] = mr[i * 4 + 3];
          const orig = alpha < 1 ? new Uint8ClampedArray(id.data) : null;
          inpaint(id.data, mk, w, h);
          if (orig) for (let i = 0; i < w * h; i++) if (mk[i] > 127) for (let c = 0; c < 3; c++) id.data[i * 4 + c] = orig[i * 4 + c] + (id.data[i * 4 + c] - orig[i * 4 + c]) * alpha;
          g.putImageData(id, x0, y0);
        }
      }
    }
    if (fix.strokes?.length) {
      // the fix's own layer: its rubbing-out strokes take away only from its own drawing
      scratch = canvasOf(scratch, W, H); const lg = scratch.getContext('2d');
      lg.setTransform(1, 0, 0, 1, 0, 0); lg.globalCompositeOperation = 'source-over'; lg.globalAlpha = 1; lg.clearRect(0, 0, W, H);
      for (const s of fix.strokes) drawStroke(lg, placeStroke(s, tr));
      g.save(); g.globalAlpha = alpha; g.drawImage(scratch, 0, 0); g.restore();
    }
  }
}

export const paintFile = (dir, shotId) => path.join(dir, 'paint', `${String(shotId).replace(/[^\w.-]/g, '_')}.json`);
/** The fixes of a shot (null if it has none). */
export function readPaint(dir, shotId) {
  try { const d = JSON.parse(fs.readFileSync(paintFile(dir, shotId), 'utf8')); return d && Array.isArray(d.fixes) ? d : null; } catch { return null; }
}
const num = (x, d = 0) => (Number.isFinite(+x) ? +x : d);
const cleanStroke = (s) => ({ id: String(s.id ?? crypto.randomUUID().slice(0, 8)), tool: ['pen', 'pencil', 'ink', 'marker', 'airbrush', 'eraser', 'brush'].includes(s.tool) ? s.tool : 'ink', color: /^#[0-9a-f]{3,8}$/i.test(s.color ?? '') ? s.color : '#111111', size: Math.max(0, num(s.size, 0.01)), opacity: clamp(num(s.opacity, 1)), pts: (Array.isArray(s.pts) ? s.pts : []).slice(0, 30000).map((v) => +num(v).toFixed(5)), ...(s.fill && /^#[0-9a-f]{3,8}$/i.test(s.fill) ? { fill: s.fill, closed: true } : {}) });
/** Check and keep a shot's fixes (nothing but numbers, colours and known words is written). */
export function writePaint(dir, shotId, data) {
  const fixes = (data?.fixes ?? []).slice(0, 200).map((f) => {
    const an = f.anchor ?? { kind: 'screen' }, anchor = an.kind === 'part' && ANCHORS[an.part] ? { kind: 'part', actor: String(an.actor), part: an.part } : an.kind === 'world' && Array.isArray(an.p) ? { kind: 'world', p: an.p.slice(0, 3).map((v) => num(v)) } : { kind: 'screen' };
    return {
      id: String(f.id ?? crypto.randomUUID().slice(0, 8)).replace(/[^\w-]/g, ''), name: String(f.name ?? 'Fix').slice(0, 80), on: f.on !== false, anchor,
      follow: ['full', 'move+scale', 'move'].includes(f.follow) ? f.follow : 'full', span: Array.isArray(f.span) ? [num(f.span[0]), num(f.span[1])] : null, fade: Math.max(0, num(f.fade, 0)),
      ref: f.ref ? { t: num(f.ref.t), ang: num(f.ref.ang), ppmH: num(f.ref.ppmH, 0.1) } : undefined,
      strokes: (f.strokes ?? []).slice(0, 2000).map(cleanStroke), erase: (f.erase ?? []).slice(0, 500).map(cleanStroke), eraseMode: f.eraseMode === 'fill' ? 'fill' : 'behind',
      note: f.note ? String(f.note).slice(0, 400) : undefined,
    };
  });
  const out = { v: 1, on: data?.on !== false, fixes }, file = paintFile(dir, shotId);
  if (!fixes.length) { fs.rmSync(file, { force: true }); return null; }
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(out));
  return out;
}
/** What of a shot's fixes changes its picture (for its fingerprint): nothing when they are all off. */
export function paintKey(data) {
  if (!data || data.on === false) return '';
  const live = data.fixes.filter((f) => f.on !== false); if (!live.length) return '';
  return crypto.createHash('md5').update(JSON.stringify(live.map((f) => ({ ...f, name: undefined, note: undefined })))).digest('hex');
}
