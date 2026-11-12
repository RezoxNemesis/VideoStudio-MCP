import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { composeSong, STYLES } from "../src/music/compose";
import { newSong, newTrack } from "../src/music/model";
import { BANK_FILM, BANK_INDIAN, BANK_MODERN, STUDIO, buildStudioBank, isStudio, studioCatalogue } from "../src/music/studio/bank";
import { SR, hz, pluck, rng, seamless } from "../src/music/studio/dsp";
import { KITS, drumSeed } from "../src/music/studio/kits";
import { listInstruments, findInstrument, renderSong } from "../src/music/synth";
import { renderWithVocals, vocalChain, type VocalEngine } from "../src/music/vocal";
import { setAssetReader } from "../src/stage/pack";

const peak = (x: Float32Array) => x.reduce((m, v) => Math.max(m, Math.abs(v)), 0);
const rms = (x: Float32Array, a = 0, b = x.length) => {
  let s = 0;
  for (let i = a; i < b; i++) s += x[i]! * x[i]!;
  return Math.sqrt(s / Math.max(1, b - a));
};
/** Level of one frequency in a buffer (a single-bin Fourier measurement). */
function tone(x: Float32Array, f: number, sr: number): number {
  let re = 0, im = 0;
  for (let i = 0; i < x.length; i++) {
    re += x[i]! * Math.cos((2 * Math.PI * f * i) / sr);
    im += x[i]! * Math.sin((2 * Math.PI * f * i) / sr);
  }
  return Math.hypot(re, im) / x.length;
}
/** The strongest frequency near `f0` (± a semitone), in cents from it. */
function centsOff(x: Float32Array, f0: number, sr: number): number {
  let best = 0, at = 0;
  for (let c = -100; c <= 100; c += 2) {
    const v = tone(x, f0 * Math.pow(2, c / 1200), sr);
    if (v > best) {
      best = v;
      at = c;
    }
  }
  return at;
}

describe("Studio bank", () => {
  it("has its own places beside General MIDI", () => {
    const ids = new Set(STUDIO.map((d) => `${d.bank}:${d.program}`));
    expect(ids.size).toBe(STUDIO.length);
    expect(STUDIO.length).toBeGreaterThanOrEqual(30);
    expect(new Set(KITS.map((k) => k.program)).size).toBe(KITS.length);
    for (const d of STUDIO) expect([BANK_MODERN, BANK_INDIAN, BANK_FILM]).toContain(d.bank);
    expect(isStudio(BANK_MODERN, 0, false)).toBe(true);
    expect(isStudio(0, 0, false)).toBe(false);
    expect(isStudio(undefined, 84, true)).toBe(true);
    expect(isStudio(undefined, 0, true)).toBe(false);
    expect(studioCatalogue().length).toBe(STUDIO.length + KITS.length);
  });
  it("every instrument makes a clean note", () => {
    for (const d of STUDIO) {
      const key = d.keys[Math.floor(d.keys.length / 2)]!;
      const s = d.make(key, rng(1));
      expect(s.pcm.length, d.name).toBeGreaterThan(SR * 0.3);
      expect(s.pcm.every((v) => Number.isFinite(v)), d.name).toBe(true);
      expect(peak(s.pcm), d.name).toBeGreaterThan(0.3);
      // (a loop's blend can add a little; the bank brings every note back under full scale, checked below)
      expect(peak(s.pcm), d.name).toBeLessThanOrEqual(1.3);
      if (s.loop) {
        expect(s.loop[0], d.name).toBeGreaterThan(0);
        expect(s.loop[1], d.name).toBeLessThan(s.pcm.length);
        expect(s.loop[1] - s.loop[0], d.name).toBeGreaterThan(SR * 0.05);
        // the loop holds its level: the sound does not die inside it
        expect(rms(s.pcm, s.loop[0], s.loop[1]), d.name).toBeGreaterThan(0.05);
      }
    }
  });
  it("every drum sound is there", () => {
    for (const k of KITS) {
      for (const n of [36, 38, 42, 46]) expect(k.sounds[n], `${k.name} ${n}`).toBeDefined();
      for (const [n, snd] of Object.entries(k.sounds)) {
        const x = snd.make(drumSeed(k.program, Number(n)));
        expect(x.every((v) => Number.isFinite(v)), `${k.name} ${n}`).toBe(true);
        expect(peak(x), `${k.name} ${n}`).toBeGreaterThan(0.2);
        expect(peak(x), `${k.name} ${n}`).toBeLessThanOrEqual(1);
      }
    }
    // the tabla strokes the rhythm cycles use
    const tabla = KITS.find((k) => k.program === 84)!;
    for (const n of [60, 61, 62, 63, 64]) expect(tabla.sounds[n]).toBeDefined();
  });
  it("plucked strings are in tune", () => {
    for (const key of [45, 57, 69, 81]) {
      const x = pluck(hz(key), 1, rng(3), { bright: 0.9, t60: 3 });
      expect(Math.abs(centsOff(x.subarray(Math.round(0.1 * SR), Math.round(0.6 * SR)), hz(key), SR)), `key ${key}`).toBeLessThanOrEqual(6);
    }
  });
  it("loops without a seam", () => {
    const x = new Float32Array(SR * 2);
    const r = rng(5);
    for (let i = 0; i < x.length; i++) x[i] = Math.sin((2 * Math.PI * 220 * i) / SR) * 0.5 + (r() - 0.5) * 0.1;
    const [a, b] = seamless(x, 0.5, 1.5, 0.1);
    // the sample at the loop end leads into the one at the loop start as it would have without a loop
    expect(Math.abs(x[b - 1]! - x[a - 1]!)).toBeLessThan(0.02);
  });
  it("builds only what a song plays", async () => {
    expect(await buildStudioBank([{ program: 0, drums: false }, { program: 0, drums: true }])).toBeNull();
    const bank = (await buildStudioBank([{ bank: BANK_INDIAN, program: 0, drums: false }, { program: 84, drums: true }, { bank: 0, program: 5, drums: false }]))!;
    expect(bank.presets.map((p) => p.name).sort()).toEqual(["Sitar", "Tabla and Dholak"]);
    expect(bank.presets.find((p) => p.name === "Tabla and Dholak")!.isGMGSDrum).toBe(true);
    expect(bank.presets.find((p) => p.name === "Sitar")!.bankMSB).toBe(BANK_INDIAN);
    const loud = (await buildStudioBank([{ bank: BANK_INDIAN, program: 5, drums: false }, { bank: BANK_MODERN, program: 2, drums: false }]))!;
    for (const smp of loud.samples) expect(peak(smp.getAudioData()), smp.name).toBeLessThanOrEqual(0.985);
  });
});

describe("composer with the Studio bank", () => {
  it("writes every style", () => {
    for (const style of Object.keys(STYLES)) {
      const s = composeSong({ style, bars: 12, seed: 3 });
      expect(s.tracks.length, style).toBeGreaterThan(1);
      for (const t of s.tracks) for (const n of t.notes) expect(n.every((v) => Number.isFinite(v)), `${style} ${t.name}`).toBe(true);
    }
    expect(Object.keys(STYLES).length).toBeGreaterThanOrEqual(36);
  });
  it("uses the new instruments where they belong", () => {
    const trap = composeSong({ style: "trap", bars: 16, seed: 1 });
    expect(trap.tracks.some((t) => t.bank === BANK_MODERN && t.program === 0)).toBe(true);
    expect(trap.tracks.some((t) => t.kind === "drums" && t.program === 80)).toBe(true);
    const raga = composeSong({ style: "indian_classical", bars: 32, seed: 1 });
    expect(raga.tracks.some((t) => t.bank === BANK_INDIAN && t.program === 0)).toBe(true);
    expect(raga.tracks.some((t) => t.kind === "drums" && t.program === 84)).toBe(true);
    const tr = composeSong({ style: "trailer", bars: 24, seed: 1 });
    const braam = tr.tracks.find((t) => t.bank === BANK_FILM && t.program === 0)!;
    expect(braam.notes.length).toBeGreaterThan(0);
    expect(braam.notes.length).toBeLessThan(12);
    // a pop song is still plain General MIDI
    expect(composeSong({ style: "pop", bars: 12, seed: 1 }).tracks.every((t) => !t.bank)).toBe(true);
  });
});

describe("voice channel", () => {
  it("lifts presence, takes out rumble and keeps the level", () => {
    const sr = 48000, n = sr;
    const mk = () => {
      const x = new Float32Array(n);
      for (let i = 0; i < n; i++) x[i] = 0.2 * Math.sin((2 * Math.PI * 40 * i) / sr) + 0.2 * Math.sin((2 * Math.PI * 300 * i) / sr) + 0.2 * Math.sin((2 * Math.PI * 3200 * i) / sr);
      return x;
    };
    const dry = mk(), wet = vocalChain(mk(), sr, 1);
    expect(tone(wet, 40, sr) / tone(dry, 40, sr)).toBeLessThan(0.4);
    expect(tone(wet, 3200, sr) / tone(wet, 300, sr)).toBeGreaterThan((tone(dry, 3200, sr) / tone(dry, 300, sr)) * 1.5);
    expect(rms(wet) / rms(dry)).toBeGreaterThan(0.5);
    expect(rms(wet) / rms(dry)).toBeLessThan(2);
    // clarity 0 leaves everything but the rumble alone
    const plain = vocalChain(mk(), sr, 0);
    expect(tone(plain, 3200, sr) / tone(dry, 3200, sr)).toBeCloseTo(1, 1);
  });
});

const SF2 = "desktop/stage-assets/music/GeneralUser-GS.sf2";
describe.skipIf(!existsSync(SF2))("with the instrument bank installed", () => {
  setAssetReader(async (rel) => {
    const b = readFileSync(`desktop/stage-assets/${rel}`);
    return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer;
  });
  /** A stand-in singer: a steady tone wherever there are notes. */
  const singer: VocalEngine = {
    syllables: async (lines) => lines.map((l) => l.split(/\s+/)),
    sing: async (notes) => {
      const end = Math.max(...notes.map((x) => x.start + x.dur)) + 0.5, x = new Float32Array(Math.ceil(end * 24000));
      for (const nt of notes) for (let i = Math.round(nt.start * 24000); i < Math.round((nt.start + nt.dur) * 24000); i++) x[i] = 0.3 * Math.sin((2 * Math.PI * 330 * i) / 24000) + 0.1 * Math.sin((2 * Math.PI * 2500 * i) / 24000);
      return x;
    },
  };
  const song = () => {
    const s = newSong({ name: "t", bpm: 120, bars: 4, humanize: 0 });
    s.tracks.push(newTrack({ name: "Pad", program: 48, notes: [[0, 16, 60, 100], [0, 16, 64, 100], [0, 16, 67, 100]] }));
    s.tracks.push(newTrack({ name: "Voice", kind: "vocal", voice: "af_heart", notes: [[2, 4, 64, 100], [8, 4, 65, 100]], lyrics: ["la", "la"] }));
    return s;
  };
  it("lists and finds the Studio instruments", async () => {
    const all = await listInstruments();
    expect(all.length).toBeGreaterThanOrEqual(310);
    expect((await findInstrument("supersaw lead")).bank).toBe(BANK_MODERN);
    expect((await findInstrument("tanpura")).bank).toBe(BANK_INDIAN);
    expect((await findInstrument("tabla", true)).program).toBe(84);
    expect((await findInstrument("piano")).bank).toBeLessThan(100);
  });
  it("plays Studio instruments and kits beside General MIDI", async () => {
    const s = newSong({ name: "t", bpm: 120, bars: 2, humanize: 0 });
    s.tracks.push(newTrack({ name: "808", program: 0, bank: BANK_MODERN, notes: [[0, 2, 36, 110]], reverb: 0 }));
    s.tracks.push(newTrack({ name: "Tabla", kind: "drums", program: 84, notes: [[4, 0.2, 64, 110], [5, 0.2, 61, 110]], reverb: 0 }));
    const r = await renderSong(s, { tail: 1 });
    const sr = r.sampleRate;
    expect(rms(r.left, 0, sr)).toBeGreaterThan(0.01);
    expect(rms(r.left, Math.round(2 * sr), Math.round(2.4 * sr))).toBeGreaterThan(0.005);
    // the 808 is where it was written: the C two octaves below middle C
    expect(Math.abs(centsOff(r.left.subarray(Math.round(0.3 * sr), Math.round(0.9 * sr)), hz(36), sr))).toBeLessThanOrEqual(12);
  });
  it("puts the voice just in front of the band, and the faders move it", async () => {
    const base = await renderWithVocals(song(), singer, { stems: true });
    expect(base.levels!.balance).toBeGreaterThan(2);
    expect(base.levels!.balance).toBeLessThan(4);
    const loud = await renderWithVocals(song(), singer, { mix: { vocals: 2 } });
    expect(loud.levels!.balance - base.levels!.balance).toBeCloseTo(6, 0);
    const bed = await renderWithVocals(song(), singer, { mix: { music: 0.5 } });
    expect(bed.levels!.music - base.levels!.music).toBeCloseTo(-6, 0);
    // the band steps back while the voice sings
    const sr = base.sampleRate, m = base.stems!.music.left;
    expect(rms(m, Math.round(1.6 * sr), Math.round(2.6 * sr))).toBeLessThan(rms(m, Math.round(0.2 * sr), Math.round(0.8 * sr)) * 0.9);
    expect(peak(base.stems!.vocals.left)).toBeGreaterThan(0.05);
    const none = await renderWithVocals(song(), singer, { mix: { duck: 0, clarity: 0 }, stems: true });
    const m2 = none.stems!.music.left;
    expect(rms(m2, Math.round(1.6 * sr), Math.round(2.6 * sr)) / rms(m2, Math.round(0.2 * sr), Math.round(0.8 * sr))).toBeGreaterThan(rms(m, Math.round(1.6 * sr), Math.round(2.6 * sr)) / rms(m, Math.round(0.2 * sr), Math.round(0.8 * sr)));
  });
});
