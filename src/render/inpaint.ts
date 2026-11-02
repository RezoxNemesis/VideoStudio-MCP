/**
 * Object / logo removal for video and photos. Each region is filled so the frame keeps its full size and the
 * clip keeps its full length (no crop, no gap):
 *  - "clone": copies texture from a nearby patch and blends it seamlessly (boundary differences are spread
 *    through the patch, a fast Poisson-style blend), so grain and detail continue through the hole;
 *  - "fill": smooth fill from the surrounding pixels (pull-push pyramid) plus matched grain;
 *  - "auto": clone when a good source patch exists, else fill;
 *  - "blur" / "pixelate": classic hide.
 * Regions can move over time (keyframed x/y) and be limited to a time range.
 */
import { valueAt } from "../core/keyframes";
import type { Keyframe } from "../core/model";

export type RemoveMode = "auto" | "ai" | "clone" | "fill" | "blur" | "pixelate";

export interface RemoveRegion {
  id: string;
  /** Centre and size as fractions of the source frame. */
  x: number;
  y: number;
  w: number;
  h: number;
  shape: "rect" | "ellipse";
  mode: RemoveMode;
  /** Soft edge, fraction of the region size (0..0.5). */
  feather: number;
  /** Active between these source seconds (time inside the media file). */
  from?: number;
  to?: number;
  /** Moving objects: keyframed centre (source seconds). */
  xKeys?: Keyframe[];
  yKeys?: Keyframe[];
  /** Clone source offset as a fraction of the frame (chosen automatically when missing). */
  offset?: [number, number];
  /** Exact shape from tap-to-select or the brush: PNG data URL over the whole source frame (alpha = remove). */
  mask?: string;
  /** Runtime only: the mask resampled to the working crop (not stored). */
  maskLocal?: { w: number; h: number; data: Uint8Array };
}

export function activeRegions(regions: RemoveRegion[] | undefined, t: number): RemoveRegion[] {
  if (!regions?.length) return [];
  return regions
    .filter((r) => (r.from === undefined || t >= r.from - 1e-6) && (r.to === undefined || t <= r.to + 1e-6))
    .map((r) => ({ ...r, x: valueAt(r.xKeys, t, r.x), y: valueAt(r.yKeys, t, r.y) }));
}

/* ------------------------------ pull-push fill ----------------------------- */

/** Fill unknown pixels (w = 0) from known ones, smoothly, using a weighted image pyramid. In place on rgb. */
export function pullPush(rgb: Float32Array, wt: Float32Array, W: number, H: number): void {
  if (W <= 1 && H <= 1) return;
  const w2 = Math.max(1, Math.ceil(W / 2));
  const h2 = Math.max(1, Math.ceil(H / 2));
  const crgb = new Float32Array(w2 * h2 * 3);
  const cw = new Float32Array(w2 * h2);
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++) {
      const i = y * W + x;
      const k = wt[i]!;
      if (k <= 0) continue;
      const j = (y >> 1) * w2 + (x >> 1);
      cw[j]! += k;
      crgb[j * 3]! += rgb[i * 3]! * k;
      crgb[j * 3 + 1]! += rgb[i * 3 + 1]! * k;
      crgb[j * 3 + 2]! += rgb[i * 3 + 2]! * k;
    }
  for (let j = 0; j < w2 * h2; j++) {
    const k = cw[j]!;
    if (k > 0) {
      crgb[j * 3]! /= k;
      crgb[j * 3 + 1]! /= k;
      crgb[j * 3 + 2]! /= k;
      cw[j] = Math.min(1, k);
    }
  }
  const anyMissing = cw.some((k) => k < 1);
  if (anyMissing && (w2 > 1 || h2 > 1)) pullPush(crgb, cw, w2, h2);
  // Push: blend the coarse level into pixels that are (partly) unknown, bilinearly.
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++) {
      const i = y * W + x;
      const k = Math.min(1, wt[i]!);
      if (k >= 1) continue;
      const fx = Math.min(w2 - 1, Math.max(0, (x - 0.5) / 2));
      const fy = Math.min(h2 - 1, Math.max(0, (y - 0.5) / 2));
      const x0 = Math.floor(fx),
        y0 = Math.floor(fy);
      const x1 = Math.min(w2 - 1, x0 + 1),
        y1 = Math.min(h2 - 1, y0 + 1);
      const ax = fx - x0,
        ay = fy - y0;
      for (let c = 0; c < 3; c++) {
        const v =
          crgb[(y0 * w2 + x0) * 3 + c]! * (1 - ax) * (1 - ay) +
          crgb[(y0 * w2 + x1) * 3 + c]! * ax * (1 - ay) +
          crgb[(y1 * w2 + x0) * 3 + c]! * (1 - ax) * ay +
          crgb[(y1 * w2 + x1) * 3 + c]! * ax * ay;
        rgb[i * 3 + c] = rgb[i * 3 + c]! * k + v * (1 - k);
      }
      wt[i] = 1;
    }
}

/* ------------------------------ the operation ------------------------------ */

function hash(n: number): number {
  const s = Math.sin(n * 127.1 + 311.7) * 43758.5453;
  return s - Math.floor(s);
}

/** Mask value (1 = remove) for a pixel at (px, py) in frame pixels. */
function maskAt(r: RemoveRegion, px: number, py: number, FW: number, FH: number): number {
  if (r.maskLocal) {
    const x = Math.floor(px),
      y = Math.floor(py);
    if (x < 0 || y < 0 || x >= r.maskLocal.w || y >= r.maskLocal.h) return 0;
    return r.maskLocal.data[y * r.maskLocal.w + x]! / 255;
  }
  const cx = r.x * FW,
    cy = r.y * FH;
  const hw = (r.w * FW) / 2,
    hh = (r.h * FH) / 2;
  const f = Math.max(0.001, r.feather);
  if (r.shape === "ellipse") {
    const d = Math.hypot((px - cx) / hw, (py - cy) / hh);
    return Math.max(0, Math.min(1, (1 + f - d) / (2 * f)));
  }
  const dx = Math.abs(px - cx) / hw,
    dy = Math.abs(py - cy) / hh;
  const d = Math.max(dx, dy);
  return Math.max(0, Math.min(1, (1 + f - d) / (2 * f)));
}

export interface Box {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

export function regionBox(r: RemoveRegion, FW: number, FH: number, pad = 0): Box {
  const hw = (r.w * FW) / 2 * (1 + r.feather) + pad,
    hh = (r.h * FH) / 2 * (1 + r.feather) + pad;
  return {
    x0: Math.max(0, Math.floor(r.x * FW - hw)),
    y0: Math.max(0, Math.floor(r.y * FH - hh)),
    x1: Math.min(FW, Math.ceil(r.x * FW + hw)),
    y1: Math.min(FH, Math.ceil(r.y * FH + hh)),
  };
}

/** Score candidate clone offsets: how well the ring around the source patch matches the ring around the hole. */
export function chooseOffset(img: ImageData, r: RemoveRegion): [number, number] | null {
  const FW = img.width,
    FH = img.height;
  const d = img.data;
  const b = regionBox(r, FW, FH, Math.max(3, Math.round(Math.min(FW, FH) * 0.01)));
  const bw = b.x1 - b.x0,
    bh = b.y1 - b.y0;
  const cands: [number, number][] = [];
  // 16 directions × 4 distances: more chances to find texture that really continues through the hole.
  for (const k of [1.05, 1.4, 1.9, 2.5])
    for (let i = 0; i < 16; i++) {
      const a = (i / 16) * Math.PI * 2;
      const ux = Math.cos(a),
        uy = Math.sin(a);
      const sc = 1 / Math.max(Math.abs(ux), Math.abs(uy));
      cands.push([Math.round(ux * sc * bw * k), Math.round(uy * sc * bh * k)]);
    }
  let best: [number, number] | null = null;
  let bestScore = Infinity;
  const step = Math.max(1, Math.round(Math.max(bw, bh) / 40));
  for (const [ox, oy] of cands) {
    if (b.x0 + ox < 0 || b.y0 + oy < 0 || b.x1 + ox > FW || b.y1 + oy > FH) continue;
    // The source patch must not overlap the hole itself.
    let score = 0,
      n = 0;
    for (let y = b.y0; y < b.y1; y += step)
      for (let x = b.x0; x < b.x1; x += step) {
        if (maskAt(r, x, y, FW, FH) > 0) continue; // compare only the ring (known pixels)
        const i = (y * FW + x) * 4,
          j = ((y + oy) * FW + (x + ox)) * 4;
        for (let c = 0; c < 3; c++) score += (d[i + c]! - d[j + c]!) ** 2;
        n++;
      }
    if (!n) continue;
    score /= n;
    // Prefer closer sources slightly.
    score *= 1 + 0.15 * Math.hypot(ox / bw, oy / bh);
    if (score < bestScore) {
      bestScore = score;
      best = [ox / FW, oy / FH];
    }
  }
  return bestScore < 60 * 60 * 3 ? best : null;
}

/** Remove one region from a frame (in place). Returns the clone offset used (to keep it steady over time). */
export function removeRegion(img: ImageData, r: RemoveRegion, seed = 1): [number, number] | null {
  const FW = img.width,
    FH = img.height;
  const d = img.data;
  const b = regionBox(r, FW, FH, 2);
  const bw = b.x1 - b.x0,
    bh = b.y1 - b.y0;
  if (bw < 2 || bh < 2) return null;
  const m = new Float32Array(bw * bh);
  for (let y = 0; y < bh; y++) for (let x = 0; x < bw; x++) m[y * bw + x] = maskAt(r, b.x0 + x + 0.5, b.y0 + y + 0.5, FW, FH);
  const get = (x: number, y: number, c: number) => d[(y * FW + x) * 4 + c]!;
  const put = (x: number, y: number, c: number, v: number, k: number) => {
    const i = (y * FW + x) * 4 + c;
    d[i] = d[i]! * (1 - k) + Math.max(0, Math.min(255, v)) * k;
  };
  let mode = r.mode;
  let off: [number, number] | null = null;
  if (mode === "auto" || mode === "clone") {
    off = r.offset ?? chooseOffset(img, r);
    if (!off) mode = "fill";
    else mode = "clone";
  }
  if (mode === "blur" || mode === "pixelate") {
    const src = new Float32Array(bw * bh * 3);
    for (let y = 0; y < bh; y++) for (let x = 0; x < bw; x++) for (let c = 0; c < 3; c++) src[(y * bw + x) * 3 + c] = get(b.x0 + x, b.y0 + y, c);
    const blk = Math.max(4, Math.round(Math.min(bw, bh) / 6));
    for (let y = 0; y < bh; y++)
      for (let x = 0; x < bw; x++) {
        const k = m[y * bw + x]!;
        if (k <= 0) continue;
        for (let c = 0; c < 3; c++) {
          let v: number;
          if (mode === "pixelate") {
            const px = Math.min(bw - 1, Math.floor(x / blk) * blk + (blk >> 1)),
              py = Math.min(bh - 1, Math.floor(y / blk) * blk + (blk >> 1));
            v = src[(py * bw + px) * 3 + c]!;
          } else {
            let s = 0,
              n = 0;
            const R = blk;
            for (let yy = Math.max(0, y - R); yy <= Math.min(bh - 1, y + R); yy += 2)
              for (let xx = Math.max(0, x - R); xx <= Math.min(bw - 1, x + R); xx += 2) {
                s += src[(yy * bw + xx) * 3 + c]!;
                n++;
              }
            v = s / n;
          }
          put(b.x0 + x, b.y0 + y, c, v, k);
        }
      }
    return null;
  }
  // Known-pixel weights: 1 outside the hole, 0 inside.
  const wt = new Float32Array(bw * bh);
  for (let i = 0; i < bw * bh; i++) wt[i] = m[i]! > 0.001 ? 0 : 1;
  const rgb = new Float32Array(bw * bh * 3);
  if (mode === "clone" && off) {
    // Difference (target - source) known on the ring, spread inward, added to the source patch.
    const ox = Math.round(off[0] * FW),
      oy = Math.round(off[1] * FH);
    for (let y = 0; y < bh; y++)
      for (let x = 0; x < bw; x++) {
        const sx = Math.min(FW - 1, Math.max(0, b.x0 + x + ox)),
          sy = Math.min(FH - 1, Math.max(0, b.y0 + y + oy));
        for (let c = 0; c < 3; c++) rgb[(y * bw + x) * 3 + c] = wt[y * bw + x] ? get(b.x0 + x, b.y0 + y, c) - get(sx, sy, c) : 0;
      }
    pullPush(rgb, wt, bw, bh);
    for (let y = 0; y < bh; y++)
      for (let x = 0; x < bw; x++) {
        const k = m[y * bw + x]!;
        if (k <= 0) continue;
        const sx = Math.min(FW - 1, Math.max(0, b.x0 + x + ox)),
          sy = Math.min(FH - 1, Math.max(0, b.y0 + y + oy));
        for (let c = 0; c < 3; c++) put(b.x0 + x, b.y0 + y, c, get(sx, sy, c) + rgb[(y * bw + x) * 3 + c]!, k);
      }
    return off;
  }
  // Smooth fill + grain matched to the surrounding texture.
  let gs = 0,
    gn = 0;
  for (let y = 1; y < bh - 1; y++)
    for (let x = 1; x < bw - 1; x++) {
      if (!wt[y * bw + x] || !wt[y * bw + x + 1]) continue;
      const a = get(b.x0 + x, b.y0 + y, 1),
        c2 = get(b.x0 + x + 1, b.y0 + y, 1);
      gs += (a - c2) ** 2;
      gn++;
    }
  const grain = gn ? Math.min(18, Math.sqrt(gs / gn / 2)) : 0;
  for (let y = 0; y < bh; y++) for (let x = 0; x < bw; x++) for (let c = 0; c < 3; c++) rgb[(y * bw + x) * 3 + c] = wt[y * bw + x] ? get(b.x0 + x, b.y0 + y, c) : 0;
  pullPush(rgb, wt, bw, bh);
  for (let y = 0; y < bh; y++)
    for (let x = 0; x < bw; x++) {
      const k = m[y * bw + x]!;
      if (k <= 0) continue;
      const n = (hash((b.x0 + x) * 0.913 + (b.y0 + y) * 7.31 + seed * 0.17) - 0.5) * 2 * grain;
      for (let c = 0; c < 3; c++) put(b.x0 + x, b.y0 + y, c, rgb[(y * bw + x) * 3 + c]! + n, k);
    }
  return null;
}

/* --------------------------- canvas convenience ---------------------------- */

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;

/** Apply regions to a 2D canvas context in place (works on the region boxes only, so it stays fast). */
export function applyRemovals(ctx: Ctx, W: number, H: number, regions: RemoveRegion[], offsets: Map<string, [number, number]>, seed: number, masks?: Map<string, Uint8Array>): void {
  for (const r of regions) {
    const full = masks?.get(r.id);
    // Work on the region plus a generous margin (clone sources and the ring live there).
    const pad = Math.round(Math.max(r.w * W, r.h * H) * 1.8) + 8;
    const b = regionBox(r, W, H, pad);
    const bw = b.x1 - b.x0,
      bh = b.y1 - b.y0;
    if (bw < 2 || bh < 2) continue;
    const img = ctx.getImageData(b.x0, b.y0, bw, bh);
    const local: RemoveRegion = { ...r, x: (r.x * W - b.x0) / bw, y: (r.y * H - b.y0) / bh, w: (r.w * W) / bw, h: (r.h * H) / bh };
    if (full) {
      // Exact mask: crop it, then soften the edge a little (2 px) so the fill blends.
      const data = new Uint8Array(bw * bh);
      for (let y = 0; y < bh; y++) for (let x = 0; x < bw; x++) data[y * bw + x] = full[(b.y0 + y) * W + (b.x0 + x)]!;
      const soft = new Uint8Array(bw * bh);
      for (let y = 0; y < bh; y++)
        for (let x = 0; x < bw; x++) {
          let s2 = 0,
            n = 0;
          for (let dy = -2; dy <= 2; dy++)
            for (let dx = -2; dx <= 2; dx++) {
              const yy = y + dy,
                xx = x + dx;
              if (yy < 0 || xx < 0 || yy >= bh || xx >= bw) continue;
              s2 += data[yy * bw + xx]!;
              n++;
            }
          soft[y * bw + x] = Math.max(data[y * bw + x]!, Math.min(255, (s2 / n) * 2));
        }
      local.maskLocal = { w: bw, h: bh, data: soft };
    }
    const known = offsets.get(r.id);
    if (known) local.offset = [(known[0] * W) / bw, (known[1] * H) / bh];
    const used = removeRegion(img, local, seed);
    if (used && !known) offsets.set(r.id, [(used[0] * bw) / W, (used[1] * bh) / H]);
    ctx.putImageData(img, b.x0, b.y0);
  }
}
