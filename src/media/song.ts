/**
 * Song understanding for music-video editing: tempo and beats, bar-level energy, chroma, key, sections
 * (intro / verse / chorus / drop / bridge / outro with repeats found by similarity), mood and hit points.
 * Lyrics with timings come from the on-phone speech model (see asr.ts); this file is pure signal analysis.
 */
import { detectBeats, type BeatInfo } from "./beats";

export interface Section {
  start: number;
  end: number;
  label: string;
  /** 0..1 relative loudness/density. */
  energy: number;
  /** Sections with the same group letter sound alike (A = first new material). */
  group: string;
}

export interface SongInfo {
  duration: number;
  bpm: number;
  beats: number[];
  downbeats: number[];
  key: string;
  /** Rough feel from tempo, mode, energy and brightness. */
  mood: { label: string; energy: number; valence: number; brightness: number };
  sections: Section[];
  /** Loudness every 0.5 s, 0..1. */
  energy: number[];
  /** Biggest hits (drops, slams) in seconds, strongest first. */
  hits: number[];
  /** Where the music goes quiet (breaks, pauses). */
  breaks: { start: number; end: number }[];
}

const NOTES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
// Krumhansl–Kessler key profiles.
const MAJOR = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR = [6.33, 2.68, 3.52, 5.38, 2.6, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

/** In-place radix-2 FFT magnitude of a real frame (length power of two). */
function fftMag(re: Float32Array, im: Float32Array): void {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      [re[i], re[j]] = [re[j]!, re[i]!];
      [im[i], im[j]] = [im[j]!, im[i]!];
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (-2 * Math.PI) / len;
    const wr = Math.cos(ang),
      wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1,
        ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const a = i + k,
          b = a + len / 2;
        const tr = re[b]! * cr - im[b]! * ci;
        const ti = re[b]! * ci + im[b]! * cr;
        re[b] = re[a]! - tr;
        im[b] = im[a]! - ti;
        re[a] = re[a]! + tr;
        im[a] = im[a]! + ti;
        const nr = cr * wr - ci * wi;
        ci = cr * wi + ci * wr;
        cr = nr;
      }
    }
  }
  for (let i = 0; i < n; i++) re[i] = Math.hypot(re[i]!, im[i]!);
}

const corr = (a: number[], b: number[]) => {
  const n = a.length;
  const ma = a.reduce((x, y) => x + y, 0) / n;
  const mb = b.reduce((x, y) => x + y, 0) / n;
  let s = 0,
    sa = 0,
    sb = 0;
  for (let i = 0; i < n; i++) {
    s += (a[i]! - ma) * (b[i]! - mb);
    sa += (a[i]! - ma) ** 2;
    sb += (b[i]! - mb) ** 2;
  }
  return s / Math.sqrt(sa * sb || 1);
};
const cosine = (a: number[], b: number[]) => {
  let s = 0,
    na = 0,
    nb = 0;
  for (let i = 0; i < a.length; i++) {
    s += a[i]! * b[i]!;
    na += a[i]! ** 2;
    nb += b[i]! ** 2;
  }
  return s / Math.sqrt(na * nb || 1);
};

export function analyseSong(channels: Float32Array[], sr: number, beatInfo?: BeatInfo): SongInfo {
  const n = channels[0]?.length ?? 0;
  const mono = new Float32Array(n);
  for (const ch of channels) for (let i = 0; i < n; i++) mono[i]! += ch[i]! / channels.length;
  const duration = n / sr;
  const bi = beatInfo ?? detectBeats(channels, sr);

  // Loudness every 0.5 s.
  const step = Math.round(sr / 2);
  const energy: number[] = [];
  for (let i = 0; i < n; i += step) {
    let e = 0;
    const end = Math.min(n, i + step);
    for (let k = i; k < end; k++) e += mono[k]! * mono[k]!;
    energy.push(Math.sqrt(e / Math.max(1, end - i)));
  }
  const eMax = Math.max(1e-6, ...energy);
  for (let i = 0; i < energy.length; i++) energy[i] = Math.round((energy[i]! / eMax) * 1000) / 1000;

  // Bars: downbeats, or 2 s blocks if no tempo was found.
  let bars = bi.downbeats.length >= 4 ? [...bi.downbeats] : [];
  if (!bars.length) for (let t = 0; t < duration; t += 2) bars.push(t);
  if (bars[0]! > 0.5) bars.unshift(0);
  bars.push(duration);

  // Per-bar features: loudness, brightness, 12-bin chroma (from a few FFT frames per bar).
  const N = 4096;
  const re = new Float32Array(N),
    im = new Float32Array(N);
  const binHz = sr / N;
  const pitchClass = new Int8Array(N / 2).fill(-1);
  for (let k = 1; k < N / 2; k++) {
    const f = k * binHz;
    if (f < 55 || f > 2000) continue;
    pitchClass[k] = ((Math.round(12 * Math.log2(f / 440)) % 12) + 12 + 9) % 12; // 0 = C
  }
  const hann = new Float32Array(N);
  for (let i = 0; i < N; i++) hann[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (N - 1));
  const feats: { e: number; bright: number; chroma: number[] }[] = [];
  const total = new Array(12).fill(0) as number[];
  let brightSum = 0;
  for (let b = 0; b < bars.length - 1; b++) {
    const s0 = Math.floor(bars[b]! * sr),
      s1 = Math.floor(bars[b + 1]! * sr);
    const chroma = new Array(12).fill(0) as number[];
    let e = 0,
      hi = 0,
      all = 0;
    const frames = Math.max(1, Math.min(6, Math.floor((s1 - s0) / N)));
    for (let fI = 0; fI < frames; fI++) {
      const off = s0 + Math.floor(((s1 - s0 - N) * (fI + 0.5)) / frames);
      if (off < 0 || off + N > n) continue;
      for (let i = 0; i < N; i++) {
        re[i] = mono[off + i]! * hann[i]!;
        im[i] = 0;
      }
      fftMag(re, im);
      for (let k = 1; k < N / 2; k++) {
        const m = re[k]!;
        all += m;
        if (k * binHz > 3000) hi += m;
        const pc = pitchClass[k]!;
        if (pc >= 0) chroma[pc]! += m * m;
      }
    }
    for (let k = s0; k < s1; k++) e += mono[k]! * mono[k]!;
    e = Math.sqrt(e / Math.max(1, s1 - s0));
    const cm = Math.max(1e-9, ...chroma);
    for (let i = 0; i < 12; i++) {
      chroma[i] = chroma[i]! / cm;
      total[i]! += chroma[i]! * e;
    }
    const bright = all > 0 ? hi / all : 0;
    brightSum += bright;
    feats.push({ e, bright, chroma });
  }

  // Key.
  let best = { score: -2, key: "C major", minor: false };
  for (let r = 0; r < 12; r++) {
    const rot = (p: number[]) => p.map((_, i) => p[(i - r + 12) % 12]!);
    const sM = corr(total, rot(MAJOR));
    const sm = corr(total, rot(MINOR));
    if (sM > best.score) best = { score: sM, key: `${NOTES[r]} major`, minor: false };
    if (sm > best.score) best = { score: sm, key: `${NOTES[r]} minor`, minor: true };
  }

  // Sections: split where bar-to-bar change (loudness + harmony) peaks; at least 4 bars per section.
  const eMaxBar = Math.max(1e-9, ...feats.map((f) => f.e));
  const novelty = feats.map((f, i) => {
    if (i < 2 || i > feats.length - 3) return 0;
    const prev = feats.slice(Math.max(0, i - 4), i);
    const next = feats.slice(i, Math.min(feats.length, i + 4));
    const avg = (xs: typeof feats, k: (f: (typeof feats)[number]) => number) => xs.reduce((s, x) => s + k(x), 0) / xs.length;
    const dE = Math.abs(avg(next, (x) => x.e) - avg(prev, (x) => x.e)) / eMaxBar;
    const cP = new Array(12).fill(0).map((_, j) => avg(prev, (x) => x.chroma[j]!));
    const cN = new Array(12).fill(0).map((_, j) => avg(next, (x) => x.chroma[j]!));
    return dE * 1.6 + (1 - cosine(cP, cN));
  });
  const minBars = Math.max(4, Math.round(feats.length / 14));
  const cuts = [0];
  const order = novelty.map((v, i) => [v, i] as const).sort((a, b) => b[0] - a[0]);
  const mean = novelty.reduce((a, b) => a + b, 0) / Math.max(1, novelty.length);
  for (const [v, i] of order) {
    if (v < mean * 1.4) break;
    if (cuts.every((c) => Math.abs(c - i) >= minBars) && i >= minBars && feats.length - i >= minBars / 2) cuts.push(i);
  }
  cuts.sort((a, b) => a - b);
  cuts.push(feats.length);
  const raw = cuts.slice(0, -1).map((c, k) => {
    const seg = feats.slice(c, cuts[k + 1]);
    const chroma = new Array(12).fill(0).map((_, j) => seg.reduce((s, x) => s + x.chroma[j]!, 0) / seg.length);
    return { b0: c, b1: cuts[k + 1]!, e: seg.reduce((s, x) => s + x.e, 0) / seg.length / eMaxBar, chroma };
  });
  // Group similar sections (repeats).
  const groups: number[] = [];
  let nextG = 0;
  raw.forEach((s, i) => {
    let g = -1;
    for (let j = 0; j < i; j++) if (cosine(s.chroma, raw[j]!.chroma) > 0.93 && Math.abs(s.e - raw[j]!.e) < 0.18) g = groups[j]!;
    groups.push(g >= 0 ? g : nextG++);
  });
  const eSorted = raw.map((s) => s.e).sort((a, b) => b - a);
  const loud = eSorted[Math.min(eSorted.length - 1, Math.floor(eSorted.length / 3))] ?? 1;
  const gCount = new Map<number, number>();
  for (const g of groups) gCount.set(g, (gCount.get(g) ?? 0) + 1);
  const sections: Section[] = raw.map((s, i) => {
    const start = bars[s.b0]!,
      end = bars[s.b1]!;
    let label: string;
    if (i === 0 && s.e < 0.6) label = "intro";
    else if (i === raw.length - 1 && s.e < 0.6) label = "outro";
    else if (s.e >= loud && (gCount.get(groups[i]!) ?? 0) > 1) label = "chorus";
    else if (s.e >= 0.85) label = "drop";
    else if (s.e < 0.45) label = "break";
    else if ((gCount.get(groups[i]!) ?? 0) === 1 && i > raw.length / 2) label = "bridge";
    else label = "verse";
    return { start: r2(start), end: r2(end), label, energy: r2(s.e), group: String.fromCharCode(65 + (groups[i]! % 26)) };
  });

  // Hits: biggest jumps in loudness (drops) and the strongest onsets.
  const jumps = energy.map((e, i) => [i > 1 ? e - (energy[i - 1]! + energy[i - 2]!) / 2 : 0, i * 0.5] as const).sort((a, b) => b[0] - a[0]);
  const hits = jumps.filter((j) => j[0] > 0.25).slice(0, 8).map((j) => snap(j[1], bi.beats));
  for (const s of sections.slice(1)) if (s.energy - (sections[sections.indexOf(s) - 1]?.energy ?? 0) > 0.25) hits.push(s.start);
  const breaks: { start: number; end: number }[] = [];
  let q = -1;
  energy.forEach((e, i) => {
    if (e < 0.12 && q < 0) q = i;
    if ((e >= 0.12 || i === energy.length - 1) && q >= 0) {
      if (i - q >= 2) breaks.push({ start: q * 0.5, end: i * 0.5 });
      q = -1;
    }
  });

  const avgE = feats.reduce((s, f) => s + f.e, 0) / Math.max(1, feats.length) / eMaxBar;
  const brightness = r2(brightSum / Math.max(1, feats.length) / 0.25);
  const tempoF = Math.min(1, Math.max(0, (bi.bpm - 70) / 90));
  const arousal = r2(Math.min(1, 0.45 * tempoF + 0.35 * avgE + 0.2 * Math.min(1, brightness)));
  const valence = r2(Math.min(1, Math.max(0, (best.minor ? 0.3 : 0.65) + 0.2 * (Math.min(1, brightness) - 0.5) + 0.15 * (tempoF - 0.5))));
  const label =
    arousal > 0.6 ? (valence > 0.5 ? "euphoric / hype" : "aggressive / intense") : arousal > 0.35 ? (valence > 0.5 ? "upbeat / warm" : "moody / driving") : valence > 0.5 ? "calm / tender" : "sad / reflective";

  return {
    duration: r2(duration),
    bpm: bi.bpm,
    beats: bi.beats.map(r2),
    downbeats: bi.downbeats.map(r2),
    key: best.key,
    mood: { label, energy: arousal, valence, brightness },
    sections,
    energy,
    hits: [...new Set(hits.map(r2))].sort((a, b) => a - b),
    breaks,
  };
}

function r2(x: number): number {
  return Math.round(x * 100) / 100;
}
function snap(t: number, beats: number[]): number {
  let best = t;
  let d = 0.3;
  for (const b of beats) if (Math.abs(b - t) < d) (d = Math.abs(b - t)), (best = b);
  return best;
}
