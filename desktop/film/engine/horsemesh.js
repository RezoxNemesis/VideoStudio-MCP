// The horse's body: rounded meshes made from the skeleton engine/horse.js places (so it walks, trots and gallops as
// that does, hooves planted), painted like the people. Measures in units of the horse's scale (1 = 1.56 m at the withers).
import { V, hex, shade as shadeHex } from './core.js';

/** Triangles between rings of points (each ring the same length), ends closed. */
function tube(rings, capA = true, capB = true) {
  const n = rings[0].length, pos = [], idx = [];
  for (const r of rings) for (const p of r) pos.push(p[0], p[1], p[2]);
  for (let i = 0; i < rings.length - 1; i++) for (let j = 0; j < n; j++) { const a = i * n + j, b = i * n + (j + 1) % n, c = (i + 1) * n + j, d = (i + 1) * n + (j + 1) % n; idx.push(a, b, c, b, d, c); }
  const cap = (ri, flip) => { const c = pos.length / 3, r = rings[ri], m = r.reduce((s, p) => [s[0] + p[0] / n, s[1] + p[1] / n, s[2] + p[2] / n], [0, 0, 0]); pos.push(m[0], m[1], m[2]); for (let j = 0; j < n; j++) { const a = ri * n + j, b = ri * n + (j + 1) % n; if (flip) idx.push(c, a, b); else idx.push(c, b, a); } };
  if (capA) cap(0, false); if (capB) cap(rings.length - 1, true);
  // faces look outwards whichever way the rings were laid (the light falls by them): a tube that holds a negative volume is turned
  let vol = 0;
  for (let t = 0; t < idx.length; t += 3) { const a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3; vol += pos[a] * (pos[b + 1] * pos[c + 2] - pos[b + 2] * pos[c + 1]) - pos[a + 1] * (pos[b] * pos[c + 2] - pos[b + 2] * pos[c]) + pos[a + 2] * (pos[b] * pos[c + 1] - pos[b + 1] * pos[c]); }
  if (vol < 0) for (let t = 0; t < idx.length; t += 3) { const x = idx[t + 1]; idx[t + 1] = idx[t + 2]; idx[t + 2] = x; }
  return { pos: Float32Array.from(pos), idx: Uint32Array.from(idx) };
}
/** A ring round c: e1 and e2 span it, r1 and r2 its half-widths along them; `shape(a)` may pull single points in or out. */
const ring = (c, e1, e2, r1, r2, n, shape = null) => Array.from({ length: n }, (_, i) => {
  const a = (i / n) * Math.PI * 2, s = shape ? shape(a) : [1, 1], x = Math.cos(a) * r1 * s[0], y = Math.sin(a) * r2 * s[1];
  return [c[0] + e1[0] * x + e2[0] * y, c[1] + e1[1] * x + e2[1] * y, c[2] + e1[2] * x + e2[2] * y];
});
const lerp3 = (a, b, u) => [a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u];

// the trunk from breast to buttock: [x (forward of the middle), topline, underline, half width, how much the top is narrowed (a spine, not a barrel)]
const TRUNK = [
  [0.72, 1.320, 1.050, 0.195, 0.06], [0.66, 1.420, 0.940, 0.262, 0.14], [0.54, 1.510, 0.845, 0.288, 0.28], [0.42, 1.560, 0.790, 0.290, 0.42],
  [0.28, 1.535, 0.785, 0.290, 0.32], [0.10, 1.485, 0.790, 0.308, 0.20], [-0.08, 1.470, 0.810, 0.312, 0.16], [-0.26, 1.475, 0.850, 0.302, 0.15],
  [-0.42, 1.500, 0.930, 0.290, 0.14], [-0.56, 1.525, 0.985, 0.305, 0.10], [-0.70, 1.510, 0.980, 0.295, 0.10], [-0.82, 1.430, 1.000, 0.235, 0.08], [-0.91, 1.330, 1.070, 0.130, 0.05],
];
/** The trunk's measures at a place along it: [topline, underline, half width, how much the top is narrowed]. */
function trunkAt(x) {
  const T = TRUNK; let i = 0; while (i < T.length - 2 && x < T[i + 1][0]) i++;
  const a = T[i], b = T[i + 1], u = Math.min(1, Math.max(0, (a[0] - x) / (a[0] - b[0])));
  return [a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u, a[3] + (b[3] - a[3]) * u, a[4] + (b[4] - a[4]) * u];
}
/** A point on the trunk's skin (or `off` outside it): x along it, a round it (0 = its left side, a quarter turn = the top of the back), for a horse of scale 1: [x, y, z]. */
function skin(x, a, off = 0) {
  const [top, bot, w, nar] = trunkAt(x), cy = (top + bot) / 2, ry = (top - bot) / 2, s = Math.sin(a), c = Math.cos(a);
  return [x, cy + (ry + off) * s - 0.03 * ry * c * c, (w * (s > 0 ? 1 - nar * s * s : 1 + 0.04 * s * s) + off) * c];
}
/** Several meshes as one. */
function merge(list) {
  const pos = [], idx = [];
  for (const m of list) { const base = pos.length / 3; for (const v of m.pos) pos.push(v); for (const i of m.idx) idx.push(i + base); }
  return { pos: Float32Array.from(pos), idx: Uint32Array.from(idx) };
}
/** A sheet over rows of points (each row the same length). */
function sheet(rows) {
  const n = rows[0].length, pos = [], idx = [];
  for (const r of rows) for (const p of r) pos.push(p[0], p[1], p[2]);
  for (let i = 0; i < rows.length - 1; i++) for (let j = 0; j < n - 1; j++) { const a = i * n + j, b = a + 1, c = a + n, d = c + 1; idx.push(a, b, c, b, d, c); }
  return { pos: Float32Array.from(pos), idx: Uint32Array.from(idx) };
}
/** A cord through points: a thin tube. */
function cord(pts, r, sides = 6, flat = 1) {
  const n = pts.length;
  return tube(pts.map((p, i) => {
    const t = V.norm(V.sub(pts[Math.min(n - 1, i + 1)], pts[Math.max(0, i - 1)]));
    let e1 = V.cross(t, [0, 1, 0]); e1 = V.len(e1) > 1e-4 ? V.norm(e1) : [1, 0, 0]; const e2 = V.norm(V.cross(t, e1));
    return ring(p, e1, e2, r, r * flat, sides);
  }));
}

/**
 * How wide the trunk is, from its middle to its side, at a place along it and a height (x forward of the middle, y
 * above the ground, for a horse of scale 1): where a rider's knee and calf lie. 0 above the back and under the belly.
 */
export function trunkHalfWidth(x, y) {
  const T = TRUNK;
  if (x >= T[0][0] || x <= T[T.length - 1][0]) return 0;
  let i = 0; while (i < T.length - 2 && x < T[i + 1][0]) i++;
  const a = T[i], b = T[i + 1], u = (a[0] - x) / (a[0] - b[0]), top = a[1] + (b[1] - a[1]) * u, bot = a[2] + (b[2] - a[2]) * u, w = a[3] + (b[3] - a[3]) * u, nar = a[4] + (b[4] - a[4]) * u;
  const s = (y - (top + bot) / 2) / ((top - bot) / 2);
  if (s >= 1 || s <= -1) return 0;
  return w * Math.sqrt(1 - s * s) * (s > 0 ? 1 - nar * s * s : 1 + 0.04 * s * s);
}

// a leg from its joint in the body to the coronet: [where along its joints (fore: 0 shoulder, 1 knee, 2 fetlock, 3 hoof;
// hind: 0 hip, 1 stifle, 2 hock, 3 fetlock, 4 hoof), half depth fore and aft, half width, shift back]
const FORE = [[-0.06, 0.135, 0.100, 0], [0.2, 0.124, 0.094, 0.012], [0.5, 0.094, 0.078, 0.004], [0.85, 0.066, 0.058, 0], [1, 0.066, 0.060, -0.004], [1.2, 0.050, 0.043, 0], [1.8, 0.048, 0.041, 0], [2, 0.058, 0.052, 0.004], [2.55, 0.046, 0.045, 0], [2.86, 0.053, 0.051, 0]];
const HIND = [[-0.1, 0.200, 0.120, 0], [0.45, 0.215, 0.128, 0.02], [1, 0.165, 0.108, 0.03], [1.35, 0.120, 0.086, 0.012], [1.75, 0.078, 0.061, 0.004], [2, 0.072, 0.058, 0.014], [2.2, 0.055, 0.046, 0.004], [2.8, 0.050, 0.043, 0], [3, 0.060, 0.053, 0.004], [3.55, 0.048, 0.046, 0], [3.86, 0.055, 0.052, 0]];
// the head from behind the poll to the lips: [where along poll → muzzle, half depth, half width, shift towards the jaw]
const HEAD = [[-0.07, 0.055, 0.055, 0], [0, 0.112, 0.090, 0.012], [0.18, 0.160, 0.110, 0.04], [0.38, 0.140, 0.100, 0.034], [0.6, 0.104, 0.076, 0.016], [0.8, 0.084, 0.064, 0.006], [0.95, 0.080, 0.066, 0], [1.04, 0.052, 0.050, 0]];

/**
 * The horse as meshes with their paints: [{ name, pos, idx, paint }]. horse: a Horse that has been placed;
 * lit(colour) → the colour in the scene's light.
 */
export function horseMeshes(horse, lit = (c) => c, q = {}) {
  const j = horse.j, k = horse.k, f = horse.f, sd = horse.sd, W = horse.W, o = horse.o, up = [0, 1, 0], out = [];
  const rgb = (c) => hex(lit(c));
  // the coat: light, shadow and a sheen where the light lies full on it (a dark horse still shows its form)
  const coatOf = (c) => ({ fill: rgb(c), shade: rgb(shadeHex(c, -0.5)), hi: rgb(shadeHex(c, 0.5)), hiAt: 0.8, thr: 0.1, group: 'horse', cast: true });
  const coat = coatOf(o.coat);
  // the legs on the far side of the body stand in its shade, and every leg is drawn against the others (q.eye: where it is seen from)
  const farSide = q.eye ? (V.dot(sd, V.sub(q.eye, j.belly)) > 0 ? 'R' : 'L') : '';
  const legPaint = (leg) => ({ ...(leg[0] === farSide ? coatOf(shadeHex(o.coat, -0.3)) : coat), group: `horse${leg}`, joins: 'horse' });
  const hairP = (c) => ({ fill: rgb(c), shade: rgb(shadeHex(c, -0.4)), thr: 0.0, group: 'horsehair', two: true, soft: true });
  const dark = (c, g) => ({ fill: rgb(c), shade: rgb(shadeHex(c, -0.35)), thr: 0.05, group: g, line: false });
  const N = 16;

  // ---- trunk
  out.push({ name: 'trunk', paint: coat, ...tube(TRUNK.map(([x, top, bot, w, nar]) => {
    const cy = (top + bot) / 2, ry = (top - bot) / 2;
    return Array.from({ length: N }, (_, i) => {
      const a = (i / N) * Math.PI * 2, s = Math.sin(a), c = Math.cos(a);
      // narrower towards the spine, fullest a little below the middle
      const wz = w * c * (s > 0 ? 1 - nar * s * s : 1 + 0.04 * s * s);
      return W(x * k, (cy + ry * s - 0.03 * ry * c * c) * k, wz * k);
    });
  })) });

  // ---- neck: from between the withers and the breast up to the poll, deep at its root, fine at the throat
  const root = W(0.5 * k, 1.3 * k, 0), poll = j.poll, nAx = V.norm(V.sub(poll, root));
  const crest = V.norm(V.cross(sd, nAx)); // towards the mane
  const bend = V.mul(crest, 0.09 * k);
  const neckAt = (u) => V.add(lerp3(root, poll, u), V.mul(bend, 4 * u * (1 - u)));
  const NECK = [[-0.14, 0.33, 0.185], [0.08, 0.30, 0.160], [0.32, 0.235, 0.122], [0.58, 0.180, 0.095], [0.82, 0.140, 0.080], [1.0, 0.115, 0.076]];
  const neckRings = NECK.map(([u, d, w]) => { const c = neckAt(u), t = V.norm(V.sub(neckAt(u + 0.02), neckAt(u - 0.02))), e2 = V.norm(V.cross(sd, t)); return ring(c, sd, e2, w * k, d * k, N, (a) => [Math.sin(a) > 0 ? 1 - 0.3 * Math.sin(a) ** 2 : 1, 1]); });
  out.push({ name: 'neck', paint: coat, ...tube(neckRings) });

  // ---- head
  const hAx = V.norm(V.sub(j.muzzle, poll)), hl = V.dist(j.muzzle, poll), brow = V.norm(V.cross(sd, hAx)); // brow: towards the forehead
  const headRings = HEAD.map(([u, d, w, jaw]) => ring(V.add(V.add(poll, V.mul(hAx, u * hl)), V.mul(brow, -jaw * k)), sd, brow, w * k, d * k, N, (a) => [Math.sin(a) > 0 ? 1 - 0.22 * Math.sin(a) ** 2 : 1 - 0.12 * Math.sin(a) ** 2, 1]));
  out.push({ name: 'head', paint: coat, ...tube(headRings) });
  for (const [nm, sg] of [['earL', 1], ['earR', -1]]) {
    const base = V.add(V.add(poll, V.mul(brow, 0.085 * k)), V.add(V.mul(sd, sg * 0.055 * k), V.mul(hAx, 0.02 * k)));
    const ax = V.norm(V.add(V.add(V.mul(up, 1), V.mul(brow, 0.25)), V.mul(sd, sg * 0.18))), e1 = V.norm(V.cross(ax, hAx)), e2 = V.norm(V.cross(ax, e1));
    out.push({ name: nm, paint: coat, ...tube([[0, 0.038, 0.024], [0.4, 0.042, 0.022], [0.8, 0.024, 0.012], [1, 0.004, 0.003]].map(([u, r1, r2]) => ring(V.add(base, V.mul(ax, u * 0.18 * k)), e1, e2, r1 * k, r2 * k, 8))) });
  }
  for (const [nm, sg] of [['eyeL', 1], ['eyeR', -1]]) {
    const c = V.add(V.add(V.add(poll, V.mul(hAx, 0.2 * hl)), V.mul(brow, 0.035 * k)), V.mul(sd, sg * 0.094 * k));
    out.push({ name: nm, paint: dark('#141012', 'horseeye'), ...tube([-1, -0.5, 0.5, 1].map((u) => ring(V.add(c, V.mul(sd, sg * u * 0.008 * k)), hAx, brow, 0.022 * k * Math.sqrt(1.05 - u * u), 0.016 * k * Math.sqrt(1.05 - u * u), 8))) });
  }
  for (const [nm, sg] of [['nostrilL', 1], ['nostrilR', -1]]) {
    const c = V.add(V.add(V.add(poll, V.mul(hAx, 0.96 * hl)), V.mul(brow, 0.02 * k)), V.mul(sd, sg * 0.05 * k));
    out.push({ name: nm, paint: dark('#1a1416', 'horseeye'), ...tube([-1, 0, 1].map((u) => ring(V.add(c, V.mul(sd, sg * u * 0.006 * k)), hAx, brow, 0.016 * k * (1.1 - Math.abs(u) * 0.5), 0.011 * k * (1.1 - Math.abs(u) * 0.5), 8))) });
  }

  // ---- legs and hooves
  for (const leg of ['LF', 'RF', 'LH', 'RH']) {
    const L = j[leg], front = leg[1] === 'F', hoofTop = V.add(L.hoof, [0, 0.03 * k, 0]);
    const pts = front ? [L.top, L.mid, L.fet, hoofTop] : [L.top, L.stifle, L.mid, L.fet, hoofTop];
    const at = (u) => { const i = Math.min(pts.length - 2, Math.max(0, Math.floor(u))); return lerp3(pts[i], pts[i + 1], u - i); };
    const rings = (front ? FORE : HIND).map(([u, a, b, back]) => {
      const c = at(u), t = V.norm(V.sub(at(u + 0.05), at(u - 0.05)));
      let fa = V.sub(f, V.mul(t, V.dot(f, t))); fa = V.norm(fa);
      return ring(V.add(c, V.mul(fa, -back * k)), sd, fa, b * k, a * k, 12);
    });
    out.push({ name: `leg${leg}`, paint: legPaint(leg), ...tube(rings) });
    // the hoof: wider at the ground than at the coronet, longer at the toe
    const ground = L.hoof[1] - 0.045 * k, hc = [L.hoof[0], 0, L.hoof[2]];
    const hr = [[hoofTop[1] + 0.012 * k, 0.054, 0.052, 0], [hoofTop[1] - 0.02 * k, 0.062, 0.058, 0.006], [ground, 0.074, 0.067, 0.016]].map(([y, a, b, toe]) => ring([hc[0] + f[0] * toe * k, y, hc[2] + f[2] * toe * k], sd, f, b * k, a * k, 12));
    out.push({ name: `hoof${leg}`, paint: dark(o.hoof ?? '#2a2424', 'hoof'), ...tube(hr) });
  }

  // ---- mane: a sheet of hair from the poll down the crest, falling to the right side; a forelock between the ears
  {
    // rows of points on the neck's skin, from the crest down its right side (a: round the neck, a quarter turn = the crest)
    const neckDims = (u) => { let n0 = NECK[0], n1 = NECK[NECK.length - 1]; for (let r = 0; r < NECK.length - 1; r++) if (u >= NECK[r][0] && u <= NECK[r + 1][0]) { n0 = NECK[r]; n1 = NECK[r + 1]; } const w = (u - n0[0]) / ((n1[0] - n0[0]) || 1); return [n0[1] + (n1[1] - n0[1]) * w, n0[2] + (n1[2] - n0[2]) * w]; };
    const onNeck = (u, ang, off) => { const c = neckAt(u), t = V.norm(V.sub(neckAt(u + 0.02), neckAt(u - 0.02))), e2 = V.norm(V.cross(sd, t)), [d, wd] = neckDims(u), sn = Math.sin(ang), cs = Math.cos(ang); return V.add(V.add(c, V.mul(e2, (d + off) * k * sn)), V.mul(sd, (wd * (sn > 0 ? 1 - 0.3 * sn * sn : 1) + off) * k * cs)); };
    const M = 16, a = [], rows = [];
    for (let i = 0; i <= M; i++) {
      // a lock is a hand and a half long (shorter towards the withers), whatever the girth of the neck where it grows
      const u = 0.98 - (i / M) * 0.92, [d, wd] = neckDims(u), len = (0.2 + 0.035 * Math.sin(i * 0.9) + (i % 2) * 0.03) * Math.min(1, 0.35 + u * 1.6), reach = len / Math.max(0.1, 0.55 * d + 0.45 * wd);
      a.push(onNeck(u, Math.PI / 2, 0.008));
      rows.push([0, 0.34, 0.68, 1].map((v, r) => onNeck(u, Math.PI / 2 + v * reach, r === 0 ? 0.008 : 0.028)));
    }
    out.push({ name: 'mane', paint: hairP(o.mane), ...sheet(rows) });
    // and the roots of it stand along the crest, seen from either side
    const up2 = a.map((p, i) => { const u = 0.98 - (i / M) * 0.92, t = V.norm(V.sub(neckAt(u + 0.02), neckAt(u - 0.02))), e2 = V.norm(V.cross(sd, t)); return [p, e2]; });
    out.push({ name: 'crest', paint: { ...hairP(o.mane), two: false }, ...tube(up2.map(([p, e2], i) => ring(V.add(p, V.mul(e2, 0.012 * k)), sd, e2, (i === 0 || i === M ? 0.012 : 0.03) * k, (i === 0 || i === M ? 0.01 : 0.034 + 0.008 * (i % 2)) * k, 8))) });
    const fb = V.add(poll, V.mul(brow, 0.1 * k)), fe = V.add(V.add(poll, V.mul(hAx, 0.15 * hl)), V.mul(brow, 0.15 * k));
    out.push({ name: 'forelock', paint: hairP(o.mane), ...tube([0, 0.5, 1].map((u) => ring(lerp3(fb, fe, u), sd, brow, (0.036 - 0.02 * u) * k, (0.016 - 0.008 * u) * k, 8))) });
  }

  // ---- tail: a sheaf of hair along the points that trail the dock
  {
    const tp = horse.tail ?? [j.dock, V.add(j.dock, [0, -0.3 * k, 0])], n = tp.length, R = [0.036, 0.058, 0.07, 0.06, 0.024];
    const rings = tp.map((p, i) => { const t = V.norm(V.sub(tp[Math.min(n - 1, i + 1)], tp[Math.max(0, i - 1)])); let e2 = V.sub(f, V.mul(t, V.dot(f, t))); e2 = V.len(e2) > 1e-4 ? V.norm(e2) : up; const r = R[Math.min(R.length - 1, Math.round((i / (n - 1)) * (R.length - 1)))]; return ring(p, sd, e2, r * k, r * 0.75 * k, 10); });
    out.push({ name: 'tail', paint: hairP(o.mane), ...tube(rings) });
  }
  // ---- tack: saddle, cloth and girth, a bridle, reins to the rider's hands, stirrups at his feet
  if (o.tack) {
    const P = (p) => W(p[0] * k, p[1] * k, p[2] * k), leather = (c = o.leather ?? '#3a2a22') => ({ fill: rgb(c), shade: rgb(shadeHex(c, -0.4)), thr: 0.05, group: 'tack', two: true });
    const arc = (x, a0, a1, n, off) => Array.from({ length: n }, (_, i) => P(skin(x, a0 + ((a1 - a0) * i) / (n - 1), off)));
    // the cloth: over the back and down both sides
    out.push({ name: 'saddlecloth', paint: { fill: rgb(o.cloth ?? '#5a1a1e'), shade: rgb(shadeHex(o.cloth ?? '#5a1a1e', -0.4)), thr: 0.05, group: 'saddlecloth', two: true }, ...sheet([0.35, 0.25, 0.13, 0, -0.13, -0.25, -0.35].map((x) => arc(x, -0.1, Math.PI + 0.1, 15, 0.014))) });
    // the saddle: a pad over the top of the back, raised in front (the pommel) and behind (the cantle)
    const SX = [0.28, 0.22, 0.1, -0.04, -0.16, -0.23], rise = [0.045, 0.02, 0, 0, 0.025, 0.07];
    out.push({ name: 'saddle', paint: leather(), ...tube(SX.map((x, i) => {
      const outer = Array.from({ length: 9 }, (_, n) => { const a = 0.6 + ((Math.PI - 1.2) * n) / 8, lift = rise[i] * Math.sin(a) ** 4; return P(skin(x, a, 0.04 + lift)); });
      const inner = Array.from({ length: 9 }, (_, n) => P(skin(x, Math.PI - 0.6 - ((Math.PI - 1.2) * n) / 8, 0.016)));
      return [...outer, ...inner];
    })) });
    out.push({ name: 'girth', paint: leather(), ...tube([0.335, 0.3, 0.265].map((x) => Array.from({ length: 20 }, (_, n) => P(skin(x, (n / 20) * Math.PI * 2, 0.01)))), false, false) });
    // the bridle: a noseband, a strap behind the ears, cheek pieces and a browband
    const headAt = (u) => { let a = HEAD[0], b = HEAD[HEAD.length - 1]; for (let r = 0; r < HEAD.length - 1; r++) if (u >= HEAD[r][0] && u <= HEAD[r + 1][0]) { a = HEAD[r]; b = HEAD[r + 1]; } const w = (u - a[0]) / ((b[0] - a[0]) || 1); return [a[1] + (b[1] - a[1]) * w, a[2] + (b[2] - a[2]) * w, a[3] + (b[3] - a[3]) * w]; };
    const onHead = (u, a, off) => { const [d, w, jaw] = headAt(u), s = Math.sin(a), c = Math.cos(a), wz = (w * (s > 0 ? 1 - 0.22 * s * s : 1 - 0.12 * s * s) + off) * k * c; return V.add(V.add(V.add(poll, V.mul(hAx, u * hl)), V.mul(brow, (-jaw + (d + off) * s) * k)), V.mul(sd, wz)); };
    const band = (u, w, n = 14, a0 = 0, a1 = Math.PI * 2) => tube([u - w / 2, u + w / 2].map((uu) => Array.from({ length: n }, (_, i) => onHead(uu, a0 + ((a1 - a0) * i) / (a1 - a0 >= 6.28 ? n : n - 1), 0.006))), false, false);
    const bitL = onHead(0.9, -0.25, 0.01), bitR = onHead(0.9, Math.PI + 0.25, 0.01);
    out.push({ name: 'bridle', paint: leather(), ...merge([band(0.7, 0.045), band(0.04, 0.04), band(0.12, 0.035, 8, 0.5, Math.PI - 0.5),
      cord([onHead(0.04, 0.1, 0.008), onHead(0.3, 0.05, 0.008), onHead(0.7, -0.05, 0.008), bitL], 0.008 * k), cord([onHead(0.04, Math.PI - 0.1, 0.008), onHead(0.3, Math.PI - 0.05, 0.008), onHead(0.7, Math.PI + 0.05, 0.008), bitR], 0.008 * k)]) });
    // the reins: from the bit to the hand that holds them, with a little slack; to the saddle's front when nobody does
    const pommel = P(skin(0.27, Math.PI / 2, 0.09));
    const rein = (bit, hand) => { const to = hand ?? pommel, m = V.add(V.lerp(bit, to, 0.5), [0, -(hand ? 0.05 : 0.02) * k, 0]); return cord(Array.from({ length: 9 }, (_, i) => { const u = i / 8, a = V.lerp(bit, m, u), b = V.lerp(m, to, u); return V.lerp(a, b, u); }), 0.007 * k); };
    out.push({ name: 'reins', paint: leather(), ...merge([rein(bitL, q.hands?.[0]), rein(bitR, q.hands?.[1])]) });
    // the stirrups: a leather from under the saddle's flap to the iron at the foot
    for (const [nm, sg, i] of [['stirrupL', 1, 0], ['stirrupR', -1, 1]]) {
      const from = P(skin(0.12, sg > 0 ? 0.5 : Math.PI - 0.5, 0.03)), foot = q.feet?.[i] ?? V.add(from, V.add(V.mul(sd, sg * 0.03 * k), [0, -0.46 * k, 0]));
      const iron = Array.from({ length: 13 }, (_, n) => { const a = (n / 12) * Math.PI * 2; return V.add(foot, V.add(V.mul(sd, Math.cos(a) * 0.06 * k), [0, (Math.sin(a) * 0.065 - 0.02) * k, 0])); });
      out.push({ name: nm, paint: { ...leather('#8a8690'), group: 'iron' }, ...merge([cord([from, V.lerp(from, foot, 0.5), V.add(foot, [0, 0.045 * k, 0])], 0.012 * k, 6, 0.35), cord(iron, 0.007 * k)]) });
    }
  }
  return out;
}
