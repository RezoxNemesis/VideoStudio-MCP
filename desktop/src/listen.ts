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
  lyrics?: { language?: string; model: string; lines: LyricLine[] } | { error: string };
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
    const model = o.model ?? "turbo";
    try {
      s.setStatus(`Listening to ${name}: lyrics (Whisper ${model} on the GPU — the model downloads once)…`);
      const raw = await vsd.voice16k(path);
      const audio = new Float32Array(raw.buffer.slice(raw.byteOffset, raw.byteOffset + raw.byteLength));
      let r;
      try {
        r = await transcribe(audio, { model, language: o.language, device: "webgpu", onProgress: (st, pct) => s.setStatus(`Lyrics: ${st} ${pct}%`) });
      } catch (gpuErr) {
        // No usable GPU path: the CPU with a smaller model still works.
        s.setStatus("Lyrics: GPU unavailable, using the CPU model…");
        r = await transcribe(new Float32Array(raw.buffer.slice(raw.byteOffset, raw.byteOffset + raw.byteLength)), { model: "small", language: o.language, onProgress: (st, pct) => s.setStatus(`Lyrics (CPU): ${st} ${pct}%`) });
        void gpuErr;
      }
      out.lyrics = { language: o.language, model, lines: snapLines(r.lines, vocals) };
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
