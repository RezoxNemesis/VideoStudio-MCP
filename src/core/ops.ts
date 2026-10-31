/** Editing operations shared by the touch UI and the agent API. Each mutates the project in place. */
import { removeKey, setKey, shiftKeys } from "./keyframes";
import {
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
  type Grade,
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

export type AnimTarget = { group: "transform"; prop: TransformProp } | { group: "grade"; prop: keyof Grade } | { group: "volume" };

export function parseTarget(path: string): AnimTarget {
  if (path === "volume") return { group: "volume" };
  const [group, prop] = path.split(".");
  if (group === "transform" && prop) return { group, prop: prop as TransformProp };
  if (group === "grade" && prop) return { group, prop: prop as keyof Grade };
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
  else c.grade[target.prop] = setKey(c.grade[target.prop], local, v, kind, fallback);
}

export function unkeyframe(p: Project, id: string, path: string, t: number): void {
  const c = getClip(p, id);
  const local = t - c.start;
  const target = parseTarget(path);
  if (target.group === "volume") c.volume = removeKey(c.volume, local) ?? 1;
  else if (target.group === "transform") c.transform[target.prop] = removeKey(c.transform[target.prop], local);
  else c.grade[target.prop] = removeKey(c.grade[target.prop], local);
}

export function setConstant(p: Project, id: string, path: string, v: number): void {
  const c = getClip(p, id);
  const target = parseTarget(path);
  if (target.group === "volume") c.volume = v;
  else if (target.group === "transform") c.transform[target.prop] = v;
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
