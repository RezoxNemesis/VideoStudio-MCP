/** Beat and onset detection on decoded audio (energy flux + autocorrelation tempo + phase fit). */

export interface BeatInfo {
  bpm: number;
  /** Beat times in seconds. */
  beats: number[];
  /** Strong onsets (hits) in seconds. */
  onsets: number[];
  /** Downbeats (every 4th beat, aligned to the strongest bar position). */
  downbeats: number[];
  duration: number;
}

export function detectBeats(channels: Float32Array[], sampleRate: number, opts: { minBpm?: number; maxBpm?: number } = {}): BeatInfo {
  const n = channels[0]?.length ?? 0;
  const duration = n / sampleRate;
  const hop = Math.max(256, Math.round(sampleRate / 100)); // ~10 ms
  const frames = Math.floor(n / hop);
  if (frames < 20) return { bpm: 0, beats: [], onsets: [], downbeats: [], duration };
  // Energy in two bands: low (kick) via a one-pole low-pass, and full.
  const env = new Float32Array(frames);
  const low = new Float32Array(frames);
  let lp = 0;
  const a = Math.exp((-2 * Math.PI * 150) / sampleRate);
  for (let f = 0; f < frames; f++) {
    let e = 0;
    let el = 0;
    for (let i = f * hop, end = i + hop; i < end; i++) {
      let v = 0;
      for (const ch of channels) v += ch[i]!;
      v /= channels.length;
      lp = a * lp + (1 - a) * v;
      e += v * v;
      el += lp * lp;
    }
    env[f] = Math.log1p((e / hop) * 1000);
    low[f] = Math.log1p((el / hop) * 4000);
  }
  // Onset strength: positive change, weighted toward the low band.
  const flux = new Float32Array(frames);
  for (let f = 1; f < frames; f++) flux[f] = Math.max(0, env[f]! - env[f - 1]!) + 1.5 * Math.max(0, low[f]! - low[f - 1]!);
  // Normalise with a moving mean.
  const W = 40;
  const norm = new Float32Array(frames);
  let sum = 0;
  for (let f = 0; f < frames; f++) {
    sum += flux[f]!;
    if (f >= W) sum -= flux[f - W]!;
    const mean = sum / Math.min(f + 1, W);
    norm[f] = Math.max(0, flux[f]! - mean);
  }
  // Tempo by autocorrelation over 60..180 BPM with a gentle preference for ~120.
  const fps = sampleRate / hop;
  const minLag = Math.round((60 / (opts.maxBpm ?? 180)) * fps);
  const maxLag = Math.round((60 / (opts.minBpm ?? 60)) * fps);
  let bestLag = Math.round(0.5 * fps);
  let bestScore = -Infinity;
  for (let lag = minLag; lag <= maxLag; lag++) {
    let acc = 0;
    for (let f = lag; f < frames; f++) acc += norm[f]! * norm[f - lag]!;
    const bpm = (60 * fps) / lag;
    const prior = Math.exp(-0.5 * Math.pow(Math.log2(bpm / 120) / 0.9, 2));
    const score = (acc / (frames - lag)) * prior;
    if (score > bestScore) {
      bestScore = score;
      bestLag = lag;
    }
  }
  // Refine to a fractional period and phase together (integer lags drift over a long song).
  const at = (x: number) => {
    const i = Math.floor(x);
    const f = x - i;
    return (norm[i] ?? 0) * (1 - f) + (norm[i + 1] ?? 0) * f;
  };
  let period = bestLag;
  let bestPhase = 0;
  let bestSum = -1;
  for (let per = bestLag - 1; per <= bestLag + 1 + 1e-9; per += 0.02) {
    for (let ph = 0; ph < per; ph += 0.5) {
      let s2 = 0;
      for (let x = ph; x < frames - 1; x += per) s2 += at(x) + 0.5 * (at(x - 1) + at(x + 1));
      if (s2 > bestSum) {
        bestSum = s2;
        bestPhase = ph;
        period = per;
      }
    }
  }
  const beats: number[] = [];
  for (let x = bestPhase; x < frames; x += period) {
    // Snap each beat to the local onset peak within ±2 frames.
    const f = Math.round(x);
    let bf = f;
    for (let d = -2; d <= 2; d++) if ((norm[f + d] ?? 0) > (norm[bf] ?? 0)) bf = f + d;
    beats.push(Math.round((bf / fps) * 1000) / 1000);
  }
  // Downbeats: the bar position (0..3) with the most low-band energy.
  let bestBar = 0;
  let bestBarE = -1;
  for (let b = 0; b < 4; b++) {
    let e = 0;
    for (let i = b; i < beats.length; i += 4) e += low[Math.round(beats[i]! * fps)] ?? 0;
    if (e > bestBarE) {
      bestBarE = e;
      bestBar = b;
    }
  }
  const downbeats = beats.filter((_, i) => i % 4 === bestBar);
  // Onsets: local maxima above an adaptive threshold, at least 100 ms apart.
  const onsets: number[] = [];
  let last = -1e9;
  const sorted = Array.from(norm).sort((x, y) => x - y);
  const thr = sorted[Math.floor(sorted.length * 0.9)] ?? 0;
  for (let f = 2; f < frames - 2; f++) {
    const v = norm[f]!;
    if (v > thr && v >= norm[f - 1]! && v >= norm[f + 1]! && f - last > fps * 0.1) {
      onsets.push(Math.round((f / fps) * 1000) / 1000);
      last = f;
    }
  }
  return { bpm: Math.round(((60 * fps) / period) * 10) / 10, beats, onsets, downbeats, duration };
}

export function bufferChannels(b: AudioBuffer): Float32Array[] {
  const out: Float32Array[] = [];
  for (let c = 0; c < Math.min(2, b.numberOfChannels); c++) out.push(b.getChannelData(c));
  return out;
}
