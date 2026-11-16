// Hair as drawn hair is built: clumps. Each clump is a tapered, slightly thick ribbon that starts on the scalp and
// follows the flow of the cut — off the crown, over the forehead, down the neck — to a point. Clumps ride on the head,
// lag behind it and swing back; long ones hang and are pulled by their own weight. They are meshes like the body, so
// hands, shoulders and blades cover and are covered by them correctly.
import { clamp, rng } from './core.js';
import { onBone, dirOnBone } from './body.js';

/** Ready cuts. Lengths in metres: top, bangs (over the forehead), sides, back; spike 0…1 (stands away from the head); mess 0…1. */
export const HAIRCUTS = {
  bald: null,
  buzz: { top: 0.012, bangs: 0.01, sides: 0.008, back: 0.008, spike: 0.3, mess: 0.1, n: 40, w: 0.03 },
  short: { top: 0.05, bangs: 0.045, sides: 0.035, back: 0.04, spike: 0.25, mess: 0.35, n: 54, w: 0.034 },
  neat: { top: 0.06, bangs: 0.05, sides: 0.03, back: 0.035, spike: 0.05, mess: 0.12, n: 50, w: 0.036, part: 0.6 },
  spiky: { top: 0.1, bangs: 0.085, sides: 0.07, back: 0.09, spike: 0.62, mess: 0.6, n: 60, w: 0.036 },
  shaggy: { top: 0.085, bangs: 0.1, sides: 0.11, back: 0.14, spike: 0.2, mess: 0.55, n: 62, w: 0.036 },
  bob: { top: 0.09, bangs: 0.085, sides: 0.2, back: 0.2, spike: 0.02, mess: 0.15, n: 60, w: 0.04 },
  long: { top: 0.1, bangs: 0.09, sides: 0.42, back: 0.5, spike: 0.02, mess: 0.2, n: 64, w: 0.04 },
  tied: { top: 0.07, bangs: 0.08, sides: 0.06, back: 0.05, spike: 0.08, mess: 0.3, n: 54, w: 0.036, tail: { len: 0.42, w: 0.06, at: 'high' } }, // tail.tie: true binds it with a cord (design.hair.tie: its colour)
  topknot: { top: 0.05, bangs: 0.03, sides: 0.04, back: 0.04, spike: 0.02, mess: 0.15, n: 48, w: 0.036, tail: { len: 0.16, w: 0.05, at: 'top' } },
};
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k], dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]], len = (a) => Math.hypot(a[0], a[1], a[2]);
const norm = (a) => { const l = len(a) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };

const slerp = (a, b, t) => { const d = Math.max(-1, Math.min(1, dot(a, b))), th = Math.acos(d); if (th < 1e-4) return norm(add(mul(a, 1 - t), mul(b, t))); if (th > Math.PI - 1e-3) { const up = Math.abs(a[1]) < 0.9 ? [0, 1, 0] : [0, 0, -1], m = norm(sub(up, mul(a, dot(up, a)))); return t < 0.5 ? slerp(a, m, t * 2) : slerp(m, b, t * 2 - 1); } const k = 1 / Math.sin(th); return norm(add(mul(a, Math.sin((1 - t) * th) * k), mul(b, Math.sin(t * th) * k))); };
const dirOf = (az, el) => [Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az)];
/** points along a line, evenly spaced by length: n + 1 of them */
function evenly(line, n) {
  const acc = [0]; for (let i = 1; i < line.length; i++) acc.push(acc[i - 1] + len(sub(line[i], line[i - 1])));
  const total = acc[acc.length - 1], out = []; let j = 0;
  for (let i = 0; i <= n; i++) { const want = total * i / n; while (j < line.length - 2 && acc[j + 1] < want) j++; const span = acc[j + 1] - acc[j] || 1, u = clamp((want - acc[j]) / span); out.push(add(mul(line[j], 1 - u), mul(line[j + 1], u))); }
  return { pts: out, len: total };
}

/**
 * The head as hair meets it: how far its surface — the skin of the head and the scalp — lies from the middle of the
 * skull in every direction (rest pose). A direction is two angles in radians: `az` round from straight ahead (positive
 * towards the head's own left, the side of its left eye) and `el` up from level.
 * Returns { c: the middle, r(az, el): metres to the surface, at(az, el, lift = 0): the place on it (lifted off it by so
 * much), out(p, margin): p itself, or p moved straight out until it is `margin` clear of the head }.
 */
export function headSurface(B) {
  if (B.headSurface) return B.headSurface;
  const L = B.landmarks, c = [0, (L.crown[1] + L.nape[1]) / 2 - 0.005, L.eyeL[2] - 0.105];
  const NA = 120, NE = 60, G = new Float32Array(NA * NE), cellA = (2 * Math.PI) / NA;
  const put = (x, y, z) => { const dx = x - c[0], dy = y - c[1], dz = z - c[2], r = Math.hypot(dx, dy, dz); if (r < 1e-6) return; const a = Math.min(NA - 1, Math.floor((Math.atan2(dx, dz) + Math.PI) / cellA)), e = Math.min(NE - 1, Math.floor((Math.asin(dy / r) + Math.PI / 2) / Math.PI * NE)), q = e * NA + a; if (r > G[q]) G[q] = r; };
  for (const p of B.parts) {
    const skin = p.material === 'skin', tris = skin ? p.idxHead : p.name === 'haircap' ? p.idx : null; if (!tris) continue;
    const P = p.rest0 ?? p.pos;
    for (let t = 0; t + 2 < tris.length; t += 3) {
      const a = tris[t] * 3, b = tris[t + 1] * 3, d = tris[t + 2] * 3;
      const edge = Math.max(Math.hypot(P[a] - P[b], P[a + 1] - P[b + 1], P[a + 2] - P[b + 2]), Math.hypot(P[a] - P[d], P[a + 1] - P[d + 1], P[a + 2] - P[d + 2]), Math.hypot(P[b] - P[d], P[b + 1] - P[d + 1], P[b + 2] - P[d + 2]));
      const K = Math.min(10, Math.ceil(edge / 0.09 / cellA * 1.6) + 1);
      for (let i = 0; i <= K; i++) for (let j = 0; j <= K - i; j++) { const u = i / K, v = j / K, w = 1 - u - v; put(P[a] * w + P[b] * u + P[d] * v, P[a + 1] * w + P[b + 1] * u + P[d + 1] * v, P[a + 2] * w + P[b + 2] * u + P[d + 2] * v); }
    }
  }
  // directions nothing was found in (under the chin, a scalp without a face) take what lies round them
  for (let it = 0; it < NA; it++) {
    const add2 = []; for (let e = 0; e < NE; e++) for (let a = 0; a < NA; a++) { if (G[e * NA + a] > 0) continue; let s2 = 0, n = 0; for (const [da, de] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const ee = e + de; if (ee < 0 || ee >= NE) continue; const v = G[ee * NA + (a + da + NA) % NA]; if (v > 0) { s2 += v; n++; } } if (n) add2.push([e * NA + a, s2 / n]); }
    if (!add2.length) break; for (const [q, v] of add2) G[q] = v;
  }
  // a little smoothing, never inwards: hair lies over an ear or a brow, it does not follow every fold
  const S = Float32Array.from(G);
  for (let e = 0; e < NE; e++) for (let a = 0; a < NA; a++) { let s2 = 0, n = 0; for (let de = -1; de <= 1; de++) for (let da = -1; da <= 1; da++) { const ee = e + de; if (ee < 0 || ee >= NE) continue; s2 += G[ee * NA + (a + da + NA) % NA]; n++; } S[e * NA + a] = Math.max(G[e * NA + a], s2 / n); }
  const r = (az, el) => {
    const fa = (az + Math.PI) / cellA - 0.5, fe = clamp((el + Math.PI / 2) / Math.PI * NE - 0.5, 0, NE - 1), a0 = Math.floor(fa), e0 = Math.min(NE - 2, Math.floor(fe)), ua = fa - a0, ue = fe - e0;
    const g = (a, e) => S[e * NA + ((a % NA) + NA) % NA];
    return (g(a0, e0) * (1 - ua) + g(a0 + 1, e0) * ua) * (1 - ue) + (g(a0, e0 + 1) * (1 - ua) + g(a0 + 1, e0 + 1) * ua) * ue;
  };
  const at = (az, el, lift = 0) => add(c, mul(dirOf(az, el), r(az, el) + lift));
  const out = (p, margin = 0) => { const d = sub(p, c), l = len(d); if (l < 1e-6) return p; const el = Math.asin(clamp(d[1] / l, -1, 1)); if (el < -1.0) return p; const need = r(Math.atan2(d[0], d[2]), el) + margin; return l >= need ? p : add(c, mul(d, need / l)); };
  return (B.headSurface = { c, r, at, out });
}

/** Build the clumps of a cut on a body's scalp (rest pose). spec: a name of HAIRCUTS or its fields; seed varies the clumps. */
/**
 * The scalp's triangles without its sideburns (design.hair.sideburns: false — a little girl, a woman with her hair drawn
 * back): none that lies below the line of the eyes in front of the ears. Kept on the scalp once made.
 */
export function capWithoutSideburns(B) {
  const cap = B.parts.find((p) => p.name === 'haircap'), L = B.landmarks;
  if (!cap || !L?.eyeL || !L?.crown) return cap ? cap.idx : null;
  if (cap.idxNoBurns) return cap.idxNoBurns;
  const P = cap.rest0 ?? cap.pos, I = cap.idx, eyeY = L.eyeL[1], midZ = (L.crown[2] + L.eyeL[2]) / 2, out = [];
  for (let t = 0; t + 2 < I.length; t += 3) { const y = (P[I[t] * 3 + 1] + P[I[t + 1] * 3 + 1] + P[I[t + 2] * 3 + 1]) / 3, z = (P[I[t] * 3 + 2] + P[I[t + 1] * 3 + 2] + P[I[t + 2] * 3 + 2]) / 3; if (!(y < eyeY && z > midZ)) out.push(I[t], I[t + 1], I[t + 2]); }
  return (cap.idxNoBurns = Uint32Array.from(out));
}
export function makeHair(B, spec, seed = 1) {
  const cut = typeof spec === 'string' ? HAIRCUTS[spec] : spec && spec.cut ? { ...HAIRCUTS[spec.cut], ...spec } : spec;
  const cap = B.parts.find((p) => p.name === 'haircap'), L = B.landmarks;
  if (!cut || !cap || !L.crown) return null;
  const R = rng(seed * 31 + 7), P0 = cap.rest0 ?? cap.pos, n = cap.n;
  // scalp normals
  const nr = new Float32Array(n * 3);
  for (let t = 0; t < cap.idx.length; t += 3) { const a = cap.idx[t] * 3, b = cap.idx[t + 1] * 3, c = cap.idx[t + 2] * 3, u = [P0[b] - P0[a], P0[b + 1] - P0[a + 1], P0[b + 2] - P0[a + 2]], v = [P0[c] - P0[a], P0[c + 1] - P0[a + 1], P0[c + 2] - P0[a + 2]], x = cross(u, v); for (const q of [a, b, c]) { nr[q] += x[0]; nr[q + 1] += x[1]; nr[q + 2] += x[2]; } }
  const ctr = [0, (L.crown[1] + L.nape[1]) / 2 - 0.005, (L.crown[2] + L.eyeL[2]) / 2 - 0.02];
  const pt = (v) => [P0[v * 3], P0[v * 3 + 1], P0[v * 3 + 2]], nrm = (v) => { let q = norm([nr[v * 3], nr[v * 3 + 1], nr[v * 3 + 2]]); if (dot(q, sub(pt(v), ctr)) < 0) q = mul(q, -1); return q; };
  // roots: spread evenly over the scalp (each new one as far as possible from those chosen)
  const N = Math.min(n, cut.n ?? 56), roots = [], dmin = new Float32Array(n).fill(1e9);
  let cur = 0; { let best = -1e9; for (let v = 0; v < n; v++) if (P0[v * 3 + 1] > best) { best = P0[v * 3 + 1]; cur = v; } }
  for (let i = 0; i < N; i++) { roots.push(cur); const c = pt(cur); let far = -1, fv = 0; for (let v = 0; v < n; v++) { const d = Math.hypot(P0[v * 3] - c[0], P0[v * 3 + 1] - c[1], P0[v * 3 + 2] - c[2]); if (d < dmin[v]) dmin[v] = d; if (dmin[v] > far) { far = dmin[v]; fv = v; } } cur = fv; }
  const whorl = [0.012 * (cut.part ? -Math.sign(cut.part) : 1), L.crown[1] - 0.01, L.crown[2] - 0.01], eyeY = L.eyeL[1], faceZ = L.eyeL[2];
  // the head and what is under it, as shapes hair cannot pass through: the skull (generous), the neck widening into shoulders
  const sk = [0, ctr[1], 0.03], rx = 0.083, ry = 0.12, rz = 0.117, neckY = L.neckY ?? (L.nape[1] - 0.09);
  const outside = (p) => {
    let q = p; const d = [(q[0] - sk[0]) / rx, (q[1] - sk[1]) / ry, (q[2] - sk[2]) / rz], l = len(d);
    if (l < 1.03 && l > 1e-6) { const k = 1.03 / l; q = [sk[0] + d[0] * k * rx, sk[1] + d[1] * k * ry, sk[2] + d[2] * k * rz]; }
    // the face stays clear: nothing but a fringe passes in front of the ears (hair goes back over them)
    if (!cut.overFace) { const f = [(q[0]) / 0.086, (q[1] - (eyeY - 0.035)) / 0.105, (q[2] - (faceZ - 0.02)) / 0.1], fl = len(f); if (fl < 1 && q[1] < eyeY + 0.012) { const sgn = q[0] >= 0 ? 1 : -1; q = [sgn * Math.max(Math.abs(q[0]), 0.086 * Math.sqrt(Math.max(0.05, 1 - f[1] * f[1])) + 0.004), q[1], Math.min(q[2], faceZ - 0.085)]; } }
    if (q[1] < L.nape[1] + 0.01) { const rr = 0.066 + (0.2 - 0.066) * clamp((neckY + 0.015 - q[1]) / 0.08) * (q[1] < neckY + 0.015 ? 1 : 0), hx = q[0], hz = q[2] + 0.02, hl = Math.hypot(hx * 0.8, hz); if (hl < rr) { const k = rr / (hl || 1e-6); q = [hx * k, q[1], hz * k - 0.02]; } }
    return q;
  };
  const clumps = [], S = 8;
  // a tail gathered at the back or on top (a strand that swings as one)
  let tail = null;
  if (cut.tail) { const place = Array.isArray(cut.tail.at), at = place ? headSurface(B).at(cut.tail.at[0] * Math.PI / 180, cut.tail.at[1] * Math.PI / 180, 0.01) : cut.tail.at === 'top' ? [0, L.crown[1] + 0.012, L.crown[2] + 0.005] : cut.tail.at === 'low' ? [0, L.nape[1] + 0.03, L.nape[2] - 0.03] : [0, (L.crown[1] + L.nape[1]) / 2 + 0.035, L.nape[2] - 0.035]; const tie = cut.tail.tie; tail = { at, len: cut.tail.len, w: cut.tail.w ?? 0.05, dir: cut.tail.at === 'top' ? [0, 1, -0.25] : [0, cut.tail.up ?? -0.15, -1], tie: !!tie, ends: tie && typeof tie === 'object' ? { n: tie.ends ?? 0, len: tie.len ?? 0.2 } : null, bow: tie && typeof tie === 'object' && tie.bow > 0 ? tie.bow : 0, locks: cut.tail.locks ?? 0, seed: seed * 17 + 3, st: null }; }
  const HS = (cut.flow === 'tie' && tail) || cut.locks?.length ? headSurface(B) : null;
  const frameAlong = (pts) => { const wides = [], nrms = []; for (let i = 0; i < pts.length; i++) { const tg = norm(sub(pts[Math.min(pts.length - 1, i + 1)], pts[Math.max(0, i - 1)])); let out = sub(pts[i], HS.c); out = sub(out, mul(tg, dot(out, tg))); out = len(out) < 1e-4 ? [0, 0, 1] : norm(out); const w = norm(cross(tg, out)); wides.push(w); nrms.push(norm(cross(w, tg))); } return { wides, nrms }; };
  // hair swept back: with flow: 'tie' every clump runs from its root over the head to where the tail is tied
  const toTie = cut.flow === 'tie' && tail ? (() => { tail.at = HS.out(tail.at, 0.012); return tail.at; })() : null;
  // (the scalp is a cap cut out of the body's own quads: its edge is a staircase. Swept back, nothing hangs over that
  // edge — so the hairline is made of hair: a clump starts at every corner of it, tucked just inside the cap's own smooth rim)
  const rim = new Map();
  if (toTie) {
    const cnt = new Map(), key = (a, b) => (a < b ? a * 1e6 + b : b * 1e6 + a), nbr = new Map(), link = (a, b) => (nbr.get(a) ?? nbr.set(a, new Set()).get(a)).add(b);
    for (let t = 0; t < cap.idx.length; t += 3) for (let e = 0; e < 3; e++) { const a = cap.idx[t + e], b = cap.idx[t + (e + 1) % 3], k = key(a, b); cnt.set(k, (cnt.get(k) ?? 0) + 1); link(a, b); link(b, a); }
    const on = new Set(); for (const [k, c] of cnt) if (c === 1) { on.add(Math.floor(k / 1e6)); on.add(k % 1e6); }
    for (const v of on) { const p = pt(v); let in2 = [0, 0, 0], m = 0; for (const q of nbr.get(v)) if (!on.has(q)) { in2 = add(in2, pt(q)); m++; } let outw = m ? sub(p, mul(in2, 1 / m)) : [0, -1, 0]; const nn = nrm(v); outw = sub(outw, mul(nn, dot(outw, nn))); rim.set(v, add(add(p, mul(len(outw) < 1e-5 ? [0, 1, 0] : norm(outw), -0.005)), mul(nn, -0.003))); }
  }
  for (const v of toTie ? [...new Set([...roots, ...rim.keys()])] : []) {
    const r0 = rim.get(v) ?? pt(v), d0 = norm(sub(r0, HS.c)), d1 = norm(sub(toTie, HS.c)), lift = (cut.lift ?? 0.006) * (0.7 + 0.6 * R()), vol = (cut.vol ?? 0.008) * (0.5 + R()), ms = (cut.mess ?? 0.3) * (R() - 0.5) * 0.16;
    const side = norm(cross(d0, d1)), line = [], M = 24;
    for (let i = 0; i <= M; i++) {
      const u = i / M; let d = slerp(d0, d1, u); if (len(side) > 0.5) d = norm(add(d, mul(side, ms * Math.sin(Math.PI * u))));
      const el = Math.asin(clamp(d[1], -1, 1)), az = Math.atan2(d[0], d[2]), on = add(HS.c, mul(d, HS.r(az, el) + lift * Math.min(1, u * 6) + vol * Math.sin(Math.PI * u)));
      const k = clamp((u - 0.72) / 0.28), kk = k * k * (3 - 2 * k); line.push(add(mul(on, 1 - kk), mul(toTie, kk)));
    }
    line[0] = rim.has(v) ? r0 : add(r0, mul(nrm(v), -0.004));
    const ev = evenly(line, S), fr = frameAlong(ev.pts);
    clumps.push({ pts: ev.pts, wides: fr.wides, nrms: fr.nrms, w: (cut.w ?? 0.036) * (0.85 + 0.4 * R()), len: ev.len, th: 0.008 + 0.004 * R(), stiff: 3.2, ph: R() * 6.28, back: true });
  }
  // designed locks: each laid over the head from one place on it to another (degrees round from straight ahead — positive
  // to the head's own left — and up from level), lifted off it, and falling freely for `hang` metres past its end
  for (const k of cut.locks ?? []) {
    const D = Math.PI / 180, d0 = dirOf(k.from[0] * D, k.from[1] * D), d1 = dirOf(k.to[0] * D, k.to[1] * D), dv = k.via ? dirOf(k.via[0] * D, k.via[1] * D) : null;
    const lf = Array.isArray(k.lift) ? k.lift : [0.002, (k.lift ?? 0.01) * 1.1, k.lift ?? 0.01], lb = 2 * lf[1] - (lf[0] + lf[2]) / 2, liftAt = (u) => lf[0] * (1 - u) * (1 - u) + 2 * lb * u * (1 - u) + lf[2] * u * u;
    const M = 40, rad = [], dirs = [];
    for (let i = 0; i <= M; i++) { const u = i / M, d = dv ? slerp(slerp(d0, dv, u), slerp(dv, d1, u), u) : slerp(d0, d1, u); dirs.push(d); rad.push(HS.r(Math.atan2(d[0], d[2]), Math.asin(clamp(d[1], -1, 1)))); }
    // (the head's own bumps — a brow, an ear — are smoothed along the lock: hair lies over them in one sweep)
    const line = dirs.map((d, i) => { let a = 0, n = 0, mx = 0; for (let j = -4; j <= 4; j++) { const q = Math.max(0, Math.min(M, i + j)); a += rad[q]; n++; if (Math.abs(j) <= 1) mx = Math.max(mx, rad[q]); } const u = i / M, sm = a / n, ends = Math.min(1, Math.min(u, 1 - u) * 8); return add(HS.c, mul(d, Math.max(rad[i], sm * ends + rad[i] * (1 - ends), mx - 0.004) + liftAt(u))); });
    if ((k.hang ?? 0) > 0) {
      let p = line[M], dir = norm(sub(line[M], line[M - 2])); const step = 0.005, clear = Math.max(0.004, lf[2] * 0.6);
      for (let g2 = 0; g2 < k.hang; g2 += step) { dir = norm(add(mul(dir, 0.72), [0, -0.28, 0])); p = HS.out(add(p, mul(dir, step)), clear); line.push(p); }
    }
    const ev = evenly(line, S), fr = frameAlong(ev.pts);
    clumps.push({ pts: ev.pts, wides: fr.wides, nrms: fr.nrms, w: k.w ?? 0.03, len: ev.len, th: k.th ?? 0.011, stiff: k.stiff ?? (k.hang ? 1 : 2.2), ph: R() * 6.28, lock: true, taper: k.taper ?? null });
  }
  for (const v of toTie ? [] : roots) {
    const r0 = pt(v), nn = nrm(v);
    const front = clamp((r0[2] - (faceZ - 0.085)) / 0.06), high = clamp((r0[1] - (eyeY + 0.03)) / 0.06), side = clamp(Math.abs(r0[0]) / 0.06), back = clamp((ctr[2] - 0.01 - r0[2]) / 0.05);
    // which length rules here
    const wB = front * high * (1 - side * 0.5), wS = side * (1 - front * 0.4), wK = back * (1 - side * 0.6), wT = Math.max(0.05, 1 - Math.max(wB, wS, wK));
    const ws = wB + wS + wK + wT;
    let lenC = ((cut.bangs ?? 0.06) * wB + (cut.sides ?? 0.05) * wS + (cut.back ?? 0.05) * wK + (cut.top ?? 0.06) * wT) / ws * (0.85 + 0.3 * R());
    // a fringe stops above the eyes unless the cut says otherwise
    if (wB > 0.45 && !cut.overEyes) lenC = Math.min(lenC, Math.max(0.018, (r0[1] - (eyeY + 0.019)) * 1.25));
    // the flow: off the whorl along the scalp; a fringe comes forward and down; a parting throws the top to one side
    let rad = sub(r0, whorl); rad = sub(rad, mul(nn, dot(rad, nn))); rad = len(rad) < 1e-4 ? [0, 0, 1] : norm(rad);
    let flow = add(mul(rad, 1 - 0.5 * wB), [0, -0.55 * (1 - wT * 0.6), 0.5 * wB]);
    if (cut.part) flow = add(flow, [cut.part * 0.9 * wT * (1 - wB * 0.3), 0, 0]);
    flow = add(flow, [0, 0, -0.7 * wS * (1 - wB)]); // at the sides the hair is swept back
    const ms = cut.mess ?? 0.3; flow = add(flow, [(R() - 0.5) * ms * 0.8, (R() - 0.5) * ms * 0.4, (R() - 0.5) * ms * 0.8]);
    flow = sub(flow, mul(nn, dot(flow, nn) * 0.85)); flow = norm(flow);
    const spike = (cut.spike ?? 0.2) * (0.6 + 0.8 * R()) * (1 - 0.6 * wB) * (lenC < 0.16 ? 1 : 0.2);
    const seg = lenC / S, pts = [], wides = [], nrms = [];
    let p = add(r0, mul(nn, -0.004)), dir = norm(add(mul(flow, 1 - spike * 0.75), mul(nn, 0.1 + spike * 1.15)));
    for (let i = 0; i <= S; i++) {
      pts.push(p);
      // weight: the further from the root, the more a clump hangs (stiff spikes hold their line)
      const pull = Math.min(0.9, (seg / 0.03) * 0.42 * (1 - spike * 0.8));
      dir = norm(add(mul(dir, 1 - pull), mul([0, -1, 0], pull)));
      p = outside(add(p, mul(dir, seg)));
    }
    for (let i = 0; i <= S; i++) {
      const tg = norm(sub(pts[Math.min(S, i + 1)], pts[Math.max(0, i - 1)]));
      let out = sub(pts[i], [0, pts[i][1], -0.01]); out = sub(out, mul(tg, dot(out, tg))); out = len(out) < 1e-4 ? nn : norm(out);
      if (i === 0) out = nn;
      const w = norm(cross(tg, out)); wides.push(w); nrms.push(norm(cross(w, tg)));
    }
    clumps.push({ pts, wides, nrms, w: (cut.w ?? 0.036) * (0.85 + 0.4 * R()) * (1 + 0.35 * wB) * (lenC > 0.2 ? 1.5 : 1), len: lenC, th: 0.012 + 0.006 * R(), stiff: 1 + spike * 2, ph: R() * 6.28 });
  }
  return { clumps, tail, cut };
}

/**
 * The back of a body as a tail of hair meets it: a row of large balls from the nape down to the hips, each touching the
 * back's surface from inside and riding on the spine or neck bone of its height: [{ bone, c (rest), r }].
 */
function backOf(B) {
  if (B.backBalls) return B.backBalls;
  const L = B.landmarks ?? {}, out = []; if (!B.head || !L.nape) return (B.backBalls = out);
  const spine = ['neck03', 'neck02', 'neck01', 'spine01', 'spine02', 'spine03', 'spine04', 'spine05'].map((n) => B.bi[n]).filter((b) => b !== undefined);
  if (!spine.length) return (B.backBalls = out);
  const R = 0.22, y1 = L.nape[1] - 0.02, y0 = (L.hipY ?? y1 - 0.65) - 0.05;
  for (let y = y1; y > y0; y -= 0.07) {
    let z = 1e9; for (const p of B.parts) { if (p.material === 'hair' || p.name === 'haircap') continue; const P = p.rest0 ?? p.pos; for (let v = 0; v < P.length; v += 3) if (P[v] < 0.07 && P[v] > -0.07 && P[v + 1] > y - 0.035 && P[v + 1] < y + 0.035 && P[v + 2] < z) z = P[v + 2]; }
    if (z > 1e8) continue;
    let bone = spine[spine.length - 1], best = 1e9; for (const b of spine) { const d = y - B.head[b * 3 + 1]; if (d >= -0.02 && d < best) { best = d; bone = b; } } // the bone this height hangs on
    out.push({ bone, c: [0, y, z + R], r: R });
  }
  return (B.backBalls = out);
}

/**
 * The hair now: world triangles for the painter. Call after poseBody. dt: seconds since the last call (the clumps lag
 * and swing); wind: (p, t) → m/s or null. Returns [{ pos, idx }] (clumps as one mesh; the tail as another).
 */
export function hairMeshes(H, B, dt, t, wind = null) {
  const out = [], S = 9, pos = new Float32Array(H.clumps.length * S * 4 * 3), idx = [], across = new Float32Array(H.clumps.length * S * 4);
  const hp = onBone(B, 'head', [0, B.landmarks.crown[1], 0]);
  // the head's movement since last time: every clump tip is left behind by it, then pulled back
  if (!H.st) H.st = { p: hp, v: [0, 0, 0], off: [0, 0, 0], t };
  const st = H.st, h = clamp(dt, 0, 0.05);
  if (Math.hypot(hp[0] - st.p[0], hp[1] - st.p[1], hp[2] - st.p[2]) > 0.6 || t < st.t) { st.p = hp; st.v = [0, 0, 0]; }
  const wv = wind ? wind(hp, t) : [0, 0, 0];
  for (let a = 0; a < 3; a++) { st.v[a] += ((hp[a] - st.p[a]) * 190 - st.v[a] * 16 + wv[a] * 3.2) * h; st.p[a] += st.v[a] * h; }
  st.t = t; const lag = [st.p[0] - hp[0], st.p[1] - hp[1], st.p[2] - hp[2]]; const ll = Math.hypot(lag[0], lag[1], lag[2]); if (ll > 0.09) { lag[0] *= 0.09 / ll; lag[1] *= 0.09 / ll; lag[2] *= 0.09 / ll; }
  H.clumps.forEach((c, ci) => {
    const flex = clamp(c.len / 0.1, 0.12, 4) / c.stiff * 0.45;
    for (let i = 0; i < S; i++) {
      const u = i / (S - 1), p0 = onBone(B, 'head', c.pts[i]), wd = dirOnBone(B, 'head', c.wides[i]), nn = dirOnBone(B, 'head', c.nrms[i]);
      const sw = u * u * flex, flut = wind ? Math.sin(t * 5.1 + c.ph + u * 3) * 0.004 * u * clamp(Math.hypot(wv[0], wv[2]) / 3) : 0;
      const p = [p0[0] + lag[0] * sw + wd[0] * flut, p0[1] + lag[1] * sw, p0[2] + lag[2] * sw + wd[2] * flut];
      // a leaf-shaped section: widest a third of the way along, a point at the end; thick enough to take light and shadow
      // (hair drawn back, and a designed lock, begin as a stroke begins — from a point, thin and flat — and are full a little way along)
      const rootK = c.back || c.lock ? clamp(0.12 + u / 0.26) : 1;
      const wv2 = c.w * 0.5 * (c.back || c.lock ? rootK * (u < 0.3 ? 1 : Math.pow(1 - (u - 0.3) / 0.7, 0.7)) : u < 0.3 ? 0.72 + 0.28 * (u / 0.3) : Math.pow(1 - (u - 0.3) / 0.7, 0.7)) + 0.0006, th = c.th * (1 - 0.75 * u) * rootK + 0.0005, o = (ci * S + i) * 12;
      pos[o] = p[0] - wd[0] * wv2; pos[o + 1] = p[1] - wd[1] * wv2; pos[o + 2] = p[2] - wd[2] * wv2;
      pos[o + 3] = p[0] + nn[0] * th; pos[o + 4] = p[1] + nn[1] * th; pos[o + 5] = p[2] + nn[2] * th;
      pos[o + 6] = p[0] + wd[0] * wv2; pos[o + 7] = p[1] + wd[1] * wv2; pos[o + 8] = p[2] + wd[2] * wv2;
      pos[o + 9] = p[0] - nn[0] * th * 0.4; pos[o + 10] = p[1] - nn[1] * th * 0.4; pos[o + 11] = p[2] - nn[2] * th * 0.4;
      across[o / 3] = -1; across[o / 3 + 2] = 1; // (the ridge and the underside are the lock's middle: 0)
      if (i < S - 1) for (let k = 0; k < 4; k++) { const a = (ci * S + i) * 4 + k, b = (ci * S + i) * 4 + (k + 1) % 4, c2 = a + 4, d = b + 4; idx.push(a, c2, b, b, c2, d); }
    }
  });
  out.push({ pos, idx: Uint32Array.from(idx), u: across });
  if (H.tail) {
    const T = H.tail, n = T.locks > 0 ? 13 : 9, base = onBone(B, 'head', T.at), d0 = norm(dirOnBone(B, 'head', norm(T.dir))), seg = T.len / (n - 1);
    // what a tail cannot pass through: the head itself (as hair meets it), and the back — a row of large balls that touch it from inside
    const HSt = B.landmarks?.crown && B.landmarks?.nape && B.landmarks?.eyeL ? headSurface(B) : null, balls = backOf(B), rad = T.w * 0.3 + 0.004;
    const hb = B.bi.head, ho = hb * 9, Rh = B.R, Th = B.T, s2 = Rh[ho] * Rh[ho] + Rh[ho + 3] * Rh[ho + 3] + Rh[ho + 6] * Rh[ho + 6] || 1, sc = Math.sqrt(s2);
    // (a thread can be no further from head and back than it has run from its tie: asked for more — a knot shorter than
    // its own breadth, a tie that lies inside the back of a bowed head — it is asked for the impossible and thrashes. Its
    // clearance grows along it, half of what it has run; and it leaves what its tie is in no faster than that)
    const clear = (p, run = 1e9) => {
      const r = Math.min(rad, run * 0.5);
      if (HSt) { const x = p[0] - Th[hb * 3], y = p[1] - Th[hb * 3 + 1], z = p[2] - Th[hb * 3 + 2], lp = [(Rh[ho] * x + Rh[ho + 3] * y + Rh[ho + 6] * z) / s2, (Rh[ho + 1] * x + Rh[ho + 4] * y + Rh[ho + 7] * z) / s2, (Rh[ho + 2] * x + Rh[ho + 5] * y + Rh[ho + 8] * z) / s2], lo = HSt.out(lp, r / sc); if (lo !== lp) p = onBone(B, 'head', lo); }
      for (const b of balls) { const c = onBone(B, b.bone, b.c), d = sub(p, c), l = len(d), need = Math.min((b.r + r) * sc, len(sub(base, c)) + run * 0.5); if (l < need && l > 1e-6) p = add(c, mul(d, need / l)); }
      return p;
    };
    // a hanging thread, in small steps of time (so it comes to rest the same at any number of pictures a second): each
    // point keeps its speed, falls, is blown, stays a span away from the one before and clear of head and back. Off the
    // tie it leaves the way it is bound (stiffly), bends over within a few spans, and hangs freely from there.
    const DOWN = [0, -1, 0], lead = [d0, ...[0.18, 0.38, 0.58, 0.78].map((k) => norm(add(mul(d0, 1 - k), mul(DOWN, k))))], hold = [0.6, 0.45, 0.3, 0.18, 0.08];
    const step = (hs, damp, wind2) => {
      for (let i = 1; i < n; i++) {
        const q = N2[i]; if (!q.q) q.q = [...q.p];
        const p = [q.p[0] + (q.p[0] - q.q[0]) * damp + wind2[0] * hs * hs, q.p[1] + (q.p[1] - q.q[1]) * damp - 9.81 * 0.9 * hs * hs, q.p[2] + (q.p[2] - q.q[2]) * damp + wind2[2] * hs * hs];
        q.q = [...q.p];
        if (i <= lead.length) { const a = N2[i - 1].p, k = hold[i - 1], d = lead[i - 1]; for (let c = 0; c < 3; c++) p[c] += (a[c] + d[c] * seg - p[c]) * k; }
        q.p = p;
      }
      // (what head or back pushes aside is carried along, not thrown: the push is no speed of its own)
      for (let it = 0; it < 8; it++) {
        // spans keep their length: both ends give way alike (the tie does not)
        for (let i = 1; i < n; i++) { const a = N2[i - 1].p, b = N2[i].p, d = sub(b, a), l = len(d) || 1, k = (l - seg) / l, wa = i === 1 ? 0 : 0.5, wb = 1 - wa; for (let c = 0; c < 3; c++) { a[c] += d[c] * k * wa; b[c] -= d[c] * k * wb; } }
        for (let i = 1; i < n; i++) { const f = N2[i].p, c = clear(f, i * seg); if (c !== f) { const q = N2[i].q; q[0] += c[0] - f[0]; q[1] += c[1] - f[1]; q[2] += c[2] - f[2]; N2[i].p = c; } }
      }
    };
    let N2 = T.st;
    if (!N2 || N2.length !== n || Math.hypot(N2[0].p[0] - base[0], N2[0].p[1] - base[1], N2[0].p[2] - base[2]) > 0.6) {
      // where it starts (a new shot): leaving the tie the way it is bound, then hanging by its own weight — and already at rest
      const pts = [[base[0], base[1], base[2]]]; let dir = [d0[0], d0[1], d0[2]];
      for (let i = 1; i < n; i++) { const pull = i < 2 ? 0.25 : 0.62; dir = norm([dir[0] * (1 - pull), dir[1] * (1 - pull) - pull, dir[2] * (1 - pull)]); const a = pts[i - 1]; pts.push([a[0] + dir[0] * seg, a[1] + dir[1] * seg, a[2] + dir[2] * seg]); }
      N2 = T.st = pts.map((p) => ({ p, q: null }));
      for (let k = 0; k < 240; k++) { step(1 / 120, 0.9, [0, 0, 0]); if (k % 40 === 39) for (const q of N2) q.q = [...q.p]; }
      for (const q of N2) q.q = [...q.p];
    }
    // the tie moves with the head through the picture's time, a small step at a time (moved all at once, it would crack the tail like a whip)
    { const from = N2[0].p, ns = Math.max(1, Math.min(8, Math.round(h * 120))), hs = h / ns, damp = Math.pow(0.2, hs), w2 = [wv[0] * 1.2, 0, wv[2] * 1.2];
      for (let k = 0; k < ns && h > 0; k++) { const u = (k + 1) / ns; N2[0].p = [from[0] + (base[0] - from[0]) * u, from[1] + (base[1] - from[1]) * u, from[2] + (base[2] - from[2]) * u]; step(hs, damp, w2); } }
    N2[0].p = base;
    // the frame across the tail is carried from the tie down the thread — the head's own side, kept square to each span in
    // turn — never taken afresh from "up": a tail that hangs straight down has no side of its own, and its sheaf of locks
    // turned about it from picture to picture
    const FRM = []; { let e1 = norm(dirOnBone(B, 'head', [1, 0, 0]));
      for (let i = 0; i < n; i++) { const a = N2[Math.max(0, i - 1)].p, b = N2[Math.min(n - 1, i + 1)].p, ax = norm(sub(b, a)), k = e1[0] * ax[0] + e1[1] * ax[1] + e1[2] * ax[2]; let e = [e1[0] - ax[0] * k, e1[1] - ax[1] * k, e1[2] - ax[2] * k]; if (len(e) < 0.05) { e = cross(ax, [0, 0, 1]); if (len(e) < 0.05) e = cross(ax, [0, 1, 0]); } e1 = norm(e); FRM.push({ ax, e1, e2: cross(ax, e1) }); } }
    const frameAt = (i) => FRM[i];
    if (T.locks > 0) {
      // a sheaf of locks: gathered at the tie, fullest a little below it, each running out to a tip of its own
      const Rk = rng(T.seed), K = T.locks, FR = N2.map((_, i) => frameAt(i));
      const bundle = (v) => T.w * 0.5 * (v < 0.12 ? 0.35 + 0.65 * (v / 0.12) : 1 - 0.55 * Math.pow((v - 0.12) / 0.88, 1.5));
      for (let k = 0; k < K; k++) {
        const ph = (Math.PI * 2 * (k + 0.35 * (Rk() - 0.5))) / K, fk = k === 0 ? 1 : 0.68 + 0.3 * Rk(), wk = T.w * (0.4 + 0.14 * Rk()), wave = (Rk() - 0.5) * 0.9, lp = new Float32Array(S * 4 * 3), lu = new Float32Array(S * 4), li = [];
        for (let i = 0; i < S; i++) {
          const u = i / (S - 1), v = u * fk, g2 = v * (n - 1), i0 = Math.min(n - 2, Math.floor(g2)), f = g2 - i0, a = N2[i0].p, b = N2[i0 + 1].p, F0 = FR[i0], F1 = FR[i0 + 1];
          const e1 = norm(add(mul(F0.e1, 1 - f), mul(F1.e1, f))), e2 = norm(add(mul(F0.e2, 1 - f), mul(F1.e2, f))), an = ph + wave * v, cs = Math.cos(an), sn = Math.sin(an);
          const radial = norm(add(mul(e1, cs), mul(e2, sn))), wide = norm(add(mul(e1, -sn), mul(e2, cs))), offR = bundle(v) * 0.72 * (1 - 0.5 * u * u);
          const p = add(add(mul(a, 1 - f), mul(b, f)), mul(radial, offR)), hw = wk * 0.5 * (u < 0.25 ? 0.6 + 0.4 * (u / 0.25) : Math.pow(1 - (u - 0.25) / 0.75, 0.7)) + 0.0006, th = 0.012 * (1 - 0.75 * u) + 0.0005, o = i * 12;
          lp[o] = p[0] - wide[0] * hw; lp[o + 1] = p[1] - wide[1] * hw; lp[o + 2] = p[2] - wide[2] * hw;
          lp[o + 3] = p[0] + radial[0] * th; lp[o + 4] = p[1] + radial[1] * th; lp[o + 5] = p[2] + radial[2] * th;
          lp[o + 6] = p[0] + wide[0] * hw; lp[o + 7] = p[1] + wide[1] * hw; lp[o + 8] = p[2] + wide[2] * hw;
          lp[o + 9] = p[0] - radial[0] * th * 0.4; lp[o + 10] = p[1] - radial[1] * th * 0.4; lp[o + 11] = p[2] - radial[2] * th * 0.4;
          lu[i * 4] = -1; lu[i * 4 + 2] = 1;
          if (i < S - 1) for (let q = 0; q < 4; q++) { const a2 = i * 4 + q, b2 = i * 4 + (q + 1) % 4, c2 = a2 + 4, d2 = b2 + 4; li.push(a2, c2, b2, b2, c2, d2); }
        }
        out.push({ pos: lp, idx: Uint32Array.from(li), u: lu, tail: true });
      }
    } else {
      const tp = new Float32Array(n * 6 * 3), ti = [];
      for (let i = 0; i < n; i++) {
        const p = N2[i].p, a = N2[Math.max(0, i - 1)].p, b = N2[Math.min(n - 1, i + 1)].p, ax = norm(sub(b, a)); let e1 = cross(ax, [0, 1, 0]); e1 = len(e1) < 0.05 ? [1, 0, 0] : norm(e1); const e2 = cross(ax, e1), u = i / (n - 1), r = T.w * 0.5 * (u < 0.15 ? 0.55 + 3 * u : Math.pow(1 - (u - 0.15) / 0.85, 0.6)) + 0.001;
        for (let k = 0; k < 6; k++) { const an = (k / 6) * Math.PI * 2, o = (i * 6 + k) * 3; tp[o] = p[0] + (e1[0] * Math.cos(an) + e2[0] * Math.sin(an) * 0.7) * r; tp[o + 1] = p[1] + (e1[1] * Math.cos(an) + e2[1] * Math.sin(an) * 0.7) * r; tp[o + 2] = p[2] + (e1[2] * Math.cos(an) + e2[2] * Math.sin(an) * 0.7) * r; }
        if (i < n - 1) for (let k = 0; k < 6; k++) { const a2 = i * 6 + k, b2 = i * 6 + (k + 1) % 6, c2 = a2 + 6, d2 = b2 + 6; ti.push(a2, c2, b2, b2, c2, d2); }
      }
      out.push({ pos: tp, idx: Uint32Array.from(ti), tail: true });
    }
    if (T.tie) {
      // the cord that binds it: a band round the tail just off the head, a little proud of the hair
      const K = 8, bp = new Float32Array(2 * K * 3), bi = [];
      if (T.locks > 0) {
        // a sheaf of locks is bound where it is gathered: a short sleeve of cord round the first span, drawn tight
        const a = N2[0].p, b = N2[1].p, ax = norm(sub(b, a)); let e1 = cross(ax, [0, 1, 0]); e1 = len(e1) < 0.05 ? [1, 0, 0] : norm(e1); const e2 = cross(ax, e1);
        for (let e = 0; e < 2; e++) { const f = e ? 0.9 : 0.3, q = add(mul(a, 1 - f), mul(b, f)), r = T.w * 0.5 * (0.35 + 0.65 * Math.min(1, (f / (n - 1)) / 0.12)) * 0.78 + 0.004; for (let k = 0; k < K; k++) { const an = (k / K) * Math.PI * 2, o = (e * K + k) * 3; bp[o] = q[0] + (e1[0] * Math.cos(an) + e2[0] * Math.sin(an)) * r; bp[o + 1] = q[1] + (e1[1] * Math.cos(an) + e2[1] * Math.sin(an)) * r; bp[o + 2] = q[2] + (e1[2] * Math.cos(an) + e2[2] * Math.sin(an)) * r; } }
      } else for (let e = 0; e < 2; e++) {
        const i = e ? 2 : 1, p = N2[i].p, a = N2[i - 1].p, b = N2[i + 1].p, ax = norm(sub(b, a)); let e1 = cross(ax, [0, 1, 0]); e1 = len(e1) < 0.05 ? [1, 0, 0] : norm(e1); const e2 = cross(ax, e1), u = i / (n - 1), r = (T.w * 0.5 * (u < 0.15 ? 0.55 + 3 * u : Math.pow(1 - (u - 0.15) / 0.85, 0.6)) + 0.001) * 1.12 + 0.004, q = e ? [p[0] - ax[0] * seg * 0.45, p[1] - ax[1] * seg * 0.45, p[2] - ax[2] * seg * 0.45] : p;
        for (let k = 0; k < K; k++) { const an = (k / K) * Math.PI * 2, o = (e * K + k) * 3; bp[o] = q[0] + (e1[0] * Math.cos(an) + e2[0] * Math.sin(an) * 0.75) * r; bp[o + 1] = q[1] + (e1[1] * Math.cos(an) + e2[1] * Math.sin(an) * 0.75) * r; bp[o + 2] = q[2] + (e1[2] * Math.cos(an) + e2[2] * Math.sin(an) * 0.75) * r; }
      }
      for (let k = 0; k < K; k++) { const a2 = k, b2 = (k + 1) % K, c2 = a2 + K, d2 = b2 + K; bi.push(a2, c2, b2, b2, c2, d2); }
      out.push({ pos: bp, idx: Uint32Array.from(bi), tie: true });
      // the cord's ends: two lengths of it hanging from the knot, each run out into a small tassel; they swing a little behind the head
      for (let e = 0; e < (T.ends?.n ?? 0); e++) {
        const F = frameAt(1), sd = e % 2 ? -1 : 1, M = 7, r0 = T.w * 0.5 * 0.9 + 0.006, start = add(N2[1].p, mul(F.e1, sd * r0)), cl = T.ends.len * (1 - 0.14 * e), ep = new Float32Array(M * 4 * 3), ei = [];
        const face = norm(add(mul(F.e1, sd), [0, 0, 0])), across2 = norm(cross([0, -1, 0], face));
        for (let i = 0; i < M; i++) {
          const u = i / (M - 1), p = [start[0] + F.e1[0] * sd * 0.012 * u + lag[0] * u * u * 1.4, start[1] - cl * u + lag[1] * u * u, start[2] + F.e1[2] * sd * 0.012 * u + lag[2] * u * u * 1.4];
          const hw = u < 0.72 ? 0.0036 : u < 0.86 ? 0.0036 + 0.0055 * ((u - 0.72) / 0.14) : 0.0091 * (1 - (u - 0.86) / 0.14) + 0.0006, th = 0.003, o = i * 12;
          ep[o] = p[0] - across2[0] * hw; ep[o + 1] = p[1] - across2[1] * hw; ep[o + 2] = p[2] - across2[2] * hw;
          ep[o + 3] = p[0] + face[0] * th; ep[o + 4] = p[1] + face[1] * th; ep[o + 5] = p[2] + face[2] * th;
          ep[o + 6] = p[0] + across2[0] * hw; ep[o + 7] = p[1] + across2[1] * hw; ep[o + 8] = p[2] + across2[2] * hw;
          ep[o + 9] = p[0] - face[0] * th; ep[o + 10] = p[1] - face[1] * th; ep[o + 11] = p[2] - face[2] * th;
          if (i < M - 1) for (let q = 0; q < 4; q++) { const a2 = i * 4 + q, b2 = i * 4 + (q + 1) % 4, c2 = a2 + 4, d2 = b2 + 4; ei.push(a2, c2, b2, b2, c2, d2); }
        }
        out.push({ pos: ep, idx: Uint32Array.from(ei), tie: true });
      }
      // a ribbon tied in a bow (tail.tie.bow: its width in metres): a loop of ribbon to each side of the knot, standing on
      // its edge, pinched at the knot and fullest towards its end, tilted up a little. It is built in the head's own
      // space (it is tied to the hair on the head, so it tilts and turns with it) and kept outside the head.
      if (T.bow > 0) {
        const M = 9, half = T.bow / 2, k0 = N2[1].p, x0 = k0[0] - Th[hb * 3], y0 = k0[1] - Th[hb * 3 + 1], z0 = k0[2] - Th[hb * 3 + 2];
        const kl = [(Rh[ho] * x0 + Rh[ho + 3] * y0 + Rh[ho + 6] * z0) / s2, (Rh[ho + 1] * x0 + Rh[ho + 4] * y0 + Rh[ho + 7] * z0) / s2, (Rh[ho + 2] * x0 + Rh[ho + 5] * y0 + Rh[ho + 8] * z0) / s2];
        const clearOf = (q, r) => (HSt ? HSt.out(q, r) : q);
        for (const sd of [1, -1]) {
          const dir = norm([sd, 0.28, -0.12]); let up = [0, 1, 0]; up = norm(sub(up, mul(dir, dot(up, dir)))); const thick = cross(dir, up);
          const lp = new Float32Array(M * 4 * 3), li = [];
          for (let i = 0; i < M; i++) {
            const u = i / (M - 1), hh = half * 0.5 * Math.pow(Math.sin(Math.PI * Math.min(1, 0.06 + 0.94 * Math.pow(u, 0.75))), 0.7) * (0.35 + 0.65 * u) + 0.004, th = 0.004 + 0.012 * Math.sin(Math.PI * u);
            const c = clearOf(add(kl, mul(dir, half * u * 0.97 + 0.004)), th + 0.014), o = i * 12;
            const put = (k, q) => { const w = onBone(B, 'head', clearOf(q, 0.006)); lp[o + k * 3] = w[0]; lp[o + k * 3 + 1] = w[1]; lp[o + k * 3 + 2] = w[2]; };
            put(0, sub(c, mul(up, hh))); put(1, add(c, mul(thick, th))); put(2, add(c, mul(up, hh))); put(3, sub(c, mul(thick, th)));
            if (i < M - 1) for (let q = 0; q < 4; q++) { const a2 = i * 4 + q, b2 = i * 4 + (q + 1) % 4, c2 = a2 + 4, d2 = b2 + 4; li.push(a2, c2, b2, b2, c2, d2); }
          }
          out.push({ pos: lp, idx: Uint32Array.from(li), tie: true, bow: true });
        }
      }
    }
  }
  return out;
}
