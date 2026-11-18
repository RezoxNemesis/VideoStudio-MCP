/** VolTune inside VideoStudio: script → natural speech (Kokoro, on device) → WAV + lip-sync envelope. */
import { SpeechEngine, isSupported, type Progress } from "./engine/client";
import { buildPlan, type PlanItem } from "./engine/plan";
import { MemoryClipStore } from "./engine/store";
import { buildWavBlob } from "./engine/export";
import { SAMPLE_RATE, encodeWav, mouthEnvelope, toFloat32 } from "./engine/audio";
import { DEFAULT_VOICE } from "./engine/voices";
import { PRESETS, resolveVoice } from "./engine/voicebank";
import { studioSay } from "./studio_client";
import type { Tone } from "./engine/open";
import { VOICES } from "./engine/voices";
import { loadCustoms } from "./voicelab";

export interface SpeakRequest {
  text: string;
  mode?: "speak" | "dialogue";
  voice?: string;
  speed?: number;
  pauseScale?: number;
  cast?: Record<string, string>;
  /** Language of the text: "de", "ru", "es", "fr", "it", "pt" … (voice.languages). Empty: worked out from the voice and the letters. */
  lang?: string;
}

export interface SpeakResult {
  wav: Blob;
  seconds: number;
  mouth: number[];
  cast: Record<string, string>;
  lines: { speaker?: string; text: string; start: number; end: number }[];
}

export class VoiceService {
  readonly engine = new SpeechEngine();
  private busy: Promise<unknown> = Promise.resolve();
  onProgress: (p: Progress) => void = () => {};

  constructor() {
    this.engine.onProgress = (p) => this.onProgress(p);
  }

  get supported(): boolean {
    return isSupported();
  }

  get device(): string | null {
    return this.engine.device;
  }

  /** Voices an agent or the menu can use: stock ids, presets, and the person's own Voice Lab voices. */
  list(): { id: string; name: string; kind: "stock" | "preset" | "custom"; detail: string; lang?: string }[] {
    return [
      ...VOICES.map((v) => ({ id: v.id, name: v.name, kind: "stock" as const, detail: `${v.gender} · ${v.accent} · ${v.tone}`, lang: v.lang })),
      ...PRESETS.map((v) => ({ id: v.id, name: v.name, kind: "preset" as const, detail: v.tone })),
      ...loadCustoms().map((v) => ({ id: v.id, name: v.name, kind: "custom" as const, detail: v.tone })),
    ];
  }

  /** Speak one request at a time (the engine is single-job). */
  speak(req: SpeakRequest, onClip?: (done: number, total: number) => void): Promise<SpeakResult> {
    const run = this.busy.then(() => this.run(req, onClip));
    this.busy = run.catch(() => {});
    return run;
  }

  private async run(req: SpeakRequest, onClip?: (done: number, total: number) => void): Promise<SpeakResult> {
    if (!req.text.trim()) throw new Error("Write something to speak.");
    const mode = req.mode ?? (/^\s*(\[[^\]]+\]|[A-Za-z][\w .'-]{0,30}(\([^)]*\))?:)/m.test(req.text) ? "dialogue" : "speak");
    const { items, cast } = buildPlan(req.text, {
      mode,
      speed: req.speed ?? 1,
      pauseScale: req.pauseScale ?? 1,
      voice: req.voice ?? DEFAULT_VOICE,
      cast: req.cast ?? {},
      lang: req.lang,
      customs: loadCustoms(),
    });
    if (items.length === 0) throw new Error("Nothing to speak in that script.");
    // On the desktop the studio voice acts every line (VolTune Studio): the script's directions become its tone
    const acted = await this.studioScript(items, req.lang, onClip);
    if (acted) return { ...acted, cast };
    const store = new MemoryClipStore();
    const jobId = `vs_${Date.now()}`;
    await store.startJob({ id: jobId, planHash: jobId, total: items.length, createdAt: Date.now() });
    await new Promise<void>((resolve, reject) => {
      void this.engine.generate(items, { store, jobId }, {
        onClip: (_m, done, total) => onClip?.(done, total),
        onDone: resolve,
        onCancel: () => reject(new Error("Voice generation cancelled.")),
        onError: (m) => reject(new Error(m)),
      });
    });
    const metas = await store.metas(jobId);
    const wav = await buildWavBlob(store, jobId, metas);
    const pcm = toFloat32(new Int16Array((await wav.arrayBuffer()).slice(44)));
    const lines = metas.map((m, i) => ({
      speaker: items[i]?.speaker,
      text: items[i]?.text ?? "",
      start: m.startSample / SAMPLE_RATE,
      end: (m.startSample + m.samples) / SAMPLE_RATE,
    }));
    return { wav, seconds: pcm.length / SAMPLE_RATE, mouth: mouthEnvelope(pcm, 30), cast, lines };
  }

  /**
   * A script said by the studio voice, line by line, each line's directions (mood, louder/softer, whispered, shouted,
   * slower, higher…) as its tone; null when the studio cannot say one of its lines here (then VolTune says it all).
   */
  private async studioScript(items: PlanItem[], lang: string | undefined, onClip?: (done: number, total: number) => void): Promise<Omit<SpeakResult, "cast"> | null> {
    const parts: Float32Array[] = [];
    for (const [i, it] of items.entries()) {
      const own = resolveVoice(it.voice, loadCustoms());
      const tone: Tone = {
        ...(it.mood ? { mood: it.mood, moodAmount: it.moodAmount } : {}),
        rate: it.speed,
        // the delivery's own pitch (the voice's recipe pitch is the voice's: the studio keeps it)
        ...(it.pitch !== undefined && Math.abs(it.pitch - own.pitch) >= 0.25 ? { pitch: it.pitch - own.pitch } : {}),
        energy: deliveryEnergy(it.gain, it.soft, it.edge),
      };
      const r = await studioSay(it.text, it.voice, tone, it.lang ?? lang);
      if (!r) return null;
      // the level its tone asks for (hushed lines quieter, shouted ones fuller), then the pause after it
      let peak = 0;
      for (const v of r.pcm) peak = Math.max(peak, Math.abs(v));
      const k = peak > 0 ? Math.min(3, r.peak / peak) : 1;
      parts.push(r.pcm.map((v) => v * k), new Float32Array(Math.round((it.pauseMs / 1000) * SAMPLE_RATE)));
      onClip?.(i + 1, items.length);
    }
    const pcm = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
    const lines: SpeakResult["lines"] = [];
    let o = 0;
    for (const [j, p] of parts.entries()) {
      if (j % 2 === 0) lines.push({ speaker: items[j / 2]?.speaker, text: items[j / 2]?.text ?? "", start: o / SAMPLE_RATE, end: (o + p.length) / SAMPLE_RATE });
      pcm.set(p, o);
      o += p.length;
    }
    return { wav: new Blob([encodeWav(pcm)], { type: "audio/wav" }), seconds: pcm.length / SAMPLE_RATE, mouth: mouthEnvelope(pcm, 30), lines };
  }

  cancel(): void {
    this.engine.cancel();
  }
}

/** A delivery's colour as the studio tone's energy (−1 hushed … 1 forceful): gain in octaves, whisper down, shout up. */
export function deliveryEnergy(gain = 1, soft = 0, edge = 0): number {
  const e = Math.log2(Math.max(0.1, gain)) * 1.4 - soft * 0.6 + edge * 0.5;
  return Math.round(Math.max(-1, Math.min(1, e)) * 100) / 100;
}
