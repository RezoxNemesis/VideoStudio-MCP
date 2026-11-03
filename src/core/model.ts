/** VideoStudio project model. Everything here is plain JSON so projects save, undo and travel to agents as-is. */

export type Ease = "linear" | "in" | "out" | "inOut" | "hold" | "back" | "elastic" | "bounce";

export interface Keyframe {
  /** Seconds from the clip's start (editor) or frame index (animation layers). */
  t: number;
  v: number;
  ease: Ease;
}

/** A property is either a constant or a list of keyframes sorted by time. */
export type Animatable = number | Keyframe[];

export type TransformProp = "x" | "y" | "scale" | "scaleX" | "scaleY" | "rotation" | "opacity" | "anchorX" | "anchorY";

export const TRANSFORM_DEFAULTS: Readonly<Record<TransformProp, number>> = {
  x: 0,
  y: 0,
  scale: 1,
  scaleX: 1,
  scaleY: 1,
  rotation: 0,
  opacity: 1,
  anchorX: 0.5,
  anchorY: 0.5,
};

/** Colour grade, in the order the shader applies it. Every field is animatable. */
export interface Grade {
  exposure: Animatable; // stops, -3..3
  contrast: Animatable; // 0..2
  saturation: Animatable; // 0..2
  vibrance: Animatable; // -1..1
  temperature: Animatable; // -1..1
  tint: Animatable; // -1..1
  hue: Animatable; // degrees
  lift: Animatable; // -0.5..0.5 (shadows)
  gamma: Animatable; // 0.2..3 (mids)
  gain: Animatable; // 0..2 (highlights)
  vignette: Animatable; // 0..1
  grain: Animatable; // 0..1
  sharpen: Animatable; // 0..2
  blur: Animatable; // pixels at project resolution
}

export const GRADE_DEFAULTS: Readonly<Record<keyof Grade, number>> = {
  exposure: 0,
  contrast: 1,
  saturation: 1,
  vibrance: 0,
  temperature: 0,
  tint: 0,
  hue: 0,
  lift: 0,
  gamma: 1,
  gain: 1,
  vignette: 0,
  grain: 0,
  sharpen: 0,
  blur: 0,
};

/** Stylised effects, all animatable. Units: px at project resolution unless noted. */
export interface Fx {
  glow: Animatable; // 0..3 strength
  glowThreshold: Animatable; // 0..1 luminance where glow starts
  glowRadius: Animatable; // px
  rgbSplit: Animatable; // px
  glitch: Animatable; // 0..1
  pixelate: Animatable; // px block size
  zoomBlur: Animatable; // 0..1
  motionBlur: Animatable; // 0..2 shutter (auto blur from movement)
  posterize: Animatable; // levels (0 off)
  invert: Animatable; // 0..1
  duotone: Animatable; // 0..1 mix
  scanlines: Animatable; // 0..1
  shadows: Animatable; // -1..1 lift / crush shadows
  highlights: Animatable; // -1..1 boost / recover highlights
  letterbox: Animatable; // 0..0.3 bar height (fraction of frame)
}

export const FX_DEFAULTS: Readonly<Record<keyof Fx, number>> = {
  glow: 0,
  glowThreshold: 0.65,
  glowRadius: 24,
  rgbSplit: 0,
  glitch: 0,
  pixelate: 0,
  zoomBlur: 0,
  motionBlur: 0,
  posterize: 0,
  invert: 0,
  duotone: 0,
  scanlines: 0,
  shadows: 0,
  highlights: 0,
  letterbox: 0,
};

/** Colour wheels: RGB offsets (-0.5..0.5) for shadows (lift), midtones (gamma) and highlights (gain). */
export interface Wheels {
  lift: [number, number, number];
  gamma: [number, number, number];
  gain: [number, number, number];
}

export type BlendMode = "normal" | "add" | "multiply" | "screen" | "overlay" | "darken" | "lighten";

export interface ChromaKey {
  enabled: boolean;
  color: string;
  similarity: number;
  smoothness: number;
  spill: number;
}

export interface Mask {
  shape: "none" | "rect" | "ellipse";
  x: number; // centre, 0..1 of the layer
  y: number;
  w: number;
  h: number;
  feather: number; // 0..0.5
  invert: boolean;
}

export type TransitionKind =
  | "none" | "fade" | "dip" | "flash" | "wipeLeft" | "wipeRight" | "wipeUp" | "slideLeft" | "slideRight" | "slideUp"
  | "whipLeft" | "whipRight" | "zoomIn" | "zoomBlur" | "spin" | "glitch" | "blur" | "iris";

export interface Transition {
  kind: TransitionKind;
  dur: number;
}

export interface TextStyle {
  text: string;
  font: string;
  size: number; // px at project height 1920 (scaled with project)
  weight: number;
  color: string;
  stroke: string;
  strokeWidth: number;
  shadow: number;
  bg: string; // "" for none
  align: "left" | "center" | "right";
  /** Per-character entrance: none, typewriter, pop, rise, wave. */
  animate: "none" | "write" | "typewriter" | "pop" | "rise" | "wave" | "fade" | "slam" | "blurIn" | "track" | "bounce" | "flicker" | "scramble" | "drop" | "shake" | "zoomOut" | "spin" | "swing";
  /** Letter spacing in px (at 1920 tall). */
  tracking?: number;
  /** Outer glow colour ("" for none). */
  glow?: string;
  animateDur: number;
}

export interface ShapeStyle {
  shape: "rect" | "ellipse" | "line";
  w: number;
  h: number;
  fill: string;
  stroke: string;
  strokeWidth: number;
  radius: number;
}

export type ClipKind = "media" | "text" | "anim" | "shape" | "adjust" | "color";

export interface Clip {
  id: string;
  kind: ClipKind;
  name: string;
  /** Timeline position, seconds. */
  start: number;
  /** Timeline duration, seconds (after speed). */
  dur: number;
  /** Source in-point, seconds (media / anim). */
  in: number;
  speed: number;
  reverse: boolean;
  volume: Animatable; // 0..2
  fadeIn: number;
  fadeOut: number;
  assetId?: string;
  sceneId?: string;
  text?: TextStyle;
  shape?: ShapeStyle;
  color?: string;
  fit: "contain" | "cover" | "fill" | "none";
  transform: Partial<Record<TransformProp, Animatable>>;
  grade: Partial<Grade>;
  blend: BlendMode;
  chroma?: ChromaKey;
  mask?: Mask;
  flipX: boolean;
  flipY: boolean;
  transitionIn: Transition;
  transitionOut: Transition;
  /** Camera shake: amplitude in px and frequency in Hz. */
  shake?: { amp: number; freq: number };
  /** Subject look from the clip's person matte (cut-out, texture fill, neon outline, double exposure, halo…). */
  subject?: import("../render/subject").SubjectSpec;
  /** Animated window in FRAME space (fractions; times are seconds from the clip start): the clip shows only inside
   *  it — split screens, strips, grids, letterbox band reveals. */
  win?: { t: number; x0: number; y0: number; x1: number; y1: number }[];
  /** Corner pin (perspective): where the clip's corners land, as fractions of the frame (tl, tr, br, bl). */
  pin?: { tl: [number, number]; tr: [number, number]; br: [number, number]; bl: [number, number] };
  fx?: Partial<Fx>;
  wheels?: Wheels;
  duotone?: { dark: string; light: string };
  /** Crop as fractions trimmed from each side of the source (0..0.95). */
  crop?: { l: number; t: number; r: number; b: number };
  /** Speed ramp: playback speed over clip-local timeline time (overrides `speed` for source timing). */
  speedKeys?: Keyframe[];
  /** Freeze frame: always show the source at `in`. */
  hold?: boolean;
  /** Object / logo removal regions (filled from the surroundings; frame size and length unchanged). */
  remove?: import("../render/inpaint").RemoveRegion[];
}

export type TrackKind = "video" | "audio";

export interface Track {
  id: string;
  kind: TrackKind;
  name: string;
  muted: boolean;
  hidden: boolean;
  locked: boolean;
  volume: number;
  clips: Clip[];
}

export type AssetKind = "video" | "image" | "audio" | "model";

export interface Asset {
  id: string;
  kind: AssetKind;
  name: string;
  mime: string;
  size: number;
  duration: number;
  width: number;
  height: number;
  /** Where the bytes live: "opfs:<id>" or "idb:<id>". */
  ref: string;
  /** Mouth-open envelope (0..1) at 30 fps, for lip sync. Present on voice assets. */
  mouth?: number[];
  /** Waveform peaks (for timeline drawing), 100 per second max. */
  peaks?: number[];
  createdBy?: "user" | "agent" | "voice" | "render";
  /** Light edit copy of a big video (preview and helpers read it; the export reads the original). */
  proxy?: { ref: string; width: number; height: number };
}

export interface Project {
  v: 1;
  id: string;
  name: string;
  width: number;
  height: number;
  fps: number;
  background: string;
  tracks: Track[];
  assets: Asset[];
  scenes: AnimScene[];
  markers: { t: number; label: string }[];
  createdAt: number;
  updatedAt: number;
}

/* ------------------------------ 2D animation ------------------------------ */

export type BrushTool = "pen" | "pencil" | "ink" | "marker" | "airbrush" | "eraser" | "brush";

export interface Stroke {
  id: string;
  tool: BrushTool;
  color: string;
  size: number;
  opacity: number;
  /** Flat [x, y, pressure, x, y, pressure, ...] in scene pixels. */
  pts: number[];
  /** Closed and filled (shape tool / lasso fill). */
  fill?: string;
  closed?: boolean;
}

export interface Cel {
  strokes: Stroke[];
  /** Raster fills from the bucket tool: PNG data URLs drawn under the strokes. */
  fills: { id: string; png: string; x: number; y: number; w: number; h: number }[];
}

export interface AnimLayer {
  id: string;
  name: string;
  kind: "draw" | "reference" | "puppet" | "backdrop" | "proc" | "three" | "living";
  visible: boolean;
  locked: boolean;
  opacity: number;
  blend: BlendMode;
  /** Keys are frame indexes. A cel is exposed until the next key (x-sheet hold). Missing key 0 means blank. */
  cels: Record<string, Cel>;
  /** Cut-out style motion for the whole layer, keyed by frame. */
  transform: Partial<Record<TransformProp, Animatable>>;
  /** Reference layers show an image asset. */
  assetId?: string;
  /** Rig: the layer this one hangs from (its transform applies first). */
  parent?: string;
  /** Rig: the joint this part rotates around, in scene pixels (rest pose). */
  pivot?: { x: number; y: number };
  /** Rig: the far end of the bone (where the next joint or the tip is), in rest-pose scene pixels. */
  tip?: { x: number; y: number };
  /** Rig: body part name (hips, torso, head, upperArmF, ...), and which rig it belongs to. */
  part?: string;
  rig?: string;
  /** Procedural character (kind "puppet"). */
  puppet?: import("../anim/puppet").PuppetSpec;
  /** Painted backdrop (kind "backdrop"). */
  backdrop?: import("../anim/puppet").BackdropSpec;
  /** Animation channels for puppets/backdrops, keyed by frame. */
  chan?: Record<string, Animatable>;
  /** Procedural element (kind "proc"): captions, kinetic type, motion graphics, FX, overlays, collage. */
  proc?: import("../anim/proc").ProcSpec;
  /** 3D scene (kind "three"). */
  three?: import("../anim/three3d").Scene3D;
  /** Living picture (kind "living"): a still drawing/photo animated by bending it on a mesh. */
  living?: import("../anim/living").LivingSpec;
  /** Draw-on: strokes write themselves on between these frames; sketch adds construction lines first. */
  drawOn?: { start: number; dur: number; sketch?: boolean };
}

export interface AnimScene {
  id: string;
  name: string;
  width: number;
  height: number;
  fps: number;
  frames: number;
  background: string; // "" = transparent
  layers: AnimLayer[];
  palette: string[];
}

/* -------------------------------- factories ------------------------------- */

let counter = 0;
export function uid(prefix = "id"): string {
  counter = (counter + 1) % 1_000_000;
  const rnd = Math.random().toString(36).slice(2, 7);
  return `${prefix}_${Date.now().toString(36)}${counter.toString(36)}${rnd}`;
}

export function newProject(name = "Untitled", width = 1080, height = 1920, fps = 30): Project {
  const now = Date.now();
  return {
    v: 1,
    id: uid("prj"),
    name,
    width,
    height,
    fps,
    background: "#000000",
    tracks: [newTrack("video", "V2"), newTrack("video", "V1"), newTrack("audio", "A1"), newTrack("audio", "A2")],
    assets: [],
    scenes: [],
    markers: [],
    createdAt: now,
    updatedAt: now,
  };
}

export function newTrack(kind: TrackKind, name: string): Track {
  return { id: uid("trk"), kind, name, muted: false, hidden: false, locked: false, volume: 1, clips: [] };
}

export function newClip(kind: ClipKind, start: number, dur: number, extra: Partial<Clip> = {}): Clip {
  return {
    id: uid("clp"),
    kind,
    name: kind,
    start,
    dur,
    in: 0,
    speed: 1,
    reverse: false,
    volume: 1,
    fadeIn: 0,
    fadeOut: 0,
    fit: "contain",
    transform: {},
    grade: {},
    blend: "normal",
    flipX: false,
    flipY: false,
    transitionIn: { kind: "none", dur: 0.5 },
    transitionOut: { kind: "none", dur: 0.5 },
    ...extra,
  };
}

export function defaultText(text = "Title"): TextStyle {
  return {
    text,
    font: "Geist Variable, system-ui, sans-serif",
    size: 120,
    weight: 700,
    color: "#ffffff",
    stroke: "#000000",
    strokeWidth: 0,
    shadow: 12,
    bg: "",
    align: "center",
    animate: "none",
    animateDur: 0.6,
  };
}

export function newScene(name = "Scene", width = 1080, height = 1920, fps = 24): AnimScene {
  return {
    id: uid("scn"),
    name,
    width,
    height,
    fps,
    frames: 24,
    background: "#ffffff",
    layers: [newAnimLayer("Line"), newAnimLayer("Color")].reverse(),
    palette: ["#111111", "#ffffff", "#e53935", "#fb8c00", "#fdd835", "#43a047", "#1e88e5", "#8e24aa", "#6d4c41", "#f4c7a1", "#90a4ae", "#00bcd4"],
  };
}

export function newAnimLayer(name: string, kind: AnimLayer["kind"] = "draw"): AnimLayer {
  return { id: uid("lyr"), name, kind, visible: true, locked: false, opacity: 1, blend: "normal", cels: {}, transform: {} };
}

export function emptyCel(): Cel {
  return { strokes: [], fills: [] };
}

/* --------------------------------- queries -------------------------------- */

export function clipEnd(c: Clip): number {
  return c.start + c.dur;
}

export function projectDuration(p: Project): number {
  let end = 0;
  for (const t of p.tracks) for (const c of t.clips) end = Math.max(end, clipEnd(c));
  return end;
}

export function findClip(p: Project, id: string): { track: Track; clip: Clip; index: number } | null {
  for (const track of p.tracks) {
    const index = track.clips.findIndex((c) => c.id === id);
    if (index >= 0) return { track, clip: track.clips[index]!, index };
  }
  return null;
}

export function findAsset(p: Project, id: string | undefined): Asset | undefined {
  return id ? p.assets.find((a) => a.id === id) : undefined;
}

export function findScene(p: Project, id: string | undefined): AnimScene | undefined {
  return id ? p.scenes.find((s) => s.id === id) : undefined;
}

/** Source time (seconds into the asset) for a timeline time inside the clip. */
export function sourceTime(c: Clip, t: number, assetDuration = Infinity): number {
  const local = Math.max(0, Math.min(c.dur, t - c.start));
  if (c.hold) return Math.max(0, Math.min(assetDuration, c.in));
  if (c.speedKeys && c.speedKeys.length) {
    const used = rampIntegral(c.speedKeys, c.reverse ? c.dur - local : local);
    return Math.max(0, Math.min(assetDuration, c.in + used));
  }
  const s = c.reverse ? c.in + (c.dur - local) * c.speed : c.in + local * c.speed;
  return Math.max(0, Math.min(assetDuration, s));
}

/** Speed (multiplier) of a ramp at clip-local time t. */
export function rampSpeed(keys: readonly Keyframe[], t: number): number {
  if (t <= keys[0]!.t) return keys[0]!.v;
  const last = keys[keys.length - 1]!;
  if (t >= last.t) return last.v;
  let i = 0;
  while (i < keys.length - 2 && keys[i + 1]!.t <= t) i++;
  const a = keys[i]!;
  const b = keys[i + 1]!;
  const x = (t - a.t) / Math.max(1e-6, b.t - a.t);
  const e = a.ease === "linear" ? x : a.ease === "hold" ? 0 : x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
  return a.v + (b.v - a.v) * e;
}

/** Source seconds consumed after `local` seconds of a speed ramp (trapezoid integration, 1/240 s steps). */
export function rampIntegral(keys: readonly Keyframe[], local: number): number {
  const step = 1 / 240;
  let sum = 0;
  let prev = Math.max(0, rampSpeed(keys, 0));
  for (let t = step; t < local + step; t += step) {
    const tt = Math.min(t, local);
    const h = tt - (t - step);
    if (h <= 0) break;
    const v = Math.max(0, rampSpeed(keys, tt));
    sum += ((prev + v) / 2) * h;
    prev = v;
  }
  return sum;
}

/** Which cel key is exposed at a frame (x-sheet hold), or undefined if blank. */
export function exposedKey(layer: AnimLayer, frame: number): string | undefined {
  let best = -1;
  for (const k of Object.keys(layer.cels)) {
    const n = Number(k);
    if (n <= frame && n > best) best = n;
  }
  return best >= 0 ? String(best) : undefined;
}

export function sortClips(track: Track): void {
  track.clips.sort((a, b) => a.start - b.start);
}
