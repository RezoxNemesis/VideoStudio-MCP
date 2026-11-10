/**
 * Measuring pictures for the colour tools: render one clip on its own (no transform, no transitions, no vignette or
 * grain) at a small size and hand back its pixels or statistics. Used by shot match, look-from-picture, auto balance
 * and style DNA.
 */
import { findAsset, newClip, newTrack, type Clip, type Project } from "../core/model";
import { OpError, getClip } from "../core/ops";
import type { MediaPool } from "../media/library";
import { Compositor } from "../render/compositor";
import { buildLayers } from "../render/layers";
import { analyse, mergeStats } from "./match";
import type { ColourStats, Pixels } from "./types";

export interface ProbeOptions {
  /** Timeline time inside the clip; default the middle. */
  t?: number;
  /** Longest side of the measured picture in px (default 384). */
  max?: number;
  /** false = the picture before any colour work. */
  graded?: boolean;
  /** Leave these nodes out (by id), e.g. the match node being recomputed. */
  dropNodes?: string[];
}

function even(n: number): number {
  return Math.max(2, Math.round(n / 2) * 2);
}

/** A stand-alone copy of the clip that fills its own frame, keeping only what affects colour. */
function probeProject(p: Project, c: Clip, o: ProbeOptions): { project: Project; w: number; h: number } {
  const a = findAsset(p, c.assetId);
  const whole = c.kind === "adjust" || !a || !a.width || !a.height;
  const sw = whole ? p.width : a.width;
  const sh = whole ? p.height : a.height;
  const k = (o.max ?? 384) / Math.max(sw, sh);
  const w = even(sw * k);
  const h = even(sh * k);
  if (whole) {
    // Adjustment clips (and clips without a picture size) are measured on the whole frame.
    const project = JSON.parse(JSON.stringify(p)) as Project;
    for (const t of project.tracks)
      t.clips = t.clips.flatMap((x) => {
        if (x.id !== c.id) return [x];
        if (o.graded === false) return [];
        return [{ ...x, nodes: x.nodes?.filter((n) => !o.dropNodes?.includes(n.id)) }];
      });
    return { project, w: even(p.width * ((o.max ?? 384) / Math.max(p.width, p.height))), h: even(p.height * ((o.max ?? 384) / Math.max(p.width, p.height))) };
  }
  const grade = { ...c.grade, vignette: 0, grain: 0, blur: 0, sharpen: 0 };
  const copy = newClip(c.kind, c.start, c.dur, {
    assetId: c.assetId,
    sceneId: c.sceneId,
    in: c.in,
    speed: c.speed,
    reverse: c.reverse,
    speedKeys: c.speedKeys,
    hold: c.hold,
    fit: "fill",
    grade,
    wheels: c.wheels,
    crop: c.crop,
    nodes: c.nodes?.filter((n) => !o.dropNodes?.includes(n.id)),
  });
  const track = newTrack("video", "probe");
  track.clips.push(copy);
  const project: Project = { ...p, width: sw, height: sh, background: "#000000", tracks: [track] };
  return { project, w, h };
}

/** The pixels of one clip at one moment, as it looks with (or without) its colour work. */
export async function clipPixels(p: Project, pool: MediaPool, clipId: string, o: ProbeOptions = {}): Promise<Pixels> {
  const c = getClip(p, clipId);
  const a = findAsset(p, c.assetId);
  if (c.kind === "media" && a?.kind === "audio") throw new OpError("That is a sound clip; colour tools work on pictures.");
  const { project, w, h } = probeProject(p, c, o);
  const t = Math.min(c.start + c.dur - 1e-3, Math.max(c.start, o.t ?? c.start + c.dur / 2));
  const canvas = new OffscreenCanvas(w, h);
  const comp = new Compositor(canvas);
  try {
    comp.bypass = o.graded === false && c.kind !== "adjust";
    const layers = await buildLayers(project, pool, t, { purpose: "export", wait: true, outW: w, outH: h, framerPurpose: "probe", framerMax: 640 });
    comp.render(layers, w, h, "#000000");
    const data = new Uint8Array(w * h * 4);
    comp.gl.readPixels(0, 0, w, h, comp.gl.RGBA, comp.gl.UNSIGNED_BYTE, data);
    return { data, width: w, height: h };
  } finally {
    comp.dispose();
  }
}

/** Statistics of a clip's look, averaged over a few moments spread across it. */
export async function clipStats(p: Project, pool: MediaPool, clipId: string, o: ProbeOptions & { samples?: number } = {}): Promise<ColourStats> {
  const c = getClip(p, clipId);
  const n = o.t !== undefined ? 1 : Math.max(1, Math.min(7, o.samples ?? (c.dur > 1.5 ? 3 : 1)));
  const out: ColourStats[] = [];
  for (let i = 0; i < n; i++) {
    const t = o.t ?? c.start + (c.dur * (i + 1)) / (n + 1);
    out.push(analyse(await clipPixels(p, pool, clipId, { ...o, t })));
  }
  return out.length === 1 ? out[0]! : mergeStats(out);
}

/** Statistics of a picture or video in the media list (not on the timeline), e.g. a reference still. */
export async function assetStats(p: Project, pool: MediaPool, assetId: string, o: { t?: number; max?: number; samples?: number } = {}): Promise<ColourStats> {
  const a = findAsset(p, assetId);
  if (!a || (a.kind !== "video" && a.kind !== "image")) throw new OpError("Pick a picture or a video as the reference.");
  const dur = a.kind === "video" ? Math.max(0.1, a.duration) : 1;
  const clip = newClip("media", 0, dur, { assetId });
  const track = newTrack("video", "ref");
  track.clips.push(clip);
  const tmp: Project = { ...p, tracks: [track] };
  const n = a.kind === "image" || o.t !== undefined ? 1 : Math.max(1, Math.min(7, o.samples ?? 4));
  const out: ColourStats[] = [];
  for (let i = 0; i < n; i++) {
    const t = o.t ?? (dur * (i + 1)) / (n + 1);
    out.push(analyse(await clipPixels(tmp, pool, clip.id, { t, max: o.max })));
  }
  return out.length === 1 ? out[0]! : mergeStats(out);
}
