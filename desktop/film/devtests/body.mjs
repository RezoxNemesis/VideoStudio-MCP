// Look at a real body: node devtests/body.mjs <dir> <bake> <out.jpg> <actor>@<t>[,<t>…] … [--yaw deg] [--dist m] [--h m] [--size px] [--hands grip]
import fs from 'node:fs';
import { createCanvas } from '@napi-rs/canvas';
import { openProject } from '../kit/project.js';
import { loadBake, poseAt } from '../engine/bake.js';
import { makeCam } from '../engine/camera.js';
import { bodyOf, poseBody, normalsOf, CelPainter, rgb255 } from '../engine/body.js';
const a = process.argv.slice(2), opt = (n, d) => { const i = a.indexOf('--' + n); if (i < 0) return d; const v = a[i + 1]; a.splice(i, 2); return v; };
const yaw = +opt('yaw', 25) * Math.PI / 180, dist = +opt('dist', 3.2), ch = +opt('h', 1.2), S = +opt('size', 540), hands = opt('hands', 'relax'), aim = +opt('aim', 1.0), ss = +opt('ss', 2), fov = +opt('fov', 35);
const focus = opt('at', null);
const nohead = a.includes('--nohead'); if (nohead) a.splice(a.indexOf('--nohead'), 1);
const [dir, bk, out, ...specs] = a;
await openProject(dir);
const bake = loadBake(bk), jobs = specs.flatMap((s) => { const [id, ts] = s.split('@'); return ts.split(',').map((t) => ({ id, t: +t })); });
const cols = Math.min(jobs.length, +opt('cols', 4)), rows = Math.ceil(jobs.length / cols), W = S, H = Math.round(S * 1.25);
const cv = createCanvas(cols * W, rows * H), g = cv.getContext('2d'); g.fillStyle = '#b9c4d6'; g.fillRect(0, 0, cv.width, cv.height);
const PAINT = (B, part) => {
  const c = B.colors[part.name], m = part.material;
  const base = m === 'skin' ? rgb255(B.look.skin ?? [0.76, 0.58, 0.46]) : m === 'hair' ? rgb255(B.look.hair ?? [0.05, 0.04, 0.04]) : c ? rgb255(c) : [120, 120, 130];
  const sh = base.map((v, i) => v * (m === 'skin' ? [0.72, 0.56, 0.6][i] : [0.56, 0.54, 0.66][i]));
  const LAYER = { skin: 0, trousers: 1, boots: 2, shoes: 2, tunic: 3, wraps: 4, skirt: 4, coat: 5, cuirass: 6, vambrace: 6, greave: 6, haircap: 2, hair: 3, beard: 2 };
  if (m === 'eye') return { fill: [245, 245, 245], shade: [225, 225, 235], bias: 0.001, line: false };
  if (m === 'lash') return { fill: [30, 22, 26], shade: [30, 22, 26], bias: 0.003, flat: true, two: true };
  return { fill: base, shade: sh, bias: (LAYER[part.name] ?? 1) * 0.0015, two: m === 'cloth' || m === 'hair', ...(m === 'metal' ? { hi: base.map((v) => Math.min(255, v * 1.5 + 70)), hiAt: 0.8 } : {}) };
};
const t0 = Date.now();
jobs.forEach((jb, i) => {
  const B = bodyOf(bake, jb.id); if (!B) throw new Error('no body for ' + jb.id);
  const pose = poseAt(bake, jb.id, jb.t), root = pose.j.root, fw = Math.atan2(pose.j['foot.R'][0] - pose.j['foot.L'][0], pose.j['foot.R'][2] - pose.j['foot.L'][2]) - Math.PI / 2;
  const tg = focus ? pose.j[focus] : [root[0], aim, root[2]];
  const ang = fw + yaw, cam = makeCam({ pos: [tg[0] - Math.sin(ang) * dist, focus ? tg[1] + ch : ch, tg[2] - Math.cos(ang) * dist], target: tg, fov }, W, H);
  poseBody(B, jb.t, null, { L: hands, R: hands });
  const cp = new CelPainter(cam, { ss, light: [0.45, 0.75, 0.5].map((v, k, arr) => v / Math.hypot(...arr)), lw: 3 * S / 540, lwIn: 1.7 * S / 540 });
  for (const p of B.parts) { if (['eyes', 'teeth', 'tongue', 'lashes', 'haircap', 'hair', 'beard'].includes(p.name) && nohead) continue; if (p.name === 'hair' || p.name === 'teeth' || p.name === 'tongue') continue; cp.add(p.out, nohead ? p.idxNoHead : p.idx, PAINT(B, p), normalsOf(p.out, p.idx, p.nrm)); }
  const r = cp.finish();
  const x = (i % cols) * W, y = Math.floor(i / cols) * H;
  if (r) g.drawImage(r.canvas, x + r.x, y + r.y);
  g.fillStyle = '#000a'; g.fillRect(x, y, 110, 17); g.fillStyle = '#fff'; g.font = '12px sans-serif'; g.fillText(`${jb.id} ${jb.t.toFixed(2)}s`, x + 4, y + 13);
});
fs.writeFileSync(out, cv.toBuffer('image/jpeg', 90));
console.log(`${jobs.length} bodies, ${Date.now() - t0} ms → ${out}`);
