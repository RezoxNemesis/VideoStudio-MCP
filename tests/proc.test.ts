import { describe, expect, it } from "vitest";
import { chunkWords, timeWords } from "../src/anim/proc";
import { flattenPath } from "../src/anim/svgpath";

describe("captions timing", () => {
  it("keeps words in order inside the window and marks *emphasis*", () => {
    const w = timeWords("Make it *move.*", 1, 3);
    expect(w.map((x) => x.w)).toEqual(["Make", "it", "move."]);
    expect(w[2]!.em).toBe(true);
    expect(w[0]!.t0).toBeCloseTo(1, 5);
    for (let i = 1; i < w.length; i++) expect(w[i]!.t0).toBeGreaterThanOrEqual(w[i - 1]!.t0);
    expect(w[w.length - 1]!.t1).toBeLessThanOrEqual(3.0001);
  });
  it("places words inside the voiced parts of the speech, skipping silence", () => {
    // 30 fps envelope: speech 0–1 s, silence 1–2 s, speech 2–3 s.
    const env = Array.from({ length: 90 }, (_, i) => (i < 30 || i >= 60 ? 0.8 : 0));
    const w = timeWords("one two three four", 0, 3, env, 30);
    for (const x of w) expect(x.t0 < 1.0 || x.t0 >= 1.95).toBe(true);
    expect(w.some((x) => x.t0 >= 2)).toBe(true);
  });
  it("chunks at punctuation and max words", () => {
    const ch = chunkWords(timeWords("Cut. Paste. Animate it now please", 0, 4), 3);
    expect(ch[0]!.words.map((x) => x.w)).toEqual(["Cut."]);
    expect(ch[1]!.words.map((x) => x.w)).toEqual(["Paste."]);
    expect(ch[2]!.words.length).toBeLessThanOrEqual(3);
  });
});

describe("svg paths for draw-on art", () => {
  it("closes petals drawn with relative curves", () => {
    const polys = flattenPath("M540 740 c 50 0 100 -40 120 -100 c -60 0 -110 40 -120 100 z");
    expect(polys).toHaveLength(1);
    const p = polys[0]!;
    expect(p[0]).toBe(540);
    expect(p[p.length - 2]).toBe(540);
    expect(p[p.length - 1]).toBe(740);
  });
});
