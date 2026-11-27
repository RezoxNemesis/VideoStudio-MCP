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
- The benchmark's own numbers: walk — hips ride 5.6 cm, speed changes ≤ 0.11 m/s a frame; hand-over — reach 77–78 %, wrists 49–53°.

## How it is used
1. Before any animation: open the Library film (`cine.studio {film: "@library"}`), read GUIDE.md and the lessons.
2. Whole-body motion comes from `body.js`, hands from `motion.js`. A new kind of motion is ADDED to them, with a Library shot.
3. Review until clean, then look at full-size frames from the side with the feet in the picture.
4. Put the new shot beside the benchmark reel. If it moves worse, it is not sent.
5. Every new success goes into the Library film and this file: the bar only rises.

`tests/cinelibrary.test.ts` runs the review on the Library film in every build, so the benchmark cannot silently break.

## What the benchmark is NOT yet (the owner, 27 Nov 2026)
The motion is the success. The owner then asked for the body MODEL to be reworked (hands and fingers, the waist from the side,
seat and front, the other joints) and said the voices' delivery is not yet natural. First answer, the same day: `people.js`
trousersSon (a seat and waist, a belt, one cloth tube per leg through hip, knee and cuff) and fuller hands with knuckles in
`motion.js` refineHands. Not yet judged by the owner; faces, Maa's body under the saree, shoulders and the voices are still open.
