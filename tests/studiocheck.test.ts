import { describe, expect, it } from "vitest";
import { heardError, normalizeHeard } from "../src/voice/engine/studio_check";

describe("a studio take is listened back to", () => {
  it("is exact when Whisper hears the words, whatever the punctuation, nuktas or full stops", () => {
    expect(heardError("बेटा, मैं ठीक हूँ।", "बेटा मैं ठीक हूँ")).toBe(0);
    expect(heardError("ज़रा रुको", "जरा रुको.")).toBe(0);
    expect(normalizeHeard("It's okay, Beta!")).toBe("it s okay beta");
  });
  it("counts a word said wrong", () => {
    expect(heardError("तुम बैठो", "तुम बैठ हूं")).toBeGreaterThan(0.2);
    expect(heardError("ठीक है", "थीक है")).toBeGreaterThan(0.1);
  });
  it("does not count numbers Whisper writes in figures as mistakes", () => {
    expect(heardError("रात के ग्यारह बजे", "रात के 11 बजे")).toBeLessThan(0.35);
  });
});
