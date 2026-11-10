/**
 * Style DNA: the measurable "feel" of a finished edit — how its cuts sit against the music, how shot length rises
 * and falls over the running time, what its colour looks like, how its titles are set and how much its shots move —
 * kept as a small JSON record that can be saved, merged into a personal signature and transplanted onto another
 * timeline. Pure and deterministic: no DOM, no Node, no network, no clock, no Math.random (a seeded generator is used
 * where variation is wanted).
 *
 * Rules used throughout (a reviewer should read these first):
 *
 *  Picture clips that take part (measuring). A clip takes part when it is on a visible video track, is kind "media"
 *  whose asset is a video or an image (or whose asset is missing), or kind "anim" whose scene has an opaque
 *  background (scene.background !== ""; procedural captions and overlays are transparent scenes), has blend
 *  "normal", opacity ≥ 0.9 at its middle, no chroma key, and covers at least 12 % of the frame at its middle
 *  (pictureArea: source size → crop → fit → scale × scaleX / scaleY → centred at frame centre + (x, y) → clipped to
 *  the frame → ∩ frame window → × mask w × h; rotation and corner pin ignored). Split-screen panels take part; small
 *  stickers, text, shapes, colour cards, adjustment clips and audio do not.
 *  Backdrops. A taking-part clip whose blur (grade.blur, constant or first key) is ≥ 20 px at a 1920-high frame and
 *  that has other taking-part clips on higher tracks over ≥ 80 % of its length (their union) is a backdrop: it never
 *  creates a cut and is not a shot. A "shot" (isShotClip) is a taking-part clip that is not a backdrop.
 *  Cuts of a project. Every start and end of a shot is a candidate, and candidates within one frame (1 / fps) are one
 *  moment, at the earliest time. A moment is a cut when at least one of its boundaries is real. A boundary is not real
 *  when it belongs to a through edit (the same asset on the same track continuing at the same speed from the source
 *  frame where the first clip stopped), or when, at that moment, a full-frame taking-part clip on a higher track
 *  covers both sides of it (full frame = covers ≥ 95 % of the frame, opacity ≥ 0.99, no mask, window or pin). Running
 *  from or into empty picture (black) is a cut, so a gap counts as a (black) shot. The running time is 0 .. the end of
 *  the last taking-part clip; moments at 0 and at the end are not cuts. Shot lengths are the gaps between cuts.
 *  Re-timing (applyDna "rhythm") is stricter: it only works on one clean sequence of full-frame shots (see
 *  isFullFrameShot) and refuses split screens.
 *  Beats. Beat period = 60 / bpm when bpm is given, otherwise the median gap between the given beats (at least two
 *  needed). Downbeats alone never give a period. For "on the beat", the beat grid is beats ∪ downbeats, and a cut is
 *  on the beat when it is within 80 ms of a grid time (on a downbeat: within 80 ms of a downbeat).
 *  Beat buckets. A shot of b beats goes in the nearest bucket in log scale (0.5, 1, 2, 4, 8, 16+): boundaries are the
 *  geometric means 0.707, 1.414, 2.83, 5.66, 11.3 beats.
 *  Arc. For each of 8 equal slices of the running time, the local mean shot length is the slice length divided by
 *  the number of shots in it, counting a shot that only partly overlaps the slice by the fraction of the shot inside
 *  it; the arc value is that length divided by the video's own median shot length (1 = typical, 2 = shots twice as
 *  long there, 0.5 = twice as fast there).
 *  Minimum shot. Planned cuts are never closer than 0.25 s to each other, to 0 or to the end (tested with a 1e-9 s
 *  tolerance for floating point).
 */
import { evaluateClip } from "../core/evaluate";
import { isAnimated, valueAt } from "../core/keyframes";
import { findAsset, findScene, projectDuration, type Animatable, type Clip, type Project, type Track } from "../core/model";
import type { ColourStats } from "../color/types";

/* ---------------------------------- types --------------------------------- */

export interface Dna {
  v: 1;
  name: string;
  source: "video" | "project" | "merged";
  /** How many videos / projects went in (1 for a measured record, the sum for a merged one). */
  samples: number;
  rhythm: {
    bpm?: number;
    shotCount: number;
    duration: number;
    /** Seconds. */
    shotMedian: number;
    shotMean: number;
    shotMin: number;
    shotMax: number;
    /** Shot length in beats (when the music was known): share of shots per length bucket, buckets 0.5,1,2,4,8,16+ beats; sums to 1. */
    beatBuckets?: number[];
    /** Share of cuts within 80 ms of a beat / of a downbeat, 0..1 (when beats were known). */
    onBeat?: number;
    onDownbeat?: number;
    /** Pacing arc: relative shot length (1 = the video's own median) across 8 equal slices of the running time. */
    arc: number[];
    /** Seconds until the first cut (the whole duration when there is no cut). */
    firstCut: number;
  };
  colour?: ColourStats;
  type?: {
    fonts: { font: string; share: number }[];
    /** Median text height (font size) as a fraction of the frame height. */
    sizeRel: number;
    colors: { color: string; share: number }[];
    /** Median vertical position, -0.5 top .. 0.5 bottom. */
    yRel: number;
    animate: { name: string; share: number }[];
    wordsPerSec: number;
    upperShare: number;
  };
  motion?: {
    /** Share of shots with animated scale/position. */
    movingShare: number;
    transitions: { kind: string; share: number }[];
  };
}

/** Options shared by the planning and applying functions. */
export interface BeatInfo {
  beats?: number[];
  downbeats?: number[];
  bpm?: number;
}

/* -------------------------------- constants ------------------------------- */

/** Planned cuts are never closer than this to each other, to 0 or to the end. */
export const MIN_GAP = 0.25;
/** A cut is "on the beat" within this many seconds of a beat. */
export const BEAT_TOL = 0.08;
/** Beat buckets for shot length, in beats (the last one is "16 or more"). */
export const BEAT_BUCKETS: readonly number[] = [0.5, 1, 2, 4, 8, 16];
const ARC_SLICES = 8;
/** Safety cap so a silly duration cannot make the planner run forever. */
const MAX_CUTS = 10000;
const SLACK = 1e-9;

/* --------------------------------- helpers -------------------------------- */

function fin(x: unknown): x is number {
  return typeof x === "number" && Number.isFinite(x);
}

function median(xs: readonly number[]): number {
  if (!xs.length) return 0;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2;
}

function sum(xs: readonly number[]): number {
  let s = 0;
  for (const x of xs) s += x;
  return s;
}

function clamp(x: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, x));
}

/** Finite values, sorted, exact duplicates (within 1 µs) removed. */
function cleanTimes(xs: readonly number[] | undefined): number[] {
  if (!Array.isArray(xs)) return [];
  const s = xs.filter(fin).sort((a, b) => a - b);
  const out: number[] = [];
  for (const x of s) if (!out.length || x - out[out.length - 1]! > 1e-6) out.push(x);
  return out;
}

/** Index of the first element ≥ t in a sorted list. */
function lowerBound(sorted: readonly number[], t: number): number {
  let lo = 0;
  let hi = sorted.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (sorted[mid]! < t) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

/** Distance from t to the nearest element of a sorted list (Infinity for an empty list). */
function nearestDist(sorted: readonly number[], t: number): number {
  const v = nearestValue(sorted, t);
  return v === undefined ? Infinity : Math.abs(v - t);
}

function nearestValue(sorted: readonly number[], t: number): number | undefined {
  if (!sorted.length) return undefined;
  const i = lowerBound(sorted, t);
  const a = sorted[i - 1];
  const b = sorted[i];
  if (a === undefined) return b;
  if (b === undefined) return a;
  return t - a <= b - t ? a : b;
}

function union(a: readonly number[], b: readonly number[]): number[] {
  return cleanTimes([...a, ...b]);
}

/** Beat period in seconds: from bpm, else the median gap between beats (at least two), else undefined. */
function beatPeriod(bpm: number | undefined, beats: readonly number[]): number | undefined {
  if (fin(bpm) && bpm > 0) return 60 / bpm;
  if (beats.length >= 2) {
    const gaps: number[] = [];
    for (let i = 1; i < beats.length; i++) gaps.push(beats[i]! - beats[i - 1]!);
    const g = median(gaps);
    if (g > 1e-6) return g;
  }
  return undefined;
}

function bucketIndex(beatsLong: number): number {
  const edges = [Math.SQRT1_2, Math.SQRT2, 2 * Math.SQRT2, 4 * Math.SQRT2, 8 * Math.SQRT2];
  for (let i = 0; i < edges.length; i++) if (beatsLong < edges[i]!) return i;
  return edges.length;
}

/** Small seeded generator (mulberry32): same seed, same sequence. */
function rng(seed: number): () => number {
  let a = (Math.floor(fin(seed) ? seed : 1) >>> 0) ^ 0x9e3779b9;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Value of a list of {key, share} by counting: shares sum to 1, heaviest first, ties in first-seen order. */
function shares<K extends string>(items: readonly string[], key: K): ({ [P in K]: string } & { share: number })[] {
  const counts = new Map<string, number>();
  for (const it of items) counts.set(it, (counts.get(it) ?? 0) + 1);
  const n = items.length || 1;
  return [...counts.entries()]
    .map(([k, c], i) => ({ k, c, i }))
    .sort((a, b) => b.c - a.c || a.i - b.i)
    .map(({ k, c }) => ({ [key]: k, share: c / n }) as { [P in K]: string } & { share: number });
}

function deepCopy<T>(x: T): T {
  return JSON.parse(JSON.stringify(x)) as T;
}

/* ------------------------------ rhythm measure ----------------------------- */

function arcOf(bounds: readonly number[], duration: number, med: number): number[] {
  const arc: number[] = [];
  if (!(duration > 0) || !(med > 0) || bounds.length < 2) return new Array<number>(ARC_SLICES).fill(1);
  for (let j = 0; j < ARC_SLICES; j++) {
    const a = (j * duration) / ARC_SLICES;
    const b = ((j + 1) * duration) / ARC_SLICES;
    let density = 0;
    for (let i = 1; i < bounds.length; i++) {
      const s = bounds[i - 1]!;
      const e = bounds[i]!;
      const len = e - s;
      if (len <= 0) continue;
      const ov = Math.min(b, e) - Math.max(a, s);
      if (ov > 0) density += ov / len;
    }
    arc.push(density > 0 ? (b - a) / density / med : 1);
  }
  return arc;
}

function measureRhythm(cuts: readonly number[], duration: number, beats: readonly number[], downs: readonly number[], bpmIn: number | undefined): Dna["rhythm"] {
  const period = beatPeriod(bpmIn, beats);
  const bpm = fin(bpmIn) && bpmIn > 0 ? bpmIn : period ? 60 / period : undefined;
  if (!(duration > 0)) {
    const r: Dna["rhythm"] = { shotCount: 0, duration: 0, shotMedian: 0, shotMean: 0, shotMin: 0, shotMax: 0, arc: new Array<number>(ARC_SLICES).fill(1), firstCut: 0 };
    if (bpm !== undefined) r.bpm = bpm;
    return r;
  }
  const bounds = [0, ...cuts, duration];
  const lens: number[] = [];
  for (let i = 1; i < bounds.length; i++) lens.push(bounds[i]! - bounds[i - 1]!);
  const med = median(lens);
  const r: Dna["rhythm"] = {
    shotCount: lens.length,
    duration,
    shotMedian: med,
    shotMean: sum(lens) / lens.length,
    shotMin: Math.min(...lens),
    shotMax: Math.max(...lens),
    arc: arcOf(bounds, duration, med),
    firstCut: cuts[0] ?? duration,
  };
  if (bpm !== undefined) r.bpm = bpm;
  if (period) {
    const counts = new Array<number>(BEAT_BUCKETS.length).fill(0);
    for (const l of lens) {
      const k = bucketIndex(l / period);
      counts[k] = counts[k]! + 1;
    }
    r.beatBuckets = counts.map((c) => c / lens.length);
  }
  const grid = union(beats, downs);
  if (grid.length && cuts.length) r.onBeat = cuts.filter((c) => nearestDist(grid, c) <= BEAT_TOL + SLACK).length / cuts.length;
  if (downs.length && cuts.length) r.onDownbeat = cuts.filter((c) => nearestDist(downs, c) <= BEAT_TOL + SLACK).length / cuts.length;
  return r;
}

/**
 * Style DNA of a finished video from its cut list. `cuts` are seconds (sorted; 0, the end, anything outside the
 * running time, non-numbers and cuts within 1 ms of the previous one are dropped). `beats` / `downbeats` / `bpm`
 * describe the music when known. `colour` is carried along as-is.
 */
export function dnaFromCuts(o: { name: string; cuts: number[]; duration: number; beats?: number[]; downbeats?: number[]; bpm?: number; colour?: ColourStats }): Dna {
  const duration = fin(o.duration) && o.duration > 0 ? o.duration : 0;
  const cuts: number[] = [];
  for (const c of cleanTimes(o.cuts)) {
    if (c <= 1e-6 || c >= duration - 1e-6) continue;
    if (cuts.length && c - cuts[cuts.length - 1]! < 1e-3) continue;
    cuts.push(c);
  }
  const dna: Dna = {
    v: 1,
    name: String(o.name ?? ""),
    source: "video",
    samples: 1,
    rhythm: measureRhythm(cuts, duration, cleanTimes(o.beats), cleanTimes(o.downbeats), o.bpm),
  };
  if (o.colour) dna.colour = deepCopy(o.colour);
  return dna;
}

/* ------------------------------ project reading ---------------------------- */

interface Shot {
  clip: Clip;
  track: Track;
  /** Index in p.tracks: lower = higher on screen (tracks are listed top first). */
  layer: number;
  start: number;
  end: number;
}

function tv(c: Clip, prop: "x" | "y" | "scale" | "scaleX" | "scaleY" | "opacity", local: number): number {
  const d = prop === "x" || prop === "y" ? 0 : 1;
  return valueAt(c.transform[prop], local, d);
}

/** Picture kinds that can be a shot: media with a video/image asset (or a missing asset), or an opaque scene. */
function isPictureKind(p: Project, c: Clip): boolean {
  if (!(c.dur > 0)) return false;
  if (c.kind === "media") {
    const a = findAsset(p, c.assetId);
    return !a || a.kind === "video" || a.kind === "image";
  }
  if (c.kind === "anim") {
    const s = findScene(p, c.sceneId);
    return !!s && s.background !== "";
  }
  return false;
}

/**
 * Share of the frame (0..1) a picture clip covers at clip-local time `local`, following the compositor: the source
 * (asset or scene size; the frame size when unknown) is cropped, fitted (none: source pixels; fill: the frame;
 * contain / cover: the cropped source times min / max of frameW / srcW and frameH / srcH), scaled by
 * scale × scaleX / scale × scaleY, centred at the frame centre plus (x, y), clipped to the frame, intersected with
 * the frame window `win` when present, and multiplied by the mask's w × h (× π/4 for an ellipse; 1 − that when
 * inverted) when a mask is on. Rotation and corner pin are ignored.
 */
export function pictureArea(p: Project, c: Clip, local = c.dur / 2): number {
  const W = p.width > 0 ? p.width : 1080;
  const H = p.height > 0 ? p.height : 1920;
  let aw = W;
  let ah = H;
  if (c.kind === "media") {
    const a = findAsset(p, c.assetId);
    if (a && a.width > 0 && a.height > 0) {
      aw = a.width;
      ah = a.height;
    }
  } else if (c.kind === "anim") {
    const sc = findScene(p, c.sceneId);
    if (sc && sc.width > 0 && sc.height > 0) {
      aw = sc.width;
      ah = sc.height;
    }
  }
  const cr = c.crop ?? { l: 0, t: 0, r: 0, b: 0 };
  const sw = aw * Math.max(0.02, 1 - clamp(cr.l, 0, 1) - clamp(cr.r, 0, 1));
  const sh = ah * Math.max(0.02, 1 - clamp(cr.t, 0, 1) - clamp(cr.b, 0, 1));
  let qw = sw;
  let qh = sh;
  if (c.fit === "fill") {
    qw = W;
    qh = H;
  } else if (c.fit === "contain" || c.fit === "cover") {
    const k = c.fit === "cover" ? Math.max(W / sw, H / sh) : Math.min(W / sw, H / sh);
    qw = sw * k;
    qh = sh * k;
  }
  const sc = tv(c, "scale", local);
  const w = qw * Math.abs(sc * tv(c, "scaleX", local));
  const h = qh * Math.abs(sc * tv(c, "scaleY", local));
  const cx = W / 2 + tv(c, "x", local);
  const cy = H / 2 + tv(c, "y", local);
  let x0 = Math.max(0, cx - w / 2);
  let y0 = Math.max(0, cy - h / 2);
  let x1 = Math.min(W, cx + w / 2);
  let y1 = Math.min(H, cy + h / 2);
  if (c.win && c.win.length) {
    const wv = evaluateClip(p, c, c.start + local).win;
    if (wv) {
      x0 = Math.max(x0, Math.min(wv[0], wv[2]) * W);
      x1 = Math.min(x1, Math.max(wv[0], wv[2]) * W);
      y0 = Math.max(y0, Math.min(wv[1], wv[3]) * H);
      y1 = Math.min(y1, Math.max(wv[1], wv[3]) * H);
    }
  }
  let area = (Math.max(0, x1 - x0) * Math.max(0, y1 - y0)) / (W * H);
  if (c.mask && c.mask.shape !== "none") {
    const m = clamp(c.mask.w, 0, 1) * clamp(c.mask.h, 0, 1) * (c.mask.shape === "ellipse" ? Math.PI / 4 : 1);
    area *= c.mask.invert ? 1 - m : m;
  }
  return fin(area) ? clamp(area, 0, 1) : 0;
}

/** Takes part in the picture (before the backdrop test): see the rules at the top of the file. */
function takesPart(p: Project, c: Clip): boolean {
  if (!isPictureKind(p, c)) return false;
  if (c.blend !== "normal" || c.chroma?.enabled) return false;
  const mid = c.dur / 2;
  if (tv(c, "opacity", mid) < 0.9) return false;
  return pictureArea(p, c, mid) >= 0.12;
}

/** Opaque and covering (almost) the whole frame at timeline time t — what can hide a cut beneath it. */
function fullFrameAt(p: Project, c: Clip, t: number): boolean {
  if (c.mask && c.mask.shape !== "none") return false;
  if (c.win && c.win.length) return false;
  if (c.pin) return false;
  const local = clamp(t - c.start, 0, c.dur);
  return tv(c, "opacity", local) >= 0.99 && pictureArea(p, c, local) >= 0.95;
}

/** Blur at the clip start in px of a 1920-high frame (constant, or the first key). */
function blurPx(p: Project, c: Clip): number {
  const b = c.grade.blur;
  const v = typeof b === "number" ? b : Array.isArray(b) && b.length ? b[0]!.v : 0;
  return (v * 1920) / Math.max(1, p.height);
}

/** Every taking-part clip of the visible video tracks, with the backdrops marked. */
function picturesOf(p: Project): { all: Shot[]; backdrops: Set<Clip> } {
  const all: Shot[] = [];
  p.tracks.forEach((track, layer) => {
    if (track.kind !== "video" || track.hidden) return;
    for (const clip of track.clips) if (takesPart(p, clip)) all.push({ clip, track, layer, start: clip.start, end: clip.start + clip.dur });
  });
  const backdrops = new Set<Clip>();
  for (const s of all) {
    if (blurPx(p, s.clip) < 20) continue;
    // How much of its length is under other taking-part clips on higher tracks (their union).
    const iv = all
      .filter((o) => o.layer < s.layer && o.start < s.end && o.end > s.start)
      .map((o) => [Math.max(s.start, o.start), Math.min(s.end, o.end)] as const)
      .sort((x, y) => x[0] - y[0]);
    let covered = 0;
    let curA = -Infinity;
    let curB = -Infinity;
    for (const [a, b] of iv) {
      if (a > curB) {
        if (curB > curA) covered += curB - curA;
        curA = a;
        curB = b;
      } else curB = Math.max(curB, b);
    }
    if (curB > curA) covered += curB - curA;
    if (covered >= 0.8 * (s.end - s.start) - 1e-9) backdrops.add(s.clip);
  }
  return { all, backdrops };
}

/**
 * Is this clip a shot for measuring (also used by the app to pick clips for colour)? True for every taking-part
 * picture clip that is not a backdrop — split-screen panels included. See the rules at the top of the file.
 */
export function isShotClip(p: Project, c: Clip): boolean {
  const { all, backdrops } = picturesOf(p);
  return all.some((s) => s.clip === c) && !backdrops.has(c);
}

/**
 * The stricter test the rhythm re-timing uses: one picture filling the frame on its own. Measured at the middle of
 * the clip: blend normal, opacity ≥ 0.99, scale × scaleX and scale × scaleY ≥ 0.95, |x| and |y| ≤ 5 % of the frame,
 * no chroma key, mask, frame window or corner pin, no crop side above 5 %.
 */
function isFullFrameShot(p: Project, c: Clip): boolean {
  if (!isPictureKind(p, c)) return false;
  const mid = c.dur / 2;
  if (c.blend !== "normal") return false;
  if (tv(c, "opacity", mid) < 0.99) return false;
  const sc = tv(c, "scale", mid);
  if (Math.abs(sc * tv(c, "scaleX", mid)) < 0.95 || Math.abs(sc * tv(c, "scaleY", mid)) < 0.95) return false;
  if (Math.abs(tv(c, "x", mid)) > 0.05 * p.width || Math.abs(tv(c, "y", mid)) > 0.05 * p.height) return false;
  if (c.chroma?.enabled) return false;
  if (c.mask && c.mask.shape !== "none") return false;
  if (c.win && c.win.length) return false;
  if (c.pin) return false;
  if (c.crop && Math.max(c.crop.l, c.crop.t, c.crop.r, c.crop.b) > 0.05) return false;
  return true;
}

function throughEdit(a: Shot, b: Shot, frame: number): boolean {
  if (a.track !== b.track) return false;
  const x = a.clip;
  const y = b.clip;
  if (!x.assetId || x.assetId !== y.assetId || x.kind !== "media" || y.kind !== "media") return false;
  if (x.reverse || y.reverse || x.hold || y.hold || x.speedKeys?.length || y.speedKeys?.length) return false;
  if (Math.abs(x.speed - y.speed) > 1e-6) return false;
  if (Math.abs(x.start + x.dur - y.start) > frame) return false;
  return Math.abs(x.in + x.dur * x.speed - y.in) <= frame * Math.max(1, x.speed);
}

interface CutInfo {
  t: number;
  /** Clips whose end / start make this cut. */
  ends: Clip[];
  starts: Clip[];
}

/**
 * Cuts: moments where the set of visible picture clips changes (see the rules at the top of the file). `shots` are
 * the non-backdrop taking-part clips; `all` also holds the backdrops (they can hide nothing and cut nothing, but a
 * full-frame clip of any kind hides boundaries beneath it).
 */
function cutsOf(p: Project, shots: readonly Shot[], all: readonly Shot[], duration: number): CutInfo[] {
  const frame = 1 / Math.max(1, fin(p.fps) ? p.fps : 30);
  type B = { t: number; s: Shot; edge: "start" | "end" };
  const bounds: B[] = [];
  for (const s of shots) {
    bounds.push({ t: s.start, s, edge: "start" }, { t: s.end, s, edge: "end" });
  }
  const live = bounds.filter((b) => b.t > frame / 2 && b.t < duration - frame / 2).sort((a, b) => a.t - b.t);
  const hidden = (b: B): boolean =>
    all.some((o) => o.layer < b.s.layer && o.start < b.t - 1e-6 && o.end > b.t + 1e-6 && fullFrameAt(p, o.clip, b.t));
  const through = (b: B): boolean =>
    b.edge === "end"
      ? shots.some((o) => o !== b.s && Math.abs(o.start - b.t) <= frame && throughEdit(b.s, o, frame))
      : shots.some((o) => o !== b.s && Math.abs(o.end - b.t) <= frame && throughEdit(o, b.s, frame));
  const groups: B[][] = [];
  for (const b of live) {
    const g = groups[groups.length - 1];
    if (g && b.t - g[0]!.t < frame - 1e-9) g.push(b);
    else groups.push([b]);
  }
  const out: CutInfo[] = [];
  for (const g of groups) {
    const real = g.filter((b) => !through(b) && !hidden(b));
    if (!real.length) continue;
    out.push({ t: g[0]!.t, ends: real.filter((b) => b.edge === "end").map((b) => b.s.clip), starts: real.filter((b) => b.edge === "start").map((b) => b.s.clip) });
  }
  return out;
}

function isMoving(c: Clip): boolean {
  for (const k of ["x", "y", "scale", "scaleX", "scaleY"] as const) {
    const a: Animatable | undefined = c.transform[k];
    if (isAnimated(a) && a.some((kf) => Math.abs(kf.v - a[0]!.v) > 1e-6)) return true;
  }
  return false;
}

function textClips(p: Project): Clip[] {
  const out: Clip[] = [];
  for (const t of p.tracks) if (t.kind === "video" && !t.hidden) for (const c of t.clips) if (c.kind === "text" && c.text) out.push(c);
  return out;
}

/** Font size of a text clip as a fraction of the frame height (size is px at 1920 tall, times the clip's vertical scale). */
function textSizeRel(c: Clip): number {
  const mid = c.dur / 2;
  const k = Math.abs(tv(c, "scale", mid) * tv(c, "scaleY", mid));
  return ((c.text?.size ?? 0) / 1920) * k;
}

function textYRel(p: Project, c: Clip): number {
  return clamp(tv(c, "y", c.dur / 2) / Math.max(1, p.height), -0.5, 0.5);
}

function words(s: string): number {
  return s.split(/\s+/).filter((w) => w.length > 0).length;
}

function isUpper(s: string): boolean {
  return s.toLowerCase() !== s && s.toUpperCase() === s;
}

function measureType(p: Project): Dna["type"] | undefined {
  const list = textClips(p);
  if (!list.length) return undefined;
  const totalDur = sum(list.map((c) => Math.max(0, c.dur)));
  const totalWords = sum(list.map((c) => words(c.text!.text)));
  return {
    fonts: shares(list.map((c) => c.text!.font), "font"),
    sizeRel: median(list.map(textSizeRel)),
    colors: shares(list.map((c) => c.text!.color), "color"),
    yRel: median(list.map((c) => textYRel(p, c))),
    animate: shares(list.map((c) => c.text!.animate), "name"),
    wordsPerSec: totalDur > 0 ? totalWords / totalDur : 0,
    upperShare: list.filter((c) => isUpper(c.text!.text)).length / list.length,
  };
}

function transitionAt(cut: CutInfo): string {
  for (const c of cut.ends) if (c.transitionOut.kind !== "none" && c.transitionOut.dur > 0) return c.transitionOut.kind;
  for (const c of cut.starts) if (c.transitionIn.kind !== "none" && c.transitionIn.dur > 0) return c.transitionIn.kind;
  return "cut";
}

function measureMotion(shots: readonly Shot[], cuts: readonly CutInfo[]): Dna["motion"] | undefined {
  if (!shots.length) return undefined;
  const clips = [...new Set(shots.map((s) => s.clip))];
  return {
    movingShare: clips.filter(isMoving).length / clips.length,
    transitions: shares(cuts.map(transitionAt), "kind"),
  };
}

/**
 * Style DNA of a timeline: shots and cuts (rules at the top of the file), titles (text clips on visible video
 * tracks) and motion (animated position/scale of shots, and the transition at each cut: the outgoing shot's
 * transitionOut, else the incoming shot's transitionIn, else "cut"). `type` is left out when there is no text clip
 * and `motion` when there is no shot.
 */
export function dnaFromProject(p: Project, o: { name?: string; beats?: number[]; downbeats?: number[]; bpm?: number; colour?: ColourStats } = {}): Dna {
  const { all, backdrops } = picturesOf(p);
  const shots = all.filter((s) => !backdrops.has(s.clip));
  const duration = all.reduce((m, s) => Math.max(m, s.end), 0);
  const cuts = cutsOf(p, shots, all, duration);
  const dna = dnaFromCuts({ name: o.name ?? p.name, cuts: cuts.map((c) => c.t), duration, beats: o.beats, downbeats: o.downbeats, bpm: o.bpm, colour: o.colour });
  dna.source = "project";
  const type = measureType(p);
  if (type) dna.type = type;
  const motion = measureMotion(shots, cuts);
  if (motion) dna.motion = motion;
  return dna;
}

/* ---------------------------------- merge --------------------------------- */

function wmean(parts: readonly { x: number; w: number }[]): number {
  const W = sum(parts.map((p) => p.w));
  return W > 0 ? sum(parts.map((p) => p.x * p.w)) / W : 0;
}

function mergeShares<K extends string>(parts: readonly { list: readonly ({ share: number } & Record<K, string>)[]; w: number }[], key: K): ({ [P in K]: string } & { share: number })[] {
  const W = sum(parts.map((p) => p.w));
  const acc = new Map<string, { s: number; i: number }>();
  let i = 0;
  for (const p of parts) {
    for (const it of p.list) {
      const cur = acc.get(it[key]);
      if (cur) cur.s += it.share * p.w;
      else acc.set(it[key], { s: it.share * p.w, i: i++ });
    }
  }
  return [...acc.entries()]
    .sort((a, b) => b[1].s - a[1].s || a[1].i - b[1].i)
    .map(([k, v]) => ({ [key]: k, share: W > 0 ? v.s / W : 0 }) as { [P in K]: string } & { share: number });
}

function mergeVec(parts: readonly { v: readonly number[]; w: number }[], len: number): number[] {
  const out: number[] = [];
  for (let i = 0; i < len; i++) out.push(wmean(parts.map((p) => ({ x: p.v[i] ?? 0, w: p.w }))));
  return out;
}

function normalise(v: number[]): number[] {
  const s = sum(v);
  return s > 0 ? v.map((x) => x / s) : v;
}

/**
 * Merge colour statistics, each weighted by `w`: means and histograms are weighted means (histograms renormalised),
 * the Lab spread is the pooled standard deviation (within-part variance plus the spread of the part means), the pixel
 * count adds up, and the palettes are pooled (colours closer than 0.1 in RGB are joined), heaviest six kept.
 */
function mergeColour(parts: readonly { c: ColourStats; w: number }[]): ColourStats {
  const W = sum(parts.map((p) => p.w)) || 1;
  const labMean = [0, 1, 2].map((i) => wmean(parts.map((p) => ({ x: p.c.labMean[i]!, w: p.w })))) as [number, number, number];
  const labStd = [0, 1, 2].map((i) => {
    let v = 0;
    for (const p of parts) v += p.w * (p.c.labStd[i]! ** 2 + (p.c.labMean[i]! - labMean[i]!) ** 2);
    return Math.sqrt(v / W);
  }) as [number, number, number];
  const hist = (get: (c: ColourStats) => number[]) => normalise(mergeVec(parts.map((p) => ({ v: get(p.c), w: p.w })), get(parts[0]!.c).length));
  const pal: { rgb: [number, number, number]; weight: number }[] = [];
  for (const p of parts) {
    for (const e of p.c.palette) {
      const w = (e.weight * p.w) / W;
      const near = pal.find((q) => Math.hypot(q.rgb[0] - e.rgb[0], q.rgb[1] - e.rgb[1], q.rgb[2] - e.rgb[2]) < 0.1);
      if (near) {
        const t = near.weight + w;
        near.rgb = [0, 1, 2].map((i) => (t > 0 ? (near.rgb[i]! * near.weight + e.rgb[i]! * w) / t : near.rgb[i]!)) as [number, number, number];
        near.weight = t;
      } else pal.push({ rgb: [...e.rgb] as [number, number, number], weight: w });
    }
  }
  pal.sort((a, b) => b.weight - a.weight);
  const top = pal.slice(0, 6);
  const tw = sum(top.map((e) => e.weight));
  return {
    n: sum(parts.map((p) => p.c.n)),
    labMean,
    labStd,
    lumaHist: hist((c) => c.lumaHist),
    rgbHist: [hist((c) => c.rgbHist[0]), hist((c) => c.rgbHist[1]), hist((c) => c.rgbHist[2])],
    satMean: wmean(parts.map((p) => ({ x: p.c.satMean, w: p.w }))),
    black: wmean(parts.map((p) => ({ x: p.c.black, w: p.w }))),
    white: wmean(parts.map((p) => ({ x: p.c.white, w: p.w }))),
    palette: top.map((e) => ({ rgb: e.rgb, weight: tw > 0 ? e.weight / tw : 0 })),
  };
}

/**
 * The personal signature: merge records, each weighted by its `samples` (a record with samples 0 counts as 1, so
 * nothing is silently ignored). Rules:
 *  - samples, shotCount and duration add up; shotMin / shotMax are the smallest / largest; every other number
 *    (medians, means, arc, first cut, shares) is the weighted mean;
 *  - an optional field (bpm, beat buckets, onBeat, onDownbeat, type, motion) is merged over the records that have
 *    it, and is left out only when none has it; share lists are pooled and stay heaviest first;
 *  - colour is merged only when EVERY record has it (a signature should not claim a look that part of it never
 *    measured); otherwise the merged record has no colour.
 * One record in → the same numbers out (as a "merged" record). No record → an empty merged record.
 */
export function mergeDna(list: Dna[], name?: string): Dna {
  if (list.length === 1) {
    const d = deepCopy(list[0]!);
    d.source = "merged";
    if (name !== undefined) d.name = name;
    return d;
  }
  if (!list.length) return { v: 1, name: name ?? "Signature", source: "merged", samples: 0, rhythm: measureRhythm([], 0, [], [], undefined) };
  const w = (d: Dna) => (d.samples > 0 ? d.samples : 1);
  const R = list.map((d) => ({ r: d.rhythm, w: w(d) }));
  const num = (get: (r: Dna["rhythm"]) => number) => wmean(R.map((p) => ({ x: get(p.r), w: p.w })));
  const opt = (get: (r: Dna["rhythm"]) => number | undefined) => {
    const parts = R.filter((p) => fin(get(p.r))).map((p) => ({ x: get(p.r)!, w: p.w }));
    return parts.length ? wmean(parts) : undefined;
  };
  const rhythm: Dna["rhythm"] = {
    shotCount: sum(R.map((p) => p.r.shotCount)),
    duration: sum(R.map((p) => p.r.duration)),
    shotMedian: num((r) => r.shotMedian),
    shotMean: num((r) => r.shotMean),
    shotMin: Math.min(...R.map((p) => p.r.shotMin)),
    shotMax: Math.max(...R.map((p) => p.r.shotMax)),
    arc: mergeVec(R.map((p) => ({ v: p.r.arc, w: p.w })), ARC_SLICES),
    firstCut: num((r) => r.firstCut),
  };
  const bpm = opt((r) => r.bpm);
  if (bpm !== undefined) rhythm.bpm = bpm;
  const bb = R.filter((p) => p.r.beatBuckets);
  if (bb.length) rhythm.beatBuckets = normalise(mergeVec(bb.map((p) => ({ v: p.r.beatBuckets!, w: p.w })), BEAT_BUCKETS.length));
  const onBeat = opt((r) => r.onBeat);
  if (onBeat !== undefined) rhythm.onBeat = onBeat;
  const onDown = opt((r) => r.onDownbeat);
  if (onDown !== undefined) rhythm.onDownbeat = onDown;
  const out: Dna = { v: 1, name: name ?? "Signature", source: "merged", samples: sum(list.map((d) => d.samples)), rhythm };
  if (list.every((d) => d.colour)) out.colour = mergeColour(list.map((d) => ({ c: d.colour!, w: w(d) })));
  const T = list.filter((d) => d.type).map((d) => ({ t: d.type!, w: w(d) }));
  if (T.length) {
    const m = (get: (t: NonNullable<Dna["type"]>) => number) => wmean(T.map((p) => ({ x: get(p.t), w: p.w })));
    out.type = {
      fonts: mergeShares(T.map((p) => ({ list: p.t.fonts, w: p.w })), "font"),
      sizeRel: m((t) => t.sizeRel),
      colors: mergeShares(T.map((p) => ({ list: p.t.colors, w: p.w })), "color"),
      yRel: m((t) => t.yRel),
      animate: mergeShares(T.map((p) => ({ list: p.t.animate, w: p.w })), "name"),
      wordsPerSec: m((t) => t.wordsPerSec),
      upperShare: m((t) => t.upperShare),
    };
  }
  const M = list.filter((d) => d.motion).map((d) => ({ m: d.motion!, w: w(d) }));
  if (M.length) out.motion = { movingShare: wmean(M.map((p) => ({ x: p.m.movingShare, w: p.w }))), transitions: mergeShares(M.map((p) => ({ list: p.m.transitions, w: p.w })), "kind") };
  return out;
}

/* --------------------------------- planning -------------------------------- */

function arcAt(arc: readonly number[], x: number): number {
  const n = arc.length;
  if (!n) return 1;
  const pos = clamp(x, 0, 1) * n - 0.5;
  if (pos <= 0) return arc[0]!;
  if (pos >= n - 1) return arc[n - 1]!;
  const i = Math.floor(pos);
  const f = pos - i;
  return arc[i]! * (1 - f) + arc[i + 1]! * f;
}

/** Largest-remainder counts for shares that should add up to n. */
function quota(sh: readonly number[], n: number): number[] {
  const s = sum(sh) || 1;
  const raw = sh.map((x) => (Math.max(0, x) / s) * n);
  const out = raw.map(Math.floor);
  let left = n - sum(out);
  const order = raw.map((r, i) => ({ i, f: r - Math.floor(r) })).sort((a, b) => b.f - a.f || a.i - b.i);
  for (const o of order) {
    if (left <= 0) break;
    out[o.i] = out[o.i]! + 1;
    left--;
  }
  return out;
}

/** The shot lengths a plan draws from (seconds), sorted ascending. */
function lengthPool(r: Dna["rhythm"], D: number, period: number | undefined, scale: number, next: () => number): number[] {
  const pool: number[] = [];
  if (r.beatBuckets && period) {
    const lens = BEAT_BUCKETS.map((b) => b * period * scale);
    const meanLen = sum(r.beatBuckets.map((s, i) => s * lens[i]!)) / (sum(r.beatBuckets) || 1);
    const n = clamp(Math.round(D / Math.max(MIN_GAP, meanLen)), 1, MAX_CUTS);
    quota(r.beatBuckets, n).forEach((c, i) => {
      for (let k = 0; k < c; k++) pool.push(Math.max(MIN_GAP, lens[i]!));
    });
  } else {
    // Log-normal shot lengths through the DNA's median, with the spread that gives its mean/median ratio,
    // drawn at jittered quantiles (logistic approximation of the normal) and kept inside its min..max.
    const m = r.shotMedian * scale;
    const ratio = r.shotMedian > 0 ? r.shotMean / r.shotMedian : 1;
    const sigma = ratio > 1.0001 ? Math.min(1.2, Math.sqrt(2 * Math.log(ratio))) : 0.15;
    const lo = Math.max(MIN_GAP, r.shotMin * scale);
    const hi = Math.max(lo, r.shotMax * scale);
    const n = clamp(Math.round(D / Math.max(MIN_GAP, r.shotMean * scale)), 1, MAX_CUTS);
    for (let i = 0; i < n; i++) {
      const q = (i + 0.25 + 0.5 * next()) / n;
      const z = Math.log(q / (1 - q)) * (Math.sqrt(3) / Math.PI);
      pool.push(clamp(m * Math.exp(sigma * z), lo, hi));
    }
  }
  return pool.sort((a, b) => a - b);
}

/** Take a length near `desired` (log scale) out of the sorted pool; the seed picks among the three nearest values. */
function take(pool: number[], desired: number, next: () => number): number {
  const i = lowerBound(pool, desired);
  const seen: { v: number; idx: number; d: number }[] = [];
  for (let k = Math.max(0, i - 6); k < Math.min(pool.length, i + 6); k++) {
    const v = pool[k]!;
    if (seen.some((s) => Math.abs(s.v - v) < 1e-9)) continue;
    seen.push({ v, idx: k, d: Math.abs(Math.log(v / desired)) });
  }
  seen.sort((a, b) => a.d - b.d || a.v - b.v);
  const best = seen[0]!;
  const cand = seen.slice(0, 3).filter((s) => s.d <= best.d + 0.75);
  const weights = [0.6, 0.28, 0.12].slice(0, cand.length);
  let u = next() * sum(weights);
  let pick = cand[0]!;
  for (let k = 0; k < cand.length; k++) {
    u -= weights[k]!;
    if (u <= 0) {
      pick = cand[k]!;
      break;
    }
  }
  pool.splice(pick.idx, 1);
  return pick.v;
}

/** Keep cuts sorted, inside (MIN_GAP, D - MIN_GAP) and at least MIN_GAP apart (dropping the later of two close cuts). */
function tidy(cuts: readonly number[], D: number): number[] {
  const out: number[] = [];
  let last = 0;
  for (const c of [...cuts].filter(fin).sort((a, b) => a - b)) {
    if (c - last < MIN_GAP - SLACK || D - c < MIN_GAP - SLACK) continue;
    out.push(c);
    last = c;
  }
  return out;
}

function planInner(dna: Dna, o: { duration: number } & BeatInfo & { seed?: number }, scale: number): number[] {
  const D = o.duration;
  const r = dna.rhythm;
  if (!fin(D) || D < 2 * MIN_GAP) return [];
  if (!(r.shotCount > 1) || !(r.shotMedian > 0) || !(scale > 0)) return [];
  const next = rng(o.seed ?? 1);
  const beats = cleanTimes(o.beats);
  const downs = cleanTimes(o.downbeats);
  const grid = union(beats, downs);
  const period = beatPeriod(o.bpm, beats);
  const pool = lengthPool(r, D, period, scale, next);
  const typical = median(pool);
  const firstDesired = Math.max(MIN_GAP, typical * (r.firstCut > 0 ? r.firstCut / r.shotMedian : 1));
  const onB = grid.length ? (fin(r.onBeat) ? r.onBeat : 1) : 0;
  const onD = downs.length && fin(r.onDownbeat) ? r.onDownbeat : undefined;
  const half = period ? period / 2 : 0.25;
  let avail = [...pool];
  const cuts: number[] = [];
  let t = 0;
  let onCount = 0;
  let downCount = 0;
  while (cuts.length < MAX_CUTS) {
    if (!avail.length) avail = [...pool];
    let desired = firstDesired;
    if (cuts.length) {
      const d0 = typical * arcAt(r.arc, t / D);
      desired = Math.max(MIN_GAP, typical * arcAt(r.arc, (t + d0 / 2) / D));
    }
    const len = take(avail, desired, next);
    const ideal = t + len;
    if (ideal > D - MIN_GAP) break;
    let s = ideal;
    const i = cuts.length;
    const reach = Math.min(len / 2, Math.max(0.3, period ?? 0.5));
    const ok = (x: number) => x - t >= MIN_GAP - SLACK && D - x >= MIN_GAP - SLACK;
    if (grid.length) {
      const wantBeat = onCount + 1 <= Math.floor(onB * (i + 1) + 0.5);
      if (wantBeat) {
        const wantDown = onD === undefined ? undefined : downCount + 1 <= Math.floor(onD * (i + 1) + 0.5);
        let pickT: number | undefined;
        if (wantDown) {
          const d = nearestValue(downs, ideal);
          if (d !== undefined && Math.abs(d - ideal) <= Math.min(len, Math.max(reach, half * 2)) && ok(d)) pickT = d;
        }
        if (pickT === undefined) {
          // Nearest beat; avoid a downbeat when the style wants fewer downbeat cuts.
          const lo = lowerBound(grid, ideal - reach);
          const opts: number[] = [];
          for (let k = lo; k < grid.length && grid[k]! <= ideal + reach; k++) if (ok(grid[k]!)) opts.push(grid[k]!);
          opts.sort((a, b) => Math.abs(a - ideal) - Math.abs(b - ideal) || a - b);
          const avoid = wantDown === false ? opts.find((x) => nearestDist(downs, x) > BEAT_TOL) : undefined;
          pickT = avoid ?? opts[0];
        }
        if (pickT !== undefined) s = pickT;
      } else if (nearestDist(grid, ideal) <= BEAT_TOL + 0.02) {
        // The style cuts off the beat here: move to the middle between the two surrounding beats.
        const k = lowerBound(grid, ideal);
        const a = grid[k - 1];
        const b = grid[k];
        const cands: number[] = [];
        if (a !== undefined && b !== undefined) cands.push((a + b) / 2);
        const c2 = grid[k + 1];
        if (b !== undefined && c2 !== undefined) cands.push((b + c2) / 2);
        const a0 = grid[k - 2];
        if (a0 !== undefined && a !== undefined) cands.push((a0 + a) / 2);
        const m = cands.filter((x) => ok(x) && nearestDist(grid, x) > BEAT_TOL).sort((x, y) => Math.abs(x - ideal) - Math.abs(y - ideal))[0];
        if (m !== undefined) s = m;
      }
    }
    if (!ok(s)) {
      if (!ok(ideal)) break;
      s = ideal;
    }
    cuts.push(s);
    if (grid.length && nearestDist(grid, s) <= BEAT_TOL + SLACK) onCount++;
    if (downs.length && nearestDist(downs, s) <= BEAT_TOL + SLACK) downCount++;
    t = s;
  }
  return tidy(cuts, D);
}

/**
 * Cut times for a NEW edit of `duration` seconds with this DNA's rhythm.
 *  - Shot lengths: with a beat period for the new music (bpm, or ≥ 2 beats) and beat buckets in the DNA, lengths are
 *    whole bucket lengths in the NEW music's beats, in the DNA's bucket proportions (largest-remainder counts);
 *    otherwise log-normal lengths in seconds through the DNA's median with its mean/median spread, kept in its
 *    min..max. The lengths are laid out in time so the local length follows the arc (and the first shot follows the
 *    DNA's first cut relative to its median); the seed picks among the three nearest candidate lengths each time,
 *    so DNAs with varied shot lengths give different plans for different seeds (a DNA where every shot is the same
 *    length gives the same plan for every seed).
 *  - Snapping, when beats or downbeats are given: each cut goes to the nearest beat within reach (one beat period,
 *    at least 0.3 s, never more than half its shot; a downbeat may be up to one period away when wanted) in the DNA's onBeat proportion (all of them when the DNA has no onBeat), and to a downbeat in the
 *    DNA's onDownbeat proportion (running quotas); cuts the style keeps off the beat are moved to the middle between
 *    two beats when they would land on one.
 *  - Always sorted, inside (0, duration), never closer than 0.25 s to each other, to 0 or to the end. A DNA of one
 *    continuous shot, or a duration under 0.5 s, gives no cuts.
 */
export function planCuts(dna: Dna, o: { duration: number; beats?: number[]; downbeats?: number[]; bpm?: number; seed?: number }): number[] {
  return planInner(dna, o, 1);
}

/* ------------------------------ applying (rhythm) ---------------------------- */

/** A re-timing plan for the shots of a timeline (absolute timeline seconds). */
export type RetimePlan =
  | { ok: true; cuts: number[]; shots: { id: string; start: number; dur: number }[]; scale: number; limited: number }
  | { ok: false; reason: string };

function trackLabel(t: Track): string {
  return t.name || t.id;
}

/**
 * Seconds of timeline this clip can show from its in-point. Known for images and freeze frames (no limit), video
 * assets with a known length ((length − in) / speed), and animation scenes (frames / fps). Unknown for clips with a
 * speed ramp, missing assets or assets of unknown length: then the clip may only shrink (its current duration is the
 * limit). Never below the clip's current duration (a clip already running past its source is not asked for more).
 */
function sourceCap(p: Project, c: Clip): number {
  if (c.hold) return Infinity;
  let cap = c.dur;
  if (c.speedKeys && c.speedKeys.length) return cap;
  const sp = c.speed > 0 ? c.speed : 1;
  if (c.kind === "media") {
    const a = findAsset(p, c.assetId);
    if (a?.kind === "image") return Infinity;
    if (a?.kind === "video" && fin(a.duration) && a.duration > 0) cap = (a.duration - c.in) / sp;
  } else if (c.kind === "anim") {
    const s = findScene(p, c.sceneId);
    if (s && s.fps > 0 && s.frames > 0) cap = (s.frames / s.fps - c.in) / sp;
  }
  return Math.max(cap, c.dur);
}

/** Change the number of cuts to `want` by joining the shortest pair or splitting the longest shot. */
function fitCount(cuts: number[], want: number, S: number, E: number, grid: readonly number[]): number[] | undefined {
  const c = [...cuts];
  while (c.length > want) {
    let best = 0;
    let bestLen = Infinity;
    for (let i = 0; i < c.length; i++) {
      const merged = (c[i + 1] ?? E) - (c[i - 1] ?? S);
      if (merged < bestLen) {
        bestLen = merged;
        best = i;
      }
    }
    c.splice(best, 1);
  }
  while (c.length < want) {
    let best = -1;
    let bestLen = 0;
    for (let i = 0; i <= c.length; i++) {
      const len = (c[i] ?? E) - (c[i - 1] ?? S);
      if (len > bestLen) {
        bestLen = len;
        best = i;
      }
    }
    if (best < 0 || bestLen < 2 * MIN_GAP - SLACK) return undefined;
    const a = c[best - 1] ?? S;
    const b = c[best] ?? E;
    let m = (a + b) / 2;
    const g = nearestValue(grid, m);
    if (g !== undefined && Math.abs(g - m) <= (b - a) / 4 && g - a >= MIN_GAP - SLACK && b - g >= MIN_GAP - SLACK) m = g;
    c.splice(best, 0, m);
  }
  return c;
}

/**
 * Plan how the shots of `p` would be re-timed to the DNA's rhythm, or say why they cannot be. Requirements: at least
 * two shots, one clean sequence (no shot overlaps another, gaps of at most one frame, nothing else on the shot tracks
 * within the sequence, no locked shot track), at least 0.25 s per shot, and enough source in total. The number of
 * shots and the sequence's start and end stay the same. The plan is planCuts over the sequence (beats shifted to
 * it); when that gives a different number of cuts, the DNA's pace is scaled (its arc, spread, bucket proportions and
 * beat snapping are kept) until it gives the right number, and any difference left is fixed by joining the shortest
 * pair / splitting the longest shot. Shots longer than their source allows are then cut to it and the extra time is
 * given to shots with room, in proportion to their room (`limited` counts the shots that hit their limit).
 */
export function planRetime(p: Project, dna: Dna, o: BeatInfo & { seed?: number } = {}): RetimePlan {
  // Split screen: a picture that is not a full-frame shot shares the screen with another picture.
  const pics = picturesOf(p);
  const partial = pics.all.filter((s) => !pics.backdrops.has(s.clip) && !isFullFrameShot(p, s.clip));
  if (partial.some((s) => pics.all.some((o) => o !== s && o.start < s.end - 1e-4 && o.end > s.start + 1e-4))) return { ok: false, reason: "This edit shows several pictures at once, so its cuts were not re-timed." };
  const shots: Shot[] = [];
  p.tracks.forEach((track, layer) => {
    if (track.kind !== "video" || track.hidden) return;
    for (const clip of track.clips) if (isFullFrameShot(p, clip)) shots.push({ clip, track, layer, start: clip.start, end: clip.start + clip.dur });
  });
  shots.sort((a, b) => a.start - b.start || a.layer - b.layer);
  if (shots.length < 2) return { ok: false, reason: shots.length ? "There is only one shot, so there is nothing to re-time." : "There are no shots (full-frame video, image or scene clips) to re-time." };
  const frame = 1 / Math.max(1, fin(p.fps) ? p.fps : 30);
  for (let i = 1; i < shots.length; i++) {
    const a = shots[i - 1]!;
    const b = shots[i]!;
    if (b.start < a.end - 1e-4) return { ok: false, reason: `Shots overlap ("${a.clip.name}" on ${trackLabel(a.track)} and "${b.clip.name}" on ${trackLabel(b.track)} at ${a.end.toFixed(2)} s), so the rhythm was not changed: it only re-times one clean back-to-back sequence of shots.` };
    if (b.start - a.end > frame + 1e-6) return { ok: false, reason: `There is a gap of ${(b.start - a.end).toFixed(2)} s between "${a.clip.name}" and "${b.clip.name}", so the rhythm was not changed: it only re-times one clean back-to-back sequence of shots.` };
  }
  const S = shots[0]!.start;
  const E = shots[shots.length - 1]!.end;
  const ids = new Set(shots.map((s) => s.clip.id));
  for (const tr of new Set(shots.map((s) => s.track))) {
    if (tr.locked) return { ok: false, reason: `Track ${trackLabel(tr)} is locked, so the rhythm was not changed.` };
    const other = tr.clips.find((c) => !ids.has(c.id) && c.start < E - 1e-4 && c.start + c.dur > S + 1e-4);
    if (other) return { ok: false, reason: `"${other.name}" sits between the shots on ${trackLabel(tr)}, so the rhythm was not changed: it only re-times one clean sequence of shots.` };
  }
  const T = E - S;
  const N = shots.length;
  if (T < N * MIN_GAP - SLACK) return { ok: false, reason: `${N} shots in ${T.toFixed(2)} s would be shorter than 0.25 s each, so the rhythm was not changed.` };
  if (!(dna.rhythm.shotCount > 1) || !(dna.rhythm.shotMedian > 0)) return { ok: false, reason: "The style is one continuous shot (it has no cuts), so there is no rhythm to copy." };
  const caps = shots.map((s) => sourceCap(p, s.clip));
  if (caps.some((c) => c < MIN_GAP - SLACK)) return { ok: false, reason: "A shot has less than 0.25 s of picture, so the rhythm was not changed." };
  if (sum(caps) < T - 1e-6) return { ok: false, reason: "The shots do not have enough source footage to fill the same running time, so the rhythm was not changed." };

  const rel = (xs: number[] | undefined) => (xs ? cleanTimes(xs).map((b) => b - S) : undefined);
  const po = { duration: T, beats: rel(o.beats), downbeats: rel(o.downbeats), bpm: o.bpm, seed: o.seed };
  const grid = union(cleanTimes(po.beats), cleanTimes(po.downbeats));
  let scale = 1;
  let cuts = planInner(dna, po, 1);
  if (cuts.length !== N - 1) {
    // Pace too fast or slow for this many shots: search the scale (count falls as the scale grows).
    let lo = -6;
    let hi = 6;
    let best = { cuts, scale: 1, err: Math.abs(cuts.length - (N - 1)) };
    for (let it = 0; it < 48 && best.err > 0; it++) {
      const mid = (lo + hi) / 2;
      const s = Math.exp(mid);
      const c = planInner(dna, po, s);
      const err = Math.abs(c.length - (N - 1));
      if (err < best.err || (err === best.err && Math.abs(mid) < Math.abs(Math.log(best.scale)))) best = { cuts: c, scale: s, err };
      if (c.length > N - 1) lo = mid;
      else hi = mid;
    }
    cuts = best.cuts;
    scale = best.scale;
  }
  const fitted = fitCount(cuts, N - 1, 0, T, grid);
  if (!fitted) return { ok: false, reason: "Could not fit the style's rhythm to this many shots, so the rhythm was not changed." };
  // Source limits: cut overlong shots to their limit and give the extra time to shots with room.
  const L: number[] = [];
  for (let i = 0; i < N; i++) L.push((fitted[i] ?? T) - (fitted[i - 1] ?? 0));
  let over = 0;
  let limited = 0;
  for (let i = 0; i < N; i++) {
    if (L[i]! > caps[i]! + SLACK) {
      over += L[i]! - caps[i]!;
      L[i] = caps[i]!;
      limited++;
    }
  }
  if (over > 0) {
    const inf = L.map((_, i) => i).filter((i) => caps[i] === Infinity);
    if (inf.length) {
      const base = sum(inf.map((i) => L[i]!));
      for (const i of inf) L[i] = L[i]! + (base > 0 ? (over * L[i]!) / base : over / inf.length);
    } else {
      const room = L.map((l, i) => Math.max(0, caps[i]! - l));
      const R = sum(room);
      for (let i = 0; i < N; i++) L[i] = Math.min(caps[i]!, L[i]! + (R > 0 ? (over * room[i]!) / R : 0));
    }
  }
  const out: number[] = [];
  let acc = 0;
  for (let i = 0; i < N - 1; i++) {
    acc += L[i]!;
    out.push(acc);
  }
  // After a source fix, put moved cuts back on a beat when both neighbours still fit.
  if (limited && grid.length) {
    for (let i = 0; i < out.length; i++) {
      const g = nearestValue(grid, out[i]!);
      if (g === undefined || Math.abs(g - out[i]!) > 0.3) continue;
      const a = out[i - 1] ?? 0;
      const b = out[i + 1] ?? T;
      if (g - a >= MIN_GAP - SLACK && b - g >= MIN_GAP - SLACK && g - a <= caps[i]! + SLACK && b - g <= caps[i + 1]! + SLACK) out[i] = g;
    }
  }
  const abs = out.map((c) => S + c);
  const planShots = shots.map((s, i) => {
    const start = i === 0 ? S : abs[i - 1]!;
    const end = i === N - 1 ? E : abs[i]!;
    return { id: s.clip.id, start, dur: Math.min(end - start, caps[i]!) };
  });
  return { ok: true, cuts: abs, shots: planShots, scale, limited };
}

/** Commands that move every shot to its planned place without ever overlapping another clip on its track. */
function retimeCommands(p: Project, plan: Extract<RetimePlan, { ok: true }>): Record<string, unknown>[] {
  const cur = new Map<string, Clip>();
  for (const t of p.tracks) for (const c of t.clips) cur.set(c.id, c);
  const changed = plan.shots.filter((s) => {
    const c = cur.get(s.id)!;
    return Math.abs(c.start - s.start) > 1e-9 || Math.abs(c.dur - s.dur) > 1e-9;
  });
  const park = Math.ceil(projectDuration(p)) + 10;
  const cmds: Record<string, unknown>[] = [];
  // 1. Park every changed shot past the end of everything, keeping their order and spacing (no overlaps there).
  for (const s of [...changed].reverse()) cmds.push({ op: "clip.move", id: s.id, start: cur.get(s.id)!.start + park });
  // 2. Bring them back in timeline order. A shot that gets shorter is shortened while parked and then moved; one that
  //    gets longer is moved first (at its old, shorter length, which fits inside its new place) and then lengthened.
  for (const s of changed) {
    const c = cur.get(s.id)!;
    if (s.dur <= c.dur) {
      if (Math.abs(s.dur - c.dur) > 1e-9) cmds.push({ op: "clip.set", id: s.id, patch: { dur: s.dur } });
      cmds.push({ op: "clip.move", id: s.id, start: s.start });
    } else {
      cmds.push({ op: "clip.move", id: s.id, start: s.start });
      cmds.push({ op: "clip.set", id: s.id, patch: { dur: s.dur } });
    }
  }
  return cmds;
}

/* ------------------------------- applying (type) ----------------------------- */

function typeCommands(p: Project, t: NonNullable<Dna["type"]>, notes: string[]): Record<string, unknown>[] {
  const list: { c: Clip; locked: boolean }[] = [];
  for (const tr of p.tracks) if (tr.kind === "video" && !tr.hidden) for (const c of tr.clips) if (c.kind === "text" && c.text) list.push({ c, locked: tr.locked });
  if (!list.length) {
    notes.push("There are no text clips to restyle.");
    return [];
  }
  const free = list.filter((x) => !x.locked).map((x) => x.c);
  if (free.length < list.length) notes.push(`${list.length - free.length} text clip(s) on locked tracks were left as they are.`);
  const font = t.fonts[0]?.font;
  // Colours by rank: this project's most used text colour becomes the style's most used one, and so on.
  const own = shares(list.map((x) => x.c.text!.color.toLowerCase()), "color").map((s) => s.color);
  const colourFor = (c: string): string | undefined => {
    if (!t.colors.length) return undefined;
    const rank = own.indexOf(c.toLowerCase());
    return (t.colors[rank] ?? t.colors[0]!).color;
  };
  const curMed = median(list.map((x) => textSizeRel(x.c)));
  const factor = curMed > 0 && t.sizeRel > 0 ? t.sizeRel / curMed : 1;
  const cmds: Record<string, unknown>[] = [];
  for (const c of free) {
    const st = c.text!;
    const patch: Record<string, unknown> = {};
    if (font && st.font !== font) patch.font = font;
    const col = colourFor(st.color);
    if (col && col.toLowerCase() !== st.color.toLowerCase()) patch.color = col;
    const size = Math.round(clamp(st.size * factor, 4, 4000) * 10) / 10;
    if (Math.abs(size - st.size) > 0.05) patch.size = size;
    if (Object.keys(patch).length) cmds.push({ op: "clip.set", id: c.id, patch: { text: patch } });
  }
  notes.push(cmds.length ? `Restyled ${cmds.length} text clip(s): ${font ? `font ${font}, ` : ""}colours from the style${Math.abs(factor - 1) > 0.005 ? `, sizes ×${factor.toFixed(2)}` : ""}.` : "The text already matches the style's font, colours and size.");
  const yNow = median(list.map((x) => textYRel(p, x.c)));
  if (Math.abs(yNow - t.yRel) > 0.1) notes.push(`The style sets titles ${placeWord(t.yRel)}; yours sit ${placeWord(yNow)}. Move them with clip.value prop y.`);
  const upNow = list.filter((x) => isUpper(x.c.text!.text)).length / list.length;
  if (t.upperShare > 0.6 && upNow < 0.4) notes.push("The style writes most titles in capitals.");
  else if (t.upperShare < 0.2 && upNow > 0.6) notes.push("The style mostly avoids all-capital titles.");
  const anim = t.animate[0];
  if (anim && anim.name !== "none" && anim.share >= 0.5) notes.push(`Most of the style's titles enter with "${anim.name}".`);
  return cmds;
}

function motionNotes(p: Project, m: NonNullable<Dna["motion"]>, notes: string[]): void {
  const { all, backdrops } = picturesOf(p);
  const shots = all.filter((s) => !backdrops.has(s.clip));
  const own = measureMotion(shots, cutsOf(p, shots, all, all.reduce((x, s) => Math.max(x, s.end), 0)));
  const ownMoving = own?.movingShare ?? 0;
  const pct = (x: number) => `${Math.round(x * 100)}%`;
  if (m.movingShare - ownMoving > 0.15) notes.push(`The style moves ${pct(m.movingShare)} of its shots (pushes, pans); yours move ${pct(ownMoving)}. Try clip.kenBurns on some still shots.`);
  else if (ownMoving - m.movingShare > 0.15) notes.push(`The style keeps most shots still (${pct(m.movingShare)} move); yours move ${pct(ownMoving)}.`);
  else notes.push(`Shot movement already matches the style (about ${pct(m.movingShare)} of shots move).`);
  const top = m.transitions[0];
  if (top) notes.push(top.kind === "cut" ? `The style mostly uses straight cuts (${pct(top.share)}).` : `The style's favourite transition is "${top.kind}" (${pct(top.share)} of cuts): clip.transition can add it.`);
}

/**
 * Commands that move a timeline toward a DNA, plus plain-English notes. Parts (default all):
 *  - "rhythm": re-times the shots to planRetime's cuts with `clip.move {id, start}` and `clip.set {id, patch:{dur}}`
 *    (the in-point is kept, so each shot still starts on the same frame; it never asks for more source than the clip
 *    has, see planRetime). Shots that change are first parked past the end of the timeline and then brought back in
 *    order, so no command ever puts a clip on top of another one (which would make the editor move it to another
 *    track). When the shots are not one clean sequence nothing is emitted and a note says why.
 *  - "type": `clip.set {id, patch:{text:{font,color,size}}}` on every text clip of visible, unlocked video tracks:
 *    the style's most used font, its colours by rank, and sizes scaled so their median matches sizeRel.
 *  - "motion": notes only (suggestions); no command, since adding movement or transitions is a creative choice.
 * Colour is not applied here (src/color/match.ts turns dna.colour into a grade); a note mentions it when present.
 */
export function applyDna(p: Project, dna: Dna, o: { beats?: number[]; downbeats?: number[]; bpm?: number; seed?: number; parts?: ("rhythm" | "type" | "motion")[] } = {}): { commands: Record<string, unknown>[]; notes: string[] } {
  const parts = new Set(o.parts ?? ["rhythm", "type", "motion"]);
  const commands: Record<string, unknown>[] = [];
  const notes: string[] = [];
  if (parts.has("rhythm")) {
    const plan = planRetime(p, dna, o);
    if (!plan.ok) notes.push(plan.reason);
    else {
      const cmds = retimeCommands(p, plan);
      commands.push(...cmds);
      const grid = union(cleanTimes(o.beats), cleanTimes(o.downbeats));
      const onBeat = grid.length ? plan.cuts.filter((c) => nearestDist(grid, c) <= BEAT_TOL + SLACK).length : 0;
      notes.push(cmds.length ? `Re-timed ${plan.shots.length} shots to the style's rhythm (${plan.cuts.length} cuts${grid.length ? `, ${onBeat} on the beat` : ""}).` : "The shots already follow this rhythm.");
      if (Math.abs(Math.log(plan.scale)) > 0.05) notes.push(`To keep your ${plan.shots.length} shots in the same running time, the style's pace was ${plan.scale > 1 ? "slowed" : "sped up"} ×${(plan.scale > 1 ? plan.scale : 1 / plan.scale).toFixed(2)}; its shape (arc and beat pattern) is kept.`);
      if (plan.limited) notes.push(`${plan.limited} shot(s) ran out of source footage, so neighbouring shots took the extra time.`);
    }
  }
  if (parts.has("type")) {
    if (dna.type) commands.push(...typeCommands(p, dna.type, notes));
    else notes.push("The style has no titles to copy.");
  }
  if (parts.has("motion")) {
    if (dna.motion) motionNotes(p, dna.motion, notes);
    else notes.push("The style has no motion information.");
  }
  if (dna.colour) notes.push("The style carries a colour look: match it with the colour-match tool.");
  return { commands, notes };
}

/* ------------------------------- description ------------------------------- */

function fmt(x: number): string {
  if (x >= 10) return String(Math.round(x));
  return (Math.round(x * 10) / 10).toFixed(1).replace(/\.0$/, "");
}

function placeWord(y: number): string {
  return y < -1 / 6 ? "in the upper third" : y > 1 / 6 ? "in the lower third" : "in the middle";
}

function fontClass(font: string): string {
  const f = font.toLowerCase();
  if (/mono|code|courier/.test(f)) return "Monospace";
  if (/script|hand|brush|marker|caveat|pacifico|dancing|kalam|cursive/.test(f)) return "Handwritten";
  if (/sans/.test(f)) return "Sans-serif";
  if (/serif|times|georgia|garamond|playfair|baskerville|didot|bodoni|merriweather|lora/.test(f)) return "Serif";
  if (/display|impact|anton|bebas|black|condensed/.test(f)) return "Bold display";
  return "Sans-serif";
}

/** 4–8 short plain-English lines describing a DNA. */
export function describeDna(dna: Dna): string[] {
  const r = dna.rhythm;
  const lines: string[] = [];
  if (r.shotCount <= 1) {
    lines.push(r.duration > 0 ? `One continuous shot of ${fmt(r.duration)} s, no cuts` : "No footage measured yet");
    lines.push("No shot lengths to compare");
    lines.push("No pacing arc (nothing to speed up or slow down)");
    lines.push(r.duration > 0 ? "No first cut" : "No cuts");
  } else {
    const period = r.bpm && r.bpm > 0 ? 60 / r.bpm : undefined;
    let pace: string;
    if (period) {
      const b = r.shotMedian / period;
      const beatsTxt = Math.abs(b - 1) < 0.15 ? "every beat" : Math.abs(b - 0.5) < 0.1 ? "twice a beat" : `every ${fmt(b)} beats`;
      pace = `Cuts ${beatsTxt} on average (about ${fmt(r.shotMedian)} s a shot)`;
    } else pace = `A cut about every ${fmt(r.shotMedian)} s (median shot)`;
    if (fin(r.onBeat)) pace += r.onBeat >= 0.9 ? ", almost always on the beat" : r.onBeat >= 0.6 ? ", mostly on the beat" : r.onBeat >= 0.35 ? ", often on the beat" : ", mostly off the beat";
    if (fin(r.onDownbeat) && r.onDownbeat >= 0.5) pace += ", often on the downbeat";
    lines.push(pace);
    lines.push(r.shotMin > 0 && r.shotMax / r.shotMin <= 2 ? `Very even shot lengths (${fmt(r.shotMin)}–${fmt(r.shotMax)} s)` : `Shot lengths vary from ${fmt(r.shotMin)} s to ${fmt(r.shotMax)} s`);
    const a = (r.arc[0]! + r.arc[1]! + r.arc[2]!) / 3;
    const b = (r.arc[3]! + r.arc[4]!) / 2;
    const c = (r.arc[5]! + r.arc[6]! + r.arc[7]!) / 3;
    const k = 1.25;
    let arc = "Even pace from start to finish";
    if (a > c * k) arc = a > b * k ? "Starts slow and keeps speeding up" : "Starts slow, speeds up in the last third";
    else if (c > a * k) arc = b > a * k ? "Starts fast and keeps slowing down" : "Starts fast, slows down in the last third";
    else if (b * k < a && b * k < c) arc = "Fastest in the middle";
    else if (b > a * k && b > c * k) arc = "Slows down in the middle";
    lines.push(arc);
    lines.push(r.firstCut > r.shotMedian * 1.5 ? `Takes its time: first cut after ${fmt(r.firstCut)} s` : `First cut after ${fmt(r.firstCut)} s`);
  }
  if (dna.type) {
    const t = dna.type;
    const size = t.sizeRel < 0.04 ? "small" : t.sizeRel < 0.08 ? "medium" : "large";
    let s = `${fontClass(t.fonts[0]?.font ?? "")} titles, ${size}, ${placeWord(t.yRel).replace(/^in the /, "")}`;
    if (t.upperShare >= 0.6) s += ", in capitals";
    lines.push(s);
  }
  if (dna.motion) {
    const m = dna.motion;
    const top = m.transitions[0];
    const tr = !top ? "" : top.kind === "cut" ? "; straight cuts" : `; mostly "${top.kind}" transitions`;
    lines.push(`${m.movingShare >= 0.5 ? "Moving camera" : m.movingShare >= 0.2 ? "Some camera movement" : "Mostly still shots"} (${Math.round(m.movingShare * 100)}% of shots move)${tr}`);
  }
  if (dna.colour) {
    const c = dna.colour;
    const [L, A, B] = c.labMean;
    const tone = L > 65 ? "bright" : L < 35 ? "dark" : "mid-toned";
    const warm = B > 8 || A > 8 ? "warm" : B < -5 ? "cool" : "neutral";
    const sat = c.satMean < 0.2 ? "muted" : c.satMean > 0.45 ? "vivid" : "natural";
    const con = c.labStd[0] > 25 ? "high contrast" : c.labStd[0] < 12 ? "flat contrast" : "normal contrast";
    lines.push(`${warm[0]!.toUpperCase()}${warm.slice(1)}, ${tone}, ${sat} colour; ${con}`);
  }
  if (dna.source === "merged") lines.push(`Signature built from ${dna.samples} ${dna.samples === 1 ? "edit" : "edits"}`);
  return lines.slice(0, 8);
}

/* --------------------------------- distance -------------------------------- */

function logDiff(a: number, b: number, span: number): number {
  if (!(a > 0) && !(b > 0)) return 0;
  if (!(a > 0) || !(b > 0)) return 1;
  return Math.min(1, Math.abs(Math.log2(a / b)) / span);
}

function overlap<T extends { share: number }>(a: readonly T[], b: readonly T[], key: (x: T) => string): number {
  const m = new Map<string, number>();
  for (const x of a) m.set(key(x), (m.get(key(x)) ?? 0) + x.share);
  let o = 0;
  const seen = new Map<string, number>();
  for (const x of b) seen.set(key(x), (seen.get(key(x)) ?? 0) + x.share);
  for (const [k, v] of seen) o += Math.min(v, m.get(k) ?? 0);
  return clamp(1 - o, 0, 1);
}

/**
 * How different two DNAs feel: 0 = the same, 1 = as different as can be. It is the weighted mean of parts that are
 * each 0..1, over the parts BOTH records have (rhythm always; type, motion and colour only when both have them):
 *  rhythm — pace: |log2 of the median ratio| / 2 (4× slower = 1), weight 3; arc: mean over the 8 slices of
 *  |log2 ratio| / 2, weight 2; spread: |log2 of the mean/median ratios|, weight 0.5; first cut (relative to the
 *  median): |log2 ratio| / 3, weight 0.5; beat buckets: total-variation distance, weight 2; onBeat |difference|,
 *  weight 1; onDownbeat |difference|, weight 0.5.
 *  type — fonts, colours, entrances: 1 − shared share (weights 1, 0.5, 0.5); size: |log2 ratio| / 1.5, weight 1;
 *  position: 2 × |Δy|, weight 1; capitals |Δ|, weight 0.25; words per second |log2 ratio| / 2, weight 0.25.
 *  motion — moving share |Δ|, weight 1; transitions 1 − shared share, weight 1.
 *  colour — ΔE of the Lab means / 40, weight 2; mean |Δ| of the Lab spreads / 20, weight 1; saturation 2 × |Δ|,
 *  weight 0.5; luma histogram total-variation distance, weight 1.
 * Rough reading: under 0.1 the same style, 0.1–0.25 close, 0.25–0.5 related, above 0.5 different.
 */
export function dnaDistance(a: Dna, b: Dna): number {
  const parts: { c: number; w: number }[] = [];
  const add = (c: number, w: number) => parts.push({ c: fin(c) ? clamp(c, 0, 1) : 1, w });
  const ra = a.rhythm;
  const rb = b.rhythm;
  add(logDiff(ra.shotMedian, rb.shotMedian, 2), 3);
  const arcs = ra.arc.map((x, i) => logDiff(Math.max(1e-3, x), Math.max(1e-3, rb.arc[i] ?? 1), 2));
  add(arcs.length ? sum(arcs) / arcs.length : 0, 2);
  const ratio = (r: Dna["rhythm"]) => (r.shotMedian > 0 ? r.shotMean / r.shotMedian : 1);
  add(logDiff(ratio(ra), ratio(rb), 1), 0.5);
  const rel = (r: Dna["rhythm"]) => (r.shotMedian > 0 ? r.firstCut / r.shotMedian : 1);
  add(logDiff(rel(ra), rel(rb), 3), 0.5);
  if (ra.beatBuckets && rb.beatBuckets) add(0.5 * sum(ra.beatBuckets.map((x, i) => Math.abs(x - (rb.beatBuckets![i] ?? 0)))), 2);
  if (fin(ra.onBeat) && fin(rb.onBeat)) add(Math.abs(ra.onBeat - rb.onBeat), 1);
  if (fin(ra.onDownbeat) && fin(rb.onDownbeat)) add(Math.abs(ra.onDownbeat - rb.onDownbeat), 0.5);
  if (a.type && b.type) {
    const ta = a.type;
    const tb = b.type;
    add(overlap(ta.fonts, tb.fonts, (x) => x.font), 1);
    add(overlap(ta.colors, tb.colors, (x) => x.color.toLowerCase()), 0.5);
    add(overlap(ta.animate, tb.animate, (x) => x.name), 0.5);
    add(logDiff(ta.sizeRel, tb.sizeRel, 1.5), 1);
    add(2 * Math.abs(ta.yRel - tb.yRel), 1);
    add(Math.abs(ta.upperShare - tb.upperShare), 0.25);
    add(logDiff(ta.wordsPerSec, tb.wordsPerSec, 2), 0.25);
  }
  if (a.motion && b.motion) {
    add(Math.abs(a.motion.movingShare - b.motion.movingShare), 1);
    if (a.motion.transitions.length || b.motion.transitions.length) add(overlap(a.motion.transitions, b.motion.transitions, (x) => x.kind), 1);
  }
  if (a.colour && b.colour) {
    const ca = a.colour;
    const cb = b.colour;
    add(Math.hypot(ca.labMean[0] - cb.labMean[0], ca.labMean[1] - cb.labMean[1], ca.labMean[2] - cb.labMean[2]) / 40, 2);
    add((Math.abs(ca.labStd[0] - cb.labStd[0]) + Math.abs(ca.labStd[1] - cb.labStd[1]) + Math.abs(ca.labStd[2] - cb.labStd[2])) / 3 / 20, 1);
    add(2 * Math.abs(ca.satMean - cb.satMean), 0.5);
    add(0.5 * sum(ca.lumaHist.map((x, i) => Math.abs(x - (cb.lumaHist[i] ?? 0)))), 1);
  }
  const W = sum(parts.map((p) => p.w));
  return W > 0 ? sum(parts.map((p) => p.c * p.w)) / W : 0;
}

/* -------------------------------- validation ------------------------------- */

function fail(path: string, msg: string): never {
  throw new Error(`Invalid style DNA: ${path} ${msg}.`);
}

function isObj(x: unknown): x is Record<string, unknown> {
  return !!x && typeof x === "object" && !Array.isArray(x);
}

function vNum(x: unknown, path: string, o: { min?: number; max?: number; int?: boolean } = {}): number {
  if (!fin(x)) fail(path, `must be a finite number (got ${x === undefined ? "nothing" : JSON.stringify(x)})`);
  if (o.int && !Number.isInteger(x)) fail(path, `must be a whole number (got ${x})`);
  if (o.min !== undefined && x < o.min) fail(path, `must be at least ${o.min} (got ${x})`);
  if (o.max !== undefined && x > o.max) fail(path, `must be at most ${o.max} (got ${x})`);
  return x;
}

function vStr(x: unknown, path: string): string {
  if (typeof x !== "string") fail(path, "must be text");
  return x;
}

function vNums(x: unknown, path: string, len: number | undefined, o: { min?: number; max?: number } = {}): number[] {
  if (!Array.isArray(x)) fail(path, "must be a list of numbers");
  if (len !== undefined && x.length !== len) fail(path, `must have ${len} numbers (got ${x.length})`);
  return x.map((v, i) => vNum(v, `${path}[${i}]`, o));
}

function vShares(x: unknown, path: string, key: string): { [k: string]: string | number }[] {
  if (!Array.isArray(x)) fail(path, `must be a list of {${key}, share}`);
  return x.map((it, i) => {
    if (!isObj(it)) fail(`${path}[${i}]`, `must be an object {${key}, share}`);
    return { [key]: vStr(it[key], `${path}[${i}].${key}`), share: vNum(it.share, `${path}[${i}].share`, { min: 0, max: 1 }) };
  });
}

function vColour(x: unknown, path: string): ColourStats {
  if (!isObj(x)) fail(path, "must be an object of colour statistics");
  const hist = (h: unknown, p: string) => vNums(h, p, 64, { min: 0 });
  if (!Array.isArray(x.rgbHist) || x.rgbHist.length !== 3) fail(`${path}.rgbHist`, "must be three histograms");
  if (!Array.isArray(x.palette) || x.palette.length > 6) fail(`${path}.palette`, "must be a list of at most 6 colours");
  return {
    n: vNum(x.n, `${path}.n`, { min: 0 }),
    labMean: vNums(x.labMean, `${path}.labMean`, 3) as [number, number, number],
    labStd: vNums(x.labStd, `${path}.labStd`, 3, { min: 0 }) as [number, number, number],
    lumaHist: hist(x.lumaHist, `${path}.lumaHist`),
    rgbHist: [hist(x.rgbHist[0], `${path}.rgbHist[0]`), hist(x.rgbHist[1], `${path}.rgbHist[1]`), hist(x.rgbHist[2], `${path}.rgbHist[2]`)],
    satMean: vNum(x.satMean, `${path}.satMean`, { min: 0, max: 1 }),
    black: vNum(x.black, `${path}.black`, { min: 0, max: 1 }),
    white: vNum(x.white, `${path}.white`, { min: 0, max: 1 }),
    palette: x.palette.map((e, i) => {
      if (!isObj(e)) fail(`${path}.palette[${i}]`, "must be an object {rgb, weight}");
      return { rgb: vNums(e.rgb, `${path}.palette[${i}].rgb`, 3, { min: 0, max: 1 }) as [number, number, number], weight: vNum(e.weight, `${path}.palette[${i}].weight`, { min: 0, max: 1 }) };
    }),
  };
}

/**
 * Check that a value loaded from disk is a well-formed DNA and return a clean copy (unknown fields dropped).
 * Throws an Error naming the first bad field, e.g. "Invalid style DNA: rhythm.arc must have 8 numbers (got 7)."
 * Beat buckets must have 6 shares that add up to 1 (± 0.01); colour histograms must have 64 bins.
 */
export function validateDna(x: unknown): Dna {
  if (!isObj(x)) fail("the record", "must be an object");
  if (x.v !== 1) fail("v", `must be 1 (got ${JSON.stringify(x.v)})`);
  const name = vStr(x.name, "name");
  if (x.source !== "video" && x.source !== "project" && x.source !== "merged") fail("source", `must be "video", "project" or "merged" (got ${JSON.stringify(x.source)})`);
  const samples = vNum(x.samples, "samples", { min: 0, int: true });
  const r = x.rhythm;
  if (!isObj(r)) fail("rhythm", "must be an object");
  const rhythm: Dna["rhythm"] = {
    shotCount: vNum(r.shotCount, "rhythm.shotCount", { min: 0, int: true }),
    duration: vNum(r.duration, "rhythm.duration", { min: 0 }),
    shotMedian: vNum(r.shotMedian, "rhythm.shotMedian", { min: 0 }),
    shotMean: vNum(r.shotMean, "rhythm.shotMean", { min: 0 }),
    shotMin: vNum(r.shotMin, "rhythm.shotMin", { min: 0 }),
    shotMax: vNum(r.shotMax, "rhythm.shotMax", { min: 0 }),
    arc: vNums(r.arc, "rhythm.arc", ARC_SLICES, { min: 0 }),
    firstCut: vNum(r.firstCut, "rhythm.firstCut", { min: 0 }),
  };
  if (rhythm.shotMin > rhythm.shotMax) fail("rhythm.shotMin", `must not be above rhythm.shotMax (${rhythm.shotMin} > ${rhythm.shotMax})`);
  if (r.bpm !== undefined) {
    rhythm.bpm = vNum(r.bpm, "rhythm.bpm");
    if (!(rhythm.bpm > 0)) fail("rhythm.bpm", `must be above 0 (got ${rhythm.bpm})`);
  }
  if (r.beatBuckets !== undefined) {
    rhythm.beatBuckets = vNums(r.beatBuckets, "rhythm.beatBuckets", BEAT_BUCKETS.length, { min: 0, max: 1 });
    const s = sum(rhythm.beatBuckets);
    if (Math.abs(s - 1) > 0.01) fail("rhythm.beatBuckets", `must add up to 1 (got ${s})`);
  }
  if (r.onBeat !== undefined) rhythm.onBeat = vNum(r.onBeat, "rhythm.onBeat", { min: 0, max: 1 });
  if (r.onDownbeat !== undefined) rhythm.onDownbeat = vNum(r.onDownbeat, "rhythm.onDownbeat", { min: 0, max: 1 });
  const out: Dna = { v: 1, name, source: x.source, samples, rhythm };
  if (x.colour !== undefined) out.colour = vColour(x.colour, "colour");
  if (x.type !== undefined) {
    const t = x.type;
    if (!isObj(t)) fail("type", "must be an object");
    out.type = {
      fonts: vShares(t.fonts, "type.fonts", "font") as { font: string; share: number }[],
      sizeRel: vNum(t.sizeRel, "type.sizeRel", { min: 0 }),
      colors: vShares(t.colors, "type.colors", "color") as { color: string; share: number }[],
      yRel: vNum(t.yRel, "type.yRel", { min: -0.5, max: 0.5 }),
      animate: vShares(t.animate, "type.animate", "name") as { name: string; share: number }[],
      wordsPerSec: vNum(t.wordsPerSec, "type.wordsPerSec", { min: 0 }),
      upperShare: vNum(t.upperShare, "type.upperShare", { min: 0, max: 1 }),
    };
  }
  if (x.motion !== undefined) {
    const m = x.motion;
    if (!isObj(m)) fail("motion", "must be an object");
    out.motion = { movingShare: vNum(m.movingShare, "motion.movingShare", { min: 0, max: 1 }), transitions: vShares(m.transitions, "motion.transitions", "kind") as { kind: string; share: number }[] };
  }
  return out;
}
