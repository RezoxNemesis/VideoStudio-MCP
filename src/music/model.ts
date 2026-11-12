/**
 * Songs: multi-track music written as notes, rendered through a sampled-instrument synthesizer
 * (hundreds of instruments from a General MIDI / GS sound bank). Time is counted in beats (quarter notes).
 */

/** [start beat, length in beats, MIDI pitch (60 = middle C), velocity 1…127]. */
export type Note = [number, number, number, number];

export interface Track {
  id: string;
  name: string;
  /** "inst": pitched instrument; "drums": a drum kit (pitches are drum sounds); "vocal": sung by a voice. */
  kind: "inst" | "drums" | "vocal";
  /** General MIDI program 0…127 (for drums: the kit number). */
  program: number;
  /** Variation bank (GS), 0 = the standard instrument. */
  bank?: number;
  /** Instrument name as shown (kept so the song reads well without the sound bank). */
  instrument?: string;
  /** 0…1 */
  volume: number;
  /** -1 left … 1 right */
  pan: number;
  /** Reverb and chorus sends, 0…1. */
  reverb: number;
  chorus?: number;
  mute?: boolean;
  solo?: boolean;
  notes: Note[];
  /** Pitch glides (meend, bends): [beat, semitones]; linear between points, range ±12. */
  bends?: [number, number][];
  /** Expression (swell) curve: [beat, 0…1]. */
  swell?: [number, number][];
  /** Vocal tracks: one syllable per note, in order. */
  lyrics?: string[];
  /** Vocal tracks: the voice that sings. */
  voice?: string;
}

export interface ChordEvent {
  /** Start beat and length in beats. */
  at: number;
  dur: number;
  /** Root pitch class 0…11 and the chord quality. */
  root: number;
  kind: string;
  /** Bass note pitch class if not the root (slash chord). */
  bass?: number;
}

export interface Section {
  name: string;
  /** First bar (0-based) and number of bars. */
  bar: number;
  bars: number;
  /** 0…1: how full and loud the arrangement is here. */
  energy: number;
}

export interface Song {
  id: string;
  name: string;
  bpm: number;
  /** Beats per bar and the beat unit: [4, 4], [3, 4], [6, 8], [7, 8]… */
  meter: [number, number];
  /** Tonic pitch class: 0 = C … 11 = B. */
  key: number;
  /** Scale or raga name (see theory.ts). */
  scale: string;
  bars: number;
  /** 0…0.4: how far every second eighth is pushed back. */
  swing?: number;
  /** 0…1: small human timing and loudness differences. */
  humanize?: number;
  tracks: Track[];
  chords: ChordEvent[];
  sections: Section[];
  /** Master reverb size 0…1 and output gain. */
  room?: number;
  gain?: number;
  /** What it was generated from, if it was. */
  brief?: Record<string, unknown>;
  /** The rendered audio in the media pool, and the settings it was rendered with. */
  assetId?: string;
}

/** Beats in one bar (in quarter notes). */
export const barBeats = (s: Pick<Song, "meter">): number => (s.meter[0] * 4) / s.meter[1];
/** Seconds per beat. */
export const beatSec = (s: Pick<Song, "bpm">): number => 60 / s.bpm;
export const songBeats = (s: Song): number => {
  let end = s.bars * barBeats(s);
  for (const t of s.tracks) for (const n of t.notes) end = Math.max(end, n[0] + n[1]);
  return end;
};
export const songSeconds = (s: Song): number => songBeats(s) * beatSec(s);

let seq = 0;
export const mid = (p: string): string => `${p}${Date.now().toString(36).slice(-4)}${(seq++).toString(36)}`;

export function newSong(o: Partial<Song> = {}): Song {
  return { id: mid("sng_"), name: o.name ?? "Song", bpm: o.bpm ?? 100, meter: o.meter ?? [4, 4], key: o.key ?? 0, scale: o.scale ?? "major", bars: o.bars ?? 16, swing: o.swing, humanize: o.humanize ?? 0.3, tracks: [], chords: [], sections: [], room: o.room ?? 0.5, gain: o.gain ?? 1 };
}
export function newTrack(o: Partial<Track> & { name: string }): Track {
  return { id: mid("trk_"), name: o.name, kind: o.kind ?? "inst", program: o.program ?? 0, bank: o.bank, instrument: o.instrument, volume: o.volume ?? 0.8, pan: o.pan ?? 0, reverb: o.reverb ?? 0.3, chorus: o.chorus, notes: o.notes ?? [], bends: o.bends, swell: o.swell, lyrics: o.lyrics, voice: o.voice };
}

const NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
const FLAT: Record<string, string> = { Db: "C#", Eb: "D#", Gb: "F#", Ab: "G#", Bb: "A#", Cb: "B", Fb: "E" };
/** "C4" → 60, "F#3" → 54, "Bb2" → 46. Numbers pass through. */
export function pitchOf(v: string | number): number {
  if (typeof v === "number") return v;
  const m = /^([A-Ga-g])([#b]?)(-?\d+)$/.exec(v.trim());
  if (!m) throw new Error(`"${v}" is not a note name. Write C4, F#3, Bb2 (C4 = middle C = 60).`);
  let n = m[1]!.toUpperCase() + m[2]!;
  n = FLAT[n] ?? n;
  return NAMES.indexOf(n) + (Number(m[3]) + 1) * 12;
}
export const pitchName = (p: number): string => `${NAMES[((p % 12) + 12) % 12]}${Math.floor(p / 12) - 1}`;
/** "C", "F#", "Bb" → pitch class. */
export function keyOf(v: string | number): number {
  if (typeof v === "number") return ((v % 12) + 12) % 12;
  let n = v.trim();
  n = n[0]!.toUpperCase() + n.slice(1);
  n = FLAT[n] ?? n;
  const i = NAMES.indexOf(n);
  if (i < 0) throw new Error(`"${v}" is not a key. Keys: ${NAMES.join(", ")} (or flats: Bb, Eb…).`);
  return i;
}
export const keyName = (k: number): string => NAMES[((k % 12) + 12) % 12]!;
