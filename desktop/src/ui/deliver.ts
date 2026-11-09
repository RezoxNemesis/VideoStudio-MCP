/** Deliver: GPU/pro render settings with presets for Instagram, YouTube and masters, and live progress. */
import type { Studio } from "../../../src/app/studio";
import { runCommand } from "../../../src/agent/api";
import { toast } from "../../../src/ui/dom";
import { vsd, type EncCaps } from "../bridge";
import { b, field, h, numRow, selectEl, tc } from "./kit";

interface Preset {
  label: string;
  codec: string;
  height: number | "project";
  fps: number | "project";
  quality: number;
  note: string;
}

const PRESETS: Preset[] = [
  { label: "Instagram / Reels", codec: "h264", height: 1080, fps: 30, quality: 90, note: "H.264 high, AAC 320k, fast start — uploads without re-compression surprises." },
  { label: "YouTube 4K", codec: "hevc", height: 2160, fps: "project", quality: 92, note: "HEVC 10-bit, 4K. YouTube keeps more detail from 4K uploads, even for 1080p viewers." },
  { label: "YouTube 1080p", codec: "h264", height: 1080, fps: "project", quality: 92, note: "H.264 at a high bitrate." },
  { label: "Master — ProRes 422 HQ", codec: "prores", height: "project", fps: "project", quality: 100, note: "10-bit 4:2:2 intermediate with PCM sound, for archiving or further grading." },
  { label: "Master — ProRes 4444", codec: "prores4444", height: "project", fps: "project", quality: 100, note: "Highest quality 4:4:4 master." },
  { label: "Small preview", codec: "h264", height: 720, fps: "project", quality: 60, note: "Quick, light file to check the edit." },
];

const CODECS: [string, string][] = [
  ["auto", "Auto (GPU when available)"],
  ["h264", "H.264 — plays everywhere"],
  ["hevc", "HEVC 10-bit — smaller, sharper"],
  ["nvenc_av1", "AV1 (RTX 40 series GPU)"],
  ["x264", "H.264 — CPU x264 (slowest, finest)"],
  ["x265", "HEVC 10-bit — CPU x265"],
  ["prores", "ProRes 422 HQ (.mov)"],
  ["prores4444", "ProRes 4444 (.mov)"],
  ["vp9", "VP9 (.webm)"],
];

export class Deliver {
  readonly el: HTMLElement;
  private caps: EncCaps | null = null;
  private codec = "auto";
  private height: string = "project";
  private fps: string = "project";
  private quality = 88;
  private range: "all" | "markers" = "all";
  private name = "";
  private progress: HTMLElement;
  private busy = false;

  constructor(private readonly s: Studio) {
    this.el = h("div", { class: "p-body", style: "max-width:760px;margin:0 auto;width:100%" });
    this.progress = h("div");
    void vsd?.enc.caps().then((c) => {
      this.caps = c;
      this.draw();
    });
    s.on("status", () => this.busy && (this.progress.textContent = s.status));
    this.draw();
  }

  draw(): void {
    const p = this.s.project;
    const c = this.caps;
    const gpu = c ? (c.nvenc_h264 ? `NVIDIA GPU encoder ready (H.264${c.nvenc_hevc ? ", HEVC 10-bit" : ""}${c.nvenc_av1 ? ", AV1" : ""})` : "No NVIDIA encoder found — rendering on the CPU (x264/x265)") : "Checking the GPU…";
    const name = h("input", { class: "inp", value: this.name || p.name, style: "width:100%" }) as HTMLInputElement;
    name.addEventListener("keydown", (e) => e.stopPropagation());
    name.addEventListener("input", () => (this.name = name.value));
    const presets = h(
      "div",
      { class: "preset-grid", style: "grid-template-columns:repeat(3,1fr)" },
      ...PRESETS.map((pr) => {
        const el = h("button", { class: "preset", type: "button" }, h("b", {}, pr.label), h("span", {}, pr.note));
        el.addEventListener("click", () => {
          this.codec = pr.codec;
          this.height = String(pr.height);
          this.fps = String(pr.fps);
          this.quality = pr.quality;
          this.draw();
        });
        return el;
      }),
    );
    const heights: [string, string][] = [["project", `Project (${p.width}×${p.height})`], ...[480, 720, 1080, 1440, 2160, 4320].map((hh) => [String(hh), `${Math.round((p.width / p.height) * hh / 2) * 2}×${hh}`] as [string, string])];
    const marks = [...p.markers].sort((a, b2) => a.t - b2.t);
    const inOut = marks.length >= 2 ? `${tc(marks[0]!.t, p.fps)} → ${tc(marks[marks.length - 1]!.t, p.fps)}` : "add two markers";
    this.el.replaceChildren(
      h("h2", { style: "margin:4px 0 2px;font-size:20px" }, "Render"),
      h("div", { class: "hint" }, `${p.name} · ${tc(this.s.duration, p.fps)} · ${p.width}×${p.height} @ ${p.fps} fps`),
      h("div", { class: "row", style: "margin:10px 0 14px" }, h("span", { class: `dot ${c?.nvenc_h264 ? "online" : c ? "connecting" : ""}` }), h("span", { class: "muted" }, gpu)),
      h("div", { class: "group-title" }, "Presets"),
      presets,
      h("div", { class: "group-title" }, "Settings"),
      h(
        "div",
        { class: "col", style: "gap:6px" },
        field("File name", name),
        field("Format", selectEl<string>(CODECS, this.codec, (v) => (this.codec = v))),
        field("Resolution", selectEl<string>(heights, this.height, (v) => (this.height = v))),
        field("Frame rate", selectEl<string>([["project", `Project (${p.fps})`], "24", "25", "30", "50", "60", "120"], this.fps, (v) => (this.fps = v))),
        numRow({ label: "Quality", min: 30, max: 100, step: 1, value: this.quality, fmt: (v) => `${Math.round(v)}`, onInput: (v) => (this.quality = v) }),
        field("Range", selectEl<"all" | "markers">([["all", "Whole edit"], ["markers", `Between first and last marker (${inOut})`]], this.range, (v) => (this.range = v))),
      ),
      h(
        "div",
        { class: "row", style: "margin-top:14px" },
        b(this.busy ? "Rendering…" : "Render", "export", () => void this.render(), "pri"),
        this.busy ? b("Cancel", "close", () => void runCommand(this.s, { op: "render.cancel" })) : null,
        h("span", { class: "sp" }),
        b("Open renders folder", "folder", async () => vsd && void vsd.openPath((await vsd.info()).out.Movies), "ghost"),
      ),
      this.progress,
    );
  }

  private async render() {
    if (this.busy) return;
    const p = this.s.project;
    const marks = [...p.markers].sort((a, b2) => a.t - b2.t);
    if (this.range === "markers" && marks.length < 2) return toast("Add two markers to set the range.", "error");
    this.busy = true;
    this.draw();
    this.progress.className = "hint";
    this.progress.style.marginTop = "12px";
    const cmd: Record<string, unknown> = { op: "render.start", codec: this.codec, quality: this.quality, name: this.name || p.name };
    if (this.height !== "project") cmd.height = Number(this.height);
    if (this.fps !== "project") cmd.fps = Number(this.fps);
    if (this.range === "markers") {
      cmd.from = marks[0]!.t;
      cmd.to = marks[marks.length - 1]!.t;
    }
    const r = await runCommand(this.s, cmd as never);
    this.busy = false;
    this.draw();
    if (r.ok) {
      const x = r.result as { path: string; bytes: number; renderSeconds: number; speed: string; codec: string };
      this.progress.replaceChildren(
        h("div", { class: "act", style: "grid-template-columns:1fr auto" }, h("div", {}, h("b", {}, x.path.split("/").pop() ?? ""), h("div", { class: "ops" }, `${x.codec} · ${(x.bytes / 1e6).toFixed(1)} MB · rendered in ${x.renderSeconds}s (${x.speed})`)), h("div", { class: "row" }, b("Play", "play", () => void vsd?.openPath(x.path), "sm"), b("Show", "folder", () => void vsd?.reveal(x.path), "sm"))),
      );
      toast("Render saved", "ok");
    } else {
      this.progress.textContent = r.error ?? "Render failed";
      toast(r.error ?? "Render failed", "error", 6000);
    }
  }
}
