import { describe, expect, it } from "vitest";
import { Meter, WorkBoard, fmtLeft, countLine } from "../src/app/progress";

/** A clock the test moves by hand. */
function clock() {
  let t = 1_000_000;
  return { now: () => t, pass: (sec: number) => { t += sec * 1000; } };
}

describe("time left, said the way a person would", () => {
  it("is vague when little is left and rounder the more is left", () => {
    expect(fmtLeft(3)).toBe("a few seconds left");
    expect(fmtLeft(42)).toBe("about 40 s left");
    expect(fmtLeft(95)).toBe("about 1 min 40 s left");
    expect(fmtLeft(60 * 4)).toBe("about 4 min left");
    expect(fmtLeft(60 * 23 + 20)).toBe("about 23 min left");
    expect(fmtLeft(3600 + 60 * 5 + 10)).toBe("about 1 h 05 min left");
  });
  it("says nothing when it does not know", () => {
    expect(fmtLeft(null)).toBe("");
    expect(fmtLeft(NaN)).toBe("");
  });
});

describe("one line for any counted work", () => {
  it("gives the percentage, the counts, the speed and the time left", () => {
    expect(countLine({ label: "Rendering", done: 120, total: 900, unit: "frames", perSec: 24, leftSec: 32.5 })).toBe("Rendering · 13% · 120 / 900 frames · 24.0 frames/s · about 35 s left");
  });
  it("puts thousands apart and leaves out what is not known", () => {
    expect(countLine({ label: "Drawing", done: 1034, total: 12460, unit: "frames" })).toBe("Drawing · 8% · 1,034 / 12,460 frames");
    expect(countLine({ label: "Mixing the sound", done: 0, total: 0 })).toBe("Mixing the sound");
  });
  it("never says 100% before the work is done", () => {
    expect(countLine({ label: "Rendering", done: 899, total: 900 })).toContain("99%");
    expect(countLine({ label: "Rendering", done: 900, total: 900 })).toContain("100%");
  });
});

describe("a meter over several stages of work", () => {
  const plan = [
    { id: "voice", label: "Speaking the lines", units: 4, unit: "lines", per: 10 },
    { id: "motion", label: "Solving the performance", units: 0, per: 60 },
    { id: "draw", label: "Drawing the shots", units: 100, unit: "frames", per: 0.5 },
  ];
  it("weighs the stages by what they cost, so the whole has one percentage", () => {
    const c = clock(), m = new Meter(c.now);
    m.plan(plan);
    m.begin("voice");
    m.set("voice", 2);
    const v = m.view();
    expect(v.percent).toBe(22); // 20 of 90 seconds' worth
    expect(v.stagePercent).toBe(50);
    expect([v.done, v.total, v.unit]).toEqual([2, 4, "lines"]);
  });
  it("counts only the stages that have work as steps", () => {
    const m = new Meter(clock().now);
    m.plan(plan);
    m.begin("voice");
    expect([m.view().step, m.view().steps]).toEqual([1, 2]);
    m.end("voice");
    m.begin("draw");
    expect([m.view().step, m.view().steps]).toEqual([2, 2]);
    expect(m.view().label).toBe("Drawing the shots");
  });
  it("believes the clock over the guess: the time left follows the speed it measures", () => {
    const c = clock(), m = new Meter(c.now);
    m.plan(plan);
    m.begin("voice");
    c.pass(40);
    m.set("voice", 2); // 20 s a line, not the 10 guessed
    const v = m.view();
    expect(v.leftSec).toBeCloseTo(2 * 20 + 100 * 0.5, 0);
    expect(v.left).toBe("about 1 min 30 s left");
    expect(v.speed).toBe("20 s per line");
  });
  it("gives a fast stage its speed per second", () => {
    const c = clock(), m = new Meter(c.now);
    m.plan(plan);
    m.begin("draw");
    c.pass(10);
    m.set("draw", 61);
    expect(m.view().speed).toBe("6.1 frames/s");
  });
  it("never lets the percentage go back, even when more work is found", () => {
    const c = clock(), m = new Meter(c.now);
    m.plan(plan);
    m.begin("voice");
    m.set("voice", 4);
    m.end("voice");
    const before = m.view().percent;
    m.plan([...plan.slice(0, 2), { ...plan[2]!, units: 4000 }]);
    m.begin("draw");
    expect(m.view().percent).toBeGreaterThanOrEqual(before);
    m.set("draw", 4000);
    m.end("draw");
    expect(m.view().percent).toBe(100);
  });
  it("moves by the clock inside a piece of work that cannot report, and says it is an estimate", () => {
    const c = clock(), m = new Meter(c.now);
    m.plan([{ id: "join", label: "Joining the film", units: 1, per: 8 }]);
    m.begin("join");
    c.pass(4);
    const v = m.view();
    expect(v.stagePercent).toBe(50);
    expect(v.estimated).toBe(true);
    expect(v.text).toContain("~50%");
    c.pass(200);
    expect(m.view().stagePercent).toBe(95); // it waits just short of done until it is told
    m.end("join");
    expect(m.view().percent).toBe(100);
  });
  it("takes parts of a unit", () => {
    const m = new Meter(clock().now);
    m.plan(plan);
    m.begin("voice");
    m.set("voice", 2.5);
    expect(m.view().stagePercent).toBe(63);
    expect(m.view().done).toBe(2);
  });
  it("learns how fast this computer is and uses it the next time", () => {
    const c = clock(), m = new Meter(c.now);
    m.plan(plan);
    m.begin("voice");
    c.pass(120);
    m.set("voice", 4);
    m.end("voice");
    const learnt = m.rates();
    expect(learnt.voice).toBeCloseTo(30, 5);
    const c2 = clock(), m2 = new Meter(c2.now, learnt);
    m2.plan(plan);
    m2.begin("voice");
    expect(m2.view().leftSec).toBeCloseTo(4 * 30 + 50, 0);
    // a rate already known is moved towards the new measurement, not thrown away
    const m3 = new Meter(c2.now, { voice: 10 });
    m3.plan(plan); m3.begin("voice"); c2.pass(120); m3.set("voice", 4); m3.end("voice");
    expect(m3.rates().voice).toBeGreaterThan(10);
    expect(m3.rates().voice).toBeLessThan(30);
  });
  it("writes one line with every number in it", () => {
    const c = clock(), m = new Meter(c.now);
    m.plan(plan);
    m.begin("voice"); c.pass(40); m.set("voice", 4); m.end("voice");
    m.begin("draw", "s00_front.en"); c.pass(10); m.set("draw", 50, "s00_front.en");
    const v = m.view();
    expect(v.text).toBe("Step 2 of 2 · Drawing the shots · 50% · 50 / 100 frames · 5.0 frames/s · about 10 s left · s00_front.en");
    expect(v.percent).toBe(83); // 40 + 10 of 40 + 20 seconds
  });
  it("has no percentage before it has a plan", () => {
    const m = new Meter(clock().now);
    m.note("Getting ready");
    const v = m.view();
    expect(v.frac).toBe(-1);
    expect(v.text).toBe("Getting ready");
  });
});

describe("the board every kind of work reports to", () => {
  it("lists what is running, tells its listeners, and forgets what ended", () => {
    const b = new WorkBoard();
    let calls = 0;
    const off = b.on(() => calls++);
    b.set("export", { label: "Rendering", frac: 0.25, text: "Rendering · 25%" });
    b.set("voice", { label: "Speaking", frac: -1, text: "Speaking" });
    expect(b.list().map((w) => w.id)).toEqual(["export", "voice"]);
    expect(b.list()[0]!.percent).toBe(25);
    expect(b.list()[1]!.percent).toBe(null);
    b.set("export", null);
    expect(b.list().map((w) => w.id)).toEqual(["voice"]);
    expect(calls).toBe(3);
    off();
    b.set("voice", null);
    expect(calls).toBe(3);
    expect(b.list()).toEqual([]);
  });
  it("reads the percentage out of a plain status line, so old reporters need no change", () => {
    const b = new WorkBoard();
    b.say("proxy", "Preparing a smooth editing copy of clip.mp4… 37%");
    expect(b.list()[0]!.percent).toBe(37);
    expect(b.list()[0]!.frac).toBeCloseTo(0.37, 5);
    b.say("proxy", "");
    expect(b.list()).toEqual([]);
  });
  it("does not take a setting for work: only a line about something being made gets a bar", () => {
    const b = new WorkBoard();
    for (const line of ["Zoom 150%", "Opacity 50%", "Volume set to 80%"]) b.say("status", line);
    expect(b.list()).toEqual([]);
    for (const line of ["Speaking… 42%", "Downloading the lyrics model 37%", "Rendering · 13% · 120 / 900 frames", "export · 37%"]) {
      b.say("status", line);
      expect(b.list().length, line).toBe(1);
    }
  });
});

describe("a long loop that reports and lets the window breathe", () => {
  it("tells who listens how far it is", async () => {
    const { longWork } = await import("../src/app/progress");
    const got: [number, string][] = [];
    longWork.stop = false;
    longWork.on = (f, w) => got.push([f, w]);
    await longWork.tick(0.25, "bake");
    await longWork.tick(0.5, "bake");
    longWork.on = null;
    expect(got).toEqual([[0.25, "bake"], [0.5, "bake"]]);
  });
  it("stops when asked, and can be started again", async () => {
    const { longWork } = await import("../src/app/progress");
    longWork.stop = true;
    await expect(longWork.tick(0.6, "bake")).rejects.toThrow("Stopped.");
    longWork.stop = false;
    await expect(longWork.tick(0.7, "bake")).resolves.toBeUndefined();
  });
  it("breathes now and then, not at every step", async () => {
    const { longWork } = await import("../src/app/progress");
    longWork.stop = false;
    const before = longWork.breaths;
    longWork.every = 1e9;
    for (let i = 0; i < 50; i++) await longWork.tick(i / 50, "check");
    expect(longWork.breaths).toBe(before);
    longWork.every = 0;
    await longWork.tick(1, "check");
    expect(longWork.breaths).toBe(before + 1);
    longWork.every = 120;
  });
});
