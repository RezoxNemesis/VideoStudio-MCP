/** Program monitor: the engine's GPU preview with transport, timecode, frame presets and safe-area guides. */
import type { Studio } from "../../../src/app/studio";
import { Preview, type PreviewQuality } from "../../../src/ui/preview";
import { runCommand } from "../../../src/agent/api";
import { ASPECTS } from "./inspector";
import { ask, b, h, ic, selectEl, tc } from "./kit";
import { RATES, Transport } from "./transport";

export class Viewer {
  readonly el: HTMLElement;
  readonly preview: Preview;
  private tcEl: HTMLElement;
  private durEl: HTMLElement;
  private playBtn: HTMLButtonElement;
  private guides: HTMLElement;
  private stage: HTMLElement;
  showGuides = false;
  /** Professional player controls (jumps, shuttle, marks, loop). */
  readonly transport: Transport;

  constructor(private readonly s: Studio, title = "Viewer") {
    this.preview = new Preview(s);
    this.transport = new Transport(s);
    const tp = this.transport;
    this.guides = h("div", { style: "position:absolute;inset:0;pointer-events:none;overflow:hidden" });
    this.stage = h("div", { class: "vstage" }, this.preview.el, this.guides);
    this.tcEl = h("span", { class: "tc" }, "00:00:00:00");
    this.durEl = h("span", { class: "tc dur" }, "/ 00:00:00:00");
    this.playBtn = h("button", { class: "play", type: "button", title: "Play / pause (Space)" }, ic("play", 18)) as HTMLButtonElement;
    this.playBtn.addEventListener("click", () => tp.toggle());
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
    const loopBtn = b(null, "loop", () => tp.setLoop(!tp.loop), "sm", "Loop (the marked range, or the whole edit)");
    const txt = (label: string, run: () => void, title: string, cls = "sm ghost") => {
      const x = h("button", { class: `btn ${cls} vtxt`, type: "button", title }, label) as HTMLButtonElement;
      x.addEventListener("click", run);
      return x;
    };
    const rateSel = selectEl<string>(RATES.map((r) => [String(r), `${r}×`] as [string, string]), "1", (v) => tp.setRate(Number(v)));
    rateSel.classList.add("vq");
    rateSel.title = "Playback speed (other than 1× plays without sound). J / L shuttle up to 8× in both directions.";
    const speed = h("span", { class: "vspeed", title: "Current speed" }, "");
    const inBtn = txt("I", () => tp.markIn(), "Mark in at the playhead (I)", "sm");
    const outBtn = txt("O", () => tp.markOut(), "Mark out at the playhead (O)", "sm");
    const clrBtn = txt("✕", () => tp.clearMarks(), "Clear in / out marks (Alt+X)");
    // Scrub bar: the whole edit under the picture. Click or drag to move; the marked range and markers show on it.
    const fill = h("div", { class: "vscrub-fill" });
    const range = h("div", { class: "vscrub-range" });
    const marks = h("div", { class: "vscrub-marks" });
    const tip = h("div", { class: "vscrub-tip" }, "");
    const scrub = h("div", { class: "vscrub", title: "" }, range, fill, marks, tip);
    const at = (e: PointerEvent) => {
      const r = scrub.getBoundingClientRect();
      return Math.max(0, Math.min(1, (e.clientX - r.left) / Math.max(1, r.width))) * s.duration;
    };
    let scrubbing = false;
    scrub.addEventListener("pointerdown", (e) => {
      scrubbing = true;
      scrub.setPointerCapture(e.pointerId);
      tp.pause();
      tp.goto(at(e));
    });
    scrub.addEventListener("pointermove", (e) => {
      const t = at(e);
      tip.textContent = tc(t, s.project.fps);
      const r = scrub.getBoundingClientRect();
      tip.style.left = `${Math.max(34, Math.min(r.width - 34, e.clientX - r.left))}px`;
      if (scrubbing) tp.goto(t);
    });
    scrub.addEventListener("pointerup", () => (scrubbing = false));
    scrub.addEventListener("pointercancel", () => (scrubbing = false));
    const drawScrub = () => {
      const d = Math.max(0.001, s.duration);
      fill.style.width = `${Math.min(100, (s.time / d) * 100)}%`;
      const i = tp.inPoint;
      const o = tp.outPoint;
      if (i === null && o === null) range.style.display = "none";
      else {
        range.style.display = "";
        range.style.left = `${((i ?? 0) / d) * 100}%`;
        range.style.width = `${Math.max(0.2, (((o ?? d) - (i ?? 0)) / d) * 100)}%`;
      }
      inBtn.classList.toggle("on", i !== null);
      outBtn.classList.toggle("on", o !== null);
      loopBtn.classList.toggle("on", tp.loop);
      speed.textContent = tp.shuttle || tp.rate !== 1 ? tp.speedLabel : "";
      this.playBtn.replaceChildren(ic(tp.moving ? "pause" : "play", 18));
    };
    const drawMarks = () => {
      const d = Math.max(0.001, s.duration);
      marks.replaceChildren(...(s.project.markers ?? []).map((m) => h("i", { style: `left:${(m.t / d) * 100}%`, title: m.label || "Marker" })));
    };
    tp.on(drawScrub);
    // Click the timecode to type a time (seconds, or mm:ss, or hh:mm:ss:ff).
    this.tcEl.title = "Click to type a time";
    this.tcEl.style.cursor = "text";
    this.tcEl.addEventListener("click", async () => {
      const v = await ask("Go to time", "Seconds, mm:ss or hh:mm:ss:ff", tc(s.time, s.project.fps));
      if (v === null) return;
      const parts = v.trim().split(":").map(Number);
      if (parts.some((n) => !Number.isFinite(n))) return;
      const fps = s.project.fps;
      const t = parts.length === 1 ? parts[0]! : parts.length === 2 ? parts[0]! * 60 + parts[1]! : parts.length === 3 ? parts[0]! * 3600 + parts[1]! * 60 + parts[2]! : parts[0]! * 3600 + parts[1]! * 60 + parts[2]! + parts[3]! / fps;
      tp.goto(t);
    });
    const bar = h(
      "div",
      { class: "vbar" },
      this.tcEl,
      this.durEl,
      speed,
      h("span", { class: "sp" }),
      b(null, "start", () => tp.goto(tp.inPoint ?? 0), "sm ghost", "Go to start / in point (Home)"),
      txt("−10s", () => tp.jump(-10), "Back 10 seconds (Page Up)"),
      b(null, "stepB", () => tp.step(-1), "sm ghost", "Previous frame (←)"),
      this.playBtn,
      b(null, "stepF", () => tp.step(1), "sm ghost", "Next frame (→)"),
      txt("+10s", () => tp.jump(10), "Forward 10 seconds (Page Down)"),
      b(null, "end", () => tp.goto(tp.outPoint ?? s.duration), "sm ghost", "Go to end / out point (End)"),
      h("span", { class: "sp" }),
      inBtn,
      outBtn,
      clrBtn,
      loopBtn,
      rateSel,
      guideBtn,
      zoomBtn,
      qualitySel,
      aspectSel,
      txt("Snap", () => void runCommand(s, { op: "render.still" }).then((r) => r.ok || console.warn(r.error)), "Save this frame as a picture", "sm"),
      b(null, "fit", () => void (window as unknown as { vsdFullscreen?: () => void }).vsdFullscreen?.(), "sm", "Full screen (F11)"),
    );
    const head = h("div", { class: "p-head" }, h("span", {}, title), h("span", { class: "sp" }), h("span", { class: "mono", style: "text-transform:none;letter-spacing:0;font-weight:450" }));
    const info = head.lastChild as HTMLElement;
    this.el = h("section", { class: "p view" }, head, h("div", { class: "p-body" }, this.stage, scrub, bar));
    const upd = () => {
      this.tcEl.textContent = tc(s.time, s.project.fps);
      this.durEl.textContent = `/ ${tc(s.duration, s.project.fps)}`;
    };
    s.on("time", () => {
      upd();
      drawScrub();
    });
    s.on("change", () => {
      upd();
      drawScrub();
      drawMarks();
    });
    s.on("play", drawScrub);
    const proj = () => {
      upd();
      drawScrub();
      drawMarks();
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
