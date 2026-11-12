import { describe, expect, it } from "vitest";
import { compileFight, FIGHT_MOVES } from "../src/stage/choreo";
import { evalStance, keyValue, strikeWeight } from "../src/stage/rig";
import type { StageActor, StanceBlock } from "../src/stage/spec";

const stance = (o: Partial<StanceBlock> = {}): StanceBlock => ({ id: "b", kind: "stance", t: 0, dur: 6, ...o }) as StanceBlock;

describe("stance rig", () => {
  it("stands with the lead foot forward", () => {
    const s = evalStance(stance({ lead: "L" }), 1, 1, 1);
    expect(s.feet.L.forward).toBeGreaterThan(s.feet.R.forward);
    expect(s.feet.L.right).toBeLessThan(s.feet.R.right);
    expect(s.feet.L.lift).toBe(0);
    expect(s.feet.R.lift).toBe(0);
    const r = evalStance(stance({ lead: "R" }), 1, 1, 1);
    expect(r.feet.R.forward).toBeGreaterThan(r.feet.L.forward);
    const sq = evalStance(stance({ lead: "none" }), 1, 1, 1);
    expect(Math.abs(sq.feet.L.forward - sq.feet.R.forward)).toBeLessThan(1e-6);
  });
  it("steps along a path: feet lift one at a time and land where the path says", () => {
    // the path's time is when the step has arrived: the feet move between 0 s and 1 s
    const b = stance({ lead: "L", path: [[1, 0, 0.6]] });
    const before = evalStance(b, 0, 1, 0), after = evalStance(b, 3, 1, 3);
    expect(after.forward - before.forward).toBeCloseTo(0.6, 2);
    expect(after.feet.L.forward - before.feet.L.forward).toBeCloseTo(0.6, 2);
    expect(after.feet.R.forward - before.feet.R.forward).toBeCloseTo(0.6, 2);
    let both = 0, lifted = 0;
    for (let t = 0; t < 3; t += 0.02) {
      const s = evalStance(b, t, 1, t);
      if (s.feet.L.lift > 0.005 && s.feet.R.lift > 0.005) both++;
      if (s.feet.L.lift > 0.005 || s.feet.R.lift > 0.005) lifted++;
    }
    expect(lifted).toBeGreaterThan(5);
    expect(both).toBe(0); // never both feet in the air
    // a planted foot does not move
    let slide = 0;
    let prev = evalStance(b, 0, 1, 0);
    for (let t = 0.02; t < 3; t += 0.02) {
      const s = evalStance(b, t, 1, t);
      for (const f of ["L", "R"] as const) if (s.feet[f].lift === 0 && prev.feet[f].lift === 0) slide = Math.max(slide, Math.hypot(s.feet[f].forward - prev.feet[f].forward, s.feet[f].right - prev.feet[f].right));
      prev = s;
    }
    expect(slide).toBeLessThan(1e-6);
  });
  it("lunges: the hips travel forward and come back", () => {
    const b = stance({ lead: "L", lunges: [[2, 0.4]] });
    const rest = evalStance(b, 0.5, 1, 0.5).hip.forward, deep = evalStance(b, 2, 1, 2).hip.forward, back = evalStance(b, 3.5, 1, 3.5).hip.forward;
    expect(deep - rest).toBeGreaterThan(0.15);
    expect(Math.abs(back - rest)).toBeLessThan(0.03);
  });
  it("ducks lower the hips", () => {
    const b = stance({ ducks: [[2, 0.3]] as never });
    expect(evalStance(b, 2, 1, 2).hip.up).toBeLessThan(evalStance(b, 0.5, 1, 0.5).hip.up - 0.1);
  });
});

describe("strikes and keys", () => {
  it("snaps to the apex, holds, then lets go", () => {
    expect(strikeWeight(0, 0, 0.3, 0.05, 0.8)).toBe(0);
    expect(strikeWeight(0.15, 0, 0.3, 0.05, 0.8)).toBeLessThan(0.3); // late acceleration: still slow at half time
    expect(strikeWeight(0.3, 0, 0.3, 0.05, 0.8)).toBe(1);
    expect(strikeWeight(0.34, 0, 0.3, 0.05, 0.8)).toBe(1);
    expect(strikeWeight(0.6, 0, 0.3, 0.05, 0.8)).toBeGreaterThan(0);
    expect(strikeWeight(0.6, 0, 0.3, 0.05, 0.8)).toBeLessThan(1);
    expect(strikeWeight(0.8, 0, 0.3, 0.05, 0.8)).toBe(0);
  });
  it("interpolates key tracks with eases and clamps at the ends", () => {
    const tr = { on: "hand.R", keys: [[0, [0, 1, 0]], [1, [1, 1, 0], "linear"], [2, [1, 2, 0], "hold"]] as [number, [number, number, number], string?][] };
    expect(keyValue(tr, -1)).toEqual([0, 1, 0]);
    expect(keyValue(tr, 0.5)![0]).toBeCloseTo(0.5, 6);
    expect(keyValue(tr, 1.5)![1]).toBe(1); // "hold" keeps the old value until the key
    expect(keyValue(tr, 5)).toEqual([1, 2, 0]);
    expect(keyValue({ on: "hips", keys: [] }, 1)).toBeNull();
  });
});

describe("fight compiler", () => {
  const actor = (id: string): StageActor => ({ id, char: {} as never, at: { pos: [0, 0, 0], yaw: 0 }, blocks: [], overlays: [], lines: [] });
  it("turns beats into stances, strikes, reactions and cuts", () => {
    const plan = compileFight({
      a: actor("hero"), b: actor("rival"), t: 2, style: "fists",
      beats: [{ do: "jab", by: "a", hit: false, defend: "slip" }, { do: "cross", by: "a", hit: true }, { do: "hook", by: "b", hit: false, defend: "duck" }, { do: "highkick", by: "a", hit: true, ko: true }],
      posA: { pos: [0, 0, 0], yaw: 0 }, posB: { pos: [0, 0, 1.2], yaw: 180 }, impactFrames: true, cameras: true,
    });
    expect(plan.beats).toHaveLength(4);
    expect(plan.beats.map((b) => b.do)).toEqual(["jab", "cross", "hook", "highkick"]);
    for (let i = 1; i < plan.beats.length; i++) expect(plan.beats[i]!.t).toBeGreaterThan(plan.beats[i - 1]!.t);
    expect(plan.end).toBeGreaterThan(plan.beats[3]!.apex);
    expect(plan.a.blocks.some((b) => b.kind === "stance")).toBe(true);
    expect(plan.b.blocks.some((b) => b.kind === "stance")).toBe(true);
    // the one knocked out falls
    expect(plan.b.blocks.some((b) => b.kind === "fall")).toBe(true);
    expect(plan.a.blocks.some((b) => b.kind === "fall")).toBe(false);
    // the attacker's strikes are aimed at the other fighter
    const strikes = plan.a.overlays.filter((o) => o.kind === "strike");
    expect(strikes.length).toBeGreaterThanOrEqual(3);
    // hits make impact effects; cuts cover the fight without overlapping
    expect(plan.fx.length).toBeGreaterThan(0);
    expect(plan.shots.length).toBeGreaterThan(2);
    const shots = [...plan.shots].sort((x, y) => x.t - y.t);
    for (let i = 1; i < shots.length; i++) expect(shots[i]!.t).toBeGreaterThanOrEqual(shots[i - 1]!.t + shots[i - 1]!.dur - 1e-6);
  });
  it("has sword and unarmed moves, and combos expand into several strikes", () => {
    for (const m of ["slash", "thrust", "overhead", "jab", "cross", "hook", "uppercut", "kick", "highkick", "barrage", "flurry"]) expect(FIGHT_MOVES[m], m).toBeTruthy();
    const plan = compileFight({
      a: actor("a"), b: actor("b"), t: 0, style: "fists", beats: [{ do: "barrage", by: "a", hit: true }],
      posA: { pos: [0, 0, 0], yaw: 0 }, posB: { pos: [0, 0, 1.2], yaw: 180 }, impactFrames: false, cameras: false,
    });
    expect(plan.a.overlays.filter((o) => o.kind === "strike").length).toBeGreaterThan(2);
    expect(plan.shots).toHaveLength(0);
  });
});
