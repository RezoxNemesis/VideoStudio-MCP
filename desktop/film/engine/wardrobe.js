// Clothes made by the engine, cut for the body that wears them.
// The Stage's own garments are the body's skin let out a little: they follow every muscle, end in torn edges and have no
// collar, placket, pocket, belt or shoe. A design can ask for cut clothes instead:
//   design.wear = { shirt: { color, shade?, sleeve: 'short' | 'long' | 'rolled', ease?, pocket?, collar: { open?, point? } },
//                   tie: { color, stripe?, loose? }, trousers: { color, shade?, fit: 'slim' | 'straight' | 'loose' },
//                   belt: { color, buckle? }, shoes: { kind: 'derby', color, sole? } }
// Every piece is a smooth shell measured from the body in its rest shape — sections of the body, the cloth spanning its
// hollows (a convex outline) with ease, hanging from the chest and the thigh — and hangs on the body's own bones (each
// bit of cloth moves as the skin nearest to it does). The pieces a drawing of clothes is made of are things of their
// own, so each gets its drawn line: collar, placket, pocket, the hem band of a sleeve, belt, buckle, sole. The skin
// under the clothes is not drawn, so nothing can come through them. (Rules: devtests/wardrobe.mjs)
import { FITS } from './tailor.js';

const TAU = Math.PI * 2, NR = 40, DT = TAU / NR, JF = NR / 4, JB = (3 * NR) / 4; // round a ring: 0 the body's left (+x), JF the front (+z), JB the back
const COS = Array.from({ length: NR }, (_, j) => Math.cos(j * DT)), SIN = Array.from({ length: NR }, (_, j) => Math.sin(j * DT));
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], mul = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2], cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
const lerp = (a, b, u) => a + (b - a) * u, mix = (a, b, u) => [lerp(a[0], b[0], u), lerp(a[1], b[1], u), lerp(a[2], b[2], u)];
const clamp = (v, a = 0, b = 1) => (v < a ? a : v > b ? b : v), smooth = (u) => { u = clamp(u); return u * u * (3 - 2 * u); };
const UP = [0, 1, 0], X = [1, 0, 0], Z = [0, 0, 1];

/** The outline of a body where a plane cuts it, as radii round a middle: o the middle, a across the plane, u/v in it. */
function section(S, mask, o, a, u, v) {
  const r = new Float32Array(NR), { pos, idx } = S, P = [[0, 0], [0, 0], [0, 0]];
  for (let t = 0; t < idx.length; t += 3) {
    const i0 = idx[t], i1 = idx[t + 1], i2 = idx[t + 2]; if (mask && !(mask[i0] && mask[i1] && mask[i2])) continue;
    const q0 = i0 * 3, q1 = i1 * 3, q2 = i2 * 3;
    const d0 = (pos[q0] - o[0]) * a[0] + (pos[q0 + 1] - o[1]) * a[1] + (pos[q0 + 2] - o[2]) * a[2], d1 = (pos[q1] - o[0]) * a[0] + (pos[q1 + 1] - o[1]) * a[1] + (pos[q1 + 2] - o[2]) * a[2], d2 = (pos[q2] - o[0]) * a[0] + (pos[q2 + 1] - o[1]) * a[1] + (pos[q2 + 2] - o[2]) * a[2];
    if ((d0 > 0) === (d1 > 0) && (d1 > 0) === (d2 > 0)) continue;
    let n = 0;
    const edge = (qa, qb, da, db) => { if ((da > 0) === (db > 0) || n > 1) return; const f = da / (da - db), x = pos[qa] + (pos[qb] - pos[qa]) * f - o[0], y = pos[qa + 1] + (pos[qb + 1] - pos[qa + 1]) * f - o[1], z = pos[qa + 2] + (pos[qb + 2] - pos[qa + 2]) * f - o[2]; P[n][0] = x * u[0] + y * u[1] + z * u[2]; P[n][1] = x * v[0] + y * v[1] + z * v[2]; n++; };
    edge(q0, q1, d0, d1); edge(q1, q2, d1, d2); edge(q2, q0, d2, d0); if (n < 2) continue;
    rays(r, P[0][0], P[0][1], P[1][0], P[1][1]);
  }
  return r;
}
/** Does the body cross its own middle (x = 0) at this height? Above the crotch it does; between the legs it does not. */
function spansMiddle(S, mask, y) {
  const { pos, idx } = S;
  for (let t = 0; t < idx.length; t += 3) {
    const i0 = idx[t], i1 = idx[t + 1], i2 = idx[t + 2]; if (mask && !(mask[i0] && mask[i1] && mask[i2])) continue;
    const q = [i0 * 3, i1 * 3, i2 * 3], d = [pos[q[0] + 1] - y, pos[q[1] + 1] - y, pos[q[2] + 1] - y]; if ((d[0] > 0) === (d[1] > 0) && (d[1] > 0) === (d[2] > 0)) continue;
    const xs = []; for (const [a, b] of [[0, 1], [1, 2], [2, 0]]) if ((d[a] > 0) !== (d[b] > 0)) xs.push(pos[q[a]] + (pos[q[b]] - pos[q[a]]) * d[a] / (d[a] - d[b]));
    if (xs.length === 2 && xs[0] * xs[1] <= 1e-10) return true;
  }
  return false;
}
// a piece of outline from (px, py) to (qx, qy): the furthest it reaches in every direction it crosses
function rays(r, px, py, qx, qy) {
  const tp = Math.atan2(py, px); let dt = Math.atan2(qy, qx) - tp; if (dt > Math.PI) dt -= TAU; else if (dt < -Math.PI) dt += TAU;
  const a0 = dt >= 0 ? tp : tp + dt, a1 = a0 + Math.abs(dt), ex = qx - px, ey = qy - py;
  for (let j = Math.ceil(a0 / DT - 1e-9); j * DT <= a1 + 1e-9; j++) {
    const jj = ((j % NR) + NR) % NR, c = COS[jj], s = SIN[jj], den = ex * s - ey * c; let rr;
    if (Math.abs(den) < 1e-12) rr = Math.max(px * c + py * s, qx * c + qy * s);
    else { const tt = -(px * s - py * c) / den; if (tt < -1e-6 || tt > 1 + 1e-6) continue; rr = (px + tt * ex) * c + (py + tt * ey) * s; }
    if (rr > r[jj]) r[jj] = rr;
  }
}
/** Cloth spans the hollows of what it lies on: the convex outline of a section (null when there is none). */
function hullOf(r) {
  const pts = []; for (let j = 0; j < NR; j++) if (r[j] > 0) pts.push([r[j] * COS[j], r[j] * SIN[j]]);
  if (pts.length < 3) return null;
  pts.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const cr = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]), H = [];
  for (const p of pts) { while (H.length >= 2 && cr(H[H.length - 2], H[H.length - 1], p) <= 0) H.pop(); H.push(p); }
  const lo = H.length + 1; for (let i = pts.length - 2; i >= 0; i--) { const p = pts[i]; while (H.length >= lo && cr(H[H.length - 2], H[H.length - 1], p) <= 0) H.pop(); H.push(p); }
  H.pop(); if (H.length < 3) return null;
  const out = new Float32Array(NR);
  for (let i = 0; i < H.length; i++) { const p = H[i], q = H[(i + 1) % H.length]; rays(out, p[0], p[1], q[0], q[1]); }
  return fill(out);
}
/** An outline let out by `e` everywhere (measured square to the outline, the way ease is), as radii again. */
function grown(r, e) {
  const H = Array.from({ length: NR }, (_, j) => [r[j] * COS[j], r[j] * SIN[j]]), out = new Float32Array(NR), O = [];
  for (let i = 0; i < NR; i++) { const a = H[(i + NR - 1) % NR], p = H[i], b = H[(i + 1) % NR], l1 = Math.hypot(p[0] - a[0], p[1] - a[1]) || 1, l2 = Math.hypot(b[0] - p[0], b[1] - p[1]) || 1;
    const n1 = [(p[1] - a[1]) / l1, -(p[0] - a[0]) / l1], n2 = [(b[1] - p[1]) / l2, -(b[0] - p[0]) / l2], dn = Math.max(0.3, 1 + n1[0] * n2[0] + n1[1] * n2[1]); O.push([p[0] + e * (n1[0] + n2[0]) / dn, p[1] + e * (n1[1] + n2[1]) / dn]); }
  for (let i = 0; i < NR; i++) rays(out, O[i][0], O[i][1], O[(i + 1) % NR][0], O[(i + 1) % NR][1]);
  for (let j = 0; j < NR; j++) if (!(out[j] > r[j])) out[j] = r[j] + e;
  return out;
}
// directions nothing was found in take what their neighbours have
function fill(r) {
  if (!r.some((v) => v > 0)) return null;
  for (let j = 0; j < NR; j++) { if (r[j] > 0) continue; let a = 1, b = 1; while (!(r[(j - a + NR * 4) % NR] > 0)) a++; while (!(r[(j + b) % NR] > 0)) b++; r[j] = -lerp(r[(j - a + NR * 4) % NR], r[(j + b) % NR], a / (a + b)); }
  for (let j = 0; j < NR; j++) if (r[j] < 0) r[j] = -r[j];
  return r;
}
const blur = (r, n = 1) => { for (let p = 0; p < n; p++) { const o = Float32Array.from(r); for (let j = 0; j < NR; j++) r[j] = 0.25 * o[(j + NR - 1) % NR] + 0.5 * o[j] + 0.25 * o[(j + 1) % NR]; } return r; };
const polar = (r, th) => { const f = ((th / DT) % NR + NR) % NR, j = Math.floor(f), u = f - j; return lerp(r[j], r[(j + 1) % NR], u); };
const mean = (r) => { let s = 0; for (let j = 0; j < NR; j++) s += r[j]; return s / NR; };
/** Radii by height: a shell round an upright axis as a function (y, θ) → radius. */
function field(ys, rs) {
  return (y, th) => { if (y <= ys[0]) return polar(rs[0], th); const n = ys.length; if (y >= ys[n - 1]) return polar(rs[n - 1], th); let i = 0; while (ys[i + 1] < y) i++; return lerp(polar(rs[i], th), polar(rs[i + 1], th), (y - ys[i]) / (ys[i + 1] - ys[i])); };
}
// smooth the radii of a stack of rings along its height (never under `floor`)
function smoothUp(rs, floor, n = 1, from = 0, to = rs.length) {
  for (let p = 0; p < n; p++) { const o = rs.map((r) => Float32Array.from(r)); for (let i = Math.max(1, from); i < Math.min(rs.length - 1, to); i++) for (let j = 0; j < NR; j++) rs[i][j] = Math.max(floor ? floor[i][j] : 0, 0.25 * o[i - 1][j] + 0.5 * o[i][j] + 0.25 * o[i + 1][j]); }
}

// ---- meshes
const ringPts = (r, o, u, v, more = 0) => Array.from({ length: NR }, (_, j) => [o[0] + (r[j] + more) * (COS[j] * u[0] + SIN[j] * v[0]), o[1] + (r[j] + more) * (COS[j] * u[1] + SIN[j] * v[1]), o[2] + (r[j] + more) * (COS[j] * u[2] + SIN[j] * v[2])]);
/** Rings of the same count joined into a tube that faces outwards; capA / capB close its ends. */
function loft(rings, o = {}) {
  const N = rings[0].length, pos = [], idx = []; for (const r of rings) for (const p of r) pos.push(p[0], p[1], p[2]);
  const c0 = rings[0].reduce((s, p) => add(s, p), [0, 0, 0]).map((v) => v / N), A = rings[0][0], Bq = rings[0][1], C = rings[1][0];
  const flip = dot(cross(sub(Bq, A), sub(C, A)), sub(A, c0)) < 0;
  const tri = (a, b, c) => (flip ? idx.push(a, c, b) : idx.push(a, b, c));
  for (let i = 0; i < rings.length - 1; i++) for (let j = 0; j < N; j++) { const a = i * N + j, b = i * N + (j + 1) % N, c = (i + 1) * N + j, d2 = (i + 1) * N + (j + 1) % N; tri(a, b, d2); tri(a, d2, c); }
  const cap = (i, rev) => { const r = rings[i], c = r.reduce((s, p) => add(s, p), [0, 0, 0]).map((v) => v / N), ci = pos.length / 3; pos.push(c[0], c[1], c[2]); for (let j = 0; j < N; j++) { const a = i * N + j, b = i * N + (j + 1) % N; rev ? tri(a, b, ci) : tri(b, a, ci); } };
  if (o.capA) cap(0, false); if (o.capB) cap(rings.length - 1, true);
  return { pos, idx };
}
/** Rows of points joined into a sheet; it faces the way `out(point)` points. */
function sheet(rows, out) {
  const M = rows[0].length, pos = [], idx = []; for (const r of rows) for (const p of r) pos.push(p[0], p[1], p[2]);
  for (let i = 0; i < rows.length - 1; i++) for (let j = 0; j < M - 1; j++) {
    const a = i * M + j, b = a + 1, c = a + M, d2 = c + 1, pa = rows[i][j], n = cross(sub(rows[i][j + 1], pa), sub(rows[i + 1][j], pa)), f = dot(n, out(pa)) < 0;
    if (f) idx.push(a, c, b, b, c, d2); else idx.push(a, b, c, b, d2, c);
  }
  return { pos, idx };
}
const join = (ms) => { const pos = [], idx = []; for (const m of ms) { const o = pos.length / 3; for (const v of m.pos) pos.push(v); for (const i of m.idx) idx.push(i + o); } return { pos, idx }; };

/** Each point of cloth hangs on the bones the skin nearest to it hangs on (the three nearest of `cand`, by nearness). */
function bind(B, sk, pos, cand, remap = null) {
  const n = pos.length / 3, si = new Uint16Array(n * 4), sw = new Float32Array(n * 4), P = sk.pos;
  for (let v = 0; v < n; v++) {
    const x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2]; let b0 = -1, b1 = -1, b2 = -1, d0 = 1e9, d1 = 1e9, d2 = 1e9;
    for (let c = 0; c < cand.length; c++) { const q = cand[c] * 3, dx = P[q] - x, dy = P[q + 1] - y, dz = P[q + 2] - z, dd = dx * dx + dy * dy + dz * dz; if (dd >= d2) continue; if (dd < d0) { b2 = b1; d2 = d1; b1 = b0; d1 = d0; b0 = cand[c]; d0 = dd; } else if (dd < d1) { b2 = b1; d2 = d1; b1 = cand[c]; d1 = dd; } else { b2 = cand[c]; d2 = dd; } }
    const acc = new Map();
    for (const [bv, dd] of [[b0, d0], [b1, d1], [b2, d2]]) { if (bv < 0) continue; const wv = 1 / (dd + 2e-5); for (let q = 0; q < 4; q++) { const ww = sk.sw[bv * 4 + q]; if (!ww) continue; let bone = sk.si[bv * 4 + q]; if (remap && remap.has(bone)) bone = remap.get(bone); acc.set(bone, (acc.get(bone) ?? 0) + ww * wv); } }
    const top = [...acc].sort((a, b) => b[1] - a[1]).slice(0, 4); let s = 0; for (const t of top) s += t[1];
    if (!top.length) { sw[v * 4] = 1; continue; }
    top.forEach((t, q) => { si[v * 4 + q] = t[0]; sw[v * 4 + q] = t[1] / s; });
  }
  return { si, sw };
}
const rigid = (n, bone) => { const si = new Uint16Array(n * 4), sw = new Float32Array(n * 4); for (let v = 0; v < n; v++) { si[v * 4] = bone; sw[v * 4] = 1; } return { si, sw }; };

/**
 * Dress a body for a design (once for each set of clothes). Returns what it wears, also kept as B.wear:
 * { parts: [{ name, group, n, pos, idx, si, sw, out, nrm, color, shade?, … }], lines: [{ name, on (the group it is
 *   drawn on), n, pos, si, sw, out }], hide: the Stage's garments not to draw, skin: { idxCore, idxLimb } (the skin left
 *   to draw) } — or null when the design asks for none (the Stage's clothes stay).
 */
export function dressBody(B, d) {
  const w = d?.wear; if (!B) return null; if (!w) { B.wear = null; return null; }
  const key = JSON.stringify(w); if (B.wear && B.wear.key === key) return B.wear;
  const sk = B.parts.find((p) => p.material === 'skin'); if (!sk || !sk.si) { B.wear = null; return null; }
  const J = (n) => { const i = B.bi[n]; return i === undefined ? null : [B.head[i * 3], B.head[i * 3 + 1], B.head[i * 3 + 2]]; };
  const need = ['upperleg01.L', 'upperleg01.R', 'lowerleg01.L', 'lowerleg01.R', 'foot.L', 'foot.R', 'upperarm01.L', 'upperarm01.R', 'lowerarm01.L', 'lowerarm01.R', 'neck01'];
  if (need.some((n) => !J(n))) { B.wear = null; return null; }
  const k = B.height / 1.8, S = { pos: sk.pos, idx: sk.idx }, P = sk.pos, n = sk.n;
  // what each bit of skin belongs to: an arm (and which), the head (from the second neck bone up)
  const cls = new Uint8Array(B.nb); B.names.forEach((nm, i) => { if (/^(upperarm|lowerarm|wrist|finger|metacarpal)/.test(nm)) cls[i] = nm.endsWith('.R') ? 2 : 1; });
  const n2 = B.bi.neck02; if (n2 !== undefined) for (let b = 0; b < B.nb; b++) for (let q = b; q >= 0; q = B.parent[q]) if (q === n2) { cls[b] = 5; break; }
  const armL = new Float32Array(n), armR = new Float32Array(n), headW = new Float32Array(n);
  for (let v = 0; v < n; v++) for (let q = 0; q < 4; q++) { const ww = sk.sw[v * 4 + q], c = cls[sk.si[v * 4 + q]]; if (c === 1) armL[v] += ww; else if (c === 2) armR[v] += ww; else if (c === 5) headW[v] += ww; }
  const mask = (f) => { const m = new Uint8Array(n); for (let v = 0; v < n; v++) if (f(v)) m[v] = 1; return m; }, list = (m) => { const a = []; for (let v = 0; v < n; v++) if (m[v]) a.push(v); return Int32Array.from(a); };
  const arm = (v) => armL[v] + armR[v], mNoArm = mask((v) => arm(v) < 0.5), mTrunk = mask((v) => arm(v) < 0.5 && headW[v] < 0.5);
  const hipL = J('upperleg01.L'), hipR = J('upperleg01.R'), hipY = (hipL[1] + hipR[1]) / 2, neck = J('neck01'), neckY = neck[1], zC = 0.005 * k;
  const trunk = (y, m = mTrunk) => hullOf(section(S, m, [0, y, zC], UP, X, Z));
  const parts = [], lines = [], hide = new Set();
  const part = (name, group, mesh, bd, paint = {}) => { const pn = mesh.pos.length / 3, pos = Float32Array.from(mesh.pos); parts.push({ name, group, n: pn, pos, idx: Uint32Array.from(mesh.idx), si: bd.si, sw: bd.sw, out: new Float32Array(pn * 3), nrm: new Float32Array(pn * 3), ...paint }); return parts[parts.length - 1]; };
  const line = (name, on, pts, cand, remap, o = {}) => { const pos = Float32Array.from(pts.flat()), bd = o.bone !== undefined ? rigid(pts.length, o.bone) : bind(B, sk, pos, cand, remap); lines.push({ name, on, n: pts.length, pos, si: bd.si, sw: bd.sw, out: new Float32Array(pts.length * 3), alpha: o.alpha ?? 0.6, lw: o.lw ?? 0.85 }); };
  const waistTop = hipY + 0.105 * k;
  let pelvR = null, pelvCand = null, beltTop = waistTop - 0.004 * k, beltLo = waistTop - 0.036 * k;
  const covered = new Uint8Array(n);

  // ================= trousers (with the belt)
  if (w.trousers) {
    const tw = w.trousers, fit = FITS[tw.fit] ?? FITS.straight, ease = 0.012 * k;
    // where the legs part: the lowest height at which the body still crosses its own middle
    let crotch = hipY - 0.14 * k;
    for (let y = hipY - 0.03 * k; y > hipY - 0.26 * k; y -= 0.004 * k) if (!spansMiddle(S, mNoArm, y)) { crotch = y - 0.008 * k; break; }
    // the seat: from the crotch to the waistband, round the hips
    const ys = [], hs = []; const nl = Math.max(4, Math.ceil((waistTop - crotch) / (0.02 * k)));
    for (let i = 0; i <= nl; i++) { const y = lerp(crotch, waistTop, i / nl), h = trunk(y, mNoArm); if (!h) continue; ys.push(y); hs.push(h); }
    const fl = hs.map((h) => grown(h, 0.006 * k));
    const rs = hs.map((h, i) => { const r = grown(h, ys[i] > waistTop - 0.04 * k ? 0.008 * k : ease); blur(r, 1); for (let j = 0; j < NR; j++) r[j] = Math.max(r[j], fl[i][j]); return r; });
    smoothUp(rs, fl, 2);
    pelvR = field(ys, rs);
    const seatRings = rs.map((r, i) => ringPts(r, [0, ys[i], zC], X, Z)), seat = loft(seatRings), seatCap = loft([seatRings[0].map((p) => [p[0] * 0.985, p[1] + 0.002 * k, zC + (p[2] - zC) * 0.985]), seatRings[0].map((p) => [p[0] * 0.5, p[1] + 0.002 * k, zC + (p[2] - zC) * 0.5])], { capB: true });
    pelvCand = list(mask((v) => arm(v) < 0.5 && P[v * 3 + 1] < waistTop + 0.06 * k && P[v * 3 + 1] > crotch - 0.1 * k));
    const remap = new Map(); B.names.forEach((nm, i) => { const m = /^(foot|toe.*)\.(L|R)$/.exec(nm); if (m && B.bi[`lowerleg02.${m[2]}`] !== undefined) remap.set(i, B.bi[`lowerleg02.${m[2]}`]); });
    const meshes = [seat], binds = [bind(B, sk, Float32Array.from(seat.pos), pelvCand, remap)], inner = [[seatCap, bind(B, sk, Float32Array.from(seatCap.pos), pelvCand, remap)]];
    for (const sx of [1, -1]) {
      const sd = sx > 0 ? 'L' : 'R', hip = J(`upperleg01.${sd}`), knee = J(`lowerleg01.${sd}`), ank = J(`foot.${sd}`), mLeg = mask((v) => arm(v) < 0.5 && sx * P[v * 3] > -0.003);
      const axis = (y) => { const [a, c] = y > knee[1] ? [hip, knee] : [knee, ank], u = (a[1] - y) / (a[1] - c[1]); return [lerp(a[0], c[0], u), y, lerp(a[2], c[2], u)]; };
      const uOf = (y) => (hip[1] - y) / (hip[1] - ank[1]), yHem = 0.082 * k, uH = uOf(yHem), E = (j) => 1 / Math.sqrt((COS[j] / 0.9) ** 2 + (SIN[j] / 1.22) ** 2);
      const yT = crotch, lv = []; for (let y = yT; y > yHem + 0.035 * k; y -= 0.026 * k) lv.push(y);
      // the leg hangs round a straight middle line: from the thigh's own middle to the ankle's (a leg is not straight, a trouser leg is)
      const midOf = (y) => { const c = axis(y), h = hullOf(section(S, mLeg, c, UP, X, Z)); if (!h) return c; let mx = 0, mz = 0; for (let j = 0; j < NR; j++) { mx += h[j] * COS[j]; mz += h[j] * SIN[j]; } return [c[0] + mx / NR, y, c[2] + mz / NR]; };
      const yA = lerp(hip[1], ank[1], 0.2), yZ = ank[1] + 0.07 * k, mA = midOf(yA), mZ = midOf(yZ), fall = (y) => { const u = (yA - y) / (yA - yZ); return [lerp(mA[0], mZ[0], u), y, lerp(mA[2], mZ[2], u)]; };
      const centre = (y) => { if (y <= yA) return fall(y); const m = midOf(y), f = fall(y), g2 = smooth((y - yA) / (0.08 * k)); return [lerp(f[0], m[0], g2), y, lerp(f[2], m[2], g2)]; };
      let last = null, rT = 0.09 * k; const cs = lv.map(centre), hl = lv.map((y, i) => { const h = hullOf(section(S, mLeg, cs[i], UP, X, Z)); if (h) last = h; return h ?? last; });
      let hT = null; { let best = 1e9; lv.forEach((y, i) => { const dd = Math.abs(uOf(y) - 0.2); if (hl[i] && dd < best) { best = dd; rT = mean(hl[i]); hT = hl[i]; } }); }
      const want = (u) => lerp(rT * fit.thigh + ease, fit.hem * k, clamp((u - 0.2) / (uH - 0.2)));
      const rl = lv.map((y, i) => { const u = uOf(y), h = hl[i] ?? new Float32Array(NR).fill(rT), r = new Float32Array(NR), fth = 1 + (fit.thigh - 1) * smooth((u - 0.08) / 0.12), b = smooth((u - 0.2) / 0.6), g1 = grown(h, ease), g0 = grown(h, 0.75 * ease), sT = hT ?? h;
        for (let j = 0; j < NR; j++) r[j] = u < 0.2 ? g1[j] + h[j] * (fth - 1) : Math.max(g1[j], want(u) * ((1 - b) * sT[j] / rT + b * E(j)));
        blur(r, 1); for (let j = 0; j < NR; j++) r[j] = Math.max(r[j], g0[j]); return r; });
      // at the crotch a leg begins as its half of the seat's lowest ring (no ledge where the legs leave the seat), and
      // turns into its own cut over the next hand's breadth
      const seat0 = ringPts(rs[0], [0, ys[0], zC], X, Z), halfSeat = (c) => { const r = new Float32Array(NR); for (let j = 0; j < NR; j++) { let best = 1e9; for (let q = 0; q < NR; q++) { const a = seat0[q], b2 = seat0[(q + 1) % NR], px = a[0] - c[0], pz = a[2] - c[2], ex = b2[0] - a[0], ez = b2[2] - a[2], den = ex * SIN[j] - ez * COS[j]; if (Math.abs(den) < 1e-12) continue; const tt = -(px * SIN[j] - pz * COS[j]) / den; if (tt < -1e-6 || tt > 1 + 1e-6) continue; const rr = (px + tt * ex) * COS[j] + (pz + tt * ez) * SIN[j]; if (rr > 0 && rr < best) best = rr; }
        if (COS[j] * sx < -1e-6) best = Math.min(best, (sx * 0.003 * k - c[0]) / COS[j]); r[j] = best < 1e8 ? Math.max(0.004 * k, best) : rl[0][j]; } return r; };
      lv.forEach((y, i) => { const gq = smooth((crotch - y) / (0.13 * k)); if (gq >= 1) return; const hs2 = halfSeat(cs[i]), g0 = grown(hl[i] ?? rl[i], 0.6 * ease); for (let j = 0; j < NR; j++) rl[i][j] = Math.max(lerp(hs2[j], rl[i][j], gq), COS[j] * sx < 0 ? 0 : g0[j] * (1 - gq) * 0); });
      const rings = rl.map((r, i) => ringPts(r, cs[i], X, Z));
      rings.unshift(rings[0].map((p) => [lerp(cs[0][0], p[0], 0.94), p[1] + 0.022 * k, lerp(cs[0][2], p[2], 0.94)]));
      // the hem: a little higher in front, where it rests on the shoe
      const ch = fall(yHem), hem = Array.from({ length: NR }, (_, j) => { const r = fit.hem * k * E(j); return [ch[0] + r * COS[j], yHem + 0.008 * k * SIN[j], ch[2] + r * SIN[j]]; });
      rings.push(hem);
      for (const r of rings) for (const p of r) if (sx * p[0] < 0.003 * k) p[0] = sx * 0.003 * k; // the two legs meet in the middle, they never run through each other
      const leg = loft(rings), cand = list(mask((v) => arm(v) < 0.5 && sx * P[v * 3] > -0.01 && P[v * 3 + 1] < waistTop));
      meshes.push(leg); binds.push(bind(B, sk, Float32Array.from(leg.pos), cand, remap));
      // inside the hem it is dark
      const up = hem.map((p) => { const c = [ch[0], p[1] + 0.02 * k, ch[2]]; return [lerp(c[0], p[0], 0.97), c[1], lerp(c[2], p[2], 0.97)]; }), cap = loft([up, up.map((p) => [lerp(ch[0], p[0], 0.5), p[1], lerp(ch[2], p[2], 0.5)])], { capB: true });
      inner.push([cap, bind(B, sk, Float32Array.from(cap.pos), cand, remap)]);
      // the crease down the front of the leg; the side pocket
      const i0 = lv.findIndex((y) => uOf(y) > 0.24) + 1, cr = []; for (let i = Math.max(1, i0); i < rings.length; i++) { const p = rings[i][JF]; cr.push([p[0], p[1], p[2] + 0.001]); }
      line(`crease.${sd}`, 'trousers', cr, cand, remap, { alpha: 0.5, lw: 0.8 });
      // the break: where the leg rests on the shoe the cloth folds once across its front
      { const r2 = rings[rings.length - 2], bk = []; for (let q = -4; q <= 4; q++) { const p = r2[JF + q]; bk.push([p[0], p[1] - 0.012 * k * (1 - (q / 4) ** 2), p[2] + 0.001]); } line(`break.${sd}`, 'trousers', bk, cand, remap, { alpha: 0.5, lw: 0.75 }); }
      const pk = []; for (let q = 0; q <= 8; q++) { const u = q / 8, th = (sx > 0 ? lerp(34, 4, u) : lerp(146, 176, u)) * Math.PI / 180, y = lerp(waistTop - 0.04 * k, waistTop - 0.19 * k, u), r = pelvR(y, th) + 0.001; pk.push([r * Math.cos(th), y, zC + r * Math.sin(th)]); }
      line(`pocket.${sd}`, 'trousers', pk, pelvCand, remap, { alpha: 0.6, lw: 0.8 });
    }
    const all = join(meshes), si = new Uint16Array(all.pos.length / 3 * 4), sw = new Float32Array(all.pos.length / 3 * 4); let o4 = 0; for (const b of binds) { si.set(b.si, o4); sw.set(b.sw, o4); o4 += b.si.length; }
    part('trousers', 'trousers', all, { si, sw }, { color: tw.color, shade: tw.shade, two: true, layer: 1 });
    { const m = join(inner.map((q) => q[0])), si2 = new Uint16Array(m.pos.length / 3 * 4), sw2 = new Float32Array(m.pos.length / 3 * 4); let o = 0; for (const q of inner) { si2.set(q[1].si, o); sw2.set(q[1].sw, o); o += q[1].si.length; } part('trousers-in', 'trousers', m, { si: si2, sw: sw2 }, { color: tw.shade ?? tw.color, dark: 0.45, flat: true, two: true, layer: 1 }); }
    // the fly: a line down from the waistband, turning in to the middle
    { const fl = []; for (let q = 0; q <= 8; q++) { const u = q / 8, y = lerp(beltLo - 0.004 * k, crotch + 0.045 * k, u), x = 0.014 * k * (1 - smooth((u - 0.7) / 0.3)), r0 = pelvR(y, Math.PI / 2), th = Math.acos(clamp(x / r0, -1, 1)), r = pelvR(y, th) + 0.001; fl.push([r * Math.cos(th), y, zC + r * Math.sin(th)]); } line('fly', 'trousers', fl, pelvCand, remap, { alpha: 0.55, lw: 0.8 }); }
    hide.add('trousers');
    for (let v = 0; v < n; v++) if (arm(v) < 0.5 && P[v * 3 + 1] < waistTop - 0.012 * k && P[v * 3 + 1] > 0.115 * k) covered[v] = 1;
    if (w.belt) {
      const bw = w.belt, lip = 0.006 * k, ring = (y, more) => { const r = new Float32Array(NR); for (let j = 0; j < NR; j++) r[j] = pelvR(y, j * DT) + more; return ringPts(r, [0, y, zC], X, Z); };
      const belt = loft([ring(beltLo, 0.004 * k - lip), ring(beltLo, 0.004 * k), ring(beltTop, 0.004 * k), ring(beltTop, 0.004 * k - lip)]);
      part('belt', 'belt', belt, bind(B, sk, Float32Array.from(belt.pos), pelvCand), { color: bw.color ?? '#141416', two: true, layer: 2 });
      const zf = zC + pelvR((beltLo + beltTop) / 2, Math.PI / 2) + 0.008 * k, cy = (beltLo + beltTop) / 2, quad = (hw, hh, z) => ({ pos: [-hw, cy - hh, z, hw, cy - hh, z, hw, cy + hh, z, -hw, cy + hh, z], idx: [0, 1, 2, 0, 2, 3] });
      const bk = quad(0.024 * k, 0.021 * k, zf), bi2 = quad(0.014 * k, 0.012 * k, zf + 0.002 * k);
      part('buckle', 'buckle', bk, bind(B, sk, Float32Array.from(bk.pos), pelvCand), { color: bw.buckle ?? '#b9bdc6', metal: true, two: true, layer: 3 });
      part('buckle-in', 'buckle-in', bi2, bind(B, sk, Float32Array.from(bi2.pos), pelvCand), { color: bw.color ?? '#141416', flat: true, two: true, layer: 4 });
    }
  }

  // ================= shirt (collar, placket, pocket, tie)
  if (w.shirt) {
    const sw_ = w.shirt, e = sw_.ease ?? 0.016 * k, shL = J('upperarm01.L'), shR = J('upperarm01.R'), shY = (shL[1] + shR[1]) / 2, open = sw_.collar?.open !== false;
    // (kind: 'tee' — a T-shirt: no collar, placket, pocket or buttons; a ribbed band round a wider, lower neck; worn out over the trousers)
    const tee = sw_.kind === 'tee', nk = tee ? 0.016 * k : 0, drop = tee ? 0.016 * k : 0;
    const g = (open ? 22 : 8) * Math.PI / 180, bandH = 0.034 * k;
    // the neck, for the collar's band: its outline at its base and a little higher, round its own (leaning) axis
    const n3 = J('neck03') ?? add(neck, [0, 0.1, 0.035]), nz = (y) => neck[2] + (y - neckY) * (n3[2] - neck[2]) / Math.max(0.02, n3[1] - neck[1]);
    const rn0 = hullOf(section(S, mNoArm, [0, neckY, nz(neckY)], UP, X, Z)) ?? new Float32Array(NR).fill(0.062 * k), rn1 = hullOf(section(S, mNoArm, [0, neckY + 0.022 * k, nz(neckY + 0.022 * k)], UP, X, Z)) ?? rn0;
    const baseY = (th) => neckY - 0.012 * k - drop - (tee ? 0.019 : 0.012) * k * Math.sin(th), bandPt = (th, up, more = 0) => { const y = baseY(th) + up, r = lerp(polar(rn0, th), polar(rn1, th), clamp((y - neckY) / (0.022 * k))) + 0.006 * k + nk + more; return [r * Math.cos(th), y, nz(y) + r * Math.sin(th)]; };
    // the body of the shirt: rings from inside the trousers up to the shoulders; the cloth hangs from the chest and the
    // shoulder blades (it does not follow the waist in), is gathered by the belt and stands out a little over it
    const yB = waistTop - (tee ? 0.12 : 0.05) * k, yS = shY - 0.03 * k, yTopH = Math.min(neckY - 0.03 * k, baseY(Math.PI / 2) - 0.008 * k), ys = [];
    for (let y = yB; y < yS; y += 0.022 * k) ys.push(y); for (let y = yS; y < yTopH; y += 0.009 * k) ys.push(y); ys.push(yTopH);
    const Rs = 0.058 * k, withHeads = (y) => { const r = section(S, mTrunk, [0, y, zC], UP, X, Z); for (const c of [shL, shR]) { const dy = y - c[1]; if (Math.abs(dy) >= Rs) continue; const rho = Math.sqrt(Rs * Rs - dy * dy); for (let q = 0; q < 16; q++) { const a0 = q * TAU / 16, a1 = (q + 1) * TAU / 16; rays(r, c[0] + rho * Math.cos(a0), c[2] - zC + rho * Math.sin(a0), c[0] + rho * Math.cos(a1), c[2] - zC + rho * Math.sin(a1)); } } return hullOf(r); };
    let last = null; const hs = ys.map((y) => { const h = withHeads(y); if (h) last = h; return h ?? last; }).map((h) => h ?? new Float32Array(NR).fill(0.14 * k));
    const D = hs.map((h) => grown(h, e)), F7 = hs.map((h) => grown(h, 0.007 * k)), F75 = hs.map((h) => grown(h, 0.75 * e));
    for (let i = ys.length - 2; i >= 0; i--) for (let j = 0; j < NR; j++) D[i][j] = Math.max(D[i][j], D[i + 1][j] - 0.35 * (ys[i + 1] - ys[i]));
    const rs = ys.map((y, i) => { const r = new Float32Array(NR), gt = smooth((y - waistTop) / (0.065 * k)), bump = 0.006 * k * Math.exp(-(((y - waistTop - 0.042 * k) / (0.02 * k)) ** 2)); const over = (j) => (pelvR && y < waistTop + 0.02 * k ? pelvR(y, j * DT) + 0.009 * k : 0); // (worn out, a T-shirt hangs from the chest over the trousers; tucked in, a shirt is gathered by the belt)
      for (let j = 0; j < NR; j++) r[j] = tee ? Math.max(D[i][j], over(j)) : y < waistTop ? hs[i][j] + 0.004 * k : lerp(F7[i][j], D[i][j], gt) + bump; blur(r, 1); for (let j = 0; j < NR; j++) r[j] = Math.max(r[j], tee ? Math.max(F75[i][j], over(j) - 0.002 * k) : y < waistTop ? hs[i][j] + 0.004 * k : lerp(F7[i][j], F75[i][j], gt)); return r; });
    const i7 = ys.findIndex((y) => y > waistTop + 0.08 * k); smoothUp(rs, F75, 1, i7, ys.length - 1);
    const shR_ = field(ys, rs);
    // on the shirt: the radius of its shell at a height and a direction (above its last level it rises to the collar's band)
    const shell = (y, th) => { if (y <= yTopH) return shR_(y, th); const yb = baseY(th), pb = bandPt(th, 0), rb = Math.hypot(pb[0], pb[2] - zC); return lerp(shR_(yTopH, th), rb, clamp((y - yTopH) / Math.max(0.004, yb - yTopH))); };
    // a point on the front of the shirt at x across and y up, `lift` off the cloth
    const front = (x, y, lift = 0) => { let a = 0, b = Math.PI; for (let it = 0; it < 28; it++) { const m = (a + b) / 2; if ((shell(y, m) + lift) * Math.cos(m) > x) a = m; else b = m; } const th = (a + b) / 2, r = shell(y, th) + lift; return [x, y, zC + r * Math.sin(th)]; };
    const outw = (p) => [p[0], 0, p[2] - zC];
    const rings = rs.map((r, i) => ringPts(r, [0, ys[i], zC], X, Z)); rings.push(Array.from({ length: NR }, (_, j) => bandPt(j * DT, 0, -0.001 * k)));
    const body = loft(rings), cand = list(mask((v) => arm(v) < 0.5 && headW[v] < 0.5 && P[v * 3 + 1] > hipY - 0.05 * k));
    part('shirt', 'shirt', body, bind(B, sk, Float32Array.from(body.pos), cand), { color: sw_.color, shade: sw_.shade, two: true, layer: 2 });
    for (let v = 0; v < n; v++) { if (!(arm(v) < 0.5 && headW[v] < 0.5) || P[v * 3 + 1] < waistTop - 0.012 * k) continue; const y = P[v * 3 + 1]; if (y < yTopH) covered[v] = 1; else if (y < neckY + 0.03 * k) { const th = Math.atan2(P[v * 3 + 2] - nz(y), P[v * 3]); if (Math.hypot(P[v * 3], P[v * 3 + 2] - nz(y)) > polar(rn0, th) + 0.004 * k + nk) covered[v] = 1; } }
    // where the body bends the cloth folds: two short lines from each armpit towards the chest, a few over the belt
    { const on = (th, y) => { const r = shell(y, th) + 0.0012; return [r * Math.cos(th), y, zC + r * Math.sin(th)]; }, Dg = Math.PI / 180, fold = (name, a0, y0, a1, y1, sag = 0) => { const pts = []; for (let q = 0; q <= 6; q++) { const u = q / 6; pts.push(on(lerp(a0, a1, u) * Dg, lerp(y0, y1, u) - sag * Math.sin(Math.PI * u))); } line(name, 'shirt', pts, cand, null, { alpha: 0.45, lw: 0.75 }); };
      for (const sx of [1, -1]) { const m = (a) => (sx > 0 ? a : 180 - a), sd = sx > 0 ? 'L' : 'R';
        fold(`fold.pit1.${sd}`, m(22), shY - 0.1 * k, m(46), shY - 0.15 * k, 0.006 * k); fold(`fold.pit2.${sd}`, m(16), shY - 0.135 * k, m(37), shY - 0.195 * k, 0.006 * k); }
      if (w.trousers && !tee) [[56, 0.05, 4], [80, 0.062, -3], [104, 0.055, 3], [127, 0.046, -4]].forEach(([a, h, lean], i) => fold(`fold.belt${i}`, a, beltTop + 0.006 * k, a + lean, beltTop + h * k)); }
    // sleeves: tubes round the upper arm that stand off it, each ending in a hem band
    const sleeves = [], sbind = [], cuffs = [], cbind = [], ins = [], ibind = [];
    for (const sx of [1, -1]) {
      const sd = sx > 0 ? 'L' : 'R', S0 = J(`upperarm01.${sd}`), El = J(`lowerarm01.${sd}`), a = norm(sub(El, S0)), len = Math.hypot(El[0] - S0[0], El[1] - S0[1], El[2] - S0[2]), Ls = (sw_.sleeve === 'long' ? 0.98 : 0.58) * len;
      const v = norm(sub(Z, mul(a, dot(a, Z)))), u = cross(v, a), aw = sx > 0 ? armL : armR, mArm = mask((q) => aw[q] >= 0.5), at = (t) => add(S0, mul(a, t));
      const ts = []; for (let t = 0; t < Ls - 0.012 * k; t += 0.02 * k) ts.push(t); ts.push(Ls);
      let lastH = null; const rr = ts.map((t) => { const h = hullOf(section(S, mArm, at(Math.max(t, 0.03 * k)), a, u, v)); if (h) lastH = h; const hh = h ?? lastH ?? new Float32Array(NR).fill(0.05 * k), r = new Float32Array(NR), es = lerp(0.012 * k, 0.024 * k, clamp(t / Ls)), rmin = lerp(0.066 * k, 0.056 * k, clamp(t / Ls)); const gg = grown(hh, es), g0 = grown(hh, 0.7 * es); for (let j = 0; j < NR; j++) r[j] = Math.max(gg[j], rmin); blur(r, 2); for (let j = 0; j < NR; j++) r[j] = Math.max(r[j], g0[j]); return r; });
      const R0 = Math.min(mean(rr[0]) * 0.92, 0.058 * k + 0.012 * k), dome = [80, 62, 44, 26, 10].map((deg) => { const ph = deg * Math.PI / 180, r = rr[0].map((q) => q * Math.cos(ph)); return ringPts(r, at(-R0 * Math.sin(ph)), u, v); });
      const sl = loft([...dome, ...ts.map((t, i) => ringPts(rr[i], at(t), u, v))], { capA: true }), acand = list(mask((q) => aw[q] >= 0.5 && dot(sub([P[q * 3], P[q * 3 + 1], P[q * 3 + 2]], S0), a) < Ls + 0.06 * k));
      sleeves.push(sl); sbind.push(bind(B, sk, Float32Array.from(sl.pos), acand));
      const rE = rr[rr.length - 1], cf = loft([ringPts(rE, at(Ls - 0.02 * k), u, v, 0.0025 * k), ringPts(rE, at(Ls + 0.0008), u, v, 0.0025 * k)]);
      cuffs.push(cf); cbind.push(bind(B, sk, Float32Array.from(cf.pos), acand));
      const hE = hullOf(section(S, mArm, at(Ls - 0.03 * k), a, u, v)) ?? rE.map((q) => q - 0.03 * k), inn = loft([ringPts(rE, at(Ls), u, v, 0.001 * k), ringPts(hE, at(Ls - 0.03 * k), u, v, 0.002 * k)]);
      ins.push(inn); ibind.push(bind(B, sk, Float32Array.from(inn.pos), acand));
      for (let q = 0; q < n; q++) if (aw[q] >= 0.5 && dot(sub([P[q * 3], P[q * 3 + 1], P[q * 3 + 2]], S0), a) < Ls - 0.03 * k) covered[q] = 1;
    }
    const cat = (ms, bs) => { const m = join(ms), si = new Uint16Array(m.pos.length / 3 * 4), sw2 = new Float32Array(m.pos.length / 3 * 4); let o = 0; for (const b of bs) { si.set(b.si, o); sw2.set(b.sw, o); o += b.si.length; } return [m, { si, sw: sw2 }]; };
    part('sleeve', 'shirt', ...cat(sleeves, sbind), { color: sw_.color, shade: sw_.shade, two: true, layer: 2, limb: true });
    part('cuff', 'cuff', ...cat(cuffs, cbind), { color: sw_.color, shade: sw_.shade, two: true, layer: 3, limb: true });
    part('sleeve-in', 'shirt', ...cat(ins, ibind), { color: sw_.shade ?? sw_.color, dark: sw_.shade ? 0.12 : 0.3, flat: true, two: true, layer: 2, limb: true });
    const ncand0 = list(mask((v) => arm(v) < 0.5 && P[v * 3 + 1] > shY - 0.12 * k && P[v * 3 + 1] < neckY + 0.06 * k));
    if (tee) { const rb = loft([Array.from({ length: NR }, (_, j) => bandPt(j * DT, -0.003 * k, 0.0025 * k)), Array.from({ length: NR }, (_, j) => bandPt(j * DT, 0.014 * k, 0.001 * k))]); part('rib', 'rib', rb, bind(B, sk, Float32Array.from(rb.pos), ncand0), { color: sw_.color, shade: sw_.shade, two: true, layer: 5 }); }
    if (!tee) {
    // the collar: a band round the neck, open in front, and two leaves folded over it that end in points on the chest
    const ncand = list(mask((v) => arm(v) < 0.5 && P[v * 3 + 1] > shY - 0.12 * k && P[v * 3 + 1] < neckY + 0.06 * k));
    const lay = (p, lift) => { const th = Math.atan2(p[2] - zC, p[0]), rho = Math.hypot(p[0], p[2] - zC), want = shell(p[1], th) + lift; return rho >= want ? p : [want * Math.cos(th), p[1], zC + want * Math.sin(th)]; };
    const cm = [], band = []; { const lo = [], hi = []; for (let q = 0; q <= 36; q++) { const th = Math.PI / 2 + g + (TAU - 2 * g) * q / 36; lo.push(bandPt(th, 0)); hi.push(bandPt(th, bandH, -0.002 * k)); } band.push(lo, hi); cm.push(sheet(band, outw)); }
    const ft = sw_.collar?.point ?? 0.09 * k, fb = 0.038 * k;
    for (const sx of [1, -1]) {
      const F = [], M = [], O = [];
      for (let q = 0; q <= 18; q++) { const s = q / 18, th = Math.PI / 2 - sx * (g + s * (Math.PI - g)), f = bandPt(th, bandH, 0.001 * k), er = [Math.cos(th), 0, Math.sin(th)], t = [sx * Math.sin(th), 0, -sx * Math.cos(th)];
        const fall = fb + (ft - fb) * Math.pow(1 - smooth(s / 0.45), 1.6), out = 0.014 * k + 0.022 * k * (1 - smooth(s / 0.5)), spread = 0.034 * k * (1 - smooth(s / 0.3));
        const o = lay([f[0] + er[0] * out + t[0] * spread, f[1] - fall, f[2] + er[2] * out + t[2] * spread], 0.005 * k), m = lay(add(mix(f, o, 0.5), mul(er, 0.004 * k)), 0.007 * k);
        F.push(f); M.push(m); O.push(o); }
      // (laid on the cloth point by point, the leaf's edge takes every step of the shell under it: drawn, it is one clean line)
      for (const row of [O, M]) for (let it = 0; it < 3; it++) { const c = row.map((q) => q.slice()); for (let q = 2; q < row.length - 1; q++) for (let a = 0; a < 3; a++) row[q][a] = 0.25 * c[q - 1][a] + 0.5 * c[q][a] + 0.25 * c[q + 1][a]; }
      cm.push(sheet([F, M, O], outw));
    }
    const col = join(cm); part('collar', 'collar', col, bind(B, sk, Float32Array.from(col.pos), ncand), { color: sw_.color, shade: sw_.shade, two: true, layer: 5 });
    const yFront = baseY(Math.PI / 2);
    if (open) { // the open top button: a V of skin between the leaves
      const A = bandPt(Math.PI / 2 - g, 0.004 * k), Bp = bandPt(Math.PI / 2 + g, 0.004 * k), yC = yFront - 0.055 * k, rows = [];
      for (let q = 0; q <= 5; q++) { const u = q / 5, y = lerp(A[1], yC, u), hw = A[0] * (1 - u); rows.push([-1, 0, 1].map((c) => (q === 0 ? [c * hw, y, lerp(A[2], Bp[2], 0.5) - 0.004 * k] : front(c * hw, y, 0.0015 * k)))); }
      const th = sheet(rows, outw); part('throat', 'skin', th, bind(B, sk, Float32Array.from(th.pos), ncand), { skin: true, layer: 3 });
    }
    const tcand = cand, yPl = yFront - 0.028 * k, yEnd = w.trousers ? beltTop + 0.002 * k : yB;
    { const rows = []; for (let y = yPl; y > yEnd; y -= 0.03 * k) rows.push([-0.016 * k, 0, 0.016 * k].map((x) => front(x, y, 0.003 * k))); rows.push([-0.016 * k, 0, 0.016 * k].map((x) => front(x, yEnd, 0.003 * k)));
      const pl = sheet(rows, outw); part('placket', 'placket', pl, bind(B, sk, Float32Array.from(pl.pos), tcand), { color: sw_.color, shade: sw_.shade, layer: 3 });
      const bt = []; for (let y = yPl - 0.03 * k; y > yEnd + 0.03 * k; y -= 0.085 * k) { const c = front(0, y, 0.0065 * k), r = 0.0055 * k, o = bt.length ? 0 : 0, m = { pos: [c[0], c[1], c[2]], idx: [] }; for (let q = 0; q < 8; q++) { m.pos.push(c[0] + r * Math.cos(q * TAU / 8), c[1] + r * Math.sin(q * TAU / 8), c[2]); m.idx.push(0, 1 + q, 1 + (q + 1) % 8); } void o; bt.push(m); }
      if (bt.length) { const m = join(bt); part('buttons', 'button', m, bind(B, sk, Float32Array.from(m.pos), tcand), { color: sw_.button ?? '#eef0f4', two: true, layer: 4 }); } }
    if (sw_.pocket !== false) { // a patch pocket on the wearer's left chest, its lower edge coming to a point
      const x0 = 0.058 * k, x1 = 0.148 * k, yT = shY - 0.105 * k, rows = [];
      for (let q = 0; q <= 5; q++) { const row = []; for (let c = 0; c <= 4; c++) { const x = lerp(x0, x1, c / 4), yb = yT - 0.1 * k - 0.014 * k * (1 - Math.abs(c / 2 - 1)); row.push(front(x, lerp(yT, yb, q / 5), 0.0035 * k)); } rows.push(row); }
      const pk = sheet(rows, outw); part('pocket', 'pocket', pk, bind(B, sk, Float32Array.from(pk.pos), tcand), { color: sw_.color, shade: sw_.shade, layer: 4 });
    }
    if (w.tie) { // a knot under the collar and a blade down to the belt, striped across
      const tw = w.tie, yk = yFront - (tw.loose ?? (open ? 0.03 * k : 0.002 * k)), rows = [], lift = 0.01 * k, kr = [];
      [[0, 0.027], [0.022, 0.023], [0.047, 0.018]].forEach(([dy, hw]) => kr.push([-1, 0, 1].map((c) => front(c * hw * k, yk - dy * k, lift + 0.005 * k))));
      const kn = sheet(kr, outw), ku = new Float32Array(kn.pos.length / 3); for (let v = 0; v < ku.length; v++) ku[v] = kn.pos[v * 3 + 1] - 0.9 * kn.pos[v * 3];
      const kb = []; if (tw.stripe) for (let q = Math.min(...ku) + 0.006 * k; q < Math.max(...ku); q += 0.034 * k) kb.push([q, q + 0.0075 * k, tw.stripe]);
      part('knot', 'knot', kn, bind(B, sk, Float32Array.from(kn.pos), tcand), { color: tw.color, shade: tw.shade, two: true, layer: 7, u: ku, bands: kb });
      const y0 = yk - 0.04 * k, yTip = (w.trousers ? beltTop : yB) + 0.006 * k, yW = yTip + 0.042 * k;
      for (let q = 0; q <= 12; q++) { const u = q / 12, y = lerp(y0, yW, u), hw = lerp(0.015 * k, 0.038 * k, Math.pow(u, 0.9)); rows.push([-1, 0, 1].map((c) => front(c * hw, y, lift))); }
      rows.push([-1, 0, 1].map((c) => front(c * 0.0005, yTip, lift)));
      const ti = sheet(rows, outw), u = new Float32Array(ti.pos.length / 3); let u0 = 1e9, u1 = -1e9; for (let v = 0; v < u.length; v++) { u[v] = ti.pos[v * 3 + 1] + 0.9 * ti.pos[v * 3]; u0 = Math.min(u0, u[v]); u1 = Math.max(u1, u[v]); }
      const bands = []; if (tw.stripe) for (let q = u0 + 0.01 * k; q < u1; q += 0.034 * k) bands.push([q, q + 0.0075 * k, tw.stripe]);
      part('tie', 'tie', ti, bind(B, sk, Float32Array.from(ti.pos), tcand), { color: tw.color, shade: tw.shade, two: true, layer: 6, u, bands });
    }
    }
    hide.add('tunic');
  }

  // ================= shoes
  if (w.shoes) {
    const sh = w.shoes, ups = [], soles = [], ub = [], sb = [];
    const pw = (s, pts) => { for (let i = 0; i < pts.length - 1; i++) if (s <= pts[i + 1][0]) { const [s0, v0] = pts[i], [s1, v1] = pts[i + 1]; return lerp(v0, v1, smooth((s - s0) / (s1 - s0))); } return pts[pts.length - 1][1]; };
    for (const sx of [1, -1]) {
      const sd = sx > 0 ? 'L' : 'R', bone = B.bi[`foot.${sd}`], A = J(`foot.${sd}`), tl = [B.tail[bone * 3], B.tail[bone * 3 + 1], B.tail[bone * 3 + 2]], f = norm([tl[0] - A[0], 0, tl[2] - A[2]]), sdv = [f[2], 0, -f[0]];
      const L = 0.3 * k, heel = add([A[0], 0, A[2]], mul(f, -0.074 * k)), NS = 22;
      const wOf = (s) => (s < 0.1 ? 0.037 * Math.sqrt(1 - ((0.1 - s) / 0.1) ** 2) : s < 0.72 ? pw(s, [[0.1, 0.037], [0.38, 0.039], [0.72, 0.053]]) : 0.053 * Math.pow(Math.max(0, 1 - ((s - 0.72) / 0.28) ** 2.6), 0.5)) * k;
      const thOf = (s) => pw(s, [[0, 0.028], [0.26, 0.028], [0.32, 0.015], [1, 0.014]]) * k, lowOf = (s) => (s > 0.27 && s < 0.5 ? 0.007 * k * Math.sin(Math.PI * (s - 0.27) / 0.23) : 0);
      const hOf = (s) => pw(s, [[0, 0.062], [0.12, 0.067], [0.4, 0.063], [0.56, 0.054], [0.76, 0.044], [0.92, 0.038], [1, 0.022]]) * k;
      const pt = (s, x, y) => [heel[0] + f[0] * s * L + sdv[0] * x, y, heel[2] + f[2] * s * L + sdv[2] * x];
      const sr = [], ur = [];
      for (let i = 0; i <= NS; i++) { const s = 0.004 + 0.992 * (i / NS) ** (i / NS < 0.5 ? 1 : 0.9), ww = Math.max(0.004 * k, wOf(s)) + 0.0045 * k, th = thOf(s), lo = lowOf(s);
        sr.push([[-ww, lo], [-ww * 0.6, lo], [ww * 0.6, lo], [ww, lo], [ww, th], [ww * 0.6, th], [-ww * 0.6, th], [-ww, th]].map(([x, y]) => pt(s, x, y)));
        const wu = Math.max(0.003 * k, wOf(s)), h = hOf(s), ex = s < 0.42 ? 0.55 : lerp(0.55, 0.8, clamp((s - 0.42) / 0.3)), ring = [];
        for (let q = 0; q <= 12; q++) { const ph = Math.PI * q / 12, c = Math.cos(ph), sn = Math.sin(ph); ring.push(pt(s, wu * Math.sign(c) * Math.pow(Math.abs(c), ex), th + h * Math.pow(sn, ex))); }
        ring.push(pt(s, -wu * 0.5, th - 0.001), pt(s, wu * 0.5, th - 0.001)); ur.push(ring); }
      const so = loft(sr, { capA: true, capB: true }), up = loft(ur, { capA: true, capB: true });
      soles.push(so); sb.push(rigid(so.pos.length / 3, bone)); ups.push(up); ub.push(rigid(up.pos.length / 3, bone));
      // the lines a leather shoe is drawn with: across the toe cap, and three laces over the instep
      const over = (s, x0, x1, lift = 0.0012) => { const wu = Math.max(0.003 * k, wOf(s)), th = thOf(s), h = hOf(s), ex = s < 0.42 ? 0.55 : lerp(0.55, 0.8, clamp((s - 0.42) / 0.3)), pts = []; for (let q = 0; q <= 8; q++) { const x = lerp(x0, x1, q / 8), c = clamp(x, -0.999, 0.999), ph = Math.acos(Math.sign(c) * Math.pow(Math.abs(c), 1 / ex)); pts.push(pt(s, wu * c, th + h * Math.pow(Math.sin(ph), ex) + lift)); } return pts; };
      line(`cap.${sd}`, 'shoe', over(0.8, -0.9, 0.9), null, null, { bone, alpha: 0.5, lw: 0.75 });
      [0.5, 0.56, 0.62].forEach((sl, i) => line(`lace${i}.${sd}`, 'shoe', over(sl, -0.42, 0.42, 0.002), null, null, { bone, alpha: 0.6, lw: 0.75 }));
    }
    const cat = (ms, bs) => { const m = join(ms), si = new Uint16Array(m.pos.length / 3 * 4), sw2 = new Float32Array(m.pos.length / 3 * 4); let o = 0; for (const b of bs) { si.set(b.si, o); sw2.set(b.sw, o); o += b.si.length; } return [m, { si, sw: sw2 }]; };
    part('shoe', 'shoe', ...cat(ups, ub), { color: sh.color ?? '#30303a', shade: sh.shade, leather: true, layer: 1 });
    part('sole', 'sole', ...cat(soles, sb), { color: sh.sole ?? sh.color ?? '#0c0c0e', flat: true, layer: 1 });
    hide.add('shoes'); hide.add('boots');
    for (let v = 0; v < n; v++) if (arm(v) < 0.5 && P[v * 3 + 1] < 0.118 * k) covered[v] = 1; // the feet are in the shoes
  }
  // the skin left to draw: what no cloth covers
  const keep = (idx) => { if (!idx) return idx; const o = []; for (let t = 0; t < idx.length; t += 3) if (!(covered[idx[t]] && covered[idx[t + 1]] && covered[idx[t + 2]])) o.push(idx[t], idx[t + 1], idx[t + 2]); return Uint32Array.from(o); };
  return (B.wear = { key, parts, lines, hide, skin: { idxCore: keep(sk.idxCore), idxLimb: keep(sk.idxLimb) } });
}
/** The measuring tools, for the rules that test them. */
export const measure = { section, hullOf, NR };
