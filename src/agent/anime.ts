/**
 * Anime action commands for 2D scenes: effects placed inside a scene (in front of or behind a character), impact
 * beats (hit-stop, shake, zoom punch, flash), held poses, afterimages and auras on any layer, and one-call action
 * moves that combine them the way action animators do.
 *
 * These work on every kind of 2D layer: drawn cels, rigs and puppets, cut-outs, living pictures, 3D layers.
 */
import type { Studio } from "../app/studio";
import * as anim from "../anim/ops";
import type { LayerAnime, SceneAnime } from "../anim/anime";
import { FX_TYPES, type ProcSpec } from "../anim/proc";
import type { AnimLayer, AnimScene, BlendMode } from "../core/model";
import * as ops from "../core/ops";
import { ctx2d, makeCanvas, renderScene } from "../anim/render";
import { toJpeg } from "../render/snapshot";
import type { HostArgs } from "./api";

type Args = HostArgs;
const r3 = (n: number) => Math.round(n * 1000) / 1000;
const GLOWING = new Set(["lightning", "beam", "aura", "fire", "blaze", "sparkle", "hitspark", "clash"]);
export const ANIME_MOVES = ["impact", "dash", "powerup", "beam", "clash", "slash"] as const;

interface FxOpts {
  x?: number;
  y?: number;
  size?: number;
  rot?: number;
  w?: number;
  value?: number;
  color?: string;
  accent?: string;
  seed?: number;
  blend?: BlendMode;
  /** Put the effect just under this layer (so the character stays in front). */
  behind?: string;
  /** Put the effect just over this layer. */
  above?: string;
  opacity?: number;
}

/** Add an effect layer inside a scene for a stretch of time. */
export function addSceneFx(sc: AnimScene, type: string, t: number, dur: number, o: FxOpts = {}): AnimLayer {
  if (!(FX_TYPES as readonly string[]).includes(type)) throw new ops.OpError(`Unknown effect "${type}". One of: ${FX_TYPES.join(", ")}.`);
  const from = Math.max(0, Math.round(t * sc.fps)), to = Math.min(sc.frames, Math.max(from + 1, Math.round((t + dur) * sc.fps)));
  if (from >= sc.frames) throw new ops.OpError(`The scene is ${r3(sc.frames / sc.fps)} s long; ${r3(t)} s is after its end.`);
  const l = anim.addLayer(sc, `FX ${type}`, "proc");
  const spec: ProcSpec = { type: "fx", style: type };
  for (const k of ["x", "y", "size", "rot", "w", "value", "seed"] as const) if (o[k] !== undefined) spec[k] = o[k];
  if (o.color) spec.color = o.color;
  if (o.accent) spec.accent = o.accent;
  l.proc = spec;
  l.span = [from, to];
  l.blend = o.blend ?? (GLOWING.has(type) ? "screen" : "normal");
  if (o.opacity !== undefined) l.opacity = o.opacity;
  const ref = o.behind ?? o.above;
  if (ref) {
    const i = sc.layers.findIndex((x) => x.id === ref);
    if (i < 0) throw new ops.OpError(`No layer ${ref} in this scene.`);
    sc.layers.splice(sc.layers.indexOf(l), 1);
    // layers[0] is the top: "behind" goes after the layer, "above" before it
    const j = sc.layers.findIndex((x) => x.id === ref);
    sc.layers.splice(o.behind ? j + 1 : j, 0, l);
  }
  return l;
}

interface Beat {
  hold?: number;
  shake?: number;
  shakeFrames?: number;
  punch?: number;
  punchFrames?: number;
  flash?: string;
  flashFrames?: number;
  x?: number;
  y?: number;
}
/** An impact beat at time t: hit-stop, shake, zoom punch and flash together. */
export function addBeat(sc: AnimScene, t: number, b: Beat = {}): SceneAnime {
  const f = Math.max(0, Math.min(sc.frames - 1, Math.round(t * sc.fps)));
  const a = (sc.anime ??= {});
  const k = sc.fps / 24; // counts are given for 24 fps and scaled to the scene's rate
  const hold = b.hold ?? 3;
  if (hold > 0) (a.holds ??= []).push([f, Math.max(1, Math.round(hold * k))]);
  const shake = b.shake ?? 1;
  if (shake > 0) (a.shakes ??= []).push([f, Math.max(2, Math.round((b.shakeFrames ?? 10) * k)), shake]);
  const punch = b.punch ?? 0.1;
  if (punch > 0) (a.punches ??= []).push([f, Math.max(3, Math.round((b.punchFrames ?? 9) * k)), punch, b.x, b.y]);
  const flash = b.flash ?? "white";
  if (flash !== "none") (a.flashes ??= []).push([f, Math.max(1, Math.round((b.flashFrames ?? (flash === "invert" ? 2 : 3)) * k)), flash]);
  for (const list of [a.holds, a.shakes, a.punches, a.flashes]) list?.sort((p, q) => p[0] - q[0]);
  return a;
}

function windowOf(sc: AnimScene, from: number | undefined, to: number | undefined): [number, number][] | undefined {
  if (from === undefined && to === undefined) return undefined;
  return [[Math.round((from ?? 0) * sc.fps), Math.round((to ?? sc.frames / sc.fps) * sc.fps)]];
}

/** One-call action moves: the pieces an action animator stacks for a beat. Returns what was added. */
export function addMove(sc: AnimScene, kind: string, t: number, o: { x?: number; y?: number; layerId?: string; dur?: number; rot?: number; color?: string; accent?: string; power?: number }): { layers: string[]; note: string } {
  const x = o.x ?? 0.5, y = o.y ?? 0.5, pw = Math.max(0.3, Math.min(3, o.power ?? 1));
  const made: string[] = [];
  const fx = (type: string, at: number, dur: number, opts: FxOpts = {}) => made.push(addSceneFx(sc, type, at, dur, opts).id);
  const behind = o.layerId;
  const who = behind ? anim.getLayer(sc, behind) : null;
  switch (kind) {
    case "impact": {
      addBeat(sc, t, { hold: Math.round(3 * pw), shake: 1.2 * pw, punch: 0.1 * pw, flash: "white", x, y });
      fx("focuslines", t, 0.4, { x, y, size: 0.2, color: o.color ?? "#ffffff" });
      fx("impact", t, 0.4, { x, y, size: 0.13 * pw, accent: o.accent });
      fx("dust", t + 0.05, 0.9, { x, y: Math.min(0.95, y + 0.12), size: 0.3 * pw });
      return { layers: made, note: "hit-stop, shake, zoom punch, white flash, focus lines, an impact burst and dust" };
    }
    case "clash": {
      addBeat(sc, t, { hold: Math.round(4 * pw), shake: 0.9 * pw, punch: 0.08 * pw, flash: "white", x, y });
      fx("clash", t, 0.5, { x, y, size: 0.22 * pw, accent: o.accent });
      fx("hitspark", t, 0.5, { x, y, size: 0.3 * pw, accent: o.accent });
      fx("shockwave", t + 0.04, 0.6, { x, y, size: 0.3 * pw });
      return { layers: made, note: "hit-stop, shake, flash, a clash burst, sparks and a shockwave" };
    }
    case "slash": {
      addBeat(sc, t, { hold: 2, shake: 0.5 * pw, punch: 0.06 * pw, flash: "invert", flashFrames: 1, x, y });
      fx("slash", t, 0.35, { x, y, size: 0.34 * pw, rot: o.rot ?? -25, accent: o.accent ?? "#ffffff" });
      fx("hitspark", t + 0.04, 0.4, { x, y, size: 0.18 * pw, accent: o.accent });
      return { layers: made, note: "a one-frame inverted flash, a slash arc, sparks and a short hit-stop" };
    }
    case "dash": {
      const dur = o.dur ?? 0.6;
      fx("rushlines", t, dur, { rot: o.rot ?? 0, size: 0.3 * pw, color: o.color ?? "#ffffff", behind });
      if (who) {
        const w = windowOf(sc, t, t + dur);
        who.anime = { ...(who.anime ?? {}), trail: { count: 4, gap: 1, opacity: 0.45, tint: o.accent, when: [...(who.anime?.trail?.when ?? []), ...(w ?? [])] } };
      }
      fx("dust", t, 0.7, { x, y: Math.min(0.95, y + 0.15), size: 0.22 });
      return { layers: made, note: who ? "speed streaks behind the character, afterimages on the character and dust at the start" : "speed streaks and dust (give layerId for afterimages on the character)" };
    }
    case "powerup": {
      const dur = o.dur ?? 2;
      const col = o.accent ?? "#ffd23f";
      addBeat(sc, t, { hold: 0, shake: 0.6 * pw, shakeFrames: Math.round(dur * 24), punch: 0.05, punchFrames: 14, flash: "white", x, y });
      fx("aura", t, dur, { x, y: Math.min(0.98, y + 0.22), size: 0.3 * pw, accent: col, behind });
      fx("lightning", t + 0.1, dur - 0.1, { x, y: Math.min(0.98, y + 0.2), rot: -90, w: 0.42, value: 3, size: 0.1, accent: o.color ?? "#ffffff" });
      fx("shockwave", t, 0.7, { x, y: Math.min(0.98, y + 0.22), size: 0.36 * pw });
      if (who) who.anime = { ...(who.anime ?? {}), aura: { color: col, size: 0.03, strength: 0.75, flicker: 0.4, when: [...(who.anime?.aura?.when ?? []), ...(windowOf(sc, t, t + dur) ?? [])] } };
      return { layers: made, note: "a rising aura, lightning, a ground shockwave, a long rumble and a flash" + (who ? ", plus a glow on the character" : "") };
    }
    case "beam": {
      const dur = o.dur ?? 1.2;
      addBeat(sc, t, { hold: 0, shake: 0.9 * pw, shakeFrames: Math.round(dur * 24), punch: 0.07 * pw, flash: "invert", flashFrames: 2, x, y });
      fx("beam", t, dur, { x, y, rot: o.rot ?? 0, size: 0.16 * pw, w: 1.3, accent: o.accent });
      fx("focuslines", t, Math.min(dur, 0.5), { x, y, size: 0.16, color: o.color ?? "#ffffff" });
      return { layers: made, note: "an energy beam with a charge ball, a two-frame inverted flash, focus lines and a rumble for as long as it fires" };
    }
    default:
      throw new ops.OpError(`kind must be one of ${ANIME_MOVES.join(", ")}.`);
  }
}

function sceneAnime(sc: AnimScene) {
  const fps = sc.fps;
  const sec = (f: number) => r3(f / fps);
  return {
    sceneId: sc.id,
    seconds: sec(sc.frames),
    holds: (sc.anime?.holds ?? []).map(([f, n]) => ({ t: sec(f), frames: n })),
    shakes: (sc.anime?.shakes ?? []).map(([f, n, s]) => ({ t: sec(f), frames: n, strength: s })),
    punches: (sc.anime?.punches ?? []).map(([f, n, a]) => ({ t: sec(f), frames: n, amount: a })),
    flashes: (sc.anime?.flashes ?? []).map(([f, n, k]) => ({ t: sec(f), frames: n, kind: k })),
    layers: sc.layers.map((l) => ({ id: l.id, name: l.name, kind: l.kind, effect: l.kind === "proc" && l.proc?.type === "fx" ? l.proc.style : undefined, from: l.span ? sec(l.span[0]) : undefined, to: l.span ? sec(l.span[1]) : undefined, anime: l.anime })),
  };
}

export const ANIME_COMMANDS: Record<string, { help: string; run: (s: Studio, a: Args) => unknown }> = {
  "anime.fx": {
    help: `{sceneId, type: ${FX_TYPES.join("|")}, t (s in the scene), dur?=1, x?, y? (0..1), size?, rot? (deg: the way rushlines, a beam or lightning runs), w? (length, in picture widths), value? (lightning: how many bolts), color? (ink), accent? (fill), behind? | above? (layer id: place it under or over that layer), blend?, opacity?} Put a hand-drawn effect INSIDE a scene for a stretch of time, in front of or behind a character. focuslines = concentration lines; rushlines = speed streaks; actionbg = a whole speed background; lightning; beam; aura = a rising power flame.`,
    run: (s, a) =>
      s.mutate("anime effect", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const o: FxOpts = {};
        for (const k of ["x", "y", "size", "rot", "w", "value", "seed", "opacity"] as const) if (a.optNum(k) !== undefined) o[k] = a.num(k);
        for (const k of ["color", "accent", "behind", "above"] as const) if (a.optStr(k)) o[k] = a.str(k);
        if (a.optStr("blend")) o.blend = a.str("blend") as BlendMode;
        const l = addSceneFx(sc, a.str("type"), a.num("t", 0), a.num("dur", 1), o);
        return { layerId: l.id, from: r3(l.span![0] / sc.fps), to: r3(l.span![1] / sc.fps) };
      }),
  },
  "anime.beat": {
    help: "{sceneId, t (s), hold?=3 (frames the drawing freezes, counted at 24 fps; 0 = none), shake?=1 (0 = none; 2 = huge), shakeFrames?=10, punch?=0.1 (zoom, 0 = none), punchFrames?=9, flash?: white|black|invert|none (=white), flashFrames?, x?, y? (zoom centre 0..1)} An impact beat: hit-stop + camera shake + zoom punch + flash at one moment. Effects keep moving through the hit-stop, as in action anime.",
    run: (s, a) =>
      s.mutate("anime beat", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        addBeat(sc, a.num("t"), { hold: a.optNum("hold"), shake: a.optNum("shake"), shakeFrames: a.optNum("shakeFrames"), punch: a.optNum("punch"), punchFrames: a.optNum("punchFrames"), flash: a.optStr("flash"), flashFrames: a.optNum("flashFrames"), x: a.optNum("x"), y: a.optNum("y") });
        return sceneAnime(sc);
      }),
  },
  "anime.layer": {
    help: '{sceneId, layerId, step?: 1|2|3 (hold poses: 2 = on twos), trail?: {count?=3, gap?=2 (frames), opacity?=0.5, tint? (#hex), from?, to? (s)} | false, aura?: {color?="#ffd23f", size?=0.035, strength?=0.8, flicker?=0.35, from?, to? (s)} | false} Anime timing and energy for one layer (a character, a rig, a drawing, a 3D layer): poses held on twos or threes, afterimages trailing fast motion, a glowing aura around the silhouette.',
    run: (s, a) =>
      s.mutate("anime layer", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const l = anim.getLayer(sc, a.str("layerId"));
        const cur: LayerAnime = { ...(l.anime ?? {}) };
        if (a.optNum("step") !== undefined) {
          const st = Math.round(a.num("step"));
          if (st > 1) cur.step = Math.min(6, st);
          else delete cur.step;
        }
        const tr = a.any("trail");
        if (tr === false || tr === null) delete cur.trail;
        else if (tr && typeof tr === "object") {
          const o = tr as Record<string, unknown>;
          cur.trail = { count: Math.max(1, Math.min(12, Number(o.count ?? 3))), gap: Math.max(1, Number(o.gap ?? 2)), opacity: Number(o.opacity ?? 0.5), tint: typeof o.tint === "string" ? o.tint : undefined, when: windowOf(sc, o.from as number | undefined, o.to as number | undefined) };
        }
        const au = a.any("aura");
        if (au === false || au === null) delete cur.aura;
        else if (au && typeof au === "object") {
          const o = au as Record<string, unknown>;
          cur.aura = { color: typeof o.color === "string" ? o.color : "#ffd23f", size: Number(o.size ?? 0.035), strength: Number(o.strength ?? 0.8), flicker: Number(o.flicker ?? 0.35), when: windowOf(sc, o.from as number | undefined, o.to as number | undefined) };
        }
        if (Object.keys(cur).length) l.anime = cur;
        else delete l.anime;
        return { layerId: l.id, anime: l.anime ?? null };
      }),
  },
  "anime.move": {
    help: `{sceneId, kind: ${ANIME_MOVES.join("|")}, t (s), x?, y? (where it happens, 0..1), layerId? (the character: effects go behind it, and it gets the afterimages or glow), dur?, rot? (deg), power?=1 (0.3..3), color?, accent?} A whole action beat in one call, stacked the way action animators do: impact (hit-stop, shake, punch, flash, focus lines, burst, dust), dash (speed streaks, afterimages), powerup (aura, lightning, shockwave, rumble), beam (beam, inverted flash, focus lines, rumble), clash (sparks, shockwave), slash (inverted flash, arc, sparks). Everything added is ordinary and can be changed or removed.`,
    run: (s, a) =>
      s.mutate("anime move", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const r = addMove(sc, a.str("kind"), a.num("t"), { x: a.optNum("x"), y: a.optNum("y"), layerId: a.optStr("layerId"), dur: a.optNum("dur"), rot: a.optNum("rot"), color: a.optStr("color"), accent: a.optStr("accent"), power: a.optNum("power") });
        return { added: r.note, layers: r.layers };
      }),
  },
  "anime.sheet": {
    help: "{sceneId, times?: [s…] | count?=8, from?, to? (s), cols?=4, cell?=360 (px wide)} Contact sheet of a 2D scene: the picture at several moments in one image, each labelled with its time. Look at it after every change to action, timing or effects.",
    run: async (s, a) => {
      const sc = anim.getScene(s.project, a.str("sceneId"));
      const end = sc.frames / sc.fps;
      let times = a.any("times") as number[] | undefined;
      if (!Array.isArray(times) || !times.length) {
        const n = Math.max(1, Math.min(24, Math.round(a.num("count", 8))));
        const from = Math.max(0, a.num("from", 0)), to = Math.min(end, a.num("to", end));
        times = Array.from({ length: n }, (_, i) => from + ((to - from) * (i + 0.5)) / n);
      }
      const cols = Math.max(1, Math.min(6, Math.round(a.num("cols", Math.min(4, times.length)))));
      const k = Math.round(a.num("cell", 360)) / sc.width;
      const cw = Math.round(sc.width * k), ch = Math.round(sc.height * k), rows = Math.ceil(times.length / cols);
      const out = makeCanvas(cw * cols, (ch + 18) * rows), oc = ctx2d(out);
      oc.fillStyle = "#0b0d12";
      oc.fillRect(0, 0, out.width, out.height);
      const tile = makeCanvas(cw, ch), tc = ctx2d(tile);
      times.forEach((t, i) => {
        renderScene(tc, sc, Math.max(0, Math.min(sc.frames - 1, Math.round(t * sc.fps))), { scale: k, images: (id) => s.pool.imageNow(id) });
        const x = (i % cols) * cw, y = Math.floor(i / cols) * (ch + 18);
        oc.drawImage(tile as CanvasImageSource, x, y);
        oc.fillStyle = "#ffd76a";
        oc.font = "bold 13px system-ui, sans-serif";
        oc.fillText(`${t.toFixed(2)}s`, x + 5, y + ch + 13);
      });
      return { jpeg: await toJpeg(out, 0.82), times: times.map(r3) };
    },
  },
  "anime.get": {
    help: "{sceneId} The scene's anime action as it stands: hit-stops, shakes, zoom punches, flashes, effect layers with their times, and each layer's timing, afterimages and aura.",
    run: (s, a) => sceneAnime(anim.getScene(s.project, a.str("sceneId"))),
  },
  "anime.clear": {
    help: "{sceneId, what?: holds|shakes|punches|flashes|effects|all (=all), from?, to? (s: only inside this stretch)} Remove anime action from a scene: beats (hit-stops, shakes, punches, flashes) and/or the effect layers placed with anime.fx and anime.move.",
    run: (s, a) =>
      s.mutate("clear anime action", (p) => {
        const sc = anim.getScene(p, a.str("sceneId"));
        const what = a.str("what", "all");
        const f0 = Math.round(a.num("from", 0) * sc.fps), f1 = Math.round(a.num("to", sc.frames / sc.fps) * sc.fps);
        const inside = (f: number) => f >= f0 && f <= f1;
        let n = 0;
        const an = sc.anime;
        if (an) {
          for (const k of ["holds", "shakes", "punches", "flashes"] as const) {
            if (what !== "all" && what !== k) continue;
            const list = an[k] as [number, ...unknown[]][] | undefined;
            if (!list) continue;
            const keep = list.filter((x) => !inside(x[0]));
            n += list.length - keep.length;
            (an as Record<string, unknown>)[k] = keep;
          }
        }
        if (what === "all" || what === "effects") {
          const keep = sc.layers.filter((l) => !(l.kind === "proc" && l.span && l.proc?.type === "fx" && inside(l.span[0])));
          n += sc.layers.length - keep.length;
          sc.layers = keep;
        }
        return { removed: n };
      }),
  },
};
