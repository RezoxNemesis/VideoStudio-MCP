// FLORA: trees and plants painted the way background artists paint them — a branching skeleton you can see through
// the leaves, crowns built of many small leaf bunches (not balls) lit in five steps from warm top to cool underside,
// gnarled trunks with bark, roots, moss and grass at the foot; blossom, autumn, pine, dead trees, stumps, logs, bushes,
// flowering bushes, mossy rocks, grass tufts, young plants and hanging vines.
//
//   plant({ p: [x, y, z], kind, h, seed, palette, lean, … }) → a scene item ({ p, r, draw(g, cam, sc, t) })
//
// Each plant stands upright in the picture (painted backgrounds are drawn flat to the lens) and sways in the scene's
// wind, its top more than its foot and each leaf bunch a little on its own.
import { V, clamp, hash, noise1, shade as shadeHex } from './core.js';

export const PLANT_KINDS = ['oak', 'sakura', 'autumn', 'pine', 'dead', 'young', 'stump', 'log', 'bush', 'flowerBush', 'rock', 'grass', 'sprout', 'vines'];
/** Five steps of light, darkest first: deep shade, shade, body, lit, highlight. */
export const LEAF = {
  oak: ['#173d2a', '#24592c', '#3d7e2c', '#6aa732', '#b2d84c'],
  spring: ['#1f4a26', '#33702a', '#56a02e', '#8cc83a', '#d2ec6a'],
  dusk: ['#1c3a30', '#2c5532', '#4a7432', '#86983a', '#e0b45a'],
  night: ['#0e1e24', '#16302e', '#24463a', '#3a6046', '#5a8460'],
  sakura: ['#7a3456', '#b85a82', '#e48aac', '#f9bdd0', '#fff0f5'],
  autumn: ['#6a1c08', '#a8400c', '#dc6c10', '#f4a020', '#ffd85a'],
  gold: ['#6a4a08', '#a07812', '#d0a422', '#f0cc3a', '#fff08a'],
  pine: ['#0a2219', '#123626', '#1d4e30', '#2f6e3c', '#5a9a50'],
};
const BARK = { oak: ['#3a2618', '#5e4028', '#8a6440', '#b48c5c'], pale: ['#4a3a30', '#6e5a48', '#988068', '#c4ac8c'], dark: ['#2a1c14', '#422c1e', '#5e4430', '#806048'] };
const R = (s) => { let x = Math.floor(s * 9973) >>> 0 || 1; return () => { x ^= x << 13; x ^= x >>> 17; x ^= x << 5; return ((x >>> 0) % 100000) / 100000; }; };

/* ------------------------------------------------------------------ the skeleton */
/** Branches as segments { a: [x, y], b, w0, w1, depth } in metres from the foot; tips: where leaf bunches gather. */
function grow(o, rnd) {
  const h = o.h, segs = [], tips = [], crownY = h * (o.crownY ?? 0.62), crownW = h * (o.spread ?? 0.5), crownH = h * (o.crownH ?? 0.36);
  const trunkTop = [h * (rnd() - 0.5) * 0.04 + (o.lean ?? 0) * h * 0.2, h * (o.trunk ?? 0.33)];
  // the trunk: in two pieces with a slight bend
  const mid = [trunkTop[0] * 0.4 + (rnd() - 0.5) * h * 0.03, trunkTop[1] * 0.5], tw = h * (o.thick ?? 0.1);
  segs.push({ a: [0, 0], b: mid, w0: tw, w1: tw * 0.86, depth: 0 }, { a: mid, b: trunkTop, w0: tw * 0.86, w1: tw * 0.68, depth: 0 });
  const maxD = o.depth ?? 3;
  const branch = (from, ang, len, w, d) => {
    const bend = (rnd() - 0.5) * (o.kink ?? 0.25), b = [from[0] + Math.sin(ang + bend) * len, from[1] + Math.cos(ang + bend) * len];
    // keep the ends inside the crown's oval (a pruned, believable silhouette)
    const ex = (b[0]) / crownW, ey = (b[1] - crownY) / crownH, out = Math.hypot(ex, ey);
    if (out > 1.05 && d > 0) { b[0] /= out * 0.98; b[1] = crownY + (b[1] - crownY) / (out * 0.98); }
    segs.push({ a: from, b, w0: w, w1: w * 0.62, depth: d });
    if (d >= maxD) { tips.push({ x: b[0], y: b[1], d }); return; }
    const n = d === 0 ? 1 : 2 + (rnd() > 0.55 ? 1 : 0);
    for (let i = 0; i < n; i++) { const spread = (o.fork ?? 0.55) * (1 - d * 0.08); branch(b, ang + (n === 1 ? (rnd() - 0.5) * 0.4 : (i / (n - 1) - 0.5) * 2 * spread + (rnd() - 0.5) * 0.25), len * (0.62 + rnd() * 0.18), w * 0.62, d + 1); }
    if (d >= 1) tips.push({ x: (from[0] + b[0]) / 2, y: (from[1] + b[1]) / 2, d, inner: true });
  };
  const limbs = o.limbs ?? 4;
  for (let i = 0; i < limbs; i++) { const u = limbs > 1 ? i / (limbs - 1) - 0.5 : 0; branch(trunkTop, u * (o.open ?? 1.5) + (rnd() - 0.5) * 0.2, h * (o.limb ?? 0.2) * (0.85 + rnd() * 0.3) * (1 - Math.abs(u) * 0.3), tw * 0.62, 1); }
  return { segs, tips, crown: { x: 0, y: crownY, w: crownW, h: crownH }, trunkTop, tw };
}
/** Leaf bunches spread over the crown: on every tip, more round the outside, gaps left between them for sky. */
function bunches(sk, o, rnd) {
  const out = [], { crown } = sk, size = o.h * (o.bunch ?? 0.075);
  for (const t of sk.tips) {
    const n = t.inner ? 1 : 2 + Math.floor(rnd() * 2);
    for (let i = 0; i < n; i++) { const a = rnd() * Math.PI * 2, r = size * (0.3 + rnd() * 0.6); out.push({ x: t.x + Math.cos(a) * r, y: t.y + Math.sin(a) * r * 0.8 + size * 0.15, r: size * (0.7 + rnd() * 0.55) * (t.inner ? 0.85 : 1), z: rnd() * 2 - 1, k: rnd() }); }
  }
  // fill the crown's body on a jittered grid (light reaches the outside, the inside stays deep), its edge left lumpy
  // so the sky shows through between bunches
  const step = size * (o.gap ?? 1.15);
  for (let gy = -crown.h; gy <= crown.h; gy += step * 0.8) for (let gx = -crown.w; gx <= crown.w; gx += step) {
    const x = crown.x + gx + (rnd() - 0.5) * step * 0.7, y = crown.y + gy + (rnd() - 0.5) * step * 0.6, e = Math.hypot((x - crown.x) / crown.w, (y - crown.y) / crown.h);
    if (e > 0.92 - rnd() * 0.25 || rnd() < (o.holes ?? (e < 0.55 ? 0.22 : 0.08))) continue;
    out.push({ x, y, r: size * (0.85 + rnd() * 0.5), z: rnd() * 2 - 1.15 + e * 0.6, k: rnd() });
  }
  // light of each: toward the sun's side and toward the top, a little random; z < 0 sits behind the branches
  for (const b of out) { b.ex = (b.x - crown.x) / crown.w; b.ey = (b.y - crown.y) / crown.h; }
  return out;
}

/* ------------------------------------------------------------------ drawing pieces */
/** a leaf: pointed oval from (x, y) outward along angle a, length l */
function leaf(g, x, y, a, l, wd = 0.42) { const c = Math.cos(a), s = Math.sin(a), tx = x + c * l, ty = y + s * l, mx = x + c * l * 0.5, my = y + s * l * 0.5, nx = -s * l * wd, ny = c * l * wd; g.moveTo(x, y); g.quadraticCurveTo(mx + nx, my + ny, tx, ty); g.quadraticCurveTo(mx - nx, my - ny, x, y); }
/** a five-petal blossom */
function blossom(g, x, y, r, a) { for (let i = 0; i < 5; i++) { const an = a + (i / 5) * Math.PI * 2; g.moveTo(x, y); g.arc(x + Math.cos(an) * r * 0.55, y + Math.sin(an) * r * 0.55, r * 0.48, 0, Math.PI * 2); } }
/** a bunch of leaves (or blossoms) as one shape: a soft core and leaves fanning out round it, more upward */
function bunchPath(g, x, y, r, seed, kind, px) {
  if (px < 5) { g.moveTo(x + r, y); g.arc(x, y, r, 0, Math.PI * 2); return; }
  if (kind === 'needle') { g.moveTo(x + r * 0.45, y); g.arc(x, y, r * 0.45, 0, Math.PI * 2); const n = px < 14 ? 8 : px < 30 ? 12 : 18; for (let i = 0; i < n; i++) { const a = Math.PI * (0.05 + 0.9 * (i / (n - 1))) + (hash(seed + i) - 0.5) * 0.3; leaf(g, x + Math.cos(a) * r * 0.2, y + Math.sin(a) * r * 0.1 - r * 0.15, a, r * (0.75 + 0.45 * hash(seed * 3 + i)), 0.14); } return; }
  if (kind === 'sakura') { const n = px < 14 ? 7 : px < 30 ? 11 : 16; for (let i = 0; i < n; i++) { const a = hash(seed + i * 2.3) * Math.PI * 2, rr = Math.sqrt(hash(seed + i * 4.1)) * r * 0.8; blossom(g, x + Math.cos(a) * rr, y + Math.sin(a) * rr * 0.85, r * (0.2 + 0.1 * hash(seed + i)), a); } return; }
  g.moveTo(x + r * 0.6, y); g.arc(x, y, r * 0.62, 0, Math.PI * 2);
  const n = px < 14 ? 6 : px < 30 ? 9 : 13;
  for (let i = 0; i < n; i++) { const a = (i / n) * Math.PI * 2 + hash(seed + i * 1.3) * 0.5, l = r * (0.55 + 0.5 * hash(seed * 2 + i)); if (kind === 'sakura') { const bx = x + Math.cos(a) * r * 0.55, by = y + Math.sin(a) * r * 0.5; blossom(g, bx, by, l * 0.42, a); } else leaf(g, x + Math.cos(a) * r * 0.3, y + Math.sin(a) * r * 0.25, a, l, kind === 'autumn' ? 0.5 : 0.4); }
}
/** a tapered limb from a to b, light on one side */
function limb(g, A, B, w0, w1, col, lit, Lx) {
  const dx = B[0] - A[0], dy = B[1] - A[1], l = Math.hypot(dx, dy) || 1, nx = -dy / l, ny = dx / l, cx = (A[0] + B[0]) / 2 + nx * l * 0.06, cy = (A[1] + B[1]) / 2 + ny * l * 0.06;
  g.beginPath(); g.moveTo(A[0] + nx * w0 / 2, A[1] + ny * w0 / 2); g.quadraticCurveTo(cx + nx * (w0 + w1) / 4, cy + ny * (w0 + w1) / 4, B[0] + nx * w1 / 2, B[1] + ny * w1 / 2); g.lineTo(B[0] - nx * w1 / 2, B[1] - ny * w1 / 2); g.quadraticCurveTo(cx - nx * (w0 + w1) / 4, cy - ny * (w0 + w1) / 4, A[0] - nx * w0 / 2, A[1] - ny * w0 / 2); g.closePath(); g.fillStyle = col; g.fill();
  if (lit && w0 > 2.5) { const s = (nx * Lx < 0 ? -1 : 1); g.beginPath(); g.moveTo(A[0] + s * nx * w0 * 0.15, A[1] + s * ny * w0 * 0.15); g.quadraticCurveTo(cx + s * nx * (w0 + w1) / 5, cy + s * ny * (w0 + w1) / 5, B[0] + s * nx * w1 * 0.3, B[1] + s * ny * w1 * 0.3); g.lineWidth = Math.max(1, w0 * 0.22); g.strokeStyle = lit; g.lineCap = 'round'; g.stroke(); }
}
/** grass blades round a point (a tuft), in screen space; s = pixels per metre */
function tuft(g, x, y, s, w, n, cols, seed, t, wind) {
  for (let i = 0; i < n; i++) { const bx = x + (hash(seed + i * 1.7) - 0.5) * w * s, hh = (0.08 + 0.16 * hash(seed + i * 3.1)) * s, lean = (hash(seed + i * 5.3) - 0.5) * 0.8 + wind * Math.sin(t * 2.2 + i) * 0.25, bw = Math.max(1, 0.012 * s);
    g.beginPath(); g.moveTo(bx - bw, y); g.quadraticCurveTo(bx + lean * hh * 0.3, y - hh * 0.6, bx + lean * hh * 0.7, y - hh); g.quadraticCurveTo(bx + lean * hh * 0.25, y - hh * 0.55, bx + bw, y); g.closePath(); g.fillStyle = cols[i % cols.length]; g.fill(); }
}

/* ------------------------------------------------------------------ the plant */
export function plant(o) {
  const kind = o.kind ?? 'oak', seed = o.seed ?? 1, p = o.p, rnd = R(seed * 1.618 + kind.length);
  const big = ['oak', 'sakura', 'autumn', 'dead', 'young'].includes(kind);
  const h = o.h ?? (kind === 'young' ? 2.6 : kind === 'pine' ? 9 : big ? 6.5 : kind === 'bush' || kind === 'flowerBush' ? 1.2 : kind === 'rock' ? 0.8 : kind === 'stump' ? 0.6 : kind === 'log' ? 0.5 : kind === 'vines' ? 1.4 : 0.35);
  const pal = o.palette ?? (kind === 'sakura' ? LEAF.sakura : kind === 'autumn' ? LEAF.autumn : kind === 'pine' ? LEAF.pine : o.leafTone ? LEAF[o.leafTone] : LEAF.oak), bark = BARK[o.bark ?? (kind === 'sakura' ? 'dark' : 'oak')];
  let sk = null, bs = null;
  if (big || kind === 'bush' || kind === 'flowerBush') {
    const opt = { h, lean: o.lean ?? 0, ...(kind === 'young' ? { thick: 0.04, limbs: 3, depth: 2, bunch: 0.11, spread: 0.36, crownY: 0.68, crownH: 0.3, trunk: 0.42 } : {}), ...(kind === 'dead' ? { depth: 5, limbs: 4, kink: 0.75, fork: 0.7, spread: 0.55, limb: 0.24, crownH: 0.42, crownY: 0.6 } : {}), ...(kind === 'bush' || kind === 'flowerBush' ? { thick: 0.03, trunk: 0.15, limbs: 5, depth: 2, bunch: 0.2, spread: 0.85, crownY: 0.5, crownH: 0.42, open: 2.4, limb: 0.35, fill: 0.6 } : {}), ...(kind === 'sakura' ? { spread: 0.56, crownH: 0.32, fork: 0.65, bunch: 0.07 } : {}), ...(o.shape ?? {}) };
    sk = grow(opt, rnd); bs = kind === 'dead' ? [] : bunches(sk, opt, rnd);
    bs.sort((a, b) => a.z - b.z);
  }
  // pine tiers, generated once
  const tiers = kind === 'pine' ? Array.from({ length: o.tiers ?? 11 }, (_, i) => { const u = i / (o.tiers ?? 11); return { y0: h * (0.14 + 0.8 * u), w: h * 0.22 * Math.pow(1 - u * 0.95, 1.0) * (o.spread ?? 1) + h * 0.02, drop: h * (0.06 - u * 0.025), seed: hash(i * 3.7 + seed) * 100 }; }) : null;
  return {
    p, r: h * 1.2,
    draw(g, cam, sc, t) {
      const b = cam.project(p); if (b[2] < 0.3) return;
      const d = V.dist(cam.pos, p), s = cam.f / b[2], px = h * s; if (px < 2) return;
      if (b[0] < -h * s * 1.2 || b[0] > cam.W + h * s * 1.2 || b[1] < -10 || b[1] - px * 1.3 > cam.H + 10) return;
      const L = sc.light2(cam), col = (c) => sc.col(c, d), Lx = L[0], wind = sc.wind ? 1 : 0;
      const sway = (y) => (wind ? (noise1(t * 0.55 + p[0] * 0.13 + p[2] * 0.07, seed) * 0.022 + Math.sin(t * 1.3 + seed) * 0.006) * h * Math.pow(Math.max(0, y) / h, 1.6) * (o.stiff ? 0.3 : 1) : 0);
      const X = (x, y) => b[0] + (x + sway(y)) * s, Y = (y) => b[1] - y * s;
      const shadow = (rx, ry) => { if (px < 18) return; const off = -L[0] * rx * 0.35; g.save(); g.fillStyle = sc.o.shadowColor ?? '#1c2433'; g.globalAlpha = (sc.o.shadowAlpha ?? 0.3) * 0.8; g.beginPath(); g.ellipse(b[0] + off * s, b[1], rx * s, Math.max(1.5, ry * s), 0, 0, Math.PI * 2); g.fill(); g.restore(); };
      const grassCols = [col('#3e6a24'), col('#5e8c2c'), col('#86b03a')];
      if (kind === 'grass' || kind === 'sprout') return drawSmall(g, kind, b, s, h, t, wind, seed, col, grassCols);
      if (kind === 'rock') return drawRock(g, b, s, h, L, col, seed, grassCols, t, wind);
      if (kind === 'stump') return drawStump(g, b, s, h, L, col, bark, grassCols, seed, t, wind, shadow);
      if (kind === 'log') return drawLog(g, b, s, h, L, col, bark, grassCols, seed, t, wind, shadow);
      if (kind === 'vines') return drawVines(g, b, s, h, L, col, pal, seed, t, wind);
      if (kind === 'pine') {
        shadow(h * 0.3, h * 0.05);
        // trunk, then tiers from the bottom up: each a drooping skirt of needles with a jagged hem, lit on the sun's side
        limb(g, [b[0], b[1]], [X(0, h * 0.3), Y(h * 0.3)], h * 0.05 * s, h * 0.035 * s, col(bark[1]), col(bark[3]), Lx);
        // tiers from the bottom up: each a drooping skirt of needle bunches — the hem first, deep under, lit across the
        // top on the sun's side, the tier above lying over it
        for (let i = 0; i < tiers.length; i++) {
          const T = tiers[i], m = Math.max(4, Math.round(T.w / (h * 0.022)));
          for (const pass of [0, 1]) for (let j = 0; j <= m; j++) {
            const u = j / m - 0.5, x = u * 2 * T.w * (pass ? 0.8 : 1), y = T.y0 + T.drop * 1.1 - Math.pow(Math.abs(u) * 2, 1.6) * T.drop * 1.3 + (pass ? T.drop * 0.55 : 0) + (hash(T.seed + j) - 0.5) * T.drop * 0.3, r = h * 0.055 * (1 - i / tiers.length * 0.4) * (0.8 + 0.4 * hash(T.seed + j * 2.3));
            const side = x * Lx, ti = pass === 0 ? (side > 0 ? 1 : 0) : side > T.w * 0.2 ? 3 : side > -T.w * 0.2 ? 2 : 1;
            const sx = X(x, y), sy = Y(y), rr = r * s;
            g.beginPath(); bunchPath(g, sx, sy, rr, T.seed * 7 + j + pass * 50, 'needle', rr); g.fillStyle = col(pal[ti]); g.fill();
            if (pass === 1 && ti >= 2 && rr > 5) { g.beginPath(); bunchPath(g, sx + Lx * rr * 0.2, sy - rr * 0.25, rr * 0.55, T.seed * 9 + j, 'needle', rr * 0.55); g.fillStyle = col(pal[ti + 1]); g.fill(); }
          }
        }
        if (px > 80) tuft(g, b[0], b[1] + 1, s, h * 0.1, 10, grassCols, seed, t, wind);
        return;
      }
      // ---- broadleaf trees and bushes
      const cr = sk.crown;
      shadow(cr.w * (kind === 'bush' || kind === 'flowerBush' ? 1.1 : 0.9), cr.w * 0.18);
      const tone = (q) => { const lit = (q.ex * Lx * 0.5 + q.ey * 0.6) * 0.5 + 0.5 + (q.k - 0.5) * 0.25 + q.z * 0.15; return lit; };
      const toneOf = (v, back) => clamp(Math.floor(v * 4.2) - (back ? 1 : 0), 0, 3);
      const flutter = (q, i) => (wind ? Math.sin(t * 2.4 + i * 1.7 + seed) * 0.012 * h : 0);
      const drawBunches = (list, back) => {
        if (!list.length) return;
        // each bunch: its body in a tone by its light, a lit cap toward the sun, a bright leaf edge on the brightest
        for (let i = 0; i < list.length; i++) {
          const q = list[i], v = tone(q), ti = toneOf(v, back), x = X(q.x + flutter(q, i), q.y), y = Y(q.y), r = q.r * s, sd = seed * 13 + i * 7;
          g.beginPath(); bunchPath(g, x, y + r * 0.08, r, sd, kind, r); g.fillStyle = col(pal[Math.max(0, ti - 1)]); g.fill();
          if (r > 3) { g.beginPath(); bunchPath(g, x + Lx * r * 0.12, y - r * 0.12, r * 0.86, sd, kind, r); g.fillStyle = col(pal[ti]); g.fill(); }
          if (r > 6 && ti >= 2) { g.beginPath(); bunchPath(g, x + Lx * r * 0.3, y - r * 0.32, r * 0.5, sd + 3, kind, r * 0.6); g.fillStyle = col(pal[Math.min(4, ti + 1)]); g.fill(); }
          if (r > 12 && ti === 3 && q.k > 0.4) { g.beginPath(); for (let j = 0; j < 3; j++) { const a = -Math.PI / 2 + (j - 1) * 0.6 + Lx * 0.4; leaf(g, x + Lx * r * 0.35, y - r * 0.4, a, r * 0.32, 0.38); } g.fillStyle = col(pal[4]); g.fill(); }
        }
      };
      const back = bs.filter((q) => q.z < -0.2), front = bs.filter((q) => q.z >= -0.2);
      // 1 — the back of the crown, in its own shade
      drawBunches(back, true);
      // 2 — trunk and branches; bark lines on the trunk; roots spreading into the ground
      const trunkCol = col(bark[1]), trunkLit = col(bark[3]), trunkDk = col(bark[0]);
      const segs = [...sk.segs].sort((a, c) => c.depth - a.depth);
      for (const sg of segs) { if (sg.depth >= 4 && px < 140) continue; const w0 = Math.max(0.8, sg.w0 * s), w1 = Math.max(0.6, sg.w1 * s); limb(g, [X(sg.a[0], sg.a[1]), Y(sg.a[1])], [X(sg.b[0], sg.b[1]), Y(sg.b[1])], w0, w1, sg.depth === 0 ? trunkCol : col(bark[sg.depth > 2 ? 0 : 1]), sg.depth <= 1 ? trunkLit : null, Lx); }
      const tw = sk.tw * s;
      if (tw > 3) {
        // roots: flared feet with the light on one side
        g.fillStyle = trunkCol; for (let i = 0; i < 4; i++) { const sgn = i % 2 ? 1 : -1, sp = (1.4 + 1.4 * hash(seed + i * 2.2)) * tw * sgn, hh = tw * (1.2 + 0.8 * hash(seed + i)); g.beginPath(); g.moveTo(b[0] + sgn * tw * 0.3, b[1] - hh); g.quadraticCurveTo(b[0] + sgn * tw * 0.6, b[1] - tw * 0.2, b[0] + sp, b[1] + tw * 0.1); g.lineTo(b[0] + sgn * tw * 0.1, b[1] + tw * 0.12); g.closePath(); g.fill(); }
        // bark: long strokes up the trunk, dark in its shade, a lit edge on the sun's side
        if (tw > 6) { g.save(); g.lineCap = 'round'; for (let i = 0; i < 7; i++) { const u = (hash(seed + i * 3.3) - 0.5) * 0.8, y0 = sk.trunkTop[1] * (0.05 + 0.3 * hash(seed + i)), y1 = y0 + sk.trunkTop[1] * (0.3 + 0.4 * hash(seed + i * 5.1)); g.beginPath(); g.moveTo(X(u * sk.tw * 0.5, y0), Y(y0)); g.quadraticCurveTo(X(u * sk.tw * 0.5 + 0.02 * h * (hash(i) - 0.5), (y0 + y1) / 2), Y((y0 + y1) / 2), X(u * sk.tw * 0.42, y1), Y(y1)); g.strokeStyle = u * Lx > 0.1 ? trunkLit : trunkDk; g.globalAlpha = 0.7; g.lineWidth = Math.max(1, tw * 0.07); g.stroke(); } g.restore(); g.globalAlpha = 1;
          // the shade side of the trunk under the crown
          g.save(); g.globalAlpha = 0.35; g.fillStyle = trunkDk; g.beginPath(); g.moveTo(X(-Lx * sk.tw * 0.5, 0), Y(0)); g.lineTo(X(-Lx * sk.tw * 0.35, sk.trunkTop[1]), Y(sk.trunkTop[1])); g.lineTo(X(-Lx * sk.tw * 0.05, sk.trunkTop[1]), Y(sk.trunkTop[1])); g.lineTo(X(-Lx * sk.tw * 0.1, 0), Y(0)); g.closePath(); g.fill(); g.restore(); }
        // moss at the foot
        if (tw > 8 && kind !== 'dead') { g.fillStyle = col('#6a8a2a'); g.globalAlpha = 0.8; for (let i = 0; i < 6; i++) { g.beginPath(); g.ellipse(b[0] + (hash(seed + i * 4.4) - 0.5) * tw * 1.4, b[1] - tw * (0.2 + 0.5 * hash(i + seed)), tw * 0.22, tw * 0.1, 0, 0, Math.PI * 2); g.fill(); } g.globalAlpha = 1; }
      }
      // 3 — the front of the crown
      drawBunches(front, false);
      // flowers on a flowering bush; petals or leaves falling from blossom and autumn trees
      if (kind === 'flowerBush' && px > 20) { for (let i = 0; i < 14; i++) { const q = bs[Math.floor(hash(seed + i * 2.7) * bs.length)]; if (!q) continue; const x = X(q.x + (hash(i * 3.9) - 0.5) * q.r, q.y), y = Y(q.y + (hash(i * 5.3) - 0.3) * q.r * 0.8), r = Math.max(1.2, q.r * s * 0.2); g.beginPath(); blossom(g, x, y, r, i); g.fillStyle = col(o.flower ?? '#fffaf0'); g.fill(); g.beginPath(); g.arc(x, y, r * 0.25, 0, Math.PI * 2); g.fillStyle = col('#f0c030'); g.fill(); } }
      if ((kind === 'sakura' || kind === 'autumn') && px > 60 && o.falling !== false) {
        const n = kind === 'sakura' ? 16 : 10;
        for (let i = 0; i < n; i++) { const life = (t * 0.18 + hash(seed + i * 1.9)) % 1, x0 = (hash(seed + i * 3.1) - 0.5) * cr.w * 2, y = cr.y - cr.h * 0.6 - life * (cr.y - cr.h * 0.6), x = x0 + Math.sin(t * 1.4 + i * 2) * h * 0.04 + life * h * 0.15 * (wind ? 1 : 0.3), r = Math.max(1.2, h * 0.012 * s);
          g.save(); g.translate(X(x, 0) - sway(0) * s, Y(y)); g.rotate(t * 2 + i); g.scale(1, 0.5 + 0.4 * Math.sin(t * 3 + i)); g.beginPath(); if (kind === 'sakura') g.ellipse(0, 0, r, r * 0.6, 0, 0, Math.PI * 2); else leaf(g, -r, 0, 0, r * 2.2, 0.45); g.fillStyle = col(pal[3 - (i % 2)]); g.globalAlpha = 1 - Math.max(0, life - 0.85) / 0.15; g.fill(); g.restore(); }
        // fallen ones on the ground round the foot
        if (px > 90) { g.fillStyle = col(pal[2]); for (let i = 0; i < 18; i++) { g.beginPath(); g.ellipse(b[0] + (hash(seed + i * 7.7) - 0.5) * cr.w * 1.8 * s, b[1] + (hash(i * 2.3) - 0.3) * h * 0.03 * s, h * 0.012 * s, h * 0.006 * s, hash(i) * 3, 0, Math.PI * 2); g.fill(); } }
      }
      if (px > 70 && kind !== 'dead') tuft(g, b[0], b[1] + 1, s, cr.w * 0.6, 14, grassCols, seed, t, wind);
    },
  };
}
function drawSmall(g, kind, b, s, h, t, wind, seed, col, grassCols) {
  if (kind === 'grass') { tuft(g, b[0], b[1], s, h * 0.9, 9, grassCols, seed, t, wind); return; }
  // a young plant: a stem with paired leaves
  const top = h * s, sw = Math.sin(t * 2 + seed) * (wind ? 0.06 : 0);
  g.strokeStyle = col('#4a7a2a'); g.lineWidth = Math.max(1, s * 0.012); g.beginPath(); g.moveTo(b[0], b[1]); g.quadraticCurveTo(b[0] + top * 0.1, b[1] - top * 0.5, b[0] + top * sw, b[1] - top); g.stroke();
  g.beginPath(); for (let i = 0; i < 5; i++) { const u = 0.3 + i * 0.16, y = b[1] - top * u, x = b[0] + top * sw * u, sgn = i % 2 ? 1 : -1; leaf(g, x, y, sgn > 0 ? -0.5 : Math.PI + 0.5, top * (0.32 - i * 0.03), 0.45); } g.fillStyle = col('#5a9a30'); g.fill();
}
function drawRock(g, b, s, h, L, col, seed, grassCols, t, wind) {
  const w = h * 1.3 * s, hh = h * s, pts = []; for (let i = 0; i <= 8; i++) { const a = Math.PI + (i / 8) * Math.PI; pts.push([b[0] + Math.cos(a) * w * (0.8 + 0.25 * hash(seed + i)), b[1] + Math.sin(a) * hh * (0.75 + 0.3 * hash(seed + i * 2.1))]); }
  const path = () => { g.beginPath(); g.moveTo(pts[0][0], pts[0][1]); for (let i = 1; i < pts.length; i++) g.lineTo(pts[i][0], pts[i][1]); g.closePath(); };
  path(); g.fillStyle = col('#7a7480'); g.fill(); g.save(); path(); g.clip();
  g.fillStyle = col('#a8a2a8'); g.beginPath(); g.ellipse(b[0] + L[0] * w * 0.25, b[1] - hh * 0.55, w * 0.6, hh * 0.45, 0, 0, Math.PI * 2); g.fill();
  g.fillStyle = col('#c8c2c4'); g.beginPath(); g.ellipse(b[0] + L[0] * w * 0.4, b[1] - hh * 0.75, w * 0.25, hh * 0.15, 0, 0, Math.PI * 2); g.fill();
  // a crack, and moss sitting on the top
  g.strokeStyle = col('#4a4450'); g.lineWidth = Math.max(1, s * 0.01); g.beginPath(); g.moveTo(b[0] - w * 0.1, b[1] - hh * 0.8); g.lineTo(b[0] + w * 0.05, b[1] - hh * 0.4); g.lineTo(b[0] - w * 0.05, b[1]); g.stroke();
  g.fillStyle = col('#6a9a2a'); g.beginPath(); g.ellipse(b[0] - L[0] * w * 0.1, b[1] - hh * 0.95, w * 0.42, hh * 0.18, 0, 0, Math.PI * 2); g.fill(); g.fillStyle = col('#9ac43a'); g.beginPath(); g.ellipse(b[0] + L[0] * w * 0.05, b[1] - hh * 1.0, w * 0.22, hh * 0.08, 0, 0, Math.PI * 2); g.fill();
  g.restore(); path(); g.strokeStyle = col('#3a3440'); g.lineWidth = Math.max(1, s * 0.012); g.stroke();
  tuft(g, b[0] - w * 0.6, b[1] + 1, s, h * 0.6, 6, grassCols, seed, t, wind); tuft(g, b[0] + w * 0.7, b[1] + 1, s, h * 0.4, 5, grassCols, seed + 9, t, wind);
}
function drawStump(g, b, s, h, L, col, bark, grassCols, seed, t, wind, shadow) {
  shadow(h * 0.7, h * 0.15); const w = h * 0.55 * s, hh = h * s, top = b[1] - hh;
  g.beginPath(); g.moveTo(b[0] - w * 1.5, b[1]); g.quadraticCurveTo(b[0] - w, b[1] - hh * 0.2, b[0] - w, top); g.lineTo(b[0] + w, top); g.quadraticCurveTo(b[0] + w, b[1] - hh * 0.2, b[0] + w * 1.6, b[1]); g.closePath(); g.fillStyle = col(bark[1]); g.fill();
  g.save(); g.clip(); g.fillStyle = col(bark[0]); g.fillRect(b[0] - L[0] * w * 0.2 - (L[0] > 0 ? w * 1.6 : 0), top, w * 1.6, hh); g.strokeStyle = col(bark[0]); g.lineWidth = Math.max(1, w * 0.05); for (let i = 0; i < 6; i++) { const x = b[0] + (i / 5 - 0.5) * w * 1.6; g.beginPath(); g.moveTo(x, top); g.lineTo(x + (hash(seed + i) - 0.5) * w * 0.2, b[1]); g.stroke(); } g.restore();
  // the cut face with its rings
  g.beginPath(); g.ellipse(b[0], top, w, w * 0.32, 0, 0, Math.PI * 2); g.fillStyle = col('#d8b884'); g.fill(); g.strokeStyle = col('#a07a4a'); g.lineWidth = Math.max(1, w * 0.03); for (let i = 1; i < 5; i++) { g.beginPath(); g.ellipse(b[0] + w * 0.05, top, w * i / 5, w * 0.32 * i / 5, 0, 0, Math.PI * 2); g.stroke(); } g.beginPath(); g.ellipse(b[0], top, w, w * 0.32, 0, 0, Math.PI * 2); g.strokeStyle = col(bark[0]); g.lineWidth = Math.max(1, w * 0.08); g.stroke();
  tuft(g, b[0] - w * 1.2, b[1] + 1, s, h * 0.6, 7, grassCols, seed, t, wind); tuft(g, b[0] + w * 1.2, b[1] + 1, s, h * 0.5, 6, grassCols, seed + 5, t, wind);
}
function drawLog(g, b, s, h, L, col, bark, grassCols, seed, t, wind, shadow) {
  shadow(h * 2.4, h * 0.3); const len = h * 4 * s, r = h * 0.5 * s, cx = b[0], cy = b[1] - r;
  g.fillStyle = col(bark[1]); g.beginPath(); g.moveTo(cx - len / 2, cy - r); g.lineTo(cx + len / 2, cy - r); g.lineTo(cx + len / 2, cy + r); g.lineTo(cx - len / 2, cy + r); g.closePath(); g.fill();
  g.fillStyle = col(bark[2]); g.fillRect(cx - len / 2, cy - r, len, r * 0.6); g.fillStyle = col(bark[0]); g.fillRect(cx - len / 2, cy + r * 0.4, len, r * 0.6);
  g.strokeStyle = col(bark[0]); g.lineWidth = Math.max(1, r * 0.06); for (let i = 0; i < 6; i++) { const y = cy - r * 0.7 + i * r * 0.28; g.beginPath(); g.moveTo(cx - len / 2 + hash(seed + i) * len * 0.2, y); g.lineTo(cx + len * (0.1 + 0.35 * hash(i + seed)), y + (hash(i) - 0.5) * r * 0.2); g.stroke(); }
  // moss along the top, the hollow end, a broken branch stub
  g.fillStyle = col('#7aa834'); for (let i = 0; i < 5; i++) { g.beginPath(); g.ellipse(cx - len * 0.2 + i * len * 0.1, cy - r, len * 0.07, r * 0.2, 0, 0, Math.PI * 2); g.fill(); }
  g.beginPath(); g.ellipse(cx + len / 2, cy, r * 0.35, r, 0, 0, Math.PI * 2); g.fillStyle = col(bark[2]); g.fill(); g.beginPath(); g.ellipse(cx + len / 2, cy, r * 0.25, r * 0.75, 0, 0, Math.PI * 2); g.fillStyle = col('#1a120c'); g.fill();
  g.beginPath(); g.ellipse(cx - len / 2, cy, r * 0.35, r, 0, 0, Math.PI * 2); g.fillStyle = col('#c8a070'); g.fill();
  limb(g, [cx - len * 0.25, cy - r * 0.9], [cx - len * 0.32, cy - r * 2.2], r * 0.35, r * 0.18, col(bark[1]), null, L[0]);
  tuft(g, cx - len * 0.3, b[1] + 1, s, h * 1.2, 8, grassCols, seed, t, wind); tuft(g, cx + len * 0.3, b[1] + 1, s, h * 1.0, 7, grassCols, seed + 3, t, wind);
}
function drawVines(g, b, s, h, L, col, pal, seed, t, wind) {
  // hanging vines from a point (a wall's top, a branch): strands that sway with small leaves along them
  const n = 7, top = b[1] - h * s;
  for (let i = 0; i < n; i++) { const x0 = b[0] + (i / (n - 1) - 0.5) * h * 0.8 * s, len = h * s * (0.4 + 0.6 * hash(seed + i * 2.3)), sw = (wind ? Math.sin(t * 1.2 + i) * 0.06 : 0) * len;
    g.strokeStyle = col(pal[1]); g.lineWidth = Math.max(1, s * 0.006); g.beginPath(); g.moveTo(x0, top); g.quadraticCurveTo(x0 + sw * 0.4, top + len * 0.5, x0 + sw, top + len); g.stroke();
    for (let j = 0; j < Math.max(3, Math.round(len / (s * 0.06))); j++) { const u = j / Math.max(3, Math.round(len / (s * 0.06))), x = x0 + sw * u * u, y = top + len * u, l = s * 0.07 * (1 - u * 0.4); g.beginPath(); leaf(g, x, y, (j % 2 ? 0.4 : Math.PI - 0.4) + 0.3, l, 0.5); g.fillStyle = col(pal[(j + i) % 2 ? 2 : 3]); g.fill(); } }
  g.fillStyle = col(pal[2]); g.beginPath(); for (let i = 0; i < 9; i++) bunchPath(g, b[0] + (i / 8 - 0.5) * h * 0.9 * s, top + (hash(seed + i) - 0.5) * s * 0.05, s * 0.09, seed + i, 'oak', s * 0.09); g.fill();
}
