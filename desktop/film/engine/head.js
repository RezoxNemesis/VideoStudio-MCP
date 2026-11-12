// The head: a sculpted form (the ball of the skull, the mask of the face, nose and lips) seen from any side,
// an anime face drawn on its surface, and hair: a cap on the scalp, locks that follow the head, strands that hang.
// Head space: +x = the character's left, +y = up, +z = forward; lengths in head radii (half the head's height).
import { V, clamp, lerp, shade as shadeHex } from './core.js';
import { hull, smoothClosed, smoothOpen, poly } from './draw.js';

const LON = 44, LAT = 28;

const BASE = { cw: 0.8, ch: 0.8, cd: 0.9, cy: 0.2, cz: -0.1, faceZ: 0.78, eyeTurn: 0.45, cheekW: 0.7, jawW: 0.6, jawY: -0.56, chinW: 0.17, chinY: -1.0, chinZ: 0.6, round: 14, nose: 0.2, noseY: -0.3, lips: 0.02, mouthY: -0.6 };
export const SHAPES = {
  male: {},
  female: { faceZ: 0.77, eyeTurn: 0.42, cheekW: 0.68, jawW: 0.52, jawY: -0.52, chinW: 0.1, chinY: -0.97, round: 11, nose: 0.15, noseY: -0.32 },
  child: { cw: 0.84, ch: 0.82, cd: 0.92, cy: 0.18, faceZ: 0.76, eyeTurn: 0.4, cheekW: 0.74, jawW: 0.6, jawY: -0.5, chinW: 0.13, chinY: -0.9, chinZ: 0.56, round: 8, nose: 0.1, noseY: -0.34, mouthY: -0.58, lips: 0.01 },
  heavy: { cheekW: 0.76, jawW: 0.72, jawY: -0.64, chinW: 0.27, chinZ: 0.62, round: 12, nose: 0.22 },
  elder: { cheekW: 0.66, jawW: 0.54, jawY: -0.58, chinW: 0.15, chinY: -1.02, round: 12, nose: 0.24 },
};

/** Turn shape numbers into the planes of the face mask (each: unit normal and distance from the head's centre). */
function compile(s0) {
  const sh = { ...BASE, ...s0 }, planes = [];
  const add = (n, p) => { const l = Math.hypot(n[0], n[1], n[2]) || 1; let nn = [n[0] / l, n[1] / l, n[2] / l], h = V.dot(nn, p); if (h < 0) { nn = V.mul(nn, -1); h = -h; } planes.push([nn[0], nn[1], nn[2], h]); };
  const tri = (a, b, c) => add(V.cross(V.sub(b, a), V.sub(c, a)), a);
  const B = [0, 0.22, sh.faceZ], N = [0, sh.chinY + 0.12, sh.chinZ + 0.1];
  sh.slope = (B[2] - N[2]) / (B[1] - N[1]);
  sh.faceZat = (y) => sh.faceZ + (y - 0.22) * sh.slope;
  tri(B, N, [1, B[1], B[2]]); // the middle of the face
  for (const s of [1, -1]) {
    add([s * Math.sin(sh.eyeTurn), -0.04, Math.cos(sh.eyeTurn)], [s * 0.15, 0, sh.faceZ]); // the plane an eye lies on
    add([s, -0.1, 0.38], [s * sh.cheekW, -0.25, 0.4]); // cheek
    tri([s * sh.cheekW, -0.3, 0.3], [s * sh.jawW, sh.jawY, -0.05], [s * sh.chinW, sh.chinY + 0.04, sh.chinZ - 0.02]); // jaw
    add([s * 0.3, -1, -0.5], [0, sh.chinY, sh.chinZ]); // under the jaw, rising towards the throat
    add([s, 0, -0.08], [s * (sh.cheekW + 0.04), 0, 0]); // side, by the ear
  }
  add([0, -0.5, 1], N); // the chin's front
  add([0, -0.2, -1], [0, -0.3, -0.32]); // back of the jaw
  add([0, 1, 0.1], [0, 0.42, 0]); // top of the mask (inside the skull)
  sh.planes = planes;
  return sh;
}

function rCran(d, sh) {
  const qy = -sh.cy / sh.ch, qz = -sh.cz / sh.cd, ex = d[0] / sh.cw, ey = d[1] / sh.ch, ez = d[2] / sh.cd;
  const ee = ex * ex + ey * ey + ez * ez, qe = qy * ey + qz * ez, qq = qy * qy + qz * qz;
  return (-qe + Math.sqrt(Math.max(0, qe * qe - ee * (qq - 1)))) / ee;
}
function rMask(d, sh) {
  let s = 0; const p = sh.round;
  for (const pl of sh.planes) { const nd = pl[0] * d[0] + pl[1] * d[1] + pl[2] * d[2]; if (nd > 1e-4) s += Math.pow(nd / pl[3], p); }
  return s > 0 ? Math.pow(s, -1 / p) : 9;
}
/** The head's surface point for a direction (azimuth from the nose towards the left ear, elevation). */
export function skull(az, el, sh) {
  const ce = Math.cos(el), d = [Math.sin(az) * ce, Math.sin(el), Math.cos(az) * ce];
  const rc = rCran(d, sh), rm = rMask(d, sh), r = Math.pow(Math.pow(rc, 24) + Math.pow(rm, 24), 1 / 24);
  return [d[0] * r, d[1] * r, d[2] * r];
}
export const shapeOf = (d) => compile({ ...(SHAPES[d.face?.shape ?? 'male'] ?? {}), ...(d.face?.tweak ?? {}) });

/** Where the hair ends on the skull: the height (in radii) of the hairline at an azimuth. */
function hairlineY(azAbs, style) {
  const a = azAbs * 180 / Math.PI, f = style.front ?? 0.55, tm = style.temple ?? 0.32, sb = style.sideburn ?? -0.08, np = style.nape ?? -0.5;
  if (a <= 50) return f - (f - tm) * Math.pow(a / 50, 2);
  if (a <= 86) { const u = (a - 50) / 36; return tm + (sb - tm) * u * u * (3 - 2 * u); }
  if (a <= 112) { const u = (a - 86) / 26; return sb + (np - sb) * u * u * (3 - 2 * u); }
  return np;
}
/** The elevation at which the surface reaches height y, at an azimuth. */
function elForY(a, y, sh) { let lo = -1.5, hi = 1.5; for (let i = 0; i < 16; i++) { const m = (lo + hi) / 2; if (skull(a, m, sh)[1] < y) lo = m; else hi = m; } return (lo + hi) / 2; }

/** A lock of hair in head space: it follows the scalp from root to tip and lifts off towards its end. */
function buildLock(lk, sh, capLift) {
  let pts;
  if (lk.p) {
    // drawn freely: a few points, smoothed into more
    const src = lk.p, n = lk.n ?? 7; pts = [];
    for (let i = 0; i < n; i++) { const x = (i / (n - 1)) * (src.length - 1), i0 = Math.min(src.length - 2, Math.floor(x)), u = x - i0; const p0 = src[Math.max(0, i0 - 1)], p1 = src[i0], p2 = src[i0 + 1], p3 = src[Math.min(src.length - 1, i0 + 2)]; pts.push([0, 1, 2].map((c) => 0.5 * ((2 * p1[c]) + (-p0[c] + p2[c]) * u + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * u * u + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * u * u * u))); }
  } else {
    const n = lk.n ?? 6, [a0, e0] = lk.r, [a1, e1] = lk.t; pts = [];
    for (let i = 0; i < n; i++) {
      const u = i / (n - 1), a = lerp(a0, a1, u) + (lk.bend ?? 0) * Math.sin(Math.PI * u), e = lerp(e0, e1, u) + (lk.arc ?? 0) * Math.sin(Math.PI * u);
      const s = skull(a, e, sh), k = 1 + capLift * (lk.base ?? 1) + (lk.lift ?? 0.07) * u * u + (lk.puff ?? 0) * Math.sin(Math.PI * u);
      pts.push([s[0] * k, (s[1] - 0.1) * k + 0.1, s[2] * k]);
    }
  }
  const m = pts[Math.floor(pts.length / 2)], r = pts[0];
  let len = 0; for (let i = 1; i < pts.length; i++) len += V.dist(pts[i], pts[i - 1]);
  return { ...lk, pts, nm: V.norm([m[0] * 0.6 + r[0] * 0.4, (m[1] * 0.6 + r[1] * 0.4) - 0.1, m[2] * 0.6 + r[2] * 0.4]), len };
}

/** Build the head of a design once: surface points, its parts, the hair cap and locks. */
export function makeHead(d) {
  const sh = shapeOf(d);
  const cap = d.hair && d.hair.cap !== false ? (d.hair.capStyle ?? {}) : null;
  const verts = [], az = [], el = [], ci = [], ji = [];
  for (let i = 0; i <= LAT; i++) {
    const e = -Math.PI / 2 + (Math.PI * i) / LAT;
    for (let j = 0; j < LON; j++) {
      const a = -Math.PI + (2 * Math.PI * j) / LON, ce = Math.cos(e), dir = [Math.sin(a) * ce, Math.sin(e), Math.cos(a) * ce];
      const rc = rCran(dir, sh), rm = rMask(dir, sh), k = verts.length;
      verts.push(skull(a, e, sh)); az.push(a); el.push(e);
      if (rc >= rm - 0.03) ci.push(k);
      if (rm >= rc - 0.03) ji.push(k);
    }
  }
  const tris = [];
  for (let i = 0; i < LAT; i++) for (let j = 0; j < LON; j++) {
    const a = i * LON + j, b = i * LON + ((j + 1) % LON), c = (i + 1) * LON + j, e = (i + 1) * LON + ((j + 1) % LON);
    tris.push([a, b, e], [a, e, c]);
  }
  const zf = sh.faceZat, ny = sh.noseY, t = sh.nose, my = sh.mouthY, lp = sh.lips;
  // nose: [bridge, upper tip, tip, wing L, wing R, base]; lips: a small swell around the mouth
  const nose = [[0, 0.05, zf(0.05) - 0.03], [0, ny + 0.035, zf(ny) + t * 0.9], [0, ny - 0.02, zf(ny) + t], [0.09, ny - 0.1, zf(ny) - 0.05], [-0.09, ny - 0.1, zf(ny) - 0.05], [0, ny - 0.125, zf(ny) + t * 0.18]];
  const lips = [[0, my + 0.11, zf(my + 0.11) - 0.01], [0, my + 0.04, zf(my) + lp], [0, my - 0.05, zf(my) + lp * 0.8], [0, my - 0.14, zf(my - 0.14) - 0.01], [0.14, my, zf(my) - 0.04], [-0.14, my, zf(my) - 0.04]];
  const lift = cap ? (cap.lift ?? 0.08) : 0;
  // a bald crown (cap.bald = height above which the front and top are bare) leaves a ring of hair at the sides and back
  const hairV = verts.map((v, k) => !!cap && v[1] > hairlineY(Math.abs(az[k]), cap) - 0.02 && !(cap.bald !== undefined && v[1] > cap.bald + (cap.baldBack ?? 0.5) * clamp((Math.abs(az[k]) - 1.9) / 1.2)));
  // the hair cap sits off the scalp so it reads as hair with body, not paint
  const hv = verts.map((v, k) => {
    if (!hairV[k]) return v;
    const n = V.norm([v[0], v[1] - 0.1, v[2]]), fr = clamp((v[1] - hairlineY(Math.abs(az[k]), cap)) / 0.25);
    const m = (lift + (cap.top ?? 0) * Math.max(0, n[1]) ** 2 + (cap.back ?? 0) * Math.max(0, -n[2]) ** 2 + (cap.side ?? 0) * Math.abs(n[0]) ** 2) * fr;
    return [v[0] + n[0] * m, v[1] + n[1] * m, v[2] + n[2] * m];
  });
  const hairT = cap ? tris.filter((q) => hairV[q[0]] && hairV[q[1]] && hairV[q[2]]) : [];
  const hairI = []; hairV.forEach((h, k) => { if (h) hairI.push(k); });
  const locks = (d.hair?.locks ?? []).map((lk) => buildLock(lk, sh, lift));
  // every hair triangle's outward normal, once; a coarser set of everything for heads that are small in the picture
  const withN = (list) => list.map((q) => { const a = hv[q[0]], b = hv[q[1]], cc = hv[q[2]]; let n = V.cross(V.sub(b, a), V.sub(cc, a)); const m = [(a[0] + b[0] + cc[0]) / 3, (a[1] + b[1] + cc[1]) / 3 - 0.1, (a[2] + b[2] + cc[2]) / 3]; if (V.dot(n, m) < 0) n = V.mul(n, -1); return [q[0], q[1], q[2], n]; });
  const even = (k) => (Math.floor(k / LON) % 2 === 0 || Math.floor(k / LON) === LAT) && (k % LON) % 2 === 0;
  const tris2 = [];
  for (let i = 0; i < LAT; i += 2) for (let j = 0; j < LON; j += 2) { const i2 = Math.min(LAT, i + 2), a = i * LON + j, b = i * LON + ((j + 2) % LON), c = i2 * LON + j, e = i2 * LON + ((j + 2) % LON); tris2.push([a, b, e], [a, e, c]); }
  const hairT2 = cap ? tris2.filter((q) => hairV[q[0]] && hairV[q[1]] && hairV[q[2]]) : [];
  const line = [];
  if (cap) for (let i = 0; i <= 40; i++) { const a = -1.6 + (3.2 * i) / 40, e = elForY(a, hairlineY(Math.abs(a), cap), sh), sp = skull(a, e, sh); line.push([V.mul(sp, 1.01), V.norm([sp[0], sp[1] - 0.1, sp[2]])]); }
  return { sh, verts, hv, tris, ci, ji, ci2: ci.filter(even), ji2: ji.filter(even), nose, lips, hairT: withN(hairT), hairT2: withN(hairT2), hairI, hairI2: hairI.filter(even), cap, locks, line, sf: new Map() };
}

/** A point of the face surface with its tangents (for drawing features that turn with the head). */
function surf(sh, a, e, memo) {
  const key = memo ? a * 1000 + e : 0;
  if (memo) { const r = memo.get(key); if (r) return r; }
  const p = skull(a, e, sh), pa = skull(a + 0.02, e, sh), pe = skull(a, e + 0.02, sh);
  const ta = V.norm(V.sub(pa, p)), te = V.norm(V.sub(pe, p)), r = { p, ta, te, n: V.norm(V.cross(ta, te)) };
  if (memo && memo.size < 4000) memo.set(key, r);
  return r;
}

/** A lock as a tapered blade along projected points → its two edges. */
function blade(p, w0, o) {
  const n = p.length, left = [], right = [];
  for (let i = 0; i < n; i++) {
    const a = p[Math.max(0, i - 1)], b = p[Math.min(n - 1, i + 1)];
    let dx = b[0] - a[0], dy = b[1] - a[1]; const l = Math.hypot(dx, dy) || 1; dx /= l; dy /= l;
    const t = i / (n - 1), w = w0 * (o.flat ? 1 - 0.35 * t : Math.pow(1 - t, o.pow ?? 0.8)) * (1 + (o.belly ?? o.bulge ?? 0) * Math.sin(Math.PI * Math.min(1, t * 1.35)));
    left.push([p[i][0] - dy * w, p[i][1] + dx * w]); right.push([p[i][0] + dy * w, p[i][1] - dx * w]);
  }
  // keep one winding, so that several blades in one path always add up
  let s = 0; const ring = left.concat([...right].reverse());
  for (let i = 0; i < ring.length; i++) { const a = ring[i], b = ring[(i + 1) % ring.length]; s += a[0] * b[1] - b[0] * a[1]; }
  return s >= 0 ? { left, right, w0 } : { left: right, right: left, w0 };
}
const bladeInk = (q, s) => { smoothOpen(q, s.left, 1); smoothOpen(q, [...s.right].reverse(), 1, false); };
const bladePath = (q, s) => { bladeInk(q, s); q.closePath(); };
function paintBlade(g, s, col, shd, ink, lw, plain, inkFrom = 0.34) {
  // the line starts part-way down, so a lock grows out of the hair without a seam at its root
  const k0 = Math.min(s.left.length - 2, Math.floor(s.left.length * inkFrom));
  g.beginPath(); smoothOpen(g, s.left.slice(k0), 1); smoothOpen(g, s.right.slice(k0).reverse(), 1, false);
  g.lineJoin = 'round'; g.lineCap = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke();
  g.beginPath(); bladePath(g, s); g.fillStyle = col; g.fill();
  if (s.w0 > 6 && !plain && shd) {
    g.save(); g.beginPath(); bladePath(g, s); g.clip();
    g.beginPath(); smoothOpen(g, s.left.map((q, i) => [q[0] * 0.25 + s.right[i][0] * 0.75, q[1] * 0.25 + s.right[i][1] * 0.75]), 1);
    g.lineWidth = s.w0 * 0.45; g.strokeStyle = shd; g.globalAlpha = 0.8; g.stroke();
    g.restore();
  }
}

/**
 * Draw a head.
 *   fr: { c: centre (world), s: left, u: up, f: forward (unit), R: radius in metres }
 *   d: the character design, hd: makeHead(d), face: expression values, lit: colour grade
 *   o: { ink, lw, light, tufts: hanging strands as screen shapes, sway: per-lock world offsets of the tips }
 */
export function drawHead(g, cam, fr, d, hd, face, lit, o) {
  const { c, s, u, f, R } = fr, sh = hd.sh;
  const W = (p) => [c[0] + (s[0] * p[0] + u[0] * p[1] + f[0] * p[2]) * R, c[1] + (s[1] * p[0] + u[1] * p[1] + f[1] * p[2]) * R, c[2] + (s[2] * p[0] + u[2] * p[1] + f[2] * p[2]) * R];
  const P = (p) => cam.project(W(p));
  const cs = cam.project(c), px = cam.ppm(cs[2]) * R; // head radius in pixels
  const view = V.norm(V.sub(cam.pos, c)); // from head to lens
  const vl = [V.dot(view, s), V.dot(view, u), V.dot(view, f)]; // the lens direction in head space
  const facing = (n) => n[0] * vl[0] + n[1] * vl[1] + n[2] * vl[2];
  const skin = lit(d.skin), skinSh = lit(d.skinShade ?? shadeHex(d.skin, -0.18)), ink = o.ink, lw = o.lw;
  const L = o.light ?? [-0.6, -0.8];
  const hair = d.hair, detail = px > 9, coarse = px < 62;
  const hairCol = hair ? lit(hair.color) : null, hairSh = hair ? lit(hair.shade ?? shadeHex(hair.color, -0.3)) : null, hairHi = hair ? lit(hair.hi ?? shadeHex(hair.color, 0.22)) : null;
  const hink = hair ? lit(hair.ink ?? shadeHex(hair.color, -0.55)) : ink;

  // ---- the head's outline: the ball of the skull, the mask of the face, the nose and the lips, as one shape
  const outC = hull((coarse ? hd.ci2 : hd.ci).map((i) => P(hd.verts[i]))), outJ = hull((coarse ? hd.ji2 : hd.ji).map((i) => P(hd.verts[i])));
  const outN = detail ? hull(hd.nose.map(P)) : null, outM = detail ? hull(hd.lips.map(P)) : null;
  const headPath = (q) => { smoothClosed(q, outC, 0.9); smoothClosed(q, outJ, 0.8); if (outN) { smoothClosed(q, outN, 0.5); smoothClosed(q, outM, 0.8); } };

  // ---- hair: where every lock is now, and which side of the head it is on
  const tufts = o.tufts ?? [];
  let hp = null;
  if (hair && hd.hairI.length) { hp = []; for (const i of (coarse ? hd.hairI2 : hd.hairI)) hp[i] = P(hd.hv[i]); }
  const locks = !hair ? [] : hd.locks.map((lk, i) => {
    const sw = o.sway?.[i], n = lk.pts.length;
    const p2 = lk.pts.map((p, k2) => { const w = W(p); if (!sw) return cam.project(w); const uu = Math.pow(k2 / (n - 1), 1.6); return cam.project([w[0] + sw[0] * uu, w[1] + sw[1] * uu, w[2] + sw[2] * uu]); });
    return { lk, near: facing(lk.nm) > (lk.fringe ? -0.3 : -0.08), z: p2[n >> 1][2], sh: blade(p2, lk.w * px, lk) };
  });
  const tuftBlade = (tf) => blade(tf.pts, tf.w0, tf);

  // a wide hat seen from underneath lies behind the head and face
  const hatUnder = d.headgear?.type === 'straw' && vl[1] < -0.02;
  if (hatUnder) drawHeadgear(g, cam, { P, W, facing, px, d, lit, ink, lw, vl, sh });
  // ---- behind the head: the mass of the hair and every lock on the far side, as one dark shape
  if (hair) {
    for (const tf of tufts) if (!tf.front) paintBlade(g, tuftBlade(tf), hairCol, hairSh, hink, lw, tf.plain, 0);
    const mass = hp ? hull((coarse ? hd.hairI2 : hd.hairI).map((i) => hp[i])) : null;
    const far = locks.filter((q) => !q.near);
    if (mass || far.length) {
      const path = (q) => { if (mass) smoothClosed(q, mass, 0.9); for (const lk of far) bladePath(q, lk.sh); };
      g.beginPath(); path(g); g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = hink; g.stroke();
      g.beginPath(); path(g); g.fillStyle = hairSh; g.fill();
    }
  }

  // ---- ears (behind the skull when they point away)
  const earAt = (side) => {
    if (d.face?.ears === false) return null;
    const e = surf(sh, side * 1.62, -0.1, hd.sf);
    return { e, vis: facing(e.n), side };
  };
  const ears = [earAt(1), earAt(-1)].filter(Boolean);
  const drawEar = (er) => {
    // seen from the front an ear stands out from the head; from the side it lies flat on it
    const ow = 0.04 + 0.08 * (1 - Math.min(1, Math.abs(er.vis) * 1.6));
    const p0 = P(V.add(er.e.p, V.mul(er.e.n, ow))), pu = P(V.add(V.add(er.e.p, V.mul(er.e.n, ow)), [0, 0.2, -0.03]));
    const hy = px * 0.21, wx = px * 0.125 * (0.62 + 0.38 * Math.abs(er.vis));
    g.save(); g.translate(p0[0], p0[1]); g.rotate(Math.atan2(pu[0] - p0[0], -(pu[1] - p0[1])));
    g.beginPath(); g.ellipse(0, 0, wx, hy, 0, 0, Math.PI * 2);
    g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); g.fillStyle = skin; g.fill();
    if (px > 30) { g.beginPath(); g.ellipse(0, hy * 0.05, wx * 0.5, hy * 0.6, 0, -1.9, 1.2); g.lineWidth = lw * 0.6; g.strokeStyle = skinSh; g.stroke(); }
    g.restore();
  };
  for (const er of ears) if (er.vis < 0.25) drawEar(er);

  // ---- skin: ink, flat colour, the side away from the light
  g.beginPath(); headPath(g); g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke();
  g.beginPath(); headPath(g); g.fillStyle = skin; g.fill();
  g.save();
  g.beginPath(); headPath(g); g.clip();
  g.fillStyle = skinSh; g.beginPath(); headPath(g); g.fill();
  g.save(); g.translate(L[0] * px * 0.2, L[1] * px * 0.12); g.fillStyle = skin; g.beginPath(); headPath(g); g.fill(); g.restore();

  // ---- hair cap (with its shadow on the brow)
  let capTris = null;
  if (hp && hd.hairT.length) {
    const front = (coarse ? hd.hairT2 : hd.hairT).filter((q) => facing(q[3]) > 0);
    capTris = (q, dx = 0, dy = 0) => { for (const tq of front) { const a = hp[tq[0]], b = hp[tq[1]], cc = hp[tq[2]]; q.moveTo(a[0] + dx, a[1] + dy); q.lineTo(b[0] + dx, b[1] + dy); q.lineTo(cc[0] + dx, cc[1] + dy); q.closePath(); } };
    const dn = P([0, -1, 0]), up2 = P([0, 0, 0]); const ddx = dn[0] - up2[0], ddy = dn[1] - up2[1], dl = Math.hypot(ddx, ddy) || 1;
    g.beginPath(); capTris(g, (ddx / dl) * px * 0.1, (ddy / dl) * px * 0.1); g.fillStyle = skinSh; g.fill();
    g.restore(); // un-clip: the cap sits outside the scalp
    g.beginPath(); capTris(g); g.fillStyle = hairCol; g.strokeStyle = hairCol; g.lineWidth = 1.2; g.fill(); g.stroke();
    // a soft, even hairline: a line of hair along it hides the mesh's steps
    if (detail) {
      const run = [];
      for (const [lp, ln] of hd.line) { if (facing(ln) > 0.02) run.push(P(lp)); else if (run.length && run[run.length - 1]) run.push(null); }
      g.save(); g.beginPath(); headPath(g); g.clip();
      g.beginPath(); const seg = [];
      for (const q of run) { if (!q) { if (seg.length > 1) smoothOpen(g, seg.splice(0), 1); else seg.length = 0; continue; } seg.push(q); }
      if (seg.length > 1) smoothOpen(g, seg, 1);
      g.lineWidth = px * 0.07; g.lineCap = 'round'; g.lineJoin = 'round'; g.strokeStyle = hairCol; g.stroke();
      g.restore();
    }
  } else g.restore();

  // ---- the face, kept inside the head's outline: first what lies under the fringe…
  const fk = { P, facing, sh, px, d, face, lit, ink, lw, L, skin, skinSh, vl, nose: hd.nose, sf: hd.sf };
  if (px > 5) { g.save(); g.beginPath(); headPath(g); g.clip(); drawFace(g, cam, fk, 'under'); g.restore(); }
  for (const er of ears) if (er.vis >= 0.25) drawEar(er);
  if (d.beard && px > 6) drawBeard(g, { P, facing, sh, px, d, lit, ink, lw });

  // ---- the fringe, each lock with its own line, far ones first
  const nearL = locks.filter((q) => q.near).sort((a, b) => b.z - a.z);
  const fringe = nearL.filter((q) => q.lk.fringe), rest = nearL.filter((q) => !q.lk.fringe);
  for (const q of fringe) paintBlade(g, q.sh, hairCol, hairSh, hink, lw, !detail);
  // a band of shine across the crown and the fringe
  if (hair && px > 18 && hair.shine !== false && (capTris || fringe.length)) {
    g.save(); g.beginPath(); if (capTris) capTris(g); for (const q of fringe) bladePath(g, q.sh); g.clip();
    const b0 = P([-1.25, 0.6, 0.5]), b1 = P([0, 0.8, 0.86]), b2 = P([1.25, 0.6, 0.5]);
    g.beginPath(); g.moveTo(b0[0], b0[1]); g.quadraticCurveTo(b1[0], b1[1] - px * 0.06, b2[0], b2[1]);
    g.lineWidth = px * 0.1; g.strokeStyle = hairHi; g.lineCap = 'round'; g.globalAlpha = 0.5; g.stroke();
    g.restore();
  }
  // …then the eyes and brows, which a drawn face always shows through the hair
  if (px > 5) { g.save(); g.beginPath(); headPath(g); g.clip(); drawFace(g, cam, fk, 'over'); g.restore(); }
  for (const q of rest) paintBlade(g, q.sh, hairCol, hairSh, hink, lw, !detail);
  for (const tf of tufts) if (tf.front) paintBlade(g, tuftBlade(tf), hairCol, hairSh, hink, lw, tf.plain, 0.12);
  if (d.headgear && !hatUnder) drawHeadgear(g, cam, { P, W, facing, px, d, lit, ink, lw, vl, sh });
}

function drawBeard(g, k) {
  const { P, facing, sh, px, d, lit, ink, lw } = k, b = d.beard;
  const col = lit(b.color ?? d.hair?.color ?? '#333333');
  // points along the jaw, from ear to ear, and the beard's lower edge under the chin
  const top = [], low = [];
  for (let i = 0; i <= 16; i++) {
    // the beard's upper edge runs under the lower lip and up the cheeks to the ears; its lower edge hangs from the jaw
    const a = -1.55 + (3.1 * i) / 16, tEl = -0.9 + 0.62 * (1 - Math.exp(-((a / 0.5) ** 2))), jEl = -0.6 - 0.42 * Math.cos(a * 0.85);
    const sp = skull(a, tEl, sh), n = V.norm(sp);
    if (facing(n) < -0.25) continue;
    top.push(P(V.mul(sp, 1.03)));
    const len = (b.length ?? 0.3) * (0.25 + 0.75 * Math.cos(a * 0.75) ** 2);
    const base = skull(a, jEl, sh);
    low.push(P([base[0] * (1.04 - 0.3 * len), base[1] - 0.04 - len, base[2] * 1.05 + len * 0.1]));
  }
  if (top.length < 3) return;
  g.beginPath(); smoothOpen(g, top, 1); smoothOpen(g, low.reverse(), 1, false); g.closePath();
  g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); g.fillStyle = col; g.fill();
  if (px > 26) {
    g.strokeStyle = lit(shadeHex(b.color ?? d.hair?.color ?? '#333333', -0.22)); g.lineWidth = lw * 0.7; g.beginPath();
    for (let i = 1; i < top.length - 1; i += 2) { const a = top[i], c2 = low[low.length - 1 - i]; if (!c2) continue; g.moveTo(lerp(a[0], c2[0], 0.3), lerp(a[1], c2[1], 0.3)); g.lineTo(lerp(a[0], c2[0], 0.85), lerp(a[1], c2[1], 0.85)); }
    g.stroke();
  }
  if (b.mustache) {
    const m = surf(sh, 0, -0.56);
    if (facing(m.n) > 0.1) {
      const c0 = P(V.mul(m.p, 1.06)), l = P(V.mul(skull(0.34, -0.7, sh), 1.05)), r = P(V.mul(skull(-0.34, -0.7, sh), 1.05));
      g.beginPath(); g.moveTo(l[0], l[1]); g.quadraticCurveTo(c0[0], c0[1] - px * 0.09, r[0], r[1]); g.quadraticCurveTo(c0[0], c0[1] + px * 0.02, l[0], l[1]); g.closePath();
      g.lineWidth = lw * 1.4; g.strokeStyle = ink; g.stroke(); g.fillStyle = col; g.fill();
    }
  }
}

function drawHeadgear(g, cam, k) {
  const { P, px, d, lit, ink, lw, vl, sh } = k, hg = d.headgear;
  // a point of the skull's ball, blown up by k2
  const ball = (a, e, k2 = 1.07) => { const ce = Math.cos(e); return [Math.sin(a) * ce * sh.cw * k2, sh.cy + Math.sin(e) * sh.ch * k2, sh.cz + Math.cos(a) * ce * sh.cd * k2]; };
  const ring = (a, y, k2 = 1.04) => { const q = Math.sqrt(Math.max(0.05, 1 - ((y - sh.cy) / sh.ch) ** 2)); return [Math.sin(a) * sh.cw * q * k2, y, sh.cz + Math.cos(a) * sh.cd * q * k2]; };
  if (hg.type === 'straw') {
    // a wide cone hat: rim circle and apex
    const rim = [], n = 22, r = hg.r ?? 1.75, y = hg.y ?? 0.5;
    for (let i = 0; i < n; i++) { const a = (i / n) * Math.PI * 2; rim.push(P([Math.sin(a) * r, y, Math.cos(a) * r - 0.1])); }
    const apex = P([0, y + (hg.h ?? 0.75), -0.1]);
    const hl = hull(rim.concat([apex]));
    g.beginPath(); poly(g, hl); g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); g.fillStyle = lit(hg.color ?? '#d9b872'); g.fill();
    g.save(); g.beginPath(); poly(g, hl); g.clip();
    g.strokeStyle = lit(shadeHex(hg.color ?? '#d9b872', -0.25)); g.lineWidth = lw * 0.7; g.beginPath();
    for (let i = 0; i < n; i += 2) { g.moveTo(apex[0], apex[1]); g.lineTo(rim[i][0], rim[i][1]); }
    g.stroke();
    if (vl[1] < 0.05) { g.beginPath(); poly(g, rim); g.fillStyle = lit(shadeHex(hg.color ?? '#d9b872', -0.4)); g.fill(); }
    g.restore();
    g.beginPath(); poly(g, rim); g.lineWidth = lw; g.strokeStyle = ink; g.stroke();
  } else if (hg.type === 'helmet') {
    // a steel cap over the skull, open at the face; a crest for officers
    // a dome over the crown, and guards for the ears and the neck; a guard on the far side of the head is not drawn
    const dome = [], parts = [];
    for (let i = 0; i <= 7; i++) for (let j = 0; j < 22; j++) { const e = 0.14 + (Math.PI / 2 - 0.14) * (i / 7), a = (j / 22) * Math.PI * 2 - Math.PI; dome.push(P(ball(a, e, 1.09))); }
    for (const [a0, a1] of [[1.15, 2.05], [1.95, 4.33], [4.23, 5.13]]) {
      const am = (a0 + a1) / 2; if (Math.sin(am) * vl[0] + Math.cos(am) * vl[2] < (a1 - a0 > 2 ? 0.08 : -0.2)) continue;
      const q = []; for (let i = 0; i <= 4; i++) for (let j = 0; j <= 6; j++) q.push(P(ball(a0 + (a1 - a0) * (j / 6), -0.42 + 0.62 * (i / 4), 1.09)));
      parts.push(hull(q));
    }
    const hl = hull(dome), col = hg.color ?? '#6d7684';
    const path = () => { g.beginPath(); for (const q of parts) smoothClosed(g, q, 0.6); smoothClosed(g, hl, 0.8); };
    path(); g.lineJoin = 'round'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); g.fillStyle = lit(col); g.fill();
    g.save(); path(); g.clip();
    g.fillStyle = lit(shadeHex(col, -0.3)); path(); g.fill();
    g.translate(-px * 0.2, -px * 0.16); g.fillStyle = lit(col); path(); g.fill();
    g.translate(-px * 0.5, -px * 0.45); g.fillStyle = lit(shadeHex(col, 0.3)); g.globalAlpha = 0.6; path(); g.fill();
    g.restore();
    // the brow band
    const band = []; for (let i = 0; i <= 14; i++) { const a = -1.5 + (3.0 * i) / 14; if (Math.sin(a) * vl[0] + Math.cos(a) * vl[2] < -0.1) continue; band.push([P(ball(a, 0.3, 1.1)), P(ball(a, 0.13, 1.1))]); }
    if (band.length > 2) { g.beginPath(); smoothOpen(g, band.map((q) => q[0]), 1); smoothOpen(g, band.map((q) => q[1]).reverse(), 1, false); g.closePath(); g.lineWidth = lw * 1.4; g.strokeStyle = ink; g.stroke(); g.fillStyle = lit(hg.band ?? shadeHex(col, -0.25)); g.fill(); }
    if (hg.crest) {
      const cr = [], cr2 = [];
      for (let i = 0; i <= 8; i++) { const a = -0.5 + (2.3 * i) / 8; cr.push(P([0, sh.cy + Math.cos(a) * sh.ch * 1.1, sh.cz - Math.sin(a) * sh.cd * 1.1])); cr2.push(P([0, sh.cy + Math.cos(a) * sh.ch * 1.48, sh.cz - Math.sin(a) * sh.cd * 1.4])); }
      g.beginPath(); smoothOpen(g, cr, 1); smoothOpen(g, cr2.reverse(), 1, false); g.closePath(); g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); g.fillStyle = lit(hg.crest); g.fill();
    }
  } else if (hg.type === 'crown') {
    const col = hg.color ?? '#d8a93a', n = 16, y0 = hg.y ?? 0.62, front = [];
    for (let i = 0; i <= n; i++) { const a = -Math.PI + (i / n) * Math.PI * 2; if (Math.sin(a) * vl[0] + Math.cos(a) * vl[2] < -0.15) continue; const lo = ring(a, y0, 1.1), hi = ring(a, y0, 1.12); front.push({ lo: P(lo), hi: P([hi[0], y0 + 0.2 + (i % 2 ? 0.24 : 0), hi[2]]) }); }
    if (front.length > 2) {
      g.beginPath(); g.moveTo(front[0].lo[0], front[0].lo[1]); for (const q of front) g.lineTo(q.hi[0], q.hi[1]); for (let i = front.length - 1; i >= 0; i--) g.lineTo(front[i].lo[0], front[i].lo[1]); g.closePath();
      g.lineJoin = 'miter'; g.lineWidth = lw * 2; g.strokeStyle = ink; g.stroke(); g.fillStyle = lit(col); g.fill();
      if (px > 30) { g.fillStyle = lit(hg.gem ?? '#c0283a'); const m = front[Math.floor(front.length / 2)]; g.beginPath(); g.arc(m.lo[0], (m.lo[1] + m.hi[1]) / 2 + px * 0.04, px * 0.07, 0, 7); g.fill(); }
    }
  } else if (hg.type === 'band') {
    const col = hg.color ?? '#b3262b', pts = [], y0 = hg.y ?? 0.5, w = hg.w ?? 0.15;
    for (let i = 0; i <= 18; i++) { const a = -2.2 + (4.4 * i) / 18; if (Math.sin(a) * vl[0] + Math.cos(a) * vl[2] < -0.1) continue; pts.push([P(ring(a, y0 + w / 2, 1.1)), P(ring(a, y0 - w / 2, 1.1))]); }
    if (pts.length > 2) { g.beginPath(); smoothOpen(g, pts.map((q) => q[0]), 1); smoothOpen(g, pts.map((q) => q[1]).reverse(), 1, false); g.closePath(); g.lineWidth = lw * 1.4; g.strokeStyle = ink; g.stroke(); g.fillStyle = lit(col); g.fill(); }
  }
}

/* ------------------------------------------------------------------ face */

/** Features drawn in the surface's own frame, so they turn, tilt and foreshorten with the head. */
function drawFace(g, cam, k, pass) {
  const { P, facing, sh, px, d, face, lit, ink, lw, L, skin, skinSh, vl } = k;
  const under = pass === 'under', over = pass === 'over';
  const fd = d.face ?? {}, ey = fd.eye ?? {};
  const eyeAz = ey.az ?? 0.47, eyeEl = ey.el ?? -0.06, ew = ey.w ?? 0.2, eh = ey.h ?? 0.15;
  const frame = (a, e, w, h, lift = 1.012, minW = 0.4) => {
    const sf = surf(sh, a, e, k.sf);
    const vis = facing(sf.n);
    const c0 = P(V.mul(sf.p, lift)), cx = P(V.add(V.mul(sf.p, lift), V.mul(sf.ta, w))), cy = P(V.add(V.mul(sf.p, lift), V.mul(sf.te, h)));
    let ex = [cx[0] - c0[0], cx[1] - c0[1]], ey2 = [cy[0] - c0[0], cy[1] - c0[1]];
    // drawn faces do not foreshorten as hard as real ones: in profile an eye is still a clear wedge
    const full = w * px, fullH = h * px, lx = Math.hypot(ex[0], ex[1]), ly = Math.hypot(ey2[0], ey2[1]);
    if (lx < full * minW) { const kx = (full * minW) / (lx || 1e-6); if (lx > 1e-3) ex = [ex[0] * kx, ex[1] * kx]; else { const fw = P([0, 0, 1.2]), cc = P([0, 0, 0]); const dx = fw[0] - cc[0], dy = fw[1] - cc[1], dl = Math.hypot(dx, dy) || 1; ex = [(-dx / dl) * full * minW * Math.sign(a || 1), (-dy / dl) * full * minW * Math.sign(a || 1)]; } }
    if (ly < fullH * 0.5) { const ky = (fullH * 0.5) / (ly || 1e-6); ey2 = [ey2[0] * ky, ey2[1] * ky]; }
    return { vis, c0, ex, ey: ey2 };
  };
  if (k.frame3) return drawEyes3(g, k);
  const inFrame = (fm, mirror, fn) => { g.save(); g.transform(fm.ex[0] * (mirror ? -1 : 1), fm.ex[1] * (mirror ? -1 : 1), fm.ey[0], fm.ey[1], fm.c0[0], fm.c0[1]); fn(); g.restore(); };
  const big = px > 26, mid = px > 12;
  const blink = clamp(face.blink ?? 0), open = clamp((face.eyeOpen ?? 1) * (1 - blink), 0, 1.4);
  const look = face.look ?? [0, 0];

  // ---- blush under the eyes
  if (under && (face.blush ?? fd.blush ?? 0) > 0.02 && mid) {
    for (const sd of [1, -1]) { const fm = frame(sd * (eyeAz + 0.14), eyeEl - 0.27, 0.17, 0.08); if (fm.vis < 0.2) continue; g.save(); g.globalAlpha = 0.5 * (face.blush ?? fd.blush) * clamp(fm.vis * 2); inFrame(fm, false, () => { g.beginPath(); g.ellipse(0, 0, 1, 1, 0, 0, 7); }); g.fillStyle = lit('#f08a7a'); g.fill(); g.restore(); }
  }

  eyesAndBrows(g, { ...k, over, mid, big, open, look, frame: (sd, kind, lift) => (kind === 'eye' ? frame(sd * eyeAz, eyeEl, ew, eh) : frame(sd * (eyeAz + 0.02), eyeEl + ((fd.brow ?? {}).gap ?? 0.27) + lift * 0.07, ew * 1.15, eh * 0.6 + 0.04)), inFrame });

  // ---- nose: a tick under the tip, a small shadow on the side away from the light, the ridge when the head turns
  if (under && mid && vl[2] > -0.2) {
    const nb = k.nose, tip = P(nb[2]), base = P(nb[5]), wl = P(nb[3]), wr = P(nb[4]), ridge = P(V.lerp(nb[0], nb[1], 0.72));
    const wing = (wl[0] - tip[0]) * -L[0] > (wr[0] - tip[0]) * -L[0] ? wl : wr; // the wing on the shadow side
    const turn = Math.abs(vl[0]);
    g.save(); g.lineCap = 'round'; g.lineJoin = 'round';
    if (turn < 0.93) {
      g.beginPath(); g.moveTo(tip[0], tip[1]); g.lineTo(wing[0], wing[1]); g.lineTo(base[0], base[1]); g.closePath();
      g.fillStyle = skinSh; g.globalAlpha = 0.9; g.fill(); g.globalAlpha = 1;
      // the ridge: only once the head has turned enough to show it
      const ra = clamp((turn - 0.16) * 3.2);
      if (ra > 0.02) { g.beginPath(); g.moveTo(ridge[0], ridge[1]); g.lineTo(tip[0], tip[1]); g.lineWidth = lw * 0.6; g.strokeStyle = ink; g.globalAlpha = 0.7 * ra; g.stroke(); g.globalAlpha = 1; }
      g.beginPath(); g.moveTo(tip[0], tip[1]); g.lineTo(lerp(tip[0], base[0], 0.9), lerp(tip[1], base[1], 0.9));
      g.lineWidth = lw * (big ? 0.8 : 0.6); g.strokeStyle = ink; g.globalAlpha = 0.8; g.stroke(); g.globalAlpha = 1;
    }
    g.restore();
  }

  // ---- mouth
  if (under) {
    const mo = clamp(face.mouthOpen ?? 0), wide = clamp(face.mouthWide ?? 0, -1, 1), smile = clamp(face.smile ?? 0, -1, 1);
    const mw = (fd.mouthW ?? 0.2) * (1 + 0.3 * wide + 0.12 * Math.abs(smile)) * (1 - 0.25 * Math.max(0, -wide));
    const fm = frame(0, fd.mouthEl ?? Math.atan2(sh.mouthY, sh.faceZat(sh.mouthY)), mw, 0.14, 1.0 + sh.lips * 1.3, 0.42);
    if (fm.vis > -0.08 && mid) {
      const cy = smile * 0.55; // corners up (smile) or down
      g.save();
      // in a head turned well away only the near half of the mouth is drawn
      const half = fm.vis < 0.5, near = vl[0] > 0 ? 1 : -1;
      if (half) { inFrame(fm, false, () => { g.beginPath(); g.rect(near > 0 ? -0.22 : -1.6, -4, 1.82, 8); }); g.clip(); }
      if (mo < 0.06) {
        inFrame(fm, false, () => { g.beginPath(); g.moveTo(-1, cy); g.quadraticCurveTo(0, -cy * 0.9 - 0.12, 1, cy); });
        g.lineWidth = lw * (big ? 1.25 : 1); g.lineCap = 'round'; g.strokeStyle = ink; g.stroke();
        if (big) { inFrame(fm, false, () => { g.beginPath(); g.moveTo(-0.4, -0.75 - cy * 0.3); g.quadraticCurveTo(0, -0.95, 0.4, -0.75 - cy * 0.3); }); g.lineWidth = lw * 0.5; g.strokeStyle = skinSh; g.stroke(); }
      } else {
        const top = 0.25 + 0.5 * mo * Math.max(0, wide), bot = -(0.5 + 2.6 * mo);
        const shape = () => { g.beginPath(); g.moveTo(-1, cy); g.bezierCurveTo(-0.5, top + cy * 0.4, 0.5, top + cy * 0.4, 1, cy); g.bezierCurveTo(0.75, bot, -0.75, bot, -1, cy); g.closePath(); };
        inFrame(fm, false, shape);
        g.fillStyle = lit('#3d1519'); g.fill();
        g.save(); g.clip();
        if (mo > 0.18 && (face.teeth ?? true)) { inFrame(fm, false, () => { g.beginPath(); g.rect(-1.2, top - 0.42 + cy * 0.3, 2.4, 0.5); }); g.fillStyle = lit('#f4efe6'); g.fill(); }
        if (mo > 0.3) { inFrame(fm, false, () => { g.beginPath(); g.ellipse(0, bot + 0.35, 0.62, 0.75, 0, 0, 7); }); g.fillStyle = lit('#a04a52'); g.fill(); }
        g.restore();
        inFrame(fm, false, shape);
        g.lineWidth = lw * (big ? 1.1 : 0.9); g.lineJoin = 'round'; g.strokeStyle = ink; g.stroke();
      }
      g.restore();
    }
  }

  // ---- lines of age, scars
  if (under && big && fd.lines) {
    g.save(); g.lineWidth = lw * 0.55; g.lineCap = 'round'; g.strokeStyle = skinSh;
    for (const sd of [1, -1]) {
      const fm = frame(sd * 0.33, -0.5, 0.1, 0.16); if (fm.vis < 0.2) continue;
      inFrame(fm, sd < 0, () => { g.beginPath(); g.moveTo(-0.4, 1); g.quadraticCurveTo(0.6, 0.2, 0.5, -1); }); g.stroke();
      const f2 = frame(sd * (eyeAz + 0.24), eyeEl - 0.1, 0.07, 0.07); if (f2.vis > 0.2) { inFrame(f2, sd < 0, () => { g.beginPath(); g.moveTo(-0.5, 0.4); g.lineTo(0.8, 0.9); g.moveTo(-0.5, -0.1); g.lineTo(0.9, -0.2); }); g.stroke(); }
    }
    const f3 = frame(0, 0.36, 0.3, 0.06); if (f3.vis > 0.2) { inFrame(f3, false, () => { g.beginPath(); g.moveTo(-0.9, 0.5); g.quadraticCurveTo(0, 1, 0.9, 0.5); g.moveTo(-0.7, -0.6); g.quadraticCurveTo(0, -0.2, 0.7, -0.6); }); g.stroke(); }
    g.restore();
  }
  if (under && fd.scar && mid) {
    const fm = frame(fd.scar.az ?? -0.5, fd.scar.el ?? -0.04, 0.07, fd.scar.len ?? 0.3);
    if (fm.vis > 0.15) { g.save(); inFrame(fm, false, () => { g.beginPath(); g.moveTo(-0.6, 1); g.lineTo(0.5, -1); }); g.lineWidth = lw * 1.3; g.lineCap = 'round'; g.strokeStyle = lit(fd.scar.color ?? '#8a4a44'); g.stroke(); g.restore(); }
  }
  // ---- dramatic shade over the eyes (rage, grief)
  if (under && (face.eyeShade ?? 0) > 0.02) {
    const fm = frame(0, eyeEl + 0.16, 0.95, 0.42);
    if (fm.vis > 0.2) { g.save(); inFrame(fm, false, () => { g.beginPath(); g.rect(-1.4, -0.6, 2.8, 1.9); }); g.fillStyle = `rgba(20,14,30,${0.55 * face.eyeShade})`; g.fill(); g.restore(); }
  }
  void skin;
}

/** Eyes and brows drawn in frames that ride on the face. k.frame(side, 'eye' | 'brow', lift) → { vis, c0, ex, ey }. */
function eyesAndBrows(g, k) {
  const { d, face, lit, ink, lw, L, skinSh, over, mid, big, open, look, inFrame } = k;
  const fd = d.face ?? {}, ey = fd.eye ?? {};
  // ---- eyes
  for (const sd of over ? [1, -1] : []) {
    const fm = k.frame(sd, 'eye', 0);
    if (fm.vis < 0.1) continue;
    const mirror = sd < 0; // the path is written for the left eye: inner corner at −x
    const tilt = ey.tilt ?? 0.12, sharp = ey.sharp ?? 0;
    const o = Math.max(0.0, open), happy = clamp(face.eyeSmile ?? 0);
    const lidTop = 0.95 * Math.min(1.15, o) + 0.05, lidLow = -0.72 * Math.min(1.1, o) * (1 - 0.6 * happy);
    const inX = -1, outX = 1, inY = -0.1 - sharp * 0.2, outY = tilt;
    if (!mid) { // far away: a dark dash
      g.save(); inFrame(fm, mirror, () => { g.beginPath(); g.ellipse(0, 0, 0.7, Math.max(0.25, 0.7 * o), 0, 0, 7); }); g.fillStyle = ink; g.fill(); g.restore();
      continue;
    }
    const upper = () => { g.moveTo(inX, inY); g.bezierCurveTo(-0.55, lidTop * (1 - sharp * 0.35), 0.35, lidTop * 1.02, outX, outY); };
    const lower = () => { g.bezierCurveTo(0.6, lidLow * 0.9, -0.5, lidLow, inX, inY); };
    if (o < 0.12) {
      // closed: one line (curved up when smiling)
      g.save(); inFrame(fm, mirror, () => { g.beginPath(); g.moveTo(inX, inY + 0.05); g.quadraticCurveTo(0, happy > 0.4 ? 0.55 : -0.35, outX, outY * 0.6); });
      g.lineWidth = lw * (big ? 1.5 : 1.1); g.lineCap = 'round'; g.strokeStyle = ink; g.stroke(); g.restore();
    } else {
      // the white
      g.save();
      inFrame(fm, mirror, () => { g.beginPath(); upper(); lower(); g.closePath(); });
      g.fillStyle = lit(ey.white ?? '#fdfbf7'); g.fill();
      g.clip();
      // iris and pupil follow the gaze
      const ir = ey.iris ?? 0.56, gx = clamp(look[0], -1, 1) * 0.34 * (mirror ? -1 : 1), gy = clamp(look[1], -1, 1) * 0.22 + 0.02;
      const pup = face.pupil ?? 1;
      inFrame(fm, mirror, () => { g.beginPath(); g.ellipse(gx, gy, ir * pup, ir * 1.18 * pup, 0, 0, 7); });
      const c1 = lit(ey.color ?? '#5a3a22');
      g.fillStyle = c1; g.fill();
      if (big) {
        // darker top of the iris (the lid's shade) and a lighter crescent below
        g.save(); g.clip();
        inFrame(fm, mirror, () => { g.beginPath(); g.ellipse(gx, gy + ir * 0.75, ir * 1.3, ir * 0.95, 0, 0, 7); }); g.fillStyle = lit(shadeHex(ey.color ?? '#5a3a22', -0.45)); g.fill();
        inFrame(fm, mirror, () => { g.beginPath(); g.ellipse(gx, gy - ir * 0.55, ir * 0.7, ir * 0.45, 0, 0, 7); }); g.fillStyle = lit(shadeHex(ey.color ?? '#5a3a22', 0.3)); g.globalAlpha = 0.75; g.fill(); g.globalAlpha = 1;
        g.restore();
      }
      inFrame(fm, mirror, () => { g.beginPath(); g.ellipse(gx, gy + 0.03, ir * 0.42 * pup, ir * 0.55 * pup, 0, 0, 7); }); g.fillStyle = ink; g.fill();
      if (face.glow) { inFrame(fm, mirror, () => { g.beginPath(); g.ellipse(gx, gy, ir * pup, ir * 1.18 * pup, 0, 0, 7); }); g.fillStyle = face.glow; g.globalAlpha = clamp(face.glowAmt ?? 0.8); g.fill(); g.globalAlpha = 1; }
      // shade under the upper lid
      inFrame(fm, mirror, () => { g.beginPath(); g.moveTo(inX, inY); g.bezierCurveTo(-0.55, lidTop * (1 - sharp * 0.35), 0.35, lidTop * 1.02, outX, outY); g.bezierCurveTo(0.35, lidTop * 0.62, -0.55, lidTop * 0.55, inX, inY); g.closePath(); });
      g.fillStyle = 'rgba(40,30,60,0.22)'; g.fill();
      // highlights: the light's reflection
      if (big && !face.dead) {
        const hx = gx + (mirror ? 1 : -1) * ir * 0.3 * (L[0] < 0 ? 1 : -1), hy = gy + ir * 0.5;
        inFrame(fm, mirror, () => { g.beginPath(); g.ellipse(hx, hy, ir * 0.24, ir * 0.3, 0, 0, 7); g.ellipse(gx - (hx - gx) * 0.9, gy - ir * 0.45, ir * 0.11, ir * 0.13, 0, 0, 7); });
        g.fillStyle = '#ffffff'; g.fill();
      }
      g.restore();
      // upper lash: a thick tapered line; lower lid: a hair line
      g.save();
      inFrame(fm, mirror, () => {
        g.beginPath(); upper();
        const th = (ey.lash ?? 0.2);
        g.lineTo(outX + (ey.flick ?? 0.12), outY + (ey.flick ?? 0.12) * 0.9); // outer flick
        g.bezierCurveTo(0.4, lidTop * 1.02 + th, -0.5, lidTop * (1 - sharp * 0.35) + th * 0.85, inX - 0.02, inY + 0.02);
        g.closePath();
      });
      g.fillStyle = ink; g.fill();
      if (big) {
        inFrame(fm, mirror, () => { g.beginPath(); g.moveTo(outX, outY); g.bezierCurveTo(0.6, lidLow * 0.9, 0.1, lidLow * 0.98, -0.25, lidLow * 0.95); });
        g.lineWidth = lw * 0.6; g.lineCap = 'round'; g.strokeStyle = ink; g.globalAlpha = 0.7; g.stroke(); g.globalAlpha = 1;
        // crease above the lid
        inFrame(fm, mirror, () => { g.beginPath(); g.moveTo(-0.7, lidTop * 0.85 + 0.42); g.quadraticCurveTo(0.1, lidTop + 0.62, 0.85, outY + 0.42); });
        g.lineWidth = lw * 0.5; g.strokeStyle = skinSh; g.stroke();
      }
      g.restore();
    }
    // tears
    if ((face.tears ?? 0) > 0.05 && big) {
      const tr = face.tears;
      g.save(); inFrame(fm, mirror, () => { g.beginPath(); g.ellipse(0.2, lidLow - 0.1, 0.55, 0.16, 0, 0, 7); if (tr > 0.4) { g.moveTo(0.35, lidLow - 0.1); g.bezierCurveTo(0.5, lidLow - 1.2, 0.35, lidLow - 2.2 * tr, 0.3, lidLow - 3.4 * tr); g.bezierCurveTo(0.12, lidLow - 2.2 * tr, 0.2, lidLow - 1.0, 0.05, lidLow - 0.1); } });
      g.fillStyle = 'rgba(190,225,255,0.75)'; g.fill(); g.restore();
    }
  }

  // ---- brows
  const br = fd.brow ?? {};
  for (const sd of over ? [1, -1] : []) {
    const lift = (sd > 0 ? face.browL : face.browR) ?? 0, tiltB = face.browTilt ?? 0; // tilt>0: inner ends up (worry); <0: inner ends down (anger)
    const fm = k.frame(sd, 'brow', lift);
    if (fm.vis < 0.12 || !mid) continue;
    const mirror = sd < 0, th = (br.thick ?? 0.16);
    const yi = tiltB * 0.75 - (face.eyeSmile ?? 0) * 0.1, yo = -tiltB * 0.35 + (br.arch ?? 0.1);
    g.save();
    inFrame(fm, mirror, () => { g.beginPath(); g.moveTo(-1.02, yi - th * 0.9); g.quadraticCurveTo(0, (yi + yo) / 2 + (br.arch ?? 0.1) + 0.3, 1.05, yo); g.quadraticCurveTo(0, (yi + yo) / 2 + (br.arch ?? 0.1) + 0.3 + th * 2.2, -1.02, yi + th * 1.5); g.closePath(); });
    g.fillStyle = lit(br.color ?? d.hair?.color ?? '#2a2020'); g.fill();
    g.restore();
  }

}

/** The drawn eyes and brows on a real head (see bodydraw.js): k.frame3(side, kind, lift) gives the frames; k.px the head's size in the picture. */
function drawEyes3(g, k) {
  const face = k.face, blink = clamp(face.blink ?? 0), open = clamp((face.eyeOpen ?? 1) * (1 - blink), 0, 1.4);
  const inFrame = (fm, mirror, fn) => { g.save(); g.transform(fm.ex[0] * (mirror ? -1 : 1), fm.ex[1] * (mirror ? -1 : 1), fm.ey[0], fm.ey[1], fm.c0[0], fm.c0[1]); fn(); g.restore(); };
  eyesAndBrows(g, { ...k, over: true, mid: k.px > 12, big: k.px > 26, open, look: face.look ?? [0, 0], frame: k.frame3, inFrame });
}
export function drawEyesOn(g, k) { drawEyes3(g, k); }
