import { describe, expect, it } from "vitest";
import { detectBeats } from "../src/media/beats";
import { newProject, rampIntegral, sourceTime, type Asset } from "../src/core/model";
import * as ops from "../src/core/ops";
import { evaluateClip } from "../src/core/evaluate";

function kickTrack(bpm: number, seconds: number, sr = 22050): Float32Array {
  const out = new Float32Array(Math.round(seconds * sr));
  const period = (60 / bpm) * sr;
  for (let b = 0.25 * sr; b < out.length; b += period) {
    for (let i = 0; i < 0.12 * sr && b + i < out.length; i++) {
      const t = i / sr;
      out[Math.round(b + i)]! += Math.sin(2 * Math.PI * (60 + 90 * Math.exp(-t * 30)) * t) * Math.exp(-t * 18) * 0.9;
    }
  }
  // A little noise so it isn't too clean.
  for (let i = 0; i < out.length; i++) out[i]! += (Math.random() - 0.5) * 0.01;
  return out;
}

describe("beats", () => {
  it("finds the tempo and beat grid of a kick track", () => {
    const info = detectBeats([kickTrack(128, 20)], 22050);
    expect(info.bpm).toBeGreaterThan(125);
    expect(info.bpm).toBeLessThan(131);
    expect(info.beats.length).toBeGreaterThan(35);
    // First beat lands on the first kick (0.25 s) within 30 ms.
    expect(Math.abs(info.beats[0]! - 0.25) % (60 / 128)).toBeLessThan(0.03);
  });
});

describe("speed ramps and freeze", () => {
  it("integrates a constant ramp like a plain speed", () => {
    expect(rampIntegral([{ t: 0, v: 2, ease: "linear" }, { t: 4, v: 2, ease: "linear" }], 3)).toBeCloseTo(6, 3);
  });
  it("ramp presets never run past the end of the source", () => {
    const p = newProject();
    const a: Asset = { id: "v", kind: "video", name: "v.mp4", mime: "video/mp4", size: 1, duration: 6, width: 1920, height: 1080, ref: "idb:v" };
    p.assets.push(a);
    const c = ops.addClip(p, { assetId: "v", start: 0, dur: 4 });
    ops.speedRamp(p, c.id, "montage");
    expect(sourceTime(c, 4, 6)).toBeLessThanOrEqual(6);
    expect(sourceTime(c, 2, 6)).toBeGreaterThan(sourceTime(c, 1, 6));
  });
  it("freeze frame holds the source and pushes later clips", () => {
    const p = newProject();
    const a: Asset = { id: "v", kind: "video", name: "v.mp4", mime: "video/mp4", size: 1, duration: 10, width: 1920, height: 1080, ref: "idb:v" };
    p.assets.push(a);
    const c = ops.addClip(p, { assetId: "v", start: 0, dur: 6 });
    const hold = ops.freezeFrame(p, c.id, 2, 1.5);
    expect(hold.hold).toBe(true);
    expect(sourceTime(hold, 3, 10)).toBeCloseTo(2);
    const track = p.tracks.find((t) => t.clips.includes(hold))!;
    const after = track.clips.find((x) => x.start > 2 && !x.hold)!;
    expect(after.start).toBeCloseTo(3.5);
  });
  it("crop, effects and whip transitions resolve", () => {
    const p = newProject();
    const c = ops.addClip(p, { kind: "color", start: 0, dur: 4 });
    ops.setCrop(p, c.id, { t: 0.1, b: 0.1 });
    ops.keyframe(p, c.id, "fx.glow", 0, 0);
    ops.keyframe(p, c.id, "fx.glow", 2, 2);
    c.transitionIn = { kind: "whipLeft", dur: 0.5 };
    const d = evaluateClip(p, c, 0.25);
    expect(d.crop).toEqual([0, 0.1, 0, 0.1]);
    expect(d.fx.glow).toBeGreaterThan(0);
    expect(Math.abs(d.dirBlur[0])).toBeGreaterThan(10);
  });
});
