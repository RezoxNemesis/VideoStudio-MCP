import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import * as THREE from "three";
import { Clip } from "../src/stage/engine/anim.js";
import { FALLS, fallClock } from "../src/stage/engine/fight.js";
import { liftToFloor } from "../src/stage/engine/shotkit.js";
import type { MotionIndex } from "../src/stage/motion/library";
import { clipsOf, type StageSpec } from "../src/stage/spec";
import { Pack } from "../src/stage/pack";

// A body that is struck down FALLS: it loses its balance, goes over and lands, and that takes about a second. The
// Stage used to play a captured get-up backwards, five seconds of it squeezed into one with a fast start, and the
// loser of a duel was on the ground a sixth of a second after the blow (the user: "transitions into a fallen position
// without a sufficiently visible and convincing impact sequence").
type Fall = { clip: string; in: number; down: number; out: number };
const F = FALLS as Record<string, Fall>;
const clock = (t: number, t0: number, f: Fall, dur?: number) => fallClock(t, t0, f, dur) as number;

describe("the clock of a fall", () => {
  it("starts where the captured fall starts and runs forwards at the capture's own speed", () => {
    const f = F.back!;
    expect(clock(2, 2, f)).toBe(f.in);
    expect(clock(1, 2, f)).toBe(f.in);
    expect(clock(2.5, 2, f)).toBeCloseTo(f.in + 0.5, 9);
    let prev = -1;
    for (let t = 2; t < 6; t += 0.05) { const c = clock(t, 2, f); expect(c).toBeGreaterThanOrEqual(prev); prev = c; }
  });
  it("goes on to the end of the capture (the body settles) and then lies still", () => {
    const f = F.back!;
    expect(clock(2 + (f.out - f.in), 2, f)).toBeCloseTo(f.out, 9);
    expect(clock(60, 2, f)).toBe(f.out);
  });
  it("can be asked to be down after a given time, within what a body can do", () => {
    const f = F.face!;
    expect(clock(3 + 1.4, 3, f, 1.4)).toBeCloseTo(f.down, 6);
    // nobody is down in a fifth of a second, and nobody takes ten: the wish is held to between 0.7 and 1.5 times the capture
    const natural = f.down - f.in;
    expect(clock(3 + 0.2, 3, f, 0.2)).toBeLessThan(f.in + 0.2 * 1.5 + 1e-9);
    expect(clock(3 + natural * 0.7, 3, f, 0.2)).toBeCloseTo(f.down, 6);
    expect(clock(3 + natural / 0.7, 3, f, 10)).toBeCloseTo(f.down, 6);
  });
  it("has a fall onto the back and a fall onto the face", () => {
    expect(Object.keys(F).sort()).toEqual(["back", "face"]);
    for (const f of Object.values(F)) { expect(f.in).toBeLessThan(f.down); expect(f.down).toBeLessThan(f.out); expect(f.down - f.in).toBeGreaterThan(0.8); }
  });
});

describe("a body on the floor", () => {
  it("is lifted onto it when a part of it would sink in, and is never pulled down out of the air", () => {
    expect(liftToFloor(-0.07, 0) as number).toBeCloseTo(0.07, 9);   // lying, the hips 7 cm under the floor: lifted
    expect(liftToFloor(0.3, 0) as number).toBe(0);                  // still falling: left where it is
    expect(liftToFloor(0.52, 0.5) as number).toBe(0);
    expect(liftToFloor(0.4, 0.5) as number).toBeCloseTo(0.1, 9);    // on a floor that is not at zero
  });
});

describe("what a stage loads before it is drawn", () => {
  it("includes the capture of every fall in it (one list of falls, used by the player and by the loader)", () => {
    const spec = { actors: [{ blocks: [{ kind: "fall" }, { kind: "fall", prone: true }], overlays: [] }] } as unknown as StageSpec;
    const need = clipsOf(spec);
    expect(need.has(F.back!.clip)).toBe(true);
    expect(need.has(F.face!.clip)).toBe(true);
  });
});

const ASSETS = [process.env.VS_STAGE_ASSETS_DIR, "desktop/stage-assets", "/home/claude/ws/squashfs-root/resources/stage-assets"].find((d) => d && existsSync(`${d}/motion/index.json`));
describe.skipIf(!ASSETS)("the captured falls", () => {
  const index = ASSETS ? (JSON.parse(readFileSync(`${ASSETS}/motion/index.json`, "utf8")) as MotionIndex) : (null as unknown as MotionIndex);
  const clipOf = (name: string) => { const m = index.clips[name]!, b = readFileSync(`${ASSETS}/motion/s${String(m.s).padStart(3, "0")}.pack`); return new Clip(name, m, index, new Pack(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength))) as unknown as { dur: number; rootAt(t: number, out?: THREE.Vector3): THREE.Vector3 }; };
  it("begin standing, end on the ground, and take their time on the way", () => {
    for (const [name, f] of Object.entries(F)) {
      const clip = clipOf(f.clip), y = (t: number) => clip.rootAt(t, new THREE.Vector3()).y, stand = y(f.in);
      expect(f.out, `${name}: inside the clip`).toBeLessThanOrEqual(clip.dur + 1e-6);
      expect(y(f.down) / stand, `${name}: the hips are on the ground when it says down`).toBeLessThan(0.36);
      expect(y(f.out) / stand, `${name}: and stay there`).toBeLessThan(0.36);
      // a sixth of the way through the fall the body is still up: losing one's balance comes before landing
      expect(y(f.in + (f.down - f.in) / 6) / stand, `${name}: still up a sixth of the way through`).toBeGreaterThan(0.7);
      for (let t = 0; t < f.in; t += 0.1) expect(y(Math.max(0.1, t)) / stand, `${name}: standing before it`).toBeGreaterThan(0.85);
    }
  });
});
