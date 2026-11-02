/**
 * Live mode: while an agent (Claude or another AI) runs commands, the app shows the work as it happens —
 * it switches tabs, opens the right inspector tab, selects and frames clips, moves the playhead, zooms the
 * timeline, scrubs animation frames, and a "Claude cursor" glides to the button for each action and taps it.
 * A HUD shows the current step with Pause / Step / Stop and the pace (Instant, Watch, Cinematic).
 */
import type { Studio } from "../app/studio";
import { findClip, type Clip } from "../core/model";
import type { CommandResult, LiveHooks } from "../agent/api";
import { STOPPED } from "../agent/api";
import { openInspector, type InspectorTab } from "./inspector";
import type { EditorView } from "./editor";
import type { AnimateView } from "./animate";
import { kvGet, kvSet } from "../core/storage";
import { h, icon } from "./dom";

export type ViewName = "edit" | "animate" | "voice" | "export" | "agent";
export type LivePace = "instant" | "watch" | "cinematic";

export interface LiveSettings {
  enabled: boolean;
  pace: LivePace;
}

interface Plan {
  view?: ViewName;
  /** Accessible label of the button the cursor taps (nav, toolbar, inspector tab, sheet button). */
  tap?: string[];
  clipId?: string;
  inspector?: InspectorTab;
  sceneId?: string;
  frame?: number;
  seek?: number;
  zoomToClip?: boolean;
  say: string;
  /** Quick ops (many strokes) are compressed so a long batch never drags. */
  quick?: boolean;
}

const VIEW_LABEL: Record<ViewName, string> = { edit: "Edit", animate: "Animate", voice: "Voice", export: "Render", agent: "Agent" };

const DELAYS: Record<LivePace, { step: number; view: number; tap: number; quick: number }> = {
  instant: { step: 0, view: 0, tap: 0, quick: 0 },
  watch: { step: 200, view: 380, tap: 340, quick: 18 },
  cinematic: { step: 750, view: 900, tap: 700, quick: 70 },
};

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const frame = () => new Promise((r) => requestAnimationFrame(() => r(null)));

const INSPECT: Record<string, [InspectorTab, string]> = {
  "clip.grade": ["color", "Colour"],
  "clip.wheels": ["color", "Colour"],
  "clip.duotone": ["color", "Colour"],
  "clip.fx": ["effects", "Effects"],
  "clip.transition": ["transition", "Transition"],
  "clip.key": ["transform", "Motion"],
  "clip.keys": ["transform", "Motion"],
  "clip.unkey": ["transform", "Motion"],
  "clip.kenBurns": ["transform", "Motion"],
  "clip.punch": ["transform", "Motion"],
  "clip.crop": ["crop", "Edit"],
  "clip.speed": ["edit", "Speed"],
  "clip.speedRamp": ["edit", "Speed"],
  "clip.textPreset": ["text", "Text"],
  "clip.kinetic": ["text", "Text"],
  "clip.collage": ["effects", "Effects"],
};

function describe(op: string, a: Record<string, unknown>): string {
  const q = (k: string) => (typeof a[k] === "string" ? `“${String(a[k]).slice(0, 28)}”` : "");
  const map: Record<string, string> = {
    "project.new": `New project ${q("name")}`,
    "clip.add": a.text ? `Adding title ${q("text")}` : a.sceneId ? "Placing animation on the timeline" : "Adding a clip",
    "clip.grade": "Colour grading",
    "clip.wheels": "Colour wheels",
    "clip.fx": "Adding effects",
    "clip.transition": "Setting transitions",
    "clip.keys": "Keyframing motion",
    "clip.key": "Keyframing motion",
    "clip.textPreset": `Title style ${q("preset")}`,
    "clip.kinetic": `Kinetic type ${q("style")}`,
    "clip.punch": "Punch zoom",
    "clip.collage": "Paper-cut collage look",
    "clip.split": "Splitting",
    "clip.move": "Moving clip",
    "clip.trim": "Trimming",
    "captions.add": `Captions ${q("style")}`,
    "overlay.add": `Overlay ${q("type")}`,
    "mograph.add": `Motion graphic ${q("type")}`,
    "fx.add": `Hand-drawn FX ${q("type")}`,
    "layout.compare": "Compare layout",
    "anim.scene.new": "New animation scene",
    "anim.stroke": "Drawing",
    "anim.path": "Drawing vector art",
    "anim.shape": "Drawing shapes",
    "anim.drawOn": "Draw-on animation",
    "char.create": "Creating the character",
    "char.act": "Animating the character",
    "char.key": "Posing",
    "char.say": `Voice line ${q("text")}`,
    "scene.backdrop": `Painting the ${String(a.type ?? "backdrop")}`,
    "anim.toTimeline": "Animation to the timeline",
    "music.compose": `Composing the score (${String(a.mood ?? "melancholy")})`,
    "sfx.ambience": `Ambience: ${String(a.type ?? "")}`,
    "voice.speak": `VolTune: ${q("text")}`,
    "render.start": "Rendering the video",
    "project.save": "Saving",
    shot: "Building the whole shot",
  };
  return map[op] ?? op.replace(/\./g, " · ");
}

function plan(s: Studio, op: string, a: Record<string, unknown>): Plan {
  const say = describe(op, a);
  const id = typeof a.id === "string" ? a.id : undefined;
  const sceneId = typeof a.sceneId === "string" ? a.sceneId : undefined;
  const sc = sceneId ? s.project.scenes.find((x) => x.id === sceneId) : undefined;
  const frameOf = () => (typeof a.frame === "number" ? a.frame : typeof a.at === "number" && sc ? a.at * sc.fps : undefined);
  if (op.startsWith("anim.") || op.startsWith("char.") || op.startsWith("scene.") || op === "fx.add.scene") {
    if (op === "anim.toTimeline") return { view: "edit", say, tap: ["Edit"] };
    const tap = op.startsWith("char.") || op === "scene.backdrop" ? ["Character"] : op === "anim.layer.add" || op === "anim.layer.set" ? ["Layers"] : op.startsWith("anim.rig") || op === "anim.pose" || op === "anim.ik" || op === "anim.motion" ? ["Rig"] : undefined;
    return { view: "animate", sceneId, frame: frameOf(), say, tap, quick: op === "anim.stroke" || op === "anim.shape" || op === "anim.clear" || op === "anim.key" || op === "anim.path" };
  }
  if (op.startsWith("voice.")) return { view: "voice", say, tap: ["Voice"] };
  if (op === "render.start") return { view: "export", say, tap: ["Render"] };
  if (op.startsWith("media.") || op.startsWith("project.") || op === "state" || op === "help" || op.endsWith(".help")) return { say, quick: true };
  const ins = INSPECT[op];
  if (id && ins) return { view: "edit", clipId: id, inspector: ins[0], tap: [ins[1]], zoomToClip: true, say };
  if (id) return { view: "edit", clipId: id, zoomToClip: true, say };
  if (typeof a.start === "number") return { view: "edit", seek: a.start, say };
  return { view: "edit", say };
}

export class LiveDirector implements LiveHooks {
  settings: LiveSettings = { enabled: true, pace: "watch" };
  private hud: HTMLElement;
  private cursor: HTMLElement;
  private text: HTMLElement;
  private counter: HTMLElement;
  private bar: HTMLElement;
  private paceBtn: HTMLButtonElement;
  private pauseBtn: HTMLButtonElement;
  private paused = false;
  private stepOnce = false;
  private stopped = false;
  private total = 0;
  private done = 0;
  private activeDepth = -1;
  private insp: { close: () => void } | null = null;
  private inspKey = "";
  private lastOp = "";
  private hideTimer = 0;

  constructor(
    private readonly s: Studio,
    private readonly host: { goto: (v: ViewName, arg?: string) => void; current: () => ViewName; editor: EditorView; animate: AnimateView },
  ) {
    this.text = h("span", { class: "live-text" }, "");
    this.counter = h("span", { class: "live-count" }, "");
    this.bar = h("div", { class: "live-bar" });
    this.pauseBtn = h("button", { class: "live-btn", type: "button", "aria-label": "Pause the agent", onclick: () => this.togglePause() }, icon("pause", 16)) as HTMLButtonElement;
    this.paceBtn = h("button", { class: "live-pace", type: "button", "aria-label": "Live speed", onclick: () => this.cyclePace() }, "Watch") as HTMLButtonElement;
    this.hud = h(
      "div",
      { class: "live-hud", role: "status", "aria-live": "polite" },
      h("div", { class: "live-row" },
        h("span", { class: "live-dot" }),
        h("b", {}, "Claude"),
        this.text,
        h("div", { class: "grow" }),
        this.counter,
      ),
      h("div", { class: "live-row" },
        h("div", { class: "live-track" }, this.bar),
        this.paceBtn,
        this.pauseBtn,
        h("button", { class: "live-btn", type: "button", "aria-label": "Do one step", onclick: () => this.step() }, icon("next", 16)),
        h("button", { class: "live-btn stop", type: "button", "aria-label": "Stop the agent", onclick: () => this.stop() }, icon("close", 16)),
      ),
    );
    this.cursor = h("div", { class: "live-cursor", "aria-hidden": "true" }, h("span", { class: "live-cursor-dot" }), h("span", { class: "live-cursor-tag" }, "Claude"));
    document.body.append(this.hud, this.cursor);
  }

  async load(): Promise<void> {
    const v = await kvGet<LiveSettings>("live").catch(() => undefined);
    if (v) this.settings = { ...this.settings, ...v };
    this.paceBtn.textContent = this.paceName();
  }

  save(): void {
    void kvSet("live", this.settings);
    this.paceBtn.textContent = this.paceName();
  }

  private paceName() {
    return { instant: "Instant", watch: "Watch", cinematic: "Cinema" }[this.settings.pace];
  }

  private cyclePace() {
    const order: LivePace[] = ["instant", "watch", "cinematic"];
    this.settings.pace = order[(order.indexOf(this.settings.pace) + 1) % order.length]!;
    this.save();
  }

  private togglePause() {
    this.paused = !this.paused;
    this.pauseBtn.replaceChildren(icon(this.paused ? "play" : "pause", 16));
    this.pauseBtn.setAttribute("aria-label", this.paused ? "Resume the agent" : "Pause the agent");
    this.hud.classList.toggle("paused", this.paused);
  }

  private step() {
    if (!this.paused) this.togglePause();
    this.stepOnce = true;
  }

  private stop() {
    this.stopped = true;
    if (this.paused) this.togglePause();
  }

  /* ------------------------------- hooks -------------------------------- */

  batchStart(n: number, depth: number): void {
    if (depth === 0 || this.activeDepth < 0) {
      this.activeDepth = depth;
      this.total = n;
      this.done = 0;
      this.stopped = false;
    } else this.total += n;
    window.clearTimeout(this.hideTimer);
    if (this.settings.enabled) this.hud.classList.add("on");
    this.progress();
  }

  batchEnd(depth: number): void {
    if (depth !== this.activeDepth) return;
    this.activeDepth = -1;
    this.text.textContent = this.stopped ? "Stopped" : "Done";
    this.bar.style.width = "100%";
    this.hideTimer = window.setTimeout(() => {
      this.hud.classList.remove("on");
      this.cursor.classList.remove("on");
      this.closeInspector();
    }, 1800);
  }

  async before(op: string, args: Record<string, unknown>): Promise<void> {
    // Pause / step gate.
    while (this.paused && !this.stepOnce && !this.stopped) await sleep(120);
    this.stepOnce = false;
    if (this.stopped) throw new Error(STOPPED);
    if (!this.settings.enabled) return;
    const d = DELAYS[this.settings.pace];
    const p = plan(this.s, op, args);
    this.text.textContent = p.say;
    this.progress();
    const repeat = op === this.lastOp && p.quick;
    this.lastOp = op;
    if (this.settings.pace === "instant" || repeat) {
      if (p.view && p.view !== this.host.current()) this.host.goto(p.view, p.sceneId);
      if (repeat && d.quick) await sleep(d.quick);
      return;
    }
    // 1. Switch screens the way a person would: tap the tab.
    if (p.view && p.view !== this.host.current()) {
      this.closeInspector();
      await this.tap(`nav button[aria-label="${VIEW_LABEL[p.view]}"]`, d.tap);
      this.host.goto(p.view, p.sceneId);
      await frame();
      await sleep(d.view);
    } else if (p.view === "animate" && p.sceneId && this.host.animate.currentScene !== p.sceneId) {
      this.host.goto("animate", p.sceneId);
      await sleep(d.view * 0.5);
    }
    // 2. Frame the clip: select, move the playhead to it, zoom the timeline around it.
    if (p.clipId) {
      const f = findClip(this.s.project, p.clipId);
      if (f) {
        if (this.s.selection !== p.clipId) {
          this.closeInspector();
          this.s.select(p.clipId);
        }
        if (p.zoomToClip) this.zoomTo(f.clip);
        this.s.seek(Math.min(this.s.duration, f.clip.start + Math.min(0.5, f.clip.dur / 3)));
        await frame();
        await this.pulse(`.tl-clip[data-id="${p.clipId}"]`, d.tap * 0.6);
      }
    } else if (typeof p.seek === "number") {
      this.s.seek(Math.max(0, Math.min(p.seek, this.s.duration)));
    }
    // 3. Tap the tool / open the inspector tab being used.
    if (p.inspector && p.clipId) {
      const key = `${p.clipId}|${p.inspector}`;
      if (key !== this.inspKey) {
        this.closeInspector();
        if (p.tap) await this.tap(p.tap.map((t) => `.toolbar button[aria-label="${t}"]`).join(","), d.tap);
        this.insp = openInspector(this.s, p.inspector);
        this.inspKey = key;
        await sleep(d.tap * 0.5);
      }
    } else if (p.tap) {
      this.closeInspector();
      await this.tap(p.tap.map((t) => `button[aria-label="${t}"]`).join(","), d.tap * 0.8);
    }
    if (p.view === "animate" && p.sceneId && typeof p.frame === "number") this.host.animate.showFrame(p.sceneId, p.frame);
    await sleep(d.step);
  }

  async after(op: string, args: Record<string, unknown>, r: CommandResult): Promise<void> {
    this.done++;
    this.progress();
    if (!this.settings.enabled || this.settings.pace === "instant" || !r.ok) return;
    const res = (r.result ?? {}) as Record<string, unknown>;
    // New clips: select and frame them so you see what just appeared.
    if ((op === "clip.add" || op === "anim.toTimeline" || op.endsWith(".add") || op === "captions.add") && typeof res.id === "string") {
      const f = findClip(this.s.project, res.id);
      if (f) {
        this.s.select(res.id);
        this.zoomTo(f.clip);
        this.s.seek(f.clip.start + Math.min(0.6, f.clip.dur / 2));
        await sleep(DELAYS[this.settings.pace].step);
      }
    }
    // Character animation: scrub through what was just keyed.
    if (op === "char.act" && typeof args.sceneId === "string" && typeof res.end === "number") {
      const sc = this.s.project.scenes.find((x) => x.id === args.sceneId);
      if (sc) {
        const end = Math.min(sc.frames - 1, Math.round(res.end * sc.fps));
        const steps = this.settings.pace === "cinematic" ? 48 : 18;
        for (let i = 0; i <= steps; i++) {
          this.host.animate.showFrame(sc.id, (end * i) / steps);
          await sleep(this.settings.pace === "cinematic" ? 40 : 25);
        }
      }
    }
  }

  /* ------------------------------ helpers ------------------------------- */

  private progress() {
    const n = Math.max(1, this.total);
    this.counter.textContent = `${Math.min(this.done + 1, this.total)}/${this.total}`;
    this.bar.style.width = `${Math.round((this.done / n) * 100)}%`;
  }

  private closeInspector() {
    if (this.insp) {
      this.insp.close();
      this.insp = null;
      this.inspKey = "";
    }
  }

  private zoomTo(c: Clip) {
    const tl = this.host.editor.timeline;
    const w = tl.el.clientWidth || 360;
    tl.zoom(Math.max(20, Math.min(400, (w * 0.55) / Math.max(0.3, c.dur))));
  }

  /** Glide the cursor to an element and tap it (ripple). */
  private async tap(selector: string, ms: number) {
    const el = selector ? (document.querySelector(selector) as HTMLElement | null) : null;
    if (!el) return;
    const r = el.getBoundingClientRect();
    if (r.width === 0) return;
    this.cursor.classList.add("on");
    this.cursor.style.transitionDuration = `${Math.max(120, ms * 0.7)}ms`;
    this.cursor.style.transform = `translate(${r.left + r.width / 2}px, ${r.top + r.height / 2}px)`;
    await sleep(Math.max(120, ms * 0.7));
    this.cursor.classList.remove("tap");
    void this.cursor.offsetWidth;
    this.cursor.classList.add("tap");
    el.classList.add("live-flash");
    setTimeout(() => el.classList.remove("live-flash"), 500);
    await sleep(ms * 0.3);
  }

  private async pulse(selector: string, ms: number) {
    const el = document.querySelector(selector) as HTMLElement | null;
    if (!el) return;
    // Only tap what is on screen; never scroll the page (the timeline already follows the playhead).
    const r = el.getBoundingClientRect();
    if (r.right < 0 || r.left > window.innerWidth || r.bottom < 0 || r.top > window.innerHeight) return;
    await this.tap(selector, ms);
  }
}
