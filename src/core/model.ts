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

export type TransitionKind = "none" | "fade" | "dip" | "wipeLeft" | "wipeRight" | "wipeUp" | "slideLeft" | "slideRight" | "slideUp" | "zoomIn" | "blur" | "iris";

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
  animate: "none" | "typewriter" | "pop" | "rise" | "wave" | "fade";
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

export type AssetKind = "video" | "image" | "audio";

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
  kind: "draw" | "reference";
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
  parent?: string;
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
  const s = c.reverse ? c.in + (c.dur - local) * c.speed : c.in + local * c.speed;
  return Math.max(0, Math.min(assetDuration, s));
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
