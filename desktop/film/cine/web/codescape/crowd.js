// crowd.js — many children at once for CodeScape films: a school assembly, a playground. Each child is a light figure (a few
// thousand points) drawn with instancing, so hundreds cost little: boys in shirt and trousers, girls in shirt and pinafore with
// plaits or a ponytail; arms down or hands folded for the prayer. Skin and height vary per child; they sway as people standing do,
// and they can walk (legs and arms swing in the vertex shader, the body moves on the CPU). Shadows are real.
import { THREE, V3, clamp, lerp, rng } from './engine.js';
import { T, clock } from './nature.js';

const lin = h => { const c = new THREE.Color(h); return [c.r, c.g, c.b]; };   /* palette in sRGB (as the full-rig children are), stored linear as vertex colours are */
const SHIRT = lin('#e9e8e2'), NAVY = lin('#1d2a52'), SHOE = lin('#121010'), SOCK = lin('#ecece8'), HAIR = lin('#17100c'), TIE = lin('#8c2430'), BELT = lin('#231812');
const SKINS = ['#a0745a', '#c29c80', '#a87e64', '#b48a6e', '#ba8f72', '#8e6248', '#b8916f'];

/** parts → one geometry with colour, a skin flag, and which limb a point swings with (leg -1/+1 about the hips, arm -1/+1 about the shoulders) */
function figure(kind, pose) { const parts = [];
  const add = (g, col, m, o = {}) => { g = g.index ? g.toNonIndexed() : g; g.applyMatrix4(m); parts.push({ g, col, skin: o.skin ? 1 : 0, leg: o.leg || 0, arm: o.arm || 0 }); };
  const M = (x, y, z, rx = 0, ry = 0, rz = 0, sx = 1, sy = 1, sz = 1) => new THREE.Matrix4().compose(new V3(x, y, z), new THREE.Quaternion().setFromEuler(new THREE.Euler(rx, ry, rz)), new V3(sx, sy, sz));
  const cyl = (r0, r1, h, s = 10) => { const g = new THREE.CylinderGeometry(r1, r0, h, s, 1, false); g.translate(0, h / 2, 0); return g; };
  const girl = kind === 'girl';
  for (const s of [-1, 1]) { const leg = s;
    add(new THREE.BoxGeometry(0.085, 0.06, 0.2), SHOE, M(s * 0.07, 0.03, 0.03), { leg });
    if (girl) { add(cyl(0.038, 0.042, 0.3), SOCK, M(s * 0.07, 0.05, 0), { leg }); add(cyl(0.042, 0.05, 0.2), [1, 1, 1], M(s * 0.07, 0.34, 0), { leg, skin: 1 }); }
    else add(cyl(0.05, 0.062, 0.68), NAVY, M(s * 0.075, 0.05, 0), { leg }); }
  if (girl) { add(cyl(0.21, 0.15, 0.36, 16), NAVY, M(0, 0.5, 0, 0, 0, 0, 1, 1, 0.78)); add(cyl(0.15, 0.135, 0.06, 14), NAVY, M(0, 0.86, 0, 0, 0, 0, 1, 1, 0.75)); }
  else add(cyl(0.135, 0.135, 0.05, 14), BELT, M(0, 0.7, 0, 0, 0, 0, 1, 1, 0.72));
  add(cyl(0.135, 0.15, 0.4, 14), SHIRT, M(0, 0.72, 0, 0, 0, 0, 1, 1, 0.68));                     /* the shirt's body */
  { const sh = new THREE.SphereGeometry(0.15, 14, 8, 0, Math.PI * 2, 0, Math.PI / 2); add(sh, SHIRT, M(0, 1.1, 0, 0, 0, 0, 1.12, 0.45, 0.7)); }
  add(cyl(0.034, 0.036, 0.09), [1, 1, 1], M(0, 1.14, 0), { skin: 1 });                          /* neck */
  { const hd = new THREE.SphereGeometry(0.1, 16, 12); add(hd, [1, 1, 1], M(0, 1.29, 0.005, 0, 0, 0, 0.92, 1.05, 0.98), { skin: 1 });
    const hr = new THREE.SphereGeometry(0.106, 16, 10, 0, Math.PI * 2, 0, Math.PI * 0.55); add(hr, HAIR, M(0, 1.3, -0.012, -0.25, 0, 0, 0.95, 1.04, 1.02)); }
  for (const s of [-1, 1]) { add(new THREE.SphereGeometry(0.026, 8, 6), [1, 1, 1], M(s * 0.091, 1.285, 0.0, 0, 0, 0, 0.45, 1, 0.8), { skin: 1 });          /* ears */
    add(new THREE.SphereGeometry(0.011, 8, 6), lin('#1a120e'), M(s * 0.031, 1.3, 0.089, 0, 0, 0, 1.2, 0.8, 0.6));                                   /* eyes */
    add(new THREE.BoxGeometry(0.03, 0.006, 0.01), HAIR, M(s * 0.032, 1.322, 0.09, 0, 0, -s * 0.1)); }                                                     /* brows */
  add(new THREE.SphereGeometry(0.014, 8, 6), [1, 1, 1], M(0, 1.275, 0.097, 0, 0, 0, 0.9, 1.1, 0.9), { skin: 1 });                                         /* nose */
  add(new THREE.BoxGeometry(0.026, 0.005, 0.01), lin('#8a4c46'), M(0, 1.243, 0.088));                                                                  /* mouth */
  if (girl) { if (pose.hair === 'plaits') for (const s of [-1, 1]) add(cyl(0.022, 0.028, 0.26, 8), HAIR, M(s * 0.07, 1.0, -0.06, 0.15, 0, s * 0.12));
    else add(cyl(0.02, 0.035, 0.22, 8), HAIR, M(0, 1.06, -0.1, 0.35, 0, 0)); }
  if (!girl || true) add(new THREE.BoxGeometry(0.035, 0.2, 0.008), TIE, M(0, 0.93, 0.105));
  for (const s of [-1, 1]) { const arm = s;
    if (pose.fold) { add(cyl(0.04, 0.043, 0.2), SHIRT, M(s * 0.175, 0.9, 0.0, 0.25, 0, s * 0.08), { arm }); add(cyl(0.028, 0.034, 0.2), [1, 1, 1], M(s * 0.17, 0.92, 0.05, -1.25, 0, -s * 0.6), { skin: 1, arm });
      add(new THREE.BoxGeometry(0.03, 0.09, 0.06), [1, 1, 1], M(s * 0.012, 1.02, 0.17, 0.2, 0, 0), { skin: 1, arm }); }
    else { add(cyl(0.04, 0.043, 0.2), SHIRT, M(s * 0.175, 0.9, 0.0, 0, 0, s * 0.06), { arm }); add(cyl(0.028, 0.034, 0.25), [1, 1, 1], M(s * 0.188, 0.66, 0.0, 0, 0, s * 0.04), { skin: 1, arm });
      add(new THREE.SphereGeometry(0.034, 8, 6), [1, 1, 1], M(s * 0.19, 0.63, 0.01, 0, 0, 0, 0.8, 1.2, 0.9), { skin: 1, arm }); } }
  let n = 0; for (const p of parts) n += p.g.attributes.position.count;
  const pos = new Float32Array(n * 3), nor = new Float32Array(n * 3), col = new Float32Array(n * 3), skin = new Float32Array(n), limb = new Float32Array(n * 2); let k = 0;
  for (const p of parts) { const P = p.g.attributes.position, N = p.g.attributes.normal; for (let i = 0; i < P.count; i++, k++) { pos.set([P.getX(i), P.getY(i), P.getZ(i)], k * 3); nor.set([N.getX(i), N.getY(i), N.getZ(i)], k * 3); col.set(p.col, k * 3); skin[k] = p.skin; limb[k * 2] = p.leg; limb[k * 2 + 1] = p.arm; } }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('normal', new THREE.BufferAttribute(nor, 3)); g.setAttribute('color', new THREE.BufferAttribute(col, 3)); g.setAttribute('aSkin', new THREE.BufferAttribute(skin, 1)); g.setAttribute('aLimb', new THREE.BufferAttribute(limb, 2)); g.computeBoundingSphere(); return g; }

function material() {
  const m = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.78 });
  const vs = sh => { sh.uniforms.uT = T; sh.vertexShader = 'uniform float uT; attribute float aSkin; attribute vec2 aLimb; attribute vec2 aWalk;\n' + sh.vertexShader
      .replace('#include <color_vertex>', `#include <color_vertex>
        #ifdef USE_INSTANCING_COLOR
          vColor.xyz = color.xyz * mix(vec3(1.0), instanceColor.xyz, aSkin);
        #endif`)
      .replace('#include <begin_vertex>', `#include <begin_vertex>
        { float sw = sin(uT * 7.6 + aWalk.y) * aWalk.x;
          if (aLimb.x != 0.0) { float a = aLimb.x * 0.36 * sw; vec3 q = transformed - vec3(0.0, 0.72, 0.0); transformed = vec3(q.x, q.y * cos(a) - q.z * sin(a), q.y * sin(a) + q.z * cos(a)) + vec3(0.0, 0.72, 0.0); }
          if (aLimb.y != 0.0) { float a = -aLimb.y * 0.35 * sw; vec3 q = transformed - vec3(0.0, 1.08, 0.0); transformed = vec3(q.x, q.y * cos(a) - q.z * sin(a), q.y * sin(a) + q.z * cos(a)) + vec3(0.0, 1.08, 0.0); } }`); };
  m.onBeforeCompile = vs; const dm = new THREE.MeshDepthMaterial({ depthPacking: THREE.RGBADepthPacking }); dm.onBeforeCompile = sh => { sh.uniforms.uT = T; sh.vertexShader = 'uniform float uT; attribute vec2 aLimb; attribute vec2 aWalk;\n' + sh.vertexShader.replace('#include <begin_vertex>', `#include <begin_vertex>
        { float sw = sin(uT * 7.6 + aWalk.y) * aWalk.x;
          if (aLimb.x != 0.0) { float a = aLimb.x * 0.36 * sw; vec3 q = transformed - vec3(0.0, 0.72, 0.0); transformed = vec3(q.x, q.y * cos(a) - q.z * sin(a), q.y * sin(a) + q.z * cos(a)) + vec3(0.0, 0.72, 0.0); }
          if (aLimb.y != 0.0) { float a = -aLimb.y * 0.35 * sw; vec3 q = transformed - vec3(0.0, 1.08, 0.0); transformed = vec3(q.x, q.y * cos(a) - q.z * sin(a), q.y * sin(a) + q.z * cos(a)) + vec3(0.0, 1.08, 0.0); } }`); };
  return { m, dm }; }

/**
 * An assembly: o.cols × o.rows children starting at `at` [x, z] (front-left corner), rows going back (−z), facing +z.
 * Columns alternate boys and girls (as classes stand), the middle o.fold share with hands folded. Returns { meshes, kids, update(t) }:
 * kids[i] = { p0 (place), yaw, h (height), walk: null | { dir, t0, speed } } — set walk to send a child off; update runs each frame.
 */
export function assembly(E, o = {}) { clock(E); const r = rng(o.seed ?? 31), cols = o.cols ?? 16, rows = o.rows ?? 12, dx = o.dx ?? 0.9, dz = o.dz ?? 0.95, at = o.at || [-(cols - 1) * dx / 2, -4];
  const kinds = [['boy', { fold: false }], ['boy', { fold: true }], ['girl', { fold: false, hair: 'plaits' }], ['girl', { fold: true, hair: 'pony' }]];
  const slots = kinds.map(() => []), kids = [];
  for (let c = 0; c < cols; c++) for (let w = 0; w < rows; w++) { if (o.skip && o.skip(c, w)) continue; const girl = c % 2 === 1, fold = r() < (o.fold ?? 0.6); const vi = (girl ? 2 : 0) + (fold ? 1 : 0);
    const h = (0.87 + 0.13 * w / rows + r() * 0.1) * (o.size ?? 1);   /* the older classes stand further back, and taller */ const kid = { p0: new V3(at[0] + c * dx + (r() - 0.5) * 0.12, 0, at[1] - w * dz + (r() - 0.5) * 0.1), yaw: (r() - 0.5) * 0.12, h, ph: r() * 6.28, walk: null, v: vi, i: slots[vi].length, c, w, skin: SKINS[Math.floor(r() * SKINS.length)] };
    slots[vi].push(kid); kids.push(kid); }
  const { m, dm } = material(), meshes = [];
  kinds.forEach(([kind, pose], vi) => { const n = slots[vi].length; if (!n) return; const g = figure(kind, pose); const walk = new Float32Array(n * 2); for (let i = 0; i < n; i++) walk[i * 2 + 1] = slots[vi][i].ph; g.setAttribute('aWalk', new THREE.InstancedBufferAttribute(walk, 2));
    const im = new THREE.InstancedMesh(g, m, n); im.customDepthMaterial = dm; im.castShadow = true; im.receiveShadow = true; im.frustumCulled = false; const c = new THREE.Color();
    slots[vi].forEach((k, i) => { c.set(k.skin); im.setColorAt(i, c); }); E.scene.add(im); meshes.push({ im, list: slots[vi], walk }); });
  const M4 = new THREE.Matrix4(), Q = new THREE.Quaternion(), S = new V3(), P = new V3(), e = new THREE.Euler();
  const update = t => { for (const { im, list, walk } of meshes) { list.forEach((k, i) => { let yaw = k.yaw, go = 0; P.copy(k.p0);
        if (k.walk && t > k.walk.t0) { const d = Math.min((t - k.walk.t0) * k.walk.speed, k.walk.len ?? 1e9), turn = clamp((t - k.walk.t0) / 0.5, 0, 1); yaw = lerp(k.yaw, k.walk.yaw ?? Math.atan2(k.walk.dir.x, k.walk.dir.z), turn); P.addScaledVector(k.walk.dir, d * turn); go = turn * (k.walk.len && d >= k.walk.len ? 0 : 1); }
        const sway = 0.012 * Math.sin(t * 0.7 + k.ph) * (1 - go); P.y = go * 0.018 * Math.abs(Math.sin(t * 7.6 + k.ph));
        e.set(sway * 0.8, yaw + 0.05 * Math.sin(t * 0.31 + k.ph * 2) * (1 - go), sway, 'YXZ'); Q.setFromEuler(e); S.set(k.h, k.h, k.h); M4.compose(P, Q, S); im.setMatrixAt(i, M4); walk[i * 2] = go; });
      im.instanceMatrix.needsUpdate = true; im.geometry.attributes.aWalk.needsUpdate = true; } };
  E.updaters.push(update); update(0); return { meshes, kids, update }; }
