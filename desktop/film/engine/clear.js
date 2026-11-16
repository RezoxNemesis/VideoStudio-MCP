// Arms clear of the body as it is dressed. A captured performer's arms rest against his own body; on a film body that is
// built (or dressed) wider, the same arm passes INTO the trunk — a laugh with the hands on the belly, a frightened
// hug, a hand on someone's shoulder (16 Nov 2026: the Movement Study's judge found forearms 5–6 cm inside the body).
// Each frame, an arm whose elbow, forearm or hand lies inside the trunk's outline is opened at the shoulder, by the
// least angle that brings it its own thickness outside: it then rests ON the body instead of in it. Measured against
// the same outline the judge measures (engine/body.js trunkShape), so what the judge calls arm-in-body is cured here.
import { trunkShape, trunkClear } from './body.js';
import { bendPose, axisQ } from './bend.js';
import { V, qrot } from './core.js';

const along = (a, b, f) => [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const norm = (v) => { const l = Math.hypot(v[0], v[1], v[2]) || 1; return [v[0] / l, v[1] / l, v[2] / l]; };
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];

/** the trunk's own directions in a pose (as the judge reads them): local(p) → [side, up, front] from the middle of the hips */
export function trunkFrameOf(j) {
  const hl = j['upperleg01.L'], hr = j['upperleg01.R']; if (!hl || !hr) return null;
  const pel = along(hl, hr, 0.5), nk = j.neck01 ?? j.spine01 ?? [pel[0], pel[1] + 0.5, pel[2]], up = norm(sub(nk, pel));
  const perp = (v) => { const d = dot(v, up); return norm([v[0] - up[0] * d, v[1] - up[1] * d, v[2] - up[2] * d]); };
  const hs = perp(sub(hl, hr)), sl = j['upperarm01.L'], sr = j['upperarm01.R'], cs = sl && sr ? perp(sub(sl, sr)) : hs, H = sl && sr ? Math.max(0.2, dot(sub(along(sl, sr, 0.5), pel), up)) : 0.5;
  const side = (f) => { const u = Math.max(0, Math.min(1, f)); return norm([hs[0] + (cs[0] - hs[0]) * u, hs[1] + (cs[1] - hs[1]) * u, hs[2] + (cs[2] - hs[2]) * u]); };
  const local = (p) => { const d = sub(p, pel), y = dot(d, up), sd = side(y / H); return [dot(d, sd), y, dot(d, cross(sd, up))]; };
  return { local, hipW: Math.hypot(hl[0] - hr[0], hl[1] - hr[1], hl[2] - hr[2]) };
}

/**
 * How far an arm is inside the trunk: the worst of elbow, forearm and hand, each against the thickness it needs
 * (metres; > 0 inside, ≤ 0 clear). E, W, K: elbow, wrist, knuckles.
 */
export function armDepth(S, F, E, W, K, margin = 0) {
  const sc = S.hip ? F.hipW / S.hip : 1, d = (p) => { const l = F.local(p); return -trunkClear(S, l[0] / sc, l[1] / sc, l[2] / sc) * sc; };
  let w = d(E) - 0.02 + margin;
  for (const f of [0.2, 0.4, 0.6, 0.8]) w = Math.max(w, d(along(E, W, f)) - 0.012 + margin);
  w = Math.max(w, d(W) - 0.012 + margin);
  for (const f of [0.5, 1]) w = Math.max(w, d(along(W, K, f)) - 0.012 + margin);
  return w;
}

/** Open each arm at the shoulder by the least angle that takes it out of the dressed trunk (at most o.max°, 40). → pose */
export function clearArms(pose, o = {}) {
  const B = o.B ?? pose.body?.B, j = pose.j; if (!B || !j) return pose;
  let S = null; try { S = trunkShape(B); } catch { S = null; } if (!S) return pose;
  const F = trunkFrameOf(j); if (!F) return pose;
  const hl = j['upperleg01.L'], hr = j['upperleg01.R'];
  let sx = hl[0] - hr[0], sz = hl[2] - hr[2]; const n = Math.hypot(sx, sz) || 1; sx /= n; sz /= n;
  const fwd = [-sz, 0, sx], margin = o.margin ?? 0.006, max = o.max ?? 40;
  const bends = [];
  for (const s of ['L', 'R']) {
    const P = j[`upperarm01.${s}`], E = j[`lowerarm01.${s}`], W = j[`wrist.${s}`], K = j[`finger3-1.${s}`] ?? W; if (!P || !E || !W) continue;
    const at = (deg) => { const q = axisQ(fwd, (deg * Math.PI) / 180), r = (p) => V.add(P, qrot(q, V.sub(p, P))); return armDepth(S, F, r(E), r(W), r(K), margin); };
    if (at(0) <= 0) continue;
    // which way round the forward axis takes this arm away from the body's middle
    const sgn = (() => { const q = axisQ(fwd, (10 * Math.PI) / 180), w = V.add(P, qrot(q, V.sub(W, P))), out = s === 'L' ? 1 : -1; return ((w[0] - W[0]) * sx + (w[2] - W[2]) * sz) * out > 0 ? 1 : -1; })();
    let lo = 0, hi = null;
    for (let a = 2; a <= max; a += 2) if (at(sgn * a) <= 0) { hi = a; break; } else lo = a;
    if (hi === null) hi = max; else for (let k = 0; k < 6; k++) { const m = (lo + hi) / 2; if (at(sgn * m) <= 0) hi = m; else lo = m; }
    bends.push({ bone: `upperarm01.${s}`, about: 'fwd', deg: sgn * hi });
  }
  return bends.length ? bendPose(pose, bends) : pose;
}
