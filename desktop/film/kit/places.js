// Places for any story: open country, woods, sand, snow, sea, a modern street, a park, a room, a rooftop, a farm, a
// lake, a mountain pass, a plain studio backdrop and the night sky. Each takes { time: 'dawn' | 'day' | 'dusk' |
// 'night' } and returns a Scene; the middle of the place (where the story plays) is around x = 0, z = 0, y = 0 is the
// ground, and the camera usually looks toward −z.  Add things with put(sc, 'bench', [x, z]) from props.js.
import { Scene, sky, range, ground, slab, Mesh, box, DET, tree, rock, dirOf, clipSeg } from '../engine/scene.js';
import { hash, lerp, shade } from '../engine/core.js';
import { makeWind } from '../engine/sim.js';
import { put, fence, building, hut, barn, lampPost, bench, haystack } from './props.js';

export const HOURS = {
  dawn: { stops: [[0, '#27406e'], [0.45, '#6d7fb0'], [0.78, '#e8a99a'], [1, '#ffd9a0']], sun: { az: 95, el: 5, color: '#fff0cf', glow: '#ff9d6a', glowR: 26 }, haze: '#d9bfc0', amb: '#f1e2e6', tint: '#ffb48a', tintAmt: 0.09, gain: 0.97, fog: 420, cloud: ['#f8c9b0', '#8f7fa8'], stars: 0.15, win: '#ffd08a', sunDir: [0.85, 0.3, 0.2] },
  day: { stops: [[0, '#2c6dc4'], [0.45, '#6fb0ec'], [0.82, '#c7e3f6'], [1, '#eef5e6']], sun: { az: 140, el: 48, color: '#fffbe8', glow: '#fff3c4', glowR: 20 }, haze: '#cfe2ee', amb: '#ffffff', tint: '#fff4d6', tintAmt: 0.05, gain: 1.04, fog: 520, cloud: ['#ffffff', '#c6d6ec'], stars: 0, win: null, sunDir: [0.5, 0.75, 0.45] },
  dusk: { stops: [[0, '#3b4f8f'], [0.4, '#8b7fb8'], [0.75, '#f0a590'], [1, '#ffd69a']], sun: { az: -100, el: 4, color: '#fff0c8', glow: '#ff9a5a', glowR: 28 }, haze: '#e0b8a8', amb: '#ffe2cc', tint: '#ff9f6a', tintAmt: 0.14, gain: 0.95, fog: 420, cloud: ['#ffd0b0', '#9a7fa6'], stars: 0.2, win: '#ffd08a', sunDir: [-0.8, 0.3, 0.3] },
  night: { stops: [[0, '#070b1f'], [0.5, '#101a3c'], [0.85, '#1f2f5a'], [1, '#2f4470']], moon: { az: 150, el: 34, r: 2.2, color: '#f3f1e2', glow: '#8aa2e0', glowR: 16, crescent: null }, haze: '#22305a', amb: '#7c8fcc', tint: '#3a4fa0', tintAmt: 0.12, gain: 0.8, fog: 300, cloud: ['#3a4a7a', '#1c2648'], stars: 1, win: '#ffc878', sunDir: [0.4, 0.7, 0.5] },
};
/** The start of every place: the hour's light, air and sky. d: { fog (× the hour's), haze, clouds (false: none), wind, seed } */
function open(o = {}, d = {}) {
  const time = HOURS[o.time] ? o.time : 'day', L = HOURS[time], night = time === 'night', low = time === 'dusk' || time === 'dawn';
  const wind = makeWind({ dir: [0.9, 0, 0.3], speed: o.wind ?? d.wind ?? 1.0, gust: 0.7, seed: d.seed ?? 7 });
  const haze = d.haze ?? L.haze;
  const sc = new Scene({
    grade: { amb: L.amb, tint: L.tint, tintAmt: L.tintAmt, gain: L.gain }, fog: { color: haze, dist: L.fog * (d.fog ?? 1), max: 0.9, start: 10 }, sun: L.sunDir, wind,
    rim: low ? '#ffc890' : null, dust: null, shadowAlpha: night ? 0.22 : 0.3, shadowColor: night ? '#05060f' : (d.shadow ?? '#1c2433'),
    post: { vignette: night ? 0.5 : 0.28, grain: 0.04, wash: night ? [{ color: '#1a2a6a', to: '#0a1030', a: 0.1, op: 'source-over' }] : time === 'dusk' ? [{ color: '#ff9a5a', to: '#3a3a7a', a: 0.07, op: 'source-over' }] : [] },
  });
  sc.look = L; sc.time = time; sc.sunAt = dirOf(L.sun?.az ?? L.moon.az, L.sun?.el ?? L.moon.el);
  const clouds = [];
  if (d.clouds !== false) { const n = night ? 6 : (d.clouds ?? 12); for (let i = 0; i < n; i++) clouds.push({ kind: i % 3 === 0 || night ? 'band' : 'puff', az: -180 + i * (360 / n) + hash(i * 3.3) * 16, el: (night ? 14 : 12) + hash(i * 5.1) * 16, w: 20 + hash(i * 2.2) * 26, h: (i % 3 === 0 || night ? 2.4 : 6) + hash(i * 6.1) * 3.5, seed: i + 11, color: L.cloud[0], shade: L.cloud[1], alpha: night ? 0.55 : 0.96, drift: 0.004 }); }
  sc.add('sky', sky({ stops: L.stops, sun: L.sun, moon: L.moon, stars: L.stars ? { n: 520, amt: L.stars, seed: 5 } : null, clouds, band: { color: L.stops[L.stops.length - 1][1], el: 6, alpha: 0.6 } }));
  return { sc, L, time, night, low };
}
/** Hills all round: far blue ones, nearer ones in the colour given. */
function hills(sc, L, night, o = {}) {
  const near = o.near ?? (night ? '#22324a' : '#6a9a52'), mid = o.mid ?? (night ? '#2a3a5a' : '#5d8a5a'), far = o.far ?? (night ? '#3a4a78' : '#6f8fb0'), k = o.k ?? 1;
  for (const yaw of [0, 90, 180, -90]) {
    if (o.peaks !== false) sc.add('far', range({ z: 2400, yaw: yaw + 10, h: 560 * k, base: -20, drop: 40, span: 8200, color: far, shade: shade(far, -0.2), seed: 61 + yaw + (o.seed ?? 0), peaks: 5, snow: o.snow === false ? null : { line: o.snowLine ?? 0.55, color: night ? '#9aa8d0' : '#f4f6fb', shade: night ? '#6a78a8' : '#b9c8e4' }, mist: { color: L.haze, h: 0.5, alpha: 0.7 } }));
    sc.add('far', range({ z: 900, yaw: yaw - 14, h: 210 * k, base: -10, drop: 30, span: 3200, color: mid, shade: shade(mid, -0.22), seed: 73 + yaw + (o.seed ?? 0), peaks: 6, rough: o.rough ?? 0.3, mist: { color: L.haze, h: 0.6, alpha: 0.6 } }));
    sc.add('far', range({ z: 330, yaw: yaw + 6, h: 60 * k, base: -4, drop: 12, span: 1300, color: near, shade: shade(near, -0.2), seed: 87 + yaw + (o.seed ?? 0), peaks: 7, rough: 0.2 }));
  }
}
const blob = (cx, cz, rx, rz, seed, n = 14) => Array.from({ length: n }, (_, i) => { const a = (i / n) * Math.PI * 2, k = 0.82 + 0.3 * hash(i * 2.1 + seed); return [cx + Math.cos(a) * rx * k, cz + Math.sin(a) * rz * k]; });
const leaves = (night, low) => (night ? ['#35603f', '#4a7a52', '#234430'] : low ? ['#5f8a3c', '#86a84c', '#3f6430'] : ['#4f9440', '#79b856', '#35702e']);
const grass = (night, o = {}) => ground({ y: 0, near: o.near ?? '#74a84e', far: o.far ?? '#a3c07c', farAt: 260, patch: { colors: ['#6a9e48', '#84b25a', '#7aa650', '#8fb866', '#6f9a4c'], cell: 3.4, max: 90, amt: 0.62, size: [0.8, 2.8], alpha: 0.6 }, tuft: { colors: ['#5f9440', '#79aa52', '#6b9d47', '#8ab85e'], cell: 0.7, max: 17, amt: 0.6, h: [0.1, 0.3], flower: night || o.flowers === false ? null : { amt: o.flowers ?? 0.09, colors: ['#ffffff', '#ffe27a', '#ff9db0', '#c9a0ff'] } } });
/** Scatter trees in a ring around the middle, keeping `clear` metres free (and anywhere `skip(x, z)` says). */
function woods(sc, n, o = {}) {
  const lc = o.leaf, r0 = o.clear ?? 14, r1 = o.out ?? 120;
  for (let i = 0; i < n; i++) {
    const a = hash(i * 1.37 + (o.seed ?? 0)) * Math.PI * 2, r = r0 + Math.pow(hash(i * 2.9 + (o.seed ?? 0)), o.pow ?? 1) * (r1 - r0), x = Math.cos(a) * r, z = Math.sin(a) * r * (o.squash ?? 0.85);
    if (o.skip && o.skip(x, z)) continue;
    const pine = hash(i * 7.1) < (o.pines ?? 0.25);
    sc.add('world', tree({ p: [x, 0, z], h: pine ? 8 + hash(i) * 6 : 5 + hash(i * 3.1) * 5, kind: o.bare ? 'bare' : pine ? 'pine' : 'leaf', seed: i + 20, leaf: pine ? (o.pineLeaf ?? '#2f6a4a') : lc[0], leafLit: pine ? (o.pineLit ?? '#4a8a5c') : lc[1], leafShade: pine ? '#1f4a36' : lc[2], trunk: '#5a4332' }));
  }
}

/** A plain backdrop with no horizon: for talking heads, explainers, product shots, any graphic style. o: { color, floor } */
export function studio(o = {}) {
  const c = o.color ?? '#2b3242', fl = o.floor ?? shade(c, -0.12);
  const sc = new Scene({ grade: { amb: '#ffffff', tint: '#ffffff', tintAmt: 0, gain: 1 }, fog: { color: c, dist: 60, max: 1, start: 6 }, sun: [0.35, 0.8, 0.5], wind: makeWind({ dir: [1, 0, 0], speed: 0.2, gust: 0.1, seed: 3 }), rim: null, dust: null, shadowAlpha: 0.28, shadowColor: shade(c, -0.6), post: { vignette: o.vignette ?? 0.3, grain: 0.03 } });
  sc.time = 'day'; sc.sunAt = dirOf(120, 50);
  sc.add('sky', sky({ stops: [[0, shade(c, 0.1)], [0.6, c], [1, c]], clouds: [] }));
  sc.add('ground', ground({ y: 0, near: fl, far: c, farAt: 30 }));
  return sc;
}
/** Open grassland under a big sky, hills all round, a few trees. o: { time, trees = 26, flowers } */
export function meadow(o = {}) {
  const { sc, L, night, low } = open(o);
  hills(sc, L, night);
  sc.add('ground', grass(night, o));
  woods(sc, o.trees ?? 26, { leaf: leaves(night, low), clear: 16, pines: 0.15 });
  for (let i = 0; i < 9; i++) { const a = hash(i * 4.1) * 6.28, r = 7 + hash(i * 2.3) * 16; sc.add('world', rock({ p: [Math.cos(a) * r, 0, Math.sin(a) * r - 6], r: 0.3 + hash(i) * 0.5, seed: i })); }
  return sc;
}
/** Deep woods with a clearing in the middle and a path through it along x. o: { time, density = 1, clearing = 13 } */
export function forest(o = {}) {
  const { sc, L, night, low } = open(o, { fog: 0.35, haze: o.time === 'night' ? '#16243a' : undefined });
  hills(sc, L, night, { peaks: false });
  sc.add('ground', grass(night, { near: '#5f8a44', far: '#6f9a5a', flowers: 0.05 }));
  sc.add('ground', slab({ pts: [[-60, -1.3], [-20, -1.8], [0, -1.2], [25, -2], [60, -1.4], [60, 1.2], [25, 0.8], [0, 1.5], [-20, 0.9], [-60, 1.4]], y: 0.006, color: '#9a8460', far: '#a8946e' }));
  const cl = o.clearing ?? 13;
  woods(sc, Math.round(170 * (o.density ?? 1)), { leaf: leaves(night, low), clear: cl, out: 95, pines: 0.4, pow: 1.4, skip: (x, z) => Math.abs(z) < 2.6 });
  for (let i = 0; i < 22; i++) { const a = hash(i * 9.3) * 6.28, r = cl + hash(i * 4.1) * 14; const x = Math.cos(a) * r, z = Math.sin(a) * r; if (Math.abs(z) < 2.4) continue; sc.add('world', tree({ p: [x, 0, z], h: 0.9 + hash(i) * 1.0, kind: 'bush', seed: i + 60, leaf: leaves(night, low)[0], leafLit: leaves(night, low)[1], leafShade: leaves(night, low)[2], trunk: '#5a4332' })); }
  return sc;
}
/** Sand to the horizon, dunes, bare rock. o: { time } */
export function desert(o = {}) {
  const { sc, L, night } = open(o, { haze: o.time === 'day' || !o.time ? '#ecdcb8' : undefined, clouds: 4 });
  hills(sc, L, night, { near: night ? '#4a4660' : '#d9b878', mid: night ? '#3f3d5a' : '#c9a468', far: night ? '#3a4a78' : '#b89a8a', snow: false, rough: 0.12, k: 0.7 });
  sc.add('ground', ground({ y: 0, near: '#dcc088', far: '#e8d4a4', farAt: 300, patch: { colors: ['#d2b47c', '#e2c894', '#c9aa70'], cell: 5, max: 110, amt: 0.5, size: [1.5, 5], alpha: 0.5 } }));
  for (let i = 0; i < 26; i++) { const a = hash(i * 4.1) * 6.28, r = 8 + hash(i * 2.3) * 70; sc.add('world', rock({ p: [Math.cos(a) * r, 0, Math.sin(a) * r * 0.8 - 8], r: 0.4 + hash(i) * (i % 5 === 0 ? 3.2 : 0.9), seed: i, color: '#b08a5c' })); }
  for (let i = 0; i < 7; i++) { const a = hash(i * 6.7) * 6.28, r = 14 + hash(i * 3.1) * 40; sc.add('world', tree({ p: [Math.cos(a) * r, 0, Math.sin(a) * r - 6], h: 2 + hash(i) * 2.5, kind: 'bare', seed: i + 3, trunk: '#6a5a44' })); }
  return sc;
}
/** Snow country: white ground, dark pines, great white peaks. o: { time } */
export function snowfield(o = {}) {
  const { sc, L, night } = open(o, { haze: o.time === 'night' ? '#2a3a66' : '#e4ecf6' });
  hills(sc, L, night, { near: night ? '#8a98c0' : '#e6edf6', mid: night ? '#6a78a8' : '#cfdcec', far: night ? '#5a6898' : '#b8c8e0', snowLine: 0.2, k: 1.25 });
  sc.add('ground', ground({ y: 0, near: night ? '#b8c4e0' : '#f1f5fb', far: night ? '#9aa8d0' : '#dfe8f4', farAt: 260, patch: { colors: ['#e4ecf6', '#ffffff', '#d6e0ee'], cell: 4, max: 90, amt: 0.5, size: [1.2, 4], alpha: 0.6 } }));
  woods(sc, o.trees ?? 60, { leaf: ['#3a6a52', '#5a8a6a', '#244a38'], clear: 12, pines: 1, pineLeaf: '#2a5a48', pineLit: '#dfe8f0' });
  for (let i = 0; i < 12; i++) { const a = hash(i * 4.1) * 6.28, r = 6 + hash(i * 2.3) * 30; sc.add('world', rock({ p: [Math.cos(a) * r, 0, Math.sin(a) * r - 5], r: 0.4 + hash(i) * 1.0, seed: i, snow: '#f4f6fb' })); }
  return sc;
}
/** A shore: sand where the story plays, the sea beyond z = −13 to the horizon, land at the sides and behind. o: { time } */
export function beach(o = {}) {
  const { sc, L, night, low } = open(o);
  for (const yaw of [0, 70, -70]) sc.add('far', range({ z: 500, yaw, h: 70, base: -4, drop: 12, span: 1500, color: night ? '#22324a' : '#6a9a52', shade: night ? '#182438' : '#4f7f44', seed: 87 + yaw, peaks: 6, rough: 0.2 }));
  sc.add('ground', ground({ y: 0, near: '#e4cf9c', far: '#ecdcb0', farAt: 200, patch: { colors: ['#dcc48c', '#ead8a8', '#d4bc84'], cell: 4, max: 80, amt: 0.5, size: [1, 4], alpha: 0.5 } }));
  const sea = night ? '#16264a' : low ? '#5a6f9a' : '#2f7fb0';
  sc.add('ground', slab({ pts: [[-900, -2000], [900, -2000], [900, -13], [-900, -13]], y: 0.004, color: sea, far: night ? '#22345e' : low ? '#e0a890' : '#9cc4dc',
    detail: (g, cam, s2) => { g.fillStyle = night ? '#9fb4e8' : '#ffffff'; for (let i = 0; i < 110; i++) { const x = cam.pos[0] - 80 + ((i * 37.7 + s2.t * 2.4 * (0.6 + hash(i))) % 160), z = -14 - hash(i * 1.7) * hash(i * 0.9) * 140, q = cam.project([x, 0.01, z]); if (q[2] < 1) continue; const w = (0.6 + hash(i * 3.3) * 2.2) * cam.f / q[2]; g.globalAlpha = 0.4 * (0.5 + 0.5 * Math.sin(s2.t * 1.3 + i)); g.fillRect(q[0] - w / 2, q[1], w, Math.max(1, w * 0.06)); } g.globalAlpha = 1; } }));
  sc.add('ground', slab({ pts: [[-900, -13.4], [900, -13.4], [900, -11.6], [-900, -11.6]], y: 0.005, color: '#d8e8ee', far: '#e8f2f4', alpha: 0.75 }));
  woods(sc, 34, { leaf: leaves(night, low), clear: 22, out: 90, pines: 0, skip: (x, z) => z < 6 && Math.abs(x) < 30 || z < -9 });
  for (let i = 0; i < 10; i++) { const x = -40 + hash(i * 3.3) * 80, z = -4 - hash(i * 5.1) * 8; sc.add('world', rock({ p: [x, 0, z], r: 0.3 + hash(i) * (i % 4 === 0 ? 1.8 : 0.6), seed: i, color: '#8a8478' })); }
  return sc;
}
/** A high pass: stony ground, boulders, pines, great peaks close on every side. o: { time } */
export function mountain(o = {}) {
  const { sc, L, night } = open(o, { fog: 0.8 });
  hills(sc, L, night, { near: night ? '#3a4258' : '#7f8a80', mid: night ? '#3a4460' : '#7a8894', far: night ? '#4a5a88' : '#8fa4c0', k: 1.9, snowLine: 0.42, rough: 0.5 });
  sc.add('ground', ground({ y: 0, near: '#8f968a', far: '#a8b0a4', farAt: 220, patch: { colors: ['#7f8a78', '#9aa294', '#868f80', '#6f8a5c'], cell: 3, max: 90, amt: 0.6, size: [0.8, 3], alpha: 0.6 } }));
  for (let i = 0; i < 40; i++) { const a = hash(i * 4.1) * 6.28, r = 5 + hash(i * 2.3) * 60; sc.add('world', rock({ p: [Math.cos(a) * r, 0, Math.sin(a) * r * 0.8 - 6], r: 0.4 + hash(i) * (i % 4 === 0 ? 3.5 : 1.0), seed: i, color: '#7d828a', snow: i % 3 === 0 ? '#f4f6fb' : undefined })); }
  woods(sc, 40, { leaf: ['#3a6a52', '#5a8a6a', '#244a38'], clear: 18, pines: 1 });
  return sc;
}
/** A modern town street along x: road (z −4…4), pavements, buildings both sides, lamps, a crossing at x = 0. o: { time, color } */
export function street(o = {}) {
  const { sc, L, night, low } = open(o, { fog: 0.6, clouds: 8 }), lit = night || low ? L.win : null;
  sc.add('ground', ground({ y: 0, near: '#6f7278', far: '#9a9ca2', farAt: 200 }));
  sc.add('ground', slab({ pts: [[-300, -4], [300, -4], [300, 4], [-300, 4]], y: 0.004, color: '#3f4248', far: '#5a5d64',
    detail: (g, cam) => { g.strokeStyle = '#d8d4c4'; g.lineWidth = Math.max(1, cam.H * 0.003); g.beginPath(); for (let x = -120; x < 120; x += 6) { if (Math.abs(x + 1.5) < 4) continue; const q = clipSeg(cam, [x, 0.006, 0], [x + 3, 0.006, 0]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } g.stroke(); } }));
  for (let i = 0; i < 7; i++) sc.add('ground', slab({ pts: [[-2.4, -3.6 + i * 1.1], [2.4, -3.6 + i * 1.1], [2.4, -3.0 + i * 1.1], [-2.4, -3.0 + i * 1.1]], y: 0.007, color: '#e2ded0', far: '#e2ded0' }));
  for (const s of [-1, 1]) sc.add('world', new Mesh(box([0, 0, s * 5.6], 600, 0.16, 3.2, 0, { side: '#8a8c90', top: '#b4b2aa' }, { top: DET.tiles(1.2, 1.2) }), { p: [0, 0, s * 5.6], bias: 4000 }));
  const cols = o.colors ?? ['#b9b2a6', '#a8604a', '#8f9aa8', '#d2c8b0', '#7f8a94', '#c4a88a', '#9a8f86'];
  for (const s of [-1, 1]) { let x = -110; for (let i = 0; x < 110; i++) { const w = 9 + hash(i * 3.1 + s) * 9, h = 9 + hash(i * 5.7 + s * 2) * 22, d = 10 + hash(i * 2.3) * 5; sc.add('world', building([x + w / 2, s * (7.3 + d / 2)], { w, d, h, color: cols[Math.floor(hash(i * 7.7 + s * 3) * cols.length)], lit, seed: i + s * 50 })); x += w + (hash(i * 9.1 + s) > 0.8 ? 4 : 0.4); } }
  for (let x = -96; x <= 96; x += 16) for (const s of [-1, 1]) sc.add('world', lampPost([x + (s > 0 ? 8 : 0), s * 4.5], { lit: lit ? '#ffe2a0' : null }));
  for (let i = 0; i < 10; i++) sc.add('world', tree({ p: [-90 + i * 20 + 5, 0, (i % 2 ? 1 : -1) * 6.4], h: 5 + hash(i) * 2, kind: 'leaf', seed: i + 9, leaf: leaves(night, low)[0], leafLit: leaves(night, low)[1], leafShade: leaves(night, low)[2], trunk: '#5a4332' }));
  return sc;
}
/** A town park: lawn, a winding path along x (z −5.5…−1.5) with benches and lamps behind it, trees, the town's roofs beyond. o: { time } */
export function park(o = {}) {
  const { sc, L, night, low } = open(o), lit = night || low ? L.win : null;
  sc.add('ground', grass(night, { flowers: 0.12 }));
  sc.add('ground', slab({ pts: [[-60, -3.7], [-25, -5.4], [-8, -4.4], [8, -5.2], [26, -3.8], [60, -5.6], [60, -3.4], [26, -1.6], [8, -3.0], [-8, -2.2], [-25, -3.2], [-60, -1.5]], y: 0.006, color: '#c9b894', far: '#d4c6a4' }));
  for (let i = 0; i < 26; i++) { const a = (i / 26) * 6.28, r = 120 + hash(i * 3.3) * 30; sc.add('world', building([Math.cos(a) * r, Math.sin(a) * r], { w: 14 + hash(i) * 12, d: 12, h: 12 + hash(i * 2.2) * 30, color: ['#b9b2a6', '#8f9aa8', '#d2c8b0', '#a8604a'][i % 4], lit, seed: i, yaw: (-a * 180) / Math.PI + 90 })); }
  woods(sc, 46, { leaf: leaves(night, low), clear: 13, out: 85, pines: 0.1, skip: (x, z) => z < -0.5 && z > -7.5 });
  for (const x of [-12, -4, 6, 14]) { sc.add('world', bench([x, -6.4], { yaw: 0 })); sc.add('world', lampPost([x + 2.2, -6.5], { lit: lit ? '#ffe2a0' : null })); }
  return sc;
}
/** A room seen from its open side (the camera stands at +z looking in). o: { time, w = 8, d = 7, h = 3.2, wall, floor, style: 'home' | 'office' | 'class' } */
export function room(o = {}) {
  const w = o.w ?? 8, d = o.d ?? 7, h = o.h ?? 3.2, wallC = o.wall ?? (o.style === 'office' ? '#d9dde2' : o.style === 'class' ? '#e6dfc8' : '#e4d6c0'), fl = o.floor ?? (o.style === 'office' ? '#8a8f98' : '#b08a5c');
  const time = HOURS[o.time] ? o.time : 'day', L = HOURS[time], night = time === 'night';
  const sc = new Scene({ grade: { amb: night ? '#ffe2c0' : '#ffffff', tint: night ? '#ffb070' : L.tint, tintAmt: night ? 0.12 : 0.04, gain: night ? 0.9 : 1.0 }, fog: { color: wallC, dist: 400, max: 0.3, start: 20 }, sun: [0.3, 0.85, 0.45], wind: makeWind({ dir: [1, 0, 0], speed: 0.05, gust: 0.02, seed: 3 }), rim: null, dust: null, shadowAlpha: 0.22, shadowColor: '#2a2018', post: { vignette: 0.32, grain: 0.035 } });
  sc.look = L; sc.time = time; sc.sunAt = dirOf(180, 30);
  sc.add('sky', sky({ stops: L.stops, sun: L.sun, moon: L.moon, stars: L.stars ? { n: 300, amt: L.stars, seed: 5 } : null, clouds: [] }));
  sc.add('ground', ground({ y: 0, near: shade(fl, -0.1), far: fl, farAt: 30 }));
  sc.add('ground', slab({ pts: [[-w / 2, -d / 2], [w / 2, -d / 2], [w / 2, d / 2 + 6], [-w / 2, d / 2 + 6]], y: 0.004, color: fl, far: shade(fl, 0.08), detail: o.style === 'office' ? null : (g, cam) => { g.strokeStyle = shade(fl, -0.28); g.lineWidth = 1; g.beginPath(); for (let x = -w / 2; x <= w / 2; x += 0.24) { const q = clipSeg(cam, [x, 0.006, -d / 2], [x, 0.006, d / 2 + 6]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } g.stroke(); } }));
  const glass = night ? '#1f2f5a' : time === 'dusk' ? '#f0a590' : time === 'dawn' ? '#e8a99a' : '#bfe0f6', trim = shade(wallC, -0.3);
  const win = [['rect', 0.58, 0.34, 0.86, 0.8, glass, trim], ['line', 0.72, 0.34, 0.72, 0.8, trim, 2], ['line', 0.58, 0.57, 0.86, 0.57, trim, 2], ['rect', 0.12, 0, 0.3, 0.66, o.style === 'office' ? '#8a8f98' : '#6a4a34', trim], ['rect', 0.27, 0.3, 0.285, 0.34, '#d9b25a'], ['line', 0, 0.035, 1, 0.035, trim, 3]];
  const board = o.style === 'class' ? [['rect', 0.3, 0.36, 0.7, 0.78, '#2f4a3c', '#7a5a40'], ['line', 0, 0.035, 1, 0.035, trim, 3]] : null;
  sc.add('world', new Mesh(box([0, 0, -d / 2 - 0.1], w + 0.4, h, 0.2, 0, { side: wallC, top: trim }, { front: DET.marks(board ?? win) }), { p: [0, 0, -d / 2 - 0.1], bias: 60 }));
  for (const s of [-1, 1]) sc.add('world', new Mesh(box([s * (w / 2 + 0.1), 0, 3], 0.2, h, d + 6.2, 0, { side: shade(wallC, s > 0 ? -0.06 : -0.02), top: trim }, { [s > 0 ? 'left' : 'right']: DET.marks(board && s < 0 ? win.map((m) => (m[0] === 'rect' && m[1] === 0.12 ? ['line', 0, 0.035, 1, 0.035, trim, 3] : m)) : [['line', 0, 0.035, 1, 0.035, trim, 3]]) }), { p: [s * (w / 2 + 0.1), 0, 3], bias: 50 }));
  sc.add('world', new Mesh(box([0, h, 3], w + 0.4, 0.2, d + 6.2, 0, { side: trim, top: null, bottom: shade(wallC, 0.06) }), { p: [0, h, 3], bias: 80 }));
  if (o.furnish !== false) {
    if (o.style === 'office') { put(sc, 'desk', [-1.2, -1.6]); put(sc, 'chair', [-1.2, -2.3], { color: '#3a3e48' }); put(sc, 'shelf', [2.9, -3.3], { w: 1.6 }); put(sc, 'plant.tall', [-3.4, -3.0]); }
    else if (o.style === 'class') { for (let r = 0; r < 2; r++) for (let c = -1; c <= 1; c++) { put(sc, 'table.small', [c * 2.2, -0.4 + r * 1.9]); put(sc, 'chair', [c * 2.2, 0.3 + r * 1.9], { yaw: 180 }); } }
    else { put(sc, 'sofa', [-1.8, -2.9]); put(sc, 'table.low', [-1.8, -1.6]); put(sc, 'shelf', [2.6, -3.3]); put(sc, 'plant', [3.5, -2.2]); }
  }
  return sc;
}
/** The flat roof of a tall building, the town spread out below and around. o: { time, town (how many buildings round it, 90; 0 when a painted backdrop() gives the city) } */
export function rooftop(o = {}) {
  const { sc, L, night, low } = open(o, { fog: 0.7 }), lit = night || low ? L.win : null;
  sc.add('ground', ground({ y: -40, near: '#5a5e66', far: '#8a8e96', farAt: 400 }));
  const town = [];
  for (let i = 0; i < (o.town ?? 90); i++) { const a = hash(i * 1.37) * 6.28, r = 34 + hash(i * 2.9) * 260, x = Math.cos(a) * r, z = Math.sin(a) * r, hh = 26 + hash(i * 5.7) * (i % 5 === 0 ? 62 : 30); town.push([r, new Mesh(box([x, -40, z], 12 + hash(i) * 14, hh, 12 + hash(i * 3) * 10, hash(i * 4) * 30, { side: ['#8f9aa8', '#a8a49a', '#7f8a94', '#b9b2a6', '#6f7a88'][i % 5], top: '#5a5e66' }, lit && i % 2 === 0 ? { side: DET.marks(Array.from({ length: 14 }, (_, k) => ['glow', 0.1 + (k % 4) * 0.22, 0.2 + Math.floor(k / 4) * 0.2, 0.22 + (k % 4) * 0.22, 0.3 + Math.floor(k / 4) * 0.2, lit])) } : { side: DET.lattice(5, 8, '#5d7388') }), { p: [x, -40, z] })]); }
  for (const [, m] of town.sort((a, b) => b[0] - a[0])) sc.add('far', m);
  sc.add('ground', slab({ pts: [[-13.4, -10.4], [13.4, -10.4], [13.4, 10.4], [-13.4, 10.4]], y: 0, color: '#7a7e86', far: '#8a8e96', detail: (g, cam) => { g.strokeStyle = '#6a6e76'; g.lineWidth = 1; g.beginPath(); for (let x = -12; x <= 12; x += 2) { const q = clipSeg(cam, [x, 0.002, -10.2], [x, 0.002, 10.2]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } for (let z = -10; z <= 10; z += 2) { const q = clipSeg(cam, [-13.2, 0.002, z], [13.2, 0.002, z]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } g.stroke(); } }));
  for (const [x, z, w2, d2] of [[0, 10.2, 26.4, 0.4], [0, -10.2, 26.4, 0.4], [13.2, 0, 0.4, 20.4], [-13.2, 0, 0.4, 20.4]]) sc.add('world', new Mesh(box([x, 0, z], w2, 1.0, d2, 0, { side: '#9aa0a8', top: '#b4b8be' }), { p: [x, 0, z] }));
  sc.add('world', new Mesh(box([8, 0, -6], 4, 2.6, 3.4, 0, { side: '#a8a49a', top: '#8a867e' }, { front: DET.marks([['rect', 0.35, 0, 0.65, 0.8, '#4a4e58']]) }), { p: [8, 0, -6] }));
  sc.add('world', new Mesh(box([-8, 0, -7], 2.2, 1.4, 2.2, 0, { side: '#7a8088', top: '#5a6068' }, { side: DET.planks(0.2, 'h') }), { p: [-8, 0, -7] }));
  return sc;
}
/** A still lake beyond the story (−z) with a wooden pier at x = 6 running out into it, grass shore, hills beyond. o: { time } */
export function lakeside(o = {}) {
  const { sc, L, night, low } = open(o);
  hills(sc, L, night);
  sc.add('ground', grass(night));
  const water = night ? '#16264a' : low ? '#6a78a8' : '#3f7fae';
  sc.add('ground', slab({ pts: [...blob(0, -72, 150, 62, 5, 22)], y: 0.005, color: water, far: night ? '#22345e' : low ? '#e8b098' : '#9cc4dc',
    detail: (g, cam, s2) => { g.fillStyle = night ? '#9fb4e8' : '#ffffff'; for (let i = 0; i < 80; i++) { const x = -70 + hash(i * 2.9) * 140, z = -14 - hash(i * 1.7) * 90, q = cam.project([x, 0.01, z]); if (q[2] < 1) continue; const w = (0.5 + hash(i * 3.3) * 1.6) * cam.f / q[2]; g.globalAlpha = 0.35 * (0.5 + 0.5 * Math.sin(s2.t * 1.1 + i)); g.fillRect(q[0] - w / 2, q[1], w, Math.max(1, w * 0.05)); } g.globalAlpha = 1; } }));
  const pier = [...box([6, 0.25, -17], 2.2, 0.12, 16, 0, { side: '#7a5a40', top: '#9a7a58' }, { top: DET.planks(0.3, 'h') })]; for (let z = -24; z <= -9; z += 3) for (const x of [5, 7]) pier.push(...box([x, -0.6, z], 0.16, 1.1, 0.16, 0, { side: '#5a4634' }));
  sc.add('world', new Mesh(pier, { p: [6, 0, -17] }));
  woods(sc, 50, { leaf: leaves(night, low), clear: 16, pines: 0.3, skip: (x, z) => z < -6 });
  for (let i = 0; i < 14; i++) sc.add('world', tree({ p: [-30 + i * 4.6 + hash(i) * 2, 0, -8.4 - hash(i * 3) * 1.2], h: 0.8 + hash(i * 2) * 0.7, kind: 'bush', seed: i + 80, leaf: '#6a9a4a', leafLit: '#8ab85e', leafShade: '#4a7a3a', trunk: '#5a4332' }));
  return sc;
}
/** A farm: a red barn, a house, fenced fields in rows, haystacks. o: { time } */
export function farm(o = {}) {
  const { sc, L, night, low } = open(o), lit = night || low ? L.win : null;
  hills(sc, L, night);
  sc.add('ground', grass(night, { flowers: 0.04 }));
  sc.add('ground', slab({ pts: blob(0, 0, 13, 9, 3), y: 0.006, color: '#b89e74', far: '#c4ad86' }));
  const field = (x0, z0, x1, z1, c1, rows) => sc.add('ground', slab({ pts: [[x0, z0], [x1, z0], [x1, z1], [x0, z1]], y: 0.008, color: c1, far: shade(c1, 0.12), detail: (g, cam) => { g.beginPath(); for (let i = 1; i < rows; i++) { const z = lerp(z0, z1, i / rows), q = clipSeg(cam, [x0, 0.01, z], [x1, 0.01, z]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } g.lineWidth = 1.2; g.strokeStyle = shade(c1, -0.25); g.stroke(); } }));
  field(20, -44, 70, -12, '#c9b45a', 16); field(-72, -44, -22, -12, '#8fb04e', 16); field(20, 12, 70, 30, '#8fb04e', 9); field(-72, 12, -22, 30, '#c9b45a', 9);
  sc.add('world', barn([-9, -16], { yaw: 12 })); sc.add('world', hut([10, -15], { yaw: -10, lit, w: 6.5, d: 5 }));
  for (const [a, b] of [[[20, -12], [70, -12]], [[20, -12], [20, -44]], [[-22, -12], [-72, -12]], [[-22, -12], [-22, -44]], [[20, 12], [70, 12]], [[-22, 12], [-72, 12]]]) sc.add('world', fence(a, b));
  for (const p of [[-15, -6], [-17.5, -7.5], [15, -5]]) sc.add('world', haystack(p));
  woods(sc, 26, { leaf: leaves(night, low), clear: 30, pines: 0.1, skip: (x, z) => Math.abs(x) > 18 && Math.abs(x) < 74 && Math.abs(z) > 10 && Math.abs(z) < 46 });
  return sc;
}
/** A country road along x through open land. o: { time } */
export function road(o = {}) {
  const { sc, L, night, low } = open(o);
  hills(sc, L, night);
  sc.add('ground', grass(night));
  sc.add('ground', slab({ pts: [[-400, -2.6], [400, -2.6], [400, 2.6], [-400, 2.6]], y: 0.006, color: '#b8a27a', far: '#c9b48e' }));
  for (let x = -120; x < 120; x += 24) sc.add('world', fence([x, 4.2], [x + 24, 4.2]));
  woods(sc, 34, { leaf: leaves(night, low), clear: 14, pines: 0.2, skip: (x, z) => Math.abs(z) < 6 });
  return sc;
}
/** Nothing but stars and a dark mirror of a floor: for dreams, titles, science fiction. o: { color } */
export function space(o = {}) {
  const c = o.color ?? '#0a0d1c';
  const sc = new Scene({ grade: { amb: '#b8c4ff', tint: '#5a6ad0', tintAmt: 0.1, gain: 0.9 }, fog: { color: c, dist: 140, max: 0.95, start: 8 }, sun: [0.3, 0.8, 0.5], wind: makeWind({ dir: [1, 0, 0], speed: 0.1, gust: 0.05, seed: 3 }), rim: '#9fb6f0', dust: null, shadowAlpha: 0.35, shadowColor: '#000004', post: { vignette: 0.55, grain: 0.04 } });
  sc.time = 'night'; sc.sunAt = dirOf(150, 34);
  sc.add('sky', sky({ stops: [[0, '#03040c'], [0.5, c], [1, shade(c, 0.25)]], stars: { n: 900, amt: 1, seed: 9 }, clouds: [], moon: o.moon === false ? null : { az: 150, el: 34, r: 3.2, color: '#f3f1e2', glow: '#8aa2e0', glowR: 22 } }));
  sc.add('ground', ground({ y: 0, near: shade(c, 0.12), far: c, farAt: 60 }));
  return sc;
}
export const PLACES = { studio, meadow, forest, desert, snowfield, beach, mountain, street, park, room, rooftop, lakeside, farm, road, space };
