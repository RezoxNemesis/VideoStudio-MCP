/** Program monitor: the engine's GPU preview with transport, timecode, frame presets and safe-area guides. */
import type { Studio } from "../../../src/app/studio";
import { Preview, type PreviewQuality } from "../../../src/ui/preview";
import { runCommand } from "../../../src/agent/api";
import { ASPECTS } from "./inspector";
import { b, h, ic, selectEl, tc } from "./kit";

export class Viewer {
  readonly el: HTMLElement;
  readonly preview: Preview;
  private tcEl: HTMLElement;
  private durEl: HTMLElement;
  private playBtn: HTMLButtonElement;
  private guides: HTMLElement;
  private stage: HTMLElement;
  showGuides = false;
  loop = false;

  constructor(private readonly s: Studio, title = "Viewer") {
    this.preview = new Preview(s);
    this.guides = h("div", { style: "position:absolute;inset:0;pointer-events:none;overflow:hidden" });
    this.stage = h("div", { class: "vstage" }, this.preview.el, this.guides);
    this.tcEl = h("span", { class: "tc" }, "00:00:00:00");
    this.durEl = h("span", { class: "tc dur" }, "/ 00:00:00:00");
    this.playBtn = h("button", { class: "play", type: "button", title: "Play / pause (Space)" }, ic("play", 18)) as HTMLButtonElement;
    this.playBtn.addEventListener("click", () => this.s.toggle());
    const frame = () => 1 / this.s.project.fps;
    const aspect = () => {
      const p = s.project;
      const cur = ASPECTS.find(([, w, hh]) => w === p.width && hh === p.height)?.[0] ?? "";
      return selectEl<string>([["", `${p.width}×${p.height}`], ...ASPECTS.map(([l]) => l)], cur, (v) => {
        const a = ASPECTS.find(([l]) => l === v);
        if (a) void runCommand(s, { op: "project.set", width: a[1], height: a[2] });
      });
    };
    let aspectSel = aspect();
    // Zoom: wheel on the picture zooms at the pointer; this shows the level and returns to fit.
    const zoomBtn = h("button", { class: "btn sm vzoom", type: "button", title: "Zoom (wheel on the picture; middle-drag to pan). Click to fit." }, "Fit") as HTMLButtonElement;
    zoomBtn.addEventListener("click", () => this.preview.resetView());
    this.preview.onView = () => {
      const z = this.preview.zoomLevel;
      zoomBtn.textContent = z <= 1.001 ? "Fit" : `${Math.round(z * 100)}%`;
      zoomBtn.classList.toggle("on", z > 1.001);
      this.drawGuides();
    };
    const qualitySel = selectEl<PreviewQuality>(
      [
        ["auto", "Auto"],
        ["full", "Full"],
        ["half", "1/2"],
        ["quarter", "1/4"],
      ],
      this.preview.quality,
      (v) => this.preview.setQuality(v),
    );
    qualitySel.classList.add("vq");
    qualitySel.title = "Preview quality. Lower it if playback stutters; renders always use full quality.";
    const guideBtn = b(null, "grid", () => {
      this.showGuides = !this.showGuides;
      guideBtn.classList.toggle("on", this.showGuides);
      this.drawGuides();
    }, "sm", "Safe-area guides");
    const loopBtn = b(null, "loop", () => {
      this.loop = !this.loop;
      loopBtn.classList.toggle("on", this.loop);
    }, "sm", "Loop playback");
    const bar = h(
      "div",
      { class: "vbar" },
      this.tcEl,
      this.durEl,
      h("span", { class: "sp" }),
      b(null, "start", () => s.seek(0), "sm ghost", "Go to start (Home)"),
      b(null, "stepB", () => s.seek(Math.max(0, s.time - frame())), "sm ghost", "Previous frame (←)"),
      this.playBtn,
      b(null, "stepF", () => s.seek(s.time + frame()), "sm ghost", "Next frame (→)"),
      b(null, "end", () => s.seek(s.duration), "sm ghost", "Go to end (End)"),
      h("span", { class: "sp" }),
      loopBtn,
      guideBtn,
      zoomBtn,
      qualitySel,
      aspectSel,
      b(null, "fit", () => void (window as unknown as { vsdFullscreen?: () => void }).vsdFullscreen?.(), "sm", "Full screen (F11)"),
    );
    const head = h("div", { class: "p-head" }, h("span", {}, title), h("span", { class: "sp" }), h("span", { class: "mono", style: "text-transform:none;letter-spacing:0;font-weight:450" }));
    const info = head.lastChild as HTMLElement;
    this.el = h("section", { class: "p view" }, head, h("div", { class: "p-body" }, this.stage, bar));
    const upd = () => {
      this.tcEl.textContent = tc(s.time, s.project.fps);
      this.durEl.textContent = `/ ${tc(s.duration, s.project.fps)}`;
    };
    s.on("time", () => {
      upd();
      if (this.loop && s.playing && s.time >= s.duration - 0.02) {
        s.pause();
        s.seek(0);
        void s.play();
      }
    });
    s.on("change", upd);
    s.on("play", () => this.playBtn.replaceChildren(ic(s.playing ? "pause" : "play", 18)));
    const proj = () => {
      upd();
      info.textContent = `${s.project.width}×${s.project.height} · ${s.project.fps} fps`;
      const n = aspect();
      aspectSel.replaceWith(n);
      aspectSel = n;
      this.drawGuides();
    };
    s.on("project", proj);
    s.on("change", () => {
      const t = `${s.project.width}×${s.project.height} · ${s.project.fps} fps`;
      if (info.textContent !== t) proj();
    });
    new ResizeObserver(() => this.drawGuides()).observe(this.stage);
    proj();
  }

  private drawGuides() {
    this.guides.replaceChildren();
    if (!this.showGuides) return;
    // The frame as the preview shows it now (follows zoom and pan); the preview sits 10px inside the stage.
    const v = this.preview.viewRect();
    const w = v.w;
    const hh = v.h;
    const x = 10 + v.x;
    const y = 10 + v.y;
    for (const f of [0.9, 0.8]) {
      const g = h("div", { class: "vguide" });
      Object.assign(g.style, { left: `${x + (w * (1 - f)) / 2}px`, top: `${y + (hh * (1 - f)) / 2}px`, width: `${w * f}px`, height: `${hh * f}px` });
      this.guides.append(g);
    }
    for (const [fx, fy] of [
      [1 / 3, 0],
      [2 / 3, 0],
      [0, 1 / 3],
      [0, 2 / 3],
    ]) {
      const g = h("div", { class: "vguide", style: "border-style:solid;border-color:rgba(255,255,255,0.12)" });
      if (fx) Object.assign(g.style, { left: `${x + w * fx}px`, top: `${y}px`, width: "0px", height: `${hh}px` });
      else Object.assign(g.style, { left: `${x}px`, top: `${y + hh * (fy ?? 0)}px`, width: `${w}px`, height: "0px" });
      this.guides.append(g);
    }
  }
}
