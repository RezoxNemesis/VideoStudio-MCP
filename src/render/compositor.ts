/**
 * GPU compositor (WebGL2). Each layer is drawn with its transform and grade into a layer buffer, optionally
 * blurred, then blended onto the frame with its blend mode. Adjustment clips grade the frame built so far.
 * Everything is premultiplied alpha.
 */
import type { LayerDraw } from "../core/evaluate";
import type { BlendMode } from "../core/model";
import { CURVE_RES, CURVE_ROWS, bakeCurves, curveMask } from "../color/engine";
import { lutFor } from "../color/luts";
import { MAX_NODES, type ColorNode } from "../color/types";

const VS_LAYER = `#version 300 es
in vec2 aPos;
uniform vec2 uRes;      // frame size in px
uniform vec2 uSize;     // quad size in px
uniform vec2 uCenter;   // anchor position in px
uniform vec2 uAnchor;   // 0..1 within the quad
uniform float uRot;     // radians
uniform vec2 uFlip;
uniform float uPinOn;
uniform mat3 uHomo;     // unit square -> frame px (perspective corner pin)
out vec2 vUv;
void main() {
  vUv = mix(aPos, 1.0 - aPos, uFlip);
  if (uPinOn > 0.5) {
    vec3 h = uHomo * vec3(aPos, 1.0);
    // Homogeneous w = h.z gives perspective-correct texture interpolation.
    gl_Position = vec4(h.x / uRes.x * 2.0 - h.z, h.z - h.y / uRes.y * 2.0, 0.0, h.z);
    return;
  }
  vec2 p = (aPos - uAnchor) * uSize;
  float c = cos(uRot), s = sin(uRot);
  p = vec2(p.x * c - p.y * s, p.x * s + p.y * c) + uCenter;
  vec2 clip = vec2(p.x / uRes.x * 2.0 - 1.0, 1.0 - p.y / uRes.y * 2.0);
  gl_Position = vec4(clip, 0.0, 1.0);
}`;

const VS_FULL = `#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() { gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0); vUv = aPos; }`;

const GRADE_FN = `
uniform float uExposure, uContrast, uSaturation, uVibrance, uTemp, uTint, uHue, uLift, uGamma, uGain, uVignette, uGrain, uSharpen, uSeed;
uniform vec2 uTexel;
uniform vec4 uKey;      // rgb, enabled
uniform vec3 uKeyParams; // similarity, smoothness, spill
uniform vec4 uMask;     // x, y, w, h
uniform vec3 uMaskParams; // shape(0 none,1 rect,2 ellipse), feather, invert
uniform float uOpacity;
uniform vec4 uCrop;        // l, t, r, b fractions trimmed from the source
uniform float uPixelate;   // block size, uv units
uniform float uGlitch;     // 0..1
uniform float uRgbSplit;   // uv units
uniform float uZoomBlur;   // 0..1
uniform vec2 uDirBlur;     // uv units
uniform float uPosterize, uInvert, uDuo, uScan, uShadows, uHighlights, uLetterbox;
uniform vec3 uDuoDark, uDuoLight, uWLift, uWGamma, uWGain;
// Colour page: grading nodes (see color/engine.ts for the same maths on the CPU).
#define MAXN ${MAX_NODES}
#define CURVE_RES ${CURVE_RES}
#define CURVE_ROWS ${CURVE_ROWS}
uniform int uNodeCount;
uniform vec4 uNLift[MAXN], uNGamma[MAXN], uNGain[MAXN];
uniform vec4 uNOffset[MAXN]; // rgb, mix
uniform vec4 uNPrim[MAXN];   // contrast, pivot, saturation, hue (radians)
uniform vec4 uNPrim2[MAXN];  // temperature, tint, LUT slot (-1 none), LUT mix
uniform vec4 uNMode[MAXN];   // kind (0 serial, 1 layer), blend, curve mask, show this node's key
uniform vec4 uNQH[MAXN];     // qualifier on, hue, half-width, softness
uniform vec4 uNQS[MAXN];     // sat low, high, softness, invert
uniform vec4 uNQL[MAXN];     // luma low, high, softness, -
uniform vec4 uNWin[MAXN];    // window x, y, w, h
uniform vec4 uNWin2[MAXN];   // shape (0 none, 1 rect, 2 ellipse, 3 gradient), rotation (radians), softness, invert
uniform highp sampler2D uCurves; // CURVE_RES x rows, one row per curve
uniform float uCurveRows;
uniform highp sampler3D uLut0, uLut1, uLut2, uLut3;
uniform vec4 uLutSize;
uniform float uNodeAspect;   // picture width / height
uniform float uBypass;       // 1 = show the picture without any colour work
uniform float uSplitX;       // frame px: left of this line the picture is shown ungraded (compare wipe); < 0 off

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + uSeed) * 43758.5453); }
float hash0(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
vec3 unpm(vec4 c) { return c.a > 1e-5 ? c.rgb / c.a : vec3(0.0); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec2 cbcr(vec3 c) { return vec2(-0.1687 * c.r - 0.3313 * c.g + 0.5 * c.b, 0.5 * c.r - 0.4187 * c.g - 0.0813 * c.b); }

vec4 tap(sampler2D tex, vec2 q) {
  if (q.x < 0.0 || q.y < 0.0 || q.x > 1.0 || q.y > 1.0) return vec4(0.0);
  if (uRgbSplit <= 0.0) return texture(tex, q);
  vec4 r = texture(tex, q + vec2(uRgbSplit, 0.0));
  vec4 g = texture(tex, q);
  vec4 b = texture(tex, q - vec2(uRgbSplit, 0.0));
  return vec4(r.r, g.g, b.b, max(max(r.a, g.a), b.a));
}

/** Source lookup with crop, pixelate, glitch, RGB split, zoom blur and directional blur. */
vec4 fetchFx(sampler2D tex, vec2 uv) {
  vec2 q = uCrop.xy + uv * (1.0 - uCrop.xy - uCrop.zw);
  if (uPixelate > 0.0) {
    vec2 px = vec2(uPixelate) * (1.0 - uCrop.xy - uCrop.zw);
    q = (floor(q / px) + 0.5) * px;
  }
  if (uGlitch > 0.0) {
    float band = floor(uv.y * 28.0);
    float t = floor(uSeed * 3.0);
    if (hash0(vec2(band, t)) < uGlitch * 0.55) q.x += (hash0(vec2(band, t + 7.0)) - 0.5) * 0.18 * uGlitch;
    if (hash0(vec2(floor(uv.y * 6.0), t + 3.0)) < uGlitch * 0.2) q.y += 0.02 * uGlitch;
  }
  bool zb = uZoomBlur > 0.001;
  bool db = abs(uDirBlur.x) + abs(uDirBlur.y) > 0.0005;
  if (!zb && !db) return tap(tex, q);
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 10; i++) {
    float f = float(i) / 9.0;
    vec2 p = q;
    if (zb) p = mix(q, vec2(0.5), f * uZoomBlur * 0.12);
    if (db) p += uDirBlur * (f - 0.5);
    acc += tap(tex, p);
  }
  return acc / 10.0;
}

float cv(float row, float x) {
  return texture(uCurves, vec2((clamp(x, 0.0, 1.0) * float(CURVE_RES - 1) + 0.5) / float(CURVE_RES), (row + 0.5) / uCurveRows)).r;
}
// Hue 0..1 and saturation ((max-min)/max) of a colour.
vec2 hsOf(vec3 c0) {
  vec3 c = clamp(c0, 0.0, 1.0);
  float mx = max(max(c.r, c.g), c.b);
  float mn = min(min(c.r, c.g), c.b);
  float d = mx - mn;
  float h = 0.0;
  if (d > 1e-6) {
    if (mx == c.r) h = mod((c.g - c.b) / d, 6.0);
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    h /= 6.0;
    if (h < 0.0) h += 1.0;
  }
  return vec2(h, mx > 1e-6 ? d / mx : 0.0);
}
vec3 hueRot(vec3 c, float rad) {
  float y = dot(c, vec3(0.299, 0.587, 0.114));
  float i = dot(c, vec3(0.596, -0.274, -0.322));
  float q = dot(c, vec3(0.211, -0.523, 0.312));
  float cs = cos(rad), sn = sin(rad);
  float i2 = i * cs - q * sn;
  float q2 = i * sn + q * cs;
  return vec3(y + 0.956 * i2 + 0.621 * q2, y - 0.272 * i2 - 0.647 * q2, y - 1.106 * i2 + 1.703 * q2);
}
vec3 lutAt(int slot, vec3 c) {
  float n = slot == 0 ? uLutSize.x : slot == 1 ? uLutSize.y : slot == 2 ? uLutSize.z : uLutSize.w;
  vec3 q = clamp(c, 0.0, 1.0) * ((n - 1.0) / n) + 0.5 / n;
  if (slot == 0) return texture(uLut0, q).rgb;
  if (slot == 1) return texture(uLut1, q).rgb;
  if (slot == 2) return texture(uLut2, q).rgb;
  return texture(uLut3, q).rgb;
}
float qualKey(int i, vec3 c) {
  if (uNQH[i].x < 0.5) return 1.0;
  vec2 hs = hsOf(c);
  float L = clamp(luma(c), 0.0, 1.0);
  float dh = abs(fract(hs.x - uNQH[i].y + 0.5) - 0.5);
  float kh = uNQH[i].z >= 0.5 ? 1.0 : 1.0 - smoothstep(uNQH[i].z, uNQH[i].z + max(uNQH[i].w, 1e-4), dh);
  float ss = max(uNQS[i].z, 1e-4);
  float ks = smoothstep(uNQS[i].x - ss, uNQS[i].x, hs.y) * (1.0 - smoothstep(uNQS[i].y, uNQS[i].y + ss, hs.y));
  float ls = max(uNQL[i].z, 1e-4);
  float kl = smoothstep(uNQL[i].x - ls, uNQL[i].x, L) * (1.0 - smoothstep(uNQL[i].y, uNQL[i].y + ls, L));
  float k = kh * ks * kl;
  return uNQS[i].w > 0.5 ? 1.0 - k : k;
}
float winKey(int i, vec2 uv) {
  float shape = uNWin2[i].x;
  if (shape < 0.5) return 1.0;
  vec2 d = uv - uNWin[i].xy;
  d.x *= uNodeAspect;
  float r = -uNWin2[i].y;
  float cs = cos(r), sn = sin(r);
  d = vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
  float soft = max(uNWin2[i].z, 1e-3);
  float m;
  if (shape > 2.5) m = 1.0 - smoothstep(-0.5, 0.5, d.y / max(uNWin[i].z, 1e-4));
  else {
    vec2 hf = max(vec2(uNWin[i].z * uNodeAspect, uNWin[i].w) * 0.5, vec2(1e-4));
    if (shape < 1.5) m = (1.0 - smoothstep(1.0 - soft, 1.0 + soft, abs(d.x) / hf.x)) * (1.0 - smoothstep(1.0 - soft, 1.0 + soft, abs(d.y) / hf.y));
    else m = 1.0 - smoothstep(1.0 - soft, 1.0 + soft, length(d / hf));
  }
  return uNWin2[i].w > 0.5 ? 1.0 - m : m;
}
vec3 nodeBlend(float mode, vec3 a, vec3 b) {
  if (mode < 0.5) return b;
  if (mode < 1.5) return a + b;
  if (mode < 2.5) return a * b;
  if (mode < 3.5) return 1.0 - (1.0 - a) * (1.0 - b);
  if (mode < 4.5) return mix(2.0 * a * b, 1.0 - 2.0 * (1.0 - a) * (1.0 - b), step(0.5, a));
  return (1.0 - 2.0 * b) * a * a + 2.0 * b * a;
}
vec3 nodes(vec3 c0, vec2 uv) {
  vec3 c = c0;
  for (int i = 0; i < MAXN; i++) {
    if (i >= uNodeCount) break;
    bool layerNode = uNMode[i].x > 0.5;
    vec3 inp = layerNode ? c0 : c;
    float key = qualKey(i, inp) * winKey(i, uv);
    if (uNMode[i].w > 0.5) return mix(vec3(0.5), inp, key);
    vec3 g = inp * vec3(1.0 + uNPrim2[i].x * 0.12, 1.0 + uNPrim2[i].y * 0.08, 1.0 - uNPrim2[i].x * 0.12);
    g += uNOffset[i].rgb;
    g = g * (1.0 + uNGain[i].rgb + uNGain[i].a) + (uNLift[i].rgb + uNLift[i].a) * (1.0 - g);
    g = pow(max(g, 0.0), vec3(1.0) / max(vec3(0.05), 1.0 + uNGamma[i].rgb + uNGamma[i].a));
    g = (g - uNPrim[i].y) * uNPrim[i].x + uNPrim[i].y;
    g = mix(vec3(luma(g)), g, uNPrim[i].z);
    if (abs(uNPrim[i].w) > 0.000175) g = hueRot(g, uNPrim[i].w);
    int mk = int(uNMode[i].z + 0.5);
    if (mk != 0) {
      float row = float(i * CURVE_ROWS);
      if ((mk & 1) != 0) g = vec3(cv(row, g.r), cv(row, g.g), cv(row, g.b));
      if ((mk & 2) != 0) g.r = cv(row + 1.0, g.r);
      if ((mk & 4) != 0) g.g = cv(row + 2.0, g.g);
      if ((mk & 8) != 0) g.b = cv(row + 3.0, g.b);
      if ((mk & 496) != 0) {
        vec2 hs = hsOf(g);
        if ((mk & 16) != 0) g = hueRot(g, (cv(row + 4.0, hs.x) - 0.5) * 3.14159265);
        float k = 1.0;
        if ((mk & 32) != 0) k *= cv(row + 5.0, hs.x) * 2.0;
        if ((mk & 128) != 0) k *= cv(row + 7.0, clamp(luma(g), 0.0, 1.0)) * 2.0;
        if ((mk & 256) != 0) k *= cv(row + 8.0, hs.y) * 2.0;
        if (k != 1.0) g = mix(vec3(luma(g)), g, k);
        if ((mk & 64) != 0) g *= 1.0 + (cv(row + 6.0, hs.x) * 2.0 - 1.0) * hs.y;
      }
    }
    if (uNPrim2[i].z > -0.5 && uNPrim2[i].w > 0.0) g = mix(g, lutAt(int(uNPrim2[i].z + 0.5), g), clamp(uNPrim2[i].w, 0.0, 1.0));
    vec3 top = layerNode ? nodeBlend(uNMode[i].y, c, g) : g;
    c = mix(c, top, key * clamp(uNOffset[i].a, 0.0, 1.0));
  }
  return c;
}

vec4 grade(sampler2D tex, vec2 uv) {
  vec4 src = fetchFx(tex, uv);
  vec3 c = unpm(src);
  float a = src.a;
  if (uSharpen > 0.0) {
    vec3 n = unpm(texture(tex, uv + vec2(uTexel.x, 0.0))) + unpm(texture(tex, uv - vec2(uTexel.x, 0.0)))
           + unpm(texture(tex, uv + vec2(0.0, uTexel.y))) + unpm(texture(tex, uv - vec2(0.0, uTexel.y)));
    c += uSharpen * (c * 4.0 - n) * 0.5;
  }
  if (uKey.a > 0.5) {
    float d = distance(cbcr(c), cbcr(uKey.rgb));
    float m = smoothstep(uKeyParams.x, uKeyParams.x + max(uKeyParams.y, 1e-3), d);
    a *= m;
    float sp = pow(clamp(1.0 - m, 0.0, 1.0), 1.5) * uKeyParams.z;
    c = mix(c, vec3(luma(c)), sp);
  }
  vec3 ungraded = c;
  c *= exp2(uExposure);
  c *= vec3(1.0 + uTemp * 0.12, 1.0 + uTint * 0.08, 1.0 - uTemp * 0.12);
  c = c * uGain + uLift * (1.0 - c);
  c = pow(max(c, 0.0), vec3(1.0 / max(uGamma, 0.05)));
  // Colour wheels: lift (shadows), gamma (mids), gain (highlights).
  c = c * (1.0 + uWGain) + uWLift * (1.0 - c);
  c = pow(max(c, 0.0), vec3(1.0) / max(vec3(0.05), 1.0 + uWGamma));
  if (uShadows != 0.0 || uHighlights != 0.0) {
    float L = luma(c);
    c += uShadows * 0.35 * (1.0 - smoothstep(0.0, 0.55, L));
    c += uHighlights * 0.35 * smoothstep(0.45, 1.0, L);
  }
  c = (c - 0.5) * uContrast + 0.5;
  float l = luma(c);
  float sat = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
  c = mix(vec3(l), c, uSaturation * (1.0 + uVibrance * (1.0 - sat)));
  if (abs(uHue) > 0.01) {
    float h = radians(uHue);
    mat3 toYiq = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);
    mat3 toRgb = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);
    vec3 y = toYiq * c;
    float cs = cos(h), sn = sin(h);
    y = vec3(y.x, y.y * cs - y.z * sn, y.y * sn + y.z * cs);
    c = toRgb * y;
  }
  if (uBypass > 0.5 || gl_FragCoord.x < uSplitX) c = ungraded;
  else if (uNodeCount > 0) c = nodes(c, uv);
  if (uVignette > 0.0) {
    float v = smoothstep(0.85, 0.2, length((uv - 0.5) * vec2(1.0, 1.0)) * (0.6 + uVignette));
    c *= mix(1.0, v, uVignette);
  }
  if (uGrain > 0.0) c += (hash(uv * 1000.0) - 0.5) * uGrain * 0.25;
  if (uDuo > 0.0) c = mix(c, mix(uDuoDark, uDuoLight, smoothstep(0.05, 0.95, luma(c))), uDuo);
  if (uPosterize > 1.5) c = floor(clamp(c, 0.0, 1.0) * uPosterize + 0.5) / uPosterize;
  if (uInvert > 0.0) c = mix(c, 1.0 - c, uInvert);
  if (uScan > 0.0) c *= 1.0 - uScan * 0.45 * (0.5 + 0.5 * sin(gl_FragCoord.y * 3.14159));
  c = clamp(c, 0.0, 1.0);
  if (uLetterbox > 0.0 && (uv.y < uLetterbox || uv.y > 1.0 - uLetterbox)) c = vec3(0.0);
  if (uMaskParams.x > 0.5) {
    vec2 d = (uv - uMask.xy) / max(uMask.zw * 0.5, vec2(1e-4));
    float f = max(uMaskParams.y, 1e-3) / max(min(uMask.z, uMask.w), 1e-3) * 2.0;
    float m = uMaskParams.x < 1.5
      ? (1.0 - smoothstep(1.0 - f, 1.0, abs(d.x))) * (1.0 - smoothstep(1.0 - f, 1.0, abs(d.y)))
      : 1.0 - smoothstep(1.0 - f, 1.0, length(d));
    if (uMaskParams.z > 0.5) m = 1.0 - m;
    a *= m;
  }
  a *= uOpacity;
  return vec4(c * a, a);
}`;

const FS_LAYER = `#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
out vec4 o;
${GRADE_FN}
void main() { o = grade(uTex, vUv); }`;

const FS_BLUR = `#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uDir; // texel step * spread
out vec4 o;
void main() {
  float w[7] = float[](0.1964825501511404, 0.2969069646728344, 0.09447039785044732, 0.010381362401148057, 0.0, 0.0, 0.0);
  vec4 acc = texture(uTex, vUv) * w[0];
  for (int i = 1; i < 4; i++) {
    vec2 off = uDir * (float(i) * 1.3846153846);
    acc += texture(uTex, vUv + off) * w[i];
    acc += texture(uTex, vUv - off) * w[i];
  }
  o = acc;
}`;

const FS_BLEND = `#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uBase;
uniform sampler2D uLayer;
uniform int uMode;
out vec4 o;
vec3 unpm(vec4 c) { return c.a > 1e-5 ? c.rgb / c.a : vec3(0.0); }
vec3 blendFn(vec3 b, vec3 s) {
  if (uMode == 1) return min(b + s, 1.0);
  if (uMode == 2) return b * s;
  if (uMode == 3) return b + s - b * s;
  if (uMode == 4) return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, b));
  if (uMode == 5) return min(b, s);
  if (uMode == 6) return max(b, s);
  return s;
}
void main() {
  vec4 b = texture(uBase, vUv);
  vec4 s = texture(uLayer, vUv);
  vec3 mixed = blendFn(unpm(b), unpm(s));
  vec3 rgb = s.rgb * (1.0 - b.a) + b.rgb * (1.0 - s.a) + s.a * b.a * mixed;
  o = vec4(rgb, s.a + b.a * (1.0 - s.a));
}`;

const FS_ADJUST = `#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
out vec4 o;
${GRADE_FN}
void main() {
  vec4 base = texture(uTex, vUv);
  float keep = uOpacity;
  vec4 g = grade(uTex, vUv);
  // grade() multiplied alpha by opacity; restore full alpha and cross-fade with the ungraded frame instead.
  vec3 gc = g.a > 1e-5 ? g.rgb / g.a * base.a : vec3(0.0);
  o = vec4(mix(base.rgb, gc, keep), base.a);
}`;

const FS_GLOW = `#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uThr;
out vec4 o;
void main() {
  vec4 c = texture(uTex, vUv);
  vec3 u = c.a > 1e-5 ? c.rgb / c.a : vec3(0.0);
  float L = dot(u, vec3(0.2126, 0.7152, 0.0722));
  o = c * smoothstep(uThr, uThr + 0.18, L);
}`;

const FS_ADDGLOW = `#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uBase;
uniform sampler2D uGlow;
uniform float uK;
out vec4 o;
void main() {
  vec4 b = texture(uBase, vUv);
  vec4 g = texture(uGlow, vUv) * uK;
  o = clamp(vec4(b.rgb + g.rgb, max(b.a, min(1.0, g.a))), 0.0, 1.0);
}`;

const FS_OUT = `#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec4 uBg;
out vec4 o;
void main() {
  vec4 c = texture(uTex, vUv);
  o = vec4(c.rgb + uBg.rgb * (1.0 - c.a), 1.0);
}`;

const BLEND_ID: Record<BlendMode, number> = { normal: 0, add: 1, multiply: 2, screen: 3, overlay: 4, darken: 5, lighten: 6 };

interface Target {
  fb: WebGLFramebuffer;
  tex: WebGLTexture;
  w: number;
  h: number;
}

type Prog = { p: WebGLProgram; u: Map<string, WebGLUniformLocation | null> };

export type LayerSource = TexImageSource & { width?: number; height?: number; displayWidth?: number; displayHeight?: number };

export interface LayerInput {
  draw: LayerDraw;
  /** Pixel content of the layer, or null for adjustment layers. */
  source: LayerSource | null;
  /** Size of the source in pixels. */
  sw: number;
  sh: number;
  /** A key that changes when the source pixels change, so unchanged layers skip re-upload. */
  version: string;
  /** Source already at project scale (text, shapes): drawn 1:1 instead of fitted. */
  native?: boolean;
}

export class Compositor {
  readonly gl: WebGL2RenderingContext;
  private quad: WebGLBuffer;
  private vao: WebGLVertexArrayObject;
  private progs: Record<"layer" | "blur" | "blend" | "adjust" | "out" | "glow" | "addglow", Prog>;
  private targets: Target[] = [];
  private texCache = new Map<string, { tex: WebGLTexture; version: string; used: number }>();
  private frame = 0;
  private dataTex = new Map<string, { tex: WebGLTexture; used: number }>();
  /** Viewer options for the colour page: bypass all colour work, a compare wipe (0..1 of the frame width, left side ungraded), and "show the key" for one node. */
  bypass = false;
  split = -1;
  showKey: { clipId: string; nodeId: string } | null = null;
  width = 0;
  height = 0;
  /** The GPU (or software renderer) behind this context, for diagnostics. */
  renderer = "";

  /** True once the GPU context has gone away (driver or GPU process restart); the compositor must be rebuilt. */
  get lost(): boolean {
    return this.gl.isContextLost();
  }

  constructor(readonly canvas: HTMLCanvasElement | OffscreenCanvas) {
    const gl = canvas.getContext("webgl2", { premultipliedAlpha: true, alpha: false, antialias: false, preserveDrawingBuffer: true }) as WebGL2RenderingContext | null;
    if (!gl) throw new Error("The GPU did not give this window a WebGL2 context.");
    if (gl.isContextLost()) throw new Error("The GPU context is not ready yet.");
    this.gl = gl;
    try {
      const dbg = gl.getExtension("WEBGL_debug_renderer_info");
      this.renderer = String(gl.getParameter(dbg ? dbg.UNMASKED_RENDERER_WEBGL : gl.RENDERER) ?? "");
    } catch {
      this.renderer = "";
    }
    this.quad = gl.createBuffer()!;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quad);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
    this.vao = gl.createVertexArray()!;
    gl.bindVertexArray(this.vao);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    this.progs = {
      layer: this.program(VS_LAYER, FS_LAYER),
      blur: this.program(VS_FULL, FS_BLUR),
      blend: this.program(VS_FULL, FS_BLEND),
      adjust: this.program(VS_FULL, FS_ADJUST),
      out: this.program(VS_FULL, FS_OUT),
      glow: this.program(VS_FULL, FS_GLOW),
      addglow: this.program(VS_FULL, FS_ADDGLOW),
    };
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
  }

  private program(vs: string, fs: string): Prog {
    const gl = this.gl;
    const compile = (type: number, src: string) => {
      const s = gl.createShader(type)!;
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(`Shader: ${gl.getShaderInfoLog(s)}`);
      return s;
    };
    const p = gl.createProgram()!;
    gl.attachShader(p, compile(gl.VERTEX_SHADER, vs));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs));
    gl.bindAttribLocation(p, 0, "aPos");
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(`Program: ${gl.getProgramInfoLog(p)}`);
    return { p, u: new Map() };
  }

  private use(name: keyof Compositor["progs"]): Prog {
    const pr = this.progs[name];
    this.gl.useProgram(pr.p);
    return pr;
  }

  private loc(pr: Prog, n: string): WebGLUniformLocation | null {
    if (!pr.u.has(n)) pr.u.set(n, this.gl.getUniformLocation(pr.p, n));
    return pr.u.get(n)!;
  }

  private newTexture(): WebGLTexture {
    const gl = this.gl;
    const t = gl.createTexture()!;
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    return t;
  }

  private target(i: number): Target {
    const gl = this.gl;
    let t = this.targets[i];
    if (t && t.w === this.width && t.h === this.height) return t;
    if (t) {
      gl.deleteFramebuffer(t.fb);
      gl.deleteTexture(t.tex);
    }
    const tex = this.newTexture();
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, this.width, this.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    const fb = gl.createFramebuffer()!;
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    t = { fb, tex, w: this.width, h: this.height };
    this.targets[i] = t;
    return t;
  }

  private bindTarget(t: Target | null) {
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, t ? t.fb : null);
    gl.viewport(0, 0, t ? t.w : this.canvas.width, t ? t.h : this.canvas.height);
  }

  private clear(t: Target) {
    this.bindTarget(t);
    this.gl.clearColor(0, 0, 0, 0);
    this.gl.clear(this.gl.COLOR_BUFFER_BIT);
  }

  private upload(key: string, src: LayerSource, version: string): WebGLTexture {
    const gl = this.gl;
    let e = this.texCache.get(key);
    if (!e) {
      e = { tex: this.newTexture(), version: "", used: this.frame };
      this.texCache.set(key, e);
    }
    e.used = this.frame;
    gl.bindTexture(gl.TEXTURE_2D, e.tex);
    if (e.version !== version) {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
      e.version = version;
    }
    return e.tex;
  }

  private setGrade(pr: Prog, d: LayerDraw, texW: number, texH: number, quadW = texW, quadH = texH) {
    const gl = this.gl;
    const fx = d.fx;
    const qw = Math.max(1, quadW);
    const qh = Math.max(1, quadH);
    gl.uniform4f(this.loc(pr, "uCrop"), d.crop[0], d.crop[1], d.crop[2], d.crop[3]);
    gl.uniform1f(this.loc(pr, "uPixelate"), fx.pixelate > 0.5 ? fx.pixelate / qw : 0);
    gl.uniform1f(this.loc(pr, "uGlitch"), fx.glitch);
    gl.uniform1f(this.loc(pr, "uRgbSplit"), fx.rgbSplit / qw);
    gl.uniform1f(this.loc(pr, "uZoomBlur"), fx.zoomBlur);
    gl.uniform2f(this.loc(pr, "uDirBlur"), (d.flipX ? -1 : 1) * d.dirBlur[0] / qw, (d.flipY ? -1 : 1) * d.dirBlur[1] / qh);
    gl.uniform1f(this.loc(pr, "uPosterize"), fx.posterize);
    gl.uniform1f(this.loc(pr, "uInvert"), fx.invert);
    gl.uniform1f(this.loc(pr, "uDuo"), d.duotone ? fx.duotone : 0);
    gl.uniform1f(this.loc(pr, "uScan"), fx.scanlines);
    gl.uniform1f(this.loc(pr, "uShadows"), fx.shadows);
    gl.uniform1f(this.loc(pr, "uHighlights"), fx.highlights);
    gl.uniform1f(this.loc(pr, "uLetterbox"), fx.letterbox);
    const dd = hexRgb(d.duotone?.dark ?? "#1b1035");
    const dl = hexRgb(d.duotone?.light ?? "#ffd166");
    gl.uniform3f(this.loc(pr, "uDuoDark"), dd[0], dd[1], dd[2]);
    gl.uniform3f(this.loc(pr, "uDuoLight"), dl[0], dl[1], dl[2]);
    const w = d.wheels;
    gl.uniform3f(this.loc(pr, "uWLift"), w?.lift[0] ?? 0, w?.lift[1] ?? 0, w?.lift[2] ?? 0);
    gl.uniform3f(this.loc(pr, "uWGamma"), w?.gamma[0] ?? 0, w?.gamma[1] ?? 0, w?.gamma[2] ?? 0);
    gl.uniform3f(this.loc(pr, "uWGain"), w?.gain[0] ?? 0, w?.gain[1] ?? 0, w?.gain[2] ?? 0);
    const g = d.grade;
    gl.uniform1f(this.loc(pr, "uExposure"), g.exposure);
    gl.uniform1f(this.loc(pr, "uContrast"), g.contrast);
    gl.uniform1f(this.loc(pr, "uSaturation"), g.saturation);
    gl.uniform1f(this.loc(pr, "uVibrance"), g.vibrance);
    gl.uniform1f(this.loc(pr, "uTemp"), g.temperature);
    gl.uniform1f(this.loc(pr, "uTint"), g.tint);
    gl.uniform1f(this.loc(pr, "uHue"), g.hue);
    gl.uniform1f(this.loc(pr, "uLift"), g.lift);
    gl.uniform1f(this.loc(pr, "uGamma"), g.gamma);
    gl.uniform1f(this.loc(pr, "uGain"), g.gain);
    gl.uniform1f(this.loc(pr, "uVignette"), g.vignette);
    gl.uniform1f(this.loc(pr, "uGrain"), g.grain);
    gl.uniform1f(this.loc(pr, "uSharpen"), g.sharpen);
    gl.uniform1f(this.loc(pr, "uSeed"), (this.frame % 997) * 0.137);
    gl.uniform2f(this.loc(pr, "uTexel"), 1 / Math.max(1, texW), 1 / Math.max(1, texH));
    const k = d.chroma;
    const kc = hexRgb(k?.color ?? "#00ff00");
    gl.uniform4f(this.loc(pr, "uKey"), kc[0], kc[1], kc[2], k ? 1 : 0);
    gl.uniform3f(this.loc(pr, "uKeyParams"), k?.similarity ?? 0.12, k?.smoothness ?? 0.08, k?.spill ?? 0.5);
    const m = d.mask;
    gl.uniform4f(this.loc(pr, "uMask"), m.x, m.y, m.w, m.h);
    gl.uniform3f(this.loc(pr, "uMaskParams"), m.shape === "rect" ? 1 : m.shape === "ellipse" ? 2 : 0, m.feather, m.invert ? 1 : 0);
    gl.uniform1f(this.loc(pr, "uOpacity"), d.opacity);
    this.setNodes(pr, d, qw / qh);
  }

  /** A float data texture (curves table or 3D LUT), uploaded once per key and dropped when unused for a while. */
  private dataTexture(key: string, make: (tex: WebGLTexture) => void, target: number): WebGLTexture {
    const gl = this.gl;
    let e = this.dataTex.get(key);
    if (!e) {
      const tex = gl.createTexture()!;
      // Work on a spare unit so the layer's own picture on unit 0 stays bound.
      gl.activeTexture(gl.TEXTURE9);
      gl.bindTexture(target, tex);
      gl.texParameteri(target, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(target, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(target, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(target, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      if (target === gl.TEXTURE_3D) gl.texParameteri(target, gl.TEXTURE_WRAP_R, gl.CLAMP_TO_EDGE);
      // Raw number tables: no alpha premultiply (3D uploads reject it outright).
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
      make(tex);
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
      e = { tex, used: this.frame };
      this.dataTex.set(key, e);
    }
    e.used = this.frame;
    return e.tex;
  }

  /** Colour page nodes for this layer: uniforms, the curves table and up to four LUTs. */
  private setNodes(pr: Prog, d: LayerDraw, aspect: number) {
    const gl = this.gl;
    // Samplers always point at their own units, so a 2D and a 3D sampler never share one.
    gl.uniform1i(this.loc(pr, "uCurves"), 4);
    gl.uniform1i(this.loc(pr, "uLut0"), 5);
    gl.uniform1i(this.loc(pr, "uLut1"), 6);
    gl.uniform1i(this.loc(pr, "uLut2"), 7);
    gl.uniform1i(this.loc(pr, "uLut3"), 8);
    gl.uniform1f(this.loc(pr, "uBypass"), this.bypass ? 1 : 0);
    gl.uniform1f(this.loc(pr, "uSplitX"), this.split >= 0 ? this.split * this.width : -1);
    const nodes: ColorNode[] = (d.nodes ?? []).slice(0, MAX_NODES);
    gl.uniform1i(this.loc(pr, "uNodeCount"), nodes.length);
    if (!nodes.length) return;
    const N = MAX_NODES;
    const u = { lift: new Float32Array(N * 4), gamma: new Float32Array(N * 4), gain: new Float32Array(N * 4), offset: new Float32Array(N * 4), prim: new Float32Array(N * 4), prim2: new Float32Array(N * 4), mode: new Float32Array(N * 4), qh: new Float32Array(N * 4), qs: new Float32Array(N * 4), ql: new Float32Array(N * 4), win: new Float32Array(N * 4), win2: new Float32Array(N * 4) };
    const BL = { normal: 0, add: 1, multiply: 2, screen: 3, overlay: 4, softLight: 5 } as const;
    const SH = { none: 0, rect: 1, ellipse: 2, gradient: 3 } as const;
    const slots: string[] = [];
    const sizes = [2, 2, 2, 2];
    nodes.forEach((n, i) => {
      const o = i * 4;
      u.lift.set(n.lift, o);
      u.gamma.set(n.gamma, o);
      u.gain.set(n.gain, o);
      u.offset.set([n.offset[0], n.offset[1], n.offset[2], n.mix], o);
      u.prim.set([n.contrast, n.pivot, n.sat, (n.hue * Math.PI) / 180], o);
      let slot = -1;
      let lutMix = 0;
      if (n.lut && n.lut.mix > 0) {
        const e = lutFor(n.lut.id);
        if (e) {
          const key = `lut:${n.lut.id}:${e.ver}`;
          slot = slots.indexOf(key);
          if (slot < 0 && slots.length < 4) {
            slot = slots.length;
            slots.push(key);
            sizes[slot] = e.lut.size;
            const tex = this.dataTexture(key, () => gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGB16F, e.lut.size, e.lut.size, e.lut.size, 0, gl.RGB, gl.FLOAT, e.lut.data), gl.TEXTURE_3D);
            gl.activeTexture(gl.TEXTURE5 + slot);
            gl.bindTexture(gl.TEXTURE_3D, tex);
          }
          if (slot >= 0) lutMix = n.lut.mix;
        }
      }
      u.prim2.set([n.temp, n.tint, slot, lutMix], o);
      const show = this.showKey && this.showKey.clipId === d.clipId && this.showKey.nodeId === n.id ? 1 : 0;
      u.mode.set([n.kind === "layer" ? 1 : 0, BL[n.blend] ?? 0, curveMask(n.curves), show], o);
      const q = n.qual;
      if (q?.on) {
        u.qh.set([1, q.h, q.hw, q.hs], o);
        u.qs.set([q.s0, q.s1, q.ss, q.invert ? 1 : 0], o);
        u.ql.set([q.l0, q.l1, q.ls, 0], o);
      }
      const w = n.win;
      if (w && w.shape !== "none") {
        u.win.set([w.x, w.y, w.w, w.h], o);
        u.win2.set([SH[w.shape], (w.rot * Math.PI) / 180, w.soft, w.invert ? 1 : 0], o);
      }
    });
    gl.uniform4fv(this.loc(pr, "uNLift"), u.lift);
    gl.uniform4fv(this.loc(pr, "uNGamma"), u.gamma);
    gl.uniform4fv(this.loc(pr, "uNGain"), u.gain);
    gl.uniform4fv(this.loc(pr, "uNOffset"), u.offset);
    gl.uniform4fv(this.loc(pr, "uNPrim"), u.prim);
    gl.uniform4fv(this.loc(pr, "uNPrim2"), u.prim2);
    gl.uniform4fv(this.loc(pr, "uNMode"), u.mode);
    gl.uniform4fv(this.loc(pr, "uNQH"), u.qh);
    gl.uniform4fv(this.loc(pr, "uNQS"), u.qs);
    gl.uniform4fv(this.loc(pr, "uNQL"), u.ql);
    gl.uniform4fv(this.loc(pr, "uNWin"), u.win);
    gl.uniform4fv(this.loc(pr, "uNWin2"), u.win2);
    gl.uniform4f(this.loc(pr, "uLutSize"), sizes[0]!, sizes[1]!, sizes[2]!, sizes[3]!);
    gl.uniform1f(this.loc(pr, "uNodeAspect"), aspect);
    const baked = bakeCurves(nodes);
    if (baked) {
      const tex = this.dataTexture(`cv:${baked.key}`, () => gl.texImage2D(gl.TEXTURE_2D, 0, gl.R16F, CURVE_RES, baked.rows, 0, gl.RED, gl.FLOAT, baked.data), gl.TEXTURE_2D);
      gl.activeTexture(gl.TEXTURE4);
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.uniform1f(this.loc(pr, "uCurveRows"), baked.rows);
    }
    gl.activeTexture(gl.TEXTURE0);
  }

  private fullQuad() {
    this.gl.bindVertexArray(this.vao);
    this.gl.drawArrays(this.gl.TRIANGLE_STRIP, 0, 4);
  }

  /** Render a frame at width × height into the canvas (which may be larger, for letterboxed preview). */
  render(layers: LayerInput[], width: number, height: number, background: string, view?: { x: number; y: number; w: number; h: number }): void {
    const gl = this.gl;
    this.frame++;
    this.width = Math.max(2, Math.round(width));
    this.height = Math.max(2, Math.round(height));
    gl.disable(gl.BLEND);
    let acc = this.target(0);
    let acc2 = this.target(1);
    const lay = this.target(2);
    const tmp = this.target(3);
    this.clear(acc);

    for (const L of layers) {
      let d = L.draw;
      if (d.opacity <= 0.001) continue;
      if (d.kind === "adjust") {
        const pr = this.use("adjust");
        this.bindTarget(acc2);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, acc.tex);
        gl.uniform1i(this.loc(pr, "uTex"), 0);
        this.setGrade(pr, d, this.width, this.height);
        this.fullQuad();
        [acc, acc2] = [acc2, acc];
        if (d.grade.blur > 0.5) acc = this.blur(acc, acc2, d.grade.blur);
        if (d.fx.glow > 0.01) {
          const g = this.glow(acc, acc2, d.fx.glow * d.opacity, d.fx.glowThreshold, d.fx.glowRadius);
          if (g !== acc) [acc, acc2] = [g, acc];
        }
        continue;
      }
      if (!L.source || L.sw < 1 || L.sh < 1) continue;
      // 1) Layer pass with transform and grade.
      this.clear(lay);
      const pr = this.use("layer");
      gl.activeTexture(gl.TEXTURE0);
      this.upload(d.clipId, L.source, L.version);
      gl.uniform1i(this.loc(pr, "uTex"), 0);
      let qw: number;
      let qh: number;
      // Crop trims the source before it is fitted.
      const sw = L.sw * Math.max(0.02, 1 - d.crop[0] - d.crop[2]);
      const sh = L.sh * Math.max(0.02, 1 - d.crop[1] - d.crop[3]);
      if (L.native || d.fit === "none") {
        qw = sw;
        qh = sh;
      } else {
        const kx = this.width / sw;
        const ky = this.height / sh;
        const k = d.fit === "cover" ? Math.max(kx, ky) : Math.min(kx, ky);
        qw = d.fit === "fill" ? this.width : sw * k;
        qh = d.fit === "fill" ? this.height : sh * k;
      }
      gl.uniform2f(this.loc(pr, "uRes"), this.width, this.height);
      gl.uniform2f(this.loc(pr, "uSize"), qw * d.scaleX, qh * d.scaleY);
      // Anchor sits at the frame centre plus the offset, so x=y=0 centres the layer.
      gl.uniform2f(this.loc(pr, "uCenter"), this.width / 2 + d.x + (d.anchorX - 0.5) * qw * d.scaleX, this.height / 2 + d.y + (d.anchorY - 0.5) * qh * d.scaleY);
      gl.uniform2f(this.loc(pr, "uAnchor"), d.anchorX, d.anchorY);
      gl.uniform1f(this.loc(pr, "uRot"), (d.rotation * Math.PI) / 180);
      gl.uniform2f(this.loc(pr, "uFlip"), d.flipX ? 1 : 0, d.flipY ? 1 : 0);
      if (d.pin) {
        const P = (q: [number, number]) => [q[0] * this.width + d.x, q[1] * this.height + d.y] as [number, number];
        gl.uniform1f(this.loc(pr, "uPinOn"), 1);
        gl.uniformMatrix3fv(this.loc(pr, "uHomo"), false, squareToQuad(P(d.pin.tl), P(d.pin.tr), P(d.pin.br), P(d.pin.bl)));
      } else gl.uniform1f(this.loc(pr, "uPinOn"), 0);
      if (d.win) {
        // Frame-space window → this layer's own UV space (rotation ignored).
        const sw = qw * d.scaleX,
          sh = qh * d.scaleY;
        const left = this.width / 2 + d.x + (d.anchorX - 0.5) * sw - d.anchorX * sw,
          top = this.height / 2 + d.y + (d.anchorY - 0.5) * sh - d.anchorY * sh;
        const [x0, y0, x1, y1] = d.win;
        d = { ...d, mask: { shape: "rect", x: (((x0 + x1) / 2) * this.width - left) / sw, y: (((y0 + y1) / 2) * this.height - top) / sh, w: Math.max(0, ((x1 - x0) * this.width) / sw), h: Math.max(0, ((y1 - y0) * this.height) / sh), feather: 0.0005, invert: false } };
      }
      this.setGrade(pr, d, L.sw, L.sh, qw * Math.abs(d.scaleX), qh * Math.abs(d.scaleY));
      this.bindTarget(lay);
      this.fullQuad();
      let layerTex = lay;
      if (d.grade.blur > 0.5) layerTex = this.blur(lay, tmp, d.grade.blur);
      if (d.fx.glow > 0.01) layerTex = this.glow(layerTex, layerTex === lay ? tmp : lay, d.fx.glow, d.fx.glowThreshold, d.fx.glowRadius);
      // 2) Blend onto the frame.
      const pb = this.use("blend");
      this.bindTarget(acc2);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, acc.tex);
      gl.uniform1i(this.loc(pb, "uBase"), 0);
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_2D, layerTex.tex);
      gl.uniform1i(this.loc(pb, "uLayer"), 1);
      gl.uniform1i(this.loc(pb, "uMode"), BLEND_ID[d.blend]);
      this.fullQuad();
      gl.activeTexture(gl.TEXTURE0);
      [acc, acc2] = [acc2, acc];
    }

    // 3) Output with background.
    const po = this.use("out");
    this.bindTarget(null);
    gl.clearColor(0.03, 0.035, 0.05, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);
    if (view) gl.viewport(view.x, this.canvas.height - view.y - view.h, view.w, view.h);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, acc.tex);
    gl.uniform1i(this.loc(po, "uTex"), 0);
    const bg = hexRgb(background);
    gl.uniform4f(this.loc(po, "uBg"), bg[0], bg[1], bg[2], 1);
    this.fullQuad();
    this.evict();
  }

  /** Bloom: bright pass → blur → add back. Writes into `out` and returns it. */
  private glow(src: Target, out: Target, k: number, thr: number, radius: number): Target {
    const gl = this.gl;
    const a = this.target(4);
    const b = this.target(5);
    const pg = this.use("glow");
    this.bindTarget(a);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, src.tex);
    gl.uniform1i(this.loc(pg, "uTex"), 0);
    gl.uniform1f(this.loc(pg, "uThr"), thr);
    this.fullQuad();
    const blurred = this.blur(a, b, Math.max(2, radius));
    const pa = this.use("addglow");
    this.bindTarget(out);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, src.tex);
    gl.uniform1i(this.loc(pa, "uBase"), 0);
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, blurred.tex);
    gl.uniform1i(this.loc(pa, "uGlow"), 1);
    gl.uniform1f(this.loc(pa, "uK"), k);
    this.fullQuad();
    gl.activeTexture(gl.TEXTURE0);
    return out;
  }

  /** Separable gaussian; large radii use repeated passes. Returns the target holding the result. */
  private blur(src: Target, spare: Target, radius: number): Target {
    const gl = this.gl;
    const pr = this.use("blur");
    let a = src;
    let b = spare;
    const passes = Math.min(6, Math.max(1, Math.ceil(radius / 12)));
    const spread = radius / passes / 3;
    for (let i = 0; i < passes; i++) {
      for (const dir of [
        [spread / this.width, 0],
        [0, spread / this.height],
      ] as const) {
        this.bindTarget(b);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, a.tex);
        gl.uniform1i(this.loc(pr, "uTex"), 0);
        gl.uniform2f(this.loc(pr, "uDir"), dir[0], dir[1]);
        this.fullQuad();
        [a, b] = [b, a];
      }
    }
    return a;
  }

  private evict() {
    for (const [k, e] of this.texCache) {
      if (this.frame - e.used > 90) {
        this.gl.deleteTexture(e.tex);
        this.texCache.delete(k);
      }
    }
    for (const [k, e] of this.dataTex) {
      if (this.frame - e.used > 600) {
        this.gl.deleteTexture(e.tex);
        this.dataTex.delete(k);
      }
    }
  }

  dispose(): void {
    const gl = this.gl;
    for (const e of this.texCache.values()) gl.deleteTexture(e.tex);
    for (const t of this.targets) {
      gl.deleteFramebuffer(t.fb);
      gl.deleteTexture(t.tex);
    }
    for (const e of this.dataTex.values()) gl.deleteTexture(e.tex);
    this.dataTex.clear();
    this.texCache.clear();
    this.targets = [];
  }
}

export function hexRgb(hex: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return [0, 0, 0];
  const n = parseInt(m[1]!, 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
}

/** Homography taking the unit square (0,0)(1,0)(1,1)(0,1) to the quad p0..p3 (column-major mat3 for WebGL). */
export function squareToQuad(p0: [number, number], p1: [number, number], p2: [number, number], p3: [number, number]): Float32Array {
  const [x0, y0] = p0,
    [x1, y1] = p1,
    [x2, y2] = p2,
    [x3, y3] = p3;
  const sx = x0 - x1 + x2 - x3,
    sy = y0 - y1 + y2 - y3;
  let a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number;
  if (Math.abs(sx) < 1e-9 && Math.abs(sy) < 1e-9) {
    a = x1 - x0;
    b = x2 - x1;
    c = x0;
    d = y1 - y0;
    e = y2 - y1;
    f = y0;
    g = 0;
    h = 0;
  } else {
    const dx1 = x1 - x2,
      dx2 = x3 - x2,
      dy1 = y1 - y2,
      dy2 = y3 - y2;
    const den = dx1 * dy2 - dx2 * dy1;
    g = (sx * dy2 - dx2 * sy) / den;
    h = (dx1 * sy - sx * dy1) / den;
    a = x1 - x0 + g * x1;
    b = x3 - x0 + h * x3;
    c = x0;
    d = y1 - y0 + g * y1;
    e = y3 - y0 + h * y3;
    f = y0;
  }
  // [X,Y,W] = M * [u,v,1]; columns: (a,d,g), (b,e,h), (c,f,1)
  return new Float32Array([a, d, g, b, e, h, c, f, 1]);
}
