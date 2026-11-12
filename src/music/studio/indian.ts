/**
 * Studio bank, Indian instruments: sitar and tanpura with the buzz of their flat bridges, bansuri, harmonium,
 * santoor, shehnai, sarangi, and the strokes of tabla, dholak and dhol with the small cymbals and bells that
 * keep time beside them. All made by arithmetic (./dsp.ts), so they belong to the app and to whoever uses it.
 */
import { Lp1, Osc, SR, Svf, TAU, buf, dc, fades, fall, formants, hz, mixIn, noise, norm, pluck, seamless, type Rnd } from "./dsp";
import type { Maker } from "./synths";

/** Sitar: a bright plucked string that buzzes against its bridge, with the shimmer of the strings beside it. */
export const sitar: Maker = (key, r) => {
  const f = hz(key);
  const x = pluck(f, 3.4, r, { bright: 0.88, t60: 3.2, buzz: 0.55, pick: 0.07, hard: 0.95 });
  // sympathetic strings: the octave and the fifth ring on, faintly
  mixIn(x, pluck(f * 2, 3.4, r, { bright: 0.9, t60: 4, buzz: 0.4, hard: 0.5 }), 0.012, 0.1);
  mixIn(x, pluck(f * 1.5, 3.4, r, { bright: 0.9, t60: 4, buzz: 0.4, hard: 0.5 }), 0.02, 0.07);
  const body = formants(x, [[380, 2.5, 0.5], [1150, 2.5, 0.35]]);
  for (let i = 0; i < x.length; i++) x[i] = x[i]! + body[i]! * 0.5;
  return { pcm: norm(fades(dc(x), 0.0005, 0.15)) };
};

/** Tanpura: the drone lute. A long, blooming buzz that fills the room behind a raga. */
export const tanpura: Maker = (key, r) => {
  const x = pluck(hz(key), 6, r, { bright: 0.93, t60: 7, buzz: 0.78, pick: 0.5, hard: 0.45 });
  for (let i = 0; i < x.length; i++) x[i] = x[i]! * Math.min(1, 0.35 + i / (0.25 * SR));
  return { pcm: norm(fades(dc(x), 0.002, 0.4)) };
};

/** Santoor: a pair of strings struck with a light hammer. */
export const santoor: Maker = (key, r) => {
  const f = hz(key);
  const x = pluck(f, 3, r, { bright: 0.92, t60: 2.8, pick: 0.12, hard: 1 });
  mixIn(x, pluck(f * 1.0019, 3, r, { bright: 0.92, t60: 2.8, pick: 0.13, hard: 1 }), 0, 0.9);
  mixIn(x, pluck(f * 2, 3, r, { bright: 0.9, t60: 1.6, pick: 0.2, hard: 1 }), 0, 0.22);
  return { pcm: norm(fades(dc(x), 0.0003, 0.15)) };
};

/** Bansuri: the bamboo flute. Mostly its fundamental, breath around it, a slow vibrato that arrives late. */
export const bansuri: Maker = (key, r) => {
  const f = hz(key), x = buf(2.6), air = new Svf(), hiss = new Svf();
  let p = 0;
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    const vib = 0.006 * Math.min(1, Math.max(0, (t - 0.3) / 0.5)) * Math.sin(TAU * 5.2 * t);
    p += (f * (1 + vib - 0.012 * Math.exp(-t / 0.05))) / SR;
    const w = TAU * p;
    const tone = Math.sin(w) + 0.4 * Math.sin(2 * w) + 0.15 * Math.sin(3 * w) + 0.05 * Math.sin(4 * w);
    const n = r() * 2 - 1;
    air.run(n, f * 2, 1.6);
    hiss.run(n, 3500, 0.7);
    const breath = (air.bp * 0.5 + hiss.hp * 0.05) * (1 + 3 * Math.exp(-t / 0.04));
    x[i] = (tone * (0.92 + 0.08 * Math.sin(TAU * 5.2 * t + 1)) + breath * 0.22) * Math.min(1, t / 0.06);
  }
  norm(dc(x), 0.85);
  return { pcm: x, loop: seamless(x, 1, 2.5, 0.25) };
};

/** Harmonium: brass reeds under bellows. Two reeds a hair apart and one an octave up. */
export const harmonium: Maker = (key, r) => {
  const f = hz(key), x = buf(2.3), a = new Osc(r()), b = new Osc(r()), c = new Osc(r()), lp = new Svf(), hp = new Svf();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    const v = a.pulse(f * 0.9985, 0.18) + b.pulse(f * 1.0016, 0.2) + c.pulse(f * 2.001, 0.22) * 0.35;
    hp.run(lp.run(v / 2.4, 4200, 0.8), 110, 0.7);
    x[i] = hp.hp * Math.min(1, t / 0.03) * (0.96 + 0.04 * Math.sin(TAU * 0.9 * t));
  }
  norm(dc(x), 0.8);
  return { pcm: x, loop: seamless(x, 0.6, 2.2, 0.2) };
};

/** Shehnai: the double-reed pipe of weddings. Nasal and bright, sliding into its note. */
export const shehnai: Maker = (key, r) => {
  const f = hz(key), n = Math.round(2.4 * SR), src = new Float32Array(n), o = new Osc(r());
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    const vib = 0.005 * Math.min(1, Math.max(0, (t - 0.25) / 0.4)) * Math.sin(TAU * 5.6 * t);
    src[i] = o.pulse(f * (1 + vib - 0.02 * Math.exp(-t / 0.045)), 0.12);
  }
  const out = formants(src, [[1250, 5, 1], [2500, 6, 0.6], [3400, 6, 0.3]]);
  for (let i = 0; i < n; i++) out[i] = (out[i]! + src[i]! * 0.12) * Math.min(1, i / (0.035 * SR));
  norm(dc(out), 0.85);
  return { pcm: out, loop: seamless(out, 0.9, 2.3, 0.25) };
};

/** Sarangi: a bowed string with a hollow, voice-like body. */
export const sarangi: Maker = (key, r) => {
  const f = hz(key), n = Math.round(2.6 * SR), src = new Float32Array(n), o = new Osc(r()), grit = new Lp1(900);
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    const vib = 0.006 * Math.min(1, Math.max(0, (t - 0.3) / 0.4)) * Math.sin(TAU * 5 * t);
    // the bow never pulls quite evenly
    src[i] = o.saw(f * (1 + vib)) * (1 + 0.25 * grit.run(r() * 2 - 1));
  }
  const out = formants(src, [[480, 4, 1], [1050, 5, 0.7], [2300, 6, 0.4]]);
  for (let i = 0; i < n; i++) out[i] = (out[i]! + src[i]! * 0.3) * Math.min(1, i / (0.08 * SR));
  norm(dc(out), 0.85);
  return { pcm: out, loop: seamless(out, 1, 2.5, 0.25) };
};

/* ------------------------------------------------ drums of India ------------------------------------------------ */

/** Ringing partials: [ratio to f, level, ring time in seconds]. */
function ring(f: number, seconds: number, parts: [number, number, number][]): Float32Array {
  const x = buf(seconds);
  for (const [k, a, t60] of parts) {
    const w = (TAU * f * k) / SR;
    for (let i = 0; i < x.length; i++) x[i] = x[i]! + Math.sin(w * i) * a * fall(i / SR, t60);
  }
  return x;
}
/** A short burst of filtered noise: the slap of a hand or stick. */
function slap(seconds: number, fc: number, q: number, r: Rnd, kind: "bp" | "hp" | "lp" = "bp"): Float32Array {
  const n = noise(Math.round(seconds * SR), r), f = new Svf();
  for (let i = 0; i < n.length; i++) {
    f.run(n[i]!, fc, q);
    n[i] = (kind === "bp" ? f.bp : kind === "hp" ? f.hp : f.lp) * fall(i / SR, seconds);
  }
  return n;
}
const done = (x: Float32Array, peak = 0.9): Float32Array => norm(fades(dc(x), 0.0003, 0.02), peak);

/** The treble drum of the tabla, tuned to `f` (its black spot makes its overtones whole multiples). */
export const DAYAN = 293.7;
export function tablaNa(r: Rnd): Float32Array {
  const x = ring(DAYAN, 0.7, [[2, 1, 0.55], [3, 0.6, 0.38], [4.03, 0.3, 0.26], [5.1, 0.15, 0.16]]);
  mixIn(x, slap(0.012, 3200, 1.2, r), 0, 0.9);
  return done(x);
}
export function tablaTin(r: Rnd): Float32Array {
  const x = ring(DAYAN, 1, [[1, 0.25, 0.3], [2, 1, 0.95], [3, 0.35, 0.5]]);
  mixIn(x, slap(0.008, 2400, 1.2, r), 0, 0.45);
  return done(x, 0.8);
}
export function tablaTun(r: Rnd): Float32Array {
  const x = ring(DAYAN, 1.1, [[1, 1, 1.05], [2, 0.3, 0.5], [3, 0.1, 0.3]]);
  mixIn(x, slap(0.008, 1500, 1, r), 0, 0.35);
  return done(x, 0.85);
}
export function tablaTe(r: Rnd): Float32Array {
  const x = buf(0.09);
  mixIn(x, slap(0.04, 1900, 2, r), 0, 1);
  mixIn(x, ring(DAYAN, 0.08, [[2, 0.5, 0.045]]), 0, 0.5);
  return done(x, 0.7);
}
/** The bass drum of the tabla: the heel of the hand presses the skin and the note bends. */
export function tablaGe(r: Rnd): Float32Array {
  const x = buf(0.95), o = new Osc(), o2 = new Osc();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    const f = 70 + 30 * Math.sin(Math.PI * Math.min(1, t / 0.16)) * Math.exp(-t / 0.3) + 8 * Math.exp(-t / 0.5);
    x[i] = (o.sine(f) + 0.25 * o2.sine(f * 2)) * fall(t, 0.85) * Math.min(1, t / 0.002);
  }
  mixIn(x, slap(0.018, 220, 0.8, r, "lp"), 0, 1.2);
  return done(x);
}
export function tablaKe(r: Rnd): Float32Array {
  const x = buf(0.1);
  mixIn(x, slap(0.05, 700, 1.3, r), 0, 1);
  mixIn(x, ring(110, 0.06, [[1, 0.7, 0.035]]), 0, 0.6);
  return done(x, 0.75);
}

/** Dholak: the two-headed folk drum. Drier and woodier than the tabla. */
export function dholakGe(r: Rnd): Float32Array {
  const x = buf(0.5), o = new Osc();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    x[i] = o.sine(72 + 26 * Math.exp(-t / 0.05)) * fall(t, 0.42) * Math.min(1, t / 0.002);
  }
  mixIn(x, slap(0.02, 300, 0.8, r, "lp"), 0, 1);
  return done(x);
}
export function dholakNa(r: Rnd): Float32Array {
  const x = ring(325, 0.3, [[1, 1, 0.2], [1.6, 0.55, 0.14], [2.3, 0.3, 0.1]]);
  mixIn(x, slap(0.01, 2600, 1.2, r), 0, 0.8);
  return done(x, 0.85);
}
export function dholakTa(r: Rnd): Float32Array {
  const x = buf(0.08);
  mixIn(x, slap(0.045, 1250, 1.6, r), 0, 1);
  return done(x, 0.75);
}

/** Dhol: the big festival drum, a boom from the bass stick and a crack from the thin one. */
export function dholDagga(r: Rnd): Float32Array {
  const x = buf(0.7), o = new Osc();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    x[i] = Math.tanh(o.sine(58 + 60 * Math.exp(-t / 0.03)) * 1.8) * fall(t, 0.6) * Math.min(1, t / 0.001);
  }
  mixIn(x, slap(0.012, 1800, 1, r), 0, 0.6);
  return done(x);
}
export function dholTilli(r: Rnd): Float32Array {
  const x = ring(420, 0.2, [[1, 0.8, 0.12], [1.5, 0.6, 0.1], [2.1, 0.4, 0.08]]);
  mixIn(x, slap(0.03, 2200, 0.8, r, "hp"), 0, 1.1);
  return done(x, 0.85);
}

/** Manjira: the pair of small brass cymbals. Open they ring; closed they give a short "chick". */
export function manjira(open: boolean): Float32Array {
  const x = ring(2637, open ? 1.5 : 0.12, [[1, 1, open ? 1.3 : 0.08], [1.48, 0.7, open ? 1 : 0.07], [2.24, 0.5, open ? 0.7 : 0.05], [2.93, 0.35, open ? 0.5 : 0.04], [3.6, 0.2, open ? 0.35 : 0.03]]);
  return done(x, open ? 0.6 : 0.5);
}
/** Ghungroo: a string of ankle bells shaken once. */
export function ghungroo(r: Rnd, seconds = 0.3): Float32Array {
  const x = buf(seconds);
  for (let k = 0; k < 14; k++) {
    const f = 3200 + r() * 3200, at = r() * seconds * 0.35, w = (TAU * f) / SR, len = Math.round(0.14 * SR), o = Math.round(at * SR), a = 0.4 + r() * 0.6;
    for (let i = 0; i < len && o + i < x.length; i++) x[o + i] = x[o + i]! + Math.sin(w * i) * a * fall(i / SR, 0.13);
  }
  return done(x, 0.5);
}
/** Sticks struck together (dandiya, khartal). */
export function sticks(r: Rnd): Float32Array {
  const x = ring(1150, 0.12, [[1, 1, 0.07], [1.68, 0.6, 0.05], [2.7, 0.3, 0.035]]);
  mixIn(x, slap(0.006, 4000, 1, r), 0, 0.7);
  return done(x, 0.7);
}
/** Hands clapped: a few people, not quite together. */
export function claps(r: Rnd, tight = false): Float32Array {
  const x = buf(0.32);
  const hits = tight ? [0, 0.008, 0.017] : [0, 0.009, 0.019, 0.031];
  for (const at of hits) mixIn(x, slap(0.014, 1100 + r() * 500, 1.6, r), at, 0.8);
  mixIn(x, slap(0.2, 1300, 1.1, r), hits[hits.length - 1]!, 0.9);
  return done(x, 0.8);
}
