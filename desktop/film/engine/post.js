// What happens to the whole picture after the world is drawn: lens vignette, film grain, fades, flashes, speed lines,
// subtitles and titles.
import { createCanvas, GlobalFonts } from '@napi-rs/canvas';
import { clamp, hash, hex, rgba } from './core.js';

let tile = null;
function grainTile() {
  if (tile) return tile;
  // specks of black and of white with a little opacity: laid over the picture they lift and lower it without any
  // costly blending
  const n = 512; tile = createCanvas(n, n); const g = tile.getContext('2d'), im = g.createImageData(n, n);
  let s = 12345;
  for (let i = 0; i < n * n; i++) { s = (s * 1664525 + 1013904223) >>> 0; const a = (s >>> 24) / 255; s = (s * 1664525 + 1013904223) >>> 0; const b = (s >>> 24) / 255; const v = a + b - 1; im.data[i * 4] = im.data[i * 4 + 1] = im.data[i * 4 + 2] = v > 0 ? 255 : 0; im.data[i * 4 + 3] = Math.min(255, Math.abs(v) * 255); }
  g.putImageData(im, 0, 0);
  return tile;
}
/** Film grain: a little living texture over the flat colours. */
export function grain(g, W, H, amt, frame) {
  if (amt <= 0) return;
  const t = grainTile(), ox = Math.floor(hash(frame * 1.7) * 512), oy = Math.floor(hash(frame * 2.3 + 5) * 512);
  g.save(); g.globalAlpha = Math.min(1, amt * 2.2);
  for (let x = -ox; x < W; x += 512) for (let y = -oy; y < H; y += 512) g.drawImage(t, x, y);
  g.restore();
}
const vig = new Map();
/** Darker corners, as a lens gives. */
export function vignette(g, W, H, amt = 0.35, color = '#05040a') {
  if (amt <= 0) return;
  const key = `${W}x${H}:${amt}:${color}`;
  let cv = vig.get(key);
  if (!cv) {
    cv = createCanvas(W, H); const q = cv.getContext('2d'), c = hex(color), gr = q.createRadialGradient(W / 2, H / 2, H * 0.42, W / 2, H / 2, Math.hypot(W, H) * 0.56);
    gr.addColorStop(0, rgba(c, 0)); gr.addColorStop(1, rgba(c, amt));
    q.fillStyle = gr; q.fillRect(0, 0, W, H);
    if (vig.size > 6) vig.clear();
    vig.set(key, cv);
  }
  g.drawImage(cv, 0, 0);
}
export function fade(g, W, H, a, color = '#000000') { if (a <= 0.002) return; g.save(); g.globalAlpha = clamp(a); g.fillStyle = color; g.fillRect(0, 0, W, H); g.restore(); }
/** A wash of colour over the picture (firelight, moonlight, a memory). op: 'soft-light' | 'overlay' | 'multiply' | 'screen' */
export function wash(g, W, H, color, a, op = 'soft-light') { if (a <= 0.002) return; g.save(); g.globalAlpha = clamp(a); g.globalCompositeOperation = op; g.fillStyle = color; g.fillRect(0, 0, W, H); g.restore(); }
/** A two-colour wash top to bottom. */
export function washGrad(g, W, H, top, bottom, a, op = 'soft-light') { if (a <= 0.002) return; const gr = g.createLinearGradient(0, 0, 0, H); gr.addColorStop(0, top); gr.addColorStop(1, bottom); g.save(); g.globalAlpha = clamp(a); g.globalCompositeOperation = op; g.fillStyle = gr; g.fillRect(0, 0, W, H); g.restore(); }

/** Lines of speed streaming from a point of the picture (a charge, a blow). */
export function speedLines(g, W, H, o = {}) {
  const cx = o.cx ?? W / 2, cy = o.cy ?? H / 2, n = o.n ?? 70, seed = o.seed ?? 0, R = Math.hypot(W, H);
  g.save(); g.fillStyle = o.color ?? '#ffffff'; g.globalAlpha = o.alpha ?? 0.7;
  g.beginPath();
  for (let i = 0; i < n; i++) {
    const a = hash(i * 7.1 + seed) * Math.PI * 2, r0 = (o.inner ?? 0.32) * H * (0.8 + 0.5 * hash(i * 3.3 + seed)), w = (0.002 + 0.006 * hash(i * 5.7 + seed)) * (o.width ?? 1);
    g.moveTo(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0); g.lineTo(cx + Math.cos(a - w) * R, cy + Math.sin(a - w) * R); g.lineTo(cx + Math.cos(a + w) * R, cy + Math.sin(a + w) * R); g.closePath();
  }
  g.fill(); g.restore();
}
/** Lines of speed all running one way (a dash across the frame). */
export function rushLines(g, W, H, o = {}) {
  const n = o.n ?? 60, seed = o.seed ?? 0, ang = o.angle ?? 0, c = Math.cos(ang), s = Math.sin(ang);
  g.save(); g.strokeStyle = o.color ?? '#ffffff'; g.globalAlpha = o.alpha ?? 0.5; g.lineCap = 'round';
  for (let i = 0; i < n; i++) {
    const x = hash(i * 3.7 + seed) * W, y = hash(i * 9.1 + seed + 4) * H, len = (0.12 + 0.5 * hash(i * 1.3 + seed)) * W * (o.len ?? 1);
    g.lineWidth = (1 + 3 * hash(i * 2.9 + seed)) * (H / 1080); g.beginPath(); g.moveTo(x, y); g.lineTo(x + c * len, y + s * len); g.stroke();
  }
  g.restore();
}

const FONT = { sub: 'Inter', title: 'Lora', deva: 'Noto Sans Devanagari' };
export function fonts() { return GlobalFonts.families.map((f) => f.family); }
function wrap(g, text, maxW) {
  const words = text.split(/\s+/), lines = []; let cur = '';
  for (const w of words) { const tryL = cur ? cur + ' ' + w : w; if (g.measureText(tryL).width > maxW && cur) { lines.push(cur); cur = w; } else cur = tryL; }
  if (cur) lines.push(cur);
  return lines;
}
/** A subtitle at the foot of the picture. */
export function subtitle(g, W, H, text, o = {}) {
  if (!text) return;
  const size = Math.round((o.size ?? 46) * H / 1080);
  g.save(); g.font = `600 ${size}px "${o.font ?? FONT.sub}", "Noto Sans", sans-serif`; g.textAlign = 'center'; g.textBaseline = 'alphabetic';
  let lines = wrap(g, text, W * 0.76);
  if (lines.length === 2 && lines[1].split(' ').length === 1 && lines[0].split(' ').length > 3) { const w = lines[0].split(' '); lines = [w.slice(0, -1).join(' '), w[w.length - 1] + ' ' + lines[1]]; }
  const y0 = H * (o.y ?? 0.915) - (lines.length - 1) * size * 1.22;
  g.globalAlpha = clamp(o.alpha ?? 1); g.lineJoin = 'round';
  lines.forEach((ln, i) => {
    const y = y0 + i * size * 1.22;
    g.lineWidth = size * 0.2; g.strokeStyle = 'rgba(8,6,14,0.82)'; g.strokeText(ln, W / 2, y);
    g.fillStyle = o.color ?? '#ffffff'; g.fillText(ln, W / 2, y);
  });
  g.restore();
}
/** Words set into the picture (titles, a place and a time). o: { x, y (0..1), size, font, weight, color, alpha, spacing, align, shadow } */
export function words(g, W, H, text, o = {}) {
  const size = Math.round((o.size ?? 90) * H / 1080);
  g.save(); g.font = `${o.weight ?? 600} ${size}px "${o.font ?? FONT.title}", serif`; g.textAlign = o.align ?? 'center'; g.textBaseline = 'middle';
  if (o.spacing) g.letterSpacing = `${o.spacing * size}px`;
  g.globalAlpha = clamp(o.alpha ?? 1);
  const x = W * (o.x ?? 0.5), y = H * (o.y ?? 0.5);
  if (o.glow) { g.shadowColor = o.glow; g.shadowBlur = size * 0.5; }
  if (o.stroke) { g.lineWidth = size * 0.08; g.strokeStyle = o.stroke; g.lineJoin = 'round'; g.strokeText(text, x, y); }
  g.fillStyle = o.color ?? '#ffffff'; g.fillText(text, x, y);
  g.restore();
}
