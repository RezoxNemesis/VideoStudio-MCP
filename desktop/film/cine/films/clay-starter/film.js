// @engine clay
// A CLAY FILM — stop-motion in code: clay puppets, felt and card sets, a miniature lens. (The line above tells
// VideoStudio which engine draws this film.) The guide: clay.help. This starter is two small shots to change.
import { THREE, V3, lerp, clamp, ease, easeIO } from './clay.js';
import { Puppet } from './puppet.js';
import { CAR, buildCoach } from './coach.js';
import { buildExterior } from './exterior.js';
import { SLIP, makeSlip } from './props.js';
import { shoot, sit, blink, lipAt, worldHands, holdOf, setM, paperM, handOn, ride, words, fade, track } from './stage.js';

// the film: a vertical 4K picture, 30 frames a second, the sound in audio.wav (laid under by cine.render)
export const META = { name: 'My Clay Film', width: 2160, height: 3840, fps: 30, audio: 'audio.wav', order: ['seat', 'city'] };
// lip tracks made from the voices by the lips tool (cine.tool lips.py lines.json lips.json); none yet: mouths stay shut
let LIPS = {}; try { LIPS = await (await fetch('./lips.json?v=' + Date.now())).json(); } catch (e) { LIPS = {}; }
const VO = { hello: 1.0 };                                  // when each spoken line starts, in film time (seconds)
const SUBS = [[1.0, 3.2, 'A subtitle goes here.']];         // [from, to, words]

const HOLD = holdOf(SLIP.W, SLIP.H), RX = -0.53, RZ = 0.21; // seat 47: the window seat of bay 0, facing +z
function over(E, T) { E.overlay((g, W, H) => { for (const [a, b, txt] of SUBS) if (T >= a && T < b) words(g, W, H, txt, 0.765, 50, { w: 500, a: fade(T, a, b, 0.12) }); }); }

export const S = {};
// 1 — somebody in the last local reads a slip of paper. The paper is placed first; the hands are found from it.
S.seat = { dur: 5, t0: 0, about: 'a man at the window seat reads a note',
  build(E) { const co = buildCoach(E.scene, E), man = new Puppet('rohan'); E.scene.add(man.root); const slip = makeSlip(['आज फिर…', 'लंच नहीं किया ना?', '— M']); E.scene.add(slip);
    for (const m of co.G.children) if (['bay1', 'handle', 'rail'].includes(m.name)) m.visible = false;                 // set pieces in the camera's way are lifted out, as on a real set
    return { man, slip }; },
  frame(t, c, E) { const T = t + this.t0, PM = paperM([RX, 0.462 + 0.27, RZ + 0.33], [-1, 0.03 * Math.sin(t * 1.3), 0], [0, 0.45, 0.89]); setM(c.slip, PM);
    const f = track([[0, worldHands(sit(RX, RZ, { lean: [8, 0, 0], head: [0, 22, 0], gaze: [0, -14], lids: 0.66, brows: [-0.1, 6], handL: handOn(PM, 'L', HOLD, { pole: [1, -0.8, -0.2] }), handR: handOn(PM, 'R', HOLD, { pole: [-1, -0.8, -0.2] }) }))],
      [2.2, {}], [3.0, { head: [6, 4, 0], gaze: [0, 2], brows: [0.5, 5], lids: 0.8 }, 'out'], [5, {}]]);
    const p = f(t); p.lids = blink(t, [1.4, 3.6], p.lids); p.mouth = lipAt(LIPS.hello, VO.hello, T, [0.15, 0, 1, 0]); c.man.set(p);
    const h = c.man.at('head').toArray(); over(E, T);
    return ride(shoot([h[0], h[1] - 0.12, h[2]], { w: 0.8, fov: 30, az: 8, el: 4, focus: c.man.at('eyes').toArray(), ap: 22 }), T); } };
// 2 — the other model: the train on its bridge over the creek, the city behind it and in the water, a moon in cloud
S.city = { dur: 4, t0: 5, about: 'the last local crosses the city',
  build: (E) => buildExterior(E.scene, E, { moonU: 0.437, moonE: 32 }),
  frame(t, c, E) { c.train.position.z = -4 + t * 1.2; const k = easeIO(t / 4), z = c.train.position.z - 1.2; E.overlay((g, W, H) => words(g, W, H, 'MY CLAY FILM', 0.2, 96, { font: 'Baloo2', w: 800, a: fade(t, 0.6, 3.8, 0.4), c: '#fff3d6', edge: 16 }));
    return { pos: [lerp(9.4, 5.2, k), lerp(2.3, 0.42, k), lerp(-6.4, z + 2.0, k)], target: [-1, lerp(1.75, 2.05, k), lerp(-3.2, z + 0.3, k)], fov: lerp(34, 44, k), focus: [0.18, 1.3, z], ap: 3.2 }; } };
