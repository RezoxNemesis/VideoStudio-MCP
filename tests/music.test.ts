import { describe, expect, it } from "vitest";
import { composeSong, describeSong, MOODS, STYLES } from "../src/music/compose";
import { exportMidi, importMidi } from "../src/music/midi";
import { barBeats, keyOf, pitchName, pitchOf, songBeats, songSeconds } from "../src/music/model";
import { chordName, harmonyNotes, parseChord, scaleDef, scalePitches, SCALES, snap, TAALS, voiceLead } from "../src/music/theory";

describe("music theory", () => {
  it("reads and names pitches and keys", () => {
    expect(pitchOf("C4")).toBe(60);
    expect(pitchOf("A4")).toBe(69);
    expect(pitchOf("F#3")).toBe(54);
    expect(pitchName(61)).toBe("C#4");
    expect(keyOf("D")).toBe(2);
    expect(keyOf("Bb")).toBe(10);
  });
  it("builds scales and ragas from their steps", () => {
    expect(scalePitches(0, "major", 60, 72)).toEqual([60, 62, 64, 65, 67, 69, 71, 72]);
    // Raga Bhupali: Sa Re Ga Pa Dha (a major pentatonic)
    expect(scalePitches(0, "bhupali", 60, 72)).toEqual([60, 62, 64, 67, 69, 72]);
    // Yaman has the raised fourth
    expect(scalePitches(0, "yaman", 60, 72)).toContain(66);
    expect(() => scaleDef("no-such-scale")).toThrow(/Unknown scale or raga/);
    expect(scaleDef("yaman")).toBe(SCALES.yaman);
    for (const name of Object.keys(SCALES)) expect(scalePitches(2, name, 48, 84).length).toBeGreaterThan(8);
  });
  it("snaps a pitch into the scale", () => {
    expect(snap(61, 0, "major")).toBe(60);
    expect([62, 64]).toContain(snap(63, 0, "bhupali"));
    expect(snap(67, 0, "malkauns") % 12).not.toBe(7); // Malkauns leaves out Pa
  });
  it("parses and voices chords", () => {
    expect(parseChord("Am7")).toMatchObject({ root: 9, kind: "m7" });
    expect(chordName(parseChord("F#m"))).toBe("F#m");
    const v = voiceLead({ root: 0, kind: "" }, null);
    expect(v.every((p) => Number.isFinite(p))).toBe(true);
    expect(new Set(v.map((p) => p % 12))).toEqual(new Set([0, 4, 7]));
    // a bare fifth still gives three sounding notes (the octave is added), never NaN
    const fifth = voiceLead({ root: 2, kind: "5" }, v);
    expect(fifth.every((p) => Number.isFinite(p))).toBe(true);
  });
  it("gives ragas with a flat second a drone rather than western chords", () => {
    expect(harmonyNotes("major")).toHaveLength(7);
    expect(harmonyNotes("bhairav")).toBeNull();
    expect(harmonyNotes("todi")).toBeNull();
  });
  it("knows the common taals", () => {
    expect(TAALS.teentaal!.beats).toBe(16);
    expect(TAALS.keherwa!.beats).toBe(8);
    expect(TAALS.dadra!.beats).toBe(6);
    expect(TAALS.rupak!.beats).toBe(7);
    for (const t of Object.values(TAALS)) expect(t.bols.length).toBe(t.beats);
  });
});

describe("composer", () => {
  it("writes a playable song in every style", () => {
    for (const style of Object.keys(STYLES)) {
      const s = composeSong({ style, seconds: 24, seed: 11 });
      expect(s.tracks.length, style).toBeGreaterThan(1);
      const end = songBeats(s);
      let notes = 0;
      for (const t of s.tracks) {
        for (const [start, len, pitch, vel] of t.notes) {
          notes++;
          expect(Number.isFinite(start) && Number.isFinite(len) && Number.isFinite(pitch) && Number.isFinite(vel), `${style}/${t.name}`).toBe(true);
          expect(start, `${style}/${t.name}`).toBeGreaterThanOrEqual(0);
          expect(start, `${style}/${t.name}`).toBeLessThan(end + 1e-6);
          expect(len).toBeGreaterThan(0);
          expect(pitch).toBeGreaterThanOrEqual(0);
          expect(pitch).toBeLessThanOrEqual(127);
          expect(vel).toBeGreaterThan(0);
          expect(vel).toBeLessThanOrEqual(127);
        }
      }
      expect(notes, style).toBeGreaterThan(20);
      expect(s.sections.reduce((n, x) => n + x.bars, 0), style).toBe(s.bars);
      expect(s.chords.length, style).toBeGreaterThan(0);
    }
  });
  it("is the same song for the same seed and a different one for another", () => {
    const a = composeSong({ style: "cinematic_epic", seconds: 30, seed: 5 });
    const b = composeSong({ style: "cinematic_epic", seconds: 30, seed: 5 });
    const c = composeSong({ style: "cinematic_epic", seconds: 30, seed: 6 });
    const tune = (s: typeof a) => JSON.stringify(s.tracks.map((t) => t.notes));
    expect(tune(a)).toBe(tune(b));
    expect(tune(a)).not.toBe(tune(c));
  });
  it("keeps close to the length asked for and honours key, tempo, mood and raga", () => {
    const s = composeSong({ style: "indian_classical", seconds: 40, seed: 2, key: 2, scale: "yaman", bpm: 90 });
    expect(Math.abs(songSeconds(s) - 40)).toBeLessThan(barBeats(s) * (60 / s.bpm) * 2 + 0.01);
    expect(s.key).toBe(2);
    expect(s.scale).toBe("yaman");
    expect(s.bpm).toBe(90);
    // every melody note of the lead belongs to the raga
    const allowed = new Set(scalePitches(2, "yaman", 0, 127).map((p) => p % 12));
    const lead = s.tracks.find((t) => t.kind === "inst" && t.notes.length > 8)!;
    for (const n of lead.notes) expect(allowed.has(n[2] % 12)).toBe(true);
    for (const mood of Object.keys(MOODS)) expect(composeSong({ style: "pop", mood, seconds: 16, seed: 1 }).tracks.length).toBeGreaterThan(1);
  });
  it("sets words to a tune: one note per syllable, inside the singer's range", () => {
    const s = composeSong({
      style: "bollywood_romantic", seed: 4,
      vocal: { voice: "hf_alpha", range: [52, 64], blocks: [{ name: "mukhda", lines: [["dil", "ki", "raa", "hein"], ["tum", "se", "mil", "ti", "hain"]] }, { name: "antara", lines: [["chand", "bhi", "de", "khe"]] }] },
    });
    const v = s.tracks.find((t) => t.kind === "vocal")!;
    expect(v).toBeTruthy();
    expect(v.notes.length).toBe(13);
    expect(v.lyrics!.length).toBe(13);
    for (const n of v.notes) {
      expect(n[2]).toBeGreaterThanOrEqual(52);
      expect(n[2]).toBeLessThanOrEqual(64);
    }
    const d = describeSong(s) as { tracks: { kind: string; instrument: string }[] };
    expect(d.tracks.find((t) => t.kind === "vocal")!.instrument).toContain("hf_alpha");
  });
});

describe("MIDI files", () => {
  it("round-trips a song: tempo, tracks and every note", () => {
    const s = composeSong({ style: "anime_rock", seconds: 20, seed: 9 });
    const back = importMidi(exportMidi(s), "back");
    expect(back.bpm).toBe(s.bpm);
    const count = (x: typeof s) => x.tracks.filter((t) => t.notes.length).map((t) => t.notes.length).sort((a, b) => a - b);
    expect(count(back)).toEqual(count(s.tracks.some((t) => t.kind === "vocal") ? s : s));
    const pitches = (x: typeof s) => x.tracks.flatMap((t) => t.notes.map((n) => n[2])).sort((a, b) => a - b);
    expect(pitches(back)).toEqual(pitches(s));
  });
  it("writes a standard header", () => {
    const b = exportMidi(composeSong({ style: "lofi", seconds: 12, seed: 1 }));
    expect(String.fromCharCode(...b.slice(0, 4))).toBe("MThd");
    expect((b[12]! << 8) | b[13]!).toBe(480);
  });
});
