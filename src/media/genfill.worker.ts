/// <reference lib="webworker" />
/**
 * Smart fill (generative inpainting) on the phone: Stable Diffusion 1.5 Inpainting, run with ONNX Runtime on the
 * phone's GPU (WebGPU) — or, if the GPU path is missing, on the CPU (very slow). It can draw what was never in the
 * picture (an arm under a jacket, skin, cloth, background). Models are read from the app's own cache; nothing is
 * sent anywhere.
 */
import * as ort from "onnxruntime-web/webgpu";
import { AutoTokenizer } from "@huggingface/transformers";

ort.env.wasm.wasmPaths = `${self.location.origin}/ort/`;
ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.max(1, Math.min(4, (self.navigator.hardwareConcurrency ?? 4) - 1)) : 1;

export interface GenFiles {
  unet: string;
  text: string;
  vaeEnc: string;
  vaeDec: string;
}

type Msg =
  | { id: number; type: "probe" }
  | { id: number; type: "fill"; files: GenFiles; rgba: Uint8ClampedArray; mask: Uint8Array; size: number; prompt: string; negative: string; steps: number; guidance: number; seed: number };

const post = (m: unknown, t: Transferable[] = []) => (self as unknown as Worker).postMessage(m, t);

/* ------------------------------- fp16 helpers ------------------------------ */

const f32 = new Float32Array(1);
const u32 = new Uint32Array(f32.buffer);
function toHalf(v: number): number {
  f32[0] = v;
  const x = u32[0]!;
  const sign = (x >>> 16) & 0x8000;
  let e = ((x >>> 23) & 0xff) - 127 + 15;
  const m = x & 0x7fffff;
  if (e <= 0) return sign;
  if (e >= 31) return sign | 0x7c00;
  return sign | (e << 10) | (m >>> 13);
}
function fromHalf(h: number): number {
  const s = h & 0x8000 ? -1 : 1;
  const e = (h >>> 10) & 0x1f;
  const m = h & 0x3ff;
  if (e === 0) return s * m * 2 ** -24;
  if (e === 31) return m ? NaN : s * Infinity;
  return s * (1 + m / 1024) * 2 ** (e - 15);
}
function asF32(t: ort.Tensor): Float32Array {
  if (t.type === "float32") return t.data as Float32Array;
  const src = t.data as Uint16Array;
  const out = new Float32Array(src.length);
  for (let i = 0; i < src.length; i++) out[i] = fromHalf(src[i]!);
  return out;
}
function floatTensor(data: Float32Array, dims: number[], half: boolean): ort.Tensor {
  if (!half) return new ort.Tensor("float32", data, dims);
  const h = new Uint16Array(data.length);
  for (let i = 0; i < data.length; i++) h[i] = toHalf(data[i]!);
  return new ort.Tensor("float16", h, dims);
}

/* ------------------------- sessions with dtype probing ------------------------ */

interface Sess {
  s: ort.InferenceSession;
  half: boolean | null;
}
const sessions: Partial<Record<keyof GenFiles, Sess>> = {};
let provider: "webgpu" | "wasm" = "wasm";

async function hasWebGpu(): Promise<boolean> {
  const g = (self.navigator as Navigator & { gpu?: { requestAdapter(): Promise<unknown> } }).gpu;
  if (!g) return false;
  try {
    return !!(await g.requestAdapter());
  } catch {
    return false;
  }
}

async function open(kind: keyof GenFiles, url: string, note: (m: string) => void): Promise<Sess> {
  const have = sessions[kind];
  if (have) return have;
  note(`Loading ${kind === "unet" ? "painter" : kind === "text" ? "prompt reader" : "image codec"}…`);
  const res = await caches.match(url);
  if (!res) throw new Error(`Smart fill model missing (${kind}). Download it from the Smart fill screen first.`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  provider = (await hasWebGpu()) ? "webgpu" : "wasm";
  const s = await ort.InferenceSession.create(bytes, {
    executionProviders: provider === "webgpu" ? ["webgpu", "wasm"] : ["wasm"],
    graphOptimizationLevel: "all",
    enableMemPattern: false,
  });
  const out: Sess = { s, half: null };
  sessions[kind] = out;
  return out;
}

/** Run with float inputs given as f32; tries fp16 first (these models are fp16), then fp32, and remembers. */
async function runFloat(ss: Sess, build: (half: boolean) => Record<string, ort.Tensor>): Promise<ort.InferenceSession.ReturnType> {
  const tries = ss.half === null ? [true, false] : [ss.half];
  let last: unknown;
  for (const half of tries) {
    try {
      const r = await ss.s.run(build(half));
      ss.half = half;
      return r;
    } catch (e) {
      last = e;
      if (!/type|float16|float32|tensor\(/i.test(String(e))) throw e;
    }
  }
  throw last;
}

/* ---------------------------------- DDIM ---------------------------------- */

const alphasCumprod = (() => {
  const a = new Float64Array(1000);
  const b0 = Math.sqrt(0.00085),
    b1 = Math.sqrt(0.012);
  let prod = 1;
  for (let i = 0; i < 1000; i++) {
    const beta = (b0 + ((b1 - b0) * i) / 999) ** 2;
    prod *= 1 - beta;
    a[i] = prod;
  }
  return a;
})();

function rng(seed: number) {
  let s = seed >>> 0 || 1;
  const u = () => {
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    return ((s >>> 0) + 0.5) / 4294967296;
  };
  return () => Math.sqrt(-2 * Math.log(u())) * Math.cos(2 * Math.PI * u());
}

/* --------------------------------- pipeline -------------------------------- */

let tokenizer: Awaited<ReturnType<typeof AutoTokenizer.from_pretrained>> | null = null;

async function encodeText(files: GenFiles, texts: string[], note: (m: string) => void): Promise<Float32Array> {
  tokenizer ??= await AutoTokenizer.from_pretrained("Xenova/clip-vit-base-patch32");
  const te = await open("text", files.text, note);
  const all = new Float32Array(texts.length * 77 * 768);
  for (let k = 0; k < texts.length; k++) {
    const enc = (tokenizer as unknown as (t: string, o: Record<string, unknown>) => { input_ids: { data: BigInt64Array } })(texts[k]!, { padding: "max_length", max_length: 77, truncation: true });
    const ids = Array.from(enc.input_ids.data, (v) => Number(v));
    const name = te.s.inputNames[0]!;
    let r: ort.InferenceSession.ReturnType;
    try {
      r = await te.s.run({ [name]: new ort.Tensor("int32", Int32Array.from(ids), [1, 77]) });
    } catch {
      r = await te.s.run({ [name]: new ort.Tensor("int64", BigInt64Array.from(ids.map((v) => BigInt(v))), [1, 77]) });
    }
    const h = asF32(r[te.s.outputNames[0]!]!);
    all.set(h.subarray(0, 77 * 768), k * 77 * 768);
  }
  return all;
}

async function vaeEncode(files: GenFiles, img: Float32Array, S: number, note: (m: string) => void): Promise<Float32Array> {
  const ve = await open("vaeEnc", files.vaeEnc, note);
  const r = await runFloat(ve, (half) => ({ [ve.s.inputNames[0]!]: floatTensor(img, [1, 3, S, S], half) }));
  const lat = asF32(r[ve.s.outputNames[0]!]!);
  const out = new Float32Array(4 * (S / 8) * (S / 8));
  for (let i = 0; i < out.length; i++) out[i] = lat[i]! * 0.18215;
  return out;
}

async function vaeDecode(files: GenFiles, lat: Float32Array, S: number, note: (m: string) => void): Promise<Float32Array> {
  const vd = await open("vaeDec", files.vaeDec, note);
  const z = new Float32Array(lat.length);
  for (let i = 0; i < z.length; i++) z[i] = lat[i]! / 0.18215;
  const r = await runFloat(vd, (half) => ({ [vd.s.inputNames[0]!]: floatTensor(z, [1, 4, S / 8, S / 8], half) }));
  return asF32(r[vd.s.outputNames[0]!]!);
}

async function fill(m: Extract<Msg, { type: "fill" }>) {
  const note = (msg: string) => post({ id: m.id, progress: msg });
  const S = m.size,
    L = S / 8,
    P = S * S,
    LP = L * L;
  const img = new Float32Array(3 * P),
    masked = new Float32Array(3 * P);
  for (let i = 0; i < P; i++) {
    const hole = m.mask[i]! > 127;
    for (let c = 0; c < 3; c++) {
      const v = (m.rgba[i * 4 + c]! / 255) * 2 - 1;
      img[c * P + i] = v;
      masked[c * P + i] = hole ? 0 : v;
    }
  }
  // Latent mask: a latent cell is "to paint" if any pixel under it is.
  const lmask = new Float32Array(LP);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) if (m.mask[y * S + x]! > 127) lmask[(y >> 3) * L + (x >> 3)] = 1;

  note("Reading the prompt…");
  const emb = await encodeText(m.files, [m.negative, m.prompt], note);
  note("Looking at the picture…");
  const mlat = await vaeEncode(m.files, masked, S, note);

  const unet = await open("unet", m.files.unet, note);
  const names = unet.s.inputNames;
  const nSample = names.find((n) => /sample/i.test(n)) ?? names[0]!;
  const nTime = names.find((n) => /time/i.test(n)) ?? names[1]!;
  const nCtx = names.find((n) => /hidden|encoder|context/i.test(n)) ?? names[2]!;
  const steps = Math.max(4, Math.min(50, m.steps));
  const ratio = Math.floor(1000 / steps);
  const ts = Array.from({ length: steps }, (_, i) => (steps - 1 - i) * ratio + 1);
  const g = rng(m.seed);
  let lat = new Float32Array(4 * LP);
  for (let i = 0; i < lat.length; i++) lat[i] = g();
  let timeKind: "i64" | "f" | null = null;
  const t0 = Date.now();
  for (let k = 0; k < ts.length; k++) {
    const t = ts[k]!;
    const x = new Float32Array(2 * 9 * LP);
    for (let b = 0; b < 2; b++) {
      const o = b * 9 * LP;
      x.set(lat, o);
      x.set(lmask, o + 4 * LP);
      x.set(mlat, o + 5 * LP);
    }
    const build = (half: boolean) => {
      const tt =
        timeKind === "f"
          ? floatTensor(new Float32Array([t, t]), [2], half)
          : new ort.Tensor("int64", BigInt64Array.from([BigInt(t), BigInt(t)]), [2]);
      return { [nSample]: floatTensor(x, [2, 9, L, L], half), [nTime]: tt, [nCtx]: floatTensor(emb, [2, 77, 768], half) };
    };
    let r: ort.InferenceSession.ReturnType;
    try {
      r = await runFloat(unet, build);
      timeKind ??= "i64";
    } catch (e) {
      if (timeKind) throw e;
      timeKind = "f";
      r = await runFloat(unet, build);
    }
    const eps2 = asF32(r[unet.s.outputNames[0]!]!);
    const a = alphasCumprod[t]!,
      prevT = t - ratio,
      ap = prevT >= 0 ? alphasCumprod[prevT]! : alphasCumprod[0]!;
    const next = new Float32Array(lat.length);
    for (let i = 0; i < lat.length; i++) {
      const eps = eps2[i]! + m.guidance * (eps2[4 * LP + i]! - eps2[i]!);
      const x0 = (lat[i]! - Math.sqrt(1 - a) * eps) / Math.sqrt(a);
      next[i] = Math.sqrt(ap) * x0 + Math.sqrt(1 - ap) * eps;
    }
    lat = next;
    const per = (Date.now() - t0) / (k + 1) / 1000;
    note(`Painting ${k + 1}/${steps} · ~${Math.round(per * (steps - k - 1))} s left (${provider === "webgpu" ? "GPU" : "CPU"})`);
  }
  note("Finishing…");
  const out = await vaeDecode(m.files, lat, S, note);
  const rgba = new Uint8ClampedArray(P * 4);
  for (let i = 0; i < P; i++) {
    for (let c = 0; c < 3; c++) rgba[i * 4 + c] = ((out[c * P + i]! + 1) / 2) * 255;
    rgba[i * 4 + 3] = 255;
  }
  post({ id: m.id, ok: true, rgba, w: S, h: S, provider }, [rgba.buffer]);
}

self.onmessage = async (e: MessageEvent<Msg>) => {
  const m = e.data;
  try {
    if (m.type === "probe") {
      post({ id: m.id, ok: true, webgpu: await hasWebGpu() });
      return;
    }
    if (m.type === "fill") await fill(m);
  } catch (err) {
    post({ id: m.id, ok: false, error: (err as Error).message ?? String(err) });
  }
};
