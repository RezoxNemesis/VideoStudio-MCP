// Sensei's measuring tools: distances between body capsules, body frames from joints, robust statistics.
export const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
export const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
export const mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
export const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
export const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
export const len = (a) => Math.hypot(a[0], a[1], a[2]);
export const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
export const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
export const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2];
export const lerp3 = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
export const flat = (a) => [a[0], 0, a[2]];
export const deg = (r) => (r * 180) / Math.PI;
export const angleBetween = (a, b) => { const la = len(a), lb = len(b); if (la < 1e-9 || lb < 1e-9) return 0; return deg(Math.acos(Math.max(-1, Math.min(1, dot(a, b) / (la * lb))))); };
/** signed yaw (degrees) from horizontal direction a to b */
export const yawBetween = (a, b) => { const x = a[0] * b[2] - a[2] * b[0], d = a[0] * b[0] + a[2] * b[2]; return deg(Math.atan2(x, d)); };

/** closest distance between segments p1-q1 and p2-q2 → { d, s, t } (s, t: where along each, 0…1) */
export function segSeg(p1, q1, p2, q2) {
  const d1 = sub(q1, p1), d2 = sub(q2, p2), r = sub(p1, p2);
  const a = dot(d1, d1), e = dot(d2, d2), f = dot(d2, r);
  let s, t;
  if (a <= 1e-12 && e <= 1e-12) return { d: len(r), s: 0, t: 0 };
  if (a <= 1e-12) { s = 0; t = Math.max(0, Math.min(1, f / e)); }
  else {
    const c = dot(d1, r);
    if (e <= 1e-12) { t = 0; s = Math.max(0, Math.min(1, -c / a)); }
    else {
      const b = dot(d1, d2), den = a * e - b * b;
      s = den > 1e-12 ? Math.max(0, Math.min(1, (b * f - c * e) / den)) : 0;
      t = (b * s + f) / e;
      if (t < 0) { t = 0; s = Math.max(0, Math.min(1, -c / a)); } else if (t > 1) { t = 1; s = Math.max(0, Math.min(1, (b - c) / a)); }
    }
  }
  const c1 = add(p1, mul(d1, s)), c2 = add(p2, mul(d2, t));
  return { d: dist(c1, c2), s, t, at: mid(c1, c2) };
}
/** distance from point p to segment a-b */
export function pointSeg(p, a, b) { const ab = sub(b, a), l2 = dot(ab, ab); const t = l2 > 1e-12 ? Math.max(0, Math.min(1, dot(sub(p, a), ab) / l2)) : 0; return dist(p, add(a, mul(ab, t))); }

/**
 * The body as capsules (segments with a radius), from the baked joints. Radii are for a 1.8 m adult and scale with height.
 * Names say which side and part; `group` keeps neighbours from being tested against each other.
 */
export const CAPSULES = [
  // torso: two side-by-side capsules give it width (a single round one would make a barrel)
  { name: 'chest', a: 'spine03', b: 'neck01', r: 0.098, off: 0.06, group: 'torso' },
  { name: 'chest', a: 'spine03', b: 'neck01', r: 0.098, off: -0.06, group: 'torso' },
  { name: 'belly', a: 'root', b: 'spine03', r: 0.11, off: 0.04, group: 'torso' },
  { name: 'belly', a: 'root', b: 'spine03', r: 0.11, off: -0.04, group: 'torso' },
  { name: 'head', a: 'head', b: 'head:tail', r: 0.095, group: 'head' },
  { name: 'neck', a: 'neck01', b: 'head', r: 0.055, group: 'neck' },
  { name: 'upper arm', side: 'L', a: 'upperarm01.L', b: 'lowerarm01.L', r: 0.048, group: 'armL' },
  { name: 'forearm', side: 'L', a: 'lowerarm01.L', b: 'wrist.L', r: 0.04, group: 'armL' },
  { name: 'hand', side: 'L', a: 'wrist.L', b: 'finger3-3.L:tail', r: 0.035, group: 'handL' },
  { name: 'upper arm', side: 'R', a: 'upperarm01.R', b: 'lowerarm01.R', r: 0.048, group: 'armR' },
  { name: 'forearm', side: 'R', a: 'lowerarm01.R', b: 'wrist.R', r: 0.04, group: 'armR' },
  { name: 'hand', side: 'R', a: 'wrist.R', b: 'finger3-3.R:tail', r: 0.035, group: 'handR' },
  { name: 'thigh', side: 'L', a: 'upperleg01.L', b: 'lowerleg01.L', r: 0.072, group: 'legL' },
  { name: 'shin', side: 'L', a: 'lowerleg01.L', b: 'foot.L', r: 0.052, group: 'legL' },
  { name: 'foot', side: 'L', a: 'foot.L', b: 'toe2-3.L:tail', r: 0.042, group: 'footL' },
  { name: 'thigh', side: 'R', a: 'upperleg01.R', b: 'lowerleg01.R', r: 0.072, group: 'legR' },
  { name: 'shin', side: 'R', a: 'lowerleg01.R', b: 'foot.R', r: 0.052, group: 'legR' },
  { name: 'foot', side: 'R', a: 'foot.R', b: 'toe2-3.R:tail', r: 0.042, group: 'footR' },
];
/** Pairs inside one body that must not pass deeply through each other (neighbours and normal contacts left out). */
export const SELF_PAIRS = [
  ['handL', 'torso'], ['handR', 'torso'], ['handL', 'head'], ['handR', 'head'], ['armL', 'torso', 'forearm'], ['armR', 'torso', 'forearm'],
  ['handL', 'legL'], ['handR', 'legR'], ['handL', 'legR'], ['handR', 'legL'], ['handL', 'handR'], ['armL', 'armR'],
  ['legL', 'legR'], ['footL', 'footR'], ['footL', 'legR'], ['footR', 'legL'], ['armL', 'head'], ['armR', 'head'],
];
const sideAxis = (j) => { const l = j['clavicle.L'], r = j['clavicle.R']; return l && r ? norm(sub(l, r)) : [1, 0, 0]; };
/** The capsules of one pose (world metres). k = body scale (height / 1.8). */
export function capsulesOf(j, k = 1) {
  const sx = sideAxis(j), out = [];
  for (const c of CAPSULES) {
    let a = j[c.a], b = j[c.b];
    if (!a || !b) continue;
    if (c.off) { const o = mul(sx, c.off * k); a = add(a, o); b = add(b, o); }
    out.push({ name: c.name, side: c.side ?? null, group: c.group, a, b, r: c.r * k });
  }
  return out;
}
export const partName = (c) => (c.side ? `${c.side === 'L' ? 'left' : 'right'} ${c.name}` : c.name);

/** The body's frames: forward (horizontal) of hips, chest and head, from joints. */
export function bodyFrames(j) {
  const up = [0, 1, 0];
  const hipSide = j['upperleg01.L'] && j['upperleg01.R'] ? norm(flat(sub(j['upperleg01.L'], j['upperleg01.R']))) : [1, 0, 0];
  const chestSide = j['clavicle.L'] && j['clavicle.R'] ? norm(flat(sub(j['clavicle.L'], j['clavicle.R']))) : hipSide;
  // a character's left is +x when it faces +z: forward = side × up
  const hips = norm(cross(hipSide, up)), chest = norm(cross(chestSide, up));
  let head = chest;
  if (j['eye.L'] && j['eye.R'] && j.head) { const e = mid(j['eye.L'], j['eye.R']); const f = flat(sub(e, j.head)); if (len(f) > 0.01) head = norm(f); }
  return { hips, chest, head, hipSide, chestSide };
}

/** median and median absolute deviation */
export function robust(arr) {
  if (!arr.length) return { med: 0, mad: 0 };
  const s = Float64Array.from(arr).sort(), med = s[s.length >> 1];
  const dev = Float64Array.from(arr, (x) => Math.abs(x - med)).sort();
  return { med, mad: dev[dev.length >> 1] };
}
/** runs of consecutive true values → [[i0, i1]] (inclusive) */
export function runs(flags) {
  const out = []; let s = -1;
  for (let i = 0; i < flags.length; i++) { if (flags[i] && s < 0) s = i; if (!flags[i] && s >= 0) { out.push([s, i - 1]); s = -1; } }
  if (s >= 0) out.push([s, flags.length - 1]);
  return out;
}
/** merge runs closer than gap */
export function mergeRuns(r, gap) { const out = []; for (const x of r) { const l = out[out.length - 1]; if (l && x[0] - l[1] <= gap) l[1] = x[1]; else out.push([...x]); } return out; }
/** 2D convex hull (x, z) */
export function hull2(pts) {
  const p = pts.map((q) => [q[0], q[1]]).sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  if (p.length < 3) return p;
  const cr = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lo = [], hi = [];
  for (const q of p) { while (lo.length >= 2 && cr(lo[lo.length - 2], lo[lo.length - 1], q) <= 0) lo.pop(); lo.push(q); }
  for (let i = p.length - 1; i >= 0; i--) { const q = p[i]; while (hi.length >= 2 && cr(hi[hi.length - 2], hi[hi.length - 1], q) <= 0) hi.pop(); hi.push(q); }
  return lo.slice(0, -1).concat(hi.slice(0, -1));
}
/** signed distance from a 2D point to a convex polygon (negative inside) */
export function polyDist(poly, q) {
  if (poly.length === 0) return Infinity;
  if (poly.length === 1) return Math.hypot(q[0] - poly[0][0], q[1] - poly[0][1]);
  let inside = true, best = Infinity;
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i], b = poly[(i + 1) % poly.length], ex = b[0] - a[0], ez = b[1] - a[1], l2 = ex * ex + ez * ez;
    const t = l2 > 1e-12 ? Math.max(0, Math.min(1, ((q[0] - a[0]) * ex + (q[1] - a[1]) * ez) / l2)) : 0;
    best = Math.min(best, Math.hypot(q[0] - a[0] - ex * t, q[1] - a[1] - ez * t));
    if (poly.length >= 3 && ex * (q[1] - a[1]) - ez * (q[0] - a[0]) < 0) inside = false;
  }
  return poly.length >= 3 && inside ? -best : best;
}
export const fmt = (x, d = 2) => (Math.round(x * 10 ** d) / 10 ** d);
export const cm = (m) => `${Math.round(m * 100)} cm`;

// ---------------------------------------------------------------- the body as it is dressed
// Rounds drawn from the joints cannot tell that a hand has sunk into a bagged tunic or that a scabbard hangs through
// a coat. The real body's own outline can (engine/body.js: trunkShape, read once from the body's rest pose).
export { trunkShape, trunkClear } from '../engine/body.js';
import { trunkClear as _clear } from '../engine/body.js';
/**
 * A posed body's own directions, from its joints: pel (the middle of the hips), up (towards the neck), side(f) and
 * front(f) — its left and its front at a height f (0 at the hips … 1 at the shoulders: a chest turns on its hips) —
 * and local(p): a point as [left, up, forward] from the middle of the hips.
 */
export function trunkFrame(j) {
  const hl = j['upperleg01.L'], hr = j['upperleg01.R']; if (!hl || !hr) return null;
  const pel = mid(hl, hr), nk = j.neck01 ?? j.spine01 ?? [pel[0], pel[1] + 0.5, pel[2]], up = norm(sub(nk, pel));
  const perp = (v) => { const d = dot(v, up); return norm([v[0] - up[0] * d, v[1] - up[1] * d, v[2] - up[2] * d]); };
  const hs = perp(sub(hl, hr)), sl = j['upperarm01.L'], sr = j['upperarm01.R'], cs = sl && sr ? perp(sub(sl, sr)) : hs, H = sl && sr ? Math.max(0.2, dot(sub(mid(sl, sr), pel), up)) : 0.5;
  const side = (f) => { const u = Math.max(0, Math.min(1, f)); return norm([hs[0] + (cs[0] - hs[0]) * u, hs[1] + (cs[1] - hs[1]) * u, hs[2] + (cs[2] - hs[2]) * u]); };
  const front = (f) => cross(side(f), up);
  const local = (p) => { const d = sub(p, pel), y = dot(d, up), sd = side(y / H); return [dot(d, sd), y, dot(d, cross(sd, up))]; };
  return { pel, up, H, side, front, local, hipW: dist(hl, hr) };
}
const _along = (a, b, f) => [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f];
const _axis = (q) => [2 * (q[0] * q[1] - q[3] * q[2]), 1 - 2 * (q[0] * q[0] + q[2] * q[2]), 2 * (q[1] * q[2] + q[3] * q[0])];
/**
 * One pose against the body as it is dressed (S: trunkShape of its real body). Finds:
 *   arm-in-body   — a forearm, elbow or hand inside the trunk (one finding a side, the deepest part)
 *   blade-in-body — the blade of a sword in a hand inside the trunk
 *   worn-in-body  — a scabbard worn through the body, or the hilt of the sword in it sitting in the clothes
 * each { kind, side?, part, depth (metres inside), at (the point, in the world) }. k: the body's scale against its
 * rest pose (default: from the hips).
 */
export function wornChecks(j, props, S, k) {
  const F = S ? trunkFrame(j) : null, out = []; if (!F) return out;
  const sc = k ?? (S.hip ? F.hipW / S.hip : 1), depthAt = (p) => { const l = F.local(p); return -_clear(S, l[0] / sc, l[1] / sc, l[2] / sc) * sc; };
  // arms: the middle line of a forearm lies its own thickness outside whatever it rests on
  for (const s of ['L', 'R']) {
    const E = j[`lowerarm01.${s}`], W = j[`wrist.${s}`], Kn = j[`finger3-1.${s}`] ?? W; if (!E || !W) continue;
    let worst = null; const take = (p, part, min) => { const d = depthAt(p); if (d > min && (!worst || d > worst.depth)) worst = { depth: d, part, at: p }; };
    take(E, 'elbow', 0.02); for (const f of [0.2, 0.4, 0.6, 0.8]) take(_along(E, W, f), 'forearm', 0.012); take(W, 'wrist', 0.012); for (const f of [0.5, 1]) take(_along(W, Kn, f), 'hand', 0.012);
    if (worst) out.push({ kind: 'arm-in-body', side: s, part: `${s === 'L' ? 'left' : 'right'} ${worst.part}`, depth: worst.depth, at: worst.at });
  }
  const sw = props?.sword, scb = props?.scabbard, kb = sc;
  // does the sword ride its scabbard (home, or sliding along it)?
  let rides = false;
  if (sw?.visible && scb?.visible) { const ax = _axis(scb.q), d = sub(sw.pos, scb.pos), al = dot(d, ax), off = len(sub(d, [ax[0] * al, ax[1] * al, ax[2] * al])); rides = off < 0.03 * kb && al < 0.02 * kb && al > -1.2 * kb && dot(_axis(sw.q), ax) > 0.96; }
  if (sw?.visible && !rides) {
    const ax = _axis(sw.q); let worst = null;
    for (const f of [0.2, 0.32, 0.44, 0.56, 0.68, 0.8]) { const p = [sw.pos[0] + ax[0] * f * kb, sw.pos[1] + ax[1] * f * kb, sw.pos[2] + ax[2] * f * kb], d = depthAt(p); if (d > 0.015 && (!worst || d > worst.depth)) worst = { depth: d, at: p }; }
    if (worst) out.push({ kind: 'blade-in-body', part: 'blade', ...worst });
  }
  if (scb?.visible) {
    const ax = _axis(scb.q); let worst = null;
    for (const f of [0.05, 0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75]) { const p = [scb.pos[0] + ax[0] * f * kb, scb.pos[1] + ax[1] * f * kb, scb.pos[2] + ax[2] * f * kb], d = depthAt(p); if (d > 0.012 && (!worst || d > worst.depth)) worst = { depth: d, at: p }; }
    if (worst) out.push({ kind: 'worn-in-body', part: 'scabbard', ...worst });
    // the hilt of the sword in it: a hand must be able to close on it — its middle a grip's thickness clear of the clothes
    if (sw?.visible && rides) { let w2 = null; for (const f of [-0.08, 0, 0.08]) { const p = [sw.pos[0] + ax[0] * f * kb, sw.pos[1] + ax[1] * f * kb, sw.pos[2] + ax[2] * f * kb], d = depthAt(p) + 0.015 * kb; if (d > 0 && (!w2 || d > w2.depth)) w2 = { depth: d, at: p }; }
      if (w2) out.push({ kind: 'worn-in-body', part: 'hilt', ...w2 }); }
  }
  return out;
}
