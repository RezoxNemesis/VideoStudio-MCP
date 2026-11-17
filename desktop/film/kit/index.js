// The film kit: everything a code film is made with. A film's code imports it through the project's ./vs.js.
//
//   import { makeSeq, at, move, breathe, town, person, fireArrows, caption } from '../vs.js';
//   export const S01 = makeSeq({ id: 's01', script, set, motion, actors, shots, sound });
//
// World: y up, metres; yaw 0 faces +Z. A sequence = one place and one solved performance, cut into shots (cameras).
export * from '../engine/core.js';
export * from '../engine/scene.js';
export * from '../engine/paintland.js';
export * from '../engine/surface.js';
export { post as pole } from '../engine/scene.js'; // a thin upright (the kit's own `post` is the picture finishing)
export * from '../engine/fx.js';
export * from '../engine/sim.js';
export * from '../engine/camera.js';
export { makeDesign, Character } from '../engine/character.js';
export * from '../engine/horse.js';
export * as post from '../engine/post.js';
export * as crowd from '../engine/crowd.js';
export * from '../engine/crowd.js';
export { ENV } from '../engine/env.js';
export { HANDS } from '../engine/body.js'; // the named hands of real bodies, to start a hand of the film's own from
export { LOOKS, resolveLook } from '../engine/look.js';
export * from './seq.js';
export * from './acting.js';
export * from './titles.js';
export { title, drawTitles, TYPE_STYLES } from '../engine/type.js'; // animated lettering: post.titles
export { transition, TRANSITION_TYPES } from '../engine/transitions.js'; // shot transitions: post.into
export { effect, effects, EFFECT_TYPES, GRADES } from '../engine/effects.js'; // picture effects and grades: post.fx
export { plant, PLANT_KINDS, LEAF } from '../engine/flora.js'; // painted trees and plants: sc.add('world', plant({ p, kind, h }))
export * from './arrows.js';
export * from './riders.js';
export * from './legions.js';
export * from './fights.js';
export * from './sets.js';
export * from './design.js';
export * from './people.js';
export * from './cast.js';
export * from './props.js';
export * from './places.js';
export * from './combat.js';
export * from './dance.js';
export * from './things.js';
export * from './hold.js';
export * from './ballplay.js';
export * from './pictures.js';
export * from './orbs.js';
export * from './moves.js';
export * from './choreo.js';
export * from './gait.js';
export * from './library2d.js';
export * from './director.js';
