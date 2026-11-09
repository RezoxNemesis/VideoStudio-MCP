/** Docked inspector: every property of the selected clip, keyframable at the playhead, or project settings. */
import type { Studio } from "../../../src/app/studio";
import { findAsset, findClip, FX_DEFAULTS, GRADE_DEFAULTS, TRANSFORM_DEFAULTS, type Animatable, type Clip, type Project, type TransitionKind } from "../../../src/core/model";
import { hasKeyAt, isAnimated, valueAt } from "../../../src/core/keyframes";
import * as ops from "../../../src/core/ops";
import { FONTS } from "../../../src/render/fonts";
import { runCommand } from "../../../src/agent/api";
import { LOOKS } from "../../../src/app/musicvideo";
import { toast } from "../../../src/ui/dom";
import { b, field, h, numRow, selectEl } from "./kit";

type Mode = "all" | "color";

const TRANSITIONS: TransitionKind[] = ["none", "fade", "dip", "flash", "wipeLeft", "wipeRight", "wipeUp", "slideLeft", "slideRight", "slideUp", "whipLeft", "whipRight", "zoomIn", "zoomBlur", "spin", "glitch", "blur", "iris"];
const ANIMS = ["none", "write", "typewriter", "pop", "rise", "wave", "fade", "slam", "blurIn", "track", "bounce", "flicker", "scramble", "drop", "shake", "zoomOut", "spin", "swing"] as const;

export const ASPECTS: [string, number, number][] = [
  ["16:9 · 1920×1080", 1920, 1080],
  ["16:9 · 4K 3840×2160", 3840, 2160],
  ["2.39:1 Scope · 1920×804", 1920, 804],
  ["2:1 Univisium · 1920×960", 1920, 960],
  ["4:3 · 1440×1080", 1440, 1080],
  ["1:1 Square · 1080×1080", 1080, 1080],
  ["4:5 Portrait · 1080×1350", 1080, 1350],
  ["9:16 Vertical · 1080×1920", 1080, 1920],
];

export class Inspector {
  readonly el: HTMLElement;
  private closed = new Set<string>(["Mask", "Chroma key", "Crop"]);
  private interacting = false;
  private pending = false;
  private quiet = false;

  constructor(
    private readonly s: Studio,
    private readonly mode: Mode = "all",
  ) {
    this.el = h("div", { class: "col", style: "gap:0" });
    this.el.addEventListener("mousedown", () => (this.interacting = true));
    addEventListener("mouseup", () => {
      if (this.interacting) {
        this.interacting = false;
        if (this.pending) this.render();
      }
    });
    s.on("select", () => this.render());
    s.on("project", () => this.render());
    s.on("change", () => this.soon());
    s.on("time", () => this.soon());
    this.render();
  }

  private timer = 0;
  private soon() {
    if (this.quiet) return;
    if (this.interacting) {
      this.pending = true;
      return;
    }
    window.clearTimeout(this.timer);
    this.timer = window.setTimeout(() => this.render(), 120);
  }

  private edit(label: string, fn: (p: Project) => unknown) {
    this.quiet = true;
    try {
      this.s.mutate(label, fn);
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      this.quiet = false;
    }
  }

  private sec(title: string, ...kids: (HTMLElement | null)[]): HTMLElement {
    const head = h("h4", {}, title, h("span", { class: "sp" }), this.closed.has(title) ? "▸" : "▾");
    const el = h("div", { class: `sec ${this.closed.has(title) ? "closed" : ""}` }, head, ...(kids.filter(Boolean) as HTMLElement[]));
    head.addEventListener("click", () => {
      if (this.closed.has(title)) this.closed.delete(title);
      else this.closed.add(title);
      this.render();
    });
    return el;
  }

  render(): void {
    this.pending = false;
    const f = this.s.selection ? findClip(this.s.project, this.s.selection) : null;
    if (!f) return this.el.replaceChildren(...this.projectPanel());
    this.el.replaceChildren(...this.clipPanel(f.clip));
  }

  /* ------------------------------------------------------------ project */

  private projectPanel(): HTMLElement[] {
    const p = this.s.project;
    const name = h("input", { class: "inp", value: p.name }) as HTMLInputElement;
    name.addEventListener("change", () => this.edit("rename", (pr) => (pr.name = name.value.slice(0, 80) || "Untitled")));
    name.addEventListener("keydown", (e) => e.stopPropagation());
    const cur = ASPECTS.find(([, w, hh]) => w === p.width && hh === p.height)?.[0] ?? "custom";
    const aspect = selectEl<string>([...ASPECTS.map(([l]) => l), ...(cur === "custom" ? [["custom", `Custom · ${p.width}×${p.height}`] as [string, string]] : [])], cur, (v) => {
      const a = ASPECTS.find(([l]) => l === v);
      if (a) void runCommand(this.s, { op: "project.set", width: a[1], height: a[2] });
    });
    const fps = selectEl<string>(["23.976", "24", "25", "30", "48", "50", "60"], String(p.fps), (v) => void runCommand(this.s, { op: "project.set", fps: Number(v) }));
    const bg = h("input", { type: "color", value: p.background && /^#/.test(p.background) ? p.background : "#000000" }) as HTMLInputElement;
    bg.addEventListener("change", () => void runCommand(this.s, { op: "project.set", background: bg.value }));
    const stats = h(
      "div",
      { class: "hint", style: "margin-top:8px" },
      `${p.tracks.length} tracks · ${p.tracks.reduce((a, t) => a + t.clips.length, 0)} clips · ${p.assets.length} media · ${p.scenes.length} drawings`,
    );
    return [
      this.sec("Project", field("Name", name), field("Frame", aspect), field("Frame rate", fps), field("Background", bg), stats),
      h(
        "div",
        { class: "hint", style: "padding:10px 2px;line-height:1.7" },
        "Select a clip on the timeline to edit it. ",
        h("br"),
        h("kbd", {}, "Space"), " play · ", h("kbd", {}, "S"), " split · ", h("kbd", {}, "B"), " blade · ", h("kbd", {}, "M"), " marker · ", h("kbd", {}, "Ctrl+Z"), " undo",
      ),
    ];
  }

  /* ------------------------------------------------------------ clip */

  private local(c: Clip) {
    return Math.max(0, Math.min(c.dur, this.s.time - c.start));
  }

  private animRow(c: Clip, path: string, label: string, min: number, max: number, step: number, cur: Animatable | undefined, def: number, fmt?: (v: number) => string): HTMLElement {
    const t = this.local(c);
    const v = valueAt(cur, t, def);
    const anim = isAnimated(cur);
    const here = anim && hasKeyAt(cur, t);
    return numRow({
      label,
      min,
      max,
      step,
      value: v,
      fmt,
      onInput: (nv) =>
        this.edit(`set ${path}`, (p) => {
          const cc = ops.getClip(p, c.id);
          const live = path === "volume" ? cc.volume : path.startsWith("transform.") ? cc.transform[path.slice(10) as keyof Clip["transform"]] : path.startsWith("grade.") ? cc.grade[path.slice(6) as keyof Clip["grade"]] : cc.fx?.[path.slice(3) as keyof NonNullable<Clip["fx"]>];
          if (isAnimated(live)) ops.keyframe(p, c.id, path, this.s.time, nv);
          else ops.setConstant(p, c.id, path, nv);
        }),
      onCommit: () => this.render(),
      key: {
        state: here ? "here" : anim ? "anim" : "none",
        toggle: () => {
          this.edit("keyframe", (p) => (here ? ops.unkeyframe(p, c.id, path, this.s.time) : ops.keyframe(p, c.id, path, this.s.time, v)));
          this.render();
        },
      },
    });
  }

  private clipPanel(c: Clip): HTMLElement[] {
    const p = this.s.project;
    const asset = findAsset(p, c.assetId);
    const out: HTMLElement[] = [];
    const color = this.mode === "color";
    const W = p.width;
    const H = p.height;

    if (!color) {
      const name = h("input", { class: "inp", value: c.name }) as HTMLInputElement;
      name.addEventListener("keydown", (e) => e.stopPropagation());
      name.addEventListener("change", () => this.edit("rename clip", (pr) => (ops.getClip(pr, c.id).name = name.value.slice(0, 80))));
      const info = h("div", { class: "hint mono", style: "font-size:11.5px" }, `${c.start.toFixed(2)}s → ${(c.start + c.dur).toFixed(2)}s  ·  ${c.dur.toFixed(2)}s${asset ? `  ·  ${asset.width ? `${asset.width}×${asset.height}` : asset.kind}` : ""}`);
      const fit = selectEl<Clip["fit"]>([["contain", "Fit"], ["cover", "Fill (crop)"], ["fill", "Stretch"], ["none", "Original size"]], c.fit, (v) => this.edit("fit", (pr) => ops.patchClip(pr, c.id, { fit: v })));
      const blend = selectEl<Clip["blend"]>(["normal", "add", "screen", "multiply", "overlay", "darken", "lighten"], c.blend, (v) => this.edit("blend", (pr) => ops.patchClip(pr, c.id, { blend: v })));
      const rev = h("input", { type: "checkbox" }) as HTMLInputElement;
      rev.checked = c.reverse;
      rev.addEventListener("change", () => this.edit("reverse", (pr) => ops.patchClip(pr, c.id, { reverse: rev.checked })));
      out.push(
        this.sec(
          "Clip",
          field("Name", name),
          info,
          c.kind === "media" || c.kind === "anim" ? numRow({ label: "Speed", min: 0.1, max: 4, step: 0.01, value: c.speed || 1, fmt: (v) => `${Math.round(v * 100)}%`, onInput: (v) => this.edit("speed", (pr) => ops.setSpeed(pr, c.id, v)), onCommit: () => this.render() }) : null,
          c.kind === "media" ? field("Reverse", rev) : null,
          field("Fit", fit),
          field("Blend", blend),
          numRow({ label: "Fade in", min: 0, max: Math.min(5, c.dur), step: 0.05, value: c.fadeIn, fmt: (v) => `${v.toFixed(2)}s`, onInput: (v) => this.edit("fade in", (pr) => ops.patchClip(pr, c.id, { fadeIn: v })) }),
          numRow({ label: "Fade out", min: 0, max: Math.min(5, c.dur), step: 0.05, value: c.fadeOut, fmt: (v) => `${v.toFixed(2)}s`, onInput: (v) => this.edit("fade out", (pr) => ops.patchClip(pr, c.id, { fadeOut: v })) }),
        ),
      );
    }

    if (!color && c.kind === "text" && c.text) out.push(this.textSec(c));

    if (!color && (c.kind !== "media" || asset?.kind !== "audio")) {
      const T = c.transform;
      const flipX = h("input", { type: "checkbox" }) as HTMLInputElement;
      flipX.checked = c.flipX;
      flipX.addEventListener("change", () => this.edit("flip", (pr) => ops.patchClip(pr, c.id, { flipX: flipX.checked })));
      const flipY = h("input", { type: "checkbox" }) as HTMLInputElement;
      flipY.checked = c.flipY;
      flipY.addEventListener("change", () => this.edit("flip", (pr) => ops.patchClip(pr, c.id, { flipY: flipY.checked })));
      out.push(
        this.sec(
          "Transform",
          this.animRow(c, "transform.x", "Position X", -W, W, 1, T.x, TRANSFORM_DEFAULTS.x, (v) => `${Math.round(v)}`),
          this.animRow(c, "transform.y", "Position Y", -H, H, 1, T.y, TRANSFORM_DEFAULTS.y, (v) => `${Math.round(v)}`),
          this.animRow(c, "transform.scale", "Scale", 0, 4, 0.005, T.scale, 1, (v) => `${Math.round(v * 100)}%`),
          this.animRow(c, "transform.scaleX", "Width", 0, 4, 0.005, T.scaleX, 1, (v) => `${Math.round(v * 100)}%`),
          this.animRow(c, "transform.scaleY", "Height", 0, 4, 0.005, T.scaleY, 1, (v) => `${Math.round(v * 100)}%`),
          this.animRow(c, "transform.rotation", "Rotation", -180, 180, 0.1, T.rotation, 0, (v) => `${v.toFixed(1)}°`),
          this.animRow(c, "transform.opacity", "Opacity", 0, 1, 0.01, T.opacity, 1, (v) => `${Math.round(v * 100)}%`),
          h("div", { class: "row", style: "gap:14px;padding:4px 0 0 2px" }, h("label", { class: "row muted" }, flipX, "Flip H"), h("label", { class: "row muted" }, flipY, "Flip V"), h("span", { class: "sp" }), b("Reset", null, () => this.edit("reset transform", (pr) => ops.patchClip(pr, c.id, { transform: {} , flipX: false, flipY: false })), "sm ghost")),
        ),
      );
      const cr = c.crop ?? { l: 0, t: 0, r: 0, b: 0 };
      const cropRow = (k: "l" | "t" | "r" | "b", label: string) => numRow({ label, min: 0, max: 0.9, step: 0.005, value: cr[k], fmt: (v) => `${Math.round(v * 100)}%`, onInput: (v) => this.edit("crop", (pr) => ops.setCrop(pr, c.id, { ...(ops.getClip(pr, c.id).crop ?? { l: 0, t: 0, r: 0, b: 0 }), [k]: v })) });
      if (c.kind === "media") out.push(this.sec("Crop", cropRow("l", "Left"), cropRow("r", "Right"), cropRow("t", "Top"), cropRow("b", "Bottom")));
    }

    if (c.kind !== "text" && (asset?.kind !== "audio")) {
      const G = c.grade;
      const gr = (k: keyof typeof GRADE_DEFAULTS, label: string, min: number, max: number, step: number, fmt?: (v: number) => string) => this.animRow(c, `grade.${k}`, label, min, max, step, G[k], GRADE_DEFAULTS[k], fmt);
      const wheel = (which: "lift" | "gamma" | "gain", label: string) => {
        const inp = h("input", { type: "color", value: "#808080" }) as HTMLInputElement;
        inp.addEventListener("change", () => void runCommand(this.s, { op: "clip.wheels", id: c.id, [which]: inp.value, strength: 0.5 }).then(() => this.render()));
        return h("label", { class: "col", style: "align-items:center;gap:4px;font-size:11px;color:var(--muted)" }, inp, label);
      };
      out.push(
        this.sec(
          "Color",
          gr("exposure", "Exposure", -3, 3, 0.01, (v) => `${v.toFixed(2)}`),
          gr("contrast", "Contrast", 0, 2, 0.01),
          gr("saturation", "Saturation", 0, 2, 0.01),
          gr("vibrance", "Vibrance", -1, 1, 0.01),
          gr("temperature", "Temperature", -1, 1, 0.01),
          gr("tint", "Tint", -1, 1, 0.01),
          gr("hue", "Hue", -180, 180, 0.5, (v) => `${Math.round(v)}°`),
          gr("lift", "Shadows (lift)", -0.5, 0.5, 0.005),
          gr("gamma", "Midtones (gamma)", 0.2, 3, 0.01),
          gr("gain", "Highlights (gain)", 0, 2, 0.01),
          h("div", { class: "row", style: "justify-content:space-around;padding:8px 0 4px" }, wheel("lift", "Shadow tint"), wheel("gamma", "Mid tint"), wheel("gain", "Highlight tint")),
          h(
            "div",
            { class: "row wrap", style: "padding-top:4px" },
            ...Object.keys(LOOKS).map((look) => b(look, null, () => void runCommand(this.s, { op: "clip.look", id: c.id, look }).then((r) => (r.ok ? this.render() : toast(r.error ?? "Look not available", "error"))), "sm")),
          ),
        ),
      );
      out.push(
        this.sec(
          "Detail & lens",
          gr("sharpen", "Sharpen", 0, 2, 0.01),
          gr("blur", "Blur", 0, 60, 0.5),
          gr("vignette", "Vignette", 0, 1, 0.01),
          gr("grain", "Film grain", 0, 1, 0.01),
          this.animRow(c, "fx.shadows", "Shadows", -1, 1, 0.01, c.fx?.shadows, FX_DEFAULTS.shadows),
          this.animRow(c, "fx.highlights", "Highlights", -1, 1, 0.01, c.fx?.highlights, FX_DEFAULTS.highlights),
          this.animRow(c, "fx.letterbox", "Letterbox bars", 0, 0.3, 0.002, c.fx?.letterbox, 0, (v) => `${Math.round(v * 100)}%`),
        ),
      );
      {
        const F = c.fx ?? {};
        const fx = (k: keyof typeof FX_DEFAULTS, label: string, min: number, max: number, step: number) => this.animRow(c, `fx.${k}`, label, min, max, step, F[k], FX_DEFAULTS[k]);
        out.push(
          this.sec(
            "Effects",
            fx("glow", "Glow", 0, 3, 0.01),
            fx("glowThreshold", "Glow threshold", 0, 1, 0.01),
            fx("glowRadius", "Glow radius", 1, 120, 1),
            fx("motionBlur", "Motion blur", 0, 2, 0.01),
            fx("zoomBlur", "Zoom blur", 0, 1, 0.01),
            fx("rgbSplit", "RGB split", 0, 40, 0.5),
            fx("glitch", "Glitch", 0, 1, 0.01),
            fx("pixelate", "Pixelate", 0, 80, 1),
            fx("posterize", "Posterize", 0, 16, 1),
            fx("scanlines", "Scanlines", 0, 1, 0.01),
            fx("invert", "Invert", 0, 1, 0.01),
          ),
        );
      }
    }

    if (!color && (c.kind === "media" || c.kind === "anim")) {
      out.push(
        this.sec(
          "Audio",
          this.animRow(c, "volume", "Volume", 0, 2, 0.01, c.volume, 1, (v) => `${v > 0 ? (20 * Math.log10(v)).toFixed(1) : "-∞"} dB`),
          asset && asset.kind !== "image"
            ? h(
                "div",
                { class: "row wrap", style: "padding-top:6px" },
                b("Clean & master", "wand", async () => {
                  this.s.setStatus("Cleaning the sound…");
                  const r = await runCommand(this.s, { op: "audio.enhance", id: c.id, voicing: asset.kind === "audio" ? "song" : "voice" });
                  toast(r.ok ? "Sound cleaned and mastered" : r.error ?? "Failed", r.ok ? "ok" : "error");
                }, "sm"),
                b("Mute", "mute", () => this.edit("mute", (pr) => ops.setConstant(pr, c.id, "volume", 0)), "sm"),
              )
            : null,
        ),
      );
    }

    if (!color && c.kind !== "media" ? true : !color && asset?.kind !== "audio") {
      const tsel = (which: "transitionIn" | "transitionOut") => {
        const tr = c[which];
        return h(
          "div",
          { class: "col", style: "gap:4px" },
          field(which === "transitionIn" ? "In" : "Out", selectEl<TransitionKind>(TRANSITIONS, tr?.kind ?? "none", (v) => this.edit("transition", (pr) => ops.patchClip(pr, c.id, { [which]: { kind: v, dur: tr?.dur || 0.6 } })))),
          tr && tr.kind !== "none" ? numRow({ label: "Length", min: 0.05, max: 3, step: 0.05, value: tr.dur, fmt: (v) => `${v.toFixed(2)}s`, onInput: (v) => this.edit("transition", (pr) => ops.patchClip(pr, c.id, { [which]: { kind: tr.kind, dur: v } })) }) : null,
        );
      };
      out.push(this.sec("Transitions", tsel("transitionIn"), tsel("transitionOut")));
      const m = c.mask ?? { shape: "none", x: 0.5, y: 0.5, w: 0.6, h: 0.6, feather: 0.05, invert: false };
      const mset = (patch: Partial<typeof m>) => this.edit("mask", (pr) => ops.patchClip(pr, c.id, { mask: { ...m, ...(ops.getClip(pr, c.id).mask ?? {}), ...patch } }));
      const inv = h("input", { type: "checkbox" }) as HTMLInputElement;
      inv.checked = m.invert;
      inv.addEventListener("change", () => mset({ invert: inv.checked }));
      out.push(
        this.sec(
          "Mask",
          field("Shape", selectEl<"none" | "rect" | "ellipse">(["none", "rect", "ellipse"], m.shape, (v) => {
            mset({ shape: v });
            this.render();
          })),
          ...(m.shape !== "none"
            ? [
                numRow({ label: "Centre X", min: 0, max: 1, step: 0.005, value: m.x, onInput: (v) => mset({ x: v }) }),
                numRow({ label: "Centre Y", min: 0, max: 1, step: 0.005, value: m.y, onInput: (v) => mset({ y: v }) }),
                numRow({ label: "Width", min: 0, max: 1.5, step: 0.005, value: m.w, onInput: (v) => mset({ w: v }) }),
                numRow({ label: "Height", min: 0, max: 1.5, step: 0.005, value: m.h, onInput: (v) => mset({ h: v }) }),
                numRow({ label: "Feather", min: 0, max: 0.5, step: 0.005, value: m.feather, onInput: (v) => mset({ feather: v }) }),
                field("Invert", inv),
              ]
            : []),
        ),
      );
      if (c.kind === "media") {
        const ck = c.chroma ?? { enabled: false, color: "#00ff00", similarity: 0.4, smoothness: 0.08, spill: 0.3 };
        const cset = (patch: Partial<typeof ck>) => this.edit("chroma", (pr) => ops.patchClip(pr, c.id, { chroma: { ...ck, ...(ops.getClip(pr, c.id).chroma ?? {}), ...patch } }));
        const en = h("input", { type: "checkbox" }) as HTMLInputElement;
        en.checked = ck.enabled;
        en.addEventListener("change", () => cset({ enabled: en.checked }));
        const col = h("input", { type: "color", value: ck.color }) as HTMLInputElement;
        col.addEventListener("change", () => cset({ color: col.value }));
        out.push(
          this.sec(
            "Chroma key",
            field("Enabled", en),
            field("Key colour", col),
            numRow({ label: "Similarity", min: 0, max: 1, step: 0.005, value: ck.similarity, onInput: (v) => cset({ similarity: v }) }),
            numRow({ label: "Smoothness", min: 0, max: 0.5, step: 0.005, value: ck.smoothness, onInput: (v) => cset({ smoothness: v }) }),
            numRow({ label: "Spill", min: 0, max: 1, step: 0.005, value: ck.spill, onInput: (v) => cset({ spill: v }) }),
          ),
        );
      }
    }
    return out;
  }

  private textSec(c: Clip): HTMLElement {
    const T = c.text!;
    const set = (patch: Partial<typeof T>) => this.edit("text", (pr) => ops.patchClip(pr, c.id, { text: patch }));
    const ta = h("textarea", { class: "inp", rows: "3", style: "width:100%" }) as HTMLTextAreaElement;
    ta.value = T.text;
    ta.addEventListener("keydown", (e) => e.stopPropagation());
    ta.addEventListener("input", () => set({ text: ta.value }));
    const font = selectEl<string>(FONTS.map((f) => f.name), T.font, (v) => set({ font: v }));
    const col = h("input", { type: "color", value: /^#/.test(T.color) ? T.color.slice(0, 7) : "#ffffff" }) as HTMLInputElement;
    col.addEventListener("input", () => set({ color: col.value }));
    const stroke = h("input", { type: "color", value: /^#/.test(T.stroke) ? T.stroke.slice(0, 7) : "#000000" }) as HTMLInputElement;
    stroke.addEventListener("input", () => set({ stroke: stroke.value }));
    const align = selectEl<"left" | "center" | "right">(["left", "center", "right"], T.align, (v) => set({ align: v }));
    const anim = selectEl<(typeof ANIMS)[number]>(ANIMS, T.animate, (v) => set({ animate: v }));
    const presets = h(
      "div",
      { class: "row wrap", style: "padding-top:6px" },
      ...Object.keys(ops.TEXT_PRESETS).map((name) => b(name, null, () => {
        this.edit("text preset", (pr) => ops.textPreset(pr, c.id, name));
        this.render();
      }, "sm")),
    );
    return this.sec(
      "Text",
      ta,
      field("Font", font),
      numRow({ label: "Size", min: 8, max: 600, step: 1, value: T.size, onInput: (v) => set({ size: v }) }),
      numRow({ label: "Weight", min: 100, max: 900, step: 100, value: T.weight, onInput: (v) => set({ weight: v }) }),
      numRow({ label: "Tracking", min: -0.2, max: 1, step: 0.005, value: T.tracking ?? 0, onInput: (v) => set({ tracking: v }) }),
      field("Colour", col),
      field("Outline", stroke),
      numRow({ label: "Outline width", min: 0, max: 30, step: 0.5, value: T.strokeWidth, onInput: (v) => set({ strokeWidth: v }) }),
      numRow({ label: "Shadow", min: 0, max: 1, step: 0.01, value: T.shadow, onInput: (v) => set({ shadow: v }) }),
      field("Align", align),
      field("Animation", anim),
      numRow({ label: "Anim length", min: 0.1, max: 4, step: 0.05, value: T.animateDur, fmt: (v) => `${v.toFixed(2)}s`, onInput: (v) => set({ animateDur: v }) }),
      presets,
    );
  }
}
