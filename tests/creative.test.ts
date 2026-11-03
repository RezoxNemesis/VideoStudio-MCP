import { describe, expect, it } from "vitest";
import { covers, headPoint, planShots, safeFraming, SHOTS, doodleEls, DOODLES } from "../src/app/creative";

const W = 1080,
  H = 1920;

describe("auto camera framing", () => {
  it("knows when a zoomed, offset or tilted frame would show black edges", () => {
    expect(covers({ s: 1, x: 0, y: 0, r: 0 }, W, H)).toBe(true);
    expect(covers({ s: 1, x: 10, y: 0, r: 0 }, W, H)).toBe(false);
    expect(covers({ s: 1.2, x: 100, y: 190, r: 0 }, W, H)).toBe(true);
    expect(covers({ s: 1.02, x: 0, y: 0, r: 3 }, W, H)).toBe(false);
  });
  it("repairs any framing so the frame is always covered", () => {
    for (const f of [
      { s: 1.3, x: 400, y: 600, r: 0 },
      { s: 1.05, x: 0, y: 0, r: 4 },
      { s: 1.45, x: -300, y: 500, r: -4 },
      { s: 1.0, x: 50, y: -80, r: 1.5 },
    ]) {
      const g = safeFraming(f, W, H);
      expect(covers(g, W, H)).toBe(true);
      expect(g.r).toBe(f.r);
    }
  });
  it("plans shots between beats, every shot covered, faces placed where the shot wants them", () => {
    const cuts = [1.3, 2.6, 3.9, 5.2, 6.5, 7.8];
    const shots = planShots({ W, H, cuts, start: 0, end: 9, head: () => ({ x: 0.5, y: 0.2 }) });
    expect(shots.length).toBe(7);
    expect(shots[0]!.t0).toBe(0);
    expect(shots[shots.length - 1]!.t1).toBe(9);
    for (const sh of shots) {
      expect(covers(sh.a, W, H)).toBe(true);
      expect(covers(sh.b, W, H)).toBe(true);
    }
    // A close-up puts the face around a third of the way down, not at the very top.
    const close = shots.find((s) => s.kind === "close")!;
    const faceY = 0.5 + ((0.2 - 0.5) * H * close.a.s + close.a.y) / H;
    expect(faceY).toBeGreaterThan(0.2);
    expect(faceY).toBeLessThan(0.45);
    expect(SHOTS.ecu.s).toBeGreaterThan(SHOTS.close.s);
  });
});

describe("head finder", () => {
  it("finds the top-centre of a silhouette", () => {
    const w = 100,
      h = 200;
    const a = new Uint8Array(w * h);
    for (let y = 40; y < 190; y++) for (let x = 60; x < 80; x++) a[y * w + x] = 255;
    const hp = headPoint(a, w, h);
    expect(hp.found).toBe(true);
    expect(hp.x).toBeGreaterThan(0.6);
    expect(hp.x).toBeLessThan(0.8);
    expect(hp.y).toBeGreaterThan(0.2);
    expect(hp.y).toBeLessThan(0.3);
  });
});

describe("doodles", () => {
  it("every doodle produces drawable elements inside its time window", () => {
    for (const d of DOODLES) {
      const els = doodleEls(d, { x: 0.5, y: 0.4, size: 0.4, t0: 0, t1: 2, color: "#fff", aspect: W / H });
      expect(els.length).toBeGreaterThan(0);
      for (const e of els) {
        expect(e.t0).toBeGreaterThanOrEqual(0);
        expect(e.t1).toBe(2);
      }
    }
  });
});
