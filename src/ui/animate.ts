/**
 * 2D animation workstation: draw with pressure on layers, frame by frame (x-sheet holds), onion skin,
 * reference/lightbox layers, bucket fill, lasso transform, shapes, symmetry, auto-inbetweens and playback.
 */
import type { Studio } from "../app/studio";
import { emptyCel, exposedKey, findAsset, uid, type AnimLayer, type AnimScene, type BrushTool, type Cel, type Stroke } from "../core/model";
import * as A from "../anim/ops";
import { bucketFill, ctx2d, drawStroke, makeCanvas, renderScene } from "../anim/render";
import * as ops from "../core/ops";
import { saveBlob } from "../native/storage";
import { btn, colorInput, confirmBox, h, icon, pickFiles, select, sheet, slider, toast, toggle } from "./dom";

type Tool = BrushTool | "fill" | "lasso" | "line" | "rect" | "ellipse" | "picker" | "hand";

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
      this.sceneId = null;
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
    this.res = Math.min(1, 2048 / Math.max(sc.width, sc.height));
    const W = Math.round(sc.width * this.res);
    const H = Math.round(sc.height * this.res);
    if (this.base.width !== W || this.base.height !== H) {
      this.base.width = this.live.width = W;
      this.base.height = this.live.height = H;
    }
    this.fit = Math.min((r.width - 16) / W, (r.height - 16) / H);
    this.applyView();
    this.queue();
  }

  private applyView() {
    const v = this.view;
    this.board.style.width = `${this.base.width}px`;
    this.board.style.height = `${this.base.height}px`;
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
    const k = this.fit * v.zoom * this.res;
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

  private drawSelection() {
    const lx = ctx2d(this.live) as CanvasRenderingContext2D;
    lx.setTransform(1, 0, 0, 1, 0, 0);
    lx.clearRect(0, 0, this.live.width, this.live.height);
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
    on?.scrollIntoView({ block: "nearest", inline: "center" });
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
    let stroke: { pts: number[]; tool: Tool; sx: number; sy: number; last: [number, number, number]; lastT: number } | null = null;
    let gesture: { view: View; d: number; a: number; cx: number; cy: number } | null = null;
    let lassoMove: { x: number; y: number } | null = null;
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
      if (this.tool === "picker") return this.pick(x, y);
      if (this.tool === "fill") return void this.fill(x, y);
      if (this.tool === "lasso" && this.selection && inside(this.selection.box, x, y)) {
        lassoMove = { x, y };
        return;
      }
      const pr = e.pointerType === "pen" ? e.pressure || 0.5 : 0.55;
      stroke = { pts: [x, y, pr], tool: this.tool, sx: x, sy: y, last: [x, y, pr], lastT: e.timeStamp };
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

  private commit(st: { pts: number[]; tool: Tool; sx: number; sy: number; last: [number, number, number] }) {
    const sc = this.scene();
    const l = this.layer();
    if (!sc || !l) return;
    if (l.locked) {
      toast("This layer is locked", "error");
      return this.drawSelection();
    }
    if (st.tool === "lasso") return this.lassoSelect(st.pts);
    const stroke = st.tool === "line" || st.tool === "rect" || st.tool === "ellipse" ? this.shapeStroke(st) : { id: uid("stk"), tool: st.tool as BrushTool, color: this.color, size: this.size, opacity: this.opacity, pts: st.pts };
    if (stroke.pts.length < 3) return;
    this.s.mutate(`stroke ${stroke.id}`, (p) => {
      const S = A.getScene(p, sc.id);
      A.addStroke(S, l.id, this.frame, stroke);
      if (this.symmetry) A.addStroke(S, l.id, this.frame, { ...mirror(stroke, S.width), id: uid("stk") });
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
