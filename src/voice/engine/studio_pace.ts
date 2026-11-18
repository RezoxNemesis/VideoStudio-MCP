/**
 * The pace of a line for VolTune Studio. The studio model acts a line as it is written but knows nothing of
 * the tone's pace: given three short sentences it runs them together, and it hurries a long line until its
 * end is swallowed. So a line is spoken a sentence at a time, the sentences are joined with the silences
 * their marks ask for (longer for a tone that pauses), and the speech is held to the tone's pace without
 * changing its pitch.
 */
import { timeStretch } from "./audio";

export interface Pace {
  /** Speed (1 natural; 0.86 is a slow, weighty line). */
  rate?: number;
  /** Lengthens the silences between sentences (1 natural). */
  pauses?: number;
  /** Words a second of the language at an unhurried pace (NATURAL_PACE for English; Hindi has more, shorter words). */
  natural?: number;
}

const TITLES = new Set(["mr", "mrs", "ms", "dr", "st", "sr", "jr", "vs", "etc", "prof", "capt", "col", "gen", "lt", "sgt"]);
const MARK = /[.!?…।॥]/;
const words = (s: string) => s.replace(/[.!?…।॥,;:—–-]+/g, " ").trim().split(/\s+/).filter(Boolean).length;

/** A line as the sentences a studio voice speaks one at a time, each keeping its closing mark. */
export function phrases(text: string): string[] {
  const t = text.trim().replace(/\s+/g, " ");
  const out: string[] = [];
  let from = 0;
  for (let i = 0; i < t.length; i++) {
    if (!MARK.test(t[i]!)) continue;
    let j = i;
    while (j + 1 < t.length && MARK.test(t[j + 1]!)) j++; // a run of marks is one mark ("...", "?!")
    const end = j + 1 >= t.length;
    if (!end && t[j + 1] !== " ") { i = j; continue; } // 3.5, a.m.b — no stop inside a word
    // a title or an initial before a single full stop is no end of a sentence
    const before = /([A-Za-z]+)$/.exec(t.slice(from, i))?.[1] ?? "";
    if (!end && t[i] === "." && j === i && (TITLES.has(before.toLowerCase()) || (before.length === 1 && before === before.toUpperCase()))) continue;
    out.push(t.slice(from, j + 1).trim());
    from = j + 1;
    i = j;
  }
  if (from < t.length && t.slice(from).trim()) out.push(t.slice(from).trim());
  // a word alone goes with what follows it (or, at the end, with what came before): one word is too little to act
  for (let k = 0; k < out.length && out.length > 1; ) {
    if (words(out[k]!) > 1) { k++; continue; }
    if (k + 1 < out.length) out.splice(k, 2, `${out[k]} ${out[k + 1]}`);
    else { out.splice(k - 1, 2, `${out[k - 1]} ${out[k]}`); break; }
  }
  return out;
}

/** Seconds of silence after a sentence: by its closing mark, longer for a tone that pauses or goes slowly. */
export function pauseAfter(phrase: string, tone: Pace = {}): number {
  const p = phrase.trim();
  const base = /(…|\.\.\.)$/.test(p) ? 0.6 : /\?$/.test(p) ? 0.42 : /!$/.test(p) ? 0.36 : /[.।॥]$/.test(p) ? 0.36 : 0.28;
  return (base * (tone.pauses ?? 1)) / rateOf(tone);
}
const rateOf = (tone: Pace) => Math.max(0.6, Math.min(1.4, tone.rate ?? 1));

/**
 * A spoken sentence without a stray sound after its last word: the model sometimes adds a breath or an "ah" after a
 * silence. What follows a silence of a quarter second or more and is shorter than 0.35 s is no word: it is cut off.
 */
export function tidyEnd(pcm: Float32Array, sr = 24000): Float32Array {
  const hop = Math.round(sr * 0.02), frames = Math.floor(pcm.length / hop);
  if (frames < 20) return pcm;
  const e = new Float32Array(frames);
  let peak = 0;
  for (let f = 0; f < frames; f++) {
    let sum = 0;
    for (let i = f * hop; i < (f + 1) * hop; i++) sum += pcm[i]! * pcm[i]!;
    e[f] = Math.sqrt(sum / hop);
    peak = Math.max(peak, e[f]!);
  }
  const loud = (f: number) => e[f]! > peak * 0.04;
  let end = frames - 1;
  while (end > 0 && !loud(end)) end--; // the last sound
  let start = end;
  while (start > 0 && loud(start - 1)) start--; // where that sound begins
  let gap = 0;
  for (let f = start - 1; f >= 0 && !loud(f); f--) gap++; // the silence before it
  const stray = (end - start + 1) * 0.02 < 0.35 && gap * 0.02 >= 0.25 && start - gap > 0;
  if (!stray) return pcm;
  return pcm.slice(0, Math.min(pcm.length, (start - gap + 4) * hop)); // keep a breath of the silence after the real last word
}

/**
 * A spoken sentence without the model's own silence around it: from where its first sound begins to where its
 * last sound has died away. The pauses of a line are the tone's to set, and a faint breath after the end is no
 * part of the sentence. A sound counts from 4 % of the loudest; its quiet beginning and its dying end are
 * followed down to 0.3 %.
 */
export function trimmed(pcm: Float32Array, sr = 24000): Float32Array {
  const b = bounds(pcm, sr);
  return b.from === 0 && b.to === pcm.length ? pcm : pcm.slice(b.from, b.to);
}
/** Where a sentence's sound lies in its samples, with the quiet (seconds) kept before and after it. */
function bounds(pcm: Float32Array, sr: number): { from: number; to: number; lead: number; tail: number } {
  const hop = Math.round(sr * 0.02), frames = Math.floor(pcm.length / hop), whole = { from: 0, to: pcm.length, lead: 0, tail: 0 };
  if (frames < 6) return whole;
  const e = new Float32Array(frames);
  let peak = 0;
  for (let f = 0; f < frames; f++) {
    let sum = 0;
    for (let i = f * hop; i < (f + 1) * hop; i++) sum += pcm[i]! * pcm[i]!;
    e[f] = Math.sqrt(sum / hop);
    peak = Math.max(peak, e[f]!);
  }
  if (!(peak > 0)) return whole;
  let a = 0, b = frames - 1;
  while (a < b && e[a]! <= peak * 0.04) a++;
  while (b > a && e[b]! <= peak * 0.04) b--;
  while (a > 0 && e[a - 1]! > peak * 0.003) a--;
  while (b < frames - 1 && e[b + 1]! > peak * 0.003) b++;
  // a little quiet is kept on either side, so no sound is clipped
  const from = Math.max(0, a * hop - Math.round(sr * 0.02)), to = Math.min(pcm.length, (b + 1) * hop + Math.round(sr * 0.04));
  return { from, to, lead: (a * hop - from) / sr, tail: (to - (b + 1) * hop) / sr };
}

/** Words a second of unhurried speech (a tone's rate of 1); a slow, weighty line of 0.86 is about 2.6. */
export const NATURAL_PACE = 3;
/**
 * Hindi and Hinglish: short words (है, के, पर, तो) make more of them a second at the same unhurried pace. Measured on
 * the studio's real Hindi readers and on the V3 voice: 3.5–4.2 a second sounds calm; held to 3, every Hindi line was
 * stretched 12–22 % and sounded drawn out.
 */
export const NATURAL_PACE_HI = 3.9;
/** The most a line is ever drawn out: beyond a quarter the stretch is heard (a smeared, slowed voice). */
export const MAX_STRETCH = 1.25;

/**
 * How much longer (or shorter) the speech must be made to sit at the tone's pace. The model does not keep a
 * pace of its own accord: it hurries some lines to five or six words a second. A hurried line is held back
 * (never by more than MAX_STRETCH: beyond that it sounds drawn out), a dawdling one is brought up a little (never
 * below 0.85: speech squeezed further sounds rushed), and one that is within a few percent is left alone.
 * A word or two is too little to measure a pace on: there only the tone's own rate applies.
 */
export function paceFactor(seconds: number, wordCount: number, tone: Pace = {}): number {
  const k = paceAsked(seconds, wordCount, tone);
  return wordCount < 3 || seconds <= 0 ? k : Math.abs(k - 1) < 0.08 ? 1 : Math.max(0.85, Math.min(MAX_STRETCH, k));
}
/** How far the speech would have to be stretched to sit exactly at the tone's pace (not limited to what sounds well). */
export function paceAsked(seconds: number, wordCount: number, tone: Pace = {}): number {
  const rate = rateOf(tone);
  return wordCount < 3 || seconds <= 0 ? 1 / rate : wordCount / seconds / ((tone.natural ?? NATURAL_PACE) * rate);
}

/** Spoken sentences joined with their silences, the speech brought to the tone's pace (pitch kept). */
export function paced(parts: Float32Array[], texts: string[], tone: Pace = {}, sr = 24000): Float32Array {
  return pacing(parts, texts, tone, sr).pcm;
}
/** `paced`, with how the line was spoken: its words, the seconds of speech the model gave, the stretch the pace asked for and the one it got. */
export function pacing(parts: Float32Array[], texts: string[], tone: Pace = {}, sr = 24000): { pcm: Float32Array; words: number; spoken: number; asked: number; factor: number } {
  let quiet = parts.map(() => ({ lead: 0, tail: 0 }));
  parts = parts.map((p, i) => {
    const t = tidyEnd(p, sr), b = bounds(t, sr);
    quiet[i] = { lead: b.lead, tail: b.tail };
    return b.from === 0 && b.to === t.length ? t : t.slice(b.from, b.to);
  });
  const spoken = parts.reduce((n, p) => n + p.length, 0) / sr, count = texts.reduce((n, t) => n + words(t), 0);
  const k = paceFactor(spoken, count, tone);
  if (Math.abs(k - 1) > 0.02) { parts = parts.map((p) => timeStretch(p, k, sr)); quiet = quiet.map((q) => ({ lead: q.lead * k, tail: q.tail * k })); }
  // the quiet kept around each sentence is part of the pause between two of them
  const gaps = parts.map((_, i) => (i < parts.length - 1 ? Math.round(Math.max(0.05, pauseAfter(texts[i] ?? "", tone) - quiet[i]!.tail - quiet[i + 1]!.lead) * sr) : 0));
  const all = new Float32Array(parts.reduce((n, p, i) => n + p.length + gaps[i]!, 0));
  const fade = Math.round(sr * 0.005);
  let o = 0;
  parts.forEach((p, i) => {
    all.set(p, o);
    // a sentence begins and ends without a click
    for (let j = 0; j < Math.min(fade, p.length >> 1); j++) { all[o + j]! *= j / fade; all[o + p.length - 1 - j]! *= j / fade; }
    o += p.length + gaps[i]!;
  });
  const r3 = (v: number) => Math.round(v * 1000) / 1000;
  return { pcm: all, words: count, spoken: r3(spoken), asked: r3(paceAsked(spoken, count, tone)), factor: r3(Math.abs(k - 1) > 0.02 ? k : 1) };
}
