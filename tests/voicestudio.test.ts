import { describe, expect, it } from "vitest";
import { acting, exposeAlignment, leadEnd, makeSpeaker, packSpeaker, prepareText, puncNorm, seedFor, referenceClip, StudioTokenizer, trimSilence, unpackSpeaker } from "../src/voice/engine/studio";
import { referenceLine, STUDIO_MOODS } from "../src/voice/engine/studio_refs";

describe("studio text", () => {
  it("cleans punctuation the way Chatterbox does", () => {
    expect(puncNorm("kaash main tumhe bacha paata...")).toBe("Kaash main tumhe bacha paata,");
    expect(puncNorm("wait: what")).toBe("Wait, what.");
    expect(puncNorm("“Hi”  there — friend")).toBe('"Hi" there - friend.');
    expect(prepareText("Main yahin hoon", "hi")).toBe("[hi]main[SPACE]yahin[SPACE]hoon.");
    // V3 was trained on full-case text: its tokenizer (NFKD normalizer) keeps the capitals
    expect(prepareText("Main yahin hoon", "hi", true)).toBe("[hi]Main[SPACE]yahin[SPACE]hoon.");
    expect(new StudioTokenizer({ normalizer: { type: "Sequence", normalizers: [{ type: "NFKD" }] }, model: { vocab: { "[UNK]": 1 }, merges: [] }, added_tokens: [] }).cased).toBe(true);
    expect(new StudioTokenizer({ model: { vocab: { "[UNK]": 1 }, merges: [] }, added_tokens: [] }).cased).toBe(false);
    // Devanagari nukta letters are decomposed (NFKD), as the model was trained
    expect(prepareText("ज़रा", "hi")).toBe("[hi]ज़रा.");
    // a Hindi sentence ends with its own full stop: no second one is put after it (the model babbles on after "।.")
    expect(puncNorm("और आख़िरी भी।")).toBe("और आख़िरी भी।");
    expect(puncNorm("सुनो॥")).toBe("सुनो॥");
    expect(prepareText("वादा करता हूँ।", "hi").endsWith("ँ।")).toBe(true);
    expect(puncNorm("Main yahin hoon")).toBe("Main yahin hoon."); // (a line with no stop still gets one)
  });

  it("tokenizes with added tokens and BPE merges", () => {
    const tok = new StudioTokenizer({
      model: { vocab: { "[UNK]": 1, "[SPACE]": 2, t: 3, h: 4, e: 5, th: 6, the: 7, ".": 8, a: 9 }, merges: ["t h", "th e"], unk_token: "[UNK]" },
      added_tokens: [{ id: 2, content: "[SPACE]" }, { id: 722, content: "[hi]" }, { id: 708, content: "[en]" }],
    });
    // template: EXAGGERATION, BOS, text…, EOT, START_SPEECH ×2
    expect(tok.encode("[en]the[SPACE]a.")).toEqual([6563, 255, 708, 7, 2, 9, 8, 0, 6561, 6561]);
    expect(tok.encode("[en]tz")).toEqual([6563, 255, 708, 3, 1, 0, 6561, 6561]);
    // a line said after a lead (studio_lead.ts): where its first token stands, as the attention counts (0 = the text's start mark)
    expect(leadEnd(tok, "the. ", "a the", "en")).toBe(5); // start, [en], the, ., space | a
    expect(leadEnd(tok, "the. ", "A the.", "en")).toBe(5);
    expect(leadEnd(tok, "", "a the", "en")).toBe(0); // no lead: nothing before the line
  });
});

describe("studio speakers", () => {
  it("round-trips through bytes", () => {
    const s = {
      condEmb: Float32Array.from({ length: 2 * 1024 }, (_, i) => i / 7),
      condLen: 2,
      promptToken: BigInt64Array.from([5n, 9n, 6000n]),
      xvec: Float32Array.from({ length: 192 }, (_, i) => -i),
      promptFeat: Float32Array.from({ length: 3 * 80 }, (_, i) => i * 0.5),
      featLen: 3,
    };
    const back = unpackSpeaker(packSpeaker(s));
    expect(back.condLen).toBe(2);
    expect(back.featLen).toBe(3);
    expect([...back.promptToken]).toEqual([5n, 9n, 6000n]);
    expect(back.condEmb[1500]).toBeCloseTo(1500 / 7);
    expect(back.xvec[191]).toBe(-191);
    expect(back.promptFeat[239]).toBe(119.5);
    expect(() => unpackSpeaker(new Uint8Array(32))).toThrow();
  });

  // What the voice says first grows out of the last sound of its reference clip: a clip that ends in the middle of a
  // sound spoils the first word of every line ("Make a world" came out "Pick a world", "For a promise" "For promise").
  // With half a second of silence after the clip, the same takes say the line.
  const SR = 24000, tone = (i: number) => 0.4 * Math.sin((2 * Math.PI * 180 * i) / SR);
  const clip = (...parts: Array<[number, boolean]>) => {
    const out: number[] = [];
    for (const [sec, loud] of parts) for (let i = 0, n = Math.round(sec * SR); i < n; i++) out.push(loud ? tone(out.length) : 0);
    return Float32Array.from(out);
  };
  const peakIn = (x: Float32Array, from: number, to: number) => {
    let m = 0;
    for (let i = Math.max(0, Math.round(from * SR)); i < Math.min(x.length, Math.round(to * SR)); i++) m = Math.max(m, Math.abs(x[i]!));
    return m;
  };

  it("ends a reference clip in half a second of silence", () => {
    const ref = clip([3, true]), out = referenceClip(ref);
    expect(out.length / SR).toBeCloseTo(3.5, 1);
    expect(peakIn(out, out.length / SR - 0.48, out.length / SR)).toBe(0);
    expect(Math.abs(out[out.length - Math.round(0.5 * SR) - 1]!)).toBeLessThan(0.02); // the sound is let go, not chopped
    expect([...out.subarray(0, Math.round(2.9 * SR))]).toEqual([...ref.subarray(0, Math.round(2.9 * SR))]); // the clip itself is as it was
    // a clip that already ends in silence keeps half a second of it, no more
    expect(referenceClip(clip([3, true], [1.4, false])).length / SR).toBeCloseTo(3.5, 1);
    expect(referenceClip(clip([3, true], [0.2, false])).length / SR).toBeCloseTo(3.5, 1);
    expect(referenceClip(new Float32Array(0)).length).toBe(0);
  });

  it("cuts a long reference clip in a pause, not through a word", () => {
    // (the model takes ten seconds of a clip: a slow voice's line is longer)
    const out = referenceClip(clip([4, true], [0.3, false], [4.5, true], [0.3, false], [3, true]));
    expect(out.length / SR).toBeLessThanOrEqual(10);
    expect(out.length / SR).toBeGreaterThan(9.2); // up to the pause at 8.8 s, and the silence
    expect(out.length / SR).toBeLessThan(9.45);
    expect(peakIn(out, out.length / SR - 0.48, out.length / SR)).toBe(0);
    expect(peakIn(out, 8.0, 8.7)).toBeGreaterThan(0.3); // the words before the pause are all there
    // a clip with no pause to cut in is let go at the limit
    const flat = referenceClip(clip([12, true]));
    expect(flat.length / SR).toBeLessThanOrEqual(10);
    expect(flat.length / SR).toBeGreaterThan(9.9);
    expect(peakIn(flat, flat.length / SR - 0.48, flat.length / SR)).toBe(0);
    expect(peakIn(flat, 9.0, 9.4)).toBeGreaterThan(0.3);
  });

  it("makes a speaker from the clip with its silence", async () => {
    let heard = new Float32Array(0);
    const t = (data: number[], dims: number[]) => ({ data: Float32Array.from(data), dims });
    const models = {
      tensor: (_type: string, data: Float32Array, dims: number[]) => ({ data, dims }),
      encoder: async () => ({
        run: async (feeds: Record<string, { data: Float32Array }>) => {
          heard = Float32Array.from(feeds.audio_values!.data);
          return { audio_features: t(new Array(1024).fill(0), [1, 1, 1024]), audio_tokens: { data: BigInt64Array.from([1n]), dims: [1, 1] }, speaker_embeddings: t(new Array(192).fill(0), [1, 192]), speaker_features: t(new Array(80).fill(0), [1, 1, 80]) };
        },
      }),
    };
    await makeSpeaker(models as never, clip([2, true]));
    expect(heard.length / SR).toBeCloseTo(2.5, 1);
    expect(peakIn(heard, 2.05, 2.5)).toBe(0);
  });

  it("has a reference line for every mood, agreeing with the speaker", () => {
    for (const m of STUDIO_MOODS) {
      expect(referenceLine("h", m, true).length).toBeGreaterThan(40);
      expect(referenceLine("a", m, false)).not.toMatch(/[{}|]/);
    }
    expect(referenceLine("h", undefined, true)).toContain("uthi");
    expect(referenceLine("h", undefined, false)).toContain("utha");
    expect(referenceLine("h", "unknown-mood", false)).toBe(referenceLine("h", "neutral", false));
  });
});

describe("studio acting", () => {
  it("acts stronger moods harder and slow moods slower", () => {
    expect(acting(undefined)).toEqual({ exaggeration: 0.5, cfg: 0.5 });
    expect(acting("crying")).toEqual({ exaggeration: 0.8, cfg: 0.3 });
    expect(acting("angry", 1.5).exaggeration).toBeGreaterThan(acting("angry", 1).exaggeration);
    expect(acting("happy", 0.5).exaggeration).toBeCloseTo(0.6);
  });

  it("gives stable seeds", () => {
    expect(seedFor("a", "b")).toBe(seedFor("a", "b"));
    expect(seedFor("a", "b")).not.toBe(seedFor("a", "c"));
  });

  it("trims silence around a line", () => {
    const pcm = new Float32Array(24000 * 2);
    for (let i = 24000 * 0.8; i < 24000 * 1.2; i++) pcm[i] = Math.sin(i / 5) * 0.5;
    const t = trimSilence(pcm);
    expect(t.length / 24000).toBeGreaterThan(0.4);
    expect(t.length / 24000).toBeLessThan(0.6);
  });
});

describe("studio model graph", () => {
  it("adds the alignment outputs to the graph and keeps everything else", () => {
    // ModelProto { ir_version: 8 (field 1), graph (field 7) { name: "g" (field 2) } }
    const model = Uint8Array.from([0x08, 0x08, 0x3a, 0x03, 0x12, 0x01, 0x67]);
    const out = exposeAlignment(model);
    expect(out.slice(0, 2)).toEqual(Uint8Array.from([0x08, 0x08]));
    const text = new TextDecoder().decode(out);
    for (const l of [9, 12, 13]) expect(text).toContain(`/model/layers.${l}/attn/qkv_proj/MatMul/output_0`);
    expect(() => exposeAlignment(Uint8Array.from([0x08, 0x08]))).toThrow();
  });
});

describe("a performance in another voice (revoice)", () => {
  it("takes a long recording in pieces no longer than the encoder keeps, cut at its quietest moments", async () => {
    const { revoicePieces, REVOICE_MAX } = await import("../src/voice/engine/studio");
    const sr = 24000, tone = (s: number) => Float32Array.from({ length: Math.round(sr * s) }, (_, i) => 0.5 * Math.sin((2 * Math.PI * 200 * i) / sr)), hush = (s: number) => new Float32Array(Math.round(sr * s));
    const join = (...a: Float32Array[]) => { const o = new Float32Array(a.reduce((n, x) => n + x.length, 0)); let k = 0; for (const x of a) { o.set(x, k); k += x.length; } return o; };
    expect(revoicePieces(tone(3), sr)).toEqual([[0, 3 * sr]]);                       // a short line is one piece
    // 4 s of speech, a 0.3 s pause, 4 s, a pause, 3 s: cut IN the pauses, never inside the speech
    const rec = join(tone(4), hush(0.3), tone(4), hush(0.3), tone(3)), pieces = revoicePieces(rec, sr);
    expect(pieces[0]![0]).toBe(0);
    expect(pieces[pieces.length - 1]![1]).toBe(rec.length);
    for (let i = 0; i < pieces.length; i++) { expect((pieces[i]![1] - pieces[i]![0]) / sr).toBeLessThanOrEqual(REVOICE_MAX + 0.01); if (i) expect(pieces[i]![0]).toBe(pieces[i - 1]![1]); }
    const cuts = pieces.slice(1).map((p) => p[0] / sr);
    expect(cuts.length).toBe(2);
    expect(cuts[0]).toBeGreaterThan(4); expect(cuts[0]).toBeLessThan(4.3);
    expect(cuts[1]).toBeGreaterThan(8.3); expect(cuts[1]).toBeLessThan(8.6);
  });
});
