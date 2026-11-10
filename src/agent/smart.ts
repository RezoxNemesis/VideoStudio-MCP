/**
 * VideoStudio's own intelligence, as commands and as functions the panels call:
 *  - Audience Lens: look at the edit the way an audience and a platform would — score, findings, predicted attention,
 *    one-tap fixes, and "Evolve" (apply every safe fix, then look again).
 *  - Style DNA: measure the feel of a finished video or of your own projects (cut rhythm against the music, pacing
 *    arc, colour, titles), keep it in a local library, and transplant it onto another timeline.
 * Everything is measured and decided on this device; nothing is sent anywhere.
 */
import type { Studio } from "../app/studio";
import { analyseBeats } from "../app/autoedit";
import { loudness } from "../audio/enhance";
import { mergeStats } from "../color/match";
import { assetStats, clipStats } from "../color/probe";
import type { ColourStats } from "../color/types";
import { findAsset, projectDuration, type Project, type TextStyle } from "../core/model";
import { OpError } from "../core/ops";
import { kvGet, kvSet } from "../core/storage";
import { detectBeats } from "../media/beats";
import type { MediaPool } from "../media/library";
import { detectScenes } from "../media/vision";
import { mixdown } from "../render/audio";
import { Compositor } from "../render/compositor";
import { fontStack } from "../render/fonts";
import { buildLayers } from "../render/layers";
import { applyDna, describeDna, dnaDistance, dnaFromCuts, dnaFromProject, isShotClip, mergeDna, validateDna, type Dna } from "../smart/dna";
import { evolve, lens, type Finding, type LensContext, type LensReport, type Platform } from "../smart/lens";
import { registerCommand, runBatch, type Command } from "./api";
import { matchClipsToStats } from "./color";

const r3 = (n: number) => Math.round(n * 1000) / 1000;

/* ----------------------------------- measuring the edit ----------------------------------- */

export interface Heard {
  beats: number[];
  downbeats: number[];
  bpm: number;
  integratedLufs: number;
  peakDb: number;
  silences: { start: number; end: number }[];
  hasMusic: boolean;
}

/** Listen to the whole mix: loudness, peak, silent stretches and (when there is music) the beat grid, in timeline time. */
export async function hearEdit(p: Project, pool: MediaPool): Promise<Heard | null> {
  const end = projectDuration(p);
  if (end <= 0) return null;
  const mix = await mixdown(p, pool, 0, end, 44_100).catch(() => null);
  if (!mix) return null;
  const chs = [mix.getChannelData(0), mix.getChannelData(mix.numberOfChannels > 1 ? 1 : 0)];
  const sr = mix.sampleRate;
  let peak = 0;
  for (const ch of chs) for (let i = 0; i < ch.length; i++) peak = Math.max(peak, Math.abs(ch[i]!));
  if (peak < 1e-5) return { beats: [], downbeats: [], bpm: 0, integratedLufs: -70, peakDb: -100, silences: [{ start: 0, end }], hasMusic: false };
  // Silences: 50 ms windows quieter than −50 dBFS, joined, at least 0.3 s long.
  const win = Math.round(sr * 0.05);
  const silences: { start: number; end: number }[] = [];
  let open = -1;
  const n = chs[0]!.length;
  for (let i = 0; i + win <= n; i += win) {
    let sum = 0;
    for (let k = i; k < i + win; k++) sum += chs[0]![k]! * chs[0]![k]! + chs[1]![k]! * chs[1]![k]!;
    const quiet = Math.sqrt(sum / (2 * win)) < 0.00316;
    if (quiet && open < 0) open = i;
    if ((!quiet || i + 2 * win > n) && open >= 0) {
      const a = open / sr;
      const b = (quiet ? i + win : i) / sr;
      if (b - a >= 0.3) silences.push({ start: r3(a), end: r3(b) });
      open = -1;
    }
  }
  // Music = a sound-only file on the timeline for at least 5 seconds (voice-overs made by the voice engine do not count).
  const hasMusic = p.tracks.some((t) => !t.muted && t.clips.some((c) => c.kind === "media" && c.dur >= 5 && findAsset(p, c.assetId)?.kind === "audio" && !findAsset(p, c.assetId)?.mouth));
  let beats: number[] = [];
  let downbeats: number[] = [];
  let bpm = 0;
  if (hasMusic) {
    const b = detectBeats(chs, sr);
    beats = b.beats;
    downbeats = b.downbeats;
    bpm = b.bpm;
  }
  return { beats, downbeats, bpm, integratedLufs: r3(loudness(chs, sr)), peakDb: r3(20 * Math.log10(peak)), silences, hasMusic };
}

/** Watch the edit at low resolution: brightness of each sample and how much it differs from the one before. */
export async function watchEdit(p: Project, pool: MediaPool, maxSamples = 96): Promise<{ frames: { t: number; luma: number; diffPrev?: number }[]; loopDiff?: number }> {
  const end = projectDuration(p);
  if (end <= 0) return { frames: [] };
  const h = 72;
  const w = Math.max(2, Math.round(((p.width / p.height) * h) / 2) * 2);
  const step = Math.max(0.25, end / maxSamples);
  const canvas = new OffscreenCanvas(w, h);
  const comp = new Compositor(canvas);
  const grab = async (t: number): Promise<Float32Array> => {
    const layers = await buildLayers(p, pool, t, { purpose: "export", wait: true, outW: w, outH: h });
    comp.render(layers, w, h, p.background);
    const px = new Uint8Array(w * h * 4);
    comp.gl.readPixels(0, 0, w, h, comp.gl.RGBA, comp.gl.UNSIGNED_BYTE, px);
    const l = new Float32Array(w * h);
    for (let i = 0; i < l.length; i++) l[i] = (0.2126 * px[i * 4]! + 0.7152 * px[i * 4 + 1]! + 0.0722 * px[i * 4 + 2]!) / 255;
    return l;
  };
  const diff = (a: Float32Array, b: Float32Array) => {
    let d = 0;
    for (let i = 0; i < a.length; i++) d += Math.abs(a[i]! - b[i]!);
    return d / a.length;
  };
  const mean = (a: Float32Array) => a.reduce((x, y) => x + y, 0) / a.length;
  try {
    const frames: { t: number; luma: number; diffPrev?: number }[] = [];
    let prev: Float32Array | null = null;
    let first: Float32Array | null = null;
    for (let t = 0; t < end - 1e-3; t += step) {
      const l = await grab(t);
      frames.push({ t: r3(t), luma: r3(mean(l)), ...(prev ? { diffPrev: r3(diff(l, prev)) } : {}) });
      first ??= l;
      prev = l;
    }
    const last = await grab(Math.max(0, end - 1 / Math.max(1, p.fps)));
    return { frames, loopDiff: first ? r3(diff(first, last)) : undefined };
  } finally {
    comp.dispose();
    await pool.dropFramers("export").catch(() => {});
  }
}

let measureCtx: OffscreenCanvasRenderingContext2D | null = null;
/** The size of a title's text block at project scale, measured with the real font (same rules as the renderer). */
export function measureTextBlock(p: Project): (text: string, st: TextStyle) => { w: number; h: number } {
  const k = p.height / 1920;
  return (text, st) => {
    measureCtx ??= new OffscreenCanvas(4, 4).getContext("2d") as OffscreenCanvasRenderingContext2D;
    const size = Math.max(4, st.size * k);
    measureCtx.font = `${st.weight} ${size}px ${/[,"']/.test(st.font) ? st.font : fontStack(st.font)}`;
    const trk = (st.tracking ?? 0) * k;
    const lines = text.split("\n");
    const w = Math.max(1, ...lines.map((l) => measureCtx!.measureText(l).width + Math.max(0, [...l].length - 1) * trk));
    return { w, h: lines.length * size * 1.2 };
  };
}

export interface LensOptions {
  platform?: Platform;
  /** Listen to the mix (loudness, silences, beats). Default true. */
  sound?: boolean;
  /** Watch the picture (dark opening, still stretches, loop). Default true. */
  picture?: boolean;
}

/** Run the Audience Lens on the current edit with everything this device can measure. */
export async function runLens(s: Studio, o: LensOptions = {}): Promise<LensReport> {
  const p = s.project;
  const ctx: LensContext = { platform: o.platform, measureText: measureTextBlock(p) };
  if (o.sound !== false) {
    const heard = await hearEdit(p, s.pool).catch(() => null);
    if (heard) {
      ctx.loudness = { integratedLufs: heard.integratedLufs, truePeakDb: heard.peakDb, silences: heard.silences };
      if (heard.hasMusic && heard.beats.length >= 4) {
        ctx.beats = heard.beats;
        ctx.downbeats = heard.downbeats;
        ctx.bpm = heard.bpm;
      }
    }
  }
  if (o.picture !== false) {
    const seen = await watchEdit(p, s.pool).catch(() => null);
    if (seen) {
      ctx.frames = seen.frames;
      ctx.loopDiff = seen.loopDiff;
    }
  }
  const report = lens(p, ctx);
  lastReport = report;
  for (const f of lensListeners) f(report);
  return report;
}

let lastReport: LensReport | null = null;
const lensListeners = new Set<(r: LensReport) => void>();
/** The latest report (for the panel), and a way to hear about new ones. */
export const latestLens = (): LensReport | null => lastReport;
export function onLens(f: (r: LensReport) => void): () => void {
  lensListeners.add(f);
  return () => lensListeners.delete(f);
}

/** Apply every safe fix, look again, repeat (at most `rounds` times) while the score improves. */
export async function evolveEdit(s: Studio, o: LensOptions & { rounds?: number } = {}): Promise<{ before: LensReport; after: LensReport; applied: { id: string; title: string; fix: string }[] }> {
  const before = await runLens(s, o);
  let cur = before;
  const applied: { id: string; title: string; fix: string }[] = [];
  for (let round = 0; round < Math.max(1, Math.min(4, o.rounds ?? 2)); round++) {
    const ev = evolve(s.project, cur);
    if (!ev.commands.length) break;
    const res = await runBatch(s, ev.commands as Command[], true);
    const failed = res.filter((r) => !r.ok).length;
    for (const id of ev.applied) {
      const f = cur.findings.find((x) => x.id === id);
      if (f?.fix) applied.push({ id, title: f.title, fix: f.fix.label });
    }
    const next = await runLens(s, o);
    if (next.score <= cur.score && failed === 0 && round > 0) {
      cur = next;
      break;
    }
    cur = next;
  }
  return { before, after: cur, applied };
}

function briefFinding(f: Finding): Record<string, unknown> {
  return { id: f.id, area: f.area, severity: f.severity, title: f.title, detail: f.detail, ...(f.at !== undefined ? { at: r3(f.at) } : {}), ...(f.clipIds?.length ? { clipIds: f.clipIds } : {}), ...(f.fix ? { fix: f.fix.label } : {}) };
}
function briefReport(r: LensReport, all = false): Record<string, unknown> {
  const pick = <T,>(a: T[], n: number) => (a.length <= n ? a : Array.from({ length: n }, (_, i) => a[Math.round((i * (a.length - 1)) / (n - 1))]!));
  return {
    platform: r.platform,
    score: r.score,
    verdict: r.verdict,
    areas: r.areas,
    findings: r.findings.filter((f) => all || f.severity !== "good").map(briefFinding),
    good: r.findings.filter((f) => f.severity === "good").length,
    attention: pick(r.attention, 24).map((a) => ({ t: r3(a.t), v: r3(a.v) })),
    facts: r.facts,
  };
}

/* ----------------------------------- the DNA library ----------------------------------- */

const DNA_KEY = "dna.library";
export const SIGNATURE = "My signature";

export async function dnaLibrary(): Promise<Dna[]> {
  const raw = (await kvGet<unknown[]>(DNA_KEY).catch(() => undefined)) ?? [];
  const out: Dna[] = [];
  for (const x of Array.isArray(raw) ? raw : []) {
    try {
      out.push(validateDna(x));
    } catch {
      /* a broken record is skipped, never fatal */
    }
  }
  return out;
}
async function saveDna(d: Dna): Promise<void> {
  const lib = (await dnaLibrary()).filter((x) => x.name.toLowerCase() !== d.name.toLowerCase());
  lib.push(d);
  await kvSet(DNA_KEY, lib.slice(-60));
  for (const f of dnaListeners) f();
}
async function findDna(name: string): Promise<Dna> {
  const lib = await dnaLibrary();
  const d = lib.find((x) => x.name.toLowerCase() === name.trim().toLowerCase());
  if (!d) throw new OpError(lib.length ? `No style DNA called "${name}". Saved: ${lib.map((x) => x.name).join(", ")}.` : "No style DNA saved yet. Make one with dna.extract (from a video) or dna.fromProject.");
  return d;
}
const dnaListeners = new Set<() => void>();
export function onDnaLibrary(f: () => void): () => void {
  dnaListeners.add(f);
  return () => dnaListeners.delete(f);
}

function briefDna(d: Dna): Record<string, unknown> {
  return { name: d.name, source: d.source, samples: d.samples, shots: d.rhythm.shotCount, medianShot: r3(d.rhythm.shotMedian), bpm: d.rhythm.bpm ? Math.round(d.rhythm.bpm) : undefined, onBeat: d.rhythm.onBeat !== undefined ? r3(d.rhythm.onBeat) : undefined, hasColour: !!d.colour, hasTitles: !!d.type, feel: describeDna(d) };
}

/** The colour of the edit's shots, averaged (at most 8 shots, spread across the timeline). */
async function shotsColour(s: Studio): Promise<ColourStats | undefined> {
  const shots = s.project.tracks.flatMap((t) => (t.hidden ? [] : t.clips.filter((c) => isShotClip(s.project, c)))).sort((a, b) => a.start - b.start);
  if (!shots.length) return undefined;
  const pick = shots.length <= 8 ? shots : Array.from({ length: 8 }, (_, i) => shots[Math.round((i * (shots.length - 1)) / 7)]!);
  const stats: ColourStats[] = [];
  for (const c of pick) stats.push(await clipStats(s.project, s.pool, c.id, { samples: 1 }));
  return stats.length === 1 ? stats[0] : mergeStats(stats);
}

/** Measure a finished video in the media list: cuts, music grid and colour → its style DNA. */
export async function extractDna(s: Studio, assetId: string, name?: string): Promise<Dna> {
  const a = findAsset(s.project, assetId);
  if (!a || a.kind !== "video") throw new OpError("Pick a video in the media list as the reference (a finished edit you like).");
  const sc = await detectScenes(s.project, s.pool, assetId, 8);
  const duration = Math.max(0.1, sc.contentEnd || sc.duration || a.duration);
  const beat = await analyseBeats(s, assetId).catch(() => null);
  const colour = await assetStats(s.project, s.pool, assetId, { samples: 6 }).catch(() => undefined);
  const d = dnaFromCuts({
    name: (name ?? a.name.replace(/\.[^.]+$/, "")).slice(0, 60),
    cuts: sc.cuts.filter((t) => t > 0 && t < duration),
    duration,
    ...(beat && beat.beats.length >= 4 ? { beats: beat.beats.filter((t) => t <= duration), downbeats: beat.downbeats.filter((t) => t <= duration), bpm: beat.bpm } : {}),
    colour,
  });
  await saveDna(d);
  return d;
}

/** The current timeline's own DNA (optionally folded into the personal signature). */
export async function projectDna(s: Studio, o: { name?: string; signature?: boolean } = {}): Promise<Dna> {
  const heard = await hearEdit(s.project, s.pool).catch(() => null);
  const colour = await shotsColour(s).catch(() => undefined);
  const d = dnaFromProject(s.project, { name: (o.name ?? s.project.name).slice(0, 60), ...(heard?.hasMusic && heard.beats.length >= 4 ? { beats: heard.beats, downbeats: heard.downbeats, bpm: heard.bpm } : {}), colour });
  if (o.signature) {
    const lib = await dnaLibrary();
    const old = lib.find((x) => x.name === SIGNATURE);
    const merged = mergeDna(old ? [old, d] : [d], SIGNATURE);
    await saveDna(merged);
    return merged;
  }
  if (o.name) await saveDna(d);
  return d;
}

/** Transplant a DNA onto the current timeline: rhythm (re-timed cuts), titles, and colour (a "Style DNA" node per shot). */
export async function transplantDna(s: Studio, d: Dna, o: { parts?: string[]; seed?: number; colourStrength?: number } = {}): Promise<Record<string, unknown>> {
  const parts = (o.parts?.length ? o.parts : ["rhythm", "type", "colour"]).map((x) => (x === "color" ? "colour" : x));
  const heard = parts.includes("rhythm") ? await hearEdit(s.project, s.pool).catch(() => null) : null;
  // Distances include colour (when the DNA has it), so the number reflects everything that was transplanted.
  const beatsOf = heard?.hasMusic ? { beats: heard.beats, downbeats: heard.downbeats, bpm: heard.bpm } : {};
  const measure = async () => dnaDistance(dnaFromProject(s.project, { ...beatsOf, colour: d.colour ? await shotsColour(s).catch(() => undefined) : undefined }), d);
  const before = await measure();
  const plan = applyDna(s.project, d, { ...(heard?.hasMusic && heard.beats.length >= 4 ? { beats: heard.beats, downbeats: heard.downbeats, bpm: heard.bpm } : {}), seed: o.seed, parts: parts.filter((x) => x === "rhythm" || x === "type" || x === "motion") as ("rhythm" | "type" | "motion")[] });
  const res = plan.commands.length ? await runBatch(s, plan.commands as Command[], true) : [];
  // When the colour is matched here, the planner's hint to "match it with the colour tool" would only confuse.
  const notes = plan.notes.filter((n) => !(parts.includes("colour") && d.colour && /colour look/i.test(n)));
  let colour: Record<string, unknown> | undefined;
  if (parts.includes("colour")) {
    if (!d.colour) notes.push("This DNA carries no colour, so the colour was left as it is.");
    else {
      const ids = s.project.tracks.flatMap((t) => (t.hidden ? [] : t.clips.filter((c) => isShotClip(s.project, c)).map((c) => c.id)));
      const m = await matchClipsToStats(s, ids, d.colour, d.name, "lut", Math.max(0, Math.min(1, o.colourStrength ?? 0.8)), "Style DNA");
      colour = { shots: m.results.length, before: m.results.length ? r3(m.results.reduce((x, y) => x + y.before, 0) / m.results.length) : 0, after: m.results.length ? r3(m.results.reduce((x, y) => x + y.after, 0) / m.results.length) : 0, differences: m.differences };
    }
  }
  const after = await measure();
  return { dna: d.name, commands: plan.commands.length, failed: res.filter((r) => !r.ok).map((r) => r.error), notes, colour, distanceBefore: r3(before), distanceAfter: r3(after) };
}

/* ----------------------------------- commands ----------------------------------- */

export function registerSmartCommands(): void {
  registerCommand(
    "lens.run",
    "{platform?: instagram-reel|tiktok|youtube-shorts|youtube|general, sound?=true, picture?=true, all?=false} Audience Lens: look at the edit the way an audience and the platform would. Returns a 0..100 score, area scores (hook, pacing, text, safe zone, sound, ending, technical), findings from most to least important (with the fix each offers), a predicted attention curve, and measured facts. These are predictions to guide the edit, not guarantees.",
    async (s, a) => briefReport(await runLens(s, { platform: a.optStr("platform") as Platform | undefined, sound: a.bool("sound", true), picture: a.bool("picture", true) }), a.bool("all")),
  );
  registerCommand("lens.fix", "{finding (a finding id from lens.run)} Apply the fix one finding offers.", async (s, a) => {
    const rep = lastReport ?? (await runLens(s));
    const f = rep.findings.find((x) => x.id === a.str("finding"));
    if (!f) throw new OpError("No such finding in the latest report. Run lens.run again.");
    if (!f.fix) throw new OpError("That finding has no automatic fix; it needs an editing decision.");
    const res = await runBatch(s, f.fix.commands as Command[], true);
    return { applied: f.fix.label, failed: res.filter((r) => !r.ok).map((r) => r.error) };
  });
  registerCommand(
    "lens.evolve",
    "{platform?, rounds?=2, sound?=true, picture?=true} Evolve: apply every safe fix the Audience Lens offers (moving titles into the safe area, giving text time to be read, fades, trims…), look again, and repeat while the score improves. Every change is an ordinary undoable edit. Returns the score before and after, what was changed, and what still needs a decision.",
    async (s, a) => {
      const r = await evolveEdit(s, { platform: a.optStr("platform") as Platform | undefined, rounds: a.num("rounds", 2), sound: a.bool("sound", true), picture: a.bool("picture", true) });
      return { before: r.before.score, after: r.after.score, applied: r.applied, remaining: r.after.findings.filter((f) => f.severity !== "good").map(briefFinding), verdict: r.after.verdict };
    },
  );

  registerCommand(
    "dna.extract",
    "{assetId (a finished video in the media list), name?} Style DNA: measure the feel of a video — where its cuts fall against its music, how shot length rises and falls, its colour — and save it in the DNA library on this device.",
    async (s, a) => briefDna(await extractDna(s, a.str("assetId"), a.optStr("name"))),
  );
  registerCommand(
    "dna.fromProject",
    "{name?, signature?=false} Measure the current timeline's own style DNA (rhythm, colour, titles, motion). name saves it in the library; signature:true folds it into \"My signature\", the running average of your own projects.",
    async (s, a) => briefDna(await projectDna(s, { name: a.optStr("name"), signature: a.bool("signature") })),
  );
  registerCommand("dna.list", "The style DNAs saved on this device, each with a short description of its feel.", async () => (await dnaLibrary()).map(briefDna));
  registerCommand("dna.get", "{name} A saved style DNA as JSON (to keep or share).", async (_s, a) => findDna(a.str("name")));
  registerCommand("dna.add", "{dna (a style DNA record, as returned by dna.get)} Add a style DNA to the library.", async (_s, a) => {
    const d = validateDna(a.any("dna"));
    await saveDna(d);
    return briefDna(d);
  });
  registerCommand("dna.remove", "{name} Delete a style DNA from the library.", async (_s, a) => {
    const d = await findDna(a.str("name"));
    await kvSet(DNA_KEY, (await dnaLibrary()).filter((x) => x.name !== d.name));
    for (const f of dnaListeners) f();
    return true;
  });
  registerCommand("dna.compare", "{name} How close the current timeline is to a saved style DNA (0 = same feel; under 0.1 same style, over 0.5 different).", async (s, a) => {
    const d = await findDna(a.str("name"));
    const mine = await projectDna(s);
    return { distance: r3(dnaDistance(mine, d)), theirs: describeDna(d), yours: describeDna(mine) };
  });
  registerCommand(
    "dna.apply",
    "{name, parts?: [rhythm, type, colour], seed?, colourStrength?=0.8} Transplant a style DNA onto the current timeline: re-time the cuts to its rhythm (only when the shots form one clean sequence; same shots, same total length), restyle the titles, and add a \"Style DNA\" grading node to every shot that brings its colour to the DNA's. Everything is undoable; notes explain anything that was left alone.",
    async (s, a) => {
      const parts = a.any("parts");
      return transplantDna(s, await findDna(a.str("name")), { parts: Array.isArray(parts) ? parts.map(String) : undefined, seed: a.optNum("seed"), colourStrength: a.optNum("colourStrength") });
    },
  );
}
