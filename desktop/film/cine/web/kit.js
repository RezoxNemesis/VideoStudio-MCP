// the film's kit: moods (light + colour), the road world, props, small living things
import { THREE, V3, clamp, lerp, sm, seg, rng, Card } from './engine.js';
const C = h => new THREE.Color(h);
export const MOODS = {
  gold: { zen: '#4f86c6', hor: '#f6dcae', low: '#c9b48e', sunc: '#fff4d6', glow: '#ffc47a', sky: [14, 0.75, 0], sun: [0.78, 0.26, -0.56], light: '#ffe6ba', shade: '#8676ac', rim: '#ffcf8e', amb: '#262a3c', out: '#3a2230',
    fog: '#f1d2a4', fogs: '#ffdca0', fogp: [0.0026, 0.012, 0.9, 0.85], g: '#ffeccb', rd: '#ffe9cf', sht: '#6d6396', tree: ['#fff0c4', '#b9a184'], gr: ['#fff2bf', '#c9b77e'], cl: ['#fff6e2', '#e2b79a'], grade: [1.04, 0.96, 1.1, 0.55], lift: '#0a0612', gain: '#fff8ee' },
  kite: { zen: '#2f78d2', hor: '#cfe7f6', low: '#b9c8b0', sunc: '#ffffff', glow: '#ffffff', sky: [30, 0.35, 0], sun: [0.35, 0.80, -0.48], light: '#fffaee', shade: '#8494c2', rim: '#ffffff', amb: '#28303f', out: '#27223a',
    fog: '#cfe2f0', fogs: '#eef6ff', fogp: [0.0011, 0.01, 0.8, 0.4], g: '#f6ffe2', rd: '#f2f2f2', sht: '#6f7fb4', tree: ['#f4ffd8', '#b6c4a4'], gr: ['#f8ff9c', '#d6d864'], cl: ['#ffffff', '#b9c9e2'], grade: [1.03, 0.97, 1.14, 0.35], lift: '#060a14', gain: '#ffffff' },
  monsoon: { zen: '#5d6c78', hor: '#a9b5b8', low: '#7e8c86', sunc: '#c8d0d2', glow: '#c0c8cc', sky: [6, 0.25, 0], sun: [0.3, 0.75, -0.5], light: '#c6d0d2', shade: '#8f9bae', rim: '#d8e4ea', amb: '#30383e', out: '#1e2630',
    fog: '#9eacb0', fogs: '#b4c0c4', fogp: [0.0075, 0.006, 0.93, 0.2], g: '#9fc49a', rd: '#a8b0b8', sht: '#8088a0', tree: ['#9cc09a', '#5f8068'], gr: ['#b4d890', '#7ca070'], cl: ['#b4bec4', '#6c7884'], grade: [1.0, 1.0, 0.96, 0.25], lift: '#081014', gain: '#f2f8ff' },
  amber: { zen: '#3f5596', hor: '#ffb277', low: '#b07a5c', sunc: '#fff0c8', glow: '#ff9a4e', sky: [10, 0.95, 0], sun: [0.93, 0.13, -0.33], light: '#ffc48a', shade: '#62548c', rim: '#ffb26a', amb: '#221f38', out: '#2c1a2c',
    fog: '#e9a374', fogs: '#ffbe7a', fogp: [0.0034, 0.01, 0.92, 0.95], g: '#e9c59a', rd: '#e4bfa4', sht: '#514a80', tree: ['#d79a66', '#5c4860'], gr: ['#f0c070', '#8a6c58'], cl: ['#ffc98f', '#8a6088'], grade: [1.03, 0.97, 1.12, 0.7], lift: '#0c0616', gain: '#fff4e6' },
  fog: { zen: '#b3bcc2', hor: '#d6dada', low: '#c4c8c6', sunc: '#f4f4ee', glow: '#e8e8e2', sky: [20, 0.5, 0], sun: [0.6, 0.35, -0.7], light: '#dcdcd6', shade: '#a2a8b6', rim: '#eef0f0', amb: '#3a3e44', out: '#3a3e48',
    fog: '#cdd2d3', fogs: '#e2e4e0', fogp: [0.024, 0.004, 0.985, 0.5], g: '#b9c4b0', rd: '#b4b8bc', sht: '#9ca2b0', tree: ['#9aa69a', '#7c8684'], gr: ['#b8c0a0', '#909a88'], cl: ['#e8e8e4', '#b8bcc0'], grade: [1.0, 1.0, 0.72, 0.2], lift: '#101214', gain: '#fafcff' },
  pink: { zen: '#3a4a9c', hor: '#ffab92', low: '#8c5c80', sunc: '#ffe6c8', glow: '#ff7a66', sky: [8, 0.62, 0.05], sun: [0.95, 0.07, -0.3], light: '#ffc4ac', shade: '#6c5c9c', rim: '#ff9e8a', amb: '#2c2648', out: '#24183a',
    fog: '#c98c9c', fogs: '#ffa684', fogp: [0.0032, 0.01, 0.9, 0.95], g: '#dcaeae', rd: '#d4acb8', sht: '#4a4284', tree: ['#b8788a', '#463a66'], gr: ['#dc9a96', '#6a5278'], cl: ['#ffc0a4', '#7a5a96'], grade: [1.04, 0.97, 1.05, 0.6], lift: '#0c0618', gain: '#fff0f0' },
  blue: { zen: '#1a2858', hor: '#6e7ec2', low: '#3a4678', sunc: '#000000', glow: '#ff8a9a', sky: [6, 0.5, 0.4], sun: [0.97, 0.03, -0.24], light: '#aabaea', shade: '#4c568a', rim: '#ff8fa0', amb: '#262e54', out: '#10142c',
    fog: '#46588e', fogs: '#b0708e', fogp: [0.0036, 0.01, 0.9, 0.8], g: '#7e8ec6', rd: '#8494c4', sht: '#3a4478', tree: ['#4c5c94', '#262e58'], gr: ['#6078b8', '#343e70'], cl: ['#7c80bc', '#363e70'], grade: [1.12, 0.97, 1.05, 0.7], lift: '#060818', gain: '#f0f2ff' },
  night: { zen: '#080d22', hor: '#22305e', low: '#121a34', sunc: '#000000', glow: '#30407a', sky: [4, 0.3, 1.0], sun: [-0.45, 0.62, -0.64], light: '#a9c0f0', shade: '#465284', rim: '#b9c8f0', amb: '#263050', out: '#080a18',
    fog: '#1c2748', fogs: '#28386a', fogp: [0.0042, 0.012, 0.9, 0.3], g: '#6a82b6', rd: '#7084b4', sht: '#343e6c', tree: ['#44568c', '#20294e'], gr: ['#5870a8', '#2c3662'], cl: ['#4e5c92', '#1c2444'], grade: [1.22, 0.97, 1.0, 0.8], lift: '#04060e', gain: '#eef2ff' },
  neon: { zen: '#0d1030', hor: '#6e3e86', low: '#1e1840', sunc: '#000000', glow: '#ff6c9c', sky: [9, 0.55, 0.8], sun: [0.985, 0.05, -0.16], light: '#d2b4ea', shade: '#584c84', rim: '#ff8ab8', amb: '#322a52', out: '#0c0a1e',
    fog: '#3a2e64', fogs: '#e074a2', fogp: [0.0034, 0.012, 0.9, 0.9], g: '#8e7cb4', rd: '#9484b8', sht: '#3c3468', tree: ['#544a88', '#241f4c'], gr: ['#6c62a4', '#322c62'], cl: ['#8a66a8', '#2c2656'], grade: [1.2, 0.97, 1.02, 0.85], lift: '#05050f', gain: '#fff0fa' },
  // first light over the neon city: peach horizon, teal high sky, warm low sun, windows still lit
  vice: { zen: '#2c5fa8', hor: '#ffb08a', low: '#7a4a7a', sunc: '#fff0d0', glow: '#ff8a70', sky: [9, 0.85, 0.12], sun: [0.97, 0.10, -0.22], light: '#ffd0b0', shade: '#6a5a9a', rim: '#ffb090', amb: '#30284c', out: '#22163a',
    fog: '#d090a0', fogs: '#ffb48a', fogp: [0.0024, 0.01, 0.88, 0.95], g: '#e0b0b4', rd: '#d8b0c0', sht: '#4c4488', tree: ['#c08090', '#42386a'], gr: ['#e0a09a', '#6a5680'], cl: ['#ffc8a8', '#8060a0'], grade: [1.06, 0.97, 1.12, 0.7], lift: '#0c0618', gain: '#fff4ee' },
  // a bedroom at night lit by one lamp and the television
  room: { zen: '#05060c', hor: '#05060c', low: '#05060c', sunc: '#000000', glow: '#000000', sky: [4, 0.0, 0], sun: [-0.35, 0.72, 0.6], light: '#ffd9a8', shade: '#5a4e78', rim: '#8ab4ff', amb: '#2a2436', out: '#1a1220',
    fog: '#0a0a14', fogs: '#0a0a14', fogp: [0.0001, 0.01, 0.2, 0], g: '#ffffff', rd: '#ffffff', sht: '#404060', tree: ['#ffffff', '#888888'], gr: ['#ffffff', '#888888'], cl: ['#ffffff', '#888888'], grade: [1.08, 0.97, 1.06, 0.55], lift: '#08060c', gain: '#fff6ea' },
};
export function setMood(E, name, mix) {
  const m = MOODS[name]; const L = E.L, S = E.S, P = E.P; E.mood = m; E.moodName = name;
  S.uZen.value.set(m.zen); S.uHor.value.set(m.hor); S.uLow.value.set(m.low); S.uSunCol.value.set(m.sunc); S.uGlowCol.value.set(m.glow); S.uSky.value.set(m.sky[0], m.sky[1], m.sky[2], 0);
  const sd = new V3(...m.sun).normalize(); L.uSunDir.value.copy(sd); L.uLightDir.value.copy(sd.y < 0.2 ? new V3(sd.x, 0.24, sd.z).normalize() : sd);
  L.uLightCol.value.set(m.light); L.uShadeCol.value.set(m.shade).lerp(new THREE.Color('#ffffff'), 0.22); L.uRimCol.value.set(m.rim); L.uRimDir.value.copy(sd); L.uAmbCol.value.set(m.amb); L.uOutCol.value.set(m.out);
  L.uFogCol.value.set(m.fog); L.uFogSun.value.set(m.fogs); L.uFog.value.set(...m.fogp);
  P.uGrade.value.set(...m.grade); P.uLift.value.set(m.lift); P.uGainC.value.set(m.gain);
  if (m.sky[2] > 0 && !E.sky.material.defines.STARS) { E.sky.material.defines.STARS = 1; E.sky.material.needsUpdate = true; }
  if (E.G) { E.G.uGTint.value.set(m.g); E.G.uRTint.value.set(m.rd); E.G.uShadowTint.value.set(m.sht); E.G.uSkyRef.value.set(m.hor); }
  return m;
}
export const TEX = ['city', 'grass', 'dirt', 'road', 'var', 'grassA', 'neem0', 'neem1', 'neem2', 'banyan0', 'gulmohar0', 'bare0', 'bare1', 'bush0', 'bush1', 'palm0', 'palm1', 'cloud0', 'cloud1', 'cloud2', 'cloud3', 'cloud4', 'cloud5', 'mist', 'glow', 'ring', 'hills0', 'hills1', 'treeline'];
export async function init(E, extra = []) {
  for (const t of TEX.concat(extra)) await E.loadTex(t, { repeat: ['grass', 'dirt', 'road', 'var', 'hills0', 'hills1', 'treeline', 'water'].includes(t), raw: /^(deco|tower)\d/.test(t) });
  await loadMarks();
  for (const [fam, file, w] of [['Bebas', 'bebas-neue-latin-400-normal', '400'], ['Oswald', 'oswald-latin-500-normal', '500'], ['Oswald', 'oswald-latin-700-normal', '700'], ['Deva', 'noto-sans-devanagari-devanagari-500-normal', '500'], ['Deva', 'noto-sans-devanagari-devanagari-700-normal', '700']]) {
    const f = new FontFace(fam, `url(fonts/${file}.woff2)`, { weight: w }); await f.load(); document.fonts.add(f); }
}
const SPEC = { neem: { tex: ['neem0', 'neem1', 'neem2'], h: [9, 13], sway: 0.35 }, banyan: { tex: ['banyan0'], h: [11, 14], sway: 0.25 }, gulmohar: { tex: ['gulmohar0'], h: [7, 9.5], sway: 0.3 }, bare: { tex: ['bare0', 'bare1'], h: [8, 11], sway: 0.1 },
  bush: { tex: ['bush0', 'bush1'], h: [1.3, 2.6], sway: 0.12 }, palm: { tex: ['palm0', 'palm1'], h: [11, 15], sway: 0.5 } };
export function tree(E, kind, x, z, o = {}) {
  const sp = SPEC[kind]; const R = o.R || Math.random; const m = E.mood; const tx = E.tex[sp.tex[Math.floor(R() * sp.tex.length)]]; const h = (o.h || lerp(sp.h[0], sp.h[1], R())) * (o.s || 1);
  return E.card(tx, { x, z, y: o.y || -0.05, w: h * tx.userData.w / tx.userData.h, h, sway: o.sway ?? sp.sway * (E.wind || 1), swf: 0.9 + R() * 0.5, tint: o.tint || m.tree[0], tint2: o.tint2 || m.tree[1], shadow: o.shadow !== false, flip: R() < 0.5, fog: o.fog ?? 1, shStr: o.shStr ?? 0.5 });
}
// the world along the road: x is the way forward, the lens usually stands on +z looking across
export function roadSet(E, o) {
  const R = rng(o.seed || 1); const m = setMood(E, o.mood); E.wind = o.wind ?? 1; const x0 = o.x0, x1 = o.x1, cx = (x0 + x1) / 2;
  E.ground({ field: !!o.field, road2: !!o.road2, wet: !!(o.wet || o.puddle) }); setMood(E, o.mood); const G = E.G; G.uRoad.value.set(o.half ?? 3.0, o.shoulder ?? 1.6, o.marks ?? 0.8, o.wet || 0); G.uPuddle.value = o.puddle || 0;
  if (o.field) { G.uFieldP.value.set(o.field.amt ?? 1, o.field.z ?? 12, 2.6, 0); G.uField.value.set(o.field.col || '#d9c63a'); }
  // far ring: hills and a distant line of trees on every side
  if (o.hills !== false) { const hv = E.tex['hills' + (o.hillv || 0)];
    for (const [px, pz, ry] of [[cx, -2600, 0], [cx, 2600, Math.PI], [cx + 2600, 0, -Math.PI / 2], [cx - 2600, 0, Math.PI / 2]]) E.card(hv, { x: px, z: pz, y: -6, w: 6200, h: 330 * (o.hillh || 1), ry, uv: [R(), 0, 1.5, 1], tint: o.hilltint || m.tree[0], tint2: o.hilltint2 || m.tree[1], fog: 1, order: -800, blur: 0 });
    const tl = E.tex.treeline; for (const [pz, ry, d] of [[-(o.tlz || 300), 0, 1], [(o.tlz || 300), Math.PI, 1], [-(o.tlz || 300) * 2.2, 0, 1.7], [(o.tlz || 300) * 2.2, Math.PI, 1.7]]) E.card(tl, { x: cx, z: pz, y: -1, w: 2400 * d, h: 30 * d, ry, uv: [R(), 0, 2, 1], tint: m.tree[0], tint2: m.tree[1], order: -700 + d }); }
  // clouds on a wide dome, drifting
  const cl = []; for (let i = 0; i < ((window.OFF || '').includes('cloud') ? 0 : (o.clouds ?? 16)); i++) { const az = R() * Math.PI * 2, el = (0.07 + 0.42 * R() * R()) , d = 3200; const w = 620 + 900 * R();
    const c = E.card(E.tex['cloud' + Math.floor(R() * 6)], { x: cx + Math.cos(az) * d, z: Math.sin(az) * d, y: d * Math.tan(el), w, h: w * 0.5, relight: true, lit: m.cl[0], shd: m.cl[1], fog: 0.35, flip: R() < 0.5, order: -850, blur: 0, op: o.cloudop ?? 0.95 }); c.vx = (o.cloudv ?? 30) * (0.7 + 0.6 * R()); cl.push(c); }
  E.updaters.push(t => { for (const c of cl) { c.m.position.x = c.o.x + c.vx * t; } });
  // trees: an avenue near the road, groves further off
  const tk = o.trees || { neem: 3, banyan: 1, gulmohar: 0.6, palm: 0.8 }; const kinds = Object.keys(tk); const tot = kinds.reduce((a, k) => a + tk[k], 0);
  const pick = () => { let r = R() * tot; for (const k of kinds) { r -= tk[k]; if (r <= 0) return k; } return kinds[0]; };
  const dens = (window.OFF || '').includes('tree') ? 0.01 : (o.dens ?? 1); const skip = o.clear || (() => false);
  for (let x = x0 - 60; x < x1 + 260; x += (11 + 16 * R()) / dens) for (const sd of [-1, 1]) { if (R() < 0.3) continue; const z = sd * (7.5 + 5 * R()); if (skip(x, z)) continue; tree(E, pick(), x + 4 * R(), z, { R }); }
  for (let i = 0; i < 70 * dens; i++) { const x = x0 - 120 + R() * (x1 - x0 + 520), z = (R() < 0.5 ? -1 : 1) * (22 + 190 * R() * R()); if (skip(x, z)) continue; tree(E, pick(), x, z, { R, shadow: Math.abs(z) < 60 }); }
  for (let i = 0; i < 46 * dens; i++) { const x = x0 - 40 + R() * (x1 - x0 + 200), z = (R() < 0.5 ? -1 : 1) * (5.2 + 22 * R()); if (skip(x, z)) continue; tree(E, 'bush', x, z, { R, shStr: 0.4 }); }
  // grass along the verges
  const OFF = window.OFF || ''; const gl = []; const gn = OFF.includes('grass') ? 1 : (o.grass ?? 2600); const gz = o.grassz || [3.5, 15];
  for (let i = 0; i < gn; i++) { const x = x0 - 25 + R() * (x1 - x0 + 90); const u = R(); let z = (R() < 0.5 ? -1 : 1) * (gz[0] + (gz[1] - gz[0]) * u * u); if (o.grassNearMin && z > 0 && z < o.grassNearMin) z = -z; gl.push([x, 0, z, (0.45 + 0.55 * R() + (o.grassh || 0)) * (o.nearLow && z > 0 && z < (o.nearLowZ || 6.6) ? 0.4 : 1), o.flowers && R() < o.flowers ? 3 : Math.floor(R() * 3), R() * 6.28, R(), R() < 0.5 ? 1 : 0]); }
  E.grassMesh = E.grass(E.tex.grassA, gl, { tint: m.gr[0], tintB: m.gr[1], wind: 0.07 * E.wind, freq: 2.4 });
  return { R, m };
}
// ---------- props ----------
export function canvasTex(w, h, draw) { const c = document.createElement('canvas'); c.width = w; c.height = h; const g = c.getContext('2d'); draw(g, w, h); const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.NoColorSpace; t.anisotropy = 4; t.userData = { c, g, draw, w, h }; return t; }
export function redraw(t, draw) { const { g, w, h } = t.userData; g.clearRect(0, 0, w, h); draw(g, w, h); t.needsUpdate = true; }
function speckle(g, w, h, n, a) { const Q = rng(1234); for (let i = 0; i < n; i++) { g.fillStyle = `rgba(${Q() < 0.5 ? '60,50,40' : '255,255,255'},${a * Q()})`; g.fillRect(Q() * w, Q() * h, 1 + Q() * 3, 1 + Q() * 3); } }
export function drawStone(top, num, unit, topCol = '#f0bd2c') { return (g, w, h) => { g.fillStyle = '#ece6d8'; g.fillRect(0, 0, w, h); g.fillStyle = topCol; g.fillRect(0, 0, w, h * 0.36); g.fillStyle = '#1b1a1c'; g.textAlign = 'center'; g.textBaseline = 'middle';
  g.font = `700 ${h * 0.17}px Oswald`; g.fillText(top, w / 2, h * 0.215); g.font = `700 ${h * (String(num).length > 2 ? 0.26 : 0.36)}px Oswald`; g.fillText(num, w / 2, h * 0.60); g.font = `500 ${h * 0.105}px Oswald`; g.fillText(unit, w / 2, h * 0.86);
  speckle(g, w, h, 500, 0.16); const gr = g.createLinearGradient(0, h * 0.8, 0, h); gr.addColorStop(0, 'rgba(90,70,40,0)'); gr.addColorStop(1, 'rgba(90,70,40,0.45)'); g.fillStyle = gr; g.fillRect(0, 0, w, h); }; }
export function milestone(E, x, z, top, num, unit, o = {}) {
  const w = 0.56, h = 0.86, d = 0.2; const sh = new THREE.Shape(); sh.moveTo(-w / 2, 0); sh.lineTo(-w / 2, h - w / 2); sh.absarc(0, h - w / 2, w / 2, Math.PI, 0, true); sh.lineTo(w / 2, 0); sh.lineTo(-w / 2, 0);
  const geo = new THREE.ExtrudeGeometry(sh, { depth: d, bevelEnabled: false, curveSegments: 20 }); geo.translate(0, 0, -d / 2); const p = geo.getAttribute('position'), uv = geo.getAttribute('uv');
  for (let i = 0; i < p.count; i++) uv.setXY(i, p.getX(i) / w + 0.5, p.getY(i) / h);
  const tex = canvasTex(256, 384, drawStone(top, num, unit, o.topCol)); const m = E.prop(geo, { map: tex, side: THREE.DoubleSide }); m.position.set(x, -0.04, z); m.rotation.y = o.ry ?? -0.9; m.userData.tex = tex;
  m.userData.set = (t2, n2, u2) => redraw(tex, drawStone(t2, n2, u2, o.topCol)); return m;
}
export function box(E, w, h, d, o = {}) { const g = new THREE.BoxGeometry(w, h, d); g.translate(0, h / 2, 0); const m = E.prop(g, o); return m; }
export function sign(E, x, z, w, h, draw, o = {}) { // a hoarding on two legs, facing ry
  const grp = new THREE.Group(); const y0 = o.y0 ?? 3.2; const tex = canvasTex(o.tw || 1024, o.th || Math.round((o.tw || 1024) * h / w), draw);
  const b = E.prop(new THREE.PlaneGeometry(w, h), { map: tex, side: THREE.DoubleSide, emis: o.emis }); b.position.set(0, y0 + h / 2, 0.06); grp.add(b);
  const bk = box(E, w + 0.2, h + 0.2, 0.1, { color: '#3a3a40' }); bk.position.set(0, y0 - 0.1, 0); grp.add(bk);
  for (const s of [-1, 1]) { const l = box(E, 0.18, y0 + h, 0.18, { color: '#4a4a52' }); l.position.set(s * w * 0.36, 0, -0.1); grp.add(l); }
  grp.position.set(x, 0, z); grp.rotation.y = o.ry ?? -Math.PI / 2; E.scene.add(grp); grp.userData.tex = tex; grp.userData.board = b; return grp;
}
export function stripes(n, a = '#d8382c', b = '#f2efe6') { return (g, w, h) => { for (let i = 0; i < n; i++) { g.fillStyle = i % 2 ? b : a; g.fillRect(i * w / n, 0, w / n + 1, h); } }; }
export function gate(E, x, text, o = {}) { // toll gate: pillars, beam with a lit sign, booth, boom
  const grp = new THREE.Group(); const half = 4.6;
  for (const s of [-1, 1]) { const p = box(E, 0.55, 5.6, 0.55, { color: '#d9d2c2' }); p.position.set(0, 0, s * half); grp.add(p); const st = box(E, 0.57, 1.0, 0.57, { map: canvasTex(64, 64, (g, w, h) => { for (let i = 0; i < 4; i++) { g.fillStyle = i % 2 ? '#f0c020' : '#1c1c20'; g.fillRect(0, i * 16, 64, 16); } }) }); st.position.set(0, 0.0, s * half); grp.add(st); }
  const beam = box(E, 0.5, 0.6, half * 2 + 0.6, { color: '#c9c2b2' }); beam.position.set(0, 5.4, 0); grp.add(beam);
  const draw = t => (g, w, h) => { g.fillStyle = '#12141c'; g.fillRect(0, 0, w, h); g.strokeStyle = '#f0bd2c'; g.lineWidth = 10; g.strokeRect(8, 8, w - 16, h - 16); g.fillStyle = '#ffe9a8'; g.textAlign = 'center'; g.textBaseline = 'middle';
    const L = t.split('|'); if (L.length === 1) { g.font = `700 ${h * 0.56}px Oswald`; g.fillText(L[0], w / 2, h * 0.54); } else { g.font = `500 ${h * 0.2}px Oswald`; g.fillStyle = '#f0bd2c'; g.fillText(L[0], w / 2, h * 0.24); g.fillStyle = '#ffe9a8'; g.font = `700 ${h * 0.5}px Oswald`; g.fillText(L[1], w / 2, h * 0.64); } };
  const tex = canvasTex(1024, 300, draw(text)); const sg = E.prop(new THREE.PlaneGeometry(6.4, 1.9), { map: tex, emis: o.emis || '#000000', shadow: false }); sg.position.set(-0.28, 6.75, 0); sg.rotation.y = -Math.PI / 2; grp.add(sg);
  const sb = box(E, 0.16, 2.1, 6.6, { color: '#2c2c34' }); sb.position.set(-0.16, 5.7, 0); grp.add(sb);
  const booth = box(E, 1.6, 2.5, 1.5, { color: '#e2c79a' }); booth.position.set(0.2, 0, -half - 1.3); grp.add(booth); const roof = box(E, 2.0, 0.14, 1.9, { color: '#a8452e' }); roof.position.set(0.2, 2.5, -half - 1.3); grp.add(roof);
  const piv = new THREE.Group(); piv.position.set(-0.6, 1.05, -half + 0.5); const arm = E.prop(new THREE.BoxGeometry(0.12, 0.12, 8.4).translate(0, 0, 4.2), { map: canvasTex(512, 16, stripes(12)) });
  const u = arm.geometry.getAttribute('uv'), pp = arm.geometry.getAttribute('position'); for (let i = 0; i < u.count; i++) u.setXY(i, pp.getZ(i) / 8.4, 0.5);
  piv.add(arm); const cw = box(E, 0.3, 0.5, 0.5, { color: '#2c2c34' }); cw.position.set(0, -0.25, -0.5); piv.add(cw); const post = box(E, 0.22, 1.05, 0.22, { color: '#2c2c34' }); post.position.set(-0.6, 0, -half + 0.5); grp.add(post); grp.add(piv);
  grp.position.set(x, 0, 0); E.scene.add(grp);
  const api = { grp, tex, sg, arm: a => { piv.rotation.x = -a; }, text: t => redraw(tex, draw(t)), emis: c => sg.material.uniforms.uEmis.value.set(c) }; api.arm(o.open ?? 1.45); return api;
}
export function kites(E, n, cx, o = {}) { const R = rng(o.seed || 9); const cols = ['#e8452c', '#f0bd2c', '#2c9c6c', '#3c6cd8', '#e86ca0', '#f08c2c', '#8c4cc8', '#ffffff']; const list = [];
  for (let i = 0; i < n; i++) { const c1 = cols[Math.floor(R() * cols.length)], c2 = cols[Math.floor(R() * cols.length)];
    const tex = canvasTex(128, 160, (g, w, h) => { g.translate(w / 2, h * 0.42); g.beginPath(); g.moveTo(0, -h * 0.4); g.lineTo(w * 0.46, 0); g.lineTo(0, h * 0.4); g.lineTo(-w * 0.46, 0); g.closePath(); g.fillStyle = c1; g.fill(); g.save(); g.clip(); g.fillStyle = c2; g.fillRect(-w, 0, 2 * w, h); g.restore();
      g.strokeStyle = 'rgba(40,30,20,0.8)'; g.lineWidth = 2; g.beginPath(); g.moveTo(0, -h * 0.4); g.lineTo(0, h * 0.4); g.stroke(); g.beginPath(); g.moveTo(-w * 0.46, 0); g.quadraticCurveTo(0, -h * 0.28, w * 0.46, 0); g.stroke(); g.fillStyle = c1; g.beginPath(); g.moveTo(-9, h * 0.4); g.lineTo(9, h * 0.4); g.lineTo(0, h * 0.56); g.fill(); });
    tex.premultiplyAlpha = true; const s = 2.0 + 1.6 * R(); const k = E.card(tex, { x: cx + (R() - 0.3) * (o.spread || 220), z: (R() - 0.5) * (o.zs || 160), y: 9 + 30 * R(), w: s, h: s * 1.25, mode: 'face', center: true, fog: 0.3, blur: 0, order: -100 });
    k.b = [k.o.x, k.o.y, k.o.z, R() * 6.28, 0.5 + R() * 0.7, 2 + 4 * R()]; list.push(k);
    if (o.shadows) { const st = canvasTex(64, 64, (g, w, h) => { g.fillStyle = 'rgba(20,24,50,0.42)'; g.beginPath(); g.moveTo(32, 2); g.lineTo(60, 28); g.lineTo(32, 62); g.lineTo(4, 28); g.fill(); }); st.premultiplyAlpha = true; k.sh = E.card(st, { x: 0, y: 0.03, z: 0, w: s * 1.1, h: s * 1.3, ry: R() * 3, rx: -Math.PI / 2, center: true, fog: 0, blur: 1.2 }); } }
  E.updaters.push(t => { for (const k of list) { const [x, y, z, ph, f, a] = k.b; k.m.position.set(x + Math.sin(t * f + ph) * a + t * 0.6, y + Math.sin(t * f * 1.9 + ph * 1.3) * a * 0.35, z + Math.cos(t * f * 0.8 + ph) * a * 0.6); k.roll = Math.sin(t * f * 1.9 + ph) * 0.5; if (k.sh) { const L = E.L.uLightDir.value; k.sh.m.position.set(k.m.position.x - L.x / L.y * k.m.position.y, 0.03, k.m.position.z - L.z / L.y * k.m.position.y); } } });
  for (const k of list) { const u0 = k.update.bind(k); k.update = (cam, t) => { u0(cam, t); k.m.rotateZ(k.roll || 0); }; }
  return list;
}
export function birds(E, n, cx, cy, cz, o = {}) { const R = rng(o.seed || 4); const tex = canvasTex(64, 32, (g, w, h) => { g.strokeStyle = o.col || '#1c1c26'; g.lineWidth = 5; g.lineCap = 'round'; g.beginPath(); g.moveTo(4, 6); g.quadraticCurveTo(20, 4, 32, 20); g.quadraticCurveTo(44, 4, 60, 6); g.stroke(); }); tex.premultiplyAlpha = true; const L = [];
  for (let i = 0; i < n; i++) { const b = E.card(tex, { x: cx, y: cy, z: cz, w: 0.9, h: 0.45, mode: 'face', center: true, fog: 0.5, blur: 0 }); b.q = [(R() - 0.5) * (o.sx || 40), (R() - 0.5) * 10, (R() - 0.5) * (o.sz || 30), R() * 6.28, 7 + 3 * R()]; L.push(b); }
  E.updaters.push(t => { for (const b of L) { const [dx, dy, dz, ph, f] = b.q; b.m.position.set(cx + dx + (o.vx ?? 9) * t, cy + dy + Math.sin(t * 0.7 + ph) * 1.5, cz + dz + Math.sin(t * 0.3 + ph) * 3); b.m.scale.y = 0.45 * (0.25 + 0.75 * Math.abs(Math.sin(t * f + ph))); } }); return L; }
export function mist(E, n, x0, x1, o = {}) { const R = rng(o.seed || 12); const L = []; for (let i = 0; i < n; i++) { const w = 30 + 50 * R(); const c = E.card(E.tex.mist, { x: lerp(x0, x1, R()), z: (R() - 0.5) * (o.zs || 80), y: (o.y || 0) + R() * 1.5, w, h: w * (o.hk || 0.14), tint: o.col || E.mood.fog, op: (o.op || 0.35) * (0.5 + 0.5 * R()), fog: 0, blur: 0 }); c.vx = (o.v || 0.8) * (0.5 + R()); L.push(c); }
  E.updaters.push(t => { for (const c of L) c.m.position.x = c.o.x + c.vx * t; }); return L; }
export function glow(E, x, y, z, s, colr, o = {}) { return E.card(E.tex.glow, { x, y, z, w: s, h: s, mode: 'face', center: true, tint: colr, add: true, fog: 0, blur: 0, depthTest: o.depthTest !== false, op: o.op ?? 1, order: o.order ?? 40 }); }
// ---------- camera helpers ----------
export const ease = sm;
export function mix3(a, b, t) { return [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)]; }
export function path(pts, t) { // Catmull-Rom through points, t 0..1
  const n = pts.length - 1; const f = clamp(t, 0, 1) * n; const i = Math.min(n - 1, Math.floor(f)); const u = f - i; const P = k => pts[clamp(k, 0, n)];
  const p0 = P(i - 1), p1 = P(i), p2 = P(i + 1), p3 = P(i + 2); const o = [];
  for (let k = 0; k < 3; k++) o.push(0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * u + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * u * u + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * u * u * u)); return o; }
export function keys(ks, t) { // [[time, value], ...] eased between
  if (t <= ks[0][0]) return ks[0][1]; for (let i = 1; i < ks.length; i++) if (t <= ks[i][0]) { const u = sm((t - ks[i - 1][0]) / (ks[i][0] - ks[i - 1][0])); const a = ks[i - 1][1], b = ks[i][1]; return typeof a === 'number' ? lerp(a, b, u) : mix3(a, b, u); } return ks[ks.length - 1][1]; }
// ---------- text ----------
export function stamp(E, txt, a = 1, sub) { if (!txt || a <= 0.004) { E.text(null); return; } E.text((g, W, H) => { const s = H / 1440; g.globalAlpha = a; g.textBaseline = 'alphabetic'; g.textAlign = 'left'; g.shadowColor = 'rgba(0,0,0,0.55)'; g.shadowBlur = 14 * s;
  g.fillStyle = '#fff6e4'; g.font = `500 ${46 * s}px Oswald`; g.letterSpacing = `${7 * s}px`; g.fillText(txt, 110 * s, H - 104 * s); g.fillRect(110 * s, H - 90 * s, 96 * s, 3 * s);
  if (sub) { g.font = `500 ${28 * s}px Oswald`; g.letterSpacing = `${4 * s}px`; g.globalAlpha = a * 0.8; g.fillText(sub, 110 * s, H - 48 * s); } g.globalAlpha = 1; }); }
export function fadeIO(t, dur, tin = 0.5, tout = 0.5) { return clamp(Math.min(t / tin, (dur - t) / tout), 0, 1); }
// ---------- more of the kit ----------
export let MARKS = {};
export async function loadMarks() { MARKS = await (await fetch('data/marks.json')).json(); return MARKS; }
const lerpHex = (a, b, t) => '#' + new THREE.Color(a).lerp(new THREE.Color(b), t).getHexString();
export function mixMood(E, an, bn, t) { // travel from one light to another inside a shot
  const a = MOODS[an], b = MOODS[bn], m = {}; for (const k in a) { const v = a[k]; m[k] = typeof v === 'string' ? lerpHex(v, b[k], t) : (typeof v[0] === 'string' ? v.map((x, i) => lerpHex(x, b[k][i], t)) : v.map((x, i) => lerp(x, b[k][i], t))); }
  MOODS._mix = m; const tr = E.mood; setMood(E, '_mix'); return m; }
export function rainFx(E, o = {}) { const far = E.rain(o.n || 2600, { box: 36, op: o.op ?? 0.42, len: 0.55, speed: 10, slant: o.slant ?? 0.2 }); const near = E.rain(o.nn || 260, { box: 9, op: (o.op ?? 0.42) * 0.55, len: 0.9, speed: 11, slant: o.slant ?? 0.2, seed: 8 });
  const R = rng(31); const rings = []; for (let i = 0; i < (o.rings ?? 46); i++) { const c = E.card(E.tex.ring, { x: 0, y: 0.02, z: 0, w: 0.3, h: 0.3, ry: 0, rx: -Math.PI / 2, center: true, tint: o.col || '#dfe8ee', fog: 0.5, blur: 0, op: 0 }); c.ph = R(); c.per = 0.45 + 0.4 * R(); c.k = -1; rings.push(c); }
  const api = { far, near, rings, op: 1, center: new V3() };
  E.updaters.push(t => { far.userData.center.copy(api.center); near.userData.center.copy(E.cam.position); far.material.uniforms.uOp.value = (o.op ?? 0.42) * api.op; near.material.uniforms.uOp.value = (o.op ?? 0.42) * 0.55 * api.op;
    for (const c of rings) { const u = t / c.per + c.ph; const k = Math.floor(u), f = u - k; if (k !== c.k) { c.k = k; const r2 = rng(k * 977 + Math.floor(c.ph * 9999)); c.m.position.set(api.center.x + (r2() - 0.5) * 22, 0.02, api.center.z + (r2() - 0.5) * 12); }
      const s = 0.06 + 0.42 * f; c.m.scale.set(s, s, 1); c.u.uOpacity.value = (1 - f) * 0.55 * api.op; } });
  return api; }
export function bursts(E, n, o = {}) { // little thrown drops / sparks: a pool
  const P = []; for (let i = 0; i < n; i++) { const c = E.card(E.tex.glow, { x: 0, y: -5, z: 0, w: 0.05, h: 0.05, mode: 'face', center: true, tint: o.col || '#e8f0f4', add: !!o.add, fog: o.fog ?? 0.4, blur: 0, op: 0 }); c.life = 0; P.push(c); }
  let last = null, idx = 0; const g = o.g ?? 9.8;
  const api = { emit(p, k, sp, up, size, colr, life) { const R = rng(Math.floor(p.x * 131 + p.z * 17 + idx)); for (let i = 0; i < k; i++) { const c = P[idx++ % n]; const a = R() * 6.283, e = R(); c.pos = new V3(p.x, p.y, p.z); c.vel = new V3(Math.cos(a) * sp * (0.3 + e), up * (0.5 + R()), Math.sin(a) * sp * (0.3 + e)); c.life = c.life0 = (life || 0.45) * (0.7 + 0.6 * R()); c.sz = (size || 0.05) * (0.6 + 0.8 * R()); if (colr) c.u.uTint.value.set(colr), c.u.uTint2.value.set(colr); } },
    sphere(p, k, sp, size, colr, life) { const R = rng(Math.floor(p.x * 7 + p.y * 13 + idx)); for (let i = 0; i < k; i++) { const c = P[idx++ % n]; const u = R() * 2 - 1, a = R() * 6.283, s = Math.sqrt(1 - u * u), v = sp * (0.75 + 0.3 * R()); c.pos = new V3(p.x, p.y, p.z); c.vel = new V3(s * Math.cos(a) * v, u * v, s * Math.sin(a) * v); c.life = c.life0 = life * (0.8 + 0.4 * R()); c.sz = size * (0.7 + 0.6 * R()); c.u.uTint.value.set(colr); c.u.uTint2.value.set(colr); } } };
  E.updaters.push(t => { const dt = last === null ? 0 : Math.min(0.1, t - last); last = t; for (const c of P) { if (c.life <= 0) { c.u.uOpacity.value = 0; continue; } c.life -= dt; c.vel.y -= g * dt; if (o.drag) c.vel.multiplyScalar(1 - o.drag * dt); c.pos.addScaledVector(c.vel, dt); if (c.pos.y < 0.01 && !o.add) { c.life = 0; }
    c.m.position.copy(c.pos); const f = Math.max(0, c.life / c.life0); c.m.scale.set(c.sz, c.sz, 1); c.u.uOpacity.value = (o.add ? f * f : Math.min(1, f * 2)) * (o.op ?? 0.9); } });
  return api; }
export function footSplash(E, actors, B, o = {}) { const st = new Map(); const v = new V3();
  E.updaters.push(() => { for (const a of actors) for (const b of ['ball_l', 'ball_r']) { a.world(b, v); const k = a.meta.name + b + a.x0; const was = st.get(k) ?? 1; if (v.y < 0.085 && was >= 0.085) B.emit(new V3(v.x, 0.04, v.z), o.n || 7, o.sp || 1.5, o.up || 1.9, o.size || 0.045); st.set(k, v.y); } }); }
export function wipeBox(E, x, z, wx = 1.7, wz = 0.6) { const b = box(E, wx, 7, wz, { color: '#241a18', shadow: false }); b.position.set(x, -0.5, z); return b; }
export function lamp(E, x, z, o = {}) { const p = box(E, 0.14, 5.2, 0.14, { color: '#3a3a44' }); p.position.set(x, 0, z); const arm = box(E, 0.1, 0.1, 1.5, { color: '#3a3a44', shadow: false }); arm.position.set(x, 5.1, z - 0.75 * Math.sign(z));
  const hx = x, hz = z - 1.4 * Math.sign(z); const c = o.col || '#ffd9a0'; const g1 = glow(E, hx, 5.05, hz, 1.6, c, { op: 1 }); const g2 = glow(E, hx, 5.05, hz, 6, c, { op: 0.35 });
  const pool = E.card(E.tex.glow, { x: hx, y: 0.03, z: hz, w: 13, h: 13, ry: 0, rx: -Math.PI / 2, center: true, tint: c, add: true, fog: 0, blur: 0, op: o.pool ?? 0.3 }); return { g1, g2, pool, set(k) { g1.u.uOpacity.value = k; g2.u.uOpacity.value = 0.35 * k; pool.u.uOpacity.value = (o.pool ?? 0.3) * k; } }; }
export function parapet(E, x, z, ry, top = 0.38) { const tex = canvasTex(256, 32, (g, w, h) => { for (let i = 0; i < 8; i++) { g.fillStyle = i % 2 ? '#e8e2d4' : '#2a2a30'; g.fillRect(i * 32, 0, 32, h); } }); const g = new THREE.BoxGeometry(2.3, top + 0.1, 0.44); g.translate(0, (top + 0.1) / 2 - 0.1, 0);
  const u = g.getAttribute('uv'), p = g.getAttribute('position'); for (let i = 0; i < u.count; i++) u.setXY(i, p.getX(i) / 2.3 + 0.5, 0.5); const m = E.prop(g, { map: tex }); m.position.set(x, 0, z); m.rotation.y = ry; return m; }
export function fireworks(E, list) { // list: [t, x, y, z, colour]
  const B = bursts(E, 300, { add: true, g: 3.5, drag: 0.9, fog: 0, op: 1 }); const done = new Set();
  E.updaters.push(t => { list.forEach((f, i) => { const tt = t - (E.t0 || 0); if (tt >= f[0] && !done.has(i)) { done.add(i); B.sphere(new V3(f[1], f[2], f[3]), 46, f[5] || 16, f[6] || 2.6, f[4], 1.7); } }); }); return B; }
export function cityFar(E, x, o = {}) { const c = E.card(E.tex.city, { x, z: 0, y: o.y ?? -6, w: o.w || 1700, h: (o.w || 1700) * 768 / 4096, ry: -Math.PI / 2, tint: o.tint || '#ffffff', tint2: o.tint2 || '#cfc8ff', fog: o.fog ?? 0.3, blur: 0, order: -780, op: o.op ?? 1 });
  const g = glow(E, x + 30, 60, 0, o.gs || 1500, o.glow || '#ff8a9c', { order: -790, op: o.gop ?? 0.55 }); return { c, g, set(k) { c.u.uOpacity.value = k; g.u.uOpacity.value = (o.gop ?? 0.55) * k; } }; }
export function look(a, yawDeg, pitchDeg, torso = 0) { // turn the head on the neck (yaw + = to the body's left, pitch + = down)
  const e = new THREE.Euler(pitchDeg * 0.55 * Math.PI / 180, yawDeg * 0.55 * Math.PI / 180, 0, 'YXZ'); a.extra.head = new THREE.Quaternion().setFromEuler(e);
  const e2 = new THREE.Euler(pitchDeg * 0.45 * Math.PI / 180, yawDeg * 0.45 * Math.PI / 180, 0, 'YXZ'); a.extra.neck_01 = new THREE.Quaternion().setFromEuler(e2);
  if (torso) a.extra.spine_03 = new THREE.Quaternion().setFromEuler(new THREE.Euler(torso * Math.PI / 180, yawDeg * 0.12 * Math.PI / 180, 0, 'YXZ')); else delete a.extra.spine_03; }
export function placeBy(a, mark, wx, wz, yaw) { // put a body so that a marked moment of its performance lands on a given spot
  const c = Math.cos(yaw), s = Math.sin(yaw); a.yaw = yaw; a.x0 = wx - (mark[0] * c + mark[2] * s); a.z0 = wz - (-mark[0] * s + mark[2] * c); a.place(a.x0, 0, a.z0, yaw); return a; }

// ---------- the neon city ----------
export const CITY_TEX = ['deco0', 'deco1', 'deco2', 'deco3', 'deco4', 'deco5', 'deco6', 'deco7', 'tower0', 'tower1', 'tower2', 'tower3', 'skyfar', 'skymid', 'water', 'beam'];
export function building(E, tex, x, z, w, h, d, o = {}) { // a block with a painted front on its four walls; what is painted as shining glows at night
  const g = new THREE.BoxGeometry(w, h, d); g.translate(0, h / 2, 0); const uv = g.getAttribute('uv'), nr = g.getAttribute('normal'), rep = o.rep || 1;
  for (let i = 0; i < uv.count; i++) { if (Math.abs(nr.getY(i)) > 0.5) uv.setXY(i, 0.02, 0.995); else if (Math.abs(nr.getX(i)) > 0.5) uv.setX(i, uv.getX(i) * Math.max(0.25, d / w) * rep); else uv.setX(i, uv.getX(i) * rep); }
  if (rep !== 1 || d !== w) { E.tex[tex].wrapS = THREE.RepeatWrapping; E.tex[tex].needsUpdate = true; }
  const m = E.prop(g, { map: E.tex[tex], glow: o.glow ?? 1.15, shadow: o.shadow === true }); m.position.set(x, o.y || 0, z); m.rotation.y = o.ry || 0; return m; }
export function beams(E, list) { // searchlights sweeping the sky: [x, z, colour, phase]
  const L = list.map(([x, z, c, ph]) => { const b = E.card(E.tex.beam, { x, z, y: 0, w: 26, h: 420, tint: c, add: true, fog: 0, blur: 0, op: 0.2, order: -600 }); b.ph = ph; return b; });
  for (const b of L) { const u0 = b.update.bind(b); b.update = (cam, t) => { u0(cam, t); b.m.rotateZ(0.5 * Math.sin(t * 0.45 + b.ph)); }; } return { L, set(k) { for (const b of L) b.u.uOpacity.value = 0.2 * k; } }; }
export function neonSign(E, x, y, z, w, h, text, o = {}) { // letters that come on one after another: set(n) lights the first n
  const draw = n => (g, W, H) => { g.fillStyle = '#0c0a16'; g.fillRect(0, 0, W, H); g.textAlign = 'center'; g.textBaseline = 'middle'; g.font = `${o.weight || 400} ${H * (o.size || 0.78)}px ${o.font || 'Bebas'}`; const tw = g.measureText(text).width; let cx = W / 2 - tw / 2; g.textAlign = 'left';
    for (let i = 0; i < text.length; i++) { const ch = text[i], cw = g.measureText(ch).width; const on = i < n; g.shadowColor = on ? (o.col || '#ff5aa8') : 'transparent'; g.shadowBlur = on ? H * 0.22 : 0; g.fillStyle = on ? (o.core || '#fff2f8') : '#2a2438'; g.fillText(ch, cx, H * 0.54); if (on) g.fillText(ch, cx, H * 0.54); cx += cw; } g.shadowBlur = 0; };
  const tex = canvasTex(o.tw || 2048, Math.round((o.tw || 2048) * h / w), draw(0)); const m = E.prop(new THREE.PlaneGeometry(w, h), { map: tex, shadow: false, side: THREE.DoubleSide, emis: '#000000' }); m.position.set(x, y, z); m.rotation.y = o.ry ?? -Math.PI / 2;
  m.material.uniforms.uColor.value.set('#000000'); const emisT = m.material.uniforms.uEmis; let cur = -1; const gl = glow(E, x - 1, y, z, w * 0.9, o.col || '#ff5aa8', { op: 0, order: 45 });
  // drawn by its own light only: the board's colour is its texture, added as light
  m.material = new THREE.MeshBasicMaterial({ map: tex, side: THREE.DoubleSide, fog: false, toneMapped: false }); if (o.legs !== false) for (const s of [-1, 1]) { const l = box(E, 0.5, y, 0.5, { color: '#2a2a34', shadow: false }); l.position.set(x + 0.3, 0, z + s * w * 0.4); }
  return { m, set(n) { n = Math.round(clamp(n, 0, text.length)); if (n !== cur) { cur = n; redraw(tex, draw(n)); } gl.u.uOpacity.value = 0.55 * n / text.length; }, n: text.length }; }
export function viceCity(E, x0, o = {}) { // everything beyond the gate: palms, hotel fronts, towers behind towers, water, searchlights
  const R = rng(o.seed || 77); const out = { b: [], glows: [] }; const len = o.len || 260;
  // the boulevard: hotel fronts on both sides, stepping back with distance
  let i = 0; for (const sd of [-1, 1]) { let x = x0 + 16 + R() * 8; while (x < x0 + len) { const w = 20 + R() * 14, h = 15 + R() * 20 + (x - x0) * 0.05, d = 14; const z = sd * (19 + R() * 4 + d / 2);
      out.b.push(building(E, 'deco' + (i++ % 8), x + w / 2, z, w, h, d, { ry: 0 })); const c = ['#ff5aa8', '#4ae8ff', '#ffc84a', '#b07aff', '#5affb0', '#ff7a4a'][i % 6];
      out.glows.push(glow(E, x + w / 2, 2.2, sd * 17.5, 16, c, { op: 0.28 })); x += w + 1.5 + R() * 5; } }
  // palms along both kerbs, lit from below
  for (let x = x0 + 8; x < x0 + len; x += 9 + R() * 3) for (const sd of [-1, 1]) { const t = tree(E, 'palm', x + R() * 2, sd * (7.6 + R() * 0.8), { R, h: 9 + R() * 4, tint: '#ffd0e0', tint2: '#6a4a8a', shadow: false }); if (R() < 0.5) out.glows.push(glow(E, x, 0.6, sd * 7.6, 5, '#ff8ad0', { op: 0.22 })); }
  for (let x = x0 + 14; x < x0 + len; x += 26) for (const sd of [-1, 1]) lamp(E, x, sd * 6.9, { col: sd > 0 ? '#ffb0d8' : '#a0e8ff', pool: 0.28 });
  // towers standing behind the fronts, then two far shores of skyline
  for (let k = 0; k < (o.towers ?? 22); k++) { const sd = k % 2 ? 1 : -1; const w = 22 + R() * 16, h = 70 + R() * 130; out.b.push(building(E, 'tower' + (k % 4), x0 + 40 + R() * (len + 260), sd * (52 + R() * 150), w, h, w, { rep: 1, glow: 1.0 })); }
  out.mid = E.card(E.tex.skymid, { x: x0 + len + 420, z: 0, y: -4, w: 2400, h: 2400 * 0.25, ry: -Math.PI / 2, tint: '#ffffff', tint2: '#d0c0ff', fog: 0.25, blur: 0, order: -770 });
  out.far = E.card(E.tex.skyfar, { x: x0 + len + 900, z: 0, y: -6, w: 3600, h: 3600 * 0.25, ry: -Math.PI / 2, tint: '#e8d8ff', tint2: '#a090e0', fog: 0.45, blur: 0, order: -780 });
  out.glow = glow(E, x0 + len + 700, 120, 0, 2600, o.glow || '#ff7aa0', { order: -790, op: 0.5 });
  // the bay: water beyond the last fronts, carrying the lights
  const wt = E.tex.water; wt.wrapS = wt.wrapT = THREE.RepeatWrapping; for (const sd of [-1, 1]) { const g = new THREE.PlaneGeometry(1400, 900).rotateX(-Math.PI / 2); const uv = g.getAttribute('uv'); for (let q = 0; q < uv.count; q++) uv.setXY(q, uv.getX(q) * 9, uv.getY(q) * 6);
    const m = E.prop(g, { map: wt, shadow: false, emis: '#0a1030' }); m.position.set(x0 + len + 500, 0.05, sd * 520); out.b.push(m); }
  out.beams = beams(E, [[x0 + 150, -70, '#ff9ad0', 0], [x0 + 210, 80, '#9ae8ff', 2.1], [x0 + 320, -40, '#ffe09a', 4.0], [x0 + 400, 120, '#c8a0ff', 1.2]]);
  // lights of cars far down the boulevard
  const cars = []; for (let k = 0; k < 14; k++) { const g = glow(E, 0, 0.7, 0, 1.4, k % 2 ? '#ff5a4a' : '#fff0c8', { op: 0.9 }); g.q = [x0 + 60 + R() * len, (k % 2 ? 1 : -1) * 1.6, (k % 2 ? 1 : -1) * (9 + R() * 6)]; cars.push(g); }
  E.updaters.push(t => { for (const c of cars) { const x = c.q[0] + ((t * c.q[2]) % 200 + 200) % 200; c.m.position.set(x, 0.7, c.q[1]); } });
  return out; }
// ---------- a room, for memories ----------
export function blob(E, x, z, s = 1.2, op = 0.5) { const t = E._blobTex || (E._blobTex = (() => { const tt = canvasTex(64, 64, (g) => { const gr = g.createRadialGradient(32, 32, 2, 32, 32, 31); gr.addColorStop(0, 'rgba(10,8,20,0.9)'); gr.addColorStop(1, 'rgba(10,8,20,0)'); g.fillStyle = gr; g.fillRect(0, 0, 64, 64); }); tt.premultiplyAlpha = true; return tt; })());
  return E.card(t, { x, y: 0.012, z, w: s, h: s, ry: 0, rx: -Math.PI / 2, center: true, fog: 0, blur: 0, op }); }
export function room(E, o = {}) { // a bedroom facing a television: x is across the room, the TV stands at -z, the bed along +z
  const W = o.w || 5.2, D = o.d || 4.6, Hh = 2.7; const wall = o.wall || '#6c7aa8', out = {};
  const wallTex = (w, draw) => canvasTex(1024, Math.round(1024 * Hh / w), (g, cw, ch) => { g.fillStyle = wall; g.fillRect(0, 0, cw, ch); const gr = g.createLinearGradient(0, 0, 0, ch); gr.addColorStop(0, 'rgba(0,0,0,0.25)'); gr.addColorStop(0.5, 'rgba(0,0,0,0)'); gr.addColorStop(1, 'rgba(0,0,0,0.3)'); g.fillStyle = gr; g.fillRect(0, 0, cw, ch); g.fillStyle = 'rgba(255,255,255,0.08)'; g.fillRect(0, ch * 0.9, cw, ch * 0.1); if (draw) draw(g, cw, ch); });
  const fl = E.prop(new THREE.PlaneGeometry(W + 2, D + 2).rotateX(-Math.PI / 2), { shadow: false, map: canvasTex(512, 512, (g, w, h) => { for (let i = 0; i < 8; i++) { g.fillStyle = i % 2 ? '#7a5a44' : '#84624a'; g.fillRect(i * 64, 0, 64, h); } g.strokeStyle = 'rgba(0,0,0,0.25)'; for (let i = 0; i < 8; i++) { g.strokeRect(i * 64, (i * 97) % 300, 64, 300); } }) }); fl.position.set(0, 0, 0);
  const poster = (g, x, y, w, h, c1, c2, txt) => { g.fillStyle = c1; g.fillRect(x, y, w, h); g.fillStyle = c2; g.beginPath(); g.moveTo(x, y + h); g.lineTo(x + w * 0.5, y + h * 0.35); g.lineTo(x + w, y + h); g.fill(); g.fillStyle = '#fff'; g.font = `700 ${h * 0.16}px Oswald`; g.textAlign = 'center'; g.fillText(txt, x + w / 2, y + h * 0.24); g.strokeStyle = 'rgba(0,0,0,0.4)'; g.lineWidth = 3; g.strokeRect(x, y, w, h); };
  const back = E.prop(new THREE.PlaneGeometry(W, Hh), { shadow: false, map: wallTex(W, (g, cw, ch) => { (o.posters || [['#e8a030', '#c8402c', 'SPEED'], ['#3a6ad8', '#1c2c5c', 'CITY']]).forEach((p, i) => poster(g, cw * (0.08 + i * 0.74), ch * 0.18, cw * 0.16, ch * 0.34, p[0], p[1], p[2])); }) }); back.position.set(0, Hh / 2, -D / 2);
  const left = E.prop(new THREE.PlaneGeometry(D, Hh), { shadow: false, map: wallTex(D, (g, cw, ch) => { g.fillStyle = o.night === false ? '#ffd9a0' : '#1a2a5a'; g.fillRect(cw * 0.3, ch * 0.2, cw * 0.36, ch * 0.42); g.strokeStyle = '#e8e2d4'; g.lineWidth = 14; g.strokeRect(cw * 0.3, ch * 0.2, cw * 0.36, ch * 0.42); g.beginPath(); g.moveTo(cw * 0.48, ch * 0.2); g.lineTo(cw * 0.48, ch * 0.62); g.stroke(); }) }); left.position.set(-W / 2, Hh / 2, 0); left.rotation.y = Math.PI / 2;
  const right = E.prop(new THREE.PlaneGeometry(D, Hh), { shadow: false, map: wallTex(D, (g, cw, ch) => { g.fillStyle = '#4a3a30'; g.fillRect(cw * 0.55, ch * 0.3, cw * 0.3, ch * 0.7); g.fillStyle = '#e8c070'; g.beginPath(); g.arc(cw * 0.6, ch * 0.68, 8, 0, 7); g.fill(); }) }); right.position.set(W / 2, Hh / 2, 0); right.rotation.y = -Math.PI / 2;
  // the bed they sit on (its edge faces the TV), a rug, a stand, the television
  const bh = o.bed ?? 0.42; const bed = box(E, 2.3, bh, 1.9, { color: o.blanket || '#c8503c', shadow: false }); bed.position.set(0, 0, 0.95 + (o.bedz || 0)); const pil = box(E, 0.7, 0.14, 0.42, { color: '#f0e8dc', shadow: false }); pil.position.set(-0.6, bh, 1.6 + (o.bedz || 0));
  const rug = E.prop(new THREE.PlaneGeometry(2.6, 1.5).rotateX(-Math.PI / 2), { shadow: false, color: o.rug || '#3c6a7a' }); rug.position.set(0, 0.008, -0.75);
  const st = box(E, 1.5, 0.5, 0.45, { color: '#3a2c26', shadow: false }); st.position.set(0, 0, -D / 2 + 0.35);
  const tvw = o.tvw || 1.15, tvh = tvw * 0.6; const body = box(E, tvw + 0.08, tvh + 0.08, 0.07, { color: '#15151a', shadow: false }); body.position.set(0, 0.52, -D / 2 + 0.36);
  const stex = canvasTex(512, 300, o.screen ? o.screen(0) : (g, w, h) => { g.fillStyle = '#204080'; g.fillRect(0, 0, w, h); }); const scr = new THREE.Mesh(new THREE.PlaneGeometry(tvw, tvh), new THREE.MeshBasicMaterial({ map: stex, toneMapped: false })); scr.position.set(0, 0.52 + tvh / 2 + 0.04, -D / 2 + 0.40); E.scene.add(scr);
  const con = box(E, 0.34, 0.07, 0.26, { color: o.console || '#18181c', shadow: false }); con.position.set(0.45, 0.5, -D / 2 + 0.36);
  out.tvGlow = glow(E, 0, 0.9, -D / 2 + 0.6, 4.2, '#7aa8ff', { op: 0.35 }); out.lampGlow = glow(E, W / 2 - 0.5, 1.5, D / 2 - 0.6, 3.2, '#ffc880', { op: 0.3 }); const ls = box(E, 0.06, 1.3, 0.06, { color: '#2a2a2a', shadow: false }); ls.position.set(W / 2 - 0.5, 0, D / 2 - 0.6);
  const fw = E.prop(new THREE.PlaneGeometry(W, Hh), { shadow: false, map: wallTex(W, (g, cw, ch) => { g.fillStyle = '#3a2c26'; g.fillRect(cw * 0.62, ch * 0.12, cw * 0.3, ch * 0.88); for (let i = 0; i < 4; i++) { g.fillStyle = '#2a1e1a'; g.fillRect(cw * 0.63, ch * (0.16 + i * 0.2), cw * 0.28, ch * 0.015); for (let j = 0; j < 9; j++) { g.fillStyle = ['#c84a3a', '#3a6ad8', '#e8b040', '#4aa870', '#e8e0d0'][(i * 3 + j) % 5]; g.fillRect(cw * (0.635 + j * 0.03), ch * (0.175 + i * 0.2) + (j % 3) * 4, cw * 0.022, ch * 0.16 - (j % 3) * 4); } } }) }); fw.position.set(0, Hh / 2, D / 2); fw.rotation.y = Math.PI;
  const ceil = E.prop(new THREE.PlaneGeometry(W + 0.2, D + 0.2).rotateX(Math.PI / 2), { shadow: false, color: '#b8b4c8' }); ceil.position.set(0, Hh, 0);
  out.bedMesh = bed; out.pillow = pil; out.setBed = (h, colr) => { bed.scale.y = h / bh; pil.position.y = h; if (colr) bed.material.uniforms.uColor.value.set(colr); };
  out.screen = { tex: stex, mesh: scr, draw(fn) { redraw(stex, fn); } }; out.W = W; out.D = D; out.bed = bh; out.seatZ = 0.95 + (o.bedz || 0) - 0.95 + 0.12; return out; }
// the game on the screen: our own little top-down drive through a night city (no real game's picture)
export function gameScreen(t, o = {}) { return (g, w, h) => { const sky = g.createLinearGradient(0, 0, 0, h); sky.addColorStop(0, o.a || '#2a1a5a'); sky.addColorStop(0.55, o.b || '#ff7a8a'); sky.addColorStop(0.56, '#1a1a2a'); sky.addColorStop(1, '#0c0c18'); g.fillStyle = sky; g.fillRect(0, 0, w, h);
  g.fillStyle = '#ffd27a'; g.beginPath(); g.arc(w * 0.5, h * 0.5, h * 0.16, Math.PI, 0); g.fill(); const R = rng(5); g.fillStyle = '#16102e'; for (let i = 0; i < 16; i++) { const bw = 18 + R() * 30, bh = 20 + R() * 80, x = ((i * 40 - t * 12) % (w + 60) + w + 60) % (w + 60) - 30; g.fillRect(x, h * 0.56 - bh, bw, bh); }
  g.fillStyle = '#22222e'; g.beginPath(); g.moveTo(w * 0.42, h * 0.56); g.lineTo(w * 0.58, h * 0.56); g.lineTo(w * 0.98, h); g.lineTo(w * 0.02, h); g.fill(); g.fillStyle = '#f0e8c0'; for (let i = 0; i < 6; i++) { const u = ((i / 6 + t * 0.9) % 1); const y = h * (0.56 + 0.44 * u * u), ww = 2 + 10 * u; g.fillRect(w / 2 - ww / 2, y, ww, 4 + 18 * u); }
  const cx = w * (0.5 + 0.13 * Math.sin(t * 1.7)), cy = h * 0.83; g.fillStyle = o.car || '#e8402c'; g.fillRect(cx - 34, cy - 14, 68, 30); g.fillStyle = '#1a1a22'; g.fillRect(cx - 24, cy - 26, 48, 14); g.fillStyle = '#ff3a3a'; g.fillRect(cx - 32, cy + 6, 12, 6); g.fillRect(cx + 20, cy + 6, 12, 6);
  g.fillStyle = '#ffffff'; g.font = '700 46px Oswald'; g.textAlign = 'left'; g.fillText(o.label ?? 'GTA V', 16, 50); g.font = '500 18px Oswald'; g.fillStyle = '#9affc0'; g.fillText('$ ' + String(1250 + Math.floor(t * 37) * 10), w - 110, 34); }; }
