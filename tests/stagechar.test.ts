import { describe, it, expect } from "vitest";
import { makeChar, garmentsOf } from "../src/stage/presets";

// A face detail is a sculpted shape with a weight from 0 to 1. A negative weight is not "the other way": it pushes
// the shape backwards through the head (the lining of the mouth came out through the jaw of a hero slimmed that way).
describe("a character's face details", () => {
  it("a detail turned the wrong way is refused, with the name of the detail that does go that way", () => {
    expect(() => makeChar({ preset: "swordsman", detail: { "chin/chin-bones-incr": -0.6 } })).toThrow(/chin\/chin-bones-decr.*0\.6/);
    expect(() => makeChar({ preset: "swordsman", detail: { "nose/nose-point-up": -0.2 } })).toThrow(/nose\/nose-point-down/);
    expect(() => makeChar({ body: "man", detail: { "head/head-trans-forward": -0.1 } })).toThrow(/head\/head-trans-backward/);
  });
  it("a detail with no opposite says what a weight can be", () => {
    expect(() => makeChar({ preset: "swordsman", detail: { "head/head-oval": -0.3 } })).toThrow(/0 to 1/);
  });
  it("0 takes a preset's own detail away", () => {
    const { char } = makeChar({ preset: "swordsman", detail: { "chin/chin-bones-incr": 0, "chin/chin-bones-decr": 0.35 } });
    expect(char.detail?.["chin/chin-bones-incr"]).toBe(0);
    expect(char.detail?.["chin/chin-bones-decr"]).toBe(0.35);
  });
});

// A film can cut a character's clothes itself: the pieces of an outfit, each with its own measures.
describe("a character's own garments", () => {
  const cut = [{ kind: "trousers", name: "trousers", wide0: 1.12, wide1: 1.4 }, { kind: "tunic", name: "tunic", flare: 1.3, bag: 0.2 }];
  it("a list of pieces is taken as it is", () => {
    expect(garmentsOf(cut)).toEqual(cut);
    expect(makeChar({ preset: "swordsman", garments: garmentsOf(cut) }).char.garments).toEqual(cut);
  });
  it("what is no list of pieces is refused, saying which kinds there are", () => {
    expect(() => garmentsOf("tunic")).toThrow(/list/);
    expect(() => garmentsOf([{ kind: "cape" }])).toThrow(/tunic.*trousers|trousers.*tunic/);
    expect(() => garmentsOf([{ name: "tunic" }])).toThrow(/kind/);
  });
});
