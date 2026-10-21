# Native impact scenes — 4.7.3

The owner requires animation quality and controls to live in VideoStudio so both
manual users and ordinary ChatGPT/MCP can reproduce the workspace tests. Android
now creates a complete ball/box scene from small numeric settings. The workspace
only runs the native app code, inspects evidence and packages its PNGs into MP4.
No reference footage, images, traced motion or generated artwork is used.

## Retained app behavior

- `add_solid_collision` constructs both actors atomically as editable vector cels.
- Native contact-frame calculation, mass/restitution response, constant stopping
  friction, distance-linked rolling and world-axis volume-conserving compression.
- Exact quadratic deceleration encoded in Bezier timing between sparse pose keys;
  this removes the small velocity steps found during the smoothness follow-up.
- Fine spheres: 48 meridians / 16 latitude divisions. `add_solid` also accepts
  `meshDetail: "fine"`; manual Solid construction has a Fine sphere mesh checkbox.
- Fine sphere colour regions store their boundary contours, discarding internal
  mesh edges. Existing standard mesh output and document limits stay unchanged.
- All scene shadows draw before actor surfaces; no duplicate floor grid.
- Manual work uses the existing background compiler, page/session guards,
  revision admission and normal sketch undo/archive/export paths.
- App version 4.7.3 / code476; package, signer and device identity unchanged.

This is a one-axis impact model on a common floor, with a stationary axis-aligned
box and no angular collision response. It is not general rigid-body physics,
character animation or film-quality acceptance. Bigger scene storage remains a
separate architectural task.

## Reproduce manually

Create a 1280×720 sketch with 120 frames / 30 FPS (or 240 / 60 FPS).
Open **Brush / Text > Ball and box impact**. Set the fields and Construct scene.
The tool preserves existing layers, FPS and frame count, and adds a new layer.
Paths, palette colours, exposures and timing remain editable afterward.

The field values are native discoverable at `sketch_capabilities.solidCollision`.
Lengths/positions are world pixels, speed world pixels/second, friction world
pixels/second², masses relative, and restitution 0..1. The requested speed adjusts
slightly so contact occurs on an exact sketch frame. Compression recovers in
0.24 seconds. Contact must leave eight recovery frames; use 48..240 frames per
scene. Existing 4 MiB, 131072-point, 4096-object and 128-palette-style bounds apply.
Out-of-canvas or oversized scenes fail without replacing the drawing.

## Reproduce through native sketch/MCP surfaces

Use `app_status` first when the actual connector is available. Read the native
capabilities; require APK >=4.7.3 and a matching worker schema before editing.
Create a fresh sketch using the supplied `studyCanvas`, begin an original-strokes
live session, and call `sketch_edit` with a stable operation ID and expected
revision. This operation needs no source assets:

```json
{
  "type": "add_solid_collision",
  "layerId": "layer_1",
  "sceneId": "impact_01",
  "settings": {}
}
```

Empty settings selects the app defaults. Optional settings: ballDiameter,
boxSize (width/height/depth), startX, boxX, speed, ballMass, boxMass, restitution,
friction, squash and the shared camera (focal/distance/height/horizon).
End the live session, inspect the native preview and request native rendering.
Reuse operation IDs only when retrying the identical request. No gallery access.

## Six final original tests

All clips are four seconds at 1280×720, silent. Values below override defaults;
use the same manual fields or native settings object.

| Test | Clock | Settings overrides | Editable document bytes |
|---|---|---|---:|
| Heavy-box rebound | 120 / 30 FPS | none | 922653 |
| Equal-mass transfer | 120 / 30 FPS | ballMass1, boxMass1, restitution1 | 905220 |
| Higher frame rate | 240 / 60 FPS | none | 1814316 |
| Sustained contact | 120 / 30 FPS | ballMass3, boxMass1, restitution0, friction160 | 1098734 |
| Fast impact | 120 / 30 FPS | ballDiameter180, startX-460, boxX90, speed700, boxMass6, restitution0.85, friction650, squash0.3 | 525297 |
| Different proportions | 120 / 30 FPS | ballDiameter90, boxSize[100,270,80], boxMass2, speed300, friction140, restitution0.5 | 732296 |

Final evidence is private at
`/workspace/engineering-review/native-impact-20261018/*-v4`.
Native export hashes were checked for all840 final frames; each editable archive
was restored with seven identical native pixel comparisons (42 total). Actual
SketchStudioActivity screenshots accompany each scene. Earlier generations are
retained, including the visibly faceted first render.

The fine rebound scene decreased from2706193bytes to922483bytes in the contour
repair, before the final deceleration change (922653bytes). A real 60-FPS full
scene test first reproduced point-budget failure, then passed after consolidation.
Sparse-key deceleration first reproduced a sampled position error, then matched
the native analytical model to1e-6 world pixels across tested frames.

## Integration and evidence boundary

Manual and native-facade execution is tested in workspace Robolectric with native
Android Canvas. FFmpeg only encodes the unmodified native PNG sequence; it does
not draw geometry, calculate motion, compose scenes or synthesize sound.

Live ChatGPT connector execution and phone installation/playback are unverified.
Do not deploy this worker branch blindly: the known deployed main queue may hold
legacy archived payload pointers requiring compatibility work with this branch's
transactional queue store. No worker deployment or stable release is made here.
The signed candidate must match the original certificate and exact source SHA.

Review identified an important 60-FPS capacity gap; the new full-apply regression
reproduced it and verifies the repair. Remaining narrower coverage suggestions:
existing-artwork preservation test starts empty; failed persistent scene+history
with artwork is not exercised together; additional restitution extremes/derived
frame velocities could be expanded; the fine checkbox emission test selects a
box while sphere geometry is covered separately.

Next animation work: multi-contact sequences, angular weight/follow-through and
camera choreography, then articulated character motion and acting. Those remain
future capabilities and must follow the same native author/inspect/repair loop.
