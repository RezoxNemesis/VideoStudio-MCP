// TYPE: animated lettering for films — titles, hooks, chapter cards, name straps, comic sounds, credits.
//
//   title(g, W, H, u, { style: 'impact', text: 'WHAT IF?', sub: 'a story', x: 0.5, y: 0.45, scale: 1, colors, hold, out, seed })
//
// u is seconds since the lettering began. Every style comes in, holds, and (when `out` is given, in seconds) leaves.
// Text in Hindi (Devanagari) is set in a face that has its letters (Baloo 2, Yatra One, Kalam, Khand, Rozha One, Teko)
// and is animated word by word, never letter by letter (a conjunct or a matra is never split).
// A shot can carry its lettering as data: post: { titles: [{ style, text, t0, …o }] } (drawn above the effects).
import { createCanvas } from '@napi-rs/canvas';
import { clamp, lerp, hash } from './core.js';
const layers = new Map();
/** A scratch canvas of at least w × h, kept for reuse. */
function layer(w, h) { const key = `${Math.ceil(w / 64)}x${Math.ceil(h / 64)}`; let c = layers.get(key); if (!c) { c = createCanvas(Math.ceil(w / 64) * 64, Math.ceil(h / 64) * 64); layers.set(key, c); } return c; }

const SCRIM = new Set(['cinematic', 'chapter', 'epic', 'gold', 'handwrite', 'rise', 'scramble']);
export const TYPE_STYLES = ['impact', 'pop', 'typewriter', 'glitch', 'neon', 'cinematic', 'chapter', 'lowerThird', 'stamp', 'comic', 'rise', 'wave', 'scramble', 'handwrite', 'gold', 'split', 'counter', 'animeTitle', 'horror', 'retro', 'epic', 'bubble', 'marker'];

const ease = (x) => 1 - Math.pow(1 - clamp(x), 3);
const easeIn = (x) => Math.pow(clamp(x), 3);
const back = (x, s = 1.7) => { x = clamp(x); const c = s + 1; return 1 + c * Math.pow(x - 1, 3) + s * Math.pow(x - 1, 2); };
const elastic = (x) => { x = clamp(x); return x === 0 || x === 1 ? x : Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * (2 * Math.PI / 3)) + 1; };
const smooth = (x) => { x = clamp(x); return x * x * (3 - 2 * x); };
const HINDI = /[ऀ-ॿ]/;
/** Faces with Devanagari, by the feel wanted. */
const DEVA = { round: 'Baloo 2', display: 'Yatra One', hand: 'Kalam', tall: 'Khand', serif: 'Rozha One', thin: 'Teko' };
const fontOf = (text, weight, px, family, devaFeel = 'round') => `${HINDI.test(text) ? (devaFeel === 'round' ? 800 : weight) : weight} ${Math.round(px)}px "${HINDI.test(text) ? DEVA[devaFeel] : family}"`;
const seg = typeof Intl !== 'undefined' && Intl.Segmenter ? new Intl.Segmenter('en', { granularity: 'grapheme' }) : null;
/** The units a style animates one by one: letters for Latin, words for Devanagari. */
function units(text) { if (HINDI.test(text)) return text.split(/(\s+)/).filter((s) => s.length); return seg ? [...seg.segment(text)].map((s) => s.segment) : [...text]; }
/** Lay units out centred on 0 with extra spacing; returns [{ s, x (centre), w }] and the total width. */
function layout(g, text, spacing = 0) {
  const us = units(text), ws = us.map((s) => g.measureText(s).width), total = ws.reduce((a, b) => a + b, 0) + spacing * Math.max(0, us.length - 1);
  let x = -total / 2; const out = us.map((s, i) => { const r = { s, x: x + ws[i] / 2, w: ws[i], i }; x += ws[i] + spacing; return r; });
  return { list: out, total };
}
/** Set a font so `text` fits within maxW; returns the size used. */
function fit(g, text, weight, px, family, maxW, feel) { g.font = fontOf(text, weight, px, family, feel); const w = g.measureText(text).width; if (w > maxW) { px *= maxW / w; g.font = fontOf(text, weight, px, family, feel); } return px; }
/** Letters with layered outlines: an outer stroke, an ink stroke, then the fill. */
function inked(g, s, x, y, o) {
  g.lineJoin = 'round'; g.miterLimit = 2;
  if (o.shadow) { g.save(); g.fillStyle = o.shadow; g.fillText(s, x + (o.sx ?? 0), y + (o.sy ?? 0)); g.restore(); }
  if (o.outer) { g.lineWidth = o.outerW; g.strokeStyle = o.outer; g.strokeText(s, x, y); }
  if (o.ink) { g.lineWidth = o.inkW; g.strokeStyle = o.ink; g.strokeText(s, x, y); }
  g.fillStyle = o.fill; g.fillText(s, x, y);
}
const vgrad = (g, y0, y1, stops) => { const gr = g.createLinearGradient(0, y0, 0, y1); stops.forEach((c, i) => gr.addColorStop(i / (stops.length - 1), c)); return gr; };
const lifeOut = (u, o, len = 0.5) => (o.out != null ? 1 - smooth((u - o.out) / len) : 1);

const S = {};

// ---- IMPACT: a big hook slammed onto the screen — tilted, gradient-filled, double outlined, with a burst behind
S.impact = (g, W, H, u, o, k) => {
  const c = { fill: ['#fff7b0', '#ffc531', '#ff7a1a'], ink: '#1a0f12', outer: '#ffffff', burst: '#ff3b3b', ...o.colors };
  const a = clamp(u / 0.28), s = u < 0.28 ? lerp(2.6, 0.92, easeIn(a)) : lerp(0.92, 1, back((u - 0.28) / 0.35, 3)), alpha = lifeOut(u, o);
  const shake = u > 0.26 && u < 0.6 ? (1 - (u - 0.26) / 0.34) * 14 * k : 0;
  g.translate(shake * (hash(Math.floor(u * 60)) - 0.5) * 2, shake * (hash(Math.floor(u * 60) + 7) - 0.5) * 2);
  g.rotate((o.tilt ?? -6) * Math.PI / 180); g.globalAlpha = alpha * Math.min(1, a * 3);
  const px = fit(g, o.text, 400, 190 * k * (o.size ?? 1), o.font ?? 'Luckiest Guy', W * 0.86, 'display');
  // the burst: a jagged star that pops out behind the words as they land
  const b = ease((u - 0.24) / 0.3);
  if (b > 0 && o.burst !== false) { g.save(); const w = g.measureText(o.text).width * 0.62 * b + 40 * k, h = px * 0.95 * b; g.beginPath(); const n = 18; for (let i = 0; i <= n * 2; i++) { const ang = (i / (n * 2)) * Math.PI * 2 + u * 0.4, r = i % 2 ? 0.78 : 1.0 + 0.12 * hash(i + (o.seed ?? 1)); g.lineTo(Math.cos(ang) * w * r, Math.sin(ang) * h * r - px * 0.33); } g.closePath(); g.fillStyle = c.burst; g.globalAlpha = alpha * 0.92; g.fill(); g.lineWidth = 6 * k; g.strokeStyle = c.ink; g.stroke(); g.restore(); }
  g.scale(s, s); g.textAlign = 'center'; g.textBaseline = 'alphabetic';
  inked(g, o.text, 0, 0, { fill: vgrad(g, -px * 0.8, 0, c.fill), ink: c.ink, inkW: px * 0.11, outer: c.outer, outerW: px * 0.2, shadow: 'rgba(0,0,0,0.45)', sx: 10 * k, sy: 12 * k });
  if (o.sub) { g.font = fontOf(o.sub, 400, px * 0.32, 'Bangers', 'display'); const sb = ease((u - 0.5) / 0.4); g.globalAlpha = alpha * sb; inked(g, o.sub, 0, px * 0.48, { fill: '#ffffff', ink: c.ink, inkW: px * 0.06 }); }
};

// ---- POP: words pop in one by one with a bounce; one word (o.key, an index) sits on a coloured box
S.pop = (g, W, H, u, o, k) => {
  const c = { fill: '#ffffff', ink: '#111118', box: '#ffd23a', keyFill: '#111118', ...o.colors };
  const px = fit(g, o.text, 800, 120 * k * (o.size ?? 1), o.font ?? 'Montserrat', W * 0.88, 'round');
  const words = o.text.split(/\s+/), sp = px * 0.28, ws = words.map((w) => g.measureText(w).width), total = ws.reduce((a, b) => a + b, 0) + sp * (words.length - 1), alpha = lifeOut(u, o);
  let x = -total / 2; g.textAlign = 'center'; g.textBaseline = 'middle';
  words.forEach((w, i) => {
    const t = (u - i * (o.gap ?? 0.16)) / 0.32, s = t <= 0 ? 0 : back(t, 2.6), cx = x + ws[i] / 2; x += ws[i] + sp; if (s <= 0) return;
    g.save(); g.translate(cx, 0); g.rotate((hash(i + (o.seed ?? 3)) - 0.5) * 0.08); g.scale(s, s); g.globalAlpha = alpha;
    const key = o.key === i || (Array.isArray(o.key) && o.key.includes(i));
    if (key) { g.fillStyle = c.box; g.beginPath(); g.roundRect(-ws[i] / 2 - px * 0.16, -px * 0.62, ws[i] + px * 0.32, px * 1.16, px * 0.12); g.fill(); g.lineWidth = px * 0.05; g.strokeStyle = c.ink; g.stroke(); }
    inked(g, w, 0, px * 0.04, key ? { fill: c.keyFill } : { fill: c.fill, ink: c.ink, inkW: px * 0.14, shadow: 'rgba(0,0,0,0.35)', sx: 0, sy: px * 0.07 });
    g.restore();
  });
};

// ---- TYPEWRITER: letters struck one at a time with a blinking caret
S.typewriter = (g, W, H, u, o, k) => {
  const c = { fill: '#f4efe2', caret: '#ffcc55', back: 'rgba(10,10,14,0.55)', ...o.colors };
  const px = fit(g, o.text, 400, 72 * k * (o.size ?? 1), o.font ?? 'Special Elite', W * 0.86, 'thin'), alpha = lifeOut(u, o);
  const us = units(o.text), n = Math.min(us.length, Math.floor(u * (o.cps ?? 16))), shown = us.slice(0, n).join(''), full = g.measureText(o.text).width;
  g.textAlign = 'left'; g.textBaseline = 'middle'; g.globalAlpha = alpha;
  if (o.panel !== false) { g.fillStyle = c.back; g.fillRect(-full / 2 - px * 0.5, -px * 0.85, full + px, px * 1.7); }
  g.fillStyle = c.fill; g.fillText(shown, -full / 2, 0);
  const cx = -full / 2 + g.measureText(shown).width + px * 0.06;
  if (Math.floor(u * 2.2) % 2 === 0 || n < us.length) { g.fillStyle = c.caret; g.fillRect(cx, -px * 0.5, px * 0.08, px); }
};

// ---- GLITCH: the words arrive torn — sliced, shifted, split into red and cyan — and lock into place
S.glitch = (g, W, H, u, o, k) => {
  const c = { fill: '#ffffff', a: '#ff2a6d', b: '#05d9e8', ...o.colors };
  const px = fit(g, o.text, 400, 150 * k * (o.size ?? 1), o.font ?? 'Rubik Glitch', W * 0.88, 'tall'), alpha = lifeOut(u, o);
  g.textAlign = 'center'; g.textBaseline = 'middle';
  const settle = 1 - ease(u / 0.9), burst = (Math.floor(u * 12) % 9 === 0 ? 0.6 : 0) + settle, f = Math.floor(u * 24), w = g.measureText(o.text).width;
  const off = burst * 16 * k;
  g.globalAlpha = alpha * Math.min(1, u * 4);
  g.globalCompositeOperation = 'lighter';
  g.fillStyle = c.a; g.fillText(o.text, -off * (0.5 + hash(f)), 0); g.fillStyle = c.b; g.fillText(o.text, off * (0.5 + hash(f + 3)), 0);
  g.globalCompositeOperation = 'source-over';
  // slices of the clean letters, each shifted on its own
  const n = 7; for (let i = 0; i < n; i++) { const y0 = -px * 0.6 + (i / n) * px * 1.2, dx = (hash(f * 7 + i) - 0.5) * 60 * k * burst * (hash(i + f) > 0.6 ? 1 : 0.15); g.save(); g.beginPath(); g.rect(-w, y0, w * 2, px * 1.2 / n + 1); g.clip(); g.fillStyle = c.fill; g.fillText(o.text, dx, 0); g.restore(); }
  if (burst > 0.3) { g.fillStyle = c.b; g.globalAlpha = alpha * 0.5; for (let i = 0; i < 4; i++) g.fillRect((hash(f + i * 5) - 0.5) * w, (hash(f + i * 9) - 0.5) * px, hash(f + i) * 120 * k, 4 * k); }
};

// ---- NEON: glass tubes that flicker on, then glow steady
S.neon = (g, W, H, u, o, k) => {
  const c = { tube: '#ff3fd8', glow: '#ff3fd8', core: '#ffe9fb', ...o.colors };
  const px = fit(g, o.text, 400, 140 * k * (o.size ?? 1), o.font ?? 'Monoton', W * 0.88, 'thin'), alpha = lifeOut(u, o);
  const on = u < 0.9 ? (hash(Math.floor(u * 20) + 11) > 0.45 - u * 0.4 ? 1 : 0.12) : 0.93 + 0.07 * Math.sin(u * 37) * Math.sin(u * 13);
  g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = alpha;
  // the unlit tubes are always faintly there
  g.lineWidth = px * 0.035; g.strokeStyle = 'rgba(255,255,255,0.12)'; g.strokeText(o.text, 0, 0);
  g.globalAlpha = alpha * on; g.shadowColor = c.glow;
  for (const [blur, lw] of [[60, 0.06], [28, 0.045], [10, 0.03]]) { g.shadowBlur = blur * k; g.lineWidth = px * lw; g.strokeStyle = c.tube; g.strokeText(o.text, 0, 0); }
  g.shadowBlur = 6 * k; g.lineWidth = px * 0.014; g.strokeStyle = c.core; g.strokeText(o.text, 0, 0); g.shadowBlur = 0;
  if (o.sub) { g.font = fontOf(o.sub, 500, px * 0.26, 'Orbitron', 'thin'); g.shadowColor = c.glow; g.shadowBlur = 18 * k; g.fillStyle = c.core; g.fillText(o.sub, 0, px * 0.85); g.shadowBlur = 0; }
};

// ---- CINEMATIC: wide-tracked capitals that breathe apart, thin rules either side, a quiet subtitle
S.cinematic = (g, W, H, u, o, k) => {
  const c = { fill: '#f6f0e4', rule: 'rgba(246,240,228,0.7)', sub: '#d8c8a8', ...o.colors };
  const alpha = Math.min(ease(u / 1.2), lifeOut(u, o, 0.9));
  g.font = fontOf(o.text, 400, 96 * k * (o.size ?? 1), o.font ?? 'Bebas Neue', 'thin'); g.textAlign = 'center'; g.textBaseline = 'middle';
  const sp = lerp(10, 26, ease(u / 4)) * k, L = layout(g, o.text.toUpperCase(), HINDI.test(o.text) ? sp * 0.3 : sp);
  g.globalAlpha = alpha; g.fillStyle = c.fill; for (const q of L.list) g.fillText(q.s, q.x, 0);
  const r = ease((u - 0.4) / 1.4) * 140 * k; g.fillStyle = c.rule; g.fillRect(-L.total / 2 - 40 * k - r, -1 * k, r, 2 * k); g.fillRect(L.total / 2 + 40 * k, -1 * k, r, 2 * k);
  if (o.sub) { g.font = fontOf(o.sub, 400, 34 * k, 'Montserrat', 'thin'); g.globalAlpha = alpha * ease((u - 0.8) / 1); g.fillStyle = c.sub; const L2 = layout(g, o.sub.toUpperCase(), HINDI.test(o.sub) ? 2 * k : 9 * k); for (const q of L2.list) g.fillText(q.s, q.x, 84 * k); }
};

// ---- CHAPTER: "CHAPTER TWO" small and spaced, the chapter's name large, a line drawn between them
S.chapter = (g, W, H, u, o, k) => {
  const c = { fill: '#fff6e4', small: '#e8b86a', line: '#e8b86a', ...o.colors };
  const alpha = lifeOut(u, o, 0.7);
  g.textAlign = 'center'; g.textBaseline = 'alphabetic';
  const a = ease(u / 0.7); g.globalAlpha = alpha * a; g.font = fontOf(o.sub ?? 'CHAPTER ONE', 600, 34 * k, 'Montserrat', 'thin'); g.fillStyle = c.small;
  const L = layout(g, (o.sub ?? 'CHAPTER ONE').toUpperCase(), HINDI.test(o.sub ?? '') ? 3 * k : 12 * k); for (const q of L.list) g.fillText(q.s, q.x, -70 * k + (1 - a) * 12 * k);
  const l = ease((u - 0.35) / 0.8) * 260 * k; g.globalAlpha = alpha; g.fillStyle = c.line; g.fillRect(-l, -40 * k, l * 2, 2.5 * k);
  const b = ease((u - 0.6) / 0.9); g.globalAlpha = alpha * b; const px = fit(g, o.text, 700, 110 * k * (o.size ?? 1), o.font ?? 'Lora', W * 0.84, 'serif'); g.save(); g.beginPath(); g.rect(-W, -40 * k, W * 2, px * 1.4); g.clip(); g.fillStyle = c.fill; g.shadowColor = 'rgba(0,0,0,0.5)'; g.shadowBlur = 18 * k; g.fillText(o.text, 0, -40 * k + px * 0.95 + (1 - b) * px); g.restore();
};

// ---- LOWER THIRD: a coloured strip slides in with a name and a role (x, y default to the lower left)
S.lowerThird = (g, W, H, u, o, k) => {
  const c = { bar: '#ff5a36', bar2: '#1d1b26', fill: '#ffffff', role: '#ffd9c8', ...o.colors };
  const alpha = lifeOut(u, o, 0.4), out = o.out != null ? easeIn((u - o.out) / 0.45) : 0;
  g.textAlign = 'left'; g.textBaseline = 'middle';
  g.font = fontOf(o.text, 800, 58 * k, o.font ?? 'Montserrat', 'round'); const nw = g.measureText(o.text).width; g.font = fontOf(o.sub ?? '', 500, 32 * k, 'Montserrat', 'round'); const rw = o.sub ? g.measureText(o.sub).width : 0;
  const w = Math.max(nw, rw) + 70 * k, a = ease(u / 0.45), b = ease((u - 0.15) / 0.45);
  g.globalAlpha = alpha; g.save(); g.beginPath(); g.rect(0, -60 * k, w * (a - out), 130 * k); g.clip();
  g.fillStyle = c.bar2; g.fillRect(0, -50 * k, w, 72 * k); g.fillStyle = c.bar; g.fillRect(0, -50 * k, 14 * k, 72 * k);
  g.fillStyle = c.fill; g.font = fontOf(o.text, 800, 58 * k, o.font ?? 'Montserrat', 'round'); g.fillText(o.text, 36 * k + (1 - b) * -30 * k, -13 * k);
  if (o.sub) { g.fillStyle = c.bar; g.fillRect(14 * k, 22 * k, w * 0.8 - 14 * k, 46 * k); g.fillStyle = c.fill; g.font = fontOf(o.sub, 600, 30 * k, 'Montserrat', 'round'); g.fillText(o.sub, 36 * k + (1 - b) * -20 * k, 46 * k); }
  g.restore();
};

// ---- STAMP: a rubber stamp thuds down at an angle — rough edges, a box round it, ink that is not quite even
S.stamp = (g, W, H, u, o, k) => {
  const c = { ink: '#d4232e', ...o.colors };
  const a = clamp(u / 0.18), s = u < 0.18 ? lerp(2.2, 1, easeIn(a)) : 1 + Math.sin(clamp((u - 0.18) / 0.2) * Math.PI) * 0.04, alpha = lifeOut(u, o) * Math.min(1, a * 2);
  g.rotate((o.tilt ?? -12) * Math.PI / 180); g.scale(s, s); g.globalAlpha = alpha * 0.9;
  const px = fit(g, o.text.toUpperCase(), 400, 120 * k * (o.size ?? 1), o.font ?? 'Black Ops One', W * 0.7, 'tall'), w = g.measureText(o.text.toUpperCase()).width;
  g.textAlign = 'center'; g.textBaseline = 'middle'; g.strokeStyle = c.ink; g.fillStyle = c.ink; g.lineWidth = 10 * k;
  g.beginPath(); g.roundRect(-w / 2 - 40 * k, -px * 0.75, w + 80 * k, px * 1.5, 16 * k); g.stroke(); g.lineWidth = 3 * k; g.beginPath(); g.roundRect(-w / 2 - 26 * k, -px * 0.62, w + 52 * k, px * 1.24, 10 * k); g.stroke();
  g.fillText(o.text.toUpperCase(), 0, px * 0.05);
  // worn ink: specks cut out of the stamp
  g.globalCompositeOperation = 'destination-out'; for (let i = 0; i < 90; i++) { g.globalAlpha = 0.5 + 0.5 * hash(i * 3.3); g.beginPath(); g.arc((hash(i * 1.7 + (o.seed ?? 2)) - 0.5) * (w + 80 * k), (hash(i * 2.9) - 0.5) * px * 1.5, (1 + 4 * hash(i * 5.1)) * k, 0, 7); g.fill(); }
  g.globalCompositeOperation = 'source-over';
};

// ---- COMIC: a sound word (BAM! WHOOSH!) in a jagged balloon, shaking
S.comic = (g, W, H, u, o, k) => {
  const c = { fill: ['#ffffff', '#ffe14a'], ink: '#14101a', burst: '#ff4040', burst2: '#ffd23a', ...o.colors };
  const s = back(u / 0.3, 3), alpha = lifeOut(u, o, 0.3);
  g.rotate((o.tilt ?? 8) * Math.PI / 180 + Math.sin(u * 30) * 0.02 * (1 - clamp(u / 0.8))); g.scale(s, s); g.globalAlpha = alpha;
  const px = fit(g, o.text, 400, 150 * k * (o.size ?? 1), o.font ?? 'Bangers', W * 0.6, 'display'), w = g.measureText(o.text).width * 0.75 + 60 * k, h = px * 0.95;
  for (const [sc, col] of [[1, c.burst], [0.8, c.burst2]]) { g.beginPath(); const n = 14; for (let i = 0; i <= n * 2; i++) { const ang = (i / (n * 2)) * Math.PI * 2, r = (i % 2 ? 0.7 : 1.0 + 0.25 * hash(i * 1.3 + (o.seed ?? 4))) * sc; g.lineTo(Math.cos(ang) * w * r, Math.sin(ang) * h * r); } g.closePath(); g.fillStyle = col; g.fill(); g.lineWidth = 6 * k; g.strokeStyle = c.ink; g.stroke(); }
  g.textAlign = 'center'; g.textBaseline = 'middle'; inked(g, o.text, 0, px * 0.05, { fill: vgrad(g, -px * 0.4, px * 0.4, c.fill), ink: c.ink, inkW: px * 0.12, shadow: c.ink, sx: 6 * k, sy: 6 * k });
};

// ---- RISE: each word rises out from behind a line, the line then fades
S.rise = (g, W, H, u, o, k) => {
  const c = { fill: '#ffffff', line: '#ffffff', ...o.colors };
  const px = fit(g, o.text, 800, 110 * k * (o.size ?? 1), o.font ?? 'Montserrat', W * 0.86, 'round'), alpha = lifeOut(u, o);
  const words = o.text.split(/\s+/), sp = px * 0.3, ws = words.map((w) => g.measureText(w).width), total = ws.reduce((a, b) => a + b, 0) + sp * (words.length - 1);
  g.textAlign = 'center'; g.textBaseline = 'alphabetic'; g.globalAlpha = alpha;
  const lw = ease(u / 0.5); g.fillStyle = c.line; g.globalAlpha = alpha * (1 - ease((u - 1.4) / 0.6)); g.fillRect(-total / 2 * lw - 20 * k, px * 0.12, (total + 40 * k) * lw, 3 * k); g.globalAlpha = alpha;
  g.save(); g.beginPath(); g.rect(-W, -px * 1.2, W * 2, px * 1.3); g.clip();
  let x = -total / 2; words.forEach((w, i) => { const t = ease((u - 0.2 - i * 0.1) / 0.6), cx = x + ws[i] / 2; x += ws[i] + sp; g.fillStyle = c.fill; g.fillText(w, cx, (1 - t) * px * 1.15); });
  g.restore();
  if (o.sub) { g.font = fontOf(o.sub, 500, px * 0.32, 'Montserrat', 'round'); g.globalAlpha = alpha * ease((u - 0.8) / 0.5); g.fillText(o.sub, 0, px * 0.62); }
};

// ---- WAVE: letters drop in one after another and keep a gentle ripple
S.wave = (g, W, H, u, o, k) => {
  const pal = o.colors?.list ?? ['#ff5a5f', '#ffb400', '#3ec300', '#00a6ed', '#a35bff'], ink = o.colors?.ink ?? '#16121c', alpha = lifeOut(u, o);
  fit(g, o.text, 400, 130 * k * (o.size ?? 1), o.font ?? 'Luckiest Guy', W * 0.88, 'round'); const px = parseFloat(g.font.match(/(\d+)px/)[1]);
  const L = layout(g, o.text, 4 * k); g.textAlign = 'center'; g.textBaseline = 'alphabetic'; g.globalAlpha = alpha;
  for (const q of L.list) { const t = (u - q.i * 0.06) / 0.5; if (t <= 0) continue; const y = (1 - elastic(t)) * -px * 1.4 + Math.sin(u * 4 - q.i * 0.6) * px * 0.05; inked(g, q.s, q.x, y, { fill: pal[q.i % pal.length], ink, inkW: px * 0.12, shadow: 'rgba(0,0,0,0.3)', sx: 0, sy: px * 0.08 }); }
};

// ---- SCRAMBLE: letters cycle through random characters and lock in, left to right (Latin text; Hindi fades in)
S.scramble = (g, W, H, u, o, k) => {
  const c = { fill: '#d6ffe8', glow: '#2bff9a', ...o.colors }, alpha = lifeOut(u, o);
  fit(g, o.text, 500, 100 * k * (o.size ?? 1), o.font ?? 'Orbitron', W * 0.88, 'thin'); g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = alpha;
  g.shadowColor = c.glow; g.shadowBlur = 16 * k; g.fillStyle = c.fill;
  if (HINDI.test(o.text)) { g.globalAlpha = alpha * ease(u / 0.8); g.fillText(o.text, 0, 0); g.shadowBlur = 0; return; }
  const L = layout(g, o.text, 3 * k), CH = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$%&@*';
  for (const q of L.list) { const lock = 0.25 + q.i * 0.05; if (u < q.i * 0.02) continue; const ch = u >= lock || q.s === ' ' ? q.s : CH[Math.floor(hash(q.i * 13 + Math.floor(u * 30)) * CH.length)]; g.globalAlpha = alpha * (u >= lock ? 1 : 0.7); g.fillText(ch, q.x, 0); }
  g.shadowBlur = 0;
};

// ---- HANDWRITE: the words appear as if brushed left to right, the brush's wet edge leading
S.handwrite = (g, W, H, u, o, k) => {
  const c = { fill: '#fdf6e8', ...o.colors }, dur = o.dur ?? 1.6, alpha = lifeOut(u, o);
  const px = fit(g, o.text, 700, 120 * k * (o.size ?? 1), o.font ?? 'Caveat', W * 0.86, 'hand'), w = g.measureText(o.text).width;
  g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = alpha;
  const p = ease(u / dur) * (w + 60 * k) - w / 2 - 30 * k;
  g.save(); g.beginPath(); g.moveTo(-w, -px); g.lineTo(p, -px); for (let i = 0; i <= 10; i++) g.lineTo(p + Math.sin(i * 1.7 + u * 9) * 10 * k, -px + (i / 10) * px * 2); g.lineTo(-w, px); g.closePath(); g.clip();
  g.shadowColor = 'rgba(0,0,0,0.45)'; g.shadowBlur = 10 * k; g.fillStyle = c.fill; g.fillText(o.text, 0, 0); g.restore();
  if (u < dur) { g.fillStyle = c.fill; g.globalAlpha = alpha * 0.5; g.beginPath(); g.arc(p, Math.sin(u * 13) * px * 0.25, px * 0.06, 0, 7); g.fill(); }
};

// ---- GOLD: a metal title — bevelled gold letters with a band of light that sweeps across
S.gold = (g, W, H, u, o, k) => {
  const c = { metal: ['#fff3c4', '#f1c45a', '#9a6418', '#f8d77a', '#7a4a0c'], ink: '#3a2306', shine: 'rgba(255,255,240,0.9)', ...o.colors };
  const a = ease(u / 0.8), alpha = Math.min(a, lifeOut(u, o)), px = fit(g, o.text, 700, 150 * k * (o.size ?? 1), o.font ?? 'Cinzel', W * 0.86, 'serif');
  g.scale(lerp(1.12, 1, a), lerp(1.12, 1, a)); g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = alpha;
  g.fillStyle = c.ink; g.fillText(o.text, 4 * k, 6 * k); g.fillText(o.text, 2 * k, 3 * k);
  const gr = vgrad(g, -px * 0.55, px * 0.45, c.metal); inked(g, o.text, 0, 0, { fill: gr, ink: c.ink, inkW: px * 0.03 });
  // the sweep of light lies on the letters only: drawn on a layer that holds just them
  const w = g.measureText(o.text).width, sx = lerp(-w * 0.8, w * 0.8, clamp((u - 0.5) / 1.1)), lw = Math.ceil(w + px), lh = Math.ceil(px * 2), lay = layer(lw, lh), lg = lay.getContext('2d');
  lg.clearRect(0, 0, lw, lh); lg.font = g.font; lg.textAlign = 'center'; lg.textBaseline = 'middle'; lg.fillStyle = '#fff'; lg.fillText(o.text, lw / 2, lh / 2);
  lg.globalCompositeOperation = 'source-in'; const sg = lg.createLinearGradient(lw / 2 + sx - px * 0.6, 0, lw / 2 + sx + px * 0.6, lh); sg.addColorStop(0, 'rgba(255,255,255,0)'); sg.addColorStop(0.5, c.shine); sg.addColorStop(1, 'rgba(255,255,255,0)'); lg.fillStyle = sg; lg.fillRect(0, 0, lw, lh); lg.globalCompositeOperation = 'source-over';
  g.drawImage(lay, -lw / 2, -lh / 2);
  if (o.sub) { g.font = fontOf(o.sub, 600, px * 0.24, 'Cinzel', 'serif'); g.globalAlpha = alpha * ease((u - 0.9) / 0.6); g.fillStyle = c.metal[1]; const L = layout(g, o.sub.toUpperCase(), HINDI.test(o.sub) ? 2 * k : 10 * k); for (const q of L.list) g.fillText(q.s, q.x, px * 0.75); }
};

// ---- SPLIT: the words cut in half along their middle; the halves slide in from either side and meet
S.split = (g, W, H, u, o, k) => {
  const c = { top: '#ffffff', bottom: '#ffd23a', line: '#ff3b5c', ...o.colors };
  const px = fit(g, o.text, 900, 140 * k * (o.size ?? 1), o.font ?? 'Anton', W * 0.86, 'tall'), a = ease(u / 0.6), alpha = lifeOut(u, o), w = g.measureText(o.text).width;
  g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = alpha;
  g.save(); g.beginPath(); g.rect(-W, -px, W * 2, px); g.clip(); g.fillStyle = c.top; g.fillText(o.text, (1 - a) * -W * 0.7, 0); g.restore();
  g.save(); g.beginPath(); g.rect(-W, 0, W * 2, px); g.clip(); g.fillStyle = c.bottom; g.fillText(o.text, (1 - a) * W * 0.7, 0); g.restore();
  const l = ease((u - 0.45) / 0.4); g.fillStyle = c.line; g.fillRect(-w / 2 * l - 10 * k, -2 * k, (w + 20 * k) * l, 4 * k);
};

// ---- COUNTER: a number that counts up to o.to (o.from, o.prefix '₹', o.suffix '%', o.dur), with a label (o.text) under it
S.counter = (g, W, H, u, o, k) => {
  const c = { fill: '#ffffff', accent: '#3ee0a0', ...o.colors }, alpha = lifeOut(u, o), p = ease(u / (o.dur ?? 1.6));
  const v = lerp(o.from ?? 0, o.to ?? 100, p), dec = o.decimals ?? 0, num = `${o.prefix ?? ''}${v.toLocaleString('en-IN', { minimumFractionDigits: dec, maximumFractionDigits: dec })}${o.suffix ?? ''}`;
  g.textAlign = 'center'; g.textBaseline = 'alphabetic'; g.globalAlpha = alpha;
  g.font = `800 ${Math.round(170 * k * (o.size ?? 1))}px "${o.font ?? 'Baloo 2'}"`; g.fillStyle = c.fill; g.shadowColor = 'rgba(0,0,0,0.45)'; g.shadowBlur = 20 * k; g.fillText(num, 0, 0); g.shadowBlur = 0;
  g.fillStyle = c.accent; g.fillRect(-120 * k * p, 24 * k, 240 * k * p, 6 * k);
  if (o.text) { g.font = fontOf(o.text, 600, 40 * k, 'Montserrat', 'round'); g.fillStyle = c.fill; g.globalAlpha = alpha * ease((u - 0.3) / 0.6); g.fillText(o.text, 0, 84 * k); }
};

// ---- ANIME TITLE: a slanted colour band wipes across, the title sits on it in white with a heavy black edge
S.animeTitle = (g, W, H, u, o, k) => {
  const c = { band: '#e8283c', band2: '#121018', fill: '#ffffff', ink: '#0c0a10', ...o.colors };
  const a = ease(u / 0.35), b = ease((u - 0.12) / 0.35), alpha = lifeOut(u, o, 0.3), out = o.out != null ? easeIn((u - o.out) / 0.35) : 0;
  g.globalAlpha = alpha; g.save(); g.rotate(-0.12);
  g.fillStyle = c.band2; g.fillRect(-W * (1 - a) - W * 0.7 + out * W * 1.6, -95 * k, W * 1.4, 190 * k);
  g.fillStyle = c.band; g.fillRect(W * (1 - b) - W * 0.7 - out * W * 1.6, -70 * k, W * 1.4, 140 * k); g.restore();
  const t = back((u - 0.3) / 0.35, 2.2); if (t <= 0) return;
  g.save(); g.rotate(-0.12); g.scale(t, t); const px = fit(g, o.text, 400, 120 * k * (o.size ?? 1), o.font ?? 'Bangers', W * 0.8, 'display'); g.textAlign = 'center'; g.textBaseline = 'middle';
  inked(g, o.text, 0, px * 0.04, { fill: c.fill, ink: c.ink, inkW: px * 0.14 });
  if (o.sub) { g.font = fontOf(o.sub, 700, px * 0.3, 'Montserrat', 'round'); g.fillStyle = c.fill; g.fillText(o.sub, W * 0.12, px * 0.95); }
  g.restore();
};

// ---- HORROR: dripping letters that flicker like a failing bulb
S.horror = (g, W, H, u, o, k) => {
  const c = { fill: '#c8141e', glow: '#ff2020', ...o.colors }, alpha = lifeOut(u, o) * ease(u / 1.2);
  const flick = hash(Math.floor(u * 15) + 3) > 0.15 ? 1 : 0.25, px = fit(g, o.text, 400, 130 * k * (o.size ?? 1), o.font ?? 'Nosifer', W * 0.88, 'display');
  g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = alpha * flick; g.shadowColor = c.glow; g.shadowBlur = 30 * k; g.fillStyle = c.fill; g.fillText(o.text, (hash(Math.floor(u * 30)) - 0.5) * 3 * k, 0); g.shadowBlur = 0;
  // drips that lengthen
  const w = g.measureText(o.text).width; for (let i = 0; i < 9; i++) { const x = (hash(i * 2.3 + (o.seed ?? 5)) - 0.5) * w * 0.9, len = clamp((u - hash(i) * 0.8) / 2.5) * px * (0.4 + hash(i * 3.7)); if (len <= 0) continue; g.fillRect(x - 2.5 * k, px * 0.3, 5 * k, len); g.beginPath(); g.arc(x, px * 0.3 + len, 5 * k, 0, 7); g.fill(); }
};

// ---- RETRO: chunky arcade letters with a stepped 3D extrusion, sliding in on a scanline
S.retro = (g, W, H, u, o, k) => {
  const c = { fill: ['#fffb96', '#ff71ce'], side: '#01cdfe', ink: '#1a0b2e', ...o.colors };
  const a = ease(u / 0.5), alpha = lifeOut(u, o), px = fit(g, o.text, 400, 110 * k * (o.size ?? 1), o.font ?? 'Bungee', W * 0.86, 'tall');
  g.translate(0, (1 - a) * -H * 0.3); g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = alpha * a;
  const depth = Math.round(10 * k); for (let i = depth; i > 0; i--) { g.fillStyle = i === depth ? c.ink : c.side; g.fillText(o.text, i, i); }
  inked(g, o.text, 0, 0, { fill: vgrad(g, -px * 0.5, px * 0.5, c.fill), ink: c.ink, inkW: px * 0.04 });
  if (o.sub) { g.font = fontOf(o.sub, 400, px * 0.22, 'Press Start 2P', 'thin'); g.fillStyle = c.side; if (Math.floor(u * 2) % 2 === 0 || u < 1) g.fillText(o.sub, 0, px * 0.9); }
};

// ---- EPIC: a carved title in a fantasy face, embers rising through it, glowing as it settles (for kings and legends)
S.epic = (g, W, H, u, o, k) => {
  const c = { fill: ['#fff2d6', '#e9b45a', '#8a4a14'], glow: '#ff9a3a', ember: '#ffb04a', ...o.colors };
  const a = ease(u / 1.6), alpha = Math.min(a, lifeOut(u, o, 1)), px = fit(g, o.text, 700, 140 * k * (o.size ?? 1), o.font ?? 'Cinzel Decorative', W * 0.86, 'serif');
  g.scale(lerp(0.94, 1, a), lerp(0.94, 1, a)); g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = alpha;
  g.shadowColor = c.glow; g.shadowBlur = (20 + 20 * Math.sin(u * 2)) * k; g.fillStyle = vgrad(g, -px * 0.5, px * 0.5, c.fill); g.fillText(o.text, 0, 0); g.shadowBlur = 0;
  g.lineWidth = 1.5 * k; g.strokeStyle = 'rgba(60,20,0,0.6)'; g.strokeText(o.text, 0, 0);
  const w = g.measureText(o.text).width; g.globalCompositeOperation = 'lighter';
  for (let i = 0; i < 40; i++) { const life = ((u * 0.35 + hash(i * 1.9)) % 1), x = (hash(i * 3.1) - 0.5) * w * 1.2 + Math.sin(u * 2 + i) * 10 * k, y = px * 0.7 - life * px * 2.2; g.globalAlpha = alpha * (1 - life) * 0.9; g.fillStyle = c.ember; g.beginPath(); g.arc(x, y, (1.5 + 2.5 * hash(i)) * k, 0, 7); g.fill(); }
  g.globalCompositeOperation = 'source-over';
  if (o.sub) { g.globalAlpha = alpha * ease((u - 1.2) / 1); g.font = fontOf(o.sub, 500, px * 0.22, 'Cinzel', 'serif'); g.fillStyle = c.fill[1]; const L = layout(g, o.sub.toUpperCase(), HINDI.test(o.sub) ? 2 * k : 12 * k); for (const q of L.list) g.fillText(q.s, q.x, px * 0.78); }
};

// ---- BUBBLE: a speech balloon that pops up with a tail pointing at o.tail ([x, y] in frame fractions) — words inside
S.bubble = (g, W, H, u, o, k) => {
  const c = { fill: '#ffffff', ink: '#16121a', text: '#16121a', ...o.colors }, s = back(u / 0.3, 2.4), alpha = lifeOut(u, o, 0.25);
  const px = 54 * k * (o.size ?? 1); g.font = fontOf(o.text, 600, px, o.font ?? 'Baloo 2', 'round');
  const lines = wrap(g, o.text, W * 0.38), lw = Math.max(...lines.map((l) => g.measureText(l).width)), bw = lw + px * 1.4, bh = lines.length * px * 1.2 + px * 0.9;
  g.globalAlpha = alpha; g.scale(s, s);
  const cx0 = (o.x ?? 0.5) * W, cy0 = (o.y ?? 0.3) * H, tx = ((o.tail?.[0] ?? (o.x ?? 0.5) - 0.05) * W - cx0) / s, ty = ((o.tail?.[1] ?? (o.y ?? 0.3) + 0.2) * H - cy0) / s;
  g.beginPath(); g.ellipse(0, 0, bw * 0.62, bh * 0.68, 0, 0, 7); g.moveTo(-bw * 0.08, bh * 0.5); g.lineTo(tx * 0.9, ty * 0.9); g.lineTo(bw * 0.12, bh * 0.55);
  g.fillStyle = c.fill; g.fill(); g.lineWidth = 5 * k; g.strokeStyle = c.ink; g.stroke(); g.beginPath(); g.ellipse(0, 0, bw * 0.62 - 2.5 * k, bh * 0.68 - 2.5 * k, 0, 0, 7); g.fill();
  g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillStyle = c.text; lines.forEach((l, i) => g.fillText(l, 0, (i - (lines.length - 1) / 2) * px * 1.2));
};
function wrap(g, text, maxW) { const words = text.split(/\s+/), lines = []; let cur = ''; for (const w of words) { const t = cur ? cur + ' ' + w : w; if (g.measureText(t).width > maxW && cur) { lines.push(cur); cur = w; } else cur = t; } if (cur) lines.push(cur); return lines; }

// ---- MARKER: hand-lettered words with a highlighter swipe behind them and a scribbled underline
S.marker = (g, W, H, u, o, k) => {
  const c = { fill: '#16121a', hi: '#ffe94a', line: '#ff3b5c', ...o.colors }, alpha = lifeOut(u, o);
  const px = fit(g, o.text, 400, 110 * k * (o.size ?? 1), o.font ?? 'Permanent Marker', W * 0.84, 'hand'), w = g.measureText(o.text).width;
  g.textAlign = 'center'; g.textBaseline = 'middle'; g.globalAlpha = alpha;
  const h1 = ease(u / 0.45); g.fillStyle = c.hi; g.beginPath(); g.moveTo(-w / 2 - 20 * k, -px * 0.35); g.lineTo(-w / 2 - 20 * k + (w + 40 * k) * h1, -px * 0.42); g.lineTo(-w / 2 - 20 * k + (w + 40 * k) * h1, px * 0.4); g.lineTo(-w / 2 - 20 * k, px * 0.45); g.closePath(); g.fill();
  const t = ease((u - 0.2) / 0.5); g.globalAlpha = alpha * t; g.fillStyle = c.fill; g.fillText(o.text, 0, (1 - t) * 10 * k);
  const ul = ease((u - 0.55) / 0.5); if (ul > 0) { g.beginPath(); g.moveTo(-w / 2, px * 0.62); for (let i = 1; i <= 20 * ul; i++) g.lineTo(-w / 2 + (w * i) / 20, px * 0.62 + Math.sin(i * 1.9) * 5 * k); g.lineWidth = 8 * k; g.lineCap = 'round'; g.strokeStyle = c.line; g.globalAlpha = alpha; g.stroke(); }
};

/**
 * Draw lettering. u = seconds since it began. o: { style, text, sub, x, y (0…1 of the frame: the middle of the lettering,
 * or the left edge of a lower third), scale, size, colors, font, tilt, hold?, out (second it leaves), seed, …per style }
 */
export function title(g, W, H, u, o = {}) {
  if (u < 0 || !o.text && o.style !== 'counter') return;
  const fn = S[o.style ?? 'impact']; if (!fn) throw new Error(`No lettering style "${o.style}". Styles: ${TYPE_STYLES.join(', ')}.`);
  if (o.out != null && u > o.out + 1.2) return;
  const k = H / 1080 * (o.scale ?? 1), lower = o.style === 'lowerThird';
  g.save(); g.translate(W * (o.x ?? (lower ? 0.06 : 0.5)), H * (o.y ?? (lower ? 0.8 : 0.45)));
  // light, thin lettering gets a soft dark scrim behind it so it reads over any picture (o.scrim: false to leave it off,
  // a number for its strength)
  if (o.scrim !== false && (SCRIM.has(o.style) || o.scrim)) { const a = (typeof o.scrim === 'number' ? o.scrim : 0.5) * ease(u / 0.6) * (o.out != null ? 1 - smooth((u - o.out) / 0.6) : 1), rx = W * 0.42, ry = 150 * k; g.save(); g.translate(0, 30 * k); g.scale(1, ry / rx); const gr = g.createRadialGradient(0, 0, 0, 0, 0, rx); gr.addColorStop(0, `rgba(8,6,14,${a})`); gr.addColorStop(0.55, `rgba(8,6,14,${a * 0.6})`); gr.addColorStop(1, 'rgba(8,6,14,0)'); g.fillStyle = gr; g.fillRect(-rx, -rx, rx * 2, rx * 2); g.restore(); }
  try { fn(g, W, H, u, { ...o, text: o.text ?? '' }, k); } finally { g.restore(); }
}
/** Every title of a shot's post.titles at time t (each { t0, …title options }). */
export function drawTitles(g, W, H, t, list) { for (const q of list ?? []) title(g, W, H, t - (q.t0 ?? 0), q); }
