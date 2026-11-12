/**
 * Stage runtime: turns a StageSpec into a posed, lit, filmed frame.
 *
 * One runtime per stage layer. It keeps the built scene (actors, set, effects) and rebuilds only what changed in the
 * spec. Evaluation is a pure function of time for bodies, faces and cameras, so any frame can be drawn on its own;
 * cloth and hair simulate forward when frames come in order and settle from rest when the playhead jumps.
 */
import * as THREE from "three";
import { Film, V3, clamp, lerp, smooth, wob } from "./engine/core.js";
import { Stage as EngineStage } from "./engine/stage.js";
import { makeActor, attachRest as attachRestJs } from "./engine/actors.js";
import { Mocap } from "./engine/anim.js";
import { relax } from "./engine/human.js";
import * as SK from "./engine/shotkit.js";
import * as P from "./engine/props.js";
import * as FX from "./engine/fx.js";
import { Tail } from "./engine/sim.js";
import { SW, swordAt, applySword, struck as struckEnv, fall as fallDown } from "./engine/fight.js";
import { IK_HANDLES, applyStance, evalStance, keyValue, setHandle, strikeLimb, strikeWeight } from "./rig";
import { GU } from "./engine/mat.js";
import { loadPack } from "./pack";
import { HumanFactory, type HumanPackMeta } from "./human/factory";
import { motions } from "./motion/library";
import { applyTime, buildEnv, type EnvRuntime } from "./env";
import { clipsOf, type Block, type BodyMask, type GoBlock, type Overlay, type Shot, type StageActor, type StageFx, type StageSpec, type TargetRef, type Vec3 } from "./spec";

// The engine is plain JavaScript; its objects are used through this loose type.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Any = any;

const attachRest = attachRestJs as Any;
const D2R = Math.PI / 180;
const Yax = new THREE.Vector3(0, 1, 0);
const _v = new THREE.Vector3(), _v2 = new THREE.Vector3(), _q = new THREE.Quaternion(), _q2 = new THREE.Quaternion();
const lerpAngle = (a: number, b: number, k: number) => a + Math.atan2(Math.sin(b - a), Math.cos(b - a)) * k;

/* ------------------------------ shared assets ------------------------------ */

class StageAssets {
  factory: HumanFactory | null = null;
  mocap: Any = null;
  private p: Promise<void> | null = null;
  get ready(): boolean {
    return !!this.factory && !!this.mocap;
  }
  /** Bodies and the motion index (the big motion packs load per clip, on demand). */
  load(): Promise<void> {
    if (!this.p) {
      this.p = Promise.all([loadPack<HumanPackMeta>("human.pack"), motions.load()]).then(([pack]) => {
        this.factory = new HumanFactory(pack);
        this.mocap = new Mocap({ index: motions.index, packNow: (s: number) => motions.packNow(s) });
      });
      this.p.catch(() => (this.p = null));
    }
    return this.p;
  }
}
export const stageAssets = new StageAssets();

let film: Any = null;
let filmError: string | null = null;
/** The one GPU renderer every stage shares (HDR, depth of field, bloom, atmosphere, grade). */
export function getFilm(): Any {
  if (film || filmError) return film;
  try {
    const canvas = typeof OffscreenCanvas !== "undefined" ? new OffscreenCanvas(16, 16) : document.createElement("canvas");
    film = new Film(canvas, { width: 1280, height: 720, msaa: 4 });
  } catch (e) {
    filmError = e instanceof Error ? e.message : String(e);
    console.error("Stage renderer could not start:", e);
  }
  return film;
}
export function filmProblem(): string | null {
  return filmError;
}

/* ------------------------------ locomotion ------------------------------ */

/** Default clips by gait: straight, clean cycles from the library (checked by eye). `want` is metres per second. */
const GAITS: Record<string, { q: string; want: number; pmin: number; pmax: number }> = {
  walk: { q: "walk", want: 1.3, pmin: 0.85, pmax: 1.35 },
  stroll: { q: "walk", want: 0.9, pmin: 0.95, pmax: 1.5 },
  march: { q: "march", want: 1.3, pmin: 0.8, pmax: 1.3 },
  jog: { q: "jog", want: 2.6, pmin: 0.55, pmax: 0.95 },
  run: { q: "run", want: 3.8, pmin: 0.5, pmax: 0.9 },
  sneak: { q: "sneak", want: 0.7, pmin: 0.9, pmax: 1.8 },
};
/** The locomotion clip a "go" block should use (stored on the block when it is made, so playback never searches). */
export function pickGait(gait = "walk", speed?: number): string {
  const g = GAITS[gait];
  const name = motions.pick({ q: g?.q ?? gait, straight: true, minDur: g && g.want > 2 ? 0.9 : 2.2, maxDur: 14, want: speed ?? g?.want ?? 1.3 });
  if (!name) throw new Error(`No straight "${gait}" motion in the library. Try gait walk, run, jog, march or sneak, or give a clip.`);
  return name;
}

interface Loco {
  clip: string;
  loop: [number, number];
  /** Metres per second at rate 1 for this body. */
  v: number;
}
interface Anchor {
  x: number;
  y: number;
  z: number;
  yaw: number;
  cut: boolean;
}
interface ActorRt {
  a: Any;
  sig: string;
  chainSig: string;
  anchors: Anchor[];
  snaps: (Any | null)[];
  loco: Map<string, Loco>;
  lastSim: number;
  swordHome: string;
  faceW: Record<string, number>;
  /** The body is in a solved stance (it faces where the stance faces). */
  solved?: boolean;
}

/** Framings: [frame height in body heights, aim height (fraction of the body), fov, lift, depth-of-field range]. */
const FRAMING: Record<string, [number, number, number, number, number]> = {
  wide: [3.4, 0.5, 30, 0.12, 0], full: [1.4, 0.52, 32, 0, 0], medium: [0.66, 0.8, 30, 0, 0.5], close: [0.36, 0.92, 28, -0.02, 0.24], extreme: [0.17, 0.935, 24, 0, 0.16],
  low: [1.55, 0.6, 28, -0.42, 0], high: [1.7, 0.5, 32, 0.8, 0], top: [2.6, 0.4, 30, 3.0, 0], follow: [1.5, 0.55, 34, 0.1, 0], ots: [0.5, 0.9, 30, 0, 0.35], two: [1.5, 0.6, 32, 0, 0],
};

/** A relaxed stand: arms down, feet under the hips with flat soles, and a small living sway. */
function stand(h: Any, t: number): void {
  relax(h);
  for (const sd of ["L", "R"]) {
    const sg = sd === "L" ? 1 : -1;
    h.aim(`upperleg01.${sd}`, _v.set(0.03 * sg, -1, 0.012), `lowerleg01.${sd}`);
    h.aim(`lowerleg01.${sd}`, _v.set(0.012 * sg, -1, -0.02), `foot.${sd}`);
    const foot = h.B[`foot.${sd}`];
    foot.parent.getWorldQuaternion(_q);
    h.root.getWorldQuaternion(_q2);
    foot.quaternion.copy(_q.premultiply(_q2.invert()).invert()).multiply(_q2.setFromAxisAngle(Yax, 0.05 * sg));
  }
  const s = h.height * 13.7;
  h.addRot("spine05", wob(t, s, 0.17) * 1.2, wob(t, s + 1, 0.13) * 2.0, wob(t, s + 2, 0.21) * 1.0);
  h.addRot("spine02", wob(t, s + 3, 0.19) * 0.8, wob(t, s + 4, 0.15) * 1.5, 0);
  h.addRot("neck01", wob(t, s + 5, 0.23) * 1.5, wob(t, s + 6, 0.2) * 3.0, 0);
}

/** How a walk to a point plays out: turn, ease into the stride, travel, ease out and settle. */
interface GoPlan {
  L: Loco;
  D: number;
  heading: number;
  nx: number;
  nz: number;
  /** Seconds spent turning on the spot before setting off (big turns only). */
  delay: number;
  turn: number;
  ta: number;
  td: number;
  /** Seconds of travel (after the delay). */
  move: number;
  /** Block-local second at which the body arrives. */
  arrive: number;
  /** Clip seconds played after x seconds of travel. */
  tau(x: number): number;
}

const MAPPED = ["root", "spine05", "spine03", "spine02", "neck01", "neck02", "head", "clavicle.L", "upperarm01.L", "lowerarm01.L", "wrist.L", "clavicle.R", "upperarm01.R", "lowerarm01.R", "wrist.R", "upperleg01.L", "lowerleg01.L", "foot.L", "upperleg01.R", "lowerleg01.R", "foot.R"];
const armOf = (s: string) => [`clavicle.${s}`, `upperarm01.${s}`, `lowerarm01.${s}`, `wrist.${s}`];
const MASKS: Record<BodyMask, string[]> = {
  upper: ["spine05", "spine03", "spine02", "neck01", "neck02", "head", ...armOf("L"), ...armOf("R")],
  arms: [...armOf("L"), ...armOf("R")],
  armL: armOf("L"),
  armR: armOf("R"),
  head: ["neck01", "neck02", "head"],
  spine: ["spine05", "spine03", "spine02"],
  lower: ["upperleg01.L", "lowerleg01.L", "foot.L", "upperleg01.R", "lowerleg01.R", "foot.R"],
  legs: ["upperleg01.L", "lowerleg01.L", "foot.L", "upperleg01.R", "lowerleg01.R", "foot.R"],
};
const KEEP: Record<string, Record<string, boolean>> = {};
for (const [k, list] of Object.entries(MASKS)) KEEP[k] = Object.fromEntries(MAPPED.filter((b) => !list.includes(b)).map((b) => [b, true]));

const FACE0: Record<string, number> = { blink: 0, smile: 0, sad: 0, anger: 0, fear: 0, surprise: 0, squint: 0, browUp: 0, browDown: 0, pain: 0, purse: 0, sneer: 0, wide: 0 };
export const FACE_SLIDERS = Object.keys(FACE0);
/** Named moods → face sliders. */
export const MOODS: Record<string, Record<string, number>> = {
  calm: {},
  happy: { smile: 0.7, browUp: 0.15 },
  joy: { smile: 1, squint: 0.3, browUp: 0.2 },
  sad: { sad: 0.8, browUp: 0.1 },
  angry: { anger: 0.9, squint: 0.2 },
  furious: { anger: 1.1, sneer: 0.6, wide: 0.2 },
  afraid: { fear: 0.9 },
  surprised: { surprise: 0.9 },
  pain: { pain: 0.9 },
  stern: { browDown: 0.35, squint: 0.25 },
  smirk: { smile: 0.35, squint: 0.2, sneer: 0.25 },
  determined: { browDown: 0.5, squint: 0.3, anger: 0.2 },
  tired: { sad: 0.3, squint: 0.4 },
  disgust: { sneer: 0.9, squint: 0.3 },
};

const env01 = (o: { t0: number; t1: number; fade?: number }, t: number) => {
  const f = Math.max(1e-3, Math.min(o.fade ?? 0.25, (o.t1 - o.t0) / 2));
  return smooth(o.t0, o.t0 + f, t) * (1 - smooth(o.t1 - f, o.t1, t));
};

/* ------------------------------ the runtime ------------------------------ */

/** A free camera for looking at the stage while working on it: positions in stage metres [x, y, z]. */
export interface StageView {
  pos: Vec3;
  target: Vec3;
  fov?: number;
}

export class StageRuntime {
  st: Any;
  env: EnvRuntime | null = null;
  private envSig = "";
  private actors = new Map<string, ActorRt>();
  private fx = new Map<string, { sig: string; obj: THREE.Object3D }>();
  /** Where the subject stood and faced when a shot began (so the camera does not swing when they turn). */
  private shotRef = new Map<string, { sig: string; yaw: number; hips: THREE.Vector3; feetY: number; fix: number }>();
  private lastT = Number.NaN;
  /** Extra camera shake this frame (impacts). */
  private kick = 0;
  /** Problems found while building or posing (shown by stage.check and the Stage page). */
  problems: string[] = [];
  /**
   * A director's view for the Stage page: look at the stage from anywhere instead of through the shot's camera.
   * Set by the page for its own draws only (drawStage puts it back), so renders and exports never see it.
   */
  view: StageView | null = null;

  constructor(readonly film: Any) {
    this.st = new EngineStage(film, { shadowRes: 2048 });
  }

  /** Clips this stage needs in memory before it can be posed. */
  static clips(spec: StageSpec): Set<string> {
    return clipsOf(spec);
  }

  dispose(): void {
    for (const rt of this.actors.values()) this.dropActor(rt);
    this.actors.clear();
    for (const f of this.fx.values()) f.obj.removeFromParent();
    this.fx.clear();
    this.env?.dispose();
    this.env = null;
  }
  private dropActor(rt: ActorRt): void {
    try {
      rt.a.remove(this.st.scene);
      for (const s of rt.a.sims) s.mesh?.geometry?.dispose?.();
      rt.a.h.dispose();
    } catch {
      /* already gone */
    }
  }

  /* ---------- build ---------- */

  /** Bring the built scene in line with the spec (rebuilds only what changed). */
  sync(spec: StageSpec): void {
    this.problems = [];
    const envSig = JSON.stringify(spec.env);
    if (envSig !== this.envSig) {
      // A new set means a new scene: the old one may have left animated pieces behind.
      for (const rt of this.actors.values()) rt.a.remove(this.st.scene);
      for (const f of this.fx.values()) f.obj.removeFromParent();
      this.fx.clear();
      this.env?.dispose();
      this.st = new EngineStage(this.film, { shadowRes: 2048 });
      this.env = buildEnv(this.st, spec.env);
      for (const rt of this.actors.values()) {
        rt.a.add(this.st.scene);
        rt.chainSig = "";
      }
      this.envSig = envSig;
      this.lastT = Number.NaN;
    }
    applyTime(this.st, spec.env);
    const seen = new Set<string>();
    for (const sa of spec.actors) {
      seen.add(sa.id);
      const sig = JSON.stringify([sa.char, sa.kit ?? null, sa.props ?? [], sa.scale ?? 1]);
      const old = this.actors.get(sa.id);
      if (old && old.sig === sig) continue;
      if (old) this.dropActor(old);
      const rt = this.buildActor(sa, sig);
      this.actors.set(sa.id, rt);
    }
    for (const [id, rt] of this.actors)
      if (!seen.has(id)) {
        this.dropActor(rt);
        this.actors.delete(id);
      }
    const fxSeen = new Set<string>();
    for (const f of spec.fx) {
      if (f.kind === "impact" || f.kind === "flash" || f.kind === "speedlines") continue;
      fxSeen.add(f.id);
      const sig = JSON.stringify(f);
      const old = this.fx.get(f.id);
      if (old && old.sig === sig) continue;
      old?.obj.removeFromParent();
      const obj = this.buildFx(f);
      if (obj) {
        this.st.scene.add(obj);
        this.fx.set(f.id, { sig, obj });
      }
    }
    for (const [id, f] of this.fx)
      if (!fxSeen.has(id)) {
        f.obj.removeFromParent();
        this.fx.delete(id);
      }
  }

  private buildActor(sa: StageActor, sig: string): ActorRt {
    const data = stageAssets.factory!.build(sa.id, sa.char);
    const a: Any = makeActor(sa.id, data, stageAssets.mocap, { kit: sa.kit ?? undefined });
    const h = a.h;
    const lm = h.lm;
    const tail = sa.char.hair?.tail;
    if (tail && !a.sims.some((s: Any) => s instanceof Tail))
      a.sims.push(new Tail({ human: h, bone: "head", pos: V3(0, lm.crown[1] - 0.012, lm.crown[2] - 0.035), dir: V3(0, -0.25, -1), length: tail.length, radius: tail.radius ?? 0.034, segments: 14, seed: 2, weight: 0.8, stiff: 0.25 }));
    for (const p of sa.props ?? []) {
      const q = (p.params ?? {}) as Any;
      if (p.kind === "sword") {
        const sw = (a.props.sword = P.sword(q));
        if (p.hold === "hip" || p.hold === "back") {
          const sc = (a.props.scabbard = P.scabbard());
          attachRest(h, sc, "root", V3(0.15, lm.hipY + 0.085, 0.04), V3(-0.12, -0.45, -0.88));
          a.swordAt("hip");
        } else a.hold(sw, p.hold === "L" ? "L" : "R");
      } else if (p.kind === "staff") a.hold((a.props.staff = P.staff(Number(q.length ?? 1.7))), p.hold === "L" ? "L" : "R", { shift: 0.25 });
      else if (p.kind === "spear") a.hold((a.props.spear = P.spear(Number(q.length ?? 2.4))), p.hold === "L" ? "L" : "R", { shift: 0.2 });
      else if (p.kind === "shield") {
        const sh = (a.props.shield = P.shield(0.4 * (h.height / 1.8)));
        const R = h.rest;
        attachRest(h, sh, "lowerarm01.L", R["lowerarm01.L"].head.clone().lerp(R["wrist.L"].head, 0.6).add(h.hand.L.back.clone().multiplyScalar(0.06)), h.hand.L.fwd.clone().negate(), h.hand.L.back);
      } else if (p.kind === "bucket") a.hold((a.props.bucket = P.bucket()), p.hold === "L" ? "L" : "R");
    }
    a.add(this.st.scene);
    return { a, sig, chainSig: "", anchors: [], snaps: [], loco: new Map(), lastSim: Number.NaN, swordHome: a.swordPlace ?? "hand", faceW: {} };
  }

  private buildFx(f: StageFx): THREE.Object3D | null {
    const p = Array.isArray(f.pos) ? f.pos : ([0, 0, 0] as Vec3);
    const o = this.env?.origin ?? [0, 0, 0];
    const x = p[0] + o[0], z = p[2] + o[2];
    const y = p[1] + (this.env?.height(x, z) ?? 0);
    const s = f.size ?? 1;
    switch (f.kind) {
      case "fire": {
        const g = new THREE.Group();
        g.add(FX.fire([{ x, y, z, s }], {}));
        g.add(FX.fireLight(V3(x, y + 0.6 * s, z), { intensity: 50 * s * (f.strength ?? 1) }));
        return g;
      }
      case "smoke": return FX.smoke([{ x, y, z, s }], {});
      case "embers": return FX.embers(Math.round(260 * s), [x - 5 * s, x + 5 * s, y, y + 5 * s, z - 5 * s, z + 5 * s], {});
      case "sparks": return FX.sparkBurst(V3(x, y, z), f.t0, { n: Math.round(70 * (f.strength ?? 1)) });
      case "dust": return FX.dustBurst(V3(x, y, z), f.t0, {});
      case "shockwave": return FX.shockwave(V3(x, y, z), f.t0, {});
      default: return null;
    }
  }

  /* ---------- helpers ---------- */

  private ground(x: number, z: number): number {
    return this.env ? this.env.height(x, z) : 0;
  }
  private origin(): Vec3 {
    return this.env?.origin ?? [0, 0, 0];
  }
  /** Stage coordinates → world (the set's origin, on the ground). */
  private world(p: Vec3, out = new THREE.Vector3()): THREE.Vector3 {
    const o = this.origin();
    const x = p[0] + o[0], z = p[2] + o[2];
    return out.set(x, this.ground(x, z) + p[1], z);
  }
  /** World position of a target reference (after the actors are posed). */
  target(ref: TargetRef, out = new THREE.Vector3()): THREE.Vector3 | null {
    if (ref === "camera") return out.copy(this.st.camera.position);
    if (Array.isArray(ref)) return this.world(ref, out);
    const rt = this.actors.get(ref.actor);
    if (!rt) return null;
    const h = rt.a.h;
    h.root.updateMatrixWorld(true);
    const bone = ref.bone ?? "head";
    if (bone === "eyes") SK.eyePos(rt.a, out);
    else if (h.B[bone]) h.wpos(bone, out);
    else return null;
    if (ref.offset) out.add(_v.set(...ref.offset).applyQuaternion(rt.a.root.quaternion));
    return out;
  }

  private loco(rt: ActorRt, clip: string, gait: string): Loco {
    let L = rt.loco.get(clip);
    if (L) return L;
    const mo = rt.a.mo;
    const c = mo.mc.clip(clip);
    const g = GAITS[gait] ?? GAITS.walk!;
    const fast = c.m.speed > 2;
    const pmin = fast ? 0.45 : g.pmin, pmax = Math.min(fast ? 0.95 : g.pmax, Math.max(0.5, c.dur * 0.6));
    const tStart = clamp(c.dur * 0.3, 0, Math.max(0, c.dur - pmax - 0.65));
    const loop = mo.findLoop(clip, tStart, Math.min(pmin, pmax), pmax) as [number, number];
    const r0 = c.rootAt(loop[0], new THREE.Vector3()), r1 = c.rootAt(loop[1], new THREE.Vector3());
    const dist = Math.hypot(r1.x - r0.x, r1.z - r0.z) * (mo.legT / c.m.leg) * (rt.a.root.scale.y || 1);
    L = { clip, loop, v: Math.max(0.2, dist / (loop[1] - loop[0])) };
    rt.loco.set(clip, L);
    return L;
  }
  /** Distance travelled along a looped clip after `tau` seconds, metres at rate 1. */
  private travel(rt: ActorRt, L: Loco, tau: number): number {
    const c = rt.a.mo.mc.clip(L.clip);
    const per = L.loop[1] - L.loop[0];
    const k = Math.max(0, Math.floor(tau / per));
    const tt = L.loop[0] + tau - k * per;
    const r0 = c.rootAt(L.loop[0], _v), r1 = c.rootAt(L.loop[1], _v2);
    const dx = r1.x - r0.x, dz = r1.z - r0.z;
    const len = Math.hypot(dx, dz) || 1;
    const r = c.rootAt(tt, new THREE.Vector3());
    const along = ((r.x - r0.x) * dx + (r.z - r0.z) * dz) / len;
    return (along + k * len) * (rt.a.mo.legT / c.m.leg) * (rt.a.root.scale.y || 1);
  }

  private goPlan(rt: ActorRt, b: GoBlock, A: Anchor): GoPlan {
    const L = this.loco(rt, b.clip ?? pickGait(b.gait, b.speed), b.gait ?? "walk");
    const dx = b.to[0] - A.x, dz = b.to[1] - A.z;
    const D = Math.hypot(dx, dz);
    const heading = D > 1e-3 ? Math.atan2(dx, dz) : A.yaw;
    const rate = b.speed ? clamp(b.speed / L.v, 0.45, 1.9) : 1;
    let dYaw = (heading - A.yaw) % (Math.PI * 2);
    if (dYaw > Math.PI) dYaw -= Math.PI * 2;
    if (dYaw < -Math.PI) dYaw += Math.PI * 2;
    const turn = clamp(Math.abs(dYaw) / 5.2, 0.15, 0.75);
    const delay = Math.abs(dYaw) > 1.0 ? turn * 0.55 : 0;
    const tc = D / (L.v * rate);
    const fast = L.v * rate > 2.2;
    const ta = Math.min(fast ? 0.3 : 0.42, tc * 0.7), td = Math.min(fast ? 0.34 : 0.46, tc * 0.7);
    const move = tc + (ta + td) / 2;
    const tau = (x: number): number => {
      const q = clamp(x, 0, move);
      let u: number;
      if (q < ta) u = (q * q) / (2 * ta);
      else if (q <= move - td) u = q - ta / 2;
      else u = tc - ((move - q) * (move - q)) / (2 * Math.max(td, 1e-6));
      return u * rate;
    };
    return { L, D, heading, nx: D > 1e-3 ? dx / D : 0, nz: D > 1e-3 ? dz / D : 1, delay, turn, ta, td, move, arrive: delay + move, tau };
  }

  /* ---------- base pose of one block ---------- */

  /** Place the actor and pose the body for block `b` at `local` seconds into it. No overlays, no face. */
  private basePose(rt: ActorRt, sa: StageActor, b: Block | null, A: Anchor, local: number, t: number): void {
    const a = rt.a, h = a.h, mo = a.mo;
    const scale = sa.scale ?? 1;
    const o = this.origin();
    const gx = A.x + o[0], gz = A.z + o[2];
    rt.solved = !!b && b.kind === "stance";
    const place = (x: number, z: number, yaw: number) => a.place(x, this.ground(x, z) + A.y, z, yaw, scale);
    h.resetPose();
    if (!b || b.kind === "idle") {
      place(gx, gz, A.yaw);
      if (b && b.kind === "idle" && b.clip && mo.mc.has(b.clip)) {
        const c = mo.mc.clip(b.clip);
        const t0 = b.in ?? 0;
        const span = Math.max(0.5, c.dur - t0 - 0.1);
        // ping-pong so the idle never jumps at its seam
        const ph = (local / span) % 2;
        mo.apply(b.clip, t0 + (ph < 1 ? ph : 2 - ph) * span, { inPlace: true, face: true });
      } else stand(h, t);
      return;
    }
    if (b.kind === "clip") {
      const c = mo.mc.clip(b.clip);
      const cin = clamp(b.in ?? 0, 0, c.dur), cout = clamp(b.out ?? c.dur, cin + 1 / 30, c.dur);
      let ct = cin + local * (b.rate ?? 1);
      if (b.warp && b.warp.length) {
        const w = b.warp;
        if (local <= w[0]![0]) ct = w[0]![1];
        else if (local >= w[w.length - 1]![0]) ct = w[w.length - 1]![1];
        else for (let i = 0; i < w.length - 1; i++) if (local <= w[i + 1]![0]) {
          ct = lerp(w[i]![1], w[i + 1]![1], (local - w[i]![0]) / Math.max(1e-6, w[i + 1]![0] - w[i]![0]));
          break;
        }
      }
      const opt: Any = { ref: cin, mirror: !!b.mirror, inPlace: !!b.inPlace };
      if (b.loop) {
        opt.loop = [cin, cout];
        if (c.m.travel > 1.2 && !b.inPlace) opt.align = [cin, cout];
        else opt.face = cin;
      } else {
        ct = clamp(ct, cin, cout);
        opt.face = cin;
      }
      place(gx, gz, A.yaw);
      mo.apply(b.clip, ct, opt);
      return;
    }
    if (b.kind === "go") {
      const P = this.goPlan(rt, b, A);
      const L = P.L;
      const x = clamp(local - P.delay, 0, P.move);
      const tau = P.tau(x);
      mo.apply(L.clip, L.loop[0] + tau, { loop: L.loop, align: L.loop });
      const total = this.travel(rt, L, P.tau(P.move)) || P.D || 1;
      const s = P.D > 1e-3 ? (this.travel(rt, L, tau) / total) * P.D : 0;
      h.B.root.position.z = h.rest.root.head.z;
      // the stride grows out of a stand and settles back into one
      const w = P.D > 1e-3 ? Math.min(smooth(P.delay, P.delay + Math.max(0.12, P.ta), local), 1 - smooth(P.arrive - P.td * 0.55, P.arrive + 0.3, local)) : 0;
      if (w < 1) {
        const snap = h.snapshot();
        h.resetPose();
        stand(h, t);
        h.blendTo(snap, w);
      }
      let yaw = lerpAngle(A.yaw, P.heading, smooth(0, P.turn, local));
      if (b.face !== undefined && local > P.arrive - 0.1) yaw = lerpAngle(P.heading, b.face * D2R, smooth(P.arrive - 0.1, P.arrive + 0.6, local));
      place(gx + P.nx * s, gz + P.nz * s, yaw);
      return;
    }
    if (b.kind === "stance") {
      relax(h);
      const S = evalStance(b, local, (mo.legT / 0.87) * scale, t);
      applyStance(a, S, { x: gx, z: gz, yaw: A.yaw }, (x: number, z: number) => this.ground(x, z), A.y, scale, b.guard ?? "none");
      return;
    }
    if (b.kind === "fall") {
      place(gx, gz, A.yaw);
      fallDown(a, local, 0, { dur: b.dur ?? 1.1, push: b.push ?? 0.6, prone: !!b.prone });
      return;
    }
    // pose
    place(gx, gz, A.yaw);
    const pose = b.pose;
    if (typeof pose === "string") {
      if (pose.startsWith("clip:")) {
        const [name, at] = pose.slice(5).split("@");
        const tt = Number(at ?? 0) || 0;
        if (name && mo.mc.has(name)) mo.apply(name, tt, { inPlace: true, face: tt });
        else relax(h);
      } else if (pose === "sit") {
        relax(h);
        SK.sitPose(a, {});
      } else if (pose === "kneel") {
        relax(h);
        SK.kneelPose(a, {});
        SK.floorFix(a, this.ground(gx, gz) + A.y);
      } else if (pose === "tpose") {
        /* rest pose */
      } else if ((SW as Any)[pose] && a.props.sword) {
        relax(h);
        this.swordInHand(rt, true);
        applySword(a, (SW as Any)[pose], {});
      } else relax(h);
    } else {
      relax(h);
      h.pose(pose);
    }
  }

  private swordInHand(rt: ActorRt, want: boolean): void {
    const a = rt.a;
    if (!a.props.sword || !a.props.scabbard) return;
    const where = want ? "hand" : rt.swordHome;
    if (a.swordPlace !== where) a.swordAt(where);
  }

  /** Where an actor is and which way they face when block i ends (the next block continues from there). */
  private endState(rt: ActorRt, sa: StageActor, b: Block, A: Anchor, L: number): { x: number; z: number; yaw: number } {
    if (b.kind === "idle" || b.kind === "pose") return { x: A.x, z: A.z, yaw: A.yaw };
    const a = rt.a, h = a.h;
    const o = this.origin();
    if (b.kind === "go" || b.kind === "stance" || b.kind === "fall") {
      this.basePose(rt, sa, b, A, L, 0);
      return { x: a.root.position.x - o[0], z: a.root.position.z - o[2], yaw: a.root.rotation.y };
    }
    // clip: average the hips' facing over the last moments so a mid-stride sway does not bend the path
    let fx = 0, fz = 0;
    for (const back of [0.2, 0.1, 0]) {
      this.basePose(rt, sa, b, A, Math.max(0, L - back), 0);
      const f = _v.set(0, 0, 1).applyQuaternion(h.B.root.quaternion);
      if (Math.abs(f.y) < 0.8) {
        fx += f.x;
        fz += f.z;
      }
    }
    const sc = a.root.scale.y || 1;
    const off = _v.copy(h.B.root.position).sub(h.rest.root.head).multiplyScalar(sc).applyAxisAngle(Yax, a.root.rotation.y);
    const yawLocal = fx === 0 && fz === 0 ? 0 : Math.atan2(fx, fz);
    return { x: a.root.position.x + off.x - o[0], z: a.root.position.z + off.z - o[2], yaw: a.root.rotation.y + yawLocal };
  }

  /** Anchors (start place and facing) of every block, following the motion from block to block. */
  private chain(rt: ActorRt, sa: StageActor): Anchor[] {
    const sig = JSON.stringify([sa.at, sa.blocks, sa.scale ?? 1]);
    if (rt.chainSig === sig) return rt.anchors;
    const bl = sa.blocks;
    const anchors: Anchor[] = [];
    let A: Anchor = { x: sa.at.pos[0], y: sa.at.pos[1], z: sa.at.pos[2], yaw: sa.at.yaw * D2R, cut: true };
    for (let i = 0; i < bl.length; i++) {
      const b = bl[i]!;
      if (b.at) A = { x: b.at.pos?.[0] ?? A.x, y: b.at.pos?.[1] ?? A.y, z: b.at.pos?.[2] ?? A.z, yaw: b.at.yaw !== undefined ? b.at.yaw * D2R : A.yaw, cut: !!b.at.pos };
      anchors.push(A);
      const next = bl[i + 1];
      if (!next) break;
      let e: { x: number; z: number; yaw: number };
      try {
        e = this.endState(rt, sa, b, A, Math.max(0, next.t - b.t));
      } catch (err) {
        this.problems.push(`${sa.id}: ${(err as Error).message}`);
        e = { x: A.x, z: A.z, yaw: A.yaw };
      }
      A = { x: e.x, y: A.y, z: e.z, yaw: e.yaw, cut: false };
    }
    rt.anchors = anchors;
    rt.snaps = [];
    rt.chainSig = sig;
    return anchors;
  }

  /** The previous block's last pose, re-expressed where block i starts (for the crossfade). */
  private prevSnap(rt: ActorRt, sa: StageActor, i: number, anchors: Anchor[]): Any | null {
    if (rt.snaps[i] !== undefined) return rt.snaps[i];
    const prev = sa.blocks[i - 1] ?? null, cur = sa.blocks[i]!;
    const a = rt.a, h = a.h;
    // before the first action the body stands where the actor was placed
    const pA: Anchor = prev ? anchors[i - 1]! : { x: sa.at.pos[0], y: sa.at.pos[1], z: sa.at.pos[2], yaw: sa.at.yaw * D2R, cut: true };
    this.basePose(rt, sa, prev, pA, prev ? Math.max(0, cur.t - prev.t) : cur.t, prev ? prev.t : cur.t);
    const snap = h.snapshot();
    const sc = a.root.scale.y || 1;
    const pPos = a.root.position.clone(), pYaw = a.root.rotation.y;
    const A = anchors[i]!, o = this.origin();
    const nx = A.x + o[0], nz = A.z + o[2];
    const nPos = _v2.set(nx, this.ground(nx, nz) + A.y, nz);
    const world = snap.__rootPos.clone().multiplyScalar(sc).applyAxisAngle(Yax, pYaw).add(pPos);
    snap.__rootPos.copy(world.sub(nPos).applyAxisAngle(Yax, -A.yaw).divideScalar(sc));
    snap.__rootPos.y = clamp(snap.__rootPos.y, h.rest.root.head.y - 1.2, h.rest.root.head.y + 1.2);
    if (snap.root) snap.root.premultiply(_q.setFromAxisAngle(Yax, pYaw - A.yaw));
    rt.snaps[i] = snap;
    return snap;
  }

  /* ---------- one actor, one frame ---------- */

  private poseActor(rt: ActorRt, sa: StageActor, t: number): void {
    const a = rt.a, h = a.h, mo = a.mo;
    const bl = sa.blocks;
    const anchors = this.chain(rt, sa);
    let i = -1;
    for (let k = 0; k < bl.length; k++) if (bl[k]!.t <= t + 1e-6) i = k;
    const b = i >= 0 ? bl[i]! : null;
    const A = i >= 0 ? anchors[i]! : { x: sa.at.pos[0], y: sa.at.pos[1], z: sa.at.pos[2], yaw: sa.at.yaw * D2R, cut: true };
    const local = b ? t - b.t : t;
    this.basePose(rt, sa, b, A, local, t);
    // crossfade from the block before
    const blend = b ? (b.blend ?? 0.3) : 0;
    const fromStand = i === 0 && !!b && b.t > 1e-3 && !b.at?.pos && b.kind !== "go";
    if (b && ((i > 0 && !A.cut) || fromStand) && blend > 0 && local < blend) {
      const snap = this.prevSnap(rt, sa, i, anchors);
      // prevSnap posed the body to measure; pose this block again, then blend
      this.basePose(rt, sa, b, A, local, t);
      if (snap) h.blendTo(snap, 1 - smooth(0, blend, local));
    }
    const isClip = !!b && (b.kind === "clip" || b.kind === "go");
    if (b && b.kind === "stance" && b.guard === "fists") {
      h.fingers("L", 1, 0.9);
      h.fingers("R", 1, 0.9);
    } else {
      h.fingers("L", 0.3, 0.25);
      h.fingers("R", 0.3, 0.25);
    }
    // keep the head level over captured motion (capture actors look down more than film actors should)
    if (isClip) {
      h.root.updateMatrixWorld(true);
      const f = _v.set(0, 0, 1).applyQuaternion(h.B.head.getWorldQuaternion(_q));
      const ang = Math.asin(clamp(f.y, -1, 1)) * 0.6;
      h.B.neck01.quaternion.multiply(_q.setFromAxisAngle(_v2.set(1, 0, 0), ang * 0.5));
      h.B.head.quaternion.multiply(_q.setFromAxisAngle(_v2.set(1, 0, 0), ang * 0.5));
    }

    // overlays that do not need another actor's position
    const face: Record<string, number> = { ...FACE0, ...(h.look.rest ?? {}), ...(sa.mood ?? {}) };
    let swordActive = false;
    for (const ov of sa.overlays) {
      if (t < ov.t0 - 1e-6 || t > ov.t1 + 1e-6) continue;
      const w = env01(ov, t);
      try {
        switch (ov.kind) {
          case "rot":
            if (h.B[ov.bone]) h.addRot(ov.bone, ov.euler[0] * w, ov.euler[1] * w, ov.euler[2] * w);
            else this.problems.push(`${sa.id}: no bone "${ov.bone}"`);
            break;
          case "face":
            for (const [k, v] of Object.entries(ov.face)) face[k] = lerp(face[k] ?? 0, v, w);
            break;
          case "fingers":
            for (const s of ov.side === "both" ? ["L", "R"] : [ov.side]) h.fingers(s, lerp(0.3, ov.curl, w), lerp(0.25, ov.thumb ?? ov.curl, w), (ov.spread ?? 0) * w);
            break;
          case "lean":
            if (ov.pitch) {
              SK.pitch(a, "spine05", ov.pitch * D2R * 0.5 * w);
              SK.pitch(a, "spine03", ov.pitch * D2R * 0.5 * w);
            }
            if (ov.twist) {
              SK.twist(a, "spine05", ov.twist * D2R * 0.4 * w);
              SK.twist(a, "spine03", ov.twist * D2R * 0.6 * w);
            }
            if (ov.roll) h.addRot("spine04", 0, 0, ov.roll * w);
            break;
          case "clip": {
            const keep = KEEP[ov.mask];
            const rp = h.B.root.position.clone(), rq = h.B.root.quaternion.clone();
            mo.apply(ov.clip, (ov.in ?? 0) + (t - ov.t0) * (ov.rate ?? 1), { keep, w: w * (ov.weight ?? 1), inPlace: true, face: true, mirror: !!ov.mirror });
            h.B.root.position.copy(rp);
            h.B.root.quaternion.copy(rq);
            break;
          }
          case "sword":
            if (a.props.sword) {
              swordActive = true;
              this.swordInHand(rt, true);
              const before = w < 1 ? h.snapshot() : null;
              // in a stance the blade travels with the hips (lunges carry it forward, ducks take it down)
              const shift = b && b.kind === "stance" ? _v.copy(h.B.root.position).sub(h.rest.root.head).multiplyScalar(a.root.scale.y || 1).applyQuaternion(a.root.quaternion).clone() : null;
              if (shift) shift.y *= 0.8;
              applySword(a, swordAt(t - ov.t0, ov.seq), { two: !!ov.two, twist: ov.twist, shift });
              if (before) h.blendTo(before, 1 - w);
            }
            break;
          case "keys":
            for (const tr of ov.tracks) {
              if (IK_HANDLES.includes(tr.on)) continue;
              const v = keyValue(tr, t - ov.t0);
              if (!v) continue;
              if (tr.on === "hips") {
                const sc = a.root.scale.y || 1;
                h.B.root.position.x += (-v[0] * w) / sc;
                h.B.root.position.y += (v[1] * w) / sc;
                h.B.root.position.z += (v[2] * w) / sc;
              } else if (h.B[tr.on]) h.addRot(tr.on, v[0] * w, v[1] * w, v[2] * w);
              else this.problems.push(`${sa.id}: no bone "${tr.on}"`);
            }
            break;
          case "struck": {
            const k = struckEnv(t, ov.t0, Math.max(0.2, ov.t1 - ov.t0)) * (ov.strength ?? 1);
            SK.pitch(a, "spine05", -0.25 * k);
            SK.pitch(a, "spine03", -0.3 * k);
            SK.pitch(a, "neck01", -0.35 * k);
            SK.twist(a, "spine03", 0.25 * k * (ov.dir && ov.dir[0] < 0 ? -1 : 1));
            face.pain = Math.max(face.pain ?? 0, clamp(k * 1.4));
            break;
          }
          default:
            break;
        }
      } catch (err) {
        this.problems.push(`${sa.id}: ${(err as Error).message}`);
      }
    }
    if (!swordActive && !(b && b.kind === "pose" && typeof b.pose === "string" && (SW as Any)[b.pose])) this.swordInHand(rt, false);
    // hands close around whatever they hold
    for (const key of Object.keys(a.props)) {
      const pn: string = a.props[key]?.parent?.name ?? "";
      if (pn === "wrist.R") a.grip("R");
      else if (pn === "wrist.L") a.grip("L");
    }
    // feet stay out of the ground; standing bodies rest on it
    mo.groundFix(this.ground(a.root.position.x, a.root.position.z) + A.y, !b || b.kind === "idle");

    // face: mood, overlays, speech
    let open = 0;
    for (const ln of sa.lines) {
      const f = (t - ln.t) * 30;
      if (f < 0 || f >= ln.mouth.length) continue;
      const k = Math.floor(f);
      open = Math.max(open, ((ln.mouth[Math.max(0, k - 1)] ?? 0) + 2 * (ln.mouth[k] ?? 0) + (ln.mouth[k + 1] ?? 0)) / 4);
    }
    Object.assign(h.face, face);
    h.face.open = Math.max(clamp(open * 0.9), face.open ?? 0);
    h.face.shape = open > 0.05 ? (["A", "E", "A", "O", "E"][Math.floor(t * 7.3 + h.height * 9) % 5] ?? "A") : "A";
    const br = Math.sin(t * 1.5 + h.height * 3);
    h.B.spine02.quaternion.multiply(_q.setFromAxisAngle(_v2.set(1, 0, 0), -0.012 * br));
    h.B.spine04.quaternion.multiply(_q.setFromAxisAngle(_v2.set(1, 0, 0), 0.006 * br));
    h.applyFace(t);
    h.root.updateMatrixWorld(true);
  }

  /** Reaches and gazes: these need everyone posed first. */
  private aimActor(rt: ActorRt, sa: StageActor, t: number): void {
    const a = rt.a, h = a.h;
    let looked = false;
    const frame = (to: THREE.Vector3) => {
      // looking from this body to a point: right, up and forward
      const f = to.clone().sub(h.wpos("root", _v2)).setY(0);
      if (f.lengthSq() < 1e-6) f.set(0, 0, 1).applyQuaternion(a.root.quaternion);
      f.normalize();
      const r = new THREE.Vector3(-f.z, 0, f.x);
      return (v: Vec3) => new THREE.Vector3().addScaledVector(r, v[0]).addScaledVector(Yax, v[1]).addScaledVector(f, v[2]);
    };
    for (const ov of sa.overlays) {
      if (t < ov.t0 - 1e-6 || t > ov.t1 + 1e-6) continue;
      const w = env01(ov, t);
      try {
        if (ov.kind === "reach") {
          const p = this.target(ov.target, new THREE.Vector3());
          if (!p) continue;
          const before = h.snapshot();
          SK.reach(a, ov.side, p);
          h.blendTo(before, 1 - w);
          h.root.updateMatrixWorld(true);
        } else if (ov.kind === "strike") {
          const p = this.target(ov.target, new THREE.Vector3());
          if (!p) continue;
          const ws = strikeWeight(t, ov.t0, ov.apex, ov.hold ?? 0.06, ov.t1);
          if (ws <= 0) continue;
          const fr = frame(p);
          if (ov.offset) p.add(fr(ov.offset));
          strikeLimb(a, ov.limb, p, ws, ov.via ? fr(ov.via) : null);
        } else if (ov.kind === "keys") {
          for (const tr of ov.tracks) {
            if (!IK_HANDLES.includes(tr.on)) continue;
            const v = keyValue(tr, t - ov.t0);
            if (!v) continue;
            let p: THREE.Vector3 | null;
            const sp = tr.space ?? "actor";
            if (sp === "world") p = this.world(v, new THREE.Vector3());
            else if (sp === "actor") p = new THREE.Vector3(-v[0], v[1], v[2]).applyQuaternion(a.root.quaternion).add(a.root.position);
            else {
              p = this.target({ actor: sp.actor, bone: sp.bone }, new THREE.Vector3());
              if (p) p.add(frame(p)(v));
            }
            if (p) setHandle(a, tr.on, p, w);
          }
        }
      } catch (err) {
        this.problems.push(`${sa.id}: ${(err as Error).message}`);
      }
    }
    for (const ov of sa.overlays) {
      if (ov.kind !== "look" || t < ov.t0 - 1e-6 || t > ov.t1 + 1e-6) continue;
      const p = this.target(ov.target, new THREE.Vector3());
      if (!p) continue;
      h.lookAt(p, (ov.head ?? 0.45) * env01(ov, t), (ov.eyes ?? 1) * env01(ov, t), true);
      looked = true;
    }
    if (!looked) h.eyesRot(wob(t, h.height * 7, 0.7) * 5, wob(t, h.height * 9, 0.6) * 3 - 2);
    h.root.updateMatrixWorld(true);
  }

  /* ---------- camera ---------- */

  private facing(rt: ActorRt): number {
    const h = rt.a.h;
    // a stance faces where its feet are set, however the hips are turned
    if (rt.solved) return rt.a.root.rotation.y;
    const f = _v.set(0, 0, 1).applyQuaternion(h.B.root.quaternion);
    return rt.a.root.rotation.y + (Math.abs(f.y) < 0.8 ? Math.atan2(f.x, f.z) : 0);
  }

  /** Is the straight view from a camera position to its aim point blocked by somebody else's body? */
  private blocked(pos: THREE.Vector3, aim: THREE.Vector3, except: string): boolean {
    const d = aim.clone().sub(pos);
    const len = d.length();
    if (len < 1e-3) return false;
    d.multiplyScalar(1 / len);
    for (const [id, o] of this.actors) {
      if (id === except) continue;
      const hp = o.a.h.wpos("root", _v2);
      const H = o.a.h.height * (o.a.root.scale.y || 1);
      // test the hips and the chest
      for (const up of [0, 0.3 * H]) {
        const c = _v.set(hp.x, hp.y + up, hp.z).sub(pos);
        const along = c.dot(d);
        if (along < 0.15 || along > len - 0.25) continue;
        if (c.addScaledVector(d, -along).length() < 0.4) return true;
      }
    }
    return false;
  }

  /** Camera position and aim for a framing that follows one character. */
  private subjectCam(rt: ActorRt, shot: Shot, t: number, ref: { yaw: number; hips: THREE.Vector3; feetY: number } | undefined, yawFix: number): { pos: THREE.Vector3; aim: THREE.Vector3; fov: number; dist: number; eye: THREE.Vector3; dofR: number } {
    const a = rt.a, h = a.h;
    const H = h.height * (a.root.scale.y || 1);
    const feet = a.root.position;
    const hips = h.wpos("root", new THREE.Vector3());
    const eye = SK.eyePos(a, new THREE.Vector3());
    const mv = shot.move, amt = mv?.amount;
    const u = clamp((t - shot.t) / Math.max(1e-3, shot.dur));
    const e = u * u * (3 - 2 * u);
    const locked = shot.track === undefined ? ["wide", "high", "top", "low"].includes(shot.kind) : !shot.track;
    let yawDeg = (shot.yaw ?? 28) + yawFix;
    const [frameH, aimK, fov0, lift0, dofR] = FRAMING[shot.kind] ?? FRAMING.full!;
    const fov = shot.fov ?? fov0;
    // follow the body as it really is: a crouch, a duck or a fall lowers the aim; a lean carries it with the head
    const posture = clamp((eye.y - feet.y) / (0.935 * H), 0.12, 1.1);
    const toHead = aimK > 0.7 ? 0.7 : 0.3;
    const aim = new THREE.Vector3(lerp(hips.x, eye.x, toHead), feet.y + aimK * H * posture, lerp(hips.z, eye.z, toHead));
    if (shot.kind === "close" || shot.kind === "extreme") aim.lerp(_v.set(eye.x, eye.y - (shot.kind === "close" ? 0.035 * H : 0), eye.z), 0.75);
    let dist = shot.dist ?? ((frameH * H) / 2 / Math.tan((fov * D2R) / 2));
    let lift = (shot.lift ?? 0) + lift0 * H * Math.max(0.5, posture);
    if (shot.kind === "follow" && shot.yaw === undefined) yawDeg = 180 + yawFix;
    let truck = 0;
    if (mv?.kind === "push") dist *= 1 - 0.35 * (amt ?? 1) * e;
    else if (mv?.kind === "pull") dist *= 1 + 0.5 * (amt ?? 1) * e;
    else if (mv?.kind === "orbit") yawDeg += (amt ?? 40) * e;
    else if (mv?.kind === "crane") lift += (amt ?? 1) * e;
    else if (mv?.kind === "truck") truck = (amt ?? 1.5) * (e - 0.5);
    else if (mv?.kind === "whip") yawDeg += (amt ?? 70) * (1 - smooth(0, Math.min(0.3, shot.dur * 0.5), t - shot.t));
    const faceYaw = shot.kind === "follow" || !ref ? this.facing(rt) : ref.yaw;
    const dir = _v.set(0, 0, 1).applyAxisAngle(Yax, faceYaw + yawDeg * D2R);
    const base = locked && ref ? new THREE.Vector3(ref.hips.x, ref.feetY + aimK * H, ref.hips.z) : aim;
    const pos = base.clone().addScaledVector(dir, dist);
    pos.y += lift + (shot.kind === "top" ? 0 : 0.02 * H);
    if (truck) pos.addScaledVector(_v2.crossVectors(dir, Yax).normalize(), truck);
    pos.y = Math.max(pos.y, this.ground(pos.x, pos.z) + 0.12);
    return { pos, aim, fov, dist, eye, dofR };
  }

  private camera(spec: StageSpec, t: number): void {
    const st = this.st;
    if (this.view) {
      const v = this.view;
      const tg = this.world(v.target, new THREE.Vector3());
      st.cam(this.world(v.pos, new THREE.Vector3()), tg, v.fov ?? 40, 0, null);
      st.dof(null);
      st.shadow(tg, clamp(st.camera.position.distanceTo(tg) * 3.2, 12, 90));
      return;
    }
    let shot: Shot | null = null;
    for (const s of spec.shots) if (s.t <= t + 1e-6 && t < s.t + s.dur + 1e-6 && (!shot || s.t >= shot.t)) shot = s;
    // between and after shots the last one holds (a cut to a default view would read as a mistake)
    if (!shot) for (const s of spec.shots) if (s.t <= t + 1e-6 && (!shot || s.t >= shot.t)) shot = s;
    const shake = (amt: number) => (amt > 0 ? { amp: 0.012 * amt, rot: 0.004 * amt, speed: 1.6, t } : null);
    if (!shot) {
      // no shot yet: a wide three-quarter view of everyone
      const c = new THREE.Vector3();
      let n = 0, r = 1.15;
      for (const rt of this.actors.values()) {
        c.add(rt.a.root.position);
        n++;
      }
      if (n) c.multiplyScalar(1 / n);
      else c.copy(this.world([0, 0, 0]));
      for (const rt of this.actors.values()) r = Math.max(r, rt.a.root.position.distanceTo(c) + 1.2);
      const d = (r * 1.25) / Math.tan((34 * D2R) / 2);
      st.cam(_v.set(c.x + d * 0.42, c.y + 1.5 + d * 0.16, c.z + d * 0.9), _v2.set(c.x, c.y + 0.95, c.z), 34, 0, shake(this.kick * 6));
      st.dof(null);
      st.shadow(c, clamp(r * 5, 10, 70));
      return;
    }
    const u = clamp((t - shot.t) / Math.max(1e-3, shot.dur));
    const e = u * u * (3 - 2 * u);
    const mv = shot.move;
    const amt = mv?.amount;
    const sh = shake((shot.shake ?? 0) * 6 + (mv?.kind === "handheld" ? (amt ?? 1) * 5 : 0) + this.kick * 8);
    const roll = (shot.roll ?? 0) * D2R;
    const rt = shot.on ? this.actors.get(shot.on) : undefined;
    if (shot.kind === "free" || !rt) {
      let pos = shot.pos ?? [3, 1.6, 6], tg = shot.target ?? [0, 1.1, 0], fov = shot.fov ?? 35;
      const keys = shot.keys;
      if (keys && keys.length) {
        const lt = t - shot.t;
        let k0 = keys[0]!, k1 = keys[keys.length - 1]!;
        for (let i = 0; i < keys.length - 1; i++) if (lt >= keys[i]!.t && lt <= keys[i + 1]!.t) {
          k0 = keys[i]!;
          k1 = keys[i + 1]!;
          break;
        }
        if (lt <= keys[0]!.t) k1 = k0 = keys[0]!;
        else if (lt >= keys[keys.length - 1]!.t) k0 = k1;
        const x = k1.t > k0.t ? clamp((lt - k0.t) / (k1.t - k0.t)) : 0;
        const k = x * x * (3 - 2 * x);
        const mix = (a?: Vec3, b?: Vec3, d?: Vec3): Vec3 => {
          const p = a ?? d!, q = b ?? a ?? d!;
          return [lerp(p[0], q[0], k), lerp(p[1], q[1], k), lerp(p[2], q[2], k)];
        };
        pos = mix(k0.pos, k1.pos, pos);
        tg = mix(k0.target, k1.target, tg);
        fov = lerp(k0.fov ?? fov, k1.fov ?? k0.fov ?? fov, k);
      }
      const P0 = this.world(pos, new THREE.Vector3()), T0 = this.world(tg, new THREE.Vector3());
      if (mv?.kind === "push") P0.lerp(T0, 0.35 * (amt ?? 1) * e);
      else if (mv?.kind === "pull") P0.lerp(T0, -0.5 * (amt ?? 1) * e);
      else if (mv?.kind === "orbit") P0.sub(T0).applyAxisAngle(Yax, (amt ?? 40) * D2R * e).add(T0);
      else if (mv?.kind === "crane") P0.y += (amt ?? 1.5) * e;
      st.cam(P0, T0, fov, roll, sh);
      if (shot.dof) st.dof(T0, typeof shot.dof === "object" ? (shot.dof.range ?? 0.35) : 0.35, typeof shot.dof === "object" ? (shot.dof.max ?? 12) : 12);
      else st.dof(null);
      st.shadow(T0, clamp(P0.distanceTo(T0) * 2.2, 10, 80));
      return;
    }
    const a = rt.a, h = a.h;
    const H = h.height * (a.root.scale.y || 1);
    const feet = a.root.position;
    const hips = h.wpos("root", new THREE.Vector3());
    const eye = SK.eyePos(a, new THREE.Vector3());
    const ref = this.shotRef.get(shot.id);
    const other = shot.other ? this.actors.get(shot.other) : undefined;
    if (shot.kind === "two" && other) {
      const [frameH, aimK, fov0, lift0] = FRAMING.two!;
      const fov = shot.fov ?? fov0;
      const oh = other.a.h.wpos("root", new THREE.Vector3());
      const sep = oh.distanceTo(hips);
      const aim = new THREE.Vector3((hips.x + oh.x) / 2, feet.y + aimK * H, (hips.z + oh.z) / 2);
      let yawDeg = shot.yaw ?? 0;
      if (mv?.kind === "orbit") yawDeg += (amt ?? 40) * e;
      const side = _v.set(oh.x - hips.x, 0, oh.z - hips.z).normalize().applyAxisAngle(Yax, Math.PI / 2 + yawDeg * D2R);
      const aspect = this.film.W / Math.max(1, this.film.H);
      let dist = shot.dist ?? Math.max(((sep + 1.5) / 2 / Math.tan((fov * D2R) / 2)) / aspect, (frameH * H) / 2 / Math.tan((fov * D2R) / 2));
      if (mv?.kind === "push") dist *= 1 - 0.3 * (amt ?? 1) * e;
      else if (mv?.kind === "pull") dist *= 1 + 0.45 * (amt ?? 1) * e;
      const pos = aim.clone().addScaledVector(side, dist);
      pos.y += (shot.lift ?? 0) + lift0 * H + 0.05 * H + (mv?.kind === "crane" ? (amt ?? 1) * e : 0);
      pos.y = Math.max(pos.y, this.ground(pos.x, pos.z) + 0.12);
      st.cam(pos, aim, fov, roll, sh);
      st.dof(null);
      st.shadow(aim, clamp(dist * 2.2, 10, 70));
      return;
    }
    if (shot.kind === "ots" && other) {
      const fov = shot.fov ?? FRAMING.ots![2];
      const oe = SK.eyePos(other.a, new THREE.Vector3());
      const toSub = _v.set(eye.x - oe.x, 0, eye.z - oe.z).normalize();
      const right = _v2.crossVectors(toSub, Yax).normalize();
      const sd = (shot.yaw ?? 1) >= 0 ? 1 : -1;
      // behind the near shoulder, far enough back that the head in front frames the shot instead of filling it
      const pos = oe.clone().addScaledVector(toSub, -1.5 * (1 - 0.25 * (mv?.kind === "push" ? e : 0))).addScaledVector(right, 0.6 * sd);
      pos.y += 0.1 + (shot.lift ?? 0);
      const tg = eye.clone().addScaledVector(right, -0.3 * sd);
      st.cam(pos, tg, fov, roll, sh);
      st.dof(eye, 0.3, 12);
      st.shadow(eye, 14);
      return;
    }
    const c = this.subjectCam(rt, shot, t, ref, ref?.fix ?? 0);
    st.cam(c.pos, c.aim, c.fov, roll, sh);
    const wantDof = shot.dof ?? c.dofR > 0;
    if (wantDof) st.dof(shot.kind === "close" || shot.kind === "extreme" ? c.eye : c.aim, typeof shot.dof === "object" ? (shot.dof.range ?? c.dofR) : c.dofR || 0.4, typeof shot.dof === "object" ? (shot.dof.max ?? 14) : 14);
    else st.dof(null);
    st.shadow(c.aim, clamp(c.dist * 2.0, 8, 70));
  }

  /** Remember where the active shot's subject stood and faced when the shot began, and pick an angle nobody blocks. */
  private refShots(spec: StageSpec, t: number): void {
    let sig0: string | null = null;
    for (const shot of spec.shots) {
      if (!shot.on || shot.kind === "free" || t < shot.t - 1e-6 || t > shot.t + shot.dur + 1e-6) continue;
      const rt = this.actors.get(shot.on);
      const sa = spec.actors.find((x) => x.id === shot.on);
      if (!rt || !sa) continue;
      sig0 ??= JSON.stringify(spec.actors.map((x) => [x.id, x.at, x.blocks, x.scale ?? 1]));
      const sig = `${shot.t}|${shot.kind}|${shot.yaw ?? ""}|${shot.dist ?? ""}|${shot.fov ?? ""}|${sig0}`;
      const old = this.shotRef.get(shot.id);
      if (old && old.sig === sig) continue;
      try {
        for (const x of spec.actors) {
          const o = this.actors.get(x.id);
          if (o) this.poseActor(o, x, shot.t);
        }
        const ref = { sig, yaw: this.facing(rt), hips: rt.a.h.wpos("root", new THREE.Vector3()), feetY: rt.a.root.position.y, fix: 0 };
        if (shot.kind !== "two" && shot.kind !== "ots" && shot.kind !== "follow" && shot.kind !== "top" && this.actors.size > 1) {
          for (const d of [0, 20, -20, 40, -40, 60, -60, 80, -80, 100, -100]) {
            const c = this.subjectCam(rt, shot, shot.t, ref, d);
            if (!this.blocked(c.pos, c.aim, shot.on)) {
              ref.fix = d;
              break;
            }
          }
        }
        this.shotRef.set(shot.id, ref);
      } catch {
        /* the frame's own posing reports the problem */
      }
    }
    if (this.shotRef.size > 200) this.shotRef.clear();
  }

  /* ---------- frame ---------- */

  /** Pose everything for time t (seconds). dt is the frame step, used by cloth and hair. */
  evaluate(spec: StageSpec, t: number, dt: number): void {
    const st = this.st, L = st.L, look = spec.look;
    L.flash = 0;
    L.invert = 0;
    L.ca = 0;
    L.fade = 0;
    L.desat = 0;
    L.streak = 0;
    L.bloom.strength = look.bloom ?? 0.6;
    L.vignette = look.vignette ?? 0.5;
    L.grain = look.grain ?? 0.03;
    L.sat = look.sat ?? 1;
    L.contrast = look.contrast ?? 1.05;
    if (look.exposure !== undefined) L.exposure *= look.exposure;
    const anime = look.style === "anime";
    (GU as Any).uToon.value = anime ? 1 : 0;
    (GU as Any).uToonAt.value = look.shadeAt ?? 0.42;
    (GU as Any).uToonSoft.value = look.shadeSoft ?? 0.03;
    L.ink = anime ? (look.ink ?? 0.9) : 0;
    L.inkWidth = look.inkWidth ?? 1.6;
    if (anime) {
      L.grain = look.grain ?? 0.012;
      L.sat = look.sat ?? 1.12;
    }
    this.refShots(spec, t);
    const step = Math.max(1, Math.round(look.step ?? 1));
    const tPose = step > 1 ? Math.floor(t / (step * dt) + 1e-6) * step * dt : t;
    for (const sa of spec.actors) {
      const rt = this.actors.get(sa.id);
      if (!rt) continue;
      try {
        this.poseActor(rt, sa, tPose);
      } catch (err) {
        this.problems.push(`${sa.id}: ${(err as Error).message}`);
      }
    }
    for (const sa of spec.actors) {
      const rt = this.actors.get(sa.id);
      if (!rt) continue;
      try {
        this.aimActor(rt, sa, tPose);
      } catch (err) {
        this.problems.push(`${sa.id}: ${(err as Error).message}`);
      }
    }
    // cloth and hair: forward when frames come in order, settle from rest after a jump
    const seq = Math.abs(tPose - (this.lastT + dt * step)) < dt * 0.6 || tPose === this.lastT;
    for (const rt of this.actors.values()) {
      if (!rt.a.sims.length) continue;
      if (tPose === this.lastT) continue;
      if (seq) rt.a.step(tPose, dt * step);
      else {
        rt.a.resetSims();
        for (let k = 12; k >= 0; k--) rt.a.step(tPose - k * dt, dt);
      }
    }
    this.lastT = tPose;
    // impacts and flashes
    this.kick = 0;
    for (const f of spec.fx) {
      if (f.kind === "impact") this.kick = Math.max(this.kick, SK.impact(st, t, f.t0, f.strength ?? 1) as number);
      else if (f.kind === "flash" && t >= f.t0 && t <= f.t1) L.flash = Math.max(L.flash, (f.strength ?? 1) * (1 - (t - f.t0) / Math.max(1e-3, f.t1 - f.t0)));
      else {
        const o = this.fx.get(f.id);
        if (o) o.obj.visible = t >= f.t0 - 1e-6 && t <= f.t1 + 1e-6;
      }
    }
    this.camera(spec, t);
    st.tick(t, dt);
    if (this.env) FX.follow(this.env.followers as Any, st.camera);
  }

  /** Render the posed frame at W×H and return the canvas holding it. */
  render(W: number, H: number, t: number): CanvasImageSource {
    const f = this.film, st = this.st;
    f.setSize(W, H);
    f.look = st.L;
    st.camera.aspect = W / H;
    st.camera.updateProjectionMatrix();
    f.render(st.scene, st.camera, t);
    return f.renderer.domElement as CanvasImageSource;
  }

  /** Where a stage point lands in the picture of the last evaluated frame: [x, y] from 0 to 1 (top-left origin), and whether it is in front of the camera. */
  project(p: Vec3, aspect: number): { x: number; y: number; front: boolean } {
    const cam = this.st.camera as THREE.PerspectiveCamera;
    cam.aspect = aspect;
    cam.updateProjectionMatrix();
    cam.updateMatrixWorld(true);
    const o = this.origin();
    const v = _v.set(p[0] + o[0], p[1], p[2] + o[2]);
    const front = _v2.copy(v).applyMatrix4(cam.matrixWorldInverse).z < 0;
    v.project(cam);
    return { x: v.x * 0.5 + 0.5, y: 0.5 - v.y * 0.5, front };
  }
  /** The ground point under a picture position (x, y from 0 to 1) in the last evaluated frame, in stage metres [x, z]; null when the ray misses the ground. */
  groundAt(x: number, y: number, aspect: number): [number, number] | null {
    const cam = this.st.camera as THREE.PerspectiveCamera;
    cam.aspect = aspect;
    cam.updateProjectionMatrix();
    cam.updateMatrixWorld(true);
    const far = _v.set(x * 2 - 1, 1 - y * 2, 0.5).unproject(cam);
    const from = cam.position;
    const dir = _v2.copy(far).sub(from).normalize();
    if (dir.y > -1e-4) return null;
    const o = this.origin();
    // flat-ground guess first, then two refinements against the set's real ground height
    let gy = this.ground(from.x, from.z);
    let hx = 0, hz = 0;
    for (let i = 0; i < 3; i++) {
      const k = (gy - from.y) / dir.y;
      if (!(k > 0) || k > 400) return null;
      hx = from.x + dir.x * k;
      hz = from.z + dir.z * k;
      gy = this.ground(hx, hz);
    }
    return [hx - o[0], hz - o[2]];
  }
  /** The camera of the last evaluated frame in stage metres (for drawing it in a director's view). */
  cameraState(): { pos: Vec3; target: Vec3; fov: number } {
    const cam = this.st.camera as THREE.PerspectiveCamera, o = this.origin();
    const d = cam.getWorldDirection(_v);
    return { pos: [cam.position.x - o[0], cam.position.y, cam.position.z - o[2]], target: [cam.position.x + d.x * 4 - o[0], cam.position.y + d.y * 4, cam.position.z + d.z * 4 - o[2]], fov: cam.fov };
  }

  /** Facts about the posed frame (for checks and the Stage page). */
  actorState(id: string): { pos: Vec3; yaw: number; head: Vec3; height: number } | null {
    const rt = this.actors.get(id);
    if (!rt) return null;
    const a = rt.a, o = this.origin();
    const hp = a.h.wpos("head", new THREE.Vector3());
    return { pos: [a.root.position.x - o[0], a.root.position.y, a.root.position.z - o[2]], yaw: this.facing(rt) / D2R, head: [hp.x - o[0], hp.y, hp.z - o[2]], height: a.h.height };
  }
  /**
   * The sculpted body of an actor — mesh parts with skin weights, the rest skeleton, colours — for a renderer outside
   * the app that draws the real body instead of a stand-in. `scale` is the size of the body on the stage.
   */
  body(id: string): { data: Any; scale: number; colors: Record<string, number[]> } | null {
    const rt = this.actors.get(id);
    if (!rt) return null;
    const h = rt.a.h;
    h.root.updateWorldMatrix(true, false);
    return { data: h.man, scale: h.root.getWorldScale(new THREE.Vector3()).y, colors: (h.look?.colors ?? {}) as Record<string, number[]> };
  }
  /**
   * The posed skeleton of an actor, for a renderer outside the app: joint positions in stage metres ("name:tail" is a
   * bone's far end), world orientations of the bones asked for, and where its props are.
   */
  skeleton(id: string, joints: string[], quats: string[]): { pos: number[]; quat: number[]; props: { name: string; m: number[]; visible: boolean }[] } | null {
    const rt = this.actors.get(id);
    if (!rt) return null;
    const h = rt.a.h, o = this.origin();
    const pos: number[] = [], quat: number[] = [];
    for (const j of joints) {
      const tail = j.endsWith(":tail"), name = tail ? j.slice(0, -5) : j;
      const b = h.B[name] as THREE.Bone | undefined;
      if (!b) {
        pos.push(NaN, NaN, NaN);
        continue;
      }
      if (tail) {
        const r = h.rest[name];
        b.localToWorld(_v.copy(r.tail).sub(r.head));
      } else _v.setFromMatrixPosition(b.matrixWorld);
      pos.push(_v.x - o[0], _v.y, _v.z - o[2]);
    }
    for (const q of quats) {
      const b = h.B[q] as THREE.Bone | undefined;
      if (!b) {
        quat.push(0, 0, 0, 1);
        continue;
      }
      b.getWorldQuaternion(_q);
      quat.push(_q.x, _q.y, _q.z, _q.w);
    }
    const props: { name: string; m: number[]; visible: boolean }[] = [];
    for (const [name, pr] of Object.entries((rt.a.props ?? {}) as Record<string, THREE.Object3D>)) {
      if (!pr) continue;
      pr.updateWorldMatrix(true, false);
      pr.getWorldPosition(_v);
      pr.getWorldQuaternion(_q);
      let visible = true;
      for (let x: THREE.Object3D | null = pr; x; x = x.parent) if (!x.visible) visible = false;
      props.push({ name, m: [_v.x - o[0], _v.y, _v.z - o[2], _q.x, _q.y, _q.z, _q.w], visible });
    }
    return { pos, quat, props };
  }
  /** World positions of both feet (ankle bones) for sliding checks. */
  feet(id: string): { L: Vec3; R: Vec3; ankle: number } | null {
    const rt = this.actors.get(id);
    if (!rt) return null;
    const h = rt.a.h;
    const l = h.wpos("foot.L", new THREE.Vector3()), r = h.wpos("foot.R", new THREE.Vector3());
    return { L: [l.x, l.y, l.z], R: [r.x, r.y, r.z], ankle: h.rest["foot.L"].head.y * (rt.a.root.scale.y || 1) };
  }
  /**
   * Which captured clip drives the body at time t, where in that clip, and which feet the capture says are on the
   * ground (bit 1 = left, bit 2 = right). `steady` is false while two motions are being blended.
   */
  motionAt(sa: StageActor, t: number): { block: string; kind: string; clip: string | null; ct: number; contact: number; steady: boolean } | null {
    const m = this.motionAt0(sa, t);
    if (!m) return null;
    let free = 0;
    for (const ov of sa.overlays) {
      if (t < ov.t0 - 1e-6 || t > ov.t1 + 1e-6) continue;
      if (ov.kind === "strike" && ov.limb.startsWith("foot")) free |= ov.limb.endsWith(".L") ? 1 : 2;
      if (ov.kind === "keys") for (const tr of ov.tracks) if (tr.on.startsWith("foot.")) free |= tr.on.endsWith(".L") ? 1 : 2;
    }
    m.contact &= ~free;
    return m;
  }
  private motionAt0(sa: StageActor, t: number): { block: string; kind: string; clip: string | null; ct: number; contact: number; steady: boolean } | null {
    const rt = this.actors.get(sa.id);
    if (!rt) return null;
    const bl = sa.blocks;
    let i = -1;
    for (let k = 0; k < bl.length; k++) if (bl[k]!.t <= t + 1e-6) i = k;
    const b = i >= 0 ? bl[i]! : null;
    if (!b) return { block: "", kind: "idle", clip: null, ct: 0, contact: 3, steady: true };
    const local = t - b.t;
    const anchors = this.chain(rt, sa);
    const A = anchors[i]!;
    const blending = ((i > 0 && !A.cut) || (i === 0 && b.t > 1e-3 && !b.at?.pos && b.kind !== "go")) && local < (b.blend ?? 0.3);
    const mo = rt.a.mo;
    try {
      if (b.kind === "clip") {
        const c = mo.mc.clip(b.clip);
        const cin = clamp(b.in ?? 0, 0, c.dur), cout = clamp(b.out ?? c.dur, cin + 1 / 30, c.dur);
        let ct = cin + local * (b.rate ?? 1);
        if (b.loop) ct = cin + ((ct - cin) % (cout - cin));
        else ct = clamp(ct, cin, cout);
        return { block: b.id, kind: "clip", clip: b.clip, ct, contact: b.inPlace ? 0 : c.contactAt(ct, !!b.mirror), steady: !blending && !b.warp };
      }
      if (b.kind === "go") {
        const P = this.goPlan(rt, b, A);
        if (local >= P.arrive || local < P.delay) return { block: b.id, kind: "go", clip: P.L.clip, ct: 0, contact: 3, steady: local > P.arrive + 0.35 || local < P.delay * 0.2 };
        const per = P.L.loop[1] - P.L.loop[0];
        const x = local - P.delay;
        const ct = P.L.loop[0] + (P.tau(x) % per);
        return { block: b.id, kind: "go", clip: P.L.clip, ct, contact: mo.mc.clip(P.L.clip).contactAt(ct), steady: !blending && x > P.ta * 0.9 && x < P.move - P.td * 0.6 };
      }
      if (b.kind === "stance") {
        const S = evalStance(b, local, (mo.legT / 0.87) * (sa.scale ?? 1), t);
        return { block: b.id, kind: "stance", clip: null, ct: local, contact: (S.feet.L.lift < 0.004 ? 1 : 0) | (S.feet.R.lift < 0.004 ? 2 : 0), steady: !blending };
      }
      if (b.kind === "fall") return { block: b.id, kind: "fall", clip: null, ct: local, contact: 0, steady: false };
    } catch {
      return null;
    }
    return { block: b.id, kind: b.kind, clip: null, ct: 0, contact: 3, steady: !blending };
  }

  /** Where an actor ends up after their last block (so the next action can be planned). */
  endOf(sa: StageActor, t: number): { pos: Vec3; yaw: number; head: Vec3; height: number } | null {
    const rt = this.actors.get(sa.id);
    if (!rt) return null;
    this.poseActor(rt, sa, t);
    return this.actorState(sa.id);
  }
  /** Speed (m/s), distance and arrival time (seconds after the action starts) of a walk or run. */
  planGo(sa: StageActor, blockId: string): { speed: number; seconds: number; distance: number } | null {
    const rt = this.actors.get(sa.id);
    const i = sa.blocks.findIndex((x) => x.id === blockId);
    const b = sa.blocks[i];
    if (!rt || !b || b.kind !== "go") return null;
    const A = this.chain(rt, sa)[i];
    if (!A) return null;
    const P = this.goPlan(rt, b, A);
    return { speed: P.D / Math.max(1e-3, P.move), seconds: P.arrive, distance: P.D };
  }
}
