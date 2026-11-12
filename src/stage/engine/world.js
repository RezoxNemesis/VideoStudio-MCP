// World kit: sky, terrain, water, trees, grass, rocks, buildings and props. Everything is generated — no asset files.
import { THREE, mulberry, clamp, lerp, smooth, fbm, ridged, vnoise, pfbm, makeTex, GLSL_NOISE, V3, noiseTex } from './core.js';
import { patch, GU } from './mat.js';
import * as BGU from 'three/addons/utils/BufferGeometryUtils.js';
const C = (r, g, b) => new THREE.Color().setRGB(r, g, b, THREE.SRGBColorSpace);

/* ================= textures ================= */
const TC = {};
export function tex(name) {
  if (TC[name]) return TC[name];
  const S = 512; let t;
  const mixc = (a, b, k) => [lerp(a[0], b[0], k), lerp(a[1], b[1], k), lerp(a[2], b[2], k)];
  switch (name) {
    case 'grass': t = makeTex(S, (u, v) => pfbm(u, v, 8, 5, 1) * 0.6 + pfbm(u, v * 1, 64, 2, 2) * 0.4, (u, v, h) => { const k = pfbm(u, v, 4, 3, 9); return mixc(mixc([0.16, 0.24, 0.07], [0.30, 0.36, 0.11], h), [0.38, 0.34, 0.14], smooth(0.55, 0.8, k) * 0.6); }, { bump: 3 }); break;
    case 'dirt': t = makeTex(S, (u, v) => pfbm(u, v, 6, 6, 3) * 0.7 + Math.pow(pfbm(u, v, 40, 2, 4), 3) * 0.6, (u, v, h) => mixc([0.20, 0.14, 0.09], [0.42, 0.32, 0.22], h), { bump: 3 }); break;
    case 'rock': t = makeTex(S, (u, v) => { const w = pfbm(u, v, 4, 4, 5); return pfbm(u + w * 0.25, v * 3 + w * 0.6, 6, 5, 6) * 0.7 + Math.abs(pfbm(u, v, 12, 3, 7) - 0.5) * 0.8; }, (u, v, h) => mixc([0.16, 0.15, 0.15], [0.50, 0.47, 0.43], h), { bump: 5 }); break;
    case 'snow': t = makeTex(S, (u, v) => pfbm(u, v, 5, 5, 11), (u, v, h) => mixc([0.78, 0.82, 0.90], [0.96, 0.97, 1.0], h), { bump: 1.5 }); break;
    case 'ash': t = makeTex(S, (u, v) => pfbm(u, v, 10, 5, 13), (u, v, h) => mixc([0.03, 0.028, 0.026], [0.16, 0.15, 0.14], h * h), { bump: 3 }); break;
    case 'wood': t = makeTex(S, (u, v) => { const pl = Math.abs(((u * 6) % 1) - 0.5) * 2; const g = pfbm(u * 0.5, v * 1, 4, 3, 21) * 0.3 + pfbm(u * 6, v, 24, 3, 22) * 0.7; return g * 0.7 + (pl > 0.93 ? -0.5 : 0.25); }, (u, v, h) => { const pi = Math.floor(u * 6); const tone = 0.8 + 0.4 * ((pi * 37 % 7) / 7); return mixc([0.10, 0.065, 0.04], [0.36, 0.25, 0.15], clamp(h)).map(x => x * tone); }, { bump: 4 }); break;
    case 'plaster': t = makeTex(S, (u, v) => pfbm(u, v, 5, 5, 31) * 0.8 + pfbm(u, v, 48, 2, 32) * 0.2, (u, v, h) => { const st = pfbm(u * 2, v * 0.3, 4, 4, 33); return mixc(mixc([0.50, 0.46, 0.38], [0.74, 0.70, 0.60], h), [0.30, 0.27, 0.22], smooth(0.5, 0.8, st) * 0.5); }, { bump: 2 }); break;
    case 'thatch': t = makeTex(S, (u, v) => pfbm(u, v * 0.06, 90, 2, 41) * 0.65 + pfbm(u, v, 5, 3, 42) * 0.35 + (((v * 7) % 1) < 0.06 ? -0.45 : 0), (u, v, h) => mixc([0.16, 0.13, 0.08], [0.52, 0.45, 0.29], clamp(h)), { bump: 5 }); break;
    case 'stone': t = makeTex(S, (u, v) => { const row = Math.floor(v * 8); const uu = u * 4 + (row % 2) * 0.5; const bx = Math.abs((uu % 1) - 0.5) * 2, by = Math.abs(((v * 8) % 1) - 0.5) * 2; const mort = (bx > 0.94 || by > 0.88) ? 0 : 1; return mort * (0.55 + 0.45 * pfbm(u, v, 16, 4, 51)); }, (u, v, h) => { const row = Math.floor(v * 8), cl = Math.floor(u * 4 + (row % 2) * 0.5); const tone = 0.75 + 0.5 * (((cl * 13 + row * 7) % 9) / 9); return mixc([0.10, 0.10, 0.10], [0.44, 0.42, 0.39], clamp(h)).map(x => x * tone); }, { bump: 6 }); break;
    case 'tiles': t = makeTex(S, (u, v) => { const row = Math.floor(v * 10); const uu = u * 12 + (row % 2) * 0.5; const cx = (uu % 1) - 0.5, cy = ((v * 10) % 1); return clamp(1 - cy * 0.8 - Math.abs(cx) * 0.9) + pfbm(u, v, 30, 2, 61) * 0.15; }, (u, v, h) => mixc([0.05, 0.06, 0.07], [0.22, 0.25, 0.28], clamp(h)), { bump: 6 }); break;
    case 'canvas': t = makeTex(S, (u, v) => (Math.sin(u * 400) * Math.sin(v * 400)) * 0.25 + pfbm(u, v, 6, 4, 71) * 0.75, (u, v, h) => mixc([0.45, 0.40, 0.32], [0.80, 0.74, 0.62], h), { bump: 2 }); break;
  }
  return TC[name] = t;
}
function canvasTex(size, draw) { const c = document.createElement('canvas'); c.width = c.height = size; const g = c.getContext('2d'); draw(g, size); const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.minFilter = THREE.LinearMipmapNearestFilter; return t; }
const LT = {};
export function leafTex(kind) {
  if (LT[kind]) return LT[kind];
  return LT[kind] = canvasTex(256, (g, S) => { const r = mulberry(kind.length * 99 + 5); g.clearRect(0, 0, S, S);
    const pal = { leaf: [[40, 80, 28], [62, 110, 36], [88, 128, 44]], autumn: [[150, 70, 20], [190, 110, 30], [120, 40, 20]], blossom: [[240, 190, 205], [250, 215, 225], [225, 160, 185]], pine: [[18, 48, 30], [28, 66, 38], [40, 80, 46]], dead: [[40, 30, 24], [60, 44, 32], [30, 22, 18]], bush: [[50, 84, 34], [70, 100, 40], [36, 66, 26]] }[kind];
    const n = kind === 'pine' ? 520 : kind === 'dead' ? 40 : 420;
    for (let i = 0; i < n; i++) { const a = r() * 6.283, rad = Math.sqrt(r()) * S * 0.46, x = S / 2 + Math.cos(a) * rad, y = S / 2 + Math.sin(a) * rad; const c = pal[Math.floor(r() * 3)], k = 0.7 + r() * 0.6; g.fillStyle = `rgb(${c[0] * k | 0},${c[1] * k | 0},${c[2] * k | 0})`;
      g.save(); g.translate(x, y); g.rotate(r() * 6.283); g.beginPath(); if (kind === 'pine') { g.strokeStyle = g.fillStyle; g.lineWidth = 1.6; g.moveTo(0, 0); g.lineTo(S * 0.07, 0); g.stroke(); } else if (kind === 'dead') { g.strokeStyle = g.fillStyle; g.lineWidth = 2.5; g.moveTo(0, 0); g.lineTo(S * 0.25 * r(), S * 0.06 * (r() - 0.5)); g.stroke(); } else { g.ellipse(0, 0, S * (kind === 'blossom' ? 0.016 : 0.030), S * (kind === 'blossom' ? 0.016 : 0.014), 0, 0, 6.283); g.fill(); } g.restore(); } });
}

/* ================= sky ================= */
export class Sky {
  constructor(o = {}) {
    this.u = { uZen: { value: C(...(o.zenith || [0.10, 0.22, 0.48])) }, uHor: { value: C(...(o.horizon || [0.62, 0.72, 0.85])) }, uGround: { value: C(...(o.ground || [0.25, 0.26, 0.28])) }, uSunDir: { value: V3(...(o.sunDir || [0.3, 0.5, 0.6])).normalize() }, uSunCol: { value: new THREE.Color(...(o.sunColor || [14, 11, 8])) }, uSunSize: { value: o.sunSize ?? 0.9995 }, uGlow: { value: o.glow ?? 0.25 },
      uStars: { value: o.stars ?? 0 }, uCloud: { value: o.cloud ?? 0.4 }, uCloudCol: { value: C(...(o.cloudColor || [1, 1, 1])) }, uCloudDark: { value: C(...(o.cloudDark || [0.45, 0.5, 0.6])) }, uTime: GU.uTime, uCloudScale: { value: o.cloudScale ?? 1 }, uMoon: { value: o.moon ?? 0 }, uHaze: { value: o.haze ?? 0.5 }, uCloudSpeed: { value: o.cloudSpeed ?? 0.006 }, tNoise: { value: noiseTex() } };
    const m = new THREE.ShaderMaterial({ uniforms: this.u, side: THREE.BackSide, depthWrite: false, depthTest: false, fog: false,
      vertexShader: `varying vec3 vD; void main(){ vD = position; vec4 p = modelViewMatrix*vec4(position, 0.); gl_Position = projectionMatrix*vec4(p.xyz, 1.); gl_Position.z = gl_Position.w; }`,
      fragmentShader: /* glsl */`varying vec3 vD; uniform vec3 uZen, uHor, uGround, uSunDir, uSunCol, uCloudCol, uCloudDark; uniform float uSunSize, uGlow, uStars, uCloud, uTime, uCloudScale, uMoon, uHaze, uCloudSpeed; uniform sampler2D tNoise;
        ${GLSL_NOISE}
        void main(){ vec3 d = normalize(vD); float h = d.y; vec3 c = mix(uHor, uZen, pow(clamp(h, 0., 1.), mix(0.35, 0.8, uHaze)));
          c = mix(c, uGround, smoothstep(0.0, -0.25, h));
          float sd = dot(d, uSunDir); c += uSunCol*0.045*uGlow*pow(max(sd, 0.), 6.) + uSunCol*0.25*uGlow*pow(max(sd, 0.), 60.);
          if(uStars > 0. && h > 0.){ vec3 sp = d*220.; vec3 cell = floor(sp); float r = hash13(cell); float tw = 0.6 + 0.4*sin(uTime*2. + r*60.); float star = smoothstep(0.9965, 1.0, r)*smoothstep(0.55, 0.0, length(fract(sp)-0.5))*tw; c += vec3(1.0, 0.95, 0.9)*star*uStars*6.*smoothstep(0.0, 0.25, h);
            float mw = texture2D(tNoise, d.xz*0.35 + 0.2).r; c += vec3(0.5, 0.6, 0.9)*uStars*0.035*smoothstep(0.45, 0.8, mw)*smoothstep(0.0, 0.3, h); }
          if(uCloud > 0. && h > -0.02){ vec2 uv = d.xz/(h + 0.18)*uCloudScale; uv += uTime*uCloudSpeed*vec2(1., 0.3); float n = texture2D(tNoise, uv*0.045).r*0.62 + texture2D(tNoise, uv*0.13 + 0.3).g*0.38; float n2 = texture2D(tNoise, uv*0.31 + 0.6).b; float cov = smoothstep(1.0 - uCloud, 1.0 - uCloud + 0.35, n*0.75 + n2*0.25);
            float lit = smoothstep(0.2, 0.9, n2 + sd*0.25); vec3 cc = mix(uCloudDark, uCloudCol, lit) + uSunCol*0.05*pow(max(sd, 0.), 4.)*(1. - cov*0.6); c = mix(c, cc, cov*smoothstep(-0.02, 0.12, h)*0.93); }
          float disc = smoothstep(uSunSize, uSunSize + (1.-uSunSize)*0.3, sd); c += uSunCol*disc*(uMoon > 0. ? 0.55 + 0.45*texture2D(tNoise, d.xy*6.).g : 1.);
          gl_FragColor = vec4(c, 1.); }` });
    this.mesh = new THREE.Mesh(new THREE.SphereGeometry(1, 32, 16), m); this.mesh.frustumCulled = false; this.mesh.renderOrder = -1000;
  }
  /** build an environment map (for metal and wet reflections) from this sky */
  env(renderer) { const s = new THREE.Scene(); const m = this.mesh.clone(); m.scale.setScalar(50); s.add(m); const pm = new THREE.PMREMGenerator(renderer); const rt = pm.fromScene(s, 0.02, 0.1, 100); pm.dispose(); return rt.texture; }
}

/* ================= terrain ================= */
/** o: { size, seg, height(x,z), mask(x,z,h)->[dirt, snow, ash, wet], center:[x,z], snowLine, uvScale } */
export function makeTerrain(o) {
  const size = o.size, seg = o.seg, cx = o.center?.[0] ?? 0, cz = o.center?.[1] ?? 0;
  const g = new THREE.PlaneGeometry(size, size, seg, seg); g.rotateX(-Math.PI / 2); const p = g.attributes.position; const mk = new Float32Array(p.count * 4);
  for (let i = 0; i < p.count; i++) { const x = p.getX(i) + cx, z = p.getZ(i) + cz; const h = o.height(x, z); p.setXYZ(i, x, h, z); if (o.mask) { const m = o.mask(x, z, h); mk[i * 4] = m[0]; mk[i * 4 + 1] = m[1]; mk[i * 4 + 2] = m[2]; mk[i * 4 + 3] = m[3] || 0; } }
  g.setAttribute('mask', new THREE.BufferAttribute(mk, 4)); g.computeVertexNormals();
  const u = { tGrass: { value: tex(o.grass || 'grass').map }, tRock: { value: tex('rock').map }, tDirt: { value: tex('dirt').map }, tNoise: { value: noiseTex() }, uScale: { value: o.uvScale ?? 0.22 }, uSnowLine: { value: o.snowLine ?? 9999 }, uTint: { value: C(...(o.tint || [1, 1, 1])) }, uRockSlope: { value: o.rockSlope ?? 0.42 }, uSnowAll: { value: o.snowAll ?? 0 } };
  const m = new THREE.MeshLambertMaterial({ color: 0xffffff }); m.userData.u = u;
  patch(m, { attrs: ['mask'], uniforms: u, key: 'terrain', lean: true, vdecl: 'varying vec3 vWN;', vmain: 'vWN = normalize(mat3(modelMatrix)*objectNormal);',
    decl: 'uniform sampler2D tGrass, tRock, tDirt, tNoise; uniform float uScale, uSnowLine, uRockSlope, uSnowAll; uniform vec3 uTint; varying vec3 vWN;',
    color: /* glsl */`{ vec2 q = vWPos.xz; vec3 n = normalize(vWN); vec4 nA = texture2D(tNoise, q*0.0031); vec4 nB = texture2D(tNoise, q*0.023 + 0.37); float macro = nA.r, macro2 = nB.g;
        vec3 gr = texture2D(tGrass, q*uScale).rgb; gr *= 0.70 + 0.62*macro; gr = mix(gr, gr*vec3(1.25, 1.12, 0.7), smoothstep(0.5, 0.8, macro2)*0.5);
        vec3 di = texture2D(tDirt, q*uScale*1.3).rgb*(0.8 + 0.4*macro);
        vec3 rk = mix(texture2D(tRock, vec2(vWPos.x + vWPos.z, vWPos.y)*uScale*0.55).rgb, texture2D(tRock, q*uScale*0.6).rgb, smoothstep(0.45, 0.8, n.y))*(0.72 + 0.55*macro2);
        float slope = 1. - n.y; float rockw = smoothstep(uRockSlope - 0.12, uRockSlope + 0.10, slope + (macro2 - 0.5)*0.18);
        vec3 c = mix(gr, di, clamp(vMask.x + smoothstep(0.62, 0.78, macro2)*0.25, 0., 1.)); c = mix(c, rk, rockw);
        float sn = clamp(max(smoothstep(uSnowLine - 6., uSnowLine + 10., vWPos.y + (macro - 0.5)*18.), max(vMask.y, uSnowAll)) * smoothstep(0.80, 0.45, slope + (macro2 - 0.5)*0.2), 0., 1.);
        c = mix(c, vec3(0.80, 0.84, 0.92)*(0.86 + 0.28*nB.b), sn);
        c = mix(c, vec3(0.045, 0.04, 0.036)*(0.5 + 1.2*nB.a), vMask.z);
        c = mix(c, c*0.45, vMask.w);
        diffuseColor.rgb = c*uTint; }`,
    rough: `{ float sn = max(vMask.y, uSnowAll); roughnessFactor = mix(roughnessFactor, 0.6, sn*0.5); roughnessFactor = mix(roughnessFactor, 0.25, vMask.w); }`,
    normal: `{ float f = smoothstep(0.3, 0.02, pxSize(vWPos)); normal = bumpN(normal, -vViewPosition, dot(diffuseColor.rgb, vec3(0.9)) + texture2D(tNoise, vWPos.xz*0.31).a*0.25, 0.05*f); }` });
  const mesh = new THREE.Mesh(g, m); mesh.receiveShadow = true; mesh.castShadow = !!o.castShadow; mesh.name = 'terrain'; return mesh;
}

/** water surface: animated ripples, sky-coloured reflection */
export function makeWater(w, d, o = {}) {
  const u = { uDeep: { value: C(...(o.deep || [0.03, 0.09, 0.11])) }, uSky: { value: C(...(o.sky || [0.5, 0.62, 0.75])) }, uFlow: { value: new THREE.Vector2(...(o.flow || [0.4, 0.1])) } };
  const m = new THREE.MeshPhysicalMaterial({ color: 0xffffff, roughness: 0.06, metalness: 0, transparent: true, opacity: o.opacity ?? 0.9, envMapIntensity: 1.2 });
  patch(m, { attrs: [], uniforms: u, key: 'water', decl: 'uniform vec3 uDeep, uSky; uniform vec2 uFlow;',
    color: `{ vec3 V = normalize(vViewPosition); float fr = pow(1. - abs(dot(normalize(vNormal), V)), 3.); diffuseColor.rgb = mix(uDeep, uSky, clamp(0.08 + fr, 0., 1.)); }`,
    normal: `{ vec2 q = vWPos.xz; float hh = fbm3(vec3(q*1.3 + uFlow*uTime, uTime*0.35)) + fbm3(vec3(q*4.1 - uFlow*uTime*1.7, uTime*0.6))*0.4; float f = smoothstep(0.6, 0.02, pxSize(vWPos)); normal = bumpN(normal, -vViewPosition, hh, 0.07*f + 0.004); }` });
  const g = new THREE.PlaneGeometry(w, d, 1, 1); g.rotateX(-Math.PI / 2); const mesh = new THREE.Mesh(g, m); mesh.receiveShadow = true; return mesh;
}

/* ================= vegetation ================= */
function windPatch(m, strength = 1, key = 'wind') {
  return patch(m, { attrs: [], key: key + strength, vdecl: 'uniform float uTime;', vmain: `
      #ifdef USE_INSTANCING
        vec3 wip = instanceMatrix[3].xyz;
      #else
        vec3 wip = vec3(0.);
      #endif
      { float ph = wip.x*0.31 + wip.z*0.47; float sway = sin(uTime*1.3 + ph)*0.6 + sin(uTime*2.9 + ph*1.7)*0.4; float hh = max(position.y, 0.);
      transformed.x += sway*hh*hh*0.006*${strength.toFixed(2)}; transformed.z += cos(uTime*1.1 + ph)*hh*hh*0.004*${strength.toFixed(2)}; }` });
}
/** tree: bent trunk + leaf cards with outward-facing normals. kind: leaf|autumn|blossom|pine|dead|bush */
export function treeGeo(seed = 1, kind = 'leaf', o = {}) {
  const r = mulberry(seed * 7919 + 13); const H = o.height ?? (kind === 'pine' ? 11 : kind === 'bush' ? 1.2 : 7), R = o.radius ?? (kind === 'pine' ? 2.3 : kind === 'bush' ? 0.9 : 3);
  const tr = []; const segs = 7, bendX = (r() - 0.5) * 0.8, bendZ = (r() - 0.5) * 0.8, tH = kind === 'bush' ? 0.25 : H * (kind === 'pine' ? 0.95 : 0.62);
  if (kind !== 'bush') {
    const trunk = new THREE.CylinderGeometry(0.06 * H / 7, 0.22 * H / 7 * (kind === 'pine' ? 0.8 : 1), tH, 7, segs); trunk.translate(0, tH / 2, 0); const tp = trunk.attributes.position;
    for (let i = 0; i < tp.count; i++) { const y = tp.getY(i) / tH; tp.setX(i, tp.getX(i) + bendX * y * y * H * 0.12); tp.setZ(i, tp.getZ(i) + bendZ * y * y * H * 0.12); } trunk.computeVertexNormals(); tr.push(trunk);
    if (kind !== 'pine') for (let b = 0; b < 4; b++) { const a = r() * 6.283, len = R * (0.5 + r() * 0.5), y0 = tH * (0.55 + r() * 0.4); const br = new THREE.CylinderGeometry(0.02, 0.06 * H / 7, len, 5, 1); br.translate(0, len / 2, 0); br.rotateZ(-(0.7 + r() * 0.5)); br.rotateY(a); br.translate(bendX * H * 0.07, y0, bendZ * H * 0.07); tr.push(br); }
  }
  const cards = []; const n = o.cards ?? (kind === 'pine' ? 46 : kind === 'dead' ? 10 : kind === 'bush' ? 14 : 58); const cen = V3(bendX * H * 0.1, kind === 'pine' ? H * 0.55 : kind === 'bush' ? H * 0.5 : tH + R * 0.25, bendZ * H * 0.1);
  const lobes = []; const nl = kind === 'bush' ? 2 : 4; for (let i = 0; i < nl; i++) { const a = r() * 6.283, d = i === 0 ? 0 : R * (0.35 + 0.3 * r()); lobes.push({ c: V3(cen.x + Math.cos(a) * d, cen.y + (i === 0 ? R * 0.25 : (r() - 0.5) * R * 0.5), cen.z + Math.sin(a) * d), r: R * (i === 0 ? 0.78 : 0.5 + 0.2 * r()) }); }
  const pos = [], nor = [], uv = [], idx = [];
  for (let i = 0; i < n; i++) {
    let p, s;
    if (kind === 'pine') { const t = i / n; const y = H * (0.18 + 0.8 * t), rad = R * (1 - t) * (0.55 + 0.45 * r()), a = r() * 6.283; p = V3(Math.cos(a) * rad * 0.7, y, Math.sin(a) * rad * 0.7); s = R * (1.25 - t) * 0.75; }
    else { const lb = lobes[i % lobes.length]; const a = r() * 6.283, e = Math.acos(1 - 1.8 * r()), rad = lb.r * (0.5 + 0.5 * Math.cbrt(r())); p = V3(Math.sin(e) * Math.cos(a) * rad, Math.cos(e) * rad * 0.7, Math.sin(e) * Math.sin(a) * rad).add(lb.c); s = lb.r * (0.75 + r() * 0.5); }
    const nrm = p.clone().sub(cen).normalize(); if (kind === 'pine') { nrm.set(p.x, 0.5 * R, p.z).normalize(); }
    // card facing roughly outward, random roll
    const ax = V3(r() - 0.5, r() - 0.5, r() - 0.5).normalize(), tx = new THREE.Vector3().crossVectors(nrm, ax).normalize(), ty = new THREE.Vector3().crossVectors(nrm, tx).normalize(); if (kind === 'pine') { ty.set(0, -0.45, 0).addScaledVector(nrm, 0.9).normalize(); tx.crossVectors(ty, V3(0, 1, 0)).normalize(); }
    const b = pos.length / 3; for (const [a_, b_] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) { const q = p.clone().addScaledVector(tx, a_ * s * 0.5).addScaledVector(ty, b_ * s * 0.5); pos.push(q.x, q.y, q.z); const nn = q.clone().sub(cen).normalize().lerp(V3(0, 1, 0), 0.35).normalize(); nor.push(nn.x, nn.y, nn.z); uv.push(a_ * 0.5 + 0.5, b_ * 0.5 + 0.5); }
    idx.push(b, b + 1, b + 2, b, b + 2, b + 3);
  }
  const leaves = new THREE.BufferGeometry(); leaves.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); leaves.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3)); leaves.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); leaves.setIndex(idx);
  return { trunk: tr.length ? BGU.mergeGeometries(tr) : null, leaves };
}
const leafMats = {};
export function leafMat(kind) { if (leafMats[kind]) return leafMats[kind]; const m = new THREE.MeshLambertMaterial({ map: leafTex(kind), alphaTest: 0.4, side: THREE.DoubleSide }); return leafMats[kind] = windPatch(m, kind === 'pine' ? 0.5 : 1, 'leaf' + kind); }
let _trunkMat = null; export function trunkMat() { return _trunkMat ??= windPatch(new THREE.MeshLambertMaterial({ map: tex('wood').map, color: C(0.5, 0.42, 0.36) }), 1, 'trunk'); }
/** instanced forest. spots: [{x,y,z,s,rot,tint?}]. o.far = cheap solid canopies for distant trees */
export function forest(spots, kind = 'leaf', seed = 1, o = {}) {
  const grp = new THREE.Group(); const variants = o.variants ?? 3; const mtx = new THREE.Matrix4(), q = new THREE.Quaternion(), col = new THREE.Color(); const cell = o.cell ?? 90;
  const cells = new Map(); spots.forEach((s, i) => { const k = (o.far ? 0 : i % variants) + '|' + Math.floor(s.x / cell) + '|' + Math.floor(s.z / cell); if (!cells.has(k)) cells.set(k, []); cells.get(k).push(s); });
  const geos = {};
  for (const [k, mine] of cells) {
    const v = +k.split('|')[0];
    const add = (geo, mat, tinted) => { const im = new THREE.InstancedMesh(geo, mat, mine.length); mine.forEach((s, i) => { q.setFromAxisAngle(V3(0, 1, 0), s.rot ?? 0); mtx.compose(V3(s.x, s.y, s.z), q, V3(s.s ?? 1, (s.s ?? 1) * (s.sy ?? 1), s.s ?? 1)); im.setMatrixAt(i, mtx); if (tinted) { const kk = s.tint ?? 1; im.setColorAt(i, col.setRGB(kk, kk, kk)); } }); im.castShadow = o.shadow !== false; im.receiveShadow = !o.far; im.computeBoundingSphere(); grp.add(im); };
    if (o.far) { const fg = geos.far ??= farTreeGeo(kind, seed); add(fg, farTreeMat(kind), true); continue; }
    const tg = geos[v] ??= treeGeo(seed + v * 31, kind, o);
    if (tg.trunk) add(tg.trunk, kind === 'dead' ? new THREE.MeshLambertMaterial({ color: 0x0c0a09 }) : trunkMat(), false); add(tg.leaves, leafMat(kind), true);
  }
  return grp;
}
function farTreeGeo(kind, seed) { const r = mulberry(seed * 3 + 1); const parts = []; const paint = (g, c0, c1) => { const p = g.attributes.position, n = p.count, c = new Float32Array(n * 3); g.computeBoundingBox(); const y0 = g.boundingBox.min.y, y1 = g.boundingBox.max.y; for (let i = 0; i < n; i++) { const t = (p.getY(i) - y0) / (y1 - y0), k = 0.85 + 0.3 * r(); c[i * 3] = lerp(c0[0], c1[0], t) * k; c[i * 3 + 1] = lerp(c0[1], c1[1], t) * k; c[i * 3 + 2] = lerp(c0[2], c1[2], t) * k; } g.setAttribute('color', new THREE.BufferAttribute(c, 3)); return g; };
  if (kind === 'pine') { for (let i = 0; i < 3; i++) { const c = new THREE.ConeGeometry(2.3 - i * 0.55, 4.6, 7); c.translate(0, 3.4 + i * 2.6, 0); parts.push(paint(c.toNonIndexed(), [0.012, 0.035, 0.022], [0.05, 0.11, 0.06])); } const t = new THREE.CylinderGeometry(0.16, 0.22, 3, 5); t.translate(0, 1.5, 0); parts.push(paint(t.toNonIndexed(), [0.04, 0.03, 0.02], [0.04, 0.03, 0.02])); }
  else { for (let i = 0; i < 3; i++) { const b = new THREE.IcosahedronGeometry(i === 0 ? 2.6 : 1.7 + r() * 0.5, 1); const p = b.attributes.position; for (let k = 0; k < p.count; k++) { const v = V3(p.getX(k), p.getY(k), p.getZ(k)); v.multiplyScalar(0.8 + 0.4 * vnoise(v.x * 1.3 + seed, v.y * 1.3, v.z * 1.3)); p.setXYZ(k, v.x, v.y * 0.8, v.z); } const a = r() * 6.283, d = i === 0 ? 0 : 1.7; b.translate(Math.cos(a) * d, 5.2 + (i === 0 ? 0.6 : (r() - 0.5)), Math.sin(a) * d); b.computeVertexNormals(); const c0 = kind === 'dead' ? [0.01, 0.008, 0.007] : kind === 'autumn' ? [0.12, 0.04, 0.012] : [0.018, 0.05, 0.012], c1 = kind === 'dead' ? [0.03, 0.025, 0.02] : kind === 'autumn' ? [0.45, 0.2, 0.04] : [0.10, 0.19, 0.05]; parts.push(paint(b.toNonIndexed(), c0, c1)); }
    const t = new THREE.CylinderGeometry(0.2, 0.3, 4.6, 5); t.translate(0, 2.3, 0); parts.push(paint(t.toNonIndexed(), [0.035, 0.026, 0.018], [0.035, 0.026, 0.018])); }
  const g = BGU.mergeGeometries(parts); g.computeVertexNormals(); return g; }
let _farMat = null; function farTreeMat() { return _farMat ??= new THREE.MeshLambertMaterial({ vertexColors: true }); }
/** grass tufts as crossed blades, instanced. spots: [{x,y,z,s}] */
export function grassField(spots, o = {}) {
  const blades = []; const r = mulberry(o.seed ?? 5);
  for (let i = 0; i < 9; i++) { const a = r() * 6.283, w = 0.035, hgt = 0.35 + r() * 0.35, lean = 0.12 + r() * 0.2; const g = new THREE.PlaneGeometry(w, hgt, 1, 3); g.translate(0, hgt / 2, 0); const p = g.attributes.position; for (let k = 0; k < p.count; k++) { const y = p.getY(k) / hgt; p.setX(k, p.getX(k) * (1 - y * 0.9)); p.setZ(k, y * y * lean); } g.rotateY(a); g.translate((r() - 0.5) * 0.3, 0, (r() - 0.5) * 0.3); blades.push(g); }
  const geo = BGU.mergeGeometries(blades); const n = geo.attributes.normal; for (let i = 0; i < n.count; i++) n.setXYZ(i, 0, 1, 0);
  const m = windPatch(new THREE.MeshLambertMaterial({ color: C(...(o.color || [0.36, 0.46, 0.16])), side: THREE.DoubleSide }), 14, 'grass');
  const im = new THREE.InstancedMesh(geo, m, spots.length); const mtx = new THREE.Matrix4(), q = new THREE.Quaternion(), col = new THREE.Color();
  spots.forEach((s, i) => { q.setFromAxisAngle(V3(0, 1, 0), r() * 6.283); mtx.compose(V3(s.x, s.y, s.z), q, V3(s.s ?? 1, (s.s ?? 1) * (0.7 + r() * 0.7), s.s ?? 1)); im.setMatrixAt(i, mtx); const k = 0.7 + r() * 0.6; im.setColorAt(i, col.setRGB(k, k * (0.9 + r() * 0.2), k * 0.8)); });
  im.receiveShadow = true; im.computeBoundingSphere(); return im;
}
/** boulders */
export function rocks(spots, seed = 3, o = {}) {
  const r = mulberry(seed); const g = new THREE.IcosahedronGeometry(1, 3); const p = g.attributes.position;
  for (let i = 0; i < p.count; i++) { const v = V3(p.getX(i), p.getY(i), p.getZ(i)); const k = 0.72 + ridged(v.x * 1.1 + seed, v.y * 1.1, v.z * 1.1, 4) * 0.55; v.multiplyScalar(k); v.y *= 0.7; p.setXYZ(i, v.x, v.y, v.z); } g.computeVertexNormals();
  const m = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.92 }); const u = { tRock: { value: tex('rock').map }, uSnow: { value: o.snow ?? 0 } };
  patch(m, { attrs: [], uniforms: u, key: 'rockm', vdecl: 'varying vec3 vWN; varying vec3 vOP;', vmain: 'vWN = normalize(mat3(modelMatrix)*mat3(instanceMatrix)*objectNormal); vOP = position;', decl: 'uniform sampler2D tRock; uniform float uSnow; varying vec3 vWN; varying vec3 vOP;',
    color: `{ vec3 n = normalize(vWN); vec3 w = pow(abs(n), vec3(5.)); w /= (w.x+w.y+w.z); vec3 pp = vOP*0.45; vec3 c = texture2D(tRock, pp.zy).rgb*w.x + texture2D(tRock, pp.xz).rgb*w.y + texture2D(tRock, pp.xy).rgb*w.z; c *= 0.8 + 0.4*fbm3(vOP*2.); c = mix(c, vec3(0.2, 0.26, 0.12), smoothstep(0.55, 0.9, n.y)*0.35*(1.-uSnow)); c = mix(c, vec3(0.9, 0.93, 0.98), uSnow*smoothstep(0.35, 0.75, n.y + (fbm3(vOP*3.)-0.5)*0.4)); diffuseColor.rgb = c; }`,
    normal: `{ normal = bumpN(normal, -vViewPosition, fbm3(vOP*9.), 0.03*smoothstep(0.2, 0.02, pxSize(vWPos))); }` });
  const im = new THREE.InstancedMesh(g, m, spots.length); const mtx = new THREE.Matrix4(), q = new THREE.Quaternion();
  spots.forEach((s, i) => { q.setFromEuler(new THREE.Euler(r() * 0.6, r() * 6.283, r() * 0.6)); const sc = s.s ?? 1; mtx.compose(V3(s.x, s.y - sc * 0.15, s.z), q, V3(sc * (0.8 + r() * 0.5), sc * (0.6 + r() * 0.6), sc * (0.8 + r() * 0.5))); im.setMatrixAt(i, mtx); });
  im.castShadow = true; im.receiveShadow = true; im.frustumCulled = false; return im;
}

/* ================= buildings & props ================= */
function box(w, h, d, x = 0, y = 0, z = 0, uvs = 1) { const g = new THREE.BoxGeometry(w, h, d); const uv = g.attributes.uv, p = g.attributes.position, n = g.attributes.normal; for (let i = 0; i < uv.count; i++) { const nx = Math.abs(n.getX(i)), ny = Math.abs(n.getY(i)); const a = nx > 0.5 ? p.getZ(i) : p.getX(i), b = ny > 0.5 ? p.getZ(i) : p.getY(i); uv.setXY(i, a * uvs * 0.5 + 0.5, b * uvs * 0.5 + 0.5); } g.translate(x, y, z); return g; }
const PM = {};
export function propMat(name, o = {}) { const k = name + JSON.stringify(o); if (PM[k]) return PM[k]; const t = tex(name); return PM[k] = new THREE.MeshLambertMaterial({ map: t.map, color: o.color ? C(...o.color) : 0xffffff, side: o.side ?? THREE.FrontSide, emissive: o.emissive ? C(...o.emissive) : 0x000000, emissiveIntensity: o.ei ?? 1 }); }
/** gable roof prism with overhang; ridge along X */
function roofGeo(w, d, h, over = 0.5, uvs = 0.5) {
  const hw = w / 2 + over, hd = d / 2 + over; const pos = [], uv = [], idx = [];
  const quad = (a, b, c, e, u0, u1, v1) => { const i = pos.length / 3; pos.push(...a, ...b, ...c, ...e); uv.push(u0, 0, u1, 0, u1, v1, u0, v1); idx.push(i, i + 1, i + 2, i, i + 2, i + 3); };
  const sl = Math.hypot(hd, h);
  quad([-hw, 0, hd], [hw, 0, hd], [hw, h, 0], [-hw, h, 0], 0, 2 * hw * uvs, sl * uvs); quad([hw, 0, -hd], [-hw, 0, -hd], [-hw, h, 0], [hw, h, 0], 0, 2 * hw * uvs, sl * uvs);
  // underside so the eaves are not see-through
  quad([hw, -0.05, hd], [-hw, -0.05, hd], [-hw, h - 0.05, 0], [hw, h - 0.05, 0], 0, 1, 1); quad([-hw, -0.05, -hd], [hw, -0.05, -hd], [hw, h - 0.05, 0], [-hw, h - 0.05, 0], 0, 1, 1);
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx); g.computeVertexNormals(); return g;
}
function gableGeo(w, d, h, uvs = 0.5) { const pos = [], uv = [], idx = []; for (const s of [1, -1]) { const i = pos.length / 3; const x = s * w / 2; pos.push(x, 0, -d / 2 * s, x, 0, d / 2 * s, x, h, 0); uv.push(0, 0, d * uvs, 0, d * uvs / 2, h * uvs); idx.push(i, i + 1, i + 2); } const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2)); g.setIndex(idx); g.computeVertexNormals(); return g; }
/** a village house. returns Group. o: {w,d,h,roof:'thatch'|'wood'|'tiles', lit, burnt} */
export function house(seed = 1, o = {}) {
  const r = mulberry(seed * 131 + 7); const w = o.w ?? 5 + r() * 3, d = o.d ?? 4 + r() * 2, h = o.h ?? 2.4 + r() * 0.5, rh = o.rh ?? 1.8 + r() * 0.9; const g = new THREE.Group();
  const add = (geo, mat, cast = true) => { const m = new THREE.Mesh(geo, mat); m.castShadow = cast; m.receiveShadow = true; g.add(m); return m; };
  const burnt = o.burnt ?? 0; const dark = [1 - burnt * 0.8, 1 - burnt * 0.82, 1 - burnt * 0.84];
  add(box(w + 0.3, 0.5, d + 0.3, 0, 0.25, 0, 0.5), propMat('stone', { color: dark }));
  add(box(w, h, d, 0, 0.5 + h / 2, 0, 0.35), propMat('plaster', { color: dark }));
  // timber frame
  const beams = []; const bw = 0.16; for (const sx of [-1, 1]) for (const sz of [-1, 1]) beams.push(box(bw, h, bw, sx * (w / 2), 0.5 + h / 2, sz * (d / 2), 2));
  for (const sz of [-1, 1]) { beams.push(box(w + bw, bw, bw, 0, 0.5 + h, sz * d / 2, 2)); beams.push(box(w + bw, bw, bw, 0, 0.5 + h * 0.45, sz * d / 2, 2)); for (let k = 1; k < 3; k++) beams.push(box(bw * 0.8, h, bw * 0.8, -w / 2 + w * k / 3, 0.5 + h / 2, sz * d / 2, 2)); }
  for (const sx of [-1, 1]) { beams.push(box(bw, bw, d + bw, sx * w / 2, 0.5 + h, 0, 2)); beams.push(box(bw, bw, d + bw, sx * w / 2, 0.5 + h * 0.45, 0, 2)); }
  add(BGU.mergeGeometries(beams), propMat('wood', { color: [0.55 * dark[0], 0.5 * dark[1], 0.45 * dark[2]] }));
  const roofM = propMat(o.roof || 'thatch', { color: dark, side: THREE.DoubleSide }); const rf = add(roofGeo(w, d, rh, 0.55, 0.3), roofM); rf.position.y = 0.5 + h;
  const gb = add(gableGeo(w, d, rh, 0.35), propMat('wood', { color: [0.5 * dark[0], 0.45 * dark[1], 0.4 * dark[2]], side: THREE.DoubleSide })); gb.position.y = 0.5 + h;
  // door + windows (dark recess, warm glow when lit)
  const lit = o.lit ?? 0; const glow = new THREE.MeshStandardMaterial({ color: 0x050403, emissive: C(1.0, 0.55, 0.2), emissiveIntensity: lit * 3.0, roughness: 1 });
  const holes = [box(0.95, 1.9, 0.06, (r() - 0.5) * w * 0.4, 0.5 + 0.95, d / 2 + 0.01)]; const nw = 1 + Math.floor(r() * 2); for (let k = 0; k < nw; k++) holes.push(box(0.7, 0.7, 0.06, (k === 0 ? -1 : 1) * w * 0.3, 0.5 + h * 0.62, d / 2 + 0.01)); holes.push(box(0.06, 0.7, 0.8, w / 2 + 0.01, 0.5 + h * 0.62, 0));
  add(BGU.mergeGeometries(holes), glow, false);
  g.userData = { w, d, h, rh, top: 0.5 + h + rh }; return g;
}
export function fence(len, o = {}) { const parts = []; const n = Math.max(2, Math.round(len / 1.6)); for (let i = 0; i <= n; i++) parts.push(box(0.1, 1.1, 0.1, -len / 2 + len * i / n, 0.55, 0, 3)); parts.push(box(len, 0.08, 0.05, 0, 0.85, 0, 1), box(len, 0.08, 0.05, 0, 0.45, 0, 1)); const m = new THREE.Mesh(BGU.mergeGeometries(parts), propMat('wood', { color: [0.6, 0.55, 0.5] })); m.castShadow = true; m.receiveShadow = true; return m; }
/** standing torch / brazier with an emissive bowl; fire is added by fx */
export function torch(hgt = 1.7) { const g = new THREE.Group(); const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.05, hgt, 6), propMat('wood', { color: [0.4, 0.35, 0.3] })); pole.position.y = hgt / 2; pole.castShadow = true; const cup = new THREE.Mesh(new THREE.CylinderGeometry(0.11, 0.05, 0.16, 8), new THREE.MeshStandardMaterial({ color: 0x151210, roughness: 0.7, metalness: 0.6 })); cup.position.y = hgt + 0.05; g.add(pole, cup); g.userData.top = V3(0, hgt + 0.16, 0); return g; }
/** hanging cloth banner on a pole; waves in the wind (vertex shader) */
export function banner(o = {}) {
  const hgt = o.height ?? 4.5, w = o.w ?? 0.9, len = o.len ?? 2.4; const g = new THREE.Group(); const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.03, 0.04, hgt, 6), propMat('wood', { color: [0.3, 0.27, 0.25] })); pole.position.y = hgt / 2; pole.castShadow = true; g.add(pole);
  const cg = new THREE.PlaneGeometry(w, len, 6, 14); cg.translate(w / 2 + 0.03, hgt - len / 2 - 0.1, 0);
  const u = { uCol: { value: C(...(o.color || [0.5, 0.05, 0.05])) }, uEmb: { value: C(...(o.emblem || [0.85, 0.8, 0.7])) }, uH: { value: new THREE.Vector4(hgt - 0.1, len, w, o.wave ?? 1) }, uSeed: { value: o.seed ?? 0 } };
  const m = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.85, side: THREE.DoubleSide });
  patch(m, { attrs: [], uniforms: u, key: 'banner', vdecl: 'uniform float uTime, uSeed; uniform vec4 uH; varying vec2 vB;', vmain: `{ float u_ = (position.x - 0.03)/uH.z; float v_ = (uH.x - position.y)/uH.y; vB = vec2(u_, v_); float ph = uTime*4.5 + uSeed*7.; float amp = (0.25 + 0.75*u_)*(0.3 + 0.7*v_)*uH.w; transformed.z += sin(v_*7. - ph + u_*3.)*0.16*amp + sin(v_*13. - ph*1.4)*0.05*amp; transformed.x += sin(v_*5. - ph*0.8)*0.06*amp; }`,
    decl: 'uniform vec3 uCol, uEmb; varying vec2 vB;', color: `{ vec3 c = uCol*(0.8 + 0.4*fbm3(vec3(vB*vec2(6., 14.), 1.))); vec2 q = (vB - vec2(0.5, 0.36))*vec2(1., 2.6); float rr = length(q); float em = smoothstep(0.34, 0.31, rr)*smoothstep(0.22, 0.25, rr) + smoothstep(0.12, 0.10, max(abs(q.x)*1.6, abs(q.y + 0.02))); c = mix(c, uEmb, clamp(em, 0., 1.)*0.9); c *= smoothstep(1.02, 0.9, vB.y + fbm3(vec3(vB.x*18., 2., 3.))*0.12); diffuseColor.rgb = c; }`,
    alpha: `{ if(vB.y + fbm3(vec3(vB.x*18., 2., 3.))*0.12 > 1.0) discard; }` });
  const cloth = new THREE.Mesh(cg, m); cloth.castShadow = true; cloth.frustumCulled = false; g.add(cloth); return g;
}
/** wide stone stairway going up +Z→ along a slope: n steps */
export function stairs(n, w = 4, rise = 0.19, run = 0.34) { const parts = []; for (let i = 0; i < n; i++) parts.push(box(w, rise, run + 0.04, 0, rise * (i + 0.5), run * i, 0.5)); const m = new THREE.Mesh(BGU.mergeGeometries(parts), propMat('stone', { color: [0.8, 0.8, 0.82] })); m.castShadow = true; m.receiveShadow = true; return m; }
/** mountain gate: two pillars, double lintel, small roof */
export function gate(w = 5, h = 5.2) { const g = new THREE.Group(); const wood = propMat('wood', { color: [0.55, 0.16, 0.10] }); const parts = [];
  for (const s of [-1, 1]) { const c = new THREE.CylinderGeometry(0.2, 0.24, h, 10); c.translate(s * w / 2, h / 2, 0); parts.push(c); }
  parts.push(box(w + 1.6, 0.3, 0.36, 0, h - 0.5, 0, 1), box(w + 0.6, 0.22, 0.28, 0, h - 1.3, 0, 1)); const m = new THREE.Mesh(BGU.mergeGeometries(parts.map(p => p.toNonIndexed())), wood); m.castShadow = true; m.receiveShadow = true; g.add(m);
  const rf = new THREE.Mesh(roofGeo(w + 1.8, 0.9, 0.5, 0.25, 0.4), propMat('tiles', { side: THREE.DoubleSide })); rf.position.y = h - 0.3; rf.castShadow = true; g.add(rf); return g; }
/** temple hall: raised stone platform, red pillars, sweeping tiled roof */
export function temple(w = 14, d = 9) { const g = new THREE.Group(); const add = (geo, mat) => { const m = new THREE.Mesh(geo, mat); m.castShadow = true; m.receiveShadow = true; g.add(m); return m; };
  add(box(w + 3, 1.0, d + 3, 0, 0.5, 0, 0.3), propMat('stone', { color: [0.8, 0.8, 0.82] })); add(box(w - 1, 3.4, d - 1, 0, 1 + 1.7, 0, 0.3), propMat('plaster', { color: [0.85, 0.8, 0.7] }));
  const cols = []; for (let i = 0; i <= 5; i++) for (const s of [-1, 1]) { const c = new THREE.CylinderGeometry(0.22, 0.24, 3.6, 10); c.translate(-w / 2 + w * i / 5, 1 + 1.8, s * d / 2); cols.push(c.toNonIndexed()); } cols.push(box(w + 0.6, 0.3, 0.3, 0, 4.5, d / 2, 1).toNonIndexed(), box(w + 0.6, 0.3, 0.3, 0, 4.5, -d / 2, 1).toNonIndexed());
  add(BGU.mergeGeometries(cols), propMat('wood', { color: [0.55, 0.15, 0.10] }));
  const rf = add(roofGeo(w + 1, d + 1, 3.2, 1.6, 0.25), propMat('tiles', { side: THREE.DoubleSide })); rf.position.y = 4.6; const p = rf.geometry.attributes.position; for (let i = 0; i < p.count; i++) { const x = Math.abs(p.getX(i)) / (w / 2 + 2.1), z = Math.abs(p.getZ(i)) / (d / 2 + 2.1); p.setY(i, p.getY(i) + Math.pow(Math.max(x, z), 3) * 1.1 - (1 - p.getY(i) / 3.2) * 0.0); } rf.geometry.computeVertexNormals();
  const gb = add(gableGeo(w + 1, d + 1, 3.2, 0.3), propMat('plaster', { color: [0.8, 0.75, 0.65], side: THREE.DoubleSide })); gb.position.y = 4.6; return g; }
/** stone lantern */
export function lantern(lit = 1) { const g = new THREE.Group(); const st = propMat('stone', { color: [0.75, 0.75, 0.78] }); const parts = [box(0.5, 0.14, 0.5, 0, 0.07, 0, 2), box(0.2, 0.9, 0.2, 0, 0.6, 0, 2), box(0.48, 0.1, 0.48, 0, 1.1, 0, 2), box(0.6, 0.1, 0.6, 0, 1.55, 0, 2)]; const cone = new THREE.ConeGeometry(0.42, 0.3, 4); cone.rotateY(Math.PI / 4); cone.translate(0, 1.75, 0); parts.push(cone.toNonIndexed());
  const m = new THREE.Mesh(BGU.mergeGeometries(parts.map(p => p.index ? p.toNonIndexed() : p)), st); m.castShadow = true; g.add(m); const fire = new THREE.Mesh(box(0.3, 0.34, 0.3, 0, 1.33, 0), new THREE.MeshStandardMaterial({ color: 0x100804, emissive: C(1, 0.6, 0.25), emissiveIntensity: lit * 4 })); g.add(fire); return g; }
/** market stall: four posts and a cloth canopy, table */
export function stall(seed = 1) { const r = mulberry(seed * 53); const g = new THREE.Group(); const posts = []; for (const sx of [-1, 1]) for (const sz of [-1, 1]) posts.push(box(0.08, 2.2 + (sz < 0 ? 0.3 : 0), 0.08, sx * 1.2, 1.1, sz * 0.8, 3)); posts.push(box(2.5, 0.08, 1.0, 0, 0.85, 0.1, 1)); const m = new THREE.Mesh(BGU.mergeGeometries(posts), propMat('wood', { color: [0.6, 0.55, 0.5] })); m.castShadow = true; g.add(m);
  const cg = new THREE.PlaneGeometry(2.8, 2.0, 6, 4); cg.rotateX(-Math.PI / 2 + 0.16); cg.translate(0, 2.35, 0); const p = cg.attributes.position; for (let i = 0; i < p.count; i++) p.setY(i, p.getY(i) - Math.sin((p.getX(i) / 2.8 + 0.5) * Math.PI) * 0.12); cg.computeVertexNormals();
  const cols = [[0.6, 0.12, 0.1], [0.75, 0.6, 0.3], [0.2, 0.3, 0.5], [0.8, 0.75, 0.65]]; const cm = new THREE.Mesh(cg, propMat('canvas', { color: cols[Math.floor(r() * cols.length)], side: THREE.DoubleSide })); cm.castShadow = true; g.add(cm);
  const goods = []; for (let i = 0; i < 7; i++) { const s = new THREE.SphereGeometry(0.09 + r() * 0.05, 7, 5); s.translate(-1 + i * 0.33, 0.98, 0.1 + (r() - 0.5) * 0.4); goods.push(s); } const gm = new THREE.Mesh(BGU.mergeGeometries(goods), new THREE.MeshStandardMaterial({ color: C(0.7, 0.35 + r() * 0.3, 0.12), roughness: 0.6 })); g.add(gm); return g; }
/** simple well */
export function well() { const g = new THREE.Group(); const ring = new THREE.Mesh(new THREE.CylinderGeometry(0.9, 0.95, 0.8, 14, 1, true), propMat('stone', { side: THREE.DoubleSide })); ring.position.y = 0.4; ring.castShadow = true; g.add(ring); const posts = [box(0.12, 2.2, 0.12, -0.85, 1.1, 0, 3), box(0.12, 2.2, 0.12, 0.85, 1.1, 0, 3), box(2.0, 0.1, 0.1, 0, 2.0, 0, 2)]; const m = new THREE.Mesh(BGU.mergeGeometries(posts), propMat('wood', { color: [0.55, 0.5, 0.45] })); m.castShadow = true; g.add(m); const rf = new THREE.Mesh(roofGeo(2.2, 1.2, 0.6, 0.2, 0.4), propMat('thatch', { side: THREE.DoubleSide })); rf.position.y = 2.2; rf.castShadow = true; g.add(rf); return g; }
/** scatter helper: n points in a region rejected by a predicate; y from height fn */
export function scatter(n, seed, region, height, accept) { const r = mulberry(seed); const out = []; let guard = 0; while (out.length < n && guard++ < n * 30) { const x = lerp(region[0], region[1], r()), z = lerp(region[2], region[3], r()); const y = height(x, z); if (accept && !accept(x, z, y, r)) continue; out.push({ x, y, z, s: 0.75 + r() * 0.6, rot: r() * 6.283, tint: 0.75 + r() * 0.45 }); } return out; }
