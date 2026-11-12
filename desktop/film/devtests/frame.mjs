// node devtests/frame.mjs <film dir> <out.jpg> shot@t [shot@t …] [--scale 1] [--crop x,y,w,h] — frames drawn from code, side by side (2 per row)
import { register } from 'node:module';
register('../runner/hooks.mjs', import.meta.url);
const { openProject, shotFrom } = await import('../kit/project.js');
const { ShotRun } = await import('../engine/shot.js');
const { createCanvas } = await import('@napi-rs/canvas');
const fs = await import('node:fs');
const args = process.argv.slice(2), dir = args[0], out = args[1];
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const scale = +opt('--scale', 1), crop = opt('--crop', null)?.split(',').map(Number), cols = +opt('--cols', 2);
const specs = args.slice(2).filter((a, i, A) => a.includes('@') && !A[i - 1]?.startsWith('--'));
const P = await openProject(dir);
const W = Math.round(P.config.width * scale / 2) * 2, H = Math.round(P.config.height * scale / 2) * 2;
const cw = crop ? crop[2] : W, ch = crop ? crop[3] : H, rows = Math.ceil(specs.length / cols);
const outCv = createCanvas(cw * Math.min(cols, specs.length), ch * rows), g = outCv.getContext('2d');
let n = 0;
for (const s of specs) {
  const [id, t] = s.split('@'); const t0 = Date.now();
  const run = new ShotRun(shotFrom(P, id, +t), P.CAST, { lang: P.config.langs[0], W, H, fps: P.config.fps, noGrain: true });
  const cv = run.draw(0);
  if (crop) g.drawImage(cv, crop[0], crop[1], cw, ch, (n % cols) * cw, Math.floor(n / cols) * ch, cw, ch); else g.drawImage(cv, (n % cols) * W, Math.floor(n / cols) * H);
  console.log(s, Date.now() - t0, 'ms'); n++;
}
fs.writeFileSync(out, outCv.toBuffer('image/jpeg', 88));
