// Life: what a body does when it does nothing. Nobody in a film is a statue — a body that stands, sits or kneels
// breathes (the chest fills, the shoulders ride up on it), its trunk sways a little over the hips, its head is never
// quite still. Laid over every performance by the engine (engine/shot.js), at each body's own pace and moment, and bent
// into the real body, not only its joints. The feet, the legs and the hips are never touched: they stay where they
// were performed.
import { clamp, hash, noise1 } from './core.js';
import { bendPose } from './bend.js';

/**
 * The breath of a body at time t: { b: −1 (empty) … +1 (full), rate (breaths a second) }. seed: the body's own number
 * (its pace and its moment); o.amp: 1 at rest, above 1 out of breath (faster and deeper), o.rate: a pace of one's own.
 */
export function breathAt(t, seed = 1, o = {}) {
  const amp = o.amp ?? 1, rate = o.rate ?? (0.2 + 0.1 * hash(seed * 1.7 + 0.31)) * (1 + 0.45 * Math.max(0, amp - 1));
  const u = t * rate + hash(seed * 3.3 + 7.1) * 8, f = u - Math.floor(u), IN = 0.4; // (in for two fifths of a breath, out for three)
  return { b: f < IN ? -Math.cos((Math.PI * f) / IN) : Math.cos((Math.PI * (f - IN)) / (1 - IN)), rate };
}

/**
 * The bends of a living body at time t (for bendPose: [{ bone, about, deg }]): breath in the upper spine and the collar
 * bones (the arms let hang, the neck keeping the head level), a slow sway of the trunk over the hips, a head that is
 * never quite still. amp: 0 = none, 1 = at rest, 2–3 = heaving after a run. o.speak: 0…1 — how loud the body is
 * speaking at this moment (its open mouth): the head goes with the words, the chest leans into them.
 */
export function lifeBends(t, seed = 1, amp = 1, o = {}) {
  if (!(amp > 0)) return [];
  const a = Math.min(amp, 3), s = Math.min(amp, 1.5), b = breathAt(t, seed, { amp }).b * a, n = (f, k) => noise1(t * f + seed * 1.9, k + seed);
  const sway = n(0.11, 1) * 0.45 * s, rock = n(0.09, 2) * 0.35 * s;
  // speaking: on a loud syllable the head dips (now and then lifts), turns a hair, and the chest comes forward with it
  const sp = Math.max(0, Math.min(1, o.speak ?? 0)), way = n(0.9, 6) > -0.55 ? 1 : -0.7;
  const talk = sp ? [{ bone: 'neck01', about: 'side', deg: -2.6 * sp * way }, { bone: 'neck01', about: 'up', deg: 1.6 * sp * n(1.3, 7) }, { bone: 'spine02', about: 'side', deg: -0.7 * sp }] : [];
  return [
    ...talk,
    // the trunk sways over the hips (balance is never finished)
    { bone: 'spine05', about: 'fwd', deg: sway }, { bone: 'spine05', about: 'side', deg: rock },
    // the breath: the upper chest lifts and draws back as it fills
    { bone: 'spine03', about: 'side', deg: 0.5 * b }, { bone: 'spine02', about: 'side', deg: 0.5 * b },
    // the shoulders ride up on it; the arms go on hanging
    { bone: 'clavicle.L', about: 'fwd', deg: 1.6 * b }, { bone: 'clavicle.R', about: 'fwd', deg: -1.6 * b },
    { bone: 'upperarm01.L', about: 'fwd', deg: -1.3 * b }, { bone: 'upperarm01.R', about: 'fwd', deg: 1.3 * b },
    // the neck gives back what the chest took: the head stays level — and looks about by a hair
    { bone: 'neck01', about: 'side', deg: -0.9 * b - rock + n(0.17, 3) * 0.7 * s }, { bone: 'neck01', about: 'fwd', deg: -sway * 0.8 },
    { bone: 'neck01', about: 'up', deg: (n(0.13, 4) * 1.4 + n(0.37, 5) * 0.5) * s },
  ].filter((x) => x.deg);
}

const BREATH = { spine03: 0.3, spine02: 0.7, spine01: 1, neck01: 1, neck03: 1, head: 1, 'head:tail': 1, 'eye.L': 1, 'eye.R': 1, 'jaw:tail': 1, 'clavicle.L': 1, 'clavicle.R': 1, 'upperarm01.L': 1, 'upperarm01.R': 1, 'lowerarm01.L': 0.6, 'lowerarm01.R': 0.6, 'wrist.L': 0.4, 'wrist.R': 0.4 };
/**
 * Lay life over a pose (so nobody is ever a statue). A real body is bent (trunk, collar bones, neck: bendPose); a
 * simple figure, which has only joints, is lifted and swayed. seed: the body's own number, amp: lifeBends' amp,
 * o: { speak } as lifeBends.
 */
export function enliven(pose, t, seed, amp = 1, o = {}) {
  if (!(amp > 0)) return pose;
  if (pose.body) return bendPose(pose, lifeBends(t, seed, amp, o));
  const j = pose.j, k = pose.height / 1.8, ank = Math.min(j['foot.L'][1], j['foot.R'][1]), top = j.head[1] - ank, a = Math.min(amp, 3);
  const sx = (noise1(t * 0.23 + seed, 1) * 0.012 + noise1(t * 0.63 + seed, 2) * 0.004) * Math.min(amp, 1.5) * k, sz = (noise1(t * 0.19 + seed, 3) * 0.01 + noise1(t * 0.55 + seed, 4) * 0.003) * Math.min(amp, 1.5) * k;
  const br = breathAt(t, seed, { amp }).b, bb = 0.0065 * a * k;
  for (const name in j) {
    const p = j[name], u = clamp((p[1] - ank - 0.06 * k) / top), w = u * u * (3 - 2 * u);
    p[0] += sx * w; p[2] += sz * w;
    const bw = BREATH[name.split(':')[0]] ?? BREATH[name]; if (bw) p[1] += br * bb * bw;
  }
  return pose;
}

const PARTS = ['head', 'neck01', 'spine01', 'upperarm01.L', 'upperarm01.R', 'wrist.L', 'wrist.R'];
/**
 * How still a body was over a stretch of time: frames = its joints at even moments ([{ root, head, … }]). → { move (m:
 * the farthest any part of its upper body went from its own mean place, measured against the hips), part (which) }.
 * A living body at rest moves 6 mm and more; one that moved less than 3 mm in two seconds is a statue.
 */
export function stillness(frames) {
  let move = 0, part = '';
  for (const name of PARTS) {
    const pts = frames.map((j) => (j[name] && j.root ? [j[name][0] - j.root[0], j[name][1] - j.root[1], j[name][2] - j.root[2]] : null)).filter(Boolean); if (pts.length < 2) continue;
    const m = [0, 1, 2].map((i) => pts.reduce((n, p) => n + p[i], 0) / pts.length), r = Math.max(...pts.map((p) => Math.hypot(p[0] - m[0], p[1] - m[1], p[2] - m[2])));
    if (r > move) { move = r; part = name; }
  }
  return { move, part };
}
