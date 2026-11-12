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
