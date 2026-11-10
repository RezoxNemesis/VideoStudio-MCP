/** Live preview: GPU-composited frame, letterboxed, with drag / pinch / rotate gestures on the selected clip. */
import { colorView, onColorView } from "../color/view";
import type { Studio } from "../app/studio";
import { Compositor } from "../render/compositor";
import { buildLayers } from "../render/layers";
import { findClip, TRANSFORM_DEFAULTS, type TransformProp } from "../core/model";
import { isAnimated, valueAt } from "../core/keyframes";
import * as ops from "../core/ops";
import { h } from "./dom";

export type PreviewQuality = "auto" | "full" | "half" | "quarter";
const QUALITY_KEY = "vs.preview.quality";

export class Preview {
  readonly el: HTMLElement;
  private canvas!: HTMLCanvasElement;
  private comp: Compositor | null = null;
  private pending = false;
  private drawing = false;
  private again = false;
  private view = { x: 0, y: 0, w: 1, h: 1 };
  private frameW = 2;
  private frameH = 2;
  private overlay: HTMLElement;
  private notice: HTMLElement;
  private recoverTimer: ReturnType<typeof setTimeout> | null = null;
  private attempts = 0;
  /** View zoom (1 = fit) and pan in CSS px from the centred position. */
  private zoom = 1;
  private panX = 0;
  private panY = 0;
  quality: PreviewQuality = "auto";
  /** How many times the graphics context was lost and rebuilt in this session. */
  losses = 0;
  error: string | null = null;
  /** Called when zoom or pan changes (the desktop viewer redraws its guides and zoom readout). */
  onView: (() => void) | null = null;

  constructor(private readonly studio: Studio) {
    this.overlay = h("div", { class: "pv-sel" });
    this.notice = h("div", { class: "pv-notice" });
    this.notice.style.display = "none";
    this.el = h("div", { class: "preview" }, this.overlay, this.notice);
    try {
      const q = localStorage.getItem(QUALITY_KEY);
      if (q === "full" || q === "half" || q === "quarter") this.quality = q;
    } catch {
      /* storage unavailable */
    }
    this.mount();
    new ResizeObserver(() => this.resize()).observe(this.el);
    studio.on("time", () => this.request());
    studio.on("change", () => this.request());
    studio.on("select", () => this.request());
    studio.on("project", () => {
      this.resetView();
      this.resize();
    });
    // Coming back to the window: make sure the picture is still alive (a GPU restart can happen while hidden).
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden) this.check();
    });
    window.addEventListener("focus", () => this.check());
    this.gestures();
    this.viewGestures();
    onColorView(() => this.request());
  }

  /**
   * (Re)create the canvas and its GPU compositor. A WebGL context that was lost (GPU process restart, driver reset,
   * suspend/resume) cannot be reused reliably, so recovery always starts from a fresh canvas.
   */
  private mount(): boolean {
    const old = this.canvas as HTMLCanvasElement | undefined;
    try {
      this.comp?.dispose();
    } catch {
      /* the old context is already gone */
    }
    this.comp = null;
    const canvas = h("canvas", { class: "pv-canvas" });
    canvas.addEventListener("webglcontextlost", (e) => {
      e.preventDefault();
      if (canvas === this.canvas) this.lost("The graphics driver restarted");
    });
    canvas.addEventListener("webglcontextrestored", () => {
      if (canvas === this.canvas && !this.comp) this.recover();
    });
    if (old) old.replaceWith(canvas);
    else this.el.prepend(canvas);
    this.canvas = canvas;
    try {
      this.comp = new Compositor(canvas);
      this.error = null;
      this.attempts = 0;
      this.notice.style.display = "none";
      this.notice.replaceChildren();
      canvas.style.visibility = "visible";
      return true;
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      canvas.style.visibility = "hidden";
      return false;
    }
  }

  private showNotice(title: string, detail: string, retry: boolean) {
    this.notice.replaceChildren(h("strong", {}, title), h("span", {}, detail));
    if (retry) {
      const btn = h("button", { type: "button", class: "pv-retry" }, "Restart preview");
      btn.addEventListener("click", () => this.rebuild());
      this.notice.append(btn);
    }
    this.notice.style.display = "flex";
  }

  /** The context is gone: hide the dead canvas (it would paint white) and start rebuilding. */
  private lost(why: string) {
    if (!this.comp && this.recoverTimer) return;
    this.losses++;
    this.comp = null;
    this.canvas.style.visibility = "hidden";
    this.showNotice("Restarting the preview…", `${why}. Your edit is safe.`, false);
    this.scheduleRecover(400);
  }

  private scheduleRecover(ms: number) {
    if (this.recoverTimer) clearTimeout(this.recoverTimer);
    this.recoverTimer = setTimeout(() => {
      this.recoverTimer = null;
      this.recover();
    }, ms);
  }

  private recover() {
    if (this.recoverTimer) {
      clearTimeout(this.recoverTimer);
      this.recoverTimer = null;
    }
    if (this.mount()) {
      this.resize();
      return;
    }
    this.attempts++;
    // The GPU process needs a moment to come back; keep trying, slower over time, and never give up.
    const wait = Math.min(8000, 500 * 2 ** Math.min(4, this.attempts));
    if (this.attempts >= 3) this.showNotice("The preview could not start", `${this.error ?? "No GPU context"}. Trying again automatically.`, true);
    this.scheduleRecover(wait);
  }

  /** Throw the current GPU context away and build a new one (also the manual "Restart preview"). */
  rebuild(): void {
    this.attempts = 0;
    this.recover();
  }

  /** Notice a context that died without an event reaching us (e.g. while the window was hidden). */
  check(): void {
    if (this.comp?.lost) this.lost("The graphics driver restarted");
    else if (!this.comp && !this.recoverTimer) this.recover();
    else this.request();
  }

  /** Whether the preview currently has a working GPU context, and what drives it. */
  status(): { ok: boolean; losses: number; error: string | null; renderer: string | null; quality: PreviewQuality; zoom: number; frame: string } {
    return { ok: !!this.comp && !this.comp.lost, losses: this.losses, error: this.comp ? null : this.error, renderer: this.comp?.renderer ?? null, quality: this.quality, zoom: this.zoom, frame: `${this.frameW}x${this.frameH}` };
  }

  setQuality(q: PreviewQuality): void {
    this.quality = q;
    try {
      localStorage.setItem(QUALITY_KEY, q);
    } catch {
      /* storage unavailable */
    }
    this.resize();
  }

  /** Zoom factor relative to "fit" (1 = whole frame visible). */
  get zoomLevel(): number {
    return this.zoom;
  }

  /** The frame's rectangle inside the preview element, in CSS px (for guides and overlays). */
  viewRect(): { x: number; y: number; w: number; h: number } {
    const dpr = this.dpr();
    return { x: this.view.x / dpr, y: this.view.y / dpr, w: this.view.w / dpr, h: this.view.h / dpr };
  }

  resetView(): void {
    this.zoom = 1;
    this.panX = 0;
    this.panY = 0;
    this.resize();
    this.onView?.();
  }

  /** Zoom to `z` (1 = fit), keeping the point under (cx, cy) — CSS px inside the preview — where it is. */
  zoomTo(z: number, cx?: number, cy?: number): void {
    const r = this.el.getBoundingClientRect();
    const px = cx ?? r.width / 2;
    const py = cy ?? r.height / 2;
    const next = Math.max(1, Math.min(16, z));
    if (next === 1) return this.resetView();
    const before = this.viewRect();
    const fx = (px - before.x) / before.w;
    const fy = (py - before.y) / before.h;
    this.zoom = next;
    this.panX = 0;
    this.panY = 0;
    this.layout();
    const centred = this.viewRect();
    this.panX = px - fx * centred.w - centred.x;
    this.panY = py - fy * centred.h - centred.y;
    this.resize();
    this.onView?.();
  }

  private dpr(): number {
    return Math.min(2, window.devicePixelRatio || 1);
  }

  /** Where the frame sits on the canvas for the current size, zoom and pan. */
  private layout() {
    const p = this.studio.project;
    const dpr = this.dpr();
    const k = Math.min(this.canvas.width / p.width, this.canvas.height / p.height) * this.zoom;
    const w = Math.max(2, Math.round(p.width * k));
    const hh = Math.max(2, Math.round(p.height * k));
    // Keep at least part of the frame on screen when panning.
    const maxX = Math.max(0, (w - this.canvas.width) / 2 / dpr);
    const maxY = Math.max(0, (hh - this.canvas.height) / 2 / dpr);
    this.panX = Math.max(-maxX, Math.min(maxX, this.panX));
    this.panY = Math.max(-maxY, Math.min(maxY, this.panY));
    this.view = { x: Math.round((this.canvas.width - w) / 2 + this.panX * dpr), y: Math.round((this.canvas.height - hh) / 2 + this.panY * dpr), w, h: hh };
  }

  private resize() {
    const r = this.el.getBoundingClientRect();
    const dpr = this.dpr();
    const cw = Math.max(2, Math.round(r.width * dpr));
    const ch = Math.max(2, Math.round(r.height * dpr));
    if (this.canvas.width !== cw) this.canvas.width = cw;
    if (this.canvas.height !== ch) this.canvas.height = ch;
    this.layout();
    const p = this.studio.project;
    const { w, h: hh } = this.view;
    // Internal frame. Auto: the size it is shown at, capped to stay fast (sharper when zoomed in).
    // Full / half / quarter: a fixed share of the project's own resolution.
    let fw: number;
    let fh: number;
    if (this.quality === "auto") {
      const cap = Math.min(1, (this.zoom > 1 ? 2560 : 1600) / Math.max(w, hh));
      fw = w * cap;
      fh = hh * cap;
    } else {
      const f = this.quality === "full" ? 1 : this.quality === "half" ? 0.5 : 0.25;
      const cap = Math.min(1, 3840 / Math.max(p.width, p.height));
      fw = p.width * f * cap;
      fh = p.height * f * cap;
    }
    this.frameW = Math.max(2, Math.round(fw));
    this.frameH = Math.max(2, Math.round(fh));
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
    if (this.comp.lost) return this.lost("The graphics driver restarted");
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
      // The context can be replaced while the layers were being prepared.
      const comp = this.comp;
      if (!comp) return;
      if (comp.lost) return this.lost("The graphics driver restarted");
      comp.bypass = colorView.bypass;
      comp.split = colorView.split;
      comp.showKey = colorView.showKey;
      comp.render(layers, this.frameW, this.frameH, s.project.background, this.view);
      this.drawSelection();
    } catch (e) {
      console.error(e);
      if (this.comp?.lost) this.lost("The graphics driver restarted");
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
    const dpr = this.dpr();
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
      if (e.button === 1 || e.altKey || !sel()) return;
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

  /** Desktop viewing: wheel zooms at the pointer, middle-drag (or Alt-drag) pans, double middle-click fits. */
  private viewGestures() {
    this.el.addEventListener(
      "wheel",
      (e) => {
        e.preventDefault();
        const r = this.el.getBoundingClientRect();
        if (e.ctrlKey || e.metaKey || !e.shiftKey) {
          const step = Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0018));
          this.zoomTo(this.zoom * step, e.clientX - r.left, e.clientY - r.top);
        } else if (this.zoom > 1) {
          this.panX -= e.deltaY + e.deltaX;
          this.resize();
          this.onView?.();
        }
      },
      { passive: false },
    );
    let drag: { id: number; x: number; y: number; px: number; py: number } | null = null;
    this.el.addEventListener("pointerdown", (e) => {
      if (!(e.button === 1 || (e.button === 0 && e.altKey)) || this.zoom <= 1) return;
      e.preventDefault();
      drag = { id: e.pointerId, x: e.clientX, y: e.clientY, px: this.panX, py: this.panY };
      this.el.setPointerCapture(e.pointerId);
      this.el.classList.add("panning");
    });
    this.el.addEventListener("pointermove", (e) => {
      if (!drag || e.pointerId !== drag.id) return;
      this.panX = drag.px + (e.clientX - drag.x);
      this.panY = drag.py + (e.clientY - drag.y);
      this.resize();
      this.onView?.();
    });
    const stop = (e: PointerEvent) => {
      if (!drag || e.pointerId !== drag.id) return;
      drag = null;
      this.el.classList.remove("panning");
    };
    this.el.addEventListener("pointerup", stop);
    this.el.addEventListener("pointercancel", stop);
    this.el.addEventListener("auxclick", (e) => {
      if (e.button === 1 && e.detail === 2) this.resetView();
    });
  }
}
