import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { applyDna, describeDna, dnaDistance, dnaFromCuts, dnaFromProject, isShotClip, mergeDna, pictureArea, planCuts, planRetime, validateDna, type Dna } from "../src/smart/dna";
import { defaultText, newClip, newProject, newScene, newTrack, type Asset, type Clip, type Project } from "../src/core/model";
import type { ColourStats } from "../src/color/types";

/* --------------------------------- helpers -------------------------------- */

/** Beat grid at `bpm` from `from` to `to` seconds (inclusive), and every `per`-th beat as a downbeat. */
function grid(bpm: number, to: number, from = 0, per = 4): { beats: number[]; downbeats: number[] } {
  const p = 60 / bpm;
  const beats: number[] = [];
  const downbeats: number[] = [];
  for (let i = 0; from + i * p <= to + 1e-9; i++) {
    const t = Math.round((from + i * p) * 1e6) / 1e6;
    beats.push(t);
    if (i % per === 0) downbeats.push(t);
  }
  return { beats, downbeats };
}

/** Cut list from shot lengths. */
function cutsFrom(lens: number[]): { cuts: number[]; duration: number } {
  const cuts: number[] = [];
  let t = 0;
  for (const l of lens) {
    t = Math.round((t + l) * 1e6) / 1e6;
    cuts.push(t);
  }
  const duration = cuts.pop()!;
  return { cuts, duration };
}

function colourStats(L: number, a: number, b: number, sat: number): ColourStats {
  const hist = new Array<number>(64).fill(1 / 64);
  return { n: 1000, labMean: [L, a, b], labStd: [20, 5, 5], lumaHist: hist, rgbHist: [hist, hist, hist], satMean: sat, black: 0.02, white: 0.97, palette: [{ rgb: [0.5, 0.4, 0.3], weight: 1 }] };
}

function asset(id: string, kind: Asset["kind"], duration: number): Asset {
  return { id, kind, name: id, mime: kind === "image" ? "image/png" : "video/mp4", size: 1, duration, width: 1080, height: 1920, ref: `idb:${id}` };
}

/** Minimal semantics of the ops applyDna emits, as documented in src/agent/api.ts / src/core/ops.ts. Stricter than
 *  the real editor: a move that would overlap another clip on the same track throws instead of changing track. */
function applyCommands(p: Project, cmds: Record<string, unknown>[]): void {
  const find = (id: string) => {
    for (const t of p.tracks) {
      const c = t.clips.find((x) => x.id === id);
      if (c) return { t, c };
    }
    throw new Error(`no clip ${id}`);
  };
  const move = (id: string, start: number) => {
    const { t, c } = find(id);
    const s = Math.max(0, start);
    const clash = t.clips.find((o) => o.id !== id && s < o.start + o.dur - 1e-4 && s + c.dur > o.start + 1e-4);
    if (clash) throw new Error(`move of ${id} to ${s} overlaps ${clash.id}`);
    c.start = s;
    t.clips.sort((a, b) => a.start - b.start);
  };
  for (const cmd of cmds) {
    if (cmd.op === "clip.move") move(String(cmd.id), Number(cmd.start));
    else if (cmd.op === "clip.set") {
      const { c } = find(String(cmd.id));
      const patch = cmd.patch as Record<string, unknown>;
      for (const [k, v] of Object.entries(patch)) {
        const rec = c as unknown as Record<string, unknown>;
        const cur = rec[k];
        rec[k] = v && typeof v === "object" && !Array.isArray(v) && cur && typeof cur === "object" ? { ...(cur as object), ...(v as object) } : v;
      }
      if (patch.start !== undefined) move(c.id, Number(patch.start));
    } else throw new Error(`test cannot apply ${String(cmd.op)}`);
  }
}

function shotClips(p: Project): Clip[] {
  return p.tracks.filter((t) => t.kind === "video").flatMap((t) => t.clips).filter((c) => c.kind === "media").sort((a, b) => a.start - b.start);
}

function expectWellFormed(cuts: number[], duration: number): void {
  for (let i = 0; i < cuts.length; i++) {
    expect(Number.isFinite(cuts[i]!)).toBe(true);
    expect(cuts[i]!).toBeGreaterThan(0);
    expect(cuts[i]!).toBeLessThan(duration);
    expect(cuts[i]! - (cuts[i - 1] ?? 0)).toBeGreaterThanOrEqual(0.25 - 1e-9);
  }
  if (cuts.length) expect(duration - cuts[cuts.length - 1]!).toBeGreaterThanOrEqual(0.25 - 1e-9);
}

/* ----------------------------- reference DNAs ----------------------------- */

/** Fast, on the beat: 120 bpm, shots of 1, 2 and 4 beats, 32 s. */
function fastOnBeat(): { dna: Dna; g: ReturnType<typeof grid> } {
  const pattern = [2, 2, 1, 1, 2, 4, 2, 1, 1, 2, 2, 4, 1, 1, 2, 2];
  const lens: number[] = [];
  while (lens.reduce((a, b) => a + b, 0) * 0.5 < 32) lens.push(pattern[lens.length % pattern.length]!);
  const { cuts, duration } = cutsFrom(lens.map((b) => b * 0.5));
  const g = grid(120, duration + 4);
  return { dna: dnaFromCuts({ name: "fast", cuts, duration, ...g, bpm: 120 }), g };
}

/** Slow and cinematic: long, varied shots, mostly off an 80 bpm beat. */
function slowCinematic(): { dna: Dna; g: ReturnType<typeof grid> } {
  const lens = [6.3, 4.1, 8.7, 5.2, 7.9, 4.6, 9.4, 5.8, 6.6, 4.3, 7.2, 8.1, 5.5, 6.9];
  const { cuts, duration } = cutsFrom(lens);
  const g = grid(80, duration + 4);
  return { dna: dnaFromCuts({ name: "slow", cuts, duration, ...g, bpm: 80 }), g };
}

/** Accelerating: long shots first, getting faster toward the end, on a 120 bpm beat. */
function accelerating(): { dna: Dna; g: ReturnType<typeof grid> } {
  const beatsLong = [8, 8, 8, 4, 4, 8, 4, 4, 4, 2, 4, 2, 2, 2, 2, 1, 2, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1];
  const { cuts, duration } = cutsFrom(beatsLong.map((b) => b * 0.5));
  const g = grid(120, duration + 4);
  return { dna: dnaFromCuts({ name: "accel", cuts, duration, ...g, bpm: 120 }), g };
}

/* ---------------------------------- tests --------------------------------- */

describe("dnaFromCuts", () => {
  it("measures shots, beat buckets, beat alignment, arc and first cut on a 120 bpm grid", () => {
    const g = grid(120, 16);
    // Four 2 s shots, then eight 1 s shots: 16 s.
    const cuts = [2, 4, 6, 8, 9, 10, 11, 12, 13, 14, 15];
    const d = dnaFromCuts({ name: "hand", cuts, duration: 16, ...g, bpm: 120 });
    expect(d.v).toBe(1);
    expect(d.source).toBe("video");
    expect(d.samples).toBe(1);
    const r = d.rhythm;
    expect(r.bpm).toBe(120);
    expect(r.shotCount).toBe(12);
    expect(r.duration).toBe(16);
    expect(r.shotMedian).toBe(1);
    expect(r.shotMean).toBeCloseTo(16 / 12, 12);
    expect(r.shotMin).toBe(1);
    expect(r.shotMax).toBe(2);
    // 1 s = 2 beats, 2 s = 4 beats.
    expect(r.beatBuckets).toEqual([0, 0, 8 / 12, 4 / 12, 0, 0]);
    expect(r.onBeat).toBe(1);
    // Downbeats every 2 s: cuts at 2, 4, 6, 8, 10, 12, 14 are on one (7 of 11).
    expect(r.onDownbeat).toBeCloseTo(7 / 11, 12);
    // Slices of 2 s: the first half holds 2 s shots (2× the 1 s median), the second half 1 s shots.
    expect(r.arc.length).toBe(8);
    r.arc.forEach((x, i) => expect(x).toBeCloseTo(i < 4 ? 2 : 1, 12));
    expect(r.firstCut).toBe(2);
  });

  it("counts cuts within 80 ms of a beat, and buckets lengths by the nearest power of two", () => {
    const g = grid(120, 8);
    // 1.25 is a quarter beat off; 2.05 is 50 ms after a downbeat; 3.0 is a beat; 5.3 is 200 ms off.
    const d = dnaFromCuts({ name: "x", cuts: [1.25, 2.05, 3, 5.3], duration: 8, ...g });
    expect(d.rhythm.bpm).toBeCloseTo(120, 9); // derived from the beat gaps
    expect(d.rhythm.onBeat).toBeCloseTo(2 / 4, 12);
    expect(d.rhythm.onDownbeat).toBeCloseTo(1 / 4, 12);
    // Lengths 1.25, 0.8, 0.95, 2.3, 2.7 s = 2.5, 1.6, 1.9, 4.6, 5.4 beats -> buckets 2, 2, 2, 4, 4.
    expect(d.rhythm.beatBuckets!.map((x) => +x.toFixed(9))).toEqual([0, 0, 0.6, 0.4, 0, 0]);
    expect(d.rhythm.firstCut).toBe(1.25);
    // Half a beat and 16+ beats.
    const e = dnaFromCuts({ name: "y", cuts: [0.25, 10.25], duration: 10.5, bpm: 120 });
    expect(e.rhythm.beatBuckets).toEqual([2 / 3, 0, 0, 0, 0, 1 / 3]); // 0.25 s, 10 s, 0.25 s
    expect(e.rhythm.onBeat).toBeUndefined(); // bpm alone gives no beat positions
  });

  it("cleans the cut list and copes with tiny inputs without NaN", () => {
    const d = dnaFromCuts({ name: "c", cuts: [5, 0, 2, 2.0004, -1, 10, 12, NaN], duration: 10 });
    expect(d.rhythm.shotCount).toBe(3); // cuts 2 and 5
    expect(d.rhythm.firstCut).toBe(2);
    expect(d.rhythm.beatBuckets).toBeUndefined();
    expect(d.rhythm.onBeat).toBeUndefined();
    const one = dnaFromCuts({ name: "one", cuts: [], duration: 7, ...grid(120, 7) });
    expect(one.rhythm).toMatchObject({ shotCount: 1, shotMedian: 7, shotMin: 7, shotMax: 7, firstCut: 7 });
    expect(one.rhythm.arc).toEqual([1, 1, 1, 1, 1, 1, 1, 1]);
    expect(one.rhythm.onBeat).toBeUndefined();
    const zero = dnaFromCuts({ name: "zero", cuts: [1, 2], duration: 0 });
    expect(zero.rhythm).toMatchObject({ shotCount: 0, duration: 0, shotMedian: 0, shotMean: 0, firstCut: 0 });
    for (const x of [one, zero, dnaFromCuts({ name: "nan", cuts: [], duration: NaN })]) {
      expect(JSON.stringify(x)).not.toMatch(/null|NaN/);
      expect(() => validateDna(JSON.parse(JSON.stringify(x)))).not.toThrow();
      expect(describeDna(x).length).toBeGreaterThanOrEqual(4);
      expect(planCuts(x, { duration: 20, seed: 3 })).toEqual([]);
      expect(dnaDistance(x, x)).toBe(0);
    }
  });
});

describe("planCuts", () => {
  const makers = [fastOnBeat, slowCinematic, accelerating];

  it("returns sorted cuts inside (0, duration), at least 0.25 s apart, deterministic per seed", () => {
    for (const make of makers) {
      const { dna } = make();
      for (const duration of [0.6, 3, 31.7, 90]) {
        const g = grid(dna.rhythm.bpm!, duration + 2, 0.13);
        for (const o of [{ duration }, { duration, ...g }, { duration, ...g, bpm: dna.rhythm.bpm }]) {
          const a = planCuts(dna, { ...o, seed: 7 });
          expectWellFormed(a, duration);
          expect(planCuts(dna, { ...o, seed: 7 })).toEqual(a);
        }
      }
    }
  });

  it("gives a different plan for a different seed when the style has varied shot lengths", () => {
    for (const make of makers) {
      const { dna } = make();
      const g = grid(dna.rhythm.bpm!, 64);
      const a = planCuts(dna, { duration: 60, ...g, seed: 1 });
      const b = planCuts(dna, { duration: 60, ...g, seed: 2 });
      expect(a).not.toEqual(b);
      // Without music as well.
      expect(planCuts(dna, { duration: 60, seed: 1 })).not.toEqual(planCuts(dna, { duration: 60, seed: 2 }));
    }
  });

  it("snaps cuts to the beat (and the downbeat) in the style's proportions", () => {
    const { dna } = fastOnBeat();
    const g = grid(120, 64, 0.37); // the new song's beats start at 0.37 s
    const plan = planCuts(dna, { duration: 60, ...g, seed: 4 });
    const back = dnaFromCuts({ name: "b", cuts: plan, duration: 60, ...g });
    expect(back.rhythm.onBeat).toBe(1);
    expect(Math.abs(back.rhythm.onDownbeat! - dna.rhythm.onDownbeat!)).toBeLessThan(0.1);
    const slow = slowCinematic();
    const g2 = grid(80, 200);
    const back2 = dnaFromCuts({ name: "b", cuts: planCuts(slow.dna, { duration: 180, ...g2, seed: 4 }), duration: 180, ...g2 });
    expect(Math.abs(back2.rhythm.onBeat! - slow.dna.rhythm.onBeat!)).toBeLessThan(0.15);
  });

  it("measured back with the same beats, a plan is close to the original DNA (dnaDistance < 0.15)", () => {
    for (const make of makers) {
      const { dna } = make();
      for (const duration of [dna.rhythm.duration, dna.rhythm.duration * 1.5]) {
        for (const seed of [1, 2, 3]) {
          const g = grid(dna.rhythm.bpm!, duration + 4);
          const plan = planCuts(dna, { duration, ...g, bpm: dna.rhythm.bpm, seed });
          const back = dnaFromCuts({ name: "back", cuts: plan, duration, ...g, bpm: dna.rhythm.bpm });
          expect(dnaDistance(dna, back), `${dna.name} ${duration} s seed ${seed}`).toBeLessThan(0.15);
        }
      }
      // Without music: lengths in seconds.
      const plan = planCuts(dna, { duration: dna.rhythm.duration * 1.5, seed: 5 });
      const back = dnaFromCuts({ name: "back", cuts: plan, duration: dna.rhythm.duration * 1.5 });
      const noBeats: Dna = { ...dna, rhythm: { ...dna.rhythm, beatBuckets: undefined, onBeat: undefined, onDownbeat: undefined } };
      expect(dnaDistance(noBeats, back), `${dna.name} without music`).toBeLessThan(0.15);
    }
  });

  it("keeps the accelerating shape: long shots early, short shots late", () => {
    const { dna } = accelerating();
    const g = grid(120, 70);
    const plan = planCuts(dna, { duration: 66, ...g, seed: 9 });
    const back = dnaFromCuts({ name: "b", cuts: plan, duration: 66, ...g });
    const first = (back.rhythm.arc[0]! + back.rhythm.arc[1]!) / 2;
    const last = (back.rhythm.arc[6]! + back.rhythm.arc[7]!) / 2;
    expect(first / last).toBeGreaterThan(3);
  });
});

describe("dnaDistance", () => {
  it("is 0 for the same DNA, symmetric, in 0..1, and large for different styles", () => {
    const a = fastOnBeat().dna;
    const b = slowCinematic().dna;
    const c = accelerating().dna;
    expect(dnaDistance(a, a)).toBe(0);
    expect(dnaDistance(a, b)).toBeCloseTo(dnaDistance(b, a), 12);
    for (const [x, y] of [[a, b], [a, c], [b, c]] as const) {
      const d = dnaDistance(x, y);
      expect(d).toBeGreaterThan(0.25);
      expect(d).toBeLessThanOrEqual(1);
    }
    expect(dnaDistance(a, b)).toBeGreaterThan(0.5);
  });

  it("compares colour, type and motion only when both records have them", () => {
    const a = fastOnBeat().dna;
    const warm = { ...a, colour: colourStats(70, 10, 25, 0.5) };
    const cold = { ...a, colour: colourStats(30, -2, -20, 0.1) };
    expect(dnaDistance(warm, a)).toBe(0);
    expect(dnaDistance(warm, cold)).toBeGreaterThan(0.15);
    expect(dnaDistance(warm, { ...a, colour: colourStats(68, 9, 24, 0.48) })).toBeLessThan(0.02);
  });
});

/* ------------------------------ project tests ----------------------------- */

/** A small project: shots on V1 and V2 (one full-frame shot hides a V1 cut, one through edit), a small picture-in-picture,
 *  a transparent overlay scene, titles on their own track and music on A1. */
function sampleProject(): Project {
  const p = newProject("Sample", 1080, 1920, 30);
  const T = newTrack("video", "T");
  const V2 = newTrack("video", "V2");
  const V1 = newTrack("video", "V1");
  const A1 = newTrack("audio", "A1");
  p.tracks = [T, V2, V1, A1];
  p.assets = [asset("vid", "video", 60), asset("img", "image", 0), asset("song", "audio", 30)];
  const opaque = newScene("Drawn", 1080, 1920, 24);
  opaque.frames = 48;
  const clear = newScene("Overlay", 1080, 1920, 24);
  clear.background = "";
  p.scenes = [opaque, clear];
  V1.clips = [
    newClip("media", 0, 2, { id: "a", name: "a", assetId: "vid", in: 0 }),
    newClip("media", 2, 3, { id: "b", name: "b", assetId: "vid", in: 10, transform: { scale: [{ t: 0, v: 1, ease: "linear" }, { t: 3, v: 1.2, ease: "linear" }] } }),
    newClip("media", 5, 1, { id: "c", name: "c", assetId: "img", transitionOut: { kind: "fade", dur: 0.5 } }),
    newClip("anim", 6, 2, { id: "d", name: "d", sceneId: opaque.id }),
    newClip("media", 8, 1, { id: "e1", name: "e1", assetId: "vid", in: 20 }),
    newClip("media", 9, 1, { id: "e2", name: "e2", assetId: "vid", in: 21 }),
  ];
  V2.clips = [
    newClip("media", 1, 2, { id: "pip", name: "pip", assetId: "vid", in: 30, transform: { scale: 0.3, x: 200 } }), // 9 % of the frame: too small to be a shot
    newClip("media", 4.5, 1, { id: "f", name: "f", assetId: "vid", in: 40 }),
  ];
  const title = (id: string, start: number, dur: number, text: string, font: string, size: number, color: string, y: number, animate: Clip["text"] extends infer X ? (X extends { animate: infer A } ? A : never) : never) =>
    newClip("text", start, dur, { id, name: id, text: { ...defaultText(text), font, size, color, animate }, transform: { y } });
  T.clips = [
    title("t1", 0, 2, "HELLO WORLD", "Playfair Display, serif", 96, "#ffffff", -600, "pop"),
    newClip("anim", 3, 1, { id: "ov", name: "ov", sceneId: clear.id }),
    title("t2", 6, 2, "Second title", "Playfair Display, serif", 96, "#ffcc00", -600, "pop"),
    title("t3", 8, 2, "end", "Inter", 192, "#ffffff", 700, "none"),
  ];
  A1.clips = [newClip("media", 0, 12, { id: "music", name: "music", assetId: "song" })];
  return p;
}

describe("dnaFromProject", () => {
  it("reads visible cuts, titles, motion and transitions from a timeline", () => {
    const p = sampleProject();
    const g = grid(120, 12);
    const d = dnaFromProject(p, { ...g, bpm: 120 });
    expect(d.source).toBe("project");
    expect(d.name).toBe("Sample");
    // Cuts: 2 (a→b), 4.5 (b→f), 5.5 (f→c), 6 (c→d, fade), 8 (d→e1). Not 5 (hidden under f), not 9 (through edit),
    // not 1/3 (picture-in-picture) or 3/4 (transparent overlay). Running time ends with the last shot at 10 s.
    expect(d.rhythm.duration).toBe(10);
    expect(d.rhythm.shotCount).toBe(6);
    expect(d.rhythm.firstCut).toBe(2);
    expect(d.rhythm.shotMin).toBe(0.5);
    expect(d.rhythm.shotMax).toBe(2.5);
    expect(d.rhythm.onBeat).toBe(1);
    expect(d.rhythm.onDownbeat).toBeCloseTo(3 / 5, 12); // 2, 6, 8
    const t = d.type!;
    expect(t.fonts).toEqual([{ font: "Playfair Display, serif", share: 2 / 3 }, { font: "Inter", share: 1 / 3 }]);
    expect(t.colors).toEqual([{ color: "#ffffff", share: 2 / 3 }, { color: "#ffcc00", share: 1 / 3 }]);
    expect(t.animate).toEqual([{ name: "pop", share: 2 / 3 }, { name: "none", share: 1 / 3 }]);
    expect(t.sizeRel).toBeCloseTo(96 / 1920, 12);
    expect(t.yRel).toBeCloseTo(-600 / 1920, 12);
    expect(t.wordsPerSec).toBeCloseTo(5 / 6, 12);
    expect(t.upperShare).toBeCloseTo(1 / 3, 12);
    const m = d.motion!;
    expect(m.movingShare).toBeCloseTo(1 / 7, 12); // b of a, b, c, d, e1, e2, f
    expect(m.transitions).toEqual([{ kind: "cut", share: 4 / 5 }, { kind: "fade", share: 1 / 5 }]);
    const lines = describeDna(d);
    expect(lines.length).toBeGreaterThanOrEqual(4);
    expect(lines.length).toBeLessThanOrEqual(8);
    expect(lines.some((l) => l.startsWith("Serif titles, medium, upper third"))).toBe(true);
    expect(lines[0]).toMatch(/on the beat/);
  });

  it("handles an empty project and a project with only titles", () => {
    const p = newProject("Empty");
    const d = dnaFromProject(p);
    expect(d.rhythm.shotCount).toBe(0);
    expect(d.type).toBeUndefined();
    expect(d.motion).toBeUndefined();
    expect(describeDna(d).length).toBeGreaterThanOrEqual(4);
    p.tracks[0]!.clips.push(newClip("text", 0, 0, { text: defaultText("") }));
    const e = dnaFromProject(p);
    expect(e.type!.wordsPerSec).toBe(0);
    expect(JSON.stringify(e)).not.toMatch(/null|NaN/);
    expect(applyDna(p, fastOnBeat().dna).commands.filter((c) => c.op === "clip.move")).toEqual([]);
  });
});

describe("mergeDna and validateDna", () => {
  it("weights by samples; colour only when every part has it", () => {
    const a = { ...dnaFromCuts({ name: "a", cuts: [1, 2, 3], duration: 4, bpm: 120 }), colour: colourStats(60, 0, 10, 0.3) };
    const b = { ...dnaFromCuts({ name: "b", cuts: [3, 6], duration: 9, bpm: 90 }), samples: 3, colour: colourStats(40, 4, -2, 0.5) };
    const m = mergeDna([a, b], "Mine");
    expect(m.name).toBe("Mine");
    expect(m.source).toBe("merged");
    expect(m.samples).toBe(4);
    expect(m.rhythm.shotCount).toBe(7);
    expect(m.rhythm.duration).toBe(13);
    expect(m.rhythm.shotMedian).toBeCloseTo((1 * 1 + 3 * 3) / 4, 12);
    expect(m.rhythm.shotMin).toBe(1);
    expect(m.rhythm.shotMax).toBe(3);
    expect(m.rhythm.bpm).toBeCloseTo((120 + 3 * 90) / 4, 12);
    expect(m.rhythm.firstCut).toBeCloseTo((1 + 3 * 3) / 4, 12);
    expect(m.rhythm.beatBuckets!.reduce((x, y) => x + y, 0)).toBeCloseTo(1, 12);
    // a: 1 s = 2 beats at 120; b: 3 s = 4.5 beats at 90.
    expect(m.rhythm.beatBuckets![2]).toBeCloseTo(0.25, 12);
    expect(m.rhythm.beatBuckets![3]).toBeCloseTo(0.75, 12);
    expect(m.colour!.labMean[0]).toBeCloseTo((60 + 3 * 40) / 4, 12);
    expect(m.colour!.lumaHist.reduce((x, y) => x + y, 0)).toBeCloseTo(1, 12);
    expect(m.colour!.n).toBe(2000);
    const noColour = mergeDna([a, { ...b, colour: undefined }]);
    expect(noColour.colour).toBeUndefined();
    expect(() => validateDna(JSON.parse(JSON.stringify(m)))).not.toThrow();
  });

  it("merges titles and motion over the parts that have them", () => {
    const d = dnaFromProject(sampleProject());
    const plain = { ...dnaFromCuts({ name: "plain", cuts: [2], duration: 4 }), samples: 2 };
    const m = mergeDna([d, plain]);
    expect(m.type).toEqual(d.type);
    expect(m.motion).toEqual(d.motion);
    const twice = mergeDna([d, { ...d, samples: 3, type: { ...d.type!, fonts: [{ font: "Inter", share: 1 }], sizeRel: 0.1 } }]);
    expect(twice.type!.fonts[0]).toEqual({ font: "Inter", share: (1 / 3 + 3) / 4 });
    expect(twice.type!.sizeRel).toBeCloseTo((0.05 + 0.3) / 4, 12);
  });

  it("merging one DNA returns the same numbers; merging none gives an empty signature", () => {
    const d = { ...dnaFromProject(sampleProject(), grid(120, 12)), colour: colourStats(50, 1, 2, 0.3) };
    const m = mergeDna([d]);
    expect({ ...m, source: d.source }).toEqual(d);
    expect(m.source).toBe("merged");
    const e = mergeDna([]);
    expect(e.samples).toBe(0);
    expect(() => validateDna(JSON.parse(JSON.stringify(e)))).not.toThrow();
  });

  it("validateDna accepts a saved record and rejects broken ones with clear messages", () => {
    const d = { ...dnaFromProject(sampleProject(), grid(120, 12)), colour: colourStats(50, 1, 2, 0.3) };
    const back = validateDna(JSON.parse(JSON.stringify(d)));
    expect(back).toEqual(d);
    const bad = (f: (x: Record<string, any>) => void): unknown => {
      const x = JSON.parse(JSON.stringify(d)) as Record<string, any>;
      f(x);
      return x;
    };
    expect(() => validateDna(null)).toThrow("Invalid style DNA: the record must be an object.");
    expect(() => validateDna("dna")).toThrow(/must be an object/);
    expect(() => validateDna(bad((x) => (x.v = 2)))).toThrow("Invalid style DNA: v must be 1 (got 2).");
    expect(() => validateDna(bad((x) => (x.source = "web")))).toThrow(/source must be "video", "project" or "merged"/);
    expect(() => validateDna(bad((x) => delete x.rhythm))).toThrow("Invalid style DNA: rhythm must be an object.");
    expect(() => validateDna(bad((x) => x.rhythm.arc.pop()))).toThrow("Invalid style DNA: rhythm.arc must have 8 numbers (got 7).");
    expect(() => validateDna(bad((x) => (x.rhythm.shotMedian = NaN)))).toThrow("Invalid style DNA: rhythm.shotMedian must be a finite number (got null).");
    expect(() => validateDna(bad((x) => (x.rhythm.beatBuckets = [1, 1, 0, 0, 0, 0])))).toThrow(/rhythm.beatBuckets\[1\] must be at most 1|must add up to 1/);
    expect(() => validateDna(bad((x) => (x.rhythm.beatBuckets = [0.5, 0.2, 0, 0, 0, 0])))).toThrow("Invalid style DNA: rhythm.beatBuckets must add up to 1 (got 0.7).");
    expect(() => validateDna(bad((x) => (x.rhythm.onBeat = 1.5)))).toThrow("Invalid style DNA: rhythm.onBeat must be at most 1 (got 1.5).");
    expect(() => validateDna(bad((x) => (x.samples = 1.5)))).toThrow("Invalid style DNA: samples must be a whole number (got 1.5).");
    expect(() => validateDna(bad((x) => (x.type.fonts[0].font = 3)))).toThrow("Invalid style DNA: type.fonts[0].font must be text.");
    expect(() => validateDna(bad((x) => (x.type.yRel = -0.9)))).toThrow(/type.yRel must be at least -0.5/);
    expect(() => validateDna(bad((x) => (x.motion.transitions = {})))).toThrow(/motion.transitions must be a list/);
    expect(() => validateDna(bad((x) => x.colour.lumaHist.pop()))).toThrow("Invalid style DNA: colour.lumaHist must have 64 numbers (got 63).");
    // Unknown fields are dropped from the returned copy.
    expect(validateDna(bad((x) => (x.extra = 1)))).not.toHaveProperty("extra");
  });
});

/* -------------------------------- applyDna -------------------------------- */

const API = readFileSync(new URL("../src/agent/api.ts", import.meta.url), "utf8");

function expectRealOps(cmds: Record<string, unknown>[]): void {
  for (const c of cmds) expect(API.includes(`"${String(c.op)}":`), `op ${String(c.op)} exists in api.ts`).toBe(true);
}

/** n back-to-back shots on V1 over [start, start + total), all images (unlimited source) or videos of `srcLen` s. */
function sequenceProject(n: number, total: number, o: { start?: number; srcLen?: number; withText?: boolean } = {}): Project {
  const p = newProject("Seq", 1080, 1920, 30);
  const V1 = p.tracks[1]!;
  p.assets.push(asset("img", "image", 0));
  const start = o.start ?? 0;
  for (let i = 0; i < n; i++) {
    let assetId = "img";
    if (o.srcLen !== undefined) {
      assetId = `v${i}`;
      p.assets.push(asset(assetId, "video", o.srcLen));
    }
    V1.clips.push(newClip("media", start + (i * total) / n, total / n, { id: `s${i}`, name: `shot ${i}`, assetId, in: 0 }));
  }
  if (o.withText) {
    p.tracks[0]!.clips.push(newClip("text", 0, 3, { id: "tx1", text: { ...defaultText("One"), size: 200, color: "#FFFFFF" } }));
    p.tracks[0]!.clips.push(newClip("text", 4, 3, { id: "tx2", text: { ...defaultText("Two"), size: 100, color: "#00ff00" } }));
  }
  return p;
}

function checkSequence(p: Project, n: number, start: number, end: number): number[] {
  const shots = shotClips(p);
  expect(shots.length).toBe(n);
  expect(shots[0]!.start).toBeCloseTo(start, 9);
  expect(shots[n - 1]!.start + shots[n - 1]!.dur).toBeCloseTo(end, 9);
  for (let i = 1; i < n; i++) {
    expect(shots[i]!.start).toBeGreaterThanOrEqual(shots[i - 1]!.start + shots[i - 1]!.dur - 1e-9); // no overlap
    expect(shots[i]!.start - (shots[i - 1]!.start + shots[i - 1]!.dur)).toBeLessThan(1e-6); // no gap
  }
  for (const t of p.tracks) for (const c of t.clips) expect(c.start + c.dur).toBeLessThanOrEqual(end + 1e-9); // nothing left parked
  return shots.slice(1).map((c) => c.start);
}

describe("applyDna", () => {
  it("re-times a clean sequence exactly to planCuts when the shot count matches", () => {
    const { dna } = fastOnBeat();
    const g = grid(120, 24);
    const plan = planCuts(dna, { duration: 20, ...g, seed: 11 });
    const n = plan.length + 1;
    const p = sequenceProject(n, 20);
    const before = JSON.parse(JSON.stringify(p)) as Project;
    const { commands, notes } = applyDna(p, dna, { ...g, seed: 11, parts: ["rhythm"] });
    expect(p).toEqual(before); // applyDna does not touch the project
    expect(commands.length).toBeGreaterThan(0);
    expectRealOps(commands);
    expect(notes[0]).toMatch(/^Re-timed \d+ shots/);
    applyCommands(p, commands);
    const cuts = checkSequence(p, n, 0, 20);
    cuts.forEach((c, i) => expect(c).toBeCloseTo(plan[i]!, 9));
    // The in-points are kept.
    for (const c of shotClips(p)) expect(c.in).toBe(0);
  });

  it("keeps shot count, total duration and source limits when the pace must be scaled", () => {
    for (const make of [fastOnBeat, slowCinematic, accelerating]) {
      const { dna } = make();
      const g = grid(dna.rhythm.bpm!, 70);
      // 12 video shots of 4 s, each with 7 s of source, starting at 3 s.
      const p = sequenceProject(12, 48, { start: 3, srcLen: 7 });
      const plan = planRetime(p, dna, { ...g, seed: 2 });
      expect(plan.ok).toBe(true);
      if (!plan.ok) return;
      const { commands } = applyDna(p, dna, { ...g, seed: 2, parts: ["rhythm"] });
      expectRealOps(commands);
      applyCommands(p, commands);
      const cuts = checkSequence(p, 12, 3, 51);
      cuts.forEach((c, i) => expect(c).toBeCloseTo(plan.cuts[i]!, 9));
      expectWellFormed(cuts.map((c) => c - 3), 48);
      for (const c of shotClips(p)) expect(c.in + c.dur * c.speed).toBeLessThanOrEqual(7 + 1e-9);
    }
  });

  it("never extends a clip whose source length is unknown", () => {
    const { dna } = accelerating();
    const p = sequenceProject(6, 30, { srcLen: 0 }); // unknown length
    const before = shotClips(p).map((c) => c.dur);
    const r = applyDna(p, dna, { parts: ["rhythm"] });
    // Every shot may only shrink, and they must still fill 30 s: so nothing can change.
    applyCommands(p, r.commands);
    shotClips(p).forEach((c, i) => expect(c.dur).toBeLessThanOrEqual(before[i]! + 1e-9));
    checkSequence(p, 6, 0, 30);
  });

  it("emits no rhythm commands for a messy timeline, and says why", () => {
    const { dna } = fastOnBeat();
    const sample = applyDna(sampleProject(), dna, { parts: ["rhythm"] });
    expect(sample.commands).toEqual([]);
    expect(sample.notes.join(" ")).toMatch(/overlap/);
    const gap = sequenceProject(4, 8);
    gap.tracks[1]!.clips[2]!.start += 0.5;
    gap.tracks[1]!.clips[3]!.start += 0.5;
    const r = applyDna(gap, dna, { parts: ["rhythm"] });
    expect(r.commands).toEqual([]);
    expect(r.notes.join(" ")).toMatch(/gap/);
    const locked = sequenceProject(4, 8);
    locked.tracks[1]!.locked = true;
    expect(applyDna(locked, dna, { parts: ["rhythm"] }).notes.join(" ")).toMatch(/locked/);
    expect(applyDna(sequenceProject(1, 5), dna).notes[0]).toMatch(/only one shot/);
    expect(applyDna(sequenceProject(4, 8), dnaFromCuts({ name: "take", cuts: [], duration: 30 })).notes[0]).toMatch(/one continuous shot/);
    const crowded = sequenceProject(40, 8);
    expect(applyDna(crowded, dna, { parts: ["rhythm"] }).notes[0]).toMatch(/shorter than 0.25 s/);
  });

  it("restyles titles with clip.set text patches and only suggests motion", () => {
    const style = dnaFromProject(sampleProject());
    const p = sequenceProject(3, 9, { withText: true });
    const { commands, notes } = applyDna(p, style, { parts: ["type", "motion"] });
    expectRealOps(commands);
    expect(commands.every((c) => c.op === "clip.set")).toBe(true);
    applyCommands(p, commands);
    const texts = p.tracks[0]!.clips;
    for (const c of texts) expect(c.text!.font).toBe("Playfair Display, serif");
    // Colours by rank: the project's main white becomes the style's main white, the green its second colour.
    expect(texts[0]!.text!.color).toBe("#FFFFFF");
    expect(texts[1]!.text!.color).toBe("#ffcc00");
    // Median size 150 -> 96 (sizeRel 0.05 of 1920), both scaled by the same factor.
    expect(texts[0]!.text!.size).toBeCloseTo(128, 6);
    expect(texts[1]!.text!.size).toBeCloseTo(64, 6);
    expect(texts[0]!.text!.text).toBe("One"); // the rest of the style is untouched
    expect(notes.some((n) => /Restyled 2 text clip/.test(n))).toBe(true);
    expect(notes.some((n) => /straight cuts/.test(n))).toBe(true);
    expect(applyDna(p, style, { parts: ["type"] }).commands).toEqual([]); // already matching
  });
});

describe("invariants under many random inputs", () => {
  it("never throws, never yields NaN, and always keeps the cut rules", () => {
    let seed = 12345;
    const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
    for (let k = 0; k < 150; k++) {
      const n = Math.floor(rnd() * 30);
      const lens = Array.from({ length: n + 1 }, () => 0.05 + rnd() * rnd() * 10);
      const { cuts, duration } = cutsFrom(lens);
      const bpm = 60 + rnd() * 120;
      const withBeats = rnd() < 0.7;
      const g = grid(bpm, duration + 5, rnd() * 0.5, 1 + Math.floor(rnd() * 4));
      const dna = dnaFromCuts({ name: `r${k}`, cuts, duration, ...(withBeats ? g : {}), bpm: withBeats && rnd() < 0.5 ? bpm : undefined });
      expect(JSON.stringify(dna)).not.toMatch(/null|NaN|Infinity/);
      validateDna(JSON.parse(JSON.stringify(dna)));
      const lines = describeDna(dna);
      expect(lines.length).toBeGreaterThanOrEqual(4);
      expect(lines.length).toBeLessThanOrEqual(8);
      const D = rnd() * 80;
      const bpm2 = 60 + rnd() * 120;
      const g2 = grid(bpm2, D + 2, rnd() * 0.5);
      const opts = rnd() < 0.5 ? { duration: D, ...g2, seed: k } : { duration: D, bpm: bpm2, seed: k };
      const plan = planCuts(dna, opts);
      expectWellFormed(plan, D);
      const back = dnaFromCuts({ name: "b", cuts: plan, duration: D, ...g2 });
      const dist = dnaDistance(dna, back);
      expect(dist).toBeGreaterThanOrEqual(0);
      expect(dist).toBeLessThanOrEqual(1);
      // Re-time a sequence of shots with this DNA.
      const shots = 1 + Math.floor(rnd() * 15);
      const total = 0.2 + rnd() * 40;
      const p = sequenceProject(shots, total, { start: rnd() * 2, srcLen: rnd() < 0.5 ? total / shots + rnd() * 5 : undefined });
      const start = p.tracks[1]!.clips[0]!.start;
      const r = applyDna(p, dna, { ...g2, seed: k });
      expectRealOps(r.commands);
      expect(r.notes.length).toBeGreaterThan(0);
      applyCommands(p, r.commands);
      checkSequence(p, shots, start, start + total);
      const merged = mergeDna([dna, back, dnaFromProject(p)]);
      expect(JSON.stringify(merged)).not.toMatch(/null|NaN|Infinity/);
      validateDna(JSON.parse(JSON.stringify(merged)));
    }
  });
});

/* ------------------------------ split screens ----------------------------- */

/**
 * A split-screen reel at 120 bpm (one bar = 2 s): a full-frame shot 0–4 s, then 4–20 s a blurred full-frame backdrop
 * on the lowest track with three stacked horizontal panels above it (landscape video, fit contain, scale ≈ 0.926,
 * 4.6 % top/bottom crop, keyframed x and scale) that all change picture on every bar (the middle panel half a frame
 * late), then a full-frame shot 20–24 s. A 10 %-area sticker sits over the first shot, titles on a text track.
 */
function splitProject(o: { backdropStart?: number; noBackdrop?: boolean } = {}): Project {
  const p = newProject("Split", 1080, 1920, 30);
  const T = newTrack("video", "Titles");
  const P1 = newTrack("video", "P1");
  const P2 = newTrack("video", "P2");
  const P3 = newTrack("video", "P3");
  const V1 = newTrack("video", "V1");
  p.tracks = [T, P1, P2, P3, V1, newTrack("audio", "A1")];
  p.assets = [{ ...asset("land", "video", 600), width: 1920, height: 1080 }, asset("tall", "video", 600), asset("sticker", "image", 0)];
  V1.clips.push(newClip("media", 0, 4, { id: "F1", assetId: "tall", in: 100 }));
  if (!o.noBackdrop) {
    const bs = o.backdropStart ?? 4;
    V1.clips.push(newClip("media", bs, 20 - bs, { id: "B", assetId: "land", in: 0, fit: "cover", grade: { blur: 40 } }));
  }
  V1.clips.push(newClip("media", 20, 4, { id: "F2", assetId: "tall", in: 200 }));
  const panels = [P1, P2, P3];
  panels.forEach((tr, k) => {
    const late = k === 1 ? 1 / 60 : 0; // within one frame of the others
    for (let bar = 0; bar < 8; bar++) {
      const start = 4 + bar * 2 + (bar === 0 ? 0 : late);
      const end = 4 + (bar + 1) * 2 + (bar === 7 ? 0 : late);
      tr.clips.push(
        newClip("media", start, end - start, {
          id: `P${k}_${bar}`,
          assetId: "land",
          in: 10 + bar * 37 + k * 5,
          fit: "contain",
          crop: { l: 0, t: 0.046, r: 0, b: 0.046 },
          transform: { y: (k - 1) * 640, x: [{ t: 0, v: -20, ease: "linear" }, { t: 2, v: 20, ease: "linear" }], scale: [{ t: 0, v: 0.92, ease: "linear" }, { t: end - start, v: 0.932, ease: "linear" }] },
        }),
      );
    }
  });
  // About 10 % of the frame.
  T.clips.push(newClip("media", 1, 2, { id: "S", assetId: "sticker", fit: "contain", transform: { scale: Math.sqrt(0.1) * 0.99 } }));
  T.clips.push(newClip("text", 4, 4, { id: "title", text: { ...defaultText("THE BIG ONE"), size: 110 } }));
  return p;
}

describe("split screens", () => {
  it("counts every panel change as a cut, on the bar", () => {
    const p = splitProject();
    const g = grid(120, 30);
    const d = dnaFromProject(p, { ...g, bpm: 120 });
    // Cuts at 4, 6, 8, …, 18, 20: shots 4 s, eight of 2 s, 4 s.
    expect(d.rhythm.duration).toBe(24);
    expect(d.rhythm.shotCount).toBe(10);
    expect(d.rhythm.firstCut).toBe(4);
    expect(d.rhythm.shotMedian).toBe(2);
    expect(d.rhythm.onBeat).toBe(1);
    expect(d.rhythm.onDownbeat).toBe(1);
    // 2 s = 4 beats (8 of 10), 4 s = 8 beats (2 of 10).
    expect(d.rhythm.beatBuckets!.map((x) => +x.toFixed(12))).toEqual([0, 0, 0, 0.8, 0.2, 0]);
    expect(d.motion!.movingShare).toBeCloseTo(24 / 26, 12); // 24 moving panels of 24 panels + F1 + F2
    expect(describeDna(d)[0]).toMatch(/every 4 beats.*almost always on the beat/);
  });

  it("isShotClip takes panels, not the backdrop or the sticker", () => {
    const p = splitProject();
    const clip = (id: string) => p.tracks.flatMap((t) => t.clips).find((c) => c.id === id)!;
    expect(isShotClip(p, clip("P0_3"))).toBe(true);
    expect(isShotClip(p, clip("F1"))).toBe(true);
    expect(isShotClip(p, clip("B"))).toBe(false);
    expect(isShotClip(p, clip("S"))).toBe(false);
    expect(isShotClip(p, clip("title"))).toBe(false);
    // A 16 %-area picture-in-picture does take part.
    const pip = newClip("media", 1, 2, { assetId: "tall", transform: { scale: 0.4 } });
    p.tracks[0]!.clips.push(pip);
    expect(isShotClip(p, pip)).toBe(true);
  });

  it("measures the picture area per fit like the compositor", () => {
    const p = splitProject();
    const clip = (id: string) => p.tracks.flatMap((t) => t.clips).find((c) => c.id === id)!;
    // Panel: 1920×(1080×0.908) contained in 1080×1920 → 1080 × 551.6, × 0.926.
    const qh = 1080 * 0.908 * (1080 / 1920);
    expect(pictureArea(p, clip("P1_2"))).toBeCloseTo((1080 * 0.926 * qh * 0.926) / (1080 * 1920), 9);
    expect(pictureArea(p, clip("B"))).toBeCloseTo(1, 12); // cover
    expect(pictureArea(p, clip("S"))).toBeCloseTo(0.1 * 0.99 * 0.99, 9);
    const c = (extra: Partial<Clip>) => newClip("media", 0, 2, { assetId: "land", ...extra });
    expect(pictureArea(p, c({ fit: "fill" }))).toBeCloseTo(1, 12);
    expect(pictureArea(p, c({ fit: "none" }))).toBeCloseTo((1080 * 1080) / (1080 * 1920), 12); // 1920 wide, clipped to 1080
    expect(pictureArea(p, c({ fit: "contain", transform: { x: 540 } }))).toBeCloseTo(((540 * 607.5) / (1080 * 1920)), 9); // half off screen
    expect(pictureArea(p, c({ fit: "cover", mask: { shape: "rect", x: 0.5, y: 0.5, w: 0.5, h: 0.5, feather: 0, invert: false } }))).toBeCloseTo(0.25, 12);
    expect(pictureArea(p, c({ fit: "cover", win: [{ t: 0, x0: 0, y0: 0, x1: 1, y1: 1 / 3 }] }))).toBeCloseTo(1 / 3, 9);
    expect(pictureArea(p, newClip("media", 0, 2, { assetId: "nothing-here" }))).toBeCloseTo(1, 12); // unknown size fills
  });

  it("a backdrop never adds cuts, and a small sticker adds none either", () => {
    const g = grid(120, 30);
    const base = dnaFromProject(splitProject(), g).rhythm;
    const noBackdrop = dnaFromProject(splitProject({ noBackdrop: true }), g).rhythm;
    expect(noBackdrop.shotCount).toBe(base.shotCount);
    // A backdrop starting off the grid (under the panels) still adds nothing.
    const late = dnaFromProject(splitProject({ backdropStart: 4.3 }), g).rhythm;
    expect(late).toEqual(base);
    // Without the sticker: the same rhythm.
    const p = splitProject();
    p.tracks[0]!.clips = p.tracks[0]!.clips.filter((c) => c.id !== "S");
    expect(dnaFromProject(p, g).rhythm).toEqual(base);
    // An unblurred "backdrop" is a picture of its own: its start at 4.3 is then a cut.
    const sharp = splitProject({ backdropStart: 4.3 });
    sharp.tracks[4]!.clips.find((c) => c.id === "B")!.grade = {};
    expect(dnaFromProject(sharp, g).rhythm.shotCount).toBe(base.shotCount + 1);
  });

  it("applyDna does not re-time a split screen, and says so", () => {
    const p = splitProject();
    const { dna } = fastOnBeat();
    const r = applyDna(p, dna, { ...grid(120, 30), parts: ["rhythm"] });
    expect(r.commands).toEqual([]);
    expect(r.notes).toEqual(["This edit shows several pictures at once, so its cuts were not re-timed."]);
  });
});
