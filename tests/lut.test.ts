import { describe, expect, it } from "vitest";
import {
  AWG3_TO_REC709,
  BUILTIN_LUTS,
  DGAMUT_TO_XYZ,
  SGAMUT3CINE_PRIMARIES,
  SGAMUT3CINE_TO_XYZ,
  VGAMUT_TO_REC709,
  base64ToBytes,
  builtinLut,
  bytesToBase64,
  composeLuts,
  dLogToLinear,
  filmicShoulder,
  identityLut,
  linearToDLog,
  linearToLogC3,
  linearToSLog3,
  linearToVLog,
  logC3ToLinear,
  lutToProject,
  mapLut,
  mixLut,
  parseCube,
  projectToLut,
  rec709Oetf,
  resizeLut,
  rgbToXyzMatrix,
  sLog3ToLinear,
  sampleLut,
  vLogToLinear,
  writeCube,
  type Mat3,
} from "../src/color/lut";
import type { Lut3D } from "../src/color/types";

/** Deterministic pseudo-random numbers in 0..1 (so failures can be reproduced). */
function rng(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

/** A smooth, non-trivial LUT used to test the helpers. */
const warpFn = (r: number, g: number, b: number): [number, number, number] => [
  Math.pow(r, 0.8) * 0.9 + 0.05 * g,
  0.5 * g + 0.5 * g * g + 0.02 * b,
  Math.sqrt(b) * 0.7 + 0.1 * r,
];

function at(lut: Lut3D, i: number, j: number, k: number): [number, number, number] {
  const o = (i + j * lut.size + k * lut.size * lut.size) * 3;
  return [lut.data[o]!, lut.data[o + 1]!, lut.data[o + 2]!];
}

function expectMatClose(a: Mat3, b: Mat3, tol: number): void {
  for (let i = 0; i < 9; i++) expect(Math.abs(a[i]! - b[i]!)).toBeLessThanOrEqual(tol);
}

describe(".cube parse and write", () => {
  it("round-trips a LUT through writeCube and parseCube to within 6 decimals", () => {
    const lut = mapLut(9, warpFn, "Warp test");
    const text = writeCube(lut);
    expect(text).toContain('TITLE "Warp test"');
    expect(text).toContain("LUT_3D_SIZE 9");
    const back = parseCube(text);
    expect(back.size).toBe(9);
    expect(back.title).toBe("Warp test");
    expect(back.data.length).toBe(lut.data.length);
    for (let i = 0; i < lut.data.length; i++) expect(Math.abs(back.data[i]! - lut.data[i]!)).toBeLessThanOrEqual(1e-6);
  });

  it("writes 6 decimals, never -0.000000, and a title argument overrides the LUT title and is sanitised", () => {
    const lut = mapLut(2, () => [-1e-9, 0.5, 1]);
    const text = writeCube(lut, 'My "best"\nLUT');
    expect(text).toContain("TITLE \"My 'best' LUT\"");
    expect(text).not.toContain("-0.000000");
    expect(text).toContain("0.000000 0.500000 1.000000");
    expect(parseCube(text).title).toBe("My 'best' LUT");
  });

  it("parses a hand-written cube with comments, CRLF, tabs, blank lines, scientific notation and DOMAIN lines", () => {
    const rows = [
      "0 0 0", "1 0 0", "0 1 0", "1 1 0",
      "0 0 1", "1 0 1", "0 1 1", "1.0e0\t1E+0\t10e-1",
    ];
    const text = [
      "# A comment line",
      'TITLE "Hand # made"',
      "",
      "LUT_3D_SIZE 2   # trailing comment",
      "DOMAIN_MIN 0.0 0.0 0.0",
      "DOMAIN_MAX\t1.0 1.0 1.0",
      "LUT_IN_VIDEO_RANGE",
      "",
      ...rows,
      "",
    ].join("\r\n");
    const lut = parseCube(text);
    expect(lut.size).toBe(2);
    expect(lut.title).toBe("Hand # made");
    expect(Array.from(lut.data)).toEqual([0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0, 0, 0, 1, 1, 0, 1, 0, 1, 1, 1, 1, 1]);
    // An identity on 2 points is exact everywhere under tetrahedral interpolation.
    const s = sampleLut(lut, 0.25, 0.5, 0.75);
    expect(s[0]).toBeCloseTo(0.25, 6);
    expect(s[1]).toBeCloseTo(0.5, 6);
    expect(s[2]).toBeCloseTo(0.75, 6);
  });

  it("accepts old Mac CR line endings and a byte-order mark", () => {
    const text = "﻿LUT_3D_SIZE 2\r" + Array.from({ length: 8 }, () => "0.5 0.5 0.5").join("\r");
    const lut = parseCube(text);
    expect(lut.size).toBe(2);
    expect(Array.from(lut.data).every((v) => v === 0.5)).toBe(true);
  });

  it("normalises a non-0..1 DOMAIN so the result gives the file's output for the same input value", () => {
    // Identity over the domain 0..2: the table holds the input value itself.
    const n = 5;
    const rows: string[] = [];
    for (let k = 0; k < n; k++) for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) rows.push(`${(i / (n - 1)) * 2} ${(j / (n - 1)) * 2} ${(k / (n - 1)) * 2}`);
    const lut = parseCube(["LUT_3D_SIZE 5", "DOMAIN_MIN 0 0 0", "DOMAIN_MAX 2 2 2", ...rows].join("\n"));
    expect(lut.size).toBe(33);
    // The file maps input v (in 0..2) to v, so the 0..1 result must be the identity on 0..1.
    const s = sampleLut(lut, 0.3, 0.6, 0.9);
    expect(s[0]).toBeCloseTo(0.3, 5);
    expect(s[1]).toBeCloseTo(0.6, 5);
    expect(s[2]).toBeCloseTo(0.9, 5);
  });

  it("treats LUT_3D_INPUT_RANGE like a domain and clamps inputs outside it", () => {
    const rows = ["0 0 0", "1 0 0", "0 1 0", "1 1 0", "0 0 1", "1 0 1", "0 1 1", "1 1 1"];
    const lut = parseCube(["LUT_3D_SIZE 2", "LUT_3D_INPUT_RANGE 0.5 1.0", ...rows].join("\n"));
    // A non-0..1 domain is resampled on a grid of at least 33 points.
    expect(lut.size).toBe(33);
    // Inputs at or below 0.5 clamp to the bottom of the table → 0.
    expect(at(lut, 0, 0, 0)).toEqual([0, 0, 0]);
    expect(at(lut, 8, 8, 8)).toEqual([0, 0, 0]);
    expect(at(lut, 16, 16, 16)).toEqual([0, 0, 0]);
    expect(at(lut, 32, 32, 32)).toEqual([1, 1, 1]);
    // 0.75 is half way through the domain → 0.5.
    const s = sampleLut(lut, 0.75, 0.75, 0.75);
    expect(s[0]).toBeCloseTo(0.5, 6);
  });

  it("converts a 1D LUT to a 33-point 3D LUT by applying the curve to each channel", () => {
    const n1 = 5;
    const rows: string[] = [];
    // Red squared, green inverted, blue constant 0.25.
    for (let i = 0; i < n1; i++) {
      const x = i / (n1 - 1);
      rows.push(`${x * x} ${1 - x} 0.25`);
    }
    const lut = parseCube(["TITLE \"Curve\"", `LUT_1D_SIZE ${n1}`, ...rows].join("\n"));
    expect(lut.size).toBe(33);
    expect(lut.title).toBe("Curve");
    // Grid point red = 16/32 = 0.5 sits on 1D row 2 exactly: 0.25. Green 8/32 = 0.25 → 0.75.
    const p = at(lut, 16, 8, 32);
    expect(p[0]).toBeCloseTo(0.25, 6);
    expect(p[1]).toBeCloseTo(0.75, 6);
    expect(p[2]).toBeCloseTo(0.25, 6);
    // Red 0.375 lies between rows 1 and 2 (0.0625 and 0.25): linear interpolation gives 0.15625.
    expect(at(lut, 12, 0, 0)[0]).toBeCloseTo(0.15625, 6);
  });

  it("bakes a 1D shaper followed by a 3D cube into one LUT", () => {
    const shaper = ["0 0 0", "0.5 0.5 0.5"]; // halves every channel
    const cube = ["0 0 0", "1 0 0", "0 1 0", "1 1 0", "0 0 1", "1 0 1", "0 1 1", "1 1 1"];
    const lut = parseCube(["LUT_1D_SIZE 2", "LUT_3D_SIZE 2", ...shaper, ...cube].join("\n"));
    expect(lut.size).toBe(33);
    expect(at(lut, 32, 32, 32)[0]).toBeCloseTo(0.5, 6);
    expect(at(lut, 16, 0, 0)[0]).toBeCloseTo(0.25, 6);
  });

  it("throws clear errors for bad files", () => {
    const eight = Array.from({ length: 8 }, () => "0 0 0");
    expect(() => parseCube(eight.join("\n"))).toThrow(/no LUT_3D_SIZE or LUT_1D_SIZE/);
    expect(() => parseCube(["LUT_3D_SIZE 2", ...eight.slice(1)].join("\n"))).toThrow(/should have 8 data rows .* but has 7/);
    expect(() => parseCube(["LUT_3D_SIZE 2", ...eight, "0 0 0"].join("\n"))).toThrow(/should have 8 data rows .* but has 9/);
    expect(() => parseCube(["LUT_3D_SIZE 2", ...eight.slice(1), "0 abc 0"].join("\n"))).toThrow(/Line 9 .*"abc", which is not a number/);
    expect(() => parseCube(["LUT_3D_SIZE 2", ...eight.slice(1), "nan nan nan"].join("\n"))).toThrow(/not a number/);
    expect(() => parseCube(["LUT_3D_SIZE 2", ...eight.slice(1), "0x10 0 0"].join("\n"))).toThrow(/not a number/);
    expect(() => parseCube(["LUT_3D_SIZE 2", ...eight.slice(1), "0 0"].join("\n"))).toThrow(/three numbers/);
    expect(() => parseCube(["LUT_3D_SIZE 2", ...eight.slice(1), "0 0 0 0"].join("\n"))).toThrow(/three numbers/);
    expect(() => parseCube("LUT_3D_SIZE 1\n0 0 0")).toThrow(/from 2 to 129, got 1/);
    expect(() => parseCube("LUT_3D_SIZE 130\n0 0 0")).toThrow(/from 2 to 129, got 130/);
    expect(() => parseCube("LUT_3D_SIZE 2.5\n0 0 0")).toThrow(/whole number/);
    expect(() => parseCube("LUT_3D_SIZE\n0 0 0")).toThrow(/needs a size/);
    expect(() => parseCube("LUT_1D_SIZE 1\n0 0 0")).toThrow(/from 2 to 65536, got 1/);
    expect(() => parseCube(["LUT_3D_SIZE 2", "DOMAIN_MIN 1 1 1", "DOMAIN_MAX 0 0 0", ...eight].join("\n"))).toThrow(/greater than its minimum/);
    expect(() => parseCube(["LUT_3D_SIZE 2", "DOMAIN_MIN 0 0", ...eight].join("\n"))).toThrow(/three numbers/);
    expect(() => parseCube("")).toThrow(/no LUT_3D_SIZE/);
  });
});

describe("core helpers", () => {
  it("identityLut has its grid inputs as outputs", () => {
    const id = identityLut(5);
    expect(id.size).toBe(5);
    expect(id.data.length).toBe(5 * 5 * 5 * 3);
    expect(at(id, 1, 2, 3)).toEqual([0.25, 0.5, 0.75]);
    expect(identityLut().size).toBe(33);
  });

  it("tetrahedral sampling of the identity is exact at random points", () => {
    const rand = rng(1);
    for (const size of [2, 17, 33]) {
      const id = identityLut(size);
      for (let n = 0; n < 2000; n++) {
        const r = rand();
        const g = rand();
        const b = rand();
        const s = sampleLut(id, r, g, b);
        expect(Math.abs(s[0] - r)).toBeLessThan(1e-6);
        expect(Math.abs(s[1] - g)).toBeLessThan(1e-6);
        expect(Math.abs(s[2] - b)).toBeLessThan(1e-6);
      }
    }
  });

  it("sampleLut clamps inputs to 0..1, maps NaN to 0, and hits grid points exactly", () => {
    const lut = mapLut(9, warpFn);
    expect(sampleLut(lut, -5, 0.5, 9)).toEqual(sampleLut(lut, 0, 0.5, 1));
    expect(sampleLut(lut, Number.NaN, 0, 0)).toEqual(sampleLut(lut, 0, 0, 0));
    const s = sampleLut(lut, 3 / 8, 5 / 8, 1);
    const g = at(lut, 3, 5, 8);
    for (let c = 0; c < 3; c++) expect(s[c]).toBeCloseTo(g[c]!, 6);
  });

  it("sampleLut is exact for any LUT that is linear in the input", () => {
    const lin = mapLut(7, (r, g, b) => [0.2 * r + 0.3 * g + 0.1 * b + 0.1, 0.9 * g - 0.2 * b + 0.2, 0.5 * r + 0.4 * b]);
    const rand = rng(2);
    for (let n = 0; n < 500; n++) {
      const r = rand();
      const g = rand();
      const b = rand();
      const s = sampleLut(lin, r, g, b);
      expect(s[0]).toBeCloseTo(0.2 * r + 0.3 * g + 0.1 * b + 0.1, 5);
      expect(s[1]).toBeCloseTo(0.9 * g - 0.2 * b + 0.2, 5);
      expect(s[2]).toBeCloseTo(0.5 * r + 0.4 * b, 5);
    }
  });

  it("composeLuts matches applying both LUTs one after the other", () => {
    const a = mapLut(17, warpFn, "A");
    const b = mapLut(17, (r, g, bb) => [1 - r * 0.8, Math.pow(g, 1.5), 0.3 + 0.6 * bb], "B");
    const ab = composeLuts(a, b);
    expect(ab.size).toBe(17);
    expect(ab.title).toBe("A + B");
    // Exact at the grid points (first LUT is read exactly there).
    for (const [i, j, k] of [[0, 0, 0], [3, 9, 16], [16, 16, 16], [7, 2, 11]] as const) {
      const p = at(a, i, j, k);
      const want = sampleLut(b, p[0], p[1], p[2]);
      const got = at(ab, i, j, k);
      for (let c = 0; c < 3; c++) expect(got[c]).toBeCloseTo(want[c]!, 6);
    }
    // Between grid points, for smooth functions, the composed LUT tracks the true composition; the gap is
    // interpolation error, which falls as the grid gets finer.
    const fA = (r: number, g: number, bb: number): [number, number, number] => [0.1 + 0.8 * r * r, 0.9 * g - 0.1 * r + 0.1, 0.2 + 0.6 * Math.sin(bb * 1.5)];
    const fB = (r: number, g: number, bb: number): [number, number, number] => [1 - 0.8 * r, Math.pow(g, 1.5), 0.3 + 0.6 * bb * bb];
    const truth = (r: number, g: number, bb: number) => {
      const p = fA(r, g, bb);
      return fB(p[0], p[1], p[2]);
    };
    const worst = (size: number): number => {
      const c = composeLuts(mapLut(size, fA), mapLut(size, fB));
      const rand = rng(3);
      let e = 0;
      for (let n = 0; n < 500; n++) {
        const r = rand();
        const g = rand();
        const bl = rand();
        const got = sampleLut(c, r, g, bl);
        const want = truth(r, g, bl);
        for (let k = 0; k < 3; k++) e = Math.max(e, Math.abs(got[k]! - want[k]!));
      }
      return e;
    };
    const e17 = worst(17);
    const e33 = worst(33);
    expect(e17).toBeLessThan(0.005);
    expect(e33).toBeLessThan(0.002);
    expect(e33).toBeLessThan(e17);
    // Composing an in-range LUT with the identity changes nothing.
    const inRange = mapLut(17, fA);
    const ai = composeLuts(inRange, identityLut(17));
    for (let i = 0; i < inRange.data.length; i++) expect(Math.abs(ai.data[i]! - inRange.data[i]!)).toBeLessThan(1e-6);
    // Values the first LUT pushes outside 0..1 are clamped on the way into the second, as when applied in turn.
    const over = composeLuts(mapLut(2, () => [1.5, -0.5, 0.5]), identityLut(2));
    expect(at(over, 1, 1, 1)).toEqual([1, 0, 0.5]);
  });

  it("resizeLut resamples and keeps the title; resizing a linear LUT is exact", () => {
    const lin = mapLut(5, (r, g, b) => [0.5 * r + 0.25, g, 1 - b], "Lin");
    const big = resizeLut(lin, 33);
    expect(big.size).toBe(33);
    expect(big.title).toBe("Lin");
    const p = at(big, 10, 20, 30);
    expect(p[0]).toBeCloseTo(0.5 * (10 / 32) + 0.25, 6);
    expect(p[1]).toBeCloseTo(20 / 32, 6);
    expect(p[2]).toBeCloseTo(1 - 30 / 32, 6);
    const same = resizeLut(lin, 5);
    expect(same.data).not.toBe(lin.data);
    expect(Array.from(same.data)).toEqual(Array.from(lin.data));
  });

  it("mixLut blends with the identity and clamps the amount", () => {
    const lut = mapLut(5, () => [1, 0, 0.5]);
    expect(Array.from(mixLut(lut, 0).data)).toEqual(Array.from(identityLut(5).data));
    expect(Array.from(mixLut(lut, 1).data)).toEqual(Array.from(lut.data));
    expect(Array.from(mixLut(lut, 7).data)).toEqual(Array.from(lut.data));
    const half = mixLut(lut, 0.5);
    const p = at(half, 0, 4, 2);
    expect(p[0]).toBeCloseTo(0.5, 6);
    expect(p[1]).toBeCloseTo(0.5, 6);
    expect(p[2]).toBeCloseTo(0.5, 6);
  });

  it("rejects bad sizes and malformed LUT data", () => {
    expect(() => identityLut(1)).toThrow(/from 2 to 129/);
    expect(() => mapLut(200, () => [0, 0, 0])).toThrow(/from 2 to 129/);
    expect(() => resizeLut({ size: 3, data: new Float32Array(10) }, 5)).toThrow(/needs 81/);
  });
});

describe("project storage", () => {
  it("base64 encoder and decoder match the standard on known vectors", () => {
    const enc = (s: string) => bytesToBase64(new TextEncoder().encode(s));
    expect(enc("")).toBe("");
    expect(enc("f")).toBe("Zg==");
    expect(enc("fo")).toBe("Zm8=");
    expect(enc("foo")).toBe("Zm9v");
    expect(enc("foobar")).toBe("Zm9vYmFy");
    expect(new TextDecoder().decode(base64ToBytes("Zm9vYmE="))).toBe("fooba");
    expect(new TextDecoder().decode(base64ToBytes("Zm9v\nYmFy"))).toBe("foobar");
    const all = new Uint8Array(256 * 3 + 1).map((_, i) => (i * 7) & 255);
    expect(Array.from(base64ToBytes(bytesToBase64(all)))).toEqual(Array.from(all));
    expect(() => base64ToBytes("ab$d")).toThrow(/invalid character/);
    expect(() => base64ToBytes("abcde")).toThrow(/length/);
  });

  it("round-trips a LUT through lutToProject / projectToLut within 1/65535 and clamps to 0..1", () => {
    const lut = mapLut(9, warpFn);
    const pl = lutToProject(lut, "lut1", "Warp");
    expect(pl).toMatchObject({ id: "lut1", name: "Warp", source: "data", size: 9 });
    const back = projectToLut(pl);
    expect(back.size).toBe(9);
    expect(back.title).toBe("Warp");
    for (let i = 0; i < lut.data.length; i++) {
      const want = Math.min(1, Math.max(0, lut.data[i]!));
      expect(Math.abs(back.data[i]! - want)).toBeLessThanOrEqual(1 / 65535);
    }
    const wild = projectToLut(lutToProject(mapLut(2, () => [-0.5, 2, 0.5]), "w", "Wild"));
    expect(at(wild, 1, 1, 1)[0]).toBe(0);
    expect(at(wild, 1, 1, 1)[1]).toBe(1);
  });

  it("stores 16-bit little-endian values", () => {
    const pl = lutToProject(mapLut(2, () => [1, 0, 1 / 65535]), "x", "x");
    const bytes = base64ToBytes(pl.data!);
    expect(bytes.length).toBe(8 * 3 * 2);
    expect(Array.from(bytes.slice(0, 6))).toEqual([0xff, 0xff, 0, 0, 1, 0]);
  });

  it("resolves builtin: sources and reports bad project LUTs", () => {
    const viaProject = projectToLut({ id: "p", name: "Print", source: "builtin:film-print", size: 17 });
    expect(viaProject.size).toBe(17);
    expect(Array.from(viaProject.data)).toEqual(Array.from(builtinLut("film-print", 17)!.data));
    expect(viaProject.title).toBe("Print");
    expect(projectToLut({ id: "p", name: "Print", source: "builtin:film-print" }).size).toBe(33);
    expect(() => projectToLut({ id: "p", name: "X", source: "builtin:nope" })).toThrow(/does not have/);
    expect(() => projectToLut({ id: "p", name: "X", source: "weird" })).toThrow(/unknown source/);
    expect(() => projectToLut({ id: "p", name: "X", source: "data", data: "" })).toThrow(/no size/);
    expect(() => projectToLut({ id: "p", name: "X", source: "data", size: 2 })).toThrow(/no data/);
    expect(() => projectToLut({ id: "p", name: "X", source: "data", size: 2, data: "AAAA" })).toThrow(/needs 48/);
  });
});

describe("built-in LUT library", () => {
  it("has unique ids, and builtinLut returns null for unknown ids and an independent copy each time", () => {
    const ids = BUILTIN_LUTS.map((b) => b.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(builtinLut("does-not-exist")).toBeNull();
    const a = builtinLut("bw-neutral")!;
    a.data[0] = 0.77;
    const b = builtinLut("bw-neutral")!;
    expect(b.data[0]).toBe(0);
    expect(b.size).toBe(33);
  });

  it("includes every requested camera, film and utility LUT", () => {
    const ids = BUILTIN_LUTS.map((b) => b.id);
    for (const id of [
      "arri-logc3-709", "sony-slog3-709", "panasonic-vlog-709", "dji-dlog-709",
      "film-print", "film-negative-cool", "film-bleach-bypass", "creative-teal-orange", "creative-cross-process",
      "film-warm-vintage", "creative-cool-night", "bw-neutral", "bw-red-filter", "bw-high-contrast",
      "legal-to-full", "full-to-legal", "soft-highlight-rolloff",
    ]) expect(ids).toContain(id);
    for (const b of BUILTIN_LUTS) {
      if (b.group === "Film" || b.group === "Creative") expect(b.about).toMatch(/VideoStudio.s own look, inspired by .*Not a copy of any vendor.s LUT\./);
    }
  });

  for (const def of BUILTIN_LUTS) {
    it(`${def.id}: finite, in 0..1, and rising along the grey axis`, () => {
      const lut = builtinLut(def.id, 33)!;
      expect(lut.size).toBe(33);
      expect(lut.data.length).toBe(33 * 33 * 33 * 3);
      for (let i = 0; i < lut.data.length; i++) {
        const v = lut.data[i]!;
        if (!(Number.isFinite(v) && v >= 0 && v <= 1)) throw new Error(`value ${v} at ${i}`);
      }
      let prev: [number, number, number] = [-1, -1, -1];
      for (let i = 0; i < 33; i++) {
        const p = at(lut, i, i, i);
        for (let c = 0; c < 3; c++) expect(p[c]!).toBeGreaterThanOrEqual(prev[c]!);
        prev = p;
      }
      // A finer check of the grey axis through the interpolated LUT (catches nothing new for a monotone grid,
      // but guards the generator itself at a different size).
      const fine = def.make(65);
      for (let i = 1; i < 65; i++) {
        const p = at(fine, i, i, i);
        const q = at(fine, i - 1, i - 1, i - 1);
        for (let c = 0; c < 3; c++) expect(p[c]!).toBeGreaterThanOrEqual(q[c]!);
      }
    });
  }

  /*
   * Black tolerance for the film and creative looks: pure black must stay within 0.02 of black, except the two
   * looks that lift black on purpose — "Cool Negative" (0.015, inside 0.02 anyway) and "Warm Vintage" (faded
   * blacks at 0.06) — which must stay within 0.08.
   */
  const BLACK_TOL = 0.02;
  const FADED_BLACK_TOL = 0.08;
  const FADED = new Set(["film-warm-vintage"]);
  for (const def of BUILTIN_LUTS.filter((b) => b.group === "Film" || b.group === "Creative")) {
    it(`${def.id}: black stays near black (within ${FADED.has(def.id) ? FADED_BLACK_TOL : BLACK_TOL})`, () => {
      const p = sampleLut(builtinLut(def.id)!, 0, 0, 0);
      const tol = FADED.has(def.id) ? FADED_BLACK_TOL : BLACK_TOL;
      for (const v of p) expect(v).toBeLessThanOrEqual(tol);
    });
  }

  it("black-and-white looks output grey; grey stays grey in the camera LUTs to within 0.002", () => {
    const rand = rng(4);
    for (const id of ["bw-neutral", "bw-red-filter", "bw-high-contrast"]) {
      const lut = builtinLut(id)!;
      for (let n = 0; n < 50; n++) {
        const p = sampleLut(lut, rand(), rand(), rand());
        expect(p[0]).toBe(p[1]);
        expect(p[1]).toBe(p[2]);
      }
    }
    for (const id of ["arri-logc3-709", "sony-slog3-709", "panasonic-vlog-709", "dji-dlog-709"]) {
      const lut = builtinLut(id)!;
      for (let i = 0; i < 33; i++) {
        const p = at(lut, i, i, i);
        expect(Math.abs(p[0] - p[1])).toBeLessThan(0.002);
        expect(Math.abs(p[2] - p[1])).toBeLessThan(0.002);
      }
    }
  });

  it("the red filter darkens blue and lightens red compared with neutral black and white", () => {
    const neutral = builtinLut("bw-neutral")!;
    const red = builtinLut("bw-red-filter")!;
    expect(sampleLut(red, 0.2, 0.3, 0.9)[0]).toBeLessThan(sampleLut(neutral, 0.2, 0.3, 0.9)[0]);
    expect(sampleLut(red, 0.9, 0.4, 0.3)[0]).toBeGreaterThan(sampleLut(neutral, 0.9, 0.4, 0.3)[0]);
  });

  it("range utilities: legal to full maps 16→0 and 235→255; full to legal is its inverse", () => {
    const l2f = builtinLut("legal-to-full", 2)!;
    const f2l = builtinLut("full-to-legal", 2)!;
    expect(at(f2l, 0, 0, 0)[0]).toBeCloseTo(16 / 255, 6);
    expect(at(f2l, 1, 1, 1)[0]).toBeCloseTo(235 / 255, 6);
    const l2fBig = builtinLut("legal-to-full", 33)!;
    expect(at(l2fBig, 0, 0, 0)[0]).toBe(0);
    expect(at(l2fBig, 32, 32, 32)[0]).toBe(1);
    // Full→legal is linear, and legal→full is linear inside 16..235, so the composition is the identity.
    const round = composeLuts(builtinLut("full-to-legal")!, builtinLut("legal-to-full")!);
    const s = sampleLut(round, 0.1, 0.5, 0.9);
    expect(s[0]).toBeCloseTo(0.1, 2);
    expect(s[1]).toBeCloseTo(0.5, 2);
    expect(s[2]).toBeCloseTo(0.9, 2);
    expect(l2f.size).toBe(2);
  });

  it("soft highlight roll-off leaves the mids alone and lands white at 0.92", () => {
    const lut = builtinLut("soft-highlight-rolloff", 11)!;
    expect(at(lut, 5, 5, 5)[0]).toBeCloseTo(0.5, 6);
    expect(at(lut, 7, 7, 7)[0]).toBeCloseTo(0.7, 6);
    expect(at(lut, 10, 10, 10)[0]).toBeCloseTo(0.92, 6);
    expect(at(lut, 9, 9, 9)[0]).toBeLessThan(0.9);
  });
});

describe("camera log curves against the manufacturers' documents", () => {
  const code = (cv: number) => cv / 1023;

  it('ARRI "ALEXA Log C Curve – Usage in VFX": LogC3 EI 800 maps 18% grey to 0.391 (400/1023) and back', () => {
    expect(linearToLogC3(0.18)).toBeCloseTo(0.391, 3);
    expect(logC3ToLinear(code(400))).toBeCloseTo(0.18, 3);
    // Black (0 linear) encodes to f = 0.092809; the two pieces meet at e·cut + f = 0.149658.
    expect(linearToLogC3(0)).toBeCloseTo(0.092809, 6);
    expect(linearToLogC3(0.010591)).toBeCloseTo(0.149658, 5);
    for (const x of [0, 0.005, 0.02, 0.18, 0.9, 5, 30]) expect(logC3ToLinear(linearToLogC3(x))).toBeCloseTo(x, 5);
  });

  it('Sony "Technical Summary for S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3": 0% = 95, 18% = 420, 90% = 598 (10-bit)', () => {
    expect(sLog3ToLinear(code(95))).toBeCloseTo(0, 6);
    expect(sLog3ToLinear(code(420))).toBeCloseTo(0.18, 6);
    expect(sLog3ToLinear(code(598))).toBeCloseTo(0.9, 2);
    expect(linearToSLog3(0) * 1023).toBeCloseTo(95, 6);
    expect(linearToSLog3(0.18) * 1023).toBeCloseTo(420, 6);
    expect(Math.round(linearToSLog3(0.9) * 1023)).toBe(598);
    for (const x of [0, 0.005, 0.01125, 0.05, 0.18, 0.9, 10]) expect(sLog3ToLinear(linearToSLog3(x))).toBeCloseTo(x, 6);
  });

  it('Panasonic "V-Log/V-Gamut Reference Manual" fig. 2.2: 0% = 128, 18% = 433, 90% = 602 (10-bit)', () => {
    expect(vLogToLinear(code(128))).toBeCloseTo(0, 4);
    expect(vLogToLinear(code(433))).toBeCloseTo(0.18, 3);
    expect(vLogToLinear(code(602))).toBeCloseTo(0.9, 2);
    expect(Math.round(linearToVLog(0) * 1023)).toBe(128);
    expect(Math.round(linearToVLog(0.18) * 1023)).toBe(433);
    expect(Math.round(linearToVLog(0.9) * 1023)).toBe(602);
    for (const x of [0, 0.005, 0.05, 0.18, 0.9, 10]) expect(vLogToLinear(linearToVLog(x))).toBeCloseTo(x, 5);
  });

  it('DJI "D-Log and D-Gamut White Paper": 0% = 95, 18% = 408, 90% = 586 (10-bit)', () => {
    expect(dLogToLinear(code(95))).toBeCloseTo(0, 4);
    expect(dLogToLinear(code(408))).toBeCloseTo(0.18, 3);
    expect(dLogToLinear(code(586))).toBeCloseTo(0.9, 2);
    expect(Math.round(linearToDLog(0) * 1023)).toBe(95);
    expect(Math.round(linearToDLog(0.18) * 1023)).toBe(408);
    expect(Math.round(linearToDLog(0.9) * 1023)).toBe(586);
    for (const x of [0, 0.05, 0.18, 0.9, 10]) expect(dLogToLinear(linearToDLog(x))).toBeCloseTo(x, 4);
  });

  it("ITU-R BT.709-6 OETF: 0.018 → 0.081, 1 → 1, 18% grey → 0.409", () => {
    expect(rec709Oetf(0)).toBe(0);
    expect(rec709Oetf(0.018)).toBeCloseTo(0.081, 3);
    expect(rec709Oetf(1)).toBeCloseTo(1, 10);
    expect(rec709Oetf(0.18)).toBeCloseTo(0.409, 3);
  });
});

describe("camera gamut matrices against the manufacturers' documents", () => {
  it('ARRI "ALEXA Log C Curve – Usage in VFX": our AWG3 → Rec.709 equals the paper\'s "linear display" matrix', () => {
    expectMatClose(AWG3_TO_REC709, [1.617523, -0.537287, -0.080237, -0.070573, 1.334613, -0.26404, -0.021102, -0.226954, 1.248056], 2e-4);
  });

  it('Panasonic "V-Log/V-Gamut Reference Manual" §4.2: our V-Gamut → Rec.709 equals the manual\'s matrix', () => {
    expectMatClose(VGAMUT_TO_REC709, [1.806576, -0.695697, -0.110879, -0.17009, 1.305955, -0.135865, -0.025206, -0.154468, 1.179674], 2e-4);
  });

  it('DJI "D-Log and D-Gamut White Paper": the printed D-Gamut → XYZ matrix matches its printed primaries', () => {
    const fromPrimaries = rgbToXyzMatrix({ r: [0.71, 0.31], g: [0.21, 0.88], b: [0.09, -0.08], w: [0.3127, 0.329] });
    // The paper prints 4 decimals and its values differ from the primaries by up to about 0.0002.
    expectMatClose(DGAMUT_TO_XYZ, fromPrimaries, 5e-4);
  });

  it("S-Gamut3.Cine → XYZ built from the primaries matches the published matrix (colour-science, citing Sony)", () => {
    expect(SGAMUT3CINE_PRIMARIES.r).toEqual([0.766, 0.275]);
    expectMatClose(SGAMUT3CINE_TO_XYZ, [0.59908392, 0.24892552, 0.10244649, 0.21507582, 0.8850685, -0.10014432, -0.03206585, -0.02765839, 1.14878199], 1e-6);
  });
});

describe("camera LUT tone curve", () => {
  it("filmic shoulder: identity to the knee, smooth, never reaching 1", () => {
    expect(filmicShoulder(-1)).toBe(0);
    expect(filmicShoulder(Number.NaN)).toBe(0);
    expect(filmicShoulder(0.18)).toBe(0.18);
    expect(filmicShoulder(0.5)).toBe(0.5);
    expect(filmicShoulder(1)).toBeCloseTo(0.75, 10);
    expect(filmicShoulder(5)).toBeCloseTo(0.95, 10);
    expect(filmicShoulder(1e9)).toBeLessThan(1);
    // Same slope on both sides of the knee.
    const h = 1e-6;
    expect((filmicShoulder(0.5 + h) - filmicShoulder(0.5)) / h).toBeCloseTo(1, 4);
  });

  it("each camera LUT puts its documented 18% grey code at Rec.709's 18% grey (0.409)", () => {
    const cases: [string, number][] = [
      ["arri-logc3-709", 400],
      ["sony-slog3-709", 420],
      ["panasonic-vlog-709", 433],
      ["dji-dlog-709", 408],
    ];
    for (const [id, cv] of cases) {
      // A 129-point grid keeps interpolation error on the steep log curves small.
      const lut = builtinLut(id, 129)!;
      const p = sampleLut(lut, cv / 1023, cv / 1023, cv / 1023);
      for (const v of p) expect(Math.abs(v - 0.409)).toBeLessThan(0.01);
    }
  });
});
