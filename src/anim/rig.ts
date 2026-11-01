/**
 * Character rigs: every body part is its own drawing layer hanging from a parent at a joint (pivot).
 * Rotating a joint moves everything below it (forward kinematics); IK places a hand or foot at a target.
 * Motions (walk, run, wave, jump, idle, talk, dance, point, nod) write per-part keyframes.
 */
import { valueAt } from "../core/keyframes";
import { TRANSFORM_DEFAULTS, emptyCel, newAnimLayer, uid, type AnimLayer, type AnimScene, type Ease, type Keyframe, type Stroke, type TransformProp } from "../core/model";
import { OpError } from "../core/ops";

/* --------------------------------- affine --------------------------------- */

/** 2D affine [a, b, c, d, e, f]: x' = a x + c y + e, y' = b x + d y + f (same as canvas). */
export type Mat = [number, number, number, number, number, number];
export const IDENTITY: Mat = [1, 0, 0, 1, 0, 0];

export function mul(m: Mat, n: Mat): Mat {
  return [m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1], m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3], m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5]];
}

export function apply(m: Mat, x: number, y: number): [number, number] {
  return [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
}

export function invert(m: Mat): Mat {
  const det = m[0] * m[3] - m[1] * m[2] || 1e-9;
  return [m[3] / det, -m[1] / det, -m[2] / det, m[0] / det, (m[2] * m[5] - m[3] * m[4]) / det, (m[1] * m[4] - m[0] * m[5]) / det];
}

export function matRotation(m: Mat): number {
  return (Math.atan2(m[1], m[0]) * 180) / Math.PI;
}

function tv(l: AnimLayer, k: TransformProp, frame: number): number {
  return valueAt(l.transform[k], frame, TRANSFORM_DEFAULTS[k]);
}

export function pivotOf(l: AnimLayer, s: AnimScene, frame = 0): { x: number; y: number } {
  return l.pivot ?? { x: tv(l, "anchorX", frame) * s.width, y: tv(l, "anchorY", frame) * s.height };
}

/** A layer's own transform: move by (x, y), then rotate and scale around its pivot. */
export function localMatrix(l: AnimLayer, s: AnimScene, frame: number): Mat {
  const p = pivotOf(l, s, frame);
  const r = (tv(l, "rotation", frame) * Math.PI) / 180;
  const sc = tv(l, "scale", frame);
  const sx = sc * tv(l, "scaleX", frame);
  const sy = sc * tv(l, "scaleY", frame);
  const cos = Math.cos(r);
  const sin = Math.sin(r);
  const tx = p.x + tv(l, "x", frame);
  const ty = p.y + tv(l, "y", frame);
  // T(tx,ty) · R · S · T(-p)
  const a = cos * sx;
  const b = sin * sx;
  const c = -sin * sy;
  const d = cos * sy;
  return [a, b, c, d, tx - (a * p.x + c * p.y), ty - (b * p.x + d * p.y)];
}

/** Parent chain applied: where this layer's rest-pose drawing ends up at `frame`. */
export function worldMatrix(s: AnimScene, l: AnimLayer, frame: number, depth = 0): Mat {
  const own = localMatrix(l, s, frame);
  if (!l.parent || depth > 32) return own;
  const parent = s.layers.find((x) => x.id === l.parent);
  return parent ? mul(worldMatrix(s, parent, frame, depth + 1), own) : own;
}

/* ------------------------------ rig building ------------------------------ */

export const PARTS = ["hips", "torso", "head", "upperArmB", "foreArmB", "handB", "thighB", "shinB", "footB", "thighF", "shinF", "footF", "upperArmF", "foreArmF", "handF"] as const;
export type Part = (typeof PARTS)[number];

/** Friendly names agents and people use. B = far/back side, F = near/front side. */
export const PART_ALIASES: Record<string, Part> = {
  pelvis: "hips", root: "hips", body: "torso", chest: "torso", neck: "head",
  armB: "upperArmB", elbowB: "foreArmB", wristB: "handB", legB: "thighB", kneeB: "shinB", ankleB: "footB",
  armF: "upperArmF", elbowF: "foreArmF", wristF: "handF", legF: "thighF", kneeF: "shinF", ankleF: "footF",
  upperArmL: "upperArmB", foreArmL: "foreArmB", handL: "handB", thighL: "thighB", shinL: "shinB", footL: "footB",
  upperArmR: "upperArmF", foreArmR: "foreArmF", handR: "handF", thighR: "thighF", shinR: "shinF", footR: "footF",
};

export function partName(n: string): Part {
  const p = (PARTS as readonly string[]).includes(n) ? (n as Part) : PART_ALIASES[n];
  if (!p) throw new OpError(`Unknown body part "${n}". Parts: ${PARTS.join(", ")}.`);
  return p;
}

export interface RigStyle {
  view: "side" | "front";
  skin: string;
  shirt: string;
  pants: string;
  shoes: string;
  hair: string;
  outline: string;
  outlineWidth: number;
}

export const DEFAULT_STYLE: RigStyle = { view: "side", skin: "#f2c29b", shirt: "#3f6fd8", pants: "#2f3a4f", shoes: "#222222", hair: "#3a2a1c", outline: "#1a1410", outlineWidth: 4 };

/** Capsule polygon from (x, y) straight down `len`, `w` wide (rest pose: limbs hang down). */
function capsule(x: number, y: number, len: number, w0: number, w1 = w0): number[] {
  const pts: number[] = [];
  const r0 = w0 / 2;
  const r1 = w1 / 2;
  for (let i = 0; i <= 10; i++) {
    const a = Math.PI + (i / 10) * Math.PI; // top semicircle (left → right)
    pts.push(x + Math.cos(a) * r0, y + Math.sin(a) * r0 * 0.9, 0.8);
  }
  for (let i = 0; i <= 10; i++) {
    const a = (i / 10) * Math.PI; // bottom semicircle (right → left)
    pts.push(x + Math.cos(a) * r1, y + len + Math.sin(a) * r1 * 0.9, 0.8);
  }
  return pts;
}

function ellipse(cx: number, cy: number, rx: number, ry: number, n = 40): number[] {
  const pts: number[] = [];
  for (let i = 0; i <= n; i++) {
    const a = (i / n) * Math.PI * 2;
    pts.push(cx + Math.cos(a) * rx, cy + Math.sin(a) * ry, 0.8);
  }
  return pts;
}

function poly(points: [number, number][]): number[] {
  const out: number[] = [];
  for (const [x, y] of points) out.push(x, y, 0.8);
  out.push(points[0]![0], points[0]![1], 0.8);
  return out;
}

function shade(hex: string, k: number): string {
  const n = parseInt(hex.replace("#", ""), 16);
  const f = (v: number) => Math.max(0, Math.min(255, Math.round(v * k)));
  return `#${[f((n >> 16) & 255), f((n >> 8) & 255), f(n & 255)].map((v) => v.toString(16).padStart(2, "0")).join("")}`;
}

function shape(pts: number[], fill: string, st: RigStyle): Omit<Stroke, "id"> {
  return { tool: "pen", color: st.outline, size: st.outlineWidth, opacity: 1, pts, closed: true, fill };
}

function line(pts: number[], color: string, size: number): Omit<Stroke, "id"> {
  return { tool: "ink", color, size, opacity: 1, pts };
}

interface PartSpec {
  part: Part;
  parent: Part | null;
  pivot: [number, number];
  tip: [number, number];
  strokes: Omit<Stroke, "id">[];
}

/**
 * Build a humanoid puppet. (x, y) is the hip joint; `height` is head-top to sole.
 * Side view faces right. Far-side limbs are drawn darker and behind the body.
 */
export function buildRig(s: AnimScene, x: number, y: number, height: number, style: Partial<RigStyle> = {}, name = "Puppet"): { rig: string; parts: Record<Part, string> } {
  const st = { ...DEFAULT_STYLE, ...style };
  const H = height;
  const side = st.view === "side";
  const neck: [number, number] = [x, y - 0.3 * H];
  const headC: [number, number] = [x + (side ? 0.01 * H : 0), y - 0.39 * H];
  const shoulder = (k: 1 | -1): [number, number] => [x + (side ? 0 : k * 0.11 * H), neck[1] + 0.035 * H];
  const hipJ = (k: 1 | -1): [number, number] => [x + (side ? 0 : k * 0.055 * H), y + 0.01 * H];
  const up = 0.175 * H;
  const fore = 0.165 * H;
  const thigh = 0.23 * H;
  const shin = 0.22 * H;
  const limbW = 0.055 * H;
  const legW = 0.07 * H;
  const specs: PartSpec[] = [];
  const arm = (k: 1 | -1, back: boolean) => {
    const sfx = back ? "B" : "F";
    const sh = shoulder(k);
    const dim = back ? 0.78 : 1;
    const el: [number, number] = [sh[0], sh[1] + up];
    const wr: [number, number] = [sh[0], el[1] + fore];
    specs.push({ part: `upperArm${sfx}` as Part, parent: "torso", pivot: sh, tip: el, strokes: [shape(capsule(sh[0], sh[1], up, limbW * 1.05, limbW), shade(st.shirt, dim), st)] });
    specs.push({ part: `foreArm${sfx}` as Part, parent: `upperArm${sfx}` as Part, pivot: el, tip: wr, strokes: [shape(capsule(el[0], el[1], fore, limbW * 0.92, limbW * 0.8), shade(st.skin, dim), st)] });
    specs.push({ part: `hand${sfx}` as Part, parent: `foreArm${sfx}` as Part, pivot: wr, tip: [wr[0], wr[1] + 0.06 * H], strokes: [shape(ellipse(wr[0] + (side ? 0.008 * H : 0), wr[1] + 0.03 * H, 0.032 * H, 0.038 * H), shade(st.skin, dim), st)] });
  };
  const leg = (k: 1 | -1, back: boolean) => {
    const sfx = back ? "B" : "F";
    const hp = hipJ(k);
    const dim = back ? 0.78 : 1;
    const kn: [number, number] = [hp[0], hp[1] + thigh];
    const an: [number, number] = [hp[0], kn[1] + shin];
    const toe = side ? 0.11 * H : k * 0.03 * H;
    specs.push({ part: `thigh${sfx}` as Part, parent: "hips", pivot: hp, tip: kn, strokes: [shape(capsule(hp[0], hp[1], thigh, legW * 1.1, legW * 0.95), shade(st.pants, dim), st)] });
    specs.push({ part: `shin${sfx}` as Part, parent: `thigh${sfx}` as Part, pivot: kn, tip: an, strokes: [shape(capsule(kn[0], kn[1], shin, legW * 0.95, legW * 0.8), shade(st.pants, dim), st)] });
    const fw = 0.05 * H;
    const footPts: [number, number][] = side
      ? [[an[0] - 0.035 * H, an[1] - 0.02 * H], [an[0] + 0.02 * H, an[1] - 0.02 * H], [an[0] + toe, an[1] + 0.01 * H], [an[0] + toe, an[1] + fw * 0.6], [an[0] - 0.04 * H, an[1] + fw * 0.6]]
      : [[an[0] - 0.035 * H, an[1] - 0.02 * H], [an[0] + 0.035 * H, an[1] - 0.02 * H], [an[0] + 0.04 * H + toe, an[1] + fw * 0.6], [an[0] - 0.04 * H + toe, an[1] + fw * 0.6]];
    specs.push({ part: `foot${sfx}` as Part, parent: `shin${sfx}` as Part, pivot: an, tip: [an[0] + toe, an[1] + fw * 0.3], strokes: [shape(poly(footPts), shade(st.shoes, dim), st)] });
  };
  // Back to front.
  arm(-1, true);
  leg(-1, true);
  specs.push({ part: "hips", parent: null, pivot: [x, y], tip: [x, y + 0.04 * H], strokes: [shape(ellipse(x, y + 0.01 * H, 0.085 * H, 0.045 * H), st.pants, st)] });
  specs.push({
    part: "torso",
    parent: "hips",
    pivot: [x, y],
    tip: neck,
    strokes: [shape(poly([[x - 0.09 * H, y + 0.02 * H], [x + 0.09 * H, y + 0.02 * H], [x + 0.11 * H, neck[1] + 0.04 * H], [x + 0.06 * H, neck[1] - 0.005 * H], [x - 0.06 * H, neck[1] - 0.005 * H], [x - 0.11 * H, neck[1] + 0.04 * H]]), st.shirt, st)],
  });
  const hr = 0.075 * H;
  const face = side ? 1 : 0;
  specs.push({
    part: "head",
    parent: "torso",
    pivot: neck,
    tip: [headC[0], headC[1] - hr],
    strokes: [
      shape(capsule(neck[0], neck[1] - 0.02 * H, 0.035 * H, 0.04 * H), st.skin, st),
      shape(ellipse(headC[0], headC[1], hr * 0.95, hr * 1.05), st.skin, st),
      shape(poly([[headC[0] - hr * 1.0, headC[1] - hr * 0.1], [headC[0] - hr * 0.8, headC[1] - hr * 0.95], [headC[0] + hr * 0.2, headC[1] - hr * 1.15], [headC[0] + hr * 0.95, headC[1] - hr * 0.55], [headC[0] + hr * (side ? 0.4 : 0.9), headC[1] - hr * 0.55], [headC[0] - hr * 0.55, headC[1] - hr * 0.3]]), st.hair, st),
      ...(side
        ? [
            shape(ellipse(headC[0] + hr * 0.45, headC[1] - hr * 0.05, hr * 0.09, hr * 0.13, 16), "#1b1410", st),
            line([headC[0] + hr * 0.3, headC[1] + hr * 0.42, 0.6, headC[0] + hr * 0.55, headC[1] + hr * 0.4, 0.9, headC[0] + hr * 0.72, headC[1] + hr * 0.3, 0.6], "#5b2a1e", 0.012 * H),
            shape(ellipse(headC[0] - hr * 0.25, headC[1] + hr * 0.05, hr * 0.14, hr * 0.2, 16), shade(st.skin, 0.88), st),
          ]
        : [
            shape(ellipse(headC[0] - hr * 0.35, headC[1] - hr * 0.05, hr * 0.09, hr * 0.13, 16), "#1b1410", st),
            shape(ellipse(headC[0] + hr * 0.35, headC[1] - hr * 0.05, hr * 0.09, hr * 0.13, 16), "#1b1410", st),
            line([headC[0] - hr * 0.3, headC[1] + hr * 0.42, 0.6, headC[0], headC[1] + hr * 0.52, 0.9, headC[0] + hr * 0.3, headC[1] + hr * 0.42, 0.6], "#5b2a1e", 0.012 * H),
          ]),
    ].map((x2) => ({ ...x2 })),
  });
  void face;
  leg(1, false);
  arm(1, false);

  const rig = uid("rig");
  const ids = {} as Record<Part, string>;
  const made: AnimLayer[] = [];
  for (const sp of specs) {
    const l = newAnimLayer(`${name} · ${sp.part}`);
    l.rig = rig;
    l.part = sp.part;
    l.pivot = { x: sp.pivot[0], y: sp.pivot[1] };
    l.tip = { x: sp.tip[0], y: sp.tip[1] };
    const cel = emptyCel();
    cel.strokes = sp.strokes.map((x2) => ({ ...x2, id: uid("stk") }));
    l.cels["0"] = cel;
    ids[sp.part] = l.id;
    made.push(l);
  }
  for (const sp of specs) {
    const l = made.find((m) => m.id === ids[sp.part])!;
    if (sp.parent) l.parent = ids[sp.parent];
  }
  // layers[0] is the top: specs were built back→front, so reverse.
  s.layers.unshift(...made.reverse());
  return { rig, parts: ids };
}

export function rigLayers(s: AnimScene, rig?: string): Map<Part, AnimLayer> {
  const id = rig ?? s.layers.find((l) => l.rig)?.rig;
  if (!id) throw new OpError("This scene has no rig. Create one with anim.rig.create.");
  const m = new Map<Part, AnimLayer>();
  for (const l of s.layers) if (l.rig === id && l.part) m.set(l.part as Part, l);
  if (!m.size) throw new OpError(`No rig ${id} in this scene.`);
  return m;
}

export function rigIds(s: AnimScene): string[] {
  return [...new Set(s.layers.map((l) => l.rig).filter(Boolean) as string[])];
}

/* ----------------------------------- keys ---------------------------------- */

export function setKey(l: AnimLayer, prop: TransformProp, frame: number, v: number, ease: Ease = "inOut"): void {
  const cur = l.transform[prop];
  let list: Keyframe[] = typeof cur === "number" ? [{ t: 0, v: cur, ease }] : cur ? cur.map((k) => ({ ...k })) : [];
  list = list.filter((k) => Math.abs(k.t - frame) > 1e-6);
  list.push({ t: frame, v, ease });
  list.sort((a, b) => a.t - b.t);
  l.transform[prop] = list;
}

export function clearKeys(l: AnimLayer, from: number, to: number): void {
  for (const prop of ["rotation", "x", "y"] as TransformProp[]) {
    const cur = l.transform[prop];
    if (Array.isArray(cur)) {
      const keep = cur.filter((k) => k.t < from || k.t > to);
      l.transform[prop] = keep.length ? keep : undefined;
    }
  }
}

export type Pose = Partial<Record<Part, number>> & { rootX?: number; rootY?: number };

/** Write a pose (joint angles in degrees, root offset in px) as keys at `frame`. */
export function applyPose(s: AnimScene, rig: string | undefined, frame: number, pose: Pose, ease: Ease = "inOut"): number {
  const parts = rigLayers(s, rig);
  let n = 0;
  for (const [k, v] of Object.entries(pose)) {
    if (v === undefined) continue;
    if (k === "rootX" || k === "rootY") {
      const hips = parts.get("hips");
      if (hips) setKey(hips, k === "rootX" ? "x" : "y", frame, v, ease);
      n++;
      continue;
    }
    const l = parts.get(partName(k));
    if (!l) continue;
    setKey(l, "rotation", frame, v, ease);
    n++;
  }
  if (frame + 1 > s.frames) s.frames = frame + 1;
  return n;
}

/** Current pose at a frame (all parts), for copy/paste and blending. */
export function readPose(s: AnimScene, rig: string | undefined, frame: number): Pose {
  const parts = rigLayers(s, rig);
  const p: Pose = {};
  for (const [name, l] of parts) p[name] = Math.round(tv(l, "rotation", frame) * 100) / 100;
  const hips = parts.get("hips");
  if (hips) {
    p.rootX = tv(hips, "x", frame);
    p.rootY = tv(hips, "y", frame);
  }
  return p;
}

/* ------------------------------------ IK ----------------------------------- */

const LIMBS: Record<string, [Part, Part, Part, 1 | -1]> = {
  // upper, lower, end, bend: -1 puts the knee forward (+x) for a right-facing rig, +1 puts the elbow back.
  armF: ["upperArmF", "foreArmF", "handF", 1],
  armB: ["upperArmB", "foreArmB", "handB", 1],
  legF: ["thighF", "shinF", "footF", -1],
  legB: ["thighB", "shinB", "footB", -1],
  armR: ["upperArmF", "foreArmF", "handF", 1],
  armL: ["upperArmB", "foreArmB", "handB", 1],
  legR: ["thighF", "shinF", "footF", -1],
  legL: ["thighB", "shinB", "footB", -1],
};

/**
 * Two-bone IK: rotate the upper and lower part of a limb so its end joint (wrist / ankle) reaches (tx, ty)
 * in scene pixels at `frame`. Writes rotation keys. Returns false when out of reach (it stretches toward it).
 */
export function solveIK(s: AnimScene, rig: string | undefined, limb: string, tx: number, ty: number, frame: number, ease: Ease = "inOut", bendOverride?: 1 | -1): boolean {
  const def = LIMBS[limb];
  if (!def) throw new OpError(`Unknown limb "${limb}". Use armF, armB, legF, legB.`);
  const parts = rigLayers(s, rig);
  const U = parts.get(def[0]);
  const Lw = parts.get(def[1]);
  const E = parts.get(def[2]);
  if (!U || !Lw || !E) throw new OpError("Rig is missing limb parts.");
  const bend = bendOverride ?? def[3];
  const pU = U.pivot!;
  const pL = Lw.pivot!;
  const pE = E.pivot!;
  const L1 = Math.hypot(pL.x - pU.x, pL.y - pU.y);
  const L2 = Math.hypot(pE.x - pL.x, pE.y - pL.y);
  const restU = Math.atan2(pL.y - pU.y, pL.x - pU.x);
  const restL = Math.atan2(pE.y - pL.y, pE.x - pL.x);
  const parent = U.parent ? s.layers.find((l) => l.id === U.parent) : undefined;
  const PW = parent ? worldMatrix(s, parent, frame) : IDENTITY;
  const parentRot = (matRotation(PW) * Math.PI) / 180;
  const [ox, oy] = apply(PW, pU.x + tv(U, "x", frame), pU.y + tv(U, "y", frame));
  const dx = tx - ox;
  const dy = ty - oy;
  const raw = Math.hypot(dx, dy);
  const d = Math.max(1e-3, Math.min(L1 + L2 - 1e-3, Math.max(Math.abs(L1 - L2) + 1e-3, raw)));
  const a1 = Math.atan2(dy, dx);
  const cosA = Math.max(-1, Math.min(1, (L1 * L1 + d * d - L2 * L2) / (2 * L1 * d)));
  const A = Math.acos(cosA);
  // In screen coordinates (y down), a positive bend turns clockwise.
  const upperDir = a1 + bend * A;
  const ex = ox + Math.cos(upperDir) * L1;
  const ey = oy + Math.sin(upperDir) * L1;
  const lowerDir = Math.atan2(ty - ey, tx - ex);
  const rotU = upperDir - restU - parentRot;
  const rotL = lowerDir - restL - (upperDir - restU);
  const deg = (r: number) => {
    let v = (r * 180) / Math.PI;
    while (v > 180) v -= 360;
    while (v < -180) v += 360;
    return Math.round(v * 100) / 100;
  };
  setKey(U, "rotation", frame, deg(rotU), ease);
  setKey(Lw, "rotation", frame, deg(rotL), ease);
  if (frame + 1 > s.frames) s.frames = frame + 1;
  return raw <= L1 + L2;
}

/* --------------------------------- motions --------------------------------- */

export type MotionType = "walk" | "run" | "idle" | "wave" | "jump" | "talk" | "dance" | "point" | "nod" | "cheer" | "sit";

export const MOTIONS: MotionType[] = ["walk", "run", "idle", "wave", "jump", "talk", "dance", "point", "nod", "cheer", "sit"];

export interface MotionOptions {
  start?: number;
  cycles?: number;
  /** Frames per cycle (defaults depend on the motion and the scene fps). */
  frames?: number;
  /** Walk/run: travel this many pixels per cycle (0 = in place). */
  travel?: number;
  /** Scale angles (0.5 = subtle, 1.5 = exaggerated). */
  energy?: number;
}

/** Rig height in px, measured from the rest pose. */
function rigHeight(parts: Map<Part, AnimLayer>): number {
  const head = parts.get("head");
  const foot = parts.get("footF");
  if (!head?.tip || !foot?.pivot) return 600;
  return Math.max(100, foot.pivot.y - head.tip.y + 40);
}

/**
 * Generate a motion as per-part keyframes. Angles: negative swings a limb forward (toward +x for a
 * side-view rig facing right); positive bends knees back. Returns the number of keys written.
 */
export function motion(s: AnimScene, rig: string | undefined, type: MotionType, o: MotionOptions = {}): { keys: number; endFrame: number } {
  const parts = rigLayers(s, rig);
  const H = rigHeight(parts);
  const e = o.energy ?? 1;
  const start = Math.max(0, Math.round(o.start ?? 0));
  const cycles = Math.max(1, Math.round(o.cycles ?? (type === "jump" || type === "point" || type === "sit" ? 1 : 2)));
  const fps = s.fps;
  const per = Math.max(4, Math.round(o.frames ?? { walk: fps, run: fps * 0.6, idle: fps * 2, wave: fps * 0.6, jump: fps * 1.4, talk: fps * 1.2, dance: fps, point: fps, nod: fps * 0.8, cheer: fps * 0.8, sit: fps }[type]));
  const steps = type === "jump" ? 7 : 8;
  const hips = parts.get("hips");
  const baseX = hips ? valueAt(hips.transform.x, start, 0) : 0;
  const baseY = hips ? valueAt(hips.transform.y, start, 0) : 0;
  let keys = 0;
  const ease: Ease = type === "jump" ? "inOut" : "linear";
  const end = start + per * cycles;
  for (const l of parts.values()) clearKeys(l, start, end);
  const put = (frame: number, pose: Pose, ez: Ease = ease) => {
    const scaled: Pose = {};
    for (const [k, v] of Object.entries(pose)) {
      if (v === undefined) continue;
      if (k === "rootX") scaled.rootX = baseX + v;
      else if (k === "rootY") scaled.rootY = baseY + v * e;
      else scaled[k as Part] = v * e;
    }
    keys += applyPose(s, rig, Math.round(frame), scaled, ez);
  };
  const sin = (p: number) => Math.sin(p * Math.PI * 2);
  const cos = (p: number) => Math.cos(p * Math.PI * 2);
  const pos = (v: number) => Math.max(0, v);
  for (let c = 0; c < cycles; c++) {
    for (let i = 0; i <= steps; i++) {
      if (c > 0 && i === 0) continue;
      const p = i / steps;
      const f = start + c * per + p * per;
      const travel = (o.travel ?? 0) * (c + p);
      switch (type) {
        case "walk": {
          const th = (q: number) => -22 * cos(q);
          const sh = (q: number) => 6 + 42 * pos(-sin(q));
          const tF = th(p);
          const tB = th(p + 0.5);
          const sF = sh(p);
          const sB = sh(p + 0.5);
          put(f, {
            thighF: tF, shinF: sF, footF: -(tF + sF) * 0.7,
            thighB: tB, shinB: sB, footB: -(tB + sB) * 0.7,
            upperArmF: -tF * 0.9, foreArmF: -12 - 10 * pos(tF / 22), handF: 5,
            upperArmB: -tB * 0.9, foreArmB: -12 - 10 * pos(tB / 22), handB: 5,
            torso: 4, head: -3, hips: 0,
            rootY: 0.018 * H * cos(2 * (p - 0.125)), rootX: travel,
          });
          break;
        }
        case "run": {
          const th = (q: number) => -42 * cos(q);
          const sh = (q: number) => 12 + 80 * pos(-sin(q));
          const tF = th(p);
          const tB = th(p + 0.5);
          put(f, {
            thighF: tF, shinF: sh(p), footF: -(tF + sh(p)) * 0.6,
            thighB: tB, shinB: sh(p + 0.5), footB: -(tB + sh(p + 0.5)) * 0.6,
            upperArmF: -tF * 1.1, foreArmF: -85, handF: 0,
            upperArmB: -tB * 1.1, foreArmB: -85, handB: 0,
            torso: 12, head: -8, hips: 0,
            rootY: 0.04 * H * cos(2 * (p - 0.15)), rootX: travel,
          });
          break;
        }
        case "idle":
          put(f, { torso: 1.5 * sin(p), head: -1.5 * sin(p + 0.1), upperArmF: 3 * sin(p + 0.2), upperArmB: -3 * sin(p + 0.2), foreArmF: -6, foreArmB: -6, thighF: -4, thighB: 4, shinF: 3, shinB: 3, rootY: 0.004 * H * sin(p) }, "inOut");
          break;
        case "wave":
          put(f, { upperArmF: -150, foreArmF: i % 2 === 0 ? -25 : 25, handF: i % 2 === 0 ? -15 : 15, upperArmB: 6, foreArmB: -10, head: 4 * sin(p), torso: -2, thighF: -3, thighB: 3 }, "inOut");
          break;
        case "talk":
          put(f, { head: i % 2 ? 3 : -4, torso: 1.5 * sin(p), upperArmF: -25 - 10 * sin(p), foreArmF: -55 + 25 * sin(p * 2), handF: 10 * sin(p * 2), upperArmB: 5, foreArmB: -15 }, "inOut");
          break;
        case "nod":
          put(f, { head: i % 2 ? 12 : -4, torso: i % 2 ? 2 : 0 }, "inOut");
          break;
        case "dance": {
          const up = i % 2 === 0;
          put(f, {
            rootX: 0.03 * H * sin(p) + travel, rootY: up ? 0 : 0.03 * H,
            hips: 6 * sin(p), torso: -8 * sin(p), head: 6 * sin(p + 0.25),
            upperArmF: up ? -150 : -60, foreArmF: up ? -20 : -70, upperArmB: up ? -60 : -150, foreArmB: up ? -70 : -20,
            thighF: up ? -5 : -25, shinF: up ? 5 : 45, footF: up ? 0 : -20, thighB: up ? 5 : -15, shinB: up ? 5 : 35, footB: up ? 0 : -15,
          }, "inOut");
          break;
        }
        case "cheer":
          put(f, { upperArmF: i % 2 ? -165 : -140, upperArmB: i % 2 ? -140 : -165, foreArmF: -10, foreArmB: -10, head: -8, torso: -3, rootY: i % 2 ? -0.03 * H : 0, thighF: -3, thighB: 3 }, "inOut");
          break;
        case "point":
          put(f, i < 3 ? { upperArmF: -40 * (i / 2), foreArmF: -10 } : { upperArmF: -88, foreArmF: -2, handF: 0, head: -4, torso: 3 }, "inOut");
          break;
        case "sit": {
          const k = Math.min(1, i / (steps * 0.6));
          put(f, { rootY: 0.2 * H * k, thighF: -85 * k, thighB: -80 * k, shinF: 88 * k, shinB: 85 * k, footF: -3 * k, footB: -5 * k, torso: 4 * k, upperArmF: -25 * k, foreArmF: -40 * k, upperArmB: -20 * k, foreArmB: -35 * k }, "inOut");
          break;
        }
        case "jump": {
          const P: Pose[] = [
            { rootY: 0, thighF: 0, shinF: 0, footF: 0, thighB: 0, shinB: 0, footB: 0, upperArmF: 0, upperArmB: 0, torso: 0 },
            { rootY: 0.09 * H, thighF: -45, shinF: 80, footF: -35, thighB: -40, shinB: 75, footB: -35, upperArmF: 45, upperArmB: 40, foreArmF: -15, foreArmB: -15, torso: 18, head: -10 },
            { rootY: -0.12 * H, thighF: 5, shinF: 5, footF: 25, thighB: 10, shinB: 5, footB: 25, upperArmF: -165, upperArmB: -150, foreArmF: -5, foreArmB: -5, torso: -4, head: -6 },
            { rootY: -0.26 * H, thighF: -30, shinF: 55, footF: 10, thighB: -20, shinB: 45, footB: 10, upperArmF: -130, upperArmB: -120, torso: 2 },
            { rootY: -0.1 * H, thighF: -10, shinF: 15, footF: 10, thighB: -5, shinB: 12, footB: 10, upperArmF: -60, upperArmB: -50, torso: 0 },
            { rootY: 0.1 * H, thighF: -50, shinF: 90, footF: -40, thighB: -45, shinB: 85, footB: -40, upperArmF: -40, upperArmB: -30, foreArmF: -30, torso: 22, head: -12 },
            { rootY: 0.02 * H, thighF: -8, shinF: 12, footF: -4, thighB: -6, shinB: 10, footB: -4, upperArmF: 5, upperArmB: 5, torso: 3 },
            { rootY: 0, thighF: 0, shinF: 0, footF: 0, thighB: 0, shinB: 0, footB: 0, upperArmF: 0, upperArmB: 0, foreArmF: 0, foreArmB: 0, torso: 0, head: 0 },
          ];
          const timing = [0, 0.18, 0.3, 0.48, 0.62, 0.74, 0.86, 1];
          put(start + c * per + timing[i]! * per, { ...P[i]!, rootX: (o.travel ?? 0) * (c + timing[i]!) }, i === 2 || i === 4 ? "out" : i === 3 ? "in" : "inOut");
          break;
        }
      }
    }
  }
  if (end + 1 > s.frames) s.frames = end + 1;
  return { keys, endFrame: end };
}

/** Joint and bone positions in scene px at a frame (for the Pose overlay and agents). */
export function skeleton(s: AnimScene, rig?: string, frame = 0): { part: Part; layerId: string; pivot: [number, number]; tip: [number, number]; rotation: number }[] {
  const parts = rigLayers(s, rig);
  const out: { part: Part; layerId: string; pivot: [number, number]; tip: [number, number]; rotation: number }[] = [];
  for (const [name, l] of parts) {
    if (!l.pivot || !l.tip) continue;
    const W = worldMatrix(s, l, frame);
    const p = apply(W, l.pivot.x, l.pivot.y);
    const t = apply(W, l.tip.x, l.tip.y);
    out.push({ part: name, layerId: l.id, pivot: [Math.round(p[0]), Math.round(p[1])], tip: [Math.round(t[0]), Math.round(t[1])], rotation: Math.round(tv(l, "rotation", frame) * 10) / 10 });
  }
  return out;
}
