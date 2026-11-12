/**
 * The 2D library inside Animate (desktop app): its pictures become layers of a scene — a prop or an effect as one
 * picture, a background as one picture or as its painted layers apart (to slide them for parallax), and a cut-out
 * character as a puppet: every part its own layer hanging from its joint, so the Pose tool and IK bend it.
 */
import { newAnimLayer, uid, type AnimLayer, type AnimScene } from "../core/model";

export interface Lib2DApi {
  library2d(): Promise<{ count: number; cats: Record<string, number>; assets: { id: string; name: string; cat: string; sub?: string; tags?: string[]; w: number; h: number; layers: number | unknown[]; rig: boolean | unknown }[] }>;
  asset2d(id: string): Promise<{ id: string; name: string; cat: string; w: number; h: number; file: string; layers?: { name: string; depth: number; file: string }[]; parts?: Record<string, { file: string; pivot: [number, number]; parent: string | null; z: number }>; rig?: { ground: number; build: string; joints: Record<string, unknown> }; anchor?: [number, number] }>;
  asset2dThumb(id: string): Promise<{ png: string }>;
  asset2dSvg(id: string, file?: string): Promise<{ svg: string; file: string }>;
}
/** The library, when the app has one (the desktop app). */
export function lib2d(): Lib2DApi | null {
  const f = (globalThis as { vsd?: { film?: Partial<Lib2DApi> } }).vsd?.film;
  return f && f.library2d && f.asset2d && f.asset2dThumb && f.asset2dSvg ? (f as Lib2DApi) : null;
}

/** An SVG drawn at a scale, cut to what it holds: the picture and where its box sits in the SVG's own units. */
export async function rasterSvg(svg: string, scale: number): Promise<{ blob: Blob; x0: number; y0: number; w: number; h: number }> {
  const bytes = new TextEncoder().encode(svg);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  const img = new Image();
  img.src = `data:image/svg+xml;base64,${btoa(bin)}`;
  await img.decode();
  const W = Math.max(1, Math.round(img.naturalWidth * scale)), H = Math.max(1, Math.round(img.naturalHeight * scale));
  const c = document.createElement("canvas");
  c.width = W;
  c.height = H;
  const g = c.getContext("2d")!;
  g.drawImage(img, 0, 0, W, H);
  const d = g.getImageData(0, 0, W, H).data;
  let x0 = W, y0 = H, x1 = -1, y1 = -1;
  for (let y = 0; y < H; y += 2) for (let x = 0; x < W; x += 2) if (d[(y * W + x) * 4 + 3]! > 6) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  if (x1 < 0) { x0 = 0; y0 = 0; x1 = 1; y1 = 1; }
  x0 = Math.max(0, x0 - 3); y0 = Math.max(0, y0 - 3); x1 = Math.min(W - 1, x1 + 3); y1 = Math.min(H - 1, y1 + 3);
  const o = document.createElement("canvas");
  o.width = x1 - x0 + 1;
  o.height = y1 - y0 + 1;
  o.getContext("2d")!.drawImage(c, -x0, -y0);
  const blob = await new Promise<Blob>((res, rej) => o.toBlob((b) => (b ? res(b) : rej(new Error("The picture could not be made."))), "image/png"));
  return { blob, x0: x0 / scale, y0: y0 / scale, w: o.width / scale, h: o.height / scale };
}

/** A picture layer: centre (scene px) and width (scene px). */
export function pictureLayer(name: string, assetId: string, cx: number, cy: number, w: number, s: Pick<AnimScene, "width" | "height">): AnimLayer {
  const l = newAnimLayer(name, "proc");
  l.proc = { type: "picture", style: "", assetId, x: cx / s.width, y: cy / s.height, w: w / s.width, enter: "none" };
  return l;
}

type AddAsset = (blob: Blob, name: string) => Promise<{ id: string }>;
/** Library rig part → the part names of Animate's rigs (the character's right arm hangs on the far side: B). */
const PART_MAP: Record<string, string> = { torso: "torso", head: "head", upperarm_R: "upperArmB", forearm_R: "foreArmB", hand_R: "handB", thigh_R: "thighB", shin_R: "shinB", foot_R: "footB", upperarm_L: "upperArmF", forearm_L: "foreArmF", hand_L: "handF", thigh_L: "thighF", shin_L: "shinF", foot_L: "footF" };
const TIP_OF: Record<string, string> = { torso: "neck", upperarm_R: "forearm_R", forearm_R: "hand_R", thigh_R: "shin_R", shin_R: "foot_R", upperarm_L: "forearm_L", forearm_L: "hand_L", thigh_L: "shin_L", shin_L: "foot_L" };

/**
 * The layers a library asset becomes in a scene of this size. mode: "picture" (one picture), "layers" (a
 * background's painted layers apart), "puppet" (a cut-out character as a rig). The pictures are added as assets
 * first (add); the layers come back top first, with where they go: on top, or under everything (a background).
 */
export async function libraryLayers(s: Pick<AnimScene, "width" | "height">, api: Lib2DApi, id: string, mode: "picture" | "layers" | "puppet", add: AddAsset, at?: { x: number; y: number; h: number }): Promise<{ layers: AnimLayer[]; bottom: boolean }> {
  const a = await api.asset2d(id);
  const W = s.width, H = s.height, made: AnimLayer[] = [];
  if (a.cat === "backgrounds") {
    // a background fills the frame's height, centred (its width is wider: room to pan)
    const k = H / a.h, files = mode === "layers" && a.layers ? a.layers.map((L) => ({ name: L.name, file: L.file })) : [{ name: "", file: a.file }];
    for (const f of files) {
      const r = await rasterSvg((await api.asset2dSvg(a.id, f.file)).svg, Math.min(2, k));
      const asset = await add(r.blob, `${a.name}${f.name ? ` · ${f.name}` : ""}.png`);
      made.unshift(pictureLayer(`${a.name}${f.name ? ` · ${f.name}` : ""}`, asset.id, W / 2 + (r.x0 + r.w / 2 - a.w / 2) * k, H / 2 + (r.y0 + r.h / 2 - a.h / 2) * k, r.w * k, s));
    }
    return { layers: made, bottom: true }; // a background goes under everything
  }
  if (a.cat === "characters" && mode === "puppet" && a.parts && a.rig) {
    const ground = a.rig.ground, top = a.rig.build === "chibi" ? 70 : 60, hh = at?.h ?? H * 0.8, k = hh / (ground - top);
    const fx = at?.x ?? W / 2, fy = at?.y ?? H * 0.92, S = (p: [number, number]): [number, number] => [fx + (p[0] - 300) * k, fy + (p[1] - ground) * k];
    const rig = uid("rig"), byName = new Map<string, AnimLayer>();
    const names = Object.keys(a.parts).filter((n) => !/^eyes_|^mouth_/.test(n) || n === "eyes_open" || n === "mouth_rest");
    for (const n of names) {
      const p = a.parts[n]!, r = await rasterSvg((await api.asset2dSvg(a.id, p.file)).svg, Math.min(2, k * 1.1));
      const asset = await add(r.blob, `${a.name} · ${n}.png`);
      const c = S([r.x0 + r.w / 2, r.y0 + r.h / 2]), l = pictureLayer(`${a.name} · ${n}`, asset.id, c[0], c[1], r.w * k, s);
      const part = PART_MAP[n], piv = S(p.pivot);
      l.pivot = { x: piv[0]!, y: piv[1]! };
      if (part) {
        l.rig = rig;
        l.part = part;
        const tipName = TIP_OF[n], tp = tipName === "neck" ? (a.parts.head?.pivot ?? p.pivot) : tipName ? a.parts[tipName]?.pivot : null;
        const tip: [number, number] = tp ? S(tp) : n === "head" ? S([300, top]) : [piv[0]!, piv[1]! + 50 * k];
        l.tip = { x: tip[0], y: tip[1] };
      }
      (l as AnimLayer & { _z?: number })._z = p.z;
      byName.set(n, l);
    }
    // the hips: the root the legs and the body hang from (it draws nothing)
    const hip = S(a.parts.torso!.pivot), hips = newAnimLayer(`${a.name} · hips`, "proc");
    hips.proc = { type: "picture", style: "" };
    hips.rig = rig;
    hips.part = "hips";
    hips.pivot = { x: hip[0], y: hip[1] };
    hips.tip = { x: hip[0], y: hip[1] + 40 * k };
    for (const [n, l] of byName) {
      const parent = a.parts[n]!.parent;
      if (/^thigh_/.test(n) || n === "torso" || n === "skirt") l.parent = hips.id;
      else if (/^eyes_|^mouth_|^hair_/.test(n)) l.parent = byName.get("head")?.id;
      else if (parent) l.parent = byName.get(parent)?.id;
    }
    const ordered = [...byName.values()].sort((x, y) => ((y as { _z?: number })._z ?? 0) - ((x as { _z?: number })._z ?? 0));
    for (const l of ordered) delete (l as { _z?: number })._z;
    made.push(...ordered, hips);
    return { layers: made, bottom: false };
  }
  // anything else: one picture, a third of the frame tall, centred
  const k = Math.min(2, ((at?.h ?? H * 0.34) / Math.max(a.w, a.h)) * 1.0);
  const r = await rasterSvg((await api.asset2dSvg(a.id)).svg, k);
  const asset = await add(r.blob, `${a.name}.png`);
  return { layers: [pictureLayer(a.name, asset.id, at?.x ?? W / 2, at?.y ?? H / 2, r.w * k, s)], bottom: false };
}
