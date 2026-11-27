// nature.js — the outdoors for CodeScape films: a sky with sun and clouds that also lights the scene, real earth, trees with leaves that move in the wind, grass, dust in the light.
// Everything here is code (textures from noise come from tools/textures.py as files in tex/). Time-driven motion reads the film time (deterministic per frame).
import { THREE, V3, clamp, lerp, rng } from './engine.js';

const TL = new THREE.TextureLoader(), browser = typeof document !== 'undefined' && typeof Image !== 'undefined' && typeof window !== 'undefined';   /* in node (the film's review) nothing is drawn: no textures, no canvases */
export const T = { value: 0 };   /* film time for wind, clouds and dust; set by an updater */
export function clock(E) { if (!E.updaters) E.updaters = []; if (!E._natClock) { E._natClock = 1; E.updaters.push(t => { T.value = t; }); } }
async function tex(path, o = {}) { if (!browser) return null; const t = await TL.loadAsync(new URL('./' + path, import.meta.url).href); t.colorSpace = o.data ? THREE.NoColorSpace : THREE.SRGBColorSpace; t.anisotropy = 8; if (o.rep) { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(...o.rep); } return t; }

// ------------------------------------------------------------------ the sky
const SKY_FS = `uniform vec3 uSun, uZen, uHor, uWarm, uSunCol, uCloudCol; uniform float uCov, uT, uSunI; varying vec3 vD;
  float hh(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
  float vn(vec2 p){ vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f); return mix(mix(hh(i), hh(i + vec2(1, 0)), f.x), mix(hh(i + vec2(0, 1)), hh(i + vec2(1, 1)), f.x), f.y); }
  float fbm(vec2 p){ float s = 0.0, a = 0.5; for (int i = 0; i < 6; i++) { s += a * vn(p); p = p * 2.03 + vec2(1.7, 9.2); a *= 0.5; } return s; }
  void main(){ vec3 d = normalize(vD); float h = max(d.y, 0.0), mu = dot(d, uSun);
    vec3 c = mix(uHor, uZen, pow(h, 0.42));
    c = mix(c, uWarm, pow(max(mu, 0.0), 3.0) * pow(1.0 - h, 2.0) * 0.75);                 /* the warm side of the sky, low down near the sun */
    c += uSunCol * (pow(max(mu, 0.0), 7.0) * 0.45 + pow(max(mu, 0.0), 60.0) * 1.6) * uSunI;  /* the glow round the sun */
    c += uSunCol * smoothstep(0.99955, 0.99975, mu) * 30.0 * uSunI;                          /* the disc */
    if (d.y > 0.005) { vec2 p = d.xz / (d.y + 0.11) * 0.9 + vec2(uT * 0.004, uT * 0.0015);
      float n = fbm(p * 1.3), n2 = fbm(p * 1.3 + vec2(0.06, 0.03) - uSun.xz * 0.04);
      float cov = smoothstep(1.0 - uCov, 1.0 - uCov + 0.22, n) * smoothstep(0.005, 0.14, d.y);
      float shade = clamp((n - n2) * 6.0 + 0.55, 0.0, 1.0);                                     /* lit on the sun's side, darker underneath */
      vec3 cc = uCloudCol * mix(0.62, 1.15, shade) + uSunCol * pow(max(mu, 0.0), 5.0) * 0.9 * (1.0 - cov * 0.6);
      c = mix(c, cc, cov * 0.92); }
    if (d.y < 0.0) c = mix(uHor, uHor * vec3(0.75, 0.72, 0.68), smoothstep(0.0, -0.2, d.y));
    gl_FragColor = vec4(c, 1.0); }`;
const SKY_VS = `varying vec3 vD; void main(){ vD = position; vec4 p = modelViewMatrix * vec4(position, 1.0); gl_Position = projectionMatrix * p; }`;
export function skyMaterial(o = {}) {
  const sun = new V3(...(o.sun || [0.1, 0.45, -1])).normalize(), C = (a, k = 1) => new V3(...a).multiplyScalar(k);
  return new THREE.ShaderMaterial({ side: THREE.BackSide, depthWrite: true, fog: false, vertexShader: SKY_VS, fragmentShader: SKY_FS,
    uniforms: { uSun: { value: sun }, uZen: { value: C(o.zen || [0.12, 0.3, 0.72], o.k ?? 1.25) }, uHor: { value: C(o.hor || [0.7, 0.76, 0.8], o.k ?? 1.25) }, uWarm: { value: C(o.warm || [1.25, 0.86, 0.55], o.k ?? 1.25) },
      uSunCol: { value: C(o.sunCol || [1.0, 0.78, 0.5]) }, uCloudCol: { value: C(o.cloudCol || [1.05, 1.0, 0.95], o.k ?? 1.25) }, uCov: { value: o.cover ?? 0.42 }, uT: T, uSunI: { value: o.sunI ?? 1 } } });
}
/** the sky dome (it follows the lens), and the same sky as the scene's light from all round (image-based light). o: sun (direction), zen, hor, warm, cover, env (strength) */
export function sky(E, o = {}) { clock(E); const m = skyMaterial(o); const dome = new THREE.Mesh(new THREE.SphereGeometry(300, 64, 32), m); dome.frustumCulled = false; dome.renderOrder = -10; E.scene.add(dome);
  E.updaters.push(() => { if (E.cam) dome.position.copy(E.cam.position); });
  const em = skyMaterial({ ...o, sunI: o.envSun ?? 0.35 }); if (E.setEnv && browser) E.setEnv(s => { s.add(new THREE.Mesh(new THREE.SphereGeometry(40, 48, 24), em)); }, o.env ?? 0.55);
  return { dome, sun: new V3(...(o.sun || [0.1, 0.45, -1])).normalize() }; }

// ------------------------------------------------------------------ the ground
/** packed earth: a detailed 24 m square round `at` (tex/ground.jpg + height) fading into a tiling earth all round. */
export async function ground(E, o = {}) {
  const at = o.at || [0, 0], S = o.size ?? 24;
  const [gc, gh, tc, th] = await Promise.all([tex('tex/ground.jpg'), tex('tex/ground_h.jpg', { data: 1 }), tex('tex/earth_tile.jpg', { rep: [30, 30] }), tex('tex/earth_tile_h.jpg', { data: 1, rep: [30, 30] })]);
  const outer = new THREE.Mesh(new THREE.PlaneGeometry(120, 120), new THREE.MeshStandardMaterial({ map: tc, bumpMap: th, bumpScale: 0.4, roughness: 0.97 })); outer.rotation.x = -Math.PI / 2; outer.position.set(at[0], -0.002, at[1]); outer.receiveShadow = true; E.scene.add(outer);
  // the inner square fades out at its edges (dithered alpha: no sorting trouble)
  let am = null; if (browser) { const fade = document.createElement('canvas'); fade.width = fade.height = 256; { const g = fade.getContext('2d'); const id = g.createImageData(256, 256); for (let y = 0; y < 256; y++) for (let x = 0; x < 256; x++) { const e = Math.min(x, y, 255 - x, 255 - y) / 256; const a = clamp(e / 0.14, 0, 1); const v = Math.round(255 * a * a * (3 - 2 * a)); const k = (y * 256 + x) * 4; id.data[k] = id.data[k + 1] = id.data[k + 2] = v; id.data[k + 3] = 255; } g.putImageData(id, 0, 0); }
  am = new THREE.CanvasTexture(fade); am.colorSpace = THREE.NoColorSpace; }
  const inner = new THREE.Mesh(new THREE.PlaneGeometry(S, S), new THREE.MeshStandardMaterial({ map: gc, bumpMap: gh, bumpScale: 0.45, roughness: 0.95, alphaMap: am, alphaHash: true }));
  inner.rotation.x = -Math.PI / 2; inner.position.set(at[0], 0, at[1]); inner.receiveShadow = true; E.scene.add(inner);
  return { inner, outer };
}

// ------------------------------------------------------------------ trees
function leafAtlas() { if (!browser) return null;  /* two twigs, each crowded with small pointed leaves (neem-like), filling their half of the canvas; alpha outside the leaves */
  const c = document.createElement('canvas'); c.width = 1024; c.height = 512; const g = c.getContext('2d'); const r = rng(11);
  for (let v = 0; v < 2; v++) { const ox = v * 512 + 256;
    for (let br = 0; br < 5; br++) { const a0 = -Math.PI / 2 + (br - 2) * 0.42 + (r() - 0.5) * 0.2, L0 = 170 + r() * 60, bx = ox + (br - 2) * 14, by = 500;
      g.strokeStyle = '#4b4a2a'; g.lineWidth = 4; g.beginPath(); g.moveTo(bx, by); const ex = bx + Math.cos(a0) * L0 * 1.9, ey = by + Math.sin(a0) * L0 * 1.9; g.quadraticCurveTo(bx + Math.cos(a0) * L0, by + Math.sin(a0) * L0 - 30, ex, ey); g.stroke();
      for (let i = 0; i < 13; i++) { const u = 0.15 + i / 13 * 0.85, px = lerp(bx, ex, u) + Math.sin(u * 3) * 10, py = lerp(by, ey, u) - Math.sin(u * Math.PI) * 25;
        for (const s of [-1, 1]) { const L = (48 + r() * 26) * (1 - 0.35 * Math.abs(u - 0.55)), ang = a0 + s * (0.8 + 0.3 * r());
          g.save(); g.translate(px, py); g.rotate(ang + Math.PI / 2); const tone = r(); const G2 = Math.round(95 + tone * 70), R2 = Math.round(50 + tone * 45), B2 = Math.round(20 + tone * 25);
          g.fillStyle = `rgb(${R2},${G2},${B2})`; g.beginPath(); g.moveTo(0, 0); g.quadraticCurveTo(L * 0.2, -L * 0.45, 0, -L); g.quadraticCurveTo(-L * 0.2, -L * 0.45, 0, 0); g.fill();
          g.strokeStyle = 'rgba(30,50,15,.4)'; g.lineWidth = 1.4; g.beginPath(); g.moveTo(0, 0); g.lineTo(0, -L * 0.92); g.stroke(); g.restore(); } } } }
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 8; return t; }
let LEAF = null;
function leafMaterial() { if (LEAF) return LEAF; const map = leafAtlas();
  const m = new THREE.MeshStandardMaterial({ map, alphaTest: 0.42, side: THREE.DoubleSide, roughness: 0.72, metalness: 0 });
  m.onBeforeCompile = sh => { sh.uniforms.uT = T; sh.vertexShader = 'uniform float uT;\n' + sh.vertexShader.replace('#include <begin_vertex>', `#include <begin_vertex>
      #ifdef USE_INSTANCING
        vec3 ip = vec3(instanceMatrix[3][0], instanceMatrix[3][1], instanceMatrix[3][2]);
        float w = sin(uT * 1.9 + ip.x * 1.3 + ip.z * 0.7) * 0.6 + sin(uT * 3.7 + ip.y * 2.1) * 0.4;
        transformed += vec3(w * 0.035, w * 0.012, cos(uT * 1.5 + ip.z * 1.1) * 0.025) * (0.4 + uv.y);
      #endif`);
    /* light coming through the leaves when the sun is behind them */
    sh.fragmentShader = sh.fragmentShader.replace('#include <lights_fragment_end>', `#include <lights_fragment_end>
      #if NUM_DIR_LIGHTS > 0
        { float back = max(0.0, dot(normalize(-directionalLights[0].direction), normalize(vViewPosition))); reflectedLight.directDiffuse += diffuseColor.rgb * directionalLights[0].color * pow(back, 3.0) * 0.55 * vec3(0.9, 1.1, 0.5); }
      #endif`); };
  const half = sh => { sh.vertexShader = 'attribute float aHalf;\n' + sh.vertexShader.replace('#include <uv_vertex>', '#include <uv_vertex>\n#if defined(USE_INSTANCING) && defined(USE_MAP)\n vMapUv.x = vMapUv.x * 0.5 + aHalf;\n#endif'); };
  const ob = m.onBeforeCompile; m.onBeforeCompile = sh => { ob(sh); half(sh); };
  const dm = new THREE.MeshDepthMaterial({ depthPacking: THREE.RGBADepthPacking, map, alphaTest: 0.42 }); dm.onBeforeCompile = half;
  LEAF = { m, dm }; return LEAF; }
function taper(curve, r0, r1, seg = 24, rad = 9, mat) {   /* a tube whose radius narrows from r0 to r1 */
  const pos = [], idx = [], uv = [], frames = curve.computeFrenetFrames(seg, false);
  for (let i = 0; i <= seg; i++) { const u = i / seg, P = curve.getPointAt(u), N = frames.normals[i], B = frames.binormals[i], r = lerp(r0, r1, Math.pow(u, 0.8));
    for (let j = 0; j <= rad; j++) { const a = j / rad * Math.PI * 2, wob = 1 + 0.06 * Math.sin(a * 3 + u * 7); pos.push(P.x + r * wob * (Math.cos(a) * N.x + Math.sin(a) * B.x), P.y + r * wob * (Math.cos(a) * N.y + Math.sin(a) * B.y), P.z + r * wob * (Math.cos(a) * N.z + Math.sin(a) * B.z)); uv.push(j / rad * 2, u * curve.getLength() * 1.5); } }
  for (let i = 0; i < seg; i++) for (let j = 0; j < rad; j++) { const a = i * (rad + 1) + j, b = a + rad + 1; idx.push(a, b, a + 1, a + 1, b, b + 1); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx); g.computeVertexNormals();
  const m = new THREE.Mesh(g, mat); m.castShadow = m.receiveShadow = true; return m; }
let BARK = null;
async function barkMat() { if (BARK) return BARK; const [c, h] = await Promise.all([tex('tex/bark.jpg', { rep: [1, 1] }), tex('tex/bark_h.jpg', { data: 1, rep: [1, 1] })]); if (c) { c.wrapS = c.wrapT = h.wrapS = h.wrapT = THREE.RepeatWrapping; } BARK = new THREE.MeshStandardMaterial({ map: c, bumpMap: h, bumpScale: 3, roughness: 0.95 }); return BARK; }
/** a broad shade tree (neem / peepal kind): trunk, limbs, twigs, and a crown of leaf sprigs that move in the wind. o: at [x,z], h (height), spread, seed, leaves (count), tint */
export async function tree(E, o = {}) {
  clock(E); const r = rng(o.seed ?? 1), H = o.h ?? 6, SP = o.spread ?? 3.2, G = new THREE.Group(); G.position.set(o.at?.[0] ?? 0, 0, o.at?.[1] ?? 0); G.rotation.y = r() * 6.28; E.scene.add(G);
  const bm = await barkMat(); const tips = [];
  const lean = new V3((r() - 0.5) * 0.5, 0, (r() - 0.5) * 0.5), top = new V3(lean.x, H * 0.48, lean.z);
  G.add(taper(new THREE.CatmullRomCurve3([new V3(0, -0.2, 0), new V3(lean.x * 0.2, H * 0.18, lean.z * 0.2), top]), 0.26 * H / 6, 0.15 * H / 6, 20, 12, bm));
  const nL = 5 + Math.floor(r() * 3);
  for (let i = 0; i < nL; i++) { const a = i / nL * 6.28 + r() * 0.6, up = 0.55 + r() * 0.5, L = SP * (0.75 + r() * 0.4);
    const p0 = top.clone().add(new V3(0, -r() * 0.6, 0)), dir = new V3(Math.cos(a), up, Math.sin(a)).normalize();
    const p1 = p0.clone().addScaledVector(dir, L * 0.5).add(new V3(0, 0.3, 0)), p2 = p0.clone().addScaledVector(dir, L).add(new V3(0, H * 0.28 * (0.6 + r() * 0.5), 0));
    G.add(taper(new THREE.CatmullRomCurve3([p0, p1, p2]), 0.11 * H / 6, 0.03, 14, 8, bm)); tips.push(p2, p1.clone().lerp(p2, 0.6));
    for (let k = 0; k < 3; k++) { const q0 = p1.clone().lerp(p2, 0.3 + 0.25 * k), d2 = new V3(Math.cos(a + (r() - 0.5) * 2), 0.6 + r() * 0.6, Math.sin(a + (r() - 0.5) * 2)).normalize(), q1 = q0.clone().addScaledVector(d2, L * 0.45);
      G.add(taper(new THREE.CatmullRomCurve3([q0, q0.clone().lerp(q1, 0.5).add(new V3(0, 0.12, 0)), q1]), 0.035, 0.01, 8, 6, bm)); tips.push(q1); } }
  // the crown: sprigs in clumps round the twig ends, facing out and a little up, more of them on the outside
  const { m, dm } = leafMaterial(), n = o.leaves ?? 5200, geo = new THREE.PlaneGeometry(0.62, 0.62); geo.translate(0, 0.27, 0);
  const im = new THREE.InstancedMesh(geo, m, n); im.customDepthMaterial = dm; im.castShadow = true; im.receiveShadow = true; im.frustumCulled = false;
  const M4 = new THREE.Matrix4(), Q = new THREE.Quaternion(), Sc = new V3(), Pp = new V3(), col = new THREE.Color(), tint = new THREE.Color(o.tint || '#ffffff');
  for (let i = 0; i < n; i++) { const ti = Math.floor(r() * tips.length), tp = tips[ti]; const RR = 0.75 + 0.45 * ((ti * 0.618) % 1); const u = new V3(r() * 2 - 1, r() * 1.5 - 0.45, r() * 2 - 1).normalize();
    Pp.copy(tp).addScaledVector(u, RR * (0.55 + 0.45 * Math.cbrt(r()))); const out = u.clone().add(new V3(0, 0.6, 0)).normalize();
    Q.setFromUnitVectors(new V3(0, 0, 1), out); Q.multiply(new THREE.Quaternion().setFromAxisAngle(new V3(0, 0, 1), r() * 6.28)); const s = 0.75 + r() * 0.6; Sc.set(s, s, s);
    M4.compose(Pp, Q, Sc); im.setMatrixAt(i, M4);
    const ins = clamp(u.y * 0.5 + 0.5, 0, 1); col.setRGB(0.75 + 0.35 * r(), 0.8 + 0.3 * r(), 0.6 + 0.3 * r()).multiplyScalar(0.7 + 0.45 * ins).multiply(tint); im.setColorAt(i, col); }
  // the atlas holds two sprigs: half the cards use the right half
  const half = new Float32Array(n); for (let i = 0; i < n; i++) half[i] = r() < 0.5 ? 0 : 0.5; geo.setAttribute('aHalf', new THREE.InstancedBufferAttribute(half, 1));
  G.add(im); return { group: G, leaves: im };
}

// ------------------------------------------------------------------ grass
function bladeClump(r) { const pos = [], col = [], uv = [], idx = []; let base = 0;
  for (let b = 0; b < 9; b++) { const a = r() * 6.28, h = 0.07 + r() * 0.2, w = 0.006 + r() * 0.006, bend = (r() - 0.3) * 0.12, ox = (r() - 0.5) * 0.08, oz = (r() - 0.5) * 0.08, ca = Math.cos(a), sa = Math.sin(a), dry = r();
    for (let k = 0; k <= 4; k++) { const u = k / 4, y = h * u, x = bend * u * u, ww = w * (1 - u * 0.92);
      for (const s of [-1, 1]) { const lx = s * ww, lz = x; pos.push(ox + lx * ca - lz * sa, y, oz + lx * sa + lz * ca); uv.push(s < 0 ? 0 : 1, u);
        const g = lerp(0.35, 0.95, u); col.push(lerp(0.16, 0.55, dry) * g, lerp(0.28, 0.5, dry) * g + 0.05, lerp(0.07, 0.2, dry) * g); } }
    for (let k = 0; k < 4; k++) { const a0 = base + k * 2; idx.push(a0, a0 + 2, a0 + 1, a0 + 1, a0 + 2, a0 + 3); } base += 10; }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx); g.computeVertexNormals(); return g; }
/** tufts of grass where nobody walks. place(x, z) → 0…1 how likely a tuft is there. o: n, box [x0, z0, x1, z1], seed */
export function grass(E, place, o = {}) { clock(E); const r = rng(o.seed ?? 5), n = o.n ?? 6000, box = o.box || [-12, -12, 12, 12];
  const m = new THREE.MeshStandardMaterial({ vertexColors: true, side: THREE.DoubleSide, roughness: 0.85 });
  m.onBeforeCompile = sh => { sh.uniforms.uT = T; sh.vertexShader = 'uniform float uT;\n' + sh.vertexShader.replace('#include <begin_vertex>', `#include <begin_vertex>
      #ifdef USE_INSTANCING
        vec3 ip = vec3(instanceMatrix[3][0], 0.0, instanceMatrix[3][2]); float w = sin(uT * 2.2 + ip.x * 0.9 + ip.z * 0.5) * 0.6 + sin(uT * 4.1 + ip.x * 2.3) * 0.25;
        transformed.x += w * 0.04 * uv.y * uv.y; transformed.z += w * 0.02 * uv.y * uv.y;
      #endif`); };
  const geo = bladeClump(r), im = new THREE.InstancedMesh(geo, m, n); im.castShadow = false; im.receiveShadow = true; im.frustumCulled = false;
  const M4 = new THREE.Matrix4(), Q = new THREE.Quaternion(); let k = 0, tries = 0;
  while (k < n && tries < n * 40) { tries++; const x = lerp(box[0], box[2], r()), z = lerp(box[1], box[3], r()); if (r() > place(x, z)) continue; const s = (0.6 + r() * 0.9) * (o.scale ?? 1);
    Q.setFromAxisAngle(new V3(0, 1, 0), r() * 6.28); M4.compose(new V3(x, 0, z), Q, new V3(s, s * (0.7 + r() * 0.6), s)); im.setMatrixAt(k++, M4); }
  im.count = k; E.scene.add(im); return im; }

// ------------------------------------------------------------------ dust in the light
export function dust(E, o = {}) { clock(E); const n = o.n ?? 500, box = o.box || [-4, 0.2, -2, 4, 3, 4], r = rng(o.seed ?? 3); const pos = new Float32Array(n * 3), seed = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) { seed[i * 3] = r(); seed[i * 3 + 1] = r(); seed[i * 3 + 2] = r(); }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('seed', new THREE.BufferAttribute(seed, 3));
  let c = null; if (browser) { c = document.createElement('canvas'); c.width = c.height = 64; } if (c) { const x = c.getContext('2d'); const gr = x.createRadialGradient(32, 32, 0, 32, 32, 32); gr.addColorStop(0, 'rgba(255,255,255,1)'); gr.addColorStop(0.35, 'rgba(255,255,255,.45)'); gr.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = gr; x.fillRect(0, 0, 64, 64); }
  const m = new THREE.PointsMaterial({ size: o.size ?? 0.012, map: c ? new THREE.CanvasTexture(c) : null, color: new THREE.Color(...(o.color || [1.6, 1.3, 0.9])), transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true, opacity: o.opacity ?? 0.55 });
  const P = new THREE.Points(g, m); P.frustumCulled = false; E.scene.add(P);
  E.updaters.push(t => { for (let i = 0; i < n; i++) { const a = seed[i * 3], b = seed[i * 3 + 1], cc = seed[i * 3 + 2];
      pos[i * 3] = lerp(box[0], box[3], (a + t * 0.012 * (0.5 + b) + 0.03 * Math.sin(t * 0.7 + cc * 9)) % 1);
      pos[i * 3 + 1] = lerp(box[1], box[4], (b + t * 0.006 * (cc - 0.4) + 1 + 0.02 * Math.sin(t * 0.5 + a * 7)) % 1);
      pos[i * 3 + 2] = lerp(box[2], box[5], (cc + 0.02 * Math.sin(t * 0.4 + b * 5)) % 1); } g.attributes.position.needsUpdate = true; });
  return P; }

// ------------------------------------------------------------------ the far side: a boundary wall round the ground, rooftops and tree masses beyond it in the haze
export function horizon(E, o = {}) { const r = rng(o.seed ?? 9), G = new THREE.Group(); E.scene.add(G); const R0 = o.wall ?? 27, cx = o.at?.[0] ?? 0, cz = o.at?.[1] ?? 0;
  const wallM = new THREE.MeshStandardMaterial({ color: '#dcbc80', roughness: 0.95 }), band = new THREE.MeshStandardMaterial({ color: '#8c3b2a', roughness: 0.95 }), cap = new THREE.MeshStandardMaterial({ color: '#ece0c4', roughness: 0.9 });
  const seg = 48; for (let i = 0; i < seg; i++) { const a0 = i / seg * Math.PI * 2, a1 = (i + 1) / seg * Math.PI * 2, x0 = cx + Math.cos(a0) * R0, z0 = cz + Math.sin(a0) * R0, x1 = cx + Math.cos(a1) * R0, z1 = cz + Math.sin(a1) * R0, L = Math.hypot(x1 - x0, z1 - z0), mx = (x0 + x1) / 2, mz = (z0 + z1) / 2, ang = Math.atan2(z1 - z0, x1 - x0);
    for (const [mm, h, y, d] of [[wallM, 2.2, 1.1, 0.25], [band, 0.6, 0.3, 0.27], [cap, 0.08, 2.24, 0.34]]) { const b = new THREE.Mesh(new THREE.BoxGeometry(L + 0.05, h, d), mm); b.position.set(mx, y, mz); b.rotation.y = -ang; b.receiveShadow = true; G.add(b); } }
  const houseC = ['#e8d8b8', '#d9c29a', '#c9d6c0', '#e6c8c0', '#d8d0c0', '#efe2c8', '#c8c0b0'];
  for (let i = 0; i < (o.houses ?? 40); i++) { const a = r() * Math.PI * 2, d = R0 + 6 + r() * 26, w = 4 + r() * 6, h = 3 + Math.floor(r() * 3) * 3, dp = 4 + r() * 5;
    const b = new THREE.Mesh(new THREE.BoxGeometry(w, h, dp), new THREE.MeshStandardMaterial({ color: houseC[i % houseC.length], roughness: 0.95 })); b.position.set(cx + Math.cos(a) * d, h / 2, cz + Math.sin(a) * d); b.rotation.y = r() * 3; G.add(b);
    if (r() < 0.6) { const t = new THREE.Mesh(new THREE.CylinderGeometry(0.5, 0.5, 1.0, 12), new THREE.MeshStandardMaterial({ color: '#1d1f22', roughness: 0.5 })); t.position.set(b.position.x + (r() - 0.5) * w * 0.5, h + 0.5, b.position.z); G.add(t); } }
  return G; }
/** the trees beyond the wall: real trees with lighter crowns (call after horizon) */
export async function farTrees(E, o = {}) { const r = rng(o.seed ?? 19), R0 = o.wall ?? 27, cx = o.at?.[0] ?? 0, cz = o.at?.[1] ?? 0; for (let i = 0; i < (o.n ?? 26); i++) { const a = r() * Math.PI * 2, d = R0 + 3 + r() * 22; await tree(E, { at: [cx + Math.cos(a) * d, cz + Math.sin(a) * d], h: 6 + r() * 3, spread: 3 + r() * 1.5, seed: 100 + i, leaves: 1800, tint: r() < 0.5 ? '#e6f0d8' : '#ffffff' }); } }

// ------------------------------------------------------------------ birds crossing the sky (crows, small and far), wings beating
export function birds(E, o = {}) { clock(E); const r = rng(o.seed ?? 21), n = o.n ?? 7, G = new THREE.Group(); E.scene.add(G); const m = new THREE.MeshBasicMaterial({ color: '#1a1714', side: THREE.DoubleSide });
  const B = []; for (let i = 0; i < n; i++) { const b = new THREE.Group(); const body = new THREE.Mesh(new THREE.SphereGeometry(0.09, 8, 6), m); body.scale.set(1, 0.8, 2.2); b.add(body);
    const wings = []; for (const s of [-1, 1]) { const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0.1, 0, 0, -0.12, s * 0.42, 0, -0.08], 3)); const w = new THREE.Mesh(g, m); b.add(w); wings.push(w); }
    G.add(b); B.push({ b, wings, p0: new V3(...(o.from || [-40, 14, -20])).add(new V3(r() * 6, r() * 4, r() * 8)), v: new V3(...(o.dir || [1, 0.02, 0.25])).normalize().multiplyScalar(4 + r() * 2), ph: r() * 6, f: 5 + r() * 2 }); }
  E.updaters.push(t => { for (const q of B) { q.b.position.copy(q.p0).addScaledVector(q.v, t); q.b.lookAt(q.b.position.clone().add(q.v)); const a = Math.sin(t * q.f + q.ph) * 0.7; q.wings[0].rotation.z = a; q.wings[1].rotation.z = -a; } });
  return G; }
