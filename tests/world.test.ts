import { describe, expect, it } from "vitest";
import { espeakIpa } from "../src/voice/engine/espeak";
import { buildPlan } from "../src/voice/engine/plan";
import { phonemesFor, worldLang } from "../src/voice/engine/synth";
import { VOCAB } from "../src/voice/engine/phonemes";
import { VOICES } from "../src/voice/engine/voices";
import { PRESETS, parseMix } from "../src/voice/engine/voicebank";
import { LANGS, langOf, langOfText, langOfVoice, toModelSounds } from "../src/voice/engine/world";

const speak = (t: string, l: string) => espeakIpa(t, l === "en" ? "en-gb" : l);

describe("other languages", () => {
  it("finds a language by code, name or regional code", () => {
    expect(langOf("de")?.name).toBe("German");
    expect(langOf("German")?.code).toBe("de");
    expect(langOf("pt-BR")?.code).toBe("pt");
    expect(langOf("en")).toBeUndefined();
    expect(langOf("hi")).toBeUndefined();
    expect(langOf(undefined)).toBeUndefined();
  });
  it("knows the language a voice was trained in", () => {
    expect(langOfVoice("ef_dora")?.code).toBe("es");
    expect(langOfVoice("ff_siwis")?.code).toBe("fr");
    expect(langOfVoice("pm_alex")?.code).toBe("pt");
    expect(langOfVoice("af_heart")).toBeUndefined();
    expect(langOfVoice("hf_alpha")).toBeUndefined();
  });
  it("reads the language off the letters only when they are plain", () => {
    expect(langOfText("Король возвращается")?.code).toBe("ru");
    expect(langOfText("Καλημέρα")?.code).toBe("el");
    expect(langOfText("The king returns")).toBeUndefined();
  });
  it("keeps English and Hindi on their own rules", () => {
    expect(worldLang("Guten Morgen", "bm_george", "de")?.code).toBe("de");
    expect(worldLang("Good morning", "bm_george")).toBeUndefined();
    expect(worldLang("Good morning", "ef_dora", "en")).toBeUndefined();
    expect(worldLang("Buenos días", "ef_dora")?.code).toBe("es");
    expect(worldLang("नमस्ते", "hf_alpha")).toBeUndefined();
  });
  it("writes sounds in the model's alphabet", () => {
    expect(toModelSounds("ts dz tʃ dʒ")).toBe("ʦ ʣ ʧ ʤ");
    // nasal vowels are a vowel and a separate mark, which the model knows
    for (const ch of toModelSounds("ãõ")) expect(VOCAB[ch]).toBeDefined();
    // Russian soft consonants glide into the vowel; elsewhere the mark goes
    expect(toModelSounds("tʲibʲˈɑ", "ru")).toBe("tjibjˈɑ");
    expect(toModelSounds("dʲˈesʲʌtʲ", "ru")).toBe("djˈesjɐt");
  });
  it("speaks German, Russian and Spanish with sounds the model has", async () => {
    for (const [lang, text, voice] of [["de", "Guten Morgen, wie geht es dir?", "bm_george"], ["ru", "Доброе утро, как дела?", "am_onyx"], ["es", "Buenos días, ¿cómo estás?", "ef_dora"], ["fr", "Bonjour, comment vas-tu ?", "ff_siwis"]] as const) {
      const ph = await phonemesFor(text, voice, speak as never, lang);
      expect(ph.length).toBeGreaterThan(10);
      const unknown = [...ph].filter((c) => VOCAB[c] === undefined && c !== "̪");
      expect(unknown, `${lang}: ${ph}`).toEqual([]);
      expect(ph).toMatch(/[,?]/);
    }
  });
  it("reads numbers by the language's own rules", async () => {
    const ph = await phonemesFor("10 Jahre", "bm_george", speak as never, "de");
    expect(ph).toContain("ʦ"); // "zehn"
  });
  it("plans a script in another language without the English treatment", () => {
    const { items } = buildPlan("Ich habe 10 Jahre gewartet. Dr. Weber kommt.", { mode: "speak", speed: 1, pauseScale: 1, voice: "bm_george", cast: {}, lang: "de" });
    expect(items.map((i) => i.text).join(" ")).toContain("10 Jahre");
    expect(items.every((i) => i.lang === "de" && i.voice === "bm_george")).toBe(true);
    const en = buildPlan("I waited 10 years.", { mode: "speak", speed: 1, pauseScale: 1, voice: "bm_george", cast: {} });
    expect(en.items[0]!.text).toContain("ten years");
    expect(en.items[0]!.lang).toBeUndefined();
  });
  it("every language has a voice that exists, and every character voice is a blend of real voices", () => {
    const ids = new Set(VOICES.map((v) => v.id));
    for (const l of LANGS) expect(ids.has(l.voice), l.code).toBe(true);
    for (const p of PRESETS) for (const [id] of parseMix(p.recipe.mix.map(([v, w]) => `${v}*${w}`).join("+"))) expect(ids.has(id), `${p.id}: ${id}`).toBe(true);
    expect(VOICES.length + PRESETS.length).toBeGreaterThanOrEqual(60);
  });
});
