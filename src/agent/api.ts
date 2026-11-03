/**
 * Agent command API. Any AI (or a person) drives the whole studio with JSON:
 *   studio.run([{ op: "clip.add", text: "Hello" }, { op: "clip.key", id: "$0.id", prop: "opacity", t: 0, v: 0 }])
 * Strings like "$0.id" refer to earlier results in the same batch. Every edit is undoable.
 * There is deliberately no command that browses the phone's gallery or files.
 */
import type { Studio } from "../app/studio";
import * as ops from "../core/ops";
import * as anim from "../anim/ops";
import * as rig from "../anim/rig";
import * as puppet from "../anim/puppet";
import { flattenPath } from "../anim/svgpath";
import { ambience, compose, sfx, SFX, toWav, type Ambience, type Mood, type Sfx } from "../audio/compose";
import { enhance, loudness, type EnhanceOptions } from "../audio/enhance";
import * as motion from "../anim/motion";
import { computeMatte, matteAt, matteLoad, storeMatte } from "../media/matte";
import { GENERATED_TEXTURES, type SubjectSpec } from "../render/subject";
import * as proc from "../anim/proc";
import { speechEnvelope } from "../media/library";
import { newScene, newTrack, type BlendMode, type Clip } from "../core/model";
import { exposedKey, findAsset, projectDuration, rampIntegral, uid, type Ease, type Grade, type Project, type Stroke, type TransitionKind } from "../core/model";
import { fetchToBlob, importBlob } from "../media/library";
import { receiveTransfer } from "../media/transfer";
import { analyseSong } from "../media/song";
import { to16k, transcribe, type LyricLine } from "../media/asr";
import { ExportJob, type ExportOptions } from "../render/export";
import { fetchInbox, listInbox } from "./remote";
import { assetThumb, contactSheet, frameSnapshot, scenePng, sceneSnapshot, stillImage } from "../render/snapshot";
import { decodeMask, depthToPng, dilate, estimateDepth, maskAtSize, maskBounds, maskToPng, segment } from "../media/ai";
import { genDownload, genStatus, smartFill } from "../media/genfill";
import { memInfo } from "../native/storage";
import { ctx2d as ctx2dA, makeCanvas as makeCanvasA } from "../anim/render";
import { analyseBeats, beatCut } from "../app/autoedit";
import * as cr from "../app/creative";
import { makeProxy, wantsProxy } from "../media/proxy";
import { valueAt } from "../core/keyframes";
import * as t3 from "../anim/three3d";
import * as living from "../anim/living";
import * as mv from "../app/musicvideo";
import { detectScenes, findOverlays, frameStrip } from "../media/vision";
import type { RemoveRegion } from "../render/inpaint";
import { saveBlob } from "../native/storage";
import { FONTS } from "../render/fonts";

export type Command = { op: string } & Record<string, unknown>;
export interface CommandResult {
  ok: boolean;
  op: string;
  result?: unknown;
  error?: string;
}

type Handler = (s: Studio, a: Args) => unknown | Promise<unknown>;

class Args {
  constructor(private readonly raw: Record<string, unknown>) {}
  str(k: string, d?: string): string {
    const v = this.raw[k];
    if (v === undefined || v === null) {
      if (d === undefined) throw new ops.OpError(`Missing "${k}".`);
      return d;
    }
    return String(v);
  }
  optStr(k: string): string | undefined {
    const v = this.raw[k];
    return v === undefined || v === null ? undefined : String(v);
  }
  num(k: string, d?: number): number {
    const v = this.raw[k];
    if (v === undefined || v === null) {
      if (d === undefined) throw new ops.OpError(`Missing "${k}".`);
      return d;
    }
    const n = Number(v);
    if (!Number.isFinite(n)) throw new ops.OpError(`"${k}" must be a number.`);
    return n;
  }
  optNum(k: string): number | undefined {
    const v = this.raw[k];
    return v === undefined || v === null ? undefined : this.num(k);
  }
  bool(k: string, d = false): boolean {
    const v = this.raw[k];
    return v === undefined ? d : v === true || v === "true" || v === 1;
  }
  obj(k: string): Record<string, unknown> {
    const v = this.raw[k];
    if (!v || typeof v !== "object" || Array.isArray(v)) throw new ops.OpError(`"${k}" must be an object.`);
    return v as Record<string, unknown>;
  }
  any(k: string): unknown {
    return this.raw[k];
  }
}

const GRADES: Record<string, Partial<Record<keyof Grade, number>>> = {
  natural: {},
  cinematic: { contrast: 1.12, saturation: 0.9, temperature: 0.08, lift: 0.02, gamma: 0.95, vignette: 0.35, grain: 0.08 },
  tealOrange: { contrast: 1.15, saturation: 1.1, temperature: 0.15, tint: -0.1, hue: -4, vignette: 0.25 },
  noir: { saturation: 0, contrast: 1.35, vignette: 0.5, grain: 0.25 },
  warm: { temperature: 0.35, saturation: 1.08, exposure: 0.05 },
  cool: { temperature: -0.3, tint: 0.05, saturation: 0.95 },
  vintage: { contrast: 0.88, saturation: 0.72, lift: 0.08, temperature: 0.2, grain: 0.3, vignette: 0.35 },
  punchy: { contrast: 1.2, saturation: 1.3, vibrance: 0.3, sharpen: 0.4 },
  faded: { contrast: 0.82, lift: 0.12, saturation: 0.85 },
  night: { exposure: -0.6, temperature: -0.45, saturation: 0.7, contrast: 1.1, vignette: 0.45 },
  dream: { blur: 2, exposure: 0.2, saturation: 1.1, vignette: 0.2, gain: 1.05 },
};

const TRANSITIONS: TransitionKind[] = ["none", "fade", "dip", "flash", "wipeLeft", "wipeRight", "wipeUp", "slideLeft", "slideRight", "slideUp", "whipLeft", "whipRight", "zoomIn", "zoomBlur", "spin", "glitch", "blur", "iris"];

function summary(p: Project) {
  return {
    id: p.id,
    name: p.name,
    size: `${p.width}x${p.height}`,
    fps: p.fps,
    duration: Math.round(projectDuration(p) * 1000) / 1000,
    tracks: p.tracks.map((t) => ({
      id: t.id,
      kind: t.kind,
      name: t.name,
      muted: t.muted,
      hidden: t.hidden,
      clips: t.clips.map((c) => ({ id: c.id, kind: c.kind, name: c.name, start: r3(c.start), dur: r3(c.dur), assetId: c.assetId, sceneId: c.sceneId, text: c.text?.text })),
    })),
    assets: p.assets.map((a) => ({ id: a.id, kind: a.kind, name: a.name, duration: r3(a.duration), width: a.width, height: a.height, lipSync: !!a.mouth })),
    scenes: p.scenes.map((s) => ({ id: s.id, name: s.name, size: `${s.width}x${s.height}`, fps: s.fps, frames: s.frames, layers: s.layers.map((l) => ({ id: l.id, name: l.name, kind: l.kind, keys: anim.keysOf(l) })) })),
  };
}
const r3 = (n: number) => Math.round(n * 1000) / 1000;

function toPts(a: Args): number[] {
  const raw = a.any("pts") ?? a.any("points");
  if (!Array.isArray(raw) || raw.length === 0) throw new ops.OpError(`"points" must be a list like [[x,y,pressure], ...].`);
  const out: number[] = [];
  if (Array.isArray(raw[0])) for (const p of raw as number[][]) out.push(Number(p[0]), Number(p[1]), p[2] === undefined ? 0.6 : Number(p[2]));
  else {
    const flat = raw as number[];
    const stride = a.num("stride", 3);
    for (let i = 0; i + 1 < flat.length; i += stride) out.push(Number(flat[i]), Number(flat[i + 1]), stride >= 3 ? Number(flat[i + 2] ?? 0.6) : 0.6);
  }
  if (out.some((n) => !Number.isFinite(n))) throw new ops.OpError("Points must be numbers.");
  return out;
}

function shapePts(shape: string, x: number, y: number, w: number, h: number): number[] {
  const pts: number[] = [];
  if (shape === "ellipse") for (let i = 0; i <= 64; i++) {
    const t = (i / 64) * Math.PI * 2;
    pts.push(x + (Math.cos(t) * w) / 2, y + (Math.sin(t) * h) / 2, 0.7);
  }
  else if (shape === "line") pts.push(x, y, 0.7, x + w, y + h, 0.7);
  else pts.push(x - w / 2, y - h / 2, 0.7, x + w / 2, y - h / 2, 0.7, x + w / 2, y + h / 2, 0.7, x - w / 2, y + h / 2, 0.7, x - w / 2, y - h / 2, 0.7);
  return pts;
}

let activeExport: ExportJob | null = null;


/* ------------------- procedural clips (captions, mograph, overlays…) ------------------- */

/** The video track just above everything visible in [start, start+dur) (creates one on top if needed). */
function topTrack(p: Project, start: number, dur: number): string {
  const vids = p.tracks.filter((t) => t.kind === "video");
  const busy = (t: (typeof vids)[number]) => t.clips.some((c) => c.start < start + dur - 1e-6 && c.start + c.dur > start + 1e-6);
  const highest = vids.findIndex(busy); // tracks are listed top first
  if (highest < 0) return ops.trackFor(p, "video", start, dur).id;
  for (let i = highest - 1; i >= 0; i--) if (!busy(vids[i]!) && !vids[i]!.locked) return vids[i]!.id;
  const t = newTrack("video", `V${vids.length + 1}`);
  p.tracks.unshift(t);
  return t.id;
}

function procClip(p: Project, spec: proc.ProcSpec, start: number, dur: number, o: { name?: string; blend?: BlendMode; opacity?: number; trackId?: string; bottom?: boolean } = {}): { id: string; sceneId: string } {
  const sc = newScene(o.name ?? spec.type, p.width, p.height, p.fps);
  sc.background = "";
  sc.frames = Math.max(1, Math.round(dur * p.fps));
  sc.layers = [];
  const l = anim.addLayer(sc, o.name ?? spec.type, "proc");
  l.proc = spec;
  p.scenes.push(sc);
  const trackId = o.trackId ?? (o.bottom ? ops.trackFor(p, "video", start, dur).id : topTrack(p, start, dur));
  const clip = ops.addClip(p, { sceneId: sc.id, start, dur, trackId });
  const patch: Record<string, unknown> = { name: o.name ?? spec.type };
  if (o.blend) patch.blend = o.blend;
  if (o.opacity !== undefined) patch.transform = { opacity: o.opacity };
  ops.patchClip(p, clip.id, patch);
  return { id: clip.id, sceneId: sc.id };
}

function specFrom(a: Args, type: proc.ProcSpec["type"], style: string): proc.ProcSpec {
  const sp: proc.ProcSpec = { type, style };
  for (const k of ["text", "color", "accent", "bg", "position", "assetId", "enter", "font"] as const) if (a.optStr(k) !== undefined) (sp as unknown as Record<string, unknown>)[k] = a.str(k);
  for (const k of ["x", "y", "size", "w", "rot", "maxWords", "value", "seed", "boil"] as const) if (a.optNum(k) !== undefined) (sp as unknown as Record<string, unknown>)[k] = a.num(k);
  if (a.any("uppercase") !== undefined) sp.uppercase = a.bool("uppercase");
  if (Array.isArray(a.any("palette"))) sp.palette = a.any("palette") as string[];
  return sp;
}

function threeLayer(p: Project, a: Args) {
  const sc = anim.getScene(p, a.str("sceneId"));
  const l = anim.getLayer(sc, a.str("layerId"));
  if (l.kind !== "three" || !l.three) throw new ops.OpError("That layer is not a 3D layer (three.new makes one).");
  return l;
}

const HANDLERS: Record<string, { help: string; run: Handler }> = {
  help: { help: "List every command.", run: () => Object.entries(HANDLERS).map(([op, h]) => `${op}: ${h.help}`) },
  state: { help: "Project summary: tracks, clips, assets, scenes (ids for later commands).", run: (s) => summary(s.project) },
  "clip.get": { help: "{id} Full clip JSON.", run: (s, a) => ops.getClip(s.project, a.str("id")) },
  "project.new": {
    help: "{name?, width?=1080, height?=1920, fps?=30} Start a new project.",
    run: async (s, a) => summary(await s.newProject(a.str("name", "Untitled"), a.num("width", 1080), a.num("height", 1920), a.num("fps", 30))),
  },
  "project.set": {
    help: "{name?, width?, height?, fps?, background?}",
    run: (s, a) =>
      s.mutate("project settings", (p) => {
        if (a.optStr("name")) p.name = a.str("name");
        if (a.optNum("width")) p.width = Math.round(a.num("width"));
        if (a.optNum("height")) p.height = Math.round(a.num("height"));
        if (a.optNum("fps")) p.fps = a.num("fps");
        if (a.optStr("background")) p.background = a.str("background");
        return summary(p);
      }),
  },
  "project.list": { help: "Saved projects.", run: (s) => s.list() },
  "project.open": { help: "{id}", run: async (s, a) => summary(await s.open(a.str("id"))) },
  "project.save": { help: "Save now.", run: async (s) => (await s.save(), true) },
  undo: { help: "Undo the last edit.", run: (s) => s.undo() },
  redo: { help: "Redo.", run: (s) => s.redo() },

  "media.importUrl": {
    help: "{url, name?, add?=true, start?} Download an https file into the project (never the gallery).",
    run: async (s, a) => {
      const { blob, name } = await fetchToBlob(a.str("url"));
      return await s.mutateAsync("import link", async (p) => {
        const asset = await importBlob(p, blob, a.str("name", name), "agent");
        const clip = a.bool("add", true) ? ops.addClip(p, { assetId: asset.id, start: a.optNum("start") }) : undefined;
        return { id: asset.id, clipId: clip?.id, kind: asset.kind, duration: asset.duration };
      });
    },
  },
  "media.transfer": {
    help: "{urls (parts, in order) | url, key (base64 AES-256), name, mime?, add?=true, start?} Bring a file the agent was given in chat into the project (encrypted hand-off; decrypted on the phone).",
    run: async (s, a) => {
      const name = a.str("name");
      const mime = a.str("mime", /\.mp3$/i.test(name) ? "audio/mpeg" : /\.wav$/i.test(name) ? "audio/wav" : /\.(m4a|aac)$/i.test(name) ? "audio/mp4" : /\.mov$/i.test(name) ? "video/quicktime" : /\.(png)$/i.test(name) ? "image/png" : /\.(jpe?g)$/i.test(name) ? "image/jpeg" : "video/mp4");
      const urls = (a.any("urls") as string[] | undefined) ?? [a.str("url")];
      const blob = await receiveTransfer(urls, a.str("key"), mime);
      return await s.mutateAsync("import transfer", async (p) => {
        const asset = await importBlob(p, blob, name, "agent");
        const clip = a.bool("add", true) ? ops.addClip(p, { assetId: asset.id, start: a.optNum("start") }) : undefined;
        return { id: asset.id, clipId: clip?.id, kind: asset.kind, duration: asset.duration, width: asset.width, height: asset.height, bytes: blob.size };
      });
    },
  },
  "media.inbox": { help: "Files waiting in the relay inbox (things shared in chat).", run: () => listInbox() },
  "media.importInbox": {
    help: "{fileId, add?=true, start?, keep?=false} Import a file from the relay inbox into the project.",
    run: async (s, a) => {
      const f = await fetchInbox(a.str("fileId"));
      const r = await s.mutateAsync("import inbox", async (p) => {
        const asset = await importBlob(p, f.blob, a.str("name", f.name), "agent");
        const clip = a.bool("add", true) ? ops.addClip(p, { assetId: asset.id, start: a.optNum("start") }) : undefined;
        return { id: asset.id, clipId: clip?.id, kind: asset.kind, duration: asset.duration, width: asset.width, height: asset.height };
      });
      if (!a.bool("keep")) await f.done();
      return r;
    },
  },
  "media.list": { help: "Assets in this project.", run: (s) => summary(s.project).assets },
  "media.remove": { help: "{id} Remove an asset and its clips.", run: (s, a) => s.mutate("remove media", (p) => ops.removeAsset(p, a.str("id"))) },

  "track.add": {
    help: "{kind: video|audio, name?}",
    run: (s, a) =>
      s.mutate("add track", (p) => {
        const kind = a.str("kind", "video") as "video" | "audio";
        const t = { id: uid("trk"), kind, name: a.str("name", `${kind === "video" ? "V" : "A"}${p.tracks.filter((x) => x.kind === kind).length + 1}`), muted: false, hidden: false, locked: false, volume: 1, clips: [] };
        if (kind === "video") p.tracks.unshift(t);
        else p.tracks.push(t);
        return { id: t.id };
      }),
  },
  "track.set": {
    help: "{id, name?, muted?, hidden?, locked?, volume?}",
    run: (s, a) =>
      s.mutate("track", (p) => {
        const t = ops.getTrack(p, a.str("id"));
        for (const k of ["muted", "hidden", "locked"] as const) if (a.any(k) !== undefined) t[k] = a.bool(k);
        if (a.optNum("volume") !== undefined) t.volume = a.num("volume");
        if (a.optStr("name")) t.name = a.str("name");
        return true;
      }),
  },
  "track.closeGaps": { help: "{id}", run: (s, a) => s.mutate("close gaps", (p) => ops.closeGaps(p, a.str("id"))) },

  "clip.add": {
    help: "{assetId | sceneId | text | kind:color|shape|adjust, trackId?, start?, dur?, in?, props?} Returns {id}.",
    run: (s, a) =>
      s.mutate("add clip", (p) => {
        const props = a.any("props") as Record<string, unknown> | undefined;
        const c = ops.addClip(p, { kind: a.optStr("kind") as never, assetId: a.optStr("assetId"), sceneId: a.optStr("sceneId"), trackId: a.optStr("trackId"), start: a.optNum("start"), dur: a.optNum("dur"), in: a.optNum("in"), text: a.optStr("text") });
        if (props) ops.patchClip(p, c.id, props);
        return { id: c.id, start: c.start, dur: c.dur };
      }),
  },
  "clip.move": { help: "{id, start, trackId?}", run: (s, a) => s.mutate("move", (p) => r3(ops.moveClip(p, a.str("id"), a.num("start"), a.optStr("trackId")).start)) },
  "clip.split": { help: "{id, t} Split at timeline time t; returns the right part's id.", run: (s, a) => s.mutate("split", (p) => ({ id: ops.splitClip(p, a.str("id"), a.num("t")).id })) },
  "clip.trim": { help: "{id, edge: start|end, t}", run: (s, a) => s.mutate("trim", (p) => { const c = ops.trimClip(p, a.str("id"), a.str("edge") as "start" | "end", a.num("t")); return { start: c.start, dur: c.dur }; }) },
  "clip.delete": { help: "{id, ripple?}", run: (s, a) => s.mutate("delete", (p) => ops.deleteClip(p, a.str("id"), a.bool("ripple"))) },
  "clip.duplicate": { help: "{id}", run: (s, a) => s.mutate("duplicate", (p) => ({ id: ops.duplicateClip(p, a.str("id")).id })) },
  "clip.set": {
    help: "{id, patch:{name,dur,in,reverse,volume,fadeIn,fadeOut,fit,blend,flipX,flipY,text:{...},shape:{...},color,chroma:{enabled,color,similarity,smoothness,spill},mask:{shape,x,y,w,h,feather,invert},shake:{amp,freq}}}",
    run: (s, a) => s.mutate("edit clip", (p) => (ops.patchClip(p, a.str("id"), a.obj("patch")), true)),
  },
  "clip.speed": { help: "{id, speed} 0.1–16, keeps the same source range.", run: (s, a) => s.mutate("speed", (p) => r3(ops.setSpeed(p, a.str("id"), a.num("speed")).dur)) },
  "clip.value": {
    help: "{id, prop, v} Set a constant. prop: x y scale scaleX scaleY rotation opacity anchorX anchorY | grade.<exposure|contrast|saturation|vibrance|temperature|tint|hue|lift|gamma|gain|vignette|grain|sharpen|blur> | volume",
    run: (s, a) => s.mutate("value", (p) => ops.setConstant(p, a.str("id"), a.str("prop"), a.num("v"))),
  },
  "clip.key": {
    help: "{id, prop, t, v, ease?: linear|in|out|inOut|hold|back|elastic|bounce} Keyframe at timeline time t.",
    run: (s, a) => s.mutate("keyframe", (p) => ops.keyframe(p, a.str("id"), a.str("prop"), a.num("t"), a.num("v"), a.str("ease", "inOut") as Ease)),
  },
  "clip.keys": {
    help: "{id, prop, keys:[[t,v,ease?],...]} Several keyframes at once (timeline times).",
    run: (s, a) =>
      s.mutate("keyframes", (p) => {
        const keys = a.any("keys");
        if (!Array.isArray(keys)) throw new ops.OpError(`"keys" must be a list.`);
        for (const k of keys as unknown[][]) ops.keyframe(p, a.str("id"), a.str("prop"), Number(k[0]), Number(k[1]), (k[2] as Ease) ?? "inOut");
        return keys.length;
      }),
  },
  "clip.unkey": { help: "{id, prop, t}", run: (s, a) => s.mutate("remove key", (p) => ops.unkeyframe(p, a.str("id"), a.str("prop"), a.num("t"))) },
  "clip.transition": {
    help: `{id, in?: kind, out?: kind, dur?=0.5} kinds: ${TRANSITIONS.join(", ")}`,
    run: (s, a) =>
      s.mutate("transition", (p) => {
        const c = ops.getClip(p, a.str("id"));
        const dur = a.num("dur", 0.5);
        const i = a.optStr("in");
        const o = a.optStr("out");
        if (i) c.transitionIn = { kind: i as TransitionKind, dur };
        if (o) c.transitionOut = { kind: o as TransitionKind, dur };
        return true;
      }),
  },
  "clip.grade": {
    help: `{id, preset?: ${Object.keys(GRADES).join("|")}, values?:{exposure,...}} Colour grade.`,
    run: (s, a) =>
      s.mutate("grade", (p) => {
        const c = ops.getClip(p, a.str("id"));
        const preset = a.optStr("preset");
        if (preset) {
          const g = GRADES[preset];
          if (!g) throw new ops.OpError(`Unknown grade preset "${preset}".`);
          c.grade = { ...g };
        }
        const values = a.any("values") as Record<string, number> | undefined;
        if (values) for (const [k, v] of Object.entries(values)) c.grade[k as keyof Grade] = Number(v);
        return c.grade;
      }),
  },
  "clip.fx": {
    help: "{id, values:{glow, glowThreshold, glowRadius, rgbSplit, glitch, pixelate, zoomBlur, motionBlur, posterize, invert, duotone, scanlines, shadows, highlights, letterbox}} Set effects (constants). Keyframe with clip.key prop 'fx.glow' etc.",
    run: (s, a) =>
      s.mutate("effects", (p) => {
        const c = ops.getClip(p, a.str("id"));
        for (const [k, v] of Object.entries(a.obj("values"))) ops.setConstant(p, c.id, `fx.${k}`, Number(v));
        return c.fx;
      }),
  },
  "clip.wheels": {
    help: "{id, lift?:'#hex', gamma?:'#hex', gain?:'#hex', strength?=0.5} Colour wheels by tint colour: shadows, midtones, highlights (e.g. teal shadows #2a8f9a, orange highlights #ff9d4d).",
    run: (s, a) =>
      s.mutate("colour wheels", (p) => {
        const c = ops.getClip(p, a.str("id"));
        const k = a.num("strength", 0.5);
        const w = c.wheels ?? { lift: [0, 0, 0], gamma: [0, 0, 0], gain: [0, 0, 0] };
        for (const r of ["lift", "gamma", "gain"] as const) if (a.optStr(r)) w[r] = ops.wheelFromColor(a.str(r), k);
        c.wheels = w;
        return w;
      }),
  },
  "clip.duotone": {
    help: "{id, dark:'#hex', light:'#hex', amount?=1}",
    run: (s, a) =>
      s.mutate("duotone", (p) => {
        const c = ops.getClip(p, a.str("id"));
        c.duotone = { dark: a.str("dark"), light: a.str("light") };
        ops.setConstant(p, c.id, "fx.duotone", a.num("amount", 1));
        return true;
      }),
  },
  "clip.crop": {
    help: "{id, l?, t?, r?, b? (fractions 0..0.95 trimmed from each side)} Real crop; the remaining picture is fitted to the frame.",
    run: (s, a) => s.mutate("crop", (p) => ops.setCrop(p, a.str("id"), { l: a.optNum("l"), t: a.optNum("t"), r: a.optNum("r"), b: a.optNum("b") }).crop ?? null),
  },
  "clip.freeze": {
    help: "{id, t, dur?=1.5} Freeze frame at timeline time t; later clips slide right.",
    run: (s, a) => s.mutate("freeze frame", (p) => ({ id: ops.freezeFrame(p, a.str("id"), a.num("t"), a.num("dur", 1.5)).id })),
  },
  "clip.speedRamp": {
    help: `{id, preset?: ${Object.keys(ops.RAMPS).join("|")}|none, keys?: [[fraction, speed], ...]} Smooth speed ramp (time remap); audio pitch follows.`,
    run: (s, a) => s.mutate("speed ramp", (p) => ops.speedRamp(p, a.str("id"), (a.any("keys") as [number, number][] | undefined) ?? a.str("preset", "hero")) ?? null),
  },
  "clip.textPreset": {
    help: `{id, preset: ${Object.keys(ops.TEXT_PRESETS).join("|")}} Pro title look (font, animation, glow, effects).`,
    run: (s, a) => s.mutate("title preset", (p) => (ops.textPreset(p, a.str("id"), a.str("preset")), true)),
  },
  "audio.beats": {
    help: "{assetId, markers?=true} Detect tempo, beats, downbeats and hits; adds beat markers to the timeline.",
    run: async (s, a) => {
      const info = await analyseBeats(s, a.str("assetId"));
      if (a.bool("markers", true)) s.mutate("beat markers", (p) => (p.markers = info.beats.map((t, i) => ({ t, label: `B${i + 1}` }))));
      return { bpm: info.bpm, beats: info.beats.length, firstBeats: info.beats.slice(0, 16), downbeats: info.downbeats.slice(0, 8), onsets: info.onsets.slice(0, 24), duration: info.duration };
    },
  },
  "audio.analyze": {
    help: "{assetId, lyrics?=true, model?: tiny|base|small, language? (e.g. hindi, english), translate?=false, markers?=true} Understand a song: tempo, beats, key, mood, sections (intro/verse/chorus/drop/bridge/outro), energy curve, hits, breaks, and timed lyrics transcribed on the phone (Whisper; model downloads once).",
    run: async (s, a) => {
      const asset = s.project.assets.find((x) => x.id === a.str("assetId"));
      if (!asset || asset.kind === "image") throw new ops.OpError("Pick an audio or video asset.");
      const buf = await s.pool.audioBuffer(asset, new OfflineAudioContext(1, 1, 44100));
      if (!buf) throw new ops.OpError("That file has no audio.");
      const chans: Float32Array[] = [];
      for (let c = 0; c < Math.min(2, buf.numberOfChannels); c++) chans.push(buf.getChannelData(c));
      const info = analyseSong(chans, buf.sampleRate);
      if (a.bool("markers", true)) s.mutate("song markers", (p) => (p.markers = info.sections.map((x) => ({ t: x.start, label: x.label }))));
      let lyrics: { text: string; lines: LyricLine[] } | { error: string } | undefined;
      if (a.bool("lyrics", true)) {
        try {
          const audio = await to16k(buf);
          lyrics = await transcribe(audio, {
            model: (a.optStr("model") as "tiny" | "base" | "small" | undefined) ?? "base",
            language: a.optStr("language"),
            translate: a.bool("translate"),
            onProgress: (stage, pct) => s.setStatus(`${stage === "model" ? "Downloading the lyrics model" : "Listening to the lyrics"} ${pct}%`),
          });
        } catch (e) {
          lyrics = { error: (e as Error).message };
        }
      }
      return { ...info, lyrics };
    },
  },
  "edit.musicVideo": {
    help: `{musicId, assetIds?, genre?: ${Object.keys(mv.GENRES).join("|")}, lyrics?=true, language?, model?: tiny|base|small, lines?:[{start,end,text}] (your own timed lyrics), captions?=true, start?, maxDur?} Full music video from your media: listens to the song (sections, energy, hits, lyrics), cuts to its structure in the genre's style, colour look, punches, transitions, timed lyric captions.`,
    run: async (s, a) => {
      const music = findAsset(s.project, a.str("musicId"));
      if (!music || (music.kind !== "audio" && music.kind !== "video")) throw new ops.OpError("musicId must be a song (audio or video with sound).");
      const buf = await s.pool.audioBuffer(music, new OfflineAudioContext(1, 1, 44100));
      if (!buf) throw new ops.OpError("That file has no audio.");
      const chans: Float32Array[] = [];
      for (let c = 0; c < Math.min(2, buf.numberOfChannels); c++) chans.push(buf.getChannelData(c));
      const song = analyseSong(chans, buf.sampleRate);
      let lines = a.any("lines") as LyricLine[] | undefined;
      let lyricError: string | undefined;
      if (!lines && a.bool("lyrics", true) && a.bool("captions", true)) {
        try {
          const r = await transcribe(await to16k(buf), { model: (a.optStr("model") as "tiny" | "base" | "small" | undefined) ?? "base", language: a.optStr("language"), onProgress: (st, pct) => s.setStatus(`${st === "model" ? "Downloading the lyrics model" : "Listening to the lyrics"} ${pct}%`) });
          lines = r.lines;
        } catch (e) {
          lyricError = (e as Error).message;
        }
      }
      const genre = (a.optStr("genre") as mv.Genre | undefined) ?? mv.guessGenre(song);
      const res = s.mutate(`music video (${genre})`, (p) => mv.musicVideoEdit(p, { musicId: music.id, assetIds: a.any("assetIds") as string[] | undefined, genre, song, start: a.optNum("start"), maxDur: a.optNum("maxDur") }));
      let captions = 0;
      if (lines?.length && a.bool("captions", true)) {
        const cmds = mv.lyricCaptionCommands(lines.filter((l) => l.start < res.duration), genre, a.num("start", 0));
        const out = await runBatch(s, cmds as Command[], true);
        captions = out.filter((r) => r.ok).length;
      }
      return { ...res, bpm: song.bpm, key: song.key, mood: song.mood, sections: song.sections, captions, lyrics: lines, lyricError };
    },
  },
  "clip.pin": {
    help: "{id, tl:[x,y], tr, br, bl (fractions of the frame) | clear:true, glow?: 0..1} Corner pin: put the clip into any four-corner shape in perspective — a monitor, phone, billboard or poster in another shot. glow adds screen light spill.",
    run: (s, a) =>
      s.mutate("corner pin", (p) => {
        const c = ops.getClip(p, a.str("id"));
        if (a.bool("clear", false)) {
          c.pin = undefined;
          return { pinned: false };
        }
        const q = (k: string) => {
          const v = a.any(k) as number[] | undefined;
          if (!Array.isArray(v) || v.length < 2) throw new ops.OpError(`${k} must be [x, y]`);
          return [Number(v[0]), Number(v[1])] as [number, number];
        };
        c.pin = { tl: q("tl"), tr: q("tr"), br: q("br"), bl: q("bl") };
        if (a.optNum("glow") !== undefined) for (const [k, v] of Object.entries({ glow: a.num("glow"), glowThreshold: 0.55, glowRadius: 30 })) ops.setConstant(p, c.id, `fx.${k}`, v);
        return { pinned: true };
      }),
  },
  "clip.look": {
    help: `{id | ids, look: ${Object.keys(mv.LOOKS).join("|")}, strength?=1} Colour look preset (grade, colour wheels, glow).`,
    run: (s, a) =>
      s.mutate("look", (p) => {
        const ids = (a.any("ids") as string[] | undefined) ?? [a.str("id")];
        for (const id of ids) mv.applyLook(ops.getClip(p, id), a.str("look"), a.num("strength", 1));
        return { clips: ids.length };
      }),
  },
  "audio.duck": {
    help: "{amount?=0.3, fade?=0.2, musicIds?} Lower the music under every VolTune/voice clip (sidechain ducking).",
    run: (s, a) => s.mutate("duck music", (p) => mv.duckMusic(p, { amount: a.optNum("amount"), fade: a.optNum("fade"), musicIds: a.any("musicIds") as string[] | undefined })),
  },
  "clip.reframe": {
    help: "{id, focus?: center|left|right|pan} Fit a landscape clip to a vertical frame (or vice versa) with a fixed focus or a slow pan.",
    run: (s, a) => s.mutate("reframe", (p) => mv.reframe(p, a.str("id"), a.str("focus", "center") as never).id),
  },
  "edit.beatCut": {
    help: "{assetIds?, musicId?, style?: punchy|smooth|cinematic|glitch, every? (beats per cut), loop?, replace?=true, maxDur?} Auto-edit your media to the music: beat cuts, overlapping transitions, beat punches, motion blur, look.",
    run: (s, a) =>
      beatCut(s, { assetIds: a.any("assetIds") as string[] | undefined, musicId: a.optStr("musicId"), style: a.optStr("style") as never, every: a.optNum("every"), loop: a.bool("loop"), replace: a.bool("replace", true), maxDur: a.optNum("maxDur") }),
  },
  "media.strip": {
    help: "{assetId, times?:[s...] | count?=8, cell?=160, cols?} One image of many moments of a clip, labelled with times (cheap way to see a whole video).",
    run: (s, a) => frameStrip(s.project, s.pool, a.str("assetId"), { times: a.any("times") as number[] | undefined, count: a.optNum("count"), cell: a.optNum("cell"), cols: a.optNum("cols") }),
  },
  "media.scenes": {
    help: "{assetId, fps?=6} Shot changes, frozen stretches (end cards, freeze frames), black stretches, and contentEnd (where real footage ends before an outro card).",
    run: (s, a) => detectScenes(s.project, s.pool, a.str("assetId"), a.num("fps", 6)),
  },
  "media.findOverlays": {
    help: "{assetId, samples?=12, from?, to?} Find burned-in static overlays (logos, stickers, text) as boxes {x,y,w,h} (centre/size, fractions of the frame), best first. Feed them to clip.remove.",
    run: (s, a) => findOverlays(s.project, s.pool, a.str("assetId"), { samples: a.optNum("samples"), from: a.optNum("from"), to: a.optNum("to") }),
  },
  "clip.remove": {
    help: "{id, x, y, w, h (centre/size as fractions of the source frame), mode?: auto|ai|clone|fill|blur|pixelate (ai = on-phone AI fill, best for photos and complex backgrounds, slow on long videos), shape?: rect|ellipse, feather?=0.15, from?, to? (source seconds), xKeys?, yKeys? ([[sourceSec, value], ...] for moving objects), offset?:[dx,dy]} Remove an object or logo: filled from its surroundings with texture, frame size and length unchanged. Returns {regionId}.",
    run: (s, a) =>
      s.mutate("remove object", (p) => {
        const c = ops.getClip(p, a.str("id"));
        const as = findAsset(p, c.assetId);
        if (!as || (as.kind !== "video" && as.kind !== "image")) throw new ops.OpError("Object removal works on video and photo clips.");
        const keys = (k: string) => (a.any(k) as [number, number][] | undefined)?.map(([t, v]) => ({ t, v, ease: "inOut" as Ease }));
        const r: RemoveRegion = {
          id: uid("rm"),
          x: a.num("x"),
          y: a.num("y"),
          w: Math.max(0.005, a.num("w")),
          h: Math.max(0.005, a.num("h")),
          shape: (a.optStr("shape") as RemoveRegion["shape"] | undefined) ?? "rect",
          mode: (a.optStr("mode") as RemoveRegion["mode"] | undefined) ?? "auto",
          feather: Math.max(0.02, Math.min(0.5, a.num("feather", 0.15))),
        };
        if (a.optNum("from") !== undefined) r.from = a.num("from");
        if (a.optNum("to") !== undefined) r.to = a.num("to");
        if (keys("xKeys")) r.xKeys = keys("xKeys");
        if (keys("yKeys")) r.yKeys = keys("yKeys");
        if (a.any("offset")) r.offset = a.any("offset") as [number, number];
        (c.remove ??= []).push(r);
        return { regionId: r.id, regions: c.remove.length };
      }),
  },
  "clip.select": {
    help: "{id, points:[[x,y,label?=1],...] (fractions of the source frame; label 0 = not this), box?:[x0,y0,x1,y1] (fractions), at? (source seconds, default the playhead), grow?=0.006 (fraction of the frame), mode?: ai|auto|fill|clone|blur|pixelate|recolor, color? (hex, for recolor), from?, to?} Tap-to-select: on-phone AI finds the exact shape of the object at the points and removes it (default AI fill). Returns {regionId, box, score}.",
    run: async (s, a) => {
      const c = ops.getClip(s.project, a.str("id"));
      const as = findAsset(s.project, c.assetId);
      if (!as || (as.kind !== "video" && as.kind !== "image")) throw new ops.OpError("Tap-to-select works on video and photo clips.");
      const t = a.optNum("at") ?? Math.max(0, c.in + (s.time - c.start) * c.speed);
      const src = as.kind === "image" ? ((await s.pool.image(as)) as CanvasImageSource) : ((await (await s.pool.framer(as, "select", 1920))?.frameAt(t))?.canvas as CanvasImageSource | undefined);
      if (!src) throw new ops.OpError("Could not read that frame.");
      const W = (src as { width: number }).width,
        H = (src as { height: number }).height;
      const pts = ((a.any("points") as number[][] | undefined) ?? []).map((p) => [p[0]! * W, p[1]! * H, p[2] ?? 1] as [number, number, number]);
      const bx = a.any("box") as number[] | undefined;
      if (!pts.length && !bx) throw new ops.OpError("Give points (where the object is) or a box.");
      const seg = await segment(src, W, H, pts, bx ? [bx[0]! * W, bx[1]! * H, bx[2]! * W, bx[3]! * H] : undefined, (m) => s.setStatus(m));
      const mask = dilate(seg.mask, W, H, Math.round(a.num("grow", 0.006) * Math.max(W, H)));
      const b = maskBounds(mask, W, H);
      if (!b) throw new ops.OpError("Nothing was selected there. Try another point.");
      const png = await maskToPng(mask, W, H);
      return s.mutate("select & remove", (p) => {
        const cc = ops.getClip(p, a.str("id"));
        const r: RemoveRegion = {
          id: uid("rm"),
          x: (b.x0 + b.x1) / 2 / W,
          y: (b.y0 + b.y1) / 2 / H,
          w: (b.x1 - b.x0) / W,
          h: (b.y1 - b.y0) / H,
          shape: "rect",
          mode: (a.optStr("mode") as RemoveRegion["mode"] | undefined) ?? "ai",
          feather: 0.04,
          mask: png,
        };
        if (a.optStr("color")) r.color = a.str("color");
        if (a.optNum("from") !== undefined) r.from = a.num("from");
        if (a.optNum("to") !== undefined) r.to = a.num("to");
        (cc.remove ??= []).push(r);
        return { regionId: r.id, box: { x: r3(r.x), y: r3(r.y), w: r3(r.w), h: r3(r.h) }, score: r3(seg.score), coverage: r3(mask.reduce((n, v) => n + (v ? 1 : 0), 0) / (W * H)) };
      });
    },
  },
  "clip.addRegion": {
    help: "{id, mask (PNG data URL over the source frame, white/opaque = area), mode: ai|smart|recolor|blur|pixelate|fill|clone|auto, color?, prompt?, from?, to?, feather?=0.04} Add a removal/repaint area from a painted mask. Returns {regionId}.",
    run: async (s, a) => {
      const png = a.str("mask");
      const mk = await decodeMask(png);
      let x0 = mk.w, y0 = mk.h, x1 = 0, y1 = 0;
      for (let y = 0; y < mk.h; y++)
        for (let x = 0; x < mk.w; x++)
          if (mk.data[y * mk.w + x]! > 127) {
            if (x < x0) x0 = x;
            if (x > x1) x1 = x;
            if (y < y0) y0 = y;
            if (y > y1) y1 = y;
          }
      if (x1 < x0) throw new ops.OpError("The mask is empty.");
      return s.mutate("add area", (p) => {
        const cc = ops.getClip(p, a.str("id"));
        const r: RemoveRegion = { id: uid("rm"), x: (x0 + x1 + 1) / 2 / mk.w, y: (y0 + y1 + 1) / 2 / mk.h, w: (x1 - x0 + 1) / mk.w, h: (y1 - y0 + 1) / mk.h, shape: "rect", mode: a.str("mode", "ai") as RemoveRegion["mode"], feather: a.num("feather", 0.04), mask: png };
        if (a.optStr("color")) r.color = a.str("color");
        if (a.optStr("prompt")) r.prompt = a.str("prompt");
        if (a.optNum("from") !== undefined) r.from = a.num("from");
        if (a.optNum("to") !== undefined) r.to = a.num("to");
        (cc.remove ??= []).push(r);
        return { regionId: r.id };
      });
    },
  },
  "smartfill.status": {
    help: "Is Smart fill (generative repaint on the phone) downloaded, how big is it, does the phone have GPU compute (WebGPU)?",
    run: async () => {
      const st = await genStatus();
      const g = (navigator as Navigator & { gpu?: { requestAdapter(): Promise<unknown> } }).gpu;
      let webgpu = false;
      try {
        webgpu = !!(g && (await g.requestAdapter()));
      } catch {
        /* no */
      }
      return { ...st, webgpu, memory: await memInfo() };
    },
  },
  "smartfill.download": {
    help: "Download the Smart fill models once (~2.1 GB). Waits until done.",
    run: async (s) => {
      await genDownload((m) => s.setStatus(m));
      s.setStatus("");
      return await genStatus();
    },
  },
  "clip.smartFill": {
    help: "{id, regionId? (an existing removal area, e.g. from clip.select) | points?/box? (select now), prompt? (what should be there, e.g. 'bare arms, white t-shirt sleeves'), steps?=20, guidance?=7.5, seed?, at? (source seconds)} Generative repaint on the phone: draws what was never visible (skin, arms, cloth, background) and bakes it into the clip. Slow (about 1–3 min on GPU).",
    run: async (s, a) => {
      const c = ops.getClip(s.project, a.str("id"));
      const as = findAsset(s.project, c.assetId);
      if (!as || (as.kind !== "video" && as.kind !== "image")) throw new ops.OpError("Smart fill works on video and photo clips.");
      const t = a.optNum("at") ?? Math.max(0, c.in + (s.time - c.start) * c.speed);
      const src = as.kind === "image" ? ((await s.pool.image(as)) as CanvasImageSource) : ((await (await s.pool.framer(as, "smartfill", 1920))?.frameAt(t))?.canvas as CanvasImageSource | undefined);
      if (!src) throw new ops.OpError("Could not read that frame.");
      const W = (src as { width: number }).width,
        H = (src as { height: number }).height;
      let region = a.optStr("regionId") ? (c.remove ?? []).find((r) => r.id === a.str("regionId")) : undefined;
      let mask: Uint8Array;
      if (region?.mask) mask = await maskAtSize(region.mask, W, H);
      else {
        const pts = ((a.any("points") as number[][] | undefined) ?? []).map((p) => [p[0]! * W, p[1]! * H, p[2] ?? 1] as [number, number, number]);
        const bx = a.any("box") as number[] | undefined;
        if (!pts.length && !bx) throw new ops.OpError("Give regionId, points or a box.");
        const seg = await segment(src, W, H, pts, bx ? [bx[0]! * W, bx[1]! * H, bx[2]! * W, bx[3]! * H] : undefined, (m) => s.setStatus(m));
        mask = dilate(seg.mask, W, H, Math.round(a.num("grow", 0.006) * Math.max(W, H)));
      }
      const b = maskBounds(mask, W, H);
      if (!b) throw new ops.OpError("Nothing selected.");
      const work = makeCanvasA(W, H);
      const wx = ctx2dA(work);
      wx.drawImage(src, 0, 0, W, H);
      const res = await smartFill(wx, W, H, mask, { prompt: a.optStr("prompt"), steps: a.optNum("steps"), guidance: a.optNum("guidance"), seed: a.optNum("seed"), force: a.bool("force", false) }, (m) => s.setStatus(m));
      s.setStatus("");
      // Bake: keep only the repainted pixels (plus the soft edge) in a transparent full-frame patch.
      const keep = dilate(mask, W, H, Math.max(5, Math.round(Math.max(W, H) / 128) + 4));
      const k = Math.min(1, 1600 / Math.max(W, H));
      const pw = Math.round(W * k),
        ph = Math.round(H * k);
      const full = wx.getImageData(0, 0, W, H);
      for (let i = 0; i < W * H; i++) if (!keep[i]) full.data[i * 4 + 3] = 0;
      const pc = makeCanvasA(W, H);
      ctx2dA(pc).putImageData(full, 0, 0);
      const small = makeCanvasA(pw, ph);
      ctx2dA(small).drawImage(pc as CanvasImageSource, 0, 0, pw, ph);
      const blob = "convertToBlob" in small ? await (small as OffscreenCanvas).convertToBlob({ type: "image/png" }) : await new Promise<Blob>((ok) => (small as HTMLCanvasElement).toBlob((bb) => ok(bb!), "image/png"));
      const patch = await new Promise<string>((ok) => {
        const fr = new FileReader();
        fr.onload = () => ok(String(fr.result));
        fr.readAsDataURL(blob);
      });
      const png = region?.mask ?? (await maskToPng(mask, W, H));
      return s.mutate("smart fill", (p) => {
        const cc = ops.getClip(p, a.str("id"));
        cc.remove ??= [];
        let r = region ? cc.remove.find((q) => q.id === region!.id) : undefined;
        if (!r) {
          r = { id: uid("rm"), x: (b.x0 + b.x1) / 2 / W, y: (b.y0 + b.y1) / 2 / H, w: (b.x1 - b.x0) / W, h: (b.y1 - b.y0) / H, shape: "rect", mode: "smart", feather: 0.04, mask: png };
          cc.remove.push(r);
        }
        r.mode = "smart";
        r.patch = patch;
        if (a.optStr("prompt")) r.prompt = a.str("prompt");
        region = r;
        return { regionId: r.id, provider: res.provider, seconds: res.seconds, patchKB: Math.round(patch.length / 1365) };
      });
    },
  },
  "render.still": {
    help: "{t?=playhead, name?, format?: png|jpeg} Save the edit at time t as a full-resolution picture in Pictures/VideoStudio.",
    run: async (s, a) => {
      const fmt = a.str("format", "png") as "png" | "jpeg";
      const blob = await stillImage(s.project, s.pool, a.num("t", s.time), fmt);
      const name = `${a.str("name", s.project.name || "Still")}_${Date.now().toString(36)}.${fmt === "png" ? "png" : "jpg"}`;
      return { ...(await saveBlob(blob, name, "Pictures")), bytes: blob.size, width: s.project.width, height: s.project.height };
    },
  },
  "clip.removeClear": {
    help: "{id, regionId?} Delete one removal region (or all of them) from a clip.",
    run: (s, a) =>
      s.mutate("clear removal", (p) => {
        const c = ops.getClip(p, a.str("id"));
        c.remove = a.optStr("regionId") ? (c.remove ?? []).filter((r) => r.id !== a.str("regionId")) : undefined;
        if (c.remove && !c.remove.length) c.remove = undefined;
        return { regions: c.remove?.length ?? 0 };
      }),
  },
  "clip.dropOutro": {
    help: "{id, keep?: stretch|ripple} Cut a frozen/black end card (outro, logo card) off a video clip. stretch (default) slows the remaining footage so the clip keeps its length (no gap); ripple pulls later clips on the track earlier.",
    run: async (s, a) => {
      const c0 = ops.getClip(s.project, a.str("id"));
      const as = findAsset(s.project, c0.assetId);
      if (!as || as.kind !== "video") throw new ops.OpError("Pick a video clip.");
      const sc = await detectScenes(s.project, s.pool, as.id);
      const end = sc.contentEnd - 0.05;
      return s.mutate("drop outro", (p) => {
        const c = ops.getClip(p, a.str("id"));
        const srcEnd = c.in + (c.speedKeys?.length ? rampIntegral(c.speedKeys, c.dur) : c.dur * c.speed);
        if (srcEnd <= end) return { changed: false, contentEnd: sc.contentEnd };
        const usable = Math.max(0.1, end - c.in);
        if (a.str("keep", "stretch") === "ripple") {
          let newDur = usable / c.speed;
          if (c.speedKeys?.length) {
            let lo = 0,
              hi = c.dur;
            for (let i = 0; i < 30; i++) {
              const mid = (lo + hi) / 2;
              if (rampIntegral(c.speedKeys, mid) < usable) lo = mid;
              else hi = mid;
            }
            newDur = lo;
          }
          const cut = c.dur - newDur;
          const tr = p.tracks.find((t) => t.clips.includes(c))!;
          for (const o of tr.clips) if (o.start > c.start) o.start -= cut;
          c.dur = newDur;
        } else {
          c.speed = usable / c.dur;
          if (c.speedKeys) c.speedKeys = c.speedKeys.map((k) => ({ ...k, v: k.v * (usable / (srcEnd - c.in)) }));
        }
        return { changed: true, contentEnd: sc.contentEnd, speed: r3(c.speed), dur: r3(c.dur) };
      });
    },
  },
  "media.thumb": {
    help: "{assetId, t?, max?=320} See an image or video frame (returns a small JPEG data URL).",
    run: (s, a) => assetThumb(s.project, s.pool, a.str("assetId"), a.num("t", 0), Math.min(640, a.num("max", 320))),
  },
  "media.sheet": {
    help: "{assetIds?, cell?=200, cols?=4} One numbered contact sheet of many assets (JPEG data URL).",
    run: (s, a) => contactSheet(s.project, s.pool, (a.any("assetIds") as string[] | undefined) ?? s.project.assets.map((x) => x.id), Math.min(320, a.num("cell", 200)), Math.round(a.num("cols", 4))),
  },
  "render.frame": {
    help: "{t, max?=360} See the edit as it renders at time t (JPEG data URL).",
    run: (s, a) => frameSnapshot(s.project, s.pool, a.num("t"), Math.min(720, a.num("max", 360))),
  },
  "anim.preview": {
    help: "{sceneId, frame?, max?=360} See a drawing frame (JPEG data URL).",
    run: (s, a) => sceneSnapshot(s.project, s.pool, a.str("sceneId"), Math.round(a.num("frame", 0)), Math.min(720, a.num("max", 360))),
  },
  "clip.kenBurns": { help: "{id, strength?=0.15} Slow push-in for photos.", run: (s, a) => s.mutate("ken burns", (p) => ops.kenBurns(p, a.str("id"), a.num("strength", 0.15))) },

  "anim.scene.new": {
    help: "{name?, width?, height?, fps?=24, frames?=24, background?} New drawing scene.",
    run: (s, a) =>
      s.mutate("new scene", (p) => {
        const sc = anim.createScene(p, { name: a.optStr("name"), width: a.optNum("width"), height: a.optNum("height"), fps: a.optNum("fps"), frames: a.optNum("frames"), background: a.optStr("background") });
        return { id: sc.id, layers: sc.layers.map((l) => ({ id: l.id, name: l.name })) };
      }),
  },
  "anim.scene.set": {
    help: "{sceneId, name?, fps?, frames?, background?}",
    run: (s, a) =>
      s.mutate("scene", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        if (a.optStr("name")) sc.name = a.str("name");
        if (a.optNum("fps")) sc.fps = a.num("fps");
        if (a.optNum("frames")) sc.frames = Math.max(1, Math.round(a.num("frames")));
        if (a.optStr("background") !== undefined) sc.background = a.str("background");
        return true;
      }),
  },
  "anim.layer.add": {
    help: "{sceneId, name?, kind?: draw|reference, assetId? (reference image), above?}",
    run: (s, a) =>
      s.mutate("add layer", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = anim.addLayer(sc, a.optStr("name"), (a.optStr("kind") as "draw" | "reference") ?? "draw", a.optStr("above"));
        if (a.optStr("assetId")) {
          if (findAsset(p, a.str("assetId"))?.kind !== "image") throw new ops.OpError("Reference must be an image asset.");
          l.assetId = a.str("assetId");
          l.kind = "reference";
          l.opacity = 0.5;
        }
        return { id: l.id };
      }),
  },
  "anim.layer.set": {
    help: "{sceneId, layerId, name?, visible?, locked?, opacity?, blend?}",
    run: (s, a) =>
      s.mutate("layer", (p) => {
        const l = anim.getLayer(anim.getScene(p, a.str("sceneId")), a.str("layerId"));
        if (a.optStr("name")) l.name = a.str("name");
        for (const k of ["visible", "locked"] as const) if (a.any(k) !== undefined) l[k] = a.bool(k);
        if (a.optNum("opacity") !== undefined) l.opacity = a.num("opacity");
        if (a.optStr("blend")) l.blend = a.str("blend") as never;
        return true;
      }),
  },
  "anim.layer.key": {
    help: "{sceneId, layerId, prop: x|y|scale|rotation|opacity|anchorX|anchorY, frame, v, ease?} Cut-out motion keyframe.",
    run: (s, a) =>
      s.mutate("layer key", (p) => {
        const l = anim.getLayer(anim.getScene(p, a.str("sceneId")), a.str("layerId"));
        const prop = a.str("prop") as keyof typeof l.transform;
        const fallback = prop === "scale" || prop === "opacity" || prop === "scaleX" || prop === "scaleY" ? 1 : prop === "anchorX" || prop === "anchorY" ? 0.5 : 0;
        l.transform[prop] = (() => {
          const cur = l.transform[prop];
          const list = typeof cur === "number" ? [{ t: 0, v: cur, ease: "inOut" as Ease }] : cur ? [...cur] : fallback !== a.num("v") && a.num("frame") > 0 ? [{ t: 0, v: fallback, ease: "inOut" as Ease }] : [];
          const f = a.num("frame");
          const i = list.findIndex((k) => k.t === f);
          const k = { t: f, v: a.num("v"), ease: a.str("ease", "inOut") as Ease };
          if (i >= 0) list[i] = k;
          else list.push(k);
          return list.sort((x, y) => x.t - y.t);
        })();
        return true;
      }),
  },
  "anim.stroke": {
    help: "{sceneId, layerId, frame, points:[[x,y,pressure],...], tool?: pen|pencil|ink|brush|marker|airbrush|eraser, color?, size?, opacity?, closed?, fill?} Draw in scene pixels.",
    run: (s, a) =>
      s.mutate("draw", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const st: Omit<Stroke, "id"> = { tool: a.str("tool", "ink") as Stroke["tool"], color: a.str("color", "#111111"), size: a.num("size", 8), opacity: a.num("opacity", 1), pts: toPts(a), closed: a.bool("closed"), fill: a.optStr("fill") };
        return { id: anim.addStroke(sc, a.str("layerId"), Math.round(a.num("frame", 0)), st).id };
      }),
  },
  "anim.shape": {
    help: "{sceneId, layerId, frame, shape: rect|ellipse|line, x, y (centre; start for line), w, h, color?, size?, fill?}",
    run: (s, a) =>
      s.mutate("draw shape", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const shape = a.str("shape", "rect");
        const st: Omit<Stroke, "id"> = { tool: "pen", color: a.str("color", "#111111"), size: a.num("size", 6), opacity: 1, pts: shapePts(shape, a.num("x"), a.num("y"), a.num("w"), a.num("h")), closed: shape !== "line" && !!a.optStr("fill"), fill: a.optStr("fill") };
        return { id: anim.addStroke(sc, a.str("layerId"), Math.round(a.num("frame", 0)), st).id };
      }),
  },
  "anim.key": { help: "{sceneId, frame, layerId?, blank?} Start a new drawing (copy of the held one, or blank).", run: (s, a) => s.mutate("key", (p) => anim.keyFrame(anim.getScene(p, a.str("sceneId")), Math.round(a.num("frame")), a.optStr("layerId"), a.bool("blank"))) },
  "anim.clear": { help: "{sceneId, layerId, frame}", run: (s, a) => s.mutate("clear", (p) => anim.clearCel(anim.getScene(p, a.str("sceneId")), a.str("layerId"), Math.round(a.num("frame")))) },
  "anim.frames.insert": { help: "{sceneId, at, count?=1}", run: (s, a) => s.mutate("insert frames", (p) => anim.insertFrames(anim.getScene(p, a.str("sceneId")), Math.round(a.num("at")), Math.round(a.num("count", 1)))) },
  "anim.frames.delete": { help: "{sceneId, at, count?=1}", run: (s, a) => s.mutate("delete frames", (p) => anim.deleteFrames(anim.getScene(p, a.str("sceneId")), Math.round(a.num("at")), Math.round(a.num("count", 1)))) },
  "anim.frames.duplicate": { help: "{sceneId, frame}", run: (s, a) => s.mutate("duplicate frame", (p) => anim.duplicateFrame(anim.getScene(p, a.str("sceneId")), Math.round(a.num("frame")))) },
  "anim.inbetween": {
    help: "{sceneId, layerId, from, to, spacing?: even|slowIn|slowOut|slowInOut, step?=1} Auto-draw inbetweens between two keys.",
    run: (s, a) => s.mutate("inbetween", (p) => anim.inbetween(anim.getScene(p, a.str("sceneId")), a.str("layerId"), Math.round(a.num("from")), Math.round(a.num("to")), (a.optStr("spacing") as never) ?? "slowInOut", Math.max(1, Math.round(a.num("step", 1))))),
  },
  "anim.lipsync": {
    help: "{sceneId, layerId, assetId (voice), shapes:[closedFrame, ..., openFrame], start?=0} Mouth drawings follow the voice.",
    run: (s, a) =>
      s.mutate("lip sync", (p) => {
        const asset = findAsset(p, a.str("assetId"));
        if (!asset?.mouth) throw new ops.OpError("That asset has no lip-sync data (make it with voice.speak).");
        const shapes = a.any("shapes");
        if (!Array.isArray(shapes)) throw new ops.OpError(`"shapes" must be a list of frame numbers.`);
        return anim.lipSync(anim.getScene(p, a.str("sceneId")), a.str("layerId"), asset.mouth, shapes.map(Number), Math.round(a.num("start", 0)));
      }),
  },
  "anim.rig.create": {
    help: "{sceneId, x?, y? (hip joint px), height? (px, head to sole), view?: side|front, skin?, shirt?, pants?, shoes?, hair?, outline?, name?} Jointed puppet: hips, torso, head, upperArm/foreArm/hand and thigh/shin/foot for B (far) and F (near) sides.",
    run: (s, a) =>
      s.mutate("create rig", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const h = a.num("height", sc.height * 0.62);
        const st: Partial<rig.RigStyle> = {};
        for (const k of ["skin", "shirt", "pants", "shoes", "hair", "outline"] as const) if (a.optStr(k)) st[k] = a.str(k);
        if (a.optStr("view")) st.view = a.str("view") as "side" | "front";
        const r = rig.buildRig(sc, a.num("x", sc.width / 2), a.num("y", sc.height * 0.55), h, st, a.str("name", "Puppet"));
        return r;
      }),
  },
  "anim.rig.bind": {
    help: "{sceneId, layerId, part, parentLayerId?, pivot:[x,y], tip:[x,y], rig?} Turn your own drawn layer into a rig part (rigs from your drawings).",
    run: (s, a) =>
      s.mutate("bind part", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = anim.getLayer(sc, a.str("layerId"));
        const pv = a.any("pivot") as number[] | undefined;
        const tp = a.any("tip") as number[] | undefined;
        if (!Array.isArray(pv) || pv.length < 2) throw new ops.OpError("pivot must be [x, y].");
        l.pivot = { x: Number(pv[0]), y: Number(pv[1]) };
        if (Array.isArray(tp) && tp.length >= 2) l.tip = { x: Number(tp[0]), y: Number(tp[1]) };
        l.part = rig.partName(a.str("part"));
        l.rig = a.str("rig", sc.layers.find((x) => x.rig)?.rig ?? uid("rig"));
        if (a.optStr("parentLayerId")) l.parent = a.str("parentLayerId");
        return { rig: l.rig, part: l.part };
      }),
  },
  "anim.rig.skeleton": {
    help: "{sceneId, frame?, rig?} Joint positions (pivot → tip, px) and current angle for every part.",
    run: (s, a) => rig.skeleton(anim.getScene(s.project, a.str("sceneId")), a.optStr("rig"), Math.round(a.num("frame", 0))),
  },
  "anim.joint": {
    help: "{sceneId, part, frame, angle (deg; negative swings a limb forward, positive bends knees), ease?, rig?} Rotate one joint at a frame.",
    run: (s, a) => s.mutate("joint", (p) => rig.applyPose(anim.getScene(p, a.str("sceneId")), a.optStr("rig"), Math.round(a.num("frame")), { [rig.partName(a.str("part"))]: a.num("angle") }, a.str("ease", "inOut") as Ease)),
  },
  "anim.pose": {
    help: "{sceneId, frame, pose:{torso:5, head:-3, upperArmF:-90, foreArmF:-20, thighF:-30, shinF:40, ..., rootX, rootY}, ease?, rig?} Key a whole pose.",
    run: (s, a) => s.mutate("pose", (p) => rig.applyPose(anim.getScene(p, a.str("sceneId")), a.optStr("rig"), Math.round(a.num("frame")), a.obj("pose") as rig.Pose, a.str("ease", "inOut") as Ease)),
  },
  "anim.pose.get": {
    help: "{sceneId, frame, rig?} Read the pose at a frame.",
    run: (s, a) => rig.readPose(anim.getScene(s.project, a.str("sceneId")), a.optStr("rig"), Math.round(a.num("frame"))),
  },
  "anim.ik": {
    help: "{sceneId, limb: armF|armB|legF|legB, x, y, frame, ease?, rig?} Reach a hand/foot (wrist/ankle) to a point; solves both joints.",
    run: (s, a) => s.mutate("ik", (p) => ({ reached: rig.solveIK(anim.getScene(p, a.str("sceneId")), a.optStr("rig"), a.str("limb"), a.num("x"), a.num("y"), Math.round(a.num("frame")), a.str("ease", "inOut") as Ease) })),
  },
  "anim.motion": {
    help: `{sceneId, type: ${rig.MOTIONS.join("|")}, start?, cycles?, frames? (per cycle), travel? (px per cycle), energy?=1, rig?} Generate body motion as keyframes on every joint.`,
    run: (s, a) =>
      s.mutate("motion", (p) =>
        rig.motion(anim.getScene(p, a.str("sceneId")), a.optStr("rig"), a.str("type") as rig.MotionType, { start: a.optNum("start"), cycles: a.optNum("cycles"), frames: a.optNum("frames"), travel: a.optNum("travel"), energy: a.optNum("energy") }),
      ),
  },
  "anim.toTimeline": {
    help: "{sceneId, start?, trackId?, dur?} Put the scene on the editor timeline (loops if dur is longer).",
    run: (s, a) => s.mutate("scene to timeline", (p) => ({ id: ops.addClip(p, { sceneId: a.str("sceneId"), start: a.optNum("start"), trackId: a.optStr("trackId"), dur: a.optNum("dur") }).id })),
  },
  "anim.frame": {
    help: "{sceneId, frame} What each layer exposes at a frame.",
    run: (s, a) => {
      const sc = anim.getScene(s.project, a.str("sceneId"));
      const f = Math.round(a.num("frame"));
      return sc.layers.map((l) => ({ layerId: l.id, name: l.name, key: exposedKey(l, f), strokes: (l.cels[exposedKey(l, f) ?? ""]?.strokes.length ?? 0) }));
    },
  },

  /* ---------------- characters, backdrops and one-command shots ---------------- */
  "char.help": {
    help: "Channels, actions, expressions, stances, backdrops and palettes for characters.",
    run: () => ({
      channels: puppet.CHANNELS,
      actions: puppet.ACTIONS,
      expressions: Object.keys(puppet.EXPRESSIONS),
      stances: Object.keys(puppet.STANCES),
      backdrops: puppet.BACKDROPS,
      palettes: Object.keys(puppet.PALETTES),
      handPoses: "0 relaxed, 1 open, 2 pinch, 3 fist, 4 in pocket, 5 point",
      notes: "Times in actions are seconds. Hand targets are relative to the shoulder in head units (1 u = height/7.6).",
    }),
  },
  "char.create": {
    help: "{sceneId, name?, x?, y? (ground px), height? (px), hair?: messy|short|long|spiky, jacket?: leather|hoodie|coat|none, shirt?: turtleneck|tee|shirt, chain?, props?: [cigarette], propHand?: R|L, palette?: black|white|red|navy|brown|suit, colors?:{skin,hair,hairShade,hairHi,eyes,jacket,...}, facing?: 1|-1, element?: fire|ice, stance?, expr?} Add a fully drawn, rigged anime character (curves, cel shading, face rig, auto blink, hair follow-through).",
    run: (s, a) =>
      s.mutate("add character", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const spec = puppet.makeSpec({
          x: a.num("x", sc.width / 2),
          y: a.num("y", sc.height * 0.9),
          height: a.num("height", sc.height * 0.66),
          hair: a.optStr("hair") as never,
          jacket: a.optStr("jacket") as never,
          shirt: a.optStr("shirt") as never,
          chain: a.any("chain") === undefined ? undefined : a.bool("chain"),
          props: (a.any("props") as string[] | undefined) ?? [],
          propHand: (a.optStr("propHand") as "L" | "R" | undefined) ?? "R",
          palette: a.optStr("palette"),
          colorOverrides: (a.any("colors") as Partial<puppet.PuppetPalette> | undefined) ?? {},
          seed: a.num("seed", Math.floor(Math.random() * 1000)),
          facing: a.num("facing", 1) < 0 ? -1 : 1,
          element: a.optStr("element") as "fire" | "ice" | "none" | undefined,
        });
        const top = sc.layers.find((l) => l.kind === "draw");
        const l = anim.addLayer(sc, a.str("name", "Character"), "puppet", top?.id);
        l.puppet = spec;
        l.chan = {};
        const steps: puppet.ActionStep[] = [];
        if (a.optStr("stance")) steps.push({ at: 0, do: "stance", name: a.str("stance"), dur: 0.001 });
        if (a.optStr("expr")) steps.push({ at: 0, do: "expr", name: a.str("expr"), dur: 0.001 });
        if (steps.length) puppet.applyActions(l, sc.fps, steps);
        return { layerId: l.id };
      }),
  },
  "char.act": {
    help: "{sceneId, layerId, actions:[{at (s), do: stance|expr|look|smoke|exhale|nod|shake|tilt|shrug|wave|point|gesture|lean|turn|laugh|breathe|set|jab|cross|uppercut|kick|block|dodge|dash{dist}|hit{force}|fly{dist,height}|land|getup|wipe|charge|jump{height}|hitstop{dur}, speed?, ...}]} Animate a character with high-level actions (keys every channel for you). Fight moves face the opponent; rootX moves are relative. Run char.help for options.",
    run: (s, a) =>
      s.mutate("character actions", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = anim.getLayer(sc, a.str("layerId"));
        if (l.kind !== "puppet") throw new ops.OpError("That layer is not a character.");
        const steps = a.any("actions");
        if (!Array.isArray(steps)) throw new ops.OpError(`"actions" must be a list.`);
        const end = puppet.applyActions(l, sc.fps, steps as puppet.ActionStep[]);
        sc.frames = Math.max(sc.frames, Math.ceil(end * sc.fps) + 1);
        return { end: r3(end), frames: sc.frames };
      }),
  },
  "char.key": {
    help: "{sceneId, layerId, at (s) | frame, values:{channel:value,...}, ease?} Key raw channels (pose, face, smoke).",
    run: (s, a) =>
      s.mutate("character keys", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = anim.getLayer(sc, a.str("layerId"));
        const f = a.optNum("frame") ?? Math.round(a.num("at", 0) * sc.fps);
        for (const [k, val] of Object.entries(a.obj("values"))) puppet.setChannelKey(l, k, f, Number(val), a.str("ease", "inOut") as Ease);
        return true;
      }),
  },
  "char.set": {
    help: "{sceneId, layerId, x?, y?, height?, hair?, jacket?, shirt?, chain?, props?, propHand?, colors?} Change a character's look.",
    run: (s, a) =>
      s.mutate("character look", (p) => {
        const l = anim.getLayer(anim.getScene(p, a.str("sceneId")), a.str("layerId"));
        if (!l.puppet) throw new ops.OpError("That layer is not a character.");
        const sp = l.puppet;
        for (const k of ["x", "y", "height"] as const) if (a.optNum(k) !== undefined) sp[k] = a.num(k);
        for (const k of ["hair", "jacket", "shirt", "propHand", "element"] as const) if (a.optStr(k)) (sp as unknown as Record<string, string>)[k] = a.str(k);
        if (a.optNum("facing") !== undefined) sp.facing = a.num("facing") < 0 ? -1 : 1;
        if (a.any("chain") !== undefined) sp.chain = a.bool("chain");
        if (Array.isArray(a.any("props"))) sp.props = a.any("props") as string[];
        if (a.any("colors")) sp.colors = { ...sp.colors, ...(a.obj("colors") as Partial<puppet.PuppetPalette>) };
        return true;
      }),
  },
  "char.lipsync": {
    help: "{sceneId, layerId, assetId (voice), at? (s in the scene)} Mouth follows a VolTune voice.",
    run: (s, a) =>
      s.mutate("character lip sync", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = anim.getLayer(sc, a.str("layerId"));
        const asset = findAsset(p, a.str("assetId"));
        if (!asset?.mouth) throw new ops.OpError("That asset has no lip-sync data (make it with voice.speak).");
        return { keys: puppet.puppetLipSync(l, sc.fps, asset.mouth, a.num("at", 0)) };
      }),
  },
  "char.say": {
    help: "{sceneId, layerId, text, at? (s), voice?, speed?, clipStart? (timeline s where the scene starts, default 0)} VolTune speaks the line, puts it on the timeline and lip-syncs the character.",
    run: async (s, a) => {
      const at = a.num("at", 0);
      const { result, asset } = await s.speak({ text: a.str("text"), voice: a.optStr("voice"), speed: a.optNum("speed") });
      return s.mutate("character line", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = anim.getLayer(sc, a.str("layerId"));
        const clip = ops.addClip(p, { assetId: asset.id, start: a.num("clipStart", 0) + at });
        if (asset.mouth) puppet.puppetLipSync(l, sc.fps, asset.mouth, at);
        sc.frames = Math.max(sc.frames, Math.ceil((at + result.seconds) * sc.fps) + 1);
        return { assetId: asset.id, clipId: clip.id, seconds: r3(result.seconds) };
      });
    },
  },
  "scene.backdrop": {
    help: `{sceneId, type: ${puppet.BACKDROPS.join("|")}, seed?, sky?:[top,bottom], moon?, time?: dusk|night, y? (horizon/railing height 0..1), front? (draw in front of characters, e.g. a railing)} Painted background layer. Animate parallax with char.key on it: values {camX}.`,
    run: (s, a) =>
      s.mutate("backdrop", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const front = a.bool("front", false);
        let l = front ? undefined : sc.layers.find((x) => x.kind === "backdrop" && !x.name.startsWith("Foreground"));
        if (!l) {
          l = anim.addLayer(sc, front ? "Foreground" : "Backdrop", "backdrop");
          if (!front) {
            sc.layers.splice(sc.layers.indexOf(l), 1);
            sc.layers.push(l);
          }
        }
        const type = a.str("type", "night-city") as puppet.BackdropSpec["type"];
        if (!puppet.BACKDROPS.includes(type)) throw new ops.OpError(`type must be one of ${puppet.BACKDROPS.join(", ")}`);
        l.backdrop = { type, seed: a.num("seed", 3), sky: a.any("sky") as [string, string] | undefined, moon: a.any("moon") === undefined ? undefined : a.bool("moon"), time: a.optStr("time") as "dusk" | "night" | undefined, y: a.optNum("y") };
        l.chan ??= {};
        return { layerId: l.id };
      }),
  },
  "anim.path": {
    help: "{sceneId, layerId, frame?, d (SVG path data), fill?, color?, size?, tool?: pen|ink|brush|marker} Draw clean vector art from SVG path data (curves, arcs). Closed subpaths with a fill are filled.",
    run: (s, a) =>
      s.mutate("path", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const polys = flattenPath(a.str("d"));
        const fill = a.optStr("fill");
        const ids: string[] = [];
        for (const poly of polys) {
          const pts: number[] = [];
          for (let i = 0; i < poly.length; i += 2) pts.push(poly[i]!, poly[i + 1]!, 0.8);
          const closed = !!fill && poly.length >= 6;
          const st: Omit<Stroke, "id"> = { tool: (a.optStr("tool") as Stroke["tool"]) ?? (closed ? "pen" : "ink"), color: a.str("color", "#111111"), size: a.num("size", closed ? 3 : 4), opacity: a.num("opacity", 1), pts, closed, fill };
          ids.push(anim.addStroke(sc, a.str("layerId"), Math.round(a.num("frame", 0)), st).id);
        }
        return { strokes: ids.length };
      }),
  },
  shot: {
    help:
      "{name?, seconds?=10, fps?=24, width?=1080, height?=1920, backdrop?:{type,...}|type, character?:{...char.create args}, actions?:[...char.act steps], say?:[{at,text,voice?,speed?}], camera?:{scale:[from,to], y:[from,to]}, look?: 'night'|'warm'|'clean', fade?=true, render?:{quality?,height?}|false} " +
      "One command: new project → scene → backdrop → character → animation → voices + lip sync → grade → camera → render.",
    run: async (s, a) => {
      const secs = a.num("seconds", 10);
      const fps = a.num("fps", 24);
      const W = a.num("width", 1080);
      const H = a.num("height", 1920);
      const cmds: Command[] = [];
      cmds.push({ op: "project.new", name: a.str("name", "Shot"), width: W, height: H, fps });
      cmds.push({ op: "anim.scene.new", name: a.str("name", "Shot"), width: W, height: H, fps, frames: Math.round(secs * fps), background: "#101018" });
      const bd = a.any("backdrop");
      if (bd !== undefined && bd !== false) cmds.push({ op: "scene.backdrop", sceneId: "$1.id", ...(typeof bd === "string" ? { type: bd } : (bd as object)) });
      const ch = a.any("character");
      let charIdx = -1;
      if (ch) {
        charIdx = cmds.length;
        cmds.push({ op: "char.create", sceneId: "$1.id", ...(ch as object) });
        const acts = a.any("actions");
        if (Array.isArray(acts) && acts.length) cmds.push({ op: "char.act", sceneId: "$1.id", layerId: `$${charIdx}.layerId`, actions: acts });
      }
      const clipIdx = cmds.length;
      cmds.push({ op: "anim.toTimeline", sceneId: "$1.id", start: 0, dur: secs });
      const look = a.str("look", "night");
      if (look === "night") {
        cmds.push({ op: "clip.fx", id: `$${clipIdx}.id`, values: { glow: 0.55, glowThreshold: 0.8, glowRadius: 26 } });
        cmds.push({ op: "clip.grade", id: `$${clipIdx}.id`, values: { contrast: 1.06, saturation: 1.02, vignette: 0.4, grain: 0.08 } });
        cmds.push({ op: "clip.wheels", id: `$${clipIdx}.id`, lift: "#2e2a6e", gain: "#ffc08a", strength: 0.25 });
      } else if (look === "warm") {
        cmds.push({ op: "clip.grade", id: `$${clipIdx}.id`, values: { contrast: 1.05, temperature: 0.25, vignette: 0.3, grain: 0.06 } });
      }
      const cam = a.any("camera") as { scale?: number[]; y?: number[]; x?: number[] } | undefined;
      for (const prop of ["scale", "x", "y"] as const) {
        const r = cam?.[prop];
        if (Array.isArray(r) && r.length >= 2) cmds.push({ op: "clip.keys", id: `$${clipIdx}.id`, prop, keys: [[0, r[0], "inOut"], [secs, r[1], "inOut"]] });
      }
      if (a.bool("fade", true)) cmds.push({ op: "clip.transition", id: `$${clipIdx}.id`, in: "fade", out: "dip", dur: 0.5 });
      const say = a.any("say");
      if (Array.isArray(say) && charIdx >= 0)
        for (const line of say as Record<string, unknown>[]) cmds.push({ op: "char.say", sceneId: "$1.id", layerId: `$${charIdx}.layerId`, ...line });
      cmds.push({ op: "project.save" });
      const render = a.any("render");
      if (render !== false) cmds.push({ op: "render.start", quality: "very-high", codec: "avc", name: a.str("name", "Shot"), to: secs, ...(typeof render === "object" && render ? render : {}) });
      const results = await runBatch(s, cmds, false);
      const failed = results.find((r) => !r.ok);
      if (failed) throw new ops.OpError(`${failed.op} failed: ${failed.error}`);
      return {
        project: s.project.id,
        sceneId: (results[1]?.result as { id?: string })?.id,
        characterLayerId: charIdx >= 0 ? (results[charIdx]?.result as { layerId?: string })?.layerId : undefined,
        clipId: (results[clipIdx]?.result as { id?: string })?.id,
        render: results[results.length - 1]?.op === "render.start" ? results[results.length - 1]?.result : undefined,
        steps: results.length,
      };
    },
  },

  /* ------------------------------- motion toolkit ------------------------------- */
  "media.fromScene": {
    help: "{sceneId, frame?=0, crop?:[x,y,w,h], background?=true, name?} Turn an animation frame into an image asset (stills, collage pieces, thumbnails).",
    run: async (s, a) => {
      const cr = a.any("crop") as number[] | undefined;
      const blob = await scenePng(s.project, s.pool, a.str("sceneId"), Math.round(a.num("frame", 0)), Array.isArray(cr) && cr.length === 4 ? { x: cr[0]!, y: cr[1]!, w: cr[2]!, h: cr[3]! } : undefined, a.bool("background", true));
      return await s.mutateAsync("still from scene", async (p) => {
        const asset = await importBlob(p, blob, `${a.str("name", "Still")}.png`, "agent");
        return { assetId: asset.id, width: asset.width, height: asset.height };
      });
    },
  },
  "captions.add": {
    help: `{text (use *word* for emphasis), assetId? (voice/audio/video to sync to), start?, end?, style?: ${proc.CAPTION_STYLES.join("|")}, position?: bottom|center|top, size?, color?, accent?, maxWords?, uppercase?} Word-by-word animated captions timed to the speech.`,
    run: async (s, a) => {
      const text = a.str("text");
      const asset = a.optStr("assetId") ? findAsset(s.project, a.str("assetId")) : undefined;
      if (a.optStr("assetId") && !asset) throw new ops.OpError("No such asset.");
      let start = a.optNum("start");
      if (start === undefined && asset) {
        const c = s.project.tracks.flatMap((t) => t.clips).find((x) => x.assetId === asset.id);
        start = c ? c.start : 0;
      }
      start ??= 0;
      const words0 = text.split(/\s+/).filter(Boolean).length;
      const end = a.optNum("end") ?? (asset?.duration ? start + asset.duration : start + Math.max(1.2, words0 * 0.38));
      let envelope: number[] | undefined;
      let envFps = 30;
      if (asset) {
        const e = await speechEnvelope(asset).catch(() => null);
        if (e) {
          envelope = e.env;
          envFps = e.fps;
        }
      }
      const words = proc.timeWords(text, 0, end - start, envelope, envFps);
      const spec = specFrom(a, "captions", a.str("style", "pop"));
      spec.words = words;
      return s.mutate("captions", (p) => ({ ...procClip(p, spec, start!, end - start!, { name: "Captions" }), words: words.length }));
    },
  },
  "kinetic.add": {
    help: `{text (lines split by / or newline), style?: ${proc.KINETIC_STYLES.join("|")}, start?, dur?=2.5, bg?, color?, accent?, palette?} Big kinetic typography.`,
    run: (s, a) => s.mutate("kinetic type", (p) => procClip(p, specFrom(a, "kinetic", a.str("style", "stretch")), a.num("start", s.time), a.num("dur", 2.5), { name: "Kinetic type" })),
  },
  "mograph.add": {
    help: `{type: ${proc.MOGRAPH_TYPES.join("|")}, start?, dur?=3, x?, y? (0..1), size? (0..1), palette?, bg?, value?} Motion-design element.`,
    run: (s, a) => s.mutate("motion graphic", (p) => procClip(p, specFrom(a, "mograph", a.str("type")), a.num("start", s.time), a.num("dur", 3), { name: `Mograph ${a.str("type")}` })),
  },
  "media.matte": {
    help: "{assetId, fps?=15} Find the person in a photo or video on the phone (once per asset; stored). Needed by clip.subject and fx.textBehind. Returns {frames, size}.",
    run: async (s, a) => {
      const as = findAsset(s.project, a.str("assetId"));
      if (!as) throw new ops.OpError("No such asset.");
      if (a.optStr("track")) {
        // A ready-made matte track (e.g. from a green screen or another tool): base64 of n frames of w*h alpha bytes.
        const bin = atob(a.str("track"));
        const data = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) data[i] = bin.charCodeAt(i);
        const w = a.num("w"),
          h = a.num("h"),
          n = Math.floor(data.length / (w * h));
        const m2 = await storeMatte(as.id, { w, h, fps: a.num("fps", 15), n, data: data.subarray(0, n * w * h) });
        return { frames: m2.n, size: `${m2.w}x${m2.h}`, fps: m2.fps };
      }
      if (a.optStr("mask")) {
        // A hand-made mask (white/opaque = subject) for a photo, instead of the AI.
        const mk = await decodeMask(a.str("mask"));
        const m1 = await storeMatte(as.id, { w: mk.w, h: mk.h, fps: 1, n: 1, data: new Uint8Array(mk.data) });
        return { frames: m1.n, size: `${m1.w}x${m1.h}`, fps: 1 };
      }
      const m = await computeMatte(s.pool, as, { fps: a.optNum("fps"), onProgress: (x) => s.setStatus(x) });
      s.setStatus("");
      return { frames: m.n, size: `${m.w}x${m.h}`, fps: m.fps };
    },
  },
  "clip.subject": {
    help: `{id, mode: cutout|background|fill|neon|double|halo|replace | none, texture? (asset id or ${GENERATED_TEXTURES.join("|")}), bg?: keep|dark|black|white|blur|transparent|texture|#hex, color?, color2?, amount?, edge?, drawOn? (s), detail?} Subject looks from the person matte: cut-out, texture/galaxy/fire inside the silhouette, glowing neon outline that draws on, double exposure with another picture, halo light behind, new background. Computes the matte first if needed.`,
    run: async (s, a) => {
      const c0 = ops.getClip(s.project, a.str("id"));
      const as = findAsset(s.project, c0.assetId);
      if (!as || (as.kind !== "video" && as.kind !== "image")) throw new ops.OpError("Pick a video or photo clip.");
      if (a.str("mode") !== "none" && !(await matteLoad(as.id))) {
        await computeMatte(s.pool, as, { onProgress: (x) => s.setStatus(x) });
        s.setStatus("");
      }
      return s.mutate("subject look", (p) => {
        const c = ops.getClip(p, a.str("id"));
        if (a.str("mode") === "none") {
          c.subject = undefined;
          return { mode: "none" };
        }
        const sp: SubjectSpec = { mode: a.str("mode") as SubjectSpec["mode"] };
        for (const k of ["texture", "bg", "color", "color2"] as const) if (a.optStr(k)) sp[k] = a.str(k);
        for (const k of ["amount", "edge", "drawOn", "detail", "blur"] as const) if (a.optNum(k) !== undefined) sp[k] = a.num(k);
        c.subject = sp;
        return sp;
      });
    },
  },
  "fx.textBehind": {
    help: "{id (video/photo clip with a person), text, preset?=cinematic (any text preset), font?, color?, size?=0.16 (of frame height), y?=0.42 (0 top..1 bottom), start?, dur?} Big words placed BEHIND the person: the text sits between the background and a cut-out copy of the subject. Returns {textId, cutoutId}.",
    run: async (s, a) => {
      const c0 = ops.getClip(s.project, a.str("id"));
      const as = findAsset(s.project, c0.assetId);
      if (!as || (as.kind !== "video" && as.kind !== "image")) throw new ops.OpError("Pick a video or photo clip.");
      if (!(await matteLoad(as.id))) {
        await computeMatte(s.pool, as, { onProgress: (x) => s.setStatus(x) });
        s.setStatus("");
      }
      return s.mutate("text behind subject", (p) => {
        const c = ops.getClip(p, a.str("id"));
        const start = a.num("start", c.start),
          dur = a.num("dur", c.dur);
        const tt = ops.addClip(p, { text: a.str("text"), start, dur, trackId: topTrack(p, start, dur) });
        ops.textPreset(p, tt.id, a.str("preset", "cinematic"));
        const style: Record<string, unknown> = { size: Math.round(a.num("size", 0.16) * 1920) };
        if (a.optStr("font")) style.font = a.str("font");
        if (a.optStr("color")) style.color = a.str("color");
        ops.patchClip(p, tt.id, { text: style });
        ops.setConstant(p, tt.id, "y", (a.num("y", 0.42) - 0.5) * p.height);
        const cut = ops.duplicateClip(p, c.id);
        ops.moveClip(p, cut.id, c.start, topTrack(p, c.start, c.dur));
        cut.subject = { mode: "cutout", bg: "transparent" };
        cut.name = `${c.name} (cut-out)`;
        return { textId: tt.id, cutoutId: cut.id };
      });
    },
  },
  "shot.depth": {
    help: "{assetId (photo/art), seconds?=8, move?: push|drift|orbit|rack|rise|still, focus? (0 far..1 near), aperture?=1 (depth blur), parallax?=1, wind? (0..2 sway of near things: grass, cloth, hair), fog?, embers?, rays?:{x,y}, light? (0..1 light breathing), look?, pins? (living body pins for breathing/blinks), depth? (PNG data URL to skip measuring), start?=0, name?} 2.5D cinematic shot from one still: on-phone AI measures depth, then the camera moves through the picture with parallax, depth of field, wind and atmosphere. Returns {id, sceneId}.",
    run: async (s, a) => {
      const as = findAsset(s.project, a.str("assetId"));
      if (!as || as.kind !== "image") throw new ops.OpError("Pick an imported photo or artwork.");
      let map = a.optStr("depth");
      if (!map) {
        const bmp = (await s.pool.image(as)) as ImageBitmap;
        const d = await estimateDepth(bmp, bmp.width, bmp.height, (m) => s.setStatus(m));
        s.setStatus("");
        map = await depthToPng(d);
      }
      const secs = a.num("seconds", 8);
      const move = a.str("move", "push");
      const f0 = a.optNum("focus") ?? (await living.autoFocus(map));
      const cams: Record<string, living.DepthCamKey[]> = {
        push: [{ t: 0, dolly: 0, zoom: 1.04, focus: f0 }, { t: secs, dolly: 0.1, zoom: 1.07, focus: f0 }],
        drift: [{ t: 0, x: -0.035, zoom: 1.06, focus: f0 }, { t: secs, x: 0.035, zoom: 1.06, focus: f0 }],
        orbit: [{ t: 0, x: -0.045, y: 0.01, dolly: 0.02, zoom: 1.07, focus: f0 }, { t: secs, x: 0.045, y: -0.01, dolly: 0.08, zoom: 1.07, focus: f0 }],
        rack: [{ t: 0, dolly: 0, zoom: 1.05, focus: 0.92 }, { t: secs * 0.35, dolly: 0.02, zoom: 1.05, focus: 0.92 }, { t: secs * 0.7, dolly: 0.05, zoom: 1.06, focus: 0.2 }, { t: secs, dolly: 0.06, zoom: 1.06, focus: 0.2 }],
        rise: [{ t: 0, y: 0.035, dolly: 0, zoom: 1.07, focus: f0 }, { t: secs, y: -0.035, dolly: 0.07, zoom: 1.07, focus: f0 }],
        still: [{ t: 0, zoom: 1.04, focus: f0 }],
      };
      const P = s.project;
      return s.mutate("2.5D shot", (p) => {
        const sc = newScene(a.str("name", "Depth shot"), p.width, p.height, p.fps);
        sc.frames = Math.max(1, Math.round(secs * p.fps));
        sc.background = "#000000";
        sc.layers = [];
        const l = anim.addLayer(sc, "Picture", "living");
        l.living = {
          assetId: as.id,
          pins: a.any("pins") as living.LivingPins | undefined,
          motion: { ...living.DEFAULT_MOTION, ...((a.any("motion") as Partial<living.LivingMotion> | undefined) ?? {}) },
          fit: "cover",
          depth: { map: map!, cam: cams[move] ?? cams.push!, aperture: a.num("aperture", 1), parallax: a.num("parallax", 1), wind: { amount: a.num("wind", 0), speed: 1 }, light: a.num("light", 0.4) },
        };
        const els: motion.MotionEl[] = [];
        if (a.bool("fog", false)) els.push({ k: "fog", t0: 0, n: 4, y: 0.8 });
        const rays = a.any("rays") as { x: number; y: number } | undefined;
        if (rays) els.push({ k: "rays", t0: 0, x: rays.x, y: rays.y, n: 14, color: "#fff1d0" });
        if (a.bool("embers", false)) els.push({ k: "embers", t0: 0, n: 50 });
        if (els.length) {
          const o = anim.addLayer(sc, "Atmosphere", "proc");
          o.proc = { type: "motion", style: "atmosphere", motion: { theme: "eclipse", els, bg: false } };
        }
        p.scenes.push(sc);
        const clip = ops.addClip(p, { sceneId: sc.id, start: a.num("start", 0), dur: secs });
        if (a.optStr("look")) mv.applyLook(ops.getClip(p, clip.id), a.str("look"), a.num("lookStrength", 0.8));
        return { id: clip.id, sceneId: sc.id, size: `${P.width}x${P.height}` };
      });
    },
  },
  "motion.promo": {
    help: "{lines:[{text, sub?}], style?: warm|tech|cinematic|editorial, photos?: [assetId | {id, cut}] (editorial: your photos as printed cut-outs), theme?: ember|cream|mono|eclipse|aurora|gold, beat?=2.4 (s per line), logo? (closing wordmark), start?=0, music?=true} A whole premium motion-design promo (glowing dots, sparkles, glow pills, frames, drawn paths, dot rows, particle rings, eclipse, brush titles) from a few lines, with camera moves, grain and an original score. Returns {id, seconds}.",
    run: async (s, a) => {
      const lines = (a.any("lines") as motion.PromoLine[] | undefined) ?? [];
      if (!lines.length) throw new ops.OpError("Give lines: [{text, sub?}].");
      const photos = (a.any("photos") as (string | { id: string; cut?: boolean })[] | undefined)?.map((x) => (typeof x === "string" ? { id: x, cut: true } : x));
      const r = motion.buildPromo(lines, { theme: a.optStr("theme"), beat: a.optNum("beat"), logo: a.optStr("logo"), style: a.optStr("style") as "warm" | "tech" | "cinematic" | "editorial" | undefined, photos });
      // Cut-outs need each photo's person matte.
      for (const ph of photos ?? []) {
        const as = findAsset(s.project, ph.id);
        if (ph.cut !== false && as && !(await matteLoad(as.id))) await computeMatte(s.pool, as, { onProgress: (x) => s.setStatus(x) }).catch(() => {});
      }
      const start = a.num("start", 0);
      const clip = s.mutate("motion promo", (p) => procClip(p, { type: "motion", style: r.spec.theme, motion: r.spec }, start, r.seconds, { name: "Motion promo", bottom: true }));
      if (a.bool("music", true)) {
        const mood = a.optStr("style") === "editorial" ? "calm" : a.optStr("style") === "cinematic" ? "dark" : a.optStr("style") === "tech" ? "tense" : "hopeful";
        const blob = await compose({ mood: mood as Mood, seconds: r.seconds + 0.5, bpm: Math.round(60 / (r.spec.els.length ? (a.num("beat", 2.4) / 4) : 0.6)) });
        await s.mutateAsync("promo music", async (p) => {
          const asset = await importBlob(p, blob, "Promo score.wav", "agent");
          const mc = ops.addClip(p, { assetId: asset.id, start });
          ops.patchClip(p, mc.id, { volume: 0.75, fadeOut: 1.2 });
        });
      }
      return { ...clip, seconds: r.seconds, elements: r.spec.els.length };
    },
  },
  "motion.add": {
    help: `{spec:{theme, els:[{k: ${["dot", "spark", "pill", "frame", "path", "dots", "ring", "stars", "title", "logo", "line", "eclipse", "fog", "embers", "rays", "brush", "flash", "ink", "staff", "note", "serif", "shape", "star4"].join("|")}, t0, t1?, x?, y?, s?, text?, sub?, pts?, n?, color?, icon?, variant?, font?, rot?}], cam?:[{t,z,x,y,r}], bg?: false to overlay footage, grain?}, start?, dur} Hand-built motion-design sequence.`,
    run: (s, a) => s.mutate("motion design", (p) => procClip(p, { type: "motion", style: "custom", motion: a.obj("spec") as unknown as motion.MotionSpec }, a.num("start", s.time), a.num("dur"), { name: "Motion", bottom: (a.obj("spec") as { bg?: boolean }).bg !== false })),
  },
  "anim.living": {
    help: "{sceneId, assetId (photo/drawing), pins?:{head,neck,chest,hips,feet,eyeL?,eyeR?,hairTop?,headR?,cloth?:[[x,y],...]} (fractions of the image; guessed from the figure when missing), motion?:{breathe,sway,head,hair,cloth (0..2), blink (per 10 s), speed, seed}, fit?: contain|cover} Bring a still picture to life: the picture itself bends on a fine mesh (breathing, head, hair and cloth motion, blinks, idle sway) so proportions and line work stay exactly as drawn. Returns {layerId, pins}.",
    run: async (s, a) => {
      const as = findAsset(s.project, a.str("assetId"));
      if (!as || as.kind !== "image") throw new ops.OpError("Pick an imported photo or drawing.");
      let pins = a.any("pins") as living.LivingPins | undefined;
      if (!pins || !pins.head) {
        const bmp = (await s.pool.image(as)) as ImageBitmap;
        const k = Math.min(1, 400 / Math.max(bmp.width, bmp.height));
        const c = new OffscreenCanvas(Math.round(bmp.width * k), Math.round(bmp.height * k));
        const x = c.getContext("2d")!;
        x.drawImage(bmp, 0, 0, c.width, c.height);
        pins = { ...living.guessPins(living.subjectBox(x.getImageData(0, 0, c.width, c.height).data, c.width, c.height)), ...(pins ?? {}) };
      }
      const motion = { ...living.DEFAULT_MOTION, ...((a.any("motion") as Partial<living.LivingMotion> | undefined) ?? {}) };
      return s.mutate("living picture", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = anim.addLayer(sc, a.str("name", "Living picture"), "living");
        sc.layers.splice(sc.layers.indexOf(l), 1);
        let i = sc.layers.length;
        while (i > 0 && sc.layers[i - 1]!.kind === "backdrop") i--;
        sc.layers.splice(i, 0, l);
        l.living = { assetId: as.id, pins: pins!, motion, fit: (a.optStr("fit") as "contain" | "cover" | undefined) ?? "contain" };
        return { layerId: l.id, pins };
      });
    },
  },
  "three.new": {
    help: "{sceneId, style?: toon|realistic|clay|neon, sky?:[top,bottom]|null, ground?:{color,size,grid?}|null, fog?:{color,near,far}, camera?:{fov,pos:[x,y,z],target:[x,y,z]}, order?: bottom|top} Add a 3D layer to an animation scene (metres, y up; a character is ~1.75 m). Returns {layerId}.",
    run: (s, a) =>
      s.mutate("3D layer", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = anim.addLayer(sc, a.str("name", "3D"), "three");
        if (a.str("order", "bottom") === "bottom") {
          sc.layers.splice(sc.layers.indexOf(l), 1);
          // Just above any painted backdrop at the bottom.
          let i = sc.layers.length;
          while (i > 0 && sc.layers[i - 1]!.kind === "backdrop") i--;
          sc.layers.splice(i, 0, l);
        }
        const sp = t3.newScene3D();
        const st = a.optStr("style");
        if (st) sp.style = st as t3.Scene3D["style"];
        if (a.any("sky") !== undefined) sp.env.sky = a.any("sky") as [string, string] | null;
        if (a.any("ground") !== undefined) sp.env.ground = a.any("ground") as t3.Scene3D["env"]["ground"];
        if (a.any("fog") !== undefined) sp.env.fog = a.any("fog") as t3.Scene3D["env"]["fog"];
        const cam = a.any("camera") as Partial<t3.Scene3D["camera"]> | undefined;
        if (cam) sp.camera = { ...sp.camera, ...cam };
        if (sp.style === "neon") {
          sp.env.sky = sp.env.sky ?? ["#05010f", "#1a0638"];
          sp.lights = [{ id: "amb", type: "ambient", color: "#4a2a80", intensity: 0.6 }, { id: "key", type: "point", color: "#ff2bd6", intensity: 3, pos: [3, 3, 3] }, { id: "rim", type: "point", color: "#24c8ff", intensity: 3, pos: [-3, 2, -2] }];
        }
        l.three = sp;
        return { layerId: l.id };
      }),
  },
  "three.add": {
    help: `{sceneId, layerId, type: ${t3.OBJ3D_TYPES.join("|")}, id?, color?, material?: toon|standard|metal|glass|emissive|clay|flat, size?:[..], pos?:[x,y,z], rot?:[deg x,y,z], scale?, text?, font?, assetId? (glb model), clip? (model animation), char?:{skin,hair,top,jacket,pants,shoes,eyes,hairStyle:spiky|short|long|bald}, outline?, emissive?, opacity?, parent?} Add a 3D object. Returns {id}.`,
    run: (s, a) =>
      s.mutate("3D object", (p) => {
        const l = threeLayer(p, a);
        const type = a.str("type") as t3.Obj3DType;
        if (!t3.OBJ3D_TYPES.includes(type)) throw new ops.OpError(`type must be one of ${t3.OBJ3D_TYPES.join(", ")}`);
        const id = a.str("id", `${type}${l.three!.objects.length + 1}`);
        if (l.three!.objects.some((o) => o.id === id)) throw new ops.OpError(`3D object id "${id}" already exists`);
        const o: t3.Obj3D = {
          id,
          type,
          color: a.str("color", type === "character" ? "#ffffff" : "#e8e4f0"),
          material: (a.optStr("material") as t3.Material3D | undefined) ?? "toon",
          pos: (a.any("pos") as [number, number, number] | undefined) ?? [0, type === "character" || type === "terrain" || type === "model" || type === "particles" ? 0 : 0.5, 0],
          rot: (a.any("rot") as [number, number, number] | undefined) ?? [0, 0, 0],
          scale: a.num("scale", 1),
        };
        for (const k of ["size", "text", "font", "assetId", "clip", "char", "outline", "emissive", "opacity", "parent", "name", "castShadow"] as const) if (a.any(k) !== undefined) (o as unknown as Record<string, unknown>)[k] = a.any(k);
        if (type === "model") {
          const as = findAsset(p, a.str("assetId"));
          if (!as || as.kind !== "model") throw new ops.OpError("assetId must be an imported .glb/.gltf model.");
          o.ref = as.ref;
        }
        if (type === "character") o.char = { ...t3.DEFAULT_CHAR3D, ...(o.char ?? {}) };
        l.three!.objects.push(o);
        return { id };
      }),
  },
  "three.set": {
    help: "{sceneId, layerId, id (object id | camera | env | style | light id), patch} Change a 3D object, the camera {fov,pos,target}, env {sky,ground,fog}, a light {color,intensity,pos} or style (patch: 'toon').",
    run: (s, a) =>
      s.mutate("3D edit", (p) => {
        const sp = threeLayer(p, a).three!;
        const id = a.str("id");
        const patch = a.any("patch") as Record<string, unknown>;
        if (id === "camera") Object.assign(sp.camera, patch);
        else if (id === "env") Object.assign(sp.env, patch);
        else if (id === "style") sp.style = String(patch) as t3.Scene3D["style"];
        else {
          const o = sp.objects.find((x) => x.id === id) ?? sp.lights.find((x) => x.id === id);
          if (!o) throw new ops.OpError(`No 3D object or light "${id}"`);
          Object.assign(o, patch);
        }
        return true;
      }),
  },
  "three.light": {
    help: "{sceneId, layerId, id?, type: ambient|hemi|key|rim|point|spot, color?, intensity?, pos?:[x,y,z], replace?=false} Add a light (replace:true clears the default rig first).",
    run: (s, a) =>
      s.mutate("3D light", (p) => {
        const sp = threeLayer(p, a).three!;
        if (a.bool("replace")) sp.lights = [];
        const id = a.str("id", `light${sp.lights.length + 1}`);
        sp.lights = sp.lights.filter((x) => x.id !== id);
        sp.lights.push({ id, type: a.str("type", "point") as t3.Light3D["type"], color: a.str("color", "#ffffff"), intensity: a.num("intensity", 1.5), pos: (a.any("pos") as [number, number, number] | undefined) ?? [2, 3, 2] });
        return { id };
      }),
  },
  "three.act": {
    help: `{sceneId, layerId, id, actions:[{at (s), do: ${Object.keys(t3.POSES3D).join("|")}|walk{dist}|run{dist}|jump{height}|turn{to}|spin{turns}|moveTo{x,y,z}|punch{side}|kick|wave|nod|expr{name:${t3.EXPR3D.join("|")}}|key{prop,value}, dur?}]} Animate a 3D character or object.`,
    run: (s, a) =>
      s.mutate("3D animation", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = threeLayer(p, a);
        const end = t3.act3D(l.three!, a.str("id"), sc.fps, a.any("actions") as t3.Step3D[]);
        if (end * sc.fps > sc.frames) sc.frames = Math.ceil(end * sc.fps) + 1;
        return { end };
      }),
  },
  "three.camera": {
    help: "{sceneId, layerId, pos?, target?, fov?, moves?:[{move: orbit|dolly|crane|truck|push|shake, start, dur, amount?, ease?}], keys?:[{at, pos?, target?, fov?, ease?}]} Set or animate the 3D camera.",
    run: (s, a) =>
      s.mutate("3D camera", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const sp = threeLayer(p, a).three!;
        if (a.any("pos")) sp.camera.pos = a.any("pos") as [number, number, number];
        if (a.any("target")) sp.camera.target = a.any("target") as [number, number, number];
        if (a.optNum("fov") !== undefined) sp.camera.fov = a.num("fov");
        for (const k of (a.any("keys") as { at: number; pos?: number[]; target?: number[]; fov?: number; ease?: Ease }[] | undefined) ?? []) {
          const fr = Math.round(k.at * sc.fps);
          k.pos?.forEach((v, i) => t3.setKey(sp, `cam.p${"xyz"[i]}`, fr, v, k.ease ?? "inOut"));
          k.target?.forEach((v, i) => t3.setKey(sp, `cam.t${"xyz"[i]}`, fr, v, k.ease ?? "inOut"));
          if (k.fov !== undefined) t3.setKey(sp, "cam.fov", fr, k.fov, k.ease ?? "inOut");
        }
        for (const m of (a.any("moves") as { move: string; start: number; dur: number; amount?: number; ease?: Ease }[] | undefined) ?? []) t3.camMove(sp, sc.fps, m);
        return true;
      }),
  },
  "three.key": {
    help: "{sceneId, layerId, prop ('<id>.px|py|pz|rx|ry|rz|s|opacity|clipTime', '<charId>.j.<joint>.x|y|z', 'cam.px|..|fov|roll', '<lightId>.i'), keys:[[seconds, value, ease?], ...]} Raw 3D keyframes.",
    run: (s, a) =>
      s.mutate("3D keys", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const sp = threeLayer(p, a).three!;
        for (const [t, v, e] of a.any("keys") as [number, number, Ease?][]) t3.setKey(sp, a.str("prop"), Math.round(t * sc.fps), v, e ?? "inOut");
        return true;
      }),
  },
  "three.exportGlb": {
    help: "{sceneId, layerId, id?, name?} Save a 3D object (or the whole 3D layer) as a .glb model into Documents/VideoStudio (and as a project asset).",
    run: async (s, a) => {
      const l = threeLayer(s.project, a);
      const blob = await t3.exportGlb(l, a.optStr("id"));
      const name = `${a.str("name", a.str("id", "scene"))}.glb`;
      const saved = await saveBlob(blob, name, "Documents");
      const asset = await s.mutateAsync("save 3D asset", async (p) => importBlob(p, blob, name, "agent"));
      return { ...saved, assetId: asset.id, bytes: blob.size };
    },
  },
  "system.caps": {
    help: "What this phone can do for on-device AI: WebGPU (GPU compute), its limits and features, memory, cores, browser engine version.",
    run: async () => {
      const nav = navigator as Navigator & { gpu?: { requestAdapter(o?: unknown): Promise<unknown> }; deviceMemory?: number };
      const out: Record<string, unknown> = { ua: navigator.userAgent, cores: navigator.hardwareConcurrency, deviceMemory: nav.deviceMemory, crossOriginIsolated: self.crossOriginIsolated, webgpu: !!nav.gpu };
      if (nav.gpu) {
        try {
          const ad = (await nav.gpu.requestAdapter({ powerPreference: "high-performance" })) as { features: Set<string>; limits: Record<string, number>; info?: Record<string, string>; requestAdapterInfo?: () => Promise<Record<string, string>> } | null;
          if (!ad) out.adapter = null;
          else {
            const info = ad.info ?? (ad.requestAdapterInfo ? await ad.requestAdapterInfo() : {});
            out.adapter = { vendor: info.vendor, architecture: info.architecture, description: info.description, features: [...ad.features], maxBufferSize: ad.limits.maxBufferSize, maxStorageBufferBindingSize: ad.limits.maxStorageBufferBindingSize, maxComputeWorkgroupStorageSize: ad.limits.maxComputeWorkgroupStorageSize };
          }
        } catch (e) {
          out.adapterError = String(e);
        }
      }
      try {
        const est = await navigator.storage?.estimate?.();
        out.storage = est ? { quotaMB: Math.round((est.quota ?? 0) / 1048576), usedMB: Math.round((est.usage ?? 0) / 1048576) } : undefined;
      } catch {
        /* ignore */
      }
      return out;
    },
  },
  "fonts.list": { help: "Every bundled font (use the name in text styles, captions, kinetic type and 3D text).", run: () => FONTS.map((f) => `${f.name} (${f.category})`) },
  "fx.add": {
    help: `{type: ${proc.FX_TYPES.join("|")}, start?, dur?=2, x?, y? (0..1), size?, color? (ink), accent? (fill)} Hand-drawn effect animated on twos.`,
    run: (s, a) => s.mutate("hand-drawn fx", (p) => procClip(p, specFrom(a, "fx", a.str("type")), a.num("start", s.time), a.num("dur", 2), { name: `FX ${a.str("type")}` })),
  },
  "overlay.add": {
    help: `{type: ${proc.OVERLAY_TYPES.join("|")}, start?, dur?=2, opacity?, blend?, color?, y?} Film overlays: light leak, film burn, dust & scratches, VHS, red stripe wipe, paper texture.`,
    run: (s, a) => {
      const type = a.str("type");
      const blend = (a.optStr("blend") as BlendMode | undefined) ?? (type === "lightLeak" || type === "filmBurn" ? "screen" : type === "paper" ? "multiply" : "normal");
      return s.mutate("overlay", (p) => procClip(p, specFrom(a, "overlay", type), a.num("start", s.time), a.num("dur", 2), { name: `Overlay ${type}`, blend, opacity: a.optNum("opacity") }));
    },
  },
  "transition.overlay": {
    help: "{at (cut time), type?: stripe|filmBurn|lightLeak, dur?=0.9, color?} A transition overlay centred on a cut.",
    run: (s, a) => {
      const type = a.str("type", "stripe");
      const dur = a.num("dur", 0.9);
      const blend: BlendMode = type === "stripe" ? "normal" : "screen";
      return s.mutate("transition overlay", (p) => procClip(p, specFrom(a, "overlay", type), Math.max(0, a.num("at") - dur / 2), dur, { name: `Transition ${type}`, blend }));
    },
  },
  "collage.add": {
    help: "{assetId (image), start?, dur?=3, x?, y? (0..1), w? (0..1 of width), rot? (deg), enter?: drop|slide|pop|none, boil? (stop-motion fps, default 8)} Paper-cut photo: torn edges, white border, shadow, stop-motion jitter.",
    run: (s, a) => {
      if (findAsset(s.project, a.str("assetId"))?.kind !== "image") throw new ops.OpError("collage.add needs an image asset.");
      return s.mutate("collage photo", (p) => procClip(p, specFrom(a, "cutout", "photo"), a.num("start", s.time), a.num("dur", 3), { name: "Cutout" }));
    },
  },
  "collage.label": {
    help: "{text, start?, dur?=2, x?, y?, size?, bg?, color?, rot?, enter?} Torn-paper label.",
    run: (s, a) => s.mutate("label", (p) => procClip(p, specFrom(a, "label", "torn"), a.num("start", s.time), a.num("dur", 2), { name: `Label ${a.str("text").slice(0, 16)}` })),
  },
  "collage.paper": {
    help: `{tone?: ${proc.PAPER_TONES.join("|")}, start?, dur?, trackId?} Paper / newspaper background (goes on the lowest free track).`,
    run: (s, a) => s.mutate("paper", (p) => procClip(p, specFrom(a, "paper", a.str("tone", "cream")), a.num("start", 0), a.num("dur", Math.max(3, projectDuration(p))), { name: "Paper", bottom: true, trackId: a.optStr("trackId") })),
  },
  "clip.punch": {
    help: "{id, at: seconds | [seconds...], amount?=1.18, dur?=0.35} Punch-in zooms (snap in, ease back).",
    run: (s, a) =>
      s.mutate("punch zoom", (p) => {
        const c = ops.getClip(p, a.str("id"));
        const at = a.any("at");
        const times = (Array.isArray(at) ? at : [at]).map(Number).filter((x) => Number.isFinite(x));
        const amt = a.num("amount", 1.18);
        const d = a.num("dur", 0.35);
        const base = typeof c.transform.scale === "number" ? c.transform.scale : 1;
        for (const t of times) {
          if (t < c.start || t > c.start + c.dur) continue;
          ops.keyframe(p, c.id, "scale", Math.max(c.start, t - 0.001), base, "hold", 1);
          ops.keyframe(p, c.id, "scale", t, base * amt, "out", 1);
          ops.keyframe(p, c.id, "scale", Math.min(c.start + c.dur, t + d), base, "inOut", 1);
        }
        return { punches: times.length };
      }),
  },
  "edit.autoPunch": {
    help: "{id, musicId? (audio asset), every?=4 (beats), amount?=1.15} Punch zooms on the music's beats.",
    run: async (s, a) => {
      const musicId = a.optStr("musicId") ?? s.project.assets.find((x) => x.kind === "audio" && x.createdBy !== "voice")?.id;
      if (!musicId) throw new ops.OpError("No music to follow: pass musicId.");
      const info = await analyseBeats(s, musicId);
      const mClip = s.project.tracks.flatMap((t) => t.clips).find((x) => x.assetId === musicId);
      const off = mClip ? mClip.start - mClip.in : 0;
      const c = ops.getClip(s.project, a.str("id"));
      const every = Math.max(1, Math.round(a.num("every", 4)));
      const times = info.beats.filter((_, i) => i % every === 0).map((b) => b + off).filter((t) => t > c.start + 0.1 && t < c.start + c.dur - 0.2);
      return await runCommand(s, { op: "clip.punch", id: c.id, at: times, amount: a.num("amount", 1.15) });
    },
  },
  "anim.drawOn": {
    help: "{sceneId, layerId? (default every drawing layer), start? (s)=0, dur? (s)=2, sketch?=false} Line art draws itself on; sketch roughs in construction lines first.",
    run: (s, a) =>
      s.mutate("draw on", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const start = Math.round(a.num("start", 0) * sc.fps);
        const dur = Math.max(1, Math.round(a.num("dur", 2) * sc.fps));
        const ls = a.optStr("layerId") ? [anim.getLayer(sc, a.str("layerId"))] : sc.layers.filter((l) => l.kind === "draw");
        for (const l of ls) l.drawOn = { start, dur, sketch: a.bool("sketch") };
        sc.frames = Math.max(sc.frames, start + dur + 1);
        return { layers: ls.length };
      }),
  },
  "fx.window": {
    help: "{id, keys:[{t (s from clip start), x0,y0,x1,y1 (frame fractions)}] | clear:true} Animated frame-space window: the clip only shows inside the moving rectangle (split screens, strips, reveals). Works on full-frame clips.",
    run: (s, a) =>
      s.mutate("window", (p) => {
        const c = ops.getClip(p, a.str("id"));
        if (a.bool("clear", false)) {
          c.win = undefined;
          return { keys: 0 };
        }
        c.win = (a.any("keys") as NonNullable<Clip["win"]>).map((k) => ({ t: Number(k.t), x0: Number(k.x0), y0: Number(k.y0), x1: Number(k.x1), y1: Number(k.y1) }));
        return { keys: c.win.length };
      }),
  },
  "fx.bandReveal": {
    help: "{id, band?=0.14 (height of the letterbox strip, of the frame), y?=0.5, open?=0.5 (s to open the strip), hold?=1.6 (s as a strip), grow?=0.9 (s to full frame), title?, sub?, color?=#e32b2b, font?=Bebas Neue} Cinematic reveal: the shot appears through a thin letterbox band (title decoding on it), then the band opens to full frame. Returns {titleId?}.",
    run: (s, a) =>
      s.mutate("band reveal", (p) => {
        const c = ops.getClip(p, a.str("id"));
        const b = a.num("band", 0.14) / 2,
          y = a.num("y", 0.5),
          o = a.num("open", 0.5),
          hold = a.num("hold", 1.6),
          g = a.num("grow", 0.9);
        c.win = [
          { t: 0, x0: 0.5, y0: y - 0.002, x1: 0.5, y1: y + 0.002 },
          { t: o * 0.5, x0: 0, y0: y - 0.002, x1: 1, y1: y + 0.002 },
          { t: o, x0: 0, y0: y - b, x1: 1, y1: y + b },
          { t: o + hold, x0: 0, y0: y - b, x1: 1, y1: y + b },
          { t: o + hold + g, x0: 0, y0: 0, x1: 1, y1: 1 },
        ];
        let titleId: string | undefined;
        if (a.optStr("title")) {
          const tt = ops.addClip(p, { text: a.str("title"), start: c.start + o * 0.6, dur: hold + g + 1.2, trackId: topTrack(p, c.start + o * 0.6, hold + g + 1.2) });
          ops.patchClip(p, tt.id, { text: { font: `"${a.str("font", "Bebas Neue")}", Impact, sans-serif`, size: 190, weight: 400, color: a.str("color", "#e32b2b"), stroke: "", strokeWidth: 0, shadow: 10, animate: "scramble", animateDur: 1.1, tracking: 6, bg: "" } });
          ops.setConstant(p, tt.id, "y", (y - 0.5) * p.height);
          titleId = tt.id;
          if (a.optStr("sub")) {
            const st = ops.addClip(p, { text: a.str("sub"), start: c.start + o * 0.8, dur: hold + g + 1, trackId: topTrack(p, c.start + o * 0.8, hold + g + 1) });
            ops.patchClip(p, st.id, { text: { font: "Inter, sans-serif", size: 40, weight: 400, color: "#f2f2f2", stroke: "", strokeWidth: 0, shadow: 6, animate: "track", animateDur: 1.2, tracking: 14, bg: "" } });
            ops.setConstant(p, st.id, "y", (y - 0.5) * p.height - 130);
          }
        }
        return { titleId };
      }),
  },
  "camera.auto": {
    help: `{id | ids, musicId? (song to cut to; default: the project's music or the clip's own sound), cuts?: [s...] (own cut times), every?=2 (beats per shot), pattern?: ${Object.keys(cr.SHOT_PATTERNS).join("|")} | [${Object.keys(cr.SHOTS).join("|")}...], start?, end?, accent?=1 (cut zoom-blur/RGB kick), follow?=true (frame on the face from the person matte)} Virtual multicam from one take: wide, close-ups, rule-of-thirds and tilted shots cut on the beat, framed on the performer, never showing black edges. Cut-out copies of the clip follow automatically.`,
    run: async (s, a) => {
      const ids0 = (a.any("ids") as string[] | undefined) ?? [a.str("id")];
      const c0 = ops.getClip(s.project, ids0[0]!);
      const start = a.num("start", c0.start),
        end = a.num("end", c0.start + c0.dur);
      let cuts = a.any("cuts") as number[] | undefined;
      let hits: number[] = [];
      if (!cuts) {
        const musicId =
          a.optStr("musicId") ??
          s.project.tracks.filter((t) => t.kind === "audio").flatMap((t) => t.clips).find((c) => c.assetId && c.start < end && c.start + c.dur > start)?.assetId ??
          c0.assetId;
        if (!musicId) throw new ops.OpError("Give cuts:[...] or a musicId to cut to.");
        const mclip = s.project.tracks.flatMap((t) => t.clips).find((c) => c.assetId === musicId);
        const off = mclip ? mclip.start - mclip.in : 0;
        const info = await analyseBeats(s, musicId);
        const every = Math.max(1, Math.round(a.num("every", 2)));
        const beats = info.beats.map((b) => b + off).filter((b) => b > start && b < end);
        cuts = beats.filter((_, i) => i % every === 0);
        hits = info.onsets.map((b) => b + off);
      }
      // Face positions from the person matte (sampled per shot).
      const as = findAsset(s.project, c0.assetId);
      const m = a.bool("follow", true) && as && (as.kind === "video" || as.kind === "image") ? await matteLoad(as.id) : null;
      const head = m
        ? (t: number) => {
            const src = c0.in + (t - c0.start) * c0.speed;
            const hp = cr.headPoint(matteAt(m, src), m.w, m.h);
            return hp.found ? hp : null;
          }
        : undefined;
      const pat = a.any("pattern");
      const pattern = Array.isArray(pat) ? (pat as cr.ShotKind[]) : cr.SHOT_PATTERNS[(pat as string | undefined) ?? "performance"];
      return s.mutate("auto camera", (p) => {
        // Every copy of this footage over the same time (cut-outs for text-behind, echoes) moves with the camera.
        const ids = new Set(ids0);
        for (const t of p.tracks)
          for (const c of t.clips) if (c.assetId && c.assetId === c0.assetId && c.subject?.mode === "cutout" && c.start < end && c.start + c.dur > start) ids.add(c.id);
        const shots = cr.planShots({ W: p.width, H: p.height, cuts: cuts!, start, end, pattern, head });
        cr.applyShots(p, [...ids], shots, { accent: a.num("accent", 1), hits });
        return { shots: shots.map((x) => ({ t: r3(x.t0), kind: x.kind })), clips: [...ids], followedFace: !!head };
      });
    },
  },
  "text.motion": {
    help: `{id, in?: ${cr.TEXT_IN.join("|")}, out?: ${cr.TEXT_OUT.join("|")}, inDur?, outDur?} Entrance and exit transitions for a title.`,
    run: (s, a) => s.mutate("text motion", (p) => (cr.textMotion(p, a.str("id"), { in: a.optStr("in") as cr.TextIn, out: a.optStr("out") as cr.TextOut, inDur: a.optNum("inDur"), outDur: a.optNum("outDur") }), true)),
  },
  "lyrics.write": {
    help: `{lines:[{start, end?, text}], style?: ${Object.keys(cr.LYRIC_STYLES).join("|")}, y?=0.12 (top line, 0..1), keep?=2 (lines on the page), behind? (subject clip id: write behind the person), underline?, color?, size?} Handwritten lyrics: each line is inked as it is sung; older lines float up and dissolve as new ones arrive.`,
    run: async (s, a) => {
      const behind = a.optStr("behind");
      if (behind) {
        const c = ops.getClip(s.project, behind);
        const as = findAsset(s.project, c.assetId);
        if (as && !(await matteLoad(as.id))) {
          await computeMatte(s.pool, as, { onProgress: (x) => s.setStatus(x) });
          s.setStatus("");
        }
      }
      // Readable placement: behind a person, pick the height where the person covers the words least
      // (checked at every line with the clip's current camera framing — run camera.auto first).
      let yAuto: number | undefined;
      if (behind && a.optNum("y") === undefined) {
        const c = ops.getClip(s.project, behind);
        const as = findAsset(s.project, c.assetId);
        const m = as ? await matteLoad(as.id) : null;
        if (m) {
          const lines = a.any("lines") as cr.LyricIn[];
          const keep = a.num("keep", 2);
          const st = cr.LYRIC_STYLES[a.str("style", "handwritten")] ?? cr.LYRIC_STYLES.handwritten!;
          const gap = ((a.optNum("size") ?? st.size) * 1.25) / 1920;
          let best = Infinity;
          for (const y of [0.07, 0.12, 0.18, 0.56, 0.64, 0.72]) {
            let cov = 0;
            for (const ln of lines)
              for (const dt of [0.3, 1.2]) {
                const t = ln.start + dt;
                const local = t - c.start;
                const f = { s: valueAt(c.transform.scale, local, 1), x: valueAt(c.transform.x, local, 0), y: valueAt(c.transform.y, local, 0) };
                cov += cr.bandCoverage(matteAt(m, c.in + local * c.speed), m.w, m.h, f, s.project.width, s.project.height, { x0: 0.18, x1: 0.82, y0: y - gap * 0.4, y1: y + gap * (keep - 0.6) });
              }
            // Small preference for the top of the frame (classic lyric position).
            cov += y > 0.5 ? 0.04 : 0;
            if (cov < best) (best = cov), (yAuto = y);
          }
        }
      }
      return s.mutate("handwritten lyrics", (p) => {
        const r = cr.lyricsWrite(p, { lines: a.any("lines") as cr.LyricIn[], style: a.optStr("style"), y: a.optNum("y") ?? yAuto, keep: a.optNum("keep"), behind, underline: a.bool("underline"), color: a.optStr("color"), size: a.optNum("size") });
        let inkId: string | undefined;
        if (r.underline) {
          const t0 = Math.min(...r.underline.map((e) => e.t0));
          const t1 = Math.max(...r.underline.map((e) => e.t1 ?? e.t0 + 2));
          const els = r.underline.map((e) => ({ ...e, t0: e.t0 - t0, t1: (e.t1 ?? e.t0 + 2) - t0 }));
          const cut = behind ? cr.cutoutFor(p, behind) : null;
          const cutTrack = cut ? p.tracks.find((t) => t.clips.includes(cut))!.id : undefined;
          inkId = procClip(p, { type: "motion", style: "custom", motion: { theme: "noir", bg: false, els } as unknown as motion.MotionSpec }, t0, t1 - t0, { name: "Lyric ink", trackId: cutTrack ? cr.trackBelow(p, cutTrack, t0, t1 - t0) : undefined }).id;
        }
        return { ids: r.ids, inkId };
      });
    },
  },
  "doodle.add": {
    help: `{type: ${cr.DOODLES.join("|")}, at?, dur?=1.6, x?, y? (0..1), size?=0.5 (of the width), color?=#ffe7c4, around? (subject clip id: centre on the performer's head, follows the camera), behind?=false (behind the person; needs around), draw? (s to draw)} Hand-drawn ink graphics that draw themselves.`,
    run: async (s, a) => {
      const at = a.num("at", s.time),
        dur = a.num("dur", 1.6);
      let x = a.num("x", 0.5),
        y = a.num("y", 0.4);
      const around = a.optStr("around");
      let size = a.num("size", 0.5);
      if (around) {
        const c = ops.getClip(s.project, around);
        const as = findAsset(s.project, c.assetId);
        const m = as ? await matteLoad(as.id) : null;
        const src = c.in + (at + 0.1 - c.start) * c.speed;
        const hp = m ? cr.headPoint(matteAt(m, src), m.w, m.h) : { x: 0.5, y: 0.22, found: false };
        const sc = cr.sourceToScreen(s.project, c, at + 0.1, hp, valueAt as never);
        x = a.optNum("x") ?? sc.x;
        y = a.optNum("y") ?? sc.y - (a.str("type") === "emphasis" || a.str("type") === "crown" ? 0.09 : 0);
        if (a.optNum("size") === undefined) size = Math.min(0.9, 0.5 * valueAt(c.transform.scale, at - c.start, 1));
      }
      const color = a.str("color", "#ffe7c4");
      return s.mutate("doodle", (p) => {
        const els = cr.doodleEls(a.str("type") as cr.Doodle, { x, y, size, t0: 0, t1: dur, color, aspect: p.width / p.height, draw: a.optNum("draw") });
        let trackId: string | undefined;
        if (around && a.bool("behind")) {
          const cut = cr.cutoutFor(p, around);
          trackId = cr.trackBelow(p, p.tracks.find((t) => t.clips.includes(cut))!.id, at, dur);
        }
        return procClip(p, { type: "motion", style: "custom", motion: { theme: "noir", bg: false, els } as unknown as motion.MotionSpec }, at, dur, { name: `Doodle ${a.str("type")}`, trackId });
      });
    },
  },
  "media.proxy": {
    help: "{assetId?} Make a light 720p editing copy of a big (2K/4K) video: smooth preview and scrubbing, no blank preview on phones. The export always uses the original. Runs automatically for new 4K imports.",
    run: async (s, a) => {
      const list = a.optStr("assetId") ? [findAsset(s.project, a.str("assetId"))!] : s.project.assets.filter(wantsProxy);
      const out: { id: string; proxy: string }[] = [];
      for (const as of list) {
        if (!as || as.kind !== "video") continue;
        const px = await makeProxy(as, (f) => s.setStatus(`Preparing editing copy ${Math.round(f * 100)}%`));
        s.mutate("proxy", (p) => {
          const cur = p.assets.find((x) => x.id === as.id);
          if (cur && px) cur.proxy = px;
        });
        await s.pool.dropFramers("preview");
        out.push({ id: as.id, proxy: px ? `${px.width}x${px.height}` : "none" });
      }
      s.setStatus("");
      return out;
    },
  },
  "layout.strips": {
    help: "{ids:[clip ids, 3..6], start?, hold?=1.6, collapse?=0.8, hero? (index kept full frame)} Strip intro: the clips appear as vertical slices side by side (staggered slide-in), then the slices collapse into the hero shot full frame.",
    run: (s, a) =>
      s.mutate("strips", (p) => {
        const ids = a.any("ids") as string[];
        const n = ids.length;
        const start = a.num("start", ops.getClip(p, ids[0]!).start);
        const hold = a.num("hold", 1.6),
          col = a.num("collapse", 0.8);
        const hero = Math.min(n - 1, a.num("hero", Math.floor(n / 2)));
        const tot = hold + col + 0.6;
        ids.forEach((id, i) => {
          const c = ops.getClip(p, id);
          const x0 = i / n,
            x1 = (i + 1) / n;
          const d0 = i * 0.08;
          const keys: NonNullable<Clip["win"]> = [
            { t: 0, x0, y0: i % 2 ? 0 : 1, x1, y1: i % 2 ? 0 : 1 },
            { t: d0, x0, y0: i % 2 ? 0 : 1, x1, y1: i % 2 ? 0 : 1 },
            { t: d0 + 0.55, x0, y0: 0, x1, y1: 1 },
            { t: hold, x0, y0: 0, x1, y1: 1 },
          ];
          if (i === hero) keys.push({ t: hold + col, x0: 0, y0: 0, x1: 1, y1: 1 });
          else keys.push({ t: hold + col, x0: i < hero ? 0 : 1, y0: 0, x1: i < hero ? 0 : 1, y1: 1 });
          ops.moveClip(p, id, start, topTrack(p, start, Math.max(c.dur, tot)));
          if (c.dur < tot && i !== hero) ops.trimClip(p, id, "end", start + tot);
          c.win = keys;
        });
        return { clips: n, until: start + hold + col };
      }),
  },
  "layout.grid": {
    help: "{ids:[4 or 9 clip ids], start?, dur?=3, gap?=0.012, bg?=#0b0b0b, stagger?=0.1} Grid split: the shots open as tiles of a 2×2 or 3×3 grid with thin gaps (product / b-roll style), then the first tile expands to full frame at the end.",
    run: (s, a) =>
      s.mutate("grid", (p) => {
        const ids = a.any("ids") as string[];
        const n = ids.length >= 9 ? 3 : 2;
        const start = a.num("start", ops.getClip(p, ids[0]!).start);
        const dur = a.num("dur", 3),
          gap = a.num("gap", 0.012),
          st = a.num("stagger", 0.1);
        const bg = ops.addClip(p, { kind: "color", start, dur, trackId: ops.trackFor(p, "video", start, dur).id });
        ops.patchClip(p, bg.id, { color: a.str("bg", "#0b0b0b"), name: "Grid background" });
        ids.slice(0, n * n).forEach((id, i) => {
          const c = ops.getClip(p, id);
          const gx = i % n,
            gy = Math.floor(i / n);
          const x0 = gx / n + gap / 2,
            x1 = (gx + 1) / n - gap / 2,
            y0 = gy / n + gap / 2,
            y1 = (gy + 1) / n - gap / 2;
          const cx = (x0 + x1) / 2,
            cy = (y0 + y1) / 2;
          const d0 = i * st;
          const keys: NonNullable<Clip["win"]> = [
            { t: 0, x0: cx, y0: cy, x1: cx, y1: cy },
            { t: d0, x0: cx, y0: cy, x1: cx, y1: cy },
            { t: d0 + 0.5, x0, y0, x1, y1 },
            { t: dur - 0.6, x0, y0, x1, y1 },
          ];
          if (i === 0) keys.push({ t: dur, x0: 0, y0: 0, x1: 1, y1: 1 });
          else keys.push({ t: dur - 0.25, x0: cx, y0: cy, x1: cx, y1: cy });
          ops.moveClip(p, id, start, topTrack(p, start, Math.max(c.dur, dur)));
          if (c.dur > dur && i !== 0) ops.trimClip(p, id, "end", start + dur);
          c.win = keys;
        });
        return { tiles: Math.min(ids.length, n * n), background: bg.id };
      }),
  },
  "layout.compare": {
    help: '{a, b (clip ids), labels?: ["RAW","EDITED"], bg?="#ffffff", title?, gap?=40} Side-by-side compare (Raw vs Edited, "$5 edit" vs "our work").',
    run: (s, a) =>
      s.mutate("compare layout", (p) => {
        const A = ops.getClip(p, a.str("a"));
        const B = ops.getClip(p, a.str("b"));
        const labels = (a.any("labels") as string[] | undefined) ?? ["RAW", "EDITED"];
        const start = Math.min(A.start, B.start);
        const dur = Math.max(A.start + A.dur, B.start + B.dur) - start;
        const W = p.width;
        const H = p.height;
        const gap = a.num("gap", 40);
        const portrait = H > W;
        // Each clip scaled to fit half the frame (side by side in landscape, small-left/big-right in portrait).
        const place = (c: Clip, side: -1 | 1, k: number, dy: number) => {
          ops.patchClip(p, c.id, { transform: { scale: k, x: side * (portrait ? W * 0.23 : W / 4 + gap / 4) + (portrait && side === -1 ? -W * 0.02 : 0), y: dy } });
        };
        place(A, -1, portrait ? 0.36 : 0.46, portrait ? H * 0.06 : 0);
        place(B, 1, portrait ? 0.52 : 0.46, 0);
        const bg = ops.addClip(p, { kind: "color", start, dur, trackId: ops.trackFor(p, "video", start, dur).id });
        ops.patchClip(p, bg.id, { color: a.str("bg", "#ffffff"), name: "Compare background" });
        // Background must sit under both clips: move it to the bottom track.
        const bt = p.tracks.find((t) => t.clips.includes(bg))!;
        bt.clips = bt.clips.filter((x) => x !== bg);
        let bottom = [...p.tracks].reverse().find((t) => t.kind === "video" && !t.clips.some((c) => c.start < start + dur && c.start + c.dur > start));
        if (!bottom) {
          bottom = newTrack("video", "BG");
          const lastVideo = p.tracks.map((t) => t.kind).lastIndexOf("video");
          p.tracks.splice(lastVideo + 1, 0, bottom);
        }
        bottom.clips.push(bg);
        const ink = a.str("bg", "#ffffff").toLowerCase() === "#ffffff" ? "#111111" : "#ffffff";
        const ids = labels.slice(0, 2).map((txt, i) => {
          const tc = ops.addClip(p, { text: txt, start, dur, trackId: topTrack(p, start, dur) });
          ops.patchClip(p, tc.id, { text: { size: portrait ? 64 : 54, weight: 800, color: ink, stroke: "", strokeWidth: 0, shadow: 0 }, transform: { x: (i ? 1 : -1) * (portrait ? W * 0.23 : W / 4), y: portrait ? (i ? -H * 0.28 : -H * 0.12) : -H * 0.4 } });
          return tc.id;
        });
        if (a.optStr("title")) {
          const tc = ops.addClip(p, { text: a.str("title"), start, dur, trackId: topTrack(p, start, dur) });
          ops.patchClip(p, tc.id, { text: { size: 88, weight: 800, color: ink, stroke: "", strokeWidth: 0, shadow: 0 }, transform: { y: -H * 0.4 } });
          ids.push(tc.id);
        }
        return { background: bg.id, labels: ids };
      }),
  },

  "music.compose": {
    help: `{seconds, mood?: ${["melancholy", "hopeful", "dark", "tense", "calm", "epic", "battle"].join("|")}, lift? (s where the score brightens), bpm?, root? (MIDI, 57=A), seed?, start?=0, volume?=0.8, name?} Compose an original soundtrack on the phone and put it on the timeline.`,
    run: async (s, a) => {
      const blob = await compose({ mood: a.optStr("mood") as Mood | undefined, seconds: a.num("seconds"), lift: a.optNum("lift"), bpm: a.optNum("bpm"), root: a.optNum("root"), seed: a.optNum("seed") });
      return await s.mutateAsync("compose music", async (p) => {
        const asset = await importBlob(p, blob, `${a.str("name", "Score")}.wav`, "agent");
        const clip = ops.addClip(p, { assetId: asset.id, start: a.num("start", 0) });
        ops.patchClip(p, clip.id, { volume: a.num("volume", 0.8), fadeOut: 1.5 });
        return { assetId: asset.id, clipId: clip.id, seconds: r3(asset.duration) };
      });
    },
  },
  "sfx.add": {
    help: `{type: ${SFX.join("|")}, at: seconds | [seconds...], volume?=0.9} Sound effects generated on the phone (one clip per hit).`,
    run: async (s, a) => {
      const type = a.str("type") as Sfx;
      if (!SFX.includes(type)) throw new ops.OpError(`type must be one of ${SFX.join(", ")}`);
      const at = a.any("at");
      const times = (Array.isArray(at) ? at : [at]).map(Number).filter((x) => Number.isFinite(x));
      const blob = await sfx(type, a.num("seed", 1));
      return await s.mutateAsync("sound effects", async (p) => {
        const asset = await importBlob(p, blob, `${type}.wav`, "agent");
        const ids = times.map((t) => {
          const c = ops.addClip(p, { assetId: asset.id, start: t });
          ops.patchClip(p, c.id, { volume: a.num("volume", 0.9) });
          return c.id;
        });
        return { assetId: asset.id, clips: ids.length };
      });
    },
  },
  "sfx.auto": {
    help: "{style?: clean|cinematic|playful, density?: 0.5..1.5 (=1), volume?=0.8, clear?=true} Sound design for the whole edit: listens to the timeline (cuts, transitions, titles, motion-graphics elements, effects) and layers matching sound effects — whooshes into cuts, pops and shimmers on UI elements, ticks on dots, chimes on logos, booms and reverse swells for cinematic titles. Everything is generated on the phone.",
    run: async (s, a) => {
      const style = a.str("style", "clean") as "clean" | "cinematic" | "playful";
      const density = Math.max(0.3, Math.min(1.6, a.num("density", 1)));
      const vol = a.num("volume", 0.8);
      const p0 = s.project;
      const ev: { t: number; type: Sfx; v: number }[] = [];
      const add = (t: number, type: Sfx, v = 1, pre = 0) => {
        if (t - pre >= 0) ev.push({ t: t - pre, type, v });
      };
      // Peak offsets so the sound lands on the moment, not after it.
      const PRE: Partial<Record<Sfx, number>> = { whoosh: 0.18, swoosh: 0.3, deepWhoosh: 0.55, reverse: 1.55, riser: 2 };
      const put = (t: number, type: Sfx, v = 1) => add(t, type, v, PRE[type] ?? 0);
      for (const tr of p0.tracks) {
        if (tr.kind !== "video" || tr.hidden) continue;
        for (const c of tr.clips) {
          if (c.kind === "media" && c.start > 0.05) {
            const hasT = c.transitionIn.kind !== "none";
            if (style === "cinematic") put(c.start, hasT ? "deepWhoosh" : "whoosh", 0.9);
            else if (style === "playful") put(c.start, hasT ? "swoosh" : "pop", 0.8);
            else put(c.start, hasT ? "swoosh" : "whoosh", 0.6);
          }
          if (c.kind === "text" && density >= 0.6) put(c.start + 0.02, style === "playful" ? "pop" : style === "cinematic" ? "whoosh" : "ui", 0.6);
          if (c.sceneId) {
            const sc = p0.scenes.find((x) => x.id === c.sceneId);
            for (const l of sc?.layers ?? []) {
              const pr = l.proc;
              if (!pr) continue;
              if (pr.type === "fx") put(c.start, /impact|clash|shockwave|hitspark/.test(pr.style) ? "impact" : "swoosh", 0.8);
              if (pr.type === "kinetic" || pr.type === "captions") put(c.start, "ui", 0.5);
              if (pr.type === "motion" && pr.motion) {
                for (const el of pr.motion.els) {
                  const t = c.start + el.t0;
                  switch (el.k) {
                    case "dot":
                      put(t, "tick", 0.7);
                      break;
                    case "spark":
                      put(t, "shimmer", 0.6);
                      put(t, "swoosh", 0.4);
                      break;
                    case "pill":
                      put(t, "swoosh", 0.5);
                      put(t + 0.12, "pop", 0.55);
                      if (density > 0.9) put(t + 0.7, "shimmer", 0.3);
                      break;
                    case "frame":
                      put(t, "deepWhoosh", 0.6);
                      put(t + 0.5, "click", 0.5);
                      break;
                    case "path":
                    case "line":
                      put(t + 0.2, "swoosh", 0.45);
                      break;
                    case "dots":
                      for (let i = 0; i < (el.n ?? 5); i++) put(t + i * 0.12, "tick", 0.55);
                      break;
                    case "ring":
                      put(t, "hum", 0.5);
                      put(t, "riser", 0.35);
                      break;
                    case "title":
                      if (style !== "playful") put(t, style === "cinematic" ? "whoosh" : "ui", 0.45);
                      break;
                    case "logo":
                      put(t, "reverse", 0.5);
                      put(t, "chime", 0.6);
                      break;
                    case "eclipse":
                      put(t + 0.2, "reverse", 0.6);
                      put(t + 0.25, "boom", 0.9);
                      break;
                    case "brush":
                      put(t, "slash", 0.6);
                      put(t, "boom", 0.8);
                      break;
                    case "flash":
                      put(t, "impact", 0.8);
                      break;
                  }
                }
              }
            }
          }
        }
      }
      ev.sort((x, y) => x.t - y.t);
      // Thin out: one of each type per 0.15 s, and fewer overall at low density.
      const last: Partial<Record<Sfx, number>> = {};
      const keep = ev.filter((e, i) => {
        if ((last[e.type] ?? -9) > e.t - 0.15) return false;
        if (density < 1 && (i * 0.618) % 1 > density) return false;
        last[e.type] = e.t;
        return true;
      });
      if (!keep.length) return { sounds: 0 };
      const types = [...new Set(keep.map((e) => e.type))];
      const blobs = new Map<Sfx, Blob>();
      for (const ty of types) blobs.set(ty, await sfx(ty, 7));
      return await s.mutateAsync("sound design", async (p) => {
        if (a.bool("clear", true))
          for (const tr of p.tracks) tr.clips = tr.clips.filter((c) => !(c.name ?? "").startsWith("SFX "));
        const assets = new Map<Sfx, string>();
        for (const ty of types) assets.set(ty, (await importBlob(p, blobs.get(ty)!, `sfx-${ty}.wav`, "agent")).id);
        for (const e of keep) {
          const c = ops.addClip(p, { assetId: assets.get(e.type)!, start: Math.max(0, e.t) });
          ops.patchClip(p, c.id, { volume: Math.min(1, vol * e.v), name: `SFX ${e.type}` });
        }
        return { sounds: keep.length, types };
      });
    },
  },
  "audio.enhance": {
    help: "{id (video or audio clip), denoise?=0.75 (0..1), voicing?: song|voice|none, target?=-14 (LUFS), width?=0.15} Clean and master the clip's sound on the phone: removes steady noise (hiss, hum, wind, fan, room rumble, crowd murmur bed), cleans mud, adds presence and air, glue compression, loudness to the target and a −1 dB limiter. The cleaned audio replaces the clip's own sound (original muted, kept). Returns {assetId, clipId, lufsBefore, lufsAfter}.",
    run: async (s, a) => {
      const c = ops.getClip(s.project, a.str("id"));
      const as = findAsset(s.project, c.assetId);
      if (!as || (as.kind !== "video" && as.kind !== "audio")) throw new ops.OpError("Pick a video or audio clip.");
      s.setStatus("Reading the sound…");
      const full = await s.pool.audioBuffer(as, new OfflineAudioContext(2, 1, 48000));
      if (!full) throw new ops.OpError("That clip has no sound.");
      const sr = full.sampleRate;
      const i0 = Math.max(0, Math.floor(c.in * sr)),
        i1 = Math.min(full.length, Math.ceil((c.in + c.dur * c.speed) * sr));
      const part = new AudioBuffer({ numberOfChannels: Math.min(2, full.numberOfChannels), length: Math.max(1, i1 - i0), sampleRate: sr });
      for (let ch = 0; ch < part.numberOfChannels; ch++) part.copyToChannel(full.getChannelData(ch).slice(i0, i1), ch);
      const ch0 = [part.getChannelData(0), part.getChannelData(part.numberOfChannels > 1 ? 1 : 0)];
      const before = loudness(ch0, sr);
      s.setStatus("Cleaning and mastering the sound…");
      const out = await enhance(part, { denoise: a.num("denoise", 0.75), voicing: a.str("voicing", "song") as EnhanceOptions["voicing"], target: a.num("target", -14), width: a.num("width", 0.15) });
      const after = loudness([out.getChannelData(0), out.getChannelData(1)], sr);
      const blob = toWav(out);
      s.setStatus("");
      return await s.mutateAsync("enhance audio", async (p) => {
        const asset = await importBlob(p, blob, `${(as.name || "clip").replace(/\.[^.]+$/, "")} (clean).wav`, "agent");
        const cc = ops.getClip(p, c.id);
        cc.volume = 0;
        const nc = ops.addClip(p, { assetId: asset.id, start: cc.start });
        if (Math.abs(cc.speed - 1) > 1e-3) ops.setSpeed(p, nc.id, cc.speed);
        ops.patchClip(p, nc.id, { name: "Clean sound", volume: 1 });
        return { assetId: asset.id, clipId: nc.id, lufsBefore: Math.round(before * 10) / 10, lufsAfter: Math.round(after * 10) / 10 };
      });
    },
  },
  "sfx.ambience": {
    help: "{type: river|wind|city|rain|night, seconds, start?=0, volume?=0.6} Environmental sound bed generated on the phone.",
    run: async (s, a) => {
      const blob = await ambience(a.str("type") as Ambience, a.num("seconds"), a.num("seed", 3));
      return await s.mutateAsync("ambience", async (p) => {
        const asset = await importBlob(p, blob, `${a.str("type")} ambience.wav`, "agent");
        const clip = ops.addClip(p, { assetId: asset.id, start: a.num("start", 0) });
        ops.patchClip(p, clip.id, { volume: a.num("volume", 0.6), fadeIn: 0.8, fadeOut: 0.8 });
        return { assetId: asset.id, clipId: clip.id };
      });
    },
  },

  "voice.list": { help: "Voices: stock, character presets and your Voice Lab voices.", run: (s) => s.voice.list() },
  "voice.speak": {
    help: "{text, mode?: speak|dialogue, voice?, speed?, cast?:{Name:voice}, add?=true, start?} VolTune speech → audio asset (+lip-sync). Dialogue: 'Name: line', '(whispering)', '[pause 1s]'.",
    run: async (s, a) => {
      const { result, asset } = await s.speak({ text: a.str("text"), mode: a.optStr("mode") as never, voice: a.optStr("voice"), speed: a.optNum("speed"), cast: a.any("cast") as Record<string, string> | undefined });
      let clipId: string | undefined;
      if (a.bool("add", true)) clipId = s.mutate("voice to timeline", (p) => ops.addClip(p, { assetId: asset.id, start: a.optNum("start") ?? s.time }).id);
      return { assetId: asset.id, clipId, seconds: r3(result.seconds), cast: result.cast, lines: result.lines.map((l) => ({ ...l, start: r3(l.start), end: r3(l.end) })) };
    },
  },

  "playback.seek": { help: "{t}", run: (s, a) => (s.seek(a.num("t")), s.time) },
  "playback.play": { help: "", run: (s) => (void s.play(), true) },
  "playback.pause": { help: "", run: (s) => (s.pause(), s.time) },

  "render.start": {
    help: "{height?, fps?, quality?: low|medium|high|very-high, bitrate?, codec?: avc|hevc, from?, to?, name?} Render MP4 into Movies/VideoStudio. Waits until done.",
    run: async (s, a) => {
      if (activeExport) throw new ops.OpError("A render is already running.");
      const o: ExportOptions = { height: a.optNum("height"), fps: a.optNum("fps"), quality: a.optStr("quality") as never, bitrate: a.optNum("bitrate"), codec: a.optStr("codec") as never, from: a.optNum("from"), to: a.optNum("to"), name: a.optStr("name") };
      const job = new ExportJob(s.project, s.pool, o);
      activeExport = job;
      job.onProgress = (pr) => s.setStatus(`Rendering ${pr.frame}/${pr.frames} · ${pr.fps.toFixed(1)} fps`);
      try {
        const r = await job.run();
        s.setStatus(`Saved ${r.name}`);
        agentEvents.onRender?.({ name: r.name, path: (r as { path?: string }).path, seconds: r.seconds });
        const { blob: _b, ...rest } = r;
        return rest;
      } finally {
        activeExport = null;
      }
    },
  },
  "render.cancel": { help: "", run: () => (activeExport?.cancel(), true) },
};

/** Live mode hooks: the app shows each command as it runs (see ui/live.ts). */
export interface LiveHooks {
  batchStart(n: number, depth: number): void;
  batchEnd(depth: number): void;
  before(op: string, args: Record<string, unknown>, depth: number): Promise<void>;
  after(op: string, args: Record<string, unknown>, result: CommandResult, depth: number): Promise<void>;
}
export const STOPPED = "Stopped by you in the app.";
/** App-level events other parts of the app listen to (notifications). */
export const agentEvents: { onRender?: (r: { name: string; path?: string; seconds: number }) => void } = {};
let live: LiveHooks | null = null;
let depth = 0;
export function setLiveHooks(h: LiveHooks | null): void {
  live = h;
}

/** Replace "$N.path" strings with values from earlier results. */
function resolveRefs(v: unknown, results: CommandResult[]): unknown {
  if (typeof v === "string") {
    const m = /^\$(\d+)((?:\.[\w]+|\[\d+\])*)$/.exec(v);
    if (!m) return v;
    let cur: unknown = results[Number(m[1])]?.result;
    for (const part of m[2]!.match(/\.[\w]+|\[\d+\]/g) ?? []) {
      const key = part.startsWith(".") ? part.slice(1) : part.slice(1, -1);
      cur = cur && typeof cur === "object" ? (cur as Record<string, unknown>)[key] : undefined;
    }
    return cur;
  }
  if (Array.isArray(v)) return v.map((x) => resolveRefs(x, results));
  if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, resolveRefs(x, results)]));
  return v;
}

export async function runCommand(s: Studio, cmd: Command, previous: CommandResult[] = []): Promise<CommandResult> {
  const op = String(cmd.op ?? "");
  const h = HANDLERS[op];
  if (!h) return { ok: false, op, error: `Unknown op "${op}". Run {"op":"help"}.` };
  let args: Record<string, unknown> = {};
  try {
    args = resolveRefs(cmd, previous) as Record<string, unknown>;
    if (live) await live.before(op, args, depth);
    depth++;
    let result: unknown;
    try {
      result = await h.run(s, new Args(args));
    } finally {
      depth--;
    }
    const r: CommandResult = { ok: true, op, result: result === undefined ? true : result };
    if (live) await live.after(op, args, r, depth).catch(() => {});
    return r;
  } catch (e) {
    const r: CommandResult = { ok: false, op, error: e instanceof Error ? e.message : String(e) };
    if (live && r.error !== STOPPED) await live.after(op, args, r, depth).catch(() => {});
    return r;
  }
}

/** Run commands in order. Stops at the first error unless `continueOnError`. */
export async function runBatch(s: Studio, cmds: Command[], continueOnError = false, onProgress?: (done: number, op: string) => void): Promise<CommandResult[]> {
  const results: CommandResult[] = [];
  const d = depth;
  live?.batchStart(cmds.length, d);
  try {
    for (const c of cmds) {
      onProgress?.(results.length, String(c.op ?? ""));
      const r = await runCommand(s, c, results);
      results.push(r);
      if (r.error === STOPPED) break;
      if (!r.ok && !continueOnError) break;
    }
  } finally {
    live?.batchEnd(d);
  }
  return results;
}

export function commandList(): { op: string; help: string }[] {
  return Object.entries(HANDLERS).map(([op, h]) => ({ op, help: h.help }));
}
