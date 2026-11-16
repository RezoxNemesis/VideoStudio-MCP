// THE CAST at the drawn standard (STYLE.md): people made of library PARTS, so every one comes out at the same standard
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

/** The cast by name. (It grows: men, women, boys, girls, elders — each a recipe of the parts above.) */
export const CAST = { father };
