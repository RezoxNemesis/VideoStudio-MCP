import { describe, expect, it } from "vitest";
import { BANK, bankVoice } from "../src/voice/engine/studio_bank";

describe("the real readers studio voices are spoken from (voice bank)", () => {
  it("chooses a reader of the voice's language and gender", () => {
    const maa = bankVoice("h", true, 0, "hf_beta");
    expect(maa?.lang).toBe("hi");
    expect(maa?.female).toBe(true);
    const narrator = bankVoice("b", false, 0, "bm_fable");
    expect(narrator?.lang).toBe("en");
    expect(narrator?.female).toBe(false);
  });

  it("follows the voice's register: a deep voice gets a deep reader", () => {
    const deep = bankVoice("a", false, -4, "am_onyx")!;
    const light = bankVoice("a", false, 2, "am_puck")!;
    expect(deep.f0).toBeLessThan(light.f0);
  });

  it("keeps two voices of the same kind apart by their recipe, and the same voice on the same reader", () => {
    const picks = new Set(["af_heart", "af_bella", "af_nicole", "af_sky", "af_river", "af_sarah"].map((m) => bankVoice("a", true, 0, m)?.file));
    expect(picks.size).toBeGreaterThan(1);
    expect(bankVoice("a", true, 0, "af_heart")).toEqual(bankVoice("a", true, 0, "af_heart"));
  });

  it("leaves children and languages without readers to their VolTune clip", () => {
    expect(bankVoice("a", false, 4.5, "am_puck*0.6+af_sky*0.4")).toBeNull();
    expect(bankVoice("e", true, 0, "ef_dora")).toBeNull();
  });

  it("names every reader's source", () => {
    for (const v of BANK) expect(v.credit.length).toBeGreaterThan(10);
  });
});
