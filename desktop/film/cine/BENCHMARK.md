# The minimum benchmark for animation (set 27 Nov 2026)

The owner's rule: the full-body motion made on 27 Nov 2026 is a success that must never be lost, and nothing that moves worse may
be delivered again — in any chat, session or model. This file says what that benchmark is, where it lives and how it is used.

## What it is
The body shots of the CodeScape Library film (`films/codescape-library/film.js`), drawn as one 46 s reel ("CodeScape body reel"):

| shot | what it shows | made with (web/codescape) |
|---|---|---|
| `walk` | a walk along a curve, from a stand to a stand; rings mark the footfalls and the feet stay on them | `body.js` planWalk + stepper |
| `turn` | a quarter turn and a half turn on the spot, stepped round | planTurn + stepper |
| `sit` | sitting down on a stool and standing up; the feet never move | sit |
| `gesture` | beat, offer, chest, point, stop, namaste | gesture, namaste |
| `give` | a card handed from one person to another | passSheet + `motion.js` pinchSheet |
| `mocap` | the owner's own filmed take on the rig | clipOf + playClip (cine.mocap) |
| `hold`, `place`, `talk` | hands that hold and place; a head that rides its words under a steady lens | `motion.js` |

## The bar (measured; `cine.review` / `node tools/review/run.mjs <film dir>` must say `REVIEW: clean`)
- Camera: no more than 3 px of shake at 720×1280. Lens: no body nearer than 12 cm.
- Holds: reach ≤ 92 % of the arm, wrist ≤ 60°, pads on the surface (≤ 2 mm), the thumb reaches.
- Bodies: a foot flat on the floor moves ≤ 6 mm a frame (benchmark: 0–5 mm), no leg at its whole length (benchmark: 99.7 %),
  weight within 13 cm of its feet when standing.
- Meshes: every mesh whole — each per-point list (normals, uv) as long as its points, every index inside them, every number finite.
- The finished render: `cine.progress` must report `picture: { blackBlocks: [] }` (the app looks every render over for a solid black block).
- The benchmark's own numbers: walk — hips ride 5.6 cm, speed changes ≤ 0.11 m/s a frame; hand-over — reach 77–78 %, wrists 49–53°.

## How it is used
1. Before any animation: open the Library film (`cine.studio {film: "@library"}`), read GUIDE.md and the lessons.
2. Whole-body motion comes from `body.js`, hands from `motion.js`. A new kind of motion is ADDED to them, with a Library shot.
3. Review until clean, then look at full-size frames from the side with the feet in the picture. FULL size means the size it will be rendered at:
   a fault one pixel wide (a point with no normal) is missed by a 360 px check sheet and blacks out half the picture at 720 px.
4. Put the new shot beside the benchmark reel. If it moves worse, it is not sent.
5. Every new success goes into the Library film and this file: the bar only rises.

`tests/cinelibrary.test.ts` runs the review on the Library film in every build, so the benchmark cannot silently break.
`tests/cinemesh.test.ts` keeps the whole-mesh check and the render look-over working.

## A fault that got as far as a render, and what now stops it (27 Nov 2026)
The new trouser seat got a closed underside: one point and 48 triangles were ADDED to a mesh that already had normals.
`computeVertexNormals()` keeps an existing normal list, so the new point had no normal. On the GPU that point draws as a pixel that
is not a number, the bloom blurs it, and half the picture is a black block — in one shot, for two seconds, only at full size.
The 360 px check sheets were clean. It was caught by looking at the PC's own render before sending it. Now:
1. `people.js`: `deleteAttribute('normal')` before the normals are made again (the rule for ANY mesh that grows).
2. The review names a mesh that is not whole (`a mesh is not whole … its normal list has 833 entries for 834 points`).
3. Both engines put out a pixel that is not a number before the bloom, so one bad pixel stays one pixel.
4. The app looks over every finished render and reports `picture.blackBlocks`; Cine Studio shows it in red.
5. The rule stays: the PC's render is looked at (a strip of frames from every shot) before anything is sent.

## The body MODEL, reworked the same day (the owner, 27 Nov 2026: "hands, fingers, waist side, butt and front, and other joints")
The motion was the success; the model was pieces: balls and cylinders that cut through each other. The rule that fixed every part is
the same one: **a part of the body is ONE skin, drawn through its joints and remade before every draw; it begins INSIDE the part it
grows from.** A ball at a joint, or two pieces meeting on the surface, always shows.
- `people.js` trousersSon: the seat and waist carry the hips (as wide as the thighs where they begin), curve under into the crotch
  (no flap in front, no shelf under the seat) and go WITH the thighs below the hips (sitting, nothing hangs under the legs); each leg
  is one cloth tube from inside the seat through hip, knee and cuff; a belt with loops and a framed buckle; pocket lines on the seat.
- `people.js` shirtSon: the shirt is one surface from inside the waistband to under the collar, each ring going with the hips, the
  waist or the chest (a lean is a bend; the cloth at the belt stays in the trousers); shoulders slope from the neck (joints 2 cm
  lower) and take the sleeve into themselves; a placket and buttons to the belt; a collar band; the lanyard laid on the cloth.
- `people.js` sleevesSon: the sleeve begins deep inside the body and makes the elbow's point itself.
- `motion.js` armSkin (every person with refined hands): the arm's skin is one surface from under the sleeve through the elbow to
  the palm: fullest below the elbow, flat and narrow at the wrist, the turn of the hand spread down the forearm, the last rings
  bending with the hand. A watch fits the wrist where it is; bangles hang from the arm and slide when the hand is raised.
- `motion.js` fingerSkin: each finger and the thumb is one skin through its joints: a knuckle is a swelling on the back that grows
  as the finger bends, the inside creases, the tip is round with a fuller pad, the nail is a nail's size.
How to look at a body: a lab shot with one pose and one close camera per second (shoulder, elbow, wrist, hand both sides, waist
all round, sitting, mid-stride), drawn at 540 px or more per view. A whole-figure frame hides every one of these faults.
Still open: faces, Maa's body under the saree and her shoulders, the voices' delivery.
