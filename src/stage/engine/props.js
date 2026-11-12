// Props: swords, spears, shields, helmets, armour pieces, small story objects, and a horse.
import { THREE, V3, mulberry, clamp, lerp } from './core.js';
import { metalMaterial, leatherMaterial, hairMaterial, patch, GU } from './mat.js';
import { propMat } from './world.js';
import * as BGU from 'three/addons/utils/BufferGeometryUtils.js';
const C = (r, g, b) => new THREE.Color().setRGB(r, g, b, THREE.SRGBColorSpace);
const withAux = (g) => { g.setAttribute('aux', g.attributes.position.clone()); return g; };
const mesh = (g, m, cast = true) => { const o = new THREE.Mesh(g, m); o.castShadow = cast; o.receiveShadow = true; return o; };

/** curved single-edged blade along +Y, edge towards +Z. */
function bladeGeo(len = 0.74, w = 0.03, thick = 0.006, curve = 0.03, segs = 18) {
  const pos = [], idx = []; const ring = 4;
  for (let i = 0; i <= segs; i++) { const t = i / segs; const y = t * len; const zc = -curve * (4 * t * (1 - t)) - curve * 0.6 * t * t; const tip = t > 0.9 ? 1 - (t - 0.9) / 0.1 : 1; const ww = w * (1 - 0.22 * t) * Math.max(0.02, Math.pow(tip, 0.6)), th = thick * (1 - 0.3 * t) * Math.max(0.05, tip);
    pos.push(-th / 2, y, zc - ww * 0.5, th / 2, y, zc - ww * 0.5, th * 0.35, y, zc + ww * 0.1, 0, y, zc + ww * 0.5); pos.push(-th * 0.35, y, zc + ww * 0.1); }
  const R = 5; for (let i = 0; i < segs; i++) for (let k = 0; k < R; k++) { const order = [0, 1, 2, 3, 4]; const a = i * R + order[k], b = i * R + order[(k + 1) % R], c = (i + 1) * R + order[k], d = (i + 1) * R + order[(k + 1) % R]; idx.push(a, c, b, b, c, d); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setIndex(idx); const ni = g.toNonIndexed(); ni.computeVertexNormals(); return withAux(ni);
}
/** the nameless blade. Origin at the middle of the grip; blade along +Y. opts: {length, big, wood} */
export function sword(o = {}) {
  const g = new THREE.Group(); const L = o.length ?? 0.74; const big = o.big ? 1.45 : 1;
  if (o.wood) { const b = new THREE.CylinderGeometry(0.012, 0.016, L + 0.26, 8); b.translate(0, (L + 0.26) / 2 - 0.13, 0); const p = b.attributes.position; for (let i = 0; i < p.count; i++) { const t = clamp((p.getY(i) - 0.1) / L); p.setZ(i, p.getZ(i) * 1.5 - 0.03 * 4 * t * (1 - t)); } b.computeVertexNormals(); g.add(mesh(b, propMat('wood', { color: [0.75, 0.6, 0.45] }))); g.userData = { tip: V3(0, L + 0.1, -0.01), base: V3(0, 0.14, 0), len: L }; return g; }
  const steel = metalMaterial(o.color || [0.62, 0.64, 0.68], { rough: 0.2, rust: 0.02 }); steel.envMapIntensity = 1.4;
  const blade = mesh(bladeGeo(L, 0.03 * big, 0.0065 * big, o.curve ?? 0.03), steel); blade.position.y = 0.135; g.add(blade); g.userData.blade = blade; g.userData.steel = steel;
  const guard = new THREE.CylinderGeometry(0.036 * big, 0.036 * big, 0.007, o.big ? 4 : 12); guard.scale(o.big ? 2.6 : 1, 1, 0.75); guard.translate(0, 0.13, 0); g.add(mesh(withAux(guard), metalMaterial([0.2, 0.19, 0.18], { rough: 0.5, rust: 0.3 })));
  const grip = new THREE.CylinderGeometry(0.0145 * big, 0.0155 * big, 0.25 * big, 10); grip.scale(1, 1, 1.25); grip.translate(0, 0.0, 0); g.add(mesh(withAux(grip), leatherMaterial(o.grip || [0.07, 0.06, 0.06], { bands: 90 })));
  const pom = new THREE.SphereGeometry(0.018 * big, 8, 6); pom.translate(0, -0.128 * big, 0); g.add(mesh(withAux(pom), metalMaterial([0.3, 0.28, 0.26], { rough: 0.45 })));
  g.userData.tip = V3(0, 0.135 + L, -0.02); g.userData.base = V3(0, 0.16, 0); g.userData.len = L; return g;
}
export function scabbard(L = 0.78) { const b = new THREE.CylinderGeometry(0.017, 0.02, L, 8); b.scale(1, 1, 1.6); b.translate(0, L / 2, 0); const p = b.attributes.position; for (let i = 0; i < p.count; i++) { const t = p.getY(i) / L; p.setZ(i, p.getZ(i) - 0.03 * 4 * t * (1 - t) - 0.018 * t * t); } b.computeVertexNormals(); return mesh(withAux(b), leatherMaterial([0.035, 0.03, 0.035], { rough: 0.35 })); }
/** mother's silver crescent hairpin */
export function crescent(s = 1) { const g = new THREE.Group(); const t = new THREE.TorusGeometry(0.022 * s, 0.0042 * s, 6, 20, Math.PI * 1.35); const p = t.attributes.position; for (let i = 0; i < p.count; i++) { const a = Math.atan2(p.getY(i), p.getX(i)); const k = 0.35 + 0.65 * Math.sin(clamp(a / (Math.PI * 1.35)) * Math.PI); const r = Math.hypot(p.getX(i), p.getY(i)); const rr = 0.022 * s + (r - 0.022 * s) * k; p.setX(i, Math.cos(a) * rr); p.setY(i, Math.sin(a) * rr); p.setZ(i, p.getZ(i) * k); } t.computeVertexNormals(); const m = metalMaterial([0.86, 0.87, 0.9], { rough: 0.16, rust: 0 }); m.envMapIntensity = 1.6; m.emissive = new THREE.Color(0.25, 0.27, 0.3); m.emissiveIntensity = 0.0; g.add(mesh(withAux(t), m)); const pin = new THREE.CylinderGeometry(0.0016 * s, 0.0006 * s, 0.085 * s, 5); pin.translate(0, -0.06 * s, 0); g.add(mesh(withAux(pin), m)); g.userData.mat = m; return g; }
export function staff(L = 1.7) { const b = new THREE.CylinderGeometry(0.016, 0.02, L, 7); const p = b.attributes.position; for (let i = 0; i < p.count; i++) p.setX(i, p.getX(i) + Math.sin(p.getY(i) * 4) * 0.008); b.computeVertexNormals(); return mesh(b, propMat('wood', { color: [0.5, 0.42, 0.34] })); }
export function spear(L = 2.5) { const g = new THREE.Group(); const sh = new THREE.CylinderGeometry(0.014, 0.016, L, 6); g.add(mesh(sh, propMat('wood', { color: [0.45, 0.38, 0.3] }))); const hd = new THREE.ConeGeometry(0.03, 0.32, 4); hd.scale(1, 1, 0.35); hd.translate(0, L / 2 + 0.16, 0); g.add(mesh(withAux(hd), metalMaterial([0.55, 0.56, 0.6], { rough: 0.3 }))); g.userData = { tip: V3(0, L / 2 + 0.32, 0), base: V3(0, L / 2, 0) }; return g; }
/** round shield with the iron wolf of Valdhar */
export function shield(R = 0.42) { const g = new THREE.Group(); const d = new THREE.SphereGeometry(R * 2.2, 24, 8, 0, 6.283, 0, 0.46); d.translate(0, -R * 2.2 * Math.cos(0.46), 0); d.rotateX(Math.PI / 2); const u = { uR: { value: R } };
  const m = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.6, metalness: 0.0, side: THREE.DoubleSide }); patch(m, { attrs: [], uniforms: u, key: 'shield', vdecl: 'varying vec3 vOP;', vmain: 'vOP = position;', decl: 'uniform float uR; varying vec3 vOP;',
    color: `{ vec2 q = vOP.xy/uR; float rr = length(q); vec3 wood = vec3(0.10, 0.02, 0.02)*(0.7 + 0.6*fbm3(vec3(q.x*30., q.y*4., 1.))); vec3 c = wood; float rim = smoothstep(0.86, 0.9, rr); float boss = smoothstep(0.2, 0.17, rr); vec2 w = q*2.4; float wolf = smoothstep(0.02, -0.02, max(abs(w.x) - 0.75 + w.y*0.55, abs(w.y) - 0.6)) * step(0.22, rr); wolf *= smoothstep(-0.02, 0.02, length(w - vec2(0.3, 0.15)) - 0.1)*smoothstep(-0.02, 0.02, length(w - vec2(-0.3, 0.15)) - 0.1); c = mix(c, vec3(0.42, 0.42, 0.44), wolf*0.8); c = mix(c, vec3(0.3, 0.3, 0.32)*(0.6 + 0.6*fbm3(vOP*40.)), max(rim, boss)); diffuseColor.rgb = c; }`,
    rough: `{ vec2 q = vOP.xy/uR; float rr = length(q); float mt = max(smoothstep(0.86, 0.9, rr), smoothstep(0.2, 0.17, rr)); metalnessFactor = mt; roughnessFactor = mix(0.7, 0.4, mt); }` });
  g.add(mesh(d, m)); return g; }
/** open-faced iron helmet with nasal bar and neck guard; origin at the centre of the skull */
export function helmet(o = {}) { const k = o.k ?? 1; const pts = []; for (let i = 0; i <= 10; i++) { const a = i / 10 * Math.PI * 0.56; pts.push(new THREE.Vector2(Math.sin(a) * 0.112 * k + 0.002, Math.cos(a) * 0.125 * k)); } pts.push(new THREE.Vector2(0.135 * k, -0.035 * k), new THREE.Vector2(0.15 * k, -0.045 * k));
  const dome = new THREE.LatheGeometry(pts, 20); dome.scale(1, 1, 1.12); const parts = [dome.toNonIndexed()];
  const nasal = new THREE.BoxGeometry(0.016 * k, 0.085 * k, 0.008 * k); nasal.translate(0, -0.035 * k, 0.128 * k); parts.push(nasal.toNonIndexed());
  const neck = new THREE.CylinderGeometry(0.128 * k, 0.16 * k, 0.09 * k, 14, 1, true, Math.PI * 0.62, Math.PI * 0.76); neck.translate(0, -0.075 * k, -0.01 * k); neck.scale(1, 1, 1.12); parts.push(neck.toNonIndexed());
  const ridge = new THREE.TorusGeometry(0.118 * k, 0.006 * k, 4, 14, Math.PI); ridge.rotateY(Math.PI / 2); ridge.scale(1, 1.06, 1.12); parts.push(ridge.toNonIndexed());
  if (o.crest) { const cr = new THREE.BoxGeometry(0.012 * k, 0.07 * k, 0.2 * k); cr.translate(0, 0.14 * k, -0.01 * k); parts.push(cr.toNonIndexed()); }
  const g = BGU.mergeGeometries(parts.map(p => { p.deleteAttribute('uv'); return p; })); g.computeVertexNormals(); return mesh(withAux(g), metalMaterial(o.color || [0.22, 0.22, 0.24], { rough: 0.42, rust: o.rust ?? 0.3 })); }
/** shoulder plate; origin at the shoulder joint, pointing outwards along +X (mirror for the right) */
export function pauldron(o = {}) { const k = o.k ?? 1; const s = new THREE.SphereGeometry(0.1 * k, 12, 8, 0, Math.PI * 2, 0, Math.PI * 0.55); s.scale(1.25, 0.85, 1.15); s.rotateZ(-0.5); s.translate(0.03 * k, 0.03 * k, 0); const parts = [s.toNonIndexed()]; for (let i = 1; i <= 2; i++) { const l = new THREE.SphereGeometry(0.095 * k, 12, 4, 0, Math.PI * 2, Math.PI * 0.3, Math.PI * 0.22); l.scale(1.15, 1, 1.12); l.rotateZ(-0.9 - i * 0.1); l.translate(0.05 * k + i * 0.012, 0.0 - i * 0.045 * k, 0); parts.push(l.toNonIndexed()); }
  const g = BGU.mergeGeometries(parts.map(p => { p.deleteAttribute('uv'); return p; })); g.computeVertexNormals(); return mesh(withAux(g), metalMaterial(o.color || [0.22, 0.22, 0.24], { rough: 0.42, rust: o.rust ?? 0.3 })); }
/** fur mantle around the shoulders (Garrick) */
export function furCollar(look, k = 1) { const t = new THREE.TorusGeometry(0.2 * k, 0.07 * k, 8, 20); t.rotateX(Math.PI / 2); t.scale(1.3, 0.62, 0.95); const p = t.attributes.position; const r = mulberry(4); for (let i = 0; i < p.count; i++) { const n = 1 + (r() - 0.5) * 0.25; p.setXYZ(i, p.getX(i) * n, p.getY(i) * n, p.getZ(i) * n); } t.computeVertexNormals(); withAux(t); return mesh(t, hairMaterial({ hair: look || [0.16, 0.14, 0.12], grey: 0.4 }, { mask: false })); }
export function bucket() { const g = new THREE.Group(); const b = new THREE.CylinderGeometry(0.15, 0.12, 0.26, 12, 1, true); g.add(mesh(b, propMat('wood', { color: [0.6, 0.5, 0.4], side: THREE.DoubleSide }))); const w = new THREE.CircleGeometry(0.14, 12); w.rotateX(-Math.PI / 2); w.translate(0, 0.09, 0); g.add(mesh(w, new THREE.MeshStandardMaterial({ color: 0x223840, roughness: 0.1 }))); return g; }
/** arrow along +Z with optional burning head */
export function arrowGeo() { const sh = new THREE.CylinderGeometry(0.006, 0.006, 0.8, 4); sh.rotateX(Math.PI / 2); const hd = new THREE.ConeGeometry(0.014, 0.06, 4); hd.rotateX(Math.PI / 2); hd.translate(0, 0, 0.43); const f = new THREE.BoxGeometry(0.002, 0.045, 0.1); f.translate(0, 0, -0.36); return BGU.mergeGeometries([sh.toNonIndexed(), hd.toNonIndexed(), f.toNonIndexed()].map(p => { p.deleteAttribute('uv'); return p; })); }

/* ---------- horse ---------- */
/** procedural horse: body lofted from rings, articulated legs/neck/head. pose with horse.userData.pose(phase, gait, o) */
function horseOld(o = {}) {
  const g = new THREE.Group(); const coat = C(...(o.color || [0.07, 0.05, 0.04])); const mat = new THREE.MeshStandardMaterial({ color: coat, roughness: 0.55 });
  const loft = (rings, seg = 12) => { const pos = [], idx = []; rings.forEach(([x, y, z, rx, ry]) => { for (let k = 0; k < seg; k++) { const a = k / seg * 6.283; pos.push(x + Math.cos(a) * rx, y + Math.sin(a) * ry, z); } }); for (let i = 0; i < rings.length - 1; i++) for (let k = 0; k < seg; k++) { const a = i * seg + k, b = i * seg + (k + 1) % seg, c = a + seg, d = b + seg; idx.push(a, b, c, b, d, c); } const gg = new THREE.BufferGeometry(); gg.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); gg.setIndex(idx); gg.computeVertexNormals(); return gg; };
  const body = mesh(loft([[0, 1.22, -0.98, 0.02, 0.02], [0, 1.24, -0.92, 0.2, 0.24], [0, 1.22, -0.62, 0.3, 0.33], [0, 1.16, -0.2, 0.31, 0.31], [0, 1.15, 0.2, 0.3, 0.32], [0, 1.2, 0.55, 0.28, 0.36], [0, 1.3, 0.8, 0.19, 0.3], [0, 1.36, 0.9, 0.02, 0.05]]), mat); g.add(body);
  const neckP = new THREE.Group(); neckP.position.set(0, 1.36, 0.74); g.add(neckP); const neck = mesh(loft([[0, -0.08, -0.06, 0.17, 0.26], [0, 0.22, 0.14, 0.13, 0.19], [0, 0.5, 0.33, 0.1, 0.14], [0, 0.62, 0.42, 0.085, 0.11]]), mat); neckP.add(neck);
  const headP = new THREE.Group(); headP.position.set(0, 0.62, 0.42); neckP.add(headP); const head = mesh(loft([[0, 0.06, -0.08, 0.09, 0.11], [0, 0.0, 0.1, 0.085, 0.115], [0, -0.14, 0.3, 0.06, 0.075], [0, -0.26, 0.46, 0.05, 0.055], [0, -0.3, 0.5, 0.01, 0.02]]), mat); headP.add(head);
  for (const s of [-1, 1]) { const ear = mesh(new THREE.ConeGeometry(0.03, 0.11, 5), mat); ear.position.set(s * 0.06, 0.16, -0.06); headP.add(ear); const eye = mesh(new THREE.SphereGeometry(0.018, 6, 5), new THREE.MeshStandardMaterial({ color: 0x050505, roughness: 0.1 }), false); eye.position.set(s * 0.078, 0.02, 0.08); headP.add(eye); }
  const mane = mesh(new THREE.BoxGeometry(0.035, 0.72, 0.16), new THREE.MeshStandardMaterial({ color: 0x050505, roughness: 0.8 })); mane.position.set(0, 0.3, 0.08); mane.rotation.x = 0.58; neckP.add(mane);
  const tailP = new THREE.Group(); tailP.position.set(0, 1.26, -0.95); g.add(tailP); const tl = new THREE.ConeGeometry(0.07, 0.95, 7); tl.translate(0, -0.47, 0); const tail = mesh(tl, new THREE.MeshStandardMaterial({ color: 0x050505, roughness: 0.8 })); tail.rotation.x = -0.35; tailP.add(tail);
  const legs = []; const legAt = [[0.17, 1.02, 0.58, 1], [-0.17, 1.02, 0.58, 1], [0.18, 1.05, -0.66, 0], [-0.18, 1.05, -0.66, 0]];
  for (const [x, y, z, front] of legAt) { const up = new THREE.Group(); up.position.set(x, y, z); g.add(up); const ul = front ? 0.46 : 0.5; const u1 = new THREE.CylinderGeometry(0.085, 0.05, ul, 8); u1.translate(0, -ul / 2, 0); up.add(mesh(u1, mat)); const lo = new THREE.Group(); lo.position.y = -ul; up.add(lo); const ll = front ? 0.5 : 0.5; const l1 = new THREE.CylinderGeometry(0.042, 0.033, ll, 7); l1.translate(0, -ll / 2, 0); lo.add(mesh(l1, mat)); const hf = new THREE.CylinderGeometry(0.045, 0.055, 0.08, 7); hf.translate(0, -ll - 0.02, 0.01); lo.add(mesh(hf, new THREE.MeshStandardMaterial({ color: 0x0a0a0a, roughness: 0.5 }))); legs.push({ up, lo, front }); }
  // saddle + bridle hints
  const sad = mesh(new THREE.BoxGeometry(0.5, 0.06, 0.5), new THREE.MeshStandardMaterial({ color: 0x1a0f0a, roughness: 0.7 })); sad.position.set(0, 1.49, -0.05); g.add(sad); const blanket = mesh(new THREE.BoxGeometry(0.66, 0.03, 0.72), new THREE.MeshStandardMaterial({ color: C(...(o.cloth || [0.35, 0.04, 0.04])), roughness: 0.9 })); blanket.position.set(0, 1.46, -0.05); g.add(blanket);
  g.userData.seat = V3(0, 1.52, -0.05);
  /** phase 0..1 through the stride; gait: 'gallop' | 'walk' | 'stand' | 'rear'; amount 0..1 */
  g.userData.pose = (ph, gait = 'gallop', amt = 1) => {
    const S = (x) => Math.sin(x * 6.283);
    if (gait === 'gallop') { body.position.y = S(ph * 1 + 0.1) * 0.07 * amt; g.rotation.x = S(ph + 0.35) * 0.06 * amt; neckP.rotation.x = 0.35 + S(ph + 0.6) * 0.12 * amt; headP.rotation.x = 0.25 - S(ph + 0.6) * 0.1; tailP.rotation.x = 1.1 + S(ph) * 0.2;
      const offs = [0.0, 0.12, 0.5, 0.62]; legs.forEach((l, i) => { const p = ph + offs[i]; const sw = S(p); const lift = Math.max(0, Math.sin((p % 1) * 6.283 + 1.2)); l.up.rotation.x = (l.front ? -sw * 0.75 - 0.1 : -sw * 0.65 + 0.15) * amt; l.lo.rotation.x = (l.front ? lift * 1.3 : -lift * 1.1 + 0.25 * (1 - lift)) * amt * (l.front ? 1 : -1) * (l.front ? 1 : -1); }); }
    else if (gait === 'walk') { body.position.y = S(ph * 2) * 0.012; g.rotation.x = 0; neckP.rotation.x = 0.25 + S(ph * 2) * 0.04; headP.rotation.x = 0.3; tailP.rotation.x = 0.25 + S(ph) * 0.08; const offs = [0.0, 0.5, 0.25, 0.75]; legs.forEach((l, i) => { const p = ph + offs[i]; const sw = S(p); const lift = Math.max(0, Math.sin((p % 1) * 6.283 + 1.2)); l.up.rotation.x = -sw * 0.32; l.lo.rotation.x = l.front ? lift * 0.6 : -lift * 0.45; }); }
    else if (gait === 'rear') { g.rotation.x = -0.75 * amt; body.position.y = 0; neckP.rotation.x = 0.1; headP.rotation.x = 0.5; tailP.rotation.x = 0.6; legs.forEach((l, i) => { if (l.front) { l.up.rotation.x = (-1.1 + S(ph + i * 0.3) * 0.35) * amt; l.lo.rotation.x = (1.5 + S(ph * 2 + i) * 0.3) * amt; } else { l.up.rotation.x = 0.7 * amt; l.lo.rotation.x = -0.15 * amt; } }); }
    else { body.position.y = 0; g.rotation.x = 0; neckP.rotation.x = 0.2 + S(ph * 0.3) * 0.05; headP.rotation.x = 0.35 + S(ph * 0.7) * 0.06; tailP.rotation.x = 0.2 + S(ph) * 0.15; legs.forEach((l, i) => { l.up.rotation.x = 0; l.lo.rotation.x = 0; }); }
  };
  g.userData.pose(0, 'stand'); g.traverse(x => { if (x.isMesh) { x.castShadow = true; } }); return g;
}

export { horse } from './horse.js';
