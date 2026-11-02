import { describe, expect, it } from "vitest";
import * as T3 from "../src/anim/three3d";

const char = (): T3.Scene3D => {
  const sp = T3.newScene3D();
  sp.objects.push({ id: "hero", type: "character", color: "#fff", material: "toon", pos: [0, 0, 0], rot: [0, 0, 0], scale: 1, char: { ...T3.DEFAULT_CHAR3D } });
  return sp;
};

describe("3D studio", () => {
  it("walks forward along the facing direction and returns to the starting pose", () => {
    const sp = char();
    const end = T3.act3D(sp, "hero", 24, [{ at: 0, do: "walk", dist: 2.8 }]);
    expect(T3.kv(sp, "hero.pz", Math.round(2 * 24), 0)).toBeCloseTo(2.8, 1);
    expect(T3.kv(sp, "hero.px", Math.round(2 * 24), 0)).toBeCloseTo(0, 3);
    const rest = T3.POSES3D.rest!.shoulderL!;
    expect(T3.jointAt(sp, "hero", "shoulderL", Math.round(end * 24))[2]).toBeCloseTo(rest[2], 3);
  });
  it("blends into named poses and keys expressions", () => {
    const sp = char();
    T3.act3D(sp, "hero", 24, [{ at: 0, do: "cheer", dur: 0.5 }, { at: 1, do: "expr", name: "smile" }]);
    expect(T3.jointAt(sp, "hero", "shoulderL", 12)[2]).toBeCloseTo(160, 3);
    expect(T3.kv(sp, "hero.expr", 30, 0)).toBe(1);
  });
  it("orbits the camera around its target at a constant distance", () => {
    const sp = char();
    T3.camMove(sp, 24, { move: "orbit", start: 0, dur: 2, amount: 90 });
    const d = (f: number) => Math.hypot(T3.kv(sp, "cam.px", f, 0) - sp.camera.target[0], T3.kv(sp, "cam.pz", f, 0) - sp.camera.target[2]);
    expect(d(0)).toBeCloseTo(d(48), 3);
    expect(T3.kv(sp, "cam.px", 48, 0)).toBeGreaterThan(7);
  });
});
