import { describe, expect, it } from "vitest";
import { newScene } from "../src/core/model";
import * as rig from "../src/anim/rig";

function scene() {
  const s = newScene("t", 1080, 1920, 24);
  const r = rig.buildRig(s, 540, 1000, 1000);
  return { s, r };
}

describe("rig", () => {
  it("builds 15 parented parts with pivots", () => {
    const { s, r } = scene();
    expect(Object.keys(r.parts)).toHaveLength(15);
    const forearm = s.layers.find((l) => l.id === r.parts.foreArmF)!;
    expect(forearm.parent).toBe(r.parts.upperArmF);
    expect(forearm.pivot).toBeDefined();
  });

  it("rotating the shoulder moves the hand (forward kinematics)", () => {
    const { s, r } = scene();
    const before = rig.skeleton(s, r.rig, 0).find((j) => j.part === "handF")!.pivot;
    rig.applyPose(s, r.rig, 0, { upperArmF: -90 });
    const after = rig.skeleton(s, r.rig, 0).find((j) => j.part === "handF")!.pivot;
    expect(after[0]).toBeGreaterThan(before[0] + 200); // swung forward (+x)
    expect(after[1]).toBeLessThan(before[1] - 200); // and up
  });

  it("IK puts the wrist on the target", () => {
    const { s, r } = scene();
    const sh = rig.skeleton(s, r.rig, 0).find((j) => j.part === "upperArmF")!.pivot;
    const target = { x: sh[0] + 180, y: sh[1] + 120 };
    expect(rig.solveIK(s, r.rig, "armF", target.x, target.y, 0)).toBe(true);
    const wrist = rig.skeleton(s, r.rig, 0).find((j) => j.part === "handF")!.pivot;
    expect(Math.hypot(wrist[0] - target.x, wrist[1] - target.y)).toBeLessThan(2);
  });

  it("IK on a leg bends the knee forward", () => {
    const { s, r } = scene();
    const hip = rig.skeleton(s, r.rig, 0).find((j) => j.part === "thighF")!.pivot;
    rig.solveIK(s, r.rig, "legF", hip[0], hip[1] + 300, 0);
    const knee = rig.skeleton(s, r.rig, 0).find((j) => j.part === "shinF")!.pivot;
    expect(knee[0]).toBeGreaterThan(hip[0]);
  });

  it("walk cycle keys every joint and extends the scene", () => {
    const { s, r } = scene();
    const m = rig.motion(s, r.rig, "walk", { cycles: 2, travel: 300 });
    expect(m.keys).toBeGreaterThan(200);
    expect(s.frames).toBeGreaterThanOrEqual(m.endFrame);
    const a = rig.readPose(s, r.rig, 0).thighF!;
    const b = rig.readPose(s, r.rig, Math.round(s.fps / 2)).thighF!;
    expect(Math.sign(a)).not.toBe(Math.sign(b)); // legs swap
    expect(rig.readPose(s, r.rig, m.endFrame).rootX).toBeCloseTo(600, 0);
  });

  it("every motion runs", () => {
    for (const t of rig.MOTIONS) {
      const { s, r } = scene();
      expect(rig.motion(s, r.rig, t).keys).toBeGreaterThan(0);
    }
  });
});
