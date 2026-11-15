// The face of a real head. The head of a real body is a sculpted mesh painted in two flat tones under inked lines:
// seen from the front, a nose leaves no mark on it and the lips are a single line. Here the features of any head are
// found from its own shape (nose, lips, chin), and drawn tones are laid on the skin where they are — the shadow side
// of the nose and the shadow under it, the nostrils, the lips, the shadow under the lower lip, the crease of the lids.
// The tones ride on the skin (CelPainter paint.map), so they turn with the head and move with every expression.

/**
 * Where the features of a head are. pos: rest positions of the skin (x right of the face's middle, y up, z forward);
 * tris: the head's triangles (three vertex numbers each); lm: the body's landmarks (eyeL, mouth); lower: for every
 * vertex how much of it hangs from the jaw (0…1) — with it the line where the lips meet is found from the lips
 * themselves (the body's `mouth` landmark lies a few millimetres under it). Heights are metres from the line of the
 * eyes (below: negative):
 * { nasion (root of the nose), tip, sub (its foot above the lip), lipTop / lipLow (where each lip stands out most),
 *   lipTopEdge / lipEdge (the lips' outer edges), mouth (where the lips meet, in the middle), seamA (how that line
 *   bends: height = mouth + seamA·x²), dip (under the lower lip), chin; half-widths alar (nose), bridge, mouthW (to the
 *   corner of the mouth); relief: the face's relief itself { G, NC, n, cell, top } (forward by row from `top` down and
 *   column out from the middle, `cell` metres each); eyeX, eyeY, eyeZ }.
 */
export function faceMarksOf(pos, tris, lm, lower = null) {
  const eyeY = lm.eyeL[1], eyeX = Math.abs(lm.eyeL[0]), eyeZ = lm.eyeL[2], mark = lm.mouth ? lm.mouth[1] - eyeY : -2.58 * eyeX, m = -mark; let mouth = mark;
  // a relief of the face: how far forward the skin comes over every millimetre of it (the head's triangles seen from the
  // front, the nearest kept), folded about the middle so that both sides speak for one
  const cell = 0.001, X1 = 0.75 * m, top = 0.25 * m, bot = mark - 0.75 * m, NC = Math.ceil(X1 / cell) + 1, n = Math.ceil((top - bot) / cell) + 1, G = new Float32Array(n * NC).fill(-1e9), zmin = eyeZ - 0.06;
  for (let t = 0; t + 2 < tris.length; t += 3) {
    const a = tris[t] * 3, b = tris[t + 1] * 3, c = tris[t + 2] * 3, za = pos[a + 2], zb = pos[b + 2], zc = pos[c + 2]; if (za < zmin && zb < zmin && zc < zmin) continue;
    // in cells: x across (signed), y down from the top
    const ax = pos[a] / cell, ay = (top - (pos[a + 1] - eyeY)) / cell, bx = pos[b] / cell, by = (top - (pos[b + 1] - eyeY)) / cell, cx = pos[c] / cell, cy = (top - (pos[c + 1] - eyeY)) / cell;
    const area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay); if (area > -1e-9 && area < 1e-9) continue;
    const x0 = Math.max(-(NC - 1), Math.ceil(Math.min(ax, bx, cx))), x1 = Math.min(NC - 1, Math.floor(Math.max(ax, bx, cx))), y0 = Math.max(0, Math.ceil(Math.min(ay, by, cy))), y1 = Math.min(n - 1, Math.floor(Math.max(ay, by, cy)));
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
      const w0 = ((bx - x) * (cy - y) - (cx - x) * (by - y)) / area, w1 = ((cx - x) * (ay - y) - (ax - x) * (cy - y)) / area, w2 = 1 - w0 - w1; if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
      const z = w0 * za + w1 * zb + w2 * zc, q = y * NC + (x < 0 ? -x : x); if (z > G[q]) G[q] = z;
    }
  }
  const mid = new Float32Array(n); for (let r = 0; r < n; r++) mid[r] = Math.max(G[r * NC], G[r * NC + 1]);
  for (let r = 0, last = -1; r < n; r++) { if (mid[r] < -1e8) continue; if (last >= 0 && r - last > 1) for (let k = last + 1; k < r; k++) mid[k] = mid[last] + (mid[r] - mid[last]) * (k - last) / (r - last); last = r; }
  const rowOf = (y) => Math.max(0, Math.min(n - 1, Math.round((top - y) / cell))), yOf = (r) => top - r * cell;
  const pick = (ya, yb, sign) => { let best = -1, bv = -1e9; for (let r = rowOf(ya); r <= rowOf(yb); r++) { if (mid[r] < -1e8) continue; const v = sign * mid[r]; if (v > bv + 1e-7) { bv = v; best = r; } } return best < 0 ? (ya + yb) / 2 : yOf(best); };
  // a run of nearly equal heights: its middle (the tip of a nose is rounded)
  const peak = (ya, yb, sign) => { const y = pick(ya, yb, sign), r0 = rowOf(y), v = mid[r0]; let a = r0, b = r0; while (a > rowOf(ya) && Math.abs(mid[a - 1] - v) < 0.0005) a--; while (b < rowOf(yb) && Math.abs(mid[b + 1] - v) < 0.0005) b++; return yOf((a + b) / 2); };
  const tip = peak(-0.25 * m, -0.8 * m, 1), nasion = peak(0.15 * m, tip + 0.2 * m, -1), sub = peak(tip - 0.05 * m, mark + 0.1 * m, -1);
  const lipTop = peak(sub, mark + 0.02 * m, 1), lipLow = peak(mark - 0.03 * m, mark - 0.17 * m, 1), dip = peak(lipLow - 0.02 * m, lipLow - 0.2 * m, -1), chin = peak(dip - 0.02 * m, dip - 0.26 * m, 1);
  // how wide the nose stands from the cheek at a height: out to where the skin has fallen most of the way to the cheek's level
  const widthAt = (y) => { const r = rowOf(y), z0 = mid[r], cz = G[r * NC + Math.min(NC - 1, Math.round(0.4 * m / cell))]; if (cz < -1e8 || z0 < -1e8) return 0.2 * m; const thr = z0 - 0.55 * (z0 - cz); let pz = z0; for (let c = 1; c < NC; c++) { const z = G[r * NC + c]; if (z < -1e8) continue; if (z < thr) return (c - 1 + (pz - thr) / Math.max(1e-6, pz - z)) * cell; pz = z; } return 0.2 * m; };
  const alar = widthAt(tip - 0.04 * m), bridge = Math.min(alar, widthAt((nasion + tip) / 2));
  // the line where the lips meet: the lower edge of the upper lip, column by column out to the corners of the mouth — the
  // lowest skin at the surface that does not hang from the jaw (the lower lip does; a lip's lining lies behind the surface)
  let seamA = 0; const mouthW = eyeX * 0.84;
  if (lower) {
    const colW = 0.0025, NS = Math.floor(mouthW / colW) + 1, up = new Float32Array(NS).fill(1e9), ya = lipTop + 0.002, yb = lipLow - 0.003, seen = new Uint8Array(pos.length / 3);
    for (let t = 0; t < tris.length; t++) {
      const v = tris[t]; if (seen[v]) continue; seen[v] = 1; if (lower[v] > 0.2) continue;
      const x = Math.abs(pos[v * 3]), y = pos[v * 3 + 1] - eyeY, z = pos[v * 3 + 2]; if (y > ya || y < yb) continue; const c = Math.round(x / colW); if (c >= NS) continue;
      const g = G[rowOf(y) * NC + Math.min(NC - 1, Math.round(x / cell))]; if (g > -1e8 && z < g - 0.0025) continue;
      if (y < up[c]) up[c] = y;
    }
    // least squares through the columns: height = c0 + a·x²
    let s0 = 0, s2 = 0, s4 = 0, t0 = 0, t2 = 0; for (let c = 0; c < NS; c++) { if (up[c] > 1e8) continue; const x2 = (c * colW) ** 2; s0 += 1; s2 += x2; s4 += x2 * x2; t0 += up[c]; t2 += up[c] * x2; }
    if (s0 >= 3) { const det = s0 * s4 - s2 * s2; if (Math.abs(det) > 1e-18) { seamA = Math.max(-6, Math.min(6, (s0 * t2 - s2 * t0) / det)); mouth = (t0 - seamA * s2) / s0; } else mouth = t0 / s0; }
  }
  // the ear, in the face's measure unfolded round the side of the head (unfoldX): where its fine folds begin, at the
  // height between the eyes and the nose's foot — the side of a head is modelled coarsely, an ear finely. A head
  // without one is given a place for it by its size.
  const sz = eyeX / 0.0302, zF = eyeZ - FACE_FRONT, fine = [];
  for (let t = 0; t + 2 < tris.length; t += 3) {
    const a = tris[t] * 3, b = tris[t + 1] * 3, c = tris[t + 2] * 3, cy = (pos[a + 1] + pos[b + 1] + pos[c + 1]) / 3 - eyeY; if (cy > 0 || cy < -0.045 * sz) continue;
    const u = Math.min(Math.abs(unfoldX(pos[a], pos[a + 2], zF)), Math.abs(unfoldX(pos[b], pos[b + 2], zF)), Math.abs(unfoldX(pos[c], pos[c + 2], zF))); if (u < 0.09 * sz) continue;
    const e = Math.max(Math.hypot(pos[a] - pos[b], pos[a + 1] - pos[b + 1], pos[a + 2] - pos[b + 2]), Math.hypot(pos[a] - pos[c], pos[a + 1] - pos[c + 1], pos[a + 2] - pos[c + 2]), Math.hypot(pos[b] - pos[c], pos[b + 1] - pos[c + 1], pos[b + 2] - pos[c + 2]));
    if (e < 0.0045 * sz) fine.push(u);
  }
  fine.sort((p, q) => p - q);
  const ear = fine.length >= 12 ? fine[2] : 0.128 * sz;
  return { eyeX, eyeY, eyeZ, m, nasion, tip, sub, lipTop, lipTopEdge: mouth + 0.42 * (sub - mouth), mouth, lipLow, lipEdge: mouth - 0.62 * (mouth - dip), dip, chin, alar, bridge, mouthW, seamA, ear, relief: { G, NC, n, cell, top } };
}

/**
 * The line of a nose, for a head turned from the lens by `turn` radians, `far` (+1 | −1) being the side of the face
 * (as a sign of x) that is turned away: points [x, y, z] in the head's rest space, on the skin, from near the root of
 * the nose down its far side, over the tip and in under it. The line lies where the nose turns away from the eye —
 * out on the nose's slope when the head is a little turned, up on the ridge when it is turned far, moving smoothly
 * between. Facing the lens a nose has no line (its shadows say where it is): an empty list.
 */
export function noseLine(M, turn, far) {
  const R = M.relief; if (!R || turn < 0.12 || !far) return [];
  // where a slanting eye sees the edge of the nose at a height: the skin that reaches furthest towards the far side as
  // the head turns (a nose has no true edge until it is well turned, so the eye is taken a little further round than it is)
  const th = Math.min(1.45, turn + 0.42), cs = Math.cos(th), sn = Math.sin(th), reach = Math.round(M.alar * 1.15 / R.cell);
  const top = M.nasion - 0.004 - (M.nasion - 0.004 - M.tip) * (1 - Math.min(1, (turn - 0.12) / 0.5)), bot = M.tip - 0.42 * (M.tip - M.sub);
  const r0 = Math.round((R.top - top) / R.cell), r1 = Math.round((R.top - bot) / R.cell), xs = [];
  for (let r = r0; r <= r1; r++) {
    // (towards its root a nose narrows and runs out into the brow: the line keeps to the ridge there)
    const up = Math.max(0, Math.min(1, (R.top - r * R.cell - M.tip) / (M.nasion - M.tip))), lim = Math.min(reach, Math.round(M.alar * (1.15 - 0.85 * up) / R.cell)); let best = 0, bv = -1e9; for (let c = 0; c <= lim; c++) { const z = R.G[r * R.NC + c]; if (z < -1e8) continue; const v = c * R.cell * cs + z * sn; if (v > bv) { bv = v; best = c; } }
    xs.push(best * R.cell);
  }
  // a drawn line is one stroke: the edge found row by row is smoothed along the nose, and runs in to the middle under the tip
  const sm = xs.map((_, i) => { let a = 0, w = 0; for (let k = -6; k <= 6; k++) { const j = Math.max(0, Math.min(xs.length - 1, i + k)), g = 7 - Math.abs(k); a += xs[j] * g; w += g; } return a / w; });
  const out = [];
  for (let r = r0, i = 0; r <= r1; r += 2, i += 2) {
    const y = R.top - r * R.cell, under = y < M.tip ? Math.max(0, 1 - (M.tip - y) / (M.tip - bot)) : 1, x = sm[i] * under, c = Math.min(R.NC - 1, Math.round(x / R.cell)), z = R.G[r * R.NC + c];
    if (z > -1e8) out.push([far * x, M.eyeY + y, z]);
  }
  return out;
}

/**
 * The drawn tones of a face as a map for CelPainter (paint.map / paint.maps): map(x, y) → the number of a tone or −1
 * for bare skin, in the face's own measure (x from its middle, y from the line of the eyes: a vertex's rest position).
 * o: { shadeSide: +1 | −1 (the side of the nose away from the light, as a sign of x), eye?: { cx, cy, hw, hh } (where
 * the drawn eyes sit: centre off the middle and above the line of the eyes, half width and height), lips?: 0…1 (how
 * full the lips are drawn; 0 leaves them out), nostrils?: false }
 * Hair and age are painted on the skin as well (they turn with the head and move with the jaw):
 *   beard?: { style: 'full' | 'goatee' | 'chin' | 'mustache', cheek: 0…1 (how high it grows on the cheek), mustache?:
 *     false | { heavy: true (it hangs over the upper lip, its ends down past the corners of the mouth), density },
 *     sideburn?: false, soul?: false (the tuft under the lower lip), rough: 0…1 (how ragged its edge on the cheek
 *     is drawn), gap (metres left bare under the nose's middle), color, shade, density: 0…1 (below 1: stubble) }
 *   scars?: [{ from: [x, y], to: [x, y], w: 0.0012 }] — fine lines between places on the face
 *   lines?: 0…1 — the lines of age: the folds from the nose past the mouth (any), under the eyes (from 0.5), across the
 *     brow (from 0.8); frown?: true — the furrow between the brows
 * x reaches round the side of the head (see unfoldX): the ear begins about 0.13 from the middle (marks.ear).
 * Returns { map, names, lidY } — names[k] is the tone map gives as k; lidY is the height of the lids' crease over the eye.
 */
export function faceDecals(M, o = {}) {
  const names = ['noseSide', 'noseUnder', 'nostril', 'lipUp', 'lipLow', 'lipUnder', 'lid', 'mouthLine'];
  const bd = o.beard && o.beard.style !== 'none' ? o.beard : null, bStyle = bd ? bd.style ?? 'full' : null;
  const kBeard = bd && bStyle !== 'mustache' ? names.push('beard') - 1 : -1, kMust = bd && bd.mustache !== false && bStyle !== 'chin' ? names.push('mustache') - 1 : -1;
  const strands = bd ? bd.strands ?? 0.5 : 0, kStrand = bd && strands > 0 ? names.push('beardStrand') - 1 : -1;
  const heavy = kMust >= 0 && typeof bd.mustache === 'object' && !!bd.mustache.heavy; // a heavy moustache hangs over the upper lip, its ends down past the corners of the mouth
  const scars = (o.scars ?? []).map((q) => ({ a: q.from, b: q.to, hw: (q.w ?? 0.0012) / 2 })), kScar = scars.length ? names.push('scar') - 1 : -1;
  const age = o.lines === true ? 0.6 : o.lines || 0, kAge = age > 0 || o.frown ? names.push('ageLine') - 1 : -1;
  const side = o.shadeSide ?? 1, lips = o.lips ?? 1, nostrils = o.nostrils !== false, nl = M.tip - M.sub;
  // the nose's shadow side: a sliver down the lower half of the nose, widening to the wing, where it runs into the shadow under the tip
  const nTop = M.nasion - 0.55 * (M.nasion - M.tip), nBot = M.tip - 0.62 * nl;
  // under the nose: a rounded shadow hanging below the tip
  const uTop = M.tip - 0.3 * nl, uBot = M.sub + 0.1 * nl, uW = 0.62 * M.alar;
  const nsY = M.tip - 0.6 * nl, nsX = 0.5 * M.alar, nsRx = 0.27 * M.alar, nsRy = 0.075 * nl;
  const e = o.eye ?? { cx: M.eyeX, cy: 0, hw: 0.0215, hh: 0.016 }, lidY = e.cy + e.hh * 0.86 + 0.0032, lidT = 0.00075;
  const ulW = 0.5 * M.mouthW, ulTop = M.lipEdge - 0.12 * (M.lipEdge - M.dip), ulBot = M.dip + 0.18 * (M.lipEdge - M.dip);
  const upH = (M.lipTopEdge - M.mouth) * (0.45 + 0.4 * lips), loH = (M.mouth - M.lipEdge) * (0.55 + 0.4 * lips), mlT = 0.00055;
  const base = (x, y) => {
    if (x > 5) return -1; // the back of the head
    const ax = x < 0 ? -x : x;
    if (y < M.lipTopEdge + 0.002 && y > M.dip - 0.002) {
      if (ax < M.mouthW) {
        const u = ax / M.mouthW, line = M.mouth + M.seamA * x * x, d = y - line;
        if (u < 0.97 && Math.abs(d) < mlT * (1.15 - 0.6 * u * u)) return 7; // where the lips meet
        if (lips > 0) {
          // the upper lip: thinnest in the middle of its bow, fullest a little to each side, running out to the corner
          if (d >= 0 && d < upH * (1 - Math.pow(u, 2.4)) * (0.84 + 0.16 * Math.min(1, u / 0.24))) return 3;
          const ul = ax / (M.mouthW * 0.88); if (d < 0 && ul < 1 && -d < loH * (1 - ul * ul)) return 4;
        }
      }
      if (ax < ulW && y < ulTop) { const u = ax / ulW, k = 1 - u * u; if (y > ulTop - (ulTop - ulBot) * k) return 5; }
      return -1;
    }
    if (y < M.nasion + 0.002 && y > uBot) {
      if (y <= uTop) {
        if (nostrils) { const dx = (ax - nsX) / nsRx, dy = (y - nsY + (ax - nsX) * 0.3) / nsRy; if (dx * dx + dy * dy < 1) return 2; }
        const u = ax / uW, t = (uTop - y) / (uTop - uBot); if (u < 1 && t < Math.sqrt(1 - u * u) && y < uTop - 0.12 * nl * u * u) return 1;
      }
      if (x * side > 0 && y >= nBot && y < nTop) { const t = (nTop - y) / (nTop - nBot), inner = 0.4 * M.alar, outer = inner + (0.98 * M.alar - inner) * Math.pow(Math.min(1, t * 1.25), 1.2); if (ax > inner && ax < outer) return 0; }
      return -1;
    }
    // the crease of the upper lid: a fine arc over each eye
    if (y > e.cy && y < lidY + 0.004) { const u = (ax - e.cx) / (e.hw * 1.02); if (u > -1 && u < 1) { const yc = lidY - e.hh * 0.42 * u * u * (u < 0 ? 0.85 : 1.15); if (Math.abs(y - yc) < lidT * (1.25 - u * u)) return 6; } }
    return -1;
  };
  // ---- hair on the face, scars, the lines of age
  // the beard's upper edge: from beside the mouth it runs back low over the cheek and sweeps up before the ear into the
  // front edge of the sideburn — one line, to where the sideburn meets the hair (a strip about a centimetre wide there)
  const sz = M.eyeX / 0.0302, uxB = (M.ear ?? 0.128 * sz) - 0.004, uxF = uxB - 0.0095 * sz, sbTop = 0.004;
  const uxM = M.mouthW + 0.012, ck = bd ? bd.cheek ?? 0.5 : 0, rough = bd ? bd.rough ?? 0.6 : 0;
  const yM = M.mouth + 0.004 + 0.006 * ck, yS = -0.047 + 0.03 * ck, pw = 3.2 - 1.6 * ck, gap = bd ? bd.gap ?? 0 : 0, yTop = bd && bd.sideburn === false ? yS : sbTop;
  const tri = (v) => { const f = v - Math.floor(v); return f < 0.5 ? 4 * f - 1 : 3 - 4 * f; };
  const cheekY = (ax) => { const t = Math.min(1, Math.max(0, (ax - uxM) / (uxF - uxM))), t4 = t * t * t * t; return yM + (yTop - yM) * Math.pow(t, pw) + rough * (0.0013 * tri(ax / 0.0037) + 0.0008 * tri(ax / 0.0023 + 0.3)) * Math.min(1, t * 6) * (1 - t4); };
  const mustTop = (ax) => M.sub - 0.0012 - 0.0045 * (ax / uxM) * (ax / uxM);
  const hair = (ax, y) => {
    // the moustache: from under the nose down to the upper lip, thinner towards the corners of the mouth, where it turns down
    if (kMust >= 0 && ax < uxM && ax >= gap && y < mustTop(ax)) {
      const lo = ax < M.mouthW * 0.85 ? (heavy ? M.mouth + M.seamA * ax * ax + 0.0009 : M.lipTopEdge + 0.0008) : bStyle === 'mustache' ? (heavy ? M.mouth - 0.012 - 0.3 * (ax - M.mouthW * 0.85) : M.mouth - 0.004 - 0.02 * (ax - M.mouthW * 0.85)) : M.mouth + 0.0045;
      if (y > lo && (bStyle !== 'mustache' || ax < M.mouthW + (heavy ? 0.0085 : 0.007))) return kMust;
    }
    if (kBeard < 0) return -1;
    if (bStyle === 'chin') return ax < 0.021 * (1 - 0.35 * Math.min(1, Math.max(0, (M.dip - y) / 0.03))) && y < M.dip + 0.001 ? kBeard : -1;
    if (ax < uxM) {
      // the chin and under it; under the lower lip the beard closes in an arc, leaving a crescent of bare skin below the lip
      const lw = M.mouthW + 0.0015, q = Math.min(1, ax / lw);
      if (y < Math.max(M.dip + 0.001, M.lipEdge - 0.003 - 0.0045 * (1 - q * q))) return kBeard;
      if (ax >= lw && y < M.mouth + 0.0045) return kBeard;                        // beside the mouth, down from the moustache
      if (bd.soul !== false && y < M.lipEdge - 0.0012 && ax < 0.0052 * (0.45 + 0.55 * (M.lipEdge - y) / (M.lipEdge - M.dip))) return kBeard; // the tuft under the lip
      return -1;
    }
    if (bStyle === 'goatee') return ax < uxM + 0.007 * Math.min(1, Math.max(0, (M.mouth - y) / 0.02)) && y < yM - 0.004 ? kBeard : -1;
    if (ax < uxF) return y < cheekY(ax) ? kBeard : -1;
    // the sideburn, up to the hair; under the lobe of the ear the beard runs a little further back, along the jaw's hind edge
    if (ax < uxB) return y < yTop ? kBeard : -1;
    return ax < uxB + 0.012 * sz * Math.min(1, Math.max(0, (-0.05 * sz - y) / (0.022 * sz))) ? kBeard : -1;
  };
  // strands: fine darker strokes through the beard the way it grows — straight down the chin, down and forward on the
  // cheek — each a dash with a beginning and an end, no two rows alike
  const sPitch = 0.0031, sWide = 0.1 + 0.14 * strands, hash = (n) => { const v = Math.sin(n * 127.1 + 31.7) * 43758.5453; return v - Math.floor(v); };
  const strand = (ax, y) => {
    const c = (ax - 0.28 * Math.min(1, Math.max(0, (ax - 0.02) / 0.03)) * (y - M.chin)) / sPitch, row = Math.floor(c), f = c - row; if (f > sWide) return false;
    const h = hash(row), along = y / (0.009 + 0.006 * h) + h * 7; return along - Math.floor(along) < 0.42 + 0.25 * strands;
  };
  const nearSeg = (x, y, a, b, hw) => { const dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy || 1e-12, u = ((x - a[0]) * dx + (y - a[1]) * dy) / l2; if (u < 0 || u > 1) return false; const px = a[0] + dx * u - x, py = a[1] + dy * u - y, w = hw * (0.35 + 0.65 * Math.sin(Math.PI * u)); return px * px + py * py < w * w; };
  const foldA = [M.alar + 0.004, M.sub + 0.004], foldB = [M.mouthW + 0.009, M.mouth - 0.004], foldC = [(foldA[0] + foldB[0]) / 2 + 0.0022, (foldA[1] + foldB[1]) / 2];
  const lines = (ax, y) => {
    if (age > 0) {
      // the fold from the wing of the nose down past the corner of the mouth (bowed a little outwards)
      if (nearSeg(ax, y, foldA, foldC, 0.00075) || nearSeg(ax, y, foldC, foldB, 0.00075)) return kAge;
      if (age >= 0.5) { const u = (ax - e.cx) / (e.hw * 0.85); if (u > -1 && u < 1) { const yc = e.cy - e.hh * 1.5 - 0.0028 * (1 - u * u) + 0.0012 * u; if (Math.abs(y - yc) < 0.00045 * (1.2 - u * u)) return kAge; } }
      if (age >= 0.8) for (const [yy, hw] of [[0.047, 0.03], [0.057, 0.024]]) { if (ax < hw) { const yc = yy - 0.0035 * (ax / hw) * (ax / hw); if (Math.abs(y - yc) < 0.00045 * (1.1 - (ax / hw) * (ax / hw))) return kAge; } }
    }
    if (o.frown && nearSeg(ax, y, [0.0052, 0.0105], [0.0068, 0.0265], 0.0007)) return kAge;
    return -1;
  };
  const map = kBeard < 0 && kMust < 0 && kScar < 0 && kAge < 0 ? base : (x, y) => {
    if (x > 5 || x < -5) return -1;
    const ax = x < 0 ? -x : x;
    if (heavy && y > M.mouth + M.seamA * ax * ax + 0.0009 && y < M.lipTopEdge + 0.003 && ax < M.mouthW * 0.85 && ax >= gap) return kMust; // (over the upper lip)
    const k = base(x, y); if (k >= 0) return k;
    if (bd) { const h = hair(ax, y); if (h >= 0) return h === kBeard && kStrand >= 0 && strand(ax, y) ? kStrand : h; }
    for (const q of scars) if (nearSeg(x, y, q.a, q.b, q.hw)) return kScar;
    return kAge >= 0 ? lines(ax, y) : -1;
  };
  return { map, names, lidY };
}

/** How far behind the eyes the face's front ends and its side begins (metres): unfoldX's zFront is the eyes' z less this. */
export const FACE_FRONT = 0.02;
/**
 * The side of the head, unfolded into the face's measure: skin in front of `zFront` keeps its x; skin behind it lies
 * further out from the middle by as much as it lies behind — so the cheek runs on round the jaw to the ear in one
 * measure, and a beard can be laid along it.
 */
export function unfoldX(x, z, zFront) { const back = zFront - z; return back <= 0 ? x : x < 0 ? x - back : x + back; }

/** A paint for every drawn tone of faceDecals: its lit tone and its tone in shadow, from the skin's own two tones. o: { lips, lip, nose (0…1: how strongly the nose's shadows are laid in) } */
export function decalPaints(D, skin, shade, o = {}) {
  const mix = (a, b, k) => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
  const deep = mul(shade, 0.9), lipC = o.lip ?? [168, 86, 88], k = o.lips ?? 1, nk = o.nose ?? 1, nose = (c) => mix(skin, c, nk); // nose: how strongly the nose's shadows are laid in (1 = full; a fine nose is drawn lightly)
  const tones = {
    noseSide: [nose(mix(skin, shade, 0.92)), nk < 1 ? mix(shade, deep, nk) : deep],
    noseUnder: [nose(mix(shade, deep, 0.45)), nk < 1 ? mix(shade, mul(shade, 0.84), nk) : mul(shade, 0.84)],
    nostril: [nose(mul(shade, 0.6)), nk < 1 ? mix(shade, mul(shade, 0.52), nk) : mul(shade, 0.52)],
    lipUp: [mix(mix(skin, shade, 0.55), lipC, 0.34 * k), mix(deep, lipC, 0.3 * k)],
    lipLow: [mix(mix(skin, shade, 0.1), lipC, 0.24 * k), mix(shade, lipC, 0.26 * k)],
    lipUnder: [mix(skin, shade, 0.85), deep],
    lid: [mul(shade, 0.74), mul(shade, 0.66)],
    mouthLine: [mul(shade, 0.42), mul(shade, 0.38)],
  };
  // hair on the face in its own colour (thinned towards the skin for stubble); a scar and the lines of age from the skin's tones
  const bd = o.beard ?? {}, bc = bd.color ?? [40, 34, 34], bs = bd.shade ?? mul(bc, 0.62), dn = bd.density ?? 1, md = typeof bd.mustache === 'object' ? bd.mustache.density ?? dn : dn;
  tones.beard = [mix(skin, bc, dn), mix(shade, bs, dn)];
  tones.mustache = [mix(skin, bc, md), mix(shade, bs, md)];
  tones.beardStrand = [mix(skin, mix(bc, bs, 0.7), dn), mix(shade, mul(bs, 0.8), dn)];
  tones.scar = [mix(mix(skin, shade, 0.75), [150, 72, 70], 0.42), mix(deep, [120, 52, 52], 0.35)];
  tones.ageLine = [mix(skin, shade, 0.95), mul(shade, 0.86)];
  return D.names.map((n) => ({ fill: tones[n][0].map(Math.round), shade: tones[n][1].map(Math.round), line: false, recvHair: true }));
}
