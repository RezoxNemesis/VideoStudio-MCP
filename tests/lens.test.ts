import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import * as ops from "../src/core/ops";
import { defaultText, newClip, newProject, type Asset, type Clip, type Ease, type Project, type TextStyle, type TransitionKind } from "../src/core/model";
import { PLATFORMS, PROMPT_WORDS, estimateTextSize, evolve, lens, safeArea, type Finding, type LensContext, type LensReport, type Platform } from "../src/smart/lens";

/* ------------------------------------------------------------- builders ------------------------------------------------------------- */

// newProject() tracks, top first: V2, V1, A1, A2.
const V2 = (p: Project) => p.tracks[0]!;
const V1 = (p: Project) => p.tracks[1]!;
const A1 = (p: Project) => p.tracks[2]!;

let n = 0;
function asset(p: Project, kind: Asset["kind"], duration: number, extra: Partial<Asset> = {}): Asset {
  const a: Asset = { id: `ast${++n}`, kind, name: `${kind}${n}`, mime: "", size: 0, duration, width: 1080, height: 1920, ref: "", ...extra };
  p.assets.push(a);
  return a;
}

function shot(p: Project, a: Asset, start: number, dur: number, inPt: number, extra: Partial<Clip> = {}): Clip {
  const c = newClip("media", start, dur, { assetId: a.id, in: inPt, ...extra });
  V1(p).clips.push(c);
  return c;
}

function text(p: Project, s: string, start: number, dur: number, style: Partial<TextStyle> = {}, extra: Partial<Clip> = {}, track = V2(p)): Clip {
  const c = newClip("text", start, dur, { text: { ...defaultText(s), ...style }, ...extra });
  track.clips.push(c);
  return c;
}

function music(p: Project, start: number, dur: number, extra: Partial<Clip> = {}, assetDur = 30): Clip {
  const a = asset(p, "audio", assetDur);
  const c = newClip("media", start, dur, { assetId: a.id, ...extra });
  A1(p).clips.push(c);
  return c;
}

/** A short vertical edit that should pass every check. */
function good(): { p: Project; ctx: LensContext; clips: Record<string, Clip> } {
  const p = newProject("good", 1080, 1920, 30);
  const v = asset(p, "video", 60);
  const s1 = shot(p, v, 0, 1.2, 0);
  const s2 = shot(p, v, 1.2, 1.8, 10);
  const s3 = shot(p, v, 3.0, 1.0, 20);
  const s4 = shot(p, v, 4.0, 2.5, 30);
  const s5 = shot(p, v, 6.5, 1.5, 40);
  const t1 = text(p, "Wait for it", 0.5, 2.5);
  const t2 = text(p, "Your pick?", 6, 2, { size: 80 });
  const m = music(p, 0, 8, { fadeOut: 1 });
  const ctx: LensContext = {
    platform: "instagram-reel",
    beats: [0, 1.2, 3.0, 4.0, 6.5],
    loudness: { integratedLufs: -14, truePeakDb: -2 },
    frames: Array.from({ length: 17 }, (_, i) => ({ t: i * 0.5, luma: 0.5, diffPrev: 0.01 })),
    loopDiff: 0.1,
  };
  return { p, ctx, clips: { s1, s2, s3, s4, s5, t1, t2, m } };
}

const find = (r: LensReport, id: string) => r.findings.find((f) => f.id === id);
const sev = (r: LensReport, id: string) => find(r, id)?.severity;
const bad = (r: LensReport) => r.findings.filter((f) => f.severity !== "good");

/** Apply agent commands the way src/agent/api.ts runs them (same ops calls). */
function apply(p: Project, cmds: Record<string, unknown>[]): void {
  for (const c of cmds) {
    const id = String(c.id);
    switch (c.op) {
      case "clip.value":
        ops.setConstant(p, id, String(c.prop), Number(c.v));
        break;
      case "clip.set":
        ops.patchClip(p, id, c.patch as Record<string, unknown>);
        break;
      case "clip.trim":
        ops.trimClip(p, id, c.edge as "start" | "end", Number(c.t));
        break;
      case "clip.keys":
        for (const k of c.keys as unknown[][]) ops.keyframe(p, id, String(c.prop), Number(k[0]), Number(k[1]), (k[2] as Ease) ?? "inOut");
        break;
      case "clip.transition": {
        const clip = ops.getClip(p, id);
        const dur = c.dur === undefined ? 0.5 : Number(c.dur);
        if (c.in) clip.transitionIn = { kind: c.in as TransitionKind, dur };
        if (c.out) clip.transitionOut = { kind: c.out as TransitionKind, dur };
        break;
      }
      default:
        throw new Error(`test apply() does not know ${String(c.op)}`);
    }
  }
}

function fixOf(r: LensReport, id: string): NonNullable<Finding["fix"]> {
  const f = find(r, id);
  expect(f?.fix, `fix for ${id}`).toBeDefined();
  return f!.fix!;
}

/* --------------------------------------------------------------- tests -------------------------------------------------------------- */

describe("baseline", () => {
  it("a clean edit scores 100 with only good findings", () => {
    const { p, ctx } = good();
    const r = lens(p, ctx);
    expect(bad(r)).toEqual([]);
    expect(r.score).toBe(100);
    expect(r.platform).toBe("instagram-reel");
    expect(r.areas.every((a) => a.score === 100)).toBe(true);
    expect(r.findings.length).toBeGreaterThan(15);
    expect(r.verdict).toMatch(/Likely/);
  });

  it("is deterministic", () => {
    const { p, ctx } = good();
    const a = lens(p, ctx);
    const b = lens(structuredClone(p), ctx);
    expect(JSON.stringify(b)).toBe(JSON.stringify(a));
    expect(lens(p, ctx)).toEqual(a);
  });

  it("does not modify the project", () => {
    const { p, ctx } = good();
    const before = JSON.stringify(p);
    const r = lens(p, ctx);
    evolve(p, r);
    expect(JSON.stringify(p)).toBe(before);
  });

  it("handles an empty project", () => {
    const p = newProject("empty");
    const r = lens(p);
    expect(r.score).toBe(0);
    expect(r.findings.map((f) => f.id)).toEqual(["technical:empty"]);
    expect(r.attention).toEqual([{ t: 0, v: 1 }]);
    expect(r.verdict).toMatch(/Nothing/);
    expect(evolve(p, r)).toEqual({ commands: [], applied: [] });
  });

  it("guesses the platform from the frame shape", () => {
    expect(lens(newProject("a", 1080, 1920)).platform).toBe("instagram-reel");
    expect(lens(newProject("b", 1920, 1080)).platform).toBe("youtube");
    expect(lens(newProject("c", 1000, 1000)).platform).toBe("general");
  });

  it("sorts findings from most to least important", () => {
    const { p, ctx } = good();
    V1(p).clips = [];
    shot(p, asset(p, "image", 0), 0, 8, 0);
    const r = lens(p, { ...ctx, loudness: { integratedLufs: -25 } });
    const order = { high: 0, medium: 1, low: 2, good: 3 } as const;
    for (let i = 1; i < r.findings.length; i++) expect(order[r.findings[i - 1]!.severity]).toBeLessThanOrEqual(order[r.findings[i]!.severity]);
  });
});

describe("safe areas at 1080×1920", () => {
  const cases: [Platform, number, number, number, number][] = [
    ["instagram-reel", 64.8, 268.8, 1015.2, 1248],
    ["tiktok", 44, 130, 940, 1436],
    ["youtube-shorts", 48, 288, 888, 1248],
    ["youtube", 54, 96, 1026, 1824],
    ["general", 54, 96, 1026, 1824],
  ];
  for (const [pl, x0, y0, x1, y1] of cases) {
    it(pl, () => {
      const s = safeArea(pl, 1080, 1920);
      expect(s.x0).toBeCloseTo(x0, 6);
      expect(s.y0).toBeCloseTo(y0, 6);
      expect(s.x1).toBeCloseTo(x1, 6);
      expect(s.y1).toBeCloseTo(y1, 6);
      expect(s.note.length).toBeGreaterThan(10);
    });
  }
  it("every platform is described", () => {
    for (const k of Object.keys(PLATFORMS) as Platform[]) expect(PLATFORMS[k].name.length).toBeGreaterThan(0);
  });
});

describe("hook", () => {
  it("flags a still opening and the push-in fix clears it", () => {
    const p = newProject("still", 1080, 1920, 30);
    const img = asset(p, "image", 0);
    shot(p, img, 0, 3, 0);
    const v = asset(p, "video", 60);
    shot(p, v, 3, 2, 0);
    text(p, "Hello there", 0, 3);
    music(p, 0, 5, { fadeOut: 0.5 });
    const ctx: LensContext = { platform: "instagram-reel" };
    const r = lens(p, ctx);
    expect(sev(r, "hook:change")).toBe("high");
    const fix = fixOf(r, "hook:change");
    expect(fix.commands[0]!.op).toBe("clip.keys");
    apply(p, fix.commands);
    const r2 = lens(p, ctx);
    expect(sev(r2, "hook:change")).toBe("good");
    expect(r2.score).toBeGreaterThan(r.score);
  });

  it("flags a long fade from black and the fix shortens it", () => {
    const { p, ctx, clips } = good();
    clips.s1!.transitionIn = { kind: "fade", dur: 1.5 };
    const r = lens(p, ctx);
    expect(sev(r, "hook:fade")).toBe("medium");
    apply(p, fixOf(r, "hook:fade").commands);
    expect(clips.s1!.transitionIn).toEqual({ kind: "fade", dur: 0.3 });
    const r2 = lens(p, ctx);
    expect(sev(r2, "hook:fade")).toBe("good");
    expect(r2.score).toBeGreaterThan(r.score);
  });

  it("flags black at the start (picture arriving late), without a fix", () => {
    const { p, ctx, clips } = good();
    clips.s1!.start = 0.8;
    clips.s1!.dur = 0.4;
    const r = lens(p, ctx);
    expect(sev(r, "hook:fade")).toBe("medium");
    expect(find(r, "hook:fade")!.fix).toBeUndefined();
  });

  it("flags a dark first frame", () => {
    const { p, ctx } = good();
    const frames = ctx.frames!.map((f, i) => (i === 0 ? { ...f, luma: 0.03 } : f));
    expect(sev(lens(p, { ...ctx, frames }), "hook:dark")).toBe("medium");
    expect(sev(lens(p, ctx), "hook:dark")).toBe("good");
  });

  it("flags late first text, and no text on short-form", () => {
    const { p, ctx, clips } = good();
    clips.t1!.start = 4;
    clips.t1!.dur = 1.5;
    expect(sev(lens(p, ctx), "hook:text")).toBe("low");
    clips.t1!.start = 6.5;
    clips.t2!.start = 6.5;
    clips.t2!.dur = 1.5;
    clips.t1!.transform.y = -300;
    expect(sev(lens(p, ctx), "hook:text")).toBe("medium");
    V2(p).clips = [];
    expect(sev(lens(p, ctx), "hook:text")).toBe("low");
    expect(find(lens(p, ctx), "hook:text")!.title).toMatch(/No on-screen text/);
  });
});

describe("pacing", () => {
  it("flags long shots and equal shot lengths", () => {
    const p = newProject("slow", 1080, 1920, 30);
    const v = asset(p, "video", 120);
    for (let i = 0; i < 4; i++) shot(p, v, i * 6, 6, i * 20);
    text(p, "Hi", 0.5, 2);
    music(p, 0, 24, { fadeOut: 1 });
    const r = lens(p, { platform: "tiktok" });
    expect(sev(r, "pacing:shots")).toBe("low");
    expect(sev(r, "pacing:variety")).toBe("low");
    expect(r.facts.medianShot).toBe(6);
  });

  it("does not count a split left in place as a cut", () => {
    const p = newProject("split", 1080, 1920, 30);
    const v = asset(p, "video", 120);
    shot(p, v, 0, 2, 10);
    shot(p, v, 2, 2, 12);
    expect(lens(p).facts.shots).toBe(1);
    expect(lens(p).facts.cuts).toBe(0);
  });

  it("ignores text, overlays and audio when working out shots", () => {
    const { p, ctx } = good();
    const ov = newClip("media", 2, 3, { assetId: p.assets[0]!.id, transform: { scale: 0.4 } });
    V2(p).clips.push(ov);
    const r = lens(p, ctx);
    expect(r.facts.shots).toBe(5);
    expect(r.facts.cuts).toBe(4);
  });

  it("flags a long unchanged stretch: high on a still image, low on footage without picture samples", () => {
    const p = newProject("still", 1080, 1920, 30);
    const v = asset(p, "video", 60);
    shot(p, v, 0, 1, 0);
    const img = asset(p, "image", 0);
    shot(p, img, 1, 10, 0);
    music(p, 0, 11, { fadeOut: 1 });
    expect(sev(lens(p, { platform: "instagram-reel" }), "pacing:still")).toBe("high");
    V1(p).clips[1]!.assetId = v.id;
    V1(p).clips[1]!.in = 20;
    const r = lens(p, { platform: "instagram-reel" });
    expect(sev(r, "pacing:still")).toBe("low");
    expect(find(r, "pacing:still")!.detail).toMatch(/not measured/);
    // Picture samples showing movement clear it.
    const frames = Array.from({ length: 23 }, (_, i) => ({ t: i * 0.5, luma: 0.5, diffPrev: 0.1 }));
    expect(sev(lens(p, { platform: "instagram-reel", frames }), "pacing:still")).toBe("good");
  });

  it("checks cuts against beats within 80 ms", () => {
    const { p, ctx } = good();
    expect(sev(lens(p, ctx), "pacing:beats")).toBe("good");
    expect(lens(p, ctx).facts.cutsOnBeat).toBe(1);
    const off = lens(p, { ...ctx, beats: [0.5, 1.5, 2.5, 3.5, 5, 6, 7] });
    expect(sev(off, "pacing:beats")).toBe("low");
    const near = lens(p, { ...ctx, beats: [1.27, 3.07, 4.07, 6.57] });
    expect(sev(near, "pacing:beats")).toBe("good");
    const far = lens(p, { ...ctx, beats: [1.29, 3.09, 4.09, 6.59] });
    expect(sev(far, "pacing:beats")).toBe("low");
  });

  it("checks the total length against the platform", () => {
    const p = newProject("long", 1080, 1920, 30);
    const v = asset(p, "video", 400);
    for (let i = 0; i < 100; i++) shot(p, v, i * 2, 2, i * 3);
    expect(sev(lens(p, { platform: "instagram-reel" }), "pacing:length")).toBe("high");
    expect(sev(lens(p, { platform: "tiktok" }), "pacing:length")).toBe("medium");
    expect(sev(lens(p, { platform: "youtube-shorts" }), "pacing:length")).toBe("high");
    expect(find(lens(p, { platform: "youtube" }), "pacing:length")).toBeUndefined();
    const { p: g, ctx } = good();
    expect(sev(lens(g, ctx), "pacing:length")).toBe("good");
  });
});

describe("text", () => {
  it("flags text too quick to read and lengthens it when there is room", () => {
    const { p, ctx, clips } = good();
    clips.t1!.text!.text = "one two three four five six seven eight";
    clips.t1!.dur = 1;
    clips.t1!.text!.size = 60;
    const r = lens(p, ctx);
    expect(sev(r, `text:read:${clips.t1!.id}`)).toBe("high");
    apply(p, fixOf(r, `text:read:${clips.t1!.id}`).commands);
    expect(clips.t1!.dur).toBeCloseTo(2, 6);
    const r2 = lens(p, ctx);
    expect(find(r2, `text:read:${clips.t1!.id}`)).toBeUndefined();
    expect(sev(r2, "text:read")).toBe("good");
    expect(r2.score).toBeGreaterThan(r.score);
  });

  it("gives no length fix when the next clip on the track is in the way", () => {
    const { p, ctx, clips } = good();
    clips.t1!.text!.text = "one two three four five six seven eight";
    clips.t1!.dur = 1;
    text(p, "next", 1.6, 0.9, { size: 60 }, { transform: { y: -400 } });
    const f = find(lens(p, ctx), `text:read:${clips.t1!.id}`)!;
    expect(f.severity).toBe("high");
    expect(f.fix).toBeUndefined();
  });

  it("uses at least 5/6 s for a single word", () => {
    const { p, ctx, clips } = good();
    clips.t1!.text!.text = "Wow";
    clips.t1!.dur = 0.7;
    expect(sev(lens(p, ctx), `text:read:${clips.t1!.id}`)).toBe("medium");
    clips.t1!.dur = 0.84;
    expect(find(lens(p, ctx), `text:read:${clips.t1!.id}`)).toBeUndefined();
  });

  it("flags small text and the fix enlarges it", () => {
    const { p, ctx, clips } = good();
    clips.t1!.text!.size = 30;
    let r = lens(p, ctx);
    expect(sev(r, `text:size:${clips.t1!.id}`)).toBe("medium");
    clips.t1!.text!.size = 20;
    r = lens(p, ctx);
    expect(sev(r, `text:size:${clips.t1!.id}`)).toBe("high");
    apply(p, fixOf(r, `text:size:${clips.t1!.id}`).commands);
    expect(clips.t1!.text!.size).toBe(40);
    expect(clips.t1!.text!.text).toBe("Wait for it");
    const r2 = lens(p, ctx);
    expect(sev(r2, "text:size")).toBe("good");
    expect(r2.score).toBeGreaterThan(r.score);
  });

  it("offers no enlarge fix when the bigger text would reach the buttons", () => {
    const { p, ctx, clips } = good();
    clips.t1!.text!.size = 30;
    clips.t1!.text!.text = "Wait for it, there is a lot more coming";
    clips.t1!.transform.y = 270; // just inside the bottom of the Reels safe area at size 30
    const r = lens(p, ctx);
    expect(find(r, `safe-zone:text:${clips.t1!.id}`)).toBeUndefined();
    const f = find(r, `text:size:${clips.t1!.id}`)!;
    expect(f.severity).toBe("medium");
    expect(f.fix).toBeUndefined();
  });

  it("does not lengthen text past the end of the picture", () => {
    const { p, ctx, clips } = good();
    clips.t2!.text!.text = "one two three four five six seven eight nine ten?";
    const f = find(lens(p, ctx), `text:read:${clips.t2!.id}`)!;
    expect(f.severity).toBe("medium");
    expect(f.fix).toBeUndefined();
  });

  it("counts scale when judging size", () => {
    const { p, ctx, clips } = good();
    clips.t1!.transform.scale = 0.25;
    expect(sev(lens(p, ctx), `text:size:${clips.t1!.id}`)).toBe("medium");
  });

  it("flags text without outline, shadow or background (stronger on bright footage)", () => {
    const { p, ctx, clips } = good();
    clips.t1!.text!.shadow = 0;
    const r = lens(p, { ...ctx, frames: undefined });
    expect(sev(r, `text:contrast:${clips.t1!.id}`)).toBe("low");
    const bright = ctx.frames!.map((f) => ({ ...f, luma: 0.8 }));
    const r2 = lens(p, { ...ctx, frames: bright });
    expect(sev(r2, `text:contrast:${clips.t1!.id}`)).toBe("medium");
    apply(p, fixOf(r2, `text:contrast:${clips.t1!.id}`).commands);
    expect(clips.t1!.text!.shadow).toBe(12);
    expect(sev(lens(p, ctx), "text:contrast")).toBe("good");
  });

  it("flags two texts on top of each other", () => {
    const { p, ctx, clips } = good();
    const o = text(p, "Same place", 1, 1.5, {}, {}, V1(p));
    V1(p).clips.splice(V1(p).clips.indexOf(o), 1);
    p.tracks.unshift({ ...V2(p), id: "trkX", clips: [o] });
    const r = lens(p, ctx);
    const f = r.findings.find((x) => x.id.startsWith("text:overlap:"));
    expect(f?.severity).toBe("medium");
    expect(f?.clipIds).toContain(clips.t1!.id);
    o.transform.y = -500;
    expect(sev(lens(p, ctx), "text:overlap")).toBe("good");
  });

  it("flags many lines at once", () => {
    const { p, ctx, clips } = good();
    clips.t1!.text!.text = "one\ntwo\nthree\nfour";
    clips.t1!.text!.size = 60;
    const r = lens(p, ctx);
    expect(sev(r, "text:lines")).toBe("medium");
    expect(r.facts.maxTextLines).toBe(4);
  });

  it("uses measureText when given", () => {
    const { p, ctx, clips } = good();
    const wide = lens(p, { ...ctx, measureText: () => ({ w: 1060, h: 100 }) });
    expect(sev(wide, `safe-zone:text:${clips.t1!.id}`)).toBeDefined();
    const narrow = lens(p, { ...ctx, measureText: () => ({ w: 100, h: 100 }) });
    expect(sev(narrow, "safe-zone:text")).toBe("good");
  });

  it("estimates text size from the style, scaled with the project height", () => {
    const st = defaultText("ab");
    const a = estimateTextSize(st, 1920);
    expect(a.w).toBeCloseTo(2 * 0.55 * 120, 6);
    expect(a.h).toBeCloseTo(120 * 1.2, 6);
    const b = estimateTextSize(st, 960);
    expect(b.w).toBeCloseTo(a.w / 2, 6);
    expect(estimateTextSize({ ...st, text: "a\nb" }, 1920).h).toBeCloseTo(2 * 144, 6);
  });
});

describe("safe zone", () => {
  it("flags text under the buttons and the move fixes it", () => {
    const { p, ctx, clips } = good();
    clips.t1!.transform.y = 800;
    const r = lens(p, ctx);
    const id = `safe-zone:text:${clips.t1!.id}`;
    expect(sev(r, id)).toBe("high");
    const fix = fixOf(r, id);
    expect(fix.commands).toEqual([{ op: "clip.value", id: clips.t1!.id, prop: "y", v: expect.any(Number) as number }]);
    apply(p, fix.commands);
    const r2 = lens(p, ctx);
    expect(find(r2, id)).toBeUndefined();
    expect(sev(r2, "safe-zone:text")).toBe("good");
    expect(r2.score).toBeGreaterThan(r.score);
    // Minimal move: the box's bottom lands just inside the safe area.
    const box = estimateTextSize(clips.t1!.text!, 1920);
    const bottom = 960 + Number(clips.t1!.transform.y) + box.h / 2;
    expect(bottom).toBeLessThan(1248);
    expect(bottom).toBeGreaterThan(1245);
  });

  it("moves sideways too, honouring scale", () => {
    const { p, ctx, clips } = good();
    clips.t2!.transform.x = 400;
    clips.t2!.transform.scale = 1.2;
    const r = lens(p, ctx);
    const id = `safe-zone:text:${clips.t2!.id}`;
    expect(sev(r, id)).toBeDefined();
    const cmds = fixOf(r, id).commands;
    expect(cmds.map((c) => c.prop)).toEqual(["x"]);
    apply(p, cmds);
    expect(find(lens(p, ctx), id)).toBeUndefined();
  });

  it("gives no move when it would land on another text", () => {
    const { p, ctx, clips } = good();
    clips.t1!.transform.y = 800;
    // A text sitting right where the move would put t1, at the same time.
    const other = text(p, "Wait for it", 0.5, 2.5, {}, { transform: { y: 200 } }, V1(p));
    V1(p).clips.splice(V1(p).clips.indexOf(other), 1);
    p.tracks.unshift({ ...V2(p), id: "trkY", clips: [other] });
    const f = find(lens(p, ctx), `safe-zone:text:${clips.t1!.id}`)!;
    expect(f.severity).toBe("high");
    expect(f.fix).toBeUndefined();
  });

  it("gives no move for keyframed positions", () => {
    const { p, ctx, clips } = good();
    clips.t1!.transform.y = [{ t: 0, v: 800, ease: "linear" }, { t: 2.5, v: 820, ease: "linear" }];
    const f = find(lens(p, ctx), `safe-zone:text:${clips.t1!.id}`)!;
    expect(f).toBeDefined();
    expect(f.fix).toBeUndefined();
  });

  it("depends on the platform", () => {
    const { p, ctx, clips } = good();
    clips.t1!.transform.y = 400; // box bottom ≈ 1432: inside TikTok's 1436, outside Reels' 1248.
    expect(sev(lens(p, { ...ctx, platform: "tiktok" }), "safe-zone:text")).toBe("good");
    expect(sev(lens(p, ctx), `safe-zone:text:${clips.t1!.id}`)).toBeDefined();
  });
});

describe("sound", () => {
  it("flags no audio at all", () => {
    const { p, ctx } = good();
    A1(p).clips = [];
    V1(p).muted = true; // the video clips' own sound is muted too
    const r = lens(p, ctx);
    expect(sev(r, "sound:none")).toBe("high");
    expect(find(r, "sound:music")).toBeUndefined();
    expect(sev(lens(p, { ...ctx, platform: "youtube" }), "sound:none")).toBe("medium");
  });

  it("treats muted tracks as silent", () => {
    const { p, ctx } = good();
    A1(p).muted = true;
    V1(p).muted = true;
    expect(sev(lens(p, ctx), "sound:none")).toBe("high");
  });

  it("flags camera sound without music, and finds music when present", () => {
    const { p, ctx } = good();
    expect(sev(lens(p, ctx), "sound:music")).toBe("good");
    A1(p).clips = [];
    const r = lens(p, ctx);
    expect(find(r, "sound:none")).toBeUndefined();
    expect(r.facts.audioClips).toBe(5);
    expect(sev(r, "sound:music")).toBe("low");
  });

  it("counts voice assets as voice, not music", () => {
    const { p, ctx, clips } = good();
    p.assets.find((a) => a.id === clips.m!.assetId)!.createdBy = "voice";
    const r = lens(p, ctx);
    expect(sev(r, "sound:music")).toBe("low");
    expect(r.facts.voiceClips).toBe(1);
  });

  it("flags music cut off without a fade, and the fix adds one", () => {
    const { p, ctx, clips } = good();
    clips.m!.fadeOut = 0;
    const r = lens(p, ctx);
    const id = `sound:fadeout:${clips.m!.id}`;
    expect(sev(r, id)).toBe("medium");
    apply(p, fixOf(r, id).commands);
    expect(clips.m!.fadeOut).toBe(1);
    const r2 = lens(p, ctx);
    expect(sev(r2, "sound:fades")).toBe("good");
    expect(r2.score).toBeGreaterThan(r.score);
  });

  it("does not ask for a fade when the song ends by itself", () => {
    const { p, ctx, clips } = good();
    clips.m!.fadeOut = 0;
    p.assets.find((a) => a.id === clips.m!.assetId)!.duration = 8;
    expect(sev(lens(p, ctx), "sound:fades")).toBe("good");
  });

  it("flags sound starting mid-waveform, fix is a short fade-in", () => {
    const { p, ctx, clips } = good();
    clips.m!.in = 12;
    const r = lens(p, ctx);
    const id = `sound:fadein:${clips.m!.id}`;
    expect(sev(r, id)).toBe("low");
    apply(p, fixOf(r, id).commands);
    expect(clips.m!.fadeIn).toBe(0.05);
    expect(find(lens(p, ctx), id)).toBeUndefined();
  });

  it("checks loudness and peaks when measured", () => {
    const { p, ctx } = good();
    expect(sev(lens(p, ctx), "sound:loudness")).toBe("good");
    expect(sev(lens(p, { ...ctx, loudness: { integratedLufs: -24 } }), "sound:loudness")).toBe("medium");
    expect(sev(lens(p, { ...ctx, loudness: { integratedLufs: -6 } }), "sound:loudness")).toBe("low");
    expect(sev(lens(p, ctx), "sound:peak")).toBe("good");
    expect(sev(lens(p, { ...ctx, loudness: { truePeakDb: 0.3 } }), "sound:peak")).toBe("medium");
    expect(find(lens(p, { ...ctx, loudness: undefined }), "sound:loudness")).toBeUndefined();
  });

  it("flags long silences, measured or from the timeline", () => {
    const { p, ctx, clips } = good();
    expect(sev(lens(p, ctx), "sound:silence")).toBe("good");
    expect(sev(lens(p, { ...ctx, loudness: { silences: [{ start: 2, end: 4 }] } }), "sound:silence")).toBe("medium");
    expect(sev(lens(p, { ...ctx, loudness: { silences: [{ start: 2, end: 3 }] } }), "sound:silence")).toBe("good");
    clips.m!.start = 3;
    clips.m!.dur = 5;
    // The video clips' own sound covers the start, so there is no silence until it is muted.
    expect(sev(lens(p, ctx), "sound:silence")).toBe("good");
    V1(p).muted = true;
    const r = lens(p, ctx);
    expect(sev(r, "sound:silence")).toBe("medium");
    expect(find(r, "sound:silence")!.at).toBe(0);
  });
});

describe("ending", () => {
  it("finds a question or a prompt, in English, Hindi and Hinglish", () => {
    const { p, ctx, clips } = good();
    expect(sev(lens(p, ctx), "ending:prompt")).toBe("good");
    for (const s of ["कमेंट में बताओ", "Kaunsa best hai, batao", "Follow for part 2", "Comment karo"]) {
      clips.t2!.text!.text = s;
      expect(sev(lens(p, ctx), "ending:prompt"), s).toBe("good");
    }
    for (const s of ["The end", "Commentary by me", "Shared memories"]) {
      clips.t2!.text!.text = s;
      expect(sev(lens(p, ctx), "ending:prompt"), s).toBe("low");
    }
    expect(PROMPT_WORDS).toContain("batao");
    expect(PROMPT_WORDS).toContain("likho");
  });

  it("checks the loop on short-form platforms", () => {
    const { p, ctx } = good();
    expect(sev(lens(p, ctx), "ending:loop")).toBe("good");
    expect(sev(lens(p, { ...ctx, loopDiff: 0.7 }), "ending:loop")).toBe("low");
    expect(find(lens(p, { ...ctx, loopDiff: undefined }), "ending:loop")).toBeUndefined();
  });

  it("flags an abrupt end on YouTube and the fix fades it out", () => {
    const p = newProject("yt", 1920, 1080, 30);
    const v = asset(p, "video", 120, { width: 1920, height: 1080 });
    shot(p, v, 0, 3, 0);
    shot(p, v, 3, 4, 10);
    const r = lens(p);
    expect(r.platform).toBe("youtube");
    expect(sev(r, "ending:fade")).toBe("low");
    apply(p, fixOf(r, "ending:fade").commands);
    expect(V1(p).clips[1]!.transitionOut).toEqual({ kind: "fade", dur: 0.5 });
    expect(sev(lens(p), "ending:fade")).toBe("good");
  });
});

describe("technical", () => {
  it("checks the frame shape", () => {
    const p = newProject("h", 1920, 1080, 30);
    shot(p, asset(p, "image", 0), 0, 3, 0);
    expect(sev(lens(p, { platform: "tiktok" }), "technical:shape")).toBe("high");
    expect(sev(lens(p, { platform: "youtube" }), "technical:shape")).toBe("good");
    const sq = newProject("sq", 1080, 1080, 30);
    shot(sq, asset(sq, "image", 0), 0, 3, 0);
    expect(sev(lens(sq, { platform: "youtube-shorts" }), "technical:shape")).toBe("low");
    expect(sev(lens(sq, { platform: "instagram-reel" }), "technical:shape")).toBe("high");
  });

  it("checks the frame size and rate", () => {
    const p = newProject("small", 360, 640, 15);
    shot(p, asset(p, "image", 0), 0, 3, 0);
    const r = lens(p, { platform: "tiktok" });
    expect(sev(r, "technical:size")).toBe("high");
    expect(sev(r, "technical:fps")).toBe("medium");
    const q = newProject("mid", 720, 1280, 30);
    shot(q, asset(q, "image", 0), 0, 3, 0);
    expect(sev(lens(q, { platform: "youtube-shorts" }), "technical:size")).toBe("low");
    expect(sev(lens(q, { platform: "youtube-shorts" }), "technical:fps")).toBe("good");
    const { p: g, ctx } = good();
    expect(sev(lens(g, ctx), "technical:size")).toBe("good");
  });

  it("flags black gaps inside the timeline", () => {
    const { p, ctx, clips } = good();
    clips.s3!.dur = 0.5; // 3.5 → 4.0 has no picture
    const r = lens(p, ctx);
    expect(sev(r, "technical:gaps")).toBe("medium");
    expect(find(r, "technical:gaps")!.at).toBe(3.5);
  });

  it("flags sound that runs on after the picture, the trim fixes it", () => {
    const { p, ctx, clips } = good();
    clips.m!.dur = 12;
    clips.m!.fadeOut = 0;
    const r = lens(p, ctx);
    expect(sev(r, "technical:soundOverBlack")).toBe("medium");
    const { commands, applied } = evolve(p, r);
    expect(applied).toContain("technical:soundOverBlack");
    expect(applied).toContain(`sound:fadeout:${clips.m!.id}`);
    apply(p, commands);
    expect(clips.m!.dur).toBeCloseTo(8, 6);
    const r2 = lens(p, ctx);
    expect(find(r2, "technical:soundOverBlack")).toBeUndefined();
    expect(bad(r2)).toEqual([]);
    expect(r2.score).toBeGreaterThan(r.score);
  });

  it("flags sound that ends long before the picture", () => {
    const { p, ctx, clips } = good();
    clips.m!.dur = 5;
    expect(sev(lens(p, ctx), "technical:soundEnds")).toBe("low");
  });
});

describe("attention", () => {
  it("starts at 1, never rises and stays in 0..1", () => {
    const projects = [good(), (() => {
      const g = good();
      V1(g.p).clips = [];
      shot(g.p, asset(g.p, "image", 0), 0, 20, 0);
      return g;
    })()];
    for (const { p, ctx } of projects) {
      const a = lens(p, ctx).attention;
      expect(a[0]).toEqual({ t: 0, v: 1 });
      expect(a[a.length - 1]!.t).toBeCloseTo(Number(lens(p, ctx).facts.duration), 6);
      for (let i = 1; i < a.length; i++) {
        expect(a[i]!.v).toBeLessThanOrEqual(a[i - 1]!.v);
        expect(a[i]!.v).toBeGreaterThanOrEqual(0);
        expect(a[i]!.t).toBeGreaterThan(a[i - 1]!.t);
      }
    }
  });

  it("predicts more viewers lost with a weak hook", () => {
    const { p, ctx, clips } = good();
    const strong = lens(p, ctx).attention;
    clips.s1!.transitionIn = { kind: "fade", dur: 2 };
    const weak = lens(p, ctx).attention;
    const at = (a: { t: number; v: number }[], t: number) => a.find((x) => Math.abs(x.t - t) < 1e-6)!.v;
    expect(at(weak, 1.5)).toBeLessThan(at(strong, 1.5));
  });
});

describe("evolve", () => {
  function messy(): { p: Project; ctx: LensContext; clips: Record<string, Clip> } {
    const g = good();
    g.clips.s1!.transitionIn = { kind: "fade", dur: 1.5 };
    g.clips.t1!.transform.y = 800;
    g.clips.t1!.text!.shadow = 0;
    g.clips.t2!.text!.size = 30;
    g.clips.m!.fadeOut = 0;
    g.clips.m!.in = 5;
    return g;
  }

  it("applies every safe fix at once and the score rises", () => {
    const { p, ctx } = messy();
    const r = lens(p, ctx);
    const { commands, applied } = evolve(p, r);
    expect(applied.length).toBeGreaterThanOrEqual(6);
    apply(p, commands);
    const r2 = lens(p, ctx);
    expect(r2.score).toBeGreaterThan(r.score);
    for (const id of applied) expect(find(r2, id)?.severity ?? "good", id).toBe("good");
  });

  it("never creates a new problem", () => {
    const { p, ctx } = messy();
    const r = lens(p, ctx);
    const before = new Set(bad(r).map((f) => f.id));
    apply(p, evolve(p, r).commands);
    for (const f of bad(lens(p, ctx))) expect(before.has(f.id), f.id).toBe(true);
  });

  it("orders fixes: sound, then text, then positions, then the opening", () => {
    const { p, ctx } = messy();
    const { applied } = evolve(p, lens(p, ctx));
    const area = (id: string) => id.split(":")[0]!;
    const rank = ["technical", "sound", "text", "safe-zone", "hook", "pacing", "ending"];
    for (let i = 1; i < applied.length; i++) expect(rank.indexOf(area(applied[i]!))).toBeGreaterThanOrEqual(rank.indexOf(area(applied[i - 1]!)));
  });

  it("never touches the same clip property twice", () => {
    const { p } = good();
    const id = V2(p).clips[0]!.id;
    const mk = (fid: string, v: number): Finding => ({ id: fid, area: "safe-zone", severity: "medium", title: "t", detail: "d", fix: { label: "l", commands: [{ op: "clip.value", id, prop: "y", v }] } });
    const report: LensReport = { platform: "general", score: 0, verdict: "", areas: [], attention: [], facts: {}, findings: [mk("a", 10), mk("b", 20), { ...mk("c", 0), fix: { label: "l", commands: [{ op: "clip.value", id: "missing", prop: "x", v: 1 }] } }] };
    const out = evolve(p, report);
    expect(out.applied).toEqual(["a"]);
    expect(out.commands).toEqual([{ op: "clip.value", id, prop: "y", v: 10 }]);
  });
});

describe("commands are real agent ops", () => {
  it("every op emitted by a fix exists in src/agent/api.ts", () => {
    const api = readFileSync(new URL("../src/agent/api.ts", import.meta.url), "utf8");
    const reports: LensReport[] = [];
    const m = (() => {
      const g = good();
      g.clips.s1!.transitionIn = { kind: "fade", dur: 1.5 };
      g.clips.t1!.transform.y = 800;
      g.clips.t1!.text!.shadow = 0;
      g.clips.t1!.text!.text = "one two three four five six seven eight nine ten eleven";
      g.clips.t2!.text!.size = 20;
      g.clips.m!.dur = 12;
      g.clips.m!.in = 4;
      text(g.p, "Low", 3.2, 1, {}, { transform: { y: 800 } });
      return g;
    })();
    reports.push(lens(m.p, m.ctx));
    const still = newProject("still", 1920, 1080, 30);
    shot(still, asset(still, "image", 0), 0, 5, 0);
    reports.push(lens(still));
    const ops = new Set<string>();
    for (const r of reports) for (const f of r.findings) for (const c of f.fix?.commands ?? []) ops.add(String(c.op));
    expect([...ops].sort()).toEqual(["clip.keys", "clip.set", "clip.transition", "clip.trim", "clip.value"]);
    for (const op of ops) expect(api.includes(`"${op}":`), op).toBe(true);
  });

  it("uses the argument names the ops read", () => {
    const api = readFileSync(new URL("../src/agent/api.ts", import.meta.url), "utf8");
    expect(api).toMatch(/"clip\.value": \{\s*help: "\{id, prop, v\}/);
    expect(api).toMatch(/"clip\.trim": \{ help: "\{id, edge: start\|end, t\}"/);
    expect(api).toMatch(/"clip\.keys": \{\s*help: "\{id, prop, keys:\[\[t,v,ease\?\],\.\.\.\]\}/);
    expect(api).toMatch(/"clip\.set": \{\s*help: "\{id, patch:/);
    expect(api).toMatch(/"clip\.transition": \{\s*help: `\{id, in\?: kind, out\?: kind, dur\?=0\.5\}/);
  });
});

describe("wording", () => {
  it("never promises views or likes and speaks as guidance", () => {
    const { p, ctx } = messyAll();
    const r = lens(p, ctx);
    const all = [r.verdict, ...r.areas.map((a) => a.title), ...r.findings.flatMap((f) => [f.title, f.detail, f.fix?.label ?? ""])].join("\n");
    expect(all).not.toMatch(/guarantee|will go viral|you will get|more views|more likes/i);
    for (const f of bad(r)) expect(`${f.title} ${f.detail}`, f.id).toMatch(/likely|often|may|usually|probably|seems|could/i);
  });
});

function messyAll(): { p: Project; ctx: LensContext } {
  const g = good();
  g.clips.s1!.transitionIn = { kind: "fade", dur: 1.5 };
  g.clips.t1!.transform.y = 800;
  g.clips.t1!.text!.shadow = 0;
  g.clips.t1!.text!.text = "one two three four five six seven eight nine ten eleven\nb\nc\nd";
  g.clips.t2!.text!.size = 20;
  g.clips.t2!.text!.text = "The end";
  g.clips.m!.dur = 12;
  g.clips.m!.in = 4;
  g.clips.s3!.dur = 0.5;
  return { p: g.p, ctx: { ...g.ctx, loopDiff: 0.9, beats: [0.3], loudness: { integratedLufs: -25, truePeakDb: 0.5, silences: [{ start: 1, end: 4 }] }, frames: g.ctx.frames!.map((f, i) => (i === 0 ? { ...f, luma: 0.02 } : { ...f, luma: 0.9 })) } };
}
