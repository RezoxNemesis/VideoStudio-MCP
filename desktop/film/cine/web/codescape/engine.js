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
    this.rtScene = rt(W, H, true); this.rtA = rt(W, H); this.b1 = rt(W >> 2, H >> 2); this.b2 = rt(W >> 2, H >> 2); this.b3 = rt(W >> 3, H >> 3); this.b4 = rt(W >> 3, H >> 3); this.ao1 = rt(W >> 1, H >> 1); this.ao2 = rt(W >> 1, H >> 1); this.ry1 = rt(W >> 2, H >> 2); this.ry2 = rt(W >> 2, H >> 2);
    this.postScene = new THREE.Scene(); this.postCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
    this.postMesh = new THREE.Mesh(quad, null); this.postMesh.frustumCulled = false; this.postScene.add(this.postMesh);
    this.mBright = new THREE.ShaderMaterial({ uniforms: { t: { value: null }, th: { value: 1.0 } }, vertexShader: FSQ, depthTest: false,
      fragmentShader: `uniform sampler2D t; uniform float th; varying vec2 vUv; void main(){ vec3 c = texture2D(t, vUv).rgb; float sum = c.r + c.g + c.b; if (isnan(sum) || isinf(sum) || abs(sum) > 1.0e5) c = vec3(0.0);   /* one pixel that is not a number would be blurred into a black block 700 px wide */
        float l = max(c.r, max(c.g, c.b)); gl_FragColor = vec4(c * smoothstep(th, th * 2.2, l), 1.); }` });
    this.mBlur = new THREE.ShaderMaterial({ uniforms: { t: { value: null }, dir: { value: new THREE.Vector2() } }, vertexShader: FSQ, depthTest: false,
      fragmentShader: `uniform sampler2D t; uniform vec2 dir; varying vec2 vUv; void main(){ vec3 s = texture2D(t, vUv).rgb * 0.227;
        s += (texture2D(t, vUv + dir * 1.385).rgb + texture2D(t, vUv - dir * 1.385).rgb) * 0.316; s += (texture2D(t, vUv + dir * 3.23).rgb + texture2D(t, vUv - dir * 3.23).rgb) * 0.070; gl_FragColor = vec4(s, 1.); }` });
    /* ambient occlusion from the depth alone (half size): where surfaces crowd each other (under a chin, between feet and ground, in a fold) less sky reaches them */
    this.mAO = new THREE.ShaderMaterial({ depthTest: false, uniforms: { tD: { value: null }, uNear: { value: 0.02 }, uFar: { value: 400 }, uTan: { value: new THREE.Vector2(0.4, 0.4) }, uRes: { value: new THREE.Vector2(W >> 1, H >> 1) }, uR: { value: 0.3 }, uK: { value: 1 } }, vertexShader: FSQ,
      fragmentShader: `uniform sampler2D tD; uniform float uNear, uFar, uR, uK; uniform vec2 uTan, uRes; varying vec2 vUv;
        float lin(float d){ float z = d * 2.0 - 1.0; return 2.0 * uNear * uFar / (uFar + uNear - z * (uFar - uNear)); }
        vec3 pos(vec2 uv){ float z = lin(texture2D(tD, uv).r); return vec3((uv * 2.0 - 1.0) * uTan * z, -z); }
        void main(){ float d0 = texture2D(tD, vUv).r; if (d0 >= 0.99999) { gl_FragColor = vec4(1.0); return; }
          vec3 P = pos(vUv); vec2 px = 1.0 / uRes;
          vec3 pr = pos(vUv + vec2(px.x, 0.0)), pl = pos(vUv - vec2(px.x, 0.0)), pu = pos(vUv + vec2(0.0, px.y)), pd = pos(vUv - vec2(0.0, px.y));
          vec3 dx = abs(pr.z - P.z) < abs(P.z - pl.z) ? pr - P : P - pl, dy = abs(pu.z - P.z) < abs(P.z - pd.z) ? pu - P : P - pd;
          vec3 n = normalize(cross(dx, dy)); float z = -P.z;
          float rpx = uR / (z * uTan.y) * 0.5 * uRes.y; rpx = min(rpx, 90.0); if (rpx < 1.0) { gl_FragColor = vec4(1.0); return; }
          float a0 = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2832, sum = 0.0;
          for (int i = 0; i < 14; i++) { float fi = float(i) + 0.5; float rr = sqrt(fi / 14.0) * rpx; float a = a0 + fi * 2.39996;
            vec3 S = pos(vUv + vec2(cos(a), sin(a)) * rr * px); vec3 v = S - P; float vv = dot(v, v);
            sum += max(0.0, dot(v, n) - 0.004 * z) / (vv + 0.0004) * smoothstep(uR * uR * 4.0, uR * uR, vv); }
          float ao = clamp(1.0 - uK * sum * uR * 0.16, 0.0, 1.0); gl_FragColor = vec4(vec3(ao), 1.0); }` });
    /* a soft blur of it that does not leak across depth edges */
    this.mAOBlur = new THREE.ShaderMaterial({ depthTest: false, uniforms: { t: { value: null }, tD: { value: null }, uNear: { value: 0.02 }, uFar: { value: 400 }, dir: { value: new THREE.Vector2() } }, vertexShader: FSQ,
      fragmentShader: `uniform sampler2D t, tD; uniform float uNear, uFar; uniform vec2 dir; varying vec2 vUv;
        float lin(float d){ float z = d * 2.0 - 1.0; return 2.0 * uNear * uFar / (uFar + uNear - z * (uFar - uNear)); }
        void main(){ float z0 = lin(texture2D(tD, vUv).r), s = 0.0, w = 0.0;
          for (int i = -4; i <= 4; i++) { vec2 uv = vUv + dir * float(i); float z = lin(texture2D(tD, uv).r); float k = exp(-float(i * i) / 10.0) * exp(-abs(z - z0) / (0.03 * z0 + 0.01) ); s += texture2D(t, uv).r * k; w += k; }
          gl_FragColor = vec4(vec3(s / w), 1.0); }` });
    /* sun rays: what of the sky is bright, smeared out from the sun's place on the picture (quarter size) */
    this.mRays = new THREE.ShaderMaterial({ depthTest: false, uniforms: { tC: { value: null }, tD: { value: null }, uSun: { value: new THREE.Vector2(0.5, 0.8) }, uNear: { value: 0.02 }, uFar: { value: 400 }, uSkyD: { value: 250 }, uLen: { value: 0.55 }, uFirst: { value: 1 } }, vertexShader: FSQ,
      fragmentShader: `uniform sampler2D tC, tD; uniform vec2 uSun; uniform float uNear, uFar, uSkyD, uLen, uFirst; varying vec2 vUv;
        float lin(float d){ float z = d * 2.0 - 1.0; return 2.0 * uNear * uFar / (uFar + uNear - z * (uFar - uNear)); }
        vec3 src(vec2 uv){ if (uFirst < 0.5) return texture2D(tC, uv).rgb; vec3 c = texture2D(tC, uv).rgb; float sum = c.r + c.g + c.b; if (isnan(sum) || isinf(sum) || sum > 1.0e5) return vec3(0.0); float sky = step(uSkyD, lin(texture2D(tD, uv).r)); float l = max(c.r, max(c.g, c.b)); vec2 q = (uv - uSun) * vec2(1.78, 1.0); float near = exp(-dot(q, q) * 9.0); return c * sky * smoothstep(1.2, 3.0, l) * near; }   /* only the bright sky close to the sun throws rays */
        void main(){ vec2 d = (uSun - vUv) * uLen / 40.0; vec2 uv = vUv; vec3 s = vec3(0.0); float w = 1.0;
          for (int i = 0; i < 40; i++) { s += src(uv) * w; w *= 0.965; uv += d; }
          gl_FragColor = vec4(s / 22.0, 1.0); }` });
    this.P = { uGrade: { value: new THREE.Vector4(1, 1, 1.05, 0.5) } };
    this.L = { uRimCol: { value: new THREE.Color() }, uLightCol: { value: new THREE.Color() }, uAmbCol: { value: new THREE.Color() } }; // shot.html debug hooks
    this.mFinal = new THREE.ShaderMaterial({ depthTest: false,
      uniforms: { tC: { value: null }, tD: { value: null }, tB1: { value: null }, tB2: { value: null }, tAO: { value: null }, tRay: { value: null }, uAo: { value: 0 }, uHaze: { value: new THREE.Vector4(0, 0, 0, 250) }, uHazeCol: { value: new THREE.Vector3(0.8, 0.8, 0.85) }, uRays: { value: new THREE.Vector3(0, 0, 0) }, uSplitS: { value: new THREE.Vector3(0, 0, 0) }, uSplitH: { value: new THREE.Vector3(0, 0, 0) }, uCA: { value: 0 }, uRes: { value: new THREE.Vector2(W, H) }, uNear: { value: 0.02 }, uFar: { value: 400 },
        uFocus: { value: 5 }, uDof: { value: 0 }, uMaxBlur: { value: 14 }, uExp: { value: 1 }, uGrade: this.P.uGrade, uT: { value: 0 }, uVig: { value: 0.35 }, uTint: { value: new THREE.Vector3(1, 1, 1) }, uLift: { value: new THREE.Vector3(0, 0, 0) }, uFade: { value: 1 }, uFlash: { value: 0 } },
      vertexShader: FSQ,
      fragmentShader: `uniform sampler2D tC, tD, tB1, tB2, tAO, tRay; uniform float uAo, uCA; uniform vec4 uHaze; uniform vec3 uHazeCol, uRays, uSplitS, uSplitH; uniform vec2 uRes; uniform float uNear, uFar, uFocus, uDof, uMaxBlur, uExp, uT, uVig, uFade, uFlash; uniform vec4 uGrade; uniform vec3 uTint, uLift; varying vec2 vUv;
        float lin(float d){ float z = d * 2.0 - 1.0; return 2.0 * uNear * uFar / (uFar + uNear - z * (uFar - uNear)); }
        float coc(vec2 uv){ float d = lin(texture2D(tD, uv).r); return clamp(uDof * abs(d - uFocus) / (d * uFocus) * uRes.y / 1000.0, 0.0, uMaxBlur); }
        float h(vec2 p){ return fract(sin(dot(p, vec2(12.9898, 78.233)) + uT * 3.7) * 43758.5453); }
        vec3 aces(vec3 x){ return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
        vec3 scn(vec2 uv){ vec3 c = texture2D(tC, uv).rgb; float sum = c.r + c.g + c.b; return (isnan(sum) || isinf(sum) || abs(sum) > 1.0e5) ? vec3(0.0) : c; }   /* the scene, with any pixel that is not a number put out */
        vec3 base(vec2 uv){ vec3 c = scn(uv); if (uCA > 0.0) { vec2 o = (uv - 0.5) * uCA; c.r = scn(uv + o).r; c.b = scn(uv - o).b; }
          float d = lin(texture2D(tD, uv).r);
          if (uAo > 0.0) { float ao = texture2D(tAO, uv).r; float l = dot(c, vec3(0.299, 0.587, 0.114)); c *= mix(1.0, ao, uAo * (1.0 - 0.55 * smoothstep(0.35, 1.6, l))); }
          if (uHaze.z > 0.0 && d < uHaze.w) c = mix(c, uHazeCol, uHaze.z * (1.0 - exp(-max(0.0, d - uHaze.x) * uHaze.y)));
          return c; }
        void main(){
          vec3 c = base(vUv);
          if (uDof > 0.0) { float r0 = coc(vUv); if (r0 > 0.35) { vec3 s = c; float wsum = 1.0; float ga = 2.39996;
              for (int i = 1; i < 24; i++) { float fi = float(i); float rr = sqrt(fi / 24.0) * r0; vec2 o = vec2(cos(fi * ga), sin(fi * ga)) * rr / uRes;
                float rs = coc(vUv + o); float w = smoothstep(rr - 0.5, rr + 0.5, max(rs, r0 * 0.6)); s += base(vUv + o) * w; wsum += w; }
              c = s / wsum; } }
          c += (texture2D(tB1, vUv).rgb * 0.6 + texture2D(tB2, vUv).rgb * 0.8) * uGrade.w;
          if (uRays.x > 0.0) c += texture2D(tRay, vUv).rgb * uRays.x;
          c *= uExp * uTint;
          c = aces(c * uGrade.x);
          c = pow(c, vec3(1.0 / 2.2));
          c = pow(c, vec3(uGrade.y));
          float l = dot(c, vec3(0.299, 0.587, 0.114)); c = mix(vec3(l), c, uGrade.z);
          c += uSplitS * (1.0 - l) * (1.0 - l) + uSplitH * l * l;   /* split tone: a colour in the shadows, another in the light */
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
    /* the new looks (all off unless the shot asks): ao 0…1 (+ aoR metres), haze [start, density, max] + hazeCol, sun [x,y,z] (a far point) + rays strength + rayCol, split [r,g,b, r,g,b], ca (lens fringe) */
    u.uAo.value = c.ao || 0; this.mAO.uniforms.uR.value = c.aoR || 0.3; this.mAO.uniforms.uK.value = c.aoK || 1;
    if (c.haze) { u.uHaze.value.set(c.haze[0], c.haze[1], c.haze[2], c.skyD || 250); u.uHazeCol.value.set(...(c.hazeCol || [0.8, 0.8, 0.85])); } else u.uHaze.value.set(0, 0, 0, 250);
    this.raysOn = false; if (c.sun && c.rays) { const v = new THREE.Vector3(...c.sun).project(cam); const ahead = new THREE.Vector3(...c.sun).sub(cam.position).dot(cam.getWorldDirection(new THREE.Vector3())) > 0;
      if (ahead) { this.raysOn = true; this.mRays.uniforms.uSun.value.set(v.x * 0.5 + 0.5, v.y * 0.5 + 0.5); const rc = c.rayCol || [1, 0.85, 0.6]; const off = Math.max(0, Math.hypot(v.x, v.y) - 1.2); u.uRays.value.set(c.rays * Math.exp(-off * 2), 0, 0); this.rayCol = rc; } }
    if (!this.raysOn) u.uRays.value.set(0, 0, 0);
    this.mRays.uniforms.uSkyD.value = c.skyD || 250; this.mRays.uniforms.uLen.value = c.rayLen || 0.55;
    if (c.split) { u.uSplitS.value.set(c.split[0], c.split[1], c.split[2]); u.uSplitH.value.set(c.split[3], c.split[4], c.split[5]); } else { u.uSplitS.value.set(0, 0, 0); u.uSplitH.value.set(0, 0, 0); }
    u.uCA.value = c.ca || 0;
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
    const cam = this.cam, tanY = Math.tan(cam.fov * Math.PI / 360);
    if (this.mFinal.uniforms.uAo.value > 0) { const A = this.mAO.uniforms; A.tD.value = this.rtScene.depthTexture; A.uNear.value = cam.near; A.uFar.value = cam.far; A.uTan.value.set(tanY * cam.aspect, tanY); run(this.mAO, this.ao1);
      const Bl = this.mAOBlur.uniforms; Bl.tD.value = this.rtScene.depthTexture; Bl.uNear.value = cam.near; Bl.uFar.value = cam.far; Bl.t.value = this.ao1.texture; Bl.dir.value.set(1.6 / this.ao1.width, 0); run(this.mAOBlur, this.ao2); Bl.t.value = this.ao2.texture; Bl.dir.value.set(0, 1.6 / this.ao1.height); run(this.mAOBlur, this.ao1); }
    if (this.raysOn) { const Rr = this.mRays.uniforms; Rr.tD.value = this.rtScene.depthTexture; Rr.uNear.value = cam.near; Rr.uFar.value = cam.far; Rr.tC.value = this.rtScene.texture; Rr.uFirst.value = 1; run(this.mRays, this.ry1); Rr.tC.value = this.ry1.texture; Rr.uFirst.value = 0; const L0 = Rr.uLen.value; Rr.uLen.value = L0 * 0.35; run(this.mRays, this.ry2); Rr.uLen.value = L0; }
    const F = this.mFinal.uniforms; F.tAO.value = this.ao1.texture; F.tRay.value = this.ry2.texture; if (this.raysOn) { const rc = this.rayCol; F.uRays.value.set(F.uRays.value.x, 0, 0); this.mFinal.uniforms.uRays.value.x = F.uRays.value.x; } F.tC.value = this.rtScene.texture; F.tD.value = this.rtScene.depthTexture; F.tB1.value = this.b1.texture; F.tB2.value = this.b3.texture; F.uT.value = t;
    run(this.mFinal, this.rtA);
    const X = this.mFxaa.uniforms; X.t.value = this.rtA.texture;
    if (this.hudFn) { const g = this.hudCtx; g.clearRect(0, 0, this.W, this.H); this.hudFn(g, this.W, this.H, t); this.hudTex.needsUpdate = true; X.tHud.value = this.hudTex; X.uHud.value = 1; } else X.uHud.value = 0;
    run(this.mFxaa, null);
  }
  pixels() { const gl = this.gl; if (!this._px) this._px = new Uint8Array(this.W * this.H * 4); gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.readPixels(0, 0, this.W, this.H, gl.RGBA, gl.UNSIGNED_BYTE, this._px); return this._px; }
}
