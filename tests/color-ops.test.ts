import { describe, expect, it } from "vitest";
import * as cops from "../src/color/ops";
import { MAX_NODES } from "../src/color/types";
import { newClip, newProject, newTrack, type Project } from "../src/core/model";

function project(): { p: Project; a: string; b: string; sound: string } {
  const p = newProject("t", 1920, 1080, 24);
  const v = newTrack("video", "V1");
  const au = newTrack("audio", "A1");
  p.assets.push({ id: "vid", kind: "video", name: "v.mp4", mime: "video/mp4", size: 1, duration: 10, width: 1920, height: 1080, ref: "file:/v.mp4" });
  p.assets.push({ id: "snd", kind: "audio", name: "s.mp3", mime: "audio/mpeg", size: 1, duration: 10, width: 0, height: 0, ref: "file:/s.mp3" });
  const a = newClip("media", 0, 2, { assetId: "vid" });
  const b = newClip("media", 2, 2, { assetId: "vid" });
  const s = newClip("media", 0, 4, { assetId: "snd" });
  v.clips.push(a, b);
  au.clips.push(s);
  p.tracks = [v, au];
  return { p, a: a.id, b: b.id, sound: s.id };
}

describe("colour page operations", () => {
  it("adds, finds, changes, reorders and removes nodes", () => {
    const { p, a } = project();
    const n1 = cops.addNode(p, a, {});
    const n2 = cops.addNode(p, a, { name: "Skin", kind: "layer", blend: "screen", gain: [0, 0, 0, 0.2] });
    const clip = p.tracks[0]!.clips[0]!;
    expect(clip.nodes!.map((n) => n.name)).toEqual(["Node 1", "Skin"]);
    expect(cops.findNode(clip, 2).node.id).toBe(n2.id);
    expect(cops.findNode(clip, "skin").node.id).toBe(n2.id);
    expect(cops.findNode(clip, n1.id).index).toBe(0);
    expect(() => cops.findNode(clip, "nope")).toThrow(/No node/);
    const set = cops.setNode(p, a, "Skin", { sat: 9, hue: 10 });
    expect(set.sat).toBe(3); // clamped
    expect(set.gain[3]).toBeCloseTo(0.2, 9); // untouched fields stay
    expect(set.kind).toBe("layer");
    const mid = cops.addNode(p, a, { name: "Mid" }, 1);
    expect(clip.nodes!.map((n) => n.name)).toEqual(["Node 1", "Mid", "Skin"]);
    cops.moveNode(p, a, mid.id, 3);
    expect(clip.nodes!.map((n) => n.name)).toEqual(["Node 1", "Skin", "Mid"]);
    expect(cops.removeNode(p, a, "Skin")).toBe(2);
    cops.removeNode(p, a, 1);
    cops.removeNode(p, a, 1);
    expect(clip.nodes).toBeUndefined();
  });
  it("refuses sound clips and more than the node limit", () => {
    const { p, a, sound } = project();
    expect(() => cops.addNode(p, sound, {})).toThrow(/picture/);
    for (let i = 0; i < MAX_NODES; i++) cops.addNode(p, a, {});
    expect(() => cops.addNode(p, a, {})).toThrow(/8 nodes/);
  });
  it("nodeOrNew creates the first node and otherwise picks the last or the named one", () => {
    const { p, a } = project();
    const first = cops.nodeOrNew(p, a);
    expect(p.tracks[0]!.clips[0]!.nodes!.length).toBe(1);
    const second = cops.addNode(p, a, { name: "B" });
    expect(cops.nodeOrNew(p, a).id).toBe(second.id);
    expect(cops.nodeOrNew(p, a, 1).id).toBe(first.id);
  });
  it("curves: set, validate, clear, presets", () => {
    const { p, a } = project();
    const n = cops.addNode(p, a, {});
    cops.setCurve(p, a, n.id, "m", cops.CURVE_PRESETS["s-curve"]!(1));
    expect(p.tracks[0]!.clips[0]!.nodes![0]!.curves!.m!.length).toBe(4);
    expect(() => cops.setCurve(p, a, n.id, "m", [[0.5, 0.5]])).toThrow(/two points/);
    expect(() => cops.setCurve(p, a, n.id, "zz" as never, [[0, 0], [1, 1]])).toThrow(/Unknown curve/);
    cops.setCurve(p, a, n.id, "hs", [[0, 0.5], [0.5, 0.9], [1, 0.5]]);
    expect(Object.keys(p.tracks[0]!.clips[0]!.nodes![0]!.curves!)).toEqual(["m", "hs"]);
    cops.setCurve(p, a, n.id, "m", [[0, 0], [1, 1]]); // identity clears it
    cops.setCurve(p, a, n.id, "hs", null);
    expect(p.tracks[0]!.clips[0]!.nodes![0]!.curves).toBeUndefined();
    for (const [name, f] of Object.entries(cops.CURVE_PRESETS)) for (const pt of f(1)) expect(pt.every((v) => v >= 0 && v <= 1), name).toBe(true);
  });
  it("qualifier from a colour and from presets; window with keys", () => {
    const { p, a } = project();
    const n = cops.addNode(p, a, {});
    const q = cops.qualifierFromColor([0.9, 0.2, 0.1]);
    expect(q.on).toBe(true);
    expect(q.h).toBeLessThan(0.05);
    expect(q.hw).toBeLessThan(0.5);
    const grey = cops.qualifierFromColor([0.5, 0.5, 0.5]);
    expect(grey.hw).toBe(0.5); // hue ignored for greys
    expect(grey.s1).toBeLessThan(0.3);
    cops.setQualifier(p, a, n.id, cops.QUAL_PRESETS.sky!);
    expect(p.tracks[0]!.clips[0]!.nodes![0]!.qual!.h).toBeCloseTo(0.59, 9);
    cops.setQualifier(p, a, n.id, null);
    expect(p.tracks[0]!.clips[0]!.nodes![0]!.qual).toBeUndefined();
    cops.setWindow(p, a, n.id, { shape: "rect", x: 0.3, w: 0.2, keys: [{ t: 1, x: 0.6, y: 0.5, w: 0.2, h: 0.5, rot: 0 }, { t: 0, x: 0.3, y: 0.5, w: 0.2, h: 0.5, rot: 0 }] });
    const w = p.tracks[0]!.clips[0]!.nodes![0]!.win!;
    expect(w.shape).toBe("rect");
    expect(w.keys!.map((k) => k.t)).toEqual([0, 1]); // sorted
    cops.setWindow(p, a, n.id, { soft: 0.4 });
    expect(p.tracks[0]!.clips[0]!.nodes![0]!.win!.keys!.length).toBe(2); // keys kept when not mentioned
    cops.setWindow(p, a, n.id, null);
    expect(p.tracks[0]!.clips[0]!.nodes![0]!.win).toBeUndefined();
  });
  it("copies a grade with fresh node ids and prunes unused LUTs", () => {
    const { p, a, b, sound } = project();
    const lut = cops.addProjectLut(p, { name: "L", source: "data", size: 2, data: "AAAA", auto: true });
    expect(cops.addProjectLut(p, { name: "L", source: "data", size: 2, data: "AAAA", auto: true }).id).toBe(lut.id); // same LUT is reused
    const imported = cops.addProjectLut(p, { name: "Mine", source: "data", size: 2, data: "BBBB" });
    const n = cops.addNode(p, a, { lut: { id: lut.id, mix: 1 }, sat: 1.3 });
    p.tracks[0]!.clips[0]!.grade = { exposure: 0.5 };
    expect(cops.copyGrade(p, a, [a, b, sound])).toBe(1); // itself and the sound clip are skipped
    const copy = p.tracks[0]!.clips[1]!;
    expect(copy.nodes![0]!.sat).toBeCloseTo(1.3, 9);
    expect(copy.nodes![0]!.id).not.toBe(n.id);
    expect(copy.grade).toEqual({ exposure: 0.5 });
    expect(cops.pruneLuts(p)).toBe(0);
    cops.removeNode(p, a, 1);
    cops.removeNode(p, b, 1);
    expect(cops.pruneLuts(p)).toBe(1); // the tool-made one goes…
    expect(p.luts!.map((l) => l.id)).toEqual([imported.id]); // …a LUT the person imported stays, used or not
    expect(cops.describeNodes(p, copy)).toEqual([]);
  });
});
