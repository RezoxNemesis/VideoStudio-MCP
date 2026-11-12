// A horse built from lofted cross-sections: barrel, deep neck, long head, jointed legs, mane, tail, tack and reins.
import { THREE, V3, mulberry } from './core.js';
const C = (r, g, b) => new THREE.Color().setRGB(r, g, b, THREE.SRGBColorSpace);
const mesh = (g, m, cast = true) => { const o = new THREE.Mesh(g, m); o.castShadow = cast; o.receiveShadow = true; return o; };
/** rings: [cy, cz, rx, ry, tilt] — an ellipse centred (0,cy,cz); its long axis is tilted `tilt` rad from +Y towards +Z */
function loft(rings, seg = 16) {
  const pos = [], idx = []; const n = rings.length;
  rings.forEach(([cy, cz, rx, ry, a = 0]) => { const uy = Math.cos(a), uz = Math.sin(a); for (let k = 0; k < seg; k++) { const th = k / seg * 6.2832; const c = Math.cos(th), s = Math.sin(th); const cc = Math.sign(c) * Math.pow(Math.abs(c), 0.85), ss = Math.sign(s) * Math.pow(Math.abs(s), 0.85); pos.push(cc * rx, cy + ss * ry * uy, cz + ss * ry * uz); } });
  for (let i = 0; i < n - 1; i++) for (let k = 0; k < seg; k++) { const a = i * seg + k, b = i * seg + (k + 1) % seg, c = a + seg, d = b + seg; idx.push(a, c, b, b, c, d); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setIndex(idx); g.computeVertexNormals(); return g;
}
const lathe = (pts, seg = 10) => new THREE.LatheGeometry(pts.map(([y, r]) => new THREE.Vector2(r, y)), seg);
/** hanging hair locks: roots[i] = {p:V3, d:V3 (hang direction), len, w} */
function locks(roots, mat, seed = 1) {
  const r = mulberry(seed); const pos = [], idx = []; let v = 0;
  for (const k of roots) { const d = k.d.clone().normalize(); const side = new THREE.Vector3().crossVectors(d, k.n || V3(1, 0, 0)).normalize().multiplyScalar(k.w / 2); const S = 4;
    for (let i = 0; i <= S; i++) { const u = i / S; const c = k.p.clone().addScaledVector(d, k.len * u).addScaledVector(k.n || V3(1, 0, 0), (k.bow ?? 0.03) * Math.sin(u * 3.14)); const w = 1 - u * 0.75; pos.push(c.x - side.x * w, c.y - side.y * w, c.z - side.z * w, c.x + side.x * w, c.y + side.y * w, c.z + side.z * w); }
    for (let i = 0; i < S; i++) { const a = v + i * 2; idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); } v += (S + 1) * 2; }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setIndex(idx); g.computeVertexNormals(); return mesh(g, mat);
}
export function horse(o = {}) {
  const g = new THREE.Group(); const coat = C(...(o.color || [0.07, 0.05, 0.04]));
  const mat = new THREE.MeshStandardMaterial({ color: coat, roughness: 0.6, metalness: 0.0 }); const dark = new THREE.MeshStandardMaterial({ color: coat.clone().multiplyScalar(0.45), roughness: 0.6 });
  const hairM = new THREE.MeshStandardMaterial({ color: C(...(o.mane || [0.012, 0.01, 0.01])), roughness: 0.62, side: THREE.DoubleSide }); const tack = new THREE.MeshStandardMaterial({ color: 0x120a07, roughness: 0.55 }); const iron = new THREE.MeshStandardMaterial({ color: 0x8a8a90, roughness: 0.35, metalness: 1 });
  const bodyG = new THREE.Group(); g.add(bodyG);
  const body = mesh(loft([[1.3, -1.0, 0.03, 0.06], [1.27, -0.95, 0.17, 0.2], [1.22, -0.82, 0.27, 0.31], [1.2, -0.62, 0.315, 0.365], [1.16, -0.3, 0.325, 0.36], [1.135, 0.08, 0.32, 0.37], [1.14, 0.4, 0.3, 0.405], [1.19, 0.66, 0.27, 0.4], [1.23, 0.84, 0.215, 0.33], [1.24, 0.95, 0.13, 0.2], [1.24, 1.0, 0.03, 0.05]]), mat); bodyG.add(body);
  for (const s of [-1, 1]) { const hip = mesh(new THREE.SphereGeometry(0.3, 12, 9), mat); hip.scale.set(0.42, 1.0, 0.92); hip.position.set(s * 0.17, 1.2, -0.62); bodyG.add(hip); const sh = mesh(new THREE.SphereGeometry(0.27, 12, 9), mat); sh.scale.set(0.36, 1.1, 0.78); sh.position.set(s * 0.15, 1.17, 0.62); bodyG.add(sh); }
  // neck: thick at the shoulder, arched crest
  const PH = 0.6; const D = [Math.cos(PH), Math.sin(PH)];   // path direction (y,z) from vertical
  const neckP = new THREE.Group(); neckP.position.set(0, 1.33, 0.7); bodyG.add(neckP);
  const nk = [[-0.16, 0.2, 0.3], [0.05, 0.175, 0.26], [0.26, 0.135, 0.205], [0.46, 0.105, 0.165], [0.64, 0.092, 0.142], [0.78, 0.085, 0.125], [0.84, 0.07, 0.1]];
  const crest = (s) => 0.035 * Math.sin(Math.max(0, s) / 0.84 * 3.14);
  neckP.add(mesh(loft(nk.map(([s, rx, ry]) => [s * D[0] + crest(s) * D[1] * -1 * -1, s * D[1] - crest(s) * D[0], rx, ry, PH + Math.PI / 2])), mat));
  const top = (s) => V3(0, s * D[0], s * D[1]).add(V3(0, D[1], -D[0]).multiplyScalar(crest(s))).add(V3(0, D[1], -D[0]).multiplyScalar(nk.reduce((a, k) => Math.abs(k[0] - s) < Math.abs(a[0] - s) ? k : a)[2]));
  const r = mulberry(o.seed ?? 4); const mr = []; for (let i = 0; i < 30; i++) { const s = 0.02 + i / 29 * 0.8; mr.push({ p: top(s).add(V3(0, 0.0, 0)), d: V3(0.75 + r() * 0.2, -1, -0.25 + r() * 0.2), n: V3(1, 0.4, 0).normalize(), len: 0.22 + r() * 0.12, w: 0.07, bow: 0.05 }); }
  neckP.add(locks(mr, hairM, 3));
  const headP = new THREE.Group(); headP.position.set(0, 0.8 * D[0], 0.8 * D[1]); neckP.add(headP);
  const K = 0.92; headP.add(mesh(loft([[0.0, -0.13, 0.07, 0.09], [-0.005, -0.03, 0.105, 0.14], [-0.015, 0.1, 0.11, 0.15], [-0.01, 0.26, 0.09, 0.115], [0.0, 0.42, 0.07, 0.085], [0.0, 0.54, 0.067, 0.078], [-0.005, 0.6, 0.052, 0.06], [-0.01, 0.635, 0.02, 0.03]].map(([y, z, rx, ry]) => [y * K, z * K, rx * K, ry * K])), mat));
  for (const s of [-1, 1]) { const ear = mesh(new THREE.ConeGeometry(0.034, 0.14, 6), mat); ear.scale.z = 0.6; ear.position.set(s * 0.062, 0.17, -0.05); ear.rotation.set(-0.25, 0, -s * 0.22); headP.add(ear);
    const eye = mesh(new THREE.SphereGeometry(0.024, 8, 6), new THREE.MeshStandardMaterial({ color: 0x020202, roughness: 0.35 }), false); eye.position.set(s * 0.094, 0.05, 0.085); headP.add(eye);
    const nos = mesh(new THREE.SphereGeometry(0.014, 6, 5), new THREE.MeshStandardMaterial({ color: 0x030303, roughness: 0.4 }), false); nos.position.set(s * 0.034, 0.012, 0.565); headP.add(nos);
    const ch = mesh(new THREE.BoxGeometry(0.008, 0.02, 0.42), tack); ch.position.set(s * 0.1, -0.01, 0.2); ch.rotation.y = s * -0.085; headP.add(ch); }
  const nb = mesh(new THREE.TorusGeometry(0.073, 0.008, 5, 18), tack); nb.scale.y = 1.2; nb.position.set(0, 0, 0.4); headP.add(nb); const bb = mesh(new THREE.TorusGeometry(0.104, 0.008, 5, 18, Math.PI), tack); bb.scale.y = 1.25; bb.position.set(0, 0.02, 0.0); headP.add(bb);
  const fl = []; for (let i = 0; i < 7; i++) fl.push({ p: V3((r() - 0.5) * 0.07, 0.125, -0.02), d: V3((r() - 0.5) * 0.5, -0.25, 1), n: V3(0, 1, 0.3).normalize(), len: 0.16 + r() * 0.07, w: 0.045, bow: 0.02 }); headP.add(locks(fl, hairM, 5));
  // tail
  const tailP = new THREE.Group(); tailP.position.set(0, 1.3, -0.97); bodyG.add(tailP); const tr = []; for (let i = 0; i < 18; i++) tr.push({ p: V3((r() - 0.5) * 0.06, (r() - 0.5) * 0.05, 0), d: V3((r() - 0.5) * 0.22, -1, -0.12 - r() * 0.15), n: V3(0, 0.2, -1).normalize(), len: 0.85 + r() * 0.3, w: 0.07, bow: 0.1 }); tailP.add(locks(tr, hairM, 7));
  // legs: shoulder/hip pivot -> knee/hock pivot -> hoof
  const legs = []; const legAt = [[0.17, 1.02, 0.6, 1], [-0.17, 1.02, 0.6, 1], [0.19, 1.07, -0.68, 0], [-0.19, 1.07, -0.68, 0]];
  for (const [x, y, z, front] of legAt) { const up = new THREE.Group(); up.position.set(x, y, z); g.add(up); const ul = front ? 0.46 : 0.5;
    const um = mesh(lathe(front ? [[0.08, 0.0], [0.06, 0.1], [-0.08, 0.112], [-0.26, 0.078], [-0.4, 0.058], [-0.455, 0.064], [-0.47, 0.05]] : [[0.1, 0.0], [0.06, 0.13], [-0.1, 0.14], [-0.3, 0.085], [-0.44, 0.062], [-0.495, 0.07], [-0.51, 0.05]]), mat); um.scale.x = 0.72; if (!front) um.rotation.x = 0.1; up.add(um);
    const lo = new THREE.Group(); lo.position.y = -ul; up.add(lo); const ll = y - ul;
    lo.add(mesh(lathe([[0.02, 0.05], [-0.05, 0.043], [-ll + 0.2, 0.036], [-ll + 0.12, 0.05], [-ll + 0.085, 0.04], [-ll + 0.06, 0.046]]), dark));
    const hoof = mesh(lathe([[-ll + 0.075, 0.046], [-ll + 0.0, 0.066], [-ll + 0.0, 0.0]]), new THREE.MeshStandardMaterial({ color: 0x060505, roughness: 0.4 })); hoof.position.z = 0.012; lo.add(hoof); legs.push({ up, lo, front }); }
  // tack: blanket draped over the back, saddle, girth
  const drape = (z0, z1, t0, t1, k, m) => { const pos = [], idx = []; const A = 12, B = 3; for (let i = 0; i <= A; i++) for (let j = 0; j <= B; j++) { const th = t0 + (t1 - t0) * i / A, z = z0 + (z1 - z0) * j / B; pos.push(Math.cos(th) * 0.325 * k, 1.145 + Math.sin(th) * 0.375 * k, z); } for (let i = 0; i < A; i++) for (let j = 0; j < B; j++) { const a = i * (B + 1) + j, b = a + B + 1; idx.push(a, b, a + 1, a + 1, b, b + 1); } const gg = new THREE.BufferGeometry(); gg.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); gg.setIndex(idx); gg.computeVertexNormals(); m.side = THREE.DoubleSide; return mesh(gg, m); };
  bodyG.add(drape(-0.46, 0.34, 0.12, Math.PI - 0.12, 1.035, new THREE.MeshStandardMaterial({ color: C(...(o.cloth || [0.35, 0.04, 0.04])), roughness: 0.92 })));
  bodyG.add(drape(0.1, 0.19, 0, 6.2832, 1.02, tack));
  const sad = mesh(new THREE.SphereGeometry(0.3, 14, 8, 0, 6.2832, 0, 1.5), tack); sad.scale.set(0.8, 0.3, 1.05); sad.position.set(0, 1.46, -0.06); bodyG.add(sad); const pom = mesh(new THREE.SphereGeometry(0.07, 8, 6), tack); pom.scale.set(1.3, 1, 0.6); pom.position.set(0, 1.56, 0.2); bodyG.add(pom); const can = pom.clone(); can.position.set(0, 1.58, -0.33); can.scale.set(2.2, 1.2, 0.6); bodyG.add(can);
  // reins: two strips from the bit to the rider's hands, rebuilt each pose
  const RS = 8; const rg = new THREE.BufferGeometry(); const rp = new Float32Array(2 * (RS + 1) * 2 * 3); rg.setAttribute('position', new THREE.BufferAttribute(rp, 3)); const ri = []; for (let s = 0; s < 2; s++) for (let i = 0; i < RS; i++) { const a = s * (RS + 1) * 2 + i * 2; ri.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); } rg.setIndex(ri);
  const reins = new THREE.Mesh(rg, new THREE.MeshStandardMaterial({ color: 0x0c0705, roughness: 0.6, side: THREE.DoubleSide })); reins.frustumCulled = false; g.add(reins); g.userData.hands = V3(0, 1.7, 0.28);
  const _a = new THREE.Vector3(), _b = new THREE.Vector3();
  const updateReins = () => { g.updateMatrixWorld(true); const inv = new THREE.Matrix4().copy(g.matrixWorld).invert(); for (let s = 0; s < 2; s++) { const sx = s ? -1 : 1; _a.set(sx * 0.07 * K, -0.03, 0.43 * K); headP.localToWorld(_a).applyMatrix4(inv); _b.copy(g.userData.hands); _b.x += sx * 0.07; for (let i = 0; i <= RS; i++) { const u = i / RS; const x = _a.x + (_b.x - _a.x) * u, y = _a.y + (_b.y - _a.y) * u - Math.sin(u * 3.14) * 0.07, z = _a.z + (_b.z - _a.z) * u; const o2 = (s * (RS + 1) * 2 + i * 2) * 3; rp[o2] = x; rp[o2 + 1] = y + 0.008; rp[o2 + 2] = z; rp[o2 + 3] = x; rp[o2 + 4] = y - 0.008; rp[o2 + 5] = z; } } rg.attributes.position.needsUpdate = true; rg.computeVertexNormals(); };
  g.userData.seat = V3(0, 1.53, -0.06);
  /** phase through the stride; gait: 'gallop' | 'walk' | 'stand' | 'rear'; amount 0..1 */
  g.userData.pose = (ph, gait = 'gallop', amt = 1) => {
    const S = (x) => Math.sin(x * 6.2832); const HB = 0.5;
    if (gait === 'gallop') { bodyG.position.y = S(ph + 0.1) * 0.07 * amt; g.rotation.x = S(ph + 0.35) * 0.06 * amt; neckP.rotation.x = 0.42 + S(ph + 0.6) * 0.12 * amt; headP.rotation.x = HB + 0.1 - S(ph + 0.6) * 0.1; tailP.rotation.x = 1.15 + S(ph) * 0.2;
      const offs = [0.0, 0.12, 0.5, 0.62]; legs.forEach((l, i) => { const p = ph + offs[i]; const sw = S(p); const lift = Math.max(0, Math.sin((p % 1) * 6.2832 + 1.2)); l.up.rotation.x = (l.front ? -sw * 0.75 - 0.1 : -sw * 0.65 + 0.15) * amt; l.lo.rotation.x = (l.front ? lift * 1.3 : -lift * 1.1 + 0.25 * (1 - lift)) * amt; }); }
    else if (gait === 'walk') { bodyG.position.y = S(ph * 2) * 0.012; g.rotation.x = 0; neckP.rotation.x = 0.22 + S(ph * 2) * 0.05; headP.rotation.x = HB + 0.25 - S(ph * 2) * 0.04; tailP.rotation.x = 0.2 + S(ph) * 0.08; const offs = [0.0, 0.5, 0.25, 0.75]; legs.forEach((l, i) => { const p = ph + offs[i]; const sw = S(p); const lift = Math.max(0, Math.sin((p % 1) * 6.2832 + 1.2)); l.up.rotation.x = -sw * 0.32; l.lo.rotation.x = l.front ? lift * 0.6 : -lift * 0.45; }); }
    else if (gait === 'rear') { g.rotation.x = -0.75 * amt; bodyG.position.y = 0; neckP.rotation.x = 0.15; headP.rotation.x = HB + 0.45; tailP.rotation.x = 0.6; legs.forEach((l, i) => { if (l.front) { l.up.rotation.x = (-1.1 + S(ph + i * 0.3) * 0.35) * amt; l.lo.rotation.x = (1.5 + S(ph * 2 + i) * 0.3) * amt; } else { l.up.rotation.x = 0.7 * amt; l.lo.rotation.x = -0.15 * amt; } }); }
    else { bodyG.position.y = 0; g.rotation.x = 0; neckP.rotation.x = 0.12 + S(ph * 0.3) * 0.05; headP.rotation.x = HB + 0.32 + S(ph * 0.7) * 0.07; tailP.rotation.x = 0.12 + S(ph) * 0.12; legs.forEach((l, i) => { l.up.rotation.x = i === 1 ? -0.06 : 0; l.lo.rotation.x = i === 1 ? 0.12 : 0; }); }
    g.userData.hands.y = 1.7 + bodyG.position.y; updateReins();
  };
  g.userData.pose(0, 'stand'); g.traverse(x => { if (x.isMesh) x.castShadow = true; }); return g;
}
