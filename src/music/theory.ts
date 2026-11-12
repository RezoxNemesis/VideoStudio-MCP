/** Scales, ragas, chords and progressions. Pitch classes are semitones above the tonic. */

export interface ScaleDef {
  /** Notes going up. */
  up: number[];
  /** Notes coming down, when a raga descends differently (as pitch classes, any order). */
  down?: number[];
  /** Notes a melody rests on (raga vadi and samvadi), as pitch classes. */
  rest?: number[];
  family: "western" | "raga" | "world";
  /** Minor-feeling scales build their home chord as minor. */
  minor?: boolean;
  about?: string;
}

export const SCALES: Record<string, ScaleDef> = {
  major: { up: [0, 2, 4, 5, 7, 9, 11], family: "western" },
  minor: { up: [0, 2, 3, 5, 7, 8, 10], family: "western", minor: true },
  harmonic_minor: { up: [0, 2, 3, 5, 7, 8, 11], family: "western", minor: true },
  melodic_minor: { up: [0, 2, 3, 5, 7, 9, 11], down: [0, 2, 3, 5, 7, 8, 10], family: "western", minor: true },
  dorian: { up: [0, 2, 3, 5, 7, 9, 10], family: "western", minor: true },
  phrygian: { up: [0, 1, 3, 5, 7, 8, 10], family: "western", minor: true },
  lydian: { up: [0, 2, 4, 6, 7, 9, 11], family: "western" },
  mixolydian: { up: [0, 2, 4, 5, 7, 9, 10], family: "western" },
  pentatonic: { up: [0, 2, 4, 7, 9], family: "western" },
  minor_pentatonic: { up: [0, 3, 5, 7, 10], family: "western", minor: true },
  blues: { up: [0, 3, 5, 6, 7, 10], family: "western", minor: true },
  whole_tone: { up: [0, 2, 4, 6, 8, 10], family: "western" },
  japanese: { up: [0, 1, 5, 7, 8], family: "world", minor: true, about: "In scale (miyako-bushi): koto and shamisen colour" },
  hirajoshi: { up: [0, 2, 3, 7, 8], family: "world", minor: true },
  arabic: { up: [0, 1, 4, 5, 7, 8, 11], family: "world", about: "Double harmonic (Hijaz Kar)" },
  hijaz: { up: [0, 1, 4, 5, 7, 8, 10], family: "world", minor: true },
  // Hindustani ragas (Sa = tonic). Simplified to their note sets, ascent and descent, and resting notes.
  yaman: { up: [0, 2, 4, 6, 7, 9, 11], rest: [4, 11], family: "raga", about: "Evening raga, serene and romantic (Kalyan thaat: tivra Ma)" },
  bhupali: { up: [0, 2, 4, 7, 9], rest: [4, 9], family: "raga", about: "Five notes, calm and devotional (Mohanam)" },
  bilawal: { up: [0, 2, 4, 5, 7, 9, 11], rest: [9, 4], family: "raga", about: "Morning raga, bright" },
  khamaj: { up: [0, 4, 5, 7, 9, 11], down: [0, 2, 4, 5, 7, 9, 10], rest: [4, 11], family: "raga", about: "Light and romantic; komal Ni coming down" },
  desh: { up: [0, 2, 5, 7, 11], down: [0, 2, 4, 5, 7, 9, 10], rest: [2, 7], family: "raga", about: "Monsoon, patriotic warmth (Vande Mataram colour)" },
  kafi: { up: [0, 2, 3, 5, 7, 9, 10], rest: [7, 0], family: "raga", minor: true, about: "Folk-like, spring (Holi)" },
  bhimpalasi: { up: [0, 3, 5, 7, 10], down: [0, 2, 3, 5, 7, 9, 10], rest: [5, 0], family: "raga", minor: true, about: "Afternoon longing" },
  bageshri: { up: [0, 3, 5, 9, 10], down: [0, 2, 3, 5, 7, 9, 10], rest: [5, 0], family: "raga", minor: true, about: "Night, waiting for the beloved" },
  bhairav: { up: [0, 1, 4, 5, 7, 8, 11], rest: [8, 1], family: "raga", about: "Dawn, solemn and devotional" },
  bhairavi: { up: [0, 1, 3, 5, 7, 8, 10], rest: [5, 0], family: "raga", minor: true, about: "Morning, tender and devotional; closes concerts" },
  todi: { up: [0, 1, 3, 6, 7, 8, 11], rest: [8, 3], family: "raga", minor: true, about: "Late morning, deep pathos" },
  malkauns: { up: [0, 3, 5, 8, 10], rest: [5, 0], family: "raga", minor: true, about: "Midnight, grave and meditative" },
  durga: { up: [0, 2, 5, 7, 9], rest: [5, 0], family: "raga", about: "Night, clear and strong" },
  hamsadhwani: { up: [0, 2, 4, 7, 11], rest: [7, 2], family: "raga", about: "Auspicious openings, joyful" },
  shivaranjani: { up: [0, 2, 3, 7, 9], rest: [7, 0], family: "raga", minor: true, about: "Poignant; many film songs" },
  kalavati: { up: [0, 4, 7, 9, 10], rest: [7, 0], family: "raga", about: "Late night, sweet" },
  charukeshi: { up: [0, 2, 4, 5, 7, 8, 10], rest: [5, 0], family: "raga", about: "Bittersweet; major below, minor above" },
  kirwani: { up: [0, 2, 3, 5, 7, 8, 11], rest: [7, 0], family: "raga", minor: true, about: "Harmonic minor mood, romantic sorrow" },
  pahadi: { up: [0, 2, 4, 7, 9], down: [0, 2, 4, 5, 7, 9, 11], rest: [7, 0], family: "raga", about: "Hill folk tune, gentle" },
  darbari: { up: [0, 2, 3, 5, 7, 8, 10], rest: [2, 7], family: "raga", minor: true, about: "Late night, majestic and heavy" },
  marwa: { up: [0, 1, 4, 6, 9, 11], rest: [1, 9], family: "raga", about: "Sunset, restless" },
  puriya_dhanashri: { up: [0, 1, 4, 6, 7, 8, 11], rest: [7, 1], family: "raga", about: "Dusk, intense" },
  ahir_bhairav: { up: [0, 1, 4, 5, 7, 9, 10], rest: [5, 0], family: "raga", about: "Early morning, pastoral" },
};

export const scaleDef = (name: string): ScaleDef => {
  const s = SCALES[name.toLowerCase().replace(/[\s-]+/g, "_")];
  if (!s) throw new Error(`Unknown scale or raga "${name}". Known: ${Object.keys(SCALES).join(", ")}.`);
  return s;
};

/** MIDI pitches of a scale between lo and hi, for the given tonic (going up, or the descending form). */
export function scalePitches(key: number, scale: string, lo: number, hi: number, descending = false): number[] {
  const d = scaleDef(scale);
  const set = new Set((descending && d.down ? d.down : d.up).map((x) => (x + key) % 12));
  const out: number[] = [];
  for (let p = lo; p <= hi; p++) if (set.has(((p % 12) + 12) % 12)) out.push(p);
  return out;
}
/** The nearest scale pitch to p. */
export function snap(p: number, key: number, scale: string, descending = false): number {
  const d = scaleDef(scale);
  const set = new Set((descending && d.down ? d.down : d.up).map((x) => (x + key) % 12));
  for (let k = 0; k < 7; k++) {
    if (set.has((((p - k) % 12) + 12) % 12)) return p - k;
    if (set.has((((p + k) % 12) + 12) % 12)) return p + k;
  }
  return p;
}

export const CHORDS: Record<string, number[]> = {
  "": [0, 4, 7], m: [0, 3, 7], dim: [0, 3, 6], aug: [0, 4, 8], sus2: [0, 2, 7], sus4: [0, 5, 7], "5": [0, 7],
  "7": [0, 4, 7, 10], maj7: [0, 4, 7, 11], m7: [0, 3, 7, 10], m7b5: [0, 3, 6, 10], dim7: [0, 3, 6, 9], "6": [0, 4, 7, 9], m6: [0, 3, 7, 9],
  add9: [0, 4, 7, 14], madd9: [0, 3, 7, 14], "9": [0, 4, 7, 10, 14], m9: [0, 3, 7, 10, 14], maj9: [0, 4, 7, 11, 14],
};
const NOTE: Record<string, number> = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };

/** "Am", "F#m7", "Bbmaj7", "G/B" → root pitch class, quality, bass. */
export function parseChord(name: string): { root: number; kind: string; bass?: number } {
  const m = /^([A-G])([#b]?)([^/]*)(?:\/([A-G])([#b]?))?$/.exec(name.trim());
  if (!m) throw new Error(`"${name}" is not a chord. Write C, Am, F#m7, Bbmaj7, G/B…`);
  const acc = (a: string) => (a === "#" ? 1 : a === "b" ? -1 : 0);
  let kind = m[3]!.replace(/^min/, "m").replace(/^M7/, "maj7").replace(/^maj$/, "");
  if (kind === "-") kind = "m";
  if (!(kind in CHORDS)) throw new Error(`Unknown chord type "${m[3]}" in "${name}". Types: ${Object.keys(CHORDS).filter(Boolean).join(", ")}.`);
  return { root: (NOTE[m[1]!]! + acc(m[2]!) + 12) % 12, kind, bass: m[4] ? (NOTE[m[4]]! + acc(m[5]!) + 12) % 12 : undefined };
}
const NAMES = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"];
export const chordName = (c: { root: number; kind: string; bass?: number }): string => `${NAMES[c.root]}${c.kind}${c.bass !== undefined && c.bass !== c.root ? "/" + NAMES[c.bass] : ""}`;

/**
 * The seven notes chords are built from for a scale. Ragas and five-note scales borrow the fuller scale they sit
 * inside; scales that do not take chords well (a flat second: Bhairav, Todi, Marwa…) return null and are played
 * over a drone instead, as they are in their own tradition.
 */
export function harmonyNotes(scale: string): number[] | null {
  const d = scaleDef(scale);
  const all = new Set([...d.up, ...(d.down ?? [])]);
  if (d.family !== "western" && all.has(1)) return null;
  if (d.up.length === 7) return d.up;
  if (d.down && d.down.length === 7) return d.down;
  if (scale === "blues" || scale === "whole_tone") return scale === "blues" ? [0, 2, 3, 5, 7, 8, 10] : null;
  const parents = [[0, 2, 4, 5, 7, 9, 11], [0, 2, 4, 5, 7, 9, 10], [0, 2, 4, 6, 7, 9, 11], [0, 2, 3, 5, 7, 8, 10], [0, 2, 3, 5, 7, 9, 10], [0, 2, 3, 5, 7, 8, 11]];
  for (const p of parents) if (d.up.every((n) => p.includes(n))) return p;
  return null;
}

/** Chord built on a degree (0-based) of a seven-note scale: stacked thirds. */
export function degreeChord(key: number, notes: number[], degree: number, seventh = false): { root: number; kind: string } {
  const n = notes.length;
  const at = (i: number) => notes[((i % n) + n) % n]! + 12 * Math.floor(i / n);
  const r = at(degree);
  const third = at(degree + 2) - r, fifth = at(degree + 4) - r, sev = at(degree + 6) - r;
  let kind = third === 3 ? (fifth === 6 ? "dim" : "m") : third === 4 ? (fifth === 8 ? "aug" : "") : fifth === 7 ? "sus4" : "5";
  if (seventh && (kind === "" || kind === "m")) kind = kind === "" ? (sev === 11 ? "maj7" : "7") : sev === 10 ? "m7" : "m";
  return { root: (r + key) % 12, kind };
}
/** Pitches of a chord around a centre pitch (close voicing, lowest note at or above `from`). */
export function chordPitches(c: { root: number; kind: string }, from: number): number[] {
  const iv = CHORDS[c.kind] ?? CHORDS[""]!;
  let base = from - (((from - c.root) % 12) + 12) % 12;
  if (base < from) base += 12;
  if (base - from > 6) base -= 12;
  return iv.map((x) => base + x);
}
/** Voice-lead: the inversion of the chord closest to the previous voicing. */
export function voiceLead(c: { root: number; kind: string }, prev: number[] | null, centre = 60): number[] {
  const base = CHORDS[c.kind] ?? CHORDS[""]!;
  // a bare fifth is voiced with its octave so every part has three notes to work with
  const iv = base.length < 3 ? [...base, 12] : base.slice(0, 4);
  const target = prev && prev.length ? prev.reduce((a, b) => a + b, 0) / prev.length : centre;
  let best: number[] = [], cost = 1e9;
  for (let inv = 0; inv < iv.length; inv++)
    for (const oct of [-12, 0, 12]) {
      const v = iv.map((x, i) => c.root + 48 + oct + (x % 12) + (i < inv ? 12 : 0)).sort((a, b) => a - b);
      const mean = v.reduce((a, b) => a + b, 0) / v.length;
      const k = Math.abs(mean - target) + (v[v.length - 1]! - v[0]! > 14 ? 4 : 0);
      if (k < cost) {
        cost = k;
        best = v;
      }
    }
  return best;
}

/** Chord progressions by feel, as scale degrees (0 = home). One entry per bar; [degree, degree] splits a bar. */
export const PROGRESSIONS: Record<string, (number | [number, number])[][]> = {
  major: [
    [0, 4, 5, 3], [0, 5, 3, 4], [0, 3, 4, 4], [5, 3, 0, 4], [0, 3, 0, 4], [0, 4, 5, 2, 3, 0, 3, 4], [3, 4, 2, 5], [0, 2, 3, 4], [3, 0, 4, 5], [0, 5, 1, 4],
  ],
  minor: [
    [0, 5, 2, 6], [0, 6, 5, 6], [0, 3, 6, 2], [0, 5, 3, 4], [0, 3, 4, 0], [0, 6, 5, 4], [5, 6, 0, 0], [0, 2, 6, 5], [0, 4, 5, 2], [3, 5, 6, 0],
  ],
  epic: [[0, 5, 2, 6], [0, 6, 5, 6], [0, 2, 5, 6], [5, 6, 0, 0], [0, 5, 6, 4]],
  dark: [[0, 1, 0, 6], [0, 5, 0, 4], [0, 0, 1, 0], [0, 3, 1, 0]],
};

/** Hindustani rhythm cycles: beats, the claps/waves structure and a basic pattern of strokes per beat. */
export const TAALS: Record<string, { beats: number; unit: number; bols: string[]; about: string }> = {
  keherwa: { beats: 8, unit: 8, bols: ["dha", "ge", "na", "ti", "na", "ka", "dhi", "na"], about: "8 beats: film songs, folk, bhajans" },
  dadra: { beats: 6, unit: 8, bols: ["dha", "dhi", "na", "dha", "tu", "na"], about: "6 beats: light, lilting" },
  teentaal: { beats: 16, unit: 4, bols: ["dha", "dhin", "dhin", "dha", "dha", "dhin", "dhin", "dha", "dha", "tin", "tin", "ta", "ta", "dhin", "dhin", "dha"], about: "16 beats: the classical standard" },
  rupak: { beats: 7, unit: 4, bols: ["tin", "tin", "na", "dhi", "na", "dhi", "na"], about: "7 beats (3+2+2)" },
  jhaptaal: { beats: 10, unit: 4, bols: ["dhi", "na", "dhi", "dhi", "na", "ti", "na", "dhi", "dhi", "na"], about: "10 beats (2+3+2+3)" },
  bhajani: { beats: 8, unit: 8, bols: ["dhin", "na", "dhin", "dhin", "na", "tin", "na", "dhin"], about: "8 beats: devotional songs" },
  garba: { beats: 8, unit: 8, bols: ["dha", "ti", "dha", "ge", "dha", "ti", "na", "ka"], about: "8 beats: Gujarati dance, driving" },
  bhangra: { beats: 8, unit: 8, bols: ["dha", "na", "na", "dha", "ge", "na", "dha", "na"], about: "8 beats: Punjabi dhol swing" },
};
/** A tabla-like stroke on a General MIDI kit: [drum pitch, loudness 0…1][] (low hand + high hand). */
export const BOL: Record<string, [number, number][]> = {
  dha: [[64, 1], [61, 0.85]], dhin: [[64, 0.95], [60, 0.8]], dhi: [[64, 0.8], [60, 0.75]], ge: [[64, 0.9]], ka: [[62, 0.55]],
  na: [[61, 0.8]], ta: [[60, 0.85]], tin: [[60, 0.7]], ti: [[61, 0.55]], tu: [[63, 0.7]], te: [[62, 0.5]],
};
