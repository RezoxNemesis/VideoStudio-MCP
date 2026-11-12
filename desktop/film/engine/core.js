// Core maths for the film: vectors, quaternions, easing, noise, colour.
export const V = {
  add: (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]],
  sub: (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]],
  mul: (a, k) => [a[0] * k, a[1] * k, a[2] * k],
  dot: (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2],
  cross: (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]],
  len: (a) => Math.hypot(a[0], a[1], a[2]),
  norm: (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; },
  lerp: (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t],
  mad: (a, b, k) => [a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k],
  dist: (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]),
  mid: (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2],
  /** the part of a perpendicular to unit n */
  perp: (a, n) => { const d = a[0] * n[0] + a[1] * n[1] + a[2] * n[2]; return [a[0] - n[0] * d, a[1] - n[1] * d, a[2] - n[2] * d]; },
  rotY: (a, ang) => { const c = Math.cos(ang), s = Math.sin(ang); return [a[0] * c + a[2] * s, a[1], -a[0] * s + a[2] * c]; },
};
export const UP = [0, 1, 0];
export const clamp = (x, a = 0, b = 1) => (x < a ? a : x > b ? b : x);
export const lerp = (a, b, t) => a + (b - a) * t;
export const smooth = (t) => { t = clamp(t); return t * t * (3 - 2 * t); };
export const smoother = (t) => { t = clamp(t); return t * t * t * (t * (t * 6 - 15) + 10); };
export const easeOut = (t, p = 3) => 1 - Math.pow(1 - clamp(t), p);
export const easeIn = (t, p = 3) => Math.pow(clamp(t), p);
export const easeIO = (t, p = 3) => { t = clamp(t); return t < 0.5 ? Math.pow(t * 2, p) / 2 : 1 - Math.pow((1 - t) * 2, p) / 2; };
/** 0→1 between a and b */
export const ramp = (x, a, b) => clamp((x - a) / (b - a));
/** a pulse that rises over [a,b], holds, and falls over [c,d] */
export const pulse = (x, a, b, c, d) => Math.min(smooth(ramp(x, a, b)), 1 - smooth(ramp(x, c, d)));
export const D2R = Math.PI / 180;

/** rotate v by quaternion q = [x,y,z,w] */
export function qrot(q, v) {
  const [x, y, z, w] = q, [vx, vy, vz] = v;
  const tx = 2 * (y * vz - z * vy), ty = 2 * (z * vx - x * vz), tz = 2 * (x * vy - y * vx);
  return [vx + w * tx + (y * tz - z * ty), vy + w * ty + (z * tx - x * tz), vz + w * tz + (x * ty - y * tx)];
}
export function qnlerp(a, b, t) {
  const d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3], s = d < 0 ? -1 : 1;
  const q = [a[0] + (b[0] * s - a[0]) * t, a[1] + (b[1] * s - a[1]) * t, a[2] + (b[2] * s - a[2]) * t, a[3] + (b[3] * s - a[3]) * t];
  const l = Math.hypot(q[0], q[1], q[2], q[3]) || 1;
  return [q[0] / l, q[1] / l, q[2] / l, q[3] / l];
}

/* ------------------------------------------------------------------ noise */
export function hash(n) { const s = Math.sin(n * 127.1 + 311.7) * 43758.5453; return s - Math.floor(s); }
export function hash2(x, y) { const s = Math.sin(x * 127.1 + y * 311.7 + 74.7) * 43758.5453; return s - Math.floor(s); }
/** seeded random stream */
export function rng(seed) { let s = (seed * 2654435761) >>> 0 || 1; return () => { s ^= s << 13; s >>>= 0; s ^= s >> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; }; }
/** smooth 1D value noise, −1..1 */
export function noise1(x, seed = 0) { const i = Math.floor(x), f = x - i, u = f * f * (3 - 2 * f); return (lerp(hash(i + seed * 57.3), hash(i + 1 + seed * 57.3), u) * 2 - 1); }
/** smooth 2D value noise, 0..1 */
export function noise2(x, y, seed = 0) {
  const xi = Math.floor(x), yi = Math.floor(y), xf = x - xi, yf = y - yi, u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
  const a = hash2(xi + seed * 17, yi), b = hash2(xi + 1 + seed * 17, yi), c = hash2(xi + seed * 17, yi + 1), d = hash2(xi + 1 + seed * 17, yi + 1);
  return lerp(lerp(a, b, u), lerp(c, d, u), v);
}
export function fbm(x, y, oct = 4, seed = 0) { let s = 0, a = 0.5, f = 1, n = 0; for (let i = 0; i < oct; i++) { s += a * noise2(x * f, y * f, seed + i * 7); n += a; a *= 0.5; f *= 2; } return s / n; }

/* ----------------------------------------------------------------- colour */
export function hex(c) { const n = parseInt(c.slice(1), 16); return c.length === 4 ? [((n >> 8) & 15) * 17, ((n >> 4) & 15) * 17, (n & 15) * 17] : [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }
const h2 = (v) => Math.round(clamp(v, 0, 255)).toString(16).padStart(2, '0');
export const rgb = (c) => `#${h2(c[0])}${h2(c[1])}${h2(c[2])}`;
export const rgba = (c, a) => `rgba(${Math.round(clamp(c[0], 0, 255))},${Math.round(clamp(c[1], 0, 255))},${Math.round(clamp(c[2], 0, 255))},${clamp(a).toFixed(3)})`;
export const mixc = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
export const mulc = (a, b) => [a[0] * b[0] / 255, a[1] * b[1] / 255, a[2] * b[2] / 255];
/** mix two hex colours → hex */
export const mixh = (a, b, t) => rgb(mixc(hex(a), hex(b), t));
/** darken (k<0) or lighten (k>0) a hex colour */
export const shade = (c, k) => { const v = hex(c); return rgb(k < 0 ? [v[0] * (1 + k), v[1] * (1 + k), v[2] * (1 + k)] : mixc(v, [255, 255, 255], k)); };

/**
 * The light of a shot applied to a flat colour: `amb` multiplies (the colour of the air), `key` is added in proportion
 * to how bright the surface is (firelight, sunset), `sat` pulls towards grey (night, snow).
 */
export function makeGrade(o = {}) {
  const amb = hex(o.amb ?? '#ffffff'), tint = o.tint ? hex(o.tint) : null, k = o.tintAmt ?? 0, sat = o.sat ?? 1, gain = o.gain ?? 1;
  const cache = new Map();
  const f = (c) => {
    let r = cache.get(c);
    if (r) return r;
    let v = hex(c);
    v = [v[0] * amb[0] / 255 * gain, v[1] * amb[1] / 255 * gain, v[2] * amb[2] / 255 * gain];
    if (sat !== 1) { const g = v[0] * 0.3 + v[1] * 0.59 + v[2] * 0.11; v = [g + (v[0] - g) * sat, g + (v[1] - g) * sat, g + (v[2] - g) * sat]; }
    if (tint) v = mixc(v, tint, k);
    r = rgb(v);
    cache.set(c, r);
    return r;
  };
  f.spec = o;
  return f;
}
