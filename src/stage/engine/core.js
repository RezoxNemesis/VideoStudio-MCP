// Film engine core: renderer, HDR post chain (atmosphere, depth of field, bloom, grade, grain), maths helpers.
import * as THREE from 'three';
export { THREE };
export const FPS = 24;

/* ---------- deterministic maths ---------- */
export function mulberry(seed) { let a = seed >>> 0; return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
export const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
export const lerp = (a, b, t) => a + (b - a) * t;
export const smooth = (a, b, x) => { const t = clamp((x - a) / (b - a)); return t * t * (3 - 2 * t); };
export const ease = {
  lin: t => t, in: t => t * t, out: t => 1 - (1 - t) * (1 - t), io: t => t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2,
  io3: t => t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2, out3: t => 1 - Math.pow(1 - t, 3), out5: t => 1 - Math.pow(1 - t, 5), in3: t => t * t * t,
  back: t => { const c = 1.70158; return 1 + (c + 1) * Math.pow(t - 1, 3) + c * Math.pow(t - 1, 2); },
};
export const seg = (t, a, b, e = ease.io) => e(clamp((t - a) / (b - a)));
function h3(x, y, z) { let n = Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(z, 2147483647) | 0; n = Math.imul(n ^ n >>> 13, 1274126177); return ((n ^ n >>> 16) >>> 0) / 4294967296; }
export function vnoise(x, y = 0, z = 0) {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z); let xf = x - xi, yf = y - yi, zf = z - zi;
  xf = xf * xf * (3 - 2 * xf); yf = yf * yf * (3 - 2 * yf); zf = zf * zf * (3 - 2 * zf);
  const l = (a, b, t) => a + (b - a) * t;
  return l(l(l(h3(xi, yi, zi), h3(xi + 1, yi, zi), xf), l(h3(xi, yi + 1, zi), h3(xi + 1, yi + 1, zi), xf), yf),
    l(l(h3(xi, yi, zi + 1), h3(xi + 1, yi, zi + 1), xf), l(h3(xi, yi + 1, zi + 1), h3(xi + 1, yi + 1, zi + 1), xf), yf), zf);
}
export function fbm(x, y = 0, z = 0, oct = 5, lac = 2, gain = .5) { let a = .5, f = 1, s = 0, n = 0; for (let i = 0; i < oct; i++) { s += a * vnoise(x * f, y * f, z * f); n += a; a *= gain; f *= lac; } return s / n; }
export const ridged = (x, y, z = 0, oct = 5) => { let a = .5, f = 1, s = 0, n = 0; for (let i = 0; i < oct; i++) { const v = 1 - Math.abs(vnoise(x * f, y * f, z * f) * 2 - 1); s += a * v * v; n += a; a *= .5; f *= 2.03; } return s / n; };
/** smooth wobble for hand-held camera / idle motion; deterministic in t */
export const wob = (t, seed = 0, speed = 1) => fbm(t * speed + seed * 17.13, seed * 3.7, 0, 3) * 2 - 1;

/* ---------- GLSL shared chunks ---------- */
export const GLSL_NOISE = /* glsl */`
float hash13(vec3 p){ p=fract(p*.1031); p+=dot(p,p.zyx+31.32); return fract((p.x+p.y)*p.z); }
float hash12(vec2 p){ vec3 p3=fract(vec3(p.xyx)*.1031); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }
float vnoise3(vec3 x){ vec3 i=floor(x), f=fract(x); f=f*f*(3.-2.*f);
  return mix(mix(mix(hash13(i),hash13(i+vec3(1,0,0)),f.x),mix(hash13(i+vec3(0,1,0)),hash13(i+vec3(1,1,0)),f.x),f.y),
             mix(mix(hash13(i+vec3(0,0,1)),hash13(i+vec3(1,0,1)),f.x),mix(hash13(i+vec3(0,1,1)),hash13(i+vec3(1,1,1)),f.x),f.y),f.z); }
float fbm3(vec3 p){ float a=.5,s=0.; for(int i=0;i<4;i++){ s+=a*vnoise3(p); p=p*2.03+vec3(7.1,3.3,5.7); a*=.5; } return s/.9375; }
float fbm3b(vec3 p){ float a=.5,s=0.; for(int i=0;i<2;i++){ s+=a*vnoise3(p); p=p*2.03+vec3(7.1,3.3,5.7); a*=.5; } return s/.75; }
`;

/* ---------- renderer + post ---------- */
const FS_VERT = /* glsl */`out vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0., 1.); }`;
function pass(frag, uniforms = {}) {
  return new THREE.RawShaderMaterial({ glslVersion: THREE.GLSL3, uniforms, depthTest: false, depthWrite: false,
    vertexShader: `precision highp float; in vec3 position; in vec2 uv; ${FS_VERT}`, fragmentShader: `precision highp float; precision highp sampler2D; in vec2 vUv; out vec4 o;\n${frag}` });
}

export class Film {
  constructor(canvas, opts = {}) {
    const r = this.renderer = new THREE.WebGLRenderer({ canvas, antialias: false, preserveDrawingBuffer: true, powerPreference: 'high-performance', alpha: false });
    r.setPixelRatio(1); r.shadowMap.enabled = true; r.shadowMap.type = THREE.PCFShadowMap;
    r.toneMapping = THREE.NoToneMapping; r.outputColorSpace = THREE.LinearSRGBColorSpace; r.info.autoReset = false;
    this.scale = opts.scale || 1; this.msaa = opts.msaa ?? 4; this.rts = []; this.W = 0; this.H = 0;
    this.quad = new THREE.Mesh(new THREE.PlaneGeometry(2, 2)); this.quad.frustumCulled = false; this.qscene = new THREE.Scene(); this.qscene.add(this.quad); this.qcam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
    this.look = defaultLook();
    const U = v => ({ value: v });
    // 1. atmosphere: distance + height fog with sun in-scatter, drawn from the depth buffer
    this.mAtmo = pass(/* glsl */`
      uniform sampler2D tColor, tDepth; uniform mat4 uInvProj, uInvView; uniform vec3 uCam, uFogCol, uSunCol, uSunDir; uniform float uDensity, uFalloff, uBase, uSunPow, uNear, uFar, uMaxFog, uTime, uNoise;
      ${GLSL_NOISE}
      void main(){ vec3 c = texture(tColor, vUv).rgb; float d = texture(tDepth, vUv).x;
        vec4 ndc = vec4(vUv*2.-1., d*2.-1., 1.); vec4 vp = uInvProj*ndc; vp /= vp.w; vec3 wp = (uInvView*vp).xyz; vec3 rd = wp - uCam; float dist = length(rd); rd /= max(dist,1e-4);
        if(d >= 0.99999) dist = uFar*0.6;
        // analytic integral of exp(-falloff*(y-base)) along the ray
        float a = uFalloff*rd.y; float h0 = exp(-uFalloff*(uCam.y-uBase));
        float od = uDensity*h0*dist*(abs(a*dist) > 1e-3 ? (1.-exp(-a*dist))/(a*dist) : 1.);
        float n = uNoise > 0. ? mix(1., fbm3b(vec3(wp.xz*0.02, uTime*0.05)+rd*3.)*2., uNoise) : 1.;
        float f = min(uMaxFog, 1.-exp(-od*n));
        float s = pow(max(dot(rd, uSunDir),0.), uSunPow);
        o = vec4(mix(c, uFogCol + uSunCol*s, f), 1.); }`,
      { tColor: U(null), tDepth: U(null), uInvProj: U(new THREE.Matrix4()), uInvView: U(new THREE.Matrix4()), uCam: U(new THREE.Vector3()), uFogCol: U(new THREE.Color()), uSunCol: U(new THREE.Color()), uSunDir: U(new THREE.Vector3(0, 1, 0)),
        uDensity: U(0), uFalloff: U(.1), uBase: U(0), uSunPow: U(8), uNear: U(.1), uFar: U(1000), uMaxFog: U(1), uTime: U(0), uNoise: U(0) });
    // 1b. ink: outlines on characters (marked by alpha 0.5) from depth breaks and tone breaks — the anime line
    this.mInk = pass(/* glsl */`uniform sampler2D tColor, tDepth; uniform vec2 uPx; uniform float uNear, uFar, uInk, uWidth; uniform vec3 uInkCol;
      float lin(float d){ return uNear*uFar/(uFar - d*(uFar-uNear)); }
      float lum(vec3 c){ return dot(c, vec3(.299, .587, .114)); }
      void main(){ vec4 cc = texture(tColor, vUv); vec3 c = cc.rgb; vec2 ox = vec2(uPx.x*uWidth, 0.), oy = vec2(0., uPx.y*uWidth);
        vec4 a1 = texture(tColor, vUv+ox), a2 = texture(tColor, vUv-ox), a3 = texture(tColor, vUv+oy), a4 = texture(tColor, vUv-oy);
        float ch = max(max(step(cc.a, .75), step(a1.a, .75)), max(max(step(a2.a, .75), step(a3.a, .75)), step(a4.a, .75)));
        if(ch < .5){ o = vec4(c, 1.); return; }
        float zc = lin(texture(tDepth, vUv).x), z1 = lin(texture(tDepth, vUv+ox).x), z2 = lin(texture(tDepth, vUv-ox).x), z3 = lin(texture(tDepth, vUv+oy).x), z4 = lin(texture(tDepth, vUv-oy).x);
        float zm = min(zc, min(min(z1, z2), min(z3, z4)));
        float lap = abs(z1 + z2 + z3 + z4 - 4.*zc)/zm; float e = smoothstep(.006, .02, lap);
        float lc = lum(c); float dc = max(max(abs(lum(a1.rgb)-lc), abs(lum(a2.rgb)-lc)), max(abs(lum(a3.rgb)-lc), abs(lum(a4.rgb)-lc)))/(max(lc, max(max(lum(a1.rgb), lum(a2.rgb)), max(lum(a3.rgb), lum(a4.rgb)))) + .02);
        float e2 = smoothstep(.45, .8, dc)*.55;
        float k = clamp(max(e, e2)*uInk, 0., 1.)*(1. - smoothstep(30., 70., zm));
        o = vec4(mix(c, uInkCol*min(lc, 1.), k), 1.); }`,
      { tColor: U(null), tDepth: U(null), uPx: U(new THREE.Vector2()), uNear: U(.1), uFar: U(1000), uInk: U(1), uWidth: U(1.6), uInkCol: U(new THREE.Vector3(0.05, 0.04, 0.08)) });
    // 2. depth of field (half-res gather)
    this.mCoC = pass(/* glsl */`uniform sampler2D tColor, tDepth; uniform float uNear, uFar, uFocus, uRange, uMaxCoc;
      void main(){ float d = texture(tDepth, vUv).x; float z = uNear*uFar/(uFar - d*(uFar-uNear)); float coc = clamp((z-uFocus)/max(uRange*max(uFocus,0.3),1e-3), -1., 1.); o = vec4(texture(tColor, vUv).rgb, coc*uMaxCoc); }`,
      { tColor: U(null), tDepth: U(null), uNear: U(.1), uFar: U(1000), uFocus: U(5), uRange: U(.5), uMaxCoc: U(10) });
    this.mDof = pass(/* glsl */`uniform sampler2D tHalf, tFull, tDepth; uniform vec2 uPx; uniform float uNear, uFar, uFocus, uRange, uMaxCoc;
      const float GA = 2.39996323;
      void main(){ vec4 c0 = texture(tHalf, vUv); float d = texture(tDepth, vUv).x; float z = uNear*uFar/(uFar - d*(uFar-uNear)); float cocC = clamp((z-uFocus)/max(uRange*max(uFocus,0.3),1e-3), -1., 1.)*uMaxCoc;
        vec3 acc = vec3(0.); float wsum = 0.; float r = 1.;
        for(int i=0;i<28;i++){ float a = float(i)*GA; float rad = sqrt(float(i)+.5)/sqrt(28.)*uMaxCoc; vec2 off = vec2(cos(a),sin(a))*rad*uPx; vec4 s = texture(tHalf, vUv+off);
          float sc = abs(s.a); float w = smoothstep(rad-1.5, rad+.5, max(sc, s.a < 0. ? sc : min(sc, abs(cocC)))); w *= 1. + dot(s.rgb, vec3(.3))*.35; acc += s.rgb*w; wsum += w; }
        vec3 blur = wsum > 0. ? acc/wsum : c0.rgb; float k = smoothstep(.6, 2.2, abs(cocC)); o = vec4(mix(texture(tFull, vUv).rgb, blur, k), 1.); }`,
      { tHalf: U(null), tFull: U(null), tDepth: U(null), uPx: U(new THREE.Vector2()), uNear: U(.1), uFar: U(1000), uFocus: U(5), uRange: U(.5), uMaxCoc: U(10) });
    // 3. bloom
    this.mPre = pass(/* glsl */`uniform sampler2D t; uniform float uThr; void main(){ vec3 c = texture(t, vUv).rgb; if(isnan(c.r) || isnan(c.g) || isnan(c.b) || isinf(c.r) || isinf(c.g) || isinf(c.b)) c = vec3(0.); c = max(c, 0.); float b = max(c.r, max(c.g, c.b)); float k = max(b-uThr, 0.)/max(b, 1e-4); o = vec4(min(c*k, vec3(40.)), 1.); }`, { t: U(null), uThr: U(1) });
    this.mBlur = pass(/* glsl */`uniform sampler2D t; uniform vec2 uDir; void main(){ vec3 c = texture(t, vUv).rgb*.227027; c += (texture(t, vUv+uDir*1.3846).rgb+texture(t, vUv-uDir*1.3846).rgb)*.316216; c += (texture(t, vUv+uDir*3.2308).rgb+texture(t, vUv-uDir*3.2308).rgb)*.070270; o = vec4(c, 1.); }`, { t: U(null), uDir: U(new THREE.Vector2()) });
    this.mCopy = pass(`uniform sampler2D t; void main(){ o = texture(t, vUv); }`, { t: U(null) });
    // 4. composite: bloom + lens + grade + tone map
    this.mComp = pass(/* glsl */`
      uniform sampler2D tColor, b0, b1, b2, b3, b4, b5; uniform float uBloom, uExposure, uVig, uGrain, uTime, uSat, uContrast, uFade, uCA, uFlash, uInvert, uStreak, uDesat; uniform vec3 uLift, uGamma, uGain, uShadowTint, uHighTint, uFadeCol; uniform vec2 uCenter;
      ${GLSL_NOISE}
      vec3 aces(vec3 x){ return clamp((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14), 0., 1.); }
      void main(){ vec2 uv = vUv; vec2 cc = uv - .5; float r2 = dot(cc, cc);
        vec3 c; if(uCA > 0.){ vec2 ca = cc*r2*uCA; c = vec3(texture(tColor, uv+ca).r, texture(tColor, uv).g, texture(tColor, uv-ca).b); } else c = texture(tColor, uv).rgb;
        vec3 bl = texture(b0, uv).rgb*.9 + texture(b1, uv).rgb*1. + texture(b2, uv).rgb*1.1 + texture(b3, uv).rgb*1.2 + texture(b4, uv).rgb*1.3 + texture(b5, uv).rgb*1.4;
        vec3 st = (texture(b3, vec2(uv.x, uv.y)).rgb + texture(b4, uv).rgb)*uStreak;
        if(isnan(c.r) || isnan(c.g) || isnan(c.b)) c = vec3(0.); c = clamp(c, 0., 60.);
        c += bl*uBloom/6.9 + st;
        c *= uExposure; c *= mix(1., smoothstep(1.05, .25, length(cc*vec2(1., .8))*1.25), uVig);
        c = aces(c);
        c = pow(max(c, 0.), vec3(1./2.2));
        float l = dot(c, vec3(.2126, .7152, .0722));
        c = mix(vec3(l), c, uSat*(1.-uDesat));
        c = (c-.5)*uContrast+.5;
        c = uGain*(c + uLift*(1.-c)); c = pow(max(c, 0.), 1./uGamma);
        l = dot(c, vec3(.2126, .7152, .0722)); c += uShadowTint*(1.-smoothstep(0., .5, l)) + uHighTint*smoothstep(.5, 1., l);
        c = mix(c, vec3(1.) - c, uInvert); c += uFlash;
        float g = hash12(gl_FragCoord.xy + fract(uTime*7.31)*vec2(913., 517.)) - .5; c += g*uGrain*(1.-.5*l);
        c = mix(c, uFadeCol, uFade);
        o = vec4(clamp(c, 0., 1.), 1.); }`,
      { tColor: U(null), b0: U(null), b1: U(null), b2: U(null), b3: U(null), b4: U(null), b5: U(null), uBloom: U(.6), uExposure: U(1), uVig: U(.5), uGrain: U(.03), uTime: U(0), uSat: U(1), uContrast: U(1), uFade: U(0), uCA: U(0), uFlash: U(0), uInvert: U(0), uStreak: U(0), uDesat: U(0),
        uLift: U(new THREE.Vector3()), uGamma: U(new THREE.Vector3(1, 1, 1)), uGain: U(new THREE.Vector3(1, 1, 1)), uShadowTint: U(new THREE.Vector3()), uHighTint: U(new THREE.Vector3()), uFadeCol: U(new THREE.Color(0)), uCenter: U(new THREE.Vector2(.5, .5)) });
    this.mFxaa = pass(/* glsl */`uniform sampler2D t; uniform vec2 uPx;
      float lu(vec3 c){ return dot(c, vec3(.299, .587, .114)); }
      void main(){ vec3 m = texture(t, vUv).rgb;
        float lNW = lu(texture(t, vUv+vec2(-1., 1.)*uPx).rgb), lNE = lu(texture(t, vUv+vec2(1., 1.)*uPx).rgb), lSW = lu(texture(t, vUv+vec2(-1., -1.)*uPx).rgb), lSE = lu(texture(t, vUv+vec2(1., -1.)*uPx).rgb), lM = lu(m);
        float mn = min(lM, min(min(lNW, lNE), min(lSW, lSE))), mx = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
        vec2 dir = vec2(-((lNW+lNE)-(lSW+lSE)), ((lNW+lSW)-(lNE+lSE)));
        float red = max((lNW+lNE+lSW+lSE)*.03125, .0078125); float rcp = 1./(min(abs(dir.x), abs(dir.y))+red);
        dir = clamp(dir*rcp, -8., 8.)*uPx;
        vec3 a = .5*(texture(t, vUv+dir*(1./3.-.5)).rgb + texture(t, vUv+dir*(2./3.-.5)).rgb);
        vec3 b = a*.5 + .25*(texture(t, vUv-dir*.5).rgb + texture(t, vUv+dir*.5).rgb);
        float lb = lu(b); o = vec4((lb < mn || lb > mx) ? a : b, 1.); }`, { t: U(null), uPx: U(new THREE.Vector2(1 / 1920, 1 / 1080)) });
    this.setSize(opts.width || 1920, opts.height || 1080);
  }
  /** (Re)allocate every render target for an output of W×H pixels. */
  setSize(W, H) {
    W = Math.max(2, Math.round(W)); H = Math.max(2, Math.round(H)); if (W === this.W && H === this.H) return;
    for (const t of this.rts) t.dispose(); this.rts = []; this.W = W; this.H = H; this.renderer.setSize(W, H, false);
    const w = this.w = Math.round(W * this.scale), h = this.h = Math.round(H * this.scale);
    const mk = (ww, hh, o = {}) => { const t = new THREE.WebGLRenderTarget(ww, hh, { type: THREE.HalfFloatType, minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter, depthBuffer: false, ...o }); this.rts.push(t); return t; };
    this.rtScene = mk(w, h, { depthBuffer: true, samples: this.msaa });
    this.rtScene.depthTexture = new THREE.DepthTexture(w, h); this.rtScene.depthTexture.type = THREE.UnsignedIntType;
    this.rtA = mk(w, h); this.rtB = mk(w, h); this.rtHalf = mk(w >> 1, h >> 1); this.rtHalf2 = mk(w >> 1, h >> 1);
    this.bloomRT = []; let bw = w >> 1, bh = h >> 1; for (let i = 0; i < 6; i++) { this.bloomRT.push([mk(bw, bh), mk(bw, bh)]); bw = Math.max(2, bw >> 1); bh = Math.max(2, bh >> 1); }
    this.rtLDR = new THREE.WebGLRenderTarget(W, H, { minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter, depthBuffer: false }); this.rts.push(this.rtLDR);
    this.mFxaa.uniforms.uPx.value.set(1 / W, 1 / H);
  }
  dispose() { for (const t of this.rts) t.dispose(); this.rts = []; this.renderer.dispose(); }
  blit(mat, target) { this.quad.material = mat; this.renderer.setRenderTarget(target); this.renderer.render(this.qscene, this.qcam); }
  render(scene, camera, time = 0) {
    const r = this.renderer, L = this.look, w = this.w, h = this.h; r.info.reset(); const gl = r.getContext(); const P = this.prof = {}; let tp = performance.now(); const _px = this._px ??= new Uint8Array(4); const mark = (k) => { if (!this.profiling) return; r.setRenderTarget(null); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, _px); const n = performance.now(); P[k] = Math.round(n - tp); tp = n; };
    camera.updateMatrixWorld(); camera.updateProjectionMatrix();
    r.setRenderTarget(this.rtScene); r.setClearColor(0x000000, 1); r.clear(); r.render(scene, camera);
    this.tris = r.info.render.triangles; mark('scene');
    let src = this.rtScene.texture;
    // ink lines (anime look)
    if (L.ink > 0) {
      const u = this.mInk.uniforms; u.tColor.value = src; u.tDepth.value = this.rtScene.depthTexture; u.uPx.value.set(1 / w, 1 / h); u.uNear.value = camera.near; u.uFar.value = camera.far; u.uInk.value = L.ink; u.uWidth.value = (L.inkWidth ?? 1.6) * h / 1080;
      this.blit(this.mInk, this.rtB); src = this.rtB.texture;
    }
    // atmosphere
    if (L.fog.density > 0) {
      const u = this.mAtmo.uniforms; u.tColor.value = src; u.tDepth.value = this.rtScene.depthTexture; u.uInvProj.value.copy(camera.projectionMatrixInverse); u.uInvView.value.copy(camera.matrixWorld); u.uCam.value.setFromMatrixPosition(camera.matrixWorld);
      u.uFogCol.value.copy(L.fog.color); u.uSunCol.value.copy(L.fog.sunColor); u.uSunDir.value.copy(L.fog.sunDir).normalize(); u.uDensity.value = L.fog.density; u.uFalloff.value = L.fog.falloff; u.uBase.value = L.fog.base; u.uSunPow.value = L.fog.sunPow;
      u.uNear.value = camera.near; u.uFar.value = camera.far; u.uMaxFog.value = L.fog.max; u.uTime.value = time; u.uNoise.value = L.fog.noise;
      this.blit(this.mAtmo, this.rtA); src = this.rtA.texture;
    }
    mark('atmo');
    // depth of field
    if (L.dof.on) {
      for (const m of [this.mCoC, this.mDof]) { const u = m.uniforms; u.tDepth.value = this.rtScene.depthTexture; u.uNear.value = camera.near; u.uFar.value = camera.far; u.uFocus.value = L.dof.focus; u.uRange.value = L.dof.range; u.uMaxCoc.value = L.dof.max * this.scale * (this.H / 1080); }
      this.mCoC.uniforms.tColor.value = src; this.blit(this.mCoC, this.rtHalf);
      const u = this.mDof.uniforms; u.tHalf.value = this.rtHalf.texture; u.tFull.value = src; u.uPx.value.set(1 / w, 1 / h);
      const dofOut = src === this.rtB.texture ? this.rtA : this.rtB; this.blit(this.mDof, dofOut); src = dofOut.texture;
    }
    mark('dof');
    // bloom pyramid
    this.mPre.uniforms.t.value = src; this.mPre.uniforms.uThr.value = L.bloom.threshold; this.blit(this.mPre, this.bloomRT[0][0]);
    for (let i = 0; i < 6; i++) {
      const [a, b] = this.bloomRT[i];
      if (i > 0) { this.mCopy.uniforms.t.value = this.bloomRT[i - 1][0].texture; this.blit(this.mCopy, a); }
      this.mBlur.uniforms.t.value = a.texture; this.mBlur.uniforms.uDir.value.set(1 / a.width, 0); this.blit(this.mBlur, b);
      this.mBlur.uniforms.t.value = b.texture; this.mBlur.uniforms.uDir.value.set(0, 1 / a.height); this.blit(this.mBlur, a);
    }
    mark('bloom');
    const c = this.mComp.uniforms; c.tColor.value = src; for (let i = 0; i < 6; i++) c['b' + i].value = this.bloomRT[i][0].texture;
    c.uBloom.value = L.bloom.strength; c.uExposure.value = L.exposure; c.uVig.value = L.vignette; c.uGrain.value = L.grain; c.uTime.value = time; c.uSat.value = L.sat; c.uContrast.value = L.contrast; c.uFade.value = L.fade; c.uFadeCol.value.copy(L.fadeColor);
    c.uCA.value = L.ca; c.uFlash.value = L.flash; c.uInvert.value = L.invert; c.uStreak.value = L.streak; c.uDesat.value = L.desat; c.uLift.value.copy(L.lift); c.uGamma.value.copy(L.gamma); c.uGain.value.copy(L.gain); c.uShadowTint.value.copy(L.shadowTint); c.uHighTint.value.copy(L.highTint);
    this.blit(this.mComp, this.rtLDR); mark('comp');
    this.mFxaa.uniforms.t.value = this.rtLDR.texture; this.blit(this.mFxaa, null); mark('fxaa');
  }
  /** raw RGBA of the finished frame (bottom-up rows) */
  pixels() { const gl = this.renderer.getContext(), W = this.W, H = this.H; const buf = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, buf); return buf; }
}

export function defaultLook() {
  return { ink: 0, inkWidth: 1.6, exposure: 1, vignette: .55, grain: .035, sat: 1, contrast: 1.04, fade: 0, fadeColor: new THREE.Color(0), ca: .0, flash: 0, invert: 0, streak: 0, desat: 0,
    lift: new THREE.Vector3(0, 0, 0), gamma: new THREE.Vector3(1, 1, 1), gain: new THREE.Vector3(1, 1, 1), shadowTint: new THREE.Vector3(0, 0, 0), highTint: new THREE.Vector3(0, 0, 0),
    bloom: { threshold: 1.0, strength: .7 }, dof: { on: false, focus: 5, range: .4, max: 12 },
    fog: { density: 0, falloff: .08, base: 0, color: new THREE.Color(.5, .6, .7), sunColor: new THREE.Color(0, 0, 0), sunDir: new THREE.Vector3(0, 1, 0), sunPow: 8, max: 1, noise: 0 } };
}

/* ---------- tiling procedural textures (CPU) ---------- */
function pnoise(x, y, per, seed) { // periodic value noise
  const xi = Math.floor(x), yi = Math.floor(y); let xf = x - xi, yf = y - yi; xf = xf * xf * (3 - 2 * xf); yf = yf * yf * (3 - 2 * yf);
  const m = (a) => ((a % per) + per) % per; const hh = (a, b) => h3(m(a), m(b), seed);
  return lerp(lerp(hh(xi, yi), hh(xi + 1, yi), xf), lerp(hh(xi, yi + 1), hh(xi + 1, yi + 1), xf), yf);
}
export function pfbm(u, v, base, oct, seed, gain = .5) { let a = .5, s = 0, n = 0, f = base; for (let i = 0; i < oct; i++) { s += a * pnoise(u * f, v * f, f, seed + i * 7); n += a; a *= gain; f *= 2; } return s / n; }
/** build {map, normalMap, roughnessMap?} from height(u,v)->[0,1] and colour(u,v,h)->[r,g,b] (sRGB 0..1) */
export function makeTex(size, heightFn, colorFn, opts = {}) {
  const hgt = new Float32Array(size * size);
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) hgt[y * size + x] = heightFn(x / size, y / size);
  const col = new Uint8Array(size * size * 4), nrm = new Uint8Array(size * size * 4); const str = opts.bump ?? 2;
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const i = y * size + x, hC = hgt[i]; const hx = hgt[y * size + (x + 1) % size] - hgt[y * size + (x + size - 1) % size], hy = hgt[((y + 1) % size) * size + x] - hgt[((y + size - 1) % size) * size + x];
    let nx = -hx * str * size / 64, ny = -hy * str * size / 64, nz = 1; const l = Math.hypot(nx, ny, nz); nx /= l; ny /= l; nz /= l;
    nrm[i * 4] = (nx * .5 + .5) * 255; nrm[i * 4 + 1] = (ny * .5 + .5) * 255; nrm[i * 4 + 2] = (nz * .5 + .5) * 255; nrm[i * 4 + 3] = 255;
    const c = colorFn(x / size, y / size, hC); col[i * 4] = clamp(c[0]) * 255; col[i * 4 + 1] = clamp(c[1]) * 255; col[i * 4 + 2] = clamp(c[2]) * 255; col[i * 4 + 3] = c[3] === undefined ? 255 : clamp(c[3]) * 255;
  }
  const mk = (d, srgb) => { const t = new THREE.DataTexture(d, size, size, THREE.RGBAFormat); t.wrapS = t.wrapT = THREE.RepeatWrapping; t.magFilter = THREE.LinearFilter; t.minFilter = THREE.LinearMipmapNearestFilter; t.generateMipmaps = true; if (srgb) t.colorSpace = THREE.SRGBColorSpace; t.needsUpdate = true; return t; };
  return { map: mk(col, true), normalMap: mk(nrm, false) };
}
let _noise = null;
/** shared tiling noise: R,G,B = smooth fbm at 3 scales, A = fine grain. Much cheaper to sample than computing noise per pixel. */
export function noiseTex() {
  if (_noise) return _noise; const S = 256, d = new Uint8Array(S * S * 4);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) { const u = x / S, v = y / S, i = (y * S + x) * 4; d[i] = clamp((pfbm(u, v, 4, 6, 101) - 0.5) * 1.9 + 0.5) * 255; d[i + 1] = clamp((pfbm(u, v, 8, 5, 202) - 0.5) * 1.9 + 0.5) * 255; d[i + 2] = clamp((pfbm(u, v, 16, 4, 303) - 0.5) * 1.9 + 0.5) * 255; d[i + 3] = pfbm(u, v, 64, 2, 404) * 255; }
  const t = new THREE.DataTexture(d, S, S, THREE.RGBAFormat); t.wrapS = t.wrapT = THREE.RepeatWrapping; t.magFilter = THREE.LinearFilter; t.minFilter = THREE.LinearMipmapNearestFilter; t.generateMipmaps = true; t.needsUpdate = true; return _noise = t;
}
export const V3 = (x = 0, y = 0, z = 0) => new THREE.Vector3(x, y, z);
export const col = (r, g, b) => new THREE.Color(r, g, b);
