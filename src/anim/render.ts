/** Draws animation scenes with Canvas 2D. Strokes are vectors, so any frame renders sharp at any size. */
import { valueAt } from "../core/keyframes";
import { TRANSFORM_DEFAULTS, exposedKey, type AnimLayer, type AnimScene, type BlendMode, type Cel, type Stroke } from "../core/model";

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;
type AnyCanvas = HTMLCanvasElement | OffscreenCanvas;

export const BLEND_2D: Record<BlendMode, GlobalCompositeOperation> = {
  normal: "source-over",
  add: "lighter",
  multiply: "multiply",
  screen: "screen",
  overlay: "overlay",
  darken: "darken",
  lighten: "lighten",
};

export function makeCanvas(w: number, h: number): AnyCanvas {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(Math.max(1, w), Math.max(1, h));
  const c = document.createElement("canvas");
  c.width = Math.max(1, w);
  c.height = Math.max(1, h);
  return c;
}

export function ctx2d(c: AnyCanvas): Ctx {
  const x = c.getContext("2d") as Ctx | null;
  if (!x) throw new Error("Canvas 2D is not available.");
  return x;
}

/** Pressure response per tool: how strongly width follows pressure, and the minimum width ratio. */
const PRESSURE: Record<Stroke["tool"], [number, number]> = {
  pen: [0.6, 0.35],
  ink: [1, 0.08],
  brush: [0.9, 0.15],
  pencil: [0.3, 0.6],
  marker: [0, 1],
  airbrush: [0.5, 0.5],
  eraser: [0.3, 0.6],
};

/** Variable-width outline polygon for a stroke (left side then right side reversed). */
export function outline(s: Stroke): number[] {
  const p = s.pts;
  const n = p.length / 3;
  if (n === 0) return [];
  const [amt, min] = PRESSURE[s.tool];
  const width = (i: number) => {
    const pr = p[i * 3 + 2] ?? 0.5;
    const taperIn = s.tool === "ink" || s.tool === "brush" ? Math.min(1, (i + 1) / 4) : 1;
    const taperOut = s.tool === "ink" || s.tool === "brush" ? Math.min(1, (n - i) / 4) : 1;
    return (s.size / 2) * (1 - amt + amt * Math.max(min, pr)) * Math.min(taperIn, taperOut);
  };
  const left: number[] = [];
  const right: number[] = [];
  for (let i = 0; i < n; i++) {
    const x = p[i * 3]!;
    const y = p[i * 3 + 1]!;
    const a = Math.max(0, i - 1);
    const b = Math.min(n - 1, i + 1);
    let dx = p[b * 3]! - p[a * 3]!;
    let dy = p[b * 3 + 1]! - p[a * 3 + 1]!;
    const len = Math.hypot(dx, dy) || 1;
    dx /= len;
    dy /= len;
    const w = width(i);
    left.push(x - dy * w, y + dx * w);
    right.push(x + dy * w, y - dx * w);
  }
  const out = left;
  for (let i = right.length - 2; i >= 0; i -= 2) out.push(right[i]!, right[i + 1]!);
  return out;
}

function tracePolygon(c: Ctx, poly: number[]) {
  c.beginPath();
  const n = poly.length / 2;
  if (n < 2) return;
  c.moveTo(poly[0]!, poly[1]!);
  // Midpoint quadratic smoothing.
  for (let i = 1; i < n; i++) {
    const x0 = poly[i * 2]!;
    const y0 = poly[i * 2 + 1]!;
    const j = (i + 1) % n;
    const mx = (x0 + poly[j * 2]!) / 2;
    const my = (y0 + poly[j * 2 + 1]!) / 2;
    c.quadraticCurveTo(x0, y0, mx, my);
  }
  c.closePath();
}

function traceCenter(c: Ctx, p: readonly number[]) {
  c.beginPath();
  const n = p.length / 3;
  c.moveTo(p[0]!, p[1]!);
  for (let i = 1; i < n - 1; i++) {
    const mx = (p[i * 3]! + p[(i + 1) * 3]!) / 2;
    const my = (p[i * 3 + 1]! + p[(i + 1) * 3 + 1]!) / 2;
    c.quadraticCurveTo(p[i * 3]!, p[i * 3 + 1]!, mx, my);
  }
  if (n > 1) c.lineTo(p[(n - 1) * 3]!, p[(n - 1) * 3 + 1]!);
}

export function drawStroke(c: Ctx, s: Stroke): void {
  const n = s.pts.length / 3;
  if (n === 0) return;
  c.save();
  c.globalAlpha *= s.opacity;
  if (s.tool === "eraser") c.globalCompositeOperation = "destination-out";
  if (s.closed && s.fill) {
    traceCenter(c, s.pts);
    c.closePath();
    c.fillStyle = s.fill;
    c.fill();
    if (s.size > 0) {
      c.lineWidth = s.size;
      c.lineJoin = "round";
      c.strokeStyle = s.color;
      c.stroke();
    }
    c.restore();
    return;
  }
  if (n === 1 || s.tool === "airbrush") {
    // Dabs: soft radial spots along the path.
    const step = Math.max(1, s.size * 0.18);
    let carry = 0;
    for (let i = 0; i < n; i++) {
      const x = s.pts[i * 3]!;
      const y = s.pts[i * 3 + 1]!;
      const pr = s.pts[i * 3 + 2] ?? 0.5;
      if (i > 0) {
        const px = s.pts[(i - 1) * 3]!;
        const py = s.pts[(i - 1) * 3 + 1]!;
        const d = Math.hypot(x - px, y - py);
        for (let t = step - carry; t < d; t += step) dab(c, s, px + ((x - px) * t) / d, py + ((y - py) * t) / d, pr);
        carry = (carry + d) % step;
      } else dab(c, s, x, y, pr);
    }
    c.restore();
    return;
  }
  if (s.tool === "marker") {
    traceCenter(c, s.pts);
    c.lineWidth = s.size;
    c.lineCap = "round";
    c.lineJoin = "round";
    c.strokeStyle = s.color;
    c.globalAlpha *= 0.75;
    c.stroke();
    c.restore();
    return;
  }
  tracePolygon(c, outline(s));
  c.fillStyle = s.color;
  if (s.tool === "pencil") c.globalAlpha *= 0.85;
  c.fill();
  c.restore();
}

function dab(c: Ctx, s: Stroke, x: number, y: number, pr: number) {
  const r = Math.max(0.5, (s.size / 2) * (s.tool === "airbrush" ? 1.4 : 0.5 + pr * 0.5));
  if (s.tool === "airbrush") {
    const g = c.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, withAlpha(s.color, 0.18 * (0.4 + pr)));
    g.addColorStop(1, withAlpha(s.color, 0));
    c.fillStyle = g;
  } else c.fillStyle = s.color;
  c.beginPath();
  c.arc(x, y, r, 0, Math.PI * 2);
  c.fill();
}

export function withAlpha(hex: string, a: number): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return hex;
  const n = parseInt(m[1]!, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

const fillCache = new Map<string, HTMLImageElement | ImageBitmap>();
const pendingFills = new Set<string>();

/** Fills are PNG data URLs; decode once and cache. Returns false if any fill is still decoding. */
function drawFills(c: Ctx, cel: Cel, onReady?: () => void): boolean {
  let ready = true;
  for (const f of cel.fills) {
    const img = fillCache.get(f.id);
    if (img) c.drawImage(img, f.x, f.y, f.w, f.h);
    else {
      ready = false;
      if (!pendingFills.has(f.id)) {
        pendingFills.add(f.id);
        const el = new Image();
        el.onload = () => {
          fillCache.set(f.id, el);
          pendingFills.delete(f.id);
          onReady?.();
        };
        el.src = f.png;
      }
    }
  }
  return ready;
}

/** Wait until every fill in the scene is decoded (used before export). */
export async function preloadFills(s: AnimScene): Promise<void> {
  const jobs: Promise<void>[] = [];
  for (const l of s.layers)
    for (const cel of Object.values(l.cels))
      for (const f of cel.fills) {
        if (fillCache.has(f.id)) continue;
        jobs.push(
          new Promise<void>((resolve) => {
            const el = new Image();
            el.onload = () => {
              fillCache.set(f.id, el);
              resolve();
            };
            el.onerror = () => resolve();
            el.src = f.png;
          }),
        );
      }
  await Promise.all(jobs);
}

export function drawCel(c: Ctx, cel: Cel, onReady?: () => void): void {
  drawFills(c, cel, onReady);
  for (const s of cel.strokes) drawStroke(c, s);
}

export type ImageLookup = (assetId: string) => CanvasImageSource | null;

export interface SceneRenderOptions {
  /** Output scale relative to scene pixels. */
  scale?: number;
  background?: boolean;
  /** Hide reference layers (exports never show them). */
  references?: boolean;
  onion?: { before: number; after: number; opacity: number } | null;
  /** Only draw this layer at full strength; others dimmed (focus mode). */
  focusLayer?: string | null;
  images?: ImageLookup;
  onReady?: () => void;
  /** Draw a cel instead of the exposed one for this layer (live stroke preview). */
  override?: { layerId: string; cel: Cel } | null;
}

let scratch: AnyCanvas | null = null;
function scratchCanvas(w: number, h: number): AnyCanvas {
  if (!scratch || scratch.width !== w || scratch.height !== h) scratch = makeCanvas(w, h);
  return scratch;
}

function layerTransform(c: Ctx, l: AnimLayer, frame: number, s: AnimScene) {
  const tr = (k: keyof typeof TRANSFORM_DEFAULTS) => valueAt(l.transform[k], frame, TRANSFORM_DEFAULTS[k]);
  const x = tr("x");
  const y = tr("y");
  const sc = tr("scale");
  const rot = tr("rotation");
  if (x === 0 && y === 0 && sc === 1 && rot === 0) return;
  const ax = tr("anchorX") * s.width;
  const ay = tr("anchorY") * s.height;
  c.translate(ax + x, ay + y);
  c.rotate((rot * Math.PI) / 180);
  c.scale(sc * tr("scaleX"), sc * tr("scaleY"));
  c.translate(-ax, -ay);
}

function layerOpacityAt(l: AnimLayer, frame: number): number {
  return l.opacity * valueAt(l.transform.opacity, frame, 1);
}

/** Render one frame of a scene into a 2D context of size scene.width*scale × scene.height*scale. */
export function renderScene(out: Ctx, s: AnimScene, frame: number, o: SceneRenderOptions = {}): void {
  const scale = o.scale ?? 1;
  const W = Math.round(s.width * scale);
  const H = Math.round(s.height * scale);
  out.save();
  out.clearRect(0, 0, W, H);
  if (o.background !== false && s.background) {
    out.fillStyle = s.background;
    out.fillRect(0, 0, W, H);
  }
  const layer = scratchCanvas(W, H);
  const lc = ctx2d(layer);
  const drawLayerAt = (l: AnimLayer, fr: number, tint: string | null, alpha: number, cel?: Cel) => {
    const key = exposedKey(l, fr);
    const use = cel ?? (key ? l.cels[key] : undefined);
    if (!use) return;
    lc.setTransform(1, 0, 0, 1, 0, 0);
    lc.globalCompositeOperation = "source-over";
    lc.globalAlpha = 1;
    lc.clearRect(0, 0, W, H);
    lc.setTransform(scale, 0, 0, scale, 0, 0);
    layerTransform(lc, l, fr, s);
    drawCel(lc, use, o.onReady);
    if (tint) {
      lc.setTransform(1, 0, 0, 1, 0, 0);
      lc.globalCompositeOperation = "source-atop";
      lc.fillStyle = tint;
      lc.fillRect(0, 0, W, H);
    }
    out.globalAlpha = alpha;
    out.globalCompositeOperation = tint ? "source-over" : BLEND_2D[l.blend];
    out.drawImage(layer as CanvasImageSource, 0, 0);
  };
  // Bottom layer first (layers[0] is the top).
  for (let i = s.layers.length - 1; i >= 0; i--) {
    const l = s.layers[i]!;
    if (!l.visible) continue;
    if (l.kind === "reference") {
      if (o.references === false || !l.assetId || !o.images) continue;
      const img = o.images(l.assetId);
      if (!img) continue;
      out.save();
      out.globalAlpha = layerOpacityAt(l, frame);
      out.setTransform(scale, 0, 0, scale, 0, 0);
      layerTransform(out, l, frame, s);
      const iw = (img as { width: number }).width;
      const ih = (img as { height: number }).height;
      const k = Math.min(s.width / iw, s.height / ih);
      out.drawImage(img, (s.width - iw * k) / 2, (s.height - ih * k) / 2, iw * k, ih * k);
      out.restore();
      continue;
    }
    const dim = o.focusLayer && o.focusLayer !== l.id ? 0.35 : 1;
    if (o.onion && o.onion.opacity > 0) {
      for (let b = o.onion.before; b >= 1; b--) {
        if (frame - b < 0) continue;
        if (exposedKey(l, frame - b) === exposedKey(l, frame)) continue;
        drawLayerAt(l, frame - b, "#e53935", o.onion.opacity * (1 - (b - 1) / (o.onion.before + 1)) * dim);
      }
      for (let a = 1; a <= o.onion.after; a++) {
        if (frame + a >= s.frames) continue;
        if (exposedKey(l, frame + a) === exposedKey(l, frame)) continue;
        drawLayerAt(l, frame + a, "#43a047", o.onion.opacity * (1 - (a - 1) / (o.onion.after + 1)) * dim);
      }
    }
    const override = o.override && o.override.layerId === l.id ? o.override.cel : undefined;
    drawLayerAt(l, frame, null, layerOpacityAt(l, frame) * dim, override);
  }
  out.restore();
}

/** Frame index for a time in seconds, looping inside the scene. */
export function frameAt(s: AnimScene, seconds: number, loop = true): number {
  const f = Math.floor(seconds * s.fps + 1e-6);
  return loop ? ((f % s.frames) + s.frames) % s.frames : Math.max(0, Math.min(s.frames - 1, f));
}

/* --------------------------------- bucket --------------------------------- */

/**
 * Flood fill from (x, y) on `lines` (rendered line art at scene size). Gaps up to `gap` px are closed by
 * dilating the line mask first. Returns a PNG of the filled region (plus a 1.5px grow under the lines), cropped.
 */
export async function bucketFill(lines: ImageData, x: number, y: number, color: string, tolerance = 32, gap = 2): Promise<{ png: string; x: number; y: number; w: number; h: number } | null> {
  const { width: W, height: H, data } = lines;
  const sx = Math.floor(x);
  const sy = Math.floor(y);
  if (sx < 0 || sy < 0 || sx >= W || sy >= H) return null;
  // Barrier = pixels with enough alpha that differ from the seed colour.
  const barrier = new Uint8Array(W * H);
  const si = (sy * W + sx) * 4;
  const seed = [data[si]!, data[si + 1]!, data[si + 2]!, data[si + 3]!];
  for (let i = 0, p = 0; i < barrier.length; i++, p += 4) {
    const d = Math.abs(data[p]! - seed[0]!) + Math.abs(data[p + 1]! - seed[1]!) + Math.abs(data[p + 2]! - seed[2]!) + Math.abs(data[p + 3]! - seed[3]!);
    barrier[i] = d > tolerance * 3 ? 1 : 0;
  }
  let wall = barrier;
  for (let g = 0; g < gap; g++) wall = dilate(wall, W, H);
  if (wall[sy * W + sx]) wall = barrier;
  const mask = new Uint8Array(W * H);
  const stack = [sx, sy];
  let minX = sx,
    maxX = sx,
    minY = sy,
    maxY = sy;
  while (stack.length) {
    const py = stack.pop()!;
    const px = stack.pop()!;
    let lx = px;
    while (lx >= 0 && !wall[py * W + lx] && !mask[py * W + lx]) lx--;
    lx++;
    let rx = px;
    while (rx < W && !wall[py * W + rx] && !mask[py * W + rx]) rx++;
    rx--;
    if (rx < lx) continue;
    for (let i = lx; i <= rx; i++) mask[py * W + i] = 1;
    minX = Math.min(minX, lx);
    maxX = Math.max(maxX, rx);
    minY = Math.min(minY, py);
    maxY = Math.max(maxY, py);
    for (const ny of [py - 1, py + 1]) {
      if (ny < 0 || ny >= H) continue;
      let i = lx;
      while (i <= rx) {
        while (i <= rx && (wall[ny * W + i] || mask[ny * W + i])) i++;
        if (i > rx) break;
        stack.push(i, ny);
        while (i <= rx && !wall[ny * W + i] && !mask[ny * W + i]) i++;
      }
    }
    if (stack.length > 4_000_000) break;
  }
  // Grow under the line art so no halo shows.
  let grown = mask;
  for (let g = 0; g < gap + 2; g++) grown = dilate(grown, W, H);
  minX = Math.max(0, minX - gap - 2);
  minY = Math.max(0, minY - gap - 2);
  maxX = Math.min(W - 1, maxX + gap + 2);
  maxY = Math.min(H - 1, maxY + gap + 2);
  const w = maxX - minX + 1;
  const h = maxY - minY + 1;
  const rgb = /^#?([0-9a-f]{6})$/i.exec(color)?.[1];
  const n = rgb ? parseInt(rgb, 16) : 0;
  const img = new ImageData(w, h);
  for (let yy = 0; yy < h; yy++)
    for (let xx = 0; xx < w; xx++) {
      if (!grown[(yy + minY) * W + xx + minX]) continue;
      const o = (yy * w + xx) * 4;
      img.data[o] = (n >> 16) & 255;
      img.data[o + 1] = (n >> 8) & 255;
      img.data[o + 2] = n & 255;
      img.data[o + 3] = 255;
    }
  const c = makeCanvas(w, h);
  ctx2d(c).putImageData(img, 0, 0);
  const png = await canvasToDataUrl(c);
  return { png, x: minX, y: minY, w, h };
}

function dilate(src: Uint8Array<ArrayBuffer>, W: number, H: number): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(src.length);
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++) {
      const i = y * W + x;
      if (src[i] || (x > 0 && src[i - 1]) || (x < W - 1 && src[i + 1]) || (y > 0 && src[i - W]) || (y < H - 1 && src[i + W])) out[i] = 1;
    }
  return out;
}

export async function canvasToDataUrl(c: AnyCanvas): Promise<string> {
  if ("toDataURL" in c) return c.toDataURL("image/png");
  const blob = await c.convertToBlob({ type: "image/png" });
  return await new Promise<string>((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
}
