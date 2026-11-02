/**
 * Subject mattes for whole clips: the on-phone person matte is computed once per video (at a modest size and frame
 * rate), packed, compressed and kept with the app's data. Rendering then reads the right frame instantly, so subject
 * effects (cut-outs, text behind people, textures inside silhouettes, neon outlines, double exposure, halos) play in
 * real time and export frame-exact.
 */
import { kvGet, kvSet } from "../core/storage";
import type { Asset } from "../core/model";
import type { MediaPool } from "./library";
import { personMatte } from "./ai";

export interface MatteTrack {
  w: number;
  h: number;
  fps: number;
  n: number;
  /** n frames of w*h alpha, back to back. */
  data: Uint8Array;
}

const mem = new Map<string, MatteTrack | null>();
const loading = new Map<string, Promise<MatteTrack | null>>();

async function pack(t: MatteTrack): Promise<Blob> {
  const head = new TextEncoder().encode(JSON.stringify({ w: t.w, h: t.h, fps: t.fps, n: t.n }));
  const raw = new Blob([new Uint32Array([head.length]), head, t.data as BlobPart]);
  if (typeof CompressionStream === "undefined") return raw;
  return await new Response(raw.stream().pipeThrough(new CompressionStream("deflate"))).blob();
}
async function unpack(b: Blob): Promise<MatteTrack> {
  let buf: ArrayBuffer;
  try {
    buf = await new Response(b.stream().pipeThrough(new DecompressionStream("deflate"))).arrayBuffer();
  } catch {
    buf = await b.arrayBuffer();
  }
  const hl = new Uint32Array(buf.slice(0, 4))[0]!;
  const head = JSON.parse(new TextDecoder().decode(new Uint8Array(buf, 4, hl))) as { w: number; h: number; fps: number; n: number };
  return { ...head, data: new Uint8Array(buf, 4 + hl) };
}

/** The matte of an asset if it has been computed (loads it once). */
export function matteNow(assetId: string): MatteTrack | null | undefined {
  if (mem.has(assetId)) return mem.get(assetId);
  if (!loading.has(assetId))
    loading.set(
      assetId,
      (async () => {
        const b = await kvGet<Blob>(`matte:${assetId}`).catch(() => undefined);
        const t = b ? await unpack(b) : null;
        mem.set(assetId, t);
        return t;
      })(),
    );
  return undefined;
}
export async function matteLoad(assetId: string): Promise<MatteTrack | null> {
  const now = matteNow(assetId);
  if (now !== undefined) return now;
  return (await loading.get(assetId)) ?? null;
}

/** Alpha for source time t, blended between the two nearest stored frames (smooth motion). */
export function matteAt(m: MatteTrack, t: number, out?: Uint8Array): Uint8Array {
  const P = m.w * m.h;
  if (m.n <= 1) return m.data.subarray(0, P);
  const f = Math.max(0, Math.min(m.n - 1, t * m.fps));
  const i = Math.floor(f),
    j = Math.min(m.n - 1, i + 1),
    k = f - i;
  if (k < 0.02) return m.data.subarray(i * P, i * P + P);
  const a = m.data.subarray(i * P, i * P + P),
    b = m.data.subarray(j * P, j * P + P);
  const o = out && out.length === P ? out : new Uint8Array(P);
  for (let q = 0; q < P; q++) o[q] = a[q]! + (b[q]! - a[q]!) * k;
  return o;
}

/**
 * Compute and store the person matte for a photo or video. Video frames are sampled at `fps` (default 15);
 * playback interpolates between them.
 */
export async function computeMatte(pool: MediaPool, a: Asset, o: { fps?: number; size?: number; onProgress?: (m: string) => void } = {}): Promise<MatteTrack> {
  if (a.kind === "image") {
    const bmp = (await pool.image(a)) as ImageBitmap;
    const r = await personMatte(bmp, bmp.width, bmp.height, o.onProgress);
    const t: MatteTrack = { w: r.w, h: r.h, fps: 1, n: 1, data: r.alpha };
    await store(a.id, t);
    return t;
  }
  if (a.kind !== "video") throw new Error("Pick a photo or a video.");
  const fps = Math.max(4, Math.min(30, o.fps ?? 12));
  const f = await pool.framer(a, "matte", 720);
  if (!f) throw new Error("This video cannot be decoded.");
  const n = Math.max(1, Math.ceil(a.duration * fps));
  let w = 0,
    h = 0;
  let data: Uint8Array | null = null;
  const t0 = performance.now();
  for (let i = 0; i < n; i++) {
    const fr = await f.frameAt(Math.min(a.duration - 0.01, i / fps));
    if (!fr) continue;
    // Videos: a smaller matte is plenty (it is upscaled smoothly) and roughly twice as fast.
    const r = await personMatte(fr.canvas as CanvasImageSource, fr.canvas.width, fr.canvas.height, o.onProgress, o.size ?? 384);
    if (!data) {
      w = r.w;
      h = r.h;
      data = new Uint8Array(w * h * n);
    }
    if (r.w === w && r.h === h) data.set(r.alpha, i * w * h);
    const per = (performance.now() - t0) / (i + 1) / 1000;
    o.onProgress?.(`Finding the person ${i + 1}/${n} · ~${Math.round(per * (n - i - 1))} s left (${r.device === "webgpu" ? "GPU" : "CPU"})`);
  }
  if (!data) throw new Error("No frames could be read.");
  const t: MatteTrack = { w, h, fps, n, data };
  await store(a.id, t);
  return t;
}

async function store(id: string, t: MatteTrack) {
  mem.set(id, t);
  loading.delete(id);
  await kvSet(`matte:${id}`, await pack(t));
}
export async function storeMatte(id: string, t: MatteTrack): Promise<MatteTrack> {
  await store(id, t);
  return t;
}
