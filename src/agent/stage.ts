/**
 * Stage commands: cast real human characters, give them motion-capture performances, choreograph, place cameras and
 * review the result, all as JSON. The Stage lives in an animation scene as a layer, so it sits on the timeline like
 * any clip (grade it, cut it, add titles, render it).
 *
 * Typical order: stage.new → stage.actor (one per character) → stage.do (walks, clips, poses) → stage.overlay
 * (looks, reaches, faces) → stage.cam (shots) → stage.sheet / stage.check (review) → render.
 */
import type { Studio } from "../app/studio";
import * as anim from "../anim/ops";
import * as ops from "../core/ops";
import type { AnimLayer, AnimScene, Project } from "../core/model";
import { ctx2d, makeCanvas, renderScene } from "../anim/render";
import { toJpeg } from "../render/snapshot";
import { registerCommand, type HostArgs } from "./api";
import { ENV_PRESETS, TIMES } from "../stage/env";
import { loadStage, preloadStage, runtimeOf, stageError } from "../stage/draw";
import { ensureModels, modelCatalogue, modelSize } from "../stage/models";
import { motions } from "../stage/motion/library";
import { BEARDS, BODIES, CAST, HAIRS, ORIGINS, OUTFITS, makeChar, type CharRequest } from "../stage/presets";
import { FACE_SLIDERS, MOODS, StageRuntime, getFilm, pickGait, stageAssets } from "../stage/runtime";
import { findActor, newStage, sid, sortBlocks, stageDuration, type Block, type BodyMask, type KeyTrack, type Overlay, type Shot, type ShotKind, type StageActor, type StageFx, type StageSpec, type TargetRef, type Vec3 } from "../stage/spec";
import { SW } from "../stage/engine/fight.js";
import { compileFight, FIGHT_MOVES, type FightBeat } from "../stage/choreo";
import { readTone, sayToAsset } from "./voiceopen";

type Args = HostArgs;
const r3 = (n: number) => Math.round(n * 1000) / 1000;
const r2 = (n: number) => Math.round(n * 100) / 100;

const SHOT_KINDS: ShotKind[] = ["free", "wide", "full", "medium", "close", "extreme", "low", "high", "top", "ots", "two", "follow"];
const MASKS: BodyMask[] = ["upper", "lower", "arms", "armL", "armR", "head", "spine", "legs"];
const FX_KINDS: StageFx["kind"][] = ["fire", "smoke", "embers", "sparks", "dust", "shockwave", "impact", "flash"];

function stageLayer(p: Project, a: Args): { sc: AnimScene; l: AnimLayer; spec: StageSpec } {
  const sc = anim.getScene(p, a.str("sceneId"));
  const id = a.optStr("layerId");
  const l = id ? anim.getLayer(sc, id) : sc.layers.find((x) => x.kind === "stage");
  if (!l || l.kind !== "stage" || !l.stage) throw new ops.OpError("That scene has no Stage layer (stage.new makes one).");
  return { sc, l, spec: l.stage };
}
/** Keep the scene (and its timeline clips) long enough for what happens on the stage. */
function fitLength(p: Project, sc: AnimScene, spec: StageSpec, minSeconds = 0): void {
  const old = sc.frames / sc.fps;
  const need = Math.max(minSeconds, stageDuration(spec));
  const frames = Math.max(sc.frames, Math.ceil(need * sc.fps) + 1);
  if (frames === sc.frames) return;
  sc.frames = frames;
  for (const t of p.tracks) for (const c of t.clips) if (c.sceneId === sc.id && Math.abs(c.dur - old) < 2 / sc.fps) c.dur = frames / sc.fps;
}
function vec3(v: unknown, what: string, d?: Vec3): Vec3 {
  if (v === undefined || v === null) {
    if (d) return d;
    throw new ops.OpError(`Missing ${what}.`);
  }
  if (!Array.isArray(v) || v.length < 2 || v.some((x) => !Number.isFinite(Number(x)))) throw new ops.OpError(`${what} must be [x, z] or [x, y, z] in metres.`);
  return v.length === 2 ? [Number(v[0]), 0, Number(v[1])] : [Number(v[0]), Number(v[1]), Number(v[2])];
}
function targetRef(v: unknown, what: string): TargetRef {
  if (v === "camera") return "camera";
  if (typeof v === "string") return { actor: v };
  if (Array.isArray(v)) return vec3(v, what);
  if (v && typeof v === "object" && typeof (v as { actor?: unknown }).actor === "string") return v as TargetRef;
  throw new ops.OpError(`${what} must be an actor id, {actor, bone?, offset?}, "camera" or [x, y, z].`);
}
function needClip(name: string): string {
  if (!motions.has(name)) {
    const near = motions.search({ q: name.replace(/[_\d]+/g, " ").trim(), limit: 5 }).map((m) => `${m.name} (${m.meta.desc})`);
    throw new ops.OpError(`No motion "${name}".${near.length ? ` Close: ${near.join("; ")}.` : ""} Search with stage.motions.`);
  }
  return name;
}
const actorIds = (spec: StageSpec) => spec.actors.map((x) => x.id);

async function ready(spec: StageSpec, layerId: string): Promise<StageRuntime> {
  await loadStage(spec);
  const rt = runtimeOf(layerId);
  if (!rt) throw new ops.OpError(`The 3D stage could not start on this device. ${stageError()}`);
  rt.sync(spec);
  return rt;
}

function summary(sc: AnimScene, l: AnimLayer, spec: StageSpec) {
  return {
    sceneId: sc.id,
    layerId: l.id,
    seconds: r3(sc.frames / sc.fps),
    fps: sc.fps,
    env: spec.env,
    look: spec.look,
    actors: spec.actors.map((x) => ({
      id: x.id,
      at: x.at,
      height: x.char.height,
      kit: x.kit,
      props: (x.props ?? []).map((q) => `${q.kind}${q.hold ? `@${q.hold}` : ""}`),
      blocks: x.blocks.map((b) => ({ id: b.id, t: r3(b.t), kind: b.kind, ...(b.kind === "clip" ? { clip: b.clip, in: b.in, out: b.out, rate: b.rate, loop: b.loop, mirror: b.mirror } : b.kind === "go" ? { to: b.to, gait: b.gait, clip: b.clip } : b.kind === "pose" ? { pose: b.pose } : b.kind === "stance" ? { lead: b.lead, guard: b.guard, path: b.path, lunges: b.lunges, ducks: b.ducks } : b.kind === "fall" ? { prone: b.prone, push: b.push } : { clip: b.clip }) })),
      overlays: x.overlays.map((o) => ({ id: o.id, kind: o.kind, t0: r3(o.t0), t1: r3(o.t1) })),
      lines: x.lines.map((ln) => ({ id: ln.id, t: r3(ln.t), dur: r3(ln.dur), text: ln.text })),
    })),
    shots: spec.shots.map((x) => ({ id: x.id, t: r3(x.t), dur: r3(x.dur), kind: x.kind, on: x.on, other: x.other, move: x.move?.kind })),
    fx: spec.fx.map((x) => ({ id: x.id, kind: x.kind, t0: r3(x.t0), t1: r3(x.t1) })),
  };
}

/** Tile frames of a scene into one picture (for review). */
async function sheetOf(s: Studio, sc: AnimScene, times: number[], cols: number, cell: number): Promise<string> {
  await preloadStage(sc);
  const k = cell / sc.width;
  const cw = Math.round(sc.width * k), ch = Math.round(sc.height * k);
  const rows = Math.ceil(times.length / cols);
  const out = makeCanvas(cw * cols, (ch + 18) * rows);
  const oc = ctx2d(out);
  oc.fillStyle = "#0b0d12";
  oc.fillRect(0, 0, out.width, out.height);
  const tile = makeCanvas(cw, ch);
  const tc = ctx2d(tile);
  for (let i = 0; i < times.length; i++) {
    const t = times[i]!;
    renderScene(tc, sc, Math.round(t * sc.fps), { scale: k, images: (id) => s.pool.imageNow(id) });
    const x = (i % cols) * cw, y = Math.floor(i / cols) * (ch + 18);
    oc.drawImage(tile as CanvasImageSource, x, y);
    oc.fillStyle = "#ffd76a";
    oc.font = "bold 13px system-ui, sans-serif";
    oc.fillText(`${t.toFixed(2)}s`, x + 5, y + ch + 13);
  }
  return toJpeg(out, 0.8);
}

/** What stage.bake writes when nothing is asked for: enough of the body to draw it from any side. */
const BAKE_JOINTS = [
  "root", "spine05", "spine03", "spine02", "spine01", "neck01", "neck03", "head", "head:tail", "eye.L", "eye.R", "jaw:tail",
  "clavicle.L", "upperarm01.L", "lowerarm01.L", "wrist.L", "finger2-1.L", "finger3-1.L", "finger5-1.L", "finger3-3.L:tail", "finger1-3.L:tail",
  "clavicle.R", "upperarm01.R", "lowerarm01.R", "wrist.R", "finger2-1.R", "finger3-1.R", "finger5-1.R", "finger3-3.R:tail", "finger1-3.R:tail",
  "upperleg01.L", "lowerleg01.L", "foot.L", "toe2-1.L", "toe2-3.L:tail",
  "upperleg01.R", "lowerleg01.R", "foot.R", "toe2-1.R", "toe2-3.R:tail",
];
const BAKE_QUATS = ["root", "spine01", "head", "wrist.L", "wrist.R", "foot.L", "foot.R"];

export function registerStageCommands(): void {
  registerCommand(
    "stage.new",
    `{name?, seconds?=10, fps?=24, env?: ${ENV_PRESETS.join("|")} (=studio), time?: ${Object.keys(TIMES).join("|")}, look?: cinematic|anime, aspect? (2.39 for scope bars), sceneId? (add to an existing scene), toTimeline?=true, start?} New 3D Stage: real human characters with motion-capture performances, cameras and a cinematic or anime look, as a scene on the timeline. Metres, y up; characters face +Z at yaw 0. Returns {sceneId, layerId, clipId}. Next: stage.actor, stage.do, stage.cam, then stage.sheet to look.`,
    (s, a) =>
      s.mutate("new stage", (p) => {
        const fps = a.num("fps", 24);
        const seconds = Math.max(0.5, a.num("seconds", 10));
        let sc: AnimScene;
        if (a.optStr("sceneId")) sc = anim.getScene(p, a.str("sceneId"));
        else {
          sc = anim.createScene(p, { name: a.str("name", "Stage"), width: p.width, height: p.height, fps, frames: Math.round(seconds * fps), background: "#000000" });
          sc.layers = [];
        }
        const l = anim.addLayer(sc, "Stage", "stage");
        sc.layers.splice(sc.layers.indexOf(l), 1);
        sc.layers.push(l);
        const spec = newStage();
        const env = a.str("env", "studio");
        if (!(ENV_PRESETS as readonly string[]).includes(env)) throw new ops.OpError(`env must be one of ${ENV_PRESETS.join(", ")}`);
        spec.env = { preset: env as StageSpec["env"]["preset"], time: (a.optStr("time") as StageSpec["env"]["time"]) ?? undefined };
        spec.look = { style: a.str("look", "cinematic") === "anime" ? "anime" : "cinematic" };
        if (a.optNum("aspect")) spec.look.aspect = a.num("aspect");
        l.stage = spec;
        let clipId: string | undefined;
        if (a.bool("toTimeline", true) && !a.optStr("sceneId")) clipId = ops.addClip(p, { sceneId: sc.id, start: a.optNum("start") ?? 0 }).id;
        return { sceneId: sc.id, layerId: l.id, clipId, seconds: r3(sc.frames / sc.fps) };
      }),
  );

  registerCommand("stage.get", "{sceneId} The stage as it stands: actors with their blocks and overlays, shots, set and look (ids for later commands).", (s, a) => {
    const { sc, l, spec } = stageLayer(s.project, a);
    return summary(sc, l, spec);
  });

  registerCommand(
    "stage.presets",
    "Everything you can pick from: character presets, body types, origins, outfits, hair, beards, kits, moods, face sliders, sets, times of day, shot kinds, camera moves, sword positions, fight moves, body masks, effects.",
    () => ({
      characters: Object.keys(CAST),
      bodies: Object.keys(BODIES),
      origins: Object.keys(ORIGINS),
      outfits: Object.keys(OUTFITS),
      hair: Object.keys(HAIRS),
      beards: ["none", ...Object.keys(BEARDS)],
      kits: ["orez", "teen", "boy", "sena", "ruon", "elder", "garrick", "vess", "soldier", "kaito", "aldric"],
      props: ["sword", "staff", "spear", "shield", "bucket"],
      moods: Object.keys(MOODS),
      face: FACE_SLIDERS,
      sets: ENV_PRESETS,
      times: Object.keys(TIMES),
      weather: ["clear", "rain", "snow", "mist", "embers", "motes"],
      shots: SHOT_KINDS,
      moves: ["push", "pull", "orbit", "truck", "crane", "handheld", "whip"],
      gaits: ["walk", "stroll", "march", "jog", "run", "sneak"],
      poses: ["sit", "kneel", "tpose", "clip:<name>@<seconds>", ...Object.keys(SW)],
      swordPositions: Object.keys(SW),
      fightMoves: Object.keys(FIGHT_MOVES),
      masks: MASKS,
      fx: FX_KINDS,
    }),
  );

  registerCommand(
    "stage.set",
    `{sceneId, env?: {preset?, time?, sunYaw?, sunElev?, fog? (multiplier), seed?, weather?, ground? (#hex, studio), items?: [{kind: house|tree|rock|torch|banner|lantern|fence|stall|well|gate|stairs, pos:[x,z], rot?, scale?}]}, look?: {style?: cinematic|anime, step? (2 = animate on twos), exposure?, bloom?, vignette?, grain?, sat?, contrast?, ink? (0..1), inkWidth?, shadeAt?, shadeSoft?, aspect?}, seconds?} Change the set, the time of day, the look or the length.`,
    (s, a) =>
      s.mutate("stage settings", (p) => {
        const { sc, l, spec } = stageLayer(p, a);
        const env = a.any("env") as Partial<StageSpec["env"]> | undefined;
        if (env) {
          if (env.preset && !(ENV_PRESETS as readonly string[]).includes(env.preset)) throw new ops.OpError(`env.preset must be one of ${ENV_PRESETS.join(", ")}`);
          if (env.time && !TIMES[env.time]) throw new ops.OpError(`env.time must be one of ${Object.keys(TIMES).join(", ")}`);
          if (env.items) env.items = env.items.map((it) => ({ ...it, pos: vec3(it.pos, "item pos") }));
          spec.env = { ...spec.env, ...env };
        }
        const look = a.any("look") as Partial<StageSpec["look"]> | undefined;
        if (look) spec.look = { ...spec.look, ...look };
        fitLength(p, sc, spec, a.optNum("seconds") ?? 0);
        if (a.optNum("seconds")) {
          const frames = Math.max(Math.ceil(stageDuration(spec) * sc.fps) + 1, Math.round(a.num("seconds") * sc.fps));
          const old = sc.frames / sc.fps;
          sc.frames = frames;
          for (const t of p.tracks) for (const c of t.clips) if (c.sceneId === sc.id && Math.abs(c.dur - old) < 2 / sc.fps) c.dur = frames / sc.fps;
        }
        return summary(sc, l, spec);
      }),
  );

  registerCommand(
    "stage.models",
    "{q? (words: car, sofa, wall, tree, barrel …), pack?, limit?=60} The 3D model library that comes with the app: hundreds of ready set pieces in packs (city, furniture, dungeon, restaurant, space, medieval, prototype, halloween), each at the size of the Stage's people. Put one on a stage with stage.prop.",
    async (_s, a) => {
      const cat = await modelCatalogue();
      const q = (a.optStr("q") ?? "").toLowerCase().split(/[\s,]+/).filter(Boolean), pack = a.optStr("pack");
      if (pack && !cat.packs.some((p) => p.id === pack)) throw new ops.OpError(`Packs: ${cat.packs.map((p) => p.id).join(", ") || "none installed"}`);
      const hit = cat.models.filter((m) => (!pack || m.pack === pack) && q.every((w) => `${m.id} ${m.name} ${m.group}`.toLowerCase().includes(w)));
      const limit = Math.max(1, Math.min(400, a.num("limit", 60)));
      return {
        packs: cat.packs.map((p) => ({ id: p.id, title: p.title, about: p.about, models: p.count, licence: p.licence })),
        found: hit.length,
        models: (q.length || pack ? hit : hit.filter((_m, i) => i % Math.max(1, Math.round(hit.length / limit)) === 0)).slice(0, limit).map((m) => ({ id: m.id, size: m.size, group: m.group || undefined })),
        note: q.length || pack ? undefined : "A sample across all packs; give q or pack to see more. Sizes are width, height, depth in metres.",
      };
    },
  );

  registerCommand(
    "stage.prop",
    "{sceneId, model: a library id (stage.models) or the path of a .glb / .gltf file on this PC (one made with forge.make, or any other), at: [x, z] | [x, y, z], rot?=0 (degrees), scale?=1, id? (a name: giving the same name again moves or replaces it)} | {sceneId, remove: id | \"all\"} | {sceneId} (list) Stand a 3D model on the stage beside the characters: furniture, buildings, vehicles, walls, anything. It is lit and shadowed like the rest of the set.",
    async (s, a) => {
      const rm = a.optStr("remove"), src = a.optStr("model");
      if (src) await ensureModels([src]);
      return s.mutate("stage prop", (p) => {
        const { spec } = stageLayer(p, a);
        const items = [...(spec.env.items ?? [])];
        const isM = (x: (typeof items)[number]) => x.kind === "model";
        const list = () => items.filter(isM).map((x) => ({ id: String(x.params?.id ?? ""), model: String(x.params?.src ?? ""), at: x.pos, rot: x.rot ?? 0, scale: x.scale ?? 1 }));
        if (rm) {
          const keep = items.filter((x) => !isM(x) || (rm !== "all" && String(x.params?.id) !== rm));
          if (keep.length === items.length) throw new ops.OpError(`No model called "${rm}" on this stage. There: ${list().map((x) => x.id).join(", ") || "none"}`);
          spec.env = { ...spec.env, items: keep };
          return { removed: items.length - keep.length, props: keep.filter(isM).length };
        }
        if (!src) return { props: list() };
        let id = a.optStr("id");
        if (!id) {
          const base = src.split("/").pop()!.replace(/\.(glb|gltf)$/i, "").replace(/[^\w-]+/g, "_").slice(0, 24);
          let n = 1;
          while (items.some((x) => isM(x) && x.params?.id === (n === 1 ? base : `${base}${n}`))) n++;
          id = n === 1 ? base : `${base}${n}`;
        }
        const scale = Math.max(0.001, a.num("scale", 1));
        const it = { kind: "model", pos: vec3(a.any("at") ?? [0, 0], "at"), rot: a.num("rot", 0), scale, params: { src, id } };
        const i = items.findIndex((x) => isM(x) && x.params?.id === id);
        if (i >= 0) items[i] = it;
        else items.push(it);
        spec.env = { ...spec.env, items };
        const size = modelSize(src);
        return { id, model: src, at: it.pos, rot: it.rot, scale, size: size ? size.map((v) => Math.round(v * scale * 100) / 100) : undefined, props: items.filter(isM).length };
      });
    },
  );

  registerCommand(
    "stage.actor",
    `{sceneId, id, preset?: ${Object.keys(CAST).join("|")}, body?: ${Object.keys(BODIES).join("|")}, origin?: ${Object.keys(ORIGINS).join("|")}, outfit?: ${Object.keys(OUTFITS).join("|")}, hair?: ${Object.keys(HAIRS).join("|")}, beard?: none|${Object.keys(BEARDS).join("|")}, height? (m), age? (years), gender? (0 female..1 male), muscle?, weight? (0..1), proportions?, detail?: {"nose/nose-hump-incr": 0.3, …} (stage.targets lists them), colors?: {skin, hair, eye, lip, tunic, trousers, skirt, coat, shoes, boots, trim, sash…: "#hex"}, look?: {age, blush, scar, beard (stubble shading 0..1), grey, pale, dirt, worn, eyeGlow…}, kit?: orez|soldier|garrick|… (ready props + cape + hair tail), props?: [{kind: sword|staff|spear|shield|bucket, hold?: R|L|hip}], at?: [x, z], yaw? (deg), mood?: ${Object.keys(MOODS).join("|")} or {slider: 0..1}, scale?} Add a character, or reshape one that exists (same id keeps its performance). Bodies are sculpted live from age, build, height and face details.`,
    async (s, a) => {
      await stageAssets.load();
      return s.mutate("stage actor", (p) => {
        const { sc, spec } = stageLayer(p, a);
        const id = a.str("id").trim();
        if (!/^[\w-]{1,24}$/.test(id)) throw new ops.OpError("id should be a short name like hero, rival, girl1.");
        const existing = spec.actors.find((x) => x.id === id);
        const req: CharRequest = {};
        for (const k of ["preset", "body", "origin", "outfit", "hair", "beard"] as const) if (a.optStr(k)) req[k] = a.str(k);
        for (const k of ["height", "age", "gender", "muscle", "weight", "proportions"] as const) if (a.optNum(k) !== undefined) req[k] = a.num(k);
        if (a.any("detail")) req.detail = a.obj("detail") as Record<string, number>;
        if (a.any("colors")) req.colors = a.obj("colors") as CharRequest["colors"];
        if (a.any("look")) req.look = a.obj("look");
        const reshape = Object.keys(req).length > 0;
        let made: ReturnType<typeof makeChar> | null = null;
        if (!existing || reshape) {
          if (existing && !req.preset && !req.body) {
            // tweak the existing body rather than starting over
            const cur = existing.char;
            made = makeChar({ ...req, garments: req.outfit ? undefined : cur.garments, body: undefined });
            const merged = JSON.parse(JSON.stringify(cur)) as StageActor["char"];
            if (req.outfit) {
              merged.garments = made.char.garments;
              merged.look = { ...merged.look, colors: (made.char.look as { colors?: unknown } | undefined)?.colors, trim: made.char.look?.trim, sash: made.char.look?.sash };
            }
            if (req.hair) merged.hair = { ...made.char.hair, beard: merged.hair?.beard };
            if (req.beard) merged.hair = { ...(merged.hair ?? {}), beard: made.char.hair?.beard };
            if (req.origin) {
              merged.macro.race = made.char.macro.race;
              merged.look = { ...merged.look, skin: made.char.look?.skin, hair: made.char.look?.hair, eye: made.char.look?.eye };
            }
            for (const k of ["age", "gender", "muscle", "weight", "proportions"] as const) if (req[k] !== undefined) merged.macro[k] = req[k]!;
            if (req.height) merged.height = req.height;
            if (req.detail) merged.detail = { ...(merged.detail ?? {}), ...req.detail };
            if (req.colors || req.look) {
              const lk = (merged.look ??= {});
              const cols = ((lk.colors as Record<string, unknown> | undefined) ?? (lk.colors = {})) as Record<string, unknown>;
              const mk = (made.char.look ?? {}) as Record<string, unknown>;
              for (const k of Object.keys(req.colors ?? {})) {
                if (["skin", "hair", "eye", "lip", "trim", "sash"].includes(k)) lk[k] = mk[k];
                else cols[k] = (mk.colors as Record<string, unknown>)[k];
              }
              Object.assign(lk, req.look ?? {});
            }
            made = { char: merged, kit: existing.kit };
          } else made = makeChar(req);
          const unknown = Object.keys(made.char.detail ?? {}).filter((k) => !stageAssets.factory!.meta.targets[k]);
          if (unknown.length) throw new ops.OpError(`Unknown detail target(s): ${unknown.join(", ")}. stage.targets lists them.`);
        }
        const at = a.any("at") !== undefined ? vec3(a.any("at"), "at") : (existing?.at.pos ?? ([spec.actors.length * 1.2, 0, 0] as Vec3));
        const moodIn = a.any("mood");
        let mood = existing?.mood;
        if (typeof moodIn === "string") {
          if (!MOODS[moodIn]) throw new ops.OpError(`mood must be one of ${Object.keys(MOODS).join(", ")} or {slider: value}`);
          mood = MOODS[moodIn];
        } else if (moodIn && typeof moodIn === "object") mood = moodIn as Record<string, number>;
        const props = a.any("props") as StageActor["props"] | undefined;
        const actor: StageActor = {
          id,
          char: made?.char ?? existing!.char,
          kit: a.optStr("kit") !== undefined ? (a.str("kit") === "none" ? undefined : a.str("kit")) : (made?.kit ?? existing?.kit),
          props: props ?? existing?.props,
          at: { pos: at, yaw: a.optNum("yaw") ?? existing?.at.yaw ?? 0 },
          blocks: existing?.blocks ?? [],
          overlays: existing?.overlays ?? [],
          lines: existing?.lines ?? [],
          scale: a.optNum("scale") ?? existing?.scale,
          mood,
        };
        if (existing) spec.actors[spec.actors.indexOf(existing)] = actor;
        else spec.actors.push(actor);
        fitLength(p, sc, spec);
        return { id, height: actor.char.height, at: actor.at, kit: actor.kit ?? null, garments: (actor.char.garments ?? []).map((g) => g.name ?? g.kind), actors: actorIds(spec) };
      });
    },
  );

  registerCommand("stage.targets", '{prefix?: "nose/" | "eyes/" | "mouth/" | "head/" | "chin/" | "cheek/" | "ears/" | "torso/" | "armslegs/" | …} Names of the body and face detail targets (for stage.actor detail).', async (_s, a) => {
    await stageAssets.load();
    const list = stageAssets.factory!.targets(a.str("prefix", ""));
    return a.optStr("prefix") ? list : { groups: [...new Set(list.map((t) => t.split("/")[0]))], count: list.length, hint: 'Pass a prefix such as "nose/" to list one group.' };
  });

  registerCommand(
    "stage.motions",
    "{q? (words: \"kick\", \"sit down\", \"walk turn left\", \"dance salsa\"), tags?: [walk|run|jump|turn|idle|sit|kneel|fight|punch|kick|sword|fall|getup|dance|gesture|talk|emotion|climb|crawl|carry|sport|acrobat|interact|everyday|animal|style…], minDur?, maxDur?, speed?: [min, max] (m/s), straight?, inPlace?, subject?, limit?=30} Search the motion library (2,500+ captured performances). Returns name, description, seconds, speed, travel, turn. See one with stage.motion.",
    async (_s, a) => {
      await motions.load();
      const res = motions.search({ q: a.optStr("q"), tags: a.any("tags") as string[] | undefined, minDur: a.optNum("minDur"), maxDur: a.optNum("maxDur"), speed: a.any("speed") as [number, number] | undefined, straight: a.bool("straight"), inPlace: a.bool("inPlace"), subject: a.optNum("subject"), limit: a.num("limit", 30) });
      return res.map((m) => ({ clip: m.name, desc: m.meta.desc, seconds: m.meta.dur, speed: m.meta.speed, travel: m.meta.travel, turn: m.meta.turn, tags: m.meta.tags.join(" ") }));
    },
  );

  registerCommand(
    "stage.motion",
    "{clip, count?=10, from?, to?, preset?=swordsman, mirror?, cell?=220} Look at a motion: a strip of the performance at even moments (labelled with clip seconds), so you can choose in/out points.",
    async (_s, a) => {
      await stageAssets.load();
      const name = needClip(a.str("clip"));
      const meta = motions.meta(name);
      const film = getFilm();
      if (!film) throw new ops.OpError(`The 3D stage could not start on this device. ${stageError()}`);
      const from = a.num("from", 0), to = Math.min(meta.dur, a.num("to", meta.dur));
      const n = Math.max(2, Math.min(24, Math.round(a.num("count", 10))));
      const spec = newStage();
      const made = makeChar({ preset: a.str("preset", "swordsman") });
      spec.actors.push({ id: "m", char: made.char, at: { pos: [0, 0, 0], yaw: 0 }, blocks: [{ id: "b", t: 0, kind: "clip", clip: name, in: from, out: to, mirror: a.bool("mirror") }], overlays: [], lines: [] });
      spec.shots.push({ id: "s", t: 0, dur: 1e6, kind: "full", on: "m", yaw: 35 });
      await loadStage(spec);
      const rt = new StageRuntime(film);
      try {
        rt.sync(spec);
        const cell = Math.round(a.num("cell", 220));
        const ch = Math.round(cell * 1.25);
        const cols = Math.min(n, 6);
        const rows = Math.ceil(n / cols);
        const out = makeCanvas(cell * cols, (ch + 18) * rows);
        const oc = ctx2d(out);
        oc.fillStyle = "#0b0d12";
        oc.fillRect(0, 0, out.width, out.height);
        for (let i = 0; i < n; i++) {
          const ct = from + ((to - from) * i) / (n - 1);
          rt.evaluate(spec, ct - from, 1 / 30);
          const img = rt.render(cell, ch, ct);
          const x = (i % cols) * cell, y = Math.floor(i / cols) * (ch + 18);
          oc.drawImage(img, x, y, cell, ch);
          oc.fillStyle = "#ffd76a";
          oc.font = "bold 13px system-ui, sans-serif";
          oc.fillText(`${ct.toFixed(2)}s`, x + 5, y + ch + 13);
        }
        return { clip: name, desc: meta.desc, seconds: meta.dur, speed: meta.speed, travel: meta.travel, turn: meta.turn, tags: meta.tags, jpeg: await toJpeg(out, 0.8) };
      } finally {
        rt.dispose();
      }
    },
  );

  registerCommand(
    "stage.do",
    `{sceneId, actor, actions: [ {t, clip, in?, out?, rate?, mirror?, loop?, inPlace?, warp?: [[sec, clipSec], …]} | {t, go: [x, z], gait?: walk|stroll|march|jog|run|sneak, speed? (m/s), face? (deg), clip?} | {t, idle: true, clip?} | {t, pose: sit|kneel|"clip:<name>@<sec>"|<sword position>|{bone: [x,y,z] deg}} | {t, stance: {lead?: L|R|none, guard?: fists|open, length?, width?, drop?, blade? (deg), bounce? (0..1), path?: [[sec, right, forward, turnDeg?], …] (footwork: the feet step there one after the other), lunges?: [[sec, reach (m; negative retreats), out?, back?, hold?], …], ducks?: [[sec, depth (m), down?, up?], …]}} (planted feet solved by IK: fighting and ready stances) | {t, fall: {prone?, push? (m), dur?}} (knocked down) ], each with blend? (s, crossfade from the previous action, default 0.3) and at?: {pos?: [x,z], yaw?} to jump instead of continuing; replace?: false|true (drop the actor's actions from the first t on)} Give a character their performance. Actions follow one another: each starts where the last one left the body (place and facing), so walks, clips and turns chain naturally. Returns each action's id and, for walks, when the character arrives; "ends" says where the body is at the end.`,
    async (s, a) => {
      await stageAssets.load();
      const raw = a.any("actions");
      if (!Array.isArray(raw) || !raw.length) throw new ops.OpError('"actions" must be a list like [{t: 0, go: [2, 3]}, {t: 3, clip: "02_05"}].');
      const blocks: Block[] = [];
      for (const x of raw as Record<string, unknown>[]) {
        const t = Number(x.t);
        if (!Number.isFinite(t) || t < 0) throw new ops.OpError("Every action needs a start time t (seconds).");
        const base = { id: sid("b"), t, blend: typeof x.blend === "number" ? x.blend : undefined, at: x.at ? { pos: (x.at as { pos?: unknown }).pos !== undefined ? vec3((x.at as { pos?: unknown }).pos, "at.pos") : undefined, yaw: (x.at as { yaw?: number }).yaw } : undefined };
        if (typeof x.clip === "string" && x.go === undefined && !x.idle) {
          const name = needClip(x.clip);
          const m = motions.meta(name);
          const cin = Number(x.in ?? 0), cout = Number(x.out ?? m.dur);
          if (!(cout > cin)) throw new ops.OpError(`${name}: "out" must be after "in" (the clip is ${m.dur}s long).`);
          blocks.push({ ...base, kind: "clip", clip: name, in: x.in !== undefined ? cin : undefined, out: x.out !== undefined ? Math.min(cout, m.dur) : undefined, rate: typeof x.rate === "number" ? x.rate : undefined, mirror: !!x.mirror || undefined, loop: !!x.loop || undefined, inPlace: !!x.inPlace || undefined, warp: Array.isArray(x.warp) ? (x.warp as [number, number][]) : undefined });
        } else if (x.go !== undefined) {
          const to = vec3(x.go, "go");
          const gait = typeof x.gait === "string" ? x.gait : "walk";
          const clip = typeof x.clip === "string" ? needClip(x.clip) : pickGait(gait, typeof x.speed === "number" ? x.speed : undefined);
          blocks.push({ ...base, kind: "go", to: [to[0], to[2]], gait, clip, speed: typeof x.speed === "number" ? x.speed : undefined, face: typeof x.face === "number" ? x.face : undefined });
        } else if (x.idle) blocks.push({ ...base, kind: "idle", clip: typeof x.clip === "string" ? needClip(x.clip) : undefined, in: typeof x.in === "number" ? x.in : undefined });
        else if (x.pose !== undefined) {
          const pose = x.pose as string | Record<string, Vec3>;
          if (typeof pose === "string" && pose.startsWith("clip:")) needClip(pose.slice(5).split("@")[0]!);
          else if (typeof pose === "string" && !["sit", "kneel", "tpose"].includes(pose) && !(SW as Record<string, unknown>)[pose]) throw new ops.OpError(`Unknown pose "${pose}". Poses: sit, kneel, tpose, clip:<name>@<sec>, ${Object.keys(SW).join(", ")}, or {bone: [x,y,z]}.`);
          blocks.push({ ...base, kind: "pose", pose });
        } else if (x.stance !== undefined) {
          const st = (typeof x.stance === "object" && x.stance ? x.stance : {}) as Record<string, unknown>;
          const num = (k: string) => (typeof st[k] === "number" ? (st[k] as number) : undefined);
          const rows = (k: string, min: number) => {
            const v = st[k];
            if (v === undefined) return undefined;
            if (!Array.isArray(v) || v.some((r) => !Array.isArray(r) || r.length < min || r.some((n) => typeof n !== "number"))) throw new ops.OpError(`stance.${k} must be a list of number rows, like [[1.2, 0.35]].`);
            return v as number[][];
          };
          const lead = st.lead === "R" || st.lead === "none" ? st.lead : "L";
          const guard = st.guard === "fists" || st.guard === "open" ? st.guard : undefined;
          blocks.push({ ...base, kind: "stance", lead, length: num("length"), width: num("width"), drop: num("drop"), blade: num("blade"), guard, bounce: num("bounce"), path: rows("path", 3) as [number, number, number, number?][] | undefined, lunges: rows("lunges", 2) as [number, number, number?, number?, number?][] | undefined, ducks: rows("ducks", 2) as [number, number, number?, number?][] | undefined });
        } else if (x.fall !== undefined) {
          const f = (typeof x.fall === "object" && x.fall ? x.fall : {}) as Record<string, unknown>;
          blocks.push({ ...base, kind: "fall", prone: !!f.prone || undefined, push: typeof f.push === "number" ? f.push : undefined, dur: typeof f.dur === "number" ? f.dur : undefined });
        } else throw new ops.OpError("Each action needs one of: clip, go, idle, pose, stance, fall.");
      }
      const { layerId, sceneFps } = s.mutate("stage performance", (p) => {
        const { sc, l, spec } = stageLayer(p, a);
        const actor = findActor(spec, a.str("actor"));
        if (a.bool("replace")) {
          const t0 = Math.min(...blocks.map((b) => b.t));
          actor.blocks = actor.blocks.filter((b) => b.t < t0 - 1e-6);
        }
        for (const b of blocks) {
          const clash = actor.blocks.find((o) => Math.abs(o.t - b.t) < 1e-3);
          if (clash) actor.blocks.splice(actor.blocks.indexOf(clash), 1);
          actor.blocks.push(b);
        }
        sortBlocks(actor);
        fitLength(p, sc, spec);
        return { layerId: l.id, sceneFps: sc.fps };
      });
      // Report where each action leaves the body, so the next one can be planned.
      const { sc, spec } = stageLayer(s.project, a);
      const actor = findActor(spec, a.str("actor"));
      const rt = await ready(spec, layerId);
      const out: { id: string; t: number; kind: string; clip?: string; seconds?: number; arrives?: number; endsAt: number; ends?: { pos: number[]; yaw: number }; cutOff?: string }[] = [];
      for (const b of blocks) {
        const i = actor.blocks.findIndex((x) => x.id === b.id);
        const next = actor.blocks[i + 1];
        const start = rt.endOf(actor, b.t);
        let natural: number | undefined, arrive: number | undefined;
        if (b.kind === "clip") {
          const m = motions.meta(b.clip);
          natural = ((b.out ?? m.dur) - (b.in ?? 0)) / (b.rate ?? 1);
        } else if (b.kind === "go" && start) {
          const plan = rt.planGo(actor, b.id);
          if (plan) arrive = plan.seconds;
        }
        const endT = next ? next.t : b.t + (arrive ?? natural ?? 1) + (b.kind === "go" ? 0.5 : 0);
        const end = rt.endOf(actor, Math.max(b.t, endT - 1 / sceneFps));
        out.push({ id: b.id, t: r3(b.t), kind: b.kind, clip: b.kind === "clip" || b.kind === "go" ? b.clip : undefined, seconds: natural !== undefined ? r3(natural) : undefined, arrives: arrive !== undefined ? r3(b.t + arrive) : undefined, endsAt: r3(endT), ends: end ? { pos: [r2(end.pos[0]), r2(end.pos[2])], yaw: Math.round(end.yaw) } : undefined, cutOff: natural !== undefined && next && next.t - b.t < natural - 0.05 ? `the next action starts ${r2(natural - (next.t - b.t))}s before this clip ends` : undefined });
      }
      s.mutate("stage length", (p) => {
        const x = stageLayer(p, a);
        const last = out[out.length - 1];
        fitLength(p, x.sc, x.spec, last ? last.endsAt : 0);
      });
      return { actions: out, seconds: r3(sc.frames / sc.fps), problems: rt.problems.length ? [...new Set(rt.problems)] : undefined };
    },
  );

  registerCommand(
    "stage.overlay",
    `{sceneId, actor, add: [ {t0, t1, look: <actor id>|{actor, bone?, offset?}|"camera"|[x,y,z], head? (0..1 how much the head turns), eyes?} | {t0, t1, reach: <target>, side: L|R} | {t0, t1, rot: [x,y,z] (deg), bone} | {t0, t1, face: ${Object.keys(MOODS).join("|")} or {slider: 0..1}} | {t0, t1, fingers: 0..1 (curl), side: L|R|both, thumb?, spread?} | {t0, t1, clip, mask: ${MASKS.join("|")}, in?, rate?, mirror?, weight?} | {t0, t1, sword: [[sec, position], …] (see stage.presets swordPositions), two?} | {t0, t1, struck: true, strength?, dir?} | {t0, t1, lean: {pitch?, twist?, roll?} (deg)} | {t0, t1, strike: <target>, limb: hand.L|hand.R|foot.L|foot.R, apex (stage second it lands), hold?, offset?: [right, up, forward], via?: [right, up, forward] (bends the path: a hook swings wide, an uppercut dips)} | {t0, t1, keys: [{on: hand.L|hand.R|foot.L|foot.R (IK handle, metres [right, up, forward] from the actor's feet) | hips (offset) | <bone> (degrees), space?: "world" | {actor, bone?}, keys: [[sec after t0, [a, b, c], ease?: linear|in|out|io|snap|hold], …]}]} ], each with fade? (s)} Layer detail over a character's performance: where they look, what they reach for, a face, a gesture on the upper body while the legs keep walking, sword work, a hit reaction, a lean. Bones: head, neck01, spine01..05, clavicle.L/R, upperarm01.L/R, lowerarm01.L/R, wrist.L/R, upperleg01.L/R, lowerleg01.L/R, foot.L/R, jaw, eye.L/R, finger<1-5>-<1-3>.L/R.`,
    (s, a) =>
      s.mutate("stage overlay", (p) => {
        const { sc, spec } = stageLayer(p, a);
        const actor = findActor(spec, a.str("actor"));
        const raw = a.any("add");
        if (!Array.isArray(raw) || !raw.length) throw new ops.OpError('"add" must be a list like [{t0: 1, t1: 4, look: "rival"}].');
        const made: { id: string; kind: string }[] = [];
        for (const x of raw as Record<string, unknown>[]) {
          const t0 = Number(x.t0 ?? x.t), t1 = Number(x.t1 ?? (typeof x.dur === "number" ? t0 + x.dur : NaN));
          if (!Number.isFinite(t0) || !Number.isFinite(t1) || t1 <= t0) throw new ops.OpError("Every overlay needs t0 and t1 (seconds, t1 after t0).");
          const base = { id: sid("o"), t0, t1, fade: typeof x.fade === "number" ? x.fade : undefined };
          let ov: Overlay;
          if (x.look !== undefined) ov = { ...base, kind: "look", target: targetRef(x.look, "look"), head: typeof x.head === "number" ? x.head : undefined, eyes: typeof x.eyes === "number" ? x.eyes : undefined };
          else if (x.reach !== undefined) ov = { ...base, kind: "reach", target: targetRef(x.reach, "reach"), side: x.side === "L" ? "L" : "R" };
          else if (x.rot !== undefined) ov = { ...base, kind: "rot", bone: String(x.bone ?? ""), euler: vec3(x.rot, "rot") };
          else if (x.face !== undefined) {
            const f = typeof x.face === "string" ? MOODS[x.face] : (x.face as Record<string, number>);
            if (!f) throw new ops.OpError(`face must be one of ${Object.keys(MOODS).join(", ")} or {slider: value} with sliders ${FACE_SLIDERS.join(", ")}, open.`);
            ov = { ...base, kind: "face", face: f };
          } else if (x.fingers !== undefined) ov = { ...base, kind: "fingers", side: x.side === "L" || x.side === "R" ? x.side : "both", curl: Number(x.fingers), thumb: typeof x.thumb === "number" ? x.thumb : undefined, spread: typeof x.spread === "number" ? x.spread : undefined };
          else if (typeof x.clip === "string") {
            if (!MASKS.includes(x.mask as BodyMask)) throw new ops.OpError(`A clip overlay needs mask: ${MASKS.join(" | ")}.`);
            ov = { ...base, kind: "clip", clip: needClip(x.clip), mask: x.mask as BodyMask, in: typeof x.in === "number" ? x.in : undefined, rate: typeof x.rate === "number" ? x.rate : undefined, mirror: !!x.mirror || undefined, weight: typeof x.weight === "number" ? x.weight : undefined };
          } else if (x.sword !== undefined) {
            const seq = x.sword as [number, string][];
            if (!Array.isArray(seq) || !seq.length) throw new ops.OpError('sword must be a list like [[0, "guard"], [0.4, "high"], [0.55, "low"]].');
            for (const [, name] of seq) if (!(SW as Record<string, unknown>)[name]) throw new ops.OpError(`Unknown sword position "${name}". Positions: ${Object.keys(SW).join(", ")}.`);
            ov = { ...base, kind: "sword", seq, two: !!x.two || undefined };
          } else if (x.struck) ov = { ...base, kind: "struck", strength: typeof x.strength === "number" ? x.strength : undefined, dir: x.dir ? vec3(x.dir, "dir") : undefined };
          else if (x.lean) ov = { ...base, kind: "lean", ...(x.lean as { pitch?: number; twist?: number; roll?: number }) };
          else if (x.strike !== undefined) {
            const limb = String(x.limb ?? "hand.R");
            if (!["hand.L", "hand.R", "foot.L", "foot.R"].includes(limb)) throw new ops.OpError("strike needs limb: hand.L | hand.R | foot.L | foot.R.");
            const apex = typeof x.apex === "number" ? x.apex : t0 + (t1 - t0) * 0.4;
            if (!(apex > t0 && apex < t1)) throw new ops.OpError("strike: apex (the second it lands) must be between t0 and t1.");
            ov = { ...base, kind: "strike", limb: limb as "hand.L", target: targetRef(x.strike, "strike"), apex, hold: typeof x.hold === "number" ? x.hold : undefined, offset: x.offset ? vec3(x.offset, "offset") : undefined, via: x.via ? vec3(x.via, "via") : undefined };
          } else if (x.keys !== undefined) {
            const tr = x.keys as { on?: unknown; space?: unknown; keys?: unknown }[];
            if (!Array.isArray(tr) || !tr.length) throw new ops.OpError('keys must be a list of tracks like [{on: "hand.R", keys: [[0, [0.2, 1.2, 0.3]], [0.5, [0.1, 1.6, 0.5]]]}].');
            const tracks: KeyTrack[] = tr.map((k) => {
              if (typeof k.on !== "string" || !Array.isArray(k.keys) || !k.keys.length) throw new ops.OpError("Each key track needs on (hand.L, hand.R, foot.L, foot.R, hips or a bone name) and keys [[sec, [a, b, c], ease?], …].");
              const keys = (k.keys as unknown[][]).map((r) => [Number(r[0]), vec3(r[1], "key value"), typeof r[2] === "string" ? r[2] : undefined] as [number, Vec3, string?]).sort((p, q) => p[0] - q[0]);
              const space = k.space === "world" ? "world" : k.space && typeof k.space === "object" ? { actor: String((k.space as { actor?: unknown }).actor ?? ""), bone: (k.space as { bone?: string }).bone } : undefined;
              if (space && typeof space === "object") findActor(spec, space.actor);
              return { on: k.on, space, keys };
            });
            ov = { ...base, kind: "keys", tracks };
          } else throw new ops.OpError("Each overlay needs one of: look, reach, rot, face, fingers, clip, sword, struck, lean, strike, keys.");
          actor.overlays.push(ov);
          made.push({ id: ov.id, kind: ov.kind });
        }
        fitLength(p, sc, spec);
        return made;
      }),
  );

  registerCommand(
    "stage.say",
    "{sceneId, actor, text, t (s in the scene), voice?, speed?, tone? (how it is said: voice.tones lists them; or {pitch, range, rate, energy, ending…}), emphasis?: [words], look? (who they speak to: actor id or \"camera\")} VolTune speaks the line (Hindi, Hinglish or English): the audio goes on the timeline under the scene and the character's mouth follows it. Returns {seconds, lineId, assetId, clipId}.",
    async (s, a) => {
      const t = a.num("t", 0);
      stageLayer(s.project, a); // fail early if the scene is not a stage
      // with a tone (or emphasis) the open voice says the line; otherwise the standard voice does
      const tone = readTone(a.any("tone"));
      const em = a.any("emphasis");
      let asset: import("../core/model").Asset, seconds: number;
      if (tone || Array.isArray(em)) {
        const r = await sayToAsset(s, a.str("text"), a.str("voice", "hf_alpha"), tone, Array.isArray(em) ? em.map(String) : undefined, a.optStr("lang"));
        asset = r.asset;
        seconds = r.seconds;
      } else {
        const r = await s.speak({ text: a.str("text"), voice: a.optStr("voice"), speed: a.optNum("speed"), lang: a.optStr("lang") });
        asset = r.asset;
        seconds = r.result.seconds;
      }
      const result = { seconds };
      return s.mutate("stage line", (p) => {
        const { sc, spec } = stageLayer(p, a);
        const actor = findActor(spec, a.str("actor"));
        let start = 0;
        for (const tr of p.tracks) for (const c of tr.clips) if (c.sceneId === sc.id) start = c.start - c.in;
        const clip = ops.addClip(p, { assetId: asset.id, start: Math.max(0, start + t) });
        const id = sid("l");
        actor.lines.push({ id, t, dur: result.seconds, mouth: asset.mouth ?? [], text: a.str("text"), assetId: asset.id });
        if (a.optStr("look")) actor.overlays.push({ id: sid("o"), kind: "look", t0: Math.max(0, t - 0.3), t1: t + result.seconds + 0.3, target: targetRef(a.str("look"), "look") });
        fitLength(p, sc, spec);
        return { lineId: id, assetId: asset.id, clipId: clip.id, seconds: r3(result.seconds), ends: r3(t + result.seconds) };
      });
    },
  );

  registerCommand(
    "stage.cam",
    `{sceneId, shots: [{t, dur, kind: ${SHOT_KINDS.join("|")}, on? (actor), other? (for ots and two), yaw? (deg around the subject from their front: 0 front, 90 their left, 180 behind; default 28), lift? (m), dist? (m), fov?, roll?, move?: {kind: push|pull|orbit|truck|crane|handheld|whip, amount?}, shake? (0..1), dof?: true|false|{range, max}, track?: true (travel with the subject) | false (stay put and pan), pos?, target? (free camera), keys?: [{t, pos?, target?, fov?}] (free camera path)}], replace?} Shots: each one frames a character the way a camera operator would (wide, full, medium, close, extreme close, low, high, top, over the shoulder, two-shot, follow) and keeps them framed as they move. Shots cut from one to the next at their start times.`,
    (s, a) =>
      s.mutate("stage camera", (p) => {
        const { sc, spec } = stageLayer(p, a);
        const raw = a.any("shots");
        if (!Array.isArray(raw) || !raw.length) throw new ops.OpError('"shots" must be a list like [{t: 0, dur: 3, kind: "wide", on: "hero"}].');
        if (a.bool("replace")) spec.shots = [];
        const made: string[] = [];
        for (const x of raw as Record<string, unknown>[]) {
          const kind = String(x.kind ?? "full") as ShotKind;
          if (!SHOT_KINDS.includes(kind)) throw new ops.OpError(`Shot kind must be one of ${SHOT_KINDS.join(", ")}.`);
          const t = Number(x.t), dur = Number(x.dur);
          if (!Number.isFinite(t) || !(dur > 0)) throw new ops.OpError("Every shot needs t and dur (seconds).");
          if (kind !== "free") {
            if (typeof x.on !== "string") throw new ops.OpError(`A ${kind} shot needs "on": the actor it frames. Actors: ${actorIds(spec).join(", ")}.`);
            findActor(spec, x.on);
            if (x.other !== undefined) findActor(spec, String(x.other));
            if ((kind === "ots" || kind === "two") && typeof x.other !== "string") throw new ops.OpError(`A ${kind} shot needs "other": the second actor.`);
          }
          const shot: Shot = { id: sid("s"), t, dur, kind };
          if (typeof x.on === "string") shot.on = x.on;
          if (typeof x.track === "boolean") shot.track = x.track;
          if (typeof x.other === "string") shot.other = x.other;
          for (const k of ["yaw", "lift", "dist", "fov", "roll", "shake"] as const) if (typeof x[k] === "number") shot[k] = x[k] as number;
          if (x.pos !== undefined) shot.pos = vec3(x.pos, "pos");
          if (x.target !== undefined) shot.target = vec3(x.target, "target");
          if (x.move) shot.move = typeof x.move === "string" ? { kind: x.move as NonNullable<Shot["move"]>["kind"] } : (x.move as Shot["move"]);
          if (x.dof !== undefined) shot.dof = x.dof as Shot["dof"];
          if (Array.isArray(x.keys)) shot.keys = (x.keys as { t: number; pos?: unknown; target?: unknown; fov?: number }[]).map((k) => ({ t: Number(k.t), pos: k.pos ? vec3(k.pos, "key pos") : undefined, target: k.target ? vec3(k.target, "key target") : undefined, fov: k.fov }));
          spec.shots.push(shot);
          made.push(shot.id);
        }
        spec.shots.sort((x, y) => x.t - y.t);
        fitLength(p, sc, spec);
        return { shots: made, seconds: r3(sc.frames / sc.fps) };
      }),
  );

  registerCommand(
    "stage.fx",
    `{sceneId, add: [{kind: ${FX_KINDS.join("|")}, t (start), dur?, pos?: [x,z]|[x,y,z], size?, strength?}]} Effects in the scene: fire, smoke, embers, a burst of sparks or dust, a shockwave; "impact" is an impact frame (flash, invert, lens kick, camera shake) at time t; "flash" a white flash.`,
    (s, a) =>
      s.mutate("stage fx", (p) => {
        const { sc, spec } = stageLayer(p, a);
        const raw = a.any("add");
        if (!Array.isArray(raw) || !raw.length) throw new ops.OpError('"add" must be a list like [{kind: "impact", t: 2.4}].');
        const made: string[] = [];
        for (const x of raw as Record<string, unknown>[]) {
          const kind = String(x.kind) as StageFx["kind"];
          if (!FX_KINDS.includes(kind)) throw new ops.OpError(`fx kind must be one of ${FX_KINDS.join(", ")}.`);
          const t0 = Number(x.t ?? x.t0);
          if (!Number.isFinite(t0)) throw new ops.OpError("Every effect needs t (seconds).");
          const dur = typeof x.dur === "number" ? x.dur : kind === "impact" ? 0.35 : kind === "flash" ? 0.25 : kind === "sparks" || kind === "dust" || kind === "shockwave" ? 2 : 1e4;
          const f: StageFx = { id: sid("f"), kind, t0, t1: t0 + dur, pos: x.pos !== undefined ? vec3(x.pos, "pos") : undefined, size: typeof x.size === "number" ? x.size : undefined, strength: typeof x.strength === "number" ? x.strength : undefined };
          spec.fx.push(f);
          made.push(f.id);
        }
        fitLength(p, sc, spec);
        return made;
      }),
  );

  registerCommand(
    "stage.fight",
    `{sceneId, a (attacker id), b (defender id), t (start), style?: sword|fists, beats: [{do: ${Object.keys(FIGHT_MOVES).join("|")}, by?: a|b (who attacks, default they take turns), gap? (s before this beat), hit?: true|false (does it land), defend?: parry|block|dodge|duck|slip (the answer when it does not land), power? (0.5..2), speed?, ko?: true (the one struck goes down; the fight ends), push? (m the attacker gains)}], spacing? (m between them), leadA?: L|R, leadB?: L|R (front foot), impactFrames?=true, cameras?=true} Choreograph a fight between two characters who already stand near each other. Both drop into fighting stances with planted feet; attacks lunge, answers move the body, hits stagger or drop the one struck, with hit-stop, impact frames, sparks, camera shake and a cut plan. Sword moves need the actors to carry swords; the others are unarmed. Everything written is ordinary actions, overlays, effects and shots that stage.edit can retime. Returns the beat times so you can add lines, looks and extra shots.`,
    async (s, a) => {
      await stageAssets.load();
      const beats = a.any("beats") as FightBeat[];
      if (!Array.isArray(beats) || !beats.length) throw new ops.OpError('"beats" must be a list like [{do: "slash"}, {do: "parry", by: "b"}, {do: "thrust", hit: true}].');
      const { l: fl, spec: before } = stageLayer(s.project, a);
      const rt = await ready(before, fl.id);
      const A = findActor(before, a.str("a")), B = findActor(before, a.str("b"));
      const t0 = a.num("t", 0);
      const pa = rt.endOf(A, t0), pb = rt.endOf(B, t0);
      if (!pa || !pb) throw new ops.OpError("Both fighters must be on the stage.");
      const plan = compileFight({ a: A, b: B, t: t0, style: a.str("style", A.props?.some((q) => q.kind === "sword") || A.kit ? "sword" : "fists") as "sword" | "fists", beats, posA: pa, posB: pb, spacing: a.optNum("spacing"), impactFrames: a.bool("impactFrames", true), cameras: a.bool("cameras", true), leadA: a.optStr("leadA") === "R" ? "R" : "L", leadB: a.optStr("leadB") === "R" ? "R" : "L" });
      const res = s.mutate("stage fight", (p) => {
        const { sc, spec } = stageLayer(p, a);
        const fa = findActor(spec, a.str("a")), fb = findActor(spec, a.str("b"));
        for (const [actor, add] of [[fa, plan.a], [fb, plan.b]] as const) {
          actor.blocks = actor.blocks.filter((b) => b.t < t0 - 1e-6 || b.t > plan.end + 1e-6);
          actor.blocks.push(...add.blocks);
          actor.overlays.push(...add.overlays);
          sortBlocks(actor);
        }
        spec.fx.push(...plan.fx);
        if (plan.shots.length) {
          spec.shots = spec.shots.filter((x) => x.t + x.dur <= t0 + 1e-6 || x.t >= plan.end - 1e-6);
          spec.shots.push(...plan.shots);
          spec.shots.sort((x, y) => x.t - y.t);
        }
        fitLength(p, sc, spec, plan.end);
        return { beats: plan.beats, end: r3(plan.end), seconds: r3(sc.frames / sc.fps) };
      });
      await loadStage(stageLayer(s.project, a).spec);
      return res;
    },
  );

  registerCommand(
    "stage.edit",
    "{sceneId, id (a block, overlay, shot, line or effect id), patch: {…fields to change}} Change one thing in place: retime it (t, t0, t1, dur), swap its clip or in/out, change a shot's kind, yaw, move, and so on.",
    (s, a) =>
      s.mutate("stage edit", (p) => {
        const { sc, spec } = stageLayer(p, a);
        const id = a.str("id");
        const patch = a.obj("patch");
        const lists: { id: string }[][] = [spec.shots, spec.fx, ...spec.actors.flatMap((x) => [x.blocks as { id: string }[], x.overlays as { id: string }[], x.lines as { id: string }[]])];
        for (const list of lists) {
          const it = list.find((x) => x.id === id);
          if (!it) continue;
          if (typeof patch.clip === "string") needClip(patch.clip);
          Object.assign(it, patch, { id });
          for (const x of spec.actors) sortBlocks(x);
          spec.shots.sort((x, y) => x.t - y.t);
          fitLength(p, sc, spec);
          return it;
        }
        throw new ops.OpError(`Nothing on this stage has id ${id}. stage.get lists the ids.`);
      }),
  );

  registerCommand(
    "stage.delete",
    "{sceneId, ids?: [block / overlay / shot / line / effect ids], actor? (remove a whole character), clear?: blocks|overlays|shots|fx|lines (with actor for that actor's, or for the whole stage)} Remove things from the stage.",
    (s, a) =>
      s.mutate("stage delete", (p) => {
        const { spec } = stageLayer(p, a);
        const ids = new Set(((a.any("ids") as string[] | undefined) ?? []).map(String));
        let n = 0;
        const strip = <T extends { id: string }>(list: T[]): T[] => {
          const out = list.filter((x) => !ids.has(x.id));
          n += list.length - out.length;
          return out;
        };
        const clear = a.optStr("clear");
        const who = a.optStr("actor");
        if (who && !clear && !ids.size) {
          findActor(spec, who);
          spec.actors = spec.actors.filter((x) => x.id !== who);
          return { removed: 1, actors: actorIds(spec) };
        }
        spec.shots = strip(spec.shots);
        spec.fx = strip(spec.fx);
        for (const x of spec.actors) {
          x.blocks = strip(x.blocks);
          x.overlays = strip(x.overlays);
          x.lines = strip(x.lines);
          if (clear && (!who || who === x.id)) {
            if (clear === "blocks") x.blocks = [];
            else if (clear === "overlays") x.overlays = [];
            else if (clear === "lines") x.lines = [];
          }
        }
        if (clear === "shots") spec.shots = [];
        if (clear === "fx") spec.fx = [];
        return { removed: n };
      }),
  );

  registerCommand("stage.where", "{sceneId, actor, t} Where a character is and which way they face at time t (metres; yaw in degrees).", async (s, a) => {
    const { l, spec } = stageLayer(s.project, a);
    const rt = await ready(spec, l.id);
    const st = rt.endOf(findActor(spec, a.str("actor")), a.num("t", 0));
    if (!st) throw new ops.OpError("That actor is not built yet.");
    return { pos: [r2(st.pos[0]), r2(st.pos[2])], yaw: Math.round(st.yaw), head: st.head.map(r2), height: st.height };
  });

  registerCommand(
    "stage.trace",
    "{sceneId, actor, from?, to?, step? (s, default one frame)} Numbers behind a performance, frame by frame: where the hips and both feet are, which captured clip is playing and where in it, and which feet the capture says are planted (L, R, LR or -). Use it to find the exact frame of a step, a strike or a slide. Up to 240 rows.",
    async (s, a) => {
      const { sc, l, spec } = stageLayer(s.project, a);
      const rt = await ready(spec, l.id);
      const sa = findActor(spec, a.str("actor"));
      const end = Math.max(stageDuration(spec), 0.5);
      const from = Math.max(0, a.num("from", 0)), to = Math.min(end, a.num("to", end));
      let dt = Math.max(1 / sc.fps, a.num("step", 1 / sc.fps));
      if ((to - from) / dt > 240) dt = (to - from) / 240;
      const rows: (number | string)[][] = [];
      for (let t = from; t <= to + 1e-6; t += dt) {
        rt.evaluate(spec, t, dt);
        const st = rt.actorState(sa.id), f = rt.feet(sa.id), m = rt.motionAt(sa, t);
        if (!st || !f) continue;
        const o = rt.env?.origin ?? [0, 0, 0];
        const c = m?.contact ?? 0;
        rows.push([r3(t), r2(st.pos[0]), r2(st.pos[2]), Math.round(st.yaw), r3(f.L[0] - o[0]), r3(f.L[1] - st.pos[1]), r3(f.L[2] - o[2]), r3(f.R[0] - o[0]), r3(f.R[1] - st.pos[1]), r3(f.R[2] - o[2]), (c & 1 ? "L" : "") + (c & 2 ? "R" : "") || "-", m?.clip ?? m?.kind ?? "", r3(m?.ct ?? 0)]);
      }
      return { columns: ["t", "x", "z", "facing°", "footL.x", "footL.height", "footL.z", "footR.x", "footR.height", "footR.z", "planted", "clip", "clipTime"], rows };
    },
  );

  registerCommand(
    "stage.bake",
    '{sceneId, fps?=60, from?=0, to?, actors?: [id…], joints?: ["head", "wrist.L", "head:tail" (a bone\'s far end) …], quats?: [bone…], bodies?: true | [id…] (also give these actors\' real bodies: mesh parts with skin weights and every bone in every rigEvery-th frame), rigEvery?=2} The posed skeletons over time, for a renderer outside the app: for each actor a base64 Float32 block of frames × (joints × 3 + quats × 4 numbers) in stage metres, which feet are planted in each frame, and its props\' places.',
    async (s, a) => {
      const { sc, l, spec } = stageLayer(s.project, a);
      const rt = await ready(spec, l.id);
      const fps = Math.max(1, a.num("fps", 60));
      const from = Math.max(0, a.num("from", 0)), to = a.num("to", sc.frames / sc.fps);
      const n = Math.max(1, Math.round((to - from) * fps) + 1);
      const want = a.any("actors") as string[] | undefined;
      const list = spec.actors.filter((x) => !Array.isArray(want) || want.includes(x.id));
      const joints = (a.any("joints") as string[] | undefined) ?? BAKE_JOINTS, quats = (a.any("quats") as string[] | undefined) ?? BAKE_QUATS;
      const w = joints.length * 3 + quats.length * 4;
      // bodies: the real mesh of these actors and every bone's place in every `rigEvery`-th frame, so a renderer outside the app can draw the true body
      const wantBodies = a.any("bodies") as boolean | string[] | undefined, rigEvery = Math.max(1, Math.round(a.num("rigEvery", 2)));
      const bodyOf = list.map((sa) => (wantBodies === true || (Array.isArray(wantBodies) && wantBodies.includes(sa.id)) ? rt.body(sa.id) : null));
      const boneNames = bodyOf.map((b) => (b ? (b.data.bones as { name: string }[]).map((x) => x.name) : []));
      const nr = Math.floor((n - 1) / rigEvery) + 1;
      const rig = bodyOf.map((b, k) => (b ? new Float32Array(nr * boneNames[k]!.length * 7) : null));
      const data = list.map(() => new Float32Array(n * w)), contact = list.map(() => new Uint8Array(n));
      const props: Map<string, Float32Array>[] = list.map(() => new Map());
      // run in from a little earlier so anything that smooths over time has settled by the first frame
      for (let i = -Math.round(fps * 0.5); i < n; i++) {
        const t = Math.max(0, from + i / fps);
        rt.evaluate(spec, t, 1 / fps);
        if (i < 0) continue;
        list.forEach((sa, k) => {
          const sk = rt.skeleton(sa.id, joints, quats);
          if (!sk) return;
          data[k]!.set(sk.pos, i * w);
          data[k]!.set(sk.quat, i * w + joints.length * 3);
          contact[k]![i] = rt.motionAt(sa, t)?.contact ?? 0;
          if (rig[k] && i % rigEvery === 0) {
            const names = boneNames[k]!, full = rt.skeleton(sa.id, names, names), nb = names.length, o = (i / rigEvery) * nb * 7, out = rig[k]!;
            if (full) for (let b = 0; b < nb; b++) { out[o + b * 7] = full.pos[b * 3]!; out[o + b * 7 + 1] = full.pos[b * 3 + 1]!; out[o + b * 7 + 2] = full.pos[b * 3 + 2]!; out[o + b * 7 + 3] = full.quat[b * 4]!; out[o + b * 7 + 4] = full.quat[b * 4 + 1]!; out[o + b * 7 + 5] = full.quat[b * 4 + 2]!; out[o + b * 7 + 6] = full.quat[b * 4 + 3]!; }
          }
          for (const pr of sk.props) {
            let arr = props[k]!.get(pr.name);
            if (!arr) props[k]!.set(pr.name, (arr = new Float32Array(n * 8)));
            arr.set(pr.m, i * 8);
            arr[i * 8 + 7] = pr.visible ? 1 : 0;
          }
        });
      }
      const b64 = (u: Uint8Array) => {
        let out = "";
        for (let i = 0; i < u.length; i += 0x8000) out += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000) as unknown as number[]);
        return btoa(out);
      };
      return {
        fps, from, frames: n, joints, quats, stride: w,
        actors: list.map((sa, k) => ({
          id: sa.id, height: rt.actorState(sa.id)?.height ?? 0,
          data: b64(new Uint8Array(data[k]!.buffer)), contact: b64(contact[k]!),
          props: [...props[k]!.entries()].map(([name, arr]) => ({ name, data: b64(new Uint8Array(arr.buffer)) })),
          ...(bodyOf[k] ? { body: packBody(bodyOf[k]!, b64), rig: { fps: fps / rigEvery, frames: nr, bones: boneNames[k]!.length, data: b64(new Uint8Array(rig[k]!.buffer)) } } : {}),
        })),
      };
    },
  );

  registerCommand(
    "stage.sheet",
    "{sceneId, times?: [s…] | count?=8, from?, to?, cols?=4, cell?=480} Contact sheet: the stage as it will render at several moments, in one picture. Look at it after every change to staging, performance or camera.",
    async (s, a) => {
      const { sc, spec } = stageLayer(s.project, a);
      await loadStage(spec);
      const end = sc.frames / sc.fps;
      let times = a.any("times") as number[] | undefined;
      if (!Array.isArray(times) || !times.length) {
        const n = Math.max(1, Math.min(24, Math.round(a.num("count", 8))));
        const from = a.num("from", 0), to = Math.min(end, a.num("to", Math.max(0.1, Math.min(end, stageDuration(spec) || end))));
        times = Array.from({ length: n }, (_, i) => from + ((to - from) * (i + 0.5)) / n);
      }
      const cols = Math.max(1, Math.min(6, Math.round(a.num("cols", Math.min(4, times.length)))));
      const jpeg = await sheetOf(s, sc, times, cols, Math.round(a.num("cell", 480)));
      const rt = runtimeOf(stageLayer(s.project, a).l.id);
      return { jpeg, times: times.map(r3), problems: rt?.problems.length ? [...new Set(rt.problems)] : undefined, error: stageError() || undefined };
    },
  );

  registerCommand(
    "stage.check",
    "{sceneId, step? (s between samples, default one frame)} Quality check of the whole stage: feet sliding on the ground, bodies sinking or floating, characters standing inside each other, subjects out of frame, time without a shot, clips cut short, missing motions. Returns issues from most to least serious, with times.",
    async (s, a) => {
      const { sc, l, spec } = stageLayer(s.project, a);
      const rt = await ready(spec, l.id);
      const dt = Math.max(1 / sc.fps, a.num("step", 1 / sc.fps));
      const end = Math.max(stageDuration(spec), 0.5);
      const issues: { level: "bad" | "warn" | "note"; t?: number; actor?: string; what: string }[] = [];
      const slide: Record<string, { m: number; worst: number; at: number; prev: { L: Vec3; R: Vec3; ankle: number } | null; con: number; steady: boolean }> = {};
      const clash = new Map<string, number>();
      const offFrame = new Map<string, number>();
      const cam = rt.st.camera;
      for (let t = 0; t <= end + 1e-6; t += dt) {
        rt.evaluate(spec, t, dt);
        for (const x of spec.actors) {
          const f = rt.feet(x.id), st = rt.actorState(x.id);
          if (!f || !st) continue;
          const sl = (slide[x.id] ??= { m: 0, worst: 0, at: 0, prev: null, con: 0, steady: false });
          // a foot the capture says is planted must stay where it is; blends between motions are not judged
          const mt = rt.motionAt(x, t);
          const con = mt?.contact ?? 0, steady = !!mt?.steady;
          if (sl.prev && steady && sl.steady)
            for (const side of ["L", "R"] as const) {
              const bit = side === "L" ? 1 : 2;
              if (!(con & bit) || !(sl.con & bit)) continue;
              const p0 = sl.prev[side], p1 = f[side];
              const d = Math.hypot(p1[0] - p0[0], p1[2] - p0[2]);
              if (d / dt > 0.3) {
                sl.m += d;
                if (d / dt > sl.worst) {
                  sl.worst = d / dt;
                  sl.at = t;
                }
              }
            }
          sl.con = con;
          sl.steady = steady;
          sl.prev = f;
        }
        for (let i = 0; i < spec.actors.length; i++)
          for (let j = i + 1; j < spec.actors.length; j++) {
            const A = rt.actorState(spec.actors[i]!.id), B = rt.actorState(spec.actors[j]!.id);
            if (A && B && Math.hypot(A.pos[0] - B.pos[0], A.pos[2] - B.pos[2]) < 0.28) {
              const k = `${spec.actors[i]!.id}+${spec.actors[j]!.id}`;
              if (!clash.has(k)) clash.set(k, t);
            }
          }
        const shot = spec.shots.filter((x) => x.t <= t && t < x.t + x.dur).pop();
        if (shot?.on) {
          const st = rt.actorState(shot.on);
          if (st) {
            const o = rt.env?.origin ?? [0, 0, 0];
            cam.updateMatrixWorld();
            const v = { x: st.head[0] + o[0], y: st.head[1], z: st.head[2] + o[2] };
            const e = cam.matrixWorldInverse.elements, pr = cam.projectionMatrix.elements;
            const cx = e[0]! * v.x + e[4]! * v.y + e[8]! * v.z + e[12]!, cy = e[1]! * v.x + e[5]! * v.y + e[9]! * v.z + e[13]!, cz = e[2]! * v.x + e[6]! * v.y + e[10]! * v.z + e[14]!;
            const nx = (pr[0]! * cx) / -cz, ny = (pr[5]! * cy) / -cz;
            if ((cz > 0 || Math.abs(nx) > 1.02 || Math.abs(ny) > 1.02) && !offFrame.has(shot.id)) offFrame.set(shot.id, t);
          }
        }
      }
      for (const [id, sl] of Object.entries(slide)) {
        if (sl.worst > 1.1 && sl.m > 0.12) issues.push({ level: "bad", actor: id, t: r2(sl.at), what: `planted feet slide on the ground (up to ${r2(sl.worst)} m/s, ${r2(sl.m)} m in total): change the walk's speed or clip, or look at stage.trace around ${r2(sl.at)}s` });
        else if (sl.worst > 0.6 && sl.m > 0.06) issues.push({ level: "warn", actor: id, t: r2(sl.at), what: `slight foot sliding (${r2(sl.worst)} m/s, ${r2(sl.m)} m in total) around ${r2(sl.at)}s` });
      }
      for (const [k, t] of clash) issues.push({ level: "bad", t: r2(t), what: `${k.replace("+", " and ")} stand inside each other from ${r2(t)}s` });
      for (const [id, t] of offFrame) issues.push({ level: "warn", t: r2(t), what: `shot ${id}: the subject's head leaves the frame at ${r2(t)}s` });
      if (spec.shots.length) {
        let cover = 0;
        for (const sh of [...spec.shots].sort((x, y) => x.t - y.t)) {
          if (sh.t > cover + 1 / sc.fps) issues.push({ level: "note", t: r2(cover), what: `no shot between ${r2(cover)}s and ${r2(sh.t)}s (the shot before it holds; before the first shot a default wide view is used)` });
          cover = Math.max(cover, sh.t + sh.dur);
        }
        if (cover < end - 1 / sc.fps) issues.push({ level: "note", t: r2(cover), what: `no shot after ${r2(cover)}s (the shot before it holds; before the first shot a default wide view is used)` });
      } else issues.push({ level: "note", what: "no shots yet: a default wide view is used (stage.cam adds shots)" });
      for (const x of spec.actors) {
        x.blocks.forEach((b, i) => {
          const next = x.blocks[i + 1];
          if (b.kind === "clip" && next) {
            const m = motions.meta(b.clip);
            const nat = ((b.out ?? m.dur) - (b.in ?? 0)) / (b.rate ?? 1);
            if (!b.loop && next.t - b.t > nat + 0.6) issues.push({ level: "note", actor: x.id, t: r2(b.t + nat), what: `${b.clip} ends at ${r2(b.t + nat)}s and its last pose is held until ${r2(next.t)}s` });
          }
        });
        if (!x.blocks.length) issues.push({ level: "note", actor: x.id, what: "has no actions yet (stands relaxed)" });
      }
      for (const m of new Set(rt.problems)) issues.push({ level: "bad", what: m });
      const rank = { bad: 0, warn: 1, note: 2 };
      issues.sort((x, y) => rank[x.level] - rank[y.level] || (x.t ?? 0) - (y.t ?? 0));
      return { ok: !issues.some((x) => x.level === "bad"), seconds: r3(end), checkedFrames: Math.round(end / dt) + 1, issues };
    },
  );
}

/** A body for a renderer outside the app: rest skeleton, mesh parts (positions, triangles, skin weights), colours. */
function packBody(b: { data: { name: string; height: number; bones: { name: string; parent: number; head: number[]; tail: number[] }[]; parts: { name: string; material: string; vcount: number; pos: Float32Array; idx: Uint32Array; skinIndex: Uint16Array; skinWeight: Float32Array; mask?: Float32Array; morphs?: { i: Uint32Array; d: Float32Array }[]; params: Record<string, unknown> }[]; morphNames?: string[]; landmarks: Record<string, number | number[]>; look: Record<string, unknown> }; scale: number; colors: Record<string, number[]> }, b64: (u: Uint8Array) => string): unknown {
  const u8 = (x: { buffer: ArrayBufferLike; byteOffset: number; byteLength: number }) => b64(new Uint8Array(x.buffer, x.byteOffset, x.byteLength));
  const look: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(b.data.look ?? {})) if (v === null || ["number", "string", "boolean"].includes(typeof v) || (Array.isArray(v) && v.length <= 4 && v.every((x) => typeof x === "number"))) look[k] = v;
  return {
    name: b.data.name, height: b.data.height, scale: b.scale, colors: b.colors, look, landmarks: b.data.landmarks, morphNames: b.data.morphNames ?? [],
    bones: b.data.bones.map((x) => ({ name: x.name, parent: x.parent, head: x.head, tail: x.tail })),
    parts: b.data.parts.map((p) => {
      const params: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(p.params ?? {})) if (["number", "string", "boolean"].includes(typeof v)) params[k] = v;
      return { name: p.name, material: p.material, vcount: p.vcount, params, pos: u8(p.pos), idx: u8(p.idx), skinIndex: u8(p.skinIndex), skinWeight: u8(p.skinWeight), ...(p.mask ? { mask: u8(p.mask) } : {}), ...(p.morphs ? { morphs: p.morphs.map((m) => ({ i: u8(m.i), d: u8(m.d) })) } : {}) };
    }),
  };
}
