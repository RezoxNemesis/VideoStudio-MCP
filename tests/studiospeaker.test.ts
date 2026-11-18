import { describe, expect, it } from "vitest";
import { seedFor } from "../src/voice/engine/studio";
import { studioSpeaker } from "../src/voice/engine/studio_speaker";

describe("the speaker a studio line is acted from", () => {
  const boy = { mix: "am_puck*0.6+af_sky*0.4", pitch: 4.6 }, plain = { mix: "am_michael", pitch: 0 };

  it("is one per voice and mood for a plain voice at its mood's own pitch", () => {
    // (v3: speakers made by the V3 encoder from reference clips that end in silence)
    expect(studioSpeaker(plain, "serious", {}).key).toBe(`v3-${seedFor("am_michael", "serious").toString(36)}`);
    expect(studioSpeaker(plain, undefined, undefined).key).toBe(`v3-${seedFor("am_michael", "neutral").toString(36)}`);
    // a line's tone arrives worked out: a serious line already carries the serious mood's own pitch (−1, halved)
    expect(studioSpeaker(plain, "serious", { pitch: -0.5, mood: "serious" }).key).toBe(`v3-${seedFor("am_michael", "serious").toString(36)}`);
  });

  it("carries a character voice's own pitch: a little boy is not acted from a grown man's voice", () => {
    const s = studioSpeaker(boy, "crying", {});
    expect(s.key).not.toBe(studioSpeaker({ ...boy, pitch: 0 }, "crying", {}).key);
    expect(s.key).toMatch(/-p4\.5$/); // to the half semitone
    // the reference clip is spoken by the character voice itself (which brings its pitch); its tone is the mood's
    expect(s.tone.pitch ?? 0).toBeCloseTo(0.25, 5); // the crying mood's own lift (0.5, halved when the model acts the mood)
    expect(s.tone.mood).toBe("crying");
  });

  it("carries the line's and the cast's pitch too: the reference is spoken at the line's pitch", () => {
    // a serious line raised to +1 stands 1.5 above the serious mood's own −0.5
    const up = studioSpeaker(boy, "serious", { pitch: 1 });
    expect(up.key).toMatch(/-p6$/);
    expect(up.tone.pitch).toBeCloseTo(1, 5);
    expect(studioSpeaker(plain, "serious", { pitch: -1.5 }).key).toMatch(/-p-1$/);
    expect(studioSpeaker(plain, "serious", { pitch: -0.4 }).key).toBe(studioSpeaker(plain, "serious", {}).key); // too little to hear
    expect(studioSpeaker(plain, undefined, { pitch: 2 }).key).toMatch(/-p2$/);
    expect(studioSpeaker(plain, undefined, { pitch: 2 }).tone.pitch).toBe(2);
  });
});
