// The film's places. Each returns a Scene ready for a shot; options pick the hour, the weather and what stands in it.
import { Scene, sky, range, ground, slab, Mesh, box, gable, post, panel, DET, tree, rock, banner, mist, dirOf, clipSeg } from '../engine/scene.js';
import { V, clamp, lerp, hash, shade as shadeHex } from '../engine/core.js';
import { makeWind } from '../engine/sim.js';
import { fire as fxFire } from '../engine/fx.js';

/* ------------------------------------------------------------------------------------------------ the bridge
 * A great stone bridge over a gorge on Valdhar's border. It runs along X: the deck is y = 0, x −70…70, z −4…4.
 * The army stands on the +X half; Orez comes from −X, with the rising sun behind him.
 * o.time: 'dawn' (cold open: blue hour, sun just up) | 'sunrise' (battle: gold) | 'morning' (after)
 */
const BR = { x0: -70, x1: 70, hw: 6, river: -72 };
const BRIDGE_LOOKS = {
  dawn: { stops: [[0, '#1d2f55'], [0.42, '#4b5a8c'], [0.74, '#b9849a'], [0.9, '#f0a57a'], [1, '#ffd08a']], sunEl: 6.5, sun: '#fff3d0', glow: '#ff9d5c', haze: '#c9a9b0', amb: '#d9d2e8', tint: '#ffb48a', tintAmt: 0.08, gain: 0.96, rim: '#ffc890', cloud: ['#f6c1a4', '#8a7aa6'], water: ['#22344e', '#b08f9a'], stars: 0.35 },
  sunrise: { stops: [[0, '#3a5c96'], [0.4, '#7f9bcb'], [0.72, '#f0c3a0'], [0.9, '#ffd690'], [1, '#fff0b8']], sunEl: 13, sun: '#fffbe6', glow: '#ffc56e', haze: '#e6cdb4', amb: '#fff0de', tint: '#ffc27a', tintAmt: 0.1, gain: 1.0, rim: '#ffe0a0', cloud: ['#fff0d2', '#c9a59c'], water: ['#2b4a66', '#d9c2a6'], stars: 0 },
  morning: { stops: [[0, '#3f78c2'], [0.45, '#8fb8e6'], [0.8, '#d5e6f5'], [1, '#f4f1e2']], sunEl: 24, sun: '#fffdf2', glow: '#fff0c0', haze: '#d3e0ec', amb: '#ffffff', tint: '#fff2d8', tintAmt: 0.04, gain: 1.03, rim: null, cloud: ['#ffffff', '#c3d2e6'], water: ['#2f5f7c', '#c2d6e2'], stars: 0 },
};
export function bridge(o = {}) {
  const L = BRIDGE_LOOKS[o.time ?? 'dawn'];
  const wind = makeWind({ dir: [-0.9, 0, 0.35], speed: o.wind ?? 1.6, gust: 1.0, seed: 4 });
  const sc = new Scene({
    grade: { amb: L.amb, tint: L.tint, tintAmt: L.tintAmt, gain: L.gain }, fog: { color: L.haze, dist: o.fogDist ?? 420, max: 0.92, start: 12 },
    sun: dirOf(-90 + (o.sunAz ?? 12), Math.max(14, L.sunEl + 16)), wind, rim: L.rim, dust: '#c8bcae', shadowAlpha: 0.3, shadowColor: '#1a1626',
    post: { vignette: 0.36, grain: 0.05, wash: [{ color: L.glow, to: '#27305a', a: o.time === 'morning' ? 0.03 : 0.085, op: 'source-over' }] },
  });
  sc.look = L; sc.sunAt = dirOf(-90 + (o.sunAz ?? 12), L.sunEl);
  // ---- sky
  const clouds = [];
  for (let i = 0; i < 12; i++) clouds.push({ kind: 'band', az: -170 + i * 30 + hash(i * 3.3) * 14, el: 10 + hash(i * 5.1) * 9, w: 30 + hash(i * 2.2) * 34, h: 2.6 + hash(i * 6.1) * 2.2, seed: i + 1, color: L.cloud[0], shade: L.cloud[1], alpha: 0.92, drift: 0.05 + 0.04 * hash(i), rim: o.time === 'morning' ? null : '#fff0cc' });
  for (let i = 0; i < 9; i++) clouds.push({ kind: 'band', az: 10 + i * 41, el: 22 + hash(i * 9.1) * 18, w: 34 + hash(i * 4.2) * 36, h: 2.4 + hash(i * 1.1) * 2.4, seed: i + 30, color: L.cloud[0], shade: L.cloud[1], alpha: 0.55, drift: 0.08 });
  sc.add('sky', sky({ stops: L.stops, sun: { az: -90 + (o.sunAz ?? 12), el: L.sunEl, r: 1.5, color: L.sun, glow: L.glow, glowR: 26 }, stars: L.stars ? { n: 260, amt: L.stars, seed: 3 } : null, clouds, band: { color: L.stops[L.stops.length - 1][1], el: 7, alpha: 0.55 } }));
  // ---- the ranges that ring the gorge
  const rg = (z, yaw, h, color, seed, snow, gap) => sc.add('far', range({ z, yaw, h, base: -40, drop: 60, span: z * 3.4, color, seed, peaks: 4 + (Math.abs(seed) % 3), snow: snow ? { line: snow, color: '#f1f3fa', shade: '#aebbdc' } : null, shade: shadeHex(color, -0.22), rough: 0.6, gap, mist: { color: L.haze, h: 0.55, alpha: 0.75 } }));
  for (const yaw of [0, 90, 180, -90]) {
    // towards the sunrise the ranges part, and the sun stands in the pass
    const east = yaw === -90, gp = east ? [0.5 + (o.sunAz ?? 12) / 180, 0.12, 0.85] : null;
    rg(2600, yaw + 8, east ? 520 : 640, '#525d80', 11 + yaw, 0.5, gp);
    rg(1500, yaw - 12, east ? 330 : 430, '#465473', 23 + yaw, 0.6, gp);
    rg(760, yaw + 5, east ? 150 : 230, '#3f5257', 37 + yaw, 0, gp);
  }
  // ---- the river far below, and the mist that lies in the gorge
  sc.add('ground', ground({ y: BR.river, near: L.water[0], far: L.water[1], farAt: 520, water: { color: '#fff2d8', alpha: 0.5, cell: 9, max: 300 } }));
  for (let i = 0; i < 16; i++) { const z = -330 + i * 44 + hash(i) * 18; sc.add('world', { ...mist({ p: [(hash(i * 3.7) - 0.5) * 90, -52 + hash(i * 2.1) * 26, z], w: 70 + hash(i * 1.3) * 50, h: 9 + hash(i * 4.4) * 7, color: L.haze, alpha: 0.5, drift: [0.5, 0.25], seed: i }), bias: 4000 }); }
  // ---- the two cliffs: a broken wall of rock along each side of the gorge, and the land on top
  const stone = '#7f7a7c', cliffC = '#6d6468';
  for (const side of [-1, 1]) {
    const edge = []; // the cliff's lip, from far −z to far +z
    for (let i = 0; i <= 26; i++) { const z = -650 + i * 50; edge.push([side * (70 + (Math.abs(z) < 30 ? 0 : 6 + hash(i * 3.1 + side) * 16)), z]); }
    const faces = [];
    for (let i = 0; i < edge.length - 1; i++) {
      const a = edge[i], b = edge[i + 1], lean = 9;
      const p = side < 0 ? [[a[0], 0, a[1]], [b[0], 0, b[1]], [b[0] + lean, BR.river, b[1]], [a[0] + lean, BR.river, a[1]]] : [[b[0], 0, b[1]], [a[0], 0, a[1]], [a[0] - lean, BR.river, a[1]], [b[0] - lean, BR.river, b[1]]];
      faces.push({ p, c: i % 2 ? cliffC : shadeHex(cliffC, 0.06), ink: false, lod: 1.2, detail: (g, F) => {
        g.beginPath();
        for (let r = 1; r < 7; r++) { const v = r / 7 + 0.03 * Math.sin(i * 2.1 + r); const s0 = F.uv(0, v), s1 = F.uv(1, v + 0.02 * Math.sin(i + r * 1.7)); g.moveTo(s0[0], s0[1]); g.lineTo(s1[0], s1[1]); }
        for (let c = 1; c < 4; c++) { const u = c / 4 + 0.08 * Math.sin(i * 1.3 + c), s0 = F.uv(u, 0), s1 = F.uv(u + 0.05, 0.5 + 0.2 * Math.sin(c + i)); g.moveTo(s0[0], s0[1]); g.lineTo(s1[0], s1[1]); }
        g.lineWidth = Math.max(0.6, F.lw * 0.8); g.strokeStyle = F.col(shadeHex(F.tone, -0.2)); g.stroke();
      } });
    }
    sc.add('world', new Mesh(faces, { p: [side * 76, 0, 0], bias: 5000 }));
    const top = edge.map((e) => [e[0], e[1]]).concat([[side * 900, 650], [side * 900, -650]]);
    sc.add('world', slab({ pts: top, y: 0, color: '#6c7459', far: '#8b8f86', bias: 2500 }));
    // pines along the lip and back from it
    for (let i = 0; i < 46; i++) { const z = -420 + hash(i * 1.7 + side) * 840, back = 6 + hash(i * 2.9 + side * 3) * 110; if (Math.abs(z) < 14) continue; sc.add('world', tree({ p: [side * (86 + back), 0, z], h: 9 + hash(i * 4.3) * 9, kind: 'pine', seed: i + (side > 0 ? 100 : 0), leaf: '#2c4a46', leafShade: '#1b2f33', trunk: '#3c2f2c' })); }
    for (let i = 0; i < 10; i++) sc.add('world', rock({ p: [side * (74 + hash(i * 5.5 + side) * 30), 0, (hash(i * 7.7 + side) - 0.5) * 120 + (i % 2 ? 22 : -22)], r: 1.2 + hash(i) * 2.2, seed: i + side, color: '#807b7e' }));
  }
  // ---- the bridge: piers, arches, deck, parapets with lantern posts, a gate at each end
  const sub = [];
  for (const x of [-42, -14, 14, 42]) sub.push(...box([x, BR.river, 0], 5.5, -BR.river - 9, 10.5, 0, { side: '#77737a' }, { side: DET.stone(2.2, 3.4) }));
  const spans = [-70, -42, -14, 14, 42, 70];
  for (let i = 0; i < spans.length - 1; i++) {
    const a = spans[i], b = spans[i + 1], arc = [];
    for (let k = 0; k <= 12; k++) { const u = k / 12, x = lerp(b - 2.75, a + 2.75, u); arc.push([x, -4.2 - 12 * (1 - Math.sin(Math.PI * u) ** 0.8)]); }
    for (const z of [BR.hw + 0.25, -BR.hw - 0.25]) {
      const pts = [[a, 0.0], [b, 0.0], [b, -16.5], ...arc, [a, -16.5]].map(([x, y]) => [x, y, z]);
      sub.push({ p: z > 0 ? pts : [...pts].reverse(), c: '#827d82', lod: 2, detail: (g, F) => { g.beginPath(); for (let r = 1; r < 9; r++) { const y = -r * 1.6, s = [[a, y, z], [b, y, z]]; const q = F.uv; void q; const A = F.proj(s[0]), B = F.proj(s[1]); g.moveTo(A[0], A[1]); g.lineTo(B[0], B[1]); } g.lineWidth = Math.max(0.6, F.lw * 0.7); g.strokeStyle = F.col(shadeHex(F.tone, -0.2)); g.stroke(); } });
    }
  }
  sc.add('world', new Mesh(sub, { p: [0, -20, 0], bias: 5000 }));
  sc.add('world', slab({ pts: [[BR.x0 - 30, -BR.hw - 0.5], [BR.x1 + 30, -BR.hw - 0.5], [BR.x1 + 30, BR.hw + 0.5], [BR.x0 - 30, BR.hw + 0.5]], y: 0, color: '#8d8887', far: '#9a9492', bias: 2400,
    detail: (g, cam, s2) => {
      // paving: courses across the deck and three joints along it, near the lens only
      const cx = clamp(cam.pos[0], BR.x0, BR.x1); g.beginPath();
      for (let x = Math.ceil((cx - 70) / 2.5) * 2.5; x <= cx + 70; x += 2.5) { if (x < BR.x0 - 30 || x > BR.x1 + 30) continue; const q = clipSeg(cam, [x, 0, -BR.hw], [x, 0, BR.hw]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } }
      for (const z of [-4, -2, 0, 2, 4]) { const q = clipSeg(cam, [Math.max(BR.x0 - 30, cx - 70), 0, z], [Math.min(BR.x1 + 30, cx + 70), 0, z]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } }
      g.lineWidth = 1.1 * (cam.H / 1080); g.strokeStyle = s2.col('#6f6a6c', 18); g.globalAlpha = 0.55; g.stroke(); g.globalAlpha = 1;
    } }));
  for (const z of [BR.hw + 0.05, -BR.hw - 0.05]) {
    for (let x = BR.x0; x < BR.x1; x += 10) {
      sc.add('world', new Mesh(box([x + 5, 0, z], 9.3, 0.95, 0.5, 0, { side: '#96908e', top: '#a8a2a0' }, { side: DET.stone(0.32, 0.8) }), { p: [x + 5, 0, z] }));
      // a lantern post where two lengths of wall meet
      const lp = [x, 0, z];
      sc.add('world', new Mesh([...box(lp, 0.8, 1.9, 0.8, 0, { side: '#8e8886' }), ...box([x, 1.9, z], 1.15, 0.16, 1.15, 0, { side: '#a39d9a' }), ...box([x, 2.06, z], 0.62, 0.55, 0.62, 0, { side: '#6f6a69' }, { side: DET.marks([['glow', 0.2, 0.15, 0.8, 0.85, o.lamps === false ? '#3a3636' : '#ffd08a']]) }), ...gable([x, 2.61, z], 0.7, 0.7, 0, { rise: 0.42, over: 0.26, color: '#5d5a60', hip: true })], { p: lp }));
    }
    sc.add('world', new Mesh(box([BR.x1, 0, z], 0.8, 1.9, 0.8, 0, { side: '#8e8886' }), { p: [BR.x1, 0, z] }));
  }
  // gates: tall stone pillars and a beam, a tiled roof over the far one
  for (const [x, tall, roofed] of [[BR.x1 + 4, 15, true], [BR.x0 - 4, 9, false]]) {
    const f = [];
    for (const z of [BR.hw + 1.6, -BR.hw - 1.6]) f.push(...box([x, 0, z], 2.8, tall, 2.8, 0, { side: '#7d787a' }, { side: DET.stone(1.0, 1.4) }));
    f.push(...box([x, tall, 0], 3.3, 1.5, BR.hw * 2 + 7, 0, { side: '#5c3d38', top: '#6a4a44' }));
    if (roofed) { f.push(...box([x, tall + 1.5, 0], 2.4, 2.2, BR.hw * 2 + 3, 0, { side: '#74706f' }, { side: DET.marks([['rect', 0.08, 0.2, 0.92, 0.8, '#3a2e30']]) })); f.push(...gable([x, tall + 3.7, 0], BR.hw * 2 + 5, 4.2, 90, { rise: 2.6, over: 1.6, color: '#3f4552', gable: '#6f6a69', sag: 1.3, detail: DET.tiles(0.7, 0.6) })); }
    sc.add('world', new Mesh(f, { p: [x, 0, 0] }));
    if (roofed && o.banners !== false) for (const z of [BR.hw + 3.6, -BR.hw - 3.6]) sc.add('world', banner({ p: [x - 1.2, 0, z], h: 12, w: 6.5, len: 2.6, color: '#7c1f27', trim: '#d9b25a', seed: z, dir: [-1, 0, 0.25] }));
  }
  // low mist creeping over the deck
  if (o.deckMist !== false) for (let i = 0; i < 9; i++) sc.add('world', mist({ p: [-66 + i * 16 + hash(i * 2.2) * 7, 0.5 + hash(i) * 0.5, (hash(i * 3.9) - 0.5) * 10], w: 11 + hash(i * 1.9) * 9, h: 1.1 + hash(i * 5.2) * 0.7, color: L.haze, alpha: 0.3, drift: [-0.45, 0.1], seed: i * 3 }));
  return sc;
}

/* ------------------------------------------------------------------------------------------------ Kiri village
 * A farming village in a river valley. The yard is at the origin; houses stand around it; the river and its dike
 * run along +z (z ≈ 20…32), the road comes in from the east (+x). Sena's house: front door at (-10, -5.4), porch facing +z.
 * o.time: 'day' | 'dusk' | 'night' | 'fire' (the raid: night, houses burning: o.burn = how far the fire has spread 0..1)
 */
export const KIRI = {
  houses: [[-10, -8, 0, 7, 5], [2, -11.5, 0, 8, 5.5], [13.5, -7, -22, 6.5, 5], [17, 6, -90, 7, 5], [-18, 2.5, 90, 6, 5], [-6, -20, 10, 6, 5], [10, -21, -8, 7, 5], [25, -15, -30, 6, 5], [-23, -13, 40, 6, 5], [29, 2, -75, 6.5, 5], [-30, -3, 70, 6, 5]],
  well: [3.2, 2.4], tree: [-3.5, 4.2], dike: 19, porch: [-10, -4.6],
};
const KIRI_LOOKS = {
  day: { stops: [[0, '#2c6dc4'], [0.45, '#6fb0ec'], [0.82, '#c7e3f6'], [1, '#eef5e6']], sun: { az: 140, el: 48, color: '#fffbe8', glow: '#fff3c4', glowR: 20 }, haze: '#cfe2ee', amb: '#ffffff', tint: '#fff4d6', tintAmt: 0.05, gain: 1.04, fog: 520, cloud: ['#ffffff', '#c6d6ec'], grass: ['#74a84e', '#a3c07c'], stars: 0, win: null, sunDir: [0.5, 0.75, 0.45] },
  dusk: { stops: [[0, '#3b4f8f'], [0.4, '#8b7fb8'], [0.75, '#f0a590'], [1, '#ffd69a']], sun: { az: -100, el: 4, color: '#fff0c8', glow: '#ff9a5a', glowR: 28 }, haze: '#e0b8a8', amb: '#ffe2cc', tint: '#ff9f6a', tintAmt: 0.14, gain: 0.95, fog: 420, cloud: ['#ffd0b0', '#9a7fa6'], grass: ['#7a9a4e', '#b3a57c'], stars: 0.2, win: '#ffd08a', sunDir: [-0.8, 0.3, 0.3] },
  night: { stops: [[0, '#070b1f'], [0.5, '#101a3c'], [0.85, '#1f2f5a'], [1, '#2f4470']], moon: { az: 150, el: 34, r: 2.2, color: '#f3f1e2', glow: '#8aa2e0', glowR: 16, crescent: null }, haze: '#22305a', amb: '#7c8fcc', tint: '#3a4fa0', tintAmt: 0.12, gain: 0.8, fog: 300, cloud: ['#3a4a7a', '#1c2648'], grass: ['#74a84e', '#8fae74'], stars: 1, win: '#ffc878', sunDir: [0.4, 0.7, 0.5] },
  fire: { stops: [[0, '#0b0714'], [0.45, '#2a1220'], [0.8, '#6a2418'], [1, '#c2521c']], moon: { az: 150, el: 34, r: 2.2, color: '#e8b89a', glow: '#b05030', glowR: 14 }, haze: '#3a1a16', amb: '#9a6a62', tint: '#ff6a20', tintAmt: 0.16, gain: 0.8, fog: 170, cloud: ['#5a2a22', '#1c0f18'], grass: ['#74a84e', '#8fae74'], stars: 0.25, win: '#ff9a3a', sunDir: [0.2, 0.6, -0.6] },
};
/** One house: plastered walls in a timber frame, a thatched roof, a door and windows on its front (the +z side before yaw). */
export function house(x, z, yawDeg, w, d, o = {}) {
  const h = o.h ?? 2.7, wall = o.wall ?? '#d9cbac', beam = '#5a4634', lit = o.win;
  const frame = (g, F) => { g.beginPath(); const n = Math.max(2, Math.round(F.w / 1.6)); for (let i = 1; i < n; i++) { const a = F.uv(i / n, 0), b = F.uv(i / n, 1); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); } const a = F.uv(0, 0.45), b = F.uv(1, 0.45); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.lineWidth = Math.max(1, F.px * 0.07); g.strokeStyle = F.col(beam); g.stroke(); };
  const wk = lit ? 'glow' : 'rect';
  const front = DET.all(frame, DET.marks([['rect', 0.42, 0, 0.58, 0.78, '#3a2c22', beam], [wk, 0.12, 0.42, 0.3, 0.78, lit ?? '#4a3c30', beam], [wk, 0.7, 0.42, 0.88, 0.78, lit ?? '#4a3c30', beam], ['line', 0.21, 0.42, 0.21, 0.78, beam, 1.2], ['line', 0.79, 0.42, 0.79, 0.78, beam, 1.2]]));
  const side = DET.all(frame, DET.marks([[wk, 0.38, 0.45, 0.62, 0.78, lit ?? '#4a3c30', beam]]));
  const f = [
    ...box([x, 0, z], w, 0.35, d + 1.3, yawDeg, { side: '#8a8478', top: '#9a9488' }),                                   // stone footing and porch floor
    ...box([x, 0.35, z], w, h, d, yawDeg, { side: wall, top: null }, { front, back: frame, left: side, right: side }),
    ...gable([x, 0.35 + h, z], w, d, yawDeg, { rise: o.rise ?? 2.3, over: 0.75, color: o.roof ?? '#b39a5c', gable: wall, detail: DET.thatch(0.55), under: '#6a5a40' }),
  ];
  return new Mesh(f, { p: [x, 0, z] });
}
export function village(o = {}) {
  const time = o.time ?? 'day', L = KIRI_LOOKS[time], night = time === 'night' || time === 'fire';
  const wind = makeWind({ dir: [0.9, 0, 0.3], speed: time === 'fire' ? 2.2 : 1.0, gust: 0.8, seed: 7 });
  const sc = new Scene({
    grade: { amb: L.amb, tint: L.tint, tintAmt: L.tintAmt, gain: L.gain }, fog: { color: L.haze, dist: L.fog, max: 0.9, start: 10 }, sun: L.sunDir, wind,
    rim: time === 'fire' ? '#ff8a3a' : time === 'dusk' ? '#ffc890' : null, dust: night ? null : '#cdbb96', shadowAlpha: night ? 0.22 : 0.3, shadowColor: night ? '#05060f' : '#1c2a14',
    post: { vignette: night ? 0.5 : 0.28, grain: 0.045, wash: time === 'fire' ? [{ color: '#ff6a20', to: '#1a0a14', a: 0.1, op: 'source-over' }] : time === 'night' ? [{ color: '#1a2a6a', to: '#0a1030', a: 0.1, op: 'source-over' }] : time === 'dusk' ? [{ color: '#ff9a5a', to: '#3a3a7a', a: 0.07, op: 'source-over' }] : [] },
  });
  sc.look = L; sc.time = time;
  // ---- sky
  const clouds = [];
  const nC = night ? 6 : 14;
  for (let i = 0; i < nC; i++) clouds.push({ kind: i % 3 === 0 || night ? 'band' : 'puff', az: -180 + i * (360 / nC) + hash(i * 3.3) * 16, el: (night ? 14 : 12) + hash(i * 5.1) * 16, w: 20 + hash(i * 2.2) * 26, h: (i % 3 === 0 || night ? 2.4 : 6) + hash(i * 6.1) * 3.5, seed: i + 11, color: L.cloud[0], shade: L.cloud[1], alpha: night ? 0.55 : 0.96, drift: 0.06 + 0.05 * hash(i), rim: time === 'dusk' ? '#fff0cc' : null });
  sc.add('sky', sky({ stops: L.stops, sun: L.sun, moon: L.moon, stars: L.stars ? { n: 520, amt: L.stars, seed: 5 } : null, clouds, band: { color: L.stops[L.stops.length - 1][1], el: 6, alpha: 0.6 } }));
  // ---- the valley's walls
  const hill = night ? '#2a3a5a' : '#5d8a5a', far = night ? '#3a4a78' : '#6f8fb0';
  for (const yaw of [0, 90, 180, -90]) {
    sc.add('far', range({ z: 2400, yaw: yaw + 10, h: 560, base: -20, drop: 40, span: 8200, color: far, shade: shadeHex(far, -0.2), seed: 61 + yaw, peaks: 5, snow: { line: 0.55, color: night ? '#9aa8d0' : '#f4f6fb', shade: night ? '#6a78a8' : '#b9c8e4' }, mist: { color: L.haze, h: 0.5, alpha: 0.7 } }));
    sc.add('far', range({ z: 900, yaw: yaw - 14, h: 210, base: -10, drop: 30, span: 3200, color: hill, shade: shadeHex(hill, -0.22), seed: 73 + yaw, peaks: 6, rough: 0.3, mist: { color: L.haze, h: 0.6, alpha: 0.6 } }));
    sc.add('far', range({ z: 330, yaw: yaw + 6, h: 60, base: -4, drop: 12, span: 1300, color: night ? '#22324a' : '#6a9a52', shade: night ? '#182438' : '#4f7f44', seed: 87 + yaw, peaks: 7, rough: 0.2 }));
  }
  // ---- ground: grass, worn earth in the yard and on the road, fields, the river behind its dike
  const g0 = L.grass;
  sc.add('ground', ground({ y: 0, near: g0[0], far: g0[1], farAt: 260, patch: { colors: ['#6a9e48', '#84b25a', '#7aa650', '#8fb866', '#6f9a4c'], cell: 3.4, max: 90, amt: 0.62, size: [0.8, 2.8], alpha: 0.6 }, tuft: { colors: ['#5f9440', '#79aa52', '#6b9d47', '#8ab85e'], cell: 0.7, max: 17, amt: 0.6, h: [0.1, 0.3], flower: night ? null : { amt: 0.09, colors: ['#fff6c8', '#ffd24a', '#ffffff', '#f5a6c0'] } } }));
  const earth = '#b89e74';
  const blobPts = (cx, cz, rx, rz, seed, n = 14) => Array.from({ length: n }, (_, i) => { const a = (i / n) * Math.PI * 2, k = 0.82 + 0.3 * hash(i * 2.1 + seed); return [cx + Math.cos(a) * rx * k, cz + Math.sin(a) * rz * k]; });
  sc.add('ground', slab({ pts: blobPts(1, 0, 13, 9.5, 3), y: 0.006, color: earth, far: '#c4ad86' }));
  sc.add('ground', slab({ pts: [[10, -1.6], [40, 1], [90, -3], [160, 4], [160, 8], [90, 1.5], [40, 5], [10, 2.2]], y: 0.007, color: earth, far: '#c9b48e' }));
  sc.add('ground', slab({ pts: [[-10, -1.5], [-40, 3], [-90, 0], [-90, 4], [-40, 7], [-10, 2]], y: 0.007, color: earth, far: '#c9b48e' }));
  // fields in rows
  const field = (x0, z0, x1, z1, c1, c2, rows) => { sc.add('ground', slab({ pts: [[x0, z0], [x1, z0], [x1, z1], [x0, z1]], y: 0.008, color: c1, far: shadeHex(c1, 0.12), detail: (g, cam, s2) => { g.beginPath(); for (let i = 1; i < rows; i++) { const z = lerp(z0, z1, i / rows), q = clipSeg(cam, [x0, 0.01, z], [x1, 0.01, z]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } g.lineWidth = 1.6 * (cam.H / 1080); g.strokeStyle = s2.col(c2, 40); g.globalAlpha = 0.7; g.stroke(); g.globalAlpha = 1; } })); };
  field(34, -34, 78, -8, '#c9b45a', '#a8923c', 14); field(34, 10, 80, 17, '#8fb04e', '#6f9438', 5); field(-78, -40, -36, -14, '#8fb04e', '#6f9438', 14); field(-80, 9, -34, 17, '#c9b45a', '#a8923c', 5);
  // the river and the dike that keeps it
  const dz = KIRI.dike;
  sc.add('ground', slab({ pts: [[-400, dz + 3.2], [400, dz + 3.2], [400, dz + 16], [-400, dz + 16]], y: -0.5, color: night ? '#16264a' : '#3f7fae', far: night ? '#22345e' : '#9cc4dc',
    detail: (g, cam, s2) => { g.fillStyle = night ? '#9fb4e8' : '#ffffff'; for (let i = 0; i < 90; i++) { const x = cam.pos[0] - 60 + ((i * 37.7 + s2.t * 3.1 * (0.6 + hash(i))) % 120), z = dz + 4 + hash(i * 1.7) * 11, q = cam.project([x, -0.5, z]); if (q[2] < 1) continue; const w = (0.5 + hash(i * 3.3) * 1.4) * cam.f / q[2]; g.globalAlpha = 0.35 * (0.5 + 0.5 * Math.sin(s2.t * 1.3 + i)); g.fillRect(q[0], q[1], w, Math.max(1, cam.f / q[2] * 0.03)); } g.globalAlpha = 1; } }));
  const dike = [];
  for (let x = -120; x < 120; x += 12) dike.push(...box([x + 6, 0, dz], 12, 1.25, 3.6, 0, { side: '#7f9a58', top: '#8aa862' }));
  sc.add('world', new Mesh(dike, { p: [0, 0, dz], bias: 2000 }));
  // sandbags where the dike is weak
  const bags = [];
  for (let i = 0; i < 16; i++) { const r = Math.floor(i / 6), c = i % 6; bags.push(...box([4.2 + c * 0.82 + (r % 2) * 0.4, 1.25 + r * 0.26, dz - 0.6], 0.8, 0.26, 0.45, (hash(i) - 0.5) * 14, { side: '#c9b78e', top: '#d6c49a' })); }
  sc.add('world', new Mesh(bags, { p: [6.5, 1.2, dz - 0.6] }));
  // ---- shadows on the ground: houses and trees (where the light falls from)
  const sdx = -L.sunDir[0] / Math.max(0.25, L.sunDir[1]), sdz = -L.sunDir[2] / Math.max(0.25, L.sunDir[1]), shA = night ? 0.2 : 0.26, shC = night ? '#04050c' : '#1f3320';
  const shadow = (pts) => sc.add('ground', slab({ pts, y: 0.012, raw: shC, color: shC, alpha: shA }));
  KIRI.houses.forEach(([x, z, yaw, w, d]) => {
    const a = yaw * Math.PI / 180, c = Math.cos(a), sn = Math.sin(a), hw = w / 2 + 0.6, hd = d / 2 + 0.6, hh = 4.2;
    const cor = [[-hw, -hd], [hw, -hd], [hw, hd], [-hw, hd]].map(([px, pz]) => [x + px * c + pz * sn, z - px * sn + pz * c]);
    // the footprint and the footprint moved along the light: their hull is the shadow
    const all = cor.concat(cor.map(([px, pz]) => [px + sdx * hh, pz + sdz * hh])).sort((p, q) => p[0] - q[0] || p[1] - q[1]);
    const cr = (o2, p, q) => (p[0] - o2[0]) * (q[1] - o2[1]) - (p[1] - o2[1]) * (q[0] - o2[0]), lo = [], up = [];
    for (const p of all) { while (lo.length >= 2 && cr(lo[lo.length - 2], lo[lo.length - 1], p) <= 0) lo.pop(); lo.push(p); }
    for (let i = all.length - 1; i >= 0; i--) { const p = all[i]; while (up.length >= 2 && cr(up[up.length - 2], up[up.length - 1], p) <= 0) up.pop(); up.push(p); }
    lo.pop(); up.pop(); shadow(lo.concat(up));
  });
  const treeShadow = (x, z, h, r) => shadow(blobPts(x + sdx * h * 0.55, z + sdz * h * 0.55, r * (1 + Math.abs(sdx) * 0.5), r * (1 + Math.abs(sdz) * 0.5), x + z, 12));
  treeShadow(KIRI.tree[0], KIRI.tree[1], 11.5, 5.2);
  // ---- houses
  KIRI.houses.forEach(([x, z, yaw, w, d], i) => sc.add('world', house(x, z, yaw, w, d, { win: L.win, roof: ['#b39a5c', '#a89054', '#bca466'][i % 3], wall: ['#d9cbac', '#cfc0a0', '#e0d3b6'][i % 3], h: 2.6 + 0.2 * (i % 2) })));
  // ---- the well: a stone ring and a little roof
  const [wx, wz] = KIRI.well;
  sc.add('world', new Mesh([...box([wx, 0, wz], 1.5, 0.75, 1.5, 0, { side: '#8f8a84', top: '#3a4a5a' }, { side: DET.stone(0.25, 0.5) }), ...post([wx - 0.8, 0, wz], 0.06, 2.1, '#5a4634'), ...post([wx + 0.8, 0, wz], 0.06, 2.1, '#5a4634'), ...gable([wx, 2.1, wz], 2.0, 1.4, 0, { rise: 0.55, over: 0.25, color: '#8a6a44', gable: '#6a5236' })], { p: [wx, 0, wz] }));
  // ---- trees: the big one in the yard, orchards and woods around
  const leafC = night ? ['#35603f', '#4a7a52', '#234430'] : time === 'dusk' ? ['#5f8a3c', '#86a84c', '#3f6430'] : ['#4f9440', '#79b856', '#35702e'];
  sc.add('world', tree({ p: [KIRI.tree[0], 0, KIRI.tree[1]], h: 11.5, kind: 'leaf', seed: 4, n: 13, spread: 1.25, thick: 1.5, leaf: leafC[0], leafLit: leafC[1], leafShade: leafC[2], trunk: '#5a4332' }));
  for (let i = 0; i < 70; i++) {
    const a = hash(i * 1.37) * Math.PI * 2, r = 26 + hash(i * 2.9) * 120, x = Math.cos(a) * r, z = Math.sin(a) * r * 0.8 - 12;
    if (z > dz - 5 || (Math.abs(z - 2) < 7 && Math.abs(x) > 8) || KIRI.houses.some((hh) => Math.hypot(hh[0] - x, hh[1] - z) < 6.5)) continue;
    const pine = hash(i * 7.1) > 0.72;
    if (r < 70) treeShadow(x, z, pine ? 10 : 7, pine ? 1.6 : 2.6);
    sc.add('world', tree({ p: [x, 0, z], h: pine ? 8 + hash(i) * 6 : 5 + hash(i * 3.1) * 5, kind: pine ? 'pine' : 'leaf', seed: i + 20, leaf: pine ? (night ? '#1f3a34' : '#2f6a4a') : leafC[0], leafLit: leafC[1], leafShade: pine ? (night ? '#132622' : '#1f4a36') : leafC[2], trunk: '#5a4332' }));
  }
  for (let i = 0; i < 26; i++) { const a = hash(i * 9.3) * 6.28, r = 12 + hash(i * 4.1) * 30, x = Math.cos(a) * r, z = Math.sin(a) * r * 0.7 - 4; if (z > dz - 3 || Math.hypot(x - 1, z) < 12 || KIRI.houses.some((hh) => Math.hypot(hh[0] - x, hh[1] - z) < 5.5)) continue; sc.add('world', tree({ p: [x, 0, z], h: 1.2 + hash(i) * 1.1, kind: 'bush', seed: i + 300, leaf: leafC[0], leafLit: leafC[1], leafShade: leafC[2] })); }
  // ---- fences, hay, barrels: the clutter of people who live here
  const fence = (a, b) => { const n = Math.max(1, Math.round(Math.hypot(b[0] - a[0], b[1] - a[1]) / 1.6)), f = []; for (let i = 0; i <= n; i++) { const x = lerp(a[0], b[0], i / n), z = lerp(a[1], b[1], i / n); f.push(...post([x, 0, z], 0.05, 1.0, '#7a6248')); } f.push(...panel(a, b, 0.72, 0.09, '#8a7052'), ...panel(a, b, 0.36, 0.09, '#8a7052')); sc.add('world', new Mesh(f, { p: [(a[0] + b[0]) / 2, 0, (a[1] + b[1]) / 2] })); };
  fence([-14.5, -4], [-14.5, 5]); fence([8, -5.2], [12, -3.5]); fence([20, -2], [20, 3]); fence([-6, 9.5], [4, 10.5]); fence([33, -8], [33, 0]); fence([-33, -14], [-33, 8]);
  for (const [x, z, s] of [[9.5, 6.5, 1.2], [11.3, 7.2, 0.9], [-13, 8, 1.1], [22, -6, 1.0]]) sc.add('world', { p: [x, 0, z], r: 2, draw(g, cam, s2) { const b = cam.project([x, 0, z]); if (b[2] < 0.3) return; const k = cam.f / b[2] * s, d = V.dist(cam.pos, [x, 0, z]); g.beginPath(); g.moveTo(b[0] - k * 1.0, b[1]); g.quadraticCurveTo(b[0] - k * 0.95, b[1] - k * 1.3, b[0], b[1] - k * 1.55); g.quadraticCurveTo(b[0] + k * 0.95, b[1] - k * 1.3, b[0] + k * 1.0, b[1]); g.closePath(); g.fillStyle = s2.col('#d6b866', d); g.fill(); g.lineWidth = Math.max(0.8, k * 0.02); g.strokeStyle = s2.col('#8a6f34', d); g.stroke(); g.save(); g.clip(); g.fillStyle = s2.col('#b8983e', d); g.fillRect(b[0] + k * 0.15, b[1] - k * 1.6, k, k * 1.7); g.restore(); } });
  for (const [x, z] of [[-6.4, -4.2], [-5.6, -4.5], [6.3, -7.6], [14.8, 2.2]]) sc.add('world', new Mesh(box([x, 0, z], 0.62, 0.85, 0.62, 20, { side: '#7a5a3c', top: '#5a4028' }, { side: DET.planks(0.14) }), { p: [x, 0, z] }));
  // washing on a line between two posts
  sc.add('world', new Mesh([...post([-15.5, 0, -10], 0.05, 2.0, '#6a5440'), ...post([-19.5, 0, -8.5], 0.05, 2.0, '#6a5440')], { p: [-17.5, 0, -9.2] }));
  for (let i = 0; i < 4; i++) sc.add('world', banner({ p: [-16 - i * 0.85, 0.7, -9.8 + i * 0.32], h: 1.25, w: 0.9 + 0.2 * (i % 2), len: 0.7, color: ['#e8e2d2', '#7a9ac8', '#d98a6a', '#e8e2d2'][i], pole: '#00000000', seed: i * 3, dir: [0.25, 0, 1] }));
  // the raid: roofs alight, the ground lit from every side
  if (time === 'fire') {
    const burn = o.burn ?? 1, skip = o.spare ?? [];
    KIRI.houses.forEach(([x, z, yw, w, d], i) => {
      if (skip.includes(i) || hash(i * 7.7) > burn + 0.15) return;
      const c = Math.cos(yw * Math.PI / 180), sn = Math.sin(yw * Math.PI / 180), n = i < 5 ? 3 : 2;
      for (let k = 0; k < n; k++) { const u = (k + 0.5) / n - 0.5, lx = u * w * 0.8, px = x + lx * c, pz = z - lx * sn; sc.add('world', fxFire({ p: [px, 3.1 + 0.5 * hash(i * 3 + k), pz], w: 2.6 + hash(i + k * 5) * 1.2, h: (2.6 + hash(i * 9 + k) * 2.2) * (i < 5 ? 1 : 0.8), seed: i * 5 + k, tongues: 6 })); }
      const pool = Array.from({ length: 14 }, (_, q) => { const a = (q / 14) * 6.283; return [x + Math.cos(a) * (w * 0.9 + 2.5), z + Math.sin(a) * (d * 0.9 + 2.5)]; });
      sc.add('ground', slab({ pts: pool, y: 0.013, raw: '#ff7a22', color: '#ff7a22', alpha: 0.1 }));
    });
    for (const [x, z] of [[-3.5, 4.2]]) sc.add('world', fxFire({ p: [x, 6.4, z], w: 4.5, h: 4.2, seed: 77, tongues: 7 }));
    for (const [x, z, hh] of [[7.5, -3.5, 1.3], [-14, 8.5, 1.1], [11, 12.5, 1.2], [-6.5, 12, 1.0], [20, -2, 1.4]]) sc.add('world', fxFire({ p: [x, 0.05, z], w: 1.5, h: hh * 1.6, seed: x * 3 + z, tongues: 4 }));
  }
  if (night && time !== 'fire') for (let i = 0; i < 5; i++) sc.add('world', mist({ p: [-30 + i * 16, 0.8, 10 + hash(i) * 6], w: 14, h: 1.2, color: '#5a70b0', alpha: 0.22, drift: [0.2, 0], seed: i }));
  return sc;
}

/* ------------------------------------------------------------------------------------------------ the mountain
 * steps(o): the long stair up the mountain in snow. It climbs along −z: a point at distance s up the stair is
 *   [x, STEP.rise * s, -s]; STEP.h(z) gives the height under a foot. Gates stand over it; pines and rocks beside it.
 * courtyard(o): the temple yard on top (flat, the origin is the middle of the yard; the hall is at −z, the gate at +z,
 *   the great tree at (−7, 3)); o.season: 'winter' | 'spring' | 'summer' | 'autumn'; o.time: 'day' | 'dawn' | 'dusk' | 'night'.
 */
export const STEP = { rise: 0.5, tread: 0.42, w: 3.4, h: (z) => (z < 0 ? Math.floor(-z / 0.42 + 0.5) * 0.21 : 0) };
const SEASON = {
  winter: { stops: [[0, '#5d7396'], [0.5, '#9fb0c8'], [0.85, '#d6dde8'], [1, '#eef0f2']], haze: '#dfe5ee', amb: '#eef2ff', tint: '#cfdcf5', tintAmt: 0.1, gain: 1.0, ground: ['#eef2f8', '#dfe6f0'], leaf: null, snow: '#f6f8fc', fog: 150, sunDir: [0.4, 0.75, 0.5] },
  spring: { stops: [[0, '#3f7fd0'], [0.5, '#86bdf0'], [0.85, '#d6ebf8'], [1, '#f6f2e6']], haze: '#d9e8f2', amb: '#ffffff', tint: '#fff0e0', tintAmt: 0.05, gain: 1.04, ground: ['#8fb46a', '#b3c890'], leaf: ['#f6b7cc', '#ffd9e6', '#d98aa8'], snow: null, fog: 500, sunDir: [0.45, 0.75, 0.45] },
  summer: { stops: [[0, '#2764bf'], [0.5, '#68aaea'], [0.85, '#c5e2f6'], [1, '#eef6e8']], haze: '#cfe4ee', amb: '#ffffff', tint: '#fff6d0', tintAmt: 0.06, gain: 1.05, ground: ['#6fa64a', '#9cbc78'], leaf: ['#4d9440', '#7cba58', '#33702e'], snow: null, fog: 560, sunDir: [0.3, 0.85, 0.4] },
  autumn: { stops: [[0, '#4a6aa8'], [0.5, '#a0b4d6'], [0.82, '#f0d6b8'], [1, '#ffe4b8']], haze: '#ead8c0', amb: '#fff2e0', tint: '#ffb060', tintAmt: 0.1, gain: 1.0, ground: ['#a89a5a', '#c4b488'], leaf: ['#d9642a', '#f09a3a', '#a8401f'], snow: null, fog: 420, sunDir: [-0.5, 0.6, 0.5] },
};
const TIMEMOD = { day: {}, dawn: { stops: [[0, '#2a3a6a'], [0.45, '#6a6f9f'], [0.78, '#e0a08a'], [1, '#ffd69a']], amb: '#e6d8e8', tint: '#ffb48a', tintAmt: 0.12, gain: 0.95, haze: '#d6b8b8', sunDir: [0.85, 0.3, 0.3] }, dusk: { stops: [[0, '#3b4f8f'], [0.4, '#8b7fb8'], [0.75, '#f0a590'], [1, '#ffd69a']], amb: '#ffe2cc', tint: '#ff9f6a', tintAmt: 0.14, gain: 0.95, haze: '#e0b8a8', sunDir: [-0.8, 0.3, 0.3] }, night: { stops: [[0, '#070b1f'], [0.5, '#101a3c'], [0.85, '#1f2f5a'], [1, '#2f4470']], amb: '#7c8fcc', tint: '#3a4fa0', tintAmt: 0.12, gain: 0.8, haze: '#22305a', sunDir: [0.4, 0.7, 0.5] } };
function mountainSky(sc, L, o, night) {
  const clouds = [];
  for (let i = 0; i < 12; i++) clouds.push({ kind: i % 2 ? 'band' : 'puff', az: -180 + i * 30 + hash(i * 3.3) * 14, el: 6 + hash(i * 5.1) * 18, w: 24 + hash(i * 2.2) * 30, h: (i % 2 ? 2.4 : 5) + hash(i * 6.1) * 3, seed: i + 41, color: night ? '#3a4a7a' : (L.cloud?.[0] ?? '#ffffff'), shade: night ? '#1c2648' : (L.cloud?.[1] ?? '#c3d0e6'), alpha: night ? 0.5 : 0.95, drift: 0.07 });
  sc.add('sky', sky({ stops: L.stops, sun: night ? null : { az: Math.atan2(L.sunDir[0], L.sunDir[2]) * 180 / Math.PI, el: Math.asin(L.sunDir[1] / Math.hypot(...L.sunDir)) * 180 / Math.PI, r: 1.4, color: '#fffbe8', glow: '#fff0c8', glowR: 22 }, moon: night ? { az: 160, el: 38, r: 2.3, color: '#f3f1e2', glow: '#8aa2e0', glowR: 16 } : null, stars: night ? { n: 520, amt: 1, seed: 9 } : null, clouds, band: { color: L.stops[L.stops.length - 1][1], el: 6, alpha: 0.6 } }));
  // peaks all around, and a sea of cloud below them
  const pk = night ? '#3a4a78' : '#6f86aa', mid = night ? '#2a3a5a' : '#566f8a';
  for (const yaw of [0, 90, 180, -90]) {
    sc.add('far', range({ z: 3200, yaw: yaw + 12, h: 900, base: -260, drop: 200, span: 11000, color: pk, shade: shadeHex(pk, -0.22), seed: 101 + yaw, peaks: 5, snow: { line: 0.42, color: night ? '#9aa8d0' : '#f6f8fc', shade: night ? '#6a78a8' : '#b9c8e4' }, mist: { color: L.haze, h: 0.55, alpha: 0.9 } }));
    sc.add('far', range({ z: 1300, yaw: yaw - 16, h: 420, base: -200, drop: 160, span: 4600, color: mid, shade: shadeHex(mid, -0.24), seed: 113 + yaw, peaks: 6, snow: { line: o.season === 'winter' ? 0.3 : 0.62, color: night ? '#9aa8d0' : '#f6f8fc', shade: night ? '#6a78a8' : '#b9c8e4' }, mist: { color: L.haze, h: 0.6, alpha: 0.95 } }));
  }
}
const stoneLantern = (x, y, z, lit) => new Mesh([...box([x, y, z], 0.5, 0.25, 0.5, 0, { side: '#8a8884' }), ...box([x, y + 0.25, z], 0.24, 0.9, 0.24, 0, { side: '#96938e' }), ...box([x, y + 1.15, z], 0.62, 0.12, 0.62, 0, { side: '#8a8884' }), ...box([x, y + 1.27, z], 0.4, 0.36, 0.4, 0, { side: '#7a7874' }, { side: DET.marks([['glow', 0.22, 0.18, 0.78, 0.82, lit ? '#ffd08a' : '#4a4644']]) }), ...gable([x, y + 1.63, z], 0.5, 0.5, 0, { rise: 0.3, over: 0.2, color: '#6a6864', hip: true })], { p: [x, y, z] });
/** A gate of two red posts and two beams (the kind that marks holy ground). */
const torii = (x, y, z, w = 4.4, h = 4.6, yaw = 0, col = '#b5362c') => new Mesh([...box([x - w / 2, y, z], 0.34, h, 0.34, yaw, { side: col }), ...box([x + w / 2, y, z], 0.34, h, 0.34, yaw, { side: col }), ...box([x, y + h * 0.78, z], w + 0.6, 0.26, 0.3, yaw, { side: col }), ...box([x, y + h, z], w + 1.5, 0.32, 0.46, yaw, { side: '#3a3234', top: '#4a4244' })], { p: [x, y, z] });

export function steps(o = {}) {
  const S = SEASON[o.season ?? 'winter'], Tm = TIMEMOD[o.time ?? 'day'], L = { ...S, ...Tm }, night = o.time === 'night';
  const wind = makeWind({ dir: [0.8, 0, 0.5], speed: 2.4, gust: 1.4, seed: 11 });
  const sc = new Scene({ grade: { amb: L.amb, tint: L.tint, tintAmt: L.tintAmt, gain: L.gain }, fog: { color: L.haze, dist: o.fog ?? Math.max(L.fog, 240), max: 0.94, start: 6 }, sun: L.sunDir, wind, rim: null, dust: null, shadowAlpha: 0.2, shadowColor: '#3a4a6a', post: { vignette: 0.34, grain: 0.05 } });
  sc.look = L;
  mountainSky(sc, L, o, night);
  // the slope itself: a great snowfield tilted like the stair, pines and rocks standing on it
  const up = 150, hw = STEP.w / 2;
  const slopeY = (z) => (z > 0 ? 0 : -z * STEP.rise);
  sc.add('ground', { draw(g, cam, s2) { // the mountainside as wide bands of snow climbing away
    const bands = 26; for (let i = bands - 1; i >= 0; i--) { const z0 = 30 - i * 9, z1 = z0 - 9.2, pts = clipPolyL(cam, [[-220, slopeY(z0) - 0.1, z0], [220, slopeY(z0) - 0.1, z0], [220, slopeY(z1) - 0.1, z1], [-220, slopeY(z1) - 0.1, z1]]); if (pts.length < 3) continue; const d = V.dist(cam.pos, [0, slopeY(z0), z0]); g.beginPath(); g.moveTo(pts[0][0], pts[0][1]); for (let k = 1; k < pts.length; k++) g.lineTo(pts[k][0], pts[k][1]); g.closePath(); g.fillStyle = s2.col(i % 2 ? L.ground[0] : shadeHex(L.ground[0], -0.035), d); g.fill(); } } });
  // the stair: treads and risers, drawn near the lens step by step, far away as one ramp
  sc.add('world', { p: [0, 0, 0], bias: 2600, r: 1e6, draw(g, cam, s2) {
    const zc = clamp(cam.pos[2], -up, 20), n0 = Math.max(0, Math.floor((-zc - 46) / STEP.tread)), n1 = Math.min(Math.floor(up / STEP.tread), Math.ceil((-zc + 60) / STEP.tread));
    const order = []; for (let i = n0; i <= n1; i++) order.push(i);
    order.sort((a, b) => Math.abs(b * STEP.tread + cam.pos[2]) - Math.abs(a * STEP.tread + cam.pos[2]));
    const tread = L.snow ? '#e9eef6' : '#a8a4a0', riser = L.snow ? '#b9c2d2' : '#85817d';
    for (const i of order) {
      const z = -i * STEP.tread, y = i * 0.21, d = V.dist(cam.pos, [0, y, z]);
      const r = clipPolyL(cam, [[-hw, y - 0.21, z + 0.001], [hw, y - 0.21, z + 0.001], [hw, y, z + 0.001], [-hw, y, z + 0.001]]), t = clipPolyL(cam, [[-hw, y, z], [hw, y, z], [hw, y, z - STEP.tread], [-hw, y, z - STEP.tread]]);
      if (cam.pos[2] > z && r.length > 2) { g.beginPath(); g.moveTo(r[0][0], r[0][1]); for (let k = 1; k < r.length; k++) g.lineTo(r[k][0], r[k][1]); g.closePath(); g.fillStyle = s2.col(riser, d); g.fill(); }
      if (cam.pos[1] > y && t.length > 2) { g.beginPath(); g.moveTo(t[0][0], t[0][1]); for (let k = 1; k < t.length; k++) g.lineTo(t[k][0], t[k][1]); g.closePath(); g.fillStyle = s2.col(tread, d); g.fill(); g.lineWidth = 0.7; g.strokeStyle = s2.col(riser, d); g.stroke(); }
    }
  } });
  for (let s = 8; s < up; s += 17) { sc.add('world', torii(0, STEP.h(-s), -s, 4.6, 4.8)); sc.add('world', stoneLantern(-hw - 0.7, slopeY(-s - 6) - 0.1, -s - 6, true)); sc.add('world', stoneLantern(hw + 0.7, slopeY(-s - 6) - 0.1, -s - 6, true)); }
  for (let i = 0; i < 90; i++) { const side = i % 2 ? 1 : -1, z = 24 - hash(i * 1.9) * (up + 30), x = side * (hw + 2.5 + hash(i * 3.7) * 46); sc.add('world', tree({ p: [x, slopeY(z) - 0.2, z], h: 7 + hash(i * 5.3) * 9, kind: 'pine', seed: i + 500, leaf: '#2f5a4c', leafShade: '#1d3d36', trunk: '#4a3a34', snow: L.snow })); }
  for (let i = 0; i < 26; i++) { const side = i % 2 ? 1 : -1, z = 20 - hash(i * 2.3) * up, x = side * (hw + 0.9 + hash(i * 4.9) * 9); sc.add('world', rock({ p: [x, slopeY(z) - 0.15, z], r: 0.6 + hash(i) * 1.5, seed: i + 40, color: '#8a8d96', snow: L.snow })); }
  for (let i = 0; i < 9; i++) sc.add('world', mist({ p: [(hash(i * 3.1) - 0.5) * 60, slopeY(-10 - i * 15) + 2.5, -10 - i * 15], w: 26 + hash(i) * 20, h: 3 + hash(i * 2) * 2, color: L.haze, alpha: 0.38, drift: [0.8, 0.2], seed: i }));
  sc.slopeY = slopeY;
  return sc;
}
function clipPolyL(cam, pts) { let cs = pts.map((p) => cam.toCam(p)); const near = 0.08; if (cs.some((c) => c[2] < near)) { const out = []; for (let i = 0; i < cs.length; i++) { const a = cs[i], b = cs[(i + 1) % cs.length], ia = a[2] >= near, ib = b[2] >= near; if (ia) out.push(a); if (ia !== ib) { const u = (near - a[2]) / (b[2] - a[2]); out.push([a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, near]); } } cs = out; } if (cs.length < 3) return []; return cs.map((c) => [cam.cx + cam.f * c[0] / c[2], cam.cy - cam.f * c[1] / c[2], c[2]]); }

/** A swept temple roof: hipped, the slopes hollow, the eaves lifting at the corners, tile rows down the slope, a ridge with finials.
 *  at = the middle of the wall top the roof sits on; w × d = the walls it covers. Returns faces for a Mesh. */
export function templeRoof(at, w, d, yawDeg = 0, o = {}) {
  const yaw = yawDeg * Math.PI / 180, ov = o.over ?? 1.6, hw = w / 2 + ov, hd = d / 2 + ov, rise = o.rise ?? d * 0.5, sag = o.sag ?? 1.1, rl = o.ridge ?? Math.max(0.3, hw - hd), th = o.thick ?? 0.3;
  const cy = Math.cos(yaw), sy = Math.sin(yaw), P = (x, y, z) => [at[0] + x * cy + z * sy, at[1] + y, at[2] - x * sy + z * cy];
  const N = o.n ?? 10, M = o.m ?? 4, curve = (v) => Math.pow(v, o.hollow ?? 1.5), lift = (u) => sag * Math.pow(Math.abs(u), 2.6), ts = o.tile ?? 0.44;
  const color = o.color ?? '#3f4656', edge = o.edge ?? shadeHex(color, -0.3), under = o.under ?? '#4a3028', ridgeC = o.ridgeColor ?? shadeHex(color, -0.18);
  const SIDES = [{ A: hw, R: rl, B: hd, off: 0, map: (a, b) => [a, b] }, { A: hw, R: rl, B: hd, off: 0, map: (a, b) => [-a, -b] }, { A: hd, R: 0, B: hw - rl, off: rl, map: (a, b) => [rl + b, -a] }, { A: hd, R: 0, B: hw - rl, off: rl, map: (a, b) => [-(rl + b), a] }];
  const pt = (S, u, v, dy = 0) => { const q = S.map(lerp(u * S.A, u * S.R, v), lerp(S.B, 0, v)); return P(q[0], rise * curve(v) + lift(u) * (1 - v) * (1 - v) + dy, q[1]); };
  const f = [];
  for (const S of SIDES) {
    const n = Math.max(3, Math.round(N * S.A / hw)), wallA = S === SIDES[0] || S === SIDES[1] ? w / 2 : d / 2, wallB = S === SIDES[0] || S === SIDES[1] ? d / 2 : w / 2 - rl;
    for (let i = 0; i < n; i++) {
      const u0 = -1 + (2 * i) / n, u1 = -1 + (2 * (i + 1)) / n;
      for (let j = 0; j < M; j++) {
        const v0 = j / M, v1 = (j + 1) / M, top = S.R === 0 && j === M - 1;
        const pts = top ? [pt(S, u0, v0), pt(S, u1, v0), pt(S, 0, 1)] : [pt(S, u0, v0), pt(S, u1, v0), pt(S, u1, v1), pt(S, u0, v1)];
        const a0 = Math.min(lerp(u0 * S.A, u0 * S.R, v0), lerp(u0 * S.A, u0 * S.R, v1)), a1 = Math.max(lerp(u1 * S.A, u1 * S.R, v0), lerp(u1 * S.A, u1 * S.R, v1));
        f.push({ p: pts, c: color, ink: false, tag: 'roof', lod: 5, detail: (g, F) => { // tile rows running down the slope, a course line across
          if (F.px * ts < 2.6) return; g.beginPath();
          for (let k = Math.ceil(a0 / ts); k * ts <= a1; k++) { const a = k * ts, w0 = lerp(S.A, S.R, v0), w1 = lerp(S.A, S.R, v1), q0 = F.proj(pt(S, clamp(a / w0, -1.2, 1.2), v0, 0.01)), q1 = F.proj(pt(S, w1 > 0.01 ? clamp(a / w1, -1.2, 1.2) : 0, v1, 0.01)); g.moveTo(q0[0], q0[1]); g.lineTo(q1[0], q1[1]); }
          g.lineWidth = Math.max(0.6, F.px * 0.022); g.strokeStyle = F.col(shadeHex(color, -0.2)); g.stroke();
        } });
      }
      // the thick edge of the eave, and the boards under it
      const e0 = pt(S, u0, 0), e1 = pt(S, u1, 0), l0 = pt(S, u0, 0, -th), l1 = pt(S, u1, 0, -th);
      f.push({ p: [l0, l1, e1, e0], c: edge, ink: false, tag: 'edge', k: -0.05, lod: 3, detail: (g, F) => { const a = F.proj(l0), b = F.proj(l1), c2 = F.proj(e0), d2 = F.proj(e1); g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.lineWidth = Math.max(0.8, F.lw * 1.5); g.strokeStyle = F.col(shadeHex(edge, -0.5)); g.stroke(); g.beginPath(); g.moveTo(c2[0], c2[1]); g.lineTo(d2[0], d2[1]); g.lineWidth = Math.max(0.6, F.lw); g.strokeStyle = F.col(o.trim ?? '#d9d2c4'); g.stroke(); } });
      const wq = (u) => { const q = S.map(u * wallA, wallB); return P(q[0], -0.02, q[1]); };
      f.push({ p: [wq(u0), wq(u1), l1, l0], c: under, ink: false, tag: 'under', k: 0.3, lod: 7, detail: (g, F) => { g.beginPath(); for (let k = 0; k < 3; k++) { const uu = (k + 0.5) / 3, a = F.uv(uu, 0), b = F.uv(uu, 1); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); } g.lineWidth = Math.max(0.8, F.px * 0.07); g.strokeStyle = F.col(shadeHex(under, -0.25)); g.stroke(); } });
    }
  }
  // the ridge, its finials, and the four hip ribs
  f.push(...box(P(0, rise - 0.05, 0), rl * 2 + 0.5, 0.4, 0.46, yawDeg, { side: ridgeC, top: shadeHex(ridgeC, 0.1) }));
  for (const sx of [-1, 1]) f.push(...box(P(sx * (rl + 0.2), rise + 0.3, 0), 0.34, 0.62, 0.3, yawDeg, { side: o.finial ?? '#c9a24a' }));
  for (const [S, u] of [[SIDES[0], 1], [SIDES[0], -1], [SIDES[1], 1], [SIDES[1], -1]]) for (let j = 0; j < M; j++) { const a = pt(S, u, j / M, 0.02), b = pt(S, u, (j + 1) / M, 0.02); f.push({ p: [a, b, [b[0], b[1] + 0.22, b[2]], [a[0], a[1] + 0.22, a[2]]], c: ridgeC, two: true, flat: true, ink: false, k: -0.2 }); }
  return f;
}
/** A hall: stone base, wooden veranda, red pillars, white walls with paper screens, a swept roof (two tiers when o.upper). */
function hallFaces(x, z, o = {}) {
  const w = o.w ?? 18, d = o.d ?? 10, yaw = o.yaw ?? 0, base = o.base ?? 1.0, wallH = o.wallH ?? 3.8, lit = o.lit, roof = o.roof ?? '#3f4656', col = o.col ?? '#a8382c', wall = o.wall ?? '#e9e2d0';
  const cy = Math.cos(yaw * Math.PI / 180), sy = Math.sin(yaw * Math.PI / 180), Pl = (lx, y, lz) => [x + lx * cy + lz * sy, y, z - lx * sy + lz * cy];
  const paper = lit ? '#ffdca0' : '#f4efe0', door = lit ? '#ffcf8a' : '#3a2e28', frame = '#5a4030';
  const screens = (n) => { const marks = []; for (let i = 0; i < n; i++) { const u0 = 0.03 + i * (0.94 / n), u1 = u0 + 0.94 / n - 0.02, mid = Math.abs(i - (n - 1) / 2) < 0.6; marks.push([lit ? 'glow' : 'rect', u0, 0.04, u1, 0.86, mid ? door : paper, frame]); } return DET.all(DET.marks(marks), (g, F) => { g.beginPath(); for (let i = 0; i < n; i++) { if (Math.abs(i - (n - 1) / 2) < 0.6) continue; const u0 = 0.03 + i * (0.94 / n), u1 = u0 + 0.94 / n - 0.02; for (let k = 1; k < 4; k++) { const u = lerp(u0, u1, k / 4), a = F.uv(u, 0.04), b = F.uv(u, 0.86); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); } for (let k = 1; k < 5; k++) { const v = lerp(0.04, 0.86, k / 5), a = F.uv(u0, v), b = F.uv(u1, v); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); } } g.lineWidth = Math.max(0.7, F.px * 0.028); g.strokeStyle = F.col('#6a5040'); g.stroke(); }); };
  const f = [
    ...box(Pl(0, 0, 0), w + 5, base, d + 5, yaw, { side: '#8f8a82', top: '#7a5c40' }, { side: DET.stone(0.34, 1.1), top: DET.planks(0.45, 'v') }),
    ...box(Pl(0, base, 0), w, wallH, d, yaw, { side: wall, top: null }, { front: screens(o.bays ?? 7), back: screens(o.bays ?? 7), left: screens(3), right: screens(3) }),
    ...box(Pl(0, base + wallH, 0), w + 0.5, 0.55, d + 0.5, yaw, { side: '#5a3a30', top: null }),
  ];
  // pillars round the veranda, a rail between them (open at the middle of the front)
  const nx = o.pillars ?? 6, px = w / 2 + 1.5, pz = d / 2 + 1.5;
  for (let i = 0; i < nx; i++) for (const sz of [-1, 1]) f.push(...box(Pl(-px + i * (2 * px / (nx - 1)), base, sz * pz), 0.46, wallH + 0.5, 0.46, yaw, { side: col }));
  for (const sx of [-1, 1]) for (let i = 1; i < 3; i++) f.push(...box(Pl(sx * px, base, -pz + i * (2 * pz / 3)), 0.46, wallH + 0.5, 0.46, yaw, { side: col }));
  const rail = (a, b) => { const mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2, len = Math.hypot(b[0] - a[0], b[1] - a[1]), ry = Math.atan2(-(b[1] - a[1]), b[0] - a[0]) * 180 / Math.PI; f.push(...box(Pl(mx, base + 0.78, mz), len, 0.1, 0.1, yaw + ry, { side: '#7a3028' }), ...box(Pl(mx, base + 0.4, mz), len, 0.07, 0.07, yaw + ry, { side: '#7a3028' })); };
  const gapw = o.stairW ?? 5.4;
  rail([-px, pz], [-gapw / 2, pz]); rail([gapw / 2, pz], [px, pz]); rail([-px, -pz], [px, -pz]); rail([-px, -pz], [-px, pz]); rail([px, -pz], [px, pz]);
  // the stair up to the veranda
  const ns = Math.max(3, Math.round(base / 0.2));
  for (let i = 0; i < ns; i++) f.push(...box(Pl(0, 0, d / 2 + 2.5 + 0.17 + (ns - 1 - i) * 0.34), gapw, (i + 1) * (base / ns), 0.34, yaw, { side: '#9a958c', top: '#aaa59c' }));
  f.push(...templeRoof(Pl(0, base + wallH + 0.55, 0), w + 2.4, d + 2.4, yaw, { rise: o.rise ?? 3.4, over: 1.9, sag: 1.2, color: roof, n: o.n ?? 12, m: 4, under: '#4a3028' }));
  if (o.upper) { const uw = w * 0.52, ud = d * 0.5, uy = base + wallH + 0.55 + (o.rise ?? 3.4) * 0.5; f.push(...box(Pl(0, uy, 0), uw, 2.5, ud, yaw, { side: wall, top: null }, { side: DET.marks([['rect', 0.08, 0.2, 0.92, 0.8, lit ? '#ffdca0' : '#f1ecdc', frame]]) }), ...templeRoof(Pl(0, uy + 2.5, 0), uw + 1, ud + 1, yaw, { rise: 2.9, over: 1.5, sag: 0.9, color: roof, n: 8, m: 3, under: '#4a3028' })); }
  return f;
}
/** A tower of five roofs, each smaller than the one below, a gold spire on top. */
function pagodaFaces(x, z, o = {}) {
  const f = [], roof = o.roof ?? '#3f4656', lit = o.lit; let y = 0, w = o.w ?? 6.4;
  f.push(...box([x, 0, z], w + 2, 0.8, w + 2, 0, { side: '#8f8a82', top: '#99948c' })); y = 0.8;
  for (let i = 0; i < 5; i++) { const hh = i === 0 ? 3.2 : 2.3; f.push(...box([x, y, z], w, hh, w, 0, { side: '#a8382c', top: null }, { side: DET.marks([['rect', 0.32, 0.12, 0.68, 0.86, lit ? '#ffcf8a' : '#3a2e28', '#5a3028'], ['rect', 0.06, 0.3, 0.26, 0.8, '#e9e2d0'], ['rect', 0.74, 0.3, 0.94, 0.8, '#e9e2d0']]) })); y += hh; f.push(...templeRoof([x, y, z], w + 0.6, w + 0.6, 0, { rise: i === 4 ? 2.4 : 1.25, over: 1.5, sag: 0.8, color: roof, n: 5, m: 3, ridge: 0.05, under: '#4a3028', tile: 0.5 })); y += i === 4 ? 2.4 : 1.0; w *= 0.84; }
  f.push(...box([x, y - 0.1, z], 0.22, 3.4, 0.22, 0, { side: '#c9a24a' })); for (let k = 0; k < 4; k++) f.push(...box([x, y + 0.5 + k * 0.6, z], 0.8 - k * 0.14, 0.12, 0.8 - k * 0.14, 0, { side: '#c9a24a' }));
  return f;
}

export const YARD = { hall: [0, -19], gate: [0, 19], tree: [-8, 4], board: [4.2, -13.75], posts: [[7.5, 3], [10, 0.5], [8.5, -2.5]], pagoda: [-27, -22], hw: 13, floor: 1.0 };
export function courtyard(o = {}) {
  const season = o.season ?? 'spring', S = SEASON[season], Tm = TIMEMOD[o.time ?? 'day'], L = { ...S, ...Tm }, night = o.time === 'night', glowing = night || o.time === 'dusk' || o.time === 'dawn';
  if (o.rain) Object.assign(L, { stops: [[0, '#56626f'], [0.5, '#8793a1'], [1, '#b6bec6']], amb: '#c6ced8', tint: '#8fa0b8', tintAmt: 0.1, gain: 0.9, haze: '#a9b3bf', fog: 160, cloud: ['#aab2bc', '#7d8792'] });
  const wind = makeWind({ dir: [0.9, 0, 0.3], speed: season === 'winter' ? 1.8 : 1.1, gust: 0.9, seed: 13 });
  const sc = new Scene({ grade: { amb: L.amb, tint: L.tint, tintAmt: L.tintAmt, gain: L.gain }, fog: { color: L.haze, dist: Math.min(L.fog, o.fog ?? 1e9), max: 0.92, start: 14 }, sun: L.sunDir, wind, rim: o.time === 'dusk' || o.time === 'dawn' ? '#ffc890' : null, dust: season === 'winter' ? null : '#cdbfa6', shadowAlpha: night ? 0.2 : 0.28, shadowColor: night ? '#05060f' : '#2a2a3a', post: { vignette: night ? 0.48 : 0.3, grain: 0.045 } });
  sc.look = L; sc.season = season;
  mountainSky(sc, L, o, night);
  const HW = YARD.hw, roof = night ? '#2a2f3c' : season === 'winter' ? '#566074' : '#3f4656';
  // the yard: flagstones (snow in winter), moss and grass beyond, the mountain falling away into mist
  const flag = L.snow ? '#e6ebf4' : '#b8b2a6';
  sc.add('ground', ground({ y: 0, near: L.ground[0], far: L.ground[1], farAt: 90, patch: { colors: L.snow ? ['#e2e8f2', '#f4f6fb', '#d9e0ec'] : [shadeHex(L.ground[0], -0.08), shadeHex(L.ground[0], 0.08)], cell: 3.2, max: 70, amt: 0.5, alpha: 0.6 }, tuft: L.snow ? null : { colors: [shadeHex(L.ground[0], -0.15), shadeHex(L.ground[0], 0.05)], cell: 0.8, max: 15, amt: 0.5, h: [0.08, 0.22], mask: (x, z) => Math.abs(x) > HW + 0.5 || Math.abs(z) > 17 } }));
  const lines = (g, cam, s2) => { g.beginPath(); for (let x = -HW; x <= HW; x += 2) { const q = clipSeg(cam, [x, 0.01, -15.5], [x, 0.01, 17]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } for (let z = -15; z <= 17; z += 2) { const q = clipSeg(cam, [-HW, 0.01, z], [HW, 0.01, z]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } g.lineWidth = 1.2 * (cam.H / 1080); g.strokeStyle = s2.col(shadeHex(flag, -0.2), 16); g.globalAlpha = 0.45; g.stroke(); g.globalAlpha = 1; };
  sc.add('ground', slab({ pts: [[-HW, -15.5], [HW, -15.5], [HW, 17], [-HW, 17]], y: 0.006, color: flag, far: shadeHex(flag, 0.06), detail: lines }));
  // a paler path from the gate to the hall, worn by feet
  sc.add('ground', slab({ pts: [[-2.4, -15.5], [2.4, -15.5], [2.4, 17], [-2.4, 17]], y: 0.009, color: shadeHex(flag, L.snow ? -0.05 : 0.07), alpha: 0.8 }));
  const sdx = -L.sunDir[0] / Math.max(0.3, L.sunDir[1]), sdz = -L.sunDir[2] / Math.max(0.3, L.sunDir[1]), shC = night ? '#04050c' : '#2a3040';
  const blob = (cx, cz, rx, rz, seed, n = 14) => Array.from({ length: n }, (_, i) => { const a = (i / n) * Math.PI * 2, k = 0.82 + 0.3 * hash(i * 2.1 + seed); return [cx + Math.cos(a) * rx * k, cz + Math.sin(a) * rz * k]; });
  if (!night) { sc.add('ground', slab({ pts: blob(YARD.tree[0] + sdx * 5, YARD.tree[1] + sdz * 5, 6.5, 5.5, 3), y: 0.012, raw: shC, color: shC, alpha: 0.2 })); sc.add('ground', slab({ pts: [[-12, -12.5], [12, -12.5], [12 + sdx * 6, -12.5 + Math.max(0.5, sdz * 6)], [-12 + sdx * 6, -12.5 + Math.max(0.5, sdz * 6)]], y: 0.011, raw: shC, color: shC, alpha: 0.18 })); }
  // ---- the buildings
  const [hx, hz] = YARD.hall;
  sc.add('world', new Mesh(hallFaces(hx, hz, { w: 18, d: 9, lit: glowing, roof, upper: true, base: YARD.floor }), { p: [hx, 0, hz + 3], bias: 6 })); // drawn before whatever stands on its veranda
  for (const sx of [-1, 1]) sc.add('world', new Mesh(hallFaces(sx * 20.5, -3, { w: 10, d: 5.4, yaw: sx > 0 ? -90 : 90, lit: glowing, roof, base: 0.6, wallH: 3, bays: 5, pillars: 4, rise: 2.4, n: 8, stairW: 3 }), { p: [sx * 19, 0, -3] }));
  const [qx, qz] = YARD.pagoda; sc.add('world', new Mesh(pagodaFaces(qx, qz, { roof, lit: glowing }), { p: [qx, 0, qz] }));
  // the gate at the head of the stair, a roofed wall either side of it
  const [gx, gz] = YARD.gate, col = '#a8382c';
  sc.add('world', new Mesh([...box([gx - 2.9, 0, gz], 0.56, 4.8, 0.56, 0, { side: col }), ...box([gx + 2.9, 0, gz], 0.56, 4.8, 0.56, 0, { side: col }), ...box([gx - 2.9, 0, gz - 1.6], 0.4, 4.8, 0.4, 0, { side: col }), ...box([gx + 2.9, 0, gz - 1.6], 0.4, 4.8, 0.4, 0, { side: col }), ...box([gx, 4.8, gz - 0.8], 7, 0.5, 2.4, 0, { side: '#5a3a30' }), ...templeRoof([gx, 5.3, gz - 0.8], 7.4, 2.8, 0, { rise: 1.9, over: 1.3, sag: 0.9, color: roof, n: 8, m: 3 })], { p: [gx, 0, gz] }));
  const wallSeg = (x0, z0, x1, z1) => { const mx = (x0 + x1) / 2, mz = (z0 + z1) / 2, len = Math.hypot(x1 - x0, z1 - z0), yw = Math.atan2(-(z1 - z0), x1 - x0) * 180 / Math.PI; sc.add('world', new Mesh([...box([mx, 0, mz], len, 0.5, 0.7, yw, { side: '#8f8a82' }), ...box([mx, 0.5, mz], len, 1.6, 0.5, yw, { side: '#e4dccb', top: null }), ...gable([mx, 2.1, mz], len, 0.9, yw, { rise: 0.5, over: 0.28, color: roof })], { p: [mx, 0, mz] })); };
  for (const sx of [-1, 1]) { for (let i = 0; i < 4; i++) wallSeg(sx * (4.2 + i * 6), gz, sx * (4.2 + (i + 1) * 6), gz); for (let i = 0; i < 3; i++) wallSeg(sx * 28.2, gz - i * 7, sx * 28.2, gz - (i + 1) * 7); }
  // the great tree: blossom in spring, green in summer, red in autumn, bare in winter
  const [tx, tz] = YARD.tree;
  sc.add('world', tree({ p: [tx, 0, tz], h: 11, kind: L.leaf ? 'sakura' : 'bare', seed: 8, n: 16, spread: 1.4, thick: 1.7, leaf: L.leaf?.[0], leafLit: L.leaf?.[1], leafShade: L.leaf?.[2], trunk: '#4a3830' }));
  sc.add('world', new Mesh([...box([tx, 0, tz], 4.2, 0.45, 4.2, 22, { side: '#8f8a82', top: L.snow ? '#eef2f8' : '#7d9a5c' }, { side: DET.stone(0.22, 0.8) })], { p: [tx, 0, tz], bias: 40 }));
  if (L.leaf) for (let i = 0; i < 5; i++) { const a = hash(i * 3.3) * 6.28, r = 20 + hash(i * 7.1) * 9; const x = Math.cos(a) * r * 1.25, z = Math.sin(a) * r; if (Math.abs(x) < HW + 2 && Math.abs(z) < 19) continue; sc.add('world', tree({ p: [x, 0, z], h: 6 + hash(i) * 3, kind: 'sakura', seed: 30 + i, n: 9, leaf: L.leaf[0], leafLit: L.leaf[1], leafShade: L.leaf[2], trunk: '#4a3830' })); }
  // training posts wrapped in rope, a rack of wooden swords, lanterns, a bell, the Go board on the veranda
  for (const [px, pz] of YARD.posts) sc.add('world', new Mesh([...box([px, 0, pz], 0.3, 1.9, 0.3, 15, { side: '#8a6a48' }, { side: DET.marks([['rect', 0, 0.45, 1, 0.75, '#d9c9a0', '#a89870']]) }), ...box([px, 1.25, pz], 1.3, 0.12, 0.12, 15, { side: '#7a5c3e' })], { p: [px, 0, pz] }));
  sc.add('world', new Mesh([...box([11.6, 0, 6], 0.12, 1.3, 0.12, 0, { side: '#6a4a30' }), ...box([11.6, 0, 8.4], 0.12, 1.3, 0.12, 0, { side: '#6a4a30' }), ...box([11.6, 1.2, 7.2], 0.1, 0.1, 2.6, 0, { side: '#6a4a30' }), ...box([11.6, 0.6, 7.2], 0.1, 0.1, 2.6, 0, { side: '#6a4a30' }), ...[0, 1, 2, 3, 4].flatMap((k) => box([11.5, 0.2, 6.3 + k * 0.45], 0.06, 1.35, 0.06, 0, { side: '#c9a878' }))], { p: [11.6, 0, 7.2] }));
  for (const [lx, lz] of [[-4.2, 13], [4.2, 13], [-4.2, 3], [4.2, 3], [-4.2, -7], [4.2, -7], [-12, -11], [12, -11], [-12, 9], [12, 9]]) sc.add('world', stoneLantern(lx, 0, lz, glowing));
  sc.add('world', new Mesh([...box([-12.5, 0, -8.2], 2.6, 0.4, 2.6, 0, { side: '#8f8a82' }), ...[[-1, -1], [1, -1], [-1, 1], [1, 1]].flatMap(([a, b]) => box([-12.5 + a * 1.05, 0.4, -8.2 + b * 1.05], 0.24, 2.7, 0.24, 0, { side: col })), ...box([-12.5, 1.5, -8.2], 0.8, 1.1, 0.8, 0, { side: '#6f7f6a', top: '#7f8f7a' }), ...templeRoof([-12.5, 3.1, -8.2], 2.6, 2.6, 0, { rise: 1.2, over: 0.8, sag: 0.5, color: roof, n: 4, m: 2, ridge: 0.05 })], { p: [-12.5, 0, -8.2] }));
  const [bx, bz] = YARD.board, fl = YARD.floor;
  sc.add('world', new Mesh([...box([bx, fl, bz], 0.9, 0.3, 0.9, 0, { side: '#b98a4a', top: '#d9b06a' }, { top: (g, F) => { g.beginPath(); for (let i = 1; i < 9; i++) { let a = F.uv(i / 9, 0.06), b = F.uv(i / 9, 0.94); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); a = F.uv(0.06, i / 9); b = F.uv(0.94, i / 9); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); } g.lineWidth = Math.max(0.5, F.px * 0.006); g.strokeStyle = F.col('#3a2a1a'); g.stroke(); for (let i = 0; i < 22; i++) { const q = F.uv((1 + Math.floor(hash(i * 3.1) * 8)) / 9, (1 + Math.floor(hash(i * 7.3) * 8)) / 9); g.beginPath(); g.arc(q[0], q[1], Math.max(1, F.px * 0.036), 0, 7); g.fillStyle = F.col(i % 2 ? '#1a1a1e' : '#f4f2ec'); g.fill(); } } })], { p: [bx, fl, bz], bias: 0.3 }));
  // pines and rocks round the yard; banks of mist where the mountain drops away
  for (let i = 0; i < 46; i++) { const a = hash(i * 1.7) * 6.28, r = 33 + hash(i * 3.3) * 44, x = Math.cos(a) * r * 1.15, z = Math.sin(a) * r; if (Math.abs(x) < 9 && z > 14) continue; if (Math.hypot(x - qx, z - qz) < 7) continue; sc.add('world', tree({ p: [x, 0, z], h: 8 + hash(i * 5.1) * 9, kind: 'pine', seed: i + 700, leaf: night ? '#1f3a34' : '#2f5f4a', leafShade: night ? '#132622' : '#1f4236', trunk: '#4a3a34', snow: L.snow })); }
  for (let i = 0; i < 14; i++) { const a = hash(i * 2.7) * 6.28, r = 30 + hash(i * 4.3) * 12; sc.add('world', rock({ p: [Math.cos(a) * r * 1.2, 0, Math.sin(a) * r], r: 0.7 + hash(i) * 1.3, seed: i + 90, color: '#8a8d96', snow: L.snow })); }
  for (let i = 0; i < 12; i++) { const a = (i / 12) * 6.28 + 0.2, r = 75 + hash(i * 3.9) * 30; sc.add('world', mist({ p: [Math.cos(a) * r, 2.5 + hash(i) * 3, Math.sin(a) * r], w: 60 + hash(i * 2.2) * 40, h: 7 + hash(i * 1.3) * 4, color: L.haze, alpha: 0.5, drift: [0.5, 0.1], seed: i + 3 })); }
  return sc;
}

/** People on the mountain stair: the performance is walked on flat ground; this lifts every joint to the slope of the
 *  stair at its own place (so the leading foot is the higher one). Use as an actor's `fit`; `stairGround` as its `ground`. */
export function onStair(pose) { const up = (p) => [p[0], p[1] + (p[2] < 0 ? -p[2] * STEP.rise : 0), p[2]]; for (const n in pose.j) pose.j[n] = up(pose.j[n]); for (const n in pose.props) pose.props[n].pos = up(pose.props[n].pos); }
export const stairGround = (pose) => { const z = pose.j.root[2]; return z < 0 ? -z * STEP.rise : 0; };

/* ------------------------------------------------------------------------------------------------ the border town
 * A walled town on the road to the great bridge: one street along X (z −4…4) between two rows of houses, market stalls,
 * the east gate at x = 31 with Valdhar's banners. o.time: 'day' (grey afternoon) | 'dusk'. One house smoulders.
 */
export const TOWN = { north: [-30, -21.5, -13, -4.5, 4, 12.5, 21], south: [-26, -17, -8.5, 0.5, 9, 17.5, 25.5], gate: 31, stalls: [[-7, -3.9], [2.6, 3.9], [-13, 3.9], [9.5, -3.9]] };
export function town(o = {}) {
  const dusk = o.time === 'dusk';
  const L = dusk ? { stops: [[0, '#3b4f8f'], [0.4, '#8b7fb8'], [0.75, '#f0a590'], [1, '#ffd69a']], haze: '#dcb8a6', amb: '#ffe2cc', tint: '#ff9f6a', tintAmt: 0.13, gain: 0.96, sunDir: [0.8, 0.3, -0.3] }
    : { stops: [[0, '#6f8098'], [0.5, '#a9b4c2'], [0.82, '#dcd6c8'], [1, '#efe6d2']], haze: '#d6cfc2', amb: '#f4ecdc', tint: '#ffe0b0', tintAmt: 0.06, gain: 1.0, sunDir: [0.5, 0.7, 0.4] };
  const wind = makeWind({ dir: [0.9, 0, 0.2], speed: 1.4, gust: 0.9, seed: 21 });
  const sc = new Scene({ grade: { amb: L.amb, tint: L.tint, tintAmt: L.tintAmt, gain: L.gain }, fog: { color: L.haze, dist: 260, max: 0.9, start: 12 }, sun: L.sunDir, wind, rim: dusk ? '#ffc890' : null, dust: '#cbbd9f', shadowAlpha: 0.26, shadowColor: '#2a2630', post: { vignette: 0.32, grain: 0.045 } });
  sc.look = L; sc.sunAt = dirOf(Math.atan2(L.sunDir[0], L.sunDir[2]) * 180 / Math.PI, dusk ? 6 : 40);
  const clouds = []; for (let i = 0; i < 14; i++) clouds.push({ kind: 'band', az: -180 + i * 26 + hash(i * 3.3) * 12, el: 8 + hash(i * 5.1) * 26, w: 36 + hash(i * 2.2) * 30, h: 3.4 + hash(i * 6.1) * 3, seed: i + 61, color: dusk ? '#ffd0b0' : '#eef0f2', shade: dusk ? '#9a7fa6' : '#b4bcc8', alpha: 0.9, drift: 0.05 });
  sc.add('sky', sky({ stops: L.stops, sun: { az: Math.atan2(L.sunDir[0], L.sunDir[2]) * 180 / Math.PI, el: dusk ? 6 : 40, r: 1.4, color: '#fff6dc', glow: dusk ? '#ff9a5a' : '#fff0c8', glowR: 24 }, clouds, band: { color: L.stops[L.stops.length - 1][1], el: 7, alpha: 0.6 } }));
  for (const [yaw, hh, seed] of [[0, 520, 301], [90, 700, 302], [180, 480, 303], [-90, 560, 304]]) sc.add('far', range({ z: 2400, yaw, h: hh, base: -60, drop: 60, span: 9000, color: dusk ? '#7a6a8a' : '#8494a8', shade: dusk ? '#5a4a6a' : '#66768c', seed, peaks: 5, snow: { line: 0.7, color: '#eef2f6', shade: '#c4cede' }, mist: { color: L.haze, h: 0.5, alpha: 0.9 } }));
  // ---- ground: grass beyond, earth in the yards, a cobbled street
  sc.add('ground', ground({ y: 0, near: dusk ? '#8a8a58' : '#86a060', far: dusk ? '#b8a880' : '#a9b88a', farAt: 220, patch: { colors: ['#7f9a58', '#94aa6a', '#8aa260'], cell: 3.6, max: 80, amt: 0.5, alpha: 0.5 } }));
  sc.add('ground', slab({ pts: [[-40, -13], [36, -13], [36, 13.5], [-40, 13.5]], y: 0.005, color: '#b9a888', far: '#c6b89c' }));
  const cob = '#a39a8c';
  sc.add('ground', slab({ pts: [[-44, -4.3], [44, -4.3], [44, 4.3], [-44, 4.3]], y: 0.008, color: cob, far: shadeHex(cob, 0.05), detail: (g, cam, s2) => { g.beginPath(); for (let z = -4; z <= 4; z += 1) { const q = clipSeg(cam, [-44, 0.012, z + 0.3 * hash(z)], [44, 0.012, z + 0.3 * hash(z)]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } for (let x = -44; x <= 44; x += 1.6) { const z0 = -4.3 + 8.6 * hash(x * 1.3), q = clipSeg(cam, [x, 0.012, z0], [x + 0.2, 0.012, z0 + 1.0]); if (q && V.dist(cam.pos, [x, 0, z0]) < 30) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } g.lineWidth = 1.1 * (cam.H / 1080); g.strokeStyle = s2.col(shadeHex(cob, -0.2), 14); g.globalAlpha = 0.45; g.stroke(); g.globalAlpha = 1; } }));
  // ---- houses: plaster and timber, tiled roofs; some of two floors
  const walls = ['#ddd2bc', '#d2c6ae', '#e4dac6', '#cbbfa8'], roofs = ['#8a5040', '#7a4a3e', '#96584a', '#6f4a44'];
  TOWN.north.forEach((x, i) => { const burnt = i === 2; sc.add('world', house(x, -9.2, 0, 7.4 + (i % 3) * 0.5, 6, { h: i % 2 ? 4.4 : 2.9, wall: burnt ? '#6a5a50' : walls[i % 4], roof: burnt ? '#3a2c2a' : roofs[i % 4], rise: 2.0, win: dusk && !burnt ? '#ffc878' : null })); });
  TOWN.south.forEach((x, i) => sc.add('world', house(x, 9.6, 180, 7.2 + ((i + 1) % 3) * 0.5, 6, { h: i % 2 ? 2.9 : 4.2, wall: walls[(i + 2) % 4], roof: roofs[(i + 1) % 4], rise: 2.0, win: dusk ? '#ffc878' : null })));
  // the burnt house still smoulders
  for (const [dx, hh] of [[-2, 1.5], [1.2, 2.0]]) sc.add('world', fxFire({ p: [TOWN.north[2] + dx, 3.3, -8.4], w: 1.9, h: hh, seed: 31 + dx, tongues: 4 }));
  // ---- the east gate: two towers, a beam, banners
  const gx = TOWN.gate, st = '#a09a90';
  sc.add('world', new Mesh([...box([gx, 0, -6.6], 3.4, 7.4, 3.4, 0, { side: st, top: '#8a857c' }, { side: DET.stone(0.5, 1.1) }), ...gable([gx, 7.4, -6.6], 3.4, 3.4, 0, { rise: 1.6, over: 0.5, color: '#6f4a44', hip: true, detail: DET.tiles(0.5, 0.5) })], { p: [gx, 0, -6.6] }));
  sc.add('world', new Mesh([...box([gx, 0, 6.6], 3.4, 7.4, 3.4, 0, { side: st, top: '#8a857c' }, { side: DET.stone(0.5, 1.1) }), ...gable([gx, 7.4, 6.6], 3.4, 3.4, 0, { rise: 1.6, over: 0.5, color: '#6f4a44', hip: true, detail: DET.tiles(0.5, 0.5) })], { p: [gx, 0, 6.6] }));
  sc.add('world', new Mesh([...box([gx, 5.2, 0], 2.2, 1.6, 9.8, 0, { side: st, top: '#8a857c', bottom: '#77726a' }, { side: DET.stone(0.4, 1.0) })], { p: [gx, 5.2, 0], bias: -1 }));
  for (const z of [-13.5, -20, 13.5, 20]) sc.add('world', new Mesh(box([gx, 0, z], 1.2, 4.4, 7, 0, { side: st, top: '#8a857c' }, { side: DET.stone(0.5, 1.1) }), { p: [gx, 0, z] }));
  for (const z of [-4.6, 4.6]) sc.add('world', banner({ p: [gx - 1.9, 0, z], h: 7.2, w: 1.9, len: 3.4, color: '#7c1f27', trim: '#d9b25a', seed: z, dir: [-1, 0, 0.1] }));
  // ---- market stalls, crates and sacks, a cart; lamp posts
  TOWN.stalls.forEach(([x, z], i) => {
    const sg = z < 0 ? 1 : -1, cl = [['#b5473a', '#e8dcc0'], ['#3f6f9a', '#e8dcc0'], ['#c9912e', '#e8dcc0'], ['#4f8a5a', '#e8dcc0']][i % 4], f = [];
    for (const [dx, dz] of [[-1.3, -0.7], [1.3, -0.7], [-1.3, 0.7], [1.3, 0.7]]) f.push(...box([x + dx, 0, z + dz], 0.09, 2.0 + (dz * sg > 0 ? 0 : 0.35), 0.09, 0, { side: '#5a4634' }));
    f.push({ p: [[x - 1.5, 2.0, z + 0.9 * sg], [x + 1.5, 2.0, z + 0.9 * sg], [x + 1.5, 2.4, z - 0.9 * sg], [x - 1.5, 2.4, z - 0.9 * sg]], c: cl[0], two: true, detail: (g, F) => { for (let k = 0; k < 6; k += 2) { const a = F.uv(k / 6, 0), b = F.uv((k + 1) / 6, 0), c2 = F.uv((k + 1) / 6, 1), d2 = F.uv(k / 6, 1); g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.lineTo(c2[0], c2[1]); g.lineTo(d2[0], d2[1]); g.closePath(); g.fillStyle = F.col(cl[1]); g.fill(); } } });
    f.push(...box([x, 0, z - 0.25 * sg], 2.5, 0.85, 0.9, 0, { side: '#7a5c40', top: '#8f7050' }, { side: DET.planks(0.3, 'v') }));
    for (let k = 0; k < 5; k++) f.push(...box([x - 0.95 + k * 0.48, 0.85, z - 0.25 * sg], 0.36, 0.16 + 0.1 * hash(i * 7 + k), 0.5, 0, { side: ['#c9432e', '#e0a030', '#7aa04a', '#d9c9a0', '#8a5a9a'][(k + i) % 5] }));
    sc.add('world', new Mesh(f, { p: [x, 0, z] }));
  });
  for (const [x, z, n] of [[-3.2, -3.6, 3], [6.8, 3.5, 2], [14.5, -3.4, 4], [-18, 3.6, 3], [20, 3.3, 2]]) { const f = []; for (let k = 0; k < n; k++) f.push(...box([x + (k % 2) * 0.62, Math.floor(k / 2) * 0.5, z + 0.1 * (k % 3)], 0.58, 0.5, 0.58, 12 * k, { side: '#8a6a48', top: '#9a7a58' }, { side: DET.planks(0.14, 'v') })); sc.add('world', new Mesh(f, { p: [x, 0, z] })); }
  for (const [x, z] of [[-10, -3.9], [0, 4.0], [16, 3.9], [22, -3.9], [-22, -3.9]]) sc.add('world', new Mesh([...box([x, 0, z], 0.12, 2.6, 0.12, 0, { side: '#4a3a30' }), ...box([x, 2.6, z], 0.34, 0.4, 0.34, 0, { side: dusk ? '#ffd08a' : '#d9c9a0', top: '#4a3a30' }, { side: DET.marks([[dusk ? 'glow' : 'rect', 0.1, 0.1, 0.9, 0.9, dusk ? '#ffd08a' : '#e4d6b0']]) })], { p: [x, 0, z] }));
  // trees behind the rows, the wall beyond
  for (let i = 0; i < 26; i++) { const x = -40 + hash(i * 3.7) * 76, z = (i % 2 ? 1 : -1) * (16 + hash(i * 5.1) * 16); sc.add('world', tree({ p: [x, 0, z], h: 6 + hash(i * 2.3) * 5, kind: i % 3 ? 'leaf' : 'pine', seed: i + 900, leaf: dusk ? '#7a8a4e' : '#6f9a50', leafLit: dusk ? '#a8a060' : '#8fb866', leafShade: '#4f7a3c', trunk: '#5a4634' })); }
  return sc;
}

/* ------------------------------------------------------------------------------------------------ the three courts
 * hall(o): a throne hall seen from inside. The throne stands on a dais at the origin facing +z; the hall runs along +z
 * (z 0…34), pillars either side, banners between them, fire in braziers. o.look: 'valdhar' (iron and red) | 'seiran' (jade and gold).
 * terrace(o): a sea-fortress terrace at night: Kaito's court looks out over its fleet (+z is the sea).
 */
const COURTS = {
  valdhar: { floor: '#3c3a40', floor2: '#4a474e', pillar: '#55525a', wall: '#2e2c33', banner: '#7c1f27', trim: '#d9b25a', dais: '#4a474e', throne: '#2a282e', carpet: '#6a1a22', amb: '#b9a9a0', tint: '#ff7a3a', tintAmt: 0.14, gain: 0.86, haze: '#1c1418', glow: '#ff8a3a', sky: [[0, '#0c0a10'], [1, '#1a1216']] },
  seiran: { floor: '#cfc8b6', floor2: '#ddd6c4', pillar: '#2f6a5c', wall: '#e9e2cf', banner: '#2f7a66', trim: '#e6c66a', dais: '#bfb8a6', throne: '#8a2f2a', carpet: '#2f6a5c', amb: '#fff4e0', tint: '#ffd9a0', tintAmt: 0.08, gain: 1.0, haze: '#d9d2c0', glow: '#ffd08a', sky: [[0, '#cfd9d0'], [1, '#f1ead8']] },
};
export function hall(o = {}) {
  const C = COURTS[o.look ?? 'valdhar'], dark = (o.look ?? 'valdhar') === 'valdhar';
  const wind = makeWind({ dir: [0.2, 0, 1], speed: 0.4, gust: 0.3, seed: 31 });
  const sc = new Scene({ grade: { amb: C.amb, tint: C.tint, tintAmt: C.tintAmt, gain: C.gain }, fog: { color: C.haze, dist: dark ? 60 : 110, max: 0.85, start: 6 }, sun: [0.3, 0.8, 0.5], wind, rim: dark ? '#ff8a3a' : null, dust: null, shadowAlpha: dark ? 0.4 : 0.22, shadowColor: '#0a080c', post: { vignette: dark ? 0.55 : 0.3, grain: 0.045 } });
  sc.look = C;
  sc.add('sky', sky({ stops: C.sky, clouds: [], band: { color: C.sky[1][1], el: 4, alpha: 0.3 } }));
  const W = 9, Lz = 36;
  sc.add('ground', slab({ pts: [[-W - 6, -8], [W + 6, -8], [W + 6, Lz + 4], [-W - 6, Lz + 4]], y: 0, color: C.floor, far: C.floor2, detail: (g, cam, s2) => { g.beginPath(); for (let x = -W; x <= W; x += 1.5) { const q = clipSeg(cam, [x, 0.004, -6], [x, 0.004, Lz]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } for (let z = -6; z <= Lz; z += 1.5) { const q = clipSeg(cam, [-W, 0.004, z], [W, 0.004, z]); if (q) { g.moveTo(q[0][0], q[0][1]); g.lineTo(q[1][0], q[1][1]); } } g.lineWidth = 1.1 * (cam.H / 1080); g.strokeStyle = s2.col(shadeHex(C.floor, dark ? 0.2 : -0.14), 12); g.globalAlpha = 0.5; g.stroke(); g.globalAlpha = 1; } }));
  sc.add('ground', slab({ pts: [[-1.5, 1.2], [1.5, 1.2], [1.5, Lz], [-1.5, Lz]], y: 0.006, color: C.carpet, far: shadeHex(C.carpet, 0.05) }));
  // walls: the back wall behind the throne, the long side walls
  sc.add('world', new Mesh([...box([0, 0, -7.4], 2 * W + 12, 14, 1, 0, { side: C.wall }, { front: DET.stone(1.0, 2.2) })], { p: [0, 0, -7.4], bias: 40 }));
  for (const sx of [-1, 1]) sc.add('world', new Mesh(box([sx * (W + 5.6), 0, Lz / 2 - 2], 1, 14, Lz + 12, 0, { side: C.wall }, { [sx > 0 ? 'left' : 'right']: DET.stone(1.0, 2.2) }), { p: [sx * (W + 5.6), 0, Lz / 2], bias: 30 }));
  // the dais and the throne
  const dais = []; for (let i = 0; i < 3; i++) dais.push(...box([0, i * 0.22, -0.6 - i * 0.1], 7.4 - i * 0.9, 0.22, 5.2 - i * 0.8, 0, { side: shadeHex(C.dais, -0.06 * i), top: C.dais }));
  sc.add('world', new Mesh(dais, { p: [0, 0, 1.5], bias: 8 }));
  const ty = 0.66, th = C.throne;
  sc.add('world', new Mesh([...box([0, ty, -0.75], 1.5, 0.5, 1.1, 0, { side: th, top: shadeHex(th, 0.12) }), ...box([0, ty + 0.5, -1.25], 1.5, 2.6, 0.22, 0, { side: th }, { front: DET.marks([['frame', 0.12, 0.08, 0.88, 0.94, C.trim, 1.5], ['rect', 0.42, 0.72, 0.58, 0.88, C.trim]]) }), ...box([-0.72, ty + 0.5, -0.75], 0.16, 0.42, 1.1, 0, { side: th }), ...box([0.72, ty + 0.5, -0.75], 0.16, 0.42, 1.1, 0, { side: th }), ...box([-0.66, ty + 3.1, -1.25], 0.2, 0.5, 0.22, 0, { side: C.trim }), ...box([0.66, ty + 3.1, -1.25], 0.2, 0.5, 0.22, 0, { side: C.trim })], { p: [0, 0, -0.9], bias: 4 }));
  // the great banner behind the throne, pillars and banners down the hall, fire
  sc.add('world', banner({ p: [0, 3.2, -6.7], h: 9.5, w: 5.2, len: 8.4, color: C.banner, trim: C.trim, seed: 3, dir: [0, 0, 1], pole: '#00000000' }));
  for (let i = 0; i < 6; i++) for (const sx of [-1, 1]) {
    const z = 2 + i * 5.6, x = sx * W * 0.72;
    sc.add('world', new Mesh([...box([x, 0, z], 1.2, 0.5, 1.2, 0, { side: shadeHex(C.pillar, -0.15) }), ...box([x, 0.5, z], 0.9, 12, 0.9, 0, { side: C.pillar, top: null }), ...(dark ? [] : box([x, 3.2, z], 0.96, 0.3, 0.96, 0, { side: C.trim }))], { p: [x, 0, z] }));
    if (i % 2 === 0) sc.add('world', banner({ p: [sx * (W + 4.9), 2.8, z + 2.8], h: 6.4, w: 2.4, len: 5.2, color: C.banner, trim: C.trim, seed: i * 2 + sx, dir: [-sx, 0, 0], pole: '#00000000' }));
    if (i % 2 === 1 || i === 0) { const bx = sx * W * 0.5, bz = z; sc.add('world', new Mesh([...box([bx, 0, bz], 0.16, 1.0, 0.16, 0, { side: '#2a2420' }), ...box([bx, 1.0, bz], 0.6, 0.16, 0.6, 0, { side: '#3a302a' })], { p: [bx, 0, bz] })); sc.add('world', fxFire({ p: [bx, 1.16, bz], w: 0.55, h: dark ? 0.9 : 0.5, seed: i * 3 + sx, tongues: 3 })); sc.add('ground', slab({ pts: Array.from({ length: 12 }, (_, q) => [bx + Math.cos(q / 12 * 6.283) * 2.6, bz + Math.sin(q / 12 * 6.283) * 2.6]), y: 0.008, raw: C.glow, color: C.glow, alpha: dark ? 0.12 : 0.05 })); }
  }
  return sc;
}
export function terrace(o = {}) {
  const wind = makeWind({ dir: [-0.6, 0, -0.8], speed: 2.4, gust: 1.2, seed: 41 });
  const sc = new Scene({ grade: { amb: '#8ea0d4', tint: '#3a55a8', tintAmt: 0.14, gain: 0.82 }, fog: { color: '#1c2a52', dist: 420, max: 0.9, start: 20 }, sun: [0.3, 0.6, 0.7], wind, rim: '#9fb6f0', dust: null, shadowAlpha: 0.3, shadowColor: '#04060f', post: { vignette: 0.5, grain: 0.045 } });
  sc.add('sky', sky({ stops: [[0, '#060a1e'], [0.5, '#0f1a3e'], [0.85, '#20345f'], [1, '#34507c']], moon: { az: 14, el: 26, r: 2.6, color: '#f3f1e2', glow: '#8aa2e0', glowR: 20 }, stars: { n: 520, amt: 1, seed: 5 }, clouds: Array.from({ length: 9 }, (_, i) => ({ kind: 'band', az: -160 + i * 40, el: 9 + hash(i * 3.1) * 12, w: 40 + hash(i) * 30, h: 3 + hash(i * 2) * 2, seed: i + 70, color: '#3a4a7a', shade: '#1c2648', alpha: 0.6, drift: 0.06 })), band: { color: '#34507c', el: 6, alpha: 0.6 } }));
  sc.add('ground', ground({ y: -9, near: '#14244a', far: '#2a4270', farAt: 600, water: { color: '#cfe0ff', alpha: 0.5, cell: 8, max: 320 } }));
  // the terrace: flagstones, a parapet with merlons, braziers with blue-white fire, banners
  const st = '#6b7186';
  sc.add('world', new Mesh(box([0, -9, -6], 44, 9, 28, 0, { side: '#4a5066', top: st }, { top: DET.stone(1.4, 1.4) }), { p: [0, -9, -6], bias: 3000 }));
  const par = []; for (let x = -21; x <= 21; x += 2) { par.push(...box([x, 0, 7.6], 2, 0.9, 0.7, 0, { side: '#5a6078', top: st })); if (Math.round(x / 2) % 2 === 0) par.push(...box([x, 0.9, 7.6], 1.1, 0.7, 0.7, 0, { side: '#5a6078', top: st })); }
  sc.add('world', new Mesh(par, { p: [0, 0, 7.6] }));
  for (const x of [-9, 9]) { sc.add('world', new Mesh([...box([x, 0, 5.6], 0.2, 1.1, 0.2, 0, { side: '#2a2c3a' }), ...box([x, 1.1, 5.6], 0.7, 0.18, 0.7, 0, { side: '#3a3e52' })], { p: [x, 0, 5.6] })); sc.add('world', fxFire({ p: [x, 1.28, 5.6], w: 0.6, h: 0.9, seed: x, tongues: 3 })); }
  for (const x of [-14, 14]) sc.add('world', banner({ p: [x, 0, 6.6], h: 8, w: 2.2, len: 4.2, color: '#1f3f7a', trim: '#cfd8ea', seed: x, dir: [-0.6, 0, -0.8] }));
  // the fleet at anchor: hulls, masts, lanterns
  for (let i = 0; i < 22; i++) { const x = -150 + hash(i * 3.3) * 300, z = 40 + hash(i * 5.7) * 220, L = 16 + hash(i * 2.1) * 12, hull = '#1a2038'; const f = [...box([x, -9, z], L, 3.2, 4.6, 12 * (hash(i) - 0.5), { side: hull, top: '#2a3250' }), ...box([x - L * 0.2, -5.8, z], 0.35, 12 + hash(i * 7) * 5, 0.35, 0, { side: '#2a2c3a' }), ...box([x + L * 0.22, -5.8, z], 0.3, 9, 0.3, 0, { side: '#2a2c3a' }), ...box([x - L * 0.2, 0.5, z], 6.5, 4.2, 0.12, 12 * (hash(i) - 0.5), { side: '#39507f' }), ...box([x + L * 0.42, -5.6, z], 0.5, 0.6, 0.5, 0, { side: '#ffd08a' }, { side: DET.marks([['glow', 0, 0, 1, 1, '#ffd08a']]) })]; sc.add('world', new Mesh(f, { p: [x, -9, z] })); }
  return sc;
}
