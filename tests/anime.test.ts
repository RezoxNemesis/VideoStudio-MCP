import { describe, expect, it } from "vitest";
import { auraAt, cameraAt, flashAt, heldFrame, layerFrame, trailFrames } from "../src/anim/anime";
import { addBeat, addMove, addSceneFx } from "../src/agent/anime";
import { FX_TYPES } from "../src/anim/proc";
import { newAnimLayer, type AnimScene } from "../src/core/model";

const scene = (frames = 120, fps = 24): AnimScene => ({ id: "s", name: "s", width: 1280, height: 720, fps, frames, background: "#000", layers: [newAnimLayer("hero", "puppet")], palette: [] });

describe("anime timing", () => {
  it("freezes the drawing inside a hit-stop and carries on after it", () => {
    const a = { holds: [[10, 4]] as [number, number][] };
    expect(heldFrame(a, 9)).toBe(9);
    expect([10, 11, 12, 13].map((f) => heldFrame(a, f))).toEqual([10, 10, 10, 10]);
    expect(heldFrame(a, 14)).toBe(14);
    expect(heldFrame(undefined, 7)).toBe(7);
  });
  it("lets effects run through a hit-stop while drawings wait", () => {
    const a = { holds: [[10, 4]] as [number, number][] };
    expect(layerFrame({ kind: "puppet" }, 12, a)).toBe(10);
    expect(layerFrame({ kind: "proc" }, 12, a)).toBe(12);
  });
  it("holds poses on twos and threes", () => {
    expect([0, 1, 2, 3, 4, 5].map((f) => layerFrame({ kind: "puppet", anime: { step: 2 } }, f, undefined))).toEqual([0, 0, 2, 2, 4, 4]);
    expect([0, 1, 2, 3, 4, 5, 6].map((f) => layerFrame({ kind: "draw", anime: { step: 3 } }, f, undefined))).toEqual([0, 0, 0, 3, 3, 3, 6]);
    // a layer that starts late steps from its own first frame
    expect([5, 6, 7, 8].map((f) => layerFrame({ kind: "puppet", anime: { step: 2 }, span: [5, 40] }, f, undefined))).toEqual([5, 5, 7, 7]);
  });
  it("trails afterimages, oldest and faintest first, only inside their window", () => {
    const l = { anime: { trail: { count: 3, gap: 2, opacity: 0.6 } } };
    const tr = trailFrames(l, 20);
    expect(tr.map((x) => x.frame)).toEqual([14, 16, 18]);
    expect(tr[0]!.alpha).toBeLessThan(tr[2]!.alpha);
    expect(tr[2]!.alpha).toBeCloseTo(0.6, 5);
    expect(trailFrames(l, 3).map((x) => x.frame)).toEqual([1]); // nothing before the scene starts
    const w = { anime: { trail: { count: 2, gap: 1, when: [[30, 40]] as [number, number][] } } };
    expect(trailFrames(w, 20)).toEqual([]);
    expect(trailFrames(w, 35)).toHaveLength(2);
    expect(trailFrames({}, 10)).toEqual([]);
  });
  it("flickers the aura and switches it off outside its window", () => {
    const l = { anime: { aura: { color: "#ff0", strength: 0.8, flicker: 0.4, when: [[10, 20]] as [number, number][] } } };
    expect(auraAt(l, 5)).toBeNull();
    const vals = [10, 12, 14, 16, 18].map((f) => auraAt(l, f)!.strength);
    for (const v of vals) {
      expect(v).toBeGreaterThan(0.8 * 0.6 - 1e-9);
      expect(v).toBeLessThanOrEqual(0.8);
    }
    expect(new Set(vals.map((v) => v.toFixed(3))).size).toBeGreaterThan(1);
    expect(auraAt(l, 10)!.strength).toBe(auraAt(l, 11)!.strength); // on twos
  });
});

describe("anime camera", () => {
  it("shakes hard at the hit and settles, never showing the frame edge", () => {
    const a = { shakes: [[10, 12, 1]] as [number, number, number][] };
    expect(cameraAt(a, 9, 1280, 720)).toBeNull();
    expect(cameraAt(a, 22, 1280, 720)).toBeNull();
    let early = 0, late = 0;
    for (let f = 10; f < 22; f++) {
      const c = cameraAt(a, f, 1280, 720)!;
      const d = Math.hypot(c.dx, c.dy);
      if (f < 14) early = Math.max(early, d);
      if (f >= 18) late = Math.max(late, d);
      // the push-in covers the offset on every side
      const cover = ((c.zoom - 1) * 720) / 2;
      expect(Math.abs(c.dx)).toBeLessThanOrEqual(cover + 1e-6);
      expect(Math.abs(c.dy)).toBeLessThanOrEqual(cover + 1e-6);
    }
    expect(early).toBeGreaterThan(late);
    expect(early).toBeGreaterThan(2);
  });
  it("punches in fast and eases back", () => {
    const a = { punches: [[0, 10, 0.2, 0.25, 0.75]] as [number, number, number, number?, number?][] };
    const z = Array.from({ length: 10 }, (_, f) => cameraAt(a, f, 1000, 500)!.zoom);
    expect(z[0]).toBeCloseTo(1, 5);
    expect(Math.max(...z)).toBeCloseTo(1.2, 1);
    expect(z.indexOf(Math.max(...z))).toBeLessThanOrEqual(2);
    expect(z[9]).toBeLessThan(1.02);
    expect(cameraAt(a, 3, 1000, 500)!.cx).toBe(250);
    expect(cameraAt(a, 3, 1000, 500)!.cy).toBe(375);
  });
  it("flashes white and fades, or inverts for a frame or two", () => {
    const a = { flashes: [[5, 4, "white"], [20, 2, "invert"]] as [number, number, string][] };
    expect(flashAt(a, 4)).toBeNull();
    expect(flashAt(a, 5)).toEqual({ kind: "white", alpha: 1 });
    expect(flashAt(a, 7)!.alpha).toBeLessThan(0.5);
    expect(flashAt(a, 9)).toBeNull();
    expect(flashAt(a, 21)).toEqual({ kind: "invert", alpha: 1 });
  });
});

describe("anime commands", () => {
  it("places an effect inside a scene for a stretch of time, behind a character", () => {
    const sc = scene();
    const hero = sc.layers[0]!;
    const l = addSceneFx(sc, "rushlines", 1, 0.5, { behind: hero.id, rot: 20 });
    expect(l.span).toEqual([24, 36]);
    expect(l.proc).toMatchObject({ type: "fx", style: "rushlines", rot: 20 });
    // layers[0] is the top: the effect sits right under the hero
    expect(sc.layers.map((x) => x.id)).toEqual([hero.id, l.id]);
    const top = addSceneFx(sc, "focuslines", 1, 0.5);
    expect(sc.layers[0]!.id).toBe(top.id);
    expect(addSceneFx(sc, "beam", 1, 1).blend).toBe("screen");
    expect(() => addSceneFx(sc, "nope", 0, 1)).toThrow(/Unknown effect/);
    expect(() => addSceneFx(sc, "dust", 99, 1)).toThrow(/after its end/);
    for (const t of ["focuslines", "rushlines", "actionbg", "lightning", "beam", "aura"]) expect(FX_TYPES as readonly string[]).toContain(t);
  });
  it("adds an impact beat: hit-stop, shake, punch and flash at one frame", () => {
    const sc = scene();
    addBeat(sc, 2, { x: 0.3, y: 0.6 });
    expect(sc.anime!.holds).toEqual([[48, 3]]);
    expect(sc.anime!.shakes![0]![0]).toBe(48);
    expect(sc.anime!.punches![0]).toEqual([48, 9, 0.1, 0.3, 0.6]);
    expect(sc.anime!.flashes![0]).toEqual([48, 3, "white"]);
    addBeat(sc, 1, { hold: 0, shake: 0, punch: 0, flash: "invert" });
    expect(sc.anime!.holds).toHaveLength(1);
    expect(sc.anime!.flashes!.map((f) => f[0])).toEqual([24, 48]); // kept in time order
    // counts are given at 24 fps and follow the scene's rate
    const s60 = scene(300, 60);
    addBeat(s60, 1, { hold: 4 });
    expect(s60.anime!.holds).toEqual([[60, 10]]);
  });
  it("stacks whole action moves", () => {
    for (const kind of ["impact", "dash", "powerup", "beam", "clash", "slash"]) {
      const sc = scene();
      const hero = sc.layers[0]!;
      const r = addMove(sc, kind, 1, { x: 0.5, y: 0.5, layerId: hero.id });
      expect(r.layers.length, kind).toBeGreaterThan(0);
      expect(r.note.length, kind).toBeGreaterThan(10);
      for (const l of sc.layers) if (l.id !== hero.id) expect(l.span, kind).toBeTruthy();
    }
    const sc = scene();
    const hero = sc.layers[0]!;
    addMove(sc, "dash", 1, { layerId: hero.id, dur: 0.5 });
    expect(hero.anime!.trail!.when).toEqual([[24, 36]]);
    // the streaks are behind the character
    const rush = sc.layers.find((l) => l.proc?.style === "rushlines")!;
    expect(sc.layers.indexOf(rush)).toBeGreaterThan(sc.layers.indexOf(hero));
    addMove(sc, "powerup", 2, { layerId: hero.id, dur: 1 });
    expect(hero.anime!.aura!.when).toEqual([[48, 72]]);
    expect(hero.anime!.trail).toBeTruthy(); // the dash's afterimages are kept
    expect(() => addMove(sc, "dance", 1, {})).toThrow(/kind must be one of/);
  });
});
