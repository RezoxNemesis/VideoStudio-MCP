import { describe, expect, it } from "vitest";
import { lockFoot } from "../src/stage/engine/shotkit.js";

// A foot on the ground stays where it was set down (16 Nov 2026: the Movement Study's judge found 70 feet skating
// 15–75 cm — where a walk began from a stand, where a movement was turned to a new facing, where a clip's speed did not
// match the ground it covered). Each frame, played in order, a planted foot is held at the spot where it came down;
// when the body has gone too far for the leg to reach, or the capture lifts the foot, it leaves that spot as a STEP
// (lifted, carried, set down), never sliding along the ground.
type Lock = { x: number; z: number; y: number; rel?: number; from?: [number, number, number] } | null;
const step = (lock: Lock, foot: [number, number, number], down: boolean, t: number, o = {}) =>
  lockFoot(lock, { foot, down, t, reach: 0.5, ...o }) as { lock: Lock; p: [number, number, number] | null; air: boolean };

describe("a planted foot is held where it came down", () => {
  it("is held on its spot while the pose under it drifts", () => {
    let L: Lock = null, r = step(L, [1, 0.08, 2], true, 0);
    L = r.lock;
    expect(r.p, "the first frame of a plant is the pose itself").toBeNull();
    r = step(L, [1.2, 0.08, 2.1], true, 1 / 30);       // the pose has carried the foot 22 cm on the ground
    expect(r.p).not.toBeNull();
    expect(Math.hypot(r.p![0] - 1, r.p![2] - 2), "it stays where it was set down").toBeLessThan(1e-6);
    expect(r.p![1], "at the pose's own height (the heel may rise as the step rolls off it)").toBeCloseTo(0.08);
  });
  it("a foot in the air is let be", () => {
    const r = step(null, [1, 0.25, 2], false, 0);
    expect(r.p).toBeNull();
    expect(r.lock).toBeNull();
  });
  it("leaves its spot as a step when the capture lifts it: lifted, carried, set down — never along the ground", () => {
    let L: Lock = { x: 1, z: 2, y: 0.08 };
    const path: [number, number, number][] = [];
    let t = 1;
    let r = step(L, [1.4, 0.08, 2], false, t);         // the pose wants it 40 cm on, and the capture says it is lifted
    for (let k = 0; k < 20 && r.lock; k++) { path.push(r.p!); L = r.lock; t += 1 / 30; r = step(L, [1.4, 0.08, 2], false, t); }
    expect(path.length, "it takes a few frames, not one").toBeGreaterThan(3);
    const mid = path[Math.floor(path.length / 2)]!;
    expect(mid[1] - 0.08, "lifted on the way").toBeGreaterThan(0.03);
    let along = 0; for (const p of path) if (p[1] - 0.08 < 0.01 && Math.hypot(p[0] - 1, p[2] - 2) > 0.05 && Math.hypot(p[0] - 1.4, p[2] - 2) > 0.05) along++;
    expect(along, "no frame moves it along the ground").toBe(0);
    expect(r.lock, "and when it has arrived the pose has it again").toBeNull();
  });
  it("when the body has gone beyond the leg's reach the foot steps after it", () => {
    const r = step({ x: 0, z: 0, y: 0.08 }, [0.7, 0.08, 0], true, 2);
    expect(r.lock?.rel, "it starts a step").toBeDefined();
    expect(r.air).toBe(true);
  });
  it("a foot set down again at the end of its step is planted where it landed", () => {
    let L: Lock = { x: 0, z: 0, y: 0.08 };
    let t = 0, r = step(L, [0.3, 0.08, 0], false, t);
    while (r.lock?.rel !== undefined) { L = r.lock; t += 1 / 30; r = step(L, [0.3, 0.08, 0], true, t); }
    L = r.lock; t += 1 / 30;
    r = step(L, [0.3, 0.08, 0], true, t);
    L = r.lock;
    r = step(L, [0.45, 0.08, 0], true, t + 1 / 30);
    expect(Math.hypot(r.p![0] - 0.3, r.p![2]), "held where it landed").toBeLessThan(0.01);
  });
  it("a foot that the pose would put down somewhere else from one frame to the next steps there instead of jumping", () => {
    // (measured: a dancer ended her dance with a foot raised; the stand that followed put it down 42 cm away in one frame)
    const o = { last: [-1.9, 0.21, 4.44] as [number, number, number] };
    let r = step(null, [-2.0, 0.07, 4.02], true, 0, o);
    expect(r.air, "it is a step").toBe(true);
    expect(Math.hypot(r.p![0] + 1.9, r.p![2] - 4.44), "beginning where the foot was").toBeLessThan(0.01);
    let L = r.lock, t = 0, n = 0;
    while (L?.rel !== undefined && n < 20) { t += 1 / 30; r = step(L, [-2.0, 0.07, 4.02], true, t); L = r.lock; n++; }
    expect(n, "over several frames").toBeGreaterThan(3);
    expect(L && Math.hypot(L.x + 2.0, L.z - 4.02), "and it stands where the pose wanted it").toBeLessThan(0.01);
    expect(step(null, [-2.0, 0.07, 4.02], true, 0, { last: [-2.02, 0.07, 4.03] }).air, "a small move is no step").toBe(false);
  });
  it("a held foot steps when told to (its legs would cross: the body has turned on it)", () => {
    const r = step({ x: 0, z: 0, y: 0.08 }, [0.2, 0.08, 0], true, 3, { force: true });
    expect(r.air).toBe(true);
    expect(r.lock?.rel).toBe(3);
  });
});
