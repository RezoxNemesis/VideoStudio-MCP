# The look to reach — VideoStudio's standard for drawn people and places

The owner's standard (reference pictures of 16 Nov 2026: a father's turnaround, face and outfit sheets, father-and-
daughter scenes, painted station and courtyard). It is the look of a hand-drawn anime feature: clean line, flat colour,
one designed shadow, painted backgrounds. Every character sheet and every film is judged against this page — by
looking at frames beside the reference, not by a number taken at one moment. What the engine cannot do yet is marked
**(to build)**; do not call a character finished while its sheet still shows those gaps.

## Line
- One thin ink line, dark brown-black, about 1.5–2.5 px at 1080p, nearly even in weight, round the whole figure.
- Inner lines carry the drawing: collar edges, the button placket, pocket, sleeve hems, belt, two to four creases
  where cloth bends (elbow, armpit, waist, knee, the break over the shoe), the edges of hair locks and a few strand
  lines, the features of the face, the ear, the knuckles and nails. **(to build: crease and seam lines on cloth)**
- No stair-steps, no torn edges: a hem, a hairline and a beard end in a clean drawn edge.

## Colour and shade
- Flat base colour + ONE shadow tone with a designed, hard-edged shape (under the chin, under the collar, inside a
  sleeve, hair on the brow, an arm across the chest, folds). The shadow is the base about a fifth darker and cooler,
  never grey and never a stain (`designAdvice`). A small lighter tone on the lit side: rim light, hair highlights.
- Whites shade to lavender-grey; skin stays warm, with a little blush on cheeks, nose, knuckles.
- Night: blue-violet air, warm light from windows and lamps on one side of every figure, a warm rim on hair.
- The finish (engine/finish.js) seats the figure: light wrap, gradient towards the light, soft glow.

## Hair
- A mass with a clear outline built of 12–20 big locks that end in points; locks curve (C and S), lie in layers,
  some fall over the brow, the ends at ear and nape turn out; two or three loose strands at the crown.
- Inside the mass: few strand lines along the flow, a darker underside, lighter streaks in groups where light falls.
- Built: `hair3.mass` + broad designed locks with outlines (hair v2). **(to build: a library of styles made this way;
  pointed ends that break the outline more; a designed highlight band; the father's own style is still a round cap.)**

## Face (the father: adult Indian man)
- Head one 7.5th of the height. Eyes on the middle line: almond, a strong upper lid line, brown iris darker at the top
  with one light; thick, slightly arched brows; nose by a line down one side, a nostril, a small shadow; mouth by a
  line with a soft shadow under the lower lip; the ear drawn with its inner lines; a defined jaw.
- Beard: short and boxed, joined to the moustache, a dark mass with a soft, slightly ragged drawn edge (density about
  0.6, never a slab). Tears: a clear line of light under the eye and down the cheek.
- Expression changes the brows, lids, mouth AND the cheeks; a reaction is held, then released.
- **(to build: face shapes and feature sets as presets — oval, square, round …; ears; cheek and jaw shading.)**

## Body
- 7.5 heads tall; legs half the height; shoulders about 2.3 heads wide; a hand as long as the face from chin to brow.
- Slim, average, broad, heavy are different silhouettes (belly, chest, neck), not one body scaled.
  **(to build: proportion presets for drawn characters; the Stage body is stockier, with a big trunk and short legs.)**

## Clothes (the father's set)
- Shirt: a pointed collar on a band, top button open, placket with buttons, chest pocket, short sleeves that stand off
  the arm and end in a hem band, 6–8 cm of ease round the body, tucked in with a little cloth over the belt.
  Built: `design.wear.shirt` (engine/wardrobe.js). **(to build: fold and shadow shapes on the cloth, long and rolled sleeves.)**
- Tie: knot under the collar, loosened, blade to the belt, diagonal stripes. Belt: black, a buckle, loops.
- Trousers: straight leg from the thigh (`bottom.fit: 'straight'`), a centre crease, a small break over the shoe, a
  pocket line: built (`design.wear.trousers`, `belt`). Shoes: black derbies with a sole line, laces, a highlight
  **(to build: laces, toe cap line, the highlight)**.
- Every outfit is an asset with its pieces, colours and lines (the wardrobe: casual, shirt, formal, suit, kurta …).
  **(to build: the wardrobe.)**

## Hands and things
- A held thing lies in the hand, fingers wrapped round it, thumb over them (kit/hold.js `prop`); it is one thing for
  a whole film: never drawn loose beside an open hand, never gone in one shot and back in the next.
- Giving: the taker closes on it before the giver opens. Taking from a surface: the hand comes to it, closes, lifts.

## Places
- Painted backgrounds with a clear light: cast and dappled shadows, haze with depth, reflections of lamps on a
  polished floor, wood with grain, metal with a highlight, signs that can be read. People in the background live.
- A figure sits ON a seat and stands ON the ground (`grounded`), with a contact shadow under it.

## How to judge
1. Draw the sheet (whole figure front and side, waist-up, face) in even light; put it beside the reference.
2. Name every gap in words; fix the engine with a rule first (devtests), then the design; draw again.
3. Only then light it for the scene. Keep the sheet with the character.
