// PEHLI SALARY — the home: Maa's kitchen counter and stove (left), the front door (centre), Papa's puja shelf (right). Back wall at z = -2.1; everyone stands in front of it.
import { THREE, V3, rng } from './engine.js';
import * as K from './kit.js';
const C = h => new THREE.Color(h), HP = Math.PI / 2, PI = Math.PI;
const std = o => new THREE.MeshStandardMaterial(o);
const M = (geo, mat, x = 0, y = 0, z = 0, par, sh = true) => { const m = new THREE.Mesh(geo, mat); m.position.set(x, y, z); m.castShadow = sh; m.receiveShadow = true; if (par) par.add(m); return m; };
const box = (w, h, d, mat, x, y, z, par) => M(new THREE.BoxGeometry(w, h, d), mat, x, y, z, par);
const cyl = (r1, r2, h, mat, x, y, z, par, s = 24) => M(new THREE.CylinderGeometry(r1, r2, h, s), mat, x, y, z, par);
const sph = (r, mat, x, y, z, par, sx = 1, sy = 1, sz = 1) => { const m = M(new THREE.SphereGeometry(r, 20, 14), mat, x, y, z, par); m.scale.set(sx, sy, sz); return m; };
const glow = (h, k = 4) => new THREE.MeshBasicMaterial({ color: C(h).multiplyScalar(k) });
const tex = (w, h, draw) => K.canvasTex(w, h, draw);

function wallTex() { return tex(512, 512, (g, w, h) => { g.fillStyle = '#d9a466'; g.fillRect(0, 0, w, h); const R = rng(4); for (let i = 0; i < 9000; i++) { g.fillStyle = `rgba(${R() < 0.5 ? '120,70,30' : '255,235,200'},${0.03 + R() * 0.04})`; g.fillRect(R() * w, R() * h, 1 + R() * 3, 1 + R() * 3); }
  g.strokeStyle = 'rgba(120,60,20,0.08)'; g.lineWidth = 2; for (let y = 40; y < h; y += 128) for (let x = 40; x < w; x += 128) { g.beginPath(); g.arc(x, y, 18, 0, 7); g.stroke(); g.beginPath(); g.arc(x + 64, y + 64, 6, 0, 7); g.stroke(); } }); }
function tileTex() { return tex(512, 512, (g, w, h) => { const R = rng(8); for (let i = 0; i < 8; i++) for (let j = 0; j < 8; j++) { const k = 0.92 + R() * 0.1; g.fillStyle = `rgb(${200 * k | 0},${178 * k | 0},${140 * k | 0})`; g.fillRect(i * 64, j * 64, 64, 64); } g.strokeStyle = '#8a6a48'; g.lineWidth = 3; for (let i = 0; i <= 8; i++) { g.beginPath(); g.moveTo(i * 64, 0); g.lineTo(i * 64, h); g.moveTo(0, i * 64); g.lineTo(w, i * 64); g.stroke(); } }); }

/** Papa's portrait: an old studio photo, warm and slightly faded — grey hair, moustache, glasses, white kurta, a gentle smile */
export function papaPortrait() { return tex(360, 460, (g, w, h) => {
  const bg = g.createRadialGradient(w * 0.5, h * 0.4, 20, w * 0.5, h * 0.45, h * 0.7); bg.addColorStop(0, '#c9b48e'); bg.addColorStop(1, '#6e5a40'); g.fillStyle = bg; g.fillRect(0, 0, w, h);
  // kurta and shoulders
  g.fillStyle = '#efe8da'; g.beginPath(); g.moveTo(w * 0.08, h); g.quadraticCurveTo(w * 0.12, h * 0.68, w * 0.5, h * 0.64); g.quadraticCurveTo(w * 0.88, h * 0.68, w * 0.92, h); g.fill();
  g.strokeStyle = 'rgba(120,100,70,0.5)'; g.lineWidth = 3; g.beginPath(); g.moveTo(w * 0.5, h * 0.66); g.lineTo(w * 0.5, h * 0.86); g.stroke(); for (let i = 0; i < 3; i++) { g.fillStyle = '#b8a888'; g.beginPath(); g.arc(w * 0.5, h * (0.7 + i * 0.05), 4, 0, 7); g.fill(); }
  // neck and face
  g.fillStyle = '#a8724e'; g.fillRect(w * 0.43, h * 0.52, w * 0.14, h * 0.14);
  const fg = g.createRadialGradient(w * 0.46, h * 0.36, 10, w * 0.5, h * 0.4, w * 0.25); fg.addColorStop(0, '#c48a60'); fg.addColorStop(1, '#9a643e'); g.fillStyle = fg; g.beginPath(); g.ellipse(w * 0.5, h * 0.4, w * 0.19, h * 0.17, 0, 0, 7); g.fill();
  for (const s of [-1, 1]) { g.fillStyle = '#9a643e'; g.beginPath(); g.ellipse(w * (0.5 + s * 0.19), h * 0.41, w * 0.03, h * 0.04, 0, 0, 7); g.fill(); }
  // grey hair, receding
  g.fillStyle = '#8c8a86'; g.beginPath(); g.ellipse(w * 0.5, h * 0.27, w * 0.19, h * 0.09, 0, PI, 2 * PI); g.fill(); for (const s of [-1, 1]) { g.beginPath(); g.ellipse(w * (0.5 + s * 0.18), h * 0.33, w * 0.03, h * 0.06, 0, 0, 7); g.fill(); }
  // glasses, eyes, brows
  g.strokeStyle = '#3a2a1a'; g.lineWidth = 4; for (const s of [-1, 1]) { g.beginPath(); g.roundRect(w * (0.5 + s * 0.085) - w * 0.065, h * 0.355, w * 0.13, h * 0.06, 8); g.stroke(); g.fillStyle = '#2a1a10'; g.beginPath(); g.ellipse(w * (0.5 + s * 0.085), h * 0.39, 6, 4, 0, 0, 7); g.fill(); g.strokeStyle = '#5a5a5a'; g.lineWidth = 5; g.beginPath(); g.moveTo(w * (0.5 + s * 0.04), h * 0.335); g.quadraticCurveTo(w * (0.5 + s * 0.09), h * 0.32, w * (0.5 + s * 0.14), h * 0.338); g.stroke(); g.strokeStyle = '#3a2a1a'; g.lineWidth = 4; }
  g.beginPath(); g.moveTo(w * 0.47, h * 0.385); g.lineTo(w * 0.53, h * 0.385); g.stroke();
  // nose, moustache, smile
  g.strokeStyle = 'rgba(90,50,30,0.6)'; g.lineWidth = 3; g.beginPath(); g.moveTo(w * 0.5, h * 0.4); g.quadraticCurveTo(w * 0.47, h * 0.445, w * 0.49, h * 0.455); g.stroke();
  g.fillStyle = '#6a6662'; g.beginPath(); g.moveTo(w * 0.42, h * 0.485); g.quadraticCurveTo(w * 0.5, h * 0.455, w * 0.58, h * 0.485); g.quadraticCurveTo(w * 0.5, h * 0.475, w * 0.42, h * 0.485); g.fill();
  g.strokeStyle = '#5a2e22'; g.lineWidth = 3; g.beginPath(); g.moveTo(w * 0.45, h * 0.5); g.quadraticCurveTo(w * 0.5, h * 0.52, w * 0.55, h * 0.5); g.stroke();
  // age: grain, a soft vignette, a faded corner
  const R = rng(12); for (let i = 0; i < 6000; i++) { g.fillStyle = `rgba(${R() < 0.5 ? '40,30,20' : '255,240,210'},${R() * 0.06})`; g.fillRect(R() * w, R() * h, 2, 2); }
  const v = g.createRadialGradient(w / 2, h / 2, h * 0.3, w / 2, h / 2, h * 0.7); v.addColorStop(0, 'rgba(0,0,0,0)'); v.addColorStop(1, 'rgba(40,25,10,0.45)'); g.fillStyle = v; g.fillRect(0, 0, w, h); }); }

/** the salary envelope: kraft paper, the company name, "Salary — November 2026", a red seal */
export function envelopeMesh() { const t = tex(512, 268, (g, w, h) => { g.fillStyle = '#d6bc8c'; g.fillRect(0, 0, w, h); const R = rng(3); for (let i = 0; i < 4000; i++) { g.fillStyle = `rgba(${R() < 0.5 ? '120,90,50' : '255,245,220'},${R() * 0.08})`; g.fillRect(R() * w, R() * h, 2, 2); }
    g.fillStyle = '#1e4f96'; g.font = 'bold 30px PoppinsB'; g.fillText('TECHNO SOLUTIONS', 28, 50); g.fillStyle = '#3a2a1a'; g.font = '26px PoppinsM'; g.fillText('Salary · November 2026', 28, 150); g.fillText('Vikas Tandon', 28, 192);
    g.strokeStyle = '#b02a2a'; g.lineWidth = 5; g.beginPath(); g.arc(w - 74, h - 70, 40, 0, 7); g.stroke(); g.fillStyle = '#b02a2a'; g.font = 'bold 40px PoppinsB'; g.textAlign = 'center'; g.fillText('₹', w - 74, h - 56); });
  const b = std({ color: '#cdb282', roughness: 0.85 }); const m = new THREE.Mesh(new THREE.BoxGeometry(0.2, 0.105, 0.004), [b, b, b, b, std({ map: t, roughness: 0.8 }), b]); m.castShadow = true; m.receiveShadow = true; return m; }


// ------------------------------------------------------------------ the rest of the home: side walls and ceiling, a fan, the tubelight, fridge, curtains, rug, sofa, shoe rack, toran, clock, photos, plants
function dress(g, E, wallM, teak) { const S = E.scene; const R = rng(33);
  const sideL = M(new THREE.PlaneGeometry(4.2, 3.0), wallM, -3.45, 1.5, 0.0, g, false); sideL.rotation.y = HP; const sideR = M(new THREE.PlaneGeometry(4.2, 3.0), wallM, 3.0, 1.5, 0.0, g, false); sideR.rotation.y = -HP;
  const ceil = M(new THREE.PlaneGeometry(6.6, 4.2), std({ color: '#efe4d0', roughness: 0.95 }), -0.2, 2.95, 0.0, g, false); ceil.rotation.x = HP;
  for (const [x, ry] of [[-3.43, HP], [2.98, -HP]]) { const sk = box(0.03, 0.12, 4.2, std({ color: '#3a2416', roughness: 0.6 }), x, 0.06, 0, g); }
  // ceiling fan
  const fan = new THREE.Group(); fan.position.set(0.2, 2.72, -0.6); g.add(fan); const fm = std({ color: '#e9e2d4', roughness: 0.4, metalness: 0.2 }); cyl(0.012, 0.012, 0.22, fm, 0, 0.12, 0, fan, 8); cyl(0.09, 0.1, 0.07, fm, 0, 0, 0, fan); for (let i = 0; i < 3; i++) { const b = box(0.62, 0.008, 0.11, fm, 0, -0.01, 0, fan); b.geometry.translate(0.36, 0, 0); b.rotation.y = i * 2.094; b.rotation.x = 0.08; }
  // tubelight over the door, warm-white, with its glow on the wall
  box(1.0, 0.035, 0.05, std({ color: '#f4f0e8' }), 0.55, 2.42, -2.07, g); const tb = M(new THREE.CylinderGeometry(0.014, 0.014, 0.92, 12), glow('#fff3dc', 3.2), 0.55, 2.42, -2.035, g, false); tb.rotation.z = HP;
  const tl = new THREE.SpotLight('#ffe6c4', 7, 7, 1.35, 0.9, 1.4); tl.position.set(0.55, 2.38, -1.95); tl.target.position.set(0.3, 0.4, -0.6); tl.castShadow = true; tl.shadow.mapSize.set(2048, 2048); tl.shadow.bias = -0.0004; tl.shadow.radius = 6; g.add(tl, tl.target);
  // fridge in the left corner, magnets and a little calendar on it
  const fr = std({ color: '#c9cfd4', roughness: 0.3, metalness: 0.4 }); box(0.66, 1.7, 0.66, fr, -2.95, 0.85, -1.72, g); box(0.02, 0.5, 0.03, std({ color: '#9aa2a8', metalness: 0.8, roughness: 0.3 }), -2.65, 1.25, -1.38, g); box(0.62, 0.005, 0.005, std({ color: '#8a9096' }), -2.95, 1.1, -1.385, g); for (let i = 0; i < 4; i++) sph(0.018, std({ color: ['#e8402c', '#2a8ad8', '#f0c020', '#3aa84a'][i] }), -3.15 + i * 0.1, 1.45 + (i % 2) * 0.08, -1.385, g, 1, 1, 0.4);
  // upper cabinets and a hood over the stove
  const cab2 = std({ color: '#2f6f6a', roughness: 0.55 }); box(1.4, 0.6, 0.35, cab2, -1.65, 2.25, -1.92, g); for (let i = 0; i < 2; i++) box(0.68, 0.56, 0.02, std({ color: '#367f78', roughness: 0.5 }), -1.99 + i * 0.7, 2.25, -1.74, g); M(new THREE.ConeGeometry(0.34, 0.25, 4, 1, true), std({ color: '#2a2a2e', metalness: 0.6, roughness: 0.35, side: THREE.DoubleSide }), -0.6, 2.05, -1.86, g).rotation.y = PI / 4;
  // steel utensil rack with plates and glasses
  box(0.7, 0.02, 0.18, std({ color: '#c8ccd2', metalness: 0.9, roughness: 0.3 }), -2.3, 1.62, -1.99, g); for (let i = 0; i < 5; i++) { const pl = cyl(0.09, 0.09, 0.008, std({ color: '#dfe3e8', metalness: 1, roughness: 0.2 }), -2.55 + i * 0.12, 1.72, -2.0, g, 24); pl.rotation.x = HP - 0.15; }
  // gas cylinder peeking from the open cabinet
  // the window on the left wall with cotton curtains
  const wsky = M(new THREE.PlaneGeometry(0.9, 1.0), new THREE.MeshBasicMaterial({ color: C('#1c2c64').multiplyScalar(1.3) }), -3.44, 1.55, -0.35, g, false); wsky.rotation.y = HP;
  for (let i = 0; i < 30; i++) { const s = M(new THREE.SphereGeometry(0.008 + R() * 0.01, 6, 4), glow(R() < 0.5 ? '#ffd890' : '#ffeec8', 2 + R() * 3), -3.445, 1.1 + R() * 0.8, -0.75 + R() * 0.8, g, false); }
  const cur = std({ color: '#d8a43a', roughness: 0.9, side: THREE.DoubleSide }); for (const s of [-1, 1]) { const cg = new THREE.PlaneGeometry(0.42, 1.5, 24, 1); const q = cg.attributes.position; for (let i = 0; i < q.count; i++) q.setZ(i, Math.sin(q.getX(i) * 40) * 0.025); cg.computeVertexNormals(); const cm = M(cg, cur, -3.4, 1.45, -0.35 + s * 0.62, g); cm.rotation.y = HP; }
  box(0.02, 0.02, 1.8, std({ color: '#8a6a3a', metalness: 0.6 }), -3.39, 2.22, -0.35, g);
  // floor rug: a block-printed dhurrie
  const rug = K.canvasTex(512, 320, (c, w, h) => { c.fillStyle = '#a83a2a'; c.fillRect(0, 0, w, h); c.fillStyle = '#e8c890'; c.fillRect(14, 14, w - 28, h - 28); c.fillStyle = '#a83a2a'; c.fillRect(28, 28, w - 56, h - 56); for (let y = 50; y < h - 40; y += 40) for (let x = 50; x < w - 40; x += 40) { c.fillStyle = (x + y) % 80 ? '#e8c890' : '#2a5a6a'; c.beginPath(); c.moveTo(x, y - 12); c.lineTo(x + 12, y); c.lineTo(x, y + 12); c.lineTo(x - 12, y); c.fill(); } });
  const rg = M(new THREE.PlaneGeometry(2.0, 1.3), std({ map: rug, roughness: 0.95 }), 0.4, 0.004, -0.55, g, false); rg.rotation.x = -HP;
  // sofa against the right wall, cushions; a low table with a newspaper
  const sofa = std({ color: '#5a3a5e', roughness: 0.85 }); box(0.8, 0.42, 1.8, sofa, 2.55, 0.21, -0.2, g); box(0.22, 0.75, 1.8, sofa, 2.85, 0.48, -0.2, g); for (const z of [-1.1, 0.7]) box(0.8, 0.6, 0.18, sofa, 2.55, 0.3, z, g); for (const [z, c] of [[-0.6, '#e0a030'], [0.2, '#2f6f8f']]) { const cu = box(0.12, 0.34, 0.34, std({ color: c, roughness: 0.9 }), 2.68, 0.6, z, g); cu.rotation.z = -0.15; }
  box(0.5, 0.04, 0.8, teak, 1.85, 0.4, 0.05, g); for (const [x, z] of [[1.64, -0.3], [2.06, -0.3], [1.64, 0.4], [2.06, 0.4]]) cyl(0.02, 0.02, 0.38, teak, x, 0.19, z, g, 8); box(0.3, 0.01, 0.4, std({ color: '#e8e4d8', roughness: 0.9 }), 1.85, 0.425, 0.1, g);
  // shoe rack by the door, chappals and his office shoes kicked off
  box(0.5, 0.4, 0.26, teak, 1.32, 0.2, -1.94, g); for (let i = 0; i < 3; i++) box(0.12, 0.025, 0.2, std({ color: ['#6a3a20', '#2a2a2a', '#b07040'][i], roughness: 0.6 }), 1.18 + i * 0.14, 0.42, -1.93, g);
  for (const [x, a] of [[0.92, 0.3], [1.04, -0.2]]) { const sh = box(0.1, 0.06, 0.27, std({ color: '#1a1410', roughness: 0.35 }), x, 0.03, -1.62, g); sh.rotation.y = a; }
  // toran over the door: marigolds and mango leaves
  for (let i = 0; i < 26; i++) { const x = 0.0 + i * 0.04, y = 2.2 - Math.sin(i / 25 * PI) * 0.05; sph(0.02, std({ color: i % 2 ? '#f29a12' : '#e8701a', roughness: 0.85 }), x, y, -2.06, g); if (i % 3 === 0) { const lf = sph(0.03, std({ color: '#3a7a2a', roughness: 0.7 }), x, y - 0.07, -2.065, g, 0.45, 1.4, 0.15); } }
  // wall clock, family photos, a switchboard
  const clk = new THREE.Group(); clk.position.set(-0.6, 2.35, -2.08); g.add(clk); cyl(0.16, 0.16, 0.03, std({ color: '#5a3a22' }), 0, 0, 0, clk, 32).rotation.x = HP; const cf = M(new THREE.CircleGeometry(0.14, 32), std({ map: K.canvasTex(256, 256, (c, w, h) => { c.fillStyle = '#fbf6ea'; c.fillRect(0, 0, w, h); c.fillStyle = '#2a2a2a'; c.font = 'bold 26px PoppinsB'; c.textAlign = 'center'; c.textBaseline = 'middle'; for (let i = 1; i <= 12; i++) { const a = i / 12 * 2 * PI; c.fillText(String(i), w / 2 + Math.sin(a) * 96, h / 2 - Math.cos(a) * 96); } c.strokeStyle = '#2a2a2a'; c.lineWidth = 6; c.beginPath(); c.moveTo(w / 2, h / 2); c.lineTo(w / 2 + 40, h / 2 - 20); c.stroke(); c.lineWidth = 4; c.beginPath(); c.moveTo(w / 2, h / 2); c.lineTo(w / 2 - 10, h / 2 - 80); c.stroke(); }), roughness: 0.6 }), 0, 0, 0.017, clk, false);
  const fam = (seed, bg) => K.canvasTex(200, 260, (c, w, h) => { c.fillStyle = bg; c.fillRect(0, 0, w, h); const R2 = rng(seed); for (let k = 0; k < 3; k++) { const x = 45 + k * 55, y = 120 + R2() * 20; c.fillStyle = ['#efe8da', '#7e2230', '#a8c8e8'][k]; c.beginPath(); c.ellipse(x, y + 90, 30, 70, 0, 0, 7); c.fill(); c.fillStyle = '#b07a52'; c.beginPath(); c.arc(x, y, 20, 0, 7); c.fill(); c.fillStyle = k === 0 ? '#8a8a86' : '#2a1c14'; c.beginPath(); c.arc(x, y - 8, 20, PI, 2 * PI); c.fill(); } });
  for (const [x, y, s, bg] of [[2.3, 1.75, 4, '#7a9ac8'], [2.62, 1.6, 8, '#c8a878']]) { box(0.24, 0.3, 0.02, teak, x, y, -2.085, g); M(new THREE.PlaneGeometry(0.2, 0.26), std({ map: fam(s, bg), roughness: 0.6 }), x, y, -2.073, g, false); }
  box(0.14, 0.2, 0.02, std({ color: '#f4f0e8', roughness: 0.5 }), 1.18, 1.25, -2.09, g); for (let i = 0; i < 3; i++) box(0.025, 0.04, 0.012, std({ color: '#3a3a3a' }), 1.15 + i * 0.03, 1.28, -2.078, g);
  // money plant on the shelf top, a tulsi pot by the puja corner
  for (let i = 0; i < 14; i++) sph(0.028, std({ color: '#3f8a3a', roughness: 0.6 }), -2.6 + Math.sin(i * 1.7) * 0.03, 1.72 - i * 0.05, -1.4 - Math.cos(i * 1.3) * 0.05, g, 1, 0.5, 0.6);
  cyl(0.13, 0.1, 0.32, std({ color: '#a8542a', roughness: 0.8 }), 2.55, 0.16, -1.75, g); for (let i = 0; i < 40; i++) sph(0.022, std({ color: i % 3 ? '#4a8a3a' : '#2f6a2a', roughness: 0.7 }), 2.55 + (R() - 0.5) * 0.22, 0.35 + R() * 0.35, -1.75 + (R() - 0.5) * 0.22, g, 1, 0.6, 1);
  return { fan, tl }; }

export function home(E) { const S = E.scene, g = new THREE.Group(); S.add(g); S.background = C('#0a0806');
  const wt = wallTex(); wt.wrapS = wt.wrapT = THREE.RepeatWrapping; wt.repeat.set(4, 2.4); const wallM = std({ map: wt, roughness: 0.92 });
  // back wall with a door opening (x 0.08 … 1.02, up to 2.1)
  const DX0 = 0.08, DX1 = 1.02, DH = 2.1, BZ = -2.1;
  M(new THREE.PlaneGeometry(4.08 + DX0, 3.4), wallM, (-4 + DX0) / 2 - 0.04, 1.7, BZ, g, false); M(new THREE.PlaneGeometry(4, 3.4), wallM, DX1 + 2, 1.7, BZ, g, false); M(new THREE.PlaneGeometry(DX1 - DX0, 1.3), wallM, (DX0 + DX1) / 2, DH + 0.65, BZ, g, false);
  const ft = tileTex(); ft.wrapS = ft.wrapT = THREE.RepeatWrapping; ft.repeat.set(6, 6); const fl = M(new THREE.PlaneGeometry(8, 8), std({ map: ft, roughness: 0.35 }), 0, 0, 0.5, g, false); fl.rotation.x = -HP;
  box(8, 0.12, 0.03, std({ color: '#3a2416', roughness: 0.6 }), 0, 0.06, BZ + 0.015, g);
  // the door: a teak frame, the corridor beyond in a dim warm light, the door itself swung open into the corridor
  const teak = std({ map: K.woodTex(21, '#6a3e20'), roughness: 0.5 }); for (const [w, h, x, y] of [[0.08, DH + 0.08, DX0 - 0.04, DH / 2], [0.08, DH + 0.08, DX1 + 0.04, DH / 2], [DX1 - DX0 + 0.16, 0.08, (DX0 + DX1) / 2, DH + 0.04]]) box(w, h, 0.12, teak, x, y, BZ + 0.02, g);
  const cor = std({ color: '#3a2a1e', roughness: 0.9 }); box(DX1 - DX0 + 0.4, 3, 0.05, cor, (DX0 + DX1) / 2, 1.5, BZ - 1.6, g); box(0.05, 3, 1.6, cor, DX0 - 0.03, 1.5, BZ - 0.8, g); box(0.05, 3, 1.6, cor, DX1 + 0.03, 1.5, BZ - 0.8, g); box(1.2, 0.05, 1.6, cor, (DX0 + DX1) / 2, DH + 0.02, BZ - 0.8, g);
  const cfl = M(new THREE.PlaneGeometry(1.2, 1.6), std({ color: '#5a4030', roughness: 0.6 }), (DX0 + DX1) / 2, 0.002, BZ - 0.8, g, false); cfl.rotation.x = -HP;
  const leaf = new THREE.Group(); leaf.position.set(DX1, 0, BZ - 0.03); leaf.rotation.y = -1.25; g.add(leaf); box(0.9, 2.04, 0.045, teak, -0.45, 1.02, 0, leaf); sph(0.022, std({ color: '#c9a046', metalness: 0.9, roughness: 0.3 }), -0.82, 1.0, 0.04, leaf);
  const cl = new THREE.PointLight('#ffb070', 1.2, 3.5, 1.6); cl.position.set((DX0 + DX1) / 2, 1.9, BZ - 1.0); g.add(cl);
  // kitchen: counter, stove with a steel patila, tins, a shelf of jars, tiles, a window into the night
  const cab = std({ color: '#2f6f6a', roughness: 0.55 }), stone = std({ color: '#3a3a40', roughness: 0.25, metalness: 0.2 }), steel = std({ color: '#d8dce2', metalness: 1, roughness: 0.22 });
  box(2.2, 0.86, 0.6, cab, -1.05, 0.43, -1.78, g); box(2.3, 0.05, 0.66, stone, -1.05, 0.885, -1.76, g); for (let i = 0; i < 3; i++) box(0.66, 0.66, 0.02, std({ color: '#367f78', roughness: 0.5 }), -1.8 + i * 0.72, 0.43, -1.47, g);
  const tl = K.canvasTex(512, 128, (c, w, h) => { c.fillStyle = '#eadfc8'; c.fillRect(0, 0, w, h); c.strokeStyle = '#cbbb98'; c.lineWidth = 3; for (let x = 0; x <= w; x += 32) { c.beginPath(); c.moveTo(x, 0); c.lineTo(x, h); c.stroke(); } for (let y = 0; y <= h; y += 32) { c.beginPath(); c.moveTo(0, y); c.lineTo(w, y); c.stroke(); } });
  M(new THREE.PlaneGeometry(2.3, 0.6), std({ map: tl, roughness: 0.25 }), -1.05, 1.21, BZ + 0.012, g, false);
  box(0.6, 0.05, 0.4, std({ color: '#15151a', roughness: 0.4, metalness: 0.6 }), -0.6, 0.935, -1.76, g); for (const x of [-0.76, -0.44]) { const b = M(new THREE.TorusGeometry(0.07, 0.01, 6, 20), std({ color: '#0a0a0c', roughness: 0.6 }), x, 0.966, -1.76, g); b.rotation.x = HP; }
  const fm = M(new THREE.TorusGeometry(0.05, 0.008, 6, 20), glow('#4a8cff', 3), -0.76, 0.972, -1.76, g, false); fm.rotation.x = HP; const flame = new THREE.PointLight('#5a8cff', 0.6, 0.8, 2); flame.position.set(-0.76, 1.0, -1.76); g.add(flame);
  const pot = new THREE.Group(); pot.position.set(-0.76, 0.975, -1.76); g.add(pot); M(new THREE.CylinderGeometry(0.11, 0.095, 0.13, 28, 1, true), new THREE.MeshStandardMaterial({ color: '#d8dce2', metalness: 1, roughness: 0.25, side: THREE.DoubleSide }), 0, 0.065, 0, pot); cyl(0.095, 0.095, 0.006, steel, 0, 0.003, 0, pot); const rimT = M(new THREE.TorusGeometry(0.11, 0.006, 6, 28), steel, 0, 0.13, 0, pot); rimT.rotation.x = HP; cyl(0.105, 0.105, 0.004, std({ color: '#c9802a', roughness: 0.6 }), 0, 0.1, 0, pot, 28);
  for (const [x, r, h] of [[-1.25, 0.07, 0.2], [-1.4, 0.06, 0.16], [-1.53, 0.05, 0.13]]) { cyl(r, r, h, steel, x, 0.91 + h / 2, -1.88, g); cyl(r * 1.02, r * 1.02, 0.015, steel, x, 0.92 + h, -1.88, g); }
  box(1.3, 0.03, 0.2, std({ color: '#5a3a22', roughness: 0.6 }), -0.75, 1.75, -1.98, g); for (let i = 0; i < 5; i++) { const c = ['#c8402c', '#e0a030', '#6a8a3a', '#8a5a2a', '#d8c8a0'][i]; cyl(0.05, 0.05, 0.13, new THREE.MeshPhysicalMaterial({ color: c, roughness: 0.15, clearcoat: 1 }), -1.25 + i * 0.25, 1.83, -1.98, g, 16); cyl(0.052, 0.052, 0.02, std({ color: '#c9a046', metalness: 0.9, roughness: 0.3 }), -1.25 + i * 0.25, 1.905, -1.98, g, 16); }
  const sky = M(new THREE.PlaneGeometry(0.9, 0.8), new THREE.MeshBasicMaterial({ color: C('#16245a').multiplyScalar(1.3) }), -2.95, 1.6, BZ + 0.006, g, false); const wf = std({ color: '#e8dccb', roughness: 0.6 }); for (const [bw, bh, bx, by] of [[1.0, 0.06, 0, 0.4], [1.0, 0.06, 0, -0.4], [0.06, 0.8, -0.45, 0], [0.06, 0.8, 0.45, 0], [0.04, 0.8, 0, 0]]) box(bw, bh, 0.05, wf, -2.95 + bx, 1.6 + by, BZ + 0.03, g);
  // the calendar by the door
  const cal = K.canvasTex(256, 340, (c, w, h) => { c.fillStyle = '#fbf6ea'; c.fillRect(0, 0, w, h); c.fillStyle = '#c8402c'; c.fillRect(0, 0, w, h * 0.3); c.fillStyle = '#fff'; c.font = 'bold 34px PoppinsB'; c.textAlign = 'center'; c.fillText('NOVEMBER', w / 2, h * 0.19); c.fillStyle = '#3a3a40'; c.font = '20px PoppinsM'; for (let i = 0; i < 30; i++) c.fillText(String(i + 1), 26 + (i % 7) * 34, h * 0.42 + Math.floor(i / 7) * 40); c.strokeStyle = '#c8402c'; c.lineWidth = 4; c.beginPath(); c.arc(26 + 6 * 34, h * 0.42 + 3 * 40 - 7, 16, 0, 7); c.stroke(); });
  M(new THREE.PlaneGeometry(0.3, 0.4), std({ map: cal, roughness: 0.8 }), -1.55, 1.62, BZ + 0.01, g, false);
  // Papa's corner: a teak shelf on the wall right of the door, his garlanded photo, a diya, incense, marigolds
  const P = new V3(1.75, 1.02, -1.98); const shelf = box(0.7, 0.035, 0.3, teak, P.x, P.y - 0.018, P.z + 0.03, g); box(0.66, 0.1, 0.02, teak, P.x, P.y - 0.08, P.z + 0.17, g);
  const ph = new THREE.Group(); ph.position.set(P.x, P.y + 0.215, P.z - 0.055); ph.rotation.x = -0.12; g.add(ph); box(0.33, 0.42, 0.025, std({ color: '#4a2a14', roughness: 0.4 }), 0, 0, 0, ph); M(new THREE.PlaneGeometry(0.27, 0.35), std({ map: papaPortrait(), color: '#a89a88', roughness: 0.7 }), 0, 0, 0.0135, ph, false);
  M(new THREE.PlaneGeometry(0.27, 0.35), new THREE.MeshPhysicalMaterial({ color: '#ffffff', roughness: 0.05, transparent: true, opacity: 0.08, clearcoat: 1 }), 0, 0, 0.016, ph, false);
  // marigold garland draped over the frame
  const gcv = new THREE.CatmullRomCurve3([[-0.16, 0.2, 0.03], [-0.17, 0.0, 0.04], [-0.12, -0.17, 0.045], [0, -0.24, 0.05], [0.12, -0.17, 0.045], [0.17, 0.0, 0.04], [0.16, 0.2, 0.03]].map(a => new V3(...a)));
  const R = rng(17); for (let i = 0; i <= 64; i++) { const p = gcv.getPoint(i / 64); const f = sph(0.016, std({ color: R() < 0.45 ? '#f29a12' : R() < 0.8 ? '#e8701a' : '#f4c020', roughness: 0.85 }), p.x, p.y, p.z, ph); f.scale.setScalar(0.85 + R() * 0.35); }
  for (const s of [-1, 1]) { const tz = new THREE.CatmullRomCurve3([[s * 0.16, 0.2, 0.03], [s * 0.18, 0.12, 0.04], [s * 0.17, 0.02, 0.045]].map(a => new V3(...a))); for (let i = 0; i < 6; i++) { const p = tz.getPoint(i / 6); sph(0.006, std({ color: '#2e7a2a' }), p.x + s * 0.01, p.y, p.z + 0.012, ph); } }
  // diya: a clay lamp with a living flame and its light
  const diya = new THREE.Group(); diya.position.set(P.x - 0.2, P.y, P.z + 0.06); g.add(diya); const clay = std({ color: '#9a4a24', roughness: 0.8 }); const dl = M(new THREE.SphereGeometry(0.045, 24, 12, 0, 2 * PI, HP, HP), clay, 0, 0.042, 0, diya); dl.scale.set(1, 0.7, 1); dl.rotation.x = PI; dl.material.side = THREE.DoubleSide; cyl(0.03, 0.02, 0.01, clay, 0, 0.005, 0, diya);
  cyl(0.04, 0.04, 0.004, std({ color: '#c9a046', metalness: 0.3, roughness: 0.3 }), 0, 0.035, 0, diya); const fl1 = M(new THREE.SphereGeometry(0.012, 16, 12), glow('#ffb347', 6), 0.03, 0.06, 0, diya, false); fl1.scale.set(0.7, 1.9, 0.7); const fl2 = M(new THREE.SphereGeometry(0.006, 12, 8), glow('#fff4d0', 9), 0.03, 0.052, 0, diya, false); fl2.scale.set(0.8, 1.6, 0.8);
  const dLight = new THREE.PointLight('#ffa040', 1.0, 2.6, 1.8); dLight.position.set(P.x - 0.17, P.y + 0.12, P.z + 0.12); dLight.castShadow = true; dLight.shadow.mapSize.set(1024, 1024); dLight.shadow.bias = -0.002; g.add(dLight);
  // incense in a little brass stand, with a ribbon of smoke
  const inc = new V3(P.x + 0.2, P.y, P.z + 0.05); cyl(0.025, 0.03, 0.02, std({ color: '#c9a046', metalness: 0.9, roughness: 0.3 }), inc.x, inc.y + 0.01, inc.z, g); const st = cyl(0.0016, 0.0016, 0.2, std({ color: '#5a2a1a' }), inc.x, inc.y + 0.11, inc.z, g, 6); st.rotation.z = -0.15; sph(0.003, glow('#ff6a20', 5), inc.x + 0.015, inc.y + 0.208, inc.z, g);
  const smoke = []; const smM = new THREE.MeshBasicMaterial({ color: '#d8d0c8', transparent: true, opacity: 0.12, depthWrite: false }); for (let i = 0; i < 40; i++) { const s = M(new THREE.SphereGeometry(0.006, 8, 6), smM.clone(), 0, 0, 0, g, false); s.castShadow = false; smoke.push(s); }
  const smokeTick = T => smoke.forEach((s, i) => { const u = ((T * 0.18 + i / smoke.length) % 1); const y = u * 0.5; s.position.set(inc.x + 0.015 + Math.sin(u * 9 + T * 0.8) * 0.02 * u + u * 0.03, inc.y + 0.21 + y, inc.z + Math.cos(u * 7 + T * 0.6) * 0.015 * u); s.scale.setScalar(1 + u * 3.5); s.material.opacity = 0.16 * (1 - u) * Math.min(1, u * 8); });
  for (let i = 0; i < 7; i++) sph(0.018, std({ color: i % 2 ? '#f0a020' : '#e8701a', roughness: 0.8 }), P.x - 0.06 + i * 0.022, P.y + 0.015, P.z + 0.13, g);
  const flick = T => { const f = 0.85 + 0.12 * Math.sin(T * 13.1) + 0.06 * Math.sin(T * 29.7 + 1) + 0.04 * Math.sin(T * 7.3); dLight.intensity = 1.0 * f; fl1.scale.set(0.7, 1.9 * (0.9 + 0.12 * Math.sin(T * 17)), 0.7); fl1.position.x = 0.03 + 0.002 * Math.sin(T * 11); };
  // room light: a warm tube light high on the wall, a soft fill, the night window
  S.add(new THREE.HemisphereLight('#9a8a80', '#3a2418', 0.22)); const key = new THREE.SpotLight('#ffd2a0', 5, 9, 1.2, 0.8, 1.4); key.position.set(0, 2.7, 0.6); key.target.position.set(0.2, 0.9, -1.4); key.castShadow = true; key.shadow.mapSize.set(2048, 2048); key.shadow.bias = -0.0004; key.shadow.radius = 4; g.add(key, key.target);
  const win = new THREE.SpotLight('#7f9cff', 10, 7, 0.7, 0.8, 1.5); win.position.set(-2.95, 1.9, -1.9); win.target.position.set(-1, 0.9, 0); g.add(win, win.target);
  const steam = []; const stM = new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.1, depthWrite: false }); for (let i = 0; i < 18; i++) { const s = M(new THREE.SphereGeometry(0.02, 8, 6), stM.clone(), 0, 0, 0, g, false); steam.push(s); }
  const steamTick = T => steam.forEach((s, i) => { const u = ((T * 0.3 + i / steam.length) % 1); s.position.set(-0.76 + Math.sin(u * 6 + i) * 0.04 * u, 1.11 + u * 0.45, -1.76 + Math.cos(u * 5 + i) * 0.03 * u); s.scale.setScalar(1 + u * 2.5); s.material.opacity = 0.1 * (1 - u) * Math.min(1, u * 6); });
  const D = dress(g, E, wallM, teak);
  // air: dust motes drifting slowly through the warm light, and a soft shaft of the tube light falling toward the stove
  const NM = 420, mp = new Float32Array(NM * 3), mseed = []; for (let i = 0; i < NM; i++) mseed.push([-2.6 + R() * 4.8, 0.4 + R() * 2.0, -2.0 + R() * 2.6, R() * 6.28, 0.3 + R()]);
  const mg = new THREE.BufferGeometry(); mg.setAttribute('position', new THREE.BufferAttribute(mp, 3));
  const mc = document.createElement ? document.createElement('canvas') : null; let mtx = null; if (mc && mc.getContext) { mc.width = mc.height = 32; const q = mc.getContext('2d'); if (q && q.createRadialGradient) { const gr = q.createRadialGradient(16, 16, 0, 16, 16, 16); gr.addColorStop(0, 'rgba(255,240,210,1)'); gr.addColorStop(1, 'rgba(255,240,210,0)'); q.fillStyle = gr; q.fillRect(0, 0, 32, 32); mtx = new THREE.CanvasTexture(mc); } }
  const motes = new THREE.Points(mg, new THREE.PointsMaterial({ color: '#ffe2b0', size: 0.012, map: mtx, transparent: true, opacity: 0.55, depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true })); g.add(motes);
  const moteTick = T => { for (let i = 0; i < NM; i++) { const s = mseed[i]; mp[i * 3] = s[0] + Math.sin(T * 0.11 * s[4] + s[3]) * 0.12; mp[i * 3 + 1] = s[1] + Math.sin(T * 0.07 * s[4] + s[3] * 2) * 0.08 + ((T * 0.012 * s[4]) % 0.3); mp[i * 3 + 2] = s[2] + Math.cos(T * 0.09 * s[4] + s[3]) * 0.1; } mg.attributes.position.needsUpdate = true; };
  { const cg = new THREE.ConeGeometry(0.9, 2.2, 40, 1, true); cg.translate(0, -1.1, 0); const shaft = new THREE.Mesh(cg, new THREE.MeshBasicMaterial({ color: '#ffcf96', transparent: true, opacity: 0.018, depthWrite: false, side: THREE.DoubleSide, blending: THREE.AdditiveBlending })); shaft.position.set(0, 2.7, 0.6); shaft.lookAt(0.2, 0.9, -1.4); shaft.rotateX(-Math.PI / 2); g.add(shaft); }
  E.setEnv(s => { s.background = C('#3a2a20'); s.add(new THREE.HemisphereLight('#806a60', '#100808', 1)); }, 0.38);
  return { g, P, fan: D.fan, photo: ph, diya, dLight, tick: T => { flick(T); smokeTick(T); steamTick(T); moteTick(T); D.fan.rotation.y = T * 5.5; }, stove: new V3(-0.6, 0.98, -1.76), door: new V3((DX0 + DX1) / 2, 0, BZ) };
}
