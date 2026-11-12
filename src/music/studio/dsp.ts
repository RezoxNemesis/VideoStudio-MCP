/**
 * Small sound-making tools for the Studio bank (./bank.ts): oscillators without aliasing, filters, plucked strings,
 * noise, envelopes and seamless loops. Everything is plain arithmetic on Float32Array at SR samples a second.
 */
export const SR = 32000;
export const TAU = Math.PI * 2;

export type Rnd = () => number;
export function rng(seed: number): Rnd {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const hz = (key: number): number => 440 * Math.pow(2, (key - 69) / 12);
export const buf = (seconds: number): Float32Array => new Float32Array(Math.max(1, Math.round(seconds * SR)));
export const clamp = (v: number, a: number, b: number): number => Math.max(a, Math.min(b, v));
/** Exponential fall: 1 at t = 0, 1/1000 (-60 dB) after `t60` seconds. */
export const fall = (t: number, t60: number): number => Math.exp((-6.908 * t) / t60);

/** The step that hides the corner of a saw or square from the ear (polyBLEP). */
function blep(t: number, dt: number): number {
  if (t < dt) {
    const x = t / dt;
    return x + x - x * x - 1;
  }
  if (t > 1 - dt) {
    const x = (t - 1) / dt;
    return x * x + x + x + 1;
  }
  return 0;
}

/** A sawtooth / pulse oscillator. `step(freq)` gives the next saw sample; `pulse(freq, width)` the next pulse sample. */
export class Osc {
  ph: number;
  constructor(phase = 0) {
    this.ph = phase % 1;
  }
  saw(f: number): number {
    const dt = f / SR;
    const v = 2 * this.ph - 1 - blep(this.ph, dt);
    this.ph += dt;
    if (this.ph >= 1) this.ph -= 1;
    return v;
  }
  pulse(f: number, width = 0.5): number {
    const dt = f / SR;
    let v = this.ph < width ? 1 : -1;
    v += blep(this.ph, dt);
    let p2 = this.ph + 1 - width;
    if (p2 >= 1) p2 -= 1;
    v -= blep(p2, dt);
    this.ph += dt;
    if (this.ph >= 1) this.ph -= 1;
    return v;
  }
  sine(f: number): number {
    const v = Math.sin(TAU * this.ph);
    this.ph += f / SR;
    if (this.ph >= 1) this.ph -= 1;
    return v;
  }
  tri(f: number): number {
    const v = 1 - 4 * Math.abs(this.ph - 0.5);
    this.ph += f / SR;
    if (this.ph >= 1) this.ph -= 1;
    return v;
  }
}

/** A two-pole filter with low, band and high outputs that stays stable while its cutoff moves. */
export class Svf {
  private a = 0;
  private b = 0;
  private g = 0;
  private fc = -1;
  lp = 0;
  bp = 0;
  hp = 0;
  run(x: number, fc: number, q = 0.707): number {
    if (fc !== this.fc) {
      this.fc = fc;
      this.g = Math.tan((Math.PI * clamp(fc, 10, SR * 0.45)) / SR);
    }
    const g = this.g, k = 1 / q;
    const a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
    const v3 = x - this.b;
    const v1 = a1 * this.a + a2 * v3;
    const v2 = this.b + a2 * this.a + a3 * v3;
    this.a = 2 * v1 - this.a;
    this.b = 2 * v2 - this.b;
    this.lp = v2;
    this.bp = v1;
    this.hp = x - k * v1 - v2;
    return v2;
  }
}

/** One-pole smoothing (low-pass) with the given cutoff. */
export class Lp1 {
  y = 0;
  private k: number;
  constructor(fc: number) {
    this.k = 1 - Math.exp((-TAU * fc) / SR);
  }
  run(x: number): number {
    this.y += (x - this.y) * this.k;
    return this.y;
  }
}

/** White noise in place, -1…1. */
export function noise(n: number, r: Rnd): Float32Array {
  const o = new Float32Array(n);
  for (let i = 0; i < n; i++) o[i] = r() * 2 - 1;
  return o;
}

/** Filter a whole buffer (returns a new one). kind: low, band or high. */
export function filt(x: Float32Array, kind: "lp" | "bp" | "hp", fc: number | ((i: number) => number), q = 0.707): Float32Array {
  const f = new Svf(), o = new Float32Array(x.length);
  for (let i = 0; i < x.length; i++) {
    f.run(x[i]!, typeof fc === "number" ? fc : fc(i), q);
    o[i] = kind === "lp" ? f.lp : kind === "bp" ? f.bp : f.hp;
  }
  return o;
}

/**
 * A plucked or struck string (Karplus–Strong): a burst of noise goes round a delay as long as one period and is
 * softened a little on every lap. `bright` 0…1 is how slowly the top dies; `t60` how long the note rings.
 * `buzz` adds the flat-bridge buzz of sitar and tanpura: the string's length gives a little with its own swing.
 */
export function pluck(f: number, seconds: number, r: Rnd, o: { bright?: number; t60?: number; buzz?: number; pick?: number; hard?: number } = {}): Float32Array {
  const n = Math.round(seconds * SR);
  const out = new Float32Array(n);
  const period = SR / f;
  // the softening filter below shortens a lap by `s` samples and the tuning all-pass adds `d`: N - s + d = one period
  // (high strings lose their top far faster per second, having many more laps: the softening eases off with pitch)
  const s = clamp(0.5 - 0.48 * (o.bright ?? 0.6), 0.01, 0.5) * clamp(330 / f, 0.12, 1);
  const N = Math.max(2, Math.floor(period + s - 0.1));
  const d = period + s - N;
  const c = (1 - d) / (1 + d);
  const line = new Float32Array(N + 4);
  // the pluck: noise, darker for a soft touch, with a notch where the string is plucked
  const lp = new Lp1(clamp(f * (3 + 14 * (o.hard ?? 0.6)), 300, SR * 0.4));
  for (let i = 0; i < N; i++) line[i] = lp.run(r() * 2 - 1);
  const pick = Math.max(1, Math.round(N * (o.pick ?? 0.18)));
  for (let i = N - 1; i >= pick; i--) line[i] = line[i]! - line[i - pick]! * 0.9;
  let mean = 0;
  for (let i = 0; i < N; i++) mean += line[i]!;
  mean /= N;
  let pk = 0;
  for (let i = 0; i < N; i++) {
    line[i] = line[i]! - mean;
    pk = Math.max(pk, Math.abs(line[i]!));
  }
  if (pk > 0) for (let i = 0; i < N; i++) line[i] = line[i]! / pk;
  // loss per lap for the ring time asked for, and the softening filter
  const laps = (o.t60 ?? 2.5) * f;
  const gain = Math.pow(0.001, 1 / Math.max(1, laps));
  const buzz = o.buzz ?? 0;
  let w = 0, prev = 0, ap = 0;
  for (let i = 0; i < n; i++) {
    const x = line[w]!;
    out[i] = x;
    const nx = line[(w + 1) % N]!;
    // two-point average: the top of the sound dies first, as on a real string
    let y = (x * (1 - s) + nx * s) * gain;
    // fine tuning: a first-order all-pass delays by the fraction of a sample the whole number left out
    const t = c * (y - ap) + prev;
    prev = y;
    ap = t;
    y = t;
    // the flat bridge: when the string swings towards it the string touches earlier and the sound brightens
    if (buzz > 0) {
      const lim = 0.28 * (1 - 0.6 * buzz);
      if (y > lim) y = lim + (y - lim) * (1 - buzz);
    }
    line[w] = y;
    w = (w + 1) % N;
  }
  return out;
}

/** Scale a buffer so its loudest sample is `peak`. */
export function norm(x: Float32Array, peak = 0.9): Float32Array {
  let m = 0;
  for (let i = 0; i < x.length; i++) m = Math.max(m, Math.abs(x[i]!));
  if (m > 0) {
    const g = peak / m;
    for (let i = 0; i < x.length; i++) x[i] = x[i]! * g;
  }
  return x;
}

/** Take out any steady offset and the rumble below `fc`. */
export function dc(x: Float32Array, fc = 18): Float32Array {
  const k = Math.exp((-TAU * fc) / SR);
  let px = 0, py = 0;
  for (let i = 0; i < x.length; i++) {
    const y = x[i]! - px + k * py;
    px = x[i]!;
    py = y;
    x[i] = y;
  }
  return x;
}

/** Short fades at the two ends so nothing clicks. */
export function fades(x: Float32Array, inSec = 0.002, outSec = 0.02): Float32Array {
  const a = Math.min(x.length, Math.round(inSec * SR)), b = Math.min(x.length, Math.round(outSec * SR));
  for (let i = 0; i < a; i++) x[i] = x[i]! * (i / a);
  for (let i = 0; i < b; i++) x[x.length - 1 - i] = x[x.length - 1 - i]! * (i / b);
  return x;
}

/**
 * Make the stretch [start, end) loop without a seam: the last `fade` seconds before the end are blended into what
 * came just before the start, so the jump back lands on the sound it left.
 */
export function seamless(x: Float32Array, startSec: number, endSec: number, fadeSec = 0.12, inStep = false): [number, number] {
  const end = Math.min(x.length - 1, Math.round(endSec * SR));
  const fade = Math.round(fadeSec * SR);
  const start = Math.max(fade + 1, Math.round(startSec * SR));
  for (let i = 0; i < fade; i++) {
    const w = i / fade;
    const a = end - fade + i, b = start - fade + i;
    // two takes of the same wave in step add up in size (straight blend); unrelated ones add up in power
    x[a] = inStep ? x[a]! * (1 - w) + x[b]! * w : x[a]! * Math.cos((w * Math.PI) / 2) + x[b]! * Math.sin((w * Math.PI) / 2);
  }
  return [start, end];
}

/** Soft saturation: the warmth and the extra harmonics of a driven stage. */
export const drive = (x: number, amount: number): number => Math.tanh(x * amount) / Math.tanh(amount);

/** A set of fixed resonances (the "mouth" of a voice or the body of an instrument) applied to a buffer. */
export function formants(x: Float32Array, bands: [number, number, number][]): Float32Array {
  const o = new Float32Array(x.length);
  for (const [fc, q, g] of bands) {
    const f = new Svf();
    for (let i = 0; i < x.length; i++) {
      f.run(x[i]!, fc, q);
      o[i] = o[i]! + f.bp * g;
    }
  }
  return o;
}

/** Add `src` into `dst` starting at second `at`, scaled by `g`. */
export function mixIn(dst: Float32Array, src: Float32Array, at = 0, g = 1): void {
  const o = Math.round(at * SR);
  for (let i = 0; i < src.length && o + i < dst.length; i++) dst[o + i] = dst[o + i]! + src[i]! * g;
}
