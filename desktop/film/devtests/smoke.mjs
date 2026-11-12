// Draw three frames of every shot small, to find shots that throw: node devtests/smoke.mjs <dir> [lang]
import fs from 'node:fs';
import { GlobalFonts } from '@napi-rs/canvas';
import { openProject, shotFrom } from '../kit/project.js';
import { ShotRun } from '../engine/shot.js';
for (const f of fs.readdirSync(new URL('../assets/fonts', import.meta.url))) { try { GlobalFonts.registerFromPath(new URL('../assets/fonts/' + f, import.meta.url).pathname); } catch { /* */ } }
const [dir, lang = 'en'] = process.argv.slice(2);
const P = await openProject(dir); let bad = 0; const t0 = Date.now();
for (const sh of P.shots) {
  for (const u of [0.02, 0.5, 0.97]) {
    const t = Math.min(sh.dur * u, sh.dur - 1 / P.config.fps);
    try { new ShotRun(shotFrom(P, sh.id, t), P.CAST, { lang, W: 192, H: 108, fps: P.config.fps, noGrain: true }).draw(0); }
    catch (e) { bad++; console.log('FAIL', sh.id, t.toFixed(2), String(e?.stack ?? e).split('\n').slice(0, 3).join(' | ')); break; }
  }
}
console.log(`${P.shots.length} shots, ${bad} failing, ${((Date.now() - t0) / 1000).toFixed(1)} s`);
