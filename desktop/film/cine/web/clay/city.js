// CITY — a model city at night, made the way a miniature is: card buildings with painted faces and pin-prick windows,
// balconies, water tanks, hoardings and shop signs; toy traffic; sponge trees; a painted sky with cotton clouds and a paper moon.
import { THREE, mat, ball, limb, rbox, tube, mesh, group, paintTex, V3, lerp, clamp, hash } from './clay.js';

const WALLS = ['#8a7a66', '#75848a', '#9a8474', '#66768c', '#93886e', '#7e7268', '#66807a', '#8c6f66', '#6f6a80'];
const SIGNS = [['चाय', '#ffb347', '#3a1c08'], ['मेडिकल', '#7CFFB2', '#06301a'], ['किराना', '#ffd75e', '#3a2a06'], ['सलून', '#ff7a9a', '#3a0816'], ['STD · PCO', '#8fd0ff', '#06223a'], ['वड़ा पाव', '#ffcf6a', '#402006'], ['टेलर', '#c9a0ff', '#1e0a3a'], ['HOTEL', '#ff6a5a', '#3a0a06']];
const CW = 26, CH = 34;                                      // one window bay of a painted face, in pixels (0.3 m × 0.34 m of model)
export const BAYW = 0.3, FLOORH = 0.34;

/** A painted building face and the light that comes out of it: { map, emis, cols, rows }. Drawn once, shared by many buildings. */
export function facade(seed, cols = 6, rows = 10, o = {}) {
  const W = cols * CW, H = rows * CH, lit = o.lit ?? 0.34, wall = new THREE.Color(WALLS[seed % WALLS.length]).multiplyScalar(o.dim ?? 0.62), R = (n) => hash(seed * 13.7 + n);
  const cells = []; for (let j = 0; j < rows; j++) for (let i = 0; i < cols; i++) { const r = R(i * 3.3 + j * 7.7), shop = o.shop && j === rows - 1; cells.push({ i, j, has: r < 0.9, on: R(i * 5.1 + j * 2.9 + 1) < lit, kind: R(i * 1.7 + j * 9.1) , shop }); }
  const balcony = Array.from({ length: cols }, (_, i) => R(i * 17.1 + 5) < 0.4), sign = SIGNS[Math.floor(R(99) * SIGNS.length)];
  const paint = (emis) => (g) => { g.fillStyle = emis ? '#000' : '#' + wall.getHexString(); g.fillRect(0, 0, W, H);
    if (!emis) { for (let k = 0; k < 14; k++) { const x = R(k * 4.4) * W, gr = g.createLinearGradient(0, 0, 0, H); gr.addColorStop(R(k) * 0.4, 'rgba(0,0,0,0)'); gr.addColorStop(1, `rgba(0,0,0,${0.12 + 0.18 * R(k * 2)})`); g.fillStyle = gr; g.fillRect(x, 0, 3 + R(k * 6) * 10, H); }
      g.fillStyle = 'rgba(0,0,0,0.28)'; for (let j = 1; j <= rows; j++) g.fillRect(0, j * CH - 2, W, 2); g.fillStyle = 'rgba(0,0,0,0.5)'; for (const i of [1, cols - 1]) if (R(i * 31) < 0.6) g.fillRect(i * CW - 1.5, 0, 2, H); }
    for (const c of cells) { const x = c.i * CW, y = c.j * CH;
      if (c.shop) { // the ground floor: a shutter, or a lit shop under its board
        const open = R(c.i * 8.8 + 3) < 0.55; if (!emis) { g.fillStyle = open ? '#2a2018' : '#3b4048'; g.fillRect(x + 2, y + 9, CW - 4, CH - 9); if (!open) { g.fillStyle = 'rgba(0,0,0,0.35)'; for (let k = 0; k < 6; k++) g.fillRect(x + 2, y + 11 + k * 4, CW - 4, 1); } }
        if (open) { const gr = g.createLinearGradient(0, y + 9, 0, y + CH); gr.addColorStop(0, emis ? '#ffd9a0' : '#ffe2b0'); gr.addColorStop(1, emis ? '#7a4a1c' : '#c98a4a'); g.fillStyle = gr; g.fillRect(x + 4, y + 11, CW - 8, CH - 13); g.fillStyle = emis ? '#000' : '#2a1a10'; g.fillRect(x + 9 + R(c.i) * 6, y + 18, 5, 14); }
        continue; }
      if (!c.has) continue; const wx = x + 6, wy = y + 7, ww = CW - 12, wh = 18;
      if (!emis) { g.fillStyle = 'rgba(255,255,255,0.16)'; g.fillRect(wx - 1.5, wy - 1.5, ww + 3, wh + 3); g.fillStyle = '#080c16'; g.fillRect(wx, wy, ww, wh); }
      if (c.on) { const k = c.kind, col = k < 0.14 ? ['#cfe6ff', '#9cc4ee'] : k < 0.24 ? ['#a8d0ff', '#6f9fe0'] : k < 0.62 ? ['#ffe2ac', '#ffb964'] : ['#ffd08a', '#f09a48']; const gr = g.createLinearGradient(0, wy, 0, wy + wh); gr.addColorStop(0, col[0]); gr.addColorStop(1, col[1]); g.fillStyle = gr; g.globalAlpha = 0.75 + 0.25 * R(c.i + c.j * 3.1); g.fillRect(wx, wy, ww, wh); g.globalAlpha = 1;
        if (R(c.i * 2.2 + c.j * 4.4) < 0.45) { g.fillStyle = emis ? 'rgba(0,0,0,0.55)' : ['#8a3a3a', '#3a6a5a', '#7a6a3a', '#4a4a7a'][Math.floor(R(c.i + c.j) * 4)]; g.fillRect(wx, wy, ww * (0.35 + 0.3 * R(c.j)), wh); }      // a curtain
        if (R(c.i * 6.1 + c.j * 1.3) < 0.12) { g.fillStyle = emis ? '#000' : '#1a1410'; g.beginPath(); g.arc(wx + ww * 0.6, wy + wh * 0.62, 3, 0, 7); g.fill(); g.fillRect(wx + ww * 0.6 - 3.5, wy + wh * 0.62 + 2, 7, 8); } }                      // somebody at the window
      g.fillStyle = emis ? 'rgba(0,0,0,0.6)' : 'rgba(10,12,18,0.85)'; g.fillRect(wx + ww / 2 - 0.5, wy, 1, wh); if (R(c.i + c.j * 5.5) < 0.5) g.fillRect(wx, wy + wh / 2, ww, 1);                                                                 // the bars
      if (balcony[c.i]) { if (!emis) { g.fillStyle = 'rgba(0,0,0,0.55)'; g.fillRect(x + 2, wy + wh + 2, CW - 4, 3); g.fillStyle = 'rgba(190,190,200,0.35)'; for (let k = 0; k < 5; k++) g.fillRect(x + 3 + k * 5, wy + wh - 6, 1, 8); g.fillRect(x + 2, wy + wh - 7, CW - 4, 1);
          if (R(c.i * 3 + c.j * 11) < 0.35) for (let k = 0; k < 3; k++) { g.fillStyle = ['#b0443a', '#3a6fb0', '#d9c06a', '#e6e2d6'][Math.floor(R(k + c.j * 2 + c.i) * 4)]; g.fillRect(x + 4 + k * 6, wy + wh - 5, 4, 6); } } }                         // washing on the rail
      else if (!emis && R(c.i * 9 + c.j * 6) < 0.16) { g.fillStyle = '#8f949c'; g.fillRect(wx + 1, wy + wh + 2, 9, 5); g.fillStyle = '#3a3d44'; g.fillRect(wx + 2, wy + wh + 3, 7, 3); } }                                                         // an air conditioner
    if (o.shop) { const y = (rows - 1) * CH; g.fillStyle = emis ? sign[1] : sign[1]; g.globalAlpha = emis ? 0.95 : 1; g.fillRect(8, y - 3, W - 16, 11); g.globalAlpha = 1; g.fillStyle = sign[2]; g.font = '700 10px Poppins, Baloo2'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(sign[0], W / 2, y + 3); } };
  const map = paintTex(W, H, paint(false)), emis = paintTex(W, H, paint(true)); for (const t of [map, emis]) { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.magFilter = THREE.LinearFilter; }
  return { map, emis, cols, rows };
}
let GP = null;
/** three painted faces of glass: a grid of panes, whole floors of offices still lit */
function glassFacades() { if (!GP) { GP = []; for (let s = 0; s < 3; s++) { const cols = 6, rows = 12, W = cols * CW, H = rows * CH, R = (n) => hash(s * 17.3 + n + 0.5);
    const paint = (emis) => (g) => { g.fillStyle = emis ? '#000' : ['#16283c', '#1c2a34', '#22263a'][s]; g.fillRect(0, 0, W, H);
      for (let j = 0; j < rows; j++) { const fl = R(j * 3.1) < 0.36, y = j * CH; for (let i = 0; i < cols; i++) { const x = i * CW, on = fl ? R(i * 5 + j * 7) < 0.82 : R(i * 5 + j * 7) < 0.08;
          if (on) { g.fillStyle = R(i + j * 2) < 0.78 ? (emis ? '#b4d0ff' : '#cfe2ff') : (emis ? '#ffd9a0' : '#ffe6c0'); g.globalAlpha = 0.5 + 0.5 * R(i * 2 + j); g.fillRect(x + 1.5, y + 3, CW - 3, CH - 9); g.globalAlpha = 1; }
          else if (!emis) { const gr = g.createLinearGradient(0, y, 0, y + CH); gr.addColorStop(0, 'rgba(120,160,220,0.24)'); gr.addColorStop(1, 'rgba(20,30,60,0.08)'); g.fillStyle = gr; g.fillRect(x + 1.5, y + 3, CW - 3, CH - 9); }
          g.fillStyle = emis ? '#000' : 'rgba(6,8,14,0.95)'; g.fillRect(x - 0.75, y, 1.5, CH); }
        g.fillStyle = emis ? '#000' : 'rgba(6,8,14,0.95)'; g.fillRect(0, y + CH - 6, W, 6); } };
    const map = paintTex(W, H, paint(false)), emis = paintTex(W, H, paint(true)); for (const t of [map, emis]) { t.wrapS = t.wrapT = THREE.RepeatWrapping; } GP.push({ map, emis, cols, rows }); } } return GP; }
let POOL = null;
export function facades() { if (!POOL) { POOL = []; for (let i = 0; i < 12; i++) POOL.push(facade(i + 1, 5 + (i % 3), 8 + (i % 4) * 2, { lit: 0.28 + 0.12 * hash(i), shop: i % 3 === 0 })); } return POOL; }

/**
 * A building: w (along z), d (along x), h; its faces painted; a parapet, a tank, a stair head, an aerial, sometimes a hoarding.
 * o: { seed, glow (how strongly its windows shine), roof: false to leave the top bare (far ones), face: +1 | -1 (which x side looks at the camera) }
 */
export function building(w, d, h, o = {}) {
  const seed = o.seed ?? 1, R = (n) => hash(seed * 7.7 + n), F = o.glass ? glassFacades()[Math.floor(R(1) * 3)] : facades()[Math.floor(R(1) * 12)], g = new THREE.Group(), glow = o.glow ?? 2.2;
  const side = (len) => { const m = F.map.clone(), e = F.emis.clone(); const rx = Math.max(1, Math.round(len / BAYW)) / F.cols, ry = Math.max(1, Math.round(h / FLOORH)) / F.rows; for (const t of [m, e]) { t.repeat.set(rx, ry); t.offset.set(Math.floor(R(len) * F.cols) / F.cols, 1 - ry); t.needsUpdate = true; }
    return new THREE.MeshStandardMaterial({ map: m, emissiveMap: e, emissive: 0xffffff, emissiveIntensity: glow, roughness: o.glass ? 0.35 : 0.92, metalness: o.glass ? 0.4 : 0 }); };
  const roofM = new THREE.MeshStandardMaterial({ color: '#1c1e28', roughness: 1 }), mx = side(w), mz = side(d);
  g.add(mesh(new THREE.BoxGeometry(d, h, w), [mx, mx, roofM, roofM, mz, mz], { p: [0, h / 2, 0] }));
  if (o.roof !== false) { const dk = new THREE.MeshStandardMaterial({ color: new THREE.Color(WALLS[seed % WALLS.length]).multiplyScalar(0.34), roughness: 1 });
    for (const [px, pz, sx, sz] of [[d / 2, 0, 0.03, w], [-d / 2, 0, 0.03, w], [0, w / 2, d, 0.03], [0, -w / 2, d, 0.03]]) g.add(mesh(new THREE.BoxGeometry(sx, 0.07, sz), dk, { p: [px, h + 0.035, pz] }));
    if (R(2) < 0.75) { const tx = (R(3) - 0.5) * d * 0.5, tz = (R(4) - 0.5) * w * 0.5; g.add(mesh(new THREE.CylinderGeometry(0.1, 0.11, 0.17, 14), new THREE.MeshStandardMaterial({ color: '#0d0e12', roughness: 0.6 }), { p: [tx, h + 0.2, tz] }), mesh(new THREE.BoxGeometry(0.16, 0.11, 0.16), dk, { p: [tx, h + 0.055, tz] })); }
    if (R(5) < 0.6) g.add(mesh(new THREE.BoxGeometry(Math.min(0.3, d * 0.5), 0.2, 0.26), dk, { p: [(R(6) - 0.5) * d * 0.4, h + 0.1, (R(7) - 0.5) * w * 0.4] }));
    if (R(8) < 0.5) g.add(mesh(new THREE.BoxGeometry(0.012, 0.42, 0.012), dk, { p: [(R(9) - 0.5) * d * 0.6, h + 0.21, (R(10) - 0.5) * w * 0.6], cast: false }));
    if (o.hoarding ?? (R(11) < 0.16 && h > 1.6)) { const s = SIGNS[Math.floor(R(12) * SIGNS.length)], f = o.face ?? 1, tx = paintTex(256, 96, (c, W, H) => { c.fillStyle = s[1]; c.fillRect(0, 0, W, H); c.strokeStyle = s[2]; c.lineWidth = 6; c.strokeRect(6, 6, W - 12, H - 12); c.fillStyle = s[2]; c.font = '800 52px Baloo2, Poppins'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(s[0], W / 2, H / 2 + 4); });
      g.add(mesh(new THREE.PlaneGeometry(Math.min(w * 0.8, 0.9), 0.32), new THREE.MeshBasicMaterial({ map: tx, color: new THREE.Color(1.7, 1.7, 1.7) }), { p: [f * d * 0.42, h + 0.36, 0], r: [0, f * Math.PI / 2, 0], cast: false }), mesh(new THREE.BoxGeometry(0.02, 0.24, 0.02), dk, { p: [f * d * 0.4, h + 0.12, w * 0.25], cast: false }), mesh(new THREE.BoxGeometry(0.02, 0.24, 0.02), dk, { p: [f * d * 0.4, h + 0.12, -w * 0.25], cast: false })); }
    // balconies that really stand out from the face that looks at the camera
    if (o.near) { const f = o.face ?? 1, floors = Math.round(h / FLOORH), col = Math.floor(R(13) * Math.max(1, Math.round(w / BAYW) - 1)), z = -w / 2 + (col + 0.5) * BAYW; for (let j = 1; j < floors; j++) if (R(j * 3.1) < 0.7) g.add(mesh(new THREE.BoxGeometry(0.1, 0.018, BAYW * 0.92), dk, { p: [f * (d / 2 + 0.05), j * FLOORH + 0.02, z] }), mesh(new THREE.BoxGeometry(0.008, 0.1, BAYW * 0.92), dk, { p: [f * (d / 2 + 0.097), j * FLOORH + 0.075, z], cast: false })); } }
  return g;
}

/** a toy car: 'taxi' (black, yellow top), 'auto' (a three-wheeler), 'bus' (red), 'car' */
export function vehicle(kind = 'taxi', seed = 1) {
  const g = new THREE.Group(), card = (c, r = 0.5) => mat(c, 'paint', { rough: r, ridge: 0.3 }), L = kind === 'bus' ? 0.62 : kind === 'auto' ? 0.2 : 0.3, Wd = kind === 'bus' ? 0.16 : 0.13, Hh = kind === 'bus' ? 0.2 : kind === 'auto' ? 0.13 : 0.085;
  const body = kind === 'taxi' ? '#15161a' : kind === 'auto' ? '#1c1d20' : kind === 'bus' ? '#b3261e' : ['#7d2a2a', '#2a4f7d', '#c9c4b6', '#3a5a4a'][seed % 4];
  g.add(mesh(rbox(Wd, Hh, L, 0.02), card(body, 0.35), { p: [0, 0.035 + Hh / 2, 0] }));
  if (kind === 'taxi' || kind === 'car') g.add(mesh(rbox(Wd * 0.86, 0.055, L * 0.5, 0.02), card(kind === 'taxi' ? '#e2b51e' : body, 0.35), { p: [0, 0.035 + Hh + 0.03, -0.01] }));
  if (kind === 'auto') g.add(mesh(rbox(Wd * 0.9, 0.05, L * 0.8, 0.02), card('#d9b81e'), { p: [0, 0.035 + Hh + 0.03, 0] }));
  if (kind === 'bus') { const wt = paintTex(128, 16, (c, w, h) => { c.fillStyle = '#1a1c22'; c.fillRect(0, 0, w, h); for (let i = 0; i < 7; i++) { c.fillStyle = '#ffe6b8'; c.fillRect(4 + i * 18, 3, 13, 10); } }); for (const s of [1, -1]) g.add(mesh(new THREE.PlaneGeometry(L * 0.9, 0.06), new THREE.MeshBasicMaterial({ map: wt, color: new THREE.Color(1.8, 1.7, 1.4) }), { p: [s * (Wd / 2 + 0.022), 0.035 + Hh * 0.7, 0], r: [0, s * Math.PI / 2, 0], cast: false })); }
  for (const s of [1, -1]) { g.add(mesh(ball(0.012), mat('#fff3cf', 'glow', { power: 7 }), { p: [s * Wd * 0.3, 0.07, L / 2 + 0.022], cast: false }), mesh(ball(0.009), mat('#ff3a24', 'glow', { power: 4 }), { p: [s * Wd * 0.3, 0.075, -L / 2 - 0.022], cast: false }));
    for (const z of [L * 0.3, -L * 0.3]) g.add(mesh(new THREE.CylinderGeometry(0.028, 0.028, 0.02, 10), card('#0a0a0c'), { p: [s * (Wd / 2 + 0.012), 0.028, z], r: [0, 0, Math.PI / 2], cast: false })); }
  return g;
}
/** a tree of dyed sponge on a wire trunk */
export function tree(seed = 1, h = 0.5) { const g = new THREE.Group(), leaf = mat(['#16302a', '#1c3a30', '#122a2c'][seed % 3], 'felt', { fuzz: 0.5, ns: 90, bump: 1 }); g.add(mesh(limb(0.018, h * 0.6, 0.012, 8), mat('#2a2018', 'clay'), { p: [0, h * 0.6, 0] })); for (let i = 0; i < 5; i++) g.add(mesh(ball(h * (0.2 + 0.1 * hash(seed + i)), h * 0.18, h * (0.2 + 0.1 * hash(seed * 2 + i)), 10), leaf, { p: [(hash(seed * 3 + i) - 0.5) * h * 0.4, h * (0.62 + 0.3 * hash(seed * 5 + i)), (hash(seed * 7 + i) - 0.5) * h * 0.4] })); return g; }

/**
 * THE SKY, painted on a great curved card that stands behind everything: deep blue going to a dusty glow over the city,
 * pin stars, a paper moon with a halo, and cotton-wool clouds on wires that drift. Looks towards +x (the camera side) when face = 1.
 * → { group, tick(t) }
 */
export function sky(o = {}) {
  const x = o.x ?? -16, f = o.face ?? 1, W = o.w ?? 90, H = o.h ?? 30, y0 = o.y0 ?? -3, g = new THREE.Group();
  const tx = paintTex(512, 1024, (c, w, h) => { const gr = c.createLinearGradient(0, 0, 0, h); gr.addColorStop(0, '#02030b'); gr.addColorStop(0.35, '#060b24'); gr.addColorStop(0.62, '#0d1740'); gr.addColorStop(0.8, '#232a62'); gr.addColorStop(0.9, '#4a3a6e'); gr.addColorStop(0.96, '#7a4a62'); gr.addColorStop(1, '#a8644e'); c.fillStyle = gr; c.fillRect(0, 0, w, h);
    for (let i = 0; i < 5; i++) { const cx = hash(i * 3.3) * w, gy = h * (0.86 + 0.08 * hash(i)), rg = c.createRadialGradient(cx, gy, 4, cx, gy, 90 + 80 * hash(i * 2)); rg.addColorStop(0, 'rgba(255,170,110,0.22)'); rg.addColorStop(1, 'rgba(255,170,110,0)'); c.fillStyle = rg; c.fillRect(0, 0, w, h); } });
  tx.wrapS = THREE.RepeatWrapping; tx.repeat.set(3, 1);
  g.add(mesh(new THREE.PlaneGeometry(W, H), new THREE.MeshBasicMaterial({ map: tx, fog: false }), { p: [x, y0 + H / 2, 0], r: [0, f * Math.PI / 2, 0], cast: false, recv: false }));
  // stars: two sizes, the big ones few
  const st = [], sz = []; for (let i = 0; i < 520; i++) { const yy = y0 + H * (0.34 + 0.64 * hash(i * 1.3) ** 0.8); st.push(x + f * 0.05, yy, (hash(i * 2.9) - 0.5) * W * 0.96); }
  const sg = new THREE.BufferGeometry(); sg.setAttribute('position', new THREE.Float32BufferAttribute(st, 3)); const stars = new THREE.Points(sg, new THREE.PointsMaterial({ color: new THREE.Color(2.2, 2.3, 2.8), size: (o.star ?? 2.2), sizeAttenuation: false, fog: false })); g.add(stars);
  const st2 = []; for (let i = 0; i < 46; i++) st2.push(x + f * 0.06, y0 + H * (0.4 + 0.58 * hash(i * 7.1)), (hash(i * 4.3) - 0.5) * W * 0.9); const sg2 = new THREE.BufferGeometry(); sg2.setAttribute('position', new THREE.Float32BufferAttribute(st2, 3)); const big = new THREE.Points(sg2, new THREE.PointsMaterial({ color: new THREE.Color(3.4, 3.4, 3.9), size: (o.star ?? 2.2) * 1.9, sizeAttenuation: false, fog: false })); g.add(big);
  // the moon: a painted disc, a soft halo round it
  const mz = o.moonZ ?? 6, my = o.moonY ?? y0 + H * 0.62, mr = o.moonR ?? 1.5;
  const mt = paintTex(256, 256, (c, w, h) => { const gr = c.createRadialGradient(108, 100, 10, 128, 128, 128); gr.addColorStop(0, '#fffaf0'); gr.addColorStop(1, '#eadcae'); c.fillStyle = gr; c.fillRect(0, 0, w, h); c.fillStyle = 'rgba(176,160,120,0.42)'; for (const [a, b, r] of [[88, 92, 24], [152, 138, 32], [172, 78, 13], [108, 176, 18], [70, 150, 10], [140, 60, 9]]) { c.beginPath(); c.arc(a, b, r, 0, 7); c.fill(); } });
  const ht = paintTex(256, 256, (c, w, h) => { const gr = c.createRadialGradient(128, 128, 30, 128, 128, 128); gr.addColorStop(0, 'rgba(255,244,214,0.55)'); gr.addColorStop(0.35, 'rgba(210,214,255,0.16)'); gr.addColorStop(1, 'rgba(160,170,255,0)'); c.fillStyle = gr; c.fillRect(0, 0, w, h); });
  g.add(mesh(new THREE.PlaneGeometry(mr * 7, mr * 7), new THREE.MeshBasicMaterial({ map: ht, transparent: true, depthWrite: false, fog: false, blending: THREE.AdditiveBlending }), { p: [x + f * 0.1, my, mz], r: [0, f * Math.PI / 2, 0], cast: false, recv: false }));
  g.add(mesh(new THREE.CircleGeometry(mr, 56), new THREE.MeshBasicMaterial({ map: mt, color: new THREE.Color(2.3, 2.2, 1.9), fog: false }), { p: [x + f * 0.14, my, mz], r: [0, f * Math.PI / 2, 0], cast: false, recv: false }));
  // clouds: tufts of cotton, lit from the moon's side, in three depths
  const ct = (seed) => paintTex(512, 192, (c, w, h) => { for (let i = 0; i < 46; i++) { const cx = w * (0.1 + 0.8 * hash(seed + i * 1.7)), cy = h * (0.42 + 0.3 * hash(seed * 2 + i * 3.1)) - 18 * Math.sin(cx / w * Math.PI), r = 26 + 46 * hash(seed * 3 + i), gr = c.createRadialGradient(cx - r * 0.2, cy - r * 0.3, 2, cx, cy, r); gr.addColorStop(0, 'rgba(196,204,236,0.34)'); gr.addColorStop(0.5, 'rgba(96,108,160,0.2)'); gr.addColorStop(1, 'rgba(40,50,100,0)'); c.fillStyle = gr; c.beginPath(); c.arc(cx, cy, r, 0, 7); c.fill(); } });
  const clouds = []; for (let i = 0; i < (o.clouds ?? 9); i++) { const cw = 9 + 9 * hash(i * 2.3), m = mesh(new THREE.PlaneGeometry(cw, cw * 0.36), new THREE.MeshBasicMaterial({ map: ct(i * 11 + 3), transparent: true, depthWrite: false, fog: false, opacity: 0.75 + 0.25 * hash(i) }), { p: [x + f * (0.3 + i * 0.12), y0 + H * (0.36 + 0.4 * hash(i * 5.7)), 0], r: [0, f * Math.PI / 2, 0], cast: false, recv: false }); m.userData = { z0: (hash(i * 9.1) - 0.5) * W * 0.8, v: 0.05 + 0.1 * hash(i * 3.7) }; m.renderOrder = 2; clouds.push(m); g.add(m); }
  const tick = (t) => { for (const m of clouds) { const span = W * 0.9; m.position.z = ((m.userData.z0 + t * m.userData.v + span / 2) % span) - span / 2; } stars.material.opacity = 1; big.material.color.setRGB(3.4, 3.4, 3.9).multiplyScalar(0.8 + 0.2 * Math.sin(t * 2.3)); };
  tick(0); return { group: g, tick };
}

// ---------------------------------------------------------------------------------------------------------------
// THE WIDE WORLD (second model): a sky all the way round, water that mirrors the city, palms, boats, tall towers.

/**
 * A sky dome painted in one piece: deep night going down to the city's dusty glow, thousands of pin stars, a moon with
 * its halo, banks of cloud lit silver from the moon's side and warm from the city below. u = 0.5 is the −x side
 * (behind the city), 0.75 is +z, 0.25 is −z. o: { r, moonU, moonE (degrees up), moonR (degrees), glowU, cards }
 * → { group, tick(t), moonDir }
 */
export function skyDome(o = {}) {
  const R = o.r ?? 52, e0 = -8, e1 = 64, g = new THREE.Group(), TW = 4096, TH = 1024, mu = o.moonU ?? 0.7, me = o.moonE ?? 21, mr = o.moonR ?? 2.6, gu = o.glowU ?? 0.5;
  const tx = paintTex(TW, TH, (c, w, h) => { const Y = (e) => h * (1 - (e - e0) / (e1 - e0)), X = (u) => u * w, pd = h / (e1 - e0);
    const gr = c.createLinearGradient(0, 0, 0, h); for (const [k, col] of [[0, '#02030c'], [0.34, '#050a22'], [0.56, '#0b1640'], [0.72, '#1c2a66'], [0.81, '#3b3a7c'], [0.865, '#6a4878'], [0.9, '#a05a62'], [0.93, '#c9784e'], [1, '#3a2630']]) gr.addColorStop(k, col); c.fillStyle = gr; c.fillRect(0, 0, w, h);
    // the city's glow, strongest behind the city
    for (let i = 0; i < 26; i++) { const u = gu + (hash(i * 3.3) - 0.5) * (i < 16 ? 0.34 : 1), cx = X((u + 1) % 1), cy = Y(-1 + 5 * hash(i)), r = (160 + 260 * hash(i * 2)), rg = c.createRadialGradient(cx, cy, 6, cx, cy, r); rg.addColorStop(0, `rgba(255,${150 + 50 * hash(i * 7)},${90 + 60 * hash(i * 5)},${i < 16 ? 0.2 : 0.09})`); rg.addColorStop(1, 'rgba(255,150,100,0)'); c.fillStyle = rg; c.save(); c.translate(cx, cy); c.scale(1.8, 0.62); c.translate(-cx, -cy); c.beginPath(); c.arc(cx, cy, r, 0, 7); c.fill(); c.restore(); }
    // stars: many faint, a few strong, a dusting in a slanting band
    for (let i = 0; i < 2600; i++) { const u = hash(i * 1.37), e = 16 + 46 * ((u * 2.2 + 0.2 * hash(i * 9.1)) % 1) + 9 * (hash(i * 5.7) - 0.5); c.fillStyle = `rgba(214,226,255,${0.05 + 0.1 * hash(i * 4.3)})`; c.beginPath(); c.arc(X(u), Y(e), 0.9, 0, 7); c.fill(); }
    // the moon and its halo
    const mx = X(mu), my = Y(me), rr = mr * pd; for (const [k, a, col] of [[9, 0.16, '190,205,255'], [4.2, 0.22, '225,230,255'], [2.1, 0.3, '255,246,224']]) { const rg = c.createRadialGradient(mx, my, rr * 0.8, mx, my, rr * k); rg.addColorStop(0, `rgba(${col},${a})`); rg.addColorStop(1, `rgba(${col},0)`); c.fillStyle = rg; c.beginPath(); c.arc(mx, my, rr * k, 0, 7); c.fill(); }
    // clouds: banks of puffs. Each bank: a dark body, a silver edge towards the moon, a warm belly towards the city
    const bank = (u, e, len, th, n, seed, k) => { for (const pass of [0, 1, 2]) for (let i = 0; i < n; i++) { const f = hash(seed + i * 1.71), cx = X(u) + (f - 0.5) * len * pd * 1.6, hump = Math.sin(f * Math.PI) ** 0.7, cy = Y(e) - hump * th * pd * (0.2 + 0.8 * hash(seed * 2 + i * 3.3)) + (1 - hump) * th * pd * 0.3, r = pd * th * (0.35 + 0.6 * hash(seed * 3 + i * 2.2)) * (0.5 + 0.5 * hump);
        const dm = Math.hypot((cx - mx) / w * 360, (cy - my) / pd), near = clamp(1 - dm / 60), dx = clamp((mx - cx) / (rr * 30), -1, 1) * r * 0.35;
        let rg; if (pass === 0) { rg = c.createRadialGradient(cx, cy + r * 0.2, r * 0.1, cx, cy + r * 0.2, r * 1.15); rg.addColorStop(0, `rgba(22,28,62,${0.5 * k})`); rg.addColorStop(0.6, `rgba(26,32,70,${0.3 * k})`); rg.addColorStop(1, 'rgba(26,32,70,0)'); }
        else if (pass === 1) { rg = c.createRadialGradient(cx + dx, cy - r * 0.34, r * 0.05, cx + dx, cy - r * 0.3, r * 0.8); const a = (0.1 + 0.42 * near ** 1.5) * k; rg.addColorStop(0, `rgba(214,224,255,${a})`); rg.addColorStop(0.5, `rgba(150,168,226,${a * 0.5})`); rg.addColorStop(1, 'rgba(150,168,226,0)'); }
        else { const lowK = clamp(1 - (e - 4) / 26); rg = c.createRadialGradient(cx, cy + r * 0.6, r * 0.05, cx, cy + r * 0.55, r * 0.8); rg.addColorStop(0, `rgba(226,140,104,${0.2 * lowK * k})`); rg.addColorStop(1, 'rgba(226,140,104,0)'); }
        c.fillStyle = rg; c.beginPath(); c.arc(cx, cy, r * 1.2, 0, 7); c.fill(); } };
    for (let i = 0; i < 30; i++) { const low = i < 16; bank(hash(i * 7.31 + 2), low ? 5 + 11 * hash(i * 3.1) : 18 + 30 * hash(i * 5.3), (low ? 16 : 11) + 16 * hash(i * 2.7), low ? 3 + 2.6 * hash(i) : 4 + 4 * hash(i * 9), low ? 46 : 38, i * 37 + 5, low ? 1 : 0.8); }
    bank(mu - 0.022, me - 3.4, 13, 3.2, 44, 991, 1); bank(mu + 0.03, me + 4.5, 9, 2.6, 30, 1207, 0.8);
    // thin high streaks
    c.globalAlpha = 0.07; c.fillStyle = '#b8c6ff'; for (let i = 0; i < 26; i++) { c.save(); c.translate(X(hash(i * 3.9)), Y(26 + 30 * hash(i * 8.3))); c.rotate(-0.12 + 0.1 * hash(i)); c.scale(1, 0.05); c.beginPath(); c.arc(0, 0, 240 + 300 * hash(i * 2), 0, 7); c.fill(); c.restore(); } c.globalAlpha = 1; });
  tx.wrapS = THREE.RepeatWrapping; tx.anisotropy = 8;
  const th0 = (90 - e1) * Math.PI / 180, th1 = (90 - e0) * Math.PI / 180, dome = new THREE.Mesh(new THREE.SphereGeometry(R, 96, 28, 0, Math.PI * 2, th0, th1 - th0), new THREE.MeshBasicMaterial({ map: tx, side: THREE.BackSide, fog: false, depthWrite: false, color: new THREE.Color(o.gain ?? 1.25, o.gain ?? 1.25, (o.gain ?? 1.25) * 1.06) }));
  dome.rotation.y = Math.PI; dome.renderOrder = -5; dome.frustumCulled = false; g.add(dome);
  const a = (mu - 0.5) * Math.PI * 2, el = me * Math.PI / 180, moonDir = new THREE.Vector3(-Math.cos(a) * Math.cos(el), Math.sin(el), Math.sin(a) * Math.cos(el));
  const dirOf = (u, e) => { const aa = (u - 0.5) * Math.PI * 2, ee = e * Math.PI / 180; return [-Math.cos(aa) * Math.cos(ee), Math.sin(ee), Math.sin(aa) * Math.cos(ee)]; };
  for (const [n, size, seed, lo] of [[1500, o.star ?? 1.6, 1, 0.3], [40, (o.star ?? 1.6) * 1.8, 77, 0.8]]) { const ps = [], cs = []; for (let i = 0; i < n; i++) { const e = 6 + 58 * hash(i * 2.91 + seed) ** 0.8, d = dirOf(hash(i * 1.37 + seed * 3), e), k = (lo + (1 - lo) * hash(i * 4.3 + seed) ** 2) * clamp((e - 4) / 14) * 1.9, tint = [[1, 0.95, 0.86], [0.84, 0.9, 1], [1, 1, 1], [1, 0.88, 0.76]][i % 4]; ps.push(d[0] * R * 0.985, d[1] * R * 0.985, d[2] * R * 0.985); cs.push(tint[0] * k, tint[1] * k, tint[2] * k); }
    const sg = new THREE.BufferGeometry(); sg.setAttribute('position', new THREE.Float32BufferAttribute(ps, 3)); sg.setAttribute('color', new THREE.Float32BufferAttribute(cs, 3)); const pts = new THREE.Points(sg, new THREE.PointsMaterial({ size, sizeAttenuation: false, vertexColors: true, fog: false, depthWrite: false })); pts.renderOrder = -4.8; pts.frustumCulled = false; g.add(pts); }
  const mt = paintTex(256, 256, (c) => { const mg = c.createRadialGradient(100, 92, 10, 128, 128, 126); mg.addColorStop(0, '#fffdf4'); mg.addColorStop(0.8, '#f3e7c2'); mg.addColorStop(1, '#d9c99c'); c.fillStyle = mg; c.beginPath(); c.arc(128, 128, 126, 0, 7); c.fill(); c.fillStyle = 'rgba(150,138,104,0.36)'; for (const [x, y, r] of [[90, 96, 36], [156, 142, 44], [178, 76, 18], [110, 180, 24], [66, 152, 14], [140, 58, 12], [200, 130, 10]]) { c.beginPath(); c.arc(x, y, r, 0, 7); c.fill(); } });
  const moon = new THREE.Mesh(new THREE.CircleGeometry(R * 0.97 * Math.tan(mr * Math.PI / 180), 64), new THREE.MeshBasicMaterial({ map: mt, color: new THREE.Color(2.4, 2.3, 2.0), fog: false, depthWrite: false, transparent: true })); moon.position.copy(moonDir).multiplyScalar(R * 0.97); moon.lookAt(0, 0, 0); moon.renderOrder = -4.5; moon.frustumCulled = false; g.add(moon);
  // a few clouds on their own cards in front of the dome: they drift, and slide against it when the camera moves
  const ct = (seed) => paintTex(512, 192, (c, w, h) => { for (let i = 0; i < 50; i++) { const cx = w * (0.12 + 0.76 * hash(seed + i * 1.7)), cy = h * (0.46 + 0.26 * hash(seed * 2 + i * 3.1)) - 22 * Math.sin(cx / w * Math.PI), r = 22 + 44 * hash(seed * 3 + i), gr = c.createRadialGradient(cx - r * 0.2, cy - r * 0.34, 2, cx, cy, r); gr.addColorStop(0, 'rgba(206,214,246,0.3)'); gr.addColorStop(0.45, 'rgba(86,98,156,0.2)'); gr.addColorStop(1, 'rgba(30,38,86,0)'); c.fillStyle = gr; c.beginPath(); c.arc(cx, cy, r, 0, 7); c.fill(); } });
  const cards = []; for (let i = 0; i < (o.cards ?? 9); i++) { const cw = 12 + 12 * hash(i * 2.3), au = (o.cardU ?? gu) + (hash(i * 5.9) - 0.5) * 0.55, ang = (au - 0.5) * Math.PI * 2, rad = R * (0.7 + 0.16 * hash(i * 3)), m = new THREE.Mesh(new THREE.PlaneGeometry(cw, cw * 0.36), new THREE.MeshBasicMaterial({ map: ct(i * 11 + 3), transparent: true, depthWrite: false, fog: false, opacity: 0.7 + 0.3 * hash(i) })); m.userData = { ang, rad, y: R * (0.16 + 0.34 * hash(i * 5.7)), v: (0.0012 + 0.002 * hash(i * 3.7)) * (i % 2 ? 1 : -0.6) }; m.renderOrder = -4; m.frustumCulled = false; cards.push(m); g.add(m); }
  const tick = (t) => { for (const m of cards) { const u = m.userData, an = u.ang + t * u.v; m.position.set(-Math.cos(an) * u.rad, u.y, Math.sin(an) * u.rad); m.lookAt(0, u.y * 0.6, 0); } };
  tick(0); return { group: g, tick, moonDir };
}

/**
 * Water that mirrors the world: a plane at height y; before each picture the scene is drawn once more from under the
 * plane (E.pre) and the water shows that picture through its ripples, drawn out long the way lights lie on a creek at night.
 * o: { y, x: [x0, x1], z: [z0, z1], deep (colour), rip (how rough), moonDir, moonCol } → the mesh (userData.u = the shader's numbers)
 */
export function water(E, scene, o = {}) {
  const Y = o.y ?? 0, [x0, x1] = o.x ?? [-40, 40], [z0, z1] = o.z ?? [-45, 45], rw = Math.round(Math.min(E.W, 2160) * 0.75), rh = Math.round(rw * E.H / E.W);
  const rt = new THREE.WebGLRenderTarget(rw, rh, { type: THREE.HalfFloatType, minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter, depthBuffer: true });
  const fog = scene.fog, u = { tM: { value: rt.texture }, uTM: { value: new THREE.Matrix4() }, uT: { value: 0 }, uDeep: { value: new THREE.Color(o.deep ?? '#030816') }, uFogC: { value: fog ? fog.color : new THREE.Color(0) }, uFogD: { value: fog ? fog.density : 0 }, uRip: { value: o.rip ?? 1 }, uMoonD: { value: (o.moonDir ?? new THREE.Vector3(-0.5, 0.4, 0.7)).clone().normalize() }, uMoonC: { value: new THREE.Color(o.moonCol ?? '#dfe8ff').multiplyScalar(o.glitter ?? 5) } };
  const m = new THREE.Mesh(new THREE.PlaneGeometry(x1 - x0, z1 - z0), new THREE.ShaderMaterial({ uniforms: u, fog: false,
    vertexShader: `uniform mat4 uTM; varying vec4 vP; varying vec3 vW; void main(){ vec4 w = modelMatrix * vec4(position, 1.); vW = w.xyz; vP = uTM * w; gl_Position = projectionMatrix * viewMatrix * w; }`,
    fragmentShader: `uniform sampler2D tM; uniform float uT, uFogD, uRip; uniform vec3 uDeep, uFogC, uMoonD, uMoonC; varying vec4 vP; varying vec3 vW;
      float hh(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
      float ns(vec2 p){ vec2 i = floor(p), f = fract(p); f = f * f * (3. - 2. * f); return mix(mix(hh(i), hh(i + vec2(1., 0.)), f.x), mix(hh(i + vec2(0., 1.)), hh(i + vec2(1., 1.)), f.x), f.y); }
      float ht(vec2 p){ float t = uT; float h = 0.;
        h += 0.30 * sin(dot(p, vec2(0.80, 0.60)) * 7.0 + t * 1.3); h += 0.26 * sin(dot(p, vec2(-0.45, 0.89)) * 11.3 + t * 1.9 + 1.7); h += 0.22 * sin(dot(p, vec2(0.96, -0.28)) * 13.7 - t * 2.4 + 0.6);
        h += 0.16 * sin(dot(p, vec2(0.2, 0.98)) * 21.0 + t * 3.1 + 4.0); h += 0.9 * (ns(p * 6.0 + vec2(t * 0.35, -t * 0.2)) - 0.5); h += 0.45 * (ns(p * 17.0 - vec2(t * 0.6, t * 0.4)) - 0.5); return h; }
      void main(){ vec3 Vv = cameraPosition - vW; float dist = length(Vv); vec3 V = Vv / dist; float e = 0.012;
        float h0 = ht(vW.xz); vec2 n = vec2(ht(vW.xz + vec2(e, 0.)) - h0, ht(vW.xz + vec2(0., e)) - h0) / e * 0.011 * uRip; n /= (1. + dist * 0.05);
        vec3 N = normalize(vec3(-n.x, 1., -n.y)); float fr = 0.05 + 0.95 * pow(1. - max(dot(V, N), 0.), 4.);
        vec2 uv = vP.xy / vP.w; vec3 rf = vec3(0.); float ws = 0.;
        for (int i = 0; i < 9; i++) { float k = (float(i) - 4.) / 4.; vec2 of = vec2(n.x * 0.06, n.y * 0.14 + k * (0.006 + 0.09 * abs(n.y) + 0.04 * abs(n.x))); float w = 1. - 0.6 * abs(k); rf += texture2D(tM, clamp(uv + of, 0.002, 0.998)).rgb * w; ws += w; } rf /= ws;
        vec3 H = normalize(V + uMoonD); float sp = pow(max(dot(N, H), 0.), 420.) + 0.15 * pow(max(dot(N, H), 0.), 60.);
        vec3 col = mix(uDeep, rf, fr) + uMoonC * sp * 0.6; float f = 1. - exp(-pow(dist * uFogD, 2.)); gl_FragColor = vec4(mix(col, uFogC, f), 1.); }` }));
  m.rotation.x = -Math.PI / 2; m.position.set((x0 + x1) / 2, Y, (z0 + z1) / 2); m.frustumCulled = false; m.userData.u = u; scene.add(m);
  const mc = new THREE.PerspectiveCamera(), plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), -Y + 0.004), d = new THREE.Vector3(), up = new THREE.Vector3(), none = [];
  E.pre.push((r, sc, cam, t) => { u.uT.value = t; const p = cam.position; d.set(0, 0, -1).applyQuaternion(cam.quaternion).add(p); up.set(0, 1, 0).applyQuaternion(cam.quaternion);
    mc.position.set(p.x, 2 * Y - p.y, p.z); mc.up.set(up.x, -up.y, up.z); mc.lookAt(d.x, 2 * Y - d.y, d.z); mc.updateMatrixWorld(true); mc.matrixWorldInverse.copy(mc.matrixWorld).invert(); mc.projectionMatrix.copy(cam.projectionMatrix); mc.projectionMatrixInverse.copy(cam.projectionMatrixInverse);
    u.uTM.value.set(0.5, 0, 0, 0.5, 0, 0.5, 0, 0.5, 0, 0, 0.5, 0.5, 0, 0, 0, 1).multiply(mc.projectionMatrix).multiply(mc.matrixWorldInverse);
    m.visible = false; r.clippingPlanes = [plane]; r.setRenderTarget(rt); r.clear(); r.render(sc, mc); r.clippingPlanes = none; m.visible = true; });
  return m;
}

/** a palm: a leaning trunk of ringed clay, fronds of felt that droop */
export function palm(seed = 1, h = 0.9) { const g = new THREE.Group(), R = (n) => hash(seed * 3.7 + n), lx = (R(1) - 0.5) * 0.24 * h, lz = (R(2) - 0.5) * 0.24 * h, leaf = mat(['#1d4034', '#22483a', '#183a34'][seed % 3], 'felt', { fuzz: 0.5, ns: 80 });
  g.add(mesh(tube([[0, 0, 0], [lx * 0.3, h * 0.4, lz * 0.3], [lx * 0.8, h * 0.8, lz * 0.8], [lx, h, lz]], 0.02 * h + 0.008, 10, 7), mat('#4a3a2a', 'clay', { bump: 1.4 })));
  for (let i = 0; i < 9; i++) { const a = i / 9 * Math.PI * 2 + R(i), L = h * (0.34 + 0.12 * R(i * 3)), dr = 0.25 + 0.5 * R(i * 5), f = mesh(tube([[0, 0, 0], [L * 0.45, L * 0.16, 0], [L * 0.8, L * (0.1 - dr * 0.3), 0], [L, -L * dr, 0]], 0.012 * h, 8, 4), leaf, { cast: false }); f.scale.set(1, 1, 5.5); f.rotation.y = a; f.position.set(lx, h, lz); g.add(f); }
  return g; }

/** a fishing boat with a lantern */
export function boat(seed = 1) { const g = new THREE.Group(), wood = mat(['#3a2a22', '#243a4a', '#4a2a26'][seed % 3], 'paint', { ridge: 0.7 }), L = 0.46 + 0.14 * hash(seed);
  const hull = mesh(ball(0.075, 0.07, L / 2, 18), wood, { p: [0, 0.012, 0] }); hull.scale.y = 0.75; g.add(hull, mesh(new THREE.BoxGeometry(0.12, 0.012, L * 0.86), mat('#5a4a3a', 'paint', { ridge: 0.8 }), { p: [0, 0.04, 0], cast: false }), mesh(rbox(0.07, 0.06, 0.1, 0.012), mat('#c9bfa6', 'paint'), { p: [0, 0.075, -L * 0.18] }), mesh(new THREE.BoxGeometry(0.008, 0.26, 0.008), wood, { p: [0, 0.17, 0.02], cast: false }),
    mesh(ball(0.016), mat('#ffc070', 'glow', { power: 8 }), { p: [0, 0.2, 0.05], cast: false }), mesh(new THREE.BoxGeometry(0.004, 0.004, L * 0.7), wood, { p: [0, 0.29, 0], r: [0.12, 0, 0], cast: false }));
  for (let i = 0; i < 5; i++) g.add(mesh(new THREE.PlaneGeometry(0.022, 0.03), new THREE.MeshBasicMaterial({ color: ['#c9443a', '#e6c14a', '#3a7ac9', '#e6e2d6', '#3aa06a'][i], side: THREE.DoubleSide }), { p: [0, 0.272 + 0.006 * i, -L * 0.26 + i * L * 0.13], cast: false, recv: false }));
  return g; }

/** a tall building with a step back, a crown and a mast with a red lamp. o as building() + { glass, sign } */
export function tower(w, d, h, o = {}) { const g = new THREE.Group(), sd = o.seed ?? 1, R = (n) => hash(sd * 5.3 + n), h1 = h * (0.58 + 0.2 * R(1)), k = 0.62 + 0.2 * R(2), dk = new THREE.MeshStandardMaterial({ color: '#191c28', roughness: 1 });
  g.add(building(w, d, h1, { ...o, roof: false, hoarding: false })); const b2 = building(w * k, d * k, h - h1, { ...o, seed: sd + 3, roof: false, hoarding: false }); b2.position.y = h1; g.add(b2);
  g.add(mesh(new THREE.BoxGeometry(d + 0.04, 0.05, w + 0.04), dk, { p: [0, h1 + 0.025, 0], cast: false }), mesh(new THREE.BoxGeometry(d * k + 0.04, 0.06, w * k + 0.04), dk, { p: [0, h + 0.03, 0], cast: false }), mesh(new THREE.BoxGeometry(d * k * 0.5, 0.22, w * k * 0.5), dk, { p: [0, h + 0.14, 0], cast: false }), mesh(new THREE.BoxGeometry(0.02, 0.9, 0.02), dk, { p: [0, h + 0.65, 0], cast: false }), mesh(ball(0.035), mat('#ff2a1a', 'glow', { power: 6 }), { p: [0, h + 1.1, 0], cast: false }));
  if (o.sign ?? R(4) < 0.45) { const col = ['#ff5a4a', '#5ad0ff', '#ffd75e', '#7CFFB2', '#ff8ad0'][Math.floor(R(5) * 5)], f = o.face ?? 1; g.add(mesh(new THREE.PlaneGeometry(w * k * 0.7, 0.16), new THREE.MeshBasicMaterial({ color: new THREE.Color(col).multiplyScalar(2.2) }), { p: [f * (d * k / 2 + 0.01), h - 0.16, 0], r: [0, f * Math.PI / 2, 0], cast: false, recv: false })); }
  return g; }

/** an old clock tower: stone shaft, a lit clock on each face, a pointed roof */
export function clockTower(h = 4.2) { const g = new THREE.Group(), st = mat('#6a5a48', 'paint', { ridge: 0.8, mottle: 0.3 }), st2 = mat('#4a3e34', 'paint', { ridge: 0.8 }), w = 0.52;
  g.add(mesh(new THREE.BoxGeometry(w, h, w), st, { p: [0, h / 2, 0] }), mesh(new THREE.BoxGeometry(w + 0.1, 0.07, w + 0.1), st2, { p: [0, h * 0.62, 0] }), mesh(new THREE.BoxGeometry(w + 0.12, 0.08, w + 0.12), st2, { p: [0, h + 0.04, 0] }), mesh(new THREE.ConeGeometry(w * 0.74, 0.9, 4), st2, { p: [0, h + 0.53, 0], r: [0, Math.PI / 4, 0] }), mesh(ball(0.03), mat('#ffd98a', 'glow', { power: 5 }), { p: [0, h + 1.02, 0], cast: false }));
  const ck = paintTex(128, 128, (c) => { c.fillStyle = '#fff1c8'; c.beginPath(); c.arc(64, 64, 60, 0, 7); c.fill(); c.strokeStyle = '#2a2018'; c.lineWidth = 5; c.stroke(); c.lineWidth = 4; for (let i = 0; i < 12; i++) { const a = i / 12 * Math.PI * 2; c.beginPath(); c.moveTo(64 + Math.cos(a) * 48, 64 + Math.sin(a) * 48); c.lineTo(64 + Math.cos(a) * 56, 64 + Math.sin(a) * 56); c.stroke(); } c.lineWidth = 6; c.beginPath(); c.moveTo(64, 64); c.lineTo(64 - 8, 64 - 34); c.stroke(); c.lineWidth = 4; c.beginPath(); c.moveTo(64, 64); c.lineTo(64 - 30, 64 - 34); c.stroke(); });
  for (let i = 0; i < 4; i++) { const a = i * Math.PI / 2; g.add(mesh(new THREE.CircleGeometry(0.19, 28), new THREE.MeshBasicMaterial({ map: ck, color: new THREE.Color(2.1, 1.9, 1.5) }), { p: [Math.sin(a) * (w / 2 + 0.006), h * 0.82, Math.cos(a) * (w / 2 + 0.006)], r: [0, a, 0], cast: false, recv: false }));
    for (let j = 0; j < 5; j++) g.add(mesh(new THREE.PlaneGeometry(0.07, 0.2), new THREE.MeshBasicMaterial({ color: j % 2 ? '#0a0c14' : new THREE.Color(1.5, 1.1, 0.6) }), { p: [Math.sin(a) * (w / 2 + 0.004), h * (0.1 + 0.1 * j), Math.cos(a) * (w / 2 + 0.004)], r: [0, a, 0], cast: false, recv: false })); }
  return g; }
