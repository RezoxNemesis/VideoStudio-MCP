/**
 * The Studio bank: instruments VideoStudio makes itself, beside the General MIDI bank. Today's electronic sounds,
 * film sounds and Indian instruments, plus five drum kits. Nothing is downloaded and nothing is sampled from a
 * record: every note is computed (./dsp.ts) the first time an instrument is used, then kept for the session.
 *
 * Melodic instruments live in variation banks 100 (Modern), 101 (Indian) and 102 (Film); kits are drum programs 80–84.
 */
import { BasicInstrument, BasicPreset, BasicSample, BasicSoundBank, GeneratorTypes as G, SampleTypes } from "spessasynth_core";
import { SR, rng } from "./dsp";
import * as I from "./indian";
import { KITS, drumSeed } from "./kits";
import * as S from "./synths";
import type { Maker, Sampled } from "./synths";

export const BANK_MODERN = 100, BANK_INDIAN = 101, BANK_FILM = 102;
export const STUDIO_BANKS: Record<number, string> = { [BANK_MODERN]: "Studio · Modern", [BANK_INDIAN]: "Studio · Indian", [BANK_FILM]: "Studio · Film" };

export interface StudioDef {
  name: string;
  bank: number;
  program: number;
  about: string;
  /** Keys that are sampled; the notes between are played from the nearest. */
  keys: number[];
  make: Maker;
  /** Attack, decay and release in seconds; sustain 0…1 (1 = holds at full). */
  a?: number;
  d?: number;
  s?: number;
  r?: number;
  /** Low-pass in Hz (a darker version of the same sound). */
  fc?: number;
  /** dB quieter than full. */
  quiet?: number;
  /** Width 0…1: the note is played twice, a few cents apart, one copy to each side (pads, choirs, supersaws). */
  wide?: number;
}

const every = (lo: number, hi: number, step: number): number[] => {
  const o: number[] = [];
  for (let k = lo; k <= hi; k += step) o.push(k);
  return o;
};
const def = (bank: number, program: number, name: string, about: string, make: Maker, keys: number[], o: Partial<StudioDef> = {}): StudioDef => ({ bank, program, name, about, make, keys, ...o });

export const STUDIO: StudioDef[] = [
  def(BANK_MODERN, 0, "808 Bass", "The long sub bass of trap and hip hop; slides well with bends", S.sub808, every(24, 48, 6), { r: 0.18, quiet: 12 }),
  def(BANK_MODERN, 1, "Reese Bass", "Thick, slowly moving bass: drum and bass, dark pop", S.reese, every(24, 54, 6), { a: 0.005, r: 0.12, quiet: 6, wide: 0.3 }),
  def(BANK_MODERN, 2, "Acid Bass", "Squelchy resonant bass line", S.acid, every(24, 60, 6), { r: 0.08, quiet: 7 }),
  def(BANK_MODERN, 3, "FM Bass", "Punchy, bright electric bass of pop and funk", S.fmBass, every(24, 60, 6), { r: 0.1, quiet: 6 }),
  def(BANK_MODERN, 4, "Sub Bass", "Pure low sine under everything", S.subSine, every(24, 48, 12), { a: 0.006, r: 0.07, quiet: 12 }),
  def(BANK_MODERN, 5, "Supersaw Lead", "Wide festival lead", S.supersaw, every(48, 90, 6), { a: 0.004, r: 0.2, wide: 0.5 }),
  def(BANK_MODERN, 6, "Supersaw Pad", "The same wall of saws, slow and soft", S.supersaw, every(48, 90, 6), { a: 0.45, r: 0.9, fc: 5200, quiet: 1, wide: 0.9 }),
  def(BANK_MODERN, 7, "Supersaw Chords", "Chord stabs that fall back: future bass, EDM drops", S.supersaw, every(48, 90, 6), { a: 0.004, d: 0.6, s: 0.35, r: 0.25, wide: 0.8 }),
  def(BANK_MODERN, 8, "Synth Pluck", "Short bright pluck for arpeggios and hooks", S.synthPluck, every(36, 96, 6), { r: 0.3 }),
  def(BANK_MODERN, 9, "FM Electric Piano", "Glassy electric piano of ballads and city pop", S.fmPiano, every(36, 96, 5), { r: 0.35, quiet: 3, wide: 0.25 }),
  def(BANK_MODERN, 10, "FM Bell", "Long ringing bell", S.fmBell, every(48, 96, 6), { r: 1.2, quiet: 4 }),
  def(BANK_MODERN, 11, "Lo-fi Keys", "Soft wavering piano on old tape", S.lofiKeys, every(36, 90, 5), { r: 0.4, quiet: 4, wide: 0.25 }),
  def(BANK_MODERN, 12, "Chip Square", "Game-console square wave", S.chip("square"), every(36, 96, 12), { r: 0.03, quiet: 9 }),
  def(BANK_MODERN, 13, "Chip Pulse", "Thin game-console pulse", S.chip("pulse"), every(36, 96, 12), { r: 0.03, quiet: 9 }),
  def(BANK_MODERN, 14, "Chip Triangle", "Stepped triangle: game-console bass and flutes", S.chip("triangle"), every(24, 84, 12), { r: 0.03, quiet: 10 }),
  def(BANK_MODERN, 15, "Choir Aah", "Small choir on an open vowel", S.choir("aah"), every(43, 81, 4), { a: 0.12, r: 0.6, quiet: 5, wide: 0.7 }),
  def(BANK_MODERN, 16, "Choir Ooh", "Small choir, closed and soft", S.choir("ooh"), every(43, 81, 4), { a: 0.15, r: 0.7, quiet: 5, wide: 0.7 }),
  def(BANK_MODERN, 17, "Analog Pad", "Warm, slowly breathing pad", S.analogPad, every(36, 84, 6), { a: 0.5, r: 1.2, quiet: 5, wide: 0.8 }),
  def(BANK_MODERN, 18, "Glass Pad", "Clear shimmering pad", S.glassPad, every(48, 90, 6), { a: 0.6, r: 1.6, quiet: 6, wide: 0.9 }),

  def(BANK_INDIAN, 0, "Sitar", "Plucked, buzzing lead of raga and film music; bends beautifully", I.sitar, every(38, 86, 4), { r: 0.5 }),
  def(BANK_INDIAN, 1, "Tanpura", "The drone behind a raga: play the tonic and the fifth, long", I.tanpura, every(36, 64, 4), { r: 2.5, quiet: 6, wide: 0.4 }),
  def(BANK_INDIAN, 2, "Bansuri", "Bamboo flute, breathy and warm", I.bansuri, every(55, 91, 4), { a: 0.02, r: 0.15, quiet: 5 }),
  def(BANK_INDIAN, 3, "Harmonium", "Reed organ of bhajans and qawwali", I.harmonium, every(36, 84, 5), { a: 0.02, r: 0.12, quiet: 3, wide: 0.3 }),
  def(BANK_INDIAN, 4, "Santoor", "Hammered strings, bright and rippling", I.santoor, every(48, 96, 4), { r: 0.8 }),
  def(BANK_INDIAN, 5, "Shehnai", "Festive double-reed pipe", I.shehnai, every(55, 87, 4), { a: 0.02, r: 0.1, quiet: 5 }),
  def(BANK_INDIAN, 6, "Sarangi", "Bowed, voice-like strings", I.sarangi, every(43, 83, 4), { a: 0.05, r: 0.2, quiet: 5 }),

  def(BANK_FILM, 0, "Braam", "The tearing low brass blast of trailers", S.braam, every(24, 48, 6), { r: 0.6, quiet: 3, wide: 0.5 }),
  def(BANK_FILM, 1, "Sub Boom", "Falling thud with a long tail", S.subBoom, [24, 30, 36], { r: 1, quiet: 4 }),
  def(BANK_FILM, 2, "Riser", "Four seconds of rising tension that stop dead: hold the note", S.riser, [48, 60, 72], { r: 0.05 }),
  def(BANK_FILM, 3, "Tension Strings", "Trembling cluster a semitone apart", S.tension, every(36, 84, 6), { a: 0.25, r: 0.5, wide: 0.7 }),
  def(BANK_FILM, 4, "Dark Drone", "Low moving drone with wind in it", S.darkDrone, every(24, 54, 6), { a: 1, r: 2, wide: 0.8 }),
  def(BANK_FILM, 5, "Impact", "Hit: noise, thump and metal", S.impact, [36, 48, 60], { r: 1.5, quiet: 3 }),
  def(BANK_FILM, 6, "Reverse Swell", "Grows from nothing and is cut at its peak", S.swell, every(36, 72, 12), { r: 0.05 }),
];

/** The Studio instruments and kits as the instrument list shows them. */
export function studioCatalogue(): { name: string; program: number; bank: number; drums: boolean; family: string; about: string }[] {
  return [
    ...STUDIO.map((d) => ({ name: d.name, program: d.program, bank: d.bank, drums: false, family: STUDIO_BANKS[d.bank]!, about: d.about })),
    ...KITS.map((k) => ({ name: k.name, program: k.program, bank: 0, drums: true, family: "Drum kits", about: k.about })),
  ];
}
export const isStudio = (bank: number | undefined, program: number, drums: boolean): boolean => (drums ? KITS.some((k) => k.program === program) : STUDIO.some((d) => d.bank === (bank ?? 0) && d.program === program));

/** Seconds → SoundFont time units; level 0…1 → attenuation in tenths of a dB; Hz → SoundFont pitch units. */
const tc = (sec: number): number => Math.round(1200 * Math.log2(Math.max(0.001, sec)));
const cb = (level: number): number => Math.round(Math.min(1000, -200 * Math.log10(Math.max(1e-5, level))));
const fcUnits = (f: number): number => Math.round(1200 * Math.log2(f / 8.176));

const made = new Map<string, Sampled>();
function sampled(id: string, make: () => Sampled): Sampled {
  let s = made.get(id);
  if (!s) {
    s = make();
    // nothing leaves here above full scale (a loop's blend can add a little)
    let pk = 0;
    for (let i = 0; i < s.pcm.length; i++) pk = Math.max(pk, Math.abs(s.pcm[i]!));
    if (pk > 0.98) for (let i = 0; i < s.pcm.length; i++) s.pcm[i] = (s.pcm[i]! * 0.98) / pk;
    made.set(id, s);
  }
  return s;
}

export interface StudioNeed {
  bank?: number;
  program: number;
  drums: boolean;
}

/**
 * A sound bank holding just the Studio instruments and kits asked for (null when none of them is a Studio sound).
 * `pause` is awaited between notes so the app stays responsive while a new instrument is being made.
 */
export async function buildStudioBank(needs: StudioNeed[], pause?: () => Promise<void>): Promise<BasicSoundBank | null> {
  const defs = STUDIO.filter((d) => needs.some((n) => !n.drums && (n.bank ?? 0) === d.bank && n.program === d.program));
  const kits = KITS.filter((k) => needs.some((n) => n.drums && n.program === k.program));
  if (!defs.length && !kits.length) return null;
  const bank = new BasicSoundBank();
  bank.soundBankInfo.name = "VideoStudio Studio bank";
  for (const d of defs) {
    const inst = new BasicInstrument();
    inst.name = d.name;
    const g = inst.globalZone;
    g.setGenerator(G.attackVolEnv, tc(d.a ?? 0.002));
    if (d.d !== undefined) g.setGenerator(G.decayVolEnv, tc(d.d));
    if (d.s !== undefined) g.setGenerator(G.sustainVolEnv, cb(d.s));
    g.setGenerator(G.releaseVolEnv, tc(d.r ?? 0.25));
    if (d.fc) g.setGenerator(G.initialFilterFc, fcUnits(d.fc));
    // (the synthesizer follows the SoundFont custom of counting this generator at 0.4 of its written value)
    if (d.quiet) g.setGenerator(G.initialAttenuation, Math.round(d.quiet * 25));
    for (let k = 0; k < d.keys.length; k++) {
      const key = d.keys[k]!;
      const s = sampled(`${d.make === S.supersaw ? "supersaw" : `${d.bank}:${d.program}`}:${key}`, () => d.make(key, rng(d.bank * 100000 + d.program * 1000 + key)));
      const smp = new BasicSample(`${d.name} ${key}`, SR, key, 0, SampleTypes.monoSample, s.loop?.[0] ?? 0, s.loop?.[1] ?? s.pcm.length - 1);
      smp.setAudioData(s.pcm, SR);
      bank.addSamples(smp);
      const lo = k === 0 ? 0 : Math.floor((d.keys[k - 1]! + key) / 2) + 1, hi = k === d.keys.length - 1 ? 127 : Math.floor((key + d.keys[k + 1]!) / 2);
      // wide sounds: the same note twice, a few cents apart and a moment apart, one to each side
      for (const side of d.wide ? [-1, 1] : [0]) {
        const z = inst.createZone(smp);
        z.keyRange = { min: lo, max: hi };
        z.setGenerator(G.sampleModes, s.loop ? 1 : 0);
        if (side) {
          z.setGenerator(G.pan, Math.round(side * 500 * d.wide!));
          z.setGenerator(G.fineTune, side * 4);
          z.setGenerator(G.initialAttenuation, 75);
          if (side > 0 && s.loop) z.setGenerator(G.startAddrsOffset, Math.min(Math.round(0.011 * SR), s.loop[0] - 1));
        }
      }
      if (pause) await pause();
    }
    bank.addInstruments(inst);
    const p = new BasicPreset(bank);
    p.name = d.name;
    p.program = d.program;
    p.bankMSB = d.bank;
    p.createZone(inst);
    bank.addPresets(p);
  }
  for (const kit of kits) {
    const inst = new BasicInstrument();
    inst.name = kit.name;
    for (const [n, snd] of Object.entries(kit.sounds)) {
      const note = Number(n);
      const s = sampled(`kit${kit.program}:${note}`, () => ({ pcm: snd.make(drumSeed(kit.program, note)) }));
      const smp = new BasicSample(`${kit.name} ${note}`, SR, note, 0, SampleTypes.monoSample, 0, s.pcm.length - 1);
      smp.setAudioData(s.pcm, SR);
      bank.addSamples(smp);
      const z = inst.createZone(smp);
      z.keyRange = { min: note, max: note };
      z.setGenerator(G.sampleModes, 0);
      // a drum rings out whatever the length of its note
      z.setGenerator(G.releaseVolEnv, tc(Math.max(1.5, (s.pcm.length / SR) * 4)));
      if (snd.group) z.setGenerator(G.exclusiveClass, snd.group);
      if (snd.pan) z.setGenerator(G.pan, Math.round(snd.pan * 500));
      if (pause) await pause();
    }
    bank.addInstruments(inst);
    const p = new BasicPreset(bank);
    p.name = kit.name;
    p.program = kit.program;
    p.isGMGSDrum = true;
    p.createZone(inst);
    bank.addPresets(p);
  }
  return bank;
}
