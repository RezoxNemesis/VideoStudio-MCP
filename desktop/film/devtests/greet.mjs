// Greetings and the drink (kit/greet.js, kit/use.js drink): node devtests/greet.mjs   (exit 1 when a rule is broken)
// The rules are the owner's 12-pose sheets (17 Nov 2026): the hands stop short and aligned BEFORE contact, the action
// happens only at contact, release goes in reverse order, eyes and face come first, the spacing bunches at contact.
import { greet, greetMarks, GREET } from '../kit/greet.js';
import { drink, DRINK } from '../kit/use.js';
const fails = []; let checks = 0;
const ok = (c, what, got) => { checks++; if (!c) fails.push(`${what} — got ${JSON.stringify(got)}`); };
const reaches = (o) => o.overlays.filter((x) => x.reach);
const live = (ovs, t) => ovs.filter((x) => x.t0 <= t && x.t1 >= t);

for (const kind of ['handshake', 'five']) {
  const lead = greet(kind, { t: 1, actor: 'a', with: 'b', lead: true }), fol = greet(kind, { t: 1, actor: 'b', with: 'a' }), G = GREET[kind], at = lead.at;
  // 1. the phases run in order, and the drawings bunch at contact (the travel is quicker per phase than the action)
  const ks = Object.keys(G.phases), ts = ks.map((k) => at[k]);
  ok(ts.every((x, i) => !i || x > ts[i - 1]), `${kind}: phases in order`, ts);
  ok(at.align < at.contact && at.contact - at.align <= 0.2 * G.dur, `${kind}: an align beat just before contact`, [at.align, at.contact]);
  // 2. the follower's hand goes to the lead's WRIST at contact (it meets it whatever the bodies do), stopped short at align
  const fc = live(reaches(fol), at.contact).map((x) => x.reach).filter((r) => r.actor === 'a');
  ok(fc.length && fc.every((r) => r.bone === 'wrist.R'), `${kind}: follower reaches the lead's wrist at contact`, fc);
  const fa = reaches(fol).find((x) => x.reach.actor === 'a' && x.reach.offset[2] > G.other[2] + 0.05);
  ok(!!fa, `${kind}: follower stops ${G.align} m short at align`, reaches(fol).map((x) => x.reach.offset));
  // 3. the eyes and a smile before any hand moves; the elbow bent (contact well inside an arm's reach)
  const look = lead.overlays.find((x) => x.look), face = lead.overlays.find((x) => x.face);
  ok(look && look.t0 < 1 && look.look.actor === 'b', `${kind}: eyes to the other first`, look);
  ok(face && face.t0 < 1, `${kind}: the face answers first`, face);
  ok(G.meet.p[2] < 0.36, `${kind}: contact forward of the chest less than 36 cm (an arm locked straight reads stiff)`, G.meet.p);
  // 4. marks: far enough apart for bent elbows, near enough to meet
  const M = greetMarks(kind, [0, 0], 90), d = Math.hypot(M.at[0], M.at[1]);
  ok(d > 0.6 && d < 0.95 && M.face === 270, `${kind}: marks ${d.toFixed(2)} m apart, face to face`, M);
  // 5. every overlay is well formed
  ok([...lead.overlays, ...fol.overlays].every((x) => x.t1 > x.t0 && Number.isFinite(x.t0)), `${kind}: overlays have t0 < t1`, null);
}
{ // the handshake: grip closes at contact, opens before the hands part; two shakes
  const L = greet('handshake', { t: 1, actor: 'a', with: 'b', lead: true }), at = L.at, f = L.overlays.filter((x) => x.fingers !== undefined);
  const grip = f.find((x) => x.fingers > 0.4), open = f.filter((x) => x.fingers < 0.2);
  ok(grip && Math.abs(grip.t0 - at.contact) < 0.1, 'handshake: the fingers wrap at contact', grip);
  ok(open.some((x) => x.t0 < at.release), 'handshake: the fingers open first at the release', open);
  ok(GREET.handshake.shake.length === 3 && Math.max(...GREET.handshake.shake.map(Math.abs)) <= 0.05, 'handshake: a small shake, up-down-up from the elbow', GREET.handshake.shake);
}
{ // namaste: both hands, mirrored; eyes shut only in the bow
  const N = greet('namaste', { t: 1, actor: 'a', with: 'b' }), at = N.at, r = reaches(N);
  ok(r.some((x) => x.side === 'L') && r.some((x) => x.side === 'R'), 'namaste: both hands', null);
  const jr = r.find((x) => x.side === 'R' && Math.abs(x.reach.offset[0] + GREET.namaste.join.p[0]) < 1e-6), jl = r.find((x) => x.side === 'L' && Math.abs(x.reach.offset[0] - GREET.namaste.join.p[0]) < 1e-6);
  ok(jr && jl, 'namaste: the joined places mirror each other', [jr?.reach.offset, jl?.reach.offset]);
  ok(r.every((x) => !x.pole || Math.abs(x.pole[0]) > 0.8), 'namaste: elbows out to the sides', r.map((x) => x.pole));
  ok(N.eyes(at.join) === 0 && N.eyes((at.bow + at.bowEnd) / 2) === 1 && N.eyes(at.separate) === 0, 'namaste: eyes shut in the bow only', [N.eyes(at.join), N.eyes((at.bow + at.bowEnd) / 2), N.eyes(at.separate)]);
}
{ // the drink: level on the way up (measured turns), tipped only at the lips, eyes shut through the sip
  const D = drink({ t: 1, actor: 'a' }), at = D.at;
  ok(DRINK.mid && DRINK.chin && DRINK.chin.r[2] > 0, 'drink: measured level turns on the lift (mid, chin)', [DRINK.mid, DRINK.chin]);
  ok(D.eyes(at.lift) === 0 && D.eyes((at.hold + at.holdEnd) / 2) === 1 && D.eyes(at.lower) === 0, 'drink: eyes shut through the sip only', [D.eyes(at.lift), D.eyes(at.hold), D.eyes(at.lower)]);
  const ph = Object.values(DRINK.phases); ok(ph.every((x, i) => !i || x > ph[i - 1]), 'drink: phases in order', ph);
}
if (fails.length) { console.log(`greet: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.log('  ✗ ' + f); process.exit(1); }
console.log(`greet: all ${checks} rules hold`);
