import { describe, expect, it } from "vitest";
import { seedFor, takeSeed } from "../src/voice/engine/studio";
import { readTone } from "../src/agent/voiceopen";
import { voiceSpec } from "../desktop/film/kit/project.js";

describe("another take of a studio line", () => {
  it("is acted from another seed; the first take is the one it always was", () => {
    expect(takeSeed("k", "Run.", 0)).toBe(seedFor("k", "Run.", "0"));
    expect(takeSeed("k", "Run.", 1)).toBe(seedFor("k", "Run.", "1"));
    expect(takeSeed("k", "Run.", 0, 1)).not.toBe(takeSeed("k", "Run.", 0));
    expect(takeSeed("k", "Run.", 0, 1)).not.toBe(takeSeed("k", "Run.", 1)); // not what the first take falls back on
    expect(takeSeed("k", "Run.", 0, 2)).not.toBe(takeSeed("k", "Run.", 0, 1));
    expect(takeSeed("k", "Run.", 0, 1)).toBe(takeSeed("k", "Run.", 0, 1));
  });

  it("is asked for per language in script.json, and only that language is spoken again", () => {
    const script = { cast: { Sena: { en: ["af_heart", {}], hi: ["hf_alpha", {}] } } };
    const ln = { id: "P4", who: "Sena", en: "Make a world that deserves her smile.", hi: "ऐसी दुनिया बनाओ।", tone: { like: "loving", rate: 0.8 } };
    const en0 = voiceSpec(script, ln, "en"), hi0 = voiceSpec(script, ln, "hi");
    expect(en0.tone.take).toBeUndefined();
    const again = { ...ln, take_hi: 2 };
    const en1 = voiceSpec(script, again, "en"), hi1 = voiceSpec(script, again, "hi");
    expect(hi1.tone.take).toBe(2);
    expect(hi1.key).not.toBe(hi0.key);
    expect(en1.key).toBe(en0.key);
    expect(en1.tone.take).toBeUndefined();
    // a take for every language
    expect(voiceSpec(script, { ...ln, take: 1 }, "en").tone.take).toBe(1);
    expect(voiceSpec(script, { ...ln, take: 1, take_hi: 3 }, "hi").tone.take).toBe(3);
    // nonsense is no take
    expect(voiceSpec(script, { ...ln, take_en: -1 }, "en").key).toBe(en0.key);
    expect(voiceSpec(script, { ...ln, take_en: 0 }, "en").key).toBe(en0.key);
  });

  it("reaches the voice with the rest of the line's tone", () => {
    expect(readTone({ like: "loving", rate: 0.8, take: 2 })?.take).toBe(2);
    expect(readTone({ rate: 0.8 })?.take).toBeUndefined();
    expect(readTone({ rate: 0.8, take: 1.5 })?.take).toBeUndefined();
    expect(readTone({ rate: 0.8, take: -1 })?.take).toBeUndefined();
  });
});
