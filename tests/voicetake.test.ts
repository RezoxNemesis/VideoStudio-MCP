import { describe, expect, it } from "vitest";
import { seedFor, takeSeed } from "../src/voice/engine/studio";
import { readTone } from "../src/agent/voiceopen";

describe("another take of a studio line", () => {
  it("is acted from another seed; the first take is the one it always was", () => {
    expect(takeSeed("k", "Run.", 0)).toBe(seedFor("k", "Run.", "0"));
    expect(takeSeed("k", "Run.", 1)).toBe(seedFor("k", "Run.", "1"));
    expect(takeSeed("k", "Run.", 0, 1)).not.toBe(takeSeed("k", "Run.", 0));
    expect(takeSeed("k", "Run.", 0, 1)).not.toBe(takeSeed("k", "Run.", 1)); // not what the first take falls back on
    expect(takeSeed("k", "Run.", 0, 2)).not.toBe(takeSeed("k", "Run.", 0, 1));
    expect(takeSeed("k", "Run.", 0, 1)).toBe(takeSeed("k", "Run.", 0, 1));
  });

  it("reaches the voice with the rest of the line's tone", () => {
    expect(readTone({ like: "loving", rate: 0.8, take: 2 })?.take).toBe(2);
    expect(readTone({ rate: 0.8 })?.take).toBeUndefined();
    expect(readTone({ rate: 0.8, take: 1.5 })?.take).toBeUndefined();
    expect(readTone({ rate: 0.8, take: -1 })?.take).toBeUndefined();
  });
});
