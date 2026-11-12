// A character drawn from its real body: the posed mesh as cels with inked lines (body.js), what the hands hold and
// the cloth that hangs from the shoulders in the same depth picture, and the painted head on top — hidden wherever an
// arm, a hand or a blade passes in front of the face.
import { createCanvas } from '@napi-rs/canvas';
import { V, clamp, lerp, hex, qrot, shade as shadeHex } from './core.js';
import { poseBody, normalsOf, CelPainter, onBone, dirOnBone } from './body.js';
import { drawHead, drawEyesOn } from './head.js';
import { makeHair, hairMeshes } from './hair.js';
import { look } from './look.js';

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

/** What the body carries, as meshes with their paints. */
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
    const ax = qrot(sc.q, [0, 1, 0]), wd = qrot(sc.q, [0, 0, 1]), th = qrot(sc.q, [1, 0, 0]), L = ((d.sword.len ?? 0.82) + 0.03) * k, curve = d.sword.curve ?? 0.035, rings = [];
    for (let i = 0; i <= 6; i++) { const u = i / 6; rings.push(ring(along(along(sc.pos, ax, L * u), wd, -curve * L * u * u), ax, wd, th, 0.03 * k * (1 - 0.25 * u), 0.012 * k, 8)); }
    out.push({ ...tube(rings), paint: flatP(d.sword.scabbard ?? '#201a22', -0.4) });
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
  if (pr.sword?.visible && pr.scabbard?.visible && V.dist(pr.sword.pos, pr.scabbard.pos) < 0.09 * k) { for (const s of ['L', 'R']) if (h[s] === 'grip' && !(pr.spear?.visible || pr.staff?.visible)) h[s] = 'relax'; }
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
  if (m === 'metal') return withBands(mk(pick, shadeHex(pick, -0.36), { hi: rgbOf(shadeHex(pick, 0.5)), hiAt: 0.8, thr: 0.05, metal: true }));
  return withBands(mk(pick, part.name === 'tunic' || part.name === 'coat' ? d.top?.shade : part.name === 'trousers' || part.name === 'skirt' ? d.bottom?.shade : undefined, part.name === 'coat' ? { group: bd.coatLine ? undefined : 'top' } : part.name === 'tunic' ? { group: 'top' } : {}));
}

/**
 * Draw a character whose pose carries its real body (pose.body = { B, t, xf, hands }). Same arguments as Character.draw;
 * o.sun: the light's direction in the world. Returns false when the body cannot be drawn (the caller falls back).
 */
export function drawBodyMesh(ch, g, cam, pose, face, lit, o) {
  const d = ch.d, F = ch.frame(pose), k = F.k, pb = pose.body, B = pb.B;
  const zc = cam.toCam(F.pel)[2]; if (zc < 0.15) return true;
  const ppm = cam.ppm(zc), hpx = pose.height * ppm, lw = clamp(0.0052 * ppm * d.inkScale, 0.9, 4.2), ink = lit(d.ink), rs = cam.H / 1080, lwB = clamp(0.0046 * ppm * d.inkScale, 0.8 * rs, 2.6 * rs);
  const real = d.realHead !== false && B.bi.head !== undefined && !!B.landmarks.eyeL; // the body's own head (face, eyes, teeth) instead of the painted one
  // the eyes and brows of a real head are drawn (large, clear, the way drawn faces have them) unless the sheet asks for the sculpted ones
  const drawn = real && (d.face?.eyes3 ?? 'drawn') === 'drawn';
  poseBody(B, pb.t, pb.xf, pb.hands ?? autoHands(pose), real ? (drawn ? { ...(face ?? {}), eyeOpen: 1, blink: 0, eyeSmile: 0, browTilt: 0, browL: 0, browR: 0 } : (face ?? {})) : null, Array.isArray(pb.after) ? pb.after : null);
  if (typeof pb.after === 'function') pb.after(B); // a film's own change to the posed body (moves and turns of kit/combat.js are applied by poseBody)
  const sun = V.norm(o.sun ?? [-0.45, 0.7, 0.5]);
  // the light a cel is drawn under leans towards the lens, so a face-on figure is never all shadow
  const L = V.norm(V.add(V.mul(sun, 0.78), V.add(V.mul(cam.F, -0.34), V.mul(cam.U, 0.22))));
  const ss = hpx > 900 ? 2 : hpx > 240 ? 2 : 3;
  const pm = look()?.paint?.mode === 'paint';
  const cp = new CelPainter(cam, { ss, light: L, ink: hex(ink), lw: lwB * (pm ? 2.1 : 1.75), lwIn: lwB * (pm ? 1.05 : 0.95) });
  const limbIds = new Set(), eyeIds = new Set();
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
      } else if (p.name === 'teeth') cp.add(p.out, p.idx, { fill: [244, 240, 232], shade: [200, 190, 190], line: false, group: 'mouth', thr: -0.3 }, nr);
      else if (p.name === 'tongue') cp.add(p.out, p.idx, { fill: rgbOf('#b0565a'), shade: rgbOf('#7a3238'), line: false, group: 'mouth' }, nr);
      else cp.add(p.out, p.idx, { fill: hex(ink), shade: hex(ink), flat: true, two: true, bias: 0.002, line: false, group: 'eye' }, nr);
      continue;
    }
    if (real && (p.name === 'haircap' || p.name === 'beard') && d.hair) { const hc = p.name === 'beard' ? (d.beard?.color ?? d.hair.color) : d.hair.color; cp.add(p.out, p.idx, { fill: rgbOf(hc), shade: rgbOf(d.hair.shade ?? shadeHex(hc, -0.3)), two: true, bias: 0.003, group: 'hair', noAO: true, recv: false }, rounded(p.out, normalsOf(p.out, p.idx, p.nrm), 0.6)); continue; }
    if (SKIP.has(p.name)) continue;
    // wrist wraps of the body model lie under a long sleeve: without a colour of their own on the sheet they would only poke through it
    if (p.name === 'wraps' && d.top?.sleeve === 'long' && !(d.body?.wraps ?? d.armor?.bracer ?? d.top?.wrap)) continue;
    const paint = paintOf(ch, B, p, lit), nr = normalsOf(p.out, p.idx, p.nrm);
    if (p.idxCore.length) cp.add(p.out, p.idxCore, { ...paint, group: paint.group ?? p.name }, nr, p.u);
    if (p.idxLimb.length) { const pl = { ...paint, group: paint.group ?? p.name }; const id = cp.add(p.out, p.idxLimb, pl, nr, p.u); limbIds.add(id); for (const b of pl.bandIds ?? []) limbIds.add(b); }
    if (real && p.material === 'skin' && p.idxHead.length) {
      // the face: lit almost everywhere (a drawn face keeps its shadows to the jaw and the far cheek); brows ride on the skin
      const xy = p.faceXY ?? (p.faceXY = (() => { const a = new Float32Array(p.n * 2), base = p.base ?? p.pos; for (let v = 0; v < p.n; v++) { const front = base[v * 3 + 2] > eyeZ - 0.045; a[v * 2] = front ? base[v * 3] : 9; a[v * 2 + 1] = base[v * 3 + 1] - eyeY; } return a; })());
      const br = fd.brow3 ?? {}, by = br.y ?? 0.0235, th = br.thick ?? 0.0036, x0 = br.x0 ?? 0.011, x1 = br.x1 ?? 0.058, arch = br.arch ?? 0.0055, tilt = br.tilt ?? 0.0;
      const bc = d.hair?.color ?? '#1c1a22';
      const map = (x, y) => { const ax = x < 0 ? -x : x; if (ax < x0 || ax > x1) return -1; const u = (ax - x0) / (x1 - x0), yc = by + arch * Math.sin(Math.PI * Math.min(1, u * 1.15)) - tilt * (1 - u) - 0.004 * u * u, h = th * (1 - 0.62 * u) * (u < 0.08 ? 0.5 + u * 6.25 : 1); return Math.abs(y - yc) < h ? 0 : -1; };
      const nrF = rounded(p.out, nr, 0.55, 0.13);
      eyeIds.add(cp.add(p.out, p.idxHead, drawn ? { ...paint, thr: -0.32, group: 'skin', edge: 2.4, recv: false, noAO: true } : { ...paint, thr: -0.32, group: 'skin', recv: false, noAO: true, map, maps: [{ fill: rgbOf(bc), shade: rgbOf(bc), flat: true, line: false }] }, nrF, null, drawn ? null : xy));
      if (p.idxMouth.length) cp.add(p.out, p.idxMouth, { fill: rgbOf('#4a1c22'), shade: rgbOf('#2a0e12'), line: false, group: 'mouth', flat: true }, nr);
    }
  }
  // hair: clumps on the scalp (design: hair3 = a cut's name or its fields; default by the old sheet's look)
  if (real && d.hair && d.hair3 !== null) {
    if (!ch.hair3 || ch.hair3.B !== B) { const h3 = makeHair(B, d.hair3 ?? (d.headgear?.type === 'helmet' ? 'buzz' : d.hair.tufts?.some((t) => t.body) ? 'long' : 'short'), (d.name ?? 'x').length * 7 + (d.name ?? 'x').charCodeAt(0)); ch.hair3 = h3 ? { ...h3, B } : { B, none: true }; }
    if (!ch.hair3.none) {
      const hc = d.hair.color, hp = { fill: rgbOf(hc), shade: rgbOf(d.hair.shade ?? shadeHex(hc, -0.34)), hi: rgbOf(d.hair.shine ?? shadeHex(hc, 0.32)), hiAt: 0.9, thr: 0.1, group: 'hair', hairy: true, bias: 0.002, edge: ch.hair3.cut && (ch.hair3.cut.top ?? 0.06) < 0.07 ? 2.6 : 1.5 };
      for (const m of hairMeshes(ch.hair3, B, clamp(ch.t - (ch.hairT ?? ch.t), 0, 0.05) || 1 / 60, ch.t, o.wind ?? null)) eyeIds.add(cp.add(m.pos, m.idx, { ...hp, noAO: true, recv: false }, rounded(m.pos, normalsOf(m.pos, m.idx, new Float32Array(m.pos.length)), 0.82)));
      ch.hairT = ch.t;
    }
  }
  // a crown of leaves on the hair (design headgear 'wreath'): a twig round the skull, leaves along it, up and down by turns
  if (real && d.headgear?.type === 'wreath' && lmk.crown && lmk.nape) for (const m of wreathMeshes(B, lmk, d.headgear)) eyeIds.add(cp.add(m.pos, m.idx, { fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.32)), thr: 0.05, two: true, group: 'hair', bias: 0.003, edge: 1.4 }));
  // a soldier's steel cap: a dome over the hair, down to the brow in front and the nape behind, a band round its rim, a crest for officers
  if (real && d.headgear?.type === 'crown' && lmk.crown && lmk.nape) for (const m of crownMeshes(B, lmk, d.headgear)) eyeIds.add(cp.add(m.pos, m.idx, m.metal ? { metal: true, fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.38)), hi: rgbOf(shadeHex(m.c, 0.5)), hiAt: 0.8, thr: 0.0, two: true, group: 'hair', bias: 0.005, edge: 1.4, noAO: true, recv: false } : { fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.3)), flat: true, two: true, group: 'hair', bias: 0.006, line: false }));
  if (real && d.headgear?.type === 'helmet' && lmk.crown && lmk.nape) for (const m of helmetMeshes(B, lmk, d.headgear)) eyeIds.add(cp.add(m.pos, m.idx, m.metal ? { metal: true, fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.42)), hi: rgbOf(shadeHex(m.c, 0.55)), hiAt: 0.8, thr: 0.0, group: 'hair', bias: 0.004, edge: 1.6 } : { fill: rgbOf(m.c), shade: rgbOf(shadeHex(m.c, -0.35)), thr: 0.05, two: true, group: 'hair', bias: 0.004, edge: 1.4 }));
  for (const m of carried(ch, pose, lit, pose.height / 1.8)) { const id = cp.add(m.pos, m.idx, m.paint); limbIds.add(id); }
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
  if (spr) g.drawImage(spr.canvas, spr.x, spr.y);
  // ---- the head: painted, then cut away wherever an arm, a hand or what it holds is nearer than the face
  const light = o.light ?? [-0.6, -0.8];
  const hc = cam.project(H.c), hr = H.R * cam.ppm(hc[2]);
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
