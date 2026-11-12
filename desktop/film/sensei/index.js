// Sensei — VideoStudio's own judge. It looks at a code film exhaustively (every body at 60 samples a second, every
// camera, the pictures, the sound, the script) and says what is wrong, where, how bad, and what to change.
import fs from 'node:fs';
import path from 'node:path';
import { loadImage, createCanvas } from '@napi-rs/canvas';
import { bench as benchOf } from './rules.js';
import { collect } from './collect.js';
import { motionChecks } from './motion.js';
import { cameraChecks, cutChecks } from './camera.js';
import { pictureChecks, colourCuts, decode, drawn, shotFile } from './picture.js';
import { soundChecks } from './sound.js';
import { storyChecks } from './story.js';
import { fingerprint, average, compare } from './style.js';
import { dedupe, buildReport, digest } from './report.js';
import { annotateIssue } from './annotate.js';
import { issue } from './rules.js';

const HERE = path.dirname(new URL(import.meta.url).pathname);
export const STYLES = JSON.parse(fs.readFileSync(path.join(HERE, 'styles.json'), 'utf8'));
export const CHECKS = ['motion', 'camera', 'picture', 'sound', 'style', 'story'];
const want = (o, c) => !o.checks || o.checks.includes(c);

/** Part of a review: the shots `ids` (one worker). → { issues, sums } */
export async function reviewShots(P, ids, o = {}, emit = () => {}) {
  const B = benchOf(o.bench), lang = o.lang ?? P.config.langs[0], issues = [], sums = [];
  let k = 0;
  for (const id of ids) {
    const sh = P.shots.find((s) => s.id === id); if (!sh || sh.off) continue;
    try {
      const D = collect(P, sh, { lang });
      if (want(o, 'motion')) issues.push(...motionChecks(D, B));
      const cam = cameraChecks(D, B);
      if (want(o, 'camera')) issues.push(...cam.issues);
      const sum = cam.summary;
      if (want(o, 'picture') || want(o, 'style')) {
        const file = shotFile(P, id, lang);
        let frames = [];
        if (file) frames = await decode(file, o.rate ?? 3);
        else if (o.draw !== false) frames = drawn(P, sh, [0.2, 0.55, 0.85].map((u) => +(u * sh.dur).toFixed(2)), lang);
        if (frames.length) { const pc = pictureChecks(D, frames, B); if (want(o, 'picture')) issues.push(...pc.issues); Object.assign(sum, { first: pc.first, last: pc.last, style: pc.style, luma: pc.luma, rendered: !!file }); }
      }
      sums.push(sum);
    } catch (e) {
      issues.push(issue({ sev: 'must', cat: 'story', kind: 'shot-error', shot: id, seq: sh.seq, msg: `Sensei could not look at ${id}: ${String(e?.message ?? e).split('\n')[0]}`, fix: 'The shot does not load or draw: run code.frame on it to see the error.' }));
      sums.push(null);
    }
    emit({ event: 'progress', done: ++k, of: ids.length, shot: id });
  }
  return { issues, sums };
}
/** Fingerprint of reference pictures (files) → average */
export async function styleOfFiles(files) {
  const fps = [];
  for (const f of files) { const img = await loadImage(f); const W = 480, H = Math.max(2, Math.round((W * img.height) / img.width)); const c = createCanvas(W, H), g = c.getContext('2d'); g.drawImage(img, 0, 0, W, H); fps.push(fingerprint(g.getImageData(0, 0, W, H).data, W, H, 4)); }
  return average(fps);
}
/** The rest of a review, once every shot was looked at: cuts, story, sound, style, scores, the report and its pictures. */
export async function finish(P, parts, o = {}, emit = () => {}) {
  const t0 = Date.now(), B = benchOf(o.bench), lang = o.lang ?? P.config.langs[0];
  const order = new Map(P.shots.map((s, k) => [s.id, k]));
  const sums = parts.flatMap((p) => p.sums).filter(Boolean).sort((a, b) => order.get(a.id) - order.get(b.id));
  let issues = parts.flatMap((p) => p.issues);
  if (want(o, 'camera')) issues.push(...cutChecks(sums, B));
  if (want(o, 'picture')) issues.push(...colourCuts(sums, B));
  if (want(o, 'story')) issues.push(...storyChecks(P, sums, lang, B));
  let sound = null;
  if (want(o, 'sound') && o.scope !== 'shots') { const s = await soundChecks(P, lang, B); issues.push(...s.issues); sound = { measured: s.measured, note: s.note }; }
  let style = null;
  if (want(o, 'style')) {
    const fps = sums.map((s) => s.style).filter(Boolean);
    const look = P.config.look?.style ?? P.config.look ?? 'anime';
    const refName = o.style ?? (['anime', 'multiplane', 'soft3d', 'cel3d', 'immersive'].includes(look) ? 'anime-scene' : null);
    let ref = null, name = refName;
    if (o.refs?.length) { ref = await styleOfFiles(o.refs); name = o.refName ?? 'your references'; }
    else if (refName) { const own = (() => { try { return JSON.parse(fs.readFileSync(path.join(P.D.vs, 'sensei', 'styles.json'), 'utf8')); } catch { return {}; } })(); ref = own[refName]?.fp ?? STYLES[refName]?.fp ?? null; name = own[refName]?.name ?? STYLES[refName]?.name ?? refName; }
    if (ref && fps.length) {
      const me = average(fps), cmp = compare(me, ref);
      style = { ref: name, score: cmp.score, gaps: cmp.gaps, me, refFp: ref };
      for (const g of cmp.gaps) issues.push(issue({ sev: o.bench === 'draft' ? 'nice' : g.sev, cat: 'style', kind: `style-${g.key}`, msg: g.say, fix: g.fix, value: g.me, limit: g.ref }));
    }
  }
  if (B.onlyMust) issues = issues.filter((i) => i.sev === 'must');
  issues = dedupe(issues);
  // what was really measured (a film without mixed sound has no sound score; no reference style, no style score)
  const measured = new Set(['motion', 'anatomy', 'camera', 'framing', 'story'].filter((c) => want(o, c === 'anatomy' ? 'motion' : c === 'framing' ? 'camera' : c)));
  if (want(o, 'picture') && sums.some((s) => s.first)) measured.add('picture');
  if (sound?.measured) measured.add('sound');
  if (style) measured.add('style');
  const rep = buildReport(P, { issues, bench: B, lang, shots: sums.length, ms: (o.ms ?? 0) + (Date.now() - t0), style, sound, scope: o.scope, only: o.only ?? null, measured });
  // pictures of the worst findings, so a person sees the problem without hunting for it
  const nImg = o.images ?? 12;
  if (nImg > 0) {
    const dir = path.join(P.D.vs, 'sensei', 'img'); fs.mkdirSync(dir, { recursive: true });
    for (const f of fs.readdirSync(dir)) if (!rep.issues.some((i) => `${i.id}.jpg` === f)) fs.rmSync(path.join(dir, f), { force: true });
    const pick = rep.issues.filter((i) => i.shot && i.sev !== 'nice' && (i.pt || i.where)).slice(0, nImg);
    let n = 0;
    for (const i of pick) {
      const file = path.join(dir, `${i.id}.jpg`);
      try { if (!fs.existsSync(file)) fs.writeFileSync(file, annotateIssue(P, i, { scale: 0.5, lang }).toBuffer('image/jpeg', 84)); i.img = file; } catch { /* a frame that does not draw is reported elsewhere */ }
      emit({ event: 'progress', phase: 'pictures', done: ++n, of: pick.length });
    }
    fs.writeFileSync(path.join(P.D.vs, 'sensei', 'latest.json'), JSON.stringify(rep));
  }
  return { digest: digest(rep, o.top ?? 12), files: { json: path.join(P.D.vs, 'sensei', 'latest.json'), md: path.join(P.D.vs, 'sensei', 'latest.md') } };
}
export { digest, annotateIssue, benchOf, STYLES as BUILTIN_STYLES };
