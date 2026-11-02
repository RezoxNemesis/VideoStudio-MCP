/// <reference lib="webworker" />
/**
 * On-phone AI for clean-up: tap-to-select segmentation (SlimSAM) and AI inpainting (LaMa). Models download once
 * (through the phone's own downloader, cached) and run here, off the UI thread. Nothing leaves the phone.
 */
import { AutoProcessor, RawImage, SamModel, env } from "@huggingface/transformers";
import * as ort from "onnxruntime-web";

env.allowLocalModels = false;
env.backends.onnx.wasm!.wasmPaths = `${self.location.origin}/ort/`;
ort.env.wasm.wasmPaths = `${self.location.origin}/ort/`;
const threads = self.crossOriginIsolated ? Math.max(1, Math.min(4, (self.navigator.hardwareConcurrency ?? 4) - 1)) : 1;
env.backends.onnx.wasm!.numThreads = threads;
ort.env.wasm.numThreads = threads;

type Msg =
  | { id: number; type: "sam"; model: string; rgba: Uint8ClampedArray; w: number; h: number; points: [number, number, number][]; box?: [number, number, number, number] }
  | { id: number; type: "lama"; model: ArrayBuffer | null; rgba: Uint8ClampedArray; mask: Uint8Array; size: number };

let sam: { model: SamModel; processor: Awaited<ReturnType<typeof AutoProcessor.from_pretrained>>; id: string } | null = null;
let lama: ort.InferenceSession | null = null;

const post = (m: unknown, t: Transferable[] = []) => (self as unknown as Worker).postMessage(m, t);

self.onmessage = async (e: MessageEvent<Msg>) => {
  const m = e.data;
  try {
    if (m.type === "sam") {
      if (!sam || sam.id !== m.model) {
        const model = (await SamModel.from_pretrained(m.model, { dtype: "q8" })) as SamModel;
        const processor = await AutoProcessor.from_pretrained(m.model);
        sam = { model, processor, id: m.model };
      }
      const img = new RawImage(m.rgba, m.w, m.h, 4).rgb();
      const pts = m.points.filter((p) => p[2] >= 0);
      const opts: Record<string, unknown> = {};
      if (pts.length) {
        opts.input_points = [[pts.map((p) => [p[0], p[1]])]];
        opts.input_labels = [[pts.map((p) => p[2])]];
      }
      if (m.box) opts.input_boxes = [[m.box]];
      const proc = sam.processor as unknown as (i: RawImage, o: Record<string, unknown>) => Promise<Record<string, unknown>>;
      const inputs = await proc(img, opts);
      const out = (await (sam.model as unknown as (i: Record<string, unknown>) => Promise<Record<string, { data: Float32Array; dims: number[] }>>)(inputs))!;
      const masks = await (sam.processor as unknown as { post_process_masks: (...a: unknown[]) => Promise<{ data: Uint8Array; dims: number[] }[]> }).post_process_masks(out.pred_masks, inputs.original_sizes, inputs.reshaped_input_sizes);
      const scores = out.iou_scores!.data;
      let best = 0;
      for (let i = 1; i < scores.length; i++) if (scores[i]! > scores[best]!) best = i;
      const t = masks[0]!;
      const [, , H, W] = t.dims as [number, number, number, number];
      const mask = new Uint8Array(W * H);
      const off = best * W * H;
      for (let i = 0; i < W * H; i++) mask[i] = t.data[off + i] ? 255 : 0;
      post({ id: m.id, ok: true, mask, w: W, h: H, score: scores[best] }, [mask.buffer]);
      return;
    }
    if (m.type === "lama") {
      if (!lama) {
        if (!m.model) throw new Error("model bytes missing");
        lama = await ort.InferenceSession.create(new Uint8Array(m.model), { executionProviders: ["wasm"], graphOptimizationLevel: "all" });
      }
      const S = m.size;
      const img = new Float32Array(3 * S * S);
      const msk = new Float32Array(S * S);
      for (let i = 0; i < S * S; i++) {
        img[i] = m.rgba[i * 4]! / 255;
        img[S * S + i] = m.rgba[i * 4 + 1]! / 255;
        img[2 * S * S + i] = m.rgba[i * 4 + 2]! / 255;
        msk[i] = m.mask[i]! > 127 ? 1 : 0;
      }
      const names = lama.inputNames;
      const maskName = names.find((n) => /mask/i.test(n)) ?? names[1]!;
      const imgName = names.find((n) => n !== maskName) ?? names[0]!;
      const feeds: Record<string, ort.Tensor> = {
        [imgName]: new ort.Tensor("float32", img, [1, 3, S, S]),
        [maskName]: new ort.Tensor("float32", msk, [1, 1, S, S]),
      };
      const res = await lama.run(feeds);
      const o = res[lama.outputNames[0]!]!;
      const od = o.data as Float32Array | Uint8Array;
      let max = 0;
      for (let i = 0; i < Math.min(od.length, 20000); i++) if (od[i]! > max) max = od[i]!;
      const k = max > 2 ? 1 : 255;
      const outW = o.dims[3]!,
        outH = o.dims[2]!;
      const rgba = new Uint8ClampedArray(outW * outH * 4);
      const plane = outW * outH;
      for (let i = 0; i < plane; i++) {
        rgba[i * 4] = od[i]! * k;
        rgba[i * 4 + 1] = od[plane + i]! * k;
        rgba[i * 4 + 2] = od[2 * plane + i]! * k;
        rgba[i * 4 + 3] = 255;
      }
      post({ id: m.id, ok: true, rgba, w: outW, h: outH }, [rgba.buffer]);
    }
  } catch (err) {
    post({ id: m.id, ok: false, error: (err as Error).message ?? String(err) });
  }
};
