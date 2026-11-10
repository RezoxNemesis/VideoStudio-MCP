/**
 * The Colour page: grading tools for the selected clip, laid out like a colour suite.
 *
 *   1. Node strip   — the clip's grading nodes left to right (select, rename, switch off, add, reorder, delete).
 *   2. Tool tabs    — Wheels | Curves | Qualifier | Window | LUT for the active node.
 *   3. Clip tools   — auto balance, shot match, copy grade, reset, and the viewer's bypass / compare wipe.
 *
 * Every number comes from the project each time it is shown, so undo, redo and edits made by Claude always show
 * their true values. Continuous drags edit through `Studio.mutate` (one stable label per control, so a drag is
 * one undo step); one-off actions go through `runCommand`, so they are undoable and acted out in the live view.
 * The page rebuilds its structure only when the clip, the node list, the active node or the open tool change;
 * otherwise it updates values in place and never replaces a control while it is being dragged.
 */
import type { Studio } from "../../../src/app/studio";
import type { Preview } from "../../../src/ui/preview";
import { runCommand } from "../../../src/agent/api";
import { applyNodes, cleanCurve, curveFn, gradeBasic, isHueCurve, isIdentityCurve, qualKey, windowAt } from "../../../src/color/engine";
import { lutTable } from "../../../src/color/luts";
import * as cops from "../../../src/color/ops";
import { MAX_NODES, QUAL_DEFAULT, WIN_DEFAULT, type ColorNode, type CurveKey, type CurvePts, type NodeBlend, type PowerWindow, type Qualifier } from "../../../src/color/types";
import { colorView, onColorView, setColorView } from "../../../src/color/view";
import { evaluateClip } from "../../../src/core/evaluate";
import { findAsset, findClip, type Clip, type Project } from "../../../src/core/model";
import { getClip } from "../../../src/core/ops";
import { vsd } from "../bridge";
import { ask, b, h, ic, menu, modal, selectEl, tabs, tc, type MenuItem } from "./kit";

type Tab = "wheels" | "curves" | "qual" | "win" | "lut";
type WheelKey = "lift" | "gamma" | "gain" | "offset";
type WinKey = "x" | "y" | "w" | "h" | "rot";
type QualNum = "h" | "hw" | "hs" | "s0" | "s1" | "ss" | "l0" | "l1" | "ls";

/** What `color.lut.list` returns. */
interface LutList {
  builtin: { id: string; name: string; group: string; about: string }[];
  project: { id: string; name: string; size?: number }[];
}

/** A value control that can be refreshed in place. */
interface Ctl {
  el: HTMLElement;
  sync: () => void;
}

const TOOL_TABS: [Tab, string][] = [
  ["wheels", "Wheels"],
  ["curves", "Curves"],
  ["qual", "Qualifier"],
  ["win", "Window"],
  ["lut", "LUT"],
];

/**
 * Where pure red sits on the colour wheels, in degrees counter-clockwise from 3 o'clock (the vectorscope's red
 * target), so pushing a wheel moves the vectorscope trace the same way. Hue increases counter-clockwise from there.
 */
const RED_AT = 103;
/** Puck travel as a share of the wheel's radius (the outer ring is the hue reference, not travel). */
const INNER = 0.84;
/** Wheel channel angles for red, green and blue: 0°, 120°, 240° of hue. */
const TH = [0, (2 * Math.PI) / 3, (4 * Math.PI) / 3] as const;

const WHEELS: { key: WheelKey; label: string; range: number; master: [number, number] | null; about: string }[] = [
  { key: "lift", label: "Lift", range: 0.25, master: [-0.5, 0.5], about: "Shadows: drag the puck to tint the dark parts; the slider raises or lowers black." },
  { key: "gamma", label: "Gamma", range: 0.5, master: [-0.75, 1.5], about: "Midtones: drag the puck to tint the middle tones; the slider brightens or darkens them." },
  { key: "gain", label: "Gain", range: 0.5, master: [-0.75, 1.5], about: "Highlights: drag the puck to tint the bright parts; the slider raises or lowers white." },
  { key: "offset", label: "Offset", range: 0.25, master: null, about: "Offset: shifts the whole picture toward a colour." },
];

const CURVE_TABS: [CurveKey, string][] = [
  ["m", "Master"],
  ["r", "R"],
  ["g", "G"],
  ["b", "B"],
  ["hh", "Hue→Hue"],
  ["hs", "Hue→Sat"],
  ["hl", "Hue→Lum"],
  ["ls", "Lum→Sat"],
  ["ss", "Sat→Sat"],
];

const CURVE_HINT: Record<CurveKey, string> = {
  m: "Output brightness for each input level, all three channels together.",
  r: "Output red for each input red level.",
  g: "Output green for each input green level.",
  b: "Output blue for each input blue level.",
  hh: "Click a hue, then drag up or down to turn it toward its neighbours (up to ±90°).",
  hs: "Click a hue, then drag up for more saturation, down for less (centre = no change).",
  hl: "Click a hue, then drag up to brighten it, down to darken it (centre = no change).",
  ls: "Saturation by brightness: left is shadows, right is highlights (centre = no change). One point moves the whole line; add a second to shape it.",
  ss: "Saturation by saturation: left is muted colours, right is vivid ones (centre = no change). One point moves the whole line; add a second to shape it.",
};

const CURVE_COLOR: Record<string, string> = { m: "#e8edf4", r: "#ff6b6b", g: "#5ee08a", b: "#6b9bff" };

const BLENDS: [NodeBlend, string][] = [
  ["normal", "Normal"],
  ["add", "Add"],
  ["multiply", "Multiply"],
  ["screen", "Screen"],
  ["overlay", "Overlay"],
  ["softLight", "Soft light"],
];

const SHAPES: [PowerWindow["shape"], string][] = [
  ["none", "None"],
  ["rect", "Rectangle"],
  ["ellipse", "Ellipse"],
  ["gradient", "Gradient"],
];

const LUT_GROUPS = ["Camera", "Film", "Creative", "Utility"];

/** Most points one curve keeps (the engine keeps 24). */
const MAX_POINTS = 24;

const clamp = (v: number, lo: number, hi: number) => (v < lo ? lo : v > hi ? hi : v);
const clamp01 = (v: number) => clamp(v, 0, 1);
const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));
const hasKey = (n: ColorNode) => !!(n.qual?.on || (n.win && n.win.shape !== "none"));

/** A number for a readout: fixed digits, no "-0.000". */
function fmtN(v: number, digits: number): string {
  const lim = 0.5 * 10 ** -digits;
  return (Math.abs(v) < lim ? 0 : v).toFixed(digits);
}

/** HSV (h in degrees, s and v 0..1) to 0..255 RGB. */
function hsv255(hDeg: number, s: number, v: number): [number, number, number] {
  const hh = (((hDeg % 360) + 360) % 360) / 60;
  const c = v * s;
  const x = c * (1 - Math.abs((hh % 2) - 1));
  const m = v - c;
  const [r, g, bb] = hh < 1 ? [c, x, 0] : hh < 2 ? [x, c, 0] : hh < 3 ? [0, c, x] : hh < 4 ? [0, x, c] : hh < 5 ? [x, 0, c] : [c, 0, x];
  return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((bb + m) * 255)];
}

/**
 * A wheel value's colour part as a puck position. The stored r, g, b are split into their mean (left untouched)
 * and a balanced part that sums to zero; the balanced part is exactly `A·cos(θ − θi)`, so it maps to one point.
 * Returns the puck in wheel units (1 = the edge of travel), u to the right and v up.
 */
function rgbToPuck(rgb: readonly number[], range: number): { u: number; v: number; mean: number } {
  const r = rgb[0] ?? 0;
  const g = rgb[1] ?? 0;
  const bl = rgb[2] ?? 0;
  const mean = (r + g + bl) / 3;
  const bal = [r - mean, g - mean, bl - mean];
  let x = 0;
  let y = 0;
  for (let i = 0; i < 3; i++) {
    x += bal[i]! * Math.cos(TH[i]!);
    y += bal[i]! * Math.sin(TH[i]!);
  }
  const amp = (2 / 3) * Math.hypot(x, y);
  const phi = Math.atan2(y, x) + (RED_AT * Math.PI) / 180;
  const rad = amp / range;
  return { u: rad * Math.cos(phi), v: rad * Math.sin(phi), mean };
}

/** The reverse of `rgbToPuck`: rgb[i] = mean + r·RANGE·cos(θ − θi), θ the hue angle under the puck. */
function puckToRgb(u: number, v: number, range: number, mean: number): [number, number, number] {
  const rad = Math.hypot(u, v);
  const th = Math.atan2(v, u) - (RED_AT * Math.PI) / 180;
  return [mean + rad * range * Math.cos(th - TH[0]), mean + rad * range * Math.cos(th - TH[1]), mean + rad * range * Math.cos(th - TH[2])];
}

const discCache = new Map<number, HTMLCanvasElement>();
/** The wheel's picture at `px` pixels: a muted hue disc (saturation outward) inside a brighter hue ring. Cached. */
function wheelDisc(px: number): HTMLCanvasElement {
  const hit = discCache.get(px);
  if (hit) return hit;
  const c = document.createElement("canvas");
  c.width = px;
  c.height = px;
  const g = c.getContext("2d")!;
  const img = g.createImageData(px, px);
  const d = img.data;
  const R = px / 2;
  const ring0 = 0.9;
  const edge = 1 / R;
  for (let y = 0; y < px; y++) {
    for (let x = 0; x < px; x++) {
      const dx = (x + 0.5 - R) / R;
      const dy = (R - (y + 0.5)) / R;
      const r = Math.hypot(dx, dy);
      if (r > 1 + edge) continue;
      const hue = (Math.atan2(dy, dx) * 180) / Math.PI - RED_AT;
      let rgb: [number, number, number];
      let a = clamp01((1 - r) / edge + 0.5);
      if (r >= ring0) rgb = hsv255(hue, 0.78, 0.72);
      else if (r >= ring0 - 0.035) {
        // A dark gap between the disc and the ring.
        rgb = [10, 12, 16];
        a = 1;
      } else {
        const k = r / (ring0 - 0.035);
        rgb = hsv255(hue, 0.62 * k ** 1.35, 0.2 + 0.13 * k);
      }
      const o = (y * px + x) * 4;
      d[o] = rgb[0];
      d[o + 1] = rgb[1];
      d[o + 2] = rgb[2];
      d[o + 3] = Math.round(a * 255);
    }
  }
  g.putImageData(img, 0, 0);
  if (discCache.size > 12) discCache.delete(discCache.keys().next().value as number);
  discCache.set(px, c);
  return c;
}

/** Canvas pixel ratio (capped like the viewer's). */
const dprOf = () => Math.min(2, window.devicePixelRatio || 1);

/** Size a canvas's backing store to its CSS box; returns true when it changed. */
function fitCanvas(cv: HTMLCanvasElement): boolean {
  const dpr = dprOf();
  const w = Math.max(2, Math.round(cv.clientWidth * dpr));
  const hh = Math.max(2, Math.round(cv.clientHeight * dpr));
  if (cv.width === w && cv.height === hh) return false;
  cv.width = w;
  cv.height = hh;
  return true;
}

/** Same curve, point for point (both already cleaned). */
function sameCurve(a: CurvePts | null | undefined, bb: CurvePts | null | undefined): boolean {
  if (!a || !bb) return !a && !bb;
  if (a.length !== bb.length) return false;
  for (let i = 0; i < a.length; i++) if (Math.abs(a[i]![0] - bb[i]![0]) > 1e-6 || Math.abs(a[i]![1] - bb[i]![1]) > 1e-6) return false;
  return true;
}

export class ColorPage {
  readonly el: HTMLElement;

  private tab: Tab = "wheels";
  private curveKey: CurveKey = "m";
  /** The node shown for each clip (by id), so coming back to a clip shows the node you left. */
  private activeByClip = new Map<string, string>();
  /** Points being edited per clip|node|curve: the engine drops identity curves, the editor keeps the points. */
  private drafts = new Map<string, CurvePts>();
  private presetK = 1;
  private lastPreset: { nodeId: string; key: CurveKey; name: string } | null = null;
  private wipe = 0.5;

  private lastSig = "";
  private lastFocus = "";
  private ups: (() => void)[] = [];
  private cleanups: (() => void)[] = [];
  private offs: (() => void)[] = [];
  private raf = 0;
  private hidden = false;
  private dirty = false;
  /** A pointer is down inside the page: structure changes wait for it to come up. */
  private interacting = false;
  private pendingBuild = false;
  /** The control being dragged right now; it is not refreshed from the project while it is. */
  private dragTok: object | null = null;

  private luts: LutList | null = null;
  private lutsFor: string | null = null;
  private lutsLoading = false;

  /** The clip and node a drag started on: the drag keeps writing there (or stops quietly once they are gone). */
  private dragTarget: { clipId: string; nodeId: string } | null = null;
  /** Rename on a second click: kept on the page because selecting a chip rebuilds the strip. */
  private lastChipClick = { id: "", at: 0 };
  /** Whether the shell shows the page, and whether the window itself is visible. */
  private shownFlag = true;

  /**
   * The eyedropper. `set` records what it changed in the viewer (and the value it put there), so cancelling puts
   * back only those, and only if nobody changed them since. `reading` is true while the pixel is being read.
   */
  private pick: {
    started: number;
    clipId: string;
    nodeId: string;
    reading: boolean;
    set: { bypass?: { was: boolean }; showKey?: { was: { clipId: string; nodeId: string } | null } };
    down: (e: PointerEvent) => void;
    click: (e: MouseEvent) => void;
    key: (e: KeyboardEvent) => void;
  } | null = null;

  private readonly onVisibility = () => this.setHidden(!this.shownFlag || document.hidden);

  private readonly onUp = () => {
    this.dragTok = null;
    this.dragTarget = null;
    if (!this.interacting) return;
    this.interacting = false;
    if (this.pendingBuild) this.schedule();
  };

  constructor(
    private readonly s: Studio,
    private readonly preview: Preview,
  ) {
    this.el = h("div", { class: "cp" });
    this.el.addEventListener("pointerdown", () => (this.interacting = true), true);
    window.addEventListener("pointerup", this.onUp);
    window.addEventListener("pointercancel", this.onUp);
    window.addEventListener("blur", this.onUp);
    const req = () => this.schedule();
    this.offs.push(s.on("select", req), s.on("change", req), s.on("project", req), s.on("time", req), onColorView(req));
    // The window being minimised or hidden counts as hidden too (the shell reports workspace switches).
    document.addEventListener("visibilitychange", this.onVisibility);
    this.offs.push(() => document.removeEventListener("visibilitychange", this.onVisibility));
    this.build();
  }

  /**
   * The shell tells the page whether its workspace is on screen. Leaving the page puts the viewer back to normal
   * (no bypass, no compare wipe, no key view) so other pages never show an ungraded picture, and stops the eyedropper.
   */
  shown(visible: boolean): void {
    this.shownFlag = visible;
    if (!visible) {
      this.cancelPick();
      if (colorView.bypass || colorView.split >= 0 || colorView.showKey) setColorView({ bypass: false, split: -1, showKey: null });
    }
    this.onVisibility();
  }

  /** Remove every listener this page registered (window, studio, colour view) and stop any eyedropper. */
  dispose(): void {
    this.cancelPick();
    cancelAnimationFrame(this.raf);
    this.raf = 0;
    window.removeEventListener("pointerup", this.onUp);
    window.removeEventListener("pointercancel", this.onUp);
    window.removeEventListener("blur", this.onUp);
    for (const f of this.cleanups) f();
    this.cleanups = [];
    for (const f of this.offs) f();
    this.offs = [];
    this.ups = [];
  }

  private setHidden(hidden: boolean): void {
    if (hidden === this.hidden) return;
    this.hidden = hidden;
    if (hidden) this.cancelPick();
    else if (this.dirty) this.schedule();
  }

  /* ------------------------------------------------------------------ state */

  /** The selected clip when it is a picture clip. */
  private clip(p: Project = this.s.project): Clip | null {
    const id = this.s.selection;
    const f = id ? findClip(p, id) : null;
    if (!f || f.track.kind !== "video" || !cops.isPicture(p, f.clip)) return null;
    return f.clip;
  }

  /** The active node of a clip: the one picked before, otherwise the last one. */
  private node(c: Clip): ColorNode | null {
    const nodes = c.nodes ?? [];
    if (!nodes.length) return null;
    const want = this.activeByClip.get(c.id);
    const n = nodes.find((x) => x.id === want) ?? nodes[nodes.length - 1]!;
    this.activeByClip.set(c.id, n.id);
    return n;
  }

  /** The clip and active node as they are in the project right now. */
  private cur(): { c: Clip; n: ColorNode | null } | null {
    const c = this.clip();
    return c ? { c, n: this.node(c) } : null;
  }

  private local(c: Clip): number {
    return clamp(this.s.time - c.start, 0, c.dur);
  }

  private frame(): number {
    return 1 / Math.max(1, this.s.project.fps || 30);
  }

  private selectNode(clipId: string, nodeId: string | undefined): void {
    if (!nodeId) return;
    this.activeByClip.set(clipId, nodeId);
    this.schedule();
  }

  /** A drag starts: remember the control (so it is not refreshed under the pointer) and the clip and node it edits. */
  private beginDrag(tok: object): void {
    this.dragTok = tok;
    const cur = this.cur();
    this.dragTarget = cur?.n ? { clipId: cur.c.id, nodeId: cur.n.id } : null;
  }

  /**
   * Edit a node directly (continuous drags). During a drag it is the clip and node the drag started on; otherwise
   * the active node. The node is looked up by id inside the edit. Returns false when there is nothing to edit (for
   * a drag: its clip or node was deleted or undone away), quietly.
   */
  private edit(label: string, fn: (p: Project, c: Clip, n: ColorNode) => void): boolean {
    let target = this.dragTok ? this.dragTarget : null;
    if (!target) {
      const cur = this.cur();
      if (!cur?.n) return false;
      target = { clipId: cur.c.id, nodeId: cur.n.id };
    }
    const { clipId, nodeId } = target;
    const f = findClip(this.s.project, clipId);
    if (!f || !(f.clip.nodes ?? []).some((x) => x.id === nodeId)) return false;
    try {
      this.s.mutate(label, (p) => {
        const c = getClip(p, clipId);
        fn(p, c, cops.findNode(c, nodeId).node);
      });
      return true;
    } catch (e) {
      this.s.setStatus(errText(e));
      return false;
    }
  }

  /** Run a command (undoable, shown in the live view). Returns its result, or undefined after showing the error. */
  private async cmd(op: string, args: Record<string, unknown>, busy?: string): Promise<unknown> {
    if (busy) this.s.setStatus(busy);
    const r = await runCommand(this.s, { op, ...args });
    if (!r.ok) {
      this.s.setStatus(r.error ?? `${op} did not work`);
      return undefined;
    }
    if (busy) this.s.setStatus("");
    return r.result ?? true;
  }

  /** A command on the active node of the selected clip. */
  private async nodeCmd(op: string, args: Record<string, unknown> = {}, busy?: string): Promise<unknown> {
    const cur = this.cur();
    if (!cur?.n) return undefined;
    return await this.cmd(op, { id: cur.c.id, node: cur.n.id, ...args }, busy);
  }

  /* ------------------------------------------------------------------ refresh */

  private schedule(): void {
    if (this.raf) return;
    this.raf = requestAnimationFrame(() => {
      this.raf = 0;
      this.refresh();
    });
  }

  /** What decides the page's structure. Anything else is a value updated in place. */
  private sig(): string {
    const c = this.clip();
    if (!c) return "none";
    const n = this.node(c);
    let tool: string = this.tab;
    if (n) {
      if (this.tab === "curves") tool += `:${this.curveKey}`;
      if (this.tab === "win") tool += `:${!n.win || n.win.shape === "none" ? "none" : n.win.shape === "gradient" ? "grad" : "box"}`;
      if (this.tab === "lut") tool += `:${this.lutsFor ?? "-"}:${this.projectLutSig()}`;
    }
    return [c.id, (c.nodes ?? []).map((x) => x.id).join(","), n?.id ?? "", n?.kind ?? "", tool].join("|");
  }

  private refresh(): void {
    if (this.hidden || !this.el.isConnected) {
      this.dirty = true;
      return;
    }
    this.dirty = false;
    // A different clip or node: the key view and the eyedropper belonged to the old one.
    const c = this.clip();
    const focus = c ? `${c.id}|${this.node(c)?.id ?? ""}` : "";
    if (focus !== this.lastFocus) {
      this.lastFocus = focus;
      this.cancelPick();
      if (colorView.showKey) setColorView({ showKey: null });
    }
    const sig = this.sig();
    if (sig !== this.lastSig) {
      if (this.interacting) this.pendingBuild = true;
      else return this.build();
    }
    for (const f of this.ups) {
      try {
        f();
      } catch (e) {
        console.error(e);
      }
    }
  }

  private build(): void {
    this.pendingBuild = false;
    for (const f of this.cleanups) f();
    this.cleanups = [];
    this.ups = [];
    this.lastSig = this.sig();
    const cur = this.cur();
    if (!cur) {
      this.el.replaceChildren(h("div", { class: "cp-empty" }, ic("color", 22), h("span", {}, "Select a clip on the timeline to grade it")));
      return;
    }
    const { c, n } = cur;
    const name = h("span", { class: "cp-clipname" });
    const count = h("span", { class: "cp-count mono" });
    const head = h("div", { class: "cp-head" }, name, h("span", { class: "sp" }), count);
    this.ups.push(() => {
      const cl = this.clip();
      const nm = cl?.name || "Clip";
      if (name.textContent !== nm) {
        name.textContent = nm;
        name.title = nm;
      }
      const k = cl?.nodes?.length ?? 0;
      const t = `${k} / ${MAX_NODES} node${k === 1 ? "" : "s"}`;
      if (count.textContent !== t) count.textContent = t;
    });
    const parts: HTMLElement[] = [head, this.stripSection(c, n)];
    if (n) parts.push(this.toolSection());
    parts.push(this.clipTools());
    this.el.replaceChildren(...parts);
    for (const f of this.ups) f();
  }

  /** A "show selection" toggle for the active node's key (qualifier and window share it). */
  private keyToggle(): HTMLButtonElement {
    const bt = b("Show selection", "eye", () => {
      const cur = this.cur();
      if (!cur?.n) return;
      const on = colorView.showKey?.clipId === cur.c.id && colorView.showKey.nodeId === cur.n.id;
      setColorView({ showKey: on ? null : { clipId: cur.c.id, nodeId: cur.n.id } });
    }, "sm", "Show what this node selects: the picture where it works, grey elsewhere");
    this.ups.push(() => {
      const cur = this.cur();
      bt.classList.toggle("on", !!cur?.n && colorView.showKey?.clipId === cur.c.id && colorView.showKey.nodeId === cur.n.id);
    });
    return bt;
  }

  /* ------------------------------------------------------------------ controls */

  /**
   * A labelled slider with a number box. `get` reads the project, `set` writes it (continuous). The label scrubs
   * (Shift = fine); double-click the label or the slider to reset. `scale` shows the value multiplied (e.g. 360
   * to show hue fractions as degrees).
   */
  private slider(o: {
    label: string;
    min: number;
    max: number;
    step: number;
    def: number;
    get: () => number | null;
    set: (v: number) => void;
    scale?: number;
    unit?: string;
    digits?: number;
    title?: string;
    compact?: boolean;
    cls?: string;
    enabled?: () => boolean;
  }): Ctl {
    const tok = {};
    const scale = o.scale ?? 1;
    const digits = o.digits ?? 3;
    const tip = `${o.title ?? o.label} · double-click to reset`;
    const range = h("input", { type: "range", min: String(o.min), max: String(o.max), step: String(o.step), "aria-label": o.label, title: tip, class: o.cls ?? "" }) as HTMLInputElement;
    const num = h("input", { class: "cp-num", type: "text", spellcheck: "false", "aria-label": `${o.label} value` }) as HTMLInputElement;
    const fmt = (v: number) => `${fmtN(v * scale, digits)}${o.unit ?? ""}`;
    // Last state written, so refreshing on every playhead tick touches the DOM only when something changed.
    let lastV = NaN;
    let lastOn: boolean | null = null;
    const fill = (v: number) => range.style.setProperty("--fill", `${clamp(((v - o.min) / (o.max - o.min)) * 100, 0, 100)}%`);
    const show = (v: number) => {
      range.value = String(v);
      fill(v);
      if (document.activeElement !== num) num.value = fmt(v);
    };
    const put = (v: number) => {
      lastV = NaN;
      const x = clamp(v, o.min, o.max);
      show(x);
      o.set(x);
    };
    range.addEventListener("pointerdown", () => this.beginDrag(tok));
    range.addEventListener("input", () => {
      lastV = NaN;
      const v = Number(range.value);
      fill(v);
      num.value = fmt(v);
      o.set(v);
    });
    range.addEventListener("dblclick", () => put(o.def));
    num.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key === "Enter") num.blur();
      if (e.key === "Escape") {
        num.value = fmt(o.get() ?? o.def);
        num.blur();
      }
    });
    num.addEventListener("focus", () => num.select());
    num.addEventListener("change", () => {
      const v = parseFloat(num.value.replace(",", "."));
      if (Number.isFinite(v)) put(v / scale);
      else num.value = fmt(o.get() ?? o.def);
    });
    const el = h("div", { class: `cp-row ${o.compact ? "compact" : ""}` });
    if (!o.compact) {
      const lab = h("label", { title: `${tip} · drag to scrub (Shift = fine)` }, o.label);
      let scrub: { x: number; v: number } | null = null;
      lab.addEventListener("pointerdown", (e) => {
        if (e.button !== 0 || range.disabled) return;
        lab.setPointerCapture(e.pointerId);
        this.beginDrag(tok);
        scrub = { x: e.clientX, v: Number(range.value) };
      });
      lab.addEventListener("pointermove", (e) => {
        if (!scrub) return;
        // Re-anchor on every move so holding Shift mid-drag slows from where the value is.
        const dv = ((e.clientX - scrub.x) / 260) * (o.max - o.min) * (e.shiftKey ? 0.2 : 1);
        scrub.x = e.clientX;
        scrub.v = clamp(scrub.v + dv, o.min, o.max);
        const v = Math.round(scrub.v / o.step) * o.step;
        lastV = NaN;
        show(v);
        o.set(v);
      });
      const end = () => (scrub = null);
      lab.addEventListener("pointerup", end);
      lab.addEventListener("pointercancel", end);
      lab.addEventListener("dblclick", () => put(o.def));
      el.append(lab);
    }
    el.append(range, num);
    const sync = () => {
      const on = o.enabled ? o.enabled() : true;
      if (on !== lastOn) {
        lastOn = on;
        range.disabled = !on;
        num.disabled = !on;
        el.classList.toggle("off", !on);
      }
      if (this.dragTok === tok) return;
      const v = o.get() ?? o.def;
      if (v === lastV && document.activeElement !== num) return;
      lastV = v;
      show(v);
    };
    this.ups.push(sync);
    return { el, sync };
  }

  /** A checkbox row bound to the project. */
  private check(label: string, title: string, get: () => boolean | null, set: (v: boolean) => void, enabled?: () => boolean): HTMLElement {
    const box = h("input", { type: "checkbox", "aria-label": label }) as HTMLInputElement;
    box.addEventListener("change", () => set(box.checked));
    const el = h("label", { class: "cp-check", title }, box, h("span", {}, label));
    this.ups.push(() => {
      const on = enabled ? enabled() : true;
      box.disabled = !on;
      el.classList.toggle("off", !on);
      box.checked = !!get();
    });
    return el;
  }

  /** Open a menu under a button; long lists scroll. */
  private menuAt(anchor: HTMLElement, items: MenuItem[]): void {
    const r = anchor.getBoundingClientRect();
    menu(items, r.left, r.bottom + 4);
    const m = document.body.lastElementChild as HTMLElement | null;
    if (m?.classList.contains("menu")) {
      m.classList.add("cp-menu");
      const mr = m.getBoundingClientRect();
      m.style.top = `${Math.max(6, Math.min(r.bottom + 4, innerHeight - mr.height - 6))}px`;
    }
  }

  /* ------------------------------------------------------------------ 1. node strip */

  private stripSection(c0: Clip, n0: ColorNode | null): HTMLElement {
    const clipId = c0.id;
    const sec = h("div", { class: "cp-sec cp-nodes" });
    if (!c0.nodes?.length) {
      const first = b("Add first node", "plus", () => void this.addNode("serial"), "pri", "Add a grading node to this clip");
      sec.append(h("div", { class: "cp-first" }, first, h("span", { class: "hint" }, "Nodes run left to right. Each one can have its own wheels, curves, key and LUT.")));
      return sec;
    }

    const strip = h("div", { class: "cp-strip", role: "listbox", "aria-label": "Grading nodes" });
    let stripSig = "";
    const chip = (n: ColorNode, i: number, active: boolean): HTMLElement => {
      const pw = h("button", { class: "cp-chip-pw", type: "button", title: n.on ? "Switch this node off" : "Switch this node on", "aria-label": n.on ? "Switch node off" : "Switch node on" });
      pw.addEventListener("click", (e) => {
        e.stopPropagation();
        void this.cmd("color.node.set", { id: clipId, node: n.id, on: !n.on });
      });
      const el = h(
        "div",
        {
          class: `cp-chip ${active ? "on" : ""} ${n.on ? "" : "off"} ${n.kind === "layer" ? "layer" : ""}`,
          role: "option",
          tabindex: "0",
          "aria-selected": active ? "true" : "false",
          title: `${i + 1}. ${n.name}${n.kind === "layer" ? " (layer)" : ""}${n.on ? "" : " (off)"} · click to select, double-click to rename`,
        },
        h("span", { class: "cp-chip-n mono" }, String(i + 1)),
        h("span", { class: "cp-chip-name" }, n.name),
        hasKey(n) ? h("span", { class: "cp-chip-key", title: "Limited by a key (qualifier or window)" }) : null,
        n.kind === "layer" ? h("span", { class: "cp-chip-l", title: "Layer node: grades the original picture and is laid over the result" }, "L") : null,
        pw,
      );
      // Rename on a second click within the double-click time (a first click may rebuild the strip).
      el.addEventListener("click", () => {
        const now = performance.now();
        const last = this.lastChipClick;
        const again = last.id === n.id && now - last.at < 450;
        // A rename resets the pair, so a third click starts over.
        this.lastChipClick = again ? { id: "", at: 0 } : { id: n.id, at: now };
        if (again) void this.rename(clipId, n);
        else this.selectNode(clipId, n.id);
      });
      el.addEventListener("keydown", (e) => {
        // Keys handled here must not also reach the app's shortcuts (Space is play / pause).
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          e.stopPropagation();
          this.selectNode(clipId, n.id);
        } else if (e.key === "F2") {
          e.preventDefault();
          e.stopPropagation();
          void this.rename(clipId, n);
        }
      });
      return el;
    };
    const drawStrip = () => {
      const c = this.clip();
      if (!c || c.id !== clipId) return;
      const act = this.node(c);
      const nodes = c.nodes ?? [];
      const sig = nodes.map((n) => `${n.id}:${n.name}:${n.on}:${n.kind}:${hasKey(n)}`).join(",") + `|${act?.id}`;
      if (sig === stripSig) return;
      stripSig = sig;
      const kids: HTMLElement[] = [];
      nodes.forEach((n, i) => {
        if (i) kids.push(h("span", { class: `cp-link ${n.kind === "layer" ? "layer" : ""}`, "aria-hidden": "true" }));
        kids.push(chip(n, i, n.id === act?.id));
      });
      strip.replaceChildren(...kids);
      const on = strip.querySelector<HTMLElement>(".cp-chip.on");
      if (on && strip.isConnected) {
        const sr = strip.getBoundingClientRect();
        const cr = on.getBoundingClientRect();
        if (cr.left < sr.left) strip.scrollLeft -= sr.left - cr.left + 8;
        else if (cr.right > sr.right) strip.scrollLeft += cr.right - sr.right + 8;
      }
    };
    this.ups.push(drawStrip);

    const addS = b("Node", "plus", () => void this.addNode("serial"), "sm", "Add a serial node after the selected one: it grades the result so far");
    const addL = b("Layer", "layers", () => void this.addNode("layer"), "sm", "Add a layer node: it grades the original picture and is laid over the result through its key");
    const left = b("‹", null, () => void this.moveNode(-1), "sm ic", "Move the node left (earlier)");
    const right = b("›", null, () => void this.moveNode(1), "sm ic", "Move the node right (later)");
    const reset = b(null, "undo", () => void this.nodeCmd("color.node.reset").then((r) => r && this.s.setStatus("Node reset to neutral (its key was kept)")), "sm ic", "Reset the node to neutral (keeps its name, key and place)");
    const del = b(null, "trash", () => void this.deleteNode(), "sm ic", "Delete the node");
    this.ups.push(() => {
      const c = this.clip();
      const nodes = c?.nodes ?? [];
      const act = c ? this.node(c) : null;
      const i = act ? nodes.indexOf(act) : -1;
      const full = nodes.length >= MAX_NODES;
      addS.disabled = full;
      addL.disabled = full;
      addS.title = full ? `A clip holds ${MAX_NODES} nodes at most` : "Add a serial node after the selected one: it grades the result so far";
      addL.title = full ? `A clip holds ${MAX_NODES} nodes at most` : "Add a layer node: it grades the original picture and is laid over the result through its key";
      left.disabled = i <= 0;
      right.disabled = i < 0 || i >= nodes.length - 1;
      reset.disabled = !act;
      del.disabled = !act;
    });
    const bar = h("div", { class: "cp-bar" }, addS, addL, h("span", { class: "sp" }), left, right, reset, del);

    const opts = h("div", { class: "cp-nodeopts" });
    const mix = this.slider({
      label: "Mix",
      title: "Key output: how much of this node shows (0 = none, 1 = all)",
      min: 0,
      max: 1,
      step: 0.005,
      def: 1,
      get: () => this.cur()?.n?.mix ?? null,
      set: (v) => this.edit("node mix", (p, c, n) => void cops.setNode(p, c.id, n.id, { mix: v })),
    });
    opts.append(mix.el);
    if (n0?.kind === "layer") {
      const blend = selectEl<NodeBlend>(BLENDS, n0.blend, (v) => void this.nodeCmd("color.node.set", { blend: v }));
      blend.title = "How this layer node is laid over the result so far";
      blend.setAttribute("aria-label", "Layer blend mode");
      this.ups.push(() => {
        const v = this.cur()?.n?.blend;
        if (v && blend.value !== v) blend.value = v;
      });
      opts.append(h("div", { class: "cp-row" }, h("label", {}, "Blend"), blend));
    }
    sec.append(strip, bar, opts);
    return sec;
  }

  private async addNode(kind: "serial" | "layer"): Promise<void> {
    const cur = this.cur();
    if (!cur) return;
    const r = (await this.cmd("color.node.add", { id: cur.c.id, kind, ...(cur.n ? { after: cur.n.id } : {}) })) as { nodeId?: string; index?: number } | undefined;
    if (!r) return;
    this.selectNode(cur.c.id, r.nodeId);
    this.s.setStatus(`Added ${kind === "layer" ? "layer" : "serial"} node ${r.index ?? ""}`.trim());
  }

  private async moveNode(dir: -1 | 1): Promise<void> {
    const cur = this.cur();
    if (!cur?.n) return;
    const i = (cur.c.nodes ?? []).indexOf(cur.n);
    // color.node.move takes the 1-based position the node should end up at.
    const to = dir < 0 ? i : i + 2;
    if (i < 0 || (dir < 0 && i === 0) || (dir > 0 && i >= (cur.c.nodes?.length ?? 0) - 1)) return;
    await this.cmd("color.node.move", { id: cur.c.id, node: cur.n.id, to });
  }

  private async deleteNode(): Promise<void> {
    const cur = this.cur();
    if (!cur?.n) return;
    const nodes = cur.c.nodes ?? [];
    const i = nodes.indexOf(cur.n);
    const next = nodes[i + 1] ?? nodes[i - 1];
    const name = cur.n.name;
    if (!(await this.cmd("color.node.remove", { id: cur.c.id, node: cur.n.id }))) return;
    if (next) this.activeByClip.set(cur.c.id, next.id);
    this.s.setStatus(`Deleted ${name} (Ctrl+Z brings it back)`);
    this.schedule();
  }

  private async rename(clipId: string, n: ColorNode): Promise<void> {
    const name = await ask("Rename node", "Node name", n.name);
    if (name === null) return;
    const t = name.trim().slice(0, 40);
    if (!t || t === n.name) return;
    await this.cmd("color.node.set", { id: clipId, node: n.id, name: t });
  }

  /* ------------------------------------------------------------------ 2. tools */

  private toolSection(): HTMLElement {
    const t = tabs<Tab>(TOOL_TABS, this.tab, (v) => {
      this.tab = v;
      this.schedule();
    });
    t.el.classList.add("cp-tabs");
    const body = h("div", { class: "cp-tool" });
    if (this.tab === "wheels") body.append(...this.wheelsTool());
    else if (this.tab === "curves") body.append(...this.curvesTool());
    else if (this.tab === "qual") body.append(...this.qualTool());
    else if (this.tab === "win") body.append(...this.winTool());
    else body.append(...this.lutTool());
    return h("div", { class: "cp-sec cp-tools" }, t.el, body);
  }

  /* ---------------------------------------------- wheels */

  private wheelsTool(): HTMLElement[] {
    const wheels = h("div", { class: "cp-wheels" }, ...WHEELS.map((w) => this.wheel(w)));
    const num = (k: "contrast" | "pivot" | "sat" | "hue" | "temp" | "tint", label: string, min: number, max: number, step: number, def: number, title: string, unit = "", digits = 3) =>
      this.slider({ label, title, min, max, step, def, unit, digits, get: () => this.cur()?.n?.[k] ?? null, set: (v) => this.edit(k, (p, c, n) => void cops.setNode(p, c.id, n.id, { [k]: v })) }).el;
    const prim = h(
      "div",
      { class: "cp-sliders" },
      num("contrast", "Contrast", 0, 2, 0.005, 1, "Contrast around the pivot (1 = none)"),
      num("pivot", "Pivot", 0, 1, 0.005, 0.435, "The level contrast turns around"),
      num("sat", "Saturation", 0, 2, 0.005, 1, "Colour strength (1 = none, 0 = black and white)"),
      num("hue", "Hue", -180, 180, 0.5, 0, "Turn every hue around the colour wheel", "°", 1),
      num("temp", "Temp", -1, 1, 0.005, 0, "Colour temperature: warmer (right) or cooler (left)"),
      num("tint", "Tint", -1, 1, 0.005, 0, "Tint: toward green (right) or magenta (left)"),
    );
    return [wheels, prim];
  }

  private wheel(w: (typeof WHEELS)[number]): HTMLElement {
    const cv = h("canvas", { class: "cp-wheel-cv", title: `${w.about} Drag anywhere on the wheel (Shift = fine); double-click to reset the colour.`, "aria-label": `${w.label} colour wheel` }) as HTMLCanvasElement;
    const box = h("div", { class: "cp-wheel-c" }, cv);
    const read = h("div", { class: "cp-readout mono", "aria-live": "off" });
    const values = (): number[] | null => this.cur()?.n?.[w.key] ?? null;
    let shown = "";
    let raf = 0;
    let drag: { u: number; v: number; lim: number; x: number; y: number } | null = null;
    const tok = {};

    const draw = () => {
      raf = 0;
      fitCanvas(cv);
      const W = cv.width;
      const g = cv.getContext("2d")!;
      g.clearRect(0, 0, W, cv.height);
      if (W < 8) return;
      g.drawImage(wheelDisc(W), 0, 0);
      const C = W / 2;
      const R = C * INNER;
      const dpr = dprOf();
      g.strokeStyle = "rgba(255,255,255,0.10)";
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(C - R, C + 0.5);
      g.lineTo(C + R, C + 0.5);
      g.moveTo(C + 0.5, C - R);
      g.lineTo(C + 0.5, C + R);
      g.stroke();
      g.beginPath();
      g.arc(C, C, R * 0.5, 0, Math.PI * 2);
      g.stroke();
      const vals = values() ?? [0, 0, 0];
      const pk = rgbToPuck(vals, w.range);
      const rad = Math.hypot(pk.u, pk.v);
      const k = rad > 1 ? 1 / rad : 1;
      const px = C + pk.u * k * R;
      const py = C - pk.v * k * R;
      if (rad > 0.002) {
        g.strokeStyle = "rgba(255,255,255,0.45)";
        g.lineWidth = 1.2 * dpr;
        g.beginPath();
        g.moveTo(C, C);
        g.lineTo(px, py);
        g.stroke();
      }
      g.beginPath();
      g.arc(px, py, 5 * dpr, 0, Math.PI * 2);
      g.fillStyle = rad > 1 ? "rgba(255,191,94,0.9)" : "rgba(10,12,16,0.85)";
      g.fill();
      g.lineWidth = 1.6 * dpr;
      g.strokeStyle = drag ? "#3ee6c4" : "#eaf8f5";
      g.stroke();
    };
    const request = () => {
      if (!raf) raf = requestAnimationFrame(draw);
    };
    const ro = new ResizeObserver(request);
    ro.observe(cv);
    this.cleanups.push(() => {
      ro.disconnect();
      cancelAnimationFrame(raf);
    });

    const write = (pointerId: number) => {
      if (!drag) return;
      const d = drag;
      const ok = this.edit(`${w.key} wheel`, (p, c, n) => {
        const cur = n[w.key];
        // Only the balanced (colour) part follows the puck; the mean of r, g, b is kept as it was.
        const mean = ((cur[0] ?? 0) + (cur[1] ?? 0) + (cur[2] ?? 0)) / 3;
        const rgb = puckToRgb(d.u, d.v, w.range, mean);
        if (w.key === "offset") cops.setNode(p, c.id, n.id, { offset: rgb });
        else cops.setNode(p, c.id, n.id, { [w.key]: [rgb[0], rgb[1], rgb[2], cur[3] ?? 0] } as Partial<ColorNode>);
      });
      // The clip or node the drag started on is gone (deleted, undone): end the drag quietly.
      if (!ok) {
        drag = null;
        if (cv.hasPointerCapture(pointerId)) cv.releasePointerCapture(pointerId);
        request();
      }
    };
    cv.addEventListener("pointerdown", (e) => {
      if (e.button !== 0) return;
      const vals = values();
      if (!vals) return;
      cv.setPointerCapture(e.pointerId);
      this.beginDrag(tok);
      const pk = rgbToPuck(vals, w.range);
      drag = { u: pk.u, v: pk.v, lim: Math.max(1, Math.hypot(pk.u, pk.v)), x: e.clientX, y: e.clientY };
      request();
    });
    cv.addEventListener("pointermove", (e) => {
      if (!drag) return;
      const R = Math.max(10, (cv.clientWidth / 2) * INNER);
      const k = e.shiftKey ? 0.25 : 1;
      drag.u += ((e.clientX - drag.x) / R) * k;
      drag.v -= ((e.clientY - drag.y) / R) * k;
      drag.x = e.clientX;
      drag.y = e.clientY;
      const rad = Math.hypot(drag.u, drag.v);
      if (rad > drag.lim) {
        drag.u *= drag.lim / rad;
        drag.v *= drag.lim / rad;
      }
      write(e.pointerId);
    });
    const end = () => {
      if (!drag) return;
      drag = null;
      request();
    };
    cv.addEventListener("pointerup", end);
    cv.addEventListener("pointercancel", end);
    cv.addEventListener("lostpointercapture", end);
    cv.addEventListener("dblclick", () => {
      const v = values();
      if (!v) return;
      void this.nodeCmd("color.node.set", { [w.key]: w.key === "offset" ? [0, 0, 0] : [0, 0, 0, v[3] ?? 0] });
    });

    const resetBtn = b(null, "undo", () => void this.nodeCmd("color.node.set", { [w.key]: w.key === "offset" ? [0, 0, 0] : [0, 0, 0, 0] }), "sm ic ghost cp-wreset", `Reset ${w.label} (colour and master)`);
    const head = h("div", { class: "cp-wheel-h" }, h("span", {}, w.label), h("span", { class: "sp" }), resetBtn);
    const parts: HTMLElement[] = [head, box];
    if (w.master) {
      const [lo, hi] = w.master;
      parts.push(
        this.slider({
          label: `${w.label} master`,
          title: `${w.label} master`,
          compact: true,
          min: lo,
          max: hi,
          step: 0.005,
          def: 0,
          get: () => values()?.[3] ?? null,
          set: (v) =>
            this.edit(`${w.key} master`, (p, c, n) => {
              const cur = n[w.key];
              cops.setNode(p, c.id, n.id, { [w.key]: [cur[0] ?? 0, cur[1] ?? 0, cur[2] ?? 0, v] } as Partial<ColorNode>);
            }),
        }).el,
      );
    } else parts.push(h("div", { class: "cp-row compact cp-nomaster", "aria-hidden": "true" }));
    parts.push(read);
    this.ups.push(() => {
      const v = values();
      const sig = v ? v.join(",") : "";
      if (sig === shown) return;
      shown = sig;
      if (v) {
        const t = (x: number | undefined) => fmtN(x ?? 0, 3);
        read.textContent = `${t(v[0])} ${t(v[1])} ${t(v[2])}${w.master ? `  ${t(v[3])}` : ""}`;
        read.title = `Red ${t(v[0])} · Green ${t(v[1])} · Blue ${t(v[2])}${w.master ? ` · Master ${t(v[3])}` : ""}`;
      }
      request();
    });
    return h("div", { class: "cp-wheel" }, ...parts);
  }

  /* ---------------------------------------------- curves */

  private draftKey(clipId: string, nodeId: string, key: CurveKey): string {
    return `${clipId}|${nodeId}|${key}`;
  }

  /**
   * The points to store for the points being edited. setCurve needs two points, so a lone point becomes a flat
   * pair (a hue curve with one point left shifts every hue alike; on Lum→Sat / Sat→Sat it lifts the whole line).
   */
  private toStore(key: CurveKey, pts: CurvePts): CurvePts | null {
    if (!pts.length) return null;
    if (pts.length === 1) {
      const [x, y] = pts[0]!;
      if (!isHueCurve(key)) return null;
      return [
        [x, y],
        [x < 0.5 ? x + 0.5 : x - 0.5, y],
      ];
    }
    return pts.map((p) => [p[0], p[1]] as [number, number]);
  }

  private curvesTool(): HTMLElement[] {
    const key = this.curveKey;
    const hue = isHueCurve(key);
    const seg = h("div", { class: "cp-seg", role: "tablist", "aria-label": "Curve" });
    const segBtns = CURVE_TABS.map(([k, label]) => {
      const bt = h("button", { type: "button", class: `${k === key ? "on" : ""} ch-${k}`, role: "tab", "aria-selected": k === key ? "true" : "false", title: CURVE_HINT[k] }, label);
      bt.addEventListener("click", () => {
        this.curveKey = k;
        this.schedule();
      });
      seg.append(bt);
      return [k, bt] as const;
    });
    this.ups.push(() => {
      const curves = this.cur()?.n?.curves;
      for (const [k, bt] of segBtns) bt.classList.toggle("mod", !!curves?.[k]);
    });

    const cv = h("canvas", { class: "cp-curve-cv", "aria-label": `${CURVE_TABS.find(([k]) => k === key)?.[1]} curve editor`, title: "Click to add a point, drag to move it (Shift = fine), double-click or right-click a point to remove it" }) as HTMLCanvasElement;
    const wrap = h("div", { class: "cp-curve" }, cv);
    let raf = 0;
    let drawn = "";
    let dragIdx = -1;
    let drag: { vx: number; vy: number; x: number; y: number } | null = null;
    const tok = {};
    const accent = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim() || "#3ee6c4";
    const color = hue ? accent : CURVE_COLOR[key] ?? "#e8edf4";

    /** The clip and node a drag started on; while dragging, the editor keeps working on them. */
    let dragIds: { clipId: string; nodeId: string } | null = null;
    const ids = (): { clipId: string; nodeId: string; n: ColorNode } | null => {
      if (dragIds) {
        const { clipId, nodeId } = dragIds;
        const n = findClip(this.s.project, clipId)?.clip.nodes?.find((x) => x.id === nodeId);
        return n ? { clipId, nodeId, n } : null;
      }
      const cur = this.cur();
      return cur?.n ? { clipId: cur.c.id, nodeId: cur.n.id, n: cur.n } : null;
    };
    const stored = (): CurvePts | null => ids()?.n.curves?.[key] ?? null;
    /** The editor's points, re-read from the project whenever the project no longer matches them. */
    const draft = (): CurvePts => {
      const id = ids();
      if (!id) return [];
      const dk = this.draftKey(id.clipId, id.nodeId, key);
      const st = stored();
      const have = this.drafts.get(dk);
      if (have) {
        // Compare what the engine would store for these points (cleaned; identity curves are not stored).
        const want = this.toStore(key, have);
        const cl = want ? cleanCurve(want) : null;
        if (sameCurve(cl && !isIdentityCurve(key, cl) ? cl : null, st)) return have;
      }
      const fresh: CurvePts = st ? st.map((p) => [p[0], p[1]] as [number, number]) : hue ? [] : [
        [0, 0],
        [1, 1],
      ];
      this.drafts.set(dk, fresh);
      return fresh;
    };
    /** Store the points; false when the clip or node is gone. */
    const commit = (pts: CurvePts): boolean => {
      const id = ids();
      if (!id) return false;
      this.drafts.set(this.draftKey(id.clipId, id.nodeId, key), pts);
      this.lastPreset = null;
      const out = this.toStore(key, pts);
      const ok = this.edit("curve", (p, c, n) => void cops.setCurve(p, c.id, n.id, key, out));
      request();
      return ok;
    };

    /** The graph area in CSS px. */
    const geo = () => {
      const W = cv.clientWidth;
      const H = cv.clientHeight;
      const pad = 10;
      const strip = hue ? 14 : 0;
      return { x0: pad, x1: W - pad, y0: pad, y1: H - pad - strip };
    };
    const toCurve = (e: { clientX: number; clientY: number }) => {
      const r = cv.getBoundingClientRect();
      const gm = geo();
      return { x: (e.clientX - r.left - gm.x0) / Math.max(1, gm.x1 - gm.x0), y: (gm.y1 - (e.clientY - r.top)) / Math.max(1, gm.y1 - gm.y0) };
    };
    const hit = (e: { clientX: number; clientY: number }): number => {
      const r = cv.getBoundingClientRect();
      const gm = geo();
      const pts = draft();
      let best = -1;
      let bd = 9;
      pts.forEach((p, i) => {
        const dx = gm.x0 + p[0] * (gm.x1 - gm.x0) - (e.clientX - r.left);
        const dy = gm.y1 - p[1] * (gm.y1 - gm.y0) - (e.clientY - r.top);
        const d = Math.hypot(dx, dy);
        if (d < bd) {
          bd = d;
          best = i;
        }
      });
      return best;
    };
    const remove = (i: number) => {
      const pts = draft().slice();
      if (!hue && (i === 0 || i === pts.length - 1)) {
        this.s.setStatus("The end points of a curve stay; drag them to set the black and white levels.");
        return;
      }
      pts.splice(i, 1);
      commit(pts);
    };

    const draw = () => {
      raf = 0;
      fitCanvas(cv);
      const g = cv.getContext("2d")!;
      const dpr = cv.width / Math.max(1, cv.clientWidth);
      const W = cv.width;
      const H = cv.height;
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.clearRect(0, 0, W, H);
      g.scale(dpr, dpr);
      const gm = geo();
      const X = (x: number) => gm.x0 + x * (gm.x1 - gm.x0);
      const Y = (y: number) => gm.y1 - y * (gm.y1 - gm.y0);
      g.fillStyle = "rgba(0,0,0,0.30)";
      g.fillRect(gm.x0, gm.y0, gm.x1 - gm.x0, gm.y1 - gm.y0);
      g.lineWidth = 1;
      g.strokeStyle = "rgba(255,255,255,0.06)";
      g.beginPath();
      const cols = hue && key !== "ls" && key !== "ss" ? 6 : 4;
      for (let i = 1; i < cols; i++) {
        const x = Math.round(X(i / cols)) + 0.5;
        g.moveTo(x, gm.y0);
        g.lineTo(x, gm.y1);
      }
      for (let i = 1; i < 4; i++) {
        const y = Math.round(Y(i / 4)) + 0.5;
        g.moveTo(gm.x0, y);
        g.lineTo(gm.x1, y);
      }
      g.stroke();
      g.strokeStyle = "rgba(255,255,255,0.12)";
      g.strokeRect(gm.x0 + 0.5, gm.y0 + 0.5, gm.x1 - gm.x0 - 1, gm.y1 - gm.y0 - 1);
      if (hue) {
        // Rest line ("no change") and the x-axis strip: hues, brightness or saturation.
        g.strokeStyle = "rgba(255,255,255,0.22)";
        g.setLineDash([4, 3]);
        g.beginPath();
        g.moveTo(gm.x0, Math.round(Y(0.5)) + 0.5);
        g.lineTo(gm.x1, Math.round(Y(0.5)) + 0.5);
        g.stroke();
        g.setLineDash([]);
        const sy = gm.y1 + 4;
        const gr = g.createLinearGradient(gm.x0, 0, gm.x1, 0);
        if (key === "ls") {
          gr.addColorStop(0, "#000");
          gr.addColorStop(1, "#fff");
        } else if (key === "ss") {
          gr.addColorStop(0, "#7d7d7d");
          gr.addColorStop(1, "#ff3d3d");
        } else for (let i = 0; i <= 12; i++) gr.addColorStop(i / 12, `hsl(${i * 30} 85% 55%)`);
        g.fillStyle = gr;
        g.fillRect(gm.x0, sy, gm.x1 - gm.x0, 8);
      } else {
        g.strokeStyle = "rgba(255,255,255,0.14)";
        g.setLineDash([3, 4]);
        g.beginPath();
        g.moveTo(X(0), Y(0));
        g.lineTo(X(1), Y(1));
        g.stroke();
        g.setLineDash([]);
        // The other custom curves, faint, so the channels can be compared.
        const all = ids()?.n.curves;
        for (const k of ["m", "r", "g", "b"] as const) {
          if (k === key || !all?.[k]) continue;
          const f = curveFn(k, all[k]);
          g.strokeStyle = CURVE_COLOR[k]!;
          g.globalAlpha = 0.28;
          g.beginPath();
          for (let i = 0; i <= 128; i++) {
            const x = i / 128;
            if (i) g.lineTo(X(x), Y(f(x)));
            else g.moveTo(X(x), Y(f(x)));
          }
          g.stroke();
          g.globalAlpha = 1;
        }
      }
      // The curve exactly as the renderer applies it.
      const f = curveFn(key, stored());
      g.save();
      g.beginPath();
      g.rect(gm.x0, gm.y0 - 2, gm.x1 - gm.x0, gm.y1 - gm.y0 + 4);
      g.clip();
      g.strokeStyle = color;
      g.lineWidth = 1.6;
      g.beginPath();
      for (let i = 0; i < 256; i++) {
        const x = i / 255;
        if (i) g.lineTo(X(x), Y(f(x)));
        else g.moveTo(X(x), Y(f(x)));
      }
      g.stroke();
      g.restore();
      draft().forEach((p, i) => {
        g.beginPath();
        g.arc(X(p[0]), Y(p[1]), i === dragIdx ? 4.5 : 3.5, 0, Math.PI * 2);
        g.fillStyle = i === dragIdx ? color : "#0b0e13";
        g.fill();
        g.lineWidth = 1.5;
        g.strokeStyle = color;
        g.stroke();
      });
    };
    const request = () => {
      if (!raf) raf = requestAnimationFrame(draw);
    };
    const ro = new ResizeObserver(request);
    ro.observe(cv);
    this.cleanups.push(() => {
      ro.disconnect();
      cancelAnimationFrame(raf);
    });
    this.ups.push(() => {
      if (this.dragTok === tok) return;
      const sig = JSON.stringify([stored(), draft(), ids()?.n.curves]);
      if (sig === drawn) return;
      drawn = sig;
      request();
    });

    cv.addEventListener("contextmenu", (e) => e.preventDefault());
    cv.addEventListener("pointerdown", (e) => {
      if (!ids()) return;
      const i0 = hit(e);
      if (e.button === 2) {
        if (i0 >= 0) remove(i0);
        return;
      }
      if (e.button !== 0) return;
      const start = ids();
      if (!start) return;
      // From here on this press is a drag on this clip and node, wherever the selection goes.
      this.beginDrag(tok);
      dragIds = { clipId: start.clipId, nodeId: start.nodeId };
      let i = i0;
      if (i < 0) {
        let pts = draft().slice();
        const pt = toCurve(e);
        const x = clamp01(pt.x);
        const near = pts.findIndex((p) => Math.abs(p[0] - x) < 0.01);
        if (near >= 0) i = near;
        else {
          if (pts.length >= MAX_POINTS) {
            this.s.setStatus(`A curve holds ${MAX_POINTS} points at most.`);
            return;
          }
          // New points start on the curve (so a click alone changes nothing), then follow the drag.
          const y = clamp01(curveFn(key, stored())(x));
          if (!pts.length && (key === "hh" || key === "hs" || key === "hl")) {
            // First point on a hue curve: neutral anchors a sixth of the circle either side, so dragging it
            // changes only the hues near the one clicked (the curve wraps round the 0/1 seam). The anchors are
            // ordinary points afterwards: they can be dragged or deleted.
            const wrap = (v: number) => (v < 0 ? v + 1 : v >= 1 ? v - 1 : v);
            pts = [
              [wrap(x - 1 / 6), 0.5],
              [x, y],
              [wrap(x + 1 / 6), 0.5],
            ];
          } else pts.push([x, y]);
          pts.sort((a, bb) => a[0] - bb[0]);
          i = pts.findIndex((p) => p[0] === x);
          if (!commit(pts)) return endDrag();
        }
      }
      const p = draft()[i];
      if (!p) return endDrag();
      cv.setPointerCapture(e.pointerId);
      dragIdx = i;
      drag = { vx: p[0], vy: p[1], x: e.clientX, y: e.clientY };
      request();
    });
    cv.addEventListener("pointermove", (e) => {
      if (!drag || dragIdx < 0) {
        cv.style.cursor = hit(e) >= 0 ? "grab" : "crosshair";
        return;
      }
      const gm = geo();
      const k = e.shiftKey ? 0.25 : 1;
      drag.vx += ((e.clientX - drag.x) / Math.max(1, gm.x1 - gm.x0)) * k;
      drag.vy -= ((e.clientY - drag.y) / Math.max(1, gm.y1 - gm.y0)) * k;
      drag.x = e.clientX;
      drag.y = e.clientY;
      const pts = draft().slice();
      if (!ids() || dragIdx >= pts.length) {
        // The clip or node the drag started on is gone (deleted, undone): end the drag quietly.
        if (cv.hasPointerCapture(e.pointerId)) cv.releasePointerCapture(e.pointerId);
        return endDrag();
      }
      const lo = dragIdx > 0 ? pts[dragIdx - 1]![0] + 0.005 : 0;
      const hi = dragIdx < pts.length - 1 ? pts[dragIdx + 1]![0] - 0.005 : 1;
      pts[dragIdx] = [clamp(drag.vx, lo, Math.max(lo, hi)), clamp01(drag.vy)];
      if (!commit(pts)) {
        if (cv.hasPointerCapture(e.pointerId)) cv.releasePointerCapture(e.pointerId);
        endDrag();
      }
    });
    function endDrag(): void {
      dragIds = null;
      end();
    }
    const end = () => {
      dragIds = null;
      if (!drag) return;
      drag = null;
      dragIdx = -1;
      drawn = "";
      request();
    };
    cv.addEventListener("pointerup", end);
    cv.addEventListener("pointercancel", end);
    cv.addEventListener("lostpointercapture", end);
    cv.addEventListener("dblclick", (e) => {
      const i = hit(e);
      if (i >= 0) remove(i);
    });

    const resetBtn = b("Reset curve", "undo", () => void this.nodeCmd("color.curve", { curve: key, reset: true }), "sm", "Put this curve back to no change");
    const row = h("div", { class: "cp-bar" }, resetBtn, h("span", { class: "sp" }));
    if (!hue) {
      const presets = b("Presets", "curve", () => {
        this.menuAt(
          presets,
          Object.keys(cops.CURVE_PRESETS).map((name) => ({
            label: name.replace(/-/g, " ").replace(/^\w/, (m) => m.toUpperCase()),
            run: () =>
              void this.nodeCmd("color.curve", { curve: key, preset: name, strength: this.presetK }).then((r) => {
                const id = ids();
                if (r && id) this.lastPreset = { nodeId: id.nodeId, key, name };
              }),
          })),
        );
      }, "sm", "Ready-made curve shapes for this channel");
      const strength = this.slider({
        label: "Strength",
        title: "Strength of the preset (moving it re-shapes the preset you just picked)",
        min: 0,
        max: 1,
        step: 0.01,
        def: 1,
        digits: 2,
        get: () => this.presetK,
        set: (v) => {
          this.presetK = v;
          const lp = this.lastPreset;
          const id = ids();
          const fn = lp ? cops.CURVE_PRESETS[lp.name] : undefined;
          if (!lp || !fn || !id || lp.nodeId !== id.nodeId || lp.key !== key) return;
          this.edit("curve preset", (p, c, n) => void cops.setCurve(p, c.id, n.id, key, fn(v)));
        },
      });
      strength.el.classList.add("cp-strength");
      row.append(presets);
      return [seg, wrap, row, strength.el, h("div", { class: "hint cp-hint" }, CURVE_HINT[key])];
    }
    return [seg, wrap, row, h("div", { class: "hint cp-hint" }, CURVE_HINT[key])];
  }

  /* ---------------------------------------------- qualifier */

  private qualTool(): HTMLElement[] {
    const q = (): Qualifier | null => this.cur()?.n?.qual ?? null;
    const setQ = (label: string, patch: Partial<Qualifier>) =>
      this.edit(label, (p, c, n) => void cops.setQualifier(p, c.id, n.id, { on: n.qual ? n.qual.on : true, ...patch }));

    const onBox = this.check("Qualifier", "Limit this node to the colours selected below", () => !!q()?.on, (v) => void this.nodeCmd("color.qualifier", { on: v }));
    const pickBtn = b(null, "dropper", () => (this.pick ? this.cancelPick() : this.startPick()), "sm ic cp-pickbtn", "Pick a colour from the viewer (shows the ungraded picture while picking; Esc cancels)");
    this.ups.push(() => pickBtn.classList.toggle("on", !!this.pick));
    const clear = b("Clear", null, () => void this.nodeCmd("color.qualifier", { off: true }), "sm ghost", "Remove the qualifier from this node");
    this.ups.push(() => (clear.disabled = !q()));
    const top = h("div", { class: "cp-bar" }, onBox, pickBtn, this.keyToggle(), h("span", { class: "sp" }), this.check("Invert", "Work on everything except the selection", () => !!q()?.invert, (v) => void this.nodeCmd("color.qualifier", { invert: v })), clear);

    const presets = h(
      "div",
      { class: "cp-chips" },
      ...Object.keys(cops.QUAL_PRESETS).map((name) => b(name.replace(/^\w/, (m) => m.toUpperCase()), null, () => void this.nodeCmd("color.qualifier", { preset: name }), "sm", `Select ${name}`)),
    );

    // Hue band: every hue, with the part the qualifier selects shown bright.
    const band = h("canvas", { class: "cp-band", "aria-hidden": "true" }) as HTMLCanvasElement;
    let bandSig = "";
    let raf = 0;
    const drawBand = () => {
      raf = 0;
      fitCanvas(band);
      const g = band.getContext("2d")!;
      const W = band.width;
      const H = band.height;
      const img = g.createImageData(W, H);
      const cur = q();
      const qq: Qualifier = { ...(cur ?? QUAL_DEFAULT), on: true, s0: 0, s1: 1, ss: 1e-4, l0: 0, l1: 1, ls: 1e-4, invert: cur?.invert ?? false };
      for (let x = 0; x < W; x++) {
        const hDeg = (x / Math.max(1, W - 1)) * 360;
        const rgb = hsv255(hDeg, 1, 1);
        // qualKey on a pure hue with open saturation and brightness ranges is the hue part of the key alone.
        const k = cur ? qualKey(qq, [rgb[0] / 255, rgb[1] / 255, rgb[2] / 255]) : 0;
        const dim = 0.22 + 0.78 * k;
        for (let y = 0; y < H; y++) {
          const o = (y * W + x) * 4;
          img.data[o] = rgb[0] * dim;
          img.data[o + 1] = rgb[1] * dim;
          img.data[o + 2] = rgb[2] * dim;
          img.data[o + 3] = 255;
        }
      }
      g.putImageData(img, 0, 0);
      if (cur) {
        g.fillStyle = "#fff";
        const cx = Math.round(cur.h * (W - 1));
        g.fillRect(cx - 1, 0, 2, H);
      }
    };
    const reqBand = () => {
      if (!raf) raf = requestAnimationFrame(drawBand);
    };
    const ro = new ResizeObserver(() => {
      bandSig = "";
      reqBand();
    });
    ro.observe(band);
    this.cleanups.push(() => {
      ro.disconnect();
      cancelAnimationFrame(raf);
    });
    this.ups.push(() => {
      const sig = JSON.stringify(q());
      if (sig === bandSig) return;
      bandSig = sig;
      reqBand();
    });

    const sl = (k: QualNum, label: string, max: number, def: number, title: string, deg = false, cls?: string) =>
      this.slider({ label, title, min: 0, max, step: deg ? 1 / 720 : 0.005, def, scale: deg ? 360 : 1, unit: deg ? "°" : "", digits: deg ? 0 : 3, cls, get: () => q()?.[k] ?? QUAL_DEFAULT[k], set: (v) => setQ(`qualifier ${k}`, { [k]: v }) }).el;
    const group = (title: string, ...kids: HTMLElement[]) => h("div", { class: "cp-group" }, h("div", { class: "cp-gtitle" }, title), ...kids);
    return [
      top,
      presets,
      group(
        "Hue",
        band,
        sl("h", "Centre", 1, QUAL_DEFAULT.h, "The hue at the middle of the selection", true, "cp-hue-range"),
        sl("hw", "Width", 0.5, QUAL_DEFAULT.hw, "Half-width of the hue range (180° = every hue)", true),
        sl("hs", "Softness", 0.5, QUAL_DEFAULT.hs, "Soft edge of the hue range", true),
      ),
      group("Saturation", sl("s0", "Low", 1, QUAL_DEFAULT.s0, "Least saturated colour selected"), sl("s1", "High", 1, QUAL_DEFAULT.s1, "Most saturated colour selected"), sl("ss", "Softness", 0.5, QUAL_DEFAULT.ss, "Soft edge of the saturation range")),
      group("Luminance", sl("l0", "Low", 1, QUAL_DEFAULT.l0, "Darkest level selected"), sl("l1", "High", 1, QUAL_DEFAULT.l1, "Brightest level selected"), sl("ls", "Softness", 0.5, QUAL_DEFAULT.ls, "Soft edge of the brightness range")),
    ];
  }

  /**
   * Eyedropper: the next click on the viewer picks a colour for the active node's qualifier. The viewer is put in
   * bypass so the pixel read is the clip's own picture; that colour is then run through the basic grade (and, for
   * a serial node, the switched-on nodes before it) with the CPU mirror of the shader, so the qualifier is built
   * around the colour the node actually receives.
   */
  private startPick(): void {
    const cur = this.cur();
    if (!cur?.n) return;
    const c = cur.c;
    if (this.s.time < c.start || this.s.time >= c.start + c.dur) {
      this.s.setStatus("Move the playhead over this clip first, so the viewer shows it.");
      return;
    }
    this.cancelPick();
    const inViewer = (e: MouseEvent) => {
      const r = this.viewerCanvas()?.getBoundingClientRect();
      return !!r && e.clientX >= r.left && e.clientX < r.right && e.clientY >= r.top && e.clientY < r.bottom;
    };
    const down = (e: PointerEvent) => {
      const pk = this.pick;
      if (!pk) return;
      if (!inViewer(e) || e.button !== 0) {
        // A click anywhere else ends picking (and does what it normally does). The eyedropper button toggles itself.
        if (!pk.reading && !(e.target instanceof Element && e.target.closest(".cp-pickbtn"))) this.cancelPick();
        return;
      }
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
      // A second click while the first is being read is ignored.
      if (pk.reading) return;
      void this.finishPick(pk, e.clientX, e.clientY);
    };
    // Swallow the click that follows a picking press, so the viewer does not act on it.
    const click = (e: MouseEvent) => {
      if (!inViewer(e)) return;
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
    };
    const key = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || !this.pick) return;
      e.preventDefault();
      e.stopPropagation();
      this.cancelPick();
      this.s.setStatus("Colour pick cancelled");
    };
    window.addEventListener("pointerdown", down, true);
    window.addEventListener("click", click, true);
    window.addEventListener("keydown", key, true);
    // Change only what picking needs, and remember it, so cancelling restores exactly that.
    const set: NonNullable<ColorPage["pick"]>["set"] = {};
    const patch: Parameters<typeof setColorView>[0] = {};
    if (!colorView.bypass) {
      set.bypass = { was: false };
      patch.bypass = true;
    }
    if (colorView.showKey) {
      set.showKey = { was: { ...colorView.showKey } };
      patch.showKey = null;
    }
    this.pick = { started: performance.now(), clipId: c.id, nodeId: cur.n.id, reading: false, set, down, click, key };
    this.preview.el.classList.add("cp-picking");
    if (Object.keys(patch).length) setColorView(patch);
    this.s.setStatus("Click the picture to pick a colour (Esc cancels)");
    this.schedule();
  }

  /** End picking. Puts back only what the eyedropper changed, and only where nobody has changed it since. */
  private cancelPick(): void {
    const pk = this.pick;
    if (!pk) return;
    this.pick = null;
    window.removeEventListener("pointerdown", pk.down, true);
    window.removeEventListener("keydown", pk.key, true);
    // The click of the picking press arrives after pointerdown: remove its swallower a moment later.
    setTimeout(() => window.removeEventListener("click", pk.click, true), 400);
    this.preview.el.classList.remove("cp-picking");
    const patch: Parameters<typeof setColorView>[0] = {};
    if (pk.set.bypass && colorView.bypass) patch.bypass = pk.set.bypass.was;
    if (pk.set.showKey && colorView.showKey === null) patch.showKey = pk.set.showKey.was;
    if (Object.keys(patch).length) setColorView(patch);
    this.schedule();
  }

  private viewerCanvas(): HTMLCanvasElement | null {
    return this.preview.el.querySelector<HTMLCanvasElement>("canvas.pv-canvas") ?? this.preview.el.querySelector<HTMLCanvasElement>("canvas");
  }

  private async finishPick(pk: NonNullable<ColorPage["pick"]>, clientX: number, clientY: number): Promise<void> {
    pk.reading = true;
    // The viewer must have drawn the bypassed frame before it is read.
    const wait = 250 - (performance.now() - pk.started);
    if (wait > 0) await new Promise((r) => setTimeout(r, wait));
    // Esc (or leaving the page) during the wait cancels: nothing is set.
    if (this.pick !== pk) return;
    const cv = this.viewerCanvas();
    if (!cv) return this.cancelPick();
    const r = cv.getBoundingClientRect();
    const vr = this.preview.viewRect();
    const lx = clientX - r.left;
    const ly = clientY - r.top;
    if (lx < vr.x || ly < vr.y || lx >= vr.x + vr.w || ly >= vr.y + vr.h) {
      pk.reading = false;
      this.s.setStatus("That is outside the picture. Click inside the frame (Esc cancels).");
      return;
    }
    const kx = cv.width / Math.max(1, r.width);
    const ky = cv.height / Math.max(1, r.height);
    const sx = Math.round(lx * kx);
    const sy = Math.round(ly * ky);
    let raw: [number, number, number] | null = null;
    try {
      const tmp = document.createElement("canvas");
      tmp.width = 3;
      tmp.height = 3;
      const g = tmp.getContext("2d", { willReadFrequently: true })!;
      // Average 3×3 pixels so noise or grain does not decide the pick.
      g.drawImage(cv, Math.max(0, sx - 1), Math.max(0, sy - 1), 3, 3, 0, 0, 3, 3);
      const d = g.getImageData(0, 0, 3, 3).data;
      let rr = 0;
      let gg = 0;
      let bb = 0;
      for (let i = 0; i < 36; i += 4) {
        rr += d[i]!;
        gg += d[i + 1]!;
        bb += d[i + 2]!;
      }
      raw = [rr / 9 / 255, gg / 9 / 255, bb / 9 / 255];
    } catch (e) {
      this.s.setStatus(`Could not read the viewer: ${errText(e)}`);
    }
    const { clipId, nodeId } = pk;
    this.cancelPick();
    if (!raw) return;
    const input = this.nodeInput(clipId, nodeId, raw, (lx - vr.x) / vr.w, (ly - vr.y) / vr.h);
    if (!input) {
      this.s.setStatus("The clip or node changed while picking; nothing was set.");
      return;
    }
    const q = cops.qualifierFromColor(input);
    const hex = "#" + input.map((v) => Math.round(clamp01(v) * 255).toString(16).padStart(2, "0")).join("");
    const res = await this.cmd("color.qualifier", { id: clipId, node: nodeId, on: true, h: q.h, hw: q.hw, hs: q.hs, s0: q.s0, s1: q.s1, ss: q.ss, l0: q.l0, l1: q.l1, ls: q.ls, invert: false });
    if (res) this.s.setStatus(`Qualifier set around ${hex} (the colour this node receives)`);
  }

  /**
   * The colour a node receives for an ungraded picture colour `raw` at frame position (fx, fy): the basic grade,
   * then for a serial node the switched-on nodes before it (windows resolved for this moment, at the pixel's place
   * in the clip's picture). A layer node receives the picture after the basic grade only.
   */
  private nodeInput(clipId: string, nodeId: string, raw: [number, number, number], fx: number, fy: number): [number, number, number] | null {
    const p = this.s.project;
    const c = findClip(p, clipId)?.clip;
    const nodes = c?.nodes ?? [];
    const idx = nodes.findIndex((n) => n.id === nodeId);
    const node = nodes[idx];
    if (!c || !node) return null;
    const d = evaluateClip(p, c, this.s.time);
    const base = gradeBasic(raw, d.grade, d.wheels, { shadows: d.fx.shadows, highlights: d.fx.highlights });
    if (node.kind === "layer") return base;
    const earlier = new Set(nodes.slice(0, idx).filter((n) => n.on).map((n) => n.id));
    // d.nodes holds the switched-on nodes in order with their windows resolved for this moment.
    const before = (d.nodes ?? []).filter((n) => earlier.has(n.id));
    if (!before.length) return base;
    const pos = this.picturePos(c, d, fx, fy);
    return applyNodes(before, base, { u: pos?.u ?? 0.5, v: pos?.v ?? 0.5, aspect: pos?.aspect ?? p.width / p.height, luts: lutTable });
  }

  /**
   * Where frame position (fx, fy) (0..1) falls inside the clip's own picture, from its fit, position, scale,
   * rotation and flips at this moment. Null when it cannot be worked out (not a media clip) or falls outside it.
   * Crop and the anchor point are not taken into account.
   */
  private picturePos(c: Clip, d: ReturnType<typeof evaluateClip>, fx: number, fy: number): { u: number; v: number; aspect: number } | null {
    const p = this.s.project;
    const a = findAsset(p, c.assetId);
    if (c.kind !== "media" || !a || !a.width || !a.height) return null;
    const W = p.width;
    const H = p.height;
    let pw = W;
    let ph = H;
    if (c.fit === "contain" || c.fit === "cover") {
      const k = c.fit === "contain" ? Math.min(W / a.width, H / a.height) : Math.max(W / a.width, H / a.height);
      pw = a.width * k;
      ph = a.height * k;
    } else if (c.fit === "none") {
      pw = a.width;
      ph = a.height;
    }
    pw *= Math.abs(d.scaleX) || 1e-6;
    ph *= Math.abs(d.scaleY) || 1e-6;
    let px = fx * W - (W / 2 + d.x);
    let py = fy * H - (H / 2 + d.y);
    const r = (-d.rotation * Math.PI) / 180;
    [px, py] = [px * Math.cos(r) - py * Math.sin(r), px * Math.sin(r) + py * Math.cos(r)];
    let u = px / pw + 0.5;
    let v = py / ph + 0.5;
    if (d.flipX) u = 1 - u;
    if (d.flipY) v = 1 - v;
    if (u < 0 || u > 1 || v < 0 || v > 1) return null;
    return { u, v, aspect: a.width / a.height };
  }

  /* ---------------------------------------------- window */

  private winTool(): HTMLElement[] {
    const win = (): PowerWindow | null => this.cur()?.n?.win ?? null;
    /** The window as it is at the playhead (keys interpolated). */
    const at = (): PowerWindow => {
      const cur = this.cur();
      const w = cur?.n?.win;
      if (!cur || !w) return { ...WIN_DEFAULT, shape: "ellipse" };
      return w.keys?.length ? windowAt(w, this.local(cur.c)) : w;
    };
    const shape = win()?.shape ?? "none";
    const shapeSel = selectEl<PowerWindow["shape"]>(SHAPES, shape, (v) => void this.nodeCmd("color.window", { shape: v }));
    shapeSel.title = "Window shape";
    shapeSel.setAttribute("aria-label", "Window shape");
    this.ups.push(() => {
      const v = win()?.shape ?? "none";
      if (shapeSel.value !== v) shapeSel.value = v;
    });
    const none = shape === "none";
    const keyBtn = this.keyToggle();
    if (none) keyBtn.disabled = true;
    const top = h(
      "div",
      { class: "cp-bar" },
      h("div", { class: "cp-row cp-shape" }, h("label", {}, "Shape"), shapeSel),
      h("span", { class: "sp" }),
      this.check("Invert", "Work outside the window instead of inside", () => !!win()?.invert, (v) => this.edit("window", (p, c, n) => void cops.setWindow(p, c.id, n.id, { invert: v })), () => !none),
      keyBtn,
    );
    if (none) return [top, h("div", { class: "hint cp-hint" }, "Choose a shape to limit this node to part of the picture. The window follows the clip's own picture, and can be animated with keys.")];

    const grad = shape === "gradient";
    /** Write one animatable value: to the key at the playhead when the window is animated, else to the window. */
    const setW = (k: WinKey, v: number) =>
      this.edit("window", (p, c, n) => {
        const w = n.win;
        if (!w?.keys?.length) {
          cops.setWindow(p, c.id, n.id, { [k]: v });
          return;
        }
        const t = this.local(c);
        const fr = this.frame();
        const keys = w.keys.map((x) => ({ ...x }));
        const i = keys.findIndex((x) => Math.abs(x.t - t) < fr);
        if (i >= 0) keys[i] = { ...keys[i]!, [k]: v };
        else {
          const now = windowAt(w, t);
          keys.push({ t, x: now.x, y: now.y, w: now.w, h: now.h, rot: now.rot, [k]: v });
        }
        cops.setWindow(p, c.id, n.id, { keys });
      });
    const sl = (k: WinKey, label: string, min: number, max: number, def: number, title: string, unit = "", digits = 3) =>
      this.slider({ label, title, min, max, step: k === "rot" ? 0.1 : 0.001, def, unit, digits, get: () => (win() ? at()[k] : null), set: (v) => setW(k, v) }).el;
    const sliders = h(
      "div",
      { class: "cp-sliders" },
      sl("x", "X", 0, 1, 0.5, "Centre, from the left of the picture"),
      sl("y", "Y", 0, 1, 0.5, "Centre, from the top of the picture"),
      sl("w", grad ? "Length" : "Width", 0.005, 2, 0.5, grad ? "Length of the gradient's ramp" : "Width as a share of the picture"),
      grad ? null : sl("h", "Height", 0.005, 2, 0.5, "Height as a share of the picture"),
      sl("rot", "Rotation", -180, 180, 0, "Rotation, clockwise", "°", 1),
      this.slider({ label: "Softness", title: "Soft edge, as a share of the window size", min: 0, max: 1, step: 0.005, def: WIN_DEFAULT.soft, get: () => win()?.soft ?? null, set: (v) => this.edit("window soft", (p, c, n) => void cops.setWindow(p, c.id, n.id, { soft: v })) }).el,
    );

    // Animation.
    const addKey = b("Add key at playhead", "key", () => {
      const cur = this.cur();
      const w = cur?.n?.win;
      if (!cur || !w) return;
      const t = this.local(cur.c);
      const now = w.keys?.length ? windowAt(w, t) : w;
      const fr = this.frame();
      const keys = (w.keys ?? []).filter((k) => Math.abs(k.t - t) >= fr).map((k) => ({ ...k }));
      keys.push({ t, x: now.x, y: now.y, w: now.w, h: now.h, rot: now.rot });
      keys.sort((a, bb) => a.t - bb.t);
      void this.nodeCmd("color.window", { keys });
    }, "sm", "Store the window's position, size and rotation at the playhead (replaces a key on the same frame)");
    // Clearing passes an empty list: color.window's keys:null leaves the keys as they are.
    const clearKeys = b("Clear keys", null, () => void this.nodeCmd("color.window", { keys: [] }), "sm ghost", "Remove the window's animation (it keeps its current shape)");
    const list = h("div", { class: "cp-keys" });
    let listSig = "";
    this.ups.push(() => {
      const cur = this.cur();
      const keys = cur?.n?.win?.keys ?? [];
      clearKeys.disabled = !keys.length;
      const t = cur ? this.local(cur.c) : 0;
      const fr = this.frame();
      const sig = keys.map((k) => `${k.t.toFixed(4)}:${Math.abs(k.t - t) < fr}`).join(",");
      if (sig === listSig) return;
      listSig = sig;
      if (!cur || !keys.length) {
        list.replaceChildren(h("span", { class: "hint" }, "Not animated. Move the playhead and add keys to make the window follow something."));
        return;
      }
      const fps = this.s.project.fps || 30;
      list.replaceChildren(
        ...keys.map((k, i) => {
          const label = tc(k.t, fps).replace(/^00:/, "");
          const jump = h("button", { type: "button", class: `cp-key ${Math.abs(k.t - t) < fr ? "on" : ""}`, title: `Move the playhead to this key (${k.t.toFixed(2)} s into the clip)` }, h("i", {}), h("span", { class: "mono" }, label));
          jump.addEventListener("click", () => this.s.seek(cur.c.start + k.t));
          const del = h("button", { type: "button", class: "cp-key-x", title: "Delete this key", "aria-label": "Delete key" }, "×");
          del.addEventListener("click", () => {
            const left = keys.filter((_, j) => j !== i).map((x) => ({ ...x }));
            void this.nodeCmd("color.window", { keys: left });
          });
          return h("span", { class: "cp-keyrow" }, jump, del);
        }),
      );
    });
    const anim = h("div", { class: "cp-group" }, h("div", { class: "cp-gtitle" }, "Animation"), h("div", { class: "cp-bar" }, addKey, h("span", { class: "sp" }), clearKeys), list);
    return [top, sliders, anim];
  }

  /* ---------------------------------------------- LUT */

  private projectLutSig(): string {
    return (this.s.project.luts ?? []).map((l) => `${l.id}:${l.name}`).join(",");
  }

  private loadLuts(): void {
    if (this.lutsLoading) return;
    this.lutsLoading = true;
    const want = this.projectLutSig();
    void runCommand(this.s, { op: "color.lut.list" })
      .then((r) => {
        if (r.ok) this.luts = r.result as LutList;
        else {
          this.luts ??= { builtin: [], project: [] };
          this.s.setStatus(r.error ?? "Could not list the LUTs");
        }
      })
      .finally(() => {
        this.lutsLoading = false;
        this.lutsFor = want;
        this.schedule();
      });
  }

  private lutTool(): HTMLElement[] {
    if (this.lutsFor !== this.projectLutSig()) this.loadLuts();
    const lutOf = () => this.cur()?.n?.lut ?? null;
    const importBtn = b("Import .cube…", "folderOpen", () => void this.importLut(), "sm", "Import a .cube LUT file from this PC and apply it to this node");
    const exportBtn = b("Export grade as .cube", "export", () => void this.exportLut(), "sm", "Save this clip's whole grade as a .cube LUT (windows are left out: a LUT has no position)");
    const mix = this.slider({
      label: "Mix",
      title: "How much of the LUT is applied",
      min: 0,
      max: 1,
      step: 0.005,
      def: 1,
      get: () => lutOf()?.mix ?? null,
      enabled: () => !!lutOf(),
      set: (v) =>
        this.edit("lut mix", (p, c, n) => {
          if (n.lut) cops.setNode(p, c.id, n.id, { lut: { id: n.lut.id, mix: v } });
        }),
    });
    const rows: { id: string | null; el: HTMLElement }[] = [];
    const row = (id: string | null, name: string, about?: string) => {
      const bt = h("button", { type: "button", class: "cp-lut", title: about ?? (id ? `Apply ${name} to this node` : "Remove the LUT from this node") }, h("span", {}, name));
      bt.addEventListener("click", () => {
        if (id) void this.nodeCmd("color.lut.apply", { lut: id, mix: lutOf()?.mix ?? 1 }).then((r) => r && this.s.setStatus(`${name} applied to the node`));
        else void this.nodeCmd("color.lut.apply", { lut: "none" }).then((r) => r && this.s.setStatus("LUT taken off the node"));
      });
      rows.push({ id, el: bt });
      return bt;
    };
    const list = h("div", { class: "cp-luts" });
    const L = this.luts;
    if (!L) list.append(h("div", { class: "hint" }, "Loading LUTs…"));
    else {
      list.append(row(null, "None"));
      for (const g of LUT_GROUPS) {
        const items = L.builtin.filter((x) => x.group === g);
        if (!items.length) continue;
        list.append(h("div", { class: "cp-gtitle" }, g), ...items.map((x) => row(`builtin:${x.id}`, x.name, `${x.name}\n\n${x.about}`)));
      }
      const other = L.builtin.filter((x) => !LUT_GROUPS.includes(x.group));
      if (other.length) list.append(h("div", { class: "cp-gtitle" }, "Other"), ...other.map((x) => row(`builtin:${x.id}`, x.name, `${x.name}\n\n${x.about}`)));
      list.append(h("div", { class: "cp-gtitle" }, "This project"));
      if (L.project.length) list.append(...L.project.map((x) => row(x.id, x.name, `${x.name}${x.size ? ` (${x.size}³)` : ""} · kept with this project`)));
      else list.append(h("div", { class: "hint" }, "LUTs you import are kept with the project and listed here."));
    }
    this.ups.push(() => {
      const cur = lutOf()?.id ?? null;
      for (const r of rows) r.el.classList.toggle("on", r.id === cur);
    });
    return [h("div", { class: "cp-bar" }, importBtn, h("span", { class: "sp" }), exportBtn), mix.el, list];
  }

  private async importLut(): Promise<void> {
    const cur = this.cur();
    if (!cur?.n) return;
    if (!vsd) {
      this.s.setStatus("Importing LUT files needs VideoStudio Desktop.");
      return;
    }
    let paths: string[] = [];
    try {
      paths = await vsd.pick({ title: "Import a .cube LUT", multiple: false });
    } catch (e) {
      this.s.setStatus(errText(e));
      return;
    }
    const path = paths[0];
    if (!path) return;
    if (!/\.cube$/i.test(path)) {
      this.s.setStatus("Pick a .cube LUT file.");
      return;
    }
    const added = (await this.cmd("color.lut.import", { path }, "Importing the LUT…")) as { id?: string; name?: string } | undefined;
    if (!added?.id) return;
    const ok = await this.cmd("color.lut.apply", { id: cur.c.id, node: cur.n.id, lut: added.id, mix: 1 });
    if (ok) this.s.setStatus(`${added.name ?? "LUT"} imported and applied to the node`);
  }

  private async exportLut(): Promise<void> {
    const c = this.clip();
    if (!c) return;
    const r = (await this.cmd("color.lut.export", { id: c.id }, "Writing the LUT…")) as { path?: string; uri?: string; windowsIgnored?: boolean } | undefined;
    if (!r) return;
    const where = r.path || r.uri || "Documents/VideoStudio";
    this.s.setStatus(`Grade saved as a LUT: ${where}${r.windowsIgnored ? " (windows are not part of a LUT and were left out)" : ""}`);
  }

  /* ------------------------------------------------------------------ 3. clip tools */

  private clipTools(): HTMLElement {
    const auto = b("Auto balance", "wand", () => void this.autoBalance(), "sm", "Measure the clip and add an \"Auto balance\" node that removes a colour cast and sets clean black and white points");
    const match = b("Match to…", "image", () => this.matchMenu(match), "sm", "Bring this clip's look to another clip or a picture (adds a \"Shot match\" node)");
    const copy = b("Copy grade to…", "copy", () => this.copyMenu(copy), "sm", "Copy this clip's whole grade onto other clips");
    const reset = b("Reset colour", "trash", () => this.resetColour(), "sm", "Remove this clip's colour work");

    const bypass = b("Bypass", "eyeOff", () => setColorView({ bypass: !colorView.bypass }), "sm", "Show the picture without any colour work (viewer only, never in renders)");
    const compare = b("Compare", "split", () => setColorView({ split: colorView.split >= 0 ? -1 : this.wipe }), "sm", "Compare wipe: left of the line is ungraded (viewer only)");
    const wipe = this.slider({
      label: "Wipe",
      title: "Where the compare line sits across the frame",
      min: 0,
      max: 1,
      step: 0.005,
      def: 0.5,
      digits: 2,
      get: () => (colorView.split >= 0 ? colorView.split : this.wipe),
      enabled: () => colorView.split >= 0,
      set: (v) => {
        this.wipe = v;
        if (colorView.split >= 0) setColorView({ split: v });
      },
    });
    wipe.el.classList.add("cp-wipe");
    this.ups.push(() => {
      bypass.classList.toggle("on", colorView.bypass);
      compare.classList.toggle("on", colorView.split >= 0);
    });
    return h("div", { class: "cp-sec cp-cliptools" }, h("div", { class: "cp-gtitle" }, "Clip"), h("div", { class: "cp-bar wrap" }, auto, match, copy, reset), h("div", { class: "cp-bar" }, bypass, compare, wipe.el));
  }

  private async autoBalance(): Promise<void> {
    const c = this.clip();
    if (!c) return;
    const r = (await this.cmd("color.auto", { id: c.id }, "Measuring the clip…")) as { nodeId?: string }[] | undefined;
    if (!r) return;
    this.selectNode(c.id, r[0]?.nodeId);
    this.s.setStatus("Auto balance added as the first node");
  }

  private matchMenu(anchor: HTMLElement): void {
    const c = this.clip();
    if (!c) return;
    const p = this.s.project;
    const clips: MenuItem[] = [];
    for (const t of p.tracks) {
      if (t.kind !== "video") continue;
      for (const o of t.clips) if (o.id !== c.id && cops.isPicture(p, o)) clips.push({ label: `${o.name || "Clip"}  ·  ${tc(o.start, p.fps || 30)}`, run: () => this.matchMode({ ref: o.id }, o.name || "clip") });
    }
    const assets: MenuItem[] = p.assets.filter((a) => a.kind === "image" || a.kind === "video").map((a) => ({ label: a.name, run: () => this.matchMode({ refAsset: a.id }, a.name) }));
    const items: MenuItem[] = [{ label: "Clips on the timeline", disabled: true }, ...(clips.length ? clips : [{ label: "No other picture clips", disabled: true }]), { label: "", sep: true }, { label: "Pictures and videos in the media list", disabled: true }, ...(assets.length ? assets : [{ label: "No pictures or videos imported", disabled: true }])];
    this.menuAt(anchor, items);
  }

  /**
   * The second step of "Match to…": how the match is built. A dialog rather than a second menu, because the kit's
   * menu leaves its outside-click listener behind after an item is chosen, which would close a menu opened from it.
   */
  private matchMode(ref: Record<string, string>, refName: string): void {
    const c = this.clip();
    if (!c) return;
    const go = (mode: "lut" | "curves" | "primaries") => async () => {
      m.close();
      const r = (await this.cmd("color.match", { id: c.id, ...ref, mode }, `Matching to ${refName}…`)) as { reference?: string; results?: { nodeId: string; before: number; after: number }[] } | undefined;
      const res = r?.results?.[0];
      if (!r || !res) return;
      this.selectNode(c.id, res.nodeId);
      this.s.setStatus(`Matched to ${r.reference ?? refName}: difference ${res.before.toFixed(3)} → ${res.after.toFixed(3)} (0 = same look)`);
    };
    const opt = (mode: "lut" | "curves" | "primaries", title: string, about: string) => {
      const bt = h("button", { type: "button", class: "cp-opt", title: about }, h("b", {}, title), h("span", {}, about));
      bt.addEventListener("click", () => void go(mode)());
      return bt;
    };
    const body = h(
      "div",
      { class: "col cp-modal" },
      h("span", { class: "muted" }, `Bring "${c.name || "this clip"}" to the look of ${refName}. A "Shot match" node is added (replacing an earlier one).`),
      opt("lut", "LUT", "Most faithful: a 3D LUT built from both looks."),
      opt("curves", "Curves", "Editable red, green and blue curves."),
      opt("primaries", "Primaries", "Lift, gamma and gain only: the gentlest match."),
    );
    const m = modal("Match to " + refName, body, [b("Cancel", null, () => m.close())]);
  }

  private copyMenu(anchor: HTMLElement): void {
    const c = this.clip();
    const f = c ? findClip(this.s.project, c.id) : null;
    if (!c || !f) return;
    const p = this.s.project;
    const track = f.track.clips.filter((o) => o.id !== c.id && cops.isPicture(p, o)).map((o) => o.id);
    const all = p.tracks.filter((t) => t.kind === "video").flatMap((t) => t.clips.filter((o) => o.id !== c.id && cops.isPicture(p, o)).map((o) => o.id));
    const go = (to: string[]) => async () => {
      const r = (await this.cmd("color.copy", { from: c.id, to })) as { copied?: number } | undefined;
      if (r) this.s.setStatus(`Grade copied to ${r.copied ?? 0} clip${r.copied === 1 ? "" : "s"}`);
    };
    this.menuAt(anchor, [
      { label: `Other clips on this track (${track.length})`, disabled: !track.length, run: () => void go(track)() },
      { label: `All picture clips (${all.length})`, disabled: !all.length, run: () => void go(all)() },
    ]);
  }

  private resetColour(): void {
    const c = this.clip();
    if (!c) return;
    const body = h("div", { class: "col" }, h("span", { class: "muted" }, `Remove the colour work from "${c.name || "this clip"}"? You can undo this.`));
    const m = modal("Reset colour", body, [
      b("Cancel", null, () => m.close()),
      b("Nodes only", null, () => {
        m.close();
        void this.cmd("color.reset", { id: c.id, nodesOnly: true }).then((r) => r && this.s.setStatus("Grading nodes removed"));
      }, "", "Remove the grading nodes, keep the basic grade and wheels"),
      b("Reset everything", null, () => {
        m.close();
        void this.cmd("color.reset", { id: c.id }).then((r) => r && this.s.setStatus("Colour reset"));
      }, "pri", "Remove the nodes, the basic grade and the wheels"),
    ]);
  }
}
