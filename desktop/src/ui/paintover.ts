/**
 * Animate → Fix a film shot. A shot of the Code Studio film plays here frame by frame; draw over it and erase in it.
 * Every fix holds on to a part of a body (found by itself from where you draw, or picked), so it moves, turns and
 * sizes with that part on every frame of its span — draw a hand once and every frame of the shot gets it. Fixes
 * switch on and off any time; the film draws them into the shot (only that shot is drawn again). Sensei's findings
 * for the shot are listed beside it: one click goes to the moment and holds the next drawing on the part it names.
 */
import { toast } from "../../../src/ui/dom";
import { drawStroke } from "../../../src/anim/render";
import type { Stroke } from "../../../src/core/model";
import { vsd } from "../bridge";
import { film } from "../film/prepare";
import { code } from "../film/session";
import { PART_NAMES, fixAlpha, fixTransform, inpaint, nearestPart, partAt, partFromWords, strokeToLocal, strokeToPicture } from "../film/paint";
import type { PaintAnchor, PaintData, PaintFix, PaintStroke, PaintTrack } from "../film/types";
import type { SenseiIssue } from "../sensei/types";
import { b, h, selectEl } from "./kit";

type Tool = "ink" | "pen" | "brush" | "pencil" | "marker" | "airbrush" | "erasePic" | "rubout" | "picker";
const TOOLS: [Tool, string, string][] = [
  ["ink", "Ink", "Ink line: thin to thick with pressure, tapered ends"],
  ["pen", "Pen", "Pen: an even line"],
  ["brush", "Brush", "Brush: paint, soft pressure"],
  ["pencil", "Pencil", "Pencil: a light line"],
  ["marker", "Marker", "Marker: flat colour"],
  ["airbrush", "Air", "Airbrush: soft shading"],
  ["erasePic", "Erase picture", "Erase what is wrong in the picture: what is behind the person is drawn there, or the hole is filled from around it"],
  ["rubout", "Rub out", "Rub out part of your own drawing in this fix"],
  ["picker", "Pick colour", "Take a colour from the picture"],
];
const uid = () => Math.random().toString(36).slice(2, 10);

export class PaintOver {
  readonly el: HTMLElement;
  private shot: string | null = null;
  private data: PaintData = { v: 1, on: true, fixes: [] };
  private track: PaintTrack | null = null;
  private issues: SenseiIssue[] = [];
  private cur: string | null = null;
  private pending: { anchor: PaintAnchor | null; span: [number, number] | null; name: string | null; note: string | null } | null = null;
  private anchorMode = "auto";
  private tool: Tool = "ink";
  private color = "#2a2433";
  private size = 6;
  private opacity = 1;
  private smoothing = 0.35;
  private live: { pts: number[]; sx: number; sy: number } | null = null;
  private frame = 0;
  private history: string[] = [];
  private future: string[] = [];
  private saveTimer = 0;
  private saved = true;
  private loading = false;
  private visible = false;
  private showParts = false;
  private exact: { f: number; img: HTMLImageElement } | null = null;
  // parts
  private video = h("video", { class: "po-video", muted: "", playsinline: "", preload: "auto" }) as HTMLVideoElement;
  private canvas = h("canvas", { class: "po-canvas" }) as HTMLCanvasElement;
  private stage = h("div", { class: "po-stage" });
  private headEl = h("div", { class: "po-head" });
  private toolsEl = h("div", { class: "po-tools" });
  private sideEl = h("div", { class: "po-side" });
  private barEl = h("div", { class: "po-bar" });
  private scrub = h("input", { type: "range", min: "0", max: "0", step: "1", value: "0", class: "po-scrub" }) as HTMLInputElement;
  private timeEl = h("span", { class: "po-time mono" }, "–");
  private statusEl = h("span", { class: "po-status" });
  private spansEl = h("div", { class: "po-spans" });
  private grab = document.createElement("canvas");

  constructor() {
    this.stage.append(this.video, this.canvas);
    this.el = h("div", { class: "po" }, this.headEl, h("div", { class: "po-main" }, this.toolsEl, h("div", { class: "po-center" }, this.stage, this.barEl), this.sideEl));
    this.video.addEventListener("seeked", () => this.paint());
    this.video.addEventListener("loadeddata", () => (this.layout(), this.seek(this.frame)));
    this.video.addEventListener("play", () => this.playLoop());
    new ResizeObserver(() => this.layout()).observe(this.stage);
    this.canvas.addEventListener("pointerdown", (e) => this.down(e));
    this.canvas.addEventListener("pointermove", (e) => this.move(e));
    this.canvas.addEventListener("pointerup", (e) => this.up(e));
    this.canvas.addEventListener("pointercancel", () => ((this.live = null), this.paint()));
    this.scrub.addEventListener("input", () => this.seek(+this.scrub.value));
    window.addEventListener("keydown", (e) => this.key(e));
    code.on((e) => {
      if (e === "film" && this.visible) void this.open(null);
    });
    this.drawHead();
    this.drawTools();
    this.drawSide();
  }

  shown(on: boolean): void {
    this.visible = on;
    if (on) this.layout();
    else this.video.pause();
  }

  /** Open a shot of the film open in Code Studio (null: the one Code Studio shows, or the first). */
  async open(shot: string | null, issueId?: string): Promise<void> {
    const f = film();
    if (!code.dir || !code.info) {
      this.shot = null;
      this.track = null;
      this.drawHead();
      this.drawSide();
      this.statusEl.textContent = "Open a film in Code Studio first: its shots can be fixed here.";
      return;
    }
    if (!f.paintGet || !f.paintTrack || !f.paintBase) return void toast("Fixing shots needs the desktop app's film engine.", "error");
    const id = shot ?? this.shot ?? code.shown?.shot ?? code.info.shots[0]?.id ?? null;
    if (!id) return;
    if (this.shot && this.shot !== id && !this.saved) await this.saveNow();
    this.shot = id;
    this.cur = null;
    this.pending = null;
    this.history = [];
    this.future = [];
    this.exact = null;
    this.loading = true;
    this.drawHead();
    this.statusEl.textContent = "Loading the shot…";
    try {
      const dir = code.dir;
      const [pg, tr] = await Promise.all([f.paintGet(dir, id), f.paintTrack(dir, id)]);
      if (this.shot !== id) return;
      this.data = pg.data;
      this.track = tr;
      this.scrub.max = String(Math.max(0, tr.frames - 1));
      this.issues = ((await f.senseiReport?.(dir).catch(() => null))?.issues ?? []).filter((x) => x.shot === id);
      this.statusEl.textContent = "Preparing the picture to paint on…";
      const base = await f.paintBase(dir, id);
      if (this.shot !== id) return;
      this.video.src = await vsd!.fileUrl(base.file);
      this.statusEl.textContent = base.kept ? "" : "The clean picture of the shot was drawn.";
      this.frame = Math.min(this.frame, tr.frames - 1);
    } catch (e) {
      this.statusEl.textContent = e instanceof Error ? e.message : String(e);
    } finally {
      this.loading = false;
      this.drawHead();
      this.drawSide();
      this.paint();
    }
    const want = issueId ? this.issues.find((x) => x.id === issueId) : null;
    if (want) this.fromIssue(want);
  }

  /* ------------------------------------------------------------------------------------------------- the frame */
  private get fps(): number {
    return this.track?.fps ?? 30;
  }
  private seek(f: number): void {
    if (!this.track) return;
    this.frame = Math.max(0, Math.min(this.track.frames - 1, Math.round(f)));
    this.scrub.value = String(this.frame);
    this.timeEl.textContent = `${(this.frame / this.fps).toFixed(2)} s · frame ${this.frame + 1}/${this.track.frames}`;
    if (this.video.readyState >= 1) this.video.currentTime = (this.frame + 0.5) / this.fps;
    this.paint();
  }
  private playLoop(): void {
    const v = this.video as HTMLVideoElement & { requestVideoFrameCallback?: (cb: (now: number, m: { mediaTime: number }) => void) => number };
    const step = (_n: number, m?: { mediaTime: number }) => {
      if (v.paused || v.ended) return void this.seek(Math.floor((v.currentTime) * this.fps));
      this.frame = Math.min((this.track?.frames ?? 1) - 1, Math.floor((m?.mediaTime ?? v.currentTime) * this.fps));
      this.scrub.value = String(this.frame);
      this.paint();
      if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(step);
      else requestAnimationFrame((n) => step(n));
    };
    if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(step);
    else requestAnimationFrame((n) => step(n));
  }
  private layout(): void {
    const r = this.stage.getBoundingClientRect(), W = this.track?.width ?? 1920, H = this.track?.height ?? 1080;
    if (r.width < 10 || r.height < 10) return;
    const k = Math.min(r.width / W, r.height / H), w = Math.round(W * k), hh = Math.round(H * k), dpr = window.devicePixelRatio || 1;
    for (const el of [this.video, this.canvas]) Object.assign(el.style, { width: `${w}px`, height: `${hh}px`, left: `${Math.round((r.width - w) / 2)}px`, top: `${Math.round((r.height - hh) / 2)}px` });
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(hh * dpr);
    this.paint();
  }
  /** Canvas pixels for a pointer event. */
  private at(e: PointerEvent): [number, number] {
    const r = this.canvas.getBoundingClientRect();
    return [((e.clientX - r.left) / r.width) * this.canvas.width, ((e.clientY - r.top) / r.height) * this.canvas.height];
  }
  private fix(): PaintFix | null {
    return this.data.fixes.find((x) => x.id === this.cur) ?? null;
  }

  /** Everything over the picture at this frame: each fix where its part is now, the stroke being drawn. */
  private paint(): void {
    const c = this.canvas, g = c.getContext("2d")!, H = c.height, tr0 = this.track;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, c.width, H);
    if (!tr0) return;
    const t = this.frame / this.fps, paused = this.video.paused;
    if (this.exact && this.exact.f === this.frame) g.drawImage(this.exact.img, 0, 0, c.width, H);
    else if (this.data.on) {
      for (const fx of this.data.fixes) {
        const a = fixAlpha(fx, t);
        if (a <= 0) continue;
        const tr = fixTransform(fx, tr0, this.frame, H);
        if (!tr) continue;
        if (fx.erase.length) this.drawErase(g, fx, tr, a, paused);
        if (fx.strokes.length) {
          const layer = this.layer(c.width, H), lg = layer.getContext("2d")!;
          lg.clearRect(0, 0, c.width, H);
          for (const s of fx.strokes) drawStroke(lg, strokeToPicture(s, tr) as Stroke);
          g.save();
          g.globalAlpha = a;
          g.drawImage(layer, 0, 0);
          g.restore();
        }
      }
    }
    // the part the current fix holds on to, and every part when asked
    const cur = this.fix(), mark = (x: number, y: number, r: number, col: string) => {
      g.beginPath();
      g.arc(x, y, r, 0, Math.PI * 2);
      g.strokeStyle = col;
      g.lineWidth = Math.max(1.5, H / 540);
      g.stroke();
    };
    const k = H / tr0.height;
    if (this.showParts) for (const [actor, A] of Object.entries(tr0.actors)) for (const part of Object.keys(A.parts)) { const p = partAt(tr0, actor, part, this.frame); if (p) mark(p.x * k, p.y * k, 3 * k, "rgba(255,255,255,0.65)"); }
    const an = cur?.anchor ?? this.pending?.anchor;
    if (an?.kind === "part") {
      const p = partAt(tr0, an.actor, an.part, this.frame);
      if (p) {
        mark(p.x * k, p.y * k, 9 * k, "#ffd23f");
        g.save();
        g.translate(p.x * k, p.y * k);
        g.rotate(p.ang);
        g.strokeStyle = "#ffd23f";
        g.beginPath();
        g.moveTo(0, 0);
        g.lineTo(22 * k, 0);
        g.stroke();
        g.restore();
      }
    }
    if (this.live) {
      const s = this.liveStroke();
      if (s) {
        if (this.tool === "erasePic") {
          g.save();
          g.globalAlpha = 0.55;
          drawStroke(g, { ...s, color: "#ff3d6e", tool: "brush" } as Stroke);
          g.restore();
        } else if (this.tool === "rubout") {
          g.save();
          g.globalAlpha = 0.5;
          drawStroke(g, { ...s, color: "#ffffff", tool: "pen" } as Stroke);
          g.restore();
        } else drawStroke(g, s as Stroke);
      }
    }
  }
  private layerCv: HTMLCanvasElement | null = null;
  private layer(w: number, h2: number): HTMLCanvasElement {
    if (!this.layerCv || this.layerCv.width !== w || this.layerCv.height !== h2) {
      this.layerCv = document.createElement("canvas");
      this.layerCv.width = w;
      this.layerCv.height = h2;
    }
    return this.layerCv;
  }
  /** An erased area: filled from around it (exactly as the film will), or — for "behind" — marked until the film draws it. */
  private drawErase(g: CanvasRenderingContext2D, fx: PaintFix, tr: { x: number; y: number; ang: number; scale: number }, a: number, paused: boolean): void {
    const W = this.canvas.width, H = this.canvas.height, m = this.layer(W, H), mg = m.getContext("2d")!;
    mg.clearRect(0, 0, W, H);
    for (const s of fx.erase) drawStroke(mg, { ...strokeToPicture(s, tr), tool: "brush", color: "#ffffff", opacity: 1 } as Stroke);
    if (fx.eraseMode === "fill" && paused && this.video.readyState >= 2) {
      const d = mg.getImageData(0, 0, W, H).data;
      let x0 = W, y0 = H, x1 = -1, y1 = -1;
      for (let y = 0; y < H; y += 2) for (let x = 0; x < W; x += 2) if (d[(y * W + x) * 4 + 3]! > 20) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
      if (x1 < 0) return;
      const pad = Math.round(H * 0.03);
      x0 = Math.max(0, x0 - pad);
      y0 = Math.max(0, y0 - pad);
      x1 = Math.min(W - 1, x1 + pad);
      y1 = Math.min(H - 1, y1 + pad);
      const w = x1 - x0 + 1, hh = y1 - y0 + 1;
      this.grab.width = W;
      this.grab.height = H;
      const gg = this.grab.getContext("2d", { willReadFrequently: true })!;
      gg.drawImage(this.video, 0, 0, W, H);
      const img = gg.getImageData(x0, y0, w, hh), mr = mg.getImageData(x0, y0, w, hh).data, mk = new Uint8Array(w * hh);
      for (let i = 0; i < w * hh; i++) mk[i] = mr[i * 4 + 3]!;
      inpaint(img.data, mk, w, hh);
      for (let i = 0; i < w * hh; i++) img.data[i * 4 + 3] = mk[i]! > 127 ? Math.round(255 * a) : 0;
      gg.putImageData(img, x0, y0);
      g.drawImage(this.grab, x0, y0, w, hh, x0, y0, w, hh);
      return;
    }
    // "behind": the film draws what is there without the person; here the area shows in a light hatch
    mg.globalCompositeOperation = "source-in";
    mg.fillStyle = mg.createPattern(hatch(), "repeat")!;
    mg.fillRect(0, 0, W, H);
    mg.globalCompositeOperation = "source-over";
    g.save();
    g.globalAlpha = 0.75 * a;
    g.drawImage(m, 0, 0);
    g.restore();
  }

  /* ------------------------------------------------------------------------------------------------- drawing */
  private down(e: PointerEvent): void {
    if (e.button !== 0 || !this.track || this.loading) return;
    this.video.pause();
    if (this.exact) this.exact = null;
    const [x, y] = this.at(e);
    if (this.tool === "picker") {
      const W = this.canvas.width, H = this.canvas.height;
      this.grab.width = W;
      this.grab.height = H;
      const gg = this.grab.getContext("2d", { willReadFrequently: true })!;
      gg.drawImage(this.video, 0, 0, W, H);
      const p = gg.getImageData(Math.round(x), Math.round(y), 1, 1).data;
      this.color = `#${[p[0]!, p[1]!, p[2]!].map((v) => v.toString(16).padStart(2, "0")).join("")}`;
      this.tool = "ink";
      this.drawTools();
      return;
    }
    try {
      this.canvas.setPointerCapture(e.pointerId);
    } catch {
      /* a pointer the window does not track (a synthetic one): drawing still works */
    }
    this.live = { pts: [x, y, e.pointerType === "mouse" ? 0.5 : e.pressure || 0.5], sx: x, sy: y };
    this.paint();
  }
  private move(e: PointerEvent): void {
    if (!this.live) return;
    const [x, y] = this.at(e), p = this.live.pts, n = p.length, k = this.smoothing;
    const sx = p[n - 3]! + (x - p[n - 3]!) * (1 - k), sy = p[n - 2]! + (y - p[n - 2]!) * (1 - k);
    if (Math.hypot(sx - p[n - 3]!, sy - p[n - 2]!) < 0.8) return;
    p.push(sx, sy, e.pointerType === "mouse" ? 0.5 : e.pressure || 0.5);
    this.paint();
  }
  private liveStroke(): PaintStroke | null {
    if (!this.live) return null;
    const px = this.canvas.height / 1080;
    const tool = this.tool === "erasePic" ? "brush" : this.tool === "rubout" ? "eraser" : (this.tool as PaintStroke["tool"]);
    return { id: uid(), tool, color: this.color, size: (this.tool === "erasePic" || this.tool === "rubout" ? Math.max(this.size * 2.5, 14) : this.size) * px, opacity: this.opacity, pts: this.live.pts };
  }
  private up(e: PointerEvent): void {
    if (!this.live || !this.track) return;
    try {
      this.canvas.releasePointerCapture(e.pointerId);
    } catch {
      /* already released */
    }
    const s = this.liveStroke()!;
    this.live = null;
    const H = this.canvas.height, t = this.frame / this.fps;
    let fx = this.fix();
    if (!fx) {
      // a new fix: it holds on to the part named (a finding, the picker), else the part nearest the drawing
      const cx = avg(s.pts, 0), cy = avg(s.pts, 1);
      const anchor: PaintAnchor = this.pending?.anchor ?? (this.anchorMode === "screen" ? { kind: "screen" } : this.anchorMode === "auto" ? nearestPart(this.track, this.frame, cx, cy, H) ?? { kind: "screen" } : parsePart(this.anchorMode) ?? { kind: "screen" });
      const p = anchor.kind === "part" ? partAt(this.track, anchor.actor, anchor.part, this.frame) : null;
      if (anchor.kind === "part" && !p) {
        toast("That part is not in this frame: go to a frame where it shows.", "info", 3000);
        this.paint();
        return;
      }
      this.remember();
      fx = {
        id: uid(), name: this.pending?.name ?? (anchor.kind === "part" ? `${anchor.actor} · ${PART_NAMES[anchor.part] ?? anchor.part}` : "On the picture"), on: true, anchor, follow: "full",
        span: this.pending?.span ?? null, fade: 0, ref: p ? { t: +t.toFixed(3), ang: +p.ang.toFixed(4), ppmH: +(p.ppm / this.track.height).toFixed(5) } : { t: +t.toFixed(3), ang: 0, ppmH: 0.1 },
        strokes: [], erase: [], eraseMode: this.tool === "erasePic" && anchor.kind !== "part" ? "fill" : "behind", note: this.pending?.note ?? undefined,
      };
      this.data.fixes.push(fx);
      this.cur = fx.id;
      this.pending = null;
    } else this.remember();
    const tr = fixTransform(fx, this.track, this.frame, H);
    if (!tr) {
      toast(`${fx.name}: its part is not in this frame. Draw where it shows, or start a new fix.`, "info", 3200);
      this.history.pop();
      this.paint();
      return;
    }
    const local = strokeToLocal(s, tr);
    if (this.tool === "erasePic") fx.erase.push(local);
    else fx.strokes.push(local);
    this.changed();
  }
  private key(e: KeyboardEvent): void {
    if (!this.visible || (e.target as HTMLElement)?.closest?.("input,textarea,select")) return;
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z") {
      e.preventDefault();
      if (e.shiftKey) this.redo();
      else this.undo();
    } else if (e.key === "ArrowRight" || e.key === ".") this.seek(this.frame + 1);
    else if (e.key === "ArrowLeft" || e.key === ",") this.seek(this.frame - 1);
    else if (e.key === " ") {
      e.preventDefault();
      if (this.video.paused) void this.video.play();
      else this.video.pause();
    }
  }

  /* ------------------------------------------------------------------------------------------------- keeping */
  private remember(): void {
    this.history.push(JSON.stringify(this.data));
    if (this.history.length > 80) this.history.shift();
    this.future = [];
  }
  private undo(): void {
    const s = this.history.pop();
    if (!s) return;
    this.future.push(JSON.stringify(this.data));
    this.data = JSON.parse(s);
    if (!this.fix()) this.cur = null;
    this.changed(false);
  }
  private redo(): void {
    const s = this.future.pop();
    if (!s) return;
    this.history.push(JSON.stringify(this.data));
    this.data = JSON.parse(s);
    this.changed(false);
  }
  private changed(drawSide = true): void {
    this.saved = false;
    this.statusEl.textContent = "Changed — saving…";
    clearTimeout(this.saveTimer);
    this.saveTimer = window.setTimeout(() => void this.saveNow(), 700);
    this.exact = null;
    if (drawSide) this.drawSide();
    else this.drawSide();
    this.paint();
  }
  private async saveNow(): Promise<void> {
    const f = film();
    if (!code.dir || !this.shot || !f.paintSave) return;
    clearTimeout(this.saveTimer);
    try {
      const r = await f.paintSave(code.dir, this.shot, this.data);
      this.saved = true;
      this.statusEl.textContent = `Saved · ${r.data.fixes.length} fix${r.data.fixes.length === 1 ? "" : "es"} in paint/${this.shot}.json — the shot will be drawn with ${r.data.on ? "them" : "none of them (all off)"}`;
      void code.refresh();
    } catch (e) {
      this.statusEl.textContent = e instanceof Error ? e.message : String(e);
    }
  }

  /** The film's own drawing of this frame, with the fixes (exactly what the shot will show). */
  private async exactFrame(): Promise<void> {
    if (!code.dir || !this.shot) return;
    await this.saveNow();
    const f = this.frame, t = f / this.fps;
    this.statusEl.textContent = "Drawing this frame as the film will…";
    try {
      const r = await film().frame(code.dir, this.shot, t, undefined, 1, 88, true);
      const img = new Image();
      img.src = `data:image/jpeg;base64,${r.jpeg}`;
      await img.decode();
      this.exact = { f, img };
      this.statusEl.textContent = "This is the film's own frame, fixes in. Move to another frame to go on painting.";
      this.paint();
    } catch (e) {
      this.statusEl.textContent = e instanceof Error ? e.message : String(e);
    }
  }
  private async drawShot(): Promise<void> {
    if (!this.shot) return;
    await this.saveNow();
    try {
      toast(`Drawing ${this.shot} with its fixes…`, "info", 2500);
      await code.build({ only: [this.shot] });
      toast(`${this.shot} is drawn with its fixes. Build the film in Code Studio to join it.`, "ok", 4000);
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), "error");
    }
  }

  /* ------------------------------------------------------------------------------------------------- panels */
  private drawHead(): void {
    const shots = code.info?.shots ?? [];
    const sel = selectEl(shots.length ? shots.map((s) => [s.id, `${s.id} · ${s.dur.toFixed(1)}s${s.note ? ` — ${s.note.slice(0, 40)}` : ""}`] as [string, string]) : [["", "No film open in Code Studio"] as [string, string]], this.shot ?? "", (v) => void this.open(v));
    sel.classList.add("po-shot");
    const master = h("label", { class: "po-master", title: "Every fix of this shot on or off (nothing is deleted)" }, h("input", { type: "checkbox", ...(this.data.on ? { checked: "" } : {}) }), "Fixes on");
    master.querySelector("input")!.addEventListener("change", (e) => {
      this.remember();
      this.data.on = (e.target as HTMLInputElement).checked;
      this.changed();
    });
    this.headEl.replaceChildren(
      h("span", { class: "po-title" }, "Fix a film shot"),
      sel,
      b(null, "stepB", () => this.seek(this.frame - 1), "sm ghost", "Previous frame (←)"),
      b(null, "play", () => (this.video.paused ? void this.video.play() : this.video.pause()), "sm ghost", "Play / pause (space)"),
      b(null, "stepF", () => this.seek(this.frame + 1), "sm ghost", "Next frame (→)"),
      master,
      h("span", { class: "sp" }),
      b("Film's frame", "eye", () => void this.exactFrame(), "sm", "See this frame exactly as the film draws it, fixes in"),
      b("Draw this shot", "film", () => void this.drawShot(), "sm primary", "Draw this shot again with its fixes (only this shot)"),
    );
    this.barEl.replaceChildren(h("div", { class: "po-scrubrow" }, this.scrub, this.timeEl), this.spansEl, this.statusEl);
  }
  private drawTools(): void {
    const tools = TOOLS.map(([id, label, title]) => {
      const el = h("button", { class: `po-tool ${this.tool === id ? "on" : ""}`, type: "button", title }, label);
      el.addEventListener("click", () => {
        this.tool = id;
        this.drawTools();
      });
      return el;
    });
    const col = h("input", { type: "color", value: this.color, title: "Colour" }) as HTMLInputElement;
    col.addEventListener("input", () => (this.color = col.value));
    const range = (label: string, min: number, max: number, step: number, v: number, set: (x: number) => void) => {
      const r = h("input", { type: "range", min: String(min), max: String(max), step: String(step), value: String(v) }) as HTMLInputElement, out = h("span", { class: "mono" }, String(v));
      r.addEventListener("input", () => {
        set(+r.value);
        out.textContent = r.value;
      });
      return h("label", { class: "po-range" }, h("span", {}, label), r, out);
    };
    const parts = h("label", { class: "po-check", title: "Show every part a fix can hold on to" }, h("input", { type: "checkbox", ...(this.showParts ? { checked: "" } : {}) }), "Show parts");
    parts.querySelector("input")!.addEventListener("change", (e) => ((this.showParts = (e.target as HTMLInputElement).checked), this.paint()));
    this.toolsEl.replaceChildren(
      h("div", { class: "po-group" }, ...tools),
      h("div", { class: "po-group" }, h("label", { class: "po-range" }, h("span", {}, "Colour"), col), range("Size", 1, 60, 1, this.size, (x) => (this.size = x)), range("Opacity", 0.1, 1, 0.05, this.opacity, (x) => (this.opacity = x)), range("Steady", 0, 0.9, 0.05, this.smoothing, (x) => (this.smoothing = x))),
      h("div", { class: "po-group" }, parts),
      h("p", { class: "po-hint" }, "Draw on the picture: the fix holds on to the body part you draw on and follows it on every frame. Erase picture takes out what is wrong (the film draws what is behind it). Ctrl+Z undoes."),
    );
  }
  private drawSide(): void {
    const tr = this.track, t = this.frame / this.fps;
    if (!this.shot || !tr) {
      this.sideEl.replaceChildren(h("div", { class: "po-note" }, code.dir ? "Pick a shot." : "Open a film in Code Studio: its shots can be fixed here."));
      this.spansEl.replaceChildren();
      return;
    }
    // where to hold on: auto (nearest part), the screen, or a part chosen
    const opts: [string, string][] = [["auto", "Hold on to: the part I draw on"], ["screen", "Hold on to: the picture (stays put)"]];
    for (const actor of Object.keys(tr.actors)) for (const p of Object.keys(PART_NAMES)) opts.push([`${actor}:${p}`, `${actor} · ${PART_NAMES[p]}`]);
    const anchorSel = selectEl(opts, this.anchorMode, (v) => (this.anchorMode = v));
    const list = this.data.fixes.map((fx) => this.fixRow(fx));
    const newBtn = b("New fix", "plus", () => {
      this.cur = null;
      this.pending = null;
      this.drawSide();
      this.paint();
    }, "sm", "The next drawing starts a new fix");
    // Sensei's findings for this shot
    const found = this.issues.slice(0, 40).map((x) => {
      const el = h("button", { class: `po-issue ${x.sev}`, type: "button", title: `${x.msg}${x.fix ? `\n→ ${x.fix}` : ""}` }, h("span", { class: "sev" }, x.sev === "must" ? "MUST" : x.sev === "should" ? "SHOULD" : "nice"), h("span", { class: "msg" }, x.msg), h("span", { class: "tm mono" }, x.at != null ? `${x.at.toFixed(2)}s` : ""));
      el.addEventListener("click", () => this.fromIssue(x));
      return el;
    });
    this.sideEl.replaceChildren(
      h("div", { class: "po-sec" }, h("h4", {}, "Fixes in this shot"), anchorSel, ...(list.length ? list : [h("div", { class: "po-note" }, "None yet. Draw on the picture to make the first: it follows the part you draw on through the whole shot.")]), newBtn),
      h("div", { class: "po-sec" }, h("h4", {}, `Sensei found here · ${this.issues.length}`), ...(found.length ? found : [h("div", { class: "po-note" }, "Nothing — or the film was not reviewed since this shot changed. Sensei → Review.")])),
    );
    // the spans under the scrubber
    this.spansEl.replaceChildren(...this.data.fixes.map((fx) => {
      const d = tr.frames / this.fps, sp = fx.span ?? [0, d];
      return h("div", { class: `po-span ${fx.on ? "" : "off"} ${fx.id === this.cur ? "cur" : ""}`, style: `left:${(sp[0] / d) * 100}%;width:${Math.max(0.5, ((sp[1] - sp[0]) / d) * 100)}%`, title: fx.name });
    }));
    void t;
  }
  private fixRow(fx: PaintFix): HTMLElement {
    const d = (this.track?.frames ?? 1) / this.fps, t = this.frame / this.fps;
    const on = h("input", { type: "checkbox", title: "On / off (nothing is deleted)", ...(fx.on ? { checked: "" } : {}) }) as HTMLInputElement;
    on.addEventListener("change", () => {
      this.remember();
      fx.on = on.checked;
      this.changed();
    });
    const name = h("input", { class: "po-name", value: fx.name, title: "Name" }) as HTMLInputElement;
    name.addEventListener("change", () => {
      this.remember();
      fx.name = name.value.slice(0, 80);
      this.changed();
    });
    const sp = fx.span ?? [0, d];
    const setSpan = (a: number, z: number) => {
      this.remember();
      fx.span = a <= 0.0001 && z >= d - 0.0001 ? null : [+Math.max(0, a).toFixed(3), +Math.min(d, z).toFixed(3)];
      this.changed();
    };
    const follow = selectEl([["full", "Moves, turns, sizes with it"], ["move+scale", "Moves and sizes, keeps its turn"], ["move", "Only moves with it"]] as [PaintFix["follow"], string][], fx.follow, (v) => {
      this.remember();
      fx.follow = v;
      this.changed();
    });
    const mode = selectEl([["behind", "Erase: show what is behind"], ["fill", "Erase: fill from around"]] as [PaintFix["eraseMode"], string][], fx.eraseMode, (v) => {
      this.remember();
      fx.eraseMode = v;
      this.changed();
    });
    const fade = h("input", { type: "number", min: "0", max: "3", step: "0.1", value: String(fx.fade), class: "po-num", title: "Fade in and out (seconds)" }) as HTMLInputElement;
    fade.addEventListener("change", () => {
      this.remember();
      fx.fade = Math.max(0, +fade.value || 0);
      this.changed();
    });
    const head = h("div", { class: "po-fixhead" }, on, name, b(null, "trash", () => {
      this.remember();
      this.data.fixes = this.data.fixes.filter((x) => x.id !== fx.id);
      if (this.cur === fx.id) this.cur = null;
      this.changed();
    }, "sm ghost", "Delete this fix"));
    const where = fx.anchor.kind === "part" ? `${fx.anchor.actor} · ${PART_NAMES[fx.anchor.part] ?? fx.anchor.part}` : fx.anchor.kind === "screen" ? "the picture" : "a point of the world";
    const body = fx.id === this.cur
      ? h("div", { class: "po-fixbody" },
          h("div", { class: "po-line" }, `Holds on to ${where} · ${fx.strokes.length} stroke${fx.strokes.length === 1 ? "" : "s"}${fx.erase.length ? `, ${fx.erase.length} erased` : ""}`),
          follow,
          fx.erase.length ? mode : null,
          h("div", { class: "po-line" }, `Shows ${fx.span ? `${sp[0].toFixed(2)}–${sp[1].toFixed(2)} s` : "through the whole shot"}`),
          h("div", { class: "row" }, b("Starts here", null, () => setSpan(t, sp[1]), "sm ghost", "Start the fix at this frame"), b("Ends here", null, () => setSpan(sp[0], t), "sm ghost", "End the fix at this frame"), b("Whole shot", null, () => setSpan(0, d), "sm ghost")),
          h("label", { class: "po-range" }, h("span", {}, "Fade"), fade, h("span", {}, "s")),
          fx.note ? h("div", { class: "po-line dim" }, fx.note) : null,
        )
      : null;
    const row = h("div", { class: `po-fix ${fx.id === this.cur ? "cur" : ""} ${fx.on ? "" : "off"}` }, head, body);
    row.addEventListener("click", (e) => {
      if ((e.target as HTMLElement).closest("input,select,button")) return;
      this.cur = this.cur === fx.id ? null : fx.id;
      this.pending = null;
      this.drawSide();
      this.paint();
    });
    return row;
  }
  /** A finding of Sensei: go to its moment; the next drawing holds on to the part it names, over the time it lasts. */
  private fromIssue(x: SenseiIssue): void {
    const part = partFromWords(x.part), actor = x.actor && this.track?.actors[x.actor] ? x.actor : x.actor?.split("+")[0] ?? null;
    const anchor: PaintAnchor | null = part && actor && this.track?.actors[actor] ? { kind: "part", actor, part } : null;
    const d = (this.track?.frames ?? 1) / this.fps;
    this.cur = null;
    this.pending = { anchor, span: x.t0 != null ? [Math.max(0, x.t0 - 0.15), Math.min(d, (x.t1 ?? x.t0) + 0.15)] : null, name: `${x.kind}${part ? ` · ${PART_NAMES[part]}` : ""}`, note: x.fix ?? x.msg };
    if (x.at != null) this.seek(Math.round(x.at * this.fps));
    this.statusEl.textContent = anchor ? `Draw the fix: it will hold on to ${actor}'s ${PART_NAMES[part!]}${this.pending.span ? ` from ${this.pending.span[0].toFixed(2)} to ${this.pending.span[1].toFixed(2)} s` : ""}. ${x.fix ?? ""}` : `Draw the fix. ${x.fix ?? ""}`;
    this.drawSide();
    this.paint();
  }
}

const avg = (p: number[], o: number) => {
  let s = 0, n = 0;
  for (let i = o; i < p.length; i += 3) (s += p[i]!), n++;
  return n ? s / n : 0;
};
function parsePart(v: string): PaintAnchor | null {
  const i = v.indexOf(":");
  return i > 0 ? { kind: "part", actor: v.slice(0, i), part: v.slice(i + 1) } : null;
}
let hatchCv: HTMLCanvasElement | null = null;
function hatch(): HTMLCanvasElement {
  if (hatchCv) return hatchCv;
  hatchCv = document.createElement("canvas");
  hatchCv.width = hatchCv.height = 12;
  const g = hatchCv.getContext("2d")!;
  g.fillStyle = "rgba(255,61,110,0.35)";
  g.fillRect(0, 0, 12, 12);
  g.strokeStyle = "rgba(255,255,255,0.8)";
  g.lineWidth = 2;
  g.beginPath();
  g.moveTo(-3, 15);
  g.lineTo(15, -3);
  g.stroke();
  return hatchCv;
}
