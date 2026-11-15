// node devtests/where.mjs <film dir> shot@t actor[:joint,joint…] [actor…] — where bodies are at a moment of a shot (after
// everything that places them: a rider is in his saddle), for setting cameras by numbers. Joints: head (default),
// root, spine02, wrist.R … or `all` for the list of names. Prints metres [x, y, z].
import { register } from 'node:module';
register('../runner/hooks.mjs', import.meta.url);
const { openProject, shotFrom } = await import('../kit/project.js');
const { ShotRun } = await import('../engine/shot.js');
const args = process.argv.slice(2), dir = args[0], P = await openProject(dir);
const W = Math.round(P.config.width * 0.06) * 2, H = Math.round(P.config.height * 0.06) * 2;
let run = null;
for (const a of args.slice(1)) {
  if (a.includes('@')) { const [id, t] = a.split('@'); run = new ShotRun(shotFrom(P, id, +t), P.CAST, { lang: P.config.langs[0], W, H, fps: P.config.fps, noGrain: true }); run.draw(0); console.log(`${a}  cam ${JSON.stringify(run.ctx.cam?.pos?.map((v) => +v.toFixed(2)))}`); continue; }
  if (!run) { console.error('name a shot@t first'); process.exit(1); }
  const [id, js] = a.split(':'), pose = run.ctx.poses?.[id];
  if (!pose) { console.log(`  ${id}: not in this shot (${Object.keys(run.ctx.poses ?? {}).join(' ')})`); continue; }
  if (js === 'all') { console.log(`  ${id}: ${Object.keys(pose.j).join(' ')}`); continue; }
  for (const j of (js ?? 'head').split(',')) { const p = pose.j[j]; console.log(`  ${id}.${j}: ${p ? '[' + p.map((v) => v.toFixed(2)).join(', ') + ']' : 'no such joint'}`); }
}
