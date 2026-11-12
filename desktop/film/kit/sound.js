// The film's sound: voices in the middle, music and ambience that make room for them, effects placed by time.
// 48 kHz stereo, levelled to −14 LUFS.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ENV } from '../engine/env.js';

const SR = 48000;
const KIT_SOUNDS = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'assets');
/** The sounds that come with the kit: { sfx: [names], amb: [names] }. */
export const kitSounds = () => Object.fromEntries(['sfx', 'amb'].map((d) => { try { return [d, fs.readdirSync(path.join(KIT_SOUNDS, d)).map((f) => f.replace(/\.\w+$/, '')).sort()]; } catch { return [d, []]; } }));
const cache = new Map();
/** Any sound file → { l, r } float samples at 48 kHz. */
function load(file) {
  let x = cache.get(file);
  if (x) return x;
  const buf = execFileSync(ENV.ffmpeg, ['-v', 'error', '-i', file, '-f', 'f32le', '-ac', '2', '-ar', String(SR), '-'], { maxBuffer: 1 << 31 - 1 });
  const a = new Float32Array(buf.buffer, buf.byteOffset, Math.floor(buf.byteLength / 4)), n = a.length >> 1, l = new Float32Array(n), r = new Float32Array(n);
  for (let i = 0; i < n; i++) { l[i] = a[2 * i]; r[i] = a[2 * i + 1]; }
  x = { l, r, n }; cache.set(file, x); return x;
}
const find = (dirs, name) => { for (const d of dirs) for (const ext of ['.wav', '.flac', '.mp3', '.ogg', '.m4a', '.webm']) { const p = path.join(d, name + ext); if (fs.existsSync(p)) return p; } return null; };
const bus = (n) => ({ l: new Float32Array(n), r: new Float32Array(n), n });
function place(b, x, at, gain = 1, pan = 0, from = 0, len = x.n - from, fi = 0, fo = 0) {
  let i = Math.round(at * SR), s = from;
  if (i < 0) { s -= i; len += i; i = 0; }
  const n = Math.min(len, b.n - i, x.n - s); if (n <= 0) return;
  const gl = gain * Math.min(1, 1 - pan), gr = gain * Math.min(1, 1 + pan), a = Math.min(n, Math.round(fi * SR)), z = Math.min(n, Math.round(fo * SR));
  for (let k = 0; k < n; k++) {
    let e = 1;
    if (k < a) e *= Math.pow(k / a, 1.5);
    if (k >= n - z) e *= Math.pow((n - 1 - k) / z, 1.5);
    b.l[i + k] += x.l[s + k] * gl * e; b.r[i + k] += x.r[s + k] * gr * e;
  }
}
/** n samples of x, repeated with a crossfade when it is too short. */
function looped(x, n, xf = 1.5) {
  if (x.n >= n) return x;
  const k = Math.min(Math.round(xf * SR), x.n >> 1), o = bus(n);
  for (let pos = 0, first = true; pos < n; pos += x.n - k, first = false)
    for (let i = 0; i < x.n && pos + i < n; i++) {
      let e = 1;
      if (!first && i < k) e = i / k;
      if (i >= x.n - k && pos + x.n - k < n) e *= (x.n - 1 - i) / k;
      o.l[pos + i] += x.l[i] * e; o.r[pos + i] += x.r[i] * e;
    }
  return o;
}
function pitched(x, f) {
  if (Math.abs(f - 1) < 0.01) return x;
  const n = Math.max(8, Math.floor(x.n / f)), o = bus(n);
  for (let i = 0; i < n; i++) { const p = (i * (x.n - 1)) / (n - 1), i0 = Math.floor(p), fr = p - i0, i1 = Math.min(x.n - 1, i0 + 1); o.l[i] = x.l[i0] * (1 - fr) + x.l[i1] * fr; o.r[i] = x.r[i0] * (1 - fr) + x.r[i1] * fr; }
  return o;
}
/** A small natural room added to a bus (feedback delays with damping). */
function room(b, wet = 0.1, secs = 0.7) {
  const taps = [1557, 1617, 1491, 1422, 1277, 1356].map((d) => Math.round((d * SR) / 44100)), fb = Math.pow(0.001, taps[0] / (secs * SR));
  for (const ch of ['l', 'r']) {
    const x = b[ch], y = new Float32Array(b.n);
    taps.forEach((d, ti) => {
      const dl = d + (ch === 'r' ? 23 : 0), buf = new Float32Array(dl); let p = 0, lp = 0;
      for (let i = 0; i < b.n; i++) { const o = buf[p]; lp = o * 0.6 + lp * 0.4; buf[p] = x[i] + lp * fb; p = (p + 1) % dl; y[i] += o; }
      void ti;
    });
    for (let i = 0; i < b.n; i++) x[i] += (wet / 6) * y[i] * 0.9;
  }
}
function peak(b) { let m = 0; for (let i = 0; i < b.n; i++) { const a = Math.abs(b.l[i]), c = Math.abs(b.r[i]); if (a > m) m = a; if (c > m) m = c; } return m; }

/**
 * Mix the film's sound for one language. TL: the timeline (kit/project.js). dirs: { vo, audio } of the project.
 * Sound files are looked for in <audio>/music, <audio>/sfx, <audio>/amb, <audio>. → { file, seconds, missing: [] }
 */
export function mix(TL, D, lang, outFile) {
  const N = Math.round((TL.total + 0.5) * SR), dia = bus(N), mus = bus(N), amb = bus(N), fx = bus(N), missing = [];
  // the film's own sounds first, then the kit's (footsteps, cloth, hits, whooshes; wind, birds, rain, sea …)
  const where = [path.join(D.audio, 'music'), path.join(D.audio, 'sfx'), path.join(D.audio, 'amb'), D.audio, path.join(KIT_SOUNDS, 'sfx'), path.join(KIT_SOUNDS, 'amb')];
  for (const c of TL.cues) {
    const p = path.join(D.vo, lang, `${c.id}.wav`);
    if (!fs.existsSync(p)) { missing.push(`voice ${c.id}`); continue; }
    const x = load(p); let pk = 1e-9, sq = 0;
    for (let i = 0; i < x.n; i++) { const a = Math.abs(x.l[i]); if (a > pk) pk = a; sq += x.l[i] * x.l[i]; }
    const g = Math.min(0.9 / pk, 0.075 / (Math.sqrt(sq / Math.max(1, x.n)) + 1e-9)); // every line at a similar loudness, never clipping
    if (c.vo) { const o = bus(x.n + SR); place(o, x, 0, g); room(o, 0.2, 1.4); place(dia, o, c.at + (c[lang]?.off ?? 0)); } // the narrator: a little larger than life
    else place(dia, x, c.at + (c[lang]?.off ?? 0), g);
  }
  room(dia, 0.05, 0.5);
  for (const e of TL.sounds) {
    if ('sfx' in e) {
      const p = find(where, e.sfx); if (!p) { missing.push(`sound ${e.sfx}`); continue; }
      const x = pitched(load(p), e.pitch ?? 1), len = e.cut ? Math.min(x.n, Math.round(e.cut * SR)) : x.n;
      place(fx, x, e.t - (e.align === 'end' ? len / SR : 0), e.gain ?? 0.7, e.pan ?? 0, 0, len, 0, e.cut ? 0.08 : Math.min(0.05, len / SR / 4));
    } else if ('amb' in e || 'music' in e) {
      const key = (e.amb ?? e.music).replace('{lang}', lang), p = find(where, key); if (!p) { missing.push(`bed ${key}`); continue; }
      const src = load(p), from = Math.round((e.from ?? 0) * SR), t1 = e.t1 ?? e.t0 + (src.n - from) / SR, n = Math.round((t1 - e.t0) * SR); if (n <= 0) continue;
      const isAmb = 'amb' in e;
      const x = isAmb || e.loop ? looped(from ? { l: src.l.subarray(from), r: src.r.subarray(from), n: src.n - from } : src, n) : src;
      place(isAmb ? amb : mus, x, e.t0, e.gain ?? 0.5, 0, isAmb || e.loop ? 0 : from, n, e.fadeIn ?? e.fade ?? (isAmb ? 1.2 : 0.05), e.fadeOut ?? e.fade ?? (isAmb ? 1.2 : 2.0));
    }
  }
  // music and ambience make room for the voices
  const step = 192, a = Math.exp(-step / (0.05 * SR)), r = Math.exp(-step / (0.45 * SR)); let v = 0;
  for (let i = 0; i < N; i += step) {
    let m = 0; for (let k = i; k < Math.min(N, i + step); k++) { const q = Math.max(Math.abs(dia.l[k]), Math.abs(dia.r[k])); if (q > m) m = q; }
    v = m > v ? a * v + (1 - a) * m : r * v + (1 - r) * m;
    const duck = Math.min(1, v / 0.03), gm = 1 - 0.6 * duck, ga = 1 - 0.35 * duck;
    for (let k = i; k < Math.min(N, i + step); k++) { mus.l[k] *= gm; mus.r[k] *= gm; amb.l[k] *= ga; amb.r[k] *= ga; }
  }
  const fxpk = peak(fx), fg = fxpk > 0.95 ? 0.95 / fxpk : 1, out = Buffer.alloc(N * 4);
  let pk = 0; const L = new Float32Array(N), R = new Float32Array(N);
  for (let i = 0; i < N; i++) { L[i] = dia.l[i] + mus.l[i] * 0.9 + amb.l[i] * 0.8 + fx.l[i] * 0.9 * fg; R[i] = dia.r[i] + mus.r[i] * 0.9 + amb.r[i] * 0.8 + fx.r[i] * 0.9 * fg; pk = Math.max(pk, Math.abs(L[i]), Math.abs(R[i])); }
  const lim = pk > 0.85, k = Math.tanh(1.1);
  for (let i = 0; i < N; i++) {
    const l = lim ? (Math.tanh(L[i] * 1.1) / k) * 0.89 : L[i], rr = lim ? (Math.tanh(R[i] * 1.1) / k) * 0.89 : R[i];
    out.writeInt16LE(Math.max(-32767, Math.min(32767, Math.round(l * 32767))), i * 4); out.writeInt16LE(Math.max(-32767, Math.min(32767, Math.round(rr * 32767))), i * 4 + 2);
  }
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  const raw = outFile.replace(/\.wav$/, '.raw.pcm'); fs.writeFileSync(raw, out);
  execFileSync(ENV.ffmpeg, ['-y', '-v', 'error', '-f', 's16le', '-ar', String(SR), '-ac', '2', '-i', raw, '-af', 'loudnorm=I=-14:TP=-1.5:LRA=11', '-ar', '48000', outFile]);
  fs.rmSync(raw, { force: true }); cache.clear();
  return { file: outFile, seconds: +(N / SR).toFixed(2), missing, peaks: { dialogue: +peak(dia).toFixed(2), music: +peak(mus).toFixed(2), ambience: +peak(amb).toFixed(2), effects: +fxpk.toFixed(2) } };
}

const ts = (t) => { const h = Math.floor(t / 3600), m = Math.floor(t / 60) % 60, s = t % 60; return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${s.toFixed(3).padStart(6, '0').replace('.', ',')}`; };
/** The subtitles of one language as SRT text. */
export function srt(TL, lang) {
  const cues = TL.cues.map((c) => ({ a: c.at + (c[lang]?.off ?? 0), b: c.at + (c[lang]?.off ?? 0) + (c[lang]?.secs || c.slot) + 0.3, text: c[lang]?.text ?? '' })).filter((c) => c.text).sort((x, y) => x.a - y.a);
  return cues.map((c, i) => { const b = Math.max(c.a + 0.5, Math.min(c.b, i + 1 < cues.length ? cues[i + 1].a - 0.04 : 1e9)); return `${i + 1}\n${ts(c.a)} --> ${ts(b)}\n${c.text}\n`; }).join('\n');
}
