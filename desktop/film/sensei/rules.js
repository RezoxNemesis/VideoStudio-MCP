// Sensei's standards: what counts as a mistake, how bad it is (MUST / SHOULD / NICE), and how a film is scored.
// A benchmark is a set of limits; the gate passes when no MUST finding is left (and, for the strict ones, few SHOULD).

export const CATS = {
  motion: { name: 'Motion & body', weight: 0.2 },
  anatomy: { name: 'Hands, joints & contact', weight: 0.14 },
  camera: { name: 'Camera', weight: 0.15 },
  framing: { name: 'Framing & composition', weight: 0.11 },
  picture: { name: 'Picture', weight: 0.12 },
  sound: { name: 'Sound', weight: 0.1 },
  style: { name: 'Drawing style', weight: 0.1 },
  story: { name: 'Story & pacing', weight: 0.08 },
};
export const SEV = { must: { name: 'MUST fix', cost: 16 }, should: { name: 'SHOULD fix', cost: 5 }, nice: { name: 'Nice to fix', cost: 1.2 } };

const BASE = {
  // motion (metres, seconds)
  footSlide: [0.06, 0.15], // should, must (slide of a planted foot)
  sink: 0.035, float: 0.07,
  selfDepth: 0.055, // capsules of one body passing into each other deeper than this
  actorDepth: [0.06, 0.12], actorTime: [0.15, 0.3],
  gripDrift: 0.15,
  pop: 45, // m/s² sudden acceleration of a joint far above its neighbourhood
  teleport: 9, // m/s of the hips in one frame
  wrist: 100, neck: 82, spineTwist: 70, // degrees
  kneeBack: 0.018,
  swing: 0.07, // metres of wrist swing while walking
  stillTalk: 2.5, // seconds of a speaker with frozen arms
  arcDev: 0.035, // straightness of a big gesture
  balance: 0.06,
  // camera
  camNear: [0.35, 0.16], camWhip: [140, 320], // degrees per second
  shotShort: 0.6, shotLong: 12,
  cutJoint: 0.03, headroom: 0.24, tiny: 0.06, jumpAngle: 20,
  // picture
  dark: 0.1, bright: 0.86, clip: 0.06, crush: 0.25, blend: 11, colourJump: 20,
  // sound
  lufs: -14, lufsTol: 2, peak: -1, dialogueOverMusic: 8, lineSpread: 6, deadAir: 3,
  // story
  cps: 17,
};
export const BENCHMARKS = {
  youtube: { name: 'YouTube / Instagram (default)', ...BASE },
  anime: { name: 'Anime TV episode', ...BASE, footSlide: [0.05, 0.12], camWhip: [160, 360], lufs: -24, lufsTol: 2, cps: 15 },
  feature: { name: 'Feature film (strict)', ...BASE, footSlide: [0.03, 0.08], selfDepth: 0.045, pop: 35, camNear: [0.45, 0.2], blend: 13, lufs: -24, lufsTol: 1.5, peak: -2, shouldLimit: 6 },
  draft: { name: 'Draft (only what breaks the film)', ...BASE, footSlide: [0.12, 0.3], selfDepth: 0.09, pop: 80, onlyMust: true },
};
export const bench = (name) => ({ id: BENCHMARKS[name] ? name : 'youtube', ...(BENCHMARKS[name] ?? BENCHMARKS.youtube) });

let seq = 0;
/**
 * One finding. Everything Sensei says is data: where (shot, seconds, actor, body part, place in the picture), what was
 * measured against which limit, and what to change.
 */
export function issue(o) {
  return {
    id: o.id ?? `${o.kind}-${(++seq).toString(36)}`,
    cat: o.cat, kind: o.kind, sev: o.sev ?? 'should',
    shot: o.shot ?? null, seq: o.seq ?? null, t0: o.t0 ?? null, t1: o.t1 ?? o.t0 ?? null, at: o.at ?? null,
    actor: o.actor ?? null, part: o.part ?? null,
    where: o.where ?? null, // { x, y } (0…1 of the picture) at time `at`, or a world point (pt) to project
    pt: o.pt ?? null,
    value: o.value ?? null, limit: o.limit ?? null, unit: o.unit ?? null,
    msg: o.msg, fix: o.fix ?? null, data: o.data ?? null, auto: o.auto ?? null,
  };
}

/**
 * Scores 0…100 per category and overall; a grade; the gate. A category's score falls with the weight of its findings
 * relative to the size of what was judged (the same mistake costs a 10-shot film more than a 200-shot film), and never
 * hits zero just because a long film has many small things. Categories that were not measured are left out.
 */
export function score(issues, B, o = {}) {
  const cats = o.cats ?? Object.keys(CATS), shots = Math.max(1, o.shots ?? 10), measured = o.measured ?? null;
  const cost = {};
  for (const c of cats) cost[c] = 0;
  for (const i of issues) {
    if (!(i.cat in cost)) continue;
    const len = i.t1 != null && i.t0 != null ? Math.max(0, i.t1 - i.t0) : 0;
    cost[i.cat] += SEV[i.sev].cost * (1 + Math.min(1.5, len / 4));
  }
  const per = {};
  for (const c of cats) per[c] = Math.round(100 * Math.exp(-cost[c] / (55 * (1 + shots / 8))));
  if (o.styleScore != null && 'style' in per) per.style = Math.min(per.style, o.styleScore);
  let tot = 0, w = 0;
  for (const c of cats) { if (measured && !measured.has(c)) continue; tot += per[c] * CATS[c].weight; w += CATS[c].weight; }
  const overall = Math.round(tot / (w || 1));
  const must = issues.filter((i) => i.sev === 'must').length, should = issues.filter((i) => i.sev === 'should').length;
  const grade = must ? (overall >= 70 ? 'C' : overall >= 50 ? 'D' : 'F') : overall >= 95 ? 'S' : overall >= 88 ? 'A' : overall >= 78 ? 'B' : overall >= 65 ? 'C' : overall >= 50 ? 'D' : 'F';
  const pass = must === 0 && (B.shouldLimit == null || should <= B.shouldLimit);
  return { overall, grade, per, pass, must, should, nice: issues.filter((i) => i.sev === 'nice').length };
}
