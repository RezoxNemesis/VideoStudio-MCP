# Code Studio — films written as code

A film is a folder of small JavaScript files. The code says who is in a scene, what they do and say, where the cameras
are; VideoStudio speaks the lines, solves the body motion (motion capture + IK), draws every shot and joins the film.
Each shot is kept as its own video: when the code changes, only the shots whose picture changed are drawn again and
the film is joined again without re-encoding — a fix to one shot of a long film takes seconds.

## The loop

1. `code.new {name}` (or `code.open {film}`) — start from the starter film and read it: `code.read {file:"seq/s01.js"}`.
2. Change the code: `code.write {file, text}` for a whole file, `code.patch {file, find, replace}` for a part.
   The answer says at once whether the code loads (and the mistake if not) and how many shots must be drawn.
3. Look before drawing: `code.frame {shot, t}` draws one frame now; `code.sheet {shot}` draws six moments of a shot.
4. `code.build` — speaks new lines, solves changed performances, makes new music, draws only the changed shots (several
   at once), mixes the sound, joins the film. `code.build {draft:true}` is a quick half-size version.
5. `code.check` looks at every frame of the joined film for flashes; `code.shots {off:[…]}` leaves shots out without
   deleting them; `code.export` saves the film to the Videos folder; `code.toTimeline` puts it into the editor.

## The folder

```
project.json   { name, width, height, fps, langs: ["en","hi"], crf, preset, off: [shot ids left out] }
film.js        export const FILM = [S01, S02, …]   (the sequences in order; optional: export const CAST = { name: design })
seq/s01.js     one sequence each (see below)
script.json    the spoken lines and who says them in which voice
cues.json      music and sound beds made by the studio's composer (optional)
vs.js          written by VideoStudio: import everything from '../vs.js'
.vs/           everything made from the code (voices, motion, sounds, shots, the joined film) — never edit
```

World: metres, y up, the ground is y = 0. Yaw 0 faces +z; yaw 90 faces +x. Places are built so the camera stands at
+z and looks toward −z. Time `t` in a sequence is seconds on that sequence's own clock.

## A sequence

```js
import { makeSeq, at, move, breathe, V, park, put, person, presetOf } from '../vs.js';

export const S01 = makeSeq({
  id: 's01',                                       // also the file name: seq/s01.js
  script: [['wait', 2], ['say', 'M1', 0.2], ['say', 'T1', 0.5], ['wait', 3], ['mark', 'end']],
  set: () => park({ time: 'day' }),                // the place
  motion: (T) => ({ actors: […], steps: […] }),    // the performance (solved by the Stage)
  actors: (T) => [ … ],                            // how people are drawn and how their faces act
  shots: (T) => [ … ],                             // the cameras
  sound: (T) => [ … ],                             // ambience, music, effects
});
```

**script** builds the clock: `['wait', seconds]`, `['say', lineId, gapBefore = 0.35, { vo: true }?]` (a line lasts as
long as its voice; `vo` = narrator, no lips), `['mark', name]`, `['at', seconds]`. In every function `T` gives:
`T.t('M1')` start of a line, `T.e('M1')` its end, `T.m('end')` a mark, `T.dur` the whole length. `tail` adds seconds.

**motion(T)** → `{ actors: [{ id, preset, kit: 'none', at: [x, z], yaw }], steps: [ … ] }`.
`preset` is the body: for a library person use `presetOf(id)`; others: villager, villager_woman, woman, girl, boy, teen,
elder, merchant, master, lady, soldier, captain, swordsman, king, warlord. Steps are Stage commands:

```js
{ op: 'stage.do', actor: 'tom', actions: [
  { t: 0, idle: true },
  { t: 1, go: [x, z], gait: 'walk' | 'stroll' | 'march' | 'jog' | 'run' | 'sneak', speed: 1.4, face: 90 },
  { t: 6, clip: '143_25', in: 0.2, out: 2.6, at: { yaw: -90 }, blend: 0.4 },   // a motion-capture clip (stage.motions finds them)
  { t: 9, pose: 'kneel' | 'sit' | 'clip:113_02@1.55', blend: 0.8 },
  { t: 12, fall: { push: 0.6 } },
] }
{ op: 'stage.overlay', actor: 'tom', add: [
  { t0: 0, t1: 8, fade: 0.4, look: 'mira', head: 0.8 },                         // head and eyes follow someone
  { t0: 2, t1: 5, fade: 0.4, lean: { pitch: 8 } },
  { t0: 3, t1: 6, fade: 0.4, keys: [{ on: 'hand.R', keys: [[0, [0.2, 1.2, 0.3]], [1, [0.3, 1.5, 0.2], 'io']] }] }, // a hand to a point (IK)
] }
{ op: 'stage.fight', … }                                                        // sword and fist fights: see {"op":"help"} stage.fight
```
Actions chain: each starts where the last left the body. `at: { pos, yaw }` jumps instead. Useful clips: `143_25` wave,
`113_02` bow. A changed performance is solved again (a few seconds); shots that do not show the change stay as they are.

**actors(T)** → `[{ id, design, who?, emote?, look?, mouth?, bias? }]`
- `design`: `person('townsman_07')` from the library (People tab, `code.people`), with changes
  `person('townsman_07', { top: { color: '#7a2a2a' }, cape: { color: '#222', len: 0.6 } })`, or a design of the film's own.
- `who`: the name in script.json whose lines move this actor's lips.
- `emote: [[t, name, blend?], …]` — neutral calm soft stern determined angry rage sad grief weep afraid shock happy joy
  laugh smirk sneer pain tired fading dead wonder; or `{ like: 'happy', tears: 0.5, blush: 0.4 }`.
- `look: [[t, 'otherActorId' | 'cam' | [x, y, z]], …]` where the eyes go.

**shots(T)** → `[{ id, t0, t1, cam, note?, dof?, post?, hide?, only?, actor?, over?, top?, fx?, settle? }]`
- `cam: (t, ctx, tInShot) => ({ pos: [x, y, z], target: [x, y, z], fov: 35, roll?, shake?: { amp, freq } })`.
  `at(ctx, 'mira', 'head' | 'chest' | 'hips' | 'feet', [dx, dy, dz])` is a point of a body right now.
  `move(camA, camB, t, t0, t1)` eases from one set-up to another; `breathe(cam, t, 0.005)` adds a living drift.
- `dof: (t, ctx) => ({ near, far, blur: 6 })` soft focus outside near…far metres from the lens.
- `post: { fadeIn: 1, fadeOut: 1.2 }`; `hide: ['id']` / `only: ['id']` choose who is drawn; `note` is a reminder.
- `top: (g, cam, sc, t, ctx, u) => caption(g, cam.W, cam.H, u, 'The park', { sub: 'morning' })` draws lettering on the
  picture (`mainTitle(g, W, H, u, { word: 'MY FILM', sub: 'A SHORT STORY' })` for a title card); `over` draws under it.
- Shots of one sequence share the performance, so continuity holds across every cut. Keep shots 1.5–6 s.

**sound(T)** → `[{ t0, t1, amb: 'birds', gain: 0.4 }, { t0, t1, music: 'theme', gain: 0.6, fadeIn, fadeOut }, { t, sfx: 'step', gain: 0.3, pitch?, pan? }]`
- Sounds that come with the kit — effects: bell block boom chime clash click cloth crackle deepWhoosh draw gong heart hoof
  hum impact kick land paper pop punch reverse riser rumble shimmer slash snowstep step stepstone subdrop swoosh tick
  whoosh; ambience: army birds city crowd fire night rain river sea shore wind.
- Music comes from cues.json: `[{ "name": "theme", "op": "music.generate", "style": "motivational", "mood": "hopeful", "seconds": 34, "seed": 7 }]`
  (styles: cinematic_epic action_battle emotional_piano sad_violin suspense horror adventure anime_rock pop lofi edm
  synthwave hiphop jazz comedy ambient chiptune motivational lullaby bollywood_romantic bollywood_dance indian_classical
  devotional folk_garba japanese). More beds: `{ "name": "storm", "dir": "amb", "op": "sfx.ambience", "type": "rain", "seconds": 60 }`.
- Voices duck the music by themselves; the mix is levelled to −14 LUFS.

## script.json

```json
{ "cast":  { "Mira": { "en": ["af_heart", { "pitch": 0.6 }], "hi": ["hf_alpha", {}] } },
  "pace":  { "en": 0.92 },
  "lines": [ { "id": "M1", "who": "Mira", "en": "You came!", "hi": "Tum aa gaye!", "hi_say": "तुम आ गए!", "tone": "happy", "em_en": ["came"] } ] }
```
`cast`: each speaker's voice per language (voices: af_heart af_nicole am_michael am_fenrir hf_alpha hf_beta hm_omega
hm_psi …; blends like `"am_michael*0.6+am_fenrir*0.4"`) and own tone. `tone`: neutral warm happy excited sad angry
stern calm afraid tired question whispery commanding playful, or `{ like, pitch, rate, energy, pauses, ending }`.
`<lang>` is the subtitle, `<lang>_say` what is spoken if different, `em_<lang>` words to lean on. With several
languages in project.json every line is spoken in each; the picture is timed to fit them all and lips follow each.

## Places and things

`set: () => place({ time: 'dawn' | 'day' | 'dusk' | 'night' })` — studio({ color, floor }) meadow forest desert snowfield
beach mountain street park room({ style: 'home' | 'office' | 'class' }) rooftop lakeside farm road space; and the story
sets town village courtyard hall terrace bridge steps. Put things into a place:

```js
set: () => { const sc = street({ time: 'night' }); put(sc, 'car.blue', [6, 2], { yaw: 90 }); put(sc, 'bench', [-3, -5]); return sc; }
```
Props (`code.sets` lists all 126): crate barrel table chair stool bench bed shelf desk sofa armchair screen plant door
lamp post lantern signpost well cart stall tent campfire haystack log pile stump boat car bus hydrant trash can mailbox
traffic light bus stop statue fountain flag tower hut barn building bush tree pine bare tree rock — most with variants
(`'car.blue'`, `'tree.blossom'`, `'building.lit'`, `'stall.green'`). `putFence(sc, [x, z], [x, z])`, `putWall(…)`.
Build your own from `box(at, w, h, d, yaw, { side, top }, { front: DET.planks(0.2) })` and `new Mesh(faces)`.

Effects for a shot or sequence, `fx: (ctx) => [rain(box), snow(box), petals(box), embers(box), smoke(box)]`,
`fire({ p, w, h })` as a thing in the place.

## People

380 ready people in 28 groups: townsman townswoman farmer farmwoman merchant nobleman noblewoman monk elder elderwoman
boy girl teen youngwoman soldier_crimson soldier_azure soldier_jade soldier_onyx captain_* bandit sailor swordsman
swordswoman king queen — ids like `townswoman_12`. `code.people {group}` lists a group, `code.person {id}` shows one.

## Working well

- Write one sequence per place and stretch of story; a film is a list of them. Put shared helpers in their own file.
- After every change look at `code.frame` / `code.sheet` of the shots you touched before `code.build`.
- Keep both feet of a character inside the picture or cut them off on purpose (frame from the chest up).
- People are drawn whole, nearest last. For a hand on a far shoulder give the nearer one `actor: { id: { bias: 0.5 } }` in that shot.
- Nothing in `.vs` needs care: delete it and everything is made again from the code.
