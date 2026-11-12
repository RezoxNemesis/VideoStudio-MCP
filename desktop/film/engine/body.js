// The real body: a sculpted mesh (skin, clothes, armour) with skin weights, posed by every bone of the skeleton and
// painted as cels — flat tones with a hard shadow edge — under inked lines of two weights (outline, inner lines).
// The mesh is rasterised here in software, a few times finer than the picture, into depth, tone and part buffers; the
// lines are found from those buffers (where the figure ends, where one part meets another, where depth breaks).
import { look } from './look.js';
import { createCanvas, ImageData } from '@napi-rs/canvas';
import { clamp, hex } from './core.js';

const PUFF = { tunic: 0.003, wraps: 0.007, skirt: 0.008, coat: 0.012, cuirass: 0.016, vambrace: 0.009, greave: 0.01, boots: 0.004, shoes: 0.003 };
/** How much wider and taller the eyes are opened than the sculpted ones, and how much the eyeballs grow: [x, y, ball]; null = as sculpted. */
let EYE = [1.1, 1.42, 1.05];
export function setEyeShape(e) { EYE = e; }
/** Smooth the open edges of a mesh along themselves (positions are changed in place). */
function smoothEdge(pos, idx, iters = 10) {
  const cnt = new Map(), key = (a, b) => (a < b ? a * 1e6 + b : b * 1e6 + a);
  for (let t = 0; t < idx.length; t += 3) for (let e = 0; e < 3; e++) { const k = key(idx[t + e], idx[t + (e + 1) % 3]); cnt.set(k, (cnt.get(k) ?? 0) + 1); }
  const nb = new Map();
  for (const [k, c] of cnt) { if (c !== 1) continue; const a = Math.floor(k / 1e6), b = k % 1e6; (nb.get(a) ?? nb.set(a, []).get(a)).push(b); (nb.get(b) ?? nb.set(b, []).get(b)).push(a); }
  const vs = [...nb.keys()].filter((v) => nb.get(v).length === 2), tmp = new Float32Array(vs.length * 3);
  for (let it = 0; it < iters; it++) {
    vs.forEach((v, i) => { const [a, b] = nb.get(v); for (let c = 0; c < 3; c++) tmp[i * 3 + c] = pos[v * 3 + c] * 0.5 + (pos[a * 3 + c] + pos[b * 3 + c]) * 0.25; });
    vs.forEach((v, i) => { pos[v * 3] = tmp[i * 3]; pos[v * 3 + 1] = tmp[i * 3 + 1]; pos[v * 3 + 2] = tmp[i * 3 + 2]; });
  }
}
const HEADISH = /^(head|jaw|eye\.|special|levator|oculi|orbicularis|oris|temporalis|risorius|tongue)/;
const FINGER = /^finger([1-5])-([1-3])\.(L|R)$/;

/** The body of a baked actor, ready to pose: null when the performance was solved without bodies. */
export function bodyOf(bake, id) {
  const a = bake.actors[id];
  if (!a || !a.bodyMeta || !a.rigMeta) return null;
  if (a.body) return a.body;
  const m = a.bodyMeta, ab = a.ab, nb = m.bones.length;
  const head = new Float32Array(nb * 3), tail = new Float32Array(nb * 3), parent = new Int16Array(nb), bi = {};
  m.bones.forEach((b, i) => { head.set(b.head, i * 3); tail.set(b.tail, i * 3); parent[i] = b.parent; bi[b.name] = i; });
  const headish = m.bones.map((b) => HEADISH.test(b.name)), armish = m.bones.map((b) => /^(upperarm|lowerarm|wrist|finger|metacarpal)/.test(b.name));
  const parts = m.parts.map((p) => {
    const n = p.vcount, pos = new Float32Array(ab, p.pos[0], n * 3), idx0 = new Uint32Array(ab, p.idx[0], p.idx[1] / 4), si = new Uint16Array(ab, p.skinIndex[0], n * 4), sw = new Float32Array(ab, p.skinWeight[0], n * 4);
    // expression shapes: for each one the vertices it moves and by how much
    const morphs = p.morphs ? p.morphs.map((mm) => ({ i: new Uint32Array(ab, mm.i[0], mm.i[1] / 4), d: new Float32Array(ab, mm.d[0], mm.d[1] / 4) })) : null;
    // how much of each vertex belongs to the head
    const hw = new Float32Array(n);
    for (let v = 0; v < n; v++) { let w = 0; for (let k = 0; k < 4; k++) if (headish[si[v * 4 + k]]) w += sw[v * 4 + k]; hw[v] = w; }
    const keep = [];
    for (let t = 0; t < idx0.length; t += 3) { const a0 = idx0[t], b0 = idx0[t + 1], c0 = idx0[t + 2]; if (hw[a0] + hw[b0] + hw[c0] < 2.2) keep.push(a0, b0, c0); }
    // a part cut out of the body's own quads ends in a staircase: draw its edge (a hem, a hairline, a beard's edge) as one smooth line
    let pos0 = pos;
    if (p.material !== 'skin' && p.material !== 'eye' && p.material !== 'teeth' && p.material !== 'tongue' && p.material !== 'lash') { pos0 = Float32Array.from(pos); smoothEdge(pos0, idx0, 14); }
    // clothes lie over one another: each layer is let out a little from the one beneath, so layers never cut through each other
    const puff = PUFF[p.name] ?? 0;
    let rest = pos0;
    if (puff) { const nr = normalsOf(pos0, idx0, new Float32Array(n * 3)); rest = new Float32Array(n * 3); for (let i = 0; i < n * 3; i++) rest[i] = pos0[i] + nr[i] * puff; }
    // drawn eyes are larger than real ones: the lids open wider round each eye (and the shapes that close them reach as far)
    if (EYE && m.landmarks?.eyeL && (p.material === 'skin' || p.material === 'lash' || p.material === 'eye')) {
      if (rest === pos) rest = Float32Array.from(pos);
      const r0 = 0.036, w = new Float32Array(n);
      for (const c of [m.landmarks.eyeL, m.landmarks.eyeR]) for (let v = 0; v < n; v++) {
        const dx = rest[v * 3] - c[0], dy = rest[v * 3 + 1] - c[1], dz = rest[v * 3 + 2] - c[2], dd = Math.hypot(dx, dy, dz); if (dd >= r0) continue;
        const u = 1 - dd / r0, k = u * u * (3 - 2 * u); w[v] = k;
        if (p.material === 'eye') { rest[v * 3] = c[0] + dx * EYE[2]; rest[v * 3 + 1] = c[1] + dy * EYE[2]; rest[v * 3 + 2] = c[2] + dz * EYE[2] - 0.0012; }
        else { rest[v * 3] = c[0] + dx * (1 + (EYE[0] - 1) * k); rest[v * 3 + 1] = c[1] + dy * (1 + (EYE[1] - 1) * k) - 0.0015 * k; }
      }
      if (morphs && p.material !== 'eye') for (const mm of morphs) { const d2 = Float32Array.from(mm.d); for (let q = 0; q < mm.i.length; q++) { const k = w[mm.i[q]]; if (k > 0) { d2[q * 3] *= 1 + (EYE[0] - 1) * k; d2[q * 3 + 1] *= 1 + (EYE[1] - 1) * k; } } mm.d = d2; }
    }
    // arms and hands apart from the rest (they are what can pass in front of the face), and each vertex's height in the rest pose (for bands of colour: a sash, a hem)
    const limb = [], core = [];
    for (let t = 0; t < keep.length; t += 3) { let w = 0; for (const v of [keep[t], keep[t + 1], keep[t + 2]]) for (let k = 0; k < 4; k++) if (armish[si[v * 4 + k]]) w += sw[v * 4 + k]; (w > 1.5 ? limb : core).push(keep[t], keep[t + 1], keep[t + 2]); }
    const u = new Float32Array(n); for (let v = 0; v < n; v++) u[v] = pos[v * 3 + 1];
    // the head's own triangles; inside the mouth apart (it is painted dark)
    const headT = [], mouthT = [], mo = m.landmarks?.mouth;
    for (let t = 0; t < idx0.length; t += 3) { const a0 = idx0[t], b0 = idx0[t + 1], c0 = idx0[t + 2]; if (hw[a0] + hw[b0] + hw[c0] < 2.2) continue;
      let inside = false; if (mo && p.material === 'skin') { inside = true; for (const v of [a0, b0, c0]) { const dx = pos[v * 3] - mo[0], dy = pos[v * 3 + 1] - mo[1], dz = pos[v * 3 + 2] - mo[2]; if (!(Math.abs(dx) < 0.03 && Math.abs(dy) < 0.022 && dz < -0.016 && dz > -0.07)) inside = false; } }
      (inside ? mouthT : headT).push(a0, b0, c0); }
    return { idxLimb: Uint32Array.from(limb), idxCore: Uint32Array.from(core), idxHead: Uint32Array.from(headT), idxMouth: Uint32Array.from(mouthT), u, morphs, base: morphs ? Float32Array.from(rest) : null, rest0: pos,
      name: p.name, material: p.material, params: p.params ?? {}, n, pos: morphs ? Float32Array.from(rest) : rest, idx: idx0, idxNoHead: Uint32Array.from(keep), si, sw, out: new Float32Array(n * 3), nrm: new Float32Array(n * 3) };
  });
  const rig = { fps: a.rigMeta.fps, frames: a.rigMeta.frames, data: new Float32Array(ab, a.rigMeta.data[0], a.rigMeta.frames * nb * 7) };
  // fingers: the axis each one bends about (in the rest frame), from the knuckle line and the finger's own direction
  const fingers = {};
  for (const s of ['L', 'R']) {
    const h = (n) => { const i = bi[n]; return i === undefined ? null : [head[i * 3], head[i * 3 + 1], head[i * 3 + 2]]; };
    const k2 = h(`finger2-1.${s}`), k5 = h(`finger5-1.${s}`), wr = h(`wrist.${s}`);
    if (!k2 || !k5 || !wr) continue;
    const across = norm(sub(k5, k2)), along = norm(sub(mid(k2, k5), wr));
    let palm = norm(cross(sub(k2, wr), sub(k5, wr))); if (s === 'L') palm = mul(palm, -1); // out of the palm
    fingers[s] = { across, along, palm, chains: [1, 2, 3, 4, 5].map((f) => [1, 2, 3].map((j) => bi[`finger${f}-${j}.${s}`])) };
  }
  // bones that hang from the jaw (they swing with it when the mouth opens)
  const jawKids = []; if (bi.jaw !== undefined) for (let b = 0; b < nb; b++) { for (let q = b; q >= 0; q = parent[q]) if (q === bi.jaw) { jawKids.push(b); break; } }
  const morphIndex = Object.fromEntries((m.morphNames ?? []).map((n, i) => [n, i]));
  a.body = { morphIndex, jawKids, id, nb, names: m.bones.map((b) => b.name), bi, parent, head, tail, parts, rig, colors: m.colors ?? {}, look: m.look ?? {}, landmarks: m.landmarks ?? {}, height: m.height, fingers,
    R: new Float32Array(nb * 9), T: new Float32Array(nb * 3), P: new Float32Array(nb * 3), Q: new Float32Array(nb * 4) };
  return a.body;
}
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]], mul = (a, k) => [a[0] * k, a[1] * k, a[2] * k], mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
const qmulTo = (o, oi, ax, ay, az, aw, bx, by, bz, bw) => { o[oi] = aw * bx + ax * bw + ay * bz - az * by; o[oi + 1] = aw * by - ax * bz + ay * bw + az * bx; o[oi + 2] = aw * bz + ax * by - ay * bx + az * bw; o[oi + 3] = aw * bw - ax * bx - ay * by - az * bz; };
const qrotv = (q, qi, v) => { const x = q[qi], y = q[qi + 1], z = q[qi + 2], w = q[qi + 3], tx = 2 * (y * v[2] - z * v[1]), ty = 2 * (z * v[0] - x * v[2]), tz = 2 * (x * v[1] - y * v[0]); return [v[0] + w * tx + y * tz - z * ty, v[1] + w * ty + z * tx - x * tz, v[2] + w * tz + x * ty - y * tx]; };

/** How the fingers of a hand are held: curl of each joint in degrees (finger 1 = thumb … 5 = little finger). */
export const HANDS = {
  relax: { curl: [[8, 12, 10], [16, 22, 14], [20, 28, 18], [26, 34, 22], [32, 38, 26]], spread: 0, thumbIn: 0.15 },
  open: { curl: [[0, 4, 4], [2, 4, 3], [2, 4, 3], [3, 5, 4], [4, 6, 5]], spread: 0.6, thumbIn: 0 },
  fist: { curl: [[28, 34, 62], [86, 96, 70], [88, 98, 72], [88, 98, 72], [86, 96, 70]], spread: -0.2, thumbIn: 1 },
  grip: { curl: [[20, 30, 52], [66, 84, 58], [70, 88, 60], [72, 90, 62], [72, 90, 64]], spread: -0.1, thumbIn: 0.9 },
  point: { curl: [[26, 34, 60], [2, 4, 3], [86, 96, 70], [88, 98, 72], [86, 96, 70]], spread: 0, thumbIn: 1 },
  claw: { curl: [[14, 24, 30], [30, 52, 42], [30, 54, 44], [32, 54, 44], [34, 52, 42]], spread: 0.5, thumbIn: 0.3 },
};
const mixHand = (a, b, u) => ({ curl: a.curl.map((f, i) => f.map((v, j) => v + (b.curl[i][j] - v) * u)), spread: a.spread + (b.spread - a.spread) * u, thumbIn: a.thumbIn + (b.thumbIn - a.thumbIn) * u });

/**
 * Pose the body at a moment of the baked performance (seconds) and place it in the film's world (xf: { pos, yaw, scale }).
 * hands: { L, R } each a name of HANDS, a [name, name, mix] blend, or a hand object. Fills every part's `out` (positions).
 */
export function poseBody(B, t, xf = null, hands = null, face = null) {
  const { nb, rig, head, R, T, P, Q } = B, d = rig.data;
  const f = clamp(t * rig.fps, 0, rig.frames - 1), i0 = Math.min(rig.frames - 1, Math.floor(f)), i1 = Math.min(rig.frames - 1, i0 + 1), u = f - i0;
  for (let b = 0; b < nb; b++) {
    const o0 = (i0 * nb + b) * 7, o1 = (i1 * nb + b) * 7;
    P[b * 3] = d[o0] + (d[o1] - d[o0]) * u; P[b * 3 + 1] = d[o0 + 1] + (d[o1 + 1] - d[o0 + 1]) * u; P[b * 3 + 2] = d[o0 + 2] + (d[o1 + 2] - d[o0 + 2]) * u;
    let x = d[o0 + 3], y = d[o0 + 4], z = d[o0 + 5], w = d[o0 + 6]; const x1 = d[o1 + 3], y1 = d[o1 + 4], z1 = d[o1 + 5], w1 = d[o1 + 6], s = x * x1 + y * y1 + z * z1 + w * w1 < 0 ? -u : u;
    x += (x1 * (s < 0 ? -1 : 1) - x) * u; y += (y1 * (s < 0 ? -1 : 1) - y) * u; z += (z1 * (s < 0 ? -1 : 1) - z) * u; w += (w1 * (s < 0 ? -1 : 1) - w) * u;
    const l = Math.hypot(x, y, z, w) || 1; Q[b * 4] = x / l; Q[b * 4 + 1] = y / l; Q[b * 4 + 2] = z / l; Q[b * 4 + 3] = w / l;
  }
  // fingers: bent here, joint by joint (the performance carries the arm and the wrist; what the hand does is the film's choice)
  if (hands) for (const s of ['L', 'R']) {
    const F = B.fingers[s]; let hd = hands[s]; if (!F || !hd) continue;
    if (Array.isArray(hd)) hd = mixHand(HANDS[hd[0]], HANDS[hd[1]], hd[2]); else if (typeof hd === 'string') hd = HANDS[hd];
    if (!hd) continue;
    F.chains.forEach((ch, fi) => {
      if (ch.some((b) => b === undefined)) return;
      // the bend axis: across the knuckles for the fingers; for the thumb, turned so that it closes over the palm
      let ax = fi === 0 ? norm([F.along[0] * 0.75 - F.palm[0] * 0.25 + F.across[0] * 0.3, F.along[1] * 0.75 - F.palm[1] * 0.25 + F.across[1] * 0.3, F.along[2] * 0.75 - F.palm[2] * 0.25 + F.across[2] * 0.3]) : F.across;
      const sgn = s === 'L' ? -1 : 1; // which way round closes the hand
      let par = B.parent[ch[0]], pq = [Q[par * 4], Q[par * 4 + 1], Q[par * 4 + 2], Q[par * 4 + 3]];
      // rest-relative local rotations of the three joints (parent⁻¹ · own), read before anything is changed
      const loc = ch.map((b) => { const p = B.parent[b], o = [0, 0, 0, 0]; qmulTo(o, 0, -Q[p * 4], -Q[p * 4 + 1], -Q[p * 4 + 2], Q[p * 4 + 3], Q[b * 4], Q[b * 4 + 1], Q[b * 4 + 2], Q[b * 4 + 3]); return o; });
      let pp = [P[par * 3], P[par * 3 + 1], P[par * 3 + 2]], ph = [head[par * 3], head[par * 3 + 1], head[par * 3 + 2]];
      ch.forEach((b, j) => {
        let ang = hd.curl[fi][j] * Math.PI / 180 * sgn; const tmp = [0, 0, 0, 0], q = [0, 0, 0, 0];
        qmulTo(tmp, 0, pq[0], pq[1], pq[2], pq[3], loc[j][0], loc[j][1], loc[j][2], loc[j][3]);
        let rx = ax; if (j === 0 && fi > 0 && hd.spread) { const sp = hd.spread * (fi - 2.5) * 0.11; const sa = F.palm, c = Math.cos(sp / 2), sn = Math.sin(sp / 2); qmulTo(q, 0, tmp[0], tmp[1], tmp[2], tmp[3], sa[0] * sn, sa[1] * sn, sa[2] * sn, c); tmp[0] = q[0]; tmp[1] = q[1]; tmp[2] = q[2]; tmp[3] = q[3]; }
        if (fi === 0 && j === 0) { // the thumb's root also swings in towards the palm
          const sw = hd.thumbIn * 0.7 * sgn, c = Math.cos(sw / 2), sn = Math.sin(sw / 2); qmulTo(q, 0, tmp[0], tmp[1], tmp[2], tmp[3], F.along[0] * sn, F.along[1] * sn, F.along[2] * sn, c); tmp[0] = q[0]; tmp[1] = q[1]; tmp[2] = q[2]; tmp[3] = q[3];
        }
        const c = Math.cos(ang / 2), sn = Math.sin(ang / 2);
        qmulTo(q, 0, tmp[0], tmp[1], tmp[2], tmp[3], rx[0] * sn, rx[1] * sn, rx[2] * sn, c);
        const off = qrotv(pq, 0, [head[b * 3] - ph[0], head[b * 3 + 1] - ph[1], head[b * 3 + 2] - ph[2]]);
        const np = [pp[0] + off[0], pp[1] + off[1], pp[2] + off[2]];
        P[b * 3] = np[0]; P[b * 3 + 1] = np[1]; P[b * 3 + 2] = np[2]; Q[b * 4] = q[0]; Q[b * 4 + 1] = q[1]; Q[b * 4 + 2] = q[2]; Q[b * 4 + 3] = q[3];
        pq = q; pp = np; ph = [head[b * 3], head[b * 3 + 1], head[b * 3 + 2]];
      });
    });
  }
  // the face: expression shapes move the skin of the head before the bones carry it; the jaw swings open with the mouth
  const W = face ? faceShapes(face) : null;
  for (const p of B.parts) {
    if (!p.morphs) continue;
    if (p.dirty) { p.pos.set(p.base); p.dirty = false; }
    if (!W) continue;
    for (const name in W.w) { const mi = B.morphIndex[name], w = W.w[name]; if (mi === undefined || Math.abs(w) < 0.004) continue; const mm = p.morphs[mi], ii = mm.i, dd = mm.d, pos = p.pos; for (let q = 0; q < ii.length; q++) { const v = ii[q] * 3; pos[v] += dd[q * 3] * w; pos[v + 1] += dd[q * 3 + 1] * w; pos[v + 2] += dd[q * 3 + 2] * w; } p.dirty = true; }
  }
  if (W && W.jaw > 0.002 && B.bi.jaw !== undefined && B.bi.head !== undefined) {
    const hb = B.bi.head, jb = B.bi.jaw, ax = qrotv(Q, hb * 4, [1, 0, 0]), sn = Math.sin(W.jaw / 2), rq = [ax[0] * sn, ax[1] * sn, ax[2] * sn, Math.cos(W.jaw / 2)], piv = [P[jb * 3], P[jb * 3 + 1], P[jb * 3 + 2]], tmp = [0, 0, 0, 0];
    for (const b of B.jawKids) { const r = qrotv(rq, 0, [P[b * 3] - piv[0], P[b * 3 + 1] - piv[1], P[b * 3 + 2] - piv[2]]); P[b * 3] = piv[0] + r[0]; P[b * 3 + 1] = piv[1] + r[1]; P[b * 3 + 2] = piv[2] + r[2]; qmulTo(tmp, 0, rq[0], rq[1], rq[2], rq[3], Q[b * 4], Q[b * 4 + 1], Q[b * 4 + 2], Q[b * 4 + 3]); Q[b * 4] = tmp[0]; Q[b * 4 + 1] = tmp[1]; Q[b * 4 + 2] = tmp[2]; Q[b * 4 + 3] = tmp[3]; }
  }
  const cy = xf ? Math.cos(xf.yaw ?? 0) : 1, sy = xf ? Math.sin(xf.yaw ?? 0) : 0, k = xf?.scale ?? 1, px = xf?.pos?.[0] ?? 0, py = xf?.pos?.[1] ?? 0, pz = xf?.pos?.[2] ?? 0;
  for (let b = 0; b < nb; b++) {
    const x = Q[b * 4], y = Q[b * 4 + 1], z = Q[b * 4 + 2], w = Q[b * 4 + 3];
    const r0 = 1 - 2 * (y * y + z * z), r1 = 2 * (x * y - z * w), r2 = 2 * (x * z + y * w), r3 = 2 * (x * y + z * w), r4 = 1 - 2 * (x * x + z * z), r5 = 2 * (y * z - x * w), r6 = 2 * (x * z - y * w), r7 = 2 * (y * z + x * w), r8 = 1 - 2 * (x * x + y * y);
    const hx = head[b * 3], hy = head[b * 3 + 1], hz = head[b * 3 + 2];
    const tx = P[b * 3] - (r0 * hx + r1 * hy + r2 * hz), ty = P[b * 3 + 1] - (r3 * hx + r4 * hy + r5 * hz), tz = P[b * 3 + 2] - (r6 * hx + r7 * hy + r8 * hz);
    // place in the film's world: turn about the vertical, scale, move
    const o = b * 9;
    R[o] = (r0 * cy + r6 * sy) * k; R[o + 1] = (r1 * cy + r7 * sy) * k; R[o + 2] = (r2 * cy + r8 * sy) * k;
    R[o + 3] = r3 * k; R[o + 4] = r4 * k; R[o + 5] = r5 * k;
    R[o + 6] = (-r0 * sy + r6 * cy) * k; R[o + 7] = (-r1 * sy + r7 * cy) * k; R[o + 8] = (-r2 * sy + r8 * cy) * k;
    T[b * 3] = (tx * cy + tz * sy) * k + px; T[b * 3 + 1] = ty * k + py; T[b * 3 + 2] = (-tx * sy + tz * cy) * k + pz;
  }
  for (const p of B.parts) {
    const { n, pos, si, sw, out } = p;
    for (let v = 0; v < n; v++) {
      const x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2]; let ox = 0, oy = 0, oz = 0;
      for (let q = 0; q < 4; q++) {
        const w = sw[v * 4 + q]; if (w === 0) continue;
        const b = si[v * 4 + q], o = b * 9;
        ox += w * (R[o] * x + R[o + 1] * y + R[o + 2] * z + T[b * 3]); oy += w * (R[o + 3] * x + R[o + 4] * y + R[o + 5] * z + T[b * 3 + 1]); oz += w * (R[o + 6] * x + R[o + 7] * y + R[o + 8] * z + T[b * 3 + 2]);
      }
      out[v * 3] = ox; out[v * 3 + 1] = oy; out[v * 3 + 2] = oz;
    }
  }
  return B;
}

/** Smooth normals of a posed part (or of any triangle soup: pos, idx → nrm). */
export function normalsOf(pos, idx, nrm) {
  nrm.fill(0);
  for (let t = 0; t < idx.length; t += 3) {
    const a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
    const ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2], vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
    const nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    nrm[a] += nx; nrm[a + 1] += ny; nrm[a + 2] += nz; nrm[b] += nx; nrm[b + 1] += ny; nrm[b + 2] += nz; nrm[c] += nx; nrm[c + 1] += ny; nrm[c + 2] += nz;
  }
  for (let i = 0; i < nrm.length; i += 3) { const l = Math.hypot(nrm[i], nrm[i + 1], nrm[i + 2]) || 1; nrm[i] /= l; nrm[i + 1] /= l; nrm[i + 2] /= l; }
  return nrm;
}

/**
 * A cel painter for meshes. Add meshes (world positions, triangles, a paint), then `finish()` gives the picture of
 * them: { canvas, x, y } to draw at (x, y), and depthAt(px, py) (metres from the lens; Infinity where empty).
 *   paint: { fill: [r,g,b], shade: [r,g,b], hi?: [r,g,b] (a highlight tone: metal, hair), thr? (where light turns to
 *            shadow, −1…1, default 0.08), line? (false: this part draws no inner line against its neighbours),
 *            two? (true: seen from both sides — cloth), flat? (no shadow tone) }
 * o: { ss (fineness, default 2), light: unit vector towards the light (world), ink: [r,g,b], lw (outline, picture px),
 *      lwIn (inner lines), pad }
 */
export class CelPainter {
  constructor(cam, o = {}) {
    // the film's look decides how surfaces are shaded and whether they are inked (engine/look.js)
    const lp = look().paint ?? {};
    if (lp.lines === false) o = { ...o, lines: false };
    if (lp.ink) o = { ...o, ink: lp.ink };
    if (lp.inner !== undefined) o = { ...o, lwIn: (o.lwIn ?? 1.5) * lp.inner };
    if (lp.outer !== undefined) o = { ...o, lw: (o.lw ?? 2.6) * lp.outer };
    if (lp.rim && o.rim === undefined) o = { ...o, rim: true };
    if (lp.mode === 'flat' && o.shadows === undefined) o = { ...o, shadows: false };
    this.mode = lp.mode ?? 'cel';
    this.cam = cam; this.o = o; this.meshes = []; this.paints = [null];
  }
  /** u: one number per vertex that the paint's `bands` cut into stripes of other paints: bands: [[from, to, paint], …]. Returns the paint's number. */
  add(pos, idx, paint, nrm = null, u = null, xy = null) {
    this.paints.push(paint); const id = this.paints.length - 1;
    if (paint.bands) paint.bandIds = paint.bands.map((b) => { this.paints.push({ group: paint.group ?? `g${id}`, bias: paint.bias, ...b[2] }); return this.paints.length - 1; });
    if (paint.back) { this.paints.push({ ...paint, ...paint.back, back: null, bands: null }); paint.backId = this.paints.length - 1; }
    // paint.map(x, y) → a number into paint.maps (other paints) or −1: drawn shapes that ride on the surface (brows, a scar); xy: two numbers per vertex
    if (paint.map && paint.maps) paint.mapIds = paint.maps.map((mp) => { this.paints.push({ group: paint.group ?? `g${id}`, bias: paint.bias, thr: paint.thr, ...mp }); return this.paints.length - 1; });
    this.meshes.push({ pos, idx, nrm, id, u, xy }); return id;
  }
  finish() {
    const cam = this.cam, o = this.o, SS = o.ss ?? 2, L = o.light ?? [0.4, 0.8, 0.45];
    const { pos: cp, R: cr, U: cu, F: cf, f: fl, cx, cy } = cam;
    // project every mesh; find the box of the picture they cover
    let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
    for (const m of this.meshes) {
      const n = m.pos.length / 3, sx = (m.sx = new Float32Array(n)), sy = (m.sy = new Float32Array(n)), sz = (m.sz = new Float32Array(n)), sl = (m.sl = new Float32Array(n)), sv = (m.sv = new Float32Array(n));
      const nr = m.nrm ?? normalsOf(m.pos, m.idx, new Float32Array(n * 3)), p = m.pos, two = this.paints[m.id].two;
      for (let v = 0; v < n; v++) {
        const dx = p[v * 3] - cp[0], dy = p[v * 3 + 1] - cp[1], dz = p[v * 3 + 2] - cp[2], z = dx * cf[0] + dy * cf[1] + dz * cf[2];
        sz[v] = z; if (z < 0.05) { sx[v] = sy[v] = NaN; continue; }
        const X = cx + fl * (dx * cr[0] + dy * cr[1] + dz * cr[2]) / z, Y = cy - fl * (dx * cu[0] + dy * cu[1] + dz * cu[2]) / z;
        sx[v] = X; sy[v] = Y; if (X < x0) x0 = X; if (X > x1) x1 = X; if (Y < y0) y0 = Y; if (Y > y1) y1 = Y;
        let nx = nr[v * 3], ny = nr[v * 3 + 1], nz = nr[v * 3 + 2];
        const il = Math.hypot(dx, dy, dz) || 1; let nv = -(nx * dx + ny * dy + nz * dz) / il;
        if (two && nv < 0) { nx = -nx; ny = -ny; nz = -nz; nv = -nv; } // cloth seen from its inside
        sl[v] = nx * L[0] + ny * L[1] + nz * L[2]; sv[v] = nv;
      }
    }
    const pad = Math.ceil((o.lw ?? 3) + 3);
    x0 = Math.max(0, Math.floor(x0) - pad); y0 = Math.max(0, Math.floor(y0) - pad); x1 = Math.min(cam.W, Math.ceil(x1) + pad); y1 = Math.min(cam.H, Math.ceil(y1) + pad);
    if (!(x1 > x0 && y1 > y0)) return null;
    const w = (x1 - x0) * SS, h = (y1 - y0) * SS, N = w * h;
    const zb = new Float32Array(N), ib = new Uint8Array(N), lb = new Float32Array(N), vb = new Float32Array(N); // 1/depth, part, light, facing
    const anyU = this.meshes.some((m) => m.u && this.paints[m.id].bands), ub = anyU ? new Float32Array(N) : null;
    const anyXY = this.meshes.some((m) => m.xy && this.paints[m.id].map), xb = anyXY ? new Float32Array(N) : null, yb = anyXY ? new Float32Array(N) : null;
    for (const m of this.meshes) {
      const { sx, sy, sz, sl, sv, idx } = m, id0 = m.id, pt = this.paints[id0], bias = pt.bias ?? 0, mu = ub && m.u && pt.bands ? m.u : null, backId = pt.backId ?? 0, faceMap = xb && m.xy && pt.map ? m.xy : null; // bias: metres towards the lens (a layer of clothing lies over what it covers)
      for (let t = 0; t < idx.length; t += 3) {
        const a = idx[t], b = idx[t + 1], c = idx[t + 2];
        const ax = (sx[a] - x0) * SS, ay = (sy[a] - y0) * SS, bx = (sx[b] - x0) * SS, by = (sy[b] - y0) * SS, cx2 = (sx[c] - x0) * SS, cy2 = (sy[c] - y0) * SS;
        if (ax !== ax || bx !== bx || cx2 !== cx2) continue; // a corner behind the lens
        let area = (bx - ax) * (cy2 - ay) - (cx2 - ax) * (by - ay); if (area > -1e-6 && area < 1e-6) continue;
        let mnx = Math.floor(Math.min(ax, bx, cx2)), mxx = Math.ceil(Math.max(ax, bx, cx2)), mny = Math.floor(Math.min(ay, by, cy2)), mxy = Math.ceil(Math.max(ay, by, cy2));
        if (mnx < 0) mnx = 0; if (mny < 0) mny = 0; if (mxx > w - 1) mxx = w - 1; if (mxy > h - 1) mxy = h - 1; if (mnx > mxx || mny > mxy) continue;
        const ia = 1 / area, za = 1 / (sz[a] - bias), zbb = 1 / (sz[b] - bias), zc = 1 / (sz[c] - bias);
        // barycentric weights are affine in the picture: w0 = e0x·x + e0y·y + e0c …
        const e0x = (by - cy2) * ia, e0y = (cx2 - bx) * ia, e0c = (bx * cy2 - cx2 * by) * ia, e1x = (cy2 - ay) * ia, e1y = (ax - cx2) * ia, e1c = (cx2 * ay - ax * cy2) * ia;
        const la = sl[a], lbv = sl[b], lc = sl[c], va = sv[a], vbv = sv[b], vc = sv[c], id = backId && area > 0 ? backId : id0, ua = mu ? mu[a] : 0, ubb = mu ? mu[b] : 0, uc = mu ? mu[c] : 0;
        for (let y = mny; y <= mxy; y++) {
          const py = y + 0.5; let w0 = e0x * (mnx + 0.5) + e0y * py + e0c, w1 = e1x * (mnx + 0.5) + e1y * py + e1c, o2 = y * w + mnx;
          for (let x = mnx; x <= mxx; x++, w0 += e0x, w1 += e1x, o2++) {
            const w2 = 1 - w0 - w1; if (w0 < -1e-5 || w1 < -1e-5 || w2 < -1e-5) continue;
            const z = w0 * za + w1 * zbb + w2 * zc; if (z <= zb[o2]) continue;
            zb[o2] = z; ib[o2] = id; lb[o2] = w0 * la + w1 * lbv + w2 * lc; vb[o2] = w0 * va + w1 * vbv + w2 * vc; if (ub) ub[o2] = w0 * ua + w1 * ubb + w2 * uc; if (faceMap) { xb[o2] = w0 * faceMap[a * 2] + w1 * faceMap[b * 2] + w2 * faceMap[c * 2]; yb[o2] = w0 * faceMap[a * 2 + 1] + w1 * faceMap[b * 2 + 1] + w2 * faceMap[c * 2 + 1]; }
          }
        }
      }
    }
    // ---- bands: stripes of another paint across a part (a sash round the waist, a hem, a cuff)
    if (ub) for (let q = 0; q < N; q++) { const p = this.paints[ib[q]]; if (!p || !p.bands) continue; const u = ub[q]; for (let k = 0; k < p.bands.length; k++) if (u >= p.bands[k][0] && u < p.bands[k][1]) { ib[q] = p.bandIds[k]; break; } }
    if (xb) for (let q = 0; q < N; q++) { const p = this.paints[ib[q]]; if (!p || !p.map) continue; const k = p.map(xb[q], yb[q]); if (k >= 0) ib[q] = p.mapIds[k]; }
    // ---- lines: the outline (figure against nothing), part against part, a break in depth
    const outR = (o.lw ?? 2.6) * SS * 0.5, inR = (o.lwIn ?? 1.5) * SS * 0.5, ink = new Float32Array(N);
    const stamp = (x, y, r) => {
      const R2 = Math.ceil(r + 0.5); for (let j = -R2; j <= R2; j++) { const yy = y + j; if (yy < 0 || yy >= h) continue; for (let i = -R2; i <= R2; i++) { const xx = x + i; if (xx < 0 || xx >= w) continue; const a = r + 0.5 - Math.sqrt(i * i + j * j); if (a <= 0) continue; const q = yy * w + xx; if (ink[q] < a) ink[q] = a > 1 ? 1 : a; } }
    };
    const P = this.paints, zk = o.depthEdge ?? 0.022; // metres of sudden depth that make a line
    if (o.lines === false) { /* a look without ink: the lines are left out (stamp stays unused) */ }
    for (let y = 0; y < h - 1; y++) for (let x = 0; x < w - 1; x++) {
      const q = y * w + x, a = ib[q];
      for (let d = 0; d < 2; d++) {
        const q2 = d ? q + w : q + 1, b = ib[q2];
        if (a === b) { if (!a) continue; const za = 1 / zb[q], z2 = 1 / zb[q2], dz = Math.abs(za - z2); if (dz > zk * (P[a].edge ?? 1) * (1 + 0.6 * Math.max(za, z2) * 0.15)) { // not a slope: the surface beyond does not continue at the same rate
          const q0 = d ? q - w : q - 1, q3 = d ? q2 + w : q2 + 1; const s0 = q0 >= 0 && ib[q0] === a ? Math.abs(1 / zb[q0] - za) : 0, s1 = q3 < N && ib[q3] === a ? Math.abs(1 / zb[q3] - z2) : 0; if (dz > 3 * Math.max(s0, s1) + zk * 0.5 * (P[a].edge ?? 1)) stamp(x, y, inR); } continue; }
        if (!a || !b) { stamp(d ? x : (a ? x : x + 1), d ? (a ? y : y + 1) : y, outR); continue; }
        if (P[a].line === false && P[b].line === false) continue;
        if (P[a].group && P[a].group === P[b].group) continue;
        // faces of one material that only differ by a shade (a model's colour atlas) are one surface: no line between them
        if (P[a].soft && P[b].soft) { const fa = P[a].fill, fb = P[b].fill; if (Math.abs(fa[0] - fb[0]) + Math.abs(fa[1] - fb[1]) + Math.abs(fa[2] - fb[2]) < 70) continue; }
        stamp(x, y, inR);
      }
    }
    // ---- cast shadows: what stands between a surface and the light (the fringe on the brow, the chin on the neck, an arm
    // across the chest). A second, coarse picture of everything is made from the light's side; a lit point that the
    // light's picture sees something nearer for is in shadow.
    let sm = null;
    if (o.shadows !== false) {
      let a3 = Math.abs(L[1]) < 0.9 ? [0, 1, 0] : [1, 0, 0]; const ax = norm(cross(a3, L)), bx = cross(L, ax);
      let u0 = 1e9, u1 = -1e9, v0 = 1e9, v1 = -1e9;
      for (const m of this.meshes) { if (this.paints[m.id].cast === false) continue; const p = m.pos, n = p.length / 3, lu = (m.lu = new Float32Array(n)), lv = (m.lv = new Float32Array(n)), ld = (m.ld = new Float32Array(n)); for (let v = 0; v < n; v++) { const x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2], U = x * ax[0] + y * ax[1] + z * ax[2], Vv = x * bx[0] + y * bx[1] + z * bx[2]; lu[v] = U; lv[v] = Vv; ld[v] = x * L[0] + y * L[1] + z * L[2]; if (U < u0) u0 = U; if (U > u1) u1 = U; if (Vv < v0) v0 = Vv; if (Vv > v1) v1 = Vv; } }
      if (u1 > u0) {
        const tex = Math.max(0.004, Math.max(u1 - u0, v1 - v0) / 700), mw = Math.ceil((u1 - u0) / tex) + 3, mh = Math.ceil((v1 - v0) / tex) + 3, md = new Float32Array(mw * mh).fill(-1e9);
        for (const m of this.meshes) {
          if (!m.lu) continue; const { lu, lv, ld, idx } = m;
          for (let t = 0; t < idx.length; t += 3) {
            const a = idx[t], b = idx[t + 1], c = idx[t + 2], ax2 = (lu[a] - u0) / tex + 1, ay2 = (lv[a] - v0) / tex + 1, bx2 = (lu[b] - u0) / tex + 1, by2 = (lv[b] - v0) / tex + 1, cx3 = (lu[c] - u0) / tex + 1, cy3 = (lv[c] - v0) / tex + 1;
            const area = (bx2 - ax2) * (cy3 - ay2) - (cx3 - ax2) * (by2 - ay2); if (area > -1e-9 && area < 1e-9) continue;
            let mnx = Math.max(0, Math.floor(Math.min(ax2, bx2, cx3))), mxx = Math.min(mw - 1, Math.ceil(Math.max(ax2, bx2, cx3))), mny = Math.max(0, Math.floor(Math.min(ay2, by2, cy3))), mxy2 = Math.min(mh - 1, Math.ceil(Math.max(ay2, by2, cy3)));
            const ia = 1 / area, e0x = (by2 - cy3) * ia, e0y = (cx3 - bx2) * ia, e0c = (bx2 * cy3 - cx3 * by2) * ia, e1x = (cy3 - ay2) * ia, e1y = (ax2 - cx3) * ia, e1c = (cx3 * ay2 - ax2 * cy3) * ia, da = ld[a], db = ld[b], dc = ld[c];
            for (let y = mny; y <= mxy2; y++) { let w0 = e0x * (mnx + 0.5) + e0y * (y + 0.5) + e0c, w1 = e1x * (mnx + 0.5) + e1y * (y + 0.5) + e1c, q = y * mw + mnx; for (let x = mnx; x <= mxx; x++, w0 += e0x, w1 += e1x, q++) { const w2 = 1 - w0 - w1; if (w0 < -1e-4 || w1 < -1e-4 || w2 < -1e-4) continue; const dd = w0 * da + w1 * db + w2 * dc; if (dd > md[q]) md[q] = dd; } }
          }
        }
        sm = { md, mw, mh, u0, v0, tex, ax, bx };
      }
    }
    const shBias = o.shadowBias ?? 0.028;
    // world position of a pixel of the fine picture, from its depth
    const inShadow = (q, X, Y) => {
      const z = 1 / zb[q] + (this.paints[ib[q]].bias ?? 0), px = (x0 + (X + 0.5) / SS - cx) / fl * z, py = -(y0 + (Y + 0.5) / SS - cy) / fl * z;
      const wx = cp[0] + cr[0] * px + cu[0] * py + cf[0] * z, wy = cp[1] + cr[1] * px + cu[1] * py + cf[1] * z, wz = cp[2] + cr[2] * px + cu[2] * py + cf[2] * z;
      const U = (wx * sm.ax[0] + wy * sm.ax[1] + wz * sm.ax[2] - sm.u0) / sm.tex + 1, Vv = (wx * sm.bx[0] + wy * sm.bx[1] + wz * sm.bx[2] - sm.v0) / sm.tex + 1, d = wx * L[0] + wy * L[1] + wz * L[2] + shBias;
      const iu = Math.floor(U - 0.5), iv = Math.floor(Vv - 0.5); if (iu < 0 || iv < 0 || iu >= sm.mw - 1 || iv >= sm.mh - 1) return false;
      const fu = U - 0.5 - iu, fv = Vv - 0.5 - iv, q2 = iv * sm.mw + iu, md = sm.md;
      const s = (md[q2] > d ? (1 - fu) * (1 - fv) : 0) + (md[q2 + 1] > d ? fu * (1 - fv) : 0) + (md[q2 + sm.mw] > d ? (1 - fu) * fv : 0) + (md[q2 + sm.mw + 1] > d ? fu * fv : 0);
      return s > 0.5;
    };
    // ---- tones → picture (box-filtered down to the picture's size)
    const ow = x1 - x0, oh = y1 - y0, img = new Uint8ClampedArray(ow * oh * 4), ik = o.ink ?? [22, 18, 26], inv = 1 / (SS * SS);
    if (o.lines === false) ink.fill(0);
    const rimC = o.rim ?? null, rimDir = o.rimDir ?? null, mode = this.mode, tone = [0, 0, 0];
    const sstep = (a, b, x) => { const u = x <= a ? 0 : x >= b ? 1 : (x - a) / (b - a); return u * u * (3 - 2 * u); };
    for (let y = 0; y < oh; y++) for (let x = 0; x < ow; x++) {
      let r = 0, g = 0, b = 0, al = 0;
      for (let j = 0; j < SS; j++) for (let i = 0; i < SS; i++) {
        const q = (y * SS + j) * w + x * SS + i, id = ib[q], ia = ink[q];
        if (!id) { if (ia > 0) { r += ik[0] * ia; g += ik[1] * ia; b += ik[2] * ia; al += ia; } continue; }
        const p = P[id], l = lb[q], v = vb[q]; let c = p.flat || (l > (p.thr ?? 0.08) && !(sm && p.recv !== false && l > (p.thr ?? 0.08) + 0.1 && inShadow(q, x * SS + i, y * SS + j))) ? p.fill : p.shade;
        if (mode !== 'cel' && !p.flat) {
          const th = p.thr ?? 0.08, lit = c === p.fill;
          if (mode === 'smooth') {
            // a 3D render: the light rolls off smoothly; a cast shadow takes most of it away; a sheen where it is strongest
            let k = sstep(th - 0.24, th + 0.5, l); if (!lit && l > th) k *= 0.3;
            const sh = k > 0.6 ? 0.16 * sstep(0.78, 1, l) * (0.4 + 0.6 * v) : 0;
            for (let m = 0; m < 3; m++) tone[m] = p.shade[m] + (p.fill[m] - p.shade[m]) * k + (255 - p.fill[m]) * sh;
            c = tone;
          } else if (mode === 'flat') c = lit ? p.fill : (p._half ??= p.fill.map((f, m) => f + (p.shade[m] - f) * 0.45));
          else if (mode === 'tri' && lit && l > th + 0.45) c = (p._lt ??= p.fill.map((f) => f + (255 - f) * 0.14));
        }
        if (p.hi && !p.flat && c === p.fill) { const hh = (l + v) * 0.5; if (hh > (p.hiAt ?? 0.86) && l > 0.2) c = p.hi; }
        if (rimC && v < 0.34 && l < (p.thr ?? 0.08) + 0.5 && !p.flat) c = l > (p.thr ?? 0.08) ? c : rimC === true ? p.fill : c;
        if (ia > 0) { r += c[0] + (ik[0] - c[0]) * ia; g += c[1] + (ik[1] - c[1]) * ia; b += c[2] + (ik[2] - c[2]) * ia; } else { r += c[0]; g += c[1]; b += c[2]; }
        al += 1;
      }
      if (al <= 0) continue;
      const o4 = (y * ow + x) * 4; img[o4] = r / al; img[o4 + 1] = g / al; img[o4 + 2] = b / al; img[o4 + 3] = Math.min(255, al * inv * 255);
    }
    void rimDir;
    const cv = createCanvas(ow, oh); cv.getContext('2d').putImageData(new ImageData(img, ow, oh), 0, 0);
    return { canvas: cv, x: x0, y: y0, w: ow, h: oh, depthAt: (px, py) => { const X = Math.floor((px - x0) * SS), Y = Math.floor((py - y0) * SS); if (X < 0 || Y < 0 || X >= w || Y >= h) return Infinity; const z = zb[Y * w + X]; return z > 0 ? 1 / z : Infinity; }, zb, ib, sw: w, sh: h, ss: SS };
  }
}
export const rgb255 = (c) => (typeof c === 'string' ? hex(c) : c.map((v) => Math.round(255 * Math.pow(clamp(v), 1 / 2.2))));

/**
 * A face (the film's words: browTilt −1 angry … +1 sad, browL/browR raise, eyeOpen, eyeSmile, smile −1…1, mouthOpen,
 * mouthWide −1 round … +1 wide, blink) as weights of the body's expression shapes, and how far the jaw swings (radians).
 */
export function faceShapes(f) {
  const c01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x), w = {};
  const open = c01(f.mouthOpen ?? 0), wide = f.mouthWide ?? 0, smile = f.smile ?? 0, tilt = f.browTilt ?? 0, eo = f.eyeOpen ?? 1, es = f.eyeSmile ?? 0, blink = c01(f.blink ?? 0);
  const anger = c01(-tilt), sad = c01(tilt), up = c01(smile), down = c01(-smile), round = c01(-wide), wd = c01(wide);
  const openAmt = open * (0.52 - 0.14 * wd) + c01(eo - 1.1) * 0.25;
  w['mouth-open'] = openAmt; w['mouth-part-later'] = open * 0.22 + down * 0.1 * open;
  w['mouth-retraction'] = open * 0.4 * wd + wd * 0.35 + up * 0.25; w['mouth-protusion'] = open * 0.5 * round + round * 0.3; w['mouth-pursing'] = open * 0.45 * round + round * 0.4;
  w['mouth-corner-puller'] = up * 0.9; w['mouth-elevation'] = up * 0.25 + (f.sneer ?? 0) * 0.3; w['mouth-upward-retraction'] = up * 0.35 + anger * 0.2 * down;
  w['mouth-depression'] = down * 0.75; w['mouth-depression-retraction'] = down * 0.3; w['mouth-compression'] = down * 0.35 * (1 - open) + anger * 0.3 * (1 - open); w['mouth-eversion'] = open * 0.1;
  const cl = c01(blink + c01(1 - eo) + es * 0.5);
  w['eye-left-closure'] = cl; w['eye-right-closure'] = cl;
  const slit = es * 0.34 + anger * 0.3; w['eye-left-slit'] = slit * (1 - cl); w['eye-right-slit'] = slit * (1 - cl);
  const wideE = c01((eo - 1) * 2.6) * (1 - blink); w['eye-left-opened-up'] = wideE; w['eye-right-opened-up'] = wideE;
  const bl = f.browL ?? 0, br = f.browR ?? 0;
  w['eyebrows-left-up'] = c01(bl) * 0.8 + wideE * 0.3; w['eyebrows-right-up'] = c01(br) * 0.8 + wideE * 0.3;
  w['eyebrows-left-extern-up'] = c01(bl) * 0.35 + anger * 0.35; w['eyebrows-right-extern-up'] = c01(br) * 0.35 + anger * 0.35;
  w['eyebrows-left-inner-up'] = sad; w['eyebrows-right-inner-up'] = sad;
  w['eyebrows-left-down'] = anger * 0.95 + c01(-bl) * 0.8; w['eyebrows-right-down'] = anger * 0.95 + c01(-br) * 0.8;
  const nose = anger * 0.45 * (0.4 + 0.6 * down) + (f.sneer ?? 0) * 0.7; w['nose-left-elevation'] = nose; w['nose-right-elevation'] = nose;
  w['nose-left-dilatation'] = anger * 0.4; w['nose-right-dilatation'] = anger * 0.4;
  return { w, jaw: openAmt * 0.11 };
}
/** Where a point that rides on a bone is now (after poseBody): rest position → world. */
export function onBone(B, bone, p) {
  const b = typeof bone === 'number' ? bone : B.bi[bone], o = b * 9, R = B.R, T = B.T;
  return [R[o] * p[0] + R[o + 1] * p[1] + R[o + 2] * p[2] + T[b * 3], R[o + 3] * p[0] + R[o + 4] * p[1] + R[o + 5] * p[2] + T[b * 3 + 1], R[o + 6] * p[0] + R[o + 7] * p[1] + R[o + 8] * p[2] + T[b * 3 + 2]];
}
/** A direction that rides on a bone (unit length kept when the body is not scaled). */
export function dirOnBone(B, bone, d) {
  const b = typeof bone === 'number' ? bone : B.bi[bone], o = b * 9, R = B.R;
  return [R[o] * d[0] + R[o + 1] * d[1] + R[o + 2] * d[2], R[o + 3] * d[0] + R[o + 4] * d[1] + R[o + 5] * d[2], R[o + 6] * d[0] + R[o + 7] * d[1] + R[o + 8] * d[2]];
}
