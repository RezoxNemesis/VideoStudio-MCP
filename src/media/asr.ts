/** Main-thread side of the on-phone transcriber (lyrics and dialogue with timings). */
export interface LyricLine {
  start: number;
  end: number;
  text: string;
}

export const ASR_MODELS = { base: "onnx-community/whisper-base", small: "onnx-community/whisper-small", tiny: "onnx-community/whisper-tiny" } as const;

let worker: Worker | null = null;
let nextId = 1;

/** Mono 16 kHz copy of an AudioBuffer (what Whisper expects). */
export async function to16k(buf: AudioBuffer, from = 0, to = buf.duration): Promise<Float32Array> {
  const len = Math.max(1, Math.ceil((to - from) * 16000));
  const ctx = new OfflineAudioContext(1, len, 16000);
  const src = ctx.createBufferSource();
  src.buffer = buf;
  src.connect(ctx.destination);
  src.start(0, from, to - from);
  return (await ctx.startRendering()).getChannelData(0);
}

export async function transcribe(
  audio: Float32Array,
  o: { model?: keyof typeof ASR_MODELS; language?: string; translate?: boolean; onProgress?: (stage: string, pct: number) => void } = {},
): Promise<{ text: string; lines: LyricLine[] }> {
  worker ??= new Worker(new URL("./asr.worker.ts", import.meta.url), { type: "module" });
  const id = nextId++;
  const w = worker;
  return await new Promise((resolve, reject) => {
    const onMsg = (e: MessageEvent<{ id: number; type: string; text?: string; chunks?: { text: string; timestamp: [number, number | null] }[]; error?: string; stage?: string; progress?: number }>) => {
      const m = e.data;
      if (m.id !== id) return;
      if (m.type === "progress") o.onProgress?.(m.stage ?? "", m.progress ?? 0);
      else {
        w.removeEventListener("message", onMsg);
        if (m.type === "error") reject(new Error(m.error));
        else {
          const lines = (m.chunks ?? [])
            .map((c) => ({ start: r2(c.timestamp[0] ?? 0), end: r2(c.timestamp[1] ?? (c.timestamp[0] ?? 0) + 2), text: c.text.trim() }))
            .filter((l) => l.text && !/^\[.*\]$|^\(.*\)$|^♪+$/.test(l.text));
          resolve({ text: (m.text ?? "").trim(), lines });
        }
      }
    };
    w.addEventListener("message", onMsg);
    w.postMessage({ id, audio, model: ASR_MODELS[o.model ?? "base"], language: o.language, task: o.translate ? "translate" : "transcribe" }, [audio.buffer]);
  });
}

const r2 = (x: number) => Math.round(x * 100) / 100;
