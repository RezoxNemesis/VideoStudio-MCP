import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import * as THREE from "three";
import { gaitCycle as findStride, Clip } from "../src/stage/engine/anim.js";
import { MotionLibrary, type MotionIndex } from "../src/stage/motion/library";
import { Pack } from "../src/stage/pack";

type Stride = { t0: number; t1: number; seam: number };
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const gaitCycle = (clip: any, jid: Record<string, number>): Stride | null => findStride(clip, jid) as Stride | null;

/** A made-up clip: the two thighs swing against each other once every `period` seconds while the body travels forward. */
function strider(period: number, dur: number, speed = 3.5) {
  const jid: Record<string, number> = { Hips: 0, LeftUpLeg: 1, LeftLeg: 2, RightUpLeg: 3, RightLeg: 4, LeftArm: 5, RightArm: 6, Spine: 7 };
  const X = new THREE.Vector3(1, 0, 0);
  const clip = {
    dur, fps: 30, n: Math.round(dur * 30) + 1, m: { rest: { LeftLeg: [0, -0.45, 0], RightLeg: [0, -0.45, 0] } },
    quatAt(t: number, j: number, out: THREE.Quaternion) { const a = 0.6 * Math.sin((2 * Math.PI * t) / period); return j === 1 ? out.setFromAxisAngle(X, a) : j === 3 ? out.setFromAxisAngle(X, -a) : j === 5 ? out.setFromAxisAngle(X, -a * 0.5) : j === 6 ? out.setFromAxisAngle(X, a * 0.5) : out.identity(); },
    rootAt(t: number, out = new THREE.Vector3()) { return out.set(0, 0.95 + 0.03 * Math.cos((4 * Math.PI * t) / period), speed * t); },
    restDir(j: string, _m: boolean, out: THREE.Vector3) { const r = (this.m.rest as Record<string, number[]>)[j]!; return out.set(r[0]!, r[1]!, r[2]!); },
  };
  return { clip, jid };
}

describe("the stride of a gait", () => {
  it("is one whole cycle: the same leg coming forward, to the same leg coming forward again", () => {
    const { clip, jid } = strider(0.7, 1.35);
    const c = gaitCycle(clip, jid);
    expect(c, "a cycle is found").not.toBeNull();
    expect(c!.t1 - c!.t0, "its length is the stride's").toBeGreaterThan(0.7 - 1 / 30);
    expect(c!.t1 - c!.t0).toBeLessThan(0.7 + 1 / 30);
    expect(c!.seam, "the two ends are the same pose").toBeLessThan(0.01);
  });

  it("is never half a stride, however well one step matches the next in the mirror", () => {
    // a window of 0.45-0.6 s (what a short run clip used to be searched in) holds no whole stride of 0.7 s
    const { clip, jid } = strider(0.7, 1.1);
    const c = gaitCycle(clip, jid);
    expect(c).not.toBeNull();
    expect(c!.t1 - c!.t0).toBeGreaterThan(0.62);
  });

  it("is not found in a clip too short to hold a whole stride, nor in one that is not a gait", () => {
    expect(gaitCycle(strider(0.7, 0.6).clip, strider(0.7, 0.6).jid)).toBeNull();
    const still = strider(0.7, 2); still.clip.quatAt = (_t: number, _j: number, out: THREE.Quaternion) => out.identity();
    expect(gaitCycle(still.clip, still.jid)).toBeNull();
  });
});

describe("the clip picked for a gait", () => {
  const index = {
    fps: 30, joints: [], source: "", subjects: {},
    clips: {
      "128_03": { n: 42, dur: 1.367, s: 128, desc: "Run Right", tags: ["run", "crawl", "stop"], speed: 3.82, path: 5.19, travel: 5.14, turn: -17.6, air: 0.76, ground: 0, stand: 0 },
      "09_01": { n: 38, dur: 1.233, s: 9, desc: "run", tags: ["run"], speed: 3.57, path: 4.31, travel: 4.31, turn: -5.7, air: 0.87, ground: 0, stand: 0 },
      "127_13": { n: 47, dur: 1.533, s: 127, desc: "Run Side Step Left", tags: ["run"], speed: 3.7, path: 4.95, travel: 4.95, turn: 3, air: 0.66, ground: 0, stand: 0 },
      "49_04": { n: 63, dur: 2.067, s: 49, desc: "run, leap", tags: ["run", "jump"], speed: 3.75, path: 5.06, travel: 5.06, turn: 1, air: 0.59, ground: 0, stand: 0 },
    },
  } as unknown as MotionIndex;
  it("is a plain, straight one: a run that veers, side-steps, leaps or stops is passed over even when its speed is nearer", () => {
    const lib = new MotionLibrary(); lib.index = index;
    expect(lib.pick({ q: "run", straight: true, minDur: 0.9, maxDur: 14, want: 3.8 })).toBe("09_01");
  });
});

// With the Stage's own motion library at hand (the app's stage-assets), every gait is checked on the real captures.
const ASSETS = [process.env.VS_STAGE_ASSETS_DIR, "desktop/stage-assets", "/home/claude/ws/squashfs-root/resources/stage-assets"].find((d) => d && existsSync(`${d}/motion/index.json`));
describe.skipIf(!ASSETS)("the gaits of the motion library", () => {
  const index = ASSETS ? (JSON.parse(readFileSync(`${ASSETS}/motion/index.json`, "utf8")) as MotionIndex) : (null as unknown as MotionIndex);
  const clipOf = (name: string) => { const m = index.clips[name]!, b = readFileSync(`${ASSETS}/motion/s${String(m.s).padStart(3, "0")}.pack`); return new Clip(name, m, index, new Pack(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer)); };
  const jid = ASSETS ? Object.fromEntries(index.joints.map((n, i) => [n, i])) : {};
  it("each has a clip with a whole, clean stride in it", () => {
    const lib = new MotionLibrary(); lib.index = index;
    for (const gait of ["walk", "stroll", "march", "jog", "run", "sneak"]) {
      const name = lib.pickGait(gait);
      expect(name, `${gait}: a clip`).toBeTruthy();
      const c = gaitCycle(clipOf(name!), jid), fast = gait === "jog" || gait === "run";
      expect(c, `${gait} (${name}): a whole stride`).not.toBeNull();
      expect(c!.seam, `${gait} (${name}): the stride's two ends are the same pose`).toBeLessThan(0.003);
      const per = c!.t1 - c!.t0;
      expect(per, `${gait} (${name}): the stride's length in seconds`).toBeGreaterThan(fast ? 0.55 : 0.8);
      expect(per, `${gait} (${name}): the stride's length in seconds`).toBeLessThan(fast ? 1.0 : 2.4);
    }
  });
  it("a gait nobody captured is said so, with the ones there are", () => {
    const lib = new MotionLibrary(); lib.index = index;
    expect(() => lib.pickGait("moonwalk backwards on stilts")).toThrow(/walk, stroll, march, jog, run/);
  });
});
