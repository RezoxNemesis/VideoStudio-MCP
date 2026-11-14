// Legions: armies whose every soldier is a drawing of a real performer of the film.
// A few soldiers are performed like anybody else (a sequence's motion, with bodies); `performer` turns one of them
// into a figure a legion can lay down by the thousand: standing, or marching in a stride that repeats.
import { loadBake, poseAt } from '../engine/bake.js';
import { bodyOf } from '../engine/body.js';
import { Character } from '../engine/character.js';
import { bendPose } from './combat.js';
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
 * performer's id there), design, at: seconds (a moment at which he stands) | loop: [t0, t1] (a stride of his march:
 * strideOf finds one), poses: 12 (drawings of the loop), facing: 0 (degrees: the way he faces in his performance),
 * top (m: how high his spear reaches; his own height + 1 without), half: 0.8 (m: half his width with what he carries),
 * mark (his colour far off), hands, bends (kit/combat.js bendPose: [{ bone, about, deg, props }] — a spear carried upright) }
 */
export function performer(o) {
  const bk = loadBake(o.bake), B = bodyOf(bk, o.as), ch = new Character(o.design), d = ch.d;
  const loop = o.loop ?? [o.at ?? 0, o.at ?? 0], poses = o.loop ? (o.poses ?? 12) : 1, face0 = ((o.facing ?? 0) * Math.PI) / 180;
  const h = bk.actors[o.as].height;
  return {
    h, top: o.top ?? h + 1.0, half: o.half ?? 0.8, poses, mark: o.mark ?? d.top?.color ?? '#3a3038',
    paint(g, cam, i, q) {
      const t = loop[0] + ((loop[1] - loop[0]) * i) / poses, raw = poseAt(bk, o.as, t), r = raw.j.root;
      // the performer is turned to face the legion's way and set down where the man stands
      const yaw = (q.yaw * Math.PI) / 180 - face0, c = Math.cos(yaw), s = Math.sin(yaw), xf = { yaw, pos: [q.at[0] - (r[0] * c + r[2] * s), 0, q.at[2] - (-r[0] * s + r[2] * c)] };
      const pose = poseAt(bk, o.as, t, xf);
      if (B) pose.body = { B, t, xf, hands: o.hands };
      if (o.bends) bendPose(pose, o.bends);
      const sc = q.sc, wind = sc?.wind ?? null;
      for (let k = 0; k < 10; k++) ch.step(pose, 1 / 24, t, { wind: null, ground: 0 }); // cloth and hair come to rest
      void wind;
      ch.draw(g, cam, pose, { blink: 0 }, sc ? sc.litAt(0) : (x) => x, { sun: q.sun, light: sc ? sc.light2(cam) : undefined, rim: sc?.o?.rim ?? null, ground: 0, shadow: false, meshFrom: 0 });
    },
  };
}
