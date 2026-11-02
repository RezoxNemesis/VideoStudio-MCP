/// <reference lib="webworker" />
/** Lyrics / speech transcription on the phone with Whisper (ONNX, runs locally; the model downloads once). */
import { env, pipeline } from "@huggingface/transformers";

env.backends.onnx.wasm!.wasmPaths = `${self.location.origin}/ort/`;
if (self.crossOriginIsolated) env.backends.onnx.wasm!.numThreads = Math.max(1, Math.min(4, (self.navigator.hardwareConcurrency ?? 4) - 1));

type Asr = (audio: Float32Array, o: Record<string, unknown>) => Promise<{ text: string; chunks?: { text: string; timestamp: [number, number | null] }[] }>;
const loaded = new Map<string, Promise<Asr>>();

self.onmessage = async (e: MessageEvent<{ id: number; audio: Float32Array; model: string; language?: string; task?: string }>) => {
  const { id, audio, model, language, task } = e.data;
  const post = (m: unknown) => (self as unknown as Worker).postMessage(m);
  try {
    if (!loaded.has(model)) {
      loaded.set(
        model,
        pipeline("automatic-speech-recognition", model, {
          dtype: "q8",
          progress_callback: (p: { status: string; progress?: number; file?: string }) => {
            if (p.status === "progress") post({ id, type: "progress", stage: "model", progress: Math.round(p.progress ?? 0), file: p.file });
          },
        }) as unknown as Promise<Asr>,
      );
    }
    const asr = await loaded.get(model)!;
    post({ id, type: "progress", stage: "listening", progress: 0 });
    const out = await asr(audio, {
      chunk_length_s: 30,
      stride_length_s: 5,
      return_timestamps: true,
      ...(language ? { language } : {}),
      task: task ?? "transcribe",
    });
    post({ id, type: "done", text: out.text, chunks: out.chunks ?? [] });
  } catch (err) {
    loaded.delete(model);
    post({ id, type: "error", error: (err as Error).message ?? String(err) });
  }
};
