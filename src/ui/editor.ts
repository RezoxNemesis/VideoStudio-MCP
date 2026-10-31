/** Editor view: preview, transport, timeline and a context toolbar (CapCut-style). */
import type { Studio } from "../app/studio";
import { findAsset, findClip, newTrack } from "../core/model";
import * as ops from "../core/ops";
import { valueAt } from "../core/keyframes";
import { createScene } from "../anim/ops";
import { Preview } from "./preview";
import { Timeline } from "./timeline";
import { openInspector } from "./inspector";
import { btn, fmtTime, h, icon, pickFiles, select, sheet, slider, toast, toolBtn, colorInput } from "./dom";

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
