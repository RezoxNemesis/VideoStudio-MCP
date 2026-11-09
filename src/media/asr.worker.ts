/// <reference lib="webworker" />
/** Lyrics / speech transcription on the phone with Whisper (ONNX, runs locally; the model downloads once). */
import { env, pipeline } from "@huggingface/transformers";

env.allowLocalModels = false;
env.backends.onnx.wasm!.wasmPaths = `${self.location.origin}/ort/`;
if (self.crossOriginIsolated) env.backends.onnx.wasm!.numThreads = Math.max(1, Math.min(4, (self.navigator.hardwareConcurrency ?? 4) - 1));

type Asr = (audio: Float32Array, o: Record<string, unknown>) => Promise<{ text: string; chunks?: { text: string; timestamp: [number, number | null] }[] }>;
const loaded = new Map<string, Promise<Asr>>();

self.onmessage = async (e: MessageEvent<{ id: number; audio: Float32Array; model: string; language?: string; task?: string; device?: "webgpu" | "wasm" }>) => {
  const { id, audio, model, language, task } = e.data;
  // Desktop: big models on the GPU (WebGPU, fp16 encoder + 4-bit decoder); phones: the 8-bit CPU path.
  const gpu = e.data.device === "webgpu" && typeof (self.navigator as Navigator & { gpu?: unknown }).gpu !== "undefined";
  // Half-precision shaders are not exposed on every GPU/driver (e.g. NVIDIA on Linux via Vulkan): use fp32 then.
  let f16 = false;
  if (gpu) {
    try {
      const ad = await (self.navigator as Navigator & { gpu: { requestAdapter(o?: unknown): Promise<{ features: Set<string> } | null> } }).gpu.requestAdapter({ powerPreference: "high-performance" });
      f16 = !!ad?.features.has("shader-f16");
    } catch {
      f16 = false;
    }
  }
  const key = `${model}|${gpu ? (f16 ? "gpu16" : "gpu32") : "cpu"}`;
  const post = (m: unknown) => (self as unknown as Worker).postMessage(m);
  try {
    if (!loaded.has(key)) {
      loaded.set(
        key,
        pipeline("automatic-speech-recognition", model, {
          ...(gpu ? { device: "webgpu", dtype: { encoder_model: f16 ? "fp16" : "fp32", decoder_model_merged: "q4" } } : { dtype: "q8" }),
          progress_callback: (p: { status: string; progress?: number; file?: string }) => {
            if (p.status === "progress") post({ id, type: "progress", stage: "model", progress: Math.round(p.progress ?? 0), file: p.file });
          },
        }) as unknown as Promise<Asr>,
      );
    }
    const asr = await loaded.get(key)!;
    post({ id, type: "progress", stage: "listening", progress: 0 });
    // Chunking only for long audio: a short passage chunked to 30 s can decode to nothing and throw.
    const long = audio.length > 29 * 16000;
    const out = await asr(audio, {
      ...(long ? { chunk_length_s: 30, stride_length_s: 5 } : {}),
      return_timestamps: true,
      ...(language ? { language } : {}),
      task: task ?? "transcribe",
    });
    post({ id, type: "done", text: out.text, chunks: out.chunks ?? [] });
  } catch (err) {
    loaded.delete(key);
    post({ id, type: "error", error: (err as Error).message ?? String(err) });
  }
};
