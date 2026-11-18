import { describe, expect, it } from "vitest";
import { glide, peakFor, shapeStudio, swellOf } from "../src/voice/engine/studio_shape";

const sr = 24000;
const tone = (hz: number, s: number) => Float32Array.from({ length: Math.round(sr * s) }, (_, i) => 0.5 * Math.sin((2 * Math.PI * hz * i) / sr));
const zc = (x: Float32Array) => { let n = 0; for (let i = 1; i < x.length; i++) if (x[i - 1]! < 0 && x[i]! >= 0) n++; return n / (x.length / sr); };
const rms = (x: Float32Array) => Math.sqrt(x.reduce((s, v) => s + v * v, 0) / x.length);

describe("a studio line takes the rest of its tone", () => {
  it("is said higher or lower without changing its length", () => {
    const x = tone(200, 2), up = shapeStudio(x, { pitch: 2 });
    expect(up.length).toBe(x.length);
    expect(zc(up.subarray(sr * 0.5, sr * 1.5))).toBeCloseTo(200 * 2 ** (2 / 12), -1);
  });
  it("rises at its end like a question, or falls, and keeps its length", () => {
    const x = tone(200, 2);
    const r = shapeStudio(x, { ending: "rise" }), f = shapeStudio(x, { ending: "fall" });
    expect(r.length).toBe(x.length);
    expect(zc(r.subarray(0, sr))).toBeCloseTo(200, -1); // the start untouched
    expect(zc(r.subarray(Math.round(sr * 1.85), Math.round(sr * 1.98)))).toBeGreaterThan(215); // the end has risen
    expect(zc(f.subarray(Math.round(sr * 1.85), Math.round(sr * 1.98)))).toBeLessThan(190);
    expect(glide(x, 0, 100, 3)).toBe(x); // too short to glide: untouched
  });
  it("is louder or softer by its energy, and the level it sits at follows", () => {
    const x = tone(200, 1);
    expect(rms(shapeStudio(x, { energy: -1 }))).toBeLessThan(rms(x) * 0.6);
    expect(rms(shapeStudio(x, { energy: 1 }))).toBeGreaterThan(rms(x) * 1.2);
    expect(peakFor(-1)).toBeLessThan(peakFor(0));
    expect(peakFor(1)).toBeGreaterThan(peakFor(0));
  });
  it("builds towards an exclamation and dies away in a trailing line", () => {
    expect(swellOf("Ruk jao!")).toBeGreaterThan(0);
    expect(swellOf("Pata nahi…")).toBeLessThan(0);
    const x = tone(200, 2), s = shapeStudio(x, { swell: 1 });
    expect(rms(s.subarray(sr * 1.5))).toBeGreaterThan(rms(s.subarray(0, sr * 0.5)) * 1.5);
  });
  it("trembles when asked", () => {
    const x = tone(200, 1), s = shapeStudio(x, { shake: 1 });
    const blocks = Array.from({ length: 20 }, (_, k) => rms(s.subarray(k * 1000, (k + 1) * 1000)));
    expect(Math.max(...blocks) / Math.min(...blocks)).toBeGreaterThan(1.15);
  });
});

describe("a script's directions reach the studio voice", () => {
  it("turns louder/softer, whispered and shouted into the tone's energy", async () => {
    const { deliveryEnergy } = await import("../src/voice/service");
    expect(deliveryEnergy()).toBe(0);
    expect(deliveryEnergy(0.55, 0.85)).toBeLessThan(-0.8); // whispering
    expect(deliveryEnergy(0.75, 0.35)).toBeLessThan(-0.5); // softly
    expect(deliveryEnergy(1.45, 0, 0.55)).toBeGreaterThan(0.9); // shouting
  });
});
