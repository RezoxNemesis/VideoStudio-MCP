// node devtests/sensei.mjs <film dir> [shot ids…] — run Sensei's shot checks and print a digest
import { register } from 'node:module';
register('../runner/hooks.mjs', import.meta.url);
const { openProject } = await import('../kit/project.js');
const { collect } = await import('../sensei/collect.js');
const { motionChecks } = await import('../sensei/motion.js');
const { cameraChecks, cutChecks } = await import('../sensei/camera.js');
const { bench } = await import('../sensei/rules.js');
const [dir, ...ids] = process.argv.slice(2);
const P = await openProject(dir), B = bench('youtube');
const shots = P.shots.filter((s) => !s.off && (!ids.length || ids.some((x) => s.id.startsWith(x))));
const all = [], sums = []; const t0 = Date.now();
for (const sh of shots) {
  const t = Date.now();
  try {
    const D = collect(P, sh);
    const m = motionChecks(D, B), c = cameraChecks(D, B);
    all.push(...m, ...c.issues); sums.push(c.summary);
    if (process.env.V) console.log(sh.id, (Date.now() - t) + 'ms', m.length + c.issues.length, c.summary.cls, c.summary.subject);
  } catch (e) { console.log('ERR', sh.id, String(e.stack).split('\n').slice(0, 3).join(' | ')); }
}
all.push(...cutChecks(sums, B));
const by = {}; for (const i of all) { const k = `${i.sev} ${i.kind}`; by[k] = (by[k] ?? 0) + 1; }
console.log(`${shots.length} shots in ${((Date.now() - t0) / 1000).toFixed(1)} s, ${all.length} findings`);
console.log(Object.entries(by).sort().map(([k, v]) => `${k}: ${v}`).join('\n'));
if (process.env.SHOW) for (const i of all.filter((x) => x.kind === process.env.SHOW).slice(0, 12)) console.log(i.shot, i.t0, '-', i.t1, i.sev, i.msg);
