// node devtests/steps.mjs <film dir> <sequence id> <actor> [bpm] [from] [to] — when a performer's feet come down in a
// solved performance (seconds of the sequence), and, with a tempo, how to lay the dance on the music: the rate to play
// the captured clip at and how much later (or earlier) to start it so the steps fall on the beats.
import { register } from 'node:module';
register('../runner/hooks.mjs', import.meta.url);
const { openProject } = await import('../kit/project.js');
const { loadBake } = await import('../engine/bake.js');
const { stepTimes, beatMatch, beatGrid } = await import('../kit/dance.js');
const [dir, seq, actor, bpm, from, to] = process.argv.slice(2);
if (!dir || !seq || !actor) { console.error('node devtests/steps.mjs <film dir> <sequence id> <actor> [bpm] [from] [to]'); process.exit(1); }
const P = await openProject(dir), S = P.FILM.find((s) => s.id === seq);
if (!S) { console.error(`no sequence ${seq} (${P.FILM.map((s) => s.id).join(' ')})`); process.exit(1); }
const B = loadBake(S.bakeName ?? seq), A = B?.actors?.[actor] ?? B?.actors?.find?.((a) => a.id === actor);
if (!A) { console.error(`no solved performance of ${actor} in ${seq}: build the film once first`); process.exit(1); }
const all = stepTimes(A.contact, B.fps), steps = all.filter((t) => t >= Number(from ?? 0) && t <= Number(to ?? 1e9));
console.log(`${actor} in ${seq}: ${steps.length} steps`);
console.log(steps.map((t) => t.toFixed(2)).join(' '));
if (bpm) {
  const G = beatGrid(Number(bpm), P.config.fps), M = beatMatch(steps.map((t) => t - steps[0]), Number(bpm));
  console.log(`tempo ${bpm}: a beat is ${G.frames} frames${G.whole ? '' : ` (not whole frames: ${G.suggest} would be)`}`);
  console.log(`play the clip at rate × ${M.rate.toFixed(3)} (steps on ${M.per === 1 ? 'the beats' : M.per === 0.5 ? 'the half beats' : 'every other beat'}); then the steps are ${(M.error * 1000).toFixed(0)} ms off the beat on average (pulse ${(M.sure * 100).toFixed(0)}% sure${M.sure < 0.5 ? ": this dance has no steady pulse, matching it will not show" : ""})`);
  console.log(`first step now at ${steps[0].toFixed(2)} s: start the clip so that it falls on a beat (a multiple of ${G.beat.toFixed(3)} s after the music starts)`);
}
