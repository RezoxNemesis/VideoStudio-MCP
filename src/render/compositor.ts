/**
 * GPU compositor (WebGL2). Each layer is drawn with its transform and grade into a layer buffer, optionally
 * blurred, then blended onto the frame with its blend mode. Adjustment clips grade the frame built so far.
 * Everything is premultiplied alpha.
 */
import type { LayerDraw } from "../core/evaluate";
import type { BlendMode } from "../core/model";

const VS_LAYER = `#version 300 es
in vec2 aPos;
uniform vec2 uRes;      // frame size in px
uniform vec2 uSize;     // quad size in px
uniform vec2 uCenter;   // anchor position in px
uniform vec2 uAnchor;   // 0..1 within the quad
uniform float uRot;     // radians
uniform vec2 uFlip;
out vec2 vUv;
void main() {
  vec2 p = (aPos - uAnchor) * uSize;
  float c = cos(uRot), s = sin(uRot);
  p = vec2(p.x * c - p.y * s, p.x * s + p.y * c) + uCenter;
  vec2 clip = vec2(p.x / uRes.x * 2.0 - 1.0, 1.0 - p.y / uRes.y * 2.0);
  gl_Position = vec4(clip, 0.0, 1.0);
  vUv = mix(aPos, 1.0 - aPos, uFlip);
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

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + uSeed) * 43758.5453); }
vec3 unpm(vec4 c) { return c.a > 1e-5 ? c.rgb / c.a : vec3(0.0); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec2 cbcr(vec3 c) { return vec2(-0.1687 * c.r - 0.3313 * c.g + 0.5 * c.b, 0.5 * c.r - 0.4187 * c.g - 0.0813 * c.b); }

vec4 grade(sampler2D tex, vec2 uv) {
  vec4 src = texture(tex, uv);
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
  c *= exp2(uExposure);
  c *= vec3(1.0 + uTemp * 0.12, 1.0 + uTint * 0.08, 1.0 - uTemp * 0.12);
  c = c * uGain + uLift * (1.0 - c);
  c = pow(max(c, 0.0), vec3(1.0 / max(uGamma, 0.05)));
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
  if (uVignette > 0.0) {
    float v = smoothstep(0.85, 0.2, length((uv - 0.5) * vec2(1.0, 1.0)) * (0.6 + uVignette));
    c *= mix(1.0, v, uVignette);
  }
  if (uGrain > 0.0) c += (hash(uv * 1000.0) - 0.5) * uGrain * 0.25;
  c = clamp(c, 0.0, 1.0);
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
  private progs: Record<"layer" | "blur" | "blend" | "adjust" | "out", Prog>;
  private targets: Target[] = [];
  private texCache = new Map<string, { tex: WebGLTexture; version: string; used: number }>();
  private frame = 0;
  width = 0;
  height = 0;

  constructor(readonly canvas: HTMLCanvasElement | OffscreenCanvas) {
    const gl = canvas.getContext("webgl2", { premultipliedAlpha: true, alpha: false, antialias: false, preserveDrawingBuffer: true }) as WebGL2RenderingContext | null;
    if (!gl) throw new Error("This device does not support WebGL2.");
    this.gl = gl;
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

  private setGrade(pr: Prog, d: LayerDraw, texW: number, texH: number) {
    const gl = this.gl;
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
      const d = L.draw;
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
      if (L.native || d.fit === "none") {
        qw = L.sw;
        qh = L.sh;
      } else {
        const kx = this.width / L.sw;
        const ky = this.height / L.sh;
        const k = d.fit === "cover" ? Math.max(kx, ky) : Math.min(kx, ky);
        qw = d.fit === "fill" ? this.width : L.sw * k;
        qh = d.fit === "fill" ? this.height : L.sh * k;
      }
      gl.uniform2f(this.loc(pr, "uRes"), this.width, this.height);
      gl.uniform2f(this.loc(pr, "uSize"), qw * d.scaleX, qh * d.scaleY);
      // Anchor sits at the frame centre plus the offset, so x=y=0 centres the layer.
      gl.uniform2f(this.loc(pr, "uCenter"), this.width / 2 + d.x + (d.anchorX - 0.5) * qw * d.scaleX, this.height / 2 + d.y + (d.anchorY - 0.5) * qh * d.scaleY);
      gl.uniform2f(this.loc(pr, "uAnchor"), d.anchorX, d.anchorY);
      gl.uniform1f(this.loc(pr, "uRot"), (d.rotation * Math.PI) / 180);
      gl.uniform2f(this.loc(pr, "uFlip"), d.flipX ? 1 : 0, d.flipY ? 1 : 0);
      this.setGrade(pr, d, L.sw, L.sh);
      this.bindTarget(lay);
      this.fullQuad();
      let layerTex = lay;
      if (d.grade.blur > 0.5) layerTex = this.blur(lay, tmp, d.grade.blur);
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
  }

  dispose(): void {
    const gl = this.gl;
    for (const e of this.texCache.values()) gl.deleteTexture(e.tex);
    for (const t of this.targets) {
      gl.deleteFramebuffer(t.fb);
      gl.deleteTexture(t.tex);
    }
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
