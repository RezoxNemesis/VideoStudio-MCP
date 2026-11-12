import { describe, expect, it } from "vitest";
import { breathIn, joinReading, performed, reading, seeded } from "../src/voice/engine/natural";

describe("natural reading", () => {
  it("is repeatable", () => {
    const a = seeded("line"), b = seeded("line"), c = seeded("other");
    const xs = [a(), a(), a()], ys = [b(), b(), b()];
    expect(xs).toEqual(ys);
    expect(c()).not.toBe(xs[0]);
    expect(reading("One. Two! Three…", {}, "v|t")).toEqual(reading("One. Two! Three…", {}, "v|t"));
  });
  it("reads sentence by sentence, no two the same", () => {
    const r = reading("The gates are open. Run! I waited ten years for this day, and now it is here.", {}, "seed");
    expect(r.length).toBeGreaterThanOrEqual(2);
    expect(r[r.length - 1]!.pauseMs).toBe(0);
    expect(r[0]!.pauseMs).toBeGreaterThan(150);
    const rates = r.map((x) => x.tone.rate);
    expect(new Set(rates).size).toBe(rates.length);
  });
  it("lets the writing shape the delivery", () => {
    const rnd = () => 0.5;
    const plain = performed("He left.", {}, 0, 1, rnd).tone;
    const loud = performed("He left!", {}, 0, 1, rnd);
    const trail = performed("He left…", {}, 0, 1, rnd).tone;
    const shock = performed("He left?!", {}, 0, 1, rnd).tone;
    expect(loud.tone.energy ?? 0).toBeGreaterThan(plain.energy ?? 0);
    expect(loud.gain).toBeGreaterThan(1);
    expect(trail.rate!).toBeLessThan(plain.rate!);
    expect(trail.ending).toBe("level");
    expect(shock.ending).toBe("rise");
    // a tone that was asked for is kept
    expect(performed("He left…", { ending: "fall" }, 0, 1, rnd).tone.ending).toBe("fall");
    expect(performed("Stop!!", { energy: 0.9 }, 0, 1, rnd).tone.energy).toBeLessThanOrEqual(1);
  });
  it("keeps another language's numbers for that language", () => {
    expect(reading("Ich habe 10 Jahre gewartet.", {}, "s", { world: true })[0]!.text).toContain("10");
    expect(reading("I waited 10 years.", {}, "s")[0]!.text).toContain("ten");
  });
  it("takes breaths only when asked, before longer sentences", () => {
    const text = "The village lay at the end of the valley. Every spring the water rose and carried away the lower fields. No.";
    expect(reading(text, {}, "s").some((r) => r.breath)).toBe(false);
    const r = reading(text, {}, "s", { breath: 1 });
    expect(r[1]!.breath).toBe(true);
    expect(r[0]!.breath).toBeFalsy();
  });
  it("makes a quiet breath and joins a take", () => {
    const air = breathIn(0.3, 0.035, seeded("x"));
    let peak = 0;
    for (const v of air) peak = Math.max(peak, Math.abs(v));
    expect(peak).toBeCloseTo(0.035, 3);
    expect(Math.abs(air[0]!)).toBeLessThan(0.002);
    const one = new Float32Array(2400).fill(0.5);
    const out = joinReading([{ pcm: one, pauseMs: 500, gain: 1 }, { pcm: one, pauseMs: 0, gain: 1.1, breath: true }], { breath: 1, seedText: "x" });
    expect(out.length).toBe(2400 + 12000 + 2400);
    expect(out[2400 + 12000 + 10]).toBeCloseTo(0.55, 5);
    // the breath sits in the pause, just before the second sentence
    let inGap = 0;
    for (let i = 2400 + 4000; i < 2400 + 12000; i++) inGap = Math.max(inGap, Math.abs(out[i]!));
    expect(inGap).toBeGreaterThan(0.01);
    expect(inGap).toBeLessThan(0.06);
  });
});
