import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import * as THREE from "three";
import { gaitCycle, Clip, fixTimes, HALF_RATE } from "../src/stage/engine/anim.js";
import { type MotionIndex } from "../src/stage/motion/library";
import { Pack } from "../src/stage/pack";

// The captured library says every performance was recorded at 120 frames a second, because its source files carry
// no frame rate and the conversion wrote 120 into all of them. Some performers were recorded at 60: their clips
// played at DOUBLE speed. Found by physics: a runner's hips fell at four times gravity, and a "run" took 340 steps a
// minute. Their clips are now played at their true speed.
describe("performers captured at half the usual rate", () => {
  const ix = (): MotionIndex => ({ fps: 30, joints: [], source: "", subjects: {}, clips: {
    "143_01": { n: 26, dur: 0.833, s: 143, desc: "Run", tags: [], speed: 6.66 },
    "09_05": { n: 36, dur: 1.167, s: 9, desc: "run", tags: [], speed: 3.43 },
  } } as unknown as MotionIndex);
  it("their clips last twice as long and travel half as fast; the others are left alone", () => {
    const i = fixTimes(ix()) as any;
    expect(HALF_RATE).toContain(143);
    expect(i.clips["143_01"].dur).toBeCloseTo(1.667, 2);
    expect(i.clips["143_01"].speed).toBeCloseTo(3.33, 2);
    expect(i.clips["143_01"].fps).toBe(15);
    expect(i.clips["09_05"].dur).toBe(1.167);
    expect(i.clips["09_05"].speed).toBe(3.43);
    expect(i.clips["09_05"].fps ?? 30).toBe(30);
  });
  it("is done once: a corrected index (by this, or by a library packed with true rates) is not corrected again", () => {
    const once = fixTimes(ix()) as any, twice = fixTimes(once) as any;
    expect(twice.clips["143_01"].dur).toBeCloseTo(1.667, 2);
    const packed = { ...ix(), timefix: "packed" } as any;
    expect((fixTimes(packed) as any).clips["143_01"].dur).toBe(0.833);
  });
});

const ASSETS = [process.env.VS_STAGE_ASSETS_DIR, "desktop/stage-assets", "/home/claude/ws/squashfs-root/resources/stage-assets"].find((d) => d && existsSync(`${d}/motion/index.json`));
describe.skipIf(!ASSETS)("the captured runs obey physics", () => {
  const index = ASSETS ? (fixTimes(JSON.parse(readFileSync(`${ASSETS}/motion/index.json`, "utf8"))) as MotionIndex) : (null as unknown as MotionIndex);
  const packs: Record<string, Pack> = {};
  const clipOf = (name: string) => { const m = index.clips[name]!; if (!packs[m.s]) { const b = readFileSync(`${ASSETS}/motion/s${String(m.s).padStart(3, "0")}.pack`); packs[m.s] = new Pack(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength)); } return new Clip(name, m, index, packs[m.s]!) as any; };
  const jid = ASSETS ? Object.fromEntries(index.joints.map((n, i) => [n, i])) : {};
  // how hard the hips fall, in g (three frames together, so one bad frame does not count); metres from the source's inches / 0.45
  // how hard the hips fall, in g: the lowest quarter of the frames, without the two worst (a capture has bad frames);
  // metres from the source's inches / 0.45
  const fall = (c: any) => {
    const v = new THREE.Vector3(), ys: number[] = [], dt = 1 / c.fps, U = 0.0254 / 0.45; for (let i = 0; i < c.n; i++) { c.rootAt(i * dt, v); ys.push(v.y); }
    const a = ys.slice(2).map((y, i) => ((y - 2 * ys[i + 1]! + ys[i]!) / (dt * dt)) * U).sort((p, q) => p - q), low = a.slice(2, 2 + Math.max(2, Math.round(a.length / 4)));
    return -low.reduce((p, q) => p + q, 0) / low.length / 9.81;
  };
  it("the run that played as a sprint (143_01) is a jog: a whole stride of about 0.7 s, and its hips fall as things fall", () => {
    const c = clipOf("143_01"), g = gaitCycle(c, jid) as { t0: number; t1: number; seam: number } | null;
    expect(c.dur).toBeCloseTo(1.667, 2);
    expect(g, "a whole stride is found").not.toBeNull();
    expect(g!.t1 - g!.t0).toBeGreaterThan(0.6);
    expect(g!.t1 - g!.t0).toBeLessThan(0.85);
    expect(fall(c)).toBeLessThan(1.7);
    expect(fall(c)).toBeGreaterThan(0.5);
  });
  it("no straight run in the library falls faster than gravity allows, or steps faster than legs can", () => {
    const bad: string[] = [];
    for (const [name, m] of Object.entries(index.clips)) {
      if (!/^(run|jog|run\/jog|running\w*)\b/i.test(m.desc.trim()) || m.n < 14 || m.speed < 2.5) continue;
      const c = clipOf(name), f = fall(c), g = gaitCycle(c, jid) as { t0: number; t1: number } | null;
      if (f > 1.8) bad.push(`${name} (${m.desc.trim().slice(0, 20)}): hips fall at ${f.toFixed(1)} g`);
      if (g && (m as any).travel / Math.max(1e-6, (m as any).path) > 0.97 && g.t1 - g.t0 < 0.4) bad.push(`${name}: a stride of ${(g.t1 - g.t0).toFixed(2)} s`);
    }
    expect(bad).toEqual([]);
  }, 60000);
});
