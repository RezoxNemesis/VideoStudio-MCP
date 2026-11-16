// THE CHOREOGRAPHER — a catalogue of body movements and the lay-out of any chain of them for one or two performers.
// Each movement is a stretch of a captured clip. What it DOES is measured from the motion library (kit/movefacts.js, by
// devtests/clipfacts.mjs --write): how far the body travels ahead and to its left, how much it turns, how long it lasts,
// its course every quarter second, and — for two captured together — where the partner stands and faces. With that a
// film knows where every body will be after any chain of movements before anything is solved:
//   const C = choreo({ area: { x: [-6, 6], z: [-3, 3] } });
//   C.actor('a', { at: [-3, 0], yaw: 90 }); C.actor('b', { at: [3, 0], yaw: -90 });
//   C.do('a', 'hopscotch', { toward: 90 });           // travels towards +x, whichever way its performer went
//   C.do('b', 'bow'); C.then();                        // at the same time; then() waits for both
//   C.pair('handshake', 'a', 'b', { at: [-1, 0], yaw: 90 });   // both walk to their marks; hands meet
//   motion: () => ({ bodies: true, actors: C.actors({ a: A.body, b: B.body }), steps: C.steps() })
//   shots: () => beatShots(C, { id: 'm' })             // a camera for every beat; beatLabel(C, t) names what is done
// Yaw is in degrees: 0 faces +z, 90 faces +x (a body's left is the side its yaw grows towards).
import { FACTS } from './movefacts.js';
import { loadBake } from '../engine/bake.js';
export { FACTS };
const D2R = Math.PI / 180, r3 = (x) => Math.round(x * 1000) / 1000, wrap = (a) => ((a + 540) % 360) - 180;

export const KINDS = {
  travel: 'Moving from place to place', balance: 'Bending, stretching, balancing', hands: 'Throwing, catching, kicking, striking',
  feeling: 'Greeting and feeling', together: 'Two together', action: 'Action',
};
const M = (kind, name, clip, tin, out, o = {}) => ({ kind, name, clip, in: tin ?? 0.05, ...(out !== undefined ? { out } : {}), ...o });
/** id → { kind, name, clip, in, out?, mirror?, with? (the partner's movement), starts?: 'run' (it begins at speed), ends?: 'floor' | 'run' (default: standing), seat? (it sits: a seat belongs under it) } */
export const MOVEMENTS = {
  // moving from place to place
  'walk': M('travel', 'Walk', '08_01', 0.05, 2.3), 'happy-walk': M('travel', 'Happy walk', '82_12'), 'sad-walk': M('travel', 'Sad walk', '82_10', 0.6, 4.6),
  'sneak': M('travel', 'Sneak', '120_13', 1.0, 6.0), 'limp': M('travel', 'Limp', '77_24', 1.0, 5.5), 'march': M('travel', 'March', '138_05', 0.05, 3.0),
  'walk-back': M('travel', 'Walk backwards', '69_53', 0.05, 3.7), 'balance-walk': M('travel', 'Walk a line, arms out', '132_07', 0.5, 4.5), 'sidestep': M('travel', 'Walk sideways', '69_63'),
  'run': M('travel', 'Run', '09_02', 0.05, 1.0, { starts: 'run', ends: 'run' }), 'run-to-sneak': M('travel', 'Run, then creep', '139_11', 1.3, 5.3),
  'jump': M('travel', 'Jump', '13_40'), 'jump-forward': M('travel', 'Jump forward', '91_43'), 'jump-sideways': M('travel', 'Jump sideways', '141_05', 0.5, 3.4),
  'hop-turn': M('travel', 'Hop and turn in the air', '83_58', 0.3, 2.8), 'hopscotch': M('travel', 'Hopscotch', '75_13', 0.05, 4.8), 'leap': M('travel', 'Run and leap', '49_05', 0.05, undefined, { starts: 'run', ends: 'run' }),
  'jumping-twists': M('travel', 'Jumping twists', '143_08', 0.5, 5.0),
  // bending, stretching, balancing
  'bow': M('balance', 'Bow', '111_02'), 'stretch-yawn': M('balance', 'Stretch and yawn', '141_13', 0.05, 4.6), 'balance-one-leg': M('balance', 'Balance on one leg', '49_20', 0.3, 4.6),
  'jump-balance': M('balance', 'Jump and hold the balance', '02_04', 0.05, 3.6), 'sit-chair': M('balance', 'Sit down and get up', '143_18', 0.5, 7.4, { seat: true }),
  'squat-pick': M('balance', 'Squat and pick up', '69_70', 0.8, 5.2), 'lift-box': M('balance', 'Lift with the knees', '115_06'), 'bend-lift': M('balance', 'Lift from the waist', '115_01'),
  'twist-dance': M('balance', 'Twist', '141_12', 0.5, 5.5), 'stretch-spin': M('balance', 'Stretch and spin', '88_10', 0.05, 6.0), 'reach-up': M('balance', 'Reach and wipe', '143_26', 1.5, 6.0),
  // throwing, catching, kicking, striking
  'throw-catch': M('hands', 'Throw and catch', '141_11', 1.8, 6.4), 'toss-catch': M('hands', 'Toss and catch', '141_10', 0.5, 5.0), 'dribble': M('hands', 'Dribble', '06_05'),
  'dribble-shoot': M('hands', 'Dribble and shoot', '06_15'), 'soccer-kick': M('hands', 'Kick a ball', '10_03'), 'kick': M('hands', 'Kick', '74_05'), 'kick-step': M('hands', 'Step and kick', '74_04'),
  'boxing': M('hands', 'Boxing', '79_08'), 'sweep': M('hands', 'Sweep', '79_55'), 'drink': M('hands', 'Drink', '14_37'), 'phone': M('hands', 'Answer the phone', '79_36'),
  // greeting and feeling
  'wave': M('feeling', 'Wave', '111_37'), 'wave-hello': M('feeling', 'Wave hello', '141_16'), 'laugh': M('feeling', 'Laugh', '79_70', 1.0, 6.0), 'scared': M('feeling', 'Be frightened', '79_73', 3.0, 7.0),
  'salsa': M('feeling', 'Dance', '60_01', 0.5, 5.5), 'pirouette': M('feeling', 'Sideways steps and a pirouette', '05_11'), 'jete': M('feeling', 'A leap that turns', '05_16', 0.05, undefined, { starts: 'run' }),
  // two together (each has its partner: `with`)
  'handshake': M('together', 'Shake hands', '18_01', 0.05, undefined, { with: 'handshake-b' }), 'handshake-b': M('together', 'Shake hands', '19_01', 0.05, undefined, { with: 'handshake' }),
  'high-five': M('together', 'High five', '20_11', 0.05, undefined, { with: 'high-five-b' }), 'high-five-b': M('together', 'High five', '21_11', 0.05, undefined, { with: 'high-five' }),
  'low-five': M('together', 'Low five', '20_12', 0.05, undefined, { with: 'low-five-b' }), 'low-five-b': M('together', 'Low five', '21_12', 0.05, undefined, { with: 'low-five' }),
  'comfort': M('together', 'A hand on the shoulder', '22_07', 0.05, undefined, { with: 'comfort-b' }), 'comfort-b': M('together', 'A hand on the shoulder', '23_07', 0.05, undefined, { with: 'comfort' }),
  'pull': M('together', 'One pulls the other', '18_03', 0.05, undefined, { with: 'pull-b' }), 'pull-b': M('together', 'One pulls the other', '19_03', 0.05, undefined, { with: 'pull' }),
  'pass-drink': M('together', 'Pass a drink', '22_13', 0.05, undefined, { with: 'pass-drink-b' }), 'pass-drink-b': M('together', 'Pass a drink', '23_13', 0.05, undefined, { with: 'pass-drink' }),
  'link-arms': M('together', 'Walk arm in arm', '20_02', 0.05, undefined, { with: 'link-arms-b' }), 'link-arms-b': M('together', 'Walk arm in arm', '21_02', 0.05, undefined, { with: 'link-arms' }),
  'talk': M('together', 'Talk with the hands', '18_09', 0.05, undefined, { with: 'talk-b' }), 'talk-b': M('together', 'Talk with the hands', '19_09', 0.05, undefined, { with: 'talk' }),
  'throw-keys': M('together', 'Throw and catch between two', '22_22', 0.05, undefined, { with: 'throw-keys-b' }), 'throw-keys-b': M('together', 'Throw and catch between two', '23_22', 0.05, undefined, { with: 'throw-keys' }),
  'jumping-jacks': M('together', 'Jumping jacks together', '22_16', 0.05, undefined, { with: 'jumping-jacks-b' }), 'jumping-jacks-b': M('together', 'Jumping jacks together', '23_16', 0.05, undefined, { with: 'jumping-jacks' }),
  'squats': M('together', 'Squats in turn', '22_14', 0.05, undefined, { with: 'squats-b' }), 'squats-b': M('together', 'Squats in turn', '23_14', 0.05, undefined, { with: 'squats' }),
  'hold-hands': M('together', 'Walk hand in hand', '22_08', 0.05, undefined, { with: 'hold-hands-b' }), 'hold-hands-b': M('together', 'Walk hand in hand', '23_08', 0.05, undefined, { with: 'hold-hands' }),
  'arm-wrestle': M('together', 'Arm wrestle', '22_17', 0.05, undefined, { with: 'arm-wrestle-b' }), 'arm-wrestle-b': M('together', 'Arm wrestle', '23_17', 0.05, undefined, { with: 'arm-wrestle' }),
  // action
  'side-step-right': M('action', 'Side step right at a run', '127_14', 0.05, undefined, { starts: 'run', ends: 'run' }), 'side-step-left': M('action', 'Side step left at a run', '127_13', 0.05, undefined, { starts: 'run', ends: 'run' }),
  'duck': M('action', 'Duck under at a run', '128_04', 0.05, undefined, { starts: 'run', ends: 'run' }), 'roll-under': M('action', 'Roll under at a run', '128_09', 0.05, undefined, { starts: 'run' }),
  'dive-roll': M('action', 'Dive, roll, run on', '127_23', 0.05, undefined, { starts: 'run', ends: 'run' }), 'jump-over': M('action', 'Jump over at a run', '127_28', 0.05, undefined, { starts: 'run', ends: 'run' }),
  'cartwheel': M('action', 'Cartwheel', '88_07'), 'cartwheel-backflip': M('action', 'Cartwheel into a backflip', '88_05', 0.05, undefined, { starts: 'run' }), 'backflip': M('action', 'Backflip', '88_01', 0.45),
  'flip-hands': M('action', 'Flip back over the hands', '88_08', 1.0), 'one-hand-flip': M('action', 'Flip onto one hand', '89_03', 0.05, undefined, { starts: 'run' }), 'kickflip': M('action', 'Kick up from the floor', '85_06', 1.5),
  'jump-kick': M('action', 'Jump kick', '75_16', 2.0), 'spin-kick': M('action', 'Jump and spin kick', '88_06'), 'punch-kick': M('action', 'Punches and kicks', '141_14', 0.5, 6.5),
  'punching': M('action', 'Punches', '143_23', 0.5, 5.5), 'boxing-long': M('action', 'Boxing, a long round', '80_10', 0.5), 'stumble': M('action', 'Stumble', '91_59'),
  'banana-slip': M('action', 'Slip and fall', '90_17', 0.8, undefined, { ends: 'floor' }), 'fall-face': M('action', 'Fall forward', '90_16', 1.5, undefined, { ends: 'floor' }), 'get-up': M('action', 'Get up from the floor', '139_16', 3.5, 9.0),
  'spin-move': M('action', 'Spin past', '78_14', 0.05, undefined, { starts: 'run', ends: 'run' }),
};

/** Where a body is after a movement (or `sec` seconds into it) that began at S = { x, z, yaw } — as the Stage will put it. `move`: a movement's name or its facts. o: { leg (m), sec } */
export function after(S, move, o = {}) {
  const F = typeof move === 'string' ? FACTS[move] : move, leg = o.leg ?? 0.88;
  let side = F.side, fwd = F.fwd, turn = F.turn;
  if (o.sec !== undefined && o.sec < F.dur) { const u = Math.max(0, o.sec) / F.step, i = Math.min(F.course.length - 2, Math.floor(u)), a = F.course[i], b = F.course[i + 1], k = Math.min(1, u - i); side = a[0] + (b[0] - a[0]) * k; fwd = a[1] + (b[1] - a[1]) * k; turn = F.turn * (o.sec / F.dur); }
  const c = Math.cos(S.yaw * D2R), s = Math.sin(S.yaw * D2R);
  return { x: r3(S.x + (side * c + fwd * s) * leg), z: r3(S.z + (-side * s + fwd * c) * leg), yaw: r3(wrap(S.yaw + turn)) };
}
/** the farthest a movement's course goes outside a floor, begun at S (metres; 0: it stays on it) */
function outside(S, F, leg, area) {
  let out = 0;
  for (let i = 0; i < F.course.length; i++) { const p = after(S, { ...F, side: F.course[i][0], fwd: F.course[i][1], turn: 0 }, { leg }); out = Math.max(out, area.x[0] - p.x, p.x - area.x[1], area.z[0] - p.z, p.z - area.z[1]); }
  return Math.max(0, out);
}
const SPEED = { walk: 1.3, stroll: 0.9, march: 1.5, sneak: 0.8, jog: 2.6, run: 3.6 };

/**
 * Lay movements out in time and on a floor. o: { leg: the performers' leg in metres (0.88 for 1.78 m), area: { x: [from, to], z: [from, to] } the floor, t: when it begins }
 *   actor(id, { at: [x, z], yaw })                              someone on the floor
 *   go(id, [x, z], { gait, speed, face, t })                    walk or run to a mark (then stand, facing `face`)
 *   do(id, movement, { t, at, toward, face, rate, hold, blend, name })   one movement. at: walk to that mark first; toward: the way it
 *       TRAVELS (degrees); face: the way the body faces as it begins; hold: seconds to stay after it; rate: speed of the clip
 *   pair(movement, a, b, { at, yaw, t })                        two captured together: both walk to their marks and begin at once
 *   both(movement, [a, b], { … })                               the same movement by both at the same moment
 *   then(gap = 0)   wait until everyone has finished            wait(t)   move the clock to t
 * Every do/pair/both gives its beat back: { n, id, name, kind, by, t0, t1, from, to }. steps() → the Stage's commands;
 * actors(bodies) → the motion actors; beats, dur, where(id, t) → { x, z, yaw } as laid out.
 */
export function choreo(o = {}) {
  const leg = o.leg ?? 0.88, area = o.area ?? { x: [-6, 6], z: [-3, 3] }, P = {}, beats = [];
  let now = o.t ?? 0;
  const me = (id) => { const p = P[id]; if (!p) throw new Error(`choreo: nobody called "${id}" is on the floor (actor('${id}', { at, yaw }) first)`); return p; };
  const push = (p, b, state, t1) => { p.blocks.push(b); p.track.push({ t0: b.t, t1, from: { x: p.x, z: p.z, yaw: p.yaw }, to: state, move: b.move, go: !!b.go, startYaw: b.startYaw }); p.x = state.x; p.z = state.z; p.yaw = state.yaw; p.free = t1; };
  const C = {
    leg, area, beats,
    get now() { return now; }, get dur() { return Math.max(now, ...Object.values(P).map((p) => p.free)); },
    actor(id, a = {}) { P[id] = { id, x: a.at?.[0] ?? 0, z: a.at?.[1] ?? 0, yaw: a.yaw ?? 0, free: now, blocks: [], track: [], start: { at: [a.at?.[0] ?? 0, a.at?.[1] ?? 0], yaw: a.yaw ?? 0 } }; return C; },
    wait(t) { now = Math.max(now, t); return C; },
    then(gap = 0) { now = C.dur + gap; return C; },
    go(id, to, g = {}) {
      const p = me(id), t = Math.max(g.t ?? now, p.free), gait = g.gait ?? 'walk', speed = g.speed ?? SPEED[gait] ?? 1.3, D = Math.hypot(to[0] - p.x, to[1] - p.z);
      const heading = D > 1e-3 ? Math.atan2(to[0] - p.x, to[1] - p.z) / D2R : p.yaw, turned = Math.abs(wrap(heading - p.yaw)) > 57;
      // (the Stage: a turn first when he faces away, the stride grows and settles, then he turns to `face`)
      // (through: he does not stop there — the next movement takes him over while he is still at speed, a little short of the mark)
      const t1 = g.through ? t + D / speed + (turned ? 0.42 : 0) + 0.1 : t + D / speed + 0.45 + (turned ? 0.42 : 0) + (g.face !== undefined && Math.abs(wrap(g.face - heading)) > 8 ? 0.7 : 0.15);
      const short = g.through && D > 0.8 ? Math.min(0.35, speed * 0.09) : 0, end = [to[0] - Math.sin(heading * D2R) * short, to[1] - Math.cos(heading * D2R) * short];
      push(p, { t: r3(t), go: [r3(to[0]), r3(to[1])], gait, speed, ...(g.face !== undefined ? { face: r3(g.face) } : {}), ...(g.clip ? { clip: g.clip } : {}) }, { x: end[0], z: end[1], yaw: g.through ? heading : g.face ?? heading }, r3(t1));
      return { t0: r3(t), t1: r3(t1) };
    },
    do(id, move, d = {}) {
      const m = MOVEMENTS[move], F = FACTS[move]; if (!m || !F) throw new Error(`choreo: no movement "${move}" in the catalogue`);
      const p = me(id), rate = d.rate ?? 1, travels = Math.hypot(F.fwd, F.side) > 0.25, cx = (area.x[0] + area.x[1]) / 2, cz = (area.z[0] + area.z[1]) / 2;
      const yawFor = (S) => {
        if (d.face !== undefined) return d.face;
        if (d.toward !== undefined) return wrap(d.toward - (travels ? F.head : 0));
        if (outside({ ...S }, F, leg, area) <= 0) return S.yaw;
        // it would leave the floor: turn it so that it travels over the middle of the floor …
        const mid = wrap(Math.atan2(cx - S.x, cz - S.z) / D2R - F.head); if (outside({ ...S, yaw: mid }, F, leg, area) <= 0) return mid;
        // … or whichever way keeps it on (tried every 15°)
        let best = null; for (let a = 0; a < 360; a += 15) { const e = outside({ ...S, yaw: a }, F, leg, area); if (!best || e < best[0]) best = [e, a]; }
        if (best[0] <= 0) return wrap(best[1]);
        throw new Error(`choreo: "${move}" cannot stay on the floor from [${r3(S.x)}, ${r3(S.z)}] (it goes ${r3(Math.hypot(F.fwd, F.side) * leg)} m; the area is x ${area.x} z ${area.z}): give it at: [x, z] or a larger area`);
      };
      if (d.at) { const y = yawFor({ x: d.at[0], z: d.at[1], yaw: p.yaw }); C.go(id, d.at, { gait: d.gait ?? 'walk', speed: d.speed, face: y, t: d.t }); }
      const t = Math.max(d.t ?? now, p.free), from = { x: p.x, z: p.z, yaw: p.yaw }, yaw = d.at ? p.yaw : yawFor(from), S = { ...from, yaw };
      if (outside(S, F, leg, area) > 0.05 && (d.face !== undefined || d.toward !== undefined)) throw new Error(`choreo: "${move}" leaves the floor from [${r3(S.x)}, ${r3(S.z)}] heading ${r3(yaw)}° (the area is x ${area.x} z ${area.z})`);
      const to = after(S, F, { leg }), t1 = t + F.dur / rate, turn = Math.abs(wrap(yaw - from.yaw)) > 0.5;
      push(p, { t: r3(t), clip: m.clip, in: m.in, ...(m.out !== undefined ? { out: m.out } : {}), ...(rate !== 1 ? { rate } : {}), ...(m.mirror ? { mirror: true } : {}), ...(turn ? { at: { yaw: r3(yaw) } } : {}), blend: d.blend ?? (turn ? 0.45 : 0.3), move, startYaw: yaw }, to, r3(t1));
      p.blocks.at(-1).ends = m.ends ?? 'stand'; if (d.hold) p.free = r3(t1 + d.hold);
      if (d.quiet) return { t0: r3(t), t1: r3(t1), from: S, to };
      const beat = { n: beats.length + 1, id: move, name: d.name ?? m.name, kind: m.kind, by: [id], t0: r3(t), t1: r3(t1), from: S, to }; beats.push(beat); return beat;
    },
    pair(move, a, b, d = {}) {
      const m = MOVEMENTS[move], F = FACTS[move]; if (!m?.with || !F?.pair) throw new Error(`choreo: "${move}" is not a movement for two`);
      const pa = me(a), at = d.at ?? [pa.x, pa.z], yaw = d.yaw ?? pa.yaw, c = Math.cos(yaw * D2R), s = Math.sin(yaw * D2R), q = F.pair;
      const bAt = [at[0] + (q.side * c + q.fwd * s) * leg, at[1] + (-q.side * s + q.fwd * c) * leg], bYaw = wrap(yaw + q.yaw);
      const moved = (p, to) => Math.hypot(p.x - to[0], p.z - to[1]) > 0.03;
      // (their ways to the marks may cross: then the second goes round — by a point beside the middle of his way, on the side away from the first)
      const pb0 = me(b), La = Math.hypot(at[0] - pa.x, at[1] - pa.z), Lb = Math.hypot(bAt[0] - pb0.x, bAt[1] - pb0.z); let nearest = 9;
      for (let u = 0; u <= 1.001; u += 0.05) { const ta = Math.min(1, u * Math.max(La, Lb) / Math.max(La, 1e-3)), tb = Math.min(1, u * Math.max(La, Lb) / Math.max(Lb, 1e-3)); nearest = Math.min(nearest, Math.hypot(pa.x + (at[0] - pa.x) * ta - pb0.x - (bAt[0] - pb0.x) * tb, pa.z + (at[1] - pa.z) * ta - pb0.z - (bAt[1] - pb0.z) * tb)); }
      const round = nearest < 0.9 && Lb > 1 ? (() => { const mx = (pb0.x + bAt[0]) / 2, mz = (pb0.z + bAt[1]) / 2, nx = -(bAt[1] - pb0.z) / Lb, nz = (bAt[0] - pb0.x) / Lb, am = [(pa.x + at[0]) / 2, (pa.z + at[1]) / 2], sgn = (am[0] - mx) * nx + (am[1] - mz) * nz > 0 ? -1 : 1; let w = [mx + nx * 1.5 * sgn, mz + nz * 1.5 * sgn]; if (w[1] < area.z[0] + 0.3 || w[1] > area.z[1] - 0.3 || w[0] < area.x[0] + 0.3 || w[0] > area.x[1] - 0.3) w = [mx - nx * 1.5 * sgn, mz - nz * 1.5 * sgn]; return w; })() : null;
      if (moved(pa, at) || Math.abs(wrap(pa.yaw - yaw)) > 1) C.go(a, at, { face: yaw, t: d.t, gait: d.gait });
      const pb = me(b); if (round) C.go(b, round, { t: d.t, gait: d.gait, through: true }); if (moved(pb, bAt) || Math.abs(wrap(pb.yaw - bYaw)) > 1) C.go(b, bAt, { face: bYaw, t: d.t, gait: d.gait });
      const t = Math.max(d.t ?? now, pa.free, pb.free) + (d.settle ?? 0.25);
      const A = C.do(a, move, { t, face: yaw, quiet: true, rate: d.rate }), B = C.do(b, m.with, { t, face: bYaw, quiet: true, rate: d.rate });
      const beat = { n: beats.length + 1, id: move, name: d.name ?? m.name, kind: m.kind, by: [a, b], t0: A.t0, t1: Math.max(A.t1, B.t1), from: A.from, to: A.to, fromB: B.from, toB: B.to }; beats.push(beat); return beat;
    },
    both(move, ids, d = {}) {
      const t = Math.max(d.t ?? now, ...ids.map((i) => me(i).free)), R = ids.map((i) => C.do(i, move, { ...d, t, quiet: true }));
      const beat = { n: beats.length + 1, id: move, name: d.name ?? MOVEMENTS[move].name, kind: MOVEMENTS[move].kind, by: [...ids], t0: Math.min(...R.map((r) => r.t0)), t1: Math.max(...R.map((r) => r.t1)), from: R[0].from, to: R[0].to, fromB: R[1]?.from, toB: R[1]?.to }; beats.push(beat); return beat;
    },
    /** Several movements as ONE beat (two performers doing different things at once; or a run of small ones): group('name', () => { C.do(…); C.do(…); }) */
    group(name, fn) {
      const n0 = beats.length; fn(C); const made = beats.splice(n0); if (!made.length) return null;
      const beat = { ...made[0], n: n0 + 1, name: name ?? [...new Set(made.map((q) => q.name))].join(' · '), by: [...new Set(made.flatMap((q) => q.by))], t0: Math.min(...made.map((q) => q.t0)), t1: Math.max(...made.map((q) => q.t1)), parts: made };
      beats.push(beat); return beat;
    },
    /** the Stage's commands: one list of actions for each body (a standing body between two movements breathes) */
    steps() {
      return Object.values(P).map((p) => {
        const actions = [];
        if (!p.blocks.length || p.blocks[0].t > 0.01) actions.push({ t: 0, idle: true });
        p.blocks.forEach((b, i) => {
          const { move, startYaw, ends, ...act } = b; actions.push(act);
          const t1 = p.track[i].t1, next = p.blocks[i + 1]?.t ?? Infinity;
          if (!b.go && ends === 'stand' && next - t1 > 0.25) actions.push({ t: t1, idle: true, blend: 0.45 });
        });
        return { op: 'stage.do', actor: p.id, actions };
      });
    },
    /** the motion actors: bodies[id] (a Stage body) with where each begins */
    actors(bodies = {}) { return Object.values(P).map((p) => ({ id: p.id, ...(bodies[p.id] ?? {}), at: p.start.at, yaw: p.start.yaw })); },
    /** where someone is at a moment, as laid out */
    where(id, t) {
      const p = me(id); let S = { x: p.start.at[0], z: p.start.at[1], yaw: p.start.yaw };
      for (const k of p.track) {
        if (t < k.t0) return S;
        if (t >= k.t1) { S = k.to; continue; }
        const u = (t - k.t0) / Math.max(1e-6, k.t1 - k.t0);
        if (k.go) return { x: k.from.x + (k.to.x - k.from.x) * u, z: k.from.z + (k.to.z - k.from.z) * u, yaw: k.to.yaw };
        return after({ ...k.from, yaw: k.startYaw }, k.move, { leg, sec: t - k.t0 });
      }
      return S;
    },
    ids: () => Object.keys(P),
  };
  return C;
}

/** What is being done at a moment: { n, name, kind (in words), by, u: seconds since it began } or null between beats. */
export function beatLabel(C, t) {
  const b = C.beats.find((q) => t >= q.t0 - 1e-6 && t < q.t1); return b ? { n: b.n, id: b.id, name: b.name, kind: KINDS[b.kind], by: b.by, u: t - b.t0 } : null;
}

const VIEWS = ['front', 'left', 'right'], AZ = { front: 0, left: -16, right: 16 };
/**
 * A camera for every beat of a choreography. o: { id: prefix of the shot ids, side: the camera's side of the floor in degrees (0: it
 * stands at +z looking towards −z), fov, far: extra metres back, h: its height, long: seconds after which a beat is cut in two,
 * top / over / post / hold: given to every shot, each: (shot, beat) => more for one shot, who: (i, beat, t0, t1) => ids the i-th shot frames (default: the beat's performers) }
 * The lens stands off to the side the floor is watched from, level with the hips; it goes with the performers along the ground
 * only (its height is held: a jump reads against a level frame), and every cut changes the view (front, left, right).
 */
export function beatShots(C, o = {}) {
  const cuts = [0], long = o.long ?? 6.5;
  for (const b of [...C.beats].sort((p, q) => p.t0 - q.t0)) { if (b.t0 - cuts.at(-1) > 1.2) cuts.push(b.t0); }
  cuts.push(C.dur);
  // (a beat too short to hold a shot is joined to the one before; a stretch too long is cut in equal parts)
  const spans = [];
  for (let i = 0; i + 1 < cuts.length; i++) { const a = cuts[i], b = cuts[i + 1], n = Math.max(1, Math.ceil((b - a) / long)); for (let k = 0; k < n; k++) spans.push([a + (b - a) * k / n, a + (b - a) * (k + 1) / n]); }
  if (spans.length > 1 && spans.at(-1)[1] - spans.at(-1)[0] < 1.2) { const l = spans.pop(); spans.at(-1)[1] = l[1]; }
  const side = (o.side ?? 0) * D2R, rot = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), 0, -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
  return spans.map(([t0, t1], i) => {
    const mid = (t0 + t1) / 2, beat = C.beats.find((b) => mid >= b.t0 && mid < b.t1) ?? C.beats.filter((b) => b.t0 <= mid).at(-1) ?? C.beats[0];
    const who = o.who?.(i, beat, t0, t1) ?? beat?.by ?? C.ids(), view = VIEWS[i % 3], dir = rot([0, 0, 1], side + AZ[view] * D2R);
    // how much ground the shot must hold: where its performers go while it lasts, as laid out
    let x0 = 1e9, x1 = -1e9, z0 = 1e9, z1 = -1e9; for (const id of who) for (let t = t0; t <= t1 + 1e-6; t += 0.25) { const p = C.where(id, t); x0 = Math.min(x0, p.x); x1 = Math.max(x1, p.x); z0 = Math.min(z0, p.z); z1 = Math.max(z1, p.z); }
    // how far back the lens must stand so that everyone it frames is IN the picture all through the shot: the picture is
    // 1.09 × the distance wide (fov 34°, 16:9). A lens that goes with the performers must hold how far apart they are;
    // a lens that stands still must hold all the ground they cover. Two at different depths are seen a little from above,
    // so the one behind stands clear of the one in front.
    const span = Math.hypot(x1 - x0, z1 - z0), fov = o.fov ?? 34, follow = span > 3.2, wide = 2 * Math.tan((fov * D2R) / 2) * (16 / 9);
    let apart = 0, deep = 0; if (who.length > 1) for (let t = t0; t <= t1 + 1e-6; t += 0.25) { const ps = who.map((id) => C.where(id, t)); for (const p of ps) for (const q of ps) { apart = Math.max(apart, Math.hypot(p.x - q.x, p.z - q.z)); deep = Math.max(deep, Math.abs((p.x - q.x) * dir[0] + (p.z - q.z) * dir[2])); } }
    // (someone who leaves the ground needs air over his head: the lens stands farther back and looks a little higher)
    const air = Math.max(0, ...C.beats.flatMap((q) => q.parts ?? [q]).filter((q) => q.t0 < t1 && q.t1 > t0 && q.by.some((id) => who.includes(id))).map((q) => FACTS[q.id]?.up ?? 0));
    const need = follow ? apart + (who.length > 1 ? 2.6 : 0) + 1.2 : span + 2.8, dist = (o.far ?? 0) + Math.max(air > 0.15 ? 5.8 : 4.6, need / wide), h = o.h ?? (deep > 0.6 ? 1.75 : 1.05), aimY = air > 0.15 ? 1.15 : 0.95;
    const cam = (t, ctx) => {
      // the middle of the performers' hips on the ground; when they travel far the lens goes with them
      const ps = who.map((id) => ctx.poses[id]?.j?.root).filter(Boolean), m = ps.length ? [ps.reduce((s, p) => s + p[0], 0) / ps.length, 0, ps.reduce((s, p) => s + p[2], 0) / ps.length] : [(x0 + x1) / 2, 0, (z0 + z1) / 2];
      const k = follow ? 1 : 0.3, cx = (x0 + x1) / 2 * (1 - k) + m[0] * k, cz = (z0 + z1) / 2 * (1 - k) + m[2] * k;
      return { pos: [cx + dir[0] * dist, h, cz + dir[2] * dist], target: [cx, aimY, cz], fov };
    };
    const sh = { id: `${o.id ?? 'm'}_${String(i + 1).padStart(2, '0')}`, t0: r3(t0), t1: r3(t1), view, beat: beat?.n, cam, note: beat ? `${beat.n} ${beat.name} (${view})` : view };
    for (const k of ['top', 'over', 'post', 'hold', 'dof']) if (o[k] !== undefined) sh[k] = o[k];
    return o.each ? { ...sh, ...(o.each(sh, beat) ?? {}) } : sh;
  });
}

/**
 * The sound of feet from a performance itself: a step each time a foot comes down, a landing when the body comes down
 * from the air. contact: one byte a frame (bit 1 = left foot down, bit 2 = right), as the Stage solved it.
 * o: { gain: 0.25, pan, air: seconds off the ground that make a landing (0.16) } → [{ t, sfx: 'step' | 'land', gain, pitch, pan }]
 */
export function footfallsOf(contact, fps, o = {}) {
  const out = [], g = o.gain ?? 0.25, airMin = o.air ?? 0.16; let airFrom = -1, last = -1;
  for (let i = 1; i < contact.length; i++) {
    const was = contact[i - 1], is = contact[i], t = i / fps;
    if (was && !is) airFrom = i;
    if (!was && is && airFrom >= 0 && (i - airFrom) / fps >= airMin) { const air = (i - airFrom) / fps; out.push({ t: r3(t), sfx: 'land', gain: r3(Math.min(1, g * (1.5 + air * 1.6))), pitch: 0.9, pan: o.pan ?? 0 }); airFrom = -1; last = i; continue; }
    if (is) airFrom = -1;
    const rise = is & ~was; if (!rise || (i - last) / fps < 0.12) continue; // (the same foot fidgeting is not a step)
    if (was === 0 && is) { last = i; out.push({ t: r3(t), sfx: 'step', gain: r3(g * 0.9), pitch: rise & 1 ? 0.96 : 1.05, pan: o.pan ?? 0 }); continue; }
    last = i; out.push({ t: r3(t), sfx: 'step', gain: r3(g * (rise === 3 ? 1 : 0.85)), pitch: rise & 1 ? 0.96 : 1.05, pan: o.pan ?? 0 });
  }
  return out;
}
/** footfallsOf for the solved performance of a sequence: footfalls('m01', ['a', 'b'], { gain }) — nothing until it has been solved once. */
export function footfalls(bakeName, ids, o = {}) {
  let B = null; try { B = loadBake(bakeName); } catch { return []; }
  return ids.flatMap((id, i) => { const A = B?.actors?.[id]; return A?.contact ? footfallsOf(A.contact, B.fps, { ...o, pan: o.pan ?? (i % 2 ? 0.25 : -0.25) }) : []; });
}

/** What everyone is doing at a moment: [{ who, name, kind, n (the movement's number in the order they begin), u }] — a pair or a "both" is one entry for both. */
export function doingAt(C, t) {
  const all = C.beats.flatMap((b) => b.parts ?? [b]).sort((p, q) => p.t0 - q.t0 || (p.by[0] < q.by[0] ? -1 : 1));
  return all.map((p, i) => ({ p, n: i + 1 })).filter(({ p }) => t >= p.t0 - 1e-6 && t < p.t1).map(({ p, n }) => ({ who: p.by, id: p.id, name: p.name, kind: KINDS[p.kind], n, u: t - p.t0 }));
}
