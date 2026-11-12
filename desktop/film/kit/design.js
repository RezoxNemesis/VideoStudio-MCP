// The cast: one design sheet per character.
import { makeDesign } from '../engine/character.js';

/** A lock that lies on the head: [root az, root el] → [tip az, tip el]; w = half width at the root (head radii). */
const lock = (r, t, w, o = {}) => ({ r, t, w, ...o });
/** A fringe: a row of locks from the crown down over the forehead. list: [root az, tip az, tip el, width?, bend?] */
const fringe = (list, o = {}) => list.map(([a0, a1, e1, w, bend]) => lock([a0, o.rootEl ?? 0.9], [a1, e1], w ?? o.w ?? 0.2, { fringe: true, bend: bend ?? 0, lift: o.lift ?? 0.06, pow: o.pow ?? 0.85, stiff: o.stiff ?? 1.2, belly: o.belly ?? 0.12 }));
/** Spikes: locks that leave the head and point away. list: [root az, root el, tip az, tip el, out, width?] */
const spikes = (list, o = {}) => list.map(([a0, e0, a1, e1, out, w]) => lock([a0, e0], [a1, e1], w ?? o.w ?? 0.2, { lift: out, pow: o.pow ?? 0.9, stiff: o.stiff ?? 1.6, belly: 0.1 }));
/** The two locks that hang in front of the ears, down to height y. */
const sideLocks = (y = -0.68, w = 0.15, o = {}) => [1, -1].map((s) => ({ p: [[s * 0.62, 0.52, 0.44], [s * 0.76, 0.12, 0.42], [s * 0.76, (0.12 + y) / 2, 0.4], [s * (0.7 + (o.out ?? 0)), y, 0.38]], w, pow: o.pow ?? 0.7, stiff: o.stiff ?? 0.5, belly: 0.2 }));
/** Long hair down the back: strands that hang and swing. n strands across, each `len` head radii long. */
const longBack = (n, len, o = {}) => Array.from({ length: n }, (_, i) => { const u = n === 1 ? 0 : (i / (n - 1)) * 2 - 1, az = Math.PI + u * (o.spread ?? 0.9); return { az, el: o.el ?? 0.1, dir: [Math.sin(az) * 0.25, -1, Math.cos(az) * 0.25], curve: [0, 0, 0.12], len: len * (1 - 0.12 * Math.abs(u)), w: o.w ?? 0.3, stiff: o.stiff ?? 0.012, damp: 0.98, gravity: 1, nodes: 7, body: true, collide: true, flat: true, pow: 0.5 }; });
/** A tail of hair tied at the back of the head. */
const tail = (len, o = {}) => ({ at: o.at ?? [0, 0.74, -0.84], az: Math.PI, el: 0.6, dir: o.dir ?? [0, -0.5, -0.87], curve: [0, -0.8, 0.5], len, w: o.w ?? 0.21, stiff: o.stiff ?? 0.006, damp: 0.985, gravity: 1, nodes: 8, body: true, collide: true, pow: 0.5, bulge: 0.3 });
/** A knot of hair (a bun or a topknot). */
const knot = (at, r = 0.3) => ({ p: [at, [at[0], at[1] + r * 0.5, at[2] - r * 0.5], [at[0], at[1] + r * 0.9, at[2] - r * 1.1]], n: 5, w: r, pow: 0.28, belly: 0.6, stiff: 3 });

const BLACK = { color: '#1b1a22', shade: '#0d0c12', hi: '#474b66', ink: '#08070b' };
const BROWN = { color: '#4a3226', shade: '#2e1e18', hi: '#7a5a44', ink: '#1c120e' };
const WHITE = { color: '#e8e6e2', shade: '#bdbcc2', hi: '#ffffff', ink: '#6b6a74' };
const GREY = { color: '#6f6e76', shade: '#4a4a52', hi: '#9c9ca6', ink: '#2a2a30' };
const STEEL = '#7b8594', IRON = '#4b4f5a', CRIMSON = '#7c1f27';

const orezHair = (tailLen) => ({
  ...BLACK, capStyle: { front: 0.6, temple: 0.36, lift: 0.09, top: 0.03, back: 0.03, nape: -0.6 },
  locks: [
    ...fringe([[-0.08, 0.05, 0.0, 0.19, 0.04], [0.3, 0.44, 0.2, 0.22], [-0.4, -0.54, 0.16, 0.22], [0.62, 0.9, 0.1, 0.22, 0.05], [-0.68, -0.96, 0.06, 0.22, -0.05], [0.96, 1.22, -0.06, 0.2, 0.04], [-1.0, -1.26, -0.1, 0.2, -0.04]]),
    ...sideLocks(-0.68),
    ...spikes([[0.9, 1.25, 2.0, 0.95, 0.17, 0.2], [-1.5, 1.1, -2.3, 0.75, 0.16, 0.2], [2.5, 0.85, 2.85, 0.4, 0.2], [-2.7, 0.7, -3.05, 0.25, 0.18]]),
  ],
  tufts: tailLen ? [tail(tailLen)] : [],
});
const soldierFace = (i) => ({ shape: i % 3 === 1 ? 'heavy' : 'male', eye: { w: 0.2, h: 0.11, tilt: 0.1, sharp: 0.4, color: ['#4a3a2c', '#3c4650', '#5a4630'][i % 3], iris: 0.55, lash: 0.2 }, brow: { thick: 0.22, gap: 0.2, arch: 0 }, mouthW: 0.2 });

export const CAST = {
  orez: makeDesign({
    name: 'Orez', skin: '#e4b08a', headScale: 1.12,
    face: { shape: 'male', eye: { w: 0.245, h: 0.16, tilt: 0.17, sharp: 0.55, color: '#b8802c', iris: 0.6, lash: 0.26, flick: 0.1 }, brow: { thick: 0.27, gap: 0.245, arch: 0.02 }, mouthW: 0.19 },
    hair: orezHair(2.9),
    top: { type: 'tunic', color: '#23242c', shade: '#14141b', sleeve: 'long', hem: 0.11, collar: 'v', trim: '#9c2028' },
    sash: { color: '#a3222a', w: 0.05 },
    bottom: { type: 'pants', color: '#1d1e25', shade: '#111117', loose: 2.2 },
    shoes: { color: '#15151a', boot: 0.5 },
    cape: { color: '#1b1b22', lining: '#8f1d23', len: 0.6, w: 0.95, cols: 8, rows: 8 },
    armor: { bracer: '#2e2f3a' },
    sword: { blade: '#e4ebf2', guard: '#b08a3c', grip: '#34161a', len: 0.78 },
  }),
  teen: makeDesign({
    name: 'Orez (fifteen)', skin: '#e6b48e', headScale: 1.18,
    face: { shape: 'male', tweak: { jawW: 0.56, chinW: 0.14, nose: 0.16, round: 11 }, eye: { w: 0.25, h: 0.185, tilt: 0.13, sharp: 0.35, color: '#b8802c', iris: 0.62, lash: 0.22, flick: 0.08 }, brow: { thick: 0.22, gap: 0.27, arch: 0.04 }, mouthW: 0.18 },
    hair: orezHair(1.7),
    top: { type: 'tunic', color: '#55606e', shade: '#3b4350', sleeve: 'short', hem: 0.1, collar: 'v', inner: '#d9d2c4' },
    sash: { color: '#a3222a', w: 0.045 },
    bottom: { type: 'pants', color: '#3a3f4a', shade: '#282c35' },
    shoes: { color: '#3a2e28' },
    armor: { bracer: '#6b5a48' },
    sword: { blade: '#9a7a54', guard: '#6b5236', grip: '#4a3626', len: 0.78, width: 0.017 },
  }),
  boy: makeDesign({
    name: 'Orez (boy)', skin: '#ecbb96', headScale: 1.3,
    build: { k: 1.08 },
    face: { shape: 'child', eye: { w: 0.25, h: 0.235, tilt: 0.06, sharp: 0, color: '#a8742c', iris: 0.66, lash: 0.16, flick: 0.04, el: -0.12, az: 0.45 }, brow: { thick: 0.13, gap: 0.36, arch: 0.1 }, blush: 0.55, mouthW: 0.17 },
    hair: {
      ...BLACK, capStyle: { front: 0.62, temple: 0.38, lift: 0.1, top: 0.04 },
      locks: [
        ...fringe([[0.0, 0.08, 0.14, 0.22, 0.05], [0.36, 0.5, 0.24, 0.22], [-0.36, -0.46, 0.2, 0.22], [0.7, 0.98, 0.12, 0.22], [-0.72, -1.0, 0.1, 0.22], [1.02, 1.3, 0.0, 0.2], [-1.04, -1.32, -0.02, 0.2]]),
        ...spikes([[0.2, 1.3, 0.9, 1.15, 0.26], [-0.5, 1.25, -1.3, 1.0, 0.24], [1.3, 0.95, 1.9, 0.6, 0.24], [-1.4, 0.9, -2.0, 0.55, 0.24], [2.5, 0.9, 2.9, 0.5, 0.26], [-2.6, 0.8, -3.0, 0.4, 0.22], [3.1, 0.3, 3.1, -0.2, 0.2], [2.2, 0.2, 2.5, -0.3, 0.2], [-2.2, 0.2, -2.5, -0.3, 0.2]]),
      ],
    },
    top: { type: 'tunic', color: '#b9a57e', shade: '#8f7d5c', sleeve: 'short', hem: 0.1, collar: 'v', inner: '#efe6d2' },
    sash: { color: '#7a4a2e', w: 0.04 },
    bottom: { type: 'shorts', color: '#5b4a3a' },
    shoes: { color: '#6b4a32' },
    sword: { blade: '#a8875c', guard: '#7a5c3c', grip: '#5a4230', len: 0.5, width: 0.016 },
  }),
  sena: makeDesign({
    name: 'Sena', skin: '#edc3a2', headScale: 1.14,
    build: { female: true, shoulder: 0.9, waist: 0.9, arm: 0.86, neck: 0.85 },
    face: { shape: 'female', eye: { w: 0.25, h: 0.2, tilt: 0.1, sharp: 0.1, color: '#7a4a2a', iris: 0.64, lash: 0.3, flick: 0.16 }, brow: { thick: 0.12, gap: 0.3, arch: 0.12 }, mouthW: 0.17 },
    hair: {
      ...BROWN, capStyle: { front: 0.62, temple: 0.4, lift: 0.085, back: 0.05, nape: -0.62 },
      locks: [
        ...fringe([[0.05, 0.3, 0.22, 0.24, 0.06], [-0.3, -0.62, 0.2, 0.24, -0.06], [0.5, 0.92, 0.05, 0.24, 0.08], [-0.66, -1.0, 0.02, 0.24, -0.06], [0.95, 1.25, -0.2, 0.2], [-1.0, -1.28, -0.22, 0.2]], { pow: 0.75 }),
        ...sideLocks(-0.95, 0.14, { stiff: 0.35 }),
        knot([0, 0.35, -0.95], 0.34),
      ],
      tufts: [{ ...tail(2.1, { at: [0, 0.1, -0.98], dir: [0.15, -0.9, -0.4], w: 0.2 }), bulge: 0.2 }],
    },
    top: { type: 'tunic', color: '#3f5a86', shade: '#2c4166', sleeve: 'wide', sleeveDrop: 0.16, hem: 0.02, collar: 'v', inner: '#f2ead8', trim: '#f2ead8', trimW: 0.02 },
    sash: { color: '#b5292f', w: 0.06 },
    bottom: { type: 'robe', color: '#3f5a86', lining: '#2c4166', len: 0.5, flare: 0.5 },
    shoes: { color: '#7a5a40' },
  }),
  miki: makeDesign({
    name: 'Miki', skin: '#f0c4a4', headScale: 1.32,
    build: { k: 1.05, female: true, shoulder: 0.92 },
    face: { shape: 'child', eye: { w: 0.26, h: 0.25, tilt: 0.05, color: '#5a7a3a', iris: 0.66, lash: 0.22, flick: 0.1, el: -0.12, az: 0.45 }, brow: { thick: 0.1, gap: 0.37, arch: 0.12 }, blush: 0.7, mouthW: 0.16 },
    hair: {
      ...BROWN, color: '#6a4028', shade: '#44281a', hi: '#9a6a48', capStyle: { front: 0.6, temple: 0.4, lift: 0.1 },
      locks: [
        ...fringe([[0.0, 0.0, 0.2, 0.26], [0.4, 0.5, 0.22, 0.24], [-0.4, -0.5, 0.22, 0.24], [0.8, 1.05, 0.1, 0.22], [-0.8, -1.05, 0.1, 0.22]], { pow: 0.6, belly: 0.2 }),
      ],
      // two tails, tied high at the sides
      tufts: [1, -1].map((s) => ({ at: [s * 0.72, 0.5, -0.3], az: s * 1.9, el: 0.5, dir: [s * 0.75, -0.5, -0.3], curve: [-s * 0.3, -0.9, 0.1], len: 1.5, w: 0.2, stiff: 0.02, damp: 0.97, gravity: 1, nodes: 6, body: false, layer: 'auto', pow: 0.5, bulge: 0.35 })),
    },
    top: { type: 'tunic', color: '#d9a13c', shade: '#b07f28', sleeve: 'short', hem: 0.02, collar: 'v', inner: '#f6efdc' },
    sash: { color: '#b5492f', w: 0.04 },
    bottom: { type: 'skirt', color: '#d9a13c', lining: '#b07f28', len: 0.22, flare: 0.8 },
    shoes: { color: '#7a5236' },
  }),
  nia: makeDesign({
    name: 'Nia', skin: '#e8b792', headScale: 1.34,
    build: { k: 1.04, female: true, shoulder: 0.9 },
    face: { shape: 'child', eye: { w: 0.265, h: 0.26, tilt: 0.03, color: '#4a6a8a', iris: 0.68, lash: 0.2, flick: 0.08, el: -0.12, az: 0.45 }, brow: { thick: 0.1, gap: 0.38, arch: 0.14 }, blush: 0.5, mouthW: 0.15 },
    hair: {
      ...BLACK, color: '#3a2c2a', shade: '#241a1a', hi: '#6a5650', capStyle: { front: 0.6, temple: 0.38, lift: 0.1, nape: -0.7, sideburn: -0.3 },
      locks: [
        ...fringe([[0.1, 0.2, 0.16, 0.26, 0.05], [-0.3, -0.45, 0.2, 0.24], [0.55, 0.85, 0.12, 0.24], [-0.7, -1.0, 0.08, 0.24], [1.0, 1.25, -0.1, 0.22], [-1.05, -1.3, -0.14, 0.22]], { pow: 0.7 }),
        ...sideLocks(-0.85, 0.2, { pow: 0.5 }),
        ...spikes([[2.0, 0.1, 2.2, -0.75, 0.1, 0.3], [-2.0, 0.1, -2.2, -0.75, 0.1, 0.3], [2.8, 0.1, 2.9, -0.8, 0.08, 0.3], [-2.8, 0.1, -2.9, -0.8, 0.08, 0.3]], { pow: 0.6 }),
      ],
    },
    top: { type: 'tunic', color: '#9a8f86', shade: '#746a62', sleeve: 'short', hem: 0.02, collar: 'v', inner: '#e4d9c4', trim: '#6b5a4c', trimW: 0.02 },
    sash: { color: '#9a4638', w: 0.034 },
    bottom: { type: 'skirt', color: '#9a8f86', lining: '#746a62', len: 0.26, flare: 0.6 },
    shoes: null,
  }),
  elder: makeDesign({
    name: 'Elder', skin: '#d9a784', headScale: 1.1,
    build: { shoulder: 0.9, chest: 0.92, arm: 0.85 },
    face: { shape: 'elder', eye: { w: 0.2, h: 0.085, tilt: 0.02, sharp: 0.1, color: '#5a4636', iris: 0.6, lash: 0.14 }, brow: { thick: 0.3, gap: 0.2, arch: 0.14, color: '#e8e6e2' }, lines: true, mouthW: 0.2 },
    hair: { ...WHITE, capStyle: { front: 0.6, temple: 0.34, lift: 0.07, bald: 0.34, baldBack: 0.65 }, locks: [...spikes([[1.9, 0.1, 2.2, -0.45, 0.1, 0.24], [-1.9, 0.1, -2.2, -0.45, 0.1, 0.24]], { pow: 0.6 })] },
    beard: { color: '#e8e6e2', length: 0.75, mustache: true },
    top: { type: 'tunic', color: '#7a6248', shade: '#5a4632', sleeve: 'wide', sleeveDrop: 0.14, hem: 0.02, collar: 'v', inner: '#e9dfc8' },
    sash: { color: '#4a3a2c', w: 0.05 },
    bottom: { type: 'robe', color: '#7a6248', lining: '#5a4632', len: 0.5, flare: 0.4 },
    shoes: { color: '#5a4636' },
    staff: { len: 1.7, color: '#6b5238' },
  }),
  ruon: makeDesign({
    name: 'Master Ruon', skin: '#dcae8c', headScale: 1.1,
    build: { shoulder: 0.96, arm: 0.9 },
    face: { shape: 'elder', eye: { w: 0.215, h: 0.09, tilt: 0.12, sharp: 0.5, color: '#3c3a44', iris: 0.6, lash: 0.2 }, brow: { thick: 0.34, gap: 0.19, arch: 0.2, color: '#e8e6e2' }, lines: true, mouthW: 0.19 },
    hair: {
      ...WHITE, capStyle: { front: 0.72, temple: 0.42, lift: 0.07, nape: -0.55 },
      locks: [knot([0, 1.0, -0.25], 0.26), ...sideLocks(-0.5, 0.11)],
      tufts: [{ ...tail(2.4, { at: [0, 0.2, -0.98], dir: [0, -0.95, -0.3], w: 0.2 }), bulge: 0.1 }],
    },
    beard: { color: '#e8e6e2', length: 1.15, mustache: true },
    top: { type: 'tunic', color: '#d9d5cc', shade: '#aaa69e', sleeve: 'wide', sleeveDrop: 0.2, hem: 0.02, collar: 'v', inner: '#8a4a2a', trim: '#c9772a', trimW: 0.02 },
    sash: { color: '#c9772a', w: 0.055 },
    bottom: { type: 'robe', color: '#d9d5cc', lining: '#aaa69e', len: 0.51, flare: 0.45 },
    shoes: { color: '#4a3a30' },
    staff: { len: 1.7, color: '#4a3626' },
  }),
  garrick: makeDesign({
    name: 'General Garrick', skin: '#c99872', headScale: 1.04, inkScale: 1.1,
    build: { k: 1.1, shoulder: 1.12, chest: 1.18, arm: 1.25, leg: 1.15, neck: 1.25, waist: 1.12 },
    face: { shape: 'heavy', eye: { w: 0.19, h: 0.085, tilt: 0.14, sharp: 0.7, color: '#8a8f98', iris: 0.52, lash: 0.24, flick: 0.04 }, brow: { thick: 0.36, gap: 0.17, arch: -0.04, color: '#2a2628' }, scar: { az: -0.5, el: -0.06, len: 0.42 }, lines: true, mouthW: 0.22 },
    hair: { ...GREY, color: '#3a383e', shade: '#242328', hi: '#6a6a74', capStyle: { front: 0.66, temple: 0.4, lift: 0.05, nape: -0.4 }, locks: [...spikes([[0.2, 1.1, 0.6, 0.85, 0.12, 0.2], [-0.4, 1.1, -0.9, 0.85, 0.12, 0.2], [0.0, 0.95, 0.1, 0.72, 0.1, 0.22]], { pow: 0.8 })] },
    beard: { color: '#3a383e', length: 0.16, mustache: true },
    top: { type: 'tunic', color: '#2a2529', shade: '#18151a', sleeve: 'long', hem: 0.13, collar: 'round' },
    sash: { color: '#5c151b', w: 0.06 },
    bottom: { type: 'pants', color: '#231f24', shade: '#141216' },
    shoes: { color: '#1a171a', boot: 0.6 },
    cape: { color: '#5c151b', lining: '#2a0c10', len: 0.66, w: 1.0, cols: 8, rows: 8 },
    armor: { plate: '#43464f', pauldron: '#585c68', bracer: '#43464f', greave: '#43464f' },
    sword: { blade: '#cfd4dc', guard: '#8a6a2e', grip: '#2a1a1a', len: 1.0, width: 0.032 },
  }),
  vess: makeDesign({
    name: 'Captain Vess', skin: '#dcae8a', headScale: 1.08,
    build: { shoulder: 1.02, arm: 1.0 },
    face: { shape: 'male', tweak: { jawW: 0.5, chinW: 0.13, cheekW: 0.66 }, eye: { w: 0.2, h: 0.09, tilt: 0.2, sharp: 0.7, color: '#4a5a3a', iris: 0.52, lash: 0.22 }, brow: { thick: 0.18, gap: 0.2, arch: 0.1, color: '#2a2224' }, mouthW: 0.19 },
    hair: { ...BLACK, color: '#2a2224', capStyle: { front: 0.7, temple: 0.44, lift: 0.04 } },
    beard: { color: '#2a2224', length: 0.02, mustache: true },
    headgear: { type: 'helmet', color: '#7d8695', band: '#5a6270', crest: '#a3222a' },
    top: { type: 'tunic', color: '#5c151b', shade: '#3c0e12', sleeve: 'long', hem: 0.12, collar: 'round' },
    sash: { color: '#2a2529', w: 0.05 },
    bottom: { type: 'pants', color: '#2a2529', shade: '#18151a' },
    shoes: { color: '#1a171a', boot: 0.55 },
    cape: { color: '#7c1f27', lining: '#3c0e12', len: 0.5, w: 0.9, cols: 7, rows: 7 },
    armor: { plate: '#7d8695', pauldron: '#8d96a5', bracer: '#6d7684', greave: '#6d7684' },
    sword: { blade: '#dfe4ea', guard: '#b08a3c', grip: '#2a1a1a', len: 0.82 },
  }),
  aldric: makeDesign({
    name: 'King Aldric', skin: '#e0b090', headScale: 1.06,
    build: { k: 1.08, shoulder: 1.1, chest: 1.2, waist: 1.25, arm: 1.1, neck: 1.2 },
    face: { shape: 'heavy', eye: { w: 0.2, h: 0.1, tilt: 0.08, sharp: 0.5, color: '#4a6a8a', iris: 0.55, lash: 0.2 }, brow: { thick: 0.34, gap: 0.19, arch: 0.02, color: '#a8742c' }, lines: true, mouthW: 0.22 },
    hair: { color: '#b98a3c', shade: '#8a6426', hi: '#e6c070', ink: '#4a3414', capStyle: { front: 0.6, temple: 0.36, lift: 0.08, nape: -0.7, sideburn: -0.3 }, locks: [...sideLocks(-0.7, 0.2, { pow: 0.5 }), ...spikes([[2.2, 0.1, 2.4, -0.8, 0.12, 0.34], [-2.2, 0.1, -2.4, -0.8, 0.12, 0.34], [3.0, 0.1, 3.05, -0.85, 0.1, 0.34]], { pow: 0.5 })] },
    beard: { color: '#b98a3c', length: 0.6, mustache: true },
    headgear: { type: 'crown', color: '#e0b040', gem: '#c0283a', y: 0.66 },
    top: { type: 'tunic', color: '#8a1f28', shade: '#5c1219', sleeve: 'wide', sleeveDrop: 0.18, hem: 0.02, collar: 'v', inner: '#f0e2c0', trim: '#e0b040', trimW: 0.03 },
    sash: { color: '#e0b040', w: 0.06 },
    bottom: { type: 'robe', color: '#8a1f28', lining: '#5c1219', len: 0.5, flare: 0.5 },
    shoes: { color: '#3a2a20' },
    cape: { color: '#f0e9da', lining: '#8a1f28', len: 0.68, w: 1.05, cols: 8, rows: 8 },
  }),
  suiren: makeDesign({
    name: 'Empress Suiren', skin: '#f2d8c6', headScale: 1.1,
    build: { female: true, shoulder: 0.88, waist: 0.86, arm: 0.82, neck: 0.8 },
    face: { shape: 'female', tweak: { chinW: 0.08, jawW: 0.48 }, eye: { w: 0.25, h: 0.13, tilt: 0.24, sharp: 0.6, color: '#3a8a7a', iris: 0.6, lash: 0.34, flick: 0.22 }, brow: { thick: 0.1, gap: 0.3, arch: 0.2 }, mouthW: 0.15 },
    hair: {
      ...BLACK, capStyle: { front: 0.7, temple: 0.46, lift: 0.07, nape: -0.6 },
      locks: [knot([0, 1.02, -0.3], 0.36), knot([0.45, 0.95, -0.45], 0.24), knot([-0.45, 0.95, -0.45], 0.24), ...sideLocks(-1.25, 0.11, { stiff: 0.3 })],
      tufts: longBack(3, 3.4, { spread: 0.6, w: 0.34 }),
    },
    headgear: { type: 'band', color: '#e0b040', y: 0.74, w: 0.12 },
    top: { type: 'tunic', color: '#2f7a6c', shade: '#1f574c', sleeve: 'wide', sleeveDrop: 0.26, hem: 0.02, collar: 'v', inner: '#f6f1e6', trim: '#e0c070', trimW: 0.022 },
    sash: { color: '#f0e9da', w: 0.07 },
    bottom: { type: 'robe', color: '#2f7a6c', lining: '#1f574c', len: 0.52, flare: 0.55 },
    shoes: { color: '#2a2a2a' },
  }),
  kaito: makeDesign({
    name: 'King Kaito', skin: '#c8926a', headScale: 1.1,
    build: { shoulder: 1.06, chest: 1.06, arm: 1.12 },
    face: { shape: 'male', eye: { w: 0.23, h: 0.12, tilt: 0.2, sharp: 0.65, color: '#3aa0c0', iris: 0.56, lash: 0.24, flick: 0.1 }, brow: { thick: 0.24, gap: 0.21, arch: 0.02, color: '#d9dde6' }, scar: { az: 0.52, el: -0.2, len: 0.25 }, mouthW: 0.2 },
    hair: {
      color: '#dfe3ea', shade: '#a9b0c0', hi: '#ffffff', ink: '#5a6070', capStyle: { front: 0.62, temple: 0.38, lift: 0.1, nape: -0.62 },
      locks: [
        ...fringe([[0.1, 0.5, 0.25, 0.22, 0.08], [-0.25, -0.2, 0.3, 0.2], [0.55, 1.0, 0.1, 0.22, 0.08], [-0.6, -1.0, 0.2, 0.22], [1.0, 1.3, -0.15, 0.2], [-1.0, -1.25, -0.1, 0.2]]),
        ...sideLocks(-0.9, 0.14, { stiff: 0.35 }),
        ...spikes([[0.4, 1.25, 1.4, 1.0, 0.26], [-0.9, 1.2, -1.9, 0.9, 0.24], [2.6, 0.9, 2.95, 0.45, 0.24]]),
      ],
      tufts: longBack(3, 2.2, { spread: 0.7, w: 0.3 }),
    },
    headgear: { type: 'band', color: '#1f4a7a', y: 0.52, w: 0.13 },
    top: { type: 'tunic', color: '#1f3a5c', shade: '#142740', sleeve: 'long', hem: 0.14, collar: 'v', trim: '#d9b25a' },
    sash: { color: '#d9b25a', w: 0.05 },
    bottom: { type: 'pants', color: '#16202e', shade: '#0d141e' },
    shoes: { color: '#12161c', boot: 0.6 },
    cape: { color: '#16202e', lining: '#2a6a9a', len: 0.62, w: 1.0, cols: 8, rows: 8 },
    armor: { bracer: '#8a6a2e', pauldron: '#2a4a6a' },
    sword: { blade: '#dfeaf2', guard: '#d9b25a', grip: '#1f3a5c', len: 0.86 },
  }),
};

/** One of the army: i picks the face and small differences. kind: 'spear' | 'sword' */
export function soldier(i = 0, o = {}) {
  const skin = ['#d9a680', '#c99872', '#e0b08a', '#b98660'][i % 4], hairC = [BLACK, BROWN, GREY][i % 3];
  return makeDesign({
    name: 'Valdhar soldier', skin, headScale: 1.06,
    build: { shoulder: 1.02 + 0.04 * (i % 3), chest: 1.04, arm: 1.05, k: 1 + 0.02 * ((i * 7) % 4) },
    face: soldierFace(i),
    hair: { ...hairC, capStyle: { front: 0.7, temple: 0.42, lift: 0.03 } },
    beard: i % 4 === 1 ? { color: hairC.color, length: 0.22, mustache: true } : i % 4 === 3 ? { color: hairC.color, length: 0.02, mustache: true } : null,
    headgear: { type: 'helmet', color: STEEL, band: '#5a6270' },
    top: { type: 'tunic', color: CRIMSON, shade: '#4c1218', sleeve: 'long', hem: 0.12, collar: 'round' },
    sash: { color: '#2a2529', w: 0.05 },
    bottom: { type: 'pants', color: '#2e2a30', shade: '#1c191e' },
    shoes: { color: '#1e1a1c', boot: 0.5 },
    armor: { plate: STEEL, pauldron: '#8a94a3', bracer: '#6a7482', greave: '#6a7482' },
    spear: { len: 2.4, shaft: '#6b5340', head: '#cfd6de' },
    shield: { r: 0.4, color: CRIMSON, rim: '#8a94a3', boss: '#c9ced6', back: '#6a5240' },
    sword: { blade: '#d6dbe2', guard: '#8a7440', grip: '#2a2020', len: 0.8 },
    ...o,
  });
}
/** A villager: i picks clothes, hair and face. woman: true for a woman. */
export function villager(i = 0, woman = false, o = {}) {
  const skin = ['#e6b890', '#dcae86', '#efc4a0', '#d6a47c'][i % 4], cloth = ['#8a7450', '#5d6f86', '#7a5a48', '#6f7f5a', '#9a6a4a', '#55606e'][i % 6], hairC = [BLACK, BROWN, BROWN, GREY][i % 4];
  if (woman) return makeDesign({
    name: 'villager', skin, headScale: 1.12, build: { female: true, shoulder: 0.9, waist: 0.92, arm: 0.86, neck: 0.85 },
    face: { shape: 'female', eye: { w: 0.24, h: 0.18, tilt: 0.08, color: ['#6a4a2a', '#4a5a3a', '#5a4a3a'][i % 3], iris: 0.62, lash: 0.26, flick: 0.12 }, brow: { thick: 0.12, gap: 0.3, arch: 0.1 }, mouthW: 0.17 },
    hair: { ...hairC, capStyle: { front: 0.62, temple: 0.4, lift: 0.08, nape: -0.62 }, locks: [...fringe([[0.1, 0.4, 0.2, 0.24, 0.05], [-0.3, -0.7, 0.14, 0.24, -0.05], [0.6, 1.0, 0.0, 0.24], [-0.75, -1.1, -0.05, 0.22]], { pow: 0.7 }), knot([0, 0.5, -0.92], 0.33)] },
    top: { type: 'tunic', color: cloth, shade: undefined, sleeve: 'long', hem: 0.02, collar: 'v', inner: '#efe6d2' },
    sash: { color: ['#b5492f', '#3f5a86', '#7a4a2e'][i % 3], w: 0.05 },
    bottom: { type: 'robe', color: cloth, len: 0.5, flare: 0.5 },
    shoes: { color: '#6b4a32' }, ...o,
  });
  return makeDesign({
    name: 'villager', skin, headScale: 1.08, build: { shoulder: 0.98 + 0.04 * (i % 3), waist: 1 + 0.06 * (i % 2) },
    face: { shape: i % 5 === 2 ? 'heavy' : 'male', eye: { w: 0.21, h: 0.12, tilt: 0.08, sharp: 0.2, color: ['#5a4630', '#4a3a2c', '#3c4650'][i % 3], iris: 0.58, lash: 0.18 }, brow: { thick: 0.22, gap: 0.22, arch: 0.04 }, mouthW: 0.2 },
    hair: { ...hairC, capStyle: { front: 0.64, temple: 0.4, lift: 0.07 }, locks: i % 3 === 0 ? [knot([0, 0.98, -0.3], 0.24)] : [...fringe([[0.1, 0.3, 0.3, 0.24], [-0.3, -0.6, 0.28, 0.24], [0.6, 0.95, 0.2, 0.22], [-0.8, -1.1, 0.16, 0.22]], { pow: 0.8 })] },
    beard: i % 4 === 1 ? { color: hairC.color, length: 0.25, mustache: true } : null,
    headgear: i % 5 === 3 ? { type: 'straw', color: '#d9b872' } : null,
    top: { type: 'tunic', color: cloth, sleeve: i % 2 ? 'short' : 'long', hem: 0.11, collar: 'v', inner: '#e9dfc8' },
    sash: { color: '#4a3a2c', w: 0.045 },
    bottom: { type: 'pants', color: ['#4a4038', '#3a3f4a', '#5b4a3a'][i % 3] },
    shoes: { color: '#5a4030' }, ...o,
  });
}
CAST.soldier = soldier(0);
CAST.rider = villager(3, false, { name: 'Rider', headgear: { type: 'straw', color: '#c9a862' }, cape: { color: '#5a5048', lining: '#3a342e', len: 0.5, w: 0.9, cols: 7, rows: 7 }, top: { type: 'tunic', color: '#6a5a4a', sleeve: 'long', hem: 0.12, collar: 'v', inner: '#d9cfb8' } });
export { lock, fringe, spikes, sideLocks, longBack, tail, knot, BLACK, BROWN, WHITE, GREY };
