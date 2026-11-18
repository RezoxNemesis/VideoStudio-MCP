// 2.5D film engine: painted layers on real depth, toon bodies driven by captured motion, one real camera
import * as THREE from './three.module.js';
export { THREE };
export const V3 = THREE.Vector3;
export const clamp = (x, a, b) => Math.min(b, Math.max(a, x)), lerp = (a, b, t) => a + (b - a) * t;
export const sm = t => { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); };
export const sm2 = t => { t = clamp(t, 0, 1); return t * t * t * (t * (6 * t - 15) + 10); };
export const seg = (t, a, b) => clamp((t - a) / (b - a), 0, 1);
export function rng(seed) { let s = seed >>> 0; return () => { s = (s + 0x6D2B79F5) >>> 0; let t = Math.imul(s ^ (s >>> 15), 1 | s); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
const col = (h) => new THREE.Color(h);
const U = v => ({ value: v });

const FOGU = `uniform vec3 uFogCol; uniform vec4 uFog; uniform vec3 uSunDir; uniform vec3 uFogSun;`;
// fog worked out at the corners of a thing and carried across it
const FOGV = FOGU + ` varying vec4 vFog;
void calcFog(vec3 wp){ vec3 d = wp - cameraPosition; float dist = length(d); float f = min(1.0 - exp(-dist * uFog.x * exp(-max(wp.y, 0.0) * uFog.y)), uFog.z);
  float s = max(dot(d / dist, uSunDir), 0.0); s = s * s; s = s * s * s; vFog = vec4(mix(uFogCol, uFogSun, s * uFog.w), f); }`;
const DOFV = `uniform vec3 uDof; float dofBias(float d){ float c = uDof.y * abs(d - uDof.x) / max(d * uDof.x, 0.01); return min(log2(1.0 + c), uDof.z); }`;

const CHAR_VS = `
#include <common>
#include <skinning_pars_vertex>
attribute float reg, loose, lip, eye; attribute vec3 blink, smile;
uniform float uBlink, uSmile, uOutline, uTime, uFlut, uShadowPass; uniform vec3 uLightDir; uniform vec3 uPal[11];
varying vec3 vN, vW, vBase; varying float vReg, vLip, vEye, vH;
${FOGV}
void main(){
  vec3 objectNormal = normal;
  vec3 transformed = position + blink * uBlink + smile * uSmile;
  #include <skinbase_vertex>
  #include <skinnormal_vertex>
  #include <skinning_vertex>
  vec4 wp = modelMatrix * vec4(transformed, 1.0);
  vec3 wn = normalize(mat3(modelMatrix) * objectNormal);
  wp.xyz += wn * loose * uFlut * (sin(uTime * 11.0 + position.y * 23.0 + position.x * 31.0) + 0.6 * sin(uTime * 17.0 + position.z * 41.0));
  int r = int(reg + 0.5);
  vBase = uPal[r]; vReg = reg; vLip = lip; vEye = eye; vH = wp.y;
  if (uShadowPass > 0.5) { wp.xz -= uLightDir.xz / max(uLightDir.y, 0.08) * max(wp.y, 0.0); wp.y = 0.012; }
  else if (uOutline > 0.0) { float d = length(cameraPosition - wp.xyz); float k = (r == 6 || r == 7) ? 0.0 : 1.0; wp.xyz += wn * min(uOutline * d, 0.0065) * k; }
  vN = wn; vW = wp.xyz; calcFog(wp.xyz);
  gl_Position = projectionMatrix * viewMatrix * wp;
}`;
const CHAR_FS = `
uniform vec3 uLightDir, uLightCol, uShadeCol, uRimCol, uRimDir, uAmbCol, uOutCol; uniform float uOutline, uShadowPass, uDark;
uniform vec3 uIris; varying vec3 vN, vW, vBase; varying float vReg, vLip, vEye, vH; varying vec4 vFog;
void main(){
  if (uShadowPass > 0.5) { float s = mix(0.80, 0.42, clamp(vH / 1.7, 0.0, 1.0)); gl_FragColor = vec4(vec3(1.0 - s), 1.0); return; }
  vec3 base = vBase; int r = int(vReg + 0.5);
  if (uOutline > 0.0) { vec3 c = mix(base * 0.30, uOutCol, 0.45) * (1.0 - uDark); gl_FragColor = vec4(mix(c, vFog.rgb, vFog.a), 1.0); return; }
  if (r == 0) base = mix(base, base * vec3(0.86, 0.60, 0.60), vLip * 0.75);
  vec3 n = normalize(vN); if (!gl_FrontFacing) n = -n;
  vec3 v = normalize(cameraPosition - vW);
  float lit = smoothstep(-0.03, 0.09, dot(n, uLightDir));
  if (r == 6) { float a = acos(clamp(vEye, -1.0, 1.0)); vec3 e = vec3(0.96, 0.95, 0.93) * (0.84 + 0.16 * smoothstep(-0.2, 0.5, 0.3 - n.y));
    e = mix(e, uIris, smoothstep(0.50, 0.46, a)); e = mix(e, vec3(0.03, 0.02, 0.02), smoothstep(0.24, 0.20, a)); base = e; lit = max(lit, 0.8); }
  if (!gl_FrontFacing) lit *= 0.25;
  vec3 c = mix(base * uShadeCol, base * uLightCol, lit) + base * uAmbCol * (0.5 + 0.5 * n.y);
  float fr = 1.0 - max(dot(n, v), 0.0); fr = fr * fr * sqrt(fr);
  c += uRimCol * fr * smoothstep(-0.1, 0.6, dot(n, uRimDir)) * (r == 5 ? 0.7 : 1.0);
  if (r == 5) { vec3 h = normalize(uLightDir + v); c += uLightCol * 0.10 * smoothstep(0.55, 0.9, dot(n, h)) * smoothstep(0.35, 0.8, n.y); }
  c *= (1.0 - uDark);
  gl_FragColor = vec4(mix(c, vFog.rgb, vFog.a), 1.0);
}`;

const CARD_VS = `
uniform vec4 uSway; uniform float uTime, uShadowPass, uCardW, uBlur, uFogK; uniform vec3 uLightDir, uCardPos;
varying vec2 vUv, vL; varying float vB; uniform vec4 uUvr;
${FOGV}
${DOFV}
void main(){ vUv = uUvr.xy + uv * uUvr.zw; vL = uv; vec3 p = position;
  float k = uv.y * uv.y; p.x += uSway.x * k * (sin(uTime * uSway.y + uSway.z) + 0.35 * sin(uTime * uSway.y * 2.7 + uSway.z * 1.7 + uv.x * 3.0) + uSway.w);
  vec4 wp = modelMatrix * vec4(p, 1.0);
  if (uShadowPass > 0.5) { vec3 ax = normalize(vec3(uLightDir.z, 0.0, -uLightDir.x)); float h = wp.y - uCardPos.y;
    wp.xyz = uCardPos + ax * p.x * uCardW; wp.xz -= uLightDir.xz / max(uLightDir.y, 0.08) * h; wp.y = 0.012; }
  vec4 mv = viewMatrix * wp; vB = uBlur < -0.5 ? dofBias(-mv.z) : uBlur; calcFog(wp.xyz); vFog.a *= uFogK; vFog.w = vFog.a; gl_Position = projectionMatrix * mv; }`;
const CARD_FS = `
uniform sampler2D map; uniform vec3 uTint, uTint2, uLit, uShd; uniform float uShadowPass, uOpacity, uShStr; uniform vec4 uUvr;
varying vec2 vUv, vL; varying float vB; varying vec4 vFog;
void main(){
  if (uShadowPass > 0.5) { float a = texture2D(map, vUv, 1.0).a; gl_FragColor = vec4(vec3(1.0 - uShStr * a), 1.0); return; }
#ifdef SOFT
  vec2 dx = dFdx(vUv), dy = dFdy(vUv); float cc = exp2(vB) - 1.0; float bb = max(vB - 1.2, 0.0);
  vec2 o1 = (dx * 0.46 + dy * 0.19) * cc, o2 = (dy * 0.46 - dx * 0.19) * cc;
  vec4 t = (texture2D(map, vUv + o1, bb) + texture2D(map, vUv - o1, bb) + texture2D(map, vUv + o2, bb) + texture2D(map, vUv - o2, bb)) * 0.25;
  t *= smoothstep(0.0, 0.008 + 0.035 * vB, min(min(vL.x, 1.0 - vL.x), min(vL.y + 0.03, 1.0 - vL.y)));
#else
  vec4 t = texture2D(map, vUv, vB);
#endif
#ifdef RELIGHT
  vec3 c = mix(uShd * t.a, uLit * t.a, t.a > 0.001 ? t.r / t.a : 0.0);
#else
  vec3 c = t.rgb * mix(uTint2, uTint, (vUv.y - uUvr.y) / uUvr.w);
#endif
  gl_FragColor = vec4(mix(c, vFog.rgb * t.a, vFog.a), t.a) * uOpacity;
}`;

const SKY_VS = `varying vec3 vDir; uniform mat4 uInvPV; void main(){ gl_Position = vec4(position.xy, 1.0, 1.0); vec4 a = uInvPV * vec4(position.xy, 1.0, 1.0); vDir = a.xyz / a.w - cameraPosition; }`;
const SKY_FS = `
uniform vec3 uZen, uHor, uLow, uSunDir, uSunCol, uGlowCol; uniform vec4 uSky; uniform vec3 uMoonDir;
varying vec3 vDir;
float h21(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
void main(){ vec3 d = normalize(vDir); float h = d.y;
  vec3 c = mix(uHor, uZen, sqrt(clamp(h, 0.0, 1.0)));
  c = mix(c, uLow, smoothstep(0.0, -0.12, h));
  float s = max(dot(d, uSunDir), 0.0); float s2 = s * s; float s8 = s2 * s2; s8 *= s8;
  c += uGlowCol * (pow(s8, uSky.x * 0.125) * uSky.y + s2 * uSky.y * 0.3) * smoothstep(-0.25, 0.1, h + 0.1);
  c += uSunCol * smoothstep(0.99955, 0.99975, s);
#ifdef STARS
  vec2 sp = vec2(atan(d.z, d.x), asin(clamp(h, -1.0, 1.0))) * 170.0; vec2 g = floor(sp); vec2 f = fract(sp) - 0.5;
  float r = h21(g); float tw = 0.75 + 0.25 * sin(uSky.w * (1.5 + 3.0 * r) + r * 40.0);
  float st = step(0.93, r) * smoothstep(0.16 + 0.1 * fract(r * 7.0), 0.0, length(f + (vec2(h21(g + 3.1), h21(g + 7.7)) - 0.5) * 0.5)) * tw;
  c += vec3(0.9, 0.93, 1.0) * st * uSky.z * smoothstep(0.02, 0.3, h);
  float m = max(dot(d, uMoonDir), 0.0); c += vec3(0.95, 0.95, 0.88) * smoothstep(0.99985, 0.99992, m) * uSky.z + vec3(0.35, 0.4, 0.5) * pow(m, 300.0) * 0.5 * uSky.z;
#endif
  gl_FragColor = vec4(c, 1.0); }`;

const GROUND_VS = `varying vec3 vW; varying float vD; void main(){ vec4 wp = modelMatrix * vec4(position, 1.0); vW = wp.xyz; vec4 mv = viewMatrix * wp; vD = -mv.z; gl_Position = projectionMatrix * mv; }`;
const GROUND_FS = `
uniform sampler2D tGrass, tDirt, tRoad, tVar, tShadow; uniform vec2 uRes; uniform vec3 uGTint, uRTint, uShadowTint, uField, uSkyRef; uniform vec4 uRoad, uRoad2, uFieldP;
uniform float uPuddle, uTime;
varying vec3 vW; varying float vD;
${FOGU}
${DOFV}
void main(){ float b = dofBias(vD); vec2 p = vW.xz;
  vec3 var = texture2D(tVar, p / 190.0).rgb; vec3 var2 = texture2D(tVar, p / 23.0 + 0.37).rgb;
  vec3 g = texture2D(tGrass, p / 7.0, b).rgb;
  g = mix(g, g * vec3(1.18, 1.12, 0.72), smoothstep(0.42, 0.7, var.r)); g = mix(g, vec3(0.58, 0.46, 0.33) * (0.8 + 0.4 * var2.r), smoothstep(0.55, 0.8, var.g) * 0.7) * (0.82 + 0.36 * var2.b);
#ifdef FIELD
  float fz = abs(p.y); float inF = smoothstep(uFieldP.y, uFieldP.y + 3.0, fz) * smoothstep(0.25, 0.4, var.b + 0.2);
  g = mix(g, uField * (0.82 + 0.18 * sin(p.y * uFieldP.z + var2.r * 1.5)) * (0.8 + 0.4 * var2.g), inF * uFieldP.x);
#endif
  vec3 c = g * uGTint;
  vec3 dv = vW - cameraPosition; float dist = length(dv); vec3 vdir = dv / dist;
#ifdef NEAR
  vec3 dirt = texture2D(tDirt, p / 5.0, b).rgb;
  float d = abs(p.y); float along = p.x;
#ifdef ROAD2
  vec2 q = p - uRoad2.xy; float tt = max(dot(q, uRoad2.zw), 0.0); float d2 = length(q - uRoad2.zw * tt); if (d2 < d) { d = d2; along = tt + uRoad2.x; }
#endif
  float edge = uRoad.x + (var2.r - 0.5) * 0.5; float sh = edge + uRoad.y * (0.6 + 0.8 * var2.g);
  vec3 rd = texture2D(tRoad, vec2(along / 9.0, d / 9.0 + 0.5), b).rgb * uRTint * (0.9 + 0.2 * var2.b);
  float dash = step(mod(along, 9.0), 3.2) * smoothstep(0.085, 0.06, d) * uRoad.z * (0.55 + 0.45 * var2.g);
  rd = mix(rd, vec3(0.86, 0.84, 0.76) * uRTint * 1.25, dash);
  c = mix(c, dirt * uGTint * 1.02, smoothstep(sh, sh - 0.7, d));
  float rm = smoothstep(edge, edge - 0.22, d); c = mix(c, rd, rm);
#ifdef WET
  float fres = 1.0 + vdir.y; fres = clamp(fres * fres * fres, 0.0, 1.0);
  float pud = smoothstep(0.50, 0.56, var2.r * 0.55 + var2.b * 0.45) * uPuddle * smoothstep(sh + 1.5, edge - 0.6, d);
  c = mix(c, c * 0.72, uRoad.w * 0.6); c = mix(c, uSkyRef, uRoad.w * fres * 0.45 * (0.4 + 0.6 * rm));
  c = mix(c, uSkyRef * (0.75 + 0.25 * fres), pud * (0.55 + 0.4 * fres));
#endif
#endif
  float s = texture2D(tShadow, gl_FragCoord.xy / uRes).r; c *= mix(uShadowTint, vec3(1.0), s);
  float f = min(1.0 - exp(-dist * uFog.x), uFog.z); float ss = max(dot(vdir, uSunDir), 0.0); ss = ss * ss; ss = ss * ss * ss;
  gl_FragColor = vec4(mix(c, mix(uFogCol, uFogSun, ss * uFog.w), f), 1.0); }`;

const GRASS_VS = `
attribute vec4 iPos; attribute vec4 iPar;
uniform float uTime; uniform vec4 uWind; uniform vec3 uTint, uTintB; varying vec2 vUv, vL; varying float vB, vCell; varying vec3 vT;
${FOGV}
${DOFV}
void main(){ vec3 base = iPos.xyz; vec3 toC = cameraPosition - base; vec3 right = normalize(vec3(toC.z, 0.0, -toC.x));
  float sx = iPar.w > 0.5 ? -1.0 : 1.0; float y = position.y;
  float sway = (sin(uTime * uWind.y + iPar.y + base.x * 0.35) + 0.4 * sin(uTime * uWind.y * 2.3 + iPar.y * 2.0)) * uWind.x + uWind.z;
  vec3 wp = base + right * (position.x * sx * iPos.w + sway * y * y * iPos.w) + vec3(0.0, y * iPos.w, 0.0);
  vUv = vec2((uv.x + iPar.x) * 0.25, uv.y); vL = uv; vCell = iPar.x * 0.25; vT = mix(uTintB, uTint, iPar.z) * (0.75 + 0.35 * uv.y); vec4 mv = viewMatrix * vec4(wp, 1.0); vB = dofBias(-mv.z); calcFog(wp); gl_Position = projectionMatrix * mv; }`;
const GRASS_FS = `
uniform sampler2D map; varying vec2 vUv, vL; varying float vB, vCell; varying vec3 vT; varying vec4 vFog;
void main(){
#ifdef SOFT
  vec2 dx = dFdx(vUv), dy = dFdy(vUv); float cc = (exp2(vB) - 1.0) * 0.5; float bb = max(vB - 1.0, 0.0); vec2 lo = vec2(vCell + 0.004, 0.004), hi = vec2(vCell + 0.246, 0.996);
  vec4 t = (texture2D(map, clamp(vUv + dx * cc, lo, hi), bb) + texture2D(map, clamp(vUv + (dy * 0.87 - dx * 0.5) * cc, lo, hi), bb) + texture2D(map, clamp(vUv - (dy * 0.87 + dx * 0.5) * cc, lo, hi), bb)) * 0.3333;
  t *= smoothstep(0.0, 0.008 + 0.05 * vB, min(min(vL.x, 1.0 - vL.x), 1.0 - vL.y));
#else
  vec4 t = texture2D(map, vUv, vB);
#endif
  t *= smoothstep(0.0, 0.11, vL.y);
  gl_FragColor = vec4(mix(t.rgb * vT, vFog.rgb * t.a, vFog.a), t.a); }`;

const RAIN_VS = `
attribute vec4 iPos; uniform float uTime; uniform vec4 uRain; // fall speed, slant, length, box
uniform vec3 uCenter; varying float vA; varying vec2 vUv;
void main(){ float box = uRain.w; vec3 b = iPos.xyz;
  b.y = mod(b.y - uTime * uRain.x * (0.8 + 0.4 * iPos.w), 14.0);
  b.x = uCenter.x + mod(b.x + b.y * uRain.y - uCenter.x + box * 0.5, box) - box * 0.5;
  b.z = uCenter.z + mod(b.z - uCenter.z + box * 0.5, box) - box * 0.5;
  vec3 toC = cameraPosition - b; vec3 right = normalize(vec3(toC.z, 0.0, -toC.x));
  float d = length(toC); float wd = 0.006 + 0.0012 * d;
  vec3 wp = b + right * position.x * wd + vec3(uRain.y, 1.0, 0.0) * position.y * uRain.z * (0.7 + 0.6 * iPos.w);
  vA = (0.35 + 0.65 * iPos.w) * smoothstep(0.4, 1.6, d) * smoothstep(box * 0.5, box * 0.35, d); vUv = uv;
  gl_Position = projectionMatrix * viewMatrix * vec4(wp, 1.0); }`;
const RAIN_FS = `uniform vec3 uCol; uniform float uOp; varying float vA; varying vec2 vUv; void main(){ float a = vA * uOp * smoothstep(0.0, 0.3, vUv.y) * smoothstep(1.0, 0.6, vUv.y) * (1.0 - abs(vUv.x - 0.5) * 2.0); gl_FragColor = vec4(uCol * a, a * 0.6); }`;

const QUAD_VS = `varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }`;
const BRIGHT_FS = `uniform sampler2D t; uniform vec2 px; uniform float thr; varying vec2 vUv; void main(){ vec3 c = (texture2D(t, vUv + px * vec2(-1.0, -1.0)).rgb + texture2D(t, vUv + px * vec2(1.0, -1.0)).rgb + texture2D(t, vUv + px * vec2(-1.0, 1.0)).rgb + texture2D(t, vUv + px * vec2(1.0, 1.0)).rgb) * 0.25; float l = max(c.r, max(c.g, c.b)); gl_FragColor = vec4(c * smoothstep(thr, thr + 0.25, l), 1.0); }`;
const BLUR_FS = `uniform sampler2D t; uniform vec2 dir; varying vec2 vUv; void main(){ vec3 c = texture2D(t, vUv).rgb * 0.227 + (texture2D(t, vUv + dir * 1.385).rgb + texture2D(t, vUv - dir * 1.385).rgb) * 0.316 + (texture2D(t, vUv + dir * 3.23).rgb + texture2D(t, vUv - dir * 3.23).rgb) * 0.070; gl_FragColor = vec4(c, 1.0); }`;
const FINAL_FS = `
uniform sampler2D t, tBloom; uniform vec2 uRes; uniform vec4 uGrade; // gain, gamma, saturation, bloom
uniform vec3 uLift, uGainC, uFadeCol; uniform vec4 uPost; // vignette, grain, fade, time
uniform float uFxaa; varying vec2 vUv;
float luma(vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }
float h21(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
void main(){ vec2 px = 1.0 / uRes; vec3 c = texture2D(t, vUv).rgb;
  if (uFxaa > 0.5) { vec3 n = texture2D(t, vUv + vec2(0.0, px.y)).rgb, s = texture2D(t, vUv - vec2(0.0, px.y)).rgb, e = texture2D(t, vUv + vec2(px.x, 0.0)).rgb, w = texture2D(t, vUv - vec2(px.x, 0.0)).rgb;
    float lc = luma(c), ln = luma(n), ls = luma(s), le = luma(e), lw = luma(w); float mn = min(lc, min(min(ln, ls), min(le, lw))), mx = max(lc, max(max(ln, ls), max(le, lw)));
    float rg = mx - mn; if (rg > max(0.03, mx * 0.09)) { vec2 dir = vec2(-(ln - ls), le - lw); float dl = max(abs(dir.x), abs(dir.y)); dir = dir / max(dl, 0.001) ; dir = clamp(dir, -1.0, 1.0);
      vec3 a = 0.5 * (texture2D(t, vUv + dir * px * 1.1).rgb + texture2D(t, vUv - dir * px * 1.1).rgb);
      c = mix(c, a, 0.62); } }
  c += texture2D(tBloom, vUv).rgb * uGrade.w;
  float l = luma(c); c = mix(vec3(l), c, uGrade.z);
  c = clamp(c * uGrade.x * uGainC + uLift * (1.0 - c), 0.0, 1.0); c = mix(c, c * c * (3.0 - 2.0 * c), (1.0 - uGrade.y) * 6.0);
  vec2 q = vUv - 0.5; c *= 1.0 - uPost.x * smoothstep(0.25, 0.95, length(q * vec2(1.0, 0.8)) * 1.25);
  c += (h21(gl_FragCoord.xy + fract(uPost.w) * 61.0) - 0.5) * uPost.y;
  c = mix(c, uFadeCol, uPost.z);
  gl_FragColor = vec4(c, 1.0); }`;
const TEXT_FS = `uniform sampler2D t; uniform float uOp; varying vec2 vUv; void main(){ vec4 c = texture2D(t, vUv); gl_FragColor = c * uOp; }`;

const PROP_VS = `varying vec3 vN; varying vec2 vUv; uniform float uShadowPass; uniform vec3 uLightDir;
${FOGV}
void main(){ vUv = uv; vec4 wp = modelMatrix * vec4(position, 1.0); vN = normalize(mat3(modelMatrix) * normal);
  if (uShadowPass > 0.5) { wp.xz -= uLightDir.xz / max(uLightDir.y, 0.08) * max(wp.y, 0.0); wp.y = 0.012; } calcFog(wp.xyz); gl_Position = projectionMatrix * viewMatrix * wp; }`;
const PROP_FS = `uniform sampler2D map; uniform vec3 uColor, uLightDir, uLightCol, uShadeCol, uAmbCol, uEmis; uniform float uUseMap, uShadowPass, uDark; varying vec3 vN; varying vec2 vUv; varying vec4 vFog;
void main(){ if (uShadowPass > 0.5) { gl_FragColor = vec4(vec3(0.3), 1.0); return; }
  vec3 base = uColor; if (uUseMap > 0.5) base *= texture2D(map, vUv).rgb; vec3 n = normalize(vN); if (!gl_FrontFacing) n = -n;
  float lit = smoothstep(-0.05, 0.25, dot(n, uLightDir)); vec3 c = mix(base * mix(uShadeCol, vec3(1.0), 0.35), base * uLightCol, lit) + base * uAmbCol * (0.5 + 0.5 * n.y);
  c = c * (1.0 - uDark) + uEmis; gl_FragColor = vec4(mix(c, vFog.rgb, vFog.a), 1.0); }`;

export class Engine {
  constructor(canvas, W, H) {
    this.W = W; this.H = H; this.canvas = canvas;
    const r = this.r = new THREE.WebGLRenderer({ canvas, antialias: false, preserveDrawingBuffer: true, alpha: false, powerPreference: 'high-performance' });
    r.setPixelRatio(1); r.setSize(W, H, false); r.outputColorSpace = THREE.LinearSRGBColorSpace; r.autoClear = false; r.sortObjects = true;
    this.gl = r.getContext();
    this.cam = new THREE.PerspectiveCamera(40, W / H, 0.08, 9000);
    this.scene = new THREE.Scene(); this.shadowScene = new THREE.Scene();
    this.time = 0; this.actors = []; this.cards = []; this.tex = {}; this.assets = {}; this.tracks = {}; this.updaters = [];
    const L = this.L = { // everything a place's light is made of; materials share these objects
      uLightDir: U(new V3(0.5, 0.6, 0.3).normalize()), uLightCol: U(col('#fff2dc')), uShadeCol: U(col('#8f7fa6')), uRimCol: U(col('#ffc890')), uRimDir: U(new V3(-0.6, 0.3, -0.7).normalize()),
      uAmbCol: U(col('#202838')), uOutCol: U(col('#2a1c2c')), uFogCol: U(col('#c8d4e0')), uFog: U(new THREE.Vector4(0.0015, 0.02, 0.9, 0.6)), uSunDir: U(new V3(0.5, 0.3, 0.3).normalize()), uFogSun: U(col('#ffd9a8')),
      uOutK: U(0.001), uTime: U(0), uDof: U(new V3(8, 0, 5)), uShadowPass: U(0), uDark: U(0),
    };
    const rt = (w, h, o = {}) => new THREE.WebGLRenderTarget(w, h, Object.assign({ depthBuffer: false, minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter, type: THREE.UnsignedByteType }, o));
    this.frameRT = rt(W, H, { depthBuffer: true });
    this.shadowRT = rt(W >> 1, H >> 1, { depthBuffer: false });
    this.b1 = rt(W >> 2, H >> 2); this.b2 = rt(W >> 2, H >> 2);
    const quad = new THREE.PlaneGeometry(2, 2); this.quadGeo = quad;
    const sm_ = (fs, u) => new THREE.ShaderMaterial({ vertexShader: QUAD_VS, fragmentShader: fs, uniforms: u, depthTest: false, depthWrite: false });
    this.mBright = sm_(BRIGHT_FS, { t: U(null), px: U(new THREE.Vector2(1 / W, 1 / H)), thr: U(0.78) });
    this.mBlur = sm_(BLUR_FS, { t: U(null), dir: U(new THREE.Vector2()) });
    this.P = { uGrade: U(new THREE.Vector4(1, 1, 1, 0.5)), uLift: U(col('#000000')), uGainC: U(col('#ffffff')), uFadeCol: U(col('#000000')), uPost: U(new THREE.Vector4(0.35, 0.016, 0, 0)) };
    this.mFinal = sm_(FINAL_FS, Object.assign({ t: U(this.frameRT.texture), tBloom: U(this.b1.texture), uRes: U(new THREE.Vector2(W, H)), uFxaa: U(0) }, this.P));
    this.textCanvas = document.createElement('canvas'); this.textCanvas.width = W; this.textCanvas.height = H; this.tctx = this.textCanvas.getContext('2d');
    this.textTex = new THREE.CanvasTexture(this.textCanvas); this.textTex.premultiplyAlpha = true; this.textTex.colorSpace = THREE.NoColorSpace; this.textTex.generateMipmaps = false; this.textTex.minFilter = THREE.LinearFilter;
    this.mText = sm_(TEXT_FS, { t: U(this.textTex), uOp: U(1) }); this.mText.transparent = true; this.mText.blending = THREE.CustomBlending; this.mText.blendSrc = THREE.OneFactor; this.mText.blendDst = THREE.OneMinusSrcAlphaFactor;
    this.textOn = false;
    this.postScene = new THREE.Scene(); this.postMesh = new THREE.Mesh(quad, this.mFinal); this.postMesh.frustumCulled = false; this.postScene.add(this.postMesh); this.postCam = new THREE.Camera();
    // sky
    this.S = { uZen: U(col('#3b6fb8')), uHor: U(col('#cfe0ee')), uLow: U(col('#a8b8b0')), uSunCol: U(col('#fff6e0')), uGlowCol: U(col('#ffd9a0')), uSky: U(new THREE.Vector4(24, 0.5, 0, 0)), uMoonDir: U(new V3(-0.4, 0.5, 0.6).normalize()), uInvPV: U(new THREE.Matrix4()) };
    const sky = new THREE.Mesh(quad, new THREE.ShaderMaterial({ defines: {}, vertexShader: SKY_VS, fragmentShader: SKY_FS, uniforms: Object.assign({ uSunDir: L.uSunDir }, this.S), depthTest: false, depthWrite: false }));
    sky.frustumCulled = false; sky.renderOrder = -1000; this.scene.add(sky); this.sky = sky;
    this.fxRects = []; this.dofFocus = 8; this.dofK = 0; this.focusBlurChar = 0;
  }
  async loadTex(name, o = {}) {
    if (this.tex[name]) return this.tex[name];
    const img = await createImageBitmap(await (await fetch('tex/' + name + '.png')).blob(), { premultiplyAlpha: 'premultiply', colorSpaceConversion: 'none', imageOrientation: 'flipY' });
    const t = new THREE.Texture(img); t.premultiplyAlpha = false; t.colorSpace = THREE.NoColorSpace; t.generateMipmaps = true; t.minFilter = THREE.LinearMipmapLinearFilter; t.magFilter = THREE.LinearFilter; t.flipY = false;
    if (o.repeat) { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.minFilter = THREE.LinearMipmapNearestFilter; } t.anisotropy = o.aniso || 1; t.needsUpdate = true; t.userData = { w: img.width, h: img.height };
    return this.tex[name] = t;
  }
  async loadChar(name) {
    if (this.assets[name]) return this.assets[name];
    const meta = await (await fetch(`data/${name}.json`)).json(); const buf = await (await fetch(`data/${name}.bin`)).arrayBuffer();
    const A = (k, T) => { const [o, l] = meta.blobs[k]; return new T(buf, o, l / T.BYTES_PER_ELEMENT); };
    const g = new THREE.BufferGeometry(); const fa = (k, n) => new THREE.BufferAttribute(A(k, Float32Array), n);
    g.setAttribute('position', fa('pos', 3)); g.setAttribute('normal', fa('nor', 3)); g.setAttribute('skinIndex', new THREE.BufferAttribute(A('si', Uint16Array), 4)); g.setAttribute('skinWeight', fa('sw', 4));
    for (const [k, n] of [['reg', 1], ['loose', 1], ['lip', 1], ['eye', 1], ['blink', 3], ['smile', 3]]) g.setAttribute(k, fa(k, n));
    g.setIndex(new THREE.BufferAttribute(A('idx', Uint32Array), 1)); g.boundingSphere = new THREE.Sphere(new V3(0, 1, 0), 1e6);
    return this.assets[name] = { meta, geo: g, bi: Object.fromEntries(meta.bones.map((b, i) => [b, i])) };
  }
  async loadTrack(name) {
    if (this.tracks[name]) return this.tracks[name];
    const a = new Float32Array(await (await fetch(`data/mo/${name}.bin`)).arrayBuffer());
    return this.tracks[name] = { F: a[0], nb: a[1], d: a, st: 3 + a[1] * 4, name };
  }
  actor(assetName, pal) { const a = new Actor(this, this.assets[assetName], pal); this.actors.push(a); return a; }
  charMat(pal, outline) {
    const L = this.L; const u = { uPal: U(pal), uBlink: U(0), uSmile: U(0), uOutline: outline ? this.L.uOutK : U(0), uFlut: U(0.0015), uIris: U(col('#3a2416')), uFade: U(0) };
    for (const k of ['uLightDir', 'uLightCol', 'uShadeCol', 'uRimCol', 'uRimDir', 'uAmbCol', 'uOutCol', 'uFogCol', 'uFog', 'uSunDir', 'uFogSun', 'uTime', 'uShadowPass', 'uDark']) u[k] = L[k];
    return new THREE.ShaderMaterial({ vertexShader: CHAR_VS, fragmentShader: CHAR_FS, uniforms: u, side: outline ? THREE.BackSide : THREE.DoubleSide });
  }
  card(tex, o) { const c = new Card(this, tex, o); this.cards.push(c); return c; }
  propMat(o = {}) { const L = this.L; const u = { map: U(o.map || null), uUseMap: U(o.map ? 1 : 0), uColor: U(col(o.color || '#ffffff')), uEmis: U(col(o.emis || '#000000')) };
    for (const k of ['uLightDir', 'uLightCol', 'uShadeCol', 'uAmbCol', 'uFogCol', 'uFog', 'uSunDir', 'uFogSun', 'uShadowPass', 'uDark']) u[k] = L[k];
    return new THREE.ShaderMaterial({ vertexShader: PROP_VS, fragmentShader: PROP_FS, uniforms: u, side: o.side || THREE.FrontSide }); }
  prop(geo, o = {}) { const m = new THREE.Mesh(geo, this.propMat(o)); this.scene.add(m); (this.props = this.props || []).push(m); if (!geo.boundingBox) geo.computeBoundingBox(); if (o.shadow !== false) { const sm = minBlend(this.propMat(o)); sm.depthTest = false; const s = new THREE.Mesh(geo, sm); s.matrixAutoUpdate = false; s.userData.follow = m; s.frustumCulled = false; this.shadowScene.add(s); m.userData.sh = s; } return m; }
  ground(o = {}) {
    const L = this.L; const T = this.tex;
    const u = this.G = { tGrass: U(T.grass), tDirt: U(T.dirt), tRoad: U(T.road), tVar: U(T.var), tShadow: U(this.shadowRT.texture), uRes: U(new THREE.Vector2(this.W, this.H)), uGTint: U(col('#ffffff')), uRTint: U(col('#ffffff')),
      uShadowTint: U(col('#5a5a8c')), uField: U(col('#d8c040')), uSkyRef: U(col('#a8c0d8')), uRoad: U(new THREE.Vector4(3.0, 1.6, 1, 0)), uRoad2: U(new THREE.Vector4(0, 0, 0, 0)), uFieldP: U(new THREE.Vector4(0, 14, 2.4, 0)), uPuddle: U(0), uTime: L.uTime };
    for (const k of ['uFogCol', 'uFog', 'uSunDir', 'uFogSun', 'uDof']) u[k] = L[k];
    const mk = (x0, z0, x1, z1, near) => { const defs = {}; for (const k of ['FIELD', 'ROAD2', 'WET']) if (o[k.toLowerCase()]) defs[k] = 1; if (near) defs.NEAR = 1;
      const g = new THREE.PlaneGeometry(x1 - x0, z1 - z0, 1, 1).rotateX(-Math.PI / 2).translate((x0 + x1) / 2, 0, (z0 + z1) / 2);
      const m = new THREE.Mesh(g, new THREE.ShaderMaterial({ defines: defs, vertexShader: GROUND_VS, fragmentShader: GROUND_FS, uniforms: u })); m.renderOrder = -900; m.frustumCulled = false; this.scene.add(m); return m; };
    const nb = o.road2 ? 3000 : (o.band || 9.5); this.groundMesh = mk(-3000, -nb, 3000, nb, true); if (!o.road2) { this.groundFar = [mk(-3000, nb, 3000, 3000, false), mk(-3000, -3000, 3000, -nb, false)]; } return this.groundMesh;
  }
  grass(tex, list, o = {}) { // list: [x,y,z,scale, atlas, phase, tint, flip]
    const n = list.length; const g = new THREE.InstancedBufferGeometry(); const pg = new THREE.PlaneGeometry(1, 1, 1, 3).translate(0, 0.5, 0);
    g.index = pg.index; g.setAttribute('position', pg.getAttribute('position')); g.setAttribute('uv', pg.getAttribute('uv'));
    const a = new Float32Array(n * 4), b = new Float32Array(n * 4); list.forEach((v, i) => { a.set(v.slice(0, 4), i * 4); b.set(v.slice(4, 8), i * 4); });
    g.setAttribute('iPos', new THREE.InstancedBufferAttribute(a, 4)); g.setAttribute('iPar', new THREE.InstancedBufferAttribute(b, 4)); g.instanceCount = n;
    const L = this.L; const u = { map: U(tex), uTint: U(col(o.tint || '#ffffff')), uTintB: U(col(o.tintB || '#c8d0a0')), uWind: U(new THREE.Vector4(o.wind || 0.06, o.freq || 2.2, 0.03, 0)), uTime: L.uTime };
    for (const k of ['uFogCol', 'uFog', 'uSunDir', 'uFogSun', 'uDof']) u[k] = L[k];
    const m = new THREE.Mesh(g, new THREE.ShaderMaterial({ defines: this.soft ? { SOFT: 1 } : {}, vertexShader: GRASS_VS, fragmentShader: GRASS_FS, uniforms: u, transparent: true, depthWrite: false, blending: THREE.CustomBlending, blendSrc: THREE.OneFactor, blendDst: THREE.OneMinusSrcAlphaFactor, side: THREE.DoubleSide }));
    m.frustumCulled = false; m.renderOrder = o.order || 0; this.scene.add(m); return m;
  }
  rain(n, o = {}) {
    const g = new THREE.InstancedBufferGeometry(); const pg = new THREE.PlaneGeometry(1, 1).translate(0, 0.5, 0); g.index = pg.index; g.setAttribute('position', pg.getAttribute('position')); g.setAttribute('uv', pg.getAttribute('uv'));
    const R = rng(o.seed || 5); const box = o.box || 30; const a = new Float32Array(n * 4); for (let i = 0; i < n; i++) { a[i * 4] = R() * box; a[i * 4 + 1] = R() * 14; a[i * 4 + 2] = R() * box; a[i * 4 + 3] = R(); }
    g.setAttribute('iPos', new THREE.InstancedBufferAttribute(a, 4)); g.instanceCount = n;
    const u = { uTime: this.L.uTime, uRain: U(new THREE.Vector4(o.speed || 9, o.slant || 0.18, o.len || 0.5, box)), uCenter: U(new V3()), uCol: U(col(o.color || '#c8d4e4')), uOp: U(o.op || 0.5) };
    const m = new THREE.Mesh(g, new THREE.ShaderMaterial({ vertexShader: RAIN_VS, fragmentShader: RAIN_FS, uniforms: u, transparent: true, depthWrite: false, blending: THREE.CustomBlending, blendSrc: THREE.OneFactor, blendDst: THREE.OneMinusSrcAlphaFactor, side: THREE.DoubleSide }));
    m.frustumCulled = false; m.renderOrder = 50; this.scene.add(m); m.userData.center = u.uCenter.value; return m;
  }
  setCam(c) { // {p, t, fov, roll, focus, dof}
    const cam = this.cam; cam.position.set(...c.p); cam.up.set(...(c.up || [0, 1, 0])); cam.fov = c.fov || 40; cam.updateProjectionMatrix(); cam.lookAt(c.t[0], c.t[1], c.t[2]);
    if (c.roll) cam.rotateZ(c.roll * Math.PI / 180); cam.updateMatrixWorld(true);
    const fpx = this.H / (2 * Math.tan(cam.fov * Math.PI / 360)); this.L.uOutK.value = (c.line ?? 1.7) * (this.H / 1440) / fpx;
    this.L.uDof.value.set(c.focus || 8, (c.dof || 0) * fpx / 1000, this.soft ? (c.maxBlur || 5.0) : Math.min(c.maxBlur || 2.2, 2.2));
  }
  tick(t) { this.time = t; this.L.uTime.value = t; this.S.uSky.value.w = t; for (const f of this.updaters) f(t); }
  text(fn) { const c = this.tctx; c.clearRect(0, 0, this.W, this.H); this.textOn = !!fn; if (fn) { fn(c, this.W, this.H); this.textTex.needsUpdate = true; } }
  render(t) {
    const r = this.r, L = this.L, cam = this.cam; this.time = t; L.uTime.value = t; this.S.uSky.value.w = t;
    for (const f of this.updaters) f(t);
    for (const c of this.cards) c.update(cam, t);
    this.scene.updateMatrixWorld(true);
    this.S.uInvPV.value.multiplyMatrices(cam.projectionMatrix, cam.matrixWorldInverse).invert();
    this.shadowScene.traverse(o => { if (o.userData.follow) { o.matrix.copy(o.userData.follow.matrixWorld); o.matrixWorld.copy(o.matrix); } });
    // shadows as seen by the lens
    const OFF = window.OFF || ''; if (this.groundMesh) { const gv = !OFF.includes('ground'); this.groundMesh.visible = gv; (this.groundFar || []).forEach(m => m.visible = gv); } this.sky.visible = !OFF.includes('sky');
    if (!OFF.includes('shadow')) { L.uShadowPass.value = 1; r.setRenderTarget(this.shadowRT); r.setClearColor(0xffffff, 1); r.clear(true, false, false); r.render(this.shadowScene, cam); L.uShadowPass.value = 0; }
    r.setRenderTarget(this.frameRT); r.setClearColor(0x000000, 1); r.clear(true, true, false); r.render(this.scene, cam);
    // bloom
    if (!OFF.includes('bloom')) { this.postMesh.material = this.mBright; this.mBright.uniforms.t.value = this.frameRT.texture; r.setRenderTarget(this.b1); r.render(this.postScene, this.postCam);
    for (let i = 0; i < 2; i++) { this.postMesh.material = this.mBlur; this.mBlur.uniforms.t.value = this.b1.texture; this.mBlur.uniforms.dir.value.set((1.6 + i * 2.2) * 4 / this.W, 0); r.setRenderTarget(this.b2); r.render(this.postScene, this.postCam);
      this.mBlur.uniforms.t.value = this.b2.texture; this.mBlur.uniforms.dir.value.set(0, (1.6 + i * 2.2) * 4 / this.H); r.setRenderTarget(this.b1); r.render(this.postScene, this.postCam); } }
    this.P.uPost.value.w = t; r.setRenderTarget(null); this.postMesh.material = this.mFinal; this.mFinal.uniforms.uFxaa.value = 0; r.setScissorTest(false); r.render(this.postScene, this.postCam);
    // antialias the hard-edged 3D things only
    this.mFinal.uniforms.uFxaa.value = 1; r.setScissorTest(true);
    if (!OFF.includes('fxaa')) for (const a of this.actors) { const b = a.screenBox(cam, this.W, this.H); if (b) { r.setScissor(b[0], b[1], b[2], b[3]); r.render(this.postScene, this.postCam); } }
    for (const m of (this.props || [])) { const b = this.propBox(m, cam); if (b) { r.setScissor(b[0], b[1], b[2], b[3]); r.render(this.postScene, this.postCam); } }
    r.setScissorTest(false);
    if (this.textOn) { this.postMesh.material = this.mText; r.render(this.postScene, this.postCam); }
  }
  propBox(m, cam) { const bb = m.geometry.boundingBox; const v = this._pv || (this._pv = new V3()); let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9; const W = this.W, H = this.H;
    for (let i = 0; i < 8; i++) { v.set(i & 1 ? bb.max.x : bb.min.x, i & 2 ? bb.max.y : bb.min.y, i & 4 ? bb.max.z : bb.min.z).applyMatrix4(m.matrixWorld).project(cam); if (v.z > 1 || v.z < -1) return null; const x = (v.x * 0.5 + 0.5) * W, y = (v.y * 0.5 + 0.5) * H; x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
    x0 = Math.max(0, Math.floor(x0 - 6)); y0 = Math.max(0, Math.floor(y0 - 6)); x1 = Math.min(W, Math.ceil(x1 + 6)); y1 = Math.min(H, Math.ceil(y1 + 6)); if (x1 - x0 < 3 || y1 - y0 < 3 || (x1 - x0) * (y1 - y0) > 0.6 * W * H) return null; return [x0, y0, x1 - x0, y1 - y0]; }
  pixels() { const gl = this.gl; if (!this._px) this._px = new Uint8Array(this.W * this.H * 4); gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.readPixels(0, 0, this.W, this.H, gl.RGBA, gl.UNSIGNED_BYTE, this._px); return this._px; }
}

export class Actor {
  constructor(E, asset, pal) {
    this.E = E; this.asset = asset; const m = asset.meta; this.meta = m; this.bi = asset.bi;
    const bones = this.bones = m.bones.map((n, i) => { const b = new THREE.Bone(); b.name = n; return b; });
    this.rest = m.head.map((h, i) => { const p = m.parent[i]; return p < 0 ? new V3(...h) : new V3(h[0] - m.head[p][0], h[1] - m.head[p][1], h[2] - m.head[p][2]); });
    this.rest[m.parent.indexOf(-1)].set(0, 0, 0); m.parent.forEach((p, i) => { bones[i].position.copy(this.rest[i]); if (p >= 0) bones[p].add(bones[i]); });
    const rootB = bones[m.parent.indexOf(-1)]; rootB.updateMatrixWorld(true);
    this.skel = new THREE.Skeleton(bones);
    this.pal = pal.map(c => col(c));
    this.mat = E.charMat(this.pal, false); this.omat = E.charMat(this.pal, true);
    const mesh = this.mesh = new THREE.SkinnedMesh(asset.geo, this.mat); mesh.add(rootB); mesh.bind(this.skel); mesh.frustumCulled = false;
    const o = this.outline = new THREE.SkinnedMesh(asset.geo, this.omat); o.bind(this.skel, new THREE.Matrix4()); o.frustumCulled = false; mesh.add(o);
    E.scene.add(mesh);
    this.smat = minBlend(E.charMat(this.pal, false)); this.smat.depthTest = false; const s = this.shadow = new THREE.SkinnedMesh(asset.geo, this.smat); s.bind(this.skel, new THREE.Matrix4()); s.frustumCulled = false; s.matrixAutoUpdate = false; s.userData.follow = mesh; E.shadowScene.add(s);
    this.pelvis = bones[this.bi.pelvis]; this.q0 = new THREE.Quaternion(); this.q1 = new THREE.Quaternion(); this.extra = {}; this.blinkSeed = Math.random() * 10; this.visible = true; this.anchor = new V3();
    this._v = new V3(); this.autoBlink = true; this.blinkOff = (asset.meta.name.length * 1.7) % 5;
  }
  place(x, y, z, yaw = 0) { this.mesh.position.set(x, y, z); this.mesh.rotation.y = yaw; return this; }
  show(v) { this.mesh.visible = v; this.shadow.visible = v; this.visible = v; return this; }
  // f: fractional frame (30 Hz). extra: {bone: THREE.Quaternion premultiplied in the parent's frame}
  pose(track, f) {
    const d = track.d, st = track.st, F = track.F; f = clamp(f, 0, F - 1.001); const f0 = Math.floor(f), a = f - f0; const o0 = 2 + f0 * st, o1 = o0 + st;
    this.pelvis.position.set(lerp(d[o0], d[o1], a), lerp(d[o0 + 1], d[o1 + 1], a), lerp(d[o0 + 2], d[o1 + 2], a));
    const q0 = this.q0, q1 = this.q1, B = this.bones;
    for (let i = 0; i < B.length; i++) { const k = 3 + i * 4; q0.set(d[o0 + k], d[o0 + k + 1], d[o0 + k + 2], d[o0 + k + 3]); q1.set(d[o1 + k], d[o1 + k + 1], d[o1 + k + 2], d[o1 + k + 3]); B[i].quaternion.slerpQuaternions(q0, q1, a); }
    for (const k in this.extra) { const b = B[this.bi[k]]; b.quaternion.premultiply(this.extra[k]); }
    if (this.autoBlink) { const t = this.E.time + this.blinkOff; const ph = t % 3.7; const bl = ph < 0.07 ? ph / 0.07 : ph < 0.11 ? 1 : ph < 0.21 ? 1 - (ph - 0.11) / 0.10 : 0; this.mat.uniforms.uBlink.value = Math.max(bl, this.blinkHold || 0); }
    return this;
  }
  rootAt(track, f, out) { const d = track.d, st = track.st; f = clamp(f, 0, track.F - 1.001); const f0 = Math.floor(f), a = f - f0, o0 = 2 + f0 * st, o1 = o0 + st; out.set(lerp(d[o0], d[o1], a), lerp(d[o0 + 1], d[o1 + 1], a), lerp(d[o0 + 2], d[o1 + 2], a)); return out; }
  world(bone, out = new V3()) { this.mesh.updateMatrixWorld(true); return out.setFromMatrixPosition(this.bones[this.bi[bone]].matrixWorld); }
  screenBox(cam, W, H) {
    if (!this.visible) return null; let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9, any = false; const v = this._v;
    for (const n of ['head', 'hand_l', 'hand_r', 'ball_l', 'ball_r', 'pelvis', 'lowerarm_l', 'lowerarm_r', 'calf_l', 'calf_r']) { v.setFromMatrixPosition(this.bones[this.bi[n]].matrixWorld); if (n === 'head') v.y += 0.22 * this.meta.S; if (n.startsWith('ball')) v.y -= 0.06; v.project(cam); if (v.z > 1 || v.z < -1) continue; any = true;
      const x = (v.x * 0.5 + 0.5) * W, y = (v.y * 0.5 + 0.5) * H; x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
    if (!any) return null; const pad = 0.16 * Math.max(x1 - x0, y1 - y0) + 14; x0 = Math.max(0, Math.floor(x0 - pad)); y0 = Math.max(0, Math.floor(y0 - pad)); x1 = Math.min(W, Math.ceil(x1 + pad)); y1 = Math.min(H, Math.ceil(y1 + pad));
    if (x1 <= x0 || y1 <= y0) return null; return [x0, y0, x1 - x0, y1 - y0];
  }
}

export class Card {
  constructor(E, tex, o) {
    this.E = E; this.o = o; const L = E.L; const w = o.w, h = o.h || o.w * tex.userData.h / tex.userData.w * (o.uv ? o.uv[3] / o.uv[2] : 1);
    this.w = w; this.h = h;
    const g = new THREE.PlaneGeometry(1, 1, o.sx || (o.w > 400 ? 12 : 1), o.sy || (o.sway ? 6 : 1)).translate(0, o.center ? 0 : 0.5, 0);
    const u = this.u = { map: U(tex), uTint: U(col(o.tint || '#ffffff')), uTint2: U(col(o.tint2 || o.tint || '#ffffff')), uLit: U(col(o.lit || '#ffffff')), uShd: U(col(o.shd || '#8090b0')), uOpacity: U(o.op ?? 1), uBlur: U(o.blur ?? -1), uFogK: U(o.fog ?? 1), uRelight: U(o.relight ? 1 : 0),
      uSway: U(new THREE.Vector4((o.sway || 0) / w, o.swf || 1.3, o.ph ?? (o.x * 0.37 + o.z * 0.91), o.gust || 0.3)), uUvr: U(new THREE.Vector4(...(o.uv || [0, 0, 1, 1]))), uCardW: U(w), uCardPos: U(new V3(o.x, o.y || 0, o.z)), uShStr: U(o.shStr ?? 0.62), uAlphaCut: U(0) };
    for (const k of ['uFogCol', 'uFog', 'uSunDir', 'uFogSun', 'uTime', 'uDof', 'uShadowPass', 'uLightDir']) u[k] = L[k];
    const add = o.add; const defs = o.relight ? { RELIGHT: 1 } : {}; if (E.soft && o.blur === undefined) defs.SOFT = 1; const m = this.m = new THREE.Mesh(g, new THREE.ShaderMaterial({ defines: defs, vertexShader: CARD_VS, fragmentShader: CARD_FS, uniforms: u, transparent: true, depthWrite: false, depthTest: o.depthTest !== false, side: THREE.DoubleSide,
      blending: THREE.CustomBlending, blendSrc: THREE.OneFactor, blendDst: add ? THREE.OneFactor : THREE.OneMinusSrcAlphaFactor }));
    m.scale.set(w * (o.flip ? -1 : 1), h, 1); m.position.set(o.x, o.y || 0, o.z); if (o.ry !== undefined) m.rotation.y = o.ry; if (o.rx !== undefined) { m.rotation.order = 'YXZ'; m.rotation.x = o.rx; }
    m.frustumCulled = false; if (o.order !== undefined) m.renderOrder = o.order; E.scene.add(m);
    if (o.shadow) { const sm = minBlend(new THREE.ShaderMaterial({ defines: defs, vertexShader: CARD_VS, fragmentShader: CARD_FS, uniforms: u, depthTest: false, depthWrite: false, side: THREE.DoubleSide })); const s = this.s = new THREE.Mesh(g, sm); s.frustumCulled = false; E.shadowScene.add(s); }
    this.mode = o.mode || (o.ry !== undefined ? 'fix' : 'y');
  }
  update(cam) { const m = this.m; if (this.mode === 'y') m.rotation.y = Math.atan2(cam.position.x - m.position.x, cam.position.z - m.position.z); else if (this.mode === 'face') m.quaternion.copy(cam.quaternion);
    this.u.uCardPos.value.copy(m.position); if (this.s) { this.s.position.copy(m.position); this.s.scale.copy(m.scale); this.s.rotation.copy(m.rotation); } }
}
// shadow blending: keep the darkest
export function minBlend(mat) { mat.blending = THREE.CustomBlending; mat.blendEquation = THREE.MinEquation; mat.blendSrc = THREE.OneFactor; mat.blendDst = THREE.OneFactor; return mat; }
export async function post(path, body) { for (let i = 0; i < 4; i++) { try { const r = await fetch(path, { method: 'POST', body }); if (r.ok) return; } catch (e) { } await new Promise(r => setTimeout(r, 300)); } }
export const log = (...a) => post('/log', a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' '));
