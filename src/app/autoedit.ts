/**
 * Beat-synced auto edit (CapCut-template style, but built from your own media): cuts land on the music's
 * beats, overlapping transitions, beat punches, motion blur, a style grade, and the music underneath.
 */
import type { Studio } from "./studio";
import { findAsset, newClip, newTrack, uid, type Clip, type Ease, type Keyframe, type Project, type TransitionKind } from "../core/model";
import { OpError } from "../core/ops";
import { bufferChannels, detectBeats, type BeatInfo } from "../media/beats";

export type CutStyle = "punchy" | "smooth" | "cinematic" | "glitch";

export interface BeatCutOptions {
  assetIds?: string[];
  musicId?: string;
  style?: CutStyle;
  /** Beats per cut. */
  every?: number;
  start?: number;
  /** Repeat the media to fill the whole song. */
  loop?: boolean;
  /** Remove existing clips that use these media first. */
  replace?: boolean;
  /** Cap the edit length in seconds. */
  maxDur?: number;
}

export async function analyseBeats(s: Studio, assetId: string): Promise<BeatInfo> {
  const a = findAsset(s.project, assetId);
  if (!a || a.kind === "image") throw new OpError("Pick an audio or video asset with sound.");
  const ctx = new OfflineAudioContext(1, 1, 44100);
  const buf = await s.pool.audioBuffer(a, ctx);
  if (!buf) throw new OpError("That file has no audio.");
  return detectBeats(bufferChannels(buf), buf.sampleRate);
}

const KINDS: Record<CutStyle, TransitionKind[]> = {
  punchy: ["whipLeft", "zoomBlur", "flash", "whipRight", "spin", "zoomBlur"],
  smooth: ["fade", "blur", "fade", "zoomIn"],
  cinematic: ["dip", "fade", "blur", "fade"],
  glitch: ["glitch", "flash", "glitch", "whipLeft"],
};

export async function beatCut(s: Studio, o: BeatCutOptions = {}): Promise<{ bpm: number; cuts: number; duration: number; trackIds: string[] }> {
  const p = s.project;
  const style = o.style ?? "punchy";
  const media = (o.assetIds ?? p.assets.filter((a) => a.kind !== "audio" && a.createdBy !== "render").map((a) => a.id)).map((id) => {
    const a = findAsset(p, id);
    if (!a) throw new OpError(`No asset ${id}`);
    if (a.kind === "audio") throw new OpError(`${a.name} is audio; pass it as musicId.`);
    return a;
  });
  if (!media.length) throw new OpError("Import some photos or videos first.");
  const music = o.musicId ? findAsset(p, o.musicId) : p.assets.find((a) => a.kind === "audio" && a.createdBy !== "voice");
  let info: BeatInfo;
  if (music) info = await analyseBeats(s, music.id);
  else {
    const beats = Array.from({ length: 400 }, (_, i) => i * 0.5);
    info = { bpm: 120, beats, onsets: [], downbeats: beats.filter((_, i) => i % 4 === 0), duration: 200 };
  }
  if (info.beats.length < 2) throw new OpError("Could not find a beat in that music.");
  const every = Math.max(1, Math.round(o.every ?? (style === "punchy" || style === "glitch" ? 2 : 4)));
  const start = o.start ?? 0;
  // Start on the first downbeat so the first cut lands with the music.
  const first = info.downbeats[0] ?? info.beats[0]!;
  const grid = info.beats.filter((b) => b >= first - 1e-6);
  const cuts: number[] = [];
  for (let i = 0; i < grid.length; i += every) cuts.push(grid[i]! - first);
  const limit = Math.min(o.maxDur ?? Infinity, music ? info.duration - first : Infinity);
  let count = o.loop ? cuts.length - 1 : Math.min(media.length, cuts.length - 1);
  while (count > 0 && cuts[count]! > limit) count--;
  if (count < 1) throw new OpError("The music is too short for one cut.");
  const ov = style === "punchy" ? 0.16 : style === "glitch" ? 0.14 : style === "smooth" ? 0.45 : 0.6;
  const kinds = KINDS[style];
  const editEnd = start + cuts[count]!;

  return await s.mutateAsync(`beat cut ${style}`, async (proj: Project) => {
    if (o.replace !== false) {
      const ids = new Set(media.map((m) => m.id));
      for (const t of proj.tracks) t.clips = t.clips.filter((c) => !c.assetId || !ids.has(c.assetId));
    }
    const lower = newTrack("video", "Cut A");
    const upper = newTrack("video", "Cut B");
    const vids = proj.tracks.filter((t) => t.kind === "video");
    const firstAudio = proj.tracks.findIndex((t) => t.kind === "audio");
    const at = firstAudio < 0 ? proj.tracks.length : firstAudio;
    // Cut tracks sit at the bottom of the video stack, under any titles.
    proj.tracks.splice(at, 0, upper, lower);
    void vids;
    const made: Clip[] = [];
    for (let i = 0; i < count; i++) {
      const a = media[i % media.length]!;
      const c0 = start + cuts[i]!;
      const c1 = start + cuts[i + 1]!;
      const begin = i === 0 ? c0 : c0 - ov;
      const dur = c1 - begin;
      const onTop = i % 2 === 1;
      const c = newClip("media", begin, dur, { assetId: a.id, name: a.name, fit: "cover" });
      if (a.kind === "video") {
        const room = Math.max(0, a.duration - dur);
        c.in = Math.min(room, room * ((i * 0.37) % 1));
        if (a.duration < dur) c.dur = Math.max(0.1, a.duration);
      }
      c.fx = { motionBlur: style === "punchy" || style === "glitch" ? 1 : 0.6 };
      c.grade =
        style === "cinematic"
          ? { contrast: 1.12, saturation: 0.92, vignette: 0.35, grain: 0.1 }
          : style === "smooth"
            ? { contrast: 1.04, saturation: 1.05, vibrance: 0.15 }
            : { contrast: 1.14, saturation: 1.12, vibrance: 0.25, sharpen: 0.3 };
      if (style === "cinematic") c.wheels = { lift: [-0.03, 0.01, 0.04], gamma: [0, 0, 0], gain: [0.05, 0.02, -0.04] };
      // Motion inside the shot.
      const local = (t: number) => Math.max(0, Math.min(c.dur, t - begin));
      if (style === "punchy" || style === "glitch") {
        const scale: Keyframe[] = [];
        for (const b of info.beats) {
          const bt = start + b - first;
          if (bt < c0 - 1e-3 || bt >= c1 - 0.05) continue;
          scale.push({ t: local(bt), v: 1.12, ease: "out" as Ease }, { t: local(Math.min(c1, bt + 0.22)), v: 1.02, ease: "linear" as Ease });
        }
        if (scale.length) c.transform.scale = dedupe(scale);
        if (style === "glitch") c.fx = { ...c.fx, rgbSplit: [{ t: local(c0), v: 18, ease: "out" }, { t: local(c0 + 0.2), v: 0, ease: "linear" }] };
      } else {
        const dir = i % 2 ? 1 : -1;
        c.transform.scale = [{ t: 0, v: 1.06, ease: "linear" }, { t: c.dur, v: 1.16, ease: "linear" }];
        c.transform.x = [{ t: 0, v: -dir * proj.width * 0.02, ease: "linear" }, { t: c.dur, v: dir * proj.width * 0.02, ease: "linear" }];
      }
      // Transition into this shot: the incoming clip on the upper track fades/whips in; when the incoming clip is
      // on the lower track, the outgoing upper clip transitions out instead.
      if (i > 0) {
        const kind = kinds[i % kinds.length]!;
        if (onTop) c.transitionIn = { kind, dur: ov };
        else made[i - 1]!.transitionOut = { kind: outKind(kind), dur: ov };
      }
      (onTop ? upper : lower).clips.push(c);
      made.push(c);
    }
    made[made.length - 1]!.transitionOut = { kind: style === "punchy" ? "flash" : "dip", dur: Math.min(0.6, made[made.length - 1]!.dur / 2) };
    const trackIds = [upper.id, lower.id];
    if (style === "cinematic" || style === "glitch") {
      const fxTrack = newTrack("video", "Look");
      const adj = newClip("adjust", start, editEnd - start, { name: style === "cinematic" ? "Cinematic look" : "Glitch look" });
      adj.fx = style === "cinematic" ? { letterbox: proj.width > proj.height ? 0.11 : 0.06, highlights: -0.1, shadows: -0.05 } : { scanlines: 0.25 };
      adj.grade = style === "cinematic" ? { temperature: 0.05 } : { contrast: 1.08 };
      fxTrack.clips.push(adj);
      proj.tracks.splice(at, 0, fxTrack);
      trackIds.push(fxTrack.id);
    }
    if (music) {
      const at2 = proj.tracks.find((t) => t.kind === "audio" && !t.clips.some((c) => c.start < editEnd && c.start + c.dur > start)) ?? (() => {
        const t = newTrack("audio", `A${proj.tracks.filter((x) => x.kind === "audio").length + 1}`);
        proj.tracks.push(t);
        return t;
      })();
      const mc = newClip("media", start, Math.min(editEnd - start + 0.4, music.duration - first), { assetId: music.id, name: music.name, in: first, fadeOut: 1.2 });
      mc.id = uid("clp");
      at2.clips.push(mc);
      trackIds.push(at2.id);
    }
    proj.markers = info.beats.filter((b) => b >= first && b - first <= editEnd - start).filter((_, i) => i % every === 0).map((b, i) => ({ t: start + b - first, label: `B${i + 1}` }));
    return { bpm: info.bpm, cuts: count, duration: Math.round((editEnd - start) * 100) / 100, trackIds };
  });
}

function outKind(k: TransitionKind): TransitionKind {
  if (k === "whipLeft") return "whipRight";
  if (k === "whipRight") return "whipLeft";
  return k;
}

function dedupe(keys: Keyframe[]): Keyframe[] {
  const out: Keyframe[] = [];
  for (const k of keys.sort((a, b) => a.t - b.t)) if (!out.length || Math.abs(out[out.length - 1]!.t - k.t) > 1e-3) out.push(k);
  return out;
}
