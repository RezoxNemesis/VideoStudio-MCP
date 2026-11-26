// Drafts — the tiny post office inside a phone. Every model, texture and motion here is made in code.
import { THREE, clamp, lerp, sm, sm2, seg, rng } from './engine.js';
const C = h => new THREE.Color(h);

export async function init(E) {
  for (const [name, file] of [['Kalam', 'Kalam-Bold.ttf'], ['PoppinsB', 'Poppins-Bold.ttf'], ['PoppinsM', 'Poppins-Medium.ttf'], ['Baloo', 'Baloo2.ttf']]) {
    try { const b = await (await fetch('fonts/' + file)).arrayBuffer(); const f = new FontFace(name, b); await f.load(); document.fonts.add(f); } catch (e) { }
  }
}

// ------------------------------------------------------------------------------------------------ textures
export function canvasTex(w, h, draw, srgb = true) { const c = document.createElement('canvas'); c.width = w; c.height = h; draw(c.getContext('2d'), w, h); const t = new THREE.CanvasTexture(c); if (srgb) t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 8; return t; }
function grain(g, w, h, n, a, seed) { const R = rng(seed); for (let i = 0; i < n; i++) { const v = R() < 0.5 ? 0 : 255; g.fillStyle = `rgba(${v},${v},${v},${a * R()})`; g.fillRect(R() * w, R() * h, 1 + R() * 2, 1 + R() * 2); } }
export const woodTex = (seed = 1, base = '#6b4326') => { const t = canvasTex(512, 512, (g, w, h) => { const R = rng(seed); g.fillStyle = base; g.fillRect(0, 0, w, h);
  for (let i = 0; i < 90; i++) { const y = R() * h, a = 0.05 + R() * 0.12; g.strokeStyle = `rgba(${R() < 0.5 ? '30,15,5' : '160,110,60'},${a})`; g.lineWidth = 1 + R() * 4; g.beginPath(); g.moveTo(0, y); for (let x = 0; x <= w; x += 16) g.lineTo(x, y + Math.sin(x / 60 + i) * 5 + (R() - 0.5) * 2); g.stroke(); }
  grain(g, w, h, 6000, 0.08, seed + 9); }); t.wrapS = t.wrapT = THREE.RepeatWrapping; return t; };
export const paperTex = (seed = 2, base = '#efe4cc') => { const t = canvasTex(256, 256, (g, w, h) => { g.fillStyle = base; g.fillRect(0, 0, w, h); grain(g, w, h, 5000, 0.07, seed); }); t.wrapS = t.wrapT = THREE.RepeatWrapping; return t; };
export function envelopeTex(o) { // {to, msg, date, stamp, base, dust, ink}
  return canvasTex(512, 320, (g, w, h) => {
    g.fillStyle = o.base || '#efe3c8'; g.fillRect(0, 0, w, h); grain(g, w, h, 4000, 0.08, (o.seed || 3));
    // flap lines
    g.strokeStyle = 'rgba(80,60,40,0.25)'; g.lineWidth = 3; g.beginPath(); g.moveTo(0, 0); g.lineTo(w / 2, h * 0.52); g.lineTo(w, 0); g.stroke();
    if (o.airmail) { for (let x = -40; x < w + 40; x += 40) { g.fillStyle = (x / 40) % 2 ? '#c8402c' : '#2a5fb0'; g.beginPath(); g.moveTo(x, 0); g.lineTo(x + 20, 0); g.lineTo(x + 8, 12); g.lineTo(x - 12, 12); g.fill(); g.beginPath(); g.moveTo(x, h); g.lineTo(x + 20, h); g.lineTo(x + 8, h - 12); g.lineTo(x - 12, h - 12); g.fill(); } }
    // stamp
    g.fillStyle = o.stampCol || '#c8402c'; g.fillRect(w - 92, 22, 64, 76); g.strokeStyle = '#fff6e6'; g.lineWidth = 4; g.setLineDash([5, 4]); g.strokeRect(w - 88, 26, 56, 68); g.setLineDash([]);
    g.fillStyle = '#fff6e6'; g.font = 'bold 30px PoppinsB'; g.textAlign = 'center'; g.fillText('♥', w - 60, 72);
    g.fillStyle = o.ink || '#20304a'; g.textAlign = 'left';
    g.font = '30px PoppinsM'; g.globalAlpha = 0.6; g.fillText('To:', 40, 150); g.globalAlpha = 1;
    g.font = 'bold 64px Kalam'; g.fillText(o.to || '', 40, 212);
    if (o.msg) { g.font = 'bold 34px Kalam'; g.globalAlpha = 0.85; g.fillText(o.msg, 44, 262); g.globalAlpha = 1; }
    if (o.date) { g.font = '24px PoppinsM'; g.globalAlpha = 0.55; g.fillText(o.date, 40, 300); g.globalAlpha = 1; }
    if (o.notSent) { g.save(); g.translate(w * 0.62, h * 0.66); g.rotate(-0.22); g.strokeStyle = 'rgba(190,40,30,0.85)'; g.lineWidth = 6; g.strokeRect(-110, -34, 220, 62); g.fillStyle = 'rgba(190,40,30,0.85)'; g.font = 'bold 36px PoppinsB'; g.textAlign = 'center'; g.fillText('NOT SENT', 0, 12); g.restore(); }
    if (o.dust) { const R = rng(o.seed || 7); for (let i = 0; i < 2600; i++) { g.fillStyle = `rgba(150,140,125,${0.25 * R() * o.dust})`; g.fillRect(R() * w, R() * h, 2 + R() * 4, 2 + R() * 4); } g.fillStyle = `rgba(160,150,135,${0.35 * o.dust})`; g.fillRect(0, 0, w, h); }
  });
}

// ------------------------------------------------------------------------------------------------ geometry helpers
export const std = (o) => new THREE.MeshStandardMaterial(o);
export function box(w, h, d, mat, x = 0, y = 0, z = 0, parent) { const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat); m.position.set(x, y, z); m.castShadow = m.receiveShadow = true; if (parent) parent.add(m); return m; }
export function envelope(tex, w = 0.5, h = 0.31) { // a thin card with the address on the front and plain paper behind
  const g = new THREE.BoxGeometry(w, h, 0.012); const back = std({ color: 0xe9dcc2, roughness: 0.92 });
  const front = std({ map: tex, roughness: 0.88 }); const m = new THREE.Mesh(g, [back, back, back, back, front, back]); m.castShadow = m.receiveShadow = true; return m;
}

// ------------------------------------------------------------------------------------------------ the post office
export function postOffice(E, o = {}) {
  const S = E.scene, R = rng(41), out = { letters: [] };
  const wood = std({ map: woodTex(3, '#6e4528'), roughness: 0.62 }); wood.map.repeat.set(2, 2);
  const darkWood = std({ map: woodTex(5, '#3f2716'), roughness: 0.6 });
  // floor: worn wooden boards
  const ft = woodTex(11, '#7a5032'); ft.repeat.set(6, 6);
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(30, 30), std({ map: ft, roughness: 0.55, metalness: 0.0 })); floor.rotation.x = -Math.PI / 2; floor.receiveShadow = true; S.add(floor);
  // the wall of pigeon-holes (back) and the two side walls, all the way up to the glass
  const CW = 0.62, CH = 0.42, depth = 0.5, top = o.top ?? 12.6;
  const parts = [], lettersM = [];
  const wallAt = (x0, x1, z, ry) => {
    const nx = Math.round((x1 - x0) / CW), ny = Math.round(top / CH);
    const grp = new THREE.Group(); grp.position.set(0, 0, 0);
    const gV = new THREE.BoxGeometry(0.05, top, depth), gH = new THREE.BoxGeometry(x1 - x0, 0.04, depth);
    for (let i = 0; i <= nx; i++) { const m = new THREE.Mesh(gV, wood); m.position.set(x0 + i * CW, top / 2, 0); grp.add(m); }
    for (let j = 0; j <= ny; j++) { const m = new THREE.Mesh(gH, wood); m.position.set((x0 + x1) / 2, j * CH, 0); grp.add(m); }
    const backP = new THREE.Mesh(new THREE.PlaneGeometry(x1 - x0, top), darkWood); backP.position.set((x0 + x1) / 2, top / 2, -depth / 2); grp.add(backP);
    grp.traverse(m => { if (m.isMesh) { m.castShadow = false; m.receiveShadow = true; } });
    grp.rotation.y = ry; grp.position.z = z; S.add(grp);
    for (let i = 0; i < nx; i++) for (let j = 0; j < ny; j++) { const n = 1 + Math.floor(R() * 4) - (R() < 0.12 ? 1 : 0); for (let k = 0; k < n; k++) lettersM.push({ grp, x: x0 + (i + 0.5) * CW + (k - n / 2) * 0.05, y: j * CH + 0.17, lean: (R() - 0.5) * 0.35, c: R(), k }); }
    return grp;
  };
  out.back = wallAt(-7, 7, -5.2, 0);
  out.left = wallAt(-5, 5, 0, Math.PI / 2); out.left.position.x = -7.2;
  out.right = wallAt(-5, 5, 0, -Math.PI / 2); out.right.position.x = 7.2;
  // envelopes standing in every cubby (instanced, many paper colours)
  const eg = new THREE.BoxGeometry(0.4, 0.27, 0.014);
  const em = std({ map: paperTex(5, '#ffffff'), roughness: 0.9 });
  const im = new THREE.InstancedMesh(eg, em, lettersM.length); const ob = new THREE.Object3D(), col = new THREE.Color();
  const pal = ['#efe4cc', '#f4efe4', '#d9c3a0', '#e8d6b8', '#cfe0ef', '#f1d9cf', '#e6e0d0', '#c9b18c'];
  lettersM.forEach((L, i) => { ob.position.set(L.x, L.y, -0.02 - L.k * 0.03); ob.rotation.set(0, 0, 0); ob.rotateY(Math.PI / 2 * 0); ob.rotation.x = -0.12; ob.rotation.z = L.lean * 0.2; ob.updateMatrix();
    const m = new THREE.Matrix4().multiplyMatrices(new THREE.Matrix4().compose(L.grp.position, L.grp.quaternion, new THREE.Vector3(1, 1, 1)), ob.matrix);
    im.setMatrixAt(i, m); im.setColorAt(i, col.set(pal[Math.floor(L.c * pal.length)])); });
  im.receiveShadow = true; S.add(im); out.cubbyLetters = im;
  // the counter
  const counter = new THREE.Group(); S.add(counter); out.counter = counter;
  box(5.2, 1.0, 1.1, wood, 0, 0.5, -0.6, counter); box(5.5, 0.07, 1.3, darkWood, 0, 1.03, -0.6, counter);
  for (let i = -2; i <= 2; i++) box(0.9, 0.7, 0.03, darkWood, i * 1.0, 0.5, -0.04, counter);
  // brass bell, ink pad, stacks of envelopes on the counter
  const brass = std({ color: 0xc9a046, metalness: 1, roughness: 0.28 });
  const bell = new THREE.Mesh(new THREE.SphereGeometry(0.09, 20, 10, 0, Math.PI * 2, 0, Math.PI / 2), brass); bell.position.set(-1.9, 1.07, -0.35); bell.castShadow = true; counter.add(bell);
  const pad = box(0.26, 0.04, 0.18, std({ color: 0x24242c, roughness: 0.5 }), 1.1, 1.08, -0.4, counter); box(0.22, 0.01, 0.14, std({ color: 0x9a1e1a, roughness: 0.4 }), 1.1, 1.105, -0.4, counter);
  for (let s = 0; s < 3; s++) { const n = 6 + s * 4; for (let k = 0; k < n; k++) { const e = new THREE.Mesh(new THREE.BoxGeometry(0.42, 0.012, 0.28), std({ color: C(pal[(k + s) % pal.length]), roughness: 0.9 })); e.position.set(-1.25 + s * 0.48 + (R() - 0.5) * 0.03, 1.075 + k * 0.014, -0.75 + (R() - 0.5) * 0.03); e.rotation.y = (R() - 0.5) * 0.3; e.castShadow = e.receiveShadow = true; counter.add(e); } }
  // sign above the counter
  const signT = canvasTex(1024, 300, (g, w, h) => { g.fillStyle = '#2a1a10'; g.fillRect(0, 0, w, h); g.strokeStyle = '#c9a046'; g.lineWidth = 10; g.strokeRect(14, 14, w - 28, h - 28);
    g.fillStyle = '#f2c86a'; g.font = 'bold 150px PoppinsB'; g.textAlign = 'center'; g.fillText('DRAFTS', w / 2, 175); g.font = '38px PoppinsM'; g.fillStyle = '#d9b46a'; g.fillText('POST OFFICE  ·  NEVER SENT, NEVER LOST', w / 2, 248); });
  const sign = new THREE.Mesh(new THREE.BoxGeometry(3.4, 1.0, 0.08), [darkWood, darkWood, darkWood, darkWood, std({ map: signT, roughness: 0.5, emissive: C('#ffb54a'), emissiveMap: signT, emissiveIntensity: 0 }), darkWood]);
  sign.position.set(0, 3.15, -4.6); sign.castShadow = true; S.add(sign); out.sign = sign;
  for (const x of [-1.5, 1.5]) box(0.03, 1.9, 0.03, brass, x, 4.6, -4.6);
  // hanging lamps: warm pools of light (one shadow-casting key)
  out.lamps = [];
  const shadeM = std({ color: 0x1f3a2c, roughness: 0.45, metalness: 0.4, side: THREE.DoubleSide }), bulbM = new THREE.MeshBasicMaterial({ color: C('#fff1c8').multiplyScalar(6) });
  for (const [x, z, key] of [[0, -0.9, true], [-3.4, 2.6, false], [3.6, 2.4, false], [0, 3.6, false]]) {
    const g = new THREE.Group(); g.position.set(x, 4.2, z); S.add(g);
    box(0.012, 8.5, 0.012, std({ color: 0x111111 }), 0, 4.3, 0, g);
    const shade = new THREE.Mesh(new THREE.ConeGeometry(0.42, 0.34, 24, 1, true), shadeM); shade.position.y = 0.0; g.add(shade);
    const bulb = new THREE.Mesh(new THREE.SphereGeometry(0.09, 12, 8), bulbM); bulb.position.y = -0.14; g.add(bulb);
    const L = key ? new THREE.SpotLight(0xffc77a, 120, 14, 0.75, 0.55, 1.6) : new THREE.PointLight(0xffbf70, 22, 9, 1.7);
    L.position.set(x, 4.0, z); if (key) { L.target.position.set(x, 0, z); S.add(L.target); L.castShadow = true; L.shadow.mapSize.set(2048, 2048); L.shadow.bias = -0.0004; L.shadow.radius = 4; }
    S.add(L); out.lamps.push({ g, L });
  }
  // cool screen-light coming down through the glass
  const sky = new THREE.DirectionalLight(0x9fc4ff, 1.1); sky.position.set(1.5, 20, 3); sky.target.position.set(0, 0, -1); S.add(sky, sky.target);
  S.add(new THREE.HemisphereLight(0x8fb2e8, 0x3a2416, 0.35)); out.sky = sky;
  // the glass ceiling: the phone's screen seen from inside, glowing
  out.glass = glassCeiling(E, top + 0.4); S.add(out.glass.mesh);
  // light shafts from the glass
  out.shafts = shafts(S, top + 0.4);
  // dust in the air
  out.dust = dust(S, 900, [-6, 0.2, -4.5], [6, Math.min(9, top), 3.5], 77);
  E.setEnv(s => { const g = new THREE.Mesh(new THREE.SphereGeometry(50, 32, 16), new THREE.ShaderMaterial({ side: THREE.BackSide, vertexShader: 'varying vec3 vP; void main(){ vP = position; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.); }',
    fragmentShader: 'varying vec3 vP; void main(){ vec3 d = normalize(vP); vec3 c = mix(vec3(0.16,0.09,0.05), vec3(0.55,0.36,0.2), smoothstep(-0.4, 0.3, d.y)); c = mix(c, vec3(0.6,0.75,1.1), smoothstep(0.55, 0.95, d.y)); gl_FragColor = vec4(c, 1.); }' })); s.add(g); }, 0.55);
  return out;
}

export function glassCeiling(E, y) {
  const tex = canvasTex(1024, 2048, (g, w, h) => phoneUI(g, w, h, { mirror: true, sendX: 0.5 }));
  const ripple = { value: new THREE.Vector4(0, 0, -10, 0) }; // x, z, start time, strength
  const m = new THREE.ShaderMaterial({ side: THREE.DoubleSide, transparent: true, depthWrite: false,
    uniforms: { map: { value: tex }, uT: { value: 0 }, uR: ripple, uGlow: { value: 1 }, uAlpha: { value: 0.96 } },
    vertexShader: 'varying vec2 vUv; varying vec3 vW; void main(){ vUv = uv; vec4 w = modelMatrix * vec4(position,1.); vW = w.xyz; gl_Position = projectionMatrix * viewMatrix * w; }',
    fragmentShader: `uniform sampler2D map; uniform float uT, uGlow, uAlpha; uniform vec4 uR; varying vec2 vUv; varying vec3 vW;
      void main(){ vec3 ui = texture2D(map, vUv).rgb; ui = pow(ui, vec3(2.2));
        float d = length(vW.xz - uR.xy), age = uT - uR.z; float ring = age > 0. ? exp(-pow((d - age * 9.0) / 0.6, 2.)) * exp(-age * 1.2) * uR.w : 0.;
        float flash = age > 0. ? exp(-d * 0.35) * exp(-age * 3.0) * uR.w : 0.;
        vec3 c = ui * 1.6 * uGlow + vec3(0.05, 0.08, 0.16) * uGlow + vec3(0.55, 0.75, 1.0) * (ring * 2.5 + flash * 3.0);
        float edge = smoothstep(0.0, 0.03, vUv.x) * smoothstep(1.0, 0.97, vUv.x) * smoothstep(0.0, 0.02, vUv.y) * smoothstep(1.0, 0.98, vUv.y);
        gl_FragColor = vec4(c, max(uAlpha, ring + flash) * edge); }` });
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(9, 18), m); mesh.rotation.x = -Math.PI / 2; mesh.position.set(0.3, y, -8.3); mesh.renderOrder = 2;
  return { mesh, ripple, mat: m };
}

// the phone screen: a messages app open on its Drafts folder (drawn, not any real app)
export function phoneUI(g, w, h, o = {}) {
  g.save(); if (o.mirror) { g.translate(w, 0); g.scale(-1, 1); }
  const bg = g.createLinearGradient(0, 0, 0, h); bg.addColorStop(0, '#0f1724'); bg.addColorStop(1, '#0a0f18'); g.fillStyle = bg; g.fillRect(0, 0, w, h);
  const s = w / 1024;
  g.fillStyle = '#e8eef8'; g.font = `${44 * s}px PoppinsM`; g.fillText('9:41', 60 * s, 80 * s);
  g.font = `bold ${92 * s}px PoppinsB`; g.fillText('Drafts', 60 * s, 250 * s);
  g.fillStyle = '#7f8ba0'; g.font = `${40 * s}px PoppinsM`; g.fillText('127 messages · never sent', 62 * s, 315 * s);
  const rows = [['Maa', 'Love you, Maa. Thank you for every…', '#e07aa0', 'now'], ['Papa', 'Sorry Papa. Us din jo bola…', '#5aa0c8', '3 yrs'], ['Riya', 'Miss you.', '#e8b52f', 'today'], ['Bhai', 'Yaar, sorry, tera birthday…', '#3d8a5a', '1 yr'],
    ['Dadi', 'Aap kaisi ho? Main aaunga…', '#c96f2c', '2 yrs'], ['Rahul', 'Thanks bro, tu na hota toh…', '#7a3d8a', '8 mo'], ['Sir', 'Thank you for believing in…', '#2a5fb0', '4 yrs'], ['Maa', 'Khana kha liya?', '#e07aa0', '5 mo']];
  rows.forEach(([n, m, c, tm], i) => { const y = (420 + i * 190) * s;
    if (o.hi === i) { g.fillStyle = 'rgba(90,150,255,0.18)'; g.fillRect(0, y - 70 * s, w, 180 * s); }
    g.fillStyle = c; g.beginPath(); g.arc(120 * s, y + 20 * s, 58 * s, 0, 7); g.fill(); g.fillStyle = '#fff'; g.font = `bold ${54 * s}px PoppinsB`; g.textAlign = 'center'; g.fillText(n[0], 120 * s, y + 40 * s); g.textAlign = 'left';
    g.fillStyle = '#eef2f8'; g.font = `bold ${50 * s}px PoppinsB`; g.fillText(n, 210 * s, y + 4 * s); g.fillStyle = '#e0614f'; g.font = `${32 * s}px PoppinsM`; g.fillText('Draft', 210 * s + g.measureText(n).width * 1.0 + 26 * s, y + 2 * s);
    g.fillStyle = '#8c97aa'; g.font = `${40 * s}px PoppinsM`; g.fillText(m, 210 * s, y + 66 * s); g.textAlign = 'right'; g.fillText(tm, w - 50 * s, y + 4 * s); g.textAlign = 'left';
    g.fillStyle = 'rgba(255,255,255,0.06)'; g.fillRect(210 * s, y + 110 * s, w, 2 * s); });
  // compose bar with the send button
  const sx = (o.sendX ?? 0.883) * w; g.fillStyle = '#18212f'; g.fillRect(0, h - 260 * s, w, 260 * s); g.fillStyle = '#243044'; g.beginPath(); g.roundRect(50 * s, h - 210 * s, Math.max(200 * s, sx - 150 * s - 50 * s), 120 * s, 60 * s); g.fill();
  g.fillStyle = '#9aa6b8'; g.font = `${42 * s}px PoppinsM`; g.fillText(o.typing || 'Love you, Maa ❤', 95 * s, h - 135 * s);
  g.fillStyle = '#3d8bff'; g.beginPath(); g.arc(sx, h - 150 * s, 70 * s, 0, 7); g.fill();
  g.fillStyle = '#fff'; g.beginPath(); g.moveTo(sx - 30 * s, h - 185 * s); g.lineTo(sx + 45 * s, h - 150 * s); g.lineTo(sx - 30 * s, h - 115 * s); g.lineTo(sx - 18 * s, h - 150 * s); g.closePath(); g.fill();
  g.restore();
}

export function shafts(S, top = 13) {
  const m = new THREE.ShaderMaterial({ transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide, uniforms: { uT: { value: 0 }, uA: { value: 0.11 } },
    vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.); }',
    fragmentShader: `uniform float uT, uA; varying vec2 vUv; float h(float x){ return fract(sin(x * 91.7) * 4375.5); }
      void main(){ float x = vUv.x * 10.0; float n = mix(h(floor(x)), h(floor(x) + 1.0), smoothstep(0.,1.,fract(x))); float b = 0.45 + 0.55 * n;
        float a = smoothstep(0.0, 0.25, vUv.x) * smoothstep(1.0, 0.75, vUv.x) * smoothstep(0.0, 0.7, vUv.y) * (0.75 + 0.25 * sin(uT * 0.6 + vUv.x * 5.0));
        gl_FragColor = vec4(vec3(0.62, 0.78, 1.0) * a * b * uA, 1.0); }` });
  const g = new THREE.Group();
  for (const [x, z, w, r] of [[-2.2, -2.5, 2.2, 0.18], [1.8, -1.0, 2.8, -0.12], [3.8, 1.5, 1.8, 0.1], [-4.2, 1.0, 2.0, -0.2]]) {
    const p = new THREE.Mesh(new THREE.PlaneGeometry(w, top), m); p.position.set(x, top / 2, z); p.rotation.set(0, r + 0.6, 0.12); g.add(p);
    const p2 = p.clone(); p2.rotation.y += Math.PI / 2; g.add(p2); }
  S.add(g); return { g, m };
}

export function dust(S, n, lo, hi, seed, color = [1.0, 0.85, 0.62]) {
  const R = rng(seed), P = new Float32Array(n * 3), Sd = new Float32Array(n);
  for (let i = 0; i < n; i++) { for (let k = 0; k < 3; k++) P[i * 3 + k] = lerp(lo[k], hi[k], R()); Sd[i] = R(); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(P, 3)); g.setAttribute('aS', new THREE.BufferAttribute(Sd, 1));
  const m = new THREE.ShaderMaterial({ transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, uniforms: { uT: { value: 0 }, uH: { value: 1440 }, uC: { value: new THREE.Vector3(...color) }, uA: { value: 1 } },
    vertexShader: `attribute float aS; uniform float uT, uH; varying float vA;
      void main(){ vec3 p = position; float s = aS * 6.283; p += vec3(sin(uT * 0.21 + s * 3.0) * 0.35, sin(uT * 0.13 + s) * 0.25 + uT * 0.02 * (aS - 0.5), cos(uT * 0.17 + s * 2.0) * 0.35);
        vec4 mv = modelViewMatrix * vec4(p, 1.); gl_Position = projectionMatrix * mv; float d = -mv.z; gl_PointSize = clamp((0.012 + aS * 0.012) * uH * projectionMatrix[1][1] / d, 1.0, 40.0);
        vA = (0.35 + 0.65 * fract(aS * 13.7)) * smoothstep(0.1, 0.6, d) * (0.6 + 0.4 * sin(uT * 1.3 + s * 5.0)); }`,
    fragmentShader: 'uniform vec3 uC; uniform float uA; varying float vA; void main(){ float r = length(gl_PointCoord - 0.5); gl_FragColor = vec4(uC * smoothstep(0.5, 0.0, r) * vA * uA * 0.8, 1.); }' });
  const pts = new THREE.Points(g, m); pts.frustumCulled = false; S.add(pts); return { pts, m };
}

// ------------------------------------------------------------------------------------------------ the postman: folded paper, old, kind
export function postman(S) {
  const paper = std({ map: paperTex(21, '#f1e7d2'), roughness: 0.85, flatShading: true });
  const paper2 = std({ map: paperTex(22, '#e2d4b8'), roughness: 0.85, flatShading: true });
  const navy = std({ color: 0x2b3a5c, roughness: 0.8, flatShading: true }), red = std({ color: 0xb9322a, roughness: 0.7, flatShading: true });
  const kraft = std({ color: 0x9a6a3c, roughness: 0.8, flatShading: true }), black = std({ color: 0x0d0d10, roughness: 0.35 }), white = new THREE.MeshBasicMaterial({ color: 0xffffff });
  const P = { root: new THREE.Group() }; S.add(P.root);
  const M = (geo, mat, x, y, z, parent) => { const m = new THREE.Mesh(geo, mat); m.position.set(x, y, z); m.castShadow = true; m.receiveShadow = true; parent.add(m); return m; };
  P.hips = new THREE.Group(); P.hips.position.y = 0.42; P.root.add(P.hips);
  // legs: folded paper trousers
  P.legs = [];
  for (const sd of [-1, 1]) { const hp = new THREE.Group(); hp.position.set(0.075 * sd, 0, 0); P.hips.add(hp); M(new THREE.CylinderGeometry(0.06, 0.05, 0.38, 4), navy, 0, -0.2, 0, hp).rotation.y = Math.PI / 4;
    M(new THREE.BoxGeometry(0.1, 0.05, 0.17), black, 0, -0.39, 0.03, hp); P.legs.push(hp); }
  // body: a folded tunic (five-sided, wider at the bottom)
  P.torso = new THREE.Group(); P.hips.add(P.torso);
  M(new THREE.CylinderGeometry(0.15, 0.2, 0.46, 5), navy, 0, 0.22, 0, P.torso).rotation.y = Math.PI / 5;
  M(new THREE.CylinderGeometry(0.155, 0.155, 0.05, 5), kraft, 0, 0.05, 0, P.torso).rotation.y = Math.PI / 5; // belt
  for (const yy of [0.18, 0.28, 0.38]) M(new THREE.SphereGeometry(0.014, 6, 4), std({ color: 0xc9a046, metalness: 1, roughness: 0.3 }), 0, yy, 0.17, P.torso);
  // satchel across the body
  const bag = M(new THREE.BoxGeometry(0.2, 0.17, 0.07), kraft, 0.17, 0.1, 0.1, P.torso); bag.rotation.y = -0.4;
  const strap = M(new THREE.BoxGeometry(0.03, 0.55, 0.02), kraft, 0.02, 0.28, 0.16, P.torso); strap.rotation.z = 0.75;
  // head: a faceted paper ball, with a white folded moustache, round glasses and a postal cap
  P.neck = new THREE.Group(); P.neck.position.y = 0.47; P.torso.add(P.neck);
  P.head = new THREE.Group(); P.head.position.y = 0.15; P.neck.add(P.head);
  M(new THREE.IcosahedronGeometry(0.15, 1), paper, 0, 0, 0, P.head).scale.set(1, 1.05, 0.95);
  M(new THREE.ConeGeometry(0.03, 0.08, 4), paper2, 0, -0.01, 0.15, P.head).rotation.x = Math.PI / 2; // nose
  for (const sd of [-1, 1]) { const mo = M(new THREE.ConeGeometry(0.035, 0.12, 3), std({ color: 0xf4f2ee, roughness: 0.9, flatShading: true }), 0.05 * sd, -0.055, 0.13, P.head); mo.rotation.z = Math.PI / 2 * sd + 0.25 * sd; mo.rotation.y = 0.3 * sd; }
  P.eyes = [];
  for (const sd of [-1, 1]) { const e = new THREE.Group(); e.position.set(0.055 * sd, 0.03, 0.142); P.head.add(e); M(new THREE.SphereGeometry(0.027, 14, 10), black, 0, 0, 0, e).scale.set(1, 1.15, 0.6); const hl = new THREE.Mesh(new THREE.SphereGeometry(0.008, 8, 6), white); hl.position.set(0.008, 0.01, 0.014); e.add(hl); P.eyes.push(e);
    const rim = new THREE.Mesh(new THREE.TorusGeometry(0.042, 0.004, 6, 24), std({ color: 0xc9a046, metalness: 1, roughness: 0.3 })); rim.position.set(0.055 * sd, 0.03, 0.16); P.head.add(rim);
    const brow = M(new THREE.BoxGeometry(0.05, 0.012, 0.012), std({ color: 0xf4f2ee, roughness: 0.9 }), 0.055 * sd, 0.085, 0.14, P.head); brow.rotation.z = -0.15 * sd; (P.brows ||= []).push(brow); }
  const cap = new THREE.Group(); cap.position.y = 0.11; P.head.add(cap);
  M(new THREE.CylinderGeometry(0.15, 0.155, 0.09, 8), navy, 0, 0.02, 0, cap); M(new THREE.CylinderGeometry(0.165, 0.165, 0.015, 8), navy, 0, 0.07, 0, cap);
  M(new THREE.BoxGeometry(0.24, 0.012, 0.1), black, 0, -0.02, 0.15, cap).rotation.x = 0.25; M(new THREE.BoxGeometry(0.31, 0.02, 0.012), red, 0, 0.0, 0.0, cap).position.set(0, -0.01, 0.152);
  M(new THREE.SphereGeometry(0.02, 8, 6), std({ color: 0xc9a046, metalness: 1, roughness: 0.3 }), 0, 0.03, 0.155, cap);
  // arms: folded strips with paper hands
  P.arms = [];
  for (const sd of [-1, 1]) { const sh = new THREE.Group(); sh.position.set(0.19 * sd, 0.42, 0); P.torso.add(sh); M(new THREE.CylinderGeometry(0.045, 0.04, 0.22, 4), navy, 0, -0.11, 0, sh);
    const el = new THREE.Group(); el.position.y = -0.22; sh.add(el); M(new THREE.CylinderGeometry(0.038, 0.034, 0.2, 4), navy, 0, -0.1, 0, el);
    const hand = new THREE.Group(); hand.position.y = -0.22; el.add(hand); M(new THREE.IcosahedronGeometry(0.045, 0), paper, 0, -0.01, 0, hand);
    P.arms.push({ sh, el, hand, sd }); }
  P.root.traverse(m => { if (m.isMesh) m.castShadow = true; });
  return P;
}
// pose the postman: everything is driven by a few numbers, so his acting is easy to direct and always smooth
export function posePostman(P, t, o = {}) {
  const br = Math.sin(t * 1.6), sway = Math.sin(t * 0.7);
  P.hips.position.y = 0.42 + (o.bob || 0) + 0.004 * br - (o.crouch || 0) * 0.08;
  P.legs.forEach((L, i) => { L.rotation.x = (o.crouch || 0) * (i ? 0.5 : 0.45) * -1 + (o.step ? Math.sin(o.step + i * Math.PI) * 0.4 : 0); });
  P.torso.rotation.set((o.lean || 0) + 0.02 * br, (o.twist || 0) + 0.04 * sway, 0.02 * sway);
  P.torso.scale.set(1 + (o.squash || 0) * 0.5, 1 - (o.squash || 0), 1 + (o.squash || 0) * 0.5);
  P.neck.rotation.set(-(o.look || 0) * 0.45 + (o.nod || 0), (o.turn || 0) * 0.5, (o.tilt || 0) * 0.5);
  P.head.rotation.set(-(o.look || 0) * 0.55, (o.turn || 0) * 0.5, (o.tilt || 0) * 0.5);
  const blink = (o.blink ?? ((t % 3.7) < 0.12 ? 1 : 0)), squint = o.squint || 0;
  P.eyes.forEach(e => e.scale.set(1, Math.max(0.12, 1 - blink - squint * 0.75), 1));
  (P.brows || []).forEach((b, i) => { b.position.y = 0.085 + (o.brow || 0) * 0.025; b.rotation.z = (i ? 1 : -1) * (-0.15 + (o.sad || 0) * -0.35); });
  const A = o.arms || {};
  P.arms.forEach(({ sh, el, hand, sd }, i) => { const a = (i === 0 ? A.l : A.r) || {};
    sh.rotation.set(a.fwd ?? 0.05 * Math.sin(t * 1.6 + i), 0, (a.out ?? 0.08) * sd * -1); el.rotation.set(a.bend ?? -0.25, 0, 0); hand.rotation.set(a.wrist || 0, 0, 0); });
}

// ------------------------------------------------------------------------------------------------ falling envelopes (written and deleted)
export function fallingLetters(S, n, seed) {
  const R = rng(seed), g = new THREE.BoxGeometry(0.4, 0.27, 0.012), m = std({ map: paperTex(9, '#ffffff'), roughness: 0.9 });
  const im = new THREE.InstancedMesh(g, m, n); const L = []; const col = new THREE.Color();
  const pal = ['#efe4cc', '#f4efe4', '#d9c3a0', '#cfe0ef', '#f1d9cf'];
  for (let i = 0; i < n; i++) { L.push({ x: (R() - 0.5) * 9, z: -4.0 + R() * 6, t0: R() * 9, sp: 0.9 + R() * 0.8, rx: R() * 6, ry: R() * 6, w: 1.5 + R() * 2, ph: R() * 6.28 }); im.setColorAt(i, col.set(pal[i % pal.length])); }
  im.castShadow = true; S.add(im); const o = new THREE.Object3D();
  return { im, set(t, on = 1) { for (let i = 0; i < n; i++) { const q = L[i]; let a = (t - q.t0); const life = 12.5 / q.sp; a = ((a % life) + life) % life; const y = 12.6 - a * q.sp;
      o.position.set(q.x + Math.sin(a * q.w * 0.5 + q.ph) * 0.6, y, q.z + Math.cos(a * q.w * 0.4 + q.ph) * 0.3); o.rotation.set(q.rx + a * 0.9, q.ry + a * 0.6, Math.sin(a * q.w + q.ph) * 0.8); o.scale.setScalar(on && y > 0.05 ? 1 : 0.0001); o.updateMatrix(); im.setMatrixAt(i, o.matrix); }
    im.instanceMatrix.needsUpdate = true; } };
}

// particles that a letter breaks into when it is deleted (or that puff off a dusty one)
export function burst(S, n, seed, color = [1, 0.9, 0.75], size = 0.02) {
  const R = rng(seed), P = new Float32Array(n * 3), V = new Float32Array(n * 3), A = new Float32Array(n);
  for (let i = 0; i < n; i++) { V[i * 3] = R() - 0.5; V[i * 3 + 1] = R() - 0.3; V[i * 3 + 2] = R() - 0.5; A[i] = R(); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(P, 3)); g.setAttribute('aV', new THREE.BufferAttribute(V, 3)); g.setAttribute('aR', new THREE.BufferAttribute(A, 1));
  const m = new THREE.ShaderMaterial({ transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, uniforms: { uAge: { value: -1 }, uO: { value: new THREE.Vector3() }, uSpread: { value: new THREE.Vector3(0.25, 0.15, 0.03) }, uC: { value: new THREE.Vector3(...color) }, uH: { value: 1440 }, uSz: { value: size }, uLife: { value: 1.6 }, uRise: { value: 0.4 }, uSrc: { value: new THREE.Vector3(0.2, 0.13, 0.0) } },
    vertexShader: `attribute vec3 aV; attribute float aR; uniform float uAge, uH, uSz, uLife, uRise; uniform vec3 uO, uSpread, uSrc; varying float vA;
      void main(){ float a = uAge - aR * 0.25; vec3 p = uO + (aV * 2.0) * uSrc + aV * uSpread * max(a, 0.0) * 3.0 + vec3(0., uRise * a * a * 0.5 + uRise * a * 0.4, 0.);
        vA = a > 0.0 ? smoothstep(uLife, 0.0, a) * smoothstep(0.0, 0.08, a) : 0.0; vec4 mv = modelViewMatrix * vec4(p, 1.); gl_Position = projectionMatrix * mv; gl_PointSize = clamp(uSz * (0.5 + aR) * uH * projectionMatrix[1][1] / -mv.z, 1.0, 60.0); }`,
    fragmentShader: 'uniform vec3 uC; varying float vA; void main(){ float r = length(gl_PointCoord - 0.5); gl_FragColor = vec4(uC * smoothstep(0.5, 0.0, r) * vA, 1.); }' });
  const pts = new THREE.Points(g, m); pts.frustumCulled = false; S.add(pts); return { pts, m };
}

// ------------------------------------------------------------------------------------------------ a letter that folds into a paper plane
export function foldingLetter(tex) {
  // two shapes with the same triangles: a flat sheet and a dart. Fold = blend between them.
  const flat = [], plane = [], uv = [];
  const sheet = [[-0.25, 0], [0.25, 0], [0.25, 0.32], [-0.25, 0.32]]; // the letter (x across, z along)
  // dart: nose at +z
  const tri = (a, b, c, A, B, Cc) => { flat.push(...a, ...b, ...c); plane.push(...A, ...B, ...Cc); for (const p of [a, b, c]) uv.push((p[0] + 0.25) / 0.5, p[2] / 0.32); };
  const F = (x, z) => [x, 0, z];
  // left wing, left body, right body, right wing (sheet split by x = -0.25, -0.06, 0, 0.06, 0.25)
  const xs = [-0.25, -0.06, 0, 0.06, 0.25];
  const P = (x, z) => { const nose = [0, 0.0, 0.42], sgn = Math.sign(x), ax = Math.abs(x);
    if (ax <= 0.001) return [0, -0.05 + z * 0.12, z * 1.25 - 0.0];             // keel
    if (ax <= 0.061) return [sgn * 0.012, 0.0 + z * 0.02, z * 1.25];             // body walls
    return [sgn * (0.012 + (ax - 0.06) * 1.3 * (0.35 + z * 2.0)), 0.02 + (ax - 0.06) * 0.15, z * 1.25 - (ax - 0.06) * 0.2]; }; // wings
  for (let i = 0; i < 4; i++) { const x0 = xs[i], x1 = xs[i + 1];
    tri(F(x0, 0), F(x1, 0), F(x1, 0.32), P(x0, 0), P(x1, 0), P(x1, 0.32)); tri(F(x0, 0), F(x1, 0.32), F(x0, 0.32), P(x0, 0), P(x1, 0.32), P(x0, 0.32)); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(flat, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.morphAttributes.position = [new THREE.Float32BufferAttribute(plane.map((v, i) => v - flat[i]), 3)]; g.computeVertexNormals();
  const m = new THREE.Mesh(g, std({ map: tex, roughness: 0.85, side: THREE.DoubleSide })); m.morphTargetInfluences = [0]; m.castShadow = true;
  return m;
}

// ------------------------------------------------------------------------------------------------ the phone in the dark room (outside)
export function phoneSet(E, at) {
  const S = E.scene, g = new THREE.Group(); g.position.set(...at); S.add(g);
  // a crumpled blanket: displaced plane
  const bg = new THREE.PlaneGeometry(4, 4, 160, 160); bg.rotateX(-Math.PI / 2); const p = bg.attributes.position;
  for (let i = 0; i < p.count; i++) { const x = p.getX(i), z = p.getZ(i); p.setY(i, Math.sin(x * 3.1 + z * 1.3) * 0.03 + Math.sin(x * 7 - z * 5) * 0.012 + Math.sin(z * 2.2) * 0.04 * Math.cos(x * 1.7)); }
  bg.computeVertexNormals();
  const fab = canvasTex(256, 256, (c, w, h) => { c.fillStyle = '#2a3148'; c.fillRect(0, 0, w, h); for (let y = 0; y < h; y += 4) { c.fillStyle = 'rgba(255,255,255,0.03)'; c.fillRect(0, y, w, 1); } grain(c, w, h, 3000, 0.1, 4); }); fab.wrapS = fab.wrapT = THREE.RepeatWrapping; fab.repeat.set(12, 12);
  const bl = new THREE.Mesh(bg, std({ map: fab, roughness: 0.95 })); bl.receiveShadow = true; g.add(bl);
  // the phone: rounded slab, glass front with the drafts list
  const shape = new THREE.Shape(); const w = 0.075, h = 0.155, r = 0.011; shape.moveTo(-w / 2 + r, -h / 2); shape.lineTo(w / 2 - r, -h / 2); shape.quadraticCurveTo(w / 2, -h / 2, w / 2, -h / 2 + r); shape.lineTo(w / 2, h / 2 - r); shape.quadraticCurveTo(w / 2, h / 2, w / 2 - r, h / 2); shape.lineTo(-w / 2 + r, h / 2); shape.quadraticCurveTo(-w / 2, h / 2, -w / 2, h / 2 - r); shape.lineTo(-w / 2, -h / 2 + r); shape.quadraticCurveTo(-w / 2, -h / 2, -w / 2 + r, -h / 2);
  const body = new THREE.Mesh(new THREE.ExtrudeGeometry(shape, { depth: 0.007, bevelEnabled: true, bevelThickness: 0.0015, bevelSize: 0.0015, bevelSegments: 3, curveSegments: 12 }), std({ color: 0x1a1c22, metalness: 0.8, roughness: 0.3 }));
  body.rotation.x = -Math.PI / 2; body.position.y = 0.035; body.castShadow = true; g.add(body);
  const scrCanvas = document.createElement('canvas'); scrCanvas.width = 540; scrCanvas.height = 1116; const scrTex = new THREE.CanvasTexture(scrCanvas); scrTex.colorSpace = THREE.SRGBColorSpace;
  const scr = new THREE.Mesh(new THREE.PlaneGeometry(0.071, 0.151), new THREE.MeshBasicMaterial({ map: scrTex, toneMapped: false, color: C('#ffffff').multiplyScalar(1.25) })); scr.rotation.x = -Math.PI / 2; scr.position.y = 0.0445; g.add(scr);
  const glow = new THREE.PointLight(0x8fb4ff, 0.35, 1.2, 2); glow.position.set(0, 0.12, 0); g.add(glow);
  const moon = new THREE.DirectionalLight(0x6f86c8, 0.9); moon.position.set(at[0] - 2, at[1] + 3, at[2] + 1); moon.target.position.set(...at); S.add(moon, moon.target);
  return { g, scrCanvas, scrTex, draw(o) { const c = scrCanvas.getContext('2d'); phoneUI(c, 540, 1116, o); scrTex.needsUpdate = true; } };
}
