/**
 * Looking at video: frame strips (one image of many moments), shot changes, frozen/black stretches (end cards,
 * outros, dead air) and burned-in static overlays (logos, stickers, captions) that object removal can clean.
 */
import { ctx2d, makeCanvas } from "../anim/render";
import { findAsset, type Project } from "../core/model";
import type { MediaPool } from "./library";
import { toJpeg } from "../render/snapshot";

async function sampler(p: Project, pool: MediaPool, assetId: string, max: number) {
  const a = findAsset(p, assetId);
  if (!a || (a.kind !== "video" && a.kind !== "image")) throw new Error("Pick a video or photo.");
  if (a.kind === "image") {
    const bmp = await pool.image(a);
    return { a, at: async () => bmp as CanvasImageSource, w: bmp.width, h: bmp.height };
  }
  const f = await pool.framer(a, "vision", max);
  if (!f) throw new Error("This video cannot be decoded.");
  return {
    a,
    at: async (t: number) => ((await f.frameAt(Math.max(0, Math.min(a.duration - 0.01, t))))?.canvas ?? null) as CanvasImageSource | null,
    w: a.width,
    h: a.height,
  };
}

/** One JPEG grid of many moments of a clip, labelled with their times. */
export async function frameStrip(p: Project, pool: MediaPool, assetId: string, o: { times?: number[]; count?: number; cell?: number; cols?: number } = {}): Promise<{ jpeg: string; times: number[] }> {
  const s = await sampler(p, pool, assetId, 480);
  const n = Math.max(1, Math.min(48, o.times?.length ?? o.count ?? 8));
  const times = o.times ?? Array.from({ length: n }, (_, i) => Math.round(((s.a.duration || 0) * (i + 0.5)) / n * 100) / 100);
  const cols = o.cols ?? Math.min(6, n);
  const cw = o.cell ?? 160;
  const ch = Math.round((cw * (s.h || 16)) / (s.w || 9)) + 22;
  const c = makeCanvas(cols * cw, Math.ceil(n / cols) * ch);
  const x = ctx2d(c);
  x.fillStyle = "#111";
  x.fillRect(0, 0, c.width, c.height);
  x.font = "bold 16px sans-serif";
  for (let i = 0; i < n; i++) {
    const src = await s.at(times[i]!);
    const gx = (i % cols) * cw,
      gy = Math.floor(i / cols) * ch;
    if (src) x.drawImage(src, gx + 2, gy + 2, cw - 4, ch - 24);
    x.fillStyle = "#ffd23f";
    x.fillText(`${times[i]!.toFixed(2)}s`, gx + 6, gy + ch - 6);
  }
  return { jpeg: await toJpeg(c, 0.7), times };
}

function lumaGrid(x: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, src: CanvasImageSource, gw: number, gh: number): Float32Array {
  x.drawImage(src, 0, 0, gw, gh);
  const d = x.getImageData(0, 0, gw, gh).data;
  const out = new Float32Array(gw * gh);
  for (let i = 0; i < gw * gh; i++) out[i] = 0.299 * d[i * 4]! + 0.587 * d[i * 4 + 1]! + 0.114 * d[i * 4 + 2]!;
  return out;
}

export interface SceneInfo {
  duration: number;
  /** Hard cuts (shot changes), seconds. */
  cuts: number[];
  /** Stretches where the picture is frozen (end cards, freeze frames). */
  frozen: { start: number; end: number }[];
  /** Near-black stretches. */
  black: { start: number; end: number }[];
  /** Where the real footage ends if the clip finishes on a frozen/black card (else the duration). */
  contentEnd: number;
}

/** Shot changes, frozen and black stretches, sampled at `fps` (default 6). */
export async function detectScenes(p: Project, pool: MediaPool, assetId: string, fps = 6): Promise<SceneInfo> {
  const s = await sampler(p, pool, assetId, 240);
  const dur = s.a.duration;
  const gw = 32,
    gh = Math.max(8, Math.round((32 * (s.h || 16)) / (s.w || 9)));
  const c = makeCanvas(gw, gh);
  const x = ctx2d(c);
  const step = 1 / fps;
  const diffs: { t: number; d: number; mean: number }[] = [];
  let prev: Float32Array | null = null;
  for (let t = 0; t < dur; t += step) {
    const src = await s.at(t);
    if (!src) continue;
    const g = lumaGrid(x, src, gw, gh);
    let mean = 0;
    for (const v of g) mean += v;
    mean /= g.length;
    let d = 0;
    if (prev) for (let i = 0; i < g.length; i++) d += Math.abs(g[i]! - prev[i]!);
    diffs.push({ t: Math.round(t * 100) / 100, d: prev ? d / g.length : 0, mean });
    prev = g;
  }
  const ds = diffs.slice(1).map((q) => q.d).sort((a, b) => a - b);
  const med = ds[Math.floor(ds.length / 2)] ?? 0;
  const cuts = diffs.filter((q, i) => i > 0 && q.d > Math.max(28, med * 5)).map((q) => q.t);
  const runs = (pred: (q: (typeof diffs)[number], i: number) => boolean, back: number) => {
    const out: { start: number; end: number }[] = [];
    let st = -1;
    diffs.forEach((q, i) => {
      if (pred(q, i)) {
        if (st < 0) st = Math.max(0, q.t - back);
      } else if (st >= 0) {
        if (q.t - st >= 0.5) out.push({ start: st, end: q.t });
        st = -1;
      }
    });
    if (st >= 0 && dur - st >= 0.5) out.push({ start: st, end: Math.round(dur * 100) / 100 });
    return out;
  };
  const frozen = runs((q, i) => i > 0 && q.d < 0.8, step);
  const black = runs((q) => q.mean < 14, 0);
  let contentEnd = dur;
  for (const r of [...frozen, ...black]) if (r.end >= dur - 0.3 && r.start < contentEnd) contentEnd = r.start;
  // A cut right before a frozen tail marks the card's real start.
  const lastCut = cuts.filter((c2) => c2 <= contentEnd + 0.4 && c2 >= contentEnd - 1).pop();
  if (lastCut !== undefined && contentEnd < dur) contentEnd = Math.min(contentEnd, lastCut);
  return { duration: Math.round(dur * 100) / 100, cuts, frozen, black, contentEnd: Math.round(contentEnd * 100) / 100 };
}

export interface OverlayBox {
  x: number;
  y: number;
  w: number;
  h: number;
  score: number;
}

/**
 * Burned-in static overlays: pixels that stay identical while the picture around them changes, with strong edges
 * (text, logos, stickers). Returns boxes as fractions of the frame (centre + size), best first.
 */
export async function findOverlays(p: Project, pool: MediaPool, assetId: string, o: { samples?: number; from?: number; to?: number } = {}): Promise<{ boxes: OverlayBox[]; moving: number }> {
  const s = await sampler(p, pool, assetId, 480);
  const from = o.from ?? 0;
  const to = o.to ?? s.a.duration;
  const n = Math.max(4, Math.min(24, o.samples ?? 12));
  const gw = 144,
    gh = Math.max(16, Math.round((144 * (s.h || 16)) / (s.w || 9)));
  const c = makeCanvas(gw, gh);
  const x = ctx2d(c);
  const frames: Float32Array[] = [];
  for (let i = 0; i < n; i++) {
    const src = await s.at(from + ((to - from) * (i + 0.5)) / n);
    if (src) frames.push(lumaGrid(x, src, gw, gh));
  }
  if (frames.length < 3) return { boxes: [], moving: 0 };
  const N = gw * gh;
  const mean = new Float32Array(N),
    sd = new Float32Array(N);
  for (const f of frames) for (let i = 0; i < N; i++) mean[i]! += f[i]! / frames.length;
  for (const f of frames) for (let i = 0; i < N; i++) sd[i]! += (f[i]! - mean[i]!) ** 2 / frames.length;
  for (let i = 0; i < N; i++) sd[i] = Math.sqrt(sd[i]!);
  // How much of the frame moves at all (a tripod shot has a static background; then only crisp, high-contrast
  // static detail counts as an overlay).
  let movingPx = 0;
  for (let i = 0; i < N; i++) if (sd[i]! > 10) movingPx++;
  const moving = movingPx / N;
  const edge = new Float32Array(N);
  for (let y = 1; y < gh - 1; y++)
    for (let x2 = 1; x2 < gw - 1; x2++) {
      const i = y * gw + x2;
      edge[i] = Math.abs(mean[i + 1]! - mean[i - 1]!) + Math.abs(mean[i + gw]! - mean[i - gw]!);
    }
  const edgeThr = moving > 0.35 ? 22 : 45;
  const hot = new Uint8Array(N);
  for (let i = 0; i < N; i++) if (sd[i]! < 4 && edge[i]! > edgeThr) hot[i] = 1;
  // Grow a little so letters join into one block.
  const grown = new Uint8Array(N);
  for (let y = 0; y < gh; y++)
    for (let x2 = 0; x2 < gw; x2++) {
      if (!hot[y * gw + x2]) continue;
      for (let dy = -2; dy <= 2; dy++)
        for (let dx = -3; dx <= 3; dx++) {
          const yy = y + dy,
            xx = x2 + dx;
          if (yy >= 0 && yy < gh && xx >= 0 && xx < gw) grown[yy * gw + xx] = 1;
        }
    }
  // Connected components.
  const seen = new Uint8Array(N);
  const boxes: OverlayBox[] = [];
  for (let i = 0; i < N; i++) {
    if (!grown[i] || seen[i]) continue;
    const stack = [i];
    seen[i] = 1;
    let x0 = gw,
      y0 = gh,
      x1 = 0,
      y1 = 0,
      area = 0,
      hits = 0;
    while (stack.length) {
      const j = stack.pop()!;
      const jx = j % gw,
        jy = (j / gw) | 0;
      area++;
      if (hot[j]) hits++;
      x0 = Math.min(x0, jx);
      x1 = Math.max(x1, jx);
      y0 = Math.min(y0, jy);
      y1 = Math.max(y1, jy);
      for (const k of [j - 1, j + 1, j - gw, j + gw]) {
        if (k < 0 || k >= N || seen[k] || !grown[k]) continue;
        if ((k === j - 1 && jx === 0) || (k === j + 1 && jx === gw - 1)) continue;
        seen[k] = 1;
        stack.push(k);
      }
    }
    const bw = x1 - x0 + 1,
      bh = y1 - y0 + 1;
    const frac = (bw * bh) / N;
    if (hits < 6 || frac > 0.12 || frac < 0.0008) continue;
    // Overlays usually hug an edge or a corner.
    const cx = (x0 + x1 + 1) / 2 / gw,
      cy = (y0 + y1 + 1) / 2 / gh;
    const edgeBias = 1 + 0.8 * Math.max(Math.abs(cx - 0.5), Math.abs(cy - 0.5)) * 2;
    const pad = 1.5;
    boxes.push({
      x: Math.round(cx * 1000) / 1000,
      y: Math.round(cy * 1000) / 1000,
      w: Math.round(Math.min(1, (bw + pad * 2) / gw) * 1000) / 1000,
      h: Math.round(Math.min(1, (bh + pad * 2) / gh) * 1000) / 1000,
      score: Math.round((hits / area) * edgeBias * Math.min(1, moving * 3 + 0.2) * 100) / 100,
    });
  }
  boxes.sort((a, b) => b.score - a.score);
  return { boxes: boxes.slice(0, 6), moving: Math.round(moving * 100) / 100 };
}
