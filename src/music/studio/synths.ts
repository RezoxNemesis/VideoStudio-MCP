/**
 * Studio bank, electronic and film sounds: the instruments of today's records (808 bass, supersaws, plucks, FM keys,
 * chip waves, pads, voices) and of trailers (braams, booms, risers). Each maker returns one sampled note for a key;
 * sustained sounds come with a seamless loop, struck and plucked ones carry their own decay.
 */
import { Lp1, Osc, SR, Svf, TAU, buf, clamp, dc, drive, fades, fall, formants, hz, noise, norm, seamless, type Rnd } from "./dsp";

export interface Sampled {
  pcm: Float32Array;
  /** Loop start and end, in samples. */
  loop?: [number, number];
}
export type Maker = (key: number, r: Rnd) => Sampled;

/** A loop of a whole number of periods (for steady, strictly periodic sounds). */
function periodic(x: Float32Array, f: number, startSec: number, lenSec: number): [number, number] {
  const periods = Math.max(1, Math.round(lenSec * f));
  return seamless(x, startSec, startSec + periods / f, Math.min(0.02, startSec * 0.5), true);
}

/* ------------------------------------------------ basses ------------------------------------------------ */

/** 808: a sine that drops onto its note and rings for seconds, driven so small speakers still carry it. */
export const sub808: Maker = (key) => {
  const f = hz(key), x = buf(2.6), o = new Osc();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    const fr = f * (1 + 1.7 * Math.exp(-t / 0.011) + 0.22 * Math.exp(-t / 0.055));
    x[i] = drive(o.sine(fr) * fall(t, 2.8) * Math.min(1, t / 0.0015), 2.4);
  }
  return { pcm: norm(fades(x, 0.0005, 0.1)) };
};

/** Reese: three saws a hair apart, slowly beating, under a low-pass. The moving bass of drum and bass and trap. */
export const reese: Maker = (key, r) => {
  const f = hz(key), x = buf(2.8), det = [-0.0105, 0.0004, 0.0118];
  const os = det.map(() => new Osc(r())), lp = new Svf();
  for (let i = 0; i < x.length; i++) {
    let v = 0;
    for (let k = 0; k < 3; k++) v += os[k]!.saw(f * (1 + det[k]!));
    x[i] = drive(lp.run(v / 3, Math.min(f * 7, 2300), 1.3), 1.5);
  }
  norm(dc(x), 0.85);
  return { pcm: x, loop: seamless(x, 0.6, 2.7, 0.22) };
};

/** Acid: one saw through a ringing filter that snaps shut. */
export const acid: Maker = (key) => {
  const f = hz(key), x = buf(1.7), o = new Osc(), lp = new Svf();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    x[i] = drive(lp.run(o.saw(f), 300 + 3400 * Math.exp(-t / 0.1), 7) * 0.8, 1.8);
  }
  norm(dc(x), 0.85);
  return { pcm: x, loop: periodic(x, f, 1.1, 0.5) };
};

/** FM bass: a sine bent by another, bright at the start. */
export const fmBass: Maker = (key) => {
  const f = hz(key), x = buf(1.4);
  let p = 0;
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    p += f / SR;
    x[i] = Math.sin(TAU * p + (0.7 + 5.2 * Math.exp(-t / 0.085)) * Math.sin(TAU * p)) * (0.75 + 0.25 * Math.exp(-t / 0.3));
  }
  norm(x, 0.85);
  return { pcm: x, loop: periodic(x, f, 0.85, 0.45) };
};

/** Sub: a clean sine with a touch of its octave. */
export const subSine: Maker = (key) => {
  const f = hz(key), x = buf(0.9);
  for (let i = 0; i < x.length; i++) {
    const p = (TAU * f * i) / SR;
    x[i] = (Math.sin(p) + 0.09 * Math.sin(2 * p)) * Math.min(1, i / (0.004 * SR));
  }
  norm(x, 0.85);
  return { pcm: x, loop: periodic(x, f, 0.3, 0.5) };
};

/* ------------------------------------------------ leads, plucks, keys ------------------------------------------------ */

/** Supersaw: seven saws spread a fifth of a semitone either side. The wide lead and chord sound of dance music. */
export const supersaw: Maker = (key, r) => {
  const f = hz(key), x = buf(2.8);
  const det = [-0.0195, -0.0128, -0.0062, 0, 0.0058, 0.0131, 0.0202], g = [0.7, 0.8, 0.9, 1, 0.9, 0.8, 0.7];
  const os = det.map(() => new Osc(r())), hp = new Svf();
  for (let i = 0; i < x.length; i++) {
    let v = 0;
    for (let k = 0; k < 7; k++) v += os[k]!.saw(f * (1 + det[k]!)) * g[k]!;
    hp.run(v / 5, f * 0.7, 0.7);
    x[i] = hp.hp;
  }
  norm(x, 0.85);
  return { pcm: x, loop: seamless(x, 0.6, 2.7, 0.25) };
};

/** Pluck: a saw and a pulse under a filter that closes in a blink. */
export const synthPluck: Maker = (key, r) => {
  const f = hz(key), x = buf(1.5), a = new Osc(r()), b = new Osc(r()), lp = new Svf();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    const fc = Math.min(SR * 0.4, f * 1.5 + 7000 * Math.exp(-t / 0.07) + 900 * Math.exp(-t / 0.5));
    x[i] = lp.run(a.saw(f) * 0.6 + b.pulse(f * 1.003, 0.42) * 0.4, fc, 1.7) * fall(t, 1.6) * Math.min(1, t / 0.001);
  }
  return { pcm: norm(fades(x, 0.0005, 0.05)) };
};

/** FM electric piano: a mellow body and a short bright tine, as on the keyboards of the eighties. */
export const fmPiano: Maker = (key) => {
  const f = hz(key), x = buf(3.2), bright = clamp(1.4 - (key - 36) / 60, 0.5, 1.4);
  let p = 0;
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    p += f / SR;
    const body = Math.sin(TAU * p + (0.25 + 1.5 * bright * Math.exp(-t / 0.35)) * Math.sin(TAU * p));
    const tine = Math.sin(TAU * p + 0.9 * Math.exp(-t / 0.06) * Math.sin(TAU * p * 14));
    x[i] = (body * 0.8 * fall(t, 3.4) + tine * 0.35 * fall(t, 0.9)) * Math.min(1, t / 0.001);
  }
  return { pcm: norm(fades(x, 0.0005, 0.1)) };
};

/** FM bell: partials that are not whole multiples of the note, ringing long. */
export const fmBell: Maker = (key) => {
  const f = hz(key), x = buf(4);
  let p = 0;
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    p += f / SR;
    const a = Math.sin(TAU * p + (0.8 + 4.5 * Math.exp(-t / 0.9)) * Math.sin(TAU * p * 3.5));
    const b = Math.sin(TAU * p * 2.76 + 2.2 * Math.exp(-t / 0.4) * Math.sin(TAU * p * 5.4));
    x[i] = (a * fall(t, 4.2) + b * 0.4 * fall(t, 1.6)) * Math.min(1, t / 0.001);
  }
  return { pcm: norm(fades(x, 0.0005, 0.15)) };
};

/** Lo-fi keys: a soft, slightly wavering piano with a little dust on it, as on an old tape. */
export const lofiKeys: Maker = (key, r) => {
  const f = hz(key), x = buf(2.6), lp = new Svf(), ph = r() * TAU, dust = new Lp1(2600);
  let p = 0;
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    // tape wow and flutter
    p += (f * (1 + 0.0032 * Math.sin(TAU * 0.7 * t + ph) + 0.0011 * Math.sin(TAU * 6.3 * t))) / SR;
    const v = Math.sin(TAU * p) + 0.34 * Math.sin(2 * TAU * p) * Math.exp(-t / 0.5) + 0.13 * Math.sin(3 * TAU * p) * Math.exp(-t / 0.25) + 0.05 * Math.sin(4 * TAU * p) * Math.exp(-t / 0.12);
    const thump = Math.exp(-t / 0.012) * Math.sin(TAU * 140 * t) * 0.25;
    x[i] = lp.run(v + thump, 1500 + 1700 * Math.exp(-t / 0.22), 0.8) * fall(t, 2.6) * Math.min(1, t / 0.003) + dust.run(r() * 2 - 1) * 0.004 * fall(t, 2.2);
  }
  return { pcm: norm(fades(x, 0.001, 0.12)) };
};

/** Chip waves: the square, thin pulse and stepped triangle of early game consoles. */
export const chip = (wave: "square" | "pulse" | "triangle"): Maker => (key) => {
  const f = hz(key), x = buf(0.7), o = new Osc();
  for (let i = 0; i < x.length; i++) {
    const v = wave === "triangle" ? Math.round(o.tri(f) * 7.5) / 7.5 : o.pulse(f, wave === "pulse" ? 0.25 : 0.5);
    x[i] = v * Math.min(1, i / (0.001 * SR));
  }
  norm(x, wave === "triangle" ? 0.9 : 0.6);
  return { pcm: x, loop: periodic(x, f, 0.2, 0.4) };
};

/* ------------------------------------------------ pads and voices ------------------------------------------------ */

const AAH: [number, number, number][] = [[730, 9, 1], [1090, 10, 0.5], [2440, 11, 0.26], [3400, 11, 0.13]];
const OOH: [number, number, number][] = [[330, 9, 1], [870, 10, 0.32], [2250, 11, 0.09], [3000, 11, 0.05]];

/** A small choir: five voices, each with its own slow tremble, shaped by the mouth of the vowel. */
export const choir = (vowel: "aah" | "ooh"): Maker => (key, r) => {
  const f = hz(key), n = Math.round(3 * SR), src = new Float32Array(n);
  for (let v = 0; v < 5; v++) {
    const o = new Osc(r()), tune = 1 + (r() - 0.5) * 0.009, vr = 4.8 + r() * 1.2, vp = r() * TAU, dr = 0.2 + r() * 0.3, dp = r() * TAU;
    const soft = new Lp1(2400);
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      const fr = f * tune * (1 + 0.0042 * Math.min(1, t / 0.5) * Math.sin(TAU * vr * t + vp) + 0.0018 * Math.sin(TAU * dr * t + dp));
      src[i] = src[i]! + soft.run(o.saw(fr));
    }
  }
  const air = noise(n, r);
  const out = formants(src, vowel === "aah" ? AAH : OOH);
  const breath = formants(air, vowel === "aah" ? [[1100, 3, 1], [2800, 3, 0.6]] : [[700, 3, 1], [2200, 3, 0.4]]);
  for (let i = 0; i < n; i++) out[i] = (out[i]! + breath[i]! * 0.02) * Math.min(1, i / (0.08 * SR));
  norm(dc(out), 0.85);
  return { pcm: out, loop: seamless(out, 0.8, 2.9, 0.3) };
};

/** Analog pad: two pulses whose width breathes, a saw an octave down, a slow filter. */
export const analogPad: Maker = (key, r) => {
  const f = hz(key), x = buf(3.8), a = new Osc(r()), b = new Osc(r()), s = new Osc(r()), lp = new Svf(), p1 = r() * TAU, p2 = r() * TAU;
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    const v = a.pulse(f * 0.997, 0.5 + 0.33 * Math.sin(TAU * 0.37 * t + p1)) + b.pulse(f * 1.004, 0.5 + 0.3 * Math.sin(TAU * 0.29 * t + p2)) + s.saw(f / 2) * 0.5;
    x[i] = lp.run(v / 2.5, 1500 + 600 * Math.sin(TAU * 0.13 * t + p1), 0.9);
  }
  norm(dc(x), 0.85);
  return { pcm: x, loop: seamless(x, 0.9, 3.7, 0.35) };
};

/** Glass pad: pure partials that swell and fade one after another. */
export const glassPad: Maker = (key, r) => {
  const f = hz(key), x = buf(3.8);
  for (const k of [1, 2, 3, 4, 5, 7, 9]) {
    if (f * k > SR * 0.42) break;
    const amp = 1 / Math.pow(k, 0.9), rate = 0.12 + r() * 0.3, ph = r() * TAU;
    for (const d of [-0.0016, 0.0017]) {
      const w = (TAU * f * k * (1 + d * (0.5 + r()))) / SR, p0 = r() * TAU;
      for (let i = 0; i < x.length; i++) x[i] = x[i]! + Math.sin(w * i + p0) * amp * (0.55 + 0.45 * Math.sin((TAU * rate * i) / SR + ph));
    }
  }
  norm(x, 0.85);
  return { pcm: x, loop: seamless(x, 0.9, 3.7, 0.4) };
};

/* ------------------------------------------------ film sounds ------------------------------------------------ */

/** Braam: the low, tearing brass blast of trailers. A stack of saws opening under a driven filter. */
export const braam: Maker = (key, r) => {
  const f = hz(key), x = buf(4.4), lp = new Svf(), sub = new Osc();
  const os = [1, 1, 1.4983, 2, 0.5].flatMap((ratio) => [-0.006, 0.007].map((d) => ({ o: new Osc(r()), fr: f * ratio * (1 + d) })));
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    const open = Math.min(1, t / 0.9), close = fall(Math.max(0, t - 1.5), 3.4);
    let v = 0;
    for (const q of os) v += q.o.saw(q.fr);
    x[i] = drive(lp.run(v / 6, 110 + 2700 * open * open * close, 1.2) * 2.4 * (0.25 + 0.75 * open) + sub.sine(f / 2) * 0.5, 3) * close;
  }
  return { pcm: norm(fades(dc(x), 0.01, 0.2)) };
};

/** Sub boom: a falling thud with a long tail, for hits and drops. */
export const subBoom: Maker = (key, r) => {
  const f = hz(key), x = buf(3.2), o = new Osc(), th = new Lp1(180);
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    x[i] = drive(o.sine(f * (1 + 2.6 * Math.exp(-t / 0.05))) * fall(t, 3.2) + th.run(r() * 2 - 1) * 1.5 * Math.exp(-t / 0.05), 2.6);
  }
  return { pcm: norm(fades(x, 0.0005, 0.2)) };
};

/** Riser: noise and a climbing tone that sweep up and tighten over four seconds, then stop dead. */
export const riser: Maker = (key, r) => {
  const f = hz(key), x = buf(4), bp = new Svf(), o = new Osc(), o2 = new Osc(0.3);
  let trem = 0;
  for (let i = 0; i < x.length; i++) {
    const u = i / x.length;
    bp.run(r() * 2 - 1, 300 * Math.pow(30, u), 2.2);
    trem += (4 + 14 * u * u) / SR;
    const tone = (o.saw(f * Math.pow(2, u)) + o2.saw(f * 1.006 * Math.pow(2, u))) * 0.3 * (0.6 + 0.4 * Math.sin(TAU * trem));
    x[i] = (bp.bp * 1.4 + tone) * Math.pow(u, 1.5);
  }
  return { pcm: norm(fades(x, 0.05, 0.004)) };
};

/** Tension strings: a tight cluster a semitone apart, trembling. */
export const tension: Maker = (key, r) => {
  const f = hz(key), x = buf(3), lp = new Svf();
  const groups = [f, f * 1.0595].map((fr) => ({ os: [-0.004, 0, 0.005].map((d) => ({ o: new Osc(r()), fr: fr * (1 + d) })), rate: 10.5 + r() * 3, ph: r() * TAU }));
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    let v = 0;
    for (const g of groups) {
      let s = 0;
      for (const q of g.os) s += q.o.saw(q.fr);
      v += s * (0.55 + 0.45 * Math.sin(TAU * g.rate * t + g.ph));
    }
    x[i] = lp.run(v / 6, 3200, 0.8);
  }
  norm(dc(x), 0.85);
  return { pcm: x, loop: seamless(x, 0.7, 2.9, 0.3) };
};

/** Dark drone: a low tone under a slowly moving filter, with wind in it. */
export const darkDrone: Maker = (key, r) => {
  const f = hz(key), x = buf(5.4), a = new Osc(r()), b = new Osc(r()), lp = new Svf(), wind = new Svf(), p1 = r() * TAU;
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    const tone = lp.run(a.saw(f / 2) + b.saw(f * 1.003) * 0.6, 420 + 260 * Math.sin(TAU * 0.11 * t + p1), 1.4);
    wind.run(r() * 2 - 1, 420 + 160 * Math.sin(TAU * 0.07 * t), 2.5);
    x[i] = tone * 0.7 + wind.bp * 0.22 * (0.6 + 0.4 * Math.sin(TAU * 0.17 * t + 1));
  }
  norm(dc(x), 0.85);
  return { pcm: x, loop: seamless(x, 1.1, 5.3, 0.5) };
};

/** Impact: a burst of noise falling in pitch, a deep thump and a metallic ring. */
export const impact: Maker = (key, r) => {
  const f = hz(key), x = buf(2.8), ns = new Svf(), o = new Osc();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    ns.run(r() * 2 - 1, 200 + 8000 * Math.exp(-t / 0.16), 0.9);
    let metal = 0;
    for (const [k, a] of [[1, 1], [2.76, 0.6], [5.4, 0.35], [8.93, 0.2]] as const) metal += Math.sin(TAU * f * 4 * k * t) * a;
    x[i] = drive(ns.lp * 1.6 * fall(t, 1.3) + o.sine(38 + 80 * Math.exp(-t / 0.07)) * 1.4 * fall(t, 2) + metal * 0.18 * fall(t, 0.7), 2.2);
  }
  return { pcm: norm(fades(x, 0.0005, 0.2)) };
};

/** Reverse swell: a sound that grows out of nothing and is cut at its peak. */
export const swell: Maker = (key, r) => {
  const f = hz(key), x = buf(2.6), lp = new Svf(), os = [-0.006, 0, 0.007].map((d) => ({ o: new Osc(r()), fr: f * (1 + d) }));
  for (let i = 0; i < x.length; i++) {
    const u = i / x.length;
    let v = (r() * 2 - 1) * 0.5;
    for (const q of os) v += q.o.saw(q.fr) * 0.4;
    x[i] = lp.run(v, 300 + 6000 * u * u, 1) * Math.pow(u, 3);
  }
  return { pcm: norm(fades(x, 0.05, 0.02)) };
};
