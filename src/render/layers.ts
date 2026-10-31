/** Turns evaluated layers into pixel sources for the compositor: video frames, images, text, shapes, animation. */
import { evaluate, type LayerDraw } from "../core/evaluate";
import { findAsset, findScene, type Project, type ShapeStyle, type TextStyle } from "../core/model";
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
  const font = `${st.weight} ${size}px ${st.font}`;
  const lines = st.text.split("\n");
  const measure = ctx2d(makeCanvas(4, 4));
  measure.font = font;
  const widths = lines.map((l) => measure.measureText(l).width);
  const lineH = size * 1.2;
  const pad = Math.ceil(size * 0.35 + st.strokeWidth * k + st.shadow * k);
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
  lines.forEach((line, li) => {
    const lw = widths[li]!;
    let cx = st.align === "left" ? pad : st.align === "right" ? w - pad - lw : (w - lw) / 2;
    const cy = pad + lineH * (li + 0.5);
    for (const ch of [...line]) {
      const cw = x.measureText(ch).width;
      const local = total > 0 ? Math.max(0, Math.min(1, (step * (total + 4) - index) / 4)) : 1;
      let alpha = 1;
      let dy = 0;
      let sc = 1;
      switch (st.animate) {
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
      }
      if (alpha > 0) {
        x.save();
        x.globalAlpha = alpha;
        x.translate(cx + cw / 2, cy + dy);
        x.scale(sc, sc);
        if (st.shadow > 0) {
          x.shadowColor = "rgba(0,0,0,0.55)";
          x.shadowBlur = st.shadow * k;
          x.shadowOffsetY = st.shadow * k * 0.25;
        }
        if (st.strokeWidth > 0) {
          x.lineWidth = st.strokeWidth * k * 2;
          x.strokeStyle = st.stroke;
          x.strokeText(ch, -cw / 2, 0);
          x.shadowColor = "transparent";
        }
        x.fillStyle = st.color;
        x.fillText(ch, -cw / 2, 0);
        x.restore();
      }
      cx += cw;
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
  const draws = evaluate(p, t);
  const k = o.outH / p.height;
  const scaled = (d: LayerDraw): LayerDraw => (k === 1 ? d : { ...d, x: d.x * k, y: d.y * k, grade: { ...d.grade, blur: d.grade.blur * k } });
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
      if (a.kind === "image") {
        const now = pool.imageNow(a.id);
        if (now) return { draw: d, source: now, sw: now.width, sh: now.height, version: a.id };
        if (o.wait) {
          const bmp = await pool.image(a);
          return { draw: d, source: bmp, sw: bmp.width, sh: bmp.height, version: a.id };
        }
        void pool.image(a).then(() => o.onLate?.());
        return null;
      }
      if (a.kind === "video") {
        const f = await pool.framer(a, o.purpose === "export" ? `export` : "preview", o.purpose === "export" ? undefined : (o.previewMax ?? 960));
        if (!f) return null;
        let wc = f.latest;
        const near = wc && Math.abs(wc.timestamp - d.srcTime) < Math.max(wc.duration, 0.04) * 1.01 && d.srcTime >= wc.timestamp - 1e-4;
        if (o.wait || !wc) wc = await f.frameAt(d.srcTime);
        else if (!near) void f.frameAt(d.srcTime).then(() => o.onLate?.());
        if (!wc) return null;
        return { draw: d, source: wc.canvas as LayerSource, sw: wc.canvas.width, sh: wc.canvas.height, version: `${wc.timestamp}` };
      }
      return null;
    }
  }
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
