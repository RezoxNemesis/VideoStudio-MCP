/**
 * Music-video tools: colour looks, section-aware auto edits for different genres (rap, classic, EDM, lo-fi,
 * romantic, sad, cinematic), lyric captions from the song's timed lyrics, voice ducking and reframing.
 */
import type { Studio } from "./studio";
import { findAsset, newClip, newTrack, uid, type Clip, type Fx, type Grade, type Keyframe, type Project, type TransitionKind, type Wheels } from "../core/model";
import { OpError, getClip } from "../core/ops";
import type { SongInfo } from "../media/song";
import type { LyricLine } from "../media/asr";

/* --------------------------------- looks ---------------------------------- */

export interface Look {
  grade: Partial<Grade>;
  wheels?: Wheels;
  fx?: Partial<Fx>;
}

export const LOOKS: Record<string, Look> = {
  "teal-orange": { grade: { contrast: 1.12, saturation: 1.1, temperature: 0.05 }, wheels: { lift: [-0.04, 0.01, 0.06], gamma: [0, 0, 0], gain: [0.06, 0.02, -0.05] } },
  noir: { grade: { contrast: 1.35, saturation: 0, vignette: 0.5, grain: 0.18 } },
  vintage: { grade: { contrast: 0.92, saturation: 0.8, temperature: 0.12, lift: 0.06, vignette: 0.35, grain: 0.2 }, wheels: { lift: [0.04, 0.02, -0.02], gamma: [0, 0, 0], gain: [0.03, 0, -0.04] } },
  cyberpunk: { grade: { contrast: 1.15, saturation: 1.3, tint: 0.15 }, wheels: { lift: [0.04, -0.03, 0.08], gamma: [0, 0, 0], gain: [0.05, -0.02, 0.06] }, fx: { glow: 0.6, glowThreshold: 0.7, glowRadius: 26 } },
  "warm-film": { grade: { contrast: 1.05, saturation: 1.05, temperature: 0.15, grain: 0.1, vignette: 0.25 }, wheels: { lift: [0.02, 0.01, -0.01], gamma: [0, 0, 0], gain: [0.04, 0.02, -0.03] } },
  bleach: { grade: { contrast: 1.3, saturation: 0.55, gain: 1.05, grain: 0.08 } },
  matte: { grade: { contrast: 0.88, lift: 0.08, saturation: 0.9 }, fx: { highlights: -0.15 } },
  vivid: { grade: { contrast: 1.1, saturation: 1.25, vibrance: 0.3, sharpen: 0.3 } },
  dreamy: { grade: { contrast: 0.95, saturation: 1.05, exposure: 0.15 }, fx: { glow: 0.9, glowThreshold: 0.55, glowRadius: 40 } },
  "night-blue": { grade: { contrast: 1.1, saturation: 0.85, temperature: -0.2, vignette: 0.4 }, wheels: { lift: [-0.03, 0, 0.06], gamma: [0, 0, 0], gain: [0, 0.01, 0.03] } },
  gold: { grade: { contrast: 1.08, saturation: 1.1, temperature: 0.22, tint: 0.04 }, fx: { glow: 0.5, glowThreshold: 0.7, glowRadius: 30 } },
  "hip-hop": { grade: { contrast: 1.22, saturation: 1.05, sharpen: 0.4, vignette: 0.35, grain: 0.06 }, wheels: { lift: [-0.03, -0.02, 0], gamma: [0, 0, 0], gain: [0.04, 0.03, 0] } },
};

export function applyLook(c: Clip, name: string, strength = 1): void {
  const L = LOOKS[name];
  if (!L) throw new OpError(`Unknown look "${name}". Looks: ${Object.keys(LOOKS).join(", ")}`);
  const mix = (v: number, def: number) => def + (v - def) * strength;
  const defs: Record<string, number> = { exposure: 0, contrast: 1, saturation: 1, vibrance: 0, temperature: 0, tint: 0, hue: 0, lift: 0, gamma: 1, gain: 1, vignette: 0, grain: 0, sharpen: 0, blur: 0 };
  c.grade = { ...c.grade, ...Object.fromEntries(Object.entries(L.grade).map(([k, v]) => [k, mix(v as number, defs[k] ?? 0)])) };
  if (L.wheels) c.wheels = { lift: L.wheels.lift.map((x) => x * strength) as never, gamma: L.wheels.gamma, gain: L.wheels.gain.map((x) => x * strength) as never };
  if (L.fx) c.fx = { ...(c.fx ?? {}), ...L.fx };
}

/* ------------------------------ music videos ------------------------------- */

export type Genre = "rap" | "classic" | "edm" | "lofi" | "romantic" | "sad" | "cinematic" | "pop";

interface GenreStyle {
  /** Beats per cut by section energy: [low, mid, high]. */
  every: [number, number, number];
  transitions: TransitionKind[];
  overlap: number;
  look: string;
  punch: boolean;
  caption: { style: string; font?: string; color?: string; accent?: string; uppercase?: boolean; maxWords?: number };
  hitFlash: boolean;
}

export const GENRES: Record<Genre, GenreStyle> = {
  rap: { every: [4, 2, 1], transitions: ["whipLeft", "zoomBlur", "glitch", "whipRight", "flash"], overlap: 0.12, look: "hip-hop", punch: true, caption: { style: "bold", font: "Anton", uppercase: true, maxWords: 3, accent: "#ffd23f" }, hitFlash: true },
  edm: { every: [4, 2, 1], transitions: ["glitch", "zoomBlur", "flash", "spin"], overlap: 0.1, look: "cyberpunk", punch: true, caption: { style: "pop", font: "Orbitron", uppercase: true, maxWords: 3, accent: "#24c8ff" }, hitFlash: true },
  pop: { every: [4, 2, 2], transitions: ["zoomIn", "slideLeft", "whipRight", "fade"], overlap: 0.25, look: "vivid", punch: true, caption: { style: "karaoke", font: "Poppins", maxWords: 4, accent: "#ff4a8a" }, hitFlash: false },
  classic: { every: [8, 8, 4], transitions: ["fade", "dip", "blur"], overlap: 0.8, look: "warm-film", punch: false, caption: { style: "serif", font: "Cormorant Garamond", maxWords: 6, color: "#f6efe0" }, hitFlash: false },
  romantic: { every: [8, 4, 4], transitions: ["fade", "blur", "zoomIn"], overlap: 0.7, look: "dreamy", punch: false, caption: { style: "serif", font: "Great Vibes", maxWords: 5, color: "#ffe6ef" }, hitFlash: false },
  sad: { every: [8, 8, 4], transitions: ["dip", "fade", "blur"], overlap: 0.9, look: "night-blue", punch: false, caption: { style: "minimal", font: "Cormorant Garamond", maxWords: 5, color: "#e6ecff" }, hitFlash: false },
  lofi: { every: [8, 4, 4], transitions: ["fade", "blur"], overlap: 0.6, look: "vintage", punch: false, caption: { style: "minimal", font: "Caveat", maxWords: 5 }, hitFlash: false },
  cinematic: { every: [8, 4, 2], transitions: ["dip", "fade", "zoomBlur"], overlap: 0.6, look: "teal-orange", punch: true, caption: { style: "serif", font: "Cinzel", maxWords: 5, color: "#f3ead7" }, hitFlash: false },
};

/** Guess a genre from the song's analysis (the agent usually passes one after listening to the lyrics). */
export function guessGenre(song: SongInfo): Genre {
  const { energy, valence } = song.mood;
  if (song.bpm >= 120 && energy > 0.65) return valence > 0.5 ? "edm" : "rap";
  if (song.bpm >= 80 && song.bpm < 115 && energy > 0.5) return "rap";
  if (energy < 0.35) return valence > 0.5 ? "romantic" : "sad";
  return valence > 0.55 ? "pop" : "cinematic";
}

export interface MusicVideoOptions {
  musicId: string;
  assetIds?: string[];
  genre?: Genre;
  song: SongInfo;
  start?: number;
  maxDur?: number;
}

/** Cut the media to the song: pacing follows each section's energy, cuts land on beats, hits get punches. */
export function musicVideoEdit(p: Project, o: MusicVideoOptions): { cuts: number; duration: number; genre: Genre; clipIds: string[] } {
  const music = findAsset(p, o.musicId);
  if (!music) throw new OpError(`No asset ${o.musicId}`);
  const media = (o.assetIds ?? p.assets.filter((a) => (a.kind === "video" || a.kind === "image") && a.createdBy !== "render").map((a) => a.id)).map((id) => {
    const a = findAsset(p, id);
    if (!a || a.kind === "audio" || a.kind === "model") throw new OpError(`${id} is not a photo or video.`);
    return a;
  });
  if (!media.length) throw new OpError("Import some photos or videos first.");
  const song = o.song;
  const genre = o.genre ?? guessGenre(song);
  const G = GENRES[genre];
  const start = o.start ?? 0;
  const end = Math.min(song.duration, o.maxDur ?? Infinity);
  const beats = song.beats.length > 4 ? song.beats : Array.from({ length: Math.ceil(song.duration * 2) }, (_, i) => i * 0.5);
  // Cut points: walk the beat grid, step size from the section energy.
  const cuts: number[] = [0];
  let i = 0;
  while (i < beats.length && beats[i]! < 0.05) i++;
  while (i < beats.length && beats[i]! < end) {
    const t = beats[i]!;
    const sec = song.sections.find((s) => t >= s.start && t < s.end);
    const e = sec?.energy ?? 0.5;
    const step = e > 0.75 ? G.every[2] : e > 0.45 ? G.every[1] : G.every[0];
    if (t - cuts[cuts.length - 1]! >= 0.35) cuts.push(t);
    i += step;
  }
  // Always cut on section starts and big hits.
  for (const s of song.sections) if (s.start > 0.3 && s.start < end) cuts.push(s.start);
  for (const h of song.hits) if (h < end) cuts.push(h);
  const grid = [...new Set(cuts.map((c) => Math.round(c * 100) / 100))].sort((a, b) => a - b).filter((c, k, arr) => k === 0 || c - arr[k - 1]! > 0.3);
  grid.push(end);
  const lower = newTrack("video", "MV A");
  const upper = newTrack("video", "MV B");
  const firstAudio = p.tracks.findIndex((t) => t.kind === "audio");
  p.tracks.splice(firstAudio < 0 ? p.tracks.length : firstAudio, 0, upper, lower);
  const made: Clip[] = [];
  for (let k = 0; k < grid.length - 1; k++) {
    const a = media[k % media.length]!;
    const c0 = start + grid[k]!;
    const c1 = start + grid[k + 1]!;
    const ov = Math.min(G.overlap, (c1 - c0) * 0.4);
    const begin = k === 0 ? c0 : c0 - ov;
    const c = newClip("media", begin, c1 - begin, { assetId: a.id, name: a.name, fit: "cover" });
    if (a.kind === "video") {
      const room = Math.max(0, a.duration - c.dur);
      c.in = Math.min(room, room * ((k * 0.37) % 1));
      if (a.duration < c.dur) c.dur = Math.max(0.1, a.duration);
    }
    applyLook(c, G.look, 0.85);
    c.fx = { ...(c.fx ?? {}), motionBlur: G.punch ? 1 : 0.5 };
    const local = (t: number) => Math.max(0, Math.min(c.dur, t - begin));
    if (G.punch) {
      const keys: Keyframe[] = [];
      for (const b of song.downbeats) {
        const bt = start + b;
        if (bt < c0 || bt >= c1 - 0.05) continue;
        keys.push({ t: local(bt), v: 1.12, ease: "out" }, { t: local(Math.min(c1, bt + 0.25)), v: 1.03, ease: "linear" });
      }
      if (keys.length) c.transform.scale = keys;
    } else {
      const dir = k % 2 ? 1 : -1;
      c.transform.scale = [{ t: 0, v: 1.05, ease: "linear" }, { t: c.dur, v: 1.15, ease: "linear" }];
      c.transform.x = [{ t: 0, v: -dir * p.width * 0.02, ease: "linear" }, { t: c.dur, v: dir * p.width * 0.02, ease: "linear" }];
    }
    if (k > 0) {
      const kind = G.transitions[k % G.transitions.length]!;
      if (k % 2 === 1) c.transitionIn = { kind, dur: ov };
      else made[k - 1]!.transitionOut = { kind: kind === "whipLeft" ? "whipRight" : kind === "whipRight" ? "whipLeft" : kind, dur: ov };
    }
    (k % 2 ? upper : lower).clips.push(c);
    made.push(c);
  }
  if (made.length) made[made.length - 1]!.transitionOut = { kind: "dip", dur: Math.min(1, made[made.length - 1]!.dur / 2) };
  const at = p.tracks.find((t) => t.kind === "audio" && !t.clips.some((c) => c.start < start + end && c.start + c.dur > start)) ?? (() => {
    const t = newTrack("audio", `A${p.tracks.filter((x) => x.kind === "audio").length + 1}`);
    p.tracks.push(t);
    return t;
  })();
  const mc = newClip("media", start, Math.min(end, music.duration), { assetId: music.id, name: music.name, fadeOut: 1.2 });
  mc.id = uid("clp");
  at.clips.push(mc);
  p.markers = song.sections.map((s) => ({ t: start + s.start, label: s.label }));
  return { cuts: made.length, duration: Math.round(end * 100) / 100, genre, clipIds: made.map((c) => c.id) };
}

/** Caption commands for timed lyrics in the genre's style (run through the agent API). */
export function lyricCaptionCommands(lines: LyricLine[], genre: Genre, start = 0, y = 0.78): Record<string, unknown>[] {
  const c = GENRES[genre].caption;
  return lines
    .filter((l) => l.text.trim())
    .map((l) => ({ op: "captions.add", text: l.text, start: start + l.start, end: start + Math.max(l.end, l.start + 0.6), style: c.style, y, ...(c.font ? { font: c.font } : {}), ...(c.color ? { color: c.color } : {}), ...(c.accent ? { accent: c.accent } : {}), ...(c.uppercase ? { uppercase: true } : {}), ...(c.maxWords ? { maxWords: c.maxWords } : {}) }));
}

/* --------------------------------- audio ----------------------------------- */

/** Dip the music under every voice clip (sidechain-style volume keys). */
export function duckMusic(p: Project, o: { amount?: number; fade?: number; musicIds?: string[] } = {}): { ducked: number; windows: number } {
  const amount = o.amount ?? 0.3;
  const fade = o.fade ?? 0.2;
  const all = p.tracks.flatMap((t) => (t.kind === "audio" || t.clips.some((c) => findAsset(p, c.assetId)?.kind === "audio") ? t.clips : []));
  const isVoice = (c: Clip) => findAsset(p, c.assetId)?.createdBy === "voice";
  const voices = all.filter(isVoice).map((c) => [c.start, c.start + c.dur] as [number, number]).sort((a, b) => a[0] - b[0]);
  const merged: [number, number][] = [];
  for (const w of voices) {
    const last = merged[merged.length - 1];
    if (last && w[0] - last[1] < 0.4) last[1] = Math.max(last[1], w[1]);
    else merged.push([...w]);
  }
  let ducked = 0;
  for (const c of all) {
    const a = findAsset(p, c.assetId);
    if (!a || a.kind !== "audio" || isVoice(c)) continue;
    if (o.musicIds && !o.musicIds.includes(c.id) && !o.musicIds.includes(a.id)) continue;
    const base = typeof c.volume === "number" ? c.volume : 1;
    const keys: Keyframe[] = [{ t: 0, v: base, ease: "linear" }];
    for (const [w0, w1] of merged) {
      const l0 = w0 - c.start,
        l1 = w1 - c.start;
      if (l1 < 0 || l0 > c.dur) continue;
      keys.push({ t: Math.max(0, l0 - fade), v: base, ease: "linear" }, { t: Math.max(0, l0), v: base * amount, ease: "linear" }, { t: Math.min(c.dur, l1), v: base * amount, ease: "linear" }, { t: Math.min(c.dur, l1 + fade * 2), v: base, ease: "linear" });
    }
    if (keys.length > 1) {
      keys.sort((x, y) => x.t - y.t);
      c.volume = keys.filter((k, i) => i === 0 || Math.abs(k.t - keys[i - 1]!.t) > 1e-3);
      ducked++;
    }
  }
  return { ducked, windows: merged.length };
}

/* -------------------------------- reframe ---------------------------------- */

/** Fit a clip to the project frame (vertical from landscape and back) with a slow pan or a fixed focus. */
export function reframe(p: Project, id: string, focus: "center" | "left" | "right" | "pan" = "center"): Clip {
  const c = getClip(p, id);
  const a = findAsset(p, c.assetId);
  if (!a || (a.kind !== "video" && a.kind !== "image")) throw new OpError("Reframe works on photos and videos.");
  c.fit = "cover";
  const k = Math.max(p.width / a.width, p.height / a.height);
  const spare = Math.max(0, (a.width * k - p.width) / 2);
  if (focus === "pan") c.transform.x = [{ t: 0, v: spare * 0.9, ease: "inOut" }, { t: c.dur, v: -spare * 0.9, ease: "inOut" }];
  else c.transform.x = focus === "left" ? spare : focus === "right" ? -spare : 0;
  return c;
}
