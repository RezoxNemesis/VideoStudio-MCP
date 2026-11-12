// Secondary motion: hair, capes, robes, sleeves and banners as particles joined by threads (Verlet integration).
// Everything is simulated in world metres from the body's own movement, gravity and wind, so cloth and hair
// follow, lag, swing and settle the way real ones do.
import { V, noise1 } from './core.js';

const G = 9.81;

/** Wind at a place and time: a steady stream plus slow gusts and small flutter. */
export function makeWind(o = {}) {
  const dir = V.norm(o.dir ?? [1, 0, 0.2]), base = o.speed ?? 1.2, gust = o.gust ?? 0.8, seed = o.seed ?? 1;
  return (p, t) => {
    const g = base + gust * (0.6 * noise1(t * 0.35 + p[0] * 0.05, seed) + 0.4 * noise1(t * 1.3 + p[2] * 0.21 + p[0] * 0.13, seed + 3));
    const side = 0.35 * noise1(t * 0.9 + p[1] * 0.4, seed + 7);
    return [dir[0] * g - dir[2] * side, 0.12 * noise1(t * 1.7 + p[0], seed + 11) * g, dir[2] * g + dir[0] * side];
  };
}

/** Push a point out of a tapered capsule (a, ra)–(b, rb). */
function pushOut(p, a, ra, b, rb, pad) {
  const abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
  const l2 = abx * abx + aby * aby + abz * abz || 1e-9;
  let u = ((p[0] - a[0]) * abx + (p[1] - a[1]) * aby + (p[2] - a[2]) * abz) / l2;
  u = u < 0 ? 0 : u > 1 ? 1 : u;
  const cx = a[0] + abx * u, cy = a[1] + aby * u, cz = a[2] + abz * u, r = ra + (rb - ra) * u + pad;
  const dx = p[0] - cx, dy = p[1] - cy, dz = p[2] - cz, d = Math.hypot(dx, dy, dz);
  if (d < r && d > 1e-6) { const k = r / d; p[0] = cx + dx * k; p[1] = cy + dy * k; p[2] = cz + dz * k; return true; }
  return false;
}

/**
 * A sheet of cloth: `cols` threads hanging from pinned points, `rows` particles down each.
 * o: { cols, rows, length (m, or per column), drag, mass feel (damp), stiff (0..1 how much it holds its rest shape), closed (ring) }
 */
export class Cloth {
  constructor(o) {
    this.cols = o.cols; this.rows = o.rows; this.closed = !!o.closed;
    this.len = Array.isArray(o.length) ? o.length : Array(o.cols).fill(o.length);
    this.damp = o.damp ?? 0.985; this.drag = o.drag ?? 1.0; this.stiff = o.stiff ?? 0; this.gravity = o.gravity ?? 1; this.pad = o.pad ?? 0.012;
    this.p = []; this.q = [];
    for (let c = 0; c < o.cols; c++) { this.p.push(Array.from({ length: o.rows }, () => [0, 0, 0])); this.q.push(Array.from({ length: o.rows }, () => [0, 0, 0])); }
    this.fresh = true;
  }
  /** Put the cloth at rest under its pins (start of a shot, or after a cut). rest(c, r) → world position, optional. */
  reset(pins, rest) {
    for (let c = 0; c < this.cols; c++) for (let r = 0; r < this.rows; r++) {
      const seg = this.len[c] / (this.rows - 1);
      const w = rest ? rest(c, r) : [pins[c][0], pins[c][1] - seg * r, pins[c][2]];
      this.p[c][r] = [...w]; this.q[c][r] = [...w];
    }
    this.fresh = false;
  }
  /**
   * Advance by dt. pins: world positions of the top row. caps: [[a, ra, b, rb], …] body parts to stay outside of.
   * wind(p, t) → m/s. rest(c, r): where the cloth would be if it were rigid (for `stiff`). ground: floor height.
   */
  step(pins, dt, t, o = {}) {
    if (this.fresh) this.reset(pins, o.rest);
    // a jump (cut, teleport): carry the whole cloth with its pins instead of whipping it across the set
    let jump = 0; for (let c = 0; c < this.cols; c++) jump = Math.max(jump, V.dist(pins[c], this.p[c][0]));
    if (jump > 0.6) { for (let c = 0; c < this.cols; c++) { const d = V.sub(pins[c], this.p[c][0]); for (let r = 0; r < this.rows; r++) { this.p[c][r] = V.add(this.p[c][r], d); this.q[c][r] = V.add(this.q[c][r], d); } } }
    const sub = o.sub ?? 3, h = dt / sub, caps = o.caps ?? [], wind = o.wind, ground = o.ground ?? -1e9;
    // where each pin was at the end of the last step (pins move smoothly within the frame from there)
    const from = this.p.map((col) => [col[0][0], col[0][1], col[0][2]]);
    for (let s = 0; s < sub; s++) {
      const u = (s + 1) / sub;
      for (let c = 0; c < this.cols; c++) {
        const col = this.p[c], old = this.q[c], seg = this.len[c] / (this.rows - 1);
        // pins move smoothly within the frame
        const pin = [from[c][0] + (pins[c][0] - from[c][0]) * u, from[c][1] + (pins[c][1] - from[c][1]) * u, from[c][2] + (pins[c][2] - from[c][2]) * u];
        for (let r = 1; r < this.rows; r++) {
          const p = col[r], q = old[r];
          let ax = 0, ay = -G * this.gravity, az = 0;
          if (wind) { const w = wind(p, t); const vx = (p[0] - q[0]) / h, vy = (p[1] - q[1]) / h, vz = (p[2] - q[2]) / h; const k = this.drag * 1.1; ax += (w[0] - vx) * k; ay += (w[1] - vy) * k * 0.35; az += (w[2] - vz) * k; }
          const nx = p[0] + (p[0] - q[0]) * this.damp + ax * h * h, ny = p[1] + (p[1] - q[1]) * this.damp + ay * h * h, nz = p[2] + (p[2] - q[2]) * this.damp + az * h * h;
          q[0] = p[0]; q[1] = p[1]; q[2] = p[2];
          p[0] = nx; p[1] = ny; p[2] = nz;
        }
        col[0][0] = pin[0]; col[0][1] = pin[1]; col[0][2] = pin[2];
        void seg;
      }
      for (let it = 0; it < 3; it++) {
        for (let c = 0; c < this.cols; c++) {
          const col = this.p[c], seg = this.len[c] / (this.rows - 1);
          for (let r = 1; r < this.rows; r++) {
            const a = col[r - 1], b = col[r];
            const dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2], d = Math.hypot(dx, dy, dz) || 1e-9, k = (d - seg) / d;
            if (r === 1) { b[0] -= dx * k; b[1] -= dy * k; b[2] -= dz * k; } else { a[0] += dx * k * 0.5; a[1] += dy * k * 0.5; a[2] += dz * k * 0.5; b[0] -= dx * k * 0.5; b[1] -= dy * k * 0.5; b[2] -= dz * k * 0.5; }
          }
        }
        // threads keep their spacing sideways (the weave)
        const last = this.closed ? this.cols : this.cols - 1;
        for (let c = 0; c < last; c++) {
          const c2 = (c + 1) % this.cols;
          const w0 = V.dist(pins[c], pins[c2]);
          for (let r = 1; r < this.rows; r++) {
            const a = this.p[c][r], b = this.p[c2][r];
            const want = w0 * (1 + (o.flare ?? 0) * r / (this.rows - 1));
            const dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2], d = Math.hypot(dx, dy, dz) || 1e-9;
            // cloth resists stretching fully, and folding only a little
            const k = d > want ? (d - want) / d * 0.5 : d < want * 0.45 ? (d - want * 0.45) / d * 0.5 : 0;
            if (k) { a[0] += dx * k; a[1] += dy * k; a[2] += dz * k; b[0] -= dx * k; b[1] -= dy * k; b[2] -= dz * k; }
          }
        }
        if (this.stiff > 0 && o.rest) {
          for (let c = 0; c < this.cols; c++) for (let r = 1; r < this.rows; r++) { const p = this.p[c][r], w = o.rest(c, r), k = this.stiff * (1 - 0.6 * r / (this.rows - 1)); p[0] += (w[0] - p[0]) * k; p[1] += (w[1] - p[1]) * k; p[2] += (w[2] - p[2]) * k; }
        }
        for (let c = 0; c < this.cols; c++) for (let r = 1; r < this.rows; r++) {
          const p = this.p[c][r];
          for (const cp of caps) pushOut(p, cp[0], cp[1], cp[2], cp[3], this.pad);
          if (p[1] < ground + 0.01) { p[1] = ground + 0.01; const q = this.q[c][r]; q[0] += (p[0] - q[0]) * 0.5; q[2] += (p[2] - q[2]) * 0.5; }
        }
      }
    }
  }
}

/** One strand (a lock of hair, a ribbon, a tail): a Cloth with a single thread and its own rest direction. */
export class Strand extends Cloth {
  constructor(o) { super({ cols: 1, rows: o.nodes ?? 5, length: o.length, damp: o.damp ?? 0.97, drag: o.drag ?? 0.7, stiff: o.stiff ?? 0.25, gravity: o.gravity ?? 1, pad: o.pad ?? 0.008 }); }
}
