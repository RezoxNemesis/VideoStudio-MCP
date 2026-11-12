/**
 * Commands for the Blender forge: Claude (or a button) asks Blender on this PC to make a 3D asset — from a recipe, from
 * Blender Python written for the job, or by converting a model file — and stands it on a 3D Stage.
 */
import type { Studio } from "../../src/app/studio";
import { registerCommand, runQuiet } from "../../src/agent/api";
import { need, type ForgeResult } from "./bridge";

function forge() {
  const f = need().forge;
  if (!f) throw new Error("This version of VideoStudio Desktop has no Blender forge.");
  return f;
}
async function place(s: Studio, r: ForgeResult, a: { optStr(k: string): string | undefined; any(k: string): unknown; num(k: string, d?: number): number; optNum(k: string): number | undefined }) {
  const sceneId = a.optStr("sceneId");
  const made = { file: r.path, MB: Math.round(r.bytes / 1e4) / 100, size: r.size, triangles: r.triangles, objects: r.objects, seconds: r.seconds, blender: r.blender };
  if (!sceneId) return { ...made, next: "Stand it on a stage: stage.prop {sceneId, model: file, at: [x, z]}" };
  const p = await runQuiet(s, [{ op: "stage.prop", sceneId, model: r.path, at: a.any("at") ?? [0, 0], rot: a.optNum("rot") ?? 0, scale: a.optNum("scale") ?? 1, id: a.optStr("id") }]);
  if (!p[0]?.ok) throw new Error(`The model was made (${r.path}) but could not be put on the stage: ${p[0]?.error}`);
  return { ...made, onStage: p[0].result };
}

export function registerForgeCommands(s: Studio): void {
  registerCommand(
    "forge.status",
    "Is Blender on this PC, which version, the recipes the forge knows (with their options) and where finished models are kept.",
    async () => {
      const st = await forge().status(true);
      return { ...st, note: st.blender ? undefined : "Blender was not found. Install Blender (blender.org or the software centre); nothing else needs setting up." };
    },
  );
  registerCommand(
    "forge.make",
    '{recipe: rock|rocks|tree|bush|terrain|mountain|building|lathe|text|crystal|stairs|arch|fence|planet|cloud|furniture, params?: {…} (forge.status lists each recipe\'s options; colours are "#rrggbb", sizes in metres), name? (file name), sceneId?, at?: [x, z], rot?, scale?, id?} Blender builds a 3D asset from a recipe and saves it as a .glb in the Models folder; with sceneId it is also stood on that 3D Stage. Examples: {recipe:"tree", params:{kind:"pine", height:9, seed:4}} · {recipe:"text", params:{text:"OREZ", depth:0.3, color:"#e0b040"}} · {recipe:"building", params:{style:"tower", floors:12, lit:true}}.',
    async (st, a) => place(st, await forge().make(a.str("recipe"), a.optStr("name"), (a.any("params") as Record<string, unknown> | undefined) ?? {}), a),
  );
  registerCommand(
    "forge.script",
    '{name, code (Blender Python), params?, sceneId?, at?, rot?, scale?, id?} Make any 3D asset with Blender Python written for it. The code runs in an empty scene with `bpy`, `math`, `P` (your params) and `F`, the forge\'s helpers: F.mat(color, rough, metal, emit) · bm = bmesh.new(); F.box(bm, center, size, materialIndex) · F.cyl(bm, base, r1, r2, h, seg) · F.ball(bm, center, radius, sub, rough=lumps) · F.lathe(bm, [[radius, height] …]) · F.obj(name, bm, [materials]) · F.fbm(x, y) · F.import_any(path). Build things standing on z = 0 (Blender: Z up, metres); the scene is saved as <name>.glb by itself — do not export in the code.',
    async (st, a) => place(st, await forge().script(a.str("name"), a.str("code"), (a.any("params") as Record<string, unknown> | undefined) ?? {}), a),
  );
  registerCommand(
    "forge.convert",
    "{from: path of a model file (fbx, obj, stl, ply, dae, usd, blend, gltf), name?, sceneId?, at?, rot?, scale?, id?} Blender turns a model of another kind into a .glb the Stage can use.",
    async (st, a) => place(st, await forge().convert(a.str("from"), a.optStr("name")), a),
  );
  registerCommand("forge.list", "The 3D models made or converted so far (the Models folder).", async () => forge().list());
}
