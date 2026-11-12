/**
 * Speech in languages beyond English and Hindi. Words are turned into sounds (IPA) by eSpeak NG, which knows the
 * spelling rules, numbers and exceptions of more than a hundred languages; those sounds are then written in the
 * voice model's own alphabet. Spanish, French, Italian and Brazilian Portuguese have voices that were trained in the
 * language; any voice can also speak German, Russian and the others listed here — clearly, with the voice's own accent.
 */
import { splitPunctuation, type Phonemizer } from "./phonemes";

export interface Lang {
  /** Short code used in commands, scripts and films ("de", "ru", "pt"). */
  code: string;
  name: string;
  /** eSpeak NG's name for it. */
  espeak: string;
  /** First letter of the voices trained in this language ("e" for ef_dora …), when there are such voices. */
  prefix?: string;
  /** The voice used when none is named. */
  voice: string;
  /** Every syllable takes about the same time (Spanish, Italian, Hindi …), unlike English, German or Russian. */
  even?: boolean;
  /** Understandable but rough: the voices have not learnt this language's sounds well (checked with a speech recogniser). */
  rough?: boolean;
}

/** Languages a line can be spoken in. English ("en") and Hindi / Hinglish ("hi") have their own, finer rules (not here). */
export const LANGS: readonly Lang[] = [
  { code: "es", name: "Spanish", espeak: "es", prefix: "e", voice: "ef_dora", even: true },
  { code: "fr", name: "French", espeak: "fr-fr", prefix: "f", voice: "ff_siwis", even: true },
  { code: "it", name: "Italian", espeak: "it", prefix: "i", voice: "if_sara", even: true },
  { code: "pt", name: "Portuguese (Brazil)", espeak: "pt-br", prefix: "p", voice: "pf_dora", even: true },
  { code: "de", name: "German", espeak: "de", voice: "bm_george" },
  { code: "ru", name: "Russian", espeak: "ru", voice: "am_onyx" },
  { code: "nl", name: "Dutch", espeak: "nl", voice: "bm_george" },
  { code: "pl", name: "Polish", espeak: "pl", voice: "af_heart", even: true },
  { code: "sv", name: "Swedish", espeak: "sv", voice: "bf_emma", rough: true },
  { code: "id", name: "Indonesian", espeak: "id", voice: "af_heart", even: true, rough: true },
  { code: "ro", name: "Romanian", espeak: "ro", voice: "if_sara", even: true, rough: true },
  { code: "el", name: "Greek", espeak: "el", voice: "ef_dora", even: true, rough: true },
  { code: "mr", name: "Marathi", espeak: "mr", voice: "hf_alpha", even: true, rough: true },
] as const;

const ALIASES: Record<string, string> = { "pt-br": "pt", "fr-fr": "fr", "es-es": "es", "de-de": "de", "ru-ru": "ru", german: "de", russian: "ru", spanish: "es", french: "fr", italian: "it", portuguese: "pt", deutsch: "de" };
/** The language for a code or a name ("de", "German", "pt-BR"), or undefined for English / Hindi / anything unknown. */
export function langOf(code: string | undefined | null): Lang | undefined {
  if (!code) return undefined;
  const c = code.trim().toLowerCase();
  const k = ALIASES[c] ?? c;
  return LANGS.find((l) => l.code === k || l.name.toLowerCase() === k);
}
/** The language a voice was trained in, from its name (ef_dora → Spanish), or undefined (English and Hindi voices). */
export function langOfVoice(voice: string): Lang | undefined {
  const p = voice.trim()[0];
  return /^[efip][fm]_/.test(voice.trim()) ? LANGS.find((l) => l.prefix === p) : undefined;
}
/** The language text is plainly written in, where its letters say so (Cyrillic → Russian or Ukrainian, Greek, …). */
export function langOfText(text: string): Lang | undefined {
  let cyr = 0, grk = 0, lat = 0, n = 0;
  for (const ch of text) {
    const c = ch.codePointAt(0)!;
    if (c >= 0x400 && c <= 0x4ff) cyr++;
    else if (c >= 0x370 && c <= 0x3ff) grk++;
    else if ((c >= 0x41 && c <= 0x5a) || (c >= 0x61 && c <= 0x7a)) lat++;
    else continue;
    n++;
  }
  if (!n) return undefined;
  if (cyr / n > 0.5) return langOf("ru");
  if (grk / n > 0.5) return langOf("el");
  void lat;
  return undefined;
}

/** eSpeak's sounds → the voice model's alphabet (what the model has no letter for is given its nearest one). */
const TO_MODEL: ReadonlyArray<readonly [RegExp, string]> = [
  [/[‍͜͡]/g, ""], // ties
  [/\([a-z-]+\)/g, ""], // language switches
  [/"/g, ""],
  [/ts/g, "ʦ"], [/dz/g, "ʣ"], [/tʃ/g, "ʧ"], [/dʒ/g, "ʤ"], [/tɕ/g, "ʨ"], [/dʑ/g, "ʥ"],
  [/ɜ/g, "ɐ"], [/[ɭɫ]/g, "l"], [/ä/g, "a"], [/o̞/g, "o"], [/e̞/g, "e"], [/ħ/g, "h"], [/ɵ/g, "ø"], [/ɘ/g, "ə"], [/ʉ/g, "u"], [/ɧ/g, "ʃ"], [/ʑ/g, "ʒ"], [/ɦ/g, "h"], [/ʕ/g, ""], [/q/g, "k"], [/ɟ/g, "ɟ"],
  [/-/g, ""],
];
/**
 * Sounds a language needs said differently by voices that never learnt it. Russian soft consonants:
 * the voices have no soft "t", "d", "n" …, but a consonant gliding into the vowel ("tj", "dj") is heard as one,
 * and their unstressed "o" is the open, short "a" of the model, not its "uh" (measured with a speech recogniser).
 */
const BY_LANG: Readonly<Record<string, ReadonlyArray<readonly [RegExp, string]>>> = {
  ru: [[/ʲ(?=[ˈˌ]?[aeiouɑɐɒæɔəɛɪʊʌɨøɵ])/g, "j"], [/ʲ/g, ""], [/ʌ/g, "ɐ"]],
};
export function toModelSounds(ipa: string, lang = ""): string {
  let s = ipa.normalize("NFC");
  // nasal vowels: the model writes them as the vowel and a separate mark
  s = s.replace(/[ãõũẽĩ]/g, (c) => c.normalize("NFD"));
  for (const [re, to] of BY_LANG[lang] ?? []) s = s.replace(re, to);
  for (const [re, to] of TO_MODEL) s = s.replace(re, to);
  return s.replace(/\s+/g, " ").trim();
}

/** Tidy text of any language for speaking: markup gone, quotes and dashes plain, the upside-down marks dropped. */
function tidy(text: string): string {
  return text
    .replace(/<[^>]+>/g, " ")
    .replace(/[*_`#~]+/g, "")
    .replace(/[«»„“”]/g, '"')
    .replace(/[‘’‚]/g, "'")
    .replace(/[¿¡]/g, "")
    .replace(/\s*[–—]\s*/g, " — ")
    .replace(/[।۔]/g, ".")
    .replace(/،/g, ",")
    .replace(/؟/g, "?")
    .replace(/؛/g, ";")
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * Text in a language → the voice model's phonemes, punctuation kept (the model reads it as pauses and melody).
 * `speak(words, espeakName)` is the phonemizer: it returns the sounds of a run of words, one string per clause.
 */
export async function worldPhonemes(text: string, lang: Lang, speak: Phonemizer): Promise<string> {
  const parts: string[] = [];
  // Greek writes its question mark as ";"
  const clean = lang.code === "el" ? tidy(text).replace(/;/g, "?").replace(/·/g, ";") : tidy(text);
  for (const { match, text: t } of splitPunctuation(clean)) {
    if (match) parts.push(t.replace(/\s+/g, " "));
    else if (t.trim()) parts.push(toModelSounds((await speak(t.trim(), lang.espeak)).join(" "), lang.code));
  }
  return parts.join("").replace(/\s+/g, " ").replace(/ ([,.;:!?])/g, "$1").trim();
}
