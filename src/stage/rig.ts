/**
 * Solved body control: stances with footwork (planted feet, stepping, lunges, ducks), strikes that land on a target,
 * and keyframed IK handles. These sit beside captured motion: the capture gives natural whole-body movement, the rig
 * gives exact control of where a hand, a foot or the hips are at a given frame.
 */
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-nocheck — the engine modules are plain JS
import { THREE, clamp, lerp, smooth } from "./engine/core.js";
import { ik2 } from "./engine/anim.js";
import type { KeyTrack, StanceBlock, Vec3 } from "./spec";

const D2R = Math.PI / 180;
const Yax = new THREE.Vector3(0, 1, 0), Xax = new THREE.Vector3(1, 0, 0);
const _q = new THREE.Quaternion(), _q2 = new THREE.Quaternion(), _v = new THREE.Vector3();

export interface FootState {
  /** Metres right and forward of where the stance began (in the facing it began with). */
  right: number;
  forward: number;
  /** Height above the ground, metres. */
  lift: number;
  /** Which way the stance faced when this foot was last put down, radians (left is positive). */
  turn: number;
}
export interface StanceState {
  right: number;
  forward: number;
  /** Radians, left positive. */
  turn: number;
  feet: { L: FootState; R: FootState };
  /** Hips relative to the stance centre, in the stance's own frame: metres left, up (negative sinks), forward; yaw and pitch in radians. */
  hip: { left: number; up: number; forward: number; yaw: number; pitch: number };
  lead: "L" | "R" | "none";
}

const sm = (u: number) => u * u * (3 - 2 * u);

/** Where the stance, both feet and the hips are `local` seconds into a stance block. `k` scales lengths to the body (leg length / 0.87). */
export function evalStance(b: StanceBlock, local: number, k: number, t: number): StanceState {
  const lead = b.lead ?? "L";
  const square = lead === "none";
  const len = square ? 0 : (b.length ?? 0.56) * k, wid = (b.width ?? (square ? 0.44 : 0.3)) * k;
  const sgn = lead === "R" ? -1 : 1;
  // foot rest offsets from the centre, [right, forward]
  const off = { L: [-wid / 2, sgn * len / 2] as [number, number], R: [wid / 2, -sgn * len / 2] as [number, number] };
  // footwork keys: absolute offsets and turn
  const keys: [number, number, number, number][] = [[0, 0, 0, 0]];
  for (const p of [...(b.path ?? [])].sort((x, y) => x[0] - y[0])) {
    const last = keys[keys.length - 1]!;
    keys.push([Math.max(last[0] + 0.05, p[0]), p[1], p[2], p[3] === undefined || p[3] === null ? last[3] : p[3] * D2R]);
  }
  let i = 0;
  while (i < keys.length - 2 && local >= keys[i + 1]![0]) i++;
  const A = keys[i]!, B = keys[Math.min(i + 1, keys.length - 1)]!;
  const u = keys.length > 1 && B[0] > A[0] ? clamp((local - A[0]) / (B[0] - A[0])) : 1;
  const dr = B[1] - A[1], df = B[2] - A[2], dturn = B[3] - A[3];
  const dist = Math.hypot(dr, df);
  const moving = dist > 0.02 || Math.abs(dturn) > 0.05;
  // who steps first: the foot on the side we are going to
  let first: "L" | "R";
  if (Math.abs(df) >= Math.abs(dr)) first = (df >= 0) === (sgn > 0) ? "L" : "R";
  else first = dr > 0 ? "R" : "L";
  if (square && Math.abs(df) >= Math.abs(dr)) first = "R";
  const H = moving ? clamp(0.05 + 0.2 * dist, 0.05, 0.16) * k : 0;
  const at = (ph: number): [number, number, number] => [lerp(A[1], B[1], sm(ph)), lerp(A[2], B[2], sm(ph)), lerp(A[3], B[3], sm(ph))];
  const foot = (side: "L" | "R"): FootState => {
    // one foot at a time: the second leaves the ground as the first lands, so the body is always carried
    const ph = side === first ? clamp(u / 0.5) : clamp((u - 0.5) / 0.5);
    const [cr, cf, ct] = at(ph);
    // rotate the foot's rest offset by the stance's turn
    const x = -off[side][0], z = off[side][1];
    const xr = x * Math.cos(ct) + z * Math.sin(ct), zr = -x * Math.sin(ct) + z * Math.cos(ct);
    return { right: cr - xr, forward: cf + zr, lift: moving ? Math.sin(Math.PI * ph) * H : 0, turn: ct };
  };
  const [cr, cf, ct] = at(u);
  const S: StanceState = {
    right: cr,
    forward: cf,
    turn: ct,
    feet: { L: foot("L"), R: foot("R") },
    hip: { left: 0, up: -(b.drop ?? (square ? 0.05 : 0.09)) * k, forward: 0, yaw: square ? 0 : -sgn * (b.blade ?? 26) * D2R, pitch: square ? 0 : 0.07 },
    lead,
  };
  // weight sits a little towards the back foot at rest
  // lunges (reach > 0: the front foot steps out and the hips follow) and retreats (reach < 0: the back foot steps away)
  const front: "L" | "R" = sgn > 0 ? "L" : "R", rear: "L" | "R" = sgn > 0 ? "R" : "L";
  for (const lg of b.lunges ?? []) {
    const [ta, reach, out = 0.16, back = 0.32, hold = 0.05] = lg;
    if (local < ta - out || local > ta + hold + back) continue;
    let e: number, lift = 0;
    if (local < ta) {
      const q = (local - (ta - out)) / out;
      e = q * q * (3 - 2 * q);
      lift = Math.sin(Math.PI * q) * 0.06 * k;
    } else if (local <= ta + hold) e = 1;
    else {
      const q = (local - ta - hold) / back;
      e = 1 - sm(q);
      lift = Math.sin(Math.PI * q) * 0.04 * k;
    }
    const r = reach * k * e;
    const f = S.feet[reach >= 0 ? front : rear];
    // along the stance's current facing
    f.right += -Math.sin(ct) * r;
    f.forward += Math.cos(ct) * r;
    f.lift = Math.max(f.lift, lift);
    S.hip.forward += 0.6 * r;
    S.hip.up -= 0.2 * Math.abs(r);
    S.hip.pitch += (reach >= 0 ? 0.16 : -0.1) * e * Math.min(1, Math.abs(reach) / 0.4);
  }
  for (const dk of b.ducks ?? []) {
    const [ta, depth, down = 0.14, up = 0.3] = dk;
    if (local < ta - down || local > ta + up) continue;
    const e = local < ta ? sm((local - (ta - down)) / down) : 1 - sm((local - ta) / up);
    S.hip.up -= depth * k * e;
    S.hip.pitch += 0.5 * e * Math.min(1, depth / 0.3);
  }
  if (b.bounce) {
    const ph = t * 2.3 * Math.PI * 2;
    S.hip.up += Math.sin(ph) * 0.016 * b.bounce * k;
    S.hip.left += Math.sin(ph * 0.5) * 0.012 * b.bounce * k;
  }
  return S;
}

/** Put one foot (its ankle) at a world position with the sole flat and the toes along `toeYaw` (world radians). */
export function plantFoot(a: any, side: "L" | "R", target: THREE.Vector3, toeYaw: number, lift = 0): void {
  const h = a.h;
  const pole = _v.set(Math.sin(toeYaw), 0.25, Math.cos(toeYaw));
  ik2(h, `upperleg01.${side}`, `lowerleg01.${side}`, `foot.${side}`, target, pole.clone());
  const foot = h.B[`foot.${side}`];
  foot.parent.getWorldQuaternion(_q);
  // a lifted foot hangs toe-down a little
  _q2.setFromAxisAngle(Yax, toeYaw).multiply(new THREE.Quaternion().setFromAxisAngle(Xax, Math.min(0.5, lift * 3.2)));
  foot.quaternion.copy(_q.invert().multiply(_q2));
  h.root.updateMatrixWorld(true);
}

/** Pose the lower body from a stance state. `base` is where the stance block began (stage → world already applied). */
export function applyStance(
  a: any,
  S: StanceState,
  base: { x: number; z: number; yaw: number },
  ground: (x: number, z: number) => number,
  yOff: number,
  scale: number,
  guard: "fists" | "open" | "none",
): void {
  const h = a.h;
  const fwd = [Math.sin(base.yaw), Math.cos(base.yaw)] as const, rgt = [-Math.cos(base.yaw), Math.sin(base.yaw)] as const;
  const wx = (r: number, f: number) => base.x + rgt[0] * r + fwd[0] * f, wz = (r: number, f: number) => base.z + rgt[1] * r + fwd[1] * f;
  const cx = wx(S.right, S.forward), cz = wz(S.right, S.forward);
  const yaw = base.yaw + S.turn;
  a.place(cx, ground(cx, cz) + yOff, cz, yaw, scale);
  const rest = h.rest.root.head;
  h.B.root.position.set(rest.x + S.hip.left / scale, rest.y + S.hip.up / scale, rest.z + S.hip.forward / scale);
  h.B.root.quaternion.setFromAxisAngle(Yax, S.hip.yaw).multiply(_q.setFromAxisAngle(Xax, S.hip.pitch));
  // the chest turns back towards the front, the head stays on the opponent
  const back = (-S.hip.yaw / D2R);
  h.addRot("spine05", -S.hip.pitch / D2R * 0.35, back * 0.3, 0);
  h.addRot("spine03", -S.hip.pitch / D2R * 0.25, back * 0.25, 0);
  h.addRot("neck01", 0, back * 0.3, 0);
  h.root.updateMatrixWorld(true);
  const ankle = h.rest["foot.L"].head.y * scale;
  const sgn = S.lead === "R" ? -1 : 1;
  for (const side of ["L", "R"] as const) {
    const f = S.feet[side];
    const x = wx(f.right, f.forward), z = wz(f.right, f.forward);
    const isFront = S.lead === "none" ? false : (side === "L") === (sgn > 0);
    const out = S.lead === "none" ? 14 : isFront ? 6 : 52;
    const toe = base.yaw + f.turn + (side === "L" ? 1 : -1) * out * D2R;
    plantFoot(a, side, new THREE.Vector3(x, ground(x, z) + yOff + ankle + f.lift, z), toe, f.lift);
  }
  if (guard !== "none") guardArms(a, guard, S.lead, scale);
}

/** Arms for a stance: a boxing guard or hands held low and ready. */
export function guardArms(a: any, guard: "fists" | "open", lead: "L" | "R" | "none", scale: number): void {
  const h = a.h;
  h.root.updateMatrixWorld(true);
  const q = a.root.quaternion;
  const k = (h.height / 1.75) * scale;
  const neck = h.wpos("neck01", new THREE.Vector3());
  const sgn = lead === "R" ? -1 : 1;
  for (const side of ["L", "R"] as const) {
    const sd = side === "L" ? 1 : -1;
    const front = lead !== "none" && (side === "L") === (sgn > 0);
    const local = guard === "fists" ? (front ? new THREE.Vector3(sd * 0.13, -0.1, 0.36) : new THREE.Vector3(sd * 0.11, -0.13, 0.2)) : new THREE.Vector3(sd * 0.2, -0.42, front ? 0.3 : 0.2);
    const p = local.multiplyScalar(k).applyQuaternion(q).add(neck);
    ik2(h, `upperarm01.${side}`, `lowerarm01.${side}`, `wrist.${side}`, p, new THREE.Vector3(sd * 0.55, -1, -0.15).applyQuaternion(q));
    if (guard === "fists") h.fingers(side, 1, 0.9);
    else h.fingers(side, 0.45, 0.4);
  }
  h.root.updateMatrixWorld(true);
}

/** Strike weight over time: 0 → 1 with a snap at the apex, held, then released. */
export function strikeWeight(t: number, t0: number, apex: number, hold: number, t1: number): number {
  if (t <= t0 || t >= t1) return 0;
  if (t < apex) {
    const u = (t - t0) / Math.max(1e-3, apex - t0);
    return Math.pow(u, 2.4);
  }
  if (t <= apex + hold) return 1;
  return 1 - sm(clamp((t - apex - hold) / Math.max(1e-3, t1 - apex - hold)));
}

/** Send a hand or foot along a path to a world point. w = 0 leaves the limb where the pose has it, 1 puts it on the target. */
export function strikeLimb(a: any, limb: string, target: THREE.Vector3, w: number, via: THREE.Vector3 | null): void {
  const h = a.h;
  h.root.updateMatrixWorld(true);
  const side = limb.endsWith(".L") ? "L" : "R";
  const isHand = limb.startsWith("hand");
  const end = isHand ? `wrist.${side}` : `foot.${side}`;
  const from = h.wpos(end, new THREE.Vector3());
  const q = a.root.quaternion;
  let ctrl: THREE.Vector3;
  if (isHand) ctrl = from.clone().lerp(target, 0.5);
  else {
    // a kick chambers: the knee comes up before the foot goes out
    const hip = h.wpos(`upperleg01.${side}`, new THREE.Vector3());
    ctrl = hip.clone().add(new THREE.Vector3(0, -0.28, 0.3).multiplyScalar(h.height / 1.75).applyQuaternion(q));
  }
  if (via) ctrl.add(via);
  // quadratic path through the control point
  const u = w, p = new THREE.Vector3()
    .addScaledVector(from, (1 - u) * (1 - u))
    .addScaledVector(ctrl, 2 * u * (1 - u))
    .addScaledVector(target, u * u);
  const sd = side === "L" ? 1 : -1;
  if (isHand) {
    ik2(h, `upperarm01.${side}`, `lowerarm01.${side}`, `wrist.${side}`, p, new THREE.Vector3(sd * 0.7, -0.7, -0.2).applyQuaternion(q));
    h.fingers(side, lerp(0.5, 1, w), lerp(0.5, 0.9, w));
  } else {
    const dir = target.clone().sub(from).setY(0);
    if (dir.lengthSq() < 1e-6) dir.set(0, 0, 1).applyQuaternion(q);
    dir.normalize();
    ik2(h, `upperleg01.${side}`, `lowerleg01.${side}`, `foot.${side}`, p, new THREE.Vector3(dir.x * 0.6, 1, dir.z * 0.6));
    // point the toes along the shin as the leg extends
    const foot = h.B[`foot.${side}`];
    foot.quaternion.slerp(_q.setFromAxisAngle(Xax, 0.75), smooth(0.2, 0.9, w));
  }
  h.root.updateMatrixWorld(true);
}

const EASE: Record<string, (u: number) => number> = {
  linear: (u) => u,
  in: (u) => u * u,
  out: (u) => 1 - (1 - u) * (1 - u),
  io: sm,
  snap: (u) => Math.pow(u, 3),
  hold: (u) => (u >= 1 ? 1 : 0),
};

/** Value of a key track at `local` seconds (clamped to the first and last keys). */
export function keyValue(tr: KeyTrack, local: number): Vec3 | null {
  const ks = tr.keys;
  if (!ks.length) return null;
  if (local <= ks[0]![0]) return ks[0]![1];
  const last = ks[ks.length - 1]!;
  if (local >= last[0]) return last[1];
  let i = 0;
  while (i < ks.length - 2 && local >= ks[i + 1]![0]) i++;
  const A = ks[i]!, B = ks[i + 1]!;
  const u = (EASE[B[2] ?? "io"] ?? sm)(clamp((local - A[0]) / Math.max(1e-6, B[0] - A[0])));
  return [lerp(A[1][0], B[1][0], u), lerp(A[1][1], B[1][1], u), lerp(A[1][2], B[1][2], u)];
}

export const IK_HANDLES = ["hand.L", "hand.R", "foot.L", "foot.R"];

/** Put an IK handle at a world position, blended by w. */
export function setHandle(a: any, handle: string, p: THREE.Vector3, w: number): void {
  const h = a.h;
  const side = handle.endsWith(".L") ? "L" : "R";
  const sd = side === "L" ? 1 : -1;
  const q = a.root.quaternion;
  const before = w < 1 ? h.snapshot() : null;
  if (handle.startsWith("hand")) ik2(h, `upperarm01.${side}`, `lowerarm01.${side}`, `wrist.${side}`, p, new THREE.Vector3(sd * 0.5, -1, -0.3).applyQuaternion(q));
  else ik2(h, `upperleg01.${side}`, `lowerleg01.${side}`, `foot.${side}`, p, new THREE.Vector3(0, 0.3, 1).applyQuaternion(q));
  if (before) h.blendTo(before, 1 - w);
  h.root.updateMatrixWorld(true);
}
