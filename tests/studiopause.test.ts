import { describe, expect, it } from "vitest";
import { innerMarks, innerPauses, valleys } from "../src/voice/engine/studio_pause";
import { pacing } from "../src/voice/engine/studio_pace";

const sr = 24000;
/** "Words": bursts of a tone (seconds each) with silences (seconds) between them, over a faint room. */
function speech(words: number[], gaps: number[], room = 0.0008): Float32Array {
  const total = words.reduce((a, b) => a + b, 0) + gaps.reduce((a, b) => a + b, 0);
  const x = new Float32Array(Math.round(total * sr));
  let seed = 7;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff) * 2 - 1;
  for (let i = 0; i < x.length; i++) x[i] = rnd() * room;
  let o = 0;
  words.forEach((w, k) => {
    const n = Math.round(w * sr);
    for (let i = 0; i < n; i++) x[o + i]! += 0.3 * Math.sin((2 * Math.PI * 180 * i) / sr) * Math.min(1, i / 240, (n - i) / 240);
    o += n + Math.round((gaps[k] ?? 0) * sr);
  });
  return x;
}
const rms = (x: Float32Array, a: number, b: number) => { let s = 0; for (let i = Math.round(a * sr); i < Math.round(b * sr); i++) s += x[i]! * x[i]!; return Math.sqrt(s / Math.round((b - a) * sr)); };

describe("the pauses inside a sentence", () => {
  it("finds the marks inside a line, not its closing one, and where the next word starts", () => {
    const t = "ये... तेरे पापा की पहली तनख़्वाह है।";
    const m = innerMarks(t);
    expect(m).toHaveLength(1);
    expect(m[0]!.strong).toBe(true);
    expect(m[0]!.want).toBeCloseTo(0.5, 2);
    expect(m[0]!.at).toBeLessThan(0.15);
    expect(t.slice(m[0]!.next)).toMatch(/^तेरे/);
    const e = innerMarks("Well done, son. Now you take care of her.");
    expect(e.map((x) => x.strong)).toEqual([false, true]);
    expect("Well done, son. Now you take care of her.".slice(e[1]!.next)).toMatch(/^Now/);
  });
  it("leaves numbers whole and asks more of a tone that pauses", () => {
    expect(innerMarks("It cost 3.5 lakh, or 1,000 a day.")).toHaveLength(1);
    expect(innerMarks("Wait… listen to me now.", { pauses: 1.5 })[0]!.want).toBeCloseTo(0.75, 2);
    expect(innerMarks("Hi.")).toHaveLength(0);
  });
  it("opens the gap where the mark falls to the pause it asks for, with the take's own room in it", () => {
    // "Wait… listen to me now." : one short word, a 70 ms gap, then the rest
    const x = speech([0.3, 0.25, 0.2, 0.2, 0.3], [0.07, 0.02, 0.02, 0.02]);
    const r = innerPauses(x, "Wait… listen to me now.", {}, sr);
    expect(r.made).toHaveLength(1);
    expect(r.made[0]!.at).toBeGreaterThan(0.28);
    expect(r.made[0]!.at).toBeLessThan(0.4);
    expect(r.made[0]!.now).toBeCloseTo(0.5, 2);
    expect(r.pcm.length / sr).toBeCloseTo(x.length / sr + 0.5 - r.made[0]!.had, 1);
    // the opened pause is quiet but not a hole: the room goes on
    const inPause = rms(r.pcm, 0.45, 0.7);
    expect(inPause).toBeGreaterThan(0.0002);
    expect(inPause).toBeLessThan(0.004);
  });
  it("leaves a line alone that has no marks, and a mark that has no gap near it", () => {
    const x = speech([0.3, 0.25, 0.2], [0.05, 0.05]);
    expect(innerPauses(x, "Wait for me now.", {}, sr).pcm).toBe(x);
    const run = speech([1.4], []); // one unbroken sound: nowhere to pause without cutting a word
    expect(innerPauses(run, "Wait… listen to me now.", {}, sr).pcm).toBe(run);
  });
  it("goes by the engine's moment when it is given: the gap before the word after the mark, not a wider one elsewhere", () => {
    // gaps after the 1st word (wide, 0.12 s) and after the 3rd (narrow, 0.04 s); the mark is after the 3rd word
    const x = speech([0.25, 0.25, 0.25, 0.25, 0.3], [0.12, 0.02, 0.04, 0.02]);
    const after3 = 0.25 + 0.12 + 0.25 + 0.02 + 0.25 + 0.04; // the 4th word begins here
    const r = innerPauses(x, "One two three… four five.", {}, sr, [after3]);
    expect(r.made).toHaveLength(1);
    expect(r.made[0]!.at).toBeGreaterThan(after3 - 0.06);
    expect(r.made[0]!.at).toBeLessThan(after3 + 0.01);
  });
  it("takes the pause the voice already made when the engine's moment falls before it (a real take: 'शाबाश, बेटा। अब माँ का ख़याल... तू रखना।')", () => {
    // words and silences as measured in that take; the attention reached "तू" 0.125 s BEFORE the long silence began
    const words = [0.44, 0.12, 0.18, 0.3, 0.08, 0.33, 0.29], gaps = [0.12, 0.04, 0.24, 0.07, 0.12, 0.27];
    const x = speech(words, gaps);
    const out = innerPauses(x, "शाबाश, बेटा। अब माँ का ख़याल... तू रखना।", {}, sr, [0.475, 0.995, 1.915]);
    expect(out.made).toHaveLength(3);
    // the three pauses are the comma's gap (0.44–0.56), the sentence's (0.90–1.14) and the long one (2.04–2.31): never the short one before it
    expect(out.made[0]!.at).toBeGreaterThan(0.42); expect(out.made[0]!.at).toBeLessThan(0.58);
    expect(out.made[1]!.at).toBeGreaterThan(0.88); expect(out.made[1]!.at).toBeLessThan(1.16);
    expect(out.made[2]!.at).toBeGreaterThan(2.02); expect(out.made[2]!.at).toBeLessThan(2.33);
    expect(out.made[2]!.now).toBeCloseTo(0.5, 1);
  });
  it("does not take an earlier word's gap when the voice ran on through the mark, and says the pause is missing (a real take: 'Nobody told you — not even her?')", () => {
    // the only gaps are around "told" (0.42–0.47, 0.52–0.56); the attention reached "not" at 0.84, in running speech
    const x = speech([0.42, 0.05, 1.16], [0.05, 0.04]);
    const out = innerPauses(x, "Nobody told you — not even her?", {}, sr, [0.84]);
    expect(out.made).toHaveLength(0);
    expect(out.missed).toBe(1);
    expect(out.pcm.length).toBe(x.length);
  });
  it("a comma is only given its length where the voice already paused", () => {
    const x = speech([0.3, 0.3, 0.3], [0.02, 0.02]);
    expect(innerPauses(x, "Yes, I will come.", {}, sr).made).toHaveLength(0);
    const y = speech([0.3, 0.3, 0.3, 0.3], [0.08, 0.02, 0.02]);
    const r = innerPauses(y, "Yes, I will come.", {}, sr);
    expect(r.made).toHaveLength(1);
    expect(r.made[0]!.now).toBeCloseTo(0.18, 2);
  });
  it("is part of a line's pacing, and says how many pauses it set", () => {
    const x = speech([0.3, 0.25, 0.2, 0.2, 0.3], [0.07, 0.02, 0.02, 0.02]);
    const r = pacing([x], ["Wait… listen to me now."], {}, sr);
    expect(r.pauses).toBe(1);
    expect(r.pcm.length).toBeGreaterThan(x.length + 0.3 * sr);
    expect(valleys(r.pcm, sr).list.some((v) => (v.to - v.from) / sr > 0.4)).toBe(true);
  });
});
