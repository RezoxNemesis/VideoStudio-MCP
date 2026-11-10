/**
 * Colour page edits on the project: add / change / remove / reorder grading nodes, curves, qualifiers, windows,
 * LUTs, and copying a grade between clips. Pure functions on the project, no drawing.
 */
import { findAsset, uid, type Clip, type Project } from "../core/model";
import { OpError, getClip } from "../core/ops";
import { cleanCurve, hsvOf, isHueCurve, isIdentityCurve, sanitizeNode } from "./engine";
import { CURVE_KEYS, MAX_NODES, QUAL_DEFAULT, WIN_DEFAULT, newColorNode, type ColorNode, type CurveKey, type CurvePts, type PowerWindow, type ProjectLut, type Qualifier } from "./types";

/** Find a node by id, by 1-based position ("1", 2) or by name (case-insensitive). */
export function findNode(c: Clip, ref: string | number): { node: ColorNode; index: number } {
  const nodes = c.nodes ?? [];
  const s = String(ref).trim();
  let i = nodes.findIndex((n) => n.id === s);
  if (i < 0 && /^\d+$/.test(s)) i = Number(s) - 1;
  if (i < 0 || i >= nodes.length) i = nodes.findIndex((n) => n.name.toLowerCase() === s.toLowerCase());
  const node = nodes[i];
  if (!node) throw new OpError(nodes.length ? `No node "${s}" on this clip. It has: ${nodes.map((n, k) => `${k + 1} ${n.name}`).join(", ")}.` : "This clip has no grading nodes yet. Add one first.");
  return { node, index: i };
}

/** The node to work on: the one named, otherwise the last one, creating the first node when the clip has none. */
export function nodeOrNew(p: Project, clipId: string, ref?: string | number): ColorNode {
  const c = getClip(p, clipId);
  if (ref !== undefined && ref !== null && String(ref) !== "") return findNode(c, ref).node;
  if (!c.nodes?.length) return addNode(p, clipId, {});
  return c.nodes[c.nodes.length - 1]!;
}

export function addNode(p: Project, clipId: string, fields: Partial<ColorNode>, after?: string | number): ColorNode {
  const c = getClip(p, clipId);
  if (!isPicture(p, c)) throw new OpError("Grading nodes go on picture clips, not on sound.");
  const nodes = (c.nodes ??= []);
  if (nodes.length >= MAX_NODES) throw new OpError(`A clip can carry ${MAX_NODES} nodes. Remove one, or put more on an adjustment clip above.`);
  const n = sanitizeNode({ ...newColorNode(uid("nod"), `Node ${nodes.length + 1}`), ...fields, id: uid("nod") });
  if (!fields.name) n.name = `Node ${nodes.length + 1}`;
  const at = after === undefined ? nodes.length : findNode(c, after).index + 1;
  nodes.splice(at, 0, n);
  return n;
}

/** Change fields of a node. Arrays (lift, gamma, gain, offset) may be partial; everything is clamped to its range. */
export function setNode(p: Project, clipId: string, ref: string | number, patch: Partial<ColorNode>): ColorNode {
  const c = getClip(p, clipId);
  const { node, index } = findNode(c, ref);
  const merged = sanitizeNode({ ...node, ...patch, id: node.id, curves: patch.curves === undefined ? node.curves : patch.curves, qual: patch.qual === undefined ? node.qual : patch.qual, win: patch.win === undefined ? node.win : patch.win, lut: patch.lut === undefined ? node.lut : patch.lut });
  c.nodes![index] = merged;
  return merged;
}

export function removeNode(p: Project, clipId: string, ref: string | number): number {
  const c = getClip(p, clipId);
  const { index } = findNode(c, ref);
  c.nodes!.splice(index, 1);
  if (!c.nodes!.length) c.nodes = undefined;
  return c.nodes?.length ?? 0;
}

export function moveNode(p: Project, clipId: string, ref: string | number, to: number): ColorNode[] {
  const c = getClip(p, clipId);
  const { node, index } = findNode(c, ref);
  c.nodes!.splice(index, 1);
  c.nodes!.splice(Math.max(0, Math.min(c.nodes!.length, Math.round(to) - 1)), 0, node);
  return c.nodes!;
}

/** Ready-made curve shapes. `k` is the strength, 0..1. */
export const CURVE_PRESETS: Record<string, (k: number) => CurvePts> = {
  "s-curve": (k) => [[0, 0], [0.25, 0.25 - 0.09 * k], [0.75, 0.75 + 0.09 * k], [1, 1]],
  "soft-contrast": (k) => [[0, 0], [0.2, 0.2 - 0.04 * k], [0.5, 0.5], [0.8, 0.8 + 0.04 * k], [1, 1]],
  fade: (k) => [[0, 0.12 * k], [0.3, 0.3 + 0.03 * k], [1, 1 - 0.04 * k]],
  crush: (k) => [[0, 0], [0.12 * k + 0.02, 0], [1, 1]],
  brighten: (k) => [[0, 0], [0.5, 0.5 + 0.14 * k], [1, 1]],
  darken: (k) => [[0, 0], [0.5, 0.5 - 0.14 * k], [1, 1]],
  "lift-shadows": (k) => [[0, 0], [0.2, 0.2 + 0.1 * k], [0.6, 0.6 + 0.02 * k], [1, 1]],
  "roll-highlights": (k) => [[0, 0], [0.6, 0.6], [0.85, 0.85 - 0.05 * k], [1, 1 - 0.1 * k]],
  invert: () => [[0, 1], [1, 0]],
};

/** Set (or clear, with null) one curve of a node. */
export function setCurve(p: Project, clipId: string, ref: string | number, key: CurveKey, pts: CurvePts | null): ColorNode {
  if (!(CURVE_KEYS as readonly string[]).includes(key)) throw new OpError(`Unknown curve "${key}". Curves: ${CURVE_KEYS.join(", ")}.`);
  const c = getClip(p, clipId);
  const { node } = findNode(c, ref);
  const curves = { ...(node.curves ?? {}) };
  const clean = pts ? cleanCurve(pts) : null;
  if (pts && (!clean || clean.length < 2)) throw new OpError(isHueCurve(key) ? "A hue curve needs at least two points [x, y] (y 0.5 = no change)." : "A curve needs at least two points [x, y] between 0 and 1.");
  if (!clean || isIdentityCurve(key, clean)) delete curves[key];
  else curves[key] = clean;
  node.curves = Object.keys(curves).length ? curves : undefined;
  return node;
}

/** Named selections for the qualifier. */
export const QUAL_PRESETS: Record<string, Partial<Qualifier>> = {
  skin: { h: 0.065, hw: 0.05, hs: 0.035, s0: 0.15, s1: 0.75, ss: 0.1, l0: 0.2, l1: 0.95, ls: 0.1 },
  sky: { h: 0.59, hw: 0.07, hs: 0.05, s0: 0.15, s1: 1, ss: 0.1, l0: 0.25, l1: 1, ls: 0.1 },
  foliage: { h: 0.3, hw: 0.1, hs: 0.05, s0: 0.15, s1: 1, ss: 0.1, l0: 0.05, l1: 0.9, ls: 0.1 },
  reds: { h: 0, hw: 0.05, hs: 0.04, s0: 0.25, s1: 1, ss: 0.1, l0: 0, l1: 1, ls: 0.05 },
  oranges: { h: 0.08, hw: 0.04, hs: 0.04, s0: 0.25, s1: 1, ss: 0.1, l0: 0, l1: 1, ls: 0.05 },
  yellows: { h: 0.16, hw: 0.04, hs: 0.04, s0: 0.25, s1: 1, ss: 0.1, l0: 0, l1: 1, ls: 0.05 },
  greens: { h: 0.33, hw: 0.08, hs: 0.05, s0: 0.2, s1: 1, ss: 0.1, l0: 0, l1: 1, ls: 0.05 },
  cyans: { h: 0.5, hw: 0.05, hs: 0.04, s0: 0.2, s1: 1, ss: 0.1, l0: 0, l1: 1, ls: 0.05 },
  blues: { h: 0.64, hw: 0.07, hs: 0.05, s0: 0.2, s1: 1, ss: 0.1, l0: 0, l1: 1, ls: 0.05 },
  magentas: { h: 0.86, hw: 0.07, hs: 0.05, s0: 0.2, s1: 1, ss: 0.1, l0: 0, l1: 1, ls: 0.05 },
  highlights: { hw: 0.5, s0: 0, s1: 1, l0: 0.7, l1: 1, ls: 0.15 },
  midtones: { hw: 0.5, s0: 0, s1: 1, l0: 0.3, l1: 0.7, ls: 0.15 },
  shadows: { hw: 0.5, s0: 0, s1: 1, l0: 0, l1: 0.3, ls: 0.15 },
};

/** A qualifier around one picked colour (display RGB 0..1): a hue slice with saturation and luminance ranges around it. */
export function qualifierFromColor(rgb: [number, number, number], tolerance = 1): Qualifier {
  const [h, s] = hsvOf(rgb);
  const L = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
  const t = Math.max(0.2, Math.min(4, tolerance));
  if (s < 0.08) {
    // A grey was picked: hue means nothing, select by lightness and low saturation.
    return { ...QUAL_DEFAULT, on: true, hw: 0.5, s0: 0, s1: Math.min(1, 0.18 * t), ss: 0.08, l0: Math.max(0, L - 0.15 * t), l1: Math.min(1, L + 0.15 * t), ls: 0.1 };
  }
  return { on: true, h, hw: Math.min(0.5, 0.045 * t), hs: 0.035 * t, s0: Math.max(0, s - 0.3 * t), s1: Math.min(1, s + 0.35 * t), ss: 0.1, l0: Math.max(0, L - 0.35 * t), l1: Math.min(1, L + 0.4 * t), ls: 0.12, invert: false };
}

export function setQualifier(p: Project, clipId: string, ref: string | number, patch: Partial<Qualifier> | null): ColorNode {
  const c = getClip(p, clipId);
  const { node, index } = findNode(c, ref);
  const next = sanitizeNode({ ...node, qual: patch ? { ...QUAL_DEFAULT, ...(node.qual ?? {}), on: true, ...patch } : undefined });
  if (!patch) next.qual = undefined;
  c.nodes![index] = next;
  return next;
}

export function setWindow(p: Project, clipId: string, ref: string | number, patch: Partial<PowerWindow> | null): ColorNode {
  const c = getClip(p, clipId);
  const { node, index } = findNode(c, ref);
  const base: PowerWindow = { ...WIN_DEFAULT, shape: "ellipse", ...(node.win ?? {}) };
  const next = sanitizeNode({ ...node, win: patch ? { ...base, ...patch, keys: patch.keys === undefined ? base.keys : patch.keys } : undefined });
  if (!patch) next.win = undefined;
  c.nodes![index] = next;
  return next;
}

/** Keep a LUT with the project so it travels with it. Re-uses an entry with the same name and source. */
export function addProjectLut(p: Project, lut: Omit<ProjectLut, "id"> & { id?: string }): ProjectLut {
  const list = (p.luts ??= []);
  const same = list.find((l) => l.name === lut.name && l.source === lut.source && l.data === lut.data);
  if (same) return same;
  if (list.length >= 40) throw new OpError("This project already holds 40 LUTs. Remove unused ones first.");
  const entry: ProjectLut = { ...lut, id: lut.id ?? uid("lut") };
  list.push(entry);
  return entry;
}

/** Drop project LUTs no node uses any more. */
export function pruneLuts(p: Project): number {
  if (!p.luts?.length) return 0;
  const used = new Set<string>();
  for (const t of p.tracks) for (const c of t.clips) for (const n of c.nodes ?? []) if (n.lut) used.add(n.lut.id);
  const before = p.luts.length;
  p.luts = p.luts.filter((l) => used.has(l.id));
  if (!p.luts.length) p.luts = undefined;
  return before - (p.luts?.length ?? 0);
}

/** Copy the whole grade (basic grade, wheels and nodes) of one clip onto others. Windows and their animation are kept. */
export function copyGrade(p: Project, fromId: string, toIds: string[], o: { nodesOnly?: boolean } = {}): number {
  const from = getClip(p, fromId);
  let n = 0;
  for (const id of toIds) {
    if (id === fromId) continue;
    const to = getClip(p, id);
    if (!isPicture(p, to)) continue;
    to.nodes = from.nodes ? (JSON.parse(JSON.stringify(from.nodes)) as ColorNode[]).map((x) => ({ ...x, id: uid("nod") })) : undefined;
    if (!o.nodesOnly) {
      to.grade = JSON.parse(JSON.stringify(from.grade)) as Clip["grade"];
      to.wheels = from.wheels ? (JSON.parse(JSON.stringify(from.wheels)) as Clip["wheels"]) : undefined;
    }
    n++;
  }
  return n;
}

/** A short description of a clip's nodes, for lists and for the agent. */
export function describeNodes(p: Project, c: Clip): Record<string, unknown>[] {
  return (c.nodes ?? []).map((n, i) => ({
    index: i + 1,
    id: n.id,
    name: n.name,
    on: n.on,
    kind: n.kind,
    ...(n.kind === "layer" ? { blend: n.blend } : {}),
    mix: n.mix,
    curves: n.curves ? Object.keys(n.curves) : [],
    lut: n.lut ? { id: n.lut.id, name: p.luts?.find((l) => l.id === n.lut!.id)?.name ?? n.lut.id.replace(/^builtin:/, ""), mix: n.lut.mix } : null,
    qualifier: n.qual?.on ? n.qual : null,
    window: n.win && n.win.shape !== "none" ? { ...n.win, keys: n.win.keys?.length ?? 0 } : null,
    primaries: { lift: n.lift, gamma: n.gamma, gain: n.gain, offset: n.offset, contrast: n.contrast, pivot: n.pivot, sat: n.sat, hue: n.hue, temp: n.temp, tint: n.tint },
  }));
}

/** Picture clips only (the ones that can be graded). */
export function isPicture(p: Project, c: Clip): boolean {
  return !(c.kind === "media" && findAsset(p, c.assetId)?.kind === "audio");
}
