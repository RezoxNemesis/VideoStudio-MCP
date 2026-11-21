// CLAY — a small stop-motion studio in code. Everything in the picture is a made thing: clay, felt, paper, tin,
// painted card, tiny bulbs. A real depth buffer (nothing is ever painted in the wrong order), soft shadows, a lens with
// a shallow field (the miniature look: a thin slice sharp, the rest melting), bloom on the little lamps, grain.
// Puppets move ON TWOS (12 poses a second) while the camera glides on ones — the way a motion-control rig shoots puppets.
import * as THREE from './three.module.js';
export { THREE };
export const post = (url, body) => fetch(url, { method: 'POST', body });
const LG = new URLSearchParams(location.search).get('lg') || 'x';
export const log = (s) => post('/log?name=' + LG, String(s));
export const V3 = (x = 0, y = 0, z = 0) => new THREE.Vector3(x, y, z);
export const lerp = (a, b, k) => a + (b - a) * k, clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
export const ease = (x) => { x = clamp(x); return x * x * (3 - 2 * x); }, easeIO = (x) => { x = clamp(x); return x * x * x * (x * (x * 6 - 15) + 10); };
export const hash = (n) => { const s = Math.sin(n * 127.1 + 311.7) * 43758.5453; return s - Math.floor(s); };

// ------------------------------------------------------------------ materials: clay, felt, paint, tin, paper
const NOISE = /* glsl */`
uniform float uBoil; uniform float uNS; uniform float uBump; uniform float uMottle; uniform float uFuzz; uniform float uRidge;
varying vec3 vOP;
float h31(vec3 p){ p = fract(p * 0.3183099 + .1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float vn(vec3 x){ vec3 i = floor(x), f = fract(x); f = f * f * (3. - 2. * f);
  return mix(mix(mix(h31(i), h31(i + vec3(1,0,0)), f.x), mix(h31(i + vec3(0,1,0)), h31(i + vec3(1,1,0)), f.x), f.y),
             mix(mix(h31(i + vec3(0,0,1)), h31(i + vec3(1,0,1)), f.x), mix(h31(i + vec3(0,1,1)), h31(i + vec3(1,1,1)), f.x), f.y), f.z); }
#ifdef LITE
float fbm(vec3 p){ return 0.66 * vn(p) + 0.34 * vn(p * 2.6 + 7.1); }
#else
float fbm(vec3 p){ return 0.58 * vn(p) + 0.29 * vn(p * 2.13 + 7.1) + 0.13 * vn(p * 4.7 + 3.3); }
#endif
`;
const mats = [];
/**
 * A handmade material. kind: 'clay' (lumps, thumb marks, a soft sheen), 'felt' (matte, a fuzzy rim of light),
 * 'paint' (brushed paint on card or wood: streaks), 'tin' (brushed metal), 'paper', 'glow' (a lit bulb).
 */
export function mat(color, kind = 'clay', o = {}) {
  const K = { clay: { rough: 0.62, metal: 0, ns: 34, bump: 0.5, mottle: 0.15, fuzz: 0.05, ridge: 0.42 }, felt: { rough: 0.96, metal: 0, ns: 150, bump: 0.5, mottle: 0.22, fuzz: 0.55, ridge: 0 },
    paint: { rough: 0.78, metal: 0, ns: 46, bump: 0.3, mottle: 0.2, fuzz: 0.02, ridge: 0.9 }, tin: { rough: 0.34, metal: 0.92, ns: 60, bump: 0.12, mottle: 0.1, fuzz: 0, ridge: 1 },
    paper: { rough: 0.9, metal: 0, ns: 260, bump: 0.07, mottle: 0.07, fuzz: 0.08, ridge: 0 }, plastic: { rough: 0.3, metal: 0, ns: 20, bump: 0.05, mottle: 0.03, fuzz: 0, ridge: 0 } }[kind] ?? {};
  if (kind === 'glow') { const m = new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(o.power ?? 3) }); m.toneMapped = false; return m; }
  const m = new THREE.MeshStandardMaterial({ color, roughness: o.rough ?? K.rough, metalness: o.metal ?? K.metal, map: o.map ?? null, transparent: !!o.opacity, opacity: o.opacity ?? 1, side: o.side ?? THREE.FrontSide,
    emissive: o.emissive ?? 0x000000, emissiveIntensity: o.emissiveIntensity ?? 1, emissiveMap: o.emissiveMap ?? null, alphaTest: o.alphaTest ?? 0 });
  const U = m.userData.U = { uBoil: { value: 0 }, uNS: { value: o.ns ?? K.ns }, uBump: { value: o.bump ?? K.bump }, uMottle: { value: o.mottle ?? K.mottle }, uFuzz: { value: o.fuzz ?? K.fuzz }, uRidge: { value: o.ridge ?? K.ridge } };
  m.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, U);
    sh.vertexShader = sh.vertexShader.replace('#include <common>', '#include <common>\nvarying vec3 vOP;').replace('#include <begin_vertex>', '#include <begin_vertex>\nvOP = position;');
    sh.fragmentShader = sh.fragmentShader.replace('#include <common>', '#include <common>\n' + (window.Q && window.Q.get('lite') ? '#define LITE\n' : '') + NOISE)
      .replace('#include <color_fragment>', `#include <color_fragment>
        vec3 qN = vOP * uNS + vec3(sin(uBoil * 12.9898), sin(uBoil * 78.233), sin(uBoil * 37.719)) * 0.16; float nA = fbm(qN);
        // thumb marks and brush streaks: fine ridges that wander
        #ifdef LITE
        float wv = vn(vOP * uNS * 0.27 + 3.1); float rd = uRidge > 0.01 ? (sin((vOP.y * 2.6 + vOP.x * 1.1 + vOP.z * 0.7) * uNS * 6.0 + wv * 24.0) * 0.5 + 0.5) * smoothstep(0.2, 0.75, wv) : 0.0;
        #else
        float wv = vn(vOP * uNS * 0.21 + 3.1) + 0.5 * vn(vOP * uNS * 0.47 + 9.7); float rd = sin((vOP.y * 2.6 + vOP.x * 1.1 + vOP.z * 0.7) * uNS * 6.0 + wv * 19.0) * 0.5 + 0.5; rd *= smoothstep(0.25, 0.7, vn(vOP * uNS * 0.33 + 5.5));
        #endif
        float hgt = nA + uRidge * 0.07 * rd;
        diffuseColor.rgb *= 1.0 + uMottle * (nA - 0.5) + uRidge * 0.035 * (rd - 0.5);`)
      .replace('#include <normal_fragment_maps>', `#include <normal_fragment_maps>
        { vec3 dpx = dFdx(vViewPosition), dpy = dFdy(vViewPosition); float dhx = dFdx(hgt), dhy = dFdy(hgt);
          vec3 r1 = cross(dpy, normal), r2 = cross(normal, dpx); float det = dot(dpx, r1);
          vec3 grad = sign(det) * (dhx * r1 + dhy * r2); normal = normalize(abs(det) * normal - uBump * 0.011 * grad); }`)
      .replace('#include <opaque_fragment>', `outgoingLight += totalDiffuse * uFuzz * pow(1.0 - saturate(dot(normalize(vViewPosition), normal)), 2.2) * 1.6;
        #include <opaque_fragment>`);
    if (o.patch) o.patch(sh);
  };
  m.customProgramCacheKey = () => 'clay' + (o.key ?? '') + (window.Q && window.Q.get('lite') ? 'L' : '');
  mats.push(m); return m;
}

// ------------------------------------------------------------------ shapes (sizes baked in: the noise keeps its grain)
export const ball = (rx, ry = rx, rz = rx, s = 28) => { const g = new THREE.SphereGeometry(1, s, Math.round(s * 0.7)); g.scale(rx, ry, rz); return g; };
/** a sausage from the origin down −y, `len` long */
export const limb = (r0, len, r1 = r0, s = 18) => { const pts = [], n = 8; for (let i = 0; i <= n; i++) { const a = -Math.PI / 2 + i / n * Math.PI / 2; pts.push(new THREE.Vector2(Math.cos(a) * r1, -len + Math.sin(a) * r1)); }
  for (let i = 0; i <= n; i++) { const a = i / n * Math.PI / 2; pts.push(new THREE.Vector2(Math.cos(a) * r0, Math.sin(a) * r0)); } return new THREE.LatheGeometry(pts, s); };
/** a box with soft edges, centred */
export const rbox = (w, h, d, r = 0.01, seg = 3) => { const s = new THREE.Shape(), x = w / 2 - r, y = h / 2 - r; s.moveTo(-x, -h / 2); s.lineTo(x, -h / 2); s.absarc(x, -y, r, -Math.PI / 2, 0); s.lineTo(w / 2, y); s.absarc(x, y, r, 0, Math.PI / 2); s.lineTo(-x, h / 2); s.absarc(-x, y, r, Math.PI / 2, Math.PI); s.lineTo(-w / 2, -y); s.absarc(-x, -y, r, Math.PI, Math.PI * 1.5);
  const b = Math.min(r, d / 2 - 0.0005), g = new THREE.ExtrudeGeometry(s, { depth: d - 2 * b, bevelEnabled: true, bevelSize: b, bevelThickness: b, bevelSegments: seg, curveSegments: 6 }); g.translate(0, 0, -(d - 2 * b) / 2); g.computeVertexNormals(); const P = g.attributes.position, U = g.attributes.uv; for (let i = 0; i < P.count; i++) U.setXY(i, P.getX(i) / w + 0.5, P.getY(i) / h + 0.5); return g; };
export const tube = (pts, r, seg = 24, rs = 8, closed = false) => new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts.map((p) => (p.isVector3 ? p : V3(...p))), closed, 'catmullrom', 0.5), seg, r, rs, closed);
export const mesh = (g, m, o = {}) => { const x = new THREE.Mesh(g, m); x.castShadow = o.cast ?? true; x.receiveShadow = o.recv ?? true; if (o.p) x.position.set(...o.p); if (o.r) x.rotation.set(...o.r); if (o.name) x.name = o.name; return x; };
export const group = (kids = [], o = {}) => { const g = new THREE.Group(); for (const k of kids) if (k) g.add(k); if (o.p) g.position.set(...o.p); if (o.r) g.rotation.set(...o.r); return g; };
/** a picture drawn in code as a texture: draw(g, w, h) */
export function paintTex(w, h, draw, o = {}) { const c = document.createElement('canvas'); c.width = w; c.height = h; const g = c.getContext('2d'); draw(g, w, h); const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 4; if (o.repeat) { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(...o.repeat); } t.userData.canvas = c; return t; }

// ------------------------------------------------------------------ the camera's own passes
const QUAD = new THREE.PlaneGeometry(2, 2), VS = 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0., 1.); }';
const pass = (fs, u) => new THREE.Mesh(QUAD, new THREE.ShaderMaterial({ vertexShader: VS, fragmentShader: fs, uniforms: u, depthTest: false, depthWrite: false }));
const LIN = 'uniform float uNear, uFar; float linz(float d){ float z = d * 2. - 1.; return 2. * uNear * uFar / (uFar + uNear - z * (uFar - uNear)); }';

export class Engine {
  constructor(canvas, W, H, o = {}) {
    this.W = W; this.H = H; this.t = 0;
    const r = this.renderer = new THREE.WebGLRenderer({ canvas, antialias: false, alpha: false, powerPreference: 'high-performance' });
    r.setPixelRatio(1); r.setSize(W, H, false); r.shadowMap.enabled = true; r.shadowMap.type = o.shadow === 'pcf' ? THREE.PCFShadowMap : o.shadow === 'vsm' ? THREE.VSMShadowMap : THREE.PCFSoftShadowMap; const TAPS = o.taps || (W >= 1000 ? 84 : 44); r.outputColorSpace = THREE.LinearSRGBColorSpace; r.toneMapping = THREE.NoToneMapping;
    this.scene = new THREE.Scene(); this.cam = new THREE.PerspectiveCamera(35, W / H, 0.06, 60);
    const HF = THREE.HalfFloatType, rt = (w, h, x = {}) => new THREE.WebGLRenderTarget(w, h, { type: HF, minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter, depthBuffer: false, ...x });
    this.rt = new THREE.WebGLRenderTarget(W, H, { type: HF, samples: o.msaa ?? 4, depthBuffer: true }); this.rt.depthTexture = new THREE.DepthTexture(W, H); this.rt.depthTexture.type = THREE.UnsignedIntType;
    const KQ = this.KQ = Math.max(1, W / 1080), w2 = Math.round(W / KQ / 2), h2 = Math.round(H / KQ / 2), w4 = w2 >> 1, h4 = h2 >> 1, w8 = w2 >> 2, h8 = h2 >> 2; this.hq = W >= 1400;
    this.rDof = rt(w2, h2); this.rB = [rt(w4, h4), rt(w4, h4), rt(w8, h8), rt(w8, h8)];
    this.rOut = new THREE.WebGLRenderTarget(W, H, { type: THREE.UnsignedByteType, depthBuffer: false });
    this.ovc = document.createElement('canvas'); this.ovc.width = W; this.ovc.height = H; this.og = this.ovc.getContext('2d'); this.ovt = new THREE.CanvasTexture(this.ovc); this.ovt.colorSpace = THREE.SRGBColorSpace;
    this.lens = { focus: 2, ap: 26, max: 16 }; this.look = { exposure: 1, sat: 1.06, lift: [0.0, 0.0, 0.012], gain: [1.02, 1.0, 0.97], vig: 0.42, grain: 0.05, bloom: 0.55, ca: 0.0012 };
    const res = { value: new THREE.Vector2(w2 * 2, h2 * 2) };
    // the lens: a gather of the picture inside each point's circle of confusion (half size), nearer things spill over
    this.pDof = pass(`${LIN} varying vec2 vUv; uniform sampler2D tS, tD; uniform vec2 uRes; uniform float uFocus, uAp, uMax, uSeed;
      float coc(float z){ return clamp(abs(z - uFocus) / z * uAp, 0., uMax); }
      void main(){ float zc = linz(texture2D(tD, vUv).r), cc = coc(zc); vec3 acc = texture2D(tS, vUv).rgb; float wsum = 1.; float mx = cc;
        float a0 = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))) + uSeed) * 6.2831853;
        for (int i = 1; i <= ${TAPS}; i++) { float fi = float(i), r = sqrt(fi / ${TAPS}.) * uMax, a = a0 + fi * 2.39996323; vec2 uv = vUv + vec2(cos(a), sin(a)) * r / uRes;
          float zs = linz(texture2D(tD, uv).r), cs = coc(zs); float w = zs > zc + 0.02 ? smoothstep(r - 1., r + 1., cc) : smoothstep(r - 1., r + 1., cs);
          vec3 c = texture2D(tS, uv).rgb; w *= 1.0 + 0.6 * smoothstep(1.5, 5.0, max(c.r, max(c.g, c.b))); acc += c * w; wsum += w; if (zs <= zc + 0.02) mx = max(mx, cs * step(r, cs)); }
        gl_FragColor = vec4(acc / wsum, mx / uMax); }`, { tS: { value: null }, tD: { value: null }, uRes: res, uFocus: { value: 2 }, uAp: { value: 20 }, uMax: { value: 14 }, uSeed: { value: 0 }, uNear: { value: 0.06 }, uFar: { value: 60 } });
    this.pBright = pass(`varying vec2 vUv; uniform sampler2D tS; uniform vec2 uPx; void main(){ vec3 c = vec3(0.); for (int i = -1; i <= 1; i += 2) for (int j = -1; j <= 1; j += 2) c += texture2D(tS, vUv + vec2(float(i), float(j)) * uPx).rgb; c *= 0.25; float l = max(c.r, max(c.g, c.b)); gl_FragColor = vec4(c * smoothstep(0.85, 1.6, l), 1.); }`, { tS: { value: null }, uPx: { value: new THREE.Vector2(1 / (w2 * 2), 1 / (h2 * 2)) } });
    this.pBlur = pass(`varying vec2 vUv; uniform sampler2D tS; uniform vec2 uDir; void main(){ vec3 c = texture2D(tS, vUv).rgb * 0.2270270; c += (texture2D(tS, vUv + uDir * 1.3846153).rgb + texture2D(tS, vUv - uDir * 1.3846153).rgb) * 0.3162162; c += (texture2D(tS, vUv + uDir * 3.2307692).rgb + texture2D(tS, vUv - uDir * 3.2307692).rgb) * 0.0702702; gl_FragColor = vec4(c, 1.); }`, { tS: { value: null }, uDir: { value: new THREE.Vector2() } });
    this.pOut = pass(`${LIN} varying vec2 vUv; uniform sampler2D tS, tD, tDof, tB1, tB2, tOv; uniform vec2 uRes; uniform float uFocus, uAp, uMax, uT, uExp, uSat, uVig, uGrain, uBloom, uCA, uKQ; uniform vec3 uLift, uGain;
      vec3 aces(vec3 x){ return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0., 1.); }
      float h21(vec2 p){ vec3 q = fract(vec3(p.xyx) * .1031); q += dot(q, q.yzx + 33.33); return fract((q.x + q.y) * q.z); }
      void main(){ vec2 d = vUv - 0.5; vec4 df = texture2D(tDof, vUv); float z = linz(texture2D(tD, vUv).r), cc = max(clamp(abs(z - uFocus) / z * uAp, 0., uMax), df.a * uMax);
        vec3 sharp = vec3(texture2D(tS, vUv + d * uCA).r, texture2D(tS, vUv).g, texture2D(tS, vUv - d * uCA).b);
        vec3 c = mix(sharp, df.rgb, smoothstep(0.6, 2.2, cc));
        c += (texture2D(tB1, vUv).rgb * 0.6 + texture2D(tB2, vUv).rgb * 0.8) * uBloom;
        c *= uExp; c *= 1.0 - uVig * smoothstep(0.25, 0.95, length(d * vec2(0.9, 1.0)) * 1.25);
        c = aces(c); c = pow(c, vec3(1. / 2.2));
        float l = dot(c, vec3(0.299, 0.587, 0.114)); c = mix(vec3(l), c, uSat); c = c * uGain + uLift * (1. - c);
        float g = h21(floor(gl_FragCoord.xy / uKQ) + floor(uT * 24.) * 17.13) - 0.5; c += g * uGrain * (1.0 - 0.6 * l);
        vec4 ov = texture2D(tOv, vUv); c = c * (1. - ov.a) + ov.rgb * ov.a; gl_FragColor = vec4(c, 1.); }`,
      { tS: { value: null }, tD: { value: null }, tDof: { value: null }, tB1: { value: null }, tB2: { value: null }, tOv: { value: this.ovt }, uRes: res, uFocus: { value: 2 }, uAp: { value: 20 }, uMax: { value: 14 }, uT: { value: 0 }, uExp: { value: 1 }, uSat: { value: 1 }, uVig: { value: 0.4 }, uGrain: { value: 0.05 }, uBloom: { value: 0.5 }, uCA: { value: 0.001 }, uKQ: { value: Math.max(1, Math.round(W / 1080)) }, uLift: { value: new THREE.Vector3() }, uGain: { value: new THREE.Vector3(1, 1, 1) }, uNear: { value: 0.06 }, uFar: { value: 60 } });
    this.oc = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1); this.ps = new THREE.Scene(); this.ticks = []; this.pre = []; // pre: passes drawn before the picture (a mirror for water …): fn(renderer, scene, camera, t)
  }
  /** cam: { pos, target, fov, roll?, focus?: metres | [x,y,z] (default: the target), ap?, near?, far? } */
  setCam(c) {
    const cam = this.cam; cam.fov = c.fov ?? 35; cam.near = c.near ?? 0.06; cam.far = c.far ?? 60; cam.position.set(...c.pos); cam.up.set(0, 1, 0); cam.lookAt(...c.target); if (c.roll) cam.rotateZ(c.roll * Math.PI / 180); cam.updateProjectionMatrix(); cam.updateMatrixWorld(true);
    const f = c.focus ?? c.target, fwd = new THREE.Vector3(); cam.getWorldDirection(fwd);
    this.lens.focus = typeof f === 'number' ? f : Math.max(0.1, V3(...f).sub(cam.position).dot(fwd)); if (c.ap !== undefined) this.lens.ap = c.ap;
  }
  overlay(draw) { const g = this.og; g.clearRect(0, 0, this.W, this.H); if (draw) draw(g, this.W, this.H); this.ovt.needsUpdate = true; }
  _run(p, target) { this.ps.clear(); this.ps.add(p); this.renderer.setRenderTarget(target); this.renderer.render(this.ps, this.oc); }
  render(t) {
    const r = this.renderer, L = this.lens, K = this.look, cam = this.cam; this.t = t;
    const boil = Math.floor(t * 12 + 1e-4); for (const m of mats) m.userData.U.uBoil.value = boil % 97; for (const f of this.ticks) f(t);
    r.setClearColor(this.clear ?? 0x05060c, 1); for (const f of this.pre) f(r, this.scene, cam, t);
    r.setRenderTarget(this.rt); r.clear(); r.render(this.scene, cam);
    const d = this.pDof.material.uniforms; d.tS.value = this.rt.texture; d.tD.value = this.rt.depthTexture; d.uFocus.value = L.focus; d.uAp.value = L.ap; d.uMax.value = L.max; d.uSeed.value = (boil * 0.618) % 1; d.uNear.value = cam.near; d.uFar.value = cam.far; this._run(this.pDof, this.rDof);
    const b = this.pBright.material.uniforms, bl = this.pBlur.material.uniforms, B = this.rB; b.tS.value = this.rt.texture; this._run(this.pBright, B[0]);
    const blur = (src, tmp, dst, w, h, k) => { bl.tS.value = src.texture; bl.uDir.value.set(k / w, 0); this._run(this.pBlur, tmp); bl.tS.value = tmp.texture; bl.uDir.value.set(0, k / h); this._run(this.pBlur, dst); };
    blur(B[0], B[1], B[0], B[0].width, B[0].height, 1.2); blur(B[0], B[3], B[2], B[2].width, B[2].height, 1.6); blur(B[2], B[3], B[2], B[2].width, B[2].height, 2.4);
    const u = this.pOut.material.uniforms; u.tS.value = this.rt.texture; u.tD.value = this.rt.depthTexture; u.tDof.value = this.rDof.texture; u.tB1.value = B[0].texture; u.tB2.value = B[2].texture; u.uFocus.value = L.focus; u.uAp.value = L.ap; u.uMax.value = L.max; u.uT.value = t;
    u.uExp.value = K.exposure; u.uSat.value = K.sat; u.uVig.value = K.vig; u.uGrain.value = K.grain; u.uBloom.value = K.bloom; u.uCA.value = K.ca; u.uLift.value.set(...K.lift); u.uGain.value.set(...K.gain); u.uNear.value = cam.near; u.uFar.value = cam.far;
    this._run(this.pOut, this.rOut); r.setRenderTarget(null);
  }
  pixels() { const px = new Uint8Array(this.W * this.H * 4); this.renderer.readRenderTargetPixels(this.rOut, 0, 0, this.W, this.H, px); return px; }
}
