import { describe, expect, it } from "vitest";
import { leadFor, lineOver, linePass, lineStart, pauseNear, withoutLead } from "../src/voice/engine/studio_lead";
import { cleanPass } from "../src/voice/engine/studio_pass";

// The studio voice spoils the beginning of whatever it is given: for the first third of a second after its reference
// clip it hurries and swallows sounds ("Make a world" came out "Take a world" in eight takes of eight, "He is not a
// man" lost its "He", Hindi lines began "आ…"). So a line is given a short sentence to spoil instead ("Listen."), and
// that sentence is cut away: at the pause that lies where the model's attention comes to the line's first word.
const T = (s: string) => s.trim().split(/\s+/).map(Number);
const SR = 24000;
/** Sound (a tone at the given level) and silence, in seconds. */
function clip(...parts: Array<[number, number]>): Float32Array {
  const out: number[] = [];
  for (const [sec, level] of parts) for (let i = 0, n = Math.round(sec * SR); i < n; i++) out.push(level * Math.sin((2 * Math.PI * 200 * out.length) / SR));
  return Float32Array.from(out);
}
const peak = (x: Float32Array, from: number, to: number) => {
  let m = 0;
  for (let i = Math.max(0, Math.round(from * SR)); i < Math.min(x.length, Math.round(to * SR)); i++) m = Math.max(m, Math.abs(x[i]!));
  return m;
};

describe("the sentence a studio line is given to spoil", () => {
  it("is a short one in the line's own language, and a whole one", () => {
    // (after a single word the model often runs on into the line: "सुनो। नहीं, जब नदी…" came out "सुनो नहीं," in
    //  three takes of three, and paused after its lead in three of three when the lead was a whole sentence)
    expect(leadFor("Make a world where no child has to run.", "en")).toBe("Listen to me. ");
    expect(leadFor("नहीं। जब नदी चढ़ती है।", "hi")).toBe("मेरी बात सुनो। ");
    expect(leadFor("Main yahin hoon", "hi")).toBe(""); // Hindi in English letters, and languages not tried: left as they are
    expect(leadFor("Guten Morgen.", "de")).toBe("");
    expect(leadFor("   ", "en")).toBe("");
  });

  it("ends where the attention leaves the lead for the last time (real takes)", () => {
    // "Listen. Make a world, …": the line's first token is the ninth (position 8)
    expect(lineStart(T("2 5 5 5 5 5 6 6 6 6 6 6 6 8 8 9 9 9 11 11 11 13 13 14 14 14"), 8)).toBe(13);
    // Hindi "सुनो। नहीं, जब नदी …": it looks ahead for a frame and comes back before it gets there
    expect(lineStart(T("2 3 3 3 3 5 5 5 5 5 8 6 6 8 8 8 8 8 8 8 8 8 8 8 10 10 14 14"), 8)).toBe(13);
    expect(lineStart(T("2 2 3 3 5 5 5 5 5 5 8 6 8 8 11 11 11 11"), 8)).toBe(12);
    // it looks ahead for three frames, goes back to the lead's full stop for the pause, and then comes to the line:
    // the pause (0.35 s of silence) lay at the second coming, a fifth of a second after the first
    expect(lineStart(T("2 3 3 3 8 8 8 6 8 6 6 8 8 8 8 8 8 8 8 10 10 10 10 14 14 14"), 8)).toBe(11);
    // it wanders over the lead for half a second first
    expect(lineStart(T("2 5 3 6 7 5 6 2 5 3 6 6 5 5 6 10 10 10 12 12 12 14"), 8)).toBe(15);
    // a glance at the end of the text is not the line: after "Listen." it looked there, went back to the start for
    // the pause, and only then came to "make"
    expect(lineStart(T("2 5 3 6 6 6 5 5 2 32 32 32 32 2 2 2 7 2 2 2 2 2 3 7 20 20 9 13 9 9 9 9 13 13 13 14"), 8)).toBe(24);
    // babble somewhere else before the lead
    expect(lineStart(T("9 9 9 2 3 3 5 5 8 8 9"), 8)).toBe(8);
    // from a frame on: an earlier lead (said, and then everything begun again) is not the one
    expect(lineStart(T("2 3 5 5 8 8 9 2 3 3 5 5 5 8 8 9 9"), 8, 7)).toBe(13);
    // a glance back at the lead once the line is well under way is not a new beginning
    expect(lineStart(T("2 3 5 5 8 8 9 9 10 10 12 12 13 13 14 14 15 2 16 16"), 8)).toBe(4);
    expect(lineStart(T("2 3 3 3 5 5 5 6 6 6"), 8)).toBe(-1); // it never came to the line
    expect(lineStart(T("9 9 10 10 12 12 14 14"), 8)).toBe(-1); // it was never on the lead: nothing to go by
    expect(lineStart([], 8)).toBe(-1);
  });

  // "Listen. They brought ten thousand men, to kill one boy, and his promise." (47 text positions, the line from the
  // ninth): over the lead and the pause after it the attention wanders and looks ahead (2 5 6 5 8 6 7 7 12 2 15 …),
  // then goes back to the start for the pause (2 2 2 2 2 2), which is no fault of the line: the line itself (from
  // frame 24) was said in one clean pass.
  const O4 = T("2 5 6 5 8 6 7 7 12 2 15 6 5 5 6 6 6 6 2 2 2 2 2 2 8 8 8 8 10 10 10 10 12 12 12 14 16 15 15 18 18 18 18 19 19 19 19 19 19 19 21 22 22 22 25 25 25 25 28 28 28 30 30 30 30 32 32 32 32 34 33 33 33 36 36 38 38 38 40 41 41 43 43 43 43 45 45 45 45 45 45 46 46 46 46 46 46 46 46 46 46 46 46 46 46 46 46 46 46 46 46");
  // Hindi "सुनो। वो आदमी नहीं है।" (25 positions): the lead was skipped (two frames), the line said at once, and then,
  // after two seconds of nothing, lead and line were said again, up to the limit
  const TWICE = T("2 2 9 11 11 12 13 11 14 14 16 16 16 16 18 18 18 18 18 18 23 22 22 22 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 24 1 24 24 24 1 1 1 1 1 1 24 24 1 1 1 1 24 24 24 1 1 1 1 1 24 24 1 2 1 1 1 24 1 1 2 1 1 24 1 1 1 1 2 2 2 2 3 3 1 1 1 1 1 1 1 2 2 1 1 1 1 2 1 2 2 3 3 3 2 2 1 3 1 3 3 3 3 3 1 2 2 3 3 3 3 2 2 4 4 3 5 5 5 5 5 6 6 6 6 8 8 8 11 11 11 12 14 14 13 13 14 14 16 16 18 17 18 18 18 18 22 22 22 23 23");
  // Hindi "सुनो। ताज उठाया जाता है, उन सबके लिए जो ख़ुद नहीं उठा सकते।" (62 positions): four seconds of murmur with the
  // attention on the language tag and glances into the text, a few words out of the middle, and then lead and line
  const MURMUR = T("1 2 2 2 2 2 2 2 1 1 1 1 1 3 3 4 1 1 1 1 59 21 1 1 12 1 1 1 1 10 1 1 2 1 1 2 2 2 2 2 1 1 11 1 11 1 1 1 1 1 1 1 1 1 1 40 40 6 1 1 1 1 1 20 1 1 1 1 1 1 2 1 1 12 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 17 25 25 25 25 25 27 26 26 1 17 17 1 1 1 1 1 1 1 27 27 1 1 1 1 1 2 27 1 4 4 3 3 5 5 5 5 5 6 6 6 8 8 8 8 8 8 8 8 8 8 9 9 9 12 12 12 12 12 14 14 14 16 16 16 18 18 18 18 20 21 21 21 21 21 21 21 21 27 24 27 27 27 30 30 30 30 31 31 32 33 33 33 33 35 37 37 37 39 39 39 42 42 42 42 44 44 47 47 47 47 47 49 48 52 52 52 54 54 54 54 54 54 56 58 57 57 57 59 59 59 59 59 59 60 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61 61");

  it("judges a take by the line, not by what the model did with the lead", () => {
    expect(cleanPass(O4, 47)!.dirt).toBeGreaterThan(0); // the whole take, lead and all: not clean
    const p = linePass(O4, 47, 8)!;
    expect(p.dirt).toBe(0);
    expect(p.start).toBe(24); // where the line begins
    expect(p.end).toBeGreaterThanOrEqual(80);
    expect(p.end).toBeLessThanOrEqual(92);
    // making the take can stop a while after the line was said, not while it is being said
    const over = (n: number) => lineOver(O4.slice(0, n), 47, 8);
    for (const n of [10, 24, 40, 80, 92]) expect(over(n)).toBe(false);
    expect(over(110)).toBe(true);
    expect(linePass(T("2 3 3 3 5 5 5 6 6 6"), 47, 8)).toBeNull(); // the line never began
  });

  it("stops a take that skipped the lead before it says everything again", () => {
    // (the line was through by frame 24: half a second later it is over, long before the second saying at frame 84)
    expect(lineOver(TWICE.slice(0, 46), 25, 8)).toBe(true);
    // and such a take has nothing before the line to cut: it begins at once (it is made again)
    expect(linePass(TWICE.slice(0, 46), 25, 8)!.start).toBe(2);
  });

  it("finds the line after murmur and a false start", () => {
    const p = linePass(MURMUR, 62, 8)!;
    expect(p.dirt).toBe(0);
    expect(p.start).toBe(133); // after the lead that was really said (frames 121–132), not at the words before it
    expect(lineOver(MURMUR.slice(0, 120), 62, 8)).toBe(false);
    // babble somewhere in the text, and then the line with no lead before it: it begins where its pass begins
    const p2 = linePass(T("2 3 3 30 30 30 30 30 30 30 30 30 30 30 8 8 9 9 11 11 13 13 15 15 17 17 19 19 21 21 23 23 24 24 24 24"), 26, 8)!;
    expect(p2.start).toBe(14);
  });

  it("finds the pause after the lead: the quiet where the line is about to begin", () => {
    // lead 0.05–0.45 s, pause, the line from 0.65 s; the attention comes to the line at 0.52 s
    const x = clip([0.05, 0], [0.4, 0.4], [0.2, 0], [0.85, 0.4]);
    const p = pauseNear(x, Math.round(0.52 * SR), SR)!;
    expect(p.from / SR).toBeCloseTo(0.45, 1);
    expect(p.to / SR).toBeCloseTo(0.65, 1);
    // the attention often comes to the line while the lead is still sounding (the pause begins a little later) …
    expect(pauseNear(x, Math.round(0.36 * SR), SR)).not.toBeNull();
    // … but not after the pause is over: sound between the pause and the line is something said that is not the
    // line ("Listen. … and weigh a world": the pause ended a tenth of a second before the attention came to "make")
    expect(pauseNear(x, Math.round(0.68 * SR), SR)).not.toBeNull();
    expect(pauseNear(x, Math.round(0.76 * SR), SR)).toBeNull();
    // a click in the middle of a pause does not end it
    const blip = clip([0.05, 0], [0.33, 0.4], [0.07, 0], [0.005, 0.2], [0.2, 0], [0.85, 0.4]);
    expect(pauseNear(blip, Math.round(0.56 * SR), SR)!.to / SR).toBeCloseTo(0.655, 1);
    // the silence before a take's first sound comes after nothing: a line that begins at once (the lead was
    // skipped: "सुनो। वो आदमी नहीं है।" began with "वो" after two frames) has no pause to be cut in
    expect(pauseNear(clip([0.05, 0], [1.2, 0.4]), Math.round(0.08 * SR), SR)).toBeNull();
    // and a click before the line is no lead (a lead is a tenth of a second of sound at the least)
    expect(pauseNear(clip([0.03, 0], [0.04, 0.4], [0.1, 0], [1.2, 0.4]), Math.round(0.12 * SR), SR)).toBeNull();
    // a pause somewhere else in the line is not it
    expect(pauseNear(x, Math.round(1.2 * SR), SR)).toBeNull();
    // a closed consonant inside the lead (40 ms of quiet at 0.2 s) is not it either
    const k = clip([0.05, 0], [0.15, 0.4], [0.04, 0], [0.21, 0.4], [0.2, 0], [0.85, 0.4]);
    expect(pauseNear(k, Math.round(0.52 * SR), SR)!.from / SR).toBeCloseTo(0.45, 1);
    // a dip that is not quiet is no pause, nor is silence too short to be one
    expect(pauseNear(clip([0.45, 0.4], [0.2, 0.1], [0.85, 0.4]), Math.round(0.52 * SR), SR)).toBeNull();
    expect(pauseNear(clip([0.5, 0.4], [0.015, 0], [0.85, 0.4]), Math.round(0.52 * SR), SR)).toBeNull();
    expect(pauseNear(new Float32Array(0), 0, SR)).toBeNull();
  });

  it("cuts the lead away in the middle of that pause", () => {
    const x = clip([0.05, 0], [0.4, 0.4], [0.2, 0], [0.85, 0.4]);
    const r = withoutLead(x, Math.round(0.52 * SR), SR);
    expect(r.clean).toBe(true);
    expect(r.pcm.length / SR).toBeCloseTo(1.5 - 0.55, 1);
    expect(peak(r.pcm, 0, 0.08)).toBe(0); // it begins in the pause: nothing of the lead is left
    expect(peak(r.pcm, 0.12, 0.3)).toBeGreaterThan(0.35); // and the line is whole
  });

  it("with no pause to cut in, cuts at the least loud moment and says the cut is rough", () => {
    // the lead runs into the line with only a dip (at 0.50–0.53 s)
    const x = clip([0.5, 0.4], [0.03, 0.12], [0.97, 0.4]);
    const r = withoutLead(x, Math.round(0.5 * SR), SR);
    expect(r.clean).toBe(false);
    expect(r.pcm.length / SR).toBeGreaterThan(1.5 - 0.54);
    expect(r.pcm.length / SR).toBeLessThan(1.5 - 0.49);
    expect(Math.abs(r.pcm[0]!)).toBeLessThan(0.01); // faded in, no click
    // nothing to go by: the take is left whole
    const none = withoutLead(x, -1, SR);
    expect(none.clean).toBe(false);
    expect(none.pcm.length).toBe(x.length);
  });
});
