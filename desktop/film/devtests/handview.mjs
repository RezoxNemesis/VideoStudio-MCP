// Look at a hand in its own frame: node devtests/handview.mjs <film dir> <bake> <out.jpg> <actor> <t> <side L|R> <hands…>
// --hilt draws a sword's handle where the Stage puts it in the hand (to check that a grip closes round it).
// One row per hand pose; columns: back of the hand, thumb side, palm, fingertips (looking back along the hand).
import fs from 'node:fs';
import { createCanvas } from '@napi-rs/canvas';
import { openProject } from '../kit/project.js';
import { loadBake } from '../engine/bake.js';
import { makeCam } from '../engine/camera.js';
import { bodyOf, poseBody, CelPainter, rgb255, dirOnBone, onBone, HANDS } from '../engine/body.js';
const argv = process.argv.slice(2), hilt = argv.includes('--hilt'); if (hilt) argv.splice(argv.indexOf('--hilt'), 1);
const [dir, bk, out, id, t, side, ...names] = argv;
await openProject(dir);
const bake = loadBake(bk), B = bodyOf(bake, id), S = 360, cols = 4;
const cv = createCanvas(cols * S, names.length * S), g = cv.getContext('2d'); g.fillStyle = '#b9c4d6'; g.fillRect(0, 0, cv.width, cv.height);
const PAINT = (part) => { const c = B.colors[part.name], m = part.material; const base = m === 'skin' ? rgb255(B.look.skin ?? [0.76, 0.58, 0.46]) : c ? rgb255(c) : [120, 120, 130]; return { fill: base, shade: base.map((v, i) => v * (m === 'skin' ? [0.72, 0.56, 0.6][i] : [0.56, 0.54, 0.66][i])), bias: m === 'skin' ? 0 : 0.003, two: m === 'cloth' }; };
const norm = (a) => { const l = Math.hypot(...a) || 1; return a.map((v) => v / l); }, add = (a, b) => a.map((v, i) => v + b[i]), mul = (a, k) => a.map((v) => v * k);
names.forEach((name, r) => {
  let hd = name; if (name.startsWith('{')) hd = JSON.parse(name); else if (name.includes('+')) { const [a, o] = name.split('+'); hd = { ...HANDS[a], ...JSON.parse(o) }; }
  poseBody(B, +t, null, { L: hd, R: hd });
  const F = B.fingers[side], w = `wrist.${side}`, al = norm(dirOnBone(B, w, F.along)), pm = norm(dirOnBone(B, w, F.palm)), ac = norm(dirOnBone(B, w, F.across));
  const k3 = B.bi[`finger3-1.${side}`], c = onBone(B, k3, [B.head[k3 * 3], B.head[k3 * 3 + 1], B.head[k3 * 3 + 2]]), tg = add(c, mul(al, -0.02));
  const views = [mul(pm, -1), mul(ac, -1), pm, al];
  views.forEach((v, i) => {
    const up = i === 3 ? mul(pm, -1) : mul(al, -1), pos = add(tg, mul(norm(add(v, mul(up, 0.12))), 0.42));
    const c0 = makeCam({ pos, target: tg, fov: 30 }, S, S), dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    const cam = makeCam({ pos, target: tg, fov: 30, roll: Math.atan2(-dot(up, c0.R), dot(up, c0.U)) * 180 / Math.PI }, S, S); // the hand upright in the picture: fingers down
    const cp = new CelPainter(cam, { ss: 2, light: norm(add(mul(v, 0.8), add(mul(up, 0.5), mul(i === 1 ? pm : ac, 0.35)))), lw: 2.2, lwIn: 1.2 });
    for (const p of B.parts) { if (p.material !== 'skin') continue; cp.add(p.out, p.idxLimb, PAINT(p)); } // the arm and hand alone, so the palm can be seen
    if (hilt) { // an oval handle across the palm: 85 mm along the hand from the wrist, 30 mm in front of the palm
      const kk = B.height / 1.8, wp = onBone(B, w, [B.head[B.bi[w] * 3], B.head[B.bi[w] * 3 + 1], B.head[B.bi[w] * 3 + 2]]), dt = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
      const ul = norm(ac.map((x, k) => x - al[k] * dt(ac, al))), c0 = add(add(wp, mul(al, 0.085 * kk)), mul(pm, 0.03 * kk)), N = 14, ps = [], ix = [];
      for (const e of [-0.09, 0.12]) for (let q = 0; q < N; q++) { const a = q / N * 2 * Math.PI; ps.push(...add(add(add(c0, mul(ul, -e * kk)), mul(al, Math.cos(a) * 0.0155 * kk)), mul(pm, Math.sin(a) * 0.012 * kk))); }
      for (let q = 0; q < N; q++) { const q1 = (q + 1) % N; ix.push(q, q1, N + q, q1, N + q1, N + q); }
      cp.add(Float32Array.from(ps), Uint32Array.from(ix), { fill: [70, 52, 44], shade: [44, 32, 30], two: true, bias: 0 });
    }
    const res = cp.finish(); if (res) g.drawImage(res.canvas, i * S + res.x, r * S + res.y);
    g.strokeStyle = '#0003'; g.strokeRect(i * S, r * S, S, S);
  });
  g.fillStyle = '#000a'; g.fillRect(0, r * S, 150, 17); g.fillStyle = '#fff'; g.font = '12px sans-serif'; g.fillText(String(name).slice(0, 24), 4, r * S + 13);
});
fs.writeFileSync(out, cv.toBuffer('image/jpeg', 90)); console.log('→', out);
