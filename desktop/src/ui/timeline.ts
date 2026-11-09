/**
 * Desktop timeline: canvas-drawn multi-track editor. Ruler + scrubbing, zoom around the cursor (Ctrl+wheel),
 * thumbnails on video clips, waveforms on audio, keyframe and transition marks, markers, snapping, move / trim /
 * cross-track drag, a blade tool, context menus and drag-in from the library.
 */
import type { Studio } from "../../../src/app/studio";
import { findAsset, newTrack, type Clip, type Track } from "../../../src/core/model";
import { isAnimated } from "../../../src/core/keyframes";
import * as ops from "../../../src/core/ops";
import { assetThumb } from "../../../src/render/snapshot";
import { toast } from "../../../src/ui/dom";
import { b, h, ic, menu, tc, ask } from "./kit";

const RULER = 28;
const HEAD = 168;
const ROW_V = 62;
const ROW_A = 50;
const EDGE = 7;

type Drag =
  | { kind: "move"; id: string; dx: number; t0: number; trackId: string; origStart: number; ghostStart: number; ghostTrack: string; moved: boolean }
  | { kind: "trim"; id: string; edge: "start" | "end"; ghostT: number; moved: boolean }
  | { kind: "scrub" }
  | { kind: "pan"; x0: number; s0: number };

export class DesktopTimeline {
  readonly el: HTMLElement;
  readonly toolbar: HTMLElement;
  private canvas: HTMLCanvasElement;
  private rulerCanvas: HTMLCanvasElement;
  private wrap: HTMLElement;
  private heads: HTMLElement;
  private headsIn: HTMLElement;
  private rulerEl: HTMLElement;
  private hscroll: HTMLElement;
  private hscrollIn: HTMLElement;
  pps = 80;
  private sx = 0; // horizontal scroll, px
  private sy = 0; // vertical scroll, px
  private drag: Drag | null = null;
  private snapLine: number | null = null;
  snap = true;
  tool: "select" | "blade" = "select";
  private hover: { x: number; y: number } | null = null;
  private thumbs = new Map<string, HTMLImageElement | "loading" | "fail">();
  private thumbQueue: { key: string; assetId: string; t: number }[] = [];
  private thumbBusy = 0;
  private raf = 0;
  private zoomInput: HTMLInputElement;
  private toolBtns: Record<string, HTMLButtonElement> = {};
  private snapBtn: HTMLButtonElement;

  constructor(private readonly s: Studio) {
    this.canvas = h("canvas") as HTMLCanvasElement;
    this.rulerCanvas = h("canvas") as HTMLCanvasElement;
    this.wrap = h("div", { class: "tl-canvas-wrap" }, this.canvas);
    this.headsIn = h("div", { class: "tl-heads-in" });
    this.heads = h("div", { class: "tl-heads" }, this.headsIn);
    this.rulerEl = h("div", { class: "tl-ruler" }, this.rulerCanvas);
    this.hscrollIn = h("div");
    this.hscroll = h("div", { class: "tl-hscroll" }, this.hscrollIn);
    const corner = h("div", { class: "tl-corner" }, b(null, "plus", (e) => this.addTrackMenu(e), "sm ghost", "Add track"), h("span", { class: "muted", style: "font-size:11px" }, "Tracks"));
    this.el = h("div", { class: "tl" }, corner, this.rulerEl, this.heads, this.wrap, this.hscroll);

    this.zoomInput = h("input", { type: "range", min: "4", max: "600", step: "1", class: "zoom", title: "Zoom (Ctrl + wheel)" }) as HTMLInputElement;
    this.zoomInput.value = String(this.pps);
    this.zoomInput.addEventListener("input", () => this.setZoom(Number(this.zoomInput.value), this.wrap.clientWidth / 2));
    this.toolBtns.select = b(null, "move", () => this.setTool("select"), "sm on", "Select tool (V)");
    this.toolBtns.blade = b(null, "scissors", () => this.setTool("blade"), "sm", "Blade tool (B)");
    this.snapBtn = b(null, "magnet", () => {
      this.snap = !this.snap;
      this.snapBtn.classList.toggle("on", this.snap);
    }, "sm on", "Snapping (N)");
    this.toolbar = h(
      "div",
      { class: "row" },
      this.toolBtns.select,
      this.toolBtns.blade,
      this.snapBtn,
      h("span", { style: "width:8px" }),
      b(null, "split", () => this.splitAtPlayhead(), "sm", "Split at playhead (S)"),
      b(null, "trash", () => this.deleteSelected(false), "sm", "Delete (Del) — Shift+Del ripple"),
      b(null, "copy", () => this.duplicateSelected(), "sm", "Duplicate (Ctrl+D)"),
      b(null, "marker", () => this.addMarker(), "sm", "Add marker (M)"),
      h("span", { style: "width:8px" }),
      b(null, "fit", () => this.fit(), "sm", "Fit the whole edit (Shift+Z)"),
      ic("zoomIn", 14),
      this.zoomInput,
    );

    new ResizeObserver(() => this.resize()).observe(this.wrap);
    s.on("change", () => this.redraw());
    s.on("select", () => this.redraw());
    s.on("project", () => {
      this.thumbs.clear();
      this.redraw();
    });
    s.on("time", () => this.follow());
    this.events();
  }

  /* ------------------------------------------------------------ geometry */

  private rows(): { track: Track; y: number; h: number }[] {
    let y = 0;
    return this.s.project.tracks.map((track) => {
      const hh = track.kind === "audio" ? ROW_A : ROW_V;
      const r = { track, y, h: hh };
      y += hh;
      return r;
    });
  }
  private contentHeight() {
    return this.rows().reduce((a, r) => a + r.h, 0) + 40;
  }
  private tx(t: number) {
    return t * this.pps - this.sx;
  }
  private xt(x: number) {
    return (x + this.sx) / this.pps;
  }
  private totalWidth() {
    return Math.max(this.wrap.clientWidth, (this.s.duration + 30) * this.pps);
  }

  setZoom(pps: number, anchorX: number): void {
    const t = this.xt(anchorX);
    this.pps = Math.max(4, Math.min(600, pps));
    this.zoomInput.value = String(Math.round(this.pps));
    this.sx = Math.max(0, t * this.pps - anchorX);
    this.syncScroll();
    this.redraw();
  }

  fit(): void {
    const w = this.wrap.clientWidth - 40;
    const d = Math.max(5, this.s.duration);
    this.pps = Math.max(4, Math.min(600, w / d));
    this.zoomInput.value = String(Math.round(this.pps));
    this.sx = 0;
    this.syncScroll();
    this.redraw();
  }

  setTool(t: "select" | "blade"): void {
    this.tool = t;
    this.toolBtns.select!.classList.toggle("on", t === "select");
    this.toolBtns.blade!.classList.toggle("on", t === "blade");
    this.wrap.style.cursor = t === "blade" ? "crosshair" : "";
  }

  private syncScroll() {
    this.hscrollIn.style.width = `${this.totalWidth()}px`;
    if (Math.abs(this.hscroll.scrollLeft - this.sx) > 1) this.hscroll.scrollLeft = this.sx;
  }

  private follow() {
    // Keep the playhead in view while playing.
    const x = this.tx(this.s.time);
    const w = this.wrap.clientWidth;
    if (this.s.playing && (x > w - 60 || x < 0)) {
      this.sx = Math.max(0, this.s.time * this.pps - 80);
      this.syncScroll();
    }
    this.redraw();
  }

  private resize() {
    const dpr = devicePixelRatio || 1;
    for (const [c, el] of [
      [this.canvas, this.wrap],
      [this.rulerCanvas, this.rulerEl],
    ] as const) {
      const w = el.clientWidth;
      const hh = el.clientHeight;
      c.width = Math.max(1, Math.round(w * dpr));
      c.height = Math.max(1, Math.round(hh * dpr));
      c.style.width = `${w}px`;
      c.style.height = `${hh}px`;
    }
    this.syncScroll();
    this.redraw();
  }

  redraw(): void {
    if (this.raf) return;
    this.raf = requestAnimationFrame(() => {
      this.raf = 0;
      this.draw();
    });
  }

  /* ------------------------------------------------------------ drawing */

  private draw() {
    const p = this.s.project;
    const dpr = devicePixelRatio || 1;
    this.drawHeads();
    this.drawRuler();
    const g = this.canvas.getContext("2d")!;
    const W = this.canvas.width / dpr;
    const H = this.canvas.height / dpr;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, W, H);
    const maxSy = Math.max(0, this.contentHeight() - H);
    this.sy = Math.min(this.sy, maxSy);

    // Row backgrounds and grid.
    const rows = this.rows();
    for (const r of rows) {
      const y = r.y - this.sy;
      if (y > H || y + r.h < 0) continue;
      g.fillStyle = r.track.kind === "audio" ? "rgba(47,179,106,0.025)" : "rgba(255,255,255,0.012)";
      g.fillRect(0, y, W, r.h);
      g.fillStyle = "rgba(255,255,255,0.04)";
      g.fillRect(0, y + r.h - 1, W, 1);
    }
    const step = this.tickStep();
    g.fillStyle = "rgba(255,255,255,0.028)";
    for (let t = Math.floor(this.xt(0) / step) * step; this.tx(t) < W; t += step) g.fillRect(Math.round(this.tx(t)), 0, 1, H);
    // End of edit.
    const endX = this.tx(this.s.duration);
    if (endX < W) {
      g.fillStyle = "rgba(0,0,0,0.25)";
      g.fillRect(Math.max(0, endX), 0, W, H);
    }
    // Markers.
    for (const m of p.markers) {
      const x = this.tx(m.t);
      if (x < -2 || x > W) continue;
      g.fillStyle = "rgba(255,191,94,0.28)";
      g.fillRect(Math.round(x), 0, 1, H);
    }

    // Clips.
    const sel = this.s.selection;
    for (const r of rows) {
      const y0 = r.y - this.sy;
      if (y0 > H || y0 + r.h < 0) continue;
      for (const c of r.track.clips) {
        let start = c.start;
        let dur = c.dur;
        let yy = y0;
        const d = this.drag;
        if (d && d.kind === "move" && d.id === c.id && d.moved) {
          start = d.ghostStart;
          const gr = rows.find((q) => q.track.id === d.ghostTrack);
          if (gr) yy = gr.y - this.sy;
        }
        if (d && d.kind === "trim" && d.id === c.id && d.moved) {
          if (d.edge === "start") {
            dur = c.start + c.dur - d.ghostT;
            start = d.ghostT;
          } else dur = d.ghostT - c.start;
        }
        const x = this.tx(start);
        const w = dur * this.pps;
        if (x > W || x + w < 0) continue;
        this.drawClip(g, c, r.track, x, yy + 3, Math.max(2, w), (rows.find((q) => q.track.id === (d && d.kind === "move" && d.id === c.id && d.moved ? d.ghostTrack : r.track.id))?.h ?? r.h) - 6, c.id === sel, r.track);
      }
    }

    // Snap guide.
    if (this.snapLine !== null) {
      const x = this.tx(this.snapLine);
      g.fillStyle = "#ffbf5e";
      g.fillRect(Math.round(x), 0, 1, H);
    }
    // Blade preview.
    if (this.tool === "blade" && this.hover) {
      g.fillStyle = "rgba(255,111,174,0.9)";
      g.fillRect(Math.round(this.hover.x), 0, 1, H);
    }
    // Playhead.
    const px = this.tx(this.s.time);
    if (px >= -2 && px <= W + 2) {
      g.fillStyle = "#3ee6c4";
      g.shadowColor = "rgba(62,230,196,0.8)";
      g.shadowBlur = 8;
      g.fillRect(Math.round(px), 0, 1.5, H);
      g.shadowBlur = 0;
    }
    if (!p.tracks.some((t) => t.clips.length)) {
      g.fillStyle = "rgba(255,255,255,0.32)";
      g.font = "13px Geist Variable, system-ui";
      g.textAlign = "center";
      g.fillText("Drag media here from the library, or press Import", W / 2, Math.min(H / 2, 90));
      g.textAlign = "left";
    }
  }

  private color(c: Clip, track: Track): [string, string] {
    const a = findAsset(this.s.project, c.assetId);
    if (track.kind === "audio" || a?.kind === "audio") return ["#1d7a4b", "#2fb36a"];
    if (c.kind === "text") return ["#7a2f69", "#c050a6"];
    if (c.kind === "anim") return ["#8a5419", "#d5832a"];
    if (c.kind === "adjust" || c.kind === "color" || c.kind === "shape") return ["#4a3f99", "#7a6be6"];
    if (a?.kind === "image") return ["#146d63", "#1fa596"];
    return ["#24499c", "#3a6fe8"];
  }

  private drawClip(g: CanvasRenderingContext2D, c: Clip, track: Track, x: number, y: number, w: number, hh: number, selected: boolean, _t: Track) {
    const [dark, light] = this.color(c, track);
    const r = 6;
    g.save();
    g.beginPath();
    g.roundRect(x + 0.5, y + 0.5, w - 1, hh - 1, r);
    const grad = g.createLinearGradient(0, y, 0, y + hh);
    grad.addColorStop(0, light + "e6");
    grad.addColorStop(1, dark + "e6");
    g.fillStyle = grad;
    g.globalAlpha = track.hidden || track.muted ? 0.4 : 1;
    g.fill();
    g.clip();
    const a = findAsset(this.s.project, c.assetId);

    // Thumbnails (video / image clips).
    if (a && (a.kind === "video" || a.kind === "image") && hh > 30 && w > 24) {
      const th = hh - 16;
      const tw = Math.max(20, Math.round((th * (a.width || 16)) / (a.height || 9)));
      const y1 = y + 14;
      for (let xx = Math.max(x, x + Math.floor((0 - x) / tw) * tw); xx < x + w && xx < this.canvas.width; xx += tw) {
        if (xx + tw < 0) continue;
        const local = (xx - x) / this.pps;
        const src = a.kind === "image" ? 0 : Math.max(0, Math.min(a.duration - 0.05, c.in + local * (c.speed || 1)));
        const img = this.thumb(a.id, a.kind === "image" ? 0 : Math.round(src * 2) / 2);
        if (img) {
          g.globalAlpha = 0.9;
          g.drawImage(img, xx, y1, tw, th);
        }
      }
      g.globalAlpha = 1;
    }
    // Waveform.
    if (a?.peaks?.length && (track.kind === "audio" || hh <= 52)) {
      const pk = a.peaks;
      const perSec = pk.length / Math.max(0.01, a.duration);
      const mid = y + hh / 2 + 5;
      const amp = (hh - 18) / 2;
      g.fillStyle = "rgba(220,255,240,0.55)";
      const x0 = Math.max(x, 0);
      const x1 = Math.min(x + w, this.canvas.width);
      for (let xx = x0; xx < x1; xx += 2) {
        const src = c.in + ((xx - x) / this.pps) * (c.speed || 1);
        const v = pk[Math.min(pk.length - 1, Math.max(0, Math.floor(src * perSec)))] ?? 0;
        const hv = Math.max(0.5, v * amp);
        g.fillRect(xx, mid - hv, 1.4, hv * 2);
      }
    }
    // Fades.
    g.fillStyle = "rgba(0,0,0,0.35)";
    if (c.fadeIn > 0) {
      g.beginPath();
      g.moveTo(x, y);
      g.lineTo(x + c.fadeIn * this.pps, y);
      g.lineTo(x, y + hh);
      g.fill();
    }
    if (c.fadeOut > 0) {
      g.beginPath();
      g.moveTo(x + w, y);
      g.lineTo(x + w - c.fadeOut * this.pps, y);
      g.lineTo(x + w, y + hh);
      g.fill();
    }
    // Transitions.
    for (const [tr, atStart] of [
      [c.transitionIn, true],
      [c.transitionOut, false],
    ] as const) {
      if (!tr || tr.kind === "none" || !(tr.dur > 0)) continue;
      const tw = tr.dur * this.pps;
      g.fillStyle = "rgba(138,125,255,0.45)";
      g.fillRect(atStart ? x : x + w - tw, y, tw, 4);
    }
    // Title bar.
    g.fillStyle = "rgba(0,0,0,0.28)";
    g.fillRect(x, y, w, 14);
    g.fillStyle = "#fff";
    g.font = "500 11px Geist Variable, system-ui";
    const label = `${c.name}${c.speed && c.speed !== 1 ? `  ${Math.round(c.speed * 100)}%` : ""}${c.reverse ? "  ⟲" : ""}`;
    if (w > 30) g.fillText(label, Math.max(x, 0) + 6, y + 11, Math.max(10, w - 12));
    // Keyframes.
    const keyTimes = new Set<number>();
    const groups = [c.transform, c.grade, c.fx ?? {}] as Record<string, unknown>[];
    for (const grp of groups) for (const v of Object.values(grp)) if (isAnimated(v as never)) for (const k of v as { t: number }[]) keyTimes.add(Math.round(k.t * 1000) / 1000);
    if (isAnimated(c.volume)) for (const k of c.volume) keyTimes.add(Math.round(k.t * 1000) / 1000);
    g.fillStyle = "#e8e4ff";
    for (const t of keyTimes) {
      const kx = x + t * this.pps;
      const ky = y + hh - 6;
      g.beginPath();
      g.moveTo(kx, ky - 3.5);
      g.lineTo(kx + 3.5, ky);
      g.lineTo(kx, ky + 3.5);
      g.lineTo(kx - 3.5, ky);
      g.fill();
    }
    g.restore();
    // Selection.
    if (selected) {
      g.save();
      g.strokeStyle = "#3ee6c4";
      g.lineWidth = 2;
      g.shadowColor = "rgba(62,230,196,0.7)";
      g.shadowBlur = 10;
      g.beginPath();
      g.roundRect(x + 1, y + 1, w - 2, hh - 2, r);
      g.stroke();
      g.restore();
      // Trim handles.
      g.fillStyle = "#3ee6c4";
      g.fillRect(x + 1, y + hh / 2 - 9, 3, 18);
      g.fillRect(x + w - 4, y + hh / 2 - 9, 3, 18);
    } else {
      g.strokeStyle = "rgba(0,0,0,0.4)";
      g.lineWidth = 1;
      g.beginPath();
      g.roundRect(x + 0.5, y + 0.5, w - 1, hh - 1, r);
      g.stroke();
    }
  }

  private thumb(assetId: string, t: number): HTMLImageElement | null {
    const key = `${assetId}@${t}`;
    const v = this.thumbs.get(key);
    if (v instanceof HTMLImageElement) return v;
    if (!v) {
      this.thumbs.set(key, "loading");
      this.thumbQueue.push({ key, assetId, t });
      this.pumpThumbs();
    }
    return null;
  }

  private pumpThumbs() {
    while (this.thumbBusy < 2 && this.thumbQueue.length) {
      const job = this.thumbQueue.pop()!;
      this.thumbBusy++;
      void assetThumb(this.s.project, this.s.pool, job.assetId, job.t, 160)
        .then((r) => {
          const img = new Image();
          img.onload = () => this.redraw();
          img.src = r.jpeg;
          this.thumbs.set(job.key, img);
        })
        .catch(() => this.thumbs.set(job.key, "fail"))
        .finally(() => {
          this.thumbBusy--;
          this.pumpThumbs();
        });
    }
  }

  private tickStep(): number {
    const steps = [1 / 30, 1 / 10, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600];
    for (const st of steps) if (st * this.pps >= 70) return st;
    return 1200;
  }

  private drawRuler() {
    const dpr = devicePixelRatio || 1;
    const g = this.rulerCanvas.getContext("2d")!;
    const W = this.rulerCanvas.width / dpr;
    const H = this.rulerCanvas.height / dpr;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, W, H);
    const step = this.tickStep();
    const fps = this.s.project.fps;
    g.font = "10.5px JetBrains Mono, ui-monospace, monospace";
    for (let t = Math.floor(this.xt(0) / step) * step; this.tx(t) < W; t += step) {
      const x = Math.round(this.tx(t)) + 0.5;
      g.fillStyle = "rgba(255,255,255,0.28)";
      g.fillRect(x, H - 10, 1, 10);
      g.fillStyle = "rgba(255,255,255,0.55)";
      const lab = step < 1 ? tc(t, fps).slice(3) : tc(t, fps).slice(0, 8);
      g.fillText(lab, x + 4, 12);
      const sub = step / 5;
      if (sub * this.pps > 8)
        for (let k = 1; k < 5; k++) {
          g.fillStyle = "rgba(255,255,255,0.13)";
          g.fillRect(Math.round(this.tx(t + k * sub)), H - 5, 1, 5);
        }
    }
    for (const m of this.s.project.markers) {
      const x = this.tx(m.t);
      if (x < -60 || x > W) continue;
      g.fillStyle = "#ffbf5e";
      g.beginPath();
      g.moveTo(x - 5, H - 12);
      g.lineTo(x + 5, H - 12);
      g.lineTo(x, H - 4);
      g.fill();
      if (m.label) {
        g.fillStyle = "rgba(255,191,94,0.85)";
        g.font = "10px Geist Variable, system-ui";
        g.fillText(m.label.slice(0, 18), x + 6, H - 4);
        g.font = "10.5px JetBrains Mono, ui-monospace, monospace";
      }
    }
    const px = this.tx(this.s.time);
    g.fillStyle = "#3ee6c4";
    g.beginPath();
    g.moveTo(px - 6, 0);
    g.lineTo(px + 6, 0);
    g.lineTo(px + 6, 8);
    g.lineTo(px, 14);
    g.lineTo(px - 6, 8);
    g.fill();
  }

  private headSig = "";
  private drawHeads() {
    const rows = this.rows();
    const sig = rows.map((r) => `${r.track.id}:${r.track.name}:${r.track.muted}:${r.track.hidden}:${r.track.locked}:${r.h}`).join("|");
    this.headsIn.style.transform = `translateY(${-this.sy}px)`;
    if (sig === this.headSig) return;
    this.headSig = sig;
    this.headsIn.replaceChildren(
      ...rows.map((r) => {
        const t = r.track;
        const tog = (label: string, on: boolean, title: string, fn: () => void, cls = "") => {
          const bt = h("button", { class: `tg ${on ? "on" : ""} ${cls}`, type: "button", title }, label.length > 2 ? ic(label, 13) : label);
          bt.addEventListener("click", fn);
          return bt;
        };
        const name = h("span", { class: "nm", title: "Double-click to rename" }, t.name);
        name.addEventListener("dblclick", async () => {
          const n = await ask("Rename track", "Track name", t.name);
          if (n) this.s.mutate("rename track", (p) => (ops.getTrack(p, t.id).name = n.slice(0, 40)));
        });
        const el = h(
          "div",
          { class: `th ${t.kind}`, style: `height:${r.h}px` },
          name,
          t.kind === "video" ? tog(t.hidden ? "eyeOff" : "eye", t.hidden, "Hide track", () => this.s.mutate("hide track", (p) => (ops.getTrack(p, t.id).hidden = !t.hidden))) : null,
          tog("M", t.muted, "Mute track", () => this.s.mutate("mute track", (p) => (ops.getTrack(p, t.id).muted = !t.muted)), "m"),
          tog(t.locked ? "lock" : "unlock", t.locked, "Lock track", () => this.s.mutate("lock track", (p) => (ops.getTrack(p, t.id).locked = !t.locked))),
        );
        el.addEventListener("contextmenu", (e) => {
          e.preventDefault();
          menu(
            [
              { label: "Rename…", run: () => name.dispatchEvent(new MouseEvent("dblclick")) },
              { label: "Close gaps", run: () => this.s.mutate("close gaps", (p) => ops.closeGaps(p, t.id)) },
              { sep: true, label: "" },
              { label: "Delete track", disabled: t.clips.length > 0, run: () => this.s.mutate("delete track", (p) => (p.tracks = p.tracks.filter((x) => x.id !== t.id))) },
            ],
            e.clientX,
            e.clientY,
          );
        });
        return el;
      }),
    );
  }

  /* ------------------------------------------------------------ hit testing */

  private hit(x: number, y: number): { clip: Clip; track: Track; zone: "start" | "end" | "body" } | null {
    const yy = y + this.sy;
    for (const r of this.rows()) {
      if (yy < r.y || yy >= r.y + r.h) continue;
      for (const c of r.track.clips) {
        const cx = this.tx(c.start);
        const cw = c.dur * this.pps;
        if (x >= cx - 2 && x <= cx + cw + 2) {
          const zone = x - cx < Math.min(EDGE, cw / 3) ? "start" : cx + cw - x < Math.min(EDGE, cw / 3) ? "end" : "body";
          return { clip: c, track: r.track, zone };
        }
      }
    }
    return null;
  }

  private rowAt(y: number): Track | null {
    const yy = y + this.sy;
    for (const r of this.rows()) if (yy >= r.y && yy < r.y + r.h) return r.track;
    return null;
  }

  private snapPoints(exclude?: string): number[] {
    const pts = [0, this.s.time, ...this.s.project.markers.map((m) => m.t)];
    for (const t of this.s.project.tracks) for (const c of t.clips) if (c.id !== exclude) pts.push(c.start, c.start + c.dur);
    return pts;
  }

  private snapT(t: number, exclude?: string, extraOffsets: number[] = [0]): number {
    this.snapLine = null;
    if (!this.snap) return t;
    const thr = 8 / this.pps;
    let best = t;
    let bestD = thr;
    for (const p of this.snapPoints(exclude))
      for (const off of extraOffsets) {
        const d = Math.abs(t + off - p);
        if (d < bestD) {
          bestD = d;
          best = p - off;
          this.snapLine = p;
        }
      }
    return best;
  }

  /* ------------------------------------------------------------ events */

  private local(e: MouseEvent, el: HTMLElement) {
    const r = el.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  private events() {
    this.hscroll.addEventListener("scroll", () => {
      this.sx = this.hscroll.scrollLeft;
      this.redraw();
    });
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      if (e.ctrlKey || e.metaKey) {
        const { x } = this.local(e, this.wrap);
        this.setZoom(this.pps * Math.exp(-e.deltaY * 0.0022), x);
        return;
      }
      if (e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
        this.sx = Math.max(0, this.sx + (e.deltaX || e.deltaY));
        this.syncScroll();
      } else {
        this.sy = Math.max(0, this.sy + e.deltaY * 0.6);
      }
      this.redraw();
    };
    this.wrap.addEventListener("wheel", wheel, { passive: false });
    this.heads.addEventListener("wheel", wheel, { passive: false });
    this.rulerEl.addEventListener("wheel", wheel, { passive: false });

    // Ruler: scrub.
    this.rulerEl.addEventListener("mousedown", (e) => {
      const seek = (ev: MouseEvent) => this.s.seek(Math.max(0, this.snapT(this.xt(this.local(ev, this.rulerEl).x))));
      this.s.pause();
      seek(e);
      const mv = (ev: MouseEvent) => seek(ev);
      const up = () => {
        this.snapLine = null;
        removeEventListener("mousemove", mv);
        removeEventListener("mouseup", up);
        this.redraw();
      };
      addEventListener("mousemove", mv);
      addEventListener("mouseup", up);
    });
    this.rulerEl.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      const t = this.xt(this.local(e, this.rulerEl).x);
      const near = this.s.project.markers.find((m) => Math.abs(m.t - t) * this.pps < 8);
      menu(
        [
          { label: "Add marker here", run: () => this.s.mutate("marker", (p) => p.markers.push({ t, label: "" })) },
          ...(near
            ? [
                { label: "Rename marker…", run: async () => {
                  const n = await ask("Marker", "Label", near.label);
                  if (n !== null) this.s.mutate("marker", () => (near.label = n));
                } },
                { label: "Delete marker", run: () => this.s.mutate("marker", (p) => (p.markers = p.markers.filter((m) => m !== near))) },
              ]
            : []),
          { label: "Clear all markers", disabled: !this.s.project.markers.length, run: () => this.s.mutate("markers", (p) => (p.markers = [])) },
        ],
        e.clientX,
        e.clientY,
      );
    });

    this.wrap.addEventListener("mousemove", (e) => {
      const { x, y } = this.local(e, this.wrap);
      this.hover = { x, y };
      if (this.drag) return;
      if (this.tool === "blade") return this.redraw();
      const hit = this.hit(x, y);
      this.wrap.style.cursor = hit && hit.zone !== "body" ? "ew-resize" : hit ? "grab" : "";
    });
    this.wrap.addEventListener("mouseleave", () => {
      this.hover = null;
      this.redraw();
    });

    this.wrap.addEventListener("mousedown", (e) => {
      if (e.button === 1) {
        this.drag = { kind: "pan", x0: e.clientX, s0: this.sx };
      } else if (e.button !== 0) return;
      const { x, y } = this.local(e, this.wrap);
      const hit = this.hit(x, y);
      if (!this.drag && this.tool === "blade") {
        if (hit && !hit.track.locked) {
          const t = this.snapT(this.xt(x));
          try {
            this.s.mutate("blade", (p) => ops.splitClip(p, hit.clip.id, t));
          } catch (err) {
            toast((err as Error).message, "error");
          }
        }
        return;
      }
      if (!this.drag) {
        if (!hit) {
          this.s.select(null);
          this.s.pause();
          this.s.seek(Math.max(0, this.snapT(this.xt(x))));
          this.drag = { kind: "scrub" };
        } else {
          this.s.select(hit.clip.id);
          if (hit.track.locked) return;
          if (hit.zone === "body") this.drag = { kind: "move", id: hit.clip.id, dx: this.xt(x) - hit.clip.start, t0: this.xt(x), trackId: hit.track.id, origStart: hit.clip.start, ghostStart: hit.clip.start, ghostTrack: hit.track.id, moved: false };
          else this.drag = { kind: "trim", id: hit.clip.id, edge: hit.zone, ghostT: hit.zone === "start" ? hit.clip.start : hit.clip.start + hit.clip.dur, moved: false };
        }
      }
      const mv = (ev: MouseEvent) => this.onDrag(ev);
      const up = (ev: MouseEvent) => {
        removeEventListener("mousemove", mv);
        removeEventListener("mouseup", up);
        this.endDrag(ev);
      };
      addEventListener("mousemove", mv);
      addEventListener("mouseup", up);
    });

    this.wrap.addEventListener("dblclick", (e) => {
      const { x, y } = this.local(e, this.wrap);
      const hit = this.hit(x, y);
      if (hit?.clip.sceneId) document.dispatchEvent(new CustomEvent("vsd:openScene", { detail: hit.clip.sceneId }));
    });

    this.wrap.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      const { x, y } = this.local(e, this.wrap);
      const hit = this.hit(x, y);
      if (!hit) {
        menu(
          [
            { label: "Paste text title here", run: () => this.s.mutate("add text", (p) => this.s.select(ops.addClip(p, { text: "Title", start: this.xt(x) }).id)) },
            { label: "Add colour matte here", run: () => this.s.mutate("add colour", (p) => this.s.select(ops.addClip(p, { kind: "color", start: this.xt(x) }).id)) },
            { label: "Add adjustment layer here", run: () => this.s.mutate("add adjust", (p) => this.s.select(ops.addClip(p, { kind: "adjust", start: this.xt(x), dur: 5 }).id)) },
            { sep: true, label: "" },
            { label: "Add marker here", run: () => this.s.mutate("marker", (p) => p.markers.push({ t: this.xt(x), label: "" })) },
          ],
          e.clientX,
          e.clientY,
        );
        return;
      }
      this.s.select(hit.clip.id);
      const c = hit.clip;
      const t = this.xt(x);
      const run = (label: string, fn: (p: import("../../../src/core/model").Project) => unknown) => () => {
        try {
          this.s.mutate(label, fn);
        } catch (err) {
          toast((err as Error).message, "error");
        }
      };
      menu(
        [
          { label: "Split here", key: "S", run: run("split", (p) => ops.splitClip(p, c.id, t)) },
          { label: "Duplicate", key: "Ctrl+D", run: run("duplicate", (p) => this.s.select(ops.duplicateClip(p, c.id).id)) },
          { label: "Delete", key: "Del", run: run("delete", (p) => ops.deleteClip(p, c.id, false)) },
          { label: "Ripple delete", key: "Shift+Del", run: run("ripple delete", (p) => ops.deleteClip(p, c.id, true)) },
          { sep: true, label: "" },
          { label: "Speed…", run: async () => {
            const v = await ask("Speed", "Speed in % (10–1600)", String(Math.round((c.speed || 1) * 100)));
            if (v) run("speed", (p) => ops.setSpeed(p, c.id, Math.max(0.1, Math.min(16, Number(v) / 100))))();
          } },
          { label: c.reverse ? "Play forwards" : "Reverse", run: run("reverse", (p) => ops.patchClip(p, c.id, { reverse: !c.reverse })) },
          { label: "Freeze frame here", run: run("freeze", (p) => ops.freezeFrame(p, c.id, t)) },
          { label: "Slow push-in (Ken Burns)", run: run("ken burns", (p) => ops.kenBurns(p, c.id, 0.15)) },
          { sep: true, label: "" },
          { label: "Fade in / out 0.5 s", run: run("fades", (p) => ops.patchClip(p, c.id, { fadeIn: 0.5, fadeOut: 0.5 })) },
          { label: "Cross-dissolve in", run: run("transition", (p) => ops.patchClip(p, c.id, { transitionIn: { kind: "fade", dur: 0.6 } })) },
          ...(c.sceneId ? [{ label: "Open in Animate", run: () => document.dispatchEvent(new CustomEvent("vsd:openScene", { detail: c.sceneId })) }] : []),
        ],
        e.clientX,
        e.clientY,
      );
    });

    // Drag & drop from the library.
    this.wrap.addEventListener("dragover", (e) => {
      if (e.dataTransfer?.types.includes("application/x-vs-asset")) {
        e.preventDefault();
        e.dataTransfer.dropEffect = "copy";
        const { x } = this.local(e, this.wrap);
        this.snapT(this.xt(x));
        this.redraw();
      }
    });
    this.wrap.addEventListener("dragleave", () => {
      this.snapLine = null;
      this.redraw();
    });
    this.wrap.addEventListener("drop", (e) => {
      const id = e.dataTransfer?.getData("application/x-vs-asset");
      if (!id) return;
      e.preventDefault();
      e.stopPropagation();
      const { x, y } = this.local(e, this.wrap);
      const start = Math.max(0, this.snapT(this.xt(x)));
      this.snapLine = null;
      const row = this.rowAt(y);
      const asset = findAsset(this.s.project, id);
      const wantKind = asset?.kind === "audio" ? "audio" : "video";
      try {
        this.s.mutate("add clip", (p) => {
          const c = ops.addClip(p, { assetId: id, start, trackId: row && row.kind === wantKind && !row.locked ? row.id : undefined });
          this.s.select(c.id);
        });
      } catch (err) {
        toast((err as Error).message, "error");
      }
    });
  }

  private onDrag(e: MouseEvent) {
    const d = this.drag;
    if (!d) return;
    const { x, y } = this.local(e, this.wrap);
    if (d.kind === "pan") {
      this.sx = Math.max(0, d.s0 - (e.clientX - d.x0));
      this.syncScroll();
    } else if (d.kind === "scrub") {
      this.s.seek(Math.max(0, this.snapT(this.xt(x))));
    } else if (d.kind === "move") {
      const c = this.findClip(d.id);
      if (!c) return;
      let start = Math.max(0, this.xt(x) - d.dx);
      start = Math.max(0, this.snapT(start, c.id, [0, c.dur]));
      if (!d.moved && Math.abs(start - d.origStart) * this.pps < 3 && this.rowAt(y)?.id === d.trackId) return;
      d.moved = true;
      d.ghostStart = start;
      const row = this.rowAt(y);
      const src = this.s.project.tracks.find((t) => t.id === d.trackId);
      if (row && src && row.kind === src.kind && !row.locked) d.ghostTrack = row.id;
    } else if (d.kind === "trim") {
      const c = this.findClip(d.id);
      if (!c) return;
      let t = this.snapT(this.xt(x), c.id);
      if (d.edge === "start") t = Math.max(0, Math.min(c.start + c.dur - 1 / this.s.project.fps, t));
      else t = Math.max(c.start + 1 / this.s.project.fps, t);
      d.moved = true;
      d.ghostT = t;
    }
    this.redraw();
  }

  private endDrag(_e: MouseEvent) {
    const d = this.drag;
    this.drag = null;
    this.snapLine = null;
    if (!d) return;
    try {
      if (d.kind === "move" && d.moved) this.s.mutate("move clip", (p) => ops.moveClip(p, d.id, d.ghostStart, d.ghostTrack));
      else if (d.kind === "trim" && d.moved) this.s.mutate("trim clip", (p) => ops.trimClip(p, d.id, d.edge, d.ghostT));
    } catch (err) {
      toast((err as Error).message, "error");
    }
    this.redraw();
  }

  private findClip(id: string): Clip | undefined {
    for (const t of this.s.project.tracks) for (const c of t.clips) if (c.id === id) return c;
    return undefined;
  }

  /* ------------------------------------------------------------ commands */

  splitAtPlayhead(): void {
    const t = this.s.time;
    const sel = this.s.selection ? this.findClip(this.s.selection) : undefined;
    const targets = sel && t > sel.start && t < sel.start + sel.dur ? [sel] : this.s.project.tracks.filter((tr) => !tr.locked).flatMap((tr) => tr.clips.filter((c) => t > c.start + 1e-3 && t < c.start + c.dur - 1e-3));
    if (!targets.length) return toast("Nothing under the playhead to split.");
    this.s.mutate("split", (p) => {
      for (const c of targets) ops.splitClip(p, c.id, t);
    });
  }

  deleteSelected(ripple: boolean): void {
    const id = this.s.selection;
    if (!id) return;
    this.s.mutate(ripple ? "ripple delete" : "delete", (p) => ops.deleteClip(p, id, ripple));
    this.s.select(null);
  }

  duplicateSelected(): void {
    const id = this.s.selection;
    if (!id) return;
    this.s.mutate("duplicate", (p) => this.s.select(ops.duplicateClip(p, id).id));
  }

  addMarker(): void {
    const t = this.s.time;
    this.s.mutate("marker", (p) => p.markers.push({ t, label: "" }));
  }

  nudge(frames: number): void {
    const id = this.s.selection;
    if (!id) return;
    const c = this.findClip(id);
    if (!c) return;
    this.s.mutate("nudge", (p) => ops.moveClip(p, id, Math.max(0, c.start + frames / p.fps)));
  }

  private addTrackMenu(e: MouseEvent) {
    menu(
      [
        { label: "Add video track", run: () => this.s.mutate("add track", (p) => p.tracks.unshift(newTrack("video", `V${p.tracks.filter((t) => t.kind === "video").length + 1}`))) },
        { label: "Add audio track", run: () => this.s.mutate("add track", (p) => p.tracks.push(newTrack("audio", `A${p.tracks.filter((t) => t.kind === "audio").length + 1}`))) },
      ],
      e.clientX,
      e.clientY,
    );
  }
}
