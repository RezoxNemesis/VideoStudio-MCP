/**
 * Character presets: ready people (bodies, outfits, hair, colours) and the parts to make new ones.
 * `makeChar` merges, in order: a preset, a body type, an outfit, a hair style and any explicit overrides.
 */
import type { BodyMacro, CharSpec, Garment, HairSpec } from "../human/factory";
import castJson from "./cast.json";

type Rgb = [number, number, number];
export interface CharPreset {
  char: CharSpec;
  kit?: string | null;
}
/** The cast of the Orez film, reusable as presets. */
export const CAST = castJson as unknown as Record<string, CharPreset>;

export const BODIES: Record<string, { height: number; macro: BodyMacro }> = {
  man: { height: 1.76, macro: { gender: 1, age: 28, muscle: 0.6, weight: 0.45, proportions: 0.7 } },
  woman: { height: 1.63, macro: { gender: 0, age: 26, muscle: 0.45, weight: 0.42, proportions: 0.75, cup: 0.45, firm: 0.6 } },
  athlete: { height: 1.82, macro: { gender: 1, age: 24, muscle: 0.85, weight: 0.42, proportions: 0.9 } },
  athlete_woman: { height: 1.7, macro: { gender: 0, age: 24, muscle: 0.7, weight: 0.38, proportions: 0.9, cup: 0.4, firm: 0.7 } },
  giant: { height: 2.02, macro: { gender: 1, age: 38, muscle: 0.98, weight: 0.7, proportions: 0.6 } },
  slim: { height: 1.74, macro: { gender: 1, age: 20, muscle: 0.4, weight: 0.3, proportions: 0.7 } },
  heavy: { height: 1.72, macro: { gender: 1, age: 45, muscle: 0.5, weight: 0.9, proportions: 0.4 } },
  teen_boy: { height: 1.62, macro: { gender: 1, age: 15, muscle: 0.55, weight: 0.38 } },
  teen_girl: { height: 1.56, macro: { gender: 0, age: 15, muscle: 0.45, weight: 0.38, cup: 0.3 } },
  boy: { height: 1.27, macro: { gender: 1, age: 8.5, muscle: 0.5, weight: 0.42 } },
  girl: { height: 1.2, macro: { gender: 0, age: 8, muscle: 0.45, weight: 0.45 } },
  old_man: { height: 1.68, macro: { gender: 1, age: 74, muscle: 0.45, weight: 0.4 } },
  old_woman: { height: 1.55, macro: { gender: 0, age: 72, muscle: 0.35, weight: 0.5 } },
};

/** Regional looks: [caucasian, asian, african] mix for MakeHuman's macro targets plus a matching skin tone. */
export const ORIGINS: Record<string, { race: [number, number, number]; skin: Rgb; hair: Rgb; eye: Rgb }> = {
  indian: { race: [0.45, 0.4, 0.15], skin: [0.62, 0.43, 0.3], hair: [0.03, 0.025, 0.022], eye: [0.2, 0.12, 0.07] },
  east_asian: { race: [0.2, 0.75, 0.05], skin: [0.84, 0.66, 0.53], hair: [0.03, 0.028, 0.03], eye: [0.18, 0.11, 0.07] },
  european: { race: [0.9, 0.07, 0.03], skin: [0.88, 0.68, 0.58], hair: [0.3, 0.2, 0.12], eye: [0.25, 0.4, 0.55] },
  african: { race: [0.05, 0.05, 0.9], skin: [0.33, 0.21, 0.15], hair: [0.02, 0.018, 0.016], eye: [0.14, 0.09, 0.06] },
  latin: { race: [0.6, 0.25, 0.15], skin: [0.72, 0.52, 0.39], hair: [0.05, 0.035, 0.028], eye: [0.22, 0.14, 0.08] },
  arab: { race: [0.7, 0.2, 0.1], skin: [0.74, 0.55, 0.42], hair: [0.04, 0.03, 0.026], eye: [0.2, 0.13, 0.08] },
  mixed: { race: [0.45, 0.45, 0.1], skin: [0.78, 0.6, 0.48], hair: [0.04, 0.032, 0.03], eye: [0.25, 0.16, 0.09] },
};

const g = (kind: Garment["kind"], name: string, p: Record<string, unknown> = {}): Garment => ({ kind, name, ...p });
/** Outfits: garment pieces plus default colours (linear-ish 0…1 RGB) for each piece. */
export const OUTFITS: Record<string, { garments: Garment[]; colors: Record<string, Rgb>; look?: Record<string, unknown> }> = {
  tshirt_jeans: {
    garments: [g("trousers", "trousers", { wide0: 1.06, wide1: 1.14, offset: 0.008 }), g("boots", "shoes", { material: "leather", shaft: 0.07, offset: 0.008 }), g("tunic", "tunic", { sleeve: "short", flare: 1.14, droop: 0, bag: 0.08, offset: 0.008, below_hip: 0.05, vneck: false })],
    colors: { tunic: [0.75, 0.75, 0.73], trousers: [0.09, 0.13, 0.24], shoes: [0.85, 0.85, 0.85] },
  },
  shirt_trousers: {
    garments: [g("trousers", "trousers", { wide0: 1.08, wide1: 1.18, offset: 0.009 }), g("boots", "shoes", { material: "leather", shaft: 0.07, offset: 0.008 }), g("tunic", "tunic", { flare: 1.12, droop: 0, bag: 0.1, offset: 0.009, below_hip: 0.04, vneck: false })],
    colors: { tunic: [0.8, 0.82, 0.86], trousers: [0.07, 0.07, 0.08], shoes: [0.05, 0.04, 0.035] },
  },
  hoodie: {
    garments: [g("trousers", "trousers", { wide0: 1.1, wide1: 1.22, offset: 0.01 }), g("boots", "shoes", { material: "leather", shaft: 0.08, offset: 0.009 }), g("tunic", "tunic", { flare: 1.3, droop: 0.02, bag: 0.3, offset: 0.02, below_hip: 0.08, vneck: false })],
    colors: { tunic: [0.16, 0.17, 0.2], trousers: [0.1, 0.1, 0.11], shoes: [0.8, 0.8, 0.8] },
  },
  suit: {
    garments: [g("trousers", "trousers", { wide0: 1.07, wide1: 1.16, offset: 0.009 }), g("boots", "shoes", { material: "leather", shaft: 0.07, offset: 0.008 }), g("tunic", "tunic", { flare: 1.16, droop: 0, bag: 0.06, offset: 0.016, below_hip: 0.1, vdepth: 0.22 })],
    colors: { tunic: [0.045, 0.05, 0.065], trousers: [0.045, 0.05, 0.065], shoes: [0.03, 0.025, 0.02] },
    look: { trim: [0.75, 0.75, 0.78] },
  },
  school_boy: {
    garments: [g("trousers", "trousers", { wide0: 1.08, wide1: 1.18, offset: 0.009 }), g("boots", "shoes", { material: "leather", shaft: 0.07, offset: 0.008 }), g("tunic", "tunic", { flare: 1.14, droop: 0, bag: 0.08, offset: 0.013, below_hip: 0.07, vneck: false })],
    colors: { tunic: [0.03, 0.035, 0.05], trousers: [0.03, 0.035, 0.05], shoes: [0.04, 0.03, 0.025] },
    look: { trim: [0.75, 0.62, 0.2] },
  },
  school_girl: {
    garments: [g("skirt", "skirt", { length: 0.42, flare: 0.38, offset: 0.016, hemTrim: true }), g("boots", "shoes", { material: "leather", shaft: 0.07, offset: 0.008 }), g("tunic", "tunic", { flare: 1.14, droop: 0, bag: 0.1, offset: 0.011, below_hip: 0.03, vdepth: 0.12 })],
    colors: { tunic: [0.86, 0.86, 0.88], skirt: [0.06, 0.08, 0.2], shoes: [0.08, 0.05, 0.04] },
    look: { trim: [0.06, 0.08, 0.2], sash: [0.6, 0.06, 0.08] },
  },
  dress: {
    garments: [g("skirt", "skirt", { length: 0.75, flare: 0.3, offset: 0.016 }), g("boots", "shoes", { material: "leather", shaft: 0.06, offset: 0.007 }), g("tunic", "tunic", { sleeve: "short", flare: 1.1, droop: 0, bag: 0.05, offset: 0.009, below_hip: 0.02, vneck: false })],
    colors: { tunic: [0.55, 0.08, 0.1], skirt: [0.55, 0.08, 0.1], shoes: [0.08, 0.05, 0.05] },
  },
  kurta: {
    garments: [g("trousers", "trousers", { wide0: 1.12, wide1: 1.3, offset: 0.01 }), g("boots", "shoes", { material: "leather", shaft: 0.05, offset: 0.007 }), g("tunic", "tunic", { flare: 1.3, droop: 0.02, bag: 0.25, offset: 0.012, below_hip: 0.0, vdepth: 0.14 }), g("skirt", "coat", { length: 0.5, flare: 0.1, offset: 0.018, hemTrim: true })],
    colors: { tunic: [0.82, 0.78, 0.66], coat: [0.82, 0.78, 0.66], trousers: [0.85, 0.83, 0.78], shoes: [0.3, 0.18, 0.1] },
    look: { trim: [0.6, 0.4, 0.1] },
  },
  saree_like: {
    garments: [g("skirt", "skirt", { length: 1.0, flare: 0.22, offset: 0.02, hemTrim: true }), g("tunic", "tunic", { sleeve: "short", flare: 1.1, droop: 0, bag: 0.05, offset: 0.009, below_hip: -0.12, vdepth: 0.1 })],
    colors: { tunic: [0.6, 0.1, 0.25], skirt: [0.7, 0.12, 0.3] },
    look: { trim: [0.85, 0.65, 0.2], sash: [0.85, 0.65, 0.2] },
  },
  robe: {
    garments: [g("trousers", "trousers", { wide0: 1.15, wide1: 1.55, offset: 0.012 }), g("boots", "boots", { material: "leather", shaft: 0.3, offset: 0.01 }), g("tunic", "tunic", { flare: 1.55, droop: 0.05, bag: 0.55, offset: 0.014, below_hip: 0 }), g("skirt", "coat", { length: 0.42, flare: 0.12, offset: 0.02, hemTrim: true }), g("wraps", "wraps", { material: "leather", offset: 0.006 })],
    colors: { tunic: [0.07, 0.07, 0.085], coat: [0.065, 0.065, 0.08], trousers: [0.05, 0.05, 0.06], boots: [0.06, 0.045, 0.04], wraps: [0.12, 0.085, 0.06] },
    look: { trim: [0.45, 0.04, 0.05], sash: [0.5, 0.045, 0.055] },
  },
  gi: {
    garments: [g("trousers", "trousers", { wide0: 1.2, wide1: 1.5, offset: 0.012, ankle: 0.1 }), g("tunic", "tunic", { sleeve: "short", flare: 1.4, droop: 0, bag: 0.3, offset: 0.014, below_hip: 0 }), g("skirt", "coat", { length: 0.3, flare: 0.14, offset: 0.018 })],
    colors: { tunic: [0.86, 0.85, 0.82], coat: [0.86, 0.85, 0.82], trousers: [0.86, 0.85, 0.82] },
    look: { sash: [0.03, 0.03, 0.03], trim: [0.8, 0.8, 0.78] },
  },
  armor: {
    garments: [g("trousers", "trousers", { wide0: 1.08, wide1: 1.2, offset: 0.012 }), g("boots", "boots", { material: "leather", shaft: 0.34, offset: 0.012 }), g("tunic", "tunic", { sleeve: "short", flare: 1.15, droop: 0, bag: 0.1, offset: 0.012, below_hip: 0, vneck: false }), g("skirt", "coat", { length: 0.36, flare: 0.16, offset: 0.022 }), g("cuirass", "cuirass", { material: "metal", offset: 0.026, cap: 0.085 }), g("vambrace", "vambrace", { material: "metal", offset: 0.014 }), g("greave", "greave", { material: "metal", offset: 0.016 })],
    colors: { tunic: [0.33, 0.045, 0.045], coat: [0.33, 0.045, 0.045], trousers: [0.07, 0.065, 0.065], boots: [0.06, 0.045, 0.04], cuirass: [0.22, 0.22, 0.25], vambrace: [0.22, 0.22, 0.25], greave: [0.22, 0.22, 0.25] },
  },
  shorts: {
    garments: [g("trousers", "trousers", { wide0: 1.12, wide1: 1.2, offset: 0.009, ankle: 0.42 }), g("boots", "shoes", { material: "leather", shaft: 0.07, offset: 0.008 }), g("tunic", "tunic", { sleeve: "none", flare: 1.1, droop: 0, bag: 0.05, offset: 0.008, below_hip: 0.04, vneck: false })],
    colors: { tunic: [0.8, 0.12, 0.1], trousers: [0.06, 0.06, 0.07], shoes: [0.85, 0.85, 0.85] },
  },
};

export const HAIRS: Record<string, HairSpec> = {
  bald: {},
  buzz: { cap: 0.005, mess: 0.001, line: -0.024 },
  short: { cap: 0.014, mess: 0.002, line: -0.03 },
  messy: { cap: 0.022, mess: 0.008, line: -0.036 },
  bob: { shell: true, cut: 0.05, puff: 1.07, rag: 0.8, cap: 0.016, mess: 0.002, line: -0.042, nobangs: true },
  bangs: { shell: true, cut: 0.07, puff: 1.06, rag: 0.9, cap: 0.016, line: -0.04 },
  shoulder: { shell: true, cut: 0.19, puff: 1.06, rag: 0.9, cap: 0.012, line: -0.03, nobangs: true },
  long: { shell: true, puff: 1.03, rag: 0.9, nobangs: true, cap: 0.012, line: -0.034 },
  long_bangs: { shell: true, puff: 1.04, rag: 0.9, cap: 0.014, line: -0.036 },
  topknot: { cap: 0.014, mess: 0.002, line: -0.03, tail: { length: 0.3, radius: 0.03 } },
  ponytail: { cap: 0.014, mess: 0.002, line: -0.032, tail: { length: 0.6, radius: 0.036 } },
};
export const BEARDS: Record<string, NonNullable<HairSpec["beard"]>> = {
  stubble: { thick: 0.0025, mess: 0.0008, mustache: true },
  short: { thick: 0.007, mess: 0.002, length: 0.03 },
  full: { thick: 0.009, mess: 0.002, length: 0.09 },
  long: { thick: 0.007, mess: 0.002, length: 0.17, mustache: true },
  goatee: { thick: 0.006, mess: 0.0015, length: 0.04, thr: 0.62, mustache: true },
};

export interface CharRequest {
  /** A ready person (swordsman, woman, boy, soldier, king…). */
  preset?: string;
  /** Body type from BODIES, or explicit numbers below. */
  body?: string;
  origin?: string;
  outfit?: string;
  hair?: string;
  beard?: string;
  height?: number;
  age?: number;
  gender?: number;
  muscle?: number;
  weight?: number;
  proportions?: number;
  /** MakeHuman detail targets, e.g. {"nose/nose-hump-incr": 0.3}. */
  detail?: Record<string, number>;
  /** Colours: skin, hair, eye, lip and garment pieces by name (tunic, trousers, skirt, coat, shoes, boots…), as #hex or [r,g,b] 0…1. */
  colors?: Record<string, string | Rgb>;
  /** Extra look values passed to the materials (age, blush, scar, beard, grey, pale, dirt, worn, eyeGlow…). */
  look?: Record<string, unknown>;
  garments?: Garment[];
}

export function rgb(v: string | Rgb | undefined): Rgb | undefined {
  if (!v) return undefined;
  if (Array.isArray(v)) return v;
  const m = /^#?([0-9a-f]{6})$/i.exec(v.trim());
  if (!m) throw new Error(`Colour "${v}" should be #rrggbb or [r,g,b].`);
  const n = parseInt(m[1]!, 16);
  // sRGB → linear, the space the materials expect their base colours in after their own conversion; keep it perceptual.
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
}

const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x)) as T;

/** Build a character spec (and the kit name, if the preset has one). */
export function makeChar(r: CharRequest): { char: CharSpec; kit?: string } {
  let char: CharSpec;
  let kit: string | undefined;
  if (r.preset) {
    const p = CAST[r.preset];
    if (!p) throw new Error(`No character preset "${r.preset}". Presets: ${Object.keys(CAST).join(", ")}.`);
    char = clone(p.char);
    kit = p.kit ?? undefined;
  } else {
    const b = BODIES[r.body ?? "man"];
    if (!b) throw new Error(`No body type "${r.body}". Body types: ${Object.keys(BODIES).join(", ")}.`);
    const o = ORIGINS[r.origin ?? "mixed"] ?? ORIGINS.mixed!;
    char = { height: b.height, macro: { ...b.macro, race: o.race }, detail: {}, garments: [], hair: clone(HAIRS.short!), look: { skin: o.skin, hair: o.hair, eye: o.eye, colors: {} } };
  }
  const look = (char.look ??= {});
  const colors = ((look.colors as Record<string, Rgb> | undefined) ?? (look.colors = {})) as Record<string, Rgb>;
  if (r.origin && r.preset) {
    const o = ORIGINS[r.origin];
    if (!o) throw new Error(`No origin "${r.origin}". Origins: ${Object.keys(ORIGINS).join(", ")}.`);
    char.macro.race = o.race;
    look.skin = o.skin;
    look.hair = o.hair;
    look.eye = o.eye;
  }
  if (r.body && r.preset) {
    const b = BODIES[r.body];
    if (!b) throw new Error(`No body type "${r.body}". Body types: ${Object.keys(BODIES).join(", ")}.`);
    char.height = b.height;
    char.macro = { ...b.macro, race: char.macro.race };
  }
  if (r.outfit || (!r.preset && !r.garments)) {
    const name = r.outfit ?? (char.macro.gender < 0.5 ? "dress" : "tshirt_jeans");
    const o = OUTFITS[name];
    if (!o) throw new Error(`No outfit "${name}". Outfits: ${Object.keys(OUTFITS).join(", ")}.`);
    char.garments = clone(o.garments);
    for (const k of Object.keys(colors)) delete colors[k];
    Object.assign(colors, clone(o.colors));
    delete look.trim;
    delete look.sash;
    Object.assign(look, clone(o.look ?? {}));
  }
  if (r.garments) char.garments = clone(r.garments);
  if (r.hair) {
    const h = HAIRS[r.hair];
    if (!h) throw new Error(`No hair style "${r.hair}". Hair: ${Object.keys(HAIRS).join(", ")}.`);
    const beard = char.hair?.beard;
    char.hair = clone(h);
    if (beard) char.hair.beard = beard;
  }
  if (r.beard) {
    if (r.beard === "none") delete char.hair?.beard;
    else {
      const b = BEARDS[r.beard];
      if (!b) throw new Error(`No beard "${r.beard}". Beards: none, ${Object.keys(BEARDS).join(", ")}.`);
      (char.hair ??= {}).beard = clone(b);
    }
  }
  if (r.height) char.height = r.height;
  if (r.age !== undefined) char.macro.age = r.age;
  if (r.gender !== undefined) char.macro.gender = r.gender;
  if (r.muscle !== undefined) char.macro.muscle = r.muscle;
  if (r.weight !== undefined) char.macro.weight = r.weight;
  if (r.proportions !== undefined) char.macro.proportions = r.proportions;
  if (r.detail) char.detail = { ...(char.detail ?? {}), ...r.detail };
  for (const [k, v] of Object.entries(r.colors ?? {})) {
    const c = rgb(v)!;
    if (k === "skin" || k === "hair" || k === "eye" || k === "lip" || k === "trim" || k === "sash") look[k] = c;
    else colors[k] = c;
  }
  if (r.look) Object.assign(look, r.look);
  if (char.macro.age > 55 && look.age === undefined) look.age = Math.min(0.8, (char.macro.age - 45) / 50);
  return { char, kit };
}
