// A painted longsword as a 3D mesh: a steel blade with a fuller, a gilded cross-guard, a leather grip and a pommel.
// sword3d(at, o) → [Mesh]; at: the middle of the guard; o: { yaw, pitch (deg; 0 = point down), len (m, blade), bias }
import { Mesh } from '../engine/scene.js';
const rad = (d) => (d * Math.PI) / 180, add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const ap = (R, p) => [R[0][0] * p[0] + R[0][1] * p[1] + R[0][2] * p[2], R[1][0] * p[0] + R[1][1] * p[1] + R[1][2] * p[2], R[2][0] * p[0] + R[2][1] * p[1] + R[2][2] * p[2]];
const mul = (A, B) => A.map((r) => [0, 1, 2].map((j) => r[0] * B[0][j] + r[1] * B[1][j] + r[2] * B[2][j]));
const Rx = (d) => { const c = Math.cos(rad(d)), s = Math.sin(rad(d)); return [[1, 0, 0], [0, c, -s], [0, s, c]]; };
const Ry = (d) => { const c = Math.cos(rad(d)), s = Math.sin(rad(d)); return [[c, 0, s], [0, 1, 0], [-s, 0, c]]; };
function slab(c, w, h, d, col, det) {
  const [cx, cy, cz] = c, x0 = cx - w / 2, x1 = cx + w / 2, y0 = cy - h / 2, y1 = cy + h / 2, z0 = cz - d / 2, z1 = cz + d / 2, s = col.side ?? col, t = col.top ?? s;
  return [{ p: [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]], c: col.front ?? s, detail: det }, { p: [[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]], c: col.front ?? s, detail: det },
    { p: [[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]], c: s }, { p: [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]], c: s },
    { p: [[x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]], c: t }, { p: [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]], c: s }];
}
/** the blade's face: a darker fuller down the middle and a bright edge line, pointed at the tip */
const bladeFace = (g, F) => { const a = F.uv(0.5, 0), b = F.uv(0.5, 0.96); g.save(); g.strokeStyle = 'rgba(80,90,110,0.55)'; g.lineWidth = Math.max(1, Math.hypot(...[F.uv(0.62, 0.5)[0] - F.uv(0.38, 0.5)[0], 0]) * 0.6);
  g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke(); g.restore(); };
export function sword3d(at, o = {}) {
  const L = o.len ?? 0.95, parts = [
    ...slab([0, -L / 2 + 0.03, 0], 0.055, L - 0.06, 0.012, { side: '#9aa3b2', front: '#dfe4ec' }, bladeFace),
    ...slab([0, -L + 0.015, 0], 0.03, 0.05, 0.01, { side: '#9aa3b2', front: '#d4dae4' }), // the narrowing point
    ...slab([0, 0, 0], 0.26, 0.035, 0.04, { side: '#a07a2a', top: '#e8c260', front: '#d4a94a' }),
    ...slab([0, 0.11, 0], 0.036, 0.18, 0.036, { side: '#4a2c18', front: '#5e3a20' }),
    ...slab([0, 0.225, 0], 0.06, 0.05, 0.06, { side: '#a07a2a', top: '#f0cc6a', front: '#d4a94a' })];
  const R = mul(Ry(o.yaw ?? 0), Rx(o.pitch ?? 0));
  return [new Mesh(parts.map((f) => ({ ...f, p: f.p.map((q) => add(ap(R, q), at)) })), { p: add(at, [0, -0.2, 0]), bias: o.bias ?? 0 })];
}
