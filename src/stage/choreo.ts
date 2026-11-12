/**
 * Fight choreography: turns a list of beats ("slash, parried; thrust, lands; …") into timed performances for two
 * characters. Both stand in solved fighting stances (planted feet, sunk hips); every attack lunges, every answer
 * moves the body (parry, block, dodge back, duck, slip); hits stagger or drop the one struck. On top come hit-stop,
 * impact frames, sparks, camera shake and a cut plan. Everything it writes is ordinary blocks, overlays, effects and
 * shots, so the result can be retimed and edited by hand afterwards.
 */
import { sid, type Block, type Overlay, type Shot, type StageActor, type StageFx, type StanceBlock, type Vec3 } from "./spec";

export interface FightBeat {
  /** The attack (see FIGHT_MOVES). */
  do: string;
  /** Who attacks: "a" or "b" (default: they take turns, a first). */
  by?: "a" | "b";
  /** Pause before this beat, seconds (default 0.22). */
  gap?: number;
  /** Does the attack land? (default false: it is defended) */
  hit?: boolean;
  /** How the defender answers when it does not land: parry, block, dodge, duck, slip (default: varies). */
  defend?: "parry" | "block" | "dodge" | "duck" | "slip";
  /** 0.5 light … 2 heavy: strength of the reaction, the impact frame and the camera kick. */
  power?: number;
  /** Tempo of this beat (1 = normal, 1.5 = faster). */
  speed?: number;
  /** The one struck goes down and the fight ends here. */
  ko?: boolean;
  /** Ground the attacker gains with this beat, metres (default 0.16; 0 keeps both where they are). */
  push?: number;
}

interface Move {
  style: "sword" | "fists";
  /** When the blade, fist or foot arrives, seconds from the beat start. */
  apex: number;
  dur: number;
  /** Body lean at the apex, degrees: forward pitch, and twist (sword: as given; fists: towards the striking side). */
  lean: [number, number];
  /** How far the attacker lunges, metres. */
  lunge: number;
  /** Sword positions over the beat: [seconds, position]. */
  seq?: [number, string][];
  two?: boolean;
  /** Fists: the striking limb and where it goes. */
  limb?: "leadHand" | "rearHand" | "leadFoot" | "rearFoot";
  target?: "head" | "chest" | "belly" | "leg";
  /** Path bend [out to the limb's side, up, forward], metres. */
  via?: Vec3;
  offset?: Vec3;
  /** A string of quick strikes: expanded into these moves. */
  combo?: string[];
}

/** Attacks. Sword attacks are built from named sword positions; unarmed ones send a hand or foot to the opponent. */
export const FIGHT_MOVES: Record<string, Move> = {
  slash: { style: "sword", seq: [[0, "guard"], [0.2, "hiR"], [0.33, "loL"], [0.62, "guard"]], apex: 0.31, dur: 0.62, lean: [14, -22], lunge: 0.3 },
  backslash: { style: "sword", seq: [[0, "guard"], [0.2, "hiL"], [0.33, "loR"], [0.62, "guard"]], apex: 0.31, dur: 0.62, lean: [14, 22], lunge: 0.26 },
  overhead: { style: "sword", seq: [[0, "guard"], [0.26, "overhead"], [0.39, "smash"], [0.72, "guard"]], apex: 0.38, dur: 0.72, lean: [22, 0], two: true, lunge: 0.34 },
  thrust: { style: "sword", seq: [[0, "guard"], [0.18, "chamber"], [0.29, "thrust"], [0.56, "guard"]], apex: 0.28, dur: 0.56, lean: [18, -8], lunge: 0.46 },
  sweep: { style: "sword", seq: [[0, "guard"], [0.22, "backR"], [0.36, "outL"], [0.66, "guard"]], apex: 0.33, dur: 0.66, lean: [8, -30], lunge: 0.24 },
  rising: { style: "sword", seq: [[0, "guard"], [0.2, "low"], [0.33, "high"], [0.64, "guard"]], apex: 0.31, dur: 0.64, lean: [6, 10], lunge: 0.22 },
  feint: { style: "sword", seq: [[0, "guard"], [0.16, "hiR"], [0.3, "midF"], [0.5, "guard"]], apex: 0.3, dur: 0.5, lean: [6, -6], lunge: 0.12 },
  flurry: { style: "sword", seq: [[0, "guard"], [0.14, "hiR"], [0.24, "loL"], [0.36, "hiL"], [0.46, "loR"], [0.6, "chamber"], [0.7, "thrust"], [0.98, "guard"]], apex: 0.69, dur: 0.98, lean: [16, -10], lunge: 0.4 },

  jab: { style: "fists", limb: "leadHand", target: "head", apex: 0.15, dur: 0.42, lean: [5, 10], lunge: 0.14 },
  cross: { style: "fists", limb: "rearHand", target: "head", apex: 0.2, dur: 0.55, lean: [9, 34], lunge: 0.22 },
  hook: { style: "fists", limb: "leadHand", target: "head", via: [0.4, 0.02, -0.14], apex: 0.22, dur: 0.58, lean: [4, 30], lunge: 0.1 },
  uppercut: { style: "fists", limb: "rearHand", target: "head", offset: [0, -0.09, -0.03], via: [0.05, -0.42, -0.12], apex: 0.22, dur: 0.6, lean: [-8, 22], lunge: 0.14 },
  body: { style: "fists", limb: "rearHand", target: "belly", apex: 0.2, dur: 0.56, lean: [22, 26], lunge: 0.22 },
  kick: { style: "fists", limb: "rearFoot", target: "belly", apex: 0.3, dur: 0.8, lean: [-14, 6], lunge: 0 },
  highkick: { style: "fists", limb: "rearFoot", target: "head", via: [0.5, -0.1, -0.25], apex: 0.34, dur: 0.92, lean: [-24, 38], lunge: 0 },
  lowkick: { style: "fists", limb: "rearFoot", target: "leg", via: [0.42, 0.05, -0.2], apex: 0.27, dur: 0.72, lean: [-6, 26], lunge: 0 },
  sidekick: { style: "fists", limb: "leadFoot", target: "chest", apex: 0.3, dur: 0.82, lean: [-26, -30], lunge: 0 },
  onetwo: { style: "fists", combo: ["jab", "cross"], apex: 0, dur: 0, lean: [0, 0], lunge: 0 },
  barrage: { style: "fists", combo: ["jab", "cross", "hook", "body", "cross"], apex: 0, dur: 0, lean: [0, 0], lunge: 0 },
};
const DEFENSE: Record<string, { seq: (apex: number) => [number, string][]; lean: [number, number]; retreat?: number; duck?: number; roll?: number }> = {
  parry: { seq: (ap) => [[0, "guard"], [Math.max(0.05, ap - 0.07), "parry"], [ap + 0.12, "parry"], [ap + 0.36, "guard"]], lean: [-4, 12], retreat: -0.08 },
  block: { seq: (ap) => [[0, "guard"], [Math.max(0.05, ap - 0.09), "block"], [ap + 0.14, "block"], [ap + 0.4, "guard"]], lean: [-6, 0], retreat: -0.12 },
  dodge: { seq: (ap) => [[0, "guard"], [Math.max(0.05, ap - 0.1), "side"], [ap + 0.2, "side"], [ap + 0.45, "guard"]], lean: [-20, 14], retreat: -0.4 },
  duck: { seq: (ap) => [[0, "guard"], [Math.max(0.05, ap - 0.1), "low"], [ap + 0.18, "low"], [ap + 0.45, "guard"]], lean: [10, 0], duck: 0.36 },
  slip: { seq: (ap) => [[0, "guard"], [Math.max(0.05, ap - 0.1), "side"], [ap + 0.16, "side"], [ap + 0.4, "guard"]], lean: [6, -18], roll: 20, duck: 0.1 },
};
const DEF_ORDER = ["parry", "block", "dodge", "parry", "duck", "slip"] as const;
const BONE = { head: "head", chest: "spine02", belly: "spine05" } as const;

export interface FightPlan {
  a: { blocks: Block[]; overlays: Overlay[] };
  b: { blocks: Block[]; overlays: Overlay[] };
  fx: StageFx[];
  shots: Shot[];
  beats: { i: number; t: number; apex: number; by: "a" | "b"; do: string; hit: boolean; defend?: string }[];
  end: number;
}

export function compileFight(o: {
  a: StageActor;
  b: StageActor;
  t: number;
  style: "sword" | "fists";
  beats: FightBeat[];
  posA: { pos: Vec3; yaw: number };
  posB: { pos: Vec3; yaw: number };
  spacing?: number;
  impactFrames: boolean;
  cameras: boolean;
  leadA?: "L" | "R";
  leadB?: "L" | "R";
}): FightPlan {
  const R2D = 180 / Math.PI;
  const plan: FightPlan = { a: { blocks: [], overlays: [] }, b: { blocks: [], overlays: [] }, fx: [], shots: [], beats: [], end: o.t };
  const ax = o.posA.pos[0], az = o.posA.pos[2];
  let bx = o.posB.pos[0], bz = o.posB.pos[2];
  let dx = bx - ax, dz = bz - az;
  let dist = Math.hypot(dx, dz);
  if (dist < 1e-3) {
    dx = 0;
    dz = 1;
    dist = 1;
  }
  const want = o.spacing ?? (o.style === "sword" ? 1.9 : 1.15);
  const moveB = Math.abs(dist - want) > 0.3;
  if (moveB) {
    bx = ax + (dx / dist) * want;
    bz = az + (dz / dist) * want;
  }
  const ux = dx / dist, uz = dz / dist;
  const yawA = Math.atan2(dx, dz) * R2D, yawB = Math.atan2(-dx, -dz) * R2D;
  const lead = { a: o.leadA ?? "L", b: o.leadB ?? "L" } as const;
  // both drop into their stances, facing each other
  const stance: Record<"a" | "b", StanceBlock> = {
    a: { id: sid("b"), t: o.t, kind: "stance", blend: 0.3, at: { yaw: yawA }, lead: lead.a, guard: o.style === "fists" ? "fists" : "none", bounce: o.style === "fists" ? 0.7 : 0.25, path: [], lunges: [], ducks: [] },
    b: { id: sid("b"), t: o.t, kind: "stance", blend: 0.3, at: moveB ? { pos: [bx, o.posB.pos[1], bz], yaw: yawB } : { yaw: yawB }, lead: lead.b, guard: o.style === "fists" ? "fists" : "none", bounce: o.style === "fists" ? 0.6 : 0.2, path: [], lunges: [], ducks: [] },
  };
  plan.a.blocks.push(stance.a);
  plan.b.blocks.push(stance.b);
  // how far each has moved forward (towards the other) so far
  const fwd = { a: 0, b: 0 };
  const where = (who: "a" | "b"): Vec3 => (who === "a" ? [ax + ux * fwd.a, 0, az + uz * fwd.a] : [bx - ux * fwd.b, 0, bz - uz * fwd.b]);
  const step = (who: "a" | "b", t0: number, t1: number, d: number) => {
    const p = stance[who].path!;
    const lastT = p.length ? p[p.length - 1]![0] : 0;
    const s0 = Math.max(lastT + 0.02, t0 - o.t);
    p.push([s0, 0, fwd[who]]);
    fwd[who] += d;
    p.push([Math.max(t1 - o.t, s0 + 0.14), 0, fwd[who]]);
  };

  let t = o.t + 0.4;
  let turn: "a" | "b" = "a";
  const seqs: Record<"a" | "b", [number, string][]> = { a: [[0, "guard"]], b: [[0, "guard"]] };
  const push = (who: "a" | "b", at: number, pos: string) => {
    const rel = at - o.t;
    const s = seqs[who];
    if (rel <= s[s.length - 1]![0] + 1e-3) s.push([s[s.length - 1]![0] + 0.04, pos]);
    else s.push([rel, pos]);
  };
  // expand combos into single strikes
  type Beat = FightBeat & { same?: boolean };
  const beats: Beat[] = [];
  for (const beat of o.beats) {
    const mv = FIGHT_MOVES[beat.do];
    if (!mv) throw new Error(`Unknown fight move "${beat.do}". Moves: ${Object.keys(FIGHT_MOVES).join(", ")}.`);
    if (mv.style !== o.style) throw new Error(`"${beat.do}" is a ${mv.style} move; this fight is ${o.style}.`);
    if (!mv.combo) {
      beats.push(beat);
      continue;
    }
    mv.combo.forEach((name, k) => {
      const lastOne = k === mv.combo!.length - 1;
      beats.push({ do: name, by: beat.by, gap: k === 0 ? beat.gap : 0.02, hit: lastOne ? beat.hit : false, defend: lastOne ? beat.defend : k % 2 ? "slip" : "block", power: lastOne ? beat.power : 0.6, speed: (beat.speed ?? 1) * 1.25, ko: lastOne ? beat.ko : false, push: lastOne ? beat.push : 0, same: k > 0 });
    });
  }
  let over = false;
  let fallen: "a" | "b" | null = null;
  beats.forEach((beat, i) => {
    if (over) return;
    const mv = FIGHT_MOVES[beat.do]!;
    const by: "a" | "b" = beat.by ?? (beat.same ? (turn === "a" ? "b" : "a") : turn);
    const df: "a" | "b" = by === "a" ? "b" : "a";
    turn = df;
    const k = 1 / Math.max(0.4, beat.speed ?? 1);
    const power = beat.power ?? 1;
    const hit = !!beat.hit;
    t += beat.gap ?? (i === 0 ? 0 : 0.22);
    const apex = t + mv.apex * k;
    const stop = hit ? 0.07 * Math.min(2, power) : 0.03;
    const A = by === "a" ? plan.a : plan.b, B = by === "a" ? plan.b : plan.a;
    const atk = by === "a" ? o.a : o.b, def = by === "a" ? o.b : o.a;
    const sideOf = (who: "a" | "b", front: boolean): "L" | "R" => ((lead[who] === "L") === front ? "L" : "R");
    let defend: string | undefined = hit ? undefined : (beat.defend ?? DEF_ORDER[i % DEF_ORDER.length]!);
    if (defend === "slip" && o.style === "sword") defend = "dodge";
    const d = defend ? (DEFENSE[defend] ?? DEFENSE.parry!) : null;

    // --- the attacker ---
    if (mv.lunge > 0) stance[by].lunges!.push([apex - o.t, mv.lunge * Math.min(1.3, 0.8 + 0.2 * power), Math.max(0.1, mv.apex * k * 0.7), 0.3, stop + 0.04]);
    if (mv.style === "sword") {
      const strike = mv.seq!.find((x) => x[0] >= mv.apex) ?? mv.seq![mv.seq!.length - 1]!;
      for (const [st, pos] of mv.seq!) {
        const at = t + st * k + (st > strike[0] ? stop : 0);
        push(by, at, pos);
        if (st === strike[0]) push(by, at + stop, pos);
      }
      A.overlays.push({ id: sid("o"), kind: "lean", t0: t, t1: t + mv.dur * k + stop + 0.1, fade: Math.min(0.14, mv.apex * k * 0.6), pitch: mv.lean[0] * Math.min(1.4, power), twist: mv.lean[1] });
    } else {
      const isFoot = mv.limb!.endsWith("Foot");
      const side = sideOf(by, mv.limb!.startsWith("lead"));
      const sg = side === "R" ? 1 : -1;
      const bone = mv.target === "leg" ? `upperleg01.${sideOf(df, true)}` : BONE[mv.target ?? "head"];
      // a defended strike must not land: it stops on the guard, goes past, or passes over
      let off: Vec3 = mv.offset ? [mv.offset[0] * sg, mv.offset[1], mv.offset[2]] : [0, 0, 0];
      if (defend === "block" || defend === "parry") off = [off[0] + (defend === "parry" ? 0.14 * sg : 0), off[1] - 0.04, off[2] - 0.2];
      else if (defend === "dodge") off = [off[0], off[1], off[2] - 0.12];
      else if (defend === "duck") off = [off[0], off[1] + 0.34, off[2] + 0.08];
      else if (defend === "slip") off = [off[0] + 0.24 * sg, off[1] + 0.04, off[2] + 0.06];
      else off = [off[0], off[1], off[2] - (isFoot ? 0.1 : 0.06)];
      A.overlays.push({ id: sid("o"), kind: "strike", limb: `${isFoot ? "foot" : "hand"}.${side}`, target: { actor: def.id, bone }, t0: t, apex, hold: stop + 0.03, t1: t + mv.dur * k + stop, fade: 0.01, offset: off, via: mv.via ? [mv.via[0] * sg, mv.via[1], mv.via[2]] : undefined });
      A.overlays.push({ id: sid("o"), kind: "lean", t0: t, t1: t + mv.dur * k + stop + 0.06, fade: Math.min(0.12, mv.apex * k * 0.7), pitch: mv.lean[0] * Math.min(1.3, power), twist: mv.lean[1] * sg });
    }
    A.overlays.push({ id: sid("o"), kind: "face", t0: Math.max(o.t, t - 0.05), t1: t + mv.dur * k + 0.2, fade: 0.1, face: { anger: 0.75, squint: 0.3, open: 0.3 } });

    // --- the defender ---
    const wa = where(by), wd = where(df);
    const contact: Vec3 = [(wa[0] + wd[0]) / 2, 1.25, (wa[2] + wd[2]) / 2];
    if (hit) {
      const head: Vec3 = [wd[0], mv.target === "belly" || mv.target === "chest" ? 1.2 : mv.target === "leg" ? 0.6 : 1.55, wd[2]];
      if (o.style === "sword") {
        push(df, apex - 0.02, "guard");
        push(df, apex + 0.12, "rest");
        if (!beat.ko) push(df, apex + 0.6 + 0.2 * power, "guard");
      }
      if (o.impactFrames) plan.fx.push({ id: sid("f"), kind: "impact", t0: apex, t1: apex + 0.35, strength: 0.7 + 0.3 * power });
      plan.fx.push({ id: sid("f"), kind: o.style === "sword" ? "sparks" : "shockwave", t0: apex, t1: apex + 1.5, pos: head, strength: 0.6 * power });
      if (beat.ko) {
        B.blocks.push({ id: sid("b"), t: apex + 0.04, kind: "fall", blend: 0.14, push: 0.5 + 0.5 * power, dur: 1.05 });
        B.overlays.push({ id: sid("o"), kind: "face", t0: apex, t1: apex + 3, fade: 0.05, face: { pain: 1, open: 0.5, squint: 0.8 } });
        fallen = df;
        over = true;
      } else {
        B.overlays.push({ id: sid("o"), kind: "struck", t0: apex, t1: apex + 0.55 + 0.2 * power, fade: 0.01, strength: power });
        // a heavy hit drives them back a step
        const back = Math.min(0.6, 0.18 * power * power);
        step(df, apex + 0.03, apex + 0.3 + 0.1 * power, -back);
        step(by, apex + 0.25, apex + 0.6 + 0.1 * power, back);
      }
    } else if (d) {
      if (o.style === "sword") for (const [st, pos] of d.seq(mv.apex * k)) push(df, t + st, pos);
      B.overlays.push({ id: sid("o"), kind: "lean", t0: apex - 0.16, t1: apex + 0.42, fade: 0.1, pitch: d.lean[0], twist: d.lean[1], roll: d.roll });
      if (d.retreat) stance[df].lunges!.push([apex - o.t - 0.02, d.retreat, 0.16, 0.34, 0.06]);
      if (d.duck) stance[df].ducks!.push([apex - o.t - 0.01, d.duck, 0.15, 0.3]);
      if (defend === "parry" && o.style === "fists" && !mv.limb!.endsWith("Foot")) {
        // slap the incoming wrist aside
        const their = `wrist.${sideOf(by, mv.limb!.startsWith("lead"))}`;
        B.overlays.push({ id: sid("o"), kind: "strike", limb: `hand.${sideOf(df, true)}`, target: { actor: atk.id, bone: their }, t0: apex - 0.14, apex: apex - 0.01, hold: 0.05, t1: apex + 0.3, fade: 0.01, offset: [0, 0, -0.04] });
      }
      if (defend === "parry" || defend === "block") {
        plan.fx.push({ id: sid("f"), kind: o.style === "sword" ? "sparks" : "dust", t0: apex, t1: apex + 1.5, pos: contact, strength: 0.8 * power });
        if (o.impactFrames && o.style === "sword") plan.fx.push({ id: sid("f"), kind: "impact", t0: apex, t1: apex + 0.3, strength: 0.2 * power });
      }
      // the attacker presses, the defender gives ground
      const gain = beat.push ?? (defend === "dodge" ? 0.3 : 0.16);
      if (gain) {
        step(by, apex + 0.12, apex + 0.42, gain);
        step(df, apex + 0.06, apex + 0.36, -gain);
      }
    }
    plan.beats.push({ i, t: Math.round(t * 1000) / 1000, apex: Math.round(apex * 1000) / 1000, by, do: beat.do, hit, defend });
    t += mv.dur * k + stop;
    if (hit) t += 0.25 + 0.15 * power;
  });
  const end = t + (over ? 1.6 : 0.5);
  plan.end = end;
  const down: string | null = fallen;
  for (const who of ["a", "b"] as const) {
    const P = who === "a" ? plan.a : plan.b;
    const other = who === "a" ? o.b.id : o.a.id;
    if (o.style === "sword") {
      const s = seqs[who];
      const last = who === down ? (plan.beats[plan.beats.length - 1]?.apex ?? end) - o.t + 0.2 : end - o.t - 0.1;
      s.push([Math.max(s[s.length - 1]![0] + 0.03, last), who === down ? "rest" : "guard"]);
      for (let i = 1; i < s.length; i++) if (s[i]![0] <= s[i - 1]![0]) s[i]![0] = s[i - 1]![0] + 0.03;
      P.overlays.push({ id: sid("o"), kind: "sword", t0: o.t, t1: who === down ? o.t + last + 0.1 : end, fade: 0.3, seq: s, two: beats.some((x) => FIGHT_MOVES[x.do]?.two) });
    }
    if (who !== down) {
      P.overlays.push({ id: sid("o"), kind: "look", t0: o.t, t1: end, target: { actor: other, bone: "head" }, head: 0.7, eyes: 1 });
      P.blocks.push({ id: sid("b"), t: end, kind: "idle", blend: 0.45 });
    }
  }
  if (o.cameras) {
    // a cut plan: establish both, then alternate sides and sizes on the beats, stay with whoever is hit
    const cuts: Shot[] = [];
    const first = plan.beats[0]?.t ?? o.t + 0.4;
    cuts.push({ id: sid("s"), t: o.t, dur: first - o.t, kind: "two", on: o.a.id, other: o.b.id, move: { kind: "push", amount: 0.5 } });
    plan.beats.forEach((bt, i) => {
      const lastBeat = i === plan.beats.length - 1;
      const nextT = lastBeat ? end : plan.beats[i + 1]!.t;
      const atk = bt.by === "a" ? o.a.id : o.b.id, def = bt.by === "a" ? o.b.id : o.a.id;
      const len = nextT - bt.t;
      if (bt.hit) {
        const split = Math.max(0.12, bt.apex - bt.t - 0.02);
        cuts.push({ id: sid("s"), t: bt.t, dur: split, kind: "low", on: atk, yaw: i % 2 ? -56 : 56 });
        if (lastBeat && over) {
          cuts.push({ id: sid("s"), t: bt.t + split, dur: 0.7, kind: "medium", on: def, yaw: i % 2 ? 50 : -50, shake: 0.5 });
          cuts.push({ id: sid("s"), t: bt.t + split + 0.7, dur: len - split - 0.7, kind: "two", on: o.a.id, other: o.b.id, yaw: 20, move: { kind: "pull", amount: 0.4 } });
        } else cuts.push({ id: sid("s"), t: bt.t + split, dur: len - split, kind: "medium", on: def, yaw: i % 2 ? 58 : -58, shake: 0.4 });
      } else {
        const pick = i % 4;
        if (pick === 0) cuts.push({ id: sid("s"), t: bt.t, dur: len, kind: "two", on: o.a.id, other: o.b.id, yaw: 0, move: { kind: "push", amount: 0.6 } });
        else if (pick === 1) cuts.push({ id: sid("s"), t: bt.t, dur: len, kind: "ots", on: def, other: atk, yaw: i % 2 ? -1 : 1 });
        else if (pick === 2) cuts.push({ id: sid("s"), t: bt.t, dur: len, kind: "full", on: atk, yaw: i % 2 ? -62 : 62, shake: 0.2 });
        else cuts.push({ id: sid("s"), t: bt.t, dur: len, kind: "two", on: o.a.id, other: o.b.id, yaw: 180, move: { kind: "orbit", amount: 0.5 } });
      }
    });
    plan.shots = cuts.filter((c) => c.dur > 0.05);
  }
  return plan;
}
