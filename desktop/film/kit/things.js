// Things people take, carry and set down.
import { V, clamp, smooth } from '../engine/core.js';

/**
 * A thing that lies somewhere until a hand takes it, then goes where the hand goes; set down, it stays where it was set.
 *   dyn: (ts, ctx) => [bag(ts, ctx)]      with   const bag = carried({ rest: [x, y, z], by: 'boy', hand: 'R', from: 2.7, make: (p, yaw) => new Mesh(box(p, 0.36, 0.27, 0.15, yaw, { side: '#b5382e' })) })
 * o: rest (where it lies), by (actor), hand 'R' | 'L', from (the moment the hand closes on it), to? + drop? (when and
 *    where it is set down), hang (m below the wrist it is held, 0.25), yaw / yawHeld (degrees: as it lies / as it is held),
 *    take (seconds it takes to come into the hand, 0.07: it never jumps), make(p, yaw) → something with draw(g, cam, sc).
 * Put the thing where the hand really is at `from`: solve once, read the hand's place (where.mjs … actor:wrist.R), move `rest` there.
 */
export function carried(o) {
  const hang = o.hang ?? 0.25, jn = `wrist.${o.hand ?? 'R'}`, take = o.take ?? 0.07;
  return (ts, ctx) => {
    const held = ts >= o.from && (o.to === undefined || o.to === null || ts < o.to);
    let p, yaw;
    if (!held) { p = ts < o.from ? o.rest : (o.drop ?? o.rest); yaw = o.yaw ?? 0; }
    else {
      const h = ctx.poses[o.by].j[jn], hp = [h[0], h[1] - hang, h[2]], k = smooth(clamp((ts - o.from) / take));
      p = k < 1 ? V.lerp(o.rest, hp, k) : hp; yaw = o.yawHeld ?? o.yaw ?? 0;
    }
    return { p, draw: (g, cam, sc) => o.make(p, yaw).draw(g, cam, sc) };
  };
}
