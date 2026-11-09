/**
 * Graph editor: every animated property of the selected clip as a value curve over time. Drag keys in time and
 * value (Shift = time only, Alt = value only), double-click a curve to add a key, right-click a key for its easing
 * or to delete it. Each curve is scaled to its own range so position, scale and opacity can be shaped together.
 */
import type { Studio } from "../../../src/app/studio";
import { findClip, FX_DEFAULTS, GRADE_DEFAULTS, TRANSFORM_DEFAULTS, type Clip, type Ease, type Keyframe, type Project } from "../../../src/core/model";
import { isAnimated, valueAt } from "../../../src/core/keyframes";
import * as ops from "../../../src/core/ops";
import { h, menu, tc } from "./kit";

const COLORS = ["#3ee6c4", "#8a7dff", "#ff6fae", "#ffbf5e", "#5ab0ff", "#c8f75a", "#ff8a5c", "#e8edf4"];
const EASES: [Ease, string][] = [
  ["linear", "Linear"],
  ["inOut", "Ease in-out"],
  ["in", "Ease in"],
  ["out", "Ease out"],
  ["back", "Back (overshoot)"],
  ["elastic", "Elastic"],
  ["bounce", "Bounce"],
  ["hold", "Hold (step)"],
];

interface Curve {
  path: string;
  label: string;
  keys: Keyframe[];
  def: number;
  color: string;
  lo: number;
  hi: number;
}

function curvesOf(c: Clip): Omit<Curve, "color" | "lo" | "hi">[] {
  const out: Omit<Curve, "color" | "lo" | "hi">[] = [];
  for (const [k, v] of Object.entries(c.transform)) if (isAnimated(v)) out.push({ path: `transform.${k}`, label: k, keys: v, def: TRANSFORM_DEFAULTS[k as keyof typeof TRANSFORM_DEFAULTS] });
  for (const [k, v] of Object.entries(c.grade)) if (isAnimated(v)) out.push({ path: `grade.${k}`, label: k, keys: v, def: GRADE_DEFAULTS[k as keyof typeof GRADE_DEFAULTS] });
  for (const [k, v] of Object.entries(c.fx ?? {})) if (isAnimated(v)) out.push({ path: `fx.${k}`, label: k, keys: v, def: FX_DEFAULTS[k as keyof typeof FX_DEFAULTS] });
  if (isAnimated(c.volume)) out.push({ path: "volume", label: "volume", keys: c.volume, def: 1 });
  return out;
}

function liveKeys(p: Project, id: string, path: string): Keyframe[] | null {
  const c = ops.getClip(p, id);
  const t = ops.parseTarget(path);
  const a = t.group === "volume" ? c.volume : t.group === "transform" ? c.transform[t.prop] : t.group === "fx" ? c.fx?.[t.prop] : c.grade[t.prop];
  return Array.isArray(a) ? a : null;
}

export class GraphEditor {
  readonly el: HTMLElement;
  private canvas: HTMLCanvasElement;
  private chips: HTMLElement;
  private hidden = new Set<string>();
  private curves: Curve[] = [];
  private drag: { path: string; idx: number; t0: number; v0: number; x0: number; y0: number } | null = null;
  private raf = 0;
  private PAD = { l: 52, r: 16, t: 14, b: 22 };

  constructor(private readonly s: Studio) {
    this.canvas = h("canvas", { style: "position:absolute;left:0;top:0;display:block" }) as HTMLCanvasElement;
    this.chips = h("div", { class: "row wrap", style: "padding:6px 10px;gap:4px;border-bottom:1px solid var(--line)" });
    const wrap = h("div", { style: "flex:1;min-height:0;position:relative" }, this.canvas);
    this.el = h("div", { style: "flex:1;min-height:0;display:flex;flex-direction:column" }, this.chips, wrap);
    new ResizeObserver(() => {
      const dpr = devicePixelRatio || 1;
      if (this.canvas.width === Math.max(1, wrap.clientWidth * dpr) && this.canvas.height === Math.max(1, wrap.clientHeight * dpr)) return;
      this.canvas.width = Math.max(1, wrap.clientWidth * dpr);
      this.canvas.height = Math.max(1, wrap.clientHeight * dpr);
      this.canvas.style.width = `${wrap.clientWidth}px`;
      this.canvas.style.height = `${wrap.clientHeight}px`;
      this.redraw();
    }).observe(wrap);
    for (const e of ["change", "select", "time", "project"] as const) s.on(e, () => this.redraw());
    this.events();
  }

  private clip(): Clip | null {
    return this.s.selection ? findClip(this.s.project, this.s.selection)?.clip ?? null : null;
  }

  redraw(): void {
    if (this.raf || !this.el.isConnected) return;
    this.raf = requestAnimationFrame(() => {
      this.raf = 0;
      this.draw();
    });
  }

  private geom() {
    const dpr = devicePixelRatio || 1;
    const W = this.canvas.width / dpr;
    const H = this.canvas.height / dpr;
    const c = this.clip();
    const dur = Math.max(0.1, c?.dur ?? 1);
    const P = this.PAD;
    return { W, H, dur, x: (t: number) => P.l + (t / dur) * (W - P.l - P.r), t: (x: number) => ((x - P.l) / (W - P.l - P.r)) * dur, y: (cv: Curve, v: number) => P.t + (1 - (v - cv.lo) / (cv.hi - cv.lo)) * (H - P.t - P.b), v: (cv: Curve, y: number) => cv.lo + (1 - (y - P.t) / (H - P.t - P.b)) * (cv.hi - cv.lo) };
  }

  private build() {
    const c = this.clip();
    const raw = c ? curvesOf(c) : [];
    this.curves = raw.map((r, i) => {
      let lo = Infinity, hi = -Infinity;
      for (const k of r.keys) (lo = Math.min(lo, k.v)), (hi = Math.max(hi, k.v));
      // Room for overshoot eases.
      const span = hi - lo || Math.max(1, Math.abs(hi) * 0.5);
      return { ...r, color: COLORS[i % COLORS.length]!, lo: lo - span * 0.18, hi: hi + span * 0.18 };
    });
    const sig = this.curves.map((c2) => c2.path).join("|");
    if (this.chips.dataset.sig !== sig) {
      this.chips.dataset.sig = sig;
      this.chips.replaceChildren(
        ...(this.curves.length
          ? this.curves.map((cv) => {
              const chip = h("button", { class: `chip ${this.hidden.has(cv.path) ? "" : "on"}`, type: "button" }, h("span", { class: "dot", style: `background:${cv.color};box-shadow:0 0 8px ${cv.color}` }), cv.label);
              chip.addEventListener("click", () => {
                if (this.hidden.has(cv.path)) this.hidden.delete(cv.path);
                else this.hidden.add(cv.path);
                chip.classList.toggle("on");
                this.redraw();
              });
              return chip;
            })
          : [h("span", { class: "muted", style: "font-size:12px" }, c ? "No animated properties on this clip yet — press a ◇ in the inspector, or ask Claude for keys.expr." : "Select a clip to see its animation curves.")]),
      );
    }
  }

  private draw() {
    this.build();
    const dpr = devicePixelRatio || 1;
    const g = this.canvas.getContext("2d")!;
    const G = this.geom();
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, G.W, G.H);
    const c = this.clip();
    const P = this.PAD;
    // Grid.
    g.strokeStyle = "rgba(255,255,255,0.05)";
    g.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = P.t + ((G.H - P.t - P.b) * i) / 4;
      g.beginPath();
      g.moveTo(P.l, y + 0.5);
      g.lineTo(G.W - P.r, y + 0.5);
      g.stroke();
    }
    const steps = Math.min(20, Math.max(2, Math.round(G.dur)));
    g.fillStyle = "rgba(255,255,255,0.4)";
    g.font = "10px JetBrains Mono, monospace";
    for (let i = 0; i <= steps; i++) {
      const t = (G.dur * i) / steps;
      const x = G.x(t);
      g.fillStyle = "rgba(255,255,255,0.05)";
      g.fillRect(Math.round(x), P.t, 1, G.H - P.t - P.b);
      if (i % Math.ceil(steps / 8) === 0) {
        g.fillStyle = "rgba(255,255,255,0.4)";
        g.fillText(c ? tc(c.start + t, this.s.project.fps).slice(3) : "", x + 2, G.H - 6);
      }
    }
    if (!c) return;
    // Curves.
    for (const cv of this.curves) {
      if (this.hidden.has(cv.path)) continue;
      g.strokeStyle = cv.color;
      g.lineWidth = 2;
      g.shadowColor = cv.color;
      g.shadowBlur = 6;
      g.beginPath();
      for (let x = P.l; x <= G.W - P.r; x += 2) {
        const v = valueAt(cv.keys, G.t(x), cv.def);
        const y = G.y(cv, v);
        if (x === P.l) g.moveTo(x, y);
        else g.lineTo(x, y);
      }
      g.stroke();
      g.shadowBlur = 0;
      const dense = cv.keys.length > 1 && (G.x(cv.keys[cv.keys.length - 1]!.t) - G.x(cv.keys[0]!.t)) / cv.keys.length < 12;
      for (const k of cv.keys) {
        const x = G.x(k.t);
        const y = G.y(cv, k.v);
        if (dense) {
          g.fillStyle = cv.color;
          g.fillRect(x - 1.5, y - 1.5, 3, 3);
          continue;
        }
        g.fillStyle = "#0b0f15";
        g.strokeStyle = cv.color;
        g.lineWidth = 2;
        g.beginPath();
        g.moveTo(x, y - 6);
        g.lineTo(x + 6, y);
        g.lineTo(x, y + 6);
        g.lineTo(x - 6, y);
        g.closePath();
        g.fill();
        g.stroke();
      }
      // Range labels on the left for the first visible curve.
    }
    const first = this.curves.find((cv) => !this.hidden.has(cv.path));
    if (first) {
      g.fillStyle = first.color;
      g.font = "10px JetBrains Mono, monospace";
      g.fillText(fmt(first.hi), 4, P.t + 8);
      g.fillText(fmt((first.hi + first.lo) / 2), 4, (G.H - P.b + P.t) / 2 + 3);
      g.fillText(fmt(first.lo), 4, G.H - P.b);
    }
    // Playhead.
    const t = this.s.time - c.start;
    if (t >= 0 && t <= c.dur) {
      g.fillStyle = "#3ee6c4";
      g.fillRect(Math.round(G.x(t)), P.t, 1.5, G.H - P.t - P.b);
    }
  }

  private hitKey(x: number, y: number): { cv: Curve; idx: number } | null {
    const G = this.geom();
    for (const cv of [...this.curves].reverse()) {
      if (this.hidden.has(cv.path)) continue;
      for (let i = 0; i < cv.keys.length; i++) {
        const k = cv.keys[i]!;
        if (Math.abs(G.x(k.t) - x) < 8 && Math.abs(G.y(cv, k.v) - y) < 8) return { cv, idx: i };
      }
    }
    return null;
  }

  private events() {
    const pos = (e: MouseEvent) => {
      const r = this.canvas.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top };
    };
    this.canvas.addEventListener("mousedown", (e) => {
      const c = this.clip();
      if (!c || e.button !== 0) return;
      const { x, y } = pos(e);
      const hit = this.hitKey(x, y);
      if (!hit) {
        const G = this.geom();
        this.s.seek(c.start + Math.max(0, Math.min(c.dur, G.t(x))));
        return;
      }
      const k = hit.cv.keys[hit.idx]!;
      this.drag = { path: hit.cv.path, idx: hit.idx, t0: k.t, v0: k.v, x0: x, y0: y };
      const cv = hit.cv;
      const id = c.id;
      const mv = (ev: MouseEvent) => {
        const d = this.drag;
        if (!d) return;
        const p2 = pos(ev);
        const G = this.geom();
        const dt = ev.altKey ? 0 : G.t(p2.x) - G.t(d.x0);
        const dv = ev.shiftKey ? 0 : cv.lo + ((d.y0 - p2.y) / (G.H - this.PAD.t - this.PAD.b)) * (cv.hi - cv.lo) - cv.lo;
        this.s.mutate("graph key", (p) => {
          const list = liveKeys(p, id, d.path);
          if (!list) return;
          const key = list[d.idx];
          if (!key) return;
          const prev = list[d.idx - 1];
          const next = list[d.idx + 1];
          const fr = 1 / p.fps;
          key.t = Math.max(prev ? prev.t + fr : 0, Math.min(next ? next.t - fr : c.dur, d.t0 + dt));
          key.v = d.v0 + dv;
        });
      };
      const up = () => {
        this.drag = null;
        removeEventListener("mousemove", mv);
        removeEventListener("mouseup", up);
      };
      addEventListener("mousemove", mv);
      addEventListener("mouseup", up);
    });
    this.canvas.addEventListener("dblclick", (e) => {
      const c = this.clip();
      if (!c) return;
      const { x, y } = pos(e);
      const G = this.geom();
      // Nearest visible curve at this time.
      const t = Math.max(0, Math.min(c.dur, G.t(x)));
      let best: Curve | null = null;
      let bd = 24;
      for (const cv of this.curves) {
        if (this.hidden.has(cv.path)) continue;
        const d = Math.abs(G.y(cv, valueAt(cv.keys, t, cv.def)) - y);
        if (d < bd) (bd = d), (best = cv);
      }
      if (!best) return;
      const cv = best;
      this.s.mutate("graph add key", (p) => ops.keyframe(p, c.id, cv.path, c.start + t, valueAt(cv.keys, t, cv.def), "inOut"));
    });
    this.canvas.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      const c = this.clip();
      if (!c) return;
      const { x, y } = pos(e);
      const hit = this.hitKey(x, y);
      if (!hit) return;
      const { cv, idx } = hit;
      const key = cv.keys[idx]!;
      menu(
        [
          ...EASES.map(([ease, label]) => ({
            label: `${key.ease === ease ? "● " : ""}${label} → next key`,
            run: () => this.s.mutate("graph ease", (p) => {
              const list = liveKeys(p, c.id, cv.path);
              if (list?.[idx]) list[idx]!.ease = ease;
            }),
          })),
          { sep: true, label: "" },
          { label: "Delete key", run: () => this.s.mutate("graph delete key", (p) => ops.unkeyframe(p, c.id, cv.path, c.start + key.t)) },
          { label: `Clear all ${cv.label} keys`, run: () => this.s.mutate("graph clear", (p) => ops.setConstant(p, c.id, cv.path, valueAt(cv.keys, this.s.time - c.start, cv.def))) },
        ],
        e.clientX,
        e.clientY,
      );
    });
  }
}

function fmt(v: number): string {
  return Math.abs(v) >= 100 ? v.toFixed(0) : Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2);
}
