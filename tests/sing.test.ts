import { describe, expect, it } from "vitest";
import { emphasisRanges, TONES } from "../src/voice/engine/open";
import { fitRange, lineSyllables, singRange, syllabify } from "../src/voice/engine/sing";

describe("singing", () => {
  it("splits words into syllables with one vowel core each", () => {
    expect(syllabify("nəməsteː")).toHaveLength(3);
    expect(syllabify("dɪl")).toEqual(["dɪl"]);
    expect(syllabify("kʰʊʃbuː")).toHaveLength(2);
    expect(syllabify("zɪndəgiː")).toHaveLength(3);
    // every syllable keeps all the sounds: nothing is dropped
    for (const w of ["nəməsteː", "kʰʊʃbuː", "zɪndəgiː", "pjaːɾ"]) expect(syllabify(w).join("")).toBe(w);
  });
  it("splits a line into the syllables to sing", () => {
    const s = lineSyllables("dɪl kiː ɾaːheːn");
    expect(s.length).toBe(4);
  });
  it("keeps each voice in the range where it stays in tune", () => {
    expect(singRange("hf_alpha")).toEqual([52, 64]);
    expect(singRange("hm_omega")).toEqual([43, 60]);
    expect(singRange("am_michael")).toEqual([43, 60]);
    // a tune written too high comes down by octaves, one in range stays
    expect(fitRange([{ midi: 72 }, { midi: 76 }], "hf_alpha")).toBe(-12);
    expect(fitRange([{ midi: 55 }, { midi: 62 }], "hf_alpha")).toBe(0);
    expect(fitRange([{ midi: 60 }, { midi: 67 }], "hm_psi")).toBe(-12);
    expect(fitRange([], "hf_alpha")).toBe(0);
  });
});

describe("tones of voice", () => {
  it("has the named tones", () => {
    for (const t of ["neutral", "warm", "happy", "excited", "sad", "angry", "stern", "calm", "afraid", "tired", "question", "whispery", "commanding", "playful", "indian"]) expect(TONES[t], t).toBeTruthy();
    expect(TONES.sad!.rate ?? 1).toBeLessThan(1);
    expect(TONES.excited!.pitch ?? 0).toBeGreaterThan(0);
  });
  it("finds the sounds of the words to stress", () => {
    const r = emphasisRanges("tʊm sətʃ mẽː dʒiːt gəeː", [1]);
    expect(r).toHaveLength(1);
    // token positions: the model's input has a pad token first, and the range includes its last sound
    expect([..."tʊm sətʃ mẽː dʒiːt gəeː"].slice(r[0]![0] - 1, r[0]![1]).join("")).toBe("sətʃ");
  });
});
