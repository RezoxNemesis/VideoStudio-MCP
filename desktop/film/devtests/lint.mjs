// Advice on a design before it is drawn: node devtests/lint.mjs        (exit 1 when a rule is broken)
// A saturated cloth whose shade is far darker than its colour reads, on a painted look, as a STAIN down the chest and
// back (the stunt lab's red shirt, 16 Nov 2026): a cloth's shade is its own colour about a fifth darker.
import { designAdvice } from '../kit/design.js';
const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${JSON.stringify(got)}`); };
const red = designAdvice({ name: 'Athlete', top: { color: '#e2553a', shade: '#8a2f22' }, bottom: { color: '#2b303c', shade: '#15171d' } });
ok(red.length === 1 && /top/i.test(red[0]) && /Athlete/.test(red[0]) && /#8a2f22/i.test(red[0]), 'a red shirt with a near-brown shade is named', red);
const m = red[0]?.match(/try (#[0-9a-f]{6})/i);
ok(!!m && designAdvice({ name: 'A', top: { color: '#e2553a', shade: m[1] } }).length === 0, 'and the shade it proposes is one it accepts', m?.[1] ?? null);
ok(designAdvice({ name: 'A', top: { color: '#e2553a', shade: '#bf4530' } }).length === 0, 'a shade a fifth darker is fine', designAdvice({ name: 'A', top: { color: '#e2553a', shade: '#bf4530' } }));
ok(designAdvice({ name: 'Papa', top: { color: '#e9ebf2', shade: '#8e919b' }, bottom: { color: '#23252c', shade: '#17181d' } }).length === 0, 'a white shirt with a grey shadow and dark trousers are left alone (a shadow, not a stain)', designAdvice({ name: 'Papa', top: { color: '#e9ebf2', shade: '#8e919b' } }));
ok(designAdvice({ name: 'A', top: { color: '#e2553a' } }).length === 0 && designAdvice({}).length === 0 && designAdvice(null).length === 0, 'a design without shades, or none at all, gives no advice', null);
ok(designAdvice({ name: 'B', bottom: { color: '#2f7fd0', shade: '#12305a' }, robe: { color: '#3aa36a', shade: '#123a22' } }).length === 2, 'every piece of cloth is looked at (trousers, robe …)', designAdvice({ name: 'B', bottom: { color: '#2f7fd0', shade: '#12305a' }, robe: { color: '#3aa36a', shade: '#123a22' } }));
if (fails.length) { console.error(`lint: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`lint: all ${checks} rules hold`);
