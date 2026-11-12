/**
 * Stage: a 3D scene with real human characters, motion-capture performances, cameras and a cinematic or anime look,
 * living inside an animation scene as a layer (kind "stage"). Everything is plain JSON, so Claude and the Stage page
 * edit the same thing, every change is undoable, and any frame can be evaluated on its own.
 *
 * Units: metres, y up, a character faces +Z at yaw 0; yaw in degrees, turning left (towards +X) is positive.
 * Times are seconds from the start of the scene.
 */
import type { CharSpec } from "./human/factory";

export type Vec3 = [number, number, number];

/** A point in the scene: fixed, or attached to an actor (optionally a bone and an offset in metres), or the camera. */
export type TargetRef = Vec3 | "camera" | { actor: string; bone?: string; offset?: Vec3 };

export type BodyMask = "upper" | "lower" | "arms" | "armL" | "armR" | "head" | "spine" | "legs";

interface BlockBase {
  id: string;
  /** Start time, seconds. A block runs until the next block of the same actor starts. */
  t: number;
  /** Crossfade from the previous block, seconds (default 0.3). */
  blend?: number;
  /** Jump to a new place at the start of this block instead of continuing where the last one ended. */
  at?: { pos?: Vec3; yaw?: number };
}
/** Play a motion-capture clip. The body travels and turns as the clip does. */
export interface ClipBlock extends BlockBase {
  kind: "clip";
  clip: string;
  /** Part of the clip to use, seconds in the clip. */
  in?: number;
  out?: number;
  /** Playback speed (1 = as captured). */
  rate?: number;
  mirror?: boolean;
  /** Repeat the in…out window (walk cycles, idles). Without it the last pose is held. */
  loop?: boolean;
  /** Keep the body on the spot (ignore the clip's travel). */
  inPlace?: boolean;
  /** Time-warp keys [[seconds since block start, clip seconds], …] for snappy or held timing; overrides rate. */
  warp?: [number, number][];
}
/** Walk or run to a point on the ground, then stand. */
export interface GoBlock extends BlockBase {
  kind: "go";
  to: [number, number];
  gait?: "walk" | "run" | "jog" | "sneak" | "march" | string;
  /** A specific locomotion clip (otherwise picked from the library by gait and speed). */
  clip?: string;
  /** Metres per second (default: the clip's own pace). */
  speed?: number;
  /** Yaw to face after arriving (default: the travel direction). */
  face?: number;
}
/** Stand and breathe (optionally an idle clip). */
export interface IdleBlock extends BlockBase {
  kind: "idle";
  clip?: string;
  in?: number;
}
/** Hold a pose: a frozen clip frame ("clip:02_05@3.2"), a built-in name (sit, kneel, guard…) or bone angles. */
export interface PoseBlock extends BlockBase {
  kind: "pose";
  pose: string | Record<string, Vec3>;
}
/**
 * A fighting or ready stance with footwork: both feet are planted where the stance says (solved, not captured), the
 * hips sink between them, and the stance can step, turn, lunge and duck. The upper body is free for overlays
 * (sword work, strikes, looks).
 */
export interface StanceBlock extends BlockBase {
  kind: "stance";
  /** The front foot: "L" or "R"; "none" stands square (feet side by side). */
  lead?: "L" | "R" | "none";
  /** Front-to-back distance between the feet, metres for a 1.75 m body (default 0.56). */
  length?: number;
  /** Sideways distance between the feet (default 0.3). */
  width?: number;
  /** How far the hips sink, metres (default 0.09). */
  drop?: number;
  /** How far the hips turn away from the front, degrees (default 26; 0 for a square stance). */
  blade?: number;
  /** Arms: "fists" (boxing guard), "open" (hands low) or "none" (left to overlays such as sword work). */
  guard?: "fists" | "open" | "none";
  /** Footwork: [seconds after the stance starts, metres right, metres forward, turn in degrees (left is positive)?]. The feet step there one after the other. */
  path?: [number, number, number, number?][];
  /** Lunges and retreats: [second of the deepest point, reach in metres (negative steps back), seconds out?, seconds back?, hold?]. */
  lunges?: [number, number, number?, number?, number?][];
  /** Ducks: [second of the lowest point, depth in metres, seconds down?, seconds up?]. */
  ducks?: [number, number, number?, number?][];
  /** Bounce on the toes, 0…1. */
  bounce?: number;
}
/** Knocked down: falls on the back (or face down) and stays there. */
export interface FallBlock extends BlockBase {
  kind: "fall";
  prone?: boolean;
  /** Thrown back this far, metres (default 0.6). */
  push?: number;
  /** Seconds the fall takes (default 1.1). */
  dur?: number;
}
export type Block = ClipBlock | GoBlock | IdleBlock | PoseBlock | StanceBlock | FallBlock;

/** An IK handle or bone animated by keys. */
export interface KeyTrack {
  /** hand.L, hand.R, foot.L, foot.R (positions), hips (offset) or any bone name (rotation in degrees). */
  on: string;
  /** Positions are metres [right, up, forward] measured from the actor's feet ("actor", default), from the stage origin ("world", [x, y, z]) or from another character's bone. */
  space?: "actor" | "world" | { actor: string; bone?: string };
  /** [seconds after t0, value, ease into this key?: linear | in | out | io (default) | snap | hold]. */
  keys: [number, Vec3, string?][];
}

interface OverlayBase {
  id: string;
  t0: number;
  t1: number;
  /** Ease in/out, seconds (default 0.25). */
  fade?: number;
}
export type Overlay = OverlayBase &
  (
    | { kind: "look"; target: TargetRef; head?: number; eyes?: number }
    | { kind: "reach"; side: "L" | "R"; target: TargetRef }
    | { kind: "rot"; bone: string; euler: Vec3 }
    | { kind: "face"; face: Record<string, number> }
    | { kind: "fingers"; side: "L" | "R" | "both"; curl: number; thumb?: number; spread?: number }
    | { kind: "clip"; clip: string; mask: BodyMask; in?: number; rate?: number; mirror?: boolean; weight?: number }
    | { kind: "sword"; seq: [number, string][]; two?: boolean; twist?: number }
    | { kind: "struck"; dir?: Vec3; strength?: number }
    | { kind: "lean"; pitch?: number; roll?: number; twist?: number }
    | {
        kind: "strike";
        /** The striking limb. */
        limb: "hand.L" | "hand.R" | "foot.L" | "foot.R";
        target: TargetRef;
        /** Stage second at which it lands (between t0 and t1). */
        apex: number;
        /** Seconds it stays extended after landing (hit-stop included). */
        hold?: number;
        /** Where on the target, metres [right, up, forward] seen from the striker (forward goes through the target). */
        offset?: Vec3;
        /** Bends the path: metres [right, up, forward] added at mid-flight (a hook swings wide, an uppercut dips). */
        via?: Vec3;
      }
    | { kind: "keys"; tracks: KeyTrack[] }
  );

/** A spoken line: drives the mouth from the voice's loudness envelope. */
export interface StageLine {
  id: string;
  t: number;
  dur: number;
  /** Mouth opening 0…1 at 30 fps. */
  mouth: number[];
  text?: string;
  assetId?: string;
}

export interface StageProp {
  id: string;
  kind: "sword" | "staff" | "spear" | "shield" | "bucket" | "lantern" | "box" | "ball";
  /** Held in a hand ("R" / "L"), worn at the hip, or free in the world. */
  hold?: "R" | "L" | "hip" | "back";
  pos?: Vec3;
  rot?: Vec3;
  params?: Record<string, unknown>;
}

export interface StageActor {
  id: string;
  name?: string;
  char: CharSpec;
  /** Engine kit: a ready set of props, cape and hair tail (orez, soldier, garrick, teen, sena…). */
  kit?: string;
  props?: StageProp[];
  /** Where the actor stands at time 0. */
  at: { pos: Vec3; yaw: number };
  blocks: Block[];
  overlays: Overlay[];
  lines: StageLine[];
  scale?: number;
  /** Resting face (calm = {}, stern = {browDown: 0.3, squint: 0.2}…). */
  mood?: Record<string, number>;
}

export type ShotKind = "free" | "wide" | "full" | "medium" | "close" | "extreme" | "ots" | "two" | "follow" | "low" | "high" | "top";
export interface Shot {
  id: string;
  t: number;
  dur: number;
  kind: ShotKind;
  /** Subject actor (and for "ots"/"two" the other one). */
  on?: string;
  other?: string;
  /** Free camera. */
  pos?: Vec3;
  target?: Vec3;
  fov?: number;
  /** Degrees around the subject from their front (0 = facing them, 90 = their left side). */
  yaw?: number;
  /** Camera height offset, metres (framing presets set their own). */
  lift?: number;
  dist?: number;
  roll?: number;
  move?: { kind: "push" | "pull" | "orbit" | "truck" | "crane" | "handheld" | "whip"; amount?: number };
  /** true: the camera travels with the subject; false: it stays where it was set up and pans. Default depends on the framing. */
  track?: boolean;
  /** Handheld shake amount 0…1. */
  shake?: number;
  /** Depth of field: true focuses on the subject. */
  dof?: boolean | { range?: number; max?: number };
  /** Free-camera keys (seconds from the shot start). */
  keys?: { t: number; pos?: Vec3; target?: Vec3; fov?: number }[];
}

export interface StageEnv {
  preset: "studio" | "plain" | "meadow" | "village" | "bridge" | "temple" | "courtyard" | "forest" | "desert" | "snow" | "city" | "rooftop" | "room" | "arena" | "void";
  time?: "dawn" | "day" | "dusk" | "night" | "overcast";
  /** Sun direction: compass yaw and elevation in degrees. */
  sunYaw?: number;
  sunElev?: number;
  fog?: number;
  seed?: number;
  ground?: string;
  /** Extra set pieces: {kind: house|tree|rock|torch|banner|lantern|fence|stall|well|gate|stairs, pos, rot?, scale?}. */
  items?: { kind: string; pos: Vec3; rot?: number; scale?: number; params?: Record<string, unknown> }[];
  weather?: "clear" | "rain" | "snow" | "mist" | "embers" | "motes";
}

export interface StageLook {
  style: "cinematic" | "anime";
  /** Animate characters on twos/threes (2, 3): poses hold for that many frames, the camera stays smooth. */
  step?: number;
  exposure?: number;
  bloom?: number;
  vignette?: number;
  grain?: number;
  sat?: number;
  contrast?: number;
  /** Anime: ink line strength 0…1 and width in px at 1080p. */
  ink?: number;
  inkWidth?: number;
  /** Anime: where shadow starts (0…1) and how soft the edge is. */
  shadeAt?: number;
  shadeSoft?: number;
  /** Letterbox the frame to this aspect (2.39 for scope), or 0 for none. */
  aspect?: number;
}

export interface StageFx {
  id: string;
  kind: "fire" | "smoke" | "embers" | "sparks" | "dust" | "shockwave" | "impact" | "speedlines" | "flash";
  t0: number;
  t1: number;
  pos?: TargetRef;
  size?: number;
  strength?: number;
  color?: string;
}

export interface StageSpec {
  v: 1;
  actors: StageActor[];
  shots: Shot[];
  env: StageEnv;
  look: StageLook;
  fx: StageFx[];
}

export function newStage(): StageSpec {
  return {
    v: 1,
    actors: [],
    shots: [],
    env: { preset: "studio", time: "day" },
    look: { style: "cinematic" },
    fx: [],
  };
}

let n = 0;
export function sid(prefix: string): string {
  n = (n + 1) % 1e6;
  return `${prefix}${Date.now().toString(36).slice(-4)}${n.toString(36)}`;
}

/** Blocks sorted by time (stable). */
export function sortBlocks(a: StageActor): void {
  a.blocks.sort((x, y) => x.t - y.t);
}
export function findActor(s: StageSpec, id: string): StageActor {
  const a = s.actors.find((x) => x.id === id);
  if (!a) throw new Error(`No actor "${id}" on this stage. Actors: ${s.actors.map((x) => x.id).join(", ") || "none yet (stage.actor adds one)"}.`);
  return a;
}
/** Every motion clip a stage uses (so their packs can be loaded before drawing). */
export function clipsOf(s: StageSpec): Set<string> {
  const out = new Set<string>();
  for (const a of s.actors) {
    for (const b of a.blocks) {
      if ((b.kind === "clip" || b.kind === "go" || b.kind === "idle") && b.clip) out.add(b.clip);
      if (b.kind === "pose" && typeof b.pose === "string" && b.pose.startsWith("clip:")) out.add(b.pose.slice(5).split("@")[0]!);
      if (b.kind === "fall") out.add(b.prone ? "139_16" : "139_18");
    }
    for (const o of a.overlays) if (o.kind === "clip") out.add(o.clip);
  }
  return out;
}
/** End of the last thing that happens on the stage, seconds. */
export function stageDuration(s: StageSpec): number {
  let end = 0;
  for (const sh of s.shots) end = Math.max(end, sh.t + sh.dur);
  for (const a of s.actors) {
    for (const b of a.blocks) end = Math.max(end, b.t);
    for (const o of a.overlays) end = Math.max(end, o.t1);
    for (const l of a.lines) end = Math.max(end, l.t + l.dur);
  }
  for (const f of s.fx) end = Math.max(end, f.t1);
  return end;
}
