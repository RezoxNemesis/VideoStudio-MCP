// Render shots of a code film into one silent clip: node devtests/clip.mjs <dir> <out.mp4> <shot> [<shot>…] [--scale 0.5] [--fps N] [--nomesh]
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { GlobalFonts } from '@napi-rs/canvas';
import { openProject } from '../kit/project.js';
import { renderVideo } from '../engine/shot.js';
import { ENV } from '../engine/env.js';
for (const f of fs.readdirSync(new URL('../assets/fonts', import.meta.url))) { try { GlobalFonts.registerFromPath(new URL('../assets/fonts/' + f, import.meta.url).pathname); } catch { /* licence files */ } }
const a = process.argv.slice(2), opt = (n, d) => { const i = a.indexOf('--' + n); if (i < 0) return d; const v = a[i + 1]; a.splice(i, 2); return v; };
const flag = (n) => { const i = a.indexOf('--' + n); if (i < 0) return false; a.splice(i, 1); return true; };
const scale = +opt('scale', 0.5), fps0 = +opt('fps', 0), noMesh = flag('nomesh');
const [dir, out, ...ids] = a;
const P = await openProject(dir), W = Math.round(P.config.width * scale / 2) * 2, H = Math.round(P.config.height * scale / 2) * 2, fps = fps0 || P.config.fps;
const parts = [], t0 = Date.now();
for (const id of ids) {
  const sh = P.shots.find((q) => q.id === id); if (!sh) throw new Error('no shot ' + id);
  const f = `${out}.${id}.mp4`; const r = await renderVideo(sh, P.CAST, f, { W, H, fps, noMesh, lang: 'en', crf: 15 }); parts.push(f);
  console.log(id, r.frames, 'frames', (r.ms / 1000).toFixed(1), 's');
}
const list = `${out}.txt`; fs.writeFileSync(list, parts.map((f) => `file '${f}'`).join('\n'));
execFileSync(ENV.ffmpeg ?? 'ffmpeg', ['-y', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', list, '-c', 'copy', '-movflags', '+faststart', out]);
for (const f of [...parts, list]) fs.unlinkSync(f);
console.log(`→ ${out} in ${((Date.now() - t0) / 1000).toFixed(1)} s`);
