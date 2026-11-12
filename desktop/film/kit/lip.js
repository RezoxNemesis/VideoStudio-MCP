// Mouth movement from a spoken line: 60 values a second of how far the jaw drops and how wide the lips are.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { ENV } from '../engine/env.js';

/** Decode any sound file to mono float samples at 24 kHz. */
export function decodeMono(file, sr = 24000) {
  const buf = execFileSync(ENV.ffmpeg, ['-v', 'error', '-i', file, '-f', 'f32le', '-ac', '1', '-ar', String(sr), '-'], { maxBuffer: 1 << 30 });
  return new Float32Array(buf.buffer, buf.byteOffset, Math.floor(buf.byteLength / 4));
}
function fftMag2(re, im) { // in place radix-2; returns nothing (re, im hold the transform)
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) { let bit = n >> 1; for (; j & bit; bit >>= 1) j ^= bit; j ^= bit; if (i < j) { const a = re[i]; re[i] = re[j]; re[j] = a; const b = im[i]; im[i] = im[j]; im[j] = b; } }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (-2 * Math.PI) / len, wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const ur = re[i + k], ui = im[i + k], vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci, vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
        re[i + k] = ur + vr; im[i + k] = ui + vi; re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi;
        const nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
      }
    }
  }
}
/** x: mono samples, sr → { n, dur, open: [0..1], wide: [-1..1] } at 60 values a second. */
export function analyse(x, sr = 24000, fps = 60) {
  const hop = Math.floor(sr / fps), win = 1024, nfr = Math.max(1, Math.ceil(x.length / hop));
  const hann = new Float32Array(win); for (let i = 0; i < win; i++) hann[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (win - 1));
  const bin = (f) => Math.round((f * win) / sr), bA = [bin(120), bin(4000)], bL = [bin(250), bin(1100)], bH = [bin(1500), bin(3400)];
  const lev = new Float64Array(nfr), f1 = new Float64Array(nfr), ratio = new Float64Array(nfr), re = new Float64Array(win), im = new Float64Array(win);
  for (let i = 0; i < nfr; i++) {
    for (let k = 0; k < win; k++) { const s = i * hop + k - win / 2; re[k] = s >= 0 && s < x.length ? x[s] * hann[k] : 0; im[k] = 0; }
    fftMag2(re, im);
    let e = 1e-12, lo = 1e-12, hi = 1e-12, num = 0;
    for (let k = bA[0]; k < bA[1]; k++) e += re[k] * re[k] + im[k] * im[k];
    for (let k = bL[0]; k < bL[1]; k++) { const m = re[k] * re[k] + im[k] * im[k]; lo += m; num += m * ((k * sr) / win); }
    for (let k = bH[0]; k < bH[1]; k++) hi += re[k] * re[k] + im[k] * im[k];
    lev[i] = 10 * Math.log10(e); f1[i] = num / lo; ratio[i] = Math.log10(hi / lo);
  }
  const sorted = Array.from(lev).sort((a, b) => a - b), top = sorted[Math.min(nfr - 1, Math.floor(nfr * 0.96))], floor = top - 34;
  const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v));
  const op = new Float64Array(nfr), wide = new Float64Array(nfr);
  for (let i = 0; i < nfr; i++) {
    const loud = clamp((lev[i] - floor) / (top - floor)), openness = clamp((f1[i] - 330) / (760 - 330));
    op[i] = loud < 0.16 ? 0 : Math.pow(loud, 0.75) * (0.42 + 0.58 * openness);
    wide[i] = clamp((ratio[i] + 0.9) / 0.9, -1, 1) * clamp(loud * 2);
  }
  // the jaw is quick to open and a little slower to shut; lips smooth
  const out = new Float64Array(nfr); let v = 0;
  for (let i = 0; i < nfr; i++) { v = op[i] > v ? op[i] : v + (op[i] - v) * 0.5; out[i] = v; }
  const sm = new Float64Array(nfr), ws = new Float64Array(nfr); let mx = 0;
  for (let i = 0; i < nfr; i++) {
    sm[i] = 0.2 * (out[i - 1] ?? 0) + 0.6 * out[i] + 0.2 * (out[i + 1] ?? 0); mx = Math.max(mx, sm[i]);
    let a = 0; for (let k = -2; k <= 2; k++) a += wide[i + k] ?? 0; ws[i] = a / 5;
  }
  const g = mx > 0.05 ? 1 / Math.max(mx, 0.6) : 1;
  return { n: nfr, dur: +(x.length / sr).toFixed(3), open: Array.from(sm, (q) => +clamp(q * g).toFixed(2)), wide: Array.from(ws, (q) => +q.toFixed(2)) };
}
const wavSeconds = (file) => { try { return decodeMono(file, 8000).length / 8000; } catch { return 0; } };

/**
 * After the voices of a film were spoken (raw files in <vo>/raw/<lang>/<id>.wav with <vo>/raw/<lang>.json), fit the
 * languages to each other (a line's picture lasts as long as its slowest reading: the slow one is spoken a little
 * faster, the quick one a little slower, pitch kept), then write <vo>/<lang>/<id>.wav, <lang>.json and <lang>.lip.json.
 */
export function fitAndLip(voDir, langs) {
  const raw = (l) => { try { return JSON.parse(fs.readFileSync(path.join(voDir, 'raw', `${l}.json`), 'utf8')); } catch { return {}; } };
  const meta = Object.fromEntries(langs.map((l) => [l, raw(l)])), ids = new Set(langs.flatMap((l) => Object.keys(meta[l])));
  const lips = Object.fromEntries(langs.map((l) => [l, {}])), out = Object.fromEntries(langs.map((l) => [l, {}]));
  for (const l of langs) fs.mkdirSync(path.join(voDir, l), { recursive: true });
  for (const id of ids) {
    const secs = langs.map((l) => meta[l][id]?.seconds ?? 0).filter((s) => s > 0), longest = Math.max(...secs, 0), shortest = Math.min(...secs);
    for (const l of langs) {
      const m = meta[l][id]; if (!m) continue;
      const src = path.join(voDir, 'raw', l, `${id}.wav`), dst = path.join(voDir, l, `${id}.wav`);
      let tempo = 1;
      if (secs.length > 1 && longest / shortest > 1.06) {
        // the slowest reading: up to 10 % faster; the others: up to 7 % slower
        tempo = m.seconds === longest ? Math.min(1.1, Math.max(1, (longest / shortest) / 1.06)) : Math.max(0.93, Math.min(1, (1.08 * m.seconds) / longest * Math.min(1.1, (longest / shortest) / 1.06)));
      }
      const stamp = `${m.key}|${tempo.toFixed(3)}`;
      let prev = null; try { prev = JSON.parse(fs.readFileSync(path.join(voDir, `${l}.json`), 'utf8'))[id]; } catch { /* first time */ }
      if (prev?.stamp === stamp && fs.existsSync(dst)) { out[l][id] = prev; }
      else {
        if (Math.abs(tempo - 1) < 0.012) fs.copyFileSync(src, dst);
        else execFileSync(ENV.ffmpeg, ['-y', '-v', 'error', '-i', src, '-filter:a', `atempo=${tempo.toFixed(4)}`, dst]);
        out[l][id] = { ...m, seconds: +wavSeconds(dst).toFixed(3), tempo: +tempo.toFixed(3), stamp };
      }
      lips[l][id] = analyse(decodeMono(dst));
    }
  }
  for (const l of langs) {
    fs.writeFileSync(path.join(voDir, `${l}.json`), JSON.stringify(out[l], null, 1));
    fs.writeFileSync(path.join(voDir, `${l}.lip.json`), JSON.stringify(lips[l]));
  }
  return Object.fromEntries(langs.map((l) => [l, Object.keys(out[l]).length]));
}
