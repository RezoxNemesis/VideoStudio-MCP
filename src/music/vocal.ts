/**
 * Songs with singing: the instruments are rendered by the synthesizer, every vocal track is sung by a voice
 * (voice/engine/sing.ts), given a little room, and the two are mixed.
 */
import { beatSec, songBeats, type Song, type SongMix, type Track } from "./model";
import { renderSong, type RenderOptions, type Rendered } from "./synth";
import { fitRange, type SingOptions, type SungNote } from "../voice/engine/sing";

/** What the music section needs from the voice engine. */
export interface VocalEngine {
  /** Split lines of lyrics into the syllables (phonemes) a voice will sing. */
  syllables(lines: string[], voice: string): Promise<string[][]>;
  /** Sing notes; returns 24 kHz mono starting at second 0. */
  sing(notes: SungNote[], voice: string, o?: SingOptions): Promise<Float32Array>;
}

/** The notes of a vocal track as the voice engine wants them (seconds, syllables), fitted to the voice's range. */
export function sungNotes(song: Song, t: Track): { notes: SungNote[]; shift: number } {
  const spb = beatSec(song);
  const notes: SungNote[] = t.notes.map((n, i) => ({ start: n[0] * spb, dur: Math.max(0.08, n[1] * spb), midi: n[2], syl: t.lyrics?.[i] ?? "", vel: n[3] / 127 }));
  const shift = fitRange(notes, t.voice ?? "hf_alpha");
  for (const n of notes) n.midi += shift;
  return { notes, shift };
}

/** A small plate-like reverb (comb and all-pass filters), mono in, stereo out, wet only. */
function reverb(x: Float32Array, sr: number, size = 0.8, damp = 0.35): [Float32Array, Float32Array] {
  const k = sr / 44100;
  const combs = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617].map((d) => Math.round(d * k));
  const alls = [556, 441, 341, 225].map((d) => Math.round(d * k));
  const out: [Float32Array, Float32Array] = [new Float32Array(x.length), new Float32Array(x.length)];
  for (let ch = 0; ch < 2; ch++) {
    const spread = ch ? Math.round(23 * k) : 0;
    const acc = new Float32Array(x.length);
    for (const d0 of combs) {
      const d = d0 + spread;
      const buf = new Float32Array(d);
      let p = 0, lp = 0;
      const fb = 0.7 + size * 0.24;
      for (let i = 0; i < x.length; i++) {
        const y = buf[p]!;
        lp = y * (1 - damp) + lp * damp;
        buf[p] = x[i]! * 0.12 + lp * fb;
        acc[i] = acc[i]! + y;
        if (++p >= d) p = 0;
      }
    }
    let cur = acc;
    for (const d0 of alls) {
      const d = d0 + spread;
      const buf = new Float32Array(d);
      const nxt = new Float32Array(x.length);
      let p = 0;
      for (let i = 0; i < x.length; i++) {
        const b = buf[p]!;
        const y = -cur[i]! + b;
        buf[p] = cur[i]! + b * 0.5;
        nxt[i] = y;
        if (++p >= d) p = 0;
      }
      cur = nxt;
    }
    out[ch] = cur;
  }
  return out;
}

/** One second-order filter section (the standard audio "biquad"): high-pass, bell (peak / dip) or high shelf. */
class Biquad {
  private b0 = 1; private b1 = 0; private b2 = 0; private a1 = 0; private a2 = 0;
  private x1 = 0; private x2 = 0; private y1 = 0; private y2 = 0;
  constructor(kind: "hp" | "bell" | "shelf" | "bp", f: number, sr: number, q = 0.707, gainDb = 0) {
    const w = (2 * Math.PI * Math.min(f, sr * 0.45)) / sr, cs = Math.cos(w), sn = Math.sin(w), A = Math.pow(10, gainDb / 40), al = sn / (2 * q);
    let b0: number, b1: number, b2: number, a0: number, a1: number, a2: number;
    if (kind === "hp") {
      b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = (1 + cs) / 2; a0 = 1 + al; a1 = -2 * cs; a2 = 1 - al;
    } else if (kind === "bp") {
      b0 = al; b1 = 0; b2 = -al; a0 = 1 + al; a1 = -2 * cs; a2 = 1 - al;
    } else if (kind === "bell") {
      b0 = 1 + al * A; b1 = -2 * cs; b2 = 1 - al * A; a0 = 1 + al / A; a1 = -2 * cs; a2 = 1 - al / A;
    } else {
      const r = 2 * Math.sqrt(A) * al;
      b0 = A * (A + 1 + (A - 1) * cs + r); b1 = -2 * A * (A - 1 + (A + 1) * cs); b2 = A * (A + 1 + (A - 1) * cs - r);
      a0 = A + 1 - (A - 1) * cs + r; a1 = 2 * (A - 1 - (A + 1) * cs); a2 = A + 1 - (A - 1) * cs - r;
    }
    this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = a1 / a0; this.a2 = a2 / a0;
  }
  run(x: number): number {
    const y = this.b0 * x + this.b1 * this.x1 + this.b2 * this.x2 - this.a1 * this.y1 - this.a2 * this.y2;
    this.x2 = this.x1; this.x1 = x; this.y2 = this.y1; this.y1 = y;
    return y;
  }
  over(x: Float32Array): Float32Array {
    for (let i = 0; i < x.length; i++) x[i] = this.run(x[i]!);
    return x;
  }
}

/** Root-mean-square level of the parts of `x` where `gate` is open (or of everything above a whisper). */
function rmsWhere(x: Float32Array, gate?: Float32Array): number {
  let s = 0, n = 0;
  for (let i = 0; i < x.length; i += 2) if (gate ? gate[i]! > 0.1 : Math.abs(x[i]!) > 0.003) {
    s += x[i]! * x[i]!;
    n++;
  }
  return n ? Math.sqrt(s / n) : 0;
}

/**
 * The voice channel of a mixing desk, for a sung line (mono, in place): rumble out, a little mud out, presence and
 * air in, then a compressor so quiet syllables come up to the loud ones. `clarity` 0…1 sets how much of each.
 */
export function vocalChain(x: Float32Array, sr: number, clarity: number): Float32Array {
  new Biquad("hp", 95, sr, 0.707).over(x);
  if (clarity <= 0) return x;
  new Biquad("bell", 300, sr, 1, -2.5 * clarity).over(x);
  new Biquad("bell", 3200, sr, 0.9, 4.5 * clarity).over(x);
  new Biquad("shelf", 8500, sr, 0.707, 3 * clarity).over(x);
  // compressor: level measured over ~10 ms, let go over ~140 ms
  const ref = rmsWhere(x);
  if (ref < 1e-6) return x;
  const thr = ref * 0.7, ratio = 1 + 2.5 * clarity;
  const up = Math.exp(-1 / (0.008 * sr)), down = Math.exp(-1 / (0.14 * sr));
  let env = 0;
  for (let i = 0; i < x.length; i++) {
    const a = Math.abs(x[i]!);
    env = a > env ? a + (env - a) * up : a + (env - a) * down;
    if (env > thr) x[i] = x[i]! * Math.pow(env / thr, 1 / ratio - 1);
  }
  const after = rmsWhere(x);
  if (after > 1e-6) for (let i = 0; i < x.length; i++) x[i] = (x[i]! * ref) / after;
  return x;
}

export interface SongLevels {
  /** Loudness of the voice and of the instruments while the voice sings, in dB (0 = full scale). */
  vocals: number;
  music: number;
  /** vocals - music: above 0 the voice is in front. Around +2…+5 words are easy to follow; below 0 they sink. */
  balance: number;
}
export interface VocalRender extends Rendered {
  vocalShift?: number;
  /** How the voice sits against the instruments in this bounce. */
  levels?: SongLevels;
  /** The voice (with its room) and the instruments on their own, at the levels they have in the mix. */
  stems?: { vocals: Rendered; music: Rendered };
}
const dB = (v: number): number => Math.round(200 * Math.log10(Math.max(v, 1e-6))) / 10;

/** Bounce a song with its vocals. Without an engine (or without vocal tracks) this is the instrumental render. */
export async function renderWithVocals(song: Song, engine: VocalEngine | null, o: RenderOptions & { sing?: SingOptions; mix?: SongMix; stems?: boolean } = {}): Promise<VocalRender> {
  const soloed = song.tracks.some((t) => t.solo);
  const vocals = song.tracks.filter((t) => t.kind === "vocal" && t.notes.length && !t.mute && (!soloed || t.solo) && (!o.only || o.only.includes(t.id)));
  const hasBand = song.tracks.some((t) => t.kind !== "vocal" && t.notes.length && !t.mute && (!soloed || t.solo) && (!o.only || o.only.includes(t.id)));
  const band = hasBand ? await renderSong(song, { ...o, onProgress: (d) => o.onProgress?.(d * (vocals.length ? 0.4 : 1)) }) : null;
  if (!engine || !vocals.length) {
    if (!band) throw new Error("There is nothing to render: no notes on any audible track.");
    return band;
  }
  const mix: SongMix = { ...song.mix, ...o.mix };
  const clarity = Math.max(0, Math.min(1, mix.clarity ?? 0.6)), duck = Math.max(0, mix.duck ?? 3), lead = mix.lead ?? 3;
  const vAmt = Math.max(0, mix.vocals ?? 1), mAmt = Math.max(0, mix.music ?? 1);
  const sr = band?.sampleRate ?? 48000;
  const spb = beatSec(song);
  const seconds = band?.seconds ?? songBeats(song) * spb + 2.5;
  const N = band ? band.left.length : Math.ceil(seconds * sr);
  const L = band ? band.left : new Float32Array(N), R = band ? band.right : new Float32Array(N);
  // the voices: sung, put through the voice channel, panned; `dry` is their sum, for the balance and the ducking
  const VL = new Float32Array(N), VR = new Float32Array(N), dry = new Float32Array(N);
  let shiftOut: number | undefined;
  for (let v = 0; v < vocals.length; v++) {
    const t = vocals[v]!;
    const { notes, shift } = sungNotes(song, t);
    shiftOut = shift;
    const from = (o.from ?? 0) * spb;
    const pcm = await engine.sing(notes, t.voice ?? "hf_alpha", { crisp: clarity, ...o.sing });
    o.onProgress?.(0.4 + (0.6 * (v + 1)) / vocals.length);
    // 24 kHz → the song's rate, placed on the song's clock
    const up = new Float32Array(N);
    for (let i = 0; i < N; i++) {
      const x = (i / sr + from) * 24000;
      const j = Math.floor(x);
      if (j < 0 || j + 1 >= pcm.length) continue;
      const a = x - j;
      up[i] = pcm[j]! * (1 - a) + pcm[j + 1]! * a;
    }
    vocalChain(up, sr, clarity);
    // every voice to the same steady loudness, then its own fader
    const rms = rmsWhere(up);
    const g = (rms > 1e-5 ? 0.2 / rms : 1) * t.volume;
    for (let i = 0; i < N; i++) up[i] = up[i]! * g;
    // its room: a short gap before the reverb and no low end in it, so the words stay in front of their own echo
    const send = new Float32Array(N), pre = Math.round(0.028 * sr);
    for (let i = pre; i < N; i++) send[i] = up[i - pre]!;
    new Biquad("hp", 260, sr, 0.707).over(send);
    const [wl, wr] = reverb(send, sr, 0.6 + (song.room ?? 0.5) * 0.3);
    const wet = t.reverb * (0.55 - 0.2 * clarity);
    const pl = Math.cos(((t.pan + 1) / 2) * (Math.PI / 2)) * Math.SQRT2, pr = Math.sin(((t.pan + 1) / 2) * (Math.PI / 2)) * Math.SQRT2;
    for (let i = 0; i < N; i++) {
      dry[i] = dry[i]! + up[i]!;
      VL[i] = VL[i]! + up[i]! * pl + wl[i]! * wet;
      VR[i] = VR[i]! + up[i]! * pr + wr[i]! * wet;
    }
  }
  // where the voice is singing: 0…1, rising in ~40 ms and letting go over ~300 ms
  const gate = new Float32Array(N);
  {
    const ref = rmsWhere(dry) * 1.2 || 1, up = Math.exp(-1 / (0.04 * sr)), down = Math.exp(-1 / (0.3 * sr));
    let env = 0;
    for (let i = 0; i < N; i++) {
      const a = Math.min(1, Math.abs(dry[i]!) / ref);
      env = a > env ? a + (env - a) * up : a + (env - a) * down;
      gate[i] = Math.min(1, env * 1.6);
    }
  }
  let vGain = vAmt, levels: SongLevels | undefined;
  if (band) {
    // while the voice sings the band steps back a little, most of all where the words live (around 3 kHz)
    const midL = new Biquad("bp", 2800, sr, 0.7), midR = new Biquad("bp", 2800, sr, 0.7);
    const pocket = 1 - Math.pow(10, (-3.5 * clarity) / 20);
    for (let i = 0; i < N; i++) {
      const k = gate[i]!, g = Math.pow(10, (-duck * k) / 20) * 0.85;
      L[i] = (L[i]! - midL.run(L[i]!) * pocket * k) * g;
      R[i] = (R[i]! - midR.run(R[i]!) * pocket * k) * g;
    }
    // balance: the voice `lead` dB in front of the band where it sings; then the two faders
    const mono = new Float32Array(N);
    for (let i = 0; i < N; i++) mono[i] = (L[i]! + R[i]!) * 0.5;
    const rb = rmsWhere(mono, gate), rv = rmsWhere(dry, gate);
    if (rb > 1e-5 && rv > 1e-5) vGain = Math.max(0.25, Math.min(4, (rb * Math.pow(10, lead / 20)) / rv)) * vAmt;
    for (let i = 0; i < N; i++) {
      L[i] = L[i]! * mAmt;
      R[i] = R[i]! * mAmt;
    }
    if (rv > 1e-5) levels = { vocals: dB(rv * vGain), music: dB(rb * mAmt), balance: dB((rv * vGain) / Math.max(rb * mAmt, 1e-6)) };
  }
  const stems = o.stems ? { vocals: { left: new Float32Array(N), right: new Float32Array(N), sampleRate: sr, seconds, peak: 0 }, music: { left: Float32Array.from(L), right: Float32Array.from(R), sampleRate: sr, seconds, peak: 0 } } : undefined;
  for (let i = 0; i < N; i++) {
    const vl = VL[i]! * vGain, vr = VR[i]! * vGain;
    if (stems) {
      stems.vocals.left[i] = vl;
      stems.vocals.right[i] = vr;
    }
    L[i] = L[i]! + vl;
    R[i] = R[i]! + vr;
  }
  let peak = 0;
  for (let i = 0; i < N; i++) {
    let l = L[i]!, r = R[i]!;
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
  if (stems)
    for (const st of [stems.vocals, stems.music]) {
      let p = 0;
      for (let i = 0; i < N; i++) p = Math.max(p, Math.abs(st.left[i]!), Math.abs(st.right[i]!));
      st.peak = p;
    }
  return { left: L, right: R, sampleRate: sr, seconds, peak, vocalShift: shiftOut, levels, stems };
}
