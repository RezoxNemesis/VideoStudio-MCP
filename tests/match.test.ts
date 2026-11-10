import { describe, expect, it } from "vitest";
import {
  analyse,
  autoBalance,
  describeMatch,
  emptyStats,
  labToRgb,
  labToRgbClamped,
  linearToSrgb,
  luma709,
  matchCurves,
  matchLut,
  matchPrimaries,
  mergeStats,
  rgbToHsv,
  rgbToLab,
  srgbToLinear,
  statsDistance,
} from "../src/color/match";
import type { ColorNode, ColourStats, CurvePts, Lut3D, Pixels } from "../src/color/types";

type V3 = [number, number, number];

/** Deterministic pseudo-random numbers (mulberry32), so every run sees the same pictures. */
function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const c01 = (v: number) => (v < 0 ? 0 : v > 1 ? 1 : v);

/** A picture from a function giving display RGB 0..1 (and optional alpha 0..1) per pixel. */
function picture(w: number, h: number, f: (x: number, y: number) => V3 | [number, number, number, number]): Pixels {
  const data = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const c = f(x, y);
    const p = (y * w + x) * 4;
    data[p] = Math.round(c01(c[0]) * 255);
    data[p + 1] = Math.round(c01(c[1]) * 255);
    data[p + 2] = Math.round(c01(c[2]) * 255);
    data[p + 3] = c.length > 3 ? Math.round(c01(c[3]!) * 255) : 255;
  }
  return { data, width: w, height: h };
}

/** Apply a per-colour function to every pixel of a picture (alpha kept). */
function mapPixels(img: Pixels, f: (c: V3) => V3): Pixels {
  const data = new Uint8ClampedArray(img.data.length);
  for (let p = 0; p < img.data.length; p += 4) {
    const c = f([img.data[p]! / 255, img.data[p + 1]! / 255, img.data[p + 2]! / 255]);
    data[p] = Math.round(c01(c[0]) * 255);
    data[p + 1] = Math.round(c01(c[1]) * 255);
    data[p + 2] = Math.round(c01(c[2]) * 255);
    data[p + 3] = img.data[p + 3]!;
  }
  return { data, width: img.width, height: img.height };
}

/**
 * A "real-ish" source shot: blue sky gradient, green/brown ground, a warm skin-tone blob, a dark doorway and
 * seeded noise and random patches — varied tones and colours, fully deterministic.
 */
function sourceShot(w = 192, h = 108, seed = 7): Pixels {
  const r = rng(seed);
  const patches: { x: number; y: number; s: number; c: V3 }[] = [];
  for (let i = 0; i < 12; i++) patches.push({ x: r() * w, y: r() * h, s: 4 + r() * 10, c: [r(), r(), r()] });
  return picture(w, h, (x, y) => {
    const u = x / w;
    const v = y / h;
    let c: V3;
    if (v < 0.45) c = [0.35 + 0.3 * v, 0.55 + 0.3 * v, 0.85 - 0.1 * v];
    else c = [0.3 + 0.25 * u * (1 - v), 0.42 - 0.2 * (v - 0.45), 0.18 + 0.05 * u];
    const dx = (x - w * 0.62) / (w * 0.09);
    const dy = (y - h * 0.55) / (h * 0.2);
    if (dx * dx + dy * dy < 1) c = [0.86, 0.62, 0.5];
    if (u > 0.12 && u < 0.24 && v > 0.5) c = [0.05, 0.04, 0.05];
    for (const p of patches) if (Math.abs(x - p.x) < p.s && Math.abs(y - p.y) < p.s) c = p.c;
    const n = (r() - 0.5) * 0.06;
    return [c[0] + n, c[1] + n, c[2] + n];
  });
}

/** The reference look used for matching tests: warmer, lower contrast, lifted blacks. */
function warmFlatLook(c: V3): V3 {
  const f = (v: number) => 0.12 + 0.72 * v;
  return [f(c[0]) * 1.07, f(c[1]) * 1.0, f(c[2]) * 0.84];
}

/** Trilinear LUT lookup (red fastest, the .cube order), written here so the test does not depend on lut.ts. */
function sampleLut(lut: Lut3D, c: V3): V3 {
  const n = lut.size;
  const pos = c.map((v) => c01(v) * (n - 1)) as V3;
  const i0 = pos.map((v) => Math.min(n - 2, Math.floor(v))) as V3;
  const f = pos.map((v, k) => v - i0[k]!) as V3;
  const out: V3 = [0, 0, 0];
  for (let dz = 0; dz < 2; dz++) for (let dy = 0; dy < 2; dy++) for (let dx = 0; dx < 2; dx++) {
    const w = (dx ? f[0] : 1 - f[0]) * (dy ? f[1] : 1 - f[1]) * (dz ? f[2] : 1 - f[2]);
    const idx = ((i0[2] + dz) * n * n + (i0[1] + dy) * n + (i0[0] + dx)) * 3;
    for (let k = 0; k < 3; k++) out[k] = out[k]! + w * lut.data[idx + k]!;
  }
  return out;
}

function lutAt(lut: Lut3D, r: number, g: number, b: number): V3 {
  const n = lut.size;
  const i = ((b * n + g) * n + r) * 3;
  return [lut.data[i]!, lut.data[i + 1]!, lut.data[i + 2]!];
}

/** Piecewise-linear curve evaluation (the engine draws monotone cubic; linear is close enough to judge a match). */
function evalCurve(pts: CurvePts, x: number): number {
  if (x <= pts[0]![0]) return pts[0]![1];
  for (let i = 0; i < pts.length - 1; i++) {
    const a = pts[i]!;
    const b = pts[i + 1]!;
    if (x <= b[0]) return a[1] + ((b[1] - a[1]) * (x - a[0])) / (b[0] - a[0]);
  }
  return pts[pts.length - 1]![1];
}

type Prim = Pick<ColorNode, "lift" | "gamma" | "gain" | "temp" | "tint"> & { sat?: number };

/** The engine's primaries, exactly as specified: temp/tint, then gain + lift, then gamma, then saturation. */
function engine(p: Prim, c: V3): V3 {
  const m: V3 = [1 + p.temp * 0.12, 1 + p.tint * 0.08, 1 - p.temp * 0.12];
  const g: V3 = [0, 0, 0];
  for (let i = 0; i < 3; i++) {
    let v = c[i]! * m[i]!;
    v = v * (1 + p.gain[i]! + p.gain[3]) + (p.lift[i]! + p.lift[3]) * (1 - v);
    g[i] = Math.pow(Math.max(v, 0), 1 / Math.max(0.05, 1 + p.gamma[i]! + p.gamma[3]));
  }
  const s = p.sat ?? 1;
  const l = luma709(g[0], g[1], g[2]);
  return [l + (g[0] - l) * s, l + (g[1] - l) * s, l + (g[2] - l) * s];
}

function expectFiniteStats(s: ColourStats): void {
  const nums = [s.n, ...s.labMean, ...s.labStd, ...s.lumaHist, ...s.rgbHist.flat(), s.satMean, s.black, s.white, ...s.palette.flatMap((p) => [...p.rgb, p.weight])];
  for (const v of nums) expect(Number.isFinite(v)).toBe(true);
}

const sum = (a: number[]) => a.reduce((s, v) => s + v, 0);

describe("colour conversions", () => {
  it("round-trips sRGB ↔ Lab for a grid of colours (error < 1e-4)", () => {
    let worst = 0;
    for (let r = 0; r <= 10; r++) for (let g = 0; g <= 10; g++) for (let b = 0; b <= 10; b++) {
      const c: V3 = [r / 10, g / 10, b / 10];
      const back = labToRgb(...rgbToLab(...c));
      for (let k = 0; k < 3; k++) worst = Math.max(worst, Math.abs(back[k]! - c[k]!));
    }
    expect(worst).toBeLessThan(1e-4);
  });

  it("gives the published Lab values for white, mid grey and pure red", () => {
    const w = rgbToLab(1, 1, 1);
    expect(w[0]).toBeCloseTo(100, 6);
    expect(Math.abs(w[1])).toBeLessThan(1e-6);
    expect(Math.abs(w[2])).toBeLessThan(1e-6);
    const g = rgbToLab(0.5, 0.5, 0.5);
    expect(Math.abs(g[0] - 53.39)).toBeLessThan(0.01);
    expect(Math.abs(g[1])).toBeLessThan(1e-6);
    expect(Math.abs(g[2])).toBeLessThan(1e-6);
    const red = rgbToLab(1, 0, 0);
    expect(Math.abs(red[0] - 53.24)).toBeLessThan(0.01);
    expect(Math.abs(red[1] - 80.09)).toBeLessThan(0.01);
    expect(Math.abs(red[2] - 67.2)).toBeLessThan(0.01);
    const black = rgbToLab(0, 0, 0);
    expect(black).toEqual([0, 0, 0]);
  });

  it("implements the IEC 61966-2-1 transfer functions, inverse of each other and continuous at the joins", () => {
    for (let i = 0; i <= 1000; i++) expect(Math.abs(linearToSrgb(srgbToLinear(i / 1000)) - i / 1000)).toBeLessThan(1e-12);
    expect(srgbToLinear(0.04045)).toBeCloseTo(0.04045 / 12.92, 12);
    expect(Math.abs(srgbToLinear(0.04045 + 1e-9) - srgbToLinear(0.04045))).toBeLessThan(1e-6);
    expect(srgbToLinear(1)).toBeCloseTo(1, 12);
    expect(srgbToLinear(-0.5)).toBe(-srgbToLinear(0.5));
  });

  it("labToRgb is unclamped for out-of-gamut colours, labToRgbClamped is not", () => {
    const c = labToRgb(50, 120, -120);
    expect(Math.min(...c) < 0 || Math.max(...c) > 1).toBe(true);
    for (const v of labToRgbClamped(50, 120, -120)) expect(v >= 0 && v <= 1).toBe(true);
  });

  it("computes HSV and BT.709 luma", () => {
    expect(rgbToHsv(1, 0, 0)).toEqual([0, 1, 1]);
    const [h, s, v] = rgbToHsv(0, 0.5, 0.5);
    expect(h).toBeCloseTo(0.5, 12);
    expect(s).toBe(1);
    expect(v).toBe(0.5);
    expect(rgbToHsv(0, 0, 0)).toEqual([0, 0, 0]);
    expect(rgbToHsv(0.3, 0.3, 0.3)[1]).toBe(0);
    expect(luma709(1, 1, 1)).toBeCloseTo(1, 12);
    expect(luma709(0, 1, 0)).toBe(0.7152);
  });
});

describe("analyse", () => {
  it("measures a flat colour exactly", () => {
    const s = analyse(picture(50, 40, () => [0.8, 0.4, 0.2]));
    const r = 204 / 255;
    const g = 102 / 255;
    const b = 51 / 255;
    const lab = rgbToLab(r, g, b);
    expect(s.n).toBe(2000);
    for (let k = 0; k < 3; k++) {
      expect(s.labMean[k]).toBeCloseTo(lab[k]!, 8);
      expect(s.labStd[k]).toBeLessThan(1e-8);
    }
    expect(Math.max(...s.lumaHist)).toBeCloseTo(1, 12);
    expect(s.rgbHist[0][Math.floor(r * 64)]).toBeCloseTo(1, 12);
    expect(s.black).toBeCloseTo(luma709(r, g, b), 6);
    expect(s.white).toBeCloseTo(luma709(r, g, b), 6);
    expect(s.satMean).toBeCloseTo((204 - 51) / 204, 12);
    expect(s.palette.length).toBe(1);
    expect(s.palette[0]!.weight).toBeCloseTo(1, 12);
    for (let k = 0; k < 3; k++) expect(Math.abs(s.palette[0]!.rgb[k]! - [r, g, b][k]!)).toBeLessThan(1e-4);
    expectFiniteStats(s);
  });

  it("measures a grey gradient: spread-out histogram, 1st/99th percentile black and white", () => {
    const s = analyse(picture(256, 10, (x) => [x / 255, x / 255, x / 255]));
    expect(s.n).toBe(2560);
    expect(sum(s.lumaHist)).toBeCloseTo(1, 12);
    for (const v of s.lumaHist) expect(v).toBeCloseTo(4 / 256, 12);
    expect(Math.abs(s.black - 0.01)).toBeLessThan(0.005);
    expect(Math.abs(s.white - 0.99)).toBeLessThan(0.005);
    expect(s.satMean).toBe(0);
    expect(Math.abs(s.labMean[1])).toBeLessThan(1e-6);
    expect(s.palette.length).toBeGreaterThan(2);
    expect(s.palette.length).toBeLessThanOrEqual(6);
    expect(sum(s.palette.map((p) => p.weight))).toBeCloseTo(1, 12);
    for (let i = 1; i < s.palette.length; i++) expect(s.palette[i]!.weight).toBeLessThanOrEqual(s.palette[i - 1]!.weight);
    expectFiniteStats(s);
  });

  it("finds both colours of a two-colour picture with the right weights", () => {
    const s = analyse(picture(100, 50, (x) => (x < 70 ? [0.9, 0.1, 0.1] : [0.1, 0.2, 0.9])));
    expect(s.palette.length).toBe(2);
    expect(s.palette[0]!.weight).toBeCloseTo(0.7, 6);
    expect(s.palette[1]!.weight).toBeCloseTo(0.3, 6);
    const near = (a: V3, b: V3) => Math.max(...a.map((v, k) => Math.abs(v - b[k]!)));
    expect(near(s.palette[0]!.rgb, [0.9, 0.1, 0.1])).toBeLessThan(0.01);
    expect(near(s.palette[1]!.rgb, [0.1, 0.2, 0.9])).toBeLessThan(0.01);
  });

  it("gives the same palette on every run (deterministic)", () => {
    const img = sourceShot();
    expect(analyse(img).palette).toEqual(analyse(img).palette);
  });

  it("returns empty statistics without NaN for transparent and empty pictures", () => {
    for (const img of [picture(20, 20, () => [1, 0, 0, 0]), { data: new Uint8ClampedArray(0), width: 0, height: 0 }, picture(10, 10, () => [0.5, 0.5, 0.5, 7 / 255])]) {
      const s = analyse(img);
      expect(s).toEqual(emptyStats());
      expectFiniteStats(s);
    }
  });

  it("skips pixels with alpha < 8 and keeps those with alpha ≥ 8", () => {
    const s = analyse(picture(40, 10, (x) => (x < 20 ? [1, 0, 0, 0] : [0, 0, 1, 8 / 255])));
    expect(s.n).toBe(200);
    expect(s.palette.length).toBe(1);
    expect(s.palette[0]!.rgb[2]).toBeGreaterThan(0.99);
  });

  it("samples a regular grid of about maxSamples pixels", () => {
    const big = picture(1000, 600, (x, y) => [x / 1000, y / 600, 0.5]);
    const s = analyse(big);
    expect(Math.abs(s.n - 40000) / 40000).toBeLessThan(0.05);
    const t = analyse(big, { maxSamples: 5000 });
    expect(Math.abs(t.n - 5000) / 5000).toBeLessThan(0.05);
    expect(Math.abs(s.labMean[0] - t.labMean[0])).toBeLessThan(0.5);
  });
});

describe("mergeStats", () => {
  const a = sourceShot(80, 40, 1);
  const b = mapPixels(sourceShot(80, 25, 2), warmFlatLook);
  const concat: Pixels = { data: new Uint8ClampedArray([...a.data, ...b.data]), width: 80, height: 65 };

  it("equals analysing the concatenated pictures", () => {
    const m = mergeStats([analyse(a), analyse(b)]);
    const c = analyse(concat);
    expect(m.n).toBe(c.n);
    for (let k = 0; k < 3; k++) {
      expect(m.labMean[k]).toBeCloseTo(c.labMean[k]!, 8);
      expect(m.labStd[k]).toBeCloseTo(c.labStd[k]!, 8);
    }
    for (let i = 0; i < 64; i++) {
      expect(m.lumaHist[i]).toBeCloseTo(c.lumaHist[i]!, 12);
      for (let k = 0; k < 3; k++) expect(m.rgbHist[k]![i]).toBeCloseTo(c.rgbHist[k]![i]!, 12);
    }
    expect(sum(m.lumaHist)).toBeCloseTo(1, 12);
    expect(m.satMean).toBeCloseTo(c.satMean, 12);
    expect(Math.abs(m.black - c.black)).toBeLessThan(1 / 64);
    expect(Math.abs(m.white - c.white)).toBeLessThan(1 / 64);
    expect(sum(m.palette.map((p) => p.weight))).toBeCloseTo(1, 12);
    expect(m.palette.length).toBeLessThanOrEqual(6);
    expect(statsDistance(m, c)).toBeLessThan(1);
  });

  it("handles one frame, custom weights, empty frames and bad input", () => {
    const sa = analyse(a);
    const one = mergeStats([sa]);
    expect(one.labMean).toEqual(sa.labMean);
    expect(one.black).toBe(sa.black);
    expect(one.white).toBe(sa.white);
    const withEmpty = mergeStats([sa, emptyStats()]);
    expect(withEmpty.labMean).toEqual(sa.labMean);
    expect(withEmpty.n).toBe(sa.n);
    const onlyA = mergeStats([sa, analyse(b)], [1, 0]);
    for (let k = 0; k < 3; k++) expect(onlyA.labMean[k]).toBeCloseTo(sa.labMean[k]!, 10);
    expect(mergeStats([])).toEqual(emptyStats());
    expect(() => mergeStats([sa], [1, 2])).toThrow(RangeError);
  });
});

describe("matchLut", () => {
  const src = sourceShot();
  const ref = mapPixels(sourceShot(192, 108, 99), warmFlatLook);
  const srcStats = analyse(src);
  const refStats = analyse(ref);

  it("brings the source much closer to the reference (distance after < 30 % of before)", () => {
    const lut = matchLut(srcStats, refStats);
    expect(lut.size).toBe(33);
    expect(lut.data.length).toBe(33 * 33 * 33 * 3);
    const out = analyse(mapPixels(src, (c) => sampleLut(lut, c)));
    const before = statsDistance(srcStats, refStats);
    const after = statsDistance(out, refStats);
    expect(before).toBeGreaterThan(15);
    expect(after).toBeLessThan(0.3 * before);
    // The specific traits moved the right way: warmer, lifted blacks, lower contrast.
    expect(out.labMean[2]).toBeGreaterThan(srcStats.labMean[2] + 0.5 * (refStats.labMean[2] - srcStats.labMean[2]));
    expect(Math.abs(out.black - refStats.black)).toBeLessThan(0.03);
    expect(out.labStd[0]).toBeLessThan(srcStats.labStd[0]);
  });

  it("is the identity when source and reference are the same (max deviation < 1e-4)", () => {
    const lut = matchLut(srcStats, srcStats, { size: 17 });
    let worst = 0;
    for (let b = 0; b < 17; b++) for (let g = 0; g < 17; g++) for (let r = 0; r < 17; r++) {
      const v = lutAt(lut, r, g, b);
      worst = Math.max(worst, Math.abs(v[0] - r / 16), Math.abs(v[1] - g / 16), Math.abs(v[2] - b / 16));
    }
    expect(worst).toBeLessThan(1e-4);
  });

  it("strength 0 and empty stats give exactly the identity", () => {
    for (const lut of [matchLut(srcStats, refStats, { strength: 0, size: 9 }), matchLut(emptyStats(), refStats, { size: 9 }), matchLut(srcStats, emptyStats(), { size: 9 })]) {
      for (let b = 0; b < 9; b++) for (let g = 0; g < 9; g++) for (let r = 0; r < 9; r++) {
        expect(lutAt(lut, r, g, b)).toEqual([Math.fround(r / 8), Math.fround(g / 8), Math.fround(b / 8)]);
      }
    }
  });

  it("keeps the grey axis monotonic, and every value finite and within 0..1", () => {
    const pairs: [ColourStats, ColourStats][] = [
      [srcStats, refStats],
      [refStats, srcStats],
      [analyse(picture(64, 64, (x, y) => [0.45 + 0.1 * (x / 64), 0.45 + 0.1 * (y / 64), 0.5])), analyse(picture(64, 64, (x) => (x < 32 ? [0, 0, 0.1] : [1, 0.9, 0])))],
    ];
    for (const [s, r] of pairs) {
      const lut = matchLut(s, r);
      for (const v of lut.data) expect(Number.isFinite(v) && v >= 0 && v <= 1).toBe(true);
      let prev: V3 = [-1, -1, -1];
      let prevY = -1;
      for (let i = 0; i < lut.size; i++) {
        const v = lutAt(lut, i, i, i);
        for (let k = 0; k < 3; k++) expect(v[k]).toBeGreaterThanOrEqual(prev[k]! - 1e-6);
        const y = luma709(...v);
        expect(y).toBeGreaterThan(prevY);
        // Slope limit: one lattice step (1/32) never jumps by more than the 2.5× limit allows (with margin for Lab).
        if (i > 0) expect(y - prevY).toBeLessThan((2.5 / 32) * 1.25);
        prev = v;
        prevY = y;
      }
    }
  });

  it("does not explode the saturation from a noisy, very colourful reference (chroma scale ≤ 2)", () => {
    const r = rng(3);
    const dull = analyse(picture(100, 60, (x, y) => [0.4 + 0.2 * (x / 100), 0.42, 0.4 + 0.2 * (y / 60)]));
    const noisy = analyse(picture(100, 60, () => [r(), r(), r()]));
    const lut = matchLut(dull, noisy);
    const out = analyse(mapPixels(picture(100, 60, (x, y) => [0.4 + 0.2 * (x / 100), 0.42, 0.4 + 0.2 * (y / 60)]), (c) => sampleLut(lut, c)));
    expect(out.labStd[1]).toBeLessThanOrEqual(2 * Math.max(dull.labStd[1], 1) + 1);
    expect(out.labStd[2]).toBeLessThanOrEqual(2 * Math.max(dull.labStd[2], 1) + 1);
  });

  it("weights luma and chroma separately and respects size", () => {
    const lumaOnly = matchLut(srcStats, refStats, { chroma: 0, size: 5 });
    const g = lutAt(lumaOnly, 2, 2, 2);
    const lab = rgbToLab(...g);
    expect(Math.abs(lab[1])).toBeLessThan(0.05);
    expect(Math.abs(lab[2])).toBeLessThan(0.05);
    expect(lumaOnly.size).toBe(5);
    const chromaOnly = matchLut(srcStats, refStats, { luma: 0, size: 5 });
    const lab2 = rgbToLab(...lutAt(chromaOnly, 2, 2, 2));
    expect(Math.abs(lab2[0] - rgbToLab(0.5, 0.5, 0.5)[0])).toBeLessThan(0.05);
    expect(lab2[2]).toBeGreaterThan(2);
  });
});

describe("matchCurves", () => {
  const src = sourceShot();
  const ref = mapPixels(sourceShot(192, 108, 99), warmFlatLook);
  const srcStats = analyse(src);
  const refStats = analyse(ref);

  it("gives monotonic curves from x = 0 to x = 1 with at most `points` points, y within 0..1", () => {
    for (const points of [undefined, 4, 2, 12]) {
      const c = matchCurves(srcStats, refStats, points ? { points } : {});
      for (const key of ["r", "g", "b"] as const) {
        const pts = c[key]!;
        expect(pts.length).toBeGreaterThanOrEqual(2);
        expect(pts.length).toBeLessThanOrEqual(points ?? 7);
        expect(pts[0]![0]).toBe(0);
        expect(pts[pts.length - 1]![0]).toBe(1);
        for (let i = 0; i < pts.length; i++) {
          expect(pts[i]![1] >= 0 && pts[i]![1] <= 1).toBe(true);
          if (i > 0) {
            expect(pts[i]![0]).toBeGreaterThan(pts[i - 1]![0]);
            expect(pts[i]![1]).toBeGreaterThanOrEqual(pts[i - 1]![1]);
          }
        }
      }
    }
  });

  it("reduces the distance to the reference (after < 40 % of before) and is the identity for the same look", () => {
    const c = matchCurves(srcStats, refStats);
    const out = analyse(mapPixels(src, (v) => [evalCurve(c.r!, v[0]), evalCurve(c.g!, v[1]), evalCurve(c.b!, v[2])]));
    const before = statsDistance(srcStats, refStats);
    expect(statsDistance(out, refStats)).toBeLessThan(0.4 * before);
    // The reference lifts the blacks: the curves must start above 0.
    expect(c.r![0]![1]).toBeGreaterThan(0.05);
    const same = matchCurves(srcStats, srcStats);
    for (const key of ["r", "g", "b"] as const) for (const [x, y] of same[key]!) expect(Math.abs(y - x)).toBeLessThan(1e-6);
    const none = matchCurves(srcStats, refStats, { strength: 0 });
    expect(none.r).toEqual([[0, 0], [1, 1]]);
    expect(matchCurves(emptyStats(), refStats).g).toEqual([[0, 0], [1, 1]]);
  });
});

describe("matchPrimaries", () => {
  const src = sourceShot();
  const srcStats = analyse(src);

  it("moves the source toward the reference under the engine math", () => {
    const ref = mapPixels(sourceShot(192, 108, 99), warmFlatLook);
    const refStats = analyse(ref);
    const p = matchPrimaries(srcStats, refStats);
    expect(p.temp).toBeGreaterThan(0.2);
    expect(p.lift[3]).toBeGreaterThan(0.05);
    for (const q of [p.lift, p.gamma, p.gain]) for (const v of q) expect(v >= -1 && v <= 1).toBe(true);
    expect(p.temp >= -1 && p.temp <= 1 && p.tint >= -1 && p.tint <= 1).toBe(true);
    expect(p.sat >= 0 && p.sat <= 2).toBe(true);
    const out = analyse(mapPixels(src, (c) => engine(p, c)));
    const before = statsDistance(srcStats, refStats);
    expect(statsDistance(out, refStats)).toBeLessThan(0.4 * before);
    const half = analyse(mapPixels(src, (c) => engine(matchPrimaries(srcStats, refStats, { strength: 0.5 }), c)));
    const dHalf = statsDistance(half, refStats);
    expect(dHalf).toBeLessThan(before);
    expect(dHalf).toBeGreaterThan(statsDistance(out, refStats));
  });

  it("matches a look the engine can express (gain, lift, gamma) closely", () => {
    const look: Prim = { lift: [0, 0, -0.03, 0.05], gamma: [0, 0, 0, 0.15], gain: [0.05, 0, -0.08, -0.05], temp: 0, tint: 0, sat: 1 };
    const refStats = analyse(mapPixels(sourceShot(192, 108, 99), (c) => engine(look, c)));
    const p = matchPrimaries(analyse(sourceShot(192, 108, 99)), refStats);
    const out = analyse(mapPixels(sourceShot(192, 108, 99), (c) => engine(p, c)));
    expect(statsDistance(out, refStats)).toBeLessThan(3);
  });

  it("changes nothing when source and reference match, and keeps neutral pictures neutral", () => {
    const p = matchPrimaries(srcStats, srcStats);
    for (const q of [p.lift, p.gamma, p.gain]) for (const v of q) expect(Math.abs(v)).toBeLessThan(1e-6);
    expect(Math.abs(p.temp)).toBeLessThan(1e-6);
    expect(Math.abs(p.tint)).toBeLessThan(1e-6);
    expect(p.sat).toBeCloseTo(1, 6);
    const grey = (lo: number, hi: number) => analyse(picture(256, 8, (x) => [lo + ((hi - lo) * x) / 255, lo + ((hi - lo) * x) / 255, lo + ((hi - lo) * x) / 255]));
    const q = matchPrimaries(grey(0.1, 0.7), grey(0, 1));
    expect(Math.abs(q.temp)).toBeLessThan(1e-6);
    expect(Math.abs(q.tint)).toBeLessThan(1e-6);
    for (const v of [...q.lift.slice(0, 3), ...q.gain.slice(0, 3), ...q.gamma.slice(0, 3)]) expect(Math.abs(v)).toBeLessThan(1e-6);
    const out = engine(q, [0.4, 0.4, 0.4]);
    expect(Math.abs(out[0] - out[2])).toBeLessThan(1e-9);
    const none = matchPrimaries(emptyStats(), srcStats);
    expect(none).toEqual({ lift: [0, 0, 0, 0], gamma: [0, 0, 0, 0], gain: [0, 0, 0, 0], sat: 1, temp: 0, tint: 0 });
  });
});

describe("autoBalance", () => {
  /** A neutral scene: greys, whites and a few muted colours that average to grey. */
  const neutralScene = (w = 160, h = 90) =>
    picture(w, h, (x, y) => {
      const v = x / (w - 1);
      const t = Math.floor((y / h) * 4);
      if (t === 1) return [v * 1.05, v, v * 0.95];
      if (t === 2) return [v * 0.95, v, v * 1.05];
      return [v, v, v];
    });
  const cast = (c: V3): V3 => [c[0] * 1.08, c[1], c[2] * 0.84];
  const castMag = (s: ColourStats) => Math.hypot(s.labMean[1], s.labMean[2]);

  it("leaves a neutral, full-range picture alone", () => {
    const p = autoBalance(analyse(neutralScene()));
    expect(Math.abs(p.temp)).toBeLessThan(0.02);
    expect(Math.abs(p.tint)).toBeLessThan(0.02);
    for (const q of [p.lift, p.gain, p.gamma]) for (const v of q) expect(Math.abs(v)).toBeLessThan(0.02);
    const g = engine(p, [0.5, 0.5, 0.5]);
    expect(Math.max(...g) - Math.min(...g)).toBeLessThan(0.005);
  });

  it("removes most of a colour cast", () => {
    const img = mapPixels(neutralScene(), cast);
    const s = analyse(img);
    const p = autoBalance(s);
    expect(p.temp).toBeLessThan(-0.2);
    const out = analyse(mapPixels(img, (c) => engine(p, c)));
    expect(castMag(s)).toBeGreaterThan(5);
    expect(castMag(out)).toBeLessThan(0.35 * castMag(s));
    const half = analyse(mapPixels(img, (c) => engine(autoBalance(s, { strength: 0.5 }), c)));
    expect(castMag(half)).toBeLessThan(castMag(s));
    expect(castMag(half)).toBeGreaterThan(castMag(out));
  });

  it("does not turn a sunset grey", () => {
    const sunset = picture(160, 90, (x, y) => {
      const v = y / 90;
      return [0.98 - 0.3 * v, 0.55 - 0.35 * v + 0.05 * (x / 160), 0.2 + 0.25 * v];
    });
    const s = analyse(sunset);
    const out = analyse(mapPixels(sunset, (c) => engine(autoBalance(s), c)));
    expect(castMag(s)).toBeGreaterThan(30);
    expect(castMag(out)).toBeGreaterThan(castMag(s) - 16.5);
    expect(out.satMean).toBeGreaterThan(0.7 * s.satMean);
    expect(out.labMean[2]).toBeGreaterThan(0.6 * s.labMean[2]);
  });

  it("sets sensible black and white points without forcing a dark scene to full range", () => {
    const flat = picture(256, 8, (x) => [0.2 + (0.5 * x) / 255, 0.2 + (0.5 * x) / 255, 0.2 + (0.5 * x) / 255]);
    const s = analyse(flat);
    const out = analyse(mapPixels(flat, (c) => engine(autoBalance(s), c)));
    expect(out.black).toBeLessThan(s.black - 0.05);
    expect(out.white).toBeGreaterThan(s.white + 0.05);
    expect(out.white - out.black).toBeLessThanOrEqual(1.5 * (s.white - s.black) + 0.02);
    const med = (st: ColourStats) => {
      let c = 0;
      for (let i = 0; i < 64; i++) if ((c += st.lumaHist[i]!) >= 0.5) return i / 64;
      return 1;
    };
    expect(Math.abs(med(out) - med(s))).toBeLessThan(0.04);
    expect(autoBalance(emptyStats())).toEqual({ lift: [0, 0, 0, 0], gain: [0, 0, 0, 0], gamma: [0, 0, 0, 0], temp: 0, tint: 0 });
  });
});

describe("describeMatch and statsDistance", () => {
  const srcStats = analyse(sourceShot());
  const refStats = analyse(mapPixels(sourceShot(192, 108, 99), warmFlatLook));

  it("says what differs in 2–5 plain lines", () => {
    const lines = describeMatch(srcStats, refStats);
    expect(lines.length).toBeGreaterThanOrEqual(2);
    expect(lines.length).toBeLessThanOrEqual(5);
    expect(lines[0]).toMatch(/warmer/);
    expect(lines.join(" ")).toMatch(/lifted blacks/);
    expect(lines[lines.length - 1]).toMatch(/Match distance/);
    const same = describeMatch(srcStats, srcStats);
    expect(same[0]).toMatch(/already look alike/);
    expect(same.length).toBe(2);
    expect(describeMatch(emptyStats(), srcStats).length).toBe(2);
  });

  it("is 0 for the same look, symmetric, and grows with the difference", () => {
    expect(statsDistance(srcStats, srcStats)).toBe(0);
    expect(statsDistance(srcStats, refStats)).toBeCloseTo(statsDistance(refStats, srcStats), 10);
    const mild = analyse(mapPixels(sourceShot(), (c) => [c[0] * 1.02, c[1], c[2] * 0.98]));
    expect(statsDistance(srcStats, mild)).toBeLessThan(statsDistance(srcStats, refStats));
    expect(statsDistance(srcStats, mild)).toBeGreaterThan(0);
    expect(statsDistance(emptyStats(), emptyStats())).toBe(0);
    expect(statsDistance(emptyStats(), srcStats)).toBe(100);
  });
});

describe("speed", () => {
  const median = (f: () => void) => {
    const t: number[] = [];
    for (let i = 0; i < 5; i++) {
      const t0 = performance.now();
      f();
      t.push(performance.now() - t0);
    }
    return t.sort((a, b) => a - b)[2]!;
  };

  it("analyses a 640×360 picture in under 50 ms and builds a 33³ LUT in under 100 ms", () => {
    const big = sourceShot(640, 360, 5);
    const ref = analyse(mapPixels(big, warmFlatLook));
    const s = analyse(big);
    const ta = median(() => analyse(big));
    const tl = median(() => matchLut(s, ref));
    console.log(`analyse 640×360: ${ta.toFixed(1)} ms, matchLut 33³: ${tl.toFixed(1)} ms`);
    expect(ta).toBeLessThan(50);
    expect(tl).toBeLessThan(100);
  });
});
