/** Live preview: GPU-composited frame, letterboxed, with drag / pinch / rotate gestures on the selected clip. */
import type { Studio } from "../app/studio";
import { Compositor } from "../render/compositor";
import { buildLayers } from "../render/layers";
import { findClip, TRANSFORM_DEFAULTS, type TransformProp } from "../core/model";
import { isAnimated, valueAt } from "../core/keyframes";
import * as ops from "../core/ops";
import { h } from "./dom";

export class Preview {
  readonly el: HTMLElement;
  private canvas: HTMLCanvasElement;
  private comp: Compositor | null = null;
  private pending = false;
  private drawing = false;
  private again = false;
  private view = { x: 0, y: 0, w: 1, h: 1 };
  private frameW = 2;
  private frameH = 2;
  private overlay: HTMLElement;
  error: string | null = null;

  constructor(private readonly studio: Studio) {
    this.canvas = h("canvas", { class: "pv-canvas" });
    this.overlay = h("div", { class: "pv-sel" });
    this.el = h("div", { class: "preview" }, this.canvas, this.overlay);
    try {
      this.comp = new Compositor(this.canvas);
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      this.el.append(h("p", { class: "pv-error" }, this.error));
    }
    new ResizeObserver(() => this.resize()).observe(this.el);
    studio.on("time", () => this.request());
    studio.on("change", () => this.request());
    studio.on("select", () => this.request());
    studio.on("project", () => this.resize());
    this.gestures();
  }

  private resize() {
    const r = this.el.getBoundingClientRect();
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    this.canvas.width = Math.max(2, Math.round(r.width * dpr));
    this.canvas.height = Math.max(2, Math.round(r.height * dpr));
    const p = this.studio.project;
    const k = Math.min(this.canvas.width / p.width, this.canvas.height / p.height);
    const w = Math.round(p.width * k);
    const hh = Math.round(p.height * k);
    this.view = { x: Math.round((this.canvas.width - w) / 2), y: Math.round((this.canvas.height - hh) / 2), w, h: hh };
    // Internal frame: the view size, capped to keep preview fast on phones.
    const cap = Math.min(1, 1280 / Math.max(w, hh));
    this.frameW = Math.max(2, Math.round(w * cap));
    this.frameH = Math.max(2, Math.round(hh * cap));
    this.request();
  }

  request(): void {
    if (this.pending) return;
    this.pending = true;
    requestAnimationFrame(() => {
      this.pending = false;
      void this.draw();
    });
  }

  private async draw() {
    if (!this.comp) return;
    if (this.drawing) {
      this.again = true;
      return;
    }
    this.drawing = true;
    try {
      const s = this.studio;
      const layers = await buildLayers(s.project, s.pool, s.time, {
        purpose: "preview",
        previewMax: Math.max(this.frameW, this.frameH),
        wait: false,
        onLate: () => this.request(),
        outW: this.frameW,
        outH: this.frameH,
      });
      this.comp.render(layers, this.frameW, this.frameH, s.project.background, this.view);
      this.drawSelection();
    } catch (e) {
      console.error(e);
    } finally {
      this.drawing = false;
      if (this.again) {
        this.again = false;
        this.request();
      }
    }
  }

  /** Outline of the selected clip's position (approximate box from its transform). */
  private drawSelection() {
    const s = this.studio;
    const f = s.selection ? findClip(s.project, s.selection) : null;
    const c = f?.clip;
    const inside = c && s.time >= c.start && s.time < c.start + c.dur && f.track.kind === "video";
    this.overlay.style.display = inside ? "block" : "none";
    if (!inside || !c) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const k = this.view.w / s.project.width / dpr;
    const local = s.time - c.start;
    const v = (p: TransformProp) => valueAt(c.transform[p], local, TRANSFORM_DEFAULTS[p]);
    const sc = v("scale");
    const a = s.project.assets.find((x) => x.id === c.assetId);
    let w = s.project.width;
    let hh = s.project.height;
    if (a && a.width && a.height && c.fit === "contain") {
      const kk = Math.min(s.project.width / a.width, s.project.height / a.height);
      w = a.width * kk;
      hh = a.height * kk;
    }
    if (c.kind === "text" || c.kind === "shape") {
      w = c.kind === "shape" ? (c.shape?.w ?? 200) : s.project.width * 0.6;
      hh = c.kind === "shape" ? (c.shape?.h ?? 200) : (c.text?.size ?? 100) * 1.6 * (s.project.height / 1920);
    }
    const bw = w * sc * v("scaleX") * k;
    const bh = hh * sc * v("scaleY") * k;
    const cx = this.view.x / dpr + (s.project.width / 2 + v("x")) * k;
    const cy = this.view.y / dpr + (s.project.height / 2 + v("y")) * k;
    Object.assign(this.overlay.style, { width: `${bw}px`, height: `${bh}px`, left: `${cx - bw / 2}px`, top: `${cy - bh / 2}px`, transform: `rotate(${v("rotation")}deg)` });
  }

  private gestures() {
    const pts = new Map<number, { x: number; y: number }>();
    let base: { x: number; y: number; scale: number; rotation: number; d: number; a: number; cx: number; cy: number } | null = null;
    const studio = this.studio;
    const sel = () => {
      const f = studio.selection ? findClip(studio.project, studio.selection) : null;
      return f && f.track.kind === "video" && studio.time >= f.clip.start && studio.time < f.clip.start + f.clip.dur ? f.clip : null;
    };
    const metrics = () => {
      const list = [...pts.values()];
      const a = list[0]!;
      const b = list[1] ?? a;
      return { cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2, d: Math.hypot(b.x - a.x, b.y - a.y), a: Math.atan2(b.y - a.y, b.x - a.x) };
    };
    const start = () => {
      const c = sel();
      if (!c) return (base = null);
      const local = studio.time - c.start;
      const m = metrics();
      base = { x: valueAt(c.transform.x, local, 0), y: valueAt(c.transform.y, local, 0), scale: valueAt(c.transform.scale, local, 1), rotation: valueAt(c.transform.rotation, local, 0), ...m };
    };
    this.el.addEventListener("pointerdown", (e) => {
      if (!sel()) return;
      this.el.setPointerCapture(e.pointerId);
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      start();
    });
    this.el.addEventListener("pointermove", (e) => {
      if (!pts.has(e.pointerId) || !base) return;
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      const c = sel();
      if (!c) return;
      const m = metrics();
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const k = studio.project.width / (this.view.w / dpr);
      const set = (prop: TransformProp, v: number) => {
        if (isAnimated(c.transform[prop])) ops.keyframe(studio.project, c.id, prop, studio.time, v);
        else ops.setConstant(studio.project, c.id, prop, v);
      };
      studio.mutate("transform", () => {
        set("x", Math.round(base!.x + (m.cx - base!.cx) * k));
        set("y", Math.round(base!.y + (m.cy - base!.cy) * k));
        if (pts.size > 1 && base!.d > 10) {
          set("scale", Math.max(0.02, base!.scale * (m.d / base!.d)));
          set("rotation", base!.rotation + ((m.a - base!.a) * 180) / Math.PI);
        }
      });
    });
    const end = (e: PointerEvent) => {
      pts.delete(e.pointerId);
      if (pts.size) start();
      else base = null;
    };
    this.el.addEventListener("pointerup", end);
    this.el.addEventListener("pointercancel", end);
  }
}
