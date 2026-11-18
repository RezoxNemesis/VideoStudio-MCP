/**
 * The tone's own shaping for a studio line. Chatterbox acts the mood (its exaggeration), but it knows nothing of the
 * rest of a tone: a line asked to be said higher, louder or softer, to rise at its end like a question or fall like a
 * final word, or to tremble, came out as the model happened to say it. These are worked onto the acted speech here,
 * gently, after the model: the voice stays the model's, only where it goes is directed.
 *
 * - pitch: the whole line up or down (semitones), the time kept.
 * - energy (−1 hushed … 1 forceful): level (±5 dB) and, when pushed, a little drive in the voice.
 * - ending "rise" / "fall": the last half second glides up (+3 semitones) or down (−2).
 * - shake (0…1): a trembling voice, a slow wobble of pitch and level.
 * - swell: level that follows the line's shape: a line that builds ("!", a rising sentence) grows towards its end,
 *   a line that trails off ("…") dies away.
 */
import { pitchShift, resampleTo, timeStretch } from "./audio";

export interface Shape {
  pitch?: number;
  energy?: number;
  ending?: "rise" | "fall" | "level";
  shake?: number;
  /** +1 grows towards the end, −1 dies away, 0 even. */
  swell?: number;
}

const SR = 24000;

/** Where the speech in a clip begins and ends (samples), from 2 % of its loudest 20 ms. */
function voiced(pcm: Float32Array, sr = SR): [number, number] {
  const hop = Math.round(sr * 0.02), n = Math.floor(pcm.length / hop);
  if (n < 3) return [0, pcm.length];
  const e = new Float32Array(n);
  let peak = 0;
  for (let f = 0; f < n; f++) {
    let q = 0;
    for (let i = f * hop; i < (f + 1) * hop; i++) q += pcm[i]! * pcm[i]!;
    peak = Math.max(peak, (e[f] = Math.sqrt(q / hop)));
  }
  let a = 0, b = n - 1;
  while (a < b && e[a]! < peak * 0.02) a++;
  while (b > a && e[b]! < peak * 0.02) b--;
  return [a * hop, Math.min(pcm.length, (b + 1) * hop)];
}

/**
 * A pitch glide over part of a clip: the pitch moves from 0 to `semis` across [from, to), the length kept. Variable-rate
 * resampling moves the pitch (and shortens or lengthens the part), WSOLA brings the part back to its length.
 */
export function glide(pcm: Float32Array, from: number, to: number, semis: number, sr = SR): Float32Array {
  if (Math.abs(semis) < 0.05 || to - from < sr * 0.08) return pcm;
  const part = pcm.subarray(from, to), L = part.length;
  // read the part at a rate that rises from 1 to 2^(semis/12), with a smooth curve
  const out: number[] = [];
  let pos = 0;
  while (pos < L - 1) {
    const u = pos / L, k = u * u * (3 - 2 * u);
    const j = Math.floor(pos), f = pos - j;
    out.push(part[j]! * (1 - f) + (part[j + 1] ?? part[j]!) * f);
    pos += 2 ** ((semis * k) / 12);
  }
  const moved = timeStretch(Float32Array.from(out), L / out.length, sr);
  const fixed = resampleTo(moved, L); // (the stretch is within a sample or two of L: no pitch change worth a cent)
  const res = Float32Array.from(pcm);
  // cross-fade 15 ms into the moved part
  const x = Math.min(Math.round(sr * 0.015), L >> 2);
  for (let i = 0; i < L; i++) {
    const w = i < x ? i / x : 1;
    res[from + i] = part[i]! * (1 - w) + fixed[i]! * w;
  }
  return res;
}

/** Work a tone's pitch, level, ending, trembling and swell onto acted speech (24 kHz). */
export function shapeStudio(pcm: Float32Array, s: Shape, sr = SR): Float32Array {
  let x = pcm;
  if (s.pitch && Math.abs(s.pitch) >= 0.25) x = pitchShift(x, Math.max(-6, Math.min(6, s.pitch)), sr);
  const [a, b] = voiced(x, sr);
  if (s.ending === "rise" || s.ending === "fall") {
    const len = Math.min(Math.round(sr * 0.5), Math.round((b - a) * 0.4));
    x = glide(x, b - len, b, s.ending === "rise" ? 3 : -2, sr);
  } else x = Float32Array.from(x);
  const energy = Math.max(-1, Math.min(1, s.energy ?? 0));
  const shake = Math.max(0, Math.min(1, s.shake ?? 0));
  const swell = Math.max(-1, Math.min(1, s.swell ?? 0));
  const drive = energy > 0.3 ? 1 + (energy - 0.3) * 2.2 : 1;
  const span = Math.max(1, b - a);
  for (let i = 0; i < x.length; i++) {
    const u = Math.max(0, Math.min(1, (i - a) / span));
    // level: energy (±5 dB), the swell across the line (±4 dB), a trembling wobble (5.5 Hz)
    let g = 10 ** ((energy * 5 + swell * (u - 0.5) * 8) / 20);
    if (shake) g *= 1 + 0.18 * shake * Math.sin((2 * Math.PI * 5.5 * i) / sr);
    let v = x[i]! * g;
    if (drive > 1) v = Math.tanh(v * drive) / Math.tanh(drive);
    x[i] = v;
  }
  if (shake) {
    // pitch wobble: a delay line swinging ±0.6 ms (≈ ±0.35 semitone at 5.5 Hz) scaled by shake
    const d = new Float32Array(x.length), depth = 0.0006 * sr * shake;
    for (let i = 0; i < x.length; i++) {
      const p = i - depth * (1 + Math.sin((2 * Math.PI * 5.5 * i) / sr));
      const j = Math.floor(p), f = p - j;
      d[i] = j < 0 ? x[0]! : (x[j]! * (1 - f) + (x[j + 1] ?? x[j]!) * f);
    }
    x = d;
  }
  return x;
}

/**
 * How loud a line should sit (its peak) after its tone: a hushed line is quieter than a forceful one, so that the
 * level a direction asks for is not undone when every clip is brought to the same peak.
 */
export function peakFor(energy = 0): number {
  const e = Math.max(-1, Math.min(1, energy));
  return Math.min(0.95, 0.85 * 10 ** ((e < 0 ? e * 9 : e * 1.5) / 20));
}

/** The swell a line's own writing asks for: a line that ends in "!" builds, one that trails off ("…") dies away. */
export function swellOf(text: string): number {
  const t = text.trim();
  if (/(…|\.\.\.)$/.test(t)) return -0.6;
  if (/!$/.test(t)) return 0.4;
  return 0;
}
