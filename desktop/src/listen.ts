/**
 * Listening to a song like an editor would: what is sung and when (Whisper large-v3-turbo on the GPU, run on a
 * voice-focused copy), where the voice is present (stereo centre analysis), the structure (intro / verse /
 * chorus…), downbeats, energy and hits — and from that, which stretch of the song best fits a given length.
 */
import type { Studio } from "../../src/app/studio";
import type { Asset } from "../../src/core/model";
import { kvGet, kvSet } from "../../src/core/storage";
import { analyseSong, type SongInfo } from "../../src/media/song";
import { transcribe, type LyricLine } from "../../src/media/asr";
import { need, type AudioReport } from "./bridge";
import { assetPath } from "./importer";

export interface Listening {
  file: string;
  duration: number;
  bpm: number;
  key: string;
  mood: SongInfo["mood"];
  sections: SongInfo["sections"];
  downbeats: number[];
  hits: number[];
  energy: number[]; // every 0.5 s, 0..1
  vocals: { start: number; end: number }[];
  lyrics?: { language?: string; model: string; device: string; gpuError?: string; lines: LyricLine[] } | { error: string };
  loudness: AudioReport["loudness"];
}

const cache = new Map<string, Listening>();

async function decode(bytes: ArrayBuffer): Promise<SongInfo> {
  const ctx = new OfflineAudioContext(1, 1, 22050);
  const b = await ctx.decodeAudioData(bytes);
  const chans: Float32Array[] = [];
  for (let c = 0; c < Math.min(2, b.numberOfChannels); c++) chans.push(b.getChannelData(c));
  return analyseSong(chans, b.sampleRate);
}

/** Line starts snapped to where the voice actually begins (Whisper's timestamps drift over music). */
function snapLines(lines: LyricLine[], vocals: { start: number; end: number }[]): LyricLine[] {
  return lines.map((l) => {
    let best = l.start;
    let bd = 1.2;
    for (const v of vocals) {
      const d = Math.abs(v.start - l.start);
      if (d < bd) (bd = d), (best = v.start);
    }
    const start = Math.round(best * 100) / 100;
    return { ...l, start, end: Math.max(start + 0.6, l.end) };
  });
}

/** Drop Whisper's loops ("अगर अगर अगर…") and lines that are mostly one repeated word. */
export function cleanLine(text: string): string | null {
  // Loops inside a word ("अदादादादा…"): keep at most two repeats of any 1-4 character pattern.
  text = text.replace(/(.{1,4}?)\1{2,}/gu, "$1$1");
  // Repeated phrases ("a b c a b c a b c"): keep one.
  text = text.replace(/((?:\S+\s+){1,5}?)\1{2,}/gu, "$1");
  const words = text.split(/\s+/).filter(Boolean).filter((w) => [...w].length <= 22);
  if (!words.length) return null;
  const out: string[] = [];
  for (const w of words) {
    const n = out.length;
    if (n >= 2 && out[n - 1] === w && out[n - 2] === w) continue; // at most two in a row
    out.push(w);
  }
  const counts = new Map<string, number>();
  for (const w of out) counts.set(w, (counts.get(w) ?? 0) + 1);
  const top = Math.max(...counts.values());
  if (out.length >= 6 && top / out.length > 0.45) return null;
  if (/�/.test(text) && out.filter((w) => /�/.test(w)).length > 2) return null;
  const t = out.join(" ").replace(/\s*�+\s*/g, " ").trim();
  return t.length >= 2 ? t : null;
}

/**
 * Transcribe only where the voice map says someone sings: each sung passage (merged into ≤ 25 s windows with a
 * little padding) goes to Whisper on its own, so instrumentals can't produce hallucinated loops and timings stay
 * anchored. Tries the GPU (large-v3-turbo) first and reports which device and model actually ran.
 */
async function sungLyrics(s: Studio, path: string, name: string, vocals: { start: number; end: number }[], o: { language?: string; model?: "turbo" | "small" | "base" | "tiny" }) {
  const vsd = need();
  const raw = await vsd.voice16k(path);
  const all = new Float32Array(raw.buffer.slice(raw.byteOffset, raw.byteOffset + raw.byteLength));
  const SR = 16000;
  const dur = all.length / SR;
  const segs = vocals.length ? vocals : [{ start: 0, end: dur }];
  const windows: { start: number; end: number }[] = [];
  for (const v of segs) {
    const a = Math.max(0, v.start - 0.4), b = Math.min(dur, v.end + 0.5);
    const last = windows[windows.length - 1];
    if (last && a - last.end < 1.2 && b - last.start <= 25) last.end = b;
    else if (b - a > 25) for (let t = a; t < b; t += 24) windows.push({ start: t, end: Math.min(b, t + 25) });
    else windows.push({ start: a, end: b });
  }
  let model: "turbo" | "small" | "base" | "tiny" = o.model ?? "turbo";
  let device: "webgpu" | "wasm" = "webgpu";
  let gpuError: string | undefined;
  const lines: LyricLine[] = [];
  for (let i = 0; i < windows.length; i++) {
    const w = windows[i]!;
    const clip = all.slice(Math.floor(w.start * SR), Math.ceil(w.end * SR));
    s.setStatus(`Lyrics of ${name}: passage ${i + 1}/${windows.length} (${model} on ${device === "webgpu" ? "GPU" : "CPU"})…`);
    // Pad short passages with a little silence: Whisper decodes them more reliably.
    const padN = Math.max(0, Math.round((8 - (w.end - w.start)) * SR * 0.5));
    const input = padN ? (() => {
      const x = new Float32Array(clip.length + padN * 2);
      x.set(clip, padN);
      return x;
    })() : clip;
    const shift = padN / SR;
    const run = (dev: "webgpu" | "wasm") => transcribe(input.slice(), { model, language: o.language, device: dev, onProgress: (st, pct) => st === "model" && s.setStatus(pct >= 100 ? `Loading the lyrics model (${model}) onto the ${dev === "webgpu" ? "GPU" : "CPU"}…` : `Downloading the lyrics model (${model}) ${pct}%`) });
    let r: Awaited<ReturnType<typeof transcribe>> | null = null;
    try {
      r = await run(device);
    } catch (e) {
      const msg = (e as Error).message ?? String(e);
      if (/token_ids|non-empty|empty array/i.test(msg)) {
        r = null; // nothing recognisable sung in this passage — skip it, keep the device
      } else if (device === "webgpu") {
        gpuError = msg;
        device = "wasm";
        model = model === "turbo" ? "small" : model;
        try {
          r = await run(device);
        } catch (e2) {
          if (!/token_ids|non-empty|empty array/i.test((e2 as Error).message ?? "")) throw e2;
        }
      } else throw e;
    }
    if (!r) continue;
    r = { ...r, lines: r.lines.map((ln) => ({ ...ln, start: Math.max(0, ln.start - shift), end: Math.max(0, ln.end - shift) })) };
    for (const ln of r.lines) {
      const text = cleanLine(ln.text);
      if (!text) continue;
      // Physically impossible singing speed = a hallucination.
      if (text.split(/\s+/).length / Math.max(0.3, ln.end - ln.start) > 7 && text.split(/\s+/).length > 4) continue;
      const start = Math.round((w.start + ln.start) * 100) / 100;
      const end = Math.round(Math.min(w.end, w.start + Math.max(ln.end, ln.start + 0.6)) * 100) / 100;
      if (lines.length && Math.abs(lines[lines.length - 1]!.start - start) < 0.3 && lines[lines.length - 1]!.text === text) continue;
      lines.push({ start, end, text });
    }
  }
  return { language: o.language, model, device: device === "webgpu" ? "GPU (WebGPU)" : "CPU", gpuError, lines: snapLines(lines, vocals) };
}

export async function listen(
  s: Studio,
  src: { assetId?: string; path?: string },
  o: { lyrics?: boolean; language?: string; model?: "turbo" | "small" | "base" | "tiny"; fresh?: boolean } = {},
): Promise<Listening> {
  const vsd = need();
  let path = src.path;
  let name = path?.split("/").pop() ?? "";
  let asset: Asset | undefined;
  if (!path) {
    asset = s.project.assets.find((a) => a.id === src.assetId);
    if (!asset) throw new Error("Give assetId or path.");
    path = await assetPath(s, asset);
    name = asset.name;
  }
  const key = `listen:${path}:${o.language ?? ""}`;
  const stored = !o.fresh ? cache.get(key) ?? (await kvGet<Listening>(key).catch(() => undefined)) : undefined;
  if (stored && (!o.lyrics || (stored.lyrics && !("error" in stored.lyrics)))) return stored;

  s.setStatus(`Listening to ${name}: structure…`);
  const rep = await vsd.audioInspect(path, { pictures: false });
  const bytes = await (await fetch(await vsd.fileUrl(path))).arrayBuffer();
  const song = await decode(bytes);
  const vocals = rep.vocals?.segments ?? [];
  const out: Listening = {
    file: name,
    duration: rep.duration,
    bpm: song.bpm,
    key: song.key,
    mood: song.mood,
    sections: song.sections,
    downbeats: song.downbeats,
    hits: song.hits,
    energy: song.energy,
    vocals,
    loudness: rep.loudness,
  };
  if (o.lyrics !== false) {
    try {
      out.lyrics = await sungLyrics(s, path, name, vocals, o);
    } catch (e) {
      out.lyrics = { error: (e as Error).message };
    }
  }
  s.setStatus("");
  cache.set(key, out);
  await kvSet(key, out).catch(() => {});
  return out;
}

/** A readable timeline of the song for an editor (or Claude): one row per event, in time order. */
export function notes(l: Listening): string[] {
  const rows: [number, string][] = [];
  for (const sec of l.sections) rows.push([sec.start, `§ ${sec.label} (energy ${Math.round(sec.energy * 100)}%, group ${sec.group}) until ${sec.end.toFixed(1)}s`]);
  for (const v of l.vocals) rows.push([v.start, `♪ voice ${v.start.toFixed(1)}–${v.end.toFixed(1)}s`]);
  if (l.lyrics && !("error" in l.lyrics)) for (const ln of l.lyrics.lines) rows.push([ln.start, `“${ln.text}” (${ln.start.toFixed(1)}–${ln.end.toFixed(1)}s)`]);
  for (const h of l.hits.slice(0, 12)) rows.push([h, `! hit`]);
  rows.sort((a, b) => a[0] - b[0]);
  return rows.map(([t, r]) => `${t.toFixed(2).padStart(7)}s  ${r}`);
}

export interface Pick {
  start: number;
  end: number;
  score: number;
  why: string[];
  sections: string[];
  lyrics: string[];
}

/**
 * Best stretches of a song for a given length: starts on a downbeat at a section or phrase start, ends on a
 * downbeat (or in a vocal gap) instead of mid-word, contains the hook, and has an arc (rises, or lands on a hit).
 */
export function pickSections(l: Listening, seconds: number, o: { prefer?: "chorus" | "vocals" | "calm" | "build" | "intro"; count?: number; mustStart?: number } = {}): Pick[] {
  const dur = l.duration;
  const L = Math.min(seconds, dur);
  const db = l.downbeats.length ? l.downbeats : Array.from({ length: Math.floor(dur / 2) }, (_, i) => i * 2);
  const lines = l.lyrics && !("error" in l.lyrics) ? l.lyrics.lines : [];
  const energyAt = (a: number, b: number) => {
    const i0 = Math.max(0, Math.floor(a * 2)), i1 = Math.min(l.energy.length, Math.ceil(b * 2));
    let s = 0;
    for (let i = i0; i < i1; i++) s += l.energy[i] ?? 0;
    return i1 > i0 ? s / (i1 - i0) : 0;
  };
  const overlap = (a0: number, a1: number, b0: number, b1: number) => Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
  const inVoice = (t: number) => l.vocals.some((v) => t > v.start + 0.15 && t < v.end - 0.15);
  const midLine = (t: number) => lines.some((ln) => t > ln.start + 0.2 && t < ln.end - 0.2);
  const secStarts = new Set(l.sections.map((x) => Math.round(x.start * 10)));
  const cands: Pick[] = [];
  const starts = o.mustStart !== undefined ? [o.mustStart] : db.filter((_, i) => i % 2 === 0 || secStarts.has(Math.round(db[i]! * 10)));
  for (const st of starts) {
    if (st + L > dur + 0.05) break;
    // End on the downbeat nearest st+L (within ±2 s), else exactly st+L.
    let end = st + L;
    let bd = 2;
    for (const d of db) if (Math.abs(d - (st + L)) < bd && d > st + L * 0.8) (bd = Math.abs(d - (st + L))), (end = d);
    const why: string[] = [];
    let score = 0;
    const secs = l.sections.filter((x) => overlap(st, end, x.start, x.end) > 1.5);
    const chorus = secs.filter((x) => /chorus|drop/.test(x.label)).reduce((a, x) => a + overlap(st, end, x.start, x.end), 0);
    const voice = l.vocals.reduce((a, v) => a + overlap(st, end, v.start, v.end), 0) / (end - st);
    const eMean = energyAt(st, end);
    const arc = energyAt(end - Math.min(10, L / 3), end) - energyAt(st, st + Math.min(10, L / 3));
    if (secStarts.has(Math.round(st * 10))) (score += 2), why.push("starts where a section starts");
    if (!inVoice(st) && !midLine(st)) (score += 1), why.push("starts between sung lines");
    else score -= 1.5;
    if (!midLine(end) && !inVoice(end)) (score += 1.5), why.push("ends in a breath, not mid-word");
    else score -= 2;
    if (l.sections.some((x) => Math.abs(x.end - end) < 1.2)) (score += 1.5), why.push("ends where a section ends");
    if (l.hits.some((h) => h > st + 1 && h < end)) (score += 0.8), why.push("contains a hit for a big cut");
    score += eMean * 2;
    if (arc > 0.05) (score += Math.min(1.5, arc * 6)), why.push("builds up");
    const pref = o.prefer ?? "chorus";
    if (pref === "chorus") (score += Math.min(3, chorus / 6)), chorus > 3 && why.push(`${chorus.toFixed(0)} s of the chorus`);
    if (pref === "vocals") (score += voice * 3), why.push(`voice ${Math.round(voice * 100)}% of the time`);
    if (pref === "calm") score += (1 - eMean) * 3;
    if (pref === "build") score += Math.max(0, arc) * 8;
    if (pref === "intro") score += st < 1 ? 4 : 0;
    cands.push({
      start: Math.round(st * 100) / 100,
      end: Math.round(end * 100) / 100,
      score: Math.round(score * 100) / 100,
      why,
      sections: secs.map((x) => `${x.label} ${x.start.toFixed(1)}–${x.end.toFixed(1)}`),
      lyrics: lines.filter((ln) => ln.start >= st - 0.2 && ln.end <= end + 0.2).map((ln) => ln.text),
    });
  }
  cands.sort((a, b) => b.score - a.score);
  // Distinct options (not three versions of the same window).
  const out: Pick[] = [];
  for (const c of cands) if (out.every((p) => Math.abs(p.start - c.start) > L / 3)) out.push(c);
  return out.slice(0, o.count ?? 3);
}
