/**
 * Shared colour types: grading nodes (the colour page's node graph), curves, 3D LUTs and picture statistics.
 * Pure data — no DOM, no GPU — so the engine, the agent API, the UI and the tests all agree on one shape.
 */

/** Curve control points, x and y in 0..1, sorted by x. Two points [[0,0],[1,1]] is the identity. */
export type CurvePts = [number, number][];

/**
 * The curves of one node.
 *  m r g b — custom curves: output level for an input level (identity = straight diagonal).
 *  hh hs hl — hue vs hue / saturation / luminance: x is the hue (0..1 = 0..360°), y is the change, 0.5 = none.
 *  ls ss — luminance vs saturation, saturation vs saturation: x is the level, y is the gain, 0.5 = ×1 (1 = ×2).
 */
export interface NodeCurves {
  m?: CurvePts;
  r?: CurvePts;
  g?: CurvePts;
  b?: CurvePts;
  hh?: CurvePts;
  hs?: CurvePts;
  hl?: CurvePts;
  ls?: CurvePts;
  ss?: CurvePts;
}
export const CURVE_KEYS = ["m", "r", "g", "b", "hh", "hs", "hl", "ls", "ss"] as const;
export type CurveKey = (typeof CURVE_KEYS)[number];

/** HSL qualifier: picks the pixels a node works on by hue, saturation and luminance. All values 0..1. */
export interface Qualifier {
  on: boolean;
  /** Hue centre and half-width (0..0.5), with a soft edge. */
  h: number;
  hw: number;
  hs: number;
  /** Saturation range and soft edge. */
  s0: number;
  s1: number;
  ss: number;
  /** Luminance range and soft edge. */
  l0: number;
  l1: number;
  ls: number;
  invert: boolean;
}

/** Power window in the clip's own picture (fractions of the picture, so it follows the clip's transform). */
export interface PowerWindow {
  shape: "none" | "rect" | "ellipse" | "gradient";
  /** Centre, 0..1 from the left / top. */
  x: number;
  y: number;
  /** Full width and height as fractions of the picture (gradient: w is the length of the ramp). */
  w: number;
  h: number;
  /** Degrees, clockwise. */
  rot: number;
  /** Soft edge, 0..1 of the window size. */
  soft: number;
  invert: boolean;
  /** Animated window: seconds from the clip start; values between keys are interpolated. */
  keys?: { t: number; x: number; y: number; w: number; h: number; rot: number }[];
}

export type NodeBlend = "normal" | "add" | "multiply" | "screen" | "overlay" | "softLight";

/**
 * One grading node. Nodes run top to bottom. A "serial" node grades the result of the node before it; a "layer"
 * node grades the clip's picture as it entered the node graph and is laid over the result so far (through its
 * key, with its blend mode) — the same building blocks as serial and layer-mixer nodes on a colour page.
 */
export interface ColorNode {
  id: string;
  name: string;
  on: boolean;
  kind: "serial" | "layer";
  blend: NodeBlend;
  /** Key output gain: how much of the node shows, 0..1. */
  mix: number;
  /** Primaries. lift/gamma/gain: [r, g, b, master] offsets around neutral (0 = no change), about -1..1. */
  lift: [number, number, number, number];
  gamma: [number, number, number, number];
  gain: [number, number, number, number];
  /** Offset: [r, g, b] added to the whole picture, about -0.5..0.5. */
  offset: [number, number, number];
  /** 0..2 (1 = none) around `pivot` (0..1, default 0.435). */
  contrast: number;
  pivot: number;
  /** 0..2 (1 = none). */
  sat: number;
  /** Degrees. */
  hue: number;
  /** -1..1. */
  temp: number;
  tint: number;
  curves?: NodeCurves;
  /** A 3D LUT from the project's LUT list (or a built-in one), blended by `mix` 0..1. */
  lut?: { id: string; mix: number };
  qual?: Qualifier;
  win?: PowerWindow;
}

/** A 3D LUT: size³ RGB triples, red changing fastest (the .cube order), values nominally 0..1. */
export interface Lut3D {
  size: number;
  data: Float32Array;
  title?: string;
}

/** A LUT kept with the project: a built-in generator id, or the parsed table itself (base64 of 16-bit values). */
export interface ProjectLut {
  id: string;
  name: string;
  /** "builtin:<id>" for a generated LUT, otherwise "data". */
  source: string;
  size?: number;
  /** base64 of Uint16 little-endian RGB triples (0..65535 = 0..1), present when source is "data". */
  data?: string;
  /** Made by a tool (shot match, style DNA) rather than imported: removed automatically once no node uses it. */
  auto?: boolean;
}

/** What a picture looks like, in numbers: used for shot matching, look transfer and style DNA. */
export interface ColourStats {
  /** Pixels sampled. */
  n: number;
  /** CIE Lab (D65) mean and standard deviation; L is 0..100. */
  labMean: [number, number, number];
  labStd: [number, number, number];
  /** 64-bin histograms of the encoded (display) values, each normalised to sum 1. */
  lumaHist: number[];
  rgbHist: [number[], number[], number[]];
  /** Mean saturation 0..1 (HSV style). */
  satMean: number;
  /** Black and white points: 1st and 99th luma percentile, 0..1. */
  black: number;
  white: number;
  /** Dominant colours (at most 6), display RGB 0..1, weights sum to 1, heaviest first. */
  palette: { rgb: [number, number, number]; weight: number }[];
}

/** A picture as plain RGBA bytes (the shape of ImageData). */
export interface Pixels {
  data: Uint8ClampedArray | Uint8Array;
  width: number;
  height: number;
}

export const QUAL_DEFAULT: Qualifier = { on: false, h: 0, hw: 0.08, hs: 0.04, s0: 0.15, s1: 1, ss: 0.08, l0: 0, l1: 1, ls: 0.08, invert: false };
export const WIN_DEFAULT: PowerWindow = { shape: "none", x: 0.5, y: 0.5, w: 0.5, h: 0.5, rot: 0, soft: 0.2, invert: false };

export function newColorNode(id: string, name = "Node"): ColorNode {
  return { id, name, on: true, kind: "serial", blend: "normal", mix: 1, lift: [0, 0, 0, 0], gamma: [0, 0, 0, 0], gain: [0, 0, 0, 0], offset: [0, 0, 0], contrast: 1, pivot: 0.435, sat: 1, hue: 0, temp: 0, tint: 0 };
}

/** The most different LUTs the nodes of one clip can use together (GPU texture slots). */
export const MAX_CLIP_LUTS = 4;

/** The most nodes one clip can carry (a GPU uniform budget, not a creative one). */
export const MAX_NODES = 8;
