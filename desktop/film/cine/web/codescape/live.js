// LIVE ACTION IN A CODED WORLD (owner's idea, 23 Nov 2026; built 27 Nov 2026): a person filmed in front of a plain wall
// (green, yellow, any one colour) stands INSIDE a CodeScape scene. The wall is keyed out on the graphics card; the person is a
// card that stands on the floor at a mark, turns to the lens, is hidden by what is in front of it, throws a shadow of its own
// outline on the floor and the walls, and goes through the film's own lens blur and grade, so it sits in the picture.
//   in the app:   cine.actor {film, video, name}     makes <name>.mp4 (every frame a key frame) + <name>.actor.json in the film
//   in film.js:   import { liveActor } from './live.js';
//                 build:  c.me = await liveActor(E, 'me', { at: [0, 0], height: 1.7 });
//                 frame:  c.me.at(x, z); c.me.play(t - 2.0);        // where the person stands; which moment of the take is shown
// A render waits for the exact frame of the take before it draws each picture (E.waits); Cine Studio shows the nearest one.
import { THREE, V3, clamp } from './engine.js';

const KEYS = { green: [0.16, 0.62, 0.22], yellow: [0.86, 0.76, 0.2], blue: [0.1, 0.25, 0.75] };
const COMMON = `uniform sampler2D map; uniform vec3 key; uniform float tol, soft, spill; uniform vec4 crop; varying vec2 vUv;
  vec3 lin(vec3 c){ return pow(c, vec3(2.2)); }
  vec2 chroma(vec3 c){ float y = dot(c, vec3(0.299, 0.587, 0.114)); return vec2(c.b - y, c.r - y) / (0.35 + y); }   /* colour without brightness: a shadow on the wall is still wall */
  float keyAlpha(vec3 c){ if (vUv.x < crop.x || vUv.x > 1.0 - crop.z || vUv.y < crop.w || vUv.y > 1.0 - crop.y) return 0.0; return smoothstep(tol, tol + soft, distance(chroma(c), chroma(key))); }`;
const VERT = `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`;

/**
 * A filmed person in the scene. `name`: the take made by cine.actor (<name>.mp4 and <name>.actor.json in the film's folder).
 * o: { at: [x, z], height: metres the picture's full height stands for (default: the take's, or 1.9), yaw: radians, or left out to
 *      turn to the lens, key: 'green' | 'yellow' | [r, g, b] (default: what cine.actor found), tol, soft: how much like the wall
 *      is wall, spill: how much of the wall's colour is taken off the person, crop: [left, top, right, bottom] shares cut away,
 *      feet: the share of the picture's height that lies under the feet (0.04 if the floor shows a little below them),
 *      gain: brightness, tint: [r, g, b], shadow: false for none, from: seconds into the take that play(0) shows }
 * → { group, at(x, z, yaw?), play(t), seconds, fps, height, width, ready }
 */
export async function liveActor(E, name, o = {}) {
  let meta = {}; try { const r = await fetch(new URL(name + '.actor.json', location.href)); if (r.ok) meta = await r.json(); } catch (e) { /* no take yet: an empty card */ }
  const hasVideo = typeof document !== 'undefined' && !!document.createElement && meta.video;
  const aspect = (meta.w && meta.h) ? meta.w / meta.h : 9 / 16, H = o.height ?? meta.height ?? 1.9, W = H * aspect, fps = meta.fps || 30, seconds = meta.seconds || 0;
  let video = null, tex = null;
  if (hasVideo) { video = document.createElement('video'); video.muted = true; video.playsInline = true; video.preload = 'auto'; video.crossOrigin = 'anonymous'; video.src = new URL(meta.video, location.href).href;
    await new Promise(res => { let done = false; const ok = () => { if (!done) { done = true; res(); } }; video.addEventListener('loadeddata', ok, { once: true }); video.addEventListener('error', ok, { once: true }); setTimeout(ok, 8000); video.load(); });
    tex = new THREE.VideoTexture(video); tex.colorSpace = THREE.NoColorSpace; tex.minFilter = THREE.LinearFilter; tex.magFilter = THREE.LinearFilter; tex.generateMipmaps = false; }
  const keyCol = Array.isArray(o.key) ? o.key : KEYS[o.key] || meta.key || KEYS.green;
  const U = { map: { value: tex }, key: { value: new V3(...keyCol) }, tol: { value: o.tol ?? 0.16 }, soft: { value: o.soft ?? 0.12 }, spill: { value: o.spill ?? 0.8 }, crop: { value: new THREE.Vector4(...(o.crop || [0, 0, 0, 0])) }, gain: { value: o.gain ?? 1 }, tint: { value: new V3(...(o.tint || [1, 1, 1])) } };
  const geo = new THREE.PlaneGeometry(W, H); geo.translate(0, H / 2 - (o.feet ?? meta.feet ?? 0) * H, 0);   /* the feet at the card's own origin (feet: the share of the picture that lies under them) */
  // the person: the wall keyed out, the wall's colour taken off skin and cloth, linear light for the film's lens and grade
  const mat = new THREE.ShaderMaterial({ uniforms: U, vertexShader: VERT, transparent: true, depthWrite: false, side: THREE.DoubleSide,
    fragmentShader: `uniform float gain; uniform vec3 tint; ${COMMON}
      void main(){ vec3 c = texture2D(map, vUv).rgb; float a = keyAlpha(c); if (a < 0.01) discard;
        vec3 k = normalize(key + 1e-4); float amt = dot(c, k); vec3 rest = c - k * amt; float lim = length(rest) * 1.15 + 0.02; c = rest + k * min(amt, mix(amt, lim, spill * (1.0 - 0.6 * a * a)));   /* spill: no more of the wall's colour than the person's own colours carry */
        gl_FragColor = vec4(lin(c) * gain * tint, a); }` });
  // the same outline into the depth (so the lens blur treats the person as standing where they stand, and things behind are hidden)
  const depthMat = new THREE.ShaderMaterial({ uniforms: U, vertexShader: VERT, colorWrite: false, side: THREE.DoubleSide, fragmentShader: `${COMMON} void main(){ if (keyAlpha(texture2D(map, vUv).rgb) < 0.6) discard; gl_FragColor = vec4(0.0); }` });
  // and into a light's shadow map
  const shadowMat = new THREE.ShaderMaterial({ uniforms: U, vertexShader: VERT, side: THREE.DoubleSide, fragmentShader: `#include <packing>\n${COMMON} void main(){ if (keyAlpha(texture2D(map, vUv).rgb) < 0.5) discard; gl_FragColor = packDepthToRGBA(gl_FragCoord.z); }` });
  const group = new THREE.Group(), card = new THREE.Mesh(geo, mat), solid = new THREE.Mesh(geo, depthMat); card.renderOrder = 2; solid.renderOrder = -1; card.frustumCulled = solid.frustumCulled = false; group.add(solid, card);
  let caster = null; if (o.shadow !== false) { caster = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ colorWrite: false, depthWrite: false, side: THREE.DoubleSide }));   /* both sides, or the shadow pass (which draws back faces of a one-sided mesh) draws nothing of a card that faces the light */ caster.castShadow = true; caster.customDepthMaterial = shadowMat; caster.frustumCulled = false; group.add(caster); }
  E.scene.add(group);
  const A = { group, card, video, seconds, fps, height: H, width: W, ready: !!tex, yaw: o.yaw, want: 0, shown: -1, from: o.from || 0 };
  A.at = (x, z, yaw) => { group.position.set(x, o.y || 0, z); if (yaw !== undefined) A.yaw = yaw; return A; };
  A.play = t => { A.want = clamp((t || 0) + A.from, 0, Math.max(0, seconds - 1 / fps)); return A; };
  if (o.at) A.at(o.at[0], o.at[1]);
  // each picture: turn to the lens (or keep the yaw given), turn the shadow's card to the key light, bring the take to its moment
  const tmp = new V3();
  const face = () => { if (!E.cam) return; if (A.yaw === undefined) { tmp.copy(E.cam.position).sub(group.position); group.rotation.y = Math.atan2(tmp.x, tmp.z); } else group.rotation.y = A.yaw;
    if (caster) { let L = null; E.scene.traverse(q => { if (!L && q.isLight && q.castShadow) L = q; }); if (L) { tmp.setFromMatrixPosition(L.matrixWorld).sub(group.position); caster.rotation.y = Math.atan2(tmp.x, tmp.z) - group.rotation.y; } } };
  const seek = (t, soft) => { face(); if (!video) return Promise.resolve(); const f = Math.round(A.want * fps); if (f === A.shown && !soft) return Promise.resolve(); const at = (f + 0.5) / fps;
    if (soft) { if (Math.abs(video.currentTime - at) > 0.5 / fps) { try { video.currentTime = at; } catch (e) { /* not ready */ } } tex.needsUpdate = true; return Promise.resolve(); }
    return new Promise(res => { let done = false; const ok = () => { if (done) return; done = true; A.shown = f; tex.needsUpdate = true; res(); }; video.addEventListener('seeked', ok, { once: true }); setTimeout(ok, 1500); try { video.currentTime = at; } catch (e) { ok(); } }); };
  (E.waits = E.waits || []).push(seek); A.seek = seek; face();
  return A;
}
