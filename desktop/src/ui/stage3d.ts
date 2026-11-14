/**
 * 3D Stage workspace: cast characters, block their performances on an action timeline, choreograph fights, place
 * cameras and review the result. A director's view looks at the stage from anywhere (orbit, top, front, side) and the
 * camera view shows the film as it will render. Everything here runs the same stage.* commands Claude uses, so the
 * two can work on one scene together and every step can be undone.
 */
import type { Studio } from "../../../src/app/studio";
import { runQuiet } from "../../../src/agent/api";
import type { AnimLayer, AnimScene } from "../../../src/core/model";
import { FIGHT_MOVES } from "../../../src/stage/choreo";
import { drawStage, runtimeOf, stageError, stageReady } from "../../../src/stage/draw";
import { ENV_PRESETS, TIMES } from "../../../src/stage/env";
import { CAST } from "../../../src/stage/presets";
import { MOODS, type StageRuntime, type StageView } from "../../../src/stage/runtime";
import { stageDuration, type StageActor, type StageSpec, type Vec3 } from "../../../src/stage/spec";
import { TONES } from "../../../src/voice/engine/open";
import { LEARNED } from "../../../src/voice/engine/learned";
import { b, field, h, modal, selectEl } from "./kit";
import "../styles/studio2.css";

type Kind = "block" | "overlay" | "line" | "shot" | "fx";
interface Item {
  id: string;
  kind: Kind;
  actor?: string;
  t0: number;
  t1: number;
  label: string;
  color: string;
  /** Has its own end (can be made longer or shorter). */
  sized: boolean;
}
interface Row {
  label: string;
  actor?: string;
  items: Item[];
}

const GUT = 132, RULER = 22, ROWH = 24;
const COLORS: Record<string, string> = { go: "#2fbf9f", clip: "#4c8dff", stance: "#f59a3c", fall: "#ef5d6c", idle: "#6b7688", pose: "#8a94a6", overlay: "#a070f0", line: "#f2c94c", shot: "#57c26a", fx: "#ef6fb1" };
const KITS = ["", "orez", "teen", "boy", "sena", "ruon", "elder", "garrick", "vess", "soldier", "kaito", "aldric"];
const GAITS = ["walk", "stroll", "march", "jog", "run", "sneak"];
const SHOTS = ["wide", "full", "medium", "close", "extreme", "low", "high", "top", "ots", "two", "follow"];
const MOVES = ["", "push", "pull", "orbit", "truck", "crane", "handheld", "whip"];
const VOICES: [string, string][] = [...LEARNED.map((v): [string, string] => [v.id, `${v.name} · ${v.tone.split(" · ")[0]}`]), ["hf_alpha", "Hindi woman (Alpha)"], ["hf_beta", "Hindi woman (Beta)"], ["hm_omega", "Hindi man (Omega)"], ["hm_psi", "Hindi man (Psi)"], ["af_heart", "English woman (Heart)"], ["af_bella", "English woman (Bella)"], ["am_michael", "English man (Michael)"], ["am_fenrir", "English man (Fenrir)"], ["bf_emma", "British woman (Emma)"], ["bm_george", "British man (George)"]];
const FX = ["impact", "sparks", "dust", "shockwave", "fire", "smoke", "embers", "flash"];
const D2R = Math.PI / 180;
const r2 = (n: number) => Math.round(n * 100) / 100;
const clamp = (v: number, a: number, c: number) => Math.min(c, Math.max(a, v));

export class StagePage {
  readonly el: HTMLElement;
  private sceneId: string | null = null;
  private t = 0;
  private actorId: string | null = null;
  private sel: { kind: Kind | "actor"; id: string } | null = null;
  private mode: "camera" | "free" = "free";
  private orbit = { yaw: 32, pitch: 16, dist: 8, target: [0, 1, 0] as Vec3 };
  private framed = "";
  private quality = 1;
  private qualitySel!: HTMLSelectElement;
  private tool: "select" | "walk" | "place" = "select";
  private gait = "walk";
  private on = false;
  private dirty = false;
  private raf = 0;
  private playing = false;
  private playT0 = 0;
  private playAt = 0;
  private pxSec = 60;
  private t0 = 0;
  private rowTop = 0;
  private rows: Row[] = [];
  private cv = h("canvas", { class: "sg-cv" }) as HTMLCanvasElement;
  private ov = h("canvas", { class: "sg-ov", tabindex: "0" }) as HTMLCanvasElement;
  private tl = h("canvas", { class: "sg-tl", tabindex: "0" }) as HTMLCanvasElement;
  private viewBox = h("div", { class: "sg-view" });
  private castEl = h("div", { class: "sg-cast" });
  private inspEl = h("div", { class: "sg-insp" });
  private sceneSel = h("select", { class: "sel" }) as HTMLSelectElement;
  private posEl = h("span", { class: "mu-pos" }, "0.00 s");
  private statusEl = h("span", { class: "mu-status" });
  private playBtn: HTMLButtonElement;
  private toolSel: HTMLSelectElement;
  private issues: { level: string; t?: number; actor?: string; what: string }[] | null = null;
  private drag: { item: Item; mode: "move" | "size"; x0: number; dt: number; moved: boolean } | null = null;
  private vdrag: { x: number; y: number; pan: boolean; moved: boolean } | null = null;
  private inner = { x: 0, y: 0, w: 1, h: 1 };
  private lastMs = 0;
  private slowSaid = false;

  constructor(private s: Studio) {
    this.playBtn = b("Play", "play", () => this.toggle(), "pri", "Play the stage from the cursor (Space)");
    this.toolSel = selectEl([["select", "Tool: select"], ["walk", "Tool: walk to"], ["place", "Tool: place"]] as [string, string][], "select", (v) => {
      this.tool = v as StagePage["tool"];
      this.ov.style.cursor = v === "select" ? "grab" : "crosshair";
    });
    this.sceneSel.addEventListener("change", () => {
      this.sceneId = this.sceneSel.value;
      this.sel = null;
      this.framed = "";
      this.refresh();
    });
    const bar = h(
      "div",
      { class: "mu-bar" },
      this.playBtn,
      b(null, "start", () => this.seek(0), "", "Back to the start"),
      this.posEl,
      selectEl([["free", "Director's view"], ["top", "Top"], ["front", "Front"], ["side", "Side"], ["camera", "Camera (the film)"]] as [string, string][], "free", (v) => this.setView(v)),
      b(null, "fit", () => this.frameAll(true), "", "Frame everyone"),
      this.toolSel,
      selectEl(GAITS.map((g) => [g, g]) as [string, string][], "walk", (v) => (this.gait = v)),
      (this.qualitySel = selectEl([["1", "Full quality"], ["0.5", "Half"], ["0.25", "Quarter"]] as [string, string][], "1", (v) => {
        this.quality = Number(v);
        this.slowSaid = true;
        this.mark();
      })),
      h("span", { class: "sp" }),
      this.statusEl,
      b("Check", "eye", () => void this.check(), "", "Look for sliding feet, bodies inside each other, subjects out of frame…"),
      b("Contact sheet", "film", () => void this.sheet(), "", "The film at eight moments, in one picture"),
    );
    this.viewBox.append(this.cv, this.ov);
    const left = h(
      "div",
      { class: "mu-left" },
      h("div", { class: "mu-card" }, h("div", { class: "mu-row" }, h("span", {}, "Stage"), this.sceneSel), h("div", { class: "mu-btns" }, b("New stage", "plus", () => this.newStage(), "pri", "A new 3D scene on the timeline"))),
      h("div", { class: "mu-h" }, "Cast"),
      this.castEl,
      h("div", { class: "mu-btns" }, b("Character", "plus", () => this.addActor(), "", "Add a character to the stage")),
      h("div", { class: "mu-h" }, "Add at the cursor"),
      h(
        "div",
        { class: "sg-add" },
        b("Motion…", "film", () => this.addMotion(), "", "A captured performance from the library (2,500+)"),
        b("Walk to", "up", () => this.pickTool("walk"), "", "Then click the ground where they should go"),
        b("Stance", null, () => this.addSimple({ stance: { guard: "fists" } }), "", "A fighting or ready stance with planted feet"),
        b("Idle", null, () => this.addSimple({ idle: true }), "", "Stand and breathe"),
        b("Sit", null, () => this.addSimple({ pose: "sit" }), "", "Sit down"),
        b("Kneel", null, () => this.addSimple({ pose: "kneel" }), "", "Kneel"),
        b("Fall", null, () => this.addSimple({ fall: {} }), "", "Knocked down"),
        b("Say…", "mic", () => this.addLine(), "", "A spoken line with lip-sync"),
        b("Look at…", "eye", () => this.addLook(), "", "Turn the head and eyes to someone"),
        b("Face…", null, () => this.addFace(), "", "An expression for a while"),
        b("Fight…", "spark", () => this.addFight(), "", "Choreograph a fight between two characters"),
        b("Shot…", "film", () => this.addShot(), "", "A camera shot"),
        b("Effect…", "spark", () => this.addFx(), "", "Impact frame, sparks, dust, fire…"),
      ),
    );
    const mid = h("div", { class: "mu-mid" }, bar, this.viewBox, h("div", { class: "sg-tlwrap" }, this.tl));
    this.el = h("div", { class: "mu sg" }, left, mid, h("div", { class: "mu-right" }, this.inspEl));
    this.bindView();
    this.bindTimeline();
    s.on("change", () => this.on && this.refresh());
    s.on("project", () => {
      this.sceneId = null;
      this.sel = null;
      this.framed = "";
      if (this.on) this.refresh();
    });
    new ResizeObserver(() => {
      this.mark();
      this.drawTimeline();
    }).observe(this.viewBox);
    new ResizeObserver(() => this.drawTimeline()).observe(this.tl);
  }

  shown(on: boolean): void {
    this.on = on;
    if (on) this.refresh();
    else this.stop();
  }

  /* ------------------------------------------------------------ data */

  private scenes(): AnimScene[] {
    return this.s.project.scenes.filter((sc) => sc.layers.some((l) => l.kind === "stage" && l.stage));
  }
  private scene(): AnimScene | null {
    const list = this.scenes();
    const sc = list.find((x) => x.id === this.sceneId) ?? list[list.length - 1] ?? null;
    this.sceneId = sc?.id ?? null;
    return sc;
  }
  private layer(sc: AnimScene): AnimLayer {
    return sc.layers.find((l) => l.kind === "stage" && l.stage)!;
  }
  private spec(): StageSpec | null {
    const sc = this.scene();
    return sc ? this.layer(sc).stage! : null;
  }
  private actor(): StageActor | null {
    const sp = this.spec();
    if (!sp) return null;
    const a = sp.actors.find((x) => x.id === this.actorId) ?? sp.actors[0] ?? null;
    this.actorId = a?.id ?? null;
    return a;
  }
  private end(): number {
    const sc = this.scene();
    return sc ? sc.frames / sc.fps : 0;
  }
  private say(t: string): void {
    this.statusEl.textContent = t;
  }
  private async run(cmd: Record<string, unknown>): Promise<unknown> {
    const sc = this.scene();
    const [r] = await runQuiet(this.s, [{ ...(sc && cmd.op !== "stage.new" ? { sceneId: sc.id } : {}), ...cmd } as never]);
    if (r && !r.ok) {
      this.say(String(r.error ?? "That did not work."));
      return null;
    }
    this.say("");
    return r?.result ?? null;
  }
  /** The runtime posed at the cursor with the page's own camera (cheap: no picture is drawn). */
  private posed(): { rt: StageRuntime; aspect: number } | null {
    const sc = this.scene();
    if (!sc) return null;
    const l = this.layer(sc), spec = l.stage!;
    if (!stageReady(spec)) return null;
    const rt = runtimeOf(l.id);
    if (!rt) return null;
    rt.sync(spec);
    rt.view = this.view();
    try {
      rt.evaluate(spec, Math.round(this.t * sc.fps) / sc.fps, 1 / sc.fps);
    } finally {
      rt.view = null;
    }
    return { rt, aspect: this.inner.w / Math.max(1, this.inner.h) };
  }

  /* ------------------------------------------------------------ refresh */

  private refresh(): void {
    const list = this.scenes(), sc = this.scene();
    this.sceneSel.replaceChildren(...list.map((x) => h("option", { value: x.id, ...(x.id === sc?.id ? { selected: "" } : {}) }, x.name)));
    if (!list.length) this.sceneSel.append(h("option", { value: "" }, "No stage yet"));
    const sp = this.spec();
    this.t = clamp(this.t, 0, this.end());
    // cast
    this.castEl.replaceChildren();
    const cur = this.actor();
    for (const a of sp?.actors ?? []) {
      const row = h("button", { class: `mu-song ${a.id === cur?.id ? "on" : ""}`, type: "button" }, h("b", {}, a.name ?? a.id), h("span", {}, `${a.kit ? a.kit + " · " : ""}${a.blocks.length} actions · ${a.lines.length} lines`));
      row.addEventListener("click", () => {
        this.actorId = a.id;
        this.sel = { kind: "actor", id: a.id };
        this.refresh();
      });
      this.castEl.append(row);
    }
    if (sp && !sp.actors.length) this.castEl.append(h("div", { class: "mu-about" }, "Nobody on the stage yet. Add a character."));
    if (!sp) this.castEl.append(h("div", { class: "mu-about" }, "Make a stage to begin: real human characters, 2,500+ captured motions, fight choreography, cameras and a cinematic or anime look."));
    if (sp && this.framed !== this.sceneId && sp.actors.length) this.frameAll(false);
    this.buildRows();
    this.drawInspector();
    this.drawTimeline();
    this.mark();
  }

  private buildRows(): void {
    const sp = this.spec(), end = this.end();
    const rows: Row[] = [];
    if (sp) {
      rows.push({ label: "Camera", items: sp.shots.map((x) => ({ id: x.id, kind: "shot" as Kind, t0: x.t, t1: x.t + x.dur, label: `${x.kind}${x.on ? " · " + x.on : ""}${x.move ? " · " + x.move.kind : ""}`, color: COLORS.shot!, sized: true })) });
      for (const a of sp.actors) {
        rows.push({
          label: a.name ?? a.id,
          actor: a.id,
          items: a.blocks.map((x, i) => {
            const next = a.blocks[i + 1];
            const label = x.kind === "clip" ? x.clip : x.kind === "go" ? `${x.gait ?? "walk"} → ${r2(x.to[0])}, ${r2(x.to[1])}` : x.kind === "pose" ? `pose ${typeof x.pose === "string" ? x.pose : ""}` : x.kind;
            return { id: x.id, kind: "block" as Kind, actor: a.id, t0: x.t, t1: Math.max(x.t + 0.1, next ? next.t : Math.max(end, x.t + 1)), label, color: COLORS[x.kind] ?? COLORS.idle!, sized: false };
          }),
        });
        // overlays that overlap in time sit on lanes of their own, so each can be read and grabbed
        const lanes: Item[][] = [];
        for (const x of [...a.overlays].sort((p, q) => p.t0 - q.t0)) {
          const it: Item = { id: x.id, kind: "overlay", actor: a.id, t0: x.t0, t1: x.t1, label: x.kind, color: COLORS.overlay!, sized: true };
          const lane = lanes.find((ln) => ln[ln.length - 1]!.t1 <= x.t0 + 1e-6);
          if (lane) lane.push(it);
          else if (lanes.length < 4) lanes.push([it]);
          else lanes[lanes.length - 1]!.push(it);
        }
        lanes.forEach((items, i) => rows.push({ label: i ? "" : "  overlays", actor: a.id, items }));
        if (a.lines.length) rows.push({ label: "  lines", actor: a.id, items: a.lines.map((x) => ({ id: x.id, kind: "line" as Kind, actor: a.id, t0: x.t, t1: x.t + x.dur, label: x.text ?? "line", color: COLORS.line!, sized: false })) });
      }
      if (sp.fx.length) rows.push({ label: "Effects", items: sp.fx.map((x) => ({ id: x.id, kind: "fx" as Kind, t0: x.t0, t1: Math.max(x.t1, x.t0 + 0.08), label: x.kind, color: COLORS.fx!, sized: true })) });
    }
    this.rows = rows;
  }

  /* ------------------------------------------------------------ view */

  private view(): StageView | null {
    if (this.mode === "camera") return null;
    const o = this.orbit, cp = Math.cos(o.pitch * D2R);
    return { pos: [o.target[0] + o.dist * cp * Math.sin(o.yaw * D2R), o.target[1] + o.dist * Math.sin(o.pitch * D2R), o.target[2] + o.dist * cp * Math.cos(o.yaw * D2R)], target: o.target, fov: 40 };
  }
  private setView(v: string): void {
    this.mode = v === "camera" ? "camera" : "free";
    if (v === "top") Object.assign(this.orbit, { yaw: 0, pitch: 89 });
    if (v === "front") Object.assign(this.orbit, { yaw: 0, pitch: 4 });
    if (v === "side") Object.assign(this.orbit, { yaw: 90, pitch: 4 });
    this.mark();
  }
  /** Aim the director's view at the middle of the cast. */
  private frameAll(force: boolean): void {
    const sp = this.spec();
    if (!sp) return;
    const p = this.posed();
    let n = 0, cx = 0, cz = 0, r = 1.2;
    const pts: [number, number][] = [];
    for (const a of sp.actors) {
      const st = p?.rt.actorState(a.id);
      const x = st ? st.pos[0] : a.at.pos[0], z = st ? st.pos[2] : a.at.pos[2];
      pts.push([x, z]);
      cx += x;
      cz += z;
      n++;
    }
    if (!n) return;
    cx /= n;
    cz /= n;
    for (const [x, z] of pts) r = Math.max(r, Math.hypot(x - cx, z - cz) + 1.2);
    this.orbit.target = [cx, 0.95, cz];
    if (force || this.framed !== this.sceneId) this.orbit.dist = clamp(r * 2.6, 3.5, 60);
    this.framed = this.sceneId ?? "";
    this.mark();
  }

  private mark(): void {
    this.dirty = true;
    if (!this.raf && this.on) this.raf = requestAnimationFrame(() => this.frame());
  }
  private frame(): void {
    this.raf = 0;
    if (!this.on) return;
    if (this.playing) {
      const sc = this.scene();
      const t = this.playAt + (performance.now() - this.playT0) / 1000;
      if (!sc || t >= this.end()) {
        this.t = this.end();
        this.stop();
      } else this.t = t;
      this.dirty = true;
      this.drawTimeline();
    }
    if (this.dirty) {
      this.dirty = false;
      this.draw();
    }
    if (this.playing) this.raf = requestAnimationFrame(() => this.frame());
  }

  private draw(): void {
    const sc = this.scene();
    const bw = this.viewBox.clientWidth, bh = this.viewBox.clientHeight;
    if (bw < 20 || bh < 20) return;
    const asp = sc ? sc.width / sc.height : 16 / 9;
    let w = bw, hh = Math.round(bw / asp);
    if (hh > bh) {
      hh = bh;
      w = Math.round(bh * asp);
    }
    for (const c of [this.cv, this.ov]) {
      c.style.width = `${w}px`;
      c.style.height = `${hh}px`;
      c.style.left = `${Math.round((bw - w) / 2)}px`;
      c.style.top = `${Math.round((bh - hh) / 2)}px`;
    }
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    // while a drag or playback is running a slow device drops to a lighter picture; a still frame is drawn at the chosen quality
    const busy = this.playing || !!this.vdrag;
    const q = busy && this.lastMs > 120 ? Math.max(0.2, this.quality * (this.lastMs > 400 ? 0.4 : 0.6)) : this.quality;
    const W = Math.max(160, Math.round((w * q * dpr) / 2) * 2), H = Math.max(90, Math.round((hh * q * dpr) / 2) * 2);
    if (this.cv.width !== W || this.cv.height !== H) {
      this.cv.width = W;
      this.cv.height = H;
    }
    const c = this.cv.getContext("2d")!;
    const ow = Math.round(w * dpr), oh = Math.round(hh * dpr);
    if (this.ov.width !== ow || this.ov.height !== oh) {
      this.ov.width = ow;
      this.ov.height = oh;
    }
    const o = this.ov.getContext("2d")!;
    o.setTransform(dpr, 0, 0, dpr, 0, 0);
    o.clearRect(0, 0, w, hh);
    this.posEl.textContent = `${this.t.toFixed(2)} s`;
    if (!sc) {
      c.fillStyle = "#0c1016";
      c.fillRect(0, 0, W, H);
      o.fillStyle = "#8a94a6";
      o.font = "13px system-ui, sans-serif";
      o.textAlign = "center";
      o.fillText("No stage in this project yet. Press “New stage”.", w / 2, hh / 2);
      return;
    }
    const l = this.layer(sc), spec = l.stage!;
    const t0 = performance.now();
    drawStage(c, l, sc, Math.round(this.t * sc.fps), W, H, () => this.mark(), this.view());
    const ms = performance.now() - t0;
    if (busy || this.lastMs === 0) this.lastMs = ms;
    else this.lastMs = Math.min(this.lastMs, ms);
    // a device without a fast graphics card draws slowly: step the preview down once, by itself (renders are not affected)
    if (!busy && !this.slowSaid && ms > 700 && this.quality > 0.5 && stageReady(spec)) {
      this.slowSaid = true;
      this.quality = 0.5;
      this.qualitySel.value = "0.5";
      this.say("This device draws the stage slowly: the preview is at half quality (the render is not affected).");
    }
    // the picture's inner frame (scope bars in the camera view)
    const sa = this.mode === "camera" ? (spec.look.aspect ?? 0) : 0;
    let ih = hh, iy = 0;
    if (sa > 0 && sa > w / hh + 0.01) {
      ih = w / sa;
      iy = (hh - ih) / 2;
    }
    this.inner = { x: 0, y: iy, w, h: ih };
    const rt = stageReady(spec) ? runtimeOf(l.id) : null;
    if (!rt) return;
    const aspect = w / ih;
    o.font = "600 11px system-ui, sans-serif";
    o.textAlign = "center";
    for (const a of spec.actors) {
      const st = rt.actorState(a.id);
      if (!st) continue;
      const hp = rt.project([st.head[0], st.head[1] + 0.22, st.head[2]], aspect);
      if (!hp.front) continue;
      const x = hp.x * w, y = iy + hp.y * ih;
      const on = a.id === this.actorId;
      const name = a.name ?? a.id;
      const tw = o.measureText(name).width + 10;
      o.fillStyle = on ? "rgba(62,230,196,0.92)" : "rgba(12,16,22,0.72)";
      o.beginPath();
      o.roundRect(x - tw / 2, y - 16, tw, 15, 4);
      o.fill();
      o.fillStyle = on ? "#04110e" : "#dfe6f0";
      o.fillText(name, x, y - 5);
      if (on) {
        const fp = rt.project([st.pos[0], st.pos[1] + 0.02, st.pos[2]], aspect);
        o.strokeStyle = "rgba(62,230,196,0.9)";
        o.lineWidth = 1.5;
        o.beginPath();
        o.ellipse(fp.x * w, iy + fp.y * ih, 16, 6, 0, 0, Math.PI * 2);
        o.stroke();
      }
    }
    if (this.mode === "free") {
      o.textAlign = "left";
      o.fillStyle = "rgba(223,230,240,0.6)";
      o.fillText("Director's view · drag to orbit · right-drag to pan · wheel to zoom", 8, hh - 8);
    }
    const err = stageError();
    if (err) this.say(err);
  }

  private bindView(): void {
    const ov = this.ov;
    ov.style.cursor = "grab";
    const at = (e: MouseEvent) => {
      const r = ov.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top };
    };
    ov.addEventListener("contextmenu", (e) => e.preventDefault());
    ov.addEventListener("mousedown", (e) => {
      ov.focus();
      this.vdrag = { ...at(e), pan: e.button === 2 || e.shiftKey, moved: false };
      const move = (ev: MouseEvent) => {
        const d = this.vdrag;
        if (!d) return;
        const p = at(ev), dx = p.x - d.x, dy = p.y - d.y;
        if (!d.moved && Math.hypot(dx, dy) < 4) return;
        d.moved = true;
        if (this.mode !== "free" || this.tool !== "select") return;
        d.x = p.x;
        d.y = p.y;
        const o = this.orbit;
        if (d.pan) {
          const k = o.dist / Math.max(200, this.inner.h) * 1.1, cy = Math.cos(o.yaw * D2R), sy = Math.sin(o.yaw * D2R);
          // screen right is (cos yaw, 0, -sin yaw); dragging up moves the target up
          o.target = [o.target[0] - dx * k * cy, clamp(o.target[1] + dy * k, -2, 30), o.target[2] + dx * k * sy];
        } else {
          o.yaw = (o.yaw - dx * 0.4) % 360;
          o.pitch = clamp(o.pitch + dy * 0.3, -8, 89);
        }
        this.mark();
      };
      const up = (ev: MouseEvent) => {
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
        const d = this.vdrag;
        this.vdrag = null;
        if (d && !d.moved) this.clickView(at(ev));
        this.mark();
      };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    });
    ov.addEventListener(
      "wheel",
      (e) => {
        e.preventDefault();
        if (this.mode !== "free") return;
        this.orbit.dist = clamp(this.orbit.dist * Math.exp(e.deltaY * 0.0012), 1.2, 120);
        this.mark();
      },
      { passive: false },
    );
    ov.addEventListener("keydown", (e) => this.key(e));
  }

  private clickView(p: { x: number; y: number }): void {
    const sp = this.spec(), ps = this.posed();
    if (!sp || !ps) return;
    const { rt, aspect } = ps, inn = this.inner;
    const nx = p.x / inn.w, ny = (p.y - inn.y) / inn.h;
    if (this.tool !== "select") {
      const g = rt.groundAt(nx, ny, aspect);
      const a = this.actor();
      if (!g || !a) {
        this.say(a ? "Click on the ground." : "Add a character first.");
        return;
      }
      const to = [r2(g[0]), r2(g[1])];
      if (this.tool === "walk") void this.run({ op: "stage.do", actor: a.id, actions: [{ t: r2(this.t), go: to, gait: this.gait }] });
      else void this.run({ op: "stage.actor", id: a.id, at: to });
      return;
    }
    let best: string | null = null, bd = 1e9;
    for (const a of sp.actors) {
      const st = rt.actorState(a.id);
      if (!st) continue;
      const hp = rt.project(st.head, aspect), fp = rt.project(st.pos, aspect);
      if (!hp.front) continue;
      const mx = ((hp.x + fp.x) / 2) * inn.w, my = inn.y + ((hp.y + fp.y) / 2) * inn.h;
      const half = Math.hypot((hp.x - fp.x) * inn.w, (hp.y - fp.y) * inn.h) / 2 + 14;
      const d = Math.hypot(p.x - mx, p.y - my);
      if (d < half && d < bd) {
        bd = d;
        best = a.id;
      }
    }
    if (best) {
      this.actorId = best;
      this.sel = { kind: "actor", id: best };
      this.refresh();
    }
  }
  private pickTool(t: StagePage["tool"]): void {
    if (!this.actor()) {
      this.say("Add a character first.");
      return;
    }
    this.tool = t;
    this.toolSel.value = t;
    this.ov.style.cursor = "crosshair";
    this.say(t === "walk" ? `Click the ground: ${this.actor()!.name ?? this.actor()!.id} will ${this.gait} there from ${this.t.toFixed(2)} s.` : "Click the ground to place the character.");
  }

  /* ------------------------------------------------------------ transport */

  private seek(t: number): void {
    this.t = clamp(t, 0, this.end());
    if (this.playing) {
      this.playAt = this.t;
      this.playT0 = performance.now();
    }
    this.drawTimeline();
    this.mark();
  }
  private toggle(): void {
    if (this.playing) return this.stop();
    if (!this.scene()) return;
    if (this.t >= this.end() - 0.02) this.t = 0;
    this.playing = true;
    this.playAt = this.t;
    this.playT0 = performance.now();
    this.playBtn.lastChild!.textContent = "Pause";
    this.mark();
  }
  private stop(): void {
    this.playing = false;
    this.playBtn.lastChild!.textContent = "Play";
    this.mark();
  }
  private key(e: KeyboardEvent): void {
    const sc = this.scene();
    if (!sc) return;
    if (e.code === "Space") {
      e.preventDefault();
      this.toggle();
    } else if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
      e.preventDefault();
      this.seek(this.t + (e.key === "ArrowLeft" ? -1 : 1) * (e.shiftKey ? 1 : 1 / sc.fps));
    } else if ((e.key === "Delete" || e.key === "Backspace") && this.sel && this.sel.kind !== "actor") {
      e.preventDefault();
      void this.run({ op: "stage.delete", ids: [this.sel.id] });
      this.sel = null;
    } else if (e.key === "Escape") {
      this.tool = "select";
      this.toolSel.value = "select";
      this.ov.style.cursor = "grab";
      this.say("");
    }
  }

  /* ------------------------------------------------------------ timeline */

  private x(t: number): number {
    return GUT + (t - this.t0) * this.pxSec;
  }
  private drawTimeline(): void {
    const cv = this.tl;
    const w = cv.clientWidth, hh = cv.clientHeight;
    if (w < 20 || hh < 20) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(hh * dpr)) {
      cv.width = Math.round(w * dpr);
      cv.height = Math.round(hh * dpr);
    }
    const c = cv.getContext("2d")!;
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.fillStyle = "#0e1218";
    c.fillRect(0, 0, w, hh);
    const end = this.end();
    // rows
    c.font = "11px system-ui, sans-serif";
    c.textBaseline = "middle";
    this.rows.forEach((row, i) => {
      const y = RULER + i * ROWH - this.rowTop;
      if (y + ROWH < RULER || y > hh) return;
      c.fillStyle = row.actor && row.actor === this.actorId ? "rgba(62,230,196,0.06)" : i % 2 ? "#10151c" : "#0e1218";
      c.fillRect(0, y, w, ROWH);
      for (const it of row.items) {
        const d = this.drag && this.drag.item.id === it.id ? this.drag : null;
        const a = it.t0 + (d && d.mode === "move" ? d.dt : 0), z = it.t1 + (d ? d.dt : 0);
        const x0 = Math.max(GUT, this.x(a)), x1 = Math.min(w, this.x(z));
        if (x1 <= GUT || x0 >= w) continue;
        const on = this.sel?.id === it.id;
        c.fillStyle = it.color;
        c.globalAlpha = on ? 1 : 0.72;
        c.beginPath();
        c.roundRect(x0 + 0.5, y + 3, Math.max(3, x1 - x0 - 1), ROWH - 6, 4);
        c.fill();
        c.globalAlpha = 1;
        if (on) {
          c.strokeStyle = "#fff";
          c.lineWidth = 1.5;
          c.stroke();
        }
        if (x1 - x0 > 26) {
          c.save();
          c.beginPath();
          c.rect(x0, y, x1 - x0 - 4, ROWH);
          c.clip();
          c.fillStyle = "#06090d";
          c.fillText(it.label, x0 + 6, y + ROWH / 2 + 0.5);
          c.restore();
        }
      }
    });
    // gutter
    c.fillStyle = "#121821";
    c.fillRect(0, 0, GUT, hh);
    this.rows.forEach((row, i) => {
      const y = RULER + i * ROWH - this.rowTop;
      if (y + ROWH < RULER || y > hh) return;
      c.fillStyle = row.actor && row.actor === this.actorId ? "#3ee6c4" : row.label.startsWith(" ") ? "#7a8597" : "#cfd7e3";
      c.fillText(row.label.length > 19 ? row.label.slice(0, 18) + "…" : row.label, 8, y + ROWH / 2);
    });
    // ruler
    c.fillStyle = "#161c26";
    c.fillRect(0, 0, w, RULER);
    const stepS = [0.1, 0.25, 0.5, 1, 2, 5, 10, 30].find((s) => s * this.pxSec >= 56) ?? 60;
    c.fillStyle = "#8a94a6";
    c.strokeStyle = "#263041";
    c.lineWidth = 1;
    for (let t = Math.ceil(this.t0 / stepS) * stepS; this.x(t) < w; t += stepS) {
      const x = Math.round(this.x(t)) + 0.5;
      if (x < GUT) continue;
      c.beginPath();
      c.moveTo(x, RULER - 6);
      c.lineTo(x, hh);
      c.stroke();
      c.fillText(`${Math.round(t * 100) / 100}s`, x + 3, RULER / 2);
    }
    // the end of the scene
    const xe = this.x(end);
    if (xe > GUT && xe < w) {
      c.fillStyle = "rgba(0,0,0,0.35)";
      c.fillRect(xe, RULER, w - xe, hh - RULER);
    }
    // issues from the last check
    for (const it of this.issues ?? []) {
      if (it.t === undefined || it.level === "note") continue;
      const x = this.x(it.t);
      if (x < GUT || x > w) continue;
      c.fillStyle = it.level === "bad" ? "#ef5d6c" : "#f2c94c";
      c.beginPath();
      c.moveTo(x - 5, 1);
      c.lineTo(x + 5, 1);
      c.lineTo(x, 9);
      c.fill();
    }
    // cursor
    const xc = this.x(this.t);
    if (xc >= GUT && xc <= w) {
      c.strokeStyle = "#ff5a5f";
      c.lineWidth = 1.5;
      c.beginPath();
      c.moveTo(xc, 0);
      c.lineTo(xc, hh);
      c.stroke();
    }
  }

  private hit(px: number, py: number): { item: Item; edge: boolean; row: Row } | null {
    const i = Math.floor((py - RULER + this.rowTop) / ROWH);
    const row = py >= RULER ? this.rows[i] : undefined;
    if (!row || px < GUT) return null;
    for (let k = row.items.length - 1; k >= 0; k--) {
      const it = row.items[k]!;
      const x0 = this.x(it.t0), x1 = this.x(it.t1);
      if (px >= x0 && px <= x1) return { item: it, edge: it.sized && px > x1 - 7 && x1 - x0 > 14, row };
    }
    return null;
  }

  private bindTimeline(): void {
    const cv = this.tl;
    const at = (e: MouseEvent) => {
      const r = cv.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top };
    };
    cv.addEventListener("mousemove", (e) => {
      if (this.drag) return;
      const p = at(e), hit = this.hit(p.x, p.y);
      cv.style.cursor = hit ? (hit.edge ? "ew-resize" : "pointer") : "default";
    });
    cv.addEventListener("mousedown", (e) => {
      cv.focus();
      const p = at(e);
      const hit = this.hit(p.x, p.y);
      if (!hit) {
        const i = Math.floor((p.y - RULER + this.rowTop) / ROWH);
        const row = p.y >= RULER ? this.rows[i] : undefined;
        if (row?.actor) this.actorId = row.actor;
        const scrub = (ev: MouseEvent) => this.seek((at(ev).x - GUT) / this.pxSec + this.t0);
        if (p.x >= GUT) scrub(e);
        const up = () => {
          window.removeEventListener("mousemove", scrub);
          window.removeEventListener("mouseup", up);
          this.refresh();
        };
        if (p.x >= GUT) window.addEventListener("mousemove", scrub);
        window.addEventListener("mouseup", up);
        return;
      }
      this.sel = { kind: hit.item.kind, id: hit.item.id };
      if (hit.item.actor) this.actorId = hit.item.actor;
      this.drag = { item: hit.item, mode: hit.edge ? "size" : "move", x0: p.x, dt: 0, moved: false };
      const move = (ev: MouseEvent) => {
        const d = this.drag;
        if (!d) return;
        const dx = at(ev).x - d.x0;
        if (!d.moved && Math.abs(dx) < 4) return;
        d.moved = true;
        const sc = this.scene();
        const fr = sc ? 1 / sc.fps : 1 / 24;
        let dt = Math.round(dx / this.pxSec / fr) * fr;
        if (d.mode === "move") dt = Math.max(-d.item.t0, dt);
        else dt = Math.max(fr * 2 - (d.item.t1 - d.item.t0), dt);
        d.dt = dt;
        this.drawTimeline();
      };
      const up = () => {
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
        const d = this.drag;
        this.drag = null;
        if (d && d.moved && Math.abs(d.dt) > 1e-4) void this.retime(d.item, d.mode, d.dt);
        else this.refresh();
      };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
    });
    cv.addEventListener("dblclick", (e) => {
      const p = at(e), hit = this.hit(p.x, p.y);
      if (hit) this.seek(hit.item.t0);
    });
    cv.addEventListener(
      "wheel",
      (e) => {
        e.preventDefault();
        const p = at(e);
        if (e.ctrlKey || e.metaKey) {
          const t = (p.x - GUT) / this.pxSec + this.t0;
          this.pxSec = clamp(this.pxSec * Math.exp(-e.deltaY * 0.002), 6, 600);
          this.t0 = Math.max(0, t - (p.x - GUT) / this.pxSec);
        } else if (e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY)) this.t0 = Math.max(0, this.t0 + (e.deltaX || e.deltaY) / this.pxSec);
        else this.rowTop = clamp(this.rowTop + e.deltaY, 0, Math.max(0, this.rows.length * ROWH + RULER - cv.clientHeight + 8));
        this.drawTimeline();
      },
      { passive: false },
    );
    cv.addEventListener("keydown", (e) => this.key(e));
  }

  private async retime(it: Item, mode: "move" | "size", dt: number): Promise<void> {
    const t0 = r2(it.t0 + (mode === "move" ? dt : 0)), t1 = r2(it.t1 + dt);
    let patch: Record<string, number>;
    if (it.kind === "block" || it.kind === "line") patch = { t: t0 };
    else if (it.kind === "shot") patch = mode === "move" ? { t: t0 } : { dur: r2(t1 - it.t0) };
    else patch = mode === "move" ? { t0, t1 } : { t1 };
    await this.run({ op: "stage.edit", id: it.id, patch });
  }

  /* ------------------------------------------------------------ inspector */

  private find(id: string): Record<string, unknown> | null {
    const sp = this.spec();
    if (!sp) return null;
    const lists: { id: string }[][] = [sp.shots, sp.fx, ...sp.actors.flatMap((x) => [x.blocks as { id: string }[], x.overlays as { id: string }[], x.lines as { id: string }[]])];
    for (const l of lists) {
      const it = l.find((x) => x.id === id);
      if (it) return it as unknown as Record<string, unknown>;
    }
    return null;
  }

  private drawInspector(): void {
    const el = this.inspEl, sp = this.spec(), sc = this.scene();
    el.replaceChildren();
    if (!sp || !sc) {
      el.append(h("div", { class: "mu-about" }, "The 3D Stage: cast characters, give them captured performances, choreograph fights, place cameras, and look at the result from any side."));
      return;
    }
    const sel = this.sel;
    const item = sel && sel.kind !== "actor" ? this.find(sel.id) : null;
    if (item && sel) {
      el.append(h("div", { class: "mu-h" }, `${sel.kind} · ${String(item.kind ?? "")}`));
      for (const [k, v] of Object.entries(item)) {
        if (k === "id" || k === "kind" || k === "mouth" || k === "assetId") continue;
        el.append(this.prop(k, v, (nv) => void this.run({ op: "stage.edit", id: sel.id, patch: { [k]: nv } })));
      }
      el.append(
        h(
          "div",
          { class: "mu-btns" },
          b("Go to", "start", () => this.seek(Number(item.t ?? item.t0 ?? 0)), "", "Move the cursor to its start"),
          b("Delete", "trash", () => {
            void this.run({ op: "stage.delete", ids: [sel.id] });
            this.sel = null;
          }, "", "Remove it from the stage"),
        ),
      );
      el.append(h("div", { class: "mu-about" }, "Drag it on the timeline to retime it; drag its right edge to make it longer. Every change can be undone (Ctrl+Z)."));
    } else if (sel?.kind === "actor" && this.actor()) {
      const a = this.actor()!;
      el.append(h("div", { class: "mu-h" }, `Character · ${a.name ?? a.id}`));
      el.append(field("Start x, z", this.text(`${r2(a.at.pos[0])}, ${r2(a.at.pos[2])}`, (v) => {
        const [x, z] = v.split(/[,\s]+/).map(Number);
        if (Number.isFinite(x) && Number.isFinite(z)) void this.run({ op: "stage.actor", id: a.id, at: [x, z] });
      })));
      el.append(field("Facing (°)", this.text(String(Math.round(a.at.yaw)), (v) => Number.isFinite(Number(v)) && void this.run({ op: "stage.actor", id: a.id, yaw: Number(v) }))));
      el.append(field("Look", selectEl(["", ...Object.keys(CAST)].map((k) => [k, k || "(keep)"]) as [string, string][], "", (v) => v && void this.run({ op: "stage.actor", id: a.id, preset: v }))));
      el.append(field("Kit", selectEl(KITS.map((k) => [k, k || "none"]) as [string, string][], a.kit ?? "", (v) => void this.run({ op: "stage.actor", id: a.id, kit: v || "none" }))));
      el.append(field("Mood", selectEl(["", ...Object.keys(MOODS)].map((k) => [k, k || "(keep)"]) as [string, string][], "", (v) => v && void this.run({ op: "stage.actor", id: a.id, mood: v }))));
      el.append(field("Height (m)", this.text(String(a.char.height ?? ""), (v) => Number(v) > 0.5 && void this.run({ op: "stage.actor", id: a.id, height: Number(v) }))));
      el.append(
        h(
          "div",
          { class: "mu-btns" },
          b("Clear actions", null, () => void this.run({ op: "stage.delete", actor: a.id, clear: "blocks" }), "", "Remove this character's actions"),
          b("Remove", "trash", () => {
            void this.run({ op: "stage.delete", actor: a.id });
            this.sel = null;
            this.actorId = null;
          }, "", "Take this character off the stage"),
        ),
      );
    } else {
      el.append(h("div", { class: "mu-h" }, "Stage"));
      el.append(field("Set", selectEl(ENV_PRESETS.map((k) => [k, k]) as [string, string][], sp.env.preset, (v) => void this.run({ op: "stage.set", env: { preset: v } }))));
      el.append(field("Time of day", selectEl(["", ...Object.keys(TIMES)].map((k) => [k, k || "default"]) as [string, string][], sp.env.time ?? "", (v) => void this.run({ op: "stage.set", env: { time: v || undefined } }))));
      el.append(field("Look", selectEl([["cinematic", "Cinematic"], ["anime", "Anime (cel + ink)"]] as [string, string][], sp.look.style ?? "cinematic", (v) => void this.run({ op: "stage.set", look: { style: v } }))));
      el.append(field("Timing", selectEl([["1", "Every frame (smooth)"], ["2", "On twos (anime)"], ["3", "On threes"]] as [string, string][], String(sp.look.step ?? 1), (v) => void this.run({ op: "stage.set", look: { step: Number(v) } }))));
      el.append(field("Frame", selectEl([["0", "Full frame"], ["2.39", "Scope 2.39:1"], ["1.85", "Flat 1.85:1"]] as [string, string][], String(sp.look.aspect ?? 0), (v) => void this.run({ op: "stage.set", look: { aspect: Number(v) } }))));
      el.append(field("Length (s)", this.text(String(r2(sc.frames / sc.fps)), (v) => Number(v) > 0.4 && void this.run({ op: "stage.set", seconds: Number(v) }))));
      el.append(h("div", { class: "mu-about" }, `${sp.actors.length} characters · ${sp.shots.length} shots · ${sp.fx.length} effects · ${r2(Math.max(stageDuration(sp), 0))} s of action`));
    }
    if (this.issues) {
      el.append(h("div", { class: "mu-h" }, this.issues.length ? `Check · ${this.issues.length} to look at` : "Check · nothing found"));
      for (const it of this.issues.slice(0, 40)) {
        const row = h("button", { class: `sg-issue ${it.level}`, type: "button" }, h("b", {}, it.t !== undefined ? `${it.t.toFixed(2)} s` : it.level), h("span", {}, `${it.actor ? it.actor + ": " : ""}${it.what}`));
        row.addEventListener("click", () => it.t !== undefined && this.seek(it.t));
        el.append(row);
      }
    }
  }

  private text(value: string, onChange: (v: string) => void): HTMLInputElement {
    const inp = h("input", { class: "inp in", value }) as HTMLInputElement;
    inp.addEventListener("change", () => onChange(inp.value.trim()));
    return inp;
  }
  /** One editable field of a stage item: numbers, words and yes/no directly, anything else as JSON. */
  private prop(k: string, v: unknown, set: (v: unknown) => void): HTMLElement {
    if (typeof v === "boolean") return field(k, selectEl([["true", "yes"], ["false", "no"]] as [string, string][], String(v), (x) => set(x === "true")));
    if (typeof v === "number") return field(k, this.text(String(r2(v)), (x) => Number.isFinite(Number(x)) && set(Number(x))));
    if (typeof v === "string") return field(k, this.text(v, (x) => set(x)));
    return field(k, this.text(JSON.stringify(v, (_k, x) => (typeof x === "number" ? Math.round(x * 1000) / 1000 : x)), (x) => {
      try {
        set(JSON.parse(x));
      } catch {
        this.say(`${k}: that is not valid JSON.`);
      }
    }));
  }

  /* ------------------------------------------------------------ adding things */

  private form(title: string, rows: [string, HTMLElement][], ok: string, go: () => void | Promise<void>, wide = false): void {
    const body = h("div", { class: `sg-form ${wide ? "wide" : ""}` }, ...rows.map(([l, c]) => (l ? field(l, c) : c)));
    const m = modal(title, body, [
      b("Cancel", null, () => m.close()),
      b(ok, null, () => {
        m.close();
        void go();
      }, "pri"),
    ]);
  }
  private sel2(opts: (string | [string, string])[], v: string): HTMLSelectElement {
    return selectEl(opts.map((o) => (Array.isArray(o) ? o : [o, o || "—"])) as [string, string][], v, () => undefined);
  }
  private inp(v: string, ph = ""): HTMLInputElement {
    return h("input", { class: "inp in", value: v, placeholder: ph }) as HTMLInputElement;
  }
  private needActor(): StageActor | null {
    const a = this.actor();
    if (!a) this.say("Add a character first.");
    return a;
  }

  private newStage(): void {
    const name = this.inp("Stage"), env = this.sel2([...ENV_PRESETS], "meadow"), time = this.sel2(["", ...Object.keys(TIMES)], "dusk"), look = this.sel2([["cinematic", "Cinematic"], ["anime", "Anime (cel + ink)"]], "cinematic"), sec = this.inp("12");
    this.form("New stage", [["Name", name], ["Set", env], ["Time of day", time], ["Look", look], ["Seconds", sec]], "Create", async () => {
      const r = (await this.run({ op: "stage.new", name: name.value || "Stage", env: env.value, time: time.value || undefined, look: look.value, seconds: Number(sec.value) || 12 })) as { sceneId?: string } | null;
      if (r?.sceneId) {
        this.sceneId = r.sceneId;
        this.sel = null;
        this.t = 0;
        this.framed = "";
        this.refresh();
      }
    });
  }

  private addActor(): void {
    if (!this.scene()) return this.say("Make a stage first.");
    const n = (this.spec()?.actors.length ?? 0) + 1;
    const id = this.inp(`actor${n}`), preset = this.sel2(Object.keys(CAST), "swordsman"), kit = this.sel2(KITS.map((k) => [k, k || "none"]) as [string, string][], ""), at = this.inp(`${(n - 1) * 1.2}, 0`), yaw = this.inp("0");
    this.form("Add a character", [["Name", id], ["Look", preset], ["Kit (props, cape)", kit], ["Start x, z (m)", at], ["Facing (°)", yaw]], "Add", async () => {
      const [x, z] = at.value.split(/[,\s]+/).map(Number);
      const nm = id.value.trim().replace(/\s+/g, "_") || `actor${n}`;
      const r = await this.run({ op: "stage.actor", id: nm, preset: preset.value, kit: kit.value || undefined, at: [Number.isFinite(x) ? x : 0, Number.isFinite(z) ? z : 0], yaw: Number(yaw.value) || 0 });
      if (r) {
        this.actorId = nm;
        this.sel = { kind: "actor", id: nm };
        this.framed = "";
        this.refresh();
      }
    });
  }

  private addSimple(action: Record<string, unknown>): void {
    const a = this.needActor();
    if (a) void this.run({ op: "stage.do", actor: a.id, actions: [{ t: r2(this.t), ...action }] });
  }

  private addMotion(): void {
    const a = this.needActor();
    if (!a) return;
    const q = this.inp("", "walk, kick, sit down, dance, wave…");
    const list = h("div", { class: "sg-motions" });
    const strip = h("div", { class: "sg-strip" });
    let chosen = "";
    const search = async () => {
      const res = ((await this.run({ op: "stage.motions", q: q.value || undefined, limit: 40 })) ?? []) as { clip: string; desc: string; seconds: number; speed: number }[];
      list.replaceChildren();
      for (const m of res) {
        const row = h("button", { class: "mu-pi", type: "button" }, h("b", {}, m.desc || m.clip), h("span", {}, ` ${m.clip} · ${r2(m.seconds)} s${m.speed > 0.2 ? ` · ${r2(m.speed)} m/s` : ""}`));
        row.addEventListener("click", async () => {
          chosen = m.clip;
          for (const x of list.children) x.classList.toggle("on", x === row);
          strip.replaceChildren(h("span", { class: "mu-about" }, "Drawing the motion…"));
          const r = (await this.run({ op: "stage.motion", clip: m.clip, count: 8, cell: 150 })) as { jpeg?: string } | null;
          strip.replaceChildren(r?.jpeg ? h("img", { src: r.jpeg, class: "sg-stripimg" }) : h("span", { class: "mu-about" }, "No preview."));
        });
        list.append(row);
      }
      if (!res.length) list.append(h("div", { class: "mu-about" }, "Nothing found. Try other words."));
    };
    q.addEventListener("keydown", (e) => e.key === "Enter" && void search());
    void search();
    this.form(`A motion for ${a.name ?? a.id} at ${this.t.toFixed(2)} s`, [["", h("div", { class: "row" }, q, b("Search", "search", () => void search()))], ["", list], ["", strip]], "Use it", async () => {
      if (chosen) await this.run({ op: "stage.do", actor: a.id, actions: [{ t: r2(this.t), clip: chosen }] });
      else this.say("Pick a motion first.");
    }, true);
  }

  private addLine(): void {
    const a = this.needActor();
    if (!a) return;
    const text = h("textarea", { class: "inp mu-lyrics", rows: "3", placeholder: "Hindi, Hinglish or English: “Tum sach mein jeet gaye?”" }) as HTMLTextAreaElement;
    const voice = this.sel2(VOICES, "hm_omega"), tone = this.sel2(Object.keys(TONES), "neutral");
    const others = (this.spec()?.actors ?? []).filter((x) => x.id !== a.id).map((x) => x.id);
    const look = this.sel2(["", "camera", ...others], others[0] ?? "");
    this.form(`${a.name ?? a.id} says…`, [["Line", text], ["Voice", voice], ["Tone", tone], ["Speaks to", look]], "Add the line", async () => {
      if (!text.value.trim()) return;
      this.say("Speaking the line…");
      await this.run({ op: "stage.say", actor: a.id, text: text.value.trim(), t: r2(this.t), voice: voice.value, tone: tone.value, look: look.value || undefined });
    }, true);
  }

  private addLook(): void {
    const a = this.needActor();
    if (!a) return;
    const others = (this.spec()?.actors ?? []).filter((x) => x.id !== a.id).map((x) => x.id);
    const to = this.sel2([...others, "camera"], others[0] ?? "camera"), dur = this.inp("3");
    this.form(`${a.name ?? a.id} looks at…`, [["Who", to], ["Seconds", dur]], "Add", () => void this.run({ op: "stage.overlay", actor: a.id, add: [{ t0: r2(this.t), t1: r2(this.t + (Number(dur.value) || 3)), look: to.value }] }));
  }
  private addFace(): void {
    const a = this.needActor();
    if (!a) return;
    const mood = this.sel2(Object.keys(MOODS), "angry"), dur = this.inp("2");
    this.form(`${a.name ?? a.id}'s expression`, [["Face", mood], ["Seconds", dur]], "Add", () => void this.run({ op: "stage.overlay", actor: a.id, add: [{ t0: r2(this.t), t1: r2(this.t + (Number(dur.value) || 2)), face: mood.value }] }));
  }

  private addFight(): void {
    const sp = this.spec();
    if (!sp || sp.actors.length < 2) return this.say("A fight needs two characters on the stage.");
    const ids = sp.actors.map((x) => x.id);
    const first = this.actorId ?? ids[0]!;
    const A = this.sel2(ids, first), B = this.sel2(ids, ids.find((x) => x !== first) ?? ids[1]!);
    const style = this.sel2([["fists", "Unarmed"], ["sword", "Swords (both must carry one)"]], "fists");
    const beats = h("textarea", { class: "inp mu-lyrics", rows: "7" }) as HTMLTextAreaElement;
    beats.value = "a jab miss slip\na cross hit\nb hook miss duck\nb body hit\na onetwo hit\na highkick hit ko";
    const moves = Object.keys(FIGHT_MOVES).join(", ");
    this.form("Choreograph a fight", [
      ["Attacker (a)", A], ["Defender (b)", B], ["Style", style], ["Beats", beats],
      ["", h("div", { class: "mu-about" }, `One beat per line: who (a or b), the move, then hit or miss with the answer (parry, block, dodge, duck, slip), and ko to end it. Extras: power=1.5, gap=0.4, push=0.3. Moves: ${moves}.`)],
    ], "Stage the fight", async () => {
      const list: Record<string, unknown>[] = [];
      for (const ln of beats.value.split("\n")) {
        const w = ln.trim().toLowerCase().split(/[\s,]+/).filter(Boolean);
        if (!w.length) continue;
        const bt: Record<string, unknown> = {};
        for (const x of w) {
          if (x === "a" || x === "b") bt.by = x;
          else if (FIGHT_MOVES[x]) bt.do = x;
          else if (x === "hit") bt.hit = true;
          else if (x === "miss") bt.hit = false;
          else if (["parry", "block", "dodge", "duck", "slip"].includes(x)) {
            bt.defend = x;
            bt.hit = false;
          } else if (x === "ko") {
            bt.ko = true;
            bt.hit = true;
          } else if (/^(power|gap|push|speed)=/.test(x)) bt[x.split("=")[0]!] = Number(x.split("=")[1]);
        }
        if (bt.do) list.push(bt);
      }
      if (!list.length) return this.say("Write at least one beat, such as “a jab hit”.");
      if (A.value === B.value) return this.say("Pick two different characters.");
      this.say("Staging the fight…");
      await this.run({ op: "stage.fight", a: A.value, b: B.value, t: r2(this.t), style: style.value, beats: list });
    }, true);
  }

  private addShot(): void {
    const sp = this.spec();
    if (!sp) return;
    const ids = sp.actors.map((x) => x.id);
    const kind = this.sel2(SHOTS, "medium"), on = this.sel2(ids, this.actorId ?? ids[0] ?? ""), other = this.sel2(["", ...ids], ""), dur = this.inp("3"), yaw = this.inp("28"), move = this.sel2(MOVES.map((m) => [m, m || "still"]) as [string, string][], "");
    this.form(`A shot at ${this.t.toFixed(2)} s`, [["Framing", kind], ["Subject", on], ["Other (two, ots)", other], ["Seconds", dur], ["Angle (° from their front)", yaw], ["Camera move", move]], "Add the shot", () =>
      void this.run({ op: "stage.cam", shots: [{ t: r2(this.t), dur: Number(dur.value) || 3, kind: kind.value, on: on.value || undefined, other: other.value || undefined, yaw: Number(yaw.value), move: move.value ? { kind: move.value } : undefined }] }),
    );
  }
  private addFx(): void {
    if (!this.scene()) return;
    const kind = this.sel2(FX, "impact"), dur = this.inp("0.6"), strength = this.inp("1");
    const a = this.actor(), st = a ? this.posed()?.rt.actorState(a.id) : null;
    const pos = this.inp(st ? `${r2(st.pos[0])}, 1, ${r2(st.pos[2])}` : "0, 1, 0");
    this.form(`An effect at ${this.t.toFixed(2)} s`, [["Effect", kind], ["Seconds", dur], ["Strength", strength], ["Where x, y, z (m)", pos]], "Add", () => {
      const p = pos.value.split(/[,\s]+/).map(Number).filter((x) => Number.isFinite(x));
      void this.run({ op: "stage.fx", add: [{ kind: kind.value, t: r2(this.t), dur: Number(dur.value) || 0.6, strength: Number(strength.value) || 1, pos: p.length >= 2 ? p : undefined }] });
    });
  }

  /* ------------------------------------------------------------ review */

  private async check(): Promise<void> {
    if (!this.scene()) return;
    this.say("Checking the stage…");
    const r = (await this.run({ op: "stage.check", step: 1 / 12 })) as { issues?: StagePage["issues"] } | null;
    if (!r) return;
    this.issues = r.issues ?? [];
    const bad = this.issues!.filter((x) => x.level === "bad").length;
    this.say(bad ? `${bad} problem${bad > 1 ? "s" : ""} to fix.` : "No problems found.");
    this.sel = null;
    this.refresh();
  }
  private async sheet(): Promise<void> {
    if (!this.scene()) return;
    this.say("Drawing the contact sheet…");
    const r = (await this.run({ op: "stage.sheet", count: 8, cols: 4, cell: 360 })) as { jpeg?: string } | null;
    if (!r?.jpeg) return;
    const img = h("img", { src: r.jpeg, style: "max-width:100%;max-height:74vh;display:block;border-radius:8px;margin:0 auto" });
    modal("Contact sheet · the film at eight moments", img);
    (img.parentElement as HTMLElement).style.width = "min(1180px, 94vw)";
  }
}
