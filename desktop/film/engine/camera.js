// A film camera: where it is, what it looks at, its lens; turns world metres into picture pixels.
import { V, D2R } from './core.js';

export function makeCam(o, W, H) {
  const pos = o.pos, F = V.norm(V.sub(o.target, o.pos));
  let R = V.norm(V.cross(F, [0, 1, 0]));
  if (!isFinite(R[0]) || V.len(V.cross(F, [0, 1, 0])) < 1e-5) R = [1, 0, 0];
  let U = V.cross(R, F);
  if (o.roll) { const c = Math.cos(o.roll * D2R), s = Math.sin(o.roll * D2R); const R2 = V.add(V.mul(R, c), V.mul(U, s)), U2 = V.add(V.mul(U, c), V.mul(R, -s)); R = R2; U = U2; }
  const f = (H / 2) / Math.tan((o.fov ?? 35) * D2R / 2);
  const cx = W / 2 + (o.dx ?? 0), cy = H / 2 + (o.dy ?? 0);
  const cam = {
    pos, R, U, F, f, W, H, cx, cy, fov: o.fov ?? 35,
    /** camera space: x right, y up, z = distance in front */
    toCam(p) { const d = [p[0] - pos[0], p[1] - pos[1], p[2] - pos[2]]; return [V.dot(d, R), V.dot(d, U), V.dot(d, F)]; },
    /** picture position [x, y, depth]; depth ≤ 0 is behind the lens */
    project(p) { const dx = p[0] - pos[0], dy = p[1] - pos[1], dz = p[2] - pos[2]; const z = dx * F[0] + dy * F[1] + dz * F[2]; const zz = z > 0.02 ? z : 0.02; return [cx + f * (dx * R[0] + dy * R[1] + dz * R[2]) / zz, cy - f * (dx * U[0] + dy * U[1] + dz * U[2]) / zz, z]; },
    /** pixels per metre at a depth */
    ppm(z) { return f / Math.max(0.02, z); },
    /** unit vector from the lens towards a world point */
    dirTo(p) { return V.norm(V.sub(p, pos)); },
    /** picture height of the horizon (flat ground far away) */
    horizon() { return cy + f * (F[1] / Math.max(1e-4, Math.hypot(F[0], F[2]))) * 1; },
    /** world point on the ground plane y = h under a picture position, or null above the horizon */
    ground(sx, sy, h = 0) { const d = V.add(V.add(V.mul(R, (sx - cx) / f), V.mul(U, -(sy - cy) / f)), F); if (d[1] > -1e-5) return null; const k = (h - pos[1]) / d[1]; return [pos[0] + d[0] * k, h, pos[2] + d[2] * k]; },
  };
  return cam;
}

/** Blend two camera set-ups (for moves): positions, targets and lenses move together. */
export function mixCam(a, b, t) {
  return { pos: V.lerp(a.pos, b.pos, t), target: V.lerp(a.target, b.target, t), fov: (a.fov ?? 35) + ((b.fov ?? 35) - (a.fov ?? 35)) * t, roll: (a.roll ?? 0) + ((b.roll ?? 0) - (a.roll ?? 0)) * t };
}

/**
 * Is any part of a figure with these joints in the picture (with m metres of margin)? A figure wholly beside or behind the
 * lens must not be drawn: when it is near the lens plane it projects as huge smears across the frame.
 */
export function inView(cam, joints, m = 0.8, near = 0.12) {
  const tx = (cam.W / 2) / cam.f, ty = (cam.H / 2) / cam.f;
  for (const k in joints) {
    const c = cam.toCam(joints[k]);
    if (c[2] >= near && Math.abs(c[0]) <= c[2] * tx + m && Math.abs(c[1]) <= c[2] * ty + m) return true;
  }
  return false;
}
