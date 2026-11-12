// Sensei shows where: the frame as it is in the film, a ring round the problem, a label with what was measured.
import { createCanvas } from '@napi-rs/canvas';
import { ShotRun } from '../engine/shot.js';
import { shotFrom } from '../kit/project.js';
import { SEV } from './rules.js';

const COL = { must: '#ff3b3b', should: '#ffb020', nice: '#4fc3f7' };
function wrap(g, text, maxW) {
  const words = String(text).split(/\s+/), lines = []; let cur = '';
  for (const w of words) { const t = cur ? cur + ' ' + w : w; if (g.measureText(t).width > maxW && cur) { lines.push(cur); cur = w; } else cur = t; }
  if (cur) lines.push(cur); return lines;
}
/** Draw the marks for one finding on a picture of the frame (cam = the frame's camera, for world points). */
export function markIssue(g, W, H, iss, cam) {
  const col = COL[iss.sev] ?? '#fff', k = H / 540;
  let p = null;
  if (iss.pt && cam) { const c = cam.toCam(iss.pt); if (c[2] > 0.1) { const s = cam.project(iss.pt); p = [s[0], s[1]]; } }
  if (!p && iss.where) p = [iss.where.x * W, iss.where.y * H];
  g.save();
  if (p) {
    const r = 34 * k;
    g.lineWidth = 5 * k; g.strokeStyle = 'rgba(0,0,0,0.55)'; g.beginPath(); g.arc(p[0], p[1], r, 0, Math.PI * 2); g.stroke();
    g.lineWidth = 3 * k; g.strokeStyle = col; g.beginPath(); g.arc(p[0], p[1], r, 0, Math.PI * 2); g.stroke();
    g.beginPath(); g.arc(p[0], p[1], r * 1.6, -0.6, 0.6); g.stroke();
  }
  // label
  g.font = `600 ${Math.round(15 * k)}px sans-serif`;
  const lines = wrap(g, iss.msg, W * 0.62).slice(0, 4), lh = 19 * k, bw = Math.max(...lines.map((l) => g.measureText(l).width)) + 22 * k, bh = lines.length * lh + 34 * k;
  let bx = p ? p[0] + 46 * k : 14 * k, by = p ? p[1] - bh / 2 : 14 * k;
  if (bx + bw > W - 8) bx = p ? p[0] - 46 * k - bw : W - bw - 8; bx = Math.max(8, bx); by = Math.max(8, Math.min(H - bh - 8, by));
  g.fillStyle = 'rgba(12,14,20,0.82)'; g.fillRect(bx, by, bw, bh);
  g.fillStyle = col; g.fillRect(bx, by, 5 * k, bh);
  g.font = `700 ${Math.round(12 * k)}px sans-serif`; g.fillText(`${SEV[iss.sev]?.name ?? iss.sev} · ${iss.kind}${iss.actor ? ' · ' + iss.actor : ''}`, bx + 12 * k, by + 16 * k);
  g.fillStyle = '#f2f4f8'; g.font = `500 ${Math.round(14 * k)}px sans-serif`;
  lines.forEach((l, i) => g.fillText(l, bx + 12 * k, by + 34 * k + i * lh));
  // where in the film
  g.font = `600 ${Math.round(12 * k)}px sans-serif`; const tag = `${iss.shot ?? ''} @ ${iss.at ?? iss.t0 ?? 0}s`;
  const tw = g.measureText(tag).width + 14 * k; g.fillStyle = 'rgba(0,0,0,0.6)'; g.fillRect(W - tw - 8, H - 26 * k, tw, 20 * k); g.fillStyle = '#fff'; g.fillText(tag, W - tw - 1, H - 11 * k);
  g.restore();
}
/** The frame of a finding, drawn from the code, with its marks. → canvas */
export function annotateIssue(P, iss, o = {}) {
  const scale = o.scale ?? 0.5, W = Math.round((P.config.width * scale) / 2) * 2, H = Math.round((P.config.height * scale) / 2) * 2;
  let t = iss.at ?? iss.t0 ?? 0;
  // never show a finding on a frame that is still fading in or already fading out (it would be black)
  const base = P.shots.find((s) => s.id === iss.shot);
  if (base) { const fi = base.post?.fadeIn ?? 0, fo = base.post?.fadeOut ?? 0, lo = Math.min(base.dur, fi + 0.15), hi = Math.max(lo, base.dur - fo - 0.15); if (t < lo || t > hi) { const want = Math.max(iss.t0 ?? 0, lo); t = Math.min(hi, Math.max(lo, want)); } }
  const sh = shotFrom(P, iss.shot, t);
  const run = new ShotRun(sh, P.CAST, { lang: o.lang ?? P.config.langs[0], W, H, fps: P.config.fps, noGrain: true, noSubs: true });
  const cv = run.draw(0);
  const out = createCanvas(W, H), g = out.getContext('2d'); g.drawImage(cv, 0, 0);
  markIssue(g, W, H, iss, run.ctx.cam);
  return out;
}
/** Several findings in one picture (a contact sheet of problems). */
export function issueSheet(P, list, o = {}) {
  const cols = o.cols ?? 3, cell = o.cell ?? 0.3, frames = list.map((i) => { try { return annotateIssue(P, i, { scale: cell, lang: o.lang }); } catch (e) { return null; } });
  const W = Math.round(P.config.width * cell), H = Math.round(P.config.height * cell), rows = Math.ceil(list.length / cols);
  const cv = createCanvas(W * cols, H * rows), g = cv.getContext('2d'); g.fillStyle = '#111'; g.fillRect(0, 0, cv.width, cv.height);
  frames.forEach((f, n) => { if (f) g.drawImage(f, (n % cols) * W, Math.floor(n / cols) * H, W, H); });
  return cv;
}
