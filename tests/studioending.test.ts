import { describe, expect, it } from "vitest";
import { endingOf, endsWrong } from "../src/voice/engine/studio_steady";
import { trimSilence } from "../src/voice/engine/studio";

// 27 Nov 2026, the owner: "make it very natural delivery, more natural ending of any dialogue".
const SR = 24000;
/** a voiced tone whose pitch glides from f0 to f1 (Hz) over `sec`, with a soft start, then silence */
function glide(f0: number, f1: number, sec: number, tail = 0): Float32Array {
  const n = Math.round(sec * SR), out = new Float32Array(n + Math.round(tail * SR)); let ph = 0;
  for (let i = 0; i < n; i++) { const u = i / n, f = f0 * Math.pow(f1 / f0, u); ph += (2 * Math.PI * f) / SR;
    out[i] = 0.4 * Math.min(1, i / (0.02 * SR)) * (Math.sin(ph) + 0.4 * Math.sin(2 * ph) + 0.2 * Math.sin(3 * ph)); }
  return out;
}
describe("how a line ends", () => {
  it("a statement falling at its end measures low; one rising measures high", () => {
    const fall = new Float32Array([...glide(140, 140, 1.2), ...glide(140, 110, 0.5)]), rise = new Float32Array([...glide(140, 140, 1.2), ...glide(140, 175, 0.5)]);
    expect(endingOf(fall)!).toBeLessThan(-1);
    expect(endingOf(rise)!).toBeGreaterThan(1);
  });
  it("only a statement that ends high is wrong; a question may rise", () => {
    expect(endsWrong("मैंने इस्तीफ़ा दे दिया।", 1.8)).toBe(true);
    expect(endsWrong("मैंने इस्तीफ़ा दे दिया।", -3)).toBe(false);
    expect(endsWrong("और वजह क्या थी?", 4.8)).toBe(false);
    expect(endsWrong("anything", null)).toBe(false);
  });
});
describe("the end of a take is kept and faded, not chopped", () => {
  it("follows the voice's own decay below -40 dB and ends at silence, with no step", () => {
    // half a second of voice, then its sound dies away over 0.4 s (an exponential), then 0.5 s of silence
    const body = glide(130, 120, 0.5), n = Math.round(0.4 * SR), decay = new Float32Array(n);
    for (let i = 0; i < n; i++) decay[i] = body[body.length - 1 - (n - 1 - i) % 200]! * Math.exp(-i / (0.06 * SR));
    const pcm = new Float32Array([...body, ...decay, ...new Float32Array(Math.round(0.5 * SR))]);
    const out = trimSilence(pcm);
    // longer than the old cut (40 dB down + 80 ms) would have kept: the decay below -40 dB is part of the line
    const db40 = 0.06 * Math.log(100) * SR;   // samples into the decay where it is 40 dB down
    expect(out.length).toBeGreaterThan(body.length + db40 + 0.08 * SR);
    expect(out.length).toBeLessThan(pcm.length - 0.3 * SR);   // the silence after is not kept
    expect(Math.abs(out[out.length - 1]!)).toBeLessThan(1e-4); // it ends at nothing (faded), not on a step
  });
});
