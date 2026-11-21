// PROPS — the small made things a story touches: a slip of paper with handwriting, a steel tiffin, an office bag, a ticket punch, a pen.
import { THREE, mat, ball, limb, rbox, tube, mesh, group, paintTex, V3, lerp, clamp, hash } from './clay.js';
/** THE NOTE: a slip torn from an old ticket book, green ink, a star punched in its corner. write: 0…1 how much of it is written */
export const SLIP = { W: 0.17, H: 0.15 };
export function makeSlip(lines, o = {}) {
  const W = SLIP.W, Hh = SLIP.H, cv = { w: 680, h: 600 }; let last = -1;
  const draw = (g, w, h, k) => { g.clearRect(0, 0, w, h); g.fillStyle = '#efe6cd'; g.fillRect(0, 0, w, h); const gr = g.createLinearGradient(0, 0, w, h); gr.addColorStop(0, 'rgba(255,255,255,0.25)'); gr.addColorStop(1, 'rgba(120,90,40,0.16)'); g.fillStyle = gr; g.fillRect(0, 0, w, h);
    g.strokeStyle = 'rgba(70,110,150,0.35)'; g.lineWidth = 1.5; for (let i = 1; i <= 3; i++) { g.beginPath(); g.moveTo(26, h * (0.19 + i * 0.235)); g.lineTo(w - 26, h * (0.19 + i * 0.235)); g.stroke(); }
    g.fillStyle = 'rgba(150,60,50,0.55)'; g.font = '600 17px Poppins'; g.fillText('M.R.  No. 0047', 28, 34); for (let i = 0; i < 30; i++) { g.fillStyle = 'rgba(90,70,40,0.5)'; g.beginPath(); g.arc(8, 14 + i * 13, 2.2, 0, 7); g.fill(); }
    // the writing appears as it is read: line by line, left to right
    g.save(); g.fillStyle = '#0f6a3a'; g.font = `700 ${o.size ?? 74}px Kalam`; g.textBaseline = 'alphabetic'; const n = lines.length, lens = lines.map((l) => Math.max(2, l.length)), tot = lens.reduce((a, b) => a + b, 0); let acc = 0; lines.forEach((ln, i) => { const a = clamp((k * tot - acc) / lens[i]); acc += lens[i]; if (a <= 0) return; g.save(); const y = h * (0.19 + (i + 1) * 0.235) - 9, tw = g.measureText(ln).width, x = ln.startsWith('—') ? w - tw - 70 : 44; g.beginPath(); g.rect(x - 8, y - 84, (tw + 16) * a, 120); g.clip(); g.fillText(ln, x, y); g.restore(); }); g.restore();
    // the punched star
    g.globalCompositeOperation = 'destination-out'; g.beginPath(); for (let i = 0; i < 10; i++) { const a = i / 10 * 6.283 - 1.57, r = i % 2 ? 9 : 21; g.lineTo(w - 46 + Math.cos(a) * r, 44 + Math.sin(a) * r); } g.fill(); g.globalCompositeOperation = 'source-over'; };
  const tex = paintTex(cv.w, cv.h, (g, w, h) => draw(g, w, h, o.write ?? 1)), geo = new THREE.PlaneGeometry(W, Hh, 12, 6), pos = geo.attributes.position;
  for (let i = 0; i < pos.count; i++) { const x = pos.getX(i) / W, y = pos.getY(i) / Hh; pos.setZ(i, 0.006 * Math.sin(x * 3.1 + 0.4) * (0.5 + y) + 0.004 * x * x); } geo.computeVertexNormals();
  const m = mat('#ffffff', 'paper', { map: tex, alphaTest: 0.5, fuzz: 0.05 }), slip = mesh(geo, m);
  // its back: bare paper, the star hole, the ink just showing through
  const bt = paintTex(170, 150, (g, w, h) => { g.fillStyle = '#e4dabd'; g.fillRect(0, 0, w, h); g.fillStyle = 'rgba(40,110,70,0.10)'; for (let i = 0; i < 3; i++) g.fillRect(24, h * (0.3 + i * 0.235), w * (0.5 + 0.3 * hash(i)), 9); g.globalCompositeOperation = 'destination-out'; g.beginPath(); for (let i = 0; i < 10; i++) { const a2 = i / 10 * 6.283 - 1.57, r = i % 2 ? 2.4 : 5.4; g.lineTo(158.5 + Math.cos(a2) * r, 11 + Math.sin(a2) * r); } g.fill(); });
  slip.add(mesh(geo, mat('#ffffff', 'paper', { map: bt, side: THREE.BackSide, alphaTest: 0.5 }), { cast: false })); slip.userData.write = (k) => { const q = Math.round(k * 60) / 60; if (q === last) return; last = q; const c = tex.userData.canvas; draw(c.getContext('2d'), c.width, c.height, q); tex.needsUpdate = true; };
  return slip;
}
/** the steel tiffin: two tiers, a clip, a wire handle; open: the lid off and the lunch nobody ate */
export function makeTiffin(o = {}) {
  const tin = mat('#c3cad2', 'tin'), g = new THREE.Group(), r = 0.062;
  for (let i = 0; i < 2; i++) g.add(mesh(new THREE.CylinderGeometry(r, r * 0.97, 0.058, 28), tin, { p: [0, 0.03 + i * 0.062, 0] }), mesh(new THREE.TorusGeometry(r, 0.004, 6, 28), tin, { p: [0, 0.06 + i * 0.062, 0], r: [Math.PI / 2, 0, 0] }));
  const lid = group([mesh(new THREE.CylinderGeometry(r * 0.96, r * 1.01, 0.014, 28), tin, { p: [0, 0.007, 0] }), mesh(ball(r * 0.6, 0.012, r * 0.6), tin, { p: [0, 0.014, 0] })], { p: [0, 0.126, 0] }); g.add(lid); g.userData.lid = lid;
  for (const s of [1, -1]) g.add(mesh(rbox(0.006, 0.15, 0.016, 0.002), tin, { p: [s * (r + 0.004), 0.075, 0] })); const hd = mesh(tube([[-(r + 0.004), 0.15, 0], [-0.03, 0.185, 0], [0.03, 0.185, 0], [r + 0.004, 0.15, 0]], 0.0035, 14, 6), tin); g.add(hd); g.userData.handle = hd;
  // lunch: two rotis folded, a yellow sabzi, a red pickle
  const food = new THREE.Group(); food.add(mesh(new THREE.CylinderGeometry(r * 0.9, r * 0.9, 0.004, 24), mat('#3a3f47', 'tin'), { p: [0, 0.112, 0] })); food.add(mesh(ball(r * 0.62, 0.008, r * 0.5), mat('#d9b27a', 'clay', { mottle: 0.4 }), { p: [-0.012, 0.118, 0.012], r: [0, 0.3, 0.05] }), mesh(ball(r * 0.5, 0.007, r * 0.44), mat('#cfa368', 'clay', { mottle: 0.4 }), { p: [-0.006, 0.124, 0.004], r: [0, -0.5, -0.06] }));
  for (let i = 0; i < 7; i++) food.add(mesh(ball(0.011 + hash(i) * 0.005), mat(i % 3 ? '#d9a02a' : '#e8b848', 'clay'), { p: [0.026 + (hash(i * 3) - 0.5) * 0.03, 0.12, -0.022 + (hash(i * 7) - 0.5) * 0.034] })); food.add(mesh(ball(0.009), mat('#a8281e', 'clay', { rough: 0.3 }), { p: [0.04, 0.122, 0.022] })); g.add(food); g.userData.food = food;
  return g;
}
/**
 * His office bag: dark felt, a zip along the top, two handles, a front pocket. It opens the way a bag does:
 * .userData.zip(k) runs the slider along (0 shut … 1 open), .userData.gape(k) pulls the mouth apart (0 … 1),
 * .userData.slider() → where the slider is (in the bag's own frame), .userData.TOP its top, .userData.inside its floor.
 */
export function makeBag() {
  const felt = mat('#252b3a', 'felt', { fuzz: 0.4 }), felt2 = mat('#2e3548', 'felt', { fuzz: 0.4 }), tin = mat('#aab0ba', 'tin'), g = new THREE.Group(), L = 0.17, TOP = 0.292;
  g.add(mesh(rbox(0.34, 0.2, 0.15, 0.035), felt, { p: [0, 0.135, 0] }), mesh(new THREE.BoxGeometry(0.36, 0.004, 0.13), mat('#06070b', 'felt', { fuzz: 0 }), { p: [0, 0.272, 0], cast: false }));
  // the mouth: two flaps that meet at the zip
  const flaps = [1, -1].map((sd) => { const h = group([mesh(rbox(0.37, 0.006, 0.064, 0.004), felt2, { p: [0, 0, -sd * 0.036] })], { p: [0, TOP, sd * 0.075] }); g.add(h); return h; });
  for (const sd of [1, -1]) g.add(mesh(rbox(0.37, 0.02, 0.006, 0.004), felt2, { p: [0, 0.28, sd * 0.079] }));
  const teeth = mesh(new THREE.BoxGeometry(1, 0.004, 0.012), tin, { p: [0, TOP + 0.006, 0], cast: false }); g.add(teeth);
  const slider = group([mesh(rbox(0.014, 0.008, 0.016, 0.003), tin), mesh(rbox(0.008, 0.03, 0.003, 0.002), tin, { p: [0.012, -0.008, 0.012], r: [0.5, 0, 1.2] })], { p: [L, TOP + 0.01, 0] }); g.add(slider);
  for (const sd of [1, -1]) g.add(mesh(tube([[-0.09, 0.275, sd * 0.082], [-0.075, 0.355, sd * 0.094], [0.075, 0.355, sd * 0.094], [0.09, 0.275, sd * 0.082]], 0.011, 18, 6), felt));
  g.add(mesh(rbox(0.2, 0.09, 0.012, 0.01), felt2, { p: [0, 0.11, 0.078] }), mesh(new THREE.BoxGeometry(0.17, 0.004, 0.004), tin, { p: [0, 0.155, 0.087], cast: false }), mesh(rbox(0.045, 0.026, 0.006, 0.003), mat('#b03a2a', 'felt'), { p: [0.13, 0.07, 0.078] }));
  let Z = 0; const zip = (k) => { Z = clamp(k); const x = lerp(L, -L, Z); slider.position.x = x; teeth.scale.x = Math.max(0.001, x + L); teeth.position.x = (x - L) / 2; teeth.visible = Z < 0.995; };   // the slider is pulled from the far end (+x) towards −x
  const gape = (k) => { flaps.forEach((h, i) => { h.rotation.x = (i ? -1 : 1) * clamp(k) * 1.15; }); };
  zip(0); gape(0); Object.assign(g.userData, { zip, gape, TOP, inside: 0.274, slider: () => [slider.position.x, slider.position.y, 0], L });
  return g;
}
/** the conductor's ticket punch: tin pliers with a star die, on a ball chain */
export function makePunch() {
  const tin = mat('#b9c0c8', 'tin'), brass = mat('#c9a03a', 'tin', { rough: 0.3 }), g = new THREE.Group(), sw = new THREE.Group(); g.add(sw);
  for (let i = 0; i < 7; i++) sw.add(mesh(ball(0.0048), tin, { p: [0, -0.008 - i * 0.011, 0] }));
  const body = group([], { p: [0, -0.09, 0] }); sw.add(body);
  body.add(mesh(rbox(0.016, 0.085, 0.01, 0.004), tin, { p: [-0.012, -0.055, 0], r: [0, 0, -0.14] }), mesh(rbox(0.016, 0.085, 0.01, 0.004), tin, { p: [0.012, -0.055, 0], r: [0, 0, 0.14] }), mesh(ball(0.007), brass, { p: [0, -0.012, 0.004] }));
  const st = new THREE.Shape(); for (let i = 0; i < 10; i++) { const a = i / 10 * Math.PI * 2 + Math.PI / 2, r = i % 2 ? 0.0085 : 0.02; i ? st.lineTo(Math.cos(a) * r, Math.sin(a) * r) : st.moveTo(Math.cos(a) * r, Math.sin(a) * r); } st.closePath();
  body.add(mesh(new THREE.CylinderGeometry(0.026, 0.026, 0.008, 24), tin, { p: [0, 0.012, 0], r: [Math.PI / 2, 0, 0] }), mesh(new THREE.ExtrudeGeometry(st, { depth: 0.005, bevelEnabled: false }), brass, { p: [0, 0.012, 0.0045] }));
  g.userData.swing = sw; return g;
}
/** a green ink pen with a clip */
export function makePen() { const g = new THREE.Group(), gr = mat('#178a4c', 'plastic', { rough: 0.25 }); g.add(mesh(limb(0.0075, 0.1, 0.006, 10), gr), mesh(rbox(0.004, 0.045, 0.004, 0.0015), mat('#c9ced6', 'tin'), { p: [0, -0.024, 0.008] }), mesh(ball(0.0078), gr)); return g; }
