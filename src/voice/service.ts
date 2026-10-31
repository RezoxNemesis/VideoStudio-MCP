/** VolTune inside VideoStudio: script → natural speech (Kokoro, on device) → WAV + lip-sync envelope. */
import { SpeechEngine, isSupported, type Progress } from "./engine/client";
import { buildPlan } from "./engine/plan";
import { MemoryClipStore } from "./engine/store";
import { buildWavBlob } from "./engine/export";
import { SAMPLE_RATE, mouthEnvelope, toFloat32 } from "./engine/audio";
import { DEFAULT_VOICE } from "./engine/voices";
import { PRESETS } from "./engine/voicebank";
import { VOICES } from "./engine/voices";
import { loadCustoms } from "./voicelab";

export interface SpeakRequest {
  text: string;
  mode?: "speak" | "dialogue";
  voice?: string;
  speed?: number;
  pauseScale?: number;
  cast?: Record<string, string>;
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
  list(): { id: string; name: string; kind: "stock" | "preset" | "custom"; detail: string }[] {
    return [
      ...VOICES.map((v) => ({ id: v.id, name: v.name, kind: "stock" as const, detail: `${v.gender} · ${v.accent} · ${v.tone}` })),
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
      customs: loadCustoms(),
    });
    if (items.length === 0) throw new Error("Nothing to speak in that script.");
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

  cancel(): void {
    this.engine.cancel();
  }
}
