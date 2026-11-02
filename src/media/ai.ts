/**
 * Main-thread side of the on-phone clean-up AI: tap-to-select (SlimSAM segmentation) and AI fill (LaMa inpainting).
 * Model files come down once through the phone's native downloader and are cached; inference runs in a worker.
 */
import { downloadBlob, hasNativeStorage } from "../native/storage";
import { ctx2d, makeCanvas } from "../anim/render";

export const SAM_MODEL = "Xenova/slimsam-77-uniform";
const SAM_FILES = ["config.json", "preprocessor_config.json", "onnx/vision_encoder_quantized.onnx", "onnx/prompt_encoder_mask_decoder_quantized.onnx"];
export const DEPTH_MODEL = "onnx-community/depth-anything-v2-small";
const DEPTH_FILES = ["config.json", "preprocessor_config.json", "onnx/model_quantized.onnx"];
export const LAMA_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx";
const LAMA_SIZE = 512;

let worker: Worker | null = null;
let nextId = 1;
const pending = new Map<number, { resolve: (v: Record<string, unknown>) => void; reject: (e: Error) => void }>();

function call(msg: Record<string, unknown>, transfer: Transferable[] = []): Promise<Record<string, unknown>> {
  if (!worker) {
    worker = new Worker(new URL("./ai.worker.ts", import.meta.url), { type: "module" });
    worker.onmessage = (e: MessageEvent<{ id: number; ok: boolean; error?: string }>) => {
      const p = pending.get(e.data.id);
      if (!p) return;
      pending.delete(e.data.id);
      if (e.data.ok) p.resolve(e.data as unknown as Record<string, unknown>);
      else p.reject(new Error(e.data.error ?? "AI step failed"));
    };
  }
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    worker!.postMessage({ ...msg, id }, transfer);
  });
}

/** Put a file into a Cache API store using the native downloader (robust for big files). */
async function cached(store: string, url: string, onProgress?: (msg: string) => void): Promise<Response | undefined> {
  if (typeof caches === "undefined") return undefined;
  const cache = await caches.open(store);
  const hit = await cache.match(url);
  if (hit) return hit;
  if (!hasNativeStorage()) return undefined;
  onProgress?.(`Downloading ${url.split("/").pop()} (once)…`);
  let blob: Blob | null = null;
  let last = "";
  for (let attempt = 0; attempt < 3 && !blob; attempt++) {
    try {
      // Native first (streams big files to disk); plain fetch as a fallback (model hosts allow it).
      blob = attempt < 2 ? await downloadBlob(url) : await fetch(url).then((r) => (r.ok ? r.blob() : Promise.reject(new Error(`HTTP ${r.status}`))));
    } catch (e) {
      last = (e as Error).message ?? String(e);
      if (/HTTP 404/.test(last)) return undefined;
    }
  }
  if (!blob) throw new Error(`Download failed for ${url.split("/").pop()} (${last}). Check the connection and try again.`);
  if (!blob) return undefined;
  const res = new Response(blob, { headers: { "content-type": "application/octet-stream", "content-length": String(blob.size) } });
  await cache.put(url, res.clone());
  return res;
}

let samReady: Promise<void> | null = null;
function prepareSam(onProgress?: (m: string) => void): Promise<void> {
  samReady ??= (async () => {
    for (const f of SAM_FILES) await cached("transformers-cache", `https://huggingface.co/${SAM_MODEL}/resolve/main/${f}`, onProgress);
  })().catch((e) => {
    samReady = null;
    throw e;
  });
  return samReady;
}

let lamaBytes: ArrayBuffer | null = null;
let lamaLoaded = false;
async function lamaModel(onProgress?: (m: string) => void): Promise<ArrayBuffer | null> {
  if (lamaLoaded) return null; // already inside the worker
  if (lamaBytes) return lamaBytes;
  const res = (await cached("vs-models", LAMA_URL, onProgress)) ?? (await fetch(LAMA_URL));
  if (!res.ok) throw new Error(`The AI fill model could not be downloaded (${res.status}).`);
  lamaBytes = await res.arrayBuffer();
  return lamaBytes;
}

function rgbaOf(src: CanvasImageSource, w: number, h: number): Uint8ClampedArray {
  const c = makeCanvas(w, h);
  const x = ctx2d(c);
  x.drawImage(src, 0, 0, w, h);
  return x.getImageData(0, 0, w, h).data;
}

/**
 * Tap-to-select: the object under the points (x, y in source pixels; label 1 = include, 0 = exclude), as a mask
 * the size of the source (255 = selected). Works on a ≤1024 px copy for speed, then scales up.
 */
export async function segment(src: CanvasImageSource, W: number, H: number, points: [number, number, number][], box?: [number, number, number, number], onProgress?: (m: string) => void): Promise<{ mask: Uint8Array; w: number; h: number; score: number }> {
  await prepareSam(onProgress);
  onProgress?.("Selecting…");
  const k = Math.min(1, 1024 / Math.max(W, H));
  const w = Math.round(W * k),
    h = Math.round(H * k);
  const rgba = rgbaOf(src, w, h);
  const r = await call({ type: "sam", model: SAM_MODEL, rgba, w, h, points: points.map(([x, y, l]) => [x * k, y * k, l]), box: box?.map((v) => v * k) }, [rgba.buffer]);
  const mw = r.w as number,
    mh = r.h as number;
  const small = r.mask as Uint8Array;
  // Scale to source size (nearest; edges are softened at use time).
  const mask = new Uint8Array(W * H);
  for (let y = 0; y < H; y++) {
    const sy = Math.min(mh - 1, Math.floor((y / H) * mh));
    for (let x = 0; x < W; x++) mask[y * W + x] = small[sy * mw + Math.min(mw - 1, Math.floor((x / W) * mw))]!;
  }
  return { mask, w: W, h: H, score: r.score as number };
}

/** Grow a mask by r pixels (so edges and shadows of the removed thing go too). */
export function dilate(mask: Uint8Array, W: number, H: number, r: number): Uint8Array {
  if (r <= 0) return mask;
  const tmp = new Uint8Array(W * H);
  const out = new Uint8Array(W * H);
  for (let y = 0; y < H; y++) {
    let last = -1e9;
    for (let x = 0; x < W; x++) {
      if (mask[y * W + x]) last = x;
      if (x - last <= r) tmp[y * W + x] = 255;
    }
    last = 1e9;
    for (let x = W - 1; x >= 0; x--) {
      if (mask[y * W + x]) last = x;
      if (last - x <= r) tmp[y * W + x] = 255;
    }
  }
  for (let x = 0; x < W; x++) {
    let last = -1e9;
    for (let y = 0; y < H; y++) {
      if (tmp[y * W + x]) last = y;
      if (y - last <= r) out[y * W + x] = 255;
    }
    last = 1e9;
    for (let y = H - 1; y >= 0; y--) {
      if (tmp[y * W + x]) last = y;
      if (last - y <= r) out[y * W + x] = 255;
    }
  }
  return out;
}

export function maskBounds(mask: Uint8Array, W: number, H: number): { x0: number; y0: number; x1: number; y1: number } | null {
  let x0 = W,
    y0 = H,
    x1 = -1,
    y1 = -1;
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++)
      if (mask[y * W + x]) {
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        if (y > y1) y1 = y;
      }
  return x1 < 0 ? null : { x0, y0, x1: x1 + 1, y1: y1 + 1 };
}

/**
 * AI fill: rebuild the masked area with LaMa. Works on a square crop around the mask (context matters), resized
 * to 512, and pastes only the masked pixels back with a soft edge.
 */
export async function aiFill(ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, W: number, H: number, mask: Uint8Array, onProgress?: (m: string) => void): Promise<void> {
  const b = maskBounds(mask, W, H);
  if (!b) return;
  const bw = b.x1 - b.x0,
    bh = b.y1 - b.y0;
  let side = Math.min(Math.max(W, H), Math.max(256, Math.round(Math.max(bw, bh) * 1.9)));
  side = Math.min(side, Math.max(W, H));
  const cx = (b.x0 + b.x1) / 2,
    cy = (b.y0 + b.y1) / 2;
  const sx = Math.round(Math.max(0, Math.min(W - Math.min(side, W), cx - side / 2)));
  const sy = Math.round(Math.max(0, Math.min(H - Math.min(side, H), cy - side / 2)));
  const cw = Math.min(side, W),
    ch = Math.min(side, H);
  const S = LAMA_SIZE;
  const crop = makeCanvas(S, S);
  const cx2 = ctx2d(crop);
  cx2.drawImage(ctx.canvas as CanvasImageSource, sx, sy, cw, ch, 0, 0, S, S);
  const rgba = cx2.getImageData(0, 0, S, S).data;
  const m = new Uint8Array(S * S);
  for (let y = 0; y < S; y++)
    for (let x = 0; x < S; x++) {
      const gx = Math.min(W - 1, sx + Math.floor((x / S) * cw)),
        gy = Math.min(H - 1, sy + Math.floor((y / S) * ch));
      m[y * S + x] = mask[gy * W + gx]!;
    }
  const model = await lamaModel(onProgress);
  onProgress?.("AI fill…");
  const r = await call({ type: "lama", model, rgba, mask: m, size: S }, [rgba.buffer]);
  lamaLoaded = true;
  lamaBytes = null;
  const out = new ImageData(new Uint8ClampedArray(r.rgba as Uint8ClampedArray), r.w as number, r.h as number);
  const oc = makeCanvas(out.width, out.height);
  ctx2d(oc).putImageData(out, 0, 0);
  // Back to crop size, then blend only where the mask is (with a 2 px soft edge).
  const back = makeCanvas(cw, ch);
  const bx = ctx2d(back);
  bx.drawImage(oc as CanvasImageSource, 0, 0, cw, ch);
  const filled = bx.getImageData(0, 0, cw, ch).data;
  const dst = ctx.getImageData(sx, sy, cw, ch);
  const soft = dilate(mask, W, H, 2);
  for (let y = 0; y < ch; y++)
    for (let x = 0; x < cw; x++) {
      const gi = (sy + y) * W + (sx + x);
      const k = mask[gi] ? 1 : soft[gi] ? 0.5 : 0;
      if (!k) continue;
      const i = (y * cw + x) * 4;
      for (let c = 0; c < 3; c++) dst.data[i + c] = dst.data[i + c]! * (1 - k) + filled[i + c]! * k;
    }
  ctx.putImageData(dst, sx, sy);
}

/** Mask as a compact PNG data URL (white = remove) for storing on a clip. */
export async function maskToPng(mask: Uint8Array, W: number, H: number, maxSide = 1024): Promise<string> {
  const k = Math.min(1, maxSide / Math.max(W, H));
  const w = Math.max(1, Math.round(W * k)),
    h = Math.max(1, Math.round(H * k));
  const full = makeCanvas(W, H);
  const fx = ctx2d(full);
  const img = fx.createImageData(W, H);
  for (let i = 0; i < W * H; i++) {
    const v = mask[i]!;
    img.data[i * 4] = img.data[i * 4 + 1] = img.data[i * 4 + 2] = 255;
    img.data[i * 4 + 3] = v;
  }
  fx.putImageData(img, 0, 0);
  const c = makeCanvas(w, h);
  ctx2d(c).drawImage(full as CanvasImageSource, 0, 0, w, h);
  const blob = "convertToBlob" in c ? await c.convertToBlob({ type: "image/png" }) : await new Promise<Blob>((r) => (c as HTMLCanvasElement).toBlob((b) => r(b!), "image/png"));
  const buf = new Uint8Array(await blob.arrayBuffer());
  let s = "";
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return `data:image/png;base64,${btoa(s)}`;
}

const decoded = new Map<string, Promise<{ data: Uint8ClampedArray; w: number; h: number }>>();
/** Decode a stored mask PNG (alpha channel) once. */
export function decodeMask(png: string): Promise<{ data: Uint8ClampedArray; w: number; h: number }> {
  let p = decoded.get(png);
  if (!p) {
    p = (async () => {
      const blob = await (await fetch(png)).blob();
      const bmp = await createImageBitmap(blob);
      const c = makeCanvas(bmp.width, bmp.height);
      const x = ctx2d(c);
      x.drawImage(bmp, 0, 0);
      const d = x.getImageData(0, 0, bmp.width, bmp.height).data;
      const a = new Uint8ClampedArray(bmp.width * bmp.height);
      for (let i = 0; i < a.length; i++) a[i] = d[i * 4 + 3]!;
      bmp.close();
      return { data: a, w: c.width, h: c.height };
    })();
    decoded.set(png, p);
  }
  return p;
}

/** A stored mask resampled to W×H (255 = remove). */
export async function maskAtSize(png: string, W: number, H: number): Promise<Uint8Array> {
  const m = await decodeMask(png);
  const out = new Uint8Array(W * H);
  for (let y = 0; y < H; y++) {
    const sy = Math.min(m.h - 1, Math.floor((y / H) * m.h));
    for (let x = 0; x < W; x++) out[y * W + x] = m.data[sy * m.w + Math.min(m.w - 1, Math.floor((x / W) * m.w))]! > 127 ? 255 : 0;
  }
  return out;
}

/**
 * Depth of a picture (Depth Anything V2 small, on the phone): returns a grey map, white = near. Used for 2.5D
 * parallax, depth of field and wind on near things.
 */
export async function estimateDepth(src: CanvasImageSource, W: number, H: number, onProgress?: (m: string) => void): Promise<{ data: Uint8Array; w: number; h: number }> {
  for (const f of DEPTH_FILES) await cached("transformers-cache", `https://huggingface.co/${DEPTH_MODEL}/resolve/main/${f}`, onProgress);
  const k = Math.min(1, 768 / Math.max(W, H));
  const w = Math.round(W * k),
    h = Math.round(H * k);
  const c = makeCanvas(w, h);
  const x = ctx2d(c);
  x.drawImage(src, 0, 0, w, h);
  const rgba = x.getImageData(0, 0, w, h).data;
  onProgress?.("Measuring depth (on-phone AI)…");
  const r = await call({ type: "depth", model: DEPTH_MODEL, rgba, w, h }, [rgba.buffer]);
  return { data: r.data as Uint8Array, w: r.w as number, h: r.h as number };
}

/** A grey depth map as a PNG data URL. */
export async function depthToPng(d: { data: Uint8Array; w: number; h: number }): Promise<string> {
  const c = makeCanvas(d.w, d.h);
  const x = ctx2d(c);
  const im = x.createImageData(d.w, d.h);
  for (let i = 0; i < d.w * d.h; i++) {
    const v = d.data[i]!;
    im.data[i * 4] = im.data[i * 4 + 1] = im.data[i * 4 + 2] = v;
    im.data[i * 4 + 3] = 255;
  }
  x.putImageData(im, 0, 0);
  const blob = "convertToBlob" in c ? await (c as OffscreenCanvas).convertToBlob({ type: "image/png" }) : await new Promise<Blob>((ok) => (c as HTMLCanvasElement).toBlob((b) => ok(b!), "image/png"));
  const buf = new Uint8Array(await blob.arrayBuffer());
  let s = "";
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return `data:image/png;base64,${btoa(s)}`;
}

/* ------------------------------ subject matting ----------------------------- */

export const MATTE_MODEL = "Xenova/modnet";
const MATTE_FILES = ["config.json", "preprocessor_config.json", "onnx/model.onnx", "onnx/model_quantized.onnx"];
let matteReady: Promise<void> | null = null;
let gpuOk: boolean | null = null;

/**
 * Person matte for one picture (MODNet, on the phone; GPU when available): alpha 0..255 at the model's size.
 */
export async function personMatte(src: CanvasImageSource, W: number, H: number, onProgress?: (m: string) => void, maxSide = 512): Promise<{ alpha: Uint8Array; w: number; h: number; device?: string }> {
  matteReady ??= (async () => {
    for (const f of MATTE_FILES) await cached("transformers-cache", `https://huggingface.co/${MATTE_MODEL}/resolve/main/${f}`, onProgress);
  })().catch((e) => {
    matteReady = null;
    throw e;
  });
  await matteReady;
  if (gpuOk === null) {
    const g = (navigator as Navigator & { gpu?: { requestAdapter(): Promise<unknown> } }).gpu;
    try {
      gpuOk = !!(g && (await g.requestAdapter()));
    } catch {
      gpuOk = false;
    }
  }
  const k = maxSide / Math.max(W, H);
  const w = Math.max(64, Math.round((W * k) / 32) * 32),
    h = Math.max(64, Math.round((H * k) / 32) * 32);
  const c = makeCanvas(w, h);
  const x = ctx2d(c);
  x.drawImage(src, 0, 0, w, h);
  const rgba = x.getImageData(0, 0, w, h).data;
  const r = await call({ type: "matte", model: MATTE_MODEL, device: gpuOk ? "webgpu" : "wasm", rgba, w, h }, [rgba.buffer]);
  return { alpha: r.alpha as Uint8Array, w: r.w as number, h: r.h as number, device: r.device as string };
}
