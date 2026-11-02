/**
 * Procedural anime characters ("puppets") and painted backdrops for the animation workstation.
 *
 * A puppet layer stores a small spec (look, outfit, props) plus animation channels (pose, face, smoke) keyed
 * by frame. Every frame is drawn from curves: limbs are continuous tapered tubes through the joints (no seams at
 * elbows or knees), everything is cel shaded with a key light, line weight varies, hands reach targets by IK,
 * hair and jacket follow through, eyes blink on their own, and smoke is a particle system. This is what lets
 * an agent describe a whole shot in a few hundred bytes instead of tens of thousands of hand strokes.
 */
import { valueAt } from "../core/keyframes";
import type { Animatable, AnimLayer, AnimScene, Ease } from "../core/model";

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;
interface V {
  x: number;
  y: number;
}

/* ---------------------------------- spec ---------------------------------- */

export interface PuppetPalette {
  skin: string;
  skinShade: string;
  skinLine: string;
  hair: string;
  hairShade: string;
  hairHi: string;
  eyes: string;
  jacket: string;
  jacketShade: string;
  jacketHi: string;
  shirt: string;
  shirtShade: string;
  pants: string;
  pantsShade: string;
  boots: string;
  line: string;
  rim: string;
  metal: string;
  tie: string;
}

export interface PuppetSpec {
  /** Ground point between the feet, scene px. */
  x: number;
  y: number;
  /** Sole to top of head, px. */
  height: number;
  hair: "messy" | "short" | "long" | "spiky";
  jacket: "leather" | "hoodie" | "coat" | "none";
  shirt: "turtleneck" | "tee" | "shirt";
  chain: boolean;
  props: string[];
  /** Which hand holds props: R (screen right) or L. */
  propHand: "R" | "L";
  /** 1 faces screen-right (default), -1 faces screen-left (the whole drawing mirrors). */
  facing?: 1 | -1;
  /** Elemental aura drawn when the "aura" channel is up. */
  element?: "fire" | "ice" | "none";
  colors: PuppetPalette;
  seed: number;
}

export const PALETTES: Record<string, Partial<PuppetPalette>> = {
  black: {},
  white: { jacket: "#e9e8ee", jacketShade: "#b9b7c8", jacketHi: "#ffffff", shirt: "#2b2b33", shirtShade: "#1d1d24" },
  red: { jacket: "#7d1622", jacketShade: "#4d0c14", jacketHi: "#c4404e" },
  navy: { jacket: "#1e2a4a", jacketShade: "#121a30", jacketHi: "#3f5590" },
  brown: { jacket: "#5a3a26", jacketShade: "#3a2416", jacketHi: "#94643f" },
  suit: { jacket: "#18181d", jacketShade: "#0b0b0e", jacketHi: "#4d5163", shirt: "#ecebf2", shirtShade: "#b8b7c9", pants: "#16161b", pantsShade: "#0c0c10" },
};

export const DEFAULT_PALETTE: PuppetPalette = {
  skin: "#f7e2d4",
  skinShade: "#e2b2a6",
  skinLine: "#7b4640",
  hair: "#1b1b25",
  hairShade: "#0c0c13",
  hairHi: "#56648f",
  eyes: "#d12c43",
  jacket: "#26272f",
  jacketShade: "#15151b",
  jacketHi: "#5a5f78",
  shirt: "#34343f",
  shirtShade: "#22222b",
  pants: "#1f1f27",
  pantsShade: "#121218",
  boots: "#121216",
  line: "#07070a",
  rim: "#a497ff",
  metal: "#c9ccd6",
  tie: "#b3121f",
};

export function makeSpec(o: Partial<PuppetSpec> & { palette?: string; colorOverrides?: Partial<PuppetPalette> } = {}): PuppetSpec {
  const extra: Partial<PuppetSpec> = {};
  if (o.facing === -1) extra.facing = -1;
  if (o.element && o.element !== "none") extra.element = o.element;
  const colors: PuppetPalette = { ...DEFAULT_PALETTE, ...(PALETTES[o.palette ?? "black"] ?? {}), ...(o.colors ?? {}), ...(o.colorOverrides ?? {}) };
  return {
    x: o.x ?? 540,
    y: o.y ?? 1700,
    height: o.height ?? 1250,
    hair: o.hair ?? "messy",
    jacket: o.jacket ?? "leather",
    shirt: o.shirt ?? "turtleneck",
    chain: o.chain ?? true,
    props: o.props ?? [],
    propHand: o.propHand ?? "R",
    ...extra,
    colors,
    seed: o.seed ?? 7,
  };
}

/* -------------------------------- channels -------------------------------- */

/**
 * Animation channels and their rest values. Hand targets are relative to the shoulder in torso space, in head
 * units (1 u = height / 7.6). Feet are relative to the hip joints. Face values are 0..1 unless noted.
 */
export const CHANNELS: Record<string, number> = {
  rootX: 0, // u
  rootY: 0, // u (positive = down / crouch)
  lean: 0, // deg, torso (positive = clockwise)
  hipTilt: 0, // deg
  headTilt: 0, // deg
  headNod: 0, // -1 look down .. 1 look up
  headTurn: 0, // -1 .. 1 (three-quarter)
  shrugL: 0,
  shrugR: 0,
  handLx: -0.22,
  handLy: 2.5,
  handLrot: 0,
  handLpose: 0, // 0 relaxed, 1 open, 2 pinch, 3 fist, 4 pocket, 5 point
  handRx: 0.22,
  handRy: 2.5,
  handRrot: 0,
  handRpose: 0,
  footLx: -0.06,
  footLy: 3.55,
  footRx: 0.06,
  footRy: 3.55,
  blink: -1, // -1 = automatic blinking; otherwise 0 open .. 1 closed
  lid: 0.12, // resting upper lid (0.4 = cool half-lidded)
  browL: 0, // -1 angry .. 1 raised
  browR: 0,
  lookX: 0,
  lookY: 0,
  mouthOpen: 0,
  mouthSmile: 0, // -1 .. 1
  mouthSmirk: 0, // -1 .. 1 (one corner)
  mouthRound: 0, // 0 .. 1 ("o")
  blush: 0,
  cigToMouth: 0, // 0 .. 1 cigarette filter turns to the lips
  drag: 0, // ember brightness boost while inhaling
  exhale: 0, // smoke from the mouth
  breath: 1, // breathing amplitude
  wind: 0.3,
  smearL: 0, // motion smear on the left fist (0..1)
  smearR: 0,
  smearK: 0, // smear on the kicking (right) foot
  bodyTurn: 0, // 0 square to camera, 1 three-quarter view toward screen-right (fighting stance)
  kneeDir: 0, // 0 front-facing knees, 1 knees point toward the opponent (fighting stance)
  rootRot: 0, // deg, whole body around the hips (knockdowns, flips)
  aura: 0, // 0..1 elemental aura (spec.element)
  trail: 1, // afterimages on fast moves (0 = off)
};

export type Channels = Record<string, Animatable>;

export function chan(l: AnimLayer, name: string, frame: number): number {
  return valueAt(l.chan?.[name], frame, CHANNELS[name] ?? 0);
}

export function setChannelKey(l: AnimLayer, name: string, frame: number, v: number, kind: Ease = "inOut"): void {
  if (!(name in CHANNELS)) throw new Error(`Unknown channel "${name}". Channels: ${Object.keys(CHANNELS).join(", ")}`);
  l.chan ??= {};
  const cur = l.chan[name];
  const list = typeof cur === "number" ? [{ t: 0, v: cur, ease: kind }] : Array.isArray(cur) ? cur.map((k) => ({ ...k })) : [];
  const i = list.findIndex((k) => Math.abs(k.t - frame) < 1e-4);
  if (i >= 0) list[i] = { t: frame, v, ease: kind };
  else list.push({ t: frame, v, ease: kind });
  list.sort((a, b) => a.t - b.t);
  l.chan[name] = list;
}

/* --------------------------------- math ----------------------------------- */

const D2R = Math.PI / 180;
const v = (x: number, y: number): V => ({ x, y });
const add = (a: V, b: V): V => ({ x: a.x + b.x, y: a.y + b.y });
const sub = (a: V, b: V): V => ({ x: a.x - b.x, y: a.y - b.y });
const mul = (a: V, k: number): V => ({ x: a.x * k, y: a.y * k });
const len = (a: V) => Math.hypot(a.x, a.y);
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
const lerpV = (a: V, b: V, t: number): V => ({ x: lerp(a.x, b.x, t), y: lerp(a.y, b.y, t) });
const clamp = (x: number, a: number, b: number) => Math.max(a, Math.min(b, x));
function rot(p: V, deg: number): V {
  const c = Math.cos(deg * D2R);
  const s = Math.sin(deg * D2R);
  return { x: p.x * c - p.y * s, y: p.x * s + p.y * c };
}
const angleOf = (a: V) => Math.atan2(a.y, a.x) / D2R;
function hash(n: number): number {
  const s = Math.sin(n * 127.1 + 311.7) * 43758.5453;
  return s - Math.floor(s);
}
function noise1(x: number, seed = 0): number {
  const i = Math.floor(x);
  const f = x - i;
  const u = f * f * (3 - 2 * f);
  return lerp(hash(i + seed * 57), hash(i + 1 + seed * 57), u) * 2 - 1;
}

/** Two-bone IK. Picks the elbow/knee solution that points along `pref`. */
function ik(a: V, target: V, l1: number, l2: number, pref: V): { mid: V; end: V } {
  const d0 = sub(target, a);
  let d = len(d0);
  const maxR = (l1 + l2) * 0.999;
  const minR = Math.abs(l1 - l2) + 1e-3;
  const dir = d > 1e-6 ? mul(d0, 1 / d) : v(0, 1);
  d = clamp(d, minR, maxR);
  const end = add(a, mul(dir, d));
  const cosA = clamp((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d), -1, 1);
  const ang = Math.acos(cosA) / D2R;
  const m1 = add(a, mul(rot(dir, ang), l1));
  const m2 = add(a, mul(rot(dir, -ang), l1));
  const mid = lerpV(a, end, 0.5);
  const s1 = (m1.x - mid.x) * pref.x + (m1.y - mid.y) * pref.y;
  const s2 = (m2.x - mid.x) * pref.x + (m2.y - mid.y) * pref.y;
  return { mid: s1 >= s2 ? m1 : m2, end };
}

/* -------------------------------- skeleton -------------------------------- */

export interface Skeleton {
  u: number;
  pelvis: V;
  neck: V;
  torsoA: number;
  headA: number;
  head: V; // head origin (neck top) for drawing
  shoulderL: V;
  shoulderR: V;
  elbowL: V;
  elbowR: V;
  wristL: V;
  wristR: V;
  handLA: number;
  handRA: number;
  hipL: V;
  hipR: V;
  kneeL: V;
  kneeR: V;
  ankleL: V;
  ankleR: V;
  /** Three-quarter body turn 0..1 (torso narrows, far side recedes). */
  bt: number;
  /** Face feature offset for head turn and nod (head-local u). */
  turn: number;
  nod: number;
  mouth: V; // world
  grip: V; // world, where the prop hand holds things
  ember: V; // world, burning tip of the cigarette (if any)
  filter: V;
}

const UPPER_ARM = 1.36;
const FORE_ARM = 1.24;
const THIGH = 1.84;
const SHIN = 1.74;
const PELVIS_H = 3.82;

/** Torso-local x under a three-quarter turn: the far (screen-right) side recedes more, the chest shifts toward the facing. */
export function turnX(x: number, bt: number): number {
  return (x > 0 ? x * (1 - 0.48 * bt) : x * (1 - 0.18 * bt)) + 0.1 * bt;
}

function breathAt(l: AnimLayer, f: number, fps: number): number {
  return Math.sin(((f / fps) * 2 * Math.PI) / 3.8) * 0.022 * chan(l, "breath", f);
}

/** Head drawing scale relative to the body (anime heads read a little larger). */
export const HS = 1.12;
/** Total figure height in head units (sole to top of hair). */
const FIG = 7.75;

export function skeleton(l: AnimLayer, spec: PuppetSpec, f: number, fps: number): Skeleton {
  const u = spec.height / FIG;
  const c = (n: string) => chan(l, n, f);
  const br = breathAt(l, f, fps);
  const ground = v(spec.x + c("rootX") * u, spec.y);
  const pelvis = v(ground.x, ground.y - PELVIS_H * u + c("rootY") * u);
  const torsoA = c("lean") + c("hipTilt") * 0.35;
  const T = (p: V) => add(pelvis, rot(mul(p, u), torsoA));
  const bt = clamp(c("bodyTurn"), 0, 1);
  const neck = T(v(turnX(0, bt), -2.1 - br * 0.6));
  const shoulderL = T(v(turnX(-0.86, bt), -1.92 - br - c("shrugL") * 0.25));
  const shoulderR = T(v(turnX(0.86, bt), -1.92 - br - c("shrugR") * 0.25 - 0.06 * bt));
  const headA = torsoA + c("headTilt");
  const turn = c("headTurn") + 0.45 * bt;
  const nod = c("headNod");
  const H = (p: V) => add(neck, rot(mul(p, u * HS), headA));
  const mouth = H(v(turn * 0.1, -0.5 - nod * 0.04));
  const ph = spec.propHand;
  const k = spec.props.includes("cigarette") ? clamp(c("cigToMouth"), 0, 1) : 0;
  const out = (side: "L" | "R") => (side === "L" ? -1 : 1);
  const gripOff = (side: "L" | "R") => v(-out(side) * 0.05 * u, 0.4 * u);
  const fd0 = norm(v(-out(ph) * 0.62, -0.78)); // grip → lips when the cigarette is at the mouth
  const hand = (side: "L" | "R", sh: V) => {
    let t = add(sh, rot(v(c(`hand${side}x`) * u, c(`hand${side}y`) * u), torsoA));
    const pref = rot(v(out(side) * 0.75, 0.6), torsoA);
    let r = ik(sh, t, UPPER_ARM * u, FORE_ARM * u, pref);
    if (side === ph && k > 0) {
      // Bring the filter exactly to the lips: solve for the wrist that puts grip + filter at the mouth.
      const grip = sub(mouth, mul(fd0, 0.14 * u));
      for (let i = 0; i < 3; i++) {
        const ha = angleOf(sub(r.end, r.mid)) - 90 + c(`hand${side}rot`);
        const want = sub(grip, rot(gripOff(side), ha));
        const e = k * k * (3 - 2 * k);
        r = ik(sh, lerpV(t, want, e), UPPER_ARM * u, FORE_ARM * u, pref);
      }
      t = r.end;
    }
    return r;
  };
  const aL = hand("L", shoulderL);
  const aR = hand("R", shoulderR);
  const hipA = c("hipTilt");
  const hipL = add(pelvis, rot(v(turnX(-0.4, bt) * u, 0.12 * u), hipA));
  const hipR = add(pelvis, rot(v(turnX(0.4, bt) * u, 0.12 * u), hipA));
  // Feet are planted relative to the ground under each hip (not the tilted pelvis).
  const footL = v(spec.x + c("rootX") * u - 0.4 * u + c("footLx") * u, pelvis.y + 0.12 * u + c("footLy") * u - c("rootY") * u);
  const footR = v(spec.x + c("rootX") * u + 0.4 * u + c("footRx") * u, pelvis.y + 0.12 * u + c("footRy") * u - c("rootY") * u);
  const kd = Math.max(0, Math.min(1, c("kneeDir")));
  const lL = ik(hipL, footL, THIGH * u, SHIN * u, lerpV(v(-0.04, -1), v(1, -0.25), kd));
  const lR = ik(hipR, footR, THIGH * u, SHIN * u, lerpV(v(0.04, -1), v(1, -0.25), kd));
  const handLA = angleOf(sub(aL.end, aL.mid)) - 90 + c("handLrot");
  const handRA = angleOf(sub(aR.end, aR.mid)) - 90 + c("handRrot");
  const wrist = ph === "R" ? aR.end : aL.end;
  const ha = ph === "R" ? handRA : handLA;
  const grip = add(wrist, rot(gripOff(ph), ha));
  // Cigarette rests across the fingers and turns so the filter meets the lips when raised.
  const restDir = rot(v(out(ph), 0.2), ha);
  const fd = norm(lerpV(mul(restDir, -1), fd0, k));
  const filter = add(grip, mul(fd, 0.14 * u));
  const ember = sub(grip, mul(fd, 0.52 * u));
  return {
    u,
    pelvis,
    neck,
    torsoA,
    headA,
    head: neck,
    shoulderL,
    shoulderR,
    elbowL: aL.mid,
    elbowR: aR.mid,
    wristL: aL.end,
    wristR: aR.end,
    handLA,
    handRA,
    hipL,
    hipR,
    kneeL: lL.mid,
    kneeR: lR.mid,
    ankleL: lL.end,
    ankleR: lR.end,
    bt,
    turn,
    nod,
    mouth,
    grip,
    ember,
    filter,
  };
}

/* ------------------------------- drawing kit ------------------------------- */

interface Kit {
  c: Ctx;
  u: number;
  lw: number; // outer line width
  pal: PuppetPalette;
  light: V; // unit vector toward the key light (screen space)
}

/** Smooth closed path through points (Catmull-Rom → Bézier). */
function smoothClosed(pts: V[], tension = 0.5): Path2D {
  const p = new Path2D();
  const n = pts.length;
  if (n < 3) return p;
  p.moveTo(pts[0]!.x, pts[0]!.y);
  for (let i = 0; i < n; i++) {
    const p0 = pts[(i - 1 + n) % n]!;
    const p1 = pts[i]!;
    const p2 = pts[(i + 1) % n]!;
    const p3 = pts[(i + 2) % n]!;
    const t = tension / 3;
    p.bezierCurveTo(p1.x + (p2.x - p0.x) * t, p1.y + (p2.y - p0.y) * t, p2.x - (p3.x - p1.x) * t, p2.y - (p3.y - p1.y) * t, p2.x, p2.y);
  }
  p.closePath();
  return p;
}

/** Path from a list of points where a point marked `sharp` is a corner. */
function shape(pts: (V & { s?: boolean })[], tension = 0.5): Path2D {
  // Duplicate sharp points so the spline passes through them with a corner.
  const out: V[] = [];
  for (const q of pts) {
    out.push(q);
    if (q.s) out.push({ x: q.x + 0.01, y: q.y + 0.01 });
  }
  return smoothClosed(out, tension);
}

function smoothOpen(pts: V[], tension = 0.5): Path2D {
  const p = new Path2D();
  if (pts.length < 2) return p;
  p.moveTo(pts[0]!.x, pts[0]!.y);
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)]!;
    const p1 = pts[i]!;
    const p2 = pts[i + 1]!;
    const p3 = pts[Math.min(pts.length - 1, i + 2)]!;
    const t = tension / 3;
    p.bezierCurveTo(p1.x + (p2.x - p0.x) * t, p1.y + (p2.y - p0.y) * t, p2.x - (p3.x - p1.x) * t, p2.y - (p3.y - p1.y) * t, p2.x, p2.y);
  }
  return p;
}

/** Tapered ink line: filled ribbon whose width follows `w(t)`. */
function inkLine(k: Kit, pts: V[], width: number, color: string, taper: [number, number] = [0.25, 0.25]): void {
  if (pts.length < 2) return;
  // Resample along a Catmull-Rom spline.
  const samp: V[] = [];
  const N = Math.max(8, pts.length * 6);
  for (let i = 0; i <= N; i++) {
    const t = (i / N) * (pts.length - 1);
    const j = Math.min(pts.length - 2, Math.floor(t));
    const f = t - j;
    const p0 = pts[Math.max(0, j - 1)]!;
    const p1 = pts[j]!;
    const p2 = pts[j + 1]!;
    const p3 = pts[Math.min(pts.length - 1, j + 2)]!;
    const f2 = f * f;
    const f3 = f2 * f;
    samp.push({
      x: 0.5 * (2 * p1.x + (-p0.x + p2.x) * f + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * f2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * f3),
      y: 0.5 * (2 * p1.y + (-p0.y + p2.y) * f + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * f2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * f3),
    });
  }
  const L: V[] = [];
  const R: V[] = [];
  for (let i = 0; i < samp.length; i++) {
    const a = samp[Math.max(0, i - 1)]!;
    const b = samp[Math.min(samp.length - 1, i + 1)]!;
    let d = sub(b, a);
    d = mul(d, 1 / (len(d) || 1));
    const t = i / (samp.length - 1);
    const tw = Math.min(1, taper[0] > 0 ? t / taper[0] : 1, taper[1] > 0 ? (1 - t) / taper[1] : 1);
    const w = (width / 2) * (0.15 + 0.85 * Math.sqrt(Math.max(0, tw)));
    L.push(add(samp[i]!, v(-d.y * w, d.x * w)));
    R.push(add(samp[i]!, v(d.y * w, -d.x * w)));
  }
  const p = new Path2D();
  p.moveTo(L[0]!.x, L[0]!.y);
  for (const q of L) p.lineTo(q.x, q.y);
  for (let i = R.length - 1; i >= 0; i--) p.lineTo(R[i]!.x, R[i]!.y);
  p.closePath();
  k.c.fillStyle = color;
  k.c.fill(p);
}

function shifted(p: Path2D, dx: number, dy: number): Path2D {
  const q = new Path2D();
  q.addPath(p, new DOMMatrix([1, 0, 0, 1, dx, dy]));
  return q;
}

/**
 * Cel-shaded fill: base colour, a hard shadow band on the side away from the light, an optional highlight band
 * on the lit edge, then the outline.
 */
function cel(k: Kit, p: Path2D, base: string, shadow: string, opts: { sh?: number; hi?: string; hiW?: number; line?: string; lw?: number; rim?: boolean } = {}): void {
  const c = k.c;
  const sh = (opts.sh ?? 0.16) * k.u;
  c.save();
  c.fillStyle = base;
  c.fill(p);
  c.clip(p);
  // Shadow = shape minus shape pushed toward the light.
  const s = new Path2D();
  s.addPath(p);
  s.addPath(shifted(p, k.light.x * sh, k.light.y * sh));
  c.fillStyle = shadow;
  c.fill(s, "evenodd");
  if (opts.hi) {
    const hw = (opts.hiW ?? 0.06) * k.u;
    const h = new Path2D();
    h.addPath(p);
    h.addPath(shifted(p, -k.light.x * hw, -k.light.y * hw));
    c.fillStyle = opts.hi;
    c.fill(h, "evenodd");
  }
  if (opts.rim !== false) {
    // Soft cool rim from the moon on the far side.
    const rw = 0.035 * k.u;
    const r = new Path2D();
    r.addPath(p);
    r.addPath(shifted(p, k.light.x * rw * -1 * -1 + rw, -rw * 0.2));
    c.globalAlpha = 0.18;
    c.fillStyle = k.pal.rim;
    c.fill(r, "evenodd");
    c.globalAlpha = 1;
  }
  c.restore();
  if (opts.line !== "none") {
    c.lineWidth = opts.lw ?? k.lw;
    c.lineJoin = "round";
    c.lineCap = "round";
    c.strokeStyle = opts.line ?? k.pal.line;
    c.stroke(p);
  }
}

/** Continuous tapered tube through joints (sleeves, legs). */
function tube(pts: V[], widths: number[], capStart = true, capEnd = true): Path2D {
  const N = 28;
  const samp: { p: V; w: number }[] = [];
  for (let i = 0; i <= N; i++) {
    const t = (i / N) * (pts.length - 1);
    const j = Math.min(pts.length - 2, Math.floor(t));
    const f = t - j;
    // Quadratic-ish blend for a rounded bend at the joint.
    const p0 = pts[Math.max(0, j - 1)]!;
    const p1 = pts[j]!;
    const p2 = pts[j + 1]!;
    const p3 = pts[Math.min(pts.length - 1, j + 2)]!;
    const f2 = f * f;
    const f3 = f2 * f;
    const p = {
      x: 0.5 * (2 * p1.x + (-p0.x + p2.x) * f + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * f2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * f3),
      y: 0.5 * (2 * p1.y + (-p0.y + p2.y) * f + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * f2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * f3),
    };
    samp.push({ p, w: lerp(widths[j]!, widths[j + 1]!, f) });
  }
  const L: V[] = [];
  const R: V[] = [];
  for (let i = 0; i < samp.length; i++) {
    const a = samp[Math.max(0, i - 1)]!.p;
    const b = samp[Math.min(samp.length - 1, i + 1)]!.p;
    let d = sub(b, a);
    d = mul(d, 1 / (len(d) || 1));
    const w = samp[i]!.w / 2;
    L.push(add(samp[i]!.p, v(-d.y * w, d.x * w)));
    R.push(add(samp[i]!.p, v(d.y * w, -d.x * w)));
  }
  const p = new Path2D();
  p.moveTo(L[0]!.x, L[0]!.y);
  for (let i = 1; i < L.length; i++) p.lineTo(L[i]!.x, L[i]!.y);
  const e = samp[samp.length - 1]!;
  if (capEnd) {
    const a0 = angleOf(sub(L[L.length - 1]!, e.p)) * D2R;
    p.arc(e.p.x, e.p.y, e.w / 2, a0, a0 - Math.PI, true);
  } else p.lineTo(R[R.length - 1]!.x, R[R.length - 1]!.y);
  for (let i = R.length - 2; i >= 0; i--) p.lineTo(R[i]!.x, R[i]!.y);
  const s0 = samp[0]!;
  if (capStart) {
    const a0 = angleOf(sub(R[0]!, s0.p)) * D2R;
    p.arc(s0.p.x, s0.p.y, s0.w / 2, a0, a0 - Math.PI, true);
  }
  p.closePath();
  return p;
}

/* ------------------------------- the puppet -------------------------------- */

export function drawPuppet(c: Ctx, l: AnimLayer, s: AnimScene, f: number): void {
  const spec = l.puppet;
  if (!spec) return;
  const ch = (n: string) => chan(l, n, f);
  const sk0 = skeleton(l, spec, f, s.fps);
  const u = sk0.u;
  c.save();
  if (spec.facing === -1) {
    c.translate(spec.x * 2, 0);
    c.scale(-1, 1);
  }
  // Ground shadow (shrinks when airborne).
  const air = Math.max(0, -ch("rootY"));
  c.save();
  const gs = c.createRadialGradient(spec.x, spec.y, 0, spec.x, spec.y, 1.9 * u);
  gs.addColorStop(0, `rgba(0,0,0,${0.45 / (1 + air * 0.5)})`);
  gs.addColorStop(1, "rgba(0,0,0,0)");
  c.fillStyle = gs;
  c.setTransform(c.getTransform().multiply(new DOMMatrix([1, 0, 0, 0.18, 0, spec.y * (1 - 0.18)])));
  c.beginPath();
  c.arc(spec.x + ch("rootX") * u, spec.y, 1.9 * u, 0, Math.PI * 2);
  c.fill();
  c.restore();
  const aura = ch("aura");
  if (aura > 0.02 && spec.element) drawAura(c, sk0, spec, aura, f / s.fps, false);
  // Afterimages on fast moves: earlier poses, fading.
  if (ch("trail") > 0) {
    const back = skeleton(l, spec, Math.max(0, f - 2), s.fps);
    const speed = Math.hypot(sk0.pelvis.x - back.pelvis.x, sk0.pelvis.y - back.pelvis.y) / u;
    if (speed > 0.45) {
      for (const g of [6, 4, 2]) {
        c.save();
        c.globalAlpha = Math.min(0.35, (speed - 0.35) * 0.3) * (1 - g / 8);
        drawBody(c, l, s, Math.max(0, f - g));
        c.restore();
      }
    }
  }
  drawBody(c, l, s, f);
  if (aura > 0.02 && spec.element) drawAura(c, sk0, spec, aura, f / s.fps, true);
  c.restore();
  drawSmoke(c, l, spec, s, f);
}

/** The character itself (no shadow, aura or smoke) at frame f; rootRot spins it around the hips. */
function drawBody(c: Ctx, l: AnimLayer, s: AnimScene, f: number): void {
  const spec = l.puppet!;
  const sk = skeleton(l, spec, f, s.fps);
  lastSk = sk;
  const u = sk.u;
  const k: Kit = { c, u, lw: Math.max(1.2, 0.036 * u), pal: spec.colors, light: norm(v(0.75, -0.66)) };
  const ch = (n: string) => chan(l, n, f);
  const prev = skeleton(l, spec, Math.max(0, f - 3), s.fps);
  const swing = {
    head: (sk.headA - prev.headA) * 0.9 + noise1(f / s.fps / 1.7, spec.seed) * 2.5 * ch("wind"),
    body: (sk.pelvis.x - prev.pelvis.x) / u,
  };
  const rr = ch("rootRot");
  c.save();
  if (Math.abs(rr) > 0.01) {
    c.translate(sk.pelvis.x, sk.pelvis.y);
    c.rotate((rr * Math.PI) / 180);
    c.translate(-sk.pelvis.x, -sk.pelvis.y);
  }
  drawHairBack(k, sk, spec, swing.head, f / s.fps);
  // Three-quarter view: the far (right) arm sits behind the body.
  const farBehind = sk.bt > 0.3;
  if (farBehind) drawArm(k, l, sk, spec, "R", f);
  drawLegs(k, sk, spec);
  drawTorso(k, sk, spec, swing.body, ch("wind"), f / s.fps);
  drawHead(k, l, sk, spec, f, s.fps, swing.head);
  if (farBehind) drawArm(k, l, sk, spec, "L", f);
  else {
    // Arms: the one crossing the body goes last.
    const crossR = sk.wristR.x < sk.shoulderR.x - 0.6 * u;
    const crossL = sk.wristL.x > sk.shoulderL.x + 0.6 * u;
    const order: ("L" | "R")[] = crossR && !crossL ? ["L", "R"] : crossL && !crossR ? ["R", "L"] : ["L", "R"];
    for (const side of order) drawArm(k, l, sk, spec, side, f);
  }
  drawSmears(c, l, s, f, sk);
  c.restore();
}

/** Anime motion smears: a tapered streak along the path the fist/foot travelled over the last frames. */
function drawSmears(c: Ctx, l: AnimLayer, s: AnimScene, f: number, sk: Skeleton): void {
  const spec = l.puppet!;
  const u = sk.u;
  const parts: [string, (q: Skeleton) => V][] = [
    ["smearL", (q) => q.wristL],
    ["smearR", (q) => q.wristR],
    ["smearK", (q) => q.ankleR],
  ];
  for (const [ch, pick] of parts) {
    const amt = chan(l, ch, f);
    if (amt < 0.05) continue;
    const pts: V[] = [];
    for (let g = 4; g >= 1; g--) pts.push(pick(skeleton(l, spec, Math.max(0, f - g), s.fps)));
    pts.push(pick(sk));
    const len = pts.slice(1).reduce((acc, p, i) => acc + Math.hypot(p.x - pts[i]!.x, p.y - pts[i]!.y), 0);
    if (len < 0.8 * u) continue;
    const col = spec.element === "fire" ? [255, 170, 80] : spec.element === "ice" ? [170, 225, 255] : [255, 255, 255];
    c.save();
    c.lineCap = "round";
    c.lineJoin = "round";
    // Wide soft streak, then a bright core, both tapering toward the oldest point.
    for (const [w, a] of [[0.9, 0.22], [0.5, 0.45], [0.18, 0.9]] as const) {
      for (let i = 1; i < pts.length; i++) {
        const t = i / (pts.length - 1);
        c.strokeStyle = `rgba(${col[0]},${col[1]},${col[2]},${a * amt * t})`;
        c.lineWidth = w * u * (0.25 + 0.75 * t);
        c.beginPath();
        c.moveTo(pts[i - 1]!.x, pts[i - 1]!.y);
        c.lineTo(pts[i]!.x, pts[i]!.y);
        c.stroke();
      }
    }
    // Speed ticks behind the strike.
    const d = norm(sub(pts[pts.length - 1]!, pts[0]!));
    const nrm = v(-d.y, d.x);
    c.strokeStyle = `rgba(255,255,255,${0.7 * amt})`;
    c.lineWidth = 0.04 * u;
    for (let i = -2; i <= 2; i++) {
      const o = add(pts[2]!, mul(nrm, i * 0.22 * u));
      c.beginPath();
      c.moveTo(o.x - d.x * 0.9 * u, o.y - d.y * 0.9 * u);
      c.lineTo(o.x - d.x * 0.2 * u, o.y - d.y * 0.2 * u);
      c.stroke();
    }
    c.restore();
  }
}

/** Elemental aura: fire tongues licking up around the body, or ice shards, frost glow and snow. Front pass adds eye glow. */
function drawAura(c: Ctx, sk: Skeleton, spec: PuppetSpec, a: number, t: number, front: boolean): void {
  const u = sk.u;
  const fire = spec.element === "fire";
  const cx = sk.pelvis.x;
  const top = sk.neck.y - 2.2 * u;
  const bot = spec.y;
  const cy = (top + bot) / 2;
  const ry = (bot - top) / 2 + 0.4 * u;
  const rx = 1.9 * u;
  c.save();
  c.globalCompositeOperation = "lighter";
  if (front) {
    // Glowing eyes.
    for (const sx of [-1, 1]) {
      const e = add(sk.head, rot(v((sx * 0.19 + sk.turn * 0.1) * u * HS, -0.9 * u * HS), sk.headA));
      const g = c.createRadialGradient(e.x, e.y, 0, e.x, e.y, 0.35 * u);
      g.addColorStop(0, fire ? `rgba(255,190,90,${0.9 * a})` : `rgba(170,230,255,${0.9 * a})`);
      g.addColorStop(1, "rgba(0,0,0,0)");
      c.fillStyle = g;
      c.fillRect(e.x - 0.4 * u, e.y - 0.4 * u, 0.8 * u, 0.8 * u);
    }
    // Embers / snow drifting in front.
    for (let i = 0; i < 26; i++) {
      const ph = (t * (fire ? 0.9 : 0.4) + hash(i * 3.3)) % 1;
      const x = cx + (hash(i * 7.1) - 0.5) * rx * 2.4 + Math.sin(t * 2 + i) * 0.15 * u;
      const y = fire ? bot - ph * (bot - top) * 1.2 : top + ph * (bot - top);
      const r = (fire ? 0.05 : 0.04) * u * (1 - ph * 0.5);
      c.fillStyle = fire ? `rgba(255,${150 + hash(i) * 90},60,${a * (1 - ph)})` : `rgba(220,245,255,${a * 0.8})`;
      c.beginPath();
      c.arc(x, y, r, 0, Math.PI * 2);
      c.fill();
    }
    c.restore();
    return;
  }
  // Outer glow.
  const g = c.createRadialGradient(cx, cy, rx * 0.2, cx, cy, ry * 1.1);
  g.addColorStop(0, fire ? `rgba(255,120,40,${0.45 * a})` : `rgba(120,200,255,${0.4 * a})`);
  g.addColorStop(1, "rgba(0,0,0,0)");
  c.fillStyle = g;
  c.fillRect(cx - ry * 1.2, cy - ry * 1.2, ry * 2.4, ry * 2.4);
  if (fire) {
    // Particle flames rising off the silhouette: soft additive embers sampled along the limbs and torso,
    // hot yellow-white near the body, cooling to red as they rise and shrink.
    const segs: [V, V][] = [
      [sk.shoulderL, sk.elbowL], [sk.elbowL, sk.wristL], [sk.shoulderR, sk.elbowR], [sk.elbowR, sk.wristR],
      [sk.shoulderL, sk.hipL], [sk.shoulderR, sk.hipR], [sk.hipL, sk.kneeL], [sk.hipR, sk.kneeR], [sk.kneeL, sk.ankleL], [sk.kneeR, sk.ankleR],
      [add(sk.head, rot(v(-0.7 * u * HS, -1.2 * u * HS), sk.headA)), add(sk.head, rot(v(0.7 * u * HS, -1.2 * u * HS), sk.headA))],
      [add(sk.head, rot(v(-0.8 * u * HS, -0.4 * u * HS), sk.headA)), add(sk.head, rot(v(-0.6 * u * HS, -1.5 * u * HS), sk.headA))],
      [add(sk.head, rot(v(0.8 * u * HS, -0.4 * u * HS), sk.headA)), add(sk.head, rot(v(0.6 * u * HS, -1.5 * u * HS), sk.headA))],
    ];
    const N = 190;
    for (let i = 0; i < N; i++) {
      const sg = segs[i % segs.length]!;
      const life = 0.45 + hash(i * 1.37) * 0.4;
      const ph = ((t + hash(i * 7.93) * life) % life) / life;
      const along = hash(i * 3.11 + Math.floor((t + hash(i * 7.93) * life) / life) * 0.7);
      const p0 = lerpV(sg[0], sg[1], along);
      const side = (hash(i * 5.5) - 0.5) * 0.7 * u;
      const x = p0.x + side + Math.sin(t * 7 + i) * 0.08 * u * ph;
      const y = p0.y - ph * (1.4 + hash(i * 2.2) * 1.5) * u * a;
      const r = (0.42 + hash(i * 4.4) * 0.3) * u * (1 - ph * 0.75) * Math.sqrt(a);
      const heat = 1 - ph;
      const gC = Math.round(70 + 175 * heat * heat);
      const bC = Math.round(20 + 150 * Math.pow(heat, 5));
      const g = c.createRadialGradient(x, y, 0, x, y, r);
      g.addColorStop(0, `rgba(255,${gC},${bC},${0.75 * heat * a})`);
      g.addColorStop(0.45, `rgba(255,${Math.round(gC * 0.7)},${bC},${0.35 * heat * a})`);
      g.addColorStop(1, "rgba(255,40,0,0)");
      c.fillStyle = g;
      c.fillRect(x - r, y - r, r * 2, r * 2);
    }
  } else {
    // Ice: rotating crystal shards and a cold mist ring.
    for (let i = 0; i < 16; i++) {
      const ang = (i / 16) * Math.PI * 2 + t * 0.6;
      const r = rx * (0.62 + 0.12 * Math.sin(t * 2 + i));
      const x = cx + Math.cos(ang) * r;
      const y = cy + Math.sin(ang) * ry * 0.85 - ((t * 0.5 + i / 16) % 1) * 0.4 * u;
      const sz = (0.12 + hash(i) * 0.1) * u * a;
      c.save();
      c.translate(x, y);
      c.rotate(ang + t);
      c.fillStyle = `rgba(190,235,255,${0.75 * a})`;
      c.beginPath();
      c.moveTo(0, -sz * 1.6);
      c.lineTo(sz * 0.45, 0);
      c.lineTo(0, sz * 1.6);
      c.lineTo(-sz * 0.45, 0);
      c.closePath();
      c.fill();
      c.restore();
    }
    const m = c.createRadialGradient(cx, bot, 0, cx, bot, rx * 1.6);
    m.addColorStop(0, `rgba(200,235,255,${0.35 * a})`);
    m.addColorStop(1, "rgba(0,0,0,0)");
    c.fillStyle = m;
    c.fillRect(cx - rx * 1.7, bot - rx * 1.7, rx * 3.4, rx * 3.4);
  }
  c.restore();
}

function norm(a: V): V {
  const n = len(a) || 1;
  return v(a.x / n, a.y / n);
}

function drawLegs(k: Kit, sk: Skeleton, spec: PuppetSpec): void {
  const u = k.u;
  const P = k.pal;
  // Pelvis / crotch block joins the legs.
  const pv = sk.pelvis;
  const X = (x: number) => turnX(x, sk.bt) * u;
  const crotch = shape([add(pv, v(X(-0.66), -0.45 * u)), add(pv, v(X(0.66), -0.45 * u)), add(pv, v(X(0.7), 0.25 * u)), add(pv, v(X(0.1), 0.62 * u)), add(pv, v(X(-0.1), 0.62 * u)), add(pv, v(X(-0.7), 0.25 * u))], 0.4);
  cel(k, crotch, P.pants, P.pantsShade, { sh: 0.12, line: "none" });
  for (const side of (sk.bt > 0.3 ? ["R", "L"] : ["L", "R"]) as ("L" | "R")[]) {
    const hip = side === "L" ? sk.hipL : sk.hipR;
    const knee = side === "L" ? sk.kneeL : sk.kneeR;
    const ankle = side === "L" ? sk.ankleL : sk.ankleR;
    const out = side === "L" ? -1 : 1;
    const top = add(hip, v(-out * 0.04 * u, -0.25 * u));
    const leg = tube([top, knee, add(ankle, v(0, 0.05 * u))], [0.8 * u, 0.58 * u, 0.52 * u], false, false);
    cel(k, leg, P.pants, P.pantsShade, { sh: 0.14, hi: shadeMix(P.pants, "#ffffff", 0.08), hiW: 0.04 });
    // Knee crease and hem fold.
    const kd = norm(sub(ankle, knee));
    inkLine(k, [add(knee, v(-out * 0.08 * u, -0.06 * u)), add(knee, add(mul(kd, 0.1 * u), v(-out * 0.02 * u, 0))), add(knee, v(out * 0.05 * u, 0.2 * u))], 0.02 * u, P.line);
    // Boot.
    const b = ankle;
    const boot = shape(
      [
        add(b, v(-0.24 * u, -0.22 * u)),
        add(b, v(0.24 * u, -0.22 * u)),
        { ...add(b, v(0.27 * u + out * 0.06 * u, 0.2 * u)), s: true },
        add(b, v(out * 0.22 * u + 0.08 * u * out, 0.36 * u)),
        { ...add(b, v(out * 0.3 * u, 0.42 * u)), s: true },
        { ...add(b, v(-out * 0.26 * u, 0.42 * u)), s: true },
        add(b, v(-0.28 * u, 0.18 * u)),
      ],
      0.6,
    );
    cel(k, boot, P.boots, "#050507", { sh: 0.1, hi: "#3a3d4c", hiW: 0.05 });
    inkLine(k, [add(b, v(-out * 0.27 * u, 0.36 * u)), add(b, v(out * 0.3 * u, 0.36 * u))], 0.05 * u, "#2c2d36", [0, 0]);
    // Trouser hem over the boot.
    const hem = shape([add(b, v(-0.25 * u, -0.28 * u)), add(b, v(0.25 * u, -0.28 * u)), add(b, v(0.27 * u, -0.08 * u)), add(b, v(0, -0.02 * u)), add(b, v(-0.27 * u, -0.08 * u))], 0.5);
    cel(k, hem, P.pants, P.pantsShade, { sh: 0.1 });
  }
  // Belt.
  const belt = shape([add(pv, v(-0.68 * u, -0.42 * u)), add(pv, v(0.68 * u, -0.42 * u)), add(pv, v(0.69 * u, -0.25 * u)), add(pv, v(-0.69 * u, -0.25 * u))], 0.2);
  cel(k, belt, "#17171c", "#0b0b0e", { sh: 0.05, hi: "#3d3e48", hiW: 0.03 });
  const buckle = shape([add(pv, v(-0.11 * u, -0.43 * u)), add(pv, v(0.11 * u, -0.43 * u)), add(pv, v(0.11 * u, -0.24 * u)), add(pv, v(-0.11 * u, -0.24 * u))], 0.1);
  cel(k, buckle, k.pal.metal, "#7f8494", { sh: 0.06, hi: "#ffffff", hiW: 0.03, lw: k.lw * 0.7 });
  void spec;
}

function drawTorso(k: Kit, sk: Skeleton, spec: PuppetSpec, bodyVel: number, wind = 0.3, time = 0): void {
  const u = k.u;
  const P = k.pal;
  const T = (x: number, y: number) => add(sk.pelvis, rot(v(turnX(x, sk.bt) * u, y * u), sk.torsoA));
  const flare = clamp(-bodyVel * 0.6, -0.15, 0.15);
  const neckY = (sk.neck.y - sk.pelvis.y) / u;
  // Inner shirt (turtleneck) visible in the jacket opening.
  const shirt = shape([T(-0.42, neckY + 0.06), T(0.42, neckY + 0.06), T(0.6, -1.2), T(0.62, -0.2), T(-0.62, -0.2), T(-0.6, -1.2)], 0.5);
  cel(k, shirt, P.shirt, P.shirtShade, { sh: 0.22, hi: shadeMix(P.shirt, "#ffffff", 0.1), hiW: 0.05 });
  if (spec.shirt === "turtleneck") {
    // Ribbing lines.
    for (let i = -2; i <= 2; i++) inkLine(k, [T(i * 0.1, -1.5), T(i * 0.1 + 0.01, -0.6), T(i * 0.1, -0.25)], 0.012 * u, shadeMix(P.shirt, "#000000", 0.35), [0.3, 0.3]);
  } else {
    // Button placket and buttons.
    inkLine(k, [T(0.0, neckY + 0.2), T(0.01, -1.0), T(0.0, -0.25)], 0.014 * u, shadeMix(P.shirt, "#000000", 0.3), [0.1, 0.1]);
  }
  if (spec.shirt === "shirt") drawTie(k, sk, spec, T, neckY, wind, time);
  if (spec.jacket === "none") return;
  const sw = 0.93;
  if (spec.jacket === "coat") {
    drawCoat(k, sk, spec, T, neckY, flare, wind, time);
    return;
  }
  const jacketSide = (side: -1 | 1) =>
    shape(
      [
        { ...T(side * 0.36, neckY + 0.02), s: true },
        T(side * 0.62, neckY + 0.1),
        T(side * sw, -2.02),
        T(side * 0.97, -1.66),
        T(side * 0.86, -1.15),
        T(side * 0.86, -0.4),
        { ...T(side * (0.95 + flare * side), 0.42), s: true },
        T(side * 0.55, 0.47),
        { ...T(side * 0.16, 0.44), s: true },
        T(side * 0.2, -0.5),
        T(side * 0.27, -1.3),
      ],
      0.45,
    );
  for (const side of [-1, 1] as const) {
    const p = jacketSide(side);
    cel(k, p, P.jacket, P.jacketShade, { sh: side === -1 ? 0.3 : 0.16, hi: P.jacketHi, hiW: 0.045 });
    // Leather specular streak.
    k.c.save();
    k.c.clip(p);
    inkLine(k, [T(side * 0.62, -1.75), T(side * 0.7, -1.1), T(side * 0.66, -0.3)], 0.07 * u, side === 1 ? withA(P.jacketHi, 0.55) : withA(P.jacketHi, 0.18), [0.4, 0.4]);
    k.c.restore();
    // Zipper edge.
    inkLine(k, [T(side * 0.24, -1.45), T(side * 0.2, -0.5), T(side * 0.17, 0.4)], 0.018 * u, withA(P.metal, 0.55), [0.1, 0.1]);
    // Pocket and seam.
    inkLine(k, [T(side * 0.42, -0.35), T(side * 0.68, -0.15)], 0.022 * u, P.line, [0.2, 0.2]);
    inkLine(k, [T(side * 0.86, -1.5), T(side * 0.64, -1.62), T(side * 0.38, -1.5)], 0.016 * u, P.line, [0.3, 0.3]);
    // Lapel.
    const lapel = shape([{ ...T(side * 0.36, neckY + 0.02), s: true }, T(side * 0.66, neckY + 0.12), { ...T(side * 0.6, -1.6), s: true }, { ...T(side * 0.3, -1.25), s: true }], 0.3);
    cel(k, lapel, shadeMix(P.jacket, "#ffffff", 0.06), P.jacketShade, { sh: 0.08, hi: P.jacketHi, hiW: 0.03, lw: k.lw * 0.8 });
  }
  // Waistband.
  for (const side of [-1, 1] as const) {
    const wb = shape([T(side * 0.17, 0.3), T(side * (0.94 + flare * side), 0.3), T(side * (0.95 + flare * side), 0.44), T(side * 0.16, 0.44)], 0.2);
    cel(k, wb, shadeMix(P.jacket, "#000000", 0.2), "#050507", { sh: 0.05, lw: k.lw * 0.7 });
  }
  if (spec.chain) {
    const a = T(-0.2, neckY + 0.12);
    const b = T(0.2, neckY + 0.12);
    const m = T(0.02, -1.38);
    inkLine(k, [a, lerpV(a, m, 0.55), m, lerpV(b, m, 0.55), b], 0.028 * u, P.metal, [0.05, 0.05]);
    const pend = T(0.02, -1.3);
    const pp = shape([add(pend, v(0, -0.09 * u)), add(pend, v(0.06 * u, 0)), add(pend, v(0, 0.12 * u)), add(pend, v(-0.06 * u, 0))], 0.1);
    cel(k, pp, P.metal, "#7c8090", { sh: 0.05, hi: "#ffffff", hiW: 0.025, lw: k.lw * 0.6 });
  }
}

/** Shirt collar and a necktie that flutters in the wind. */
function drawTie(k: Kit, sk: Skeleton, spec: PuppetSpec, T: (x: number, y: number) => V, neckY: number, wind: number, time: number): void {
  const u = k.u;
  const P = k.pal;
  const flut = wind * (noise1(time * 2.3, spec.seed + 21) * 0.18 + 0.06);
  const knotTop = neckY + 0.1;
  const knot = shape([T(-0.07, knotTop), T(0.07, knotTop), T(0.05, knotTop + 0.16), T(-0.05, knotTop + 0.16)], 0.3);
  const blade = shape(
    [
      T(-0.05, knotTop + 0.14),
      T(0.05, knotTop + 0.14),
      T(0.12 + flut * 0.5, -1.05),
      { ...T(0.02 + flut, -0.55 - Math.abs(flut) * 0.4), s: true },
      T(-0.1 + flut * 0.5, -1.05),
    ],
    0.35,
  );
  cel(k, blade, P.tie, shadeMix(P.tie, "#000000", 0.45), { sh: 0.08, hi: shadeMix(P.tie, "#ffffff", 0.3), hiW: 0.03, lw: k.lw * 0.8 });
  cel(k, knot, P.tie, shadeMix(P.tie, "#000000", 0.45), { sh: 0.05, lw: k.lw * 0.8 });
  // Collar points either side of the knot.
  for (const sd of [-1, 1] as const) {
    const col = shape([{ ...T(sd * 0.05, neckY + 0.04), s: true }, T(sd * 0.3, neckY + 0.02), { ...T(sd * 0.2, neckY + 0.3), s: true }, { ...T(sd * 0.06, neckY + 0.18), s: true }], 0.2);
    cel(k, col, P.shirt, P.shirtShade, { sh: 0.05, lw: k.lw * 0.75 });
  }
}

/** Long open coat to the knees: wide lapels, tied back belt, hem and tails that blow in the wind. */
function drawCoat(k: Kit, sk: Skeleton, spec: PuppetSpec, T: (x: number, y: number) => V, neckY: number, flare: number, wind: number, time: number): void {
  const u = k.u;
  const P = k.pal;
  for (const side of [-1, 1] as const) {
    const gust = wind * (0.5 + 0.5 * noise1(time * 1.6 + side * 3, spec.seed + 5));
    const flap = side * (0.1 * gust + flare * 0.6);
    const lift = -0.35 * gust;
    const hemY = 2.15 + lift;
    const p = shape(
      [
        { ...T(side * 0.34, neckY + 0.02), s: true },
        T(side * 0.62, neckY + 0.1),
        T(side * 0.93, -2.0),
        T(side * 0.98, -1.64),
        T(side * 0.88, -1.1),
        T(side * 0.86, -0.3),
        T(side * (0.98 + flap * 0.5), 0.8),
        { ...T(side * (1.1 + flap), hemY + 0.05 * Math.sin(time * 5 + side)), s: true },
        T(side * (0.62 + flap * 0.6), hemY + 0.08),
        { ...T(side * (0.2 + flap * 0.3), hemY - 0.02), s: true },
        T(side * (0.15 + flap * 0.1), 0.6),
        T(side * 0.2, -0.5),
        T(side * 0.27, -1.3),
      ],
      0.45,
    );
    cel(k, p, P.jacket, P.jacketShade, { sh: side === -1 ? 0.32 : 0.16, hi: P.jacketHi, hiW: 0.04 });
    // Folds that run down the tails.
    k.c.save();
    k.c.clip(p);
    inkLine(k, [T(side * 0.55, 0.35), T(side * (0.6 + flap * 0.4), 1.2), T(side * (0.66 + flap * 0.7), hemY - 0.05)], 0.02 * u, withA(P.line, 0.8), [0.4, 0.2]);
    inkLine(k, [T(side * 0.7, -1.6), T(side * 0.74, -0.8), T(side * (0.82 + flap * 0.4), 0.9)], 0.06 * u, side === 1 ? withA(P.jacketHi, 0.35) : withA(P.jacketHi, 0.12), [0.4, 0.4]);
    k.c.restore();
    // Wide notched lapel.
    const lapel = shape([{ ...T(side * 0.34, neckY + 0.02), s: true }, T(side * 0.72, neckY + 0.14), { ...T(side * 0.66, -1.62), s: true }, T(side * 0.56, -1.45), { ...T(side * 0.24, -1.05), s: true }], 0.3);
    cel(k, lapel, shadeMix(P.jacket, "#ffffff", 0.07), P.jacketShade, { sh: 0.08, hi: P.jacketHi, hiW: 0.03, lw: k.lw * 0.8 });
    // Belt loop and pocket flap.
    const pk = shape([T(side * 0.42, 0.15), T(side * 0.74, 0.12), T(side * 0.75, 0.24), T(side * 0.43, 0.27)], 0.2);
    cel(k, pk, shadeMix(P.jacket, "#000000", 0.15), P.jacketShade, { sh: 0.04, lw: k.lw * 0.6 });
  }
}

function drawArm(k: Kit, l: AnimLayer, sk: Skeleton, spec: PuppetSpec, side: "L" | "R", f: number): void {
  const u = k.u;
  const P = k.pal;
  const sh = side === "L" ? sk.shoulderL : sk.shoulderR;
  const el = side === "L" ? sk.elbowL : sk.elbowR;
  const wr = side === "L" ? sk.wristL : sk.wristR;
  const ha = side === "L" ? sk.handLA : sk.handRA;
  const pose = Math.round(chan(l, `hand${side}pose`, f));
  const out = side === "L" ? -1 : 1;
  const top = add(sh, rot(v(-out * 0.02 * u, 0.02 * u), sk.torsoA));
  const sleeveEnd = add(wr, rot(v(0, -0.08 * u), ha));
  const sleeve = tube([top, el, sleeveEnd], [0.54 * u, 0.44 * u, 0.4 * u], true, false);
  const jacket = spec.jacket !== "none";
  cel(k, sleeve, jacket ? P.jacket : P.shirt, jacket ? P.jacketShade : P.shirtShade, { sh: side === "L" ? 0.2 : 0.14, hi: jacket ? P.jacketHi : undefined, hiW: 0.04 });
  // Elbow wrinkles.
  const ed = norm(sub(wr, el));
  const en = v(-ed.y, ed.x);
  inkLine(k, [add(el, add(mul(en, 0.16 * u), mul(ed, -0.05 * u))), add(el, mul(ed, 0.04 * u)), add(el, add(mul(en, -0.08 * u), mul(ed, 0.12 * u)))], 0.022 * u, P.line, [0.3, 0.3]);
  // Cuff.
  const cuff = tube([add(wr, rot(v(0, -0.22 * u), ha)), add(wr, rot(v(0, -0.03 * u), ha))], [0.42 * u, 0.4 * u], false, false);
  cel(k, cuff, shadeMix(jacket ? P.jacket : P.shirt, "#000000", 0.25), "#050507", { sh: 0.06, lw: k.lw * 0.8 });
  if (pose !== 4) drawHand(k, wr, ha, side, pose, spec, l, f);
  else {
    // Hand in pocket: a little fold where the wrist disappears.
    inkLine(k, [add(wr, v(-0.18 * u, 0)), add(wr, v(0, 0.06 * u)), add(wr, v(0.18 * u, 0))], 0.03 * u, P.line);
  }
}

function drawHand(k: Kit, wr: V, ang: number, side: "L" | "R", pose: number, spec: PuppetSpec, l: AnimLayer, f: number): void {
  const u = k.u;
  const P = k.pal;
  const m = (x: number, y: number) => add(wr, rot(v((side === "L" ? -x : x) * u, y * u), ang));
  const holds = spec.props.includes("cigarette") && side === spec.propHand;
  const skin = (p: Path2D, lw = k.lw * 0.75) => cel(k, p, P.skin, P.skinShade, { sh: 0.06, line: P.skinLine, lw, rim: false });
  const finger = (bx: number, by: number, tx: number, ty: number, w: number) => {
    const b = m(bx, by);
    const t = m(tx, ty);
    const mid = lerpV(b, t, 0.5);
    return tube([b, mid, t], [w * u, w * 0.95 * u, w * 0.8 * u]);
  };
  if (holds && (pose === 0 || pose === 3)) pose = 2;
  const palm = shape([m(-0.15, -0.02), m(0.16, -0.02), m(0.19, 0.16), m(0.15, 0.31), m(-0.14, 0.32), m(-0.18, 0.14)], 0.6);
  const thumb = finger(-0.15, 0.06, -0.22, 0.25, 0.1);
  const fingers: Path2D[] = [];
  let front: Path2D[] = [];
  if (pose === 1) {
    for (let i = 0; i < 4; i++) fingers.push(finger(-0.1 + i * 0.075, 0.27, -0.13 + i * 0.1, 0.58 - Math.abs(i - 1.3) * 0.05, 0.08));
    front = [finger(-0.14, 0.08, -0.32, 0.22, 0.095)];
  } else if (pose === 5) {
    fingers.push(finger(0.11, 0.27, 0.13, 0.62, 0.08));
    for (let i = 0; i < 3; i++) front.push(finger(-0.1 + i * 0.07, 0.27, -0.1 + i * 0.07, 0.38, 0.085));
    front.push(thumb);
  } else if (pose === 2) {
    // Index and middle hold the cigarette; ring and pinky curl in.
    for (let i = 0; i < 2; i++) fingers.push(finger(0.02 + i * 0.075, 0.27, 0.04 + i * 0.09, 0.5, 0.08));
    front = [finger(-0.1, 0.27, -0.1, 0.38, 0.085), finger(-0.03, 0.27, -0.03, 0.39, 0.085), thumb];
  } else {
    // Relaxed / fist: curled fingers show as a knuckle row.
    front = [0, 1, 2, 3].map((i) => finger(-0.1 + i * 0.075, 0.26, -0.11 + i * 0.075, pose === 3 ? 0.36 : 0.42, 0.085));
    front.push(thumb);
  }
  for (const fp of fingers) skin(fp, k.lw * 0.6);
  skin(palm);
  if (pose === 2 && holds) drawCigarette(k, l, spec, f);
  for (const fp of front) skin(fp, k.lw * 0.6);
  // Knuckle hints.
  if (pose === 0 || pose === 3) inkLine(k, [m(-0.08, 0.3), m(0.02, 0.31), m(0.12, 0.29)], 0.012 * u, P.skinLine, [0.3, 0.3]);
}

function drawCigarette(k: Kit, l: AnimLayer, spec: PuppetSpec, f: number): void {
  const c = k.c;
  const u = k.u;
  const sk = lastSk;
  if (!sk) return;
  const a = sk.filter;
  const b = sk.ember;
  const d = norm(sub(b, a));
  const n = v(-d.y, d.x);
  const w = 0.075 * u;
  const filterEnd = add(a, mul(d, 0.17 * u));
  const body = (p0: V, p1: V, col: string) => {
    const p = new Path2D();
    p.moveTo(p0.x + n.x * w * 0.5, p0.y + n.y * w * 0.5);
    p.lineTo(p1.x + n.x * w * 0.5, p1.y + n.y * w * 0.5);
    p.lineTo(p1.x - n.x * w * 0.5, p1.y - n.y * w * 0.5);
    p.lineTo(p0.x - n.x * w * 0.5, p0.y - n.y * w * 0.5);
    p.closePath();
    c.fillStyle = col;
    c.fill(p);
    c.lineWidth = k.lw * 0.5;
    c.strokeStyle = "#3a2a22";
    c.stroke(p);
  };
  const ashLen = 0.06 * u;
  body(a, filterEnd, "#d9925a");
  body(filterEnd, sub(b, mul(d, ashLen)), "#f4f1ea");
  body(sub(b, mul(d, ashLen)), b, "#8d8a86");
  // Ember with glow; brighter while dragging.
  const drag = chan(l, "drag", f);
  const flick = 0.85 + 0.15 * noise1(f * 0.7, spec.seed + 3);
  const g = c.createRadialGradient(b.x, b.y, 0, b.x, b.y, (0.35 + drag * 0.4) * u);
  g.addColorStop(0, `rgba(255,170,90,${0.55 * flick + drag * 0.35})`);
  g.addColorStop(0.35, `rgba(255,90,40,${0.25 * flick + drag * 0.2})`);
  g.addColorStop(1, "rgba(255,60,20,0)");
  c.save();
  c.globalCompositeOperation = "lighter";
  c.fillStyle = g;
  c.beginPath();
  c.arc(b.x, b.y, (0.35 + drag * 0.4) * u, 0, Math.PI * 2);
  c.fill();
  c.restore();
  c.fillStyle = drag > 0.3 ? "#ffd27a" : "#ff6a2a";
  c.beginPath();
  c.arc(b.x, b.y, w * 0.55, 0, Math.PI * 2);
  c.fill();
}

let lastSk: Skeleton | null = null;

/** Map head-local units (scaled by HS) to the world. */
function headMap(sk: Skeleton, dx = 0, dy = 0) {
  const u = sk.u * HS;
  return (x: number, y: number) => add(sk.head, rot(v((x + dx) * u, (y + dy) * u), sk.headA));
}

/** Messy anime hair silhouette behind the head: spiky crown and nape. */
function drawHairBack(k: Kit, sk: Skeleton, spec: PuppetSpec, swing: number, t: number): void {
  const H = headMap(sk);
  const P = k.pal;
  const long = spec.hair === "long";
  const pts: (V & { s?: boolean })[] = [];
  const cx = 0;
  const cy = -1.12;
  const tips = spec.hair === "short" ? 6 : 8;
  // Sweep from the left nape, over the crown, to the right nape; clumps lean with the hair flow.
  const a0 = 160;
  const a1 = 380;
  for (let i = 0; i <= tips; i++) {
    const aDeg = lerp(a0, a1, i / tips);
    const a = (aDeg * Math.PI) / 180;
    const top = Math.max(0, -Math.sin(a)); // 1 at the crown
    const tipR = 0.74 + hash(spec.seed + i) * 0.08 + (1 - top) * 0.06 - (spec.hair === "short" ? 0.05 : 0);
    const lean = (Math.cos(a) >= 0 ? 1 : -1) * (14 + 10 * (1 - top));
    const ta = ((aDeg + lean) * Math.PI) / 180;
    const sw = swing * 0.012 * (1 - top * 0.5);
    pts.push({ ...H(cx + Math.cos(ta) * tipR + sw, cy + Math.sin(ta) * tipR), s: true });
    if (i < tips) {
      const am = (lerp(a0, a1, (i + 0.5) / tips) * Math.PI) / 180;
      pts.push(H(cx + Math.cos(am) * 0.66, cy + Math.sin(am) * 0.68));
    }
  }
  // Nape: hangs lower for long hair.
  const nape = long ? 0.35 : -0.5;
  pts.push({ ...H(0.54 + swing * 0.012, nape), s: true });
  pts.push(H(0.34, nape - 0.1));
  pts.push({ ...H(0.16 + swing * 0.012, nape + 0.04), s: true });
  pts.push(H(0, nape - 0.08));
  pts.push({ ...H(-0.16 + swing * 0.012, nape + 0.04), s: true });
  pts.push(H(-0.34, nape - 0.1));
  pts.push({ ...H(-0.54 + swing * 0.012, nape), s: true });
  const p = shape(pts, 0.42);
  cel(k, p, P.hair, P.hairShade, { sh: 0.22, hi: shadeMix(P.hair, P.hairHi, 0.35), hiW: 0.04 });
  void t;
}

/** Front locks: [rootX, rootY, tipX, tipY, width, bend]. */
function lockSet(spec: PuppetSpec): number[][] {
  if (spec.hair === "spiky")
    return [
      [-0.46, -1.42, -0.62, -0.74, 0.2, -0.12],
      [-0.3, -1.6, -0.44, -1.0, 0.24, -0.1],
      [-0.12, -1.68, -0.18, -0.96, 0.24, -0.06],
      [0.06, -1.7, 0.08, -0.98, 0.24, 0.04],
      [0.22, -1.64, 0.32, -1.0, 0.24, 0.08],
      [0.46, -1.42, 0.62, -0.74, 0.2, 0.12],
    ];
  return [
    [-0.47, -1.36, -0.55, -0.6, 0.2, -0.1], // left side lock framing the cheek
    [-0.36, -1.58, -0.42, -0.98, 0.25, -0.12],
    [-0.2, -1.68, -0.29, -0.9, 0.26, -0.08],
    [-0.03, -1.72, -0.05, -0.97, 0.2, -0.02], // centre lock
    [0.12, -1.7, 0.2, -0.88, 0.25, 0.05],
    [0.28, -1.64, 0.36, -0.96, 0.25, 0.1],
    [0.47, -1.36, 0.56, -0.6, 0.2, 0.12], // right side lock
  ];
}

function lockPath(H: (x: number, y: number) => V, l: number[], swing: number): Path2D {
  const [rx, ry, tx0, ty, w, bend] = l as [number, number, number, number, number, number];
  const tx = tx0 + swing * 0.008 * (1 + Math.abs(rx));
  const mx = (rx + tx) / 2 + bend;
  const my = (ry + ty) / 2;
  // Leaf: wide near the root, curving to a sharp tip.
  const pts: (V & { s?: boolean })[] = [
    H(rx - w * 0.55, ry + 0.02),
    H(mx - w * 0.42, my - 0.06),
    { ...H(tx, ty), s: true },
    H(mx + w * 0.42, my - 0.12),
    H(rx + w * 0.55, ry - 0.02),
    H(rx, ry - 0.1),
  ];
  return shape(pts, 0.55);
}

function drawHead(k: Kit, l: AnimLayer, sk: Skeleton, spec: PuppetSpec, f: number, fps: number, swing: number): void {
  const u = k.u * HS;
  const P = k.pal;
  const c = k.c;
  const tn = sk.turn * 0.1;
  const nd = -sk.nod * 0.04;
  const H = headMap(sk);
  const F = headMap(sk, tn, nd); // face features follow turn / nod
  const kh: Kit = { ...k, u, lw: k.lw * 1.05 };
  // Neck.
  const neck = shape([H(-0.16, 0.14), H(-0.15, -0.5), H(0.15, -0.5), H(0.16, 0.14)], 0.3);
  cel(kh, neck, P.skin, P.skinShade, { sh: 0.1, line: P.skinLine, lw: k.lw * 0.8 });
  c.save();
  c.clip(neck);
  c.fillStyle = P.skinShade;
  c.fill(shape([H(-0.4, -0.7), H(0.4, -0.7), H(0.3, -0.36), H(0, -0.16), H(-0.3, -0.36)], 0.6));
  c.restore();
  if (spec.shirt === "shirt") {
    // Stand collar hugging the neck.
    const band = shape([H(-0.24, 0.16), H(-0.2, -0.2), H(0, -0.16), H(0.2, -0.2), H(0.24, 0.16)], 0.4);
    cel(kh, band, P.shirt, P.shirtShade, { sh: 0.08, lw: k.lw * 0.8 });
    inkLine(kh, [H(0, -0.16), H(0, 0.14)], 0.014 * u, P.skinLine, [0.2, 0.2]);
  }
  if (spec.shirt === "turtleneck") {
    const col = shape([H(-0.27, 0.18), H(-0.24, -0.28), H(0, -0.24), H(0.24, -0.28), H(0.27, 0.18)], 0.5);
    cel(kh, col, P.shirt, P.shirtShade, { sh: 0.12 });
    inkLine(kh, [H(-0.22, -0.12), H(0, -0.08), H(0.22, -0.12)], 0.016 * u, P.line, [0.3, 0.3]);
    inkLine(kh, [H(-0.23, 0.02), H(0, 0.06), H(0.23, 0.02)], 0.016 * u, P.line, [0.3, 0.3]);
  }
  // Ears.
  for (const sx of [-1, 1] as const) {
    const ex = sx * 0.39 - tn * 0.6;
    const ear = shape([H(ex, -1.0), H(ex + sx * 0.09, -0.98), H(ex + sx * 0.1, -0.8), H(ex + sx * 0.03, -0.7), H(ex - sx * 0.02, -0.74)], 0.6);
    cel(kh, ear, P.skin, P.skinShade, { sh: 0.05, line: P.skinLine, lw: k.lw * 0.7 });
  }
  // Face: slim, cheekbones high, sharp chin.
  const face = shape(
    [
      F(-0.4, -1.34),
      F(-0.41, -1.0),
      F(-0.37, -0.76),
      F(-0.25, -0.52),
      F(-0.09, -0.36),
      { ...F(0, -0.32), s: true },
      F(0.09, -0.36),
      F(0.25, -0.52),
      F(0.37, -0.76),
      F(0.41, -1.0),
      F(0.4, -1.34),
      F(0, -1.5),
    ],
    0.5,
  );
  cel(kh, face, P.skin, P.skinShade, { sh: 0.07, line: P.skinLine, lw: k.lw * 0.9, rim: false });
  const locks = lockSet(spec);
  c.save();
  c.clip(face);
  // Hair shadow cast on the forehead (locks pushed down), and a soft cheek shadow on the far side.
  const Hs = headMap(sk, 0.02, 0.07);
  c.fillStyle = P.skinShade;
  for (const lk of locks) c.fill(lockPath(Hs, lk, swing));
  c.fill(shape([Hs(-0.62, -1.3), Hs(0, -1.85), Hs(0.62, -1.3), Hs(0.3, -1.38), Hs(-0.3, -1.38)], 0.4));
  const blush = chan(l, "blush", f);
  if (blush > 0.02) {
    for (const sx of [-1, 1]) {
      const p = F(sx * 0.24, -0.72);
      const g = c.createRadialGradient(p.x, p.y, 0, p.x, p.y, 0.14 * u);
      g.addColorStop(0, `rgba(240,110,120,${0.5 * blush})`);
      g.addColorStop(1, "rgba(240,110,120,0)");
      c.fillStyle = g;
      c.fillRect(p.x - 0.2 * u, p.y - 0.2 * u, 0.4 * u, 0.4 * u);
    }
  }
  c.restore();
  drawEyes(kh, l, sk, f, fps, F);
  // Nose: a small shadow on the far side and a tip line.
  inkLine(kh, [F(0.035, -0.74), F(0.05, -0.66), F(0.01, -0.635)], 0.02 * u, P.skinLine, [0.5, 0.2]);
  drawMouth(kh, l, f, F);
  // Front hair cap over the hairline, then the locks from the sides to the centre.
  const cap = shape([H(-0.6, -1.22), H(-0.48, -1.6), H(0, -1.82), H(0.48, -1.6), H(0.6, -1.22), H(0.3, -1.4), H(0, -1.46), H(-0.3, -1.4)], 0.4);
  cel(kh, cap, P.hair, P.hairShade, { sh: 0.12, line: "none" });
  const order = locks.map((_, i) => i).sort((a, b) => Math.abs(locks[b]![0]!) - Math.abs(locks[a]![0]!));
  for (const i of order) {
    const lk = locks[i]!;
    const lp = lockPath(H, lk, swing);
    cel(kh, lp, P.hair, P.hairShade, { sh: 0.1, lw: k.lw * 0.85 });
    // Glossy streak near the root on the lit side.
    c.save();
    c.clip(lp);
    const [rx, ry, tx, ty] = lk as [number, number, number, number];
    inkLine(kh, [H(lerp(rx, tx, 0.12) + 0.04, lerp(ry, ty, 0.12)), H(lerp(rx, tx, 0.3) + 0.03, lerp(ry, ty, 0.3)), H(lerp(rx, tx, 0.45) + 0.02, lerp(ry, ty, 0.45))], 0.045 * u, withA(P.hairHi, 0.8), [0.3, 0.6]);
    c.restore();
  }
  // A couple of loose strands.
  inkLine(kh, [H(-0.1, -1.62), H(-0.02, -1.95), H(0.12, -2.02)], 0.03 * u, P.hair, [0.1, 0.8]);
  inkLine(kh, [H(0.3, -1.58), H(0.42, -1.86), H(0.55, -1.9)], 0.028 * u, P.hair, [0.1, 0.8]);
}

/** Bangs outline (kept for compatibility with older callers). */
function bangsPath(sk: Skeleton, spec: PuppetSpec, swing: number, drop: number): Path2D {
  const H = headMap(sk, 0, drop);
  const p = new Path2D();
  for (const lk of lockSet(spec)) p.addPath(lockPath(H, lk, swing));
  return p;
}

function drawEyes(k: Kit, l: AnimLayer, sk: Skeleton, f: number, fps: number, F: (x: number, y: number) => V): void {
  const u = k.u;
  const c = k.c;
  const P = k.pal;
  let blink = chan(l, "blink", f);
  if (blink < 0) blink = autoBlink(f, fps, l.puppet?.seed ?? 1);
  const lid = chan(l, "lid", f);
  const closed = clamp(Math.max(blink, lid * 0.72), 0, 1);
  const lx = chan(l, "lookX", f) * 0.045;
  const ly = chan(l, "lookY", f) * 0.025;
  for (const sx of [-1, 1] as const) {
    const cx = sx * 0.19;
    const cy = -0.9;
    const brow = chan(l, sx === -1 ? "browL" : "browR", f);
    const inner = F(cx - sx * 0.12, cy + 0.025);
    const outer = F(cx + sx * 0.15, cy - 0.015);
    const topOpen = F(cx + sx * 0.02, cy - 0.105);
    const bottom = F(cx + sx * 0.03, cy + 0.07);
    const topNow = lerpV(topOpen, F(cx + sx * 0.03, cy + 0.06), closed);
    if (closed > 0.9) {
      inkLine(k, [inner, F(cx, cy + 0.055), outer, F(cx + sx * 0.19, cy - 0.01)], 0.05 * u, P.line, [0.15, 0.35]);
      inkLine(k, [F(cx - sx * 0.02, cy + 0.075), F(cx + sx * 0.08, cy + 0.07)], 0.015 * u, P.skinLine, [0.4, 0.4]);
    } else {
      const eye = new Path2D();
      eye.moveTo(inner.x, inner.y);
      eye.bezierCurveTo(lerpV(inner, topNow, 0.4).x, topNow.y - 0.01 * u, topNow.x, topNow.y, lerpV(topNow, outer, 0.5).x, lerpV(topNow, outer, 0.35).y);
      eye.lineTo(outer.x, outer.y);
      eye.quadraticCurveTo(bottom.x, bottom.y + 0.015 * u, inner.x, inner.y);
      eye.closePath();
      c.save();
      c.fillStyle = "#fbf8f6";
      c.fill(eye);
      c.clip(eye);
      const ic = F(cx + lx + sx * 0.01, cy - 0.015 + ly);
      const ir = 0.078 * u;
      const g = c.createLinearGradient(ic.x, ic.y - ir * 1.3, ic.x, ic.y + ir * 1.3);
      g.addColorStop(0, shadeMix(P.eyes, "#000000", 0.78));
      g.addColorStop(0.5, shadeMix(P.eyes, "#000000", 0.2));
      g.addColorStop(1, shadeMix(P.eyes, "#ffd6dc", 0.3));
      c.fillStyle = g;
      c.beginPath();
      c.ellipse(ic.x, ic.y, ir, ir * 1.28, sk.headA * D2R, 0, Math.PI * 2);
      c.fill();
      c.lineWidth = 0.012 * u;
      c.strokeStyle = shadeMix(P.eyes, "#000000", 0.8);
      c.stroke();
      // Pupil slit-ish and iris ring detail.
      c.fillStyle = shadeMix(P.eyes, "#000000", 0.88);
      c.beginPath();
      c.ellipse(ic.x, ic.y + 0.004 * u, ir * 0.38, ir * 0.62, sk.headA * D2R, 0, Math.PI * 2);
      c.fill();
      c.strokeStyle = withA(shadeMix(P.eyes, "#ffffff", 0.4), 0.6);
      c.lineWidth = 0.008 * u;
      c.beginPath();
      c.arc(ic.x, ic.y + ir * 0.35, ir * 0.62, Math.PI * 0.15, Math.PI * 0.85);
      c.stroke();
      // Upper-lid shadow on the eyeball.
      const sg = c.createLinearGradient(topNow.x, topNow.y, topNow.x, topNow.y + 0.07 * u);
      sg.addColorStop(0, "rgba(50,20,50,0.6)");
      sg.addColorStop(1, "rgba(50,20,50,0)");
      c.fillStyle = sg;
      c.fillRect(ic.x - 0.25 * u, topNow.y - 0.03 * u, 0.5 * u, 0.12 * u);
      c.fillStyle = "#ffffff";
      c.beginPath();
      c.ellipse(ic.x + 0.025 * u, ic.y - 0.04 * u, 0.024 * u, 0.03 * u, 0, 0, Math.PI * 2);
      c.fill();
      c.globalAlpha = 0.75;
      c.beginPath();
      c.arc(ic.x - 0.025 * u, ic.y + 0.045 * u, 0.012 * u, 0, Math.PI * 2);
      c.fill();
      c.restore();
      // Heavy upper lash line with an outer flick, plus the crease above.
      inkLine(k, [lerpV(inner, topNow, 0.05), lerpV(inner, topNow, 0.55), topNow, lerpV(topNow, outer, 0.55), outer, F(cx + sx * 0.2, cy - 0.055)], 0.058 * u, P.line, [0.1, 0.25]);
      inkLine(k, [F(cx - sx * 0.06, cy - 0.12 + closed * 0.08), F(cx + sx * 0.05, cy - 0.15 + closed * 0.1), F(cx + sx * 0.14, cy - 0.11 + closed * 0.1)], 0.014 * u, P.skinLine, [0.4, 0.4]);
      inkLine(k, [F(cx + sx * 0.03, cy + 0.08), F(cx + sx * 0.1, cy + 0.06), F(cx + sx * 0.145, cy + 0.01)], 0.017 * u, P.skinLine, [0.4, 0.3]);
    }
    const bIn = F(cx - sx * 0.13, cy - 0.2 - brow * 0.05 + (brow < 0 ? -brow * 0.06 : 0));
    const bMid = F(cx + sx * 0.02, cy - 0.255 - brow * 0.06);
    const bOut = F(cx + sx * 0.18, cy - 0.21 - brow * 0.03);
    inkLine(k, [bIn, bMid, bOut], 0.034 * u, P.hairShade, [0.1, 0.6]);
  }
}

function autoBlink(f: number, fps: number, seed: number): number {
  const t = f / fps;
  // Blinks every 2.6–4.6 s, 0.16 s long.
  let at = 1.1 + hash(seed) * 1.5;
  let i = 0;
  while (at < t + 0.2 && i < 400) {
    const d = t - at;
    if (d >= 0 && d < 0.16) return Math.sin((d / 0.16) * Math.PI);
    at += 2.6 + hash(seed + ++i) * 2;
  }
  return 0;
}

function drawMouth(k: Kit, l: AnimLayer, f: number, F: (x: number, y: number) => V): void {
  const u = k.u;
  const c = k.c;
  const P = k.pal;
  const open = clamp(chan(l, "mouthOpen", f), 0, 1);
  const smile = chan(l, "mouthSmile", f);
  const smirk = chan(l, "mouthSmirk", f);
  const round = clamp(chan(l, "mouthRound", f), 0, 1);
  const y = -0.5;
  const hw = 0.085 * (1 - round * 0.55) * (1 + smile * 0.15);
  const lc = F(-hw, y - smile * 0.03 + smirk * 0.0);
  const rc = F(hw, y - smile * 0.03 - smirk * 0.05);
  if (open < 0.06) {
    const mid = F(0, y + smile * 0.012 + 0.005);
    inkLine(k, [lc, mid, rc], 0.024 * u, P.skinLine, [0.3, 0.3]);
    if (smirk > 0.3) inkLine(k, [rc, F(hw + 0.03, y - 0.075 * smirk)], 0.016 * u, P.skinLine, [0.2, 0.6]);
    return;
  }
  const top = F(0, y - 0.01 - open * 0.015);
  const bot = F(0, y + 0.03 + open * (0.1 + round * 0.04));
  const m = new Path2D();
  m.moveTo(lc.x, lc.y);
  m.quadraticCurveTo(top.x, top.y - 0.01 * u, rc.x, rc.y);
  m.quadraticCurveTo(bot.x + (rc.x - lc.x) * 0.05, bot.y + 0.02 * u, lc.x, lc.y);
  m.closePath();
  c.save();
  c.fillStyle = "#2c1216";
  c.fill(m);
  c.clip(m);
  // Tongue and upper teeth.
  c.fillStyle = "#9a4650";
  c.beginPath();
  c.ellipse(bot.x, bot.y + 0.01 * u, hw * u * 0.8, 0.05 * u, 0, 0, Math.PI * 2);
  c.fill();
  if (open > 0.3 && round < 0.5) {
    c.fillStyle = "#fbf6f2";
    c.fillRect(lc.x, top.y - 0.03 * u, rc.x - lc.x, 0.045 * u);
  }
  c.restore();
  c.lineWidth = 0.02 * u;
  c.strokeStyle = P.skinLine;
  c.lineJoin = "round";
  c.stroke(m);
}

/* ---------------------------------- smoke ---------------------------------- */

function drawSmoke(c: Ctx, l: AnimLayer, spec: PuppetSpec, s: AnimScene, f: number): void {
  if (!spec.props.includes("cigarette")) return;
  const fps = s.fps;
  const u = spec.height / FIG;
  c.save();
  // Ember wisp: particles born every 2 frames, each lives 3 s.
  const life = Math.round(3.2 * fps);
  const wisp: { p: V; a: number; r: number; age: number }[] = [];
  for (let b = Math.max(0, f - life); b <= f; b += 1) {
    if (b % 2) continue;
    const age = (f - b) / fps;
    const sk = skeleton(l, spec, b, fps);
    const seed = b * 1.37 + spec.seed;
    const sway = noise1(age * 1.3 + seed, 2) * 0.35 * Math.pow(age, 1.25) + noise1(f / fps * 0.6 + age, 9) * 0.12 * age;
    const rise = 0.5 * age + 0.12 * age * age;
    const p = add(sk.ember, v((sway + 0.1 * age) * u, -rise * u));
    const a = 0.32 * Math.pow(Math.max(0, 1 - age / 3.2), 1.6) * Math.min(1, age * 6);
    wisp.push({ p, a, r: (0.03 + 0.22 * age) * u, age });
  }
  // Thin curling line near the source.
  const young = wisp.filter((w) => w.age < 1.1).sort((x, y) => x.age - y.age);
  if (young.length > 2) {
    c.lineCap = "round";
    c.lineJoin = "round";
    for (let i = 1; i < young.length; i++) {
      const w0 = young[i - 1]!;
      const w1 = young[i]!;
      if (len(sub(w1.p, w0.p)) > 0.22 * u) continue;
      c.strokeStyle = `rgba(220,222,235,${Math.min(0.55, w1.a * 1.6)})`;
      c.lineWidth = Math.max(1, (0.025 + w1.age * 0.05) * u);
      c.beginPath();
      c.moveTo(w0.p.x, w0.p.y);
      c.lineTo(w1.p.x, w1.p.y);
      c.stroke();
    }
  }
  for (const w of wisp) {
    if (w.age < 0.6) continue;
    blob(c, w.p, w.r, w.a * 0.7);
  }
  // Exhaled smoke from the mouth.
  const life2 = Math.round(2.6 * fps);
  for (let b = Math.max(0, f - life2); b <= f; b++) {
    const e = chan(l, "exhale", b);
    if (e < 0.04) continue;
    const age = (f - b) / fps;
    const sk = skeleton(l, spec, b, fps);
    for (let j = 0; j < 2; j++) {
      const seed = b * 3.1 + j * 17 + spec.seed;
      const dir = rot(v(0.8, -0.75), (hash(seed) - 0.5) * 40 + chan(l, "headTilt", b) * 0.8);
      const spd = 1.9 * (0.6 + hash(seed + 1) * 0.6);
      const dist = (spd * (1 - Math.exp(-age * 2.2))) / 2.2;
      const drift = noise1(age * 1.1 + seed, 4) * 0.4 * age;
      const p = add(sk.mouth, add(mul(dir, dist * u), v(drift * u + 0.15 * age * u, -0.25 * age * age * u)));
      const a = e * 0.14 * Math.pow(Math.max(0, 1 - age / 2.6), 1.6) * Math.min(1, age * 8);
      blob(c, p, (0.05 + 0.3 * Math.pow(age, 0.85)) * u, a);
    }
  }
  c.restore();
}

function blob(c: Ctx, p: V, r: number, a: number): void {
  if (a <= 0.003 || r <= 0.5) return;
  const g = c.createRadialGradient(p.x, p.y, 0, p.x, p.y, r);
  g.addColorStop(0, `rgba(214,216,228,${a})`);
  g.addColorStop(0.5, `rgba(200,202,216,${a * 0.55})`);
  g.addColorStop(1, "rgba(190,192,210,0)");
  c.fillStyle = g;
  c.beginPath();
  c.arc(p.x, p.y, r, 0, Math.PI * 2);
  c.fill();
}

/* --------------------------------- colours --------------------------------- */

function hexRgb(h: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(h.trim());
  const n = m ? parseInt(m[1]!, 16) : 0;
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
export function shadeMix(a: string, b: string, t: number): string {
  const x = hexRgb(a);
  const y = hexRgb(b);
  const q = (i: number) => Math.round(lerp(x[i]!, y[i]!, t)).toString(16).padStart(2, "0");
  return `#${q(0)}${q(1)}${q(2)}`;
}
function withA(h: string, a: number): string {
  const [r, g, b] = hexRgb(h);
  return `rgba(${r},${g},${b},${a})`;
}

/* -------------------------------- backdrops -------------------------------- */

export interface BackdropSpec {
  type: (typeof BACKDROPS)[number];
  seed: number;
  sky?: [string, string];
  moon?: boolean;
  /** Time of day for the river scenes: dusk (default) or night. */
  time?: "dusk" | "night";
  /** Railing / horizon height as a fraction of the frame (railing, bridge-deck). */
  y?: number;
}

export const BACKDROPS = ["night-city", "night-rooftop", "sunset-city", "studio", "gradient", "river-bridge", "bridge-deck", "river-pov", "railing", "speed", "speed-radial", "sky-high"] as const;

/**
 * Anime action backgrounds: "speed" (streaming horizontal speed lines over a colour wash, for dashes and
 * close-ups mid-fight), "speed-radial" (focus lines toward a point), "sky-high" (open sky with racing clouds,
 * for airborne shots). sky = [base, accent].
 */
function drawActionBackdrop(c: Ctx, l: AnimLayer, s: AnimScene, f: number, b: BackdropSpec): void {
  const W = s.width;
  const H = s.height;
  const t = f / s.fps;
  const [c0, c1] = b.sky ?? (b.type === "sky-high" ? ["#2a6fd6", "#bfe3ff"] : ["#1a0f2e", "#ff5a1f"]);
  const two = Math.floor(f / 2);
  if (b.type === "sky-high") {
    const g = c.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0, c0);
    g.addColorStop(1, c1);
    c.fillStyle = g;
    c.fillRect(0, 0, W, H);
    for (let i = 0; i < 14; i++) {
      const y = ((hash(i * 3.3 + b.seed) * H * 1.4 - t * H * (0.6 + hash(i) * 0.8)) % (H * 1.4) + H * 1.4) % (H * 1.4) - H * 0.2;
      const x = hash(i * 7.1 + b.seed) * W;
      const r = W * (0.12 + hash(i * 2.2) * 0.2);
      for (let k = 0; k < 5; k++) {
        const gx = x + (k - 2) * r * 0.45;
        const gr = c.createRadialGradient(gx, y, 0, gx, y, r * 0.6);
        gr.addColorStop(0, "rgba(255,255,255,0.75)");
        gr.addColorStop(1, "rgba(255,255,255,0)");
        c.fillStyle = gr;
        c.fillRect(gx - r, y - r, r * 2, r * 2);
      }
    }
    return;
  }
  const radial = b.type === "speed-radial";
  const g = radial ? c.createRadialGradient(W / 2, H * 0.45, 0, W / 2, H * 0.45, H * 0.8) : c.createLinearGradient(0, 0, W, H);
  g.addColorStop(0, c1);
  g.addColorStop(1, c0);
  c.fillStyle = g;
  c.fillRect(0, 0, W, H);
  c.fillStyle = "rgba(255,255,255,0.9)";
  if (radial) {
    const cx = W / 2,
      cy = H * 0.45;
    for (let i = 0; i < 70; i++) {
      const a = hash(i * 2.3 + two * 0.13 + b.seed) * Math.PI * 2;
      const r0 = H * (0.18 + hash(i + two) * 0.2);
      const r1 = Math.hypot(W, H);
      const wd = (0.002 + hash(i * 5.7) * 0.007) * Math.PI;
      c.globalAlpha = 0.35 + hash(i * 9) * 0.5;
      c.beginPath();
      c.moveTo(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0);
      c.lineTo(cx + Math.cos(a - wd) * r1, cy + Math.sin(a - wd) * r1);
      c.lineTo(cx + Math.cos(a + wd) * r1, cy + Math.sin(a + wd) * r1);
      c.closePath();
      c.fill();
    }
  } else {
    // Lines stream right-to-left (the camera is flying with a character moving screen-right).
    const dir = b.y !== undefined && b.y < 0 ? -1 : 1;
    for (let i = 0; i < 90; i++) {
      const y = hash(i * 1.7 + b.seed) * H;
      const len = W * (0.15 + hash(i * 3.9) * 0.5);
      const sp = W * (3 + hash(i * 5.1) * 4);
      const x = (((hash(i * 7.3) * W * 2 - t * sp * dir) % (W * 2)) + W * 2) % (W * 2) - W * 0.5;
      const th = 1 + hash(i * 2.9) * 5;
      c.globalAlpha = 0.25 + hash(i * 4.4) * 0.6;
      c.beginPath();
      c.moveTo(x, y - th / 2);
      c.lineTo(x + len, y);
      c.lineTo(x, y + th / 2);
      c.closePath();
      c.fill();
    }
  }
  c.globalAlpha = 1;
}

export function drawBackdrop(c: Ctx, l: AnimLayer, s: AnimScene, f: number): void {
  const b = l.backdrop;
  if (!b) return;
  if (b.type === "river-bridge" || b.type === "bridge-deck" || b.type === "river-pov" || b.type === "railing") return drawRiverScene(c, l, s, f, b);
  if (b.type === "speed" || b.type === "speed-radial" || b.type === "sky-high") return drawActionBackdrop(c, l, s, f, b);
  const W = s.width;
  const H = s.height;
  const t = f / s.fps;
  const cam = valueAt(l.chan?.camX, f, 0);
  const night = b.type.startsWith("night");
  const sunset = b.type === "sunset-city";
  const sky = b.sky ?? (night ? ["#0d0b24", "#3b2f72"] : sunset ? ["#2b1b4f", "#ff9a62"] : b.type === "studio" ? ["#2a2a33", "#0e0e12"] : ["#1b1b2f", "#4a3f7a"]);
  const g = c.createLinearGradient(0, 0, 0, H);
  g.addColorStop(0, sky[0]);
  g.addColorStop(0.75, sky[1]);
  g.addColorStop(1, sky[1]);
  c.fillStyle = g;
  c.fillRect(0, 0, W, H);
  if (b.type === "studio" || b.type === "gradient") {
    if (b.type === "studio") {
      const v2 = c.createRadialGradient(W / 2, H * 0.45, 0, W / 2, H * 0.45, H * 0.7);
      v2.addColorStop(0, "rgba(255,255,255,0.10)");
      v2.addColorStop(1, "rgba(0,0,0,0.35)");
      c.fillStyle = v2;
      c.fillRect(0, 0, W, H);
    }
    return;
  }
  const rnd = (i: number) => hash(i * 13.7 + b.seed * 101);
  if (night) {
    // Stars.
    for (let i = 0; i < 140; i++) {
      const x = rnd(i) * W;
      const y = rnd(i + 500) * H * 0.55;
      const tw = 0.5 + 0.5 * Math.sin(t * (1 + rnd(i + 900) * 2) + i);
      c.fillStyle = `rgba(255,255,255,${(0.25 + rnd(i + 300) * 0.55) * (0.6 + 0.4 * tw)})`;
      c.fillRect(x, y, 1.6 + rnd(i + 77) * 1.8, 1.6 + rnd(i + 77) * 1.8);
    }
  }
  if (b.moon !== false) {
    const mx = W * 0.76 - cam * 0.05;
    const my = H * (sunset ? 0.52 : 0.17);
    const mr = W * (sunset ? 0.16 : 0.085);
    const halo = c.createRadialGradient(mx, my, mr * 0.8, mx, my, mr * 5);
    halo.addColorStop(0, sunset ? "rgba(255,190,120,0.55)" : "rgba(190,180,255,0.35)");
    halo.addColorStop(1, "rgba(0,0,0,0)");
    c.fillStyle = halo;
    c.fillRect(0, 0, W, H);
    const disc = c.createRadialGradient(mx - mr * 0.3, my - mr * 0.3, mr * 0.1, mx, my, mr);
    disc.addColorStop(0, sunset ? "#fff1c9" : "#fffdf3");
    disc.addColorStop(1, sunset ? "#ffb36b" : "#d9d3ee");
    c.fillStyle = disc;
    c.beginPath();
    c.arc(mx, my, mr, 0, Math.PI * 2);
    c.fill();
    if (!sunset) {
      c.fillStyle = "rgba(150,140,190,0.25)";
      for (const [dx, dy, r] of [
        [-0.3, -0.2, 0.18],
        [0.25, 0.15, 0.12],
        [0.05, 0.4, 0.1],
      ] as const) {
        c.beginPath();
        c.arc(mx + dx * mr, my + dy * mr, r * mr, 0, Math.PI * 2);
        c.fill();
      }
    }
  }
  // Drifting clouds.
  for (let i = 0; i < 4; i++) {
    const y = H * (0.12 + i * 0.09);
    const x = ((rnd(i + 40) * W * 1.6 + t * (8 + i * 4)) % (W * 1.6)) - W * 0.3;
    const cg = c.createRadialGradient(x, y, 0, x, y, W * 0.35);
    const col = sunset ? "255,170,140" : "120,110,190";
    cg.addColorStop(0, `rgba(${col},0.18)`);
    cg.addColorStop(1, `rgba(${col},0)`);
    c.save();
    c.translate(x, y);
    c.scale(1, 0.22);
    c.translate(-x, -y);
    c.fillStyle = cg;
    c.beginPath();
    c.arc(x, y, W * 0.35, 0, Math.PI * 2);
    c.fill();
    c.restore();
  }
  // Three layers of buildings with parallax and lit windows.
  const layers = [
    { base: H * 0.62, hMin: 0.06, hMax: 0.2, col: night ? "#2a2552" : "#5b3a6e", win: 0.05, par: 0.1, w: [40, 90], a: 0.35 },
    { base: H * 0.7, hMin: 0.1, hMax: 0.3, col: night ? "#1a1636" : "#3a2347", win: 0.1, par: 0.25, w: [60, 130], a: 0.5 },
    { base: H * 0.8, hMin: 0.12, hMax: 0.42, col: night ? "#0f0d20" : "#22142c", win: 0.16, par: 0.45, w: [90, 180], a: 0.7 },
  ];
  layers.forEach((L, li) => {
    let x = -200 - ((cam * L.par) % 400);
    let i = 0;
    // Haze between layers.
    const hz = c.createLinearGradient(0, L.base - H * 0.25, 0, L.base);
    hz.addColorStop(0, "rgba(0,0,0,0)");
    hz.addColorStop(1, night ? "rgba(90,70,170,0.18)" : "rgba(255,150,120,0.2)");
    c.fillStyle = hz;
    c.fillRect(0, L.base - H * 0.25, W, H * 0.25);
    while (x < W + 200) {
      const seed = li * 1000 + i++;
      const bw = L.w[0]! + rnd(seed) * (L.w[1]! - L.w[0]!);
      const bh = H * (L.hMin + rnd(seed + 1) * (L.hMax - L.hMin));
      const top = L.base - bh;
      c.fillStyle = L.col;
      c.fillRect(x, top, bw, H - top);
      // Roof details.
      if (rnd(seed + 2) > 0.6) c.fillRect(x + bw * 0.2, top - bh * 0.06, bw * 0.25, bh * 0.06);
      if (rnd(seed + 3) > 0.75) {
        c.fillRect(x + bw * 0.7, top - bh * 0.18, 3, bh * 0.18);
        if (night) {
          const blink = Math.sin(t * 3 + seed) > 0.3;
          c.fillStyle = blink ? "#ff4a5a" : "#5a1a22";
          c.fillRect(x + bw * 0.7 - 2, top - bh * 0.18 - 4, 7, 7);
        }
      }
      // Windows.
      const cols = Math.max(2, Math.floor(bw / 18));
      const rows = Math.floor(bh / 26);
      for (let r = 1; r < rows; r++)
        for (let q = 0; q < cols; q++) {
          const wseed = seed * 97 + r * 13 + q;
          if (rnd(wseed) > L.win) continue;
          const lit = night ? (rnd(wseed + 5) > 0.25 ? "#ffd27a" : "#9ad4ff") : "#ffcf9a";
          c.globalAlpha = L.a * (0.4 + rnd(wseed + 9) * 0.6);
          c.fillStyle = lit;
          c.fillRect(x + 6 + q * ((bw - 10) / cols), top + r * 26, (bw - 10) / cols - 6, 12);
          c.globalAlpha = 1;
        }
      x += bw + rnd(seed + 4) * 10;
    }
  });
  if (b.type === "night-rooftop") {
    // Rooftop floor and railing in the foreground.
    const fy = H * 0.86;
    const fl = c.createLinearGradient(0, fy, 0, H);
    fl.addColorStop(0, "#1d1a2c");
    fl.addColorStop(1, "#0b0a12");
    c.fillStyle = fl;
    c.fillRect(0, fy, W, H - fy);
    c.fillStyle = "#2c2842";
    c.fillRect(0, fy - 6, W, 8);
    // Railing.
    const ry = H * 0.74;
    c.strokeStyle = "#3a3556";
    c.lineWidth = 7;
    c.beginPath();
    c.moveTo(0, ry);
    c.lineTo(W, ry);
    c.moveTo(0, ry + 60);
    c.lineTo(W, ry + 60);
    c.stroke();
    c.lineWidth = 6;
    for (let x = -((cam * 0.9) % 90); x < W; x += 90) {
      c.beginPath();
      c.moveTo(x, ry - 4);
      c.lineTo(x, fy);
      c.stroke();
    }
    // Moonlight sheen on the rail.
    c.strokeStyle = "rgba(180,170,255,0.35)";
    c.lineWidth = 2;
    c.beginPath();
    c.moveTo(0, ry - 3);
    c.lineTo(W, ry - 3);
    c.stroke();
  }
  // Atmospheric depth at the bottom.
  const fog = c.createLinearGradient(0, H * 0.55, 0, H);
  fog.addColorStop(0, "rgba(0,0,0,0)");
  fog.addColorStop(1, night ? "rgba(8,6,20,0.55)" : "rgba(30,10,30,0.45)");
  c.fillStyle = fog;
  c.fillRect(0, H * 0.55, W, H * 0.45);
}

/* ------------------------------- river scenes ------------------------------- */

interface Light {
  x: number;
  y: number;
  col: string;
  a: number;
}

function skyGrad(c: Ctx, W: number, H: number, horizon: number, dusk: boolean) {
  const g = c.createLinearGradient(0, 0, 0, horizon);
  if (dusk) {
    g.addColorStop(0, "#0e1330");
    g.addColorStop(0.45, "#2b2b5c");
    g.addColorStop(0.78, "#8a4a6a");
    g.addColorStop(1, "#f08a5a");
  } else {
    g.addColorStop(0, "#05060f");
    g.addColorStop(0.7, "#141a3a");
    g.addColorStop(1, "#2a2d55");
  }
  c.fillStyle = g;
  c.fillRect(0, 0, W, horizon + 2);
}

/** City skyline standing on `base`; returns lit windows so water can reflect them. */
function cityBlock(c: Ctx, x0: number, x1: number, base: number, H: number, scale: number, seed: number, t: number, dusk: boolean, depth: number): Light[] {
  const lights: Light[] = [];
  const rnd = (i: number) => hash(i * 13.7 + seed * 101);
  let x = x0;
  let i = 0;
  const shade = dusk ? ["#3a2f55", "#271f40", "#17132a"][depth]! : ["#1e2140", "#141631", "#0b0c1d"][depth]!;
  while (x < x1) {
    const sd = seed * 1000 + i++;
    const bw = (50 + rnd(sd) * 110) * scale;
    const bh = (0.1 + Math.pow(rnd(sd + 1), 1.6) * 0.38) * H * scale;
    const top = base - bh;
    // Body with a subtle vertical gradient and a lit edge facing the sunset.
    const g = c.createLinearGradient(0, top, 0, base);
    g.addColorStop(0, shadeMix(shade, dusk ? "#f08a5a" : "#5060a0", 0.12 / (depth + 1)));
    g.addColorStop(1, shade);
    c.fillStyle = g;
    c.fillRect(x, top, bw, bh + 2);
    c.fillStyle = dusk ? "rgba(255,170,120,0.18)" : "rgba(140,150,255,0.12)";
    c.fillRect(x + bw - 3 * scale, top, 3 * scale, bh);
    if (rnd(sd + 2) > 0.55) {
      c.fillStyle = shade;
      c.fillRect(x + bw * 0.3, top - bh * 0.07, bw * 0.3, bh * 0.07);
    }
    if (rnd(sd + 3) > 0.7) {
      c.fillStyle = shade;
      c.fillRect(x + bw * 0.5, top - bh * 0.22, 2.5 * scale, bh * 0.22);
      const on = Math.sin(t * 2.5 + sd) > 0;
      c.fillStyle = on ? "#ff3b4e" : "#4a1218";
      c.beginPath();
      c.arc(x + bw * 0.5 + scale, top - bh * 0.22, 3 * scale, 0, Math.PI * 2);
      c.fill();
    }
    // Windows: rows of warm and cool lights.
    const cw = 9 * scale;
    const chh = 12 * scale;
    const cols = Math.max(1, Math.floor((bw - 8 * scale) / (cw + 6 * scale)));
    const rows = Math.floor((bh - 10 * scale) / (chh + 9 * scale));
    const density = dusk ? 0.32 : 0.45;
    for (let r = 0; r < rows; r++)
      for (let q = 0; q < cols; q++) {
        const ws = sd * 97 + r * 13 + q;
        if (rnd(ws) > density) continue;
        const wx = x + 6 * scale + q * (cw + 6 * scale);
        const wy = top + 10 * scale + r * (chh + 9 * scale);
        const col = rnd(ws + 5) > 0.3 ? "#ffcf7a" : "#a9d8ff";
        const a = (0.35 + rnd(ws + 9) * 0.6) * (1 - depth * 0.2);
        c.globalAlpha = a;
        c.fillStyle = col;
        c.fillRect(wx, wy, cw, chh);
        c.globalAlpha = 1;
        if (rnd(ws + 11) > 0.6) lights.push({ x: wx + cw / 2, y: wy, col, a });
      }
    x += bw + (2 + rnd(sd + 4) * 14) * scale;
  }
  return lights;
}

/** River surface between y0 and y1 with reflections of the given lights, shimmering over time. */
function riverSurface(c: Ctx, W: number, y0: number, y1: number, lights: Light[], mirrorY: number, t: number, dusk: boolean, seed: number) {
  const g = c.createLinearGradient(0, y0, 0, y1);
  g.addColorStop(0, dusk ? "#3b2f55" : "#121633");
  g.addColorStop(0.15, dusk ? "#22223f" : "#0b0e24");
  g.addColorStop(1, dusk ? "#0c0d1c" : "#04050c");
  c.fillStyle = g;
  c.fillRect(0, y0, W, y1 - y0);
  // Sunset glow on the water near the horizon.
  if (dusk) {
    const sg = c.createRadialGradient(W * 0.55, y0, 0, W * 0.55, y0, W * 0.7);
    sg.addColorStop(0, "rgba(255,140,90,0.35)");
    sg.addColorStop(1, "rgba(255,140,90,0)");
    c.fillStyle = sg;
    c.fillRect(0, y0, W, (y1 - y0) * 0.6);
  }
  // Reflected lights: broken vertical streaks that wobble.
  c.save();
  c.globalCompositeOperation = "lighter";
  for (let i = 0; i < lights.length; i++) {
    const L = lights[i]!;
    const ry = mirrorY + (mirrorY - L.y);
    if (ry < y0 || ry > y1) continue;
    const len = (y1 - ry) * 0.5 + 30;
    const segs = 9;
    for (let k = 0; k < segs; k++) {
      const yy = ry + (k / segs) * len;
      if (yy > y1) break;
      const wob = Math.sin(t * 2.2 + yy * 0.05 + i) * (2 + k * 1.2);
      const w = 4 + k * 0.8;
      c.globalAlpha = L.a * 0.32 * (1 - k / segs) * (0.6 + 0.4 * Math.sin(t * 3 + i * 1.7 + k));
      c.fillStyle = L.col;
      c.fillRect(L.x + wob - w / 2, yy, w, len / segs * 0.55);
    }
  }
  c.restore();
  // Ripple lines drifting downstream.
  c.save();
  const n = 46;
  for (let i = 0; i < n; i++) {
    const r = hash(i * 7.3 + seed);
    const yy = y0 + Math.pow(r, 1.7) * (y1 - y0);
    const depth = (yy - y0) / (y1 - y0);
    const len = 30 + depth * 260 * hash(i + 3);
    const xx = ((hash(i * 3.1 + seed) * W * 1.4 + t * (12 + depth * 40)) % (W * 1.4)) - W * 0.2;
    c.globalAlpha = 0.08 + 0.18 * depth;
    c.strokeStyle = dusk ? "#c99ab8" : "#7c86c8";
    c.lineWidth = 1 + depth * 2.5;
    c.beginPath();
    c.moveTo(xx, yy);
    c.quadraticCurveTo(xx + len / 2, yy - 2 - depth * 3, xx + len, yy);
    c.stroke();
  }
  c.restore();
}

function streetLamp(c: Ctx, x: number, base: number, h: number, t: number, i: number) {
  c.strokeStyle = "#141522";
  c.lineWidth = Math.max(2, h * 0.035);
  c.beginPath();
  c.moveTo(x, base);
  c.lineTo(x, base - h);
  c.quadraticCurveTo(x, base - h * 1.08, x + h * 0.14, base - h * 1.06);
  c.stroke();
  const lx = x + h * 0.14;
  const ly = base - h * 1.03;
  const glow = c.createRadialGradient(lx, ly, 0, lx, ly, h * 0.9);
  const fl = 0.85 + 0.15 * Math.sin(t * 7 + i * 3);
  glow.addColorStop(0, `rgba(255,214,150,${0.55 * fl})`);
  glow.addColorStop(1, "rgba(255,214,150,0)");
  c.save();
  c.globalCompositeOperation = "lighter";
  c.fillStyle = glow;
  c.fillRect(lx - h, ly - h, h * 2, h * 2);
  c.restore();
  c.fillStyle = "#fff1cf";
  c.beginPath();
  c.ellipse(lx, ly, h * 0.05, h * 0.03, 0, 0, Math.PI * 2);
  c.fill();
}

function railing(c: Ctx, W: number, top: number, bottom: number, cam: number, dusk: boolean) {
  const rail = Math.max(10, (bottom - top) * 0.09);
  // Balusters.
  c.fillStyle = "#121320";
  const step = (bottom - top) * 0.32;
  for (let x = -((cam * 1.2) % step); x < W + step; x += step) {
    c.fillRect(x - rail * 0.35, top, rail * 0.7, bottom - top);
  }
  // Mid rail and top rail with a metallic sheen.
  c.fillRect(0, top + (bottom - top) * 0.55, W, rail * 0.5);
  const g = c.createLinearGradient(0, top - rail * 0.6, 0, top + rail);
  g.addColorStop(0, dusk ? "#b9879a" : "#6c74b8");
  g.addColorStop(0.3, "#2a2a3d");
  g.addColorStop(1, "#0b0b13");
  c.fillStyle = g;
  c.fillRect(0, top - rail * 0.6, W, rail * 1.6);
  // Concrete parapet at the bottom.
  const pg = c.createLinearGradient(0, bottom, 0, bottom + rail * 4);
  pg.addColorStop(0, "#2a2838");
  pg.addColorStop(1, "#0f0e18");
  c.fillStyle = pg;
  c.fillRect(0, bottom, W, rail * 4);
}

function drawRiverScene(c: Ctx, l: AnimLayer, s: AnimScene, f: number, b: BackdropSpec): void {
  const W = s.width;
  const H = s.height;
  const t = f / s.fps;
  const cam = valueAt(l.chan?.camX, f, 0);
  const dusk = (b.time ?? "dusk") === "dusk";
  if (b.type === "railing") {
    const top = H * (b.y ?? 0.62);
    railing(c, W, top, top + H * 0.2, cam, dusk);
    return;
  }
  if (b.type === "river-pov") {
    // Looking straight down from the railing at moving water.
    const g = c.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0, dusk ? "#3e3a66" : "#141a3a");
    g.addColorStop(0.5, dusk ? "#2a2c52" : "#0c1130");
    g.addColorStop(1, dusk ? "#141633" : "#05071a");
    c.fillStyle = g;
    c.fillRect(0, 0, W, H);
    // The sky's glow mirrored on the surface: one big soft warm reflection that drifts.
    c.save();
    c.globalCompositeOperation = "lighter";
    const rx = W * 0.5 + Math.sin(t * 0.4) * 40;
    const ry = H * 0.34 + Math.sin(t * 0.3) * 20;
    c.translate(rx, ry);
    c.scale(1, 0.55);
    const rg = c.createRadialGradient(0, 0, 0, 0, 0, W * 0.75);
    rg.addColorStop(0, dusk ? "rgba(255,150,110,0.32)" : "rgba(130,140,255,0.2)");
    rg.addColorStop(0.5, dusk ? "rgba(200,110,140,0.12)" : "rgba(90,100,200,0.08)");
    rg.addColorStop(1, "rgba(0,0,0,0)");
    c.fillStyle = rg;
    c.beginPath();
    c.arc(0, 0, W * 0.75, 0, Math.PI * 2);
    c.fill();
    c.restore();
    // Flowing wave lines: brighter where they cross the reflection.
    c.save();
    c.lineCap = "round";
    for (let i = 0; i < 70; i++) {
      const y0 = ((i / 70) * H * 1.1 + t * 32) % (H * 1.1) - H * 0.05;
      const near = Math.exp(-Math.pow((y0 - ry) / (H * 0.25), 2));
      c.strokeStyle = dusk ? `rgba(255,${170 + near * 50},${150 + near * 30},${0.05 + near * 0.22})` : `rgba(150,160,255,${0.04 + near * 0.15})`;
      c.lineWidth = 1.5 + near * 2.5;
      c.beginPath();
      const x0 = hash(i * 2.3) * W * 0.6 - W * 0.1;
      const len = W * (0.25 + hash(i * 5.1) * 0.6);
      for (let k = 0; k <= 24; k++) {
        const x = x0 + (k / 24) * len;
        const y = y0 + Math.sin(x * 0.012 + t * 1.8 + i) * 7 + Math.sin(x * 0.031 - t * 1.1 + i * 2) * 3;
        if (k === 0) c.moveTo(x, y);
        else c.lineTo(x, y);
      }
      c.stroke();
    }
    c.restore();
    // City lights reflected as dancing glints.
    c.save();
    c.globalCompositeOperation = "lighter";
    for (let i = 0; i < 55; i++) {
      const x = hash(i * 5.1 + b.seed) * W;
      const y = (hash(i * 9.7 + b.seed) * H * 1.2 + t * (28 + hash(i) * 20)) % (H * 1.2) - H * 0.1;
      const col = hash(i + 2) > 0.35 ? "255,205,130" : "160,205,255";
      const w = 16 + hash(i + 4) * 36;
      const a = 0.1 + 0.2 * (0.5 + 0.5 * Math.sin(t * 3 + i));
      for (let k = 0; k < 6; k++) {
        const wob = Math.sin(t * 2.4 + i + k * 1.3) * (8 + k * 3);
        const gw = w * (1 - k * 0.12) * (0.6 + 0.4 * Math.sin(t * 4 + k + i));
        c.fillStyle = `rgba(${col},${a * (1 - k / 6)})`;
        c.beginPath();
        c.ellipse(x + wob, y + k * 13, Math.max(2, gw / 2), 2.2, 0, 0, Math.PI * 2);
        c.fill();
      }
    }
    for (let i = 0; i < 120; i++) {
      const x = hash(i * 3.3 + 7) * W;
      const y = (hash(i * 4.1 + 9) * H + t * 40) % H;
      const a = Math.max(0, Math.sin(t * 6 + i * 2.1)) * 0.45;
      c.fillStyle = `rgba(255,240,220,${a})`;
      c.beginPath();
      c.ellipse(x, y, 4, 1.4, 0, 0, Math.PI * 2);
      c.fill();
    }
    c.restore();
    // Ripple arcs.
    c.strokeStyle = dusk ? "rgba(220,170,200,0.12)" : "rgba(140,150,220,0.1)";
    c.lineWidth = 2;
    for (let i = 0; i < 40; i++) {
      const x = hash(i * 2.9) * W;
      const y = (hash(i * 6.7) * H + t * 35) % H;
      const r = 30 + hash(i * 1.3) * 120;
      c.beginPath();
      c.ellipse(x, y, r, r * 0.35, 0, Math.PI * 1.05, Math.PI * 1.95);
      c.stroke();
    }
    // The bridge edge at the bottom of the view and his shadow on the water.
    const ed = H * 0.86;
    const sh = c.createRadialGradient(W * 0.52, H * 0.55, 0, W * 0.52, H * 0.55, W * 0.25);
    sh.addColorStop(0, "rgba(0,0,0,0.35)");
    sh.addColorStop(1, "rgba(0,0,0,0)");
    c.fillStyle = sh;
    c.fillRect(0, 0, W, H);
    const eg = c.createLinearGradient(0, ed, 0, H);
    eg.addColorStop(0, "#3a3550");
    eg.addColorStop(0.15, "#1d1b2b");
    eg.addColorStop(1, "#08080f");
    c.fillStyle = eg;
    c.beginPath();
    c.moveTo(0, ed + 40);
    c.lineTo(W, ed - 10);
    c.lineTo(W, H);
    c.lineTo(0, H);
    c.closePath();
    c.fill();
    c.strokeStyle = dusk ? "rgba(255,180,150,0.45)" : "rgba(150,160,255,0.4)";
    c.lineWidth = 3;
    c.beginPath();
    c.moveTo(0, ed + 40);
    c.lineTo(W, ed - 10);
    c.stroke();
    // Vignette.
    const v2 = c.createRadialGradient(W / 2, H / 2, H * 0.25, W / 2, H / 2, H * 0.75);
    v2.addColorStop(0, "rgba(0,0,0,0)");
    v2.addColorStop(1, "rgba(0,0,0,0.6)");
    c.fillStyle = v2;
    c.fillRect(0, 0, W, H);
    return;
  }
  const wide = b.type === "river-bridge";
  const horizon = H * (b.y ?? (wide ? 0.47 : 0.56));
  skyGrad(c, W, H, horizon, dusk);
  // Stars and a low sun / moon glow.
  for (let i = 0; i < (dusk ? 40 : 120); i++) {
    c.fillStyle = `rgba(255,255,255,${(0.2 + hash(i + 300) * 0.5) * (dusk ? 0.6 : 1)})`;
    c.fillRect(hash(i) * W, hash(i + 500) * horizon * 0.5, 1.8, 1.8);
  }
  const sunX = W * 0.58 - cam * 0.05;
  const sunG = c.createRadialGradient(sunX, horizon, 0, sunX, horizon, W * 0.6);
  sunG.addColorStop(0, dusk ? "rgba(255,170,100,0.55)" : "rgba(160,150,255,0.25)");
  sunG.addColorStop(1, "rgba(0,0,0,0)");
  c.fillStyle = sunG;
  c.fillRect(0, 0, W, horizon);
  // Thin clouds catching the light.
  for (let i = 0; i < 6; i++) {
    const y = horizon * (0.25 + i * 0.1);
    const x = ((hash(i + 40) * W * 1.6 + t * (6 + i * 3)) % (W * 1.6)) - W * 0.3;
    c.save();
    c.translate(x, y);
    c.scale(1, 0.12);
    const cg = c.createRadialGradient(0, 0, 0, 0, 0, W * 0.4);
    cg.addColorStop(0, dusk ? "rgba(255,160,150,0.28)" : "rgba(120,120,200,0.15)");
    cg.addColorStop(1, "rgba(0,0,0,0)");
    c.fillStyle = cg;
    c.beginPath();
    c.arc(0, 0, W * 0.4, 0, Math.PI * 2);
    c.fill();
    c.restore();
  }
  // Skylines on the far bank (three depths).
  const lights: Light[] = [];
  const sc = wide ? 1 : 0.8;
  lights.push(...cityBlock(c, -60 - cam * 0.05, W + 60, horizon, H, sc * 0.65, b.seed + 1, t, dusk, 0));
  lights.push(...cityBlock(c, -80 - cam * 0.12, W + 80, horizon + 2, H, sc * 0.85, b.seed + 2, t, dusk, 1));
  if (wide) {
    // Near banks left and right frame the river.
    lights.push(...cityBlock(c, -40, W * 0.28, horizon + H * 0.04, H, 1.25, b.seed + 3, t, dusk, 2));
    lights.push(...cityBlock(c, W * 0.74, W + 40, horizon + H * 0.04, H, 1.25, b.seed + 4, t, dusk, 2));
  }
  // Haze over the water line.
  const hz = c.createLinearGradient(0, horizon - H * 0.08, 0, horizon + H * 0.02);
  hz.addColorStop(0, "rgba(0,0,0,0)");
  hz.addColorStop(1, dusk ? "rgba(255,150,130,0.25)" : "rgba(80,90,170,0.2)");
  c.fillStyle = hz;
  c.fillRect(0, horizon - H * 0.08, W, H * 0.1);
  riverSurface(c, W, horizon, H, lights, horizon, t, dusk, b.seed);
  if (wide) {
    // The bridge spanning the frame: deck, arches, cables, lamps.
    const deck = H * 0.6;
    const dh = H * 0.018;
    c.fillStyle = "#0d0d17";
    for (let i = 0; i < 4; i++) {
      const ax = (i + 0.5) * (W / 4);
      c.beginPath();
      c.ellipse(ax, deck + dh, W / 8.4, H * 0.07, 0, Math.PI, 0);
      c.lineTo(ax + W / 8, deck + H * 0.12);
      c.lineTo(ax - W / 8, deck + H * 0.12);
      c.closePath();
      c.fill();
    }
    // Arch openings show water.
    c.save();
    c.globalCompositeOperation = "destination-out";
    for (let i = 0; i < 4; i++) {
      const ax = (i + 0.5) * (W / 4);
      c.beginPath();
      c.ellipse(ax, deck + H * 0.12, W / 9.5, H * 0.09, 0, Math.PI, 0);
      c.fill();
    }
    c.restore();
    c.fillStyle = "#0d0d17";
    c.fillRect(0, deck, W, dh * 1.4);
    // Arch reflections in the water.
    c.fillStyle = "rgba(10,10,20,0.6)";
    c.fillRect(0, deck + H * 0.12, W, H * 0.03);
    // Deck rail.
    c.strokeStyle = "#1a1a28";
    c.lineWidth = 3;
    c.beginPath();
    c.moveTo(0, deck - H * 0.022);
    c.lineTo(W, deck - H * 0.022);
    c.stroke();
    for (let x = 0; x < W; x += 18) {
      c.beginPath();
      c.moveTo(x, deck - H * 0.022);
      c.lineTo(x, deck);
      c.stroke();
    }
    for (let i = 0; i < 6; i++) streetLamp(c, (i + 0.5) * (W / 6), deck, H * 0.045, t, i);
    // Lamp reflections.
    c.save();
    c.globalCompositeOperation = "lighter";
    for (let i = 0; i < 6; i++) {
      const x = (i + 0.5) * (W / 6) + H * 0.006;
      for (let k = 0; k < 10; k++) {
        const yy = deck + H * 0.16 + k * H * 0.025;
        c.fillStyle = `rgba(255,200,130,${0.25 * (1 - k / 10) * (0.6 + 0.4 * Math.sin(t * 3 + k + i))})`;
        c.fillRect(x + Math.sin(t * 2 + k + i) * (2 + k), yy, 6, H * 0.012);
      }
    }
    c.restore();
  } else {
    // Bridge deck behind him: lamp posts along the far rail.
    for (let i = 0; i < 4; i++) streetLamp(c, ((i + 0.3) * W) / 3.3 - ((cam * 0.6) % (W / 3.3)), horizon + H * 0.02, H * 0.16, t, i);
  }
  // Atmosphere.
  const fog = c.createLinearGradient(0, horizon, 0, H);
  fog.addColorStop(0, "rgba(0,0,0,0)");
  fog.addColorStop(1, "rgba(5,5,12,0.45)");
  c.fillStyle = fog;
  c.fillRect(0, horizon, W, H - horizon);
}

/* ------------------------------ action library ----------------------------- */

export type Expression = "neutral" | "smirk" | "cool" | "angry" | "surprised" | "smile" | "sad" | "closed" | "laugh" | "serious" | "fierce" | "shout" | "hurt";

export const EXPRESSIONS: Record<Expression, Record<string, number>> = {
  neutral: { lid: 0.12, browL: 0, browR: 0, mouthSmile: 0, mouthSmirk: 0, mouthOpen: 0, mouthRound: 0, blink: -1 },
  cool: { lid: 0.42, browL: -0.2, browR: -0.1, mouthSmile: 0, mouthSmirk: 0.2, mouthOpen: 0, mouthRound: 0, blink: -1 },
  smirk: { lid: 0.3, browL: -0.1, browR: 0.25, mouthSmile: 0.2, mouthSmirk: 0.85, mouthOpen: 0, mouthRound: 0, blink: -1 },
  angry: { lid: 0.2, browL: -1, browR: -1, mouthSmile: -0.5, mouthSmirk: 0, mouthOpen: 0.1, mouthRound: 0, blink: -1 },
  surprised: { lid: 0, browL: 1, browR: 1, mouthSmile: 0, mouthSmirk: 0, mouthOpen: 0.45, mouthRound: 0.7, blink: -1 },
  smile: { lid: 0.18, browL: 0.3, browR: 0.3, mouthSmile: 0.8, mouthSmirk: 0, mouthOpen: 0, mouthRound: 0, blink: -1 },
  sad: { lid: 0.3, browL: 0.7, browR: 0.7, mouthSmile: -0.6, mouthSmirk: 0, mouthOpen: 0, mouthRound: 0, blink: -1 },
  closed: { lid: 0.12, blink: 1, browL: 0.15, browR: 0.15, mouthSmile: 0, mouthSmirk: 0, mouthOpen: 0, mouthRound: 0 },
  laugh: { lid: 0.12, blink: 1, browL: 0.5, browR: 0.5, mouthSmile: 1, mouthSmirk: 0, mouthOpen: 0.6, mouthRound: 0 },
  serious: { lid: 0.25, browL: -0.45, browR: -0.45, mouthSmile: -0.1, mouthSmirk: 0, mouthOpen: 0, mouthRound: 0, blink: -1 },
  fierce: { lid: 0.18, browL: -1, browR: -1, mouthSmile: -0.35, mouthSmirk: 0.15, mouthOpen: 0.12, mouthRound: 0, blink: -1 },
  shout: { lid: 0.05, browL: -1, browR: -1, mouthSmile: -0.4, mouthSmirk: 0, mouthOpen: 0.85, mouthRound: 0.15, blink: -1 },
  hurt: { lid: 0.12, blink: 1, browL: 0.9, browR: 0.9, mouthSmile: -0.8, mouthSmirk: 0, mouthOpen: 0.35, mouthRound: 0 },
};

/** Hand placements relative to the shoulders (head units, torso space). */
export const STANCES: Record<string, Record<string, number>> = {
  relaxed: { handLx: -0.2, handLy: 2.5, handLpose: 0, handRx: 0.2, handRy: 2.5, handRpose: 0, kneeDir: 0, bodyTurn: 0 },
  pocket: { handLx: 0.28, handLy: 2.42, handLpose: 4, handRx: 0.2, handRy: 2.5, handRpose: 0 },
  pockets: { handLx: 0.28, handLy: 2.42, handLpose: 4, handRx: -0.28, handRy: 2.42, handRpose: 4 },
  hip: { handLx: 0.02, handLy: 1.62, handLpose: 3, handLrot: 70, handRx: 0.2, handRy: 2.5, handRpose: 0 },
  tpose: { handLx: -2.55, handLy: 0.08, handLpose: 1, handLrot: 0, handRx: 2.55, handRy: 0.08, handRpose: 1, handRrot: 0 },
  guard: { handLx: 0.95, handLy: 0.75, handLpose: 3, handLrot: -20, handRx: 1.3, handRy: 0.55, handRpose: 3, handRrot: -30, footLx: -0.62, footLy: 3.55, footRx: 0.5, footRy: 3.55, rootY: 0.22, kneeDir: 1, bodyTurn: 0.85, lean: 5, headNod: -0.15, headTurn: 0.1 },
  crossed: { handLx: 1.35, handLy: 0.95, handLpose: 3, handRx: -1.3, handRy: 0.85, handRpose: 3 },
};

/**
 * Fight moves, written for a character facing screen-right (+x = toward the opponent); a character with
 * facing -1 is mirrored so the same moves point left. Each move is [time, pose, ease] keys and ends in guard.
 */
type MoveKey = [number, Record<string, number>, Ease?];
const G = STANCES.guard!;
const MOVES: Record<string, (o: { dist: number; height: number; force: number }) => MoveKey[]> = {
  // Lead (far, right) hand: tiny load, snap out with the shoulder, snap back.
  jab: () => [
    [0.06, { handRx: 0.95, handRy: 0.7, lean: 2, bodyTurn: 0.9 }, "in"],
    [0.13, { handRx: 2.55, handRy: -0.08, handRrot: 0, lean: 13, rootX: 0.3, bodyTurn: 1, headTurn: 0.2, smearR: 1 }, "out"],
    [0.2, { handRx: 2.5, handRy: -0.06, lean: 12, smearR: 0 }],
    [0.42, { handRx: G.handRx!, handRy: G.handRy!, handRrot: G.handRrot!, lean: G.lean!, rootX: 0, bodyTurn: G.bodyTurn!, headTurn: G.headTurn! }],
  ],
  // Rear (near, left) hand: load the hip, rotate square through the punch, step in.
  cross: () => [
    [0.1, { handLx: 0.5, handLy: 1.0, lean: -2, bodyTurn: 1, rootX: -0.15, rootY: G.rootY! + 0.15 }, "in"],
    [0.21, { handLx: 3.05, handLy: -0.05, handLrot: 0, lean: 17, hipTilt: 7, bodyTurn: 0.25, rootX: 0.55, rootY: G.rootY!, footLx: -0.25, smearL: 1, handRx: 0.7, handRy: 0.6 }, "out"],
    [0.32, { handLx: 3.0, handLy: -0.03, lean: 16, rootX: 0.55, smearL: 0 }],
    [0.62, { handLx: G.handLx!, handLy: G.handLy!, handLrot: G.handLrot!, lean: G.lean!, hipTilt: 0, rootX: 0, bodyTurn: G.bodyTurn!, footLx: G.footLx!, handRx: G.handRx!, handRy: G.handRy! }],
  ],
  // Left hook: wide arc at head height.
  hook: () => [
    [0.1, { handLx: -0.3, handLy: 0.7, bodyTurn: 1, lean: 4 }, "in"],
    [0.17, { handLx: 1.3, handLy: -1.1, bodyTurn: 0.55, lean: 10, smearL: 1 }, "linear"],
    [0.24, { handLx: 2.45, handLy: -0.45, bodyTurn: 0.2, lean: 14, rootX: 0.35, hipTilt: 9 }, "out"],
    [0.34, { handLx: 2.3, handLy: -0.3, smearL: 0 }],
    [0.64, { handLx: G.handLx!, handLy: G.handLy!, bodyTurn: G.bodyTurn!, lean: G.lean!, rootX: 0, hipTilt: 0 }],
  ],
  uppercut: () => [
    [0.14, { rootY: 1.05, handRx: 0.8, handRy: 1.8, lean: 12, bodyTurn: 1 }, "in"],
    [0.26, { rootY: -0.45, handRx: 1.15, handRy: -1.95, handRrot: -10, lean: -9, headNod: 0.45, footRy: 3.2, bodyTurn: 0.5, smearR: 1 }, "out"],
    [0.42, { rootY: -0.45, handRy: -2.0, headNod: 0.45, smearR: 0 }],
    [0.7, { rootY: G.rootY!, handRx: G.handRx!, handRy: G.handRy!, handRrot: G.handRrot!, lean: G.lean!, headNod: G.headNod!, footRy: G.footRy!, bodyTurn: G.bodyTurn! }],
  ],
  // Lead-leg roundhouse: chamber, whip out high, re-chamber, plant.
  kick: () => [
    [0.12, { footRx: 0.7, footRy: 1.55, lean: -10, rootY: 0.05, handLx: 0.7, handLy: 0.3, bodyTurn: 1 }, "out"],
    [0.21, { footRx: 3.0, footRy: 0.85, lean: -26, handLx: 0.2, handLy: 1.3, handRx: -0.6, handRy: 1.6, smearK: 1 }, "out"],
    [0.36, { footRx: 2.95, footRy: 0.9, lean: -25, smearK: 0 }],
    [0.5, { footRx: 0.8, footRy: 1.8, lean: -10 }],
    [0.68, { footRx: G.footRx!, footRy: G.footRy!, lean: G.lean!, rootY: G.rootY!, handLx: G.handLx!, handLy: G.handLy!, handRx: G.handRx!, handRy: G.handRy!, bodyTurn: G.bodyTurn! }],
  ],
  block: () => [
    [0.06, { handLx: 1.15, handLy: -0.35, handRx: 0.85, handRy: -0.55, handLrot: -60, handRrot: -50, lean: -9, rootX: -0.3, headNod: 0.35, blink: 1, bodyTurn: 0.95 }, "out"],
    [0.3, { handLx: 1.15, handLy: -0.35, handRx: 0.85, handRy: -0.55, lean: -7, rootX: -0.35, blink: 1 }],
    [0.52, { handLx: G.handLx!, handLy: G.handLy!, handRx: G.handRx!, handRy: G.handRy!, handLrot: G.handLrot!, handRrot: G.handRrot!, lean: G.lean!, rootX: -0.35, headNod: G.headNod!, blink: -1, bodyTurn: G.bodyTurn! }],
  ],
  dodge: () => [
    [0.1, { rootY: 1.2, lean: -18, rootX: -0.45, headTilt: -10 }, "out"],
    [0.32, { rootY: 1.15, lean: -16, rootX: -0.45 }],
    [0.52, { rootY: G.rootY!, lean: G.lean!, rootX: -0.3, headTilt: 0 }],
  ],
  // Burst forward: crouch, lean into a low run, slide to a stop.
  dash: (o) => [
    [0.08, { lean: 8, rootY: 0.7, rootX: -0.15, handLx: 0.3, handLy: 1.2 }, "in"],
    [0.14, { lean: 28, handLx: -1.2, handLy: 1.8, handRx: -0.4, handRy: 2.1, rootY: 0.75, wind: 1.6 }, "in"],
    [0.32, { rootX: o.dist, lean: 26, footLx: -1.1, footRx: 0.9 }, "out"],
    [0.48, { lean: G.lean!, handLx: G.handLx!, handLy: G.handLy!, handRx: G.handRx!, handRy: G.handRy!, rootY: G.rootY!, wind: 0.5, footLx: G.footLx!, footRx: G.footRx! }],
  ],
  // Taking a hit to the face: head snaps, body follows, recovers.
  hit: (o) => [
    [0.04, { rootX: -0.6 * o.force, lean: -22 * o.force, headTilt: -20 * o.force, headNod: 0.4, blink: 1, browL: 0.9, browR: 0.9, mouthOpen: 0.5, mouthSmile: -0.8, handLx: -0.9, handLy: 0.8, handRx: 0.4, handRy: 1.7, bodyTurn: 0.4 }, "out"],
    [0.3, { rootX: -0.9 * o.force, lean: -14 * o.force, headTilt: -10 }],
    [0.7, { rootX: -0.9 * o.force, lean: G.lean!, headTilt: 0, headNod: G.headNod!, blink: -1, browL: -1, browR: -1, mouthOpen: 0.1, mouthSmile: -0.4, handLx: G.handLx!, handLy: G.handLy!, handRx: G.handRx!, handRy: G.handRy!, bodyTurn: G.bodyTurn! }],
  ],
  // Gut punch: folds forward.
  hitBody: (o) => [
    [0.05, { rootX: -0.4 * o.force, lean: 24 * o.force, headNod: 0.7, rootY: 0.6, blink: 1, mouthOpen: 0.6, mouthRound: 0.6, handLx: 0.6, handLy: 1.4, handRx: 0.5, handRy: 1.5 }, "out"],
    [0.4, { rootX: -0.55 * o.force, lean: 20 * o.force }],
    [0.8, { rootX: -0.55 * o.force, lean: G.lean!, headNod: G.headNod!, rootY: G.rootY!, blink: -1, mouthOpen: 0.1, mouthRound: 0, handLx: G.handLx!, handLy: G.handLy!, handRx: G.handRx!, handRy: G.handRy! }],
  ],
  fly: (o) => [
    [0.05, { rootRot: -25, headNod: 0.6, blink: 1, mouthOpen: 0.45, browL: 0.9, browR: 0.9, handLx: -1.6, handLy: -0.4, handRx: 1.2, handRy: -0.8, footLy: 2.6, footRy: 3.0, bodyTurn: 0.3 }, "out"],
    [0.4, { rootX: -o.dist * 0.6, rootY: -o.height, footLy: 3.6, footRy: 3.6, rootRot: -80 }, "out"],
    [0.75, { rootX: -o.dist, rootY: 0.9, rootRot: -10 }, "in"],
    [0.9, { rootX: -o.dist, rootY: 1.2, rootRot: 0, footLy: 3.55, footRy: 3.55, footLx: -0.6, footRx: 0.6, handLx: -0.9, handLy: 2.0, handRx: 0.6, handRy: 2.3, headNod: -0.4, blink: 1 }],
  ],
  land: () => [
    [0.06, { rootY: 1.3, lean: 14, handLx: -0.6, handLy: 2.2, handRx: 0.9, handRy: 2.4 }, "out"],
    [0.5, { rootY: G.rootY!, lean: G.lean!, handLx: G.handLx!, handLy: G.handLy!, handRx: G.handRx!, handRy: G.handRy! }],
  ],
  getup: () => [
    [0.3, { rootY: 0.9, headNod: -0.3, blink: -1, footLx: -0.5, footRx: 0.5 }],
    [0.7, { rootY: G.rootY!, headNod: 0.1, lean: 2, footLx: G.footLx!, footRx: G.footRx! }],
  ],
  wipe: () => [
    [0.2, { handRx: -0.85, handRy: -0.05, handRrot: 40, handRpose: 3, headTilt: 6 }],
    [0.35, { handRx: -0.6, handRy: 0.0 }],
    [0.55, { handRx: 1.5, handRy: 0.9, handRrot: -10, headTilt: 0 }, "out"],
    [0.8, { handRx: G.handRx!, handRy: G.handRy!, handRrot: G.handRrot! }],
  ],
  charge: () => [
    [0.3, { rootY: 0.75, lean: 8, handLx: -0.9, handLy: 2.0, handRx: 0.85, handRy: 2.1, shrugL: 0.5, shrugR: 0.5, wind: 1.6, aura: 0.6, bodyTurn: 0 }, "out"],
    [1.0, { rootY: 0.75, aura: 1, wind: 1.8, headNod: -0.2 }],
  ],
  jump: (o) => [
    [0.1, { rootY: 1.0, lean: 8 }, "in"],
    [0.35, { rootY: -o.height, footLy: 2.8, footRy: 2.6, lean: -4 }, "out"],
    [0.6, { rootY: -o.height * 0.9 }],
    [0.8, { rootY: 0.9, footLy: 3.55, footRy: 3.55 }, "in"],
    [1.0, { rootY: G.rootY!, lean: G.lean! }],
  ],
};
export const FIGHT_MOVES = Object.keys(MOVES);

export interface ActionStep {
  at: number; // seconds
  do: string;
  dur?: number;
  [k: string]: unknown;
}

export const ACTIONS = ["stance", "expr", "look", "smoke", "exhale", "nod", "shake", "tilt", "shrug", "wave", "point", "gesture", "lean", "breathe", "turn", "laugh", "set", ...FIGHT_MOVES, "hitstop"] as const;

type Key = (name: string, sec: number, val: number, ease?: Ease) => void;

/** Bake a list of high-level actions into channel keys. Returns the end time in seconds. */
export function applyActions(l: AnimLayer, fps: number, steps: ActionStep[]): number {
  const key: Key = (name, sec, val, ease = "inOut") => setChannelKey(l, name, Math.round(sec * fps), val, ease);
  const at = (name: string, sec: number) => chan(l, name, Math.round(sec * fps));
  let end = 0;
  const sorted = [...steps].sort((a, b) => a.at - b.at);
  for (const st of sorted) {
    const t = st.at;
    const num = (k: string, d: number) => (typeof st[k] === "number" ? (st[k] as number) : d);
    const str = (k: string, d: string) => (typeof st[k] === "string" ? (st[k] as string) : d);
    switch (st.do) {
      case "set": {
        // Raw channel keys: {do:"set", values:{headTilt:5}, ease?}
        const vals = (st.values ?? {}) as Record<string, number>;
        // Ramp from the current value over dur (default 0.25 s) so earlier frames keep their pose.
        const d = num("dur", 0.25);
        for (const [n, val] of Object.entries(vals)) {
          if (d > 0) key(n, t, at(n, t));
          key(n, t + d, Number(val), str("ease", "inOut") as Ease);
        }
        end = Math.max(end, t + d);
        break;
      }
      case "stance": {
        const s = STANCES[str("name", "relaxed")] ?? STANCES.relaxed!;
        const d = num("dur", 0.6);
        // Hands travel on an arc around the shoulder, not a straight line (arms swing, they don't fold).
        for (const side of ["L", "R"] as const) {
          const xn = `hand${side}x`;
          const yn = `hand${side}y`;
          if (!(xn in s) || d < 0.05) continue;
          const x0 = at(xn, t);
          const y0 = at(yn, t);
          const x1 = s[xn]!;
          const y1 = s[yn]!;
          const r0 = Math.hypot(x0, y0);
          const r1 = Math.hypot(x1, y1);
          const a0 = Math.atan2(y0, x0);
          let a1 = Math.atan2(y1, x1);
          if (Math.abs(a1 - a0) < 0.6) continue;
          key(xn, t, x0);
          key(yn, t, y0);
          if (a1 - a0 > Math.PI) a1 -= 2 * Math.PI;
          if (a0 - a1 > Math.PI) a1 += 2 * Math.PI;
          for (const q of [0.33, 0.66]) {
            const r = Math.max(lerp(r0, r1, q), 2.3);
            const a = lerp(a0, a1, q);
            key(xn, t + d * q, Math.cos(a) * r);
            key(yn, t + d * q, Math.sin(a) * r);
          }
        }
        for (const [n, val] of Object.entries(s)) {
          if (n.endsWith("pose")) {
            key(n, t, at(n, t), "hold");
            key(n, t + d * 0.5, val, "hold");
          } else {
            key(n, t, at(n, t));
            key(n, t + d, val);
          }
        }
        end = Math.max(end, t + d);
        break;
      }
      case "expr": {
        const e = EXPRESSIONS[str("name", "neutral") as Expression] ?? EXPRESSIONS.neutral;
        const d = num("dur", 0.25);
        for (const [n, val] of Object.entries(e)) {
          if (n === "blink") {
            key(n, t, at(n, t), "hold");
            key(n, t + d * 0.5, val, "hold");
            continue;
          }
          key(n, t, at(n, t));
          key(n, t + d, val);
        }
        end = Math.max(end, t + d);
        break;
      }
      case "look": {
        const d = num("dur", 0.3);
        key("lookX", t, at("lookX", t));
        key("lookY", t, at("lookY", t));
        key("lookX", t + d, num("x", 0), "out");
        key("lookY", t + d, num("y", 0), "out");
        end = Math.max(end, t + d);
        break;
      }
      case "turn": {
        const d = num("dur", 0.5);
        key("headTurn", t, at("headTurn", t));
        key("headTurn", t + d, num("to", 0));
        end = Math.max(end, t + d);
        break;
      }
      case "tilt": {
        const d = num("dur", 0.5);
        key("headTilt", t, at("headTilt", t));
        key("headTilt", t + d, num("to", 6));
        end = Math.max(end, t + d);
        break;
      }
      case "lean": {
        const d = num("dur", 0.6);
        key("lean", t, at("lean", t));
        key("lean", t + d, num("to", 3));
        end = Math.max(end, t + d);
        break;
      }
      case "nod":
      case "shake": {
        const d = num("dur", 0.7);
        const ch = st.do === "nod" ? "headNod" : "headTurn";
        const amp = num("amount", st.do === "nod" ? 0.6 : 0.35);
        const base = at(ch, t);
        key(ch, t, base);
        key(ch, t + d * 0.25, base - amp, "inOut");
        key(ch, t + d * 0.55, base + amp * 0.5);
        key(ch, t + d, base);
        end = Math.max(end, t + d);
        break;
      }
      case "shrug": {
        const d = num("dur", 0.9);
        for (const n of ["shrugL", "shrugR"]) {
          key(n, t, at(n, t));
          key(n, t + d * 0.35, 1, "out");
          key(n, t + d, 0);
        }
        key("headTilt", t, at("headTilt", t));
        key("headTilt", t + d * 0.35, at("headTilt", t) + 5);
        key("headTilt", t + d, at("headTilt", t));
        end = Math.max(end, t + d);
        break;
      }
      case "wave":
      case "point":
      case "gesture": {
        const side = str("hand", "L") === "R" ? "R" : "L";
        const out = side === "L" ? -1 : 1;
        const d = num("dur", st.do === "wave" ? 2 : 1.4);
        const x0 = at(`hand${side}x`, t);
        const y0 = at(`hand${side}y`, t);
        const p0 = at(`hand${side}pose`, t);
        key(`hand${side}x`, t, x0);
        key(`hand${side}y`, t, y0);
        key(`hand${side}pose`, t, p0, "hold");
        if (st.do === "wave") {
          key(`hand${side}x`, t + 0.35, out * 0.95, "back");
          key(`hand${side}y`, t + 0.35, -1.3, "back");
          key(`hand${side}pose`, t + 0.2, 1, "hold");
          for (let i = 0; i < 4; i++) key(`hand${side}rot`, t + 0.45 + i * 0.25, i % 2 ? 18 * out : -18 * out);
          key(`hand${side}rot`, t + d - 0.3, 0);
        } else if (st.do === "point") {
          key(`hand${side}x`, t + 0.3, out * 1.9, "back");
          key(`hand${side}y`, t + 0.3, 0.2, "back");
          key(`hand${side}pose`, t + 0.15, 5, "hold");
        } else {
          key(`hand${side}x`, t + 0.3, out * 0.55, "out");
          key(`hand${side}y`, t + 0.3, 1.2, "out");
          key(`hand${side}pose`, t + 0.15, 1, "hold");
          key(`hand${side}x`, t + 0.7, out * 0.75);
          key(`hand${side}y`, t + 0.7, 1.05);
        }
        key(`hand${side}x`, t + d - 0.35, at(`hand${side}x`, t + d - 0.35));
        key(`hand${side}x`, t + d, x0);
        key(`hand${side}y`, t + d, y0);
        key(`hand${side}pose`, t + d - 0.15, p0, "hold");
        end = Math.max(end, t + d);
        break;
      }
      case "smoke": {
        // Raise → drag (eyes close, ember flares) → lower → exhale with a cool look.
        const side = l.puppet?.propHand ?? "R";
        const out = side === "L" ? -1 : 1;
        const d = num("dur", 4.2);
        const x0 = at(`hand${side}x`, t);
        const y0 = at(`hand${side}y`, t);
        const s = d / 4.2;
        const hx = `hand${side}x`;
        const hy = `hand${side}y`;
        // Anticipation: a small dip before the lift.
        key(hx, t, x0);
        key(hy, t, y0);
        key(hy, t + 0.18 * s, y0 + 0.08, "out");
        // Up to the lips (target set relative to the shoulder: inward and up).
        key(hx, t + 0.95 * s, -out * 0.92, "inOut");
        key(hy, t + 0.95 * s, -0.12, "inOut");
        key(`hand${side}rot`, t, at(`hand${side}rot`, t));
        key(`hand${side}rot`, t + 0.95 * s, out * -25);
        key("cigToMouth", t + 0.55 * s, 0);
        key("cigToMouth", t + 0.95 * s, 1, "out");
        key("headTilt", t + 0.6 * s, at("headTilt", t));
        key("headTilt", t + 1.0 * s, at("headTilt", t) - out * 4);
        key("headNod", t + 0.7 * s, at("headNod", t));
        key("headNod", t + 1.05 * s, -0.25);
        // Drag.
        key("blink", t + 0.95 * s, at("blink", t + 0.9 * s) < 0 ? 0 : at("blink", t), "hold");
        key("blink", t + 1.0 * s, 0, "inOut");
        key("blink", t + 1.15 * s, 1, "inOut");
        key("drag", t + 1.0 * s, 0);
        key("drag", t + 1.25 * s, 1, "out");
        key("drag", t + 1.75 * s, 1);
        key("drag", t + 2.0 * s, 0);
        key("lid", t + 1.0 * s, at("lid", t));
        key(hx, t + 1.8 * s, -out * 0.95);
        key(hy, t + 1.8 * s, -0.1);
        // Lower to chest height.
        key("cigToMouth", t + 1.85 * s, 1);
        key("cigToMouth", t + 2.25 * s, 0);
        key(hx, t + 2.45 * s, out * -0.1, "inOut");
        key(hy, t + 2.45 * s, 1.35, "inOut");
        key(`hand${side}rot`, t + 2.45 * s, out * 15);
        // Exhale: head lifts, eyes half open, mouth rounds.
        key("blink", t + 2.1 * s, 1);
        key("blink", t + 2.35 * s, 0);
        key("blink", t + 2.4 * s, -1, "hold");
        key("lid", t + 2.35 * s, 0.48);
        key("headNod", t + 2.1 * s, -0.25);
        key("headNod", t + 2.5 * s, 0.35);
        key("headTilt", t + 2.5 * s, at("headTilt", t) + out * 5);
        key("mouthOpen", t + 2.2 * s, 0);
        key("mouthOpen", t + 2.4 * s, 0.28);
        key("mouthRound", t + 2.2 * s, 0);
        key("mouthRound", t + 2.4 * s, 0.85);
        key("exhale", t + 2.3 * s, 0);
        key("exhale", t + 2.45 * s, 1, "out");
        key("exhale", t + 3.4 * s, 0.7);
        key("exhale", t + 3.8 * s, 0);
        key("mouthOpen", t + 3.6 * s, 0.25);
        key("mouthOpen", t + 3.9 * s, 0);
        key("mouthRound", t + 3.9 * s, 0);
        key("headNod", t + 4.1 * s, 0);
        key("headTilt", t + 4.1 * s, at("headTilt", t));
        key(hx, t + d, x0);
        key(hy, t + d, y0);
        key(`hand${side}rot`, t + d, 0);
        end = Math.max(end, t + d);
        break;
      }
      case "exhale": {
        const d = num("dur", 1.4);
        key("mouthOpen", t, at("mouthOpen", t));
        key("mouthRound", t, at("mouthRound", t));
        key("mouthOpen", t + 0.2, 0.28);
        key("mouthRound", t + 0.2, 0.85);
        key("exhale", t, 0);
        key("exhale", t + 0.2, 1, "out");
        key("exhale", t + d, 0);
        key("mouthOpen", t + d, 0);
        key("mouthRound", t + d, 0);
        end = Math.max(end, t + d);
        break;
      }
      case "laugh": {
        const d = num("dur", 1.2);
        key("rootY", t, at("rootY", t));
        for (let i = 0; i < 4; i++) {
          key("rootY", t + 0.15 + i * 0.25, i % 2 ? 0 : 0.06);
          key("shrugL", t + 0.15 + i * 0.25, i % 2 ? 0 : 0.4);
          key("shrugR", t + 0.15 + i * 0.25, i % 2 ? 0 : 0.4);
        }
        key("rootY", t + d, 0);
        key("shrugL", t + d, 0);
        key("shrugR", t + d, 0);
        end = Math.max(end, t + d);
        break;
      }
      case "breathe": {
        key("breath", t, num("amount", 1.6));
        end = Math.max(end, t);
        break;
      }
      case "hitstop": {
        // Freeze for a beat on impact: time is inserted, so the rest of the move plays after the freeze.
        const d = num("dur", 0.1);
        const tf = Math.round(t * fps);
        const df = Math.max(1, Math.round(d * fps));
        for (const n of Object.keys(l.chan ?? {})) {
          const v0 = at(n, t);
          const cur = l.chan![n];
          if (!Array.isArray(cur)) continue;
          l.chan![n] = cur.map((k) => (k.t > tf ? { ...k, t: k.t + df } : k));
          setChannelKey(l, n, tf, v0, "hold");
          setChannelKey(l, n, tf + df, v0, "inOut");
        }
        end = Math.max(end, t + d);
        break;
      }
      default: {
        const mv = MOVES[st.do];
        if (!mv) throw new Error(`Unknown action "${st.do}". Actions: ${ACTIONS.join(", ")}`);
        const keys = mv({ dist: num("dist", 3), height: num("height", 2.5), force: num("force", 1) });
        const sc = num("speed", 1) > 0 ? 1 / num("speed", 1) : 1;
        // Start from the current pose for every channel the move touches.
        const names = new Set(keys.flatMap(([, v]) => Object.keys(v)));
        // Position moves are relative: a jab after a dash starts where the dash ended.
        const baseX = at("rootX", t);
        for (const n of names) key(n, t, at(n, t), n.endsWith("pose") ? "hold" : "inOut");
        for (const [dt, vals, ease] of keys) for (const [n, val] of Object.entries(vals)) key(n, t + dt * sc, n === "rootX" ? baseX + val : val, n.endsWith("pose") ? "hold" : (ease ?? "inOut"));
        end = Math.max(end, t + keys[keys.length - 1]![0] * sc);
      }
    }
  }
  return end;
}

/** Lip sync on a puppet: the mouth opens with the voice envelope (0..1 at envFps). */
export function puppetLipSync(l: AnimLayer, fps: number, envelope: readonly number[], startSec: number, envFps = 30): number {
  const frames = Math.ceil((envelope.length / envFps) * fps);
  const f0 = Math.round(startSec * fps);
  // Clear existing mouth keys in the range.
  for (const n of ["mouthOpen", "mouthRound"]) {
    const a = l.chan?.[n];
    if (Array.isArray(a)) l.chan![n] = a.filter((k) => k.t < f0 || k.t > f0 + frames);
  }
  setChannelKey(l, "mouthOpen", f0, 0, "linear");
  let made = 0;
  for (let i = 0; i < frames; i += 2) {
    const e = envelope[Math.min(envelope.length - 1, Math.floor((i / fps) * envFps))] ?? 0;
    const val = clamp(Math.pow(e, 0.75) * 0.85, 0, 0.75);
    setChannelKey(l, "mouthOpen", f0 + i, val < 0.06 ? 0 : val, "linear");
    // Rounder on louder vowels for variety.
    setChannelKey(l, "mouthRound", f0 + i, e > 0.6 && (i / 2) % 3 === 0 ? 0.4 : 0, "linear");
    made++;
  }
  setChannelKey(l, "mouthOpen", f0 + frames + 1, 0, "inOut");
  setChannelKey(l, "mouthRound", f0 + frames + 1, 0, "inOut");
  return made;
}
