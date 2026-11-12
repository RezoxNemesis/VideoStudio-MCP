// Baked body motion: joint tracks solved from motion capture (and IK) at 60 frames a second.
import fs from 'node:fs';
import { V, qnlerp, qrot } from './core.js';
import { ENV, onEnv } from './env.js';

const cache = new Map();
onEnv(() => cache.clear());
/** Forget loaded bakes (after a motion solve wrote new files). */
export function dropBakes(name) { if (name) cache.delete(name); else cache.clear(); }

export function loadBake(name) {
  let b = cache.get(name);
  if (b) return b;
  const meta = JSON.parse(fs.readFileSync(`${ENV.bakeDir}${name}.json`, 'utf8'));
  const buf = fs.readFileSync(`${ENV.bakeDir}${name}.bin`);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  const ji = Object.fromEntries(meta.joints.map((n, i) => [n, i])), qi = Object.fromEntries(meta.quats.map((n, i) => [n, i]));
  const actors = {};
  for (const a of meta.actors) {
    actors[a.id] = {
      id: a.id, height: a.height,
      data: new Float32Array(ab, a.data[0], a.data[1] / 4),
      contact: new Uint8Array(ab, a.contact[0], a.contact[1]),
      props: Object.fromEntries(a.props.map((p) => [p.name, new Float32Array(ab, p.data[0], p.data[1] / 4)])),
      // the real body, when the performance was solved with `bodies` (see body.js)
      bodyMeta: a.body ?? null, rigMeta: a.rig ?? null, ab,
    };
  }
  b = { name, fps: meta.fps, frames: meta.frames, dur: (meta.frames - 1) / meta.fps, joints: meta.joints, quats: meta.quats, stride: meta.stride, ji, qi, actors, info: meta.info, issues: meta.issues };
  cache.set(name, b);
  return b;
}

/**
 * The body at a moment. `xf` places the baked performance in the film's world: { pos: [x, y, z], yaw (radians), scale }.
 * Returns { j: name → [x, y, z], q: name → quaternion, props: name → { pos, q, visible }, contact, height }.
 */
export function poseAt(bake, id, t, xf = null) {
  const a = bake.actors[id];
  if (!a) throw new Error(`no actor ${id} in bake ${bake.name}`);
  const f = Math.max(0, Math.min(bake.frames - 1, t * bake.fps)), i0 = Math.min(bake.frames - 2 < 0 ? 0 : bake.frames - 2, Math.floor(f)), i1 = Math.min(bake.frames - 1, i0 + 1), u = f - i0;
  const S = bake.stride, d = a.data, o0 = i0 * S, o1 = i1 * S, nj = bake.joints.length;
  const c = xf ? Math.cos(xf.yaw ?? 0) : 1, s = xf ? Math.sin(xf.yaw ?? 0) : 0, k = xf?.scale ?? 1, px = xf?.pos?.[0] ?? 0, py = xf?.pos?.[1] ?? 0, pz = xf?.pos?.[2] ?? 0;
  const place = (x, y, z) => [(x * c + z * s) * k + px, y * k + py, (-x * s + z * c) * k + pz];
  const j = {};
  for (let n = 0; n < nj; n++) {
    const b0 = o0 + n * 3, b1 = o1 + n * 3;
    j[bake.joints[n]] = place(d[b0] + (d[b1] - d[b0]) * u, d[b0 + 1] + (d[b1 + 1] - d[b0 + 1]) * u, d[b0 + 2] + (d[b1 + 2] - d[b0 + 2]) * u);
  }
  const yq = xf && xf.yaw ? [0, Math.sin(xf.yaw / 2), 0, Math.cos(xf.yaw / 2)] : null;
  const qmul = (p, q) => [p[3] * q[0] + p[0] * q[3] + p[1] * q[2] - p[2] * q[1], p[3] * q[1] - p[0] * q[2] + p[1] * q[3] + p[2] * q[0], p[3] * q[2] + p[0] * q[1] - p[1] * q[0] + p[2] * q[3], p[3] * q[3] - p[0] * q[0] - p[1] * q[1] - p[2] * q[2]];
  const q = {};
  for (let n = 0; n < bake.quats.length; n++) {
    const b0 = o0 + nj * 3 + n * 4, b1 = o1 + nj * 3 + n * 4;
    let v = qnlerp([d[b0], d[b0 + 1], d[b0 + 2], d[b0 + 3]], [d[b1], d[b1 + 1], d[b1 + 2], d[b1 + 3]], u);
    if (yq) v = qmul(yq, v);
    q[bake.quats[n]] = v;
  }
  const props = {};
  for (const [name, pd] of Object.entries(a.props)) {
    const b0 = i0 * 8, b1 = i1 * 8;
    let pq = qnlerp([pd[b0 + 3], pd[b0 + 4], pd[b0 + 5], pd[b0 + 6]], [pd[b1 + 3], pd[b1 + 4], pd[b1 + 5], pd[b1 + 6]], u);
    if (yq) pq = qmul(yq, pq);
    props[name] = { pos: place(pd[b0] + (pd[b1] - pd[b0]) * u, pd[b0 + 1] + (pd[b1 + 1] - pd[b0 + 1]) * u, pd[b0 + 2] + (pd[b1 + 2] - pd[b0 + 2]) * u), q: pq, visible: pd[(u < 0.5 ? i0 : i1) * 8 + 7] > 0.5 };
  }
  return { j, q, props, contact: a.contact[u < 0.5 ? i0 : i1], height: a.height * k, id };
}

/** Local axes of a prop in the world: +Y of a sword is its blade. */
export const propAxis = (p, axis) => qrot(p.q, axis);
export { V };
