// Effects: fire, smoke, embers, sparks, rain, snow, mist, dust, sword trails, shockwaves. Stateless (a function of time) so any frame can be rendered on its own.
import { THREE, mulberry, V3, noiseTex, clamp, wob, lerp } from './core.js';
import { GU } from './mat.js';

const QUAD = (() => { const g = new THREE.PlaneGeometry(1, 1); return g; })();
/** n billboards. init(i, rnd) -> {pos:[x,y,z], par:[size, life, speed, t0], seed?:[..4]} */
function particles(n, init, o) {
  const g = new THREE.InstancedBufferGeometry(); g.index = QUAD.index; g.setAttribute('position', QUAD.attributes.position); g.setAttribute('uv', QUAD.attributes.uv);
  const pos = new Float32Array(n * 3), seed = new Float32Array(n * 4), par = new Float32Array(n * 4); const r = mulberry(o.seed ?? 1);
  for (let i = 0; i < n; i++) { const p = init(i, r); pos.set(p.pos, i * 3); par.set(p.par, i * 4); seed.set(p.seed || [r(), r(), r(), r()], i * 4); }
  g.setAttribute('aPos', new THREE.InstancedBufferAttribute(pos, 3)); g.setAttribute('aSeed', new THREE.InstancedBufferAttribute(seed, 4)); g.setAttribute('aPar', new THREE.InstancedBufferAttribute(par, 4)); g.instanceCount = n;
  const u = { uTime: GU.uTime, uWind: { value: V3(...(o.wind || [0, 0, 0])) }, tNoise: { value: noiseTex() }, uGain: { value: o.gain ?? 1 }, uCol: { value: new THREE.Color(...(o.color || [1, 1, 1])) }, uCol2: { value: new THREE.Color(...(o.color2 || o.color || [1, 1, 1])) }, uBox: { value: V3(...(o.box || [30, 20, 30])) }, uCam: { value: V3() }, uT0: { value: o.t0 ?? 0 }, uAlpha: { value: o.alpha ?? 1 }, ...(o.uniforms || {}) };
  const m = new THREE.ShaderMaterial({ uniforms: u, transparent: true, depthWrite: false, blending: o.additive ? THREE.AdditiveBlending : THREE.NormalBlending, side: THREE.DoubleSide,
    vertexShader: /* glsl */`attribute vec3 aPos; attribute vec4 aSeed, aPar; uniform float uTime, uT0; uniform vec3 uWind, uBox, uCam; uniform sampler2D tNoise; varying vec2 vUv; varying float vAge; varying vec4 vSeed; varying float vK;
      void quad(vec3 c, float size, float rot){ vec4 mv = viewMatrix*vec4(c, 1.); float cs = cos(rot), sn = sin(rot); vec2 q = position.xy; mv.xy += vec2(q.x*cs - q.y*sn, q.x*sn + q.y*cs)*size; gl_Position = projectionMatrix*mv; }
      void streak(vec3 c, vec3 vel, float len, float wid){ vec4 a = viewMatrix*vec4(c, 1.), b = viewMatrix*vec4(c - vel*len, 1.); vec2 d = normalize(a.xy - b.xy + 1e-5); vec2 nrm = vec2(-d.y, d.x); vec4 mv = mix(b, a, position.y + .5); mv.xy += nrm*position.x*wid; gl_Position = projectionMatrix*mv; }
      void hide(){ gl_Position = vec4(2., 2., 2., 1.); }
      void main(){ vUv = uv; vSeed = aSeed; vK = 1.; float life = aPar.y; float tt = uTime - uT0 - aPar.w; ${o.vertex} }`,
    fragmentShader: /* glsl */`uniform float uTime, uGain, uAlpha; uniform vec3 uCol, uCol2; uniform sampler2D tNoise; varying vec2 vUv; varying float vAge; varying vec4 vSeed; varying float vK;
      void main(){ vec2 p = vUv - .5; float r = length(p)*2.; ${o.fragment} }` });
  const mesh = new THREE.Mesh(g, m); mesh.frustumCulled = false; mesh.renderOrder = o.order ?? 10; mesh.userData.u = u; return mesh;
}
const LOOPAGE = `float age = fract((tt + aSeed.x*life)/life); vAge = age;`;

/** flames at a list of points: [{x,y,z,s}] (s = size multiplier). */
export function fire(points, o = {}) {
  const per = o.per ?? 14; return particles(points.length * per, (i, r) => { const p = points[Math.floor(i / per)], s = (p.s ?? 1); return { pos: [p.x + (r() - .5) * 0.5 * s, p.y, p.z + (r() - .5) * 0.5 * s], par: [(0.55 + r() * 0.6) * s, 0.7 + r() * 0.7, (1.6 + r() * 1.4) * s, 0] }; },
    { additive: true, seed: o.seed ?? 3, wind: o.wind, gain: o.gain ?? 1,
      vertex: `${LOOPAGE} vec3 c = aPos; c.y += age*aPar.z*0.9 + aPar.x*0.2; c.xz += (aSeed.zw - .5)*aPar.x*0.5*(1. - age) + uWind.xz*age*age*aPar.z*0.8; c.x += sin(uTime*5. + aSeed.y*30.)*0.06*aPar.x*age; quad(c, aPar.x*(1.15 - 0.75*age), aSeed.w*6.28 + uTime*(aSeed.z - .5)*2.);`,
      fragment: `float n = texture2D(tNoise, vUv*0.6 + vec2(vSeed.x*7., vSeed.y*3. - uTime*0.9)).g; float a = smoothstep(1., 0.1, r + (n - .5)*0.9)*smoothstep(0., 0.12, vAge)*(1. - vAge);
        vec3 c = mix(vec3(1.0, 0.16, 0.02), vec3(1.0, 0.55, 0.12), smoothstep(0.75, 0.2, vAge + r*0.3)); c = mix(c, vec3(1.0, 0.85, 0.55), smoothstep(0.35, 0.0, vAge + r*0.5)); gl_FragColor = vec4(c*a*a*4.2*uGain, 1.);` });
}
/** rising smoke columns at points */
export function smoke(points, o = {}) {
  const per = o.per ?? 10; return particles(points.length * per, (i, r) => { const p = points[Math.floor(i / per)], s = (p.s ?? 1); return { pos: [p.x + (r() - .5) * s, p.y + (o.lift ?? 1.2) * s, p.z + (r() - .5) * s], par: [(1.6 + r() * 1.4) * s, (o.life ?? 7) * (0.7 + r() * 0.6), (o.rise ?? 2.2) * (0.7 + r() * 0.6), 0] }; },
    { additive: false, seed: o.seed ?? 5, wind: o.wind || [0.6, 0, 0.2], color: o.color || [0.045, 0.04, 0.04], color2: o.glow || [0.9, 0.3, 0.06], alpha: o.alpha ?? 0.55, order: 5,
      vertex: `${LOOPAGE} vec3 c = aPos; float tl = age*life; c.y += tl*aPar.z; c += uWind*tl*(0.4 + age); c.xz += (aSeed.zw - .5)*aPar.x*(0.4 + age*2.2); quad(c, aPar.x*(0.8 + 3.2*age), aSeed.w*6.28 + tl*0.15*(aSeed.z - .5));`,
      fragment: `float n = texture2D(tNoise, vUv*0.45 + vSeed.xy*5. + vec2(0., -uTime*0.03)).r; float a = smoothstep(1., 0.25, r + (n - .5)*1.1)*smoothstep(0., 0.1, vAge)*pow(1. - vAge, 1.5)*uAlpha;
        vec3 c = uCol*(0.6 + 0.9*n) + uCol2*smoothstep(0.35, 0.0, vAge)*0.5*(1. - r); gl_FragColor = vec4(c, a);` });
}
/** embers / sparks drifting up from an area. area: [x0,x1,y0,y1,z0,z1] */
export function embers(n, area, o = {}) {
  return particles(n, (i, r) => ({ pos: [lerp(area[0], area[1], r()), lerp(area[2], area[3], r() * r()), lerp(area[4], area[5], r())], par: [(o.size ?? 0.035) * (0.5 + r()), (o.life ?? 5) * (0.5 + r()), (o.rise ?? 2.4) * (0.4 + r()), 0] }),
    { additive: true, seed: o.seed ?? 9, wind: o.wind || [0.8, 0, 0.3], gain: o.gain ?? 1, color: o.color || [1.0, 0.42, 0.08],
      vertex: `${LOOPAGE} vec3 c = aPos; float tl = age*life; c.y += tl*aPar.z; c += uWind*tl; c.x += sin(tl*1.7 + aSeed.y*40.)*0.5 + sin(tl*4.1 + aSeed.z*20.)*0.15; c.z += cos(tl*1.3 + aSeed.w*40.)*0.5; vK = smoothstep(0., 0.08, age)*(1. - age)*(0.5 + 0.5*sin(uTime*9. + aSeed.x*90.));
        vec3 vel = vec3(cos(tl*1.7 + aSeed.y*40.)*0.85, aPar.z, -sin(tl*1.3 + aSeed.w*40.)*0.65) + uWind; streak(c, normalize(vel), aPar.x*5., aPar.x*1.6);`,
      fragment: `float a = smoothstep(1., 0., abs(p.x)*2.)*smoothstep(.5, .1, abs(p.y)); gl_FragColor = vec4(uCol*a*vK*7.*uGain, 1.);` });
}
/** burst of sparks from a point at time t0 (e.g. steel on steel). dir biases the spray. */
export function sparkBurst(pos, t0, o = {}) {
  const n = o.n ?? 70; const d = o.dir || [0, 1, 0];
  return particles(n, (i, r) => { const a = r() * 6.283, e = Math.acos(1 - r() * (o.spread ?? 1.2)); const sp = (o.speed ?? 7) * (0.25 + r() * r()); const v = V3(Math.sin(e) * Math.cos(a), Math.cos(e), Math.sin(e) * Math.sin(a)); const q = new THREE.Quaternion().setFromUnitVectors(V3(0, 1, 0), V3(...d).normalize()); v.applyQuaternion(q).multiplyScalar(sp);
    return { pos: [pos.x, pos.y, pos.z], par: [(o.size ?? 0.012) * (0.6 + r()), (o.life ?? 0.7) * (0.4 + r()), 0, 0], seed: [v.x, v.y, v.z, r()] }; },
    { additive: true, seed: o.seed ?? 11, t0, gain: o.gain ?? 1, color: o.color || [1.0, 0.72, 0.35],
      vertex: `float age = tt/life; if(age < 0. || age > 1.){ hide(); return; } vAge = age; vec3 g = vec3(0., -9.0, 0.); vec3 c = aPos + aSeed.xyz*tt*(1. - 0.25*age) + 0.5*g*tt*tt; vec3 vel = aSeed.xyz*(1. - 0.5*age) + g*tt; vK = 1. - age*age; streak(c, normalize(vel), aPar.x*(4. + length(vel)*1.5), aPar.x*2.2);`,
      fragment: `float a = smoothstep(1., 0., abs(p.x)*2.)*smoothstep(.5, .0, abs(p.y)); gl_FragColor = vec4(mix(uCol, vec3(1.), vK*0.5)*a*vK*10.*uGain, 1.);` });
}
/** burst of dust / debris puffs at time t0 */
export function dustBurst(pos, t0, o = {}) {
  const n = o.n ?? 14; return particles(n, (i, r) => { const a = r() * 6.283, sp = (o.speed ?? 2.5) * (0.3 + r()); return { pos: [pos.x, pos.y, pos.z], par: [(o.size ?? 0.8) * (0.5 + r()), (o.life ?? 1.6) * (0.6 + r() * 0.8), 0, 0], seed: [Math.cos(a) * sp, (o.up ?? 0.8) * r(), Math.sin(a) * sp, r()] }; },
    { additive: false, seed: o.seed ?? 13, t0, color: o.color || [0.34, 0.29, 0.23], alpha: o.alpha ?? 0.5, order: 6,
      vertex: `float age = tt/life; if(age < 0. || age > 1.){ hide(); return; } vAge = age; float e = 1. - exp(-tt*2.5); vec3 c = aPos + aSeed.xyz*e*0.8 + vec3(0., aPar.x*0.3*age, 0.); quad(c, aPar.x*(0.5 + 2.2*age), aSeed.w*6.28);`,
      fragment: `float n = texture2D(tNoise, vUv*0.5 + vSeed.xy).r; float a = smoothstep(1., 0.2, r + (n - .5))*smoothstep(0., 0.08, vAge)*pow(1. - vAge, 1.3)*uAlpha; gl_FragColor = vec4(uCol*(0.7 + 0.6*n), a);` });
}
/** rain in a box that follows the camera */
export function rain(n = 5000, o = {}) {
  const box = o.box || [26, 16, 26]; return particles(n, (i, r) => ({ pos: [r() * box[0], r() * box[1], r() * box[2]], par: [o.width ?? 0.012, 1, (o.speed ?? 15) * (0.8 + r() * 0.4), 0] }),
    { additive: true, seed: o.seed ?? 17, wind: o.wind || [2.5, 0, 0.5], box, gain: o.gain ?? 1, color: o.color || [0.55, 0.62, 0.72], alpha: o.alpha ?? 0.35,
      vertex: `vec3 vel = vec3(uWind.x, -aPar.z, uWind.z); vec3 c = aPos + vel*uTime; c = mod(c - uCam + uBox*0.5, uBox) - uBox*0.5 + uCam; vK = 0.4 + 0.6*aSeed.x; streak(c, normalize(vel), 0.55 + aSeed.y*0.5, aPar.x);`,
      fragment: `float a = smoothstep(1., 0.2, abs(p.x)*2.)*smoothstep(.5, .3, abs(p.y)); gl_FragColor = vec4(uCol*a*vK*uAlpha*uGain, 1.);` });
}
/** snowfall in a box that follows the camera */
export function snow(n = 4000, o = {}) {
  const box = o.box || [28, 16, 28]; return particles(n, (i, r) => ({ pos: [r() * box[0], r() * box[1], r() * box[2]], par: [(o.size ?? 0.035) * (0.5 + r()), 1, (o.speed ?? 1.5) * (0.6 + r() * 0.8), 0] }),
    { additive: false, seed: o.seed ?? 19, wind: o.wind || [1.2, 0, 0.4], box, color: o.color || [0.92, 0.94, 1.0], alpha: o.alpha ?? 0.9,
      vertex: `vec3 c = aPos + vec3(uWind.x, -aPar.z, uWind.z)*uTime; c.x += sin(uTime*0.9 + aSeed.x*30.)*0.35; c.z += cos(uTime*0.7 + aSeed.y*30.)*0.35; c = mod(c - uCam + uBox*0.5, uBox) - uBox*0.5 + uCam; float d = length(c - uCam); vK = smoothstep(0.3, 1.5, d); quad(c, aPar.x*(1. + 0.04*d), 0.);`,
      fragment: `float a = smoothstep(1., 0.3, r)*vK*uAlpha; gl_FragColor = vec4(uCol, a);` });
}
/** drifting mist / ground fog cards over an area [x0,x1,y0,y1,z0,z1] */
export function mist(n, area, o = {}) {
  return particles(n, (i, r) => ({ pos: [lerp(area[0], area[1], r()), lerp(area[2], area[3], r()), lerp(area[4], area[5], r())], par: [(o.size ?? 14) * (0.6 + r()), 1, 0, r() * 100] }),
    { additive: false, seed: o.seed ?? 23, wind: o.wind || [0.5, 0, 0.15], color: o.color || [0.6, 0.65, 0.72], alpha: o.alpha ?? 0.12, order: 4, box: [area[1] - area[0], 1, area[5] - area[4]], uniforms: { uOrg: { value: V3(area[0], 0, area[4]) } },
      vertex: `vec3 c = aPos; c.xz += uWind.xz*uTime; c.xz = mod(c.xz - vec2(${area[0].toFixed(1)}, ${area[4].toFixed(1)}), uBox.xz) + vec2(${area[0].toFixed(1)}, ${area[4].toFixed(1)}); float d = length(c - cameraPosition); vK = smoothstep(2., 9., d); quad(c, aPar.x, aSeed.w*6.28 + uTime*0.02*(aSeed.z - .5));`,
      fragment: `float n = texture2D(tNoise, vUv*0.3 + vSeed.xy*4. + uTime*0.004).r; float a = smoothstep(1., 0.0, r + (n - .5)*0.8); gl_FragColor = vec4(uCol*(0.8 + 0.4*n), a*a*uAlpha*vK);` });
}
/** floating dust motes / ash flakes around the camera */
export function motes(n = 600, o = {}) {
  const box = o.box || [14, 8, 14]; return particles(n, (i, r) => ({ pos: [r() * box[0], r() * box[1], r() * box[2]], par: [(o.size ?? 0.012) * (0.5 + r()), 1, 0, 0] }),
    { additive: !!o.additive, seed: o.seed ?? 29, wind: o.wind || [0.15, 0.03, 0.05], box, color: o.color || [1, 0.9, 0.75], alpha: o.alpha ?? 0.5, gain: o.gain ?? 1,
      vertex: `vec3 c = aPos + uWind*uTime; c.x += sin(uTime*0.4 + aSeed.x*30.)*0.3; c.y += sin(uTime*0.31 + aSeed.y*30.)*0.2; c = mod(c - uCam + uBox*0.5, uBox) - uBox*0.5 + uCam; vK = 0.5 + 0.5*sin(uTime*1.3 + aSeed.z*40.); quad(c, aPar.x, 0.);`,
      fragment: `float a = smoothstep(1., 0.2, r)*vK*uAlpha; gl_FragColor = vec4(uCol*uGain*(${o.additive ? 'a' : '1.'}), ${o.additive ? '1.' : 'a'});` });
}
/** expanding shock ring on the ground at t0 */
export function shockwave(pos, t0, o = {}) {
  const g = new THREE.RingGeometry(0.01, 1, 48, 1); g.rotateX(-Math.PI / 2); const u = { uTime: GU.uTime, uT0: { value: t0 }, uLife: { value: o.life ?? 0.5 }, uR: { value: o.radius ?? 5 }, uCol: { value: new THREE.Color(...(o.color || [1, 0.9, 0.75])) } };
  const m = new THREE.ShaderMaterial({ uniforms: u, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide,
    vertexShader: `uniform float uTime, uT0, uLife, uR; varying float vA; varying float vR; void main(){ float a = clamp((uTime - uT0)/uLife, 0., 1.); vA = (uTime < uT0 || a >= 1.) ? 0. : 1. - a; vR = length(position.xz); vec3 p = position*uR*(1. - pow(1. - a, 3.)); gl_Position = projectionMatrix*modelViewMatrix*vec4(p, 1.); }`,
    fragmentShader: `uniform vec3 uCol; varying float vA; varying float vR; void main(){ float e = smoothstep(0.55, 1.0, vR)*smoothstep(1.0, 0.92, vR); gl_FragColor = vec4(uCol*e*vA*vA*5., 1.); }` });
  const mesh = new THREE.Mesh(g, m); mesh.position.copy(pos); mesh.frustumCulled = false; mesh.renderOrder = 12; return mesh;
}
/** glowing ribbon that follows a moving edge (sword blade): call push(base, tip, t) each frame */
export class Trail {
  constructor(o = {}) { this.n = o.n ?? 18; this.life = o.life ?? 0.16; const g = new THREE.BufferGeometry(); this.pos = new Float32Array(this.n * 2 * 3); this.al = new Float32Array(this.n * 2); g.setAttribute('position', new THREE.BufferAttribute(this.pos, 3)); g.setAttribute('al', new THREE.BufferAttribute(this.al, 1));
    const idx = []; for (let i = 0; i < this.n - 1; i++) { const a = i * 2; idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); } g.setIndex(idx);
    this.u = { uCol: { value: new THREE.Color(...(o.color || [0.75, 0.85, 1.0])) }, uGain: { value: o.gain ?? 1 } };
    const m = new THREE.ShaderMaterial({ uniforms: this.u, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide, vertexShader: `attribute float al; varying float vA; varying float vS; void main(){ vA = al; vS = float(gl_VertexID % 2); gl_Position = projectionMatrix*modelViewMatrix*vec4(position, 1.); }`,
      fragmentShader: `uniform vec3 uCol; uniform float uGain; varying float vA; varying float vS; void main(){ float e = pow(vS, 2.5); gl_FragColor = vec4(uCol*(0.15 + e*2.5)*vA*vA*uGain*3., 1.); }`, });
    this.mesh = new THREE.Mesh(g, m); this.mesh.frustumCulled = false; this.mesh.renderOrder = 11; this.hist = []; }
  reset() { this.hist.length = 0; this.al.fill(0); this.mesh.geometry.attributes.al.needsUpdate = true; }
  push(base, tip, t, strength = 1) { this.hist.unshift({ b: base.clone(), p: tip.clone(), t, s: strength }); if (this.hist.length > this.n) this.hist.pop();
    for (let i = 0; i < this.n; i++) { const h = this.hist[Math.min(i, this.hist.length - 1)]; const a = clamp(1 - (t - h.t) / this.life) * h.s * (i < this.hist.length ? 1 : 0); this.pos.set([h.b.x, h.b.y, h.b.z, h.p.x, h.p.y, h.p.z], i * 6); this.al[i * 2] = a; this.al[i * 2 + 1] = a; }
    this.mesh.geometry.attributes.position.needsUpdate = true; this.mesh.geometry.attributes.al.needsUpdate = true; }
}
/** flickering fire light */
export function fireLight(pos, o = {}) { const l = new THREE.PointLight(new THREE.Color(...(o.color || [1.0, 0.45, 0.14])), o.intensity ?? 60, o.distance ?? 30, 1.6); l.position.copy(pos); l.userData = { base: o.intensity ?? 60, seed: o.seed ?? Math.abs(pos.x * 3.1 + pos.z * 1.7), p0: pos.clone() };
  l.userData.tick = (t) => { const k = 0.72 + 0.28 * wob(t, l.userData.seed, 7) + 0.12 * wob(t, l.userData.seed + 5, 19); l.intensity = l.userData.base * k; l.position.set(l.userData.p0.x + wob(t, l.userData.seed + 1, 5) * 0.12, l.userData.p0.y + wob(t, l.userData.seed + 2, 6) * 0.1, l.userData.p0.z + wob(t, l.userData.seed + 3, 5) * 0.12); }; return l; }
/** keep camera-following effects centred */
export function follow(meshes, camera) { for (const m of meshes) if (m.userData.u && m.userData.u.uCam) m.userData.u.uCam.value.copy(camera.position); }
/** a volley of (burning) arrows on ballistic arcs. shots: [{from:V3, to:V3, t0, dur, arc}] ; update(t) each frame */
export class Volley {
  constructor(geo, shots, o = {}) { this.shots = shots; const n = shots.length; this.mesh = new THREE.InstancedMesh(geo, new THREE.MeshLambertMaterial({ color: 0x1a1410 }), n); this.mesh.frustumCulled = false;
    this.glow = new THREE.InstancedMesh(new THREE.SphereGeometry(o.glow ?? 0.09, 6, 5), new THREE.MeshBasicMaterial({ color: new THREE.Color(...(o.color || [9, 3.2, 0.6])) }), n); this.glow.frustumCulled = false; this.group = new THREE.Group(); this.group.add(this.mesh, this.glow); this.fire = o.fire !== false; this.tail = o.tail ?? 3; }
  pos(s, k, out) { out.lerpVectors(s.from, s.to, k); out.y += (s.arc ?? 12) * 4 * k * (1 - k); return out; }
  update(t) { const m = new THREE.Matrix4(), p = new THREE.Vector3(), p2 = new THREE.Vector3(), q = new THREE.Quaternion(), Z = new THREE.Vector3(0, 0, 1), sc = new THREE.Vector3(1, 1, 1), zero = new THREE.Matrix4().makeScale(0, 0, 0);
    this.shots.forEach((s, i) => { const k = (t - s.t0) / s.dur; if (k < 0 || (k > 1 && !s.stick)) { this.mesh.setMatrixAt(i, zero); this.glow.setMatrixAt(i, zero); return; } const kk = Math.min(1, k); this.pos(s, kk, p); this.pos(s, Math.min(1, kk + 0.01), p2); const d = p2.sub(p); if (d.lengthSq() < 1e-10) { this.pos(s, kk - 0.01, p2); d.copy(p).sub(p2); } q.setFromUnitVectors(Z, d.normalize()); m.compose(p, q, sc); this.mesh.setMatrixAt(i, m);
      if (this.fire && k <= 1.02) { const g = 1 + 0.4 * Math.sin(t * 40 + i); m.compose(p.clone().addScaledVector(d, 0.4), q, new THREE.Vector3(g, g, g * this.tail)); this.glow.setMatrixAt(i, m); } else this.glow.setMatrixAt(i, zero); });
    this.mesh.instanceMatrix.needsUpdate = true; this.glow.instanceMatrix.needsUpdate = true; }
}
