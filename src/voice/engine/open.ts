/**
 * Kokoro with its prosody opened up. The stock model goes straight from phonemes to sound; here it is split into
 * three steps (tools/voice/split_kokoro.py) so the timing and the pitch can be read and changed in between:
 *
 *   durations  how many frames each phoneme lasts (40 frames = 1 s)
 *   prosody    the voice's own melody (f0, Hz) and loudness curve for those durations (80 values = 1 s)
 *   render     sound for whatever durations, melody and loudness it is given
 *
 * Tone (a question's rise, emphasis, a mood) is a reshaping of those curves; singing replaces them outright.
 */
import { MAX_TOKENS, tokenize } from "./phonemes";
import { styleRow } from "./voicebank";

/** The part of onnxruntime (web or node) this needs. */
export interface OrtLike {
  Tensor: new (type: string, data: unknown, dims: number[]) => unknown;
}
export interface SessionLike {
  run(feeds: Record<string, unknown>): Promise<Record<string, { data: unknown }>>;
}
export const FRAME_RATE = 40;
export const CURVE_RATE = 80;

export class OpenModel {
  constructor(private ort: OrtLike, private s: { dur: SessionLike; pros: SessionLike; voc: SessionLike }) {}

  private ids(ids: number[]): unknown {
    return new this.ort.Tensor("int64", BigInt64Array.from(ids, (n) => BigInt(n)), [1, ids.length]);
  }
  private style(st: Float32Array): unknown {
    return new this.ort.Tensor("float32", st, [1, st.length]);
  }
  /** Natural length of every token in frames (not rounded). */
  async durations(ids: number[], style: Float32Array, speed = 1): Promise<Float32Array> {
    const out = await this.s.dur.run({ tokens: this.ids(ids), style: this.style(style), speed: new this.ort.Tensor("float32", new Float32Array([speed]), [1]) });
    return Float32Array.from(out.frames!.data as Float32Array);
  }
  /** The voice's own pitch (Hz; near zero where unvoiced) and loudness curves for these durations. */
  async prosody(ids: number[], style: Float32Array, dur: ArrayLike<number>): Promise<{ f0: Float32Array; energy: Float32Array }> {
    const d = new this.ort.Tensor("int64", BigInt64Array.from(dur as number[], (n) => BigInt(Math.max(1, Math.round(n)))), [1, dur.length]);
    const out = await this.s.pros.run({ tokens: this.ids(ids), style: this.style(style), durations: d });
    return { f0: Float32Array.from(out.f0!.data as Float32Array), energy: Float32Array.from(out.energy!.data as Float32Array) };
  }
  /** Sound (24 kHz mono) for the given durations, pitch and loudness curves. */
  async render(ids: number[], style: Float32Array, dur: ArrayLike<number>, f0: Float32Array, energy: Float32Array): Promise<Float32Array> {
    const d = new this.ort.Tensor("int64", BigInt64Array.from(dur as number[], (n) => BigInt(Math.max(1, Math.round(n)))), [1, dur.length]);
    const out = await this.s.voc.run({ tokens: this.ids(ids), style: this.style(style), durations: d, f0: new this.ort.Tensor("float32", f0, [1, 1, f0.length]), energy: new this.ort.Tensor("float32", energy, [1, 1, energy.length]) });
    return Float32Array.from(out.audio!.data as Float32Array);
  }
}

/** How a line is said. Every field is optional; 0 or 1 means "as the voice would say it". */
export interface Tone {
  /** Semitones up or down for the whole line. */
  pitch?: number;
  /** How wide the melody swings: 1 natural, 0.5 flat, 1.6 animated. */
  range?: number;
  /** Speaking rate: 1 natural, 0.85 slower. */
  rate?: number;
  /** Loudness: -1 hushed … 1 forceful. */
  energy?: number;
  /** End of the line: "rise" (a question), "fall" (final, firm), "level" (unfinished). */
  ending?: "rise" | "fall" | "level";
  /** Evenness of syllables: 1 gives every syllable nearly the same length (the rhythm of Hindi and Indian English). */
  even?: number;
  /** Small trembling of the pitch (fear, old age), 0…1. */
  shake?: number;
  /** Words to lean on: [first token index, last token index] ranges (see `emphasisRanges`). */
  stress?: [number, number][];
  /** Lengthen the pauses at commas and stops. */
  pauses?: number;
}

/** Named tones. `tone: "sad"` in a speak request picks one; fields given beside it override. */
export const TONES: Record<string, Tone> = {
  neutral: {},
  warm: { pitch: -0.5, range: 1.1, rate: 0.96, energy: -0.1 },
  happy: { pitch: 1.5, range: 1.35, rate: 1.05, energy: 0.3 },
  excited: { pitch: 2.5, range: 1.6, rate: 1.12, energy: 0.6 },
  sad: { pitch: -2, range: 0.6, rate: 0.86, energy: -0.45, ending: "fall", pauses: 1.3 },
  angry: { pitch: 0.5, range: 1.45, rate: 1.06, energy: 0.9, ending: "fall" },
  stern: { pitch: -1.5, range: 0.8, rate: 0.93, energy: 0.4, ending: "fall" },
  calm: { pitch: -1, range: 0.75, rate: 0.92, energy: -0.2 },
  afraid: { pitch: 2.5, range: 1.2, rate: 1.14, energy: -0.1, shake: 0.7 },
  tired: { pitch: -2.5, range: 0.55, rate: 0.82, energy: -0.6, ending: "fall", pauses: 1.4 },
  question: { range: 1.15, ending: "rise" },
  whispery: { pitch: -1, range: 0.5, rate: 0.9, energy: -0.9 },
  commanding: { pitch: -1, range: 1.2, rate: 0.95, energy: 0.8, ending: "fall" },
  playful: { pitch: 2, range: 1.5, rate: 1.04, energy: 0.2 },
  indian: { even: 0.6, range: 1.15 },
};

const VOWELS = new Set([..."aeiouɑɐɒæɔəɚɛɜɪʊʌɨøœɯɤyAIOWYᵻᵊ"]);
const PAUSE = new Set([..." ,.;:!?—…"]);

/** Token ranges of the given words inside a phoneme string that was built word by word (same word order). */
export function emphasisRanges(phonemes: string, wordIndexes: number[]): [number, number][] {
  const out: [number, number][] = [];
  let w = 0, start = -1, tok = 1;
  const want = new Set(wordIndexes);
  for (const ch of phonemes) {
    const isSpace = ch === " ";
    if (!isSpace && start < 0) start = tok;
    if (isSpace && start >= 0) {
      if (want.has(w)) out.push([start, tok - 1]);
      w++;
      start = -1;
    }
    tok++;
  }
  if (start >= 0 && want.has(w)) out.push([start, tok - 1]);
  return out;
}

/** Say phonemes with a tone: natural timing and melody from the voice, reshaped, then rendered. */
export async function sayOpen(m: OpenModel, table: Float32Array, phonemes: string, tone: Tone = {}): Promise<Float32Array> {
  let ids = tokenize(phonemes);
  if (ids.length > MAX_TOKENS + 2) ids = [...ids.slice(0, MAX_TOKENS + 1), 0];
  const chars = [...phonemes].filter((c) => tokenize(c).length === 3);
  const style = styleRow(table, ids.length);
  const nat = await m.durations(ids, style, tone.rate ?? 1);
  const dur = Array.from(nat, (d) => Math.max(1, d));
  const n = ids.length;
  // evenness: pull vowel lengths towards their average
  if (tone.even) {
    const vi: number[] = [];
    for (let i = 1; i < n - 1; i++) if (VOWELS.has(chars[i - 1] ?? "")) vi.push(i);
    if (vi.length > 2) {
      const mean = vi.reduce((a, i) => a + dur[i]!, 0) / vi.length;
      for (const i of vi) dur[i] = dur[i]! + (mean - dur[i]!) * Math.min(1, tone.even) * 0.7;
    }
  }
  if (tone.pauses && tone.pauses !== 1) for (let i = 1; i < n - 1; i++) if (PAUSE.has(chars[i - 1] ?? "") && chars[i - 1] !== " ") dur[i] = dur[i]! * tone.pauses;
  for (const [a, b] of tone.stress ?? []) for (let i = a; i <= b && i < n - 1; i++) if (VOWELS.has(chars[i - 1] ?? "") || chars[i - 1] === "ː") dur[i] = dur[i]! * 1.3;
  // the opening pad stays as long as the voice wants it: the first sound forms inside it, and cutting it short
  // swallows the first word. The silence is trimmed from the audio afterwards (see `polish`).
  const di = dur.map((d) => Math.max(1, Math.round(d)));
  const { f0, energy } = await m.prosody(ids, style, di);
  // frame positions of each token on the curves
  const start: number[] = [];
  let acc = 0;
  for (const d of di) {
    start.push(acc * 2);
    acc += d;
  }
  const T = f0.length;
  // the line's own centre and spread, in log pitch, over the voiced frames
  let sum = 0, cnt = 0;
  for (let i = 0; i < T; i++) if (f0[i]! > 40) {
    sum += Math.log(f0[i]!);
    cnt++;
  }
  const centre = cnt ? sum / cnt : Math.log(200);
  const st = (tone.pitch ?? 0) / 12 * Math.LN2;
  const range = tone.range ?? 1;
  let lastVoiced = T - 1;
  while (lastVoiced > 0 && f0[lastVoiced]! <= 40) lastVoiced--;
  const tail = Math.max(8, Math.min(Math.round(CURVE_RATE * 0.55), Math.round(lastVoiced * 0.35)));
  for (let i = 0; i < T; i++) {
    if (f0[i]! <= 40) continue;
    let lg = centre + (Math.log(f0[i]!) - centre) * range + st;
    const k = (i - (lastVoiced - tail)) / tail;
    if (k > 0 && tone.ending) {
      const e = Math.min(1, k);
      if (tone.ending === "rise") lg += e * e * (5.5 / 12) * Math.LN2;
      else if (tone.ending === "fall") lg -= e * (3 / 12) * Math.LN2;
      else lg = lg + (centre + st - lg) * e * 0.6;
    }
    if (tone.shake) lg += Math.sin(i * 0.92) * Math.sin(i * 0.37 + 1.3) * 0.018 * tone.shake;
    f0[i] = Math.exp(lg);
  }
  for (const [a, b] of tone.stress ?? []) {
    const i0 = start[a] ?? 0, i1 = (start[b + 1] ?? T) - 1;
    for (let i = i0; i <= i1 && i < T; i++) {
      const u = (i - i0) / Math.max(1, i1 - i0);
      const bump = Math.sin(Math.PI * u);
      if (f0[i]! > 40) f0[i] = f0[i]! * Math.pow(2, (2.6 * bump) / 12);
      if (energy[i]! > 0) energy[i] = energy[i]! + 0.9 * bump;
    }
  }
  if (tone.energy) for (let i = 0; i < T; i++) if (energy[i]! > 0) energy[i] = Math.max(0.5, energy[i]! + tone.energy * 1.6);
  return m.render(ids, style, di, f0, energy);
}
