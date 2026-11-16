// Clothes that are cut, not painted on.
// The body's own garments lie on its skin: its trousers follow thigh, knee and calf like leggings. A design can ask for a
// cut — design.bottom.fit: 'slim' | 'straight' | 'loose' — and the trouser legs are re-cut in the body's rest shape (so
// they bend with the knees as before): from the middle of the thigh the cloth falls in a nearly straight line to a hem
// far wider than the ankle. Nothing is moved at the seat, the hip and the waistband, and the two legs never run into each other.
// Without a fit the garment is the body's own again. (Rules: devtests/tailor.mjs)

// thigh: how much wider than the leg's own widest; hem: the hem's radius in metres on a 1.8 m body
export const FITS = { slim: { thigh: 1.0, hem: 0.058 }, straight: { thigh: 1.08, hem: 0.075 }, loose: { thigh: 1.22, hem: 0.095 } };
const smooth = (k) => { k = Math.max(0, Math.min(1, k)); return k * k * (3 - 2 * k); };

/** Re-cut a body's cloth for a design (once for each cut; the body keeps its own cut to go back to). */
export function tailorBody(B, d) {
  const fit = d?.bottom?.fit && FITS[d.bottom.fit] ? d.bottom.fit : null, key = String(fit);
  if (!B || B.tailored === key) return B;
  for (const p of B.parts) { if (p.material !== 'cloth') continue; if (!p.cut0) p.cut0 = Float32Array.from(p.pos); else p.pos.set(p.cut0); }
  if (fit) cutLegs(B, FITS[fit]);
  B.tailored = key;
  return B;
}

function cutLegs(B, F) {
  const p = B.parts.find((q) => q.material === 'cloth' && (q.name === 'trousers' || q.name === 'pants')), H = B.head, bi = B.bi; if (!p || !H) return;
  const hl = bi['upperleg01.L'], hr = bi['upperleg01.R'], fl = bi['foot.L'], fr = bi['foot.R']; if ([hl, hr, fl, fr].some((b) => b === undefined)) return;
  let top = 0; for (let b = 0; b < H.length / 3; b++) top = Math.max(top, H[b * 3 + 1]);
  const k = (B.height || top * 1.07) / 1.8, cx = (H[hl * 3] + H[hr * 3]) / 2, P = p.pos, n = P.length / 3, gap = 0.006 * k, STEP = 0.02 * k;
  for (const [hb, fb] of [[hl, fl], [hr, fr]]) {
    const hipY = H[hb * 3 + 1], footY = H[fb * 3 + 1], len = hipY - footY, sx = H[hb * 3] >= cx ? 1 : -1; if (!(len > 0)) continue;
    const mine = (v) => (P[v * 3] - cx) * sx >= 0, u = (v) => (hipY - P[v * 3 + 1]) / len;
    // the leg of the trousers in slices by height: where its middle is and how far the cloth stands from it
    // (cloth close to the middle line between the legs — the seat's seam — belongs to neither leg's round)
    const nb = Math.ceil((len * 1.15) / STEP) + 1, bin = (v) => Math.max(0, Math.min(nb - 1, Math.round((hipY - P[v * 3 + 1]) / STEP))), sum = Array.from({ length: nb }, () => [0, 0, 0]);
    for (let v = 0; v < n; v++) if (mine(v) && (P[v * 3] - cx) * sx > 0.025 * k) { const b = sum[bin(v)]; b[0] += P[v * 3]; b[1] += P[v * 3 + 2]; b[2]++; }
    const mid = sum.map((b) => (b[2] ? [b[0] / b[2], b[1] / b[2]] : null)), rad = new Float32Array(nb), cnt = new Float32Array(nb);
    for (let v = 0; v < n; v++) if (mine(v) && (P[v * 3] - cx) * sx > 0.025 * k) { const b = bin(v), m = mid[b]; if (m) { rad[b] += Math.hypot(P[v * 3] - m[0], P[v * 3 + 2] - m[1]); cnt[b]++; } }
    for (let b = 0; b < nb; b++) rad[b] = cnt[b] ? rad[b] / cnt[b] : 0;
    // smoothed along the leg, gaps filled from the nearest slice that has cloth
    const near = (b, f) => { for (let d = 0; d < nb; d++) for (const q of [b - d, b + d]) if (q >= 0 && q < nb && f(q)) return q; return -1; };
    const M = [], R = new Float32Array(nb); for (let b = 0; b < nb; b++) { let x = 0, z = 0, r = 0, w = 0; for (let d = -2; d <= 2; d++) { const q = near(Math.max(0, Math.min(nb - 1, b + d)), (i) => !!mid[i] && rad[i] > 0); if (q < 0) continue; x += mid[q][0]; z += mid[q][1]; r += rad[q]; w++; } M.push(w ? [x / w, z / w] : [cx + sx * 0.1 * k, 0]); R[b] = w ? r / w : 0; }
    const at = (y) => { const f = Math.max(0, Math.min(nb - 1, (hipY - y) / STEP)), b0 = Math.floor(f), b1 = Math.min(nb - 1, b0 + 1), t = f - b0; return { mx: M[b0][0] + (M[b1][0] - M[b0][0]) * t, mz: M[b0][1] + (M[b1][1] - M[b0][1]) * t, r: R[b0] + (R[b1] - R[b0]) * t }; };
    // from the middle of the thigh a straight line down to the hem
    let thigh = 0; { const y0 = hipY - 0.35 * len, S = at(y0); for (let v = 0; v < n; v++) if (mine(v) && Math.abs(P[v * 3 + 1] - y0) < 0.05 * len && (P[v * 3] - cx) * sx > 0.025 * k) thigh = Math.max(thigh, Math.hypot(P[v * 3] - S.mx, P[v * 3 + 2] - S.mz)); } // (its widest there)
    if (!(thigh > 0)) continue;
    const r0 = thigh * F.thigh, r1 = F.hem * k;
    for (let v = 0; v < n; v++) {
      if (!mine(v)) continue; const uu = u(v); if (uu <= 0.18) continue;
      const S = at(P[v * 3 + 1]); if (!(S.r > 1e-5)) continue;
      // every bit of cloth nearer the leg's middle than the cut allows is brought out to the cut: a clean round leg
      const want = r0 + (r1 - r0) * Math.max(0, Math.min(1, (uu - 0.35) / 0.62)), dx = P[v * 3] - S.mx, dz = P[v * 3 + 2] - S.mz, rv = Math.hypot(dx, dz); if (rv < 1e-6) continue;
      const s = (rv + (Math.max(rv, want) - rv) * smooth((uu - 0.18) / 0.17)) / rv;
      let x = S.mx + dx * s; const z = S.mz + dz * s;
      if ((x - cx) * sx < gap) x = cx + sx * gap; // the inseam: the two legs meet, they do not cross
      P[v * 3] = x; P[v * 3 + 2] = z;
    }
  }
}
