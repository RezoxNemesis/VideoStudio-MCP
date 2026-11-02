import { describe, expect, it } from "vitest";
import { analyseSong } from "../src/media/song";

/** Synthetic song: 120 bpm kick; quiet A-minor verse, loud chorus, verse, chorus. */
function song(sr = 22050) {
  const secs = 64;
  const x = new Float32Array(sr * secs);
  const beat = 0.5;
  const parts = [
    { from: 0, to: 16, amp: 0.12, chord: [220, 261.63, 329.63] },
    { from: 16, to: 32, amp: 0.5, chord: [174.61, 220, 261.63] },
    { from: 32, to: 48, amp: 0.12, chord: [220, 261.63, 329.63] },
    { from: 48, to: 64, amp: 0.5, chord: [174.61, 220, 261.63] },
  ];
  for (const p of parts)
    for (let i = p.from * sr; i < p.to * sr; i++) {
      const t = i / sr;
      let v = 0;
      for (const f of p.chord) v += Math.sin(2 * Math.PI * f * t) * p.amp * 0.3;
      const tb = t % beat;
      v += Math.sin(2 * Math.PI * 60 * tb) * Math.exp(-tb * 30) * (p.amp + 0.2);
      x[i] = v;
    }
  return { x, sr };
}

describe("song understanding", () => {
  it("finds tempo, the minor key area, louder repeated choruses and section boundaries", () => {
    const { x, sr } = song();
    const s = analyseSong([x], sr);
    expect(Math.abs(s.bpm - 120)).toBeLessThan(4);
    expect(s.sections.length).toBeGreaterThanOrEqual(3);
    const at = (t: number) => s.sections.find((q) => q.start <= t && q.end > t)!;
    expect(at(20).energy).toBeGreaterThan(at(8).energy);
    expect(at(56).group).toBe(at(20).group);
    expect(s.sections.some((q) => Math.abs(q.start - 16) < 2.5)).toBe(true);
    expect(s.energy.length).toBe(128);
  });
});
