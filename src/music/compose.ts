/**
 * The composer: writes a whole arrangement from a short brief (style, mood, length, key, tempo): a chord plan,
 * a melody built from motifs, bass, chords, pads, arpeggios, drums or tabla-style hand percussion, and section
 * dynamics. Everything it writes is plain notes on tracks, so any of it can be edited afterwards.
 */
import { barBeats, newSong, newTrack, type ChordEvent, type Note, type Section, type Song, type Track } from "./model";
import { BOL, CHORDS, PROGRESSIONS, TAALS, degreeChord, harmonyNotes, scaleDef, scalePitches, voiceLead } from "./theory";

function rng(seed: number) {
  let s = (seed >>> 0) || 1;
  return () => {
    s ^= s << 13;
    s >>>= 0;
    s ^= s >>> 17;
    s ^= s << 5;
    s >>>= 0;
    return s / 4294967296;
  };
}
type Rnd = () => number;
const pick = <T,>(r: Rnd, a: readonly T[]): T => a[Math.floor(r() * a.length) % a.length]!;
const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v));

/** Instruments by General MIDI program number. */
const P = {
  piano: 0, brightPiano: 1, epiano: 4, dxpiano: 5, harpsichord: 6, celesta: 8, glock: 9, musicbox: 10, vibes: 11, marimba: 12, bells: 14, santur: 15,
  organ: 16, rockOrgan: 18, churchOrgan: 19, harmonium: 20, accordion: 21, nylon: 24, steel: 25, jazzGtr: 26, cleanGtr: 27, mutedGtr: 28, overdrive: 29, distortion: 30,
  upright: 32, fingerBass: 33, pickBass: 34, fretless: 35, synthBass: 38, synthBass2: 39, violin: 40, viola: 41, cello: 42, contrabass: 43, tremolo: 44, pizz: 45, harp: 46, timpani: 47,
  strings: 48, slowStrings: 49, synthStrings: 50, choir: 52, oohs: 53, orchHit: 55, trumpet: 56, trombone: 57, tuba: 58, horn: 60, brass: 61, synthBrass: 62,
  sopSax: 64, altoSax: 65, tenorSax: 66, oboe: 68, bassoon: 70, clarinet: 71, piccolo: 72, flute: 73, recorder: 74, panflute: 75, shakuhachi: 77, whistle: 78, ocarina: 79,
  square: 80, saw: 81, calliope: 82, charang: 84, fifths: 86, newage: 88, warmPad: 89, polysynth: 90, choirPad: 91, bowed: 92, halo: 94, sweep: 95, crystal: 98, atmosphere: 99, brightness: 100,
  sitar: 104, banjo: 105, shamisen: 106, koto: 107, kalimba: 108, bagpipe: 109, fiddle: 110, shehnai: 111, tinkle: 112, steelDrum: 114, woodblock: 115, taiko: 116, melodicTom: 117, revCymbal: 119,
} as const;
const KIT = { standard: 0, room: 8, power: 16, electronic: 24, tr808: 25, jazz: 32, brush: 40, orchestra: 48, trap: 80, dance: 81, lofi: 82, film: 83, tabla: 84 } as const;
/** The Studio bank (music/studio/bank.ts): instruments VideoStudio makes itself. Program numbers inside each bank. */
const M = { bass808: 0, reese: 1, acid: 2, fmBass: 3, sub: 4, superLead: 5, superPad: 6, superChords: 7, pluck: 8, fmPiano: 9, fmBell: 10, lofiKeys: 11, chipSquare: 12, chipPulse: 13, chipTri: 14, aah: 15, ooh: 16, analogPad: 17, glassPad: 18 } as const;
const IN = { sitar: 0, tanpura: 1, bansuri: 2, harmonium: 3, santoor: 4, shehnai: 5, sarangi: 6 } as const;
const F = { braam: 0, boom: 1, riser: 2, tension: 3, drone: 4, impact: 5, swell: 6 } as const;
/** General MIDI drum sounds. */
const D = { kick: 36, rim: 37, snare: 38, clap: 39, tomL: 41, hat: 42, tomM: 45, hatO: 46, tomH: 48, crash: 49, tomHH: 50, ride: 51, tamb: 54, cowbell: 56, bongoH: 60, bongoL: 61, congaM: 62, congaH: 63, congaL: 64, cabasa: 69, maracas: 70, claves: 75, triM: 80, triO: 81, shaker: 82 } as const;

type Role = "lead" | "counter" | "chords" | "pad" | "arp" | "bass" | "drums" | "perc" | "drone" | "ostinato" | "hits";
interface Part {
  role: Role;
  name: string;
  /** Program (or drum kit). */
  prog: number;
  /** Variation bank: 0 (or none) General MIDI; 100, 101, 102 the Studio bank's Modern, Indian and Film instruments. */
  bank?: number;
  /** How the part plays (meaning depends on the role). */
  how?: string;
  /** Plays only where the section's energy is at least this. */
  from?: number;
  /** …and at most this (intros and breakdowns). */
  upTo?: number;
  vol?: number;
  pan?: number;
  rev?: number;
  chorus?: number;
  /** Octave shift for this part. */
  oct?: number;
}
interface Style {
  about: string;
  bpm: [number, number];
  meter?: [number, number];
  scales: string[];
  /** Chord language: major | minor | epic | dark | drone (no changes: raga over a drone) | jazz. */
  harmony: string;
  swing?: number;
  /** Melody notes per beat, roughly: 0.5 slow … 2 busy. */
  density: number;
  /** Glides and grace notes on the lead (Indian and bowed styles). */
  ornament?: boolean;
  parts: Part[];
  /** Sections: [name, bars, energy]. Scaled to the length asked for. */
  form: [string, number, number][];
  taal?: string;
}

const part = (role: Role, name: string, prog: number, o: Partial<Part> = {}): Part => ({ role, name, prog, ...o });
/** Parts played by the Studio bank's Modern, Indian and Film instruments. */
const mod = (role: Role, name: string, prog: number, o: Partial<Part> = {}): Part => ({ role, name, prog, bank: 100, ...o });
const ind = (role: Role, name: string, prog: number, o: Partial<Part> = {}): Part => ({ role, name, prog, bank: 101, ...o });
const film = (role: Role, name: string, prog: number, o: Partial<Part> = {}): Part => ({ role, name, prog, bank: 102, ...o });

export const STYLES: Record<string, Style> = {
  cinematic_epic: {
    about: "Trailer-style orchestra: driving strings, horns, choir, big drums", bpm: [96, 126], scales: ["minor", "harmonic_minor", "dorian"], harmony: "epic", density: 0.7,
    parts: [part("ostinato", "Strings ostinato", P.strings, { how: "spiccato", from: 0.3, vol: 0.7, pan: -0.2 }), part("pad", "Strings", P.slowStrings, { vol: 0.62, rev: 0.5 }), part("pad", "Choir", P.choir, { from: 0.6, vol: 0.5, rev: 0.6, oct: 0 }), part("lead", "Horns", P.horn, { from: 0.35, vol: 0.85, rev: 0.5 }), part("counter", "Trumpets", P.trumpet, { from: 0.8, vol: 0.6, pan: 0.2 }), part("bass", "Low strings", P.contrabass, { how: "long", vol: 0.8 }), part("chords", "Piano", P.piano, { how: "ballad", upTo: 0.45, vol: 0.6 }), part("drums", "Film percussion", KIT.film, { how: "epic", from: 0.45, vol: 0.9 }), part("hits", "Timpani", P.timpani, { from: 0.55, vol: 0.8 }), film("hits", "Braam", F.braam, { how: "open", from: 0.8, vol: 0.6, rev: 0.4 })],
    form: [["intro", 4, 0.2], ["build", 8, 0.5], ["theme", 8, 0.85], ["breakdown", 4, 0.3], ["finale", 8, 1]],
  },
  action_battle: {
    about: "Fast, relentless: chopping strings, brass stabs, pounding drums", bpm: [138, 168], scales: ["minor", "phrygian", "harmonic_minor"], harmony: "dark", density: 1.1,
    parts: [part("ostinato", "Strings", P.strings, { how: "sixteenths", vol: 0.75, pan: -0.15 }), part("ostinato", "Low strings", P.cello, { how: "spiccato", vol: 0.7, oct: -1, pan: 0.15 }), part("lead", "Brass", P.brass, { from: 0.4, vol: 0.85 }), part("counter", "Horns", P.horn, { from: 0.75, vol: 0.6 }), part("bass", "Synth bass", P.synthBass, { how: "eighths", vol: 0.8 }), part("drums", "Power drums", KIT.power, { how: "battle", vol: 0.95 }), part("perc", "Taikos", KIT.film, { how: "epic", from: 0.6, vol: 0.8 }), part("hits", "Timpani", P.timpani, { from: 0.5, vol: 0.8 }), film("hits", "Braam", F.braam, { how: "open", from: 0.9, vol: 0.55, rev: 0.4 }), part("pad", "Choir", P.choir, { from: 0.7, vol: 0.5 })],
    form: [["intro", 4, 0.45], ["attack", 8, 0.8], ["surge", 8, 1], ["hold", 4, 0.55], ["final", 8, 1]],
  },
  emotional_piano: {
    about: "Slow piano with strings and a cello line", bpm: [60, 80], scales: ["minor", "major", "dorian"], harmony: "minor", density: 0.6,
    parts: [part("chords", "Piano", P.piano, { how: "ballad", vol: 0.85, rev: 0.45 }), part("lead", "Piano melody", P.piano, { vol: 0.9, rev: 0.45, upTo: 0.6 }), part("lead", "Cello", P.cello, { from: 0.6, vol: 0.8, rev: 0.5, oct: -1 }), part("pad", "Strings", P.slowStrings, { from: 0.4, vol: 0.55, rev: 0.6 }), part("bass", "Bass", P.contrabass, { how: "long", from: 0.5, vol: 0.6 })],
    form: [["intro", 4, 0.2], ["verse", 8, 0.4], ["rise", 8, 0.75], ["peak", 8, 0.9], ["outro", 4, 0.25]],
  },
  sad_violin: {
    about: "A solo violin over piano and soft strings", bpm: [56, 72], scales: ["minor", "harmonic_minor", "kirwani"], harmony: "minor", density: 0.6, ornament: true,
    parts: [part("lead", "Violin", P.violin, { vol: 0.9, rev: 0.55 }), part("chords", "Piano", P.piano, { how: "ballad", vol: 0.7, rev: 0.45 }), part("pad", "Strings", P.slowStrings, { from: 0.45, vol: 0.5, rev: 0.6 }), part("bass", "Cello", P.cello, { how: "long", from: 0.4, vol: 0.6, oct: 0 })],
    form: [["intro", 2, 0.2], ["verse", 8, 0.45], ["lament", 8, 0.8], ["outro", 4, 0.3]],
  },
  suspense: {
    about: "Tension: low drones, trembling strings, a pulse, sparse high piano", bpm: [70, 100], scales: ["phrygian", "harmonic_minor", "todi"], harmony: "dark", density: 0.35,
    parts: [film("drone", "Dark drone", F.drone, { vol: 0.7, rev: 0.5 }), film("pad", "Tension strings", F.tension, { vol: 0.5, rev: 0.5 }), part("lead", "High piano", P.piano, { vol: 0.6, rev: 0.7, oct: 1 }), part("ostinato", "Pulse", P.synthBass2, { how: "pulse", from: 0.4, vol: 0.55 }), part("hits", "Taiko", P.taiko, { from: 0.6, vol: 0.7 }), part("pad", "Pad", P.sweep, { from: 0.5, vol: 0.4 })],
    form: [["creep", 8, 0.3], ["closer", 8, 0.6], ["edge", 8, 0.9]],
  },
  horror: {
    about: "Unsettling: music box, clusters, breath of strings", bpm: [56, 76], scales: ["whole_tone", "phrygian", "marwa"], harmony: "dark", density: 0.4,
    parts: [part("lead", "Music box", P.musicbox, { vol: 0.7, rev: 0.7, oct: 1 }), part("pad", "Tremolo strings", P.tremolo, { vol: 0.5, rev: 0.6 }), film("drone", "Dark drone", F.drone, { vol: 0.55, rev: 0.7 }), mod("pad", "Choir", M.ooh, { from: 0.55, vol: 0.45, rev: 0.7 }), part("hits", "Timpani", P.timpani, { from: 0.7, vol: 0.6 })],
    form: [["lullaby", 8, 0.3], ["wrong", 8, 0.6], ["dread", 8, 0.9]],
  },
  adventure: {
    about: "Bright and heroic: trumpet theme, marching snare, sweeping strings", bpm: [108, 132], scales: ["major", "mixolydian", "lydian"], harmony: "major", density: 0.9,
    parts: [part("lead", "Trumpet", P.trumpet, { vol: 0.85 }), part("counter", "Horns", P.horn, { from: 0.6, vol: 0.65 }), part("pad", "Strings", P.strings, { vol: 0.6 }), part("ostinato", "Strings rhythm", P.strings, { how: "gallop", from: 0.4, vol: 0.6, pan: -0.2 }), part("bass", "Tuba and basses", P.contrabass, { how: "march", vol: 0.8 }), part("drums", "Orchestral drums", KIT.orchestra, { how: "march", from: 0.3, vol: 0.85 }), part("arp", "Harp", P.harp, { upTo: 0.5, vol: 0.6, pan: 0.3 }), part("hits", "Timpani", P.timpani, { from: 0.6, vol: 0.75 })],
    form: [["fanfare", 4, 0.5], ["theme", 8, 0.75], ["journey", 8, 0.55], ["return", 8, 1]],
  },
  anime_rock: {
    about: "Anime-opening energy: fast drums, driving guitars, a soaring lead", bpm: [150, 184], scales: ["minor", "major", "dorian"], harmony: "minor", density: 1.2,
    parts: [part("chords", "Rhythm guitar", P.distortion, { how: "power", vol: 0.7, pan: -0.35 }), part("chords", "Rhythm guitar 2", P.overdrive, { how: "power", from: 0.6, vol: 0.6, pan: 0.35 }), part("lead", "Lead", P.saw, { vol: 0.75, rev: 0.35, upTo: 0.69 }), part("lead", "Lead guitar", P.overdrive, { from: 0.7, vol: 0.8, rev: 0.35, oct: 1 }), part("bass", "Bass", P.pickBass, { how: "eighths", vol: 0.85 }), part("drums", "Drums", KIT.power, { how: "rock", vol: 0.95 }), part("pad", "Strings", P.strings, { from: 0.55, vol: 0.5 }), part("arp", "Piano", P.brightPiano, { upTo: 0.5, vol: 0.6 })],
    form: [["intro", 4, 0.6], ["verse", 8, 0.55], ["pre", 4, 0.75], ["chorus", 8, 1], ["outro", 4, 0.7]],
  },
  pop: {
    about: "Radio pop: piano and guitar, tight drums, a singable hook", bpm: [96, 124], scales: ["major", "minor", "mixolydian"], harmony: "major", density: 1,
    parts: [part("chords", "Piano", P.piano, { how: "pop", vol: 0.75 }), part("chords", "Guitar", P.steel, { how: "strum", from: 0.5, vol: 0.55, pan: 0.3 }), part("lead", "Lead", P.brightness, { vol: 0.7, rev: 0.4 }), part("bass", "Bass", P.fingerBass, { how: "pop", from: 0.35, vol: 0.85 }), part("drums", "Drums", KIT.standard, { how: "pop", from: 0.35, vol: 0.9 }), part("pad", "Pad", P.warmPad, { vol: 0.45 }), part("arp", "Bells", P.vibes, { from: 0.8, vol: 0.45, pan: -0.3 })],
    form: [["intro", 4, 0.3], ["verse", 8, 0.5], ["chorus", 8, 0.9], ["bridge", 4, 0.45], ["chorus", 8, 1], ["outro", 4, 0.35]],
  },
  lofi: {
    about: "Lo-fi study beat: warm electric piano sevenths, lazy swung drums", bpm: [68, 88], scales: ["dorian", "minor", "major"], harmony: "jazz", swing: 0.22, density: 0.6,
    parts: [mod("chords", "Lo-fi keys", M.lofiKeys, { how: "lofi", vol: 0.8, chorus: 0.3 }), part("bass", "Bass", P.fretless, { how: "lofi", vol: 0.8 }), part("drums", "Lo-fi drums", KIT.lofi, { how: "lofi", vol: 0.8 }), part("lead", "Vibes", P.vibes, { from: 0.45, vol: 0.6, rev: 0.5 }), part("pad", "Pad", P.warmPad, { vol: 0.35 }), part("arp", "Guitar", P.jazzGtr, { from: 0.7, vol: 0.5, pan: 0.3 })],
    form: [["intro", 4, 0.3], ["a", 8, 0.55], ["b", 8, 0.75], ["a", 8, 0.6], ["outro", 4, 0.3]],
  },
  edm: {
    about: "Festival dance: four-on-the-floor, big saw chords, a rising lead", bpm: [122, 130], scales: ["minor", "major"], harmony: "minor", density: 1,
    parts: [mod("chords", "Supersaw chords", M.superChords, { how: "stabs", from: 0.5, vol: 0.7 }), mod("pad", "Supersaw pad", M.superPad, { vol: 0.5 }), mod("lead", "Supersaw lead", M.superLead, { from: 0.45, vol: 0.7, rev: 0.4 }), mod("bass", "Bass", M.fmBass, { how: "offbeat", from: 0.4, vol: 0.9 }), part("drums", "Dance kit", KIT.dance, { how: "house", from: 0.3, vol: 0.95 }), mod("arp", "Pluck", M.pluck, { vol: 0.55, pan: 0.25 })],
    form: [["intro", 8, 0.3], ["build", 8, 0.6], ["drop", 8, 1], ["break", 4, 0.35], ["drop", 8, 1]],
  },
  synthwave: {
    about: "Eighties night drive: pulsing bass, wide pads, gated drums, a singing lead", bpm: [84, 110], scales: ["minor", "dorian"], harmony: "minor", density: 0.8,
    parts: [mod("bass", "Bass", M.fmBass, { how: "eighths", vol: 0.85 }), mod("pad", "Analog pad", M.analogPad, { vol: 0.6, chorus: 0.4 }), mod("arp", "Arp", M.pluck, { vol: 0.5, pan: 0.3 }), mod("lead", "Lead", M.superLead, { from: 0.45, vol: 0.65, rev: 0.5 }), part("drums", "Drums", KIT.dance, { how: "gated", from: 0.3, vol: 0.9 }), part("chords", "Brass", P.synthBrass, { how: "stabs", from: 0.8, vol: 0.5 })],
    form: [["intro", 4, 0.3], ["cruise", 8, 0.6], ["neon", 8, 0.9], ["drift", 4, 0.4], ["neon", 8, 1]],
  },
  hiphop: {
    about: "Trap-leaning beat: 808 kit, sub bass, dark keys, rolling hats", bpm: [130, 150], scales: ["minor", "phrygian", "harmonic_minor"], harmony: "dark", density: 0.6,
    parts: [part("chords", "Keys", P.piano, { how: "sparse", vol: 0.65, rev: 0.5 }), mod("lead", "Bell", M.fmBell, { from: 0.4, vol: 0.55, rev: 0.5, oct: 1 }), mod("bass", "808", M.bass808, { how: "trap", from: 0.35, vol: 0.95, oct: -1 }), part("drums", "Trap kit", KIT.trap, { how: "trap", from: 0.3, vol: 0.9 }), part("pad", "Pad", P.halo, { vol: 0.35 })],
    form: [["intro", 4, 0.3], ["verse", 8, 0.65], ["hook", 8, 0.95], ["verse", 8, 0.7], ["hook", 8, 1]],
  },
  jazz: {
    about: "Small-group swing: walking bass, ride cymbal, piano, a sax tune", bpm: [112, 160], scales: ["major", "dorian", "mixolydian"], harmony: "jazz", swing: 0.3, density: 1.1,
    parts: [part("lead", "Tenor sax", P.tenorSax, { vol: 0.8, rev: 0.35 }), part("chords", "Piano", P.piano, { how: "comp", vol: 0.6 }), part("bass", "Upright bass", P.upright, { how: "walk", vol: 0.85 }), part("drums", "Jazz kit", KIT.jazz, { how: "swing", vol: 0.75 })],
    form: [["head", 8, 0.6], ["solo", 8, 0.8], ["head", 8, 0.7], ["tag", 4, 0.5]],
  },
  comedy: {
    about: "Playful and sneaky: pizzicato strings, clarinet, woodblock", bpm: [100, 128], scales: ["major", "mixolydian"], harmony: "major", density: 1.1,
    parts: [part("ostinato", "Pizzicato", P.pizz, { how: "oompah", vol: 0.75 }), part("lead", "Clarinet", P.clarinet, { vol: 0.8 }), part("counter", "Bassoon", P.bassoon, { from: 0.6, vol: 0.6, oct: -1 }), part("bass", "Tuba", P.tuba, { how: "march", vol: 0.7 }), part("perc", "Woodblock", KIT.standard, { how: "woodblock", vol: 0.6 }), part("arp", "Xylophone", 13, { from: 0.7, vol: 0.55 })],
    form: [["sneak", 4, 0.4], ["tune", 8, 0.7], ["chase", 8, 1], ["bow", 2, 0.5]],
  },
  ambient: {
    about: "Slow, spacious pads and distant bells; no drums", bpm: [56, 72], scales: ["lydian", "major", "pentatonic", "yaman"], harmony: "major", density: 0.25,
    parts: [part("pad", "Pad", P.warmPad, { vol: 0.65, rev: 0.8 }), part("pad", "Halo", P.halo, { vol: 0.45, rev: 0.8, oct: 1 }), part("lead", "Bells", P.crystal, { vol: 0.5, rev: 0.85, oct: 1 }), part("drone", "Deep", P.bowed, { vol: 0.5, rev: 0.7 }), part("arp", "Harp", P.harp, { from: 0.5, vol: 0.45, rev: 0.7 })],
    form: [["air", 8, 0.3], ["light", 8, 0.6], ["air", 8, 0.4]],
  },
  chiptune: {
    about: "8-bit game music: square lead, fast arps, simple drums", bpm: [128, 168], scales: ["major", "minor"], harmony: "major", density: 1.4,
    parts: [mod("lead", "Square lead", M.chipSquare, { vol: 0.6, rev: 0.05 }), mod("arp", "Pulse arp", M.chipPulse, { how: "fast", vol: 0.4, rev: 0.05, pan: 0.3 }), mod("bass", "Triangle bass", M.chipTri, { how: "eighths", vol: 0.7, rev: 0.05, oct: -1 }), part("drums", "Kit", KIT.electronic, { how: "chip", vol: 0.7 })],
    form: [["title", 4, 0.6], ["stage", 8, 0.8], ["boss", 8, 1], ["clear", 4, 0.7]],
  },
  motivational: {
    about: "Uplifting corporate build: muted guitar pulse, piano, strings, claps", bpm: [104, 122], scales: ["major", "lydian"], harmony: "major", density: 0.8,
    parts: [part("ostinato", "Muted guitar", P.mutedGtr, { how: "eighths", vol: 0.6, pan: -0.3 }), part("chords", "Piano", P.piano, { how: "pop", vol: 0.7 }), part("pad", "Strings", P.strings, { from: 0.5, vol: 0.55 }), part("lead", "Bells", P.glock, { from: 0.55, vol: 0.5, oct: 1 }), part("bass", "Bass", P.fingerBass, { how: "pop", from: 0.45, vol: 0.8 }), part("drums", "Drums", KIT.standard, { how: "claps", from: 0.5, vol: 0.85 })],
    form: [["start", 4, 0.3], ["grow", 8, 0.55], ["lift", 8, 0.85], ["top", 8, 1]],
  },
  lullaby: {
    about: "Music box and celesta in three-four", bpm: [72, 92], meter: [3, 4], scales: ["major", "pentatonic"], harmony: "major", density: 0.7,
    parts: [part("lead", "Music box", P.musicbox, { vol: 0.75, rev: 0.6, oct: 1 }), part("arp", "Celesta", P.celesta, { how: "waltz", vol: 0.55, rev: 0.6 }), part("pad", "Strings", P.slowStrings, { from: 0.5, vol: 0.4, rev: 0.6 }), part("bass", "Harp bass", P.harp, { how: "long", vol: 0.55, oct: 0 })],
    form: [["a", 8, 0.4], ["b", 8, 0.65], ["a", 8, 0.45]],
  },
  bollywood_romantic: {
    about: "Film love song: guitar strum, flute and strings, tabla-style keherwa", bpm: [80, 104], scales: ["yaman", "khamaj", "pahadi", "charukeshi", "major"], harmony: "major", density: 0.9, ornament: true, taal: "keherwa",
    parts: [ind("lead", "Bansuri", IN.bansuri, { vol: 0.85, rev: 0.5 }), ind("lead", "Sitar", IN.sitar, { from: 0.72, vol: 0.8, rev: 0.4, upTo: 0.9 }), part("chords", "Guitar", P.nylon, { how: "strum", vol: 0.65, pan: -0.25 }), part("pad", "Strings", P.strings, { from: 0.45, vol: 0.6, rev: 0.5 }), part("counter", "Violins", P.violin, { from: 0.75, vol: 0.55, rev: 0.5, pan: 0.25 }), part("bass", "Bass", P.fingerBass, { how: "pop", from: 0.35, vol: 0.8 }), part("perc", "Tabla", KIT.tabla, { how: "taal", from: 0.3, vol: 0.85 }), part("arp", "Santoor", P.santur, { upTo: 0.5, vol: 0.55, rev: 0.5, pan: 0.3 })],
    form: [["alaap", 4, 0.25], ["mukhda", 8, 0.6], ["antara", 8, 0.8], ["mukhda", 8, 0.95], ["end", 4, 0.4]],
  },
  bollywood_dance: {
    about: "Party number: dhol groove, claps, a bright hook", bpm: [104, 128], scales: ["khamaj", "kafi", "mixolydian", "bilawal"], harmony: "major", swing: 0.12, density: 1.2, taal: "bhangra",
    parts: [part("lead", "Hook (tumbi-style)", P.banjo, { vol: 0.75, rev: 0.25 }), ind("lead", "Shehnai", IN.shehnai, { from: 0.8, vol: 0.7, rev: 0.35 }), part("chords", "Synth brass", P.synthBrass, { how: "stabs", from: 0.5, vol: 0.55 }), part("bass", "Bass", P.synthBass, { how: "offbeat", vol: 0.9 }), part("drums", "Kit", KIT.power, { how: "dhol", vol: 0.95 }), part("perc", "Dhol and dholak", KIT.tabla, { how: "taal", vol: 0.9 }), part("pad", "Strings", P.strings, { from: 0.6, vol: 0.45 })],
    form: [["intro", 4, 0.5], ["verse", 8, 0.7], ["hook", 8, 1], ["break", 4, 0.5], ["hook", 8, 1]],
  },
  indian_classical: {
    about: "Raga performance: sitar over a drone, slow opening, then tabla-style teentaal", bpm: [72, 132], scales: ["yaman", "bhairav", "bhimpalasi", "malkauns", "desh", "bageshri", "darbari", "todi"], harmony: "drone", density: 1, ornament: true, taal: "teentaal",
    parts: [ind("lead", "Sitar", IN.sitar, { vol: 0.95, rev: 0.4 }), ind("drone", "Tanpura", IN.tanpura, { how: "tanpura", vol: 0.5, rev: 0.5, pan: 0.2 }), part("perc", "Tabla", KIT.tabla, { how: "taal", from: 0.4, vol: 0.9 }), ind("counter", "Bansuri", IN.bansuri, { from: 0.8, vol: 0.6, rev: 0.5, pan: -0.25 }), part("pad", "Drone pad", P.bowed, { vol: 0.3, rev: 0.6 })],
    form: [["alaap", 8, 0.2], ["jor", 8, 0.5], ["gat", 8, 0.75], ["jhala", 8, 1]],
  },
  devotional: {
    about: "Bhajan: harmonium, flute, hand drums and small cymbals", bpm: [76, 104], scales: ["bhupali", "yaman", "bhairavi", "durga", "kafi"], harmony: "drone", density: 0.8, ornament: true, taal: "bhajani",
    parts: [ind("chords", "Harmonium", IN.harmonium, { how: "harmonium", vol: 0.7 }), ind("lead", "Bansuri", IN.bansuri, { vol: 0.85, rev: 0.5 }), ind("drone", "Tanpura", IN.tanpura, { how: "tanpura", vol: 0.45, rev: 0.5 }), part("perc", "Tabla and manjira", KIT.tabla, { how: "taal", from: 0.35, vol: 0.85 }), mod("pad", "Voices", M.ooh, { from: 0.7, vol: 0.45, rev: 0.6 }), ind("counter", "Santoor", IN.santoor, { from: 0.6, vol: 0.6, pan: 0.3 })],
    form: [["invocation", 4, 0.25], ["sthayi", 8, 0.6], ["antara", 8, 0.8], ["sthayi", 8, 0.95]],
  },
  folk_garba: {
    about: "Festival dance: fast hand drums, shehnai, clapping sticks", bpm: [116, 140], scales: ["kafi", "khamaj", "bilawal", "pahadi"], harmony: "drone", density: 1.2, taal: "garba",
    parts: [ind("lead", "Shehnai", IN.shehnai, { vol: 0.8, rev: 0.35 }), ind("chords", "Harmonium", IN.harmonium, { how: "harmonium", vol: 0.55 }), part("perc", "Dhol and dholak", KIT.tabla, { how: "taal", vol: 0.9 }), part("perc", "Sticks and claps", KIT.tabla, { how: "dandiya", from: 0.45, vol: 0.7 }), part("bass", "Bass", P.fingerBass, { how: "pop", from: 0.5, vol: 0.75 }), part("counter", "Flute", P.flute, { from: 0.75, vol: 0.55 })],
    form: [["call", 4, 0.5], ["round", 8, 0.75], ["faster", 8, 1], ["round", 8, 0.9]],
  },
  trap: {
    about: "Modern trap: long 808 bass, rolling hats, bell and pluck melodies", bpm: [130, 150], scales: ["minor", "phrygian", "harmonic_minor"], harmony: "dark", density: 0.7,
    parts: [mod("lead", "Bell", M.fmBell, { vol: 0.6, rev: 0.5, oct: 1 }), mod("arp", "Pluck", M.pluck, { from: 0.55, vol: 0.5, rev: 0.4, pan: 0.25 }), mod("chords", "Dark keys", M.lofiKeys, { how: "sparse", vol: 0.6, rev: 0.5 }), mod("bass", "808", M.bass808, { how: "trap", from: 0.35, vol: 1, oct: -1 }), part("drums", "Trap kit", KIT.trap, { how: "trap", from: 0.3, vol: 0.9 }), mod("pad", "Choir", M.ooh, { from: 0.6, vol: 0.35, rev: 0.6 })],
    form: [["intro", 4, 0.3], ["verse", 8, 0.65], ["hook", 8, 0.95], ["verse", 8, 0.7], ["hook", 8, 1]],
  },
  drill: {
    about: "Drill: sliding 808s, late snares, hats in threes, a cold piano", bpm: [138, 146], scales: ["minor", "phrygian", "harmonic_minor"], harmony: "dark", density: 0.6,
    parts: [part("chords", "Piano", P.piano, { how: "sparse", vol: 0.7, rev: 0.45 }), mod("lead", "Bell", M.fmBell, { from: 0.5, vol: 0.5, rev: 0.5, oct: 1 }), mod("bass", "808", M.bass808, { how: "trap", from: 0.3, vol: 1, oct: -1 }), part("drums", "Trap kit", KIT.trap, { how: "drill", from: 0.3, vol: 0.9 }), film("pad", "Tension strings", F.tension, { from: 0.6, vol: 0.3, rev: 0.5 })],
    form: [["intro", 4, 0.3], ["verse", 8, 0.7], ["hook", 8, 0.95], ["verse", 8, 0.75], ["hook", 8, 1]],
  },
  phonk: {
    about: "Phonk: driven 808, hard kick and clap, a ringing bell riff", bpm: [120, 160], scales: ["phrygian", "minor", "minor_pentatonic"], harmony: "dark", density: 1.1,
    parts: [mod("lead", "Bell riff", M.fmBell, { vol: 0.7, rev: 0.3, oct: 1 }), mod("bass", "808", M.bass808, { how: "eighths", vol: 1, oct: -1 }), part("drums", "Trap kit", KIT.trap, { how: "house", vol: 0.95 }), mod("pad", "Choir", M.aah, { from: 0.5, vol: 0.35, rev: 0.6 }), mod("arp", "Pluck", M.pluck, { from: 0.8, vol: 0.45, pan: -0.25 })],
    form: [["intro", 4, 0.5], ["ride", 8, 0.8], ["peak", 8, 1], ["ride", 8, 0.85]],
  },
  future_bass: {
    about: "Future bass: wide supersaw chords that swell and fall, bright plucks, a big drop", bpm: [140, 160], scales: ["major", "lydian", "minor"], harmony: "major", density: 0.9,
    parts: [mod("chords", "Supersaw chords", M.superChords, { how: "stabs", from: 0.5, vol: 0.8 }), mod("pad", "Supersaw pad", M.superPad, { vol: 0.5, rev: 0.5 }), mod("lead", "Lead", M.superLead, { from: 0.6, vol: 0.6, rev: 0.4, oct: 1 }), mod("arp", "Pluck", M.pluck, { vol: 0.55, rev: 0.4, pan: 0.25 }), mod("bass", "Sub", M.sub, { how: "long", from: 0.4, vol: 0.95, oct: -1 }), part("drums", "Dance kit", KIT.dance, { how: "trap", from: 0.45, vol: 0.9 }), mod("pad", "Voices", M.aah, { from: 0.8, vol: 0.35, rev: 0.6 })],
    form: [["intro", 4, 0.3], ["build", 8, 0.6], ["drop", 8, 1], ["break", 4, 0.35], ["drop", 8, 1]],
  },
  house: {
    about: "House: four-on-the-floor, off-beat bass, warm keys and a vocal pad", bpm: [120, 128], scales: ["minor", "dorian", "major"], harmony: "jazz", density: 0.8,
    parts: [mod("chords", "Electric piano", M.fmPiano, { how: "comp", vol: 0.7, chorus: 0.3 }), mod("bass", "Bass", M.fmBass, { how: "offbeat", from: 0.35, vol: 0.9 }), part("drums", "Dance kit", KIT.dance, { how: "house", from: 0.25, vol: 0.95 }), mod("pad", "Analog pad", M.analogPad, { vol: 0.45 }), mod("lead", "Pluck", M.pluck, { from: 0.5, vol: 0.6, rev: 0.4 }), mod("pad", "Voices", M.ooh, { from: 0.75, vol: 0.35, rev: 0.6 })],
    form: [["intro", 8, 0.3], ["groove", 8, 0.65], ["lift", 8, 0.9], ["break", 4, 0.4], ["lift", 8, 1]],
  },
  drum_and_bass: {
    about: "Drum and bass: fast two-step break, deep moving bass, airy pads", bpm: [168, 176], scales: ["minor", "dorian"], harmony: "minor", density: 0.8,
    parts: [mod("bass", "Reese bass", M.reese, { how: "long", from: 0.35, vol: 0.95, oct: -1 }), part("drums", "Dance kit", KIT.dance, { how: "dnb", from: 0.3, vol: 0.95 }), mod("pad", "Glass pad", M.glassPad, { vol: 0.5, rev: 0.6 }), mod("chords", "Electric piano", M.fmPiano, { how: "sparse", vol: 0.6, rev: 0.5 }), mod("lead", "Lead", M.superLead, { from: 0.7, vol: 0.5, rev: 0.5 }), mod("arp", "Pluck", M.pluck, { from: 0.55, vol: 0.45, pan: 0.3 })],
    form: [["intro", 8, 0.3], ["roll", 8, 0.8], ["deep", 8, 0.6], ["roll", 8, 1]],
  },
  reggaeton: {
    about: "Reggaeton: the dembow beat, a plucked hook, warm bass", bpm: [88, 100], scales: ["minor", "harmonic_minor", "dorian"], harmony: "minor", density: 1,
    parts: [mod("lead", "Pluck hook", M.pluck, { vol: 0.7, rev: 0.3 }), part("chords", "Guitar", P.nylon, { how: "strum", from: 0.5, vol: 0.55, pan: -0.25 }), mod("bass", "Bass", M.sub, { how: "pop", from: 0.3, vol: 0.95, oct: -1 }), part("drums", "Dance kit", KIT.dance, { how: "dembow", from: 0.3, vol: 0.9 }), mod("pad", "Pad", M.analogPad, { vol: 0.4 }), mod("arp", "Bell", M.fmBell, { from: 0.8, vol: 0.4, pan: 0.3, oct: 1 })],
    form: [["intro", 4, 0.4], ["verse", 8, 0.65], ["coro", 8, 0.95], ["verse", 8, 0.7], ["coro", 8, 1]],
  },
  afrobeats: {
    about: "Afrobeats: a loping groove of shakers and hand drums, bright guitar, soft keys", bpm: [98, 114], scales: ["major", "mixolydian", "dorian"], harmony: "major", swing: 0.08, density: 1,
    parts: [part("arp", "Guitar", P.cleanGtr, { vol: 0.6, pan: 0.3 }), mod("chords", "Electric piano", M.fmPiano, { how: "comp", vol: 0.65 }), mod("lead", "Pluck", M.pluck, { from: 0.45, vol: 0.6, rev: 0.35 }), mod("bass", "Bass", M.sub, { how: "pop", from: 0.3, vol: 0.9, oct: -1 }), part("drums", "Lo-fi kit", KIT.lofi, { how: "afro", from: 0.25, vol: 0.9 }), mod("pad", "Voices", M.ooh, { from: 0.7, vol: 0.4, rev: 0.5 })],
    form: [["intro", 4, 0.4], ["verse", 8, 0.65], ["hook", 8, 0.95], ["verse", 8, 0.7], ["hook", 8, 1]],
  },
  punjabi_pop: {
    about: "Punjabi pop: dhol and 808 together, a tumbi-style hook, shehnai on the drop", bpm: [92, 108], scales: ["khamaj", "kafi", "mixolydian", "minor"], harmony: "minor", swing: 0.1, density: 1.2, taal: "bhangra",
    parts: [part("lead", "Tumbi-style hook", P.banjo, { vol: 0.75, rev: 0.25 }), ind("lead", "Shehnai", IN.shehnai, { from: 0.85, vol: 0.65, rev: 0.35 }), mod("bass", "808", M.bass808, { how: "trap", vol: 1, oct: -1 }), part("drums", "Trap kit", KIT.trap, { how: "trap", from: 0.35, vol: 0.85 }), part("perc", "Dhol", KIT.tabla, { how: "taal", vol: 0.9 }), mod("chords", "Supersaw chords", M.superChords, { how: "stabs", from: 0.7, vol: 0.5 }), ind("pad", "Harmonium", IN.harmonium, { upTo: 0.6, vol: 0.4 })],
    form: [["intro", 4, 0.5], ["verse", 8, 0.7], ["hook", 8, 1], ["break", 4, 0.5], ["hook", 8, 1]],
  },
  sufi: {
    about: "Sufi and qawwali: harmonium, claps, tabla and dholak, a soaring line", bpm: [84, 116], scales: ["bhairavi", "kafi", "yaman", "khamaj"], harmony: "drone", density: 0.9, ornament: true, taal: "keherwa",
    parts: [ind("lead", "Sarangi", IN.sarangi, { vol: 0.8, rev: 0.45 }), ind("chords", "Harmonium", IN.harmonium, { how: "harmonium", vol: 0.75 }), ind("drone", "Tanpura", IN.tanpura, { how: "tanpura", vol: 0.45, rev: 0.5 }), part("perc", "Tabla and dholak", KIT.tabla, { how: "taal", from: 0.35, vol: 0.9 }), part("perc", "Claps", KIT.tabla, { how: "claps", from: 0.6, vol: 0.6 }), mod("pad", "Chorus voices", M.aah, { from: 0.75, vol: 0.45, rev: 0.6 })],
    form: [["alaap", 4, 0.25], ["sthayi", 8, 0.6], ["antara", 8, 0.8], ["sthayi", 8, 1]],
  },
  trailer: {
    about: "Hybrid trailer: braams, risers and booms under a pounding string pulse", bpm: [100, 140], scales: ["minor", "phrygian", "harmonic_minor"], harmony: "epic", density: 0.5,
    parts: [part("ostinato", "Strings pulse", P.strings, { how: "sixteenths", from: 0.4, vol: 0.7, pan: -0.15 }), film("hits", "Braam", F.braam, { how: "open", vol: 0.8, rev: 0.4 }), film("drone", "Dark drone", F.drone, { vol: 0.5, rev: 0.5 }), part("drums", "Film percussion", KIT.film, { how: "epic", from: 0.5, vol: 0.95 }), mod("bass", "Sub", M.sub, { how: "long", from: 0.5, vol: 0.9, oct: -1 }), part("lead", "Horns", P.horn, { from: 0.7, vol: 0.8, rev: 0.5 }), mod("pad", "Choir", M.aah, { from: 0.8, vol: 0.5, rev: 0.6 }), film("pad", "Tension strings", F.tension, { upTo: 0.5, vol: 0.4, rev: 0.5 })],
    form: [["dread", 4, 0.3], ["rise", 8, 0.6], ["assault", 8, 1], ["silence", 2, 0.2], ["final", 8, 1]],
  },
  japanese: {
    about: "Koto and shakuhachi over taiko", bpm: [72, 110], scales: ["japanese", "hirajoshi", "minor_pentatonic"], harmony: "drone", density: 0.8, ornament: true,
    parts: [part("lead", "Shakuhachi", P.shakuhachi, { vol: 0.85, rev: 0.6 }), part("arp", "Koto", P.koto, { vol: 0.7, rev: 0.45, pan: 0.25 }), part("chords", "Shamisen", P.shamisen, { how: "sparse", from: 0.55, vol: 0.6, pan: -0.25 }), part("hits", "Taiko", P.taiko, { from: 0.45, vol: 0.85 }), part("drone", "Low strings", P.contrabass, { vol: 0.5, rev: 0.5 }), part("pad", "Strings", P.slowStrings, { from: 0.7, vol: 0.4 })],
    form: [["mist", 4, 0.25], ["garden", 8, 0.55], ["storm", 8, 0.95], ["mist", 4, 0.3]],
  },
};

export const MOODS: Record<string, { scale?: "major" | "minor"; tempo: number; energy: number; scales?: string[] }> = {
  happy: { scale: "major", tempo: 0.7, energy: 0.1 }, joyful: { scale: "major", tempo: 0.8, energy: 0.15 }, hopeful: { scale: "major", tempo: 0.5, energy: 0 }, romantic: { tempo: 0.35, energy: -0.05, scales: ["yaman", "khamaj", "major"] },
  sad: { scale: "minor", tempo: 0.15, energy: -0.1 }, melancholy: { scale: "minor", tempo: 0.25, energy: -0.08 }, dark: { scale: "minor", tempo: 0.4, energy: 0, scales: ["phrygian", "harmonic_minor"] }, tense: { scale: "minor", tempo: 0.6, energy: 0.05 },
  epic: { scale: "minor", tempo: 0.7, energy: 0.12 }, heroic: { scale: "major", tempo: 0.7, energy: 0.12 }, calm: { tempo: 0.2, energy: -0.15 }, playful: { scale: "major", tempo: 0.75, energy: 0.05 }, mysterious: { scale: "minor", tempo: 0.3, energy: -0.05, scales: ["dorian", "phrygian"] },
  devotional: { tempo: 0.35, energy: -0.05, scales: ["bhupali", "yaman", "bhairavi"] }, angry: { scale: "minor", tempo: 0.9, energy: 0.2 },
};

export interface Brief {
  style: string;
  mood?: string;
  /** Length: bars, or seconds (rounded to whole bars). */
  bars?: number;
  seconds?: number;
  bpm?: number;
  key?: number;
  scale?: string;
  seed?: number;
  name?: string;
  /** Replace the lead instrument (program, and its variation bank when it is not a General MIDI one). */
  lead?: number;
  leadBank?: number;
  leadName?: string;
  taal?: string;
  /** No melody (a bed to sing or speak over). */
  noLead?: boolean;
  /**
   * A song with words: blocks of lines (verse, chorus…), each line already split into the syllables that will be
   * sung (the voice engine does the splitting). The tune is written to the words: one note per syllable.
   */
  vocal?: { voice: string; range: [number, number]; blocks: { name: string; lines: string[][] }[] };
}

/* ------------------------------------------------------------------ harmony */

function planChords(song: Song, st: Style, secs: Section[], r: Rnd): ChordEvent[] {
  const bb = barBeats(song);
  const out: ChordEvent[] = [];
  const def = scaleDef(song.scale);
  let hs = harmonyNotes(song.scale);
  // chords of a minor key come from the natural minor; the raised notes belong to the tune and the dominant
  if (def.family === "western" && (song.scale === "harmonic_minor" || song.scale === "melodic_minor")) hs = [0, 2, 3, 5, 7, 8, 10];
  const isMinor = hs ? hs[2] === 3 : !!def.minor;
  const fam = st.harmony === "jazz" || st.harmony === "major" || st.harmony === "minor" ? (isMinor ? "minor" : "major") : st.harmony;
  if (fam === "drone" || !hs) {
    // one home sonority all the way: the raga carries the colour, as it does over a tanpura
    for (let b = 0; b < song.bars; b++) out.push({ at: b * bb, dur: bb, root: song.key, kind: def.up.includes(7) || def.down?.includes(7) ? "5" : "sus4" });
    return out;
  }
  const bank = PROGRESSIONS[fam] ?? PROGRESSIONS.minor!;
  const byName = new Map<string, (number | [number, number])[]>();
  for (const s of secs) {
    const base = s.name.replace(/\d+$/, "");
    if (!byName.has(base)) byName.set(base, pick(r, bank));
    const prog = byName.get(base)!;
    for (let b = 0; b < s.bars; b++) {
      const cell = prog[b % prog.length]!;
      const last = b === s.bars - 1 && s === secs[secs.length - 1];
      const mk = (deg: number, at: number, dur: number) => {
        let c = degreeChord(song.key, hs!, deg, st.harmony === "jazz");
        // the dominant of a minor key wants its major third at a cadence (not in a raga, where that note is foreign)
        if (isMinor && def.family === "western" && deg === 4 && c.kind === "m" && (b % 4 === 3 || st.harmony === "epic")) c = { root: c.root, kind: st.harmony === "jazz" ? "7" : "" };
        if (c.kind === "dim" && st.harmony !== "jazz") c = degreeChord(song.key, hs!, deg + 5);
        if (c.kind === "aug") c = { root: c.root, kind: "" };
        out.push({ at, dur, root: c.root, kind: c.kind });
      };
      const at = (s.bar + b) * bb;
      if (last) mk(0, at, bb);
      else if (Array.isArray(cell)) {
        mk(cell[0], at, bb / 2);
        mk(cell[1], at + bb / 2, bb / 2);
      } else mk(cell, at, bb);
    }
  }
  return out;
}
const chordAt = (ch: ChordEvent[], beat: number): ChordEvent => {
  let c = ch[0]!;
  for (const x of ch) if (x.at <= beat + 1e-6) c = x;
  return c;
};
const chordTones = (c: ChordEvent): number[] => (CHORDS[c.kind] ?? CHORDS[""]!).map((x) => (x + c.root) % 12);

/* ------------------------------------------------------------------ melody */

/** A bar's rhythm: [start beat in bar, length]. */
function barRhythm(r: Rnd, bb: number, density: number, cadence = false): [number, number][] {
  const out: [number, number][] = [];
  let t = 0;
  if (cadence) {
    const cut = bb >= 4 ? bb / 2 : bb >= 3 ? 1 : bb / 2;
    if (r() < 0.5 && bb >= 3) return [[0, 1], [1, cut - 1 > 0 ? cut - 1 : 1], [cut, bb - cut]].filter((x) => x[1]! > 0) as [number, number][];
    return [[0, cut], [cut, bb - cut]];
  }
  // sometimes enter after the downbeat
  if (r() < 0.18 * density) t = 0.5;
  while (t < bb - 1e-6) {
    const left = bb - t;
    const onBeat = Math.abs(t - Math.round(t)) < 1e-6;
    const w: [number, number][] = [];
    if (density > 1.25 && onBeat) w.push([0.25, 0.8 * (density - 1)]);
    w.push([0.5, 0.9 * density]);
    if (onBeat) w.push([1, 1.3], [1.5, 0.5], [2, 0.9 / Math.max(0.4, density)]);
    else w.push([1, 0.5]);
    if (onBeat && density < 0.6 && left >= 3) w.push([3, 0.5]);
    const ok = w.filter((x) => x[0] <= left + 1e-6);
    let sum = 0;
    for (const x of ok) sum += x[1];
    let q = r() * sum, d = ok[0]![0];
    for (const x of ok) {
      q -= x[1];
      if (q <= 0) {
        d = x[0];
        break;
      }
    }
    if (d === 0.25) {
      out.push([t, 0.25], [t + 0.25, 0.25]);
      t += 0.5;
    } else {
      // a breath now and then
      if (r() < 0.1 / Math.max(0.5, density) && out.length) t += Math.min(d, 0.5);
      else out.push([t, d]);
      t += d;
    }
  }
  return out.filter((x) => x[0] < bb - 1e-6).map((x) => [x[0], Math.min(x[1], bb - x[0])] as [number, number]);
}

interface MelodyOpts {
  /** The harmony moves in chords (false: a raga over a drone, where every note of the scale may be dwelt on). */
  tonal: boolean;
  lo: number;
  hi: number;
  density: number;
  ornament: boolean;
  energy: number;
  hook: boolean;
  /** Rhythm given from outside (words): per bar of the section, [start beat in bar, length]. */
  fixed?: [number, number][][];
  /** Bars (within the section) on whose last note a line of words ends. */
  lineEnds?: Set<number>;
}

function writeMelody(song: Song, sec: Section, chords: ChordEvent[], r: Rnd, o: MelodyOpts): { notes: Note[]; bends: [number, number][] } {
  const bb = barBeats(song);
  const def = scaleDef(song.scale);
  // a minor-key tune sings the natural minor and raises its seventh only over the dominant chord
  const raisable = def.family === "western" && (song.scale === "harmonic_minor" || song.scale === "melodic_minor" || song.scale === "minor");
  const mel = song.scale === "harmonic_minor" || song.scale === "melodic_minor" ? "minor" : song.scale;
  const up = scalePitches(song.key, mel, o.lo - 2, o.hi + 2), dn = scalePitches(song.key, mel, o.lo - 2, o.hi + 2, true);
  const rest = new Set((def.rest ?? [0, 7]).map((x) => (x + song.key) % 12));
  const notes: Note[] = [];
  const bends: [number, number][] = [];
  const near = (arr: number[], p: number): number => {
    let b = 0, c = 1e9;
    arr.forEach((x, i) => {
      if (Math.abs(x - p) < c) {
        c = Math.abs(x - p);
        b = i;
      }
    });
    return b;
  };
  const tonic = song.key;
  let cur = up[near(up, (o.lo + o.hi) / 2 - 2 + Math.floor(r() * 5))]!;
  const phrase = sec.bars >= 4 ? 4 : sec.bars;
  const need = Math.max(3, Math.round(o.density * bb * (o.hook ? 0.7 : 0.45)));
  const newMotif = (): [number, number][] => {
    let m = barRhythm(r, bb, o.density);
    for (let k = 0; k < 6 && m.length < need; k++) m = barRhythm(r, bb, o.density * (1 + k * 0.12));
    return m;
  };
  let motif: [number, number][] = newMotif();
  let motifSteps: number[] = [];
  let dir = r() < 0.6 ? 1 : -1;
  for (let b = 0; b < sec.bars; b++) {
    const inPhrase = b % phrase;
    const lastBar = b === sec.bars - 1;
    const lastOfPhrase = o.lineEnds ? o.lineEnds.has(b) : inPhrase === phrase - 1 || lastBar;
    const bar0 = (sec.bar + b) * bb;
    let rhythm: [number, number][];
    if (o.fixed) rhythm = o.fixed[b] ?? [];
    else if (lastOfPhrase) rhythm = barRhythm(r, bb, o.density, true);
    else if (inPhrase === 0) {
      if (b > 0 && !o.hook && r() < 0.5) motif = newMotif();
      rhythm = motif;
    } else if (inPhrase === 1) rhythm = motif;
    else rhythm = o.hook ? motif : barRhythm(r, bb, Math.min(2, o.density * 1.15));
    const reuse = !o.fixed && (inPhrase === 1 || (o.hook && inPhrase === 2));
    const steps: number[] = [];
    if (inPhrase === 0) dir = r() < 0.65 ? 1 : -1;
    else if (inPhrase === 2) dir = 1;
    else if (lastOfPhrase) dir = -1;
    rhythm.forEach(([st, du], k) => {
      const beat = bar0 + st;
      const ch = chordAt(chords, beat);
      const tones = chordTones(ch);
      const strong = Math.abs(st - Math.round(st)) < 1e-6 && (st === 0 || Math.abs(st - bb / 2) < 1e-6);
      const isLast = k === rhythm.length - 1;
      let p: number;
      const arr = dir < 0 ? dn : up;
      const i = near(arr, cur);
      if (reuse && motifSteps[k] !== undefined && !strong) p = arr[clamp(i + motifSteps[k]!, 0, arr.length - 1)]!;
      else if (lastOfPhrase && isLast) {
        // cadence: come home at the end of a section, hang on a resting note otherwise
        const closing = o.lineEnds ? b === Math.max(...o.lineEnds) : lastBar;
        const want = closing ? [tonic] : [...rest].filter((x) => x !== tonic).concat(def.up.includes(7) ? [(tonic + 7) % 12] : []);
        const pool = (want.length ? want : [tonic]);
        let best = cur, cost = 1e9;
        for (let q = Math.max(o.lo, cur - 7); q <= Math.min(o.hi, cur + 7); q++) if (pool.includes(((q % 12) + 12) % 12) && Math.abs(q - cur) < cost) {
          cost = Math.abs(q - cur);
          best = q;
        }
        p = best;
      } else if (strong || k === 0) {
        // land on a chord tone (or a resting note of the raga) near the line
        let best = cur, cost = 1e9;
        for (let j = Math.max(0, i - 4); j <= Math.min(arr.length - 1, i + 4); j++) {
          const q = arr[j]!, pc = ((q % 12) + 12) % 12;
          if (o.tonal && !tones.includes(pc) && !(def.family === "raga" && rest.has(pc))) continue;
          // over a drone any note of the raga may be landed on; its resting notes and Sa pull strongest
          const c2 = Math.abs(j - i - dir * 1.2) + (q === cur ? 1.4 : 0) + r() * (o.tonal ? 0.8 : 1.6) - (!o.tonal && (rest.has(pc) || pc === tonic) ? 0.9 : 0);
          if (c2 < cost) {
            cost = c2;
            best = q;
          }
        }
        p = best;
      } else {
        const x = r();
        let stp = x < 0.68 ? dir : x < 0.86 ? dir * 2 : x < 0.93 ? -dir : dir * (3 + Math.floor(r() * 2));
        if (steps.length && Math.abs(steps[steps.length - 1]!) >= 3) stp = -Math.sign(steps[steps.length - 1]!);
        p = arr[clamp(i + stp, 0, arr.length - 1)]!;
      }
      // a held note must not grind against the chord: a semitone from a chord tone resolves onto it
      if (o.tonal && du >= 1 && !(lastOfPhrase && isLast)) {
        const pc = ((p % 12) + 12) % 12;
        if (!tones.includes(pc) && (tones.includes((pc + 1) % 12) || tones.includes((pc + 11) % 12))) {
          const j0 = near(arr, p);
          let found = false;
          for (const dj of [dir, -dir, 2 * dir, -2 * dir, 3 * dir, -3 * dir]) {
            const q = arr[clamp(j0 + dj, 0, arr.length - 1)]!;
            if (q !== cur && tones.includes(((q % 12) + 12) % 12)) {
              p = q;
              found = true;
              break;
            }
          }
          if (!found) p = cur;
        }
      }
      if (raisable && tones.includes((tonic + 11) % 12) && ((p % 12) + 12) % 12 === (tonic + 10) % 12) p += 1;
      // stay in range: turn round at the edges
      if (p > o.hi) {
        p = arr[clamp(near(arr, o.hi) - 1, 0, arr.length - 1)]!;
        dir = -1;
      } else if (p < o.lo) {
        p = arr[clamp(near(arr, o.lo) + 1, 0, arr.length - 1)]!;
        dir = 1;
      }
      steps.push(near(up, p) - near(up, cur));
      const accent = strong ? 10 : Math.abs(st - Math.round(st)) < 1e-6 ? 3 : -3;
      const swell = Math.sin(Math.PI * ((b % phrase) + st / bb) / phrase) * 8;
      const vel = clamp(Math.round(72 + o.energy * 22 + accent + swell + (r() - 0.5) * 6), 30, 124);
      const len = lastOfPhrase && isLast ? du * 0.96 : du * (du >= 1 ? 0.94 : 0.88);
      notes.push([beat, len, p, vel]);
      if (o.ornament) {
        const iv = p - cur;
        // meend: slide into the note from the one before
        if (Math.abs(iv) >= 1 && Math.abs(iv) <= 4 && du >= 0.75 && r() < 0.5) {
          bends.push([beat - 0.01, 0], [beat, -iv], [beat + Math.min(0.32, du * 0.4), 0]);
        } else if (du >= 1.5 && r() < 0.45) {
          // andolan: a slow, shallow sway on a long note
          const n = Math.floor(du * 2);
          for (let w = 0; w <= n; w++) bends.push([beat + 0.3 + (w / n) * (du - 0.4), w % 2 ? 0.28 : 0]);
          bends.push([beat + du, 0]);
        } else if (du >= 0.5 && r() < 0.22) {
          // kan: a grace note from the neighbour
          const g = arr[clamp(near(arr, p) + (r() < 0.5 ? 1 : -1), 0, arr.length - 1)]!;
          bends.push([beat - 0.01, 0], [beat, g - p], [beat + 0.09, 0]);
        }
      }
      cur = p;
    });
    if (inPhrase === 0) motifSteps = steps;
  }
  bends.sort((a, b) => a[0] - b[0]);
  return { notes, bends };
}

/**
 * Set a line of words to a rhythm: one note per syllable over `beats` beats, mostly even eighths, stressed
 * syllables that fall on a beat held a little longer, the last syllable held, and a breath before the next line.
 * Returns [start beat, length] per syllable.
 */
export function wordRhythm(syl: string[], beats: number, r: Rnd): [number, number][] {
  const S = syl.length;
  if (!S) return [];
  const breath = beats >= 6 ? 1 : 0.5;
  let unit = 0.5;
  if (S * unit > beats - breath) unit = 0.25;
  if (S * unit > beats - 0.25) unit = Math.max(0.125, (beats - 0.25) / S);
  const d = new Array<number>(S).fill(unit);
  let free = beats - breath - S * unit;
  // hold the last syllable
  const hold = Math.min(free, unit >= 0.5 ? 1.5 : 1);
  if (hold > 0) {
    d[S - 1] = d[S - 1]! + hold;
    free -= hold;
  }
  // lengthen stressed syllables that start on a beat, then any on a beat, while there is room
  for (const pass of [0, 1, 2]) {
    let t = 0;
    for (let i = 0; i < S - 1 && free >= unit - 1e-6; i++) {
      const onBeat = Math.abs(t - Math.round(t)) < 1e-6;
      const stressed = /[ˈˌ]/.test(syl[i]!) || /ː/.test(syl[i]!);
      if (onBeat && (pass === 2 || (pass === 0 ? stressed : r() < 0.5)) && d[i]! < unit * 2 + 1e-6) {
        d[i] = d[i]! + unit;
        free -= unit;
      }
      t += d[i]!;
    }
  }
  // what room is left becomes a late entry (a pick-up) rather than a long silence at the end
  let t = free >= 1 ? Math.floor(free) * (r() < 0.6 ? 0.5 : 0) : 0;
  const out: [number, number][] = [];
  for (let i = 0; i < S; i++) {
    out.push([t, d[i]!]);
    t += d[i]!;
  }
  return out;
}

/* ------------------------------------------------------------------ parts */

function drumBar(how: string, r: Rnd, bb: number, e: number, bar: number, fill: boolean, first: boolean): [number, number, number][] {
  // [beat, drum, loudness 0…1]
  const o: [number, number, number][] = [];
  const hit = (b: number, d: number, v = 1) => { if (b < bb - 1e-6) o.push([b, d, v]); };
  const each = (step: number, fn: (b: number, i: number) => void) => { for (let b = 0, i = 0; b < bb - 1e-6; b += step, i++) fn(b, i); };
  if (first && e > 0.4 && how !== "lofi" && how !== "swing" && how !== "woodblock" && how !== "dandiya") hit(0, D.crash, 0.85);
  switch (how) {
    case "pop": case "claps":
      hit(0, D.kick); hit(bb / 2, D.kick, 0.9); if (e > 0.6) hit(bb / 2 - 0.5, D.kick, 0.7); if (e > 0.8 && bar % 2) hit(bb - 0.5, D.kick, 0.6);
      each(2, (b) => hit(b + 1, how === "claps" ? D.clap : D.snare, 0.95));
      each(e > 0.75 ? 0.25 : 0.5, (b, i) => hit(b, D.hat, (i % 2 ? 0.45 : 0.7) * (e > 0.75 && i % 4 ? 0.7 : 1)));
      if (e > 0.7) hit(bb - 0.5, D.hatO, 0.6);
      break;
    case "rock":
      each(1, (b, i) => hit(b, i % 2 ? D.snare : D.kick, 1)); hit(1.5, D.kick, 0.8); if (bb >= 4) hit(2.5, D.kick, 0.85); if (e > 0.85) each(0.5, (b) => hit(b + 0.25, D.kick, 0.5));
      each(0.5, (b, i) => hit(b, e > 0.8 ? D.ride : D.hat, i % 2 ? 0.55 : 0.8));
      break;
    case "battle":
      each(0.5, (b, i) => hit(b, D.kick, i % 2 ? 0.7 : 1)); each(2, (b) => hit(b + 1, D.snare, 1)); each(0.25, (b, i) => { if (i % 4 === 2 || (e > 0.8 && i % 4 === 3)) hit(b, D.tomL, 0.7); });
      each(1, (b) => hit(b, D.hat, 0.6)); if (bar % 4 === 0) hit(0, D.crash, 0.9);
      break;
    case "epic":
      hit(0, D.kick, 1); hit(0, D.tomL, 0.9); if (bb >= 4) { hit(2.5, D.tomL, 0.8); hit(3, D.kick, 0.9); hit(3, D.tomM, 0.85); }
      if (e > 0.7) { hit(1.5, D.tomL, 0.7); hit(3.5, D.tomM, 0.75); hit(3.75, D.tomH, 0.7); }
      if (e > 0.85) each(2, (b) => hit(b + 1, D.snare, 0.8));
      if (bar % 4 === 0) hit(0, D.crash, 0.9);
      break;
    case "march":
      each(1, (b, i) => hit(b, i % 2 ? D.snare : D.kick, i % 2 ? 0.8 : 1)); each(1, (b) => { hit(b + 0.5, D.snare, 0.45); if (e > 0.6) hit(b + 0.75, D.snare, 0.5); });
      if (bar % 4 === 0 && e > 0.6) hit(0, D.crash, 0.8);
      break;
    case "house": case "gated":
      each(1, (b) => hit(b, D.kick, 1)); each(2, (b) => hit(b + 1, how === "gated" ? D.snare : D.clap, 0.95)); each(1, (b) => hit(b + 0.5, D.hatO, 0.6));
      if (e > 0.7) each(0.25, (b, i) => { if (i % 2) hit(b, D.hat, 0.4); });
      break;
    case "trap":
      hit(0, D.kick, 1); if (bar % 2) { hit(1.5, D.kick, 0.8); hit(2.75, D.kick, 0.85); } else hit(2.5, D.kick, 0.9);
      if (bb >= 4) hit(2, D.snare, 1); else hit(bb - 1, D.snare, 1);
      each(0.5, (b, i) => hit(b, D.hat, i % 2 ? 0.5 : 0.75));
      if (e > 0.6) { const s0 = Math.floor(r() * 3) + 0.5; for (let k = 0; k < 6; k++) hit(s0 + k / 12, D.hat, 0.4 + k * 0.06); }
      if (bar % 4 === 3) hit(bb - 0.5, D.hatO, 0.6);
      break;
    case "drill":
      // sliding, off-centre kicks, the snare late in the bar, hats in threes
      hit(0, D.kick, 1); hit(bar % 2 ? 1.25 : 0.75, D.kick, 0.8); hit(2.5, D.kick, 0.9); if (e > 0.7) hit(3.25, D.kick, 0.7);
      hit(1.5, D.snare, 0.9); if (bb >= 4) hit(3.5, D.snare, 1);
      for (const b of [0, 0.75, 1.5, 2, 2.75, 3.5]) hit(b, D.hat, 0.7);
      if (e > 0.6) for (let k = 0; k < 3; k++) hit((bar % 2 ? 2.25 : 1) + k / 6, D.hat, 0.45 + k * 0.08);
      if (bar % 4 === 3) hit(bb - 0.25, D.hatO, 0.55);
      break;
    case "dnb":
      // the two-step break of drum and bass
      hit(0, D.kick, 1); hit(2.5, D.kick, 0.95); if (e > 0.8 && bar % 2) hit(1.75, D.kick, 0.6);
      hit(1, D.snare, 1); hit(3, D.snare, 1); if (e > 0.6) { hit(1.75, D.snare, 0.35); hit(3.75, D.snare, 0.4); }
      each(0.5, (b, i) => hit(b, D.hat, i % 2 ? 0.5 : 0.7)); if (e > 0.75) each(0.25, (b, i) => { if (i % 4 === 3) hit(b, D.hat, 0.35); });
      if (bar % 4 === 0 && e > 0.6) hit(0, D.crash, 0.8);
      break;
    case "dembow":
      // reggaeton: kick on every beat, the snare answering before two and before four
      each(1, (b) => hit(b, D.kick, 1)); for (const b of [0.75, 1.5, 2.75, 3.5]) hit(b, D.snare, b % 1 === 0.5 ? 0.95 : 0.8);
      if (e > 0.5) each(0.5, (b, i) => hit(b, D.hat, i % 2 ? 0.4 : 0.55)); if (e > 0.8) each(1, (b) => hit(b + 0.5, D.shaker, 0.4));
      break;
    case "afro":
      // afrobeats: a loping kick, rim on two and four, shaker and hand drums between
      hit(0, D.kick, 1); hit(1.5, D.kick, 0.7); hit(2, D.kick, 0.9); if (bar % 2) hit(3.5, D.kick, 0.75);
      hit(1, D.rim, 0.85); hit(3, D.rim, 0.9); if (e > 0.7) hit(3, D.clap, 0.6);
      each(0.25, (b, i) => hit(b, D.shaker, i % 4 === 0 ? 0.55 : i % 2 ? 0.3 : 0.42));
      for (const b of [0.75, 1.75, 2.5, 3.25]) hit(b, bar % 2 ? D.congaH : D.congaM, 0.6); if (e > 0.6) hit(2.75, D.congaL, 0.7);
      break;
    case "lofi":
      hit(0, D.kick, 0.9); hit(bb / 2 + 0.5, D.kick, 0.75); if (bar % 2) hit(bb / 2 - 0.5, D.kick, 0.6);
      each(2, (b) => hit(b + 1, D.rim, 0.8)); each(0.5, (b, i) => hit(b, D.hat, i % 2 ? 0.35 : 0.55));
      break;
    case "swing":
      each(1, (b, i) => { hit(b, D.ride, i % 2 ? 0.8 : 0.6); if (i % 2) hit(b + 0.5, D.ride, 0.5); }); each(2, (b) => hit(b + 1, 44, 0.6));
      if (r() < 0.4) hit(Math.floor(r() * bb) + 0.5, D.snare, 0.35); if (bar % 4 === 0) hit(0, D.kick, 0.5);
      break;
    case "chip":
      each(1, (b, i) => hit(b, i % 2 ? D.snare : D.kick, 0.9)); each(0.5, (b) => hit(b, D.hat, 0.5));
      break;
    case "dhol":
      hit(0, D.kick, 1); hit(0.75, D.kick, 0.7); hit(1.5, D.kick, 0.9); if (bb >= 4) { hit(2, D.kick, 1); hit(2.75, D.kick, 0.7); hit(3.5, D.kick, 0.85); }
      each(2, (b) => hit(b + 1, D.clap, 0.9)); each(0.5, (b, i) => hit(b, D.tamb, i % 2 ? 0.4 : 0.6));
      break;
    case "dandiya":
      each(1, (b, i) => hit(b, D.claves, i % 2 ? 0.7 : 0.9)); each(2, (b) => hit(b + 1.5, D.claves, 0.6)); if (e > 0.7) each(2, (b) => hit(b + 1, D.clap, 0.7));
      break;
    case "woodblock":
      each(1, (b, i) => hit(b + (i % 2 ? 0.5 : 0), i % 2 ? 76 : 77, 0.7)); if (bar % 2) hit(bb - 0.25, 76, 0.6);
      break;
    default:
      each(1, (b, i) => hit(b, i % 2 ? D.snare : D.kick, 0.9));
  }
  if (fill && e > 0.35 && !["lofi", "swing", "woodblock", "dandiya", "chip", "afro", "dembow"].includes(how)) {
    // a fill over the last beat or two
    const start = bb - (e > 0.7 ? 2 : 1);
    const keep = o.filter((x) => x[0] < start || x[1] === D.kick);
    const toms = [D.snare, D.snare, D.tomH, D.tomH, D.tomM, D.tomM, D.tomL, D.tomL];
    const n = (bb - start) * 4;
    for (let k = 0; k < n; k++) if (k % 2 === 0 || e > 0.6) keep.push([start + k / 4, toms[Math.floor((k / n) * toms.length)]!, 0.6 + (k / n) * 0.4]);
    return keep;
  }
  return o;
}

function taalBar(taal: string, r: Rnd, bb: number, e: number, bar: number, fill: boolean): [number, number, number][] {
  const T = TAALS[taal] ?? TAALS.keherwa!;
  const o: [number, number, number][] = [];
  // strokes are eighths for the 8-beat folk cycles and quarters for the classical ones
  const step = T.unit === 8 ? 0.5 : 1;
  const n = Math.round(bb / step);
  for (let i = 0; i < n; i++) {
    const idx = (bar * n + i) % T.bols.length;
    const bol = T.bols[idx]!;
    const sam = idx === 0;
    for (const [d, v] of BOL[bol] ?? []) o.push([i * step, d, v * (sam ? 1 : 0.82) * (0.75 + 0.25 * e)]);
    // lighter filler strokes between the main ones as the energy rises
    if (e > 0.55 && step >= 0.5 && r() < e * 0.7) o.push([i * step + step / 2, r() < 0.5 ? 62 : 61, 0.42]);
    if (e > 0.85 && step === 0.5) o.push([i * step + 0.25, 62, 0.3]);
  }
  if (fill && e > 0.4) {
    // tihai: a phrase played three times that lands on the next downbeat
    const keep = o.filter((x) => x[0] < bb - 1.5);
    for (let k = 0; k < 3; k++) for (const [j, bol] of (["ti", "na", "dha"] as const).entries()) for (const [d, v] of BOL[bol]!) keep.push([bb - 1.5 + k * 0.5 + j * (0.5 / 3), d, v * (0.7 + k * 0.12)]);
    return keep;
  }
  // small cymbals (manjira) keep time in devotional music
  if (taal === "bhajani") for (let b = 0; b < bb; b += 1) o.push([b, b % 2 ? D.triM : D.triO, 0.45]);
  if (taal === "garba" || taal === "bhangra") for (let b = 0; b < bb; b += 0.5) o.push([b, D.shaker, b % 1 ? 0.3 : 0.45]);
  return o;
}

function writePart(song: Song, p: Part, secs: Section[], chords: ChordEvent[], r: Rnd, st: Style, lead: { notes: Note[] } | null): Track | null {
  const bb = barBeats(song);
  const tr = newTrack({ name: p.name, kind: p.role === "drums" || p.role === "perc" ? "drums" : "inst", program: p.prog, bank: p.bank, volume: p.vol ?? 0.7, pan: p.pan ?? 0, reverb: p.rev ?? (p.role === "drums" || p.role === "perc" || p.role === "bass" ? 0.12 : 0.35), chorus: p.chorus });
  const N = tr.notes;
  const oct = (p.oct ?? 0) * 12;
  let prevVoicing: number[] | null = null;
  for (const s of secs) {
    if (s.energy < (p.from ?? 0) - 1e-6 || s.energy > (p.upTo ?? 1) + 1e-6) continue;
    const e = s.energy;
    const vel = (base: number) => clamp(Math.round(base * (0.62 + 0.38 * e) + (r() - 0.5) * 6), 20, 125);
    for (let b = 0; b < s.bars; b++) {
      const bar = s.bar + b, at = bar * bb;
      const lastBar = b === s.bars - 1;
      const cells = chords.filter((c) => c.at >= at - 1e-6 && c.at < at + bb - 1e-6);
      const first = cells[0] ?? chordAt(chords, at);
      switch (p.role) {
        case "drums": {
          for (const [bt, d, v] of drumBar(p.how ?? "pop", r, bb, e, bar, lastBar || b % 4 === 3 && e > 0.6, b === 0)) N.push([at + bt, 0.2, d, vel(60 + v * 55)]);
          break;
        }
        case "perc": {
          const how = p.how ?? "taal";
          if (how === "taal") for (const [bt, d, v] of taalBar(song.brief?.taal as string ?? st.taal ?? "keherwa", r, bb, e, bar, lastBar)) N.push([at + bt, 0.2, d, vel(50 + v * 62)]);
          else for (const [bt, d, v] of drumBar(how, r, bb, e, bar, false, false)) N.push([at + bt, 0.2, d, vel(60 + v * 50)]);
          break;
        }
        case "bass": {
          for (const c of cells.length ? cells : [first]) {
            const root = 36 + ((c.bass ?? c.root) % 12) + oct + (c.root % 12 > 7 ? -12 : 0);
            const fifth = root + 7, o8 = root + 12;
            const c0 = c.at, d = c.dur;
            const how = p.how ?? "pop";
            if (how === "long") N.push([c0, d * 0.98, root, vel(88)]);
            else if (how === "eighths") for (let x = 0; x < d - 1e-6; x += 0.5) N.push([c0 + x, 0.42, x % 2 === 1.5 && r() < 0.3 ? o8 : root, vel(x % 1 ? 82 : 96)]);
            else if (how === "offbeat") for (let x = 0.5; x < d - 1e-6; x += 1) N.push([c0 + x, 0.4, root, vel(98)]);
            else if (how === "march") for (let x = 0; x < d - 1e-6; x += 1) N.push([c0 + x, 0.7, x % 2 ? fifth - 12 > 28 ? fifth - 12 : fifth : root, vel(92)]);
            else if (how === "walk") {
              const tones = [root, root + (CHORDS[c.kind]?.[1] ?? 4), fifth, root + (CHORDS[c.kind]?.[3] ?? 9)];
              for (let x = 0; x < d - 1e-6; x += 1) N.push([c0 + x, 0.9, x === 0 ? root : x >= d - 1 ? root + (r() < 0.5 ? 1 : -1) * (r() < 0.5 ? 1 : 2) + 0 : pick(r, tones), vel(88)]);
            } else if (how === "trap") {
              N.push([c0, Math.min(d, 1.5), root, vel(108)]);
              if (d >= 4) N.push([c0 + 2.5, 1.2, r() < 0.3 ? root + 3 : root, vel(100)]);
            } else if (how === "lofi") {
              N.push([c0, 1.4, root, vel(90)]);
              if (d >= 3) N.push([c0 + 2.5, 0.9, r() < 0.5 ? fifth : o8, vel(76)]);
            } else {
              // pop: root with a push into the next beat and a lift at the end of the bar
              N.push([c0, Math.min(d, 1.4), root, vel(96)]);
              if (d >= 2) N.push([c0 + 1.5, 0.45, root, vel(80)]);
              if (d >= 4) {
                N.push([c0 + 2, 1.2, e > 0.7 ? o8 : root, vel(92)]);
                N.push([c0 + 3.5, 0.45, r() < 0.5 ? fifth : root, vel(78)]);
              }
            }
          }
          break;
        }
        case "pad": case "chords": case "arp": case "ostinato": {
          for (const c of cells.length ? cells : [first]) {
            const v = voiceLead(c, prevVoicing, 60 + oct);
            prevVoicing = v;
            const c0 = c.at, d = c.dur;
            const how = p.how ?? (p.role === "pad" ? "hold" : p.role === "arp" ? "arp" : p.role === "ostinato" ? "spiccato" : "block");
            if (p.role === "pad" || how === "hold" || how === "harmonium") {
              const top = how === "harmonium" ? v.slice(0, 3) : v;
              for (const q of top) N.push([c0, d * 0.995, q, vel(how === "harmonium" ? 74 : 66)]);
              if (p.role === "pad" && e > 0.7) N.push([c0, d * 0.995, v[0]! + 12, vel(58)]);
            } else if (how === "ballad") {
              // left hand rolls the chord, right hand answers
              const lh = [v[0]! - 12, v[0]! - 5, v[0]!, v[1]!];
              for (let x = 0, k = 0; x < d - 1e-6; x += 0.5, k++) N.push([c0 + x, 0.9, lh[k % lh.length === 3 && k % 8 > 4 ? 1 : k % lh.length]!, vel(k === 0 ? 78 : 60)]);
              if (e > 0.35) for (const q of v.slice(1)) N.push([c0 + (d >= 4 ? 1 : 0.5), d * 0.6, q + (e > 0.7 ? 12 : 0), vel(62)]);
            } else if (how === "pop" || how === "comp" || how === "lofi" || how === "sparse") {
              const hits = how === "pop" ? (e > 0.6 ? [0, 1.5, 2.5, 3] : [0, 2]) : how === "comp" ? [0.5 + Math.floor(r() * 2), 2.5 + (r() < 0.5 ? 0 : 1)] : how === "lofi" ? [0, 1.5 + (r() < 0.4 ? 0.5 : 0)] : [0];
              const len = how === "sparse" || how === "lofi" ? d * 0.9 : how === "comp" ? 0.6 : 0.9;
              for (const hx of hits) if (hx < d - 1e-6) v.forEach((q, i) => N.push([c0 + hx + (how === "lofi" ? i * 0.03 : 0), Math.min(len, d - hx), q, vel(hx === 0 ? 76 : 64)]));
              if (how === "pop") N.push([c0, Math.min(d, 2), v[0]! - 12, vel(70)]);
            } else if (how === "strum") {
              // down and up strokes, the strings spread a few milliseconds apart
              const pat = e > 0.6 ? [0, 1, 1.5, 2.5, 3, 3.5] : [0, 1.5, 2, 3.5];
              const six = [v[0]! - 12, v[0]! - 5, v[0]!, v[1]!, v[2]!, v[0]! + 12];
              pat.forEach((hx, k) => {
                if (hx >= d - 1e-6) return;
                const downStroke = Math.abs(hx - Math.round(hx)) < 1e-6;
                (downStroke ? six : [...six].reverse().slice(0, 4)).forEach((q, i) => N.push([c0 + hx + i * 0.012, 0.42, q, vel((downStroke ? 78 : 62) - i * 2 + (k === 0 ? 8 : 0))]));
              });
            } else if (how === "power") {
              const root = 40 + ((c.root + 8) % 12) + oct;
              for (let x = 0; x < d - 1e-6; x += 0.5) for (const q of [root, root + 7, root + 12]) N.push([c0 + x, e > 0.75 ? 0.46 : 0.3, q, vel(x % 1 ? 84 : 98)]);
            } else if (how === "stabs") {
              const pat = e > 0.8 ? [0, 0.75, 1.5, 2.5, 3.25] : [0, 1.5, 3];
              for (const hx of pat) if (hx < d - 1e-6) for (const q of v) N.push([c0 + hx, 0.3, q + 12, vel(88)]);
            } else if (how === "spiccato" || how === "sixteenths" || how === "gallop" || how === "eighths" || how === "pulse" || how === "oompah") {
              const stepLen = how === "sixteenths" ? 0.25 : 0.5;
              for (let x = 0, k = 0; x < d - 1e-6; x += stepLen, k++) {
                if (how === "gallop" && k % 4 === 1) continue;
                if (how === "pulse") {
                  N.push([c0 + x, 0.3, 36 + (c.root % 12), vel(k % 2 ? 60 : 78)]);
                  continue;
                }
                if (how === "oompah") {
                  if (k % 2 === 0) N.push([c0 + x, 0.3, v[0]! - 12, vel(84)]);
                  else for (const q of v.slice(0, 3)) N.push([c0 + x, 0.22, q, vel(64)]);
                  continue;
                }
                const acc = k % (how === "sixteenths" ? 4 : 2) === 0;
                const q = how === "eighths" ? v[0]! : acc ? v[0]! : v[(k % 2) + 1] ?? v[1]!;
                N.push([c0 + x, stepLen * 0.55, q, vel(acc ? 92 : 68)]);
                if (acc && e > 0.75 && how !== "eighths") N.push([c0 + x, stepLen * 0.55, q + 12, vel(70)]);
              }
            } else {
              // arpeggio: broken chord, up and over
              const stepLen = how === "fast" ? 0.25 : how === "waltz" ? 1 : 0.5;
              const seq = how === "waltz" ? [v[0]!, v[1]! + 12, v[2]! + 12] : [v[0]!, v[1]!, v[2]!, v[0]! + 12, v[2]!, v[1]!];
              for (let x = 0, k = 0; x < d - 1e-6; x += stepLen, k++) N.push([c0 + x, stepLen * (how === "fast" ? 0.9 : 1.6), seq[k % seq.length]! + (how === "fast" ? 12 : 0), vel(k % (seq.length) === 0 ? 76 : 60)]);
            }
          }
          break;
        }
        case "drone": {
          const root = 36 + (song.key % 12) + oct;
          if (p.how === "tanpura") {
            // Pa, Sa, Sa, low Sa: the tanpura's four-string cycle
            const fifthOk = scaleDef(song.scale).up.includes(7);
            const cyc = [root + (fifthOk ? 19 : 17), root + 24, root + 24, root + 12];
            for (let x = 0, k = 0; x < bb - 1e-6; x += bb / 4, k++) N.push([at + x, bb / 4 * 1.8, cyc[k % 4]!, vel(k % 4 === 0 ? 70 : 62)]);
          } else {
            if (b % 2 === 0) {
              N.push([at, Math.min(2, s.bars - b) * bb * 0.995, root, vel(72)]);
              N.push([at, Math.min(2, s.bars - b) * bb * 0.995, root + 7, vel(54)]);
            }
          }
          break;
        }
        case "hits": {
          const root = 36 + (first.root % 12) + (p.prog === P.timpani ? 0 : 0);
          if (p.how === "open") {
            // one long blast where a section opens, and again at its middle when it is long
            if (b === 0 || (s.bars >= 8 && b === s.bars / 2)) N.push([at, Math.min(bb * 2, 8), root - 12 + oct, vel(112)]);
            break;
          }
          N.push([at, 1.5, root, vel(104)]);
          if (e > 0.7 && bb >= 4) {
            N.push([at + 2.5, 0.4, root, vel(84)]);
            N.push([at + 3, 0.9, root + (b % 2 ? 7 : 0), vel(96)]);
          }
          if (lastBar && e > 0.5) for (let k = 0; k < 8; k++) N.push([at + bb - 1 + k / 8, 0.12, root, vel(60 + k * 7)]);
          break;
        }
        case "counter": {
          // answer the lead in thirds or sixths on its longer notes
          if (!lead) break;
          const western = scaleDef(song.scale).family === "western";
          const sc = scalePitches(song.key, song.scale === "harmonic_minor" || song.scale === "melodic_minor" ? "minor" : song.scale, 36, 96);
          for (const n of lead.notes) {
            if (n[0] < at - 1e-6 || n[0] >= at + bb - 1e-6 || n[1] < 0.9) continue;
            const tones = chordTones(chordAt(chords, n[0]));
            let i = sc.indexOf(n[2]);
            if (i < 0) i = sc.indexOf(n[2] - 1);
            if (i < 2) continue;
            let q = sc[i - (r() < 0.6 ? 2 : 5)] ?? n[2] - 4;
            // keep the answering voice out of the mud: go above the tune instead
            if (q + oct < 55) q = sc[i + 2] ?? n[2] + 3;
            if (western && tones.includes((song.key + 11) % 12) && ((q % 12) + 12) % 12 === (song.key + 10) % 12) q += 1;
            N.push([n[0], n[1], q + oct, clamp(n[3] - 14, 20, 110)]);
          }
          break;
        }
        default:
          break;
      }
    }
  }
  return N.length ? tr : null;
}

/* ------------------------------------------------------------------ the whole song */

export function composeSong(b: Brief): Song {
  const st = STYLES[b.style];
  if (!st) throw new Error(`Unknown style "${b.style}". Styles: ${Object.keys(STYLES).join(", ")}.`);
  const seed = b.seed ?? Math.floor(Math.random() * 1e9);
  const r = rng(seed * 7919 + 13);
  const mood = b.mood ? MOODS[b.mood] : undefined;
  if (b.mood && !mood) throw new Error(`Unknown mood "${b.mood}". Moods: ${Object.keys(MOODS).join(", ")}.`);
  let scale = b.scale;
  if (!scale) {
    const cands = st.scales.filter((s) => !mood?.scale || !!scaleDef(s).minor === (mood.scale === "minor"));
    const liked = (mood?.scales ?? []).filter((s) => st.scales.includes(s));
    scale = pick(r, liked.length ? liked : cands.length ? cands : st.scales);
  }
  scaleDef(scale);
  const bpm = b.bpm ?? Math.round(st.bpm[0] + (st.bpm[1] - st.bpm[0]) * (mood ? mood.tempo : 0.3 + r() * 0.5));
  const meter = st.meter ?? [4, 4];
  const key = b.key ?? pick(r, [0, 2, 4, 5, 7, 9, 10, 3]);
  const song = newSong({ name: b.name ?? `${b.style.replace(/_/g, " ")}${b.mood ? " · " + b.mood : ""}`, bpm, meter, key, scale, swing: st.swing, humanize: 0.35, room: 0.5 });
  song.brief = { ...b, seed, taal: b.taal ?? st.taal };
  const bb = barBeats(song);
  if (b.vocal) return composeWithWords(song, st, b, r, mood?.energy ?? 0);
  // scale the form to the length asked for
  const formBars = st.form.reduce((a, f) => a + f[1], 0);
  let want = b.bars ?? (b.seconds ? Math.max(2, Math.round((b.seconds * bpm) / 60 / bb)) : formBars);
  want = clamp(Math.round(want), 2, 400);
  const secs: Section[] = [];
  const k = want / formBars;
  // phrases are four bars long: sections are sized in fours (twos for short pieces), the largest ones absorb the rest
  const unit = want >= 12 ? 4 : want >= 4 ? 2 : 1;
  const sizes = st.form.map((f) => Math.max(unit, Math.round((f[1] * k) / unit) * unit));
  const total = () => sizes.reduce((a, x) => a + x, 0);
  for (let guard = 0; guard < 400 && total() !== want; guard++) {
    const over = total() > want;
    let bi = -1;
    // when there is too much: shorten the longest; once everything is at its shortest, drop repeats, then bridges, then intros and endings
    const spare = (i: number): number => {
      const nm = st.form[i]![0];
      const repeat = st.form.findIndex((f) => f[0] === nm) !== i;
      return (repeat ? 4 : 0) + (/bridge|break|hold|drift|tag|bow|pre/.test(nm) ? 3 : 0) + (/intro|outro|end|start|alaap|invocation|mist|air|call|title|sneak|creep|fanfare/.test(nm) ? 2 : 0) + i * 0.01;
    };
    for (let i = 0; i < sizes.length; i++) {
      if (sizes[i]! <= 0) continue;
      if (over && sizes[i]! <= unit && sizes.some((x) => x > unit)) continue;
      const allMin = !sizes.some((x) => x > unit);
      if (bi < 0 || (over ? (allMin ? spare(i) > spare(bi) : sizes[i]! > sizes[bi]!) : st.form[i]![2] > st.form[bi]![2] || (st.form[i]![2] === st.form[bi]![2] && sizes[i]! < sizes[bi]!))) bi = i;
    }
    if (bi < 0) break;
    const step = Math.min(unit, Math.abs(total() - want));
    sizes[bi] = sizes[bi]! + (over ? -step : step);
    if (sizes[bi]! <= 0) sizes[bi] = 0;
  }
  let bar = 0;
  st.form.forEach(([name, , energy], i) => {
    const n = sizes[i]!;
    if (n <= 0) return;
    secs.push({ name, bar, bars: n, energy: clamp(energy + (mood?.energy ?? 0), 0.1, 1) });
    bar += n;
  });
  song.bars = want;
  song.sections = secs;
  song.chords = planChords(song, st, secs, r);

  // lead first: the other parts answer it
  const leadParts = st.parts.filter((p) => p.role === "lead");
  const leadAll: Note[] = [];
  const hooks = new Map<string, { notes: Note[]; bends: [number, number][] }>();
  const perLead = new Map<Part, { notes: Note[]; bends: [number, number][] }>();
  if (!b.noLead) {
    for (const s of secs) {
      const active = leadParts.filter((p) => s.energy >= (p.from ?? 0) - 1e-6 && s.energy <= (p.upTo ?? 1) + 1e-6);
      if (!active.length) continue;
      const p = active[active.length - 1]!;
      const base = s.name.replace(/\d+$/, "");
      const o8 = (p.oct ?? 0) * 12;
      const high = /chorus|hook|theme|drop|peak|final|return|surge|top|neon|antara/.test(base) ? 4 : 0;
      let m: { notes: Note[]; bends: [number, number][] };
      const again = hooks.get(base);
      if (again) {
        // a returning section brings its tune back (round again if the section is longer than the tune)
        const src = again as unknown as { bar: number; bars: number };
        m = { notes: [], bends: [] };
        for (let rep = 0; rep * src.bars < s.bars; rep++) {
          const sh = (s.bar + rep * src.bars - src.bar) * bb;
          const room = (s.bars - rep * src.bars) * bb;
          for (const n of again.notes) if (n[0] - src.bar * bb < room - 1e-6) m.notes.push([n[0] + sh, n[1], n[2], clamp(n[3] + 6, 1, 127)]);
          for (const x of again.bends) if (x[0] - src.bar * bb < room - 1e-6) m.bends.push([x[0] + sh, x[1]]);
        }
      } else {
        m = writeMelody(song, s, song.chords, r, { tonal: song.chords.some((c) => c.kind !== "5" && c.kind !== "sus4"), lo: 60 + high + o8, hi: 79 + high + o8, density: st.density * (0.75 + 0.5 * s.energy), ornament: !!st.ornament, energy: s.energy, hook: high > 0 });
        hooks.set(base, Object.assign(m, { bar: s.bar, bars: s.bars }));
      }
      leadAll.push(...m.notes.map((n) => [n[0], n[1], n[2] - o8, n[3]] as Note));
      const acc = perLead.get(p) ?? { notes: [], bends: [] };
      acc.notes.push(...m.notes);
      acc.bends.push(...m.bends);
      perLead.set(p, acc);
    }
  }
  for (const p of st.parts) {
    if (p.role === "lead") {
      const m = perLead.get(p);
      if (!m || !m.notes.length) continue;
      const first = leadParts.indexOf(p) === 0;
      const tr = newTrack({ name: first && b.leadName ? b.leadName : p.name, program: first && b.lead !== undefined ? b.lead : p.prog, bank: first && b.lead !== undefined ? b.leadBank : p.bank, volume: p.vol ?? 0.8, pan: p.pan ?? 0, reverb: p.rev ?? 0.4, notes: m.notes, bends: m.bends.length ? m.bends : undefined });
      song.tracks.push(tr);
      continue;
    }
    const tr = writePart(song, p, secs, song.chords, r, st, { notes: leadAll });
    if (tr) song.tracks.push(tr);
  }
  // section changes: a swelling cymbal into every lift
  const sw = newTrack({ name: "Swells", program: P.revCymbal, volume: 0.5, reverb: 0.5 });
  secs.forEach((s, i) => {
    const prev = secs[i - 1];
    if (prev && s.energy - prev.energy > 0.15) sw.notes.push([s.bar * bb - 2, 2, 60, 90]);
  });
  if (sw.notes.length) song.tracks.push(sw);
  for (const t of song.tracks) t.notes.sort((x, y) => x[0] - y[0] || x[2] - y[2]);
  return song;
}

/** A song built around words: the sections follow the verses and choruses, and the tune is written to the syllables. */
function composeWithWords(song: Song, st: Style, b: Brief, r: Rnd, moodEnergy: number): Song {
  const V = b.vocal!;
  const bb = barBeats(song);
  const secs: Section[] = [];
  const plan: { sec: Section; lines: string[][]; barsPerLine: number }[] = [];
  let bar = 0;
  const add = (name: string, bars: number, energy: number): Section => {
    const s: Section = { name, bar, bars, energy: clamp(energy + moodEnergy, 0.1, 1) };
    secs.push(s);
    bar += bars;
    return s;
  };
  add("intro", bb >= 4 ? 4 : 8, 0.35);
  V.blocks.forEach((blk, i) => {
    const longest = Math.max(1, ...blk.lines.map((l) => l.length));
    const barsPerLine = longest * 0.5 > 2 * bb - 1 ? 4 : 2;
    const chorus = /chorus|hook|mukhda|refrain/i.test(blk.name);
    const sec = add(blk.name, blk.lines.length * barsPerLine, chorus ? 0.92 : 0.6 + Math.min(0.15, i * 0.05));
    plan.push({ sec, lines: blk.lines, barsPerLine });
    if (i < V.blocks.length - 1 && !chorus) add(`link${i + 1}`, 2, 0.5);
  });
  add("outro", 4, 0.35);
  song.bars = bar;
  song.sections = secs;
  song.chords = planChords(song, st, secs, r);
  const tonal = song.chords.some((c) => c.kind !== "5" && c.kind !== "sus4");
  // the tune for the words
  const [lo, hi] = V.range;
  const vocal = newTrack({ name: "Vocal", kind: "vocal", program: 0, volume: 0.9, reverb: 0.35 });
  vocal.voice = V.voice;
  vocal.lyrics = [];
  const tunes = new Map<string, Note[]>();
  for (const pl of plan) {
    const base = pl.sec.name.replace(/\s*\d+$/, "").toLowerCase();
    const sig = `${base}|${pl.lines.map((l) => l.length).join(",")}`;
    const fixed: [number, number][][] = Array.from({ length: pl.sec.bars }, () => []);
    const lineEnds = new Set<number>();
    pl.lines.forEach((syl, li) => {
      const rh = wordRhythm(syl, pl.barsPerLine * bb, r);
      rh.forEach(([t, d]) => {
        const barIn = Math.min(pl.barsPerLine - 1, Math.floor(t / bb + 1e-6));
        fixed[li * pl.barsPerLine + barIn]!.push([t - barIn * bb, d]);
      });
      // the bar that holds the line's last note
      const lastT = rh.length ? rh[rh.length - 1]![0] : 0;
      lineEnds.add(li * pl.barsPerLine + Math.min(pl.barsPerLine - 1, Math.floor(lastT / bb + 1e-6)));
    });
    let notes: Note[];
    const again = tunes.get(sig);
    if (again) {
      // the same words-shape again (a chorus coming back): the same tune
      const sh = pl.sec.bar * bb;
      notes = again.map((n) => [n[0] + sh, n[1], n[2], n[3]] as Note);
    } else {
      const chorus = pl.sec.energy > 0.85;
      const span = hi - lo;
      const m = writeMelody(song, pl.sec, song.chords, r, { tonal, lo: lo + Math.round(span * (chorus ? 0.3 : 0.12)), hi: lo + Math.round(span * (chorus ? 1 : 0.8)), density: 1, ornament: false, energy: pl.sec.energy, hook: false, fixed, lineEnds });
      notes = m.notes;
      tunes.set(sig, notes.map((n) => [n[0] - pl.sec.bar * bb, n[1], n[2], n[3]] as Note));
    }
    const flat = pl.lines.flat();
    notes.forEach((n, i) => {
      vocal.notes.push(n);
      vocal.lyrics!.push(flat[i] ?? "");
    });
  }
  // instruments: the style's parts, with the lead instrument playing only where nobody sings
  const sung = new Set(plan.map((p) => p.sec));
  const leadParts = st.parts.filter((p) => p.role === "lead");
  const firstLead = leadParts[0];
  if (firstLead) {
    const tr = newTrack({ name: b.leadName ?? firstLead.name, program: b.lead ?? firstLead.prog, bank: b.lead !== undefined ? b.leadBank : firstLead.bank, volume: (firstLead.vol ?? 0.8) * 0.9, pan: firstLead.pan ?? 0, reverb: firstLead.rev ?? 0.4 });
    const bends: [number, number][] = [];
    for (const s of secs) {
      if (sung.has(s)) continue;
      const m = writeMelody(song, s, song.chords, r, { tonal, lo: 62 + (firstLead.oct ?? 0) * 12, hi: 81 + (firstLead.oct ?? 0) * 12, density: st.density * 0.9, ornament: !!st.ornament, energy: s.energy, hook: false });
      tr.notes.push(...m.notes);
      bends.push(...m.bends);
    }
    if (bends.length) tr.bends = bends.sort((x, y) => x[0] - y[0]);
    if (tr.notes.length) song.tracks.push(tr);
  }
  song.tracks.unshift(vocal);
  for (const p of st.parts) {
    if (p.role === "lead") continue;
    // a quieter answering voice under singing; no doubled counter-melodies fighting the words
    if (p.role === "counter") continue;
    const tr = writePart(song, p, secs, song.chords, r, st, { notes: vocal.notes });
    if (tr) song.tracks.push(tr);
  }
  const sw = newTrack({ name: "Swells", program: P.revCymbal, volume: 0.5, reverb: 0.5 });
  secs.forEach((s, i) => {
    const prev = secs[i - 1];
    if (prev && s.energy - prev.energy > 0.15) sw.notes.push([s.bar * bb - 2, 2, 60, 90]);
  });
  if (sw.notes.length) song.tracks.push(sw);
  for (const t of song.tracks) if (t.kind !== "vocal") t.notes.sort((x, y) => x[0] - y[0] || x[2] - y[2]);
  return song;
}

/** What a song contains, in words (for the agent and the page). */
export function describeSong(s: Song): Record<string, unknown> {
  const bb = barBeats(s);
  return {
    id: s.id, name: s.name, bpm: s.bpm, meter: `${s.meter[0]}/${s.meter[1]}`, key: ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"][s.key], scale: s.scale, bars: s.bars, seconds: Math.round(((s.bars * bb * 60) / s.bpm) * 10) / 10,
    sections: s.sections.map((x) => `${x.name} (bars ${x.bar + 1}-${x.bar + x.bars}, energy ${Math.round(x.energy * 100) / 100})`),
    tracks: s.tracks.map((t) => ({ id: t.id, name: t.name, kind: t.kind, instrument: t.kind === "vocal" ? `sung by ${t.voice ?? "hf_alpha"}` : (t.instrument ?? t.program), notes: t.notes.length, volume: t.volume, pan: t.pan, mute: t.mute || undefined })),
  };
}
