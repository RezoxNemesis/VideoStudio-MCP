/**
 * Procedural layers: everything here is drawn fresh each frame from a few parameters, so an agent can add a
 * whole caption track, a motion-graphics element or a film-burn overlay with one small command.
 *
 *   captions   word-by-word captions synced to speech (pop, karaoke, bold, serif, minimal, torn)
 *   kinetic    big kinetic typography (stretch, stack, warp, split, typewriter, slide)
 *   mograph    motion-design elements (ball, morph, halftone, cube, counter, chart, burst, toggle)
 *   fx         hand-drawn effects animated on twos (fire, splash, ripple, impact, speedlines, sparkle, smoke)
 *   overlay    film looks (lightLeak, filmBurn, dust, vhs, stripe, paper)
 *   cutout     paper-cut collage photo: torn edge, white border, shadow, stop-motion jitter
 *   label      torn-paper label with text
 *   paper      paper / newspaper / kraft background texture
 */
import type { AnimLayer, AnimScene } from "../core/model";

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;
export type ImageLookup = (assetId: string) => CanvasImageSource | null;

export interface CaptionWord {
  w: string;
  t0: number;
  t1: number;
  em?: boolean;
}

export interface ProcSpec {
  type: "captions" | "kinetic" | "mograph" | "fx" | "overlay" | "cutout" | "label" | "paper";
  /** Sub-type: caption style, kinetic style, mograph/fx/overlay kind, paper tone. */
  style: string;
  text?: string;
  words?: CaptionWord[];
  x?: number;
  y?: number;
  size?: number;
  w?: number;
  rot?: number;
  color?: string;
  accent?: string;
  bg?: string;
  palette?: string[];
  position?: "top" | "center" | "bottom";
  maxWords?: number;
  uppercase?: boolean;
  assetId?: string;
  value?: number;
  seed?: number;
  enter?: "drop" | "slide" | "pop" | "none";
  /** Stop-motion rate for jitter (frames per second of the "boil"). */
  boil?: number;
}

export const PROC_TYPES = ["captions", "kinetic", "mograph", "fx", "overlay", "cutout", "label", "paper"] as const;
export const CAPTION_STYLES = ["pop", "karaoke", "bold", "serif", "minimal", "torn"] as const;
export const KINETIC_STYLES = ["stretch", "stack", "warp", "split", "typewriter", "slide"] as const;
export const MOGRAPH_TYPES = ["ball", "morph", "halftone", "cube", "counter", "chart", "burst", "toggle"] as const;
export const FX_TYPES = ["fire", "splash", "ripple", "impact", "speedlines", "sparkle", "smoke", "shockwave", "slash", "debris", "dust", "clash"] as const;
export const OVERLAY_TYPES = ["lightLeak", "filmBurn", "dust", "vhs", "stripe", "paper", "rain", "flash", "impactFrame"] as const;
export const PAPER_TONES = ["cream", "news", "kraft", "dark"] as const;

const SANS = "Geist Variable, system-ui, sans-serif";
const SERIF = "Instrument Serif, Georgia, serif";

/* --------------------------------- helpers --------------------------------- */

const clamp = (x: number, a = 0, b = 1) => Math.max(a, Math.min(b, x));
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
const easeOut = (t: number) => 1 - Math.pow(1 - clamp(t), 3);
const easeInOut = (t: number) => {
  const x = clamp(t);
  return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
};
const backOut = (t: number, k = 1.9) => {
  const x = clamp(t) - 1;
  return 1 + (k + 1) * x * x * x + k * x * x;
};
function hash(n: number): number {
  const s = Math.sin(n * 127.1 + 311.7) * 43758.5453;
  return s - Math.floor(s);
}
function noise(x: number, seed = 0): number {
  const i = Math.floor(x);
  const f = x - i;
  const u = f * f * (3 - 2 * f);
  return lerp(hash(i + seed * 57), hash(i + 1 + seed * 57), u) * 2 - 1;
}
function rgba(hex: string, a: number): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return hex;
  const n = parseInt(m[1]!, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

/** Jagged "torn paper" polygon around a rectangle. */
function tornRect(c: Ctx, x: number, y: number, w: number, h: number, seed: number, rough: number) {
  const pts: [number, number][] = [];
  const step = Math.max(6, rough * 1.6);
  const edge = (ax: number, ay: number, bx: number, by: number, s: number) => {
    const len = Math.hypot(bx - ax, by - ay);
    const n = Math.max(2, Math.round(len / step));
    const nx = -(by - ay) / len;
    const ny = (bx - ax) / len;
    for (let i = 0; i < n; i++) {
      const t = i / n;
      const j = (hash(s + i * 1.7) - 0.5) * rough + (hash(s + i * 0.31) - 0.5) * rough * 0.6;
      pts.push([lerp(ax, bx, t) + nx * j, lerp(ay, by, t) + ny * j]);
    }
  };
  edge(x, y, x + w, y, seed);
  edge(x + w, y, x + w, y + h, seed + 40);
  edge(x + w, y + h, x, y + h, seed + 80);
  edge(x, y + h, x, y, seed + 120);
  c.beginPath();
  c.moveTo(pts[0]![0], pts[0]![1]);
  for (const [px, py] of pts) c.lineTo(px, py);
  c.closePath();
}

function paperFill(c: Ctx, x: number, y: number, w: number, h: number, base: string, seed: number) {
  c.fillStyle = base;
  c.fill();
  c.save();
  c.clip();
  // Fibres and mottling.
  for (let i = 0; i < 40; i++) {
    const px = x + hash(seed + i) * w;
    const py = y + hash(seed + i + 99) * h;
    const r = (0.08 + hash(seed + i + 7) * 0.25) * Math.max(w, h);
    const g = c.createRadialGradient(px, py, 0, px, py, r);
    g.addColorStop(0, `rgba(0,0,0,${0.03 + hash(i + seed) * 0.03})`);
    g.addColorStop(1, "rgba(0,0,0,0)");
    c.fillStyle = g;
    c.fillRect(px - r, py - r, r * 2, r * 2);
  }
  c.restore();
}

/* --------------------------------- entry ---------------------------------- */

export function drawProc(c: Ctx, l: AnimLayer, s: AnimScene, f: number, images?: ImageLookup): void {
  const p = l.proc;
  if (!p) return;
  const t = f / s.fps;
  const dur = s.frames / s.fps;
  c.save();
  try {
    switch (p.type) {
      case "captions":
        captions(c, p, s, t);
        break;
      case "kinetic":
        kinetic(c, p, s, t, dur);
        break;
      case "mograph":
        mograph(c, p, s, t, dur);
        break;
      case "fx":
        fx(c, p, s, f);
        break;
      case "overlay":
        overlay(c, p, s, t, dur, f);
        break;
      case "cutout":
        cutout(c, p, s, t, f, images);
        break;
      case "label":
        label(c, p, s, t, f);
        break;
      case "paper":
        paper(c, p, s);
        break;
    }
  } finally {
    c.restore();
  }
}

/* -------------------------------- captions -------------------------------- */

interface Chunk {
  words: CaptionWord[];
  start: number;
  end: number;
}

export function chunkWords(words: CaptionWord[], maxWords: number): Chunk[] {
  const out: Chunk[] = [];
  let cur: CaptionWord[] = [];
  const flush = () => {
    if (!cur.length) return;
    out.push({ words: cur, start: cur[0]!.t0, end: cur[cur.length - 1]!.t1 });
    cur = [];
  };
  for (const w of words) {
    cur.push(w);
    const chars = cur.reduce((n, x) => n + x.w.length + 1, 0);
    if (cur.length >= maxWords || /[.!?,;:]$/.test(w.w) || chars > 22) flush();
  }
  flush();
  return out;
}

/** Spread words over the speech: proportional to word length inside the voiced spans of an envelope. */
export function timeWords(text: string, start: number, end: number, envelope?: readonly number[], envFps = 30): CaptionWord[] {
  const raw = text.split(/\s+/).filter(Boolean);
  const words = raw.map((w) => ({ w: w.replace(/^\*(.+)\*([.,!?;:]*)$/, "$1$2"), em: /^\*.+\*[.,!?;:]*$/.test(w) }));
  if (!words.length) return [];
  // Voiced spans from the envelope (gaps longer than 0.18 s split phrases).
  let spans: [number, number][] = [[start, end]];
  if (envelope && envelope.length > 4) {
    spans = [];
    const thr = 0.12;
    let on = -1;
    let quiet = 0;
    for (let i = 0; i < envelope.length; i++) {
      const v = envelope[i]!;
      const tt = start + i / envFps;
      if (tt > end) break;
      if (v > thr) {
        if (on < 0) on = tt;
        quiet = 0;
      } else if (on >= 0) {
        quiet += 1 / envFps;
        if (quiet > 0.18) {
          spans.push([on, tt - quiet]);
          on = -1;
        }
      }
    }
    if (on >= 0) spans.push([on, Math.min(end, start + envelope.length / envFps)]);
    if (!spans.length) spans = [[start, end]];
  }
  const voiced = spans.reduce((n, [a, b]) => n + (b - a), 0);
  const weights = words.map((w) => 0.6 + w.w.replace(/[^\p{L}\p{N}]/gu, "").length);
  const total = weights.reduce((a, b) => a + b, 0);
  // Map cumulative weight to a time inside the voiced spans.
  const at = (frac: number) => {
    let need = frac * voiced;
    for (const [a, b] of spans) {
      if (need <= b - a) return a + need;
      need -= b - a;
    }
    return spans[spans.length - 1]![1];
  };
  let acc = 0;
  return words.map((w, i) => {
    const t0 = at(acc / total);
    acc += weights[i]!;
    const t1 = at(acc / total);
    return { w: w.w, em: w.em, t0, t1: Math.max(t0 + 0.08, t1) };
  });
}

function captions(c: Ctx, p: ProcSpec, s: AnimScene, t: number) {
  const words = p.words ?? [];
  if (!words.length) return;
  const K = s.height / 1920;
  const W = s.width;
  const style = p.style || "pop";
  const chunks = chunkWords(words, p.maxWords ?? (style === "serif" ? 4 : style === "bold" ? 2 : 3));
  let ci = -1;
  for (let i = 0; i < chunks.length; i++) {
    const next = chunks[i + 1];
    if (t >= chunks[i]!.start - 0.04 && t < (next ? next.start - 0.04 : chunks[i]!.end + 0.6)) ci = i;
  }
  if (ci < 0) return;
  const ch = chunks[ci]!;
  const base = (p.size ?? (style === "bold" ? 118 : style === "minimal" ? 68 : style === "serif" ? 84 : 92)) * K;
  const color = p.color ?? "#ffffff";
  const accent = p.accent ?? (style === "karaoke" ? "#7c5cff" : "#ffd23f");
  const yBase = (p.y ?? (p.position === "top" ? 0.2 : p.position === "center" ? 0.5 : 0.72)) * s.height;
  const up = p.uppercase ?? (style === "bold" || style === "torn");
  const label = (w: CaptionWord) => (up ? w.w.toUpperCase() : style === "minimal" ? w.w.toLowerCase() : w.w);
  const active = (w: CaptionWord) => t >= w.t0 && t < w.t1 + 0.02;
  const appear = (w: CaptionWord) => clamp((t - w.t0) / 0.16);

  if (style === "serif") {
    // Small sans words + one big italic serif keyword (the *emphasised* or longest word).
    const em = ch.words.find((w) => w.em) ?? ch.words.reduce((a, b) => (b.w.length > a.w.length ? b : a));
    const small = ch.words.filter((w) => w !== em);
    c.textAlign = "center";
    c.textBaseline = "middle";
    const fade = clamp((t - ch.start) / 0.25);
    if (small.length) {
      c.font = `600 ${base * 0.62}px ${SANS}`;
      c.globalAlpha = fade;
      c.fillStyle = rgba(color, 0.92);
      const light = /^#?(?:[e-f][0-9a-f]){3}$/i.test(color) || color === "#ffffff";
      c.shadowColor = light ? "rgba(0,0,0,0.6)" : "rgba(255,255,255,0.0)";
      c.shadowBlur = 12 * K;
      c.fillText(small.map(label).join(" ").toLowerCase(), W / 2, yBase - base * 1.1);
    }
    const a = appear(em);
    c.globalAlpha = a;
    c.font = `italic 400 ${base * 2.1}px ${SERIF}`;
    c.filter = a < 1 ? `blur(${((1 - a) * 10 * K).toFixed(1)}px)` : "none";
    c.fillStyle = color;
    c.fillText(label(em).replace(/[.,!?;:]$/, ""), W / 2, yBase + base * 0.35);
    c.filter = "none";
    return;
  }

  // Lay words out in lines.
  const font = (w: CaptionWord, big = 1) => (style === "bold" ? `900 ${base * big}px ${SANS}` : style === "minimal" ? `500 ${base * big}px ${SANS}` : style === "torn" ? `800 ${base * big}px ${SANS}` : `800 ${base * big}px ${SANS}`);
  const space = base * (style === "bold" ? 0.42 : 0.32);
  const maxW = W * 0.86;
  const metrics = ch.words.map((w) => {
    c.font = font(w);
    return c.measureText(label(w)).width;
  });
  const lines: number[][] = [[]];
  let lw = 0;
  ch.words.forEach((_, i) => {
    const wv = metrics[i]!;
    if (lw > 0 && lw + space + wv > maxW) {
      lines.push([]);
      lw = 0;
    }
    lines[lines.length - 1]!.push(i);
    lw += (lw > 0 ? space : 0) + wv;
  });
  const lineH = base * (style === "torn" ? 1.5 : 1.18);
  const y0 = yBase - ((lines.length - 1) * lineH) / 2;
  c.textBaseline = "middle";
  c.textAlign = "left";
  lines.forEach((li, row) => {
    const width = li.reduce((n, i, k) => n + metrics[i]! + (k ? space : 0), 0);
    let x = W / 2 - width / 2;
    const y = y0 + row * lineH;
    if (style === "torn") {
      // One torn paper strip per line, slightly rotated, jittering on 2s.
      const seed = (ch.start * 100 + row * 13) | 0;
      const jit = Math.floor(t * 12);
      c.save();
      c.translate(W / 2, y);
      c.rotate((hash(seed) - 0.5) * 0.06 + (hash(seed + jit) - 0.5) * 0.008);
      const padX = base * 0.35;
      tornRect(c, -width / 2 - padX, -lineH * 0.42, width + padX * 2, lineH * 0.84, seed + jit * 0, base * 0.12);
      c.shadowColor = "rgba(0,0,0,0.35)";
      c.shadowBlur = 14 * K;
      c.shadowOffsetY = 5 * K;
      paperFill(c, -width / 2 - padX, -lineH * 0.42, width + padX * 2, lineH * 0.84, row % 2 ? (p.accent ?? "#e8542f") : (p.bg ?? "#f1e9d8"), seed);
      c.restore();
    }
    for (const i of li) {
      const w = ch.words[i]!;
      const wv = metrics[i]!;
      const a = appear(w);
      const on = active(w);
      let sc = 1;
      let alpha = 1;
      let dy = 0;
      let blur = 0;
      if (style === "pop" || style === "bold") {
        alpha = a > 0 ? 1 : 0;
        sc = a >= 1 ? 1 : 0.55 + 0.45 * backOut(a, 2.6);
        if (on && style === "bold") sc *= 1.12;
      } else if (style === "minimal") {
        alpha = a;
        blur = (1 - a) * 8 * K;
        dy = (1 - easeOut(a)) * base * 0.25;
      } else if (style === "karaoke") {
        alpha = clamp((t - ch.start + 0.05) / 0.15);
      } else if (style === "torn") {
        alpha = a > 0 ? 1 : 0;
      }
      if (alpha <= 0) {
        x += wv + space;
        continue;
      }
      c.save();
      c.globalAlpha = alpha;
      c.translate(x + wv / 2, y + dy);
      c.scale(sc, sc);
      c.font = font(w);
      if (blur > 0.3) c.filter = `blur(${blur.toFixed(1)}px)`;
      if (style === "karaoke" && on) {
        const k2 = easeOut(clamp((t - w.t0) / 0.12));
        c.fillStyle = accent;
        c.beginPath();
        c.roundRect(-wv / 2 - base * 0.16, -base * 0.62, (wv + base * 0.32) * k2, base * 1.18, base * 0.22);
        c.fill();
      }
      if (style !== "torn" && style !== "minimal") {
        c.lineJoin = "round";
        c.lineWidth = base * (style === "bold" ? 0.2 : 0.14);
        c.strokeStyle = "#000000";
        c.shadowColor = "rgba(0,0,0,0.55)";
        c.shadowBlur = base * 0.18;
        c.shadowOffsetY = base * 0.06;
        c.strokeText(label(w), -wv / 2, 0);
        c.shadowColor = "transparent";
      } else if (style === "minimal") {
        c.shadowColor = "rgba(0,0,0,0.5)";
        c.shadowBlur = base * 0.25;
      }
      c.fillStyle = style === "torn" ? (lines.indexOf(li) % 2 ? "#fff8ee" : "#1b1712") : (on && style !== "karaoke") || w.em ? accent : color;
      c.fillText(label(w), -wv / 2, 0);
      c.restore();
      x += wv + space;
    }
  });
}

/* ------------------------------ kinetic type ------------------------------- */

function kinetic(c: Ctx, p: ProcSpec, s: AnimScene, t: number, dur: number) {
  const W = s.width;
  const H = s.height;
  const K = Math.min(W, H) / 1080;
  const text = (p.text ?? "MAKE IT MOVE").trim();
  const lines = text.split(/\n|\s*\/\s*/).filter(Boolean);
  const pal = p.palette ?? [p.color ?? "#111111", p.accent ?? "#ff4a2e"];
  if (p.bg) {
    c.fillStyle = p.bg;
    c.fillRect(0, 0, W, H);
  }
  const style = p.style || "stretch";
  const out = clamp((t - (dur - 0.45)) / 0.45); // exit
  c.textBaseline = "alphabetic";
  c.textAlign = "left";
  if (style === "stretch") {
    // Heavy letters fill the width; each line drops in squashed then springs to full height with smear.
    const lh = (H * 0.62) / Math.max(1, lines.length);
    lines.forEach((ln, i) => {
      const a = clamp((t - i * 0.22) / 0.5);
      if (a <= 0) return;
      let size = Math.min(lh * 1.12, W * 0.32);
      c.font = `900 ${size}px ${SANS}`;
      let w = c.measureText(ln).width;
      const fit = (W * 0.92) / w;
      size *= Math.min(fit, 1.8);
      c.font = `900 ${size}px ${SANS}`;
      w = c.measureText(ln).width;
      const sy = backOut(a, 1.6) * (1 - out);
      const sx = lerp(1.25, 1, easeOut(a));
      const yc = H * 0.5 + (i - (lines.length - 1) / 2) * lh;
      c.save();
      c.translate(W / 2, yc + size * 0.36);
      c.scale(sx, Math.max(0.001, sy));
      // Smear trail while moving fast.
      if (a < 0.7) {
        c.globalAlpha = 0.25;
        c.fillStyle = pal[i % pal.length]!;
        c.fillText(ln, -w / 2, size * 0.15 * (1 - a));
        c.globalAlpha = 1;
      }
      c.fillStyle = pal[i % pal.length]!;
      c.fillText(ln, -w / 2, 0);
      c.restore();
    });
    return;
  }
  if (style === "stack") {
    const size = Math.min(W * 0.16, (H * 0.7) / Math.max(1, lines.length) / 1.05);
    lines.forEach((ln, i) => {
      const a = easeOut(clamp((t - i * 0.18) / 0.45));
      const y = H * 0.5 + (i - (lines.length - 1) / 2) * size * 1.02 + size * 0.36;
      c.save();
      c.beginPath();
      c.rect(0, y - size * 0.95, W, size * 1.08);
      c.clip();
      c.font = `900 ${size}px ${SANS}`;
      const w = c.measureText(ln).width;
      c.fillStyle = pal[i % pal.length]!;
      c.fillText(ln, (W - w) / 2, y + (1 - a) * size * 1.1 + out * size * 1.1);
      c.restore();
    });
    return;
  }
  if (style === "warp") {
    // Letters grow along the word with a perspective skew (comic "ANIMATION" style).
    const ln = lines.join(" ");
    const n = [...ln].length;
    let size = W * 0.2;
    c.font = `900 ${size}px ${SANS}`;
    // Fit the whole warped word inside 88% of the width.
    const est = [...ln].reduce((sum, ch, i) => sum + c.measureText(ch).width * lerp(1.5, 0.55, i / Math.max(1, n - 1)) * 0.92, 0);
    size *= Math.min(1, (W * 0.88) / est);
    c.font = `900 ${size}px ${SANS}`;
    let x = W * 0.06;
    const y = H * 0.5;
    [...ln].forEach((ch, i) => {
      const a = backOut(clamp((t - i * 0.05) / 0.35), 2.2);
      if (a <= 0) return;
      const k = lerp(1.5, 0.55, i / Math.max(1, n - 1));
      const cw = c.measureText(ch).width * k * 0.92;
      c.save();
      c.translate(x + cw / 2, y + (1 - k) * size * -0.5);
      c.transform(1, 0, -0.18, 1, 0, 0);
      c.rotate((-8 + i * 1.2) * (Math.PI / 180));
      c.scale(k * a * (1 - out), k * a * (1 - out));
      c.fillStyle = pal[0]!;
      c.fillText(ch, -c.measureText(ch).width / 2, size * 0.35);
      c.restore();
      x += cw;
    });
    return;
  }
  if (style === "split") {
    // Each line in two halves that slide in from opposite sides and lock together.
    const size = Math.min(W * 0.17, (H * 0.6) / Math.max(1, lines.length));
    lines.forEach((ln, i) => {
      c.font = `900 ${size}px ${SANS}`;
      const w = c.measureText(ln).width;
      const y = H * 0.5 + (i - (lines.length - 1) / 2) * size * 1.05 + size * 0.36;
      const a = easeInOut(clamp((t - i * 0.15) / 0.6));
      for (const half of [0, 1]) {
        c.save();
        c.beginPath();
        c.rect(0, half ? y - size * 0.36 : y - size, W, size * 0.64);
        c.clip();
        c.fillStyle = half ? pal[1 % pal.length]! : pal[0]!;
        c.fillText(ln, (W - w) / 2 + (1 - a) * W * (half ? 1 : -1) * 0.8, y);
        c.restore();
      }
    });
    return;
  }
  if (style === "typewriter") {
    const ln = lines.join("\n");
    const size = W * 0.075;
    c.font = `600 ${size}px "Courier New", monospace`;
    const shown = Math.floor(clamp(t / Math.max(0.5, dur * 0.7)) * ln.length);
    const rows = ln.slice(0, shown).split("\n");
    rows.forEach((r, i) => {
      c.fillStyle = pal[0]!;
      c.fillText(r, W * 0.1, H * 0.42 + i * size * 1.3);
    });
    if (Math.floor(t * 2) % 2 === 0) {
      const last = rows[rows.length - 1] ?? "";
      c.fillRect(W * 0.1 + c.measureText(last).width + 4, H * 0.42 + (rows.length - 1) * size * 1.3 - size * 0.8, size * 0.5, size * 0.95);
    }
    return;
  }
  // slide: words slide up one after another with a mixed serif accent on the last word.
  const words = lines.join(" ").split(" ");
  const size = (p.size ?? 0.13) * W;
  let y = (p.y ?? 0.4) * H;
  words.forEach((w, i) => {
    const a = easeOut(clamp((t - i * 0.2) / 0.4));
    const last = i === words.length - 1;
    c.font = last ? `italic 400 ${size * 1.3}px ${SERIF}` : `900 ${size}px ${SANS}`;
    const ww = c.measureText(w).width;
    c.globalAlpha = a * (1 - out);
    c.fillStyle = last ? pal[1 % pal.length]! : pal[0]!;
    c.fillText(w, (W - ww) / 2, y + (1 - a) * size * 0.6);
    y += size * 1.05;
  });
  c.globalAlpha = 1;
  void K;
}

/* ------------------------------ motion graphics ------------------------------ */

function mograph(c: Ctx, p: ProcSpec, s: AnimScene, t: number, dur: number) {
  const W = s.width;
  const H = s.height;
  const cx = (p.x ?? 0.5) * W;
  const cy = (p.y ?? 0.5) * H;
  const R = (p.size ?? 0.32) * Math.min(W, H);
  const pal = p.palette ?? ["#ff4a2e", "#2f3cff", "#ffd23f", "#f6b5d0", "#111111", "#f4efe6"];
  if (p.bg) {
    c.fillStyle = p.bg;
    c.fillRect(0, 0, W, H);
  }
  switch (p.style) {
    case "ball": {
      // Squash and stretch bounce with a dotted motion trail.
      const floor = cy + R * 0.9;
      const period = 0.9;
      const pos = (tt: number) => {
        const ph = (tt % period) / period;
        const hgt = Math.pow(0.86, Math.floor(tt / period)) * R * 1.8;
        const y = floor - (1 - Math.pow(2 * ph - 1, 2)) * hgt;
        const x = cx - R * 1.2 + ((tt / dur) * R * 2.4);
        return { x, y, ph };
      };
      c.strokeStyle = rgba(pal[5]!, 0.6);
      c.lineWidth = Math.max(2, R * 0.012);
      c.beginPath();
      c.moveTo(cx - R * 1.5, floor + R * 0.18);
      c.lineTo(cx + R * 1.5, floor + R * 0.18);
      c.stroke();
      for (let k = 1; k <= 14; k++) {
        const q = pos(Math.max(0, t - k * 0.035));
        c.fillStyle = rgba(pal[5]!, 0.7 * (1 - k / 14));
        c.beginPath();
        c.arc(q.x, q.y, R * 0.035, 0, Math.PI * 2);
        c.fill();
      }
      const q = pos(t);
      const near = Math.min(q.ph, 1 - q.ph);
      const squash = near < 0.06 ? 1 - (0.06 - near) * 6 : 1 + Math.min(0.25, (0.5 - Math.abs(q.ph - 0.5)) * 0.2) * 0;
      const speed = Math.abs(2 * q.ph - 1);
      const sx = near < 0.06 ? 1 + (0.06 - near) * 7 : 1 - speed * 0.12;
      const sy = near < 0.06 ? squash : 1 + speed * 0.2;
      c.save();
      c.translate(q.x, Math.min(q.y, floor + R * 0.18 - R * 0.26 * sy));
      c.scale(sx, sy);
      c.fillStyle = pal[0]!;
      c.beginPath();
      c.arc(0, 0, R * 0.26, 0, Math.PI * 2);
      c.fill();
      c.restore();
      break;
    }
    case "morph": {
      // Circle → star → rounded square → blob, colour shifts between shapes.
      const shapes = 4;
      const ph = (t / Math.max(0.5, dur)) * shapes;
      const i = Math.floor(ph) % shapes;
      const a = easeInOut(ph - Math.floor(ph));
      const radius = (k: number, ang: number) => {
        switch (k) {
          case 0:
            return 1;
          case 1:
            return 0.62 + 0.38 * Math.pow(Math.abs(Math.cos(ang * 2.5)), 6);
          case 2: {
            const q = Math.PI / 4;
            return Math.min(1.25, 0.86 / Math.max(Math.abs(Math.cos(ang)), Math.abs(Math.sin(ang)))) * (Math.abs(Math.cos(((ang + q) % (Math.PI / 2)) - q)) > 0.9 ? 0.95 : 1);
          }
          default:
            return 0.85 + 0.15 * Math.sin(ang * 3 + t * 2) + 0.06 * Math.sin(ang * 5 - t * 3);
        }
      };
      c.save();
      c.translate(cx, cy);
      c.rotate(t * 0.6);
      c.fillStyle = "rgba(0,0,0,0.18)";
      c.beginPath();
      for (let k = 0; k <= 160; k++) {
        const ang = (k / 160) * Math.PI * 2;
        const r = R * lerp(radius(i, ang), radius((i + 1) % shapes, ang), a);
        const px = Math.cos(ang) * r + R * 0.06;
        const py = Math.sin(ang) * r + R * 0.08;
        if (k) c.lineTo(px, py);
        else c.moveTo(px, py);
      }
      c.fill();
      c.beginPath();
      for (let k = 0; k <= 160; k++) {
        const ang = (k / 160) * Math.PI * 2;
        const r = R * lerp(radius(i, ang), radius((i + 1) % shapes, ang), a);
        if (k) c.lineTo(Math.cos(ang) * r, Math.sin(ang) * r);
        else c.moveTo(Math.cos(ang) * r, Math.sin(ang) * r);
      }
      c.fillStyle = pal[i % 3]!;
      c.fill();
      c.restore();
      break;
    }
    case "halftone": {
      // Dot grid with a radial wave; red core.
      const gap = Math.max(10, R * 0.07);
      for (let y = gap / 2; y < H; y += gap)
        for (let x = gap / 2; x < W; x += gap) {
          const d = Math.hypot(x - cx, y - cy) / R;
          const wave = Math.sin(d * 5 - t * 4) * 0.5 + 0.5;
          const ring = clamp(1.25 - Math.abs(d - 0.7) * 1.6);
          const r = gap * 0.46 * clamp(ring * (0.45 + 0.55 * wave) + (d < 0.32 ? 0.55 : 0));
          if (r < 0.6) {
            c.fillStyle = "rgba(255,255,255,0.18)";
            c.fillRect(x, y, 1.5, 1.5);
            continue;
          }
          c.fillStyle = d < 0.35 ? pal[0]! : "#ffffff";
          c.beginPath();
          c.arc(x, y, r, 0, Math.PI * 2);
          c.fill();
        }
      break;
    }
    case "cube": {
      // Rotating cube with coloured faces and thick black edges (painter's sort).
      const ax = 0.55 + Math.sin(t * 0.7) * 0.2;
      const ay = t * 0.9;
      const verts = [-1, 1].flatMap((x) => [-1, 1].flatMap((y) => [-1, 1].map((z) => [x, y, z] as [number, number, number])));
      const rotP = ([x, y, z]: [number, number, number]) => {
        let X = x * Math.cos(ay) + z * Math.sin(ay);
        let Z = -x * Math.sin(ay) + z * Math.cos(ay);
        const Y = y * Math.cos(ax) - Z * Math.sin(ax);
        Z = y * Math.sin(ax) + Z * Math.cos(ax);
        const pz = 4 / (4 + Z);
        X *= pz;
        return [cx + X * R * 0.62, cy + Y * pz * R * 0.62, Z] as [number, number, number];
      };
      const P = verts.map(rotP);
      const faces: [number[], string][] = [
        [[0, 1, 3, 2], pal[0]!],
        [[4, 5, 7, 6], pal[2]!],
        [[0, 1, 5, 4], pal[3]!],
        [[2, 3, 7, 6], pal[5]!],
        [[0, 2, 6, 4], pal[1]!],
        [[1, 3, 7, 5], pal[5]!],
      ];
      faces
        .map(([idx, col]) => ({ idx, col, z: idx.reduce((n, i) => n + P[i]![2], 0) / 4 }))
        .sort((a, b) => b.z - a.z)
        .forEach(({ idx, col }) => {
          c.beginPath();
          idx.forEach((i, k) => (k ? c.lineTo(P[i]![0], P[i]![1]) : c.moveTo(P[i]![0], P[i]![1])));
          c.closePath();
          c.fillStyle = col;
          c.fill();
          c.lineWidth = R * 0.05;
          c.lineJoin = "round";
          c.strokeStyle = "#111111";
          c.stroke();
          // Cross lines like a 2×2 puzzle cube.
          const m = (a: number, b: number) => [(P[a]![0] + P[b]![0]) / 2, (P[a]![1] + P[b]![1]) / 2];
          const [i0, i1, i2, i3] = idx as [number, number, number, number];
          const [a1, a2] = [m(i0, i1), m(i3, i2)];
          const [b1, b2] = [m(i1, i2), m(i0, i3)];
          c.lineWidth = R * 0.035;
          c.beginPath();
          c.moveTo(a1[0]!, a1[1]!);
          c.lineTo(a2[0]!, a2[1]!);
          c.moveTo(b1[0]!, b1[1]!);
          c.lineTo(b2[0]!, b2[1]!);
          c.stroke();
        });
      break;
    }
    case "counter": {
      // Pill toggle + big counting number + progress bar (UI widget).
      const v = Math.round((p.value ?? 100) * easeOut(clamp(t / Math.max(0.6, dur * 0.6))));
      const pw = R * 1.5;
      const ph = R * 0.62;
      c.fillStyle = pal[0]!;
      c.beginPath();
      c.roundRect(cx - pw * 0.9, cy - ph * 1.6, pw * 1.8, ph * 2.2, R * 0.1);
      c.fill();
      c.font = `900 ${R * 0.95}px ${SANS}`;
      c.fillStyle = "#ffffff";
      c.textAlign = "center";
      c.textBaseline = "middle";
      c.fillText(`${v}%`, cx, cy - ph * 0.55);
      c.fillStyle = "rgba(255,255,255,0.3)";
      c.fillRect(cx - pw * 0.75, cy + ph * 0.25, pw * 1.5, R * 0.03);
      c.fillStyle = "#ffffff";
      c.fillRect(cx - pw * 0.75, cy + ph * 0.25, pw * 1.5 * (v / Math.max(1, p.value ?? 100)), R * 0.03);
      break;
    }
    case "chart": {
      // Bars grow with stagger; an easing curve draws with a dot riding it.
      const n = 8;
      const bw = (R * 2.2) / n;
      const base = cy + R * 0.5;
      c.fillStyle = pal[1]!;
      c.beginPath();
      c.roundRect(cx - R * 1.3, cy - R * 1.0, R * 2.6, R * 1.75, R * 0.08);
      c.fill();
      for (let i = 0; i < n; i++) {
        const a = backOut(clamp((t - i * 0.08) / 0.6), 1.4);
        const hgt = R * (0.25 + (i / n) * 0.9 + hash(i) * 0.15) * a;
        c.fillStyle = i === n - 1 ? pal[2]! : "#ffffff";
        c.fillRect(cx - R * 1.1 + i * bw + bw * 0.15, base - hgt, bw * 0.7, hgt);
      }
      c.strokeStyle = "rgba(255,255,255,0.5)";
      c.lineWidth = 2;
      c.beginPath();
      c.moveTo(cx - R * 1.15, base);
      c.lineTo(cx + R * 1.15, base);
      c.stroke();
      break;
    }
    case "burst": {
      // Spiky star with offset colour plates (print misregistration look).
      const spikes = 14;
      const draw = (col: string, dx: number, dy: number, rot: number, k: number) => {
        c.save();
        c.translate(cx + dx, cy + dy);
        c.rotate(rot);
        c.beginPath();
        for (let i = 0; i <= spikes * 2; i++) {
          const ang = (i / (spikes * 2)) * Math.PI * 2;
          const r = (i % 2 ? 0.72 : 1) * R * k * (1 + (hash(i) - 0.5) * 0.12);
          if (i) c.lineTo(Math.cos(ang) * r, Math.sin(ang) * r);
          else c.moveTo(Math.cos(ang) * r, Math.sin(ang) * r);
        }
        c.closePath();
        c.fillStyle = col;
        c.fill();
        c.restore();
      };
      const pulse = backOut(clamp(t / 0.5), 2) * (1 + 0.04 * Math.sin(t * 6));
      draw(pal[1]!, R * 0.05, R * 0.04, t * 0.4, pulse);
      draw(pal[2]!, -R * 0.04, R * 0.05, t * 0.4 + 0.05, pulse);
      draw(pal[0]!, 0, 0, t * 0.4 + 0.02, pulse * 0.95);
      break;
    }
    case "toggle": {
      const on = Math.floor(t / 1.2) % 2 === 1;
      const k = easeInOut(clamp(((t % 1.2) - 0.0) / 0.35));
      const pos = on ? k : 1 - k;
      const w = R * 1.6;
      const h2 = R * 0.8;
      c.fillStyle = "#111111";
      c.beginPath();
      c.roundRect(cx - w / 2 - R * 0.08, cy - h2 / 2 - R * 0.08, w + R * 0.16, h2 + R * 0.16, h2);
      c.fill();
      c.fillStyle = on ? pal[0]! : "#3a3a3a";
      c.beginPath();
      c.roundRect(cx - w / 2, cy - h2 / 2, w, h2, h2 / 2);
      c.fill();
      c.fillStyle = "#ffffff";
      c.beginPath();
      c.arc(cx - w / 2 + h2 / 2 + (w - h2) * pos, cy, h2 * 0.4 * (1 + Math.sin(k * Math.PI) * 0.12), 0, Math.PI * 2);
      c.fill();
      break;
    }
  }
}

/* ------------------------- hand-drawn FX on twos ---------------------------- */

function fx(c: Ctx, p: ProcSpec, s: AnimScene, f: number) {
  const W = s.width;
  const H = s.height;
  const cx = (p.x ?? 0.5) * W;
  const cy = (p.y ?? 0.6) * H;
  const R = (p.size ?? 0.25) * Math.min(W, H);
  const ink = p.color ?? "#1a1410";
  const col = p.accent ?? (p.style === "fire" ? "#ff7a1a" : p.style === "splash" || p.style === "ripple" ? "#4fb3ff" : "#ffd23f");
  const two = Math.floor(f / 2); // animated on twos
  const t = (two * 2) / s.fps;
  const seed = (p.seed ?? 3) + two * 0.37;
  const boil = (i: number, a = 1) => (hash(seed * 13 + i) - 0.5) * R * 0.03 * a;
  c.lineJoin = "round";
  c.lineCap = "round";
  const lw = Math.max(2, R * 0.022);
  switch (p.style) {
    case "fire": {
      // Licking flame tongues: inner yellow, outer orange, ink outline; tongues rise and peel off.
      const tongues = 7;
      const shapeAt = (scale: number) => {
        c.beginPath();
        for (let k = 0; k <= 40; k++) {
          const ang = Math.PI + (k / 40) * Math.PI;
          const x = cx + Math.cos(ang) * R * 0.55 * scale;
          const lick = Math.max(0, Math.sin(k * (tongues / 40) * Math.PI + t * 7 + noise(k * 0.3 + t * 3, 5) * 2));
          const y = cy + Math.sin(ang) * R * 0.4 * scale - lick * R * 0.9 * scale * (0.6 + 0.4 * Math.sin(k + t * 5)) + boil(k);
          if (k) c.lineTo(x, y);
          else c.moveTo(x, y);
        }
        c.quadraticCurveTo(cx, cy + R * 0.35 * scale, cx - R * 0.55 * scale, cy);
        c.closePath();
      };
      shapeAt(1);
      c.fillStyle = col;
      c.fill();
      c.lineWidth = lw;
      c.strokeStyle = ink;
      c.stroke();
      shapeAt(0.55);
      c.fillStyle = "#ffd94a";
      c.fill();
      // Detached flame bits rising.
      for (let i = 0; i < 4; i++) {
        const ph = ((t * 1.6 + i * 0.27) % 1);
        const x = cx + (hash(i * 7.1) - 0.5) * R * 0.7 + Math.sin(t * 3 + i) * R * 0.06;
        const y = cy - R * (0.6 + ph * 0.9);
        const r = R * 0.07 * (1 - ph);
        c.beginPath();
        c.moveTo(x, y - r * 2);
        c.quadraticCurveTo(x + r, y, x, y + r);
        c.quadraticCurveTo(x - r, y, x, y - r * 2);
        c.fillStyle = col;
        c.fill();
        c.lineWidth = lw * 0.7;
        c.stroke();
      }
      break;
    }
    case "splash": {
      // Crown splash: rises, peaks, collapses into droplets (loops every 1.2 s).
      const ph = (t % 1.2) / 1.2;
      const up = Math.sin(clamp(ph / 0.7) * Math.PI);
      c.beginPath();
      const n = 9;
      c.moveTo(cx - R * 0.7, cy);
      for (let i = 0; i <= n; i++) {
        const x = cx - R * 0.7 + (i / n) * R * 1.4;
        const peak = i % 2 ? R * (0.55 + hash(i) * 0.35) * up : R * 0.12 * up;
        c.lineTo(x + boil(i), cy - peak + boil(i + 9));
      }
      c.lineTo(cx + R * 0.7, cy);
      c.closePath();
      c.fillStyle = col;
      c.fill();
      c.lineWidth = lw;
      c.strokeStyle = ink;
      c.stroke();
      for (let i = 0; i < 7; i++) {
        const a = -Math.PI / 2 + (hash(i * 3.1) - 0.5) * 2.2;
        const d = R * (0.4 + ph * 1.2) * (0.6 + hash(i) * 0.6);
        const x = cx + Math.cos(a) * d;
        const y = cy + Math.sin(a) * d + ph * ph * R * 1.4;
        c.beginPath();
        c.ellipse(x, y, R * 0.045, R * 0.06, a, 0, Math.PI * 2);
        c.fillStyle = col;
        c.fill();
        c.stroke();
      }
      c.beginPath();
      c.ellipse(cx, cy + R * 0.04, R * 0.9, R * 0.16, 0, 0, Math.PI * 2);
      c.lineWidth = lw * 0.8;
      c.stroke();
      break;
    }
    case "ripple": {
      // Expanding broken rings (the breaks get bigger as they spread).
      for (let i = 0; i < 4; i++) {
        const ph = ((t * 0.7 + i * 0.25) % 1);
        const r = R * (0.1 + ph * 1.2);
        const gaps = 3 + i;
        c.lineWidth = lw * (1 - ph * 0.7);
        c.strokeStyle = rgba(ink.length === 7 ? ink : "#1a1410", 1 - ph);
        for (let g = 0; g < gaps; g++) {
          const a0 = (g / gaps) * Math.PI * 2 + hash(i + g) * 0.4;
          const a1 = a0 + (Math.PI * 2) / gaps - (0.15 + ph * 0.5);
          c.beginPath();
          c.ellipse(cx, cy, r, r * 0.32, 0, a0, a1);
          c.stroke();
        }
      }
      break;
    }
    case "impact": {
      // Anime impact: jagged starburst + radiating strokes, flashes on contact then fades.
      const ph = clamp(t / 0.6);
      const k = backOut(clamp(ph * 2.5), 2.4) * (1 - Math.max(0, ph - 0.6) * 1.5);
      c.beginPath();
      const n = 12;
      for (let i = 0; i <= n * 2; i++) {
        const a = (i / (n * 2)) * Math.PI * 2;
        const r = (i % 2 ? 0.45 : 0.9 + hash(i + two) * 0.35) * R * k;
        if (i) c.lineTo(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
        else c.moveTo(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
      }
      c.closePath();
      c.fillStyle = col;
      c.fill();
      c.lineWidth = lw;
      c.strokeStyle = ink;
      c.stroke();
      c.fillStyle = "#ffffff";
      c.beginPath();
      c.arc(cx, cy, R * 0.3 * k, 0, Math.PI * 2);
      c.fill();
      for (let i = 0; i < 16; i++) {
        const a = hash(i * 1.9 + two) * Math.PI * 2;
        const r0 = R * (1.1 + ph * 0.6);
        c.beginPath();
        c.moveTo(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0);
        c.lineTo(cx + Math.cos(a) * (r0 + R * 0.5 * (1 - ph)), cy + Math.sin(a) * (r0 + R * 0.5 * (1 - ph)));
        c.lineWidth = lw * 1.2 * (1 - ph);
        c.stroke();
      }
      break;
    }
    case "speedlines": {
      // Radial anime speed lines with a calm centre (redrawn on twos).
      c.fillStyle = ink;
      c.globalAlpha = 0.85;
      for (let i = 0; i < 48; i++) {
        const a = hash(i * 2.3 + two * 0.11) * Math.PI * 2;
        const r0 = R * (1.6 + hash(i + two) * 1.2);
        const r1 = Math.hypot(W, H);
        const wdt = (0.002 + hash(i * 5.7) * 0.006) * Math.PI;
        c.beginPath();
        c.moveTo(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0);
        c.lineTo(cx + Math.cos(a - wdt) * r1, cy + Math.sin(a - wdt) * r1);
        c.lineTo(cx + Math.cos(a + wdt) * r1, cy + Math.sin(a + wdt) * r1);
        c.closePath();
        c.fill();
      }
      break;
    }
    case "sparkle": {
      for (let i = 0; i < 9; i++) {
        const ph = ((t * 1.1 + hash(i) ) % 1);
        const x = cx + (hash(i * 3.3) - 0.5) * R * 2.2;
        const y = cy + (hash(i * 7.7) - 0.5) * R * 1.6;
        const r = R * 0.16 * Math.sin(ph * Math.PI);
        c.beginPath();
        for (let k = 0; k < 8; k++) {
          const a = (k / 8) * Math.PI * 2;
          const rr = k % 2 ? r * 0.25 : r;
          if (k) c.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr);
          else c.moveTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr);
        }
        c.closePath();
        c.fillStyle = col;
        c.fill();
        c.lineWidth = lw * 0.6;
        c.strokeStyle = ink;
        c.stroke();
      }
      break;
    }
    case "shockwave":
    case "clash": {
      // Expanding ring with radial streaks; "clash" adds a white core flash and a second ring.
      const ph = clamp(t / Math.max(0.3, (s.frames / s.fps) * 0.8));
      const rings = p.style === "clash" ? [0, 0.18] : [0];
      for (const off of rings) {
        const q = clamp(ph - off);
        if (q <= 0) continue;
        const r = R * (0.2 + easeOut(q) * 2.6);
        c.save();
        c.globalAlpha = 1 - q;
        c.lineWidth = R * 0.12 * (1 - q) + lw;
        c.strokeStyle = col;
        c.beginPath();
        c.ellipse(cx, cy, r, r * 0.42, 0, 0, Math.PI * 2);
        c.stroke();
        c.lineWidth = lw;
        c.strokeStyle = "#ffffff";
        c.beginPath();
        c.ellipse(cx, cy, r * 0.96, r * 0.4, 0, 0, Math.PI * 2);
        c.stroke();
        c.restore();
      }
      c.save();
      c.globalAlpha = 1 - ph;
      c.strokeStyle = col;
      for (let i = 0; i < 24; i++) {
        const a = hash(i * 2.1 + seed) * Math.PI * 2;
        const r0 = R * (0.3 + ph * 1.4);
        const r1 = r0 + R * (0.4 + hash(i) * 0.8) * (1 - ph);
        c.lineWidth = lw * (1.5 - ph);
        c.beginPath();
        c.moveTo(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0 * 0.6);
        c.lineTo(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1 * 0.6);
        c.stroke();
      }
      if (p.style === "clash") {
        const g = c.createRadialGradient(cx, cy, 0, cx, cy, R * 1.4);
        g.addColorStop(0, `rgba(255,255,255,${1 - ph})`);
        g.addColorStop(0.4, rgba(col, 0.6 * (1 - ph)));
        g.addColorStop(1, "rgba(255,255,255,0)");
        c.globalAlpha = 1;
        c.fillStyle = g;
        c.fillRect(cx - R * 1.5, cy - R * 1.5, R * 3, R * 3);
      }
      c.restore();
      break;
    }
    case "slash": {
      // Crescent sword/energy arc that sweeps and fades.
      const ph = clamp(t / 0.35);
      const fade = 1 - clamp((t - 0.35) / 0.3);
      const a0 = -2.4 + ph * 0.4;
      const a1 = a0 + ph * 2.8;
      c.save();
      c.globalAlpha = fade;
      c.translate(cx, cy);
      c.rotate(((p.rot ?? -20) * Math.PI) / 180);
      c.beginPath();
      c.arc(0, 0, R, a0, a1);
      c.arc(R * 0.18, -R * 0.08, R * 0.88, a1, a0, true);
      c.closePath();
      const g = c.createLinearGradient(-R, 0, R, 0);
      g.addColorStop(0, rgba(col, 0));
      g.addColorStop(1, "#ffffff");
      c.fillStyle = g;
      c.fill();
      c.lineWidth = lw * 0.8;
      c.strokeStyle = col;
      c.stroke();
      c.restore();
      break;
    }
    case "debris": {
      // Chunks thrown up and falling with gravity, spinning.
      for (let i = 0; i < 16; i++) {
        const vx = (hash(i * 3.7 + (p.seed ?? 3)) - 0.5) * 2.4 * R;
        const vy = -(0.8 + hash(i * 1.3) * 1.6) * R;
        const x = cx + vx * t;
        const y = cy + vy * t + 2.6 * R * t * t;
        if (y > cy + R * 0.4) continue;
        const sz = R * (0.04 + hash(i * 9) * 0.07);
        c.save();
        c.translate(x, y);
        c.rotate(t * (4 + hash(i) * 8));
        c.beginPath();
        c.moveTo(-sz, -sz * 0.6);
        c.lineTo(sz * 0.8, -sz);
        c.lineTo(sz, sz * 0.7);
        c.lineTo(-sz * 0.6, sz);
        c.closePath();
        c.fillStyle = p.accent ?? "#4a4560";
        c.fill();
        c.lineWidth = lw * 0.6;
        c.strokeStyle = ink;
        c.stroke();
        c.restore();
      }
      break;
    }
    case "dust": {
      // Landing / skid dust puffs spreading sideways along the ground.
      const ph = clamp(t / 1.2);
      for (let i = 0; i < 10; i++) {
        const side = i % 2 ? 1 : -1;
        const x = cx + side * (0.2 + easeOut(ph) * (0.8 + hash(i) * 0.6)) * R;
        const y = cy - hash(i * 3) * R * 0.25 * ph - R * 0.05;
        const r = R * (0.12 + ph * 0.3) * (0.7 + hash(i * 5) * 0.6);
        c.globalAlpha = (1 - ph) * 0.9;
        c.beginPath();
        for (let k = 0; k <= 9; k++) {
          const a = (k / 9) * Math.PI * 2;
          const rr = r * (0.82 + hash(i * 10 + k + two) * 0.3);
          if (k) c.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr * 0.75);
          else c.moveTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr * 0.75);
        }
        c.closePath();
        c.fillStyle = p.accent ?? "#cfc8bd";
        c.fill();
        c.lineWidth = lw * 0.7;
        c.strokeStyle = ink;
        c.stroke();
      }
      c.globalAlpha = 1;
      break;
    }
    case "smoke": {
      for (let i = 0; i < 6; i++) {
        const ph = ((t * 0.5 + i / 6) % 1);
        const x = cx + Math.sin(i * 2 + t) * R * 0.2 + ph * R * 0.3;
        const y = cy - ph * R * 1.6;
        const r = R * (0.2 + ph * 0.45);
        c.beginPath();
        for (let k = 0; k <= 10; k++) {
          const a = (k / 10) * Math.PI * 2;
          const rr = r * (0.82 + hash(i * 10 + k + two) * 0.3);
          if (k) c.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr);
          else c.moveTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr);
        }
        c.closePath();
        c.globalAlpha = 1 - ph;
        c.fillStyle = p.accent ?? "#d9d6cf";
        c.fill();
        c.lineWidth = lw * 0.8;
        c.strokeStyle = ink;
        c.stroke();
        c.globalAlpha = 1;
      }
      break;
    }
  }
}

/* --------------------------------- overlays -------------------------------- */

function overlay(c: Ctx, p: ProcSpec, s: AnimScene, t: number, dur: number, f: number) {
  const W = s.width;
  const H = s.height;
  const seed = p.seed ?? 5;
  switch (p.style) {
    case "lightLeak": {
      // Warm blobs drifting across the frame (use screen blend).
      const cols = p.palette ?? ["#ff7a2e", "#ff3d6e", "#ffc46b"];
      for (let i = 0; i < 4; i++) {
        const x = W * (0.2 + 0.8 * noise(t * 0.25 + i * 3, seed));
        const y = H * (0.3 + 0.6 * noise(t * 0.2 + i * 5, seed + 2));
        const r = Math.max(W, H) * (0.35 + 0.2 * hash(i + seed));
        const g = c.createRadialGradient(x, y, 0, x, y, r);
        const a = 0.55 * (0.6 + 0.4 * Math.sin(t * 1.3 + i));
        g.addColorStop(0, rgba(cols[i % cols.length]!, a));
        g.addColorStop(1, rgba(cols[i % cols.length]!, 0));
        c.fillStyle = g;
        c.fillRect(0, 0, W, H);
      }
      break;
    }
    case "filmBurn": {
      // Burning edge that flares in, whites out the frame for a beat, then recedes.
      const ph = clamp(t / Math.max(0.2, dur));
      const k = Math.sin(ph * Math.PI);
      for (let i = 0; i < 6; i++) {
        const side = i % 2 ? W : 0;
        const y = H * hash(i * 3 + seed);
        const r = Math.max(W, H) * (0.3 + k * 0.9) * (0.6 + hash(i + seed) * 0.6);
        const g = c.createRadialGradient(side, y, 0, side, y, r);
        g.addColorStop(0, `rgba(255,250,230,${0.95 * k})`);
        g.addColorStop(0.25, `rgba(255,170,60,${0.8 * k})`);
        g.addColorStop(0.6, `rgba(200,50,10,${0.45 * k})`);
        g.addColorStop(1, "rgba(120,10,0,0)");
        c.fillStyle = g;
        c.fillRect(0, 0, W, H);
      }
      if (k > 0.85) {
        c.fillStyle = `rgba(255,245,225,${(k - 0.85) * 5})`;
        c.fillRect(0, 0, W, H);
      }
      break;
    }
    case "dust": {
      // Specks, hairs and vertical scratches that change every frame (old film).
      const fr = f;
      c.fillStyle = "rgba(255,255,255,0.7)";
      for (let i = 0; i < 26; i++) {
        const x = hash(fr * 7.1 + i) * W;
        const y = hash(fr * 3.7 + i * 2) * H;
        const r = 1 + hash(fr + i * 9) * 3.5;
        c.globalAlpha = 0.25 + hash(fr * 1.3 + i) * 0.5;
        c.beginPath();
        c.arc(x, y, r, 0, Math.PI * 2);
        c.fill();
      }
      c.strokeStyle = "rgba(20,15,10,0.6)";
      for (let i = 0; i < 3; i++) {
        if (hash(fr * 0.7 + i * 11) > 0.6) continue;
        const x = hash(fr * 0.9 + i) * W;
        c.globalAlpha = 0.4;
        c.lineWidth = 1 + hash(i + fr) * 1.5;
        c.beginPath();
        c.moveTo(x, 0);
        c.lineTo(x + (hash(fr + i) - 0.5) * 20, H);
        c.stroke();
      }
      if (hash(fr * 0.13) > 0.8) {
        const x = hash(fr * 3) * W;
        const y = hash(fr * 5) * H;
        c.globalAlpha = 0.5;
        c.lineWidth = 1.5;
        c.beginPath();
        c.moveTo(x, y);
        c.bezierCurveTo(x + 30, y + 10, x + 10, y + 50, x + 45, y + 70);
        c.stroke();
      }
      c.globalAlpha = 1;
      break;
    }
    case "vhs": {
      // Tracking band rolling down, noise lines, RGB fringe bars and a timestamp.
      const band = ((t * 0.35) % 1.3) * H - H * 0.15;
      const g = c.createLinearGradient(0, band - 60, 0, band + 60);
      g.addColorStop(0, "rgba(255,255,255,0)");
      g.addColorStop(0.5, "rgba(255,255,255,0.18)");
      g.addColorStop(1, "rgba(255,255,255,0)");
      c.fillStyle = g;
      c.fillRect(0, band - 60, W, 120);
      for (let i = 0; i < 18; i++) {
        const y = hash(f * 1.7 + i) * H;
        c.fillStyle = `rgba(255,255,255,${0.04 + hash(f + i) * 0.1})`;
        c.fillRect(0, y, W, 1 + hash(i * f) * 3);
      }
      for (let i = 0; i < 2; i++) {
        if (hash(f * 0.4 + i) > 0.25) continue;
        const y = hash(f * 2.3 + i) * H;
        c.fillStyle = "rgba(255,0,80,0.25)";
        c.fillRect(0, y, W, 6);
        c.fillStyle = "rgba(0,200,255,0.25)";
        c.fillRect(8, y + 4, W, 6);
      }
      c.font = `600 ${H * 0.028}px "Courier New", monospace`;
      c.fillStyle = "rgba(255,255,255,0.85)";
      c.shadowColor = "rgba(0,0,0,0.6)";
      c.shadowBlur = 6;
      c.fillText(Math.floor(t * 2) % 2 ? "● REC" : "  REC", W * 0.07, H * 0.07);
      const sec = Math.floor(t);
      c.fillText(`PLAY  00:${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`, W * 0.07, H * 0.94);
      c.shadowColor = "transparent";
      break;
    }
    case "stripe": {
      // A bold coloured band sweeps in, widens to cover the frame, then wipes away (cut transition).
      const col = p.color ?? "#e1121b";
      const ph = clamp(t / Math.max(0.2, dur));
      const y = (p.y ?? 0.42) * H;
      let top: number;
      let bot: number;
      if (ph < 0.35) {
        const k = easeOut(ph / 0.35);
        top = y - H * 0.035;
        bot = y + H * 0.035;
        c.fillStyle = col;
        c.fillRect(-W + k * W * 2, top, W, bot - top);
        c.fillRect(W - k * W * 2, top + H * 0.02, W, (bot - top) * 0.6);
        break;
      }
      const k = easeInOut((ph - 0.35) / 0.3);
      top = lerp(y - H * 0.035, -10, clamp(k));
      bot = lerp(y + H * 0.035, H + 10, clamp(k));
      if (ph > 0.65) {
        const k2 = easeInOut((ph - 0.65) / 0.35);
        top = lerp(-10, H + 10, k2);
      }
      c.fillStyle = col;
      c.fillRect(0, top, W, Math.max(0, bot - top));
      break;
    }
    case "rain": {
      // Slanted streaks falling fast, splashes on the ground line.
      c.strokeStyle = p.color ?? "rgba(190,200,255,0.45)";
      c.lineWidth = 2;
      for (let i = 0; i < 160; i++) {
        const sp = 1.6 + hash(i * 1.7) * 0.9;
        const x = ((hash(i * 3.1 + seed) * W * 1.3 + t * W * 0.25 * sp) % (W * 1.3)) - W * 0.15;
        const y = ((hash(i * 7.3 + seed) * H + t * H * 1.4 * sp) % H);
        const len = 30 + hash(i) * 50;
        c.globalAlpha = 0.25 + hash(i * 5) * 0.45;
        c.beginPath();
        c.moveTo(x, y);
        c.lineTo(x - len * 0.25, y - len);
        c.stroke();
      }
      c.globalAlpha = 1;
      break;
    }
    case "flash": {
      // A hard white (or coloured) flash that decays: hit sparks, lightning, cuts.
      const k = Math.pow(1 - clamp(t / Math.max(0.05, dur)), 2);
      c.fillStyle = rgba(p.color ?? "#ffffff", k);
      c.fillRect(0, 0, W, H);
      break;
    }
    case "impactFrame": {
      // Anime impact frame: black / white / red graphic burst for a few frames, alternating.
      const n = Math.floor(f / 2);
      const col = p.color ?? "#e1121b";
      const cx = (p.x ?? 0.5) * W;
      const cy = (p.y ?? 0.5) * H;
      c.fillStyle = n % 2 ? "#ffffff" : "#000000";
      c.fillRect(0, 0, W, H);
      c.fillStyle = n % 2 ? "#000000" : col;
      for (let i = 0; i < 40; i++) {
        const a = hash(i * 1.9 + n) * Math.PI * 2;
        const w2 = (0.01 + hash(i * 4.4) * 0.03) * Math.PI;
        const r1 = Math.hypot(W, H);
        c.beginPath();
        c.moveTo(cx, cy);
        c.lineTo(cx + Math.cos(a - w2) * r1, cy + Math.sin(a - w2) * r1);
        c.lineTo(cx + Math.cos(a + w2) * r1, cy + Math.sin(a + w2) * r1);
        c.closePath();
        c.fill();
      }
      break;
    }
    case "paper": {
      // Paper texture over everything (use multiply).
      c.fillStyle = "#f3ead8";
      c.fillRect(0, 0, W, H);
      for (let i = 0; i < 1400; i++) {
        c.fillStyle = `rgba(90,70,40,${hash(i + seed) * 0.08})`;
        c.fillRect(hash(i * 3.1) * W, hash(i * 5.3) * H, 1 + hash(i) * 3, 1 + hash(i * 2) * 3);
      }
      break;
    }
  }
}

/* ------------------------------ collage pieces ------------------------------ */

function jitterAt(t: number, rate: number, seed: number) {
  const step = Math.floor(t * rate);
  return { dx: (hash(seed + step) - 0.5) * 6, dy: (hash(seed + step * 1.7) - 0.5) * 6, dr: (hash(seed + step * 2.3) - 0.5) * 0.012 };
}

function enterK(p: ProcSpec, t: number) {
  return p.enter === "none" ? 1 : backOut(clamp(t / 0.45), 1.5);
}

function cutout(c: Ctx, p: ProcSpec, s: AnimScene, t: number, f: number, images?: ImageLookup) {
  const img = p.assetId && images ? images(p.assetId) : null;
  const W = s.width;
  const H = s.height;
  const w = (p.w ?? 0.62) * W;
  const iw = img ? ((img as { width: number }).width || 1) : 1;
  const ih = img ? ((img as { height: number }).height || 1) : 1;
  const h = (w * ih) / iw;
  const seed = p.seed ?? 11;
  const j = jitterAt(t, p.boil ?? 8, seed);
  const k = enterK(p, t);
  const cx = (p.x ?? 0.5) * W;
  const cy = (p.y ?? 0.5) * H;
  c.translate(cx + j.dx + (p.enter === "slide" ? (1 - k) * W : 0), cy + j.dy + (p.enter === "drop" ? (k - 1) * H * 0.6 : 0));
  c.rotate(((p.rot ?? (hash(seed) - 0.5) * 6) * Math.PI) / 180 + j.dr);
  if (p.enter === "pop" || !p.enter) c.scale(Math.max(0.001, k), Math.max(0.001, k));
  const border = Math.max(8, w * 0.025);
  // Shadow + white torn border.
  tornRect(c, -w / 2 - border, -h / 2 - border, w + border * 2, h + border * 2, seed, border * 0.9);
  c.save();
  c.shadowColor = "rgba(0,0,0,0.45)";
  c.shadowBlur = border * 2;
  c.shadowOffsetY = border * 0.8;
  c.fillStyle = p.bg ?? "#fbf8f1";
  c.fill();
  c.restore();
  if (img) {
    c.save();
    tornRect(c, -w / 2, -h / 2, w, h, seed + 7, border * 0.5);
    c.clip();
    c.drawImage(img, -w / 2, -h / 2, w, h);
    // Printed look: slight warm tint and halftone grain.
    c.fillStyle = "rgba(255,230,190,0.08)";
    c.fillRect(-w / 2, -h / 2, w, h);
    c.restore();
  } else {
    c.fillStyle = "#cfc6b4";
    c.fillRect(-w / 2, -h / 2, w, h);
  }
  void f;
}

function label(c: Ctx, p: ProcSpec, s: AnimScene, t: number, f: number) {
  const W = s.width;
  const H = s.height;
  const K = H / 1920;
  const size = (p.size ?? 64) * K;
  const text = p.text ?? "label";
  c.font = `800 ${size}px ${SANS}`;
  const tw = c.measureText(text).width;
  const seed = p.seed ?? 21;
  const j = jitterAt(t, p.boil ?? 8, seed);
  const k = enterK(p, t);
  c.translate((p.x ?? 0.5) * W + j.dx + (p.enter === "slide" ? (1 - k) * -W : 0), (p.y ?? 0.5) * H + j.dy);
  c.rotate(((p.rot ?? (hash(seed) - 0.5) * 8) * Math.PI) / 180 + j.dr);
  if (p.enter !== "slide" && p.enter !== "none") c.scale(Math.max(0.001, k), Math.max(0.001, k));
  const pw = tw + size * 0.9;
  const ph = size * 1.45;
  tornRect(c, -pw / 2, -ph / 2, pw, ph, seed, size * 0.14);
  c.save();
  c.shadowColor = "rgba(0,0,0,0.35)";
  c.shadowBlur = size * 0.3;
  c.shadowOffsetY = size * 0.08;
  paperFill(c, -pw / 2, -ph / 2, pw, ph, p.bg ?? "#e8542f", seed);
  c.restore();
  c.fillStyle = p.color ?? "#ffffff";
  c.textAlign = "center";
  c.textBaseline = "middle";
  c.fillText(text, 0, size * 0.04);
  void f;
}

function paper(c: Ctx, p: ProcSpec, s: AnimScene) {
  const W = s.width;
  const H = s.height;
  const tone = p.style || "cream";
  const base = tone === "news" ? "#e9e3d3" : tone === "kraft" ? "#c9a77a" : tone === "dark" ? "#1c1b1d" : "#efe6d2";
  c.fillStyle = base;
  c.fillRect(0, 0, W, H);
  const seed = p.seed ?? 4;
  for (let i = 0; i < 70; i++) {
    const x = hash(seed + i) * W;
    const y = hash(seed + i * 3) * H;
    const r = Math.max(W, H) * (0.1 + hash(i * 7) * 0.3);
    const g = c.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, tone === "dark" ? "rgba(255,255,255,0.025)" : "rgba(60,40,10,0.05)");
    g.addColorStop(1, "rgba(0,0,0,0)");
    c.fillStyle = g;
    c.fillRect(x - r, y - r, r * 2, r * 2);
  }
  if (tone === "news") {
    // Faint columns of newsprint.
    c.fillStyle = "rgba(40,35,30,0.13)";
    const colW = W / 4;
    for (let col = 0; col < 4; col++)
      for (let y = H * 0.05; y < H * 0.95; y += 14) {
        if (hash(col * 999 + y) < 0.06) continue;
        const len = colW * (0.6 + hash(y + col) * 0.32);
        c.fillRect(col * colW + colW * 0.06, y, len, 5);
      }
    c.fillStyle = "rgba(40,35,30,0.35)";
    c.font = `700 ${W * 0.07}px ${SERIF}`;
    c.textAlign = "center";
    c.fillText("The Daily Chronicle", W / 2, H * 0.04 + W * 0.06);
  }
  // Grain.
  for (let i = 0; i < 2500; i++) {
    c.fillStyle = tone === "dark" ? `rgba(255,255,255,${hash(i) * 0.05})` : `rgba(50,35,15,${hash(i) * 0.06})`;
    c.fillRect(hash(i * 3.3 + seed) * W, hash(i * 7.1 + seed) * H, 1.5, 1.5);
  }
}
