/** Main-thread side of the on-phone transcriber (lyrics and dialogue with timings). */
import { downloadBlob, hasNativeStorage } from "../native/storage";

const MODEL_FILES = ["config.json", "generation_config.json", "preprocessor_config.json", "tokenizer.json", "tokenizer_config.json", "onnx/encoder_model_quantized.onnx", "onnx/decoder_model_merged_quantized.onnx"];

/**
 * Big model files are fetched by the phone's native downloader (streamed to disk, retried) and placed in the
 * same Cache API store the speech model reads from, so a flaky WebView download can't break transcription.
 */
export async function prefetchModel(model: string, onProgress?: (done: number, total: number) => void): Promise<void> {
  if (typeof caches === "undefined") return;
  const cache = await caches.open("transformers-cache");
  let done = 0;
  for (const f of MODEL_FILES) {
    const url = `https://huggingface.co/${model}/resolve/main/${f}`;
    onProgress?.(done, MODEL_FILES.length);
    if (await cache.match(url)) {
      done++;
      continue;
    }
    if (!hasNativeStorage()) {
      done++;
      continue; // the browser path downloads inside the worker
    }
    let blob: Blob | null = null;
    for (let attempt = 0; attempt < 3 && !blob; attempt++) {
      try {
        blob = await downloadBlob(url);
      } catch (e) {
        if (/HTTP 404/.test(String(e))) break; // optional file
        if (attempt === 2) throw new Error(`Could not download the lyrics model (${f}). Check the internet connection and try again.`);
      }
    }
    if (blob) await cache.put(url, new Response(blob, { headers: { "content-type": f.endsWith(".json") ? "application/json" : "application/octet-stream", "content-length": String(blob.size) } }));
    done++;
  }
  onProgress?.(done, MODEL_FILES.length);
}
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
  await prefetchModel(ASR_MODELS[o.model ?? "base"], (d, n) => o.onProgress?.("model", Math.round((d / n) * 100)));
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
