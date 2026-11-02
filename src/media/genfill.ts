/**
 * Smart fill: generative inpainting on the phone (Stable Diffusion 1.5 Inpainting, fp16 ONNX). Draws what was never
 * in the picture — skin, an arm, cloth, background — guided by an optional prompt. Models (~2.1 GB) download once
 * through the phone's own downloader into the app's cache; inference runs in a worker on the GPU (WebGPU) when the
 * phone has it.
 */
import { downloadBlob, hasNativeStorage } from "../native/storage";
import { ctx2d, makeCanvas } from "../anim/render";
import { dilate, maskBounds } from "./ai";
import type { GenFiles } from "./genfill.worker";

const HF = "https://huggingface.co";
export const GEN_FILES: GenFiles = {
  unet: `${HF}/sona-forge/sd15-inpainting-fp16/resolve/main/unet/model.onnx`,
  text: `${HF}/sona-forge/sd15-base-fp16/resolve/main/text_encoder/model.onnx`,
  vaeEnc: `${HF}/sona-forge/sd15-base-fp16/resolve/main/vae_encoder/model.onnx`,
  vaeDec: `${HF}/sona-forge/sd15-base-fp16/resolve/main/vae_decoder/model.onnx`,
};
const SIZES: Record<keyof GenFiles, number> = { unet: 1720173976, text: 246476214, vaeEnc: 68430493, vaeDec: 99094195 };
export const GEN_TOTAL_MB = Math.round(Object.values(SIZES).reduce((a, b) => a + b, 0) / 1048576);
const STORE = "vs-genfill";
const DEFAULT_NEG = "blurry, deformed, bad anatomy, extra limbs, extra fingers, missing fingers, disfigured, watermark, text, logo, low quality, jpeg artifacts";

let worker: Worker | null = null;
let nextId = 1;
const pending = new Map<number, { resolve: (v: Record<string, unknown>) => void; reject: (e: Error) => void; progress?: (m: string) => void }>();

function call(msg: Record<string, unknown>, transfer: Transferable[] = [], progress?: (m: string) => void): Promise<Record<string, unknown>> {
  if (!worker) {
    worker = new Worker(new URL("./genfill.worker.ts", import.meta.url), { type: "module" });
    worker.onmessage = (e: MessageEvent<{ id: number; ok?: boolean; error?: string; progress?: string }>) => {
      const p = pending.get(e.data.id);
      if (!p) return;
      if (e.data.progress) return p.progress?.(e.data.progress);
      pending.delete(e.data.id);
      if (e.data.ok) p.resolve(e.data as unknown as Record<string, unknown>);
      else p.reject(new Error(e.data.error ?? "Smart fill failed"));
    };
  }
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject, progress });
    worker!.postMessage({ ...msg, id }, transfer);
  });
}

/** Which model files are already on the phone. */
export async function genStatus(): Promise<{ ready: boolean; missingMB: number; totalMB: number }> {
  let missing = 0;
  if (typeof caches === "undefined") return { ready: false, missingMB: GEN_TOTAL_MB, totalMB: GEN_TOTAL_MB };
  const c = await caches.open(STORE);
  for (const k of Object.keys(GEN_FILES) as (keyof GenFiles)[]) if (!(await c.match(GEN_FILES[k]))) missing += SIZES[k];
  return { ready: missing === 0, missingMB: Math.round(missing / 1048576), totalMB: GEN_TOTAL_MB };
}

/** Download the Smart fill models once (resumable per file). */
export async function genDownload(onProgress?: (m: string) => void): Promise<void> {
  const c = await caches.open(STORE);
  const keys = (Object.keys(GEN_FILES) as (keyof GenFiles)[]).sort((a, b) => SIZES[a] - SIZES[b]);
  for (const k of keys) {
    const url = GEN_FILES[k];
    if (await c.match(url)) continue;
    onProgress?.(`Downloading Smart fill ${k === "unet" ? "painter" : k} (${Math.round(SIZES[k] / 1048576)} MB, once)…`);
    let blob: Blob | null = null;
    let last = "";
    for (let attempt = 0; attempt < 3 && !blob; attempt++) {
      try {
        blob = hasNativeStorage() ? await downloadBlob(url) : await fetch(url).then((r) => (r.ok ? r.blob() : Promise.reject(new Error(`HTTP ${r.status}`))));
      } catch (e) {
        last = (e as Error).message;
      }
    }
    if (!blob) throw new Error(`Smart fill download failed (${last}).`);
    if (blob.size < SIZES[k] * 0.99) throw new Error(`Smart fill download was cut short (${k}). Try again on Wi-Fi.`);
    await c.put(url, new Response(blob, { headers: { "content-type": "application/octet-stream", "content-length": String(blob.size) } }));
  }
}

export async function genRemove(): Promise<void> {
  if (typeof caches !== "undefined") await caches.delete(STORE);
}

export interface SmartFillOptions {
  prompt?: string;
  negative?: string;
  steps?: number;
  guidance?: number;
  seed?: number;
}

/**
 * Repaint the masked area of the canvas. Works on a square crop around the mask (with surrounding context so the
 * new pixels match), at 512×512, and blends only the masked pixels back with a soft edge.
 */
export async function smartFill(
  ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D,
  W: number,
  H: number,
  mask: Uint8Array,
  o: SmartFillOptions = {},
  onProgress?: (m: string) => void,
): Promise<{ provider: string; seconds: number }> {
  const t0 = performance.now();
  const b = maskBounds(mask, W, H);
  if (!b) throw new Error("Nothing selected.");
  const st = await genStatus();
  if (!st.ready) await genDownload(onProgress);
  const bw = b.x1 - b.x0,
    bh = b.y1 - b.y0;
  // Context: the crop is ~1.6× the selection, at least 384 px, clamped to the frame.
  let side = Math.max(384, Math.round(Math.max(bw, bh) * 1.6));
  side = Math.min(side, Math.max(W, H));
  const cw = Math.min(side, W),
    ch = Math.min(side, H);
  const cx = (b.x0 + b.x1) / 2,
    cy = (b.y0 + b.y1) / 2;
  const sx = Math.round(Math.max(0, Math.min(W - cw, cx - cw / 2)));
  const sy = Math.round(Math.max(0, Math.min(H - ch, cy - ch / 2)));
  const S = 512;
  const crop = makeCanvas(S, S);
  const c2 = ctx2d(crop);
  c2.drawImage(ctx.canvas as CanvasImageSource, sx, sy, cw, ch, 0, 0, S, S);
  const rgba = c2.getImageData(0, 0, S, S).data;
  // The model gets a slightly grown mask (so seams fall on new pixels), we blend back with the soft one.
  const grown = dilate(mask, W, H, Math.max(2, Math.round(Math.max(cw, ch) / 128)));
  const m = new Uint8Array(S * S);
  for (let y = 0; y < S; y++)
    for (let x = 0; x < S; x++) {
      const gx = Math.min(W - 1, sx + Math.floor((x / S) * cw)),
        gy = Math.min(H - 1, sy + Math.floor((y / S) * ch));
      m[y * S + x] = grown[gy * W + gx]!;
    }
  const r = await call(
    {
      type: "fill",
      files: GEN_FILES,
      rgba,
      mask: m,
      size: S,
      prompt: (o.prompt?.trim() || "seamless continuation of the surrounding picture, same style, same lighting, correct anatomy") + ", high quality",
      negative: o.negative ?? DEFAULT_NEG,
      steps: o.steps ?? 20,
      guidance: o.guidance ?? 7.5,
      seed: o.seed ?? Math.floor(Math.random() * 1e9),
    },
    [rgba.buffer],
    onProgress,
  );
  const out = new ImageData(new Uint8ClampedArray(r.rgba as Uint8ClampedArray), r.w as number, r.h as number);
  const oc = makeCanvas(out.width, out.height);
  ctx2d(oc).putImageData(out, 0, 0);
  const back = makeCanvas(cw, ch);
  const bx = ctx2d(back);
  bx.drawImage(oc as CanvasImageSource, 0, 0, cw, ch);
  const filled = bx.getImageData(0, 0, cw, ch).data;
  const dst = ctx.getImageData(sx, sy, cw, ch);
  // Soft edge: full inside the grown mask, fading over a few pixels outside it.
  const soft = dilate(grown, W, H, 3);
  for (let y = 0; y < ch; y++)
    for (let x = 0; x < cw; x++) {
      const gi = (sy + y) * W + (sx + x);
      const k = grown[gi] ? 1 : soft[gi] ? 0.45 : 0;
      if (!k) continue;
      const i = (y * cw + x) * 4;
      for (let c = 0; c < 3; c++) dst.data[i + c] = dst.data[i + c]! * (1 - k) + filled[i + c]! * k;
    }
  ctx.putImageData(dst, sx, sy);
  return { provider: String(r.provider), seconds: Math.round((performance.now() - t0) / 100) / 10 };
}
