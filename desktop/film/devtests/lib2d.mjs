// The 2D library inside films: cut-out poses, a walk, a multiplane background under a moving camera, stickers and pops.
// node devtests/lib2d.mjs [outdir]
import fs from 'node:fs';
import path from 'node:path';
import { createCanvas } from '@napi-rs/canvas';
import { makeCam } from '../engine/camera.js';
import { library2d, drawCutout, walkPose, CUTOUT_POSES, parallax, cutoutActor, sticker, pop, drawAsset } from '../kit/library2d.js';
const OUT = process.argv[2] ?? '/tmp/l2t'; fs.mkdirSync(OUT, { recursive: true });
const lib = library2d(); console.log(lib.count, 'assets');
const chars = lib.assets.filter((a) => a.cat === 'characters');
const save = (cv, n) => { fs.writeFileSync(path.join(OUT, n), cv.toBuffer('image/jpeg', 90)); console.log('wrote', n); };
// 1. poses
{
  const names = Object.keys(CUTOUT_POSES), ids = [chars[0].id, chars.find((c) => c.sub === 'chibi').id], W = 220 * names.length, H = 900, cv = createCanvas(W, H), g = cv.getContext('2d');
  g.fillStyle = '#e9e4dc'; g.fillRect(0, 0, W, H); g.fillStyle = '#333'; g.font = '20px sans-serif';
  const t0 = Date.now();
  names.forEach((n, i) => ids.forEach((id, j) => { drawCutout(g, id, 110 + i * 220, 420 + j * 450, j ? 300 : 380, CUTOUT_POSES[n]); if (!j) g.fillText(n, 70 + i * 220, 30); }));
  console.log('poses', Date.now() - t0, 'ms'); save(cv, 'poses.jpg');
}
// 2. a walk, eight phases
{
  const W = 8 * 230, H = 520, cv = createCanvas(W, H), g = cv.getContext('2d'); g.fillStyle = '#e9e4dc'; g.fillRect(0, 0, W, H);
  for (let i = 0; i < 8; i++) drawCutout(g, chars[3].id, 115 + i * 230, 480, 420, walkPose(i / 8 / 0.9));
  save(cv, 'walk.jpg');
}
// 3. a multiplane background under a truck and a push in, with a character standing on the near ground
{
  const bg = 'bg_village_dusk', sc = parallax(bg), st = sc.stand(0.42, 0.9, 0.62);
  const hero = cutoutActor(chars[1].id, { at: st.at, height: st.height, pose: (t) => walkPose(t) });
  const fx = [sticker('fx_emo_sparkle', { at: [st.at[0] + 1.5, st.at[1] + 2.5, st.at[2]], size: 0.8 }), pop('fx_impact_comic_white', 0.0, { x: 0.8, y: 0.3, h: 0.25, dur: 1 })].filter(Boolean);
  const W = 1920, H = 1080, sheet = createCanvas(W, H * 3 / 2), sg = sheet.getContext('2d');
  const views = [{ pos: [0, 1.6, -8], target: [0, 1.6, 0], fov: 38 }, { pos: [3.5, 1.6, -8], target: [3.5, 1.6, 0], fov: 38 }, { pos: [0, 1.6, -2], target: [0, 1.6, 6], fov: 38 }];
  views.forEach((v, i) => {
    const cv = createCanvas(W, H), g = cv.getContext('2d'), cam = makeCam(v, W, H), t0 = Date.now();
    sc.draw(g, cam, 0.3 + i * 0.2, [hero]);
    for (const f of fx) { try { f(g, cam, sc, 0.3 + i * 0.2); } catch (e) { console.log('fx', e.message); } }
    console.log('view', i, Date.now() - t0, 'ms');
    sg.drawImage(cv, (i % 2) * W / 2, Math.floor(i / 2) * H / 2, W / 2, H / 2);
    if (i === 0) save(cv, 'set_ref.jpg');
  });
  // 4. assets of every kind over a plain card
  const g = sg, ox = W / 2, oy = H / 2; g.fillStyle = '#f4efe6'; g.fillRect(ox, oy, W / 2, H / 2);
  const pick = ['props', 'fx', 'faces', 'hands', 'ui', 'pixel', 'stick', 'textures'].map((c) => lib.assets.filter((a) => a.cat === c));
  pick.forEach((list, i) => list.slice(0, 4).forEach((a, j) => { try { drawAsset(g, a.id, ox + 70 + i * 116, oy + 80 + j * 125, { h: 100 * Math.min(1, a.h / Math.max(a.w, a.h)) / 0.5 * 0.5 + 0 }); } catch (e) { console.log(a.id, e.message); } }));
  save(sheet, 'set_moves.jpg');
}
