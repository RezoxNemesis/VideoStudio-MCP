// Armour worn over a real body that the body model itself does not have: shoulder plates.
// They are built from the joints of the posed body, picture by picture: plates that lie over the top of the shoulder
// and lap down the upper arm, each beginning under the one above, all of them going with the arm when it is lifted.
import { hex, shade as shadeHex } from './core.js';

const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2], cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = (a) => Math.hypot(a[0], a[1], a[2]), norm = (a) => { const l = len(a) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
const perp = (v, ax) => sub(v, mul(ax, dot(v, ax)));

/**
 * Shoulder plates for both shoulders. j: the body's joints in the world (upperarm01.L/R = the shoulders, lowerarm01.L/R
 * = the elbows, neck01, spine02); k: the body's height / 1.8; o: { size: 1, lames: 3, color: '#585c68', trim (the
 * colour of each plate's lower edge), rgbOf (how a colour of the sheet becomes the picture's) }.
 * Returns [{ name: 'pauldronL0' …, side: 'L' | 'R', pos, idx, paint }] — the top plate first.
 */
export function pauldronMeshes(j, k = 1, o = {}) {
  const out = [], SL = j['upperarm01.L'], SR = j['upperarm01.R']; if (!SL || !SR) return out;
  const size = o.size ?? 1, n = Math.max(2, o.lames ?? 3), rgbOf = o.rgbOf ?? ((c) => hex(c)), col = o.color ?? '#585c68';
  const mid = mul(add(SL, SR), 0.5), up = norm(j.neck01 && j.spine02 ? sub(j.neck01, j.spine02) : [0, 1, 0]), u = k * size;
  const paint = (name) => ({ fill: rgbOf(col), shade: rgbOf(shadeHex(col, -0.4)), hi: rgbOf(shadeHex(col, 0.5)), hiAt: 0.8, thr: 0.05, metal: true, two: true, soft: 0, group: name, bias: 0.004 });
  for (const sd of ['L', 'R']) {
    const S = j[`upperarm01.${sd}`], E = j[`lowerarm01.${sd}`]; if (!E) continue;
    const arm = norm(sub(E, S)), down = mul(up, -1);
    // away from the body: out from the chest's middle, square to the arm — an arm held level has no such side: there it is the body's up
    const side = perp(sub(S, mid), arm), high = perp(up, arm), away = norm(add(mul(norm(side), Math.min(1, len(side) / (0.08 * k))), mul(len(high) > 1e-6 ? norm(high) : [0, 0, 0], 0.8 * Math.min(1, len(high) / 0.3))));
    for (let i = 0; i < n; i++) {
      // every plate goes with the arm; the top one keeps a little of the shoulder's own set
      const w = 0.8 + 0.2 * (i / (n - 1)), ax = norm(add(mul(down, 1 - w), mul(arm, w))), e1 = norm(perp(away, ax)), e2 = cross(ax, e1);
      const s0 = (i === 0 ? -0.05 : -0.03 + i * 0.05) * u, s1 = (i === 0 ? 0.045 : -0.03 + i * 0.05 + 0.075) * u, R = (0.068 + 0.006 * (n - 1 - i)) * u, sweep = ((i === 0 ? 105 : 96) * Math.PI) / 180;
      const rows = 5, cols = 13, pos = new Float32Array(rows * cols * 3), idx = [];
      for (let r = 0; r < rows; r++) {
        const t = r / (rows - 1), s = s0 + (s1 - s0) * t;
        // the top plate is a cap: it closes in over the shoulder; the others are bands that flare a little at their lower edge
        const rad = i === 0 ? R * Math.sin((Math.PI / 2) * (0.35 + 0.65 * t)) + 0.006 * u * t * t : R + 0.009 * u * t * t;
        for (let c = 0; c < cols; c++) {
          const ph = -sweep + (2 * sweep * c) / (cols - 1), q = add(add(S, mul(ax, s)), add(mul(e1, Math.cos(ph) * rad), mul(e2, Math.sin(ph) * rad))), v = (r * cols + c) * 3;
          pos[v] = q[0]; pos[v + 1] = q[1]; pos[v + 2] = q[2];
        }
      }
      // faces that look outwards (away from the plate's own middle line)
      for (let r = 0; r < rows - 1; r++) for (let c = 0; c < cols - 1; c++) {
        const a = r * cols + c, b = a + 1, d = a + cols, e = d + 1;
        const A = [pos[a * 3], pos[a * 3 + 1], pos[a * 3 + 2]], B = [pos[b * 3], pos[b * 3 + 1], pos[b * 3 + 2]], D = [pos[d * 3], pos[d * 3 + 1], pos[d * 3 + 2]];
        const nr = cross(sub(B, A), sub(D, A)), ctr = mul(add(add(A, B), D), 1 / 3), rel = sub(ctr, S), outw = perp(rel, ax);
        if (dot(nr, outw) >= 0) idx.push(a, b, d, b, e, d); else idx.push(a, d, b, b, d, e);
      }
      const name = `pauldron${sd}${i}`;
      out.push({ name, side: sd, pos, idx: Uint32Array.from(idx), paint: paint(name) });
    }
  }
  return out;
}
