// Styled hair: node devtests/hairstyle.mjs        (exit 1 when a rule is broken)
// A cut can be designed lock by lock: each lock is laid over the real head from one place on it to another (a curtain
// of fringe from the parting to the temple, a lock hanging in front of the ear), hair swept back runs to its tie, a
// tail is a sheaf of locks with tips of their own, a cord has ends that hang — and every lock knows which way is
// across it, so it can be painted with strands.
import fs from 'node:fs';
import * as HAIR from '../engine/hair.js';

const { makeHair, hairMeshes } = HAIR;
const fails = []; let checks = 0;
const ok = (cond, what, got) => { checks++; if (!cond) fails.push(`${what} — got ${typeof got === 'number' ? got.toFixed(4) : JSON.stringify(got)}`); };
const FX = JSON.parse(fs.readFileSync(new URL('./fixtures/head_mesh.json', import.meta.url), 'utf8'));
const sp = Float32Array.from(FX.skin.pos), cp = Float32Array.from(FX.cap.pos);
const B = { parts: [{ name: 'haircap', material: 'hair', pos: cp, rest0: cp, idx: Uint32Array.from(FX.cap.idx), n: cp.length / 3 }, { name: 'skin', material: 'skin', pos: sp, rest0: sp, idxHead: Uint32Array.from(FX.skin.idx), n: sp.length / 3 }],
  landmarks: FX.landmarks, bi: { head: 0 }, R: Float32Array.from([1, 0, 0, 0, 1, 0, 0, 0, 1]), T: new Float32Array(3) };
const L = FX.landmarks, D = Math.PI / 180;
const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
const TRIS = [[sp, FX.skin.idx], [cp, FX.cap.idx]];
/** how far a point is from the head's skin and scalp (the nearest place on their triangles) */
const off = (p) => {
  let m = 1e9;
  for (const [P, I] of TRIS) for (let t = 0; t + 2 < I.length; t += 3) {
    const a = I[t] * 3, b = I[t + 1] * 3, c = I[t + 2] * 3, ax = P[a], ay = P[a + 1], az = P[a + 2];
    const e0 = [P[b] - ax, P[b + 1] - ay, P[b + 2] - az], e1 = [P[c] - ax, P[c + 1] - ay, P[c + 2] - az], d = [ax - p[0], ay - p[1], az - p[2]];
    const A = e0[0] * e0[0] + e0[1] * e0[1] + e0[2] * e0[2], Bq = e0[0] * e1[0] + e0[1] * e1[1] + e0[2] * e1[2], C = e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2], Dq = e0[0] * d[0] + e0[1] * d[1] + e0[2] * d[2], E = e1[0] * d[0] + e1[1] * d[1] + e1[2] * d[2];
    const det = A * C - Bq * Bq; if (det < 1e-18) continue;
    let u = (Bq * E - C * Dq) / det, v = (Bq * Dq - A * E) / det;
    if (u < 0 || v < 0 || u + v > 1) { // outside the triangle: the nearest place on its edges
      let best = 1e9; for (const [o, e] of [[[ax, ay, az], e0], [[ax, ay, az], e1], [[P[b], P[b + 1], P[b + 2]], [P[c] - P[b], P[c + 1] - P[b + 1], P[c + 2] - P[b + 2]]]]) { const l2 = e[0] * e[0] + e[1] * e[1] + e[2] * e[2] || 1, k = Math.max(0, Math.min(1, ((p[0] - o[0]) * e[0] + (p[1] - o[1]) * e[1] + (p[2] - o[2]) * e[2]) / l2)), q = Math.hypot(o[0] + e[0] * k - p[0], o[1] + e[1] * k - p[1], o[2] + e[2] * k - p[2]); if (q < best) best = q; }
      if (best < m) m = best; continue;
    }
    const q = Math.hypot(d[0] + e0[0] * u + e1[0] * v, d[1] + e0[1] * u + e1[1] * v, d[2] + e0[2] * u + e1[2] * v); if (q < m) m = q;
  }
  return m;
};

// ---- 1. the head as hair meets it
const HS = typeof HAIR.headSurface === 'function' ? HAIR.headSurface(B) : null;
ok(!!HS, 'headSurface(body) gives the head as hair meets it', typeof HAIR.headSurface);
if (HS) {
  const spots = [[0, 20], [0, 55], [40, 10], [-40, 10], [90, 20], [-90, 20], [180, 10], [140, 40], [0, 88], [60, -20], [-60, -20]];
  const worst = Math.max(...spots.map(([az, el]) => off(HS.at(az * D, el * D, 0))));
  ok(worst < 0.008, 'a place on it (so far round from straight ahead, so far up) lies on the real head — forehead, temples, crown, back, cheeks', worst);
  const fore = HS.at(0, 20 * D, 0), left = HS.at(90 * D, 20 * D, 0), back = HS.at(180 * D, 10 * D, 0);
  ok(fore[2] > L.eyeL[2] - 0.02 && Math.abs(fore[0]) < 0.004 && fore[1] > L.eyeL[1] + 0.02, 'straight ahead and up a little is the forehead', fore);
  ok(left[0] > 0.06 && back[2] < -0.05 && back[2] < HS.c[2] - 0.08, 'a quarter turn round is the head\'s own left side (the side of its left eye); half a turn is the back', [left[0], back[2]]);
  const lifted = HS.at(30 * D, 30 * D, 0.02), on = HS.at(30 * D, 30 * D, 0);
  ok(Math.abs(dist(lifted, on) - 0.02) < 0.004, 'lifted off it by so much, a place stands that far out from the head', dist(lifted, on));
}

// ---- 2. a designed lock: from the parting, over the forehead, to the temple
const pts = (c) => c.pts;
{
  const H = makeHair(B, { cut: 'buzz', n: 20, locks: [{ from: [-8, 62], to: [52, 2], lift: 0.012, w: 0.034 }] }, 3);
  const lock = H?.clumps?.find((c) => c.lock);
  ok(!!lock, 'a cut with `locks` gets each as a clump of its own', H?.clumps?.length);
  if (lock && HS) {
    const P = pts(lock), root = HS.at(-8 * D, 62 * D, 0), tip = HS.at(52 * D, 2 * D, 0.012);
    ok(dist(P[0], root) < 0.012, 'it starts where it is rooted', dist(P[0], root));
    ok(dist(P[P.length - 1], tip) < 0.015, 'and ends where it is led to', dist(P[P.length - 1], tip));
    const offs = P.slice(1).map(off);
    ok(Math.min(...offs) > 0.004 && Math.max(...offs) < 0.03, 'between them it lies over the head: off the skin, never in it and never far from it', [Math.min(...offs), Math.max(...offs)]);
    const turns = P.slice(1, -1).map((p, i) => { const a = [p[0] - P[i][0], p[1] - P[i][1], p[2] - P[i][2]], b = [P[i + 2][0] - p[0], P[i + 2][1] - p[1], P[i + 2][2] - p[2]]; return Math.acos(Math.max(-1, Math.min(1, (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (Math.hypot(...a) * Math.hypot(...b) || 1)))) / D; });
    ok(Math.max(...turns) < 40, 'in one sweep, without a kink', Math.max(...turns));
    ok(Math.abs(lock.w - 0.034) < 1e-6 && lock.len > 0.1, 'as wide as it is designed, as long as its way over the head', [lock.w, lock.len]);
    // it begins as a stroke begins: from a point (a blunt root would stand on the hairline like a step)
    const m = hairMeshes(H, B, 1 / 24, 0, null)[0], ci = H.clumps.indexOf(lock), wAt = (i) => { const o = (ci * 9 + i) * 12; return Math.hypot(m.pos[o] - m.pos[o + 6], m.pos[o + 1] - m.pos[o + 7], m.pos[o + 2] - m.pos[o + 8]); }, ws = [0, 1, 2, 3, 4, 5, 6, 7, 8].map(wAt);
    ok(ws[0] < 0.25 * Math.max(...ws) && Math.abs(Math.max(...ws) - 0.034) < 0.004, 'it begins from a point and is as wide as designed where it is fullest', [ws[0], Math.max(...ws)]);
  }
}

// ---- 3. a lock that hangs: past where it is led to, it falls
{
  const H = makeHair(B, { cut: 'buzz', n: 20, locks: [{ from: [62, 40], to: [72, -8], hang: 0.16, lift: 0.01, w: 0.022 }] }, 3);
  const lock = H?.clumps?.find((c) => c.lock);
  if (lock && HS) {
    const P = pts(lock), led = HS.at(72 * D, -8 * D, 0.01), tip = P[P.length - 1];
    ok(led[1] - tip[1] > 0.16 * 0.8, 'a lock with `hang` falls that much further, straight down from where it leaves the head', led[1] - tip[1]);
    ok(Math.hypot(tip[0] - led[0], tip[2] - led[2]) < 0.05, 'beside the face, not into it or away from it', Math.hypot(tip[0] - led[0], tip[2] - led[2]));
    ok(P.every((p) => off(p) > 0.003), 'and never passes through the head', Math.min(...P.map(off)));
  } else ok(false, 'a lock with `hang` is made', !!lock);
}

// ---- 4. hair swept back runs to its tie
{
  const H = makeHair(B, { cut: 'tied', flow: 'tie', n: 90, tail: { len: 0.6, w: 0.07, at: 'high', tie: true } }, 3);
  const base = (H?.clumps ?? []).filter((c) => !c.lock), tie = H?.tail?.at;
  ok(base.length >= 60 && !!tie, 'a tied cut still has its clumps and its tail', base.length);
  if (tie) {
    const ends = base.map((c) => dist(c.pts[c.pts.length - 1], tie));
    const near = ends.filter((d) => d < 0.045).length / ends.length;
    ok(near > 0.9, 'with flow: \'tie\' the hair of the whole scalp is drawn back to the tie (nine clumps in ten end at it)', near);
    const fringe = base.filter((c) => c.pts[0][2] > L.eyeL[2] - 0.07 && c.pts[0][1] > L.eyeL[1] + 0.05);
    ok(fringe.length > 3 && fringe.every((c) => c.pts[c.pts.length - 1][2] < c.pts[0][2] - 0.03), 'the hair of the brow goes back over the head, not down over the face', fringe.length);
    ok(base.every((c) => c.pts.every((p) => off(p) > 0.001 || dist(p, c.pts[0]) < 0.01)), 'it lies over the head all the way', 0);
    // the scalp's own edge (a cap cut out of the body: a staircase) is covered: a clump starts at every corner of it
    const cnt = new Map(), I = FX.cap.idx, key = (a, b) => (a < b ? a * 1e6 + b : b * 1e6 + a);
    for (let t = 0; t < I.length; t += 3) for (let e = 0; e < 3; e++) { const k = key(I[t + e], I[t + (e + 1) % 3]); cnt.set(k, (cnt.get(k) ?? 0) + 1); }
    const rim = new Set(); for (const [k, n] of cnt) if (n === 1) { rim.add(Math.floor(k / 1e6)); rim.add(k % 1e6); }
    const bare = [...rim].filter((v) => { const p = [cp[v * 3], cp[v * 3 + 1], cp[v * 3 + 2]]; return !base.some((c) => dist(c.pts[0], p) < 0.014); }).length;
    ok(rim.size > 20 && bare === 0, 'the hairline is hair too: a clump starts at every corner of the scalp\'s edge, so the edge itself never shows', [rim.size, bare]);
    // drawn back, a clump begins as a stroke begins: from a point (a blunt root would stand along the hairline like a row of bricks)
    const m = hairMeshes(H, B, 1 / 24, 0, null)[0], ci = H.clumps.findIndex((c) => c.back), wAt = (i) => { const o = (ci * 9 + i) * 12; return Math.hypot(m.pos[o] - m.pos[o + 6], m.pos[o + 1] - m.pos[o + 7], m.pos[o + 2] - m.pos[o + 8]); };
    const widths = [0, 1, 2, 3, 4, 5, 6, 7, 8].map(wAt), hAt = (i) => { const o = (ci * 9 + i) * 12; return Math.hypot(m.pos[o + 3] - m.pos[o + 9], m.pos[o + 4] - m.pos[o + 10], m.pos[o + 5] - m.pos[o + 11]); };
    ok(widths[0] < 0.25 * Math.max(...widths) && hAt(0) < 0.4 * Math.max(...[1, 2, 3].map(hAt)), 'hair drawn back begins from a point: thin and flat at its root, full a little way along', [widths[0] / Math.max(...widths), hAt(0) / Math.max(...[1, 2, 3].map(hAt))]);
  }
}

// ---- 5. a tail of many locks
{
  const H = makeHair(B, { cut: 'tied', tail: { len: 0.6, w: 0.08, at: 'high', tie: true, locks: 7 } }, 3);
  const ms = hairMeshes(H, B, 1 / 24, 0, null), tails = ms.filter((m) => m.tail);
  ok(tails.length === 7, 'tail.locks: 7 makes the tail a sheaf of seven locks', tails.length);
  if (tails.length === 7) {
    const low = tails.map((m) => { let y = 1e9; for (let i = 1; i < m.pos.length; i += 3) y = Math.min(y, m.pos[i]); return y; });
    ok(Math.max(...low) - Math.min(...low) > 0.05, 'whose tips end at different lengths', Math.max(...low) - Math.min(...low));
    const at = H.tail.at, gathered = tails.every((m) => { let d = 1e9; for (let i = 0; i < m.pos.length; i += 3) d = Math.min(d, dist([m.pos[i], m.pos[i + 1], m.pos[i + 2]], at)); return d < 0.05; });
    ok(gathered, 'all gathered at the tie', gathered);
    ok(tails.every((m) => m.u && m.u.length === m.pos.length / 3), 'each with its across-coordinate for strands', tails.map((m) => !!m.u));
  }
}

// ---- 5b. tied where the design says
{
  const H = makeHair(B, { cut: 'tied', tail: { len: 0.6, w: 0.08, at: [180, 48], up: 0.4 } }, 3);
  if (HS) {
    const want = HS.at(180 * D, 48 * D, 0), got = H?.tail?.at ?? [9, 9, 9];
    ok(dist(want, got) < 0.02 && dist(got, HS.c) > dist(want, HS.c), 'tail.at: [round, up] ties the tail at that place on the head, just clear of it', dist(want, got));
    ok(H?.tail?.dir?.[1] > 0.3, 'tail.up: how steeply it leaves the tie (it rises before it falls)', H?.tail?.dir);
  }
}

// ---- 6. the cord's ends
{
  const H = makeHair(B, { cut: 'tied', tail: { len: 0.6, w: 0.08, at: 'high', tie: { ends: 2, len: 0.22 } } }, 3);
  let ms = null; for (let f = 0; f < 30; f++) ms = hairMeshes(H, B, 1 / 24, f / 24, null);
  const cords = ms.filter((m) => m.tie);
  ok(cords.length === 3, 'a tie with ends: 2 is a band and two cord ends', cords.length);
  if (cords.length === 3) {
    const at = H.tail.at, drop = cords.slice(1).map((m) => { let y = 1e9; for (let i = 1; i < m.pos.length; i += 3) y = Math.min(y, m.pos[i]); return at[1] - y; });
    ok(drop.every((d) => d > 0.22 * 0.6 && d < 0.22 * 1.5), 'the ends hang down from the tie by about their length', drop);
  }
}

// ---- 6b. on a sheaf of locks the cord is wound where they are gathered
{
  const H = makeHair(B, { cut: 'tied', tail: { len: 0.7, w: 0.085, at: [180, 46], up: 0.45, tie: true, locks: 8 } }, 3);
  let ms = null; for (let f = 0; f < 20; f++) ms = hairMeshes(H, B, 1 / 24, f / 24, null);
  const band = ms.find((m) => m.tie), at = H.tail.at, seg = 0.7 / 8;
  if (band) {
    let far = 0, wide = 0; const c = [0, 0, 0], n = band.pos.length / 3;
    for (let i = 0; i < band.pos.length; i += 3) { far = Math.max(far, dist([band.pos[i], band.pos[i + 1], band.pos[i + 2]], at)); c[0] += band.pos[i] / n; c[1] += band.pos[i + 1] / n; c[2] += band.pos[i + 2] / n; }
    for (let i = 0; i < band.pos.length; i += 3) wide = Math.max(wide, dist([band.pos[i], band.pos[i + 1], band.pos[i + 2]], c));
    ok(far < seg * 1.15, 'the band lies at the tie itself, within the first span of the tail', far / seg);
    ok(wide < 0.085 * 0.62, 'drawn tight round the gathered hair (narrower than the tail at its fullest)', wide / 0.085);
  } else ok(false, 'a sheaf tail tied with a cord has its band', !!band);
}

// ---- 6c. a tail tied high leaves its tie in a curve, not as a rod with a knee in it
{
  const H = makeHair(B, { cut: 'tied', tail: { len: 0.72, w: 0.085, at: [180, 46], up: 0.45, tie: true, locks: 8 } }, 3);
  for (let f = 0; f < 60; f++) hairMeshes(H, B, 1 / 24, f / 24, null);
  const P = H.tail.st.map((q) => q.p), bends = [];
  for (let i = 1; i < P.length - 1; i++) { const a = [P[i][0] - P[i - 1][0], P[i][1] - P[i - 1][1], P[i][2] - P[i - 1][2]], b = [P[i + 1][0] - P[i][0], P[i + 1][1] - P[i][1], P[i + 1][2] - P[i][2]]; bends.push(Math.acos(Math.max(-1, Math.min(1, (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (Math.hypot(...a) * Math.hypot(...b))))) / D); }
  ok(Math.max(...bends) < 42, 'hanging at rest, no two spans of the tail meet at a knee (the sharpest bend is gentle)', bends.map(Math.round));
  ok(P[1][1] > P[0][1] + 0.01 && P[P.length - 1][1] < P[0][1] - 0.4, 'it still rises from the tie before it falls, and hangs its length', [P[1][1] - P[0][1], P[0][1] - P[P.length - 1][1]]);
  // it lies on the back of the head, never in it
  if (HS) { const inside = P.slice(1).map((q) => dist(q, HS.c) - HS.r(Math.atan2(q[0] - HS.c[0], q[2] - HS.c[2]), Math.asin(Math.max(-1, Math.min(1, (q[1] - HS.c[1]) / dist(q, HS.c)))))).filter((d, i) => P[i + 1][1] > HS.c[1] - 0.09); ok(Math.min(...inside) > -0.003, 'and passes round the back of the head, not through it', Math.min(...inside)); }
  // the same at any number of pictures a second
  const H60 = makeHair(B, { cut: 'tied', tail: { len: 0.72, w: 0.085, at: [180, 46], up: 0.45, tie: true, locks: 8 } }, 3);
  for (let f = 0; f < 150; f++) hairMeshes(H60, B, 1 / 60, f / 60, null);
  const P60 = H60.tail.st.map((q) => q.p), apart = Math.max(...P.map((q, i) => dist(q, P60[i])));
  ok(apart < 0.03, 'drawn at 24 or at 60 pictures a second it comes to rest in the same place', apart);
}

// ---- 6d. walking, the tail trails down the back: it sways, it does not fly up, and it never passes through the back
{
  // a body to walk with: the same head on a spine, and a back (a slab of cloth 11 cm behind the middle of the body)
  const slab = []; for (let y = 0.9; y <= 1.6; y += 0.05) for (let x = -0.2; x <= 0.2; x += 0.05) slab.push(x, y, -0.11);
  const SP = Float32Array.from(slab);
  const W = { parts: [...B.parts, { name: 'tunic', material: 'cloth', pos: SP, rest0: SP, n: SP.length / 3 }], landmarks: { ...L, hipY: 0.957 }, bi: { head: 0, spine01: 1 },
    head: Float32Array.from([0, 1.641, 0.052, 0, 1.318, -0.055]), R: Float32Array.from([1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 1]), T: new Float32Array(6) };
  const H = makeHair(W, { cut: 'tied', tail: { len: 0.72, w: 0.085, at: [180, 46], up: 0.45, tie: true, locks: 8 } }, 3);
  let lowest = 1e9, deepest = 1e9, high = -1e9;
  for (let f = 0; f < 120; f++) {
    const t = f / 24, z = 1.3 * t, y = 0.02 * Math.sin(2 * Math.PI * 1.9 * t);
    W.T.set([0, y, z, 0, y, z]);
    hairMeshes(H, W, 1 / 24, t, null);
    if (f < 30) continue; // (he starts from standing in one step: the swing of that start is let die away)
    const P = H.tail.st.map((q) => q.p), tie = P[0], tip = P[P.length - 1];
    lowest = Math.min(lowest, (tie[1] - tip[1]) / 0.72);
    for (const q of P.slice(3)) { if (q[1] - y < 1.58 && q[1] - y > 0.95) deepest = Math.min(deepest, (-0.11) - (q[2] - z)); high = Math.max(high, q[1] - tie[1]); }
  }
  ok(lowest > 0.6, 'at a walk the tail hangs down the back all the way (its tip stays more than 0.6 of its length below the tie)', lowest);
  ok(high < 0.05, 'none of it flies up over the tie', high);
  ok(deepest > 0.0, 'and it stays behind the back, never in it', deepest);
}

// ---- 7. every lock knows which way is across it
{
  const H = makeHair(B, { cut: 'short' }, 3), m = hairMeshes(H, B, 1 / 24, 0, null)[0];
  ok(m.u && m.u.length === m.pos.length / 3, 'the clumps come with one number per point: where it lies across its lock', !!m.u);
  if (m.u) {
    let lo = 1e9, hi = -1e9; for (const v of m.u) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
    ok(lo === -1 && hi === 1, 'from −1 at one edge to 1 at the other', [lo, hi]);
    // the ridge of a lock (its second point of four) is its middle
    ok(Math.abs(m.u[1]) < 1e-6 && m.u[0] === -1 && m.u[2] === 1, 'the ridge of a lock is its middle (0)', [m.u[0], m.u[1], m.u[2]]);
  }
}

// ---- 8. hair painted with strands (the brush of a drawn head of hair)
{
  const BD = await import('../engine/bodydraw.js'), { CelPainter } = await import('../engine/body.js'), { makeCam } = await import('../engine/camera.js'), { setLook } = await import('../engine/look.js');
  setLook('anime');
  const rgb = (c) => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
  const plain = typeof BD.hairPaint === 'function' ? BD.hairPaint({ hair: { color: '#3a281e' } }, rgb) : null, st = typeof BD.hairPaint === 'function' ? BD.hairPaint({ hair: { color: '#3a281e', strands: 0.8, sheen: 1, shine: '#c9a37a' } }, rgb) : null;
  ok(!!plain && !plain.bands && plain.hairy === true, 'hairPaint(design): hair without `strands` is painted as before, in its own tones', plain && Object.keys(plain));
  ok(!!st && Array.isArray(st.bands) && st.bands.length >= 3, 'hair.strands lays strands along every lock: darker ones and a lighter streak', st?.bands?.length);
  if (st?.bands) {
    // one lock, flat on to the lens: a ribbon 4 cm wide and 20 cm long, its points numbered across from −1 to 1
    const N = 9, pos = [], u = [], idx = [], nrmFlat = [], nrmBent = [];
    for (let i = 0; i < N; i++) { const y = 1.7 - 0.2 * i / (N - 1); for (const [x, uu] of [[-0.02, -1], [0, 0], [0.02, 1]]) { pos.push(x, y, 0); u.push(uu); nrmFlat.push(0, 0.3, 0.954); const b = (1 - i / (N - 1)) * 1.0; nrmBent.push(0, Math.sin(b), Math.cos(b)); } } // (bent: its top turned up to the sky, its foot flat on to the lens)
    for (let i = 0; i < N - 1; i++) for (let k = 0; k < 2; k++) { const a = i * 3 + k, b = a + 1, c = a + 3, d = c + 1; idx.push(a, c, b, b, c, d); }
    const cam = makeCam({ pos: [0, 1.6, 0.8], target: [0, 1.6, 0], fov: 20 }, 240, 240);
    const paintIt = (paint, nrm, light = [0, 0.6, 0.8]) => { const cpn = new CelPainter(cam, { ss: 2, light, lines: false, shadows: false }); cpn.add(Float32Array.from(pos), Uint32Array.from(idx), paint, Float32Array.from(nrm), Float32Array.from(u)); const r = cpn.finish(), g = r.canvas.getContext('2d'); return (x, y) => { const q = cam.project([x, y, 0]); return Array.from(g.getImageData(Math.round(q[0] - r.x), Math.round(q[1] - r.y), 1, 1).data); }; };
    const at = paintIt(st, nrmFlat), tones = new Set(), key = (p4) => p4.slice(0, 3).map((v) => Math.round(v / 6)).join(',');
    for (let x = -0.018; x <= 0.018; x += 0.0006) tones.add(key(at(x, 1.6)));
    ok(tones.size >= 3, 'across a lock there are at least three tones: its own, a darker strand, a lighter streak', tones.size);
    const down = new Set(); for (let y = 1.68; y >= 1.52; y -= 0.01) down.add(key(at(0.012, y)));
    ok(down.size === 1, 'and they run along it: down the lock, the same place across it keeps its tone', down.size);
    const lum = (p4) => p4[0] + p4[1] + p4[2], base = paintIt(plain, nrmFlat);
    let dark = 0, light = 0; for (let x = -0.018; x <= 0.018; x += 0.0006) { const d = lum(at(x, 1.6)) - lum(base(x, 1.6)); if (d < -6) dark++; if (d > 6) light++; }
    ok(dark >= 2 && light >= 2, 'both ways from the hair\'s own tone: some of the lock is darker than plain hair, some lighter', [dark, light]);
    // the sheen: a band of light across hair that is turned to the lamp — with hair.sheen it is higher on the ridge of a lock than at its edges (a zigzag from lock to lock)
    const bent = paintIt(st, nrmBent, [0, 0.949, 0.316]), top = (x) => { let best = -1, by = 0; for (let y = 1.69; y >= 1.51; y -= 0.002) { const l = lum(bent(x, y)); if (l > best + 1) { best = l; by = y; } } return by; };
    const ridge = top(0.001), edge = top(0.0185);
    ok(Math.abs(ridge - edge) > 0.006, 'the sheen stands at another height on the ridge of a lock than at its edge', [ridge, edge]);
  }
}

// ---- a ribbon tied in a bow: a loop to each side of the knot (a child's high tail)
{
  const mk = (tie) => { const H = makeHair(B, { cut: 'tied', flow: 'tie', tail: { len: 0.3, w: 0.07, at: [180, 52], up: 0.5, locks: 7, tie } }, 3); let ms = null; for (let f = 0; f < 20; f++) ms = hairMeshes(H, B, 1 / 24, f / 24, null); return { H, ms }; };
  const { H, ms } = mk({ ends: 2, len: 0.08, bow: 0.13 }), loops = ms.filter((m) => m.bow), knot = H.tail.at;
  ok(loops.length === 2 && loops.every((m) => m.tie), 'a tie with bow: 0.13 has two loops, painted as the ribbon', loops.length);
  if (loops.length === 2) {
    const ext = loops.map((m) => { let lo = 1e9, hi = -1e9, ylo = 1e9, yhi = -1e9; for (let i = 0; i < m.pos.length; i += 3) { lo = Math.min(lo, m.pos[i]); hi = Math.max(hi, m.pos[i]); ylo = Math.min(ylo, m.pos[i + 1]); yhi = Math.max(yhi, m.pos[i + 1]); } return { lo, hi, mid: (lo + hi) / 2, tall: yhi - ylo }; });
    ok(Math.sign(ext[0].mid - knot[0]) === -Math.sign(ext[1].mid - knot[0]) && ext.every((e) => Math.abs(e.mid - knot[0]) > 0.02), 'one loop to each side of the knot', ext.map((e) => e.mid - knot[0]));
    const span = Math.max(ext[0].hi, ext[1].hi) - Math.min(ext[0].lo, ext[1].lo);
    ok(span > 0.13 * 0.8 && span < 0.13 * 1.25, 'the bow is as wide as it is asked to be', span);
    ok(ext.every((e) => e.tall > 0.13 * 0.25 && e.tall < 0.13 * 0.75), 'each loop is a broad band of ribbon standing on its edge', ext.map((e) => e.tall));
    const HS = HAIR.headSurface(B), deep = (q) => dist(q, HS.c) - HS.r(Math.atan2(q[0] - HS.c[0], q[2] - HS.c[2]), Math.asin(Math.max(-1, Math.min(1, (q[1] - HS.c[1]) / dist(q, HS.c))))); let low = 1e9;
    for (const m of loops) for (let i = 0; i < m.pos.length; i += 3) low = Math.min(low, deep([m.pos[i], m.pos[i + 1], m.pos[i + 2]]));
    ok(low > -0.002, 'no part of a loop is inside the head', low);
  }
  ok(mk({ ends: 2, len: 0.08 }).ms.filter((m) => m.bow).length === 0, 'a tie without a bow has no loops', 0);
}

if (fails.length) { console.error(`hairstyle: ${fails.length} of ${checks} rules broken`); for (const f of fails) console.error('  ✗ ' + f); process.exit(1); }
console.log(`hairstyle: ${checks} rules hold`);
