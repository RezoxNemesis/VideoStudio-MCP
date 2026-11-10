import { describe, expect, it } from "vitest";
import { History } from "../src/core/history";
import { newClip, newProject, newTrack, type Project } from "../src/core/model";
import * as ops from "../src/core/ops";
import * as cops from "../src/color/ops";

function project(): { p: Project; id: string } {
  const p = newProject("t", 1920, 1080, 24);
  const v = newTrack("video", "V1");
  p.assets.push({ id: "vid", kind: "video", name: "v.mp4", mime: "video/mp4", size: 1, duration: 20, width: 1920, height: 1080, ref: "file:/v.mp4" });
  const c = newClip("media", 0, 10, { assetId: "vid" });
  v.clips.push(c);
  p.tracks = [v];
  return { p, id: c.id };
}

describe("grading nodes survive ordinary editing", () => {
  it("clip.set with raw nodes is cleaned, never stored half-formed", () => {
    const { p, id } = project();
    ops.patchClip(p, id, { nodes: [{ id: "a", on: true, lift: [0.1, 0, 0, 0] }, "rubbish", null] });
    const nodes = p.tracks[0]!.clips[0]!.nodes!;
    expect(nodes.length).toBe(1);
    expect(nodes[0]!.gamma).toEqual([0, 0, 0, 0]);
    expect(nodes[0]!.offset).toEqual([0, 0, 0]);
    expect(nodes[0]!.contrast).toBe(1);
    expect(() => ops.patchClip(p, id, { nodes: "x" })).toThrow(/list of grading nodes/);
    ops.patchClip(p, id, { nodes: null });
    expect(p.tracks[0]!.clips[0]!.nodes).toBeUndefined();
    expect(ops.cleanNodes([{ lut: { id: "l", mix: 1 } }])![0]!.id).toMatch(/^nod_/); // a missing id is given one
  });
  it("window keys stay with the picture when the clip is split or trimmed at the head", () => {
    const { p, id } = project();
    const n = cops.addNode(p, id, {});
    cops.setWindow(p, id, n.id, { shape: "ellipse", keys: [{ t: 2, x: 0.2, y: 0.5, w: 0.3, h: 0.3, rot: 0 }, { t: 8, x: 0.8, y: 0.5, w: 0.3, h: 0.3, rot: 0 }] });
    const right = ops.splitClip(p, id, 4);
    const left = p.tracks[0]!.clips.find((c) => c.id === id)!;
    expect(left.nodes![0]!.win!.keys!.map((k) => k.t)).toEqual([2, 8]);
    expect(right.nodes![0]!.win!.keys!.map((k) => k.t)).toEqual([-2, 4]); // the same moments, counted from the new start
    ops.trimClip(p, right.id, "start", 5);
    expect(p.tracks[0]!.clips.find((c) => c.id === right.id)!.nodes![0]!.win!.keys!.map((k) => k.t)).toEqual([-3, 3]);
  });
  it("a clip takes at most four different LUTs", () => {
    const { p, id } = project();
    for (let i = 0; i < 4; i++) cops.addNode(p, id, { lut: { id: `builtin:l${i}`, mix: 1 } });
    expect(() => cops.addNode(p, id, { lut: { id: "builtin:l4", mix: 1 } })).toThrow(/4 different LUTs/);
    cops.addNode(p, id, { lut: { id: "builtin:l0", mix: 1 } }); // a LUT already in use is fine
    const plain = cops.addNode(p, id, {});
    expect(() => cops.setNode(p, id, plain.id, { lut: { id: "builtin:l9", mix: 1 } })).toThrow(/4 different LUTs/);
    expect(p.tracks[0]!.clips[0]!.nodes!.find((x) => x.id === plain.id)!.lut).toBeUndefined(); // nothing was changed
  });
});

describe("history groups", () => {
  it("folds a group's steps into one, and forgets them on roll-back", () => {
    const h = new History<{ v: number }>(120, 0);
    h.push({ v: 0 }, "a");
    const mark = h.count;
    h.push({ v: 1 }, "b");
    h.push({ v: 2 }, "c");
    h.collapse(mark, JSON.stringify({ v: 1 }), "group");
    expect(h.undo({ v: 3 })).toEqual({ state: { v: 1 }, label: "group" });
    expect(h.undo({ v: 1 })).toEqual({ state: { v: 0 }, label: "a" });
    expect(h.canUndo).toBe(false);
    h.push({ v: 0 }, "a");
    const m2 = h.count;
    h.push({ v: 1 }, "b");
    h.collapse(m2); // rolled back
    expect(h.undo({ v: 0 })!.label).toBe("a");
    expect(h.canUndo).toBe(false);
  });
  it("still collapses correctly after old steps fell off the end", () => {
    const h = new History<number>(3, 0);
    h.push(0, "a");
    h.push(1, "b");
    const mark = h.count;
    for (let i = 2; i < 8; i++) h.push(i, `s${i}`); // more steps than the limit holds
    h.collapse(mark, "1", "group");
    expect(h.undo(99)).toEqual({ state: 1, label: "group" });
    expect(h.canUndo).toBe(false);
  });
});
