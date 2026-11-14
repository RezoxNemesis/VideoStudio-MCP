import { describe, expect, it } from "vitest";
import { cleanPass, tailOver } from "../src/voice/engine/studio_pass";

// The text position the model attended, frame by frame (25 frames a second), in real takes of the studio voice.
const T = (s: string) => s.trim().split(/\s+/).map(Number);
// Hindi, 3 words: it babbles for a second ("ja keshan"), then says the line, then goes on to the token cap
const P5 = { S: 18, cur: T("0 1 2 2 1 1 6 7 8 9 1 1 9 1 1 1 1 1 1 1 16 17 17 2 2 2 2 2 2 2 2 2 2 2 3 5 5 5 5 5 7 9 9 8 9 10 10 10 10 15 13 13 13 14 14 16 16 17 17 17 17 17 16 17 17 17 1 17 17 1 1 17 17 17 17 17 17 17 17 17 1 17 1 17 1 1 1 1 17 17 1 1 16 17 16 17 17 17 17 17 1 17 16 1 1 17 17 17 17 17 17 17 17 17 17 1 1 17 17 17 2 17 17 17 1 1 17 1 1 17 17 17 16 17 17 17 17 17 17 17 17 17 17 17 17 17 17 17") };
// Hindi, 8 words: two seconds stuck on the first word, a rush through the rest, then the line itself
const M9 = { S: 48, cur: T("0 1 2 2 2 2 2 2 1 1 1 1 2 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 7 1 1 2 1 2 2 1 1 1 1 4 1 1 1 1 1 1 1 25 25 27 27 1 28 28 45 1 1 1 45 47 2 47 2 47 2 2 2 4 4 6 6 6 6 7 7 11 9 11 14 11 14 14 16 15 16 16 19 20 19 22 20 22 22 24 22 24 25 25 25 27 28 28 28 33 33 33 33 34 34 34 37 37 39 39 37 39 41 41 41 41 42 42 44 44 44 45 46 47 47 47 47 47 47 47 47 47 47 2 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 1 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47 47") };
// Hindi, 3 words: said at once, then a hundred frames of nothing in particular up to the cap
const K82 = { S: 17, cur: T("0 1 2 2 3 5 5 5 5 5 5 6 10 10 9 10 10 10 14 14 13 13 13 14 14 14 16 16 16 16 16 16 16 16 15 15 2 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 4 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 9 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 9 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 2 1 1 2 2 5 1 1 6 1 1 9 1") };
// Hindi and English lines said cleanly, once
const K81 = { S: 54, cur: T("0 1 2 2 1 1 1 1 1 1 2 4 4 2 2 2 2 4 4 4 3 4 8 4 8 8 8 8 8 8 8 8 8 9 11 11 11 13 12 13 15 15 15 15 16 16 19 18 19 19 19 20 22 22 22 25 25 28 26 30 31 31 31 31 33 31 33 34 34 36 38 36 36 38 41 41 43 43 43 45 45 45 45 45 47 47 47 51 50 50 50 50 51 53 53 53 53 53 53 53 53") };
const O4 = { S: 39, cur: T("0 0 2 2 4 4 6 6 8 9 9 9 9 12 12 12 13 13 13 13 13 13 13 15 16 18 16 18 20 21 21 21 23 23 23 23 23 25 25 25 26 28 28 28 28 30 30 30 30 33 33 35 35 35 35 34 35 37 37 37 37 37 37 38 38 38 38 38 38 38 38 38 38 38 30 30 30 38 38 38 38 38 38 38 38") };

// Hindi, 11 words: three quarters of a second on the first word, then a leap over the next three (they were never said)
const K8ALL = { S: 67, cur: T("0 1 2 1 1 2 1 1 1 2 1 1 1 1 1 1 1 1 1 16 1 16 1 1 19 19 19 22 22 22 22 22 23 23 25 25 25 26 25 28 26 30 31 29 30 31 31 33 33 33 34 34 35 36 35 36 36 36 40 39 41 41 43 43 43 45 45 45 45 45 45 47 47 47 47 53 53 50 53 50 53 53 53 53 53 53 53 56 56 56 56 56 56 59 61 61 61 60 60 61 64 63 65 64 66 66 65 66 66 66 66 66 66 66 66 66 66 66 66 66 66 66 66 66") };

describe("the one clean saying of a line inside what the studio voice made", () => {
  it("a pass that leapt over words is known for what it is: the take is to be made again", () => {
    expect(cleanPass(K8ALL.cur, K8ALL.S)!.dirt).toBeGreaterThan(0);
    for (const t of [P5, M9, K82, K81, O4]) expect(cleanPass(t.cur, t.S)!.dirt).toBe(0);
  });

  it("a line said cleanly once is kept from its start to its end", () => {
    const a = cleanPass(O4.cur, O4.S)!, b = cleanPass(K81.cur, K81.S)!;
    expect(a.from).toBeLessThanOrEqual(1);
    expect(a.end).toBeGreaterThanOrEqual(57);
    expect(a.end).toBeLessThanOrEqual(64);
    expect(b.from).toBeLessThanOrEqual(1);
    expect(b.end).toBeGreaterThanOrEqual(87);
    expect(b.end).toBeLessThanOrEqual(95);
  });

  it("babble before the line is left out: the line is the last clean pass through its words", () => {
    const p = cleanPass(P5.cur, P5.S)!;
    expect(p.from).toBeGreaterThanOrEqual(20); // the babble runs to frame 22
    expect(p.from).toBeLessThanOrEqual(24);
    expect(p.end).toBeGreaterThanOrEqual(53);
    expect(p.end).toBeLessThanOrEqual(58);
    const m = cleanPass(M9.cur, M9.S)!;
    expect(m.from).toBeGreaterThanOrEqual(58); // stuck, then a rush: the line itself begins about frame 66
    expect(m.from).toBeLessThanOrEqual(67);
    expect(m.end).toBeGreaterThanOrEqual(124);
    expect(m.end).toBeLessThanOrEqual(133);
  });

  it("what follows a line that was said is left out", () => {
    const k = cleanPass(K82.cur, K82.S)!;
    expect(k.from).toBeLessThanOrEqual(1);
    expect(k.end).toBeGreaterThanOrEqual(24);
    expect(k.end).toBeLessThanOrEqual(30);
  });

  it("a take that never says the line through has no clean pass", () => {
    expect(cleanPass(T("0 1 2 2 1 1 1 2 1 1 1 1 1 2 2 1 1 1 1 1 1 1 1 1 1 1 1 1 1"), 30)).toBeNull();
    expect(cleanPass(T("0 1 2 3 3 4 4 4"), 5)).toBeNull(); // a word or two is too little to follow
    expect(cleanPass([], 30)).toBeNull();
  });

  it("making a line stops soon after it was said, but not while it is being said again", () => {
    const at = (t: { S: number; cur: number[] }, n: number) => tailOver(t.cur.slice(0, n), t.S);
    // said at frame 26 …: over by frame 50, not yet at frame 34
    expect(at(K82, 34)).toBe(false);
    expect(at(K82, 50)).toBe(true);
    // babble, then the line: never over while the line is under way, over a while after its end
    for (const n of [10, 22, 30, 45, 56]) expect(at(P5, n)).toBe(false);
    expect(at(P5, 80)).toBe(true);
    for (const n of [40, 62, 70, 100, 128]) expect(at(M9, n)).toBe(false);
    expect(at(M9, 152)).toBe(true);
    // a clean line: not before it ends
    for (const n of [20, 50, 62]) expect(at(O4, n)).toBe(false);
    expect(at(O4, 84)).toBe(true);
  });
});
