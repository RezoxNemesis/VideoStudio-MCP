/**
 * The 3D model library of the Stage: hundreds of ready set pieces (buildings, furniture, vehicles, dungeon and castle
 * parts, food, space-base modules, trees and terrain) and any .glb / .gltf file of the person's own — placed on a stage
 * beside the characters with stage.prop. Models are loaded once and shared by every stage that uses them.
 */
import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import type { StageEnv } from "./spec";

export interface ModelEntry {
  id: string;
  pack: string;
  name: string;
  file: string;
  group: string;
  /** Width, height, depth in metres at scale 1 (already at the size of the Stage's people). */
  size: [number, number, number];
  kb: number;
}
export interface ModelPack {
  id: string;
  title: string;
  about: string;
  /** What the pack's own units are multiplied by so a door is door-sized next to a person. */
  scale: number;
  count: number;
  licence: string;
}
export interface ModelCatalogue {
  packs: ModelPack[];
  models: ModelEntry[];
}

const BASE = "stage-assets/models/";
let catalogue: Promise<ModelCatalogue> | null = null;
/** The library that comes with the app (empty where it is not installed). */
export function modelCatalogue(): Promise<ModelCatalogue> {
  catalogue ??= fetch(BASE + "index.json")
    .then((r) => (r.ok ? (r.json() as Promise<ModelCatalogue>) : { packs: [], models: [] }))
    .catch(() => ({ packs: [], models: [] }));
  return catalogue;
}

/** A model file on this computer → an address the window can load (set by the desktop app). */
let fileUrl: (path: string) => Promise<string> | string = (p) => p;
export function setModelFileUrl(fn: (path: string) => Promise<string> | string): void {
  fileUrl = fn;
}
export const isModelFile = (src: string): boolean => src.startsWith("/") || /^[A-Za-z]:[\\/]/.test(src);

interface Loaded {
  root: THREE.Object3D;
  scale: number;
  size: [number, number, number];
}
const loaded = new Map<string, Loaded>();
const pending = new Map<string, Promise<void>>();

async function load(src: string): Promise<void> {
  let url: string;
  let scale = 1;
  if (isModelFile(src)) url = await fileUrl(src);
  else {
    const cat = await modelCatalogue();
    const m = cat.models.find((x) => x.id === src);
    if (!m) throw new Error(`No model "${src}" in the library. Find models with stage.models {q}.`);
    url = BASE + m.file;
    scale = cat.packs.find((p) => p.id === m.pack)?.scale ?? 1;
  }
  let g: { scene: THREE.Group };
  try {
    g = await new GLTFLoader().loadAsync(url);
  } catch (e) {
    throw new Error(`The model "${src}" could not be read (${e instanceof Error ? e.message : String(e)}).`);
  }
  g.scene.traverse((o) => {
    const mesh = o as THREE.Mesh;
    if (mesh.isMesh) mesh.castShadow = mesh.receiveShadow = true;
  });
  g.scene.updateMatrixWorld(true);
  const s = new THREE.Box3().setFromObject(g.scene).getSize(new THREE.Vector3());
  const r2 = (n: number) => Math.round(n * scale * 100) / 100;
  loaded.set(src, { root: g.scene, scale, size: [r2(s.x), r2(s.y), r2(s.z)] });
}
/** Load the models named (library ids or files); those already loaded cost nothing. */
export async function ensureModels(srcs: string[]): Promise<void> {
  await Promise.all(
    [...new Set(srcs.filter(Boolean))].map((src) => {
      if (loaded.has(src)) return undefined;
      let p = pending.get(src);
      if (!p) {
        p = load(src).finally(() => pending.delete(src));
        pending.set(src, p);
      }
      return p;
    }),
  );
}
export const modelsLoaded = (srcs: string[]): boolean => srcs.every((s) => !s || loaded.has(s));
/** A fresh copy of a loaded model to stand in a scene (geometry and materials are shared). */
export function modelInstance(src: string): THREE.Object3D {
  const m = loaded.get(src);
  if (!m) throw new Error(`The model "${src}" is not loaded yet.`);
  const holder = new THREE.Group();
  const copy = m.root.clone(true);
  copy.scale.multiplyScalar(m.scale);
  holder.add(copy);
  holder.name = `model:${src}`;
  return holder;
}
export function modelSize(src: string): [number, number, number] | null {
  return loaded.get(src)?.size ?? null;
}
/** The models a stage's set uses. */
export const modelSrcs = (env: StageEnv): string[] => (env.items ?? []).filter((i) => i.kind === "model").map((i) => String(i.params?.src ?? ""));
