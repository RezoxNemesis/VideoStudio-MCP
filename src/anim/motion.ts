/**
 * Premium motion design: glowing dots and sparkles, glass/glow pills, morphing frames, drawn paths with a
 * travelling light, dot rows with a sweeping arc, particle rings, starfields, soft blur-in type, brush titles, and
 * cinematic atmosphere (fog, embers, eclipse corona, god rays) — over themed living backgrounds with grain and a
 * moving camera. One spec draws a whole sequence; `buildPromo` writes a spec from a few lines of text.
 */
import { fontStack } from "../render/fonts";

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;

export type MotionKind =
  | "dot"
  | "spark"
  | "pill"
  | "frame"
  | "path"
  | "dots"
  | "ring"
  | "stars"
  | "title"
  | "logo"
  | "line"
  | "eclipse"
  | "fog"
  | "embers"
  | "rays"
  | "brush"
  | "flash";

export interface MotionEl {
  k: MotionKind;
  /** Local seconds: appears at t0, leaves at t1 (fades/scales out over the last `out` s). */
  t0: number;
  t1?: number;
  out?: number;
  x?: number;
  y?: number;
  /** Size as a fraction of the shorter side. */
  s?: number;
  text?: string;
  sub?: string;
  /** Points (fractions) for paths / travelling dots. */
  pts?: [number, number][];
  n?: number;
  color?: string;
  icon?: "spark" | "bar" | "dot" | "none";
  /** pill: "solid" (gradient fill) | "outline" (dark glass with glowing edge). */
  variant?: string;
  font?: string;
  rot?: number;
  seed?: number;
}

export interface MotionCamKey {
  t: number;
  z?: number;
  x?: number;
  y?: number;
  r?: number;
}

export interface MotionSpec {
  theme: string;
  els: MotionEl[];
  cam?: MotionCamKey[];
  /** Draw the themed background (false = transparent, to put over footage). */
  bg?: boolean;
  grain?: number;
}

export interface Theme {
  base: string;
  blobs: string[];
  accent: string;
  accent2: string;
  text: string;
  sub: string;
  glow: string;
  light?: boolean;
  streaks?: string;
  font: string;
}

export const THEMES: Record<string, Theme> = {
  ember: { base: "#0b0605", blobs: ["#3a0d05", "#5a1a06", "#1a0604"], accent: "#ff7a1a", accent2: "#ffb347", text: "#fff3e6", sub: "#d9b9a0", glow: "#ff8a2a", font: "Inter" },
  cream: { base: "#fbf7f0", blobs: ["#fff1c9", "#ffe7b0", "#fff8e8"], accent: "#ff7a1a", accent2: "#ffb347", text: "#ffffff", sub: "#7a5a40", glow: "#ffb04a", light: true, streaks: "#ffd76a", font: "Inter" },
  mono: { base: "#04060b", blobs: ["#0d1424", "#101a30", "#060a14"], accent: "#ffffff", accent2: "#9fb4ff", text: "#ffffff", sub: "#8a95ad", glow: "#cfe0ff", font: "Space Grotesk" },
  eclipse: { base: "#050304", blobs: ["#2a0508", "#140306", "#1c0a0c"], accent: "#ff1f3d", accent2: "#ff6a3d", text: "#ffe9ea", sub: "#b98c90", glow: "#ff2a45", font: "Playfair Display" },
  aurora: { base: "#05060f", blobs: ["#1b0f3a", "#0c2e3a", "#2a0f33"], accent: "#7cf5d0", accent2: "#a98bff", text: "#f1f5ff", sub: "#9ea8c8", glow: "#8ef0ff", font: "Syne" },
  gold: { base: "#0a0806", blobs: ["#2b2110", "#1a140a", "#3a2a10"], accent: "#f2c46b", accent2: "#fff1c4", text: "#fff8ea", sub: "#bfa98a", glow: "#ffd98a", font: "Playfair Display" },
};

/* --------------------------------- helpers -------------------------------- */

const clamp = (v: number, a = 0, b = 1) => Math.max(a, Math.min(b, v));
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
const easeOutExpo = (t: number) => (t >= 1 ? 1 : 1 - Math.pow(2, -10 * t));
const easeInOut = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
/** Damped spring 0→1 with a little overshoot. */
const spring = (t: number, k = 7, d = 0.55) => (t <= 0 ? 0 : 1 - Math.exp(-k * d * t) * Math.cos(k * Math.sqrt(1 - d * d) * t));
function hash(n: number): number {
  const s = Math.sin(n * 127.1 + 311.7) * 43758.5453;
  return s - Math.floor(s);
}
function noise(x: number, y = 0): number {
  const xi = Math.floor(x),
    yi = Math.floor(y);
  const xf = x - xi,
    yf = y - yi;
  const u = xf * xf * (3 - 2 * xf),
    v = yf * yf * (3 - 2 * yf);
  const h = (a: number, b: number) => hash(a * 57 + b * 131);
  return lerp(lerp(h(xi, yi), h(xi + 1, yi), u), lerp(h(xi, yi + 1), h(xi + 1, yi + 1), u), v);
}
function rgba(hex: string, a: number): string {
  const h = hex.replace("#", "");
  const n = parseInt(h.length === 3 ? h.split("").map((c) => c + c).join("") : h.slice(0, 6), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${clamp(a)})`;
}
function rr(c: Ctx, x: number, y: number, w: number, h: number, r: number) {
  const q = Math.min(r, w / 2, h / 2);
  c.beginPath();
  c.moveTo(x + q, y);
  c.arcTo(x + w, y, x + w, y + h, q);
  c.arcTo(x + w, y + h, x, y + h, q);
  c.arcTo(x, y + h, x, y, q);
  c.arcTo(x, y, x + w, y, q);
  c.closePath();
}
/** Catmull-Rom point on a polyline (fractions → px) at progress p. */
function along(pts: [number, number][], p: number, W: number, H: number): [number, number] {
  if (pts.length === 1) return [pts[0]![0] * W, pts[0]![1] * H];
  const n = pts.length - 1;
  const f = clamp(p) * n;
  const i = Math.min(n - 1, Math.floor(f));
  const t = f - i;
  const P = (k: number) => pts[clamp(k, 0, n)]!;
  const p0 = P(i - 1),
    p1 = P(i),
    p2 = P(i + 1),
    p3 = P(i + 2);
  const cr = (a: number, b: number, c2: number, d: number) => 0.5 * (2 * b + (-a + c2) * t + (2 * a - 5 * b + 4 * c2 - d) * t * t + (-a + 3 * b - 3 * c2 + d) * t * t * t);
  return [cr(p0[0], p1[0], p2[0], p3[0]) * W, cr(p0[1], p1[1], p2[1], p3[1]) * H];
}

let grainTile: OffscreenCanvas | HTMLCanvasElement | null = null;
function grain(): OffscreenCanvas | HTMLCanvasElement {
  if (grainTile) return grainTile;
  const N = 256;
  const c = typeof OffscreenCanvas !== "undefined" ? new OffscreenCanvas(N, N) : Object.assign(document.createElement("canvas"), { width: N, height: N });
  const x = c.getContext("2d") as Ctx;
  const d = x.createImageData(N, N);
  for (let i = 0; i < N * N; i++) {
    const v = Math.floor(hash(i * 1.37) * 255);
    d.data[i * 4] = d.data[i * 4 + 1] = d.data[i * 4 + 2] = v;
    d.data[i * 4 + 3] = 255;
  }
  x.putImageData(d, 0, 0);
  grainTile = c;
  return c;
}

/* ------------------------------- background -------------------------------- */

function background(c: Ctx, th: Theme, W: number, H: number, t: number) {
  c.fillStyle = th.base;
  c.fillRect(0, 0, W, H);
  const M = Math.max(W, H);
  th.blobs.forEach((col, i) => {
    const bx = (0.5 + 0.38 * Math.sin(t * 0.13 + i * 2.1)) * W;
    const by = (0.5 + 0.34 * Math.cos(t * 0.11 + i * 1.7)) * H;
    const r = M * (0.55 + 0.12 * Math.sin(t * 0.2 + i));
    const g = c.createRadialGradient(bx, by, 0, bx, by, r);
    g.addColorStop(0, rgba(col, th.light ? 0.9 : 0.85));
    g.addColorStop(1, rgba(col, 0));
    c.fillStyle = g;
    c.fillRect(0, 0, W, H);
  });
  if (th.streaks) {
    // Soft diagonal light streaks (bright themes).
    c.save();
    c.globalCompositeOperation = "multiply";
    for (let i = 0; i < 7; i++) {
      const x0 = (hash(i + 3) * 1.4 - 0.2) * W + Math.sin(t * 0.3 + i) * W * 0.03;
      c.strokeStyle = rgba(th.streaks, 0.18 + 0.12 * hash(i + 9));
      c.lineWidth = M * (0.01 + 0.03 * hash(i + 5));
      c.filter = `blur(${Math.round(M * 0.012)}px)`;
      c.beginPath();
      c.moveTo(x0, -H * 0.1);
      c.bezierCurveTo(x0 + W * 0.15, H * 0.3, x0 - W * 0.1, H * 0.6, x0 + W * 0.2, H * 1.1);
      c.stroke();
    }
    c.restore();
  }
}

function finish(c: Ctx, th: Theme, W: number, H: number, t: number, grainAmt: number) {
  // Vignette.
  const g = c.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.35, W / 2, H / 2, Math.max(W, H) * 0.75);
  g.addColorStop(0, "rgba(0,0,0,0)");
  g.addColorStop(1, th.light ? "rgba(120,80,40,0.12)" : "rgba(0,0,0,0.55)");
  c.fillStyle = g;
  c.fillRect(0, 0, W, H);
  if (grainAmt > 0) {
    c.save();
    c.globalAlpha = grainAmt;
    c.globalCompositeOperation = "overlay";
    const tile = grain();
    const ox = Math.floor(hash(Math.floor(t * 24)) * 256),
      oy = Math.floor(hash(Math.floor(t * 24) + 7) * 256);
    c.translate(-ox, -oy);
    c.fillStyle = c.createPattern(tile as CanvasImageSource, "repeat")!;
    c.fillRect(0, 0, W + 256, H + 256);
    c.restore();
  }
}

/* --------------------------------- elements -------------------------------- */

function glowDot(c: Ctx, x: number, y: number, r: number, col: string, a = 1) {
  const g = c.createRadialGradient(x, y, 0, x, y, r * 7);
  g.addColorStop(0, rgba(col, 0.55 * a));
  g.addColorStop(0.25, rgba(col, 0.18 * a));
  g.addColorStop(1, rgba(col, 0));
  c.fillStyle = g;
  c.beginPath();
  c.arc(x, y, r * 7, 0, Math.PI * 2);
  c.fill();
  c.fillStyle = `rgba(255,255,255,${a})`;
  c.beginPath();
  c.arc(x, y, r, 0, Math.PI * 2);
  c.fill();
}

function sparkPath(c: Ctx, x: number, y: number, R: number, rot: number) {
  c.beginPath();
  for (let i = 0; i < 4; i++) {
    const a = rot + (i * Math.PI) / 2;
    const tip = [x + Math.cos(a) * R, y + Math.sin(a) * R];
    const nx = rot + ((i + 1) * Math.PI) / 2;
    const ctl = [x + Math.cos(a + Math.PI / 4) * R * 0.12, y + Math.sin(a + Math.PI / 4) * R * 0.12];
    if (i === 0) c.moveTo(tip[0]!, tip[1]!);
    c.quadraticCurveTo(ctl[0]!, ctl[1]!, x + Math.cos(nx) * R, y + Math.sin(nx) * R);
  }
  c.closePath();
}

function textBlurIn(c: Ctx, text: string, x: number, y: number, size: number, u: number, col: string, font: string, weight = 500, align: CanvasTextAlign = "center", spacing = 0) {
  const words = text.split(/\s+/);
  c.font = `${weight} ${size}px ${fontStack(font)}`;
  c.textBaseline = "middle";
  (c as CanvasRenderingContext2D).letterSpacing = `${spacing}px`;
  const widths = words.map((w) => c.measureText(w).width);
  const sp = c.measureText(" ").width;
  const total = widths.reduce((a, b) => a + b, 0) + sp * (words.length - 1);
  let cx = align === "center" ? x - total / 2 : align === "right" ? x - total : x;
  c.textAlign = "left";
  words.forEach((w, i) => {
    const p = clamp((u - i * 0.08) / 0.55);
    const e = easeOutExpo(p);
    if (p > 0) {
      c.save();
      c.globalAlpha *= e;
      const b = (1 - e) * size * 0.35;
      if (b > 0.5) c.filter = `blur(${b.toFixed(1)}px)`;
      c.fillStyle = col;
      c.fillText(w, cx, y + (1 - e) * size * 0.35);
      c.restore();
    }
    cx += widths[i]! + sp;
  });
  (c as CanvasRenderingContext2D).letterSpacing = "0px";
  return total;
}

function drawEl(c: Ctx, el: MotionEl, th: Theme, W: number, H: number, t: number) {
  const u = t - el.t0;
  if (u < 0) return;
  const outDur = el.out ?? 0.4;
  const left = el.t1 !== undefined ? el.t1 - t : Infinity;
  if (left < 0) return;
  const outP = left < outDur ? 1 - left / outDur : 0;
  const S = Math.min(W, H);
  const x = (el.x ?? 0.5) * W,
    y = (el.y ?? 0.5) * H;
  const col = el.color ?? th.accent;
  const font = el.font ?? th.font;
  c.save();
  c.globalAlpha = 1 - easeInOut(outP);
  switch (el.k) {
    case "dot": {
      const r = (el.s ?? 0.012) * S;
      let px = x,
        py = y;
      if (el.pts?.length) [px, py] = along(el.pts, easeInOut(clamp(u / Math.max(0.3, (el.t1 ?? el.t0 + 2) - el.t0 - outDur))), W, H);
      const g = spring(u * 1.4);
      glowDot(c, px, py, r * (0.4 + 0.6 * g) * (1 + 0.08 * Math.sin(u * 4)), col, clamp(u * 3));
      break;
    }
    case "spark": {
      const R = (el.s ?? 0.07) * S;
      const g = spring(u * 1.3);
      const rot = (el.rot ?? 0) + (1 - clamp(u / 0.9)) * -Math.PI * 0.75 + u * 0.15;
      c.save();
      c.shadowColor = rgba(th.glow, 0.9);
      c.shadowBlur = R * 0.9;
      const gr = c.createLinearGradient(x - R, y - R, x + R, y + R);
      gr.addColorStop(0, "#fffbe8");
      gr.addColorStop(0.5, th.accent2);
      gr.addColorStop(1, col);
      c.fillStyle = gr;
      sparkPath(c, x, y, R * g * (1 + 0.04 * Math.sin(u * 5)), rot);
      c.fill();
      c.restore();
      break;
    }
    case "pill": {
      const h = (el.s ?? 0.085) * S;
      const fs = h * 0.36;
      c.font = `500 ${fs}px ${fontStack(font)}`;
      const tw = Math.max(c.measureText(el.text ?? "").width, el.sub ? c.measureText(el.sub).width * 0.8 : 0);
      const icon = el.icon ?? "spark";
      const full = tw + h * (icon === "none" ? 0.9 : 1.6);
      const grow = easeOutExpo(clamp((u - 0.05) / 0.7));
      const w = lerp(h, full, grow);
      const sc = 0.6 + 0.4 * spring(u * 1.6);
      c.translate(x, y);
      c.scale(sc, sc);
      const X = -w / 2,
        Y = -h / 2;
      if (el.variant === "outline") {
        c.save();
        c.shadowColor = rgba(th.glow, 0.85);
        c.shadowBlur = h * 0.45;
        const eg = c.createLinearGradient(X, 0, X + w, 0);
        eg.addColorStop(0, rgba(col, 1));
        eg.addColorStop(0.6, rgba(th.accent2, 0.8));
        eg.addColorStop(1, rgba(col, 0.15));
        c.strokeStyle = eg;
        c.lineWidth = Math.max(2, h * 0.045);
        rr(c, X, Y, w, h, h / 2);
        c.stroke();
        c.restore();
        c.fillStyle = rgba("#000000", 0.55);
        rr(c, X, Y, w, h, h / 2);
        c.fill();
      } else {
        c.save();
        c.shadowColor = rgba(th.glow, 0.7);
        c.shadowBlur = h * 0.5;
        const fg = c.createLinearGradient(X, Y, X + w, Y + h);
        fg.addColorStop(0, col);
        fg.addColorStop(1, th.accent2);
        c.fillStyle = fg;
        rr(c, X, Y, w, h, h / 2);
        c.fill();
        c.restore();
        // Glass highlight.
        const hl = c.createLinearGradient(0, Y, 0, Y + h);
        hl.addColorStop(0, "rgba(255,255,255,0.35)");
        hl.addColorStop(0.5, "rgba(255,255,255,0.02)");
        c.fillStyle = hl;
        rr(c, X + 2, Y + 2, w - 4, h * 0.55, h / 2);
        c.fill();
      }
      // Light sweep across the pill once it is open.
      const sw = clamp((u - 0.6) / 0.9);
      if (sw > 0 && sw < 1) {
        c.save();
        rr(c, X, Y, w, h, h / 2);
        c.clip();
        const sx = X + sw * (w + h * 2) - h;
        const sg = c.createLinearGradient(sx - h, 0, sx + h, 0);
        sg.addColorStop(0, "rgba(255,255,255,0)");
        sg.addColorStop(0.5, "rgba(255,255,255,0.35)");
        sg.addColorStop(1, "rgba(255,255,255,0)");
        c.fillStyle = sg;
        c.fillRect(X, Y, w, h);
        c.restore();
      }
      c.save();
      rr(c, X, Y, w, h, h / 2);
      c.clip();
      let tx = X + h * 0.45;
      if (icon !== "none") {
        const ix = X + h * 0.55;
        const ia = clamp((u - 0.25) / 0.4);
        c.globalAlpha *= ia;
        if (icon === "spark") {
          c.fillStyle = el.variant === "outline" ? col : "#fff7e8";
          sparkPath(c, ix, 0, h * 0.26, u * 0.4);
          c.fill();
        } else if (icon === "bar") {
          c.fillStyle = "#fff7e8";
          c.fillRect(ix - h * 0.03, -h * 0.2, h * 0.06, h * 0.4);
        } else {
          c.fillStyle = "#fff7e8";
          c.beginPath();
          c.arc(ix, 0, h * 0.09, 0, Math.PI * 2);
          c.fill();
        }
        c.globalAlpha /= ia || 1;
        tx = X + h * 1.05;
      }
      const tu = clamp((u - 0.35) / 1);
      const lum = (hx: string) => {
        const n = parseInt(hx.replace("#", "").slice(0, 6), 16);
        return (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
      };
      const textCol = el.variant === "outline" ? th.text : lum(col) > 0.72 ? "#0b0f1a" : "#ffffff";
      if (el.sub) {
        textBlurIn(c, el.text ?? "", tx, -h * 0.13, fs, tu * 1.4, textCol, font, 500, "left");
        textBlurIn(c, el.sub, tx, h * 0.2, fs * 0.72, (tu - 0.15) * 1.4, rgba(textCol, 0.7), font, 400, "left");
      } else textBlurIn(c, el.text ?? "", tx, 0, fs, tu * 1.4, textCol, font, 500, "left");
      c.restore();
      break;
    }
    case "frame": {
      const R = (el.s ?? 0.3) * S;
      const g = spring(u * 1.2);
      const rot = (el.rot ?? -0.18) * (1 - easeOutExpo(clamp(u / 1.6))) + Math.sin(u * 0.6) * 0.03 + (el.rot ?? 0) * 0.3;
      c.translate(x, y);
      c.rotate(rot);
      c.scale(g, g);
      const half = R / 2;
      // Glowing gradient edge drawn on.
      const draw = easeOutExpo(clamp(u / 1.1));
      c.save();
      c.shadowColor = rgba(th.glow, 1);
      c.shadowBlur = R * 0.18;
      c.lineWidth = R * 0.06;
      const eg = c.createLinearGradient(-half, -half, half, half);
      eg.addColorStop(0, rgba(th.accent2, 0.95));
      eg.addColorStop(0.5, rgba(col, 0.6));
      eg.addColorStop(1, rgba(th.accent2, 0.95));
      c.strokeStyle = eg;
      c.setLineDash([R * 4 * draw, R * 4]);
      rr(c, -half, -half, R, R, R * 0.12);
      c.stroke();
      c.restore();
      c.fillStyle = th.base;
      rr(c, -half + R * 0.03, -half + R * 0.03, R * 0.94, R * 0.94, R * 0.1);
      c.fill();
      // Corner arrows.
      const ca = clamp((u - 0.5) / 0.5);
      c.fillStyle = col;
      for (let i = 0; i < 4; i++) {
        c.save();
        c.rotate((i * Math.PI) / 2);
        c.translate(half * 1.04 + (1 - ca) * R * 0.2, half * 1.04 + (1 - ca) * R * 0.2);
        c.globalAlpha *= ca;
        c.beginPath();
        c.moveTo(0, 0);
        c.lineTo(-R * 0.09, -R * 0.03);
        c.lineTo(-R * 0.03, -R * 0.09);
        c.closePath();
        c.fill();
        c.restore();
      }
      if (el.text) textBlurIn(c, el.text, 0, 0, R * 0.08, u - 0.6, th.text, font, 400);
      break;
    }
    case "path": {
      const pts = el.pts ?? [
        [0.3, 0.9],
        [0.4, 0.6],
        [0.6, 0.5],
      ];
      const dur = Math.max(0.4, (el.t1 ?? el.t0 + 2) - el.t0 - outDur);
      const p = easeInOut(clamp(u / dur));
      c.strokeStyle = rgba(col, 0.75);
      c.lineWidth = Math.max(1.5, S * 0.003);
      c.beginPath();
      const N = 80;
      for (let i = 0; i <= N * p; i++) {
        const [px, py] = along(pts, i / N, W, H);
        if (i === 0) c.moveTo(px, py);
        else c.lineTo(px, py);
      }
      c.stroke();
      const [hx, hy] = along(pts, p, W, H);
      glowDot(c, hx, hy, S * 0.007, col);
      break;
    }
    case "dots": {
      const n = el.n ?? 5;
      const span = (el.s ?? 0.6) * W;
      const gap = span / (n - 1);
      const r = S * 0.011;
      const xs = Array.from({ length: n }, (_, i) => x - span / 2 + i * gap);
      xs.forEach((dx, i) => {
        const a = clamp((u - i * 0.12) / 0.3);
        if (a > 0) glowDot(c, dx, y, r * spring((u - i * 0.12) * 2), th.accent2, a);
      });
      // A glowing arc hops along the row.
      const hop = (u - n * 0.12 - 0.2) / 0.55;
      if (hop > 0) {
        const k = Math.min(n - 3, Math.floor(hop));
        const f = easeInOut(clamp(hop - Math.floor(hop)));
        const a0 = xs[Math.max(0, k)]!,
          a1 = xs[Math.min(n - 1, k + 2)]!;
        const mx = (a0 + a1) / 2,
          R = (a1 - a0) / 2;
        c.save();
        c.shadowColor = rgba(th.glow, 1);
        c.shadowBlur = S * 0.02;
        c.lineWidth = S * 0.006;
        c.lineCap = "round";
        const gr = c.createLinearGradient(a0, y, a1, y);
        gr.addColorStop(0, rgba(col, 0));
        gr.addColorStop(1, rgba(th.accent2, 1));
        c.strokeStyle = gr;
        c.beginPath();
        c.arc(mx, y, R, Math.PI, Math.PI + Math.PI * f);
        c.stroke();
        c.restore();
      }
      if (el.text) textBlurIn(c, el.text, x, y + S * 0.11, S * 0.055, u - 0.5, th.sub, font, 400);
      break;
    }
    case "ring": {
      // Dot-matrix ring: particles on a tilted torus, lit by angle, slowly turning.
      const R = (el.s ?? 0.22) * S;
      const n = el.n ?? 900;
      const g = easeOutExpo(clamp(u / 1.4));
      const turn = u * 0.35;
      for (let i = 0; i < n; i++) {
        const a = hash(i * 1.1) * Math.PI * 2 + turn;
        const tube = (hash(i * 2.3) - 0.5) * R * 0.5 * (0.6 + 0.4 * hash(i * 3.1));
        const rad = R + tube;
        const px = x + Math.cos(a) * rad * g;
        const py = y + Math.sin(a) * rad * 0.92 * g + Math.sin(a * 3 + u) * R * 0.02;
        const lit = 0.25 + 0.75 * Math.pow(0.5 + 0.5 * Math.cos(a - 2.3), 2);
        const tw = 0.6 + 0.4 * Math.sin(u * 3 + i);
        c.fillStyle = rgba(col, lit * tw * clamp(u * 2));
        const sz = Math.max(1, S * 0.0022 * (0.6 + hash(i * 5.7)));
        c.fillRect(px, py, sz, sz);
      }
      const gl = c.createRadialGradient(x, y, R * 0.6, x, y, R * 1.5);
      gl.addColorStop(0, rgba(th.glow, 0));
      gl.addColorStop(0.5, rgba(th.glow, 0.08 * g));
      gl.addColorStop(1, rgba(th.glow, 0));
      c.fillStyle = gl;
      c.fillRect(x - R * 2, y - R * 2, R * 4, R * 4);
      if (el.text) textBlurIn(c, el.text, x + R * 1.05, y + R * 0.85, S * 0.022, u - 0.8, th.sub, font, 400, "left");
      break;
    }
    case "stars": {
      const n = el.n ?? 60;
      for (let i = 0; i < n; i++) {
        const sx = (hash(i * 7.1 + (el.seed ?? 0)) + u * 0.004 * (1 + hash(i))) % 1;
        const sy = hash(i * 3.9 + 1 + (el.seed ?? 0));
        const tw = 0.4 + 0.6 * Math.pow(0.5 + 0.5 * Math.sin(u * (1 + hash(i) * 2) + i), 3);
        const a = clamp(u * 1.5 - hash(i * 9) * 0.8) * tw;
        const r = S * (0.0015 + 0.004 * Math.pow(hash(i * 4.4), 4));
        glowDot(c, sx * W, sy * H, r, col, a);
      }
      break;
    }
    case "title": {
      const fs = (el.s ?? 0.06) * S;
      textBlurIn(c, el.text ?? "", x, y, fs, u, el.color ?? th.text, font, 500, "center", fs * 0.01);
      if (el.sub) textBlurIn(c, el.sub, x, y + fs * 0.9, fs * 0.42, u - 0.25, th.sub, font, 400);
      break;
    }
    case "logo": {
      const R = (el.s ?? 0.07) * S;
      const draw = easeOutExpo(clamp(u / 1.2));
      c.save();
      c.translate(x, y);
      c.rotate(-Math.PI / 6 + u * 0.05);
      c.shadowColor = rgba(th.glow, 0.9);
      c.shadowBlur = R * (0.4 + 0.25 * Math.sin(u * 2));
      c.strokeStyle = col;
      c.lineWidth = R * 0.22;
      c.lineJoin = "round";
      c.setLineDash([R * 7 * draw, R * 7]);
      c.beginPath();
      for (let i = 0; i <= 6; i++) {
        const a = (i * Math.PI) / 3;
        if (i === 0) c.moveTo(Math.cos(a) * R, Math.sin(a) * R);
        else c.lineTo(Math.cos(a) * R, Math.sin(a) * R);
      }
      c.stroke();
      c.restore();
      if (el.text) textBlurIn(c, el.text, x, y + R * 2, R * 0.55, u - 0.7, th.text, font, 600, "center", R * 0.08);
      break;
    }
    case "line": {
      // A line runs in with a dot and opens into an outlined pill.
      const L = (el.s ?? 0.25) * W;
      const p = easeOutExpo(clamp(u / 0.9));
      c.strokeStyle = rgba(col, 0.85);
      c.lineWidth = Math.max(1.5, S * 0.003);
      c.beginPath();
      c.moveTo(x - L, y);
      c.lineTo(x - L + L * p, y);
      c.stroke();
      glowDot(c, x - L + L * p, y, S * 0.007, col);
      break;
    }
    case "eclipse": {
      // A black sun with a living, swirling corona.
      const R = (el.s ?? 0.17) * S;
      const g = easeOutExpo(clamp(u / 2.2));
      c.save();
      c.globalCompositeOperation = "lighter";
      for (let layer = 0; layer < 3; layer++) {
        const lw = R * (0.05 + layer * 0.05);
        for (let i = 0; i < 140; i++) {
          const a = (i / 140) * Math.PI * 2;
          const nz = noise(Math.cos(a) * 2 + u * 0.4 + layer * 5, Math.sin(a) * 2 + u * 0.3);
          const rad = R * (1.02 + 0.12 * nz + layer * 0.07);
          const a2 = a + (Math.PI * 2) / 140 + 0.01;
          c.strokeStyle = rgba(layer === 0 ? "#ffd0d4" : col, (0.55 - layer * 0.15) * g * (0.5 + nz));
          c.lineWidth = lw * (0.6 + nz);
          c.beginPath();
          c.arc(x, y, rad, a, a2);
          c.stroke();
        }
      }
      const gl = c.createRadialGradient(x, y, R * 0.9, x, y, R * 2.6);
      gl.addColorStop(0, rgba(col, 0.55 * g));
      gl.addColorStop(0.35, rgba(col, 0.18 * g));
      gl.addColorStop(1, rgba(col, 0));
      c.fillStyle = gl;
      c.fillRect(x - R * 3, y - R * 3, R * 6, R * 6);
      c.restore();
      c.fillStyle = "#020102";
      c.beginPath();
      c.arc(x, y, R * 0.98, 0, Math.PI * 2);
      c.fill();
      break;
    }
    case "fog": {
      // Layers of soft cloud drifting at different speeds (depth).
      const layers = el.n ?? 4;
      for (let L = 0; L < layers; L++) {
        const sp = 0.012 + L * 0.01;
        const yy = (el.y ?? 0.75) * H + L * H * 0.05;
        for (let i = 0; i < 9; i++) {
          const bx = ((hash(i * 3 + L * 17) + u * sp) % 1.3) * W * 1.1 - W * 0.15;
          const by = yy + (hash(i * 5 + L) - 0.5) * H * 0.18;
          const r = W * (0.18 + 0.2 * hash(i * 7 + L));
          const fg = c.createRadialGradient(bx, by, 0, bx, by, r);
          const fc = el.color ?? (th.light ? "#ffffff" : "#9a8f93");
          fg.addColorStop(0, rgba(fc, 0.16 * (1 - L * 0.15)));
          fg.addColorStop(1, rgba(fc, 0));
          c.fillStyle = fg;
          c.fillRect(bx - r, by - r, r * 2, r * 2);
        }
      }
      break;
    }
    case "embers": {
      const n = el.n ?? 70;
      c.save();
      c.globalCompositeOperation = "lighter";
      for (let i = 0; i < n; i++) {
        const life = 3 + hash(i) * 4;
        const ph = ((u + hash(i * 3) * life) % life) / life;
        const ex = (hash(i * 7.7) + Math.sin(u * 0.7 + i) * 0.02) * W;
        const ey = H * (1.05 - ph * 1.1);
        const fl = 0.5 + 0.5 * Math.sin(u * 9 + i * 3);
        glowDot(c, ex, ey, S * 0.0025 * (0.6 + hash(i * 2)), col, Math.sin(ph * Math.PI) * fl * clamp(u));
      }
      c.restore();
      break;
    }
    case "rays": {
      c.save();
      c.globalCompositeOperation = "lighter";
      const n = el.n ?? 14;
      for (let i = 0; i < n; i++) {
        const a = hash(i * 2.2) * Math.PI * 2 + Math.sin(u * 0.2 + i) * 0.03 + u * 0.02;
        const len = Math.max(W, H) * (0.8 + 0.5 * hash(i));
        const wdt = 0.02 + 0.05 * hash(i * 9);
        const gr = c.createLinearGradient(x, y, x + Math.cos(a) * len, y + Math.sin(a) * len);
        gr.addColorStop(0, rgba(col, 0.1 * clamp(u)));
        gr.addColorStop(1, rgba(col, 0));
        c.fillStyle = gr;
        c.beginPath();
        c.moveTo(x, y);
        c.lineTo(x + Math.cos(a - wdt) * len, y + Math.sin(a - wdt) * len);
        c.lineTo(x + Math.cos(a + wdt) * len, y + Math.sin(a + wdt) * len);
        c.closePath();
        c.fill();
      }
      c.restore();
      break;
    }
    case "brush": {
      // Brush-stroke title: revealed by a ragged wipe, glowing, with a slight ink shake on arrival.
      let fs = (el.s ?? 0.14) * S;
      c.font = `400 ${fs}px ${fontStack(el.font ?? "Permanent Marker")}`;
      c.textAlign = "center";
      c.textBaseline = "middle";
      let tw = c.measureText(el.text ?? "").width;
      if (tw > W * 0.86) {
        fs *= (W * 0.86) / tw;
        c.font = `400 ${fs}px ${fontStack(el.font ?? "Permanent Marker")}`;
        tw = c.measureText(el.text ?? "").width;
      }
      const p = easeOutExpo(clamp(u / 0.9));
      const shake = (1 - clamp(u / 0.4)) * fs * 0.03;
      c.save();
      c.beginPath();
      const x0 = x - tw / 2 - fs * 0.2;
      const edge = x0 + (tw + fs * 0.6) * p;
      c.moveTo(x0, y - fs);
      for (let k = 0; k <= 16; k++) c.lineTo(edge + (hash(k + Math.floor(u * 12)) - 0.5) * fs * 0.25, y - fs + (k / 16) * fs * 2);
      c.lineTo(x0, y + fs);
      c.closePath();
      c.clip();
      c.shadowColor = rgba(th.glow, 0.9);
      c.shadowBlur = fs * 0.25;
      c.fillStyle = col;
      c.fillText(el.text ?? "", x + (hash(Math.floor(u * 30)) - 0.5) * shake, y + (hash(Math.floor(u * 30) + 3) - 0.5) * shake);
      c.restore();
      if (el.sub) textBlurIn(c, el.sub, x, y + fs * 0.75, fs * 0.16, u - 0.6, th.text, el.font ?? th.font, 500, "center", fs * 0.05);
      break;
    }
    case "flash": {
      const a = Math.max(0, 1 - u / 0.35);
      c.fillStyle = rgba(el.color ?? "#ffffff", a * 0.85);
      c.fillRect(0, 0, W, H);
      break;
    }
  }
  c.restore();
}

/* ---------------------------------- render --------------------------------- */

function camAt(cam: MotionCamKey[] | undefined, t: number): Required<Omit<MotionCamKey, "t">> {
  const base = { z: 1, x: 0, y: 0, r: 0 };
  if (!cam?.length) return base;
  const k = [...cam].sort((a, b) => a.t - b.t);
  if (t <= k[0]!.t) return { ...base, ...k[0] } as Required<Omit<MotionCamKey, "t">>;
  for (let i = 0; i < k.length - 1; i++) {
    const a = k[i]!,
      b = k[i + 1]!;
    if (t <= b.t) {
      const f = easeInOut((t - a.t) / Math.max(1e-6, b.t - a.t));
      return { z: lerp(a.z ?? 1, b.z ?? 1, f), x: lerp(a.x ?? 0, b.x ?? 0, f), y: lerp(a.y ?? 0, b.y ?? 0, f), r: lerp(a.r ?? 0, b.r ?? 0, f) };
    }
  }
  const l = k[k.length - 1]!;
  return { z: l.z ?? 1, x: l.x ?? 0, y: l.y ?? 0, r: l.r ?? 0 };
}

export function drawMotion(c: Ctx, spec: MotionSpec, W: number, H: number, t: number): void {
  const th = THEMES[spec.theme] ?? THEMES.ember!;
  c.save();
  if (spec.bg !== false) background(c, th, W, H, t);
  const cam = camAt(spec.cam, t);
  c.translate(W / 2, H / 2);
  c.rotate(cam.r);
  c.scale(cam.z, cam.z);
  c.translate(-W / 2 - cam.x * W, -H / 2 - cam.y * H);
  for (const el of spec.els) drawEl(c, el, th, W, H, t);
  c.restore();
  if (spec.bg !== false) finish(c, th, W, H, t, spec.grain ?? 0.08);
}

/* --------------------------------- builder --------------------------------- */

export interface PromoLine {
  text: string;
  sub?: string;
}

/**
 * Write a whole motion promo from a few lines: each line gets its own composition (dot → frame, sparkle title,
 * glowing pill, dot row with sweeping arc, path landing on a pill, connected outline, particle ring, logo), with
 * soft camera moves and overlaps. Returns the spec and its length in seconds.
 */
export function buildPromo(lines: PromoLine[], o: { theme?: string; beat?: number; logo?: string; style?: "warm" | "tech" | "cinematic" } = {}): { spec: MotionSpec; seconds: number } {
  const theme = o.theme ?? (o.style === "tech" ? "mono" : o.style === "cinematic" ? "eclipse" : "ember");
  const B = o.beat ?? 2.4;
  const els: MotionEl[] = [];
  const cam: MotionCamKey[] = [{ t: 0, z: 1.08 }];
  let t = 0;
  const plan = o.style === "tech" ? ["ring", "title", "stars", "pill", "dots", "connect"] : o.style === "cinematic" ? ["eclipse", "brush", "title", "embers"] : ["intro", "spark", "pill", "dots", "path", "connect"];
  if (theme === "mono") els.push({ k: "stars", t0: 0, n: 70 });
  if (theme === "eclipse") els.push({ k: "fog", t0: 0, n: 4, y: 0.82 }, { k: "embers", t0: 0, n: 60 });
  lines.forEach((ln, i) => {
    const kind = plan[i % plan.length]!;
    const end = t + B;
    switch (kind) {
      case "intro":
        els.push({ k: "dot", t0: t, t1: t + B * 0.35, s: 0.012 });
        els.push({ k: "frame", t0: t + B * 0.25, t1: end, s: 0.5, text: ln.text, rot: -0.2 });
        break;
      case "spark":
        els.push({ k: "spark", t0: t, t1: end, y: 0.42, s: 0.11 });
        els.push({ k: "title", t0: t + 0.35, t1: end, y: 0.56, s: 0.085, text: ln.text, sub: ln.sub });
        break;
      case "pill":
        els.push({ k: "pill", t0: t, t1: end, s: 0.14, text: ln.text, sub: ln.sub, icon: "spark" });
        break;
      case "dots":
        els.push({ k: "dots", t0: t, t1: end, n: 5, s: 0.7, text: ln.text, y: 0.48 });
        break;
      case "path":
        els.push({ k: "path", t0: t, t1: end, pts: [[0.3, 0.95], [0.25, 0.75], [0.45, 0.62], [0.52, 0.52]] });
        els.push({ k: "pill", t0: t + B * 0.45, t1: end, y: 0.5, s: 0.13, text: ln.text, icon: "spark" });
        break;
      case "connect":
        els.push({ k: "line", t0: t, t1: t + B * 0.45, s: 0.3 });
        els.push({ k: "pill", t0: t + B * 0.35, t1: end, s: 0.14, text: ln.text, icon: "none", variant: "outline" });
        break;
      case "ring":
        els.push({ k: "ring", t0: t, t1: end, s: 0.24, text: ln.sub });
        els.push({ k: "title", t0: t + 0.6, t1: end, y: 0.8, s: 0.05, text: ln.text });
        break;
      case "title":
        els.push({ k: "title", t0: t, t1: end, y: theme === "eclipse" ? 0.72 : 0.5, s: 0.07, text: ln.text, sub: ln.sub });
        break;
      case "stars":
        els.push({ k: "logo", t0: t, t1: end, s: 0.06, text: ln.text });
        break;
      case "eclipse":
        els.push({ k: "rays", t0: t + 0.5, y: 0.36, n: 16 });
        els.push({ k: "eclipse", t0: t, y: 0.36, s: 0.2 });
        els.push({ k: "title", t0: t + 0.8, t1: end, y: 0.72, s: 0.065, text: ln.text, sub: ln.sub });
        break;
      case "brush":
        els.push({ k: "brush", t0: t, t1: end, y: 0.68, s: 0.16, text: ln.text, sub: ln.sub, font: "Permanent Marker" });
        break;
      case "embers":
        els.push({ k: "title", t0: t, t1: end, y: 0.72, s: 0.06, text: ln.text, sub: ln.sub });
        break;
    }
    // Gentle push-in through each beat, a small release at the change.
    cam.push({ t: t + B * 0.9, z: 1 + 0.06 * ((i % 2) + 1), x: (i % 2 ? 0.01 : -0.01), r: i % 2 ? 0.008 : -0.008 });
    t = end;
  });
  if (o.logo) {
    els.push({ k: "logo", t0: t, t1: t + 2.6, s: 0.06, text: o.logo });
    t += 2.6;
  }
  cam.push({ t, z: 1 });
  return { spec: { theme, els, cam, grain: 0.07 }, seconds: Math.round(t * 100) / 100 };
}
