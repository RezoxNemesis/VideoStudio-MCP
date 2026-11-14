// Land and sky painted pixel by pixel, the way a background painter lays them in: a sky lit from where the sun
// stands, with clouds that have a lit and a shaded side; ground whose colour varies like grown land, with low light
// lying on it and mist gathering far off; ridges that pale into the air. Everything is worked out in the world
// (a spot of ground keeps its colour wherever the camera stands), nothing boils under a still camera, and each is an
// item for a place: sc.add('sky', skyPaint(…)), sc.add('far', ridgePaint(…)), sc.add('ground', landPaint(…)).
import { createCanvas } from '@napi-rs/canvas';
import { V, clamp, hex } from './core.js';

/* ------------------------------------------------------------------ noise (fast, the same on every machine) */
const TAB = new Float32Array(65536);
{ let s = 0x2545f491; for (let i = 0; i < 65536; i++) { s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0; TAB[i] = s / 4294967296; } }
/** smooth value noise, 0…1 */
export function vnoise(x, y) {
  const xi = Math.floor(x), yi = Math.floor(y), fx = x - xi, fy = y - yi, u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy);
  const x0 = xi & 255, x1 = (xi + 1) & 255, y0 = (yi & 255) << 8, y1 = ((yi + 1) & 255) << 8;
  const a = TAB[y0 | x0], b = TAB[y0 | x1], c = TAB[y1 | x0], d = TAB[y1 | x1];
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}
/** layered noise, 0…1 (about 0.5 on average) */
export function fbm2(x, y, oct = 4) {
  let s = 0, a = 0.5, n = 0;
  for (let i = 0; i < oct; i++) { s += a * vnoise(x, y); n += a; a *= 0.5; const nx = x * 1.62 + y * 1.18 + 17.3; y = y * 1.62 - x * 1.18 + 9.1; x = nx; }
  return s / n;
}
const sstep = (a, b, x) => { const t = x <= a ? 0 : x >= b ? 1 : (x - a) / (b - a); return t * t * (3 - 2 * t); };
/** a little noise on every pixel, always the same: it breaks the bands of a smooth gradient without ever moving */
const dither = (x, y) => (TAB[((y * 131 + x * 71) ^ (x * 7 + y * 13)) & 65535] - 0.5) * 0.9;

/** A picture of w × h pixels worked out at 1/res of the picture's size, laid over the whole picture softly. */
function plate(cam, res) {
  const w = Math.ceil(cam.W / res), h = Math.ceil(cam.H / res), cv = createCanvas(w, h), cg = cv.getContext('2d'), img = cg.createImageData(w, h);
  return { w, h, d: img.data, put(g) { cg.putImageData(img, 0, 0); g.save(); g.imageSmoothingEnabled = true; g.imageSmoothingQuality = 'high'; g.drawImage(cv, 0, 0, w, h, 0, 0, w * res, h * res); g.restore(); } };
}
const camSig = (cam) => [cam.pos, cam.F, cam.U].map((v) => v.map((x) => x.toFixed(4)).join()).join() + [cam.f, cam.cx, cam.cy, cam.W, cam.H].map((x) => x.toFixed(2)).join();

/* ----------------------------------------------------------------------------------------------- the sky */

/**
 * o: { zenith, mid, horizon (colours from high up to the horizon on the sun's side), away (the horizon on the far side),
 *      sun: { dir: [x, y, z] towards it, color, glow, r (degrees, its disc: 0 for none), spread: 1 },
 *      clouds: { cover: 0…1, scale (m, the size of a cloud), height (m), color (its lit side near the sun), far (its lit side
 *        far round the sky from the sun), shade, wind: [x, z] m/s, seed, alpha },
 *      res: 2 (worked out at half the picture's size) }
 */
export function skyPaint(o) {
  const zen = hex(o.zenith), mid = hex(o.mid), hor = hex(o.horizon), away = hex(o.away ?? o.mid);
  const S = o.sun ? V.norm(o.sun.dir) : null, sh = S ? Math.hypot(S[0], S[2]) || 1 : 1, sunC = hex(o.sun?.color ?? '#fff4dc'), glowC = hex(o.sun?.glow ?? o.sun?.color ?? '#ffd9a0');
  const spread = o.sun?.spread ?? 1, discR = Math.cos(((o.sun?.r ?? 0.9) * Math.PI) / 180), discSoft = Math.cos((((o.sun?.r ?? 0.9) + 0.35) * Math.PI) / 180);
  const cl = o.clouds, clC = cl ? hex(cl.color ?? '#ffffff') : null, clS = cl ? hex(cl.shade ?? '#8a90a8') : null, clF = cl ? hex(cl.far ?? cl.color ?? '#ffffff') : null, seed = (cl?.seed ?? 1) * 37.13;
  return {
    draw(g, cam, sc, t = 0) {
      const res = o.res ?? 2, P = plate(cam, res), d = P.d, R = cam.R, U = cam.U, F = cam.F, f = cam.f;
      const wx = cl ? (cl.wind?.[0] ?? 0) * t : 0, wz = cl ? (cl.wind?.[1] ?? 0) * t : 0, inv = cl ? 1 / (cl.scale ?? 900) : 0, Hc = cl?.height ?? 1500;
      for (let py = 0; py < P.h; py++) {
        const vy = -((py + 0.5) * res - cam.cy);
        for (let px = 0; px < P.w; px++) {
          const vx = (px + 0.5) * res - cam.cx;
          let dx = F[0] * f + R[0] * vx + U[0] * vy, dy = F[1] * f + R[1] * vx + U[1] * vy, dz = F[2] * f + R[2] * vx + U[2] * vy;
          const l = 1 / Math.hypot(dx, dy, dz); dx *= l; dy *= l; dz *= l;
          const el = Math.asin(clamp(dy, -1, 1)) / (Math.PI / 2), a = el < 0 ? 0 : el; // 0 at the horizon, 1 straight up
          // towards the sun or away from it, round the horizon
          const hl = Math.hypot(dx, dz) || 1e-6, side = S ? 0.5 + 0.5 * (dx * S[0] + dz * S[2]) / (hl * sh) : 0.5, sd = Math.pow(side, 2.4); // (the warmth of a low sun reaches about a sixth of the way round)
          const u1 = sstep(0, 0.16 + 0.1 * sd, a), u2 = sstep(0.06, 0.62, a);
          let r = away[0] + (hor[0] - away[0]) * sd, gg = away[1] + (hor[1] - away[1]) * sd, b = away[2] + (hor[2] - away[2]) * sd;
          r += (mid[0] - r) * u1; gg += (mid[1] - gg) * u1; b += (mid[2] - b) * u1;
          r += (zen[0] - r) * u2; gg += (zen[1] - gg) * u2; b += (zen[2] - b) * u2;
          let c3 = 0;
          if (S) {
            // the light round the sun: a wide warmth, a nearer glow, its disc
            c3 = Math.max(0, dx * S[0] + dy * S[1] + dz * S[2]);
            const wide = Math.pow(c3, 6 / spread) * 0.34, nearG = Math.pow(c3, 70 / spread) * 0.55, core = Math.pow(c3, 900 / spread) * 0.9, k = Math.min(1, wide + nearG + core);
            r += (glowC[0] - r) * k * 0.9 + glowC[0] * core * 0.25; gg += (glowC[1] - gg) * k * 0.9 + glowC[1] * core * 0.25; b += (glowC[2] - b) * k * 0.9 + glowC[2] * core * 0.25;
            if (o.sun.r !== 0 && c3 > discSoft) { const q = sstep(discSoft, discR, c3); r += (sunC[0] - r) * q; gg += (sunC[1] - gg) * q; b += (sunC[2] - b) * q; }
          }
          if (cl && dy > 0.012) {
            // a layer of cloud at its height above the lens; far clouds are thin streaks and fade into the horizon
            const tt = Hc / dy, cx = (dx * tt + wx) * inv + seed, cz = (dz * tt + wz) * inv, oct = dy < 0.05 ? 2 : dy < 0.11 ? 3 : 5;
            const warp = fbm2(cx * 0.5 + 31.7, cz * 0.5, 2) - 0.5, n = fbm2(cx + warp * 0.9, cz - warp * 0.6, oct);
            const dens = sstep(0.7 - 0.42 * (cl.cover ?? 0.4), 0.86 - 0.42 * (cl.cover ?? 0.4), n);
            if (dens > 0.003) {
              // lit where less cloud lies towards the sun
              const sx = S ? S[0] / sh : 0.7, sz = S ? S[2] / sh : 0.7, n2 = fbm2(cx + warp * 0.9 + sx * 0.16, cz - warp * 0.6 + sz * 0.16, Math.min(oct, 3));
              const lit = clamp(0.55 + (n - n2) * 5 + (1 - dens) * 0.3), near = Math.pow(c3, 3) * 0.6;
              // (the light on a cloud is the sun's own near it, and pales to the far colour round the sky)
              const lr = clF[0] + (clC[0] - clF[0]) * sd, lg = clF[1] + (clC[1] - clF[1]) * sd, lb = clF[2] + (clC[2] - clF[2]) * sd;
              let cr = clS[0] + (lr - clS[0]) * lit, cg2 = clS[1] + (lg - clS[1]) * lit, cb = clS[2] + (lb - clS[2]) * lit;
              cr += (glowC[0] - cr) * near; cg2 += (glowC[1] - cg2) * near; cb += (glowC[2] - cb) * near;
              const al = dens * (cl.alpha ?? 0.92) * (0.18 + 0.82 * sstep(0.012, 0.16, dy));
              r += (cr - r) * al; gg += (cg2 - gg) * al; b += (cb - b) * al;
            }
          }
          const q = (py * P.w + px) * 4, dn = dither(px, py);
          d[q] = r + dn; d[q + 1] = gg + dn; d[q + 2] = b + dn; d[q + 3] = 255;
        }
      }
      P.put(g);
    },
  };
}

/* ---------------------------------------------------------------------------------------------- the land */

/**
 * Flat land at height y, painted. o: { y: 0, colors: [the colours of what grows, from one kind of patch to another],
 *   earth (bare ground), earthAmt: 0.35 (how bare it gets), earthCover: 0.3 (how much of the land), patch: 34 (m, the size of the big patches), relief: 1 (how strongly low light models
 *   the swells of the ground), cool (the colour hollows keep), sun: { dir, color }, wet: 0.5 (dew: how the land shines towards a low sun),
 *   mist: { color, dist (m at which it has gathered), amt: 0…1 }, res: 1 }
 */
export function landPaint(o) {
  const y0 = o.y ?? 0, seed = (o.seed ?? 1) * 51.7, patch = o.patch ?? 34, S = o.sun ? V.norm(o.sun.dir) : null, sh = S ? Math.hypot(S[0], S[2]) || 1 : 1;
  const sunC = hex(o.sun?.color ?? '#fff0d0'), wet = o.wet ?? 0.5, relief = o.relief ?? 1, earthAmt = o.earthAmt ?? 0.35, bareAt = 0.73 - 0.45 * (o.earthCover ?? 0.3);
  let cache = null;
  return {
    sun: o.sun ?? null,
    /** What grows at a spot of the land: its own colour there (before light and air) and how bare it is (0…1). */
    colourAt(wx, wz, sc) {
      const pal = (this.pal ??= o.colors.map((c) => hex(sc.grade(c)))), earth = (this.earth ??= hex(sc.grade(o.earth ?? '#5a4a38'))), nP = pal.length - 1;
      const big = fbm2(wx / patch + seed, wz / patch, 3), med = fbm2(wx / 7.5 + seed * 0.7, wz / 7.5 + 3.3, 3);
      const v = clamp((big * 0.62 + med * 0.38 - 0.3) / 0.4) * nP, i0 = Math.min(nP - 1, Math.floor(v)), fr = nP > 0 ? v - i0 : 0, A = pal[Math.max(0, i0)], B = pal[Math.min(nP, i0 + 1)];
      const e = sstep(bareAt, bareAt + 0.1, fbm2(wx / 11 + 40.3 + seed, wz / 11 + 12.9, 3)) * earthAmt;
      return { rgb: [A[0] + (B[0] - A[0]) * fr + (earth[0] - (A[0] + (B[0] - A[0]) * fr)) * e, A[1] + (B[1] - A[1]) * fr + (earth[1] - (A[1] + (B[1] - A[1]) * fr)) * e, A[2] + (B[2] - A[2]) * fr + (earth[2] - (A[2] + (B[2] - A[2]) * fr)) * e], bare: e };
    },
    draw(g, cam, sc) {
      const res = o.res ?? 1, sig = camSig(cam) + res;
      if (cache?.sig === sig) { cache.P.put(g); return; }
      const P = plate(cam, res), d = P.d, R = cam.R, U = cam.U, F = cam.F, f = cam.f, ch = cam.pos[1] - y0;
      const pal = o.colors.map((c) => hex(sc.grade(c))), earth = hex(sc.grade(o.earth ?? '#5a4a38')), haze = sc.haze, nP = pal.length - 1;
      const mist = o.mist ? { c: hex(o.mist.color), d: o.mist.dist ?? 300, a: o.mist.amt ?? 0.8 } : null;
      const sx = S ? S[0] / sh : 0.7, sz = S ? S[2] / sh : 0.7, low = S ? clamp(1 - S[1] * 2.2) : 0; // how low the sun stands
      const cool = hex(sc.grade(o.cool ?? '#2e3550'));
      if (ch > 0.02) for (let py = 0; py < P.h; py++) {
        const vy = -((py + 0.5) * res - cam.cy);
        for (let px = 0; px < P.w; px++) {
          const vx = (px + 0.5) * res - cam.cx;
          let dx = F[0] * f + R[0] * vx + U[0] * vy, dy = F[1] * f + R[1] * vx + U[1] * vy, dz = F[2] * f + R[2] * vx + U[2] * vy;
          const l = 1 / Math.hypot(dx, dy, dz); dx *= l; dy *= l; dz *= l;
          const q = (py * P.w + px) * 4;
          if (dy > -2e-4) { d[q + 3] = 0; continue; }
          const tt = -ch / dy, wx = cam.pos[0] + dx * tt, wz = cam.pos[2] + dz * tt;
          // what a pixel covers on the ground: detail finer than that is left out (it would only glitter)
          const fp = (tt * res) / f, fpd = fp * Math.min(40, tt / ch);
          const big = fbm2(wx / patch + seed, wz / patch, 3), med = fbm2(wx / 7.5 + seed * 0.7, wz / 7.5 + 3.3, 3);
          const w3 = clamp(1.3 - fpd / 0.7), w4 = clamp(1.3 - fpd / 0.13);
          const fine = w3 > 0 ? vnoise(wx / 1.25 + 7.1, wz / 1.25 + seed) - 0.5 : 0, grain = w4 > 0 ? vnoise(wx / 0.2 + seed, wz / 0.2 + 1.7) - 0.5 : 0;
          // what grows here: from one colour of the land to the next
          const v = clamp((big * 0.62 + med * 0.38 - 0.3) / 0.4) * nP, i0 = Math.min(nP - 1, Math.floor(v)), fr = nP > 0 ? v - i0 : 0, A = pal[Math.max(0, i0)], B = pal[Math.min(nP, i0 + 1)];
          let r = A[0] + (B[0] - A[0]) * fr, gg = A[1] + (B[1] - A[1]) * fr, b = A[2] + (B[2] - A[2]) * fr;
          // bare earth where the growth is thin
          const e = sstep(bareAt, bareAt + 0.1, fbm2(wx / 11 + 40.3 + seed, wz / 11 + 12.9, 3) + (fine * w3) * 0.12) * earthAmt;
          r += (earth[0] - r) * e; gg += (earth[1] - gg) * e; b += (earth[2] - b) * e;
          // low light models the ground at every size (rolls, hummocks, tussocks): brighter where it rises towards the sun
          let swell = 0;
          if (S) {
            const a = 0.35 + 0.65 * low, w2 = clamp(1.3 - fpd / 1.4);
            swell = (fbm2((wx + sx * 6) / 21 + seed * 1.3, (wz + sz * 6) / 21 + 8.8, 2) - fbm2(wx / 21 + seed * 1.3, wz / 21 + 8.8, 2)) * 2.6 * a
              + (fbm2((wx + sx * 1.4) / 4.6 + seed * 0.7, (wz + sz * 1.4) / 4.6 + 3.3, 2) - fbm2(wx / 4.6 + seed * 0.7, wz / 4.6 + 3.3, 2)) * 1.9 * a * w2
              + (w3 > 0 ? (vnoise((wx + sx * 0.35) / 1.1 + 2.2, (wz + sz * 0.35) / 1.1 + seed) - vnoise(wx / 1.1 + 2.2, wz / 1.1 + seed)) * 1.3 * a * w3 : 0);
            swell *= relief;
          }
          // tufts and what lies between them, as far out as a pixel can still tell them apart
          const wt = clamp(1.3 - fpd / 0.32), tuft = wt > 0 ? sstep(0.56, 0.72, vnoise(wx / 0.42 + seed * 0.3, wz / 0.42 + 5.1)) * wt : 0;
          const k = 1 + clamp(swell, -0.32, 0.36) + fine * w3 * 0.26 + grain * w4 * 0.2 - tuft * 0.24 * (1 - e);
          r *= k; gg *= k; b *= k;
          // hollows hold the cool of the night
          if (swell < 0) { const cq = Math.min(0.35, -swell * 0.9); r += (cool[0] - r) * cq; gg += (cool[1] - gg) * cq; b += (cool[2] - b) * cq; }
          if (S) {
            // the sun's own colour on what faces it, and dew shining back towards a low sun
            const lit = clamp(0.5 + swell * 2.2), facing = Math.max(0, -(dx * sx + dz * sz) / (Math.hypot(dx, dz) || 1e-6));
            const warm = 0.1 * lit * (0.4 + 0.6 * low), sheen = wet * low * Math.pow(Math.max(0, (dx * sx + dz * sz) / (Math.hypot(dx, dz) || 1e-6)), 2.2) * (0.16 + 0.3 * clamp(tt / 60));
            r += (sunC[0] - r) * (warm + sheen); gg += (sunC[1] - gg) * (warm + sheen); b += (sunC[2] - b) * (warm + sheen);
            void facing;
          }
          // the air between the ground and the lens, and mist lying on far land
          let fa = sc.fog(tt);
          r += (haze[0] - r) * fa; gg += (haze[1] - gg) * fa; b += (haze[2] - b) * fa;
          if (mist) { fa = mist.a * (1 - Math.exp(-tt / mist.d)); r += (mist.c[0] - r) * fa; gg += (mist.c[1] - gg) * fa; b += (mist.c[2] - b) * fa; }
          const dn = dither(px, py);
          d[q] = r + dn; d[q + 1] = gg + dn; d[q + 2] = b + dn; d[q + 3] = 255;
        }
      }
      cache = { sig, P };
      P.put(g);
    },
  };
}

/* ---------------------------------------------------------------------------------------------- far ridges */

/**
 * Ridges standing round the horizon, one behind another. Each is a body of its own: crest behind crest (folds), every
 * crest dark against the paler one behind it and lost in mist at its foot.
 * o: { layers: [{ dist (m), h (m, its highest crest), color, seed, peaks: 5 (crests in a quarter turn), base: 0.25 (its lowest
 *   saddles, as a part of h), folds: 4, sharp: 0.4 (0 a rolling line … 1 teeth), rough: 0.5 (small teeth), snow: { line: 0.7, color },
 *   gap: { az (degrees round from +z towards +x), w (degrees), depth: 0…1 } a gorge cut through it }],
 *   air: 2600 (m of air after which a ridge has paled by two thirds), sun: { dir, color, glow (the air's colour towards it) }, res: 1 }
 */
export function ridgePaint(o) {
  const S = o.sun ? V.norm(o.sun.dir) : null, sunAz = S ? Math.atan2(S[0], S[2]) : 0, sunC = hex(o.sun?.color ?? '#ffe0b0'), air = o.air ?? 2600;
  // every fold of every layer, farthest first
  const folds = [];
  for (const L of o.layers) {
    const n = L.folds ?? 4;
    for (let j = 0; j < n; j++) folds.push({ L, j, dist: L.dist * (1 - 0.3 * (j / n)), h: L.h * (1 - 0.72 * (j / n)) * (j ? 0.92 : 1), seed: (L.seed ?? 1) * 13.7 + j * 5.31, k: ((L.peaks ?? 5) * (1 + j * 0.45)) / (Math.PI / 2) });
  }
  folds.sort((a, b) => b.dist - a.dist);
  // (round the horizon the pattern is read on a circle, so it has no seam where the angle starts again)
  const crest = (q, ca, sa, az) => {
    const L = q.L, s = q.seed, K = q.k, w = (fbm2(ca * K * 0.5 + s * 0.4, sa * K * 0.5 + 7.7, 2) - 0.5) * 0.5;
    const soft = fbm2(ca * K + s + w, sa * K + s * 0.3, 4), sharp = 1 - Math.abs(2 * fbm2(ca * K * 2.3 + s * 1.7 - w, sa * K * 2.3 + 4.1 + s, 3) - 1);
    const sp = L.sharp ?? 0.4;
    let hh = (L.base ?? 0.25) + (1 - (L.base ?? 0.25)) * clamp(((1 - sp) * soft + sp * sharp * (0.4 + soft) - 0.2) * 1.8) + ((L.rough ?? 0.5) * 0.05 + sp * 0.07) * (fbm2(ca * K * 9 + s, sa * K * 9 + 1.3, 3) - 0.5);
    if (L.gap) { let da = az - (L.gap.az * Math.PI) / 180; while (da > Math.PI) da -= 2 * Math.PI; while (da < -Math.PI) da += 2 * Math.PI; const u = da / ((L.gap.w * Math.PI) / 180); hh *= 1 - (L.gap.depth ?? 0.9) * Math.exp(-u * u); }
    return Math.max(0.004, hh);
  };
  return {
    draw(g, cam, sc) {
      const res = o.res ?? 1, P = plate(cam, res), d = P.d, R = cam.R, U = cam.U, F = cam.F, f = cam.f, haze = sc.haze, pix = res / f;
      const Fs = folds.map((q) => ({ q, c: hex(sc.grade(q.L.color)), fog: clamp(1 - Math.exp(-q.dist / air), 0, 0.93), snow: q.L.snow ? hex(q.L.snow.color ?? '#f4f2f0') : null }));
      const glow = hex(o.sun?.glow ?? o.sun?.color ?? '#ffd9a0'), nF = Fs.length;
      // the crest of every fold in every column of the picture (a column's direction at the horizon), one column beyond each edge
      const EL = new Float32Array((P.w + 2) * nF), LIT = new Float32Array((P.w + 2) * nF), AZ = new Float32Array(P.w + 2);
      for (let cx = 0; cx < P.w + 2; cx++) {
        const vx = (cx - 0.5) * res - cam.cx, az = Math.atan2(F[0] * f + R[0] * vx, F[2] * f + R[2] * vx), ca = Math.cos(az), sa = Math.sin(az), ca1 = Math.cos(az - 0.02), sa1 = Math.sin(az - 0.02), ca2 = Math.cos(az + 0.02), sa2 = Math.sin(az + 0.02);
        let da = az - sunAz; while (da > Math.PI) da -= 2 * Math.PI; while (da < -Math.PI) da += 2 * Math.PI;
        const sg = Math.sign(da || 1); AZ[cx] = az;
        for (let i = 0; i < nF; i++) {
          const q = Fs[i].q;
          EL[cx * nF + i] = Math.atan((q.h * crest(q, ca, sa, az)) / q.dist);
          // the broad lie of the flank (its small teeth left out): one that falls away from the sun's side is in its light
          LIT[cx * nF + i] = S ? clamp(0.5 + (fbm2(ca2 * q.k + q.seed, sa2 * q.k + q.seed * 0.3, 2) - fbm2(ca1 * q.k + q.seed, sa1 * q.k + q.seed * 0.3, 2)) * sg * 9) : 0.5;
        }
      }
      const col = Fs.map(() => ({ el: 0, lit: 0, edge: 1 }));
      for (let px = 0; px < P.w; px++) {
        const vx = (px + 0.5) * res - cam.cx, az = AZ[px + 1], ca = Math.cos(az), sa = Math.sin(az);
        let da = az - sunAz; while (da > Math.PI) da -= 2 * Math.PI; while (da < -Math.PI) da += 2 * Math.PI;
        // 0 where the ridge stands before the sun (seen against the light), 1 where the sun shines full on it
        const sg = Math.sign(da || 1), front = S ? 0.5 - 0.5 * Math.cos(da) : 0.5, against = (1 - front) * (1 - front);
        const hz = [haze[0] + (glow[0] - haze[0]) * 0.4 * against, haze[1] + (glow[1] - haze[1]) * 0.4 * against, haze[2] + (glow[2] - haze[2]) * 0.4 * against];
        for (let i = 0; i < nF; i++) {
          col[i].el = EL[(px + 1) * nF + i]; col[i].lit = LIT[(px + 1) * nF + i];
          // how far a pixel stands from the crest line is measured across it, not straight up (a steep crest stays smooth)
          const sl = (EL[(px + 2) * nF + i] - EL[px * nF + i]) / (2 * pix); col[i].edge = 1 / Math.sqrt(1 + sl * sl);
        }
        for (let py = 0; py < P.h; py++) {
          const vy = -((py + 0.5) * res - cam.cy);
          const dx = F[0] * f + R[0] * vx + U[0] * vy, dy = F[1] * f + R[1] * vx + U[1] * vy, dz = F[2] * f + R[2] * vx + U[2] * vy;
          const el = Math.atan2(dy, Math.hypot(dx, dz)), qq = (py * P.w + px) * 4;
          if (el < -0.03) { d[qq + 3] = 0; continue; }
          let r = 0, gg = 0, b = 0, al = 0;
          for (let i = 0; i < Fs.length; i++) {
            const c = col[i], cov = clamp(((c.el - el) / pix) * c.edge + 0.5); if (cov <= 0) continue;
            const F1 = Fs[i], q = F1.q, u = clamp(el / Math.max(1e-5, c.el)); // 0 at its foot, 1 at its crest
            // spurs and gullies running down its flanks (they lean with the fall of the slope), laid in with a broken stroke
            const spur = fbm2(ca * q.k * 7 + q.seed * 2.1 + u * 1.3 * sg, sa * q.k * 7 + u * 2.6 + q.seed, 3) - 0.5, stroke = (vnoise(ca * q.k * 31 + q.seed + u * 2.2, sa * q.k * 31 + u * 11 + q.seed) - 0.5) * 0.07;
            const form = clamp(c.lit + spur * 1.5 * (0.25 + 0.75 * u)), k = 0.66 + 0.22 * front + stroke * u + (form - 0.5) * 0.16;
            let cr = F1.c[0] * k, cg2 = F1.c[1] * k, cb = F1.c[2] * k;
            if (F1.snow && q.j === 0) { const sl = (q.L.snow.line ?? 0.7) + spur * 0.35, sn = sstep(sl, sl + 0.07, u * (0.55 + 0.45 * clamp(c.el / Math.atan(q.h / q.dist)))); cr += (F1.snow[0] * (0.78 + 0.22 * form) - cr) * sn; cg2 += (F1.snow[1] * (0.78 + 0.22 * form) - cg2) * sn; cb += (F1.snow[2] * (0.8 + 0.2 * form) - cb) * sn; }
            // the sun's colour on the flanks turned to it (most where it shines full on the ridge), a thin edge of light on a crest seen against it
            const lit = S ? form * (0.25 + 0.75 * u) * (0.08 + 0.3 * front) + against * 0.22 * sstep(0.9, 1, u) * (q.j === 0 ? 1 : 0.35) : 0;
            cr += (sunC[0] - cr) * lit; cg2 += (sunC[1] - cg2) * lit; cb += (sunC[2] - cb) * lit;
            // the air before it, and the mist its foot stands in
            const fa = F1.fog + (1 - F1.fog) * 0.94 * Math.pow(1 - u, 1.3);
            cr += (hz[0] - cr) * fa; cg2 += (hz[1] - cg2) * fa; cb += (hz[2] - cb) * fa;
            r += (cr - r) * cov; gg += (cg2 - gg) * cov; b += (cb - b) * cov; al = al + (1 - al) * cov;
          }
          if (al <= 0) { d[qq + 3] = 0; continue; }
          const dn = dither(px, py);
          d[qq] = r + dn; d[qq + 1] = gg + dn; d[qq + 2] = b + dn; d[qq + 3] = al * 255;
        }
      }
      P.put(g);
    },
  };
}

/* ------------------------------------------------------------------------------------- mist on the horizon */

/**
 * Mist lying along the horizon: it hides where the land ends and the feet of what stands beyond, in banks and wisps
 * that drift. o: { color, h: 1.6 (degrees it rises to), reach: 1.1 (how far it lies out over the land, in parts of h), amt: 0.9, drift: 0.02 (how fast its banks move along), seed, res: 2 }
 * (add it to 'ground' after the land, or to 'far' after the ridges)
 */
export function mistBand(o) {
  const c = hex(o.color), hgt = ((o.h ?? 1.6) * Math.PI) / 180, amt = o.amt ?? 0.9, seed = (o.seed ?? 1) * 19.3, drift = o.drift ?? 0.02;
  return {
    draw(g, cam, sc, t = 0) {
      const res = o.res ?? 2, P = plate(cam, res), d = P.d, R = cam.R, U = cam.U, F = cam.F, f = cam.f;
      for (let px = 0; px < P.w; px++) {
        const vx = (px + 0.5) * res - cam.cx, az = Math.atan2(F[0] * f + R[0] * vx, F[2] * f + R[2] * vx);
        // how high the mist stands in this direction: banks, with wisps on them
        const ca = Math.cos(az), sa = Math.sin(az);
        const bank = 0.45 + 1.1 * fbm2(ca * 5 + seed + t * drift, sa * 5 + seed * 0.37 + t * drift * 0.6, 3), wisp = fbm2(ca * 23 + seed * 1.7 - t * drift * 2.2, sa * 23 + 2.9 + t * drift, 2) - 0.5;
        for (let py = 0; py < P.h; py++) {
          const vy = -((py + 0.5) * res - cam.cy);
          const dx = F[0] * f + R[0] * vx + U[0] * vy, dy = F[1] * f + R[1] * vx + U[1] * vy, dz = F[2] * f + R[2] * vx + U[2] * vy;
          const el = Math.atan2(dy, Math.hypot(dx, dz)), q = (py * P.w + px) * 4;
          let a = 0;
          if (el >= 0) { const top = hgt * (bank + wisp * 0.5 * (0.4 + el / hgt)); a = el < top ? amt * Math.pow(1 - el / top, 1.5) : 0; }
          else a = amt * Math.exp(el / (hgt * (o.reach ?? 1.1))); // under the horizon it thins out over the far land
          d[q] = c[0]; d[q + 1] = c[1]; d[q + 2] = c[2]; d[q + 3] = a * 255;
        }
      }
      P.put(g);
    },
  };
}

/* ------------------------------------------------------------------------------------------------ grass */

/**
 * Grass standing on painted land, near the lens: tufts of blades, each tuft at its own place on the land, in the land's
 * colour there, stirring in the wind. Farther out the painted land takes over.
 * o: { land (the landPaint it grows on), max: 24 (m it reaches), cell: 0.3 (m between tufts), h: [0.16, 0.4] (m),
 *      amt: 0.9, wind: 1, seed }      (add it to 'ground' after the land)
 */
export function grassPaint(o) {
  const land = o.land, max = o.max ?? 24, cell = o.cell ?? 0.3, hs = o.h ?? [0.16, 0.4], amt = o.amt ?? 0.9, wind = o.wind ?? 1, seed = (o.seed ?? 1) * 7.77;
  const S = land.sun ? V.norm(land.sun.dir) : null, sunC = hex(land.sun?.color ?? '#fff0d0'), sh = S ? Math.hypot(S[0], S[2]) || 1 : 1;
  const hsh = (a, b) => TAB[((a * 73856093) ^ (b * 19349663)) & 65535];
  return {
    draw(g, cam, sc, t = 0) {
      const W = cam.W, H = cam.H, y0 = 0, ch = cam.pos[1] - y0; if (ch < 0.05) return;
      const hd = Math.hypot(cam.F[0], cam.F[2]) || 1e-6, fx = cam.F[0] / hd, fz = cam.F[2] / hd, haze = sc.haze, cool = hex(sc.grade('#2e3550'));
      const cx0 = cam.pos[0] + fx * max * 0.5, cz0 = cam.pos[2] + fz * max * 0.5, r = max * 0.62;
      const x0 = Math.floor((cx0 - r) / cell), x1 = Math.ceil((cx0 + r) / cell), z0 = Math.floor((cz0 - r) / cell), z1 = Math.ceil((cz0 + r) / cell), list = [];
      for (let ix = x0; ix <= x1; ix++) for (let iz = z0; iz <= z1; iz++) {
        const h1 = hsh(ix + 311, iz + 97); if (h1 > amt) continue;
        const wx = (ix + 0.1 + 0.8 * hsh(ix * 3 + 5, iz + 1)) * cell, wz = (iz + 0.1 + 0.8 * hsh(ix + 9, iz * 3 + 2)) * cell;
        const dx = wx - cam.pos[0], dz = wz - cam.pos[2], zc = dx * cam.F[0] - ch * cam.F[1] + dz * cam.F[2];
        if (zc < 0.25) continue;
        const d = Math.hypot(dx, dz); if (d > max) continue;
        // thinning out towards its reach, so no edge shows where it ends
        if (hsh(ix * 7 + 1, iz * 5 + 3) < sstep(max * 0.45, max, d)) continue;
        const sx = cam.cx + (cam.f * (dx * cam.R[0] - ch * cam.R[1] + dz * cam.R[2])) / zc; if (sx < -60 || sx > W + 60) continue;
        const sy = cam.cy - (cam.f * (dx * cam.U[0] - ch * cam.U[1] + dz * cam.U[2])) / zc; if (sy > H + 80 || sy < -10) continue;
        list.push([zc, sx, sy, d, wx, wz, ix, iz]);
      }
      list.sort((a, b) => b[0] - a[0]);
      const lw = Math.sin(t * 0.7) * 0.02; // (a slow breath through all of it)
      g.save();
      for (const [zc, sx, sy, d, wx, wz, ix, iz] of list) {
        const at = land.colourAt(wx, wz, sc);
        if (hsh(ix + 41, iz + 59) < at.bare * 1.15) continue; // little grows on bare earth
        // long grass and short lie in drifts
        const drift = fbm2(wx / 5.5 + seed, wz / 5.5 + 2.2, 2), ppm = cam.f / zc, tall = (hs[0] + (hs[1] - hs[0]) * hsh(ix * 5 + 2, iz * 7 + 4)) * (0.45 + 1.1 * sstep(0.3, 0.7, drift)), hp = tall * ppm; if (hp < 2.2) continue;
        const fa = sc.fog(d), c = at.rgb, n = d < max * 0.35 ? 9 : d < max * 0.7 ? 6 : 4;
        // the dark at its root
        { const rr = tall * 0.42 * ppm, sq = clamp(ch / Math.hypot(ch, d), 0.05, 1); g.fillStyle = `rgba(${(c[0] * 0.4 + (haze[0] - c[0] * 0.4) * fa) | 0},${(c[1] * 0.42 + (haze[1] - c[1] * 0.42) * fa) | 0},${(c[2] * 0.5 + (haze[2] - c[2] * 0.5) * fa) | 0},0.5)`; g.beginPath(); g.ellipse(sx, sy, rr, Math.max(0.6, rr * sq * 0.8), 0, 0, 7); g.fill(); }
        // towards a low sun the blades stand against its light: their tips take it, their bodies go dark
        const tow = S ? Math.max(0, ((wx - cam.pos[0]) * S[0] + (wz - cam.pos[2]) * S[2]) / (d * sh || 1e-6)) : 0;
        const sway = (vnoise(t * 0.55 + wx * 0.21 + seed, wz * 0.17 + t * 0.2) - 0.5 + lw) * 0.5 * wind;
        for (let b = 0; b < n; b++) {
          const q = hsh(ix * 11 + b * 17 + 3, iz * 13 + b * 7 + 1), q2 = hsh(ix * 17 + b * 5 + 8, iz * 3 + b * 29 + 6);
          const k = 0.55 + 0.9 * q2 - 0.25 * tow * (1 - q2), sun = (0.1 + 0.3 * tow) * q2 * q2;
          let r1 = c[0] * k, g1 = c[1] * k, b1 = c[2] * k;
          if (k < 0.8) { const cq = (0.8 - k) * 0.8; r1 += (cool[0] - r1) * cq; g1 += (cool[1] - g1) * cq; b1 += (cool[2] - b1) * cq; }
          r1 += (sunC[0] - r1) * sun; g1 += (sunC[1] - g1) * sun; b1 += (sunC[2] - b1) * sun;
          r1 += (haze[0] - r1) * fa; g1 += (haze[1] - g1) * fa; b1 += (haze[2] - b1) * fa;
          const bh = hp * (0.55 + 0.6 * q), bx = sx + (b - (n - 1) / 2) * hp * 0.085 + (q - 0.5) * hp * 0.1, w = Math.max(0.55, 0.011 * ppm * (0.7 + 0.6 * q2));
          const lean = ((q2 - 0.5) * 0.7 + sway * (0.6 + 0.8 * q)) * bh, tx = bx + lean, ty = sy - bh * (1 - 0.12 * Math.abs(lean / bh));
          g.fillStyle = `rgb(${r1 | 0},${g1 | 0},${b1 | 0})`;
          g.beginPath(); g.moveTo(bx - w, sy); g.quadraticCurveTo(bx - w * 0.6 + lean * 0.35, sy - bh * 0.6, tx, ty); g.quadraticCurveTo(bx + w * 0.6 + lean * 0.35, sy - bh * 0.6, bx + w, sy); g.closePath(); g.fill();
        }
      }
      g.restore();
    },
  };
}
