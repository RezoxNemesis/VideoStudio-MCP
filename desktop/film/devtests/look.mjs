// Look at shots of a code film: node devtests/look.mjs <dir> <out.jpg> <shot>@<t>[,<t>…] [<shot>@…] [--scale 0.5] [--crop x,y,w,h] [--cols 3] [--lang en] [--run]
// Times are seconds into the shot. --run draws the shot in order up to each time (cloth, hair, particles as in the film);
// without it every frame is drawn on its own (instant). crop is in 0..1 of the frame and is shown enlarged.
import fs from 'node:fs';
import { createCanvas, GlobalFonts } from '@napi-rs/canvas';
import { openProject, shotFrom } from '../kit/project.js';
import { ShotRun } from '../engine/shot.js';
for (const f of fs.readdirSync(new URL('../assets/fonts', import.meta.url))) { try { GlobalFonts.registerFromPath(new URL('../assets/fonts/' + f, import.meta.url).pathname); } catch { /* licence files */ } }
const a = process.argv.slice(2), opt = (n, d) => { const i = a.indexOf('--' + n); if (i < 0) return d; const v = a[i + 1]; a.splice(i, 2); return v; };
const flag = (n) => { const i = a.indexOf('--' + n); if (i < 0) return false; a.splice(i, 1); return true; };
const lookArg = opt('look', null), scale = +opt('scale', 0.5), crop = opt('crop', null)?.split(',').map(Number), cols0 = +opt('cols', 0), lang = opt('lang', 'en'), run = flag('run'), noMesh = flag('nomesh'), fps = +opt('fps', 0), zm = +opt('zoom', 1);
const [dir, out, ...specs] = a;
const P = await openProject(dir), W = Math.round(P.config.width * scale / 2) * 2, H = Math.round(P.config.height * scale / 2) * 2, FPS = fps || P.config.fps;
const jobs = specs.flatMap((s) => { const [id, ts] = s.split('@'); const sh = P.shots.find((q) => q.id === id); if (!sh) throw new Error('no shot ' + id); return (ts ? ts.split(',').map(Number) : [sh.dur / 2]).map((t) => ({ id, t: Math.min(t, sh.dur - 1 / FPS), sh })); });
const cw = crop ? Math.round(W * crop[2]) : W, ch = crop ? Math.round(H * crop[3]) : H, zoom = zm;
const tw = Math.round(cw * zoom), th = Math.round(ch * zoom), cols = cols0 || Math.min(jobs.length, tw > 800 ? 2 : 3), rows = Math.ceil(jobs.length / cols);
const cv = createCanvas(cols * tw, rows * th), g = cv.getContext('2d'); let runner = null, runId = '', at = -1;
const t0 = Date.now();
jobs.forEach((j, i) => {
  let im;
  if (run) {
    if (runId !== j.id || j.t < at) { runner = new ShotRun(j.sh, P.CAST, { lang, W, H, fps: FPS, noGrain: true, noMesh, look: lookArg ?? undefined }); runId = j.id; at = -1; }
    const n = Math.round(j.t * FPS); for (let f = Math.max(0, Math.round(at * FPS) + 1); f <= n; f++) im = runner.draw(f / FPS); at = j.t;
  } else im = new ShotRun(shotFrom(P, j.id, j.t), P.CAST, { lang, W, H, fps: FPS, noGrain: true, noMesh, look: lookArg ?? undefined }).draw(0);
  const x = (i % cols) * tw, y = Math.floor(i / cols) * th;
  if (crop) g.drawImage(im, Math.round(W * crop[0]), Math.round(H * crop[1]), cw, ch, x, y, tw, th); else g.drawImage(im, x, y, tw, th);
  g.fillStyle = '#000a'; g.fillRect(x, y, 118, 17); g.fillStyle = '#fff'; g.font = '12px Inter'; g.fillText(`${lookArg ?? ''} ${j.id} ${j.t.toFixed(2)}s`, x + 4, y + 13);
});
fs.writeFileSync(out, cv.toBuffer('image/jpeg', 86));
console.log(`${jobs.length} frames, ${cols * tw}x${rows * th}, ${Date.now() - t0} ms → ${out}`);
