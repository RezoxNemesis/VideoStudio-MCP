// THE TROUPE at the drawn standard (STYLE.md): people made of library PARTS, so every one comes out at the same standard
// and a part made better later makes all of them better. A person of the cast is { body, design }:
//   motion: { bodies: true, actors: [{ id: 'papa', ...P.body, at: [0, 0], yaw: 0 }], … }      a BARE Stage body
//   actors: [{ id: 'papa', design: P.design, … }]                                             the engine cuts the clothes
// The parts: a body (the Stage's sculpting: age, build, face detail), clothes (`wear`: engine/wardrobe.js, an OUTFIT
// in a palette), hair (a mass with designed locks: layeredHair), a designed beard, a fine dark line.
// The first of the cast is the father of the Papa film. (Rules: devtests/cast.mjs; the sheet to judge on: a film with
// one person standing in even light, whole, to the waist, the face, the profile.)
import { makeDesign } from '../engine/character.js';

const shadeOf = (hex, k) => { const n = parseInt(hex.slice(1), 16), f = (v) => Math.max(0, Math.min(255, Math.round(v + (k < 0 ? v * k : (255 - v) * k)))); return '#' + [n >> 16 & 255, n >> 8 & 255, n & 255].map((v) => f(v).toString(16).padStart(2, '0')).join(''); };

// ---- hair: a mass with designed locks laid in two layers by rule
/** The hairline of a head of hair that covers the tops of the ears: [° round from the front, ° up from level]. */
export const HAIRLINE = [[0, 27], [32, 25], [50, 13], [64, 2], [80, -8], [100, -14], [130, -26], [180, -36]];
const lineAt = (line, az) => { const a = Math.abs(az); for (let i = 0; i < line.length - 1; i++) if (a <= line[i + 1][0]) { const u = (a - line[i][0]) / (line[i + 1][0] - line[i][0]); return line[i][1] + (line[i + 1][1] - line[i][1]) * u * u * (3 - 2 * u); } return line[line.length - 1][1]; };
/**
 * A head of hair as a `hair3`: one smooth mass, an upper layer of broad locks off the parting whose tips stand off
 * the head (the waves of the outline), a lower layer down to the hairline with ends turned out, a fringe of three
 * pointed locks, two loose strands at the crown.
 * o: { vol: 0.02 (metres of body), lift: 1 (how far the locks stand off, a factor), cols: [22, 50, 78, 106, 134, 162]
 *      (° round the head where the locks of one side lie), ends: 1 (how long the turned-out ends are, a factor),
 *      fringe: true, strands: true, line: HAIRLINE }
 */
export function layeredHair(o = {}) {
  const L = [], line = o.line ?? HAIRLINE, cols = o.cols ?? [22, 50, 78, 106, 134, 162], lf = o.lift ?? 1, en = o.ends ?? 1;
  for (const sd of [1, -1]) cols.forEach((az, c) => {
    const j = ((c * 7 + (sd > 0 ? 0 : 3)) % 5) / 4 - 0.5, a = sd * az; // (a steady little unevenness from lock to lock)
    L.push({ from: [a * 0.22, 80 + 4 * j], via: [a * 0.7, 66 + 6 * j], to: [a, 34 + 8 * j], w: 0.074 + 0.012 * j, lift: [0.004, 0.022 * lf, (0.02 + 0.008 * j) * lf], broad: 0.8 });
    L.push({ from: [a * 0.75, 52 + 5 * j], via: [a * 0.96, 30], to: [a, lineAt(line, a) + 4], w: 0.078 - 0.01 * j, lift: [0.006, 0.016 * lf, 0.017 * lf], hang: az < 60 ? 0 : (0.024 + 0.01 * j) * en, flick: 0.55, broad: 0.85 });
  });
  L.push({ from: [180, 84], to: [180, 30], w: 0.078, lift: [0.004, 0.022 * lf, 0.02 * lf], broad: 0.8 }, { from: [180, 50], to: [180, lineAt(line, 180) + 4], w: 0.08, lift: [0.006, 0.016 * lf, 0.016 * lf], hang: 0.016 * en, flick: 0.8, broad: 0.85 });
  L.push({ from: [-6, 62], via: [10, 46], to: [28, 24], w: 0.06, lift: [0.004, 0.022 * lf, 0.018 * lf], broad: 0.8 }, { from: [-8, 62], via: [-22, 46], to: [-38, 24], w: 0.06, lift: [0.004, 0.022 * lf, 0.018 * lf], broad: 0.8 });
  if (o.fringe !== false) L.push({ from: [-4, 48], via: [8, 34], to: [16, 17], w: 0.04, lift: [0.004, 0.016, 0.008], broad: 0.7 }, { from: [-6, 47], to: [-9, 14], w: 0.03, lift: [0.004, 0.015, 0.008], broad: 0.7 }, { from: [-9, 48], via: [-20, 34], to: [-31, 18], w: 0.044, lift: [0.004, 0.016, 0.008], broad: 0.7 });
  if (o.strands !== false) L.push({ from: [12, 88], to: [58, 74], w: 0.02, lift: [0.004, 0.02, 0.032] }, { from: [-30, 86], to: [-74, 70], w: 0.018, lift: [0.004, 0.018, 0.028] });
  return { n: 0, mass: { vol: o.vol ?? 0.02, flat: 0.1, line }, locks: L };
}

// ---- outfits: the pieces of engine/wardrobe.js in a palette ("black" leather is a dark grey: its form and lines must show)
export const OUTFITS = {
  /** Office: short-sleeved shirt, tie (null: none, the collar open), belted straight trousers, leather shoes. */
  office: (c = {}) => {
    const shirt = c.shirt ?? '#c9cee8', trousers = c.trousers ?? '#232842', tie = c.tie === undefined ? '#7c2233' : c.tie, open = c.open ?? (tie === null);
    return { shirt: { color: shirt, shade: c.shirtShade ?? shadeOf(shirt, -0.2), sleeve: c.sleeve ?? 'short', pocket: c.pocket ?? true, collar: { open } },
      ...(tie ? { tie: { color: tie, stripe: c.stripe === undefined ? '#d9b9bf' : c.stripe } } : {}),
      trousers: { color: trousers, shade: c.trousersShade ?? shadeOf(trousers, -0.38), fit: c.fit ?? 'straight' },
      belt: { color: c.belt ?? '#26262c', buckle: c.buckle ?? '#b9bdc6' }, shoes: { kind: 'derby', color: c.shoes ?? '#30303a', shade: '#101014', sole: '#0c0c0e' } };
  },
};

/**
 * THE FATHER (Papa film): an office worker of about 38 — thick dark wavy hair with a fringe, heavy brows, a full dark
 * beard, a pale blue short-sleeved shirt, a loosened striped tie, navy trousers, black shoes.
 * o: { young: true (the father of the memory: no beard, his collar closed, no lines), hair, wear (other clothes) }
 */
export function father(o = {}) {
  const body = {
    body: 'man', origin: 'indian', height: 1.78, age: 38, muscle: 0.5, weight: 0.42, proportions: 0.9, hair: 'buzz', beard: 'none', kit: 'none', garments: [],
    detail: { 'head/head-oval': 0.3, 'chin/chin-width-decr': 0.15, 'chin/chin-prominent-incr': 0.15, 'cheek/l-cheek-bones-incr': 0.2, 'cheek/r-cheek-bones-incr': 0.2, 'cheek/l-cheek-volume-decr': 0.15, 'cheek/r-cheek-volume-decr': 0.15,
      'nose/nose-scale-horiz-decr': 0.3, 'nose/nose-volume-decr': 0.35, 'nose/nose-width2-decr': 0.35, 'nose/nose-width3-decr': 0.35, 'nose/nose-nostrils-width-decr': 0.4, 'nose/nose-flaring-decr': 0.3, 'neck/neck-scale-horiz-decr': 0.25 },
  };
  const design = makeDesign({
    name: 'Papa', skin: '#c08b63', headScale: 1.0, ink: '#241a1a', inkScale: 0.62,
    face: { shape: 'male', eye: { w: 0.215, h: 0.092, tilt: 0.03, sharp: 0.45, color: '#4a3020', iris: 0.56, lash: 0.2, flick: 0.03 }, brow: { thick: 0.42, arch: 0.02, color: '#120e0c' }, eye3: { brow: 0.02 },
      lips: 0.35, nose: 0.5, lines: o.young ? 0 : 0.3, frown: !o.young, hairShadow: true,
      beard: o.young ? null : { style: 'full', cheek: 0.42, rough: 0.25, strands: 0.5, density: 0.9, mustache: { density: 0.94 } } },
    hair: { color: '#221815', shade: '#0e0908', shine: '#7a5f52', tufts: [], strands: 0.5, sheen: 0.6 },
    hair3: o.hair ?? layeredHair(), beard: null,
    top: { type: 'tunic', color: '#c9cee8', shade: '#9ea4cf', sleeve: 'short', hem: 0.06, collar: 'v', inner: '#e9e6e0' }, bottom: { type: 'pants', color: '#232842', shade: '#141830' }, shoes: { color: '#30303a', boot: 0 },
    wear: o.wear ?? OUTFITS.office({ shirtShade: '#9ea4cf', trousersShade: '#141830', open: !o.young }),
  });
  return { body, design };
}

// ---- the parts a man of the cast is made of
/** Builds: how the Stage sculpts the body. */
export const BUILDS = { slim: { muscle: 0.35, weight: 0.28 }, average: { muscle: 0.5, weight: 0.42 }, athletic: { muscle: 0.82, weight: 0.45 }, broad: { muscle: 0.7, weight: 0.62 }, heavy: { muscle: 0.4, weight: 0.86 } };
const SHORT_LINE = [[0, 27], [32, 25], [50, 14], [64, 7], [80, 3], [100, -5], [130, -22], [180, -32]]; // (a short cut: the ears are free)
/** Men's hair: each a `hair3` — a mass with designed locks. */
export const HAIR_STYLES = {
  layered: () => layeredHair(),                                                                  // thick, wavy, to the ears, a fringe (the father's)
  tousled: () => layeredHair({ vol: 0.026, lift: 1.3, ends: 1.25 }),                             // fuller and wilder
  neat: () => layeredHair({ vol: 0.016, lift: 0.75, ends: 0.4, fringe: false, strands: false }), // combed, no fringe
  short: () => layeredHair({ vol: 0.012, lift: 0.6, ends: 0, fringe: false, strands: false, cols: [26, 62, 98, 134, 166], line: SHORT_LINE }),
  fringe: () => layeredHair({ vol: 0.014, lift: 0.7, ends: 0.2, strands: false, cols: [26, 62, 98, 134, 166], line: SHORT_LINE }), // short, with a fringe
  crop: () => ({ n: 0, mass: { vol: 0.007, flat: 0.1, line: SHORT_LINE }, locks: [] }),            // cut close: the mass alone
};
export const HAIR_COLORS = {
  black: { color: '#1d1a1e', shade: '#0b0a0d', shine: '#5c5866' }, dark: { color: '#221815', shade: '#0e0908', shine: '#7a5f52' }, brown: { color: '#4a3226', shade: '#2a1b14', shine: '#8f6c55' },
  auburn: { color: '#6a3524', shade: '#3e1d13', shine: '#a8674c' }, grey: { color: '#77757b', shade: '#4c4b53', shine: '#bdbcc4' }, white: { color: '#c9c8cc', shade: '#9a99a2', shine: '#f2f2f4' },
};
/** Beards: painted on the face (engine/face3.js), each a dark mass with one clean outline. */
export const BEARDS = {
  none: null,
  full: { style: 'full', cheek: 0.42, rough: 0.25, strands: 0.5, density: 0.9, mustache: { density: 0.94 } },
  goatee: { style: 'goatee', rough: 0.25, strands: 0.5, density: 0.9, mustache: { density: 0.94 } },
  moustache: { style: 'mustache', strands: 0.3, density: 0.92 },
  chin: { style: 'chin', rough: 0.25, strands: 0.4, density: 0.9 },
  stubble: { style: 'full', cheek: 0.5, rough: 0.45, strands: 0.15, density: 0.3, mustache: { density: 0.34 } },
};
OUTFITS.casual = (c = {}) => OUTFITS.office({ tie: null, shirt: '#ece4d6', trousers: '#5a4a3c', belt: '#4a3324', shoes: '#4a3528', ...c });
/** A T-shirt worn out over trousers. */
OUTFITS.tee = (c = {}) => { const shirt = c.shirt ?? '#7a2a2e', trousers = c.trousers ?? '#2b3040'; return { shirt: { kind: 'tee', color: shirt, shade: c.shirtShade ?? shadeOf(shirt, -0.22), sleeve: 'short' }, trousers: { color: trousers, shade: shadeOf(trousers, -0.38), fit: c.fit ?? 'straight' }, shoes: { kind: 'derby', color: c.shoes ?? '#3a3a44', shade: '#131318', sole: '#e8e4dc' } }; };
// ---- the parts of a face. The shape and the nose sculpt the head (the Stage's own shapes); eyes, brows and lips are drawn.
const both = (name, v) => ({ [`cheek/l-cheek-${name}`]: v, [`cheek/r-cheek-${name}`]: v });
export const FACES = {
  oval: { 'head/head-oval': 0.6 },
  round: { 'head/head-round': 0.7, ...both('volume-incr', 0.35), 'chin/chin-width-incr': 0.2 },
  square: { 'head/head-square': 0.7, 'chin/chin-width-incr': 0.4, 'chin/chin-bones-incr': 0.4 },
  long: { 'head/head-rectangular': 0.7, 'chin/chin-jaw-drop-incr': 0.3 },
  heart: { 'head/head-oval': 0.3, 'chin/chin-width-decr': 0.5, ...both('bones-incr', 0.4) },
  diamond: { ...both('bones-incr', 0.6), ...both('volume-decr', 0.3), 'chin/chin-width-decr': 0.35 },
  lean: { 'head/head-rectangular': 0.35, ...both('volume-decr', 0.5), 'chin/chin-width-decr': 0.2 },
  strong: { 'head/head-square': 0.4, 'chin/chin-prominent-incr': 0.4, 'chin/chin-bones-incr': 0.5, ...both('bones-incr', 0.3) },
};
export const NOSES = {
  straight: {}, narrow: { 'nose/nose-scale-horiz-decr': 0.6 }, broad: { 'nose/nose-scale-horiz-incr': 0.45, 'nose/nose-scale-horiz-decr': 0 }, long: { 'nose/nose-scale-vert-incr': 0.5 },
  hooked: { 'nose/nose-hump-incr': 0.6 }, snub: { 'nose/nose-point-up': 0.6, 'nose/nose-hump-decr': 0.3 },
};
/** Eyes as they are drawn: w and h (of the face's width), tilt (outer corner up +), sharp (how pointed the corners). */
export const EYES = {
  almond: { w: 0.215, h: 0.092, tilt: 0.03, sharp: 0.45 }, round: { w: 0.2, h: 0.118, tilt: 0.0, sharp: 0.15 }, narrow: { w: 0.225, h: 0.074, tilt: 0.04, sharp: 0.6 },
  upturned: { w: 0.215, h: 0.09, tilt: 0.09, sharp: 0.5 }, downturned: { w: 0.215, h: 0.094, tilt: -0.04, sharp: 0.35 }, wide: { w: 0.235, h: 0.106, tilt: 0.02, sharp: 0.35 },
};
export const BROWS = { thick: { thick: 0.42, arch: 0.02 }, straight: { thick: 0.34, arch: 0.0 }, arched: { thick: 0.32, arch: 0.07 }, thin: { thick: 0.2, arch: 0.03 }, heavy: { thick: 0.54, arch: 0.01 } };
export const MOUTHS = {
  medium: { lips: 0.35 }, thin: { lips: 0.18, detail: { 'mouth/mouth-upperlip-volume-decr': 0.5, 'mouth/mouth-lowerlip-volume-decr': 0.4 } }, full: { lips: 0.56 },
  small: { lips: 0.3, detail: { 'mouth/mouth-scale-horiz-decr': 0.5 } }, downturned: { lips: 0.34, detail: { 'mouth/mouth-angles-down': 0.5 } },
};
const FACE = { 'head/head-oval': 0.3, 'nose/nose-scale-horiz-decr': 0.3, 'nose/nose-volume-decr': 0.35, 'nose/nose-width2-decr': 0.35, 'nose/nose-width3-decr': 0.35, 'nose/nose-nostrils-width-decr': 0.4, 'nose/nose-flaring-decr': 0.3 };
const part = (table, key, what) => { if (!(key in table)) throw new Error(`The cast has no ${what} "${key}". It has: ${Object.keys(table).join(', ')}.`); return table[key]; };

/**
 * A man of the cast from a recipe of parts — man({ … }). o: { name, age: 30, build: 'average' (BUILDS), height: 1.75, origin:
 * 'indian', skin: '#c08b63', hair: 'layered' (HAIR_STYLES), hairColor: 'dark' (HAIR_COLORS), beard: 'none' (BEARDS),
 * outfit: 'office' (OUTFITS), palette: { shirt, tie, trousers, fit, … } (the outfit's colours), face: 'oval' (FACES),
 * eyes: 'almond' (EYES), eyeColor: '#4a3020', brows: 'thick' (BROWS), nose: 'straight' (NOSES), mouth: 'medium'
 * (MOUTHS), detail: { … } (more of the Stage's own face sculpting) }. Returns { body, design }.
 */
export function man(o = {}) {
  const b = part(BUILDS, o.build ?? 'average', 'build'), hs = part(HAIR_STYLES, o.hair ?? 'layered', 'hair style'), hc = part(HAIR_COLORS, o.hairColor ?? 'dark', 'hair colour'), bd = part(BEARDS, o.beard ?? 'none', 'beard'), of = part(OUTFITS, o.outfit ?? 'office', 'outfit');
  const fc = part(FACES, o.face ?? 'oval', 'face shape'), ey = part(EYES, o.eyes ?? 'almond', 'kind of eyes'), br = part(BROWS, o.brows ?? 'thick', 'kind of brows'), ns = part(NOSES, o.nose ?? 'straight', 'nose'), mo = part(MOUTHS, o.mouth ?? 'medium', 'mouth');
  const age = o.age ?? 30, wear = of(o.palette ?? {});
  const body = { body: 'man', origin: o.origin ?? 'indian', height: o.height ?? 1.75, age, muscle: b.muscle, weight: b.weight, proportions: 0.9, hair: 'buzz', beard: 'none', kit: 'none', garments: [], detail: { ...FACE, ...fc, ...ns, ...(mo.detail ?? {}), ...(o.detail ?? {}) } };
  const design = makeDesign({
    name: o.name ?? 'Man', skin: o.skin ?? '#c08b63', headScale: 1.0, ink: '#241a1a', inkScale: 0.62,
    face: { shape: 'male', eye: { ...ey, color: o.eyeColor ?? '#4a3020', iris: 0.56, lash: 0.2, flick: 0.03 }, brow: { ...br, color: hc.shade }, eye3: { brow: 0.02 },
      lips: mo.lips, nose: 0.5, lines: age > 48 ? 0.45 : age > 34 ? 0.25 : 0, hairShadow: true, beard: bd ? { ...bd } : null },
    hair: { ...hc, tufts: [], strands: 0.5, sheen: 0.6 }, hair3: hs(), beard: null,
    top: { type: 'tunic', color: wear.shirt.color, sleeve: 'short', hem: 0.06, collar: 'v' }, bottom: { type: 'pants', color: wear.trousers.color }, shoes: { color: wear.shoes.color, boot: 0 },
    wear,
  });
  return { body, design };
}

/** The men of the library: each a recipe for man({ … }). */
export const MEN = {
  arjun: { face: 'heart', eyes: 'wide', brows: 'straight', nose: 'narrow', mouth: 'medium', name: 'Arjun', age: 24, build: 'slim', height: 1.76, hair: 'tousled', hairColor: 'black', beard: 'none', outfit: 'tee', palette: { shirt: '#7a2a2e', trousers: '#2b3040' }, skin: '#c99672' },
  ravi: { face: 'strong', eyes: 'narrow', brows: 'thick', nose: 'straight', mouth: 'thin', name: 'Ravi', age: 31, build: 'athletic', height: 1.8, hair: 'short', hairColor: 'black', beard: 'stubble', outfit: 'casual', palette: { shirt: '#e9e2d4', trousers: '#5a4a3c' }, skin: '#b9825c' },
  sameer: { face: 'oval', eyes: 'almond', brows: 'arched', nose: 'long', mouth: 'small', name: 'Sameer', age: 35, build: 'average', height: 1.73, hair: 'neat', hairColor: 'dark', beard: 'moustache', outfit: 'office', palette: { shirt: '#f1efe9', tie: '#27406b', stripe: '#9fb4d6', trousers: '#3a3b40' }, skin: '#c69068' },
  imran: { face: 'square', eyes: 'downturned', brows: 'heavy', nose: 'broad', mouth: 'full', name: 'Imran', age: 42, build: 'broad', height: 1.78, hair: 'fringe', hairColor: 'dark', beard: 'full', outfit: 'casual', palette: { shirt: '#9db3c8', trousers: '#2e3138', belt: '#26262c', shoes: '#30303a' }, skin: '#b07a52' },
  joseph: { face: 'round', eyes: 'round', brows: 'thin', nose: 'snub', mouth: 'medium', name: 'Joseph', age: 50, build: 'heavy', height: 1.7, hair: 'short', hairColor: 'grey', beard: 'goatee', outfit: 'office', palette: { shirt: '#d9e3d2', tie: null, trousers: '#4a4034', fit: 'loose' }, skin: '#c48c62' },
  dev: { face: 'lean', eyes: 'upturned', brows: 'straight', nose: 'straight', mouth: 'small', name: 'Dev', age: 19, build: 'slim', height: 1.71, hair: 'layered', hairColor: 'brown', beard: 'none', outfit: 'tee', palette: { shirt: '#e8e6df', trousers: '#39507a', fit: 'slim' }, skin: '#d2a17c' },
  harish: { face: 'long', eyes: 'downturned', brows: 'thick', nose: 'hooked', mouth: 'downturned', name: 'Harish', age: 61, build: 'average', height: 1.69, hair: 'neat', hairColor: 'white', beard: 'full', outfit: 'casual', palette: { shirt: '#c9b79a', trousers: '#44403a' }, skin: '#b88660' },
};
/** The cast by name: TROUPE.ravi() → { body, design }; TROUPE.ravi({ outfit: 'tee' }) is Ravi in other clothes. (It grows: women, boys, girls, elders.) */
export const TROUPE = { father, ...Object.fromEntries(Object.entries(MEN).map(([k, r]) => [k, (o = {}) => man({ ...r, ...o })])) };
