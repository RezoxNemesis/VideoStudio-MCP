// node devtests/review.mjs <film dir> [bench] [seqs] — a whole Sensei review from the command line
import { register } from 'node:module';
register('../runner/hooks.mjs', import.meta.url);
const { openProject } = await import('../kit/project.js');
const { reviewShots, finish } = await import('../sensei/index.js');
const [dir, bench = 'youtube', seqs = ''] = process.argv.slice(2);
const t0 = Date.now();
const P = await openProject(dir);
const ids = P.shots.filter((s) => !s.off && (!seqs || seqs.split(',').some((x) => s.id.startsWith(x)))).map((s) => s.id);
const part = await reviewShots(P, ids, { bench, draw: false }, (e) => { if (e.done % 25 === 0) process.stderr.write(`\r${e.done}/${e.of}  `); });
const r = await finish(P, [part], { bench, ms: Date.now() - t0, images: +(process.env.IMG ?? 6), scope: seqs ? 'part' : 'film' });
console.log(JSON.stringify(r.digest, null, 1).slice(0, 6000));
console.log(r.files);
