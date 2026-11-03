/** Editing operations shared by the touch UI and the agent API. Each mutates the project in place. */
import { removeKey, setKey, shiftKeys } from "./keyframes";
import { rampIntegral, sourceTime } from "./model";
import {
  FX_DEFAULTS,
  GRADE_DEFAULTS,
  defaultText,
  findAsset,
  findClip,
  newClip,
  newTrack,
  sortClips,
  uid,
  type Asset,
  type Clip,
  type ClipKind,
  type Ease,
  type Fx,
  type Grade,
  type Keyframe,
  type Project,
  type Track,
  type TrackKind,
  type TransformProp,
} from "./model";

export class OpError extends Error {}

const EPS = 1e-4;

function overlaps(track: Track, start: number, end: number, ignore?: string): boolean {
  return track.clips.some((c) => c.id !== ignore && start < c.start + c.dur - EPS && end > c.start + EPS);
}

export function trackFor(p: Project, kind: TrackKind, start: number, dur: number, preferred?: string, ignore?: string): Track {
  const pref = preferred ? p.tracks.find((t) => t.id === preferred) : undefined;
  if (pref && pref.kind === kind && !pref.locked && !overlaps(pref, start, start + dur, ignore)) return pref;
  // Video stacks bottom-up (V1 is the main track at the bottom of the list); audio fills top-down.
  const ordered = kind === "video" ? p.tracks.filter((t) => t.kind === "video").reverse() : p.tracks.filter((t) => t.kind === "audio");
  const free = ordered.find((t) => !t.locked && !overlaps(t, start, start + dur, ignore));
  if (free) return free;
  const n = p.tracks.filter((t) => t.kind === kind).length + 1;
  const track = newTrack(kind, `${kind === "video" ? "V" : "A"}${n}`);
  // New video tracks go on top (start of list); audio tracks at the end.
  if (kind === "video") p.tracks.unshift(track);
  else p.tracks.push(track);
  return track;
}

export function getTrack(p: Project, id: string): Track {
  const t = p.tracks.find((x) => x.id === id);
  if (!t) throw new OpError(`No track ${id}`);
  return t;
}

export function getClip(p: Project, id: string): Clip {
  const f = findClip(p, id);
  if (!f) throw new OpError(`No clip ${id}`);
  return f.clip;
}

export interface AddClipInput {
  kind?: ClipKind;
  assetId?: string;
  sceneId?: string;
  trackId?: string;
  start?: number;
  dur?: number;
  in?: number;
  text?: string;
  props?: Partial<Clip>;
}

/** Add a clip. Media clips take the asset's length; text/shape/colour default to 3 s. Returns the clip. */
export function addClip(p: Project, input: AddClipInput): Clip {
  const asset = findAsset(p, input.assetId);
  if (input.assetId && !asset) throw new OpError(`No asset ${input.assetId}`);
  if (asset?.kind === "model") throw new OpError("3D models go into a 3D layer (three.add type:model), not straight onto the timeline.");
  const scene = input.sceneId ? p.scenes.find((s) => s.id === input.sceneId) : undefined;
  if (input.sceneId && !scene) throw new OpError(`No scene ${input.sceneId}`);
  const kind: ClipKind = input.kind ?? (asset ? "media" : scene ? "anim" : input.text !== undefined ? "text" : "color");
  const trackKind: TrackKind = asset?.kind === "audio" ? "audio" : "video";
  const srcIn = Math.max(0, input.in ?? 0);
  let dur = input.dur ?? 0;
  if (!dur) {
    if (asset && asset.kind !== "image") dur = Math.max(0.1, asset.duration - srcIn);
    else if (scene) dur = scene.frames / scene.fps;
    else dur = 3;
  }
  const start = input.start ?? endOfTrackKind(p, trackKind);
  const track = trackFor(p, trackKind, start, dur, input.trackId);
  const clip = newClip(kind, start, dur, {
    name: asset?.name ?? scene?.name ?? (kind === "text" ? (input.text ?? "Title") : kind),
    assetId: asset?.id,
    sceneId: scene?.id,
    in: srcIn,
    text: kind === "text" ? defaultText(input.text ?? "Title") : undefined,
    shape: kind === "shape" ? { shape: "rect", w: 400, h: 400, fill: "#ffffff", stroke: "", strokeWidth: 0, radius: 24 } : undefined,
    color: kind === "color" ? "#1e88e5" : undefined,
    fit: asset && asset.kind === "image" ? "cover" : "contain",
    ...(input.props ?? {}),
  });
  track.clips.push(clip);
  sortClips(track);
  return clip;
}

function endOfTrackKind(p: Project, kind: TrackKind): number {
  let end = 0;
  const list = p.tracks.filter((t) => t.kind === kind);
  const first = kind === "video" ? list[list.length - 1] : list[0];
  if (first) for (const c of first.clips) end = Math.max(end, c.start + c.dur);
  return end;
}

export function moveClip(p: Project, id: string, start: number, trackId?: string): Clip {
  const f = findClip(p, id);
  if (!f) throw new OpError(`No clip ${id}`);
  const s = Math.max(0, start);
  const target = trackId ? getTrack(p, trackId) : f.track;
  if (target.kind !== f.track.kind) throw new OpError("A clip can only move to a track of the same kind.");
  const dest = trackFor(p, f.track.kind, s, f.clip.dur, target.id, id);
  if (dest !== f.track) {
    f.track.clips.splice(f.index, 1);
    dest.clips.push(f.clip);
  }
  f.clip.start = s;
  sortClips(dest);
  return f.clip;
}

/** Split at timeline time t. Returns the new right-hand clip. */
export function splitClip(p: Project, id: string, t: number): Clip {
  const f = findClip(p, id);
  if (!f) throw new OpError(`No clip ${id}`);
  const c = f.clip;
  if (t <= c.start + 0.02 || t >= c.start + c.dur - 0.02) throw new OpError("Split point must be inside the clip.");
  const leftDur = t - c.start;
  const right: Clip = JSON.parse(JSON.stringify(c)) as Clip;
  right.id = uid("clp");
  right.start = t;
  right.dur = c.dur - leftDur;
  if (!c.reverse) right.in = c.in + leftDur * c.speed;
  else c.in = c.in + right.dur * c.speed;
  // Keyframes are clip-local: shift the right half so it keeps the same motion.
  for (const k of Object.keys(right.transform) as TransformProp[]) right.transform[k] = shiftKeys(right.transform[k], -leftDur);
  for (const k of Object.keys(right.grade) as (keyof Grade)[]) right.grade[k] = shiftKeys(right.grade[k], -leftDur);
  right.volume = shiftKeys(right.volume, -leftDur) ?? 1;
  // Frame windows (band reveals, strips) are clip-local too: the right half continues where the left stopped.
  if (right.win) right.win = right.win.map((k) => ({ ...k, t: k.t - leftDur }));
  right.transitionIn = { kind: "none", dur: right.transitionIn.dur };
  right.fadeIn = 0;
  c.transitionOut = { kind: "none", dur: c.transitionOut.dur };
  c.fadeOut = 0;
  c.dur = leftDur;
  f.track.clips.splice(f.index + 1, 0, right);
  return right;
}

/** Trim one edge to timeline time t. */
export function trimClip(p: Project, id: string, edge: "start" | "end", t: number): Clip {
  const f = findClip(p, id);
  if (!f) throw new OpError(`No clip ${id}`);
  const c = f.clip;
  const asset = findAsset(p, c.assetId);
  const maxSrc = asset && asset.kind !== "image" ? asset.duration : Infinity;
  if (edge === "start") {
    let ns = Math.min(t, c.start + c.dur - 0.05);
    ns = Math.max(0, ns);
    let delta = ns - c.start;
    if (!c.reverse && c.in + delta * c.speed < 0) delta = -c.in / c.speed;
    ns = c.start + delta;
    const others = f.track.clips.filter((o) => o.id !== id && o.start + o.dur <= c.start + EPS);
    const prevEnd = others.reduce((m, o) => Math.max(m, o.start + o.dur), 0);
    if (ns < prevEnd) {
      delta = prevEnd - c.start;
      ns = prevEnd;
    }
    if (!c.reverse) c.in += delta * c.speed;
    c.start = ns;
    c.dur -= delta;
    for (const k of Object.keys(c.transform) as TransformProp[]) c.transform[k] = shiftKeys(c.transform[k], -delta);
    if (c.win) c.win = c.win.map((k) => ({ ...k, t: k.t - delta }));
  } else {
    let ne = Math.max(t, c.start + 0.05);
    const srcEnd = c.in + (ne - c.start) * c.speed;
    if (!c.reverse && srcEnd > maxSrc) ne = c.start + (maxSrc - c.in) / c.speed;
    const next = f.track.clips.filter((o) => o.id !== id && o.start >= c.start + c.dur - EPS).reduce((m, o) => Math.min(m, o.start), Infinity);
    ne = Math.min(ne, next);
    c.dur = Math.max(0.05, ne - c.start);
  }
  return c;
}

export function deleteClip(p: Project, id: string, ripple = false): void {
  const f = findClip(p, id);
  if (!f) throw new OpError(`No clip ${id}`);
  f.track.clips.splice(f.index, 1);
  if (ripple) for (const c of f.track.clips) if (c.start >= f.clip.start) c.start = Math.max(0, c.start - f.clip.dur);
}

export function duplicateClip(p: Project, id: string): Clip {
  const f = findClip(p, id);
  if (!f) throw new OpError(`No clip ${id}`);
  const copy: Clip = JSON.parse(JSON.stringify(f.clip)) as Clip;
  copy.id = uid("clp");
  copy.start = f.clip.start + f.clip.dur;
  const track = trackFor(p, f.track.kind, copy.start, copy.dur, f.track.id);
  track.clips.push(copy);
  sortClips(track);
  return copy;
}

/** Change speed, keeping the same source range (duration changes). */
export function setSpeed(p: Project, id: string, speed: number): Clip {
  const c = getClip(p, id);
  const s = Math.max(0.1, Math.min(16, speed));
  const srcLen = c.dur * c.speed;
  c.speed = s;
  c.dur = Math.max(0.05, srcLen / s);
  return c;
}

const PATCHABLE = new Set<keyof Clip>([
  "name", "start", "dur", "in", "speed", "reverse", "volume", "fadeIn", "fadeOut", "text", "shape", "color", "fit",
  "transform", "grade", "blend", "chroma", "mask", "flipX", "flipY", "transitionIn", "transitionOut", "shake",
  "fx", "wheels", "duotone", "crop", "speedKeys", "hold",
]);

/** Shallow-merge a patch. Object fields (text, grade, transform...) merge one level deep. */
export function patchClip(p: Project, id: string, patch: Record<string, unknown>): Clip {
  const c = getClip(p, id);
  for (const [k, v] of Object.entries(patch)) {
    if (!PATCHABLE.has(k as keyof Clip)) throw new OpError(`Clip field "${k}" cannot be set.`);
    const cur = (c as unknown as Record<string, unknown>)[k];
    if (v && typeof v === "object" && !Array.isArray(v) && cur && typeof cur === "object" && !Array.isArray(cur)) {
      (c as unknown as Record<string, unknown>)[k] = { ...(cur as object), ...(v as object) };
    } else {
      (c as unknown as Record<string, unknown>)[k] = v;
    }
  }
  if (patch.speed !== undefined) setSpeed(p, id, Number(patch.speed));
  if (patch.start !== undefined) moveClip(p, id, Number(patch.start));
  return c;
}

export type AnimTarget = { group: "transform"; prop: TransformProp } | { group: "grade"; prop: keyof Grade } | { group: "fx"; prop: keyof Fx } | { group: "volume" };

export function parseTarget(path: string): AnimTarget {
  if (path === "volume") return { group: "volume" };
  const [group, prop] = path.split(".");
  if (group === "transform" && prop) return { group, prop: prop as TransformProp };
  if (group === "grade" && prop) return { group, prop: prop as keyof Grade };
  if (group === "fx" && prop) {
    if (!(prop in FX_DEFAULTS)) throw new OpError(`Unknown effect "${prop}". Effects: ${Object.keys(FX_DEFAULTS).join(", ")}.`);
    return { group: "fx", prop: prop as keyof Fx };
  }
  if (path in FX_DEFAULTS && !(path in GRADE_DEFAULTS)) return { group: "fx", prop: path as keyof Fx };
  // Bare names: transform first, then grade.
  if (["x", "y", "scale", "scaleX", "scaleY", "rotation", "opacity", "anchorX", "anchorY"].includes(path)) return { group: "transform", prop: path as TransformProp };
  return { group: "grade", prop: path as keyof Grade };
}

/** Add or replace a keyframe. `t` is timeline time; it is stored clip-local. */
export function keyframe(p: Project, id: string, path: string, t: number, v: number, kind: Ease = "inOut", fallback = 0): void {
  const c = getClip(p, id);
  const local = Math.max(0, Math.min(c.dur, t - c.start));
  const target = parseTarget(path);
  if (target.group === "volume") c.volume = setKey(c.volume, local, v, kind, 1);
  else if (target.group === "transform") c.transform[target.prop] = setKey(c.transform[target.prop], local, v, kind, fallback);
  else if (target.group === "fx") (c.fx ??= {})[target.prop] = setKey(c.fx[target.prop], local, v, kind, FX_DEFAULTS[target.prop]);
  else c.grade[target.prop] = setKey(c.grade[target.prop], local, v, kind, fallback);
}

export function unkeyframe(p: Project, id: string, path: string, t: number): void {
  const c = getClip(p, id);
  const local = t - c.start;
  const target = parseTarget(path);
  if (target.group === "volume") c.volume = removeKey(c.volume, local) ?? 1;
  else if (target.group === "transform") c.transform[target.prop] = removeKey(c.transform[target.prop], local);
  else if (target.group === "fx") (c.fx ??= {})[target.prop] = removeKey(c.fx[target.prop], local);
  else c.grade[target.prop] = removeKey(c.grade[target.prop], local);
}

export function setConstant(p: Project, id: string, path: string, v: number): void {
  const c = getClip(p, id);
  const target = parseTarget(path);
  if (target.group === "volume") c.volume = v;
  else if (target.group === "transform") c.transform[target.prop] = v;
  else if (target.group === "fx") (c.fx ??= {})[target.prop] = v;
  else c.grade[target.prop] = v;
}

export function addAsset(p: Project, a: Asset): Asset {
  p.assets.push(a);
  return a;
}

export function removeAsset(p: Project, id: string): void {
  p.assets = p.assets.filter((a) => a.id !== id);
  for (const t of p.tracks) t.clips = t.clips.filter((c) => c.assetId !== id);
}

/** Remove gaps on a track by sliding clips left. */
export function closeGaps(p: Project, trackId: string): void {
  const t = getTrack(p, trackId);
  sortClips(t);
  let at = 0;
  for (const c of t.clips) {
    c.start = at;
    at += c.dur;
  }
}

/** Ken Burns: slow push-in with a drift, the classic photo move. */
export function kenBurns(p: Project, id: string, strength = 0.15): void {
  const c = getClip(p, id);
  c.transform.scale = [
    { t: 0, v: 1, ease: "linear" },
    { t: c.dur, v: 1 + strength, ease: "linear" },
  ];
  c.transform.x = [
    { t: 0, v: -p.width * strength * 0.15, ease: "linear" },
    { t: c.dur, v: p.width * strength * 0.15, ease: "linear" },
  ];
}

/* ------------------------------ pro edit ops ------------------------------ */

/** Freeze the frame at timeline time t for `dur` seconds; later clips on the track slide right. */
export function freezeFrame(p: Project, id: string, t: number, dur = 1.5): Clip {
  const f = findClip(p, id);
  if (!f) throw new OpError(`No clip ${id}`);
  const c = f.clip;
  if (c.kind !== "media" && c.kind !== "anim") throw new OpError("Freeze frames work on video and animation clips.");
  if (t <= c.start || t >= c.start + c.dur) throw new OpError("Put the playhead inside the clip.");
  const asset = findAsset(p, c.assetId);
  const src = sourceTime(c, t, asset?.duration ?? Infinity);
  const right = splitClip(p, id, t);
  for (const o of f.track.clips) if (o.start >= t - 1e-6) o.start += dur;
  const hold: Clip = JSON.parse(JSON.stringify(right)) as Clip;
  hold.id = uid("clp");
  hold.start = t;
  hold.dur = dur;
  hold.in = src;
  hold.hold = true;
  hold.speedKeys = undefined;
  hold.name = `${c.name} (freeze)`;
  hold.transform = {};
  hold.transitionIn = { kind: "flash", dur: 0.15 };
  hold.transitionOut = { kind: "none", dur: 0.3 };
  f.track.clips.push(hold);
  sortClips(f.track);
  return hold;
}

export const RAMPS: Record<string, [number, number][]> = {
  // [fraction of clip, speed]
  montage: [[0, 1], [0.3, 4], [0.7, 4], [1, 1]],
  hero: [[0, 1.6], [0.35, 0.25], [0.7, 0.25], [1, 1.6]],
  bullet: [[0, 3], [0.4, 0.15], [0.6, 0.15], [1, 3]],
  rampUp: [[0, 0.5], [1, 3]],
  rampDown: [[0, 3], [1, 0.4]],
  flashIn: [[0, 4], [0.25, 1], [1, 1]],
};

/** Apply a speed ramp preset (or custom keys). Speeds are scaled down if the source would run out. */
export function speedRamp(p: Project, id: string, preset: string | [number, number][]): Keyframe[] | undefined {
  const c = getClip(p, id);
  if (preset === "none") {
    c.speedKeys = undefined;
    return undefined;
  }
  const shape = typeof preset === "string" ? RAMPS[preset] : preset;
  if (!shape) throw new OpError(`Unknown ramp "${preset}". Ramps: ${Object.keys(RAMPS).join(", ")}, none.`);
  let keys: Keyframe[] = shape.map(([f, v]) => ({ t: f * c.dur, v, ease: "inOut" as Ease }));
  const asset = findAsset(p, c.assetId);
  const avail = asset && asset.kind !== "image" ? asset.duration - c.in : Infinity;
  const need = rampIntegral(keys, c.dur);
  if (need > avail && need > 0) keys = keys.map((k) => ({ ...k, v: (k.v * avail) / need * 0.999 }));
  c.speedKeys = keys;
  return keys;
}

export function setCrop(p: Project, id: string, crop: { l?: number; t?: number; r?: number; b?: number }): Clip {
  const c = getClip(p, id);
  const cur = c.crop ?? { l: 0, t: 0, r: 0, b: 0 };
  const n = { ...cur, ...crop };
  for (const k of ["l", "t", "r", "b"] as const) n[k] = Math.max(0, Math.min(0.95, Number(n[k]) || 0));
  if (n.l + n.r > 0.95) n.r = 0.95 - n.l;
  if (n.t + n.b > 0.95) n.b = 0.95 - n.t;
  c.crop = n.l || n.t || n.r || n.b ? n : undefined;
  return c;
}

/** Tint a colour wheel from a colour: the offset from neutral grey, scaled by strength (0..1). */
export function wheelFromColor(hex: string, strength: number): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return [0, 0, 0];
  const n = parseInt(m[1]!, 16);
  const rgb = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => v / 255);
  const avg = (rgb[0]! + rgb[1]! + rgb[2]!) / 3;
  return rgb.map((v) => Math.round((v - avg) * strength * 0.6 * 1000) / 1000) as [number, number, number];
}

export const TEXT_PRESETS: Record<string, Partial<import("./model").TextStyle> & { fx?: Partial<Fx>; transform?: { y?: number } }> = {
  punch: { font: '"Anton", Impact, sans-serif', weight: 900, size: 170, color: "#ffffff", stroke: "#000000", strokeWidth: 10, shadow: 18, animate: "slam", animateDur: 0.45, tracking: 2, glow: "", bg: "" },
  neon: { font: "Geist Variable, system-ui, sans-serif", weight: 600, size: 130, color: "#e9fbff", stroke: "", strokeWidth: 0, shadow: 0, glow: "#21d4fd", animate: "flicker", animateDur: 0.9, tracking: 6, bg: "", fx: { glow: 1.2, glowThreshold: 0.4, glowRadius: 30 } },
  cinematic: { font: "Instrument Serif, Georgia, serif", weight: 400, size: 120, color: "#f3ead7", stroke: "", strokeWidth: 0, shadow: 20, animate: "track", animateDur: 2.2, tracking: 18, glow: "", bg: "" },
  subtitle: { font: "Geist Variable, system-ui, sans-serif", weight: 600, size: 62, color: "#ffffff", stroke: "", strokeWidth: 0, shadow: 0, bg: "#000000b0", animate: "fade", animateDur: 0.2, tracking: 0, glow: "", transform: { y: 700 } },
  minimal: { font: "Geist Variable, system-ui, sans-serif", weight: 300, size: 96, color: "#ffffff", stroke: "", strokeWidth: 0, shadow: 6, animate: "blurIn", animateDur: 0.8, tracking: 10, glow: "", bg: "" },
  glitch: { font: "'Courier New', monospace", weight: 800, size: 120, color: "#ffffff", stroke: "#ff0055", strokeWidth: 2, shadow: 0, animate: "flicker", animateDur: 0.6, tracking: 4, glow: "", bg: "", fx: { rgbSplit: 10, glitch: 0.25, scanlines: 0.3 } },
  bounce: { font: "Geist Variable, system-ui, sans-serif", weight: 900, size: 150, color: "#ffd23f", stroke: "#2b1055", strokeWidth: 8, shadow: 14, animate: "bounce", animateDur: 1, tracking: 2, glow: "", bg: "" },
  rap: { font: '"Bebas Neue", sans-serif', weight: 400, size: 230, color: "#ffffff", stroke: "#000000", strokeWidth: 6, shadow: 22, animate: "slam", animateDur: 0.3, tracking: 4, glow: "", bg: "" },
  anime: { font: '"Bangers", sans-serif', weight: 400, size: 190, color: "#ffe14d", stroke: "#1a0f2e", strokeWidth: 12, shadow: 0, animate: "pop", animateDur: 0.5, tracking: 6, glow: "", bg: "" },
  handwritten: { font: '"Caveat", cursive', weight: 700, size: 110, color: "#fff6e6", stroke: "", strokeWidth: 0, shadow: 14, animate: "write", animateDur: 1.2, tracking: 0, glow: "", bg: "" },
  royal: { font: '"Cinzel", serif', weight: 900, size: 120, color: "#f7e3a1", stroke: "", strokeWidth: 0, shadow: 12, glow: "#d4a537", animate: "track", animateDur: 2, tracking: 20, bg: "" },
  love: { font: '"Great Vibes", cursive', weight: 400, size: 170, color: "#ffe6ef", stroke: "", strokeWidth: 0, shadow: 10, glow: "#ff6fa3", animate: "blurIn", animateDur: 1.2, tracking: 0, bg: "" },
  street: { font: '"Permanent Marker", cursive', weight: 400, size: 150, color: "#ffffff", stroke: "#000000", strokeWidth: 4, shadow: 10, animate: "rise", animateDur: 0.5, tracking: 2, glow: "", bg: "" },
  graffiti: { font: '"Rubik Wet Paint", sans-serif', weight: 400, size: 170, color: "#7dff4a", stroke: "#111111", strokeWidth: 6, shadow: 14, animate: "slam", animateDur: 0.4, tracking: 2, glow: "", bg: "" },
  retro: { font: '"Monoton", sans-serif', weight: 400, size: 150, color: "#ffd1f7", stroke: "", strokeWidth: 0, shadow: 0, glow: "#ff2bd6", animate: "flicker", animateDur: 0.9, tracking: 6, bg: "", fx: { glow: 1, glowThreshold: 0.4, glowRadius: 26 } },
  pixel: { font: '"Press Start 2P", monospace', weight: 400, size: 80, color: "#ffffff", stroke: "#000000", strokeWidth: 6, shadow: 0, animate: "typewriter", animateDur: 0.8, tracking: 2, glow: "", bg: "" },
  horror: { font: '"Creepster", cursive', weight: 400, size: 180, color: "#d10f1f", stroke: "#000000", strokeWidth: 4, shadow: 20, animate: "flicker", animateDur: 1.2, tracking: 4, glow: "", bg: "" },
  scifi: { font: '"Orbitron", sans-serif', weight: 900, size: 120, color: "#bff6ff", stroke: "", strokeWidth: 0, shadow: 0, glow: "#24c8ff", animate: "track", animateDur: 1.2, tracking: 14, bg: "" },
  elegant: { font: '"Playfair Display", serif', weight: 900, size: 140, color: "#ffffff", stroke: "", strokeWidth: 0, shadow: 16, animate: "rise", animateDur: 0.9, tracking: 2, glow: "", bg: "" },
  hindi: { font: '"Rozha One", serif', weight: 400, size: 160, color: "#fff4d6", stroke: "#3a1200", strokeWidth: 6, shadow: 14, animate: "pop", animateDur: 0.5, tracking: 0, glow: "", bg: "" },
  bold: { font: '"Archivo Black", sans-serif', weight: 400, size: 150, color: "#ffffff", stroke: "", strokeWidth: 0, shadow: 18, animate: "rise", animateDur: 0.45, tracking: 0, glow: "", bg: "" },
};

export function textPreset(p: Project, id: string, name: string): Clip {
  const c = getClip(p, id);
  if (!c.text) throw new OpError("That clip is not a title.");
  const pr = TEXT_PRESETS[name];
  if (!pr) throw new OpError(`Unknown title preset "${name}". Presets: ${Object.keys(TEXT_PRESETS).join(", ")}.`);
  const { fx, transform, ...style } = pr;
  Object.assign(c.text, style);
  c.fx = fx ? { ...fx } : undefined;
  if (transform?.y !== undefined) c.transform.y = transform.y * (p.height / 1920);
  return c;
}
