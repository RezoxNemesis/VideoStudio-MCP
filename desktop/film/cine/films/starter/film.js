// A Cine film: every shot is { dur, build(E) → things, frame(t, things, E) → the lens of that moment }.
// Read cine.help first. Look at frames (cine.frame / cine.sheet) while writing; render when they read right.
import { THREE, V3, clamp, lerp, sm, sm2, seg, rng } from './engine.js';
import * as K from './kit.js';
export const META = { name: 'My Cine Film', width: 2560, height: 1440, fps: 30, order: ['s01'] };
export const S = {};
// 1. A milestone by a road at golden hour. Focus starts on the stone; the crane lifts over the road.
S.s01 = { dur: 8, about: 'rack focus from a stone, crane up', soft: true, async build(E) {
    K.roadSet(E, { mood: 'gold', x0: -30, x1: 90, seed: 11, clear: (x, z) => z > 0 && z < 15 && x > -9 && x < 9, flowers: 0.12 });
    K.birds(E, 7, 20, 14, -40, { seed: 3 });
    return { stone: K.milestone(E, 0.6, 4.25, 'CINE', '1', 'FILM') }; },
  frame(t, c, E) {
    const u = sm2(seg(t, 2.5, 7.5)); E.P.uPost.value.z = 1 - sm(seg(t, 0, 1.2)); // fade in from black
    K.stamp(E, t > 4 ? 'MY FIRST SHOT' : null, sm(seg(t, 4, 5)) * (1 - sm(seg(t, 7, 8))));
    return { p: K.path([[-1.05, 0.66, 5.75], [-1.6, 1.2, 5.2], [-3.2, 2.7, 2.2], [-2.0, 3.6, 1.0]], u), t: K.mix3([0.6, 0.6, 4.25], [30, 1.2, 0], sm(seg(t, 2.2, 6))), fov: lerp(31, 38, u), focus: lerp(1.9, 30, sm(seg(t, 2.2, 4.5))), dof: lerp(11, 2, u) }; } };
