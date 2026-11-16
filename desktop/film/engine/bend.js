// Bending a performed body after the performance: a film's own change to what was captured (a rider's seat, a spear
// carried upright), and the life the engine lays over every body (engine/life.js). The pose's joints turn at once; the
// real body is bent the same way when it is drawn (engine/body.js bendBone).
import { V, qrot } from './core.js';

const qmul = (p, q) => [p[3] * q[0] + p[0] * q[3] + p[1] * q[2] - p[2] * q[1], p[3] * q[1] - p[0] * q[2] + p[1] * q[3] + p[2] * q[0], p[3] * q[2] + p[0] * q[1] - p[1] * q[0] + p[2] * q[3], p[3] * q[3] - p[0] * q[0] - p[1] * q[1] - p[2] * q[2]];
export const axisQ = (ax, ang) => { const s = Math.sin(ang / 2); return [ax[0] * s, ax[1] * s, ax[2] * s, Math.cos(ang / 2)]; };

/** The joints that hang on a bone, by name, when the pose carries no real body to ask: the limbs. */
const HANGS = [
  [/^upperleg01\.(L|R)$/, /^(upperleg02|lowerleg\d*|foot|toe[\w-]*)\.S/], [/^lowerleg01\.(L|R)$/, /^(lowerleg02|foot|toe[\w-]*)\.S/],
  [/^upperarm01\.(L|R)$/, /^(upperarm02|lowerarm\d*|wrist|finger[\w-]*|metacarpal\d*)\.S/], [/^lowerarm01\.(L|R)$/, /^(lowerarm02|wrist|finger[\w-]*|metacarpal\d*)\.S/],
];
/**
 * Bend a performed body at its joints, after the performance: bends: [{ bone, about: 'side' | 'fwd' | 'up', deg, props?:
 * [what is carried on what hangs there: 'spear'] }], in the body's own axes — about `side` a positive turn swings what
 * hangs on the bone backwards (a knee bends; a thigh or a forearm lifts forward with a negative one), about `fwd` out to
 * the body's left. The pose's joints and the named props turn at once; the real body is bent the same way when it is
 * drawn (engine/body.js bendBone). A rider's seat, a spear carried upright.
 */
export function bendPose(pose, bends) {
  const hl = pose.j['upperleg01.L'], hr = pose.j['upperleg01.R'], B = pose.body?.B ?? null;
  let sx = hl[0] - hr[0], sz = hl[2] - hr[2]; const n = Math.hypot(sx, sz) || 1; sx /= n; sz /= n;
  const AX = { side: [sx, 0, sz], up: [0, 1, 0], fwd: [-sz, 0, sx] };
  for (const b of bends) {
    const piv = pose.j[b.bone]; if (!piv || !b.deg) continue;
    const q = axisQ(AX[b.about ?? 'side'], (b.deg * Math.PI) / 180), r = (p) => V.add(piv, qrot(q, V.sub(p, piv)));
    // what hangs on the bone: by the real body's own tree where it knows the joint, by the limbs' names otherwise
    const h = HANGS.find(([re]) => re.test(b.bone)), re = h ? new RegExp(h[1].source.replace('\\.S', `\\.${b.bone.slice(-1)}`)) : null, b0 = B ? B.bi[b.bone] : undefined;
    const under = (nm) => { let p = b0 !== undefined ? B.bi[nm] : undefined; if (p === undefined) return !!re && re.test(nm); p = B.parent[p]; while (p >= 0 && p !== b0) p = B.parent[p]; return p === b0; };
    for (const nm in pose.j) { const base = nm.split(':')[0]; if ((base === b.bone && nm !== b.bone) || under(base)) pose.j[nm] = r(pose.j[nm]); }
    for (const nm in pose.q ?? {}) if (nm === b.bone || under(nm)) pose.q[nm] = qmul(q, pose.q[nm]);
    for (const nm of b.props ?? []) { const pr = pose.props?.[nm]; if (pr) { pr.pos = r(pr.pos); pr.q = qmul(q, pr.q); } }
    if (pose.body) (pose.body.after ??= []).push({ bend: { bone: b.bone, about: b.about ?? 'side', deg: b.deg } });
  }
  return pose;
}
