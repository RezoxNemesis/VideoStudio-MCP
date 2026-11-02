/** Editor view: preview, transport, timeline and a context toolbar (CapCut-style). */
import type { Studio } from "../app/studio";
import { findAsset, findClip, newTrack } from "../core/model";
import * as ops from "../core/ops";
import { valueAt } from "../core/keyframes";
import { createScene } from "../anim/ops";
import { beatCut, type CutStyle } from "../app/autoedit";
import * as MV from "../app/musicvideo";
import { Preview } from "./preview";
import { Timeline } from "./timeline";
import { openInspector } from "./inspector";
import { runCommand, type Command } from "../agent/api";
import * as PR from "../anim/proc";
import { btn, fmtTime, h, icon, pickFiles, select, sheet, slider, toast, toolBtn, colorInput, toggle } from "./dom";
import { runBatch } from "../agent/api";

export interface EditorHost {
  goto(view: "edit" | "animate" | "voice" | "export" | "agent", arg?: string): void;
}

export class EditorView {
  readonly el: HTMLElement;
  readonly preview: Preview;
  readonly timeline: Timeline;
  private tools: HTMLElement;
  private playBtn: HTMLButtonElement;
  private timeEl: HTMLElement;

  constructor(
    private readonly s: Studio,
    private readonly host: EditorHost,
  ) {
    this.preview = new Preview(s);
    this.timeline = new Timeline(s);
    this.playBtn = h("button", { class: "play", type: "button", "aria-label": "Play" }, icon("play", 26));
    this.playBtn.addEventListener("click", () => s.toggle());
    this.timeEl = h("span", { class: "tc" }, "00:00:00");
    const transport = h(
      "div",
      { class: "transport" },
      this.timeEl,
      h("div", { class: "grow" }),
      btn("Undo", "undo", () => s.undo() || toast("Nothing to undo"), "icon-only ghost"),
      this.playBtn,
      btn("Redo", "redo", () => s.redo() || toast("Nothing to redo"), "icon-only ghost"),
      h("div", { class: "grow" }),
      btn("Add marker", "key", () => s.mutate("marker", (p) => p.markers.push({ t: s.time, label: `M${p.markers.length + 1}` })), "icon-only ghost"),
      btn("Zoom timeline", "zoomIn", () => this.timeline.zoom(this.timeline.pps * 1.6), "icon-only ghost"),
    );
    this.tools = h("div", { class: "toolbar", role: "toolbar" });
    this.el = h("div", { class: "view editor" }, this.preview.el, transport, this.timeline.el, this.tools);
    s.on("select", () => this.drawTools());
    s.on("change", () => this.drawTools());
    s.on("play", () => this.playBtn.replaceChildren(icon(s.playing ? "pause" : "play", 26)));
    s.on("time", () => (this.timeEl.textContent = `${fmtTime(s.time, s.project.fps)}`));
    this.drawTools();
  }

  private lastToolsKey = "";
  private drawTools() {
    const s = this.s;
    const f = s.selection ? findClip(s.project, s.selection) : null;
    const key = f ? `${f.clip.id}|${f.clip.kind}` : "none";
    if (key === this.lastToolsKey) return;
    this.lastToolsKey = key;
    if (!f) {
      this.tools.replaceChildren(
        toolBtn("Import", "folder", () => void this.importMedia("video/*,image/*,audio/*")),
        toolBtn("Motion kit", "wand", () => this.motionKit()),
        toolBtn("Beat cut", "wand", () => this.beatCutSheet()),
        toolBtn("Music video", "film", () => this.musicVideoSheet()),
        toolBtn("Text", "text", () => this.addText()),
        toolBtn("Voice", "mic", () => this.host.goto("voice")),
        toolBtn("Animate", "brush", () => this.newAnimation()),
        toolBtn("Audio", "volume", () => void this.importMedia("audio/*")),
        toolBtn("Shape", "shape", () => this.add("shape")),
        toolBtn("Colour", "rect", () => this.add("color")),
        toolBtn("Adjust", "adjust", () => this.add("adjust")),
        toolBtn("Tracks", "layers", () => this.trackMenu()),
        toolBtn("Canvas", "grid", () => this.canvasSettings()),
      );
      return;
    }
    const c = f.clip;
    const a = findAsset(s.project, c.assetId);
    const id = c.id;
    const items = [
      toolBtn("Edit", "sliders", () => openInspector(s, "edit")),
      toolBtn("Split", "split", () => this.safe(() => s.mutate("split", (p) => ops.splitClip(p, id, s.time)))),
      toolBtn("Delete", "trash", () => s.mutate("delete", (p) => ops.deleteClip(p, id))),
      toolBtn("Ripple", "trash", () => s.mutate("ripple delete", (p) => ops.deleteClip(p, id, true))),
      toolBtn("Copy", "copy", () => s.mutate("duplicate", (p) => s.select(ops.duplicateClip(p, id).id))),
      c.kind === "text" ? toolBtn("Text", "text", () => openInspector(s, "text")) : null,
      toolBtn("Motion", "move", () => openInspector(s, "transform")),
      toolBtn("Keyframe", "key", () => this.keyAll(id)),
      a?.kind !== "audio" ? toolBtn("Colour", "color", () => openInspector(s, "color")) : null,
      a?.kind !== "audio" ? toolBtn("Effects", "wand", () => openInspector(s, "effects")) : null,
      toolBtn("Transition", "transition", () => openInspector(s, "transition")),
      c.kind === "media" || c.kind === "anim" ? toolBtn("Speed", "speed", () => openInspector(s, "edit")) : null,
      c.kind === "anim" ? toolBtn("Draw", "brush", () => this.host.goto("animate", c.sceneId)) : null,
      toolBtn("Done", "check", () => s.select(null)),
    ];
    this.tools.replaceChildren(...(items.filter(Boolean) as HTMLElement[]));
  }

  private safe(fn: () => void) {
    try {
      fn();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    }
  }

  /** Keyframe every transform property at the playhead (a "pose" key). */
  private keyAll(id: string) {
    const s = this.s;
    this.safe(() =>
      s.mutate("key all", (p) => {
        const c = ops.getClip(p, id);
        const t = Math.max(c.start, Math.min(c.start + c.dur, s.time));
        const local = t - c.start;
        for (const [prop, def] of [["x", 0], ["y", 0], ["scale", 1], ["rotation", 0], ["opacity", 1]] as const) {
          const cur = c.transform[prop];
          const v = valueAt(cur, local, def);
          ops.keyframe(p, id, prop, t, v, "inOut", def);
        }
      }),
    );
    toast("Keyframed position, scale, rotation and opacity");
  }

  async importMedia(accept: string): Promise<void> {
    const files = await pickFiles(accept, true);
    if (!files.length) return;
    try {
      await this.s.importFiles(files, true);
      toast(`Added ${files.length} file${files.length > 1 ? "s" : ""}`, "ok");
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error", 5000);
    }
  }

  private addText() {
    const s = this.s;
    const c = s.mutate("add text", (p) => ops.addClip(p, { text: "Your title", start: s.time, dur: 3 }));
    s.select(c.id);
    openInspector(s, "text");
  }

  private add(kind: "shape" | "color" | "adjust") {
    const s = this.s;
    const c = s.mutate(`add ${kind}`, (p) => ops.addClip(p, { kind, start: s.time, dur: kind === "color" ? 3 : 5 }));
    s.select(c.id);
    if (kind === "adjust") {
      s.mutate("adjust name", (p) => (ops.getClip(p, c.id).name = "Adjustment"));
      openInspector(s, "color");
    }
  }

  private newAnimation() {
    const s = this.s;
    const sc = s.mutate("new animation", (p) => createScene(p));
    s.mutate("animation to timeline", (p) => ops.addClip(p, { sceneId: sc.id, start: s.time }));
    this.host.goto("animate", sc.id);
  }

  /** One-tap motion toolkit: captions, kinetic type, motion graphics, hand-drawn FX, film overlays, collage. */
  private motionKit() {
    const s = this.s;
    let text = "Make it *move.*";
    let dur = 3;
    const run = async (cmd: Command) => {
      sh.close();
      const r = await runCommand(s, { ...cmd, start: "start" in cmd ? cmd.start : s.time, dur: "dur" in cmd ? cmd.dur : dur });
      if (!r.ok) return toast(r.error ?? "Failed", "error", 5000);
      const id = (r.result as { id?: string } | undefined)?.id;
      if (id) s.select(id);
      toast("Added at the playhead", "ok");
    };
    const chips = (title: string, items: readonly string[], make: (x: string) => Command) =>
      h("div", { class: "stack" }, h("h3", {}, title), h("div", { class: "chips" }, ...items.map((x) => h("button", { class: "chip", type: "button", onclick: () => void run(make(x)) }, x))));
    const voices = s.project.assets.filter((x) => x.kind === "audio" || x.kind === "video");
    let sync = voices.find((x) => x.createdBy === "voice")?.id ?? "";
    const ta = h("textarea", { rows: 2, "aria-label": "Text for captions and titles" }, text) as HTMLTextAreaElement;
    ta.addEventListener("input", () => (text = ta.value));
    const body = h(
      "div",
      { class: "stack" },
      h("p", { class: "hint" }, "Everything is added at the playhead as its own clip: move, trim, grade or delete it like any clip. Use *stars* around a word to emphasise it in captions."),
      ta,
      slider("Length", 0.5, 10, 0.5, dur, (v) => (dur = v), { fmt: (v) => `${v}s` }).el,
      voices.length ? select("Sync captions to", [["", "— no audio (even timing) —"] as [string, string]].concat(voices.map((x) => [x.id, x.name] as [string, string])), sync, (v) => (sync = v)) : h("span"),
      chips("Captions", PR.CAPTION_STYLES, (st) => ({ op: "captions.add", text, style: st, ...(sync ? { assetId: sync, start: undefined, dur: undefined } : { end: s.time + dur }) })),
      chips("Kinetic type", PR.KINETIC_STYLES, (st) => ({ op: "kinetic.add", text: text.replace(/\*/g, ""), style: st })),
      chips("Motion graphics", PR.MOGRAPH_TYPES, (ty) => ({ op: "mograph.add", type: ty })),
      chips("Hand-drawn FX (on twos)", PR.FX_TYPES, (ty) => ({ op: "fx.add", type: ty })),
      chips("Film overlays", PR.OVERLAY_TYPES, (ty) => ({ op: "overlay.add", type: ty })),
      chips("Collage", ["torn label", "paper", "newspaper", "kraft"], (x) => (x === "torn label" ? { op: "collage.label", text: text.replace(/\*/g, "") } : { op: "collage.paper", tone: x === "newspaper" ? "news" : x === "paper" ? "cream" : x })),
      chips("Collage photo (pick an image)", s.project.assets.filter((x) => x.kind === "image").slice(0, 12).map((x) => x.name), (name) => ({ op: "collage.add", assetId: s.project.assets.find((x) => x.name === name)!.id })),
    );
    const sh = sheet("Motion kit", body, { tall: true });
  }

  private beatCutSheet() {
    const s = this.s;
    const audio = s.project.assets.filter((a) => a.kind === "audio");
    let music = audio.find((a) => a.createdBy !== "voice")?.id ?? audio[0]?.id ?? "";
    let style: CutStyle = "punchy";
    let every = 2;
    const body = h(
      "div",
      { class: "stack" },
      h("p", { class: "hint" }, "Cuts all your photos and videos to the music: beat-timed cuts, overlapping transitions, beat punches, motion blur and a look. Existing clips of that media are replaced (undo brings them back)."),
      audio.length
        ? select("Music", audio.map((a) => [a.id, a.name] as [string, string]), music, (v) => (music = v))
        : h("p", { class: "hint" }, "No music imported: cuts follow a 120 BPM grid. Import an audio file for real beat sync."),
      select("Style", [["punchy", "Punchy (whips, zoom blur, flash)"], ["smooth", "Smooth (dissolves, drift)"], ["cinematic", "Cinematic (letterbox, teal & orange)"], ["glitch", "Glitch (RGB split, scanlines)"]] as const, style, (v) => {
        style = v;
        every = v === "punchy" || v === "glitch" ? 2 : 4;
      }),
      select("Cut every", [["1", "beat"], ["2", "2 beats"], ["4", "bar (4 beats)"], ["8", "2 bars"]] as const, "2", (v) => (every = Number(v))),
      btn("Make the edit", "wand", async () => {
        sh.close();
        s.setStatus("Finding the beat…");
        try {
          const r = await beatCut(s, { musicId: music || undefined, style, every });
          toast(`${r.cuts} cuts at ${r.bpm} BPM · ${r.duration}s`, "ok");
          s.seek(0);
        } catch (e) {
          toast(e instanceof Error ? e.message : String(e), "error", 5000);
        } finally {
          s.setStatus("");
        }
      }, "primary"),
    );
    const sh = sheet("Beat cut", body);
  }

  /** Song-aware auto edit: listens to structure, energy and lyrics, cuts in a genre style, adds lyric captions. */
  private musicVideoSheet() {
    const s = this.s;
    const songs = s.project.assets.filter((a) => a.kind === "audio" && a.createdBy !== "voice");
    let music = songs[0]?.id ?? "";
    let genre = "auto";
    let lyrics = true;
    let look = "";
    const body = h(
      "div",
      { class: "stack" },
      h("p", { class: "hint" }, "Makes a full music video from your photos and videos: finds the song's sections, energy and big hits, cuts faster in the chorus and drops, applies a colour look, and writes the lyrics on screen in time (the lyrics are heard on this phone; the model downloads once)."),
      songs.length ? select("Song", songs.map((a) => [a.id, a.name] as [string, string]), music, (v) => (music = v)) : h("p", { class: "hint" }, "Import a song first."),
      select("Style", [["auto", "Auto (from the song)"], ...Object.keys(MV.GENRES).map((g) => [g, g] as [string, string])] as [string, string][], genre, (v) => (genre = v)),
      toggle("Lyric captions", lyrics, (v) => (lyrics = v)),
      btn("Make the music video", "film", async () => {
        if (!music) return toast("Import a song first", "error");
        sh.close();
        s.setStatus("Listening to the song…");
        try {
          const [r] = await runBatch(s, [{ op: "edit.musicVideo", musicId: music, ...(genre !== "auto" ? { genre } : {}), lyrics, captions: lyrics }]);
          if (!r?.ok) throw new Error(r?.error ?? "Failed");
          const res = r.result as { cuts: number; genre: string; captions: number };
          toast(`${res.cuts} cuts · ${res.genre} · ${res.captions} lyric lines`, "ok", 4000);
          s.seek(0);
        } catch (e) {
          toast(e instanceof Error ? e.message : String(e), "error", 5000);
        } finally {
          s.setStatus("");
        }
      }, "primary"),
      h("h3", {}, "Tools"),
      btn("Duck music under voices", "volume", () => {
        const r = s.mutate("duck music", (p) => MV.duckMusic(p));
        toast(r.ducked ? `Music dips under ${r.windows} voice parts` : "No voice clips to duck under", r.ducked ? "ok" : "info");
      }, "ghost"),
      select("Colour look for the selected clip", [["", "Pick a look…"], ...Object.keys(MV.LOOKS).map((k) => [k, k] as [string, string])] as [string, string][], look, (v) => {
        look = v;
        if (!v) return;
        if (!s.selection) return toast("Select a clip first", "error");
        try {
          s.mutate("look", (p) => MV.applyLook(ops.getClip(p, s.selection!), v));
          toast(`Look: ${v}`, "ok");
        } catch (e) {
          toast(e instanceof Error ? e.message : String(e), "error");
        }
      }),
    );
    const sh = sheet("Music video", body, { tall: true });
  }

  private trackMenu() {
    const s = this.s;
    const body = h("div", { class: "stack" });
    const draw = () =>
      body.replaceChildren(
        ...s.project.tracks.map((t) =>
          h(
            "div",
            { class: "track-row" },
            h("input", { value: t.name, "aria-label": "Track name", onchange: (e: Event) => s.mutate("rename track", () => (t.name = (e.target as HTMLInputElement).value)) }),
            slider("Vol", 0, 2, 0.01, t.volume, (v) => s.mutate("track volume", () => (t.volume = v)), { fmt: (v) => `${Math.round(v * 100)}%` }).el,
            btn(t.locked ? "Unlock" : "Lock", t.locked ? "lock" : "unlock", () => (s.mutate("lock", () => (t.locked = !t.locked)), draw()), "icon-only ghost"),
            btn("Close gaps", "move", () => s.mutate("close gaps", (p) => ops.closeGaps(p, t.id)), "icon-only ghost"),
            btn("Remove", "trash", () => {
              if (t.clips.length) return toast("Empty the track first", "error");
              s.mutate("remove track", (p) => (p.tracks = p.tracks.filter((x) => x !== t)));
              draw();
            }, "icon-only ghost"),
          ),
        ),
        h("div", { class: "row" },
          btn("Video track", "plus", () => (s.mutate("add track", (p) => p.tracks.unshift(newTrack("video", `V${p.tracks.filter((x) => x.kind === "video").length + 1}`))), draw())),
          btn("Audio track", "plus", () => (s.mutate("add track", (p) => p.tracks.push(newTrack("audio", `A${p.tracks.filter((x) => x.kind === "audio").length + 1}`))), draw())),
        ),
      );
    draw();
    sheet("Tracks", body);
  }

  private canvasSettings() {
    const s = this.s;
    const p = s.project;
    const presets: [string, string][] = [
      ["1080x1920", "9:16 Shorts / Reels"],
      ["1920x1080", "16:9 YouTube"],
      ["1080x1080", "1:1 Square"],
      ["1080x1350", "4:5 Portrait"],
      ["2160x3840", "9:16 4K"],
      ["3840x2160", "16:9 4K"],
      ["1440x1080", "4:3"],
      ["2560x1080", "21:9 Cinema"],
    ];
    const body = h(
      "div",
      { class: "stack" },
      select("Size", presets.map(([v, l]) => [v, `${l} (${v})`] as [string, string]).concat(presets.some(([v]) => v === `${p.width}x${p.height}`) ? [] : [[`${p.width}x${p.height}`, `Custom ${p.width}x${p.height}`]]), `${p.width}x${p.height}`, (v) => {
        const [w, hh] = v.split("x").map(Number) as [number, number];
        s.mutate("canvas size", (pp) => {
          pp.width = w;
          pp.height = hh;
        });
        s.emit("project");
      }),
      select("Frame rate", ["24", "25", "30", "50", "60"] as const, String(p.fps) as "30", (v) => s.mutate("fps", (pp) => (pp.fps = Number(v)))),
      colorInput("Background", p.background, (v) => s.mutate("background", (pp) => (pp.background = v))),
    );
    sheet("Canvas", body);
  }
}
