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
import { ambience, compose, type Ambience, type Mood } from "../audio/compose";
import { exposedKey, findAsset, projectDuration, uid, type Ease, type Grade, type Project, type Stroke, type TransitionKind } from "../core/model";
import { fetchToBlob, importBlob } from "../media/library";
import { ExportJob, type ExportOptions } from "../render/export";
import { fetchInbox, listInbox } from "./remote";
import { assetThumb, contactSheet, frameSnapshot, sceneSnapshot } from "../render/snapshot";
import { analyseBeats, beatCut } from "../app/autoedit";

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
  "edit.beatCut": {
    help: "{assetIds?, musicId?, style?: punchy|smooth|cinematic|glitch, every? (beats per cut), loop?, replace?=true, maxDur?} Auto-edit your media to the music: beat cuts, overlapping transitions, beat punches, motion blur, look.",
    run: (s, a) =>
      beatCut(s, { assetIds: a.any("assetIds") as string[] | undefined, musicId: a.optStr("musicId"), style: a.optStr("style") as never, every: a.optNum("every"), loop: a.bool("loop"), replace: a.bool("replace", true), maxDur: a.optNum("maxDur") }),
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
    help: "{sceneId, name?, x?, y? (ground px), height? (px), hair?: messy|short|long|spiky, jacket?: leather|hoodie|coat|none, shirt?: turtleneck|tee|shirt, chain?, props?: [cigarette], propHand?: R|L, palette?: black|white|red|navy|brown, colors?:{skin,hair,eyes,jacket,...}, stance?, expr?} Add a fully drawn, rigged anime character (curves, cel shading, face rig, auto blink, hair follow-through).",
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
    help: "{sceneId, layerId, actions:[{at (s), do: stance|expr|look|smoke|exhale|nod|shake|tilt|shrug|wave|point|gesture|lean|turn|laugh|breathe|set, ...}]} Animate a character with high-level actions (keys every channel for you). Run char.help for options.",
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
        for (const k of ["hair", "jacket", "shirt", "propHand"] as const) if (a.optStr(k)) (sp as unknown as Record<string, string>)[k] = a.str(k);
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

  "music.compose": {
    help: `{seconds, mood?: ${["melancholy", "hopeful", "dark", "tense", "calm", "epic"].join("|")}, lift? (s where the score brightens), bpm?, root? (MIDI, 57=A), seed?, start?=0, volume?=0.8, name?} Compose an original soundtrack on the phone and put it on the timeline.`,
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
        const { blob: _b, ...rest } = r;
        return rest;
      } finally {
        activeExport = null;
      }
    },
  },
  "render.cancel": { help: "", run: () => (activeExport?.cancel(), true) },
};

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
  try {
    const args = resolveRefs(cmd, previous) as Record<string, unknown>;
    const result = await h.run(s, new Args(args));
    return { ok: true, op, result: result === undefined ? true : result };
  } catch (e) {
    return { ok: false, op, error: e instanceof Error ? e.message : String(e) };
  }
}

/** Run commands in order. Stops at the first error unless `continueOnError`. */
export async function runBatch(s: Studio, cmds: Command[], continueOnError = false, onProgress?: (done: number, op: string) => void): Promise<CommandResult[]> {
  const results: CommandResult[] = [];
  for (const c of cmds) {
    onProgress?.(results.length, String(c.op ?? ""));
    const r = await runCommand(s, c, results);
    results.push(r);
    if (!r.ok && !continueOnError) break;
  }
  return results;
}

export function commandList(): { op: string; help: string }[] {
  return Object.entries(HANDLERS).map(([op, h]) => ({ op, help: h.help }));
}
