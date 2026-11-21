// CITY — a model city at night, made the way a miniature is: card buildings with painted faces and pin-prick windows,
// balconies, water tanks, hoardings and shop signs; toy traffic; sponge trees; a painted sky with cotton clouds and a paper moon.
import { THREE, mat, ball, limb, rbox, tube, mesh, group, paintTex, V3, lerp, clamp, hash } from './clay.js';

const WALLS = ['#8a7a66', '#75848a', '#9a8474', '#66768c', '#93886e', '#7e7268', '#66807a', '#8c6f66', '#6f6a80'];
const SIGNS = [['चाय', '#ffb347', '#3a1c08'], ['मेडिकल', '#7CFFB2', '#06301a'], ['किराना', '#ffd75e', '#3a2a06'], ['सलून', '#ff7a9a', '#3a0816'], ['STD · PCO', '#8fd0ff', '#06223a'], ['वड़ा पाव', '#ffcf6a', '#402006'], ['टेलर', '#c9a0ff', '#1e0a3a'], ['HOTEL', '#ff6a5a', '#3a0a06']];
const CW = 26, CH = 34;                                      // one window bay of a painted face, in pixels (0.3 m × 0.34 m of model)
export const BAYW = 0.3, FLOORH = 0.34;

/** A painted building face and the light that comes out of it: { map, emis, cols, rows }. Drawn once, shared by many buildings. */
export function facade(seed, cols = 6, rows = 10, o = {}) {
  const W = cols * CW, H = rows * CH, lit = o.lit ?? 0.34, wall = new THREE.Color(WALLS[seed % WALLS.length]).multiplyScalar(o.dim ?? 0.5), R = (n) => hash(seed * 13.7 + n);
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
let POOL = null;
export function facades() { if (!POOL) { POOL = []; for (let i = 0; i < 12; i++) POOL.push(facade(i + 1, 5 + (i % 3), 8 + (i % 4) * 2, { lit: 0.28 + 0.12 * hash(i), shop: i % 3 === 0 })); } return POOL; }

/**
 * A building: w (along z), d (along x), h; its faces painted; a parapet, a tank, a stair head, an aerial, sometimes a hoarding.
 * o: { seed, glow (how strongly its windows shine), roof: false to leave the top bare (far ones), face: +1 | -1 (which x side looks at the camera) }
 */
export function building(w, d, h, o = {}) {
  const seed = o.seed ?? 1, R = (n) => hash(seed * 7.7 + n), F = facades()[Math.floor(R(1) * 12)], g = new THREE.Group(), glow = o.glow ?? 2.2;
  const side = (len) => { const m = F.map.clone(), e = F.emis.clone(); const rx = Math.max(1, Math.round(len / BAYW)) / F.cols, ry = Math.max(1, Math.round(h / FLOORH)) / F.rows; for (const t of [m, e]) { t.repeat.set(rx, ry); t.offset.set(Math.floor(R(len) * F.cols) / F.cols, 1 - ry); t.needsUpdate = true; }
    return new THREE.MeshStandardMaterial({ map: m, emissiveMap: e, emissive: 0xffffff, emissiveIntensity: glow, roughness: 0.92 }); };
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
