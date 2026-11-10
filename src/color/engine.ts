/**
 * The colour page's engine, pure and GPU-free: curve interpolation and baking, window animation, node clean-up,
 * and a CPU version of the node maths that mirrors the shader line for line (compositor.ts, `nodes()`), so the
 * same grade can be tested, measured and exported as a LUT with full float precision.
 *
 * Order inside one node (the shader does exactly the same):
 *   temperature / tint → offset → gain & lift → gamma → contrast around the pivot → saturation → hue
 *   → custom curves (master, then red / green / blue) → hue & saturation curves → LUT
 * The node's key (qualifier × window) and its mix decide how much of that result replaces the node's input.
 */
import { CURVE_KEYS, MAX_NODES, type ColorNode, type CurveKey, type CurvePts, type Lut3D, type NodeCurves, type PowerWindow, type Qualifier } from "./types";

/** Samples per baked curve row. */
export const CURVE_RES = 256;
/** Rows per node in the curves texture, in CURVE_KEYS order. */
export const CURVE_ROWS = CURVE_KEYS.length;

const clamp01 = (v: number) => (v < 0 ? 0 : v > 1 ? 1 : v);
const finite = (v: unknown, d: number) => (typeof v === "number" && Number.isFinite(v) ? v : d);

/** Hue and saturation curves rest at 0.5 ("no change"); the custom curves rest on the diagonal. */
export function isHueCurve(k: CurveKey): boolean {
  return k === "hh" || k === "hs" || k === "hl" || k === "ls" || k === "ss";
}
/** Only hue is a circle: its curves wrap around from 1 back to 0. */
function wraps(k: CurveKey): boolean {
  return k === "hh" || k === "hs" || k === "hl";
}

/** Sorted, de-duplicated, clamped control points (at most 24); null when the curve is missing or unusable. */
export function cleanCurve(pts: unknown): CurvePts | null {
  if (!Array.isArray(pts)) return null;
  const out: CurvePts = [];
  for (const p of pts) {
    if (!Array.isArray(p) || p.length < 2) continue;
    const x = Number(p[0]);
    const y = Number(p[1]);
    if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
    out.push([clamp01(x), clamp01(y)]);
  }
  out.sort((a, b) => a[0] - b[0]);
  const dedup: CurvePts = [];
  for (const p of out) {
    const last = dedup[dedup.length - 1];
    if (last && Math.abs(last[0] - p[0]) < 1e-4) last[1] = p[1];
    else dedup.push(p);
  }
  return dedup.length ? dedup.slice(0, 24) : null;
}

/** True when a curve changes nothing. */
export function isIdentityCurve(k: CurveKey, pts: CurvePts | undefined | null): boolean {
  if (!pts || !pts.length) return true;
  if (isHueCurve(k)) return pts.every((p) => Math.abs(p[1] - 0.5) < 1e-4);
  return pts.every((p) => Math.abs(p[1] - p[0]) < 1e-4);
}

/**
 * Monotone cubic interpolation (Fritsch–Carlson): passes through every point, never overshoots between them, so a
 * curve drawn as rising stays rising. Outside the first and last point the curve holds its end value — except
 * custom curves with a first point at x > 0 or a last point at x < 1, which is also a hold (a clipped black or
 * white point), matching how such curves behave on a colour page.
 */
function monotone(xs: number[], ys: number[]): (x: number) => number {
  const n = xs.length;
  if (n === 1) return () => ys[0]!;
  const d: number[] = [];
  const h: number[] = [];
  for (let i = 0; i < n - 1; i++) {
    h.push(xs[i + 1]! - xs[i]!);
    d.push((ys[i + 1]! - ys[i]!) / Math.max(1e-9, h[i]!));
  }
  const m: number[] = new Array<number>(n).fill(0);
  m[0] = d[0]!;
  m[n - 1] = d[n - 2]!;
  for (let i = 1; i < n - 1; i++) m[i] = d[i - 1]! * d[i]! <= 0 ? 0 : (d[i - 1]! + d[i]!) / 2;
  for (let i = 0; i < n - 1; i++) {
    if (Math.abs(d[i]!) < 1e-12) {
      m[i] = 0;
      m[i + 1] = 0;
      continue;
    }
    const a = m[i]! / d[i]!;
    const b = m[i + 1]! / d[i]!;
    const s = a * a + b * b;
    if (s > 9) {
      const t = 3 / Math.sqrt(s);
      m[i] = t * a * d[i]!;
      m[i + 1] = t * b * d[i]!;
    }
  }
  return (x: number) => {
    if (x <= xs[0]!) return ys[0]!;
    if (x >= xs[n - 1]!) return ys[n - 1]!;
    let i = 0;
    while (i < n - 2 && x > xs[i + 1]!) i++;
    const t = (x - xs[i]!) / h[i]!;
    const t2 = t * t;
    const t3 = t2 * t;
    return (2 * t3 - 3 * t2 + 1) * ys[i]! + (t3 - 2 * t2 + t) * h[i]! * m[i]! + (-2 * t3 + 3 * t2) * ys[i + 1]! + (t3 - t2) * h[i]! * m[i + 1]!;
  };
}

/** A function for one curve: x in 0..1 → y in 0..1. Hue curves wrap around; missing curves return their rest value. */
export function curveFn(k: CurveKey, pts: CurvePts | undefined | null): (x: number) => number {
  const c = cleanCurve(pts);
  if (!c || isIdentityCurve(k, c)) return isHueCurve(k) ? () => 0.5 : (x) => clamp01(x);
  let xs = c.map((p) => p[0]);
  let ys = c.map((p) => p[1]);
  if (wraps(k)) {
    // Repeat the points one turn to the left and right so the curve is smooth across the 0/1 seam.
    xs = [...c.map((p) => p[0] - 1), ...xs, ...c.map((p) => p[0] + 1)];
    ys = [...ys, ...ys, ...ys];
    // A point exactly at 0 and one at 1 would coincide after the shift: drop duplicates.
    const fx: number[] = [];
    const fy: number[] = [];
    for (let i = 0; i < xs.length; i++) {
      if (fx.length && xs[i]! - fx[fx.length - 1]! < 1e-4) continue;
      fx.push(xs[i]!);
      fy.push(ys[i]!);
    }
    xs = fx;
    ys = fy;
  }
  const f = monotone(xs, ys);
  return (x: number) => clamp01(f(clamp01(x)));
}

/** Bit for each curve that is not the identity: m=1 r=2 g=4 b=8 hh=16 hs=32 hl=64 ls=128 ss=256. */
export function curveMask(curves: NodeCurves | undefined): number {
  if (!curves) return 0;
  let mask = 0;
  CURVE_KEYS.forEach((k, i) => {
    if (!isIdentityCurve(k, cleanCurve(curves[k]))) mask |= 1 << i;
  });
  return mask;
}

const bakeCache = new Map<string, Float32Array>();

/**
 * All curves of a clip's nodes as one table: MAX_NODES × CURVE_ROWS rows of CURVE_RES samples (row = node index ×
 * CURVE_ROWS + curve index). `key` changes only when a curve changes, so the GPU texture is re-uploaded only then.
 * Returns null when no node has a curve.
 */
export function bakeCurves(nodes: ColorNode[]): { key: string; rows: number; data: Float32Array } | null {
  if (!nodes.some((n) => curveMask(n.curves))) return null;
  const key = JSON.stringify(nodes.map((n) => n.curves ?? 0));
  const rows = MAX_NODES * CURVE_ROWS;
  let data = bakeCache.get(key);
  if (!data) {
    data = new Float32Array(CURVE_RES * rows);
    nodes.slice(0, MAX_NODES).forEach((n, ni) => {
      CURVE_KEYS.forEach((k, ki) => {
        const f = curveFn(k, n.curves?.[k]);
        const off = (ni * CURVE_ROWS + ki) * CURVE_RES;
        for (let i = 0; i < CURVE_RES; i++) data![off + i] = f(i / (CURVE_RES - 1));
      });
    });
    // Rows of unused node slots stay 0; they are never read (the shader stops at the node count).
    if (bakeCache.size > 64) bakeCache.delete(bakeCache.keys().next().value as string);
    bakeCache.set(key, data);
  }
  return { key, rows, data };
}

/** The window at `local` seconds into the clip (keys interpolated linearly, held before the first and after the last). */
export function windowAt(w: PowerWindow, local: number): PowerWindow {
  const keys = w.keys;
  if (!keys || !keys.length) return w;
  const ks = [...keys].sort((a, b) => a.t - b.t);
  let a = ks[0]!;
  let b = ks[ks.length - 1]!;
  if (local <= a.t) b = a;
  else if (local >= b.t) a = b;
  else
    for (let i = 0; i < ks.length - 1; i++)
      if (local >= ks[i]!.t && local <= ks[i + 1]!.t) {
        a = ks[i]!;
        b = ks[i + 1]!;
        break;
      }
  const f = b.t > a.t ? (local - a.t) / (b.t - a.t) : 0;
  const mix = (p: number, q: number) => p + (q - p) * f;
  return { ...w, x: mix(a.x, b.x), y: mix(a.y, b.y), w: mix(a.w, b.w), h: mix(a.h, b.h), rot: mix(a.rot, b.rot), keys: undefined };
}

/** The nodes that will actually run at `local` seconds into the clip: switched-on ones, windows resolved, at most MAX_NODES. */
export function resolveNodes(nodes: ColorNode[] | undefined, local: number): ColorNode[] | undefined {
  if (!Array.isArray(nodes) || !nodes.length) return undefined;
  const on = nodes.filter((n) => n && n.on).slice(0, MAX_NODES);
  if (!on.length) return undefined;
  return on.map((n) => (n.win?.keys?.length ? { ...n, win: windowAt(n.win, local) } : n));
}

/** True when a node changes nothing at all (so the engine and the UI can skip it). */
export function isNeutralNode(n: ColorNode): boolean {
  const z = (a: number[]) => a.every((v) => Math.abs(v) < 1e-6);
  return z(n.lift) && z(n.gamma) && z(n.gain) && z(n.offset) && Math.abs(n.contrast - 1) < 1e-6 && Math.abs(n.sat - 1) < 1e-6 && Math.abs(n.hue) < 1e-6 && Math.abs(n.temp) < 1e-6 && Math.abs(n.tint) < 1e-6 && !curveMask(n.curves) && !(n.lut && n.lut.mix > 0);
}

/* ------------------------------ CPU mirror of the shader ------------------------------ */

type V3 = [number, number, number];
const luma = (c: V3) => 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
const smoothstep = (e0: number, e1: number, x: number) => {
  const t = clamp01((x - e0) / (e1 - e0));
  return t * t * (3 - 2 * t);
};
const fract = (v: number) => v - Math.floor(v);

/** Hue 0..1, saturation 0..1 ((max−min)/max), value. Same formula as the shader's `hsvOf`. */
export function hsvOf(c: V3): V3 {
  const r = clamp01(c[0]);
  const g = clamp01(c[1]);
  const b = clamp01(c[2]);
  const mx = Math.max(r, g, b);
  const mn = Math.min(r, g, b);
  const d = mx - mn;
  let h = 0;
  if (d > 1e-6) {
    if (mx === r) h = ((g - b) / d) % 6;
    else if (mx === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h /= 6;
    if (h < 0) h += 1;
  }
  return [h, mx > 1e-6 ? d / mx : 0, mx];
}

/** Rotate the hue by `rad` in YIQ (keeps luminance), as the shader does. */
function hueRotate(c: V3, rad: number): V3 {
  const y = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];
  const i = 0.596 * c[0] - 0.274 * c[1] - 0.322 * c[2];
  const q = 0.211 * c[0] - 0.523 * c[1] + 0.312 * c[2];
  const cs = Math.cos(rad);
  const sn = Math.sin(rad);
  const i2 = i * cs - q * sn;
  const q2 = i * sn + q * cs;
  return [y + 0.956 * i2 + 0.621 * q2, y - 0.272 * i2 - 0.647 * q2, y - 1.106 * i2 + 1.703 * q2];
}

/** 0..1: how much of the pixel the qualifier selects. */
export function qualKey(q: Qualifier | undefined, c: V3): number {
  if (!q || !q.on) return 1;
  const [h, s] = hsvOf(c);
  const L = clamp01(luma(c));
  const dh = Math.abs(fract(h - q.h + 0.5) - 0.5);
  const kh = q.hw >= 0.5 ? 1 : 1 - smoothstep(q.hw, q.hw + Math.max(q.hs, 1e-4), dh);
  const ss = Math.max(q.ss, 1e-4);
  const ks = smoothstep(q.s0 - ss, q.s0, s) * (1 - smoothstep(q.s1, q.s1 + ss, s));
  const ls = Math.max(q.ls, 1e-4);
  const kl = smoothstep(q.l0 - ls, q.l0, L) * (1 - smoothstep(q.l1, q.l1 + ls, L));
  const k = kh * ks * kl;
  return q.invert ? 1 - k : k;
}

/** 0..1: how much of the pixel at picture position (u, v) the window selects. `aspect` = picture width / height. */
export function winKey(w: PowerWindow | undefined, u: number, v: number, aspect: number): number {
  if (!w || w.shape === "none") return 1;
  let dx = (u - w.x) * aspect;
  let dy = v - w.y;
  const r = (-w.rot * Math.PI) / 180;
  const cs = Math.cos(r);
  const sn = Math.sin(r);
  [dx, dy] = [dx * cs - dy * sn, dx * sn + dy * cs];
  const soft = Math.max(w.soft, 1e-3);
  let m: number;
  if (w.shape === "gradient") m = 1 - smoothstep(-0.5, 0.5, dy / Math.max(w.w, 1e-4));
  else {
    const hx = Math.max(w.w * aspect * 0.5, 1e-4);
    const hy = Math.max(w.h * 0.5, 1e-4);
    if (w.shape === "rect") m = (1 - smoothstep(1 - soft, 1 + soft, Math.abs(dx) / hx)) * (1 - smoothstep(1 - soft, 1 + soft, Math.abs(dy) / hy));
    else m = 1 - smoothstep(1 - soft, 1 + soft, Math.hypot(dx / hx, dy / hy));
  }
  return w.invert ? 1 - m : m;
}

/** Trilinear LUT lookup (what the GPU's 3D texture filter does). */
export function lutLookup(lut: Lut3D, c: V3): V3 {
  const n = lut.size;
  const p = [clamp01(c[0]) * (n - 1), clamp01(c[1]) * (n - 1), clamp01(c[2]) * (n - 1)];
  const i0 = p.map((v) => Math.min(n - 2, Math.floor(v)));
  const f = p.map((v, i) => v - i0[i]!);
  const at = (r: number, g: number, b: number, ch: number) => lut.data[(r + g * n + b * n * n) * 3 + ch]!;
  const out: V3 = [0, 0, 0];
  for (let ch = 0; ch < 3; ch++) {
    let acc = 0;
    for (let k = 0; k < 8; k++) {
      const dr = k & 1;
      const dg = (k >> 1) & 1;
      const db = (k >> 2) & 1;
      acc += at(i0[0]! + dr, i0[1]! + dg, i0[2]! + db, ch) * (dr ? f[0]! : 1 - f[0]!) * (dg ? f[1]! : 1 - f[1]!) * (db ? f[2]! : 1 - f[2]!);
    }
    out[ch] = acc;
  }
  return out;
}

function blendOf(mode: ColorNode["blend"], base: V3, top: V3): V3 {
  const each = (f: (a: number, b: number) => number): V3 => [f(base[0], top[0]), f(base[1], top[1]), f(base[2], top[2])];
  switch (mode) {
    case "add":
      return each((a, b) => a + b);
    case "multiply":
      return each((a, b) => a * b);
    case "screen":
      return each((a, b) => 1 - (1 - a) * (1 - b));
    case "overlay":
      return each((a, b) => (a < 0.5 ? 2 * a * b : 1 - 2 * (1 - a) * (1 - b)));
    case "softLight":
      return each((a, b) => (1 - 2 * b) * a * a + 2 * b * a);
    default:
      return top;
  }
}

/** One node's grade on one colour (no key, no mix). `fns` are the node's curve functions; `lut` its table. */
export function gradeNode(n: ColorNode, c: V3, fns?: Partial<Record<CurveKey, (x: number) => number>>, lut?: Lut3D | null): V3 {
  let g: V3 = [c[0] * (1 + n.temp * 0.12), c[1] * (1 + n.tint * 0.08), c[2] * (1 - n.temp * 0.12)];
  for (let i = 0; i < 3; i++) {
    let v = g[i]! + n.offset[i]!;
    v = v * (1 + n.gain[i]! + n.gain[3]) + (n.lift[i]! + n.lift[3]) * (1 - v);
    v = Math.pow(Math.max(v, 0), 1 / Math.max(0.05, 1 + n.gamma[i]! + n.gamma[3]));
    v = (v - n.pivot) * n.contrast + n.pivot;
    g[i] = v;
  }
  const l = luma(g);
  g = [l + (g[0] - l) * n.sat, l + (g[1] - l) * n.sat, l + (g[2] - l) * n.sat];
  if (Math.abs(n.hue) > 0.01) g = hueRotate(g, (n.hue * Math.PI) / 180);
  if (fns) {
    const m = fns.m;
    if (m) g = [m(g[0]), m(g[1]), m(g[2])];
    if (fns.r) g[0] = fns.r(g[0]);
    if (fns.g) g[1] = fns.g(g[1]);
    if (fns.b) g[2] = fns.b(g[2]);
    if (fns.hh || fns.hs || fns.hl || fns.ls || fns.ss) {
      const [h, s] = hsvOf(g);
      if (fns.hh) g = hueRotate(g, (fns.hh(h) - 0.5) * Math.PI);
      let k = 1;
      if (fns.hs) k *= fns.hs(h) * 2;
      if (fns.ls) k *= fns.ls(clamp01(luma(g))) * 2;
      if (fns.ss) k *= fns.ss(s) * 2;
      if (k !== 1) {
        const y = luma(g);
        g = [y + (g[0] - y) * k, y + (g[1] - y) * k, y + (g[2] - y) * k];
      }
      if (fns.hl) {
        const gain = 1 + (fns.hl(h) * 2 - 1) * s;
        g = [g[0] * gain, g[1] * gain, g[2] * gain];
      }
    }
  }
  if (lut && n.lut && n.lut.mix > 0) {
    const t = lutLookup(lut, g);
    const k = clamp01(n.lut.mix);
    g = [g[0] + (t[0] - g[0]) * k, g[1] + (t[1] - g[1]) * k, g[2] + (t[2] - g[2]) * k];
  }
  return g;
}

/**
 * Run a clip's node graph on one colour at picture position (u, v) — the reference the shader is checked against.
 * `luts` resolves a node's LUT id to its table.
 */
export function applyNodes(nodes: ColorNode[], c0: V3, o: { u?: number; v?: number; aspect?: number; luts?: (id: string) => Lut3D | null | undefined } = {}): V3 {
  let c: V3 = [c0[0], c0[1], c0[2]];
  for (const n of nodes.slice(0, MAX_NODES)) {
    if (!n.on) continue;
    const inp: V3 = n.kind === "layer" ? [c0[0], c0[1], c0[2]] : c;
    const key = qualKey(n.qual, inp) * winKey(n.win, o.u ?? 0.5, o.v ?? 0.5, o.aspect ?? 16 / 9);
    const mask = curveMask(n.curves);
    const fns: Partial<Record<CurveKey, (x: number) => number>> = {};
    CURVE_KEYS.forEach((k, i) => {
      if (mask & (1 << i)) fns[k] = curveFn(k, n.curves?.[k]);
    });
    const g = gradeNode(n, inp, mask ? fns : undefined, n.lut ? o.luts?.(n.lut.id) : null);
    const top = n.kind === "layer" ? blendOf(n.blend, c, g) : g;
    const k = key * clamp01(n.mix);
    c = [c[0] + (top[0] - c[0]) * k, c[1] + (top[1] - c[1]) * k, c[2] + (top[2] - c[2]) * k];
  }
  return c;
}

/**
 * Bake a node graph into a 3D LUT ("generate LUT"): what the nodes do to every colour, ignoring windows (a LUT has
 * no position) but honouring qualifiers. Output clamped to 0..1.
 */
export function bakeNodesLut(nodes: ColorNode[], size = 33, luts?: (id: string) => Lut3D | null | undefined, title?: string): Lut3D {
  const flat = nodes.filter((n) => n.on).map((n) => ({ ...n, win: undefined }));
  const data = new Float32Array(size * size * size * 3);
  // Curve functions are rebuilt per call inside applyNodes; cache them per node here for speed.
  const prepared = flat.map((n) => {
    const mask = curveMask(n.curves);
    const fns: Partial<Record<CurveKey, (x: number) => number>> = {};
    CURVE_KEYS.forEach((k, i) => {
      if (mask & (1 << i)) fns[k] = curveFn(k, n.curves?.[k]);
    });
    return { n, fns: mask ? fns : undefined, lut: n.lut ? luts?.(n.lut.id) ?? null : null };
  });
  let o = 0;
  for (let b = 0; b < size; b++)
    for (let g = 0; g < size; g++)
      for (let r = 0; r < size; r++) {
        const c0: V3 = [r / (size - 1), g / (size - 1), b / (size - 1)];
        let c: V3 = [c0[0], c0[1], c0[2]];
        for (const { n, fns, lut } of prepared) {
          const inp: V3 = n.kind === "layer" ? c0 : c;
          const gr = gradeNode(n, inp, fns, lut);
          const top = n.kind === "layer" ? blendOf(n.blend, c, gr) : gr;
          const k = qualKey(n.qual, inp) * clamp01(n.mix);
          c = [c[0] + (top[0] - c[0]) * k, c[1] + (top[1] - c[1]) * k, c[2] + (top[2] - c[2]) * k];
        }
        data[o++] = clamp01(c[0]);
        data[o++] = clamp01(c[1]);
        data[o++] = clamp01(c[2]);
      }
  return { size, data, title };
}

/* ------------------------------ clean-up of incoming node data ------------------------------ */

const rng = (v: unknown, lo: number, hi: number, d: number) => Math.min(hi, Math.max(lo, finite(v, d)));
const vec = (v: unknown, n: number, lo: number, hi: number): number[] => {
  const a = Array.isArray(v) ? v : [];
  return Array.from({ length: n }, (_, i) => rng(a[i], lo, hi, 0));
};

/** Make any object a valid node: every field present, finite and inside its range. Used on everything that comes in. */
export function sanitizeNode(raw: Partial<ColorNode> & { id: string }): ColorNode {
  const n: ColorNode = {
    id: String(raw.id),
    name: typeof raw.name === "string" && raw.name.trim() ? raw.name.trim().slice(0, 40) : "Node",
    on: raw.on !== false,
    kind: raw.kind === "layer" ? "layer" : "serial",
    blend: (["normal", "add", "multiply", "screen", "overlay", "softLight"] as const).includes(raw.blend as ColorNode["blend"]) ? (raw.blend as ColorNode["blend"]) : "normal",
    mix: rng(raw.mix, 0, 1, 1),
    lift: vec(raw.lift, 4, -1, 1) as ColorNode["lift"],
    gamma: vec(raw.gamma, 4, -0.9, 4) as ColorNode["gamma"],
    gain: vec(raw.gain, 4, -1, 4) as ColorNode["gain"],
    offset: vec(raw.offset, 3, -1, 1) as ColorNode["offset"],
    contrast: rng(raw.contrast, 0, 3, 1),
    pivot: rng(raw.pivot, 0, 1, 0.435),
    sat: rng(raw.sat, 0, 3, 1),
    hue: rng(raw.hue, -180, 180, 0),
    temp: rng(raw.temp, -2, 2, 0),
    tint: rng(raw.tint, -2, 2, 0),
  };
  if (raw.curves && typeof raw.curves === "object") {
    const cv: NodeCurves = {};
    for (const k of CURVE_KEYS) {
      const c = cleanCurve((raw.curves as NodeCurves)[k]);
      if (c && !isIdentityCurve(k, c)) cv[k] = c;
    }
    if (Object.keys(cv).length) n.curves = cv;
  }
  if (raw.lut && typeof raw.lut === "object" && typeof raw.lut.id === "string" && raw.lut.id) n.lut = { id: raw.lut.id, mix: rng(raw.lut.mix, 0, 1, 1) };
  if (raw.qual && typeof raw.qual === "object") {
    const q = raw.qual;
    const s0 = rng(q.s0, 0, 1, 0);
    const l0 = rng(q.l0, 0, 1, 0);
    n.qual = { on: q.on !== false, h: fract(finite(q.h, 0)), hw: rng(q.hw, 0, 0.5, 0.08), hs: rng(q.hs, 0, 0.5, 0.04), s0, s1: Math.max(s0, rng(q.s1, 0, 1, 1)), ss: rng(q.ss, 0, 1, 0.08), l0, l1: Math.max(l0, rng(q.l1, 0, 1, 1)), ls: rng(q.ls, 0, 1, 0.08), invert: q.invert === true };
  }
  if (raw.win && typeof raw.win === "object") {
    const w = raw.win;
    const shape = (["none", "rect", "ellipse", "gradient"] as const).includes(w.shape) ? w.shape : "none";
    const one = (k: Partial<PowerWindow>) => ({ x: rng(k.x, -1, 2, 0.5), y: rng(k.y, -1, 2, 0.5), w: rng(k.w, 0.005, 4, 0.5), h: rng(k.h, 0.005, 4, 0.5), rot: rng(k.rot, -3600, 3600, 0) });
    n.win = { shape, ...one(w), soft: rng(w.soft, 0, 1, 0.2), invert: w.invert === true };
    if (Array.isArray(w.keys) && w.keys.length) n.win.keys = w.keys.slice(0, 200).map((k) => ({ t: Math.max(0, finite(k?.t, 0)), ...one(k ?? {}) })).sort((a, b) => a.t - b.t);
  }
  return n;
}

/* ------------------------------ the basic grade on the CPU ------------------------------ */

/** The clip's basic colour grade (the sliders before the nodes) on one colour — same order and maths as the shader. */
export function gradeBasic(
  c0: V3,
  g: { exposure: number; contrast: number; saturation: number; vibrance: number; temperature: number; tint: number; hue: number; lift: number; gamma: number; gain: number },
  wheels?: { lift: number[]; gamma: number[]; gain: number[] },
  tone?: { shadows: number; highlights: number },
): V3 {
  const e = Math.pow(2, g.exposure);
  let c: V3 = [c0[0] * e * (1 + g.temperature * 0.12), c0[1] * e * (1 + g.tint * 0.08), c0[2] * e * (1 - g.temperature * 0.12)];
  for (let i = 0; i < 3; i++) {
    let v = c[i]! * g.gain + g.lift * (1 - c[i]!);
    v = Math.pow(Math.max(v, 0), 1 / Math.max(g.gamma, 0.05));
    v = v * (1 + (wheels?.gain[i] ?? 0)) + (wheels?.lift[i] ?? 0) * (1 - v);
    v = Math.pow(Math.max(v, 0), 1 / Math.max(0.05, 1 + (wheels?.gamma[i] ?? 0)));
    c[i] = v;
  }
  if (tone && (tone.shadows !== 0 || tone.highlights !== 0)) {
    const L = luma(c);
    const add = tone.shadows * 0.35 * (1 - smoothstep(0, 0.55, L)) + tone.highlights * 0.35 * smoothstep(0.45, 1, L);
    c = [c[0] + add, c[1] + add, c[2] + add];
  }
  c = [(c[0] - 0.5) * g.contrast + 0.5, (c[1] - 0.5) * g.contrast + 0.5, (c[2] - 0.5) * g.contrast + 0.5];
  const l = luma(c);
  const sat = Math.max(c[0], c[1], c[2]) - Math.min(c[0], c[1], c[2]);
  const k = g.saturation * (1 + g.vibrance * (1 - sat));
  c = [l + (c[0] - l) * k, l + (c[1] - l) * k, l + (c[2] - l) * k];
  if (Math.abs(g.hue) > 0.01) {
    // The basic grade's hue uses the same YIQ rotation as the nodes.
    c = hueRotate(c, (g.hue * Math.PI) / 180);
  }
  return c;
}
