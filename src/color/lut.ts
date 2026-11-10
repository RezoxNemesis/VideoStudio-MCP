/**
 * 3D LUTs for VideoStudio: reading and writing .cube files, sampling, combining and storing LUTs with a project,
 * and a library of built-in LUTs (camera log to Rec.709 transforms, film-style looks and utilities).
 *
 * Everything here is pure TypeScript with no DOM, Node or GPU dependency, so the same code runs in the browser,
 * in a worker and in vitest.
 *
 * Conventions used throughout:
 *  - A Lut3D maps input RGB in 0..1 to output RGB. Its data holds size³ RGB triples with red changing fastest,
 *    then green, then blue (the .cube order). Grid point (i, j, k) sits at input (i, j, k) / (size - 1).
 *  - The camera LUTs take the camera's log signal as the manufacturers define it: the code value divided by
 *    1023 for 10-bit (full "data" range, no legal-range scaling).
 */

import type { Lut3D, ProjectLut } from "./types";

/* ------------------------------------------------------------------------------------------------------------ */
/* Small helpers                                                                                                 */
/* ------------------------------------------------------------------------------------------------------------ */

/** Smallest and largest 3D grid size we accept (2 is the smallest grid that can interpolate; 129 keeps memory sane). */
export const LUT_MIN_SIZE = 2;
export const LUT_MAX_SIZE = 129;
/** Largest 1D LUT we accept from a .cube file (1D LUTs are routinely 1024 or 4096 entries long). */
export const LUT_1D_MAX_SIZE = 65536;
/** The 3D grid size a 1D .cube LUT is converted to. */
export const LUT_1D_TO_3D_SIZE = 33;

/** Clamps to 0..1; NaN becomes 0 so a bad input can never poison a lookup. */
function clamp01(x: number): number {
  return x > 0 ? (x < 1 ? x : 1) : 0;
}

/** Checks that a grid size is a whole number in the supported range, with a plain-English message if not. */
function checkSize(size: number, what = "LUT size"): void {
  if (!Number.isInteger(size) || size < LUT_MIN_SIZE || size > LUT_MAX_SIZE) {
    throw new Error(`${what} must be a whole number from ${LUT_MIN_SIZE} to ${LUT_MAX_SIZE}, got ${size}.`);
  }
}

/** Checks that a Lut3D has a valid size and exactly size³ RGB triples. */
function checkLut(lut: Lut3D): void {
  checkSize(lut.size);
  const want = lut.size * lut.size * lut.size * 3;
  if (lut.data.length !== want) {
    throw new Error(`LUT data has ${lut.data.length} values but a size ${lut.size} LUT needs ${want}.`);
  }
}

/* ------------------------------------------------------------------------------------------------------------ */
/* Core helpers                                                                                                  */
/* ------------------------------------------------------------------------------------------------------------ */

/**
 * Builds a LUT by calling fn at every grid point. fn receives the grid point's input RGB (0..1) and returns the
 * output RGB. This is how every generated LUT in this file is made.
 */
export function mapLut(size: number, fn: (r: number, g: number, b: number) => [number, number, number], title?: string): Lut3D {
  checkSize(size);
  const n = size;
  const data = new Float32Array(n * n * n * 3);
  const step = 1 / (n - 1);
  let o = 0;
  for (let k = 0; k < n; k++) {
    const b = k * step;
    for (let j = 0; j < n; j++) {
      const g = j * step;
      for (let i = 0; i < n; i++) {
        const out = fn(i * step, g, b);
        data[o++] = out[0];
        data[o++] = out[1];
        data[o++] = out[2];
      }
    }
  }
  const lut: Lut3D = { size: n, data };
  if (title !== undefined) lut.title = title;
  return lut;
}

/** A LUT that changes nothing: every grid point outputs its own input. */
export function identityLut(size = 33): Lut3D {
  return mapLut(size, (r, g, b) => [r, g, b], "Identity");
}

/**
 * Looks up one colour with tetrahedral interpolation (the method used by colour grading systems: it splits each
 * grid cube into six tetrahedra and blends the four corners of the one the colour falls in). It is exact for any
 * LUT that is linear inside each cube, including the identity, and it keeps the grey axis neutral.
 * Inputs are clamped to 0..1 (NaN counts as 0). Outputs are returned as stored, without clamping.
 */
export function sampleLut(lut: Lut3D, r: number, g: number, b: number): [number, number, number] {
  const n = lut.size;
  const d = lut.data;
  const m = n - 1;
  const pr = clamp01(r) * m;
  const pg = clamp01(g) * m;
  const pb = clamp01(b) * m;
  // Lower corner of the cell; the top edge (input exactly 1) uses the last cell with fraction 1.
  const ir = Math.min(Math.floor(pr), m - 1);
  const ig = Math.min(Math.floor(pg), m - 1);
  const ib = Math.min(Math.floor(pb), m - 1);
  const fr = pr - ir;
  const fg = pg - ig;
  const fb = pb - ib;
  // Offsets (in floats) of one step along each axis.
  const sr = 3;
  const sg = n * 3;
  const sb = n * n * 3;
  const c000 = ir * sr + ig * sg + ib * sb;
  const c111 = c000 + sr + sg + sb;
  // Pick the tetrahedron by the order of the three fractions; each walks from c000 to c111 along the axes in
  // descending order of fraction.
  let a1: number;
  let a2: number;
  let w0: number;
  let w1: number;
  let w2: number;
  let w3: number;
  if (fr >= fg) {
    if (fg >= fb) {
      a1 = c000 + sr; a2 = c000 + sr + sg; w0 = 1 - fr; w1 = fr - fg; w2 = fg - fb; w3 = fb;
    } else if (fr >= fb) {
      a1 = c000 + sr; a2 = c000 + sr + sb; w0 = 1 - fr; w1 = fr - fb; w2 = fb - fg; w3 = fg;
    } else {
      a1 = c000 + sb; a2 = c000 + sr + sb; w0 = 1 - fb; w1 = fb - fr; w2 = fr - fg; w3 = fg;
    }
  } else {
    if (fb >= fg) {
      a1 = c000 + sb; a2 = c000 + sg + sb; w0 = 1 - fb; w1 = fb - fg; w2 = fg - fr; w3 = fr;
    } else if (fb >= fr) {
      a1 = c000 + sg; a2 = c000 + sg + sb; w0 = 1 - fg; w1 = fg - fb; w2 = fb - fr; w3 = fr;
    } else {
      a1 = c000 + sg; a2 = c000 + sr + sg; w0 = 1 - fg; w1 = fg - fr; w2 = fr - fb; w3 = fb;
    }
  }
  return [
    w0 * d[c000]! + w1 * d[a1]! + w2 * d[a2]! + w3 * d[c111]!,
    w0 * d[c000 + 1]! + w1 * d[a1 + 1]! + w2 * d[a2 + 1]! + w3 * d[c111 + 1]!,
    w0 * d[c000 + 2]! + w1 * d[a1 + 2]! + w2 * d[a2 + 2]! + w3 * d[c111 + 2]!,
  ];
}

/**
 * One LUT that does what applying `first` and then `then` does. Built by sampling `first` at each grid point of
 * the result and feeding that through `then`. `then` clamps its input to 0..1, exactly as applying the two LUTs
 * one after another would. The default size is the larger of the two.
 */
export function composeLuts(first: Lut3D, then: Lut3D, size?: number): Lut3D {
  checkLut(first);
  checkLut(then);
  const n = size ?? Math.max(first.size, then.size);
  const title = first.title !== undefined && then.title !== undefined ? `${first.title} + ${then.title}` : first.title ?? then.title;
  return mapLut(n, (r, g, b) => {
    const a = sampleLut(first, r, g, b);
    return sampleLut(then, a[0], a[1], a[2]);
  }, title);
}

/** The same LUT on a different grid size (resampled with tetrahedral interpolation). Keeps the title. */
export function resizeLut(lut: Lut3D, size: number): Lut3D {
  checkLut(lut);
  if (size === lut.size) {
    const copy: Lut3D = { size, data: new Float32Array(lut.data) };
    if (lut.title !== undefined) copy.title = lut.title;
    return copy;
  }
  return mapLut(size, (r, g, b) => sampleLut(lut, r, g, b), lut.title);
}

/**
 * Blends a LUT with the identity: amount 0 changes nothing, 1 is the full LUT, 0.5 is half way at every grid
 * point. Amount is clamped to 0..1. This is the same as a node's LUT "mix".
 */
export function mixLut(lut: Lut3D, amount: number): Lut3D {
  checkLut(lut);
  const t = clamp01(amount);
  const n = lut.size;
  const step = 1 / (n - 1);
  const src = lut.data;
  const data = new Float32Array(src.length);
  let o = 0;
  for (let k = 0; k < n; k++) {
    for (let j = 0; j < n; j++) {
      for (let i = 0; i < n; i++) {
        const ins = [i * step, j * step, k * step] as const;
        for (let c = 0; c < 3; c++) {
          const x = ins[c]!;
          data[o] = x + (src[o]! - x) * t;
          o++;
        }
      }
    }
  }
  const out: Lut3D = { size: n, data };
  if (lut.title !== undefined) out.title = lut.title;
  return out;
}

/* ------------------------------------------------------------------------------------------------------------ */
/* .cube files                                                                                                   */
/* ------------------------------------------------------------------------------------------------------------ */

/** A plain decimal or scientific-notation number (no hex, no Infinity, no NaN). */
const NUMBER_RE = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;
/** A keyword line starts with an upper-case word such as LUT_3D_SIZE or TITLE. */
const KEYWORD_RE = /^[A-Z][A-Z0-9_]*$/;

function parseNum(tok: string, lineNo: number): number {
  if (!NUMBER_RE.test(tok)) throw new Error(`Line ${lineNo} of the .cube file has "${tok}", which is not a number.`);
  const v = Number(tok);
  if (!Number.isFinite(v)) throw new Error(`Line ${lineNo} of the .cube file has "${tok}", which is too large to use.`);
  return v;
}

/** Linear interpolation of one channel of a 1D table (rows of RGB), at a position 0..1 across the table. */
function sample1D(table: Float32Array, n: number, channel: number, t: number): number {
  const p = clamp01(t) * (n - 1);
  const i = Math.min(Math.floor(p), n - 2);
  const f = p - i;
  const a = table[i * 3 + channel]!;
  const b = table[(i + 1) * 3 + channel]!;
  return a + (b - a) * f;
}

/** True when a domain is exactly 0..1 on every channel (then no resampling is needed). */
function isUnitDomain(min: number[], max: number[]): boolean {
  return min.every((v) => v === 0) && max.every((v) => v === 1);
}

/**
 * Reads an Adobe / DaVinci Resolve .cube file.
 *
 * Understood: TITLE, LUT_3D_SIZE, LUT_1D_SIZE, DOMAIN_MIN, DOMAIN_MAX, LUT_3D_INPUT_RANGE and LUT_1D_INPUT_RANGE
 * (Resolve's form of the domain), # comments, blank lines, Windows (CRLF) and old Mac (CR) line endings, tabs,
 * and scientific notation. Other upper-case keyword lines are ignored, as the format asks.
 *
 * Domain: the result always takes input in 0..1. When the file declares another input domain, the LUT is
 * resampled (tetrahedral) on a grid of the file's size or 33, whichever is larger, so that for any input in 0..1
 * the result gives what the file gives for that same input value. Inputs that fall outside the file's domain
 * are clamped to its edge (the .cube rule). Resampling is an approximation between grid points (for example
 * where the clamp at a domain edge falls inside a cell); a file whose domain is exactly 0..1 is copied without
 * resampling, so it loses nothing.
 *
 * 1D files: converted to a 3D LUT of size 33 by applying the 1D curve to each channel (linear interpolation).
 * A file with both a 1D and a 3D table (Resolve's "shaper + cube") is baked into one 3D LUT (size: the 3D size
 * or 33, whichever is larger): the 1D shaper runs first (with LUT_1D_INPUT_RANGE as its domain), then the cube
 * (with LUT_3D_INPUT_RANGE / DOMAIN as its domain).
 *
 * Errors (plain English, thrown as Error): no LUT size, a size that is not a whole number, a 3D size below 2 or
 * above 129 (1D sizes may be 2..65536), a row that is not three numbers, and the wrong number of rows.
 */
export function parseCube(text: string): Lut3D {
  let title: string | undefined;
  let size3 = 0;
  let size1 = 0;
  let dom3Min = [0, 0, 0];
  let dom3Max = [1, 1, 1];
  let dom1Min = [0, 0, 0];
  let dom1Max = [1, 1, 1];
  let has1Range = false;
  const rows: number[] = [];
  let rowCount = 0;
  const lines = text.replace(/^﻿/, "").split(/\r\n|\r|\n/);

  const readSize = (tok: string | undefined, key: string, lineNo: number, max: number): number => {
    if (tok === undefined) throw new Error(`Line ${lineNo}: ${key} needs a size.`);
    const v = parseNum(tok, lineNo);
    if (!Number.isInteger(v)) throw new Error(`Line ${lineNo}: ${key} must be a whole number, got ${tok}.`);
    if (v < LUT_MIN_SIZE || v > max) throw new Error(`Line ${lineNo}: ${key} must be from ${LUT_MIN_SIZE} to ${max}, got ${v}.`);
    return v;
  };
  const readTriple = (toks: string[], key: string, lineNo: number): number[] => {
    if (toks.length !== 4) throw new Error(`Line ${lineNo}: ${key} needs three numbers.`);
    return [parseNum(toks[1]!, lineNo), parseNum(toks[2]!, lineNo), parseNum(toks[3]!, lineNo)];
  };
  const readRange = (toks: string[], key: string, lineNo: number): [number[], number[]] => {
    if (toks.length !== 3) throw new Error(`Line ${lineNo}: ${key} needs two numbers (minimum and maximum).`);
    const lo = parseNum(toks[1]!, lineNo);
    const hi = parseNum(toks[2]!, lineNo);
    return [[lo, lo, lo], [hi, hi, hi]];
  };

  for (let li = 0; li < lines.length; li++) {
    const lineNo = li + 1;
    const raw = lines[li]!;
    const trimmed = raw.trim();
    // TITLE is read before comments are removed, so a "#" inside the quoted title is kept.
    if (/^TITLE(\s|$)/.test(trimmed)) {
      const rest = trimmed.slice(5).trim();
      const q = /^"([^"]*)"/.exec(rest);
      title = q ? q[1]! : rest.replace(/#.*$/, "").trim();
      continue;
    }
    const hash = raw.indexOf("#");
    const line = (hash >= 0 ? raw.slice(0, hash) : raw).trim();
    if (line === "") continue;
    const toks = line.split(/\s+/);
    const key = toks[0]!;
    if (KEYWORD_RE.test(key)) {
      switch (key) {
        case "LUT_3D_SIZE":
          size3 = readSize(toks[1], key, lineNo, LUT_MAX_SIZE);
          break;
        case "LUT_1D_SIZE":
          size1 = readSize(toks[1], key, lineNo, LUT_1D_MAX_SIZE);
          break;
        case "DOMAIN_MIN": {
          const v = readTriple(toks, key, lineNo);
          dom3Min = v;
          if (!has1Range) dom1Min = v;
          break;
        }
        case "DOMAIN_MAX": {
          const v = readTriple(toks, key, lineNo);
          dom3Max = v;
          if (!has1Range) dom1Max = v;
          break;
        }
        case "LUT_3D_INPUT_RANGE":
          [dom3Min, dom3Max] = readRange(toks, key, lineNo);
          break;
        case "LUT_1D_INPUT_RANGE":
          [dom1Min, dom1Max] = readRange(toks, key, lineNo);
          has1Range = true;
          break;
        default:
          // Unknown keywords (for example LUT_IN_VIDEO_RANGE) are allowed by the format and ignored.
          break;
      }
      continue;
    }
    if (toks.length !== 3) throw new Error(`Line ${lineNo} of the .cube file should have three numbers (red green blue) but has ${toks.length} values.`);
    rows.push(parseNum(toks[0]!, lineNo), parseNum(toks[1]!, lineNo), parseNum(toks[2]!, lineNo));
    rowCount++;
  }

  if (size3 === 0 && size1 === 0) throw new Error("The .cube file has no LUT_3D_SIZE or LUT_1D_SIZE line, so its size is unknown.");
  for (let c = 0; c < 3; c++) {
    if (!(dom3Max[c]! > dom3Min[c]!)) throw new Error("The .cube file's domain maximum must be greater than its minimum on every channel.");
    if (!(dom1Max[c]! > dom1Min[c]!)) throw new Error("The .cube file's 1D input range maximum must be greater than its minimum.");
  }
  const want = size1 + size3 * size3 * size3;
  if (rowCount !== want) {
    const parts = [size1 ? `${size1} 1D rows` : "", size3 ? `${size3 * size3 * size3} 3D rows (${size3}³)` : ""].filter((s) => s).join(" plus ");
    throw new Error(`The .cube file should have ${want} data rows (${parts}) but has ${rowCount}.`);
  }

  const all = new Float32Array(rows);
  const table1 = size1 ? all.subarray(0, size1 * 3) : null;
  const cube: Lut3D | null = size3 ? { size: size3, data: all.slice(size1 * 3) } : null;

  // Maps an input value 0..1 into a position 0..1 across a table with the given domain (clamped to the domain).
  const toUnit = (v: number, lo: number, hi: number): number => clamp01((v - lo) / (hi - lo));

  let out: Lut3D;
  if (cube && !table1) {
    out = isUnitDomain(dom3Min, dom3Max)
      ? cube
      : mapLut(Math.max(size3, LUT_1D_TO_3D_SIZE), (r, g, b) => sampleLut(cube, toUnit(r, dom3Min[0]!, dom3Max[0]!), toUnit(g, dom3Min[1]!, dom3Max[1]!), toUnit(b, dom3Min[2]!, dom3Max[2]!)));
  } else if (table1 && !cube) {
    out = mapLut(LUT_1D_TO_3D_SIZE, (r, g, b) => [
      sample1D(table1, size1, 0, toUnit(r, dom1Min[0]!, dom1Max[0]!)),
      sample1D(table1, size1, 1, toUnit(g, dom1Min[1]!, dom1Max[1]!)),
      sample1D(table1, size1, 2, toUnit(b, dom1Min[2]!, dom1Max[2]!)),
    ]);
  } else {
    const t1 = table1!;
    const c3 = cube!;
    out = mapLut(Math.max(size3, LUT_1D_TO_3D_SIZE), (r, g, b) => {
      const s0 = sample1D(t1, size1, 0, toUnit(r, dom1Min[0]!, dom1Max[0]!));
      const s1 = sample1D(t1, size1, 1, toUnit(g, dom1Min[1]!, dom1Max[1]!));
      const s2 = sample1D(t1, size1, 2, toUnit(b, dom1Min[2]!, dom1Max[2]!));
      return sampleLut(c3, toUnit(s0, dom3Min[0]!, dom3Max[0]!), toUnit(s1, dom3Min[1]!, dom3Max[1]!), toUnit(s2, dom3Min[2]!, dom3Max[2]!));
    });
  }
  if (title !== undefined) out.title = title;
  return out;
}

/** Formats one value with 6 decimals, never writing "-0.000000". */
function fmt6(v: number): string {
  const s = v.toFixed(6);
  return s === "-0.000000" ? "0.000000" : s;
}

/**
 * Writes a LUT as a .cube file (3D, domain 0..1, 6 decimals, LF line endings). The title is the argument, else
 * the LUT's own title; double quotes and line breaks in it are replaced so the file stays valid. The output
 * reads back through parseCube to within 0.0000005 per value.
 */
export function writeCube(lut: Lut3D, title?: string): string {
  checkLut(lut);
  const t = (title ?? lut.title ?? "").replace(/"/g, "'").replace(/[\r\n]+/g, " ").trim();
  const out: string[] = [];
  if (t) out.push(`TITLE "${t}"`);
  out.push("# Created by VideoStudio");
  out.push(`LUT_3D_SIZE ${lut.size}`);
  out.push("DOMAIN_MIN 0.0 0.0 0.0");
  out.push("DOMAIN_MAX 1.0 1.0 1.0");
  const d = lut.data;
  for (let o = 0; o < d.length; o += 3) out.push(`${fmt6(d[o]!)} ${fmt6(d[o + 1]!)} ${fmt6(d[o + 2]!)}`);
  return out.join("\n") + "\n";
}

/* ------------------------------------------------------------------------------------------------------------ */
/* Storing LUTs with a project (base64 of 16-bit values)                                                         */
/* ------------------------------------------------------------------------------------------------------------ */

const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const B64_INDEX: Int16Array = (() => {
  const t = new Int16Array(128).fill(-1);
  for (let i = 0; i < 64; i++) t[B64.charCodeAt(i)] = i;
  return t;
})();

/** Standard base64 (RFC 4648, with "=" padding) of a byte array. Written here so it needs no Buffer or btoa. */
export function bytesToBase64(bytes: Uint8Array): string {
  const parts: string[] = [];
  let chunk = "";
  const n = bytes.length;
  let i = 0;
  for (; i + 2 < n; i += 3) {
    const v = (bytes[i]! << 16) | (bytes[i + 1]! << 8) | bytes[i + 2]!;
    chunk += B64[(v >> 18) & 63]! + B64[(v >> 12) & 63]! + B64[(v >> 6) & 63]! + B64[v & 63]!;
    if (chunk.length >= 8192) {
      parts.push(chunk);
      chunk = "";
    }
  }
  const rest = n - i;
  if (rest === 1) {
    const v = bytes[i]! << 16;
    chunk += B64[(v >> 18) & 63]! + B64[(v >> 12) & 63]! + "==";
  } else if (rest === 2) {
    const v = (bytes[i]! << 16) | (bytes[i + 1]! << 8);
    chunk += B64[(v >> 18) & 63]! + B64[(v >> 12) & 63]! + B64[(v >> 6) & 63]! + "=";
  }
  parts.push(chunk);
  return parts.join("");
}

/** Decodes standard base64 (whitespace is ignored, padding optional). Throws on any other character. */
export function base64ToBytes(text: string): Uint8Array {
  const s = text.replace(/\s+/g, "").replace(/=+$/, "");
  if (s.length % 4 === 1) throw new Error("The base64 LUT data has a length that cannot be valid.");
  const out = new Uint8Array(Math.floor((s.length * 3) / 4));
  let o = 0;
  let acc = 0;
  let bits = 0;
  for (let i = 0; i < s.length; i++) {
    const code = s.charCodeAt(i);
    const v = code < 128 ? B64_INDEX[code]! : -1;
    if (v < 0) throw new Error(`The base64 LUT data has an invalid character "${s[i]}".`);
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[o++] = (acc >> bits) & 0xff;
    }
    acc &= (1 << bits) - 1;
  }
  return out;
}

/**
 * Packs a LUT for saving in the project: values clamped to 0..1, stored as 16-bit integers (0..65535),
 * little-endian, RGB triples in .cube order, then base64. The round-trip error is at most 1/131070 per value.
 */
export function lutToProject(lut: Lut3D, id: string, name: string): ProjectLut {
  checkLut(lut);
  const d = lut.data;
  const bytes = new Uint8Array(d.length * 2);
  for (let i = 0; i < d.length; i++) {
    const q = Math.round(clamp01(d[i]!) * 65535);
    bytes[i * 2] = q & 0xff;
    bytes[i * 2 + 1] = q >> 8;
  }
  return { id, name, source: "data", size: lut.size, data: bytesToBase64(bytes) };
}

/**
 * Unpacks a project LUT. "data" LUTs are decoded from base64; "builtin:<id>" LUTs are generated (at the stored
 * size, else 33). Throws a plain-English Error for an unknown built-in, a missing size or data, or data of the
 * wrong length. The returned LUT's title is the project LUT's name.
 */
export function projectToLut(pl: ProjectLut): Lut3D {
  if (pl.source.startsWith("builtin:")) {
    const id = pl.source.slice("builtin:".length);
    const lut = builtinLut(id, pl.size ?? 33);
    if (!lut) throw new Error(`The project uses a built-in LUT "${id}" that this version of VideoStudio does not have.`);
    lut.title = pl.name;
    return lut;
  }
  if (pl.source !== "data") throw new Error(`The project LUT "${pl.name}" has an unknown source "${pl.source}".`);
  if (pl.size === undefined) throw new Error(`The project LUT "${pl.name}" has no size.`);
  checkSize(pl.size, `The project LUT "${pl.name}" size`);
  if (pl.data === undefined) throw new Error(`The project LUT "${pl.name}" has no data.`);
  const bytes = base64ToBytes(pl.data);
  const count = pl.size * pl.size * pl.size * 3;
  if (bytes.length !== count * 2) {
    throw new Error(`The project LUT "${pl.name}" has ${bytes.length} bytes of data but a size ${pl.size} LUT needs ${count * 2}.`);
  }
  const data = new Float32Array(count);
  for (let i = 0; i < count; i++) data[i] = (bytes[i * 2]! | (bytes[i * 2 + 1]! << 8)) / 65535;
  return { size: pl.size, data, title: pl.name };
}

/* ------------------------------------------------------------------------------------------------------------ */
/* Colour science: matrices, standards and camera log curves                                                     */
/* ------------------------------------------------------------------------------------------------------------ */

/** A 3×3 matrix, row by row. */
export type Mat3 = readonly [number, number, number, number, number, number, number, number, number];
/** CIE 1931 xy chromaticities of red, green, blue and white. */
export interface Primaries {
  r: readonly [number, number];
  g: readonly [number, number];
  b: readonly [number, number];
  w: readonly [number, number];
}

export function mulMat3(a: Mat3, b: Mat3): Mat3 {
  const m = (i: number, j: number): number => a[i * 3]! * b[j]! + a[i * 3 + 1]! * b[3 + j]! + a[i * 3 + 2]! * b[6 + j]!;
  return [m(0, 0), m(0, 1), m(0, 2), m(1, 0), m(1, 1), m(1, 2), m(2, 0), m(2, 1), m(2, 2)];
}

export function invertMat3(a: Mat3): Mat3 {
  const [a0, a1, a2, a3, a4, a5, a6, a7, a8] = a;
  const c0 = a4 * a8 - a5 * a7;
  const c1 = a5 * a6 - a3 * a8;
  const c2 = a3 * a7 - a4 * a6;
  const det = a0 * c0 + a1 * c1 + a2 * c2;
  if (det === 0 || !Number.isFinite(det)) throw new Error("This colour matrix cannot be inverted.");
  const k = 1 / det;
  return [
    c0 * k, (a2 * a7 - a1 * a8) * k, (a1 * a5 - a2 * a4) * k,
    c1 * k, (a0 * a8 - a2 * a6) * k, (a2 * a3 - a0 * a5) * k,
    c2 * k, (a1 * a6 - a0 * a7) * k, (a0 * a4 - a1 * a3) * k,
  ];
}

function applyMat3(m: Mat3, r: number, g: number, b: number): [number, number, number] {
  return [m[0] * r + m[1] * g + m[2] * b, m[3] * r + m[4] * g + m[5] * b, m[6] * r + m[7] * g + m[8] * b];
}

/**
 * The RGB → CIE XYZ matrix of a colour space from its primaries and white point (the standard "normalised
 * primary matrix" construction, as in SMPTE RP 177): white RGB (1, 1, 1) maps to the white point with Y = 1.
 */
export function rgbToXyzMatrix(p: Primaries): Mat3 {
  const col = (xy: readonly [number, number]): [number, number, number] => [xy[0] / xy[1], 1, (1 - xy[0] - xy[1]) / xy[1]];
  const R = col(p.r);
  const G = col(p.g);
  const B = col(p.b);
  const W = col(p.w);
  const P: Mat3 = [R[0], G[0], B[0], R[1], G[1], B[1], R[2], G[2], B[2]];
  const S = applyMat3(invertMat3(P), W[0], W[1], W[2]);
  return [R[0] * S[0], G[0] * S[1], B[0] * S[2], R[1] * S[0], G[1] * S[1], B[1] * S[2], R[2] * S[0], G[2] * S[1], B[2] * S[2]];
}

/** Rec.709 primaries and D65 white. Source: ITU-R BT.709-6, items 1.3 and 1.4. */
export const REC709_PRIMARIES: Primaries = { r: [0.64, 0.33], g: [0.3, 0.6], b: [0.15, 0.06], w: [0.3127, 0.329] };
export const REC709_TO_XYZ: Mat3 = rgbToXyzMatrix(REC709_PRIMARIES);
export const XYZ_TO_REC709: Mat3 = invertMat3(REC709_TO_XYZ);

/**
 * Rec.709 opto-electronic transfer function (scene-linear 0..1 → signal 0..1).
 * Source: ITU-R BT.709-6, item 1.2: V = 1.099 L^0.45 − 0.099 for 1 ≥ L ≥ 0.018, V = 4.500 L for 0.018 > L ≥ 0.
 */
export function rec709Oetf(l: number): number {
  const x = clamp01(l);
  return x < 0.018 ? 4.5 * x : 1.099 * Math.pow(x, 0.45) - 0.099;
}

/** Rec.709 luma weights for a Y' made from R'G'B'. Source: ITU-R BT.709-6, item 3.2. */
const LUMA_R = 0.2126;
const LUMA_G = 0.7152;
const LUMA_B = 0.0722;

/* ---- ARRI LogC3 (EI 800) / ARRI Wide Gamut 3 ---- */
/*
 * Source: ARRI, "ALEXA Log C Curve – Usage in VFX" (white paper, 2017-03), the EI 800 row of the table for
 * relative scene linear ("linear exposure values"): cut 0.010591, a 5.555556, b 0.052272, c 0.247190,
 * d 0.385537, e 5.367655, f 0.092809, e·cut + f = 0.149658.
 * Encode: x > cut ? c·log10(a·x + b) + d : e·x + f.   Decode: t > e·cut + f ? (10^((t − d)/c) − b)/a : (t − f)/e.
 * The paper states 18% grey maps to LogC 0.391 (400/1023).
 */
const LOGC3 = { cut: 0.010591, a: 5.555556, b: 0.052272, c: 0.24719, d: 0.385537, e: 5.367655, f: 0.092809 } as const;

/** ARRI LogC3 (EI 800) signal → relative scene-linear exposure (0.18 = 18% grey). Source: ARRI "ALEXA Log C Curve – Usage in VFX". */
export function logC3ToLinear(t: number): number {
  const { cut, a, b, c, d, e, f } = LOGC3;
  return t > e * cut + f ? (Math.pow(10, (t - d) / c) - b) / a : (t - f) / e;
}

/** Relative scene-linear exposure → ARRI LogC3 (EI 800) signal. Source: ARRI "ALEXA Log C Curve – Usage in VFX". */
export function linearToLogC3(x: number): number {
  const { cut, a, b, c, d, e, f } = LOGC3;
  return x > cut ? c * Math.log10(a * x + b) + d : e * x + f;
}

/**
 * ARRI Wide Gamut 3 RGB → CIE XYZ. Source: ARRI "ALEXA Log C Curve – Usage in VFX" (primaries R 0.6840/0.3130,
 * G 0.2210/0.8480, B 0.0861/−0.1020, white D65 0.3127/0.3290).
 */
export const AWG3_TO_XYZ: Mat3 = [0.638008, 0.214704, 0.097744, 0.291954, 0.823841, -0.115795, 0.002798, -0.067034, 1.153294];

/* ---- Sony S-Log3 / S-Gamut3.Cine ---- */
/*
 * Source: Sony, "Technical Summary for S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3", appendix "S-Log3 Formula":
 *   S-Log3 → reflection: in ≥ 171.2102946929/1023 ? 10^((in·1023 − 420)/261.5)·(0.18 + 0.01) − 0.01
 *                                               : (in·1023 − 95)·0.01125/(171.2102946929 − 95)
 *   reflection → S-Log3: in ≥ 0.01125 ? (420 + log10((in + 0.01)/(0.18 + 0.01))·261.5)/1023
 *                                     : (in·(171.2102946929 − 95)/0.01125 + 95)/1023
 * Code values in the same document: 0% black = 95, 18% grey = 420, 90% white = 598 (10-bit).
 */
const SLOG3_CUT_CODE = 171.2102946929;

/** Sony S-Log3 signal → scene-linear reflection (0.18 = 18% grey). Source: Sony "Technical Summary for S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3". */
export function sLog3ToLinear(t: number): number {
  return t >= SLOG3_CUT_CODE / 1023 ? Math.pow(10, (t * 1023 - 420) / 261.5) * (0.18 + 0.01) - 0.01 : ((t * 1023 - 95) * 0.01125) / (SLOG3_CUT_CODE - 95);
}

/** Scene-linear reflection → Sony S-Log3 signal. Source: Sony "Technical Summary for S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3". */
export function linearToSLog3(x: number): number {
  return x >= 0.01125 ? (420 + Math.log10((x + 0.01) / (0.18 + 0.01)) * 261.5) / 1023 : ((x * (SLOG3_CUT_CODE - 95)) / 0.01125 + 95) / 1023;
}

/**
 * S-Gamut3.Cine primaries (R 0.766/0.275, G 0.225/0.800, B 0.089/−0.087, white D65).
 * Source: Sony "Technical Summary for S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3", section "Color Primaries and
 * conversion matrix". That section is printed as an image in the copy available to us, so these values were
 * checked against the colour-science library's S-Gamut3.Cine definition, which cites that Sony document.
 * The matrix is built from the primaries (see rgbToXyzMatrix).
 */
export const SGAMUT3CINE_PRIMARIES: Primaries = { r: [0.766, 0.275], g: [0.225, 0.8], b: [0.089, -0.087], w: [0.3127, 0.329] };
export const SGAMUT3CINE_TO_XYZ: Mat3 = rgbToXyzMatrix(SGAMUT3CINE_PRIMARIES);

/* ---- Panasonic V-Log / V-Gamut ---- */
/*
 * Source: Panasonic, "V-Log/V-Gamut Reference Manual", Rev.1.0, 28 November 2014, section 3.2:
 *   out = (in − 0.125)/5.6 for in < cut2;  out = 10^((in − d)/c) − b for in ≥ cut2;
 *   cut2 = 0.181, b = 0.00873, c = 0.241514, d = 0.598206 (encode: cut1 = 0.01, out = 5.6·in + 0.125 below it).
 * Code values in the same document (fig. 2.2): 0% = 128, 18% = 433, 90% = 602 (10-bit).
 */
const VLOG = { cut1: 0.01, cut2: 0.181, b: 0.00873, c: 0.241514, d: 0.598206 } as const;

/** Panasonic V-Log signal → scene-linear reflection. Source: Panasonic "V-Log/V-Gamut Reference Manual" §3.2. */
export function vLogToLinear(t: number): number {
  return t < VLOG.cut2 ? (t - 0.125) / 5.6 : Math.pow(10, (t - VLOG.d) / VLOG.c) - VLOG.b;
}

/** Scene-linear reflection → Panasonic V-Log signal. Source: Panasonic "V-Log/V-Gamut Reference Manual" §3.1. */
export function linearToVLog(x: number): number {
  return x < VLOG.cut1 ? 5.6 * x + 0.125 : VLOG.c * Math.log10(x + VLOG.b) + VLOG.d;
}

/** V-Gamut RGB → CIE XYZ. Source: Panasonic "V-Log/V-Gamut Reference Manual" §4.2. */
export const VGAMUT_TO_XYZ: Mat3 = [0.679644, 0.152211, 0.1186, 0.260686, 0.774894, -0.03558, -0.00931, -0.004612, 1.10298];

/* ---- DJI D-Log / D-Gamut ---- */
/*
 * Source: DJI, "D-Log and D-Gamut White Paper" (DJI Cinema Color System; D-Log EI measured on the Zenmuse X5S):
 *   D-Log → linear: x ≤ 0.14 ? (x − 0.0929)/6.025 : (10^(3.89616·x − 2.27752) − 0.0108)/0.9892
 *   linear → D-Log: in ≤ 0.0078 ? 6.025·in + 0.0929 : log10(in·0.9892 + 0.0108)·0.256663 + 0.584555
 * Code values in the same document: 0% = 95, 18% = 408, 90% = 586 (10-bit).
 * Note: the paper's two decode pieces meet at x = 0.14 with a step of about −0.000008 in linear (its encode
 * cut is 0.0078, which encodes to 0.1419, not 0.14). We use the published constants unchanged; the step is far
 * below one 10-bit code value and does not show in a LUT grid.
 * This is the original D-Log, not D-Log M or D-Log 2, which are different curves.
 */
/** DJI D-Log signal → scene-linear reflection. Source: DJI "D-Log and D-Gamut White Paper". */
export function dLogToLinear(x: number): number {
  return x <= 0.14 ? (x - 0.0929) / 6.025 : (Math.pow(10, 3.89616 * x - 2.27752) - 0.0108) / 0.9892;
}

/** Scene-linear reflection → DJI D-Log signal. Source: DJI "D-Log and D-Gamut White Paper". */
export function linearToDLog(x: number): number {
  return x <= 0.0078 ? 6.025 * x + 0.0929 : Math.log10(x * 0.9892 + 0.0108) * 0.256663 + 0.584555;
}

/** D-Gamut RGB → CIE XYZ (primaries R 0.71/0.31, G 0.21/0.88, B 0.09/−0.08, white D65). Source: DJI "D-Log and D-Gamut White Paper". */
export const DGAMUT_TO_XYZ: Mat3 = [0.6482, 0.194, 0.1082, 0.283, 0.8132, -0.0962, -0.0183, -0.0832, 1.1903];

/* Canon Log 3 / Cinema Gamut is not included: Canon's white paper "Description of the Canon Log, Canon Log 2 and
 * Canon Log 3 Gamma Curves" (2018) does not print the formulas or the gamut, so we could not verify them. */

/* ------------------------------------------------------------------------------------------------------------ */
/* Camera → Rec.709 display transforms                                                                           */
/* ------------------------------------------------------------------------------------------------------------ */

/**
 * Where the highlight shoulder starts, in scene-linear (about 1.5 stops above 18% grey). Below it the tone curve
 * is the identity, so 18% grey keeps exactly its normal Rec.709 value (0.18 → 0.409).
 */
export const SCENE_SHOULDER_KNEE = 0.5;

/**
 * VideoStudio's filmic highlight roll-off for scene-linear light (our own curve, not from any standard).
 * Identity up to the knee k; above it, y = (x − k)/(1 − k) is compressed to k + (1 − k)·y/(1 + y). It meets the
 * straight line with the same slope (no visible kink), rises forever but never reaches 1, so nothing clips
 * hard: with k = 0.5, 90% white → 0.72, scene 1.0 → 0.75, 2.0 → 0.875, 5.0 → 0.95 (about 4.8 stops above grey).
 * Negative input (out-of-gamut colour after the matrix) is clipped to 0 first.
 */
export function filmicShoulder(x: number, knee = SCENE_SHOULDER_KNEE): number {
  if (!(x > 0)) return 0;
  if (x <= knee) return x;
  const y = (x - knee) / (1 - knee);
  return knee + (1 - knee) * (y / (1 + y));
}

/**
 * A camera → Rec.709 display LUT: decode the log signal to scene-linear, convert the camera gamut to Rec.709
 * primaries (camera → XYZ → Rec.709, both D65 so no white adaptation is needed), apply the filmic shoulder to
 * each channel, then the Rec.709 OETF. The result looks like footage shot in a Rec.709 camera mode, with
 * softened highlights. The matrices are the colorimetric ones; strongly saturated colours outside Rec.709 are
 * clipped per channel.
 */
function cameraToRec709(size: number, decode: (t: number) => number, camToXyz: Mat3, title: string): Lut3D {
  const m = mulMat3(XYZ_TO_REC709, camToXyz);
  return mapLut(size, (r, g, b) => {
    const lin = applyMat3(m, decode(r), decode(g), decode(b));
    return [rec709Oetf(filmicShoulder(lin[0])), rec709Oetf(filmicShoulder(lin[1])), rec709Oetf(filmicShoulder(lin[2]))];
  }, title);
}

/** The combined camera gamut → Rec.709 matrices (exported so tests can compare them with the published ones). */
export const AWG3_TO_REC709: Mat3 = mulMat3(XYZ_TO_REC709, AWG3_TO_XYZ);
export const SGAMUT3CINE_TO_REC709: Mat3 = mulMat3(XYZ_TO_REC709, SGAMUT3CINE_TO_XYZ);
export const VGAMUT_TO_REC709: Mat3 = mulMat3(XYZ_TO_REC709, VGAMUT_TO_XYZ);
export const DGAMUT_TO_REC709: Mat3 = mulMat3(XYZ_TO_REC709, DGAMUT_TO_XYZ);

/* ------------------------------------------------------------------------------------------------------------ */
/* Film looks and utilities (display-referred Rec.709 in, Rec.709 out)                                           */
/* ------------------------------------------------------------------------------------------------------------ */

/**
 * A smooth S-curve with fixed end points: x + k·(smoothstep(x) − x). Its slope is 1 + k·(6x − 6x² − 1), which is
 * at least 1 − k, so it never decreases for k ≤ 1. k > 0 adds mid-tone contrast and softens toe and shoulder.
 */
function sCurve(x: number, k: number): number {
  return x + k * (x * x * (3 - 2 * x) - x);
}

/** Split-tone weights: shadow weight peaks (1) at 1/3, highlight weight peaks at 2/3, both 0 at black and white. */
function shadowWeight(l: number): number {
  return 6.75 * l * (1 - l) * (1 - l);
}
function highlightWeight(l: number): number {
  return 6.75 * l * l * (1 - l);
}

/** The settings of one look; every step keeps the grey axis rising (see `look`). */
interface LookParams {
  /** Saturation around Rec.709 luma (1 = none). */
  sat?: number;
  /** Power applied to each channel (1 = none). */
  gamma?: number;
  /** S-curve strength 0..1 (0 = none). */
  contrast?: number;
  /** Colour added to the shadows / highlights per channel; each value must stay within ±0.08 (keeps monotonic). */
  shadow?: readonly [number, number, number];
  highlight?: readonly [number, number, number];
  /** Per-channel gain (≥ 0). */
  gain?: readonly [number, number, number];
  /** Output black and white levels (fade). */
  black?: number;
  white?: number;
}

/**
 * Builds a look function. Steps, in order: saturation (does nothing to greys), per-channel gamma and S-curve
 * (rising, end points fixed), split toning (rising for tints within ±0.08: slope ≥ 1 − 0.08·6.75 > 0), gain,
 * fade to the black/white levels, clamp to 0..1. Each step is non-decreasing along the grey axis, so the look is.
 */
function look(p: LookParams): (r: number, g: number, b: number) => [number, number, number] {
  const sat = p.sat ?? 1;
  const gamma = p.gamma ?? 1;
  const k = p.contrast ?? 0;
  const sh = p.shadow ?? [0, 0, 0];
  const hi = p.highlight ?? [0, 0, 0];
  const gain = p.gain ?? [1, 1, 1];
  const black = p.black ?? 0;
  const white = p.white ?? 1;
  return (r, g, b) => {
    const y = LUMA_R * r + LUMA_G * g + LUMA_B * b;
    const c = [clamp01(y + (r - y) * sat), clamp01(y + (g - y) * sat), clamp01(y + (b - y) * sat)];
    for (let i = 0; i < 3; i++) c[i] = sCurve(Math.pow(c[i]!, gamma), k);
    const l = clamp01(LUMA_R * c[0]! + LUMA_G * c[1]! + LUMA_B * c[2]!);
    const ws = shadowWeight(l);
    const wh = highlightWeight(l);
    const out: [number, number, number] = [0, 0, 0];
    for (let i = 0; i < 3; i++) {
      const v = (c[i]! + sh[i]! * ws + hi[i]! * wh) * gain[i]!;
      out[i] = clamp01(black + (white - black) * clamp01(v));
    }
    return out;
  };
}

/** A black-and-white look: a weighted mix of R, G, B (weights sum to 1, so grey stays grey), then a tone curve. */
function mono(weights: readonly [number, number, number], tone: (x: number) => number): (r: number, g: number, b: number) => [number, number, number] {
  return (r, g, b) => {
    const v = clamp01(tone(clamp01(weights[0] * r + weights[1] * g + weights[2] * b)));
    return [v, v, v];
  };
}

/** Knee of the display-referred "Soft highlight roll-off" utility. */
const SOFT_ROLL_KNEE = 0.7;
/** Strength of that roll-off: chosen so input 1.0 lands at 0.92. */
const SOFT_ROLL_A = 0.3 / (0.92 - SOFT_ROLL_KNEE) - 1;

/**
 * Display-referred highlight roll-off: identity below 0.7, then 0.7 + 0.3·y/(1 + a·y) with y = (x − 0.7)/0.3,
 * which meets the straight line with the same slope and brings full white down to 0.92. A rising curve that
 * starts with slope 1 cannot bend down and still end at 1, so the top of the range is lowered a little.
 */
function softRoll(x: number): number {
  const v = clamp01(x);
  if (v <= SOFT_ROLL_KNEE) return v;
  const y = (v - SOFT_ROLL_KNEE) / (1 - SOFT_ROLL_KNEE);
  return SOFT_ROLL_KNEE + (1 - SOFT_ROLL_KNEE) * (y / (1 + SOFT_ROLL_A * y));
}

/* ------------------------------------------------------------------------------------------------------------ */
/* Built-in library                                                                                              */
/* ------------------------------------------------------------------------------------------------------------ */

export interface BuiltinLut {
  id: string;
  name: string;
  group: "Camera" | "Film" | "Creative" | "Utility";
  about: string;
  make(size?: number): Lut3D;
}

const FILM_NOTE = "VideoStudio's own look, inspired by";

function lookLut(id: string, name: string, group: BuiltinLut["group"], about: string, fn: (r: number, g: number, b: number) => [number, number, number]): BuiltinLut {
  return { id, name, group, about, make: (size = 33) => mapLut(size, fn, name) };
}

/**
 * Every built-in LUT. Camera LUTs expect the camera's log signal; the others expect Rec.709 display pictures.
 * All of them keep outputs in 0..1, never produce NaN and rise along the grey axis (checked by the tests).
 */
export const BUILTIN_LUTS: BuiltinLut[] = [
  {
    id: "arri-logc3-709",
    name: "ARRI LogC3 to Rec.709",
    group: "Camera",
    about: "ARRI LogC3 (EI 800), ARRI Wide Gamut 3 to Rec.709 with a soft highlight roll-off. Curve and gamut from ARRI's \"ALEXA Log C Curve – Usage in VFX\".",
    make: (size = 33) => cameraToRec709(size, logC3ToLinear, AWG3_TO_XYZ, "ARRI LogC3 to Rec.709"),
  },
  {
    id: "sony-slog3-709",
    name: "Sony S-Log3 to Rec.709",
    group: "Camera",
    about: "Sony S-Log3, S-Gamut3.Cine to Rec.709 with a soft highlight roll-off. Curve from Sony's \"Technical Summary for S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3\".",
    make: (size = 33) => cameraToRec709(size, sLog3ToLinear, SGAMUT3CINE_TO_XYZ, "Sony S-Log3 to Rec.709"),
  },
  {
    id: "panasonic-vlog-709",
    name: "Panasonic V-Log to Rec.709",
    group: "Camera",
    about: "Panasonic V-Log, V-Gamut to Rec.709 with a soft highlight roll-off. Curve and gamut from Panasonic's \"V-Log/V-Gamut Reference Manual\".",
    make: (size = 33) => cameraToRec709(size, vLogToLinear, VGAMUT_TO_XYZ, "Panasonic V-Log to Rec.709"),
  },
  {
    id: "dji-dlog-709",
    name: "DJI D-Log to Rec.709",
    group: "Camera",
    about: "DJI D-Log, D-Gamut (the original D-Log, not D-Log M) to Rec.709 with a soft highlight roll-off. Curve and gamut from DJI's \"D-Log and D-Gamut White Paper\".",
    make: (size = 33) => cameraToRec709(size, dLogToLinear, DGAMUT_TO_XYZ, "DJI D-Log to Rec.709"),
  },
  lookLut("film-print", "Print Film", "Film", `${FILM_NOTE} classic print film: warm highlights, cool-teal shadows, a soft toe and shoulder. Not a copy of any vendor's LUT.`,
    look({ sat: 1.05, contrast: 0.35, shadow: [-0.04, 0.0, 0.035], highlight: [0.04, 0.005, -0.035] })),
  lookLut("film-negative-cool", "Cool Negative", "Film", `${FILM_NOTE} a cooler camera negative scan: low contrast, gentle cool cast, slightly lifted blacks. Not a copy of any vendor's LUT.`,
    look({ sat: 0.9, contrast: 0.15, shadow: [-0.02, 0.005, 0.03], highlight: [-0.01, 0.0, 0.02], black: 0.015, white: 0.985 })),
  lookLut("film-bleach-bypass", "Bleach Bypass", "Film", `${FILM_NOTE} the bleach bypass process: low saturation, hard contrast, silvery highlights. Not a copy of any vendor's LUT.`,
    look({ sat: 0.45, gamma: 1.05, contrast: 0.75, shadow: [-0.01, 0.0, 0.01] })),
  lookLut("film-warm-vintage", "Warm Vintage", "Film", `${FILM_NOTE} faded vintage prints: warm cast, softer colour, faded (lifted) blacks and dimmed whites. Not a copy of any vendor's LUT.`,
    look({ sat: 0.8, contrast: 0.2, shadow: [0.02, 0.0, -0.02], highlight: [0.05, 0.02, -0.05], gain: [1, 0.98, 0.92], black: 0.06, white: 0.96 })),
  lookLut("creative-teal-orange", "Teal & Orange", "Creative", `${FILM_NOTE} the blockbuster teal-and-orange grade: teal shadows, warm skin-tone highlights. Not a copy of any vendor's LUT.`,
    look({ sat: 1.1, contrast: 0.3, shadow: [-0.07, 0.01, 0.06], highlight: [0.07, 0.0, -0.06] })),
  lookLut("creative-cross-process", "Cross Process", "Creative", `${FILM_NOTE} cross-processed film: punchy red, cyan shadows, yellow highlights. Not a copy of any vendor's LUT.`,
    (r, g, b) => {
      const y = LUMA_R * r + LUMA_G * g + LUMA_B * b;
      const s = 1.15;
      const R = clamp01(y + (r - y) * s);
      const G = clamp01(y + (g - y) * s);
      const B = clamp01(y + (b - y) * s);
      const l = clamp01(LUMA_R * R + LUMA_G * G + LUMA_B * B);
      const ws = shadowWeight(l);
      // Red: strong S-curve. Green: gentle lift. Blue: pulled down in the highlights (x^1.15 − 0.15·x^2.15 rises on 0..1).
      const ro = sCurve(R, 0.6) - 0.03 * ws;
      const go = sCurve(Math.pow(G, 0.92), 0.2) + 0.03 * ws;
      const bo = Math.pow(B, 1.15) - 0.15 * Math.pow(B, 2.15) + 0.04 * ws;
      return [clamp01(ro), clamp01(go), clamp01(bo)];
    }),
  lookLut("creative-cool-night", "Cool Night", "Creative", `${FILM_NOTE} classic day-for-night: darker, desaturated, moonlit blue. Not a copy of any vendor's LUT.`,
    (r, g, b) => {
      const y = LUMA_R * r + LUMA_G * g + LUMA_B * b;
      const s = 0.55;
      const c = [clamp01(y + (r - y) * s), clamp01(y + (g - y) * s), clamp01(y + (b - y) * s)];
      const gain = [0.62, 0.72, 0.95];
      return [0, 1, 2].map((i) => clamp01(sCurve(Math.pow(c[i]!, 1.35), 0.3) * gain[i]!)) as [number, number, number];
    }),
  lookLut("bw-neutral", "Black & White", "Film", `${FILM_NOTE} neutral black-and-white film: Rec.709 luminance with a gentle S-curve. Not a copy of any vendor's LUT.`,
    mono([LUMA_R, LUMA_G, LUMA_B], (x) => sCurve(x, 0.3))),
  lookLut("bw-red-filter", "Black & White Red Filter", "Film", `${FILM_NOTE} black-and-white film shot through a red filter: darker skies, lighter skin. Not a copy of any vendor's LUT.`,
    mono([0.75, 0.25, 0], (x) => sCurve(x, 0.35))),
  lookLut("bw-high-contrast", "Black & White High Contrast", "Film", `${FILM_NOTE} hard black-and-white prints: deep blacks and bright whites. Not a copy of any vendor's LUT.`,
    mono([LUMA_R, LUMA_G, LUMA_B], (x) => sCurve(sCurve(x, 1), 0.6))),
  lookLut("legal-to-full", "Legal to Full Range", "Utility", "Expands legal (video) range to full range: 8-bit 16 → 0 and 235 → 255, i.e. (v·255 − 16)/219. Values below 16 or above 235 are clipped.",
    (r, g, b) => [clamp01((r * 255 - 16) / 219), clamp01((g * 255 - 16) / 219), clamp01((b * 255 - 16) / 219)]),
  lookLut("full-to-legal", "Full to Legal Range", "Utility", "Squeezes full range into legal (video) range: 8-bit 0 → 16 and 255 → 235, i.e. (16 + 219·v)/255.",
    (r, g, b) => [(16 + 219 * r) / 255, (16 + 219 * g) / 255, (16 + 219 * b) / 255]),
  lookLut("soft-highlight-rolloff", "Soft Highlight Roll-off", "Utility", "Leaves everything below 70% untouched and bends the top smoothly so full white lands at 92%, softening hard-clipped highlights.",
    (r, g, b) => [softRoll(r), softRoll(g), softRoll(b)]),
];

const builtinCache = new Map<string, Lut3D>();

/**
 * Generates a built-in LUT by id, or returns null for an unknown id. Results are cached per id and size; each
 * call returns a fresh copy so a caller that edits its LUT cannot change what the next caller gets.
 * A size outside 2..129 throws (as mapLut does).
 */
export function builtinLut(id: string, size = 33): Lut3D | null {
  const key = `${id}@${size}`;
  let lut = builtinCache.get(key);
  if (!lut) {
    const def = BUILTIN_LUTS.find((b) => b.id === id);
    if (!def) return null;
    lut = def.make(size);
    builtinCache.set(key, lut);
  }
  const copy: Lut3D = { size: lut.size, data: new Float32Array(lut.data) };
  if (lut.title !== undefined) copy.title = lut.title;
  return copy;
}
