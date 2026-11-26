// CodeScape engine for Cine: physically lit three.js scenes, sky-based reflections, shadows, depth of field,
// bloom, ACES grade, grain, FXAA, and a 2D overlay for titles. Same contract as the Cine engine (shot.html).
import * as THREE from './three.module.js';
export { THREE };
export const V3 = THREE.Vector3;
export const clamp = (x, a, b) => Math.min(b, Math.max(a, x)), lerp = (a, b, t) => a + (b - a) * t;
export const sm = t => { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); };
export const sm2 = t => { t = clamp(t, 0, 1); return t * t * t * (t * (6 * t - 15) + 10); };
export const seg = (t, a, b) => clamp((t - a) / (b - a), 0, 1);
export function rng(seed) { let s = seed >>> 0; return () => { s = (s + 0x6D2B79F5) >>> 0; let t = Math.imul(s ^ (s >>> 15), 1 | s); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
export async function post(path, body) { for (let i = 0; i < 4; i++) { try { const r = await fetch(path, { method: 'POST', body }); if (r.ok) return; } catch (e) { } await new Promise(r => setTimeout(r, 300)); } }
export const log = (...a) => post('/log', a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' '));

const FSQ = `varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0., 1.); }`;
const quad = new THREE.PlaneGeometry(2, 2);

export class Engine {
  constructor(canvas, W, H) {
    this.W = W; this.H = H; this.soft = false; this.updaters = [];
    const r = this.renderer = new THREE.WebGLRenderer({ canvas, antialias: false, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
    r.setPixelRatio(1); r.setSize(W, H, false); r.outputColorSpace = THREE.LinearSRGBColorSpace; r.toneMapping = THREE.NoToneMapping;
    r.shadowMap.enabled = true; r.shadowMap.type = THREE.PCFSoftShadowMap;
    this.gl = r.getContext();
    this.scene = new THREE.Scene();
    this.cam = new THREE.PerspectiveCamera(40, W / H, 0.02, 400);
    // render targets: scene (float, with depth), bloom chain, graded image
    const rt = (w, h, depth) => { const t = new THREE.WebGLRenderTarget(w, h, { type: THREE.HalfFloatType, minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter }); if (depth) { t.depthTexture = new THREE.DepthTexture(w, h); t.depthTexture.type = THREE.UnsignedIntType; } return t; };
    this.rtScene = rt(W, H, true); this.rtA = rt(W, H); this.b1 = rt(W >> 2, H >> 2); this.b2 = rt(W >> 2, H >> 2); this.b3 = rt(W >> 3, H >> 3); this.b4 = rt(W >> 3, H >> 3);
    this.postScene = new THREE.Scene(); this.postCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
    this.postMesh = new THREE.Mesh(quad, null); this.postMesh.frustumCulled = false; this.postScene.add(this.postMesh);
    this.mBright = new THREE.ShaderMaterial({ uniforms: { t: { value: null }, th: { value: 1.0 } }, vertexShader: FSQ, depthTest: false,
      fragmentShader: `uniform sampler2D t; uniform float th; varying vec2 vUv; void main(){ vec3 c = texture2D(t, vUv).rgb; float l = max(c.r, max(c.g, c.b)); gl_FragColor = vec4(c * smoothstep(th, th * 2.2, l), 1.); }` });
    this.mBlur = new THREE.ShaderMaterial({ uniforms: { t: { value: null }, dir: { value: new THREE.Vector2() } }, vertexShader: FSQ, depthTest: false,
      fragmentShader: `uniform sampler2D t; uniform vec2 dir; varying vec2 vUv; void main(){ vec3 s = texture2D(t, vUv).rgb * 0.227;
        s += (texture2D(t, vUv + dir * 1.385).rgb + texture2D(t, vUv - dir * 1.385).rgb) * 0.316; s += (texture2D(t, vUv + dir * 3.23).rgb + texture2D(t, vUv - dir * 3.23).rgb) * 0.070; gl_FragColor = vec4(s, 1.); }` });
    this.P = { uGrade: { value: new THREE.Vector4(1, 1, 1.05, 0.5) } };
    this.L = { uRimCol: { value: new THREE.Color() }, uLightCol: { value: new THREE.Color() }, uAmbCol: { value: new THREE.Color() } }; // shot.html debug hooks
    this.mFinal = new THREE.ShaderMaterial({ depthTest: false,
      uniforms: { tC: { value: null }, tD: { value: null }, tB1: { value: null }, tB2: { value: null }, uRes: { value: new THREE.Vector2(W, H) }, uNear: { value: 0.02 }, uFar: { value: 400 },
        uFocus: { value: 5 }, uDof: { value: 0 }, uMaxBlur: { value: 14 }, uExp: { value: 1 }, uGrade: this.P.uGrade, uT: { value: 0 }, uVig: { value: 0.35 }, uTint: { value: new THREE.Vector3(1, 1, 1) }, uLift: { value: new THREE.Vector3(0, 0, 0) }, uFade: { value: 1 }, uFlash: { value: 0 } },
      vertexShader: FSQ,
      fragmentShader: `uniform sampler2D tC, tD, tB1, tB2; uniform vec2 uRes; uniform float uNear, uFar, uFocus, uDof, uMaxBlur, uExp, uT, uVig, uFade, uFlash; uniform vec4 uGrade; uniform vec3 uTint, uLift; varying vec2 vUv;
        float lin(float d){ float z = d * 2.0 - 1.0; return 2.0 * uNear * uFar / (uFar + uNear - z * (uFar - uNear)); }
        float coc(vec2 uv){ float d = lin(texture2D(tD, uv).r); return clamp(uDof * abs(d - uFocus) / (d * uFocus) * uRes.y / 1000.0, 0.0, uMaxBlur); }
        float h(vec2 p){ return fract(sin(dot(p, vec2(12.9898, 78.233)) + uT * 3.7) * 43758.5453); }
        vec3 aces(vec3 x){ return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
        void main(){
          vec3 c = texture2D(tC, vUv).rgb;
          if (uDof > 0.0) { float r0 = coc(vUv); if (r0 > 0.35) { vec3 s = c; float wsum = 1.0; float ga = 2.39996;
              for (int i = 1; i < 24; i++) { float fi = float(i); float rr = sqrt(fi / 24.0) * r0; vec2 o = vec2(cos(fi * ga), sin(fi * ga)) * rr / uRes;
                float rs = coc(vUv + o); float w = smoothstep(rr - 0.5, rr + 0.5, max(rs, r0 * 0.6)); s += texture2D(tC, vUv + o).rgb * w; wsum += w; }
              c = s / wsum; } }
          c += (texture2D(tB1, vUv).rgb * 0.6 + texture2D(tB2, vUv).rgb * 0.8) * uGrade.w;
          c *= uExp * uTint;
          c = aces(c * uGrade.x);
          c = pow(c, vec3(1.0 / 2.2));
          c = pow(c, vec3(uGrade.y));
          float l = dot(c, vec3(0.299, 0.587, 0.114)); c = mix(vec3(l), c, uGrade.z);
          c = c + uLift * (1.0 - c);
          vec2 q = (vUv - 0.5) * vec2(uRes.x / uRes.y, 1.0); c *= mix(1.0, smoothstep(1.05, 0.25, length(q) * 1.15), uVig);
          c += (h(vUv * uRes) - 0.5) * 0.022;
          c = mix(c, vec3(1.0), uFlash);
          gl_FragColor = vec4(c * uFade, 1.0); }` });
    this.mFxaa = new THREE.ShaderMaterial({ depthTest: false, uniforms: { t: { value: null }, uRes: { value: new THREE.Vector2(W, H) }, tHud: { value: null }, uHud: { value: 0 } }, vertexShader: FSQ,
      fragmentShader: `uniform sampler2D t, tHud; uniform vec2 uRes; uniform float uHud; varying vec2 vUv;
        void main(){ vec2 px = 1.0 / uRes; vec3 rgbNW = texture2D(t, vUv + vec2(-1., -1.) * px).rgb, rgbNE = texture2D(t, vUv + vec2(1., -1.) * px).rgb, rgbSW = texture2D(t, vUv + vec2(-1., 1.) * px).rgb, rgbSE = texture2D(t, vUv + vec2(1., 1.) * px).rgb, rgbM = texture2D(t, vUv).rgb;
          vec3 lu = vec3(0.299, 0.587, 0.114); float lNW = dot(rgbNW, lu), lNE = dot(rgbNE, lu), lSW = dot(rgbSW, lu), lSE = dot(rgbSE, lu), lM = dot(rgbM, lu);
          float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE))), lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
          vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), ((lNW + lSW) - (lNE + lSE)));
          float red = max((lNW + lNE + lSW + lSE) * 0.03125, 0.0078125); float rcp = 1.0 / (min(abs(dir.x), abs(dir.y)) + red);
          dir = clamp(dir * rcp, -8.0, 8.0) * px;
          vec3 A = 0.5 * (texture2D(t, vUv + dir * (1.0 / 3.0 - 0.5)).rgb + texture2D(t, vUv + dir * (2.0 / 3.0 - 0.5)).rgb);
          vec3 B = A * 0.5 + 0.25 * (texture2D(t, vUv - dir * 0.5).rgb + texture2D(t, vUv + dir * 0.5).rgb);
          float lB = dot(B, lu); vec3 c = (lB < lMin || lB > lMax) ? A : B;
          if (uHud > 0.0) { vec4 hd = texture2D(tHud, vec2(vUv.x, 1.0 - vUv.y)); c = mix(c, hd.rgb, hd.a); }
          gl_FragColor = vec4(c, 1.0); }` });
    // 2D overlay for titles and phone UI text drawn per frame
    this.hudCanvas = document.createElement('canvas'); this.hudCanvas.width = W; this.hudCanvas.height = H; this.hudCtx = this.hudCanvas.getContext('2d');
    this.hudTex = new THREE.CanvasTexture(this.hudCanvas); this.hudTex.colorSpace = THREE.NoColorSpace; this.hudTex.flipY = false; this.hudFn = null;
    this.pmrem = new THREE.PMREMGenerator(r);
  }
  /** environment lighting from a small scene (or a function that fills one) */
  setEnv(build, intensity = 1) { const s = new THREE.Scene(); build(s); const rt = this.pmrem.fromScene(s, 0.02, 0.1, 100); this.scene.environment = rt.texture; this.scene.environmentIntensity = intensity; return rt; }
  setCam(c) {
    const cam = this.cam; cam.position.set(...c.p); cam.up.set(...(c.up || [0, 1, 0])); cam.lookAt(new THREE.Vector3(...c.t)); if (c.roll) cam.rotateZ(c.roll);
    cam.fov = c.fov || 40; cam.near = c.near || 0.02; cam.far = c.far || 400; cam.updateProjectionMatrix();
    const u = this.mFinal.uniforms; u.uNear.value = cam.near; u.uFar.value = cam.far; u.uFocus.value = c.focus || 5; u.uDof.value = c.dof || 0; u.uMaxBlur.value = c.maxBlur || (this.soft ? 16 : 9);
    u.uExp.value = c.exp ?? 0.84; u.uVig.value = c.vig ?? 0.35; u.uFade.value = c.fade ?? 1; u.uFlash.value = c.flash || 0; if (c.grade) this.P.uGrade.value.set(...c.grade); if (c.tint) u.uTint.value.set(...c.tint); else u.uTint.value.set(1, 1, 1); if (c.lift) u.uLift.value.set(...c.lift); else u.uLift.value.set(0, 0, 0);
    this.mBright.uniforms.th.value = c.bloomTh || 1.0;
    this.hudFn = c.hud || null;
  }
  tick(t) { this.time = t; for (const f of this.updaters) f(t); }
  render(t) {
    this.tick(t); const r = this.renderer;
    r.setRenderTarget(this.rtScene); r.setClearColor(0x000000, 1); r.clear(); r.render(this.scene, this.cam);
    const pm = this.postMesh, run = (m, target) => { pm.material = m; r.setRenderTarget(target); r.render(this.postScene, this.postCam); };
    this.mBright.uniforms.t.value = this.rtScene.texture; run(this.mBright, this.b1);
    const B = this.mBlur.uniforms;
    for (let i = 0; i < 2; i++) { B.t.value = this.b1.texture; B.dir.value.set((1 + i * 1.5) / this.b1.width, 0); run(this.mBlur, this.b2); B.t.value = this.b2.texture; B.dir.value.set(0, (1 + i * 1.5) / this.b1.height); run(this.mBlur, this.b1); }
    B.t.value = this.b1.texture; B.dir.value.set(1 / this.b3.width, 0); run(this.mBlur, this.b3);
    for (let i = 0; i < 3; i++) { B.t.value = this.b3.texture; B.dir.value.set((1.5 + i * 2) / this.b3.width, 0); run(this.mBlur, this.b4); B.t.value = this.b4.texture; B.dir.value.set(0, (1.5 + i * 2) / this.b3.height); run(this.mBlur, this.b3); }
    const F = this.mFinal.uniforms; F.tC.value = this.rtScene.texture; F.tD.value = this.rtScene.depthTexture; F.tB1.value = this.b1.texture; F.tB2.value = this.b3.texture; F.uT.value = t;
    run(this.mFinal, this.rtA);
    const X = this.mFxaa.uniforms; X.t.value = this.rtA.texture;
    if (this.hudFn) { const g = this.hudCtx; g.clearRect(0, 0, this.W, this.H); this.hudFn(g, this.W, this.H, t); this.hudTex.needsUpdate = true; X.tHud.value = this.hudTex; X.uHud.value = 1; } else X.uHud.value = 0;
    run(this.mFxaa, null);
  }
  pixels() { const gl = this.gl; if (!this._px) this._px = new Uint8Array(this.W * this.H * 4); gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.readPixels(0, 0, this.W, this.H, gl.RGBA, gl.UNSIGNED_BYTE, this._px); return this._px; }
}
