// CodeScape person rig: a jointed, stylised human built from smooth parts, posed every frame by code.
// Legs stand and step by two-bone IK on planted feet; arms reach by IK with an elbow pole; hands have
// four two-jointed fingers and a thumb; the face has eyes that look, lids that blink and squint,
// brows that lift and knit, a jaw that opens with teeth and tongue inside, cheeks that rise, a moustache that moves.
import { THREE, V3, clamp, lerp, sm } from './engine.js';
const Q = THREE.Quaternion, M4 = THREE.Matrix4;
const _q = new Q(), _q2 = new Q(), _m = new M4(), _v = new V3(), _w = new V3(), _u = new V3();
const UP = new V3(0, 1, 0);

function mat(c, o = {}) { return new THREE.MeshStandardMaterial({ color: c, roughness: o.r ?? 0.62, metalness: o.m ?? 0, emissive: o.e || 0x000000, emissiveIntensity: o.ei ?? 1, ...(o.x || {}) }); }
function fabric(c, o = {}) { return new THREE.MeshPhysicalMaterial({ color: c, roughness: o.r ?? 0.85, sheen: 0.6, sheenRoughness: 0.6, sheenColor: new THREE.Color(o.sheen || '#ffffff') }); }
function capsule(r, len, m, seg = 16) { const g = new THREE.CapsuleGeometry(r, Math.max(0.0001, len), 8, seg); g.translate(0, -len / 2, 0); const me = new THREE.Mesh(g, m); me.castShadow = true; me.receiveShadow = true; return me; }
// a limb that tapers from r0 at the joint to r1 at the far end, along local -Y
function taper(r0, r1, len, m, seg = 18) { const pts = []; const n = 14;
  for (let i = 0; i <= n; i++) { const a = -Math.PI / 2 + Math.PI / 2 * i / n; pts.push(new THREE.Vector2(r1 * Math.cos(a), -len + r1 * Math.sin(a))); }
  for (let i = 0; i <= n; i++) { const a = Math.PI / 2 * i / n; pts.push(new THREE.Vector2(r0 * Math.cos(a), r0 * Math.sin(a))); }
  const g = new THREE.LatheGeometry(pts, seg); const me = new THREE.Mesh(g, m); me.castShadow = true; me.receiveShadow = true; return me; }
function ball(r, m, sx = 1, sy = 1, sz = 1, seg = 24) { const me = new THREE.Mesh(new THREE.SphereGeometry(r, seg, Math.round(seg * 0.7)), m); me.scale.set(sx, sy, sz); me.castShadow = true; me.receiveShadow = true; return me; }
const G = (parent, x = 0, y = 0, z = 0) => { const g = new THREE.Group(); g.position.set(x, y, z); if (parent) parent.add(g); return g; };

// set a joint so its local -Y points along dir (world) and its local +Z leans toward hint (world)
export function aim(j, dir, hint) {
  const y = _v.copy(dir).normalize().negate(); const z = _w.copy(hint).addScaledVector(y, -hint.dot(y)); if (z.lengthSq() < 1e-8) z.set(0, 0, 1).addScaledVector(y, -y.z); z.normalize();
  const x = _u.crossVectors(y, z); _m.makeBasis(x, y, z); _q.setFromRotationMatrix(_m);
  j.parent.updateWorldMatrix(true, false); j.parent.getWorldQuaternion(_q2); j.quaternion.copy(_q2.invert().multiply(_q)); j.updateWorldMatrix(false, false); }
const wp = (o, out = new V3()) => { o.updateWorldMatrix(true, false); return out.setFromMatrixPosition(o.matrixWorld); };
// two-bone IK: returns the elbow/knee position
function solve2(a, b, S, T, pole) { const d0 = new V3().subVectors(T, S); let d = d0.length(); const dn = d0.clone().divideScalar(d || 1); d = clamp(d, Math.abs(a - b) + 1e-3, (a + b) * 0.9995);
  const x = (a * a - b * b + d * d) / (2 * d), h = Math.sqrt(Math.max(0, a * a - x * x)); const p = pole.clone().addScaledVector(dn, -pole.dot(dn)); if (p.lengthSq() < 1e-8) p.set(0, 0, 1); p.normalize();
  return { E: S.clone().addScaledVector(dn, x).addScaledVector(p, h), T: S.clone().addScaledVector(dn, d), dn, p }; }


// ------------------------------------------------------------------ sculpted head, lips and hair (one smooth surface each: no stacked balls)
const _g = (dx, dy, dz, sx, sy, sz) => Math.exp(-(dx * dx / (sx * sx) + dy * dy / (sy * sy) + dz * dz / (sz * sz)));
function sculptHead(skin, o = {}) { const g = new THREE.SphereGeometry(1, 128, 96); const p = g.attributes.position, n = p.count; const ms = new Float32Array(n * 3), pf = new Float32Array(n * 3), jw = new Float32Array(n * 3);
  const jawW = o.jaw ?? 0.3, nose = o.nose ?? 1, cheek = o.cheek ?? 1, chin = o.chin ?? 1;
  for (let i = 0; i < n; i++) { const dx = p.getX(i), dy = p.getY(i), dz = p.getZ(i); const low = Math.max(0, -dy);
    let x = dx * 0.148 * (1 - jawW * low * low), y = 0.08 + dy * 0.156 * (1 + 0.04 * low), z = dz * 0.146 * (1 - 0.16 * low * low) * (dz > 0 ? 1 - 0.05 * (1 - Math.abs(dy)) : 1.03);
    let off = 0; const A = (cx, cy, cz, sx, sy, sz, a) => { off += _g(x - cx, y - cy, z - cz, sx, sy, sz) * a; };
    for (const s of [-1, 1]) { A(s * 0.094, 0.052, 0.085, 0.04, 0.035, 0.05, 0.006); A(s * 0.055, 0.154, 0.115, 0.035, 0.014, 0.04, 0.004); A(s * 0.078, 0.015, 0.09, 0.05, 0.045, 0.05, 0.005 * cheek); A(s * 0.019, 0.058, 0.13, 0.011, 0.01, 0.02, 0.007 * nose); }
    A(0, -0.002, 0.12, 0.05, 0.035, 0.05, 0.006); A(0, -0.06, 0.1, 0.034, 0.022, 0.04, 0.007 * chin); A(0, 0.108, 0.135, 0.013, 0.04, 0.03, 0.011 * nose); A(0, 0.07, 0.14, 0.019, 0.018, 0.03, 0.027 * nose);
    x += dx * off; y += dy * off; z += dz * off; p.setXYZ(i, x, y, z);
    for (const s of [-1, 1]) { const w = _g(x - s * 0.085, y - 0.04, z - 0.105, 0.04, 0.035, 0.05); ms[i * 3] += s * 0.003 * w; ms[i * 3 + 1] += 0.008 * w; ms[i * 3 + 2] += 0.004 * w; const w2 = _g(x - s * 0.088, y - 0.018, z - 0.1, 0.045, 0.04, 0.05) * 0.02; pf[i * 3] += dx * w2; pf[i * 3 + 1] += dy * w2; pf[i * 3 + 2] += dz * w2; }
    const wj = sm(clamp((0.0 - y) / 0.05, 0, 1)) * clamp(dz * 1.5 + 0.5, 0, 1); jw[i * 3 + 1] = -0.022 * wj; jw[i * 3 + 2] = -0.004 * wj; }
  g.computeVertexNormals(); g.morphAttributes.position = [ms, pf, jw].map(a => new THREE.Float32BufferAttribute(a, 3)); g.morphTargetsRelative = true;
  const m = new THREE.Mesh(g, skin); m.castShadow = m.receiveShadow = true; m.morphTargetInfluences = [0, 0, 0]; return m; }
function lipMesh(matL, lower) { const w = 0.043; const P = lower ? [[-w, 0, -0.013], [-w * 0.5, -0.008, 0.0], [0, -0.01, 0.0045], [w * 0.5, -0.008, 0.0], [w, 0, -0.013]] : [[-w, 0, -0.013], [-w * 0.5, 0.0045, 0.001], [0, 0.0036, 0.006], [w * 0.5, 0.0045, 0.001], [w, 0, -0.013]];
  const cv = new THREE.CatmullRomCurve3(P.map(a => new V3(...a))); const seg = 36, rad = 10; const g = new THREE.TubeGeometry(cv, seg, lower ? 0.0082 : 0.0064, rad); const p = g.attributes.position, n = p.count; const M = [0, 1, 2, 3].map(() => new Float32Array(n * 3));
  for (let i = 0; i < n; i++) { const u = Math.floor(i / (rad + 1)) / seg, c = cv.getPoint(u), k = 0.22 + 0.78 * Math.pow(Math.sin(u * Math.PI), 0.75); const x = c.x + (p.getX(i) - c.x) * k, y = c.y + (p.getY(i) - c.y) * k * 0.85, z = c.z + (p.getZ(i) - c.z) * k; p.setXYZ(i, x, y, z);
    const e = Math.pow(Math.abs(2 * u - 1), 1.6), sg = u < 0.5 ? -1 : 1; M[0].set([sg * 0.008 * e, 0.017 * e, -0.005 * e], i * 3); M[1].set([-sg * 0.003 * e, -0.012 * e, 0], i * 3); M[2].set([-x * 0.4, (lower ? -0.003 : 0.003) * (1 - e), 0.006 * (1 - e)], i * 3); M[3].set([x * 0.16, 0, -0.002 * e], i * 3); }
  g.computeVertexNormals(); g.morphAttributes.position = M.map(a => new THREE.Float32BufferAttribute(a, 3)); g.morphTargetsRelative = true; const m = new THREE.Mesh(g, matL); m.morphTargetInfluences = [0, 0, 0, 0]; m.userData.noShadow = 1; return m; }
const lineOf = (a, pts) => { for (let i = 0; i < pts.length - 1; i++) if (a <= pts[i + 1][0]) { const u = (a - pts[i][0]) / (pts[i + 1][0] - pts[i][0]); return lerp(pts[i][1], pts[i + 1][1], sm(u)); } return pts[pts.length - 1][1]; };
/** hair as one surface over the skull: o.line(a, phi) is the hairline height at angle a (degrees from the front), o.topLine an optional upper edge (bald crown) */
function hairMesh(matH, o) { const g = new THREE.SphereGeometry(1, 128, 88); const p = g.attributes.position; const hl = new Float32Array(p.count);
  for (let i = 0; i < p.count; i++) { const dx = p.getX(i), dy = p.getY(i), dz = p.getZ(i); const phi = Math.atan2(dx, dz), a = Math.abs(phi) * 180 / Math.PI;
    let th = (o.th ?? 0.014) + (o.top ?? 0.008) * Math.max(0, dy) ** 2 + (o.groove ?? 0.0025) * Math.sin(phi * (o.gf ?? 16) + dy * (o.gs ?? 6));
    if (o.quiff) th += o.quiff * _g(dx - (o.qx ?? 0), dy - 0.72, dz - 0.62, 0.55, 0.4, 0.45); if (o.part) th -= 0.007 * Math.exp(-((dx / 0.03) ** 2)) * (dz > -0.3 ? 1 : 0) * Math.max(0, dy);
    const y0 = 0.08 + dy * (0.156 + th); let h = y0 - o.line(a, phi); if (o.topLine) h = Math.min(h, o.topLine(a, phi) - y0);
    // the hair thins toward its edge, and the edge itself is cut in the shader (a clean line, no saw-tooth)
    th *= lerp(0.42, 1, sm(clamp(h / 0.035, 0, 1))); hl[i] = h;
    p.setXYZ(i, dx * (0.148 + th), 0.08 + dy * (0.156 + th), dz * (0.146 * (dz > 0 ? 0.97 : 1.03) + th)); }
  g.setAttribute('hl', new THREE.BufferAttribute(hl, 1)); g.computeVertexNormals(); const mm = matH.clone(); mm.side = THREE.DoubleSide;
  mm.onBeforeCompile = sh => { sh.vertexShader = 'attribute float hl; varying float vHl;\n' + sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\n vHl = hl;'); sh.fragmentShader = 'varying float vHl;\n' + sh.fragmentShader.replace('#include <clipping_planes_fragment>', 'if (vHl < 0.0) discard;\n#include <clipping_planes_fragment>'); };
  const m = new THREE.Mesh(g, mm); m.castShadow = false; return m; }
function longHair(matH) { const g = new THREE.PlaneGeometry(1, 1, 56, 26); const p = g.attributes.position; for (let i = 0; i < p.count; i++) { const u = p.getX(i) + 0.5, v = 0.5 - p.getY(i); const phi = lerp(1.25, 2 * Math.PI - 1.25, u);
      const r = lerp(0.164, 0.15, sm(v / 0.4)) + 0.03 * sm((v - 0.45) / 0.55) + 0.004 * Math.sin(phi * 24) + 0.006 * Math.sin(phi * 7 + 1); const y = lerp(0.16, -0.25 + 0.02 * Math.sin(phi * 9), v);
      p.setXYZ(i, Math.sin(phi) * r * lerp(1, 0.92, v), y, Math.cos(phi) * r * 1.02 - 0.012 * v); } g.computeVertexNormals(); const m = new THREE.Mesh(g, matH); m.material.side = THREE.DoubleSide; m.castShadow = true; return m; }
const hairMat = c => new THREE.MeshPhysicalMaterial({ color: c, roughness: 0.5, sheen: 0.8, sheenRoughness: 0.4, sheenColor: new THREE.Color(c).lerp(new THREE.Color('#ffffff'), 0.35), clearcoat: 0.15, clearcoatRoughness: 0.5 });
const HORSESHOE = { line: a => a < 66 ? 9 : lineOf(a, [[66, 0.15], [80, 0.135], [98, 0.115], [128, 0.05], [180, 0.012]]), topLine: a => lineOf(a, [[0, 0.19], [120, 0.19], [180, 0.212]]) };

export class Person {
  constructor(o = {}) {
    const C = this.C = Object.assign({ skin: '#d49a72', shirt: '#cfa765', shirt2: '#b48a4c', pants: '#2b3558', shoe: '#3a2418', hair: '#e8e6ea', cap: '#23305a', capBand: '#d4a73c', eye: '#3a2210', bag: '#8a4a26', h: 1 }, o);
    const sk = this.mSkin = new THREE.MeshPhysicalMaterial({ color: C.skin, roughness: 0.6, sheen: 0.15, sheenColor: new THREE.Color('#ffb0a0'), clearcoat: 0.04 });
    const sh = fabric(C.shirt, { sheen: '#fff0c8' }), sh2 = fabric(C.shirt2), pa = fabric(C.pants, { sheen: '#9ab0ff' }), shoe = mat(C.shoe, { r: 0.35, x: {} });
    this.L = { thigh: 0.37, shin: 0.36, ankle: 0.075, upper: 0.27, fore: 0.245, hipW: 0.095, shW: 0.205 };
    if (o.armK) { this.L.upper *= o.armK; this.L.fore *= o.armK; }
    const L = this.L;
    const root = this.root = new THREE.Group();
    const pelvis = this.pelvis = G(root, 0, 0.79, 0);
    // pelvis + belt + seat
    const hips = ball(0.155, pa, 1.05, 0.72, 0.85); hips.position.y = 0.0; pelvis.add(hips);
    const belt = new THREE.Mesh(new THREE.TorusGeometry(0.152, 0.018, 8, 40), mat('#2a1a10', { r: 0.4 })); belt.rotation.x = Math.PI / 2; belt.scale.set(1.08, 0.9, 1); belt.position.y = 0.07; pelvis.add(belt);
    const buckle = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.035, 0.012), mat('#e0b84a', { r: 0.25, m: 0.8 })); buckle.position.set(0, 0.07, 0.137); pelvis.add(buckle);
    // spine: belly (spine1) and chest (spine2)
    const sp1 = this.sp1 = G(pelvis, 0, 0.08, 0); const belly = this.belly = ball(0.17, sh, 1.08, 1.0, 1.0); belly.position.set(0, 0.1, 0.015); sp1.add(belly); this.buttons = [];
    for (let i = 0; i < 3; i++) { const b = ball(0.009, mat('#efe6d0', { r: 0.3 }), 1, 1, 0.5, 10); b.position.set(0, 0.06 + i * 0.07, 0.185 - Math.abs(i - 1) * 0.008); sp1.add(b); this.buttons.push(b); }
    const sp2 = this.sp2 = G(sp1, 0, 0.19, 0); const chest = this.chest = ball(0.165, sh, 1.18, 0.86, 0.86); chest.position.set(0, 0.08, 0); sp2.add(chest);
    const yoke = ball(0.12, sh2, 1.5, 0.45, 0.85); yoke.position.set(0, 0.165, -0.01); sp2.add(yoke);
    for (const s of [-1, 1]) { const pk = new THREE.Mesh(new THREE.BoxGeometry(0.075, 0.07, 0.015), sh2); pk.position.set(s * 0.085, 0.09, 0.14); pk.rotation.x = -0.15; sp2.add(pk); (this.pockets = this.pockets || []).push(pk); const fl = new THREE.Mesh(new THREE.BoxGeometry(0.08, 0.025, 0.02), sh2); fl.position.set(s * 0.085, 0.128, 0.145); sp2.add(fl); this.pockets.push(fl); }
    // bag strap from the left shoulder across the chest, and the satchel at the right hip
    const strap = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3([new V3(0.15, 0.2, -0.05), new V3(0.08, 0.17, 0.15), new V3(-0.08, 0.02, 0.165), new V3(-0.19, -0.13, 0.1), new V3(-0.21, -0.22, 0.0)]), 30, 0.014, 6), mat(C.bag, { r: 0.5 }));
    strap.scale.set(1, 1, 1); strap.position.y = 0; sp2.add(strap); strap.castShadow = true; this.strap = strap;
    const bag = this.bag = new THREE.Mesh(new THREE.BoxGeometry(0.2, 0.17, 0.07), mat(C.bag, { r: 0.55 })); bag.position.set(-0.22, -0.02, 0.02); bag.castShadow = true; pelvis.add(bag);
    const flap = new THREE.Mesh(new THREE.BoxGeometry(0.205, 0.09, 0.075), mat('#a05a30', { r: 0.5 })); flap.position.set(-0.22, 0.03, 0.025); pelvis.add(flap); this.flap = flap; this.belt = [belt, buckle];
    // neck, collar, head
    const neck = this.neck = G(sp2, 0, 0.2, 0.0); const nk = capsule(0.055, 0.04, sk); nk.position.y = 0.06; neck.add(nk);
    const collar = new THREE.Mesh(new THREE.TorusGeometry(0.068, 0.02, 8, 24), sh2); collar.rotation.x = Math.PI / 2 - 0.25; collar.position.set(0, 0.0, 0.0); neck.add(collar);
    const head = this.head = G(neck, 0, 0.1, 0.012); this.buildHead(head);
    // arms
    this.arm = {}; for (const s of ['l', 'r']) { const sg = s === 'l' ? 1 : -1;
      const sho = G(sp2, sg * L.shW, 0.15, -0.01); const shBall = ball(0.07, sh, 1, 1, 1); sho.add(shBall);
      const SL = C.sleeveL ?? 0.55; const up = G(sho); const ua = taper(0.068, SL > 0.9 ? 0.052 : 0.058, L.upper * SL, sh); up.add(ua); const cuff = new THREE.Mesh(new THREE.TorusGeometry(SL > 0.9 ? 0.05 : 0.058, SL > 0.9 ? 0.017 : 0.012, 10, 24), SL > 0.9 ? sh : sh2); cuff.rotation.x = Math.PI / 2; cuff.position.y = -L.upper * SL; up.add(cuff); this.cuffs = (this.cuffs || []).concat([cuff]);
      const ua2 = taper(0.048, 0.044, L.upper, sk); up.add(ua2);
      const fo = G(up, 0, -L.upper, 0); const el = ball(0.045, sk); fo.add(el); const fa = taper(0.046, 0.036, L.fore, sk); fo.add(fa);
      const wa = new THREE.Mesh(new THREE.TorusGeometry(0.038, 0.008, 6, 18), mat(s === 'l' ? '#1c1c1c' : C.skin, { r: 0.3 })); wa.rotation.x = Math.PI / 2; wa.position.y = -L.fore + 0.03; if (s === 'l') fo.add(wa);
      const ha = G(fo, 0, -L.fore, 0); const hand = this.buildHand(ha, sg);
      this.arm[s] = { sho, up, fo, ha, hand, sg }; }
    // legs
    this.leg = {}; for (const s of ['l', 'r']) { const sg = s === 'l' ? 1 : -1;
      const hip = G(pelvis, sg * L.hipW, -0.02, 0); const th = G(hip); th.add(taper(0.088, 0.068, L.thigh, pa));
      const sn = G(th, 0, -L.thigh, 0); sn.add(ball(0.066, pa)); sn.add(taper(0.066, 0.052, L.shin, pa));
      const hem = new THREE.Mesh(new THREE.TorusGeometry(0.054, 0.01, 6, 18), pa); hem.rotation.x = Math.PI / 2; hem.position.y = -L.shin + 0.02; sn.add(hem);
      const an = G(sn, 0, -L.shin, 0); const ft = G(an); // foot: local -Y down, +Z forward
      const sole = ball(0.06, shoe, 0.95, 0.55, 1.9); sole.position.set(0, -L.ankle + 0.03, 0.06); ft.add(sole); const heel = ball(0.052, shoe, 1, 0.75, 1); heel.position.set(0, -L.ankle + 0.035, -0.02); ft.add(heel);
      const solePl = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.06, 0.014, 20), mat('#1a120c', { r: 0.8 })); solePl.scale.set(0.95, 1, 2.05); solePl.position.set(0, -L.ankle + 0.007, 0.05); ft.add(solePl);
      this.leg[s] = { hip, th, sn, an, ft, sg }; }
    root.traverse(o => { if (o.isMesh && !o.userData.noShadow) { o.castShadow = true; o.receiveShadow = true; } });
    this.face = { blink: 0, lidL: 0.12, lidR: 0.12, squint: 0, browL: 0, browR: 0, knit: 0, sad: 0, jaw: 0, wide: 0, round: 0, smile: 0, puff: 0, gaze: null };
    this.gripState = { l: [0.25, 0.25, 0.25, 0.25, 0.2], r: [0.25, 0.25, 0.25, 0.25, 0.2] };
    if (C.size) { this.root.scale.setScalar(C.size); for (const k in this.L) this.L[k] *= C.size; } this.size = C.size || 1;
    if (C.headS) this.head.scale.set(...C.headS); if (C.eyeS) for (const e of this.eyes) e.piv.scale.setScalar(C.eyeS);
  }

  buildHead(head) { const C = this.C, sk = this.mSkin;
    const hm0 = this.headM = sculptHead(sk, { jaw: C.jaw ?? 0.26, nose: C.nose || 1, cheek: C.cheek ?? 1.3, chin: C.chin ?? 1 }); head.add(hm0); this.skull = hm0;
    const jawG = this.jawG = G(head, 0, 0.055, 0.02); // the jaw hinges just under the ears: the lower lip rides it
    // mouth: two soft lips; behind them a dark cavity with teeth and a tongue that shows when the jaw opens
    const mo = this.mouth = G(head, 0, -0.012, 0.114); const cav = this.cav = ball(0.037, mat('#3a0e14', { r: 0.9 }), 1.0, 0.12, 0.5, 24); cav.position.z = -0.012; mo.add(cav);
    const teeth = this.teeth = ball(0.026, mat('#fbf6ee', { r: 0.35 }), 1.05, 0.22, 0.4, 20); teeth.position.set(0, 0.004, -0.008); mo.add(teeth);
    const tongue = this.tongue = ball(0.02, mat('#c8505a', { r: 0.6 }), 1.2, 0.35, 0.6, 18); tongue.position.set(0, -0.012, -0.02); mo.add(tongue);
    const lm = this.mLip = new THREE.MeshPhysicalMaterial({ color: C.lipc || new THREE.Color(C.skin).lerp(new THREE.Color('#a8483e'), 0.45), roughness: 0.42, sheen: 0.3 });
    this.lipU = lipMesh(lm, false); mo.add(this.lipU); this.lipL = lipMesh(lm, true); this.lipL.position.set(0, -0.067, 0.094); jawG.add(this.lipL); this.lipU.visible = false;
    this.lip = new THREE.Object3D(); this.cheeks = [];
    for (const s of [-1, 1]) { const e = ball(0.034, sk, 0.42, 1.0, 0.72, 28); e.position.set(s * 0.146, 0.088, -0.004); e.rotation.y = s * 0.25; head.add(e); const ei = ball(0.018, mat(new THREE.Color(C.skin).multiplyScalar(0.8), { r: 0.7 }), 0.3, 0.9, 0.7, 16); ei.position.set(s * 0.158, 0.09, 0.004); head.add(ei); }
    // eyes: whites, iris, pupil, highlight; lids above and below
    const rc = new THREE.Raycaster(); hm0.updateMatrix(); hm0.matrixWorld.copy(hm0.matrix); const skinZ = (x, y) => { rc.set(new V3(x, y, 0.6), new V3(0, 0, -1)); const h = []; hm0.raycast(rc, h); h.sort((a, b) => a.distance - b.distance); return h.length ? h[0].point.z : 0.128; };
    this.eyes = []; for (const s of [-1, 1]) { const piv = G(head, s * 0.052, 0.112, 0.105); const white = ball(0.032, mat('#fbfaf6', { r: 0.25 }), 1, 1, 0.9, 28); piv.add(white);
      const ball2 = G(piv); const ir = ball(0.0195, mat(C.eye, { r: 0.3 }), 1, 1, 0.3); ir.position.z = 0.0248; ball2.add(ir); const pu = ball(0.0105, mat('#050302', { r: 0.2 }), 1, 1, 0.32); pu.position.z = 0.0288; ball2.add(pu);
      const hl = ball(0.0052, mat('#ffffff', { e: '#ffffff', ei: 1.4 }), 1, 1, 0.4); hl.position.set(0.007, 0.008, 0.0308); ball2.add(hl);
      const lidU = G(piv); const lu = new THREE.Mesh(new THREE.SphereGeometry(0.0352, 28, 14, 0, Math.PI * 2, 0, Math.PI / 2), sk); lu.rotation.x = Math.PI / 2; lu.scale.set(1, 1, 1); lidU.add(lu);
      const lash = new THREE.Mesh(new THREE.TorusGeometry(0.0352, 0.0024, 6, 28, Math.PI), mat('#2a1a14')); lash.rotation.z = Math.PI; lash.rotation.y = 0; lidU.add(lash); lash.rotation.x = 0;
      const lidD = G(piv); const ld = new THREE.Mesh(new THREE.SphereGeometry(0.034, 28, 14, 0, Math.PI * 2, 0, Math.PI / 2), sk); ld.rotation.x = -Math.PI / 2; lidD.add(ld);
      const brow = G(head, s * 0.056, 0.152, 0.135); const bm = new THREE.Mesh(new THREE.CapsuleGeometry(C.browR || 0.0085, 0.044, 6, 10).rotateZ(Math.PI / 2), fabric(C.hair, { r: 0.95 })); brow.add(bm); bm.rotation.y = -s * 0.25;
      const cp = []; for (let k = 0; k <= 12; k++) { const th = lerp(3.52, 5.9, k / 12), x = s * 0.052 + 0.0255 * Math.cos(th), y = 0.118 + 0.022 * Math.sin(th); cp.push(new V3(x, y, skinZ(x, y) + 0.0012)); }
      const cl = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(cp), 24, 0.0029, 6), mat('#2a1a14')); cl.visible = false; head.add(cl);
      this.eyes.push({ piv, ball: ball2, lidU, lidD, brow, s, cl, lash }); }
    // glasses
    const gl = this.glasses = G(head); const gm = mat('#2a1c16', { r: 0.3, m: 0.5 }); for (const s of [-1, 1]) { const rim = new THREE.Mesh(new THREE.TorusGeometry(0.039, 0.0045, 8, 36), gm); rim.position.set(s * 0.054, 0.112, 0.15); gl.add(rim);
      const lens = new THREE.Mesh(new THREE.CircleGeometry(0.038, 32), new THREE.MeshPhysicalMaterial({ color: '#ffffff', roughness: 0.05, transmission: 0.0, transparent: true, opacity: 0.12, metalness: 0.1 })); lens.position.set(s * 0.054, 0.112, 0.151); gl.add(lens);
      const arm = new THREE.Mesh(new THREE.CylinderGeometry(0.003, 0.003, 0.16, 6).rotateX(Math.PI / 2), gm); arm.position.set(s * 0.093, 0.118, 0.075); arm.rotation.y = s * 0.12; gl.add(arm); }
    const br = new THREE.Mesh(new THREE.TorusGeometry(0.012, 0.0035, 6, 12, Math.PI), gm); br.position.set(0, 0.112, 0.156); gl.add(br);
    // moustache: a big white brush that rides the upper lip
    const ms = this.must = G(head, 0, 0.032, 0.142); const cv = new THREE.CatmullRomCurve3([new V3(-0.075, -0.03, -0.03), new V3(-0.045, -0.002, 0.0), new V3(0, 0.008, 0.016), new V3(0.045, -0.002, 0.0), new V3(0.075, -0.03, -0.03)]);
    const mg = new THREE.TubeGeometry(cv, 40, 0.02, 12); const mp = mg.attributes.position; for (let i = 0; i < mp.count; i++) { const u = Math.floor(i / 13) / 40, k = 0.3 + 0.7 * Math.pow(Math.sin(u * Math.PI), 0.7), c = cv.getPoint(u); mp.setXYZ(i, c.x + (mp.getX(i) - c.x) * k, c.y + (mp.getY(i) - c.y) * k * 0.85, c.z + (mp.getZ(i) - c.z) * k * 0.9); } mg.computeVertexNormals();
    ms.add(new THREE.Mesh(mg, this.mMust = fabric(C.must || C.hair, { r: 0.95, sheen: '#ffffff' })));
    this.arc = new THREE.Object3D();
    // grey hair round the sides and back, under the cap
    const hg = this.hairG = G(head); hg.add(hairMesh(hairMat(C.hair), { ...HORSESHOE, th: 0.02, groove: 0.004, gf: 13 }));
    // the cap
    const cap = G(head, 0, 0.215, -0.01); cap.rotation.x = -0.12; const cm = fabric(C.cap, { sheen: '#8090ff', r: 0.7 });
    const crown = new THREE.Mesh(new THREE.CylinderGeometry(0.165, 0.152, 0.1, 40), cm); crown.position.y = 0.025; cap.add(crown); const top = ball(0.165, cm, 1, 0.18, 1.06, 40); top.position.set(0, 0.075, 0.008); cap.add(top);
    const band = new THREE.Mesh(new THREE.CylinderGeometry(0.154, 0.154, 0.028, 40, 1, true), mat(C.capBand, { r: 0.35, m: 0.6 })); band.position.y = -0.01; cap.add(band);
    const vis = new THREE.Mesh(new THREE.CylinderGeometry(0.15, 0.15, 0.012, 32, 1, false, -1.0, 2.0), mat('#141a30', { r: 0.25, x: {} })); vis.scale.z = 0.95; vis.position.set(0, -0.025, 0.045); vis.rotation.x = 0.28; cap.add(vis);
    const badge = new THREE.Mesh(new THREE.CylinderGeometry(0.024, 0.024, 0.008, 20).rotateX(Math.PI / 2), mat('#f0c040', { r: 0.2, m: 0.9, e: '#3a2600' })); badge.position.set(0, 0.03, 0.163); cap.add(badge);
    this.cap = cap;
  }

  buildHand(ha, sg) { const sk = this.mSkin; const hand = { fingers: [], thumb: null };
    const palm = ball(0.048, sk, 1.0, 1.05, 0.52); palm.position.set(0, -0.045, 0); ha.add(palm); const wr = ball(0.036, sk, 1, 1, 0.75); ha.add(wr);
    const X = [0.033, 0.011, -0.011, -0.031], LEN = [[0.032, 0.026, 0.02], [0.035, 0.028, 0.021], [0.033, 0.026, 0.02], [0.026, 0.021, 0.017]], R = [0.0115, 0.012, 0.0115, 0.0102];
    for (let f = 0; f < 4; f++) { let par = G(ha, sg * X[f], -0.088, 0.002); const segs = [];
      for (let k = 0; k < 3; k++) { const j = k === 0 ? par : G(par, 0, -LEN[f][k - 1], 0); j.add(capsule(R[f] * (1 - k * 0.08), LEN[f][k] - R[f] * 0.6, sk, 10)); if (k === 2) { const nl = ball(R[f] * 0.75, mat('#f0c0b0', { r: 0.3 }), 1, 1.2, 0.4, 10); nl.position.set(0, -LEN[f][k] + 0.008, -R[f] * 0.75); j.add(nl); } segs.push(j); par = j; }
      hand.fingers.push(segs); }
    const tb = G(ha, sg * 0.037, -0.03, 0.022); const t1 = G(tb); t1.add(capsule(0.0135, 0.026, sk, 10)); const t2 = G(t1, 0, -0.032, 0); t2.add(capsule(0.0125, 0.022, sk, 10)); const tn = ball(0.009, mat('#f0c0b0', { r: 0.3 }), 1, 1.2, 0.4, 10); tn.position.set(0, -0.022, -0.009); t2.add(tn);
    hand.thumb = [tb, t1, t2]; return hand; }


  // ------------------------------------------------------------- holding flat things (letters, phones) without going through them
  /** the thumb folds over the front of a slab that lies on the palm (back face at hand-local z = 0.022) */
  thumbOver(s, lift = 0) { const A = this.arm[s], h = A.hand, sg = A.sg; A.ha.updateWorldMatrix(true, false); const q = A.ha.getWorldQuaternion(new Q());
    const d1 = new V3(-sg * 0.2, -0.35, 0.9 + lift).applyQuaternion(q), d2 = new V3(-sg * 0.35, -0.9, -0.25 + lift * 1.6).applyQuaternion(q);
    h.thumb[0].rotation.set(0, 0, 0); h.thumb[1].rotation.set(0, 0, 0); h.thumb[2].rotation.set(0, 0, 0);
    aim(h.thumb[0], d1, new V3(0, -1, 0).applyQuaternion(q)); aim(h.thumb[2], d2, new V3(0, 0, 1).applyQuaternion(q)); }
  /** thumb tip to a world point (two-bone IK), bending away from `out` */
  thumbTo(s, T, out) { const A = this.arm[s], h = A.hand; h.thumb[0].rotation.set(0, 0, 0); h.thumb[1].rotation.set(0, 0, 0); h.thumb[2].rotation.set(0, 0, 0); const S = wp(h.thumb[0]);
    const r = solve2(0.032, 0.03, S, T, out); aim(h.thumb[0], new V3().subVectors(r.E, S), out); aim(h.thumb[2], new V3().subVectors(r.T, r.E), out); }
  /** fingers flat behind a slab (curl c), thumb over its front */
  flat(s, c = 0.04, spread = 0.15, lift = 0) { const h = this.arm[s].hand, sg = this.arm[s].sg; h.fingers.forEach((segs, f) => { segs[0].rotation.set(-c * 1.25, 0, sg * spread * (f - 1.5) * 0.12); segs[1].rotation.set(-c * 1.45, 0, 0); segs[2].rotation.set(-c * 1.1, 0, 0); }); this.thumbOver(s, lift); }
  /** a slab (letter, phone: front +Z, width along X) carried in hand s: it lies on the palm and runs out past the fingertips. Call after the arm is posed. */
  carry(L, s, o = {}) { const A = this.arm[s], th = o.th ?? 0.006, w = o.w ?? 0.26; A.ha.updateWorldMatrix(true, false); const q = A.ha.getWorldQuaternion(new Q());
    const c = new V3(o.x ?? 0, -(o.edge ?? 0.046) - w / 2, 0.022 + th / 2).applyMatrix4(A.ha.matrixWorld); L.position.copy(c);
    _m.makeBasis(new V3(0, -1, 0), new V3(A.sg * (o.flip ? -1 : 1), 0, 0), new V3(0, 0, 1)); if ((A.sg * (o.flip ? -1 : 1)) < 0) _m.makeBasis(new V3(0, 1, 0), new V3(-1, 0, 0), new V3(0, 0, 1));
    L.quaternion.copy(q).multiply(new Q().setFromRotationMatrix(_m)); this.flat(s, o.curl ?? 0.04); }
  /** both hands hold a slab that is already placed (front +Z toward the reader, +X to the reader's right): hands come up from below-outside, fingers behind, thumbs in front */
  hold2(L, o = {}) { const w = o.w ?? 0.26, h = o.h ?? 0.17, th = o.th ?? 0.006; L.updateMatrixWorld(true); const q = L.getWorldQuaternion(new Q());
    const X = new V3(1, 0, 0).applyQuaternion(q), Y = new V3(0, 1, 0).applyQuaternion(q), Z = new V3(0, 0, 1).applyQuaternion(q); const C0 = L.getWorldPosition(new V3());
    for (const s of (o.hands || ['l', 'r'])) { const sg = (s === 'l' ? -1 : 1) * (o.show ? -1 : 1); // the reader's left hand takes the slab's -X edge (show: the front is turned away from the holder)
      const f = X.clone().multiplyScalar(-sg * (o.inw ?? 0.86)).addScaledVector(Y, o.up ?? 0.5).normalize();
      const E = C0.clone().addScaledVector(X, sg * (w / 2 - (o.inset ?? 0.012))).addScaledVector(Y, o.v ?? -h * 0.3);
      const W = E.clone().addScaledVector(f, -0.05).addScaledVector(Z, -(0.022 + th / 2));
      const pole = (o.pole || new V3(0, -1, 0)).clone().addScaledVector(X, sg * 0.9).addScaledVector(Z, 0.3);
      this.reach(s, W, pole, f, Z); this.flat(s, o.curl ?? 0.04); } }
  /** one hand placed on a slab that is already placed, gripping the edge at (u along X, v along Y) with fingers along direction ang (radians in the slab's plane, 0 = +Y) */
  hold1(L, s, o = {}) { const th = o.th ?? 0.006; L.updateMatrixWorld(true); const q = L.getWorldQuaternion(new Q()); const X = new V3(1, 0, 0).applyQuaternion(q), Y = new V3(0, 1, 0).applyQuaternion(q), Z = new V3(0, 0, 1).applyQuaternion(q);
    const a = o.ang ?? 0; const f = X.clone().multiplyScalar(Math.sin(a)).addScaledVector(Y, Math.cos(a)); const E = L.getWorldPosition(new V3()).addScaledVector(X, o.u ?? 0).addScaledVector(Y, o.v ?? 0);
    const W = E.addScaledVector(f, -0.05).addScaledVector(Z, -(0.022 + th / 2) * (o.back ? -1 : 1)); this.reach(s, W, o.pole || new V3(0, -1, 0.2), f, o.back ? Z.clone().negate() : Z); this.flat(s, o.curl ?? 0.04); }

  // ------------------------------------------------------------- other people: take the postman's kit off and dress the body
  style(kind, o = {}) { const head = this.head, C = this.C; for (const m of [this.cap, this.strap, this.bag, this.flap, ...(this.pockets || [])]) m.visible = false; this.hairG.visible = false;
    const hm = hairMat(o.hair || '#1c1410'); const hair = oo => { const m = hairMesh(hm, oo); head.add(m); return m; };
    for (const e of this.eyes) e.brow.children[0].material = hm; const slim = o.slim ?? 1; this.belly.scale.set(1.08 * slim, 1.0, 1.0 * slim * slim); this.belly.position.z = 0.015 * slim; this.chest.scale.set(1.18 * (0.8 + 0.2 * slim), 0.86, 0.86 * slim * slim); for (const s of ['l', 'r']) this.arm[s].sho.children[0].scale.setScalar(0.72 + 0.2 * slim); if (o.noButtons) for (const b of this.buttons) b.visible = false;
    if (kind !== 'papa') { this.must.visible = false; this.glasses.visible = false; this.useArc = true; this.lipU.visible = true; }
    const gold = mat('#e8b84a', { r: 0.25, m: 0.9 });
    if (kind === 'maa') { hair({ line: a => lineOf(a, [[0, 0.208], [38, 0.2], [68, 0.16], [95, 0.112], [128, 0.05], [180, 0.005]]), th: 0.011, top: 0.005, groove: 0.0012, gf: 44, gs: 0, part: 1 }); const bun = ball(0.066, hm, 1, 0.92, 0.9, 32); bun.position.set(0, 0.075, -0.168); head.add(bun); const bt = new THREE.Mesh(new THREE.TorusGeometry(0.05, 0.012, 8, 28), hm); bt.position.set(0, 0.075, -0.19); head.add(bt);
      const part = new THREE.Mesh(new THREE.BoxGeometry(0.007, 0.004, 0.06), mat('#c02828', { r: 0.6 })); part.position.set(0, 0.222, 0.092); part.rotation.x = 0.62; head.add(part);
      const bindi = ball(0.008, mat('#c01828', { r: 0.4 }), 1, 1, 0.4); bindi.position.set(0, 0.158, 0.146); head.add(bindi);
      for (const s of [-1, 1]) { const er = ball(0.012, gold); er.position.set(s * 0.152, 0.05, 0.005); head.add(er); }
      const np = ball(0.0045, gold); np.position.set(0.024, 0.066, 0.162); head.add(np);
      // saree: a long skirt from the waist, the pallu over the left shoulder, bangles
      const sar = fabric(o.saree || '#a8284a', { sheen: '#ffb0c0', r: 0.7 }), bor = mat('#e0b040', { r: 0.4, m: 0.5 });
      const pts = [[0.172, 0.07], [0.19, -0.02], [0.215, -0.25], [0.245, -0.55], [0.265, -0.735]].map(a => new THREE.Vector2(...a)); const sk = new THREE.Mesh(new THREE.LatheGeometry(pts, 40), sar); sk.material.side = THREE.DoubleSide; sk.castShadow = true; this.pelvis.add(sk); this.skirt = sk;
      const hem = new THREE.Mesh(new THREE.TorusGeometry(0.265, 0.014, 8, 48), bor); hem.rotation.x = Math.PI / 2; hem.position.y = -0.725; this.pelvis.add(hem);
      const ell = [[this.belly, 0.17, -0.19], [this.chest, 0.165, 0]].map(([m, r, oy]) => ({ a: r * m.scale.x, b: r * m.scale.y, c: r * m.scale.z, cy: m.position.y + oy, cz: m.position.z }));
      const onBody = v => { const o = new V3(v.x, 0, v.z).normalize(); let R = 0; for (const e of ell) { const dy = clamp((v.y - e.cy) / e.b, -0.9, 0.9); if (Math.abs((v.y - e.cy) / e.b) > 1.25) continue; const A = (o.x / e.a) ** 2 + (o.z / e.c) ** 2, B = -2 * o.z * e.cz / (e.c ** 2), Cc = (e.cz / e.c) ** 2 + dy * dy - 1, D = B * B - 4 * A * Cc; if (D > 0) R = Math.max(R, (-B + Math.sqrt(D)) / (2 * A)); } return R > 0 ? new V3(o.x * (R + 0.008), v.y, o.z * (R + 0.008)) : v; };
      const rib = (P, wd, m, par) => { const cv = new THREE.CatmullRomCurve3(P.map(a => new V3(...a))); const n = 48, W = 6, pos = [], idx = []; for (let i = 0; i <= n; i++) { const p = cv.getPoint(i / n), t = cv.getTangent(i / n); const out = new V3(p.x, 0, p.z).normalize(); const b = new V3().crossVectors(t, out).normalize(); for (let j = 0; j <= W; j++) { const v = onBody(p.clone().addScaledVector(b, (j / W - 0.5) * wd * (1 - 0.7 * Math.exp(-(((i / n - 0.57) / 0.2) ** 2))))); pos.push(v.x, v.y, v.z); } if (i < n) for (let j = 0; j < W; j++) { const a = i * (W + 1) + j, c = a + W + 1; idx.push(a, a + 1, c, a + 1, c + 1, c); } }
        const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setIndex(idx); g.computeVertexNormals(); const me = new THREE.Mesh(g, m); me.material.side = THREE.DoubleSide; me.castShadow = true; par.add(me); return me; };
      rib([[-0.17, -0.13, 0.1], [-0.08, 0.0, 0.175], [0.06, 0.12, 0.165], [0.15, 0.185, 0.07], [0.165, 0.19, -0.05], [0.15, 0.05, -0.13], [0.13, -0.2, -0.15]], 0.17, sar, this.sp2);
      for (const s of ['l', 'r']) for (const k of [0, 1, 2]) { const bg = new THREE.Mesh(new THREE.TorusGeometry(0.04, 0.0045, 6, 20), k === 1 ? mat('#c01828', { r: 0.3 }) : gold); bg.rotation.x = Math.PI / 2; bg.position.y = -this.L.fore + 0.035 + k * 0.011; this.arm[s].fo.add(bg); } }
    if (kind === 'boy') { hair({ line: a => lineOf(a, [[0, 0.205], [40, 0.2], [70, 0.17], [95, 0.125], [128, 0.06], [180, 0.02]]), th: 0.016, top: 0.016, groove: 0.0035, gf: 15, quiff: 0.024, qx: -0.2 }); for (const m of this.belt) m.visible = false; }
    if (kind === 'papa') { hair({ ...HORSESHOE, th: 0.013, groove: 0.0025, gf: 18 }); this.mMust.color.set(o.must || '#5a5a60'); for (const m of this.belt) m.visible = false; }
    if (kind === 'riya') { hair({ line: (a, phi) => lineOf(a, [[0, 0.2], [38, 0.19], [68, 0.12], [95, 0.02], [128, -0.03], [180, -0.05]]) - 0.035 * sm((phi + 0.75) / 1.5) * (a < 60 ? 1 : 0), th: 0.014, top: 0.008, groove: 0.0022, gf: 20, quiff: 0.012, qx: 0.25 }); head.add(longHair(hm));
      for (const s of [-1, 1]) { const lk = ball(0.03, hm, 0.75, 3.4, 0.8, 20); lk.position.set(s * 0.14, -0.02, 0.045); lk.rotation.z = s * 0.06; head.add(lk); const er = ball(0.009, gold); er.position.set(s * 0.152, 0.045, 0.03); head.add(er); } for (const m of this.belt) m.visible = false; }
    return this; }

  place(x, z, yaw) { this.root.position.set(x, 0, z); this.root.rotation.set(0, yaw, 0); this.yaw = yaw; this.root.updateMatrixWorld(true); }
  // local helpers in the body's frame
  fwd() { return new V3(Math.sin(this.yaw), 0, Math.cos(this.yaw)); }
  left() { return new V3(Math.cos(this.yaw), 0, -Math.sin(this.yaw)); }

  /** body: pelvis position (world, or relative), lean/twist; feet planted at world points with lift; knees bend by IK */
  body(o) { const { pel, lean = 0, side = 0, twist = 0, chestLean = 0, chestTwist = 0, chestSide = 0, pelYaw = 0, pelRoll = 0, pelPitch = 0 } = o;
    this.root.updateMatrixWorld(true);
    // pelvis in root space
    const inv = new M4().copy(this.root.matrixWorld).invert(); const pl = pel.clone().applyMatrix4(inv);
    this.pelvis.position.copy(pl); this.pelvis.rotation.set(pelPitch, pelYaw, pelRoll, 'YXZ');
    this.sp1.rotation.set(lean * 0.55, twist * 0.5, side * 0.5, 'YXZ'); this.sp2.rotation.set(lean * 0.45 + chestLean, twist * 0.5 + chestTwist, side * 0.5 + chestSide, 'YXZ');
    this.root.updateMatrixWorld(true);
    for (const s of ['l', 'r']) { const f = o.feet[s]; this.foot(s, f.p, f.dir || this.fwd(), f.pitch || 0, f.knee); } }
  foot(s, ankle, dir, pitch = 0, kneeHint) { const L = this.L, lg = this.leg[s]; const H = wp(lg.hip);
    const kp = kneeHint || dir.clone().add(this.left().multiplyScalar(lg.sg * 0.15));
    const r = solve2(L.thigh, L.shin, H, ankle, kp);
    aim(lg.th, new V3().subVectors(r.E, H), dir); aim(lg.sn, new V3().subVectors(r.T, r.E), dir);
    // foot flat on its direction, pitched (toe up +) at the ankle
    const d = dir.clone().setY(0).normalize(); const down = new V3(0, -1, 0).applyAxisAngle(new V3().crossVectors(UP, d).normalize(), -pitch); const fw = d.clone().applyAxisAngle(new V3().crossVectors(UP, d).normalize(), -pitch);
    aim(lg.ft, down, fw); }
  /** arm reaches its wrist to T (world) with the elbow toward pole; hand fingers along fdir, palm facing pdir */
  reach(s, T, pole, fdir, pdir) { const L = this.L, A = this.arm[s]; const S = wp(A.up);
    const r = solve2(L.upper, L.fore, S, T, pole); aim(A.up, new V3().subVectors(r.E, S), r.p); aim(A.fo, new V3().subVectors(r.T, r.E), r.p);
    if (fdir) aim(A.ha, fdir, pdir.clone().multiplyScalar(1)); else aim(A.ha, new V3().subVectors(r.T, r.E), r.p); }
  /** hang an arm with a swing (walk): forward/back angle and elbow bend */
  hang(s, swing = 0, bend = 0.25, out = 0.12) { const A = this.arm[s]; const f = this.fwd(), l = this.left().multiplyScalar(A.sg);
    A.sho.updateWorldMatrix(true, false); const S = wp(A.up); const d1 = new V3(0, -1, 0).addScaledVector(f, Math.sin(swing)).addScaledVector(l, out).normalize();
    const E = S.clone().addScaledVector(d1, this.L.upper); const d2 = d1.clone().addScaledVector(f, Math.sin(bend) * 1.2).normalize(); const T = E.clone().addScaledVector(d2, this.L.fore);
    this.reach(s, T, f.clone().negate().addScaledVector(l, 0.4), d2.clone().addScaledVector(f, 0.15), l.clone().negate().addScaledVector(f, 0.3)); }
  /** fingers: curl 0 (open) … 1 (fist) for index, middle, ring, pinky and thumb; spread */
  grip(s, c, spread = 0) { const h = this.arm[s].hand, sg = this.arm[s].sg;
    h.fingers.forEach((segs, f) => { const k = c[f]; segs[0].rotation.set(-k * 1.25, 0, sg * spread * (f - 1.5) * 0.12); segs[1].rotation.set(-k * 1.45, 0, 0); segs[2].rotation.set(-k * 1.1, 0, 0); });
    const t = c[4]; h.thumb[0].rotation.set(-0.3 - t * 0.5, sg * (0.5 + t * 0.4), sg * (0.9 - t * 0.3), 'YXZ'); h.thumb[1].rotation.set(-t * 0.8, 0, 0); h.thumb[2].rotation.set(-t * 0.9, 0, 0); }
  /** head and eyes look at a world point (eyes lead, head follows by w) */
  look(P, w = 0.8, roll = 0) { const nk = this.neck, hd = this.head; nk.rotation.set(0, 0, 0); hd.rotation.set(0, 0, 0); this.root.updateMatrixWorld(true);
    const H = wp(hd); const d = new V3().subVectors(P, H).normalize(); hd.parent.getWorldQuaternion(_q2); const loc = d.clone().applyQuaternion(_q2.clone().invert());
    const yaw = Math.atan2(loc.x, loc.z), pitch = Math.asin(clamp(-loc.y, -1, 1));
    const cy = clamp(yaw, -1.3, 1.3) * w, cp = clamp(pitch, -0.9, 0.9) * w; nk.rotation.set(cp * 0.4, cy * 0.45, 0, 'YXZ'); hd.rotation.set(cp * 0.6, cy * 0.55, roll, 'YXZ'); this.face.gaze = P; }
  /** apply the face (call after look) */
  faceUpdate(t) { const F = this.face; this.root.updateMatrixWorld(true);
    for (const e of this.eyes) { if (F.gaze) { const c = wp(e.piv); const d = new V3().subVectors(F.gaze, c).normalize(); e.piv.getWorldQuaternion(_q2); const l = d.applyQuaternion(_q2.invert());
        e.ball.rotation.set(clamp(-Math.asin(clamp(l.y, -1, 1)), -0.5, 0.5), clamp(Math.atan2(l.x, l.z), -0.6, 0.6), 0, 'YXZ'); }
      const lidOpen = (e.s < 0 ? F.lidR : F.lidL); const close = clamp(Math.max(F.blink, (lidOpen + F.squint * 0.45) * 0.6), 0, 1);
      // upper lid: rotates down over the eye from its open angle; lower lid rises a little with a smile/squint
      e.lidU.rotation.x = lerp(-2.62, 0.12, close) + (e.ball.rotation.x) * 0.3 * (1 - close); e.lidD.rotation.x = lerp(1.2, 0.35, clamp(F.squint * 0.6 + F.blink * 0.25, 0, 1));
      const shut = sm(clamp((close - 0.4) / 0.4, 0, 1)); e.piv.scale.z = lerp(1, 0.74, shut); e.cl.visible = close > 0.72; e.lash.visible = close <= 0.72;
      const b = e.s < 0 ? F.browR : F.browL; e.brow.position.y = 0.152 + b * 0.02 - F.knit * 0.008; e.brow.rotation.z = e.s * (-F.sad * 0.38 + F.knit * 0.3); e.brow.position.x = e.s * (0.056 - F.knit * 0.008); }
    // jaw and mouth shape: open, wide (ee), round (oo), smile
    const jaw = clamp(F.jaw, 0, 1); this.jawG.rotation.x = jaw * 0.32;
    const wide = 1 + F.wide * 0.3 - F.round * 0.3 + F.smile * 0.15; this.cav.scale.set(1.25 * wide, 0.12 + jaw * 0.95 + F.round * 0.2, 0.5 + jaw * 0.15); this.cav.position.y = -jaw * 0.02;
    this.lip.scale.set(1.25 * wide, 0.2 + jaw * 1.0 + F.round * 0.35, 0.6); this.lip.position.y = -jaw * 0.02; this.teeth.visible = jaw > 0.08 || F.wide > 0.3; this.teeth.scale.set(1.15 * wide, 0.28, 0.4); this.tongue.position.y = -0.012 - jaw * 0.022; this.tongue.visible = jaw > 0.15; 
    this.must.position.y = 0.032 + F.smile * 0.006 - jaw * 0.004; this.must.rotation.x = -jaw * 0.12; this.must.scale.x = 1 + F.smile * 0.08 - F.round * 0.05;
    { const li = [F.smile, F.sad * 0.8, F.round, F.wide]; for (const L of [this.lipU, this.lipL]) for (let k = 0; k < 4; k++) L.morphTargetInfluences[k] = clamp(li[k], 0, 1.2); const hm = this.headM.morphTargetInfluences; hm[0] = clamp(F.smile, 0, 1); hm[1] = clamp(F.puff, 0, 1); hm[2] = jaw;
      this.cav.visible = jaw > 0.07; this.cav.scale.set(1.0 * wide, 0.1 + jaw * 0.9, 0.5); this.cav.position.y = -0.004 - jaw * 0.014; this.teeth.visible = jaw > 0.14; this.teeth.scale.set(1.05 * wide, 0.22, 0.4); this.tongue.visible = jaw > 0.2; }
    for (const c of this.cheeks) { c.position.y = 0.048 + F.smile * 0.012 + F.puff * 0.004; c.scale.set(1 + F.puff * 0.45 + F.smile * 0.05, 0.8 + F.smile * 0.18, 0.14 + F.puff * 0.9 + F.smile * 0.16); } }
}

// ------------------------------------------------------------------ motion helpers
export const blinkAt = (t, seed = 1) => { const per = 3.1 + (seed % 3) * 0.6; const u = ((t + seed * 1.37) % per) / per; const k = (u - 0.97) / 0.03; return k > 0 && k < 1 ? Math.sin(Math.PI * k) : 0; };
/** a walk on a path: returns pelvis point, feet, and arm swing phases. path(s) → {p, dir}; s metres along it.
 *  stride: one step length; speed in m/s is applied by the caller (s = speed * t). */
export function walkPose(path, s, o = {}) { const step = o.step ?? 0.5, w = o.width ?? 0.11, lift = o.lift ?? 0.11, hip = o.hip ?? 0.79, stance = 0.62;
  const ph = s / (2 * step); const out = { feet: {} };
  const c = path(s); const dir = c.dir.clone().setY(0).normalize(); const left = new V3(dir.z, 0, -dir.x);
  for (const [k, off] of [['l', 0], ['r', 0.5]]) { const u0 = ph + off, n = Math.floor(u0), u = u0 - n;
    const sp = (n - off) * 2 * step + stance * 0.5 * 2 * step; // where this foot plants for cycle n
    let sf, h = 0, pitch = 0; if (u < stance) { sf = sp; pitch = u < 0.08 ? 0.25 * (1 - u / 0.08) : (u > stance - 0.15 ? -0.5 * (u - (stance - 0.15)) / 0.15 : 0); }
    else { const v = (u - stance) / (1 - stance), e = v * v * (3 - 2 * v); sf = sp + e * 2 * step; h = Math.sin(Math.PI * v) * lift; pitch = lerp(-0.5, 0.3, v); }
    const fc = path(sf); const fd = fc.dir.clone().setY(0).normalize(); const fl = new V3(fd.z, 0, -fd.x); const sg = k === 'l' ? 1 : -1;
    out.feet[k] = { p: fc.p.clone().addScaledVector(fl, sg * w).add(new V3(0, 0.075 + h + Math.max(0, pitch) * 0.04, 0)), dir: fd, pitch }; }
  const bob = Math.cos(ph * 4 * Math.PI); out.pel = c.p.clone().add(new V3(0, hip - 0.03 + 0.022 * bob * -1, 0)).addScaledVector(left, 0.022 * Math.sin(ph * 2 * Math.PI));
  out.swing = Math.sin(ph * 2 * Math.PI); out.dir = dir; out.ph = ph; out.pelRoll = 0.05 * Math.sin(ph * 2 * Math.PI); out.pelYaw = 0.09 * Math.sin(ph * 2 * Math.PI);
  return out; }
