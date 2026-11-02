/**
 * Audio clean-up and mastering on the phone:
 *  - spectral noise reduction (learns the steady noise — hiss, hum, wind, fan, room rumble — and gates it out
 *    per frequency, with smoothing so music isn't "watery")
 *  - tone: rumble cut, mud cut, presence and air lift ("song" or "voice" voicing)
 *  - glue compression and a true-peak-safe limiter
 *  - loudness to a target (Instagram/Reels ≈ −14 LUFS-ish; measured with K-weighting approximation)
 * And syncing a clean studio track to a phone recording (onset-envelope cross-correlation).
 */

/* ----------------------------------- FFT ----------------------------------- */

function fft(re: Float32Array, im: Float32Array, inverse = false) {
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
    const ang = ((inverse ? 2 : -2) * Math.PI) / len;
    const wr = Math.cos(ang),
      wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1,
        ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const a = i + k,
          b = a + len / 2;
        const tr = re[b]! * cr - im[b]! * ci,
          ti = re[b]! * ci + im[b]! * cr;
        re[b] = re[a]! - tr;
        im[b] = im[a]! - ti;
        re[a] = re[a]! + tr;
        im[a] = im[a]! + ti;
        const ncr = cr * wr - ci * wi;
        ci = cr * wi + ci * wr;
        cr = ncr;
      }
    }
  }
  if (inverse)
    for (let i = 0; i < n; i++) {
      re[i] = re[i]! / n;
      im[i] = im[i]! / n;
    }
}

/**
 * Spectral gating noise reduction on one channel. `amount` 0..1. The noise profile is the quiet floor of each
 * frequency over the whole clip (a low percentile), so steady noise is removed and music/voices stay.
 */
export function denoise(x: Float32Array, sr: number, amount = 0.8): Float32Array {
  const N = 2048,
    hop = 512;
  const win = new Float32Array(N);
  for (let i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / N);
  const frames = Math.max(1, Math.ceil((x.length + N) / hop));
  const B = N / 2 + 1;
  const mags = new Float32Array(frames * B);
  const specRe = new Float32Array(frames * B),
    specIm = new Float32Array(frames * B);
  const re = new Float32Array(N),
    im = new Float32Array(N);
  for (let f = 0; f < frames; f++) {
    const off = f * hop - N / 2;
    for (let i = 0; i < N; i++) {
      const s = x[off + i] ?? 0;
      re[i] = s * win[i]!;
      im[i] = 0;
    }
    fft(re, im);
    for (let k = 0; k < B; k++) {
      specRe[f * B + k] = re[k]!;
      specIm[f * B + k] = im[k]!;
      mags[f * B + k] = Math.hypot(re[k]!, im[k]!);
    }
  }
  // Noise profile: the average spectrum of the quietest 8% of frames (pauses, breaths, gaps between phrases),
  // never above each bin's own low percentile — so sustained notes are not mistaken for noise.
  const energy = new Float32Array(frames);
  for (let f = 0; f < frames; f++) {
    let e = 0;
    for (let k = 0; k < B; k++) e += mags[f * B + k]! ** 2;
    energy[f] = e;
  }
  const order = Array.from(energy.keys()).sort((p, q) => energy[p]! - energy[q]!);
  const quiet = order.slice(0, Math.max(3, Math.floor(frames * 0.08)));
  const noise = new Float32Array(B);
  const col = new Float32Array(frames);
  for (let k = 0; k < B; k++) {
    let m = 0;
    for (const f of quiet) m += mags[f * B + k]!;
    m /= quiet.length;
    for (let f = 0; f < frames; f++) col[f] = mags[f * B + k]!;
    const sorted = Float32Array.from(col).sort();
    noise[k] = Math.min(m, sorted[Math.floor(frames * 0.2)]!);
  }
  // Gains: soft spectral subtraction (power domain) with a floor, so nothing pumps or turns "watery".
  const over = 1 + amount * 1.5;
  const floor = Math.max(0.08, 0.4 - amount * 0.3);
  const g = new Float32Array(frames * B);
  for (let f = 0; f < frames; f++)
    for (let k = 0; k < B; k++) {
      const m = mags[f * B + k]!;
      const nn = noise[k]! * over;
      const pg = m > 0 ? Math.max(0, 1 - (nn * nn) / (m * m)) : 0;
      g[f * B + k] = Math.max(floor, Math.sqrt(pg));
    }
  const gs = new Float32Array(frames * B);
  for (let f = 0; f < frames; f++)
    for (let k = 0; k < B; k++) {
      let s = 0,
        n = 0;
      for (let df = -2; df <= 2; df++) {
        const ff = f + df;
        if (ff < 0 || ff >= frames) continue;
        for (let dk = -1; dk <= 1; dk++) {
          const kk = k + dk;
          if (kk < 0 || kk >= B) continue;
          s += g[ff * B + kk]!;
          n++;
        }
      }
      gs[f * B + k] = s / n;
    }
  // Resynthesis (weighted overlap-add).
  const out = new Float32Array(x.length);
  const norm = new Float32Array(x.length);
  for (let f = 0; f < frames; f++) {
    for (let k = 0; k < B; k++) {
      const gg = gs[f * B + k]!;
      re[k] = specRe[f * B + k]! * gg;
      im[k] = specIm[f * B + k]! * gg;
      if (k > 0 && k < N / 2) {
        re[N - k] = re[k]!;
        im[N - k] = -im[k]!;
      }
    }
    fft(re, im, true);
    const off = f * hop - N / 2;
    for (let i = 0; i < N; i++) {
      const p = off + i;
      if (p < 0 || p >= out.length) continue;
      out[p] = out[p]! + re[i]! * win[i]!;
      norm[p] = norm[p]! + win[i]! * win[i]!;
    }
  }
  for (let i = 0; i < out.length; i++) out[i] = norm[i]! > 1e-6 ? out[i]! / norm[i]! : 0;
  void sr;
  return out;
}

/** Approximate integrated loudness (K-weighted RMS, gated) in LUFS. */
export function loudness(chs: Float32Array[], sr: number): number {
  // K-weighting approximated by a 1st-order high-pass at ~100 Hz plus a +4 dB shelf above ~1.5 kHz.
  const block = Math.round(sr * 0.4),
    step = Math.round(sr * 0.1);
  const pre = chs.map((x) => {
    const y = new Float32Array(x.length);
    const a = Math.exp((-2 * Math.PI * 100) / sr);
    let px = 0,
      py = 0;
    for (let i = 0; i < x.length; i++) {
      const v = a * (py + x[i]! - px);
      px = x[i]!;
      py = v;
      y[i] = v;
    }
    const b = Math.exp((-2 * Math.PI * 1500) / sr);
    let lp = 0;
    for (let i = 0; i < y.length; i++) {
      lp = b * lp + (1 - b) * y[i]!;
      y[i] = y[i]! + (y[i]! - lp) * 0.58;
    }
    return y;
  });
  const vals: number[] = [];
  for (let s = 0; s + block <= pre[0]!.length; s += step) {
    let e = 0;
    for (const y of pre) for (let i = s; i < s + block; i++) e += y[i]! * y[i]!;
    vals.push(e / block);
  }
  const abs = vals.filter((v) => -0.691 + 10 * Math.log10(v + 1e-12) > -70);
  if (!abs.length) return -70;
  const mean = abs.reduce((a, b) => a + b, 0) / abs.length;
  const rel = abs.filter((v) => v > mean * 0.1);
  const m2 = rel.reduce((a, b) => a + b, 0) / Math.max(1, rel.length);
  return -0.691 + 10 * Math.log10(m2 + 1e-12);
}

export interface EnhanceOptions {
  denoise?: number;
  voicing?: "song" | "voice" | "none";
  target?: number;
  /** Extra width for stereo material (0..1). */
  width?: number;
}

/** Clean and master an AudioBuffer. Returns a new buffer. */
export async function enhance(buf: AudioBuffer, o: EnhanceOptions = {}): Promise<AudioBuffer> {
  const sr = buf.sampleRate;
  const chs: Float32Array[] = [];
  for (let c = 0; c < Math.min(2, buf.numberOfChannels); c++) {
    const x = buf.getChannelData(c);
    chs.push(o.denoise === 0 ? Float32Array.from(x) : denoise(x, sr, o.denoise ?? 0.75));
  }
  if (chs.length === 1) chs.push(chs[0]!);
  // Tone + dynamics in an offline graph.
  const ctx = new OfflineAudioContext(2, chs[0]!.length, sr);
  const src = ctx.createBufferSource();
  const b2 = ctx.createBuffer(2, chs[0]!.length, sr);
  b2.copyToChannel(chs[0]! as Float32Array<ArrayBuffer>, 0);
  b2.copyToChannel(chs[1]! as Float32Array<ArrayBuffer>, 1);
  src.buffer = b2;
  const chain: AudioNode[] = [];
  const biq = (type: BiquadFilterType, f: number, gain = 0, q = 0.7) => {
    const n = ctx.createBiquadFilter();
    n.type = type;
    n.frequency.value = f;
    n.gain.value = gain;
    n.Q.value = q;
    chain.push(n);
  };
  const v = o.voicing ?? "song";
  if (v !== "none") {
    biq("highpass", v === "voice" ? 85 : 35, 0, 0.7);
    biq("peaking", 250, v === "voice" ? -3 : -2, 1);
    biq("peaking", 3200, v === "voice" ? 3 : 2, 0.8);
    biq("highshelf", 10000, v === "voice" ? 2 : 3, 0.7);
    if (v === "song") biq("lowshelf", 90, 2, 0.7);
  }
  const comp = ctx.createDynamicsCompressor();
  comp.threshold.value = -20;
  comp.knee.value = 8;
  comp.ratio.value = 2.5;
  comp.attack.value = 0.012;
  comp.release.value = 0.22;
  chain.push(comp);
  let node: AudioNode = src;
  for (const n of chain) {
    node.connect(n);
    node = n;
  }
  node.connect(ctx.destination);
  src.start(0);
  const toned = await ctx.startRendering();
  // Loudness to target, then a brick-wall-ish limiter at −1 dBTP.
  const L = [toned.getChannelData(0), toned.getChannelData(1)];
  const lufs = loudness(L, sr);
  const gain = Math.pow(10, ((o.target ?? -14) - lufs) / 20);
  const ceil = Math.pow(10, -1 / 20);
  const look = Math.round(sr * 0.005);
  const env = new Float32Array(L[0]!.length);
  // Peak envelope with lookahead and smooth release.
  let g = 1;
  const rel = Math.exp(-1 / (sr * 0.08));
  for (let i = L[0]!.length - 1; i >= 0; i--) {
    let pk = 0;
    for (const ch of L) pk = Math.max(pk, Math.abs(ch[i]! * gain));
    env[i] = pk;
  }
  const peakAhead = new Float32Array(env.length);
  for (let i = 0; i < env.length; i++) {
    let m = 0;
    for (let k = 0; k <= look && i + k < env.length; k += 8) m = Math.max(m, env[i + k]!);
    peakAhead[i] = m;
  }
  const out = new AudioBuffer({ numberOfChannels: 2, length: L[0]!.length, sampleRate: sr });
  const o0 = out.getChannelData(0),
    o1 = out.getChannelData(1);
  const w = o.width ?? 0.15;
  for (let i = 0; i < env.length; i++) {
    const need = peakAhead[i]! > ceil ? ceil / peakAhead[i]! : 1;
    g = need < g ? need : 1 - (1 - g) * rel;
    const l = L[0]![i]! * gain * g,
      r = L[1]![i]! * gain * g;
    const mid = (l + r) / 2,
      side = ((l - r) / 2) * (1 + w);
    o0[i] = Math.max(-ceil, Math.min(ceil, mid + side));
    o1[i] = Math.max(-ceil, Math.min(ceil, mid - side));
  }
  return out;
}

/* ----------------------------------- sync ----------------------------------- */

/** Onset envelope at 100 Hz (half-wave rectified energy rise in 3 bands). */
export function onsetEnvelope(x: Float32Array, sr: number): Float32Array {
  const hop = Math.round(sr / 100);
  const n = Math.floor(x.length / hop);
  const env = new Float32Array(n);
  let prev = [0, 0, 0];
  const lp1 = Math.exp((-2 * Math.PI * 200) / sr),
    lp2 = Math.exp((-2 * Math.PI * 2000) / sr);
  let a = 0,
    b = 0;
  for (let f = 0; f < n; f++) {
    const e = [0, 0, 0];
    for (let i = f * hop; i < (f + 1) * hop; i++) {
      const v = x[i]!;
      a = lp1 * a + (1 - lp1) * v;
      b = lp2 * b + (1 - lp2) * v;
      e[0]! += a * a;
      e[1]! += (b - a) * (b - a);
      e[2]! += (v - b) * (v - b);
    }
    const le = e.map((q) => Math.log(1e-7 + q));
    env[f] = Math.max(0, le[0]! - prev[0]!) + Math.max(0, le[1]! - prev[1]!) + Math.max(0, le[2]! - prev[2]!);
    prev = le;
  }
  // Normalise.
  let m = 0;
  for (const v of env) m += v;
  m /= Math.max(1, n);
  for (let i = 0; i < n; i++) env[i] = env[i]! - m;
  return env;
}

/**
 * Where does `clip` (a phone recording) sit inside `song`? Returns the song time (s) that lines up with the clip's
 * start, and a confidence 0..1.
 */
export function findOffset(clip: Float32Array, song: Float32Array, sr: number): { offset: number; confidence: number } {
  const a = onsetEnvelope(clip, sr),
    b = onsetEnvelope(song, sr);
  const n = a.length;
  let best = 0,
    bestV = -Infinity,
    second = -Infinity;
  const norm = Math.sqrt(a.reduce((s, v) => s + v * v, 0)) + 1e-9;
  for (let lag = 0; lag + n <= b.length; lag++) {
    let s = 0,
      e = 0;
    for (let i = 0; i < n; i++) {
      s += a[i]! * b[lag + i]!;
      e += b[lag + i]! * b[lag + i]!;
    }
    const v = s / (norm * (Math.sqrt(e) + 1e-9));
    if (v > bestV) {
      if (Math.abs(lag - best) > 50) second = bestV;
      bestV = v;
      best = lag;
    } else if (v > second && Math.abs(lag - best) > 50) second = v;
  }
  const conf = Math.max(0, Math.min(1, (bestV - Math.max(0, second)) * 4 + bestV));
  return { offset: best / 100, confidence: Math.round(conf * 100) / 100 };
}
