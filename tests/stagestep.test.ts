import { describe, expect, it } from "vitest";
import { plantedJoin, stepFoot, landingFeet } from "../src/stage/engine/shotkit.js";

// Two captured actions joined on feet that stand: the blend used to drag a planted foot across the ground (a runner
// who pivoted from a pick-up into a start skated a foot 19 cm). A foot that has to move becomes a STEP: lifted, carried,
// set down — as a person does.
describe("a join on standing feet steps instead of skating", () => {
  it("is a stepping join only when both feet stand before and after it", () => {
    expect(plantedJoin(3, 3)).toBe(true);
    expect(plantedJoin(3, 1)).toBe(false);   // the next action begins with a foot in the air: it flows, the blend is right
    expect(plantedJoin(2, 3)).toBe(false);   // the action before ends in mid-stride
    expect(plantedJoin(0, 0)).toBe(false);
  });
  it("a foot that was in the air when the action before ended is landing through the join, not skating", () => {
    // (measured: a boy rising from a pick-up had his left foot 6 cm up, mid-step, when the next action — whose own
    // marks say both feet stand — took over; the foot came down over the blend and was reported as a 19 cm skate)
    expect(landingFeet(2, 3, 0.12, 0.06)).toBe(1);     // right stood, the left was 6 cm higher: the left is landing
    expect(landingFeet(1, 3, 0.06, 0.13)).toBe(2);
    expect(landingFeet(3, 3, 0.06, 0.06)).toBe(0);     // both stood: nothing lands (they step, if they must move)
    expect(landingFeet(3, 1, 0.06, 0.06)).toBe(0);     // a foot that leaves the ground is not landing
    // the marks alone are not believed: a foot marked "in the air" that is as low as the standing one is on the ground,
    // and if it moves it is a skate the judge must still see
    expect(landingFeet(2, 3, 0.065, 0.06)).toBe(0);
    expect(landingFeet(0, 3, 0.2, 0.2)).toBe(0);       // (no standing foot to measure against)
  });
  const from: [number, number] = [0, 0], to: [number, number, number] = [0.19, 0.08, 0.05];
  type Step = { p: [number, number, number]; air: boolean; done: boolean } | null;
  const at = (local: number, k = 0, o = {}) => stepFoot(from, to, local, k, o) as Step;
  it("a foot that has 19 cm to go stands, lifts, is carried and lands where the new action has it", () => {
    const a = at(0)!, m = at(0.12)!, z = at(0.3)!;
    expect(a.p[0]).toBeCloseTo(0, 6); expect(a.p[2]).toBeCloseTo(0, 6); expect(a.air).toBe(false);
    expect(m.air).toBe(true);
    expect(m.p[1]).toBeGreaterThan(0.08 + 0.05);          // lifted clear of the ground …
    expect(m.p[1]).toBeLessThan(0.08 + 0.1);              // … but a step, not a jump
    expect(m.p[0]).toBeGreaterThan(0.05); expect(m.p[0]).toBeLessThan(0.14);
    expect(z.p).toEqual([0.19, 0.08, 0.05]); expect(z.air).toBe(false); expect(z.done).toBe(true);
  });
  it("it never goes under the ground and never jumps: every frame is near the one before", () => {
    let prev = at(0)!.p;
    for (let t = 1 / 60; t <= 0.3; t += 1 / 60) {
      const p = at(t)!.p;
      expect(p[1]).toBeGreaterThanOrEqual(0.08 - 1e-9);
      expect(Math.hypot(p[0] - prev[0], p[1] - prev[1], p[2] - prev[2])).toBeLessThan(0.04);
      prev = p;
    }
  });
  it("the second foot waits for the first", () => {
    expect(at(0.1, 1)!.air).toBe(false);
    expect(at(0.1, 1)!.p[0]).toBeCloseTo(0, 6);
    expect(at(0.32, 1)!.air).toBe(true);
    expect(at(0.5, 1)!.done).toBe(true);
  });
  it("a foot that hardly has to move is left to the blend", () => {
    expect(stepFoot([0, 0], [0.03, 0.08, 0.02], 0.1, 0, {})).toBeNull();           // under 4 cm: never a step
    expect(stepFoot([0, 0], [0.06, 0.08, 0], 0.1, 0, { min: 0.08 })).toBeNull();    // a join of two captures: under 8 cm
    expect(stepFoot([0, 0], [0.06, 0.08, 0], 0.1, 0, {})).not.toBeNull();           // into a stance it steps from 4 cm, as before
  });
});
