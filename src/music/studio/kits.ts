/**
 * Studio bank, drum kits: trap / 808, dance, lo-fi, film percussion, and tabla with dholak and dhol. Every kit
 * follows the usual drum layout (36 kick, 38 snare, 42 closed hat, 46 open hat …) so any pattern plays on any kit.
 */
import { Lp1, Osc, SR, Svf, TAU, buf, dc, drive, fades, fall, mixIn, norm, rng, type Rnd } from "./dsp";
import { claps, dholDagga, dholTilli, dholakGe, dholakNa, dholakTa, ghungroo, manjira, sticks, tablaGe, tablaKe, tablaNa, tablaTe, tablaTin, tablaTun } from "./indian";

const done = (x: Float32Array, peak = 0.9): Float32Array => norm(fades(dc(x), 0.0003, 0.02), peak);

/** Noise through a filter with a falling envelope. */
function hiss(seconds: number, kind: "bp" | "hp" | "lp", fc: number | ((t: number) => number), q: number, t60: number, r: Rnd): Float32Array {
  const x = buf(seconds), f = new Svf();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    f.run(r() * 2 - 1, typeof fc === "number" ? fc : fc(t), q);
    x[i] = (kind === "bp" ? f.bp : kind === "hp" ? f.hp : f.lp) * fall(t, t60);
  }
  return x;
}
/** A drum's body: a sine that starts high and drops onto its note. */
function thump(seconds: number, f: number, from: number, dropSec: number, t60: number, push = 1): Float32Array {
  const x = buf(seconds), o = new Osc();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    const v = o.sine(f + (from - f) * Math.exp(-t / dropSec)) * fall(t, t60) * Math.min(1, t / 0.0008);
    x[i] = push > 1 ? drive(v, push) : v;
  }
  return x;
}
/** The metal of hats and cymbals: six square waves at unrelated pitches, as in the classic drum machines. */
function metal(seconds: number, t60: number, hp: number, r: Rnd, scale = 1): Float32Array {
  const x = buf(seconds), fs = [205.3, 304.4, 369.6, 522.7, 540, 800].map((f) => f * 8.1 * scale), os = fs.map(() => new Osc(r())), f = new Svf(), air = new Svf();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    let v = 0;
    for (let k = 0; k < 6; k++) v += os[k]!.pulse(Math.min(fs[k]!, SR * 0.46), 0.5);
    f.run(v / 6, hp, 0.9);
    air.run(r() * 2 - 1, hp * 1.1, 0.7);
    x[i] = (f.hp * 0.75 + air.hp * 0.45) * fall(t, t60);
  }
  return x;
}

const kick808 = (long: boolean): Float32Array => done(mix(thump(long ? 1.8 : 0.7, 47, 170, 0.028, long ? 1.9 : 0.6, 2.2), [clickOf(0.004), 0, 0.5]));
const kickPunch = (): Float32Array => done(mix(thump(0.45, 52, 230, 0.017, 0.36, 3), [clickOf(0.003), 0, 0.7]));
function kickDusty(r: Rnd): Float32Array {
  const x = thump(0.4, 60, 125, 0.03, 0.3), lp = new Lp1(2200);
  for (let i = 0; i < x.length; i++) x[i] = lp.run(x[i]! + (r() * 2 - 1) * 0.02 * fall(i / SR, 0.3));
  return done(x, 0.85);
}
function clickOf(seconds: number): Float32Array {
  const x = buf(seconds);
  for (let i = 0; i < x.length; i++) x[i] = (i % 2 ? -1 : 1) * (1 - i / x.length);
  return x;
}
function mix(base: Float32Array, ...layers: [Float32Array, number, number][]): Float32Array {
  for (const [x, at, g] of layers) mixIn(base, x, at, g);
  return base;
}
function snare(r: Rnd, kind: "808" | "dance" | "lofi" | "big"): Float32Array {
  if (kind === "808") return done(mix(buf(0.35), [thump(0.2, 178, 260, 0.01, 0.14), 0, 0.8], [thump(0.2, 331, 420, 0.01, 0.1), 0, 0.45], [hiss(0.3, "hp", 1600, 0.8, 0.2, r), 0, 0.7]));
  if (kind === "lofi") {
    const x = mix(buf(0.3), [thump(0.2, 190, 240, 0.012, 0.13), 0, 0.7], [hiss(0.28, "lp", 5200, 0.7, 0.16, r), 0, 0.9]);
    return done(x, 0.75);
  }
  if (kind === "big") return done(mix(buf(1.1), [thump(0.3, 165, 250, 0.012, 0.2), 0, 0.9], [hiss(1.05, "bp", 1900, 0.7, 0.9, r), 0, 1], [hiss(0.25, "hp", 4500, 0.8, 0.16, r), 0, 0.5]));
  return done(mix(buf(0.4), [thump(0.2, 205, 330, 0.008, 0.11, 2), 0, 0.9], [hiss(0.36, "bp", 1900, 0.8, 0.24, r), 0, 0.9], [hiss(0.2, "hp", 5200, 0.8, 0.12, r), 0, 0.5]));
}
const hat = (r: Rnd, open: boolean, soft = false): Float32Array => done(metal(open ? 0.55 : 0.07, open ? 0.5 : 0.05, soft ? 5200 : 7000, r), soft ? 0.38 : 0.5);
const crash = (r: Rnd): Float32Array => done(mix(metal(1.8, 1.6, 4200, r, 0.8), [hiss(1.7, "hp", 3200, 0.7, 1.3, r), 0, 0.6]), 0.7);
const ride = (r: Rnd, bell = false): Float32Array => done(mix(metal(1.1, 0.95, bell ? 3000 : 5200, r, bell ? 0.55 : 0.9), [ringOf(bell ? 830 : 5200, 0.6, bell ? 0.5 : 0.15), 0, bell ? 0.6 : 0.15]), 0.55);
function ringOf(f: number, seconds: number, t60: number): Float32Array {
  const x = buf(seconds), w = (TAU * f) / SR;
  for (let i = 0; i < x.length; i++) x[i] = Math.sin(w * i) * fall(i / SR, t60);
  return x;
}
const rim = (r: Rnd): Float32Array => done(mix(buf(0.08), [ringOf(1720, 0.06, 0.035), 0, 1], [ringOf(480, 0.06, 0.03), 0, 0.5], [hiss(0.01, "hp", 3000, 0.8, 0.01, r), 0, 0.7]), 0.7);
function cowbell(): Float32Array {
  const x = buf(0.4), a = new Osc(), b = new Osc(0.3), f = new Svf();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    f.run(a.pulse(540, 0.5) + b.pulse(800, 0.5), 2640, 2.5);
    x[i] = f.bp * (0.4 * fall(t, 0.35) + 0.6 * fall(t, 0.06));
  }
  return done(x, 0.6);
}
const tom = (f: number, t60 = 0.4): Float32Array => done(thump(t60 * 1.2, f, f * 1.9, 0.03, t60, 1.5), 0.85);
const shaker = (r: Rnd, seconds = 0.09): Float32Array => {
  const x = hiss(seconds, "bp", 6200, 1.2, seconds, r);
  for (let i = 0; i < x.length; i++) x[i] = x[i]! * Math.sin((Math.PI * i) / x.length);
  return done(x, 0.45);
};
const tamb = (r: Rnd): Float32Array => done(mix(metal(0.25, 0.2, 6000, r, 1.2), [ghungroo(r, 0.2), 0, 0.6]), 0.5);
const snap = (r: Rnd): Float32Array => done(mix(buf(0.1), [hiss(0.02, "bp", 2400, 2.5, 0.02, r), 0.012, 1], [hiss(0.08, "bp", 1900, 1.5, 0.06, r), 0.012, 0.4]), 0.6);
const blip = (f: number, t60: number): Float32Array => done(ringOf(f, t60 * 1.5, t60), 0.6);

/* film percussion */
function taiko(r: Rnd, f: number): Float32Array {
  return done(mix(thump(1.4, f, f * 2.3, 0.04, 1.2, 2), [hiss(0.06, "bp", 900, 0.9, 0.05, r), 0, 0.8], [hiss(0.9, "lp", 320, 0.7, 0.8, r), 0, 0.35]));
}
function bigBoom(r: Rnd): Float32Array {
  return done(mix(thump(2.6, 34, 130, 0.06, 2.6, 2.6), [hiss(0.3, "lp", 400, 0.7, 0.25, r), 0, 1], [hiss(1.6, "bp", 2400, 0.6, 1.2, r), 0, 0.12]));
}
function anvil(): Float32Array {
  const x = buf(1.4);
  for (const [f, a, t60] of [[1840, 1, 1.2], [2710, 0.8, 0.9], [4120, 0.6, 0.7], [5630, 0.4, 0.5], [7300, 0.3, 0.35]] as const) mixIn(x, ringOf(f, 1.4, t60), 0, a);
  return done(x, 0.6);
}
function whoosh(r: Rnd, reverse = false): Float32Array {
  const n = Math.round((reverse ? 1.6 : 0.8) * SR), x = new Float32Array(n), f = new Svf();
  for (let i = 0; i < n; i++) {
    const u = i / n;
    f.run(r() * 2 - 1, reverse ? 2500 + 5000 * u : 500 + 5200 * Math.sin(Math.PI * u), reverse ? 0.7 : 1.4);
    x[i] = (reverse ? f.hp : f.bp) * (reverse ? Math.pow(u, 3) : Math.pow(Math.sin(Math.PI * u), 2));
  }
  return norm(fades(x, 0.02, reverse ? 0.004 : 0.03), 0.6);
}
const subDrop = (): Float32Array => {
  const x = buf(1.7), o = new Osc();
  for (let i = 0; i < x.length; i++) {
    const t = i / SR;
    x[i] = drive(o.sine(28 + 70 * Math.exp(-t / 0.35)) * fall(t, 1.7), 2);
  }
  return done(x);
};
const tick = (): Float32Array => done(mix(buf(0.05), [ringOf(2900, 0.04, 0.02), 0, 1], [ringOf(4700, 0.03, 0.012), 0, 0.5]), 0.5);
const heart = (): Float32Array => done(mix(buf(0.6), [thump(0.25, 46, 90, 0.02, 0.2), 0, 1], [thump(0.25, 42, 80, 0.02, 0.22), 0.2, 0.75]), 0.8);

export interface DrumSound {
  /** Makes the sound (mono, SR). */
  make: (r: Rnd) => Float32Array;
  /** Sounds in the same group cut each other off (a closed hat stops the open one). */
  group?: number;
  /** -1 left … 1 right. */
  pan?: number;
}
export interface Kit {
  name: string;
  /** Kit number: the program of a drum track. */
  program: number;
  about: string;
  sounds: Record<number, DrumSound>;
}

const d = (make: (r: Rnd) => Float32Array, o: Partial<DrumSound> = {}): DrumSound => ({ make, ...o });
/** The sounds every kit shares unless it brings its own. */
function common(): Record<number, DrumSound> {
  return {
    37: d(rim), 39: d((r) => claps(r, true)), 54: d(tamb, { pan: 0.2 }), 56: d(cowbell, { pan: -0.2 }), 69: d((r) => shaker(r, 0.07), { pan: 0.3 }), 70: d((r) => shaker(r, 0.11), { pan: -0.3 }),
    75: d(sticks), 80: d(() => manjira(false), { group: 3, pan: 0.25 }), 81: d(() => manjira(true), { group: 3, pan: 0.25 }), 82: d((r) => shaker(r, 0.09), { pan: 0.3 }),
    60: d(() => blip(660, 0.12)), 61: d(() => blip(520, 0.14)), 62: d((r) => snap(r)), 63: d(() => tom(240, 0.22)), 64: d(() => tom(170, 0.28)),
  };
}
function toms(base: number, t60: number): Record<number, DrumSound> {
  return { 41: d(() => tom(base, t60), { pan: -0.4 }), 43: d(() => tom(base * 1.18, t60), { pan: -0.25 }), 45: d(() => tom(base * 1.42, t60), { pan: -0.05 }), 47: d(() => tom(base * 1.68, t60), { pan: 0.1 }), 48: d(() => tom(base * 2, t60), { pan: 0.25 }), 50: d(() => tom(base * 2.4, t60), { pan: 0.4 }) };
}

export const KITS: Kit[] = [
  {
    name: "Trap 808 Kit", program: 80, about: "Long 808 kick, tight snare and clap, fast metallic hats",
    sounds: { ...common(), ...toms(70, 0.5), 35: d(() => kick808(true)), 36: d(() => kick808(false)), 38: d((r) => snare(r, "808")), 40: d((r) => claps(r)), 42: d((r) => hat(r, false), { group: 1, pan: 0.15 }), 44: d((r) => hat(r, false, true), { group: 1, pan: 0.15 }), 46: d((r) => hat(r, true), { group: 1, pan: 0.15 }), 49: d(crash, { pan: -0.2 }), 51: d((r) => ride(r), { pan: 0.3 }), 53: d((r) => ride(r, true), { pan: 0.3 }), 57: d(crash, { pan: 0.25 }) },
  },
  {
    name: "Dance Kit", program: 81, about: "Punchy kick, layered snare, bright hats: house, EDM, future bass",
    sounds: { ...common(), ...toms(95, 0.3), 35: d(kickPunch), 36: d(kickPunch), 38: d((r) => snare(r, "dance")), 40: d((r) => done(mix(snare(r, "dance"), [claps(r), 0, 0.7]))), 39: d((r) => claps(r)), 42: d((r) => hat(r, false), { group: 1, pan: 0.15 }), 44: d((r) => hat(r, false, true), { group: 1, pan: 0.15 }), 46: d((r) => hat(r, true), { group: 1, pan: 0.15 }), 49: d(crash, { pan: -0.2 }), 51: d((r) => ride(r), { pan: 0.3 }), 53: d((r) => ride(r, true), { pan: 0.3 }), 55: d((r) => whoosh(r)), 57: d((r) => whoosh(r, true)) },
  },
  {
    name: "Lo-fi Kit", program: 82, about: "Dusty kick, soft snare, brushed hats: lo-fi and chill hip hop",
    sounds: { ...common(), ...toms(85, 0.3), 35: d(kickDusty), 36: d(kickDusty), 38: d((r) => snare(r, "lofi")), 40: d((r) => snare(r, "lofi")), 37: d((r) => snap(r)), 42: d((r) => hat(r, false, true), { group: 1, pan: 0.15 }), 44: d((r) => hat(r, false, true), { group: 1, pan: 0.15 }), 46: d((r) => norm(hat(r, true, true), 0.35), { group: 1, pan: 0.15 }), 49: d((r) => norm(crash(r), 0.4), { pan: -0.2 }), 51: d((r) => norm(ride(r), 0.4), { pan: 0.3 }), 57: d((r) => norm(crash(r), 0.4), { pan: 0.25 }) },
  },
  {
    name: "Film Percussion", program: 83, about: "Taikos, booms, sub drops, anvil, whooshes: trailers and battle scenes",
    sounds: { ...common(), 35: d(bigBoom), 36: d((r) => taiko(r, 58)), 38: d((r) => snare(r, "big")), 40: d((r) => snare(r, "big")), 41: d((r) => taiko(r, 52), { pan: -0.35 }), 43: d((r) => taiko(r, 66), { pan: -0.2 }), 45: d((r) => taiko(r, 80), { pan: 0 }), 47: d((r) => taiko(r, 98), { pan: 0.15 }), 48: d((r) => taiko(r, 118), { pan: 0.3 }), 50: d((r) => taiko(r, 140), { pan: 0.4 }), 42: d(tick, { pan: 0.1 }), 44: d(heart), 46: d(anvil, { pan: -0.15 }), 49: d(crash, { pan: -0.2 }), 51: d(subDrop), 53: d(anvil), 55: d((r) => whoosh(r)), 57: d((r) => whoosh(r, true)) },
  },
  {
    name: "Tabla and Dholak", program: 84, about: "Tabla strokes (60 tin, 61 na, 62 te, 63 tun, 64 ge, 65 ke), dholak (36 ge, 38 na, 37 ta), dhol (41 dagga, 43 tilli), manjira, ghungroo, sticks, claps",
    sounds: {
      60: d(tablaTin, { pan: 0.2 }), 61: d(tablaNa, { pan: 0.2 }), 62: d(tablaTe, { pan: 0.2 }), 63: d(tablaTun, { pan: 0.2 }), 64: d(tablaGe, { pan: -0.2 }), 65: d(tablaKe, { pan: -0.2 }),
      35: d(dholakGe, { pan: -0.15 }), 36: d(dholakGe, { pan: -0.15 }), 38: d(dholakNa, { pan: 0.15 }), 40: d(dholakNa, { pan: 0.15 }), 37: d(dholakTa, { pan: 0.15 }),
      41: d(dholDagga, { pan: -0.1 }), 45: d(dholDagga, { pan: -0.1 }), 43: d(dholTilli, { pan: 0.1 }), 47: d(dholTilli, { pan: 0.1 }), 48: d(tablaTun), 50: d(tablaNa),
      39: d((r) => claps(r)), 42: d(() => manjira(false), { group: 3, pan: 0.3 }), 44: d(() => manjira(false), { group: 3, pan: 0.3 }), 46: d(() => manjira(true), { group: 3, pan: 0.3 }), 49: d(() => manjira(true), { pan: 0.3 }), 51: d(() => manjira(true), { pan: 0.3 }),
      54: d((r) => ghungroo(r), { pan: -0.3 }), 56: d(sticks), 69: d((r) => ghungroo(r, 0.15), { pan: -0.3 }), 70: d((r) => ghungroo(r, 0.2), { pan: -0.3 }), 75: d(sticks), 80: d(() => manjira(false), { group: 3, pan: 0.3 }), 81: d(() => manjira(true), { group: 3, pan: 0.3 }), 82: d((r) => ghungroo(r, 0.18), { pan: -0.3 }),
    },
  },
];

/** A kit's sound for a note, made the same way every time. */
export const drumSeed = (kit: number, note: number): Rnd => rng(kit * 1000 + note * 7 + 11);
