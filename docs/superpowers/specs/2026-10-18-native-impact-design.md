# Native two-body impact scene

The owner wants complete scenes that expose and improve VideoStudio's actual
capabilities, with manual/MCP parity and screenshots for every visual test.
The first retained slice is a ball striking a stationary box on one floor axis.

## Approach

Add `add_solid_collision` to `sketch_edit`. Android calculates the contact frame,
post-contact velocities from both masses and restitution, and friction-limited
stopping distances. It creates both solids through the existing native mesh and
cel compiler. Output remains ordinary editable paths, compatible with archives,
undo and the existing renderer. All shadows precede surfaces in the new scene.
The entire scene validates before mutation. No reference media is consumed.

Baking editable cels retains existing project compatibility. A new parametric
render format could reduce storage more, but would require a separate document,
renderer and editor migration. External scene simulation would not satisfy the
owner's normal-ChatGPT requirement. Retain bounded baking for this slice, measure
its cost and leave the existing 4 MiB / 4096-object bounds intact.

## Controls and semantics

Manual: Sketch Studio > Brush / Text > Ball and box impact. MCP calls the same
operation with `sceneId`, `layerId`, and strict `settings`:
`ballDiameter`, `boxSize` (three dimensions), `startX`, `boxX`, `speed`,
`ballMass`, `boxMass`, `restitution`, `friction`, `squash`, and `camera`.
Defaults are native-discoverable. No hidden workspace geometry or motion.

The box starts at rest; initial ball speed is positive; both bodies share depth
zero. Contact time is rounded to the closest representable frame (at least one),
and approach speed adjusts to meet precisely on that frame. The floor applies
constant post-impact deceleration until each body stops; this is a deterministic
one-axis model, not general rigid-body physics. World-axis impact compression
recovers with volume conservation; its contact-side faces remain anchored.
Frames/FPS come from the sketch, never silently replaced. Require 48..240 frames
for this bounded scene; out-of-shot contact or canvas/budget overflow fails clearly.

## Validation and evidence

Native tests cover elastic equal-mass transfer, unequal-mass recoil, contact and
no interpenetration, stopping without velocity reversal, timing at different FPS,
invalid settings, caller atomicity, edit idempotency, undo and preserved artwork.
Manual tests create a real saved scene through the dialog and native worker.
MCP strict contract rejects unknown fields and gates the operation to 4.7.3.

Visual tests use native authoring, native export, archive roundtrip and actual
editor screenshots. FFmpeg may encode unmodified native PNGs only. Inspect at
least a default scene and a materially different mass/restitution case; repair
visible defects and repeat. Source tests and workspace Android execution do not
establish phone execution or live connector deployment. Existing MCP queue
migration remains a separate rollout blocker; do not deploy blindly.

## Visual repair retained
The first native render revealed faceted sphere outlines. Add optional
`meshDetail: standard|fine` to native solids, with a manual checkbox and 4.7.3
MCP gate. Standard geometry stays unchanged; fine spheres use 48 meridians and
16 latitude divisions. Impact scenes use fine spheres automatically. Existing
byte/point budgets still enforce limits; measure the rerender rather than
raising those limits.

Fine spheres consolidate connected same-colour visible mesh faces into directed
boundary contours. Native gradients remain continuous and paths stay editable;
internal face edges are discarded before storage. A full default 240-frame,
60-FPS scene must fit the original point and byte limits. This is measured by
an actual apply/validate regression, not just pose-key generation.
