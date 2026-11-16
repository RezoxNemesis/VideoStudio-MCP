# Code Studio — films written as code

A film is a folder of small JavaScript files. The code says who is in a scene, what they do and say, where the cameras
are; VideoStudio speaks the lines, solves the body motion (motion capture + IK), draws every shot and joins the film.
Each shot is kept as its own video: when the code changes, only the shots whose picture changed are drawn again and
the film is joined again without re-encoding — a fix to one shot of a long film takes seconds.

## The loop

1. `code.new {name}` (or `code.open {film}`) — start from the starter film and read it: `code.read {file:"seq/s01.js"}`.
2. Change the code: `code.write {file, text}` for a whole file, `code.patch {file, find, replace}` for a part.
   The answer says at once whether the code loads (and the mistake if not) and how many shots must be drawn.
3. Look before drawing: `code.frame {shot, t}` draws one frame now (`scale: 2, crop: [left, top, width, height]`
   enlarges a part of it); `code.sheet {shot}` draws six moments of a shot; `code.where {shot, t}` says where bodies
   and the camera are, in numbers.
4. `code.build` — speaks new lines, solves changed performances, makes new music, draws only the changed shots (several
   at once), mixes the sound, joins the film. `code.build {draft:true}` is a quick half-size version.
   Every build comes back with `sensei`: the judge's findings on the bodies, cameras and story — read them, fix the
   MUST findings, build again (see "Working well" at the end: the order of work and what goes wrong).
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
  { t: 6, clip: '143_25', in: 0.4, out: 5.2, at: { yaw: -90 }, blend: 0.4 },   // a motion-capture clip (stage.motions finds them)
  { t: 9, pose: 'kneel' | 'sit' (on the GROUND) | 'clip:113_02@1.55' (the pose of a clip at a moment: 'clip:143_18@6.0' is seated on a chair), blend: 0.8 },
  { t: 12, fall: { push: 0.6 } },
] }
{ op: 'stage.overlay', actor: 'tom', add: [
  { t0: 0, t1: 8, fade: 0.4, look: 'mira', head: 0.8 },                         // head and eyes follow someone
  { t0: 2, t1: 5, fade: 0.4, lean: { pitch: 8 } },
  { t0: 3, t1: 6, fade: 0.4, keys: [{ on: 'hand.R', keys: [[0, [0.2, 1.2, 0.3]], [1, [0.3, 1.5, 0.2], 'io']] }] }, // a hand to a point (IK)
] }
{ op: 'stage.fight', … }                                                        // sword and fist fights: see {"op":"help"} stage.fight
```
A sword worn at the hip (`props: [{ kind: 'sword', hold: 'hip' }]`, or the swordsman's kit) hangs outside whatever the
body wears: its scabbard through the sash at the left hip, back, down and a little out, the hilt before the belly.
It is drawn with the sword overlay: `{ t0, t1, fade: 0.5, sword: [[0, 'sheath'], [1.5, 'sheath'], [1.7, 'unsheath'],
[2.05, 'loR']] }` — the right hand comes across to the hilt and the left takes the scabbard (`left: false` keeps it
off), the blade slides out along the scabbard's own line, and from `unsheath` to whatever position follows it leaves
round the front (out to the left, across in a cut) — never through the body. The sword stays in its scabbard until it
has slid out, so the overlay can begin in view. A sequence that ends in `sheath` puts it away the same way backwards.
`{ t0, t1, fade: 0.5, scabbard: true }` rests the left hand on the scabbard (how a swordsman walks: the arm does not
swing through the sword). The ground of a film is flat (y = 0 everywhere), and
a performance is solved on a flat floor; `motion: () => ({ env: 'village', … })` asks for one of the Stage's own places.
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
- `post: { fadeIn: 1, fadeOut: 1.2, fadeColor: '#fff8ea' }`; `post: { dissolve: 1.2 }` — the shot opens on the last picture of the shot
  before it (also across sequences) and lets it go, eased: **use it for every change of time or place** (day → night, into a
  memory and back); a fade through a colour is `fadeOut` + `fadeOutColor` on one shot and `fadeIn` + `fadeColor` on the next —
  never a one-frame flash. `hide: ['id']` / `only: ['id']` choose who is drawn; `note` is a reminder.
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

**Hands.** Fingers close towards the palm and lie side by side; the thumb rests beside the index finger, and in a fist
or a grip it is laid over the fingers. A hand the film leaves out is held by the engine (closed round a sword, spear or
staff it carries, relaxed otherwise), so `hands: () => ({ L: 'open' })` poses the left hand only. Besides a name, a hand
can be a blend `['open', 'fist', 0.5]` or its own shape: `{ curl: [[thumb ×3], [index ×3], [middle ×3], [ring ×3],
[little ×3]], spread, thumbIn, thumbOut }` — `curl` in degrees towards the palm, joint by joint (0 = a straight finger;
the thumb's is added to how it rests); `spread` 0 = side by side, 1 = spread wide, below 0 = pressed together;
`thumbOut` 0 = beside the index finger, 1 = held out; `thumbIn` 0 = by the flat of the hand, 1 = in front of the palm.
`import { HANDS } from '../vs.js'` has the named ones to start from: `{ ...HANDS.open, spread: 1 }`.
Seen from the back, a closed hand shows its knuckles (a short arc over each, as far as it is bent — a flat hand has
none); neighbouring fingers have a line between them, and a finger or the thumb is outlined where it lies over the
hand, never where it grows out of it. A hand is a small form: its outline is drawn finer than the body's unless it
fills the picture.

**Costume on a real body.** `design.body` colours the body's own garments (`tunic`, `coat`, `trousers`, `boots`,
`wraps` …), `body.sleeves` gives the top's sleeves a colour of their own (a dark vest over a white robe), and
`body.bands: { tunic: [[from, to, colour]] }` lays stripes across a garment by height (fractions of the body's height:
a collar, a sash). `design.robe: { color, lining, len: 0.43, open: 0.2, flare: 0.5, trim, trimW }` hangs a robe's
skirts from the waist over the trousers — open in front, the lining showing inside, a trim along the hem; they swing
and stream behind a moving figure, and on a real body they hang outside the body as it is dressed (its hips and seat
never come through them). Hair tied in a tail takes a cord: `hair3: { cut: 'tied', tail: { len, w, at: 'high',
tie: true } }` with `hair: { tie: '#a3222a' }`; finer hair is more, narrower clumps (`n: 150, w: 0.021`).

**Hair designed lock by lock.** A cut can be laid out the way a character designer draws one. `hair3.locks: [{ from: [round,
up], to: [round, up], via?, w: 0.034, lift: 0.012 | [root, middle, tip], hang: 0.16 }]` lays each lock over the real head
from one place on it to another — degrees round from straight ahead (positive towards the head's own left) and up from
level; the hairline is about 26° up in front, the eyes about −5°, the temples ±45° round — lifted off the skin, and falling
freely for `hang` metres past its end (a curtain of fringe from the parting to the temple, a long lock in front of the
ear). `flow: 'tie'` draws the hair of the whole scalp back to where the tail is tied, each clump beginning from a point
(`lift`, `vol` for fullness). The tail: `tail: { len, w, at: [180, 46] (a place on the head) | 'high' | 'low' | 'top', up:
0.45 (it rises before it falls), locks: 8 (a sheaf of locks with tips of their own), tie: { ends: 2, len: 0.24 } (a cord
with two hanging ends) }`. A tail hangs as hair does: off the tie in a curve, round the back of the head and down the
back (never through them), swaying with the walk. Painted with a brush: `hair: { strands: 0…1 }` lays darker strands and a lighter streak along
every lock, `hair.sheen: 0…1` makes the band of light across the hair zigzag from lock to lock, `hair.shine` its colour.

**Clothes cut by the engine (`design.wear`).** The Stage's own garments are the body's skin let out a little: they follow
every muscle and have no collar, placket, pocket, belt or shoe. With `wear` the engine cuts clothes for the body
(engine/wardrobe.js): smooth shells measured from the body's rest shape, with ease, hanging from the chest and the
thigh; every piece a drawing of clothes is made of is a thing of its own with its drawn line. Give the Stage a BARE
body for it (`garments: []` on the motion actor, no `outfit`): the Stage removes the skin under its own garments, and
the engine's clothes need the body under them. `wear: { shirt: { color, shade?, sleeve: 'short' | 'long', ease?,
pocket?, collar: { open?, point? } }, tie: { color, stripe?, loose? }, trousers: { color, shade?, fit: 'slim' |
'straight' | 'loose' }, belt: { color, buckle? }, shoes: { kind: 'derby', color, sole? } }` — any piece may be left
out. What it makes: trousers (seat + two legs that hang round a straight middle line, hem at the ankle, a crease down
each leg, fly and side pockets as ink lines), belt and buckle, shirt (tucked in, a little cloth over the belt), sleeves
whose heads are balls round the shoulder joints, a hem band on each sleeve, a collar on a band with two pointed leaves
(open: the top button undone, a V of skin), placket, buttons, a patch pocket, a tie (knot and striped blade), low
shoes on soles. Skin under the clothes is not drawn, so nothing comes through them. The same design with other
colours, fits and pieces is another outfit: the wardrobe of the library is made of these pieces. Body numbers for
cutting: `node devtests/bodyinfo.mjs <film> shot@t actor` (bones, parts, landmarks). (Rules: devtests/wardrobe.mjs)

**A shirt.** `design.body.collar: { style: 'shirt', depth: 0.1, w: 0.03, buttons: 0.085 (metres between them), button:
'#eef0f4', pocket: true, tie: { color, stripe, len: 0.38, w: 0.03, loose: 0.035 (the knot pulled down: a loosened tie) } }`
opens the neck on the wearer's own skin (or `inner`), lays a collar's two leaves along the opening, stitches a placket
with buttons down to the hem and a pocket on the wearer's left breast, and hangs a striped tie. A tucked shirt: the
tunic garment `below_hip: -0.1`, the trousers `rise: 0.19, offset: 0.013`, a belt as `body.bands: { trousers: [[0.605,
0.628, '#0e0d10']] }`. Rolled sleeves: the tunic's `sleeve: 'short'` (it ends at the elbow). A child's dress collar is the
same cut, small: `depth: 0.045, buttons: 0.05`.

**No sideburns.** `hair: { sideburns: false }` leaves the scalp's hair out below the line of the eyes in front of the ears
(a little girl, a woman with her hair drawn back: the body's short scalp otherwise shows as dark marks by the jaw).

**A ribbon bow.** `hair3.tail.tie: { ends: 2, len: 0.07, bow: 0.15 }` ties the tail with a bow that wide (metres): a loop
of ribbon to each side of the knot, in the colour `hair.tie`; it turns and tilts with the head.

**A top cut like a robe.** `design.body.collar: { style: 'cross' | 'v', depth: 0.13, w: 0.026, inner: '#ece6da', trim:
'#b08a3c' }` cuts the top's neck: lapels that cross at the chest (the wearer's left over the right, down to the sash) or
a neck cut to a point, the under-robe showing in the opening, a band along the lapels and round the back of the neck.
With `body.sleeves` the sleeves are set in at one clean seam from shoulder to armpit.

**Faces.** A real head's face is drawn with form: the shadow side of the nose (it follows the light) and the shadow
under its tip, nostrils, the line of a turned head's nose, the lips and the line where they meet, the shadow under the
lower lip, the crease of the lids, and the hair's own shadow on the brow. On the design: `face: { lips: 0…1 }` how full
the lips are drawn (default 0.45; about 0.9 for a woman's painted mouth), `face.lipColor`, `face.nostrils: false`,
`face.detail: false` for the plain face of earlier versions. `face.hairShadow: false` keeps the hair's cast shadow off the face
(a child's face under a fringe, a gentle face in low light). `face.nose: 0…1` lays the nose's shadows in lightly
(about 0.35 for a fine nose under the soft brush).

**Beards, scars, the lines of age.** They are painted on the skin of a real head, so they turn with it and move with
the jaw: `face.beard: { style: 'full' | 'goatee' | 'chin' | 'mustache', cheek: 0…1 (how high it grows on the cheek),
mustache: false | { heavy: true (it hangs over the upper lip, its ends down past the corners of the mouth), density },
sideburn: false, soul: false, rough: 0…1 (how ragged its edge is drawn), strands: 0…1 (darker strokes through it, the
way it grows), density: 0…1 (below 1: stubble), color }` — in the hair's colour unless it has its own (a white beard
under dark hair); the body model's own beard (a shell of hair round the jaw) is then left out. `face.beard: false` leaves the body
model's own beard out altogether (a woman, a child: a stray piece of it shows as a dark mark on the chin). `face.scars: [{ from:
[x, y], to: [x, y], w: 0.0012 }]` draws fine scars between places on the face — metres from its middle (positive
towards the head's own left) and up from the line of the eyes: the brow lies near 0.03, the nose's tip near −0.045,
the mouth near −0.08. `face.lines: 0…1` draws the lines of age (the folds from the nose past the mouth; from 0.5 the
lines under the eyes; from 0.8 across the brow), `face.frown: true` the furrow between the brows.

**Shoulder plates.** `design.armor.pauldron: '#585c68'` puts plates on the shoulders of a real body: a cap over each
shoulder and lames lapping down the upper arm, going with the arm when it is lifted (`design.body.pauldrons: false |
{ size: 1.15, lames: 3 }`). With `body.plates: { cuirass: {…}, coat: { h, w, lace } }` the skirt under a cuirass is a
skirt of plates.

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

The painted look can be given a brush: `"look": { "style": "anime", "paint": { "soft": 0.85 } }` turns light to shadow
gradually on skin and cloth (rounded, painted forms; hair and metal stay crisp), gives the shadows forms throw on each
other and hair throws on a face a soft edge, and keeps the tucks of a form (under a jaw) from going to blots.

**Clothes that are cut (engine/tailor.js).** The body's own trousers lie on the skin like leggings. `design.bottom.fit:
'slim' | 'straight' | 'loose'` re-cuts the legs in the body's rest shape: from the middle of the thigh the cloth falls
in a nearly straight line to a hem far wider than the ankle; seat, hip and waistband keep their cut, and the two legs
never run into each other. Without `fit` the garment is the body's own. Rules: `devtests/tailor.mjs`. **Wavy hair:**
`hair3: { cut: …, wave: 0…1, flick: 0…1 }` swings each lock from side to side and turns its tip out (a cut without
them is exactly as before). It does not yet give the soft drawn locks of an illustration: hair still needs a smooth
mass with designed locks, strand lines and a highlight band.

**Holding, giving, taking, setting down (kit/hold.js).** A thing in a hand lies IN the hand — across the palm, behind
the knuckles, the fingers closed round it, the thumb over them — and it is ONE thing through a sequence: it lies, is
taken, carried, given from hand to hand, set down; at no moment is it nowhere, and it never jumps.
`const rose = prop({ id: 'rose', r: 0.003, at: 0.12, make: (p, dir) => …, life: [{ t: 0, on: [x, y, z], dir },
{ t: 4.2, by: 'beti', hand: 'R' }, { t: 9, by: 'papa', hand: 'R', over: 0.5 }, { t: 14, on: [x, y, z] }] })`;
the sequence draws it with `dyn: (ts, ctx) => [rose.item(ts, ctx)]`, and each actor who touches it takes
`hands: (t) => rose.hands('papa')(t)` — the hand opens as it reaches, closes as it takes, and in a hand-off the
taker has it before the giver lets go. `rose.where(ts, ctx)` says who has it and where (cameras, reaching hands);
`wristFor(pose, 'R', point)` is where the wrist must be for the grip to close on a point; `gripFrame(pose, hand)` is the
palm itself. A held state can lean the thing (`tilt`), carry it head down (`flip`) or pinch it (`style: 'pinch'`).
Never draw a held thing as a free object near the wrist with the hand left open. Rules: `devtests/hold.mjs`.

**The engine renews itself without an install.** Every release carries this engine as one small file
(`film-kit.json.gz`, about 400 KB: engine/, kit/, runner/, sensei/, templates/, this handbook). `{"op":"kit.update"}`
fetches the newest into the person's own folder, checks it against the release's checksum and draws with it from then
on — no installer, no password; `{"op":"kit.info"}` says which engine draws (`built-in` or `fetched`). The canvas
library, fonts, sounds and the 2D library stay with the installation. Only when an engine needs something new in the app
itself (a new command, a change to the Stage's solver) must the app be installed once: raise `minApp` in `film/KIT.json`
to that release, and `kit.update` will say so in a sentence. (electron/film-kit.cjs; tests/filmkit.test.ts)

**Rolls, dives and falls: nothing under the ground.** A capture is fitted to our body by its feet; in a roll the hips,
a shoulder and the hands can sink into the floor. `fit: (pose) => grounded(pose)` on the actor lifts the whole body onto
it, eased in (kit/combat.js; rules in `devtests/ground.mjs`). Moves `dive-roll` and `backflip` in the code collection
hold the clips, their moments, the yaw each needs to travel along the actor's heading, and where a thing in the way goes.

**The finish (engine/finish.js) — what anime calls photography.** A painted figure is not pasted on its background:
where it is laid down, the background's light bleeds a little over its edge (`wrap`, light only, just short of being
noticed) and a soft gradient goes over it, lighter and warmer towards the scene's light, deeper and cooler away from it
(`para`); then the whole picture gets a soft glow round what is light (`post: ['diffuse']`, a soft knee: blacks stay
black). The `multiplane` and `anime` looks ask for it; any look can: `look: { finish: { wrap: 0.55, para: 0.2 },
post: ['bloom', 'diffuse'], diffuse: 0.3 }`; one actor can differ (`finish: false` on its draw options). The head's skin
is lit as a ball (`design.body.round`, default 0.55; `false`: the mesh's own facets), the trunk as a cylinder
(`design.body.proxy`): light and shadow meet in one clean curve. Rules: `devtests/finish.mjs`, `devtests/brush.mjs`.

**Check the finished film for flashes:** `node devtests/flash.mjs <video>` reads its brightness frame by frame and names
every one- or two-frame flash (never right) and every cut where the whole picture jumps in brightness (day ↔ night, into
white: right only when meant — else `post.dissolve` or a fade). Run it on every draft before it is shown.

**Pictures on surfaces** (a photograph in a hand, a poster, a face in the sky): keep the file in the film's `assets/`,
`const PHOTO = await picture(new URL('../assets/photo.png', import.meta.url))` at the top of the sequence file, then in a
face's detail `onFace(g, F, PHOTO, u0, v0, u1, v1)` (cropped to fill, lit like the face) or in a shot's `top`:
`g.drawImage(PHOTO, x, y, w, h)` (with `g.globalCompositeOperation = 'screen'` for something made of light).

**What a drawn figure needs (learnt on the Papa film, 16 Nov 2026 — it came back for "hard polygonal patches", ragged
edges, a night too dark to read and people "extremely static"):**
- Every look that draws anime **paints** (`paint.mode: 'paint'`): one designed shadow shape per form with a soft brushed
  edge. Plain two-tone cel (`classic`) follows the polygons of the body and reads as accidental patches — do not use it for
  real bodies. Draw one close-up and look at the shirt and the cheek before building anything.
- **Rim light**: every painted look puts a thin rim of light on the side turned from the lens; it takes the colour of the
  scene's `rim` (`new Scene({ rim: '#ffc890' })`: a warm lamp at night) and is what lifts black hair and dark trousers off a
  dark background. Never switch it off to cure a hair fault.
- **The trunk is lit as one round form** (proxy normals: a cylinder round the spine, as the head is lit as a ball): a
  shirt gets one clean shadow shape that moves slowly, not a patch on every fold. `design.body.proxy: 0…1` (0.5; false: off).
- **Hems and hairlines** are closed by themselves (`paint.hem`): a short sleeve, a shirt's lower edge and the cap of hair no
  longer end in the teeth of their triangles.
- **Nobody is a statue**: at rest the shoulders ride 1–3 cm on the breath, about one breath in five is a sigh, the head
  drifts, tilts and turns by a few degrees. It is the floor, not the acting: a held pose still needs eyes that move
  (`look` keys), a blink on a change of gaze, and a small change of pose every few seconds.

| look | |
|---|---|
| `anime` (`2d`) | the default: cel tones, ink lines |
| `multiplane` (`2.5d`) | painted anime drawing (soft brush `soft: 0.5`, rim light) in layered depth: haze, a soft far background |
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
With the studio voice a line keeps its tone's pace: it is spoken a sentence at a time, the silences between the
sentences follow their marks and `pauses`, and the speech is held to about three words a second times `rate`
(0.86 is a slow, weighty line of about 2.6). Listen to every line after it is spoken. When one lost or changed a
word, ask for another take of it: `"take_hi": 1` (then 2, 3 …) acts the line afresh in that language only, `"take"`
in every language.
The studio voice spoils the first third of a second of whatever it is given (a first "Make" came out "Take", a first
"He" was lost). So every sentence is spoken after a short sentence of its own ("Listen to me." in English, "मेरी बात
सुनो।" in Hindi) that takes the damage and is cut away in the pause after it; a take in which it runs into the line is
made again.
Nothing to write for it: the line in script.json is the line.

## Scenes in a few lines (the director)

`scene()` writes a whole sequence from who is there and what happens: the Stage performance (standing alive, walks,
captured gestures, heads turning to whoever speaks, talking hands), the faces, the sound, and the coverage a film editor
wants — an establishing wide, shot and reverse shot over the shoulders on one side of the line, close-ups, a reaction
when someone listens, a closing two-shot, with focus and breathing cameras.

```js
import { scene, rooftop, backdrop } from '../vs.js';
export const S01 = scene({
  id: 's01',
  set: () => backdrop(rooftop({ time: 'dusk', town: 0 }), 'bg_city_dusk'),   // a painted city round the roof
  cast: [
    { id: 'aya', person: 'youngwoman_05', who: 'Aya', at: [-0.6, 0], mood: 'soft' },
    { id: 'ren', person: 'teen_04', who: 'Ren', at: [0.75, 0.35], stage: { outfit: 'hoodie' }, changes: { top: { color: '#3b6fa6', shade: '#264a72' } } },
  ],
  beats: [
    { wait: 3.6 },
    { say: 'A1', emote: ['sad', 0.6] },
    { say: 'R1', gap: 0.7, gesture: 'shrug' },
    { walk: 'ren', to: [3, -2], speed: 1.2 },
    { look: 'aya', at: [0, 9, -6] },
    { do: 'aya', gesture: 'wave' },
  ],
  amb: 'wind', music: 'dusk', title: { word: 'Rooftop Promise' },   // the title card says A FILM BY …
});
```
- Beats: `{ say, by?, to?, emote?, gesture?, hands? }` `{ wait }` `{ walk, to, gait?, speed?, face? }` `{ do, gesture, travel? }`
  `{ look, at: id | [x, y, z] }` `{ emote: { id: emotion } }` `{ sfx }`. Gestures by name (`GESTURES`: wave hello shrug think
  laugh cry bow lookaround angry phone drink sit comfort handshake pickup explain) or `['clipName', in, out]` from
  `stage.motions`; they play on the spot unless `travel: true`.
- `coverage: (T, D) => shots` to shoot it yourself: `D.ots(ctx, from, to)`, `D.cu(ctx, id, to)`, `D.wide(ctx)`, `D.two(ctx)` keep
  to one side of the line; `cu low high chase wide two ots facing steady push mixCams` work anywhere.
- `steps: (T) => [...]` adds Stage steps (a `stage.fight`, a hand on a prop); `fx`, `dyn`, `over`, `top`, `sounds: (T) => [...]`
  and `warm` (seconds of snow or fireflies already falling when a shot starts) as in `makeSeq`.
- `backdrop(set, 'bg_mountains_day')` rings any 3D place with the painted layers of a library background.
- A cast member's `stage` goes to the Stage body (`outfit`: tshirt_jeans shirt_trousers hoodie suit school_boy school_girl
  dress kurta robe gi armor shorts); `changes` edits the person's sheet (`headgear: { type: 'crown' }`, `hair3: 'tied'`).

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

## Painted land and sky

A place can be painted pixel by pixel, the way a background painter lays it in (engine/paintland.js). Each is an item
for a Scene: `sc.add('sky', skyPaint({ zenith, mid, horizon, away, sun: { dir, color, glow, r }, clouds: { cover, scale,
height, color, far, shade, wind, seed } }))` — a sky lit from where the sun stands (warm round the sun, cool away from
it), clouds with a lit and a shaded side that drift; `sc.add('far', ridgePaint({ layers: [{ dist, h, color, seed, peaks,
folds, sharp, peaky, snow, gap: { az, w, depth } }], air, sun }))` — ridges round the horizon, crest behind crest, each lost in
mist at its foot, paler the farther off, with a gap where a gorge cuts them (`peaky: 1` makes the highest crests peaks
instead of level table tops; far ranges read as land, not as cut paper, when they stay low in the sky's pale band and
near the colour of the air); `sc.add('ground', landPaint({ colors: [the
colours of what grows], earth, earthAmt, earthCover, patch, relief, cool, sun, wet, mist: { color, dist, amt } }))` — land
whose colour varies like grown ground, low light lying on its swells, dew shining towards a low sun, mist on far land;
`mistBand({ color, h, amt })` — mist along the horizon, in banks that drift; `grassPaint({ land, max, h: [a, b] })` — tufts
of grass near the lens, each at its own place, in the land's colour there, stirring in the wind. Everything holds to the
world (a spot of ground keeps its colour wherever the camera stands) and nothing boils under a still camera.

## Armies

A legion is an army of real figures (kit/legions.js): a few soldiers are performed like anybody else (a sequence's
motion, with bodies), and each becomes a figure that is laid down by the thousand — drawn once at the size and from the
side the lens sees a man, so ten thousand cost little more than the few drawings they are made from.

```js
import { legion, ranks, performer, strideOf, soldier } from '../vs.js';
const figs = [0, 1, 2].map((i) => performer({ bake: 's00', as: 'model', design: soldier(i), at: 1 + i, top: 3.1,
  bends: [{ bone: 'lowerarm01.R', about: 'side', deg: -88, props: ['spear'] }] }));          // the spear carried upright
for (const it of legion({ men: ranks({ at: [14, 0], yaw: -90, files: 56, ranks: 60, figs: 3 }), yaw: -90, figures: figs }))
  sc.add('world', it);
```
A sequence's armies can be made from a soldier of that same sequence: the performance is read when the first figure is
drawn, after the Stage has solved it. `ranks` sets men in ranks and files (`skip(rank, file)` leaves places empty for actors); `performer({ loop: strideOf('s00',
'marcher') })` makes a marching figure (one stride, twelve drawings) and `legion({ state: (t) => ({ advance: t * 1.3,
phase: t / stride }) })` marches them in step. Near men cover far ones, far ones pale into the air and end as marks along
the horizon; the ground they stand on is darkened away from the sun.

**Bending a performed body.** `bendPose(pose, [{ bone, about: 'side' | 'fwd' | 'up', deg, props?: ['spear'] }])` (in an
actor's `fit`, or a performer's `bends`) turns a bone about its own joint after the performance, with everything that
hangs on it: about `side` a positive turn swings what hangs there backwards (a knee bends; a forearm or a thigh lifts
forward with a negative one), about `fwd` out to the body's left. The real body is bent the same way when it is drawn.

**Armour of small plates.** `design.body.plates: { cuirass: { h: 0.042, w: 0.036, lace: '#a3222a' } }` paints a part as
rows of small plates laid like bricks: each plate's lower edge catches the light, its top lies in the shadow of the row
above, a cord's colour is stitched through every plate. `from` / `to` (metres up from the ground) keep the plates to a
part of a garment's height.

**A standing army is alive.** A figure made at one moment (`performer({ at })`) is ONE drawing: ten thousand men who
never move. Give the legion men who wait: `performer({ bake, as, design, wait: [t0, t1], fit: (pose) => plantSpear(pose) })`
— a stretch in which the performer stands and waits. The legion plays it forth and back by itself, every man at his own
moment and pace (near men on finer moments than far men), so heads turn, weight shifts and spear tips sway all through
the ranks, never in step. The performers are soldiers of the sequence who wait as soldiers do:
```js
// a short stand, then a captured wait: it keeps the feet where they stood (its shuffles become shifts of weight);
// the hands are kept on spear and shield, so the capture's arms do not wave them about
{ op: 'stage.do', actor: 'model', actions: [{ t: 0, idle: true }, { t: 0.3, idle: true, clip: '139_02', in: 1.0, blend: 0.6 }] },
{ op: 'stage.overlay', actor: 'model', add: [{ t0: 0, t1: dur, fade: 0, keys: [
  { on: 'hand.R', keys: [[0, [0.24, 1.0, 0.2]], [dur, [0.24, 1.0, 0.2]]] }, { on: 'hand.L', keys: [[0, [-0.3, 1.1, 0.16]], [dur, [-0.3, 1.1, 0.16]]] }] }] },
```
Waits that read as a soldier in the line: `139_02` (shifting weight), `137_41`, `40_10`, `40_11`; not `139_01`, `137_32`,
`137_28` (they crouch, stoop or fidget). Deal three performers among six figures, each figure its own stretch of the wait.
`plantSpear(pose, { lean: [right, forward] })` stands a spear on its butt through the hand that holds it. Which way a
figure faces is measured from his performance: a legion always faces its `yaw`, however the performer was turned.

## Riders

A horse is a rounded, painted body made from a skeleton that walks, trots and gallops with its hooves planted
(engine/horse.js, engine/horsemesh.js), and a performed person sits on it with a real body (kit/riders.js):

```js
import { rider } from '../vs.js';
const R = rider({ horse: { coat: '#2e282c', mane: '#0e0c0e', tack: true, cloth: '#5a1a1e' },
                  path: [[0, 1.7, 0, 'stand'], [8, 1.7, 0]] });        // or [[0, 3, -4, 'walk'], [8, 3, 6]]
makeSeq({ …,
  actors: () => [{ id: 'gar', design: …, ...R.actor }],               // he is carried to the saddle and seated
  dyn: (ts, ctx) => [R.item(ts, ctx)],                                // the horse's place in the picture (its shadow)
});
```

- `path`: way-points `[t, x, z, gait?, yaw?]` in sequence time (`stand`, `walk`, `trot`, `gallop`; the gait follows the
  speed when it is not named), or a function `(ts) → { pos: [x, z], yaw, dist, gait }`. A horse that stands faces the
  way it came in by, or `yaw` degrees when its way-point names one: `[[0, 17, 0, 'stand', -90], [60, 17, 0]]`.
- `horse`: `coat`, `mane`, `scale` (1 = 1.56 m at the withers), `tack: true` (saddle, girth, bridle, reins to the
  rider's hands, stirrups at his feet), `cloth` (the saddle cloth's colour), `leather`.
- The rider performs as anybody does (stage him standing: `idle: true`, lines, looks, gestures); `...R.actor` turns
  him the way the horse faces, carries him to the saddle and bends him into a seat: thighs along the barrel, lower
  legs down the horse's sides, hands at the reins. `arms: false` leaves the arms to the performance (a rider who
  points, draws, carries a spear); `lift`, `spread`, `thigh`, `knee` adjust the seat.
- Rider and horse are painted in one picture, so the horse hides the leg on its far side.
- A horse alone: `const h = new Horse({ coat }); h.place([x, z], yaw, dist, gait, ts)` in a `dyn` item that calls
  `h.draw(g, cam, sc.litAt(d), { sun: sc.sun, light: sc.light2(cam), shadow: true })`.
- Looks that are flat on purpose (paper, puppet, flat) keep the cut-out horse.

**A horse that stands is alive** by itself: it breathes (the barrel fills), carries its head higher and lower, dips and
lifts it, turns it to look, flicks each ear by itself, swishes its tail, and rests one hind leg and then the other with
the toe on the ground — every horse in its own time (`horse: { seed }`), the same at the same moment. `horse: { alive: 0 }`
holds one still. The rider on it breathes like everybody else.

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

## Captured clips play at their true speed (2.5.59)

The captured library's source says every performance was recorded at 120 frames a second; some performers were
recorded at 60, so their clips played at DOUBLE speed (found by physics: a runner's hips fell at four times gravity and
a "run" took 340 steps a minute). The clips of performers **75, 77, 88, 106, 139, 141 and 143** now last twice as long
and travel half as fast, as they were performed. **Their moments are true seconds now: double any `in`, `out` or
`clip:…@t` written for them before** (`143_25` the wave is 0.4–5.2, `143_10` the pick-up bends at 3.18 s, `139_02` starts
at 1.0, `143_18@6.0` is seated). `143_01`, which played as a sprint, is a jog of 3.3 m/s: the fastest honest runs in the
library are about 4.3 to 5 m/s at 176–198 steps a minute (`16_46`, `127_08`, `78_06`), which is what a real runner does.
`node devtests/gait.mjs <film> <seq> <actor> [from] [to]` reads any walk or run by numbers (`gaitReport`): steps a
minute against what is usual for those legs, step length, time in the air, lean, knee lift, arms against legs, how far
apart the hands go — and what a coach would say is wrong.

## Joining two captured actions (2.5.60)

`actions` chain with a `blend`. What the feet do in that blend: where the action before ends on standing feet and the
next begins on standing feet, a foot that has more than 8 cm to go takes a STEP (the farther one first, lifted up to
9 cm, 0.24 s each) instead of sliding; a foot that was still in the air when the first action ended lands through the
blend. Everything else is a plain crossfade — so **join where the feet agree**: enter the next capture at a moment that
matches what the body is doing (after a stop, enter a pick-up at a WALKING moment and let it take its steps: 41 cm of
slide became none), and look at the judge's foot-slide lines for each join (`SHOW=foot-slide node devtests/sensei.mjs
<film> <seq>`). A new take with `blend: 0` belongs on a cut.

## The code collection

Every movement that has been worked out is kept ready to use in one place, `kit/moves.js`: for each move its name,
what it is for, the rules that make it read, the captured clips with their moments, the kit functions and tools it
needs, the code to paste, and how to check it. **Ask before working a movement out again:** `{"op":"code.moves",
"q":"pick up a bag"}` (or `node devtests/moves.mjs pick up a bag`; in a film `findMoves('fall')`). So far: run, sprint,
brake, pick-up, carry, sit-alive, fall, kneel, look, caught-looking, wait, duel-ending, dance-on-beat, solar-system,
wave. A move is added only when it has been built, watched in motion and measured — `devtests/moves.mjs` holds every
entry to that (its clips exist, its moments are inside them, nothing starts on a capture's T-pose frame).

The judge now also reports **arm-flare**: arms that fly out for a fraction of a second and come straight back (a pose
that belongs to nothing — seen once a stride, it is the seam of a looping clip). A flare SNAPS (out or back faster than 8 m/s,
which no arm can do); a dancer's quick flourish opens and closes as arms move and is left alone.

A **bone overlay adds degrees** to whatever the body is doing (`keys: [{ on: 'upperarm01.L', keys: [[0, [0, 0, 8]], [0.7,
[0, 0, 8]]] }]`): for the left upper arm z+ opens the arm sideways (15° moves the wrist 10 cm out) and x+ swings it back
(15° = 7 cm). It is the small, local cure for a hand that brushes the body on one frame of a capture (move `hand-clear`).

## Craft: dance, eyes, things in hands, things that go round

Four kinds of scene the practice reel proved, each with the rule that makes it read and the helper that does it.

**A dance on the beat: take the tempo from the dancer, then lock him to it.** A dancer who grooves on the spot hardly
lifts a foot: his pulse is the BOUNCE (the hips come down on every beat or half beat). `node devtests/steps.mjs <film>
<seq> <actor> <bpm> [from] [to]` now prints the dips of the hips too (`bounceTimes(hipHeights, fps)`) and how sure that
pulse is. Make the music's half beat as long as the dancer's bounce, at a beat that is a whole number of frames (a
captured groove of 0.35 s a bounce → 85.7 beats a minute, 21 frames a beat). A person drifts and stumbles, so
`beatLock(dipsInClipSeconds, { bpm, in, out, t })` → `{ warp, dur, locked, skipped, rates }` gives the clip action a
`warp` that plays each stretch a little faster or slower (never more than 15 %) so that EVERY bounce falls on the grid.
Put the big accent (a dive, a drop) on the first beat of a bar: `in = accent − beats × beat`. The lens stands still (a
lens that follows the hips cancels the bounce); cuts fall on a bar or half a bar; a new take begins ON a cut with
`blend: 0`, at `in = dip − (beat moment − t)` so its first bounce is on the grid by itself. Measure the music cue once
as well (its onsets must be where the grid says). All of it is the move `dance-on-beat` in the code collection.

**The older way (steps on beats).** Music is counted in eights and the tempo drives the animation: `beatGrid(120, 30)` → a beat every
15 frames (pick a tempo whose beats are whole frames; `.suggest` names the nearest one that is). A captured dance has
its own tempo, so lay it on the music instead of hoping: build once, then `node devtests/steps.mjs <film> <seq> <actor>
120` prints when the dancer's feet come down and how to play the clip — `beatMatch(steps, bpm)` → `{ rate, in, per,
error }`: `{ clip, in, rate }` on the action makes every step fall on a beat (per 1), a half beat (0.5) or every other
beat (2). `sure` (and the tool) says how much of a pulse the dancer has at all: below about 50% the clip has
no steady step to match (a captured salsa is quick-quick-quick-pause with taps in between) — choose another clip, or
put the POSES on the counts instead. `countAt(t, bpm)` → `{ count: 1…8, accent }` puts a pose, a cut or a light on the 1. Cut on the beat; hold a
shot for a whole count of eight or four.

**Eyes.** They go first and fast, the head follows when the thought commits. A film's `look` keys are played that way
by themselves: the eyes dart to a new target in two or three frames (most of it in the first), and a big change of gaze
takes a blink with it (`[t, target, { blink: false }]` forbids it for a stare that drops; `{ blink: true }` asks for
one). `eyesLead([{ t, at, head, lead?, fade? }], { end })` writes both halves of a look from one list: `.look` for the
actor and `.head` for `{ op: 'stage.overlay', actor, add: L.head }`, each head turn setting off 0.22 s after the eyes.
`head: 0` is a look with the eyes alone (careful, shy, sneaking: the head stays where it was), 0.4–0.5 a guarded look,
0.9 an open one. Caught looking: eyes away at once, the head ducks after 0.08 s with a short fade, the blush comes half
a second later (`emote: [[t, { like: 'afraid', blush: 0.9 }, 0.5]]`). Someone who stares forgets to blink; nobody turns
eyes and head in one move.

**Things in hands.** `carried({ rest: [x, y, z], by: 'boy', hand: 'R', from: 2.7, make: (p, yaw) => new Mesh(box(p, 0.36,
0.27, 0.15, yaw, { side: '#b5382e' })) })` in `dyn: (ts, ctx) => [bag(ts, ctx)]`: the thing lies at `rest` until the
hand closes on it at `from`, then goes where the hand goes (`to` + `drop` set it down). Put it where the hand really is:
solve once, `where.mjs … boy:wrist.R` at that moment, move `rest` there. A pick-up at a run is keyed over the captured
run: a `lean` of 35° and `keys` on `hips` (−0.3 m) and `hand.R` (down to the thing and up again) for a second round
the moment; slow the shot there with `warp: ramps(…)` so the snatch reads.

**Balls and things that go round.** `ball(g, cam, p, r, color, light, { bands, land, hoop })` draws a ball lit from one
side with the right phase (full, half, crescent): a planet, a moon, a football. `solarSystem({ sun, year: 9, planets:
[{ name, ring, r, color, year: true, bands, hoop, moon: true }] })` → `S.draw(g, cam, t)` in a shot's `over`, and
`S.placeOf('earth', t)` for a camera to follow: the planets go round by Kepler's law (twice as far out, 2.83 times as
long), which is what makes it look like a solar system and not a carousel. A set of `space({ moon: false })`, one slow
camera move, no cuts.

**A fight that reads** is few beats, each one a wind-up held for a moment, a fast path, a contact that stops for two
or three frames, a recoil and a recovery — `stage.fight` writes them (see **Action** for the sparks, the shake and the
slow motion on the blow that ends it). Three-quarter view, both bodies in full for the first exchange; keep to one side
of the line; go close only between blows.

While a build runs, every progress report carries `work`: the whole job's percentage, the time left and the numbers of
the step that runs (`Step 3 of 6 · Drawing the shots · 42% · 1,034 / 2,460 frames · 6.1 frames/s · about 4 min left`).
`code.stop` stops it at any stage: speaking (after the line in hand), solving, drawing, joining.

**The look to reach is written down in `STYLE.md`** (beside this file): line, colour and shade, hair, face, body,
clothes, hands and things, places, and how a character sheet is judged against the owner's reference pictures. Read it
before designing any character or place; what the engine cannot draw yet is marked there.

## Working well — the director's handbook

What goes wrong in films made this way, and how each mistake is caught before anyone sees it. Read it before staging.

**The order of work.** Write → look → build → read the judge → fix → look again. Never show a build unread.
1. After every change look at the shots you touched: `code.sheet {shot}` (moments in order), `code.frame {shot, t}`.
2. Look closely at what people look at first — hands, faces, feet, hips: `code.frame {shot, t, scale: 2, crop: [left,
   top, width, height]}` enlarges a part of the picture. A half-size sheet hides most mistakes.
3. `code.build` comes back with `sensei`: what the judge found in the bodies, cameras and story. **MUST findings are
   mistakes, not opinions** — a foot that skates, an arm inside the body, a blade through its owner, a scabbard worn
   through a coat, bodies in one place, a camera that jerks. Fix them (`sensei.report {sev: "must"}` gives each with its
   fix, `sensei.issue {id}` shows it marked in the picture) and build again. `code.export` is refused while any remain.
4. Look at the film from angles the camera does not use while you stage: from behind, from the side, from above. Most
   costume and prop mistakes (things worn through the body, cloth the body comes through) only show from behind.

**Life.** Nobody in a picture is a statue. The engine gives every body breath, shoulders that ride on it, a trunk that
sways over the hips and a head that is never quite still, at its own pace; a speaker's head rides the words. Leave it on.
- `alive: 2.5` on an actor who has run or fought: he heaves. `alive: 0` only for the dead.
- Life is the floor, not the acting. A body that only stands for more than two seconds gets a captured wait
  (`idle: true, clip: '137_28'` after a short plain `idle`, so its feet stay planted), a look that goes somewhere, a hand
  that settles. A listener reacts: eyes first, then the head, a blink on the turn.
- The judge's `statue` finds a body that holds still for two seconds; `sensei.report {kind: "statue"}`.
- Horses, armies, banners, cloth, hair, grass, clouds and mist move too: give the place a wind that can be seen
  (`makeWind({ speed: 2.5, gust: 1 })`, clouds `wind: [30, 9]`, `mistBand({ drift: 0.07 })`, `grassPaint({ wind: 1.9 })`).
  A still background under a moving figure reads as a painted card.

**Bodies.**
- Proportions: judge a full figure from the front before any shot. Warriors: weight 0.45–0.55, muscle 0.9+, proportions
  0.95–1, a close-cut tunic; heavy presets with baggy tunics read as sacks with short legs.
- Detail weights are 0…1 only; the way back from a preset's own detail is its opposite detail, never a negative weight.
- Everyone stands ON the ground: `code.where {shot, t, actors: {v: ["root"]}}` — hips near 0.93 m for a standing adult.
- Hands: a hand lies along its forearm and swings with the arm; look at the hands on a sheet of any walk or run.
- A hand sent to something on the body (a hilt, a pocket, the chest) crosses IN FRONT of the body: the forearm must
  never run through the belly. The judge measures this against the body as it is dressed (`arm-in-body`).
- Clothes in layers are carried by different bones: what lies under a coat at rest can come through it in motion.
  Look from behind in motion. A robe (`design.robe`) and a skirt/coat garment keep themselves outside the body.

**Movement.**
- Running is captured running (`gait: 'run'`), never a sped-up walk. A runner who stops needs room to stop: a run that
  ends in `idle` within half a second skates (`foot-slide`). Let the run end a stride early (`speed` down, a longer
  blend) or stage the stop out of the picture.
- Someone who reports to a leader is SEEN to arrive — do not cut away from the hard part. The chain that works, every
  link a captured performance: `{ go, gait: 'run', clip: '09_05', speed: 3.6 }` (a sprint with the arms pumping; the
  Stage's own run tucks them) → `{ clip: '127_05', in: 0.45, out: 2.2 }` (run to a quick stop: weight back, arms out; it
  carries him 2.3 m on) → `{ go, gait: 'walk', face: deg }` (two steps that turn him to his lord; allow 2.4 s) →
  `{ clip: '23_03', in: 0.45, out: 2.6, warp: [[0, 0.45], [1.45, 1.9], [rest, 2.6]] }` (down on one knee in a second
  and a half, then the capture runs on very slowly: he never freezes). Solve, then read where he really ended
  (`code.where`) and move the way-points by the difference: captures travel on their own.
- A held pose after a clip (`pose: 'clip:…@t'`) slides the feet to a new anchor; hold by slowing the same clip (`warp`).
- Laughing: only gentle captures, or the upper body; if the voice cannot laugh, write a smile.
- Before a kind of movement goes into a film, look at it alone on a sheet from the side.

**Swords.**
- Worn: `props: [{ kind: 'sword', hold: 'hip' }]` (or the swordsman's kit). It hangs outside the clothes by itself.
- Walking with one: `{ t0, t1, scabbard: true }` rests the left hand on the scabbard (the arm would swing through it).
- Drawing: one overlay from `'sheath'` — `sword: [[0,'sheath'],[1.5,'sheath'],[1.7,'unsheath'],[2.05,'loR']]`. Never
  key a blade from the hip to a guard by hand: it turns through the body. Putting it away: end the list in `'sheath'`.
- A fight: ready, wind-up, swing, impact, follow-through, recovery; the world and the camera stay still on big strikes.

**Cameras.**
- Every shot moves a little or has a reason not to: a slow push on a face (5–8 % in four seconds), a rise over an army,
  a track with a walker. A move too small to feel (centimetres) is a still picture with a flaw in it.
- Stay on one side of the line between two people who look at each other, shot after shot (the 180° rule): a cut to
  the other side turns them round in the viewer's head.
- A crowd has depth only from above or from an angle: at eye height in front of it, ten thousand men are one row.
- No shake, ever — not on impacts. Locked-off shots, or smooth eased moves that have a reason (`move(a, b, t, t0, t1)`).
- Set cameras by numbers: `code.where` gives where heads, hands and hips are; a camera that follows a head shakes
  with every step — aim at a point that is eased over time.
- Keep both feet in the picture or cut on purpose (chest up); never cut at ankles or knees.
- Nobody who has not entered yet may be visible in an earlier shot: `hide: [ids]` until their entrance.
- A leader and those who report to him: check from the camera who stands before whom.

**Voices.**
- A natural dramatic line is 2.2–2.8 words a second; pace comes from pauses written into the line (`...`, commas).
- Listen to the first word and the last word of every line; a line the voice gets wrong twice is rewritten for speaking.
- A stopped build loses no spoken line; lines already spoken are only fitted again.

**Pictures.**
- Land and sky are painted (`paintedLand`), judged at full size. Far ranges: low, near the colour of the air, peaked.
- Armies are performed soldiers laid down by the thousand (`legion`), never capsules; look from inside the ranks, and
  CHECK WHICH WAY THEY FACE in a frame from the enemy's side and from behind. Their figures wait (`wait:`), never `at:`.
- Beards are painted on the skin (`face.beard`), hair is designed lock by lock or drawn back to a tie.

**Housekeeping.**
- One sequence per place and stretch of story; shared helpers in their own file.
- People are drawn whole, nearest last. For a hand on a far shoulder give the nearer one `actor: { id: { bias: 0.5 } }`.
- Nothing in `.vs` needs care: delete it and everything is made again from the code.
