/**
 * Living picture: animates a still drawing or photo by gently bending the picture itself on a fine mesh
 * (breathing, head movement, hair and cloth in the wind, blinks, idle sway) instead of redrawing anything, so the
 * character's proportions and line work stay exactly as drawn. Pins mark body points as fractions of the image.
 */
import * as THREE from "three";
import type { AnimLayer, AnimScene } from "../core/model";
import { getRenderer } from "./three3d";
import { pullPush } from "../render/inpaint";

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

/** 2.5D: a depth map turns the still into layered space — parallax camera, depth of field and wind. */
export interface DepthCamKey {
  t: number;
  /** Camera shift (fractions of the frame); near things move more than far things. */
  x?: number;
  y?: number;
  /** Dolly: >0 pushes in (near grows faster than far). */
  dolly?: number;
  /** Plain zoom of the whole picture. */
  zoom?: number;
  /** Depth in focus, 0 (far) .. 1 (near). */
  focus?: number;
}
export interface DepthSpec {
  /** Depth map as a PNG data URL (white = near), any size. */
  map: string;
  cam: DepthCamKey[];
  /** Depth of field strength (0 = everything sharp). */
  aperture?: number;
  /** Parallax strength. */
  parallax?: number;
  /** Wind on near things (grass, cloth, hair): amount 0..2, speed. */
  wind?: { amount: number; speed?: number; near?: number };
  /** Gentle light breathing (flicker / clouds passing) 0..1. */
  light?: number;
}

export interface LivingSpec {
  assetId: string;
  /** Body pins (optional when only depth motion is wanted). */
  pins?: LivingPins;
  motion: LivingMotion;
  depth?: DepthSpec;
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
  if (sp.depth) {
    drawDepth(c, l, sp, s, f, W, H, img, r, ox, oy, dw, dh);
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
    const [dx, dy] = sp.pins ? displace(u, v, t, sp.pins, sp.motion, ar) : [0, 0];
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

/* --------------------------------- 2.5D depth -------------------------------- */

const depthCache = new Map<string, { scene: THREE.Scene; mat: THREE.ShaderMaterial; src: unknown; map: string; base: Float32Array; mesh: THREE.Mesh; ready: boolean }>();

const VERT = `
uniform sampler2D uDepth;
uniform vec2 uShift;
uniform float uDolly;
uniform float uZoom;
uniform float uParallax;
uniform float uFocus;
varying vec2 vUv;
varying float vDepth;
void main() {
  vUv = uv;
  float d = texture2D(uDepth, uv).r;
  vDepth = d;
  vec3 p = position;
  // Screen-space centre is (0.5,-0.5). Near points (d→1) move and grow more than far ones (d→0).
  vec2 c = vec2(0.5, -0.5);
  float rel = d - uFocus;
  p.xy = c + (p.xy - c) * (uZoom * (1.0 + uDolly * d));
  p.xy += vec2(uShift.x, -uShift.y) * rel * uParallax;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
}`;

const FRAG = `
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform sampler2D uBlur2;
uniform sampler2D uBgBlur;
uniform sampler2D uBgBlur2;
uniform sampler2D uDepth;
uniform float uFocus;
uniform float uAperture;
uniform float uTime;
uniform float uWind;
uniform float uWindSpeed;
uniform float uWindNear;
uniform float uLight;
varying vec2 vUv;
varying float vDepth;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}
void main() {
  vec2 uv = vUv;
  float d = texture2D(uDepth, uv).r;
  // Wind: near things sway; gusts travel across the picture.
  float w = smoothstep(uWindNear, 1.0, d) * uWind;
  if (w > 0.0) {
    float gust = 0.5 + 0.5 * noise(vec2(uv.x * 2.0 - uTime * 0.35 * uWindSpeed, uTime * 0.2));
    float sway = sin(uTime * 2.2 * uWindSpeed + uv.x * 18.0 + noise(uv * 9.0) * 6.0) * 0.6 + sin(uTime * 3.7 * uWindSpeed + uv.y * 30.0) * 0.4;
    uv.x += sway * gust * w * 0.006;
    uv.y += sway * gust * w * 0.002;
  }
  vec4 sharp = texture2D(uTex, uv);
  float coc = clamp(abs(d - uFocus) * uAperture, 0.0, 1.0);
  // Behind the focus plane, blur the background-only picture so near subjects don't smear dark halos into it.
  bool behind = d < uFocus;
  vec4 b1 = behind ? texture2D(uBgBlur, uv) : texture2D(uBlur, uv);
  vec4 b2 = behind ? texture2D(uBgBlur2, uv) : texture2D(uBlur2, uv);
  vec4 col = coc < 0.5 ? mix(sharp, b1, coc * 2.0) : mix(b1, b2, (coc - 0.5) * 2.0);
  // Light breathing: soft moving brightness (clouds, flicker).
  float lb = 1.0 + uLight * 0.08 * (noise(uv * 2.0 + vec2(uTime * 0.07, uTime * 0.05)) - 0.5);
  gl_FragColor = vec4(col.rgb * lb, col.a);
}`;

function canvasOf(img: CanvasImageSource, w: number, h: number, blur = 0) {
  const cv = typeof OffscreenCanvas !== "undefined" ? new OffscreenCanvas(w, h) : Object.assign(document.createElement("canvas"), { width: w, height: h });
  const x = cv.getContext("2d") as CanvasRenderingContext2D;
  if (blur) {
    // Pad with the edge so blur does not darken borders.
    x.filter = `blur(${blur}px)`;
    x.drawImage(img, -blur * 2, -blur * 2, w + blur * 4, h + blur * 4);
  } else x.drawImage(img, 0, 0, w, h);
  return cv;
}
/** The picture with its near things removed (filled from what surrounds them) — what blurs behind the subject. */
export function backgroundOnly(img: CanvasImageSource, dimg: CanvasImageSource, w: number, h: number, near = 0.6) {
  const cv = canvasOf(img, w, h);
  const x = cv.getContext("2d") as CanvasRenderingContext2D;
  const id = x.getImageData(0, 0, w, h);
  const dc = canvasOf(dimg, w, h);
  const dd = (dc.getContext("2d") as CanvasRenderingContext2D).getImageData(0, 0, w, h).data;
  const rgb = new Float32Array(w * h * 3),
    wt = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++) {
    const keep = dd[i * 4]! / 255 < near - 0.06 ? 1 : 0;
    wt[i] = keep;
    for (let c = 0; c < 3; c++) rgb[i * 3 + c] = keep ? id.data[i * 4 + c]! : 0;
  }
  const known = wt.slice();
  pullPush(rgb, wt, w, h);
  for (let i = 0; i < w * h; i++) if (!known[i]) for (let c = 0; c < 3; c++) id.data[i * 4 + c] = rgb[i * 3 + c]!;
  x.putImageData(id, 0, 0);
  return cv;
}

function tex(cv: unknown, srgb = true) {
  const t = new THREE.CanvasTexture(cv as HTMLCanvasElement);
  if (srgb) t.colorSpace = THREE.SRGBColorSpace;
  t.minFilter = THREE.LinearFilter;
  t.generateMipmaps = false;
  t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping;
  t.needsUpdate = true;
  return t;
}

function camAt(keys: DepthCamKey[], t: number): Required<Omit<DepthCamKey, "t">> {
  const base = { x: 0, y: 0, dolly: 0, zoom: 1.04, focus: 0.5 };
  if (!keys.length) return base;
  const k = [...keys].sort((a, b) => a.t - b.t);
  const val = (o: DepthCamKey) => ({ x: o.x ?? 0, y: o.y ?? 0, dolly: o.dolly ?? 0, zoom: o.zoom ?? 1.04, focus: o.focus ?? 0.5 });
  if (t <= k[0]!.t) return val(k[0]!);
  for (let i = 0; i < k.length - 1; i++) {
    const a = k[i]!,
      b = k[i + 1]!;
    if (t <= b.t) {
      const r = (t - a.t) / Math.max(1e-6, b.t - a.t);
      const e = r < 0.5 ? 4 * r * r * r : 1 - Math.pow(-2 * r + 2, 3) / 2;
      const A = val(a),
        B = val(b);
      return { x: A.x + (B.x - A.x) * e, y: A.y + (B.y - A.y) * e, dolly: A.dolly + (B.dolly - A.dolly) * e, zoom: A.zoom + (B.zoom - A.zoom) * e, focus: A.focus + (B.focus - A.focus) * e };
    }
  }
  return val(k[k.length - 1]!);
}

const depthImgs = new Map<string, ImageBitmap | null>();
function depthImage(url: string): ImageBitmap | null | undefined {
  if (depthImgs.has(url)) return depthImgs.get(url);
  depthImgs.set(url, null);
  void (async () => {
    try {
      depthImgs.set(url, await createImageBitmap(await (await fetch(url)).blob()));
    } catch {
      /* keep null */
    }
  })();
  return undefined;
}
/** Load a depth map ahead of rendering (exports wait on this). */
export async function preloadDepth(url: string): Promise<void> {
  if (depthImgs.get(url)) return;
  depthImgs.set(url, await createImageBitmap(await (await fetch(url)).blob()));
}

function drawDepth(c: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, l: AnimLayer, sp: LivingSpec, s: AnimScene, f: number, W: number, H: number, img: CanvasImageSource, r: THREE.WebGLRenderer, ox: number, oy: number, dw: number, dh: number) {
  const ds = sp.depth!;
  const dimg = depthImage(ds.map);
  const t = f / s.fps;
  const iw = (img as { width: number }).width,
    ih = (img as { height: number }).height;
  let e = depthCache.get(l.id);
  if (dimg && (!e || e.src !== img || e.map !== ds.map)) {
    const cols = 160,
      rows = Math.round((160 * ih) / iw);
    const geo = new THREE.PlaneGeometry(1, 1, cols, rows);
    geo.translate(0.5, -0.5, 0);
    const k = Math.min(1, 2048 / Math.max(iw, ih));
    const w = Math.round(iw * k),
      h = Math.round(ih * k);
    const small = Math.max(w, h) / 900;
    const mat = new THREE.ShaderMaterial({
      vertexShader: VERT,
      fragmentShader: FRAG,
      transparent: true,
      uniforms: {
        uTex: { value: tex(canvasOf(img, w, h)) },
        uBlur: { value: tex(canvasOf(img, Math.round(w / 2), Math.round(h / 2), Math.max(2, Math.round(4 / small)))) },
        uBlur2: { value: tex(canvasOf(img, Math.round(w / 4), Math.round(h / 4), Math.max(3, Math.round(6 / small)))) },
        uBgBlur: { value: tex(canvasOf(backgroundOnly(img, dimg, Math.round(w / 2), Math.round(h / 2)), Math.round(w / 2), Math.round(h / 2), Math.max(2, Math.round(4 / small)))) },
        uBgBlur2: { value: tex(canvasOf(backgroundOnly(img, dimg, Math.round(w / 4), Math.round(h / 4)), Math.round(w / 4), Math.round(h / 4), Math.max(3, Math.round(6 / small)))) },
        uDepth: { value: tex(canvasOf(dimg, dimg.width, dimg.height, 1), false) },
        uShift: { value: new THREE.Vector2() },
        uDolly: { value: 0 },
        uZoom: { value: 1 },
        uParallax: { value: 1 },
        uFocus: { value: 0.5 },
        uAperture: { value: 1 },
        uTime: { value: 0 },
        uWind: { value: 0 },
        uWindSpeed: { value: 1 },
        uWindNear: { value: 0.6 },
        uLight: { value: 0 },
      },
    });
    const mesh = new THREE.Mesh(geo, mat);
    const scene = new THREE.Scene();
    scene.add(mesh);
    e = { scene, mat, src: img, map: ds.map, base: Float32Array.from(geo.attributes.position!.array as Float32Array), mesh, ready: true };
    depthCache.set(l.id, e);
  }
  if (!e) {
    c.save();
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.drawImage(img, ox, oy, dw, dh);
    c.restore();
    return;
  }
  // Optional body pins still animate (breathing, blinks) on top of the depth motion.
  if (sp.pins) {
    const pos = e.mesh.geometry.attributes.position as THREE.BufferAttribute;
    const arr = pos.array as Float32Array;
    const ar = iw / ih;
    for (let i = 0; i < arr.length; i += 3) {
      const u = e.base[i]!,
        v = -e.base[i + 1]!;
      const [dx, dy] = displace(u, v, t, sp.pins, sp.motion, ar);
      arr[i] = u + dx;
      arr[i + 1] = -(v + dy);
    }
    pos.needsUpdate = true;
  }
  const cm = camAt(ds.cam, t);
  const U = e.mat.uniforms;
  U.uShift!.value.set(cm.x, cm.y);
  U.uDolly!.value = cm.dolly;
  U.uZoom!.value = cm.zoom;
  U.uFocus!.value = cm.focus;
  U.uParallax!.value = ds.parallax ?? 1;
  U.uAperture!.value = (ds.aperture ?? 1) * 2.2;
  U.uTime!.value = t;
  U.uWind!.value = ds.wind?.amount ?? 0;
  U.uWindSpeed!.value = ds.wind?.speed ?? 1;
  U.uWindNear!.value = ds.wind?.near ?? 0.55;
  U.uLight!.value = ds.light ?? 0;
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

/** Where to focus by default: the depth of the main subject (near, central things), 0..1. */
export async function autoFocus(url: string): Promise<number> {
  const bmp = await createImageBitmap(await (await fetch(url)).blob());
  const w = 96,
    h = Math.max(8, Math.round((96 * bmp.height) / bmp.width));
  const cv = canvasOf(bmp, w, h);
  const d = (cv.getContext("2d") as CanvasRenderingContext2D).getImageData(0, 0, w, h).data;
  const vals: number[] = [];
  for (let y = Math.floor(h * 0.15); y < h * 0.9; y++) for (let x = Math.floor(w * 0.25); x < w * 0.75; x++) vals.push(d[(y * w + x) * 4]! / 255);
  vals.sort((a, b) => a - b);
  return Math.round((vals[Math.floor(vals.length * 0.85)] ?? 0.6) * 100) / 100;
}
