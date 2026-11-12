// Riders: a horse on a path with somebody on its back. The path gives where the horse is at a sequence time; the legs
// are driven by the ground covered, the rider is seated by engine/horse.js `mount`.
import { Horse, mount } from '../engine/horse.js';
import { V, clamp, lerp, smooth } from '../engine/core.js';

/**
 * path: [[t, x, z, gait?], …] way-points in sequence time (the horse eases between them; the gait named at a point is
 * used from there on; 'stand' between two equal points). Returns { horse, at(ts) → { pos, yaw, dist, gait, speed } }.
 */
export function ridePath(path) {
  // cumulative distance at each way-point
  const acc = [0]; for (let i = 1; i < path.length; i++) acc.push(acc[i - 1] + Math.hypot(path[i][1] - path[i - 1][1], path[i][2] - path[i - 1][2]));
  let lastYaw = null;
  return (ts) => {
    let i = 0; while (i < path.length - 2 && ts >= path[i + 1][0]) i++;
    const a = path[i], b = path[i + 1], span = Math.max(1e-6, b[0] - a[0]), raw = clamp((ts - a[0]) / span);
    const still = Math.hypot(b[1] - a[1], b[2] - a[2]) < 0.02;
    // ease in from a stop and out to a stop; run through otherwise
    const fromStop = i === 0 || path[i][3] === 'stand' || Math.hypot(a[1] - path[i - 1][1], a[2] - path[i - 1][2]) < 0.02, toStop = i + 2 >= path.length || Math.hypot(path[i + 2][1] - b[1], path[i + 2][2] - b[2]) < 0.02;
    const u = fromStop && toStop ? smooth(raw) : fromStop ? raw * raw * (2 - raw) * 1 : toStop ? 1 - (1 - raw) * (1 - raw) * (2 - (1 - raw)) * 1 : raw;
    const uu = clamp(fromStop && !toStop ? (raw * raw + raw) / 2 : !fromStop && toStop ? 1 - ((1 - raw) * (1 - raw) + (1 - raw)) / 2 : u);
    const x = lerp(a[1], b[1], uu), z = lerp(a[2], b[2], uu), dist = lerp(acc[i], acc[i + 1], uu);
    let yaw = still ? (lastYaw ?? 0) : Math.atan2(b[1] - a[1], b[2] - a[2]);
    // turn smoothly through a way-point: blend towards the next leg's heading near its end
    if (!still && i + 2 < path.length) { const c = path[i + 2], nx = Math.atan2(c[1] - b[1], c[2] - b[2]); if (Math.hypot(c[1] - b[1], c[2] - b[2]) > 0.02) { let d = nx - yaw; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; yaw += d * smooth(clamp((raw - 0.7) / 0.3)) * 0.5; } }
    if (!still && i > 0) { const p = path[i - 1], pv = Math.atan2(a[1] - p[1], a[2] - p[2]); if (Math.hypot(a[1] - p[1], a[2] - p[2]) > 0.02) { let d = pv - yaw; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; yaw += d * (1 - smooth(clamp(raw / 0.3))) * 0.5; } }
    if (path[i][4] != null && still) yaw = path[i][4];
    lastYaw = yaw;
    const speed = still ? 0 : Math.hypot(b[1] - a[1], b[2] - a[2]) / span;
    return { pos: [x, z], yaw, dist, gait: still ? 'stand' : (a[3] ?? (speed > 6 ? 'gallop' : speed > 2.6 ? 'trot' : 'walk')), speed };
  };
}
/** A horse and its rider for a sequence: returns { horse, fit (for the actor), item(ts, ctx) (for the shot's dyn list) }. */
export function rider(o) {
  const horse = new Horse(o.horse ?? {}), where = typeof o.path === 'function' ? o.path : ridePath(o.path);
  let cur = null;
  const upd = (ts) => { cur = where(ts); horse.place(cur.pos, cur.yaw, cur.dist, cur.gait, ts, { ground: o.ground ?? 0, rear: o.rear ? o.rear(ts) : 0, head: o.head ? o.head(ts) : 0 }); return cur; };
  return {
    horse, where,
    fit: (pose, t, ctx) => { upd(ctx.shot.t0 + t); mount(pose, horse, { cam: ctx.cam, lift: o.lift }); },
    item: (ts, ctx) => { if (!cur) upd(ts); const p = [cur.pos[0], o.ground ?? 0, cur.pos[1]]; return { p, bias: 0.35, draw: (g, cam, sc) => horse.draw(g, cam, sc.litAt(V.dist(cam.pos, p)), { light: sc.light2(cam), shadow: true, ground: o.ground ?? 0, shadowAlpha: sc.o.shadowAlpha ?? 0.28, shadowColor: sc.o.shadowColor }) }; },
  };
}
