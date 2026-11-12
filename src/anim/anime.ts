/**
 * Anime action toolkit for 2D scenes: the timing and camera tricks that make hand-drawn action hit.
 *
 * Per layer: hold poses on twos or threes, trail afterimages behind fast motion, wrap the silhouette in an aura.
 * Per scene: hit-stop (the picture freezes for a few frames at an impact), camera shake, zoom punches and impact
 * flashes. Everything here is plain numbers on the scene, read by the renderer; nothing is baked into the drawings,
 * so every beat can be retimed or removed.
 */
import type { AnimLayer } from "../core/model";

export interface LayerAnime {
  /** Hold each pose for this many frames: 2 = on twos, 3 = on threes. */
  step?: number;
  /** Afterimages: earlier frames of the layer trail behind it. `when`: only inside these frame windows. */
  trail?: { count: number; gap: number; opacity?: number; tint?: string; when?: [number, number][] };
  /** A glow around the layer's silhouette that flickers and rises (power-up, rage, spirit energy). */
  aura?: { color: string; size?: number; strength?: number; flicker?: number; when?: [number, number][] };
}

export interface SceneAnime {
  /** Hit-stop: [frame, frames held]. The drawing freezes; effects keep moving. */
  holds?: [number, number][];
  /** Camera shake: [frame, frames, strength (1 = a solid hit)]. */
  shakes?: [number, number, number][];
  /** Zoom punch: [frame, frames, amount (0.12 = 12 %), centre x (0..1)?, centre y?]. A fast push-in that eases back. */
  punches?: [number, number, number, number?, number?][];
  /** Flash: [frame, frames, "white" | "black" | "invert"]. */
  flashes?: [number, number, string][];
}

const clamp = (x: number, a = 0, b = 1) => Math.max(a, Math.min(b, x));
function hash(n: number): number {
  const s = Math.sin(n * 127.1 + 311.7) * 43758.5453;
  return s - Math.floor(s);
}

/** The frame the drawings show at `frame`: inside a hit-stop the picture stays on the frame where it began. */
export function heldFrame(a: SceneAnime | undefined, frame: number): number {
  if (!a?.holds) return frame;
  for (const [f0, n] of a.holds) if (frame >= f0 && frame < f0 + n) return f0;
  return frame;
}

const inWindow = (when: [number, number][] | undefined, frame: number) => !when || !when.length || when.some(([a, b]) => frame >= a && frame < b);

/** The frame a layer draws: hit-stops freeze drawings (effects run on), and a stepped layer holds its poses. */
export function layerFrame(l: Pick<AnimLayer, "kind" | "anime" | "span">, frame: number, a: SceneAnime | undefined): number {
  let fr = l.kind === "proc" ? frame : heldFrame(a, frame);
  const step = Math.round(l.anime?.step ?? 1);
  if (step > 1) {
    const base = l.span?.[0] ?? 0;
    fr = base + Math.floor((fr - base) / step) * step;
  }
  return fr;
}

/** Afterimage frames for a layer at `frame` (oldest first), each with its opacity. */
export function trailFrames(l: Pick<AnimLayer, "anime" | "span">, frame: number): { frame: number; alpha: number }[] {
  const tr = l.anime?.trail;
  if (!tr || tr.count < 1 || !inWindow(tr.when, frame)) return [];
  const out: { frame: number; alpha: number }[] = [];
  const gap = Math.max(1, Math.round(tr.gap)), first = l.span?.[0] ?? 0;
  for (let k = Math.min(12, Math.round(tr.count)); k >= 1; k--) {
    const f = frame - k * gap;
    if (f < first) continue;
    out.push({ frame: f, alpha: (tr.opacity ?? 0.5) * (1 - (k - 1) / (Math.round(tr.count) + 0.5)) });
  }
  return out;
}

/** The aura of a layer at `frame`: how strong it is right now (it flickers on twos), or null when it is off. */
export function auraAt(l: Pick<AnimLayer, "anime">, frame: number): { color: string; size: number; strength: number } | null {
  const au = l.anime?.aura;
  if (!au || !inWindow(au.when, frame)) return null;
  const two = Math.floor(frame / 2);
  const fl = clamp(au.flicker ?? 0.35);
  return { color: au.color, size: au.size ?? 0.035, strength: clamp((au.strength ?? 0.8) * (1 - fl * hash(two * 1.7 + 3))) };
}

export interface CameraKick {
  /** Pixels (for a frame W×H). */
  dx: number;
  dy: number;
  zoom: number;
  /** Zoom centre in pixels. */
  cx: number;
  cy: number;
}

/** Shake and zoom punch at `frame` for a picture of W×H pixels; null when the camera is at rest. */
export function cameraAt(a: SceneAnime | undefined, frame: number, W: number, H: number): CameraKick | null {
  if (!a) return null;
  let dx = 0, dy = 0, zoom = 1, cx = W / 2, cy = H / 2, any = false;
  const unit = Math.min(W, H) / 1080;
  for (const [f0, n, strength] of a.shakes ?? []) {
    if (frame < f0 || frame >= f0 + n) continue;
    const u = (frame - f0) / Math.max(1, n);
    const amp = 16 * strength * unit * Math.pow(1 - u, 1.4);
    dx += (hash(frame * 7.31 + f0) - 0.5) * 2 * amp;
    dy += (hash(frame * 11.17 + f0 + 5) - 0.5) * 2 * amp;
    // a little push-in hides the frame edge while the picture is off-centre
    zoom = Math.max(zoom, 1 + (2.2 * amp) / Math.min(W, H));
    any = true;
  }
  for (const [f0, n, amount, px, py] of a.punches ?? []) {
    if (frame < f0 || frame >= f0 + n) continue;
    const u = (frame - f0) / Math.max(1, n);
    // in fast (the first fifth), out slowly
    const env = u < 0.2 ? 1 - Math.pow(1 - u / 0.2, 3) : Math.pow(1 - (u - 0.2) / 0.8, 2);
    zoom *= 1 + amount * env;
    cx = (px ?? 0.5) * W;
    cy = (py ?? 0.5) * H;
    any = true;
  }
  return any ? { dx, dy, zoom, cx, cy } : null;
}

/** The flash at `frame`: a colour wash with its opacity, or an inverted picture. */
export function flashAt(a: SceneAnime | undefined, frame: number): { kind: "white" | "black" | "invert"; alpha: number } | null {
  let best: { kind: "white" | "black" | "invert"; alpha: number } | null = null;
  for (const [f0, n, kind] of a?.flashes ?? []) {
    if (frame < f0 || frame >= f0 + n) continue;
    const u = (frame - f0) / Math.max(1, n);
    const k = kind === "black" || kind === "invert" ? kind : "white";
    const alpha = k === "invert" ? 1 : Math.pow(1 - u, 1.6);
    if (!best || alpha > best.alpha) best = { kind: k, alpha };
  }
  return best;
}
