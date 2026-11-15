// Legions: armies whose every soldier is a drawing of a real performer of the film.
// A few soldiers are performed like anybody else (a sequence's motion, with bodies); `performer` turns one of them
// into a figure a legion can lay down by the thousand: standing, or marching in a stride that repeats.
import { loadBake, poseAt } from '../engine/bake.js';
import { bodyOf } from '../engine/body.js';
import { Character } from '../engine/character.js';
import { bendPose } from './combat.js';
import { enliven } from '../engine/life.js';
export { legion, ranks } from '../engine/legion.js';

/**
 * One whole stride of a performer's walk or march: [t0, t1], from one fall of the left foot to the next (seconds of
 * the performance), looked for after `from`. Null when the performer does not walk there.
 */
export function strideOf(bakeName, id, from = 0) {
  const bk = loadBake(bakeName), a = bk.actors[id]; if (!a) throw new Error(`no actor ${id} in ${bakeName}`);
  const falls = [];
  for (let f = Math.max(1, Math.round(from * bk.fps)); f < bk.frames; f++) if ((a.contact[f] & 1) && !(a.contact[f - 1] & 1)) { falls.push(f / bk.fps); if (falls.length === 2) break; }
  return falls.length === 2 ? falls : null;
}

/**
 * A figure for a legion, from a performer: o: { bake (the id of the sequence that holds the performance), as (the
 * performer's id there), design, at: seconds (a moment at which he stands: ONE drawing — a frozen man) | wait: [t0, t1]
 * (a stretch in which he stands and waits, best a captured wait: the legion plays it forth and back, every man in his
 * own moment — use this for any army that is looked at for more than a second) | loop: [t0, t1] (a stride of his march:
 * strideOf finds one), poses: 12 (drawings of the loop; a wait: two a second), alive: 1 (breath laid over a wait), facing (degrees: the way he faces in his performance — MEASURED
 * from his hips when left out, so a legion always faces the way it is told to; give it only for a figure meant to stand askew),
 * top (m: how high his spear reaches; his own height + 1 without), half: 0.8 (m: half his width with what he carries),
 * mark (his colour far off), hands, bends (kit/combat.js bendPose: [{ bone, about, deg, props }]), fit(pose, t) (the
 * film's own last word on each drawing: (pose) => plantSpear(pose) stands a soldier's spear on its butt) }
 */
/** The way a body faces, read from its hips (radians; 0 = towards +z, −π/2 = towards −x): joints → yaw. */
export function facingOf(j) {
  const l = j['upperleg01.L'], r = j['upperleg01.R'], sx = l[0] - r[0], sz = l[2] - r[2]; // (from his right hip to his left: his left)
  return Math.atan2(-sz, sx);
}
/**
 * How far a performer must be turned to face a legion's way (radians): from the way his performance faces at that
 * moment (measured, or `facingDeg` when the film gives it) to `yawDeg`.
 */
export function figureTurn(j, yawDeg, facingDeg = null) {
  return (yawDeg * Math.PI) / 180 - (facingDeg != null ? (facingDeg * Math.PI) / 180 : facingOf(j));
}

export function performer(o) {
  // (the performance is read when a figure is first drawn: a sequence's own set — its armies — is built before the
  // Stage has solved that sequence, and can still be made from it)
  let got = null;
  const perf = () => {
    if (got) return got;
    let bk; try { bk = loadBake(o.bake); } catch (e) { if (e && e.code === 'ENOENT') throw new Error(`the performance of ${o.bake} has not been solved yet (build the film, or the sequence ${o.bake}, once): a legion's figure "${o.as}" is drawn from it`); throw e; }
    if (!bk.actors[o.as]) throw new Error(`no actor ${o.as} in the performance of ${o.bake}`);
    return (got = { bk, B: bodyOf(bk, o.as), ch: new Character(o.design), h: bk.actors[o.as].height });
  };
  const loop = o.wait ?? o.loop ?? [o.at ?? 0, o.at ?? 0], dur = o.wait ? o.wait[1] - o.wait[0] : 0;
  const poses = o.wait ? (o.poses ?? Math.max(4, Math.round(dur * 2))) : o.loop ? (o.poses ?? 12) : 1;
  const seed = String(o.as ?? '').length * 3.1 + (o.wait ? o.wait[0] : (o.at ?? 0)) * 0.73;
  return {
    get h() { return perf().h; }, get top() { return o.top ?? perf().h + 1.0; }, half: o.half ?? 0.8, poses, ...(o.wait ? { dur, swing: true } : {}), mark: o.mark ?? o.design?.top?.color ?? '#3a3038',
    paint(g, cam, i, q) {
      const { bk, B, ch } = perf();
      const t = loop[0] + ((loop[1] - loop[0]) * i) / poses, raw = poseAt(bk, o.as, t), r = raw.j.root;
      // the performer is turned to face the legion's way and set down where the man stands
      // (which way he faces there is measured: at the loop's first moment, so a stride does not wag him)
      const yaw = figureTurn(poseAt(bk, o.as, loop[0]).j, q.yaw, o.facing ?? null), c = Math.cos(yaw), s = Math.sin(yaw), xf = { yaw, pos: [q.at[0] - (r[0] * c + r[2] * s), 0, q.at[2] - (-r[0] * s + r[2] * c)] };
      const pose = poseAt(bk, o.as, t, xf);
      if (B) pose.body = { B, t, xf, hands: o.hands };
      if (o.wait && o.alive !== 0) enliven(pose, t, seed, o.alive ?? 1); // (he breathes while he waits)
      if (o.bends) bendPose(pose, o.bends);
      if (o.fit) o.fit(pose, t); // (a film's own fitting of the figure: a spear stood on its butt — kit plantSpear)
      const sc = q.sc, wind = sc?.wind ?? null;
      for (let k = 0; k < 10; k++) ch.step(pose, 1 / 24, t, { wind: null, ground: 0 }); // cloth and hair come to rest
      void wind;
      ch.draw(g, cam, pose, { blink: 0 }, sc ? sc.litAt(0) : (x) => x, { sun: q.sun, light: sc ? sc.light2(cam) : undefined, rim: sc?.o?.rim ?? null, ground: 0, shadow: false, meshFrom: 0 });
    },
  };
}
