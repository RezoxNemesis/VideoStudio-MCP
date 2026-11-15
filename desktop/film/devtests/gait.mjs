// node devtests/gait.mjs <film dir> <sequence id> <actor> [from] [to] — a walk or a run by numbers, from the solved
// performance: steps a minute, step length, time in the air, lean, knee lift, arms against legs, how far apart the hands
// go — and what a coach would say is wrong. (A real runner: about 180 steps a minute, in the air between every two
// steps, the trunk 8–10° forward, the knee driven toward hip height, the hands close to the ribs.)
import { register } from 'node:module';
register('../runner/hooks.mjs', import.meta.url);
const { openProject } = await import('../kit/project.js');
const { loadBake, poseAt } = await import('../engine/bake.js');
const { gaitReport } = await import('../kit/gait.js');
const [dir, seq, actor, from, to] = process.argv.slice(2);
if (!dir || !seq || !actor) { console.error('node devtests/gait.mjs <film dir> <sequence id> <actor> [from] [to]'); process.exit(1); }
const P = await openProject(dir), S = P.FILM.find((s) => s.id === seq);
if (!S) { console.error(`no sequence ${seq} (${P.FILM.map((s) => s.id).join(' ')})`); process.exit(1); }
const B = loadBake(S.bakeName ?? seq), A = B?.actors?.[actor] ?? B?.actors?.find?.((a) => a.id === actor);
if (!A) { console.error(`no solved performance of ${actor} in ${seq}: build the film once first`); process.exit(1); }
const fps = 60, t0 = Number(from ?? 0), t1 = Math.min(Number(to ?? 1e9), (A.contact.length - 1) / B.fps), samples = [];
for (let t = t0; t <= t1 + 1e-9; t += 1 / fps) {
  const j = poseAt(B, actor, t).j;
  samples.push({ root: j.root, neck: j.neck01 ?? j.head, footL: j['foot.L'], footR: j['foot.R'], toeL: j['toe2-1.L'], toeR: j['toe2-1.R'], kneeL: j['lowerleg01.L'], kneeR: j['lowerleg01.R'], wristL: j['wrist.L'], wristR: j['wrist.R'] });
}
const R = gaitReport(samples, fps);
// what the solver itself believes about the ground (the clip's own contact marks)
let marked = 0; for (let i = Math.round(t0 * B.fps); i <= Math.round(t1 * B.fps); i++) if (A.contact[i]) marked++;
console.log(`${actor} in ${seq}, ${t0.toFixed(2)}–${t1.toFixed(2)} s: a ${R.kind} at ${R.speed} m/s`);
console.log(`  ${Math.round(R.cadence)} steps a minute (about ${R.usual} is usual for these legs), ${R.stepLength} m a step`);
console.log(`  in the air ${Math.round(R.flight * 100)} % of the time; the hips go up and down ${Math.round(R.bounce * 100)} cm`);
console.log(`  the trunk leans ${R.lean}° forward; the knee comes up to ${Math.round(R.kneeLift * 100)} % of hip height`);
console.log(`  arms against legs ${R.armLeg} (1 = each arm with the opposite leg); the hands are ${R.wrists[0]} to ${R.wrists[1]} m apart`);
console.log(`  feet come down at ${R.steps.map((t) => (t + t0).toFixed(2)).join(' ')}`);
console.log(`  the solver marks a foot on the ground in ${Math.round((100 * marked) / Math.max(1, Math.round((t1 - t0) * B.fps) + 1))} % of the frames`);
console.log(R.notes.length ? R.notes.map((n) => `  ✗ ${n}`).join('\n') : '  ✓ nothing a coach would remark on');
