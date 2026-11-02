import { describe, expect, it } from "vitest";
import { stripIcc } from "../src/render/snapshot";
import { activeRegions, pullPush, removeRegion, type RemoveRegion } from "../src/render/inpaint";

describe("snapshots", () => {
  it("drops the ICC profile and keeps a valid JPEG", () => {
    // SOI, APP0 (JFIF, 16 bytes), APP2 (ICC, 600 bytes), SOS + data, EOI.
    const app0 = [0xff, 0xe0, 0, 16, ...new Array(14).fill(1)];
    const app2 = [0xff, 0xe2, 600 >> 8, 600 & 255, ...new Array(598).fill(7)];
    const sos = [0xff, 0xda, 0, 4, 9, 9, 1, 2, 3];
    const b = new Uint8Array([0xff, 0xd8, ...app0, ...app2, ...sos, 0xff, 0xd9]);
    const o = stripIcc(b);
    expect(o.length).toBe(b.length - 602);
    expect([...o.slice(2, 4)]).toEqual([0xff, 0xe0]);
    expect(o[0]).toBe(0xff);
    expect(o[1]).toBe(0xd8);
    expect(o[o.length - 2]).toBe(0xff);
    expect(o[o.length - 1]).toBe(0xd9);
  });
});

describe("object removal", () => {
  const frame = (W: number, H: number) => {
    const d = new Uint8ClampedArray(W * H * 4);
    for (let y = 0; y < H; y++)
      for (let x = 0; x < W; x++) {
        const i = (y * W + x) * 4;
        d[i] = 40 + x; // horizontal gradient
        d[i + 1] = 80;
        d[i + 2] = 120 + (y % 2) * 4;
        d[i + 3] = 255;
      }
    return { data: d, width: W, height: H } as unknown as ImageData;
  };
  it("pull-push fills holes smoothly from the edges", () => {
    const rgb = new Float32Array(9 * 3).fill(0);
    const w = new Float32Array(9).fill(1);
    for (let i = 0; i < 9; i++) rgb[i * 3] = 100;
    w[4] = 0;
    rgb[12] = 0;
    pullPush(rgb, w, 3, 3);
    expect(rgb[12]).toBeCloseTo(100, 0);
  });
  for (const mode of ["fill", "clone", "auto"] as const)
    it(`${mode} removes a white logo and blends with the background`, () => {
      const img = frame(160, 120);
      // White "logo" block.
      for (let y = 50; y < 66; y++) for (let x = 60; x < 100; x++) for (let c = 0; c < 3; c++) img.data[(y * 160 + x) * 4 + c] = 255;
      const r: RemoveRegion = { id: "a", x: 80 / 160, y: 58 / 120, w: 46 / 160, h: 22 / 120, shape: "rect", mode, feather: 0.1 };
      removeRegion(img, r);
      const px = (x: number, y: number) => img.data[(y * 160 + x) * 4]!;
      expect(Math.abs(px(80, 58) - (40 + 80))).toBeLessThan(14);
      expect(img.data[(58 * 160 + 80) * 4 + 1]).toBeLessThan(110);
    });
  it("keeps regions to their time range and follows keyed positions", () => {
    const r: RemoveRegion = { id: "a", x: 0.1, y: 0.1, w: 0.1, h: 0.1, shape: "rect", mode: "auto", feather: 0.1, from: 1, to: 3, xKeys: [{ t: 1, v: 0.2, ease: "linear" }, { t: 3, v: 0.6, ease: "linear" }] };
    expect(activeRegions([r], 0.5)).toHaveLength(0);
    expect(activeRegions([r], 2)[0]!.x).toBeCloseTo(0.4, 3);
  });
});

describe("recolor", () => {
  it("changes colour but keeps the shading order", () => {
    const W = 20, H = 10;
    const d = new Uint8ClampedArray(W * H * 4);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const i = (y * W + x) * 4; const v = 40 + x * 5; d[i] = v; d[i + 1] = v + 5; d[i + 2] = v; d[i + 3] = 255; }
    const img = { width: W, height: H, data: d } as unknown as ImageData;
    removeRegion(img, { id: "r", x: 0.5, y: 0.5, w: 1, h: 1, shape: "rect", mode: "recolor", color: "#2050d0", feather: 0 });
    const px = (x: number) => [d[(5 * W + x) * 4]!, d[(5 * W + x) * 4 + 1]!, d[(5 * W + x) * 4 + 2]!];
    const a = px(3), b = px(16);
    expect(a[2]!).toBeGreaterThan(a[0]!);
    expect(b[2]!).toBeGreaterThan(a[2]!);
  });
});

import { squareToQuad } from "../src/render/compositor";
describe("corner pin", () => {
  it("maps unit square corners onto the quad", () => {
    const P: [number, number][] = [[100, 50], [400, 80], [380, 300], [120, 260]];
    const m = squareToQuad(P[0]!, P[1]!, P[2]!, P[3]!);
    const ap = (u: number, v: number) => { const X = m[0]! * u + m[3]! * v + m[6]!, Y = m[1]! * u + m[4]! * v + m[7]!, W = m[2]! * u + m[5]! * v + m[8]!; return [X / W, Y / W]; };
    ([[0, 0], [1, 0], [1, 1], [0, 1]] as const).forEach(([u, v], i) => { const r = ap(u, v); expect(r[0]).toBeCloseTo(P[i]![0], 3); expect(r[1]).toBeCloseTo(P[i]![1], 3); });
  });
});
