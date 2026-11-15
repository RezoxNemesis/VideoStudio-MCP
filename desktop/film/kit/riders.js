// Riders: a horse on a path with somebody on its back. The path gives where the horse is at a sequence time; the legs
// are driven by the ground covered, the rider is seated by engine/horse.js `mount`.
import { Horse, mount } from '../engine/horse.js';
import { horseMeshes } from '../engine/horsemesh.js';
import { V, clamp, lerp, smooth } from '../engine/core.js';
import { bendPose, shiftPose, turnPose } from './combat.js';

/**
 * Seat a performed body on a horse: the whole body is turned the way the horse faces and carried to the saddle, then
 * bent into a rider's seat: the thighs out and forward along the barrel, the lower legs hanging down its sides, the
 * hands at the reins in front of the body. (The simple figures, which have no real body, are seated as they were.)
 * o: { lift (how high the hips sit above the saddle's place, ×body height/1.8; 0.07), spread, thigh, knee (degrees),
 *      arms: false (leave the arms to the performance: a rider who points, draws or carries something) }
 */
export function seat(pose, horse, o = {}) {
  if (!pose.body) return mount(pose, horse, o);
  const s = horse.saddle(), j = pose.j, k = pose.height / 1.8;
  const hl = j['upperleg01.L'], hr = j['upperleg01.R'], side = V.norm([hl[0] - hr[0], 0, hl[2] - hr[2]]), fw = V.cross(side, [0, 1, 0]);
  const pel = V.mid(hl, hr), to = V.add(s.p, [0, (o.lift ?? 0.07) * k, 0]);
  turnPose(pose, pel, [0, 1, 0], s.yaw - Math.atan2(fw[0], fw[2]));
  shiftPose(pose, V.sub(to, pel));
  const sp = o.spread ?? 28, th = o.thigh ?? 50, kn = o.knee ?? 62, bends = [];
  for (const [S, sg] of [['L', 1], ['R', -1]]) {
    // (what the hand holds goes with the hand: a drawn sword, a spear)
    const wr = pose.j[`wrist.${S}`], held = wr ? Object.keys(pose.props ?? {}).filter((n) => V.dist(pose.props[n].pos, wr) < 0.2 * k) : [];
    bends.push({ bone: `upperleg01.${S}`, about: 'fwd', deg: sg * sp }, { bone: `upperleg01.${S}`, about: 'side', deg: -th },
      { bone: `lowerleg01.${S}`, about: 'side', deg: kn }, { bone: `lowerleg01.${S}`, about: 'fwd', deg: -sg * sp * 0.86 }, { bone: `foot.${S}`, about: 'side', deg: -10 });
    if (o.arms !== false) bends.push({ bone: `upperarm01.${S}`, about: 'side', deg: -16, props: held }, { bone: `lowerarm01.${S}`, about: 'side', deg: -68, props: held }, { bone: `lowerarm01.${S}`, about: 'up', deg: -sg * 30, props: held });
  }
  bendPose(pose, bends);
  pose.contact = 0;
  return pose;
}

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
/**
 * A horse and its rider for a sequence: returns { horse, actor (spread into the rider's actor: { id, design, ...R.actor }),
 * item(ts, ctx) (for the sequence's dyn list) }. A rider with a real body is painted in one picture with the horse, so
 * the horse hides the leg on its far side and the rider's legs hide the horse's flanks.
 * o: { horse: { coat, mane, … }, path: [[t, x, z, gait?], …] or (ts) → { pos, yaw, dist, gait }, ground, lift, arms: false … }
 */
export function rider(o) {
  const horse = new Horse(o.horse ?? {}), where = typeof o.path === 'function' ? o.path : ridePath(o.path);
  let cur = null, real = false, last = null;
  // (where the hands hold the reins and the feet stand in the stirrups)
  const held = () => { const j = last?.j; if (!j) return {}; const pick = (...ns) => { for (const n of ns) if (j[n]) return j[n]; return null; };
    return { hands: o.arms === false ? null : [pick('finger3-1.L', 'wrist.L'), pick('finger3-1.R', 'wrist.R')], feet: [pick('toe2-1.L', 'foot.L'), pick('toe2-1.R', 'foot.R')] }; };
  const upd = (ts) => { cur = where(ts); horse.place(cur.pos, cur.yaw, cur.dist, cur.gait, ts, { ground: o.ground ?? 0, rear: o.rear ? o.rear(ts) : 0, head: o.head ? o.head(ts) : 0 }); return cur; };
  const fit = (pose, t, ctx) => { upd(ctx.shot.t0 + t); real = !!pose.body; last = pose; seat(pose, horse, { cam: ctx.cam, lift: o.lift, arms: o.arms, spread: o.spread, thigh: o.thigh, knee: o.knee }); };
  return {
    horse, where, fit,
    actor: { fit, shadow: false, along: (t, ctx, cam, lit) => (real ? horseMeshes(horse, lit, { eye: cam.pos, ...held() }) : null) },
    item: (ts, ctx) => { if (!cur) upd(ts); const p = [cur.pos[0], o.ground ?? 0, cur.pos[1]]; return { p, bias: 0.35, draw: (g, cam, sc) => horse.draw(g, cam, sc.litAt(V.dist(cam.pos, p)), { light: sc.light2(cam), sun: sc.sun, shadow: true, ground: o.ground ?? 0, shadowAlpha: sc.o.shadowAlpha ?? 0.28, shadowColor: sc.o.shadowColor, body: !real }) }; },
  };
}
