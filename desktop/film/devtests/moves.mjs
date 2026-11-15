// The code collection: node devtests/moves.mjs [words]        (exit 1 when a rule is broken; with words: prints what matches)
// One place where every movement that has been worked out is kept ready to use: its name, what it is for, the rules
// that make it read, the captured clips with their moments, the kit functions and tools it needs, the code, and how to
// check it. A film (or Claude) asks for a move by name instead of working it out again.
import fs from 'node:fs';
import * as KIT from '../kit/index.js';
import { MOVES, findMoves, moveText } from '../kit/moves.js';

const words = process.argv.slice(2).join(' ');
if (words) { for (const m of findMoves(words)) console.log(moveText(m) + '\n'); process.exit(0); }

const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${JSON.stringify(got)}`); };

ok(Array.isArray(MOVES) && MOVES.length >= 14, 'the collection holds the moves worked out so far', MOVES.length);
const ids = MOVES.map((m) => m.id);
ok(new Set(ids).size === ids.length && ids.every((x) => /^[a-z][a-z0-9-]*$/.test(x)), 'every move has a name of its own (lower case, hyphens)', ids);
for (const id of ['run', 'sprint', 'brake', 'pick-up', 'carry', 'sit-alive', 'fall', 'kneel', 'look', 'caught-looking', 'wait', 'duel-ending', 'dance-on-beat', 'solar-system'])
  ok(ids.includes(id), `it has "${id}"`, ids.includes(id));
for (const m of MOVES) {
  ok(typeof m.name === 'string' && m.name.length > 3 && typeof m.use === 'string' && m.use.length > 20, `${m.id}: a name and what it is for`, [m.name, m.use?.length]);
  ok(Array.isArray(m.rules) && m.rules.length >= 2 && m.rules.every((r) => r.length > 15), `${m.id}: the rules that make it read (at least two)`, m.rules?.length);
  ok(typeof m.code === 'string' && m.code.trim().length > 30, `${m.id}: the code`, m.code?.length);
  ok(Array.isArray(m.check) && m.check.length >= 1, `${m.id}: how to check it (a number or a strip, not an impression)`, m.check?.length);
  ok((m.clips?.length ?? 0) + (m.needs?.length ?? 0) > 0, `${m.id}: the clips or the kit functions it is made of`, [m.clips?.length, m.needs?.length]);
  for (const fn of m.needs ?? []) ok(fn in KIT || /^(stage|code|sensei)\./.test(fn) || /\.mjs$/.test(fn), `${m.id}: needs ${fn}, which exists (a kit function, a command or a tool)`, fn);
  for (const fn of (m.needs ?? []).filter((x) => /\.mjs$/.test(x))) ok(fs.existsSync(new URL(`./${fn}`, import.meta.url)), `${m.id}: the tool ${fn} is in devtests`, fn);
  for (const c of m.clips ?? []) ok(m.code.includes(c.clip), `${m.id}: its code uses the clip ${c.clip} it lists`, c.clip);
  for (const fn of (m.needs ?? []).filter((x) => x in KIT)) ok(m.code.includes(fn), `${m.id}: its code uses ${fn}`, fn);
}
// the captured clips it names exist, and their moments are inside them
const A = [process.env.VS_STAGE_ASSETS_DIR, '../stage-assets', '/home/claude/ws/squashfs-root/resources/stage-assets'].find((d) => d && fs.existsSync(`${d}/motion/index.json`));
if (A) {
  const idx = JSON.parse(fs.readFileSync(`${A}/motion/index.json`, 'utf8')).clips;
  for (const m of MOVES) for (const c of m.clips ?? []) {
    ok(!!idx[c.clip], `${m.id}: the clip ${c.clip} is in the motion library`, c.clip);
    if (idx[c.clip]) ok((c.in ?? 0) >= 0 && (c.out ?? idx[c.clip].dur) <= idx[c.clip].dur + 1e-6 && (c.in ?? 0) < (c.out ?? idx[c.clip].dur), `${m.id}: ${c.clip} from ${c.in} to ${c.out} is inside the clip (${idx[c.clip].dur} s)`, [c.in, c.out]);
    // (the first frame of a capture is its T-pose: nothing starts there)
    ok((c.in ?? 0.1) >= 0.05, `${m.id}: ${c.clip} does not start on the capture's T-pose frame`, c.in);
  }
}
// finding
ok(findMoves().length === MOVES.length, 'asked for nothing, it gives everything', findMoves().length);
ok(findMoves('run').some((m) => m.id === 'run') && findMoves('run').some((m) => m.id === 'sprint'), 'asked for "run" it gives the runs', findMoves('run').map((m) => m.id));
ok(findMoves('pick up a bag')[0]?.id === 'pick-up', 'asked in plain words ("pick up a bag") the best match comes first', findMoves('pick up a bag').map((m) => m.id));
ok(findMoves('shy glance')[0]?.id === 'caught-looking' || findMoves('shy glance')[0]?.id === 'look', 'a shy glance finds the looks', findMoves('shy glance').map((m) => m.id));
ok(findMoves('zzzz').length === 0, 'and nothing when nothing fits', findMoves('zzzz').length);
const txt = moveText(MOVES.find((m) => m.id === 'pick-up'));
ok(txt.includes('RULES') && txt.includes('CODE') && txt.includes('CHECK') && txt.includes('143_10'), 'a move reads as one page: use, rules, clips, code, check', txt.slice(0, 80));

console.log(`moves: ${checks} rules checked, ${fails.length} broken (${MOVES.length} moves)`);
for (const f of fails) console.log('  ✗ ' + f);
process.exit(fails.length ? 1 : 0);
