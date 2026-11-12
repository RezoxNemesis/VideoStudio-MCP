/**
 * Songs with singing: the instruments are rendered by the synthesizer, every vocal track is sung by a voice
 * (voice/engine/sing.ts), given a little room, and the two are mixed.
 */
import { beatSec, songBeats, type Song, type Track } from "./model";
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

/** Bounce a song with its vocals. Without an engine (or without vocal tracks) this is the instrumental render. */
export async function renderWithVocals(song: Song, engine: VocalEngine | null, o: RenderOptions & { sing?: SingOptions } = {}): Promise<Rendered & { vocalShift?: number }> {
  const soloed = song.tracks.some((t) => t.solo);
  const vocals = song.tracks.filter((t) => t.kind === "vocal" && t.notes.length && !t.mute && (!soloed || t.solo) && (!o.only || o.only.includes(t.id)));
  const hasBand = song.tracks.some((t) => t.kind !== "vocal" && t.notes.length && !t.mute && (!soloed || t.solo) && (!o.only || o.only.includes(t.id)));
  const band = hasBand ? await renderSong(song, { ...o, onProgress: (d) => o.onProgress?.(d * (vocals.length ? 0.4 : 1)) }) : null;
  if (!engine || !vocals.length) {
    if (!band) throw new Error("There is nothing to render: no notes on any audible track.");
    return band;
  }
  const sr = band?.sampleRate ?? 48000;
  const spb = beatSec(song);
  const seconds = band?.seconds ?? songBeats(song) * spb + 2.5;
  const N = band ? band.left.length : Math.ceil(seconds * sr);
  const L = band ? band.left : new Float32Array(N), R = band ? band.right : new Float32Array(N);
  // the band steps back a little to leave room for the voice
  if (band) for (let i = 0; i < N; i++) {
    L[i] = L[i]! * 0.78;
    R[i] = R[i]! * 0.78;
  }
  let shiftOut: number | undefined;
  for (let v = 0; v < vocals.length; v++) {
    const t = vocals[v]!;
    const { notes, shift } = sungNotes(song, t);
    shiftOut = shift;
    const from = (o.from ?? 0) * spb;
    const pcm = await engine.sing(notes, t.voice ?? "hf_alpha", o.sing);
    o.onProgress?.(0.4 + (0.6 * (v + 1)) / vocals.length);
    // level: bring the sung parts to a steady loudness
    let sum = 0, cnt = 0;
    for (let i = 0; i < pcm.length; i += 2) if (Math.abs(pcm[i]!) > 0.01) {
      sum += pcm[i]! * pcm[i]!;
      cnt++;
    }
    const rms = cnt ? Math.sqrt(sum / cnt) : 0;
    const g = (rms > 1e-5 ? 0.2 / rms : 1) * t.volume;
    // 24 kHz → the song's rate, placed on the song's clock
    const up = new Float32Array(N);
    const ratio = 24000 / sr;
    for (let i = 0; i < N; i++) {
      const x = (i / sr + from) * 24000;
      const j = Math.floor(x);
      if (j < 0 || j + 1 >= pcm.length) continue;
      const a = x - j;
      up[i] = (pcm[j]! * (1 - a) + pcm[j + 1]! * a) * g;
    }
    void ratio;
    const [wl, wr] = reverb(up, sr, 0.6 + (song.room ?? 0.5) * 0.3);
    const wet = t.reverb * 0.55;
    const pl = Math.cos(((t.pan + 1) / 2) * (Math.PI / 2)) * Math.SQRT2, pr = Math.sin(((t.pan + 1) / 2) * (Math.PI / 2)) * Math.SQRT2;
    for (let i = 0; i < N; i++) {
      L[i] = L[i]! + up[i]! * pl + wl[i]! * wet;
      R[i] = R[i]! + up[i]! * pr + wr[i]! * wet;
    }
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
  return { left: L, right: R, sampleRate: sr, seconds, peak, vocalShift: shiftOut };
}
