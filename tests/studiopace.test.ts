import { describe, it, expect } from "vitest";
import { phrases, pauseAfter, paced, pacing, tidyEnd, trimmed, speechSeconds, NATURAL_PACE_HI } from "../src/voice/engine/studio_pace";

// A studio voice acts a line the way it is written, but it knows nothing of the tone's pace: it ran three short
// sentences together in two and a half seconds and swallowed the end of a long one. So a line is spoken a
// sentence at a time, with the silences between them and the pace the tone asks for.
describe("a studio voice keeps the pace of a line", () => {
  it("a line is taken a sentence at a time, each with its own mark", () => {
    expect(phrases("He is not a man. Then he became a story. And today I end it.")).toEqual(["He is not a man.", "Then he became a story.", "And today I end it."]);
    expect(phrases("वो आदमी नहीं है। वो एक कहानी है। और आज मैं उसे ख़त्म करूँगा।")).toEqual(["वो आदमी नहीं है। वो एक कहानी है।", "और आज मैं उसे ख़त्म करूँगा।"]);
    expect(phrases("Wait, wait... is that really him? No — it cannot be him at all.")).toEqual(["Wait, wait... is that really him?", "No — it cannot be him at all."]);
  });
  it("a comma, a number or a title does not break a sentence", () => {
    expect(phrases("When the river rises, a king carries the first sandbag.")).toEqual(["When the river rises, a king carries the first sandbag."]);
    expect(phrases("It cost 3.5 crore in the end. Dr. Rao paid all of it.")).toEqual(["It cost 3.5 crore in the end.", "Dr. Rao paid all of it."]);
    expect(phrases("  one line without a stop  ")).toEqual(["one line without a stop"]);
  });
  it("a short sentence goes with what follows it (a model given four words or fewer to act alone stumbles)", () => {
    expect(phrases("No. When the river rises, a king carries the first sandbag. And the last.")).toEqual(["No. When the river rises, a king carries the first sandbag. And the last."]);
    expect(phrases("I promise. I promise! Just stay with me!")).toEqual(["I promise. I promise! Just stay with me!"]);
    expect(phrases("बेटा, मैं ठीक हूँ। तुम अपना ख़याल रखना।")).toEqual(["बेटा, मैं ठीक हूँ। तुम अपना ख़याल रखना।"]);
    expect(phrases("Run!")).toEqual(["Run!"]);
  });
  it("the silence after a sentence: longer after a trailing-off, longer for a slow or a pausing tone", () => {
    const stop = pauseAfter("He is a story."), dots = pauseAfter("Wait..."), ask = pauseAfter("Is that him?");
    expect(stop).toBeGreaterThan(0.3);
    expect(stop).toBeLessThan(0.6);
    expect(dots).toBeGreaterThan(stop * 1.3);
    expect(ask).toBeGreaterThanOrEqual(stop);
    expect(pauseAfter("He is a story.", { pauses: 1.3 })).toBeCloseTo(stop * 1.3, 5);
    expect(pauseAfter("He is a story.", { rate: 0.8 })).toBeCloseTo(stop / 0.8, 5);
  });
  it("spoken sentences are joined with their silences, and the speech is brought to the tone's pace", () => {
    const sr = 24000, tone = (hz: number, s: number) => Float32Array.from({ length: Math.round(sr * s) }, (_, i) => 0.5 * Math.sin((2 * Math.PI * hz * i) / sr));
    // two sentences spoken at a natural pace: three words a second
    const parts = [tone(220, 2), tone(330, 1)], texts = ["He was only ever a story.", "And it ends."];
    const plain = paced(parts, texts, {}, sr);
    expect(plain.length / sr).toBeCloseTo(3 + pauseAfter(texts[0]!), 1);
    // the silence really is silence, between two sounds
    const mid = Math.round(sr * (2 + pauseAfter(texts[0]!) / 2));
    expect(Math.max(...plain.subarray(mid - 200, mid + 200).map(Math.abs))).toBeLessThan(0.01);
    expect(Math.max(...plain.subarray(0, sr * 0.5).map(Math.abs))).toBeGreaterThan(0.3);
    const slow = paced(parts, texts, { rate: 0.8, pauses: 1.3 }, sr);
    expect(slow.length / sr).toBeCloseTo(3 / 0.8 + (pauseAfter(texts[0]!) * 1.3) / 0.8, 1);
    // a slower line keeps its pitch (it is stretched, not played slower)
    const zc = (x: Float32Array) => { let n = 0; for (let i = 1; i < x.length; i++) if (x[i - 1]! < 0 && x[i]! >= 0) n++; return n / (x.length / sr); };
    expect(zc(slow.subarray(0, Math.round(sr * 2 / 0.8)))).toBeGreaterThan(205);
    expect(zc(slow.subarray(0, Math.round(sr * 2 / 0.8)))).toBeLessThan(235);
  });
  it("a model that hurries is held to the pace, one that dawdles is not slowed further", () => {
    const sr = 24000, tone = (s: number) => Float32Array.from({ length: Math.round(sr * s) }, (_, i) => 0.5 * Math.sin((2 * Math.PI * 220 * i) / sr));
    const six = ["They brought ten thousand armed men."];
    // six words in one second is twice the natural pace: stretched, but never by more than 1.25 (more is heard as smearing)
    expect(paced([tone(1)], six, {}, sr).length / sr).toBeCloseTo(1.25, 1);
    // Hindi has more, shorter words a second: eight words in two seconds is its calm pace, left alone
    expect(paced([tone(2)], ["बेटा मैं ठीक हूँ तुम अपना ख़याल रखना"], { natural: 3.9 }, sr).length / sr).toBeCloseTo(2, 1);
    // six words in 2.4 s is 2.5 a second: with a tone of 0.86 (2.58 a second) that is already right
    expect(paced([tone(2.4)], six, { rate: 0.86 }, sr).length / sr).toBeCloseTo(2.4, 1);
    // six words in four seconds is slower than the tone asks, but it is how the line was acted: it keeps its pace
    expect(paced([tone(4)], six, { rate: 0.86 }, sr).length / sr).toBeCloseTo(4, 1);
    // a felt Hindi line at 3 words a second is not squeezed toward 3.9 (that made every aching line sound rushed)
    expect(paced([tone(2.3)], ["ये तेरे पापा की पहली तनख़्वाह है"], { natural: 3.9 }, sr).length / sr).toBeCloseTo(2.3, 1);
    // six words in seven seconds truly dawdles (under half the pace): brought up a little, never rushed
    expect(paced([tone(7)], six, { rate: 0.86 }, sr).length / sr).toBeCloseTo(7 * 0.85, 1);
    // a word or two is too little to measure a pace on: only the tone's own rate applies
    expect(paced([tone(0.5)], ["Run!"], { rate: 0.8 }, sr).length / sr).toBeCloseTo(0.5 / 0.8, 1);
  });
  it("measures the pace on the time a line was spoken in: a long silence inside it is a pause, not slowness", () => {
    // 27 Nov 2026: Papa's line in a film was said at 5 words a second between its pauses; with its silences counted it measured 3.2,
    // passed for slow, and was never held back.
    const sr = 24000, tone = (s: number) => Float32Array.from({ length: Math.round(sr * s) }, (_, i) => 0.5 * Math.sin((2 * Math.PI * 180 * i) / sr)), hush = (s: number) => new Float32Array(Math.round(sr * s));
    const join = (...a: Float32Array[]) => { const o = new Float32Array(a.reduce((n, x) => n + x.length, 0)); let k = 0; for (const x of a) { o.set(x, k); k += x.length; } return o; };
    const hurried = join(tone(0.8), hush(0.9), tone(0.8));   // eight words in 1.6 s of sound, a 0.9 s silence in the middle
    expect(speechSeconds(hurried, sr)).toBeCloseTo(1.7, 1);                       // the silence, less a word gap
    expect(speechSeconds(join(tone(0.8), hush(0.12), tone(0.8)), sr)).toBeCloseTo(1.72, 1);   // a short gap between words is speech
    const r = pacing([hurried], ["शाबाश बेटा अब माँ का ख़याल तू रखना।"], { natural: NATURAL_PACE_HI }, sr);
    expect(r.words).toBe(8);
    expect(r.spoken).toBeCloseTo(1.7, 1);
    expect(r.factor).toBeGreaterThan(1.15);     // 4.7 words a second is hurried Hindi: held back
    expect(r.factor).toBeLessThanOrEqual(1.25);
    // the same eight words said over the whole 2.5 s are at a calm pace and are left exactly as they were
    expect(pacing([tone(2.5)], ["शाबाश बेटा अब माँ का ख़याल तू रखना।"], { natural: NATURAL_PACE_HI }, sr).factor).toBe(1);
  });
  it("says how the line was spoken: the words, the seconds the model took and how far it was held", () => {
    const sr = 24000, tone = (s: number) => Float32Array.from({ length: Math.round(sr * s) }, (_, i) => 0.5 * Math.sin((2 * Math.PI * 220 * i) / sr));
    const r = pacing([tone(1)], ["They brought ten thousand armed men."], {}, sr);
    expect(r.words).toBe(6);
    expect(r.spoken).toBeCloseTo(1, 1); // seconds of speech as the model gave it
    expect(r.asked).toBeCloseTo(2, 1); // how far it would have to be stretched to sit at the pace
    expect(r.factor).toBeCloseTo(1.25, 2); // how far it was
    expect(r.pcm.length).toBe(paced([tone(1)], ["They brought ten thousand armed men."], {}, sr).length);
  });
  it("the model's own silence around a sentence is taken off, a word's dying sound is kept", () => {
    const sr = 24000, x = new Float32Array(sr * 2.4), w = (i: number) => Math.sin((2 * Math.PI * 220 * i) / sr);
    // 0.3 s of nothing, a second of speech whose last sound dies away over 0.2 s, 0.6 s of nothing, a faint breath
    for (let i = 0; i < sr; i++) x[sr * 0.3 + i] = 0.5 * w(i);
    for (let i = 0; i < sr * 0.2; i++) x[sr * 1.3 + i] = 0.05 * (1 - i / (sr * 0.2)) ** 2 * w(i);
    for (let i = 0; i < sr * 0.2; i++) x[sr * 2.1 + i] = 0.004 * w(i);
    const t = trimmed(x, sr);
    expect(t.length / sr).toBeGreaterThan(1.15); // the dying sound is still there
    expect(t.length / sr).toBeLessThan(1.32); // the silences and the breath are not
    expect(Math.max(...t.subarray(0, sr * 0.06).map(Math.abs))).toBeGreaterThan(0.3); // it begins with its sound
    // so the pause between two sentences is the tone's, whatever silence the model left
    const speech = Float32Array.from({ length: sr * 2 }, (_, i) => 0.5 * w(i)), padded = new Float32Array(sr * 2.8);
    padded.set(speech, sr * 0.4);
    const texts = ["They brought ten thousand armed men.", "We had forty farmers and a wall."];
    const out = paced([padded, padded], texts, {}, sr);
    let run = 0, longest = 0;
    for (const v of out) { run = Math.abs(v) < 0.002 ? run + 1 : 0; longest = Math.max(longest, run); }
    expect(longest / sr).toBeCloseTo(pauseAfter(texts[0]!), 1);
    expect(out.length / sr).toBeLessThan(4 + pauseAfter(texts[0]!) + 0.15);
  });
  it("a stray sound after the last word is cut off (the model sometimes adds a breath or an 'ah' after a silence)", () => {
    const sr = 24000, sine = (hz: number, s: number, a = 0.5) => Float32Array.from({ length: Math.round(sr * s) }, (_, i) => a * Math.sin((2 * Math.PI * hz * i) / sr));
    const join = (...xs: Float32Array[]) => { const o = new Float32Array(xs.reduce((n, x) => n + x.length, 0)); let at = 0; for (const x of xs) { o.set(x, at); at += x.length; } return o; };
    const quiet = (s: number) => new Float32Array(Math.round(sr * s));
    const stray = tidyEnd(join(sine(220, 1), quiet(0.4), sine(300, 0.12, 0.2)), sr);
    expect(stray.length / sr).toBeGreaterThan(0.98);
    expect(stray.length / sr).toBeLessThan(1.2);
    // words after a pause are words: a second stretch of speech stays
    const two = join(sine(220, 1), quiet(0.4), sine(300, 0.6));
    expect(tidyEnd(two, sr).length).toBe(two.length);
    // and a line with nothing after it is left alone
    const plain = sine(220, 1);
    expect(tidyEnd(plain, sr).length).toBe(plain.length);
  });
});
