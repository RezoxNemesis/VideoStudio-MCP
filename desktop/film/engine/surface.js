// Surfaces painted the way a background painter lays a wall in: the flat colour of a face keeps its light and its air,
// and over it goes the stuff the thing is made of (plaster with damp, brick, worn floor tiles, wood grain, concrete,
// a rolled steel shutter), the dirt that gathers at its foot and where hands touch, and the pools a lamp throws on it.
//
//   box(at, w, h, d, 0, { side: '#d9c39c' }, { front: surface('plaster', { tile: 1.6 }) })
//   sc.lamps = [{ p: [x, y, z], color: '#ffe0a0', reach: 3, k: 0.6 }]   // every surface() in the place catches them
//
// A material is a small picture that repeats every `tile` metres, worked out once (always the same: no boiling) and
// laid on the face cell by cell so it keeps its perspective. It is a DETAIL MAP: grey 0.5 changes nothing, lighter and
// darker move the face's own lit colour, so a brick wall in the shade stays in the shade and fogs into the air.
import { createCanvas } from '@napi-rs/canvas';
import { fbm2, vnoise } from './paintland.js';
import { V, clamp, hex } from './core.js';

const PX = 256;
const CACHE = new Map();
const sst = (a, b, x) => { const t = x <= a ? 0 : x >= b ? 1 : (x - a) / (b - a); return t * t * (3 - 2 * t); };
/** noise that repeats every `per` units in x and y (blended across the seams) */
function tiled(fn, x, y, per) {
  const fx = x / per, fy = y / per;
  return fn(x, y) * (1 - fx) * (1 - fy) + fn(x - per, y) * fx * (1 - fy) + fn(x, y - per) * (1 - fx) * fy + fn(x - per, y - per) * fx * fy;
}
const h1 = (i) => { const s = Math.sin(i * 127.1 + 311.7) * 43758.5453; return s - Math.floor(s); };

/** The makers: each returns [value 0…1 (0.5 = no change), r, g, b tint multipliers] for a point (x, y) of the tile in 0…1. */
const MAKERS = {
  // limewashed or painted plaster: soft blotches, a fine tooth, streaks of damp running down, the odd hairline crack
  plaster: (o) => (x, y) => {
    const s = o.seed ?? 1, blot = tiled((a, b) => fbm2(a + s * 7.1, b, 4), x * 3, y * 3, 3) - 0.5;
    const grain = vnoise(x * 180 + s, y * 180) - 0.5, drip = tiled((a, b) => vnoise(a + s * 3.3, b), x * 26, y * 1.2, 26);
    const crack = Math.abs(tiled((a, b) => fbm2(a + 40 + s, b, 3), x * 4, y * 4, 4) - 0.5) < 0.006 ? -0.18 : 0;
    const v = 0.5 + blot * (o.blot ?? 0.32) + grain * 0.06 - sst(0.62, 0.9, drip) * (o.damp ?? 0.1) * (1 - y * 0.4) + crack * (o.cracks ?? 0);
    return [v, 1, 1, 1];
  },
  // brick in stretcher bond: every brick its own fired colour, mortar recessed and lighter, chipped edges
  brick: (o) => (x, y) => {
    const rows = 8, cols = 4, ry = y * rows, r = Math.floor(ry), off = r % 2 ? 0.5 : 0, rx = x * cols + off, c = Math.floor(rx), fy = ry - r, fx = rx - c;
    const id = h1(r * 17 + ((c % cols) + cols) % cols * 3 + 1), m = 0.07, edge = Math.min(fx, 1 - fx) * 3.5, edgeY = Math.min(fy, 1 - fy);
    const mortar = edgeY < m || Math.min(fx, 1 - fx) < m * 0.32;
    if (mortar) return [0.62 + (vnoise(x * 90, y * 90) - 0.5) * 0.1, 1.04, 1.0, 0.94];
    const chip = vnoise(x * 60 + id * 9, y * 60) > 0.78 && (edgeY < 0.2 || edge < 0.7) ? -0.12 : 0;
    const v = 0.44 + (id - 0.5) * 0.22 + (vnoise(x * 120, y * 120) - 0.5) * 0.08 + chip - (edgeY < 0.15 ? 0.05 : 0);
    return [v, 1 + (id - 0.5) * 0.08, 1, 1 - (id - 0.5) * 0.08];
  },
  // floor tiles: grout lines, each tile a touch different, worn and mottled, a sheen that is not even
  tiles: (o) => (x, y) => {
    const n = o.n ?? 2, tx = x * n, ty = y * n, ix = Math.floor(tx), iy = Math.floor(ty), fx = tx - ix, fy = ty - iy, g = 0.035;
    if (fx < g || fy < g) return [0.3 + (vnoise(x * 70, y * 70) - 0.5) * 0.1, 1, 0.98, 0.95];
    const id = h1(ix * 13 + iy * 7 + (o.seed ?? 0)), mott = tiled((a, b) => fbm2(a + id * 5, b, 3), x * 6, y * 6, 6) - 0.5;
    const v = 0.5 + (id - 0.5) * 0.12 + mott * 0.14 + (fx < g * 2.2 || fy < g * 2.2 ? -0.05 : 0) + (fx > 1 - g * 2 || fy > 1 - g * 2 ? 0.04 : 0);
    return [v, 1, 1, 1];
  },
  // planed wood: long grain along x, darker knots
  wood: (o) => (x, y) => {
    const s = o.seed ?? 3, w = tiled((a, b) => fbm2(a * 0.25 + s, b * 6, 4), x * 4, y * 4, 4), ring = Math.sin((y * 38 + w * 7) * Math.PI) * 0.5 + 0.5;
    const plank = Math.floor(y * (o.planks ?? 4)), pid = h1(plank + s), seam = ((y * (o.planks ?? 4)) % 1) < 0.03 ? -0.2 : 0;
    return [0.5 + (ring - 0.5) * 0.18 + (pid - 0.5) * 0.14 + (w - 0.5) * 0.12 + seam, 1.03, 1, 0.96];
  },
  // cast concrete: speckle, blotches, the lines of the shuttering
  concrete: (o) => (x, y) => {
    const blot = tiled((a, b) => fbm2(a + 9, b, 4), x * 4, y * 4, 4) - 0.5, sp = vnoise(x * 240, y * 240), seam = (y % 0.5 < 0.008 || x % 1 < 0.004) ? -0.1 : 0;
    return [0.5 + blot * 0.26 + (sp > 0.82 ? -0.08 : sp < 0.1 ? 0.05 : 0) + seam, 1, 1, 1.02];
  },
  // a rolled steel shutter: ribs across, rust where water sits
  shutter: (o) => (x, y) => {
    const rib = Math.sin(y * 24 * Math.PI * 2) * 0.5 + 0.5, rust = sst(0.6, 0.8, tiled((a, b) => fbm2(a + 3, b * 3, 4), x * 3, y * 3, 3));
    return [0.42 + rib * 0.22 - rust * 0.12 + (vnoise(x * 200, y * 30) - 0.5) * 0.06, 1 + rust * 0.25, 1, 1 - rust * 0.3];
  },
  // painted metal or a laminate: nearly even, a few scratches and a soft mottle
  paint: (o) => (x, y) => {
    const m = tiled((a, b) => fbm2(a + 2, b, 3), x * 3, y * 3, 3) - 0.5, sc = Math.abs(vnoise(x * 3 + 11, y * 90) - 0.5) < 0.004 ? 0.12 : 0;
    return [0.5 + m * 0.1 + sc, 1, 1, 1];
  },
  // packed earth or an old road: patches, grit, tyre-smoothed bands
  asphalt: (o) => (x, y) => {
    const blot = tiled((a, b) => fbm2(a + 5, b, 4), x * 3, y * 3, 3) - 0.5, grit = vnoise(x * 260, y * 260);
    return [0.5 + blot * 0.22 + (grit - 0.5) * 0.16 + (grit > 0.9 ? 0.12 : 0), 1, 1, 1.02];
  },
};

/** The material's repeating picture (a canvas) — worked out once per kind and options. */
export function materialPicture(kind, o = {}) {
  const key = kind + JSON.stringify(o);
  let cv = CACHE.get(key);
  if (cv) return cv;
  const mk = MAKERS[kind]; if (!mk) throw new Error(`surface: no material "${kind}" (have ${Object.keys(MAKERS).join(', ')})`);
  const f = mk(o), N = o.px ?? PX;
  cv = createCanvas(N, N); const g = cv.getContext('2d'), img = g.createImageData(N, N), d = img.data;
  for (let py = 0; py < N; py++) for (let px = 0; px < N; px++) {
    const [v, tr, tg, tb] = f((px + 0.5) / N, (py + 0.5) / N), q = (py * N + px) * 4, c = clamp(v) * 255;
    d[q] = clamp(c * tr / 255) * 255; d[q + 1] = clamp(c * tg / 255) * 255; d[q + 2] = clamp(c * tb / 255) * 255; d[q + 3] = 255;
  }
  g.putImageData(img, 0, 0);
  CACHE.set(key, cv);
  return cv;
}
export const MATERIALS = Object.keys(MAKERS);

/** draw the picture so that its corners land on screen points a (its top left), b (top right), c (bottom left) */
function lay(g, img, sx, sy, sw, sh, a, b, c) {
  const ex = [b[0] - a[0], b[1] - a[1]], ey = [c[0] - a[0], c[1] - a[1]];
  if (Math.hypot(ex[0], ex[1]) < 0.5 || Math.hypot(ey[0], ey[1]) < 0.5) return;
  g.setTransform(ex[0] / sw, ex[1] / sw, ey[0] / sh, ey[1] / sh, a[0], a[1]);
  g.drawImage(img, sx, sy, sw, sh, 0, 0, sw + 0.6, sh + 0.6); // (a hair of overlap closes the seams between cells)
}

/**
 * A face detail that paints a material on the face. o: { tile: metres one picture covers (default by material),
 *   amt: 0.8 (how strongly), op: 'soft-light' | 'overlay' | 'multiply', grime: 0.35 (dirt at the foot; 0 none),
 *   lip: dirt under the top edge, lamps: true (catch the place's sc.lamps), plus the material's own options }
 * The face's u runs along its first edge, v up its second (for a box's sides: v = 0 at the foot).
 */
export function surface(kind, o = {}) {
  const TILE = { plaster: 1.6, brick: 0.94, tiles: 1.2, wood: 1.2, concrete: 2.4, shutter: 1.2, paint: 1.5, asphalt: 3 };
  const det = (g, F) => {
    const at = F.at; if (!at) return; // (an engine from before 2.6.115 gives no world points)
    const img = materialPicture(kind, o.mat ?? {}), N = img.width;
    let T = o.tile ?? TILE[kind] ?? 1.5;
    const fade = sst(3, 26, F.px * T / 4) * (o.amt ?? 0.8); // too small on screen to show its stuff: leave it plain
    const cam = F.cam, sc = F.sc;
    g.save();
    if (fade > 0.02) {
      let nu = Math.ceil(F.w / T - 1e-6), nv = Math.ceil(F.h / T - 1e-6);
      while (nu * nv > 360) { T *= 2; nu = Math.ceil(F.w / T - 1e-6); nv = Math.ceil(F.h / T - 1e-6); }
      g.globalCompositeOperation = o.op ?? 'soft-light'; g.globalAlpha = fade;
      const tr = g.getTransform();
      for (let iv = 0; iv < nv; iv++) for (let iu = 0; iu < nu; iu++) {
        const u0 = (iu * T) / F.w, u1 = Math.min(1, ((iu + 1) * T) / F.w), v0 = (iv * T) / F.h, v1 = Math.min(1, ((iv + 1) * T) / F.h);
        const a = F.uv(u0, v1), b = F.uv(u1, v1), c = F.uv(u0, v0);
        if (a[2] < 0.2 || b[2] < 0.2 || c[2] < 0.2) continue;
        const sw = ((u1 - u0) * F.w / T) * N, sh = ((v1 - v0) * F.h / T) * N;
        lay(g, img, 0, N - sh, sw, sh, a, b, c);
      }
      g.setTransform(tr);
    }
    g.globalAlpha = 1;
    // dirt: gathered at the foot of an upright face, and a darker lip under its top edge
    const upright = Math.abs(F.n[1]) < 0.5, grime = o.grime ?? (upright ? 0.35 : 0);
    if (grime > 0 && F.h > 0.3) {
      g.globalCompositeOperation = 'multiply';
      const p0 = F.uv(0.5, 0), p1 = F.uv(0.5, Math.min(1, (o.grimeH ?? 0.9) / F.h));
      if (p0[2] > 0.2 && p1[2] > 0.2) {
        const gr = g.createLinearGradient(p0[0], p0[1], p1[0], p1[1]);
        gr.addColorStop(0, `rgba(60,48,40,${grime})`); gr.addColorStop(1, 'rgba(60,48,40,0)');
        g.fillStyle = gr; g.fillRect(-1e4, -1e4, 3e4, 3e4);
      }
      const corner = o.corner ?? 0;
      if (corner > 0) for (const [ua, ub] of [[0, Math.min(0.5, 0.6 / F.w)], [1, Math.max(0.5, 1 - 0.6 / F.w)]]) {
        const a = F.uv(ua, 0.5), b = F.uv(ub, 0.5); if (a[2] < 0.2 || b[2] < 0.2) continue;
        const gr = g.createLinearGradient(a[0], a[1], b[0], b[1]); gr.addColorStop(0, `rgba(50,38,34,${corner})`); gr.addColorStop(1, 'rgba(50,38,34,0)'); g.fillStyle = gr; g.fillRect(-1e4, -1e4, 3e4, 3e4);
      }
      if (o.lip !== 0) {
        const q0 = F.uv(0.5, 1), q1 = F.uv(0.5, Math.max(0, 1 - 0.35 / F.h));
        if (q0[2] > 0.2 && q1[2] > 0.2) { const gr = g.createLinearGradient(q0[0], q0[1], q1[0], q1[1]); gr.addColorStop(0, `rgba(40,30,30,${(o.lip ?? 0.25)})`); gr.addColorStop(1, 'rgba(40,30,30,0)'); g.fillStyle = gr; g.fillRect(-1e4, -1e4, 3e4, 3e4); }
      }
    }
    // pools of lamplight: the nearest point of the face to each lamp, falling off with distance
    const lamps = o.lamps === false ? [] : (o.lampList ?? sc?.lamps ?? []);
    if (lamps.length) {
      g.globalCompositeOperation = 'screen';
      const n = F.n, o0 = F.o0;
      for (const L of lamps) {
        const rel = V.sub(L.p, o0), hgt = V.dot(rel, n);
        if (hgt < -0.05) continue; // behind the face
        const q = V.sub(L.p, V.mul(n, hgt)), reach = (L.reach ?? 3) + hgt * 0.6, k = (L.k ?? 0.55) / (1 + hgt * hgt * 0.35);
        if (k < 0.02) continue;
        const sq = cam.project(q); if (sq[2] < 0.2) continue;
        const side = V.norm(F.eu), edge = cam.project(V.add(q, V.mul(side, reach))); if (edge[2] < 0.2) continue;
        const r = Math.hypot(edge[0] - sq[0], edge[1] - sq[1]); if (r < 2) continue;
        const [cr, cg, cb] = hex(L.color ?? '#ffdca0');
        const gr = g.createRadialGradient(sq[0], sq[1], 0, sq[0], sq[1], r);
        gr.addColorStop(0, `rgba(${cr},${cg},${cb},${clamp(k)})`); gr.addColorStop(0.35, `rgba(${cr},${cg},${cb},${clamp(k * 0.45)})`); gr.addColorStop(1, `rgba(${cr},${cg},${cb},0)`);
        g.fillStyle = gr; g.fillRect(sq[0] - r, sq[1] - r, r * 2, r * 2);
      }
    }
    g.restore();
  };
  det.lod = o.lod ?? 0.5; det.near = true; // (it checks every point it lays itself, so a floor that runs under the lens still gets its stuff)
  return det;
}
