// Sensei looks at the pictures themselves: too dark or burnt out, crushed blacks, a figure that melts into the
// background, a colour jump at a cut, parts of the picture that flash for a frame — and the drawing style.
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { ShotRun } from '../engine/shot.js';
import { shotFrom } from '../kit/project.js';
import { ENV } from '../engine/env.js';
import { issue } from './rules.js';
import { fingerprint, labOf, dE } from './style.js';
import { idxAt } from './collect.js';

const t2 = (x) => Math.round(x * 100) / 100;
export const PW = 320, PH = 180;
/** Frames of a rendered shot at `rate` per second, small. → [{ t, rgb }] */
export function decode(file, rate = 4, W = PW, H = PH) {
  return new Promise((resolve, reject) => {
    const ff = spawn(ENV.ffmpeg, ['-v', 'error', '-i', file, '-vf', `fps=${rate},scale=${W}:${H}:flags=area`, '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], { stdio: ['ignore', 'pipe', 'pipe'] });
    const chunks = []; let err = '';
    ff.stdout.on('data', (d) => chunks.push(d)); ff.stderr.on('data', (d) => { err += d; });
    ff.on('error', reject);
    ff.on('close', (c) => { if (c !== 0) return reject(new Error(err.slice(-300) || `ffmpeg ${c}`)); const all = Buffer.concat(chunks), n = W * H * 3, out = []; for (let k = 0; k * n + n <= all.length; k++) out.push({ t: (k + 0.5) / rate, rgb: all.subarray(k * n, k * n + n) }); resolve(out); });
  });
}
/** Frames drawn from the code (for a shot that is not rendered yet). */
export function drawn(P, sh, times, lang) {
  const out = [];
  for (const t of times) {
    const run = new ShotRun(shotFrom(P, sh.id, t), P.CAST, { lang, W: PW, H: PH, fps: P.config.fps, noGrain: true, noSubs: true });
    const cv = run.draw(0), d = cv.getContext('2d').getImageData(0, 0, PW, PH).data, rgb = Buffer.alloc(PW * PH * 3);
    for (let i = 0; i < PW * PH; i++) { rgb[i * 3] = d[i * 4]; rgb[i * 3 + 1] = d[i * 4 + 1]; rgb[i * 3 + 2] = d[i * 4 + 2]; }
    out.push({ t, rgb });
  }
  return out;
}
function meanLab(rgb, x0, y0, x1, y1, notIn = null) {
  let L = 0, A = 0, Bb = 0, n = 0;
  x0 = Math.max(0, Math.floor(x0)); y0 = Math.max(0, Math.floor(y0)); x1 = Math.min(PW - 1, Math.ceil(x1)); y1 = Math.min(PH - 1, Math.ceil(y1));
  for (let y = y0; y <= y1; y += 2) for (let x = x0; x <= x1; x += 2) {
    if (notIn && x >= notIn[0] && x <= notIn[2] && y >= notIn[1] && y <= notIn[3]) continue;
    const i = (y * PW + x) * 3, l = labOf(rgb[i], rgb[i + 1], rgb[i + 2]); L += l[0]; A += l[1]; Bb += l[2]; n++;
  }
  return n ? [L / n, A / n, Bb / n] : null;
}
/**
 * The picture checks of one shot. frames: from decode() or drawn(); D: the shot's data (collect) for where the figures are.
 * → { issues, style (average fingerprint), first/last mean colour }
 */
export function pictureChecks(D, frames, B) {
  const out = [], push = (o) => { if (o.sev) out.push(issue({ shot: D.id, seq: D.seq, ...o })); };
  if (!frames.length) return { issues: out, style: null, first: null, last: null };
  const per = frames.map((f) => {
    let s = 0, clip = 0, crush = 0; const n = PW * PH;
    for (let i = 0; i < n; i++) { const y = (0.2126 * f.rgb[i * 3] + 0.7152 * f.rgb[i * 3 + 1] + 0.0722 * f.rgb[i * 3 + 2]) / 255; s += y; if (y > 0.985) clip++; if (y < 0.015) crush++; }
    return { t: f.t, mean: s / n, clip: clip / n, crush: crush / n };
  });
  const share = (fn) => per.filter(fn).length / per.length, avg = (k) => per.reduce((s, x) => s + x[k], 0) / per.length;
  if (share((x) => x.mean < B.dark) > 0.6) push({ sev: avg('mean') < B.dark * 0.5 ? 'should' : 'nice', cat: 'picture', kind: 'too-dark', t0: 0, t1: t2(D.dur), value: +avg('mean').toFixed(3), limit: B.dark, unit: 'mean value', where: { x: 0.5, y: 0.5 }, msg: `The picture is very dark (average value ${Math.round(avg('mean') * 100)}%) — on a phone in daylight it will read as black.`, fix: 'Even at night keep faces and the subject lit: a rim light (actor rim), a lantern/moon key, or raise the set\'s ambient.' });
  if (share((x) => x.mean > B.bright) > 0.6) push({ sev: 'nice', cat: 'picture', kind: 'too-bright', t0: 0, t1: t2(D.dur), value: +avg('mean').toFixed(3), limit: B.bright, unit: 'mean value', where: { x: 0.5, y: 0.5 }, msg: `The picture is washed out (average value ${Math.round(avg('mean') * 100)}%).`, fix: 'Lower the exposure/wash, add shadow areas.' });
  if (avg('clip') > B.clip) push({ sev: 'nice', cat: 'picture', kind: 'blown-highlights', t0: 0, t1: t2(D.dur), value: +avg('clip').toFixed(3), limit: B.clip, unit: 'of the picture', where: { x: 0.5, y: 0.3 }, msg: `${Math.round(avg('clip') * 100)}% of the picture is burnt to pure white.`, fix: 'Tone down bloom/glow and white washes; keep highlights below pure white.' });
  if (avg('crush') > B.crush) push({ sev: 'nice', cat: 'picture', kind: 'crushed-blacks', t0: 0, t1: t2(D.dur), value: +avg('crush').toFixed(3), limit: B.crush, unit: 'of the picture', where: { x: 0.5, y: 0.7 }, msg: `${Math.round(avg('crush') * 100)}% of the picture is pure black — detail is lost.`, fix: 'Lift the shadows a little or add fill light.' });
  // the main figure against what is behind it
  let low = 0, tot = 0, worst = null;
  for (const f of frames) {
    const i = idxAt(D, Math.min(D.dur, f.t)); let S = null; for (const a of D.actors) if (a.vis[i] && a.box[i] && (!S || a.size[i] > S.size[i])) S = a;
    if (!S || S.size[i] < 0.12) continue;
    const [bx0, by0, bx1, by1] = S.box[i].map((v, k) => v * (k % 2 ? PH : PW)), w = bx1 - bx0, h = by1 - by0;
    const inner = meanLab(f.rgb, bx0 + w * 0.3, by0 + h * 0.15, bx1 - w * 0.3, by1 - h * 0.25);
    const ring = meanLab(f.rgb, bx0 - w * 0.35, by0 - h * 0.1, bx1 + w * 0.35, by1, [bx0, by0, bx1, by1]);
    if (!inner || !ring) continue; tot++;
    const d = dE(inner, ring); if (d < B.blend) { low++; if (!worst || d < worst.d) worst = { d, t: f.t, id: S.id, x: (bx0 + bx1) / 2 / PW, y: (by0 + by1) / 2 / PH }; }
  }
  if (tot >= 2 && low / tot > 0.6 && worst) push({ sev: 'should', cat: 'picture', kind: 'blends-in', actor: worst.id, t0: 0, t1: t2(D.dur), at: t2(worst.t), where: { x: +worst.x.toFixed(3), y: +worst.y.toFixed(3) }, value: +worst.d.toFixed(1), limit: B.blend, unit: 'ΔE',
    msg: `${worst.id} melts into the background (colour difference ${worst.d.toFixed(0)}; a figure needs ≥ ${B.blend} to read at a glance).`, fix: `Separate figure from ground: a darker/lighter costume colour, a rim light (actor rim), or a background that is lighter/darker or softer (dof) behind ${worst.id}.` });
  const fps = frames.slice(0, 6).map((f) => fingerprint(f.rgb, PW, PH, 3));
  const st = {}; for (const k of Object.keys(fps[0])) if (typeof fps[0][k] === 'number') st[k] = +(fps.reduce((s, f) => s + f[k], 0) / fps.length).toFixed(4);
  return { issues: out, style: st, first: meanLab(frames[0].rgb, 0, 0, PW - 1, PH - 1), last: meanLab(frames[frames.length - 1].rgb, 0, 0, PW - 1, PH - 1), luma: +avg('mean').toFixed(3) };
}
/** Colour jumps at cuts inside a sequence. sums: [{ id, seq, first, last }] in film order */
export function colourCuts(sums, B) {
  const out = [];
  for (let k = 1; k < sums.length; k++) {
    const a = sums[k - 1], b = sums[k]; if (!a?.last || !b?.first || a.seq !== b.seq) continue;
    const d = dE(a.last, b.first);
    if (d > B.colourJump) out.push(issue({ sev: d > B.colourJump * 1.6 ? 'should' : 'nice', cat: 'picture', kind: 'colour-jump', shot: b.id, seq: b.seq, t0: 0, t1: 0, at: 0, where: { x: 0.5, y: 0.5 }, value: +d.toFixed(1), limit: B.colourJump, unit: 'ΔE',
      msg: `The colour jumps at the cut ${a.id} → ${b.id} (difference ${d.toFixed(0)}): same scene, but it looks like another time of day.`, fix: `Match the grade of the two shots (wash/post) or the light direction; if the angle shows a brighter side, soften it.` }));
  }
  return out;
}
/** Flash scan of a rendered shot (parts of the picture that change for a few frames and come back). */
export { flashScan } from '../kit/qa.js';
export const shotFile = (P, id, lang) => { try { const idx = JSON.parse(fs.readFileSync(path.join(P.D.shots, 'index.json'), 'utf8')); for (const k of [`${id}|${lang}`, `${id}|-`]) if (idx[k] && fs.existsSync(path.join(P.D.shots, idx[k].file))) return path.join(P.D.shots, idx[k].file); } catch { /* not built */ } return null; };
