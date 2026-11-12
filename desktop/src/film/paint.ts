/**
 * Fixes painted over film shots — the app's side of film/kit/paint.js: the same transforms (a fix follows the body
 * part it was drawn on, frame by frame, from the shot's part track), so what is drawn in Animate lands where the film
 * engine will draw it.
 */
import type { PaintAnchor, PaintFix, PaintStroke, PaintTrack } from "./types";

export interface Tr {
  x: number;
  y: number;
  ang: number;
  scale: number;
}
const clamp = (x: number, a = 0, b = 1) => (x < a ? a : x > b ? b : x);
const wrap = (a: number) => Math.atan2(Math.sin(a), Math.cos(a));
const sstep = (a: number, b: number, x: number) => {
  const u = clamp((x - a) / (b - a));
  return u * u * (3 - 2 * u);
};

/** Human names of the parts a fix can hold on to. */
export const PART_NAMES: Record<string, string> = {
  head: "head", face: "face", eyes: "eyes", jaw: "jaw", neck: "neck", chest: "chest", belly: "stomach", hips: "hips",
  "shoulder.L": "left shoulder", "upperarm.L": "left upper arm", "elbow.L": "left elbow", "forearm.L": "left forearm", "hand.L": "left hand", "fingers.L": "left fingers",
  "shoulder.R": "right shoulder", "upperarm.R": "right upper arm", "elbow.R": "right elbow", "forearm.R": "right forearm", "hand.R": "right hand", "fingers.R": "right fingers",
  "thigh.L": "left thigh", "knee.L": "left knee", "shin.L": "left shin", "foot.L": "left foot",
  "thigh.R": "right thigh", "knee.R": "right knee", "shin.R": "right shin", "foot.R": "right foot",
};
/** A part as Sensei names it ("left wrist", "right foot", "whole body") → a part a fix can hold on to. */
export function partFromWords(words: string | null | undefined): string | null {
  const w = (words ?? "").toLowerCase().split("/")[0]!.trim();
  const s = /\bleft\b/.test(w) ? "L" : /\bright\b/.test(w) ? "R" : null;
  const map: [RegExp, string, boolean][] = [[/finger/, "fingers", true], [/wrist|hand|grip|palm/, "hand", true], [/forearm/, "forearm", true], [/elbow/, "elbow", true], [/upper ?arm|arm/, "upperarm", true], [/shoulder/, "shoulder", true], [/ankle|foot|heel|toe/, "foot", true], [/shin|calf/, "shin", true], [/knee/, "knee", true], [/thigh|leg/, "thigh", true], [/eye/, "eyes", false], [/jaw|mouth|chin/, "jaw", false], [/face/, "face", false], [/head|hair/, "head", false], [/neck/, "neck", false], [/chest|torso|spine/, "chest", false], [/belly|stomach/, "belly", false], [/hip|pelvis|weight|whole/, "hips", false]];
  for (const [re, part, sided] of map) if (re.test(w)) return sided ? `${part}.${s ?? "R"}` : part;
  return null;
}

/** The part's place at frame f: origin, turn, pixels per metre, how much it lies across the picture. */
export function partAt(track: PaintTrack, actor: string, part: string, f: number): { x: number; y: number; ang: number; ppm: number; vis: number } | null {
  const A = track.actors[actor];
  if (!A) return null;
  const i = Math.max(0, Math.min(track.frames - 1, Math.round(f)));
  if (!A.shown[i]) return null;
  const v = A.parts[part]?.[i];
  if (!v) return null;
  return { x: v[0], y: v[1], ang: v[2], ppm: v[3], vis: v[4] };
}

/** The transform of a fix at frame f in a picture H tall (the track's own picture is 1080 tall). */
export function fixTransform(fix: Pick<PaintFix, "anchor" | "follow" | "ref">, track: PaintTrack, f: number, H = 1080): Tr | null {
  const an = fix.anchor, ref = fix.ref, k = H / track.height;
  if (an.kind === "screen") return { x: 0, y: 0, ang: 0, scale: H };
  if (an.kind !== "part") return null; // a point of the world is placed by the film engine only
  const p = partAt(track, an.actor, an.part, f);
  if (!p) return null;
  const refAng = ref?.ang ?? p.ang;
  const turn = fix.follow === "full" || !fix.follow ? refAng + wrap(p.ang - refAng) * sstep(0.12, 0.35, p.vis) : refAng;
  const scale = fix.follow === "move" ? (ref?.ppmH ?? p.ppm / track.height) * H : p.ppm * k;
  return { x: p.x * k, y: p.y * k, ang: turn, scale };
}
export function toLocal(tr: Tr, x: number, y: number): [number, number] {
  const dx = x - tr.x, dy = y - tr.y, c = Math.cos(-tr.ang), s = Math.sin(-tr.ang);
  return [(dx * c - dy * s) / tr.scale, (dx * s + dy * c) / tr.scale];
}
export function toPicture(tr: Tr, u: number, v: number): [number, number] {
  const c = Math.cos(tr.ang), s = Math.sin(tr.ang);
  return [tr.x + (u * c - v * s) * tr.scale, tr.y + (u * s + v * c) * tr.scale];
}
/** A stroke in picture pixels → the anchor's units (to keep). */
export function strokeToLocal(s: PaintStroke, tr: Tr): PaintStroke {
  const pts: number[] = [];
  for (let i = 0; i < s.pts.length; i += 3) {
    const q = toLocal(tr, s.pts[i]!, s.pts[i + 1]!);
    pts.push(+q[0].toFixed(5), +q[1].toFixed(5), +(s.pts[i + 2] ?? 0.5).toFixed(3));
  }
  return { ...s, pts, size: +(s.size / tr.scale).toFixed(6) };
}
/** A kept stroke → picture pixels at a frame. */
export function strokeToPicture(s: PaintStroke, tr: Tr): PaintStroke {
  const pts: number[] = new Array(s.pts.length);
  for (let i = 0; i < s.pts.length; i += 3) {
    const q = toPicture(tr, s.pts[i]!, s.pts[i + 1]!);
    pts[i] = q[0];
    pts[i + 1] = q[1];
    pts[i + 2] = s.pts[i + 2]!;
  }
  return { ...s, pts, size: s.size * tr.scale };
}
export function fixAlpha(fix: PaintFix, t: number): number {
  if (fix.on === false) return 0;
  const sp = fix.span;
  if (!sp) return 1;
  if (t < sp[0] - 1e-6 || t > sp[1] + 1e-6) return 0;
  return fix.fade > 0 ? clamp(Math.min((t - sp[0]) / fix.fade, (sp[1] - t) / fix.fade) + 0.0001) : 1;
}

/**
 * The part a new drawing holds on to: of the people in the picture at frame f, the part whose origin is nearest the
 * middle of the drawing (small parts — hands, feet, the face — win when they are close). null: nothing near (screen).
 */
export function nearestPart(track: PaintTrack, f: number, x: number, y: number, H = 1080): PaintAnchor | null {
  const k = H / track.height;
  let best: { actor: string; part: string; d: number } | null = null;
  for (const [actor, A] of Object.entries(track.actors)) {
    for (const part of Object.keys(A.parts)) {
      const p = partAt(track, actor, part, f);
      if (!p) continue;
      const small = /^(hand|fingers|foot|face|eyes|jaw)/.test(part) ? 0.75 : /^(elbow|knee|head|neck)/.test(part) ? 0.9 : 1;
      const d = (Math.hypot(p.x * k - x, p.y * k - y) / (p.ppm * k)) * small; // in metres at the part, smaller parts nearer
      if (!best || d < best.d) best = { actor, part, d };
    }
  }
  return best && best.d < 0.6 ? { kind: "part", actor: best.actor, part: best.part } : null;
}

/** Fill the masked pixels from those around them (push-pull, the same as the film engine's "fill" erase). */
export function inpaint(img: Uint8ClampedArray, mask: Uint8Array, w: number, h: number): void {
  type Lv = { w: number; h: number; col: Float32Array; wt: Float32Array };
  let L: Lv = { w, h, col: new Float32Array(w * h * 3), wt: new Float32Array(w * h) };
  for (let i = 0; i < w * h; i++) {
    L.wt[i] = mask[i]! > 127 ? 0 : 1;
    L.col[i * 3] = img[i * 4]!;
    L.col[i * 3 + 1] = img[i * 4 + 1]!;
    L.col[i * 3 + 2] = img[i * 4 + 2]!;
  }
  const levels: Lv[] = [L];
  while (L.w > 1 || L.h > 1) {
    const nw = Math.max(1, Math.ceil(L.w / 2)), nh = Math.max(1, Math.ceil(L.h / 2)), N: Lv = { w: nw, h: nh, col: new Float32Array(nw * nh * 3), wt: new Float32Array(nw * nh) }, sum = new Float32Array(nw * nh);
    for (let y = 0; y < L.h; y++)
      for (let x = 0; x < L.w; x++) {
        const i = y * L.w + x, a = L.wt[i]!;
        if (a <= 0) continue;
        const j = (y >> 1) * nw + (x >> 1);
        sum[j]! += a;
        N.col[j * 3]! += L.col[i * 3]! * a;
        N.col[j * 3 + 1]! += L.col[i * 3 + 1]! * a;
        N.col[j * 3 + 2]! += L.col[i * 3 + 2]! * a;
      }
    for (let j = 0; j < nw * nh; j++)
      if (sum[j]! > 0) {
        N.col[j * 3]! /= sum[j]!;
        N.col[j * 3 + 1]! /= sum[j]!;
        N.col[j * 3 + 2]! /= sum[j]!;
        N.wt[j] = Math.min(1, sum[j]!);
      }
    levels.push(N);
    L = N;
  }
  for (let k = levels.length - 2; k >= 0; k--) {
    const A = levels[k]!, C = levels[k + 1]!;
    for (let y = 0; y < A.h; y++)
      for (let x = 0; x < A.w; x++) {
        const i = y * A.w + x, a = A.wt[i]!;
        if (a >= 1) continue;
        const fx = Math.min(C.w - 1, Math.max(0, (x + 0.5) / 2 - 0.5)), fy = Math.min(C.h - 1, Math.max(0, (y + 0.5) / 2 - 0.5)), x0 = Math.floor(fx), y0 = Math.floor(fy), x1 = Math.min(C.w - 1, x0 + 1), y1 = Math.min(C.h - 1, y0 + 1), u = fx - x0, v = fy - y0;
        for (let c = 0; c < 3; c++) {
          const q = C.col[(y0 * C.w + x0) * 3 + c]! * (1 - u) * (1 - v) + C.col[(y0 * C.w + x1) * 3 + c]! * u * (1 - v) + C.col[(y1 * C.w + x0) * 3 + c]! * (1 - u) * v + C.col[(y1 * C.w + x1) * 3 + c]! * u * v;
          A.col[i * 3 + c] = A.col[i * 3 + c]! * a + q * (1 - a);
        }
        A.wt[i] = 1;
      }
  }
  const F = levels[0]!;
  for (let i = 0; i < w * h; i++)
    if (mask[i]! > 127) {
      img[i * 4] = F.col[i * 3]!;
      img[i * 4 + 1] = F.col[i * 3 + 1]!;
      img[i * 4 + 2] = F.col[i * 3 + 2]!;
    }
}
