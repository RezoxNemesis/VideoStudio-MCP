/**
 * The instrument engine: plays songs through a SoundFont synthesizer (SpessaSynth) loaded with the bundled
 * GeneralUser GS bank: 261 instruments and 13 drum kits. Rendering is offline and many times faster than real time,
 * so a whole song bounces in seconds and previews are simply short renders.
 */
import { SoundBankLoader, SpessaSynthProcessor } from "spessasynth_core";
import { loadAsset } from "../stage/pack";
import { buildStudioBank, studioCatalogue, type StudioNeed } from "./studio/bank";
import { barBeats, beatSec, songBeats, type Song, type Track } from "./model";

export interface Instrument {
  name: string;
  program: number;
  bank: number;
  drums: boolean;
  family: string;
  /** What it is for (Studio instruments). */
  about?: string;
}

const FAMILIES = ["Piano", "Chromatic percussion", "Organ", "Guitar", "Bass", "Strings", "Ensemble", "Brass", "Reed", "Pipe", "Synth lead", "Synth pad", "Synth effects", "World", "Percussive", "Sound effects"];
export const SAMPLE_RATE = 48000;
const BANK_FILE = "music/GeneralUser-GS.sf2";

let bankBytes: Promise<ArrayBuffer> | null = null;
let instruments: Instrument[] | null = null;
let problem = "";

/** Why the instruments are not available (the sound bank is missing), or "". */
export const musicProblem = (): string => problem;

/** Give the app a moment between two notes of an instrument being made. */
const breathe = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

async function makeSynth(needs: StudioNeed[] = []): Promise<SpessaSynthProcessor> {
  bankBytes ??= loadAsset(BANK_FILE);
  let bytes: ArrayBuffer;
  try {
    bytes = await bankBytes;
  } catch (e) {
    bankBytes = null;
    problem = `The instrument bank (${BANK_FILE}) is not installed: ${(e as Error).message}`;
    throw new Error(problem);
  }
  // the parser keeps views into the buffer it is given: every synth gets its own copy
  const bank = SoundBankLoader.fromArrayBuffer(bytes.slice(0));
  const synth = new SpessaSynthProcessor(SAMPLE_RATE, { eventsEnabled: false } as never);
  synth.soundBankManager.addSoundBank(bank, "main");
  // the instruments VideoStudio makes itself (808s, supersaws, sitar, tabla …): only the ones this song plays
  const studio = await buildStudioBank(needs, breathe);
  if (studio) synth.soundBankManager.addSoundBank(studio, "studio");
  await synth.ready;
  try {
    synth.setSystemParameter("autoAllocateVoices" as never, true as never);
  } catch {
    /* older builds cap voices instead */
  }
  if (!instruments) {
    const own = studioCatalogue();
    instruments = [
      ...synth.soundBankManager.presetList
        .map((p) => ({ name: p.name.trim(), program: p.program, bank: p.isDrum ? 0 : p.bankMSB, drums: !!p.isDrum, family: p.isDrum ? "Drum kits" : (FAMILIES[p.program >> 3] ?? "Other") }))
        .filter((p) => !own.some((o) => o.drums === p.drums && o.program === p.program && o.bank === p.bank)),
      ...own,
    ].sort((a, b) => Number(a.drums) - Number(b.drums) || a.bank - b.bank || a.program - b.program);
  }
  problem = "";
  return synth;
}

/** Every instrument and drum kit in the bank. */
export async function listInstruments(): Promise<Instrument[]> {
  if (!instruments) await makeSynth();
  return instruments!;
}

/** Find an instrument by name ("sitar", "nylon guitar", "strings"), or by program number. */
export async function findInstrument(q: string | number, drums = false): Promise<Instrument> {
  const all = await listInstruments();
  if (typeof q === "number") {
    const hit = all.find((i) => i.drums === drums && i.program === q && i.bank === 0) ?? all.find((i) => i.drums === drums && i.program === q);
    if (!hit) throw new Error(`No ${drums ? "drum kit" : "instrument"} with program ${q}.`);
    return hit;
  }
  const words = q.toLowerCase().split(/[\s_-]+/).filter(Boolean);
  let best: Instrument | null = null, score = 0;
  for (const i of all) {
    if (drums !== i.drums && !/kit|drum/.test(q.toLowerCase())) continue;
    const n = i.name.toLowerCase();
    let s = 0;
    for (const w of words) if (n.includes(w)) s += w.length + (n.startsWith(w) ? 2 : 0);
    if (n === q.toLowerCase()) s += 50;
    // "tabla", "dholak", "dhol" are strokes of a kit, not instrument names: they are found by what the kit is for
    if (i.about && s === 0) for (const w of words) if (w.length > 2 && i.about.toLowerCase().includes(w)) s += w.length * 0.6;
    if (s > 0 && i.bank === 0) s += 1;
    s -= n.length * 0.01;
    if (s > score) {
      score = s;
      best = i;
    }
  }
  if (!best || score < 2) throw new Error(`No instrument matches "${q}". music.instruments lists all ${all.length}; try a plainer word (piano, sitar, flute, strings, 808, supersaw, tabla).`);
  return best;
}

interface Ev {
  t: number;
  /** 0 off, 1 on, 2 bend, 3 expression */
  k: number;
  ch: number;
  a: number;
  b: number;
}

function hash(n: number): number {
  let x = (n * 2654435761) >>> 0;
  x ^= x >>> 15;
  x = Math.imul(x, 2246822519) >>> 0;
  x ^= x >>> 13;
  return (x >>> 0) / 4294967296;
}

export interface RenderOptions {
  /** Only these tracks (ids); all audible tracks otherwise. */
  only?: string[];
  /** Beats to render: from (default 0) and to (default the end). */
  from?: number;
  to?: number;
  /** Seconds of ring-out after the last note. */
  tail?: number;
  onProgress?: (done: number) => void;
  /** Checked between slices: return true to stop. */
  cancelled?: () => boolean;
}
export interface Rendered {
  left: Float32Array;
  right: Float32Array;
  sampleRate: number;
  seconds: number;
  peak: number;
}

/** Bounce a song (or part of it, or some of its tracks) to stereo audio. */
export async function renderSong(song: Song, o: RenderOptions = {}): Promise<Rendered> {
  const spb = beatSec(song);
  const from = o.from ?? 0, to = o.to ?? songBeats(song);
  const soloed = song.tracks.some((t) => t.solo);
  const tracks = song.tracks.filter((t) => t.kind !== "vocal" && !t.mute && (!soloed || t.solo) && (!o.only || o.only.includes(t.id)));
  const synth = await makeSynth(tracks.map((t) => ({ bank: t.bank, program: t.program, drums: t.kind === "drums" })));
  while (synth.midiChannels.length < Math.max(16, tracks.length + 1)) synth.createMIDIChannel();
  const evs: Ev[] = [];
  const swing = song.swing ?? 0, human = song.humanize ?? 0;
  const sw = (b: number): number => {
    // push the off-beat eighths back
    if (!swing) return b;
    const f = b % 1;
    return f >= 0.5 ? Math.floor(b) + 0.5 + (f - 0.5) * (1 - swing * 2) + swing * 0.5 : Math.floor(b) + f * (1 + swing);
  };
  const chOf = (i: number): number => (i >= 9 ? i + 1 : i); // keep the GM drum channel free
  tracks.forEach((tr: Track, i) => {
    const ch = tr.kind === "drums" ? (tracks.findIndex((x) => x.kind === "drums") === i ? 9 : chOf(i)) : chOf(i);
    const mc = synth.midiChannels[ch]!;
    try {
      (mc as unknown as { setDrums(d: boolean): void }).setDrums(tr.kind === "drums");
    } catch {
      /* channel 9 is the drum channel already */
    }
    if (tr.kind !== "drums") synth.controllerChange(ch, 0 as never, tr.bank ?? 0);
    synth.programChange(ch, tr.program);
    synth.controllerChange(ch, 7 as never, Math.round(Math.min(1, Math.max(0, tr.volume)) * 127));
    synth.controllerChange(ch, 10 as never, Math.round(64 + Math.max(-1, Math.min(1, tr.pan)) * 63));
    synth.controllerChange(ch, 91 as never, Math.round(Math.max(0, Math.min(1, tr.reverb * (0.5 + (song.room ?? 0.5)))) * 127));
    synth.controllerChange(ch, 93 as never, Math.round(Math.max(0, Math.min(1, tr.chorus ?? 0)) * 127));
    // bend range ±12 semitones (RPN 0)
    synth.controllerChange(ch, 101 as never, 0);
    synth.controllerChange(ch, 100 as never, 0);
    synth.controllerChange(ch, 6 as never, 12);
    synth.controllerChange(ch, 38 as never, 0);
    tr.notes.forEach((n, k) => {
      if (n[0] >= to || n[0] + n[1] <= from) return;
      const j = human ? (hash(k * 31 + i * 977 + Math.round(n[0] * 96)) - 0.5) * 0.028 * human : 0;
      const t0 = (sw(Math.max(from, n[0])) - from) * spb + j;
      const t1 = (sw(Math.min(to, n[0] + n[1])) - from) * spb - 0.004;
      const v = Math.round(Math.max(1, Math.min(127, n[3] * (1 + (human ? (hash(k * 17 + i * 131) - 0.5) * 0.16 * human : 0)))));
      evs.push({ t: Math.max(0, t0), k: 1, ch, a: n[2], b: v });
      evs.push({ t: Math.max(t0 + 0.02, t1), k: 0, ch, a: n[2], b: 0 });
    });
    const curve = (pts: [number, number][] | undefined, kind: number, scale: (v: number) => number) => {
      if (!pts || !pts.length) return;
      const s = [...pts].sort((x, y) => x[0] - y[0]);
      for (let q = 0; q < s.length; q++) {
        const A = s[q]!, B = s[q + 1];
        if (!B) {
          evs.push({ t: Math.max(0, (A[0] - from) * spb), k: kind, ch, a: scale(A[1]), b: 0 });
          break;
        }
        const steps = Math.max(1, Math.ceil((B[0] - A[0]) * spb / 0.012));
        for (let w = 0; w < steps; w++) {
          const u = w / steps, bt = A[0] + (B[0] - A[0]) * u;
          if (bt < from || bt > to) continue;
          evs.push({ t: (bt - from) * spb, k: kind, ch, a: scale(A[1] + (B[1] - A[1]) * u), b: 0 });
        }
      }
    };
    curve(tr.bends, 2, (v) => Math.round(8192 + Math.max(-1, Math.min(1, v / 12)) * 8191));
    curve(tr.swell, 3, (v) => Math.round(Math.max(0, Math.min(1, v)) * 127));
  });
  evs.sort((x, y) => x.t - y.t || x.k - y.k);
  const seconds = (to - from) * spb + (o.tail ?? 2.5);
  const N = Math.ceil(seconds * SAMPLE_RATE);
  const L = new Float32Array(N), R = new Float32Array(N);
  let done = 0, e = 0, lastYield = performance.now();
  while (done < N) {
    const t = done / SAMPLE_RATE;
    while (e < evs.length && evs[e]!.t <= t) {
      const v = evs[e++]!;
      if (v.k === 1) synth.noteOn(v.ch, v.a, v.b);
      else if (v.k === 0) synth.noteOff(v.ch, v.a);
      else if (v.k === 2) synth.pitchWheel(v.ch, v.a);
      else synth.controllerChange(v.ch, 11 as never, v.a);
    }
    const n = Math.min(128, N - done);
    synth.process(L, R, done, n);
    done += n;
    if (performance.now() - lastYield > 40) {
      o.onProgress?.(done / N);
      await new Promise((r) => setTimeout(r, 0));
      lastYield = performance.now();
      if (o.cancelled?.()) break;
    }
  }
  // master: bring the song to a steady loudness, then a soft knee so peaks never clip
  let sum = 0, cnt = 0, pk = 0;
  for (let i = 0; i < N; i += 3) {
    const m = Math.max(Math.abs(L[i]!), Math.abs(R[i]!));
    if (m > pk) pk = m;
    if (m > 0.004) {
      sum += L[i]! * L[i]! + R[i]! * R[i]!;
      cnt += 2;
    }
  }
  const rms = cnt ? Math.sqrt(sum / cnt) : 0;
  // about -17 dB RMS for the sounding parts, without pushing peaks more than 5 dB into the knee
  let g = rms > 1e-5 ? 0.14 / rms : 1;
  if (pk * g > 1.55) g = 1.55 / pk;
  g = Math.min(g, 8) * (song.gain ?? 1);
  let peak = 0;
  for (let i = 0; i < N; i++) {
    let l = L[i]! * g, r = R[i]! * g;
    const m = Math.max(Math.abs(l), Math.abs(r));
    if (m > 0.7) {
      const k = (0.7 + 0.28 * Math.tanh((m - 0.7) / 0.28)) / m;
      l *= k;
      r *= k;
    }
    L[i] = l;
    R[i] = r;
    const a = Math.max(Math.abs(l), Math.abs(r));
    if (a > peak) peak = a;
  }
  return { left: L, right: R, sampleRate: SAMPLE_RATE, seconds, peak };
}

/** 16-bit stereo WAV file bytes. */
export function wavBytes(a: { left: Float32Array; right: Float32Array; sampleRate: number }): Uint8Array {
  const n = a.left.length;
  const out = new Uint8Array(44 + n * 4);
  const dv = new DataView(out.buffer);
  const wr = (o: number, s: string) => { for (let i = 0; i < s.length; i++) out[o + i] = s.charCodeAt(i); };
  wr(0, "RIFF");
  dv.setUint32(4, 36 + n * 4, true);
  wr(8, "WAVEfmt ");
  dv.setUint32(16, 16, true);
  dv.setUint16(20, 1, true);
  dv.setUint16(22, 2, true);
  dv.setUint32(24, a.sampleRate, true);
  dv.setUint32(28, a.sampleRate * 4, true);
  dv.setUint16(32, 4, true);
  dv.setUint16(34, 16, true);
  wr(36, "data");
  dv.setUint32(40, n * 4, true);
  for (let i = 0, o = 44; i < n; i++, o += 4) {
    dv.setInt16(o, Math.max(-32768, Math.min(32767, Math.round(a.left[i]! * 32767))), true);
    dv.setInt16(o + 2, Math.max(-32768, Math.min(32767, Math.round(a.right[i]! * 32767))), true);
  }
  return out;
}

/** One bar of beats in seconds, for callers that place music on a timeline. */
export const barSeconds = (s: Song): number => barBeats(s) * beatSec(s);
