// school.js — an Indian school's ground for CodeScape films: a two-storey block with a verandah, pillars and grilled windows, the assembly stage and flag,
// the brass bell, potted plants, school bags, the compound wall. Built in metres; the school block faces +z.
import { THREE, V3, clamp, lerp, rng } from './engine.js';
import { T, clock } from './nature.js';

const TL = new THREE.TextureLoader(), browser = typeof document !== 'undefined' && typeof Image !== 'undefined' && typeof window !== 'undefined'; let AO = null, PH = null;
async function plasterImgs() { if (!AO) [AO, PH] = await Promise.all([TL.loadAsync(new URL('./tex/plaster_ao.jpg', import.meta.url).href), TL.loadAsync(new URL('./tex/plaster_h.jpg', import.meta.url).href)]); PH.colorSpace = THREE.NoColorSpace; PH.wrapS = PH.wrapT = THREE.RepeatWrapping; return { ao: AO, h: PH }; }
/** painted plaster: the paint colour, weathered by the stain picture; an optional band of another colour at the foot (dado). rep = how many 4 m × 2 m tiles across and up */
async function plaster(colour, o = {}) { if (!browser) return new THREE.MeshStandardMaterial({ color: colour, roughness: 0.93 }); const { ao, h } = await plasterImgs(); const c = document.createElement('canvas'); c.width = 1024; c.height = 512; const g = c.getContext('2d');
  g.fillStyle = colour; g.fillRect(0, 0, 1024, 512); g.globalCompositeOperation = 'multiply'; g.drawImage(ao.image, 0, 0, 1024, 512); g.globalCompositeOperation = 'source-over';
  if (o.band) { g.fillStyle = o.band; g.globalAlpha = 0.92; g.fillRect(0, 512 * (1 - o.bandH), 1024, 512 * o.bandH); g.globalAlpha = 1; g.globalCompositeOperation = 'multiply'; g.drawImage(ao.image, 0, 0, 1024, 512); g.globalCompositeOperation = 'source-over';
    g.fillStyle = 'rgba(255,248,230,.85)'; g.fillRect(0, 512 * (1 - o.bandH) - 6, 1024, 6); }
  /* rain streaks and a darker foot */
  const gr = g.createLinearGradient(0, 380, 0, 512); gr.addColorStop(0, 'rgba(60,40,20,0)'); gr.addColorStop(1, `rgba(60,40,20,${o.foot ?? 0.28})`); g.fillStyle = gr; g.fillRect(0, 380, 1024, 132);
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; t.anisotropy = 8; if (o.rep) t.repeat.set(...o.rep);
  const hh = h.clone(); hh.needsUpdate = true; if (o.rep) hh.repeat.set(...o.rep);
  return new THREE.MeshStandardMaterial({ map: t, bumpMap: hh, bumpScale: 1.4, roughness: 0.93 }); }
const M = (c, o = {}) => new THREE.MeshStandardMaterial({ color: c, roughness: o.r ?? 0.85, metalness: o.m ?? 0, ...(o.x || {}) });
function box(parent, w, h, d, mat, x, y, z, o = {}) { const g = new THREE.BoxGeometry(w, h, d); if (o.uvScale) { const uv = g.attributes.uv, p = g.attributes.position, n = g.attributes.normal; for (let i = 0; i < uv.count; i++) { const ax = Math.abs(n.getX(i)), ay = Math.abs(n.getY(i)); const a = ax > 0.5 ? p.getZ(i) : p.getX(i), b = ay > 0.5 ? p.getZ(i) : p.getY(i); uv.setXY(i, (a + x) / 4, (b + y) / 2); } }
  const m = new THREE.Mesh(g, mat); m.position.set(x, y, z); m.castShadow = o.cast ?? true; m.receiveShadow = true; parent.add(m); return m; }
function canvasTex(w, h, draw) { if (!browser) return null; const c = document.createElement('canvas'); c.width = w; c.height = h; draw(c.getContext('2d'), w, h); const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 8; return t; }

/** a window: a deep frame, glass that reflects the sky, an iron grille painted green, a sunshade over it */
function windowAt(G, x, y, z, w, h, mats) { box(G, w + 0.12, h + 0.12, 0.08, mats.frame, x, y, z + 0.02); box(G, w, h, 0.02, mats.glass, x, y, z + 0.03, { cast: false });
  for (let i = 1; i < 6; i++) box(G, 0.018, h, 0.018, mats.grille, x - w / 2 + i * w / 6, y, z + 0.09); for (let i = 1; i < 4; i++) box(G, w, 0.018, 0.018, mats.grille, x, y - h / 2 + i * h / 4, z + 0.09);
  const sh = box(G, w + 0.4, 0.05, 0.5, mats.slab, x, y + h / 2 + 0.22, z + 0.22); sh.rotation.x = 0.18; }
function doorAt(G, x, y0, z, mats) { box(G, 1.06, 2.2, 0.1, mats.frame, x, y0 + 1.1, z + 0.02); for (const s of [-1, 1]) { const d = box(G, 0.48, 2.1, 0.05, mats.door, x + s * 0.25, y0 + 1.05, z + 0.06); for (let k = 0; k < 3; k++) box(G, 0.36, 0.5, 0.02, mats.door2, x + s * 0.25, y0 + 0.45 + k * 0.65, z + 0.095); } }
function pot(G, x, z, y, r, seed) { const R = rng(seed), pts = []; for (let i = 0; i <= 10; i++) { const u = i / 10; pts.push(new THREE.Vector2(r * (0.62 + 0.38 * Math.sin(u * 2.6) ** 0.7 + (u > 0.92 ? 0.08 : 0)), u * r * 1.5)); }
  const p = new THREE.Mesh(new THREE.LatheGeometry(pts, 24), M('#a5563a', { r: 0.9 })); p.position.set(x, y, z); p.castShadow = p.receiveShadow = true; G.add(p);
  const soil = new THREE.Mesh(new THREE.CircleGeometry(r * 0.95, 20), M('#3a2618')); soil.rotation.x = -Math.PI / 2; soil.position.set(x, y + r * 1.42, z); G.add(soil);
  const leaf = new THREE.MeshStandardMaterial({ color: '#3f7a2e', roughness: 0.6, side: THREE.DoubleSide });
  for (let i = 0; i < 16; i++) { const a = R() * 6.28, L = r * (1.4 + R() * 1.4), lg = new THREE.PlaneGeometry(L * 0.28, L); lg.translate(0, L / 2, 0); const pos = lg.attributes.position; for (let k = 0; k < pos.count; k++) { const yy = pos.getY(k) / L; pos.setX(k, pos.getX(k) * Math.sin(Math.PI * Math.min(1, yy * 1.1 + 0.05)) ** 0.6); pos.setZ(k, -yy * yy * L * 0.35); } lg.computeVertexNormals();
    const l = new THREE.Mesh(lg, leaf); l.position.set(x, y + r * 1.4, z); l.rotation.set(-0.35 - R() * 0.5, a, 0, 'YXZ'); l.castShadow = true; G.add(l); } }
/** the national flag on its pole: the cloth ripples in the breeze (vertex wave), the wheel drawn with its 24 spokes */
function flag(E, G, x, z, H) { clock(E);
  const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.05, H, 16), M('#e8e6e0', { r: 0.4, m: 0.3 })); pole.position.set(x, H / 2, z); pole.castShadow = true; G.add(pole);
  const knob = new THREE.Mesh(new THREE.SphereGeometry(0.06, 16, 12), M('#c9a24a', { r: 0.3, m: 0.8 })); knob.position.set(x, H + 0.04, z); G.add(knob);
  for (let k = 0; k < 3; k++) { const b = new THREE.Mesh(new THREE.CylinderGeometry(1.4 - k * 0.35, 1.5 - k * 0.35, 0.25, 32), M('#d9d2c2', { r: 0.9 })); b.position.set(x, 0.125 + k * 0.25, z); b.castShadow = b.receiveShadow = true; G.add(b); }
  const tx = canvasTex(600, 400, (g, w, h) => { g.fillStyle = '#FF9933'; g.fillRect(0, 0, w, h / 3); g.fillStyle = '#FFFFFF'; g.fillRect(0, h / 3, w, h / 3); g.fillStyle = '#138808'; g.fillRect(0, 2 * h / 3, w, h / 3);
    g.strokeStyle = '#000080'; g.fillStyle = '#000080'; const cx = w / 2, cy = h / 2, R = h / 6 * 0.95; g.lineWidth = 4; g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.stroke(); g.beginPath(); g.arc(cx, cy, R * 0.18, 0, Math.PI * 2); g.fill();
    g.lineWidth = 2; for (let i = 0; i < 24; i++) { const a = i / 24 * Math.PI * 2; g.beginPath(); g.moveTo(cx, cy); g.lineTo(cx + Math.cos(a) * R, cy + Math.sin(a) * R); g.stroke(); } });
  const W = 1.5, Hf = 1.0, geo = new THREE.PlaneGeometry(W, Hf, 30, 12); geo.translate(W / 2, 0, 0);
  const mat = new THREE.MeshStandardMaterial({ map: tx, side: THREE.DoubleSide, roughness: 0.8 });
  mat.onBeforeCompile = sh => { sh.uniforms.uT = T; sh.vertexShader = 'uniform float uT;\n' + sh.vertexShader.replace('#include <begin_vertex>', `#include <begin_vertex>
      float u = position.x / ${W.toFixed(2)}; float wv = sin(position.x * 4.2 - uT * 6.0) * 0.08 + sin(position.x * 7.3 - uT * 9.1 + position.y * 2.0) * 0.035;
      transformed.z += wv * u; transformed.y -= u * u * 0.05; transformed.x -= abs(wv) * u * 0.2;`).replace('#include <beginnormal_vertex>', `#include <beginnormal_vertex>
      { float uu = position.x / ${W.toFixed(2)}; float d = cos(position.x * 4.2 - uT * 6.0) * 4.2 * 0.08 + cos(position.x * 7.3 - uT * 9.1 + position.y * 2.0) * 7.3 * 0.035; objectNormal = normalize(vec3(-d * uu, 0.0, 1.0)); }`); };
  const fl = new THREE.Mesh(geo, mat); fl.position.set(x + 0.04, H - 0.55, z); fl.rotation.y = -0.5; fl.castShadow = true; G.add(fl);
  const dm = new THREE.MeshDepthMaterial({ depthPacking: THREE.RGBADepthPacking }); dm.onBeforeCompile = mat.onBeforeCompile; fl.customDepthMaterial = dm; return fl; }

/** a two-storey school block whose verandah edge runs along local x at local z = 0 (it faces local +z). o: at [x, z], rotY, len, name (false: no board), tank */
export async function schoolBlock(E, o = {}) {
  const G = new THREE.Group(); E.scene.add(G); G.position.set(o.at?.[0] ?? 0, 0, o.at?.[1] ?? -11); G.rotation.y = o.rotY || 0; const LEN = o.len ?? 30, Z = 0, X0 = -LEN / 2, X1 = LEN / 2;
  const [wall, wallG, col, slab, plinth] = await Promise.all([plaster('#e9c97c', { band: '#8a3428', bandH: 0.34, rep: [LEN / 4, 1] }), plaster('#efd59a', { rep: [LEN / 4, 1.8] }), plaster('#f1dfae', { band: '#8a3428', bandH: 0.18, rep: [0.2, 1.6] }), plaster('#ece3cf', { rep: [LEN / 4, 0.3], foot: 0.1 }), plaster('#9c9488', { rep: [LEN / 4, 0.25], foot: 0.4 })]);
  const mats = { frame: M('#2f5f48', { r: 0.6 }), glass: new THREE.MeshPhysicalMaterial({ color: '#1e2a30', roughness: 0.08, metalness: 0.1, envMapIntensity: 1.6 }), grille: M('#244a38', { r: 0.5, m: 0.4 }), slab, door: M('#2d6a52', { r: 0.55 }), door2: M('#285e48', { r: 0.6 }) };
  const BZ = Z - 2.4;   /* the rooms' front wall */
  box(G, LEN + 0.6, 0.45, 3.0, plinth, 0, 0.225, Z - 1.25);                                          /* plinth */
  for (let k = 0; k < 3; k++) box(G, 3.2, 0.15, 0.32, plinth, 0, 0.075 + k * 0.15, Z + 0.55 - k * 0.32);   /* steps up to the verandah */
  box(G, LEN, 3.2, 0.25, wall, 0, 0.45 + 1.6, BZ);  box(G, LEN, 3.4, 0.25, wallG, 0, 3.85 + 1.7, BZ);   /* rooms' walls, ground and upper */
  box(G, LEN + 0.7, 0.26, 3.3, slab, 0, 3.72, Z - 1.25); box(G, LEN + 0.7, 0.3, 3.3, slab, 0, 7.2, Z - 1.25);   /* floor and roof slabs */
  box(G, LEN + 0.72, 0.12, 0.08, M('#8a3428'), 0, 3.66, Z + 0.4, { cast: false }); box(G, LEN + 0.72, 0.12, 0.08, M('#8a3428'), 0, 7.15, Z + 0.4, { cast: false });
  for (let x = X0; x <= X1 + 0.01; x += 3.0) { box(G, 0.38, 3.15, 0.38, col, x, 0.45 + 1.575, Z); box(G, 0.34, 3.2, 0.34, col, x, 3.85 + 1.6, Z); }   /* pillars */
  // the upper railing: a low wall with a pierced pattern between the pillars
  const jali = canvasTex(512, 128, (g, w, h) => { g.fillStyle = '#f0deb0'; g.fillRect(0, 0, w, h); g.fillStyle = '#000'; for (let i = 0; i < 16; i++) { const cx = 16 + i * 32; g.beginPath(); g.moveTo(cx, 30); g.lineTo(cx + 11, 64); g.lineTo(cx, 98); g.lineTo(cx - 11, 64); g.closePath(); g.fill(); } });
  const jm = new THREE.MeshStandardMaterial({ map: jali, alphaMap: jali, alphaTest: 0.5, roughness: 0.9, side: THREE.DoubleSide }); if (jali) { jali.wrapS = THREE.RepeatWrapping; jali.repeat.set(LEN / 3, 1); }
  if (browser) { const c2 = document.createElement('canvas'); c2.width = 512; c2.height = 128; const g = c2.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, 512, 128); g.fillStyle = '#000'; for (let i = 0; i < 16; i++) { const cx = 16 + i * 32; g.beginPath(); g.moveTo(cx, 30); g.lineTo(cx + 11, 64); g.lineTo(cx, 98); g.lineTo(cx - 11, 64); g.closePath(); g.fill(); } const at = new THREE.CanvasTexture(c2); at.wrapS = THREE.RepeatWrapping; at.repeat.set(LEN / 3, 1); jm.alphaMap = at; jm.map = null; jm.color.set('#efd9a8'); }
  box(G, LEN, 0.9, 0.12, jm, 0, 3.85 + 0.45, Z); box(G, LEN + 0.1, 0.1, 0.2, mats.slab, 0, 3.85 + 0.95, Z);
  box(G, LEN + 0.7, 0.75, 0.15, wallG, 0, 7.35 + 0.37, Z + 0.35);   /* roof parapet */
  // rooms: a door and two windows in each bay, on both floors
  for (let x = X0 + 1.5; x < X1; x += 3.0) for (const y0 of [0.45, 3.85]) { const bay = Math.round((x - X0 - 1.5) / 3); if (bay % 2 === 0) doorAt(G, x - 0.6, y0, BZ + 0.12, mats); else windowAt(G, x - 0.6, y0 + 1.55, BZ + 0.12, 1.1, 1.2, mats); windowAt(G, x + 0.75, y0 + 1.6, BZ + 0.12, 0.9, 1.1, mats); }
  // the name board on the roof, a water tank, a clock over the steps
  if (o.name !== false) { const nb = canvasTex(2048, 300, (g, w, h) => { g.fillStyle = '#f3ead2'; g.fillRect(0, 0, w, h); g.strokeStyle = '#8a3428'; g.lineWidth = 14; g.strokeRect(10, 10, w - 20, h - 20); g.fillStyle = '#7a2a20'; g.font = '180px Baloo'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('ज्ञान ज्योति विद्यालय', w / 2, h / 2 + 14); });
  const board = new THREE.Mesh(new THREE.PlaneGeometry(9, 1.3), new THREE.MeshStandardMaterial({ map: nb, roughness: 0.8 })); board.position.set(0, 8.75, Z + 0.45); board.castShadow = true; G.add(board); box(G, 9.2, 1.45, 0.12, M('#e8dcc0'), 0, 8.75, Z + 0.36);
  for (const s of [-1, 1]) box(G, 0.1, 1.5, 0.1, M('#7a6a58'), s * 4.2, 8.2, Z + 0.3); }
  if (o.tank !== false) { const tank = new THREE.Mesh(new THREE.CylinderGeometry(0.75, 0.75, 1.4, 28), M('#1d1f22', { r: 0.4 })); tank.position.set(9.5, 8.05, Z - 2.0); tank.castShadow = true; G.add(tank);
  for (let k = 0; k < 4; k++) { const rib = new THREE.Mesh(new THREE.TorusGeometry(0.76, 0.02, 6, 40), M('#26292d', { r: 0.4 })); rib.rotation.x = Math.PI / 2; rib.position.set(9.5, 7.5 + k * 0.38, Z - 2.0); G.add(rib); } }
  // the brass bell hanging from the verandah beam, with its striker
  let bell = null; if (o.bell !== false) { bell = new THREE.Mesh(new THREE.LatheGeometry([0, 0.1, 0.2, 0.32, 0.5, 0.75, 0.95, 1.0].map((u, i) => new THREE.Vector2(0.05 + 0.13 * Math.pow(u, 1.6) + (i === 7 ? 0.02 : 0), 0.3 - u * 0.3)), 32), M('#c9993c', { r: 0.28, m: 0.9 }));
  bell.position.set(X0 + 6.0 + 0.5, 2.85, Z - 0.1); bell.castShadow = true; G.add(bell); { const ch = new THREE.Mesh(new THREE.CylinderGeometry(0.008, 0.008, 0.45, 6), M('#555', { m: 0.8, r: 0.4 })); ch.position.set(X0 + 6.5, 3.33, Z - 0.1); G.add(ch); } }
  // potted plants along the plinth edge, and school bags lined against the wall
  const R = rng(4); for (let x = X0 + 2.2; x < X1 - 1; x += 3.0) if (Math.abs(x) > 2.2) pot(G, x + (R() - 0.5) * 0.3, Z + 0.15, 0.45, 0.17 + R() * 0.06, Math.floor(R() * 999));
  const bagC = ['#1f4e8a', '#b8322a', '#2f6b3a', '#6b3a8a', '#d08a1f', '#283a52', '#9a2a4a'];
  for (let i = 0; i < 18; i++) { const x = X0 + 4 + i * 0.62 + R() * 0.15; if (Math.abs(x) < 2.2) continue; const b = box(G, 0.3, 0.38, 0.16, M(bagC[i % bagC.length], { r: 0.75 }), x, 0.45 + 0.19, BZ + 0.25); b.rotation.set(-0.12, (R() - 0.5) * 0.4, (R() - 0.5) * 0.12); box(G, 0.24, 0.14, 0.04, M(bagC[(i + 3) % bagC.length], { r: 0.75 }), x, 0.45 + 0.12, BZ + 0.35); }
  // a long bench under the verandah
  for (const bx of [-9, 7]) { box(G, 2.2, 0.05, 0.35, M('#6b4a2e', { r: 0.7 }), bx, 0.45 + 0.45, BZ + 0.6); for (const s of [-1, 1]) box(G, 0.05, 0.45, 0.3, M('#3a2c22', { r: 0.5, m: 0.4 }), bx + s * 1.0, 0.45 + 0.225, BZ + 0.6); }
  return { group: G, bell, z: Z };
}

/** the assembly stage the children face, with its flag. at [x, z] = the stage's front edge centre */
export async function stage(E, o = {}) { const G = new THREE.Group(); E.scene.add(G); const [x, z] = o.at || [0, 7.5];
  const top = await plaster('#d8cfbd', { rep: [1.5, 0.3], foot: 0.2 }), face = await plaster('#c2b6a0', { rep: [1.5, 0.3], foot: 0.4 });
  box(G, 6, 0.6, 2.6, face, x, 0.3, z + 1.3); box(G, 6.1, 0.06, 2.7, top, x, 0.63, z + 1.3);
  for (let k = 0; k < 3; k++) box(G, 1.6, 0.2, 0.3, face, x, 0.1 + k * 0.2, z - 0.3 + k * 0.3 - 0.3 * 2 + 0.6);
  const mic = new THREE.Mesh(new THREE.CylinderGeometry(0.012, 0.012, 1.5, 8), M('#222', { m: 0.7, r: 0.3 })); mic.position.set(x - 0.5, 0.66 + 0.75, z + 0.6); G.add(mic);
  const lect = box(G, 0.6, 1.0, 0.45, M('#5a3a22', { r: 0.6 }), x + 0.6, 0.66 + 0.5, z + 0.9);
  flag(E, G, x + (o.flagDx ?? 4.2), z + (o.flagDz ?? 1.0), 6.5);
  return G; }

/** the compound wall the shadow walks along: plaster with a maroon foot, a coping on top, pillars, a bougainvillea spilling over, grass at its foot */
export async function compoundWall(E, o = {}) { const G = new THREE.Group(); E.scene.add(G); const Z = o.z ?? 0, X0 = o.x0 ?? -8, X1 = o.x1 ?? 8, H = o.h ?? 2.6, LEN = X1 - X0;
  const face = await plaster('#e6b95e', { band: '#8c3b2a', bandH: 0.3, rep: [LEN / 4, 1] });
  const wallM = box(G, LEN, H, 0.25, face, (X0 + X1) / 2, H / 2, Z - 0.125); wallM.castShadow = false;
  const cope = await plaster('#efe2c2', { rep: [LEN / 4, 0.2], foot: 0.15 });
  box(G, LEN + 0.1, 0.1, 0.4, cope, (X0 + X1) / 2, H + 0.05, Z - 0.1);
  const pil = await plaster('#ecc777', { band: '#8c3b2a', bandH: 0.3, rep: [0.2, 1] });
  for (const x of o.pillars || [X0 + 0.2, X1 - 0.2]) { box(G, 0.42, H + 0.25, 0.42, pil, x, (H + 0.25) / 2, Z - 0.05); box(G, 0.5, 0.08, 0.5, cope, x, H + 0.29, Z - 0.05); }
  // the motto, painted by hand
  const mt = canvasTex(2048, 256, (g, w, h) => { g.clearRect(0, 0, w, h); g.fillStyle = 'rgba(122,42,30,.88)'; g.font = '150px Baloo'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(o.motto || 'सच बोलो  •  रोशनी में रहो', w / 2, h / 2 + 10); });
  const mm = new THREE.Mesh(new THREE.PlaneGeometry(o.mottoW ?? 4.2, (o.mottoW ?? 4.2) / 8), new THREE.MeshStandardMaterial({ map: mt, transparent: true, roughness: 0.9, depthWrite: false })); mm.position.set(o.mottoX ?? 1.8, o.mottoY ?? 2.0, Z + 0.003); mm.receiveShadow = true; G.add(mm);
  return G; }
