// The mountain: a thousand steps, a gate, a courtyard and hall, a waterfall. Seasons change through the training years.
import { THREE, V3, fbm, ridged, smooth, clamp, lerp, mulberry, noiseTex } from './core.js';
import * as W from './world.js';
import { GU } from './mat.js';
export const TOP = 30, STEPS = 158, RISE = 0.19, RUN = 0.34;   // courtyard height; stairs climb from z=0 to z≈54
export function templeHeight(x, z) {
  const stairTop = STEPS * RUN; let base = z < 0 ? z * 0.35 : z < stairTop ? z / stairTop * TOP : TOP;
  const plat = smooth(34, 26, Math.abs(x)) * smooth(stairTop - 2, stairTop + 4, z) * smooth(128, 116, z);
  let h = base + (fbm(x * 0.03, z * 0.03, 2.2, 4) - 0.5) * 10 * (1 - plat) * smooth(3, 9, Math.abs(x));
  h += smooth(28, 70, Math.abs(x)) * (x > 0 ? 34 + ridged(x * 0.012, z * 0.012, 5.5, 4) * 30 : -22 - (fbm(x * 0.02, z * 0.02, 1.1, 3)) * 26) * (1 - 0.0);
  h += smooth(116, 190, z) * (40 + ridged(x * 0.006, z * 0.006, 9.9, 5) * 140);
  h -= smooth(-10, -120, z) * 60 * 0.6;
  if (plat > 0) h = lerp(h, TOP, plat);
  return h;
}
export function buildTemple(st, o = {}) {
  const g = new THREE.Group(); st.scene.add(g); const r = mulberry(91); const season = o.season || 'winter'; const snow = season === 'winter' ? (o.snow ?? 0.85) : 0;
  const stairTop = STEPS * RUN;
  const ter = W.makeTerrain({ size: 520, seg: 240, center: [0, 70], height: templeHeight, snowLine: season === 'winter' ? -999 : 62, snowAll: snow, uvScale: 0.22, rockSlope: 0.4, castShadow: true, tint: season === 'autumn' ? [1.05, 0.9, 0.72] : [0.92, 0.95, 0.92],
    mask: (x, z, h) => [clamp(smooth(30, 22, Math.abs(x)) * smooth(stairTop, stairTop + 6, z) * smooth(124, 112, z) * 0.85 + smooth(3.2, 2.0, Math.abs(x)) * smooth(-40, 0, z)), 0, 0, 0] }); g.add(ter);
  g.add(W.makeTerrain({ size: 6400, seg: 140, center: [0, 70], snowLine: season === 'winter' ? -999 : 80, snowAll: snow, uvScale: 0.05, rockSlope: 0.3, height: (x, z) => { const d = Math.hypot(x, z - 70); return d < 250 ? -120 : templeHeight(x, z) * 0.8 + smooth(500, 2600, d) * 420 * ridged(x * 0.001, z * 0.001, 2.2, 5) - 30; } }));
  const stairs = W.stairs(STEPS, 4.4, RISE, RUN); g.add(stairs);
  const gate = W.gate(5.6, 5.6); gate.position.set(0, TOP, stairTop + 2.5); g.add(gate);
  const hall = W.temple(16, 9); hall.position.set(0, TOP - 0.05, 108); g.add(hall);
  const lanterns = []; for (const s of [-1, 1]) for (let i = 0; i < 4; i++) { const l = W.lantern(o.lit ?? 0); l.position.set(s * 4.2, TOP, stairTop + 9 + i * 9); g.add(l); lanterns.push(l); }
  for (const s of [-1, 1]) for (let i = 0; i < 7; i++) { const l = W.lantern(o.lit ?? 0); const z = 4 + i * 8; l.position.set(s * 3.4, templeHeight(s * 3.4, z), z); g.add(l); lanterns.push(l); }
  // trees: the courtyard tree changes with the seasons
  const kind = { winter: 'dead', spring: 'blossom', summer: 'leaf', autumn: 'autumn' }[season];
  const big = W.forest([{ x: -13, y: TOP - 0.2, z: 84, s: 1.7, rot: 0.8 }, { x: 15, y: TOP - 0.2, z: 71, s: 1.3, rot: 2.1 }], kind, 33, { variants: 1, height: 8, radius: 4.4, cards: 96 }); g.add(big);
  const slope = (x, z, y) => !(Math.abs(x) < 6 && z < stairTop + 4) && !(Math.abs(x) < 30 && z > stairTop && z < 126) && y > -30;
  g.add(W.forest(W.scatter(60, 92, [-70, 70, -30, 150], templeHeight, (x, z, y) => slope(x, z, y) && Math.abs(x) < 60), 'pine', 8, { far: true, shadow: true }));
  g.add(W.forest(W.scatter(700, 93, [-260, 260, -150, 300], templeHeight, (x, z, y) => slope(x, z, y) && (Math.abs(x) > 45 || z < -20 || z > 135) && y < 150), 'pine', 5, { shadow: false, far: true }));
  if (season === 'autumn' || season === 'spring') g.add(W.forest(W.scatter(160, 94, [-240, 240, -140, 280], templeHeight, (x, z, y) => slope(x, z, y) && (Math.abs(x) > 40 || z < -20) && y < 120), season === 'autumn' ? 'autumn' : 'leaf', 7, { shadow: false, far: true }));
  g.add(W.rocks(W.scatter(70, 95, [-60, 60, -20, 130], templeHeight, (x, z, y) => slope(x, z, y) && Math.abs(x) > 5).map(s => ({ ...s, s: s.s * 1.5 })), 9, { snow }));
  // waterfall on the east cliff, with a pool
  const wf = new THREE.Group(); wf.position.set(31, TOP, 86); g.add(wf);
  const wg = new THREE.PlaneGeometry(7, 34, 8, 30); wg.translate(0, 17, 0); const wu = { uTime: GU.uTime, tNoise: { value: noiseTex() }, uOpen: { value: new THREE.Vector4(0, 0, 0, 0) } };
  const wm = new THREE.ShaderMaterial({ uniforms: wu, transparent: true, depthWrite: false, side: THREE.DoubleSide, vertexShader: `varying vec2 vUv; varying vec3 vP; uniform float uTime; void main(){ vUv = uv; vec3 p = position; p.z += sin(p.y*0.9 + uTime*3.)*0.12 + (1. - uv.y)*1.6; vP = p; gl_Position = projectionMatrix*modelViewMatrix*vec4(p, 1.); }`,
    fragmentShader: `uniform sampler2D tNoise; uniform float uTime; uniform vec4 uOpen; varying vec2 vUv; varying vec3 vP; void main(){ float n = texture2D(tNoise, vec2(vUv.x*1.3, vUv.y*0.5 + uTime*0.55)).r; float n2 = texture2D(tNoise, vec2(vUv.x*3.1 + 0.3, vUv.y*1.2 + uTime*0.9)).g; float edge = smoothstep(0., 0.14, vUv.x)*smoothstep(1., 0.86, vUv.x); float a = edge*(0.5 + 0.5*n)*smoothstep(1.0, 0.92, vUv.y);
      float cut = uOpen.w*smoothstep(uOpen.z, uOpen.z*0.5, abs(vP.x - uOpen.x))*smoothstep(1.3, 0.3, abs(vP.y - uOpen.y)*0.12); a *= 1. - cut; vec3 c = mix(vec3(0.55, 0.68, 0.8), vec3(1.0), smoothstep(0.45, 0.8, n*0.6 + n2*0.5)); gl_FragColor = vec4(c*1.15, a*0.9); }` });
  const fall = new THREE.Mesh(wg, wm); fall.renderOrder = 3; fall.rotation.y = -Math.PI / 2; wf.add(fall); wf.userData.u = wu;
  const pool = W.makeWater(13, 14, { deep: [0.04, 0.1, 0.13], sky: [0.6, 0.7, 0.8], flow: [0.0, 0.5] }); pool.position.set(-4.5, 0.06, 0); wf.add(pool);
  const cliff = W.rocks([{ x: 34.5, y: TOP + 14, z: 86, s: 11 }, { x: 35, y: TOP + 6, z: 78, s: 8 }, { x: 35, y: TOP + 7, z: 95, s: 9 }, { x: 37, y: TOP + 30, z: 88, s: 12 }, { x: 28, y: TOP + 0.3, z: 79.5, s: 1.6 }, { x: 27, y: TOP + 0.2, z: 92.5, s: 1.9 }, { x: 24, y: TOP + 0.1, z: 85, s: 0.8 }], 12, { snow }); g.add(cliff);
  return { group: g, stairTop, gate, hall, lanterns, waterfall: wf, big };
}
