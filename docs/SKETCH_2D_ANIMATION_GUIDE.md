# Animate prepared sketches in Video studio

This guide covers the prepared-sketch animation workspace added in 4.4.0. It uses
your Sketch Studio artwork, articulated joints, pose keys, foot targets, camera
keys and replacement cels. Baking creates a separate, editable Sketch Studio
animation; your prepared drawing remains available.

For ChatGPT commands and safe retries, see the
[agent guide](SKETCH_2D_ANIMATION_CHATGPT_GUIDE.md).

## Prepare artwork that can articulate

Start with a coloured, single-frame preparation drawing in Sketch Studio. Separate the parts that need to
move: torso, head, upper arms, forearms, hands, thighs, lower legs, feet, hair,
clothing and held props. Name the layers and objects clearly. Leave transparency
around each part and overlap the shapes at shoulders, elbows, hips and knees so
small rotations do not open visible gaps. Keep the background separate.

Rigged artwork must be on layers without spatial layer transforms or layer
transform keys. Move/rotate the objects themselves when preparing the drawing,
or prepare a separate clean layer. The compiler rejects unsupported transformed
rig layers rather than reporting contacts in a different coordinate space.

A single photograph or flattened drawing does not contain the hidden arm, body
surface or scenery needed for a new pose. Redraw those areas before animating.
Importing an image is not the same as drawing it, and a rig does not reconstruct
unseen views. Use a separately drawn angle for a reverse shot or a first-person
view.

Keep a clean preparation sketch. Save an animation from that sketch when its
parts are ready. The animation saves a frozen source snapshot: later edits to
the preparation sketch do not silently change an already saved animation.

## Build a short action

Open your prepared drawing, then choose **More → 2D animation**. Work on a short
shot first, before extending it into a sequence.

1. Use **Timing** to set the frame count and frame rate. At 24 FPS, 100 frames last
   about 4.17 seconds. Frames in the animation data start at zero. Static parts
   from a single-frame preparation drawing are held across the new animation;
   an existing multi-frame source keeps its explicit cel exposure ranges.
2. In **Rig mode**, drag the skeleton's joints over the character. Use
   **Rig / bind → Joint position / parent** for precise coordinates. A child joint follows its
   parent, so put shoulders below the chest, elbows below shoulders, and hands
   below elbows.
3. Use **Rig / bind → Bind artwork to selected joint** to map each prepared object
   to its controlling joint. The whole forearm usually
   belongs to the elbow joint; the hand and a held prop follow the wrist/hand.
   Check the binding in preview before mapping the next part.
4. Switch to **Pose mode**. Drag an endpoint to rotate its parent joint; drag the
   root to move the character. **Pose tools** also offers numeric rotation/root
   motion and **Save pose key**. Make an anticipation pose, an action/contact
   pose and a recovery pose. Add
   smaller breakdown poses where the silhouette, hand arc or body weight needs
   correction. Choose the interpolation intentionally: eased motion for a settle,
   linear motion for a steady movement, and held changes for a deliberate cut.
5. Use **Foot contact** to add a two-bone IK target over the contact interval. Keep
   the target in the same world position while the body shifts. Check the knee's
   bend direction, lock the foot's world rotation when its sole should stay level,
   and avoid targets beyond the leg's reach: an unreachable
   target is reported as an error rather than silently stretching the leg.
6. Use **Camera** to add keys and choose the affected layers, after the character
   action reads clearly. Camera movement
   changes the framing; it cannot create a new anatomical pose. Use new artwork
   and exposure ranges for a POV or a different view.
7. Add mouth drawings as separate cels. Use **Cels / dialogue → Mouth cel
   range** to time each shape and **Timed dialogue** for the caption.
   **Artwork visibility range** controls replacement hands or other prepared cels. Dialogue text is a visual caption, not generated speech or automatic
   lip synchronization.
8. Preview the full shot, then inspect the contact frames, changes of view and
   mouth changes one frame at a time. Adjust the keys and redraw a cel when a
   pose needs a different silhouette.
9. Choose **Save scene**, then **Bake animation** to create a new sketch when the
   motion is ready. Open that output and use **File → Export video** for the
   existing native export path. **Open** returns to a saved animation scene.

## Combine three kinds of 2D animation

**Frame by frame:** draw a new cel when anatomy, perspective or the silhouette
changes. Onion skin and the exposure sheet help maintain continuity. Hold a cel
for two frames when appropriate; a held drawing is useful timing, not a claim
that every output frame is newly drawn.

**Articulated cutout:** bind an object to one joint. Its shape stays rigid while
the skeleton moves it. This works well for clearly separated limbs and props,
but can reveal seams or stiff movement without suitable artwork and pose keys.

**Weighted vector deformation:** the animation program can assign more than one
joint influence to prepared vector geometry. Its points deform with those joints.
Choose **Rig / bind → Deform vector artwork**, select the object and vertex,
choose two joints and set the blend. **Set vertex** changes that point;
**Linear blend all** prepares a gradient along vertex order. Inspect the orange
vertex marker, refine the result and choose **Save weights**. The ChatGPT/program
interface supports up to four influences per vertex. This is useful for bends
and overlapping shapes; it does not add missing anatomy, paint detail or a new
camera angle. Weighted vectors use flat-colour regions; gradient-filled artwork
uses rigid bindings until a deforming gradient mapping is available.
Separate raster parts can move rigidly; their pixels do not receive
the vector deformation described here.

Use these methods together. Keep a prop rigid, deform a sleeve where suitable,
and replace the face or hand cel for a stronger expression. A whole-picture pan,
zoom or crossfade does not establish articulated character animation.

## Included exercise: Rooftop Relay

Choose **Example** in the animation workspace to create your own editable copy
of the bundled action scene. Press **Play**, inspect a contact frame, change a
pose, then **Save scene → Bake animation**.

The repository also includes this original coloured action exercise: Vale, a courier in
a teal jacket and coral scarf, deflects a scout drone's bolt on a rooftop.
It runs for 100 frames at 24 FPS on a 1280 × 720 canvas.

| Frames | Prepared view and action |
| --- | --- |
| 0–35 | Wide shot: anticipation, planted deflection and follow-through. |
| 36–59 | Separately drawn foreground POV with the courier's hand and baton. |
| 60–99 | Separately drawn medium recovery and settle, with “Not today.” and mouth cels. |

The [prepared vector artwork](../android/app/src/test/resources/sketch-animation/rooftop-relay-source.json),
[animation program](../android/app/src/test/resources/sketch-animation/rooftop-relay-program.json)
and [authorship description](../android/app/src/test/resources/sketch-animation/rooftop-relay-authoring.json)
show how the drawing and motion are kept separate. They contain no inserted
reference photograph. The 100 output frames are generated from authored parts
and pose keys; they are not 100 individually hand-drawn full pictures.

The reproducible `SketchActionFilmTest` uses Sketch Studio's Android Canvas
renderer in the workspace. It writes the frame sequence, contact sheet,
selected proof frames, compiler report and motion landmarks under
`android/app/build/sketch-action-film/`. Those images are workspace render
evidence, not physical-phone screenshots. Inspecting the complete output remains
necessary even when its structural tests pass.

## Judge the result

Watch the action once at full speed without reading the dialogue. Then check:

- The character's silhouette changes through the action, with independent limb
  movement, anticipation, contact and follow-through.
- A planted foot stays planted; a held object stays attached to the correct hand.
- Body parts do not unexpectedly detach, swap order or expose missing artwork.
- The camera cut has suitable artwork, rather than stretching the previous view.
- Mouth shapes and captions occur at the intended frames and remain legible.
- Colours, line weight, character design and background continuity survive the
  transition between cels.

Frame counts, changed pixels, valid JSON, completed exports and automated tests
can establish specific engineering properties. They do not certify good acting,
anatomy, likeness or professional artistic quality. Inspect the actual output.

## Recovery without losing work

Save before baking. A bake writes a fresh output sketch and does not replace
the original. If a save reports that its revision changed, reopen the saved
animation and review the newer work before saving again. Do not discard another
editor's changes by guessing a revision.

If ChatGPT reports only that a command was sent, the native result is still
unconfirmed. Keep its command and operation IDs until the terminal result is
known. The [connection guide](VIDEO_STUDIO_APP_KEY.md) explains the private
app-key route; Gallery access is unnecessary.

For more than one short shot, keep each shot manageable and inspect it before
joining it with the editor. Sketch Studio also has paged sketch books for larger
frame-by-frame projects. A large page count is storage capacity, not a promise
of instant rendering or automatically authored animation.
