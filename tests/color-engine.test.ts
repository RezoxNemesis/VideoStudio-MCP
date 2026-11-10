import { describe, expect, it } from "vitest";
import { applyNodes, bakeCurves, bakeNodesLut, cleanCurve, curveFn, curveMask, CURVE_RES, CURVE_ROWS, gradeBasic, isNeutralNode, lutLookup, qualKey, resolveNodes, sanitizeNode, windowAt, winKey } from "../src/color/engine";
import { MAX_NODES, newColorNode, type ColorNode, type Lut3D } from "../src/color/types";

const node = (patch: Partial<ColorNode> = {}): ColorNode => sanitizeNode({ ...newColorNode("n1"), ...patch });
const close = (a: number[], b: number[], eps = 1e-6) => a.forEach((v, i) => expect(Math.abs(v - b[i]!)).toBeLessThan(eps));

describe("curves", () => {
  it("identity curves change nothing and are not baked", () => {
    expect(curveMask({ m: [[0, 0], [1, 1]], hs: [[0, 0.5], [1, 0.5]] })).toBe(0);
    expect(bakeCurves([node({ curves: { m: [[0, 0], [0.5, 0.5], [1, 1]] } })])).toBeNull();
    const f = curveFn("m", null);
    expect(f(0.3)).toBeCloseTo(0.3, 9);
  });
  it("passes through its points, stays monotonic and inside 0..1", () => {
    const pts: [number, number][] = [[0, 0], [0.25, 0.1], [0.75, 0.95], [1, 1]];
    const f = curveFn("m", pts);
    for (const [x, y] of pts) expect(f(x)).toBeCloseTo(y, 6);
    let prev = -1;
    for (let i = 0; i <= 1000; i++) {
      const v = f(i / 1000);
      expect(v).toBeGreaterThanOrEqual(prev - 1e-9);
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThanOrEqual(1);
      prev = v;
    }
  });
  it("holds the end values outside the first and last point", () => {
    const f = curveFn("r", [[0.2, 0.1], [0.8, 0.9]]);
    expect(f(0)).toBeCloseTo(0.1, 9);
    expect(f(1)).toBeCloseTo(0.9, 9);
  });
  it("hue curves wrap around the 0/1 seam", () => {
    const f = curveFn("hs", [[0.02, 0.9], [0.5, 0.5], [0.98, 0.9]]);
    expect(f(0)).toBeCloseTo(f(1), 6);
    expect(f(0)).toBeGreaterThan(0.85);
    expect(f(0.5)).toBeCloseTo(0.5, 6);
  });
  it("cleans bad input", () => {
    expect(cleanCurve("x")).toBeNull();
    expect(cleanCurve([[0.5, 2], [0.5, 0.2], ["a", 1], [-1, 0.3]])).toEqual([[0, 0.3], [0.5, 0.2]]);
  });
  it("bakes one row per curve at the node's slot", () => {
    const b = bakeCurves([node(), node({ id: "n2", curves: { g: [[0, 0], [0.5, 0.8], [1, 1]] } })])!;
    expect(b.rows).toBe(MAX_NODES * CURVE_ROWS);
    expect(b.data.length).toBe(CURVE_RES * b.rows);
    const row = (1 * CURVE_ROWS + 2) * CURVE_RES; // node 2, curve "g"
    expect(b.data[row]).toBeCloseTo(0, 6);
    expect(b.data[row + CURVE_RES - 1]).toBeCloseTo(1, 6);
    expect(b.data[row + Math.round((CURVE_RES - 1) / 2)]).toBeGreaterThan(0.78);
    expect(bakeCurves([node(), node({ id: "n2", curves: { g: [[0, 0], [0.5, 0.8], [1, 1]] } })])!.data).toBe(b.data); // cached
  });
});

describe("nodes", () => {
  it("a new node is neutral and leaves every colour alone", () => {
    const n = node();
    expect(isNeutralNode(n)).toBe(true);
    for (const c of [[0, 0, 0], [1, 1, 1], [0.2, 0.5, 0.9], [0.9, 0.1, 0.4]] as [number, number, number][]) close(applyNodes([n], c), c, 1e-9);
  });
  it("gain, lift, gamma and offset follow the documented formulas", () => {
    close(applyNodes([node({ gain: [0, 0, 0, 0.5] })], [0.4, 0.4, 0.4]), [0.6, 0.6, 0.6]);
    close(applyNodes([node({ lift: [0, 0, 0, 0.1] })], [0, 0.5, 1]), [0.1, 0.55, 1]);
    close(applyNodes([node({ gamma: [0, 0, 0, 1] })], [0.25, 0.25, 0.25]), [0.5, 0.5, 0.5]);
    close(applyNodes([node({ offset: [0.1, 0, -0.1] })], [0.5, 0.5, 0.5]), [0.6, 0.5, 0.4]);
    close(applyNodes([node({ gain: [0.2, 0, 0, 0] })], [0.5, 0.5, 0.5]), [0.6, 0.5, 0.5]);
  });
  it("contrast pivots, saturation 0 is grey", () => {
    close(applyNodes([node({ contrast: 2, pivot: 0.5 })], [0.5, 0.25, 0.75]), [0.5, 0, 1]);
    const g = applyNodes([node({ sat: 0 })], [0.9, 0.2, 0.1]);
    expect(g[0]).toBeCloseTo(g[1], 9);
    expect(g[1]).toBeCloseTo(g[2], 9);
    expect(g[0]).toBeCloseTo(0.2126 * 0.9 + 0.7152 * 0.2 + 0.0722 * 0.1, 9);
  });
  it("mix blends, switched-off nodes do nothing, serial nodes chain", () => {
    close(applyNodes([node({ gain: [0, 0, 0, 1], mix: 0.5 })], [0.2, 0.2, 0.2]), [0.3, 0.3, 0.3]);
    close(applyNodes([node({ gain: [0, 0, 0, 1], on: false })], [0.2, 0.2, 0.2]), [0.2, 0.2, 0.2]);
    close(applyNodes([node({ gain: [0, 0, 0, 1] }), node({ id: "n2", offset: [0.1, 0.1, 0.1] })], [0.2, 0.2, 0.2]), [0.5, 0.5, 0.5]);
  });
  it("a layer node grades the original picture and blends over the result", () => {
    const serial = node({ gain: [0, 0, 0, 1] }); // 0.2 -> 0.4
    const layer = node({ id: "n2", kind: "layer", blend: "multiply" }); // original 0.2 multiplied onto 0.4
    close(applyNodes([serial, layer], [0.2, 0.2, 0.2]), [0.08, 0.08, 0.08]);
    const normal = node({ id: "n3", kind: "layer", mix: 0.5 }); // half of the original back
    close(applyNodes([serial, normal], [0.2, 0.2, 0.2]), [0.3, 0.3, 0.3]);
  });
  it("custom curves run master first, then the channel", () => {
    const n = node({ curves: { m: [[0, 0], [1, 0.5]], r: [[0, 0.2], [1, 1]] } });
    const out = applyNodes([n], [1, 1, 1]);
    expect(out[1]).toBeCloseTo(0.5, 6);
    expect(out[0]).toBeCloseTo(0.2 + 0.8 * 0.5, 6);
  });
  it("hue vs saturation lowers only the chosen hue", () => {
    const n = node({ curves: { hs: [[0, 0], [0.1, 0.5], [0.9, 0.5], [1, 0]] } }); // reds to grey
    const red = applyNodes([n], [0.9, 0.1, 0.1]);
    expect(Math.max(...red) - Math.min(...red)).toBeLessThan(1e-6);
    close(applyNodes([n], [0.1, 0.9, 0.1]), [0.1, 0.9, 0.1], 1e-6);
  });
  it("a LUT is applied through its mix", () => {
    const size = 2;
    const data = new Float32Array(size ** 3 * 3).fill(0.25);
    const lut: Lut3D = { size, data };
    close(lutLookup(lut, [0.3, 0.7, 0.1]), [0.25, 0.25, 0.25]);
    const n = node({ lut: { id: "x", mix: 0.5 } });
    close(applyNodes([n], [0.75, 0.75, 0.75], { luts: () => lut }), [0.5, 0.5, 0.5]);
    close(applyNodes([n], [0.75, 0.75, 0.75], { luts: () => null }), [0.75, 0.75, 0.75]);
  });
});

describe("keys", () => {
  it("the qualifier selects its hue and nothing else", () => {
    const q = { on: true, h: 0, hw: 0.05, hs: 0.02, s0: 0.3, s1: 1, ss: 0.05, l0: 0, l1: 1, ls: 0.05, invert: false };
    expect(qualKey(q, [0.9, 0.1, 0.1])).toBeCloseTo(1, 6);
    expect(qualKey(q, [0.1, 0.9, 0.1])).toBeCloseTo(0, 6);
    expect(qualKey(q, [0.5, 0.5, 0.5])).toBeCloseTo(0, 6); // grey: below the saturation range
    expect(qualKey({ ...q, invert: true }, [0.9, 0.1, 0.1])).toBeCloseTo(0, 6);
    expect(qualKey(undefined, [0.3, 0.2, 0.1])).toBe(1);
    expect(qualKey({ ...q, hw: 0.5, s0: 0, l0: 0.7 }, [0.9, 0.9, 0.9])).toBeCloseTo(1, 6); // highlights only
    expect(qualKey({ ...q, hw: 0.5, s0: 0, l0: 0.7 }, [0.2, 0.2, 0.2])).toBeCloseTo(0, 6);
  });
  it("windows: inside is 1, outside 0, the edge is half, invert flips", () => {
    const w = { shape: "ellipse" as const, x: 0.5, y: 0.5, w: 0.4, h: 0.4, rot: 0, soft: 0.2, invert: false };
    expect(winKey(w, 0.5, 0.5, 1)).toBeCloseTo(1, 6);
    expect(winKey(w, 0.95, 0.5, 1)).toBeCloseTo(0, 6);
    expect(winKey(w, 0.7, 0.5, 1)).toBeCloseTo(0.5, 6);
    expect(winKey({ ...w, invert: true }, 0.5, 0.5, 1)).toBeCloseTo(0, 6);
    const r = { ...w, shape: "rect" as const };
    expect(winKey(r, 0.69, 0.69, 1)).toBeGreaterThan(0.2);
    expect(winKey(r, 0.5, 0.75, 1)).toBeCloseTo(0, 6);
    // Width and height are fractions of the picture, whatever its shape: the edge sits at the same fractions.
    expect(winKey(w, 0.5, 0.7, 2)).toBeCloseTo(0.5, 6);
    expect(winKey(w, 0.7, 0.5, 2)).toBeCloseTo(0.5, 6);
    // Rotation happens in true picture proportions: a quarter turn swaps the reach on a 2:1 picture.
    expect(winKey({ ...w, rot: 90 }, 0.5 + 0.2 / 2, 0.5, 2)).toBeCloseTo(0.5, 6);
    const g = { ...w, shape: "gradient" as const, y: 0.5, w: 0.2 };
    expect(winKey(g, 0.5, 0.1, 1)).toBeCloseTo(1, 6);
    expect(winKey(g, 0.5, 0.9, 1)).toBeCloseTo(0, 6);
    expect(winKey(g, 0.5, 0.5, 1)).toBeCloseTo(0.5, 6);
    expect(winKey({ ...r, rot: 90, w: 0.8, h: 0.1 }, 0.5, 0.8, 1)).toBeCloseTo(1, 6); // rotated: long side now vertical
  });
  it("window keys interpolate and hold at the ends", () => {
    const w = { shape: "ellipse" as const, x: 0, y: 0, w: 0.2, h: 0.2, rot: 0, soft: 0.1, invert: false, keys: [{ t: 1, x: 0.2, y: 0.4, w: 0.2, h: 0.2, rot: 0 }, { t: 3, x: 0.6, y: 0.8, w: 0.4, h: 0.2, rot: 90 }] };
    expect(windowAt(w, 0).x).toBeCloseTo(0.2, 9);
    expect(windowAt(w, 2).x).toBeCloseTo(0.4, 9);
    expect(windowAt(w, 2).rot).toBeCloseTo(45, 9);
    expect(windowAt(w, 9).y).toBeCloseTo(0.8, 9);
    const res = resolveNodes([node({ win: w }), node({ id: "off", on: false })], 2)!;
    expect(res.length).toBe(1);
    expect(res[0]!.win!.keys).toBeUndefined();
    expect(res[0]!.win!.w).toBeCloseTo(0.3, 9);
    expect(resolveNodes([], 0)).toBeUndefined();
    expect(resolveNodes([node({ on: false })], 0)).toBeUndefined();
  });
  it("a window limits where the node works", () => {
    const n = node({ gain: [0, 0, 0, 1], win: { shape: "rect", x: 0.25, y: 0.5, w: 0.3, h: 0.6, rot: 0, soft: 0.05, invert: false } });
    close(applyNodes([n], [0.2, 0.2, 0.2], { u: 0.25, v: 0.5, aspect: 1 }), [0.4, 0.4, 0.4]);
    close(applyNodes([n], [0.2, 0.2, 0.2], { u: 0.8, v: 0.5, aspect: 1 }), [0.2, 0.2, 0.2]);
  });
});

describe("sanitizing and baking", () => {
  it("fills, clamps and drops rubbish", () => {
    const n = sanitizeNode({ id: "a", mix: 9, sat: -4, lift: [NaN, 2, "x"] as unknown as [number, number, number, number], kind: "weird" as never, curves: { m: [[0, 0], [1, 1]], r: [[0, 0.3], [1, 1]] }, lut: { id: "", mix: 2 }, qual: { on: true, h: 1.25, hw: 2, hs: 0, s0: 0.8, s1: 0.2, ss: 0, l0: 0, l1: 1, ls: 0, invert: false } });
    expect(n.mix).toBe(1);
    expect(n.sat).toBe(0);
    expect(n.lift).toEqual([0, 1, 0, 0]);
    expect(n.kind).toBe("serial");
    expect(n.curves).toEqual({ r: [[0, 0.3], [1, 1]] });
    expect(n.lut).toBeUndefined();
    expect(n.qual!.h).toBeCloseTo(0.25, 9);
    expect(n.qual!.hw).toBe(0.5);
    expect(n.qual!.s1).toBe(0.8);
    expect(JSON.parse(JSON.stringify(sanitizeNode(n)))).toEqual(JSON.parse(JSON.stringify(n))); // stable
  });
  it("a baked LUT reproduces the nodes", () => {
    const nodes = [node({ gain: [0.1, 0, -0.1, 0.1], gamma: [0, 0, 0, 0.2], sat: 1.2, curves: { m: [[0, 0.02], [0.5, 0.55], [1, 0.98]] } })];
    const lut = bakeNodesLut(nodes, 17);
    expect(lut.data.length).toBe(17 ** 3 * 3);
    for (const c of [[0, 0, 0], [0.25, 0.5, 0.75], [1, 1, 1], [0.5, 0.5, 0.5]] as [number, number, number][]) {
      const want = applyNodes(nodes, c).map((v) => Math.max(0, Math.min(1, v)));
      close(lutLookup(lut, c), want, 1e-6); // these colours sit exactly on the 17-point grid
    }
    close(lutLookup(lut, [0.33, 0.61, 0.2]), applyNodes(nodes, [0.33, 0.61, 0.2]), 0.01);
  });
  it("the basic grade matches its formulas", () => {
    const base = { exposure: 0, contrast: 1, saturation: 1, vibrance: 0, temperature: 0, tint: 0, hue: 0, lift: 0, gamma: 1, gain: 1 };
    close(gradeBasic([0.3, 0.5, 0.7], base), [0.3, 0.5, 0.7], 1e-9);
    close(gradeBasic([0.2, 0.2, 0.2], { ...base, exposure: 1 }), [0.4, 0.4, 0.4], 1e-9);
    close(gradeBasic([0.5, 0.25, 0.75], { ...base, contrast: 2 }), [0.5, 0, 1], 1e-9);
    close(gradeBasic([0.5, 0.5, 0.5], { ...base, temperature: 1 }), [0.56, 0.5, 0.44], 1e-9);
  });
});
