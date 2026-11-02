/**
 * 3D studio: a three.js scene living inside an animation scene as a layer (kind "three"). Objects (primitives,
 * extruded text, imported glTF/GLB models, procedural toon characters), lights, environment and camera are plain
 * JSON (so the agent and the UI edit the same thing); every number can be keyframed by frame in `keys`.
 * Rendered with one shared WebGL renderer and drawn into the 2D scene, so 3D mixes with puppets, drawings,
 * FX, the timeline, grading and export.
 */
import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import { GLTFExporter } from "three/examples/jsm/exporters/GLTFExporter.js";
import type { AnimLayer, AnimScene, Keyframe } from "../core/model";
import { valueAt } from "../core/keyframes";
import { getBlob } from "../core/storage";

export type Obj3DType = "box" | "sphere" | "cylinder" | "cone" | "torus" | "plane" | "capsule" | "ring" | "icosa" | "knot" | "text" | "model" | "character" | "terrain" | "particles";
export const OBJ3D_TYPES: Obj3DType[] = ["box", "sphere", "cylinder", "cone", "torus", "plane", "capsule", "ring", "icosa", "knot", "text", "model", "character", "terrain", "particles"];
export type Material3D = "toon" | "standard" | "metal" | "glass" | "emissive" | "clay" | "flat";

export interface Char3D {
  skin: string;
  hair: string;
  top: string;
  pants: string;
  shoes: string;
  eyes: string;
  hairStyle: "spiky" | "short" | "long" | "bald";
  jacket?: string;
}

export interface Obj3D {
  id: string;
  name?: string;
  type: Obj3DType;
  color: string;
  material: Material3D;
  outline?: boolean;
  /** Type-specific size: box [w,h,d], sphere [r], cylinder/cone [rTop,rBottom,h], torus [r,tube], plane [w,h], text [size,depth]. */
  size?: number[];
  text?: string;
  font?: string;
  assetId?: string;
  /** Storage ref of the model asset (lets the preview load it without the project). */
  ref?: string;
  /** glTF animation clip name to play (time follows the scene). */
  clip?: string;
  char?: Char3D;
  emissive?: string;
  pos: [number, number, number];
  rot: [number, number, number]; // degrees
  scale: number;
  opacity?: number;
  castShadow?: boolean;
  parent?: string;
}

export interface Light3D {
  id: string;
  type: "ambient" | "key" | "rim" | "point" | "spot" | "hemi";
  color: string;
  intensity: number;
  pos?: [number, number, number];
}

export interface Scene3D {
  objects: Obj3D[];
  lights: Light3D[];
  camera: { fov: number; pos: [number, number, number]; target: [number, number, number] };
  env: { sky?: [string, string] | null; fog?: { color: string; near: number; far: number } | null; ground?: { color: string; size: number; grid?: boolean } | null };
  style: "toon" | "realistic" | "clay" | "neon";
  /** Keyframes by property: "<objId>.px|py|pz|rx|ry|rz|s|opacity", "<charId>.j.<joint>.x|y|z", "<charId>.expr", "cam.px..pz|tx..tz|fov", "<lightId>.i". */
  keys: Record<string, Keyframe[]>;
}

export function newScene3D(): Scene3D {
  return {
    objects: [],
    lights: [
      { id: "amb", type: "hemi", color: "#bcd0ff", intensity: 0.9 },
      { id: "key", type: "key", color: "#fff3e0", intensity: 2.4, pos: [4, 8, 5] },
      { id: "rim", type: "rim", color: "#8fb0ff", intensity: 1.6, pos: [-5, 4, -6] },
    ],
    camera: { fov: 40, pos: [0, 1.6, 8], target: [0, 1.1, 0] },
    env: { sky: ["#0f1630", "#3a4a8a"], fog: null, ground: { color: "#2a2d3a", size: 40, grid: false } },
    style: "toon",
    keys: {},
  };
}

/* ------------------------------ characters -------------------------------- */

/** Joints of the procedural character, with their rest offsets from the parent (metres). */
export const JOINTS = ["hips", "spine", "chest", "neck", "head", "shoulderL", "elbowL", "handL", "shoulderR", "elbowR", "handR", "hipL", "kneeL", "footL", "hipR", "kneeR", "footR"] as const;
export type Joint = (typeof JOINTS)[number];

/**
 * Poses: joint → [x, y, z] rotation in degrees (missing joints are 0; limbs hang straight down at 0).
 * Arms/legs: x < 0 swings forward, x > 0 back (knees/elbows bend with x); z raises sideways (left arm +, right arm −).
 */
export const POSES3D: Record<string, Partial<Record<Joint, [number, number, number]>>> = {
  rest: { shoulderL: [0, 0, 8], shoulderR: [0, 0, -8], elbowL: [-8, 0, 0], elbowR: [-8, 0, 0] },
  tpose: { shoulderL: [0, 0, 90], shoulderR: [0, 0, -90] },
  guard: { hips: [0, -30, 0], chest: [5, 20, 0], neck: [5, 10, 0], shoulderL: [-50, 0, 18], elbowL: [-115, 0, 0], shoulderR: [-62, 0, -12], elbowR: [-105, 0, 0], hipL: [-18, 0, 6], kneeL: [28, 0, 0], hipR: [14, 0, -6], kneeR: [22, 0, 0] },
  punchR: { hips: [0, -12, 0], chest: [6, -32, 0], neck: [0, 20, 0], shoulderR: [-90, 0, 0], elbowR: [-4, 0, 0], shoulderL: [-50, 0, 18], elbowL: [-115, 0, 0], hipL: [-24, 0, 6], kneeL: [16, 0, 0], hipR: [20, 0, -6], kneeR: [30, 0, 0] },
  punchL: { hips: [0, -48, 0], chest: [8, 42, 0], neck: [0, -20, 0], shoulderL: [-90, 0, 0], elbowL: [-4, 0, 0], shoulderR: [-62, 0, -12], elbowR: [-105, 0, 0], hipL: [-12, 0, 6], kneeL: [30, 0, 0], hipR: [12, 0, -6], kneeR: [12, 0, 0] },
  kick: { hips: [0, -20, -10], chest: [-16, 10, 8], hipR: [-95, 0, -12], kneeR: [8, 0, 0], footR: [20, 0, 0], hipL: [6, 0, 4], kneeL: [12, 0, 0], shoulderL: [-30, 0, 45], elbowL: [-60, 0, 0], shoulderR: [10, 0, -55], elbowR: [-30, 0, 0] },
  wave: { shoulderR: [0, 0, -155], elbowR: [0, 0, -25], shoulderL: [0, 0, 8], elbowL: [-8, 0, 0] },
  cheer: { shoulderL: [0, 0, 160], shoulderR: [0, 0, -160], elbowL: [0, 0, 15], elbowR: [0, 0, -15], chest: [-8, 0, 0], neck: [-14, 0, 0] },
  think: { shoulderR: [-40, 0, -22], elbowR: [-145, 0, 0], neck: [10, 0, 8], shoulderL: [-30, 0, 10], elbowL: [-100, 0, 0] },
  sit: { hipL: [-90, 0, 4], kneeL: [90, 0, 0], hipR: [-90, 0, -4], kneeR: [90, 0, 0], shoulderL: [-20, 0, 8], shoulderR: [-20, 0, -8], elbowL: [-40, 0, 0], elbowR: [-40, 0, 0] },
  hit: { chest: [-25, 0, -10], neck: [-25, 0, -15], shoulderL: [10, 0, 45], shoulderR: [20, 0, -50], elbowL: [-30, 0, 0], hipR: [-15, 0, -4], kneeR: [20, 0, 0] },
  crouch: { hipL: [-75, 0, 8], kneeL: [125, 0, 0], footL: [-45, 0, 0], hipR: [-65, 0, -8], kneeR: [115, 0, 0], footR: [-45, 0, 0], chest: [25, 0, 0], shoulderL: [-35, 0, 15], shoulderR: [-35, 0, -15], elbowL: [-40, 0, 0], elbowR: [-40, 0, 0] },
  bow: { spine: [35, 0, 0], chest: [15, 0, 0], shoulderL: [-10, 0, 6], shoulderR: [-10, 0, -6] },
  point: { shoulderR: [-90, 0, -5], elbowR: [0, 0, 0], shoulderL: [0, 0, 8], neck: [0, -10, 0] },
  dance1: { hips: [0, 20, 8], chest: [0, -20, -6], shoulderL: [0, 0, 135], elbowL: [0, 0, 40], shoulderR: [-40, 0, -30], elbowR: [-90, 0, 0], hipL: [-30, 0, 4], kneeL: [40, 0, 0] },
  dance2: { hips: [0, -20, -8], chest: [0, 20, 6], shoulderR: [0, 0, -135], elbowR: [0, 0, -40], shoulderL: [-40, 0, 30], elbowL: [-90, 0, 0], hipR: [-30, 0, -4], kneeR: [40, 0, 0] },
};

const SEG: Record<Joint, { parent: Joint | null; off: [number, number, number] }> = {
  hips: { parent: null, off: [0, 0.98, 0] },
  spine: { parent: "hips", off: [0, 0.12, 0] },
  chest: { parent: "spine", off: [0, 0.24, 0] },
  neck: { parent: "chest", off: [0, 0.27, 0] },
  head: { parent: "neck", off: [0, 0.1, 0] },
  shoulderL: { parent: "chest", off: [0.2, 0.2, 0] },
  elbowL: { parent: "shoulderL", off: [0, -0.3, 0] },
  handL: { parent: "elbowL", off: [0, -0.27, 0] },
  shoulderR: { parent: "chest", off: [-0.2, 0.2, 0] },
  elbowR: { parent: "shoulderR", off: [0, -0.3, 0] },
  handR: { parent: "elbowR", off: [0, -0.27, 0] },
  hipL: { parent: "hips", off: [0.1, -0.05, 0] },
  kneeL: { parent: "hipL", off: [0, -0.44, 0] },
  footL: { parent: "kneeL", off: [0, -0.43, 0] },
  hipR: { parent: "hips", off: [-0.1, -0.05, 0] },
  kneeR: { parent: "hipR", off: [0, -0.44, 0] },
  footR: { parent: "kneeR", off: [0, -0.43, 0] },
};

export const DEFAULT_CHAR3D: Char3D = { skin: "#f6dccb", hair: "#1c1c26", top: "#7d1622", pants: "#1f1f27", shoes: "#111114", eyes: "#d12c43", hairStyle: "spiky" };

/* ------------------------------ materials --------------------------------- */

let toonRamp: THREE.DataTexture | null = null;
function ramp(): THREE.DataTexture {
  if (toonRamp) return toonRamp;
  const d = new Uint8Array([70, 70, 70, 255, 160, 160, 160, 255, 255, 255, 255, 255]);
  toonRamp = new THREE.DataTexture(d, 3, 1, THREE.RGBAFormat);
  toonRamp.minFilter = toonRamp.magFilter = THREE.NearestFilter;
  toonRamp.needsUpdate = true;
  return toonRamp;
}

function material(kind: Material3D, color: string, style: Scene3D["style"], emissive?: string, opacity = 1): THREE.Material {
  const col = new THREE.Color(color);
  const k = kind === "toon" && style === "realistic" ? "standard" : kind;
  const transparent = opacity < 1 || k === "glass";
  switch (k) {
    case "toon":
      return new THREE.MeshToonMaterial({ color: col, gradientMap: ramp(), emissive: new THREE.Color(emissive ?? "#000000"), transparent, opacity });
    case "metal":
      return new THREE.MeshStandardMaterial({ color: col, metalness: 1, roughness: 0.25, transparent, opacity });
    case "glass":
      return new THREE.MeshPhysicalMaterial({ color: col, metalness: 0, roughness: 0.05, transmission: 0.9, thickness: 0.5, transparent: true, opacity: Math.min(opacity, 0.6) });
    case "emissive":
      return new THREE.MeshStandardMaterial({ color: col, emissive: new THREE.Color(emissive ?? color), emissiveIntensity: 2.2, transparent, opacity });
    case "clay":
      return new THREE.MeshStandardMaterial({ color: col, roughness: 0.95, metalness: 0, transparent, opacity });
    case "flat":
      return new THREE.MeshBasicMaterial({ color: col, transparent, opacity });
    default:
      return new THREE.MeshStandardMaterial({ color: col, roughness: 0.55, metalness: 0.05, emissive: new THREE.Color(emissive ?? "#000000"), transparent, opacity });
  }
}

/** Inverted-hull ink outline (anime look). */
function addOutline(mesh: THREE.Mesh, width = 0.025): void {
  const o = new THREE.Mesh(mesh.geometry, new THREE.MeshBasicMaterial({ color: 0x07070a, side: THREE.BackSide }));
  o.name = "__outline";
  mesh.geometry.computeBoundingSphere();
  const r = mesh.geometry.boundingSphere?.radius ?? 1;
  o.scale.setScalar(1 + width / Math.max(0.05, r));
  mesh.add(o);
}

/* ------------------------------ geometry ---------------------------------- */

function textTexture(text: string, color: string, font: string): { tex: THREE.CanvasTexture; aspect: number } {
  const c = document.createElement("canvas");
  const px = 160;
  const ctx = c.getContext("2d")!;
  ctx.font = `900 ${px}px ${font}`;
  const lines = text.split("\n");
  const w = Math.max(...lines.map((l) => ctx.measureText(l).width)) + px * 0.3;
  c.width = Math.ceil(w);
  c.height = Math.ceil(px * 1.2 * lines.length + px * 0.2);
  const g = c.getContext("2d")!;
  g.font = `900 ${px}px ${font}`;
  g.fillStyle = color;
  g.textBaseline = "top";
  g.textAlign = "center";
  lines.forEach((l, i) => g.fillText(l, c.width / 2, px * 0.1 + i * px * 1.2));
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  return { tex, aspect: c.width / c.height };
}

function faceTexture(eyes: string, expr: number): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = c.height = 256;
  const g = c.getContext("2d")!;
  const e = Math.round(expr);
  // 0 neutral, 1 smile, 2 angry, 3 surprised, 4 closed, 5 shout
  g.lineCap = "round";
  for (const sx of [-1, 1]) {
    const x = 128 + sx * 46;
    if (e === 4) {
      g.strokeStyle = "#1a1018";
      g.lineWidth = 9;
      g.beginPath();
      g.arc(x, 118, 22, Math.PI * 0.15, Math.PI * 0.85);
      g.stroke();
    } else {
      g.fillStyle = "#ffffff";
      g.beginPath();
      g.ellipse(x, 120, 24, e === 3 ? 34 : 29, 0, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = eyes;
      g.beginPath();
      g.ellipse(x + sx * 2, 124, 16, e === 3 ? 18 : 22, 0, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = "#0a0a10";
      g.beginPath();
      g.ellipse(x + sx * 2, 126, 8, 11, 0, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = "#ffffff";
      g.beginPath();
      g.arc(x - 6, 112, 6, 0, Math.PI * 2);
      g.fill();
      g.strokeStyle = "#140c12";
      g.lineWidth = 7;
      g.beginPath();
      g.ellipse(x, 120, 25, e === 3 ? 35 : 30, 0, Math.PI * 1.05, Math.PI * 1.95);
      g.stroke();
    }
    // Brows.
    g.strokeStyle = "#1c1218";
    g.lineWidth = 8;
    g.beginPath();
    const tilt = e === 2 || e === 5 ? 14 : e === 3 ? -8 : 0;
    g.moveTo(x - 24, 78 - sx * tilt * -0.5 + (e === 3 ? -10 : 0));
    g.lineTo(x + 24, 78 + sx * tilt * -0.5 + (e === 3 ? -10 : 0));
    g.stroke();
  }
  g.strokeStyle = "#5a2a30";
  g.fillStyle = "#5a2030";
  g.lineWidth = 6;
  g.beginPath();
  if (e === 1) g.arc(128, 170, 22, Math.PI * 0.15, Math.PI * 0.85);
  else if (e === 3 || e === 5) {
    g.ellipse(128, 186, e === 5 ? 22 : 12, e === 5 ? 20 : 14, 0, 0, Math.PI * 2);
    g.fill();
  } else if (e === 2) {
    g.moveTo(108, 190);
    g.lineTo(148, 184);
  } else {
    g.moveTo(114, 186);
    g.lineTo(142, 186);
  }
  g.stroke();
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function buildCharacter(o: Obj3D, style: Scene3D["style"]): THREE.Group {
  const ch = { ...DEFAULT_CHAR3D, ...(o.char ?? {}) };
  const root = new THREE.Group();
  const joints: Partial<Record<Joint, THREE.Object3D>> = {};
  for (const j of JOINTS) {
    const g = new THREE.Group();
    g.name = `j:${j}`;
    g.position.set(...SEG[j].off);
    joints[j] = g;
    (SEG[j].parent ? joints[SEG[j].parent!]! : root).add(g);
  }
  const mat = (c: string) => material(o.material === "toon" || !o.material ? "toon" : o.material, c, style);
  const limb = (from: Joint, len: number, r: number, color: string, dir: [number, number, number]) => {
    const m = new THREE.Mesh(new THREE.CapsuleGeometry(r, Math.max(0.01, len - r * 2), 6, 12), mat(color));
    m.position.set((dir[0] * len) / 2, (dir[1] * len) / 2, (dir[2] * len) / 2);
    if (dir[0] !== 0) m.rotation.z = Math.PI / 2;
    joints[from]!.add(m);
    if (o.outline !== false) addOutline(m, 0.012);
    return m;
  };
  const top = ch.jacket ?? ch.top;
  // Torso: pelvis + chest blocks (rounded).
  const pelvis = new THREE.Mesh(new THREE.CapsuleGeometry(0.13, 0.06, 6, 12), mat(ch.pants));
  pelvis.scale.set(1.2, 1, 0.8);
  joints.hips!.add(pelvis);
  const torso = new THREE.Mesh(new THREE.CapsuleGeometry(0.145, 0.3, 6, 14), mat(top));
  torso.scale.set(1.28, 1, 0.72);
  torso.position.y = 0.1;
  joints.chest!.add(torso);
  if (o.outline !== false) {
    addOutline(pelvis, 0.012);
    addOutline(torso, 0.012);
  }
  limb("neck", 0.12, 0.05, ch.skin, [0, 1, 0]);
  // Head: slightly tall sphere + face decal + hair.
  const head = new THREE.Mesh(new THREE.SphereGeometry(0.17, 28, 20), mat(ch.skin));
  head.scale.set(0.95, 1.1, 0.98);
  head.position.y = 0.14;
  joints.head!.add(head);
  if (o.outline !== false) addOutline(head, 0.01);
  const face = new THREE.Mesh(new THREE.PlaneGeometry(0.26, 0.26), new THREE.MeshBasicMaterial({ map: faceTexture(ch.eyes, 0), transparent: true }));
  face.name = "face";
  face.position.set(0, 0.12, 0.168);
  joints.head!.add(face);
  const hairMat = mat(ch.hair);
  if (ch.hairStyle !== "bald") {
    const cap = new THREE.Mesh(new THREE.SphereGeometry(0.18, 22, 14, 0, Math.PI * 2, 0, Math.PI * 0.42), hairMat);
    cap.position.y = 0.17;
    cap.scale.set(1.0, 1.12, 1.04);
    if (o.outline !== false) addOutline(cap, 0.008);
    joints.head!.add(cap);
    const n = ch.hairStyle === "spiky" ? 14 : ch.hairStyle === "long" ? 10 : 8;
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2;
      const len = ch.hairStyle === "spiky" ? 0.16 : ch.hairStyle === "long" ? 0.34 : 0.08;
      const sp = new THREE.Mesh(new THREE.ConeGeometry(0.05, len, 6), hairMat);
      const back = Math.cos(a) < -0.2;
      sp.position.set(Math.sin(a) * 0.13, 0.27 - (ch.hairStyle === "long" && back ? 0.2 : 0), Math.cos(a) * 0.13);
      sp.rotation.set(Math.cos(a) * (ch.hairStyle === "long" && back ? 2.6 : 1.1), 0, -Math.sin(a) * 1.1);
      joints.head!.add(sp);
      if (o.outline !== false) addOutline(sp, 0.006);
    }
  }
  limb("shoulderL", 0.3, 0.055, top, [0, -1, 0]);
  limb("elbowL", 0.27, 0.048, top, [0, -1, 0]);
  limb("shoulderR", 0.3, 0.055, top, [0, -1, 0]);
  limb("elbowR", 0.27, 0.048, top, [0, -1, 0]);
  for (const j of ["handL", "handR"] as const) {
    const h = new THREE.Mesh(new THREE.SphereGeometry(0.058, 12, 10), mat(ch.skin));
    h.scale.set(0.9, 1.15, 0.8);
    h.position.y = -0.045;
    joints[j]!.add(h);
  }
  limb("hipL", 0.44, 0.075, ch.pants, [0, -1, 0]);
  limb("kneeL", 0.43, 0.062, ch.pants, [0, -1, 0]);
  limb("hipR", 0.44, 0.075, ch.pants, [0, -1, 0]);
  limb("kneeR", 0.43, 0.062, ch.pants, [0, -1, 0]);
  for (const j of ["footL", "footR"] as const) {
    const f = new THREE.Mesh(new THREE.BoxGeometry(0.11, 0.08, 0.24), mat(ch.shoes));
    f.position.set(0, -0.03, 0.05);
    joints[j]!.add(f);
    if (o.outline !== false) addOutline(f, 0.01);
  }
  root.userData.joints = joints;
  root.userData.face = face;
  root.userData.eyes = ch.eyes;
  return root;
}

function terrain(size: number, color: string, style: Scene3D["style"]): THREE.Mesh {
  const g = new THREE.PlaneGeometry(size, size, 64, 64);
  const p = g.attributes.position!;
  for (let i = 0; i < p.count; i++) {
    const x = p.getX(i),
      y = p.getY(i);
    const d = Math.hypot(x, y) / (size / 2);
    p.setZ(i, (Math.sin(x * 0.45) * Math.cos(y * 0.38) * 0.6 + Math.sin(x * 1.3 + y * 0.7) * 0.15) * Math.min(1, d * 1.6) * size * 0.05);
  }
  g.computeVertexNormals();
  const m = new THREE.Mesh(g, material(style === "toon" ? "toon" : "standard", color, style));
  m.rotation.x = -Math.PI / 2;
  return m;
}

function particles(color: string, count = 400): THREE.Points {
  const g = new THREE.BufferGeometry();
  const pos = new Float32Array(count * 3);
  for (let i = 0; i < count; i++) {
    pos[i * 3] = (Math.random() - 0.5) * 12;
    pos[i * 3 + 1] = Math.random() * 6;
    pos[i * 3 + 2] = (Math.random() - 0.5) * 12;
  }
  g.setAttribute("position", new THREE.BufferAttribute(pos, 3));
  return new THREE.Points(g, new THREE.PointsMaterial({ color: new THREE.Color(color), size: 0.05, transparent: true, opacity: 0.85, blending: THREE.AdditiveBlending, depthWrite: false }));
}

/* --------------------------- models (glTF/GLB) ----------------------------- */

const models = new Map<string, Promise<{ scene: THREE.Group; clips: THREE.AnimationClip[] }>>();
const modelsReady = new Map<string, { scene: THREE.Group; clips: THREE.AnimationClip[] }>();

/** Load (once) a glTF/GLB asset; `fetchBlob` returns the stored bytes. */
export function loadModel(assetId: string, fetchBlob: () => Promise<Blob>): Promise<{ scene: THREE.Group; clips: THREE.AnimationClip[] }> {
  let p = models.get(assetId);
  if (!p) {
    p = fetchBlob()
      .then((b) => b.arrayBuffer())
      .then((buf) => new GLTFLoader().parseAsync(buf, ""))
      .then((g) => {
        const r = { scene: g.scene, clips: g.animations };
        modelsReady.set(assetId, r);
        return r;
      });
    models.set(assetId, p);
    p.catch(() => models.delete(assetId));
  }
  return p;
}

/** Load every model a scene uses (call before export). */
export async function preloadModels(s: AnimScene, fetchBlob: (assetId: string) => Promise<Blob>): Promise<void> {
  const ids = new Set<string>();
  for (const l of s.layers) for (const o of l.three?.objects ?? []) if (o.type === "model" && o.assetId) ids.add(o.assetId);
  await Promise.all([...ids].map((id) => loadModel(id, () => fetchBlob(id)).catch(() => null)));
}

/* ------------------------------ scene build -------------------------------- */

interface Built {
  sig: string;
  scene: THREE.Scene;
  nodes: Map<string, THREE.Object3D>;
  mixers: Map<string, { mixer: THREE.AnimationMixer; action: THREE.AnimationAction | null }>;
  lights: Map<string, THREE.Light>;
  faces: Map<string, number>;
  pending: boolean;
}
const built = new Map<string, Built>();

function sigOf(sp: Scene3D): string {
  const { keys: _k, camera: _c, ...rest } = sp;
  return JSON.stringify(rest) + [...modelsReady.keys()].join(",");
}

function build(layerId: string, sp: Scene3D): Built {
  const sig = sigOf(sp);
  const old = built.get(layerId);
  if (old && old.sig === sig) return old;
  if (old) dispose(old.scene);
  const scene = new THREE.Scene();
  const nodes = new Map<string, THREE.Object3D>();
  const mixers = new Map<string, { mixer: THREE.AnimationMixer; action: THREE.AnimationAction | null }>();
  let pending = false;
  if (sp.env.fog) scene.fog = new THREE.Fog(new THREE.Color(sp.env.fog.color), sp.env.fog.near, sp.env.fog.far);
  if (sp.env.ground) {
    const g = new THREE.Mesh(new THREE.CircleGeometry(sp.env.ground.size / 2, 64), material(sp.style === "toon" ? "toon" : "standard", sp.env.ground.color, sp.style));
    g.rotation.x = -Math.PI / 2;
    g.receiveShadow = true;
    scene.add(g);
    if (sp.env.ground.grid) {
      const grid = new THREE.GridHelper(sp.env.ground.size, sp.env.ground.size, 0x8890b0, 0x40445a);
      (grid.material as THREE.Material).transparent = true;
      (grid.material as THREE.Material).opacity = 0.4;
      grid.position.y = 0.002;
      scene.add(grid);
    }
  }
  const lights = new Map<string, THREE.Light>();
  for (const L of sp.lights) {
    let light: THREE.Light;
    const col = new THREE.Color(L.color);
    if (L.type === "ambient") light = new THREE.AmbientLight(col, L.intensity);
    else if (L.type === "hemi") light = new THREE.HemisphereLight(col, new THREE.Color("#20222c"), L.intensity);
    else if (L.type === "point") light = new THREE.PointLight(col, L.intensity * 10, 0, 2);
    else if (L.type === "spot") {
      const sl = new THREE.SpotLight(col, L.intensity * 20, 0, Math.PI / 6, 0.4, 1.5);
      sl.castShadow = true;
      light = sl;
    } else {
      const dl = new THREE.DirectionalLight(col, L.intensity);
      if (L.type === "key") {
        dl.castShadow = true;
        dl.shadow.mapSize.set(1024, 1024);
        const cam = dl.shadow.camera as THREE.OrthographicCamera;
        cam.left = cam.bottom = -8;
        cam.right = cam.top = 8;
      }
      light = dl;
    }
    if (L.pos) light.position.set(...L.pos);
    scene.add(light);
    lights.set(L.id, light);
  }
  for (const o of sp.objects) {
    let node: THREE.Object3D;
    const sz = o.size ?? [];
    const geo = (): THREE.BufferGeometry | null => {
      switch (o.type) {
        case "box": return new THREE.BoxGeometry(sz[0] ?? 1, sz[1] ?? 1, sz[2] ?? 1, 2, 2, 2);
        case "sphere": return new THREE.SphereGeometry(sz[0] ?? 0.5, 40, 28);
        case "cylinder": return new THREE.CylinderGeometry(sz[0] ?? 0.4, sz[1] ?? 0.4, sz[2] ?? 1, 40);
        case "cone": return new THREE.ConeGeometry(sz[0] ?? 0.5, sz[1] ?? 1, 40);
        case "torus": return new THREE.TorusGeometry(sz[0] ?? 0.5, sz[1] ?? 0.16, 20, 60);
        case "plane": return new THREE.PlaneGeometry(sz[0] ?? 2, sz[1] ?? 2);
        case "capsule": return new THREE.CapsuleGeometry(sz[0] ?? 0.3, sz[1] ?? 0.8, 8, 20);
        case "ring": return new THREE.RingGeometry(sz[0] ?? 0.4, sz[1] ?? 0.6, 48);
        case "icosa": return new THREE.IcosahedronGeometry(sz[0] ?? 0.6, Math.round(sz[1] ?? 0));
        case "knot": return new THREE.TorusKnotGeometry(sz[0] ?? 0.45, sz[1] ?? 0.14, 160, 20);
        default: return null;
      }
    };
    if (o.type === "character") node = buildCharacter(o, sp.style);
    else if (o.type === "terrain") node = terrain(sz[0] ?? 30, o.color, sp.style);
    else if (o.type === "particles") node = particles(o.color, Math.round(sz[0] ?? 400));
    else if (o.type === "text") {
      const grp = new THREE.Group();
      const { tex, aspect } = textTexture(o.text ?? "TEXT", o.color, o.font ? `"${o.font}", sans-serif` : "Geist Variable, sans-serif");
      const h = sz[0] ?? 0.6;
      const depth = sz[1] ?? 0.12;
      const layers = 10;
      const dark = new THREE.Color(o.color).multiplyScalar(0.35);
      for (let i = layers; i >= 0; i--) {
        const m = new THREE.MeshBasicMaterial({ map: tex, transparent: true, color: i === 0 ? 0xffffff : dark, depthWrite: i === 0 });
        const pl = new THREE.Mesh(new THREE.PlaneGeometry(h * aspect, h), m);
        pl.position.z = -(i / layers) * depth;
        grp.add(pl);
      }
      node = grp;
    } else if (o.type === "model") {
      const ready = o.assetId ? modelsReady.get(o.assetId) : undefined;
      if (ready) {
        const clone = ready.scene.clone(true);
        node = clone;
        if (ready.clips.length) {
          const mixer = new THREE.AnimationMixer(clone);
          const clip = (o.clip ? ready.clips.find((c) => c.name === o.clip) : null) ?? ready.clips[0]!;
          const action = mixer.clipAction(clip);
          action.play();
          mixers.set(o.id, { mixer, action });
        }
        clone.traverse((n) => {
          if ((n as THREE.Mesh).isMesh) (n as THREE.Mesh).castShadow = true;
        });
      } else {
        pending = !!o.assetId;
        node = new THREE.Group();
      }
    } else {
      const g = geo()!;
      const m = new THREE.Mesh(g, material(o.material ?? "toon", o.color, sp.style, o.emissive, o.opacity ?? 1));
      m.castShadow = o.castShadow !== false;
      m.receiveShadow = true;
      if (o.outline ?? (sp.style === "toon" && o.material === "toon")) addOutline(m);
      node = m;
    }
    node.name = o.id;
    node.userData.base = o;
    nodes.set(o.id, node);
  }
  for (const o of sp.objects) {
    const n = nodes.get(o.id)!;
    (o.parent && nodes.get(o.parent) ? nodes.get(o.parent)! : scene).add(n);
  }
  const b: Built = { sig, scene, nodes, mixers, lights, faces: new Map(), pending };
  built.set(layerId, b);
  return b;
}

function dispose(o: THREE.Object3D): void {
  o.traverse((n) => {
    const m = n as THREE.Mesh;
    if (m.geometry && m.name !== "__outline") m.geometry.dispose();
    const mat = m.material as THREE.Material | THREE.Material[] | undefined;
    if (mat) (Array.isArray(mat) ? mat : [mat]).forEach((x) => x.dispose());
  });
}

/* ------------------------------ evaluation --------------------------------- */

const D2R = Math.PI / 180;
export const kv = (sp: Scene3D, name: string, f: number, base: number): number => valueAt(sp.keys[name], f, base);

/** Joint rotation (degrees) for a character at frame f. */
export function jointAt(sp: Scene3D, id: string, j: Joint, f: number): [number, number, number] {
  const rest = POSES3D.rest![j] ?? [0, 0, 0];
  return [kv(sp, `${id}.j.${j}.x`, f, rest[0]), kv(sp, `${id}.j.${j}.y`, f, rest[1]), kv(sp, `${id}.j.${j}.z`, f, rest[2])];
}

function pose(b: Built, sp: Scene3D, f: number, fps: number): void {
  for (const o of sp.objects) {
    const n = b.nodes.get(o.id);
    if (!n) continue;
    n.position.set(kv(sp, `${o.id}.px`, f, o.pos[0]), kv(sp, `${o.id}.py`, f, o.pos[1]), kv(sp, `${o.id}.pz`, f, o.pos[2]));
    n.rotation.set(kv(sp, `${o.id}.rx`, f, o.rot[0]) * D2R, kv(sp, `${o.id}.ry`, f, o.rot[1]) * D2R, kv(sp, `${o.id}.rz`, f, o.rot[2]) * D2R);
    n.scale.setScalar(Math.max(0.0001, kv(sp, `${o.id}.s`, f, o.scale)));
    const op = kv(sp, `${o.id}.opacity`, f, o.opacity ?? 1);
    n.visible = op > 0.001;
    if (o.type === "character") {
      const joints = n.userData.joints as Record<Joint, THREE.Object3D>;
      for (const j of JOINTS) {
        const r = jointAt(sp, o.id, j, f);
        joints[j].rotation.set(r[0] * D2R, r[1] * D2R, r[2] * D2R, "YXZ");
      }
      // Breathing.
      joints.chest.scale.setScalar(1 + Math.sin((f / fps) * 1.7) * 0.008);
      const ex = Math.round(kv(sp, `${o.id}.expr`, f, 0));
      if (b.faces.get(o.id) !== ex) {
        const face = n.userData.face as THREE.Mesh;
        const m = face.material as THREE.MeshBasicMaterial;
        m.map?.dispose();
        m.map = faceTexture(n.userData.eyes as string, ex);
        m.needsUpdate = true;
        b.faces.set(o.id, ex);
      }
    } else if (o.type === "particles") {
      const pts = n as THREE.Points;
      pts.rotation.y += 0; // drift handled below
      const t = f / fps;
      pts.position.y = kv(sp, `${o.id}.py`, f, o.pos[1]) - ((t * 0.3) % 1) * 0.5;
    }
    const mx = b.mixers.get(o.id);
    if (mx?.action) {
      mx.mixer.setTime(kv(sp, `${o.id}.clipTime`, f, f / fps));
    }
  }
  for (const L of sp.lights) {
    const l = b.lights.get(L.id);
    if (!l) continue;
    const base = L.type === "point" ? L.intensity * 10 : L.type === "spot" ? L.intensity * 20 : L.intensity;
    l.intensity = kv(sp, `${L.id}.i`, f, base);
  }
}

/* ------------------------------ rendering ---------------------------------- */

let renderer: THREE.WebGLRenderer | null = null;
let rendererFailed = false;
const camera = new THREE.PerspectiveCamera(40, 9 / 16, 0.05, 500);

export function getRenderer(): THREE.WebGLRenderer | null {
  if (renderer || rendererFailed) return renderer;
  try {
    const canvas = typeof OffscreenCanvas !== "undefined" ? new OffscreenCanvas(16, 16) : document.createElement("canvas");
    renderer = new THREE.WebGLRenderer({ canvas: canvas as HTMLCanvasElement, antialias: true, alpha: true, preserveDrawingBuffer: true, powerPreference: "high-performance" });
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  } catch {
    rendererFailed = true;
  }
  return renderer;
}

/** Draw a 3D layer into a 2D context sized W×H (the scene's pixels × scale). */
export function draw3D(c: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, l: AnimLayer, s: AnimScene, f: number, W: number, H: number, onReady?: () => void): void {
  const sp = l.three;
  if (!sp) return;
  const r = getRenderer();
  if (!r) {
    c.save();
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.fillStyle = "rgba(120,140,200,0.25)";
    c.fillRect(0, 0, W, H);
    c.restore();
    return;
  }
  const b = build(l.id, sp);
  if (b.pending && onReady) {
    for (const o of sp.objects) {
      if (o.type !== "model" || !o.assetId || modelsReady.has(o.assetId)) continue;
      const ref = o.ref;
      const p = models.get(o.assetId) ?? (ref ? loadModel(o.assetId, () => getBlob(ref)) : undefined);
      p?.then(() => onReady()).catch(() => {});
    }
  }
  pose(b, sp, f, s.fps);
  const style = sp.style;
  r.toneMapping = style === "realistic" ? THREE.ACESFilmicToneMapping : THREE.NoToneMapping;
  if (sp.env.sky && sp.env.sky.length === 2) {
    if (!b.scene.background || (b.scene.background as THREE.Texture).userData?.sky !== sp.env.sky.join()) {
      const cv = document.createElement("canvas");
      cv.width = 4;
      cv.height = 256;
      const g = cv.getContext("2d")!;
      const gr = g.createLinearGradient(0, 0, 0, 256);
      gr.addColorStop(0, sp.env.sky[0]);
      gr.addColorStop(1, sp.env.sky[1]);
      g.fillStyle = gr;
      g.fillRect(0, 0, 4, 256);
      const tex = new THREE.CanvasTexture(cv);
      tex.colorSpace = THREE.SRGBColorSpace;
      tex.userData.sky = sp.env.sky.join();
      b.scene.background = tex;
    }
  } else b.scene.background = null;
  camera.fov = kv(sp, "cam.fov", f, sp.camera.fov);
  camera.aspect = W / H;
  camera.position.set(kv(sp, "cam.px", f, sp.camera.pos[0]), kv(sp, "cam.py", f, sp.camera.pos[1]), kv(sp, "cam.pz", f, sp.camera.pos[2]));
  camera.lookAt(kv(sp, "cam.tx", f, sp.camera.target[0]), kv(sp, "cam.ty", f, sp.camera.target[1]), kv(sp, "cam.tz", f, sp.camera.target[2]));
  camera.rotation.z += kv(sp, "cam.roll", f, 0) * D2R;
  camera.updateProjectionMatrix();
  if (r.domElement.width !== W || r.domElement.height !== H) r.setSize(W, H, false);
  r.setClearColor(0x000000, 0);
  r.render(b.scene, camera);
  c.save();
  c.setTransform(1, 0, 0, 1, 0, 0);
  c.drawImage(r.domElement as CanvasImageSource, 0, 0, W, H);
  c.restore();
}

/** Export one object (or the whole 3D layer) as a .glb file. */
export async function exportGlb(l: AnimLayer, objId?: string): Promise<Blob> {
  if (!l.three) throw new Error("Not a 3D layer.");
  const b = build(l.id, l.three);
  pose(b, l.three, 0, 24);
  const target = objId ? b.nodes.get(objId) : b.scene;
  if (!target) throw new Error(`No object ${objId}`);
  const out = await new GLTFExporter().parseAsync(target, { binary: true });
  return new Blob([out as ArrayBuffer], { type: "model/gltf-binary" });
}

/* ------------------------------ actions ------------------------------------ */

export interface Step3D {
  at: number;
  do: string;
  dur?: number;
  [k: string]: unknown;
}

export function setKey(sp: Scene3D, name: string, frame: number, v: number, ease: Keyframe["ease"] = "inOut"): void {
  const list = (sp.keys[name] ??= []);
  const i = list.findIndex((k) => Math.abs(k.t - frame) < 1e-4);
  if (i >= 0) list[i] = { t: frame, v, ease };
  else list.push({ t: frame, v, ease });
  list.sort((a, b) => a.t - b.t);
}

export const EXPR3D = ["neutral", "smile", "angry", "surprised", "closed", "shout"];

/**
 * High-level character/object actions: any POSES3D name (blends into the pose), walk/run {dist}, jump {height},
 * turn {to}, moveTo {x,z}, punch, kick, wave, nod, expr {name}, spin {turns}, and "key" {prop, value}.
 */
export function act3D(sp: Scene3D, id: string, fps: number, steps: Step3D[]): number {
  const o = sp.objects.find((x) => x.id === id);
  if (!o) throw new Error(`No 3D object ${id}`);
  let end = 0;
  const F = (sec: number) => Math.round(sec * fps);
  const cur = (name: string, sec: number, base: number) => kv(sp, name, F(sec), base);
  const keyJ = (sec: number, p: Partial<Record<Joint, [number, number, number]>>, ease: Keyframe["ease"] = "inOut") => {
    for (const j of JOINTS) {
      const r = p[j] ?? [0, 0, 0];
      ["x", "y", "z"].forEach((ax, i) => setKey(sp, `${id}.j.${j}.${ax}`, F(sec), r[i]!, ease));
    }
  };
  const holdJ = (sec: number) => {
    for (const j of JOINTS) {
      const r = jointAt(sp, id, j, F(sec));
      ["x", "y", "z"].forEach((ax, i) => setKey(sp, `${id}.j.${j}.${ax}`, F(sec), r[i]!));
    }
  };
  const snapshot = (sec: number): Partial<Record<Joint, [number, number, number]>> => Object.fromEntries(JOINTS.map((j) => [j, jointAt(sp, id, j, F(sec))]));
  for (const st of [...steps].sort((a, b) => a.at - b.at)) {
    const t = st.at;
    const d = typeof st.dur === "number" ? st.dur : 0.5;
    const n = (k: string, def: number) => (typeof st[k] === "number" ? (st[k] as number) : def);
    if (POSES3D[st.do]) {
      holdJ(t);
      keyJ(t + d, POSES3D[st.do]!);
      end = Math.max(end, t + d);
      continue;
    }
    switch (st.do) {
      case "walk":
      case "run": {
        // Gait cycle while travelling `dist` metres along the facing direction.
        const run = st.do === "run";
        const dist = n("dist", run ? 6 : 3);
        const dd = typeof st.dur === "number" ? st.dur : dist / (run ? 4.5 : 1.4);
        const ry = cur(`${id}.ry`, t, o.rot[1]) * D2R;
        const x0 = cur(`${id}.px`, t, o.pos[0]);
        const z0 = cur(`${id}.pz`, t, o.pos[2]);
        const y0 = cur(`${id}.py`, t, o.pos[1]);
        const back = snapshot(t);
        holdJ(t);
        setKey(sp, `${id}.px`, F(t), x0, "linear");
        setKey(sp, `${id}.pz`, F(t), z0, "linear");
        setKey(sp, `${id}.px`, F(t + dd), x0 + Math.sin(ry) * dist, "linear");
        setKey(sp, `${id}.pz`, F(t + dd), z0 + Math.cos(ry) * dist, "linear");
        const stride = run ? 0.36 : 0.55;
        const steps = Math.max(2, Math.round(dd / stride));
        const sw = run ? 50 : 28;
        for (let i = 0; i <= steps; i++) {
          const ts = t + (i / steps) * dd;
          const s1 = i % 2 ? 1 : -1;
          keyJ(ts, {
            hipL: [-s1 * sw, 0, 3],
            hipR: [s1 * sw, 0, -3],
            kneeL: [s1 > 0 ? 8 : run ? 85 : 35, 0, 0],
            kneeR: [s1 < 0 ? 8 : run ? 85 : 35, 0, 0],
            shoulderL: [s1 * sw * 0.8, 0, 8],
            shoulderR: [-s1 * sw * 0.8, 0, -8],
            elbowL: [run ? -85 : -18, 0, 0],
            elbowR: [run ? -85 : -18, 0, 0],
            chest: [run ? 12 : 3, s1 * 6, 0],
            hips: [0, -s1 * 5, 0],
          });
          setKey(sp, `${id}.py`, F(ts), y0 + (run ? 0.06 : 0.025), "inOut");
          if (i < steps) setKey(sp, `${id}.py`, F(ts + dd / steps / 2), y0, "inOut");
        }
        keyJ(t + dd + 0.25, back);
        end = Math.max(end, t + dd + 0.25);
        break;
      }
      case "jump": {
        const h = n("height", 1);
        const y0 = cur(`${id}.py`, t, o.pos[1]);
        const back = snapshot(t);
        holdJ(t);
        keyJ(t + 0.15, POSES3D.crouch!);
        setKey(sp, `${id}.py`, F(t + 0.15), y0 - 0.25);
        setKey(sp, `${id}.py`, F(t + 0.45), y0 + h, "out");
        keyJ(t + 0.45, { ...POSES3D.cheer!, kneeL: [70, 0, 0], kneeR: [50, 0, 0], hipL: [-50, 0, 0], hipR: [-30, 0, 0] });
        setKey(sp, `${id}.py`, F(t + 0.8), y0, "in");
        keyJ(t + 0.85, POSES3D.crouch!);
        setKey(sp, `${id}.py`, F(t + 0.85), y0 - 0.2);
        setKey(sp, `${id}.py`, F(t + 1.1), y0);
        keyJ(t + 1.15, back);
        end = Math.max(end, t + 1.15);
        break;
      }
      case "turn": {
        setKey(sp, `${id}.ry`, F(t), cur(`${id}.ry`, t, o.rot[1]));
        setKey(sp, `${id}.ry`, F(t + d), n("to", 0));
        end = Math.max(end, t + d);
        break;
      }
      case "spin": {
        const r0 = cur(`${id}.ry`, t, o.rot[1]);
        setKey(sp, `${id}.ry`, F(t), r0);
        setKey(sp, `${id}.ry`, F(t + d), r0 + 360 * n("turns", 1));
        end = Math.max(end, t + d);
        break;
      }
      case "moveTo": {
        for (const [ax, k] of [["px", "x"], ["py", "y"], ["pz", "z"]] as const) {
          if (typeof st[k] !== "number") continue;
          const base = o.pos[ax === "px" ? 0 : ax === "py" ? 1 : 2];
          setKey(sp, `${id}.${ax}`, F(t), cur(`${id}.${ax}`, t, base));
          setKey(sp, `${id}.${ax}`, F(t + d), st[k] as number);
        }
        end = Math.max(end, t + d);
        break;
      }
      case "punch":
      case "kick": {
        const back = snapshot(t);
        holdJ(t);
        const pz = st.do === "kick" ? POSES3D.kick! : st.side === "L" ? POSES3D.punchL! : POSES3D.punchR!;
        keyJ(t + 0.1, POSES3D.guard!);
        keyJ(t + 0.2, pz, "out");
        keyJ(t + 0.35, pz);
        keyJ(t + 0.65, back);
        end = Math.max(end, t + 0.65);
        break;
      }
      case "wave": {
        const back = snapshot(t);
        holdJ(t);
        for (let i = 0; i < 4; i++) keyJ(t + 0.25 + i * 0.25, { ...POSES3D.wave!, elbowR: [0, 0, i % 2 ? 10 : -40] });
        keyJ(t + 1.5, back);
        end = Math.max(end, t + 1.5);
        break;
      }
      case "nod": {
        const r = jointAt(sp, id, "neck", F(t));
        setKey(sp, `${id}.j.neck.x`, F(t), r[0]);
        setKey(sp, `${id}.j.neck.x`, F(t + 0.2), r[0] + 18);
        setKey(sp, `${id}.j.neck.x`, F(t + 0.45), r[0]);
        end = Math.max(end, t + 0.45);
        break;
      }
      case "expr": {
        const i = Math.max(0, EXPR3D.indexOf(String(st.name ?? "neutral")));
        setKey(sp, `${id}.expr`, F(t), i, "hold");
        end = Math.max(end, t);
        break;
      }
      case "key": {
        const prop = String(st.prop);
        setKey(sp, `${id}.${prop}`, F(t + d), n("value", 0), (st.ease as Keyframe["ease"]) ?? "inOut");
        end = Math.max(end, t + d);
        break;
      }
      default:
        throw new Error(`Unknown 3D action "${st.do}". Actions: ${[...Object.keys(POSES3D), "walk", "run", "jump", "turn", "spin", "moveTo", "punch", "kick", "wave", "nod", "expr", "key"].join(", ")}`);
    }
  }
  return end;
}

/** Camera moves: orbit (around target), dolly (toward/away), crane (up/down), push (fov), shake. */
export function camMove(sp: Scene3D, fps: number, o: { move: string; start: number; dur: number; amount?: number; ease?: Keyframe["ease"] }): void {
  const F = (sec: number) => Math.round(sec * fps);
  const f0 = F(o.start),
    f1 = F(o.start + o.dur);
  const p = [kv(sp, "cam.px", f0, sp.camera.pos[0]), kv(sp, "cam.py", f0, sp.camera.pos[1]), kv(sp, "cam.pz", f0, sp.camera.pos[2])];
  const tg = [kv(sp, "cam.tx", f0, sp.camera.target[0]), kv(sp, "cam.ty", f0, sp.camera.target[1]), kv(sp, "cam.tz", f0, sp.camera.target[2])];
  const set = (n: string, a: number, b: number) => {
    setKey(sp, n, f0, a, "inOut");
    setKey(sp, n, f1, b, o.ease ?? "inOut");
  };
  const amt = o.amount;
  switch (o.move) {
    case "orbit": {
      const deg = (amt ?? 90) * D2R;
      const dx = p[0]! - tg[0]!,
        dz = p[2]! - tg[2]!;
      const r = Math.hypot(dx, dz);
      const a0 = Math.atan2(dx, dz);
      const n = Math.max(2, Math.ceil(Math.abs(deg) / (Math.PI / 8)));
      for (let i = 0; i <= n; i++) {
        const a = a0 + (deg * i) / n;
        const fr = Math.round(f0 + ((f1 - f0) * i) / n);
        setKey(sp, "cam.px", fr, tg[0]! + Math.sin(a) * r, "linear");
        setKey(sp, "cam.pz", fr, tg[2]! + Math.cos(a) * r, "linear");
      }
      break;
    }
    case "dolly": {
      const k = amt ?? 0.5;
      set("cam.px", p[0]!, p[0]! + (tg[0]! - p[0]!) * k);
      set("cam.py", p[1]!, p[1]! + (tg[1]! - p[1]!) * k);
      set("cam.pz", p[2]!, p[2]! + (tg[2]! - p[2]!) * k);
      break;
    }
    case "crane":
      set("cam.py", p[1]!, p[1]! + (amt ?? 2));
      break;
    case "truck":
      set("cam.px", p[0]!, p[0]! + (amt ?? 2));
      set("cam.tx", tg[0]!, tg[0]! + (amt ?? 2));
      break;
    case "push":
      set("cam.fov", kv(sp, "cam.fov", f0, sp.camera.fov), amt ?? 25);
      break;
    case "shake": {
      const a = amt ?? 0.06;
      for (let fr = f0; fr <= f1; fr += 2) {
        const k = 1 - (fr - f0) / Math.max(1, f1 - f0);
        setKey(sp, "cam.tx", fr, tg[0]! + Math.sin(fr * 2.7) * a * k, "linear");
        setKey(sp, "cam.ty", fr, tg[1]! + Math.cos(fr * 3.3) * a * k, "linear");
      }
      break;
    }
    default:
      throw new Error("Camera moves: orbit, dolly, crane, truck, push, shake");
  }
}
