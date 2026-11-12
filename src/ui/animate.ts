/**
 * 2D animation workstation: draw with pressure on layers, frame by frame (x-sheet holds), onion skin,
 * reference/lightbox layers, bucket fill, lasso transform, shapes, symmetry, auto-inbetweens and playback.
 */
import type { Studio } from "../app/studio";
import { emptyCel, exposedKey, findAsset, uid, type AnimLayer, type AnimScene, type BrushTool, type Cel, type Stroke } from "../core/model";
import * as A from "../anim/ops";
import { bucketFill, ctx2d, drawStroke, makeCanvas, renderScene } from "../anim/render";
import * as ops from "../core/ops";
import * as R from "../anim/rig";
import * as PU from "../anim/puppet";
import * as T3 from "../anim/three3d";
import { importBlob } from "../media/library";
import { valueAt } from "../core/keyframes";
import { saveBlob } from "../native/storage";
import { btn, colorInput, confirmBox, h, icon, pickFiles, select, sheet, slider, toast, toggle } from "./dom";
import { ANIME_MOVES, addBeat, addMove, addSceneFx } from "../agent/anime";
import { FX_TYPES } from "../anim/proc";

type Tool = BrushTool | "fill" | "lasso" | "line" | "rect" | "ellipse" | "picker" | "hand" | "pose";

/** Where a stroke was started: it is kept there even if the playhead or scene moves before release. */
interface DrawTarget {
  sceneId: string | null;
  layerId: string | null;
  frame: number;
}

interface View {
  x: number;
  y: number;
  zoom: number;
  rot: number;
}

const BRUSHES: [BrushTool, string, string][] = [
  ["ink", "Ink", "pencil"],
  ["pen", "Pen", "pencil"],
  ["pencil", "Pencil", "pencil"],
  ["brush", "Brush", "brush"],
  ["marker", "Marker", "brush"],
  ["airbrush", "Air", "brush"],
  ["eraser", "Eraser", "eraser"],
];

export class AnimateView {
  readonly el: HTMLElement;
  private stage: HTMLElement;
  private board: HTMLElement;
  private base: HTMLCanvasElement;
  private live: HTMLCanvasElement;
  private strip: HTMLElement;
  private toolsEl: HTMLElement;
  private header: HTMLElement;
  sceneId: string | null = null;
  layerId: string | null = null;
  frame = 0;
  tool: Tool = "ink";
  color = "#111111";
  size = 6;
  opacity = 1;
  stabilizer = 0.35;
  symmetry = false;
  onion = { on: true, before: 2, after: 1, opacity: 0.35 };
  private view: View = { x: 0, y: 0, zoom: 1, rot: 0 };
  private fit = 1;
  private res = 1;
  private playing = 0;
  private selection: { ids: Set<string>; box: [number, number, number, number] } | null = null;
  private drawQueued = false;
  private penSeen = false;
  private active = false;

  constructor(private readonly s: Studio) {
    this.base = h("canvas", { class: "an-base" });
    this.live = h("canvas", { class: "an-live" });
    this.board = h("div", { class: "an-board" }, this.base, this.live);
    this.stage = h("div", { class: "an-stage" }, this.board);
    this.header = h("div", { class: "an-head" });
    this.toolsEl = h("div", { class: "an-tools" });
    this.strip = h("div", { class: "an-strip" });
    this.el = h("div", { class: "view animate" }, this.header, this.stage, this.toolsEl, this.strip);
    new ResizeObserver(() => this.layout()).observe(this.stage);
    s.on("change", () => {
      if (!this.active) return;
      this.ensureScene();
      this.queue();
      this.drawStrip();
    });
    s.on("project", () => {
      // Keep the scene on screen when it still exists (only a different project should jump to its first scene).
      if (!s.project.scenes.some((x) => x.id === this.sceneId)) this.sceneId = null;
      if (this.active) this.open();
    });
    this.input();
  }

  /** Called when the tab is shown. */
  open(sceneId?: string): void {
    this.active = true;
    if (sceneId) this.sceneId = sceneId;
    this.ensureScene();
    this.drawHeader();
    this.drawTools();
    this.drawStrip();
    this.layout();
  }

  hide(): void {
    this.active = false;
    this.stop();
  }

  /** Live mode: show a scene at a frame (the strip scrolls there and the canvas redraws). */
  showFrame(sceneId: string, frame: number): void {
    if (this.sceneId !== sceneId) this.open(sceneId);
    const sc = this.scene();
    if (!sc) return;
    this.frame = Math.max(0, Math.min(sc.frames - 1, Math.round(frame)));
    this.drawHeader();
    this.drawStrip();
    this.queue();
  }

  /** Live mode: which scene is on screen. */
  get currentScene(): string | null {
    return this.sceneId;
  }

  private scene(): AnimScene | undefined {
    return this.s.project.scenes.find((x) => x.id === this.sceneId);
  }

  private layer(): AnimLayer | undefined {
    return this.scene()?.layers.find((l) => l.id === this.layerId);
  }

  private ensureScene() {
    const p = this.s.project;
    if (!this.scene()) this.sceneId = p.scenes[0]?.id ?? null;
    const sc = this.scene();
    if (!sc) return;
    if (!this.layer() || this.layer()!.kind !== "draw") this.layerId = sc.layers.find((l) => l.kind === "draw")?.id ?? null;
    this.frame = Math.max(0, Math.min(sc.frames - 1, this.frame));
  }

  /* --------------------------------- layout -------------------------------- */

  private layout() {
    const sc = this.scene();
    if (!sc) return;
    const r = this.stage.getBoundingClientRect();
    this.fit = Math.min((r.width - 16) / sc.width, (r.height - 16) / sc.height);
    this.setRes(this.wantedRes(), true);
    this.applyView();
    this.queue();
  }

  /** Canvas pixels per scene pixel that match what is on screen (fit × zoom × device pixels), so zooming stays sharp. */
  private wantedRes(): number {
    const sc = this.scene();
    if (!sc) return 1;
    const dpr = Math.min(3, window.devicePixelRatio || 1);
    const want = Math.max(0.25, this.fit * this.view.zoom * dpr);
    const cap = 4096 / Math.max(sc.width, sc.height);
    return Math.min(want, cap);
  }

  /** Resize the drawing canvases; a change re-renders, a small change is ignored to avoid churn while pinching. */
  private setRes(res: number, force = false) {
    const sc = this.scene();
    if (!sc) return;
    if (!force && Math.abs(res - this.res) / this.res < 0.2) return;
    this.res = res;
    const W = Math.max(2, Math.round(sc.width * res));
    const H = Math.max(2, Math.round(sc.height * res));
    if (this.base.width !== W || this.base.height !== H) {
      this.base.width = this.live.width = W;
      this.base.height = this.live.height = H;
    }
    this.queue();
  }

  private applyView() {
    if (this.scene()) this.setRes(this.wantedRes());
    const v = this.view;
    const sc = this.scene();
    // The board is laid out in scene pixels; the canvases are rasterised at `res`, so zoom never stretches a blurry bitmap.
    if (sc) {
      this.board.style.width = `${sc.width}px`;
      this.board.style.height = `${sc.height}px`;
    }
    this.board.style.transform = `translate(-50%, -50%) translate(${v.x}px, ${v.y}px) rotate(${v.rot}deg) scale(${this.fit * v.zoom})`;
  }

  /** Screen point → scene pixel. */
  private toScene(cx: number, cy: number): [number, number] {
    const r = this.stage.getBoundingClientRect();
    const v = this.view;
    let x = cx - (r.left + r.width / 2) - v.x;
    let y = cy - (r.top + r.height / 2) - v.y;
    const a = (-v.rot * Math.PI) / 180;
    [x, y] = [x * Math.cos(a) - y * Math.sin(a), x * Math.sin(a) + y * Math.cos(a)];
    const k = this.fit * v.zoom;
    const sc = this.scene()!;
    return [x / k + sc.width / 2, y / k + sc.height / 2];
  }

  queue(): void {
    if (this.drawQueued) return;
    this.drawQueued = true;
    requestAnimationFrame(() => {
      this.drawQueued = false;
      this.render();
    });
  }

  private render() {
    const sc = this.scene();
    const x = ctx2d(this.base) as CanvasRenderingContext2D;
    if (!sc) {
      x.clearRect(0, 0, this.base.width, this.base.height);
      return;
    }
    renderScene(x, sc, this.frame, {
      scale: this.res,
      onion: this.onion.on && !this.playing ? this.onion : null,
      images: (id) => this.s.pool.imageNow(id),
      onReady: () => this.queue(),
    });
    for (const l of sc.layers) if (l.kind === "reference" && l.assetId) {
      const a = findAsset(this.s.project, l.assetId);
      if (a && !this.s.pool.imageNow(a.id)) void this.s.pool.image(a).then(() => this.queue());
    }
    this.drawSelection();
  }

  ik = true;
  poseEase: "inOut" | "linear" | "out" | "in" | "back" | "hold" = "inOut";
  private copiedPose: R.Pose | null = null;
  activePart: string | null = null;

  /** Skeleton overlay for the Pose tool: bones, joints, and IK handles on hands and feet. */
  private drawSkeleton(lx: CanvasRenderingContext2D) {
    const sc = this.scene();
    if (!sc || this.tool !== "pose") return;
    let bones: ReturnType<typeof R.skeleton> = [];
    try {
      bones = R.skeleton(sc, undefined, this.frame);
    } catch {
      return;
    }
    lx.setTransform(this.res, 0, 0, this.res, 0, 0);
    const k = 1 / this.res / Math.max(0.5, this.fit * this.view.zoom);
    for (const b of bones) {
      const on = b.part === this.activePart;
      lx.strokeStyle = on ? "#ffb74d" : "rgba(47,123,255,0.85)";
      lx.lineWidth = (on ? 7 : 5) * k;
      lx.beginPath();
      lx.moveTo(b.pivot[0], b.pivot[1]);
      lx.lineTo(b.tip[0], b.tip[1]);
      lx.stroke();
    }
    for (const b of bones) {
      const end = this.ik && (b.part === "handF" || b.part === "handB" || b.part === "footF" || b.part === "footB");
      lx.fillStyle = b.part === "hips" ? "#ff5b5b" : end ? "#3ccf8e" : "#ffffff";
      lx.strokeStyle = "#1a1d26";
      lx.lineWidth = 3 * k;
      lx.beginPath();
      lx.arc(b.pivot[0], b.pivot[1], (b.part === "hips" ? 14 : end ? 13 : 9) * k, 0, Math.PI * 2);
      lx.fill();
      lx.stroke();
    }
  }

  private drawSelection() {
    const lx = ctx2d(this.live) as CanvasRenderingContext2D;
    lx.setTransform(1, 0, 0, 1, 0, 0);
    lx.clearRect(0, 0, this.live.width, this.live.height);
    this.drawSkeleton(lx);
    if (!this.selection) return;
    const [x0, y0, x1, y1] = this.selection.box;
    lx.setTransform(this.res, 0, 0, this.res, 0, 0);
    lx.setLineDash([8, 6]);
    lx.lineWidth = 2 / this.res;
    lx.strokeStyle = "#2f7bff";
    lx.strokeRect(x0, y0, x1 - x0, y1 - y0);
    lx.setLineDash([]);
  }

  /* --------------------------------- header -------------------------------- */

  private drawHeader() {
    const s = this.s;
    const sc = this.scene();
    const pick = h("select", { "aria-label": "Scene" }) as HTMLSelectElement;
    for (const x of s.project.scenes) pick.append(h("option", { value: x.id, selected: x.id === this.sceneId }, x.name));
    pick.addEventListener("change", () => {
      this.sceneId = pick.value;
      this.frame = 0;
      this.selection = null;
      this.open();
    });
    this.header.replaceChildren(
      ...([
      s.project.scenes.length ? pick : h("span", { class: "muted" }, "No scene yet"),
      btn("New scene", "plus", () => {
        const n = s.mutate("new scene", (p) => A.createScene(p));
        this.sceneId = n.id;
        this.frame = 0;
        this.open();
      }, "icon-only ghost"),
      sc ? btn("Scene settings", "sliders", () => this.sceneSettings(), "icon-only ghost") : null,
      sc ? btn("Layers", "layers", () => this.layersSheet(), "ghost") : null,
      sc ? btn("Rig", "bone", () => this.rigSheet(), "ghost") : null,
      sc ? btn("Character", "user", () => this.characterSheet(), "ghost") : null,
      sc ? btn("Action", "spark", () => this.actionSheet(), "ghost") : null,
      sc ? btn("3D", "cube", () => this.threeSheet(), "ghost") : null,
      h("div", { class: "grow" }),
      btn("Undo", "undo", () => s.undo(), "icon-only ghost"),
      btn("Redo", "redo", () => s.redo(), "icon-only ghost"),
      sc ? btn("To timeline", "film", () => {
        const c = s.mutate("scene to timeline", (p) => ops.addClip(p, { sceneId: sc.id, start: s.time }));
        s.select(c.id);
        toast("Animation added to the timeline", "ok");
      }, "icon-only ghost") : null,
    ].filter(Boolean) as HTMLElement[]),
    );
    if (!sc) {
      this.stage.querySelector(".an-empty")?.remove();
      this.stage.append(h("div", { class: "an-empty" }, h("p", {}, "Create a scene to start drawing."), btn("New scene", "plus", () => this.header.querySelector("button")?.click(), "primary")));
    } else this.stage.querySelector(".an-empty")?.remove();
  }

  private sceneSettings() {
    const s = this.s;
    const sc = this.scene()!;
    const upd = (fn: (x: AnimScene) => void) => s.mutate("scene settings", (p) => fn(A.getScene(p, sc.id)));
    const body = h(
      "div",
      { class: "stack" },
      h("input", { value: sc.name, "aria-label": "Scene name", onchange: (e: Event) => (upd((x) => (x.name = (e.target as HTMLInputElement).value)), this.drawHeader()) }),
      select("Frame rate", ["8", "12", "15", "24", "25", "30", "60"] as const, String(sc.fps) as "24", (v) => upd((x) => (x.fps = Number(v)))),
      slider("Frames", 1, 2000, 1, sc.frames, (v) => upd((x) => (x.frames = Math.round(v))), { fmt: (v) => `${v} (${(v / sc.fps).toFixed(2)}s)` }).el,
      colorInput("Paper", sc.background || "#ffffff", (v) => upd((x) => (x.background = v))),
      toggle("Transparent paper", !sc.background, (v) => upd((x) => (x.background = v ? "" : "#ffffff"))),
      h("h3", {}, "Onion skin"),
      toggle("Show", this.onion.on, (v) => ((this.onion.on = v), this.queue())),
      slider("Before", 0, 6, 1, this.onion.before, (v) => ((this.onion.before = v), this.queue()), { fmt: (v) => String(v) }).el,
      slider("After", 0, 6, 1, this.onion.after, (v) => ((this.onion.after = v), this.queue()), { fmt: (v) => String(v) }).el,
      slider("Strength", 0.05, 0.8, 0.01, this.onion.opacity, (v) => ((this.onion.opacity = v), this.queue())).el,
      h("h3", {}, "Export"),
      h("div", { class: "row wrap" },
        btn("Frame as PNG", "image", () => void this.exportPng(false)),
        btn("Frame to project", "image", () => void this.exportPng(true)),
      ),
      h("p", { class: "hint" }, `Canvas ${sc.width}×${sc.height}. Strokes are vectors, so they stay sharp at any render size.`),
      btn("Delete scene", "trash", async () => {
        if (!(await confirmBox(`Delete “${sc.name}” and its clips?`, "Delete"))) return;
        s.mutate("delete scene", (p) => {
          p.scenes = p.scenes.filter((x) => x.id !== sc.id);
          for (const t of p.tracks) t.clips = t.clips.filter((c) => c.sceneId !== sc.id);
        });
        this.sceneId = null;
        this.open();
      }, "danger"),
    );
    sheet("Scene", body);
  }

  private async exportPng(toProject: boolean) {
    const sc = this.scene()!;
    const c = makeCanvas(sc.width, sc.height);
    renderScene(ctx2d(c), sc, this.frame, { references: false });
    const blob = c instanceof HTMLCanvasElement ? await new Promise<Blob>((r) => c.toBlob((b) => r(b!), "image/png")) : await c.convertToBlob({ type: "image/png" });
    const name = `${sc.name}_f${this.frame + 1}.png`;
    if (toProject) {
      await this.s.addImageAsset(blob, name);
      toast("Frame added to media", "ok");
    } else {
      const r = await saveBlob(blob, name, "Pictures");
      toast(`Saved ${r.path}`, "ok");
    }
  }

  /* ---------------------------------- layers -------------------------------- */

  private layersSheet() {
    const s = this.s;
    const body = h("div", { class: "stack" });
    const upd = (lid: string, fn: (l: AnimLayer) => void, redraw = true) => {
      s.mutate("layer", (p) => fn(A.getLayer(A.getScene(p, this.sceneId!), lid)));
      if (redraw) draw();
    };
    const draw = () => {
      const sc = this.scene();
      if (!sc) return;
      body.replaceChildren(
        ...sc.layers.map((l, i) =>
          h(
            "div",
            { class: `layer-row ${l.id === this.layerId ? "on" : ""}` },
            h("button", { class: "lr-pick", type: "button", onclick: () => ((this.layerId = l.kind === "draw" ? l.id : this.layerId), draw(), this.drawTools()) }, l.kind === "reference" ? icon("image", 16) : icon("brush", 16), h("span", {}, l.name)),
            btn(l.visible ? "Hide" : "Show", l.visible ? "eye" : "eyeOff", () => upd(l.id, (x) => (x.visible = !x.visible)), "icon-only ghost"),
            btn(l.locked ? "Unlock" : "Lock", l.locked ? "lock" : "unlock", () => upd(l.id, (x) => (x.locked = !x.locked)), "icon-only ghost"),
            btn("Up", "redo", () => s.mutate("reorder", (p) => {
              const L = A.getScene(p, sc.id).layers;
              if (i > 0) [L[i - 1], L[i]] = [L[i]!, L[i - 1]!];
            }) ?? draw(), "icon-only ghost"),
            btn("Down", "undo", () => s.mutate("reorder", (p) => {
              const L = A.getScene(p, sc.id).layers;
              if (i < L.length - 1) [L[i + 1], L[i]] = [L[i]!, L[i + 1]!];
            }) ?? draw(), "icon-only ghost"),
            slider("Opacity", 0, 1, 0.01, l.opacity, (v) => upd(l.id, (x) => (x.opacity = v), false), { fmt: (v) => `${Math.round(v * 100)}%` }).el,
            h("input", { class: "lr-name", value: l.name, "aria-label": "Layer name", onchange: (e: Event) => upd(l.id, (x) => (x.name = (e.target as HTMLInputElement).value), false) }),
            btn("Delete", "trash", async () => {
              if (sc.layers.filter((x) => x.kind === "draw").length <= 1 && l.kind === "draw") return toast("Keep at least one drawing layer", "error");
              if (!(await confirmBox(`Delete layer “${l.name}”?`, "Delete"))) return;
              s.mutate("delete layer", (p) => {
                const S = A.getScene(p, sc.id);
                S.layers = S.layers.filter((x) => x.id !== l.id);
              });
              this.ensureScene();
              draw();
            }, "icon-only ghost"),
          ),
        ),
        h("div", { class: "row wrap" },
          btn("Drawing layer", "plus", () => {
            const l = s.mutate("add layer", (p) => A.addLayer(A.getScene(p, sc.id), undefined, "draw", this.layerId ?? undefined));
            this.layerId = l.id;
            draw();
          }),
          btn("Reference image", "image", async () => {
            const [f] = await pickFiles("image/*", false);
            if (!f) return;
            const a = await s.addBlobAsset(f, f.name, "user");
            s.mutate("reference layer", (p) => {
              const l = A.addLayer(A.getScene(p, sc.id), `Ref – ${f.name}`, "reference");
              l.assetId = a.id;
              l.opacity = 0.45;
              const S = A.getScene(p, sc.id);
              S.layers = [...S.layers.filter((x) => x.id !== l.id), l];
            });
            void s.pool.image(a).then(() => this.queue());
            draw();
          }),
          btn("Merge down", "layers", () => this.mergeDown()),
        ),
      );
    };
    draw();
    sheet("Layers", body, { tall: true });
  }

  private mergeDown() {
    const sc = this.scene();
    const l = this.layer();
    if (!sc || !l) return;
    const i = sc.layers.indexOf(l);
    const below = sc.layers.slice(i + 1).find((x) => x.kind === "draw");
    if (!below) return toast("No drawing layer below", "error");
    this.s.mutate("merge layers", (p) => {
      const S = A.getScene(p, sc.id);
      const top = A.getLayer(S, l.id);
      const bot = A.getLayer(S, below.id);
      const keys = new Set([...Object.keys(top.cels), ...Object.keys(bot.cels)].map(Number));
      const merged: Record<string, Cel> = {};
      for (const k of [...keys].sort((a, b) => a - b)) {
        const tk = exposedKey(top, k);
        const bk = exposedKey(bot, k);
        const tc = tk ? top.cels[tk]! : emptyCel();
        const bc = bk ? bot.cels[bk]! : emptyCel();
        merged[String(k)] = JSON.parse(JSON.stringify({ strokes: [...bc.strokes, ...tc.strokes], fills: [...bc.fills, ...tc.fills] })) as Cel;
      }
      bot.cels = merged;
      S.layers = S.layers.filter((x) => x.id !== top.id);
    });
    this.layerId = below.id;
    toast("Merged");
  }

  /* ---------------------------------- tools --------------------------------- */

  private drawTools() {
    const mk = (t: Tool, label: string, ic: string) =>
      h("button", { class: `an-tool ${this.tool === t ? "on" : ""}`, type: "button", title: label, "aria-label": label, onclick: () => ((this.tool = t), this.selection = t === "lasso" ? this.selection : null, this.drawTools(), this.queue()) }, icon(ic, 20), h("span", {}, label));
    const sw = h("input", { type: "color", value: this.color, class: "an-color", "aria-label": "Colour" }) as HTMLInputElement;
    sw.addEventListener("input", () => {
      this.color = sw.value;
      this.drawTools();
    });
    const sc = this.scene();
    const palette = h("div", { class: "an-palette" }, ...(sc?.palette ?? []).map((c) => h("button", { class: `sw ${c === this.color ? "on" : ""}`, type: "button", style: `background:${c}`, "aria-label": c, onclick: () => ((this.color = c), this.drawTools()) })), h("button", { class: "sw add", type: "button", title: "Save colour to palette", onclick: () => sc && this.s.mutate("palette", (p) => A.getScene(p, sc.id).palette.push(this.color)) && this.drawTools() }, "+"));
    const sizeRow = slider("Size", 1, 200, 1, this.size, (v) => (this.size = v), { fmt: (v) => `${v}px` });
    const opRow = slider("Opacity", 0.05, 1, 0.01, this.opacity, (v) => (this.opacity = v), { fmt: (v) => `${Math.round(v * 100)}%` });
    const stabRow = slider("Smooth", 0, 0.9, 0.01, this.stabilizer, (v) => (this.stabilizer = v), { fmt: (v) => `${Math.round(v * 100)}%` });
    const selTools = this.selection
      ? h("div", { class: "row wrap sel-tools" },
          btn("Bigger", "plus", () => this.transformSel(1.1, 0, 0, 0)),
          btn("Smaller", "zoomIn", () => this.transformSel(1 / 1.1, 0, 0, 0)),
          btn("Rotate", "redo", () => this.transformSel(1, 15, 0, 0)),
          btn("Flip", "symmetry", () => this.transformSel(-1, 0, 0, 0)),
          btn("Recolour", "color", () => this.recolorSel()),
          btn("Copy → next", "copy", () => this.copySelToNext()),
          btn("Delete", "trash", () => this.deleteSel()),
        )
      : null;
    this.toolsEl.replaceChildren(
      h("div", { class: "an-toolrow" },
        ...BRUSHES.map(([t, l, i]) => mk(t, l, i)),
        mk("fill", "Fill", "bucket"),
        mk("lasso", "Lasso", "lasso"),
        mk("line", "Line", "pencil"),
        mk("rect", "Rect", "rect"),
        mk("ellipse", "Ellipse", "shape"),
        mk("picker", "Pick", "dropper"),
        mk("pose", "Pose", "bone"),
        mk("hand", "Hand", "hand"),
        h("button", { class: `an-tool ${this.symmetry ? "on" : ""}`, type: "button", title: "Mirror", onclick: () => ((this.symmetry = !this.symmetry), this.drawTools()) }, icon("symmetry", 20), h("span", {}, "Mirror")),
      ),
      h("div", { class: "an-opts" }, sw, palette),
      h("div", { class: "an-sliders" }, sizeRow.el, opRow.el, stabRow.el),
      ...(selTools ? [selTools] : []),
    );
  }

  /* ---------------------------------- strip --------------------------------- */

  private drawStrip() {
    const sc = this.scene();
    const l = this.layer();
    if (!sc) {
      this.strip.replaceChildren();
      return;
    }
    const keys = new Set(l ? Object.keys(l.cels).map(Number) : []);
    const anyKeys = new Set(sc.layers.flatMap((x) => Object.keys(x.cels).map(Number)));
    // Pose keys (joint rotations / body moves) show as keys too.
    for (const x of sc.layers) for (const v of Object.values(x.transform)) if (Array.isArray(v)) for (const k of v) anyKeys.add(Math.round(k.t));
    const cells = h("div", { class: "an-cells" });
    const max = Math.min(sc.frames, 2000);
    for (let i = 0; i < max; i++) {
      const c = h("button", { class: `cell ${i === this.frame ? "on" : ""} ${keys.has(i) ? "key" : anyKeys.has(i) ? "okey" : ""}`, type: "button", "aria-label": `Frame ${i + 1}` }, String(i + 1));
      c.addEventListener("click", () => this.goto(i));
      cells.append(c);
    }
    const s = this.s;
    const sid = sc.id;
    const controls = h(
      "div",
      { class: "an-ctrl" },
      btn("Previous", "undo", () => this.goto(this.frame - 1), "icon-only ghost"),
      h("button", { class: "play small", type: "button", "aria-label": this.playing ? "Stop" : "Play", onclick: () => (this.playing ? this.stop() : this.play()) }, icon(this.playing ? "pause" : "play", 20)),
      btn("Next", "redo", () => this.goto(this.frame + 1), "icon-only ghost"),
      h("span", { class: "fcount" }, `${this.frame + 1}/${sc.frames} · ${sc.fps}fps`),
      h("div", { class: "grow" }),
      btn("New drawing here", "plus", () => s.mutate("blank key", (p) => A.keyFrame(A.getScene(p, sid), this.frame, this.layerId ?? undefined, true)), "ghost"),
      btn("Insert frame", "frames", () => s.mutate("insert frame", (p) => A.insertFrames(A.getScene(p, sid), this.frame + 1, 1)) ?? this.goto(this.frame + 1), "icon-only ghost"),
      btn("Duplicate frame", "copy", () => (s.mutate("duplicate frame", (p) => A.duplicateFrame(A.getScene(p, sid), this.frame)), this.goto(this.frame + 1)), "icon-only ghost"),
      btn("Delete frame", "trash", () => s.mutate("delete frame", (p) => A.deleteFrames(A.getScene(p, sid), this.frame, 1)), "icon-only ghost"),
      btn("Inbetween", "wand", () => this.inbetweenHere(), "ghost"),
      btn("Onion", "onion", () => ((this.onion.on = !this.onion.on), this.queue(), this.drawStrip()), `icon-only ghost ${this.onion.on ? "on" : ""}`),
    );
    this.strip.replaceChildren(controls, cells);
    const on = cells.children[this.frame] as HTMLElement | undefined;
    // Centre the current frame inside the strip only (scrollIntoView would also scroll the page sideways).
    if (on) requestAnimationFrame(() => (cells.scrollLeft = on.offsetLeft - cells.clientWidth / 2 + on.offsetWidth / 2));
  }

  goto(f: number): void {
    const sc = this.scene();
    if (!sc) return;
    this.frame = ((f % sc.frames) + sc.frames) % sc.frames;
    this.selection = null;
    this.queue();
    this.drawStrip();
  }

  private play() {
    const sc = this.scene();
    if (!sc) return;
    const t0 = performance.now();
    const f0 = this.frame;
    const tick = () => {
      const s2 = this.scene();
      if (!this.playing || !s2) return;
      const f = (f0 + Math.floor(((performance.now() - t0) / 1000) * s2.fps)) % s2.frames;
      if (f !== this.frame) {
        this.frame = f;
        this.render();
        const cells = this.strip.querySelector(".an-cells");
        cells?.querySelector(".cell.on")?.classList.remove("on");
        cells?.children[f]?.classList.add("on");
      }
      this.playing = requestAnimationFrame(tick);
    };
    this.playing = requestAnimationFrame(tick);
    this.drawStrip();
  }

  private stop() {
    if (!this.playing) return;
    cancelAnimationFrame(this.playing);
    this.playing = 0;
    this.drawStrip();
    this.queue();
  }

  private inbetweenHere() {
    const sc = this.scene();
    const l = this.layer();
    if (!sc || !l) return;
    const keys = A.keysOf(l);
    const prev = [...keys].reverse().find((k) => k <= this.frame);
    const next = keys.find((k) => k > this.frame);
    if (prev === undefined || next === undefined || next - prev < 2) return toast("Put the playhead between two drawings on this layer that have at least one frame between them.", "error", 4500);
    try {
      const n = this.s.mutate("inbetween", (p) => A.inbetween(A.getScene(p, sc.id), l.id, prev, next, "slowInOut"));
      toast(`Drew ${n} inbetween${n > 1 ? "s" : ""} (frames ${prev + 2}–${next})`, "ok");
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    }
  }

  /* ---------------------------------- input --------------------------------- */

  private input() {
    const pointers = new Map<number, { x: number; y: number; type: string }>();
    let stroke: { pts: number[]; tool: Tool; sx: number; sy: number; last: [number, number, number]; lastT: number; target: DrawTarget } | null = null;
    let gesture: { view: View; d: number; a: number; cx: number; cy: number } | null = null;
    let lassoMove: { x: number; y: number } | null = null;
    let poseDrag: { mode: "rotate" | "ik" | "root"; part: string; limb?: string; px: number; py: number; a0: number; rot0: number; x0: number; y0: number; sx: number; sy: number } | null = null;
    const lx = () => ctx2d(this.live) as CanvasRenderingContext2D;

    const pair = () => {
      const [a, b] = [...pointers.values()];
      return { cx: (a!.x + b!.x) / 2, cy: (a!.y + b!.y) / 2, d: Math.hypot(b!.x - a!.x, b!.y - a!.y), a: Math.atan2(b!.y - a!.y, b!.x - a!.x) };
    };

    this.stage.addEventListener("pointerdown", (e) => {
      if (!this.scene()) return;
      this.stage.setPointerCapture(e.pointerId);
      if (e.pointerType === "pen") this.penSeen = true;
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, type: e.pointerType });
      // Drawing while the animation plays would land strokes on moving frames: stop playback first.
      if (this.playing && pointers.size === 1 && this.tool !== "hand") this.stop();
      // Two fingers (or a finger while a pen is in use) navigate the canvas.
      const navigate = pointers.size >= 2 || this.tool === "hand" || (this.penSeen && e.pointerType === "touch");
      if (navigate) {
        if (stroke) {
          stroke = null;
          this.drawSelection();
        }
        const m = pointers.size >= 2 ? pair() : { cx: e.clientX, cy: e.clientY, d: 0, a: 0 };
        gesture = { view: { ...this.view }, ...m };
        return;
      }
      const [x, y] = this.toScene(e.clientX, e.clientY);
      if (this.tool === "pose") {
        poseDrag = this.pickBone(x, y);
        if (!poseDrag) toast("Add a rig first (Rig → Add puppet), then drag its joints.");
        return;
      }
      if (this.tool === "picker") return this.pick(x, y);
      if (this.tool === "fill") return void this.fill(x, y);
      if (this.tool === "lasso" && this.selection && inside(this.selection.box, x, y)) {
        lassoMove = { x, y };
        return;
      }
      const pr = e.pointerType === "pen" ? e.pressure || 0.5 : 0.55;
      // Remember where the stroke starts, so it lands on that frame even if the view moves while drawing.
      const target: DrawTarget = { sceneId: this.sceneId, layerId: this.layerId, frame: this.frame };
      stroke = { pts: [x, y, pr], tool: this.tool, sx: x, sy: y, last: [x, y, pr], lastT: e.timeStamp, target };
    });

    this.stage.addEventListener("pointermove", (e) => {
      if (!pointers.has(e.pointerId)) return;
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, type: e.pointerType });
      if (gesture) {
        if (pointers.size >= 2) {
          const m = pair();
          const z = Math.max(0.2, Math.min(12, gesture.view.zoom * (m.d / Math.max(1, gesture.d))));
          this.view = { zoom: z, rot: gesture.view.rot + ((m.a - gesture.a) * 180) / Math.PI, x: gesture.view.x + (m.cx - gesture.cx), y: gesture.view.y + (m.cy - gesture.cy) };
        } else this.view = { ...gesture.view, x: gesture.view.x + (e.clientX - gesture.cx), y: gesture.view.y + (e.clientY - gesture.cy) };
        this.applyView();
        return;
      }
      if (poseDrag) {
        const [x, y] = this.toScene(e.clientX, e.clientY);
        this.dragBone(poseDrag, x, y);
        return;
      }
      if (lassoMove && this.selection) {
        const [x, y] = this.toScene(e.clientX, e.clientY);
        this.transformSel(1, 0, x - lassoMove.x, y - lassoMove.y, true);
        lassoMove = { x, y };
        return;
      }
      if (!stroke) return;
      const events = typeof e.getCoalescedEvents === "function" ? e.getCoalescedEvents() : [e];
      for (const ev of events.length ? events : [e]) {
        const [x, y] = this.toScene(ev.clientX, ev.clientY);
        if (stroke.tool === "line" || stroke.tool === "rect" || stroke.tool === "ellipse") {
          stroke.last = [x, y, 0.7];
          continue;
        }
        const dt = Math.max(1, ev.timeStamp - stroke.lastT);
        const speed = Math.hypot(x - stroke.last[0], y - stroke.last[1]) / dt;
        // Touch has no pressure: thin out with speed for an ink feel.
        const pr = ev.pointerType === "pen" ? ev.pressure || 0.5 : Math.max(0.25, Math.min(1, 0.95 - speed * 0.12));
        const k = 1 - this.stabilizer;
        const nx = stroke.last[0] + (x - stroke.last[0]) * k;
        const ny = stroke.last[1] + (y - stroke.last[1]) * k;
        const np = stroke.last[2] + (pr - stroke.last[2]) * 0.4;
        if (Math.hypot(nx - stroke.last[0], ny - stroke.last[1]) < 0.6 / this.res) continue;
        stroke.pts.push(nx, ny, np);
        stroke.last = [nx, ny, np];
        stroke.lastT = ev.timeStamp;
      }
      this.drawLive(lx(), stroke);
    });

    const end = (e: PointerEvent) => {
      if (!pointers.has(e.pointerId)) return;
      pointers.delete(e.pointerId);
      if (gesture) {
        if (pointers.size === 0) gesture = null;
        else if (pointers.size === 1) {
          const [p] = [...pointers.values()];
          gesture = { view: { ...this.view }, cx: p!.x, cy: p!.y, d: 0, a: 0 };
        }
        return;
      }
      if (poseDrag) {
        poseDrag = null;
        this.activePart = null;
        this.drawSelection();
        this.drawStrip();
        return;
      }
      if (lassoMove) {
        lassoMove = null;
        return;
      }
      if (!stroke) return;
      const st = stroke;
      stroke = null;
      this.commit(st);
    };
    this.stage.addEventListener("pointerup", end);
    this.stage.addEventListener("pointercancel", end);
    this.stage.addEventListener(
      "wheel",
      (e) => {
        e.preventDefault();
        this.view.zoom = Math.max(0.2, Math.min(12, this.view.zoom * (e.deltaY < 0 ? 1.1 : 0.9)));
        this.applyView();
      },
      { passive: false },
    );
    this.stage.addEventListener("dblclick", () => {
      this.view = { x: 0, y: 0, zoom: 1, rot: 0 };
      this.applyView();
    });
  }

  private shapeStroke(st: { tool: Tool; sx: number; sy: number; last: [number, number, number] }): Stroke {
    const [x1, y1] = st.last;
    const { sx, sy } = st;
    const pts: number[] = [];
    if (st.tool === "line") pts.push(sx, sy, 0.8, x1, y1, 0.8);
    else if (st.tool === "rect") pts.push(sx, sy, 0.8, x1, sy, 0.8, x1, y1, 0.8, sx, y1, 0.8, sx, sy, 0.8);
    else {
      const cx = (sx + x1) / 2;
      const cy = (sy + y1) / 2;
      for (let i = 0; i <= 72; i++) {
        const t = (i / 72) * Math.PI * 2;
        pts.push(cx + (Math.cos(t) * (x1 - sx)) / 2, cy + (Math.sin(t) * (y1 - sy)) / 2, 0.8);
      }
    }
    return { id: uid("stk"), tool: "pen", color: this.color, size: this.size, opacity: this.opacity, pts };
  }

  private drawLive(x: CanvasRenderingContext2D, st: { pts: number[]; tool: Tool; sx: number; sy: number; last: [number, number, number] }) {
    x.setTransform(1, 0, 0, 1, 0, 0);
    x.clearRect(0, 0, this.live.width, this.live.height);
    x.setTransform(this.res, 0, 0, this.res, 0, 0);
    if (st.tool === "lasso") {
      x.beginPath();
      for (let i = 0; i < st.pts.length; i += 3) (i ? x.lineTo : x.moveTo).call(x, st.pts[i]!, st.pts[i + 1]!);
      x.setLineDash([6, 6]);
      x.lineWidth = 2 / this.res;
      x.strokeStyle = "#2f7bff";
      x.stroke();
      x.setLineDash([]);
      return;
    }
    const s: Stroke = st.tool === "line" || st.tool === "rect" || st.tool === "ellipse" ? this.shapeStroke(st) : { id: "live", tool: st.tool as BrushTool, color: st.tool === "eraser" ? "rgba(120,120,120,0.5)" : this.color, size: this.size, opacity: this.opacity, pts: st.pts };
    drawStroke(x, { ...s, tool: s.tool === "eraser" ? "marker" : s.tool, color: s.tool === "eraser" ? "#888888" : s.color });
    if (this.symmetry) drawStroke(x, mirror({ ...s, tool: s.tool === "eraser" ? "marker" : s.tool, color: s.tool === "eraser" ? "#888888" : s.color }, this.scene()!.width));
  }

  private commit(st: { pts: number[]; tool: Tool; sx: number; sy: number; last: [number, number, number]; target?: DrawTarget }) {
    const t = st.target ?? { sceneId: this.sceneId, layerId: this.layerId, frame: this.frame };
    const sc = this.s.project.scenes.find((x) => x.id === t.sceneId);
    const l = sc?.layers.find((x) => x.id === t.layerId);
    if (!sc || !l || l.kind !== "draw") return toast("That drawing layer is gone — the stroke was not kept", "error");
    if (l.locked) {
      toast("This layer is locked", "error");
      return this.drawSelection();
    }
    if (st.tool === "lasso") return this.lassoSelect(st.pts);
    const stroke = st.tool === "line" || st.tool === "rect" || st.tool === "ellipse" ? this.shapeStroke(st) : { id: uid("stk"), tool: st.tool as BrushTool, color: this.color, size: this.size, opacity: this.opacity, pts: st.pts };
    if (stroke.pts.length < 3) return;
    this.s.mutate(`stroke ${stroke.id}`, (p) => {
      const S = A.getScene(p, sc.id);
      A.addStroke(S, l.id, t.frame, stroke);
      if (this.symmetry) A.addStroke(S, l.id, t.frame, { ...mirror(stroke, S.width), id: uid("stk") });
    });
  }

  private pick(x: number, y: number) {
    const d = (ctx2d(this.base) as CanvasRenderingContext2D).getImageData(Math.floor(x * this.res), Math.floor(y * this.res), 1, 1).data;
    this.color = `#${[d[0]!, d[1]!, d[2]!].map((v) => v.toString(16).padStart(2, "0")).join("")}`;
    this.tool = "ink";
    this.drawTools();
  }

  private async fill(x: number, y: number) {
    const sc = this.scene();
    const l = this.layer();
    if (!sc || !l || l.locked) return;
    // Boundaries come from every visible drawing layer at full scene resolution (no paper, no onion).
    const c = makeCanvas(sc.width, sc.height);
    const only = { ...sc, background: "", layers: sc.layers.filter((x2) => x2.kind === "draw") };
    renderScene(ctx2d(c), only, this.frame, { references: false, background: false });
    const data = ctx2d(c).getImageData(0, 0, sc.width, sc.height);
    this.s.setStatus("Filling…");
    const r = await bucketFill(data, x, y, this.color, 40, 2);
    this.s.setStatus("");
    if (!r) return;
    if (r.w * r.h > sc.width * sc.height * 0.98) toast("That area isn't closed — filled the whole frame. Undo and close the gap.", "error", 4500);
    this.s.mutate("fill", (p) => {
      const S = A.getScene(p, sc.id);
      A.celForEdit(A.getLayer(S, l.id), this.frame).fills.push({ id: uid("fil"), ...r });
    });
  }

  /* ----------------------------------- rig ---------------------------------- */

  private pickBone(x: number, y: number): { mode: "rotate" | "ik" | "root"; part: string; limb?: string; px: number; py: number; a0: number; rot0: number; x0: number; y0: number; sx: number; sy: number } | null {
    const sc = this.scene();
    if (!sc) return null;
    let bones: ReturnType<typeof R.skeleton>;
    try {
      bones = R.skeleton(sc, undefined, this.frame);
    } catch {
      return null;
    }
    const reach = 46 / Math.max(0.35, this.fit * this.view.zoom * this.res);
    const parts = R.rigLayers(sc);
    const hips = parts.get("hips");
    const base = { sx: x, sy: y, x0: hips ? valueAt(hips.transform.x, this.frame, 0) : 0, y0: hips ? valueAt(hips.transform.y, this.frame, 0) : 0 };
    // IK handles: wrists and ankles.
    if (this.ik) {
      const ends: [string, string][] = [["handF", "armF"], ["handB", "armB"], ["footF", "legF"], ["footB", "legB"]];
      let best: { d: number; part: string; limb: string; b: (typeof bones)[number] } | null = null;
      for (const [part, limb] of ends) {
        const b = bones.find((q) => q.part === part);
        if (!b) continue;
        const d = Math.hypot(b.pivot[0] - x, b.pivot[1] - y);
        if (d < reach && (!best || d < best.d)) best = { d, part, limb, b };
      }
      if (best) {
        this.activePart = best.part;
        return { mode: "ik", part: best.part, limb: best.limb, px: best.b.pivot[0], py: best.b.pivot[1], a0: 0, rot0: 0, ...base };
      }
    }
    const hb = bones.find((q) => q.part === "hips");
    if (hb && Math.hypot(hb.pivot[0] - x, hb.pivot[1] - y) < reach) {
      this.activePart = "hips";
      return { mode: "root", part: "hips", px: hb.pivot[0], py: hb.pivot[1], a0: 0, rot0: 0, ...base };
    }
    // Nearest bone segment.
    let best: { d: number; b: (typeof bones)[number] } | null = null;
    for (const b of bones) {
      const d = segDist(x, y, b.pivot, b.tip);
      if (!best || d < best.d) best = { d, b };
    }
    if (!best || best.d > reach * 2.2) return null;
    const b = best.b;
    this.activePart = b.part;
    return { mode: "rotate", part: b.part, px: b.pivot[0], py: b.pivot[1], a0: Math.atan2(y - b.pivot[1], x - b.pivot[0]), rot0: b.rotation, ...base };
  }

  private dragBone(d: { mode: "rotate" | "ik" | "root"; part: string; limb?: string; px: number; py: number; a0: number; rot0: number; x0: number; y0: number; sx: number; sy: number }, x: number, y: number) {
    const sc = this.scene();
    if (!sc) return;
    const sid = sc.id;
    const f = this.frame;
    try {
      this.s.mutate(`pose ${d.part}`, (p) => {
        const S = A.getScene(p, sid);
        if (d.mode === "ik" && d.limb) R.solveIK(S, undefined, d.limb, x, y, f, this.poseEase);
        else if (d.mode === "root") R.applyPose(S, undefined, f, { rootX: Math.round(d.x0 + x - d.sx), rootY: Math.round(d.y0 + y - d.sy) }, this.poseEase);
        else {
          let delta = ((Math.atan2(y - d.py, x - d.px) - d.a0) * 180) / Math.PI;
          while (delta > 180) delta -= 360;
          while (delta < -180) delta += 360;
          R.applyPose(S, undefined, f, { [d.part]: Math.round((d.rot0 + delta) * 10) / 10 }, this.poseEase);
        }
      });
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    }
  }

  private rigSheet() {
    const s = this.s;
    const sc = this.scene();
    if (!sc) return;
    const sid = sc.id;
    const has = R.rigIds(sc).length > 0;
    const style = { ...R.DEFAULT_STYLE };
    let cycles = 2;
    let travel = 0;
    let energy = 1;
    const body = h("div", { class: "stack" });
    const run = (label: string, fn: (S: AnimScene) => unknown) => {
      try {
        s.mutate(label, (p) => fn(A.getScene(p, sid)));
        this.tool = "pose";
        this.drawTools();
        this.drawStrip();
        this.queue();
      } catch (e) {
        toast(e instanceof Error ? e.message : String(e), "error");
      }
    };
    body.append(
      h("p", { class: "hint" }, "Each body part is its own layer hanging from a joint. Use the Pose tool: drag a bone to rotate it, drag a green hand/foot dot to place it (IK), drag the red hip dot to move the body. Every drag keys the current frame."),
      h("h3", {}, "Puppet"),
      select("View", [["side", "Side (walks, runs)"], ["front", "Front"]] as const, "side", (v) => (style.view = v)),
      h("div", { class: "row wrap" },
        colorInput("Skin", style.skin, (v) => (style.skin = v)),
        colorInput("Shirt", style.shirt, (v) => (style.shirt = v)),
        colorInput("Pants", style.pants, (v) => (style.pants = v)),
        colorInput("Shoes", style.shoes, (v) => (style.shoes = v)),
        colorInput("Hair", style.hair, (v) => (style.hair = v)),
      ),
      btn(has ? "Add another puppet" : "Add puppet", "plus", () => {
        run("add rig", (S) => R.buildRig(S, S.width / 2, S.height * 0.55, S.height * 0.62, style));
        toast("Puppet added. Pick the Pose tool and drag its joints.", "ok");
      }, "primary"),
    );
    if (has) {
      body.append(
        h("h3", {}, "Motion (from the current frame)"),
        slider("Cycles", 1, 8, 1, cycles, (v) => (cycles = v), { fmt: (v) => String(v) }).el,
        slider("Travel", -800, 800, 10, travel, (v) => (travel = v), { fmt: (v) => `${v}px` }).el,
        slider("Energy", 0.3, 1.8, 0.05, energy, (v) => (energy = v), { fmt: (v) => `${Math.round(v * 100)}%` }).el,
        h("div", { class: "chips" }, ...R.MOTIONS.map((m) => h("button", { class: "chip", type: "button", onclick: () => run(`motion ${m}`, (S) => R.motion(S, undefined, m, { start: this.frame, cycles, travel, energy })) }, m))),
        h("h3", {}, "Posing"),
        toggle("IK on hands and feet", this.ik, (v) => ((this.ik = v), this.queue())),
        select("Ease into this pose", ["inOut", "linear", "out", "in", "back", "hold"] as const, this.poseEase, (v) => (this.poseEase = v)),
        h("div", { class: "row wrap" },
          btn("Key whole pose", "key", () => run("key pose", (S) => R.applyPose(S, undefined, this.frame, R.readPose(S, undefined, this.frame), this.poseEase))),
          btn("Copy pose", "copy", () => {
            this.copiedPose = R.readPose(sc, undefined, this.frame);
            toast("Pose copied");
          }),
          btn("Paste pose", "download", () => this.copiedPose && run("paste pose", (S) => R.applyPose(S, undefined, this.frame, this.copiedPose!, this.poseEase))),
          btn("Rest pose", "undo", () => run("rest pose", (S) => R.applyPose(S, undefined, this.frame, Object.fromEntries(R.PARTS.map((q) => [q, 0])) as R.Pose, this.poseEase))),
        ),
      );
    }
    sheet("Rig", body, { tall: true });
  }

  /**
   * Anime action: whole moves (impact, dash, power-up, beam, clash, slash), effects placed inside the scene, impact
   * beats, and per-layer timing (on twos), afterimages and aura. Everything lands at the current frame.
   */
  private actionSheet() {
    const s = this.s;
    const sc = this.scene();
    if (!sc) return;
    const sid = sc.id;
    const body = h("div", { class: "stack" });
    // the layer the action is about: a character if there is one, else the layer being drawn on
    let whoId = sc.layers.find((l) => l.kind === "puppet")?.id ?? this.layerId ?? sc.layers.find((l) => l.kind !== "proc" && l.kind !== "backdrop")?.id ?? null;
    let power = 1, auraColor = "#ffb020";
    const run = (label: string, fn: (S: AnimScene) => unknown) => {
      try {
        s.mutate(label, (p) => fn(A.getScene(p, sid)));
        this.drawStrip();
        this.queue();
        draw();
      } catch (e) {
        toast(e instanceof Error ? e.message : String(e), "error");
      }
    };
    /** Where the chosen layer is in the picture right now (0..1): the middle of a character, else the middle of the frame. */
    const where = (S: AnimScene): { x: number; y: number } => {
      const l = whoId ? S.layers.find((x) => x.id === whoId) : undefined;
      if (l?.kind === "puppet" && l.puppet) {
        const u = l.puppet.height / 7.6;
        const rx = valueAt(l.chan?.rootX, this.frame, 0) * u * (l.puppet.facing ?? 1);
        return { x: Math.min(0.97, Math.max(0.03, (l.puppet.x + rx) / S.width)), y: Math.min(0.95, Math.max(0.05, (l.puppet.y - l.puppet.height * 0.55) / S.height)) };
      }
      return { x: 0.5, y: 0.5 };
    };
    const draw = () => {
      const S = this.scene();
      if (!S) return;
      const layers = S.layers.filter((l) => l.kind !== "proc" && l.kind !== "reference");
      const who = layers.find((l) => l.id === whoId);
      const t = this.frame / S.fps;
      const beats = (S.anime?.holds?.length ?? 0) + (S.anime?.shakes?.length ?? 0) + (S.anime?.punches?.length ?? 0) + (S.anime?.flashes?.length ?? 0);
      const fxLayers = S.layers.filter((l) => l.kind === "proc" && l.span);
      body.replaceChildren(
        h("p", { class: "hint" }, `Action lands at the current frame (${t.toFixed(2)} s). Effects keep moving through a hit-stop, the way action anime does; every beat can be undone or cleared.`),
        layers.length > 1 ? select("About", layers.map((l) => [l.id, l.name] as const), whoId ?? layers[0]!.id, (v) => ((whoId = v), draw())) : h("span"),
        h("h3", {}, "Moves"),
        h("div", { class: "chips" }, ...ANIME_MOVES.map((k) => h("button", { class: "chip", type: "button", onclick: () => run(`anime ${k}`, (X) => addMove(X, k, t, { ...where(X), layerId: whoId ?? undefined, power, accent: k === "powerup" ? auraColor : undefined })) }, k === "powerup" ? "power-up" : k))),
        slider("Power", 0.4, 2.5, 0.1, power, (v) => (power = v), { fmt: (v) => `${v.toFixed(1)}×` }).el,
        h("h3", {}, "Impact beat"),
        h("div", { class: "chips" },
          h("button", { class: "chip", type: "button", onclick: () => run("anime beat", (X) => addBeat(X, t, { ...where(X), hold: Math.round(3 * power), shake: power, punch: 0.1 * power })) }, "Hit-stop + shake + punch + flash"),
          h("button", { class: "chip", type: "button", onclick: () => run("anime shake", (X) => addBeat(X, t, { hold: 0, punch: 0, flash: "none", shake: power, shakeFrames: 14 })) }, "Shake only"),
          h("button", { class: "chip", type: "button", onclick: () => run("anime flash", (X) => addBeat(X, t, { hold: 0, punch: 0, shake: 0, flash: "invert" })) }, "Inverted flash"),
          h("button", { class: "chip", type: "button", onclick: () => run("anime hit-stop", (X) => addBeat(X, t, { hold: Math.round(4 * power), punch: 0, shake: 0, flash: "none" })) }, "Hit-stop only"),
        ),
        h("h3", {}, "Effects in the scene (1 s from here)"),
        h("div", { class: "chips" }, ...FX_TYPES.map((ty) => h("button", { class: "chip", type: "button", onclick: () => run(`effect ${ty}`, (X) => addSceneFx(X, ty, t, 1, { ...where(X), behind: ["rushlines", "actionbg", "aura", "speedlines"].includes(ty) && whoId ? whoId : undefined, color: ["rushlines", "focuslines", "speedlines"].includes(ty) ? "#ffffff" : undefined })) }, ty))),
        who
          ? h("div", { class: "stack" },
              h("h3", {}, `Timing and energy · ${who.name}`),
              select("Poses", [["1", "Every frame (smooth)"], ["2", "On twos (anime)"], ["3", "On threes"]] as const, String(who.anime?.step ?? 1) as "1" | "2" | "3", (v) => run("anime timing", (X) => {
                const l = A.getLayer(X, who.id);
                const cur = { ...(l.anime ?? {}) };
                if (Number(v) > 1) cur.step = Number(v);
                else delete cur.step;
                l.anime = Object.keys(cur).length ? cur : undefined;
              })),
              toggle("Afterimages behind fast motion", !!who.anime?.trail, (on) => run("anime afterimages", (X) => {
                const l = A.getLayer(X, who.id);
                const cur = { ...(l.anime ?? {}) };
                if (on) cur.trail = { count: 4, gap: 1, opacity: 0.45 };
                else delete cur.trail;
                l.anime = Object.keys(cur).length ? cur : undefined;
              })),
              toggle("Aura around the figure", !!who.anime?.aura, (on) => run("anime aura", (X) => {
                const l = A.getLayer(X, who.id);
                const cur = { ...(l.anime ?? {}) };
                if (on) cur.aura = { color: auraColor, size: 0.035, strength: 0.8, flicker: 0.35 };
                else delete cur.aura;
                l.anime = Object.keys(cur).length ? cur : undefined;
              })),
              colorInput("Aura colour", auraColor, (v) => {
                auraColor = v;
                if (who.anime?.aura) run("anime aura colour", (X) => {
                  const l = A.getLayer(X, who.id);
                  if (l.anime?.aura) l.anime = { ...l.anime, aura: { ...l.anime.aura, color: v } };
                });
              }),
            )
          : h("span"),
        h("h3", {}, "In this scene"),
        h("p", { class: "hint" }, `${beats} beat part${beats === 1 ? "" : "s"} (hit-stops, shakes, punches, flashes) · ${fxLayers.length} effect${fxLayers.length === 1 ? "" : "s"}`),
        h("div", { class: "row wrap" },
          btn("Clear action near this frame", "trash", () => run("clear anime action here", (X) => {
            const f0 = this.frame - Math.round(X.fps * 0.5), f1 = this.frame + Math.round(X.fps * 0.5);
            const near = (f: number) => f >= f0 && f <= f1;
            const an = X.anime;
            if (an) for (const k of ["holds", "shakes", "punches", "flashes"] as const) if (an[k]) (an as Record<string, unknown>)[k] = (an[k] as [number, ...unknown[]][]).filter((x) => !near(x[0]));
            X.layers = X.layers.filter((l) => !(l.kind === "proc" && l.span && near(l.span[0])));
          }), "ghost"),
          btn("Clear all action", "trash", () => run("clear anime action", (X) => {
            delete X.anime;
            X.layers = X.layers.filter((l) => !(l.kind === "proc" && l.span));
          }), "ghost"),
        ),
      );
    };
    draw();
    sheet("Action", body, { tall: true });
  }

  /** Procedural anime characters and painted backdrops: one tap per action, keyed at the current frame. */
  private characterSheet() {
    const s = this.s;
    const sc = this.scene();
    if (!sc) return;
    const sid = sc.id;
    const body = h("div", { class: "stack" });
    const look = { hair: "messy", jacket: "leather", palette: "black", cig: true, eyes: PU.DEFAULT_PALETTE.eyes, hairColor: PU.DEFAULT_PALETTE.hair };
    let charId = sc.layers.find((l) => l.kind === "puppet")?.id ?? null;
    const run = (label: string, fn: (S: AnimScene) => unknown) => {
      try {
        s.mutate(label, (p) => fn(A.getScene(p, sid)));
        this.drawStrip();
        this.queue();
      } catch (e) {
        toast(e instanceof Error ? e.message : String(e), "error");
      }
    };
    const act = (step: Omit<PU.ActionStep, "at">) =>
      charId
        ? run(`character ${step.do}`, (S) => {
            const l = A.getLayer(S, charId!);
            const end = PU.applyActions(l, S.fps, [{ at: this.frame / S.fps, ...step } as PU.ActionStep]);
            S.frames = Math.max(S.frames, Math.ceil(end * S.fps) + 1);
          })
        : toast("Add a character first", "error");
    const draw = () => {
      const chars = this.scene()?.layers.filter((l) => l.kind === "puppet") ?? [];
      body.replaceChildren(
        h("p", { class: "hint" }, "A fully drawn anime character: curved limbs, cel shading, face rig, blinking, hair follow-through. Tap an action to key it from the current frame; scrub to see it."),
        h("h3", {}, "New character"),
        select("Hair", [["messy", "Messy"], ["spiky", "Spiky"], ["short", "Short"], ["long", "Long"]] as const, "messy", (v) => (look.hair = v)),
        select("Outfit", [["leather", "Leather jacket"], ["none", "Turtleneck only"]] as const, "leather", (v) => (look.jacket = v)),
        select("Jacket colour", Object.keys(PU.PALETTES).map((k) => [k, k] as const), "black", (v) => (look.palette = v)),
        h("div", { class: "row wrap" }, colorInput("Eyes", look.eyes, (v) => (look.eyes = v)), colorInput("Hair colour", look.hairColor, (v) => (look.hairColor = v))),
        toggle("Cigarette in hand", look.cig, (v) => (look.cig = v)),
        btn("Add character", "plus", () => {
          run("add character", (S) => {
            const top = S.layers.find((l) => l.kind === "draw");
            const l = A.addLayer(S, "Character", "puppet", top?.id);
            l.puppet = PU.makeSpec({ x: S.width / 2, y: S.height * 0.92, height: S.height * 0.68, hair: look.hair as never, jacket: look.jacket as never, palette: look.palette, props: look.cig ? ["cigarette"] : [], colorOverrides: { eyes: look.eyes, hair: look.hairColor }, seed: Math.floor(Math.random() * 1000) });
            l.chan = {};
            charId = l.id;
          });
          draw();
        }, "primary"),
        h("h3", {}, "Backdrop"),
        h("div", { class: "chips" }, ...PU.BACKDROPS.map((b) => h("button", { class: "chip", type: "button", onclick: () => run("backdrop", (S) => {
          let l = S.layers.find((x) => x.kind === "backdrop");
          if (!l) {
            l = A.addLayer(S, "Backdrop", "backdrop");
            S.layers.splice(S.layers.indexOf(l), 1);
            S.layers.push(l);
          }
          l.backdrop = { type: b, seed: 3 };
        }) }, b))),
        chars.length
          ? h("div", { class: "stack" },
              chars.length > 1 ? select("Character", chars.map((c) => [c.id, c.name] as const), charId ?? chars[0]!.id, (v) => (charId = v)) : h("span"),
              h("h3", {}, "Actions (from this frame)"),
              h("div", { class: "chips" },
                ...([["smoke", "Smoke"], ["exhale", "Exhale"], ["nod", "Nod"], ["shake", "Shake head"], ["shrug", "Shrug"], ["laugh", "Laugh"], ["wave", "Wave"], ["point", "Point"], ["gesture", "Talk gesture"]] as const).map(([d, label]) => h("button", { class: "chip", type: "button", onclick: () => act({ do: d }) }, label)),
              ),
              h("h3", {}, "Expression"),
              h("div", { class: "chips" }, ...Object.keys(PU.EXPRESSIONS).map((e) => h("button", { class: "chip", type: "button", onclick: () => act({ do: "expr", name: e }) }, e))),
              h("h3", {}, "Stance"),
              h("div", { class: "chips" }, ...Object.keys(PU.STANCES).map((e) => h("button", { class: "chip", type: "button", onclick: () => act({ do: "stance", name: e }) }, e))),
              h("h3", {}, "Look"),
              h("div", { class: "chips" },
                ...([["Left", -0.8, 0], ["Centre", 0, 0], ["Right", 0.8, 0], ["Up", 0, -0.8], ["Down", 0, 0.8]] as const).map(([n, x, y]) => h("button", { class: "chip", type: "button", onclick: () => act({ do: "look", x, y }) }, n)),
                h("button", { class: "chip", type: "button", onclick: () => act({ do: "tilt", to: 6 }) }, "Tilt head"),
                h("button", { class: "chip", type: "button", onclick: () => act({ do: "tilt", to: 0 }) }, "Straighten"),
              ),
              btn("Clear this character's animation", "trash", () => charId && run("clear character animation", (S) => (A.getLayer(S, charId!).chan = {})), "ghost"),
            )
          : h("p", { class: "hint" }, "Add a character to animate it."),
      );
    };
    draw();
    sheet("Character", body, { tall: true });
  }


  /** 3D studio: a three.js layer in this scene. Objects, characters, models, camera moves, keyed at the current frame. */
  private threeSheet() {
    const s = this.s;
    const sc = this.scene();
    if (!sc) return;
    const sid = sc.id;
    const body = h("div", { class: "stack" });
    const sl = (...args: Parameters<typeof slider>) => slider(...args).el;
    let objId: string | null = null;
    let textValue = "HELLO";
    let style: T3.Scene3D["style"] = "toon";
    const layer = () => this.scene()?.layers.find((l) => l.kind === "three") ?? null;
    const run = (label: string, fn: (S: AnimScene, sp: T3.Scene3D) => unknown) => {
      try {
        s.mutate(label, (p) => {
          const S = A.getScene(p, sid);
          const l = S.layers.find((x) => x.kind === "three");
          if (!l?.three) throw new Error("Add a 3D layer first");
          return fn(S, l.three);
        });
        this.drawStrip();
        this.queue();
      } catch (e) {
        toast(e instanceof Error ? e.message : String(e), "error");
      }
    };
    const fr = () => this.frame;
    const addObj = (type: T3.Obj3DType, extra: Partial<T3.Obj3D> = {}) => {
      run(`add 3D ${type}`, (_S, sp) => {
        let n = sp.objects.length + 1;
        while (sp.objects.some((o) => o.id === `${type}${n}`)) n++;
        const o: T3.Obj3D = { id: `${type}${n}`, type, color: type === "character" ? "#ffffff" : ["#e8e4f0", "#ff4a2e", "#3b7dd8", "#ffd23f", "#2ecc71"][n % 5]!, material: "toon", pos: [0, type === "character" || type === "terrain" || type === "particles" || type === "model" ? 0 : 0.5, 0], rot: [0, 0, 0], scale: 1, ...extra };
        if (type === "character") o.char = { ...T3.DEFAULT_CHAR3D };
        sp.objects.push(o);
        objId = o.id;
      });
      draw();
    };
    /** Set a property: the base value, or a key at this frame once the property is animated. */
    const setProp = (o: T3.Obj3D, prop: string, v: number, base: () => void) =>
      run(`3D ${prop}`, (_S, sp) => {
        const live = sp.objects.find((x) => x.id === o.id)!;
        if (sp.keys[`${o.id}.${prop}`]?.length) T3.setKey(sp, `${o.id}.${prop}`, fr(), v);
        else base.call(live);
        Object.assign(o, live);
      });
    const draw = () => {
      const l = layer();
      const sp = l?.three;
      const o = sp?.objects.find((x) => x.id === objId) ?? sp?.objects[0] ?? null;
      if (o) objId = o.id;
      const kids: (HTMLElement | null)[] = [h("p", { class: "hint" }, "Build 3D scenes inside this animation: shapes, 3D text, toon characters, imported .glb models, lights and camera moves. Everything mixes with your drawings, characters and effects. Moving a slider on an animated property keys it at the current frame.")];
      if (!sp) {
        kids.push(
          select("Look", [["toon", "Toon (anime)"], ["realistic", "Realistic"], ["clay", "Clay"], ["neon", "Neon"]] as const, style, (v) => (style = v)),
          btn("Add 3D layer", "cube", () => {
            try {
              s.mutate("3D layer", (p) => {
                const S = A.getScene(p, sid);
                const nl = A.addLayer(S, "3D", "three");
                S.layers.splice(S.layers.indexOf(nl), 1);
                let i = S.layers.length;
                while (i > 0 && S.layers[i - 1]!.kind === "backdrop") i--;
                S.layers.splice(i, 0, nl);
                nl.three = T3.newScene3D();
                nl.three.style = style;
              });
              this.queue();
            } catch (e) {
              toast(e instanceof Error ? e.message : String(e), "error");
            }
            draw();
          }, "primary"),
        );
        body.replaceChildren(...(kids.filter(Boolean) as HTMLElement[]));
        return;
      }
      kids.push(
        h("h3", {}, "Add"),
        h("div", { class: "chips" },
          ...(["box", "sphere", "cylinder", "cone", "torus", "capsule", "icosa", "knot", "plane", "ring", "terrain", "particles"] as const).map((t) => h("button", { class: "chip", type: "button", onclick: () => addObj(t, t === "particles" ? { color: "#ffe9a8", size: [500] } : t === "terrain" ? { color: "#3c6e47", size: [30] } : {}) }, t)),
          h("button", { class: "chip", type: "button", onclick: () => addObj("character") }, "character"),
        ),
        h("div", { class: "row" },
          (() => {
            const i = h("input", { type: "text", value: textValue, placeholder: "3D text" }) as HTMLInputElement;
            i.addEventListener("input", () => (textValue = i.value));
            return i;
          })(),
          btn("3D text", "text", () => addObj("text", { text: textValue, color: "#ffd23f", font: "Anton", pos: [0, 2, -1], size: [0.7, 0.15] }), "ghost"),
        ),
        btn("Import 3D model (.glb / .gltf)", "folder", async () => {
          const [file] = await pickFiles(".glb,.gltf,model/gltf-binary", false);
          if (!file) return;
          try {
            const asset = await s.mutateAsync("import 3D model", async (p) => importBlob(p, file, file.name, "user"));
            addObj("model", { assetId: asset.id, ref: asset.ref, color: "#ffffff" });
          } catch (e) {
            toast(e instanceof Error ? e.message : String(e), "error");
          }
        }, "ghost"),
      );
      if (o) {
        const has = (prop: string) => !!sp.keys[`${o.id}.${prop}`]?.length;
        const cur = (prop: string, base: number) => T3.kv(sp, `${o.id}.${prop}`, fr(), base);
        kids.push(
          h("h3", {}, "Object"),
          select("Selected", sp.objects.map((x) => [x.id, `${x.id} (${x.type})`] as const), o.id, (v) => ((objId = v), draw())),
          ...(["x", "y", "z"] as const).map((ax, i) => sl(`Position ${ax}${has(`p${ax}`) ? " ◆" : ""}`, -10, 10, 0.05, cur(`p${ax}`, o.pos[i]!), (v) => setProp(o, `p${ax}`, v, function (this: T3.Obj3D) { this.pos[i] = v; }), { fmt: (v) => v.toFixed(2) })),
          ...(["x", "y", "z"] as const).map((ax, i) => sl(`Rotate ${ax}${has(`r${ax}`) ? " ◆" : ""}`, -180, 180, 1, cur(`r${ax}`, o.rot[i]!), (v) => setProp(o, `r${ax}`, v, function (this: T3.Obj3D) { this.rot[i] = v; }), { fmt: (v) => `${Math.round(v)}°` })),
          sl(`Scale${has("s") ? " ◆" : ""}`, 0.05, 8, 0.05, cur("s", o.scale), (v) => setProp(o, "s", v, function (this: T3.Obj3D) { this.scale = v; }), { fmt: (v) => v.toFixed(2) }),
          o.type !== "character" && o.type !== "model" ? colorInput("Colour", o.color, (v) => run("3D colour", (_S, sp2) => (sp2.objects.find((x) => x.id === o.id)!.color = v))) : null,
          o.type !== "character" && o.type !== "model" ? select("Material", [["toon", "Toon"], ["standard", "Standard"], ["metal", "Metal"], ["glass", "Glass"], ["emissive", "Glow"], ["clay", "Clay"], ["flat", "Flat"]] as const, o.material, (v) => run("3D material", (_S, sp2) => (sp2.objects.find((x) => x.id === o.id)!.material = v))) : null,
          h("div", { class: "row wrap" },
            btn("Key all here", "key", () => run("3D key", (_S, sp2) => {
              const live = sp2.objects.find((x) => x.id === o.id)!;
              (["px", "py", "pz", "rx", "ry", "rz", "s"] as const).forEach((pr, k) => T3.setKey(sp2, `${o.id}.${pr}`, fr(), T3.kv(sp2, `${o.id}.${pr}`, fr(), k < 3 ? live.pos[k]! : k < 6 ? live.rot[k - 3]! : live.scale)));
            }), "ghost"),
            btn("Spin", "loop", () => run("3D spin", (S, sp2) => T3.act3D(sp2, o.id, S.fps, [{ at: fr() / S.fps, do: "spin", turns: 1, dur: 2 }])), "ghost"),
            btn("Delete", "trash", () => {
              run("delete 3D object", (_S, sp2) => {
                sp2.objects = sp2.objects.filter((x) => x.id !== o.id);
                for (const k of Object.keys(sp2.keys)) if (k.startsWith(`${o.id}.`)) delete sp2.keys[k];
              });
              objId = null;
              draw();
            }, "ghost"),
            btn("Save as .glb", "download", async () => {
              try {
                const lay = layer();
                if (!lay) return;
                const blob = await T3.exportGlb(lay, o.id);
                const r = await saveBlob(blob, `${o.id}.glb`, "Documents");
                toast(`Saved ${r.path}`, "ok");
              } catch (e) {
                toast(e instanceof Error ? e.message : String(e), "error");
              }
            }, "ghost"),
          ),
        );
        if (o.type === "character") {
          const a3 = (step: Omit<T3.Step3D, "at">) =>
            run(`3D ${step.do}`, (S, sp2) => {
              const end = T3.act3D(sp2, o.id, S.fps, [{ at: fr() / S.fps, ...step } as T3.Step3D]);
              S.frames = Math.max(S.frames, Math.ceil(end * S.fps) + 1);
            });
          kids.push(
            h("h3", {}, "Character"),
            h("div", { class: "row wrap" },
              colorInput("Top", o.char?.top ?? "#7d1622", (v) => run("3D outfit", (_S, sp2) => (sp2.objects.find((x) => x.id === o.id)!.char!.top = v))),
              colorInput("Hair", o.char?.hair ?? "#1c1c26", (v) => run("3D hair", (_S, sp2) => (sp2.objects.find((x) => x.id === o.id)!.char!.hair = v))),
              colorInput("Eyes", o.char?.eyes ?? "#d12c43", (v) => run("3D eyes", (_S, sp2) => (sp2.objects.find((x) => x.id === o.id)!.char!.eyes = v))),
              colorInput("Skin", o.char?.skin ?? "#f6dccb", (v) => run("3D skin", (_S, sp2) => (sp2.objects.find((x) => x.id === o.id)!.char!.skin = v))),
            ),
            select("Hair style", [["spiky", "Spiky"], ["short", "Short"], ["long", "Long"], ["bald", "None"]] as const, o.char?.hairStyle ?? "spiky", (v) => run("3D hair style", (_S, sp2) => (sp2.objects.find((x) => x.id === o.id)!.char!.hairStyle = v))),
            h("h3", {}, "Moves (from this frame)"),
            h("div", { class: "chips" },
              ...([["walk", { dist: 2 }], ["run", { dist: 5 }], ["jump", { height: 0.8 }], ["punch", {}], ["kick", {}], ["wave", {}], ["nod", {}]] as const).map(([d, extra]) => h("button", { class: "chip", type: "button", onclick: () => a3({ do: d, ...extra }) }, d)),
              ...Object.keys(T3.POSES3D).map((pz) => h("button", { class: "chip", type: "button", onclick: () => a3({ do: pz, dur: 0.4 }) }, pz)),
            ),
            h("div", { class: "chips" }, ...T3.EXPR3D.map((e) => h("button", { class: "chip", type: "button", onclick: () => a3({ do: "expr", name: e }) }, e))),
          );
        }
      }
      const cam = (move: string, amount?: number) =>
        run(`camera ${move}`, (S, sp2) => {
          T3.camMove(sp2, S.fps, { move, start: fr() / S.fps, dur: move === "shake" ? 0.5 : 2, amount });
          S.frames = Math.max(S.frames, fr() + Math.ceil(2 * S.fps) + 1);
        });
      const preset = (pos: [number, number, number], target: [number, number, number], fov: number) =>
        run("camera angle", (_S, sp2) => {
          const animated = Object.keys(sp2.keys).some((k) => k.startsWith("cam."));
          if (animated) {
            pos.forEach((v, i) => T3.setKey(sp2, `cam.p${"xyz"[i]}`, fr(), v));
            target.forEach((v, i) => T3.setKey(sp2, `cam.t${"xyz"[i]}`, fr(), v));
            T3.setKey(sp2, "cam.fov", fr(), fov);
          } else sp2.camera = { pos, target, fov };
        });
      kids.push(
        h("h3", {}, "Camera"),
        h("div", { class: "chips" },
          h("button", { class: "chip", type: "button", onclick: () => preset([0, 1.5, 7], [0, 1.1, 0], 40) }, "Front"),
          h("button", { class: "chip", type: "button", onclick: () => preset([0, 1.4, 2.4], [0, 1.45, 0], 32) }, "Close-up"),
          h("button", { class: "chip", type: "button", onclick: () => preset([1.5, 0.3, 4], [0, 1.4, 0], 45) }, "Low angle"),
          h("button", { class: "chip", type: "button", onclick: () => preset([0, 7, 6], [0, 0.5, 0], 40) }, "High angle"),
          h("button", { class: "chip", type: "button", onclick: () => preset([0, 3, 16], [0, 1, 0], 35) }, "Wide"),
        ),
        h("div", { class: "chips" },
          h("button", { class: "chip", type: "button", onclick: () => cam("orbit", 90) }, "Orbit 90°"),
          h("button", { class: "chip", type: "button", onclick: () => cam("dolly", 0.5) }, "Dolly in"),
          h("button", { class: "chip", type: "button", onclick: () => cam("dolly", -0.6) }, "Dolly out"),
          h("button", { class: "chip", type: "button", onclick: () => cam("crane", 2) }, "Crane up"),
          h("button", { class: "chip", type: "button", onclick: () => cam("push", 22) }, "Push in"),
          h("button", { class: "chip", type: "button", onclick: () => cam("shake", 0.06) }, "Shake"),
        ),
        h("h3", {}, "World"),
        select("Look", [["toon", "Toon (anime)"], ["realistic", "Realistic"], ["clay", "Clay"], ["neon", "Neon"]] as const, sp.style, (v) => run("3D look", (_S, sp2) => (sp2.style = v))),
        h("div", { class: "row wrap" },
          colorInput("Sky top", sp.env.sky?.[0] ?? "#0f1630", (v) => run("3D sky", (_S, sp2) => (sp2.env.sky = [v, sp2.env.sky?.[1] ?? "#3a4a8a"]))),
          colorInput("Sky bottom", sp.env.sky?.[1] ?? "#3a4a8a", (v) => run("3D sky", (_S, sp2) => (sp2.env.sky = [sp2.env.sky?.[0] ?? "#0f1630", v]))),
          colorInput("Ground", sp.env.ground?.color ?? "#2a2d3a", (v) => run("3D ground", (_S, sp2) => (sp2.env.ground = { size: 40, ...(sp2.env.ground ?? {}), color: v }))),
        ),
        toggle("Transparent sky (show the 2D backdrop)", !sp.env.sky, (v) => run("3D sky", (_S, sp2) => (sp2.env.sky = v ? null : ["#0f1630", "#3a4a8a"]))),
        toggle("Ground", !!sp.env.ground, (v) => run("3D ground", (_S, sp2) => (sp2.env.ground = v ? { color: "#2a2d3a", size: 40 } : null))),
        toggle("Fog", !!sp.env.fog, (v) => run("3D fog", (_S, sp2) => (sp2.env.fog = v ? { color: sp2.env.sky?.[1] ?? "#3a4a8a", near: 6, far: 40 } : null))),
      );
      body.replaceChildren(...(kids.filter(Boolean) as HTMLElement[]));
    };
    draw();
    sheet("3D studio", body, { tall: true });
  }

  /* -------------------------------- selection ------------------------------- */

  private lassoSelect(poly: number[]) {
    const l = this.layer();
    const key = l ? exposedKey(l, this.frame) : undefined;
    const cel = l && key ? l.cels[key] : undefined;
    if (!cel || poly.length < 9) {
      this.selection = null;
      this.drawTools();
      return this.drawSelection();
    }
    const ids = new Set<string>();
    let box: [number, number, number, number] = [Infinity, Infinity, -Infinity, -Infinity];
    for (const s of cel.strokes) {
      let inn = 0;
      const n = s.pts.length / 3;
      for (let i = 0; i < n; i++) if (pointInPoly(s.pts[i * 3]!, s.pts[i * 3 + 1]!, poly)) inn++;
      if (inn / n >= 0.5) {
        ids.add(s.id);
        for (let i = 0; i < n; i++) box = [Math.min(box[0], s.pts[i * 3]!), Math.min(box[1], s.pts[i * 3 + 1]!), Math.max(box[2], s.pts[i * 3]!), Math.max(box[3], s.pts[i * 3 + 1]!)];
      }
    }
    this.selection = ids.size ? { ids, box: [box[0] - 6, box[1] - 6, box[2] + 6, box[3] + 6] } : null;
    if (!ids.size) toast("Nothing selected — circle the strokes you want");
    this.drawTools();
    this.drawSelection();
  }

  /** Scale (negative = flip), rotate (deg) and move the selected strokes around the box centre. */
  private transformSel(scale: number, rotDeg: number, dx: number, dy: number, live = false) {
    const sel = this.selection;
    const sc = this.scene();
    const l = this.layer();
    if (!sel || !sc || !l) return;
    const [x0, y0, x1, y1] = sel.box;
    const cx = (x0 + x1) / 2;
    const cy = (y0 + y1) / 2;
    const a = (rotDeg * Math.PI) / 180;
    const sx = scale;
    const sy = Math.abs(scale);
    this.s.mutate(live ? "move selection" : `transform ${Date.now()}`, (p) => {
      const cel = A.celForEdit(A.getLayer(A.getScene(p, sc.id), l.id), this.frame);
      for (const s of cel.strokes) {
        if (!sel.ids.has(s.id)) continue;
        for (let i = 0; i < s.pts.length; i += 3) {
          let x = (s.pts[i]! - cx) * sx;
          let y = (s.pts[i + 1]! - cy) * sy;
          [x, y] = [x * Math.cos(a) - y * Math.sin(a), x * Math.sin(a) + y * Math.cos(a)];
          s.pts[i] = x + cx + dx;
          s.pts[i + 1] = y + cy + dy;
        }
        if (scale !== 1 && scale > 0) s.size *= scale;
      }
    });
    const w = ((x1 - x0) * Math.abs(scale)) / 2;
    const hh = ((y1 - y0) * Math.abs(scale)) / 2;
    sel.box = [cx - w + dx, cy - hh + dy, cx + w + dx, cy + hh + dy];
    this.drawSelection();
  }

  private recolorSel() {
    const sel = this.selection;
    const sc = this.scene();
    const l = this.layer();
    if (!sel || !sc || !l) return;
    this.s.mutate("recolour", (p) => {
      for (const s of A.celForEdit(A.getLayer(A.getScene(p, sc.id), l.id), this.frame).strokes) if (sel.ids.has(s.id)) s.color = this.color;
    });
  }

  private deleteSel() {
    const sel = this.selection;
    const sc = this.scene();
    const l = this.layer();
    if (!sel || !sc || !l) return;
    this.s.mutate("delete strokes", (p) => {
      const cel = A.celForEdit(A.getLayer(A.getScene(p, sc.id), l.id), this.frame);
      cel.strokes = cel.strokes.filter((s) => !sel.ids.has(s.id));
    });
    this.selection = null;
    this.drawTools();
    this.drawSelection();
  }

  private copySelToNext() {
    const sel = this.selection;
    const sc = this.scene();
    const l = this.layer();
    if (!sel || !sc || !l) return;
    const next = this.frame + 1;
    this.s.mutate("copy to next", (p) => {
      const S = A.getScene(p, sc.id);
      if (next >= S.frames) S.frames = next + 1;
      const L = A.getLayer(S, l.id);
      const src = A.celForEdit(L, this.frame).strokes.filter((s) => sel.ids.has(s.id));
      const dst = L.cels[String(next)] ?? (L.cels[String(next)] = emptyCel());
      const copies = src.map((s) => ({ ...JSON.parse(JSON.stringify(s)), id: uid("stk") }) as Stroke);
      dst.strokes.push(...copies);
      sel.ids = new Set(copies.map((c) => c.id));
    });
    this.frame = next;
    this.queue();
    this.drawStrip();
    toast("Copied to the next frame — keep it selected and move it");
  }
}

function mirror(s: Stroke, width: number): Stroke {
  const pts = s.pts.slice();
  for (let i = 0; i < pts.length; i += 3) pts[i] = width - pts[i]!;
  return { ...s, pts };
}

function inside(b: [number, number, number, number], x: number, y: number): boolean {
  return x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3];
}

function pointInPoly(x: number, y: number, poly: number[]): boolean {
  let c = false;
  const n = poly.length / 3;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const xi = poly[i * 3]!;
    const yi = poly[i * 3 + 1]!;
    const xj = poly[j * 3]!;
    const yj = poly[j * 3 + 1]!;
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) c = !c;
  }
  return c;
}

function segDist(x: number, y: number, a: [number, number], b: [number, number]): number {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const L = dx * dx + dy * dy || 1;
  const t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / L));
  return Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy));
}
