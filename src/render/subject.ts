/**
 * Subject looks: everything editors build by hand from a person cut-out, done from the stored matte —
 *   cutout · background · fill (texture inside the silhouette) · neon (glowing animated outline) · double
 *   (double exposure) · halo (light behind the subject) · replace (new background).
 * Textures are another clip's picture/video or generated live: galaxy, fire, aurora, kaleido, glass, grid.
 */
import { ctx2d, makeCanvas } from "../anim/render";

type AnyCanvas = ReturnType<typeof makeCanvas>;
type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;

export type SubjectMode = "cutout" | "background" | "fill" | "neon" | "double" | "halo" | "replace";
export interface SubjectSpec {
  mode: SubjectMode;
  /** Texture: an asset id, or a generated one: galaxy | fire | aurora | kaleido | glass | grid. */
  texture?: string;
  /** Background: keep | dark | black | white | blur | transparent | #hex. */
  bg?: string;
  color?: string;
  color2?: string;
  /** Effect strength 0..1 (texture mix, glow, exposure blend). */
  amount?: number;
  /** Neon line width as a fraction of the frame (default 0.006). */
  edge?: number;
  /** Seconds for the outline / fill to draw on from the clip start (0 = already on). */
  drawOn?: number;
  /** Keep some of the person's own shading over the texture (0..1). */
  detail?: number;
}

export const GENERATED_TEXTURES = ["galaxy", "fire", "aurora", "kaleido", "glass", "grid"] as const;

const hash = (n: number) => {
  const s = Math.sin(n * 127.1 + 311.7) * 43758.5453;
  return s - Math.floor(s);
};

/* --------------------------------- textures -------------------------------- */

const texCanvases = new Map<string, AnyCanvas>();
function texCanvas(key: string, w: number, h: number): { c: AnyCanvas; x: Ctx } {
  let c = texCanvases.get(key);
  if (!c || c.width !== w || c.height !== h) {
    c = makeCanvas(w, h);
    texCanvases.set(key, c);
  }
  return { c, x: ctx2d(c) };
}

/** A generated, animated texture at w×h. `src` is the clip's own frame (for kaleido). */
export function generatedTexture(name: string, w: number, h: number, t: number, src: CanvasImageSource, col = "#7c5cff", col2 = "#18e0ff"): AnyCanvas {
  const { c, x } = texCanvas(`gen:${name}`, w, h);
  x.save();
  x.globalCompositeOperation = "source-over";
  x.filter = "none";
  const M = Math.max(w, h);
  switch (name) {
    case "galaxy": {
      x.fillStyle = "#04030c";
      x.fillRect(0, 0, w, h);
      for (let i = 0; i < 5; i++) {
        const cx = (0.5 + 0.35 * Math.sin(t * 0.07 + i * 1.9)) * w,
          cy = (0.5 + 0.35 * Math.cos(t * 0.05 + i * 2.7)) * h;
        const g = x.createRadialGradient(cx, cy, 0, cx, cy, M * (0.35 + 0.15 * hash(i)));
        const cc = [col, col2, "#ff3fa4", "#2b59ff", "#9b4dff"][i]!;
        g.addColorStop(0, cc + "aa");
        g.addColorStop(1, cc + "00");
        x.fillStyle = g;
        x.fillRect(0, 0, w, h);
      }
      x.globalCompositeOperation = "lighter";
      for (let i = 0; i < 420; i++) {
        const sx = ((hash(i * 3.1) + t * 0.004 * (0.3 + hash(i))) % 1) * w,
          sy = hash(i * 7.7) * h;
        const tw = 0.4 + 0.6 * Math.abs(Math.sin(t * (1 + hash(i) * 3) + i));
        const r = Math.max(0.6, M * 0.0016 * Math.pow(hash(i * 5.5), 3) * 3);
        x.fillStyle = `rgba(255,255,255,${tw})`;
        x.beginPath();
        x.arc(sx, sy, r, 0, Math.PI * 2);
        x.fill();
      }
      break;
    }
    case "fire": {
      const g = x.createLinearGradient(0, h, 0, 0);
      g.addColorStop(0, "#ffef9a");
      g.addColorStop(0.35, "#ff8a1a");
      g.addColorStop(0.7, "#c0180c");
      g.addColorStop(1, "#1a0302");
      x.fillStyle = g;
      x.fillRect(0, 0, w, h);
      x.globalCompositeOperation = "lighter";
      for (let i = 0; i < 70; i++) {
        const life = 1.2 + hash(i) * 1.6;
        const ph = ((t + hash(i * 9) * life) % life) / life;
        const fx = hash(i * 4.4) * w + Math.sin(t * 3 + i) * w * 0.03;
        const fy = h * (1.05 - ph * 1.2);
        const r = M * (0.05 + 0.08 * hash(i * 2)) * (1 - ph * 0.6);
        const fg = x.createRadialGradient(fx, fy, 0, fx, fy, r);
        fg.addColorStop(0, `rgba(255,200,80,${0.35 * (1 - ph)})`);
        fg.addColorStop(1, "rgba(255,80,0,0)");
        x.fillStyle = fg;
        x.fillRect(fx - r, fy - r, r * 2, r * 2);
      }
      break;
    }
    case "aurora": {
      x.fillStyle = "#020814";
      x.fillRect(0, 0, w, h);
      x.globalCompositeOperation = "lighter";
      for (let b = 0; b < 4; b++) {
        x.beginPath();
        for (let i = 0; i <= 40; i++) {
          const px = (i / 40) * w;
          const py = h * (0.3 + 0.12 * b) + Math.sin(i * 0.35 + t * (0.6 + b * 0.2) + b) * h * 0.08;
          if (i === 0) x.moveTo(px, py);
          else x.lineTo(px, py);
        }
        x.strokeStyle = b % 2 ? col2 + "66" : col + "66";
        x.lineWidth = M * 0.08;
        x.filter = `blur(${Math.round(M * 0.03)}px)`;
        x.stroke();
      }
      break;
    }
    case "kaleido": {
      // Mandala from the clip's own picture: 12 mirrored wedges, slowly turning.
      x.fillStyle = "#000";
      x.fillRect(0, 0, w, h);
      const n = 12;
      const R = M * 0.75;
      for (let i = 0; i < n; i++) {
        x.save();
        x.translate(w / 2, h / 2);
        x.rotate((i * Math.PI * 2) / n + t * 0.15);
        if (i % 2) x.scale(1, -1);
        x.beginPath();
        x.moveTo(0, 0);
        x.arc(0, 0, R, -Math.PI / n, Math.PI / n);
        x.closePath();
        x.clip();
        x.rotate(-t * 0.1);
        x.drawImage(src, -R * 0.2, -R * 0.5, R, R);
        x.restore();
      }
      break;
    }
    case "glass": {
      // Stained glass: coloured cells with dark leading.
      const cells = 40;
      const pts = Array.from({ length: cells }, (_, i) => [hash(i * 1.3) * w + Math.sin(t * 0.3 + i) * w * 0.01, hash(i * 7.9) * h + Math.cos(t * 0.25 + i) * h * 0.01] as const);
      const palette = [col, col2, "#ffcc33", "#ff4d6d", "#35d07f", "#3a7bff", "#b14dff"];
      const step = Math.max(3, Math.round(M / 160));
      for (let y = 0; y < h; y += step)
        for (let xx = 0; xx < w; xx += step) {
          let best = 0,
            bd = 1e18,
            sd = 1e18;
          for (let k = 0; k < cells; k++) {
            const d = (pts[k]![0] - xx) ** 2 + (pts[k]![1] - y) ** 2;
            if (d < bd) {
              sd = bd;
              bd = d;
              best = k;
            } else if (d < sd) sd = d;
          }
          const edge = Math.sqrt(sd) - Math.sqrt(bd) < M * 0.006;
          x.fillStyle = edge ? "#120a06" : palette[best % palette.length]!;
          x.fillRect(xx, y, step, step);
        }
      x.globalCompositeOperation = "soft-light";
      const g = x.createRadialGradient(w * 0.4, h * 0.3, 0, w * 0.4, h * 0.3, M);
      g.addColorStop(0, `rgba(255,255,255,${0.5 + 0.2 * Math.sin(t)})`);
      g.addColorStop(1, "rgba(0,0,0,0.4)");
      x.fillStyle = g;
      x.fillRect(0, 0, w, h);
      break;
    }
    case "grid": {
      x.fillStyle = "#05020f";
      x.fillRect(0, 0, w, h);
      x.strokeStyle = col2;
      x.lineWidth = Math.max(1, M * 0.003);
      x.shadowColor = col2;
      x.shadowBlur = M * 0.01;
      const gs = M / 14;
      const off = (t * gs * 0.6) % gs;
      for (let y = -gs + off; y < h + gs; y += gs) {
        x.beginPath();
        x.moveTo(0, y);
        x.lineTo(w, y);
        x.stroke();
      }
      for (let xx = 0; xx < w + gs; xx += gs) {
        x.beginPath();
        x.moveTo(xx, 0);
        x.lineTo(xx, h);
        x.stroke();
      }
      break;
    }
  }
  x.restore();
  return c;
}

/* ---------------------------------- helpers -------------------------------- */

function matteCanvas(key: string, alpha: Uint8Array, mw: number, mh: number, tint = "#ffffff"): AnyCanvas {
  const { c, x } = texCanvas(`m:${key}`, mw, mh);
  const id = x.createImageData(mw, mh);
  const n = parseInt(tint.slice(1, 7), 16);
  const r = (n >> 16) & 255,
    g = (n >> 8) & 255,
    b = n & 255;
  for (let i = 0; i < mw * mh; i++) {
    id.data[i * 4] = r;
    id.data[i * 4 + 1] = g;
    id.data[i * 4 + 2] = b;
    id.data[i * 4 + 3] = alpha[i]!;
  }
  x.putImageData(id, 0, 0);
  return c;
}

/** Thin band along the matte's edge (alpha), for outlines. */
function edgeAlpha(alpha: Uint8Array, w: number, h: number, r: number): Uint8Array {
  const bin = new Uint8Array(w * h);
  for (let i = 0; i < w * h; i++) bin[i] = alpha[i]! > 127 ? 1 : 0;
  const out = new Uint8Array(w * h);
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const v = bin[y * w + x]!;
      let edge = false;
      for (let dy = -r; dy <= r && !edge; dy++)
        for (let dx = -r; dx <= r; dx++) {
          const yy = y + dy,
            xx = x + dx;
          if (yy < 0 || xx < 0 || yy >= h || xx >= w) continue;
          if (bin[yy * w + xx] !== v) {
            edge = true;
            break;
          }
        }
      out[y * w + x] = edge ? 255 : 0;
    }
  return out;
}

function centroid(alpha: Uint8Array, w: number, h: number): { x: number; y: number; top: number; size: number } {
  let sx = 0,
    sy = 0,
    n = 0,
    top = h,
    x0 = w,
    x1 = 0;
  for (let y = 0; y < h; y += 2)
    for (let x = 0; x < w; x += 2)
      if (alpha[y * w + x]! > 127) {
        sx += x;
        sy += y;
        n++;
        if (y < top) top = y;
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
      }
  if (!n) return { x: 0.5, y: 0.5, top: 0.2, size: 0.4 };
  return { x: sx / n / w, y: sy / n / h, top: top / h, size: Math.max(0.1, (x1 - x0) / w) };
}

function background(x: Ctx, src: CanvasImageSource, W: number, H: number, bg: string | undefined, tex?: CanvasImageSource) {
  const b = bg ?? "keep";
  x.save();
  if (b === "keep") x.drawImage(src, 0, 0, W, H);
  else if (b === "dark") {
    x.filter = "brightness(0.35) saturate(0.7)";
    x.drawImage(src, 0, 0, W, H);
  } else if (b === "blur") {
    x.filter = `blur(${Math.round(Math.max(W, H) / 60)}px) brightness(0.8)`;
    x.drawImage(src, -W * 0.05, -H * 0.05, W * 1.1, H * 1.1);
  } else if (b === "texture" && tex) x.drawImage(tex, 0, 0, W, H);
  else if (b === "transparent") {
    /* nothing */
  } else {
    x.fillStyle = b === "black" ? "#000" : b === "white" ? "#f4f1ea" : b;
    x.fillRect(0, 0, W, H);
  }
  x.restore();
}

/* ---------------------------------- render --------------------------------- */

const outs = new Map<string, AnyCanvas>();

/**
 * Draw the subject look for one frame. `local` is seconds since the clip started (for draw-on), `t` the
 * animation clock.
 */
export function renderSubject(key: string, src: CanvasImageSource, W: number, H: number, alpha: Uint8Array, mw: number, mh: number, sp: SubjectSpec, t: number, local: number, texSrc?: CanvasImageSource | null): AnyCanvas {
  let out = outs.get(key);
  if (!out || out.width !== W || out.height !== H) {
    out = makeCanvas(W, H);
    outs.set(key, out);
  }
  const x = ctx2d(out);
  x.save();
  x.clearRect(0, 0, W, H);
  x.imageSmoothingEnabled = true;
  x.imageSmoothingQuality = "high";
  const amount = sp.amount ?? 1;
  const col = sp.color ?? "#ffd23f";
  const col2 = sp.color2 ?? "#18e0ff";
  const reveal = sp.drawOn ? Math.max(0, Math.min(1, local / sp.drawOn)) : 1;
  const tex = (): CanvasImageSource => texSrc ?? generatedTexture(sp.texture ?? "galaxy", Math.min(720, W), Math.round((Math.min(720, W) * H) / W), t, src, sp.color ?? "#7c5cff", col2);
  const mc = () => matteCanvas(key, alpha, mw, mh);
  // A layer that holds "something cut to the subject".
  const cutTo = (draw: (c: Ctx) => void, k2 = "cut") => {
    const { c, x: cx } = texCanvas(`${key}:${k2}`, W, H);
    cx.save();
    cx.clearRect(0, 0, W, H);
    cx.globalCompositeOperation = "source-over";
    draw(cx);
    cx.globalCompositeOperation = "destination-in";
    cx.drawImage(mc() as CanvasImageSource, 0, 0, W, H);
    cx.restore();
    return c;
  };
  switch (sp.mode) {
    case "cutout": {
      background(x, src, W, H, sp.bg ?? "transparent");
      x.drawImage(cutTo((c) => c.drawImage(src, 0, 0, W, H)) as CanvasImageSource, 0, 0);
      break;
    }
    case "background": {
      x.drawImage(src, 0, 0, W, H);
      x.globalCompositeOperation = "destination-out";
      x.drawImage(mc() as CanvasImageSource, 0, 0, W, H);
      break;
    }
    case "replace": {
      background(x, src, W, H, sp.bg ?? "blur", sp.texture ? tex() : undefined);
      x.drawImage(cutTo((c) => c.drawImage(src, 0, 0, W, H)) as CanvasImageSource, 0, 0);
      break;
    }
    case "fill": {
      background(x, src, W, H, sp.bg ?? "dark");
      const T = tex();
      const filled = cutTo((c) => {
        c.drawImage(T, 0, 0, W, H);
        // Keep the person's shading so it still reads as a body, not a flat shape.
        const det = sp.detail ?? 0.55;
        if (det > 0) {
          c.globalAlpha = det;
          c.globalCompositeOperation = "soft-light";
          c.filter = "grayscale(1) contrast(1.35)";
          c.drawImage(src, 0, 0, W, H);
          c.filter = "none";
          c.globalAlpha = 1;
          c.globalCompositeOperation = "source-over";
        }
      }, "fill");
      x.save();
      if (reveal < 1) {
        // Fill rises up through the body.
        x.beginPath();
        x.rect(0, H * (1 - reveal), W, H * reveal);
        x.clip();
      }
      x.globalAlpha = amount;
      x.shadowColor = col2;
      x.shadowBlur = Math.max(W, H) * 0.02;
      x.drawImage(filled as CanvasImageSource, 0, 0);
      x.restore();
      if (amount < 1) {
        x.globalAlpha = 1 - amount;
        x.drawImage(cutTo((c) => c.drawImage(src, 0, 0, W, H), "orig") as CanvasImageSource, 0, 0);
      }
      break;
    }
    case "neon": {
      background(x, src, W, H, sp.bg ?? "dark");
      const r = Math.max(1, Math.round((sp.edge ?? 0.006) * mw));
      const e = edgeAlpha(alpha, mw, mh, r);
      // Draw-on: the line grows from the top of the figure down.
      if (reveal < 1) {
        const cut = Math.floor(mh * reveal);
        e.fill(0, cut * mw);
      }
      const ec = matteCanvas(`${key}:edge`, e, mw, mh, col);
      x.globalCompositeOperation = "lighter";
      const M = Math.max(W, H);
      for (const [blur, a] of [
        [M * 0.03, 0.9],
        [M * 0.012, 1],
        [0, 1],
      ] as const) {
        x.save();
        x.globalAlpha = a * amount * (0.85 + 0.15 * Math.sin(t * 9));
        if (blur) x.filter = `blur(${Math.round(blur)}px)`;
        x.drawImage(ec as CanvasImageSource, 0, 0, W, H);
        x.restore();
      }
      // White-hot core.
      const core = matteCanvas(`${key}:core`, e, mw, mh, "#ffffff");
      x.globalAlpha = 0.6 * amount;
      x.drawImage(core as CanvasImageSource, 0, 0, W, H);
      break;
    }
    case "double": {
      // Light paper, the person in soft monochrome, the second picture living inside them.
      background(x, src, W, H, sp.bg ?? "white");
      const T = tex();
      const person = cutTo((c) => {
        c.filter = "grayscale(1) contrast(1.15) brightness(1.08)";
        c.drawImage(src, 0, 0, W, H);
        c.filter = "none";
        c.globalCompositeOperation = "screen";
        c.globalAlpha = amount;
        c.drawImage(T, 0, 0, W, H);
      }, "dbl");
      x.drawImage(person as CanvasImageSource, 0, 0);
      // Soft fade at the bottom like print double exposures.
      const g = x.createLinearGradient(0, H * 0.7, 0, H);
      g.addColorStop(0, "rgba(244,241,234,0)");
      g.addColorStop(1, "rgba(244,241,234,1)");
      x.fillStyle = sp.bg && sp.bg !== "white" ? "rgba(0,0,0,0)" : g;
      x.fillRect(0, H * 0.7, W, H * 0.3);
      break;
    }
    case "halo": {
      background(x, src, W, H, sp.bg ?? "dark");
      const c = centroid(alpha, mw, mh);
      const cx = c.x * W,
        cy = (c.top + 0.12) * H;
      const R = Math.max(W, H) * (0.22 + 0.1 * c.size) * (0.95 + 0.05 * Math.sin(t * 1.5)) * (0.3 + 0.7 * reveal);
      const g = x.createRadialGradient(cx, cy, 0, cx, cy, R);
      g.addColorStop(0, "rgba(255,255,255,0.95)");
      g.addColorStop(0.25, col2 + "cc");
      g.addColorStop(0.6, col2 + "33");
      g.addColorStop(1, col2 + "00");
      x.globalCompositeOperation = "lighter";
      x.globalAlpha = amount;
      x.fillStyle = g;
      x.fillRect(0, 0, W, H);
      x.globalCompositeOperation = "source-over";
      x.globalAlpha = 1;
      // Subject as a dark silhouette with a thin rim of the halo's light.
      const sil = cutTo((cc) => {
        cc.filter = "brightness(0.25)";
        cc.drawImage(src, 0, 0, W, H);
        cc.filter = "none";
      }, "sil");
      x.drawImage(sil as CanvasImageSource, 0, 0);
      const rim = matteCanvas(`${key}:rim`, edgeAlpha(alpha, mw, mh, 1), mw, mh, col2);
      x.globalCompositeOperation = "lighter";
      x.globalAlpha = 0.5 * amount;
      x.filter = `blur(${Math.round(Math.max(W, H) / 300)}px)`;
      x.drawImage(rim as CanvasImageSource, 0, 0, W, H);
      break;
    }
  }
  x.restore();
  return out;
}
