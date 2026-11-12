/**
 * Singing. A voice sings when its vowels are held for the length of the notes and its pitch follows the tune instead
 * of the sentence. With the model's timing and pitch open (open.ts) that is exactly what is set here: every syllable's
 * vowel starts on its note, consonants borrow a little time from the note before, the pitch glides from note to note
 * and trembles on held ones, and the loudness is held up so long notes do not fade the way speech does.
 */
import { CURVE_RATE, FRAME_RATE, type OpenModel } from "./open";
import { tokenize } from "./phonemes";
import { styleRow } from "./voicebank";

const VOWELS = new Set([..."aeiouɑɐɒæɔəɚɛɜɪʊʌɨøœɯɤyAIOWYᵻᵊ"]);
const LONG_MARK = "ː", NASAL = "̃";
const STRESS = new Set(["ˈ", "ˌ"]);
const GLIDES = new Set([..."jɾlʋwɹr"]);
const hz = (midi: number) => 440 * Math.pow(2, (midi - 69) / 12);
/**
 * Measured on the model: the sound of a phoneme comes out about 60 ms before its place on the frame grid, loudness
 * about 30 ms before, while the pitch curve is heard exactly where it is written. So pitch and loudness are written
 * that much early (to meet the phonemes), and the finished phrase is laid 60 ms late (so vowels land on the beat).
 */
const PHONE_LEAD = 0.06, ENERGY_LEAD = 0.03;

/** The comfortable sung range of each kind of voice (MIDI): above the top the model's pitch breaks. */
export function singRange(voice: string): [number, number] {
  const v = voice.split(/[+*]/)[0]!.trim();
  if (/^.m_/.test(v)) return [43, 60];
  return [52, 64];
}

/** Split one word's phonemes into syllables: each has one vowel core; consonants lean towards the next vowel. */
export function syllabify(word: string): string[] {
  const ch = [...word];
  // nuclei: a vowel with its marks; a long vowel followed by another vowel starts a new syllable (kʰaː·iː)
  const isV = (c: string | undefined) => !!c && VOWELS.has(c);
  const cores: [number, number][] = [];
  let i = 0;
  while (i < ch.length) {
    if (!isV(ch[i])) {
      i++;
      continue;
    }
    let j = i + 1;
    let long = false;
    while (j < ch.length) {
      const c = ch[j]!;
      if (c === LONG_MARK) {
        long = true;
        j++;
      } else if (c === NASAL) j++;
      else if (isV(c) && !long && j - i < 2) j++;
      else break;
    }
    cores.push([i, j]);
    i = j;
  }
  if (!cores.length) return word ? [word] : [];
  // where each later syllable begins: the last consonant before its vowel (with its aspiration, or a stop + glide
  // pair such as "pɾ", "kj"), and any stress mark in front of that
  const cuts: number[] = [];
  for (let k = 0; k < cores.length - 1; k++) {
    const e = cores[k]![1], nx = cores[k + 1]![0];
    let idx = nx - 1;
    while (idx >= e && STRESS.has(ch[idx]!)) idx--;
    let start = idx + 1;
    if (idx >= e) {
      start = idx;
      if (ch[start] === "ʰ" && start - 1 >= e) start--;
      if (GLIDES.has(ch[start]!) && start - 1 >= e && !GLIDES.has(ch[start - 1]!) && !STRESS.has(ch[start - 1]!) && ch[start - 1] !== "ʰ" && start - 2 >= e) start--;
      while (start - 1 >= e && STRESS.has(ch[start - 1]!)) start--;
    }
    cuts.push(start);
  }
  const out: string[] = [];
  let from = 0;
  for (const c of cuts) {
    out.push(ch.slice(from, c).join(""));
    from = c;
  }
  out.push(ch.slice(from).join(""));
  return out.filter(Boolean);
}

/** Syllables of a phonemized line, word by word (punctuation dropped). */
export function lineSyllables(phonemes: string): string[] {
  const out: string[] = [];
  for (const w of phonemes.split(/[\s,.;:!?—…"“”()]+/)) if (w) out.push(...syllabify(w));
  return out;
}

export interface SungNote {
  /** Seconds from the start of the song. */
  start: number;
  dur: number;
  midi: number;
  /** Phonemes of the syllable sung on this note; empty carries the previous syllable's vowel on (a melisma). */
  syl: string;
  /** Loudness 0…1 (0.7 normal). */
  vel?: number;
}
export interface SingOptions {
  /** Vibrato depth 0…1 (0.5 gentle). */
  vibrato?: number;
  /** Seconds a slide between two notes takes (0.06 crisp, 0.14 gliding as in Indian singing). */
  glide?: number;
  /** Tuning offset in semitones (after the range fit). */
  transpose?: number;
}

interface Tok {
  ch: string;
  /** Index of the note whose syllable this belongs to. */
  note: number;
  part: "onset" | "core" | "coda";
}

/** Sing one unbroken phrase. Returns the sound and how many seconds of it come before the first note starts. */
export async function singPhrase(m: OpenModel, table: Float32Array, notes: SungNote[], o: SingOptions = {}): Promise<{ pcm: Float32Array; lead: number; text?: string; frames?: number[] }> {
  const toks: Tok[] = [];
  let owner = -1;
  notes.forEach((n, k) => {
    if (!n.syl) return;
    owner = k;
    const ch = [...n.syl].filter((c) => tokenize(c).length === 3);
    const firstV = ch.findIndex((c) => VOWELS.has(c));
    let lastV = -1;
    ch.forEach((c, i) => {
      if (VOWELS.has(c) || c === LONG_MARK || c === NASAL) lastV = i;
    });
    ch.forEach((c, i) => {
      // a stress mark right before the vowel sounds as the start of the vowel
      const core = firstV >= 0 && ((i >= firstV && i <= lastV) || (STRESS.has(c) && i === firstV - 1));
      toks.push({ ch: c, note: k, part: firstV < 0 ? "onset" : core ? "core" : i < firstV ? "onset" : "coda" });
    });
  });
  if (!toks.length || owner < 0) return { pcm: new Float32Array(0), lead: 0 };
  const text = toks.map((t) => t.ch).join("");
  const ids = tokenize(text);
  const style = styleRow(table, ids.length);
  const nat = await m.durations(ids, style, 1);
  const N = ids.length;
  const dur = new Array<number>(N).fill(1);
  // consonants keep their natural length, within reason
  toks.forEach((t, i) => {
    if (t.part !== "core") dur[i + 1] = Math.max(1, Math.min(STRESS.has(t.ch) || t.ch === "ʰ" ? 1 : t.part === "coda" ? 2 : 3, Math.round(nat[i + 1]!)));
  });
  const t0 = notes[0]!.start;
  const fr = (sec: number) => Math.round((sec - t0) * FRAME_RATE);
  // syllable k: its notes run from its own note to the note before the next syllable
  const sylNotes = notes.map((n, k) => (n.syl ? k : -1)).filter((k) => k >= 0);
  const sumPart = (note: number, part: Tok["part"]) => toks.reduce((a, t, i) => (t.note === note && t.part === part ? a + dur[i + 1]! : a), 0);
  const last = notes[notes.length - 1]!;
  const endFrame = fr(last.start + last.dur);
  for (let s = 0; s < sylNotes.length; s++) {
    const k = sylNotes[s]!;
    const nextK = sylNotes[s + 1];
    const vowelOn = fr(notes[k]!.start);
    const nextOn = nextK !== undefined ? fr(notes[nextK]!.start) : endFrame;
    let room = nextOn - vowelOn - sumPart(k, "coda") - (nextK !== undefined ? sumPart(nextK, "onset") : 0);
    if (room < 2) {
      // a fast run: squeeze the consonants around it
      toks.forEach((t, i) => {
        if ((t.note === k && t.part === "coda") || (nextK !== undefined && t.note === nextK && t.part === "onset")) dur[i + 1] = 1;
      });
      room = Math.max(2, nextOn - vowelOn - sumPart(k, "coda") - (nextK !== undefined ? sumPart(nextK, "onset") : 0));
    }
    // the vowel itself carries the note; its marks (stress, length, nasal) and a second vowel of a glide get a sliver.
    // (Frames given to a length mark come out as a fading tail, which is right for speech and wrong for a held note.)
    const core = toks.map((t, i) => (t.note === k && t.part === "core" ? i : -1)).filter((i) => i >= 0);
    const main = core.find((i) => VOWELS.has(toks[i]!.ch)) ?? core[0]!;
    let left = room;
    for (const i of core) {
      if (i === main) continue;
      const second = VOWELS.has(toks[i]!.ch);
      const d = Math.max(1, Math.min(second ? Math.max(2, Math.round(room * 0.22)) : 1, left - 2));
      dur[i + 1] = d;
      left -= d;
    }
    dur[main + 1] = Math.max(1, left);
  }
  // the voice needs its usual run-up of silence before the first sound, or the first syllable comes out faint;
  // the run-up is cut off again when the phrase is laid on the song's clock (`lead`)
  dur[0] = Math.max(12, Math.min(24, Math.round(nat[0]!)));
  dur[N - 1] = 6;
  const firstOnset = sumPart(sylNotes[0]!, "onset");
  const lead = (dur[0]! + firstOnset) / FRAME_RATE;
  const { f0, energy } = await m.prosody(ids, style, dur);
  const T = f0.length;
  // which token owns each curve position
  const tokAt = new Int32Array(T);
  let p = 0;
  for (let i = 0; i < N; i++) for (let q = 0; q < dur[i]! * 2 && p < T; q++) tokAt[p++] = i;
  // the tune on the curve's clock: curve position 0 is `lead` seconds before the first note
  const tr = o.transpose ?? 0;
  const target = new Float32Array(T);
  let ni = 0;
  for (let i = 0; i < T; i++) {
    const sec = t0 - lead + i / CURVE_RATE + PHONE_LEAD;
    while (ni < notes.length - 1 && sec >= notes[ni + 1]!.start) ni++;
    target[i] = Math.log(hz(notes[ni]!.midi + tr));
  }
  // glide between notes
  const half = Math.max(1, Math.round(((o.glide ?? 0.08) * CURVE_RATE) / 2));
  const sm = new Float32Array(T);
  for (let i = 0; i < T; i++) {
    let s = 0, w = 0;
    for (let q = -half; q <= half; q++) {
      const j = Math.min(T - 1, Math.max(0, i + q));
      const k = 1 - Math.abs(q) / (half + 1);
      s += target[j]! * k;
      w += k;
    }
    sm[i] = s / w;
  }
  // how loud this voice's full vowels are (the loudness scale is steep: a few units lower is near silence)
  const voiced = Array.from(energy).filter((e) => e > 2).sort((a, b) => a - b);
  const hold = Math.max(7.8, Math.min(9.4, voiced.length ? voiced[Math.floor(voiced.length * 0.8)]! : 8.3));
  const pShift = Math.round(PHONE_LEAD * CURVE_RATE), eShift = Math.round(ENERGY_LEAD * CURVE_RATE);
  const tokOf = (i: number): Tok | undefined => toks[tokAt[Math.min(T - 1, Math.max(0, i))]! - 1];
  // vibrato grows in on held notes; a small scoop into the phrase
  const vib = o.vibrato ?? 0.5;
  const f0In = Float32Array.from(f0), enIn = Float32Array.from(energy);
  ni = 0;
  let ei = 0;
  for (let i = 0; i < T; i++) {
    const sec = t0 - lead + i / CURVE_RATE + PHONE_LEAD;
    while (ni < notes.length - 1 && sec >= notes[ni + 1]!.start) ni++;
    const n = notes[ni]!;
    const into = sec - n.start;
    let lg = sm[i]!;
    if (vib > 0 && n.dur > 0.38 && into > 0.16) {
      const grow = Math.min(1, (into - 0.16) / 0.25) * Math.min(1, Math.max(0, (n.start + n.dur - sec) / 0.08));
      lg += Math.sin(2 * Math.PI * (5.4 + 0.25 * Math.sin(n.start * 3.1)) * into) * (0.021 * vib) * grow;
    }
    if (i < 8) lg -= ((8 - i) / 8) * 0.03;
    // pitch: wherever the phoneme heard at this moment is a vowel, or the voice itself was voiced
    const tp = tokOf(i + pShift);
    if ((tp && tp.part === "core") || f0In[Math.min(T - 1, i + pShift)]! > 35) f0[i] = Math.exp(lg);
    // loudness: hold sung vowels up (they do not die away like spoken ones)
    const te = tokOf(i + eShift);
    const src = enIn[i]!;
    if (te && te.part === "core") {
      const secE = t0 - lead + i / CURVE_RATE + ENERGY_LEAD;
      while (ei < notes.length - 1 && secE >= notes[ei + 1]!.start) ei++;
      const ne = notes[ei]!;
      const v = (notes[te.note]!.vel ?? 0.7) - 0.7;
      const endOf = ne.start + ne.dur - secE;
      const rel = ei === notes.length - 1 ? Math.min(1, Math.max(0, endOf / 0.1)) : 1;
      energy[i] = Math.max(src, hold + v * 1.6) * rel + (1 - rel) * Math.min(src, 3);
    }
  }
  const pcm = await m.render(ids, style, dur, f0, energy);
  return { pcm, lead, text, frames: dur };
}

/** Fit a tune to a voice: the octave (and at most a few semitones) that puts it inside the voice's range. */
export function fitRange(notes: { midi: number }[], voice: string): number {
  if (!notes.length) return 0;
  const [lo, hi] = singRange(voice);
  const mn = Math.min(...notes.map((n) => n.midi)), mx = Math.max(...notes.map((n) => n.midi));
  let best = 0, cost = 1e9;
  for (const sh of [-24, -12, 0, 12]) {
    const over = Math.max(0, mx + sh - hi), under = Math.max(0, lo - (mn + sh));
    // a voice sings best a little above where it speaks: the upper middle of its range
    const mid = Math.abs((mn + mx) / 2 + sh - (lo + (hi - lo) * 0.64));
    const c = (over + under) * 10 + mid;
    if (c < cost) {
      cost = c;
      best = sh;
    }
  }
  return best;
}

/**
 * Sing a whole vocal line. Notes are split into phrases at the rests; each phrase is sung in one breath and laid
 * on the timeline so its vowels land on the beat. Returns 24 kHz mono starting at second 0 of the song.
 */
export async function sing(m: OpenModel, table: Float32Array, notes: SungNote[], o: SingOptions & { onProgress?: (done: number) => void } = {}): Promise<Float32Array> {
  const ns = [...notes].sort((a, b) => a.start - b.start);
  if (!ns.length) return new Float32Array(0);
  const phrases: SungNote[][] = [];
  let cur: SungNote[] = [];
  for (const n of ns) {
    const prev = cur[cur.length - 1];
    const gap = prev ? n.start - (prev.start + prev.dur) : 0;
    const chars = cur.reduce((a, x) => a + x.syl.length, 0);
    if (prev && (gap > 0.14 || chars > 150 || n.start - cur[0]!.start > 11) && n.syl) {
      phrases.push(cur);
      cur = [];
    }
    // a note that overlaps the next is cut to it
    if (cur.length && prev && prev.start + prev.dur > n.start) prev.dur = Math.max(0.05, n.start - prev.start);
    cur.push({ ...n });
  }
  if (cur.length) phrases.push(cur);
  const end = Math.max(...ns.map((n) => n.start + n.dur)) + 0.6;
  const SR = 24000;
  const out = new Float32Array(Math.ceil(end * SR));
  let done = 0;
  for (const ph of phrases) {
    // a phrase must open with a syllable
    while (ph.length && !ph[0]!.syl) ph.shift();
    if (!ph.length) continue;
    const { pcm, lead } = await singPhrase(m, table, ph, o);
    const at = Math.round((ph[0]!.start - lead + PHONE_LEAD) * SR);
    const fade = 120;
    for (let i = 0; i < pcm.length; i++) {
      const j = at + i;
      if (j < 0 || j >= out.length) continue;
      const g = i < fade ? i / fade : i > pcm.length - fade ? (pcm.length - i) / fade : 1;
      out[j] = out[j]! + pcm[i]! * g;
    }
    done++;
    o.onProgress?.(done / phrases.length);
  }
  return out;
}
