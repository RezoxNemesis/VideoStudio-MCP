import { syncProjectLuts } from "../color/luts";
import { activeRegions, applyRemovals, regionBox, type RemoveRegion } from "./inpaint";
import { aiFill, maskAtSize } from "../media/ai";
import { fontStack } from "./fonts";
import { preloadDepth } from "../anim/living";
import { matteAt, matteLoad, matteNow } from "../media/matte";
import { GENERATED_TEXTURES, renderSubject } from "./subject";
import { setMotionMatte } from "../anim/motion";

setMotionMatte((id) => {
  const m = matteNow(id);
  return m ? { alpha: m.data.subarray(0, m.w * m.h), w: m.w, h: m.h } : null;
});
/** Turns evaluated layers into pixel sources for the compositor: video frames, images, text, shapes, animation. */
import { evaluate, type LayerDraw } from "../core/evaluate";
import { findAsset, findClip, findScene, type Project, type ShapeStyle, type TextStyle } from "../core/model";
import { ctx2d, frameAt, makeCanvas, renderScene } from "../anim/render";
import type { LayerInput, LayerSource } from "./compositor";
import type { MediaPool } from "../media/library";

type AnyCanvas = HTMLCanvasElement | OffscreenCanvas;

/* ---------------------------------- text ---------------------------------- */

const textCache = new Map<string, { canvas: AnyCanvas; used: number }>();
let tick = 0;

function easeOut(x: number) {
  return 1 - Math.pow(1 - Math.max(0, Math.min(1, x)), 3);
}

/** Draw a text style at project scale `k` (project height / 1920). Cached by content and animation step. */
export function textCanvas(st: TextStyle, k: number, progress: number): { canvas: AnyCanvas; w: number; h: number; key: string } {
  const step = st.animate === "none" ? 1 : Math.round(progress * 60) / 60;
  const key = JSON.stringify([st, Math.round(k * 1000), step]);
  const hit = textCache.get(key);
  tick++;
  if (hit) {
    hit.used = tick;
    return { canvas: hit.canvas, w: hit.canvas.width, h: hit.canvas.height, key };
  }
  const size = Math.max(4, st.size * k);
  const font = `${st.weight} ${size}px ${/[,"']/.test(st.font) ? st.font : fontStack(st.font)}`;
  const lines = st.text.split("\n");
  const measure = ctx2d(makeCanvas(4, 4));
  measure.font = font;
  const trk = (st.tracking ?? 0) * k;
  const widths = lines.map((l) => measure.measureText(l).width + Math.max(0, [...l].length - 1) * trk);
  const lineH = size * 1.2;
  const pad = Math.ceil(size * 0.35 + st.strokeWidth * k + st.shadow * k + (st.glow ? size * 0.4 : 0) + (st.animate === "track" ? size * 0.2 : 0));
  const w = Math.ceil(Math.max(1, ...widths) + pad * 2);
  const h = Math.ceil(lines.length * lineH + pad * 2);
  const c = makeCanvas(w, h);
  const x = ctx2d(c);
  if (st.bg) {
    x.fillStyle = st.bg;
    const r = size * 0.25;
    x.beginPath();
    x.roundRect(pad * 0.4, pad * 0.4, w - pad * 0.8, h - pad * 0.8, r);
    x.fill();
  }
  x.font = font;
  x.textBaseline = "middle";
  x.lineJoin = "round";
  const total = st.text.replace(/\n/g, "").length;
  let index = 0;
  // Complex scripts (Devanagari etc.) must be shaped as whole words, never glyph by glyph.
  const complex = (l: string) => /[\u0900-\u0DFF\u0600-\u06FF]/.test(l);
  const paint = (cv: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, str: string, left: number, cy: number) => {
    if (st.glow) {
      cv.save();
      cv.shadowColor = st.glow;
      cv.shadowBlur = size * 0.35;
      cv.fillStyle = st.glow;
      cv.fillText(str, left, cy);
      cv.shadowBlur = size * 0.12;
      cv.fillText(str, left, cy);
      cv.restore();
    }
    cv.save();
    if (st.shadow > 0) {
      cv.shadowColor = "rgba(0,0,0,0.55)";
      cv.shadowBlur = st.shadow * k;
      cv.shadowOffsetY = st.shadow * k * 0.25;
    }
    if (st.strokeWidth > 0) {
      cv.lineWidth = st.strokeWidth * k * 2;
      cv.strokeStyle = st.stroke;
      cv.strokeText(str, left, cy);
      cv.shadowColor = "transparent";
    }
    cv.fillStyle = st.color;
    cv.fillText(str, left, cy);
    cv.restore();
  };
  lines.forEach((line, li) => {
    const lw = widths[li]!;
    let cx = st.align === "left" ? pad : st.align === "right" ? w - pad - lw : (w - lw) / 2;
    const cy = pad + lineH * (li + 0.5);
    const chars = [...line];
    if (st.animate === "write" || (complex(line) && !trk && ["none", "fade", "rise", "blurIn", "track", "typewriter"].includes(st.animate))) {
      // Whole-line drawing. "write": the line is inked left to right like a pen (soft wet edge), one line after another.
      const n = chars.length;
      const lp = total > 0 ? Math.max(0, Math.min(1, (step * total - index) / Math.max(1, n))) : 1;
      const prog = st.animate === "write" ? lp : st.animate === "none" ? 1 : st.animate === "typewriter" ? (lp > 0 ? 1 : 0) : easeOut(Math.max(0, Math.min(1, step * 1.4 - li * 0.15)));
      if (prog > 0) {
        const lc = makeCanvas(w, Math.ceil(lineH + pad * 2));
        const lx = ctx2d(lc);
        lx.font = font;
        lx.textBaseline = "middle";
        lx.lineJoin = "round";
        const ly = pad + lineH / 2;
        paint(lx, line, cx, ly);
        if (st.animate === "write" && prog < 1) {
          const feather = size * 0.35;
          const edge = cx - feather + (lw + feather * 2) * prog;
          const g = lx.createLinearGradient(edge - feather, 0, edge + 1, 0);
          g.addColorStop(0, "rgba(0,0,0,1)");
          g.addColorStop(1, "rgba(0,0,0,0)");
          lx.globalCompositeOperation = "destination-in";
          lx.fillStyle = g;
          lx.fillRect(0, 0, w, lc.height);
        }
        x.save();
        if (st.animate !== "write" && st.animate !== "none" && st.animate !== "typewriter") {
          x.globalAlpha = prog;
          if (st.animate === "blurIn" && prog < 1) x.filter = `blur(${((1 - prog) * size * 0.25).toFixed(1)}px)`;
        }
        const dy0 = st.animate === "rise" ? (1 - prog) * size * 0.6 : 0;
        x.drawImage(lc as CanvasImageSource, 0, cy - ly + dy0);
        x.restore();
      }
      index += n;
      return;
    }
    // Tracking-in: letters start spread out and close up.
    const spread = st.animate === "track" ? (1 - easeOut(step)) * size * 0.45 : 0;
    cx -= (spread * (chars.length - 1)) / 2;
    for (const ch of chars) {
      const cw = x.measureText(ch).width;
      const local = total > 0 ? Math.max(0, Math.min(1, (step * (total + 4) - index) / 4)) : 1;
      let alpha = 1;
      let dy = 0;
      let sc = 1;
      let blur = 0;
      let rot = 0;
      let dx = 0;
      let glyph = ch;
      switch (st.animate) {
        case "scramble": {
          alpha = local > 0 ? 1 : 0;
          if (local < 1 && ch.trim()) {
            const set = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789#$%&@";
            const n = Math.abs(Math.sin(index * 12.9898 + Math.floor(step * 40) * 78.233) * 43758.5453);
            glyph = set[Math.floor((n - Math.floor(n)) * set.length)]!;
          }
          break;
        }
        case "drop":
          alpha = Math.min(1, local * 2);
          dy = -(1 - easeOut(local)) * size * 1.4 + Math.sin(Math.min(1, local) * Math.PI) * size * 0.06;
          break;
        case "shake": {
          alpha = 1;
          const amp = (1 - Math.min(1, step)) * size * 0.12 + size * 0.015;
          dx = Math.sin(index * 7.1 + step * 90) * amp;
          dy = Math.cos(index * 3.3 + step * 110) * amp;
          break;
        }
        case "zoomOut":
          alpha = Math.min(1, local * 1.5);
          sc = 1 + (1 - easeOut(local)) * 2.5;
          break;
        case "spin":
          alpha = local > 0 ? 1 : 0;
          sc = Math.max(0.01, easeOut(local));
          rot = (1 - easeOut(local)) * Math.PI * 1.5;
          break;
        case "swing":
          alpha = Math.min(1, local * 2);
          rot = Math.sin(local * Math.PI * 3) * (1 - local) * 0.6;
          break;
        case "typewriter":
          alpha = step * total >= index ? 1 : 0;
          break;
        case "fade":
          alpha = local;
          break;
        case "rise":
          alpha = local;
          dy = (1 - easeOut(local)) * size * 0.6;
          break;
        case "pop":
          alpha = local > 0 ? 1 : 0;
          sc = local >= 1 ? 1 : 0.4 + easeOut(local) * 0.75 - Math.max(0, local - 0.8) * 0.75;
          break;
        case "wave":
          alpha = local;
          dy = Math.sin(local * Math.PI) * -size * 0.25;
          break;
        case "slam":
          alpha = Math.min(1, local * 3);
          sc = 1 + (1 - easeOut(local)) * 1.8;
          blur = (1 - local) * size * 0.08;
          break;
        case "blurIn":
          alpha = local;
          blur = (1 - easeOut(local)) * size * 0.25;
          break;
        case "track":
          alpha = Math.min(1, step * 1.6);
          break;
        case "bounce":
          alpha = local > 0 ? 1 : 0;
          dy = -Math.abs(Math.sin(local * Math.PI * 2.2)) * (1 - local) * size * 0.7;
          break;
        case "flicker": {
          const n = Math.sin(index * 91.7 + Math.floor(step * 30) * 12.9898) * 43758.5453;
          alpha = step >= 1 ? 1 : (n - Math.floor(n)) < step ? 1 : 0.15;
          break;
        }
      }
      if (alpha > 0) {
        x.save();
        x.globalAlpha = alpha;
        x.translate(cx + cw / 2 + dx, cy + dy);
        if (rot) x.rotate(rot);
        x.scale(sc, sc);
        if (blur > 0.3) x.filter = `blur(${blur.toFixed(1)}px)`;
        if (st.glow) {
          x.save();
          x.shadowColor = st.glow;
          x.shadowBlur = size * 0.35;
          x.fillStyle = st.glow;
          x.fillText(glyph, -cw / 2, 0);
          x.shadowBlur = size * 0.12;
          x.fillText(glyph, -cw / 2, 0);
          x.restore();
        }
        if (st.shadow > 0) {
          x.shadowColor = "rgba(0,0,0,0.55)";
          x.shadowBlur = st.shadow * k;
          x.shadowOffsetY = st.shadow * k * 0.25;
        }
        if (st.strokeWidth > 0) {
          x.lineWidth = st.strokeWidth * k * 2;
          x.strokeStyle = st.stroke;
          x.strokeText(glyph, -cw / 2, 0);
          x.shadowColor = "transparent";
        }
        x.fillStyle = st.color;
        x.fillText(glyph, -cw / 2, 0);
        x.restore();
      }
      cx += cw + trk + spread;
      index++;
    }
  });
  textCache.set(key, { canvas: c, used: tick });
  if (textCache.size > 160) {
    const old = [...textCache.entries()].sort((a, b) => a[1].used - b[1].used).slice(0, 60);
    for (const [k2] of old) textCache.delete(k2);
  }
  return { canvas: c, w, h, key };
}

const shapeCache = new Map<string, AnyCanvas>();
export function shapeCanvas(sh: ShapeStyle, k: number): { canvas: AnyCanvas; key: string } {
  const key = JSON.stringify([sh, Math.round(k * 1000)]);
  let c = shapeCache.get(key);
  if (!c) {
    const w = Math.max(2, Math.round(sh.w * k + sh.strokeWidth * k * 2));
    const h = Math.max(2, Math.round(sh.h * k + sh.strokeWidth * k * 2));
    c = makeCanvas(w, h);
    const x = ctx2d(c);
    const sw = sh.strokeWidth * k;
    x.beginPath();
    if (sh.shape === "ellipse") x.ellipse(w / 2, h / 2, Math.max(1, w / 2 - sw), Math.max(1, h / 2 - sw), 0, 0, Math.PI * 2);
    else if (sh.shape === "line") {
      x.moveTo(sw, h / 2);
      x.lineTo(w - sw, h / 2);
    } else x.roundRect(sw, sw, w - sw * 2, h - sw * 2, sh.radius * k);
    if (sh.fill && sh.shape !== "line") {
      x.fillStyle = sh.fill;
      x.fill();
    }
    if (sh.stroke && sw > 0) {
      x.lineWidth = sw;
      x.strokeStyle = sh.stroke;
      x.stroke();
    }
    shapeCache.set(key, c);
    if (shapeCache.size > 80) shapeCache.delete(shapeCache.keys().next().value!);
  }
  return { canvas: c, key };
}

/* ------------------------------- animation -------------------------------- */

const sceneCanvases = new Map<string, AnyCanvas>();

/* --------------------------------- builder -------------------------------- */

export interface BuildOptions {
  purpose: "preview" | "export";
  /** Longest side for decoded preview video. */
  previewMax?: number;
  /** For export: wait for every frame. For preview: use what is ready and ask for a redraw. */
  wait: boolean;
  onLate?: () => void;
  /** Scale animation/text against this output size instead of the project size. */
  outW: number;
  outH: number;
}

/** Gather sources for every visible layer at time t. */
export async function buildLayers(p: Project, pool: MediaPool, t: number, o: BuildOptions): Promise<LayerInput[]> {
  syncProjectLuts(p);
  pool.beginFrame(o.purpose);
  const draws = evaluate(p, t);
  const k = o.outH / p.height;
  const scaled = (d: LayerDraw): LayerDraw =>
    k === 1
      ? d
      : {
          ...d,
          x: d.x * k,
          y: d.y * k,
          grade: { ...d.grade, blur: d.grade.blur * k },
          fx: { ...d.fx, rgbSplit: d.fx.rgbSplit * k, pixelate: d.fx.pixelate * k, glowRadius: d.fx.glowRadius * k },
          dirBlur: [d.dirBlur[0] * k, d.dirBlur[1] * k],
        };
  const out: LayerInput[] = [];
  await Promise.all(
    draws.map(async (d0, i) => {
      const d = scaled(d0);
      const input = await sourceFor(p, pool, d, o, k);
      if (input) out[i] = input;
    }),
  );
  return out.filter(Boolean);
}

async function sourceFor(p: Project, pool: MediaPool, d: LayerDraw, o: BuildOptions, k: number): Promise<LayerInput | null> {
  switch (d.kind) {
    case "adjust":
      return { draw: d, source: null, sw: 0, sh: 0, version: "adjust" };
    case "color": {
      const c = makeColor(d.color ?? "#000000");
      return { draw: { ...d, fit: "fill" }, source: c, sw: 4, sh: 4, version: d.color ?? "" };
    }
    case "text": {
      if (!d.text) return null;
      const tc = textCanvas(d.text, (p.height / 1920) * k, d.textProgress);
      return { draw: d, source: tc.canvas as LayerSource, sw: tc.w, sh: tc.h, version: tc.key, native: true };
    }
    case "shape": {
      if (!d.shape) return null;
      const sc = shapeCanvas(d.shape, k);
      return { draw: d, source: sc.canvas as LayerSource, sw: sc.canvas.width, sh: sc.canvas.height, version: sc.key, native: true };
    }
    case "anim": {
      const s = findScene(p, d.sceneId);
      if (!s) return null;
      // Collage photos inside scenes: make sure they are decoded before an export frame is drawn.
      for (const l of s.layers) {
          const ids = [l.proc?.assetId ?? l.living?.assetId ?? (l.kind === "reference" ? l.assetId : undefined), ...(l.proc?.motion?.els.map((e) => e.assetId) ?? [])];
          for (const id of ids) {
            if (!id || pool.imageNow(id)) continue;
            const a = findAsset(p, id);
            if (!o.wait) {
              if (a?.kind === "image") void pool.image(a).then(() => o.onLate?.());
              void matteLoad(id).then(() => o.onLate?.());
              continue;
            }
            if (a?.kind === "image") await pool.image(a);
            await matteLoad(id).catch(() => null);
          }
        }
      for (const l of s.layers) if (l.living?.depth?.map) await preloadDepth(l.living.depth.map).catch(() => {});
      const frame = frameAt(s, d.srcTime, true);
      const maxSide = o.purpose === "preview" ? (o.previewMax ?? 960) : Math.max(o.outW, o.outH);
      const sk = Math.min(1, maxSide / Math.max(s.width, s.height)) * (o.purpose === "export" ? Math.max(1, o.outH / s.height) : 1);
      const W = Math.round(s.width * sk);
      const H = Math.round(s.height * sk);
      const key = `${d.clipId}|${o.purpose}`;
      let c = sceneCanvases.get(key);
      if (!c || c.width !== W || c.height !== H) {
        c = makeCanvas(W, H);
        sceneCanvases.set(key, c);
      }
      renderScene(ctx2d(c), s, frame, { scale: sk, references: false, images: (id) => pool.imageNow(id), onReady: o.onLate });
      return { draw: d, source: c as LayerSource, sw: W, sh: H, version: `${frame}|${Math.random()}` };
    }
    case "media": {
      const a = findAsset(p, d.assetId);
      if (!a) return null;
      const clip = findClip(p, d.clipId);
      if (clip?.clip.subject) {
        const base = await mediaLayer(p, pool, { ...d }, o, a, clip.clip.remove);
        if (!base) return null;
        return await withSubject(p, pool, base, clip.clip, a.id, d.srcTime, o);
      }
      const regions = activeRegions(clip?.clip.remove, d.srcTime);
      if (a.kind === "image") {
        const now = pool.imageNow(a.id) ?? (o.wait ? await pool.image(a) : null);
        if (now) {
          if (regions.length) return cleaned(d, now, now.width, now.height, regions, `${a.id}`, o.purpose, !!o.wait, o.onLate);
          return { draw: d, source: now, sw: now.width, sh: now.height, version: a.id };
        }
        void pool.image(a).then(() => o.onLate?.());
        return null;
      }
      if (a.kind === "video") {
        const f = await pool.framer(a, o.purpose === "export" ? `export` : "preview", o.purpose === "export" ? undefined : (o.previewMax ?? 960), d.clipId);
        if (!f) return null;
        let wc = f.latest;
        const near = wc && Math.abs(wc.timestamp - d.srcTime) < Math.max(wc.duration, 0.04) * 1.01 && d.srcTime >= wc.timestamp - 1e-4;
        if (o.wait || !wc) wc = await f.frameAt(d.srcTime);
        else if (!near) void f.frameAt(d.srcTime).then(() => o.onLate?.());
        if (!wc) return null;
        if (regions.length) return cleaned(d, wc.canvas as CanvasImageSource, wc.canvas.width, wc.canvas.height, regions, `${wc.timestamp}`, o.purpose, !!o.wait, o.onLate);
        return { draw: d, source: wc.canvas as LayerSource, sw: wc.canvas.width, sh: wc.canvas.height, version: `${wc.timestamp}` };
      }
      return null;
    }
  }
}

/** The plain (or cleaned) media frame as a layer — shared by the subject path. */
async function mediaLayer(p: Project, pool: MediaPool, d: LayerDraw, o: BuildOptions, a: NonNullable<ReturnType<typeof findAsset>>, remove: RemoveRegion[] | undefined): Promise<LayerInput | null> {
  const regions = activeRegions(remove, d.srcTime);
  if (a.kind === "image") {
    const now = pool.imageNow(a.id) ?? (o.wait ? await pool.image(a) : null);
    if (!now) {
      void pool.image(a).then(() => o.onLate?.());
      return null;
    }
    if (regions.length) return cleaned(d, now, now.width, now.height, regions, `${a.id}`, o.purpose, !!o.wait, o.onLate);
    return { draw: d, source: now, sw: now.width, sh: now.height, version: a.id };
  }
  if (a.kind !== "video") return null;
  const f = await pool.framer(a, o.purpose === "export" ? `export` : "preview", o.purpose === "export" ? undefined : (o.previewMax ?? 960), d.clipId);
  if (!f) return null;
  const wc = await f.frameAt(d.srcTime);
  if (!wc) return null;
  if (regions.length) return cleaned(d, wc.canvas as CanvasImageSource, wc.canvas.width, wc.canvas.height, regions, `${wc.timestamp}`, o.purpose, !!o.wait, o.onLate);
  return { draw: d, source: wc.canvas as LayerSource, sw: wc.canvas.width, sh: wc.canvas.height, version: `${wc.timestamp}` };
}

const downCanvases = new Map<string, ReturnType<typeof makeCanvas>>();

/** Apply the clip's subject look (needs its matte; until the matte is ready the plain frame shows). */
async function withSubject(p: Project, pool: MediaPool, base: LayerInput, clip: import("../core/model").Clip, assetId: string, srcTime: number, o: BuildOptions): Promise<LayerInput> {
  const sp = clip.subject!;
  const m = o.wait ? await matteLoad(assetId) : matteNow(assetId);
  if (!m) {
    if (m === undefined) void matteLoad(assetId).then(() => o.onLate?.());
    return base;
  }
  const alpha = matteAt(m, srcTime);
  let tex: CanvasImageSource | null = null;
  if (sp.texture && !(GENERATED_TEXTURES as readonly string[]).includes(sp.texture)) {
    const ta = findAsset(p, sp.texture);
    if (ta?.kind === "image") tex = pool.imageNow(ta.id) ?? (o.wait ? await pool.image(ta) : null);
    else if (ta?.kind === "video") {
      const tf = await pool.framer(ta, `tex-${o.purpose}`, 720);
      const local = (srcTime - clip.in) % Math.max(0.1, ta.duration);
      tex = ((await tf?.frameAt(local))?.canvas as CanvasImageSource | undefined) ?? null;
    }
  }
  // Work at the size the output needs (a 4K source for a 1080p render is 4× the memory and time for nothing;
  // phones run out of memory). Close-ups keep their extra detail.
  let src = base.source as CanvasImageSource;
  let W = base.sw,
    H = base.sh;
  // Quantised so a moving camera doesn't reallocate canvases every frame.
  const need = Math.ceil(((o.purpose === "export" ? o.outH : Math.min(o.outH, o.previewMax ?? 960)) * Math.min(2, Math.max(1, Math.abs(base.draw.scaleY)))) / 240) * 240;
  if (H > need * 1.1) {
    const k = need / H;
    const w2 = Math.max(2, Math.round(W * k)),
      h2 = Math.max(2, Math.round(H * k));
    const ckey = `${clip.id}|${o.purpose}|down`;
    let c = downCanvases.get(ckey);
    if (!c || c.width !== w2 || c.height !== h2) {
      c = makeCanvas(w2, h2);
      downCanvases.set(ckey, c);
    }
    const cx = ctx2d(c);
    cx.imageSmoothingQuality = "high";
    cx.drawImage(src, 0, 0, w2, h2);
    src = c as CanvasImageSource;
    W = w2;
    H = h2;
  }
  const local = (srcTime - clip.in) / Math.max(0.01, clip.speed);
  const out = renderSubject(`${clip.id}|${o.purpose}`, src, W, H, alpha, m.w, m.h, sp, local, local, tex);
  return { ...base, source: out as LayerSource, version: `${base.version}|subj|${srcTime.toFixed(3)}|${JSON.stringify(sp)}` };
}

/* Object removal: a cleaned copy of the frame per clip, with clone offsets kept steady across frames. */
const cleanCanvases = new Map<string, { canvas: AnyCanvas; version: string }>();
const cleanOffsets = new Map<string, Map<string, [number, number]>>();
/** AI-filled frames (slow): kept by version so scrubbing back is instant. */
const aiFrames = new Map<string, ImageBitmap | AnyCanvas>();
const aiBusy = new Set<string>();
async function regionMasks(regions: RemoveRegion[], W: number, H: number): Promise<Map<string, Uint8Array>> {
  const out = new Map<string, Uint8Array>();
  for (const r of regions) {
    if (r.mask) out.set(r.id, await maskAtSize(r.mask, W, H));
    else if (r.mode === "ai" || r.mode === "smart") {
      // A box/oval drawn as a mask for the AI fill.
      const m = new Uint8Array(W * H);
      const b = regionBox(r, W, H);
      for (let y = b.y0; y < b.y1; y++)
        for (let x = b.x0; x < b.x1; x++) {
          const dx = (x + 0.5 - r.x * W) / ((r.w * W) / 2),
            dy = (y + 0.5 - r.y * H) / ((r.h * H) / 2);
          if (r.shape === "ellipse" ? dx * dx + dy * dy <= 1 : Math.abs(dx) <= 1 && Math.abs(dy) <= 1) m[y * W + x] = 255;
        }
      out.set(r.id, m);
    }
  }
  return out;
}
async function cleaned(d: LayerDraw, src: CanvasImageSource, W: number, H: number, regions: RemoveRegion[], ver: string, purpose: string, wait: boolean, onLate?: () => void): Promise<LayerInput> {
  const key = `${d.clipId}|${purpose}`;
  const sig = JSON.stringify(regions.map(({ offset: _o, maskLocal: _m, ...r }) => r));
  const version = `${ver}|${sig}`;
  let e = cleanCanvases.get(key);
  if (!e || e.canvas.width !== W || e.canvas.height !== H) {
    e = { canvas: makeCanvas(W, H), version: "" };
    cleanCanvases.set(key, e);
  }
  if (e.version !== version) {
    const x = ctx2d(e.canvas);
    const ai = regions.filter((r) => r.mode === "ai" || (r.mode === "smart" && !r.patch));
    const fast = regions.filter((r) => r.mode !== "ai" && r.mode !== "smart");
    const patched = regions.filter((r) => r.mode === "smart" && r.patch);
    const aiKey = `${d.clipId}|${W}x${H}|${version}`;
    const masks = regions.some((r) => r.mask || r.mode === "ai" || (r.mode === "smart" && !r.patch)) ? await regionMasks(regions, W, H) : undefined;
    const runFast = (list: RemoveRegion[]) => {
      const okey = `${d.clipId}|${W}x${H}|${JSON.stringify(list.map((r) => [r.id, r.w, r.h, r.shape, r.mode, r.offset, !!r.mask]))}`;
      let offs = cleanOffsets.get(okey);
      if (!offs) {
        offs = new Map();
        cleanOffsets.set(okey, offs);
      }
      for (const r of list) if (r.offset) offs.set(r.id, r.offset);
      applyRemovals(x as CanvasRenderingContext2D, W, H, list, offs, 7, masks);
    };
    x.clearRect(0, 0, W, H);
    const done = aiFrames.get(aiKey);
    if (ai.length && done) {
      x.drawImage(done as CanvasImageSource, 0, 0, W, H);
    } else {
      x.drawImage(src, 0, 0, W, H);
      if (ai.length && wait) {
        for (const r of ai) await aiFill(x, W, H, masks!.get(r.id)!);
        aiFrames.set(aiKey, await snapshotOf(e.canvas));
      } else if (ai.length) {
        // Preview: show a quick fill now, compute the AI fill in the background, then redraw.
        if (!aiBusy.has(aiKey)) {
          aiBusy.add(aiKey);
          const c2 = makeCanvas(W, H);
          const x2 = ctx2d(c2);
          x2.drawImage(src, 0, 0, W, H);
          void (async () => {
            try {
              for (const r of ai) await aiFill(x2, W, H, masks!.get(r.id)!);
              aiFrames.set(aiKey, c2);
              if (aiFrames.size > 24) aiFrames.delete(aiFrames.keys().next().value!);
              if (e) e.version = "";
              onLate?.();
            } catch {
              /* keep the quick fill */
            } finally {
              aiBusy.delete(aiKey);
            }
          })();
        }
        runFast(ai.map((r) => ({ ...r, mode: "fill" as const })));
      }
    }
    if (fast.length) runFast(fast);
    // Smart fill: the generated pixels were baked once into a patch (transparent outside the repaint).
    for (const r of patched) {
      const img = await patchImage(r.patch!);
      if (img) x.drawImage(img, 0, 0, W, H);
    }
    e.version = version;
  }
  return { draw: d, source: e.canvas as LayerSource, sw: W, sh: H, version };
}
const patchCache = new Map<string, ImageBitmap | null>();
async function patchImage(url: string): Promise<ImageBitmap | null> {
  const k = url.length + ":" + url.slice(-64);
  if (patchCache.has(k)) return patchCache.get(k)!;
  let bmp: ImageBitmap | null = null;
  try {
    bmp = await createImageBitmap(await (await fetch(url)).blob());
  } catch {
    bmp = null;
  }
  patchCache.set(k, bmp);
  if (patchCache.size > 16) patchCache.delete(patchCache.keys().next().value!);
  return bmp;
}
async function snapshotOf(c: AnyCanvas): Promise<AnyCanvas> {
  const copy = makeCanvas(c.width, c.height);
  ctx2d(copy).drawImage(c as CanvasImageSource, 0, 0);
  return copy;
}

const colorCanvases = new Map<string, AnyCanvas>();
function makeColor(color: string): LayerSource {
  let c = colorCanvases.get(color);
  if (!c) {
    c = makeCanvas(4, 4);
    const x = ctx2d(c);
    x.fillStyle = color;
    x.fillRect(0, 0, 4, 4);
    colorCanvases.set(color, c);
  }
  return c as LayerSource;
}
