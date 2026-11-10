/**
 * Colour page commands: grading nodes, curves, qualifiers, power windows, LUTs, shot match, auto balance and the
 * viewer's compare tools. Everything here is undoable and works without any outside service.
 */
import type { Studio } from "../app/studio";
import { applyNodes, gradeBasic, sanitizeNode } from "../color/engine";
import { BUILTIN_LUTS, lutToProject, parseCube, projectToLut, resizeLut, writeCube } from "../color/lut";
import { lutTable, registerLut, syncProjectLuts } from "../color/luts";
import { autoBalance, describeMatch, matchCurves, matchLut, matchPrimaries, statsDistance } from "../color/match";
import * as cops from "../color/ops";
import { assetStats, clipStats } from "../color/probe";
import { CURVE_KEYS, type ColorNode, type CurveKey, type CurvePts, type ColourStats, type Lut3D, type PowerWindow, type Qualifier } from "../color/types";
import { setColorView } from "../color/view";
import { evaluateClip } from "../core/evaluate";
import { findAsset, uid, type Project } from "../core/model";
import { OpError, getClip } from "../core/ops";
import { saveBlob } from "../native/storage";
import { hexRgb } from "../render/compositor";
import { registerCommand, type HostArgs } from "./api";

type A = HostArgs;
const r3 = (n: number) => Math.round(n * 1000) / 1000;

/** Clip ids from {id} or {ids}. */
function idsOf(a: A): string[] {
  const many = a.any("ids");
  if (Array.isArray(many) && many.length) return many.map(String);
  return [a.str("id")];
}

/** lift / gamma / gain / offset given as a number (master), a list, or a colour with an amount. */
function wheelValue(v: unknown, len: 3 | 4, cur: number[]): number[] {
  if (typeof v === "number") {
    if (len === 3) return [v, v, v];
    return [cur[0] ?? 0, cur[1] ?? 0, cur[2] ?? 0, v];
  }
  if (Array.isArray(v)) return Array.from({ length: len }, (_, i) => (v[i] === undefined || v[i] === null ? cur[i] ?? 0 : Number(v[i])));
  if (v && typeof v === "object") {
    const o = v as { color?: string; amount?: number; master?: number };
    const out = [...cur];
    if (o.color) {
      // Push toward a colour: the colour's offset from its own grey, scaled by the amount.
      const rgb = hexRgb(o.color);
      const mean = (rgb[0] + rgb[1] + rgb[2]) / 3;
      const k = o.amount ?? 0.2;
      for (let i = 0; i < 3; i++) out[i] = (rgb[i]! - mean) * k * 2;
    }
    if (len === 4 && o.master !== undefined) out[3] = Number(o.master);
    return out.slice(0, len);
  }
  throw new OpError("Give a number (master), a list like [r, g, b, master], or {color:'#hex', amount, master}.");
}

/** Node fields found in the arguments (only the ones given). */
function nodeFields(a: A, cur?: ColorNode): Partial<ColorNode> {
  const out: Partial<ColorNode> = {};
  const s = (k: "name" | "kind" | "blend") => {
    const v = a.optStr(k);
    if (v !== undefined) (out as Record<string, unknown>)[k] = v;
  };
  s("name");
  s("kind");
  s("blend");
  if (a.any("on") !== undefined) out.on = a.bool("on", true);
  for (const k of ["mix", "contrast", "pivot", "sat", "hue", "temp", "tint"] as const) {
    const v = a.optNum(k);
    if (v !== undefined) out[k] = v;
  }
  for (const k of ["lift", "gamma", "gain"] as const) if (a.any(k) !== undefined) out[k] = wheelValue(a.any(k), 4, cur?.[k] ?? [0, 0, 0, 0]) as ColorNode["lift"];
  if (a.any("offset") !== undefined) out.offset = wheelValue(a.any("offset"), 3, cur?.offset ?? [0, 0, 0]) as ColorNode["offset"];
  return out;
}

function findLut(p: Project, ref: string): { id: string; name: string } {
  const q = ref.trim().toLowerCase();
  const own = p.luts?.find((l) => l.id === ref || l.name.toLowerCase() === q);
  if (own) return { id: own.id, name: own.name };
  const b = BUILTIN_LUTS.find((l) => l.id.toLowerCase() === q.replace(/^builtin:/, "") || l.name.toLowerCase() === q);
  if (b) return { id: `builtin:${b.id}`, name: b.name };
  throw new OpError(`No LUT "${ref}". Run color.lut.list to see the built-in ones and the ones in this project.`);
}

/**
 * Keep a table with the project (stored at 33³ at most so undo stays light). `auto` marks a LUT a tool made (shot
 * match): those are dropped again once nothing uses them; imported LUTs stay until the person removes them.
 */
function storeLut(p: Project, lut: Lut3D, name: string, auto = false): { id: string; name: string; size: number; storedSize: number } {
  const stored = lut.size > 33 ? resizeLut(lut, 33) : lut;
  const id = uid("lut");
  const entry = cops.addProjectLut(p, { ...lutToProject(stored, id, name.slice(0, 60)), ...(auto ? { auto: true } : {}) });
  // Register exactly what the project stores (16-bit, clamped), so the picture is the same after reopening.
  registerLut(entry.id, projectToLut(entry));
  return { id: entry.id, name: entry.name, size: lut.size, storedSize: stored.size };
}

/** Every id must be a clip before anything is changed, so a bad id never leaves half the work done. */
function checkedIds(s: Studio, ids: string[]): string[] {
  for (const id of ids) getClip(s.project, id);
  return ids;
}

function briefStats(s: ColourStats): Record<string, unknown> {
  return {
    brightness: r3(s.labMean[0] / 100),
    contrast: r3(s.labStd[0] / 100),
    saturation: r3(s.satMean),
    black: r3(s.black),
    white: r3(s.white),
    warmth: r3(s.labMean[2] / 100),
    greenMagenta: r3(s.labMean[1] / 100),
    palette: s.palette.map((c) => ({ hex: "#" + c.rgb.map((v) => Math.round(Math.max(0, Math.min(1, v)) * 255).toString(16).padStart(2, "0")).join(""), share: r3(c.weight) })),
  };
}

const MATCH_NODE = "Shot match";
const AUTO_NODE = "Auto balance";

/** Replace a clip's node of this name (so running a tool twice does not stack), returning the ids that were dropped. */
function dropNamed(p: Project, clipId: string, name: string): void {
  const c = getClip(p, clipId);
  if (c.nodes?.some((n) => n.name === name)) {
    c.nodes = c.nodes.filter((n) => n.name !== name);
    if (!c.nodes.length) c.nodes = undefined;
  }
}

/**
 * Bring clips to a measured look: measure each clip (without an earlier node of the same name), add one node named
 * `nodeName` that moves its look to `ref`, and report how far apart the looks were before and after.
 */
export async function matchClipsToStats(s: Studio, ids: string[], ref: ColourStats, refName: string, mode: "lut" | "curves" | "primaries", strength: number, nodeName: string): Promise<{ differences: string[]; results: { id: string; nodeId: string; before: number; after: number }[] }> {
  const results: { id: string; nodeId: string; before: number; after: number }[] = [];
  let differences: string[] = [];
  checkedIds(s, ids);
  // Each LUT-mode match keeps one small LUT with the project; say so before starting rather than stopping half way.
  if (mode === "lut" && (s.project.luts?.length ?? 0) + ids.length > 40) throw new OpError(`Matching ${ids.length} clips as LUTs would pass the 40 LUTs a project can hold. Use mode "curves" (editable, no LUT) for this many clips, or match fewer at a time.`);
  for (const id of ids) {
    const old = getClip(s.project, id).nodes?.filter((n) => n.name === nodeName).map((n) => n.id) ?? [];
    const src = await clipStats(s.project, s.pool, id, { dropNodes: old });
    if (!differences.length) differences = describeMatch(src, ref);
    const before = statsDistance(src, ref);
    const nodeId = s.mutate("shot match", (p) => {
      dropNamed(p, id, nodeName);
      let node: ColorNode;
      if (mode === "lut") {
        // 17³ is plenty for the smooth move a match makes, and keeps the project (and every undo step) small.
        const l = storeLut(p, matchLut(src, ref, { size: 17, strength }), `Match to ${refName}`, true);
        node = cops.addNode(p, id, { name: nodeName, lut: { id: l.id, mix: 1 } });
      } else if (mode === "curves") node = cops.addNode(p, id, { name: nodeName, curves: matchCurves(src, ref, { strength }) });
      else node = cops.addNode(p, id, { name: nodeName, ...matchPrimaries(src, ref, { strength }) });
      cops.pruneLuts(p);
      return node.id;
    });
    const after = statsDistance(await clipStats(s.project, s.pool, id), ref);
    results.push({ id, nodeId, before: r3(before), after: r3(after) });
  }
  return { differences, results };
}

export function registerColorCommands(): void {
  registerCommand("color.nodes", "{id} The clip's grading nodes in order: primaries, curves, LUT, qualifier and window of each.", (s: Studio, a) => cops.describeNodes(s.project, getClip(s.project, a.str("id"))));

  registerCommand(
    "color.node.add",
    "{id, name?, kind?: serial|layer, blend?: normal|add|multiply|screen|overlay|softLight, after? (node), mix?, lift?, gamma?, gain?, offset?, contrast?, pivot?, sat?, hue?, temp?, tint?} Add a grading node to a clip (up to 8). lift/gamma/gain take a number (master), [r,g,b,master] or {color:'#hex', amount, master}; 0 = no change. A serial node grades the result so far; a layer node grades the original picture and is laid over the result through its key. Returns {nodeId, index}.",
    (s, a) =>
      s.mutate("add node", (p) => {
        const after = a.any("after");
        const n = cops.addNode(p, a.str("id"), nodeFields(a), after === undefined || after === null ? undefined : (after as string | number));
        return { nodeId: n.id, index: getClip(p, a.str("id")).nodes!.indexOf(n) + 1, name: n.name };
      }),
  );

  registerCommand(
    "color.node.set",
    "{id, node (id, position 1.. or name), on?, name?, kind?, blend?, mix?, lift?, gamma?, gain?, offset?, contrast?, pivot?, sat?, hue?, temp?, tint?} Change a node. Only the fields given change.",
    (s, a) =>
      s.mutate("node", (p) => {
        const c = getClip(p, a.str("id"));
        const { node } = cops.findNode(c, a.str("node"));
        const n = cops.setNode(p, c.id, node.id, nodeFields(a, node));
        return cops.describeNodes(p, c).find((x) => x.id === n.id);
      }),
  );

  registerCommand("color.node.remove", "{id, node} Delete a node.", (s, a) => s.mutate("remove node", (p) => ({ nodes: cops.removeNode(p, a.str("id"), a.str("node")) })));
  registerCommand("color.node.move", "{id, node, to (position 1..)} Reorder a node.", (s, a) => s.mutate("move node", (p) => cops.moveNode(p, a.str("id"), a.str("node"), a.num("to")).map((n) => n.name)));
  registerCommand("color.node.reset", "{id, node} Put a node back to neutral (keeps its name, key and place).", (s, a) =>
    s.mutate("reset node", (p) => {
      const c = getClip(p, a.str("id"));
      const { node, index } = cops.findNode(c, a.str("node"));
      c.nodes![index] = sanitizeNode({ id: node.id, name: node.name, kind: node.kind, blend: node.blend, qual: node.qual, win: node.win });
      return true;
    }),
  );
  registerCommand("color.reset", "{id | ids, nodesOnly?=false} Remove all grading nodes from the clip(s) (and, unless nodesOnly, the basic grade and wheels too).", (s, a) =>
    s.mutate("reset colour", (p) => {
      for (const id of checkedIds(s, idsOf(a))) {
        const c = getClip(p, id);
        c.nodes = undefined;
        if (!a.bool("nodesOnly")) {
          c.grade = {};
          c.wheels = undefined;
        }
      }
      cops.pruneLuts(p);
      return true;
    }),
  );
  registerCommand("color.copy", "{from, to: [ids], nodesOnly?=false} Copy one clip's whole grade (basic grade, wheels, nodes with their keys) onto other clips.", (s, a) =>
    s.mutate("copy grade", (p) => {
      const to = a.any("to");
      return { copied: cops.copyGrade(p, a.str("from"), Array.isArray(to) ? to.map(String) : [String(to)], { nodesOnly: a.bool("nodesOnly") }) };
    }),
  );

  registerCommand(
    "color.curve",
    `{id, node?, curve: ${CURVE_KEYS.join("|")}, points?: [[x,y],...] | preset?: ${Object.keys(cops.CURVE_PRESETS).join("|")}, strength?=1, reset?} Draw a curve on a node (the last node, or a new one, when "node" is left out). m r g b: output level for an input level. hh hs hl: by hue (x 0..1 = 0..360°; y 0.5 = no change: hue ±90°, saturation ×0..2, brightness). ls ss: saturation by brightness / by saturation (y 0.5 = ×1).`,
    (s, a) =>
      s.mutate("curve", (p) => {
        const key = a.str("curve", "m") as CurveKey;
        const node = cops.nodeOrNew(p, a.str("id"), a.any("node") as string | undefined);
        let pts: CurvePts | null = null;
        if (!a.bool("reset")) {
          const preset = a.optStr("preset");
          if (preset) {
            const f = cops.CURVE_PRESETS[preset];
            if (!f) throw new OpError(`Unknown curve preset "${preset}". Presets: ${Object.keys(cops.CURVE_PRESETS).join(", ")}.`);
            pts = f(Math.max(0, Math.min(3, a.num("strength", 1))));
          } else pts = a.any("points") as CurvePts;
          if (!Array.isArray(pts)) throw new OpError('Give "points": [[x,y],...], a "preset", or reset:true.');
        }
        const n = cops.setCurve(p, a.str("id"), node.id, key, pts);
        return { nodeId: n.id, curve: key, points: n.curves?.[key] ?? null };
      }),
  );

  registerCommand(
    "color.qualifier",
    `{id, node?, color?: '#hex' (select around this colour), preset?: ${Object.keys(cops.QUAL_PRESETS).join("|")}, tolerance?=1, h?, hw?, hs?, s0?, s1?, ss?, l0?, l1?, ls?, invert?, on? (false keeps the settings but switches the selection off), off?} Limit a node to the pixels of one colour range (hue centre/half-width/softness, saturation range, brightness range; all 0..1). off:true removes it.`,
    (s, a) =>
      s.mutate("qualifier", (p) => {
        const node = cops.nodeOrNew(p, a.str("id"), a.any("node") as string | undefined);
        if (a.bool("off")) return cops.describeNodes(p, getClip(p, a.str("id"))).find((x) => x.id === cops.setQualifier(p, a.str("id"), node.id, null).id);
        let patch: Partial<Qualifier> = {};
        const preset = a.optStr("preset");
        if (preset) {
          const q = cops.QUAL_PRESETS[preset];
          if (!q) throw new OpError(`Unknown selection "${preset}". Choose from: ${Object.keys(cops.QUAL_PRESETS).join(", ")}.`);
          patch = { ...q };
        }
        const color = a.optStr("color");
        if (color) patch = cops.qualifierFromColor(hexRgb(color), a.num("tolerance", 1));
        for (const k of ["h", "hw", "hs", "s0", "s1", "ss", "l0", "l1", "ls"] as const) {
          const v = a.optNum(k);
          if (v !== undefined) patch[k] = v;
        }
        if (a.any("invert") !== undefined) patch.invert = a.bool("invert");
        if (a.any("on") !== undefined) patch.on = a.bool("on", true);
        const n = cops.setQualifier(p, a.str("id"), node.id, patch);
        return { nodeId: n.id, qualifier: n.qual };
      }),
  );

  registerCommand(
    "color.window",
    "{id, node?, shape?: rect|ellipse|gradient|none, x?, y?, w?, h?, rot?, soft?, invert?, keys?: [{t,x,y,w,h,rot}], off?} Limit a node to a soft-edged window inside the clip's picture (fractions: x,y = centre from the left/top; w,h = size; rot in degrees; gradient: everything above the line through x,y, w = length of the ramp). keys animate it (t = seconds from the clip start). off:true removes it.",
    (s, a) =>
      s.mutate("window", (p) => {
        const node = cops.nodeOrNew(p, a.str("id"), a.any("node") as string | undefined);
        if (a.bool("off")) {
          cops.setWindow(p, a.str("id"), node.id, null);
          return { nodeId: node.id, window: null };
        }
        const patch: Partial<PowerWindow> = {};
        const shape = a.optStr("shape");
        if (shape) patch.shape = shape as PowerWindow["shape"];
        for (const k of ["x", "y", "w", "h", "rot", "soft"] as const) {
          const v = a.optNum(k);
          if (v !== undefined) patch[k] = v;
        }
        if (a.any("invert") !== undefined) patch.invert = a.bool("invert");
        const keys = a.any("keys");
        if (Array.isArray(keys)) patch.keys = keys as PowerWindow["keys"];
        else if (keys === null) patch.keys = [];
        const n = cops.setWindow(p, a.str("id"), node.id, patch);
        return { nodeId: n.id, window: n.win };
      }),
  );

  registerCommand("color.lut.list", "The LUTs available: built-in camera transforms, film looks and utilities, plus the ones imported into this project.", (s) => ({
    builtin: BUILTIN_LUTS.map((l) => ({ id: l.id, name: l.name, group: l.group, about: l.about })),
    project: (s.project.luts ?? []).map((l) => ({ id: l.id, name: l.name, size: l.size })),
  }));

  registerCommand("color.lut.add", "{name, cube (the text of a .cube file)} Add a LUT to this project from .cube text. Returns {id}.", (s, a) =>
    s.mutate("add LUT", (p) => storeLut(p, parseCube(a.str("cube")), a.str("name", "LUT"))),
  );

  registerCommand(
    "color.lut.apply",
    "{id | ids, lut (a built-in id or name, or a project LUT id or name; \"none\" takes the LUT off the node), mix?=1, node? (put it on this node; otherwise a new node named after the LUT)} Apply a LUT through a grading node.",
    (s, a) =>
      s.mutate("apply LUT", (p) => {
        if (/^(none|off)$/i.test(a.str("lut").trim())) {
          const cleared: { id: string; nodeId: string }[] = [];
          for (const id of checkedIds(s, idsOf(a))) {
            const n = cops.clearLut(p, id, (a.any("node") as string | number | undefined) ?? getClip(p, id).nodes?.length ?? 1);
            cleared.push({ id, nodeId: n.id });
          }
          cops.pruneLuts(p);
          return { lut: null, applied: cleared };
        }
        const l = findLut(p, a.str("lut"));
        const mix = Math.max(0, Math.min(1, a.num("mix", 1)));
        const out: { id: string; nodeId: string }[] = [];
        for (const id of checkedIds(s, idsOf(a))) {
          const ref = a.any("node");
          const n = ref === undefined || ref === null ? cops.addNode(p, id, { name: l.name.slice(0, 40) }) : cops.findNode(getClip(p, id), ref as string | number).node;
          cops.setNode(p, id, n.id, { lut: { id: l.id, mix } });
          out.push({ id, nodeId: n.id });
        }
        return { lut: l, applied: out };
      }),
  );

  registerCommand("color.lut.remove", "{lut} Remove a project LUT (nodes that used it lose it).", (s, a) =>
    s.mutate("remove LUT", (p) => {
      const l = findLut(p, a.str("lut"));
      if (l.id.startsWith("builtin:")) throw new OpError("Built-in LUTs are part of the app; remove them from the nodes instead.");
      p.luts = (p.luts ?? []).filter((x) => x.id !== l.id);
      if (!p.luts.length) p.luts = undefined;
      for (const t of p.tracks) for (const c of t.clips) for (const n of c.nodes ?? []) if (n.lut?.id === l.id) n.lut = undefined;
      return true;
    }),
  );

  registerCommand(
    "color.lut.export",
    "{id, size?=33, name?, t? (timeline time for animated grades; default the clip's middle), save?=true} Generate a .cube LUT of the clip's whole grade (basic grade, wheels and nodes; windows cannot be part of a LUT and are ignored). Saved in Documents/VideoStudio unless save:false, which returns the text.",
    async (s, a) => {
      const p = s.project;
      syncProjectLuts(p);
      const c = getClip(p, a.str("id"));
      const size = Math.max(2, Math.min(65, Math.round(a.num("size", 33))));
      const d = evaluateClip(p, c, a.num("t", c.start + c.dur / 2));
      const nodes = (d.nodes ?? []).map((n) => ({ ...n, win: undefined }));
      const hasWindows = (c.nodes ?? []).some((n) => n.on && n.win && n.win.shape !== "none");
      // The basic grade runs first, then the nodes — the same order as on screen.
      const data = new Float32Array(size * size * size * 3);
      let o = 0;
      for (let b = 0; b < size; b++)
        for (let g = 0; g < size; g++)
          for (let r = 0; r < size; r++) {
            const base = gradeBasic([r / (size - 1), g / (size - 1), b / (size - 1)], d.grade, d.wheels, { shadows: d.fx.shadows, highlights: d.fx.highlights });
            const out = nodes.length ? applyNodes(nodes, base, { luts: lutTable }) : base;
            data[o++] = Math.max(0, Math.min(1, out[0]));
            data[o++] = Math.max(0, Math.min(1, out[1]));
            data[o++] = Math.max(0, Math.min(1, out[2]));
          }
      const title = a.str("name", `${p.name} - ${c.name}`).replace(/[^\w\- ]+/g, "").trim() || "VideoStudio grade";
      const text = writeCube({ size, data }, title);
      if (!a.bool("save", true)) return { size, cube: text, windowsIgnored: hasWindows };
      const saved = await saveBlob(new Blob([text], { type: "text/plain" }), `${title}.cube`, "Documents");
      return { ...saved, size, bytes: text.length, windowsIgnored: hasWindows };
    },
  );

  registerCommand(
    "color.sample",
    "{id, colors: ['#hex', ...], t?, x?=0.5, y?=0.5} What the clip's grade does to given colours (basic grade, wheels and nodes; x,y = position inside the picture, for windows). Computed with the same maths as the picture.",
    (s, a) => {
      syncProjectLuts(s.project);
      const c = getClip(s.project, a.str("id"));
      const d = evaluateClip(s.project, c, a.num("t", c.start + c.dur / 2));
      const list = a.any("colors");
      if (!Array.isArray(list) || !list.length) throw new OpError('Give "colors": a list of #hex colours.');
      const hex = (v: number[]) => "#" + v.map((x) => Math.round(Math.max(0, Math.min(1, x)) * 255).toString(16).padStart(2, "0")).join("");
      return list.slice(0, 64).map((h) => {
        const base = gradeBasic(hexRgb(String(h)), d.grade, d.wheels, { shadows: d.fx.shadows, highlights: d.fx.highlights });
        const out = d.nodes?.length ? applyNodes(d.nodes, base, { u: a.num("x", 0.5), v: a.num("y", 0.5), aspect: a.num("aspect", s.project.width / s.project.height), luts: lutTable }) : base;
        return { in: String(h), out: hex(out), rgb: out.map(r3) };
      });
    },
  );

  registerCommand(
    "color.stats",
    "{id, graded?=true, t?} Measure a clip's look in numbers: brightness, contrast, saturation, black and white points, warmth, and its main colours.",
    async (s, a) => briefStats(await clipStats(s.project, s.pool, a.str("id"), { graded: a.bool("graded", true), t: a.optNum("t") })),
  );

  registerCommand(
    "color.match",
    "{id | ids, ref (a clip id) | refAsset (a picture or video in the media list), refT?, mode?: lut|curves|primaries, strength?=1} Shot match / look from a picture: measure the clip and the reference, then add a \"Shot match\" node that brings the clip's look to the reference's (replacing an earlier match). lut = most faithful; curves = editable red/green/blue curves; primaries = lift/gamma/gain only. Returns how far apart the looks were before and after (0 = same).",
    async (s, a) => {
      const mode = a.str("mode", "lut");
      if (!["lut", "curves", "primaries"].includes(mode)) throw new OpError('mode is "lut", "curves" or "primaries".');
      const strength = Math.max(0, Math.min(1, a.num("strength", 1)));
      const p0 = s.project;
      const refClip = a.optStr("ref");
      const refAsset = a.optStr("refAsset");
      if (!refClip && !refAsset) throw new OpError('Give "ref" (a clip id) or "refAsset" (a picture or video in the media list).');
      const ref = refClip ? await clipStats(p0, s.pool, refClip, { t: a.optNum("refT") }) : await assetStats(p0, s.pool, refAsset!, { t: a.optNum("refT") });
      const refName = refClip ? getClip(p0, refClip).name : findAsset(p0, refAsset)?.name ?? "reference";
      const ids = idsOf(a).filter((id) => id !== refClip);
      if (!ids.length) throw new OpError("The reference is the only clip given. Name the clip(s) to change in id/ids and a different clip or picture as the reference.");
      return { reference: refName, mode, ...(await matchClipsToStats(s, ids, ref, refName, mode as "lut" | "curves" | "primaries", strength, MATCH_NODE)) };
    },
  );

  registerCommand(
    "color.auto",
    "{id | ids, strength?=1} Auto balance: measure the clip, then add an \"Auto balance\" node that removes a colour cast and sets clean black and white points (replacing an earlier one). Colourful scenes such as sunsets are left warm.",
    async (s, a) => {
      const strength = Math.max(0, Math.min(1, a.num("strength", 1)));
      const out: Record<string, unknown>[] = [];
      for (const id of checkedIds(s, idsOf(a))) {
        // The balance node goes first, so it is worked out from the picture before any node touches it.
        const all = getClip(s.project, id).nodes?.map((n) => n.id) ?? [];
        const stats = await clipStats(s.project, s.pool, id, { dropNodes: all });
        const fix = autoBalance(stats, { strength });
        const nodeId = s.mutate("auto balance", (p) => {
          dropNamed(p, id, AUTO_NODE);
          const c = getClip(p, id);
          const n = cops.addNode(p, id, { name: AUTO_NODE, ...fix });
          // Balance belongs first, before any creative node.
          c.nodes!.splice(c.nodes!.indexOf(n), 1);
          c.nodes!.unshift(n);
          return n.id;
        });
        out.push({ id, nodeId, ...fix });
      }
      return out;
    },
  );

  registerCommand(
    "color.view",
    "{bypass?, split? (0..1 of the frame width: left side ungraded; -1 off), key?: {id, node} | null} How the viewer shows colour (never affects renders): bypass all colour work, a compare wipe, or show one node's selection as the picture on grey.",
    (s, a) => {
      const patch: Parameters<typeof setColorView>[0] = {};
      if (a.any("bypass") !== undefined) patch.bypass = a.bool("bypass");
      if (a.any("split") !== undefined) patch.split = a.num("split", -1);
      const key = a.any("key");
      if (key === null || key === false) patch.showKey = null;
      else if (key && typeof key === "object") {
        const k = key as { id?: string; node?: string | number };
        const c = getClip(s.project, String(k.id));
        patch.showKey = { clipId: c.id, nodeId: cops.findNode(c, k.node ?? 1).node.id };
      }
      return { ...setColorView(patch) };
    },
  );
}
