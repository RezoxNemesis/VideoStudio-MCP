// CodeScape MOTION — the shared library every film uses, so hands hold things, heads talk, and cameras stay steady.
// Written after the owner's review of "Papa ki Pehli Salary" (26 Nov 2026): the picture shook when people spoke, hands did not
// really hold or place the letter, faces were flat. Every rule here has a check in review/ (camshake, contact, reach, lens).
//
//  HANDS   refineHands(p)            a real hand: shaped palm, tapered fingers with knuckles and nails, a three-bone thumb
//          pinchSheet(p, s, L, o)    hold a card/letter/envelope between thumb and fingers — the hand follows the thing, pads ON its faces
//          snap / blendHand          capture a whole arm+hand pose and move between poses on an arc (approach, align, withdraw)
//  SPEECH  speechPlan / speechHead   small eased nods and brow flashes on the stressed words (never a sine of the loudness)
//  CAMERA  steady(shot, …)           the lens aims at rest positions measured at fixed moments — never at a live head
//  FACE    blinks, saccade, tremble  blinks on changes of thought, eyes that search a face, a lip that trembles
import { THREE, V3, clamp, lerp, sm, sm2, seg } from './engine.js';
import { aim } from './rig.js';
const Q = THREE.Quaternion, M4 = THREE.Matrix4, PI = Math.PI;
const wp = (o, out = new V3()) => { o.updateWorldMatrix(true, false); return out.setFromMatrixPosition(o.matrixWorld); };
function solve2(a, b, S, T, pole) { const d0 = new V3().subVectors(T, S); let d = d0.length(); const dn = d0.clone().divideScalar(d || 1); const far = d > (a + b) * 0.9995; d = clamp(d, Math.abs(a - b) + 1e-4, (a + b) * 0.9995);
  const x = (a * a - b * b + d * d) / (2 * d), h = Math.sqrt(Math.max(0, a * a - x * x)); const p = pole.clone().addScaledVector(dn, -pole.dot(dn)); if (p.lengthSq() < 1e-10) p.set(0, 0, 1).addScaledVector(dn, -dn.z); p.normalize();
  return { E: S.clone().addScaledVector(dn, x).addScaledVector(p, h), T: S.clone().addScaledVector(dn, d), far }; }

// =================================================================================================== HANDS
function taperMesh(r0, r1, len, m, segs = 14) { const pts = [], n = 8; len = Math.max(len, 0.002);
  for (let i = 0; i <= n; i++) { const a = -PI / 2 + PI / 2 * i / n; pts.push(new THREE.Vector2(r1 * Math.cos(a), -len + r1 * Math.sin(a))); }
  for (let i = 0; i <= n; i++) { const a = PI / 2 * i / n; pts.push(new THREE.Vector2(r0 * Math.cos(a), r0 * Math.sin(a))); }
  const me = new THREE.Mesh(new THREE.LatheGeometry(pts, segs), m); me.castShadow = true; me.receiveShadow = true; return me; }
function superBall(hx, hy, hz, m, e = 3.4, shape) { const g = new THREE.BoxGeometry(2, 2, 2, 12, 14, 6), p = g.attributes.position; // a rounded block: a cube's even grid pushed onto a super-ellipsoid (no poles, no pinched corners)
  for (let i = 0; i < p.count; i++) { let x = p.getX(i), y = p.getY(i), z = p.getZ(i); const k = Math.pow(Math.abs(x) ** e + Math.abs(y) ** e + Math.abs(z) ** e, -1 / e); x *= k; y *= k; z *= k; const v = shape ? shape(x, y, z) : [x, y, z]; p.setXYZ(i, v[0] * hx, v[1] * hy, v[2] * hz); }
  g.deleteAttribute('normal'); g.deleteAttribute('uv'); const gm = mergeVerts(g); gm.computeVertexNormals(); const me = new THREE.Mesh(gm, m); me.castShadow = true; me.receiveShadow = true; return me; }
/** weld a geometry's coincident vertices so its normals are smooth across the faces of the block it was made from */
function mergeVerts(g) { const p = g.attributes.position, idx = g.index.array, map = new Map(), pos = [], re = new Uint32Array(p.count); for (let i = 0; i < p.count; i++) { const k = `${Math.round(p.getX(i) * 1e5)},${Math.round(p.getY(i) * 1e5)},${Math.round(p.getZ(i) * 1e5)}`; let j = map.get(k); if (j === undefined) { j = pos.length / 3; map.set(k, j); pos.push(p.getX(i), p.getY(i), p.getZ(i)); } re[i] = j; }
  const o = new THREE.BufferGeometry(); o.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); o.setIndex(Array.from(idx, i => re[i])); return o; }
const G = (parent, x = 0, y = 0, z = 0) => { const g = new THREE.Group(); g.position.set(x, y, z); parent.add(g); return g; };
// finger geometry (metres, for a person of size 1): knuckle x, knuckle y, bone lengths, base radius
const FX = [0.0305, 0.0102, -0.0102, -0.0295], FY = [-0.0885, -0.0915, -0.0875, -0.079], FL = [[0.034, 0.024, 0.021], [0.038, 0.027, 0.022], [0.035, 0.025, 0.021], [0.027, 0.019, 0.019]], FR = [0.0089, 0.0092, 0.0087, 0.0077];
const TH = { a: 0.044, b: 0.033, c: 0.027, r: 0.0092, base: [0.027, -0.025, 0.012] };
const tipR = (f, k) => FR[f] * k * 0.8;

/** Give a person real hands. k: finger thickness (1 a man, 0.9 a woman). The rig's own grip/flat/thumb functions are replaced to suit. */
export function refineHands(p, o = {}) { const k = o.k ?? 1, sk = p.mSkin; const nailM = new THREE.MeshStandardMaterial({ color: new THREE.Color(sk.color).lerp(new THREE.Color('#ffe9e2'), 0.38), roughness: 0.28 });
  for (const s of ['l', 'r']) { const A = p.arm[s], ha = A.ha, sg = A.sg; for (const ch of [...ha.children]) ha.remove(ch);
    const hand = { fingers: [], thumb: null, k };
    // palm: a rounded slab, narrower at the wrist, the back gently domed, the heel and the thumb's ball fuller
    const palm = superBall(0.0405 * k, 0.0475, 0.0132 * k, sk, 3.2, (x, y, z) => [x * (1 - 0.16 * Math.max(0, y)) * (1 + 0.04 * Math.max(0, -y)), y, z * (z < 0 ? 1.12 - 0.25 * x * x : 1) * (1 + 0.18 * Math.max(0, y))]); palm.position.set(0, -0.0445, 0.003); ha.add(palm);
    const wr = new THREE.Mesh(new THREE.SphereGeometry(0.03 * k, 20, 14), sk); wr.scale.set(1, 0.95, 0.72); wr.position.set(0, -0.002, 0.001); wr.castShadow = true; ha.add(wr);
    for (let f = 0; f < 4; f++) { let par = G(ha, sg * FX[f], FY[f], 0.004); const segs = [];
      for (let j = 0; j < 3; j++) { const jt = j === 0 ? par : G(par, 0, -FL[f][j - 1], 0); const r0 = FR[f] * k * 0.94 ** j, r1 = j === 2 ? tipR(f, k) : FR[f] * k * 0.94 ** (j + 1); jt.add(taperMesh(r0, r1, FL[f][j] - (j === 2 ? r1 : 0), sk, 12));
        if (j === 2) { const nl = new THREE.Mesh(new THREE.SphereGeometry(1, 12, 8), nailM); nl.scale.set(r1 * 0.78, r1 * 1.25, r1 * 0.32); nl.position.set(0, -FL[f][2] + r1 * 1.55, -r1 * 0.78); jt.add(nl); }
        segs.push(jt); par = jt; }
      hand.fingers.push(segs); }
    const tb = G(ha, sg * TH.base[0], TH.base[1], TH.base[2]); tb.add(taperMesh(0.0138 * k, 0.0116 * k, TH.a, sk, 12)); const thenar = new THREE.Mesh(new THREE.SphereGeometry(0.0185 * k, 18, 12), sk); thenar.scale.set(0.9, 1.4, 0.8); thenar.position.set(0, -0.021, 0.001); thenar.castShadow = true; tb.add(thenar);
    const t1 = G(tb, 0, -TH.a, 0); t1.add(taperMesh(0.0112 * k, 0.01 * k, TH.b, sk, 12)); const t2 = G(t1, 0, -TH.b, 0); const tr = TH.r * k * 0.93; t2.add(taperMesh(0.01 * k, tr, TH.c - tr, sk, 12));
    { const nl = new THREE.Mesh(new THREE.SphereGeometry(1, 12, 8), nailM); nl.scale.set(tr * 0.8, tr * 1.3, tr * 0.32); nl.position.set(0, -TH.c + tr * 1.6, -tr * 0.8); t2.add(nl); }
    hand.thumb = [tb, t1, t2]; A.hand = hand; }
  // ---- the rig's finger functions, re-made for this hand
  p.fingers = (s, c, spread = 0, ratio = [1.0, 1.1, 0.6]) => { const h = p.arm[s].hand, sg = p.arm[s].sg; h.fingers.forEach((segs, f) => { const q = Array.isArray(c) ? c[f] : c; segs[0].rotation.set(-q * ratio[0], 0, sg * (spread * (f - 1.5) * 0.12 + (f - 1.5) * 0.02 * (1 - Math.min(1, q)))); segs[1].rotation.set(-q * ratio[1], 0, 0); segs[2].rotation.set(-q * ratio[2], 0, 0); }); };
  p.thumbDirs = (s, dM, dP, dD, pad) => { const A = p.arm[s], h = A.hand; A.ha.updateWorldMatrix(true, false); const q = A.ha.getWorldQuaternion(new Q()); const W = v => new V3(v[0] * A.sg, v[1], v[2]).applyQuaternion(q); for (const j of h.thumb) j.rotation.set(0, 0, 0); const hint = W(pad); aim(h.thumb[0], W(dM), hint); aim(h.thumb[1], W(dP), hint); aim(h.thumb[2], W(dD), hint); };
  /** the thumb at rest beside the hand (t = 0) … folded across a fist (t = 1) */
  p.thumbRest = (s, t = 0) => { const L3 = (a, b) => a.map((v, i) => lerp(v, b[i], t)); p.thumbDirs(s, L3([0.6, -0.58, 0.55], [0.3, -0.5, 0.82]), L3([0.4, -0.8, 0.44], [-0.25, -0.72, 0.64]), L3([0.26, -0.92, 0.3], [-0.6, -0.72, 0.34]), L3([-0.8, 0.05, 0.6], [-0.3, 0.3, -0.9])); };
  p.grip = (s, c, spread = 0) => { p.fingers(s, c.slice(0, 4), spread, [1.25, 1.45, 1.1]); p.thumbRest(s, clamp(c[4] ?? 0, 0, 1)); };
  p.thumbTo = (s, T, out) => thumbPad(p, s, T, out.clone().normalize(), { r0: 0 });
  /** thumb over the front of a slab that lies on the palm (its back face at hand-local z = 0.022) */
  p.thumbOver = (s, lift = 0) => { const A = p.arm[s]; A.ha.updateWorldMatrix(true, false); const P = A.ha.localToWorld(new V3(A.sg * 0.014, -0.086, 0.0285 + lift * 0.03)); const n = new V3(0, 0, 1).transformDirection(A.ha.matrixWorld); thumbPad(p, s, P, n, {}); };
  p.flat = (s, c = 0.04, spread = 0.15, lift = 0) => { p.fingers(s, c, spread, [1.25, 1.45, 1.1]); p.thumbOver(s, lift); };
  p.refined = true; return p; }

/** The thumb's pad pressed on a surface: P a point of the surface (world), n its normal toward the thumb. o.lift: metres off the surface; o.dir: where the thumb points along the surface (world). → { far } when it cannot reach. */
export function thumbPad(p, s, P, n, o = {}) { const A = p.arm[s], h = A.hand, z = p.size, k = h.k ?? 1; for (const j of h.thumb) j.rotation.set(0, 0, 0); const S = wp(h.thumb[0]); const r = (o.r0 ?? TH.r * k) * z;
  let u = o.dir ? o.dir.clone() : new V3().subVectors(P, S); u.addScaledVector(n, -u.dot(n)); if (u.lengthSq() < 1e-10) u.set(0, -1, 0).transformDirection(A.ha.matrixWorld); u.normalize();
  const J2 = P.clone().addScaledVector(n, r + (o.lift || 0)).addScaledVector(u, -TH.c * 0.62 * z); const R = solve2(TH.a * z, TH.b * z, S, J2, o.pole || n); const hint = n.clone().negate();
  aim(h.thumb[0], new V3().subVectors(R.E, S), hint); aim(h.thumb[1], new V3().subVectors(R.T, R.E), hint); aim(h.thumb[2], u.clone().addScaledVector(n, -(o.press ?? 0.12)), hint); return { far: R.far }; }
/** the pad of finger f (0 index … 3 pinky): its point and outward normal, in the world */
export function fingerPad(p, s, f) { const h = p.arm[s].hand, j = h.fingers[f][2], r = tipR(f, h.k ?? 1); j.updateWorldMatrix(true, false); return { P: j.localToWorld(new V3(0, -FL[f][2] * 0.6, r)), n: new V3(0, 0, 1).transformDirection(j.matrixWorld), tip: j.localToWorld(new V3(0, -FL[f][2], 0)) }; }
const setFinger = (p, s, f, c, ratio, spread = 0) => { const segs = p.arm[s].hand.fingers[f], sg = p.arm[s].sg; segs[0].rotation.set(-c * ratio[0], 0, sg * spread * (f - 1.5) * 0.12); segs[1].rotation.set(-c * ratio[1], 0, 0); segs[2].rotation.set(-c * ratio[2], 0, 0); };
/** curl finger f until its pad lies on the plane (point P0, normal n): the pad comes from the −n side. → the curl used, and the gap left (m) */
export function fingerOnPlane(p, s, f, P0, n, o = {}) { const ratio = o.ratio || [1.0, 0.8, 0.4], c0 = o.c0 ?? -0.15, c1 = o.c1 ?? 1.5; const d = c => { setFinger(p, s, f, c, ratio, o.spread || 0); const q = fingerPad(p, s, f); return q.P.sub(P0).dot(n); };
  let best = c0, bd = 1e9, prev = null, lo = null, hi = null; const N = 16; for (let i = 0; i <= N; i++) { const c = c0 + (c1 - c0) * i / N, v = d(c); if (Math.abs(v) < bd) { bd = Math.abs(v); best = c; } if (prev && lo === null && Math.sign(v) !== Math.sign(prev[1]) && v !== 0) { lo = prev[0]; hi = c; } prev = [c, v]; }
  if (lo !== null) { let vl = d(lo); for (let i = 0; i < 12; i++) { const m = (lo + hi) / 2, vm = d(m); if (Math.sign(vm) === Math.sign(vl)) { lo = m; vl = vm; } else hi = m; } best = (lo + hi) / 2; bd = Math.abs(d(best)); } else d(best);
  return { c: best, gap: bd }; }

/**
 * HOLD A SHEET (a letter, an envelope, a card, a note, a photo): thumb on one face, finger pads on the other. The sheet is placed first
 * (its front is its +Z, it lies in its own XY); the hand then takes it — so the hand follows the thing and can never slide or float.
 *   at: [u, v]   where the thumb presses, in the sheet's own XY, metres from its centre (keep ~1.5 cm inside the edge)
 *   into: angle (rad, 0 = the sheet's +X, π/2 = its +Y) of the direction from the hand INTO the sheet (so the hand lies outside the opposite edge)
 *   flex: 0 … 1   0: fingers lie flat behind the sheet, the forearm runs in the sheet's plane.  1: a curled pinch, the forearm square to the sheet
 *   th: the sheet's thickness; open: 0 held … 1 let go (the thumb lifts, the fingers ease off); thumbBack: the thumb takes the back face
 *   rest: 0 … 1 in a pinch, how open the ring and little finger stay (0: folded away)
 *   pole: where the elbow points
 * → { ratio (shoulder→wrist over the arm's length: keep under 0.92), bend (rad between forearm and hand: keep under ~0.9), gap (m, worst pad off the face), far (thumb short) }
 */
export function pinchSheet(p, s, L, o = {}) { const A = p.arm[s], h = A.hand, sg = A.sg, z = p.size, flex = clamp(o.flex ?? 0, 0, 1), th = o.th ?? 0.002, open = clamp(o.open || 0, 0, 1);
  // 1. the hand's own shape for this hold, measured in the hand's frame
  const c = lerp(0.03, 0.95, flex), ratio = [lerp(0.9, 1.0, flex), lerp(0.5, 0.95, flex), lerp(0.25, 0.5, flex)]; setFinger(p, s, 0, c, ratio); setFinger(p, s, 1, c * 1.02, ratio); A.ha.updateWorldMatrix(true, true);
  const inv = new M4().copy(A.ha.matrixWorld).invert(); const toL = v => v.clone().applyMatrix4(inv), dirL = v => v.clone().transformDirection(inv);
  const i0 = fingerPad(p, s, 0), i1 = fingerPad(p, s, 1); const nH = dirL(i0.n).add(dirL(i1.n)).normalize(), PiH = toL(i0.P);
  const natT = new V3(sg * 0.02, -0.103, 0.02).lerp(PiH, sm(flex)); const CH = natT.clone().addScaledVector(nH, PiH.clone().sub(natT).dot(nH)); // where the thumb presses, on the fingers' plane
  const dH = CH.clone().sub(new V3(sg * 0.03, -0.068, 0.012)); dH.addScaledVector(nH, -dH.dot(nH)).normalize(); const eH = new V3().crossVectors(nH, dH);
  // 2. the sheet
  L.updateMatrixWorld(true); const q = L.getWorldQuaternion(new Q()); const X = new V3(1, 0, 0).applyQuaternion(q), Y = new V3(0, 1, 0).applyQuaternion(q), Z = new V3(0, 0, 1).applyQuaternion(q); const side = o.thumbBack ? -1 : 1;
  const at = o.at || [0, 0], a = o.into ?? 0; const C0 = L.getWorldPosition(new V3()).addScaledVector(X, at[0]).addScaledVector(Y, at[1]); const nO = Z.clone().multiplyScalar(side), dO = X.clone().multiplyScalar(Math.cos(a)).addScaledVector(Y, Math.sin(a)), eO = new V3().crossVectors(nO, dO);
  const back = C0.clone().addScaledVector(nO, -th / 2 - open * 0.004), front = C0.clone().addScaledVector(nO, th / 2);
  // 3. the hand's turn: its (d, e, n) onto the sheet's (d, e, n); the wrist where that puts it
  const R = new M4().makeBasis(dO, eO, nO).multiply(new M4().makeBasis(dH, eH, nH).transpose()); const fdir = new V3(0, -1, 0).applyMatrix4(R), pdir = new V3(0, 0, 1).applyMatrix4(R);
  const W = back.clone().sub(CH.clone().applyMatrix4(R).multiplyScalar(z)); const S = wp(A.up); p.reach(s, W, o.pole || new V3(0, -1, 0.2), fdir, pdir);
  A.ha.updateWorldMatrix(true, false); const Wn = wp(A.ha), miss = Wn.distanceTo(W); const E = wp(A.fo); const fo = Wn.clone().sub(E).normalize();
  // 4. pads onto the back face; ring and little finger lie on it too in a flat hold, fold away in a pinch
  let gap = miss; for (const f of [0, 1]) { const r = fingerOnPlane(p, s, f, back, nO, { ratio, c0: c - 0.35, c1: c + 0.45 }); gap = Math.max(gap, r.gap); }
  if (flex < 0.45) for (const f of [2, 3]) fingerOnPlane(p, s, f, back, nO, { ratio, c0: c - 0.35, c1: c + 0.6 }); else for (const f of [2, 3]) setFinger(p, s, f, lerp(lerp(c + 0.25, 1.25, sm(seg(flex, 0.45, 1))) + 0.06 * f, 0.62 + 0.1 * (f - 2), clamp(o.rest || 0, 0, 1)), [1.05, 1.15, 0.7]);   /* rest 0 … 1: how far the ring and little finger stay open in a pinch (0 folds them away: safest beside other things, but the hand reads as a fist) */
  // 5. the thumb onto the front face, pointing into the sheet
  const tr = thumbPad(p, s, front, nO, { lift: open * 0.022, dir: o.thumbDir || dO.clone().addScaledVector(eO, (o.thumbSkew ?? 0.25) * sg * side), press: 0.12 * (1 - open) });
  return { ratio: S.distanceTo(W) / (p.L.upper + p.L.fore), bend: Math.acos(clamp(fo.dot(fdir), -1, 1)), gap, far: tr.far, W, fdir, pdir }; }

/** the whole arm and hand, as it is now: for moving between poses */
export function snap(p, s) { const A = p.arm[s], h = A.hand; A.ha.updateWorldMatrix(true, false); const J = [...h.fingers.flat(), ...h.thumb];
  return { T: wp(A.ha), fd: new V3(0, -1, 0).transformDirection(A.ha.matrixWorld), pd: new V3(0, 0, 1).transformDirection(A.ha.matrixWorld), q: J.map(j => j.quaternion.clone()) }; }
/** move arm+hand from pose A to pose B (k 0…1, already eased by the caller or not): the wrist rides an arc through `via`, the hand turns on the way, fingers change shape on the way */
export function blendHand(p, s, A, B, k, o = {}) { const u = o.raw ? k : sm2(k); let T; if (o.via) { const a = A.T.clone().lerp(o.via, u), b = o.via.clone().lerp(B.T, u); T = a.lerp(b, u); } else T = A.T.clone().lerp(B.T, u); if (o.lift) T.y += Math.sin(u * PI) * o.lift;
  const fd = A.fd.clone().lerp(B.fd, u).normalize(), pd = A.pd.clone().lerp(B.pd, u); pd.addScaledVector(fd, -pd.dot(fd)).normalize(); p.reach(s, T, o.pole || new V3(0, -1, 0.3), fd, pd);
  const h = p.arm[s].hand, J = [...h.fingers.flat(), ...h.thumb], uf = o.fingers ? clamp(o.fingers(u), 0, 1) : u; J.forEach((j, i) => j.quaternion.copy(A.q[i]).slerp(B.q[i], uf)); }
/** a loose, living hand at rest: the fingers fall in a cascade (little finger most curled), each drifting a little */
export function looseHand(p, s, t = 0, o = {}) { const A = p.arm[s], b = o.curl ?? 0.3; const w = k => 0.035 * Math.sin(t * (0.5 + 0.13 * k) + k * 1.7 + A.sg); p.fingers(s, [b + w(0), b + 0.1 + w(1), b + 0.18 + w(2), b + 0.26 + w(3)], 0.4, [0.85, 0.9, 0.5]); p.thumbRest(s, 0.18 + 0.05 * Math.sin(t * 0.4 + A.sg)); }

// =================================================================================================== SPEECH
let QUIET = false; // while the camera's anchors are measured, heads do not talk
/** from a line's word timings [[word, start, end, …], …]: where the head stresses. Phrases are split at pauses; each phrase lifts a little before it starts, nods on its weightiest word and settles at its end. */
export function speechPlan(words, o = {}) { const gap = o.gap ?? 0.22, ph = []; let cur = []; (words || []).forEach((w, i) => { if (i && w[1] - words[i - 1][2] > gap) { ph.push(cur); cur = []; } cur.push(w); }); if (cur.length) ph.push(cur);
  const ev = []; ph.forEach((P, pi) => { const a = P[0][1], b = P[P.length - 1][2]; ev.push({ t: a - 0.16, w: 0.3, nod: -0.55, brow: 0.5 }); // a small lift with the breath, before the first word
    let hv = P[0], hd = 0; for (const w of P) { const d = (w[2] - w[1]) * (w === P[P.length - 1] ? 1.25 : 1) + String(w[0]).length * 0.004; if (d > hd) { hd = d; hv = w; } }
    ev.push({ t: hv[1] + (hv[2] - hv[1]) * 0.35, w: 0.36, nod: 1, brow: 1 }); if (P.length > 2 && hv !== P[0]) ev.push({ t: P[0][1] + (P[0][2] - P[0][1]) * 0.4, w: 0.3, nod: 0.45, brow: 0.3 });
    ev.push({ t: b + 0.12, w: 0.5, nod: 0.35, brow: -0.2, tilt: pi % 2 ? -1 : 1 }); });
  return { ev, a: ph.length ? ph[0][0][1] : 0, b: ph.length ? ph[ph.length - 1][ph[ph.length - 1].length - 1][2] : 0, phrases: ph.map(P => [P[0][1], P[P.length - 1][2]]) }; }
const bump = (T, t, w) => { const x = (T - t) / w; return Math.abs(x) >= 1 ? 0 : 0.5 + 0.5 * Math.cos(PI * x); };
/** what the head does at time T: nod (rad, + is down), tilt (rad), brow (0…1 lift), talking (0…1). amp: 1 = an ordinary speaker (about 3° on the main stress) */
export function speechHead(plan, T, amp = 1) { if (QUIET || !plan || !plan.ev.length) return { nod: 0, tilt: 0, brow: 0, talking: 0 }; let nod = 0, brow = 0, tilt = 0;
  for (const e of plan.ev) { const k = bump(T, e.t, e.w); if (!k) continue; nod += e.nod * k; brow += e.brow * k; if (e.tilt) tilt += e.tilt * k; }
  const talking = Math.max(...plan.phrases.map(([a, b]) => Math.min(sm(seg(T, a - 0.15, a + 0.05)), 1 - sm(seg(T, b, b + 0.3)))), 0);
  return { nod: nod * 0.05 * amp, tilt: tilt * 0.03 * amp, brow: clamp(brow, -0.3, 1) * 0.16 * amp, talking }; }
/** add the speech movement to a head that has just been aimed with look() (call before the face is applied) */
export function nodHead(p, sp) { p.neck.rotation.x += sp.nod * 0.35; p.head.rotation.x += sp.nod * 0.65; p.head.rotation.z += sp.tilt; }

// =================================================================================================== CAMERA
/**
 * A STEADY CAMERA. A lens must never aim at a live head: heads move with speech and the whole picture shakes.
 * steady(shot, times, sample) measures the rest positions once per build, at fixed moments (so any frame, drawn in any order, gets the same
 * answer), with speech movement off. In frame(): anchorAt(c, name, t) gives the eased position between those moments.
 *   sample(c) → { name: V3, … } is called after the shot has posed its people at each of `times`.
 */
export function steady(shot, times, sample) { const f0 = shot.frame; shot.frame = function (t, c, E) { if (!c._anc) { c._anc = { times, v: [] }; QUIET = true; try { for (const ti of times) { f0.call(this, ti, c, E); const sv = sample(c); const o = {}; for (const k in sv) o[k] = sv[k].clone(); c._anc.v.push(o); } } finally { QUIET = false; } } return f0.call(this, t, c, E); }; return shot; }
export function anchorAt(c, name, t) { const A = c._anc; if (!A) return new V3(); const ts = A.times; if (A.v.length < ts.length) return (A.v[A.v.length - 1] || {})[name]?.clone() || new V3(); if (t <= ts[0]) return A.v[0][name].clone(); for (let i = 0; i < ts.length - 1; i++) if (t <= ts[i + 1]) return A.v[i][name].clone().lerp(A.v[i + 1][name], sm2((t - ts[i]) / (ts[i + 1] - ts[i]))); return A.v[ts.length - 1][name].clone(); }
export const quiet = () => QUIET;
/** a hand-held breath for a locked-off camera: millimetres, slow (well under the 3 px limit of review/camshake) */
export const drift = (T, a = 1) => [Math.sin(T * 0.53) * 0.0022 * a + Math.sin(T * 0.21 + 1) * 0.0015 * a, Math.sin(T * 0.47 + 1) * 0.0018 * a, Math.sin(T * 0.39 + 2) * 0.002 * a];

// =================================================================================================== FACE
const hash = n => { const x = Math.sin(n * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); };
/** blinks at chosen moments (changes of thought, the end of a look): each [t] or [t, length]; a long one is a held, emotional blink */
export const blinks = (T, list) => { let b = 0; for (const e of list) { const t = Array.isArray(e) ? e[0] : e, d = Array.isArray(e) ? e[1] : 0.2; const x = (T - t) / d; if (x > 0 && x < 1) b = Math.max(b, x < 0.3 ? sm(x / 0.3) : x > 0.62 ? 1 - sm((x - 0.62) / 0.38) : 1); } return b; };
/** eyes that search: small quick jumps of the gaze (metres at the target), a new place every 0.5–1.1 s */
export function saccade(T, seed = 1, amp = 0.012) { let t0 = 0, i = 0; const dur = n => 0.5 + 0.6 * hash(seed * 7.3 + n); while (t0 + dur(i) < T && i < 400) { t0 += dur(i); i++; } const P = n => new V3((hash(seed + n * 3.1) - 0.5) * 2 * amp, (hash(seed + n * 5.7) - 0.5) * amp, 0); const k = sm(seg(T, t0, t0 + 0.07)); return P(i - 1).lerp(P(i), k); }
/** a lip and chin that tremble (k 0…1): add to the face's press and jaw */
export const tremble = (T, k) => ({ press: k * (0.1 + 0.07 * Math.sin(T * 21.0) + 0.05 * Math.sin(T * 33.7 + 1)), jaw: k * 0.025 * (0.5 + 0.5 * Math.sin(T * 17.3 + 2)) });
/** a breath that catches: chest lift (rad) for a sharp in-breath at t0 and a slow, shaky release */
export const breathCatch = (T, t0) => { const x = T - t0; if (x < 0 || x > 2.2) return 0; return x < 0.35 ? -0.035 * sm(x / 0.35) : -0.035 * (1 - sm((x - 0.35) / 1.85)) + 0.004 * Math.sin(x * 19) * (1 - x / 2.2); };
