/**
 * How a take is SAID, measured: is the voice steady, or does its pitch crack?
 *
 * 27 Nov 2026: the owner heard one character's lines as "a little unnatural, the way they are said". Measured against fourteen
 * real readers' clips, those takes' pitch made sudden steps (more than three semitones inside a hundredth of a second) 1.7 and
 * 2.7 times a second, and travelled 75 semitones a second; the readers, and the lines he accepted, stay under 1.5 steps and
 * between 19 and 60 semitones. A voice whose pitch breaks like that is heard as a wobble, whatever the words. Nothing in the
 * letters of a take shows it (the words were all there), so it is measured on the sound: a take that is complete but cracks
 * is made again while tries remain, and the steadiest complete take is kept.
 */

/** Sudden steps of the pitch a second above which a take is called cracked (real readers: 0 to 1.3). */
export const CRACKS = 1.6;

export interface Steadiness {
  /** sudden steps of the pitch (over 3 semitones between two neighbouring hundredths of a second) for each second spoken */
  jumps: number;
  /** how many such steps there were */
  steps: number;
  /** semitones the pitch travels in a second of voiced sound (a flat voice under 19, a restless one over 60) */
  wander: number;
  /** seconds from the first sound to the last, without the silences */
  spoken: number;
  /** the middle pitch, Hz */
  median: number;
}

/** The pitch every hundredth of a second (0 where there is no voice), by the sound's likeness to itself a period later. */
export function pitchTrack(pcm: Float32Array, sr = 24000, lo = 75, hi = 420): { f0: Float32Array; loud: Float32Array } {
  const n = Math.round(0.04 * sr), h = Math.round(0.01 * sr), frames = Math.max(0, Math.floor((pcm.length - n) / h) + (pcm.length >= n ? 1 : 0));
  const f0 = new Float32Array(frames), loud = new Float32Array(frames);
  let peak = 0;
  for (let i = 0; i < pcm.length; i++) { const v = Math.abs(pcm[i]!); if (v > peak) peak = v; }
  if (!frames || peak <= 0) return { f0, loud };
  const g = 0.5 / peak, win = new Float32Array(n), w = new Float32Array(n), a = Math.max(2, Math.floor(sr / hi)), b = Math.min(n - 2, Math.ceil(sr / lo));
  for (let j = 0; j < n; j++) win[j] = 0.5 - 0.5 * Math.cos((2 * Math.PI * j) / (n - 1));
  for (let f = 0; f < frames; f++) {
    const at = f * h;
    let e = 0;
    for (let j = 0; j < n; j++) { const v = pcm[at + j]! * g * win[j]!; w[j] = v; e += v * v; }
    loud[f] = Math.sqrt(e / n);
    if (loud[f]! < 0.01 || e <= 0) continue;
    let best = 0, bestK = 0;
    for (let k = a; k <= b; k++) {
      let s = 0;
      for (let j = 0, m = n - k; j < m; j++) s += w[j]! * w[j + k]!;
      if (s > best) { best = s; bestK = k; }
    }
    if (bestK && best / e > 0.5) f0[f] = sr / bestK;
  }
  // an octave slip is the measure's own mistake, not the voice's: a value near double or half the middle is folded back
  const voiced = Array.from(f0).filter((v) => v > 0).sort((p, q) => p - q);
  if (voiced.length > 5) {
    const med = voiced[voiced.length >> 1]!;
    for (let f = 0; f < frames; f++) { const v = f0[f]!; if (v > 1.8 * med) f0[f] = v / 2; else if (v > 0 && v < 0.55 * med) f0[f] = v * 2; }
  }
  return { f0, loud };
}

/** How steady a take's voice is; null when there is too little voice to tell (a whisper, a breath, a word). */
export function steadiness(pcm: Float32Array, sr = 24000): Steadiness | null {
  const { f0, loud } = pitchTrack(pcm, sr);
  const voiced = Array.from(f0).filter((v) => v > 0).sort((p, q) => p - q);
  if (voiced.length < 20) return null;
  const median = voiced[voiced.length >> 1]!;
  const sorted = Array.from(loud).sort((p, q) => p - q), floor = Math.max(0.012, 0.08 * sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))]!);
  let first = -1, last = -1, spokenFrames = 0;
  for (let f = 0; f < loud.length; f++) if (loud[f]! > floor) { if (first < 0) first = f; last = f; }
  for (let f = Math.max(0, first); f <= last; f++) if (loud[f]! > floor) spokenFrames++;
  const spoken = spokenFrames * 0.01;
  let steps = 0, sum = 0, pairs = 0;
  for (let f = 1; f < f0.length; f++) {
    if (!(f0[f]! > 0) || !(f0[f - 1]! > 0)) continue;
    const d = Math.abs(12 * Math.log2(f0[f]! / f0[f - 1]!));
    sum += d; pairs++;
    if (d > 3) steps++;
  }
  const r = (x: number, k: number) => Math.round(x * k) / k;
  return { jumps: r(steps / Math.max(0.2, spoken), 100), steps, wander: r(pairs ? (sum / pairs) * 100 : 0, 10), spoken: r(spoken, 100), median: Math.round(median) };
}

/** A take cracks when its pitch breaks more often than any real reader's does (and at least three times: one break is a breath). */
export const cracked = (s: Steadiness | null): boolean => !!s && s.steps >= 3 && s.jumps > CRACKS;
