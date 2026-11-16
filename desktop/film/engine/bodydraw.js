// A character drawn from its real body: the posed mesh as cels with inked lines (body.js), what the hands hold and
// the cloth that hangs from the shoulders in the same depth picture, and the painted head on top — hidden wherever an
// arm, a hand or a blade passes in front of the face.
import { createCanvas } from '@napi-rs/canvas';
import { V, clamp, lerp, hex, qrot, shade as shadeHex } from './core.js';
import { poseBody, normalsOf, CelPainter, onBone, dirOnBone, faceShapes, inkOn, proxyNormals, roundNormals } from './body.js';
import { swordInHand } from './character.js';
import { drawHead, drawEyesOn } from './head.js';
import { makeHair, hairMeshes, capWithoutSideburns } from './hair.js';
import { pauldronMeshes } from './armour.js';
import { look } from './look.js';
import { finishFigure } from './finish.js';
import { tailorBody } from './tailor.js';
import { dressBody } from './wardrobe.js';
import { faceMarksOf, faceDecals, decalPaints, noseLine, unfoldX, FACE_FRONT } from './face3.js';
import { knuckleMarks, handLineWeight } from './hand3.js';

const SKIP = new Set(['eyes', 'teeth', 'tongue', 'lashes', 'haircap', 'hair', 'beard']);
const LAYER = { skin: 0, trousers: 1, boots: 2, shoes: 2, tunic: 3, wraps: 4, skirt: 4, coat: 5, cuirass: 6, vambrace: 6, greave: 6 };
let scratch = null;

/** Triangles of a lathe / tube between rings of points (each ring the same length); closed ends optional. */
function tube(rings, capA = true, capB = true) {
  const n = rings[0].length, pos = [], idx = [];
  for (const r of rings) for (const p of r) pos.push(p[0], p[1], p[2]);
  for (let i = 0; i < rings.length - 1; i++) for (let j = 0; j < n; j++) { const a = i * n + j, b = i * n + (j + 1) % n, c = (i + 1) * n + j, d = (i + 1) * n + (j + 1) % n; idx.push(a, c, b, b, c, d); }
  const cap = (ri, flip) => { const c = pos.length / 3, r = rings[ri], m = r.reduce((s, p) => [s[0] + p[0] / n, s[1] + p[1] / n, s[2] + p[2] / n], [0, 0, 0]); pos.push(m[0], m[1], m[2]); for (let j = 0; j < n; j++) { const a = ri * n + j, b = ri * n + (j + 1) % n; if (flip) idx.push(c, b, a); else idx.push(c, a, b); } };
  if (capA) cap(0, false); if (capB) cap(rings.length - 1, true);
  return { pos: Float32Array.from(pos), idx: Uint32Array.from(idx) };
}
const ring = (c, ax, e1, e2, r1, r2, n) => Array.from({ length: n }, (_, i) => { const a = (i / n) * Math.PI * 2, x = Math.cos(a) * r1, y = Math.sin(a) * r2; return [c[0] + e1[0] * x + e2[0] * y, c[1] + e1[1] * x + e2[1] * y, c[2] + e1[2] * x + e2[2] * y]; });
const along = (p, ax, d) => [p[0] + ax[0] * d, p[1] + ax[1] * d, p[2] + ax[2] * d];

/** What a body carries (sword, scabbard, shield, spear…), as meshes with their paints. */
export function carriedMeshes(ch, pose, lit = (c) => c, k = pose.height / 1.8) { return carried(ch, pose, lit, k); }
function carried(ch, pose, lit, k) {
  const d = ch.d, out = [], pr = pose.props ?? {}, rgbOf = (c) => hex(lit(c));
  const steel = (c) => ({ fill: rgbOf(c), shade: rgbOf(shadeHex(c, -0.38)), hi: rgbOf(shadeHex(c, 0.55)), hiAt: 0.78, thr: 0.0, group: 'blade', recv: false, metal: true });
  const flatP = (c, sh = -0.3) => ({ fill: rgbOf(c), shade: rgbOf(shadeHex(c, sh)) });
  const sw = pr.sword;
  if (sw && sw.visible && !d.hideSword) {
    const ax = qrot(sw.q, [0, 1, 0]), wd = qrot(sw.q, [0, 0, 1]), th = qrot(sw.q, [1, 0, 0]), L = (d.sword.len ?? 0.82) * (d.sword.scale ?? 1) * k, hw = (d.sword.width ?? 0.024) * k, p0 = sw.pos;
    const g0 = along(p0, ax, 0.12 * k), tip = along(p0, ax, 0.12 * k + L), curve = d.sword.curve ?? 0.035; // a slight curve towards the back edge
    const N = 7, rings = [];
    for (let i = 0; i <= N; i++) { const u = i / N, c = along(along(g0, ax, L * u), wd, -curve * L * u * u), w = hw * (u < 0.82 ? 1 - 0.12 * u : (1 - u) / 0.18 * 0.9 + 0.02), t = 0.0045 * k * (1 - 0.5 * u); rings.push(ring(c, ax, wd, th, w, t, 4)); }
    out.push({ ...tube(rings, true, true), paint: steel(d.sword.blade) });
    // guard: an oval plate; grip: wrapped handle; pommel cap
    out.push({ ...tube([ring(along(p0, ax, 0.108 * k), ax, wd, th, 0.044 * k, 0.03 * k, 12), ring(along(p0, ax, 0.122 * k), ax, wd, th, 0.044 * k, 0.03 * k, 12)]), paint: { ...flatP(d.sword.guard), hi: rgbOf(shadeHex(d.sword.guard, 0.4)), hiAt: 0.82, metal: true } });
    out.push({ ...tube([ring(along(p0, ax, -0.07 * k), ax, wd, th, 0.0155 * k, 0.012 * k, 8), ring(along(p0, ax, 0.108 * k), ax, wd, th, 0.0165 * k, 0.0125 * k, 8)], false, false), paint: flatP(d.sword.grip, -0.35) });
    out.push({ ...tube([ring(along(p0, ax, -0.088 * k), ax, wd, th, 0.012 * k, 0.01 * k, 8), ring(along(p0, ax, -0.07 * k), ax, wd, th, 0.019 * k, 0.015 * k, 8), ring(along(p0, ax, -0.066 * k), ax, wd, th, 0.0165 * k, 0.013 * k, 8)]), paint: flatP(d.sword.guard) });
    void tip;
  }
  const sc = pr.scabbard;
  if (sc && sc.visible && !d.hideSword) {
    // (made to the blade it holds: the blade begins at its mouth and ends inside it)
    const ax = qrot(sc.q, [0, 1, 0]), wd = qrot(sc.q, [0, 0, 1]), th = qrot(sc.q, [1, 0, 0]), L = ((d.sword.len ?? 0.82) * (d.sword.scale ?? 1) + 0.03) * k, curve = d.sword.curve ?? 0.035, rings = [];
    for (let i = 0; i <= 6; i++) { const u = i / 6; rings.push(ring(along(along(sc.pos, ax, L * u), wd, -curve * L * u * u), ax, wd, th, 0.03 * k * (1 - 0.25 * u), 0.012 * k, 8)); }
    out.push({ ...tube(rings), name: 'scabbard', paint: flatP(d.sword.scabbard ?? '#201a22', -0.4) });
  }
  const sh = pr.shield;
  if (sh && sh.visible) {
    const up = qrot(sh.q, [0, 1, 0]), sd = qrot(sh.q, [1, 0, 0]), nz = qrot(sh.q, [0, 0, 1]), R = (d.shield?.r ?? 0.4) * k, rings = [];
    for (const [rr, dz] of [[1, -0.012], [1, 0.012], [0.86, 0.03], [0.3, 0.055], [0.16, 0.085], [0.02, 0.1]]) rings.push(ring(along(sh.pos, nz, dz * k), nz, sd, up, R * rr, R * rr, 20));
    const m = tube(rings, true, true), n = 20, face = [], rim = [], back = [];
    for (let t = 0; t < m.idx.length; t += 3) { const r0 = Math.floor(Math.min(m.idx[t], m.idx[t + 1], m.idx[t + 2]) / n); (m.idx[t] >= rings.length * n && m.idx[t] === rings.length * n ? back : r0 === 0 ? rim : r0 >= 3 ? rim : face).push(m.idx[t], m.idx[t + 1], m.idx[t + 2]); }
    out.push({ pos: m.pos, idx: Uint32Array.from(face), paint: { ...flatP(d.shield?.color ?? '#7c2a2a'), group: 'shield' } });
    out.push({ pos: m.pos, idx: Uint32Array.from(rim), paint: { ...flatP(d.shield?.rim ?? '#9aa2ad'), hi: rgbOf('#e8edf2'), hiAt: 0.8 } });
    out.push({ pos: m.pos, idx: Uint32Array.from(back), paint: flatP(d.shield?.back ?? '#6a5240') });
  }
  for (const name of ['spear', 'staff']) {
    const q = pr[name]; if (!q || !q.visible) continue;
    const ax = qrot(q.q, [0, 1, 0]), wd = qrot(q.q, [0, 0, 1]), th = qrot(q.q, [1, 0, 0]), L = (name === 'spear' ? (d.spear?.len ?? 2.4) : (d.staff?.len ?? 1.7)), A = along(q.pos, ax, -L / 2), B = along(q.pos, ax, L / 2), r = 0.016 * k;
    out.push({ ...tube([ring(A, ax, wd, th, r, r, 8), ring(B, ax, wd, th, r * 0.92, r * 0.92, 8)]), paint: flatP(name === 'spear' ? (d.spear?.shaft ?? '#6b5340') : (d.staff?.color ?? '#7a6248')) });
    if (name === 'spear') out.push({ ...tube([ring(B, ax, wd, th, 0.012 * k, 0.008 * k, 4), ring(along(B, ax, 0.09), ax, wd, th, 0.036 * k, 0.008 * k, 4), ring(along(B, ax, 0.34), ax, wd, th, 0.002, 0.002, 4)]), paint: steel(d.spear?.head ?? '#cfd6de') });

  }
  // a bucket hangs from the hand by its bail: wooden staves, iron hoops, water inside
  const bk = pr.bucket;
  if (bk && bk.visible) {
    const dn = [0, -1, 0], e1 = [1, 0, 0], e2 = [0, 0, 1], top = along(bk.pos, dn, 0.17 * k), bot = along(top, dn, 0.27 * k), R0 = 0.15 * k, R1 = 0.12 * k;
    const wall = tube([ring(top, dn, e1, e2, R0, R0, 14), ring(along(top, dn, 0.135 * k), dn, e1, e2, (R0 + R1) / 2, (R0 + R1) / 2, 14), ring(bot, dn, e1, e2, R1, R1, 14)], false, true);
    out.push({ ...wall, paint: { ...flatP(d.bucket?.color ?? '#8a6a4a', -0.32), group: 'bucket' } });
    for (const u of [0.18, 0.8]) { const c = along(top, dn, 0.27 * k * u), r = lerp(R0, R1, u) * 1.03; out.push({ ...tube([ring(along(c, dn, -0.01 * k), dn, e1, e2, r, r, 14), ring(along(c, dn, 0.01 * k), dn, e1, e2, r, r, 14)]), paint: flatP('#3e3a3a', -0.3) }); }
    out.push({ ...tube([ring(along(top, dn, 0.035 * k), dn, e1, e2, R0 * 0.96, R0 * 0.96, 14), ring(along(top, dn, 0.036 * k), dn, e1, e2, R0 * 0.96, R0 * 0.96, 14)], true, true), paint: flatP(d.bucket?.water ?? '#3d6a7c', -0.2) });
    // the bail: from one side of the rim up to the fist and down to the other
    const side = (sg) => along(top, e1, sg * R0), bail = [side(-1), V.lerp(side(-1), bk.pos, 0.55), bk.pos, V.lerp(side(1), bk.pos, 0.55), side(1)];
    const wire = bail.map((p, i) => { const t = V.norm(V.sub(bail[Math.min(4, i + 1)], bail[Math.max(0, i - 1)])), a = V.norm(V.cross(t, [0, 0, 1])), b = V.cross(t, a); return ring(p, t, a, b, 0.006 * k, 0.006 * k, 5); });
    out.push({ ...tube(wire), paint: flatP('#3a3434', -0.3) });
  }
  return out;
}

/** A steel cap on the head bone, in the frame of the landmarks (as the hair is): dome, rim band, crest. */
function helmetMeshes(B, L, hg) {
  const cy = (L.crown[1] + L.nape[1]) / 2 - 0.005, cz = 0.03, rx = 0.083 * 1.2, ry = 0.12 * 1.13, rz = 0.117 * 1.15, eyeY = L.eyeL[1];
  const W = (p) => onBone(B, 'head', p), N = 28, R = 9, out = [];
  // where the rim comes down to, round the head: the brow in front, the ear at the sides, the nape behind
  const rimY = (az) => { const f = Math.cos(az); return f > 0 ? lerp(eyeY - 0.045, eyeY + 0.032, f) : lerp(eyeY - 0.045, L.nape[1] + 0.012, -f); };
  const pt = (az, y, k = 1) => { const q = Math.sqrt(Math.max(0.02, 1 - ((y - cy) / ry) ** 2)); return [Math.sin(az) * rx * q * k, y, cz + Math.cos(az) * rz * q * k]; };
  const pos = [], idx = [];
  for (let i = 0; i <= R; i++) for (let j = 0; j < N; j++) { const az = (j / N) * Math.PI * 2, y0 = rimY(az), top = cy + ry * 0.995, y = lerp(top, y0, Math.pow(i / R, 1.15)); const p = W(pt(az, y)); pos.push(p[0], p[1], p[2]); }
  for (let i = 0; i < R; i++) for (let j = 0; j < N; j++) { const a = i * N + j, b = i * N + ((j + 1) % N), c = (i + 1) * N + j, e = (i + 1) * N + ((j + 1) % N); idx.push(a, c, b, b, c, e); }
  out.push({ pos: Float32Array.from(pos), idx: Uint32Array.from(idx), c: hg.color ?? '#8a94a3', metal: true });
  // the band round the rim
  const band = []; for (let j = 0; j <= N; j++) { const az = (j / N) * Math.PI * 2, y = rimY(az); band.push(...[W(pt(az, y + 0.016, 1.03)), W(pt(az, y - 0.004, 1.035))]); }
  const bp = [], bi = []; band.forEach((p) => bp.push(p[0], p[1], p[2])); for (let j = 0; j < N; j++) { const a = j * 2; bi.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); }
  out.push({ pos: Float32Array.from(bp), idx: Uint32Array.from(bi), c: hg.band ?? '#5a6270', metal: true });
  if (hg.crest) { // a plume of horsehair along the top, from over the brow (+z is the face) back to the nape, taller behind
    const cp2 = [], ci = []; for (let i = 0; i <= 10; i++) { const u = i / 10, a = lerp(0.62, -2.05, u), y = cy + Math.cos(a) * ry * 1.02, z = cz + Math.sin(a) * rz * 1.02, h = 0.05 + 0.035 * Math.sin(Math.PI * Math.min(1, u * 1.25)); const lo = W([0, y, z]), hi2 = W([0, y + Math.cos(a) * h, z + Math.sin(a) * h - 0.018 * u]); cp2.push(lo[0], lo[1], lo[2], hi2[0], hi2[1], hi2[2]); }
    for (let i = 0; i < 10; i++) { const a = i * 2; ci.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); }
    out.push({ pos: Float32Array.from(cp2), idx: Uint32Array.from(ci), c: hg.crest });
  }
  return out;
}
/** A crown on the hair (design headgear { type: 'crown', color, gem, points, h }): a gold band round the skull, points rising from
 * it, a gem on the brow — on the head bone, in the frame of the landmarks (as the hair is). */
function crownMeshes(B, L, hg) {
  const skY = (L.crown[1] + L.nape[1]) / 2 - 0.005, skZ = 0.03, rx = 0.083, ry = 0.12, rz = 0.117, y0 = L.eyeL[1] + (hg.lift ?? 0.7) * (L.crown[1] - L.eyeL[1]);
  const at = (a, dy = 0, out = 0) => { const y = y0 + dy - 0.012 * Math.cos(a), q = Math.sqrt(Math.max(0.05, 1 - ((y - skY) / ry) ** 2)); return [Math.sin(a) * (rx * q + 0.026 + out), y, skZ + Math.cos(a) * (rz * q + 0.026 + out)]; };
  const W = (p) => onBone(B, 'head', p), N = 40, bh = hg.band ?? 0.026, ph = hg.h ?? 0.042, np = hg.points ?? 8, pos = [], idx = [];
  // the band: a ring a little outside the hair, leaning out at the top
  for (let j = 0; j <= N; j++) { const a = (j / N) * Math.PI * 2, lo = W(at(a, 0, 0)), hi = W(at(a, bh, 0.004)); pos.push(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]); }
  for (let j = 0; j < N; j++) { const q = j * 2; idx.push(q, q + 1, q + 2, q + 1, q + 3, q + 2); }
  // the points: a triangle on the band's top edge every 1/np of the way round, one over the brow
  for (let k = 0; k < np; k++) { const a = (k / np) * Math.PI * 2, w = (Math.PI / np) * 0.62, b0 = pos.length / 3, l = W(at(a - w, bh, 0.004)), r = W(at(a + w, bh, 0.004)), t = W(at(a, bh + ph, 0.012)); pos.push(l[0], l[1], l[2], r[0], r[1], r[2], t[0], t[1], t[2]); idx.push(b0, b0 + 1, b0 + 2); }
  const out = [{ pos: Float32Array.from(pos), idx: Uint32Array.from(idx), c: hg.color ?? '#e0b84a', metal: true }];
  // the gem on the brow: a small diamond standing on the band
  const g0 = W(at(0, bh * 0.5, 0.006)), up = V.norm(V.sub(W(at(0, bh, 0.006)), g0)), sd = V.norm(V.sub(W(at(0.2, bh * 0.5, 0.006)), g0)), gs = 0.011;
  const gp = [V.add(g0, V.mul(up, gs)), V.add(g0, V.mul(sd, gs * 0.8)), V.sub(g0, V.mul(up, gs)), V.sub(g0, V.mul(sd, gs * 0.8))];
  out.push({ pos: Float32Array.from(gp.flat()), idx: Uint32Array.from([0, 1, 2, 0, 2, 3]), c: hg.gem ?? '#2f8a5a' });
  return out;
}
/** The leaves and twig of a wreath, on the head bone: in the frame of the landmarks (as the hair is). */
function wreathMeshes(B, L, hg) {
  const skY = (L.crown[1] + L.nape[1]) / 2 - 0.005, skZ = 0.03, rx = 0.083, ry = 0.12, rz = 0.117, y0 = L.eyeL[1] + (hg.lift ?? 0.62) * (L.crown[1] - L.eyeL[1]);
  const at = (a, dy = 0, out = 0) => { const y = y0 + dy - 0.022 * Math.cos(a), q = Math.sqrt(Math.max(0.05, 1 - ((y - skY) / ry) ** 2)); return [Math.sin(a) * (rx * q + 0.024 + out), y, skZ + Math.cos(a) * (rz * q + 0.024 + out)]; };
  const W = (p) => onBone(B, 'head', p), out = [], col = hg.color ?? '#5f9a3c', n = hg.n ?? 20;
  // the twig
  const tw = []; for (let i = 0; i <= 36; i++) { const a = (i / 36) * Math.PI * 2, p = W(at(a)), q = W(at(a + 0.01)), t = V.norm(V.sub(q, p)), up = V.norm(V.sub(W(at(a, 0.01)), p)), sd = V.cross(t, up); tw.push(ring(p, t, up, sd, 0.0055, 0.0055, 5)); }
  out.push({ ...tube(tw), c: hg.twig ?? '#6a4a2a' });
  // the leaves: flat, pointed, lying on the twig and turned up or down
  const pos = [], idx = [], pos2 = [], idx2 = [];
  for (let i = 0; i < n; i++) {
    const a = ((i + 0.5) / n) * Math.PI * 2, sg = i % 2 ? 1 : -1, P0 = W(at(a, sg * 0.006, 0.004)), P1 = W(at(a + 0.24, sg * 0.072 + 0.012, 0.016)), mid = V.lerp(P0, P1, 0.5);
    const len = V.dist(P0, P1), ax = V.norm(V.sub(P1, P0)), outw = V.norm(V.sub(W(at(a + 0.16, sg * 0.015, 0.05)), mid)), side = V.norm(V.cross(ax, outw)), w = len * 0.36;
    const T = i % 3 ? pos : pos2, I = i % 3 ? idx : idx2, b = T.length / 3;
    // a leaf: narrow at the stem, widest a third of the way, a pointed tip; bowed out a little from the twig
    const at2 = (u, s2, bow) => V.add(V.add(V.lerp(P0, P1, u), V.mul(side, s2 * w)), V.mul(outw, bow));
    for (const p of [P0, at2(0.3, 1, 0.003), at2(0.66, 0.78, 0.005), P1, at2(0.66, -0.78, 0.005), at2(0.3, -1, 0.003)]) T.push(p[0], p[1], p[2]);
    I.push(b, b + 1, b + 5, b + 1, b + 2, b + 5, b + 2, b + 4, b + 5, b + 2, b + 3, b + 4);
  }
  out.push({ pos: Float32Array.from(pos), idx: Uint32Array.from(idx), c: col }, { pos: Float32Array.from(pos2), idx: Uint32Array.from(idx2), c: shadeHex(col, 0.16) });
  return out;
}

/** A hanging cloth (threads × rows of points) as a smooth sheet: every cell cut into finer ones along curves through the points. */
function sheet(cl, fine = 3) {
  const C = cl.cols, Rn = cl.rows, cols = (C - 1) * fine + 1, rows = (Rn - 1) * fine + 1, pos = new Float32Array(cols * rows * 3), idx = [];
  const cr = (p0, p1, p2, p3, t) => { const t2 = t * t, t3 = t2 * t; return [0, 1, 2].map((a) => 0.5 * (2 * p1[a] + (-p0[a] + p2[a]) * t + (2 * p0[a] - 5 * p1[a] + 4 * p2[a] - p3[a]) * t2 + (-p0[a] + 3 * p1[a] - 3 * p2[a] + p3[a]) * t3)); };
  const at = (c, r) => cl.p[clamp(c, 0, C - 1)][clamp(r, 0, Rn - 1)];
  for (let i = 0; i < cols; i++) for (let j = 0; j < rows; j++) {
    const c = Math.min(C - 2, Math.floor(i / fine)), u = i / fine - c, r = Math.min(Rn - 2, Math.floor(j / fine)), v = j / fine - r;
    const row = (rr) => cr(at(c - 1, rr), at(c, rr), at(c + 1, rr), at(c + 2, rr), u), p = cr(row(r - 1), row(r), row(r + 1), row(r + 2), v);
    pos.set(p, (i * rows + j) * 3);
  }
  for (let i = 0; i < cols - 1; i++) for (let j = 0; j < rows - 1; j++) { const a = i * rows + j, b = (i + 1) * rows + j, c = a + 1, d = b + 1; idx.push(a, b, c, c, b, d); }
  return { pos, idx: Uint32Array.from(idx) };
}

/** How each hand is held when the film does not say: closed round what it carries, loose otherwise. */
function autoHands(pose) {
  const pr = pose.props ?? {}, k = pose.height / 1.8, h = { L: 'relax', R: 'relax' };
  for (const s of ['L', 'R']) {
    const w = pose.j[`wrist.${s}`];
    for (const name of ['sword', 'spear', 'staff', 'bucket']) { const q = pr[name]; if (!q || !q.visible) continue; const ax = qrot(q.q, [0, 1, 0]), dv = V.sub(w, q.pos), al = V.dot(dv, ax), off = V.len(V.sub(dv, V.mul(ax, al))); if (off < 0.16 * k && Math.abs(al) < (name === 'sword' ? 0.3 : 1.4) * k) h[s] = 'grip'; }
    if (pose.fist?.[s]) h[s] = pose.fist[s] >= 1 ? 'fist' : ['relax', 'fist', pose.fist[s]];
  }
  if (pr.shield?.visible) h.L = 'fist';
  // (a sword in its scabbard is in no hand — unless a hand is on its hilt, or on the scabbard, for a draw)
  if (pr.sword?.visible && !swordInHand(pose)) { for (const s of ['L', 'R']) if (h[s] === 'grip' && !(pr.spear?.visible || pr.staff?.visible) && V.dist(pose.j[`wrist.${s}`], pr.sword.pos) > 0.2 * k && V.dist(pose.j[`wrist.${s}`], pr.scabbard.pos) > 0.2 * k) h[s] = 'relax'; }
  return h;
}

/** Paints for the parts of a body from the character's design sheet (and the body's own colours where the sheet is silent). */
function paintOf(ch, B, part, lit) {
  const d = ch.d, m = part.material, own = B.colors[part.name], rgbOf = (c) => hex(lit(c)), lin = (c) => '#' + c.map((v) => Math.round(255 * Math.pow(clamp(v), 1 / 2.2)).toString(16).padStart(2, '0')).join('');
  // dark cloth still shows its form: the lit tone is kept a clear step above the shadow tone
  const sep = (f, sh) => { const lf = f[0] * 0.3 + f[1] * 0.59 + f[2] * 0.11, ls = sh[0] * 0.3 + sh[1] * 0.59 + sh[2] * 0.11; if (lf - ls >= 20) return [f, sh]; const up = 20 - (lf - ls); return [[f[0] + up * 0.9, f[1] + up * 0.9, f[2] + up * 1.05], [sh[0] * 0.92, sh[1] * 0.9, sh[2] * 0.98]]; };
  const mk = (c, sh, extra = {}) => { const [f, s2] = sep(rgbOf(c), rgbOf(sh ?? shadeHex(c, -0.3))); return { fill: f, shade: s2, bias: (LAYER[part.name] ?? 1) * 0.0012, two: m === 'cloth', ...extra }; };
  const mkOld = (c, sh, extra = {}) => ({ fill: rgbOf(c), shade: rgbOf(sh ?? shadeHex(c, -0.3)), bias: (LAYER[part.name] ?? 1) * 0.0012, two: m === 'cloth', ...extra });
  void mkOld;
  const bd = d.body ?? {}; // the design's words for the real body: { tunic, coat, trousers, boots, wraps, cuirass, vambrace, greave, skirt, bands: { part: [[y0, y1, colour]] } }
  const bands = (bd.bands?.[part.name] ?? []).map(([a, b, c]) => [a * B.height, b * B.height, { fill: rgbOf(c), shade: rgbOf(shadeHex(c, -0.3)) }]);
  const withBands = (p) => (bands.length ? { ...p, bands } : p);
  if (m === 'skin') return withBands(mk(d.skin, d.skinShade ?? shadeHex(d.skin, -0.2), { thr: 0.02, group: 'skin', skin: true }));
  const pick = { tunic: bd.tunic ?? d.top?.color, coat: bd.coat ?? d.top?.coat ?? d.top?.color, trousers: bd.trousers ?? d.bottom?.color, skirt: bd.skirt ?? d.bottom?.color, boots: bd.boots ?? d.shoes?.color, shoes: bd.boots ?? d.shoes?.color,
    wraps: bd.wraps ?? d.armor?.bracer ?? d.top?.wrap, cuirass: bd.cuirass ?? d.armor?.plate, vambrace: bd.vambrace ?? d.armor?.bracer ?? d.armor?.plate, greave: bd.greave ?? d.armor?.greave ?? d.armor?.plate }[part.name] ?? (own ? lin(own) : '#777a84');
  if (m === 'metal') return withBands(mk(pick, shadeHex(pick, -0.36), { hi: rgbOf(shadeHex(pick, 0.5)), hiAt: 0.8, thr: 0.05, metal: true, soft: 0 }));
  return withBands(mk(pick, part.name === 'tunic' || part.name === 'coat' ? d.top?.shade : part.name === 'trousers' || part.name === 'skirt' ? d.bottom?.shade : undefined, part.name === 'coat' ? { group: bd.coatLine ? undefined : 'top' } : part.name === 'tunic' ? { group: 'top' } : {}));
}

/**
 * A robe's skirts as a mesh for the painter: { pos, idx, u (0 at the waist … 1 at the hem), paint } — the robe's colour
 * outside, its lining inside, its trim as a band along the hem. null when the character wears none.
 */
export function robeMesh(ch, lit = (c) => c) {
  const R = ch.d.robe, cl = ch.robe; if (!R || !cl) return null;
  const m = sheet(cl), rows = (cl.rows - 1) * 3 + 1, u = new Float32Array(m.pos.length / 3); for (let v = 0; v < u.length; v++) u[v] = (v % rows) / (rows - 1);
  const c = R.color ?? '#23242c', li = R.lining ?? shadeHex(c, -0.25), rgb = (x) => hex(lit(x)), tw = R.trimW ?? 0.06;
  const paint = { fill: rgb(c), shade: rgb(R.shade ?? shadeHex(c, -0.3)), two: true, thr: 0.0, group: 'robe', back: { fill: rgb(li), shade: rgb(shadeHex(li, -0.3)) },
    ...(R.trim ? { bands: [[1 - tw, 1.01, { fill: rgb(R.trim), shade: rgb(shadeHex(R.trim, -0.3)) }]] } : {}) };
  // the sheet is wound so that its inside faces the lens first: turned round, the outside is the robe's own colour
  const idx = new Uint32Array(m.idx.length); for (let t = 0; t < idx.length; t += 3) { idx[t] = m.idx[t]; idx[t + 1] = m.idx[t + 2]; idx[t + 2] = m.idx[t + 1]; }
  return { pos: m.pos, idx, u, paint };
}

/**
 * The paint of a garment's arms (its triangles on the arms are painted apart from its body): design.body.sleeves gives
 * the top's sleeves a colour of their own — a dark vest over a white robe — with a line where sleeve meets vest.
 */
/**
 * A top cut like a robe, or a shirt. design.body.collar: { style: 'cross' | 'v' | 'shirt' (see below), depth (m below the neck where the lapels cross or the
 * neck is cut to), w (the band along the lapels), inner (the under-robe that shows in the opening), trim (the band's
 * colour; none without it), to (where the lapels end, as a part of the body's height: the sash) } and design.body.sleeves
 * (sleeves of a colour of their own, set in at one clean seam at the shoulder). Returns `base` itself when the design
 * asks for neither, else its paint with a map of the cut: map(x, y) → a number into `maps` (named 'sleeve', 'inner',
 * 'trim', 'fold') or −1 for the top's own cloth — x from the middle of the body, y up from the ground, in the rest
 * pose; points of the back are given 10 m higher (a top's back has no opening). G: the measures of this body's top,
 * from topMeasures.
 */
export function topPaint(base, d, G, rgbOf) {
  const bd = d.body ?? {}, col = bd.collar ?? null, sl = bd.sleeves ?? null;
  if (!col && !sl) return base;
  const maps = [], tone = (name, c, extra = {}) => { maps.push({ name, fill: rgbOf(c), shade: rgbOf(shadeHex(c, -0.3)), two: base.two, bias: base.bias, ...extra }); return maps.length - 1; };
  const SLEEVE = sl ? tone('sleeve', sl, { group: 'sleeve' }) : -1, INNER = col ? tone('inner', col.inner ?? (col.style === 'shirt' ? d.skin ?? '#e9b995' : '#ece6da'), { group: 'inner' }) : -1, TRIM = col?.trim ? tone('trim', col.trim, { group: 'trim' }) : -1;
  const shirt = col?.style === 'shirt', cross = col?.style !== 'v' && !shirt, FOLD = col && cross ? tone('fold', shadeHex(col.inner ?? '#ece6da', -0.34), { group: 'inner' }) : -1;
  const [sx, sy] = G.shoulder, nw = G.neckW ?? 0.062, w = col?.w ?? 0.03, yc = G.neckY - (col?.depth ?? 0.12), k = nw / Math.max(0.02, G.top - yc), n = Math.sqrt(1 + k * k), gap = 0.014;
  // a shirt (style: 'shirt'): the neck open on the skin, a collar's two leaves along the opening running out to points, a
  // placket with a stitched edge and buttons down to the hem (collar.buttons: metres between them), a pocket on the
  // wearer's left breast (collar.pocket), a tie (collar.tie: { color, stripe, len, w, loose: how far the knot is pulled down })
  const up = (c, q) => c.map((v) => Math.max(0, Math.min(255, q < 0 ? v * (1 + q) : v + (255 - v) * q)));
  const own = (name, fill, shade, extra = {}) => { maps.push({ name, fill, shade, two: base.two, bias: base.bias, group: base.group, ...extra }); return maps.length - 1; };
  const tie = shirt ? col.tie ?? null : null;
  const LEAF = shirt ? own('leaf', up(base.fill, 0.14), up(base.shade, 0.1), { group: 'leaf' }) : -1, SEAM = shirt ? own('seam', up(base.fill, -0.24), up(base.shade, -0.2)) : -1;
  const BUTTON = shirt && col.buttons ? own('button', rgbOf(col.button ?? '#eef0f4'), rgbOf(shadeHex(col.button ?? '#eef0f4', -0.25))) : -1;
  const TIE = tie ? tone('tie', tie.color ?? '#6e2630', { group: 'tie' }) : -1, STRIPE = tie?.stripe ? tone('stripe', tie.stripe, { group: 'tie' }) : -1;
  const hem = G.hem ?? G.sashY - 0.12, kY = G.neckY - (col?.depth ?? 0.12) - (tie?.loose ?? 0), tLen = tie?.len ?? 0.36, tW = tie?.w ?? 0.028, pkY = sy - 0.045;
  const shirtAt = (x, y, ax) => {
    const e = nw - k * (G.top - y), f = (ax - e) / n;
    if (y >= yc && f < 0) return INNER;
    if (tie) {
      // the knot (a small wedge, wider at its top), then the blade: widening to four fifths of its length, then cut to a point
      const kt = kY + 0.012, kb = kY - 0.028, end = kY - tLen;
      let hw = -1;
      if (y <= kt && y > kb) hw = 0.013 + 0.007 * ((y - kb) / (kt - kb));
      else if (y <= kb && y > end) { const u = (kb - y) / (kb - end); hw = u < 0.8 ? 0.012 + (tW - 0.012) * (u / 0.8) : tW * (1 - (u - 0.8) / 0.2); }
      if (hw > 0 && ax < hw) { if (STRIPE >= 0) { const q = (x * 0.9 + y) / 0.024, r = q - Math.floor(q); if (r < 0.3) return STRIPE; } return TIE; }
    }
    // the leaves of the collar: along each edge of the opening, their outer corners hanging to a point below where the edges meet
    if (f >= 0 && f < w + 0.0028) { const low = yc - 0.038 * Math.min(1, f / w); if (y >= low) return f < w ? (y < low + 0.0026 ? SEAM : LEAF) : SEAM; }
    if (y < yc - 0.004 && y > hem) {
      if (BUTTON >= 0 && ax < 0.006) { const sp = col.buttons, i = Math.round((yc - 0.03 - y) / sp), by = yc - 0.03 - i * sp; if (i >= 0 && by > hem + 0.03 && x * x + (y - by) * (y - by) < 0.0055 * 0.0055) return BUTTON; }
      if (ax >= 0.0125 && ax < 0.0147) return SEAM;
    }
    if (col.pocket && x > 0.06 && x < 0.135 && y < pkY && y > pkY - 0.085) { if (x < 0.0626 || x > 0.1324 || y < pkY - 0.0824 || (y > pkY - 0.016 && y < pkY - 0.0134) || y > pkY - 0.0026) return SEAM; }
    return -1;
  };
  const map = (x, yy) => {
    const back = yy > 5, y = back ? yy - 10 : yy, ax = x < 0 ? -x : x;
    // the sleeve: beyond a seam that runs down from the shoulder to the armpit (leaning in a little towards the neck at its top)
    if (SLEEVE >= 0 && ax - sx + 0.012 + 0.2 * (y - sy) > 0) return SLEEVE;
    if (!col) return -1;
    if (shirt) return back ? (y > G.backTop - 0.03 ? LEAF : -1) : shirtAt(x, y, ax);
    if (back) return TRIM >= 0 && y > G.backTop - w ? TRIM : -1;
    if (y < G.sashY) return -1;
    // the lapels' edges: from beside the neck down to where they meet; the cloth of each lies outside its edge
    const e = nw - k * (G.top - y), f1 = (x - e) / n, f2 = (-x - e) / n;
    if (cross) { // the lapel from the wearer's left lies over the other, all the way down to the sash
      if (f1 >= 0) return TRIM >= 0 && f1 < w ? TRIM : -1;
      if (f2 >= 0) return TRIM >= 0 && f2 < w ? TRIM : -1;
      return f1 < -gap && f1 > -gap - 0.0035 ? FOLD : INNER; // (the under-robe is folded over the same way: its own edge shows as a line)
    }
    if (y < yc) return -1;
    const f = (ax - e) / n; return f >= 0 ? (TRIM >= 0 && f < w ? TRIM : -1) : INNER;
  };
  return { ...base, map, maps };
}
/**
 * Armour of small plates (lamellar): design.body.plates: { <part>: { h: 0.05, w: 0.04 (m, a plate), lace (the colour of
 * the cord it is laced with; none without), from, to (m up from the ground in the rest pose: only that stretch of the
 * part is plated) } }. Returns `base` with a map of the plates: map(x, y) → a number into `maps` — 'lip' (a plate's lower
 * edge, where it catches the light), 'under' (its top, in the shadow of the row lapped over it), 'gap' (the line between
 * two plates of a row), 'lace' — or −1 for the plate's own face. Rows are set half a plate over, like bricks. x runs round
 * the body, y up from the ground; points of the back are given 10 m higher, as for a top's cut.
 */
export function platePaint(base, o, rgbOf) {
  const h = o.h ?? 0.05, w = o.w ?? 0.04, y0 = o.from ?? -1e9, y1 = o.to ?? 1e9, maps = [];
  const up = (c, k) => c.map((v) => Math.max(0, Math.min(255, k < 0 ? v * (1 + k) : v + (255 - v) * k)));
  const tone = (name, fill, shade) => { maps.push({ name, fill, shade, two: base.two, bias: base.bias, metal: base.metal, soft: 0, group: base.group }); return maps.length - 1; };
  const LIP = tone('lip', up(base.fill, 0.24), up(base.shade, 0.16)), UNDER = tone('under', up(base.fill, -0.42), up(base.shade, -0.3)), GAP = tone('gap', up(base.fill, -0.5), up(base.shade, -0.36));
  const LACE = o.lace ? tone('lace', rgbOf(o.lace), up(rgbOf(o.lace), -0.35)) : -1;
  const map = (x, yy) => {
    const y = yy > 5 ? yy - 10 : yy;
    if (y < y0 || y > y1) return -1;
    const r = Math.floor(y / h), v = y / h - r, xo = x / w + (r & 1 ? 0.5 : 0), u = xo - Math.floor(xo);
    if (v > 0.84) return UNDER;
    if (u < 0.06 || u > 0.94) return GAP;
    if (v < 0.15) return LIP;
    // two stitches of the cord through every plate
    if (LACE >= 0 && v > 0.5 && v < 0.7 && ((u > 0.26 && u < 0.36) || (u > 0.64 && u < 0.74))) return LACE;
    return -1;
  };
  return { ...base, map, maps };
}
/** Where every point of a part lies round the body and up it, for a map of plates: two numbers a vertex (round the body's own middle, in metres along its girth; height). */
export function girthXY(p) {
  if (p.girth) return p.girth;
  const P = p.rest0 ?? p.pos, n = p.n, a = new Float32Array(n * 2);
  let zc = 0, m = 0; for (const i of p.idxCore) { zc += P[i * 3 + 2]; m++; } zc = m ? zc / m : 0;
  let rr = 0; for (const i of p.idxCore) rr += Math.hypot(P[i * 3], P[i * 3 + 2] - zc); rr = m ? rr / m : 0.15;
  for (let v = 0; v < n; v++) { a[v * 2] = Math.atan2(P[v * 3], P[v * 3 + 2] - zc) * rr; a[v * 2 + 1] = P[v * 3 + 1]; }
  return (p.girth = a);
}
/**
 * The measures of a body's top for topPaint, and its triangles sorted by front and back (a map of the cut is laid on
 * each side by itself): { G, xyF, xyB (two numbers a vertex: x, y — the back's 10 m higher), coreF, coreB, limbF, limbB }.
 */
export function topMeasures(B, p, d) {
  if (p.top) return p.top;
  const P = p.rest0 ?? p.pos, n = p.n, lm = B.landmarks, si = B.bi['upperarm01.L'], sh = si !== undefined ? [B.head[si * 3], B.head[si * 3 + 1], B.head[si * 3 + 2]] : [0.23, (lm.neckY ?? 1.5) - 0.11, 0];
  const zs = sh[2]; let top = -1e9, backTop = -1e9, ringW = 0;
  let hem = 1e9; for (const v of p.idxCore) if (P[v * 3 + 1] < hem) hem = P[v * 3 + 1];
  for (let v = 0; v < n; v++) { const x = Math.abs(P[v * 3]), y = P[v * 3 + 1], z = P[v * 3 + 2]; if (z > zs) { if (x < 0.1 && y > top) top = y; } else if (y > backTop) backTop = y; }
  for (let v = 0; v < n; v++) { const x = Math.abs(P[v * 3]), y = P[v * 3 + 1]; if (P[v * 3 + 2] > zs && y > top - 0.02 && x < 0.12 && x > ringW) ringW = x; }
  const xyF = new Float32Array(n * 2), xyB = new Float32Array(n * 2);
  for (let v = 0; v < n; v++) { xyF[v * 2] = xyB[v * 2] = P[v * 3]; xyF[v * 2 + 1] = P[v * 3 + 1]; xyB[v * 2 + 1] = P[v * 3 + 1] + 10; }
  const sort = (idx) => { const f = [], b = []; for (let t = 0; t + 2 < idx.length; t += 3) { const z = (P[idx[t] * 3 + 2] + P[idx[t + 1] * 3 + 2] + P[idx[t + 2] * 3 + 2]) / 3; (z > zs ? f : b).push(idx[t], idx[t + 1], idx[t + 2]); } return [Uint32Array.from(f), Uint32Array.from(b)]; };
  const [coreF, coreB] = sort(p.idxCore), [limbF, limbB] = sort(p.idxLimb), to = d?.body?.collar?.to;
  return (p.top = { G: { neckY: lm.neckY ?? top - 0.03, top, backTop, sashY: to !== undefined ? to * B.height : (lm.hipY ?? 0.96) + 0.14, shoulder: [sh[0], sh[1]], neckW: Math.max(0.045, ringW * 0.8), hem: hem < 1e8 ? hem : undefined }, xyF, xyB, coreF, coreB, limbF, limbB });
}
/**
 * The paint of a head of hair: its two tones, a sheen, and — with design.hair.strands (0…1) — strands laid along every
 * lock (darker ones, a lighter streak beside the ridge), the way hair is brushed in with a few strokes per lock. With
 * hair.sheen (0…1) the band of light across the hair stands higher on the ridge of each lock than at its edges.
 * Locks are numbered across from −1 to 1 (hair.js gives that with every mesh). o: { edge }.
 */
/**
 * What a design asks to have painted on its face (design.face: { beard: { style, cheek, color?, density? … }, scars: [{
 * from, to }], lines, frown } — see faceDecals): a beard takes the hair's colour and shade unless it has its own.
 * rgbOf: how a colour of the sheet becomes the picture's (the light of the place); default: as it is.
 */
export function faceMarkOptions(d, rgbOf = (c) => hex(c)) {
  const fd = d.face ?? {}, out = {};
  if (fd.beard) { const own = fd.beard.color, c = own ?? d.hair?.color ?? '#28221f', sh = fd.beard.shade ?? (own ? shadeHex(own, -0.34) : d.hair?.shade ?? shadeHex(c, -0.34)); out.beard = { ...fd.beard, color: rgbOf(c), shade: rgbOf(sh) }; }
  if (fd.scars?.length) out.scars = fd.scars;
  if (fd.lines) out.lines = fd.lines;
  if (fd.frown) out.frown = fd.frown;
  return out;
}
/** A beard painted on the face replaces the body model's own beard (a shell of hair round the jaw). */
export function paintedBeard(d) { return !!d.face?.beard; }
/** Is the body model's own beard drawn? Not under a painted one, and not at all with design.face.beard: false (a woman, a child). */
export function bodyBeard(d) { return d.face?.beard !== false && !paintedBeard(d); }

export function hairPaint(d, rgbOf, o = {}) {
  const hc = d.hair.color, fill = rgbOf(hc), shade = rgbOf(d.hair.shade ?? shadeHex(hc, -0.34)), hi = rgbOf(d.hair.shine ?? shadeHex(hc, 0.32)), k = clamp(d.hair.strands ?? 0);
  const base = { fill, shade, hi, hiAt: 0.9, thr: 0.1, group: 'hair', hairy: true, soft: 0, bias: 0.002, noAO: true, recv: false, castHair: true, ...(o.edge ? { edge: o.edge } : {}) };
  if (!(k > 0)) return base;
  const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t)), deep = shade.map((v) => v * 0.72), zig = clamp(d.hair.sheen ?? 0) * 0.1;
  const tone = (f, s) => ({ ...base, fill: f, shade: s, zig });
  const dark = tone(mix(fill, shade, 0.8 * k), mix(shade, deep, 0.7 * k)), soft = tone(mix(fill, shade, 0.45 * k), mix(shade, deep, 0.35 * k)), light = tone(mix(fill, hi, 0.42 * k), mix(shade, fill, 0.4 * k)), fine = tone(mix(fill, hi, 0.24 * k), mix(shade, fill, 0.22 * k));
  return { ...base, zig, bands: [[-0.86, -0.7, dark], [-0.34, -0.06, light], [0.3, 0.38, soft], [0.5, 0.6, dark], [0.74, 0.8, fine]] };
}
export function limbPaint(paint, partName, d, rgbOf) {
  const c = partName === 'tunic' ? d.body?.sleeves : null; if (!c) return paint;
  const f = rgbOf(c), sh = rgbOf(d.body?.sleevesShade ?? shadeHex(c, -0.22));
  return { ...paint, fill: f, shade: sh, group: 'sleeve', bands: undefined, bandIds: undefined };
}

/** The hands of a figure: what the film asks for, hand by hand; a hand it leaves out is held as autoHands would hold it. */
export function handsFor(pose, asked) { return asked ? { ...autoHands(pose), ...asked } : autoHands(pose); }

/**
 * Draw a character whose pose carries its real body (pose.body = { B, t, xf, hands }). Same arguments as Character.draw;
 * o.sun: the light's direction in the world. Returns false when the body cannot be drawn (the caller falls back).
 */
const TRUNK = ['root', 'spine05', 'spine04', 'spine03', 'spine02', 'spine01', 'neck01']; // the line a trunk is round about, from the hips to the neck
export function drawBodyMesh(ch, g, cam, pose, face, lit, o) {
  const d = ch.d, F = ch.frame(pose), k = F.k, pb = pose.body, B = pb.B;
  const zc = cam.toCam(F.pel)[2]; if (zc < 0.15) return true;
  const ppm = cam.ppm(zc), hpx = pose.height * ppm, lw = clamp(0.0052 * ppm * d.inkScale, 0.9, 4.2), ink = lit(d.ink), rs = cam.H / 1080, lwB = clamp(0.0046 * ppm * d.inkScale, 0.8 * rs, 2.6 * rs);
  const real = d.realHead !== false && B.bi.head !== undefined && !!B.landmarks.eyeL; // the body's own head (face, eyes, teeth) instead of the painted one
  // the eyes and brows of a real head are drawn (large, clear, the way drawn faces have them) unless the sheet asks for the sculpted ones
  const drawn = real && (d.face?.eyes3 ?? 'drawn') === 'drawn';
  tailorBody(B, d); // the cut of its clothes (design.bottom.fit): once for each cut, in the body's rest shape
  const wear = dressBody(B, d), wearIds = {}; // clothes made by the engine for this body (design.wear): once for each set
  poseBody(B, pb.t, pb.xf, handsFor(pose, pb.hands), real ? (drawn ? { ...(face ?? {}), eyeOpen: 1, blink: 0, eyeSmile: 0, browTilt: 0, browL: 0, browR: 0 } : (face ?? {})) : null, Array.isArray(pb.after) ? pb.after : null);
  if (typeof pb.after === 'function') pb.after(B); // a film's own change to the posed body (moves and turns of kit/combat.js are applied by poseBody)
  const sun = V.norm(o.sun ?? [-0.45, 0.7, 0.5]);
  // the light a cel is drawn under leans towards the lens, so a face-on figure is never all shadow
  const L = V.norm(V.add(V.mul(sun, 0.78), V.add(V.mul(cam.F, -0.34), V.mul(cam.U, 0.22))));
  const ss = hpx > 900 ? 2 : hpx > 240 ? 2 : 3;
  const pm = look()?.paint?.mode === 'paint';
  const cp = new CelPainter(cam, { ss, light: L, ink: hex(ink), lw: lwB * (pm ? 2.1 : 1.75), lwIn: lwB * (pm ? 1.05 : 0.95), rimColor: typeof o.rim === 'string' ? hex(o.rim) : Array.isArray(o.rim) ? o.rim : undefined, rimAmt: o.rimAmt }); // (the scene's rim light gives the rim its colour: a warm lamp, a cold moon)
  const hemPx = clamp(0.0055 * ppm, 1.2, 16); // hems and hairlines: notches of the mesh edge narrower than half a centimetre are closed
  const handOw = handLineWeight(0.016 * (pose.height / 1.8) * ppm);
  const limbIds = new Set(), eyeIds = new Set(), faceIds = new Set(), handIds = new Set(); // faceIds: the skin of the face itself (with the tones drawn on it); handIds: the bare skin of arms and hands
  const rgbOf = (c) => hex(lit(c)), fd = d.face ?? {}, lmk = B.landmarks, eyeY = lmk.eyeL?.[1] ?? 0, eyeZ = lmk.eyeL?.[2] ?? 0;
  // the middle of the head: anime shading treats a face and its hair as one round form, so their normals lean out from
  // here (a clean terminator, no patches where a low-poly surface turns), blended with the surface's own normal
  const headC = real && lmk.eyeL ? onBone(B, 'head', [0, eyeY + 0.025, eyeZ - 0.088]) : null;
  const rounded = (pos, nrm, k, near = 0) => {
    if (!headC) return nrm; const out = new Float32Array(nrm.length);
    for (let v = 0; v < nrm.length / 3; v++) {
      const dx = pos[v * 3] - headC[0], dy = pos[v * 3 + 1] - headC[1], dz = pos[v * 3 + 2] - headC[2], dl = Math.hypot(dx, dy, dz) || 1;
      const w = near ? k * (1 - Math.min(1, Math.max(0, (dl - near) / (near * 0.6)))) : k;
      let nx = nrm[v * 3] * (1 - w) + (dx / dl) * w, ny = nrm[v * 3 + 1] * (1 - w) + (dy / dl) * w, nz = nrm[v * 3 + 2] * (1 - w) + (dz / dl) * w; const l = Math.hypot(nx, ny, nz) || 1;
      out[v * 3] = nx / l; out[v * 3 + 1] = ny / l; out[v * 3 + 2] = nz / l;
    }
    return out;
  };
  const mouthShut = !face || (faceShapes(face).w['mouth-open'] ?? 0) < 0.06;
  const LM = B.landmarks ?? {}, headBall = B.bi.head !== undefined && LM.crown && LM.nape && LM.eyeL ? onBone(B, 'head', [0, (LM.crown[1] + LM.nape[1]) / 2, (LM.eyeL[2] + LM.nape[2]) / 2]) : null;
  const trunkNames = TRUNK.filter((n) => B.bi[n] !== undefined), trunkIds = new Set(trunkNames.map((n) => B.bi[n])), trunkAxis = trunkNames.length > 2 && B.P ? trunkNames.map((n) => { const i = B.bi[n]; return onBone(B, i, [B.P[i * 3], B.P[i * 3 + 1], B.P[i * 3 + 2]]); }) : null;
  for (const p of B.parts) {
    if (real && (p.name === 'eyes' || p.name === 'teeth' || p.name === 'tongue' || p.name === 'lashes')) {
      const nr = normalsOf(p.out, p.idx, p.nrm);
      if (drawn && (p.name === 'eyes' || p.name === 'lashes')) { if (p.name === 'eyes') { const sk = rgbOf(d.skin); eyeIds.add(cp.add(p.out, p.idx, { fill: sk, shade: sk, flat: true, line: false, group: 'skin', bias: 0.003 }, nr)); } continue; }
      if (p.name === 'eyes') {
        // the iris and the pupil are rings round the line of sight, so the eyes turn without the eyeballs turning
        const look = face?.look ?? [0, 0], gz = V.norm(dirOnBone(B, 'head', [clamp(look[0], -1, 1) * 0.42, clamp(look[1], -1, 1) * 0.3, 1]));
        const cL = onBone(B, 'head', lmk.eyeL), cR = onBone(B, 'head', lmk.eyeR), u = p.uEye ?? (p.uEye = new Float32Array(p.n));
        for (let v = 0; v < p.n; v++) { const x = p.out[v * 3], y = p.out[v * 3 + 1], z = p.out[v * 3 + 2], dl = (x - cL[0]) ** 2 + (y - cL[1]) ** 2 + (z - cL[2]) ** 2, dr = (x - cR[0]) ** 2 + (y - cR[1]) ** 2 + (z - cR[2]) ** 2, c = dl < dr ? cL : cR, l = Math.sqrt(Math.min(dl, dr)) || 1; u[v] = Math.acos(clamp(((x - c[0]) * gz[0] + (y - c[1]) * gz[1] + (z - c[2]) * gz[2]) / l, -1, 1)); }
        const ic = fd.eye?.color ?? '#6b4a2a', ir = (fd.eye?.iris3 ?? 0.56) * (face?.pupil ? 0.9 + 0.1 * face.pupil : 1), pu = ir * 0.42 * (face?.pupil ?? 1);
        const id = cp.add(p.out, p.idx, { fill: [250, 250, 252], shade: [212, 216, 230], thr: -0.2, line: false, group: 'eye', bias: 0.0006,
          bands: [[0, pu, { fill: [16, 12, 16], shade: [16, 12, 16], flat: true }], [pu, ir * 0.82, { fill: rgbOf(ic), shade: rgbOf(shadeHex(ic, -0.35)), thr: 0.15 }], [ir * 0.82, ir, { fill: rgbOf(shadeHex(ic, -0.55)), shade: rgbOf(shadeHex(ic, -0.6)), flat: true }]] }, nr, u);
        eyeIds.add(id);
      } else if ((p.name === 'teeth' || p.name === 'tongue') && mouthShut) { /* lips that only touch show no teeth between them: a closed mouth is a line */ }
      else if (p.name === 'teeth') cp.add(p.out, p.idx, { fill: [244, 240, 232], shade: [200, 190, 190], line: false, group: 'mouth', thr: -0.3 }, nr);
      else if (p.name === 'tongue') cp.add(p.out, p.idx, { fill: rgbOf('#b0565a'), shade: rgbOf('#7a3238'), line: false, group: 'mouth' }, nr);
      else cp.add(p.out, p.idx, { fill: hex(ink), shade: hex(ink), flat: true, two: true, bias: 0.002, line: false, group: 'eye' }, nr);
      continue;
    }
    if (wear && wear.hide.has(p.name)) continue; // the Stage's own garment under clothes the engine made
    if (real && p.name === 'beard' && !bodyBeard(d)) continue;
    if (real && (p.name === 'haircap' || p.name === 'beard') && d.hair) { const hc = p.name === 'beard' ? (d.beard?.color ?? d.hair.color) : d.hair.color; cp.add(p.out, p.name === 'haircap' && d.hair.sideburns === false ? capWithoutSideburns(B) : p.idx, { fill: rgbOf(hc), shade: rgbOf(d.hair.shade ?? shadeHex(hc, -0.3)), two: true, bias: 0.003, group: 'hair', noAO: true, recv: false, castHair: p.name !== 'beard', hem: hemPx }, rounded(p.out, normalsOf(p.out, p.idx, p.nrm), 0.6)); continue; }
    if (SKIP.has(p.name)) continue;
    // wrist wraps of the body model lie under a long sleeve: without a colour of their own on the sheet they would only poke through it
    if (p.name === 'wraps' && d.top?.sleeve === 'long' && !(d.body?.wraps ?? d.armor?.bracer ?? d.top?.wrap)) continue;
    const paint = paintOf(ch, B, p, lit); let nr = normalsOf(p.out, p.idx, p.nrm);
    const sk = wear && p.material === 'skin', iCore = sk ? wear.skin.idxCore : p.idxCore, iLimb = sk ? wear.skin.idxLimb : p.idxLimb; // (skin under the engine's clothes is not drawn)
    // the trunk is lit as one round form (proxy normals: a cylinder round the spine), so a shirt gets one clean shadow
    // shape instead of a patch on every fold; how much each bit of a part hangs on the spine is found once
    if (trunkAxis && p.si && p.sw && d.body?.proxy !== false) {
      if (p.trunkW === undefined) { const a = new Float32Array(p.n); let any = false; for (let v = 0; v < p.n; v++) { let w = 0; for (let k = 0; k < 4; k++) if (trunkIds.has(p.si[v * 4 + k])) w += p.sw[v * 4 + k]; a[v] = w; if (w > 0.02) any = true; } p.trunkW = any ? a : null; }
      if (p.trunkW) nr = proxyNormals(p.out, nr, trunkAxis, p.trunkW, d.body?.proxy ?? 0.5);
    }
    // the head's skin is lit as a ball (round normals): the edge between light and shadow is one clean curve across the
    // face, not the facets of its mesh; the face keeps part of its own relief (design.body.round: 0…1, false: off)
    if (headBall && p.material === 'skin' && p.si && p.sw && d.body?.round !== false) {
      if (p.headW === undefined) { const a = new Float32Array(p.n), hb = B.bi.head; let any = false; for (let v = 0; v < p.n; v++) { let w = 0; for (let k = 0; k < 4; k++) if (p.si[v * 4 + k] === hb) w += p.sw[v * 4 + k]; a[v] = w; if (w > 0.02) any = true; } p.headW = any ? a : null; }
      if (p.headW) nr = roundNormals(p.out, nr, headBall, p.headW, d.body?.round ?? 0.55);
    }
    if (p.material !== 'skin' && paint.hem === undefined) paint.hem = hemPx; // a garment's mesh ends in teeth (a short sleeve, a shirt's lower edge): drawn, its edge is smooth
    // a top cut like a robe (crossed lapels over an under-robe, set-in sleeves): painted by a map of its cut, front and back each by itself
    if (p.name === 'tunic' && (d.body?.collar || d.body?.sleeves)) {
      const T = topMeasures(B, p, d), tp = topPaint({ ...paint, group: paint.group ?? p.name }, d, T.G, rgbOf);
      for (const [idx, xy, limb] of [[T.coreF, T.xyF, 0], [T.coreB, T.xyB, 0], [T.limbF, T.xyF, 1], [T.limbB, T.xyB, 1]]) { if (!idx.length) continue; const pp = { ...tp }, id = cp.add(p.out, idx, pp, nr, p.u, xy); if (limb) { limbIds.add(id); for (const b of [...(pp.bandIds ?? []), ...(pp.mapIds ?? [])]) limbIds.add(b); } }
      continue;
    }
    // armour of small plates (design.body.plates): the part's body is painted by a map of its plates, laid round it
    const pl8 = d.body?.plates?.[p.name];
    if (pl8 && p.idxCore.length) cp.add(p.out, p.idxCore, platePaint({ ...paint, group: paint.group ?? p.name }, pl8, rgbOf), nr, p.u, girthXY(p));
    else if (iCore.length) cp.add(p.out, iCore, { ...paint, group: paint.group ?? p.name }, nr, p.u);
    if (iLimb.length) { const pl = limbPaint({ ...paint, group: paint.group ?? p.name }, p.name, d, rgbOf); const id = cp.add(p.out, iLimb, pl, nr, p.u); limbIds.add(id); for (const b of pl.bandIds ?? []) limbIds.add(b); if (p.material === 'skin') handIds.add(id); }
    // fingers: each its own part of the skin, so two that lie side by side have a line between them (none where they leave the palm)
    // the hand itself and each finger: parts of the skin of their own, drawn with a finer outline (a hand is a small form);
    // two fingers side by side have a line between them, a finger or the thumb one only where it lies over the hand
    if (p.idxHand?.length) { const id = cp.add(p.out, p.idxHand, { ...paint, bands: undefined, group: paint.group ?? p.name, ow: handOw }, nr); limbIds.add(id); handIds.add(id); }
    if (p.idxFingers) p.idxFingers.forEach((fi, f) => { if (fi.length) { const id = cp.add(p.out, fi, { ...paint, bands: undefined, group: `finger${f + 1}`, joins: paint.group ?? p.name, ow: handOw }, nr); limbIds.add(id); handIds.add(id); } });
    if (real && p.material === 'skin' && p.idxHead.length) {
      // the face: lit almost everywhere (a drawn face keeps its shadows to the jaw and the far cheek); brows ride on the skin
      const xy = p.faceXY ?? (p.faceXY = (() => { const a = new Float32Array(p.n * 2), base = p.base ?? p.pos; for (let v = 0; v < p.n; v++) { a[v * 2] = unfoldX(base[v * 3], base[v * 3 + 2], eyeZ - FACE_FRONT); a[v * 2 + 1] = base[v * 3 + 1] - eyeY; } return a; })());
      const br = fd.brow3 ?? {}, by = br.y ?? 0.0235, th = br.thick ?? 0.0036, x0 = br.x0 ?? 0.011, x1 = br.x1 ?? 0.058, arch = br.arch ?? 0.0055, tilt = br.tilt ?? 0.0;
      const bc = d.hair?.color ?? '#1c1a22';
      const brow = (x, y) => { const ax = x < 0 ? -x : x; if (ax < x0 || ax > x1) return -1; const u = (ax - x0) / (x1 - x0), yc = by + arch * Math.sin(Math.PI * Math.min(1, u * 1.15)) - tilt * (1 - u) - 0.004 * u * u, h = th * (1 - 0.62 * u) * (u < 0.08 ? 0.5 + u * 6.25 : 1); return Math.abs(y - yc) < h ? 0 : -1; };
      // the features of this head (found once from its own shape) and the drawn tones that give the face form: the nose's
      // shadow side (away from the light) and the shadow under it, the lips, the crease of the lids — they ride on the skin
      let dec = null, decP = [];
      if (fd.detail !== false) {
        const M = B.faceMarks ?? (B.faceMarks = (() => {
          const base = p.base ?? p.pos, hv = new Set(p.idxHead), jaw = new Set(B.jawKids), lower = new Float32Array(p.n);
          for (const v of hv) { let w = 0; for (let k = 0; k < 4; k++) if (jaw.has(p.si[v * 4 + k])) w += p.sw[v * 4 + k]; lower[v] = w; } // how much of each bit of skin hangs from the jaw: the lower lip does, the upper does not
          return faceMarksOf(base, p.idxHead, lmk, jaw.size ? lower : null);
        })());
        const ey = fd.eye ?? {}, e3 = fd.eye3 ?? {}, ehw = e3.w ?? 0.0215, ehh = e3.h ?? ehw * ((ey.h ?? 0.15) / (ey.w ?? 0.2)) * 1.05;
        const marks = faceMarkOptions(d, rgbOf);
        dec = faceDecals(M, { ...marks, shadeSide: V.dot(L, dirOnBone(B, 'head', [1, 0, 0])) > 0 ? -1 : 1, lips: fd.lips ?? 0.45, nostrils: fd.nostrils, eye: drawn ? { cx: M.eyeX + (e3.dx ?? 0.003), cy: e3.dy ?? 0.003, hw: ehw, hh: ehh } : { cx: M.eyeX, cy: 0.001, hw: 0.017, hh: 0.0085 } });
        decP = decalPaints(dec, paint.fill, paint.shade, { ...marks, lips: fd.lips ?? 0.45, lip: fd.lipColor ? rgbOf(fd.lipColor) : undefined, nose: fd.nose, hairShadow: fd.hairShadow });
      }
      const browP = { fill: rgbOf(bc), shade: rgbOf(bc), flat: true, line: false };
      const maps = drawn ? decP : [browP, ...decP];
      const map = drawn ? (dec ? dec.map : null) : (x, y) => { if (brow(x, y) === 0) return 0; const k = dec ? dec.map(x, y) : -1; return k < 0 ? -1 : k + 1; };
      const nrF = rounded(p.out, nr, 0.55, 0.13);
      const fp = { ...paint, thr: -0.32, group: 'skin', ...(drawn ? { edge: 2.4 } : {}), recv: false, recvHair: d.face?.hairShadow !== false, noAO: true, ...(map ? { map, maps } : {}) }, fid = cp.add(p.out, p.idxHead, fp, nrF, null, map ? xy : null);
      eyeIds.add(fid); faceIds.add(fid); for (const k of fp.mapIds ?? []) faceIds.add(k);
      if (p.idxMouth.length) cp.add(p.out, p.idxMouth, { fill: rgbOf('#4a1c22'), shade: rgbOf('#2a0e12'), line: false, group: 'mouth', flat: true }, nr);
    }
  }
  // the clothes the engine made (design.wear): smooth shells, each piece a thing of its own with its drawn line
  if (wear) {
    const skinP = B.parts.find((q) => q.material === 'skin'), sp = skinP ? paintOf(ch, B, skinP, lit) : null;
    for (const p of wear.parts) {
      const nr = normalsOf(p.out, p.idx, p.nrm), bias = 0.0012 * (p.layer ?? 1); let paint;
      if (p.skin && sp) paint = { ...sp, bands: undefined, group: 'skin', bias, two: true };
      else {
        const base = p.dark ? shadeHex(p.color, -p.dark) : p.color, f = rgbOf(base), s2 = rgbOf(p.shade && !p.dark ? p.shade : shadeHex(base, -0.24));
        paint = { fill: f, shade: p.flat ? f : s2, flat: !!p.flat, two: !!p.two, group: p.group, bias, thr: 0.05, noAO: !!p.flat };
        if (p.metal) paint = { ...paint, hi: rgbOf(shadeHex(base, 0.5)), hiAt: 0.8, metal: true, soft: 0 };
        if (p.leather) paint = { ...paint, hi: rgbOf(shadeHex(base, 0.42)), hiAt: 0.9, soft: 0 };
        if (p.bands?.length) paint.bands = p.bands.map(([a, b, c]) => [a, b, { fill: rgbOf(c), shade: rgbOf(shadeHex(c, -0.24)) }]);
      }
      const id = cp.add(p.out, p.idx, paint, nr, p.u ?? null), ids = (wearIds[p.group] ??= new Set()); ids.add(id); for (const b of paint.bandIds ?? []) ids.add(b);
      if (p.limb) { limbIds.add(id); for (const b of paint.bandIds ?? []) limbIds.add(b); }
    }
  }
  // hair: clumps on the scalp (design: hair3 = a cut's name or its fields; default by the old sheet's look)
  if (real && d.hair && d.hair3 !== null) {
    if (!ch.hair3 || ch.hair3.B !== B) { const h3 = makeHair(B, d.hair3 ?? (d.headgear?.type === 'helmet' ? 'buzz' : d.hair.tufts?.some((t) => t.body) ? 'long' : 'short'), (d.name ?? 'x').length * 7 + (d.name ?? 'x').charCodeAt(0)); ch.hair3 = h3 ? { ...h3, B } : { B, none: true }; }
    if (!ch.hair3.none) {
      const hp = hairPaint(d, rgbOf, { edge: ch.hair3.cut && (ch.hair3.cut.top ?? 0.06) < 0.07 ? 2.6 : 1.5 });
      const tieC = d.hair.tie ?? '#9c1f26', tieP = { fill: rgbOf(tieC), shade: rgbOf(shadeHex(tieC, -0.32)), thr: 0.05, group: 'tie', bias: 0.003, noAO: true, recv: false };
      for (const m of hairMeshes(ch.hair3, B, clamp(ch.t - (ch.hairT ?? ch.t), 0, 0.05) || 1 / 60, ch.t, o.wind ?? null)) { if (m.tie) { eyeIds.add(cp.add(m.pos, m.idx, tieP)); continue; } const id = cp.add(m.pos, m.idx, hp, rounded(m.pos, normalsOf(m.pos, m.idx, new Float32Array(m.pos.length)), 0.82), hp.bands ? m.u ?? null : null); eyeIds.add(id); for (const b of hp.bandIds ?? []) eyeIds.add(b); }
      ch.hairT = ch.t;
    }
  }
  // a crown of leaves on the hair (design headgear 'wreath'): a twig round the skull, leaves along it, up and down by turns
  if (real && d.headgear?.type === 'wreath' && lmk.crown && lmk.nape) for (const m of wreathMeshes(B, lmk, d.headgear)) eyeIds.add(cp.add(m.pos, m.idx, { fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.32)), thr: 0.05, two: true, group: 'hair', bias: 0.003, edge: 1.4 }));
  // a soldier's steel cap: a dome over the hair, down to the brow in front and the nape behind, a band round its rim, a crest for officers
  if (real && d.headgear?.type === 'crown' && lmk.crown && lmk.nape) for (const m of crownMeshes(B, lmk, d.headgear)) eyeIds.add(cp.add(m.pos, m.idx, m.metal ? { metal: true, fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.38)), hi: rgbOf(shadeHex(m.c, 0.5)), hiAt: 0.8, thr: 0.0, two: true, group: 'hair', bias: 0.005, edge: 1.4, noAO: true, recv: false } : { fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.3)), flat: true, two: true, group: 'hair', bias: 0.006, line: false }));
  if (real && d.headgear?.type === 'helmet' && lmk.crown && lmk.nape) for (const m of helmetMeshes(B, lmk, d.headgear)) eyeIds.add(cp.add(m.pos, m.idx, m.metal ? { metal: true, fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.42)), hi: rgbOf(shadeHex(m.c, 0.55)), hiAt: 0.8, thr: 0.0, group: 'hair', bias: 0.004, edge: 1.6 } : { fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.35)), thr: 0.05, two: true, group: 'hair', bias: 0.004, edge: 1.4 }));
  for (const m of carried(ch, pose, lit, pose.height / 1.8)) { const id = cp.add(m.pos, m.idx, m.paint); limbIds.add(id); }
  // shoulder plates (design.armor.pauldron: their colour; design.body.pauldrons: false | { size, lames }): built over the body's own shoulders as they are posed
  if (real && d.armor?.pauldron && d.body?.pauldrons !== false) {
    const jb = {}; for (const nm of ['upperarm01.L', 'upperarm01.R', 'lowerarm01.L', 'lowerarm01.R', 'neck01', 'spine02']) { const b = B.bi[nm]; if (b !== undefined) jb[nm] = onBone(B, b, [B.head[b * 3], B.head[b * 3 + 1], B.head[b * 3 + 2]]); }
    for (const m of pauldronMeshes(jb, B.height / 1.8, { color: d.armor.pauldron, size: d.body?.pauldrons?.size, lames: d.body?.pauldrons?.lames, rgbOf })) cp.add(m.pos, m.idx, m.paint);
  }
  // what is painted in one picture with the body, so each hides the other as it should: the horse under a rider
  // (drawn apart, the whole rider lay on top of the horse, the leg on its far side too)
  if (real) for (const m of o.along ?? []) cp.add(m.pos, m.idx, m.paint);
  { const rb = robeMesh(ch, lit); if (rb) cp.add(rb.pos, rb.idx, rb.paint, null, rb.u); }
  if (ch.cape && d.cape) { const m = sheet(ch.cape), c = d.cape.color, li = d.cape.lining ?? shadeHex(c, -0.25); cp.add(m.pos, m.idx, { fill: hex(lit(c)), shade: hex(lit(shadeHex(c, -0.3))), two: true, back: { fill: hex(lit(li)), shade: hex(lit(shadeHex(li, -0.3))) }, thr: 0.0 }); }
  const spr = cp.finish();
  const C = (c) => lit(c), S = (c, s) => lit(s ?? shadeHex(c, -0.26));
  // ---- ground shadow
  if (o.shadow !== false) {
    const gy = o.ground ?? 0, fl = pose.j['foot.L'], fr = pose.j['foot.R'], c0 = cam.project([(fl[0] + fr[0]) / 2, gy, (fl[2] + fr[2]) / 2]);
    if (c0[2] > 0.2) { const r = (0.22 * k + V.dist(fl, fr) * 0.5) * cam.ppm(c0[2]), squash = clamp(Math.abs(cam.F[1]) * 1.15 + 0.1, 0.12, 1); g.save(); g.globalAlpha = clamp(o.shadowAlpha ?? 0.3) * clamp(1.4 - (F.pel[1] - gy) / (pose.height * 0.9)); g.fillStyle = o.shadowColor ?? '#0a0814'; g.beginPath(); g.ellipse(c0[0], c0[1], r * 1.25, r * squash * 0.8, 0, 0, Math.PI * 2); g.fill(); g.restore(); }
  }
  // ---- long hair that hangs behind the body goes first, in front of it last
  const H = F.head, tufts = ch.tuftShapes(cam, F), headFront = V.dot(H.f, V.norm(V.sub(cam.pos, H.c)));
  const longBack = tufts.filter((t) => t.body), short = tufts.filter((t) => !t.body);
  let tailFront = ch.front.tail; const m0 = -headFront; if (tailFront === null) tailFront = m0 > 0; else if (m0 > 0.15) tailFront = true; else if (m0 < -0.15) tailFront = false; ch.front.tail = tailFront;
  const drawTails = () => { for (const tf of longBack) ch.drawTuft(g, tf, C(d.hair.color), S(d.hair.color, d.hair.shade), ink, lw); };
  if (longBack.length && !tailFront && !real) drawTails();
  if (spr) {
    // the finish (engine/finish.js): the figure is seated in the picture it is laid on — the background's light over its
    // edge, a soft gradient towards the scene's light — instead of being pasted on it
    const fin = o.finish ?? look()?.finish;
    if (fin) { const sx = V.dot(sun, cam.R), sy = -V.dot(sun, cam.U), sl = Math.hypot(sx, sy); finishFigure(g, spr, fin, { H: cam.H, light2: sl < 0.15 ? [-0.5, -0.86] : [sx / sl, sy / sl], shade: hex(lit('#6a72a6')), glow: typeof o.rim === 'string' ? hex(o.rim) : hex(lit('#fff2dc')) }); }
    g.drawImage(spr.canvas, spr.x, spr.y);
  }
  // the lines drawn on its clothes (a crease, a fly, a pocket's edge), inked on their own cloth
  if (wear && spr) for (const l of wear.lines) { const ids = wearIds[l.on]; if (!ids) continue; const pts = []; for (let v = 0; v < l.n; v++) pts.push([l.out[v * 3], l.out[v * 3 + 1], l.out[v * 3 + 2]]); inkOn(g, spr, cam, [{ pts, alpha: l.alpha, lw: Math.max(0.9, lwB * l.lw) }], ids, { ink, tol: 0.02 }); }
  // the knuckles of a closed hand, inked on its own skin (never over a sleeve or a blade in front of it)
  if (real && spr && handIds.size && B.fingers) for (const s of ['L', 'R']) {
    const wj = pose.j[`wrist.${s}`]; if (!wj) continue; const px = 0.0068 * (pose.height / 1.8) * cam.ppm(cam.project(wj)[2]); if (px < 1.6) continue; // too small in the picture to be drawn
    inkOn(g, spr, cam, knuckleMarks(B, s, cam.pos).map((m) => ({ pts: m.pts, alpha: m.alpha * clamp((px - 1.6) / 1.4), lw: lwB * 0.7 })), handIds, { ink });
  }
  // ---- the head: painted, then cut away wherever an arm, a hand or what it holds is nearer than the face
  const light = o.light ?? [-0.6, -0.8];
  const hc = cam.project(H.c), hr = H.R * cam.ppm(hc[2]);
  if (real && spr && hc[2] > 0.1 && hr > 14 && B.faceMarks && faceIds.size) {
    // a head turned from the lens shows the line of its nose: the ridge, from as high as the head is turned, over the tip and
    // in under it — inked on the face only (never over hair, a hand or a blade in front of it). Facing the lens the nose
    // has no line (its shadows say where it is); in profile the outline is that line already.
    const M = B.faceMarks, hx = V.norm(dirOnBone(B, 'head', [1, 0, 0])), hz = V.norm(dirOnBone(B, 'head', [0, 0, 1])), toLens = V.norm(V.sub(cam.pos, onBone(B, 'head', [0, eyeY + M.tip, eyeZ + 0.03])));
    const sideways = V.dot(toLens, hx), turn = Math.atan2(Math.abs(sideways), Math.max(1e-3, V.dot(toLens, hz))), far = sideways > 0 ? -1 : 1;
    const pts = noseLine(M, turn, far).map((q) => cam.project(onBone(B, 'head', q)));
    // the line is the nose against the far cheek: it goes as the cheek goes behind the nose (in profile the outline is that line already)
    let alpha = clamp((turn - 0.14) / 0.14);
    if (alpha > 0 && pts.length > 2) {
      const R = M.relief, hy = eyeY + M.tip + 0.008, cx = Math.min(R.NC - 1, Math.round((M.alar + 0.016) / R.cell)), cz = R.G[Math.round((R.top - (M.tip + 0.008)) / R.cell) * R.NC + cx];
      if (cz > -1e8) {
        const ck = cam.project(onBone(B, 'head', [far * cx * R.cell, hy, cz])), nt = pts.reduce((a, q) => (Math.abs(q[1] - ck[1]) < Math.abs(a[1] - ck[1]) ? q : a), pts[0]);
        const out = Math.sign(cam.project(onBone(B, 'head', [far * 0.02, hy, cz]))[0] - cam.project(onBone(B, 'head', [0, hy, cz]))[0]) || 1; // which way the far side lies in the picture
        alpha *= clamp(((ck[0] - nt[0]) * out) / (0.006 * cam.ppm(ck[2])));
      }
    }
    if (pts.length > 2 && alpha > 0.02 && pts.every((q) => q[2] > 0.1)) {
      const W = cam.W, Hh = cam.H; if (!scratch || scratch.width !== W || scratch.height !== Hh) scratch = createCanvas(W, Hh);
      const sg = scratch.getContext('2d'), pad = lwB * 3 + 2; let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9; for (const q of pts) { x0 = Math.min(x0, q[0]); y0 = Math.min(y0, q[1]); x1 = Math.max(x1, q[0]); y1 = Math.max(y1, q[1]); }
      x0 = Math.max(0, Math.floor(x0 - pad)); y0 = Math.max(0, Math.floor(y0 - pad)); x1 = Math.min(W, Math.ceil(x1 + pad)); y1 = Math.min(Hh, Math.ceil(y1 + pad));
      if (x1 > x0 && y1 > y0) {
        sg.setTransform(1, 0, 0, 1, 0, 0); sg.clearRect(x0, y0, x1 - x0, y1 - y0); sg.save(); sg.beginPath(); sg.rect(x0, y0, x1 - x0, y1 - y0); sg.clip();
        sg.strokeStyle = ink; sg.lineCap = 'round'; sg.lineJoin = 'round';
        // a drawn line: fine where it starts near the brow, full at the tip
        for (let i = 0; i < pts.length - 1; i++) { const u = i / (pts.length - 2 || 1); sg.lineWidth = lwB * (0.45 + 0.75 * Math.min(1, u * 1.6)); sg.beginPath(); sg.moveTo(pts[i][0], pts[i][1]); sg.lineTo(pts[i + 1][0], pts[i + 1][1]); sg.stroke(); }
        sg.restore();
        const im = sg.getImageData(x0, y0, x1 - x0, y1 - y0), px = im.data, w = x1 - x0, SS = spr.ss;
        for (let y = 0; y < y1 - y0; y++) for (let x = 0; x < w; x++) {
          const o4 = (y * w + x) * 4; if (!px[o4 + 3]) continue;
          const X = Math.floor((x0 + x + 0.5 - spr.x) * SS), Y = Math.floor((y0 + y + 0.5 - spr.y) * SS);
          if (X < 0 || Y < 0 || X >= spr.sw || Y >= spr.sh || !faceIds.has(spr.ib[Y * spr.sw + X])) px[o4 + 3] = 0; else px[o4 + 3] *= alpha;
        }
        sg.putImageData(im, x0, y0);
        g.drawImage(scratch, x0, y0, x1 - x0, y1 - y0, x0, y0, x1 - x0, y1 - y0);
      }
    }
  }
  if (drawn && spr && hc[2] > 0.1) {
    // drawn eyes and brows ride on the face: frames from the eye sockets, turned and foreshortened with the head, and
    // kept to where the face (or the hair over it) is what the lens sees — never over a hand or a blade
    const hs = V.norm(dirOnBone(B, 'head', [1, 0, 0])), hu = V.norm(dirOnBone(B, 'head', [0, 1, 0])), hf = V.norm(dirOnBone(B, 'head', [0, 0, 1]));
    const ey = d.face?.eye ?? {}, e3 = d.face?.eye3 ?? {}, hw = e3.w ?? 0.0215, hh = e3.h ?? hw * ((ey.h ?? 0.15) / (ey.w ?? 0.2)) * 1.05, sc = B.R[4] || 1;
    const frame3 = (sd, kind, lift) => {
      const lm = sd > 0 ? lmk.eyeL : lmk.eyeR, w = (kind === 'eye' ? hw : hw * 1.15) * sc, h = (kind === 'eye' ? hh : hh * 0.6 + 0.004) * sc;
      const o3 = onBone(B, 'head', [lm[0] + sd * (e3.dx ?? 0.003), lm[1] + (e3.dy ?? 0.003) + (kind === 'brow' ? (e3.brow ?? 0.027) + lift * 0.008 : 0), lm[2] + (lmk.eyeRadius ?? 0.0173) * 0.85 + (kind === 'brow' ? 0.006 : 0.002)]);
      const az = sd * (e3.az ?? 0.36), n = V.norm(V.add(V.mul(hf, Math.cos(az)), V.mul(hs, Math.sin(az)))), ta = V.norm(V.add(V.mul(hs, Math.cos(az)), V.mul(hf, -Math.sin(az))));
      const c0 = cam.project(o3), cx = cam.project(V.add(o3, V.mul(ta, w))), cy = cam.project(V.add(o3, V.mul(hu, h)));
      let ex = [cx[0] - c0[0], cx[1] - c0[1]], ey2 = [cy[0] - c0[0], cy[1] - c0[1]];
      const full = w * cam.ppm(c0[2]), lx = Math.hypot(ex[0], ex[1]); if (lx < full * 0.38 && lx > 1e-3) { const kx = (full * 0.38) / lx; ex = [ex[0] * kx, ex[1] * kx]; }
      return { vis: V.dot(n, V.norm(V.sub(cam.pos, o3))), c0, ex, ey: ey2 };
    };
    const W = cam.W, Hh = cam.H;
    if (!scratch || scratch.width !== W || scratch.height !== Hh) scratch = createCanvas(W, Hh);
    const sg = scratch.getContext('2d');
    const x0 = Math.max(0, Math.floor(hc[0] - hr * 2.2)), y0 = Math.max(0, Math.floor(hc[1] - hr * 2.2)), x1 = Math.min(W, Math.ceil(hc[0] + hr * 2.2)), y1 = Math.min(Hh, Math.ceil(hc[1] + hr * 2.2));
    if (x1 > x0 && y1 > y0 && hr > 3) {
      sg.setTransform(1, 0, 0, 1, 0, 0); sg.clearRect(x0, y0, x1 - x0, y1 - y0);
      sg.save(); sg.beginPath(); sg.rect(x0, y0, x1 - x0, y1 - y0); sg.clip();
      drawEyesOn(sg, { d, face: face ?? {}, lit, ink, lw: lwB * 1.25, L: light, skinSh: lit(d.skinShade ?? shadeHex(d.skin, -0.2)), px: hr * 1.05, frame3 });
      sg.restore();
      const im = sg.getImageData(x0, y0, x1 - x0, y1 - y0), px = im.data, w = x1 - x0, SS = spr.ss;
      for (let y = 0; y < y1 - y0; y++) for (let x = 0; x < w; x++) {
        const o4 = (y * w + x) * 4; if (!px[o4 + 3]) continue;
        const X = Math.floor((x0 + x + 0.5 - spr.x) * SS), Y = Math.floor((y0 + y + 0.5 - spr.y) * SS);
        if (X < 0 || Y < 0 || X >= spr.sw || Y >= spr.sh) { px[o4 + 3] = 0; continue; }
        let id = spr.ib[Y * spr.sw + X]; const pt = id ? cp.paints[id] : null;
        if (!pt || !(eyeIds.has(id) || pt.group === 'skin' && false || pt.group === 'hair')) px[o4 + 3] = 0;
      }
      sg.putImageData(im, x0, y0);
      g.drawImage(scratch, x0, y0, x1 - x0, y1 - y0, x0, y0, x1 - x0, y1 - y0);
    }
  } else if (real && spr) {
    // a point of light in each eye, where the eye is seen
    for (const e of [lmk.eyeL, lmk.eyeR]) {
      const c = onBone(B, 'head', [e[0], e[1], e[2] + (lmk.eyeRadius ?? 0.0173)]), q = cam.project(c); if (q[2] < 0.1) continue;
      const r = 0.0034 * cam.ppm(q[2]); if (r < 0.7) continue;
      const hx = q[0] - light[0] * r * 1.5, hy = q[1] + light[1] * r * 1.7 - r * 0.4;
      const X = Math.floor((hx - spr.x) * spr.ss), Y = Math.floor((hy - spr.y) * spr.ss); if (X < 0 || Y < 0 || X >= spr.sw || Y >= spr.sh) continue;
      const id = spr.ib[Y * spr.sw + X], pt = id ? cp.paints[id] : null; if (!pt || pt.group !== 'eye') continue;
      g.beginPath(); g.arc(hx, hy, r, 0, 7); g.fillStyle = '#ffffff'; g.fill();
    }
  }
  if (!real && hc[2] > 0.1) {
    const W = cam.W, Hh = cam.H;
    if (!scratch || scratch.width !== W || scratch.height !== Hh) scratch = createCanvas(W, Hh);
    const sg = scratch.getContext('2d');
    const x0 = Math.max(0, Math.floor(hc[0] - hr * 3.2)), y0 = Math.max(0, Math.floor(hc[1] - hr * 3.0)), x1 = Math.min(W, Math.ceil(hc[0] + hr * 3.2)), y1 = Math.min(Hh, Math.ceil(hc[1] + hr * 3.6));
    if (x1 > x0 && y1 > y0) {
      sg.setTransform(1, 0, 0, 1, 0, 0); sg.clearRect(x0, y0, x1 - x0, y1 - y0);
      sg.save(); sg.beginPath(); sg.rect(x0, y0, x1 - x0, y1 - y0); sg.clip();
      drawHead(sg, cam, H, d, ch.hd, face ?? {}, lit, { ink, lw, light, tufts: short, sway: ch.lockState ? ch.lockState.map((q) => q?.off) : null });
      sg.restore();
      if (spr && limbIds.size) {
        // is anything of the arms in front of the head at all? (most frames: no — skip the pixel work)
        const im = sg.getImageData(x0, y0, x1 - x0, y1 - y0), px = im.data, w = x1 - x0, SS = spr.ss; let cut = false;
        for (let y = 0; y < y1 - y0; y++) for (let x = 0; x < w; x++) {
          const o4 = (y * w + x) * 4; if (!px[o4 + 3]) continue;
          const X = Math.floor((x0 + x + 0.5 - spr.x) * SS), Y = Math.floor((y0 + y + 0.5 - spr.y) * SS); if (X < 0 || Y < 0 || X >= spr.sw || Y >= spr.sh) continue;
          const q = Y * spr.sw + X, id = spr.ib[q]; if (!id || !limbIds.has(id)) continue;
          // the face's own depth here: the front of a ball round the skull
          const dx = (x0 + x - hc[0]) / hr, dy = (y0 + y - hc[1]) / hr, rr = dx * dx + dy * dy, zf = hc[2] - (rr < 1 ? Math.sqrt(1 - rr) * H.R : 0);
          if (1 / spr.zb[q] < zf - 0.012) { px[o4 + 3] = 0; cut = true; }
        }
        if (cut) {
          sg.putImageData(im, x0, y0);
          // the line of whatever covers the face stays whole: redraw that strip of the body over the head's edge
          g.drawImage(scratch, x0, y0, x1 - x0, y1 - y0, x0, y0, x1 - x0, y1 - y0);
        } else g.drawImage(scratch, x0, y0, x1 - x0, y1 - y0, x0, y0, x1 - x0, y1 - y0);
      } else g.drawImage(scratch, x0, y0, x1 - x0, y1 - y0, x0, y0, x1 - x0, y1 - y0);
    }
  }
  if (longBack.length && tailFront && !real) drawTails();
  return true;
}
