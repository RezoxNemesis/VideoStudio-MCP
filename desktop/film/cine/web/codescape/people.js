// PEHLI SALARY — the signature cast: Maa and the Son, upgraded from the Drafts rig.
import { THREE, V3, clamp, lerp, sm } from './engine.js';
import { Person } from './rig.js';
import { buildFace, faceZ, irisUpgrade } from './face2.js';

const M = (c, o = {}) => new THREE.MeshStandardMaterial({ color: c, roughness: o.r ?? 0.6, metalness: o.m ?? 0, ...(o.x || {}) });
const tube = (pts, r, m, seg = 40, rs = 8) => { const g = new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts.map(a => a.isVector3 ? a : new V3(...a))), seg, r, rs); const me = new THREE.Mesh(g, m); me.castShadow = true; return me; };
// a point on the hair surface: unit direction d, out = height above the skull
const onHair = (d, out = 0.016) => { d = d.clone().normalize(); return new V3(d.x * (0.148 + out), 0.08 + d.y * (0.156 + out), d.z * (0.146 * (d.z > 0 ? 0.97 : 1.03) + out)); };
const sph = (az, el) => new V3(Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el));
// a point on an ellipsoid mesh's surface (in its parent's frame), along the horizontal direction (dx, dz) at height y
function surf(mesh, r, y, dx, dz, pad = 0.006) { const a = r * mesh.scale.x, b = r * mesh.scale.y, c = r * mesh.scale.z, cy = mesh.position.y, cz = mesh.position.z; const v = clamp((y - cy) / b, -0.98, 0.98); const k = Math.sqrt(1 - v * v);
  const o = new V3(dx, 0, dz).normalize(); const A = (o.x / a) ** 2 + (o.z / c) ** 2, B = -2 * o.z * cz / (c * c), C = (cz / c) ** 2 - k * k; const R = (-B + Math.sqrt(B * B - 4 * A * C)) / (2 * A); return new V3(o.x * (R + pad), y, o.z * (R + pad)); }

export const MAAC = { size: 0.93, jaw: 0.2, cheek: 0.7, chin: 0.9, nose: 0.35, skin: '#c48660', shirt: '#8a2a34', shirt2: '#6e1e28', pants: '#7e2230', shoe: '#5a3020', hair: '#241814', eye: '#2a1608', lipc: '#9a4a44', browR: 0.0052, headS: [0.82, 0.86, 0.84], eyeS: 0.97, armK: 0.9, sleeveL: 0.78 };
export const SONC = { jaw: 0.17, cheek: 0.45, chin: 1.2, nose: 0.35, skin: '#c08660', shirt: '#a8c8e8', shirt2: '#94b6da', pants: '#363a44', shoe: '#2a1e16', hair: '#16100c', eye: '#22140a', lipc: '#8e5048', browR: 0.0068, sleeveL: 0.96, headS: [0.82, 0.86, 0.84], eyeS: 0.97, armK: 0.9 };

// arched, tapered brows that follow the forehead (thick at the inner end, fine at the tail)
function brows(p, thick, arch, col) { const m = new THREE.MeshStandardMaterial({ color: col, roughness: 0.9 }); for (const e of p.eyes) { const s = e.s, old = e.brow.children[0]; old.visible = false;
    const cv = new THREE.CatmullRomCurve3([[-0.026, -0.003, 0.002], [-0.01, 0.002, 0.001], [0.008, 0.002 + arch, -0.003], [0.024, -0.004 + arch * 0.4, -0.012], [0.032, -0.011, -0.02]].map(a => new V3(a[0] * s, a[1], a[2])));
    const g = new THREE.TubeGeometry(cv, 40, 1, 8); const q = g.attributes.position; for (let i = 0; i < q.count; i++) { const u = Math.floor(i / 9) / 40, c = cv.getPoint(u); const r = thick * lerp(1, 0.35, sm(u)); q.setXYZ(i, c.x + (q.getX(i) - c.x) * r, c.y + (q.getY(i) - c.y) * r * 0.9, c.z + (q.getZ(i) - c.z) * r * 0.45); }
    g.computeVertexNormals(); const b = new THREE.Mesh(g, m); e.brow.add(b); } }
// a shorter upper lip: lift the mouth a little toward the nose, so the face reads younger and warmer
function liftMouth(p, d, dz = 0.002) { p.mouth.position.y += d; p.mouth.position.z += dz; p.lipL.position.y += d; p.lipL.position.z += dz; }
const trimShoulders = (p, k) => { for (const s of ['l', 'r']) p.arm[s].sho.children[0].scale.multiplyScalar(k); };
export function makeMaa(E) { const p = new Person(MAAC).style('maa', { slim: 0.9, noButtons: 1, saree: '#7e2230', hair: '#241814' }); E.scene.add(p.root);
  const hm = p.head.children.find(o => o.isMesh && o.geometry.attributes.hl)?.material || M('#241814');
  const grey = new THREE.MeshPhysicalMaterial({ color: '#8a847e', roughness: 0.5, sheen: 0.6, sheenColor: new THREE.Color('#e0dcd6') });
  liftMouth(p, 0.005, 0.005); trimShoulders(p, 0.82); for (const o of p.sp2.children) if (o.isMesh && Math.abs(o.position.y - 0.165) < 0.001 && Math.abs(o.position.z + 0.01) < 0.001) { o.scale.multiply(new V3(0.8, 0.6, 0.8)); o.material = p.chest.material; } { const ring = p.neck.children.find(o => o.isMesh && o.geometry.type === 'TorusGeometry'); if (ring) ring.visible = false; }
  for (const m of p.belt) m.visible = false; brows(p, 0.0042, 0.004, '#2a1e18');
  // grey streaks: thin strands from the hairline over the crown and down the back to the bun
  for (const [x0, w] of [[0.05, 0.0016], [-0.11, 0.0014], [0.2, 0.0013], [-0.27, 0.0012], [0.36, 0.0012], [-0.42, 0.0011]]) { const pts = []; for (let i = 0; i <= 14; i++) { const th = lerp(1.08, 2.55, i / 14); const xs = x0 * (1 - 0.55 * Math.max(0, (i - 8) / 6)); pts.push(onHair(new V3(xs, Math.sin(th), Math.cos(th)), 0.0115)); }
    pts.push(new V3(x0 * 0.12, 0.075, -0.2)); p.head.add(tube(pts, w, grey, 60, 5)); }
  // a slimmer body: less belly; the saree wraps the waist, so the belly wears the saree
  p.belly.scale.multiply(new V3(0.86, 0.95, 0.8)); p.belly.position.z = 0.0; p.chest.scale.multiply(new V3(0.92, 1.02, 0.95));
  const sar = p.skirt.material; p.belly.material = sar;
  for (const s of ['l', 'r']) { const lg = p.leg[s]; for (const m of [...lg.th.children, ...lg.sn.children]) if (m.isMesh) m.visible = false; } // the saree hides the legs
  { const pts = [[0.128, 0.15], [0.162, 0.03], [0.172, -0.2], [0.182, -0.48], [0.196, -0.735]].map(a => new THREE.Vector2(...a)); p.skirt.geometry.dispose(); p.skirt.geometry = new THREE.LatheGeometry(pts, 56); p.skirt.scale.z = 0.86;
    const hem = p.pelvis.children.find(o => o.geometry?.type === 'TorusGeometry' && Math.abs(o.position.y + 0.725) < 0.01); if (hem) { hem.geometry.dispose(); hem.geometry = new THREE.TorusGeometry(0.196, 0.01, 8, 56); hem.scale.y = 0.86; }
    const R = y => y > 0.03 ? lerp(0.162, 0.128, (y - 0.03) / 0.12) : y > -0.2 ? lerp(0.162, 0.172, -(y - 0.03) / 0.23) : y > -0.48 ? lerp(0.172, 0.182, -(y + 0.2) / 0.28) : lerp(0.182, 0.196, -(y + 0.48) / 0.255);
    const fold = (a, r, m) => { const P = []; for (const y of [0.1, 0.0, -0.2, -0.45, -0.72]) { const k = clamp((0.1 - y) / 0.5, 0, 1), aa = a * lerp(0.55, 1, k); P.push(new V3(Math.sin(aa) * (R(y) + 0.003), y, Math.cos(aa) * (R(y) + 0.003))); } p.skirt.add(tube(P, r, m, 30, 6)); };
    for (let i = 0; i < 8; i++) fold(-0.3 + i * 0.075, 0.0075, sar); const bor = M('#d9a43a', { r: 0.35, m: 0.5 }); fold(-0.35, 0.005, bor); fold(0.27, 0.005, bor); }
  // the pallu: from the right hip, across the chest, over the left shoulder, down the back
  for (const m of [...p.sp2.children]) if (m.isMesh && m.material === sar) p.sp2.remove(m);
  { const ell = [[p.belly, 0.17, p.belly.position.y - 0.19], [p.chest, 0.165, p.chest.position.y]].map(([m, r, cy]) => ({ a: r * m.scale.x, b: r * m.scale.y, c: r * m.scale.z, cy, cz: m.position.z }));
    const onBody = (v, pad) => { const o = new V3(v.x, 0, v.z).normalize(); let R = 0; for (const e of ell) { const u = (v.y - e.cy) / e.b; if (Math.abs(u) > 1.2) continue; const dy = clamp(u, -0.95, 0.95); const A = (o.x / e.a) ** 2 + (o.z / e.c) ** 2, B = -2 * o.z * e.cz / (e.c ** 2), Cc = (e.cz / e.c) ** 2 + dy * dy - 1, D = B * B - 4 * A * Cc; if (D > 0) R = Math.max(R, (-B + Math.sqrt(D)) / (2 * A)); }
      const r0 = Math.hypot(v.x, v.z); return R + pad > r0 ? new V3(o.x * (R + pad), v.y, o.z * (R + pad)) : v; };
    const path = new THREE.CatmullRomCurve3([[-0.13, -0.24, 0.12], [-0.06, -0.12, 0.14], [0.03, 0.02, 0.15], [0.1, 0.13, 0.12], [0.135, 0.205, 0.04], [0.13, 0.2, -0.06], [0.12, 0.08, -0.14], [0.11, -0.12, -0.15], [0.1, -0.42, -0.14], [0.09, -0.62, -0.13]].map(a => new V3(...a)));
    const n = 90, W = 8, pos = [], idx = [], edge = [[], []];
    for (let i = 0; i <= n; i++) { const u = i / n, c = path.getPoint(u), t = path.getTangent(u); const out = new V3(c.x, 0, c.z).normalize(); const b = new V3().crossVectors(t, out).normalize(); const wd = lerp(0.12, 0.2, sm(u * 1.6)) * (1 - 0.35 * Math.exp(-(((u - 0.44) / 0.06) ** 2)));
      for (let j = 0; j <= W; j++) { const s = j / W - 0.5; let v = c.clone().addScaledVector(b, s * wd); v.addScaledVector(out, 0.004 * Math.sin(j * 1.9 + u * 9)); v = onBody(v, 0.012 + 0.002 * j); pos.push(v.x, v.y, v.z); if (j === 0) edge[0].push(v.clone()); if (j === W) edge[1].push(v.clone()); }
      if (i < n) for (let j = 0; j < W; j++) { const a = i * (W + 1) + j, d = a + W + 1; idx.push(a, a + 1, d, a + 1, d + 1, d); } }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setIndex(idx); g.computeVertexNormals(); const pm = sar.clone(); pm.side = THREE.DoubleSide; const me = new THREE.Mesh(g, pm); me.castShadow = true; p.sp2.add(me); p.pallu = me;
    const bor = M('#d9a43a', { r: 0.35, m: 0.5 }); for (const E of edge) p.sp2.add(tube(E.filter((_, i) => i % 3 === 0), 0.0045, bor, 90, 5));
    { const uv = []; for (let i = 0; i <= n; i++) for (let j = 0; j <= W; j++) uv.push(i / n * 6, j / W); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); } }
  // blouse sleeves end above the elbow (the rig's sleeve is the blouse)
  refineMaa(p); looksMaa(p); faceMaa(p); blouseMaa(p, E); return p; }

export function makeSon(E, o = {}) { const p = new Person(o.skin ? { ...SONC, skin: o.skin } : SONC).style('boy', { slim: o.slim ?? 0.86, hair: '#16100c' }); E.scene.add(p.root);
  for (const m of p.belt) m.visible = false; for (const b of p.buttons) b.visible = false; const sp2 = p.sp2, ch = p.chest; brows(p, 0.0062, 0.003, '#18100c'); liftMouth(p, 0.005, 0.005); trimShoulders(p, o.shoulders ?? 0.86);
  p.belly.scale.multiply(new V3(0.84, 0.97, 0.74)); p.belly.position.z = -0.005; p.chest.scale.multiply(new V3(0.9, 1.04, 0.9));
  const shirt = M('#a8c8e8', { r: 0.75 }), shirtD = M('#8fb0d4', { r: 0.75 }), btn = M('#f4f4f0', { r: 0.3 });
  // a proper collar: the band round the neck, and two flat points resting on the chest
  for (const s of [-1, 1]) { const sh = new THREE.Shape([new THREE.Vector2(0, 0), new THREE.Vector2(s * 0.062, 0.01), new THREE.Vector2(s * 0.018, -0.052)]); const g = new THREE.ExtrudeGeometry(sh, { depth: 0.004, bevelEnabled: true, bevelThickness: 0.002, bevelSize: 0.002, bevelSegments: 2 });
    const pt = new THREE.Mesh(g, shirt); const a = surf(ch, 0.165, 0.205, s * 0.05, 1, 0.004); pt.position.copy(a).add(new V3(-s * 0.012, 0.004, 0.0)); pt.rotation.set(-0.55, s * 0.28, 0); pt.castShadow = true; sp2.add(pt); }
  // placket and buttons down the chest to the belly
  const pk = []; for (let i = 0; i <= 8; i++) { const y = 0.19 - i * 0.03; pk.push(surf(ch, 0.165, y, 0, 1, 0.004)); } sp2.add(tube(pk, 0.006, shirtD, 30, 5));
  for (let i = 0; i < 3; i++) { const b = new THREE.Mesh(new THREE.SphereGeometry(0.0055, 10, 8), btn); b.position.copy(surf(ch, 0.165, 0.15 - i * 0.055, 0, 1, 0.009)); b.scale.z = 0.5; sp2.add(b); }
  for (let i = 0; i < 2; i++) { const b = new THREE.Mesh(new THREE.SphereGeometry(0.0055, 10, 8), btn); b.position.copy(surf(p.belly, 0.17, 0.12 - i * 0.065, 0, 1, 0.006)); b.scale.z = 0.5; p.sp1.add(b); }
  // ID card on a lanyard
  const lan = M('#c0283a', { r: 0.6 }); const top = [-0.06, 0.22, -0.02]; const cardC = surf(ch, 0.165, 0.035, 0.0, 1, 0.012);
  for (const s of [-1, 1]) sp2.add(tube([[s * 0.06, 0.226, -0.045], [s * 0.07, 0.205, 0.04], surf(ch, 0.165, 0.13, s * 0.35, 1, 0.006), surf(ch, 0.165, 0.075, s * 0.15, 1, 0.009), cardC.clone().add(new V3(s * 0.01, 0.05, 0))], 0.0032, lan, 30, 5));
  const cv = typeof document !== 'undefined' ? document.createElement('canvas') : null; if (cv) { cv.width = 160; cv.height = 240; const g = cv.getContext('2d'); g.fillStyle = '#f6f6f2'; g.fillRect(0, 0, 160, 240); g.fillStyle = '#1e5aa8'; g.fillRect(0, 0, 160, 54); g.fillStyle = '#fff'; g.font = 'bold 30px sans-serif'; g.textAlign = 'center'; g.fillText('TECHNO', 80, 38);
  g.fillStyle = '#c9a080'; g.fillRect(45, 70, 70, 80); g.fillStyle = '#3a2a20'; g.fillRect(45, 70, 70, 26); g.fillStyle = '#222'; g.font = 'bold 22px sans-serif'; g.font = 'bold 18px sans-serif'; g.fillText('VIKAS TANDON', 80, 182); g.font = '16px sans-serif'; g.fillStyle = '#666'; g.fillText('TRAINEE', 80, 208); g.fillStyle = '#1e5aa8'; g.fillRect(0, 226, 160, 14);
  } const tx = cv ? new THREE.CanvasTexture(cv) : null; if (tx) tx.colorSpace = THREE.SRGBColorSpace; const cm = M('#ffffff', { r: 0.35 }); const card = new THREE.Mesh(new THREE.BoxGeometry(0.054, 0.08, 0.003), [cm, cm, cm, cm, new THREE.MeshStandardMaterial({ map: tx, roughness: 0.35 }), cm]);
  card.position.copy(cardC).add(new V3(0, 0, 0.004)); card.rotation.x = -0.12; sp2.add(card); p.card = card; const clip = new THREE.Mesh(new THREE.BoxGeometry(0.014, 0.012, 0.006), M('#888', { m: 0.8, r: 0.3 })); clip.position.copy(card.position).add(new V3(0, 0.046, 0.002)); sp2.add(clip);
  // backpack: dark grey, two straps over the shoulders
  const bm = M('#3a3e48', { r: 0.7 }), bm2 = M('#2a2d34', { r: 0.7 }); const back = surf(ch, 0.165, 0.07, 0, -1, 0); const bp = new THREE.Mesh(new THREE.BoxGeometry(0.27, 0.36, 0.11, 4, 4, 2), bm);
  { const q = bp.geometry.attributes.position; for (let i = 0; i < q.count; i++) { const x = q.getX(i), y = q.getY(i), z = q.getZ(i); const r = 1 - 0.18 * ((x / 0.135) ** 4 + (y / 0.18) ** 4) * 0.5; q.setXYZ(i, x * (0.92 + 0.08 * r), y, z * r); } bp.geometry.computeVertexNormals(); }
  const n0 = sp2.children.length; bp.position.set(0, -0.02, back.z - 0.06); sp2.add(bp); const pocket = new THREE.Mesh(new THREE.BoxGeometry(0.2, 0.13, 0.04), bm2); pocket.position.set(0, -0.12, back.z - 0.125); sp2.add(pocket); p.backpack = bp;
  const zip = tube([[-0.12, 0.15, back.z - 0.115], [0, 0.175, back.z - 0.118], [0.12, 0.15, back.z - 0.115]], 0.004, M('#111', { r: 0.4 }), 20, 4); sp2.add(zip);
  for (const o of [...sp2.children]) if (o.isMesh && Math.abs(o.position.y - 0.165) < 0.001 && Math.abs(o.position.z + 0.01) < 0.001) o.scale.multiply(new V3(0.85, 0.7, 0.85)); // a flatter yoke
  for (const s of [-1, 1]) { const F = (y, dx, dz = 1, pad = 0.011) => surf(ch, 0.165, y, dx, dz, pad);
    const P = [[s * 0.085, -0.1, back.z - 0.004], [s * 0.1, 0.1, back.z + 0.012], [s * 0.112, 0.205, -0.1], [s * 0.118, 0.236, -0.02], F(0.2, s * 0.5), F(0.13, s * 0.58), F(0.05, s * 0.66), F(-0.02, s * 0.95, 0.75), [s * 0.17, -0.06, -0.06], [s * 0.13, -0.12, back.z + 0.02]];
    const st = tube(P, 1, bm2, 80, 8); { const q = st.geometry.attributes.position, cv2 = st.geometry.parameters.path; for (let i = 0; i < q.count; i++) { const u = Math.floor(i / 9) / 80, c = cv2.getPoint(u), t = cv2.getTangent(u); const out = new V3(c.x, (c.y > 0.2 ? 1.5 : 0) * (c.y - 0.2), c.z).normalize(); const d = new V3(q.getX(i), q.getY(i), q.getZ(i)).sub(c); const k = d.dot(out); d.addScaledVector(out, -k * 0.72); d.multiplyScalar(0.011); q.setXYZ(i, c.x + d.x, c.y + d.y, c.z + d.z); } st.geometry.computeVertexNormals(); } sp2.add(st); }
  p.backpackParts = sp2.children.slice(n0); p.bagOff = () => p.backpackParts.forEach(m => m.visible = false);
  // light stubble: a darker tint on the jaw (its own copy of the skin, so hands stay clean)
  const hd = p.headM; const sk = hd.material.clone(); sk.onBeforeCompile = sh => { sh.vertexShader = 'varying vec3 vP;\n' + sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\n vP = position;');
    sh.fragmentShader = 'varying vec3 vP;\nfloat hh(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n' + sh.fragmentShader.replace('#include <color_fragment>', `#include <color_fragment>
      float jw = smoothstep(0.035, -0.01, vP.y) * smoothstep(-0.02, 0.06, vP.z) * (1.0 - smoothstep(0.1, 0.135, abs(vP.x)));
      float lipz = 1.0 - smoothstep(0.012, 0.03, length(vec2(vP.x * 0.75, vP.y + 0.012))) ; float mo = smoothstep(0.075, 0.03, abs(vP.x)) * smoothstep(0.035, 0.012, vP.y) * smoothstep(-0.01, 0.006, vP.y);
      float st = clamp(jw + mo * 0.8, 0.0, 1.0) * (1.0 - lipz) * (0.6 + 0.4 * hh(floor(vP.xy * 900.0)));
      diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * vec3(0.62, 0.6, 0.62), st * 0.55);`); };
  if (!o.kid) hd.material = sk; refineSon(p, o); looksSon(p); faceSon(p, o); trousersSon(p, E); shirtSon(p, E); if (o.kid) kidUniform(p); return p; }

// ================================================================== REFINE: structure in the faces, slimmer hands, real skin and cloth (25 Nov 2026 upgrade)
const gs = (x, y, z, cx, cy, cz, sx, sy, sz) => Math.exp(-(((x - cx) / sx) ** 2 + ((y - cy) / sy) ** 2 + ((z - cz) / sz) ** 2));
function noiseTex(n, base, amp, seed = 1, cell = 1) { const c = document.createElement('canvas'); c.width = c.height = n; const g = c.getContext('2d'); g.fillStyle = base; g.fillRect(0, 0, n, n); let s = seed; const R = () => (s = (s * 16807) % 2147483647) / 2147483647;
  for (let i = 0; i < n * n / 6; i++) { const v = Math.floor(128 + (R() - 0.5) * amp); g.fillStyle = `rgb(${v},${v},${v})`; g.fillRect(R() * n, R() * n, cell, cell); } const t = new THREE.CanvasTexture(c); t.wrapS = t.wrapT = THREE.RepeatWrapping; return t; }
const browser = typeof document !== 'undefined' && document.createElement('canvas').getContext('2d')?.fillRect;
/** sculpt the head further: brow ridge, cheekbones and the hollows under them, temples, jaw angle, chin, nostril wings; age lines for Maa */
function sculpt(p, o) { const g = p.headM.geometry, P = g.attributes.position, N = P.count;
  for (let i = 0; i < N; i++) { const x = P.getX(i), y = P.getY(i), z = P.getZ(i); if (z < -0.02 && y < 0.2) { } let d = 0; const ax = Math.abs(x);
    d += o.brow * gs(ax, y, z, 0.045, 0.147, 0.13, 0.034, 0.011, 0.03);            // brow ridge
    d += o.cheek * gs(ax, y, z, 0.083, 0.062, 0.105, 0.03, 0.018, 0.04);           // cheekbone
    d -= o.hollow * gs(ax, y, z, 0.09, 0.005, 0.09, 0.03, 0.025, 0.04);            // under the cheekbone
    d -= 0.003 * gs(ax, y, z, 0.13, 0.13, 0.05, 0.025, 0.03, 0.04);                // temples
    d += o.jaw * gs(ax, y, z, 0.112, -0.03, 0.02, 0.025, 0.03, 0.05);              // jaw angle
    d += o.chin * gs(ax, y, z, 0.0, -0.078, 0.1, 0.022, 0.014, 0.03);              // chin
    d += 0.0028 * gs(ax, y, z, 0.018, 0.05, 0.14, 0.01, 0.009, 0.015);             // nostril wings
    d -= 0.0016 * gs(ax, y, z, 0.0, 0.025, 0.143, 0.005, 0.01, 0.012);             // philtrum groove
    if (o.age) { for (let k = 0; k <= 6; k++) { const u = k / 6; d -= o.age * 0.0011 * gs(ax, y, z, lerp(0.028, 0.048, u), lerp(0.046, -0.012, u), lerp(0.135, 0.12, u), 0.004, 0.006, 0.01); }   // nasolabial fold
      d += o.age * 0.0012 * gs(ax, y, z, 0.05, 0.072, 0.124, 0.016, 0.006, 0.012); }                    // soft under-eye
    const r = new V3(x, y - 0.08, z).normalize(); P.setXYZ(i, x + r.x * d, y + r.y * d, z + r.z * d); }
  g.computeVertexNormals(); }
function refine(p, o) {
  // skin: soft, warm, matte — no plastic shine; a fine pore noise in the bump
  for (const m of [p.mSkin, p.headM.material]) { m.roughness = 0.74; m.clearcoat = 0; m.sheen = 0.45; m.sheenRoughness = 0.55; m.sheenColor = new THREE.Color('#ff8a70'); m.specularIntensity = 0.35; if (browser) { const t = noiseTex(256, '#808080', 60, 7); t.repeat.set(6, 6); m.bumpMap = t; m.bumpScale = 0.4; } m.needsUpdate = true; }
  sculpt(p, o);
  // the head's own shading: eye sockets, the shadow under the nose and lower lip, warmth on the cheeks, a darker underside of the jaw; light stubble for the son
  const hm = p.mSkin.clone(); hm.onBeforeCompile = sh => { sh.vertexShader = 'varying vec3 vP;\n' + sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\n vP = position;');
    sh.fragmentShader = 'varying vec3 vP;\nfloat G3(vec3 p, vec3 c, vec3 s){ vec3 d = (p - c) / s; return exp(-dot(d, d)); }\nfloat hh(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n' + sh.fragmentShader.replace('#include <color_fragment>', `#include <color_fragment>
      vec3 q = vec3(abs(vP.x), vP.y, vP.z);
      float sock = G3(q, vec3(0.05, 0.1, 0.12), vec3(0.032, 0.022, 0.025)); float nose = G3(q, vec3(0.022, 0.055, 0.135), vec3(0.012, 0.012, 0.02)); float lip = G3(q, vec3(0.0, -0.045, 0.125), vec3(0.03, 0.01, 0.02));
      float cheek = G3(q, vec3(0.08, 0.035, 0.11), vec3(0.03, 0.025, 0.03)); float under = smoothstep(-0.03, -0.09, vP.y) * smoothstep(0.02, -0.06, vP.z + 0.0) ;
      diffuseColor.rgb *= 1.0 - ${(o.sock ?? 0.2).toFixed(2)} * sock - 0.16 * nose - 0.16 * lip - 0.3 * under;
      diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.85, 0.42, 0.36), ${(o.blush ?? 0.12).toFixed(2)} * cheek);
      ${o.stubble ? `float jw = smoothstep(0.035, -0.015, vP.y) * smoothstep(-0.02, 0.06, vP.z) * (1.0 - smoothstep(0.1, 0.135, abs(vP.x))); float mo = smoothstep(0.06, 0.03, abs(vP.x)) * smoothstep(0.035, 0.015, vP.y) * smoothstep(-0.005, 0.01, vP.y);
      float lz = 1.0 - smoothstep(0.012, 0.03, length(vec2(vP.x * 0.75, vP.y + 0.012))); float st = clamp(jw, 0.0, 1.0) * (1.0 - lz) * (0.5 + 0.5 * hh(floor(vP.xy * 1400.0)));
      diffuseColor.rgb *= 1.0 - 0.16 * st;` : ''}`); };
  p.headM.material = hm;
  // slimmer upper arms and shoulders (no puffer look)
  for (const s of ['l', 'r']) { const A = p.arm[s]; A.sho.children[0].scale.multiplyScalar(0.85); const ups = A.up.children.filter(m => m.isMesh); if (ups[0]) ups[0].scale.multiply(new V3(0.84, 1, 0.84)); if (ups[1]) ups[1].scale.multiplyScalar(0.86); }
  // slimmer, longer fingers and a flatter palm
  for (const s of ['l', 'r']) { const h = p.arm[s].hand; for (const segs of h.fingers) for (const j of segs) for (const m of j.children) if (m.isMesh && m.geometry.type === 'CapsuleGeometry') m.scale.set(0.84, 1.06, 0.84);
    for (const j of h.thumb) for (const m of j.children) if (m.isMesh && m.geometry.type === 'CapsuleGeometry') m.scale.set(0.88, 1, 0.88);
    const palm = p.arm[s].ha.children.find(m => m.isMesh && m.geometry.type === 'SphereGeometry'); if (palm) palm.scale.multiply(new V3(0.92, 1.02, 0.85)); }
  // a waist that tapers into the chest (no peanut torso): a lathe shell in the clothing
  if (o.shell) { const pts = o.shell.map(a => new THREE.Vector2(a[0], a[1])); const sh = new THREE.Mesh(new THREE.LatheGeometry(pts, 48), o.shellMat); sh.scale.set(1, 1, o.depth ?? 0.74); sh.position.z = o.dz ?? 0; sh.castShadow = sh.receiveShadow = true; p.sp1.add(sh); p.shell = sh; }
}
/** cloth with a weave and soft creases (a texture in the map and the bump) */
function clothTex(base, line, seed, stripes = 0) { if (!browser) return null; const n = 512; const c = document.createElement('canvas'); c.width = c.height = n; const g = c.getContext('2d'); g.fillStyle = base; g.fillRect(0, 0, n, n);
  let s = seed; const R = () => (s = (s * 16807) % 2147483647) / 2147483647; for (let i = 0; i < 14000; i++) { g.fillStyle = `rgba(${R() < 0.5 ? '0,0,0' : '255,255,255'},${0.025 + R() * 0.03})`; g.fillRect(R() * n, R() * n, 1 + R() * 3, 1); }
  if (stripes) { g.strokeStyle = line; g.lineWidth = 2; for (let x = 0; x < n; x += stripes) { g.beginPath(); g.moveTo(x, 0); g.lineTo(x, n); g.stroke(); } }
  for (let i = 0; i < 10; i++) { const y = R() * n, gr = g.createLinearGradient(0, y - 20, 0, y + 20); gr.addColorStop(0, 'rgba(0,0,0,0)'); gr.addColorStop(0.5, 'rgba(0,0,0,0.06)'); gr.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = gr; g.fillRect(0, y - 20, n, 40); }
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; return t; }
/** the saree's own print: small gold buttis on the maroon, a woven border motif */
function sareeTex() { if (!browser) return null; const n = 512; const c = document.createElement('canvas'); c.width = c.height = n; const g = c.getContext('2d'); g.fillStyle = '#7e2230'; g.fillRect(0, 0, n, n);
  for (let i = 0; i < 9000; i++) { g.fillStyle = `rgba(${Math.random() < 0.5 ? '0,0,0' : '255,200,200'},0.04)`; g.fillRect(Math.random() * n, Math.random() * n, 2, 1); }
  for (let y = 16; y < n; y += 64) for (let x = (y / 64) % 2 ? 48 : 16; x < n; x += 64) { g.fillStyle = '#d9a43a'; g.beginPath(); g.ellipse(x, y, 5, 8, 0.5, 0, 7); g.fill(); g.fillStyle = '#e8c060'; g.beginPath(); g.arc(x + 4, y - 7, 2, 0, 7); g.fill(); }
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; return t; }
export function refineMaa(p) {
  const sar = p.skirt.material; const st = sareeTex(); if (st) { st.repeat.set(5, 6); sar.map = st; sar.color.set('#ffffff'); sar.needsUpdate = true; if (p.pallu) { p.pallu.material.map = st; p.pallu.material.color.set('#ffffff'); p.pallu.material.needsUpdate = true; } }
  const bl = clothTex('#8a2a34', '#7a2028', 11); if (bl) for (const m of [p.chest.material]) { m.map = bl; m.color.set('#ffffff'); m.needsUpdate = true; }
  refine(p, { brow: 0.006, cheek: 0.006, hollow: 0.0025, jaw: 0.0025, chin: 0.004, age: 0.9, sock: 0.38, blush: 0.16, shell: [[0.0, -0.06], [0.13, -0.05], [0.128, 0.04], [0.13, 0.12], [0.11, 0.17], [0.0, 0.19]], shellMat: sar, depth: 0.78 }); }
export function refineSon(p, o = {}) {
  const sh = clothTex('#a8c8e8', 'rgba(60,90,140,0.18)', 5, 9); if (sh) { sh.repeat.set(3, 3); for (const m of [p.chest.material, p.belly.material]) { m.map = sh; m.color.set('#ffffff'); m.needsUpdate = true; } }
  const pa = clothTex('#363a44', '#2a2d34', 9); if (pa) { pa.repeat.set(2, 4); const pm = p.leg.l.th.children[0].material; pm.map = pa; pm.color.set('#ffffff'); pm.needsUpdate = true; }
  refine(p, { brow: 0.007, cheek: 0.005, hollow: 0.0025, jaw: 0.007, chin: 0.007, age: 0, sock: 0.34, blush: 0.08, stubble: o.kid ? 0 : 1, shell: [[0.0, -0.07], [0.142, -0.06], [0.14, 0.04], [0.15, 0.14], [0.162, 0.24], [0.14, 0.3], [0.0, 0.33]], shellMat: p.belly.material, depth: 0.7, dz: -0.005 }); }

// ================================================================== LOOKS v3: a modelled nose, finer lips, hair in strands, a longer neck
function taperTube(pts, r0, r1, m, seg = 40) { const cv = new THREE.CatmullRomCurve3(pts); const g = new THREE.TubeGeometry(cv, seg, 1, 6); const q = g.attributes.position;
  for (let i = 0; i < q.count; i++) { const u = Math.floor(i / 7) / seg, c = cv.getPoint(u); const r = lerp(r0, r1, u) * (u < 0.08 ? 0.6 + 5 * u : 1); q.setXYZ(i, c.x + (q.getX(i) - c.x) * r, c.y + (q.getY(i) - c.y) * r, c.z + (q.getZ(i) - c.z) * r); } g.computeVertexNormals(); const me = new THREE.Mesh(g, m); me.castShadow = true; return me; }
function nose(p, o) { const m = p.mSkin, g = new THREE.Group(); p.head.add(g); const s = o.s ?? 1;
  const br = taperTube([new V3(0, 0.128, 0.132), new V3(0, 0.105, 0.143), new V3(0, 0.082, 0.153 * (o.len ?? 1))], 0.0085 * s, 0.0105 * s, m, 16); g.add(br);
  const tip = new THREE.Mesh(new THREE.SphereGeometry(0.0125 * s, 20, 14), m); tip.position.set(0, 0.07, 0.156 * (o.len ?? 1)); tip.scale.set(1.12, 0.92, 0.95); g.add(tip);
  for (const sg of [-1, 1]) { const al = new THREE.Mesh(new THREE.SphereGeometry(0.0095 * s, 16, 12), m); al.position.set(sg * 0.0148 * s, 0.064, 0.146); al.scale.set(0.95, 0.78, 0.95); g.add(al);
    const ns = new THREE.Mesh(new THREE.SphereGeometry(0.0042 * s, 10, 8), new THREE.MeshStandardMaterial({ color: '#3a1a12', roughness: 0.9 })); ns.position.set(sg * 0.0075 * s, 0.0605, 0.151 * (o.len ?? 1)); ns.scale.set(1.1, 0.45, 1.0); g.add(ns); }
  for (const c of g.children) c.castShadow = c.receiveShadow = true; return g; }
function lips(p, o) { for (const L of [p.lipU, p.lipL]) { L.scale.set(o.w ?? 0.86, L === p.lipU ? 0.62 : 0.66, 0.72); } p.mLip.color.set(o.col); p.mLip.roughness = 0.55; }
/** hair as many tapered strands over the cap: o.from(i, R) gives the root, o.flow(root, i, R) the path */
function strands(p, n, mat, path, r = 0.0055) { const g = new THREE.Group(); p.head.add(g); let s = 11; const R = () => (s = (s * 16807) % 2147483647) / 2147483647; for (let i = 0; i < n; i++) { const pts = path(i, R); g.add(taperTube(pts, r * (0.8 + 0.4 * R()), r * 0.2, mat, 24)); } return g; }
const sbez = (a, b, c, u) => a.clone().multiplyScalar((1 - u) * (1 - u)).addScaledVector(b, 2 * u * (1 - u)).addScaledVector(c, u * u).normalize();
const dirAt = (az, el) => new V3(Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el));
const hairMatOf = p => p.head.children.find(o => o.isMesh && o.geometry.attributes.hl).material;
export function looksMaa(p) { p.noseG = nose(p, { s: 0.92 }); const hm = hairMatOf(p);
  // combed back from the centre parting into the bun: strands from the hairline over the head to the nape
  strands(p, 120, hm, (i, R) => { const sd = i % 2 ? 1 : -1, az = sd * (0.04 + R() * 1.3); const el = lerp(0.98, 0.5, Math.abs(az) / 1.3) + 0.02; const r0 = dirAt(az, el);
    const mid = r0.clone().add(new V3(0, 0.9, -0.5)).normalize(), end = new V3(sd * 0.22 * Math.abs(Math.sin(az)) + sd * 0.03, -0.03, -1).normalize(); const pts = [];
    for (let k = 0; k <= 10; k++) { const u = k / 10; pts.push(onHair(sbez(r0, mid, end, u), 0.012 + 0.004 * Math.sin(Math.PI * u))); } pts.push(new V3(sd * 0.012, 0.078, -0.17)); return pts; }, 0.0042);
  // the bun: wound strands
  const bun = new V3(0, 0.075, -0.168); for (let i = 0; i < 26; i++) { const a0 = i * 0.73, pts = []; for (let k = 0; k <= 16; k++) { const a = a0 + k * 0.42, rr = 0.062 - k * 0.0028; pts.push(bun.clone().add(new V3(Math.cos(a) * rr, Math.sin(a) * rr * 0.9, -0.018 - Math.sin(k / 16 * Math.PI) * 0.03))); } p.head.add(taperTube(pts, 0.009, 0.004, hm, 40)); }
  return p; }
export function looksSon(p) { p.noseG = nose(p, { s: 1.0, len: 1.01 }); const hm = hairMatOf(p);
  // a side part on his right; the front lifts and sweeps over to the left; the crown falls back
  if (0) strands(p, 150, hm, (i, R) => { const az = (R() * 2 - 1) * 1.35; const el = lerp(1.02, 0.55, Math.abs(az) / 1.35) + 0.02 + (i > 100 ? 0.35 + R() * 0.2 : 0); const r0 = dirAt(az, el);
    const side = az < -0.25 ? -1 : 1; const end = (i > 100 ? r0.clone().add(new V3(0, -0.4, -1.0)) : r0.clone().add(new V3(side * 0.55, 0.35, -0.9))).normalize(); const mid = r0.clone().lerp(end, 0.5).add(new V3(0, 0.25, 0)).normalize(); const pts = [];
    for (let k = 0; k <= 8; k++) { const u = k / 8; pts.push(onHair(sbez(r0, mid, end, u), 0.02 + 0.018 * Math.sin(Math.PI * Math.min(1, u * 1.2)) * (i > 100 ? 0.5 : 1))); } return pts; }, 0.011);
  return p; }

// ================================================================== FACE v4: one sculpted surface, a mouth that opens (see face2.js)
const onFace = p => { for (const c of p.head.children) if (c.isMesh && c.geometry.type === 'SphereGeometry') { const r = c.geometry.parameters.radius;
  if (Math.abs(r - 0.0045) < 1e-5) c.position.set(0.0172, 0.049, faceZ(p, 0.0172, 0.049) + 0.0015);          // nose pin on the wing
  if (Math.abs(r - 0.008) < 1e-5 && c.position.y > 0.15) c.position.z = faceZ(p, 0, 0.158) + 0.0006; } };    // bindi on the brow
const slimNeck = (p, k) => { for (const c of p.neck.children) if (c.isMesh && c.geometry.type === 'CapsuleGeometry') c.scale.set(k * 1.08, 0.8, k * 1.08); p.head.position.y -= 0.022; };
export function faceMaa(p) { slimNeck(p, 0.82); shoulders(p, 0.6); buildFace(p, { jaw: 0.3, mouthW: 0.031, skin: MAAC.skin, lip: '#8a4646', lipA: 0.75, lipU: 0.0026, lipL: 0.0032, cheekY: 0.045, cheek: 0.008, chin: 0.005, age: 0.9, sock: 0.1, blush: 0.14, nose: 0.95 }); onFace(p); irisUpgrade(p, '#8a5a30', '#4a2a12'); hairMaa(p); sleevesMaa(p); sareeMaa(p); finerHands(p); widowMaa(p); }
export function faceSon(p, o = {}) { buildFace(p, { jaw: 0.32, mouthW: 0.03, skin: o.skin || SONC.skin, lip: '#7e4a42', lipA: 0.72, lipU: 0.0026, lipL: 0.003, cheek: 0.004, cheekY: 0.05, chin: 0.01, jawA: 0.0012, stubble: o.kid ? 0 : 1.5, sock: 0.08, blush: o.kid ? 0.14 : 0.09, ...(o.face || {}) }); slimNeck(p, 0.84); shoulders(p, 0.56); irisUpgrade(p, '#7a4a22', '#3a200c'); hairSon(p); hairShell(p); sleevesSon(p); finerHands(p); }

// ================================================================== HAIR v4: thick wavy clumps (flat ribbons that taper), the way a feature-film groom reads
/** one clump: a tapered tube along pts, flattened against the head (w wide, t thick), wavy sideways */
function clump(pts, w, t, m, o = {}) { const cv = new THREE.CatmullRomCurve3(pts); const S = o.seg ?? 30, RS = 8; const g = new THREE.TubeGeometry(cv, S, 1, RS); const q = g.attributes.position; const C0 = new V3(0, 0.08, 0);
  for (let i = 0; i < q.count; i++) { const u = Math.floor(i / (RS + 1)) / S, c = cv.getPoint(u), tg = cv.getTangent(u); const out = c.clone().sub(C0).normalize(); const side = new V3().crossVectors(tg, out).normalize();
    const d = new V3(q.getX(i), q.getY(i), q.getZ(i)).sub(c); const k = d.dot(out), s = d.dot(side); const taper = Math.pow(Math.sin(Math.PI * Math.min(1, 0.12 + u * 0.95)), 0.6) * (1 - 0.65 * u);
    const wave = (o.wave ?? 0.004) * Math.sin((u * (o.waves ?? 1.6) + (o.ph ?? 0)) * Math.PI * 2) * u;
    const v = c.clone().addScaledVector(side, s * w * taper + wave).addScaledVector(out, k * w * t * taper); q.setXYZ(i, v.x, v.y, v.z); }
  g.computeVertexNormals(); const me = new THREE.Mesh(g, m); me.castShadow = true; me.receiveShadow = true; return me; }
export function hairSon(p) { const hm0 = hairMatOf(p); const G2 = new THREE.Group(); p.head.add(G2); let s = 7; const R = () => (s = (s * 16807) % 2147483647) / 2147483647;
  const tones = ['#1a120d', '#241810', '#120c08', '#2e1e14'].map(c => { const m = hm0.clone(); m.color = new THREE.Color(c); m.side = THREE.DoubleSide; return m; }); const hm = () => tones[Math.floor(R() * tones.length)];
  // a modest base under the locks (fills gaps, keeps the sides close)
  for (const c of p.head.children) if (c.isMesh && c.geometry.attributes.hl) c.visible = false;
  const vol = new THREE.Mesh(new THREE.SphereGeometry(0.15, 48, 32), tones[2]); vol.scale.set(1.02, 0.58, 1.02); vol.position.set(0.004, 0.155, -0.03); vol.rotation.x = -0.12; G2.add(vol);
  const P = (az, el, out) => onHair(dirAt(az, el), out);
  // top: many narrow wavy locks from the front hairline, swept up and back over the head (wide, full), a little to his left
  for (let i = 0; i < 90; i++) { const az = (R() * 2 - 1) * 1.15, el0 = 0.93 + 0.1 * R() - 0.07 * Math.abs(az); const r0 = dirAt(az, el0); const end = dirAt(Math.PI - az * 0.5 + 0.25, 0.75 + 0.3 * R());
    const mid = r0.clone().add(end).normalize().add(new V3(0.12, 0.6, 0.2)).normalize(); const hi = 0.026 + 0.016 * R(), span = 0.55 + 0.35 * R(); const pts = [];
    for (let k = 0; k <= 7; k++) { const u = k / 7 * span; pts.push(onHair(sbez(r0, mid, end, u), 0.01 + hi * Math.sin(Math.PI * Math.min(1, u * 1.6 + 0.1)) * (1 - 0.3 * u))); }
    G2.add(clump(pts, 0.013 + 0.008 * R(), 0.7, hm(), { wave: 0.006, waves: 1.2 + 0.8 * R(), ph: R() })); }
  // crown to the back: falls to the nape with a soft wave
  for (let i = 0; i < 60; i++) { const az = Math.PI + (R() * 2 - 1) * 1.55, el0 = 1.2 + 0.2 * R(); const pts = [P(az, el0, 0.026), P(az, el0 - 0.35, 0.028), P(az + 0.04, el0 - 0.72, 0.022), P(az + 0.08, el0 - 1.05, 0.012)];
    G2.add(clump(pts, 0.016 + 0.008 * R(), 0.7, hm(), { wave: 0.005, waves: 1.2, ph: R() })); }
  // sides: short, combed back over the ears' tops
  for (const sd of [-1, 1]) for (let i = 0; i < 18; i++) { const az = sd * (1.0 + 0.65 * R()), el0 = 0.55 + 0.45 * R(); const pts = [P(az, el0, 0.01), P(az + sd * 0.35, el0 + 0.06, 0.017), P(az + sd * 0.75, el0 + 0.02, 0.015)];
    G2.add(clump(pts, 0.014, 0.6, hm(), { wave: 0.002, waves: 1 })); }
  // three locks that fall forward onto the forehead
  for (const [az, len] of [[-0.25, 1], [-0.08, 0.7], [0.14, 0.85]]) { const pts = [P(az, 1.2, 0.05), P(az - 0.04, 1.08, 0.054), P(az - 0.1, 0.98 - 0.04 * len, 0.034), P(az - 0.16, 0.92 - 0.06 * len, 0.014)];
    G2.add(clump(pts, 0.012, 0.75, hm(), { wave: 0.004, waves: 1.1 })); }
  p.hairV4 = G2; return G2; }

/** Maa's hair, fuller: centre parting, combed to the sides over the ears' tops and back into the bun; dark with grey streaks */
export function hairMaa(p) { const hm0 = hairMatOf(p); const G2 = new THREE.Group(); p.head.add(G2); let s = 21; const R = () => (s = (s * 16807) % 2147483647) / 2147483647;
  const tones = ['#2a1e18', '#221812', '#3a2e28', '#5e5650', '#77706a'].map(c => { const m = hm0.clone(); m.color = new THREE.Color(c); m.side = THREE.DoubleSide; return m; }); const hm = () => tones[R() < 0.18 ? 3 + Math.floor(R() * 2) : Math.floor(R() * 3)];
  const bun = new V3(0, 0.075, -0.168);
  for (const sd of [-1, 1]) for (let i = 0; i < 46; i++) { const az0 = sd * (0.03 + 0.08 * R()), el0 = 1.2 + 0.25 * R(); const r0 = dirAt(az0, el0);
    // from the parting down the side: over the temple, above the ear, back to the nape and up into the bun
    const az1 = sd * (0.9 + 0.75 * R()), el1 = 0.45 + 0.35 * R(); const m1 = dirAt(az1, el1), end = dirAt(Math.PI - sd * 0.25, 0.08);
    const pts = []; for (let k = 0; k <= 8; k++) { const u = k / 8; const d = u < 0.5 ? sbez(r0, dirAt(sd * 0.6, (el0 + el1) / 2 + 0.25), m1, u * 2) : sbez(m1, dirAt(sd * 2.4, 0.3), end, (u - 0.5) * 2);
      pts.push(onHair(d, 0.008 + 0.014 * Math.sin(Math.PI * Math.min(1, u * 1.3)) * (0.7 + 0.5 * R() * 0.2))); }
    pts.push(bun.clone().add(new V3(sd * 0.02, 0.01, 0.02)));
    G2.add(clump(pts, 0.011 + 0.006 * R(), 0.6, hm(), { wave: 0.003, waves: 2 + R(), ph: R(), seg: 40 })); }
  p.hairV4 = G2; return G2; }
const shoulders = (p, k) => { for (const s of ['l', 'r']) { const A = p.arm[s]; A.sho.children[0].scale.setScalar(k); A.sho.position.x *= 0.93; A.sho.position.y -= 0.008; const ups = A.up.children.filter(m => m.isMesh); if (ups[0]) ups[0].scale.multiply(new V3(0.86, 1, 0.86)); } };

// ================================================================== CLOTHES v4: sleeves that cover the arm properly
/** son: shirt sleeves rolled to the middle of the forearm (a sleeve over the elbow + a thick rolled band) */
export function sleevesSon(p) { const sh = p.chest.material; let matDone = false;
  const NA = 34, NR = 22; p.sleeveTick = () => {}; p.sleeveCover = 1.36;   /* how far down the arm the sleeve reaches: 1 = the elbow, 2 = the wrist (the arm's skin stays slim under it) */
  const arms = [];
  for (const s of ['l', 'r']) { const A = p.arm[s], L = p.L;
    // hide every old sleeve piece and the skin that sits inside the new sleeve
    for (const c of A.up.children) if (c.isMesh) c.visible = false;
    for (const c of A.fo.children) { if (c.isMesh && c.geometry.type === 'SphereGeometry') c.visible = false; if (c.isMesh && c.geometry.type === 'LatheGeometry') c.scale.set(0.88, 1, 0.86); }
    const len = L.fore * 0.36 / (p.size || 1);
    const g = new THREE.BufferGeometry(); const pos = new Float32Array((NA + 1) * (NR + 1) * 3), nor = new Float32Array(pos.length), uv = new Float32Array((NA + 1) * (NR + 1) * 2), idx = [];
    for (let i = 0; i < NA; i++) for (let j = 0; j < NR; j++) { const a = i * (NR + 1) + j, b = a + NR + 1; idx.push(a, a + 1, b, b, a + 1, b + 1); }
    g.setIndex(idx); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('normal', new THREE.BufferAttribute(nor, 3)); g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    const m = new THREE.Mesh(g, sh); m.frustumCulled = false; m.castShadow = m.receiveShadow = true; p.root.add(m);
    arms.push({ A, len, g, pos, nor, uv, m, sg: A.sg }); }
  const tmp = new V3(), up0 = new V3();
  // radius along the sleeve (u: 0 shoulder → 1 cuff); the elbow sits at u≈0.58
  let US = 0.15, UE = 0.62; const R = u => { const ue = UE; if (p.sleeveTo) { const cap = u < 0.04 ? Math.sqrt(Math.max(0, 1 - Math.pow(1 - u / 0.04, 2))) : 1; return (u < US ? lerp(0.064, 0.058, u / US) : lerp(0.058, 0.061, (u - US) / (1 - US))) * (p.sleeveR || 1) * Math.max(0.05, cap); } const cap = u < 0.04 ? Math.sqrt(Math.max(0, 1 - Math.pow(1 - u / 0.04, 2))) : 1; let r = u < US ? lerp(0.064, 0.056, u / US) : u < ue ? lerp(0.056, 0.0465, Math.pow((u - US) / (ue - US), 0.8)) : lerp(0.0465, 0.0445, (u - ue) / (1 - ue)); if (u > 0.9) r += 0.0045 * Math.sin(clamp((u - 0.9) / 0.1, 0, 1) * Math.PI); return r * Math.max(0.05, cap); };
  p.sleeveTick = () => { p.root.updateWorldMatrix(true, true); if (!matDone) { matDone = true; const s2 = p.chest.material.clone(); s2.side = THREE.FrontSide; for (const a of arms) a.m.material = s2; }
    for (const a of arms) { const { A } = a; const toL = v => p.root.worldToLocal(v);
      const S = toL(A.up.getWorldPosition(new V3())), E = toL(A.fo.getWorldPosition(new V3())), half = p.sleeveTo, C = half ? S.clone().lerp(E, half) : toL(A.fo.localToWorld(new V3(0, -a.len, 0)));
      const CH = toL(p.sp2.getWorldPosition(new V3())).add(new V3(0, 0.17, 0)); const inw = CH.clone().sub(S); inw.y = 0; inw.normalize(); const S0 = S.clone().addScaledVector(inw, p.shirt ? 0.118 : 0.07).add(new V3(0, p.shirt ? -0.004 : 0.005, 0));   /* with the one-piece shirt (shirtSon) the sleeve begins deep inside the body: its capped start used to show on the shoulder as a box */
      const lU = S0.distanceTo(S) + S.distanceTo(E), lT = lU + E.distanceTo(C); a.us = S0.distanceTo(S) / lT; a.ue = lU / lT;
      // the elbow's point is the SLEEVE's (the cloth goes round it): the same point the arm's skin makes (motion.js armSkin), a little further out
      const t1 = E.clone().sub(S).normalize(), t2 = C.clone().sub(E).normalize(), bnd = new V3().crossVectors(t1, t2).length(), out = bnd > 1e-4 ? t1.clone().sub(t2).normalize() : null, Ec = out ? E.clone().addScaledVector(out, 0.004 + 0.012 * Math.min(1, bnd * 1.3)) : E;
      const crv = half ? new THREE.CatmullRomCurve3([S0, S0.clone().lerp(S, 0.6), S.clone().lerp(C, 0.5), C], false, 'centripetal', 0.5) : new THREE.CatmullRomCurve3([S0, S0.clone().lerp(S, 0.6), S.clone().lerp(E, 0.5), E.clone().addScaledVector(t1, -0.036), Ec, E.clone().addScaledVector(t2, 0.036), C], false, 'centripetal', 0.5);
      const P = crv.getSpacedPoints(NA), T = P.map((q, i) => (i < NA ? P[i + 1].clone().sub(q) : q.clone().sub(P[i - 1])).normalize());
      // a stable frame: the arm's own side axis from the upper arm's world X, parallel-transported down the sleeve
      const ax = toL(A.up.localToWorld(new V3(1, 0, 0))).sub(toL(A.up.localToWorld(new V3(0, 0, 0)))).normalize(); let Nn = ax.clone().sub(T[0].clone().multiplyScalar(ax.dot(T[0]))).normalize();
      US = a.us; UE = half ? 3 : a.ue; const bend = half ? 0 : E.clone().sub(S).normalize().cross(C.clone().sub(E).normalize()).length();
      for (let i = 0; i <= NA; i++) { if (i > 0) { const t0 = T[i - 1], t1 = T[i]; const q = new THREE.Quaternion().setFromUnitVectors(t0, t1); Nn.applyQuaternion(q); Nn.sub(t1.clone().multiplyScalar(Nn.dot(t1))).normalize(); }
        const B = T[i].clone().cross(Nn).normalize(); const u = i / NA; const r0 = R(u);
        for (let j = 0; j <= NR; j++) { const th = j / NR * Math.PI * 2; const cs = Math.cos(th), sn = Math.sin(th);
          // soft folds bunching at the inside of the elbow when the arm bends
          const fold = bend * 0.12 * Math.exp(-Math.pow((u - UE) / 0.08, 2)) * Math.max(0, sn) * (0.6 + 0.4 * Math.sin(u * 90 + th * 2));
          const nx = Nn.x * cs + B.x * sn, ny = Nn.y * cs + B.y * sn, nz = Nn.z * cs + B.z * sn; const k = (i * (NR + 1) + j);
          const tip = out && !half ? 0.09 * Math.exp(-Math.pow((u - UE) / 0.07, 2)) * Math.max(0, nx * out.x + ny * out.y + nz * out.z) * Math.min(1, bnd * 1.5) : 0;
          const r = r0 * (1 + fold + tip + 0.015 * Math.sin(th * 3 + u * 11));
          a.pos[k * 3] = P[i].x + nx * r; a.pos[k * 3 + 1] = P[i].y + ny * r; a.pos[k * 3 + 2] = P[i].z + nz * r; a.nor[k * 3] = nx; a.nor[k * 3 + 1] = ny; a.nor[k * 3 + 2] = nz; a.uv[k * 2] = j / NR; a.uv[k * 2 + 1] = u * 1.6; } }
      a.g.attributes.position.needsUpdate = true; a.g.attributes.normal.needsUpdate = true; a.g.attributes.uv.needsUpdate = true; } };
  // the rolled cuff edge: a darker inner lining just visible at the opening
}
/** Maa: a fitted blouse sleeve to just above the elbow with a gold border; no ball at the shoulder */
export function sleevesMaa(p) { { const m = p.chest.material; m.sheen = 0.12; m.sheenColor = new THREE.Color('#a04050'); m.roughness = 0.88; m.color = new THREE.Color('#c8b0b0'); }
  const bl = p.chest.material, gold = M('#d9a43a', { r: 0.35, m: 0.5 }); for (const s of ['l', 'r']) { const A = p.arm[s], L = p.L;
    for (const c of A.up.children) if (c.isMesh && c.geometry.type === 'TorusGeometry') { c.material = gold; c.geometry.dispose(); c.geometry = new THREE.TorusGeometry(0.047, 0.006, 8, 28); }
    const ups = A.up.children.filter(m => m.isMesh && m.geometry.type === 'LatheGeometry'); if (ups[0]) { ups[0].material = bl; ups[0].scale.set(0.84, 1, 0.84); } if (ups[1]) ups[1].scale.set(0.78, 1, 0.78);
    A.sho.children[0].material = bl; A.sho.children[0].scale.setScalar(0.55); } p.sleeveCover = 0.66; }   /* her sleeve ends above the elbow: the arm's skin (motion.js armSkin) is bare from there */
/** Maa's saree: a wide woven gold border at the hem and a fan of pleats in front */
export function sareeMaa(p) { const gold = M('#c8952e', { r: 0.4, m: 0.45 }), sar = p.skirt.material;
  const R = y => y > 0.03 ? lerp(0.162, 0.128, (y - 0.03) / 0.12) : y > -0.2 ? lerp(0.162, 0.172, -(y - 0.03) / 0.23) : y > -0.48 ? lerp(0.172, 0.182, -(y + 0.2) / 0.28) : lerp(0.182, 0.196, -(y + 0.48) / 0.255);
  const band = []; for (let i = 0; i <= 6; i++) { const y = -0.735 + i * 0.008; band.push(new THREE.Vector2(R(y) + 0.0025, y)); } const b = new THREE.Mesh(new THREE.LatheGeometry(band, 56), gold); b.scale.z = 0.86; p.skirt.add(b);
  const b2 = []; for (let i = 0; i <= 2; i++) { const y = -0.67 + i * 0.004; b2.push(new THREE.Vector2(R(y) + 0.0028, y)); } const bb = new THREE.Mesh(new THREE.LatheGeometry(b2, 56), gold); bb.scale.z = 0.86; p.skirt.add(bb);
  // pleats: narrow at the waist, fanning out to the hem, tucked a little left of centre
  for (let i = 0; i < 9; i++) { const a0 = -0.12 + i * 0.022, a1 = -0.3 + i * 0.07, P = []; for (const y of [0.06, -0.1, -0.3, -0.5, -0.72]) { const k = clamp((0.06 - y) / 0.78, 0, 1), aa = lerp(a0, a1, k); const rr = (R(y) + 0.006) ; P.push(new V3(Math.sin(aa) * rr, y, Math.cos(aa) * rr * 0.86)); }
    p.skirt.add(tube(P, 0.0055, sar, 30, 6)); }
  for (const c of p.skirt.children) { c.castShadow = c.receiveShadow = true; } }

/** finer hands: slimmer, slightly longer fingers and thumb, a flatter palm (the sausage-finger look goes) */
export function finerHands(p) { for (const s of ['l', 'r']) { const h = p.arm[s].hand;
    for (const segs of h.fingers) for (const j of segs) for (const m of j.children) if (m.isMesh && m.geometry.type === 'CapsuleGeometry') m.scale.set(0.7, 1.1, 0.66);
    for (const j of h.thumb) for (const m of j.children) if (m.isMesh && m.geometry.type === 'CapsuleGeometry') m.scale.set(0.76, 1.06, 0.72);
    for (const segs of h.fingers) for (const j of segs) for (const m of j.children) if (m.isMesh && m.geometry.type === 'SphereGeometry') m.scale.multiplyScalar(0.75);
    const palm = p.arm[s].ha.children.find(m => m.isMesh && m.geometry.type === 'SphereGeometry'); if (palm) palm.scale.multiply(new V3(0.95, 1.0, 0.86)); } }

// ================================================================== HAIR v5 (son): one sculpted surface, the way feature-film grooms read at a distance
/** a sculpted hair shell over the skull: volume on top and front, short sides, chunky wavy locks swept up and back (slightly to his left), a clean hairline cut in the shader */
export function hairShell(p, o = {}) { const g = new THREE.SphereGeometry(1, 220, 160); const P = g.attributes.position, N = P.count, hl = new Float32Array(N);
  const lineEl = az => { const a = Math.abs(az); return a < 0.5 ? 0.9 + 0.025 * Math.cos(az * 6) : a < 1.15 ? lerp(0.9, 0.42, sm((a - 0.5) / 0.65)) : a < 1.9 ? lerp(0.42, 0.24, sm((a - 1.15) / 0.75)) : lerp(0.24, -0.3, sm((a - 1.9) / 1.0)); };
  for (let i = 0; i < N; i++) { const d = new V3(P.getX(i), P.getY(i), P.getZ(i)); const az = Math.atan2(d.x, d.z), el = Math.asin(clamp(d.y, -1, 1)); const h = el - lineEl(az);
    // volume: a lifted front/top (the quiff sweeps a little to his left = +x), close at the sides and nape
    const top = Math.exp(-(((el - 1.08) / 0.42) ** 2)) * Math.exp(-(((az - 0.12) / 1.7) ** 2)); const crown = Math.exp(-(((el - 1.4) / 0.5) ** 2)); const side = Math.exp(-(((el - 0.75) / 0.3) ** 2)) * Math.exp(-(((Math.abs(az) - 1.2) / 0.7) ** 2));
    let T = 0.011 + 0.022 * top + 0.012 * crown + 0.009 * side + 0.004 * Math.max(0, Math.cos(az));
    // locks: ridges running front-to-back along the skull, with a slow wave and uneven width; a soft side parting on his right
    const fl = 12 * az + 1.6 * Math.sin(el * 3.2 + az * 1.3) + 0.6 * Math.sin(az * 31 + el * 5); const ridge = Math.pow(Math.abs(Math.sin(fl)), 0.55);
    T += (0.0045 + 0.004 * top) * ridge - 0.004 * Math.exp(-(((az + 0.42) / 0.05) ** 2)) * sm((el - 0.85) / 0.3);
    T *= lerp(0.35, 1, sm(clamp(h / 0.22, 0, 1))); hl[i] = h;
    const q = onHair(d, T); P.setXYZ(i, q.x, q.y, q.z); }
  g.setAttribute('hl', new THREE.BufferAttribute(hl, 1)); g.computeVertexNormals();
  const m = new THREE.MeshPhysicalMaterial({ color: o.color || '#17100b', roughness: 0.5, sheen: 0.35, sheenRoughness: 0.5, sheenColor: new THREE.Color('#3a2a20'), clearcoat: 0.35, clearcoatRoughness: 0.35 });
  m.onBeforeCompile = sh => { sh.vertexShader = 'attribute float hl; varying float vHl;\n' + sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\n vHl = hl;'); sh.fragmentShader = 'varying float vHl;\n' + sh.fragmentShader.replace('#include <clipping_planes_fragment>', 'if (vHl < 0.0) discard;\n#include <clipping_planes_fragment>'); };
  const me = new THREE.Mesh(g, m); me.castShadow = true; me.receiveShadow = true; p.head.add(me);
  // a few loose locks: two fall forward over the brow, short tufts at the temples and the nape
  const lk = []; const P2 = (az, el, out) => onHair(dirAt(az, el), out);
  for (const [az, s, len] of [[-0.2, -1, 1], [0.05, -1, 0.75], [0.32, 1, 0.6]]) lk.push(clump([P2(az, 1.18, 0.04), P2(az + s * 0.03, 1.07, 0.044), P2(az + s * 0.08, 0.99 - 0.04 * len, 0.03), P2(az + s * 0.14, 0.93 - 0.05 * len, 0.013)], 0.013, 0.7, m, { wave: 0.004, waves: 1.1 }));
  for (const sd of [-1, 1]) for (let i = 0; i < 4; i++) lk.push(clump([P2(sd * (1.2 + i * 0.12), 0.62, 0.012), P2(sd * (1.3 + i * 0.12), 0.5, 0.012), P2(sd * (1.42 + i * 0.12), 0.42, 0.006)], 0.012, 0.6, m, { wave: 0.002 }));
  for (const L of lk) p.head.add(L);
  // the earlier hair (cap, base, clumps) goes
  for (const c of p.head.children) if (c.isMesh && c.geometry.attributes.hl && c !== me) c.visible = false; if (p.hairV4) p.hairV4.visible = false;
  p.hairShell = me; return me; }

/** Maa is a widow: no sindoor, no bindi, no red bangles, no nose pin; a plain off-white saree with a thin muted border and a soft grey blouse */
function widowTex() { if (!browser) return null; const n = 512; const c = document.createElement('canvas'); c.width = c.height = n; const g = c.getContext('2d'); g.fillStyle = '#ebe5da'; g.fillRect(0, 0, n, n);
  for (let i = 0; i < 14000; i++) { g.fillStyle = `rgba(${Math.random() < 0.5 ? '90,80,70' : '255,255,250'},0.05)`; g.fillRect(Math.random() * n, Math.random() * n, 3, 1); }
  for (let y = 0; y < n; y += 4) { g.fillStyle = 'rgba(120,110,100,0.025)'; g.fillRect(0, y, n, 1); }
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; return t; }
export function widowMaa(p) {
  const red = m => { if (!m || !m.color) return false; const h = m.color.getHSL({}); return (h.h < 0.04 || h.h > 0.94) && h.s > 0.55 && h.l > 0.08 && h.l < 0.5; };
  p.root.traverse(o => { if (!o.isMesh) return;
    if (o.parent === p.head && (o.geometry.type === 'BoxGeometry' || o.geometry.type === 'SphereGeometry') && red(o.material)) o.visible = false;   // sindoor line, bindi
    if (o.parent === p.head && o.geometry.type === 'SphereGeometry' && Math.abs((o.geometry.parameters.radius || 0) - 0.0045) < 1e-5) o.visible = false;   // nose pin
    if (o.geometry.type === 'TorusGeometry' && red(o.material)) o.visible = false; });   // red glass bangles
  const sar = p.skirt.material, st = widowTex(); if (st) { st.repeat.set(5, 6); sar.map = st; } sar.color.set(st ? '#ffffff' : '#ebe5da'); if (sar.sheenColor) sar.sheenColor.set('#fff4e8'); sar.needsUpdate = true;
  if (p.pallu) { p.pallu.material.map = sar.map; p.pallu.material.color.copy(sar.color); p.pallu.material.needsUpdate = true; }
  const border = M('#7a8290', { r: 0.75, m: 0.05 });
  for (const c of p.skirt.children) if (c.isMesh && c.geometry.type === 'LatheGeometry') c.material = border;
  for (const c of p.pelvis.children) if (c.isMesh && c.geometry.type === 'TorusGeometry') c.material = border;
  p.skirt.traverse(c => { if (c.isMesh && c.material.color && ['d9a43a', 'c8952e', 'e0b040'].includes(c.material.color.getHexString())) c.material = border; });
  for (const c of p.pelvis.children) if (c.isMesh && c.geometry.type === 'SphereGeometry' && c.material.color && c.material.color.getHexString() === '7e2230') c.material = sar;
  for (const c of p.sp2.children) if (c.isMesh && c.material !== sar && c.material.color && c.material.metalness > 0.3) c.material = border;
  const bl = p.chest.material, bt = clothTex('#7f7772', '#736b66', 11); if (bt) { bl.map = bt; bl.color.set('#ffffff'); } else bl.color.set('#7f7772'); bl.sheen = 0.05; if (bl.sheenColor) bl.sheenColor.set('#d0c8c0'); bl.needsUpdate = true;
  for (const s of ['l', 'r']) for (const c of p.arm[s].up.children) if (c.isMesh && c.geometry.type === 'TorusGeometry') c.material = M('#6f6762', { r: 0.8, m: 0 });
}

/** a smooth curve through [x, y] keys that never overshoots one (monotone cubic): a radius along a limb */
function curveK(keys, x) { const n = keys.length; if (x <= keys[0][0]) return keys[0][1]; if (x >= keys[n - 1][0]) return keys[n - 1][1]; let i = 0; while (i < n - 2 && x > keys[i + 1][0]) i++;
  const sl = j => (keys[j + 1][1] - keys[j][1]) / Math.max(1e-9, keys[j + 1][0] - keys[j][0]), tg = j => { if (j === 0 || j === n - 1) return sl(Math.min(j, n - 2)); const a = sl(j - 1), b = sl(j); return a * b <= 0 ? 0 : 2 * a * b / (a + b); };
  const h = keys[i + 1][0] - keys[i][0], u = (x - keys[i][0]) / h, m0 = tg(i) * h, m1 = tg(i + 1) * h, u2 = u * u, u3 = u2 * u; return (2 * u3 - 3 * u2 + 1) * keys[i][1] + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * keys[i + 1][1] + (u3 - u2) * m1; }
/** Maa's body under the saree as ONE surface that bends with her spine (27 Nov 2026, after Vikas's shirt): the saree's wrap round the
 *  waist, the blouse above it up to a scooped neckline, and her own skin from there to the neck: one skin from inside the skirt's top
 *  to the neck, each ring going with the hips, the waist or the chest. The mesh's rows follow the neckline, so the blouse's edge is a
 *  clean line with a small lip. Each sleeve GROWS out of the body: its first ring lies on the body's surface round the shoulder (the
 *  armhole) and the rings after it turn into the round sleeve down the arm, so there is no line where two surfaces cross (a tube pushed
 *  through the body left a ragged one). The pallu, the skirt and her jewellery are as they were. Remade before every draw. */
export function blouseMaa(p, E) { const sp1 = p.sp1, sp2 = p.sp2, Y1 = sp1.position.y, Y2 = Y1 + sp2.position.y, blouse = p.chest.material, wrap = p.belly.material;
  const nkM = p.neck.children.find(o => o.isMesh && o.geometry.type === 'CapsuleGeometry'), skinM = nkM ? nkM.material : blouse, nkR = nkM ? 0.055 * nkM.scale.x : 0.049, yNk = Y2 + p.neck.position.y, yHead = yNk + 0.095;   /* the neck is part of this skin (her neck was a capsule standing in the body: a ring showed where the two met) */
  const shY = Y2 + p.arm.l.sho.position.y, shX = Math.abs(p.arm.l.sho.position.x);
  const ell = (m, r, y0) => ({ cy: y0 + m.position.y, cz: m.position.z, a: r * m.scale.x, b: r * m.scale.y, c: r * m.scale.z });
  // the ribs are narrower than the shoulders, so a hanging arm lies BESIDE them (the old chest ball was as wide as the shoulder joints, and the arm sank into it); the shoulder girdle is a bar that keeps its thickness out to the joint
  const chest = ell(p.chest, 0.165, Y2); chest.a *= 0.87;
  const GEN = [ell(p.belly, 0.17, Y1), chest, { cy: shY - 0.004, cz: -0.011, a: shX + 0.012, b: 0.05, c: 0.072, n: 4 }];
  const ray = (A, C, cz, sn, cs) => { const qa = sn * sn / (A * A) + cs * cs / (C * C), qb = -2 * cz * cs / (C * C), qc = cz * cz / (C * C) - 1, d = qb * qb - 4 * qa * qc; return d <= 0 ? 0 : (-qb + Math.sqrt(d)) / (2 * qa); };
  const rayN = (g, q, sn, cs) => { if ((g.cz / g.c) ** 2 >= q) return 0; let lo = 0, hi = 0.4; for (let k = 0; k < 22; k++) { const m = (lo + hi) / 2; if (Math.pow(Math.abs(m * sn) / g.a, g.n) + ((m * cs - g.cz) / g.c) ** 2 < q) lo = m; else hi = m; } return lo; };   /* a section that is blunter than an ellipse along x */
  const yBot = 0.1, yTop = Y2 + p.neck.position.y + 0.02, neckY0 = shY + 0.026, yHem = Y2 - 0.012, yW = shY - 0.05, NS = 64, skirtTop = (sn, cs) => ray(0.128, 0.128 * 0.86, 0, sn, cs) - 0.003;
  const rad0 = (y, sn, cs) => { let r = 0; for (const g of GEN) { const h = (y - g.cy) / g.b; if (Math.abs(h) < 1) { const f = Math.sqrt(1 - h * h); r = Math.max(r, g.n ? rayN(g, 1 - h * h, sn, cs) : ray(g.a * f, g.c * f, g.cz, sn, cs)); } }
    if (y > neckY0) { const nk = ray(nkR, nkR, 0, sn, cs); r = lerp(Math.max(r, nk), nk, sm((y - neckY0) / (yTop - neckY0))); }
    if (y < 0.19) r = lerp(Math.min(r, skirtTop(sn, cs)), r, sm((y - 0.15) / 0.04)); return r; };
  // the neckline: the blouse's upper edge, scooped in front and a little at the back; her skin above it
  const neckline = th => { const c = Math.cos(th); return shY + 0.05 - 0.052 * Math.pow(Math.max(0, c), 1.5) - 0.03 * Math.pow(Math.max(0, -c), 1.5); };
  // rows: level ones from the skirt to under the arms, then rows that rise to the neckline (the last is the blouse's edge), the skin's own first row on the same line, and the skin up to the neck
  const NA0 = 30, NB = 8, NC = 9, ROWS = []; for (let i = 0; i < NA0; i++) { const y = lerp(yBot, yW, i / (NA0 - 1)); ROWS.push({ y: () => y, kind: 0 }); }
  for (let k = 1; k <= NB; k++) ROWS.push({ y: th => lerp(yW, neckline(th), k / NB), kind: k === NB ? 1 : 0 });
  ROWS.push({ y: th => neckline(th), kind: 2 }); for (let k = 1; k <= NC; k++) ROWS.push({ y: th => lerp(neckline(th), yTop, k / NC), kind: 3, k }); for (let k = 1; k <= 7; k++) { const y = lerp(yTop, yHead, k / 7); ROWS.push({ y: () => y, kind: 3, k: 9 }); }
  const NG = ROWS.length - 1, RY = [], TB = []; for (let i = 0; i <= NG; i++) { const ry = new Float32Array(NS + 1), row = new Float32Array(NS + 1); for (let j = 0; j <= NS; j++) { const th = j / NS * 2 * Math.PI, y = ROWS[i].y(th); ry[j] = y; row[j] = rad0(y, Math.sin(th), Math.cos(th)); } RY.push(ry); TB.push(row); }
  for (let pass = 0; pass < 2; pass++) for (let j = 0; j <= NS; j++) { let prev = TB[0][j]; for (let i = 1; i < NG; i++) { const cur = TB[i][j], lone = ROWS[i].kind === 1 || ROWS[i].kind === 2, v = RY[i][j] < 0.2 || lone ? cur : 0.25 * prev + 0.5 * cur + 0.25 * TB[i + 1][j]; prev = cur; TB[i][j] = v; } }
  const edgeI = ROWS.findIndex(r => r.kind === 1); for (let j = 0; j <= NS; j++) { const q = (TB[edgeI - 1][j] + TB[edgeI + 2][j]) / 2; TB[edgeI][j] = TB[edgeI + 1][j] = (q + TB[edgeI][j]) / 2; }
  /** the body's surface at rest: how far it reaches from the spine at a height and an angle (read from the rows, as drawn) */
  const bodyR = (y, th) => { const u = ((th / (2 * Math.PI)) % 1 + 1) % 1 * NS, j = Math.min(NS - 1, Math.floor(u)), f = u - j, yy = i => lerp(RY[i][j], RY[i][j + 1], f), rr = i => lerp(TB[i][j], TB[i][j + 1], f);
    if (y <= yy(0)) return rr(0); for (let i = 0; i < NG; i++) { const a = yy(i), b = yy(i + 1); if (y <= b) return b - a < 1e-6 ? rr(i) : lerp(rr(i), rr(i + 1), (y - a) / (b - a)); } return 0; };
  const pos = [], uv = [], idx = [], LIP = 0.0028; let hemI = 0; for (let i = 0; i <= NG; i++) { if (ROWS[i].kind === 0 && i < NA0 && RY[i][0] < yHem) hemI = i; const k = ROWS[i].kind, off = k === 2 ? -LIP : k === 3 ? -LIP * (1 - sm(ROWS[i].k / 3)) : 0;
    for (let j = 0; j <= NS; j++) { const th = j / NS * 2 * Math.PI, r = TB[i][j] + off, y = RY[i][j]; pos.push(r * Math.sin(th), y, r * Math.cos(th)); uv.push(j / NS, (y - yBot) / 0.4); } }
  for (let i = 0; i < NG; i++) for (let j = 0; j < NS; j++) { const a = i * (NS + 1) + j, b = a + NS + 1; idx.push(a, a + 1, b, b, a + 1, b + 1); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx); g.computeVertexNormals();
  g.addGroup(0, hemI * NS * 6, 0); g.addGroup(hemI * NS * 6, (edgeI + 1 - hemI) * NS * 6, 1); g.addGroup((edgeI + 1) * NS * 6, (NG - edgeI - 1) * NS * 6, 2);   /* the saree's wrap, the blouse (with its lip at the neckline), her skin */
  const body = new THREE.Mesh(g, [wrap, blouse, skinM]); body.castShadow = body.receiveShadow = true; body.frustumCulled = false; p.pelvis.add(body); p.shirt = body;
  for (const m of [p.belly, p.chest]) m.visible = false; if (nkM) nkM.visible = false; for (const o of sp2.children) if (o.isMesh && o.geometry.type === 'SphereGeometry' && Math.abs(o.position.y - 0.165) < 1e-3 && Math.abs(o.position.x) < 1e-3) o.visible = false;
  // the sleeves: each begins as a ring ON the body round the shoulder, a little under its surface, and becomes the round sleeve down to above the elbow
  const SL = p.C && p.C.sleeveL ? p.C.sleeveL : 0.78, NA = 24, NR = 24, arms = [], X0 = shX - 0.03, DROP = 0.02, RS = 0.053;
  for (const s of ['l', 'r']) { const A = p.arm[s], sg = Math.sign(A.sho.position.x) || 1; const ball = A.sho.children[0]; if (ball && ball.isMesh) ball.visible = false; for (const c of A.up.children) if (c.isMesh && c.geometry.type === 'LatheGeometry' && c.material === blouse) c.visible = false;
    const c0 = new V3(sg * X0, shY - DROP, -0.011), inside = (x, y, z) => y > yBot && y < yTop && Math.hypot(x, z) < bodyR(y, Math.atan2(x, z)), loop = [];
    for (let j = 0; j <= NR; j++) { const ph = j / NR * 2 * Math.PI, cs = Math.cos(ph), sn = Math.sin(ph); let lo = 0, hi = 0.16; for (let k = 0; k < 20; k++) { const m = (lo + hi) / 2; if (inside(c0.x, c0.y + m * cs, c0.z + sg * m * sn)) lo = m; else hi = m; }
      const rho = lerp(Math.min(lo - 0.004, 0.066), 0.05, sm(-cs / 0.35)); loop.push(new V3(c0.x, c0.y - Y2 + rho * cs, c0.z + sg * rho * sn)); }
    const sgm = new THREE.BufferGeometry(), n = (NA + 1) * (NR + 1), spA = new Float32Array(n * 3), su = new Float32Array(n * 2), si = []; for (let i = 0; i < NA; i++) for (let j = 0; j < NR; j++) { const a = i * (NR + 1) + j, b = a + NR + 1; si.push(a, a + 1, b, b, a + 1, b + 1); }
    for (let i = 0; i <= NA; i++) for (let j = 0; j <= NR; j++) { su[(i * (NR + 1) + j) * 2] = j / NR; su[(i * (NR + 1) + j) * 2 + 1] = i / NA; }
    sgm.setIndex(si); sgm.setAttribute('position', new THREE.BufferAttribute(spA, 3)); sgm.setAttribute('uv', new THREE.BufferAttribute(su, 2)); sgm.computeVertexNormals(); const m = new THREE.Mesh(sgm, blouse); m.frustumCulled = false; m.castShadow = m.receiveShadow = true; p.root.add(m);
    arms.push({ A, sg, sgm, spA, loop, c0: new V3(c0.x, c0.y - Y2, c0.z) }); }
  const rest = Float32Array.from(g.attributes.position.array), m1 = new THREE.Matrix4(), m2 = new THREE.Matrix4(), m3 = new THREE.Matrix4(), v = new V3(), o3 = new V3(), NROW = NG + 1;
  const skin = (x, y, z, out) => { const w1 = sm((y - 0.15) / 0.09), w2 = sm((y - 0.215) / 0.13), w3 = sm((y - yNk + 0.014) / 0.05); let X = x, Y = y, Z = z; if (w1 > 0) { v.set(x, y - Y1, z).applyMatrix4(m1); X += (v.x - X) * w1; Y += (v.y - Y) * w1; Z += (v.z - Z) * w1; } if (w2 > 0) { v.set(x, y - Y2, z).applyMatrix4(m2); X += (v.x - X) * w2; Y += (v.y - Y) * w2; Z += (v.z - Z) * w2; }
    if (w3 > 0) { v.set(x, y - yNk, z).applyMatrix4(m3); X += (v.x - X) * w3; Y += (v.y - Y) * w3; Z += (v.z - Z) * w3; } return out.set(X, Y, Z); };   /* hips, waist, chest, and the neck's own turn for the rows on the neck */
  const seam = (geo, rows, cols) => { const N = geo.attributes.normal.array; for (let i = 0; i < rows; i++) { const a = i * (cols + 1) * 3, b = a + cols * 3; for (let k = 0; k < 3; k++) { const q = (N[a + k] + N[b + k]) / 2; N[a + k] = N[b + k] = q; } } };
  p.shirtTick = () => { sp1.updateMatrix(); sp2.updateMatrix(); p.neck.updateMatrix(); m1.copy(sp1.matrix); m2.multiplyMatrices(sp1.matrix, sp2.matrix); m3.multiplyMatrices(m2, p.neck.matrix);
    const A0 = g.attributes.position.array; for (let i = 0, n = rest.length / 3; i < n; i++) { skin(rest[i * 3], rest[i * 3 + 1], rest[i * 3 + 2], o3); A0[i * 3] = o3.x; A0[i * 3 + 1] = o3.y; A0[i * 3 + 2] = o3.z; }
    g.attributes.position.needsUpdate = true; g.computeVertexNormals(); seam(g, NROW, NS);
    p.root.updateWorldMatrix(true, true); const toL = q => p.root.worldToLocal(q), fromChest = q => toL(sp2.localToWorld(q.clone()));
    for (const a of arms) { const A = a.A, P0 = fromChest(a.c0), upB = fromChest(a.c0.clone().add(new V3(0, 1, 0))).sub(P0).normalize(), S = toL(A.up.getWorldPosition(new V3())), El = toL(A.fo.getWorldPosition(new V3())), C = S.clone().lerp(El, SL);
      const hang = clamp(-El.clone().sub(S).normalize().dot(upB), 0, 1), S1 = S.clone().addScaledVector(upB, -DROP * hang);   /* a hanging arm's sleeve passes a little under the joint, so its top is the shoulder's own slope and not a puff above it */
      const crv = new THREE.CatmullRomCurve3([P0, S1, S.clone().lerp(C, 0.3), S.clone().lerp(C, 0.7), C], false, 'centripetal', 0.5), P = crv.getSpacedPoints(NA), T = P.map((q, i) => (i < NA ? P[i + 1].clone().sub(q) : q.clone().sub(P[i - 1])).normalize());
      const L = a.loop.map(fromChest); let Nn = upB.clone().sub(T[0].clone().multiplyScalar(upB.dot(T[0]))).normalize();
      for (let i = 0; i <= NA; i++) { if (i > 0) { Nn.applyQuaternion(new THREE.Quaternion().setFromUnitVectors(T[i - 1], T[i])); Nn.sub(T[i].clone().multiplyScalar(Nn.dot(T[i]))).normalize(); } const B = T[i].clone().cross(Nn).normalize(), u = i / NA, r = lerp(RS, 0.0475, sm(u * 1.15)) + (u > 0.9 ? 0.0025 * Math.sin((u - 0.9) / 0.1 * Math.PI) : 0), k = sm(i / 3);
        for (let j = 0; j <= NR; j++) { const th = j / NR * 2 * Math.PI, cs = Math.cos(th), sn2 = Math.sin(th), rr = r * (1 + 0.012 * Math.sin(th * 3 + u * 9)), q = i * (NR + 1) + j, Lj = L[j];
          a.spA[q * 3] = lerp(Lj.x + P[i].x - P0.x, P[i].x + (Nn.x * cs + B.x * sn2) * rr, k); a.spA[q * 3 + 1] = lerp(Lj.y + P[i].y - P0.y, P[i].y + (Nn.y * cs + B.y * sn2) * rr, k); a.spA[q * 3 + 2] = lerp(Lj.z + P[i].z - P0.z, P[i].z + (Nn.z * cs + B.z * sn2) * rr, k); } }
      a.sgm.attributes.position.needsUpdate = true; a.sgm.computeVertexNormals(); seam(a.sgm, NA + 1, NR); } };
  if (E && E.scene) { const sc = E.scene; (sc.userData.ticks = sc.userData.ticks || []).push(() => { if (p.root.visible) p.shirtTick(); }); if (!sc.userData.ticked) { sc.userData.ticked = true; const prev = sc.onBeforeRender; sc.onBeforeRender = function (...a) { if (prev) prev.apply(this, a); for (const f of sc.userData.ticks) f(); }; } }
  p.shirtTick(); return p;
}

/** Vikas's shirt as ONE surface that bends with the spine (owner, 27 Nov 2026: "the other joints").
 *  Before: a ball for the belly, a ball for the chest, a shell round both, a flat ball across the shoulders and a ball at each shoulder —
 *  five pieces that cut through each other in ragged lines, a box across the shoulders, and a belly that came out over the belt when he
 *  sat. Now: the same outline as one skin from inside the waistband to under the collar, each ring going with the hips, the waist or the
 *  chest (so a lean is a bend, and the cloth at the belt stays in the trousers); shoulders that slope from the neck and take the sleeve
 *  into themselves (the shoulder joints sit 2 cm lower); a placket and buttons that run down to the belt; the lanyard and card laid on it.
 *  Remade before every draw (p.shirtTick). */
export function shirtSon(p, E) { const sp1 = p.sp1, sp2 = p.sp2, Y1 = sp1.position.y, Y2 = Y1 + sp2.position.y, mat0 = p.chest.material;
  for (const s of ['l', 'r']) p.arm[s].sho.position.y -= 0.02;
  const shY = Y2 + p.arm.l.sho.position.y, shX = Math.abs(p.arm.l.sho.position.x);
  // what makes the outline (in the pelvis's space, the spine straight): the old belly and chest, the old shell, and a line across the shoulders
  const ell = (m, r, y0) => ({ cy: y0 + m.position.y, cz: m.position.z, a: r * m.scale.x, b: r * m.scale.y, c: r * m.scale.z });
  const GEN = [ell(p.belly, 0.17, Y1), ell(p.chest, 0.165, Y2), { cy: shY + 0.004, cz: -0.012, a: shX + 0.014, b: 0.05, c: 0.074 }];
  const SH = [[-0.06, 0.142], [0.04, 0.14], [0.14, 0.15], [0.24, 0.162], [0.3, 0.14], [0.33, 0.02]].map(([y, r]) => [y + Y1, r]), shellR = y => y < SH[0][0] || y > SH[SH.length - 1][0] ? 0 : curveK(SH, y);
  const ray = (A, C, cz, sn, cs) => { const qa = sn * sn / (A * A) + cs * cs / (C * C), qb = -2 * cz * cs / (C * C), qc = cz * cz / (C * C) - 1, d = qb * qb - 4 * qa * qc; return d <= 0 ? 0 : (-qb + Math.sqrt(d)) / (2 * qa); };
  const waist = (sn, cs) => { const n = 2.5, k = Math.pow(Math.pow(Math.abs(sn), n) + Math.pow(Math.abs(cs), n), -1 / n); return Math.hypot(0.128 * sn * k, (cs >= 0 ? 0.102 : 0.098) * cs * k); };   /* the trousers' waistband (trousersSon) */
  const yBot = 0.05, yTop = Y2 + p.neck.position.y + 0.03, neckY0 = shY + 0.028, NG = 48, NS = 48;
  const rad0 = (y, sn, cs) => { let r = 0; for (const g of GEN) { const h = (y - g.cy) / g.b; if (Math.abs(h) < 1) { const f = Math.sqrt(1 - h * h); r = Math.max(r, ray(g.a * f, g.c * f, g.cz, sn, cs)); } }
    const rs = shellR(y); if (rs > 0) r = Math.max(r, ray(rs, rs * 0.7, -0.005, sn, cs));
    if (y > neckY0) { const nk = ray(0.066, 0.06, 0.002, sn, cs); r = lerp(Math.max(r, nk), nk, sm((y - neckY0) / (yTop - neckY0))); }
    if (y < 0.13) { const w = waist(sn, cs); r = lerp(Math.min(r, w - 0.004), r, sm((y - 0.1) / 0.03)); } return r; };
  // the rings, smoothed up the body (two passes), so where one old piece met another is a soft fold and not a line
  const YS = [], TB = []; for (let i = 0; i <= NG; i++) { const y = lerp(yBot, yTop, i / NG); YS.push(y); const row = new Float32Array(NS + 1); for (let j = 0; j <= NS; j++) { const th = j / NS * 2 * Math.PI; row[j] = rad0(y, Math.sin(th), Math.cos(th)); } TB.push(row); }
  for (let pass = 0; pass < 2; pass++) for (let j = 0; j <= NS; j++) { let prev = TB[0][j]; for (let i = 1; i < NG; i++) { const cur = TB[i][j], v = YS[i] < 0.135 ? cur : 0.25 * prev + 0.5 * cur + 0.25 * TB[i + 1][j]; prev = cur; TB[i][j] = v; } }
  const radAt = (y, th) => { const fi = clamp((y - yBot) / (yTop - yBot), 0, 1) * NG, i = Math.min(NG - 1, Math.floor(fi)), u = fi - i, fj = ((th / (2 * Math.PI)) % 1 + 1) % 1 * NS, j = Math.min(NS - 1, Math.floor(fj)), w = fj - j; return lerp(lerp(TB[i][j], TB[i][j + 1], w), lerp(TB[i + 1][j], TB[i + 1][j + 1], w), u); };
  /** a point of the shirt's front at height y and side x (straight spine), out metres off the cloth */
  const front = (x, y, out = 0) => { let th = 0, r = radAt(y, 0); for (let k = 0; k < 4; k++) { th = Math.asin(clamp(x / Math.max(1e-4, r), -1, 1)); r = radAt(y, th); } return new V3((r + out) * Math.sin(th), y, (r + out) * Math.cos(th)); };
  p.front = front; p.Y2 = Y2;
  const pos = [], uv = [], idx = []; for (let i = 0; i <= NG; i++) for (let j = 0; j <= NS; j++) { const th = j / NS * 2 * Math.PI, r = TB[i][j]; pos.push(r * Math.sin(th), YS[i], r * Math.cos(th)); uv.push(j / NS, (YS[i] - yBot) / 0.42); }
  for (let i = 0; i < NG; i++) for (let j = 0; j < NS; j++) { const a = i * (NS + 1) + j, b = a + NS + 1; idx.push(a, a + 1, b, b, a + 1, b + 1); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx); g.computeVertexNormals();
  const body = new THREE.Mesh(g, mat0); body.castShadow = body.receiveShadow = true; body.frustumCulled = false; p.pelvis.add(body); p.shirt = body;
  // the placket: a darker strip down the front, on the cloth, to the belt; and the buttons on it
  const dark = M('#8fb0d4', { r: 0.75 }), btn = M('#f4f4f0', { r: 0.3 }), PKN = 30, pk0 = [], pkI = []; for (let i = 0; i <= PKN; i++) { const y = lerp(0.108, yTop - 0.012, i / PKN); for (const sx of [-1, 1]) { const q = front(sx * 0.0105, y, 0.0022); pk0.push(q.x, q.y, q.z); } }
  for (let i = 0; i < PKN; i++) { const a = i * 2; pkI.push(a, a + 1, a + 2, a + 2, a + 1, a + 3); }
  const pg = new THREE.BufferGeometry(); pg.setAttribute('position', new THREE.Float32BufferAttribute(pk0, 3)); pg.setIndex(pkI); pg.computeVertexNormals(); const plk = new THREE.Mesh(pg, dark); plk.receiveShadow = true; plk.frustumCulled = false; p.pelvis.add(plk);
  const BT = []; for (let i = 0; i < 6; i++) { const y = lerp(0.135, yTop - 0.05, i / 5), q = front(0, y, 0.0042), b = new THREE.Mesh(new THREE.SphereGeometry(0.0052, 10, 8), btn); b.scale.z = 0.45; p.pelvis.add(b); BT.push({ b, q }); }
  // the collar: a band that stands round the neck and folds over (it was a fat rolled ring)
  { const col = p.neck.children.find(o => o.isMesh && o.geometry.type === 'TorusGeometry'); if (col) { col.geometry.dispose(); col.geometry = new THREE.LatheGeometry([[0.0625, -0.004], [0.0665, -0.02], [0.0725, -0.021], [0.0738, -0.004], [0.0712, 0.019], [0.0668, 0.0235], [0.0632, 0.019], [0.0625, -0.004]].map(a => new THREE.Vector2(a[0], a[1])), 40); col.rotation.set(-0.2, 0, 0); col.position.set(0, 0.004, 0.002); col.castShadow = true; } }
  // the old pieces go: belly, chest, shell, the flat ball across the shoulders, the shoulder balls, the old placket and buttons
  for (const m of [p.belly, p.chest, p.shell]) if (m) m.visible = false; for (const s of ['l', 'r']) { const b = p.arm[s].sho.children[0]; if (b && b.isMesh) b.visible = false; }
  for (const o of [...sp2.children, ...sp1.children]) { if (!o.isMesh) continue; const t = o.geometry.type, c = o.material && o.material.color ? o.material.color.getHexString() : '';
    if (t === 'SphereGeometry' && Math.abs(o.position.y - 0.165) < 1e-3 && Math.abs(o.position.x) < 1e-3) o.visible = false;
    if (t === 'SphereGeometry' && o.geometry.parameters.radius === 0.0055) o.visible = false; if (t === 'TubeGeometry' && c === '8fb0d4') o.visible = false;
    if (t === 'TubeGeometry' && c === 'c0283a') { o.visible = false; o.userData.oldLanyard = true; } }
  // the lanyard and its card, laid on the new cloth (they ride the chest)
  { const lan = M('#c0283a', { r: 0.6 }), cy = Y2 + 0.035, toC = v => new V3(v.x, v.y - Y2, v.z), cz = front(0, cy, 0.007);
    if (p.card) { const clip = sp2.children.find(o => o.isMesh && o.geometry.type === 'BoxGeometry' && Math.abs(o.position.y - (p.card.position.y + 0.046)) < 1e-4); p.card.position.set(0, cy - Y2, cz.z); if (clip) clip.position.set(0, cy - Y2 + 0.046, cz.z + 0.002); }
    for (const s of [-1, 1]) { const ny = p.neck.position.y, pts = [new V3(0, ny + 0.012, -0.0668), new V3(s * 0.05, ny + 0.011, -0.044), new V3(s * 0.0672, ny + 0.006, -0.004), new V3(s * 0.061, ny - 0.006, 0.034)]; for (const [u, x] of [[0.12, 0.057], [0.34, 0.05], [0.62, 0.03], [0.86, 0.016], [1, 0.009]]) { const y = lerp(Y2 + 0.168, cy + 0.052, u); pts.push(toC(front(s * x, y, 0.0045))); } sp2.add(tube(pts, 0.0032, lan, 36, 5)); } }
  // how much each ring goes with the waist (sp1) and with the chest (sp2): the cloth inside the waistband stays with the hips
  const rest = Float32Array.from(g.attributes.position.array), m1 = new THREE.Matrix4(), m2 = new THREE.Matrix4(), v = new V3();
  const skin = (x, y, z, out) => { const w1 = sm((y - 0.095) / 0.11), w2 = sm((y - 0.215) / 0.13); let X = x, Y = y, Z = z; if (w1 > 0) { v.set(x, y - Y1, z).applyMatrix4(m1); X += (v.x - X) * w1; Y += (v.y - Y) * w1; Z += (v.z - Z) * w1; } if (w2 > 0) { v.set(x, y - Y2, z).applyMatrix4(m2); X += (v.x - X) * w2; Y += (v.y - Y) * w2; Z += (v.z - Z) * w2; } return out.set(X, Y, Z); };
  const o3 = new V3(); p.shirtTick = () => { sp1.updateMatrix(); sp2.updateMatrix(); m1.copy(sp1.matrix); m2.multiplyMatrices(sp1.matrix, sp2.matrix);
    const A = g.attributes.position.array; for (let i = 0, n = rest.length / 3; i < n; i++) { skin(rest[i * 3], rest[i * 3 + 1], rest[i * 3 + 2], o3); A[i * 3] = o3.x; A[i * 3 + 1] = o3.y; A[i * 3 + 2] = o3.z; }
    g.attributes.position.needsUpdate = true; g.computeVertexNormals(); { const N = g.attributes.normal.array; for (let i = 0; i <= NG; i++) { const a = i * (NS + 1) * 3, b = a + NS * 3; for (let k = 0; k < 3; k++) { const q = (N[a + k] + N[b + k]) / 2; N[a + k] = N[b + k] = q; } } }   /* no line of light where the surface closes on itself */
    const B = pg.attributes.position.array; for (let i = 0, n = pk0.length / 3; i < n; i++) { skin(pk0[i * 3], pk0[i * 3 + 1], pk0[i * 3 + 2], o3); B[i * 3] = o3.x; B[i * 3 + 1] = o3.y; B[i * 3 + 2] = o3.z; } pg.attributes.position.needsUpdate = true; pg.computeVertexNormals();
    for (const t of BT) { skin(t.q.x, t.q.y, t.q.z, o3); t.b.position.copy(o3); t.b.updateMatrixWorld(true); } };   /* moved before a draw, after the scene's own matrices were made: its matrix is made here, or the button would be a frame late */
  if (E && E.scene) { const sc = E.scene; (sc.userData.ticks = sc.userData.ticks || []).push(() => { if (p.root.visible) p.shirtTick(); }); if (!sc.userData.ticked) { sc.userData.ticked = true; const prev = sc.onBeforeRender; sc.onBeforeRender = function (...a) { if (prev) prev.apply(this, a); for (const f of sc.userData.ticks) f(); }; } }
  p.shirtTick(); return p;
}

/** Vikas: trousers as trousers (owner, 27 Nov 2026: "waist side, butt and front, and other joints need rework").
 *  The old lower body was a ball with a cylinder under each side: briefs seen from the front, no seat from the side, a ball at each knee.
 *  Now: one tube per leg, rebuilt every frame from INSIDE the seat through hip → knee → cuff (thigh and shin stay straight, the knee is one
 *  cloth bend with a kneecap and folds behind it, a crease runs down the front); a seat-and-waist piece with a real seat behind and a flat
 *  front; a belt with loops and a buckle where the shirt is tucked in. Nothing needs calling: it follows the legs by itself before each draw. */
export function trousersSon(p, E) {
  const pm = p.leg.l.th.children.find(c => c.isMesh).material, hipsBall = p.pelvis.children.find(c => c.isMesh && c.geometry.type === 'SphereGeometry' && c.material === pm);
  if (hipsBall) hipsBall.visible = false;
  for (const s of ['l', 'r']) for (const j of [p.leg[s].th, p.leg[s].sn]) for (const c of j.children) if (c.isMesh) c.visible = false;
  // ---- the seat and waist, in the pelvis's own space. y: 0 is the pelvis joint (hip joints at y -0.02, x ±0.095)
  const ascK = new Map(), lerpK = (keys, y) => { let a = ascK.get(keys); if (!a) ascK.set(keys, a = keys.slice().reverse()); return curveK(a, y); };   /* one smooth curve through the keys (eased steps between them showed as rings) */
  // below the hips the piece curves UNDER (27 Nov 2026, second pass): it used to stop at -0.11 with a straight lower edge, which stood proud of the
  // thighs as a flap in front and a shelf under the seat. Now it closes like a bowl inside the legs; only its middle shows, narrowing into the crotch.
  // The hips are the SEAT's (it is as wide as the thighs where they begin, so no leg top shows under the belt as a shelf); the legs come out of it lower down.
  const WX = [[0.105, 0.128], [0.07, 0.133], [0.03, 0.150], [0.0, 0.164], [-0.04, 0.174], [-0.08, 0.170], [-0.11, 0.150], [-0.14, 0.104], [-0.172, 0.046]], ZF = [[0.105, 0.102], [0.06, 0.104], [0.0, 0.100], [-0.06, 0.092], [-0.10, 0.076], [-0.14, 0.048], [-0.172, 0.014]], ZB = [[0.105, 0.098], [0.05, 0.106], [-0.01, 0.120], [-0.055, 0.124], [-0.10, 0.108], [-0.14, 0.072], [-0.172, 0.022]];
  const ring = (y, th, grow = 0) => { const c = Math.cos(th), s = Math.sin(th), n = 2.5, a = lerpK(WX, y) + grow, b = (c >= 0 ? lerpK(ZF, y) : lerpK(ZB, y)) + grow; const k = Math.pow(Math.pow(Math.abs(s), n) + Math.pow(Math.abs(c), n), -1 / n);
    let x = a * s * k, z = b * c * k; if (c < 0 && y < 0.02) z += 0.007 * Math.exp(-Math.pow(x / 0.018, 2)) * Math.min(1, (0.02 - y) / 0.05);   /* the seat's own middle line */ return [x, y, z]; };
  const surf = (ys, nr, grow, mat0) => { const pos = [], uv = [], idx = []; ys.forEach((y, i) => { for (let j = 0; j <= nr; j++) { const q = ring(y, j / nr * 2 * Math.PI, grow); pos.push(q[0], q[1], q[2]); uv.push(j / nr * 4, i / (ys.length - 1) * 0.6); } });
    for (let i = 0; i < ys.length - 1; i++) for (let j = 0; j < nr; j++) { const a = i * (nr + 1) + j, b = a + nr + 1; idx.push(a, b, a + 1, b, b + 1, a + 1); }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx); g.computeVertexNormals(); const m = new THREE.Mesh(g, mat0); m.castShadow = m.receiveShadow = true; return m; };
  const seatM = pm.clone(); seatM.side = THREE.DoubleSide; const YS = []; for (let i = 0; i <= 24; i++) YS.push(lerp(0.105, -0.172, i / 24));
  const seat = surf(YS, 48, 0, seatM); p.pelvis.add(seat);
  { const g = seat.geometry, P = g.attributes.position, n0 = P.count, pos = Array.from(P.array), uv = Array.from(g.attributes.uv.array), idx = Array.from(g.index.array); pos.push(0, -0.18, 0.0); uv.push(0.5, 1); const last = (YS.length - 1) * 49; for (let j = 0; j < 48; j++) idx.push(last + j, n0, last + j + 1);   /* the underside, closed (it hides between the legs) */
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx);
    g.deleteAttribute('normal'); g.computeVertexNormals(); }   /* the old normals are one point short: computeVertexNormals would keep them and leave the new point with NO normal, which draws as a black block on the GPU (27 Nov 2026) */
  // the part below the hips goes with the thighs (skinned to them): sitting, the seat no longer hangs under the legs like a bag, and in a stride each side follows its leg
  const seat0 = Float32Array.from(seat.geometry.attributes.position.array), seatW = new Float32Array(seat0.length / 3 * 2);
  for (let i = 0; i < seat0.length / 3; i++) { const x = seat0[i * 3], y = seat0[i * 3 + 1], t = clamp((-0.03 - y) / 0.12, 0, 1), w = t * t * (3 - 2 * t), sx = clamp((x + 0.04) / 0.08, 0, 1), sl = sx * sx * (3 - 2 * sx); seatW[i * 2] = w * sl; seatW[i * 2 + 1] = w * (1 - sl); }
  const seatTick = () => { const P = seat.geometry.attributes.position, A = P.array, v = new V3(), L = [[p.leg.l.th.quaternion, p.leg.l.hip.position], [p.leg.r.th.quaternion, p.leg.r.hip.position]];
    for (let i = 0, n = seat0.length / 3; i < n; i++) { const x = seat0[i * 3], y = seat0[i * 3 + 1], z = seat0[i * 3 + 2]; let X = x, Y = y, Z = z;
      for (let k = 0; k < 2; k++) { const w = seatW[i * 2 + k]; if (w < 1e-4) continue; const h = L[k][1]; v.set(x - h.x, y - h.y, z - h.z).applyQuaternion(L[k][0]); X += w * (v.x + h.x - x); Y += w * (v.y + h.y - y); Z += w * (v.z + h.z - z); }
      A[i * 3] = X; A[i * 3 + 1] = Y; A[i * 3 + 2] = Z; }
    P.needsUpdate = true; seat.geometry.computeVertexNormals(); };
  // the belt: a leather band standing a little proud of the waist, loops of the cloth over it, a buckle in front
  const leather = new THREE.MeshStandardMaterial({ color: '#2b1c12', roughness: 0.45 }), belt = surf([0.098, 0.094, 0.066, 0.062], 48, 0.0055, leather); belt.geometry.computeVertexNormals(); p.pelvis.add(belt);
  const loopM = new THREE.MeshStandardMaterial({ color: '#2f333c', roughness: 0.92 });   /* plain cloth: the trousers' own sheen made the loops shine pale blue */
  for (const th of [0.42, -0.42, 1.35, -1.35, Math.PI]) { const q = ring(0.08, th, 0.0072), lp = new THREE.Mesh(new THREE.BoxGeometry(0.0085, 0.043, 0.003), loopM); lp.position.set(q[0], q[1], q[2]); lp.rotation.y = Math.atan2(q[0], q[2]); p.pelvis.add(lp); }
  { const q = ring(0.08, 0, 0.008), brass = new THREE.MeshStandardMaterial({ color: '#b9a06a', roughness: 0.32, metalness: 0.85 }), bk = new THREE.Group(); bk.position.set(q[0], q[1], q[2]); p.pelvis.add(bk);   /* a buckle: a frame with the belt showing through and a prong, not a plate */
    for (const [w, h, x, y] of [[0.036, 0.0045, 0, 0.0145], [0.036, 0.0045, 0, -0.0145], [0.0045, 0.0335, -0.0158, 0], [0.0045, 0.0335, 0.0158, 0]]) { const b = new THREE.Mesh(new THREE.BoxGeometry(w, h, 0.005), brass); b.position.set(x, y, 0.001); bk.add(b); }
    { const pr = new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.0032, 0.0034), brass); pr.position.set(0.001, 0, 0.0028); bk.add(pr); }
    const fly = new THREE.Mesh(new THREE.BoxGeometry(0.0022, 0.085, 0.0022), new THREE.MeshStandardMaterial({ color: '#23262d', roughness: 0.9 })); const f0 = ring(0.015, 0.11, 0.0006); fly.position.set(f0[0], 0.015, f0[2]); fly.rotation.x = 0.09; p.pelvis.add(fly);
    for (const sg of [-1, 1]) { const pts = []; for (let i = 0; i <= 6; i++) { const th = Math.PI - sg * (0.40 + 0.42 * i / 6), q = ring(0.008 - 0.004 * i / 6, th, 0.0011); pts.push(q); } p.pelvis.add(tube(pts, 0.0013, fly.material, 14, 4)); } }   /* back pocket welts that lie ON the seat (a straight bar stuck out past its curve) */
  // ---- the legs: a tube each, through the joints as they are now
  const NA = 44, NR = 24, legs = [];
  for (const s of ['l', 'r']) { const g = new THREE.BufferGeometry(), n = (NA + 1) * (NR + 1), pos = new Float32Array(n * 3), nor = new Float32Array(n * 3), uv = new Float32Array(n * 2), idx = [];
    for (let i = 0; i < NA; i++) for (let j = 0; j < NR; j++) { const a = i * (NR + 1) + j, b = a + NR + 1; idx.push(a, a + 1, b, b, a + 1, b + 1); }
    g.setIndex(idx); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('normal', new THREE.BufferAttribute(nor, 3)); g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    const m = new THREE.Mesh(g, seatM); m.frustumCulled = false; m.castShadow = m.receiveShadow = true; p.root.add(m); legs.push({ lg: p.leg[s], g, pos, nor, uv, sg: p.leg[s].sg }); }
  const Lr = { thigh: p.L.thigh / p.size, shin: p.L.shin / p.size }; const UP0 = 0.036;   /* the leg begins this far above the hip joint, inside the seat where the seat is already as wide as the leg */
  p.legsTick = () => { p.root.updateWorldMatrix(true, true); seatTick(); const toL = v => p.root.worldToLocal(v), dirL = (o, v) => toL(o.localToWorld(v.clone())).sub(toL(o.localToWorld(new V3())));
    for (const a of legs) { const { lg } = a, H = toL(lg.hip.getWorldPosition(new V3())), K = toL(lg.sn.getWorldPosition(new V3())), A = toL(lg.an.getWorldPosition(new V3()));
      const upP = dirL(p.pelvis, new V3(0, 1, 0)).normalize(), side = dirL(p.pelvis, new V3(1, 0, 0)).normalize(), S0 = H.clone().addScaledVector(upP, UP0).addScaledVector(side, -a.sg * 0.016);
      const t1 = K.clone().sub(H).normalize(), t2 = A.clone().sub(K).normalize(), ax = new V3().crossVectors(t1, t2), bend = ax.length(), out = bend > 1e-4 ? t2.clone().sub(t1).normalize().negate() : dirL(p.pelvis, new V3(0, 0, 1)).normalize();   /* out: the way the kneecap points */
      const Kc = K.clone().addScaledVector(out, 0.006 + 0.016 * Math.min(1, bend * 1.4));
      const pts = [S0, H.clone().lerp(S0, 0.3), H.clone().lerp(K, 0.12), H.clone().lerp(K, 0.5), H.clone().lerp(K, 0.86), Kc, K.clone().lerp(A, 0.14), K.clone().lerp(A, 0.55), A.clone().addScaledVector(t2, 0.004)];
      const crv = new THREE.CatmullRomCurve3(pts, false, 'centripetal', 0.5), P = crv.getSpacedPoints(NA), T = P.map((q, i) => (i < NA ? P[i + 1].clone().sub(q) : q.clone().sub(P[i - 1])).normalize());
      const len = (() => { let l = 0; for (let i = 1; i <= NA; i++) l += P[i].distanceTo(P[i - 1]); return l; })(), uh = UP0 / len, uk = (UP0 + Lr.thigh) / len;
      let Nn = side.clone().sub(T[0].clone().multiplyScalar(side.dot(T[0]))).normalize(); const fw0 = dirL(p.pelvis, new V3(0, 0, 1)).normalize();
      const RK = [[0, 0.066], [uh, 0.080], [uh + 0.25 * (uk - uh), 0.086], [uh + 0.55 * (uk - uh), 0.082], [uk - 0.05, 0.0715], [uk, 0.069], [uk + 0.22 * (1 - uk), 0.0685], [uk + 0.6 * (1 - uk), 0.062], [1, 0.057]], R = u => curveK(RK, u);   /* thigh, knee, a little calf, cuff: one smooth curve (a kink in it shows as a ring round the leg) */
      for (let i = 0; i <= NA; i++) { if (i > 0) { const q = new THREE.Quaternion().setFromUnitVectors(T[i - 1], T[i]); Nn.applyQuaternion(q); Nn.sub(T[i].clone().multiplyScalar(Nn.dot(T[i]))).normalize(); }
        const B = new V3().crossVectors(T[i], Nn).normalize(), fsg = B.dot(i === 0 ? fw0 : out) >= 0 || bend < 1e-4 && B.dot(fw0) >= 0 ? 1 : -1, u = i / NA, r0 = R(u) + (u > 0.985 ? 0.003 : 0);
        for (let j = 0; j <= NR; j++) { const th = j / NR * 2 * Math.PI, cs = Math.cos(th), sn = Math.sin(th), front = sn * fsg;   /* front: +1 at the front of the leg, -1 behind */
          const crease = u > uh * 1.6 ? 0.045 * Math.exp(-Math.pow((1 - Math.max(0, front)) / 0.05, 2)) : 0;                       /* the pressed line down the front */
          const cap = 0.09 * Math.exp(-Math.pow((u - uk) / 0.05, 2)) * Math.max(0, front) * (0.4 + 0.6 * Math.min(1, bend * 1.5));  /* the kneecap under the cloth */
          const fold = -bend * 0.11 * Math.exp(-Math.pow((u - uk) / 0.07, 2)) * Math.max(0, -front) * (0.7 + 0.3 * Math.sin(u * 75 + th * 2));   /* soft: deep, fine folds drew as dark pits */   /* cloth gathering behind a bent knee */
          const r = r0 * (1 + crease + cap + fold + 0.01 * Math.sin(th * 3 + u * 9)), nx = Nn.x * cs + B.x * sn, ny = Nn.y * cs + B.y * sn, nz = Nn.z * cs + B.z * sn, k = i * (NR + 1) + j;
          a.pos[k * 3] = P[i].x + nx * r; a.pos[k * 3 + 1] = P[i].y + ny * r; a.pos[k * 3 + 2] = P[i].z + nz * r; a.nor[k * 3] = nx; a.nor[k * 3 + 1] = ny; a.nor[k * 3 + 2] = nz; a.uv[k * 2] = j / NR * 2; a.uv[k * 2 + 1] = u * 4; } }
      a.g.attributes.position.needsUpdate = true; a.g.attributes.normal.needsUpdate = true; a.g.attributes.uv.needsUpdate = true; } };
  // before every draw of the scene the legs are remade from the joints (a film need not call anything)
  if (E && E.scene) { const sc = E.scene; (sc.userData.ticks = sc.userData.ticks || []).push(() => { if (p.root.visible) p.legsTick(); }); if (!sc.userData.ticked) { sc.userData.ticked = true; const prev = sc.onBeforeRender; sc.onBeforeRender = function (...a) { if (prev) prev.apply(this, a); for (const f of sc.userData.ticks) f(); }; } }
  p.legsTick(); return p;
}

/** A schoolboy's uniform on the son's body: no office card, a short striped tie under the collar (it rides the chest). */
export function kidUniform(p) { const sp2 = p.sp2, Y2 = p.Y2;
  for (const o of sp2.children) { if (!o.isMesh) continue; const c = o.material && o.material.color ? o.material.color.getHexString() : ''; if (c === 'c0283a' || o === p.card || (o.geometry.type === 'BoxGeometry' && o.geometry.parameters.width === 0.014)) o.visible = false; }
  const cv = typeof document !== 'undefined' ? document.createElement('canvas') : null; let map = null;
  if (cv) { cv.width = 64; cv.height = 256; const g = cv.getContext('2d'); g.fillStyle = '#1b2850'; g.fillRect(0, 0, 64, 256); g.strokeStyle = '#b8343a'; g.lineWidth = 7; for (let y = -64; y < 320; y += 44) { g.beginPath(); g.moveTo(-4, y + 40); g.lineTo(68, y); g.stroke(); } g.strokeStyle = '#e2c46a'; g.lineWidth = 2; for (let y = -64; y < 320; y += 44) { g.beginPath(); g.moveTo(-4, y + 48); g.lineTo(68, y + 8); g.stroke(); } map = new THREE.CanvasTexture(cv); map.colorSpace = THREE.SRGBColorSpace; }
  const mt = new THREE.MeshStandardMaterial({ color: map ? '#ffffff' : '#1b2850', map, roughness: 0.6, side: THREE.DoubleSide });
  const yT = Y2 + 0.196, rows = [[0, 0.015], [0.012, 0.014], [0.026, 0.0085], [0.03, 0.0095], [0.07, 0.014], [0.11, 0.019], [0.138, 0.022], [0.15, 0.016], [0.166, 0.0005]];
  const pos = [], uv = [], idx = []; rows.forEach(([d, w], i) => { for (const sx of [-1, 0, 1]) { const q = p.front(sx * w, yT - d, sx ? 0.0045 : 0.0075); pos.push(q.x, q.y - Y2, q.z); uv.push(0.5 + sx * w / 0.05, 1 - d / 0.166); } if (i) { const a = (i - 1) * 3, b = i * 3; idx.push(a, b, a + 1, a + 1, b, b + 1, a + 1, b + 1, a + 2, a + 2, b + 1, b + 2); } });
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx); g.computeVertexNormals();
  const tie = new THREE.Mesh(g, mt); tie.castShadow = true; tie.receiveShadow = true; sp2.add(tie); p.tie = tie; return tie; }

/** A CHILD from the son's body (not the grown man scaled down): a shorter lower face, bigger eyes, a small nose, a small mouth, round cheeks, thin brows,
 *  a slender neck; each child its own face (o.face), skin (o.skin), hair (o.hair: 'fringe' | 'parted' | 'crew' | 'curly' | 'spiky'), build (o.slim). */
export function makeKid(E, o = {}) {
  const p = makeSon(E, { kid: 1, skin: o.skin, slim: o.slim ?? 0.8, shoulders: o.shoulders ?? 0.8, face: { jaw: 0.27, mouthW: 0.0255, cheek: 0.0075, cheekY: 0.045, chin: 0.006, lip: '#9a5850', lipA: 0.6, ...(o.face || {}) } });
  if (p.bagOff) p.bagOff(); kidHead(p, o); brows(p, o.brow ?? 0.0046, 0.0032, o.browColor || '#2b1d15'); p.head.position.y += 0.01; hairKid(p, o.hair || 'fringe', o);   /* a child's thinner, softer brows; a little more neck above the collar */ p.sleeveTo = o.sleeve ?? 0.56; p.sleeveCover = (o.sleeve ?? 0.56) * 0.95; p.sleeveR = o.sleeveR ?? 0.95; p.kid = true;
  p.root.traverse(m => { if (m.isMesh && m.material && m.material.color && ['8fb0d4', 'a8c8e8', '94b6da'].includes(m.material.color.getHexString())) { m.material = m.material.clone(); m.material.color.set(o.shirt || '#e9e8e2'); } });   /* the son's blue placket and collar pieces become the white of the school shirt */
  { const ct = kidCloth(o.shirt || '#e9e8e2', o.seed ?? 3); if (ct) for (const m of [p.chest.material, p.belly.material]) { m.map = ct; m.color.set('#ffffff'); m.sheen = 0.05; m.roughness = 0.82; m.needsUpdate = true; } }
  p.neck.scale.set(0.9, 1, 0.9); skinGlow(p);
  return p; }
/** light that goes into the skin and comes back out warm: a wrapped, reddened diffuse at the edge of the light (a cheap subsurface look) and a soft sheen; on the face, hands and arms */
export function skinGlow(p, o = {}) { const seen = new Set(), k = o.k ?? 0.55;
  p.root.traverse(m => { if (!m.isMesh || !m.material || Array.isArray(m.material) || seen.has(m.material)) return; const mt = m.material; if (!(mt === p.mSkin || mt.vertexColors || (p.face2 && m === p.face2) || mt.userData.skin)) return; seen.add(mt);
    if (mt.sheen !== undefined) { mt.sheen = 0.25; mt.sheenRoughness = 0.6; mt.sheenColor = new THREE.Color('#ff9a80'); mt.roughness = 0.58; mt.clearcoat = 0.06; mt.clearcoatRoughness = 0.5; }
    const ob = mt.onBeforeCompile; mt.onBeforeCompile = (sh, r) => { if (ob) ob(sh, r); sh.fragmentShader = sh.fragmentShader.replace('#include <lights_fragment_end>', `#include <lights_fragment_end>
      #if NUM_DIR_LIGHTS > 0
        for (int i = 0; i < NUM_DIR_LIGHTS; i++) { float nl = dot(normal, directionalLights[i].direction); float wrap = max(0.0, (nl + 0.45) / 1.45) - max(0.0, nl);
          reflectedLight.directDiffuse += diffuseColor.rgb * directionalLights[i].color * wrap * vec3(0.95, 0.38, 0.26) * ${k.toFixed(2)}; }
      #endif`); }; mt.needsUpdate = true; }); }

/** the head's lower part pressed up toward the eyes (children's faces are short below the eyes), eyes a little bigger, nose and brows smaller */
export function kidHead(p, o = {}) { const H = p.head, yE = o.eyeLine ?? 0.096, k0 = o.lower ?? 0.9, rigid = new Set();
  for (const e of p.eyes || []) { rigid.add(e.piv); if (e.brow) rigid.add(e.brow); }
  const warp = v => { const d = yE - v.y; if (d > 0) { const k = 1 - (1 - k0) * sm(d / 0.06); v.y = yE - d * k; const f = sm(d / 0.1); v.z *= 1 - 0.035 * f * (v.z > 0 ? 1 : 0); v.x *= 1 + 0.03 * Math.exp(-(((v.y - 0.045) / 0.05) ** 2)) * sm(d / 0.03); } return v; };   /* (the cheek fullness grows in from the eye line: a step there showed as a seam across the face) */
  const inv = new THREE.Matrix4(), mt = new THREE.Matrix4(), v = new V3(), w = new V3();
  H.updateMatrixWorld(true); const Hi = new THREE.Matrix4().copy(H.matrixWorld).invert();
  const top = H.children.slice();
  for (const c of top) { if (!c.visible) continue;
    if (rigid.has(c) || [...rigid].some(r => r === c || r.parent === c)) continue;
    const isEar = c.isMesh && c.geometry.type === 'SphereGeometry' && Math.abs(Math.abs(c.position.x) - 0.15) < 0.015;
    if (isEar) { warp(c.position); c.scale.multiplyScalar(1.06); continue; }
    c.traverse(m => { if (!m.isMesh || !m.visible) return; mt.multiplyMatrices(Hi, m.matrixWorld); inv.copy(mt).invert(); const g = m.geometry, P = g.attributes.position, n = P.count, old = Float32Array.from(P.array);
      for (let i = 0; i < n; i++) { v.set(P.getX(i), P.getY(i), P.getZ(i)).applyMatrix4(mt); warp(v).applyMatrix4(inv); P.setXYZ(i, v.x, v.y, v.z); }
      const MA = g.morphAttributes.position; if (MA && g.morphTargetsRelative) for (const D of MA) for (let i = 0; i < n; i++) { v.set(old[i * 3] + D.getX(i), old[i * 3 + 1] + D.getY(i), old[i * 3 + 2] + D.getZ(i)).applyMatrix4(mt); warp(v).applyMatrix4(inv); D.setXYZ(i, v.x - P.getX(i), v.y - P.getY(i), v.z - P.getZ(i)); }
      P.needsUpdate = true; g.deleteAttribute('normal'); g.computeVertexNormals(); g.computeBoundingSphere(); }); }
  const es = o.eyeScale ?? 1.05; for (const e of p.eyes || []) { e.piv.scale.multiplyScalar(es); e.piv.position.y -= 0.004; e.piv.position.x *= 1.03; e.piv.position.z -= 0.003; if (e.brow) { e.brow.scale.set(e.brow.scale.x * 0.92, e.brow.scale.y * 0.72, e.brow.scale.z); e.brow.position.y += 0.002; } }
  if (p.noseG) { const ns = o.noseScale ?? 0.8; p.noseG.updateMatrixWorld(true); const bb = new THREE.Box3().setFromObject(p.noseG); const root = new V3((bb.min.x + bb.max.x) / 2, bb.max.y, bb.min.z).applyMatrix4(Hi);
    p.noseG.traverse(m => { if (!m.isMesh) return; mt.multiplyMatrices(Hi, m.matrixWorld); inv.copy(mt).invert(); const P = m.geometry.attributes.position; for (let i = 0; i < P.count; i++) { v.set(P.getX(i), P.getY(i), P.getZ(i)).applyMatrix4(mt); w.copy(v).sub(root).multiply(new V3(ns, ns * 0.95, ns * 0.88)).add(root); w.applyMatrix4(inv); P.setXYZ(i, w.x, w.y, w.z); } P.needsUpdate = true; m.geometry.deleteAttribute('normal'); m.geometry.computeVertexNormals(); }); }
  return p; }
/** children's hair: a thick shell with locks flowing from the crown, in one of five cuts. Replaces the man's swept-back hair. */
export function hairKid(p, style = 'fringe', o = {}) {
  for (const c of p.head.children) { if (c === p.hairShell) c.visible = false; if (c.isMesh && c.geometry.type === 'TubeGeometry' && c.material === p.hairShell?.material) c.visible = false; }
  const g = new THREE.SphereGeometry(1, 220, 160), P = g.attributes.position, N = P.count, hl = new Float32Array(N), R = (o.seed ?? 3) * 1.37;
  const crown = new V3(-0.15, 0.93, -0.35).normalize();   /* the whorl, back of the top */
  const line = az => { const a = Math.abs(az);
    if (style === 'fringe') return a < 0.75 ? 0.6 + 0.035 * Math.sin(az * 23 + R) + 0.05 * (a / 0.75) ** 2 : a < 1.3 ? lerp(0.65, 0.4, sm((a - 0.75) / 0.55)) : a < 1.95 ? lerp(0.4, 0.22, sm((a - 1.3) / 0.65)) : lerp(0.22, -0.28, sm((a - 1.95) / 1.0));
    if (style === 'crew') return a < 0.6 ? 0.8 + 0.01 * Math.sin(az * 9) : a < 1.2 ? lerp(0.8, 0.5, sm((a - 0.6) / 0.6)) : a < 1.9 ? lerp(0.5, 0.25, sm((a - 1.2) / 0.7)) : lerp(0.25, -0.25, sm((a - 1.9) / 1.0));
    return a < 0.5 ? 0.66 + 0.03 * Math.sin(az * 13 + R) : a < 1.15 ? lerp(0.66, 0.42, sm((a - 0.5) / 0.65)) : a < 1.9 ? lerp(0.42, 0.23, sm((a - 1.15) / 0.75)) : lerp(0.23, -0.3, sm((a - 1.9) / 1.0)); };
  const partAz = style === 'parted' ? -0.45 : 99;
  for (let i = 0; i < N; i++) { const d = new V3(P.getX(i), P.getY(i), P.getZ(i)); const az = Math.atan2(d.x, d.z), el = Math.asin(clamp(d.y, -1, 1)), h = el - line(az);
    const top = Math.exp(-(((el - 1.0) / 0.5) ** 2)), side = Math.exp(-(((el - 0.7) / 0.3) ** 2)) * Math.exp(-(((Math.abs(az) - 1.25) / 0.7) ** 2)), front = Math.exp(-(((el - 0.72) / 0.25) ** 2)) * Math.exp(-((az / 0.8) ** 2));
    let T = style === 'crew' ? 0.006 + 0.004 * top : style === 'curly' ? 0.02 + 0.012 * top + 0.008 * side : style === 'spiky' ? 0.016 + 0.014 * top : 0.016 + 0.016 * top + 0.008 * side + (style === 'fringe' ? 0.006 * front : 0);
    // locks flow away from the crown: the ridge runs along the great circle through the crown
    const t = d.clone().sub(crown.clone().multiplyScalar(d.dot(crown))).normalize(), across = new V3().crossVectors(crown, t); const ang = Math.atan2(t.dot(new V3(0, 0, 1).cross(crown).normalize()), t.dot(new V3(0, 0, 1)));
    const fl = (style === 'crew' ? 40 : 22) * ang + 1.4 * Math.sin(d.dot(crown) * 9 + R) + 0.5 * Math.sin(ang * 57 + el * 7);
    let ridge = Math.pow(Math.abs(Math.sin(fl)), 0.6);
    if (style === 'curly') ridge = 0.5 + 0.5 * Math.sin(d.x * 61 + R) * Math.sin(d.y * 59) * Math.sin(d.z * 63 + R);
    if (style === 'spiky') ridge = Math.pow(Math.max(0, Math.sin(d.x * 37 + R) * Math.sin(d.z * 41 + d.y * 13)), 3) * 2.2;
    T += (style === 'crew' ? 0.0018 : 0.004 + 0.004 * top) * ridge - (partAz < 9 ? 0.006 * Math.exp(-(((az - partAz) / 0.045) ** 2)) * sm((el - 0.7) / 0.3) : 0);
    T *= lerp(0.3, 1, sm(clamp(h / 0.2, 0, 1))); hl[i] = h;
    const q = onHair(d, T); P.setXYZ(i, q.x, q.y, q.z); }
  g.setAttribute('hl', new THREE.BufferAttribute(hl, 1)); g.computeVertexNormals();
  const colr = o.hairColor || ['#120c08', '#1a110b', '#0f0b09', '#21170f'][(o.seed ?? 3) % 4];
  const m = new THREE.MeshPhysicalMaterial({ color: colr, roughness: style === 'parted' ? 0.42 : 0.6, sheen: 0.2, sheenRoughness: 0.6, sheenColor: new THREE.Color('#2a2018'), clearcoat: style === 'parted' ? 0.35 : 0.08, clearcoatRoughness: 0.45 });
  m.onBeforeCompile = sh => { sh.vertexShader = 'attribute float hl; varying float vHl;\n' + sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\n vHl = hl;'); sh.fragmentShader = 'varying float vHl;\n' + sh.fragmentShader.replace('#include <clipping_planes_fragment>', 'if (vHl < 0.0) discard;\n#include <clipping_planes_fragment>'); };
  const me = new THREE.Mesh(g, m); me.castShadow = me.receiveShadow = true; p.head.add(me);
  // loose locks that break the edge: a fringe falling over the forehead, tufts at the temples; a cowlick at the crown
  const P2 = (az, el, out) => onHair(dirAt(az, el), out), lk = [];
  if (style === 'fringe') for (let i = 0; i < 7; i++) { const az = -0.6 + i * 0.2 + 0.05 * Math.sin(i * 7 + R), len = 0.7 + 0.35 * ((i * 0.37 + R) % 1); lk.push(clump([P2(az + 0.1, 0.98, 0.03), P2(az + 0.05, 0.82, 0.03), P2(az, 0.68 - 0.04 * len, 0.022), P2(az - 0.04, 0.6 - 0.05 * len, 0.012)], 0.014, 0.6, m, { wave: 0.003 })); }
  if (style === 'spiky') for (let i = 0; i < 9; i++) { const az = -0.9 + i * 0.22, el = 0.95 + 0.25 * ((i * 0.53) % 1); lk.push(clump([P2(az, el, 0.02), P2(az, el + 0.02, 0.05), P2(az + 0.05, el + 0.05, 0.075)], 0.012, 0.5, m, {})); }
  if (style !== 'crew') lk.push(clump([P2(-0.15 + Math.PI, 0.93, 0.03), P2(-0.1 + Math.PI, 1.0, 0.055), P2(0.05 + Math.PI, 1.05, 0.06)], 0.012, 0.6, m, {}));
  for (const sd of [-1, 1]) for (let i = 0; i < 3; i++) lk.push(clump([P2(sd * (1.25 + i * 0.13), 0.6, 0.012), P2(sd * (1.32 + i * 0.13), 0.48, 0.012), P2(sd * (1.42 + i * 0.13), 0.4, 0.006)], 0.011, 0.6, m, { wave: 0.002 }));
  for (const L of lk) p.head.add(L); p.hairKid = me; return me; }

/** a school shirt's white cotton: a fine even weave, no stripes, the faintest creases */
function kidCloth(base, seed) { if (!browser) return null; const n = 512, c = document.createElement('canvas'); c.width = c.height = n; const g = c.getContext('2d'); g.fillStyle = base; g.fillRect(0, 0, n, n);
  let s = seed * 977 + 13; const R = () => (s = (s * 16807) % 2147483647) / 2147483647; for (let y = 0; y < n; y += 2) { g.fillStyle = `rgba(0,0,0,${0.012 + R() * 0.01})`; g.fillRect(0, y, n, 1); } for (let x = 0; x < n; x += 2) { g.fillStyle = `rgba(0,0,0,${0.008 + R() * 0.008})`; g.fillRect(x, 0, 1, n); }
  for (let i = 0; i < 9000; i++) { g.fillStyle = `rgba(${R() < 0.5 ? '0,0,0' : '255,255,255'},${0.015 + R() * 0.02})`; g.fillRect(R() * n, R() * n, 1 + R() * 2, 1); }
  for (let i = 0; i < 5; i++) { const x = R() * n, gr = g.createLinearGradient(x - 30, 0, x + 30, 0); gr.addColorStop(0, 'rgba(0,0,0,0)'); gr.addColorStop(0.5, 'rgba(60,50,40,0.03)'); gr.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = gr; g.fillRect(x - 30, 0, 60, n); }
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(3, 3); return t; }
