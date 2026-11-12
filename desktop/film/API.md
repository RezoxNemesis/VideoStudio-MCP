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
project.json   { name, width, height, fps, langs: ["en","hi"], crf, preset, off: [shot ids left out], look }
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

**shots(T)** → `[{ id, t0, t1, cam, note?, dof?, post?, hide?, only?, actor?, over?, top?, fx?, settle?, hold?, warp?, look? }]`
- `cam: (t, ctx, tInShot) => ({ pos: [x, y, z], target: [x, y, z], fov: 35, roll?, shake?: { amp, freq } })`.
  `at(ctx, 'mira', 'head' | 'chest' | 'hips' | 'feet', [dx, dy, dz])` is a point of a body right now.
  `move(camA, camB, t, t0, t1)` eases from one set-up to another; `breathe(cam, t, 0.005)` adds a living drift.
- `dof: (t, ctx) => ({ near, far, blur: 6 })` soft focus outside near…far metres from the lens.
- `post: { fadeIn: 1, fadeOut: 1.2 }`; `hide: ['id']` / `only: ['id']` choose who is drawn; `note` is a reminder.
- `top: (g, cam, sc, t, ctx, u) => caption(g, cam.W, cam.H, u, 'The park', { sub: 'morning' })` draws lettering on the
  picture (`mainTitle(g, W, H, u, { word: 'MY FILM', sub: 'A SHORT STORY' })` for a title card); `over` draws under it.
- `hold: 2` holds every drawing for two frames ("on twos", the rhythm of drawn animation; cameras, cloth and effects
  still move on every frame); a function `(t, ctx) => 1 | 2 | 3` puts fast action on ones. `seq.hold` sets it for all shots.
- `warp: ramps(dur, [{ at, slow: 0.15, len: 0.5 }])` bends the shot's clock: slow motion and hit-stops at the moments that
  matter; the time is won back afterwards, so the shot still ends when it would have.
- `look` draws this shot (or `seq.look`, the whole sequence) in another kind of animation — see **Looks**.
- Shots of one sequence share the performance, so continuity holds across every cut. Keep shots 1.5–6 s.

## Real bodies

`motion: (T) => ({ actors, steps, bodies: true })` keeps each performer's whole body: 163 bones, a skinned anatomical mesh,
five-fingered hands (`hands: (t) => ({ L: 'relax' | 'open' | 'fist' | 'grip' | 'point' | 'claw', R: … })` on an actor),
a real head whose jaw opens on the lines, drawn anime eyes and brows that act (`emote`), hair in clumps that swing
(`design.hair3: 'short' | 'neat' | 'spiky' | 'shaggy' | 'bob' | 'long' | 'tied' | 'topknot' | 'buzz' | 'bald'`), cloth
layers, cast shadows. Swords, spears, staffs and shields are real objects held by the fingers. Figures smaller than about
40 px are drawn the old, simpler way. `actor: { id: { mesh: false } }` draws one the old way on purpose.

## Action

```js
import { combat, fightEvents, ramps } from '../vs.js';
const FX = combat([{ t: 3.2, kind: 'clash', by: 'orez', on: 'a1' }, { t: 4.1, kind: 'cut', by: 'orez', on: 'a1', power: 1.4 },
                   { t: 5.0, kind: 'kill', by: 'orez', on: 'a2' }], { blood: true });
// fx: FX.fx, and in a shot: over: FX.over, top: FX.top, cam: (t, ctx) => ({ …, shake: FX.shake(t) })
```
Events: `clash` (sparks, a star of light, ringing steel), `cut` / `stab` (blade arc, cut line, blood that sprays, drips and
pools, staining the ground), `kill`, `kick`, `thud`, `whoosh`. `fightEvents(beats, ['orez', 'a1'])` turns the beats of a
`stage.fight` into events. `ramps` (above) gives the slow motion. The sounds come with the events.

## Looks

The same film drawn as another kind of animation: `"look": "paper"` in project.json for the whole film, or
`look: 'sketch'` on a sequence or a shot (a flashback in pencil, a dream in paper). Or `{ "style": "pixel", "size": 4 }`.

| look | |
|---|---|
| `anime` (`2d`) | the default: cel tones, ink lines |
| `multiplane` (`2.5d`) | anime drawing in layered depth: haze, a soft far background |
| `soft3d` (`3d`) | a 3D render: smooth light, no lines, soft bloom |
| `cel3d` (`3.5d`) | cel-shaded 3D: three tones, rim light, an outer line, fine inner lines |
| `immersive` (`4d`) | cel-shaded 3D with light in the air: sun shafts, bloom, haze, floating motes, a breathing camera |
| `paper` | paper cut: flat paper colours with pale cut edges, every layer casting a shadow, paper grain, on threes |
| `puppet` | cut-out puppet theatre: paper pieces that jolt from drawing to drawing, on threes |
| `flat` | flat motion-graphics design: clean colour, thin lines, no grain |
| `pixel` | pixel art: a small canvas, a short palette, ordered dither, on twos |
| `sketch` | pencil: graphite lines and hatching on paper, a faint colour wash |
| `manga` | black and white: ink and screen tone |

## Library models

`model(sc, 'medieval/building_tower_A', [x, z], { yaw, scale, tint, recolor: { '#hex': '#hex' } })` puts one of 854 ready
3D models (buildings, walls, furniture, dungeon, city, food, weapons …) into a place, drawn in the film's look. `models('tower')`
finds them.

**sound(T)** → `[{ t0, t1, amb: 'birds', gain: 0.4 }, { t0, t1, music: 'theme', gain: 0.6, fadeIn, fadeOut }, { t, sfx: 'step', gain: 0.3, pitch?, pan? }]`
- Sounds that come with the kit — effects: bell block boom chime clash click cloth crackle deepWhoosh draw gong heart hoof
  hum impact kick land paper pop punch reverse riser rumble shimmer slash snowstep step stepstone subdrop swoosh tick
  whoosh; ambience: army birds city crowd fire night rain river sea shore wind.
- Music comes from cues.json: `[{ "name": "theme", "op": "music.generate", "style": "motivational", "mood": "hopeful", "seconds": 34, "seed": 7 }]`
  (styles: cinematic_epic action_battle trailer emotional_piano sad_violin suspense horror adventure anime_rock pop lofi
  edm house future_bass drum_and_bass trap drill phonk reggaeton afrobeats synthwave hiphop jazz comedy ambient chiptune
  motivational lullaby bollywood_romantic bollywood_dance punjabi_pop sufi indian_classical devotional folk_garba japanese).
  The instruments include VideoStudio's own Studio bank: 808s, supersaws, plucks, FM keys, choirs, braams, risers, sitar,
  tanpura, bansuri, harmonium, santoor, shehnai, sarangi, and kits for trap, dance, lo-fi, film percussion and tabla. More beds: `{ "name": "storm", "dir": "amb", "op": "sfx.ambience", "type": "rain", "seconds": 60 }`.
- Voices duck the music by themselves; the mix is levelled to −14 LUFS.

## script.json

```json
{ "cast":  { "Mira": { "en": ["af_heart", { "pitch": 0.6 }], "hi": ["hf_alpha", {}] } },
  "pace":  { "en": 0.92 },
  "lines": [ { "id": "M1", "who": "Mira", "en": "You came!", "hi": "Tum aa gaye!", "hi_say": "तुम आ गए!", "tone": "happy", "em_en": ["came"] } ] }
```
`cast`: each speaker's voice per language and own tone. Voices: 32 trained ones (English af_heart af_bella af_nicole af_sky
af_nova am_michael am_fenrir am_onyx am_puck bf_emma bm_george bm_fable bm_lewis …; Hindi hf_alpha hf_beta hm_omega hm_psi;
Spanish ef_dora em_alex em_santa; French ff_siwis; Italian if_sara im_nicola; Portuguese pf_dora pm_alex pm_santa), the
character voices (pre_young_hero pre_warrior pre_warlord pre_master pre_mother pre_little_boy pre_little_girl pre_queen
pre_trickster pre_announcer pre_documentary pre_whisperer pre_storyteller …, Hindi pre_hi_nayak pre_hi_guru pre_hi_maa
pre_hi_bachcha pre_hi_khalnayak pre_hi_sutradhar) and blends like `"am_michael*0.6+am_fenrir*0.4"`. Languages for `langs`:
en hi es fr it pt de ru nl pl sv id ro el mr — any voice speaks any of them (its own accent); `voice.languages` lists them.
Lines are read sentence by sentence the way a person reads: "!" "…" "?!" shape the delivery. `tone`: neutral warm happy excited sad angry
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

## The 2D library

587 pictures drawn by the app (all original, free to use): 48 backgrounds (16 places × day, dusk, night) each in five
painted layers, 36 cut-out characters (24 at six and a half heads, 12 chibi) with rigs of parts, 142 effects (impacts,
slashes, focus lines, fire, smoke, explosions, energy, magic circles, splashes, emotion marks, weather), 138 props, 44
face parts, 32 hands, 40 balloons / titles / lower thirds, 29 textures and screen tones, 38 pixel sprites, 40 stick-figure
poses. `library.list {cat, q}` finds them (each with the code that uses it), `library.get {id}` shows one.

```js
// a 2.5D place: its layers stand at their depths, so a truck or a push-in moves them past each other (multiplane)
set: () => parallax('bg_village_dusk'),
// in a shot (sc = the set): a puppet that stands on the near ground, walks, blinks and speaks its lines
dyn: [cutoutActor('char_mira', { ...sc.stand(0.42, 0.9, 0.62), pose: (t) => walkPose(t), who: 'Mira', cues })],
// stickers and effects, in over: or top:
over: layered(sticker('ui_balloon_round', { x: 0.7, y: 0.25, h: 0.3 }), pop('fx_impact_comic_white', 2.1, { x: 0.6, y: 0.4, h: 0.4 })),
```
- `sticker(id, { x, y | at: [x, y, z], size, w | h, rot, flip, blend, alpha, t0, t1, fade, anim })`, `pop(id, t0, { dur, spin, grow })`,
  `drawAsset(g, id, x, y, o)` for your own drawing code.
- Cut-out poses: `CUTOUT_POSES` (stand wave point cheer think shrug hips surprised crossed sad angry run), `walkPose(t)`,
  `idlePose(t)`, `posesOver([[t, pose], …])` (pose to pose, eased), `mixPose(a, b, u)`. A pose is `{ rot: { part: degrees },
  sy, z, eyes, mouth, bob, lean, squash }`; parts: torso head hair_back hair_front upperarm_R/L forearm_R/L hand_R/L thigh_R/L
  shin_R/L foot_R/L skirt. The right arm (_R) hangs on the picture's left and opens with positive degrees.
- In Animate: Layers → 2D library (or `anim.library`) brings any of them into a drawing scene; characters come in as puppets
  that the Pose tool, `anim.pose`, `anim.ik` and `anim.motion` bend.

## Fixes painted over shots

Animate → **Fix a film shot** plays a shot frame by frame: draw over it and erase in it. Each fix holds on to a part of
a body (the one you draw on, or the one picked: head face eyes jaw neck chest belly hips, and per side shoulder upperarm
elbow forearm hand fingers thigh knee shin foot), so it moves, turns and sizes with that part on every frame of its span.
Erasing shows what is behind the person (the film draws the frame again without them, under the erased area) or fills the
hole from around it. Fixes live in `paint/<shot>.json` (part of the film), switch on and off any time, and only that
shot is drawn again. `paint.parts {shot, t}` says where every part is; `paint.add`, `paint.set`, `paint.on`,
`paint.remove`, `paint.frame` do the rest. Sensei's findings open there (Fix by painting) at their moment and part.

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
