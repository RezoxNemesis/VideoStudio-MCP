/** Render panel: resolution, frame rate, quality, codec; renders into Movies/VideoStudio. */
import type { Studio } from "../app/studio";
import { ExportJob, type ExportOptions } from "../render/export";
import { Share } from "@capacitor/share";
import { Capacitor } from "@capacitor/core";
import { btn, fmtBytes, fmtTime, h, select, toast } from "./dom";

export class ExportView {
  readonly el: HTMLElement;
  private body: HTMLElement;
  private job: ExportJob | null = null;
  private opts: ExportOptions = { quality: "very-high", codec: "avc", audio: true };
  private height = "project";
  private fps = "project";

  constructor(private readonly s: Studio) {
    this.body = h("div", { class: "panel" });
    this.el = h("div", { class: "view export" }, this.body);
    s.on("project", () => this.draw());
    this.draw();
  }

  draw(): void {
    const p = this.s.project;
    const heights: [string, string][] = [["project", `Project (${p.width}×${p.height})`], ["720", "720p"], ["1080", "1080p"], ["1440", "1440p (2K)"], ["2160", "2160p (4K)"]];
    this.body.replaceChildren(
      h("h2", {}, "Render"),
      h("p", { class: "muted" }, `${p.name} · ${fmtTime(this.s.duration, p.fps)} · ${p.width}×${p.height} @ ${p.fps}fps`),
      select("Resolution", heights, this.height as "project", (v) => (this.height = v)),
      select("Frame rate", [["project", `Project (${p.fps})`], ["24", "24"], ["25", "25"], ["30", "30"], ["60", "60"]] as const, this.fps as "project", (v) => (this.fps = v)),
      select("Quality", [["very-high", "Best (large file)"], ["high", "High"], ["medium", "Medium"], ["low", "Small"]] as const, this.opts.quality ?? "very-high", (v) => (this.opts.quality = v)),
      select("Codec", [["avc", "H.264 (plays everywhere)"], ["hevc", "HEVC / H.265 (smaller, if supported)"]] as const, (this.opts.codec ?? "avc") as "avc", (v) => (this.opts.codec = v)),
      h("div", { class: "row wrap" }, btn("Render video", "export", () => void this.run(), "primary"), btn("Cancel", "close", () => this.job?.cancel(), "ghost")),
      h("div", { class: "progress big" }, h("i", {})),
      h("p", { class: "render-status muted" }, "Hardware encoding through the phone's video encoder. The file goes to Movies/VideoStudio."),
      h("div", { class: "render-result" }),
    );
  }

  private async run() {
    if (this.job) return;
    if (this.s.duration <= 0) return toast("The timeline is empty", "error");
    this.s.pause();
    const bar = this.body.querySelector<HTMLElement>(".progress i")!;
    const status = this.body.querySelector<HTMLElement>(".render-status")!;
    const out = this.body.querySelector<HTMLElement>(".render-result")!;
    const job = new ExportJob(this.s.project, this.s.pool, {
      ...this.opts,
      height: this.height === "project" ? undefined : Number(this.height),
      fps: this.fps === "project" ? undefined : Number(this.fps),
    });
    this.job = job;
    job.onProgress = (pr) => {
      bar.style.width = `${Math.round((pr.frame / Math.max(1, pr.frames)) * 100)}%`;
      status.textContent =
        pr.stage === "audio" ? "Mixing audio…" : pr.stage === "finalizing" ? "Finishing the file…" : pr.stage === "done" ? "Done" : `Frame ${pr.frame}/${pr.frames} · ${pr.fps.toFixed(1)} fps · ~${Math.ceil(pr.etaSec)}s left`;
    };
    try {
      const r = await job.run();
      status.textContent = `Saved in ${r.renderSeconds.toFixed(1)}s (${(r.seconds / r.renderSeconds).toFixed(2)}× real time)`;
      out.replaceChildren(
        h("p", {}, h("b", {}, r.name)),
        h("p", { class: "muted small" }, `${r.width}×${r.height} · ${r.fps}fps · ${r.codec.toUpperCase()}${r.audioCodec ? ` + ${r.audioCodec.toUpperCase()}` : " · no audio"} · ${fmtBytes(r.bytes)} · ${r.path}`),
        ...(Capacitor.isNativePlatform() && r.uri.startsWith("content:") ? [btn("Share", "share", () => void Share.share({ title: r.name, url: r.uri }).catch(() => {}), "primary")] : []),
      );
      for (const w of r.warnings) toast(w, "error", 6000);
      toast("Video saved to Movies/VideoStudio", "ok", 4000);
    } catch (e) {
      status.textContent = e instanceof Error ? e.message : String(e);
      toast(status.textContent, "error", 6000);
    } finally {
      this.job = null;
    }
  }
}
