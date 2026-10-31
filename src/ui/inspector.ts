/** Clip inspector: tabs for edit, transform (keyframes), colour, effects, text and transitions. */
import type { Studio } from "../app/studio";
import { GRADE_DEFAULTS, TRANSFORM_DEFAULTS, findAsset, findClip, type BlendMode, type Clip, type Grade, type TextStyle, type TransitionKind } from "../core/model";
import { hasKeyAt, isAnimated, valueAt } from "../core/keyframes";
import * as ops from "../core/ops";
import { runCommand } from "../agent/api";
import { colorInput, h, select, sheet, slider, toggle, type SliderRow } from "./dom";

export type InspectorTab = "edit" | "transform" | "color" | "effects" | "text" | "transition";

const TRANSITIONS: [TransitionKind, string][] = [
  ["none", "None"], ["fade", "Fade"], ["dip", "Dip to black"], ["blur", "Blur"], ["zoomIn", "Zoom"], ["slideLeft", "Slide left"], ["slideRight", "Slide right"],
  ["slideUp", "Slide up"], ["wipeLeft", "Wipe left"], ["wipeRight", "Wipe right"], ["wipeUp", "Wipe up"], ["iris", "Iris"],
];
const PRESETS = ["natural", "cinematic", "tealOrange", "noir", "warm", "cool", "vintage", "punchy", "faded", "night", "dream"];
const BLENDS: BlendMode[] = ["normal", "add", "multiply", "screen", "overlay", "darken", "lighten"];
const FONTS: [string, string][] = [
  ["Geist Variable, system-ui, sans-serif", "Geist"], ["Instrument Serif, Georgia, serif", "Serif"], ["Impact, Haettenschweiler, sans-serif", "Impact"],
  ["'Comic Sans MS', 'Comic Neue', cursive", "Comic"], ["'Courier New', monospace", "Mono"], ["system-ui, sans-serif", "System"],
];

export function openInspector(s: Studio, tab: InspectorTab = "edit"): void {
  const id = s.selection;
  if (!id || !findClip(s.project, id)) return;
  const tabs = h("nav", { class: "tabs" });
  const body = h("div", { class: "insp-body" });
  const root = h("div", { class: "inspector" }, tabs, body);
  const offs: (() => void)[] = [];
  let current: InspectorTab = tab;
  let rows: { prop: string; row: SliderRow; def: number }[] = [];
  const clip = () => findClip(s.project, id)?.clip;
  const sh = sheet("Clip", root, { onClose: () => offs.forEach((f) => f()) });

  const list = (): InspectorTab[] => {
    const c = clip();
    const out: InspectorTab[] = ["edit", "transform", "color", "effects", "transition"];
    if (c?.kind === "text") out.splice(1, 0, "text");
    return out;
  };

  const draw = () => {
    const c = clip();
    if (!c) return sh.close();
    tabs.replaceChildren(...list().map((t) => h("button", { class: `tab ${t === current ? "on" : ""}`, type: "button", onclick: () => ((current = t), draw()) }, label(t))));
    rows = [];
    body.replaceChildren(...build(current, c));
    syncKeys();
  };

  const prop = (path: string, def: number, c: Clip) => {
    const t = ops.parseTarget(path);
    const v = t.group === "volume" ? c.volume : t.group === "transform" ? c.transform[t.prop] : c.grade[t.prop];
    return v;
  };

  /** Animatable slider: if the property has keyframes, editing sets a key at the playhead. */
  const anim = (lbl: string, path: string, min: number, max: number, step: number, def: number, fmt?: (v: number) => string): HTMLElement => {
    const c = clip()!;
    const local = s.time - c.start;
    const row = slider(lbl, min, max, step, valueAt(prop(path, def, c), local, def), (v) => {
      s.mutate(`set ${path}`, (p) => {
        const cc = ops.getClip(p, id);
        if (isAnimated(prop(path, def, cc))) ops.keyframe(p, id, path, clampTime(s, cc), v, "inOut", def);
        else ops.setConstant(p, id, path, v);
      });
    }, {
      fmt,
      onKey: () => {
        s.mutate(`key ${path}`, (p) => {
          const cc = ops.getClip(p, id);
          const t = clampTime(s, cc);
          const a = prop(path, def, cc);
          if (hasKeyAt(a, t - cc.start)) ops.unkeyframe(p, id, path, t);
          else ops.keyframe(p, id, path, t, valueAt(a, t - cc.start, def), "inOut", def);
        });
        syncKeys();
      },
      onReset: () => s.mutate(`reset ${path}`, (p) => ops.setConstant(p, id, path, def)),
    });
    rows.push({ prop: path, row, def });
    return row.el;
  };

  const syncKeys = () => {
    const c = clip();
    if (!c) return;
    const local = s.time - c.start;
    for (const r of rows) {
      const a = prop(r.prop, r.def, c);
      r.row.setKeyState(isAnimated(a) ? (hasKeyAt(a, local) ? "onKey" : "animated") : "none");
      if (isAnimated(a) && document.activeElement !== r.row.input) r.row.set(valueAt(a, local, r.def));
    }
  };

  const set = (label: string, fn: (c: Clip) => void, redraw = false) => {
    s.mutate(label, (p) => fn(ops.getClip(p, id)));
    if (redraw) draw();
  };

  const build = (t: InspectorTab, c: Clip): HTMLElement[] => {
    const asset = findAsset(s.project, c.assetId);
    const hasAudio = c.kind === "media" && asset?.kind !== "image";
    switch (t) {
      case "edit":
        return [
          h("input", { class: "name", value: c.name, "aria-label": "Clip name", onchange: (e: Event) => set("rename", (cc) => (cc.name = (e.target as HTMLInputElement).value)) }),
          c.kind === "media" || c.kind === "anim"
            ? slider("Speed", 0.1, 8, 0.05, c.speed, (v) => s.mutate("speed", (p) => ops.setSpeed(p, id, v)), { fmt: (v) => `${v.toFixed(2)}×` }).el
            : null,
          hasAudio ? anim("Volume", "volume", 0, 2, 0.01, 1, (v) => `${Math.round(v * 100)}%`) : null,
          hasAudio ? slider("Fade in", 0, Math.min(10, c.dur / 2), 0.05, c.fadeIn, (v) => set("fade in", (cc) => (cc.fadeIn = v)), { fmt: (v) => `${v.toFixed(2)}s` }).el : null,
          hasAudio ? slider("Fade out", 0, Math.min(10, c.dur / 2), 0.05, c.fadeOut, (v) => set("fade out", (cc) => (cc.fadeOut = v)), { fmt: (v) => `${v.toFixed(2)}s` }).el : null,
          c.kind === "media" && asset?.kind === "video" ? toggle("Reverse", c.reverse, (v) => set("reverse", (cc) => (cc.reverse = v))) : null,
          c.kind === "media" || c.kind === "anim" ? select("Fit", ["contain", "cover", "fill", "none"] as const, c.fit, (v) => set("fit", (cc) => (cc.fit = v))) : null,
          c.kind === "color" ? colorInput("Colour", c.color ?? "#000000", (v) => set("colour", (cc) => (cc.color = v))) : null,
          ...(c.kind === "shape" && c.shape
            ? [
                select("Shape", ["rect", "ellipse", "line"] as const, c.shape.shape, (v) => set("shape", (cc) => (cc.shape!.shape = v))),
                colorInput("Fill", c.shape.fill || "#ffffff", (v) => set("shape fill", (cc) => (cc.shape!.fill = v))),
                colorInput("Outline", c.shape.stroke || "#000000", (v) => set("shape outline", (cc) => (cc.shape!.stroke = v))),
                slider("Outline width", 0, 60, 1, c.shape.strokeWidth, (v) => set("shape outline", (cc) => (cc.shape!.strokeWidth = v))).el,
                slider("Width", 10, 3000, 1, c.shape.w, (v) => set("shape size", (cc) => (cc.shape!.w = v))).el,
                slider("Height", 10, 3000, 1, c.shape.h, (v) => set("shape size", (cc) => (cc.shape!.h = v))).el,
                slider("Corner radius", 0, 500, 1, c.shape.radius, (v) => set("shape radius", (cc) => (cc.shape!.radius = v))).el,
              ]
            : []),
          h("div", { class: "row" },
            toggle("Flip H", c.flipX, (v) => set("flip", (cc) => (cc.flipX = v))),
            toggle("Flip V", c.flipY, (v) => set("flip", (cc) => (cc.flipY = v))),
          ),
          asset?.kind === "image" ? h("button", { class: "btn", type: "button", onclick: () => s.mutate("ken burns", (p) => ops.kenBurns(p, id)) }, "Ken Burns move") : null,
        ].filter(Boolean) as HTMLElement[];
      case "transform":
        return [
          h("p", { class: "hint" }, "Tap ◆ to keyframe at the playhead. Drag or pinch the preview to move, scale and rotate."),
          anim("Position X", "x", -s.project.width * 1.5, s.project.width * 1.5, 1, 0, (v) => v.toFixed(0)),
          anim("Position Y", "y", -s.project.height * 1.5, s.project.height * 1.5, 1, 0, (v) => v.toFixed(0)),
          anim("Scale", "scale", 0.02, 6, 0.01, 1, (v) => `${Math.round(v * 100)}%`),
          anim("Stretch X", "scaleX", 0.05, 4, 0.01, 1, (v) => `${Math.round(v * 100)}%`),
          anim("Stretch Y", "scaleY", 0.05, 4, 0.01, 1, (v) => `${Math.round(v * 100)}%`),
          anim("Rotation", "rotation", -720, 720, 0.5, 0, (v) => `${v.toFixed(1)}°`),
          anim("Opacity", "opacity", 0, 1, 0.01, 1, (v) => `${Math.round(v * 100)}%`),
          anim("Anchor X", "anchorX", 0, 1, 0.01, TRANSFORM_DEFAULTS.anchorX),
          anim("Anchor Y", "anchorY", 0, 1, 0.01, TRANSFORM_DEFAULTS.anchorY),
          select("Ease of next key", ["inOut", "linear", "in", "out", "back", "elastic", "bounce", "hold"] as const, "inOut", (v) =>
            s.mutate("ease", (p) => {
              const cc = ops.getClip(p, id);
              const local = s.time - cc.start;
              for (const a of Object.values(cc.transform)) if (Array.isArray(a)) for (const k of a) if (k.t <= local + 1e-4) k.ease = v;
            }),
          ),
          h("div", { class: "row wrap" },
            h("button", { class: "btn", type: "button", onclick: () => s.mutate("reset transform", (p) => (ops.getClip(p, id).transform = {})) }, "Reset"),
            h("button", { class: "btn", type: "button", onclick: () => quickAnim(s, id, "popIn") }, "Pop in"),
            h("button", { class: "btn", type: "button", onclick: () => quickAnim(s, id, "slideIn") }, "Slide in"),
            h("button", { class: "btn", type: "button", onclick: () => quickAnim(s, id, "zoomOut") }, "Zoom out"),
            h("button", { class: "btn", type: "button", onclick: () => quickAnim(s, id, "spin") }, "Spin"),
            h("button", { class: "btn", type: "button", onclick: () => quickAnim(s, id, "float") }, "Float"),
          ),
        ];
      case "color":
        return [
          h("div", { class: "chips" }, ...PRESETS.map((pr) => h("button", { class: "chip", type: "button", onclick: async () => (await runCommand(s, { op: "clip.grade", id, preset: pr }), draw()) }, pr))),
          ...(Object.keys(GRADE_DEFAULTS) as (keyof Grade)[])
            .filter((k) => !["blur", "sharpen", "grain", "vignette"].includes(k))
            .map((k) => anim(cap(k), `grade.${k}`, ...gradeRange(k), GRADE_DEFAULTS[k])),
          h("button", { class: "btn", type: "button", onclick: () => (s.mutate("reset grade", (p) => (ops.getClip(p, id).grade = {})), draw()) }, "Reset colour"),
        ];
      case "effects":
        return [
          anim("Blur", "grade.blur", 0, 120, 0.5, 0),
          anim("Sharpen", "grade.sharpen", 0, 2, 0.01, 0),
          anim("Vignette", "grade.vignette", 0, 1, 0.01, 0),
          anim("Film grain", "grade.grain", 0, 1, 0.01, 0),
          select("Blend", BLENDS, c.blend, (v) => set("blend", (cc) => (cc.blend = v))),
          h("h3", {}, "Camera shake"),
          slider("Amount", 0, 80, 1, c.shake?.amp ?? 0, (v) => set("shake", (cc) => (cc.shake = { amp: v, freq: cc.shake?.freq ?? 8 }))).el,
          slider("Speed", 1, 30, 0.5, c.shake?.freq ?? 8, (v) => set("shake", (cc) => (cc.shake = { amp: cc.shake?.amp ?? 0, freq: v }))).el,
          h("h3", {}, "Chroma key (green screen)"),
          toggle("Enabled", !!c.chroma?.enabled, (v) => set("chroma", (cc) => (cc.chroma = { ...(cc.chroma ?? defChroma()), enabled: v }), true)),
          ...(c.chroma?.enabled
            ? [
                colorInput("Key colour", c.chroma.color, (v) => set("chroma", (cc) => (cc.chroma!.color = v))),
                slider("Similarity", 0, 0.5, 0.005, c.chroma.similarity, (v) => set("chroma", (cc) => (cc.chroma!.similarity = v))).el,
                slider("Smoothness", 0, 0.3, 0.005, c.chroma.smoothness, (v) => set("chroma", (cc) => (cc.chroma!.smoothness = v))).el,
                slider("Spill", 0, 1, 0.01, c.chroma.spill, (v) => set("chroma", (cc) => (cc.chroma!.spill = v))).el,
              ]
            : []),
          h("h3", {}, "Mask"),
          select("Shape", ["none", "rect", "ellipse"] as const, c.mask?.shape ?? "none", (v) => set("mask", (cc) => (cc.mask = { ...(cc.mask ?? defMask()), shape: v }), true)),
          ...(c.mask && c.mask.shape !== "none"
            ? (["x", "y", "w", "h", "feather"] as const).map((k) => slider(cap(k === "w" ? "width" : k === "h" ? "height" : k), 0, k === "feather" ? 0.5 : k === "w" || k === "h" ? 2 : 1, 0.005, c.mask![k], (v) => set("mask", (cc) => (cc.mask![k] = v))).el).concat([toggle("Invert", c.mask.invert, (v) => set("mask", (cc) => (cc.mask!.invert = v)))])
            : []),
        ];
      case "text": {
        const st = c.text!;
        const upd = (patch: Partial<TextStyle>, redraw = false) => set("text", (cc) => Object.assign(cc.text!, patch), redraw);
        const ta = h("textarea", { class: "text-edit", rows: 3, "aria-label": "Text" }, st.text) as HTMLTextAreaElement;
        ta.addEventListener("input", () => {
          upd({ text: ta.value });
          s.mutate("rename", (p) => (ops.getClip(p, id).name = ta.value.split("\n")[0]!.slice(0, 40)));
        });
        return [
          ta,
          select("Font", FONTS.map(([v, l]) => [v, l] as [string, string]), st.font, (v) => upd({ font: v })),
          slider("Size", 10, 600, 1, st.size, (v) => upd({ size: v })).el,
          slider("Weight", 100, 900, 100, st.weight, (v) => upd({ weight: v })).el,
          colorInput("Colour", st.color, (v) => upd({ color: v })),
          colorInput("Outline", st.stroke, (v) => upd({ stroke: v })),
          slider("Outline width", 0, 30, 0.5, st.strokeWidth, (v) => upd({ strokeWidth: v })).el,
          slider("Shadow", 0, 60, 1, st.shadow, (v) => upd({ shadow: v })).el,
          toggle("Background box", !!st.bg, (v) => upd({ bg: v ? "#000000aa" : "" }, true)),
          st.bg ? colorInput("Box colour", st.bg.slice(0, 7), (v) => upd({ bg: `${v}cc` })) : null,
          select("Align", ["left", "center", "right"] as const, st.align, (v) => upd({ align: v })),
          select("Animation", ["none", "typewriter", "pop", "rise", "wave", "fade"] as const, st.animate, (v) => upd({ animate: v })),
          slider("Animation time", 0.1, 5, 0.05, st.animateDur, (v) => upd({ animateDur: v }), { fmt: (v) => `${v.toFixed(2)}s` }).el,
        ].filter(Boolean) as HTMLElement[];
      }
      case "transition":
        return [
          select("In", TRANSITIONS, c.transitionIn.kind, (v) => set("transition", (cc) => (cc.transitionIn = { ...cc.transitionIn, kind: v }))),
          slider("In duration", 0.05, Math.max(0.1, Math.min(5, c.dur)), 0.05, c.transitionIn.dur, (v) => set("transition", (cc) => (cc.transitionIn.dur = v)), { fmt: (v) => `${v.toFixed(2)}s` }).el,
          select("Out", TRANSITIONS, c.transitionOut.kind, (v) => set("transition", (cc) => (cc.transitionOut = { ...cc.transitionOut, kind: v }))),
          slider("Out duration", 0.05, Math.max(0.1, Math.min(5, c.dur)), 0.05, c.transitionOut.dur, (v) => set("transition", (cc) => (cc.transitionOut.dur = v)), { fmt: (v) => `${v.toFixed(2)}s` }).el,
          h("p", { class: "hint" }, "Overlap two clips on different tracks for a cross-transition: give the top clip a Fade/Wipe in."),
        ];
    }
  };

  offs.push(s.on("time", syncKeys));
  offs.push(s.on("change", () => (clip() ? syncKeys() : sh.close())));
  draw();
}

function clampTime(s: Studio, c: Clip): number {
  return Math.max(c.start, Math.min(c.start + c.dur, s.time));
}

function label(t: InspectorTab): string {
  return { edit: "Edit", transform: "Motion", color: "Colour", effects: "Effects", text: "Text", transition: "Transition" }[t];
}

function cap(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function gradeRange(k: keyof Grade): [number, number, number] {
  switch (k) {
    case "exposure": return [-3, 3, 0.01];
    case "contrast": case "saturation": case "gain": return [0, 2, 0.01];
    case "gamma": return [0.2, 3, 0.01];
    case "hue": return [-180, 180, 1];
    case "lift": return [-0.5, 0.5, 0.005];
    default: return [-1, 1, 0.01];
  }
}

function defChroma() {
  return { enabled: true, color: "#00ff00", similarity: 0.12, smoothness: 0.08, spill: 0.5 };
}
function defMask() {
  return { shape: "rect" as const, x: 0.5, y: 0.5, w: 0.8, h: 0.8, feather: 0.05, invert: false };
}

/** One-tap motion presets written as keyframes from the playhead. */
function quickAnim(s: Studio, id: string, kind: "popIn" | "slideIn" | "zoomOut" | "spin" | "float"): void {
  s.mutate(`motion ${kind}`, (p) => {
    const c = ops.getClip(p, id);
    const t0 = Math.max(c.start, Math.min(s.time, c.start + c.dur - 0.1));
    const d = Math.min(0.6, c.start + c.dur - t0);
    switch (kind) {
      case "popIn":
        ops.keyframe(p, id, "scale", t0, 0, "back");
        ops.keyframe(p, id, "scale", t0 + d, 1, "inOut");
        ops.keyframe(p, id, "opacity", t0, 0, "out");
        ops.keyframe(p, id, "opacity", t0 + d * 0.5, 1, "inOut");
        break;
      case "slideIn":
        ops.keyframe(p, id, "x", t0, -p.width, "out");
        ops.keyframe(p, id, "x", t0 + d, 0, "inOut");
        break;
      case "zoomOut":
        ops.keyframe(p, id, "scale", t0, 1.4, "out");
        ops.keyframe(p, id, "scale", Math.min(c.start + c.dur, t0 + 2), 1, "inOut");
        break;
      case "spin":
        ops.keyframe(p, id, "rotation", t0, 0, "inOut");
        ops.keyframe(p, id, "rotation", t0 + Math.max(d, 1), 360, "inOut");
        break;
      case "float": {
        const end = c.start + c.dur;
        for (let t = t0, i = 0; t <= end; t += 1, i++) ops.keyframe(p, id, "y", t, i % 2 ? -24 : 24, "inOut");
        break;
      }
    }
  });
}
