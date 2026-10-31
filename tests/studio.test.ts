import { describe, expect, it } from "vitest";
import { ease, valueAt, setKey, removeKey } from "../src/core/keyframes";
import { newProject, sourceTime, exposedKey, newScene, type Asset, type Project } from "../src/core/model";
import * as ops from "../src/core/ops";
import { evaluate, clipGain, evaluateClip } from "../src/core/evaluate";
import { History } from "../src/core/history";
import * as anim from "../src/anim/ops";

function withVideo(p: Project, duration = 10): Asset {
  const a: Asset = { id: "ast_v", kind: "video", name: "clip.mp4", mime: "video/mp4", size: 1, duration, width: 1920, height: 1080, ref: "idb:x" };
  p.assets.push(a);
  return a;
}

describe("keyframes", () => {
  it("eases stay in range at the ends", () => {
    for (const k of ["linear", "in", "out", "inOut", "back", "elastic", "bounce"] as const) {
      expect(ease(k, 0)).toBeCloseTo(0, 5);
      expect(ease(k, 1)).toBeCloseTo(1, 5);
    }
    expect(ease("hold", 0.99)).toBe(0);
  });
  it("interpolates and clamps", () => {
    const keys = setKey(setKey(undefined, 0, 0, "linear"), 2, 10, "linear");
    expect(valueAt(keys, -1, 5)).toBe(0);
    expect(valueAt(keys, 1, 5)).toBeCloseTo(5);
    expect(valueAt(keys, 3, 5)).toBe(10);
    expect(valueAt(undefined, 1, 7)).toBe(7);
    expect(valueAt(3, 1, 7)).toBe(3);
  });
  it("adding a key to a constant keeps the old value at 0", () => {
    const keys = setKey(0.5, 2, 1, "linear");
    expect(keys).toHaveLength(2);
    expect(valueAt(keys, 1, 0)).toBeCloseTo(0.75);
    expect(removeKey(keys, 2)).toBe(0.5);
  });
});

describe("timeline ops", () => {
  it("adds media clips at the asset's length and stacks onto free tracks", () => {
    const p = newProject();
    const a = withVideo(p, 8);
    const c1 = ops.addClip(p, { assetId: a.id, start: 0 });
    expect(c1.dur).toBe(8);
    const c2 = ops.addClip(p, { assetId: a.id, start: 2 });
    const t1 = p.tracks.find((t) => t.clips.includes(c1))!;
    const t2 = p.tracks.find((t) => t.clips.includes(c2))!;
    expect(t1).not.toBe(t2);
  });
  it("split keeps the source continuous and keyframes in place", () => {
    const p = newProject();
    const a = withVideo(p, 10);
    const c = ops.addClip(p, { assetId: a.id, start: 1 });
    ops.keyframe(p, c.id, "opacity", 1, 0, "linear");
    ops.keyframe(p, c.id, "opacity", 5, 1, "linear");
    const right = ops.splitClip(p, c.id, 4);
    expect(c.dur).toBeCloseTo(3);
    expect(right.start).toBe(4);
    expect(right.in).toBeCloseTo(3);
    expect(sourceTime(right, 4)).toBeCloseTo(3);
    // Opacity at t=4.5 is the same whether we look at the original curve or the right half.
    const op = evaluateClip(p, right, 4.5).opacity;
    expect(op).toBeCloseTo(0.875, 2);
  });
  it("trim respects the source length and neighbours", () => {
    const p = newProject();
    const a = withVideo(p, 5);
    const c = ops.addClip(p, { assetId: a.id, start: 0 });
    ops.trimClip(p, c.id, "end", 20);
    expect(c.dur).toBeCloseTo(5);
    ops.trimClip(p, c.id, "start", 1);
    expect(c.start).toBeCloseTo(1);
    expect(c.in).toBeCloseTo(1);
    expect(c.dur).toBeCloseTo(4);
  });
  it("speed keeps the source range", () => {
    const p = newProject();
    const a = withVideo(p, 6);
    const c = ops.addClip(p, { assetId: a.id, start: 0 });
    ops.setSpeed(p, c.id, 2);
    expect(c.dur).toBeCloseTo(3);
    expect(sourceTime(c, 3)).toBeCloseTo(6);
  });
  it("ripple delete closes the gap", () => {
    const p = newProject();
    const a = withVideo(p, 2);
    const t = p.tracks.find((x) => x.name === "V1")!.id;
    const c1 = ops.addClip(p, { assetId: a.id, start: 0, trackId: t });
    const c2 = ops.addClip(p, { assetId: a.id, start: 2, trackId: t });
    ops.deleteClip(p, c1.id, true);
    expect(c2.start).toBe(0);
  });
  it("evaluate puts the top track last and applies fades", () => {
    const p = newProject();
    const bottom = ops.addClip(p, { kind: "color", start: 0, dur: 4 });
    const top = ops.addClip(p, { text: "Hi", start: 0, dur: 4 });
    top.transitionIn = { kind: "fade", dur: 1 };
    const layers = evaluate(p, 0.5);
    expect(layers.map((l) => l.clipId)).toEqual([bottom.id, top.id]);
    expect(layers[1]!.opacity).toBeGreaterThan(0);
    expect(layers[1]!.opacity).toBeLessThan(1);
    const c = ops.getClip(p, bottom.id);
    c.fadeOut = 1;
    expect(clipGain(c, 3.5)).toBeCloseTo(0.5);
  });
});

describe("history", () => {
  it("undoes and redoes", () => {
    const h = new History<{ n: number }>(10, 0);
    let s = { n: 1 };
    h.push(s, "a");
    s = { n: 2 };
    const u = h.undo(s)!;
    expect(u.state.n).toBe(1);
    const r = h.redo(u.state)!;
    expect(r.state.n).toBe(2);
  });
});

describe("animation", () => {
  it("holds drawings until the next key and copies on edit", () => {
    const s = newScene();
    const l = s.layers[0]!;
    anim.addStroke(s, l.id, 0, { tool: "ink", color: "#000000", size: 4, opacity: 1, pts: [0, 0, 1, 10, 10, 1] });
    expect(exposedKey(l, 5)).toBe("0");
    anim.addStroke(s, l.id, 5, { tool: "ink", color: "#000000", size: 4, opacity: 1, pts: [5, 5, 1, 20, 20, 1] });
    expect(l.cels["5"]!.strokes).toHaveLength(2);
    expect(l.cels["0"]!.strokes).toHaveLength(1);
  });
  it("inbetweens pair strokes and resample", () => {
    const s = newScene();
    const l = s.layers[0]!;
    anim.addStroke(s, l.id, 0, { tool: "ink", color: "#000000", size: 4, opacity: 1, pts: [0, 0, 1, 100, 0, 1] });
    l.cels["4"] = { strokes: [{ id: "b", tool: "ink", color: "#000000", size: 4, opacity: 1, pts: [0, 100, 1, 100, 100, 1] }], fills: [] };
    const made = anim.inbetween(s, l.id, 0, 4, "even");
    expect(made).toBe(3);
    const mid = l.cels["2"]!.strokes[0]!;
    expect(mid.pts[1]).toBeCloseTo(50);
  });
  it("insert and delete frames shift keys", () => {
    const s = newScene();
    const l = s.layers[0]!;
    l.cels["3"] = { strokes: [], fills: [] };
    anim.insertFrames(s, 2, 2);
    expect(Object.keys(l.cels)).toContain("5");
    anim.deleteFrames(s, 2, 2);
    expect(Object.keys(l.cels)).toContain("3");
  });
  it("lip sync writes mouth keys from an envelope", () => {
    const s = newScene();
    const l = s.layers[0]!;
    l.cels["0"] = { strokes: [{ id: "c", tool: "ink", color: "#000", size: 2, opacity: 1, pts: [0, 0, 1] }], fills: [] };
    l.cels["1"] = { strokes: [{ id: "o", tool: "ink", color: "#000", size: 9, opacity: 1, pts: [0, 0, 1] }], fills: [] };
    const env = [0, 0, 0.9, 0.9, 0.9, 0.9, 0, 0, 0, 0, 0.8, 0.8];
    const n = anim.lipSync(s, l.id, env, [0, 1], 30);
    expect(n).toBeGreaterThan(1);
  });
});
