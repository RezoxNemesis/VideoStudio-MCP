/** 2D animation operations: cels, frames, layers, inbetweens and lip sync. Shared by the workstation UI and agents. */
import { emptyCel, exposedKey, newAnimLayer, newScene, uid, type AnimLayer, type AnimScene, type Cel, type Project, type Stroke } from "../core/model";
import { OpError } from "../core/ops";

export function getScene(p: Project, id: string): AnimScene {
  const s = p.scenes.find((x) => x.id === id);
  if (!s) throw new OpError(`No scene ${id}`);
  return s;
}

export function getLayer(s: AnimScene, id: string): AnimLayer {
  const l = s.layers.find((x) => x.id === id);
  if (!l) throw new OpError(`No layer ${id}`);
  return l;
}

export function createScene(p: Project, opts: Partial<Pick<AnimScene, "name" | "width" | "height" | "fps" | "frames" | "background">> = {}): AnimScene {
  const s = newScene(opts.name ?? `Scene ${p.scenes.length + 1}`, opts.width ?? p.width, opts.height ?? p.height, opts.fps ?? 24);
  if (opts.frames) s.frames = Math.max(1, Math.round(opts.frames));
  if (opts.background !== undefined) s.background = opts.background;
  p.scenes.push(s);
  return s;
}

export function addLayer(s: AnimScene, name?: string, kind: AnimLayer["kind"] = "draw", above?: string): AnimLayer {
  const l = newAnimLayer(name ?? `Layer ${s.layers.length + 1}`, kind);
  // layers[0] is the top layer.
  const i = above ? s.layers.findIndex((x) => x.id === above) : 0;
  s.layers.splice(Math.max(0, i), 0, l);
  return l;
}

/** The cel to draw into at a frame. Drawing on a held frame creates a new key there (a copy of the held drawing). */
export function celForEdit(layer: AnimLayer, frame: number, copyHeld = true): Cel {
  const key = String(frame);
  const existing = layer.cels[key];
  if (existing) return existing;
  const held = exposedKey(layer, frame);
  const cel: Cel = held && copyHeld ? (JSON.parse(JSON.stringify(layer.cels[held])) as Cel) : emptyCel();
  layer.cels[key] = cel;
  return cel;
}

export function addStroke(s: AnimScene, layerId: string, frame: number, stroke: Omit<Stroke, "id"> & { id?: string }): Stroke {
  const layer = getLayer(s, layerId);
  if (layer.locked) throw new OpError("Layer is locked.");
  const st: Stroke = { ...stroke, id: stroke.id ?? uid("stk") };
  celForEdit(layer, frame).strokes.push(st);
  return st;
}

/** Make frame `frame` a key on every layer (or one), so the next drawing starts from the held image. */
export function keyFrame(s: AnimScene, frame: number, layerId?: string, blank = false): void {
  for (const l of s.layers) {
    if (layerId && l.id !== layerId) continue;
    if (l.kind !== "draw") continue;
    if (!l.cels[String(frame)]) l.cels[String(frame)] = blank ? emptyCel() : celForEdit(l, frame);
  }
}

/** Insert `count` frames at `at`, shifting later keys (extends holds). */
export function insertFrames(s: AnimScene, at: number, count = 1): void {
  for (const l of s.layers) {
    const next: Record<string, Cel> = {};
    for (const [k, cel] of Object.entries(l.cels)) {
      const n = Number(k);
      next[String(n >= at && n > 0 ? n + count : n)] = cel;
    }
    l.cels = next;
  }
  s.frames += count;
}

export function deleteFrames(s: AnimScene, at: number, count = 1): void {
  for (const l of s.layers) {
    const next: Record<string, Cel> = {};
    const held = exposedKey(l, at + count);
    for (const [k, cel] of Object.entries(l.cels)) {
      const n = Number(k);
      if (n >= at && n < at + count) continue;
      next[String(n >= at + count ? n - count : n)] = cel;
    }
    // Keep what was showing after the cut.
    if (held && !next[String(at)] && Number(held) < at + count && Number(held) >= at) next[String(at)] = l.cels[held]!;
    l.cels = next;
  }
  s.frames = Math.max(1, s.frames - count);
}

export function duplicateFrame(s: AnimScene, frame: number): void {
  insertFrames(s, frame + 1, 1);
  for (const l of s.layers) {
    const k = exposedKey(l, frame);
    if (k) l.cels[String(frame + 1)] = JSON.parse(JSON.stringify(l.cels[k])) as Cel;
  }
}

export function clearCel(s: AnimScene, layerId: string, frame: number): void {
  const l = getLayer(s, layerId);
  l.cels[String(frame)] = emptyCel();
}

/* ------------------------------- inbetweens ------------------------------- */

/** Resample a stroke to n points evenly along its length. */
export function resample(pts: readonly number[], n: number): number[] {
  const count = pts.length / 3;
  if (count === 0) return [];
  if (count === 1) {
    const out: number[] = [];
    for (let i = 0; i < n; i++) out.push(pts[0]!, pts[1]!, pts[2]!);
    return out;
  }
  const cum: number[] = [0];
  for (let i = 1; i < count; i++) {
    const dx = pts[i * 3]! - pts[(i - 1) * 3]!;
    const dy = pts[i * 3 + 1]! - pts[(i - 1) * 3 + 1]!;
    cum.push(cum[i - 1]! + Math.hypot(dx, dy));
  }
  const total = cum[count - 1]!;
  const out: number[] = [];
  let j = 0;
  for (let i = 0; i < n; i++) {
    const d = n === 1 ? 0 : (total * i) / (n - 1);
    while (j < count - 2 && cum[j + 1]! < d) j++;
    const seg = cum[j + 1]! - cum[j]!;
    const f = seg > 0 ? (d - cum[j]!) / seg : 0;
    for (let c = 0; c < 3; c++) out.push(pts[j * 3 + c]! + (pts[(j + 1) * 3 + c]! - pts[j * 3 + c]!) * f);
  }
  return out;
}

function lerpColor(a: string, b: string, f: number): string {
  const pa = parseHex(a);
  const pb = parseHex(b);
  if (!pa || !pb) return f < 0.5 ? a : b;
  const c = pa.map((v, i) => Math.round(v + (pb[i]! - v) * f));
  return `#${c.map((v) => v.toString(16).padStart(2, "0")).join("")}`;
}

function parseHex(h: string): [number, number, number] | null {
  const m = /^#?([0-9a-f]{6})$/i.exec(h.trim());
  if (!m) return null;
  const n = parseInt(m[1]!, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

export function tweenStroke(a: Stroke, b: Stroke, f: number, ease = (x: number) => x): Stroke {
  const n = Math.max(8, Math.min(256, Math.max(a.pts.length, b.pts.length) / 3));
  const ra = resample(a.pts, n);
  const rb = resample(b.pts, n);
  const e = ease(f);
  const pts = ra.map((v, i) => v + (rb[i]! - v) * e);
  return {
    id: uid("stk"),
    tool: a.tool,
    color: lerpColor(a.color, b.color, e),
    size: a.size + (b.size - a.size) * e,
    opacity: a.opacity + (b.opacity - a.opacity) * e,
    pts,
    fill: a.fill && b.fill ? lerpColor(a.fill, b.fill, e) : a.fill,
    closed: a.closed,
  };
}

/**
 * Generate inbetween drawings on a layer between key `from` and key `to`.
 * Strokes pair by drawing order, so draw both keys in the same order for clean results.
 * `spacing`: "even", "slowIn" (ease out of the first key), "slowOut", "slowInOut".
 */
export function inbetween(s: AnimScene, layerId: string, from: number, to: number, spacing: "even" | "slowIn" | "slowOut" | "slowInOut" = "slowInOut", step = 1): number {
  const l = getLayer(s, layerId);
  const A = l.cels[String(from)];
  const B = l.cels[String(to)];
  if (!A || !B) throw new OpError("Both frames must be keys (draw on them first).");
  if (to - from < 2) throw new OpError("Leave at least one frame between the keys.");
  const ease =
    spacing === "slowIn" ? (x: number) => 1 - (1 - x) * (1 - x) : spacing === "slowOut" ? (x: number) => x * x : spacing === "slowInOut" ? (x: number) => x * x * (3 - 2 * x) : (x: number) => x;
  const pairs = Math.min(A.strokes.length, B.strokes.length);
  let made = 0;
  for (let fr = from + step; fr < to; fr += step) {
    const f = (fr - from) / (to - from);
    const strokes: Stroke[] = [];
    for (let i = 0; i < pairs; i++) strokes.push(tweenStroke(A.strokes[i]!, B.strokes[i]!, f, ease));
    // Unpaired strokes cross over at the midpoint.
    const extra = f < 0.5 ? A.strokes.slice(pairs) : B.strokes.slice(pairs);
    for (const st of extra) strokes.push({ ...st, id: uid("stk") });
    l.cels[String(fr)] = { strokes, fills: f < 0.5 ? A.fills : B.fills };
    made++;
  }
  return made;
}

/* -------------------------------- lip sync -------------------------------- */

/**
 * Drive a mouth layer from a voice envelope (0..1 at 30 fps): each scene frame exposes the drawing from
 * `shapes[i]`, where shapes go from closed to widest. Returns the number of keys written.
 */
export function lipSync(s: AnimScene, layerId: string, envelope: readonly number[], shapes: readonly number[], startFrame = 0, envFps = 30): number {
  const l = getLayer(s, layerId);
  if (shapes.length < 2) throw new OpError("Give at least two mouth shapes (closed, open).");
  const src = shapes.map((f) => {
    const k = exposedKey(l, f);
    if (!k) throw new OpError(`Mouth shape frame ${f} is blank.`);
    return JSON.stringify(l.cels[k]);
  });
  const frames = Math.ceil((envelope.length / envFps) * s.fps);
  let last = -1;
  let made = 0;
  for (let i = 0; i < frames; i++) {
    const e = envelope[Math.min(envelope.length - 1, Math.floor((i / s.fps) * envFps))] ?? 0;
    const idx = Math.min(shapes.length - 1, Math.round(Math.pow(e, 0.8) * (shapes.length - 1)));
    if (idx === last) continue;
    // Animate on twos for a natural mouth.
    if (i > 0 && i % 2 === 1 && last >= 0) continue;
    l.cels[String(startFrame + i)] = JSON.parse(src[idx]!) as Cel;
    last = idx;
    made++;
  }
  l.cels[String(startFrame + frames)] = JSON.parse(src[0]!) as Cel;
  s.frames = Math.max(s.frames, startFrame + frames + 1);
  return made + 1;
}

/** Keys on a layer, sorted. */
export function keysOf(l: AnimLayer): number[] {
  return Object.keys(l.cels)
    .map(Number)
    .sort((a, b) => a - b);
}
