import { describe, expect, it } from "vitest";
import { keyValue } from "../src/stage/rig";

// A hand that travels through key poses must not stop at each one (eased keys made every pose a dead stop).
describe("spline keys", () => {
  const tr = { on: "hand.R", keys: [[0, [0, 0, 0]], [1, [1, 0, 0], "spline"], [2, [2, 1, 0], "spline"], [3, [2, 1, 0], "spline"]] as [number, [number, number, number], string?][] };
  const at = (t: number) => keyValue(tr, t)!;
  it("passes through every key", () => {
    expect(at(1)[0]).toBeCloseTo(1, 6);
    expect(at(2)[1]).toBeCloseTo(1, 6);
  });
  it("keeps moving through an interior key", () => {
    const v = (at(1.02)[0] - at(0.98)[0]) / 0.04;
    expect(v).toBeGreaterThan(0.6);
  });
  it("starts and ends at rest, and holds where a key repeats", () => {
    expect(Math.abs(at(0.02)[0] - at(0)[0]) / 0.02).toBeLessThan(0.2);
    expect(at(2.5)).toEqual([2, 1, 0]);
  });
  it("never overshoots past a key", () => {
    for (let t = 0; t <= 3; t += 0.01) { expect(at(t)[0]).toBeLessThanOrEqual(2 + 1e-9); expect(at(t)[1]).toBeLessThanOrEqual(1 + 1e-9); }
  });
  it("eased keys still stop at each key (the old way)", () => {
    const io = { on: "hand.R", keys: [[0, [0, 0, 0]], [1, [1, 0, 0]], [2, [2, 0, 0]]] as [number, [number, number, number], string?][] };
    const v = (keyValue(io, 1.02)![0] - keyValue(io, 0.98)![0]) / 0.04;
    expect(v).toBeLessThan(0.1);
  });
});
