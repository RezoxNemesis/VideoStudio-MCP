/**
 * Stage environments: where the action happens. Each preset builds its set into the scene and says how high the
 * ground is at any point, so actors stand on it. Time of day sets sky, sun, fill light and haze.
 */
import * as THREE from "three";
import { fbm, mulberry, smooth, V3 } from "./engine/core.js";
import * as W from "./engine/world.js";
import * as FX from "./engine/fx.js";
import { buildBorder, borderHeight, DECK } from "./engine/sc_border.js";
import { buildTemple, templeHeight, TOP } from "./engine/sc_temple.js";
import { buildKiri, kiriHeight } from "./engine/sc_kiri.js";
import type { StageEnv, Vec3 } from "./spec";
import { modelInstance } from "./models";

// The engine is plain JavaScript; its objects are used through this loose type.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Any = any;

export interface EnvRuntime {
  group: THREE.Object3D[];
  /** Ground height at a point. */
  height(x: number, z: number): number;
  /** Where "0,0" is for actors: presets with a special floor (bridge deck, temple court) move the origin there. */
  origin: Vec3;
  followers: THREE.Object3D[];
  dispose(): void;
}

type Rgb = [number, number, number];
interface TimeLook {
  sky: Record<string, unknown>;
  sun: { yaw: number; elev: number; color: Rgb; intensity: number };
  hemi: [Rgb, Rgb, number];
  fog: { density: number; falloff: number; color: Rgb; sunColor: Rgb; sunPow: number; max: number };
  rim?: { color: Rgb; intensity: number };
  exposure: number;
}
export const TIMES: Record<string, TimeLook> = {
  day: {
    sky: { zenith: [0.14, 0.3, 0.6], horizon: [0.75, 0.82, 0.9], sunColor: [18, 15, 11], glow: 0.7, cloud: 0.4, cloudColor: [1, 1, 1], cloudDark: [0.45, 0.5, 0.6], haze: 0.5, stars: 0, moon: 0 },
    sun: { yaw: 215, elev: 48, color: [1.0, 0.93, 0.8], intensity: 4.0 },
    hemi: [[0.6, 0.68, 0.85], [0.35, 0.33, 0.33], 1.1],
    fog: { density: 0.003, falloff: 0.03, color: [0.7, 0.76, 0.84], sunColor: [0.5, 0.4, 0.25], sunPow: 6, max: 0.9 },
    exposure: 1.0,
  },
  dawn: {
    sky: { zenith: [0.22, 0.3, 0.5], horizon: [0.95, 0.72, 0.6], sunColor: [22, 13, 8], glow: 1.3, cloud: 0.45, cloudColor: [1.0, 0.8, 0.7], cloudDark: [0.35, 0.33, 0.45], haze: 0.35, stars: 0, moon: 0 },
    sun: { yaw: 95, elev: 10, color: [1.0, 0.7, 0.5], intensity: 3.6 },
    hemi: [[0.5, 0.55, 0.75], [0.26, 0.22, 0.22], 1.0],
    fog: { density: 0.007, falloff: 0.06, color: [0.6, 0.58, 0.66], sunColor: [0.85, 0.5, 0.3], sunPow: 7, max: 0.9 },
    exposure: 1.0,
  },
  dusk: {
    sky: { zenith: [0.2, 0.27, 0.42], horizon: [0.86, 0.62, 0.45], sunColor: [26, 14, 6], glow: 1.2, cloud: 0.5, cloudColor: [1.0, 0.75, 0.55], cloudDark: [0.3, 0.3, 0.4], haze: 0.25, stars: 0, moon: 0 },
    sun: { yaw: 262, elev: 13, color: [1.0, 0.62, 0.36], intensity: 4.5 },
    hemi: [[0.46, 0.54, 0.76], [0.28, 0.23, 0.22], 1.3],
    fog: { density: 0.0055, falloff: 0.06, color: [0.42, 0.45, 0.55], sunColor: [0.8, 0.4, 0.18], sunPow: 8, max: 0.9 },
    exposure: 1.0,
  },
  night: {
    sky: { zenith: [0.012, 0.018, 0.05], horizon: [0.05, 0.075, 0.14], sunColor: [3, 3.6, 5], glow: 0.35, cloud: 0.3, cloudColor: [0.25, 0.3, 0.45], cloudDark: [0.03, 0.04, 0.08], haze: 0.3, stars: 1, moon: 1 },
    sun: { yaw: 150, elev: 52, color: [0.55, 0.68, 1.0], intensity: 1.5 },
    hemi: [[0.13, 0.18, 0.34], [0.05, 0.05, 0.07], 0.9],
    fog: { density: 0.006, falloff: 0.05, color: [0.05, 0.07, 0.12], sunColor: [0.1, 0.12, 0.2], sunPow: 6, max: 0.9 },
    rim: { color: [0.5, 0.65, 1.0], intensity: 1.2 },
    exposure: 1.15,
  },
  overcast: {
    sky: { zenith: [0.3, 0.34, 0.42], horizon: [0.72, 0.75, 0.8], sunColor: [5, 5, 5.5], glow: 0.5, cloud: 0.85, cloudColor: [0.85, 0.87, 0.92], cloudDark: [0.45, 0.47, 0.55], haze: 0.6, stars: 0, moon: 0 },
    sun: { yaw: 200, elev: 55, color: [0.85, 0.9, 1.0], intensity: 2.4 },
    hemi: [[0.6, 0.68, 0.85], [0.35, 0.33, 0.33], 1.5],
    fog: { density: 0.008, falloff: 0.03, color: [0.7, 0.74, 0.8], sunColor: [0.1, 0.1, 0.1], sunPow: 6, max: 0.95 },
    exposure: 1.0,
  },
};

export const ENV_PRESETS = ["studio", "plain", "meadow", "forest", "desert", "snow", "arena", "bridge", "temple", "village", "void"] as const;

/** Set sky, sun, fill light and haze for the time of day (and the preset's own mood). */
export function applyTime(st: Any, env: StageEnv): void {
  const preset = env.preset;
  const T = TIMES[env.time ?? (preset === "bridge" ? "dusk" : "day")] ?? TIMES.day!;
  const u = st.sky.u;
  const s = T.sky as Record<string, Rgb | number>;
  const setC = (name: string, v: Rgb) => u[name].value.setRGB(v[0], v[1], v[2], THREE.SRGBColorSpace);
  setC("uZen", s.zenith as Rgb);
  setC("uHor", s.horizon as Rgb);
  u.uSunCol.value.setRGB(...(s.sunColor as Rgb));
  u.uGlow.value = s.glow;
  u.uCloud.value = s.cloud;
  setC("uCloudCol", s.cloudColor as Rgb);
  setC("uCloudDark", s.cloudDark as Rgb);
  u.uHaze.value = s.haze;
  u.uStars.value = s.stars;
  u.uMoon.value = s.moon;
  const yaw = ((env.sunYaw ?? T.sun.yaw) * Math.PI) / 180;
  const el = ((env.sunElev ?? T.sun.elev) * Math.PI) / 180;
  const dir = V3(Math.sin(yaw) * Math.cos(el), Math.sin(el), Math.cos(yaw) * Math.cos(el));
  const indoor = preset === "studio" || preset === "void";
  st.setSun(dir, T.sun.color, indoor ? 3.2 : T.sun.intensity);
  st.setHemi(T.hemi[0], T.hemi[1], indoor ? 1.25 : T.hemi[2]);
  if (T.rim) st.setRim([-dir.x, 0.35, -dir.z], T.rim.color, T.rim.intensity);
  else st.setRim([-dir.x, 0.3, -dir.z], [1, 1, 1], indoor ? 1.2 : 0.0);
  const fogK = env.fog ?? 1;
  if (preset === "studio") {
    // an infinity cove: the far floor melts into a backdrop of the same tone
    const c = studioBackdrop(env);
    st.fog({ density: 0.045 * fogK, falloff: 0.0001, base: 0, color: c, sunColor: new THREE.Color(0, 0, 0), sunPow: 8, max: 1, noise: 0 });
  } else st.fog({ density: preset === "void" ? 0 : T.fog.density * fogK, falloff: T.fog.falloff, base: 0, color: new THREE.Color(...T.fog.color), sunColor: new THREE.Color(...T.fog.sunColor), sunPow: T.fog.sunPow, max: T.fog.max, noise: 0 });
  st.L.exposure = T.exposure;
  st.sky.mesh.visible = preset !== "void" && preset !== "studio";
}

/** The studio backdrop tone (linear light): what the lit floor reflects, so floor and wall read as one surface. */
function studioBackdrop(env: StageEnv): THREE.Color {
  return new THREE.Color(env.ground ?? "#565c66").multiplyScalar(1.25);
}

function flat(color: number, size = 400): THREE.Mesh {
  const m = new THREE.Mesh(new THREE.CircleGeometry(size / 2, 96), new THREE.MeshStandardMaterial({ color, roughness: 0.95, metalness: 0 }));
  m.rotation.x = -Math.PI / 2;
  m.receiveShadow = true;
  return m;
}

/** Build a preset into the scene. */
export function buildEnv(st: Any, env: StageEnv): EnvRuntime {
  const scene: THREE.Scene = st.scene;
  const group: THREE.Object3D[] = [];
  const followers: THREE.Object3D[] = [];
  const add = <T extends THREE.Object3D>(o: T): T => {
    scene.add(o);
    group.push(o);
    return o;
  };
  const seed = env.seed ?? 7;
  const r = mulberry(seed * 977 + 13);
  let height: (x: number, z: number) => number = () => 0;
  let origin: Vec3 = [0, 0, 0];
  const p = env.preset;
  scene.background = null;
  const before = new Set(scene.children);

  if (p === "void") {
    scene.background = new THREE.Color(0x000000);
  } else if (p === "studio") {
    // A photographic cyclorama: soft grey floor that fades into the backdrop.
    scene.background = studioBackdrop(env);
    const f = add(flat(new THREE.Color(env.ground ?? "#565c66").getHex(), 240));
    (f.material as THREE.MeshStandardMaterial).roughness = 0.9;
  } else if (p === "bridge") {
    buildBorder(st, {});
    height = (x, z) => (Math.abs(z) < 2.6 && Math.abs(x) < 36 ? DECK : borderHeight(x, z));
    origin = [0, DECK, 0];
  } else if (p === "temple") {
    buildTemple(st, { season: env.weather === "snow" ? "winter" : "summer" });
    height = templeHeight;
    origin = [0, TOP, 84];
  } else if (p === "village") {
    buildKiri(st, { lit: env.time === "night" ? 1 : 0, night: env.time === "night" });
    height = kiriHeight;
  } else if (p === "arena") {
    const f = add(new THREE.Mesh(new THREE.CircleGeometry(60, 96), W.propMat("stone", { color: [0.62, 0.6, 0.58] })));
    f.rotation.x = -Math.PI / 2;
    f.receiveShadow = true;
    const uv = f.geometry.attributes.uv!;
    for (let i = 0; i < uv.count; i++) uv.setXY(i, uv.getX(i) * 40, uv.getY(i) * 40);
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * Math.PI * 2;
      const col = add(new THREE.Mesh(new THREE.CylinderGeometry(0.45, 0.55, 6.5, 14), W.propMat("stone", { color: [0.7, 0.68, 0.66] })));
      col.position.set(Math.cos(a) * 16, 3.25, Math.sin(a) * 16);
      col.castShadow = col.receiveShadow = true;
      if (i % 3 === 0) {
        const t = add(W.torch(1.9));
        t.position.set(Math.cos(a) * 14.2, 0, Math.sin(a) * 14.2);
      }
    }
  } else {
    // Open land: plain, meadow, forest, desert, snow.
    const amp = p === "desert" ? 2.2 : p === "forest" ? 1.6 : 1.0;
    height = (x, z) => {
      const d = Math.hypot(x, z);
      return (fbm(x * 0.012 + seed, z * 0.012, 3.1, 4) - 0.5) * 9 * amp * smooth(26, 110, d) + smooth(140, 520, d) * 60 * fbm(x * 0.003, z * 0.003 + seed, 1.7, 4);
    };
    const tint: Rgb = p === "desert" ? [1.5, 1.25, 0.85] : p === "snow" ? [1, 1, 1] : [1, 1, 1];
    add(W.makeTerrain({ size: 640, seg: 220, height, uvScale: 0.25, snowLine: 9999, snowAll: p === "snow" ? 1 : 0, tint, grass: p === "desert" ? "dirt" : "grass", mask: (x: number, z: number) => [p === "desert" ? 1 : smooth(3.5, 1.2, Math.hypot(x, z)) * 0.5, 0, 0, 0] }));
    add(W.makeTerrain({ size: 6400, seg: 120, snowLine: p === "snow" ? -1 : 230, uvScale: 0.05, rockSlope: 0.3, snowAll: p === "snow" ? 1 : 0, tint, grass: p === "desert" ? "dirt" : "grass", height: (x: number, z: number) => {
      const d = Math.hypot(x, z);
      return d < 290 ? -30 : height(x, z) * 0.9 + smooth(500, 2600, d) * 240 * fbm(x * 0.0011, z * 0.0011, 4.2, 5) - 6;
    } }));
    if (p === "meadow" || p === "forest" || p === "plain") {
      const n = p === "forest" ? 260 : p === "meadow" ? 60 : 18;
      const inner = p === "forest" ? 9 : 16;
      const spots = W.scatter(n, seed * 31 + 5, [-150, 150, -150, 150], height, (x: number, z: number) => Math.hypot(x, z) > inner + r() * 6);
      if (spots.length) add(W.forest(spots, p === "forest" && seed % 2 ? "pine" : "leaf", seed));
      const far = W.scatter(p === "forest" ? 900 : 260, seed * 17 + 3, [-420, 420, -420, 420], height, (x: number, z: number) => Math.hypot(x, z) > 150);
      if (far.length) add(W.forest(far, "leaf", seed + 1, { far: true }));
    }
    if (p === "meadow" || p === "plain" || p === "forest") {
      const tufts = W.scatter(p === "plain" ? 5000 : 9000, seed * 7 + 1, [-46, 46, -46, 46], height, (x: number, z: number) => Math.hypot(x, z) > 1.6 || r() < 0.3);
      // short meadow grass: tall tufts hide feet and swallow a low camera
      add(W.grassField(tufts.map((g: Any) => ({ ...g, s: g.s * 0.42 })), { seed }));
    }
    if (p !== "plain") {
      const rk = W.scatter(p === "desert" ? 40 : 16, seed * 5 + 9, [-70, 70, -70, 70], height, (x: number, z: number) => Math.hypot(x, z) > 7);
      add(W.rocks(rk.map((s: Any) => ({ ...s, s: s.s * (p === "desert" ? 1.6 : 0.9) })), seed));
    }
  }

  // Extra set pieces placed by hand.
  for (const it of env.items ?? []) {
    let o: THREE.Object3D | null = null;
    const k = it.kind;
    const q = it.params ?? {};
    if (k === "house") o = W.house(Number(q.seed ?? 3), q);
    else if (k === "torch") o = W.torch(Number(q.height ?? 1.7));
    else if (k === "banner") o = W.banner(q);
    else if (k === "lantern") o = W.lantern(Number(q.lit ?? 1));
    else if (k === "fence") o = W.fence(Number(q.length ?? 6), q);
    else if (k === "stall") o = W.stall(Number(q.seed ?? 1));
    else if (k === "well") o = W.well();
    else if (k === "gate") o = W.gate(Number(q.w ?? 5), Number(q.h ?? 5.2));
    else if (k === "stairs") o = W.stairs(Number(q.steps ?? 8), Number(q.w ?? 4));
    else if (k === "tree") o = W.forest([{ x: 0, y: 0, z: 0, s: 1, rot: 0, tint: 1 }], String(q.type ?? "leaf"), Number(q.seed ?? 2));
    else if (k === "rock") o = W.rocks([{ x: 0, y: 0, z: 0, s: 1, rot: 0, tint: 1 }], Number(q.seed ?? 3));
    else if (k === "model") o = modelInstance(String(q.src ?? ""));
    else throw new Error(`Unknown set piece "${k}". Pieces: house, tree, rock, torch, banner, lantern, fence, stall, well, gate, stairs, model.`);
    o.position.set(it.pos[0], it.pos[1] + height(it.pos[0], it.pos[2]) + origin[1] * 0, it.pos[2]);
    o.rotation.y = ((it.rot ?? 0) * Math.PI) / 180;
    o.scale.setScalar(it.scale ?? 1);
    add(o);
  }

  // Weather drifts with the camera.
  const w = env.weather;
  if (w === "rain") followers.push(add(FX.rain(5000, {})));
  else if (w === "snow") followers.push(add(FX.snow(3500, {})));
  else if (w === "mist") followers.push(add(FX.mist(60, [-40, 40, origin[1] - 1, origin[1] + 3, -60, 60], { size: 26, alpha: 0.2, color: [0.6, 0.63, 0.72] })));
  else if (w === "embers") followers.push(add(FX.embers(500, [-14, 14, origin[1], origin[1] + 6, -14, 14], {})));
  else if (w === "motes") followers.push(add(FX.motes(600, {})));

  for (const c of scene.children) if (!before.has(c) && !group.includes(c)) group.push(c);
  return {
    group,
    height,
    origin,
    followers,
    dispose() {
      for (const o of group) {
        o.removeFromParent();
        o.traverse((n) => {
          const m = n as THREE.Mesh;
          m.geometry?.dispose?.();
        });
      }
    },
  };
}
