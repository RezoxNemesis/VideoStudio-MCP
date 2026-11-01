import { describe, expect, it } from "vitest";
import * as PU from "../src/anim/puppet";
import { flattenPath } from "../src/anim/svgpath";
import { newAnimLayer, newScene } from "../src/core/model";

function character(props: string[] = ["cigarette"]) {
  const s = newScene("t", 1080, 1920, 24);
  const l = newAnimLayer("Character", "puppet");
  l.puppet = PU.makeSpec({ x: 540, y: 1780, height: 1300, props });
  l.chan = {};
  s.layers.unshift(l);
  return { s, l };
}

const dist = (a: { x: number; y: number }, b: { x: number; y: number }) => Math.hypot(a.x - b.x, a.y - b.y);

describe("puppet skeleton", () => {
  it("stands with the feet on the ground line and the head on top", () => {
    const { s, l } = character();
    const sk = PU.skeleton(l, l.puppet!, 0, s.fps);
    expect(sk.ankleL.y).toBeGreaterThan(sk.kneeL.y);
    expect(sk.kneeL.y).toBeGreaterThan(sk.pelvis.y);
    expect(sk.neck.y).toBeLessThan(sk.pelvis.y);
    expect(Math.abs(sk.ankleL.y - (1780 - 0.3 * sk.u))).toBeLessThan(0.5 * sk.u);
  });
  it("brings the cigarette filter to the lips when cigToMouth is 1", () => {
    const { s, l } = character();
    PU.applyActions(l, s.fps, [{ at: 0, do: "smoke", dur: 4.2 }]);
    // Middle of the drag.
    const f = Math.round(1.4 * s.fps);
    expect(PU.chan(l, "cigToMouth", f)).toBeCloseTo(1, 3);
    const sk = PU.skeleton(l, l.puppet!, f, s.fps);
    expect(dist(sk.filter, sk.mouth)).toBeLessThan(0.06 * sk.u);
    // At rest the cigarette is down by the hip.
    const rest = PU.skeleton(l, l.puppet!, 0, s.fps);
    expect(rest.grip.y).toBeGreaterThan(rest.pelvis.y - 0.5 * rest.u);
  });
  it("returns the hand to where it started after smoking", () => {
    const { s, l } = character();
    PU.applyActions(l, s.fps, [{ at: 0, do: "stance", name: "pocket", dur: 0.001 }, { at: 1, do: "smoke", dur: 4 }]);
    const before = PU.skeleton(l, l.puppet!, Math.round(0.9 * s.fps), s.fps);
    const after = PU.skeleton(l, l.puppet!, Math.round(5.2 * s.fps), s.fps);
    expect(dist(before.wristR, after.wristR)).toBeLessThan(0.05 * before.u);
  });
});

describe("puppet actions", () => {
  it("expressions and looks key the face channels", () => {
    const { s, l } = character([]);
    const end = PU.applyActions(l, s.fps, [
      { at: 1, do: "expr", name: "smirk" },
      { at: 2, do: "look", x: 0.6, y: -0.2 },
      { at: 3, do: "nod" },
    ]);
    expect(end).toBeGreaterThan(3);
    expect(PU.chan(l, "mouthSmirk", Math.round(1.5 * s.fps))).toBeGreaterThan(0.8);
    expect(PU.chan(l, "lookX", Math.round(2.5 * s.fps))).toBeCloseTo(0.6, 3);
    expect(PU.chan(l, "headNod", Math.round(3.7 * s.fps))).toBeCloseTo(0, 3);
  });
  it("rejects unknown actions and channels", () => {
    const { s, l } = character([]);
    expect(() => PU.applyActions(l, s.fps, [{ at: 0, do: "moonwalk" }])).toThrow(/Unknown action/);
    expect(() => PU.setChannelKey(l, "tail", 0, 1)).toThrow(/Unknown channel/);
  });
  it("lip sync opens the mouth with the voice", () => {
    const { s, l } = character([]);
    const env = Array.from({ length: 60 }, (_, i) => (i % 10 < 5 ? 0.9 : 0));
    PU.puppetLipSync(l, s.fps, env, 1);
    const vals = Array.from({ length: 48 }, (_, i) => PU.chan(l, "mouthOpen", 24 + i));
    expect(Math.max(...vals)).toBeGreaterThan(0.5);
    expect(PU.chan(l, "mouthOpen", 24 + 60)).toBeCloseTo(0, 3);
  });
});

describe("svg paths", () => {
  it("flattens lines, curves, arcs and closes subpaths", () => {
    const polys = flattenPath("M10 10 L110 10 Q110 60 60 110 C40 110 10 80 10 60 Z M200 200 a50 50 0 1 0 100 0 z");
    expect(polys.length).toBe(2);
    const first = polys[0]!;
    expect(first[0]).toBe(10);
    expect(first[first.length - 2]).toBe(10);
    expect(first[first.length - 1]).toBe(10);
    // The arc reaches below its chord.
    const ys = polys[1]!.filter((_, i) => i % 2 === 1);
    expect(Math.max(...ys)).toBeGreaterThan(240);
  });
});
