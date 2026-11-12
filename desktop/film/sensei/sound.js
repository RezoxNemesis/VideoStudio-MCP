// Sensei listens: loudness against the platform's target, peaks, lines buried under music, voices at different
// loudness, and stretches where the film falls silent.
import fs from 'node:fs';
import { spawn } from 'node:child_process';
import { ENV } from '../engine/env.js';
import { issue } from './rules.js';

/** integrated loudness, range and true peak of a sound file (ffmpeg ebur128) */
export function loudness(file) {
  return new Promise((resolve) => {
    const ff = spawn(ENV.ffmpeg, ['-hide_banner', '-nostats', '-i', file, '-af', 'ebur128=peak=true', '-f', 'null', '-'], { stdio: ['ignore', 'ignore', 'pipe'] });
    let err = ''; ff.stderr.on('data', (d) => { err += d; if (err.length > 2e6) err = err.slice(-20000); });
    ff.on('error', () => resolve(null));
    ff.on('close', () => {
      const sum = err.slice(err.lastIndexOf('Summary:'));
      const num = (re) => { const m = sum.match(re); return m ? parseFloat(m[1]) : null; };
      resolve({ I: num(/I:\s+(-?[\d.]+) LUFS/), LRA: num(/LRA:\s+(-?[\d.]+) LU/), peak: num(/Peak:\s+(-?[\d.]+) dBFS/) });
    });
  });
}
export async function soundChecks(P, lang, B) {
  const out = [], push = (o) => { if (o.sev) out.push(issue({ shot: null, seq: null, ...o })); };
  const mixFile = `${P.D.out}/mix.${lang}.wav`;
  if (!fs.existsSync(mixFile)) return { issues: out, measured: null, note: 'The film has no mixed sound yet (code.build mixes it).' };
  const L = await loudness(mixFile);
  if (L?.I != null && Math.abs(L.I - B.lufs) > B.lufsTol) push({ sev: 'should', cat: 'sound', kind: 'loudness', value: L.I, limit: B.lufs, unit: 'LUFS', msg: `The film plays at ${L.I} LUFS; ${B.name} expects ${B.lufs} ± ${B.lufsTol}. Platforms will turn it ${L.I > B.lufs ? 'down' : 'up'}, changing the balance you mixed.`, fix: `Mix to ${B.lufs} LUFS (the mixer's loudnorm target).` });
  if (L?.peak != null && L.peak > B.peak) push({ sev: 'should', cat: 'sound', kind: 'true-peak', value: L.peak, limit: B.peak, unit: 'dBTP', msg: `Peaks reach ${L.peak} dBTP (limit ${B.peak}) — they distort after the platform's encode.`, fix: 'Lower the loudest effects or let the limiter work harder.' });
  let st = null; try { st = JSON.parse(fs.readFileSync(mixFile.replace(/\.wav$/, '.stats.json'), 'utf8')); } catch { /* an older mix */ }
  if (st) {
    const buried = st.lines.filter((l) => l.over < B.dialogueOverMusic && l.bed > -60).sort((a, b) => a.over - b.over);
    if (buried.length) {
      const w = buried[0];
      push({ sev: buried.length > 3 || w.over < 3 ? 'should' : 'nice', cat: 'sound', kind: 'dialogue-buried', at: w.at, t0: w.at, t1: +(w.at + w.secs).toFixed(2), value: w.over, limit: B.dialogueOverMusic, unit: 'dB', data: { lines: buried.slice(0, 12).map((l) => ({ id: l.id, at: l.at, over: l.over })) },
        msg: `${buried.length} line${buried.length > 1 ? 's sit' : ' sits'} less than ${B.dialogueOverMusic} dB above the music and effects (worst: ${w.id} at ${w.at} s, only ${w.over} dB) — words get lost on phone speakers.`, fix: `Lower the music cue under those lines (gain) or duck harder; keep voices ≥ ${B.dialogueOverMusic} dB above the bed.` });
    }
    const v = st.lines.map((l) => l.voice).filter((x) => x > -60);
    if (v.length > 3) { const spread = Math.max(...v) - Math.min(...v); if (spread > B.lineSpread) push({ sev: 'nice', cat: 'sound', kind: 'voice-levels', value: +spread.toFixed(1), limit: B.lineSpread, unit: 'dB', msg: `Lines differ by ${spread.toFixed(1)} dB in loudness — some voices jump out, others hide.`, fix: 'Even the line levels (the mixer evens them; check lines with a very different tone or a whisper).' }); }
    for (const [a, b] of st.quiet ?? []) if (b - a >= B.deadAir) push({ sev: 'nice', cat: 'sound', kind: 'dead-air', t0: a, t1: b, at: a, value: +(b - a).toFixed(1), limit: B.deadAir, unit: 's', msg: `${(b - a).toFixed(1)} s of near silence from ${a} s — it sounds like the sound dropped out.`, fix: 'Lay ambience (wind, room tone) or music under it, unless the silence is meant.' });
  }
  return { issues: out, measured: L, lines: st?.lines?.length ?? 0 };
}
