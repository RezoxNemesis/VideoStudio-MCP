/**
 * Living picture: animates a still drawing or photo by gently bending the picture itself on a fine mesh
 * (breathing, head movement, hair and cloth in the wind, blinks, idle sway) instead of redrawing anything, so the
 * character's proportions and line work stay exactly as drawn. Pins mark body points as fractions of the image.
 */
import * as THREE from "three";
import type { AnimLayer, AnimScene } from "../core/model";
import { getRenderer } from "./three3d";

export interface LivingPins {
  head: [number, number];
  neck: [number, number];
  chest: [number, number];
  hips: [number, number];
  feet: [number, number];
  eyeL?: [number, number];
  eyeR?: [number, number];
  hairTop?: [number, number];
  /** Head radius as a fraction of the image height (sets falloffs). */
  headR?: number;
  /** Loose cloth (hems, sleeves) that should flutter. */
  cloth?: [number, number][];
}

export interface LivingMotion {
  breathe: number; // 0..2
  sway: number; // body sway around the feet, 0..2
  head: number; // head turn/tilt, 0..2
  hair: number; // 0..2
  cloth: number; // 0..2
  blink: number; // blinks per 10 s (0 = off)
  speed: number; // overall tempo
  /** Look direction keys are optional; head drifts naturally otherwise. */
  seed: number;
}

export interface LivingSpec {
  assetId: string;
  pins: LivingPins;
  motion: LivingMotion;
  /** Where the picture sits in the scene (fit inside). */
  fit?: "contain" | "cover";
}

export const DEFAULT_MOTION: LivingMotion = { breathe: 1, sway: 1, head: 1, hair: 1, cloth: 1, blink: 2.5, speed: 1, seed: 3 };

/** Rough pins for a standing full-body figure from its bounding box (fractions). The agent can refine them. */
export function guessPins(box: { x0: number; y0: number; x1: number; y1: number }): LivingPins {
  const cx = (box.x0 + box.x1) / 2;
  const h = box.y1 - box.y0;
  const y = (f: number) => box.y0 + h * f;
  return { head: [cx, y(0.08)], neck: [cx, y(0.15)], chest: [cx, y(0.27)], hips: [cx, y(0.46)], feet: [cx, y(0.97)], eyeL: [cx - h * 0.025, y(0.085)], eyeR: [cx + h * 0.025, y(0.085)], hairTop: [cx, y(0.02)], headR: h * 0.07 };
}

/** Subject box from a picture on a plain background (corner colour = background). */
export function subjectBox(d: Uint8ClampedArray, W: number, H: number): { x0: number; y0: number; x1: number; y1: number } {
  const bg = [0, 0, 0];
  for (const [x, y] of [[2, 2], [W - 3, 2], [2, H - 3], [W - 3, H - 3]] as const) for (let c = 0; c < 3; c++) bg[c]! += d[(y * W + x) * 4 + c]! / 4;
  let x0 = W,
    y0 = H,
    x1 = 0,
    y1 = 0;
  for (let y = 0; y < H; y += 2)
    for (let x = 0; x < W; x += 2) {
      const i = (y * W + x) * 4;
      const dist = Math.abs(d[i]! - bg[0]!) + Math.abs(d[i + 1]! - bg[1]!) + Math.abs(d[i + 2]! - bg[2]!);
      if (dist > 60) {
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        if (y > y1) y1 = y;
      }
    }
  return x1 > x0 ? { x0: x0 / W, y0: y0 / H, x1: x1 / W, y1: y1 / H } : { x0: 0.2, y0: 0.05, x1: 0.8, y1: 0.95 };
}

/* ------------------------------ deformation -------------------------------- */

const smooth = (e0: number, e1: number, x: number) => {
  const t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
  return t * t * (3 - 2 * t);
};
function noise(t: number, s: number): number {
  return Math.sin(t * 1.13 + s * 1.7) * 0.6 + Math.sin(t * 2.31 + s * 4.1) * 0.3 + Math.sin(t * 4.07 + s * 2.3) * 0.1;
}

/**
 * Displacement (in image fractions, aspect-corrected by `ar` = W/H) of a point at (u, v) at time t.
 * Exported for tests: the feet stay put, the head moves the most, nothing tears.
 */
export function displace(u: number, v: number, t: number, p: LivingPins, m: LivingMotion, ar: number): [number, number] {
  const T = t * m.speed;
  const s = m.seed;
  let dx = 0,
    dy = 0;
  const hr = p.headR ?? Math.abs(p.neck[1] - p.head[1]) * 1.1;
  const bodyH = Math.max(0.05, p.feet[1] - p.head[1]);
  // 1) Whole-body sway pivoting at the feet: weight grows with height above the feet.
  const up = smooth(0, 1, (p.feet[1] - v) / bodyH);
  const sway = (noise(T * 0.35, s) * 0.012 * m.sway) * up * up;
  const rot = sway; // radians-ish
  const rx = (u - p.feet[0]) * ar,
    ry = v - p.feet[1];
  dx += (-ry * rot) / ar;
  dy += rx * rot * 0.3;
  // Only the figure moves: fade with horizontal distance from the body axis.
  const axis = Math.abs(u - p.chest[0]) * ar;
  const onBody = 1 - smooth(hr * 2.2, hr * 4.5, axis);
  dx *= onBody;
  dy *= onBody;
  // 2) Breathing: chest and shoulders rise and expand slightly.
  const br = Math.sin(T * 1.6) * 0.5 + 0.5;
  const dChest = Math.hypot((u - p.chest[0]) * ar, (v - p.chest[1]) * 1.2);
  const wChest = 1 - smooth(0, hr * 2.6, dChest);
  dy += -0.0035 * m.breathe * br * wChest;
  dx += (u - p.chest[0]) * 0.012 * m.breathe * br * wChest;
  // 3) Head: tilt around the neck + small nods; the face region follows rigidly, falling off below the neck.
  const tilt = noise(T * 0.5, s + 7) * 0.035 * m.head;
  const nod = noise(T * 0.42, s + 11) * 0.006 * m.head;
  const dHead = Math.hypot((u - p.head[0]) * ar, v - p.head[1]);
  const wHead = (1 - smooth(hr * 1.1, hr * 2.0, dHead)) * smooth(p.neck[1] + hr * 0.4, p.neck[1] - hr * 0.2, v);
  const hx = (u - p.neck[0]) * ar,
    hy = v - p.neck[1];
  dx += ((Math.cos(tilt) * hx - Math.sin(tilt) * hy - hx) / ar) * wHead;
  dy += (Math.sin(tilt) * hx + Math.cos(tilt) * hy - hy + nod) * wHead;
  // 4) Hair: wind ripples strongest at the top and edges of the hair mass.
  if (p.hairTop) {
    const dHair = Math.hypot((u - p.hairTop[0]) * ar, (v - p.hairTop[1]) * 1.3);
    const wHair = (1 - smooth(hr * 0.6, hr * 1.6, dHair)) * (0.4 + 0.6 * smooth(0, hr, Math.abs(u - p.hairTop[0]) * ar + (p.hairTop[1] - v + hr) * 0.5));
    dx += (Math.sin(T * 3.1 + v * 40 + s) * 0.6 + Math.sin(T * 5.3 + u * 30) * 0.4) * 0.0028 * m.hair * wHair;
    dy += Math.sin(T * 2.7 + u * 35 + s) * 0.0012 * m.hair * wHair;
  }
  // 5) Cloth flutter around hems.
  for (const c of p.cloth ?? []) {
    const dc = Math.hypot((u - c[0]) * ar, v - c[1]);
    const w = 1 - smooth(0, hr * 1.6, dc);
    dx += Math.sin(T * 2.2 + v * 25 + c[0] * 9) * 0.0022 * m.cloth * w;
    dy += Math.sin(T * 1.8 + u * 21) * 0.0009 * m.cloth * w;
  }
  // 6) Blinks: squash each eye vertically toward its centre for ~0.14 s.
  if (m.blink > 0 && p.eyeL && p.eyeR) {
    const period = 10 / m.blink;
    const ph = (T + (s % 3)) % period;
    const k = ph < 0.14 ? Math.sin((ph / 0.14) * Math.PI) : 0;
    if (k > 0) {
      const er = hr * 0.3;
      for (const e of [p.eyeL, p.eyeR]) {
        // Eyes are wider than tall: a flat elliptical falloff closes the whole eye, lids meet in the middle.
        const de = Math.hypot((u - e[0]) * ar * 0.55, (v - e[1]) * 1.6);
        const w = 1 - smooth(er * 0.55, er * 1.2, de);
        dy += (e[1] - v) * 0.9 * k * w;
      }
    }
  }
  return [dx, dy];
}

/* -------------------------------- rendering -------------------------------- */

const meshes = new Map<string, { scene: THREE.Scene; mesh: THREE.Mesh; base: Float32Array; tex: THREE.Texture; src: unknown; cols: number; rows: number }>();
const cam = new THREE.OrthographicCamera(0, 1, 0, -1, -1, 1);

export function drawLiving(c: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, l: AnimLayer, s: AnimScene, f: number, W: number, H: number, img: CanvasImageSource | null): void {
  const sp = l.living;
  if (!sp || !img) return;
  const iw = (img as { width: number }).width,
    ih = (img as { height: number }).height;
  const r = getRenderer();
  // Placement inside the scene.
  const k = sp.fit === "cover" ? Math.max(W / iw, H / ih) : Math.min(W / iw, H / ih);
  const dw = iw * k,
    dh = ih * k;
  const ox = (W - dw) / 2,
    oy = (H - dh) / 2;
  if (!r) {
    c.save();
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.drawImage(img, ox, oy, dw, dh);
    c.restore();
    return;
  }
  let e = meshes.get(l.id);
  if (!e || e.src !== img) {
    const cols = 64,
      rows = Math.round((64 * ih) / iw);
    const geo = new THREE.PlaneGeometry(1, 1, cols, rows);
    geo.translate(0.5, -0.5, 0); // u right, v down → x = u, y = -v
    // Copy into a canvas: ImageBitmap uploads ignore flipY, canvases honour it.
    const cv = typeof OffscreenCanvas !== "undefined" ? new OffscreenCanvas(iw, ih) : Object.assign(document.createElement("canvas"), { width: iw, height: ih });
    (cv.getContext("2d") as CanvasRenderingContext2D).drawImage(img, 0, 0);
    const tex = new THREE.CanvasTexture(cv as HTMLCanvasElement);
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.minFilter = THREE.LinearMipmapLinearFilter;
    tex.generateMipmaps = true;
    tex.needsUpdate = true;
    const mesh = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ map: tex, transparent: true }));
    const scene = new THREE.Scene();
    scene.add(mesh);
    e = { scene, mesh, base: Float32Array.from(geo.attributes.position!.array as Float32Array), tex, src: img, cols, rows };
    meshes.set(l.id, e);
  }
  const pos = e.mesh.geometry.attributes.position as THREE.BufferAttribute;
  const arr = pos.array as Float32Array;
  const t = f / s.fps;
  const ar = iw / ih;
  for (let i = 0; i < arr.length; i += 3) {
    const u = e.base[i]!,
      v = -e.base[i + 1]!;
    const [dx, dy] = displace(u, v, t, sp.pins, sp.motion, ar);
    arr[i] = u + dx;
    arr[i + 1] = -(v + dy);
  }
  pos.needsUpdate = true;
  const pw = Math.max(1, Math.round(dw)),
    ph = Math.max(1, Math.round(dh));
  if (r.domElement.width !== pw || r.domElement.height !== ph) r.setSize(pw, ph, false);
  r.setClearColor(0x000000, 0);
  r.toneMapping = THREE.NoToneMapping;
  r.render(e.scene, cam);
  c.save();
  c.setTransform(1, 0, 0, 1, 0, 0);
  c.drawImage(r.domElement as CanvasImageSource, ox, oy, dw, dh);
  c.restore();
}
