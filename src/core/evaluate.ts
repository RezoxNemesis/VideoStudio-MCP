import { valueAt, ease } from "./keyframes";
import {
  FX_DEFAULTS,
  GRADE_DEFAULTS,
  TRANSFORM_DEFAULTS,
  findAsset,
  sourceTime,
  type BlendMode,
  type ChromaKey,
  type Clip,
  type Fx,
  type Grade,
  type Wheels,
  type Project,
  type ShapeStyle,
  type TextStyle,
  type Transition,
} from "./model";

export type GradeValues = Record<keyof Grade, number>;
export type FxValues = Record<keyof Fx, number>;

export interface ResolvedMask {
  shape: "none" | "rect" | "ellipse";
  x: number;
  y: number;
  w: number;
  h: number;
  feather: number;
  invert: boolean;
}

/** Everything the compositor needs to draw one clip at one instant. Pure data. */
export interface LayerDraw {
  clipId: string;
  pin?: Clip["pin"];
  kind: Clip["kind"];
  assetId?: string;
  sceneId?: string;
  /** Seconds into the media asset, or into the animation scene. */
  srcTime: number;
  x: number;
  y: number;
  scaleX: number;
  scaleY: number;
  rotation: number; // degrees
  anchorX: number;
  anchorY: number;
  opacity: number;
  blend: BlendMode;
  fit: Clip["fit"];
  flipX: boolean;
  flipY: boolean;
  grade: GradeValues;
  chroma?: ChromaKey;
  mask: ResolvedMask;
  text?: TextStyle;
  /** 0..1 progress of the text entrance animation. */
  textProgress: number;
  shape?: ShapeStyle;
  color?: string;
  fx: FxValues;
  wheels?: Wheels;
  duotone?: { dark: string; light: string };
  crop: [number, number, number, number];
  /** Directional (motion) blur in project px: from movement or whip transitions. */
  dirBlur: [number, number];
}

const NO_MASK: ResolvedMask = { shape: "none", x: 0.5, y: 0.5, w: 1, h: 1, feather: 0, invert: false };

/** Smooth deterministic noise in -1..1, for camera shake. */
export function noise1(x: number, seed = 0): number {
  const i = Math.floor(x);
  const f = x - i;
  const h = (n: number) => {
    const s = Math.sin((n + seed * 57.13) * 127.1) * 43758.5453;
    return (s - Math.floor(s)) * 2 - 1;
  };
  const u = f * f * (3 - 2 * f);
  return h(i) * (1 - u) + h(i + 1) * u;
}

function resolveGrade(c: Clip, local: number): GradeValues {
  const out = { ...GRADE_DEFAULTS } as GradeValues;
  for (const k of Object.keys(GRADE_DEFAULTS) as (keyof Grade)[]) out[k] = valueAt(c.grade[k], local, GRADE_DEFAULTS[k]);
  return out;
}

function resolveFx(c: Clip, local: number): FxValues {
  const out = { ...FX_DEFAULTS } as FxValues;
  if (c.fx) for (const k of Object.keys(FX_DEFAULTS) as (keyof Fx)[]) out[k] = valueAt(c.fx[k], local, FX_DEFAULTS[k]);
  return out;
}

function applyTransition(d: LayerDraw, tr: Transition, p: number, dir: "in" | "out", w: number, h: number): void {
  // p runs 0 -> 1 as the clip becomes fully visible.
  const q = ease("inOut", p);
  const r = 1 - q;
  const sgn = dir === "in" ? 1 : -1;
  switch (tr.kind) {
    case "none":
      return;
    case "flash":
      d.grade.exposure += r * r * 4;
      d.opacity *= Math.min(1, q * 3);
      return;
    case "whipLeft":
    case "whipRight": {
      const k = tr.kind === "whipLeft" ? 1 : -1;
      const fast = ease("in", r);
      d.x += sgn * k * fast * w * 1.1;
      d.dirBlur[0] += k * Math.sin(r * Math.PI) * 160;
      return;
    }
    case "zoomBlur":
      d.scaleX *= 1 + r * (dir === "in" ? 0.6 : -0.4);
      d.scaleY *= 1 + r * (dir === "in" ? 0.6 : -0.4);
      d.fx.zoomBlur = Math.max(d.fx.zoomBlur, Math.sin(r * Math.PI * 0.5) * 0.9);
      d.opacity *= Math.min(1, q * 2.5);
      return;
    case "spin":
      d.rotation += sgn * r * r * 220;
      d.scaleX *= 1 - r * 0.7;
      d.scaleY *= 1 - r * 0.7;
      d.opacity *= Math.min(1, q * 2);
      return;
    case "glitch":
      d.fx.glitch = Math.max(d.fx.glitch, r > 0.02 ? 0.4 + r * 0.6 : 0);
      d.fx.rgbSplit += r * 36;
      d.opacity *= q < 0.15 ? q / 0.15 : 1;
      return;
    case "fade":
      d.opacity *= q;
      return;
    case "dip":
      d.grade.exposure -= r * 8;
      d.opacity *= Math.min(1, q * 2);
      return;
    case "blur":
      d.grade.blur += r * 48;
      d.opacity *= q;
      return;
    case "zoomIn":
      d.scaleX *= 1 + r * (dir === "in" ? 0.35 : -0.25);
      d.scaleY *= 1 + r * (dir === "in" ? 0.35 : -0.25);
      d.opacity *= q;
      return;
    case "slideLeft":
      d.x += (dir === "in" ? 1 : -1) * r * w;
      return;
    case "slideRight":
      d.x -= (dir === "in" ? 1 : -1) * r * w;
      return;
    case "slideUp":
      d.y += (dir === "in" ? 1 : -1) * r * h;
      return;
    case "wipeLeft":
      d.mask = { shape: "rect", x: 1 - q / 2, y: 0.5, w: q, h: 1, feather: 0.02, invert: false };
      return;
    case "wipeRight":
      d.mask = { shape: "rect", x: q / 2, y: 0.5, w: q, h: 1, feather: 0.02, invert: false };
      return;
    case "wipeUp":
      d.mask = { shape: "rect", x: 0.5, y: 1 - q / 2, w: 1, h: q, feather: 0.02, invert: false };
      return;
    case "iris":
      d.mask = { shape: "ellipse", x: 0.5, y: 0.5, w: q * 1.6, h: q * 1.6, feather: 0.03, invert: false };
      return;
  }
}

export function evaluateClip(p: Project, c: Clip, t: number): LayerDraw {
  const local = t - c.start;
  const tr = (k: keyof typeof TRANSFORM_DEFAULTS) => valueAt(c.transform[k], local, TRANSFORM_DEFAULTS[k]);
  const scale = tr("scale");
  const asset = findAsset(p, c.assetId);
  const d: LayerDraw = {
    clipId: c.id,
    kind: c.kind,
    assetId: c.assetId,
    sceneId: c.sceneId,
    srcTime: c.kind === "anim" ? c.in + Math.max(0, local) * c.speed : sourceTime(c, t, asset?.duration ?? Infinity),
    x: tr("x"),
    y: tr("y"),
    scaleX: scale * tr("scaleX"),
    scaleY: scale * tr("scaleY"),
    rotation: tr("rotation"),
    anchorX: tr("anchorX"),
    anchorY: tr("anchorY"),
    opacity: Math.max(0, Math.min(1, tr("opacity"))),
    blend: c.blend,
    fit: c.fit,
    flipX: c.flipX,
    flipY: c.flipY,
    grade: resolveGrade(c, local),
    chroma: c.chroma?.enabled ? c.chroma : undefined,
    mask: c.mask && c.mask.shape !== "none" ? { ...c.mask } : { ...NO_MASK },
    text: c.text,
    textProgress: c.text ? (c.text.animate === "none" ? 1 : Math.max(0, Math.min(1, local / Math.max(0.01, c.text.animateDur)))) : 1,
    shape: c.shape,
    color: c.color,
    fx: resolveFx(c, local),
    wheels: c.wheels,
    duotone: c.duotone,
    crop: c.crop ? [c.crop.l, c.crop.t, c.crop.r, c.crop.b] : [0, 0, 0, 0],
    pin: c.pin,
    dirBlur: [0, 0],
  };
  // Motion blur from movement: compare with where the clip was half a frame earlier.
  if (d.fx.motionBlur > 0) {
    const dt = 0.5 / Math.max(1, p.fps);
    const px = valueAt(c.transform.x, local - dt, TRANSFORM_DEFAULTS.x);
    const py = valueAt(c.transform.y, local - dt, TRANSFORM_DEFAULTS.y);
    const ps = valueAt(c.transform.scale, local - dt, 1);
    d.dirBlur[0] += (d.x - px) * 2 * d.fx.motionBlur;
    d.dirBlur[1] += (d.y - py) * 2 * d.fx.motionBlur;
    d.fx.zoomBlur = Math.max(d.fx.zoomBlur, Math.min(1, Math.abs(scale - ps) * 6 * d.fx.motionBlur));
  }
  if (c.shake && c.shake.amp > 0) {
    d.x += noise1(t * c.shake.freq, 1) * c.shake.amp;
    d.y += noise1(t * c.shake.freq, 2) * c.shake.amp;
    d.rotation += noise1(t * c.shake.freq, 3) * c.shake.amp * 0.02;
  }
  if (c.transitionIn.kind !== "none" && c.transitionIn.dur > 0 && local < c.transitionIn.dur) {
    applyTransition(d, c.transitionIn, local / c.transitionIn.dur, "in", p.width, p.height);
  }
  const remain = c.start + c.dur - t;
  if (c.transitionOut.kind !== "none" && c.transitionOut.dur > 0 && remain < c.transitionOut.dur) {
    applyTransition(d, c.transitionOut, Math.max(0, remain) / c.transitionOut.dur, "out", p.width, p.height);
  }
  return d;
}

/** Visual layers at time t, bottom first. Tracks are listed top-first, like the timeline shows them. */
export function evaluate(p: Project, t: number): LayerDraw[] {
  const out: LayerDraw[] = [];
  for (let i = p.tracks.length - 1; i >= 0; i--) {
    const track = p.tracks[i]!;
    if (track.kind !== "video" || track.hidden) continue;
    for (const c of track.clips) {
      if (t < c.start || t >= c.start + c.dur) continue;
      const a = findAsset(p, c.assetId);
      if (c.kind === "media" && a?.kind === "audio") continue;
      out.push(evaluateClip(p, c, t));
    }
  }
  return out;
}

/** Adjustment clips grade everything beneath them; the compositor applies them as a full-frame pass. */
export function isAdjustment(d: LayerDraw): boolean {
  return d.kind === "adjust";
}

/** Clip volume at t, including clip fades and track gain. */
export function clipGain(c: Clip, t: number, trackVolume = 1): number {
  const local = t - c.start;
  let g = valueAt(c.volume, local, 1) * trackVolume;
  if (c.fadeIn > 0 && local < c.fadeIn) g *= Math.max(0, local / c.fadeIn);
  const remain = c.dur - local;
  if (c.fadeOut > 0 && remain < c.fadeOut) g *= Math.max(0, remain / c.fadeOut);
  return g;
}
