// The hands of a real body, drawn. A hand's shape alone leaves the back of a closed hand a smooth patch of skin (the
// knuckles of the sculpted body do not stand out); a draughtsman marks them. Here the marks are found from the posed
// hand itself — where each knuckle is, how far it is bent, which way the back of the hand faces — as short strokes in
// the world, inked onto the hand's own skin afterwards (body.js: inkOn).

const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2], sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; }, clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);
const qrot = (Q, b, v) => { const i = b * 4, x = Q[i], y = Q[i + 1], z = Q[i + 2], w = Q[i + 3], tx = 2 * (y * v[2] - z * v[1]), ty = 2 * (z * v[0] - x * v[2]), tz = 2 * (x * v[1] - y * v[0]); return [v[0] + w * tx + y * tz - z * ty, v[1] + w * ty + z * tx - x * tz, v[2] + w * tz + x * ty - y * tx]; };

/** Half the width of each finger at its knuckle (index … little finger), metres on a 1.8 m body. */
const HALF = [0.0068, 0.007, 0.0064, 0.0056];

/**
 * The knuckles of one hand (after poseBody), for an eye at `eye`: a short arc over each of the four, bending round it
 * on the wrist's side, on the skin of the back of the hand. A knuckle shows as far as it is bent (a flat hand has
 * none, a relaxed one a hint, a fist all four) and as far as the back of the hand is turned to the eye.
 * Returns [{ f (1…4: index … little finger), pts: [[x, y, z]…] in the world, alpha }].
 */
export function knuckleMarks(B, s, eye) {
  const F = B.fingers?.[s], w = B.bi?.[`wrist.${s}`]; if (!F || w == null) return [];
  const k = (B.height ?? 1.8) / 1.8, P = (b) => [B.P[b * 3], B.P[b * 3 + 1], B.P[b * 3 + 2]];
  const palm = norm(qrot(B.Q, w, F.palm)), along = norm(qrot(B.Q, w, F.along)), ac = qrot(B.Q, w, F.across), ka = dot(ac, along), across = norm([ac[0] - along[0] * ka, ac[1] - along[1] * ka, ac[2] - along[2] * ka]);
  const out = [];
  for (let f = 1; f <= 4; f++) {
    const ch = F.chains[f]; if (!ch || ch.length < 2) continue;
    const K = P(ch[0]), d = norm(sub(P(ch[1]), K)), bend = Math.atan2(dot(d, palm), dot(d, along));
    const top = [K[0] - palm[0] * 0.007 * k, K[1] - palm[1] * 0.007 * k, K[2] - palm[2] * 0.007 * k], facing = -dot(norm(sub(eye, top)), palm);
    const alpha = clamp01((bend - 0.45) / 0.5) * clamp01((facing - 0.15) / 0.3);
    const r = HALF[f - 1] * k, pts = [];
    for (let a = 20; a <= 160; a += 20) { const c = Math.cos(a * Math.PI / 180) * r * 1.25, sn = Math.sin(a * Math.PI / 180) * r * 0.8; pts.push([top[0] + across[0] * c - along[0] * sn, top[1] + across[1] * c - along[1] * sn, top[2] + across[2] * c - along[2] * sn]); }
    out.push({ f, pts, alpha });
  }
  return out;
}
