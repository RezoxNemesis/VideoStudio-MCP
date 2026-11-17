// GREETINGS between people — a handshake, a high five, a namaste — built from the owner's 12-pose reference sheets
// (17 Nov 2026). Every one of them has the same bones (the method, kept in the skill):
//   notice (eyes to the other, a small smile) → start (the hand leaves the side) → approach (on an arc, the hand opening
//   and turning on the way) → ALIGN (the hands stop short, already shaped and turned as they will be at contact — they
//   never collide into place) → contact → the action (grip and shake / the clap and a slight push / the palms held and a
//   bow) → release in reverse order (the fingers open first, then the hands part) → lower → settle.
// The drawings bunch at contact and the action and are few on the travel: the phase fractions below keep that spacing.
// The wrist turns are MEASURED (Greet_Lab g1/g2: grids of 1029 + 1061 turns, palm and finger directions in the body's
// own frame): handshake [-30, 0, -15] palm to the middle −1.0, fingers forward and 30° down, thumb up 0.87; high five
// [15, 0, -75] palm forward 1.0, fingers up 0.93; namaste [-30, 30, -60] with the elbows OUT palm to the middle −0.98,
// fingers up 0.96 (the wrists bend back ~70°, as real ones do in a namaste).
//
// Two people: the LEAD's hand goes to its places (in his own frame); the other's hand goes to the LEAD's wrist (a reach
// to a bone of another actor, solved after it — runtime aimOrder), so the hands meet whatever the bodies do.
//   cast a: uses: () => [{ greet: 'handshake', t: 2, with: 'b', lead: true }]
//   cast b: uses: () => [{ greet: 'handshake', t: 2, with: 'a' }]
//   greetMarks('handshake', [x, z], yaw) → where b stands and his yaw, so the right hands meet in front of both
const off = (p, s, mir) => [-(mir ? -p[0] : p[0]) * s, (p[1] - 1.36) * s, (p[2] + 0.05) * s];
const turn = (r, mir) => (mir ? [r[0], -r[1], -r[2]] : r);
const pole = (p, mir) => (mir ? [-p[0], p[1], p[2]] : p);
const r3 = (x) => +x.toFixed(3);

export const GREET = {
  handshake: {
    dur: 3.2,   // (measured handshakes: reaching ~0.9 s, contact ~2 s, return ~0.75 s; the shake ~3–4 Hz from the elbow)
    // [right, up, forward] of the right wrist, a body 1.75 m tall, from its chest like usePose
    side: { p: [0.17, 0.98, 0.12], r: [0, 0, 0] },        // 02: the hand leaves the side, still loose
    reach: { p: [0.1, 1.04, 0.22], r: [-30, 0, -15] },      // 03: forward and turned already, thumb up
    meet: { p: [0.07, 1.08, 0.31], r: [-30, 0, -15] },     // 05: contact (elbow bent ~110°: a locked arm reads stiff)
    pole: [0.6, -0.6, 0.2],
    // the other's wrist from the lead's: on the far side of his palm, a hand's length on (the two palms overlap)
    other: [0.035, 0, 0.14], align: 0.07,                    // 04: stopped this much short
    shake: [0.03, -0.03, 0.028, -0.026, 0.02],               // 07-09: up, down, up, down, up — metres, from the elbow, ~3 Hz
    phases: { side: 0.05, reach: 0.2, align: 0.26, contact: 0.3, grip: 0.36, up1: 0.41, down: 0.46, up2: 0.51, down2: 0.56, up3: 0.61, settle: 0.67, release: 0.76, back: 0.95 },
    dist: 0.82, lat: 0.105,
  },
  five: {
    dur: 1.5,   // (a high five is quick: up in about half a second, a slap, gone)
    side: { p: [0.26, 1.24, 0.08], r: [15, 0, -40] },       // 02: the arm starts up at the side, the palm turning out
    reach: { p: [0.25, 1.52, 0.16], r: [15, 0, -75] },      // 03: up beside the head, never across the face      // 03: up and forward, palm forward, fingers up
    meet: { p: [0.13, 1.6, 0.33], r: [15, 0, -75] },       // 06: contact, about at the top of the head, the elbow still bent
    pole: [0.8, -0.5, -0.1],
    other: [0, 0, 0.04], align: 0.1,                         // palm on palm (two hands' thickness); 04 stops 10 cm short
    push: [0, 0.015, 0.02],                                  // 08: the follow-through — both hands go on a little
    phases: { side: 0.06, reach: 0.26, align: 0.36, near: 0.42, contact: 0.46, hold: 0.5, push: 0.55, separate: 0.64, lower: 0.8, back: 0.97 },
    dist: 0.74, lat: 0.26,
  },
  namaste: {
    dur: 3.0,
    side: { p: [0.2, 1.0, 0.14], r: [0, 15, -20] },        // 02: both hands start up from the sides (apart: they never meet low)
    raise: { p: [0.11, 1.17, 0.2], r: [-15, 30, -45] },    // 03: rising before the stomach, palms turning in, still a hand apart
    near: { p: [0.05, 1.29, 0.21], r: [-30, 30, -60] },    // 04-05: at the chest, palms facing, fingers up, apart
    join: { p: [0.025, 1.32, 0.2], r: [-30, 30, -60] },    // 06: joined at the breastbone, close to it (a hand's length off the chest), elbows out
    pole: [1, -0.4, -0.1],
    bow: 14, head: 16,                                       // 08: a slight bow, the head lower still, eyes shut
    phases: { side: 0.05, raise: 0.14, near: 0.23, align: 0.27, join: 0.31, hold: 0.38, bow: 0.46, bowEnd: 0.64, lift: 0.73, separate: 0.84, back: 0.97 },
  },
};

/** Where the other one stands (and faces) for a greeting with the lead at `at` facing `yaw` (deg). */
export function greetMarks(kind, at, yaw = 0, o = {}) {
  const G = GREET[kind], y = (yaw * Math.PI) / 180, f = [Math.sin(y), Math.cos(y)], r = [-Math.cos(y), Math.sin(y)];
  const d = o.dist ?? G.dist, l = o.lat ?? G.lat;
  return { at: [r3(at[0] + f[0] * d + r[0] * l), r3(at[1] + f[1] * d + r[1] * l)], face: yaw + 180 };
}

// THE HAND'S PATH through its places as ONE smooth reach (kit/greet.js and the drink): each run of places on the same
// body part is one reach overlay whose target offset moves along a spline (Stage reach `path`), so the hand passes
// THROUGH every key pose without stopping. (A chain of separately eased reaches made every pose a dead stop: the
// owner saw slow, stop-start motion, 18 Nov 2026.) A run that hands over to another (the follower's own approach →
// the lead's wrist) cross-fades over the gap between them; the later one is laid over the earlier.
// path: [[time, { actor, bone, offset }, pole?], …]
function chain(path, o) {
  const runs = [];
  for (const e of path) { const k = `${e[1].actor}|${e[1].bone}`, last = runs[runs.length - 1]; if (last && last.k === k) last.e.push(e); else runs.push({ k, e: [e] }); }
  return runs.map((r, i) => {
    const first = r.e[0][0], prevEnd = i ? runs[i - 1].e[runs[i - 1].e.length - 1][0] : first - (o.lead ?? 0.3), fadeIn = Math.max(0.12, first - prevEnd);
    const t0 = r3(first - fadeIn), next = runs[i + 1], t1 = r3(next ? next.e[0][0] + 0.02 : o.end), last = r.e[r.e.length - 1][0];
    const keys = r.e.map(([tt, tg]) => [r3(tt - t0), tg.offset]);
    // (the path starts moving as the reach fades in: a held first key made a dead stop right after the hand left the side)
    if (keys[0][0] > 0) keys[0] = [0, keys[0][1]];
    if (t1 - t0 > keys[keys.length - 1][0] + 1e-3) keys.push([r3(t1 - t0), keys[keys.length - 1][1]]);
    const poles = r.e.filter((x) => x[2]).map(([tt, , pl]) => [r3(tt - t0), pl]);
    const out = { t0, t1, fade: next ? fadeIn : Math.min(fadeIn, o.out ?? 0.4), reach: { actor: r.e[0][1].actor, bone: r.e[0][1].bone, offset: keys[0][1] }, side: o.side, path: keys };
    if (poles.length >= 2) { if (poles[0][0] > 0) poles[0] = [0, poles[0][1]]; out.polePath = poles; } else if (poles.length) out.pole = poles[0][1];
    void last;
    return out;
  });
}

/**
 * One person's part of a greeting. o: { t (when the hand leaves the side), with (the other's id, for two), lead (the
 * one whose hand leads), actor, height, hand ('R') } → { overlays, eyes(ts) → 0…1 shut, at: phase times }
 */
export function greet(kind, o) {
  const G = GREET[kind]; if (!G) throw new Error(`no greeting "${kind}" (${Object.keys(GREET).join(', ')})`);
  const D = o.dur ?? G.dur, t = o.t, s = (o.height ?? 1.75) / 1.75, mir = o.hand === 'L', side = mir ? 'L' : 'R', Ph = G.phases;
  const at = (k) => r3(t + Ph[k] * D), end = t + D + 0.3, me = { actor: o.actor, bone: 'spine01' };
  const own = (q) => ({ ...me, offset: off(q.p, s, mir) }), P = pole(G.pole, mir), ov = [];
  const wristKeys = (pts) => ({ t0: r3(t - 0.35), t1: r3(end + 0.2), fade: 0.3, keys: [{ on: `wrist.${side}`, keys: [[0, turn([0, 0, 0], mir)], ...pts.map(([tt, r]) => [r3(tt - t + 0.35), turn(r, mir), 'spline'])] }] });
  const fingers = (pts) => pts.map(([a, b, curl, thumb, f]) => ({ t0: r3(a), t1: r3(b), fade: f ?? 0.12, fingers: curl, side, thumb }));
  let eyes = () => 0;

  if (kind === 'namaste') {
    // both hands, mirrored; joined palms stay at the chest through the bow (placed from the chest, they bow with it)
    for (const h of ['R', 'L']) {
      const m = h === 'L', q = (x) => ({ ...me, offset: off(x.p, s, m) }), pl = pole(G.pole, m);
      ov.push(...chain([[at('side'), q(G.side), pl], [at('raise'), q(G.raise), pl], [at('near'), q(G.near), pl], [at('join'), q(G.join), pl], [at('separate'), q(G.near), pl], [at('back'), q(G.side), pl]], { side: h, end, out: 0.35 }));
      ov.push({ t0: r3(t - 0.35), t1: r3(end + 0.2), fade: 0.3, keys: [{ on: `wrist.${h}`, keys: [[0, [0, 0, 0]], ...[[at('side'), G.side.r], [at('raise'), G.raise.r], [at('near'), G.near.r], [at('separate'), G.near.r], [at('back'), G.side.r]].map(([tt, r]) => [r3(tt - t + 0.35), turn(r, m), 'spline'])] }] });
      ov.push({ t0: at('near') - 0.15, t1: at('back'), fade: 0.2, fingers: 0, side: h, thumb: 0.05, spread: -0.4 });
    }
    ov.push({ t0: r3(at('bow') - 0.15), t1: r3(at('lift') + 0.1), fade: 0.3, lean: { pitch: G.bow } },
      { t0: r3(at('bow') - 0.1), t1: r3(at('lift') + 0.1), fade: 0.3, rot: [G.head, 0, 0], bone: 'head' },
      { t0: r3(at('join') - 0.2), t1: r3(at('back')), fade: 0.4, face: { smile: 0.3 } });
    if (o.with) ov.push({ t0: r3(t - 0.5), t1: r3(at('bow')), fade: 0.25, look: { actor: o.with, bone: 'head' }, head: 0.5 }, { t0: r3(at('lift')), t1: r3(end), fade: 0.25, look: { actor: o.with, bone: 'head' }, head: 0.5 });
    const a = at('bow'), b = at('lift');
    eyes = (ts) => (ts <= a - 0.1 || ts >= b + 0.12 ? 0 : ts < a ? (ts - (a - 0.1)) / 0.1 : ts > b ? 1 - (ts - b) / 0.12 : 1);
  } else {
    // 01: notice — the eyes and a smile come first, before any hand moves
    if (o.with) ov.push({ t0: r3(t - 0.55), t1: r3(end), fade: 0.25, look: { actor: o.with, bone: 'head' }, head: 0.45 }, { t0: r3(t - 0.4), t1: r3(end), fade: 0.4, face: kind === 'five' ? 'joy' : 'happy' });
    const lead = !!o.lead || !o.with;
    // the follower's hand goes to the lead's wrist: stopped short at align, on it at contact (the lead's moves carry it)
    // (o.theirHand: the hand the other one uses, when it is not the same as this one's — a right meets a left in a high
    // five filmed from the side, so both near hands are seen; then the two stand straight across: greetMarks lat 0)
    const th = o.theirHand ?? side, cross = th !== side, theirs = (gap) => ({ actor: o.with, bone: `wrist.${th}`, offset: [cross ? 0 : G.other[0], G.other[1], G.other[2] + gap] });
    // where, in the follower's own frame, the lead's hand will be: its own approach heads there, so the hand-over to the
    // lead's wrist is between two near points (no stop on the way)
    const dist = o.dist ?? G.dist, est = (gap) => own({ p: [G.meet.p[0], G.meet.p[1], Math.max(G.reach.p[2] + 0.04, dist - G.meet.p[2] - G.other[2] - gap - 0.1)] });
    if (kind === 'handshake') {
      const meetAt = (dy) => ({ ...me, offset: off([G.meet.p[0], G.meet.p[1] + dy, G.meet.p[2]], s, mir) });
      const path = lead
        ? [[at('side'), own(G.side)], [at('reach'), own(G.reach), P], [at('align'), { ...me, offset: off([G.meet.p[0], G.meet.p[1], G.meet.p[2] - G.align], s, mir) }, P], [at('contact'), meetAt(0), P],
          [at('up1'), meetAt(G.shake[0]), P], [at('down'), meetAt(G.shake[1]), P], [at('up2'), meetAt(G.shake[2]), P], [at('down2'), meetAt(G.shake[3]), P], [at('up3'), meetAt(G.shake[4]), P], [at('settle'), meetAt(0), P],
          [at('release'), { ...me, offset: off([G.meet.p[0], G.meet.p[1], G.meet.p[2] - 0.05], s, mir) }, P], [at('back'), own(G.side)]]
        : [[at('side'), own(G.side)], [at('reach'), own(G.reach), P], [at('align') - 0.03, est(G.align), P], [at('align'), theirs(G.align), P], [at('contact'), theirs(0), P], [at('release'), theirs(0.06), P], [at('back'), own(G.side)]];
      ov.push(...chain(path, { side, end }));
      ov.push(wristKeys([[at('side'), G.side.r], [at('reach'), G.reach.r], [at('release'), G.meet.r], [at('back'), G.side.r]]));
      // open on the way (the aperture), wrap at contact, open first at the release, loose again at the side
      ov.push(...fingers([[t, at('contact'), 0.05, 0.2], [at('contact') - 0.02, at('settle') + 0.06, 0.62, 0.55, 0.08], [at('release') - 0.06, at('back'), 0.1, 0.25]]));
      // the shake comes from the elbow, the shoulders and head nod a little with it
      ov.push({ t0: at('up1') - 0.1, t1: at('settle'), fade: 0.12, rot: [3, 0, 0], bone: 'head' });
    } else {
      const meetAt = (d) => ({ ...me, offset: off([G.meet.p[0] + d[0], G.meet.p[1] + d[1], G.meet.p[2] + d[2]], s, mir) });
      const path = lead
        ? [[at('side'), own(G.side)], [at('reach'), own(G.reach), P], [at('align'), meetAt([0, 0, -G.align]), P], [at('near'), meetAt([0, 0, -0.03]), P], [at('contact'), meetAt([0, 0, 0]), P],
          [at('push'), meetAt(G.push), P], [at('separate'), meetAt([0, 0, -0.08]), P], [at('lower'), own(G.reach), P], [at('back'), own(G.side)]]
        : [[at('side'), own(G.side)], [at('reach'), own(G.reach), P], [at('align') - 0.03, est(G.align), P], [at('align'), theirs(G.align), P], [at('near'), theirs(0.03), P], [at('contact'), theirs(0), P], [at('push'), theirs(0), P],
          [at('separate'), theirs(0.16), P], [at('lower'), own(G.reach), P], [at('back'), own(G.side)]];
      ov.push(...chain(path, { side, end }));
      ov.push(wristKeys([[at('side'), G.side.r], [at('reach'), G.reach.r], [at('separate'), G.meet.r], [at('lower'), G.reach.r], [at('back'), [0, 0, 0]]]));
      // a flat hand, fingers together, from the approach to the parting
      ov.push(...fingers([[at('side'), at('lower'), 0, 0.05, 0.15]]));
      // the body goes into it: a small lean on the way up, the head up to the hands
      ov.push({ t0: at('reach'), t1: at('separate'), fade: 0.25, lean: { pitch: 6 } }, { t0: at('reach'), t1: at('separate'), fade: 0.2, rot: [-8, 0, 0], bone: 'head' });
    }
  }
  return { overlays: ov, eyes, at: Object.fromEntries(Object.keys(Ph).map((k) => [k, at(k)])) };
}
