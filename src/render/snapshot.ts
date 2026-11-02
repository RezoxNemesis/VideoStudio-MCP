import { preloadModels } from "../anim/three3d";
import { getBlob } from "../core/storage";
/** Small JPEG snapshots so an agent can see media, rendered frames and drawings before deciding edits. */
import { findAsset, findScene, type Project } from "../core/model";
import type { MediaPool } from "../media/library";
import { Compositor } from "./compositor";
import { buildLayers } from "./layers";
import { ctx2d, makeCanvas, preloadFills, renderScene } from "../anim/render";

type AnyCanvas = HTMLCanvasElement | OffscreenCanvas;

export async function toJpeg(c: AnyCanvas, quality = 0.72): Promise<string> {
  const blob = "convertToBlob" in c ? await c.convertToBlob({ type: "image/jpeg", quality }) : await new Promise<Blob>((r) => (c as HTMLCanvasElement).toBlob((b) => r(b!), "image/jpeg", quality));
  const buf = stripIcc(new Uint8Array(await blob.arrayBuffer()));
  let s = "";
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return `data:image/jpeg;base64,${btoa(s)}`;
}

/** Drop the embedded colour profile (APP2/ICC, ~0.6 KB) so snapshots sent to the agent stay small. */
export function stripIcc(b: Uint8Array): Uint8Array {
  if (b[0] !== 0xff || b[1] !== 0xd8) return b;
  const parts: Uint8Array[] = [b.subarray(0, 2)];
  let i = 2;
  while (i + 4 <= b.length && b[i] === 0xff) {
    const m = b[i + 1]!;
    if (m === 0xda) break; // start of scan: the rest is image data
    const len = (b[i + 2]! << 8) | b[i + 3]!;
    if (m !== 0xe2) parts.push(b.subarray(i, i + 2 + len));
    i += 2 + len;
  }
  parts.push(b.subarray(i));
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

function fit(w: number, h: number, max: number): [number, number] {
  const k = Math.min(1, max / Math.max(w, h));
  return [Math.max(2, Math.round(w * k)), Math.max(2, Math.round(h * k))];
}

/** Thumbnail of an image or a video frame. */
export async function assetThumb(p: Project, pool: MediaPool, assetId: string, t = 0, max = 320): Promise<{ jpeg: string; width: number; height: number }> {
  const a = findAsset(p, assetId);
  if (!a) throw new Error(`No asset ${assetId}`);
  if (a.kind === "audio") throw new Error("Audio has no picture.");
  let src: CanvasImageSource;
  let sw: number;
  let sh: number;
  if (a.kind === "image") {
    const bmp = await pool.image(a);
    src = bmp;
    sw = bmp.width;
    sh = bmp.height;
  } else {
    const f = await pool.framer(a, "thumb", max * 2);
    const wc = await f?.frameAt(Math.max(0, Math.min(a.duration - 0.05, t)));
    if (!wc) throw new Error("Could not decode a frame.");
    src = wc.canvas;
    sw = wc.canvas.width;
    sh = wc.canvas.height;
  }
  const [w, h] = fit(sw, sh, max);
  const c = makeCanvas(w, h);
  ctx2d(c).drawImage(src, 0, 0, w, h);
  return { jpeg: await toJpeg(c), width: a.width, height: a.height };
}

/** Grid of numbered thumbnails: one image to review many assets at once. */
export async function contactSheet(p: Project, pool: MediaPool, ids: string[], cell = 220, cols = 4): Promise<{ jpeg: string; order: string[] }> {
  const vis = ids.filter((id) => findAsset(p, id)?.kind !== "audio");
  const rows = Math.ceil(vis.length / cols) || 1;
  const ch = Math.round(cell * 1.6);
  const c = makeCanvas(cols * cell, rows * ch);
  const x = ctx2d(c);
  x.fillStyle = "#111";
  x.fillRect(0, 0, c.width, c.height);
  for (let i = 0; i < vis.length; i++) {
    const a = findAsset(p, vis[i]!)!;
    try {
      const src = a.kind === "image" ? await pool.image(a) : (await (await pool.framer(a, "thumb", 480))?.frameAt(Math.min(1, a.duration / 3)))?.canvas;
      if (!src) continue;
      const sw = (src as { width: number }).width;
      const sh = (src as { height: number }).height;
      const k = Math.min((cell - 8) / sw, (ch - 30) / sh);
      const gx = (i % cols) * cell;
      const gy = Math.floor(i / cols) * ch;
      x.drawImage(src as CanvasImageSource, gx + (cell - sw * k) / 2, gy + 4, sw * k, sh * k);
      x.fillStyle = "#ffd23f";
      x.font = "bold 18px sans-serif";
      x.fillText(String(i + 1), gx + 8, gy + ch - 8);
    } catch {
      /* skip undecodable */
    }
  }
  return { jpeg: await toJpeg(c, 0.68), order: vis };
}

/** The edit as it will render at time t (GPU composite), downscaled. */
export async function frameSnapshot(p: Project, pool: MediaPool, t: number, max = 360): Promise<{ jpeg: string; t: number }> {
  const [w, h] = fit(p.width, p.height, max);
  const canvas = new OffscreenCanvas(w, h);
  const comp = new Compositor(canvas);
  try {
    for (const s of p.scenes) await preloadFills(s), await preloadModels(s, (id) => getBlob(p.assets.find((a) => a.id === id)!.ref));
    const layers = await buildLayers(p, pool, t, { purpose: "export", wait: true, outW: w, outH: h });
    comp.render(layers, w, h, p.background);
    return { jpeg: await toJpeg(canvas), t };
  } finally {
    comp.dispose();
  }
}

/** The edit at time t at full project resolution, saved as a still image (PNG or JPEG). */
export async function stillImage(p: Project, pool: MediaPool, t: number, type: "png" | "jpeg" = "png"): Promise<Blob> {
  const canvas = new OffscreenCanvas(p.width, p.height);
  const comp = new Compositor(canvas);
  try {
    for (const s of p.scenes) await preloadFills(s), await preloadModels(s, (id) => getBlob(p.assets.find((a) => a.id === id)!.ref));
    const layers = await buildLayers(p, pool, t, { purpose: "export", wait: true, outW: p.width, outH: p.height });
    comp.render(layers, p.width, p.height, p.background);
    return await canvas.convertToBlob({ type: `image/${type}`, quality: 0.95 });
  } finally {
    comp.dispose();
  }
}

/** A drawing scene frame (with onion skin off). */
export async function sceneSnapshot(p: Project, pool: MediaPool, sceneId: string, frame: number, max = 360): Promise<{ jpeg: string }> {
  const s = findScene(p, sceneId);
  if (!s) throw new Error(`No scene ${sceneId}`);
  await preloadFills(s);
  await preloadModels(s, (id) => getBlob(p.assets.find((a) => a.id === id)!.ref));
  const k = Math.min(1, max / Math.max(s.width, s.height));
  const c = makeCanvas(Math.round(s.width * k), Math.round(s.height * k));
  renderScene(ctx2d(c), s, frame, { scale: k, images: (id) => pool.imageNow(id) });
  return { jpeg: await toJpeg(c) };
}

/** Render a scene frame as a PNG blob (transparent where the scene is), optionally cropped to a box in scene px. */
export async function scenePng(p: Project, pool: MediaPool, sceneId: string, frame: number, crop?: { x: number; y: number; w: number; h: number }, background = true): Promise<Blob> {
  const s = findScene(p, sceneId);
  if (!s) throw new Error(`No scene ${sceneId}`);
  await preloadFills(s);
  await preloadModels(s, (id) => getBlob(p.assets.find((a) => a.id === id)!.ref));
  const full = makeCanvas(s.width, s.height);
  renderScene(ctx2d(full), s, frame, { scale: 1, images: (id) => pool.imageNow(id), background });
  const box = crop ?? { x: 0, y: 0, w: s.width, h: s.height };
  const out = makeCanvas(Math.max(1, Math.round(box.w)), Math.max(1, Math.round(box.h)));
  ctx2d(out).drawImage(full as CanvasImageSource, box.x, box.y, box.w, box.h, 0, 0, out.width, out.height);
  if ("convertToBlob" in out) return await out.convertToBlob({ type: "image/png" });
  return await new Promise<Blob>((res, rej) => (out as HTMLCanvasElement).toBlob((b) => (b ? res(b) : rej(new Error("PNG failed"))), "image/png"));
}
