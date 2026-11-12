// s01 — THE PARK, MORNING. Mira waits on the path; Tom walks up; three lines; they leave together.
//
// A sequence is one place and one continuous performance, cut into shots (cameras). Change anything here and only the
// shots whose picture changes are drawn again.
import { makeSeq, at, move, breathe, V, park, put, person, presetOf } from '../vs.js';

const MIRA = 'youngwoman_03', TOM = 'townsman_07';       // two people from the library (the People tab shows them all)
const SPOT = [0, -3.6], MEET = [-1.25, -3.5];            // where Mira waits and where Tom stops: [x, z] in metres

export const S01 = makeSeq({
  id: 's01',

  // The clock of the sequence: waits, spoken lines (ids from script.json) and marks. A line takes as long as its voice.
  script: [['wait', 6.4], ['say', 'M1', 0.2], ['say', 'T1', 0.5], ['say', 'M2', 0.6], ['wait', 5.2], ['mark', 'end']],
  tail: 0.2,

  // The place. Things from the props catalogue can be added to it.
  set: () => {
    const sc = park({ time: 'day' });
    put(sc, 'stall.yellow', [7, -7.4]);
    put(sc, 'crate', [5.2, -7.0], { yaw: 20 });
    return sc;
  },

  // The performance: who is on the stage and what each one does, on the sequence's clock (T.t('M1') = when line M1
  // starts, T.e('M1') = when it ends). The Stage solves it with motion capture; walks, turns and clips chain by themselves.
  motion: (T) => ({
    actors: [
      { id: 'mira', preset: presetOf(MIRA), kit: 'none', at: SPOT, yaw: -90 },
      { id: 'tom', preset: presetOf(TOM), kit: 'none', at: [-9.5, -3.2], yaw: 90 },
    ],
    steps: [
      { op: 'stage.do', actor: 'tom', actions: [
        { t: 0.2, go: MEET, gait: 'walk', speed: 1.4, face: 90 },
        { t: T.e('M2') + 0.9, go: [10, -3.2], gait: 'walk', speed: 1.35, blend: 0.5 },
      ] },
      { op: 'stage.do', actor: 'mira', actions: [
        { t: 0, idle: true },
        { t: 2.4, clip: '143_25', in: 0.2, out: 2.6, at: { yaw: -90 }, blend: 0.4 },          // she sees him and waves
        { t: 4.9, idle: true, blend: 0.5 },
        { t: T.e('M2') + 0.3, go: [10, -4.1], gait: 'walk', speed: 1.3, blend: 0.5 },
      ] },
      // they look at each other until they leave
      { op: 'stage.overlay', actor: 'mira', add: [{ t0: 0, t1: T.e('M2') + 0.3, fade: 0.4, look: 'tom', head: 0.8 }] },
      { op: 'stage.overlay', actor: 'tom', add: [{ t0: 0, t1: T.e('M2') + 0.9, fade: 0.4, look: 'mira', head: 0.8 }] },
    ],
  }),

  // How the people are drawn and how their faces act. `who` ties an actor to the lines of script.json (lips move).
  actors: (T) => [
    { id: 'mira', design: person(MIRA), who: 'Mira', look: [[0, 'tom']],
      emote: [[0, 'wonder'], [2.6, 'happy', 0.5], [T.t('T1'), 'soft', 0.6], [T.t('M2') - 0.2, 'joy', 0.5], [T.e('M2') + 0.4, 'happy', 0.8]] },
    { id: 'tom', design: person(TOM), who: 'Tom', look: [[0, 'mira']],
      emote: [[0, 'calm'], [3.2, 'happy', 0.8], [T.t('T1') - 0.2, 'soft', 0.4], [T.e('T1'), 'happy', 0.6]] },
  ],

  // The shots: each is a stretch of the sequence's clock seen by one camera. cam(t, ctx) is called for every frame;
  // at(ctx, 'mira', 'head') is where her head is at that moment.
  shots: (T) => {
    const a1 = T.t('M1') - 0.15, b1 = T.e('M1') + 0.3, c1 = T.e('T1') + 0.3, end = T.m('end');
    const pair = (ctx) => V.mid(at(ctx, 'mira', 'head'), at(ctx, 'tom', 'head'));
    return [
      { id: 'a', t0: 0, t1: a1, note: 'the park; Tom walks up, Mira waves', post: { fadeIn: 1.0 },
        cam: (t) => breathe(move({ pos: [-4.2, 1.5, 3.4], target: [-3.4, 1.2, -3.5], fov: 40 }, { pos: [-2.0, 1.5, 2.2], target: [-0.7, 1.3, -3.5], fov: 35 }, t, 0, a1), t, 0.006) },
      { id: 'b', t0: a1, t1: b1, note: 'Mira, past his shoulder',
        cam: (t, ctx) => breathe({ pos: [-2.0, 1.5, -1.9], target: at(ctx, 'mira', 'head', [0, -0.06, 0]), fov: 27 }, t, 0.004, 2) },
      { id: 'c', t0: b1, t1: c1, note: 'Tom answers',
        cam: (t, ctx) => breathe({ pos: [0.8, 1.56, -1.9], target: at(ctx, 'tom', 'head', [0, -0.06, 0]), fov: 27 }, t, 0.004, 5) },
      { id: 'd', t0: c1, t1: end, note: 'together; they leave', post: { fadeOut: 1.2 },
        cam: (t, ctx) => { const p = pair(ctx); return { pos: [p[0] * 0.55 - 0.4, 1.45, 1.9], target: [p[0], 1.28, p[2]], fov: 36 }; } },
    ];
  },

  // The sound, on the same clock: beds (ambience, music) with a start and an end, effects at a moment.
  sound: (T) => {
    const ev = [{ t0: 0, t1: T.dur, amb: 'birds', gain: 0.4 }, { t0: 0.6, t1: T.dur, music: 'theme', gain: 0.6, fadeIn: 1.5, fadeOut: 2.5 }];
    for (let t = 0.5, i = 0; t < 5.8; t += 0.52, i++) ev.push({ t, sfx: 'step', gain: 0.2 + 0.02 * i, pitch: i % 2 ? 0.95 : 1.05, pan: -0.3 });
    for (let t = T.e('M2') + 0.7, i = 0; t < T.dur - 0.3; t += 0.26, i++) ev.push({ t, sfx: 'step', gain: Math.max(0.08, 0.3 - 0.015 * i), pitch: i % 2 ? 0.95 : 1.05, pan: 0.2 });
    return ev;
  },
});
