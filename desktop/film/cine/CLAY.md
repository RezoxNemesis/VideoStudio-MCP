# CLAY — the stop-motion studio of Cine

Films that look handmade: clay puppets with talking mouths, felt clothes, sets of painted card and tin, tiny bulbs,
shot through a lens with a shallow field so the world reads as a miniature. It is all code, drawn on this PC's
graphics card by a hidden window, up to 4K, and encoded on the graphics card too (NVENC) so the processor stays free.

A clay film is a Cine film whose `film.js` begins with `// @engine clay`. Every `cine.*` command works on it:

| to | command |
|---|---|
| start a film | `cine.new {name, kind: "clay"}` |
| write / read its code | `cine.write {film, file, text}`, `cine.read {film, file}` |
| look at one frame | `cine.frame {film, shot, t, width: 720, height: 1280}` (give both sizes: the film is vertical) |
| look at a shot's moments | `cine.sheet {film, shot, times: [..], cols, cell: 360, height: 640}` |
| draw the film | `cine.render {film, width: 2160, height: 3840}` then `cine.progress` |
| new sound only | `cine.join {film}` |
| lips from voices | `cine.tool {film, script: "lips.py", args: ["lines.json", "lips.json"]}` |

Files are looked for in the film's folder first, then `Cine/assets`, then this engine (`film/cine/web/clay`), then the
Cine engine. So a film can carry its own newer `coach.js`, `puppet.js` … while the work is being done, and the app's
copy is used by every other film.

## 1. The film folder

```
film.js      // @engine clay — META and the shots
audio.wav    the finished mix (voices, music, effects), laid under by cine.render at -14 LUFS
lips.json    mouth shapes for the spoken lines (made by lips.py)
lines.json   { id: { file: "voices/R1.wav", text: "what is said" } }  → input of lips.py
```

`film.js` exports `META = { name, width, height, fps, audio, order: [shot ids] }` and `S = { id: { dur, t0, build(E), frame(t, ctx, E) } }`.
`build` makes the set and puppets once and returns what `frame` needs. `frame(t)` poses everything for the moment `t`
(seconds into the shot) and returns the camera: `{ pos, target, fov, focus, ap, roll }`. `t0` is the shot's start in
film time: the world's clocks (lamps going by, handles swaying) run on film time, so they continue across cuts.
A frame must depend on `t` only (never on the frame before): pieces are drawn in any order.

## 2. The engine (`clay.js`)

- `mat(color, kind, o)` — a handmade material. kinds: `clay` (lumps, thumb marks), `felt` (matte, a fuzzy rim of light),
  `paint` (brushed card or wood), `tin`, `paper`, `plastic`, `glow` (a lit bulb: `{ power }`). Options: `map`, `rough`,
  `bump`, `mottle`, `fuzz`, `ridge`, `ns` (the grain's size), `side`, `alphaTest`.
- shapes (sizes baked in, metres): `ball(rx, ry, rz)`, `limb(r0, len, r1)` (a sausage hanging from the origin),
  `rbox(w, h, d, r)` (NOTE: its bevel adds `r` to each side — a thin thing laid on its face must clear that),
  `tube(points, r)`, `mesh(geo, mat, { p, r, cast, recv, name })`, `group(kids, { p, r })`,
  `paintTex(w, h, draw)` (a picture drawn in code as a texture).
- `E.scene`, `E.ticks.push(fn(t))` (things that move with film time), `E.clear`, `E.hq` (true from 1440 wide: use bigger shadow maps).
- the lens: the camera's `focus` (a point or metres) and `ap` (how fast things go soft: 8 wide shots, 22 people, 30 macro).
- `E.look = { exposure, sat, lift, gain, vig, grain, bloom, ca }`, `E.overlay(draw(g, W, H))` — a 2D layer for words.
  Draw the overlay again only when what it shows has changed (a 4K layer costs time).

## 3. Puppets (`puppet.js`)

`new Puppet(look, overrides)`; looks in `LOOKS` (`rohan`, `mukesh`, `meher`): size `h`, colours, `hairStyle`, `glasses`,
`tie`, `lanyard`, `moustache`, `jaw`, `narrow` … Add a look by adding an entry. About four heads tall; faces +z; its left is +x.

`puppet.set(pose)` — all optional:

```
at [x,y,z] (hips)   yaw°   lean [pitch, roll, twist]   head [yaw, pitch, roll]   gaze [yaw, pitch]
lids 0 shut … 0.7 tired … 1 wide      brows [height −1…1, angle° (inner ends UP + = worried, DOWN − = angry)]
mouth [curve −1 frown … 1 smile, open 0…1, width 0.5 pursed … 1.3 spread, skew, tongue 0…1]
handL / handR { p (from the hips, body frame) | w (world), f (where the fingers point), n (where the palm faces), pole (elbow), curl, thumb, spread }
footL / footR { p | w, toe°, pitch°, pole }
```

Arms and legs find their own elbows and knees (two-bone IK). The mouth is really cut into the head: lips, a dark
inside, upper and lower teeth, a tongue; the chin drops with it. `puppet.at('head' | 'eyes' | 'chest' | 'hips' | 'handL' | 'gripR' …)`
gives a point in the world (aim cameras at these, not at guessed numbers).

`track([[t, pose, ease], …])` (from `stage.js`) → `(t) => pose`. A key carries only what changes. Eases: `io`, `out`, `in`, `lin`, `snap`.
Within one track a hand must be given the same way in every key (`p` everywhere or `w` everywhere — `worldHands(pose)` converts), or it jumps.

## 4. Staging (`stage.js`)

- `shoot(target, { w, fov, az, el, focus, ap })` — a lens on a subject: `w` is the width of the picture at the subject in
  metres. A vertical frame is narrow: a head close-up is `w: 0.5`, a seated person `w: 0.8`. (`setAspect(16/9)` for a wide film.)
- `sit(x, z, overrides, h)`, `walk(t, { x, z0, v, step, h })` (the planted foot never slides), `blink(t, [times], open)`.
- `lipAt(LIPS.id, lineStart, T, restMouth)` — the mouth for film time `T` from a lip track.
- paper in hands: place the paper first with `paperM(centre, writingDirection, topDirection)` and `setM(paper, M)`, then
  `handOn(M, 'L' | 'R', holdOf(w, h), { pole })` gives each hand. For a grab, keep the paper where it is until contact,
  then give it the hand's matrix times the relative matrix taken at the contact pose.
- `ride(cam, T, k)` — the camera trembles and rolls a little with the car. `words(g, W, H, text, y, px, o)` for subtitles and titles.

## 5. Sets and props

- `buildCoach(scene, E)` (`coach.js`) — a whole second-class car: both walls with barred windows and open doorways,
  benches both sides of the aisle (`CAR.BAYS`), poles, two rails with swaying handles, racks, fans, tube lights, signs,
  an alarm chain, litter; the city going by outside both rows of windows, masts whipping past, a sodium lamp sweeping
  its light and the bars' shadows through. Every piece has a name; lift pieces out per shot, as a real set's walls lift
  away: `for (const m of co.G.children) if (['bay1','rbay1','handle','rail'].includes(m.name)) m.visible = false`.
  Names: `bay-2 … bay2` (left benches), `rbay…` (right), `handle`, `rail`, `rack`, `luggage`, `wallL`, `wallR`, `skinL`
  (the car's outside), `winL/R`, `signL/R`, `ceiling`, `tube`, `fan`, `end`, `doorpole`, `chain`, `litter`, `floor`.
  Seat 47 is the window seat of bay 0: hips at about (−0.53, 0.462, 0.21), facing +z. The aisle's middle is x = 0.26.
- `buildExterior(scene, E)` (`exterior.js`) — the other model: a viaduct, the train (`.train`, move it along z), streets
  with traffic, rows of buildings into haze, the sky. `city.js`: `building(w, d, h, { seed, near, glow, hoarding })`,
  `vehicle(kind)`, `tree()`, `sky({ … })`, `facade()`.
- `props.js`: `makeSlip(lines, { write })` (handwriting that writes itself: `slip.userData.write(0…1)`), `makeTiffin()`,
  `makeBag()` (`.userData.open(k)`), `makePunch()`, `makePen()`.

## 6. Lips from a voice (`lips.py`)

`lines.json`: `{ "R4": { "file": "voices/R4.wav", "text": "कौन हो तुम? एम?" } }` (paths from the film's folder or absolute).
The text (Devanagari or Latin) gives the order of the mouth shapes; the sound gives when: phrases are matched to the
stretches of speech between silences, and vowels to the peaks of loudness. Out: `lips.json`, 60 values a second of
`[open, width, tongue]`. Lips that meet (m, b, p) shut for two frames; the mouth runs 30 ms ahead of the sound.
Write the text as it is spoken, with the same pauses (commas, `…`, `?`) the speaker made.

## 7. The method

1. **Look sheet first.** Each puppet front / three-quarter / side / back and a face in four expressions, the set wide
   and in detail, every prop — real frames from this engine, agreed before any animation.
2. **Voices first, then timing.** Shots are timed to the real lengths of the lines. Speak a phrase as its own line and
   join phrases with a real pause (0.4–0.5 s); a tender line is a little slower.
3. **On ones at 30 frames a second**: every frame is its own pose. Anticipate, arrive (`out`), settle; the eyes lead a
   head turn; blink on big turns.
4. **Cameras are checked on stills** before anything long: a camera inside a roof, behind a seat back or blocked by a
   pole is the commonest mistake. Vary the size of shots: macro, close, mid, wide, a point of view, a top shot.
5. **The thing leads, the hands follow** (section 4). Look at every contact at full size.
6. **Review before showing** (the owner's checklist): hands and grips, anything passing through anything, gaps at
   joints, colours, camera interest, clothes and hair, the side profile, legs and feet, the background and the world.
7. Draw at 2160 × 3840. A piece is five seconds; only changed shots are drawn again (`cine.render {film, shots: [...]}`).

## 8. Mistakes already paid for

- Two balls for a head leave a crease like a second chin: the head is one displaced sphere, features laid by raycast.
- A hairline at brow height hides the brows; brows angled inner-end-down read as angry.
- `rbox` is bigger than its numbers by its bevel: decals placed at the nominal face are buried (train windows, plates).
- A tie, a strap, a lanyard must follow the chest's surface or they sink inside the shirt.
- A paper's writing shows mirrored from behind: give it a back.
- Mixing body-frame and world-frame hand targets in one track makes the hand jump across the car.
- A fill light is needed when a face is turned away from the key; a lamp too close to a wall burns it white.
- Text-to-speech reads short lines too fast and ignores `…`: speak phrases separately and join them.
