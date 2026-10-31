/**
 * Phone timeline: the playhead stays in the middle and the tracks scroll under it (scrolling = scrubbing).
 * Tap a clip to select it; drag a selected clip to move it (with snapping) or its edges to trim. Pinch to zoom.
 */
import type { Studio } from "../app/studio";
import { findAsset, type Clip, type Track } from "../core/model";
import { isAnimated } from "../core/keyframes";
import * as ops from "../core/ops";
import { fmtTime, h, icon } from "./dom";

const HEADER = 44;

export class Timeline {
  readonly el: HTMLElement;
  private scroller: HTMLElement;
  private content: HTMLElement;
  private ruler: HTMLCanvasElement;
  private rows: HTMLElement;
  private timeLabel: HTMLElement;
  pps = 60; // pixels per second
  private programmatic = false;
  private rendering = false;
  private pad = 0;

  constructor(private readonly s: Studio) {
    this.ruler = h("canvas", { class: "tl-ruler" });
    this.rows = h("div", { class: "tl-rows" });
    this.content = h("div", { class: "tl-content" }, this.ruler, this.rows);
    this.scroller = h("div", { class: "tl-scroll" }, this.content);
    this.timeLabel = h("div", { class: "tl-time" }, "00:00:00");
    this.el = h("div", { class: "timeline" }, this.scroller, h("div", { class: "tl-playhead" }), this.timeLabel);
    this.scroller.addEventListener("scroll", () => this.onScroll(), { passive: true });
    new ResizeObserver(() => this.render()).observe(this.el);
    s.on("change", () => this.render());
    s.on("select", () => this.render());
    s.on("project", () => this.render());
    s.on("time", () => this.follow());
    this.pinch();
    this.rows.addEventListener("click", (e) => {
      if (e.target === this.rows || (e.target as HTMLElement).classList.contains("tl-row")) s.select(null);
    });
  }

  private x(t: number): number {
    return this.pad + t * this.pps;
  }

  private onScroll() {
    if (this.programmatic) {
      this.programmatic = false;
      return;
    }
    const t = Math.max(0, this.scroller.scrollLeft / this.pps);
    if (this.s.playing) this.s.pause();
    this.s.time = Math.min(t, this.s.duration);
    this.s.emit("time");
  }

  private follow() {
    const want = Math.round(this.s.time * this.pps);
    this.timeLabel.textContent = `${fmtTime(this.s.time, this.s.project.fps)} / ${fmtTime(this.s.duration, this.s.project.fps)}`;
    if (Math.abs(this.scroller.scrollLeft - want) > 0.5) {
      this.programmatic = true;
      this.scroller.scrollLeft = want;
    }
  }

  render(): void {
    if (this.rendering) return;
    this.rendering = true;
    requestAnimationFrame(() => {
      this.rendering = false;
      this.draw();
    });
  }

  private draw() {
    const s = this.s;
    const vw = this.scroller.clientWidth || 360;
    this.pad = vw / 2;
    const dur = Math.max(s.duration, 5) + 10;
    const width = this.pad * 2 + dur * this.pps;
    this.content.style.width = `${width}px`;
    this.drawRuler(width, dur);
    this.rows.replaceChildren(...s.project.tracks.map((t) => this.row(t)));
    this.rows.append(h("div", { class: "tl-spacer" }));
    this.follow();
  }

  private drawRuler(width: number, dur: number) {
    const dpr = Math.min(2, devicePixelRatio || 1);
    const c = this.ruler;
    const W = Math.min(width, 32000);
    c.width = Math.round(W * dpr);
    c.height = Math.round(22 * dpr);
    c.style.width = `${W}px`;
    const x = c.getContext("2d")!;
    x.scale(dpr, dpr);
    x.fillStyle = getComputedStyle(this.el).getPropertyValue("--muted") || "#8a93a8";
    x.font = "10px system-ui";
    const steps = [0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300];
    const major = steps.find((st) => st * this.pps >= 70) ?? 300;
    const minor = major / 5;
    for (let t = 0; t <= dur; t += minor) {
      const px = this.x(t);
      const isMajor = Math.abs(t / major - Math.round(t / major)) < 1e-6;
      x.fillRect(px, isMajor ? 10 : 15, 1, isMajor ? 12 : 7);
      if (isMajor) x.fillText(major < 1 ? t.toFixed(1) + "s" : fmtShort(t), px + 3, 9);
    }
    for (const m of this.s.project.markers) {
      x.fillStyle = "#ffb74d";
      x.fillRect(this.x(m.t) - 1, 0, 3, 22);
    }
  }

  private row(t: Track): HTMLElement {
    const s = this.s;
    const head = h(
      "div",
      { class: "tl-head", style: `width:${HEADER}px` },
      h("span", { class: "tl-name" }, t.name),
      h("button", { class: `tl-hbtn ${t.muted ? "on" : ""}`, type: "button", title: t.muted ? "Unmute" : "Mute", onclick: () => s.mutate("mute", () => (t.muted = !t.muted)) }, icon(t.muted ? "mute" : "volume", 14)),
      t.kind === "video" ? h("button", { class: `tl-hbtn ${t.hidden ? "on" : ""}`, type: "button", title: t.hidden ? "Show" : "Hide", onclick: () => s.mutate("hide", () => (t.hidden = !t.hidden)) }, icon(t.hidden ? "eyeOff" : "eye", 14)) : null,
    );
    const row = h("div", { class: `tl-row ${t.kind}`, "data-track": t.id }, head);
    for (const c of t.clips) row.append(this.clip(t, c));
    return row;
  }

  private clip(t: Track, c: Clip): HTMLElement {
    const s = this.s;
    const selected = s.selection === c.id;
    const a = findAsset(s.project, c.assetId);
    const kind = c.kind === "media" ? (a?.kind ?? "video") : c.kind;
    const el = h("div", { class: `tl-clip k-${kind} ${selected ? "sel" : ""}`, style: `left:${this.x(c.start)}px;width:${Math.max(4, c.dur * this.pps)}px`, "data-id": c.id }, h("span", { class: "tl-clabel" }, c.kind === "text" ? `T  ${c.text?.text ?? ""}` : c.name), c.speed !== 1 ? h("span", { class: "tl-badge" }, `${c.speed}×`) : null);
    if (a?.peaks && (kind === "audio" || kind === "video") && c.dur * this.pps > 12) el.append(this.wave(c, a.peaks, a.duration));
    if (c.transitionIn.kind !== "none") el.append(h("span", { class: "tl-tr in", style: `width:${c.transitionIn.dur * this.pps}px` }));
    if (c.transitionOut.kind !== "none") el.append(h("span", { class: "tl-tr out", style: `width:${c.transitionOut.dur * this.pps}px` }));
    if (selected) {
      // Keyframe diamonds.
      const times = new Set<number>();
      for (const v of [...Object.values(c.transform), ...Object.values(c.grade), c.volume]) if (isAnimated(v)) for (const k of v) times.add(Math.round(k.t * 1000) / 1000);
      for (const kt of times) el.append(h("i", { class: "tl-kf", style: `left:${kt * this.pps}px` }));
      el.append(h("b", { class: "tl-handle l", "data-edge": "start" }), h("b", { class: "tl-handle r", "data-edge": "end" }));
    }
    this.clipGestures(el, t, c);
    return el;
  }

  private wave(c: Clip, peaks: number[], duration: number): HTMLCanvasElement {
    const w = Math.min(4000, Math.round(c.dur * this.pps));
    const cv = h("canvas", { class: "tl-wave", width: w, height: 24 });
    const x = cv.getContext("2d")!;
    x.fillStyle = "rgba(255,255,255,0.55)";
    const rate = peaks.length / Math.max(0.001, duration);
    for (let px = 0; px < w; px++) {
      const st = c.in + (px / this.pps) * c.speed;
      const v = peaks[Math.floor(st * rate)] ?? 0;
      const hh = Math.max(1, v * 22);
      x.fillRect(px, 12 - hh / 2, 1, hh);
    }
    return cv;
  }

  private drag: { id: string; track: string; mode: "move" | "start" | "end"; x: number; start: number; dur: number; pointer: number } | null = null;

  private clipGestures(el: HTMLElement, t: Track, c: Clip) {
    const s = this.s;
    let downX = 0;
    let downY = 0;
    el.addEventListener("pointerdown", (e) => {
      downX = e.clientX;
      downY = e.clientY;
      const edge = (e.target as HTMLElement).dataset.edge as "start" | "end" | undefined;
      if (s.selection !== c.id || t.locked) return;
      e.stopPropagation();
      e.preventDefault();
      this.drag = { id: c.id, track: t.id, mode: edge ?? "move", x: e.clientX, start: c.start, dur: c.dur, pointer: e.pointerId };
      let moved = false;
      const move = (ev: PointerEvent) => {
        const d = this.drag;
        if (!d || ev.pointerId !== d.pointer) return;
        if (!moved && Math.abs(ev.clientX - d.x) < 6 && Math.abs(ev.clientY - downY) < 10) return;
        moved = true;
        const dt = (ev.clientX - d.x) / this.pps;
        try {
          if (d.mode === "move") {
            const ns = this.snap(Math.max(0, d.start + dt), d.id, d.dur);
            const under = document.elementFromPoint(ev.clientX, ev.clientY)?.closest<HTMLElement>(".tl-row")?.dataset.track;
            s.mutate("move clip", (p) => ops.moveClip(p, d.id, ns, under && under !== d.track ? under : undefined));
            const f = s.project.tracks.find((tr) => tr.clips.some((x) => x.id === d.id));
            if (f) d.track = f.id;
          } else if (d.mode === "start") s.mutate("trim", (p) => ops.trimClip(p, d.id, "start", this.snap(d.start + dt, d.id, 0)));
          else s.mutate("trim", (p) => ops.trimClip(p, d.id, "end", this.snap(d.start + d.dur + dt, d.id, 0)));
        } catch {
          /* invalid position: skip */
        }
      };
      const up = (ev: PointerEvent) => {
        if (this.drag && ev.pointerId !== this.drag.pointer) return;
        this.drag = null;
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", up);
        window.removeEventListener("pointercancel", up);
      };
      window.addEventListener("pointermove", move);
      window.addEventListener("pointerup", up);
      window.addEventListener("pointercancel", up);
    });
    el.addEventListener("pointerup", (e) => {
      if (s.selection === c.id) return;
      if (Math.hypot(e.clientX - downX, e.clientY - downY) < 8) s.select(c.id);
    });
  }

  /** Snap a time (and optionally the clip end) to the playhead and other clip edges within 10 px. */
  private snap(t: number, ignore: string, dur: number): number {
    const edges = [this.s.time, 0];
    for (const tr of this.s.project.tracks) for (const c of tr.clips) if (c.id !== ignore) edges.push(c.start, c.start + c.dur);
    const tol = 10 / this.pps;
    let best = t;
    let bestD = tol;
    for (const e of edges) {
      for (const cand of dur > 0 ? [e, e - dur] : [e]) {
        const d = Math.abs(cand - t);
        if (d < bestD) {
          bestD = d;
          best = cand;
        }
      }
    }
    return Math.max(0, best);
  }

  private pinch() {
    const pts = new Map<number, number>();
    let d0 = 0;
    let pps0 = this.pps;
    this.scroller.addEventListener("pointerdown", (e) => {
      if (e.pointerType !== "touch") return;
      pts.set(e.pointerId, e.clientX);
      if (pts.size === 2) {
        const [a, b] = [...pts.values()];
        d0 = Math.abs(a! - b!);
        pps0 = this.pps;
      }
    });
    this.scroller.addEventListener("pointermove", (e) => {
      if (!pts.has(e.pointerId)) return;
      pts.set(e.pointerId, e.clientX);
      if (pts.size !== 2 || d0 < 20) return;
      const [a, b] = [...pts.values()];
      this.zoom((pps0 * Math.abs(a! - b!)) / d0);
    });
    const end = (e: PointerEvent) => pts.delete(e.pointerId);
    this.scroller.addEventListener("pointerup", end);
    this.scroller.addEventListener("pointercancel", end);
    this.scroller.addEventListener(
      "wheel",
      (e) => {
        if (!e.ctrlKey) return;
        e.preventDefault();
        this.zoom(this.pps * (e.deltaY < 0 ? 1.15 : 0.87));
      },
      { passive: false },
    );
  }

  zoom(pps: number): void {
    this.pps = Math.max(8, Math.min(600, pps));
    this.draw();
  }
}

function fmtShort(t: number): string {
  const m = Math.floor(t / 60);
  const s = Math.round(t % 60);
  return m ? `${m}:${String(s).padStart(2, "0")}` : `${s}s`;
}
