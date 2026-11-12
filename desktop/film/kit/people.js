// The people library: several hundred ready characters, each a full design sheet drawn by the character engine.
// Every one is different (face, hair, build, clothes, colours) and belongs to a group; use one by its id as an
// actor's `design`, or copy and change it with person(id, { …overrides }).
import { makeDesign } from '../engine/character.js';
import { fringe, spikes, sideLocks, longBack, tail, knot } from './design.js';

const rng = (seed) => { let s = (seed * 2654435761) >>> 0 || 1; return () => ((s = (Math.imul(s ^ (s >>> 15), 2246822507) + 0x9e3779b9) >>> 0), ((s ^= s >>> 13) >>> 0) / 4294967296); };
const pick = (r, a) => a[Math.floor(r() * a.length) % a.length];
const shade = (hex, k) => { const n = parseInt(hex.slice(1), 16), f = (v) => Math.max(0, Math.min(255, Math.round(v + (k < 0 ? v * k : (255 - v) * k)))); return '#' + [n >> 16 & 255, n >> 8 & 255, n & 255].map((v) => f(v).toString(16).padStart(2, '0')).join(''); };

const SKIN = ['#f3d2b8', '#efc4a0', '#e6b890', '#dcae86', '#d6a47c', '#c8926a', '#b07a54', '#8f5e3e', '#6e452c'];
const HAIR = [
  { color: '#1b1a22', shade: '#0d0c12', hi: '#474b66', ink: '#08070b' }, { color: '#2a201c', shade: '#17110f', hi: '#5a4a40', ink: '#0c0806' },
  { color: '#4a3226', shade: '#2e1e18', hi: '#7a5a44', ink: '#1c120e' }, { color: '#6b4630', shade: '#452c1e', hi: '#9a7050', ink: '#2a1a10' },
  { color: '#8a5a34', shade: '#5c3a20', hi: '#b98858', ink: '#3a2412' }, { color: '#b08a4a', shade: '#7c5e30', hi: '#dcc080', ink: '#4a3618' },
  { color: '#7a2e22', shade: '#4e1c14', hi: '#a85a44', ink: '#2e100c' }, { color: '#6f6e76', shade: '#4a4a52', hi: '#9c9ca6', ink: '#2a2a30' },
  { color: '#e8e6e2', shade: '#bdbcc2', hi: '#ffffff', ink: '#6b6a74' },
];
const EYES = ['#4a3a2c', '#5a4630', '#6a4a2a', '#3c4650', '#4a6a8a', '#3a8a7a', '#5a7a3a', '#7a5a8a', '#a8742c', '#2e2a28'];
const CLOTH = {
  plain: ['#8a7450', '#5d6f86', '#7a5a48', '#6f7f5a', '#9a6a4a', '#55606e', '#a08a6a', '#6a5a52', '#7d6a8a', '#4f6a66'],
  rich: ['#7c1f27', '#1f4a6e', '#2f6a4a', '#5a2f6e', '#b5822e', '#23304a', '#8a2f4a', '#1f5a5a'],
  monk: ['#c9862e', '#a8641e', '#7a4a2e', '#8a8a86', '#d9d2c2'],
  dark: ['#2a2529', '#3a3f4c', '#2e2a30', '#3a2c2a', '#1f2a33'],
  bright: ['#d94f3a', '#e0a030', '#3f8fb5', '#5aa05a', '#c05a9a', '#e07a3a', '#4f6ad0'],
};
const KINGDOM = { // colours of the four armies
  crimson: { cloth: '#7c1f27', plate: '#7b8594', trim: '#d9b25a' }, azure: { cloth: '#1f4a6e', plate: '#8fa4b4', trim: '#c9a24a' },
  jade: { cloth: '#2f6a4a', plate: '#9a9a8a', trim: '#e6dcc0' }, onyx: { cloth: '#2a2529', plate: '#4b4f5a', trim: '#b5492f' },
};

/* ---- hair: a style is a function (r) → { capStyle, locks, tufts } ------------------------------------------------ */
const HAIR_M = {
  crop: (r) => ({ capStyle: { front: 0.64, temple: 0.4, lift: 0.06 }, locks: fringe([[0.1, 0.3, 0.3, 0.24], [-0.3, -0.6, 0.28, 0.24], [0.6, 0.95, 0.2, 0.22], [-0.8, -1.1, 0.16, 0.22]], { pow: 0.8 + 0.1 * r() }) }),
  swept: () => ({ capStyle: { front: 0.6, temple: 0.38, lift: 0.1 }, locks: fringe([[-0.2, 0.5, 0.22, 0.26, 0.08], [0.3, 0.9, 0.12, 0.24, 0.06], [-0.6, -0.9, 0.2, 0.22], [0.9, 1.25, -0.02, 0.2]], { pow: 0.8 }) }),
  topknot: () => ({ capStyle: { front: 0.66, temple: 0.42, lift: 0.04 }, locks: [knot([0, 0.98, -0.3], 0.24)] }),
  tail: (r) => ({ capStyle: { front: 0.6, temple: 0.36, lift: 0.08, nape: -0.6 }, locks: [...fringe([[-0.08, 0.05, 0.0, 0.19, 0.04], [0.3, 0.44, 0.2, 0.22], [-0.4, -0.54, 0.16, 0.22], [0.62, 0.9, 0.1, 0.22], [-0.68, -0.96, 0.06, 0.22]]), ...sideLocks(-0.6)], tufts: [tail(1.0 + 0.8 * r())] }),
  spiky: () => ({ capStyle: { front: 0.6, temple: 0.38, lift: 0.1 }, locks: [...fringe([[0.1, 0.2, 0.2, 0.2], [-0.35, -0.5, 0.22, 0.2], [0.6, 0.85, 0.14, 0.2]]), ...spikes([[0.4, 1.25, 1.4, 1.0, 0.26], [-0.9, 1.2, -1.9, 0.9, 0.24], [2.6, 0.9, 2.95, 0.45, 0.24], [-2.4, 0.8, -2.9, 0.4, 0.22]])] }),
  long: (r) => ({ capStyle: { front: 0.62, temple: 0.38, lift: 0.08, nape: -0.7 }, locks: [...fringe([[0.1, 0.5, 0.25, 0.22, 0.08], [-0.25, -0.2, 0.3, 0.2], [0.55, 1.0, 0.1, 0.22], [-0.6, -1.0, 0.2, 0.22]]), ...sideLocks(-0.9, 0.14)], tufts: longBack(5, 1.4 + 0.8 * r()) }),
  bald: () => ({ capStyle: { front: 0.6, temple: 0.34, lift: 0.07, bald: 0.34, baldBack: 0.65 }, locks: spikes([[1.9, 0.1, 2.2, -0.45, 0.1, 0.24], [-1.9, 0.1, -2.2, -0.45, 0.1, 0.24]], { pow: 0.6 }) }),
  shaved: () => ({ capStyle: { front: 0.7, temple: 0.46, lift: 0.015 }, locks: [] }),
};
const HAIR_F = {
  bun: () => ({ capStyle: { front: 0.62, temple: 0.4, lift: 0.08, nape: -0.62 }, locks: [...fringe([[0.1, 0.4, 0.2, 0.24, 0.05], [-0.3, -0.7, 0.14, 0.24, -0.05], [0.6, 1.0, 0.0, 0.24], [-0.75, -1.1, -0.05, 0.22]], { pow: 0.7 }), knot([0, 0.5, -0.92], 0.33)] }),
  high: () => ({ capStyle: { front: 0.62, temple: 0.4, lift: 0.08, nape: -0.6 }, locks: [...fringe([[0.0, 0.3, 0.24, 0.22], [-0.4, -0.75, 0.16, 0.22], [0.5, 0.95, 0.06, 0.22]], { pow: 0.7 }), ...sideLocks(-0.75, 0.13), knot([0, 1.0, -0.35], 0.3)] }),
  long: (r) => ({ capStyle: { front: 0.6, temple: 0.38, lift: 0.09, nape: -0.75 }, locks: [...fringe([[0.1, 0.2, 0.16, 0.26, 0.05], [-0.3, -0.45, 0.2, 0.24], [0.55, 0.85, 0.12, 0.24], [-0.7, -1.0, 0.08, 0.24]], { pow: 0.7 }), ...sideLocks(-1.0, 0.16, { pow: 0.5 })], tufts: longBack(6, 1.6 + 1.0 * r()) }),
  pony: (r) => ({ capStyle: { front: 0.6, temple: 0.38, lift: 0.08, nape: -0.6 }, locks: [...fringe([[0.1, 0.3, 0.18, 0.24], [-0.3, -0.6, 0.2, 0.24], [0.6, 0.95, 0.06, 0.22], [-0.8, -1.1, 0.02, 0.22]], { pow: 0.7 }), ...sideLocks(-0.7, 0.13)], tufts: [tail(1.2 + 0.9 * r(), { w: 0.24 })] }),
  bob: () => ({ capStyle: { front: 0.6, temple: 0.38, lift: 0.1, nape: -0.7, sideburn: -0.3 }, locks: [...fringe([[0.1, 0.2, 0.16, 0.26, 0.05], [-0.3, -0.45, 0.2, 0.24], [0.55, 0.85, 0.12, 0.24], [-0.7, -1.0, 0.08, 0.24], [1.0, 1.25, -0.1, 0.22], [-1.05, -1.3, -0.14, 0.22]], { pow: 0.7 }), ...sideLocks(-0.85, 0.2, { pow: 0.5 }), ...spikes([[2.0, 0.1, 2.2, -0.75, 0.1, 0.3], [-2.0, 0.1, -2.2, -0.75, 0.1, 0.3], [2.8, 0.1, 2.9, -0.8, 0.08, 0.3], [-2.8, 0.1, -2.9, -0.8, 0.08, 0.3]], { pow: 0.6 })] }),
  twin: () => ({ capStyle: { front: 0.62, temple: 0.4, lift: 0.08, nape: -0.6 }, locks: [...fringe([[0.05, 0.25, 0.2, 0.24], [-0.35, -0.6, 0.2, 0.24], [0.6, 0.9, 0.1, 0.22], [-0.8, -1.05, 0.08, 0.22]], { pow: 0.7 }), knot([0.72, 0.62, -0.5], 0.26), knot([-0.72, 0.62, -0.5], 0.26)] }),
};

/* ---- one person from a recipe ------------------------------------------------------------------------------------ */
function build(seed, k) {
  const r = rng(seed), woman = !!k.woman, child = !!k.child, old = !!k.old;
  const skin = k.skin ?? pick(r, SKIN), hc = old ? HAIR[7 + Math.floor(r() * 2)] : pick(r, k.hairCols ?? HAIR.slice(0, 7));
  const styles = woman ? HAIR_F : HAIR_M, names = k.hair ?? Object.keys(styles).filter((n) => (woman ? true : n !== 'bald' || old));
  const hs = styles[pick(r, names)](r);
  const eye = woman || child
    ? { w: child ? 0.26 : 0.24, h: child ? 0.24 : 0.14 + 0.06 * r(), tilt: 0.04 + 0.2 * r(), color: pick(r, EYES), iris: child ? 0.68 : 0.6, lash: woman ? 0.26 + 0.1 * r() : 0.16, flick: woman ? 0.1 + 0.14 * r() : 0.05, sharp: 0.2 + 0.4 * r() }
    : { w: 0.2 + 0.03 * r(), h: 0.1 + 0.03 * r(), tilt: 0.04 + 0.16 * r(), sharp: 0.2 + 0.5 * r(), color: pick(r, EYES), iris: 0.55 + 0.05 * r(), lash: 0.18 };
  const shape = child ? 'child' : old ? 'elder' : woman ? 'female' : (k.heavy ?? r() < 0.25) ? 'heavy' : 'male';
  const main = k.cloth ?? pick(r, CLOTH[k.palette ?? 'plain']), second = k.second ?? pick(r, CLOTH[k.palette2 ?? 'dark']);
  const d = {
    name: k.name, skin, headScale: child ? 1.3 : woman ? 1.12 : 1.06 + 0.04 * r(),
    build: { k: 0.98 + 0.12 * r(), shoulder: (woman ? 0.88 : 0.98) + 0.1 * r() + (k.big ? 0.08 : 0), chest: (woman ? 0.95 : 1) + (k.big ? 0.15 : 0.08 * r()), waist: (woman ? 0.88 : 1) + 0.12 * r() + (k.fat ? 0.2 : 0), arm: (woman ? 0.84 : 0.98) + 0.1 * r(), neck: woman ? 0.82 : 1, female: woman },
    face: { shape, eye, brow: { thick: woman || child ? 0.1 + 0.04 * r() : 0.2 + 0.14 * r(), gap: 0.2 + 0.12 * r(), arch: 0.14 * r(), color: old ? '#e8e6e2' : undefined }, mouthW: 0.16 + 0.05 * r(), blush: child ? 0.4 : woman ? 0.2 * r() : 0, lines: old, scar: k.scar && r() < 0.5 ? { az: 0.5 * (r() < 0.5 ? 1 : -1), el: -0.2 + 0.3 * r(), len: 0.25 } : undefined },
    hair: { ...hc, ...hs },
    beard: !woman && !child && (k.beard ?? r() < (old ? 0.7 : 0.3)) ? { color: hc.color, length: old ? 0.4 + 0.5 * r() : 0.05 + 0.3 * r(), mustache: r() < 0.7 } : null,
    headgear: k.headgear ? k.headgear(r) : null,
    top: k.top ? k.top(r, main, second) : { type: 'tunic', color: main, sleeve: r() < 0.6 ? 'long' : 'short', hem: woman ? 0.02 : 0.1, collar: 'v', inner: pick(r, ['#efe6d2', '#e9dfc8', '#d9cfb8', '#f4ecdc']) },
    sash: k.sash === null ? null : { color: k.sash ?? pick(r, ['#b5492f', '#3f5a86', '#7a4a2e', '#4a3a2c', '#c9a24a', '#2a2529']), w: 0.04 + 0.015 * r() },
    bottom: k.bottom ? k.bottom(r, main, second) : woman ? { type: 'robe', color: main, len: 0.44 + 0.1 * r(), flare: 0.4 + 0.2 * r() } : { type: 'pants', color: second },
    shoes: k.shoes === null ? null : { color: pick(r, ['#6b4a32', '#5a4030', '#1e1a1c', '#4a3a2c']), boot: k.boots ? 0.5 : undefined },
    armor: k.armor ? k.armor(r) : null, cape: k.cape ? k.cape(r, main, second) : null,
    spear: k.spear, shield: k.shield, sword: k.sword, staff: k.staff,
  };
  for (const key of Object.keys(d)) if (d[key] === undefined || d[key] === null) delete d[key];
  if (d.face.scar === undefined) delete d.face.scar;
  if (d.face.brow.color === undefined) delete d.face.brow.color;
  return makeDesign(d);
}

/* ---- the groups -------------------------------------------------------------------------------------------------- */
const cape = (len) => (r, main, second) => ({ color: second, lining: shade(main, -0.2), len: len + 0.1 * r(), w: 0.9, cols: 7, rows: 7 });
const robeTop = (r, main) => ({ type: 'tunic', color: main, shade: shade(main, -0.3), sleeve: 'wide', sleeveDrop: 0.12 + 0.06 * r(), hem: 0.02, collar: 'v', inner: '#efe6d2', trim: '#d9b25a', trimW: 0.02 });
const soldierKit = (K) => ({ palette: 'dark', cloth: K.cloth, sash: '#2a2529', boots: true, heavy: undefined,
  headgear: () => ({ type: 'helmet', color: K.plate, band: shade(K.plate, -0.3) }), top: () => ({ type: 'tunic', color: K.cloth, shade: shade(K.cloth, -0.35), sleeve: 'long', hem: 0.12, collar: 'round' }),
  bottom: () => ({ type: 'pants', color: '#2e2a30', shade: '#1c191e' }), armor: () => ({ plate: K.plate, pauldron: shade(K.plate, 0.12), bracer: shade(K.plate, -0.12), greave: shade(K.plate, -0.12) }),
  spear: { len: 2.4, shaft: '#6b5340', head: '#cfd6de' }, shield: { r: 0.4, color: K.cloth, rim: K.plate, boss: '#c9ced6', back: '#6a5240' }, sword: { blade: '#d6dbe2', guard: '#8a7440', grip: '#2a2020', len: 0.8 } });
const GROUPS = [
  { id: 'townsman', title: 'Townsmen', n: 44, preset: 'villager', kit: {} },
  { id: 'townswoman', title: 'Townswomen', n: 44, preset: 'villager_woman', kit: { woman: true } },
  { id: 'farmer', title: 'Farmers', n: 16, preset: 'villager', kit: { palette: 'plain', headgear: (r) => (r() < 0.7 ? { type: 'straw', color: pick(r, ['#d9b872', '#c9a862', '#e0c890']) } : null), top: (r, m) => ({ type: 'tunic', color: m, sleeve: 'short', hem: 0.1, collar: 'v', inner: '#e9dfc8' }) } },
  { id: 'farmwoman', title: 'Farm women', n: 12, preset: 'villager_woman', kit: { woman: true, hair: ['bun', 'pony', 'twin'], headgear: (r) => (r() < 0.4 ? { type: 'straw', color: '#d9b872' } : null) } },
  { id: 'merchant', title: 'Merchants', n: 18, preset: 'merchant', kit: { palette: 'rich', fat: true, top: robeTop, bottom: (r, m) => ({ type: 'robe', color: shade(m, -0.15), len: 0.5, flare: 0.3 }), sash: '#c9a24a' } },
  { id: 'nobleman', title: 'Noblemen', n: 14, preset: 'master', kit: { palette: 'rich', hair: ['long', 'tail', 'topknot', 'swept'], top: robeTop, bottom: (r, m) => ({ type: 'robe', color: m, len: 0.52, flare: 0.35 }), cape: cape(0.55), sash: '#d9b25a' } },
  { id: 'noblewoman', title: 'Noblewomen', n: 14, preset: 'lady', kit: { woman: true, palette: 'rich', hair: ['high', 'long', 'bun'], top: robeTop, bottom: (r, m) => ({ type: 'robe', color: m, len: 0.56, flare: 0.55 }), sash: '#d9b25a' } },
  { id: 'monk', title: 'Monks and priests', n: 14, preset: 'master', kit: { palette: 'monk', hair: ['shaved', 'bald', 'topknot'], top: robeTop, bottom: (r, m) => ({ type: 'robe', color: m, len: 0.55, flare: 0.3 }), sash: '#7a4a2e', staff: { len: 1.7, color: '#7a6248' } } },
  { id: 'elder', title: 'Elders', n: 12, preset: 'elder', kit: { old: true, top: robeTop, bottom: (r, m) => ({ type: 'robe', color: shade(m, -0.1), len: 0.5, flare: 0.3 }) } },
  { id: 'elderwoman', title: 'Old women', n: 10, preset: 'villager_woman', kit: { woman: true, old: true, hair: ['bun', 'high'] } },
  { id: 'boy', title: 'Boys', n: 16, preset: 'boy', kit: { child: true, hair: ['crop', 'spiky', 'swept', 'tail'], top: (r, m) => ({ type: 'tunic', color: m, sleeve: 'short', hem: 0.08, collar: 'v', inner: '#efe6d2' }), shoes: undefined } },
  { id: 'girl', title: 'Girls', n: 16, preset: 'girl', kit: { child: true, woman: true, hair: ['bob', 'twin', 'pony', 'bun'], palette: 'bright', top: (r, m) => ({ type: 'tunic', color: m, shade: shade(m, -0.25), sleeve: 'short', hem: 0.02, collar: 'v', inner: '#f4ecdc' }), bottom: (r, m) => ({ type: 'skirt', color: m, lining: shade(m, -0.25), len: 0.26, flare: 0.6 }) } },
  { id: 'teen', title: 'Young men', n: 12, preset: 'teen', kit: { hair: ['spiky', 'swept', 'tail', 'crop'], beard: false } },
  { id: 'youngwoman', title: 'Young women', n: 12, preset: 'woman', kit: { woman: true, palette: 'bright', hair: ['long', 'pony', 'bob', 'high'] } },
  ...Object.entries(KINGDOM).map(([name, K]) => ({ id: `soldier_${name}`, title: `Soldiers (${name})`, n: 10, preset: 'soldier', kit: soldierKit(K) })),
  ...Object.entries(KINGDOM).map(([name, K]) => ({ id: `captain_${name}`, title: `Captains (${name})`, n: 4, preset: 'captain', kit: { ...soldierKit(K), big: true, scar: true, spear: undefined, shield: undefined, headgear: (r) => (r() < 0.5 ? { type: 'helmet', color: K.plate, band: K.trim, crest: K.cloth } : null), cape: cape(0.6) } })),
  { id: 'bandit', title: 'Bandits and rogues', n: 16, preset: 'warlord', kit: { palette: 'dark', scar: true, hair: ['spiky', 'shaved', 'tail', 'long'], beard: true, boots: true, sword: { blade: '#c9ced6', guard: '#5a4a30', grip: '#2a2020', len: 0.85 } } },
  { id: 'sailor', title: 'Sailors', n: 12, preset: 'villager', kit: { cloth: '#2f4a66', palette2: 'plain', hair: ['crop', 'shaved', 'tail'], top: (r) => ({ type: 'tunic', color: pick(r, ['#2f4a66', '#e6dcc8', '#5a6f86']), sleeve: 'short', hem: 0.1, collar: 'v', inner: '#e6dcc8' }), sash: '#b5492f' } },
  { id: 'swordsman', title: 'Swordsmen', n: 16, preset: 'swordsman', kit: { palette: 'dark', hair: ['tail', 'spiky', 'long', 'swept'], beard: false, top: (r, m) => ({ type: 'tunic', color: m, shade: shade(m, -0.35), sleeve: 'long', hem: 0.12, collar: 'v', inner: '#8a2a2e', trim: pick(r, ['#b5292f', '#c9a24a', '#3f8fb5', '#5aa05a']), trimW: 0.022 }), sash: undefined, boots: true, cape: (r, m, s) => (r() < 0.5 ? cape(0.5)(r, m, s) : null), sword: { blade: '#dfe5ec', guard: '#8a7440', grip: '#2a1a1a', len: 0.92 } } },
  { id: 'swordswoman', title: 'Swordswomen', n: 10, preset: 'woman', kit: { woman: true, palette: 'dark', hair: ['pony', 'long', 'high'], top: (r, m) => ({ type: 'tunic', color: m, shade: shade(m, -0.35), sleeve: 'long', hem: 0.1, collar: 'v', inner: '#8a2a2e', trim: pick(r, ['#b5292f', '#c9a24a', '#3f8fb5']), trimW: 0.02 }), bottom: (r, m, s) => ({ type: 'pants', color: s }), boots: true, sword: { blade: '#dfe5ec', guard: '#8a7440', grip: '#2a1a1a', len: 0.88 } } },
  { id: 'king', title: 'Kings and lords', n: 8, preset: 'king', kit: { palette: 'rich', big: true, hair: ['long', 'swept', 'tail'], beard: true, headgear: (r) => (r() < 0.6 ? { type: 'crown', color: '#e0b84a', gem: pick(r, ['#c0392b', '#2f6fb5', '#2f8a5a']), y: 0.66 } : null), top: robeTop, bottom: (r, m) => ({ type: 'robe', color: m, len: 0.55, flare: 0.3 }), cape: cape(0.7), sash: '#d9b25a' } },
  { id: 'queen', title: 'Queens and ladies', n: 8, preset: 'lady', kit: { woman: true, palette: 'rich', hair: ['high', 'long'], headgear: (r) => (r() < 0.5 ? { type: 'crown', color: '#e0b84a', gem: pick(r, ['#c0392b', '#2f6fb5', '#2f8a5a']), y: 0.66 } : null), top: robeTop, bottom: (r, m) => ({ type: 'robe', color: m, len: 0.58, flare: 0.6 }), sash: '#d9b25a' } },
];

/** PEOPLE[id] = design; LIST = [{ id, group, title, preset, name }] in library order. */
export const PEOPLE = {}, PEOPLE_LIST = [], PEOPLE_GROUPS = GROUPS.map((g) => ({ id: g.id, title: g.title, count: g.n, preset: g.preset }));
let serial = 0;
for (const g of GROUPS) for (let i = 0; i < g.n; i++) {
  const id = `${g.id}_${String(i + 1).padStart(2, '0')}`, name = `${g.title.replace(/s( \(|$)/, '$1').replace(/men\b/, 'man').replace(/women\b/, 'woman')} ${i + 1}`;
  PEOPLE[id] = build(1000 + serial * 37 + i, { ...g.kit, name });
  PEOPLE_LIST.push({ id, group: g.id, title: g.title, preset: g.preset, name });
  serial++;
}
/** A copy of a library person with changes: person('townsman_03', { top: { …}, cape: { … } }). */
export function person(id, changes = {}) {
  const d = PEOPLE[id]; if (!d) throw new Error(`No person ${id} in the library.`);
  const out = JSON.parse(JSON.stringify(d));
  for (const [k, v] of Object.entries(changes)) out[k] = v && typeof v === 'object' && !Array.isArray(v) && out[k] && typeof out[k] === 'object' ? { ...out[k], ...v } : v;
  return makeDesign(out);
}
/** The stage preset (skeleton) a library person is made for. */
export const presetOf = (id) => PEOPLE_LIST.find((p) => p.id === id)?.preset ?? 'villager';
