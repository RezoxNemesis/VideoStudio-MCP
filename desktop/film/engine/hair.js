// Hair as drawn hair is built: clumps. Each clump is a tapered, slightly thick ribbon that starts on the scalp and
// follows the flow of the cut — off the crown, over the forehead, down the neck — to a point. Clumps ride on the head,
// lag behind it and swing back; long ones hang and are pulled by their own weight. They are meshes like the body, so
// hands, shoulders and blades cover and are covered by them correctly.
import { clamp, rng } from './core.js';
import { onBone, dirOnBone } from './body.js';

/** Ready cuts. Lengths in metres: top, bangs (over the forehead), sides, back; spike 0…1 (stands away from the head); mess 0…1. */
export const HAIRCUTS = {
  bald: null,
  buzz: { top: 0.012, bangs: 0.01, sides: 0.008, back: 0.008, spike: 0.3, mess: 0.1, n: 40, w: 0.03 },
  short: { top: 0.05, bangs: 0.045, sides: 0.035, back: 0.04, spike: 0.25, mess: 0.35, n: 54, w: 0.034 },
  neat: { top: 0.06, bangs: 0.05, sides: 0.03, back: 0.035, spike: 0.05, mess: 0.12, n: 50, w: 0.036, part: 0.6 },
  spiky: { top: 0.1, bangs: 0.085, sides: 0.07, back: 0.09, spike: 0.62, mess: 0.6, n: 60, w: 0.036 },
  shaggy: { top: 0.085, bangs: 0.1, sides: 0.11, back: 0.14, spike: 0.2, mess: 0.55, n: 62, w: 0.036 },
  bob: { top: 0.09, bangs: 0.085, sides: 0.2, back: 0.2, spike: 0.02, mess: 0.15, n: 60, w: 0.04 },
  long: { top: 0.1, bangs: 0.09, sides: 0.42, back: 0.5, spike: 0.02, mess: 0.2, n: 64, w: 0.04 },
  tied: { top: 0.07, bangs: 0.08, sides: 0.06, back: 0.05, spike: 0.08, mess: 0.3, n: 54, w: 0.036, tail: { len: 0.42, w: 0.06, at: 'high' } },
  topknot: { top: 0.05, bangs: 0.03, sides: 0.04, back: 0.04, spike: 0.02, mess: 0.15, n: 48, w: 0.036, tail: { len: 0.16, w: 0.05, at: 'top' } },
};
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k], dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]], len = (a) => Math.hypot(a[0], a[1], a[2]);
const norm = (a) => { const l = len(a) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };

/** Build the clumps of a cut on a body's scalp (rest pose). spec: a name of HAIRCUTS or its fields; seed varies the clumps. */
export function makeHair(B, spec, seed = 1) {
  const cut = typeof spec === 'string' ? HAIRCUTS[spec] : spec && spec.cut ? { ...HAIRCUTS[spec.cut], ...spec } : spec;
  const cap = B.parts.find((p) => p.name === 'haircap'), L = B.landmarks;
  if (!cut || !cap || !L.crown) return null;
  const R = rng(seed * 31 + 7), P0 = cap.rest0 ?? cap.pos, n = cap.n;
  // scalp normals
  const nr = new Float32Array(n * 3);
  for (let t = 0; t < cap.idx.length; t += 3) { const a = cap.idx[t] * 3, b = cap.idx[t + 1] * 3, c = cap.idx[t + 2] * 3, u = [P0[b] - P0[a], P0[b + 1] - P0[a + 1], P0[b + 2] - P0[a + 2]], v = [P0[c] - P0[a], P0[c + 1] - P0[a + 1], P0[c + 2] - P0[a + 2]], x = cross(u, v); for (const q of [a, b, c]) { nr[q] += x[0]; nr[q + 1] += x[1]; nr[q + 2] += x[2]; } }
  const ctr = [0, (L.crown[1] + L.nape[1]) / 2 - 0.005, (L.crown[2] + L.eyeL[2]) / 2 - 0.02];
  const pt = (v) => [P0[v * 3], P0[v * 3 + 1], P0[v * 3 + 2]], nrm = (v) => { let q = norm([nr[v * 3], nr[v * 3 + 1], nr[v * 3 + 2]]); if (dot(q, sub(pt(v), ctr)) < 0) q = mul(q, -1); return q; };
  // roots: spread evenly over the scalp (each new one as far as possible from those chosen)
  const N = Math.min(n, cut.n ?? 56), roots = [], dmin = new Float32Array(n).fill(1e9);
  let cur = 0; { let best = -1e9; for (let v = 0; v < n; v++) if (P0[v * 3 + 1] > best) { best = P0[v * 3 + 1]; cur = v; } }
  for (let i = 0; i < N; i++) { roots.push(cur); const c = pt(cur); let far = -1, fv = 0; for (let v = 0; v < n; v++) { const d = Math.hypot(P0[v * 3] - c[0], P0[v * 3 + 1] - c[1], P0[v * 3 + 2] - c[2]); if (d < dmin[v]) dmin[v] = d; if (dmin[v] > far) { far = dmin[v]; fv = v; } } cur = fv; }
  const whorl = [0.012 * (cut.part ? -Math.sign(cut.part) : 1), L.crown[1] - 0.01, L.crown[2] - 0.01], eyeY = L.eyeL[1], faceZ = L.eyeL[2];
  // the head and what is under it, as shapes hair cannot pass through: the skull (generous), the neck widening into shoulders
  const sk = [0, ctr[1], 0.03], rx = 0.083, ry = 0.12, rz = 0.117, neckY = L.neckY ?? (L.nape[1] - 0.09);
  const outside = (p) => {
    let q = p; const d = [(q[0] - sk[0]) / rx, (q[1] - sk[1]) / ry, (q[2] - sk[2]) / rz], l = len(d);
    if (l < 1.03 && l > 1e-6) { const k = 1.03 / l; q = [sk[0] + d[0] * k * rx, sk[1] + d[1] * k * ry, sk[2] + d[2] * k * rz]; }
    // the face stays clear: nothing but a fringe passes in front of the ears (hair goes back over them)
    if (!cut.overFace) { const f = [(q[0]) / 0.086, (q[1] - (eyeY - 0.035)) / 0.105, (q[2] - (faceZ - 0.02)) / 0.1], fl = len(f); if (fl < 1 && q[1] < eyeY + 0.012) { const sgn = q[0] >= 0 ? 1 : -1; q = [sgn * Math.max(Math.abs(q[0]), 0.086 * Math.sqrt(Math.max(0.05, 1 - f[1] * f[1])) + 0.004), q[1], Math.min(q[2], faceZ - 0.085)]; } }
    if (q[1] < L.nape[1] + 0.01) { const rr = 0.066 + (0.2 - 0.066) * clamp((neckY + 0.015 - q[1]) / 0.08) * (q[1] < neckY + 0.015 ? 1 : 0), hx = q[0], hz = q[2] + 0.02, hl = Math.hypot(hx * 0.8, hz); if (hl < rr) { const k = rr / (hl || 1e-6); q = [hx * k, q[1], hz * k - 0.02]; } }
    return q;
  };
  const clumps = [], S = 8;
  for (const v of roots) {
    const r0 = pt(v), nn = nrm(v);
    const front = clamp((r0[2] - (faceZ - 0.085)) / 0.06), high = clamp((r0[1] - (eyeY + 0.03)) / 0.06), side = clamp(Math.abs(r0[0]) / 0.06), back = clamp((ctr[2] - 0.01 - r0[2]) / 0.05);
    // which length rules here
    const wB = front * high * (1 - side * 0.5), wS = side * (1 - front * 0.4), wK = back * (1 - side * 0.6), wT = Math.max(0.05, 1 - Math.max(wB, wS, wK));
    const ws = wB + wS + wK + wT;
    let lenC = ((cut.bangs ?? 0.06) * wB + (cut.sides ?? 0.05) * wS + (cut.back ?? 0.05) * wK + (cut.top ?? 0.06) * wT) / ws * (0.85 + 0.3 * R());
    // a fringe stops above the eyes unless the cut says otherwise
    if (wB > 0.45 && !cut.overEyes) lenC = Math.min(lenC, Math.max(0.018, (r0[1] - (eyeY + 0.019)) * 1.25));
    // the flow: off the whorl along the scalp; a fringe comes forward and down; a parting throws the top to one side
    let rad = sub(r0, whorl); rad = sub(rad, mul(nn, dot(rad, nn))); rad = len(rad) < 1e-4 ? [0, 0, 1] : norm(rad);
    let flow = add(mul(rad, 1 - 0.5 * wB), [0, -0.55 * (1 - wT * 0.6), 0.5 * wB]);
    if (cut.part) flow = add(flow, [cut.part * 0.9 * wT * (1 - wB * 0.3), 0, 0]);
    flow = add(flow, [0, 0, -0.7 * wS * (1 - wB)]); // at the sides the hair is swept back
    const ms = cut.mess ?? 0.3; flow = add(flow, [(R() - 0.5) * ms * 0.8, (R() - 0.5) * ms * 0.4, (R() - 0.5) * ms * 0.8]);
    flow = sub(flow, mul(nn, dot(flow, nn) * 0.85)); flow = norm(flow);
    const spike = (cut.spike ?? 0.2) * (0.6 + 0.8 * R()) * (1 - 0.6 * wB) * (lenC < 0.16 ? 1 : 0.2);
    const seg = lenC / S, pts = [], wides = [], nrms = [];
    let p = add(r0, mul(nn, -0.004)), dir = norm(add(mul(flow, 1 - spike * 0.75), mul(nn, 0.1 + spike * 1.15)));
    for (let i = 0; i <= S; i++) {
      pts.push(p);
      // weight: the further from the root, the more a clump hangs (stiff spikes hold their line)
      const pull = Math.min(0.9, (seg / 0.03) * 0.42 * (1 - spike * 0.8));
      dir = norm(add(mul(dir, 1 - pull), mul([0, -1, 0], pull)));
      p = outside(add(p, mul(dir, seg)));
    }
    for (let i = 0; i <= S; i++) {
      const tg = norm(sub(pts[Math.min(S, i + 1)], pts[Math.max(0, i - 1)]));
      let out = sub(pts[i], [0, pts[i][1], -0.01]); out = sub(out, mul(tg, dot(out, tg))); out = len(out) < 1e-4 ? nn : norm(out);
      if (i === 0) out = nn;
      const w = norm(cross(tg, out)); wides.push(w); nrms.push(norm(cross(w, tg)));
    }
    clumps.push({ pts, wides, nrms, w: (cut.w ?? 0.036) * (0.85 + 0.4 * R()) * (1 + 0.35 * wB) * (lenC > 0.2 ? 1.5 : 1), len: lenC, th: 0.012 + 0.006 * R(), stiff: 1 + spike * 2, ph: R() * 6.28 });
  }
  // a tail gathered at the back or on top (a strand that swings as one)
  let tail = null;
  if (cut.tail) { const at = cut.tail.at === 'top' ? [0, L.crown[1] + 0.012, L.crown[2] + 0.005] : cut.tail.at === 'low' ? [0, L.nape[1] + 0.03, L.nape[2] - 0.03] : [0, (L.crown[1] + L.nape[1]) / 2 + 0.035, L.nape[2] - 0.035]; tail = { at, len: cut.tail.len, w: cut.tail.w ?? 0.05, dir: cut.tail.at === 'top' ? [0, 1, -0.25] : [0, -0.15, -1], st: null }; }
  return { clumps, tail, cut };
}

/**
 * The hair now: world triangles for the painter. Call after poseBody. dt: seconds since the last call (the clumps lag
 * and swing); wind: (p, t) → m/s or null. Returns [{ pos, idx }] (clumps as one mesh; the tail as another).
 */
export function hairMeshes(H, B, dt, t, wind = null) {
  const out = [], S = 9, pos = new Float32Array(H.clumps.length * S * 4 * 3), idx = [];
  const hp = onBone(B, 'head', [0, B.landmarks.crown[1], 0]);
  // the head's movement since last time: every clump tip is left behind by it, then pulled back
  if (!H.st) H.st = { p: hp, v: [0, 0, 0], off: [0, 0, 0], t };
  const st = H.st, h = clamp(dt, 0, 0.05);
  if (Math.hypot(hp[0] - st.p[0], hp[1] - st.p[1], hp[2] - st.p[2]) > 0.6 || t < st.t) { st.p = hp; st.v = [0, 0, 0]; }
  const wv = wind ? wind(hp, t) : [0, 0, 0];
  for (let a = 0; a < 3; a++) { st.v[a] += ((hp[a] - st.p[a]) * 190 - st.v[a] * 16 + wv[a] * 3.2) * h; st.p[a] += st.v[a] * h; }
  st.t = t; const lag = [st.p[0] - hp[0], st.p[1] - hp[1], st.p[2] - hp[2]]; const ll = Math.hypot(lag[0], lag[1], lag[2]); if (ll > 0.09) { lag[0] *= 0.09 / ll; lag[1] *= 0.09 / ll; lag[2] *= 0.09 / ll; }
  H.clumps.forEach((c, ci) => {
    const flex = clamp(c.len / 0.1, 0.12, 4) / c.stiff * 0.45;
    for (let i = 0; i < S; i++) {
      const u = i / (S - 1), p0 = onBone(B, 'head', c.pts[i]), wd = dirOnBone(B, 'head', c.wides[i]), nn = dirOnBone(B, 'head', c.nrms[i]);
      const sw = u * u * flex, flut = wind ? Math.sin(t * 5.1 + c.ph + u * 3) * 0.004 * u * clamp(Math.hypot(wv[0], wv[2]) / 3) : 0;
      const p = [p0[0] + lag[0] * sw + wd[0] * flut, p0[1] + lag[1] * sw, p0[2] + lag[2] * sw + wd[2] * flut];
      // a leaf-shaped section: widest a third of the way along, a point at the end; thick enough to take light and shadow
      const wv2 = c.w * 0.5 * (u < 0.3 ? 0.72 + 0.28 * (u / 0.3) : Math.pow(1 - (u - 0.3) / 0.7, 0.7)) + 0.0006, th = c.th * (1 - 0.75 * u) + 0.0005, o = (ci * S + i) * 12;
      pos[o] = p[0] - wd[0] * wv2; pos[o + 1] = p[1] - wd[1] * wv2; pos[o + 2] = p[2] - wd[2] * wv2;
      pos[o + 3] = p[0] + nn[0] * th; pos[o + 4] = p[1] + nn[1] * th; pos[o + 5] = p[2] + nn[2] * th;
      pos[o + 6] = p[0] + wd[0] * wv2; pos[o + 7] = p[1] + wd[1] * wv2; pos[o + 8] = p[2] + wd[2] * wv2;
      pos[o + 9] = p[0] - nn[0] * th * 0.4; pos[o + 10] = p[1] - nn[1] * th * 0.4; pos[o + 11] = p[2] - nn[2] * th * 0.4;
      if (i < S - 1) for (let k = 0; k < 4; k++) { const a = (ci * S + i) * 4 + k, b = (ci * S + i) * 4 + (k + 1) % 4, c2 = a + 4, d = b + 4; idx.push(a, c2, b, b, c2, d); }
    }
  });
  out.push({ pos, idx: Uint32Array.from(idx) });
  if (H.tail) {
    const T = H.tail, n = 9, base = onBone(B, 'head', T.at), d0 = dirOnBone(B, 'head', norm(T.dir)), seg = T.len / (n - 1);
    if (!T.st || Math.hypot(T.st[0][0] - base[0], T.st[0][1] - base[1], T.st[0][2] - base[2]) > 0.6) T.st = Array.from({ length: n }, (_, i) => ({ p: [base[0] + d0[0] * seg * i, base[1] + d0[1] * seg * i - 0.02 * i * i * seg, base[2] + d0[2] * seg * i], q: null }));
    const N2 = T.st; N2[0].p = base; N2[0][0] = base[0]; N2[0][1] = base[1]; N2[0][2] = base[2];
    // a hanging thread: each point keeps its speed, falls, is blown, and stays a segment away from the one before
    for (let i = 1; i < n; i++) { const q = N2[i]; if (!q.q) q.q = [...q.p]; const vx = (q.p[0] - q.q[0]) * 0.94, vy = (q.p[1] - q.q[1]) * 0.94, vz = (q.p[2] - q.q[2]) * 0.94; q.q = [...q.p]; const st2 = i < 3 ? 0.35 : 0.06; q.p = [q.p[0] + vx + wv[0] * 0.02 * h * 60 * h + (base[0] + d0[0] * seg * i - q.p[0]) * st2, q.p[1] + vy - 9.81 * h * h * 0.9 + (base[1] + d0[1] * seg * i - q.p[1]) * st2 * 0.5, q.p[2] + vz + wv[2] * 0.02 * h * 60 * h + (base[2] + d0[2] * seg * i - q.p[2]) * st2]; }
    for (let it = 0; it < 4; it++) for (let i = 1; i < n; i++) { const a = N2[i - 1].p, b = N2[i].p, d = sub(b, a), l = len(d) || 1; N2[i].p = add(a, mul(d, seg / l)); }
    const tp = new Float32Array(n * 6 * 3), ti = [];
    for (let i = 0; i < n; i++) {
      const p = N2[i].p, a = N2[Math.max(0, i - 1)].p, b = N2[Math.min(n - 1, i + 1)].p, ax = norm(sub(b, a)); let e1 = cross(ax, [0, 1, 0]); e1 = len(e1) < 0.05 ? [1, 0, 0] : norm(e1); const e2 = cross(ax, e1), u = i / (n - 1), r = T.w * 0.5 * (u < 0.15 ? 0.55 + 3 * u : Math.pow(1 - (u - 0.15) / 0.85, 0.6)) + 0.001;
      for (let k = 0; k < 6; k++) { const an = (k / 6) * Math.PI * 2, o = (i * 6 + k) * 3; tp[o] = p[0] + (e1[0] * Math.cos(an) + e2[0] * Math.sin(an) * 0.7) * r; tp[o + 1] = p[1] + (e1[1] * Math.cos(an) + e2[1] * Math.sin(an) * 0.7) * r; tp[o + 2] = p[2] + (e1[2] * Math.cos(an) + e2[2] * Math.sin(an) * 0.7) * r; }
      if (i < n - 1) for (let k = 0; k < 6; k++) { const a2 = i * 6 + k, b2 = i * 6 + (k + 1) % 6, c2 = a2 + 6, d2 = b2 + 6; ti.push(a2, c2, b2, b2, c2, d2); }
    }
    out.push({ pos: tp, idx: Uint32Array.from(ti) });
  }
  return out;
}
