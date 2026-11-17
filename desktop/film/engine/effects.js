// EFFECTS: what can be done to the whole picture after it is drawn — colour grades, bloom, lens flares, film and video
// looks, shakes, impact frames, rain on the lens, dust in the light, comic halftone and more.
//   post: { fx: [{ type: 'bloom', amt: 0.5 }, { type: 'flare', x: 0.8, y: 0.2 }, { type: 'shake', t0: 1.2, t1: 1.6, amt: 14 }] }
// Each effect may carry t0 / t1 (seconds into the shot: when it is on; `ramp` seconds to ease in and out).
// Order matters: grades first, then light (bloom, flare, rays), then lens and film (chroma, vhs, grain-like), then shakes.
import { createCanvas } from '@napi-rs/canvas';
import { clamp, hash } from './core.js';

export const EFFECT_TYPES = ['grade', 'bloom', 'glow', 'chroma', 'vhs', 'scanlines', 'halftone', 'oldFilm', 'lightLeak', 'flare', 'rays', 'letterbox', 'shake', 'impact', 'flash', 'zoomBlur', 'motes', 'rainLens', 'sparkle', 'focusLines', 'heartbeat', 'glitch', 'pixelate', 'duotone', 'fog', 'shockwave', 'tiltShift'];
/** Colour grades by name: a CSS filter, then washes laid over (colour, alpha, blend). */
export const GRADES = {
  tealOrange: { f: 'saturate(1.15) contrast(1.08)', w: [['#00b4c8', 0.16, 'soft-light'], ['#ff8a3a', 0.12, 'overlay']], split: ['#0a5a78', '#ffb070'] },
  warm: { f: 'saturate(1.08) sepia(0.12) brightness(1.03)', w: [['#ffb46a', 0.14, 'soft-light']] },
  cool: { f: 'saturate(0.95) hue-rotate(-6deg)', w: [['#5a8aff', 0.16, 'soft-light']] },
  noir: { f: 'grayscale(1) contrast(1.45) brightness(0.92)', w: [] },
  sepia: { f: 'sepia(0.85) contrast(1.05) brightness(0.98)', w: [['#7a4a1a', 0.1, 'multiply']] },
  vibrant: { f: 'saturate(1.45) contrast(1.06)', w: [] },
  faded: { f: 'saturate(0.7) contrast(0.86) brightness(1.06)', w: [['#f2e6d8', 0.12, 'screen']] },
  night: { f: 'saturate(0.7) brightness(0.72) contrast(1.1)', w: [['#1a2a6a', 0.35, 'multiply'], ['#5a7aff', 0.12, 'screen']] },
  golden: { f: 'saturate(1.2) sepia(0.2) brightness(1.04)', w: [['#ffb23a', 0.2, 'soft-light'], ['#ff7a3a', 0.08, 'overlay']] },
  bleach: { f: 'saturate(0.45) contrast(1.35) brightness(1.04)', w: [] },
  matrix: { f: 'grayscale(0.7) contrast(1.25) brightness(0.95)', w: [['#30ff80', 0.55, 'multiply'], ['#062a12', 0.2, 'screen']] },
  dream: { f: 'saturate(1.1) brightness(1.08) contrast(0.92)', w: [['#ffc8e8', 0.14, 'screen']] },
  anime: { f: 'saturate(1.3) contrast(1.1) brightness(1.03)', w: [['#ffe2b8', 0.08, 'soft-light']] },
  horror: { f: 'saturate(0.55) contrast(1.3) brightness(0.85) hue-rotate(-10deg)', w: [['#3a7a5a', 0.18, 'soft-light'], ['#200808', 0.2, 'multiply']] },
  memory: { f: 'sepia(0.35) saturate(0.8) contrast(0.9) brightness(1.08)', w: [['#fff0d0', 0.18, 'screen']] },
};
const ease = (x) => 1 - Math.pow(1 - clamp(x), 3);
const pool = new Map();
function scratch(name, W, H) { const key = `${name}:${Math.round(W)}x${Math.round(H)}`; let c = pool.get(key); if (!c) { c = createCanvas(Math.max(1, Math.round(W)), Math.max(1, Math.round(H))); pool.set(key, c); } return c; }
/** the picture as it is now, copied (an effect that redraws the picture needs it) */
function copy(g, W, H, name = 'fxcopy') { const c = scratch(name, W, H), cg = c.getContext('2d'); cg.clearRect(0, 0, W, H); cg.drawImage(g.canvas, 0, 0); return c; }
/** a small, blurred copy of the picture (cheap glow): s = scale, r = blur radius at that scale */
function soft(g, W, H, s = 0.25, r = 6, name = 'soft') { const w = Math.round(W * s), h = Math.round(H * s), c = scratch(name, w, h), cg = c.getContext('2d'); cg.clearRect(0, 0, w, h); cg.filter = `blur(${r}px)`; cg.drawImage(g.canvas, 0, 0, w, h); cg.filter = 'none'; return c; }

const E = {};
E.grade = (g, W, H, t, o, a) => {
  const L = typeof o.look === 'object' ? o.look : GRADES[o.look ?? 'tealOrange']; if (!L) throw new Error(`No grade "${o.look}". Grades: ${Object.keys(GRADES).join(', ')}.`);
  if (L.f) { const c = copy(g, W, H); g.save(); g.globalAlpha = a; g.filter = L.f; g.drawImage(c, 0, 0); g.restore(); }
  for (const [col, al, op] of L.w ?? []) { g.save(); g.globalCompositeOperation = op; g.globalAlpha = al * a; g.fillStyle = col; g.fillRect(0, 0, W, H); g.restore(); }
  if (L.split) { g.save(); g.globalCompositeOperation = 'soft-light'; const gr = g.createLinearGradient(0, H, 0, 0); gr.addColorStop(0, L.split[0]); gr.addColorStop(1, L.split[1]); g.globalAlpha = 0.25 * a; g.fillStyle = gr; g.fillRect(0, 0, W, H); g.restore(); }
};
E.bloom = (g, W, H, t, o, a) => { const s = soft(g, W, H, 0.25, (o.radius ?? 10) * W / 1920); g.save(); g.globalCompositeOperation = 'screen'; g.globalAlpha = (o.amt ?? 0.45) * a; g.drawImage(s, 0, 0, W, H); g.restore(); };
E.glow = (g, W, H, t, o, a) => { const s = soft(g, W, H, 0.5, (o.radius ?? 8) * W / 1920); g.save(); g.globalCompositeOperation = 'lighten'; g.globalAlpha = (o.amt ?? 0.55) * a; g.drawImage(s, 0, 0, W, H); g.restore(); };
E.chroma = (g, W, H, t, o, a) => {
  // the lens splits colours: red one way, cyan the other (o.radial: more at the edges than the middle)
  const d = (o.amt ?? 6) * a * W / 1920, c = copy(g, W, H), r = scratch('chR', W, H), b = scratch('chB', W, H);
  for (const [cv, col] of [[r, '#ff0000'], [b, '#00ffff']]) { const cg = cv.getContext('2d'); cg.globalCompositeOperation = 'source-over'; cg.clearRect(0, 0, W, H); cg.drawImage(c, 0, 0); cg.globalCompositeOperation = 'multiply'; cg.fillStyle = col; cg.fillRect(0, 0, W, H); cg.globalCompositeOperation = 'destination-in'; cg.drawImage(c, 0, 0); cg.globalCompositeOperation = 'source-over'; }
  g.save(); g.fillStyle = '#000'; g.fillRect(0, 0, W, H); g.globalCompositeOperation = 'lighter';
  if (o.radial) { const s = 1 + d / W * 2; g.save(); g.translate(W / 2, H / 2); g.scale(s, s); g.drawImage(r, -W / 2, -H / 2); g.restore(); g.save(); g.translate(W / 2, H / 2); g.scale(1 / s, 1 / s); g.drawImage(b, -W / 2, -H / 2); g.restore(); }
  else { g.drawImage(r, d, 0); g.drawImage(b, -d, 0); }
  g.restore();
};
E.scanlines = (g, W, H, t, o, a) => { g.save(); g.globalAlpha = (o.amt ?? 0.25) * a; g.fillStyle = '#000'; const step = Math.max(2, Math.round(H / (o.lines ?? 270))); for (let y = 0; y < H; y += step) g.fillRect(0, y, W, Math.max(1, step / 2)); g.restore(); };
E.vhs = (g, W, H, t, o, a) => {
  E.chroma(g, W, H, t, { amt: 4 }, a); E.scanlines(g, W, H, t, { amt: 0.18, lines: 240 }, a);
  const f = Math.floor(t * 30), band = ((t * 0.23) % 1.3) * H; g.save();
  // a tracking band rolling down, with a smear of noise in it
  const c = copy(g, W, H); g.globalAlpha = 0.85 * a; g.drawImage(c, 0, band, W, 24 * H / 1080, (hash(f) - 0.5) * 40, band, W, 24 * H / 1080);
  g.fillStyle = 'rgba(255,255,255,0.12)'; for (let i = 0; i < 60; i++) g.fillRect(hash(f * 3 + i) * W, band + hash(i + f) * 26 * H / 1080, 20 + hash(i) * 80, 1.5);
  // colour bleed and the faded top of tape
  g.globalCompositeOperation = 'soft-light'; g.globalAlpha = 0.3 * a; g.fillStyle = '#4a7aff'; g.fillRect(0, 0, W, H); g.globalCompositeOperation = 'source-over';
  if (o.stamp !== false) { g.globalAlpha = a; g.font = `${Math.round(42 * H / 1080)}px "VT323", "Press Start 2P", monospace`; g.fillStyle = '#ffffff'; g.shadowColor = '#000'; g.shadowBlur = 4; g.fillText(o.stamp ?? 'PLAY ▶', W * 0.05, H * 0.1); if (o.date) g.fillText(o.date, W * 0.05, H * 0.92); }
  g.restore();
};
E.halftone = (g, W, H, t, o, a) => {
  // comic halftone: dots in the shadows, bigger where it is darker (o.cell px, o.color, o.angle)
  const cell = (o.cell ?? 9) * H / 1080, cols = Math.ceil(W / cell), rows = Math.ceil(H / cell), sm = scratch('ht', cols, rows), sg = sm.getContext('2d'); sg.drawImage(g.canvas, 0, 0, cols, rows);
  const d = sg.getImageData(0, 0, cols, rows).data; g.save(); g.globalAlpha = (o.amt ?? 0.55) * a; g.fillStyle = o.color ?? '#1a1420'; g.beginPath();
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) { const i = (r * cols + c) * 4, lum = (d[i] * 0.3 + d[i + 1] * 0.59 + d[i + 2] * 0.11) / 255, rad = (1 - lum) * cell * 0.62; if (rad < 0.4) continue; const x = c * cell + cell / 2 + (r % 2 ? cell / 2 : 0), y = r * cell + cell / 2; g.moveTo(x + rad, y); g.arc(x, y, rad, 0, 7); }
  g.fill(); g.restore();
};
E.oldFilm = (g, W, H, t, o, a) => {
  const f = Math.floor(t * 24), c = copy(g, W, H); g.save(); g.globalAlpha = a; g.filter = `sepia(${o.sepia ?? 0.7}) contrast(1.1) brightness(${0.94 + 0.08 * hash(f)})`; g.drawImage(c, (hash(f + 2) - 0.5) * 3, (hash(f + 5) - 0.5) * 3); g.filter = 'none';
  g.strokeStyle = 'rgba(30,20,10,0.5)'; g.lineWidth = 1.2; for (let i = 0; i < 2; i++) if (hash(f * 7 + i) > 0.4) { const x = hash(f * 3 + i * 11) * W; g.beginPath(); g.moveTo(x, 0); g.lineTo(x + (hash(i + f) - 0.5) * 20, H); g.stroke(); }
  g.fillStyle = 'rgba(20,12,6,0.6)'; for (let i = 0; i < 14; i++) { if (hash(f * 13 + i) < 0.5) continue; g.beginPath(); g.arc(hash(f * 5 + i) * W, hash(f * 9 + i) * H, (1 + 3 * hash(i * f)) * H / 1080, 0, 7); g.fill(); }
  const gr = g.createRadialGradient(W / 2, H / 2, H * 0.35, W / 2, H / 2, H * 0.95); gr.addColorStop(0, 'rgba(0,0,0,0)'); gr.addColorStop(1, 'rgba(20,10,0,0.55)'); g.fillStyle = gr; g.fillRect(0, 0, W, H); g.restore();
};
E.lightLeak = (g, W, H, t, o, a) => { g.save(); g.globalCompositeOperation = 'screen'; const cols = o.colors ?? ['#ff8a3a', '#ff3a6a', '#ffd27a']; for (let i = 0; i < 3; i++) { const ph = t * (0.15 + i * 0.07) + i * 2.1 + (o.seed ?? 0), x = (0.5 + 0.6 * Math.sin(ph)) * W * (i === 1 ? 1.1 : 1), y = (0.3 + 0.4 * Math.cos(ph * 0.8)) * H, r = W * (0.35 + 0.15 * Math.sin(ph * 1.3)); const gr = g.createRadialGradient(x, y, 0, x, y, r); gr.addColorStop(0, cols[i % cols.length]); gr.addColorStop(1, 'rgba(0,0,0,0)'); g.globalAlpha = (o.amt ?? 0.35) * a * (0.7 + 0.3 * Math.sin(ph * 2)); g.fillStyle = gr; g.fillRect(0, 0, W, H); } g.restore(); };
E.flare = (g, W, H, t, o, a) => {
  // a lens flare from a bright point (o.x, o.y in frame fractions, or o.at(t) → [x, y]): a halo, a horizontal
  // anamorphic streak, and ghosts strung along the line through the middle of the frame
  const p = o.at ? o.at(t) : [o.x ?? 0.8, o.y ?? 0.2], x = p[0] * W, y = p[1] * H, k = H / 1080, s = (o.amt ?? 1) * a, col = o.color ?? '#ffd8a0';
  g.save(); g.globalCompositeOperation = 'screen';
  let gr = g.createRadialGradient(x, y, 0, x, y, 260 * k); gr.addColorStop(0, `rgba(255,255,245,${0.95 * s})`); gr.addColorStop(0.15, `rgba(255,220,160,${0.5 * s})`); gr.addColorStop(1, 'rgba(255,160,80,0)'); g.fillStyle = gr; g.fillRect(0, 0, W, H);
  if (o.streak !== false) { gr = g.createLinearGradient(x - W * 0.6, y, x + W * 0.6, y); gr.addColorStop(0, 'rgba(80,140,255,0)'); gr.addColorStop(0.5, `rgba(150,200,255,${0.7 * s})`); gr.addColorStop(1, 'rgba(80,140,255,0)'); g.fillStyle = gr; g.fillRect(x - W * 0.6, y - 3 * k, W * 1.2, 6 * k); g.globalAlpha = 0.4; g.fillRect(x - W * 0.4, y - 9 * k, W * 0.8, 18 * k); g.globalAlpha = 1; }
  const cx = W / 2, cy = H / 2, ghosts = [[0.4, 40, '#7affc8', 0.18], [0.7, 18, '#ffd27a', 0.3], [1.25, 70, '#9a7aff', 0.12], [1.6, 30, '#ff8a6a', 0.2], [2.0, 110, '#6ab4ff', 0.08]];
  for (const [f, r, c, al] of ghosts) { const gx = x + (cx - x) * f * 1.0, gy = y + (cy - y) * f; gr = g.createRadialGradient(gx, gy, 0, gx, gy, r * k); gr.addColorStop(0, c); gr.addColorStop(0.7, c); gr.addColorStop(1, 'rgba(0,0,0,0)'); g.globalAlpha = al * s; g.fillStyle = gr; g.beginPath(); g.arc(gx, gy, r * k, 0, 7); g.fill(); }
  g.globalAlpha = 0.25 * s; g.strokeStyle = col; g.lineWidth = 3 * k; g.beginPath(); g.arc(x + (cx - x) * 1.1, y + (cy - y) * 1.1, 170 * k, 0, 7); g.stroke();
  g.restore();
};
E.rays = (g, W, H, t, o, a) => { const x = (o.x ?? 0.75) * W, y = (o.y ?? 0.1) * H, n = o.n ?? 16, R = Math.hypot(W, H); g.save(); g.globalCompositeOperation = 'screen'; for (let i = 0; i < n; i++) { const ang = (o.dir ?? Math.PI * 0.6) + (hash(i * 3.1 + (o.seed ?? 1)) - 0.5) * (o.spread ?? 1.2), w = 0.01 + 0.04 * hash(i * 1.7), al = (0.08 + 0.12 * hash(i * 5.3)) * (0.7 + 0.3 * Math.sin(t * 0.8 + i)) * (o.amt ?? 1) * a; const gr = g.createRadialGradient(x, y, 0, x, y, R * 0.9); gr.addColorStop(0, `rgba(255,240,200,${al})`); gr.addColorStop(1, 'rgba(255,220,160,0)'); g.fillStyle = gr; g.beginPath(); g.moveTo(x, y); g.lineTo(x + Math.cos(ang - w) * R, y + Math.sin(ang - w) * R); g.lineTo(x + Math.cos(ang + w) * R, y + Math.sin(ang + w) * R); g.closePath(); g.fill(); } g.restore(); };
E.letterbox = (g, W, H, t, o, a) => { const ratio = o.ratio ?? 2.39, bar = Math.max(0, (H - W / ratio) / 2) * a; g.fillStyle = o.color ?? '#000'; g.fillRect(0, 0, W, bar); g.fillRect(0, H - bar, W, bar); };
E.shake = (g, W, H, t, o, a) => { const amp = (o.amt ?? 12) * a * H / 1080, f = o.freq ?? 24, n = Math.floor(t * f), u = t * f - n, dx = ((hash(n) - 0.5) * (1 - u) + (hash(n + 1) - 0.5) * u) * 2 * amp, dy = ((hash(n + 7) - 0.5) * (1 - u) + (hash(n + 8) - 0.5) * u) * 2 * amp, rot = ((hash(n + 3) - 0.5) * (1 - u) + (hash(n + 4) - 0.5) * u) * 0.012 * a * (o.roll ?? 1), s = 1 + (amp * 2.4) / Math.min(W, H) + Math.abs(rot);
  const c = copy(g, W, H); g.save(); g.translate(W / 2 + dx, H / 2 + dy); g.rotate(rot); g.scale(s, s); g.drawImage(c, -W / 2, -H / 2); g.restore(); };
E.impact = (g, W, H, t, o, a) => {
  // the anime impact frame: the picture snaps to black and white (or inverted) for a beat
  if (a < 0.5) return; const c = copy(g, W, H); g.save(); g.filter = o.invert ? 'invert(1) grayscale(1) contrast(6)' : 'grayscale(1) contrast(7) brightness(1.1)'; g.drawImage(c, 0, 0); g.filter = 'none';
  if (o.color) { g.globalCompositeOperation = 'multiply'; g.fillStyle = o.color; g.fillRect(0, 0, W, H); } g.restore();
};
E.flash = (g, W, H, t, o, a) => { g.save(); g.globalCompositeOperation = o.op ?? 'screen'; g.globalAlpha = a * (o.amt ?? 0.9); g.fillStyle = o.color ?? '#ffffff'; g.fillRect(0, 0, W, H); g.restore(); };
E.zoomBlur = (g, W, H, t, o, a) => { const c = copy(g, W, H), x = (o.x ?? 0.5) * W, y = (o.y ?? 0.5) * H, amt = (o.amt ?? 0.12) * a, n = 8; g.save(); for (let i = 1; i <= n; i++) { const s = 1 + amt * i / n; g.globalAlpha = 0.5 / i + 0.05; g.setTransform(s, 0, 0, s, x - x * s, y - y * s); g.drawImage(c, 0, 0); } g.restore(); };
E.motes = (g, W, H, t, o, a) => {
  // things floating in the air in front of the lens: kind 'motes' (dust in light), 'bokeh', 'fireflies', 'snow', 'petals', 'embers', 'ash'
  const kind = o.kind ?? 'motes', n = o.n ?? (kind === 'snow' ? 140 : kind === 'bokeh' ? 22 : 60), k = H / 1080, s = o.seed ?? 1;
  const look = { motes: ['#fff6dc', 2.5, 0.55, -0.01, 0.02], bokeh: [null, 40, 0.28, 0, 0.01], fireflies: ['#d8ff7a', 3, 0.9, 0, 0.05], snow: ['#ffffff', 4, 0.85, 0.08, 0.03], petals: ['#ffb8d0', 7, 0.9, 0.05, 0.06], embers: ['#ffa040', 3, 0.95, -0.07, 0.04], ash: ['#c8c4c0', 3, 0.6, 0.03, 0.03] }[kind] ?? ['#fff', 3, 0.5, 0, 0.02];
  g.save(); g.globalCompositeOperation = kind === 'snow' || kind === 'petals' || kind === 'ash' ? 'source-over' : 'screen';
  for (let i = 0; i < n; i++) {
    const depth = 0.4 + 0.9 * hash(i * 7.7 + s), sp = look[3] * depth, x = ((hash(i * 1.3 + s) + t * look[4] * depth * (1 + hash(i)) + Math.sin(t * 0.7 + i) * 0.01) % 1) * W, y = ((hash(i * 2.9 + s) + t * sp + 1) % 1) * H, r = look[1] * depth * k * (0.6 + 0.8 * hash(i * 4.4));
    const tw = kind === 'fireflies' ? 0.4 + 0.6 * Math.max(0, Math.sin(t * 3 + i * 1.7)) : kind === 'motes' ? 0.5 + 0.5 * Math.sin(t * 1.5 + i) : 1, al = look[2] * tw * a * (o.amt ?? 1);
    if (kind === 'bokeh') { const cols = o.colors ?? ['#ffd27a', '#ff8ab0', '#8ad0ff', '#ffffff']; g.globalAlpha = al * (0.4 + 0.6 * hash(i)); g.fillStyle = cols[i % cols.length]; g.beginPath(); g.arc(x, y, r, 0, 7); g.fill(); g.globalAlpha *= 0.6; g.lineWidth = 2 * k; g.strokeStyle = '#ffffff'; g.stroke(); continue; }
    g.globalAlpha = al; g.fillStyle = o.color ?? look[0];
    if (kind === 'petals') { g.save(); g.translate(x, y); g.rotate(t * 2 + i); g.scale(1, 0.5 + 0.4 * Math.sin(t * 3 + i)); g.beginPath(); g.ellipse(0, 0, r, r * 0.55, 0, 0, 7); g.fill(); g.restore(); continue; }
    if (kind === 'fireflies' || kind === 'embers' || kind === 'motes') { const gr = g.createRadialGradient(x, y, 0, x, y, r * 4); gr.addColorStop(0, o.color ?? look[0]); gr.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = gr; g.fillRect(x - r * 4, y - r * 4, r * 8, r * 8); continue; }
    g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
  }
  g.restore();
};
E.rainLens = (g, W, H, t, o, a) => {
  // rain on the glass: drops that sit, grow, and now and then run down leaving a trail
  const n = o.n ?? 70, k = H / 1080, s = o.seed ?? 2, c = soft(g, W, H, 0.25, 3, 'rainsoft'); g.save();
  for (let i = 0; i < n; i++) { const life = (t * (0.15 + 0.3 * hash(i * 3.3 + s)) + hash(i * 1.1 + s)) % 1, run = hash(i * 9.1 + s) > 0.7, x = hash(i * 2.2 + s) * W, y0 = hash(i * 4.7 + s) * H, y = run ? y0 + life * life * H * 0.6 : y0, r = (6 + 18 * hash(i * 5.5 + s)) * k * (run ? 0.7 : 0.4 + 0.6 * life);
    g.globalAlpha = a * (o.amt ?? 0.85); g.save(); g.beginPath(); g.ellipse(x, y, r, r * 1.15, 0, 0, 7); g.clip(); g.translate(x, y); g.scale(1, -1); g.drawImage(c, (x - r * 2.5) * 0.25, (y - r * 2.5) * 0.25, r * 1.25, r * 1.25, -r, -r * 1.15, r * 2, r * 2.3); g.restore();
    g.globalAlpha = a * 0.55; g.strokeStyle = 'rgba(10,20,40,0.55)'; g.lineWidth = 2 * k; g.beginPath(); g.ellipse(x, y, r, r * 1.15, 0, 0.15 * Math.PI, 0.85 * Math.PI); g.stroke();
    g.globalAlpha = a * 0.9; g.fillStyle = 'rgba(255,255,255,0.85)'; g.beginPath(); g.ellipse(x - r * 0.35, y - r * 0.45, r * 0.22, r * 0.14, -0.6, 0, 7); g.fill(); g.strokeStyle = 'rgba(255,255,255,0.45)'; g.lineWidth = 1.5 * k; g.beginPath(); g.arc(x, y, r * 0.85, Math.PI * 1.1, Math.PI * 1.6); g.stroke();
    if (run && life > 0.1) { g.globalAlpha = a * 0.25; g.fillStyle = 'rgba(220,235,255,0.6)'; g.fillRect(x - r * 0.25, y0, r * 0.5, y - y0); } }
  g.restore();
};
E.sparkle = (g, W, H, t, o, a) => { const n = o.n ?? 26, k = H / 1080, s = o.seed ?? 4, box = o.box ?? [0, 0, 1, 1]; g.save(); g.globalCompositeOperation = 'screen'; g.fillStyle = o.color ?? '#fffbe8'; for (let i = 0; i < n; i++) { const ph = (t * (0.8 + hash(i * 3.1 + s)) + hash(i * 7.3 + s)) % 1, tw = Math.sin(ph * Math.PI), x = (box[0] + (box[2] - box[0]) * hash(i * 1.7 + s)) * W, y = (box[1] + (box[3] - box[1]) * hash(i * 2.9 + s)) * H, r = (8 + 22 * hash(i * 4.1 + s)) * k * tw; if (r < 0.5) continue; g.globalAlpha = a * tw; g.beginPath(); g.moveTo(x, y - r); g.quadraticCurveTo(x, y, x + r, y); g.quadraticCurveTo(x, y, x, y + r); g.quadraticCurveTo(x, y, x - r, y); g.quadraticCurveTo(x, y, x, y - r); g.fill(); const gr = g.createRadialGradient(x, y, 0, x, y, r * 0.6); gr.addColorStop(0, 'rgba(255,255,255,0.9)'); gr.addColorStop(1, 'rgba(255,255,255,0)'); g.fillStyle = gr; g.fillRect(x - r, y - r, r * 2, r * 2); g.fillStyle = o.color ?? '#fffbe8'; } g.restore(); };
E.focusLines = (g, W, H, t, o, a) => { const cx = (o.x ?? 0.5) * W, cy = (o.y ?? 0.5) * H, n = o.n ?? 120, R = Math.hypot(W, H) * 0.7, clear = (o.clear ?? 0.33) * Math.min(W, H), f = Math.floor(t * (o.fps ?? 12)); g.save(); g.fillStyle = o.color ?? '#14101a'; g.globalAlpha = (o.amt ?? 0.85) * a; g.beginPath(); for (let i = 0; i < n; i++) { const ang = (i / n) * Math.PI * 2 + (hash(i * 1.3 + f) - 0.5) * 0.05, w = 0.003 + 0.01 * hash(i * 2.7 + f), r0 = clear * (1 + 0.5 * hash(i * 3.9 + f)); g.moveTo(cx + Math.cos(ang) * r0, cy + Math.sin(ang) * r0 * (H / W) * 1.6); g.lineTo(cx + Math.cos(ang - w) * R, cy + Math.sin(ang - w) * R); g.lineTo(cx + Math.cos(ang + w) * R, cy + Math.sin(ang + w) * R); g.closePath(); } g.fill(); g.restore(); };
E.heartbeat = (g, W, H, t, o, a) => { const bpm = o.bpm ?? 72, ph = (t * bpm / 60) % 1, beat = Math.max(Math.exp(-ph * 14), 0.7 * Math.exp(-Math.max(0, ph - 0.18) * 14) * (ph > 0.18 ? 1 : 0)), gr = g.createRadialGradient(W / 2, H / 2, H * 0.25, W / 2, H / 2, H * 0.9); gr.addColorStop(0, 'rgba(0,0,0,0)'); gr.addColorStop(1, o.color ?? 'rgba(170,0,10,1)'); g.save(); g.globalAlpha = (0.35 + 0.45 * beat) * a * (o.amt ?? 1); g.fillStyle = gr; g.fillRect(0, 0, W, H); g.restore(); };
E.glitch = (g, W, H, t, o, a) => { const f = Math.floor(t * 24), on = hash(f * 1.7 + (o.seed ?? 0)) < (o.rate ?? 0.18) * a; if (!on) return; const c = copy(g, W, H); g.save(); for (let i = 0; i < 8; i++) { const y = hash(f * 3 + i) * H, h = (0.01 + 0.08 * hash(i + f * 5)) * H, dx = (hash(f + i * 7) - 0.5) * W * 0.12; g.drawImage(c, 0, y, W, h, dx, y, W, h); } g.globalCompositeOperation = 'screen'; g.globalAlpha = 0.35; g.fillStyle = hash(f) > 0.5 ? '#ff0050' : '#00e5ff'; g.fillRect(0, hash(f * 9) * H, W, H * 0.05); g.restore(); };
E.pixelate = (g, W, H, t, o, a) => { const bs = Math.max(1, Math.round((o.size ?? 16) * a * H / 1080)); if (bs < 2) return; const sw = Math.ceil(W / bs), sh = Math.ceil(H / bs), s = scratch('px', sw, sh), sg = s.getContext('2d'); sg.drawImage(g.canvas, 0, 0, sw, sh); g.save(); g.imageSmoothingEnabled = false; g.drawImage(s, 0, 0, sw, sh, 0, 0, W, H); g.restore(); };
E.duotone = (g, W, H, t, o, a) => { const c = copy(g, W, H); g.save(); g.globalAlpha = a; g.filter = 'grayscale(1) contrast(1.15)'; g.drawImage(c, 0, 0); g.filter = 'none'; g.globalCompositeOperation = 'multiply'; g.fillStyle = o.light ?? '#ff6a8a'; g.fillRect(0, 0, W, H); g.globalCompositeOperation = 'screen'; g.fillStyle = o.dark ?? '#1a1060'; g.fillRect(0, 0, W, H); g.restore(); };
E.fog = (g, W, H, t, o, a) => { g.save(); const col = o.color ?? '230,232,240'; for (let i = 0; i < 4; i++) { const y = H * ((o.y ?? 0.65) + i * 0.08), x = ((t * (8 + i * 5) * W / 1920 + i * 300) % (W * 1.5)) - W * 0.25, gr = g.createRadialGradient(x, y, 0, x, y, W * 0.6); gr.addColorStop(0, `rgba(${col},${0.35 * (o.amt ?? 1) * a})`); gr.addColorStop(1, `rgba(${col},0)`); g.fillStyle = gr; g.fillRect(0, 0, W, H); const gr2 = g.createLinearGradient(0, y - H * 0.15, 0, H); gr2.addColorStop(0, `rgba(${col},0)`); gr2.addColorStop(1, `rgba(${col},${0.12 * (o.amt ?? 1) * a})`); g.fillStyle = gr2; g.fillRect(0, y - H * 0.15, W, H); } g.restore(); };
E.shockwave = (g, W, H, t, o, a, u) => { const x = (o.x ?? 0.5) * W, y = (o.y ?? 0.5) * H, p = clamp(u / (o.dur ?? 0.6)), R = ease(p) * Math.hypot(W, H) * (o.size ?? 0.6), w = (60 * (1 - p) + 6) * H / 1080; if (p >= 1) return; const c = copy(g, W, H); g.save(); g.beginPath(); g.arc(x, y, R + w, 0, 7); g.arc(x, y, Math.max(0, R - w), 0, 7, true); g.clip(); const s = 1.04; g.translate(x, y); g.scale(s, s); g.drawImage(c, -x, -y); g.restore(); g.save(); g.globalCompositeOperation = 'screen'; g.strokeStyle = o.color ?? '#ffffff'; g.globalAlpha = (1 - p) * 0.8; g.lineWidth = w * 0.3; g.beginPath(); g.arc(x, y, R, 0, 7); g.stroke(); g.restore(); };
E.tiltShift = (g, W, H, t, o, a) => { const s = soft(g, W, H, 0.5, 5, 'tilt'), m = scratch('tiltm', W, H), mg = m.getContext('2d'); mg.clearRect(0, 0, W, H); mg.drawImage(s, 0, 0, W, H); mg.globalCompositeOperation = 'destination-in'; const y = (o.y ?? 0.55) * H, band = (o.band ?? 0.18) * H, gr = mg.createLinearGradient(0, 0, 0, H); gr.addColorStop(0, 'rgba(0,0,0,1)'); gr.addColorStop(clamp((y - band) / H), 'rgba(0,0,0,0)'); gr.addColorStop(clamp((y + band) / H), 'rgba(0,0,0,0)'); gr.addColorStop(1, 'rgba(0,0,0,1)'); mg.fillStyle = gr; mg.fillRect(0, 0, W, H); mg.globalCompositeOperation = 'source-over'; g.save(); g.globalAlpha = a; g.drawImage(m, 0, 0); g.restore(); };

/** How much of an effect is on at time t: 0 outside t0…t1, eased in and out over `ramp` seconds. */
function amount(o, t) { const t0 = o.t0 ?? -1e9, t1 = o.t1 ?? 1e9; if (t < t0 || t > t1) return 0; const r = o.ramp ?? 0; if (!r) return 1; return Math.min(1, ease((t - t0) / r), ease((t1 - t) / r)); }
/** Lay one effect over the picture on g at time t (seconds into the shot). */
export function effect(g, W, H, t, o = {}) {
  const fn = E[o.type]; if (!fn) throw new Error(`No effect "${o.type}". Effects: ${EFFECT_TYPES.join(', ')}.`);
  const a = typeof o.amount === 'function' ? o.amount(t) : amount(o, t); if (a <= 0.001) return;
  g.save(); try { fn(g, W, H, t, o, a, t - (o.t0 ?? 0)); } finally { g.restore(); }
}
/** A shot's post.fx list, in order. */
export function effects(g, W, H, t, list) { for (const o of list ?? []) effect(g, W, H, t, o); }
