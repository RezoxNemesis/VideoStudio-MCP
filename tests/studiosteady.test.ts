import { describe, it, expect } from "vitest";
import { pitchTrack, steadiness, cracked, CRACKS } from "../src/voice/engine/studio_steady";

// The owner heard one character's lines as "a little unnatural, the way they are said" (27 Nov 2026). Their words were all there; their
// pitch broke: sudden steps of more than three semitones 1.7 and 2.7 times a second, where real readers and the lines he accepted stay
// under 1.5. So the engine measures how steady a take's voice is, and a complete take that cracks is made again while tries remain.
const SR = 24000;
/** a voice-like tone (a few overtones) whose pitch is pitchAt(t) Hz, with silence where it gives 0 */
const voice = (seconds: number, pitchAt: (t: number) => number) => {
  const a = new Float32Array(Math.round(seconds * SR)); let ph = 0;
  for (let i = 0; i < a.length; i++) { const f = pitchAt(i / SR); if (!f) { continue; } ph += (2 * Math.PI * f) / SR; a[i] = 0.3 * (Math.sin(ph) + 0.5 * Math.sin(2 * ph) + 0.25 * Math.sin(3 * ph)); }
  return a;
};

describe("how steady a take's voice is", () => {
  it("follows the pitch of a voice", () => {
    const { f0 } = pitchTrack(voice(1, () => 200), SR), v = Array.from(f0).filter((x) => x > 0);
    expect(v.length).toBeGreaterThan(80);
    for (const x of v) expect(Math.abs(x - 200)).toBeLessThan(3);
  });
  it("a sentence that glides down, with a pause in it, is steady", () => {
    const s = steadiness(voice(2.4, (t) => (t > 1.0 && t < 1.4 ? 0 : 215 - 20 * t)), SR)!;
    expect(s.steps).toBe(0); expect(s.jumps).toBe(0); expect(cracked(s)).toBe(false);
    expect(s.spoken).toBeGreaterThan(1.8); expect(s.spoken).toBeLessThan(2.1);            // the pause is not speech
    expect(Math.abs(s.median - 190)).toBeLessThan(15);
    expect(s.wander).toBeLessThan(19);                                                   // a plain glide travels little
  });
  it("a voice whose pitch breaks again and again is called cracked", () => {
    // what the owner's unsteady takes did: low in a falling sentence the voice flips an octave up for a few hundredths of a second, and back
    const fall = (t: number) => 240 - 45 * t, flip = (t: number) => [1.3, 1.45, 1.6, 1.75, 1.9].some((b) => t >= b && t < b + 0.04);
    const s = steadiness(voice(2, (t) => fall(t) * (flip(t) ? 2 : 1)), SR)!;
    expect(s.steps).toBeGreaterThanOrEqual(6); expect(s.jumps).toBeGreaterThan(CRACKS); expect(cracked(s)).toBe(true);
    // the same sentence without the flips, and a voice that slides far and fast, are steady: only a break counts
    expect(cracked(steadiness(voice(2, fall), SR))).toBe(false);
    expect(cracked(steadiness(voice(2, (t) => 200 + 100 * Math.abs(Math.sin(Math.PI * t * 2.5))), SR))).toBe(false);
  });
  it("one break is a breath, not a crack; a whisper or a word says nothing either way", () => {
    const one = steadiness(voice(1.5, (t) => (240 - 45 * t) * (t >= 1.3 && t < 1.34 ? 2 : 1)), SR)!;
    expect(one.steps).toBeGreaterThan(0); expect(one.steps).toBeLessThan(3); expect(cracked(one)).toBe(false);
    expect(steadiness(new Float32Array(SR), SR)).toBeNull();
    expect(steadiness(voice(0.12, () => 180), SR)).toBeNull();
    expect(cracked(null)).toBe(false);
  });
});
