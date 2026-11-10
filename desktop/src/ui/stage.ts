/**
 * Claude at work — live view for the desktop.
 *
 * Every command Claude sends is acted out in the window the way a person would do it: the right page opens, a
 * "Claude" pointer glides to the tab, button or clip and clicks, the timeline scrolls and zooms to the clip being
 * edited, the clip is selected (so the inspector shows what changes) and flashes when the edit lands, and a bar at
 * the bottom says in plain words what is happening and how far along the job is. The pace is adjustable
 * (Watch · Fast · Off) and long jobs speed themselves up so watching never costs more than a few seconds.
 */
import type { Studio } from "../../../src/app/studio";
import type { CommandResult, LiveHooks } from "../../../src/agent/api";
import type { DesktopTimeline as Timeline } from "./timeline";
import { h, tc } from "./kit";

export type Pace = "watch" | "fast" | "off";
export interface StageUi {
  go(ws: string): void;
  current(): string;
  timeline: Timeline;
  tab(ws: string): HTMLElement | null;
  playButton(): HTMLElement | null;
  importButton(): HTMLElement | null;
  viewerBox(): HTMLElement | null;
}

const QUIET = /^(state|help|undo|redo|.*\.(list|get|probe|peek|status|info|caps|roots|find|version|crashLog|screenshot|thumb|sheet|backup|save)|fs\..*|desktop\..*|system\..*|smartfill\.status|ui\.notify)$/;
const PAGE: [RegExp, string][] = [
  [/^(clip\.(grade|wheels|look|duotone)$|color\.)/, "color"],
  [/^(lens\.|dna\.)/, "insight"],
  [/^(audio\.|sfx\.|music\.)/, "audio"],
  [/^voice\./, "voice"],
  [/^render\.(start|pro)$/, "deliver"],
  [/^(clip\.|track\.|fx\.|captions\.|text\.|transition\.|overlay\.|collage\.|layout\.|media\.import|kinetic\.|motion\.|mograph\.|edit\.|camera\.|timeline\.|playback\.)/, "edit"],
];

const base = (p: unknown) => String(p ?? "").split("/").pop() || "file";
const short = (v: unknown, n = 34) => {
  const t = String(v ?? "");
  return t.length > n ? `${t.slice(0, n - 1)}…` : t;
};

export class AgentStage implements LiveHooks {
  pace: Pace = (localStorage.getItem("vs.stage.pace") as Pace) || "watch";
  private hud: HTMLElement;
  private text: HTMLElement;
  private count: HTMLElement;
  private bar: HTMLElement;
  private log: HTMLElement;
  private cursor: HTMLElement;
  private flashEl: HTMLElement;
  private total = 0;
  private done = 0;
  private stepMs = 0;
  private hideTimer = 0;
  private cx = window.innerWidth / 2;
  private cy = window.innerHeight / 2;
  private active = false;

  constructor(
    private readonly s: Studio,
    private readonly ui: StageUi,
  ) {
    this.text = h("span", { class: "st-text" }, "");
    this.count = h("span", { class: "st-count mono" }, "");
    this.bar = h("i", {});
    this.log = h("div", { class: "st-log" });
    const paceBtn = h("button", { class: "st-pace", type: "button", title: "How fast Claude's steps are shown: Watch (every step), Fast, or Off (no pointer, no waiting)" }, "") as HTMLButtonElement;
    const label = () => (paceBtn.textContent = this.pace === "watch" ? "Watch" : this.pace === "fast" ? "Fast" : "Off");
    paceBtn.addEventListener("click", () => {
      this.pace = this.pace === "watch" ? "fast" : this.pace === "fast" ? "off" : "watch";
      localStorage.setItem("vs.stage.pace", this.pace);
      label();
      if (this.pace === "off") this.cursor.classList.remove("on");
    });
    label();
    this.hud = h("div", { class: "stage-hud" }, h("span", { class: "st-dot" }), h("b", {}, "Claude"), this.text, h("span", { class: "sp" }), this.count, paceBtn, h("div", { class: "st-bar" }, this.bar), this.log);
    this.hud.addEventListener("mouseenter", () => this.hud.classList.add("open"));
    this.hud.addEventListener("mouseleave", () => this.hud.classList.remove("open"));
    this.cursor = h("div", { class: "stage-cursor" });
    this.cursor.innerHTML = `<svg width="22" height="22" viewBox="0 0 24 24"><path d="M4 2l15 9-6.6 1.6L9.6 20z" fill="#3ee6c4" stroke="#041511" stroke-width="1.4" stroke-linejoin="round"/></svg><span>Claude</span><i></i>`;
    this.flashEl = h("div", { class: "stage-flash" });
    document.body.append(this.hud, this.cursor, this.flashEl);
  }

  /* ------------------------------------------------------------------ hooks */
  batchStart(n: number, depth: number): void {
    if (depth !== 0) return;
    this.active = true;
    this.total = n;
    this.done = 0;
    const per = this.pace === "watch" ? 420 : this.pace === "fast" ? 120 : 0;
    const cap = this.pace === "watch" ? 30_000 : 8_000;
    this.stepMs = per ? Math.min(per, cap / Math.max(1, n)) : 0;
    clearTimeout(this.hideTimer);
    this.log.replaceChildren();
    this.hud.classList.add("on");
    this.hud.classList.remove("bad");
    this.set(`Starting ${n} step${n === 1 ? "" : "s"}…`);
  }

  batchEnd(depth: number): void {
    if (depth !== 0) return;
    this.active = false;
    this.cursor.classList.remove("on");
    this.set(this.hud.classList.contains("bad") ? "Stopped with an error" : "Done");
    this.hideTimer = window.setTimeout(() => this.hud.classList.remove("on"), 5000);
  }

  async before(op: string, args: Record<string, unknown>, depth: number): Promise<void> {
    if (!this.active) return;
    const words = this.describe(op, args);
    if (depth > 0) {
      this.set(words, true);
      return;
    }
    this.set(words);
    // Nobody is looking (window hidden or minimised): do the work at full speed, without the show.
    if (QUIET.test(op) || this.stepMs < 1 || document.visibilityState !== "visible") return;
    const animate = this.stepMs >= 60;
    const move = Math.min(260, this.stepMs * 0.6);
    // 1) The page this kind of work happens on.
    const page = PAGE.find(([re]) => re.test(op))?.[1];
    if (page && this.ui.current() !== page && !(page === "edit" && this.ui.current() === "color")) {
      if (animate) await this.tap(this.ui.tab(page), move);
      this.ui.go(page);
      await this.wait(animate ? 90 : 0);
    }
    // 2) The thing being worked on.
    const clipId = this.clipOf(args);
    if (clipId && ["edit", "color", "insight", "audio"].includes(this.ui.current())) {
      const c = this.find(clipId);
      if (c) {
        await this.ui.timeline.reveal(c.start, c.start + c.dur, animate);
        this.s.select(clipId);
        if (animate) await this.glide(this.ui.timeline.clipRect(clipId), move);
      }
    } else if (animate) {
      if (/^playback\.(play|pause)$/.test(op)) await this.tap(this.ui.playButton(), move);
      else if (op === "playback.seek" && typeof args.t === "number") {
        await this.ui.timeline.reveal(args.t, args.t, true);
        await this.glide(this.ui.timeline.timeRect(args.t), move);
      } else if (/^media\.import/.test(op)) await this.tap(this.ui.importButton(), move);
      else if (/^(viewer\.|render\.(frame|still))/.test(op)) await this.glide(this.rectOf(this.ui.viewerBox()), move);
      else if (op === "clip.add" && typeof args.start === "number") {
        await this.ui.timeline.reveal(args.start, args.start + (Number(args.dur) || 2), true);
        await this.glide(this.ui.timeline.timeRect(args.start), move);
      }
    }
    if (animate) this.click();
    await this.wait(Math.max(0, this.stepMs - (animate ? move : 0)));
  }

  async after(op: string, args: Record<string, unknown>, r: CommandResult, depth: number): Promise<void> {
    if (!this.active || depth > 0) return;
    this.done++;
    this.progress();
    if (!r.ok) {
      this.hud.classList.add("bad");
      this.set(`${this.describe(op, args)} — ${short(r.error, 80)}`);
      return;
    }
    if (this.pace === "off" || QUIET.test(op)) return;
    const res = r.result as { id?: string; clipId?: string } | undefined;
    const id = this.clipOf(args) ?? (typeof res?.id === "string" && this.find(res.id) ? res.id : typeof res?.clipId === "string" ? res.clipId : null);
    if (id && ["edit", "color", "insight", "audio"].includes(this.ui.current())) {
      const c = this.find(id);
      if (c) {
        if (!this.clipOf(args)) await this.ui.timeline.reveal(c.start, c.start + c.dur, this.stepMs >= 60);
        this.flash(this.ui.timeline.clipRect(id));
      }
    }
    if (op === "playback.seek") void this.ui.timeline.reveal(this.s.time, this.s.time, true);
  }

  /* ------------------------------------------------------------------ words */
  private describe(op: string, a: Record<string, unknown>): string {
    const fps = this.s.project.fps;
    const at = (v: unknown) => (typeof v === "number" ? tc(v, fps) : "the playhead");
    const clip = () => {
      const id = this.clipOf(a);
      const c = id ? this.find(id) : null;
      return c ? `“${short(c.name || c.kind, 26)}”` : "the clip";
    };
    switch (op) {
      case "project.new":
        return `New project “${short(a.name ?? "Untitled")}” · ${a.width ?? 1080}×${a.height ?? 1920}`;
      case "project.open":
        return "Open a saved project";
      case "project.set":
        return `Project settings: ${Object.keys(a).filter((k) => k !== "op").join(", ")}`;
      case "media.importPath":
        return `Import ${base(a.path ?? (Array.isArray(a.paths) ? a.paths[0] : ""))}`;
      case "track.add":
        return `Add a ${a.kind ?? "video"} track${a.name ? ` “${short(a.name, 20)}”` : ""}`;
      case "clip.add":
        return a.text !== undefined ? `Add the title “${short(a.text, 28)}”` : `Put a clip on the timeline at ${at(a.start)}`;
      case "clip.split":
        return `Cut ${clip()} at ${at(a.t)}`;
      case "clip.trim":
        return `Trim ${clip()}`;
      case "clip.move":
        return `Move ${clip()}${typeof a.start === "number" ? ` to ${at(a.start)}` : ""}`;
      case "clip.delete":
        return `Delete ${clip()}`;
      case "clip.duplicate":
        return `Duplicate ${clip()}`;
      case "clip.speed":
      case "clip.speedRamp":
        return `Change the speed of ${clip()}`;
      case "clip.set":
        return `Adjust ${clip()}: ${short(Object.keys((a.props as object) ?? a).filter((k) => !["op", "id"].includes(k)).join(", "), 40)}`;
      case "clip.key":
      case "clip.keys":
        return `Animate ${a.prop ?? "a property"} on ${clip()}`;
      case "clip.transition":
        return `Add a ${a.type ?? ""} transition to ${clip()}`.replace("  ", " ");
      case "clip.grade":
      case "clip.wheels":
      case "clip.look":
      case "clip.duotone":
        return `Colour: ${op.split(".")[1]} on ${clip()}`;
      case "color.node.add":
        return `Add a ${a.kind === "layer" ? "layer" : "grading"} node${a.name ? ` “${short(a.name, 20)}”` : ""} to ${clip()}`;
      case "color.node.set":
        return `Adjust node ${short(a.node, 16)} on ${clip()}`;
      case "color.node.remove":
        return `Remove node ${short(a.node, 16)} from ${clip()}`;
      case "color.node.move":
        return `Reorder the nodes of ${clip()}`;
      case "color.node.reset":
        return `Reset node ${short(a.node, 16)} on ${clip()}`;
      case "color.curve":
        return `${a.reset ? "Reset" : "Draw"} the ${({ m: "master", r: "red", g: "green", b: "blue", hh: "hue-vs-hue", hs: "hue-vs-saturation", hl: "hue-vs-brightness", ls: "brightness-vs-saturation", ss: "saturation-vs-saturation" } as Record<string, string>)[String(a.curve ?? "m")] ?? "custom"} curve on ${clip()}`;
      case "color.qualifier":
        return a.off ? `Remove the colour selection on ${clip()}` : `Select ${a.preset ? short(a.preset, 14) : a.color ? `the colour ${a.color}` : "a colour range"} on ${clip()}`;
      case "color.window":
        return a.off ? `Remove the window on ${clip()}` : `Place a ${a.shape ?? ""} window on ${clip()}`.replace("  ", " ");
      case "color.lut.apply":
        return `Apply the LUT “${short(a.lut, 24)}”`;
      case "color.lut.import":
        return `Import the LUT ${base(a.path)}`;
      case "color.lut.add":
        return `Add the LUT “${short(a.name, 24)}”`;
      case "color.lut.export":
        return `Save the grade of ${clip()} as a LUT`;
      case "color.match":
        return `Match the colour of ${clip()} to the reference`;
      case "color.auto":
        return `Auto balance ${clip()}`;
      case "color.copy":
        return "Copy the grade to other clips";
      case "color.reset":
        return `Reset the colour of ${clip()}`;
      case "color.view":
        return a.bypass !== undefined ? (a.bypass ? "Show the picture without the grade" : "Show the grade again") : a.split !== undefined ? "Compare graded and ungraded" : "Show the node's selection";
      case "color.stats":
        return `Measure the colour of ${clip()}`;
      case "lens.run":
        return "Audience Lens: look at the edit like a viewer";
      case "lens.fix":
        return "Audience Lens: apply a fix";
      case "lens.evolve":
        return "Evolve the edit: apply every safe fix and look again";
      case "dna.extract":
        return "Style DNA: measure the feel of a video";
      case "dna.fromProject":
        return a.signature ? "Style DNA: learn from this project" : "Style DNA: measure this edit";
      case "dna.apply":
        return `Style DNA: transplant “${short(a.name, 22)}” onto this edit`;
      case "dna.compare":
        return `Style DNA: compare this edit with “${short(a.name, 22)}”`;
      case "clip.crop":
        return `Crop ${clip()}`;
      case "fx.window":
        return `Frame shape (bars / window) on ${clip()}`;
      case "captions.add":
        return "Add captions";
      case "playback.seek":
        return `Go to ${at(a.t)}`;
      case "playback.play":
        return "Play";
      case "playback.pause":
        return "Pause";
      case "render.start":
      case "render.pro":
        return `Render the video${a.codec ? ` (${a.codec})` : ""}`;
      case "render.frame":
      case "render.still":
        return "Save a frame as a picture";
      case "voice.speak":
        return `Record the voice line “${short(a.text, 30)}”`;
      case "ui.workspace":
        return `Open the ${a.name ?? ""} page`;
      case "timeline.zoom":
        return a.fit ? "Zoom the timeline to fit the edit" : "Zoom the timeline";
      case "ui.screenshot":
        return "Look at the window";
      case "batch.runFile":
        return `Run the steps in ${base(a.path)}`;
    }
    const [group, ...rest] = op.split(".");
    const tail = rest.join(" ").replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
    const nice: Record<string, string> = { clip: "Clip", fx: "Effect", anim: "Animation", char: "Character", three: "3D", audio: "Sound", sfx: "Sound effect", music: "Music", media: "Media", overlay: "Overlay", text: "Text", motion: "Motion", edit: "Edit", camera: "Camera", collage: "Collage", layout: "Layout", scene: "Scene", transition: "Transition" };
    return `${nice[group!] ?? group}: ${tail || op}`;
  }

  /* ------------------------------------------------------------------ helpers */
  private clipOf(a: Record<string, unknown>): string | null {
    for (const k of ["id", "clipId"]) {
      const v = a[k];
      if (typeof v === "string" && v.startsWith("clp_")) return v;
    }
    return null;
  }
  private find(id: string) {
    for (const t of this.s.project.tracks) {
      const c = t.clips.find((x) => x.id === id);
      if (c) return c;
    }
    return null;
  }
  private set(words: string, sub = false): void {
    if (!sub) {
      const row = h("div", {}, words);
      this.log.append(row);
      while (this.log.childElementCount > 7) this.log.firstElementChild!.remove();
    }
    this.text.textContent = sub ? `${this.text.textContent?.split(" › ")[0]} › ${words}` : words;
    this.progress();
  }
  private progress(): void {
    this.count.textContent = this.total > 1 ? `${Math.min(this.total, this.done + (this.active ? 1 : 0))} / ${this.total}` : "";
    this.bar.style.width = `${this.total ? (this.done / this.total) * 100 : 0}%`;
  }
  private wait(ms: number): Promise<void> {
    return ms > 0 ? new Promise((r) => setTimeout(r, ms)) : Promise.resolve();
  }
  private rectOf(el: HTMLElement | null): { x: number; y: number; w: number; h: number } | null {
    if (!el || !el.isConnected) return null;
    const r = el.getBoundingClientRect();
    return r.width && r.height ? { x: r.left, y: r.top, w: r.width, h: r.height } : null;
  }
  private async glide(r: { x: number; y: number; w: number; h: number } | null, ms: number): Promise<void> {
    if (!r) return;
    const x = r.x + Math.min(r.w / 2, 160);
    const y = r.y + r.h / 2;
    const dist = Math.hypot(x - this.cx, y - this.cy);
    this.cursor.classList.add("on");
    this.cursor.style.transitionDuration = `${Math.round(ms)}ms`;
    this.cursor.style.transform = `translate(${Math.round(x)}px, ${Math.round(y)}px)`;
    this.cx = x;
    this.cy = y;
    await this.wait(dist < 4 ? 0 : ms);
  }
  private async tap(el: HTMLElement | null, ms: number): Promise<void> {
    await this.glide(this.rectOf(el), ms);
    this.click();
    el?.classList.add("stage-press");
    setTimeout(() => el?.classList.remove("stage-press"), 220);
    await this.wait(70);
  }
  private click(): void {
    const ring = this.cursor.querySelector("i") as HTMLElement;
    ring.classList.remove("go");
    void ring.offsetWidth;
    ring.classList.add("go");
  }
  private flash(r: { x: number; y: number; w: number; h: number } | null): void {
    if (!r) return;
    Object.assign(this.flashEl.style, { left: `${r.x}px`, top: `${r.y}px`, width: `${r.w}px`, height: `${r.h}px` });
    this.flashEl.classList.remove("go");
    void this.flashEl.offsetWidth;
    this.flashEl.classList.add("go");
  }
}
