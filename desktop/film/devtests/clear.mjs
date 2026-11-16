// Limbs clear of the body: node devtests/clear.mjs        (exit 1 when a rule is broken)
// A captured performer's legs pass close to each other (a stumble, a spin, jumping twists); on a film body that is
// built wider they pass THROUGH each other (16 Nov 2026: the Movement Study's judge found a thigh 12–14 cm inside the
// other). clearLegs opens the leg that is off the ground at the hip by the least angle that keeps the thighs apart;
// a leg that stands is not moved (its foot holds the ground).
import { clearLegs, thighOverlap } from '../engine/clear.js';
const fails = []; let checks = 0;
const ok = (c, what, got) => { checks++; if (!c) fails.push(`${what} — got ${JSON.stringify(got)}`); };
const pose = () => ({ height: 1.8, j: {
  'upperleg01.L': [0.09, 0.95, 0], 'lowerleg01.L': [-0.03, 0.52, 0.05], 'foot.L': [-0.06, 0.2, 0.12], 'toe2-1.L': [-0.06, 0.18, 0.24],
  'upperleg01.R': [-0.09, 0.95, 0], 'lowerleg01.R': [-0.09, 0.5, 0], 'foot.R': [-0.09, 0.07, 0], 'toe2-1.R': [-0.09, 0.03, 0.12],
} });
const P = pose(), before = thighOverlap(P.j), standR = [...P.j['foot.R']];
ok(before > 0.06, 'the test pose crosses the thighs (the left thigh goes into the right)', before);
clearLegs(P);
ok(thighOverlap(P.j) <= 0.03 + 1e-6, 'after clearLegs the thighs at most touch (3 cm, the flesh giving)', thighOverlap(P.j));
ok(P.j['foot.R'].every((v, i) => Math.abs(v - standR[i]) < 1e-9), 'the leg that stands is not moved: its foot holds the ground', P.j['foot.R']);
ok(P.j['foot.L'][0] > -0.06, 'the lifted leg is the one opened, outwards', P.j['foot.L']);
const Q = pose(); Q.j['lowerleg01.L'] = [0.1, 0.5, 0]; Q.j['foot.L'] = [0.1, 0.07, 0];
const q0 = JSON.stringify(Q.j); clearLegs(Q);
ok(JSON.stringify(Q.j) === q0, 'legs that are apart are left as they are', null);
const R = pose(), r0 = JSON.stringify(R.j); clearLegs(R, { depth: 0 });
ok(JSON.stringify(R.j) === r0, 'a crossing that does not last (depth over the frames round it: none) is let be', null);
console.log(fails.length ? `CLEAR: ${fails.length} of ${checks} rules broken\n  ✗ ${fails.join('\n  ✗ ')}` : `CLEAR: all ${checks} rules hold`);
process.exit(fails.length ? 1 : 0);
