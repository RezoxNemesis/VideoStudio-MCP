# Cine 3D — the guide

Cine 3D is the engine inside VideoStudio 2 for **2.5D films with real depth**: painted layers standing at their true
distances, toon bodies driven by captured human motion, and one real lens. It was built for the film
**"The Long Road to VI"** (4:44, 2560×1440, 30 fps, 20 shots) and this guide is the whole recipe of that film: the
files, the functions, every number of light, colour and lens that was used, the camera moves, the checks and the
mistakes already paid for. The film's own source is in `film/cine/films/long_road_vi/` — read it beside this guide; it
is the worked example of everything below.

Any model can make a film of the same level (or better) by following the order of work in section 2.

---

## 1. What is where

```
<app>/resources/film/cine/            the engine (comes with VideoStudio 2)
  GUIDE.md                            this guide (command cine.help)
  web/engine.js                       the renderer: Engine, Actor, Card, all shaders
  web/kit.js                          moods, the road world, props, weather, the neon city, a bedroom, camera helpers
  web/shot.html, lab.html             the pages that draw a shot / a close look at a body
  web/three.module.js                 three.js r170
  tools/mh.py                         bodies: MakeHuman base mesh → dressed, rigged toon character with a painted face
  tools/mocap.py                      performances: captured clips → retargeted, looped, stitched, feet planted, acting layers
  tools/paint.py                      painted textures (ground, trees, grass, clouds, hills, the neon city)
  tools/qa.py                         checks on a finished video (camera steadiness, flashes, joins)
  tools/cli.cjs                       the same engine without the app (a workspace with headless Chrome)
  films/long_road_vi/                 the film: film.js (shots), moves.py (performances), audio.py (score and effects)
  films/starter/                      what cine.new copies

~/Videos/VideoStudio/Cine/            the person's Cine folder
  assets/data/<body>.json|.bin        bodies (shared by all films)
  assets/tex/*.png                    painted textures      assets/fonts/*.woff2
  assets/bvh/*.bvh                    captured clips (only needed to make new performances)
  <Film>/film.js                      the shots
  <Film>/data/mo/<track>.bin          performances          <Film>/data/marks.json   marked moments of performances
  <Film>/audio.wav                    the sound (laid under at -14 LUFS when the film is joined)
  <Film>/out/seg/<shot>_<k>.mp4       five-second pieces already drawn      <Film>/out/<name>.mp4   the film
```

A page asks for a file by a plain name (`engine.js`, `tex/grass.png`, `data/hero23.bin`, `data/mo/s01_hero.bin`).
The server looks in **the film's folder first, then `assets/`, then the engine**. So a film can carry its own
`engine.js` or `kit.js` (a newer one, or one changed for that film) and its own textures.

### Commands (through the VideoStudio 2 connector)

| command | what it does |
|---|---|
| `cine.help` | this guide |
| `cine.list` | films on the PC, whether assets are in place |
| `cine.new {name}` | a new film folder from the starter |
| `cine.open {film}` | shots in order with lengths, files, pieces drawn |
| `cine.read {film, file}` / `cine.write {film, file, text}` | the film's text files; after `film.js` it says whether the code loads |
| `cine.frame {film, shot, t, width?}` | one frame exactly as in the film (everything before it is simulated) |
| `cine.sheet {film, shot, times: […], cols?, cell?}` | several moments in one picture |
| `cine.render {film, shots?, width?, height?, fps?, crf?, force?, maxMB?}` | draw in 5-second pieces on the graphics card, join, add sound; runs on |
| `cine.progress` / `cine.stop` / `cine.join {film, maxMB?}` | watch, stop, join again after new sound |
| `cine.unpack {file}` | unpack a `.tar.gz` lying in the Cine folder (how data made in a workspace arrives) |
| `cine.tool {film?, script, args?}` | run a Python tool on the PC (needs python3 with numpy, scipy, opencv, pillow) |

In a cloud workspace use `node tools/cli.cjs frame|sheet|render|join|open|lab <film dir> …` — the same code, drawn by
headless Chrome in software (1.2–2.3 s a 2K frame). On the PC's graphics card it is many times faster.

---

## 2. The order of work

1. **Story as shots.** Write each shot as one sentence that names what the *lens* does ("rack focus from the stone to
   the runner, then a crane up behind him"). No shot may be "zoom in" or "zoom out". 8–24 s a shot.
2. **Facts.** Look up anything about the real world before it goes on a sign.
3. **Bodies** (`mh.py`): build the cast once; look at face close-ups (`lab.html?views=hero23:face,hero23:face3`).
4. **Performances** (`moves.py` + `mocap.py`): compile per shot; read the gait report (steps a minute, air time, slide).
5. **Stage** each shot in `film.js`; look at `cine.sheet` of 6–12 moments, then full-size `cine.frame` of every face,
   hand and low-angle moment.
6. **Sound** (`audio.py`): score and effects synthesised, footsteps on the real foot strikes.
7. **Render** (`cine.render`), then **check** (`qa.py`), then deliver a copy under the chat's size limit (`maxMB: 340`).
8. **Record** every new mistake as a lesson (section 11).

---

## 3. A film in code

```js
import { THREE, V3, clamp, lerp, sm, sm2, seg, rng } from './engine.js';
import * as K from './kit.js';
export const META = { name: 'My Film', width: 2560, height: 1440, fps: 30, audio: 'audio.wav', order: ['s01', 's02'] };
export const S = {};
S.s01 = { dur: 14, soft: true, about: 'one line',
  async build(E) { /* make the place and the people; return what frame() needs */ },
  frame(t, c, E) { /* move people and things for second t; return the lens */ return { p, t, fov, focus, dof }; } };
```

- `build(E)` runs once per piece. `frame(t, c, E)` runs for **every** frame from 0, also for frames that are skipped
  (a piece that starts at second 5 simulates 0–5 first), so particles, cloth and feet match across pieces. Never use
  `Math.random()` in a shot: use `rng(seed)`.
- The lens: `{ p: [x,y,z], t: [x,y,z] (look-at), fov (degrees, vertical), roll?, up?, focus (m), dof (strength; 0 = all sharp), maxBlur?, line? (outline width, 1.7) }`.
- `soft: true` (or `soft: t0 => bool` per piece) turns on the multi-tap focus blur: needed whenever `dof` > ~5 at 2K;
  it costs about 1.7× the drawing time.
- World: **x is the way forward along the road, y up, z across**; the lens usually stands on +z. A body made by
  `mh.py` faces +z; a performance travels along its own +z, so a runner going along +x is placed with yaw `PI/2`.
- Helpers: `sm` (smoothstep), `sm2` (smootherstep, for camera moves), `seg(t, a, b)` (0→1 between a and b),
  `K.path(points, u)` (Catmull-Rom), `K.keys([[t, v], …], t)`, `K.mix3(a, b, u)`.

### People

```js
const a = await mk(E, 'hero23', PAL.hero, 's09_hero', x, z, yaw);   // body, palette, performance, where
play(a, t);                       // pose for second t (a.rate, a.off shift time)
const [x, y, z] = anc(a, a.f);    // where the body is, smoothed: aim cameras at this, never at the bobbing hips
K.look(a, yawDeg, pitchDeg, torso);          // turn the head on the neck (55 % head, 45 % neck)
a.mat.uniforms.uSmile.value = 0.8; a.blinkHold = 1;  // face
K.placeBy(a, K.MARKS.s11_hero.seat, wx, wz, yaw);   // put a marked moment of the performance on a spot of the world
```

Palette = 11 colours by region: `[skin, top, bottom, shoe, sole, hair, eye-white, dark (lashes), sock, trim, top2]`.
The film's: hero `['#c68e68','#e9a81c','#2b3a5c','#c8402c','#f2ece0','#17131a','#ffffff','#1a1418','#efe9dc','#c98a10','#e9a81c']`.

---

## 4. The engine, with its numbers

**Bodies** — toon shader, two tones with a soft edge: `lit = smoothstep(-0.03, 0.09, N·L)`;
colour = `mix(base·shade, base·light, lit) + base·ambient·(0.5+0.5·N.y)`; rim = `rimColour · fresnel^2.5 ·
smoothstep(-0.1, 0.6, N·rimDir)`; outline = the body drawn again inside-out, pushed out by 1.7 px (never more than
6.5 mm), colour `mix(base·0.30, outline, 0.45)`; loose cloth flutters 1.5 mm.

**Faces** (new in the second cut; all worked out from the head's own shape in `mh.py`, stored per vertex as `paint`):
- `paint.x` blush: two cheeks (24 mm blobs beside the nose), nose tip ×0.22, ears → skin × `(1.03, 0.76, 0.74)` at 50 %.
- `paint.y` placed shadow from the surface's own hollows (smoothed mesh minus mesh, at 2 and 7 smoothing passes) plus
  under the nose and under the lower lip → skin × `(0.60, 0.44, 0.42)` at 85 %, and the light there is halved.
- `paint.z` shine on the nose tip and lower lip, 34 % of the light's colour.
- Lips: skin × `(0.84, 0.56, 0.56)` at 78 %. Blood under the skin where light turns to shade: `+ base·(0.30, 0.07, 0.03)·lit·(1−lit)·0.9`.
- Eyes: white shaded darker under the upper lid; iris (default `#5a3418`) with a dark rim, a lighter lower half, a
  pupil, and two catch-lights (upper-right large, lower-left small); never darker than 82 % lit.
- Hair: strands as stripes round the head (`sin(azimuth·46)`, ±12 %), a zig-zag band of shine at mid height;
  the hairline is slid onto a designed line and smoothed 10 times so it is one clean curve; brows are tapered strokes
  47 mm long laid on the skin; lashes on the upper lid only.
- Head normals lean 50 % towards a ball, so the face takes one clean shadow shape but the nose and brow still turn.
- `uFace` (0–1) fades all face paint (for far crowds).

**Cards** (trees, clouds, hills, mist, glows): textured planes that turn to the lens round the vertical axis
(`mode: 'y'`), fully (`'face'`), or not at all (`ry` given). Fog and focus blur are worked out at the corners.
Sway: `sway` metres at the top, squared with height. `relight: true` cards (clouds) are painted as light amount and
coloured by `lit` / `shd`. Keep `depthTest` on for every card.

**Ground**: a detailed band 9.5 m each side of the road (grass, dirt shoulder, asphalt with dashes every 9 m, wet
reflections, puddles) and cheap outer fields. Road half-width 3.0 m, shoulder 1.6 m.

**Shadows**: every body, prop and card is drawn flattened onto the ground along the light into a half-size picture
(darkest wins) which the ground multiplies in (`uShadowTint`). Indoors there are none: use `K.blob`.

**Focus**: blur (in mip levels) = `log2(1 + dof·fpx/1000 · |d − focus| / (d · focus))`, capped at 5 (2.2 without `soft`).

**Finish**: bloom (threshold 0.78, two blur passes, quarter size), then grade `gain, gamma S-curve, saturation, bloom
amount`, lift, vignette 0.35, grain 0.016, FXAA only over bodies and props. **Clamp before the S-curve.**

**Props that glow by themselves**: a texture loaded `raw` keeps its alpha as "how much this pixel shines"; the prop
option `glow` (≈1.1) shows those pixels at their own brightness whatever the place's light (lit rooms, neon).

---

## 5. Moods — the film's exact light

`K.setMood(E, name)`, `K.mixMood(E, a, b, u)` travels between two inside a shot. Each: zenith / horizon / below
horizon, sun disc, sun glow, `sky [glow tightness, glow amount, stars]`, sun direction, key light, shade, rim,
ambient, outline, fog colour / fog towards the sun, `fogp [density, height falloff, max, sun share]`, ground tint,
road tint, shadow tint, tree tints [top, bottom], grass tints, cloud [lit, shade], `grade [gain, gamma, saturation,
bloom]`, lift, gain colour.

| mood | used for | key light | shade | rim | fog (density) | grade |
|---|---|---|---|---|---|---|
| gold | 2013, the boy sets off | `#ffe6ba` | `#8676ac` | `#ffcf8e` | `#f1d2a4` (0.0026) | 1.04, 0.96, 1.10, 0.55 |
| kite | kites, noon | `#fffaee` | `#8494c2` | `#ffffff` | `#cfe2f0` (0.0011) | 1.03, 0.97, 1.14, 0.35 |
| monsoon | rain | `#c6d0d2` | `#8f9bae` | `#d8e4ea` | `#9eacb0` (0.0075) | 1.00, 1.00, 0.96, 0.25 |
| amber | the fork, the low sun | `#ffc48a` | `#62548c` | `#ffb26a` | `#e9a374` (0.0034) | 1.03, 0.97, 1.12, 0.70 |
| fog | 2020 | `#dcdcd6` | `#a2a8b6` | `#eef0f0` | `#cdd2d3` (0.024) | 1.00, 1.00, 0.72, 0.20 |
| pink | the city appears | `#ffc4ac` | `#6c5c9c` | `#ff9e8a` | `#c98c9c` (0.0032) | 1.04, 0.97, 1.05, 0.60 |
| blue | blue hour | `#aabaea` | `#4c568a` | `#ff8fa0` | `#46588e` (0.0036) | 1.12, 0.97, 1.05, 0.70 |
| night | the wait | `#a9c0f0` | `#465284` | `#b9c8f0` | `#1c2748` (0.0042) | 1.22, 0.97, 1.00, 0.80 |
| neon | the gate, the city at night | `#d2b4ea` | `#584c84` | `#ff8ab8` | `#3a2e64` (0.0034) | 1.20, 0.97, 1.02, 0.85 |
| vice | first light over the city | `#ffd0b0` | `#6a5a9a` | `#ffb090` | `#d090a0` (0.0024) | 1.06, 0.97, 1.12, 0.70 |
| room | a bedroom lit by a lamp and a television | `#ffd9a8` | `#5a4e78` | `#8ab4ff` | none | 1.08, 0.97, 1.06, 0.55 |

The complete sets (sky, sun, tints) are in `kit.js` (`MOODS`). Night moods must be **lifted** until figures separate
from the ground; never stack a dark grade on a cool scene.

---

## 6. The kit

**The road world** — `K.roadSet(E, { mood, x0, x1, seed, clear: (x,z) => bool (no trees here: keep the lens's side
open), trees: { neem, banyan, gulmohar, palm, bare, bush weights }, dens, grass (count), grassz, grassNearMin (no
tufts between a low lens and the road), nearLow, flowers, field: { z, col }, wet, puddle, road2, clouds, cloudv, hills,
half, shoulder, marks, wind })`: ground, a ring of hills and far tree lines, 16 drifting clouds on a dome 3.2 km out,
an avenue 7.5–12.5 m from the road, groves to 200 m, bushes, 2,600 grass tufts that sway.

**Props** — `milestone(x, z, top, number, unit)`, `sign(x, z, w, h, draw, { ry, y0, emis })` (a hoarding painted by a
canvas function), `gate(x, text)` (toll gate with a boom: `.arm(angle)`, `.text('A|B')`), `lamp`, `parapet`, `box`,
`wipeBox` (a dark post close to the lens to cut on), `canvasTex` / `redraw`.

**Living things and weather** — `kites` (with shadows on the ground), `birds`, `mist`, `glow`, `rainFx` (2,600 far
streaks in a 36 m box, 260 near ones in a 9 m box round the lens, 46 splash rings), `bursts` + `footSplash` (drops
thrown at every real foot strike), `fireworks([[t, x, y, z, colour, speed, size]…])`.

**The neon city** (second cut; textures by `paint.py vice`, all original) — `K.viceCity(E, x0, { len, towers })`:
eight art-deco hotel fronts as blocks on both sides of the boulevard (pastel stucco, eyebrow ledges, a fin with a neon
name, lit rooms that glow by themselves), palms along both kerbs, street lamps in pink and cyan, glass towers behind,
two far shores of skyline, water carrying the lights, four searchlights, car lights going down the road.
`K.neonSign(E, x, y, z, w, h, text)` → `.set(n)` lights the first n letters. `K.building`, `K.beams`.

**A room** — `K.room(E, { w, d, screen })`: floor, four walls with posters, a window, a door and a bookshelf, a
ceiling, a bed (`.setBed(height, colour)`), a rug, a television whose picture is a canvas (`.screen.draw(fn)`),
a lamp. `K.gameScreen(t, { label, car, a, b })` is the film's own little driving game for the screen.
Light a face from the screen: `E.L.uLightDir.value.set(0.1, 0.22, -1)`, key colour = the screen's colour × a flicker
`0.8 + 0.2·sin(23t)·sin(5.1t)`, rim warm from the lamp behind.

**Text** — `K.stamp(E, 'JULY 2016', alpha, sub?)` (lower left, Oswald 46 px at 1440p, letter-spacing 7 px),
or `E.text((g, W, H) => …)` for titles (Bebas 210 px for the main title).

---

## 7. Bodies — `tools/mh.py`

`python3 mh.py [names]` builds the cast (`CAST` at the end of the file) into `assets/data`. A body is the open
MakeHuman base mesh (MPFB2, CC0) morphed by age (`child` 1 = ten years, 0 = adult), weight, muscle and gender; a shirt
and trousers cut from the mesh's own "tights" helper and let out into loose tubes; a hair shell grown from the scalp
(styles `short`, `bob`, `bun`); lofted sneakers; brows; the game-engine rig with its weights; and the
painted face of section 4. Output: `<name>.json` (bones, rest positions, size `S` against a 1.75 m adult) and
`<name>.bin` (positions, normals, 4 weights, region, loose, blink, smile, lip, eye, paint, indices). ~11,600 vertices.

The film's cast: `hero10 / hero16 / hero23` (the same person at 1.35 / 1.56 / 1.71 m), friends `frA*` (heavier) and
`frB*` (a girl, bob), crowd `manA, manB, womA, teenC`.

Sources needed (only to build new bodies): `assets/src/mpfb2` = a clone of github.com/makehumancommunity/mpfb2.

---

## 8. Performances — `tools/mocap.py` and a film's `moves.py`

Captured clips are CMU BVH files in `assets/bvh` (github.com/una-dinosauria/cmu-mocap, `data/<subject>/<clip>.bvh`).
Subjects 75, 77, 88, 106, 139, 141, 143 were recorded at half the rate the files say (`HALF`).

```python
ch = Char('hero23'); s = Seq(ch)
s.loop(cycle('16_46', ch), 14.5, speed=1.15)            # a run: one clean stride found in the clip, looped, travelling
s.clip('127_05', 0.1, 2.7, blend=0.25)                  # a one-off action (a stop), crossfaded in
s.loop(cycle('16_58', ch), 5.0, turn=-0.85, face=0.0)   # a walk that curves; face = heading to start on
Q, root = s.done()                                      # feet flat when seated, then heel/ball planting with leg IK
Q = wave(ch, Q, root, 8.6, side='r', swings=7, hz=2.3, amp=21, height=0.12)   # an acting layer (see below)
export(ch, 's15_hero', Q, root, curl=dict(l=0.4, r=0.1))                      # 60 Hz → 30 Hz, fingers curled 0–1
gait_report(ch, Q, root, 's15_hero')
```

- Retargeting: limbs by aligning bone frames, spine and feet by orientation; each clip's usual head pitch is brought
  to `look=(neck 8°, head 5°)` down; `lean` straightens or bends the upper body.
- `cycle` searches the clip for the stride (0.5–1.0 s for runs, 0.8–1.7 s for walks) whose two ends match best in
  pose and velocity, and blends its last 10 frames into the frames before its start.
- Feet: a planted heel or ball stays exactly where it landed (drift limited to 15 cm, released with a 0.07 s time
  constant), the leg is solved again, a foot never goes below ground.
- Read the report: **adult run ≈ 180 steps/min, child ≈ 210**, each with air time; a walk 104–128; slide under 1 cm in
  loops. Children need long-stride clips (`16_46`, `16_45`, `127_08` with `lean=-20`) played about 1.2×.
- Clips that worked: runs `16_46 16_45 127_08 35_22 09_05`; jog `16_35 02_03`; walks `16_58 08_01 08_03 02_01 35_28 82_10`
  (tired); stops `127_05 16_33 16_57 143_02`; standing and looking `139_02 40_10 137_41`; turn round `36_02`;
  joy `142_09`; sit on a low seat `13_05` (seated from 3.4 s); start to run `127_04`.

### Acting layers (second cut) — laid over a finished performance

**`wave(ch, Q, root, t0, side, swings, hz, amp, height, big, towards)`** — a wave as people wave (the first cut's
wave was a raw capture and read as stiff):
1. The body gives first: chest tips 3° away from the raised arm, the shoulder rides up 9–18°, the head tips 5.5°
   towards the hand.
2. The **elbow leads**: its weight starts at 0 s, the hand's 0.13 s later; on the way up the hand hangs 38° from the
   wrist and trails.
3. Upper arm out to the side and 28° forward; 34° below level for a modest wave (`height` 0), 28° above for a child's
   big wave (`height` 0.9).
4. The **forearm swings from the elbow like a wiper** — never the whole arm from the shoulder — ±21° at 2.3 Hz for an
   adult, ±31° at 2.9 Hz for a child; the swings die down (`exp(-t / 1.15·T)`); the elbow answers 1.8 cm.
5. The **wrist follows 1.25 rad late** with 85 % of the swing; the palm faces the person waved at.
6. Lowering: the elbow drops first, the hand comes down last and hangs again.
Export with the waving hand open (`curl` ≈ 0.05–0.1) and the other relaxed (0.45).

**`hold_pad(ch, Q, root, t0, t1, seed, lively)`** — sitting and playing: both hands hold a controller 30 % of body
size before the hips, elbows out and down; the trunk leans in 11° and rolls ±6.5° with the turns while the neck
counters so the eyes stay on the screen; thumbs twitch at 3 Hz; now and then both arms jerk up 3.5 cm.
**`cheer(ch, Q, root, t0)`** — both fists up, pumped at 3.2 Hz, chest back 7°.
**`limb_ik`**, **`hand_aim`**, **`turn_subtree`** are the pieces these are made of (an arm never straightens past 98.5 %).

`data/marks.json` holds marked moments (`marks=dict(seat=4.0)` → position, height and heading at 4.0 s) for `K.placeBy`.

---

## 9. The camera — what was used, with numbers

No shot is a plain zoom. Each move has a reason in the story.

| shot | move | numbers |
|---|---|---|
| s01 | rack focus from a foreground stone to the runner, then a crane from grass height to behind him | focus 1.9 m → runner, dof 11 → 3.5, fov 31 → 36, lens rises 0.66 → 3.1 m along a 5-point path |
| s02 | travel beside the runners, then swing in front of them | lens 7.2 → 2.7 m from the road, fov 30 → 37 |
| s03 | straight down from 17.5 m, rolling over into a low chase, then up into the kites | `up` vector turned from across to vertical over 4 s |
| s04 | puddle height (0.38 m) as they run past the lens | grass kept out between lens and road |
| s05 | crane lifts as the friends wave and take the other road | 1.45 → 5.4 m |
| s06 | a long lens far down the road into a huge low sun | fov 5.2 → 4.6, lens 72 m away, dof 60 |
| s07 | one wide still frame in fog | 6.6 m up, fov 33 |
| s08 | over the shoulder; focus goes to the hoarding and comes back | focus 2.5 → 12.4 → 2.6 m, dof 10 |
| s09 | a full half-orbit round him that ends on the city reveal | radius 4.3 m, 8 s, mood blue → pink |
| s10 | dolly-zoom while the gate itself recedes | lens 3 → 11 m back, fov = 2·atan(tan 29° · 3/d) |
| s11 | long crane in to a seated figure, fade to night | fov 36 → 31 |
| s12 | match-framed day → night; the sky hurries, he only sits | clouds ×14 speed |
| m1 | the lens leaves him and sinks to a puddle; a warm light flickers in it | 0.98 → 0.20 m, focus 4.1 → 1.35 m, fade to warm white |
| m2 | **one turn round the edge of the same bed, and ten years pass**: the screen whites out twice and the people on the bed are older | orbit from -0.14π to 2π in 22 s, radius 1.95 → 1.7 m, height 1.28 → 0.86 m in front of the faces |
| s13 | crane up over the road as everybody comes | 1.3 → 11.5 m |
| s14 | three set-ups at the gate: his face, the clock, the boom going up | fov 27 / 38 / 42 |
| s15 | **the goodbye in four set-ups**: long lens over his shoulder closing on the far boy (fov 34 → 13.5); low behind the boy's waving hand with the neon gate far off, focus going from boy to man; his face with the hand still in the air; a long lens from behind him as the boy walks home and the old board goes dark | focus 1.9 → 38 m, dof 22 |
| s16 | crane over the crowd through the gate | 1.15 → 21 m |
| s16b | the lens walks backwards before them, then climbs as night turns to first light and the sign comes on letter by letter | 0.95 → 15.5 m, mood neon → vice over 7 s |
| s17 | rack focus from the new stone to him and back | focus 1.9 → 5.6 → 1.9 |

Rules: aim at `anc()` (the smoothed body), ease every move with `sm2`, keep the lens ≥ 45 cm from any body, stay on
one side of the line between two people, and measure steadiness afterwards (`qa.py`: the first cut's drift was a
median 0.06–0.4 px a frame at 2K).

---

## 10. Sound — a film's `audio.py`

Everything is synthesised with numpy at 44.1 kHz: Karplus-Strong plucks (`pluck`), detuned saw pads through a low-pass
(`pad`), sine+saw bass, kick, snare, clap, hats, toms; a 13-note motif in D (`MOT`) that returns as plucks, an octave
up, slowed, and as a music box for the memories; chords D–Bm–G–A, one bar = 14/6 s. The world: wind, birds, rain,
crickets, a crowd's murmur, thunder, the gate's motor and bell, countdown beeps, cheers, firework booms with crackle,
and **footsteps placed on the real foot strikes** read from each performance (`strikes`: the ball of the foot crossing
4.5 % of body size on the way down). A short noise reverb on music and effects; high-pass 28 Hz; soft limit;
`cine.render` then normalises to **-14 LUFS, true peak -1.5 dB**.

---

## 11. Checks, and mistakes already paid for

**Before a long render**
- A full-size frame of every face under its mood; every low-angle shot; every contact.
- The first finished piece at full size.

**After** — `python3 tools/qa.py film.mp4`: frames, flashes away from cuts, camera jerk per shot, black frames,
joins between pieces (ratio of change across a join to the change around it; above 1.8 is suspicious).

**Lessons**
- A reused variable name turned the skull centre into an integer: every head's normals pointed one way (white faces
  under the rim, dark under the key). Found by switching rim, key and bloom off one at a time on a crop.
- Far cards drawn without the depth test are painted after the bodies and cut heads off at the horizon.
- An S-curve on values above 1 goes negative: a blue hole in the brightest glow. Clamp first.
- Captured performers carry the head 4° to 47° down in plain walks: bring each clip's usual gaze to ~5° down.
- The seated capture has the feet on a stool's step: when the hips are low, put both feet flat by leg IK.
- After a stop, wait or wave the heading has drifted: give the next walk its heading (`face=0`), or the walker leaves
  the road. A performance that starts with a one-off action is turned to face ahead at its first frame.
- One browser drawing a whole shot stalls (memory grows ~14 MB a frame): draw five-second pieces, a fresh window each.
- Focus blur made only by raising the mip level shows blocks at 2K: 3–4 taps at a lower mip, card edges faded.
- Grass tufts between a low lens and the actors become stair-stepped blades: keep them out (`grassNearMin`).
- A frame server that keeps a job whose encoder died blocks that piece for ever: reset the job on a piece's first frame.
- A wave taken raw from a capture read as "not how people wave": build it (elbow leads, forearm wipes from the elbow,
  wrist late, swings die down) and look at a 15-frame strip.
- A glow card that stands between the lens and a body washes the whole frame out: lower it for that set-up.
- A lens that looks back along a street sees only what has been built behind the walkers: start them far enough in.
- `from mocap import *` does not bring names that start with `_`.
- Chats refuse very large files: make the deliverable under ~350 MB (`maxMB`) and keep the master on the PC.

## CodeScape, the motion library, the deep review and Cine Studio (26 Nov 2026)

**CodeScape films** (stylised real-time 3D, every model and motion written in code) are Cine films whose film.js begins with
`// @engine codescape`. The app then supplies `engine.js`, `kit.js`, `rig.js`, `people.js`, `face2.js`, `sets.js`, `home.js` and
`motion.js` (film/cine/web/codescape); a film may still carry its own newer copy of any of them. Any other engine folder under
`web/` is chosen the same way (`// @engine clay`).

**motion.js — what every film must use** (each rule was paid for with a rejected film):
- *Hands hold things.* `refineHands(p)` gives a person real hands (shaped palm, tapered fingers, three-bone thumb).
  `pinchSheet(p, side, sheet, { at, into, flex, th, open })` holds a letter, envelope, card or note: place the sheet first, then the
  hand takes it — thumb on its front, finger pads solved onto its back — so a hand can never float, slide or pass through.
  `flex 0` is a flat hold (forearm in the sheet's plane), `flex 1` a curled pinch (forearm square to it); avoid 0.45–0.75 (the thumb
  cannot oppose). It returns `{ ratio, bend, gap, far }`: note it in `c.rep[name] = { ...r, t }` and the review checks it.
  Give the elbow's `pole` in the BODY's frame (out to that side, down, a little back), not in world axes.
  To let go: ramp `open` 0→1, then `snap` the pose and `blendHand` to the next one through a `via` point that slides the fingers
  out the way they came. To take: the reverse (pre-shape while travelling, stop 4–5 cm short, close, then move the thing).
- *Staging a hand action.* The camera sees the forearm side-on or three-quarter, never end-on (an arm that points at the lens is a
  blob). A grip on a top edge is filmed LOW and across the hand (lens at the height of the thing, well to one side): from above and
  in front the lens sees only the back of the hand, and any pinch reads as a fist. In a pinch, `rest: 1` leaves the ring and little
  finger open (a hand, not a ball); keep `rest: 0` only where they would touch a neighbouring thing. The thing held stays readable. Nothing passes over a flame. Travel takes a quarter of the time; contact and the action half.
- *Heads talk, cameras do not.* `speechPlan(words)` + `speechHead(plan, T)` + `nodHead(p, sp)`: a small lift before a phrase, a nod
  on its weightiest word, a settle after, a brow flash. Never a sine of the loudness.
  `steady(shot, times, sample)` + `anchorAt(c, name, t)`: the lens aims at REST positions measured at fixed moments. A lens aimed at
  live eyes (or at a trembling prop) shakes the whole picture with every word: 18–28 px in the film that taught this.
- *Faces act.* `blinks(T, [...])` on changes of thought, `saccade` for eyes that search a face, `tremble` for a lip, `breathCatch`
  before a hard line. Eyes lead a head turn by ~0.2 s, covered by a blink.

**The deep review** — `cine.review {film}` (seconds, nothing is drawn; `node tools/review/run.mjs <film dir>` in a workspace):
camera shake per shot (limit 3 px at 720×1280: what a smooth local curve does not explain), the nearest body to the lens (12 cm),
and every hold in `c.rep` (reach ≤ 92 % of the arm, wrist ≤ 60°, pads within 2 mm, thumb long enough). Run it after EVERY change,
before looking at a frame; then look at full-size frames. A film is not rendered until it says `REVIEW: clean`.

**Cine Studio** — `cine.studio {film?}` (or the Cine Studio button on the Code page): a window where the film plays live on the
graphics card with its sound, the code of the current shot beside it; any change to the film's files reloads it by itself, so the
owner watches the work as it is written. Tabs: Shots, Library (every module the film can use and what each offers — click to read
the code). Check film runs the review in the window. A render that is running is shown as it is drawn. Film "Library"
(`@library`) is the CodeScape library itself, live: the people on a turntable, the holds, placing, speech.

## The body, captured motion and editing in Cine Studio (27 Nov 2026)

**body.js — the whole body** (web/codescape; a CodeScape film imports it like motion.js). Every function here keeps three rules that
the review now measures: a planted foot does not move, a knee never locks, and the weight is over the standing foot before the
other one lifts.
- `stand(p, x, z, yaw, t, { weight })` — a person at rest, alive. `weight` −1 … 1 puts them on one leg (the pelvis goes over that
  foot, the free hip drops, the free heel eases up); `idleWeight(t, seed)` changes the leg every 5–9 s. `restArm(p, s, t)` for a
  hanging arm.
- `planWalk(points, { size, speed, step, t0, face })` → a plan: footfalls in time along a path, from a stand to a stand (a first
  short step, full strides, a closing step). `planTurn([x, z], yaw0, yaw1, { size, t0 })` → a turn on the spot, stepped round: the
  foot on the side of the turn opens first; a quarter turn is two steps, a half turn four. `chain(a, b)`. A plan's `footfalls`
  (`{ t, s, p }`) time the footstep sounds.
- `stepper(p, plan, t, { look, arms })` — the body at time t of a plan: heel first, the foot rolls flat, the heel peels up before
  it leaves; the pelvis is midway between the feet at each heel strike and rides over the standing leg between; the chest turns
  against the hips; each arm swings against its own leg, a little late. Before and after the plan it is exactly `stand`, so there
  is no pop. It returns `ground` (the body's place along its path without the bob): aim a following lens at THAT.
- `sit(p, { x, z, yaw, h }, k, t, { up })` — sitting down (k 0 → 1): the hips go back as the chest comes forward, the hands come to
  the thighs. With `up`: the chest goes forward first (nose over toes), then the hips leave the seat. The feet never move.
- `gesture(p, s, kind, k, t, { hit, at })` — a hand that talks, risen by k from the arm at rest: `beat` (marks a word; `hit` drops
  it on the stress), `offer` (an open palm held out), `chest` ("I", "mine"), `point` (`at`: a world point), `stop`.
  `namaste(p, k, t)`.
- `passSheet(giver, gs, taker, ts, sheet, k, { give, take })` — a hand-over. The FILM moves the sheet; this does both hands: she
  holds it, his hand comes in already open to its own edge and closes, both hold for a beat, her fingers open, her hand goes back.
  Stage it side-on, each person holding with the FAR hand, and move the sheet in the plane of those two shoulders: then each
  forearm runs straight to it (with the sheet on the centre line the wrists bend 75°).
- Numbers that matter: a leg at 98.5 % of its length already has its knee bent 20° (a crouch); straight to the eye is 99.6–99.7 %.
  Hips that sink 7 cm a step mean the pelvis is behind the middle of the feet at the heel strike, not that the knees need bending.

**The review measures bodies too** (every person in `c`, on the rig itself): a foot flat on the floor that moves more than 6 mm in
a frame ("slides"), a leg at its whole length (the foot is out of reach), and — for body.js poses — weight standing more than
13 cm off its own feet.

**Captured motion** — `cine.mocap {film, video, name?, from?, to?}` (or "Capture motion…" in Cine Studio): an ordinary video of a
person (no suit, no markers) becomes `<name>.json` in the film's folder. It runs INSIDE the app, with no Python: the video is copied
small at a steady 30 frames a second (any phone or camera format, turned upright), and a page (`web/mocap.html`, MediaPipe's pose
model on the graphics card) finds the body in every frame. The pose model (30 MB) is fetched once into the shared assets.
(`tools/mocap/video2clip.py` does the same with Python + mediapipe, for a workspace; `node tools/cli.cjs mocap <film> <video>` runs
the app's own way with a headless Chrome.) In the film:
`c.clip = clipOf(await (await fetch(new URL('take.json', import.meta.url))).json())` in build, `playClip(p, c.clip, t, { at, yaw,
fist })` in frame. Only DIRECTIONS are taken from the performer (their bones are not the rig's): each limb points as theirs did, on
the rig's own lengths; the palm comes from wrist → index and wrist → little finger; fingers are not seen by a body-pose capture
(`fist` 0 … 1 sets them). Legs the camera did not see are not guessed: the rig stands and its hips turn over planted feet. Film the
whole body, feet in the picture, for the legs. The clip is on the spot (the hips are the origin of every frame): to travel, use a
plan for the legs and `playClip(..., { legs: false })` for the rest.

**Editing in Cine Studio** (the window is the film's own workbench now):
- *Edit* turns the code panel into an editor; *Save* (Ctrl+S) writes the file and the picture reloads with it. Every save first
  keeps the old file in `<film>/.studio/` (the newest 20); *Undo save* puts the last one back.
- *Live numbers*: a film that has `export const TUNE = { name: number, … }` and reads its numbers from it gets a slider for each,
  under the picture; moving one redraws at once; *Write to film.js* puts the values into the code.
- *Inspect* (I): drag on the picture to go round what the shot is looking at, wheel to move in and out, double-click to come
  back: for looking at a hand or a pose from every side without changing the shot.
- *Save frame* keeps the picture on screen as a PNG in the film's `out/`. *Render…* starts a 720p preview or the full-size render
  on the graphics card from the window (and Stop).
- *Library* rows have *copy import*. *Capture motion…* is the motion capture above.
- The Library film (`@library`) now also has: walk, turn, sit, gesture, give, mocap. It cannot be changed from the window: copy
  what you need into a film.
