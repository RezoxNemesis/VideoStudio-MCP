import { describe, expect, it } from "vitest";
import { hinglishShare, indianEnglish, romanToDevanagari, transliterateHinglish, wordKind } from "../src/voice/engine/hinglish";
import { voiceForText } from "../src/voice/engine/plan";

describe("Hinglish (Hindi typed in English letters)", () => {
  it("tells Hindi words from English ones", () => {
    expect(wordKind("nahi")).toBe("hindi");
    expect(wordKind("meeting")).toBe("english");
    expect(wordKind("deadline")).toBe("english");
    expect(wordKind("hi")).toBe("either");
  });
  it("measures how Hindi a line is", () => {
    expect(hinglishShare("kal milte hain")).toBeGreaterThan(0.6);
    expect(hinglishShare("Arre yaar, kal meeting hai aur maine presentation ready nahi ki")).toBeGreaterThan(0.34);
    expect(hinglishShare("Good morning everyone")).toBe(0);
    expect(hinglishShare("Please send the report before the deadline")).toBeLessThan(0.2);
  });
  it("does not take a line of words that are also English for Hindi", () => {
    // "hi", "to", "so", "do" are Hindi words too, but with no clearly Hindi word around the line is English
    expect(hinglishShare("Hi.")).toBe(0);
    expect(hinglishShare("Hi to all")).toBe(0);
    expect(hinglishShare("So do it")).toBe(0);
    expect(voiceForText("bm_fable", "Hi.")).toBe("bm_fable");
    expect(voiceForText("af_heart", "Hello. Hi to all of you.")).toBe("af_heart");
  });
  it("writes the Hindi words in Devanagari and leaves the English ones", () => {
    expect(transliterateHinglish("Kal meeting hai, tum aa rahe ho na?")).toBe("कल meeting है, तुम आ रहे हो ना?");
    expect(transliterateHinglish("Mujhe office mein bahut kaam hai")).toBe("मुझे office में बहुत काम है");
    expect(transliterateHinglish("Good morning everyone")).toBe("Good morning everyone");
  });
  it("inflects verbs from their roots", () => {
    expect(romanToDevanagari("milte", true)).toBe("मिलते");
    expect(romanToDevanagari("karunga", true)).toBe("करूँगा");
    expect(romanToDevanagari("jaayenge", true)).toBe("जाएँगे");
  });
  it("sends Hinglish to a Hindi voice and keeps a Hindi voice on English lines", () => {
    expect(voiceForText("af_heart", "Kal milte hain dost")).toBe("hf_alpha");
    expect(voiceForText("bm_george", "Tum kahan ja rahe ho?")).toBe("hm_omega");
    expect(voiceForText("hf_beta", "Good morning everyone")).toBe("hf_beta");
  });
  it("gives English words Indian sounds for Hindi voices", () => {
    // retroflex stops for t and d, "v" for w, the American flap in "water" becomes a clear t, r is tapped
    expect(indianEnglish("wˈɔːɾəɹ", "water")).toBe("ʋɔːʈəɾ");
    expect(indianEnglish("tˈɛn", "ten")).toContain("ʈ");
    expect(indianEnglish("dˈeɪ", "day")).toContain("ɖ");
  });
});
