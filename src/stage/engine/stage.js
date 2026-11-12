// Stage: scene + camera + sun/sky rig shared by every shot, with helpers for lighting moods and camera moves.
import { THREE, V3, wob, lerp, clamp, defaultLook } from './core.js';
import { GU } from './mat.js';
import { Sky } from './world.js';
export class Stage {
  constructor(film, o = {}) {
    this.film = film; const scene = this.scene = new THREE.Scene(); this.camera = new THREE.PerspectiveCamera(35, o.aspect || 16 / 9, 0.08, 6000);
    this.sky = new Sky(o.sky || {}); this.sky.mesh.scale.setScalar(3000); scene.add(this.sky.mesh);
    this.sun = new THREE.DirectionalLight(0xffffff, 3); this.sun.castShadow = true; const SR = o.shadowRes || 4096; this.sun.shadow.mapSize.set(SR, SR); this.sun.shadow.bias = -0.0005; this.sun.shadow.normalBias = 0.02; scene.add(this.sun, this.sun.target);
    this.hemi = new THREE.HemisphereLight(0x8090b0, 0x403830, 1); scene.add(this.hemi);
    this.rim = new THREE.DirectionalLight(0xffffff, 0); scene.add(this.rim, this.rim.target);
    this.L = film.look = defaultLook(); this.sunDir = V3(0.3, 0.6, 0.5).normalize(); this.focus = V3(); this.shadowSize = 40; this.updaters = [];
  }
  /** direction TO the sun, colour (linear), intensity */
  setSun(dir, color, intensity, sky = true) { this.sunDir.copy(dir).normalize(); this.sun.color.setRGB(...color); this.sun.intensity = intensity; GU.uKeyDir.value.copy(this.sunDir); GU.uKeyCol.value.setRGB(...color).multiplyScalar(Math.min(1.5, intensity / 3)); GU.uToonSun.value.setRGB(...color).multiplyScalar(intensity); if (sky) this.sky.u.uSunDir.value.copy(this.sunDir); this.L.fog.sunDir.copy(this.sunDir); return this; }
  setHemi(sky, ground, intensity) { this.hemi.color.setRGB(...sky); this.hemi.groundColor.setRGB(...ground); this.hemi.intensity = intensity; return this; }
  setRim(dir, color, intensity) { this.rimDir = V3(...dir).normalize(); this.rim.color.setRGB(...color); this.rim.intensity = intensity; return this; }
  /** shadow box of `size` metres centred on a point */
  shadow(center, size = 40, depth = 300) { this.focus.copy(center); this.shadowSize = size; const s = this.sun; s.target.position.copy(center); s.position.copy(center).addScaledVector(this.sunDir, depth * 0.5); const c = s.shadow.camera; c.left = -size / 2; c.right = size / 2; c.top = size / 2; c.bottom = -size / 2; c.near = 1; c.far = depth; c.updateProjectionMatrix(); s.shadow.normalBias = 0.0008 * size + 0.004; s.shadow.bias = -0.00002 * size - 0.0002;
    if (this.rimDir) { this.rim.target.position.copy(center); this.rim.position.copy(center).addScaledVector(this.rimDir, 50); } return this; }
  fog(o) { Object.assign(this.L.fog, o); return this; }
  /** camera: position, look-at target, fov; shake = {amp (m), rot (rad), speed, t} */
  cam(pos, target, fov = 35, roll = 0, shake = null) { const c = this.camera; c.position.copy(pos); if (shake) { const t = shake.t * (shake.speed || 1); c.position.x += wob(t, 1) * shake.amp; c.position.y += wob(t, 2) * shake.amp; c.position.z += wob(t, 3) * shake.amp; }
    c.up.set(0, 1, 0); c.lookAt(target); if (shake && shake.rot) { c.rotateX(wob(shake.t * (shake.speed || 1), 4) * shake.rot); c.rotateY(wob(shake.t * (shake.speed || 1), 5) * shake.rot); } if (roll) c.rotateZ(roll); c.fov = fov; c.updateProjectionMatrix(); this.sky.mesh.position.copy(c.position); return this; }
  dof(focus, range = 0.35, max = 12) { const d = this.L.dof; if (focus === null) { d.on = false; return this; } d.on = true; d.focus = typeof focus === 'number' ? focus : this.camera.position.distanceTo(focus); d.range = range; d.max = max; return this; }
  tick(t, dt) { GU.uTime.value = t; for (const u of this.updaters) u(t, dt); }
}
