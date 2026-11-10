/**
 * Colour intelligence: picture statistics, Shot Match (make clip A look like clip B), Look from picture (take the
 * palette and tone of a reference still) and Auto balance.
 *
 * Everything here is pure TypeScript (no DOM, no Node APIs) so it runs the same in the browser, in a worker and in
 * the tests. The results are ordinary grading data — a 3D LUT, editable curves or primaries — so the person can see
 * and adjust what the app did instead of trusting a black box.
 *
 * Colour science used (and only this):
 *  - sRGB transfer function and primaries: IEC 61966-2-1:1999 (primaries and white are those of ITU-R BT.709).
 *  - D65 white: chromaticity x = 0.3127, y = 0.3290 (IEC 61966-2-1 / ITU-R BT.709).
 *  - CIE 1976 L*a*b* (CIE 15:2004), with the exact rational constants ε = 216/24389 and κ = 24389/27.
 *  - Luma: ITU-R BT.709 coefficients (0.2126, 0.7152, 0.0722) on display-encoded values, as the grading engine does.
 */
import type { ColorNode, ColourStats, CurvePts, Lut3D, NodeCurves, Pixels } from "./types";

type V3 = [number, number, number];
type M3 = [number, number, number, number, number, number, number, number, number];

// ---------------------------------------------------------------------------------------------------------------
// Constants and matrices
// ---------------------------------------------------------------------------------------------------------------

/** CIE 15:2004 Lab constants in their exact rational form (ε = (6/29)³, κ = (29/3)³). */
const LAB_EPS = 216 / 24389;
const LAB_KAPPA = 24389 / 27;

/** Number of bins of every histogram in ColourStats. */
const BINS = 64;

/** XYZ (Y = 1) of a chromaticity x, y. */
function xyToXyz(x: number, y: number): V3 {
  return [x / y, 1, (1 - x - y) / y];
}

function det3(m: M3): number {
  const [a, b, c, d, e, f, g, h, i] = m;
  return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
}

/** Inverse of a 3×3 matrix (row-major) by cofactors. */
function inv3(m: M3): M3 {
  const [a, b, c, d, e, f, g, h, i] = m;
  const k = 1 / det3(m);
  return [
    (e * i - f * h) * k, (c * h - b * i) * k, (b * f - c * e) * k,
    (f * g - d * i) * k, (a * i - c * g) * k, (c * d - a * f) * k,
    (d * h - e * g) * k, (b * g - a * h) * k, (a * e - b * d) * k,
  ];
}

/**
 * The linear-sRGB → XYZ matrix, derived (the standard way, SMPTE RP 177) from the sRGB primaries R (0.64, 0.33),
 * G (0.30, 0.60), B (0.15, 0.06) and the D65 white (0.3127, 0.3290) of IEC 61966-2-1. Deriving it keeps full
 * precision (the 4-decimal table printed in the standard is this matrix rounded) and makes RGB white map exactly
 * to the reference white, so greys have a* = b* = 0 exactly. The inverse is computed from it, so conversions
 * round-trip to floating-point precision.
 */
function buildSrgbMatrix(): { toXyz: M3; white: V3 } {
  const r = xyToXyz(0.64, 0.33);
  const g = xyToXyz(0.3, 0.6);
  const b = xyToXyz(0.15, 0.06);
  const white = xyToXyz(0.3127, 0.329);
  const prim: M3 = [r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2]];
  const pi = inv3(prim);
  const s0 = pi[0] * white[0] + pi[1] * white[1] + pi[2] * white[2];
  const s1 = pi[3] * white[0] + pi[4] * white[1] + pi[5] * white[2];
  const s2 = pi[6] * white[0] + pi[7] * white[1] + pi[8] * white[2];
  return {
    toXyz: [r[0] * s0, g[0] * s1, b[0] * s2, r[1] * s0, g[1] * s1, b[1] * s2, r[2] * s0, g[2] * s1, b[2] * s2],
    white,
  };
}

const SRGB = buildSrgbMatrix();
const [M00, M01, M02, M10, M11, M12, M20, M21, M22] = SRGB.toXyz;
const [N00, N01, N02, N10, N11, N12, N20, N21, N22] = inv3(SRGB.toXyz);
const [WX, WY, WZ] = SRGB.white;

// ---------------------------------------------------------------------------------------------------------------
// Conversions
// ---------------------------------------------------------------------------------------------------------------

function clamp(v: number, lo: number, hi: number): number {
  return v < lo ? lo : v > hi ? hi : v;
}

function clamp01(v: number): number {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

/** A number option: the value when finite, else the default; then clamped. */
function opt(v: number | undefined, def: number, lo: number, hi: number): number {
  return clamp(typeof v === "number" && Number.isFinite(v) ? v : def, lo, hi);
}

/**
 * sRGB decoding (IEC 61966-2-1): display value 0..1 → linear light. Extended symmetrically to negative values
 * (mirror image), so out-of-gamut results of labToRgb keep their sign instead of turning into NaN.
 */
export function srgbToLinear(v: number): number {
  const a = Math.abs(v);
  const l = a <= 0.04045 ? a / 12.92 : Math.pow((a + 0.055) / 1.055, 2.4);
  return v < 0 ? -l : l;
}

/** sRGB encoding (IEC 61966-2-1): linear light → display value. Mirrored for negative values like srgbToLinear. */
export function linearToSrgb(v: number): number {
  const a = Math.abs(v);
  const e = a <= 0.0031308 ? a * 12.92 : 1.055 * Math.pow(a, 1 / 2.4) - 0.055;
  return v < 0 ? -e : e;
}

/** CIE 1976 Lab forward companding f(t). */
function labF(t: number): number {
  return t > LAB_EPS ? Math.cbrt(t) : (LAB_KAPPA * t + 16) / 116;
}

/** CIE 1976 Lab inverse companding f⁻¹(t). */
function labFInv(t: number): number {
  const t3 = t * t * t;
  return t3 > LAB_EPS ? t3 : (116 * t - 16) / LAB_KAPPA;
}

/** Linear sRGB → CIE Lab (D65). */
function linToLab(r: number, g: number, b: number): V3 {
  const fx = labF((M00 * r + M01 * g + M02 * b) / WX);
  const fy = labF((M10 * r + M11 * g + M12 * b) / WY);
  const fz = labF((M20 * r + M21 * g + M22 * b) / WZ);
  return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}

/** Display sRGB 0..1 → CIE Lab (D65, CIE 1976); L is 0..100. */
export function rgbToLab(r: number, g: number, b: number): [number, number, number] {
  return linToLab(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b));
}

/**
 * CIE Lab (D65) → display sRGB. NOT clamped: colours outside the sRGB gamut come back below 0 or above 1, so
 * callers can see (and decide how to handle) the overflow. See labToRgbClamped for the display-safe version.
 */
export function labToRgb(L: number, a: number, b: number): [number, number, number] {
  const fy = (L + 16) / 116;
  const X = WX * labFInv(fy + a / 500);
  const Y = WY * labFInv(fy);
  const Z = WZ * labFInv(fy - b / 200);
  return [
    linearToSrgb(N00 * X + N01 * Y + N02 * Z),
    linearToSrgb(N10 * X + N11 * Y + N12 * Z),
    linearToSrgb(N20 * X + N21 * Y + N22 * Z),
  ];
}

/** labToRgb with each channel clipped to 0..1 (a simple per-channel gamut clip). */
export function labToRgbClamped(L: number, a: number, b: number): [number, number, number] {
  const c = labToRgb(L, a, b);
  return [clamp01(c[0]), clamp01(c[1]), clamp01(c[2])];
}

/** RGB 0..1 → HSV: hue 0..1 (0 = red, 1/3 = green, 2/3 = blue), saturation 0..1, value 0..1. */
export function rgbToHsv(r: number, g: number, b: number): [number, number, number] {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  const s = max > 0 ? d / max : 0;
  let h = 0;
  if (d > 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h /= 6;
    if (h < 0) h += 1;
  }
  return [h, s, max];
}

/** ITU-R BT.709 luma of display-encoded RGB (the same weights the grading engine uses for saturation). */
export function luma709(r: number, g: number, b: number): number {
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** Display byte → linear light, for the 256 possible 8-bit values (speeds up analyse). */
const LIN8 = (() => {
  const t = new Float64Array(256);
  for (let i = 0; i < 256; i++) t[i] = srgbToLinear(i / 255);
  return t;
})();

// ---------------------------------------------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------------------------------------------

function zeros(n: number): number[] {
  return new Array<number>(n).fill(0);
}

/**
 * The statistics of a picture with no visible pixels: n = 0, Lab mean and spread 0, all-zero histograms (they sum
 * to 0, not 1 — there is nothing to distribute), satMean 0, black 0, white 1, empty palette. Every function in
 * this module treats n = 0 as "no information" and does nothing (identity LUT, identity curves, neutral primaries).
 */
export function emptyStats(): ColourStats {
  return {
    n: 0,
    labMean: [0, 0, 0],
    labStd: [0, 0, 0],
    lumaHist: zeros(BINS),
    rgbHist: [zeros(BINS), zeros(BINS), zeros(BINS)],
    satMean: 0,
    black: 0,
    white: 1,
    palette: [],
  };
}

function bin(v: number): number {
  const i = Math.floor(v * BINS);
  return i < 0 ? 0 : i >= BINS ? BINS - 1 : i;
}

/** Value at percentile p (0..1) of sorted values, interpolating between neighbours (the "linear" definition). */
function sortedQuantile(s: Float64Array, p: number): number {
  const n = s.length;
  if (n === 0) return 0;
  const x = p * (n - 1);
  const i = Math.floor(x);
  const a = s[i] ?? 0;
  const b = s[Math.min(n - 1, i + 1)] ?? a;
  return a + (b - a) * (x - i);
}

/**
 * Percentile p of a 64-bin histogram over 0..1, assuming values are spread evenly inside each bin. Accurate to a
 * fraction of a bin (1/64); used where only the histogram is known (merged clips, per-channel points).
 */
function histQuantile(h: ArrayLike<number>, p: number): number {
  let total = 0;
  for (let i = 0; i < BINS; i++) total += Math.max(0, h[i] ?? 0);
  if (!(total > 0)) return p;
  const target = clamp01(p) * total;
  let c = 0;
  for (let i = 0; i < BINS; i++) {
    const v = Math.max(0, h[i] ?? 0);
    if (v > 0 && c + v >= target) return (i + clamp01((target - c) / v)) / BINS;
    c += v;
  }
  // Rounding left the target just past the last bin: return the top of the highest occupied bin.
  for (let i = BINS - 1; i >= 0; i--) if ((h[i] ?? 0) > 0) return (i + 1) / BINS;
  return 1;
}

/** Mean of a 64-bin histogram over 0..1, with each bin at its centre. */
function histMean(h: ArrayLike<number>): number {
  let s = 0;
  let w = 0;
  for (let i = 0; i < BINS; i++) {
    const v = Math.max(0, h[i] ?? 0);
    s += v * ((i + 0.5) / BINS);
    w += v;
  }
  return w > 0 ? s / w : 0.5;
}

/** Euclidean distance in Lab: CIE 1976 ΔE*ab. */
function deltaE(a: V3, b: V3): number {
  return Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
}

interface Cluster {
  lab: V3;
  w: number;
}

/** Two swatches closer than this (CIE 1976 ΔE*ab) are treated as one colour. */
const PALETTE_MERGE_DE = 7;
/** Swatches lighter than this share of the picture are folded into their nearest neighbour. */
const PALETTE_MIN_WEIGHT = 0.01;
const PALETTE_MAX = 6;

function mergeInto(a: Cluster, b: Cluster): void {
  const w = a.w + b.w;
  if (w <= 0) return;
  a.lab = [(a.lab[0] * a.w + b.lab[0] * b.w) / w, (a.lab[1] * a.w + b.lab[1] * b.w) / w, (a.lab[2] * a.w + b.lab[2] * b.w) / w];
  a.w = w;
}

/**
 * Tidy a set of weighted Lab clusters into a palette: merge pairs closer than PALETTE_MERGE_DE (closest first),
 * merge the closest pairs until at most six remain, fold swatches under 1 % into their nearest neighbour (weight is
 * moved, never lost), then sort heaviest first and normalise the weights to sum 1.
 */
function finishPalette(input: Cluster[]): ColourStats["palette"] {
  const cl = input.filter((c) => c.w > 0 && c.lab.every(Number.isFinite)).map((c) => ({ lab: [...c.lab] as V3, w: c.w }));
  const closest = (): [number, number, number] => {
    let best: [number, number, number] = [-1, -1, Infinity];
    for (let i = 0; i < cl.length; i++) for (let j = i + 1; j < cl.length; j++) {
      const d = deltaE(cl[i]!.lab, cl[j]!.lab);
      if (d < best[2]) best = [i, j, d];
    }
    return best;
  };
  for (;;) {
    const [i, j, d] = closest();
    if (i < 0 || (d >= PALETTE_MERGE_DE && cl.length <= PALETTE_MAX)) break;
    mergeInto(cl[i]!, cl[j]!);
    cl.splice(j, 1);
  }
  for (;;) {
    if (cl.length < 2) break;
    const total = cl.reduce((s, c) => s + c.w, 0);
    let k = 0;
    for (let i = 1; i < cl.length; i++) if (cl[i]!.w < cl[k]!.w) k = i;
    if (cl[k]!.w >= PALETTE_MIN_WEIGHT * total) break;
    let near = -1;
    let nd = Infinity;
    for (let i = 0; i < cl.length; i++) {
      if (i === k) continue;
      const d = deltaE(cl[i]!.lab, cl[k]!.lab);
      if (d < nd) {
        nd = d;
        near = i;
      }
    }
    mergeInto(cl[near]!, cl[k]!);
    cl.splice(k, 1);
  }
  const total = cl.reduce((s, c) => s + c.w, 0);
  if (!(total > 0)) return [];
  cl.sort((a, b) => b.w - a.w);
  return cl.map((c) => ({ rgb: labToRgbClamped(c.lab[0], c.lab[1], c.lab[2]), weight: c.w / total }));
}

/**
 * Dominant colours by k-means in Lab (k up to 6).
 * Initialisation is deterministic: the samples are binned in 10-unit Lab cells and the seeds are the means of the
 * most populated cells (each holding at least 0.5 % of the samples) that lie at least 12 ΔE from the seeds already
 * chosen. Lloyd iterations run on an even subset of at most 12 000 samples; the final assignment, means and weights
 * use every sample. Ties go to the lower cluster index, so the result is the same on every run.
 */
function kmeansPalette(lab: Float64Array, n: number): ColourStats["palette"] {
  if (n <= 0) return [];
  const NA = 26;
  const cells = 11 * NA * NA;
  const count = new Int32Array(cells);
  const sum = new Float64Array(cells * 3);
  for (let i = 0; i < n; i++) {
    const L = lab[i * 3]!;
    const a = lab[i * 3 + 1]!;
    const b = lab[i * 3 + 2]!;
    const li = clamp(Math.floor(L / 10), 0, 10);
    const ai = clamp(Math.floor((a + 130) / 10), 0, NA - 1);
    const bi = clamp(Math.floor((b + 130) / 10), 0, NA - 1);
    const c = (li * NA + ai) * NA + bi;
    count[c]!++;
    sum[c * 3] = sum[c * 3]! + L;
    sum[c * 3 + 1] = sum[c * 3 + 1]! + a;
    sum[c * 3 + 2] = sum[c * 3 + 2]! + b;
  }
  const order: number[] = [];
  for (let c = 0; c < cells; c++) if (count[c]! > 0) order.push(c);
  order.sort((x, y) => count[y]! - count[x]! || x - y);
  const minCount = Math.max(1, 0.005 * n);
  const seeds: V3[] = [];
  for (const c of order) {
    if (seeds.length >= PALETTE_MAX || count[c]! < minCount) break;
    const k = count[c]!;
    const m: V3 = [sum[c * 3]! / k, sum[c * 3 + 1]! / k, sum[c * 3 + 2]! / k];
    if (seeds.every((s) => deltaE(s, m) >= 12)) seeds.push(m);
  }
  if (!seeds.length) {
    const c = order[0]!;
    const k = count[c]!;
    seeds.push([sum[c * 3]! / k, sum[c * 3 + 1]! / k, sum[c * 3 + 2]! / k]);
  }
  const K = seeds.length;
  const cen = new Float64Array(K * 3);
  seeds.forEach((s, i) => cen.set(s, i * 3));
  const nearest = (i: number): number => {
    const L = lab[i * 3]!;
    const a = lab[i * 3 + 1]!;
    const b = lab[i * 3 + 2]!;
    let best = 0;
    let bd = Infinity;
    for (let k = 0; k < K; k++) {
      const dL = L - cen[k * 3]!;
      const da = a - cen[k * 3 + 1]!;
      const db = b - cen[k * 3 + 2]!;
      const d = dL * dL + da * da + db * db;
      if (d < bd) {
        bd = d;
        best = k;
      }
    }
    return best;
  };
  const acc = new Float64Array(K * 4);
  /** One Lloyd step over every `stride`-th sample; returns the largest centre movement (ΔE). */
  const step = (stride: number): number => {
    acc.fill(0);
    for (let i = 0; i < n; i += stride) {
      const k = nearest(i);
      acc[k * 4] = acc[k * 4]! + lab[i * 3]!;
      acc[k * 4 + 1] = acc[k * 4 + 1]! + lab[i * 3 + 1]!;
      acc[k * 4 + 2] = acc[k * 4 + 2]! + lab[i * 3 + 2]!;
      acc[k * 4 + 3] = acc[k * 4 + 3]! + 1;
    }
    let moved = 0;
    for (let k = 0; k < K; k++) {
      const w = acc[k * 4 + 3]!;
      if (w <= 0) continue;
      const m: V3 = [acc[k * 4]! / w, acc[k * 4 + 1]! / w, acc[k * 4 + 2]! / w];
      moved = Math.max(moved, deltaE(m, [cen[k * 3]!, cen[k * 3 + 1]!, cen[k * 3 + 2]!]));
      cen.set(m, k * 3);
    }
    return moved;
  };
  const stride = Math.max(1, Math.floor(n / 12000));
  for (let it = 0; it < 20; it++) if (step(stride) < 0.05) break;
  step(1);
  const clusters: Cluster[] = [];
  for (let k = 0; k < K; k++) {
    const w = acc[k * 4 + 3]!;
    if (w > 0) clusters.push({ lab: [cen[k * 3]!, cen[k * 3 + 1]!, cen[k * 3 + 2]!], w });
  }
  return finishPalette(clusters);
}

/**
 * Measure a picture. Samples a regular grid of about `maxSamples` pixels (default 40 000; every pixel when the
 * picture is smaller), skips pixels with alpha < 8, and reads colours as straight (non-premultiplied) RGBA bytes,
 * the ImageData convention. Lab mean and std are over the samples (population std). lumaHist uses BT.709 luma of
 * the display values. black / white are the exact 1st / 99th luma percentiles of the samples. An empty or fully
 * transparent picture returns emptyStats() — no NaN anywhere.
 */
export function analyse(img: Pixels, o: { maxSamples?: number } = {}): ColourStats {
  const w = Math.floor(img.width);
  const h = Math.floor(img.height);
  if (!(w > 0 && h > 0) || !img.data) return emptyStats();
  const data = img.data;
  const maxS = Math.floor(opt(o.maxSamples, 40000, 1, 1e8));
  const total = w * h;
  let nx = w;
  let ny = h;
  if (total > maxS) {
    const stepSize = Math.sqrt(total / maxS);
    nx = clamp(Math.round(w / stepSize), 1, w);
    ny = clamp(Math.round(h / stepSize), 1, h);
  }
  const cap = nx * ny;
  const lab = new Float64Array(cap * 3);
  const lum = new Float64Array(cap);
  const lh = new Float64Array(BINS);
  const rh = new Float64Array(BINS);
  const gh = new Float64Array(BINS);
  const bh = new Float64Array(BINS);
  let satSum = 0;
  let n = 0;
  const xs = new Int32Array(nx);
  for (let i = 0; i < nx; i++) xs[i] = Math.floor(((i + 0.5) * w) / nx);
  for (let j = 0; j < ny; j++) {
    const y = Math.floor(((j + 0.5) * h) / ny);
    const row = y * w;
    for (let i = 0; i < nx; i++) {
      const p = (row + xs[i]!) * 4;
      if (p + 3 >= data.length) continue;
      if (data[p + 3]! < 8) continue;
      const R = data[p]!;
      const G = data[p + 1]!;
      const B = data[p + 2]!;
      const c = linToLab(LIN8[R]!, LIN8[G]!, LIN8[B]!);
      lab[n * 3] = c[0];
      lab[n * 3 + 1] = c[1];
      lab[n * 3 + 2] = c[2];
      const r = R / 255;
      const g = G / 255;
      const b = B / 255;
      const Y = luma709(r, g, b);
      lum[n] = Y;
      lh[bin(Y)]!++;
      rh[bin(r)]!++;
      gh[bin(g)]!++;
      bh[bin(b)]!++;
      const mx = Math.max(R, G, B);
      if (mx > 0) satSum += (mx - Math.min(R, G, B)) / mx;
      n++;
    }
  }
  if (n === 0) return emptyStats();
  let sL = 0;
  let sa = 0;
  let sb = 0;
  for (let i = 0; i < n; i++) {
    sL += lab[i * 3]!;
    sa += lab[i * 3 + 1]!;
    sb += lab[i * 3 + 2]!;
  }
  const mean: V3 = [sL / n, sa / n, sb / n];
  let vL = 0;
  let va = 0;
  let vb = 0;
  for (let i = 0; i < n; i++) {
    const dL = lab[i * 3]! - mean[0];
    const da = lab[i * 3 + 1]! - mean[1];
    const db = lab[i * 3 + 2]! - mean[2];
    vL += dL * dL;
    va += da * da;
    vb += db * db;
  }
  const sorted = lum.subarray(0, n).sort();
  const norm = (a: Float64Array) => Array.from(a, (v) => v / n);
  return {
    n,
    labMean: mean,
    labStd: [Math.sqrt(vL / n), Math.sqrt(va / n), Math.sqrt(vb / n)],
    lumaHist: norm(lh),
    rgbHist: [norm(rh), norm(gh), norm(bh)],
    satMean: satSum / n,
    black: sortedQuantile(sorted, 0.01),
    white: sortedQuantile(sorted, 0.99),
    palette: kmeansPalette(lab, n),
  };
}

/**
 * Combine the statistics of several frames of one clip. Weights default to each frame's n (so the result is the
 * statistics of all samples pooled); negative, NaN or missing-data (n = 0) entries count as 0. n is the total of
 * the frames used. Means and histograms are weighted averages (histograms renormalised to sum 1); the Lab std is the
 * pooled one, including the spread of the frame means: σ² = Σ wᵢ(σᵢ² + (μᵢ − μ)²). black / white are read from the
 * merged luma histogram and then kept between the frames' own lowest and highest values (the true percentile of a
 * mixture always lies there), so they are exact when the frames agree and within about one bin (1/64) otherwise.
 * The palette is the frames' palettes pooled and tidied with the same merge rules as analyse.
 * Throws a RangeError if `weights` is given with a different length than `list`.
 */
export function mergeStats(list: ColourStats[], weights?: number[]): ColourStats {
  if (weights && weights.length !== list.length) throw new RangeError("mergeStats: weights must have one entry per stats");
  const items = list
    .map((s, i) => ({ s, w: weights ? weights[i]! : s.n }))
    .filter((it) => it.s.n > 0 && Number.isFinite(it.w) && it.w > 0);
  const W = items.reduce((t, it) => t + it.w, 0);
  if (!items.length || !(W > 0)) return emptyStats();
  const mean: V3 = [0, 0, 0];
  for (const { s, w } of items) for (let c = 0; c < 3; c++) mean[c] = mean[c]! + ((w / W) * s.labMean[c]!);
  const std: V3 = [0, 0, 0];
  for (let c = 0; c < 3; c++) {
    let v = 0;
    for (const { s, w } of items) {
      const d = s.labMean[c]! - mean[c]!;
      v += (w / W) * (s.labStd[c]! * s.labStd[c]! + d * d);
    }
    std[c] = Math.sqrt(Math.max(0, v));
  }
  const mix = (get: (s: ColourStats) => number[]): number[] => {
    const out = zeros(BINS);
    for (const { s, w } of items) {
      const h = get(s);
      let t = 0;
      for (let i = 0; i < BINS; i++) t += Math.max(0, h[i] ?? 0);
      if (t > 0) for (let i = 0; i < BINS; i++) out[i] = out[i]! + (((w / W) * Math.max(0, h[i] ?? 0)) / t);
    }
    const t = out.reduce((a, b) => a + b, 0);
    return t > 0 ? out.map((v) => v / t) : out;
  };
  const lumaHist = mix((s) => s.lumaHist);
  const blacks = items.map((it) => it.s.black);
  const whites = items.map((it) => it.s.white);
  const pal: Cluster[] = [];
  for (const { s, w } of items) for (const p of s.palette) pal.push({ lab: rgbToLab(p.rgb[0], p.rgb[1], p.rgb[2]), w: (w / W) * p.weight });
  return {
    n: items.reduce((t, it) => t + it.s.n, 0),
    labMean: mean,
    labStd: std,
    lumaHist,
    rgbHist: [mix((s) => s.rgbHist[0]), mix((s) => s.rgbHist[1]), mix((s) => s.rgbHist[2])],
    satMean: items.reduce((t, { s, w }) => t + (w / W) * s.satMean, 0),
    black: clamp(histQuantile(lumaHist, 0.01), Math.min(...blacks), Math.max(...blacks)),
    white: clamp(histQuantile(lumaHist, 0.99), Math.min(...whites), Math.max(...whites)),
    palette: finishPalette(pal),
  };
}

// ---------------------------------------------------------------------------------------------------------------
// Tone matching (shared by the LUT and the curves)
// ---------------------------------------------------------------------------------------------------------------

/**
 * Slope limits of every matched tone curve. Below 0.4 a range of input levels is squeezed into less than half the
 * output range (detail crushes, and with smoothing a curve can look stepped); above 2.5 each 8-bit input step
 * spreads over more than two output steps (visible banding). Within these limits the curve can never invert or
 * posterise, whatever the reference.
 */
const SLOPE_MIN = 0.4;
const SLOPE_MAX = 2.5;
/**
 * Below the source's black point or above its white point there are no pixels to crush, so the curve may flatten
 * further there (keeping a lifted-black source from being forced below 0 when it is matched to a deep-black
 * reference) — but it still rises, so the curve stays strictly increasing.
 */
const SLOPE_MIN_UNUSED = 0.05;

/** Gaussian blur of a histogram (σ = 1 bin), mirrored at the ends so no mass is lost; normalised to sum 1. */
function smoothHist(h: ArrayLike<number>): Float64Array | null {
  const K = [-3, -2, -1, 0, 1, 2, 3].map((k) => Math.exp((-k * k) / 2)); // Gaussian, σ = 1 bin, cut at ±3σ
  const ks = K.reduce((a, b) => a + b, 0);
  const out = new Float64Array(BINS);
  let total = 0;
  for (let j = 0; j < BINS; j++) {
    const v = Math.max(0, Number(h[j] ?? 0) || 0);
    if (v === 0) continue;
    total += v;
    for (let k = -3; k <= 3; k++) {
      let t = j + k;
      if (t < 0) t = -t - 1;
      if (t >= BINS) t = 2 * BINS - 1 - t;
      out[t] = out[t]! + ((v * K[k + 3]!) / ks);
    }
  }
  if (!(total > 0)) return null;
  for (let i = 0; i < BINS; i++) out[i] = out[i]! / total + 1e-12;
  const t2 = out.reduce((a, b) => a + b, 0);
  for (let i = 0; i < BINS; i++) out[i] = out[i]! / t2;
  return out;
}

/**
 * Histogram matching: a tone curve T (sampled at the 65 bin edges 0, 1/64 … 1) that maps the source distribution
 * onto the reference one, T = F_ref⁻¹ ∘ F_src.
 *  1. Both histograms are smoothed (Gaussian, σ = 1 bin).
 *  2. 129 matching quantile pairs (0.5 % … 99.5 %) give the curve over the range the source actually occupies.
 *     Outside that range (below the source's black or above its white, where there is no data) the curve continues
 *     in a straight line with the overall slope of the matched range — or, where that line would leave 0..1 and
 *     clip the unused levels flat, in a straight line to 0 (at x = 0) or 1 (at x = 1).
 *  3. The slopes are smoothed ([1 2 1]/4, twice) and clamped to SLOPE_MIN..SLOPE_MAX (SLOPE_MIN_UNUSED..SLOPE_MAX
 *     outside the source's occupied range), then integrated again. The constant of integration is chosen by least
 *     squares, weighted by the source histogram, so the levels the picture actually uses stay where the matching
 *     put them.
 * Result: strictly increasing, not clamped (callers clamp to 0..1). Identical histograms give the identity.
 * Returns null when either histogram is empty.
 */
function toneMatch(srcHist: ArrayLike<number>, refHist: ArrayLike<number>): Float64Array | null {
  const s = smoothHist(srcHist);
  const r = smoothHist(refHist);
  if (!s || !r) return null;
  const Q = 128;
  const px: number[] = [];
  const py: number[] = [];
  for (let k = 0; k <= Q; k++) {
    const p = 0.005 + (0.99 * k) / Q;
    const x = histQuantile(s, p);
    const y = histQuantile(r, p);
    const last = px.length - 1;
    if (last >= 0 && x - px[last]! < 1e-6) {
      // Same source level for several quantiles (a spike in the histogram): keep the mean of their targets.
      py[last] = (py[last]! + y) / 2;
      continue;
    }
    px.push(x);
    py.push(y);
  }
  const x0 = px[0]!;
  const y0 = py[0]!;
  const x1 = px[px.length - 1]!;
  const y1 = py[py.length - 1]!;
  const slope = x1 - x0 > 1e-3 ? clamp((y1 - y0) / (x1 - x0), SLOPE_MIN, SLOPE_MAX) : 1;
  // The straight tails would leave 0..1 (clipping unused levels flat): end them at 0 / 1 instead.
  const lowTail = x0 <= 1e-6 || y0 - x0 * slope >= 0;
  const highTail = x1 >= 1 - 1e-6 || y1 + (1 - x1) * slope <= 1;
  const T = new Float64Array(BINS + 1);
  let seg = 0;
  for (let i = 0; i <= BINS; i++) {
    const x = i / BINS;
    if (x <= x0) T[i] = lowTail ? y0 + (x - x0) * slope : (y0 * x) / x0;
    else if (x >= x1) T[i] = highTail ? y1 + (x - x1) * slope : y1 + ((1 - y1) * (x - x1)) / (1 - x1);
    else {
      while (seg < px.length - 2 && px[seg + 1]! < x) seg++;
      const xa = px[seg]!;
      const xb = px[seg + 1]!;
      T[i] = py[seg]! + ((py[seg + 1]! - py[seg]!) * (x - xa)) / (xb - xa);
    }
  }
  let d = new Float64Array(BINS);
  for (let i = 0; i < BINS; i++) d[i] = (T[i + 1]! - T[i]!) * BINS;
  for (let pass = 0; pass < 2; pass++) {
    const e = new Float64Array(BINS);
    for (let i = 0; i < BINS; i++) e[i] = (d[Math.max(0, i - 1)]! + 2 * d[i]! + d[Math.min(BINS - 1, i + 1)]!) / 4;
    d = e;
  }
  const U = new Float64Array(BINS + 1);
  for (let i = 0; i < BINS; i++) {
    const used = (i + 1) / BINS > x0 && i / BINS < x1;
    U[i + 1] = U[i]! + clamp(d[i]!, used ? SLOPE_MIN : SLOPE_MIN_UNUSED, SLOPE_MAX) / BINS;
  }
  let num = 0;
  let den = 0;
  for (let i = 0; i <= BINS; i++) {
    const wt = ((s[Math.max(0, i - 1)] ?? 0) + (s[Math.min(BINS - 1, i)] ?? 0)) / 2;
    num += wt * (T[i]! - U[i]!);
    den += wt;
  }
  const off = den > 0 ? num / den : 0;
  for (let i = 0; i <= BINS; i++) U[i] = U[i]! + off;
  return U;
}

/** Evaluate a 65-node curve (nodes at i/64) at x in 0..1 by linear interpolation. */
function evalNodes(T: Float64Array, x: number): number {
  const t = clamp01(x) * BINS;
  const i = Math.min(BINS - 1, Math.floor(t));
  return T[i]! + (T[i + 1]! - T[i]!) * (t - i);
}

// ---------------------------------------------------------------------------------------------------------------
// Shot match: 3D LUT
// ---------------------------------------------------------------------------------------------------------------

/**
 * Chroma scale limits for the Reinhard transfer: the reference's a/b spread may at most double or halve the source's.
 * Spreads are floored at 1 ΔE (about one just-noticeable difference) before dividing, so a nearly grey source or
 * reference cannot produce a huge ratio from noise.
 */
const CHROMA_SCALE_MIN = 0.5;
const CHROMA_SCALE_MAX = 2;

function chromaScale(srcStd: number, refStd: number): number {
  return clamp(Math.max(refStd, 1) / Math.max(srcStd, 1), CHROMA_SCALE_MIN, CHROMA_SCALE_MAX);
}

/** Smooth 0→1 ramp (cubic smoothstep) of t over 0..1. */
function smooth01(t: number): number {
  const u = clamp01(t);
  return u * u * (3 - 2 * u);
}

/**
 * Shot match as a 3D LUT (red fastest, the .cube order; default 33³). Works in CIE Lab:
 *  - L: histogram matching of the source's luma distribution onto the reference's (see toneMatch: monotonic,
 *    smoothed, slopes held within 0.4..2.5). The curve is built on display luma and applied to L through the grey
 *    axis (L → the grey of that L → curve → back to L), so greys follow it exactly and colours follow it by lightness.
 *  - a, b: Reinhard-style statistics transfer, a' = μ_ref + (a − μ_src)·k, with k = σ_ref/σ_src held within 0.5..2.
 *    The colour change fades out (smoothstep) over the darkest and lightest 8 L of the output, so pure black and
 *    pure white stay neutral instead of being pushed out of gamut.
 *  - `luma` and `chroma` (0..1, default 1) weight the two parts; `strength` (0..1, default 1) blends the result with
 *    the identity in RGB. Output is clipped to 0..1 per channel and always finite.
 * If either stats has n = 0 the LUT is the identity.
 */
export function matchLut(src: ColourStats, ref: ColourStats, o: { size?: number; strength?: number; luma?: number; chroma?: number } = {}): Lut3D {
  const size = Math.round(opt(o.size, 33, 2, 129));
  const strength = opt(o.strength, 1, 0, 1);
  const wL = opt(o.luma, 1, 0, 1);
  const wC = opt(o.chroma, 1, 0, 1);
  const data = new Float32Array(size * size * size * 3);
  const tone = src.n > 0 && ref.n > 0 && strength > 0 ? toneMatch(src.lumaHist, ref.lumaHist) : null;
  const ka = chromaScale(src.labStd[1], ref.labStd[1]);
  const kb = chromaScale(src.labStd[2], ref.labStd[2]);
  const [, ma, mb] = src.labMean;
  const [, ra, rb] = ref.labMean;
  const inv = 1 / (size - 1);
  let p = 0;
  for (let bi = 0; bi < size; bi++) {
    for (let gi = 0; gi < size; gi++) {
      for (let ri = 0; ri < size; ri++, p += 3) {
        const r = ri * inv;
        const g = gi * inv;
        const b = bi * inv;
        if (!tone) {
          data[p] = r;
          data[p + 1] = g;
          data[p + 2] = b;
          continue;
        }
        const [L, a, bb] = rgbToLab(r, g, b);
        const grey = linearToSrgb(labFInv((L + 16) / 116));
        const L1 = 116 * labF(srgbToLinear(clamp01(evalNodes(tone, grey)))) - 16;
        const L2 = L + wL * (L1 - L);
        const fade = smooth01(L2 / 8) * smooth01((100 - L2) / 8);
        const a2 = a + wC * fade * (ra + (a - ma) * ka - a);
        const b2 = bb + wC * fade * (rb + (bb - mb) * kb - bb);
        const out = labToRgb(L2, a2, b2);
        const o0 = r + strength * (out[0] - r);
        const o1 = g + strength * (out[1] - g);
        const o2 = b + strength * (out[2] - b);
        data[p] = Number.isFinite(o0) ? clamp01(o0) : r;
        data[p + 1] = Number.isFinite(o1) ? clamp01(o1) : g;
        data[p + 2] = Number.isFinite(o2) ? clamp01(o2) : b;
      }
    }
  }
  return { size, data, title: "Shot match" };
}

// ---------------------------------------------------------------------------------------------------------------
// Shot match: curves
// ---------------------------------------------------------------------------------------------------------------

const IDENTITY_CURVE: CurvePts = [
  [0, 0],
  [1, 1],
];

/**
 * Reduce a 65-node curve to at most `points` control points: start with both ends, then repeatedly add the node
 * farthest from the straight-line interpolation of the points chosen so far (at least 2/64 from any chosen point,
 * so the handles stay grabbable), until the limit or until no node is off by more than 0.001.
 */
function reduceCurve(y: Float64Array, points: number): CurvePts {
  const chosen = [0, BINS];
  while (chosen.length < points) {
    let best = -1;
    let bestErr = 0.001;
    for (let s = 0; s < chosen.length - 1; s++) {
      const i0 = chosen[s]!;
      const i1 = chosen[s + 1]!;
      for (let i = i0 + 2; i <= i1 - 2; i++) {
        const lin = y[i0]! + ((y[i1]! - y[i0]!) * (i - i0)) / (i1 - i0);
        const err = Math.abs(y[i]! - lin);
        if (err > bestErr) {
          bestErr = err;
          best = i;
        }
      }
    }
    if (best < 0) break;
    chosen.push(best);
    chosen.sort((a, b) => a - b);
  }
  return chosen.map((i) => [i / BINS, y[i]!] as [number, number]);
}

/**
 * Shot match as editable per-channel curves (r, g, b): histogram matching of each channel's distribution (same
 * algorithm and slope limits as the LUT's tone curve), blended with the identity by `strength` (0..1, default 1),
 * clipped to 0..1 and reduced to at most `points` control points (default 7, allowed 2..24 — the engine keeps 24).
 * Every curve starts at x = 0, ends at x = 1, and is monotonic non-decreasing. The engine draws them with monotone
 * cubic interpolation, which never overshoots between points, so the drawn curve is monotonic too.
 * Any stats with n = 0 gives identity curves.
 */
export function matchCurves(src: ColourStats, ref: ColourStats, o: { strength?: number; points?: number } = {}): NodeCurves {
  const strength = opt(o.strength, 1, 0, 1);
  const points = Math.round(opt(o.points, 7, 2, 24));
  const one = (c: 0 | 1 | 2): CurvePts => {
    if (src.n <= 0 || ref.n <= 0 || strength <= 0) return IDENTITY_CURVE.map((p) => [p[0], p[1]] as [number, number]);
    const T = toneMatch(src.rgbHist[c], ref.rgbHist[c]);
    if (!T) return IDENTITY_CURVE.map((p) => [p[0], p[1]] as [number, number]);
    const y = new Float64Array(BINS + 1);
    for (let i = 0; i <= BINS; i++) {
      const x = i / BINS;
      y[i] = clamp01(x + strength * (T[i]! - x));
    }
    return reduceCurve(y, points);
  };
  return { r: one(0), g: one(1), b: one(2) };
}

// ---------------------------------------------------------------------------------------------------------------
// Primaries (lift / gamma / gain / sat / temp / tint)
// ---------------------------------------------------------------------------------------------------------------

/**
 * One channel of the engine's primaries reduced to y = (lift + k·x)^(1/γ), where k = (temp/tint factor)·(1 + gain −
 * lift) and γ = 1 + gamma (see gradeNode: c·m, then c·(1+G) + Lf·(1−c) = Lf + c·(1+G−Lf), then pow 1/γ).
 */
interface ChannelFit {
  lift: number;
  k: number;
  gamma: number;
}

/**
 * Fit y = (lift + k·x)^(1/γ) through three points (black, mid, white): γ by bisection so the mid point lands
 * (u(γ) = (y1^γ − y0^γ)/(y2^γ − y0^γ) falls as γ rises), then k and lift so black and white land exactly. γ is held
 * within gLo..gHi and k within 0.2..3, and lift within −0.5..0.5; when a limit bites, lift is refitted by least
 * squares over the three points. A flat picture (white − black < 0.02) gets a plain gain through its mid level.
 */
function fit3(x: V3, y: V3, gLo: number, gHi: number): ChannelFit {
  const [x0, x1, x2] = x;
  let [y0, y1, y2] = y.map(clamp01) as V3;
  if (x2 - x0 < 0.02) {
    if (x1 > 0.01) return { lift: 0, k: clamp(y1 / x1, 0.2, 3), gamma: 1 };
    return { lift: clamp(y1 - x1, -0.5, 0.5), k: 1, gamma: 1 };
  }
  y1 = clamp(y1, y0, y2);
  if (y2 - y0 < 1e-3) y2 = y0 + 1e-3;
  const t = clamp((x1 - x0) / (x2 - x0), 0, 1);
  const u = (g: number) => (Math.pow(y1, g) - Math.pow(y0, g)) / (Math.pow(y2, g) - Math.pow(y0, g));
  let gam: number;
  if (t >= u(gLo)) gam = gLo;
  else if (t <= u(gHi)) gam = gHi;
  else {
    let lo = gLo;
    let hi = gHi;
    for (let i = 0; i < 60; i++) {
      const mid = (lo + hi) / 2;
      if (u(mid) > t) lo = mid;
      else hi = mid;
    }
    gam = (lo + hi) / 2;
  }
  const Y0 = Math.pow(y0, gam);
  const Y1 = Math.pow(y1, gam);
  const Y2 = Math.pow(y2, gam);
  let k = (Y2 - Y0) / (x2 - x0);
  let lift = Y0 - k * x0;
  const kc = clamp(k, 0.2, 3);
  if (kc !== k || Math.abs(lift) > 0.5) {
    k = kc;
    lift = (Y0 - k * x0 + (Y1 - k * x1) + (Y2 - k * x2)) / 3;
  }
  return { lift: clamp(lift, -0.5, 0.5), k, gamma: gam };
}

type Quad = [number, number, number, number];

/** Split per-channel values into [r, g, b, master] with master = their mean, each part clamped to −1..1. */
function splitMaster(v: V3): Quad {
  const m = (v[0] + v[1] + v[2]) / 3;
  return [clamp(v[0] - m, -1, 1), clamp(v[1] - m, -1, 1), clamp(v[2] - m, -1, 1), clamp(m, -1, 1)];
}

/** Snap values within 1e-9 of 0 to exactly 0, so "no change" reads as 0 in the UI. */
function tidy(q: Quad): Quad {
  return q.map((v) => (Math.abs(v) < 1e-9 ? 0 : v)) as Quad;
}

/**
 * Turn three channel fits into node primaries. The common colour balance of the per-channel scale k goes into temp
 * and tint (the person's warmer/cooler and green/magenta controls; temp from the red/blue ratio, tint from green
 * against the result), clamped to −1..1; what they cannot carry is left in the per-channel gains. Lift and gamma go
 * to [r, g, b, master] with master = mean.
 */
function toPrimaries(f: [ChannelFit, ChannelFit, ChannelFit]): Pick<ColorNode, "lift" | "gamma" | "gain" | "temp" | "tint"> {
  const [fr, fg, fb] = f;
  const q = fr.k / fb.k;
  const temp = clamp((q - 1) / (0.12 * (q + 1)), -1, 1);
  const mr = 1 + temp * 0.12;
  const mb = 1 - temp * 0.12;
  const sAvg = (fr.k / mr + fb.k / mb) / 2;
  const tint = clamp((fg.k / sAvg - 1) / 0.08, -1, 1);
  const mg = 1 + tint * 0.08;
  const m: V3 = [mr, mg, mb];
  const gainCh = f.map((c, i) => c.k / m[i]! - 1 + c.lift) as V3;
  return {
    lift: tidy(splitMaster([fr.lift, fg.lift, fb.lift])),
    gamma: tidy(splitMaster([fr.gamma - 1, fg.gamma - 1, fb.gamma - 1])),
    gain: tidy(splitMaster(gainCh)),
    temp: Math.abs(temp) < 1e-9 ? 0 : temp,
    tint: Math.abs(tint) < 1e-9 ? 0 : tint,
  };
}

/** The engine's primaries on one colour, exactly as gradeNode applies them (no offset, contrast or curves). */
function applyPrimaries(p: Pick<ColorNode, "lift" | "gamma" | "gain" | "sat" | "temp" | "tint">, c: V3): V3 {
  const m: V3 = [1 + p.temp * 0.12, 1 + p.tint * 0.08, 1 - p.temp * 0.12];
  const g: V3 = [0, 0, 0];
  for (let i = 0; i < 3; i++) {
    let v = c[i]! * m[i]!;
    v = v * (1 + p.gain[i]! + p.gain[3]) + (p.lift[i]! + p.lift[3]) * (1 - v);
    g[i] = Math.pow(Math.max(v, 0), 1 / Math.max(0.05, 1 + p.gamma[i]! + p.gamma[3]));
  }
  const l = luma709(g[0], g[1], g[2]);
  return [l + (g[0] - l) * p.sat, l + (g[1] - l) * p.sat, l + (g[2] - l) * p.sat];
}

/** A channel's black, mid and white levels: its 1st, 50th and 99th percentiles (from the 64-bin histogram). */
function channelPoints(s: ColourStats, c: 0 | 1 | 2): V3 {
  const h = s.rgbHist[c];
  return [histQuantile(h, 0.01), histQuantile(h, 0.5), histQuantile(h, 0.99)];
}

/** Gamma (γ = 1 + gamma) range used when matching: wider would let a noisy reference bend mid-tones too far. */
const MATCH_GAMMA_MIN = 0.5;
const MATCH_GAMMA_MAX = 2;
/** Saturation range of matchPrimaries (the node allows 0..2; beyond this a noisy reference would dominate). */
const MATCH_SAT_MIN = 0.5;
const MATCH_SAT_MAX = 1.5;

/**
 * Shot match with primaries only — the simplest, most editable approximation. For each channel the source's
 * black / mid / white levels (1st / 50th / 99th percentile) are sent to the reference's (blended by `strength`,
 * 0..1, default 1) by solving the engine's own math, y = (lift + k·x)^(1/γ), through those three points (γ held to
 * 0.5..2). The common part of the per-channel scale becomes temp / tint, the rest gain; lift and gamma are split into
 * master + per-channel. Saturation: the change the primaries already make is estimated on the source palette, and
 * sat covers the remaining ratio to the reference's mean saturation, held to 0.5..1.5. Ranges: lift, gamma, gain
 * parts −1..1, temp and tint −1..1. Any stats with n = 0 gives neutral values (no change).
 */
export function matchPrimaries(src: ColourStats, ref: ColourStats, o: { strength?: number } = {}): Pick<ColorNode, "lift" | "gamma" | "gain" | "sat" | "temp" | "tint"> {
  const st = opt(o.strength, 1, 0, 1);
  const neutral = { lift: [0, 0, 0, 0] as Quad, gamma: [0, 0, 0, 0] as Quad, gain: [0, 0, 0, 0] as Quad, sat: 1, temp: 0, tint: 0 };
  if (src.n <= 0 || ref.n <= 0 || st <= 0) return neutral;
  const fits = ([0, 1, 2] as const).map((c) => {
    const x = channelPoints(src, c);
    const r = channelPoints(ref, c);
    const y: V3 = [x[0] + st * (r[0] - x[0]), x[1] + st * (r[1] - x[1]), x[2] + st * (r[2] - x[2])];
    return fit3(x, y, MATCH_GAMMA_MIN, MATCH_GAMMA_MAX);
  }) as [ChannelFit, ChannelFit, ChannelFit];
  const prim = { ...toPrimaries(fits), sat: 1 };
  let before = 0;
  let after = 0;
  for (const p of src.palette) {
    before += p.weight * rgbToHsv(p.rgb[0], p.rgb[1], p.rgb[2])[1];
    const c = applyPrimaries(prim, p.rgb).map(clamp01) as V3;
    after += p.weight * rgbToHsv(c[0], c[1], c[2])[1];
  }
  const est = before > 0.02 ? src.satMean * (after / before) : src.satMean;
  if (est > 0.02) {
    const target = est + st * (ref.satMean - est);
    prim.sat = clamp(target / est, MATCH_SAT_MIN, MATCH_SAT_MAX);
  }
  return prim;
}

// ---------------------------------------------------------------------------------------------------------------
// Auto balance
// ---------------------------------------------------------------------------------------------------------------

/**
 * How much of a colour cast Auto balance removes, given the cast's size C (ΔE*ab of the picture's mean a, b from
 * neutral). Casts up to 10 are removed fully (about the size of a typical white-balance slip, e.g. daylight
 * balance under warm interior light). Beyond that only a quarter of the excess is removed, and never more than 16 in
 * total: a strong overall colour is more often the scene (a sunset, a neon street, a forest) than an error, so Auto
 * balance leans it toward neutral but never makes it grey.
 */
function castRemoved(C: number): number {
  return C <= 10 ? C : Math.min(16, 10 + 0.25 * (C - 10));
}

/** Auto balance gamma (γ = 1 + gamma) range: a gentle mid-tone fix, not a look. */
const AUTO_GAMMA_MIN = 0.75;
const AUTO_GAMMA_MAX = 1.33;

/**
 * Auto balance: neutralise a colour cast and set sensible black / white points, as primaries under the engine math.
 *  - Cast estimate: the mean of two illuminant estimates, each normalised to unit BT.709 luma — grey world (the
 *    per-channel means) and brightest neutral (the lightest palette swatch that could be a neutral surface under a
 *    cast: Lab chroma under 25 and L above 50; when there is none, grey world alone is used). The swatch is used
 *    rather than per-channel highlights because those can come from different, saturated pixels (a red neon and
 *    a blue sky would read as a white). The per-channel scale that neutralises it is applied only partly: as a fraction
 *    castRemoved(C)/C of the full correction (geometrically), see castRemoved for the rule protecting colourful
 *    pictures. The scale goes to temp / tint first, the remainder to per-channel gain.
 *  - Levels: a black point above 0.02 is brought down to 0.02 and a white point under 0.96 up to 0.96, with the
 *    stretch held to at most 1.5× (both ends then move the same fraction of the way, so a night scene is not
 *    forced to full range and neither end moves backwards) and the median luma kept where it
 *    was, so exposure does not jump; gamma held to 0.75..1.33. Pictures with almost no range (white − black < 0.05)
 *    get no level change.
 *  - `strength` (0..1, default 1) scales both parts. A neutral, full-range picture gets all zeros.
 * n = 0 gives neutral values.
 */
export function autoBalance(stats: ColourStats, o: { strength?: number } = {}): Pick<ColorNode, "lift" | "gain" | "gamma" | "temp" | "tint"> {
  const st = opt(o.strength, 1, 0, 1);
  if (stats.n <= 0 || st <= 0) return { lift: [0, 0, 0, 0], gain: [0, 0, 0, 0], gamma: [0, 0, 0, 0], temp: 0, tint: 0 };
  // White balance: the per-channel scale K.
  const gw: V3 = [histMean(stats.rgbHist[0]), histMean(stats.rgbHist[1]), histMean(stats.rgbHist[2])];
  let wp: V3 | null = null;
  let wpL = 50;
  for (const p of stats.palette) {
    const [L, a, b] = rgbToLab(p.rgb[0], p.rgb[1], p.rgb[2]);
    if (L > wpL && Math.hypot(a, b) < 25) {
      wpL = L;
      wp = p.rgb;
    }
  }
  const lg = luma709(gw[0], gw[1], gw[2]);
  const lw = wp ? luma709(wp[0], wp[1], wp[2]) : 0;
  const K: V3 = [1, 1, 1];
  if (lg > 0.01) {
    const C = Math.hypot(stats.labMean[1], stats.labMean[2]);
    const frac = C > 1e-9 ? castRemoved(C) / C : 1;
    for (let c = 0; c < 3; c++) {
      const e = wp && lw > 0.01 ? (gw[c]! / lg + wp[c]! / lw) / 2 : gw[c]! / lg;
      if (e > 1e-3) K[c] = Math.pow(1 / e, frac * st);
    }
  }
  // Levels on luma: black, median, white → targets, as one fit shared by the three channels.
  const b = stats.black;
  const w = stats.white;
  const med = histQuantile(stats.lumaHist, 0.5);
  let lv: ChannelFit = { lift: 0, k: 1, gamma: 1 };
  if (w - b >= 0.05) {
    let tb = Math.min(b, 0.02);
    let tw = Math.max(w, 0.96);
    const grow = b - tb + (tw - w);
    const allowed = 0.5 * (w - b);
    if (grow > allowed) {
      // Too much stretch: move both ends the same fraction of the way, so neither end goes the wrong way.
      const k = allowed / grow;
      tb = b - k * (b - tb);
      tw = w + k * (tw - w);
    }
    tb = b + st * (tb - b);
    tw = w + st * (tw - w);
    // Keep the median target strictly inside the new range (2 % margin) so the gamma solve stays well posed.
    const tm = clamp(med, tb + 0.02 * (tw - tb), tw - 0.02 * (tw - tb));
    lv = fit3([b, clamp(med, b, w), w], [tb, tm, tw], AUTO_GAMMA_MIN, AUTO_GAMMA_MAX);
  }
  // Channel c: y = (lift + k·K_c·x)^(1/γ) — the white-balance scale goes before the levels, as temp/tint do.
  const fits = K.map((k) => ({ lift: lv.lift, k: lv.k * k, gamma: lv.gamma })) as [ChannelFit, ChannelFit, ChannelFit];
  return toPrimaries(fits);
}

// ---------------------------------------------------------------------------------------------------------------
// Comparing looks
// ---------------------------------------------------------------------------------------------------------------

/** Earth mover's distance between two 64-bin histograms over 0..1, in display levels (0..1). */
function histEmd(a: ArrayLike<number>, b: ArrayLike<number>): number {
  let ta = 0;
  let tb = 0;
  for (let i = 0; i < BINS; i++) {
    ta += Math.max(0, a[i] ?? 0);
    tb += Math.max(0, b[i] ?? 0);
  }
  if (!(ta > 0) || !(tb > 0)) return ta > 0 || tb > 0 ? 1 : 0;
  let ca = 0;
  let cb = 0;
  let d = 0;
  for (let i = 0; i < BINS - 1; i++) {
    ca += Math.max(0, a[i] ?? 0) / ta;
    cb += Math.max(0, b[i] ?? 0) / tb;
    d += Math.abs(ca - cb);
  }
  return d / BINS;
}

/**
 * How different two looks are; 0 = the same statistics. Roughly in CIE ΔE*ab units:
 *   ΔE of the Lab means + ½ × the summed difference of the L, a, b spreads
 *   + 100 × luma-histogram earth mover's distance (a tonal shift of 0.01 display level ≈ 1)
 *   + 50 × the mean of the three channel-histogram distances + 50 × the difference of mean saturation.
 * As a guide: under about 3 the shots read as matched, 5–10 is a visible difference, above 20 a different look.
 * Symmetric and never negative. If exactly one of the two is empty (n = 0) the result is 100; both empty gives 0.
 */
export function statsDistance(a: ColourStats, b: ColourStats): number {
  if (a.n <= 0 || b.n <= 0) return a.n <= 0 && b.n <= 0 ? 0 : 100;
  const dMean = deltaE(a.labMean, b.labMean);
  const dStd = Math.abs(a.labStd[0] - b.labStd[0]) + Math.abs(a.labStd[1] - b.labStd[1]) + Math.abs(a.labStd[2] - b.labStd[2]);
  const dLuma = histEmd(a.lumaHist, b.lumaHist);
  const dRgb = (histEmd(a.rgbHist[0], b.rgbHist[0]) + histEmd(a.rgbHist[1], b.rgbHist[1]) + histEmd(a.rgbHist[2], b.rgbHist[2])) / 3;
  const d = dMean + 0.5 * dStd + 100 * dLuma + 50 * dRgb + 50 * Math.abs(a.satMean - b.satMean);
  return Number.isFinite(d) ? d : 100;
}

/** A plain colour name for a Lab colour (hue angle in Lab, chroma and lightness), for descriptions only. */
function colourName(lab: V3): string {
  const [L, a, b] = lab;
  const C = Math.hypot(a, b);
  if (C < 10) return L > 80 ? "white" : L < 20 ? "black" : "grey";
  const h = ((Math.atan2(b, a) * 180) / Math.PI + 360) % 360;
  if (h < 20 || h >= 345) return "magenta-red";
  if (h < 45) return "red";
  if (h < 70) return L > 60 ? "orange" : "brown";
  if (h < 100) return "yellow";
  if (h < 160) return "green";
  if (h < 220) return "teal";
  if (h < 290) return "blue";
  return "purple";
}

function joinWords(w: string[]): string {
  if (w.length <= 1) return w.join("");
  return `${w.slice(0, -1).join(", ")} and ${w[w.length - 1]}`;
}

/**
 * Two to five short plain-English lines on how the reference differs from the source, e.g. "Reference is warmer
 * and less saturated, with lifted blacks." The last line is always the match distance (statsDistance). Thresholds:
 * warmth / tint ±3 in mean b / a, brightness ±4 in mean L, saturation and contrast (L spread) ±15 % (ratio), black
 * point ±0.03, white point ±0.04, main colour named when the two heaviest swatches differ by more than 15 ΔE.
 */
export function describeMatch(src: ColourStats, ref: ColourStats): string[] {
  if (src.n <= 0 || ref.n <= 0) return ["One of the shots has no visible pixels (empty or fully transparent).", "There is nothing to match yet."];
  const adj: string[] = [];
  const db = ref.labMean[2] - src.labMean[2];
  const da = ref.labMean[1] - src.labMean[1];
  const dL = ref.labMean[0] - src.labMean[0];
  if (db > 3) adj.push("warmer");
  else if (db < -3) adj.push("cooler");
  if (da > 3) adj.push("more magenta");
  else if (da < -3) adj.push("greener");
  if (dL > 4) adj.push("brighter");
  else if (dL < -4) adj.push("darker");
  const sr = (ref.satMean + 0.01) / (src.satMean + 0.01);
  if (sr > 1.15) adj.push("more saturated");
  else if (sr < 1 / 1.15) adj.push("less saturated");
  const cr = (ref.labStd[0] + 0.5) / (src.labStd[0] + 0.5);
  if (cr > 1.15) adj.push("more contrasty");
  else if (cr < 1 / 1.15) adj.push("flatter");
  const ends: string[] = [];
  const dBlack = ref.black - src.black;
  const dWhite = ref.white - src.white;
  if (dBlack > 0.03) ends.push("lifted blacks");
  else if (dBlack < -0.03) ends.push("deeper blacks");
  if (dWhite < -0.04) ends.push("duller highlights");
  else if (dWhite > 0.04) ends.push("brighter highlights");
  const lines: string[] = [];
  const first = adj.slice(0, 3);
  const rest = adj.slice(3);
  if (first.length) lines.push(`Reference is ${joinWords(first)}${ends.length ? `, with ${joinWords(ends)}` : ""}.`);
  else if (ends.length) lines.push(`Reference has ${joinWords(ends)}.`);
  if (rest.length) lines.push(`It is also ${joinWords(rest)}.`);
  const ps = src.palette[0];
  const pr = ref.palette[0];
  if (ps && pr) {
    const ls = rgbToLab(ps.rgb[0], ps.rgb[1], ps.rgb[2]);
    const lr = rgbToLab(pr.rgb[0], pr.rgb[1], pr.rgb[2]);
    if (deltaE(ls, lr) > 15) lines.push(`Main colour: ${colourName(lr)} in the reference, ${colourName(ls)} in this shot.`);
  }
  const d = statsDistance(src, ref);
  if (!lines.length) lines.push("The two shots already look alike.");
  lines.push(`Match distance ${d.toFixed(1)} (0 = same look, under 3 reads as matched).`);
  return lines.slice(0, 5);
}
