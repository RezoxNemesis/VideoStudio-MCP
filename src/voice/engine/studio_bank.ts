/**
 * Real voices for VolTune Studio. Chatterbox takes the whole sound of a voice from its reference clip: its timbre,
 * its breath, its rhythm. A clip spoken by Kokoro gave every studio line Kokoro's flat, synthetic colour, however well
 * the line was acted. These are short clips of real people reading aloud (public-domain LibriVox recordings and
 * LibriTTS-R, CC BY 4.0; see desktop/voice/refs/CREDITS.md), so a studio voice now sounds like a person.
 *
 * A VolTune voice keeps its own identity through the bank: its language and gender choose the readers, its pitch (the
 * voice's own plus what a line asks for) chooses the nearest reader in register, and its recipe chooses among readers
 * close in register, so two different female Hindi voices stay two different women. A voice far above every reader
 * of its kind (a child) keeps its VolTune reference.
 */

export interface BankVoice {
  /** File name in the bank folder (desktop/voice/refs/<file>.wav, 24 kHz mono PCM). */
  file: string;
  lang: "hi" | "en";
  female: boolean;
  /** Median pitch of the clip (Hz). */
  f0: number;
  /** Who reads it, and where it comes from. */
  credit: string;
}

export const BANK: readonly BankVoice[] = [
  // Hindi
  { file: "hi_f_dc", lang: "hi", female: true, f0: 211, credit: "dc, Panchatantra (LibriVox, public domain)" },
  { file: "hi_f_sonali", lang: "hi", female: true, f0: 231, credit: "Sonali Ekka, Do Sakhiyan (LibriVox, public domain)" },
  { file: "hi_f_pratibha", lang: "hi", female: true, f0: 185, credit: "pratibhanair, Kshama (LibriVox, public domain)" },
  { file: "hi_m_shashank", lang: "hi", female: false, f0: 117, credit: "Shashank Jakhmola, UDHR in Hindi (LibriVox, public domain)" },
  { file: "hi_m_shashank2", lang: "hi", female: false, f0: 132, credit: "Shashank Jakhmola, UDHR in Hindi (LibriVox, public domain)" },
  // English
  { file: "en_m_1188", lang: "en", female: false, f0: 103, credit: "LibriTTS-R speaker 1188 (CC BY 4.0)" },
  { file: "en_m_7127", lang: "en", female: false, f0: 126, credit: "LibriTTS-R speaker 7127 (CC BY 4.0)" },
  { file: "en_m_8224", lang: "en", female: false, f0: 139, credit: "LibriTTS-R speaker 8224 (CC BY 4.0)" },
  { file: "en_f_4446", lang: "en", female: true, f0: 188, credit: "LibriTTS-R speaker 4446 (CC BY 4.0)" },
  { file: "en_f_237", lang: "en", female: true, f0: 200, credit: "LibriTTS-R speaker 237 (CC BY 4.0)" },
  { file: "en_f_4992", lang: "en", female: true, f0: 207, credit: "LibriTTS-R speaker 4992 (CC BY 4.0)" },
  { file: "en_f_5683", lang: "en", female: true, f0: 235, credit: "LibriTTS-R speaker 5683 (CC BY 4.0)" },
];

/** The usual speaking pitch (Hz) of a voice of each kind before its own pitch shift. */
const BASE_F0 = { female: 205, male: 120 } as const;
/** Readers this close in register (semitones) to the nearest one are all fit for the voice. */
const NEAR = 1.6;
/** Further than this from every reader of its kind, a voice keeps its VolTune reference. */
const FAR = 4;
/** Semitones above the usual register from which a voice is a child's. */
const CHILD = 3;

const semis = (a: number, b: number) => 12 * Math.log2(a / b);
function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

/**
 * The real reader a VolTune voice is spoken from, or null when none fits (a language with no readers, a child's
 * register). lang: the voice's language letter ("h" Hindi, "a"/"b" English); pitch: semitones above the voice's
 * usual register (its own pitch and the line's together); recipe: the voice's mix (decides among close readers).
 */
export function bankVoice(lang: string, female: boolean, pitch: number, recipe: string): BankVoice | null {
  const want = lang === "h" ? "hi" : lang === "a" || lang === "b" ? "en" : null;
  if (!want) return null;
  const kind = BANK.filter((v) => v.lang === want && v.female === female);
  // a voice raised this far is a child's (a boy is a man's voice +4.5): no grown reader may speak it
  if (!kind.length || pitch >= CHILD) return null;
  const target = (female ? BASE_F0.female : BASE_F0.male) * 2 ** (pitch / 12);
  const dist = (v: BankVoice) => Math.abs(semis(v.f0, target));
  const best = Math.min(...kind.map(dist));
  if (best > FAR) return null;
  const near = kind.filter((v) => dist(v) <= best + NEAR).sort((a, b) => a.file.localeCompare(b.file));
  return near[hash(recipe) % near.length]!;
}

/** The speaker key part that names a bank reader (so a change of reader is a new speaker). */
export const bankKey = (v: BankVoice) => `b${v.file}`;
