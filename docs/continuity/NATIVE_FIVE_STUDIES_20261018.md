# Five original native motion studies — 4.7.2

The owner requested at least five new capability tests: improve the existing
cube, then test four different objects. All retained geometry, motion rules and
presets run in Android. Workspace code drives the real native authoring/export
facades and inspects output; it does not supply generated artwork or geometry.

## Retained native improvements

| Study | Capability exercised and added |
| --- | --- |
| Cube bounce | Parabolic flight, independent squash timing, world-axis squash after flips, interpolated volume preservation |
| Rolling ball | Shared sphere mesh, radius/distance-driven roll, clean silhouette, continuous native material gradients |
| Rolling cylinder | Circular caps, visible rim creases, orientation marker, floor locking, soft gradient contact shadows |
| Spinning cone | Tapered mesh, stripe/marker materials, decaying wobble, fixed world light on faces |
| Rocking pyramid | Triangular surfaces, anchored active foot, decaying rocking, final-pose holds and shared held cels |

The combined retest exposed visibly faceted sphere lighting. It was replaced
with continuous native radial material shading and retested. Hard faces retain
three lighting bands. Artistic acceptance remains a separate judgment.

All output uses the existing editable path, palette, gradient, exposure, undo and
archive formats. There is no new raster asset dependency or document kind.
Existing 4.7.1 programs retain their behavior when extension fields are absent.

## Use through ordinary ChatGPT and MCP

Both a compatible installed APK and a compatible server tool schema are needed.
Workspace native execution does not establish execution by the ChatGPT host or
installation on a phone. Never replace the device-owned endpoint or credentials.

1. Call `app_status` first. Confirm native version 4.7.2 or newer and the actual
   executor/queue state. A queued action is not completed work.
2. Read `sketch_capabilities.solidConstruction`. Its `studyCanvas` specifies
   1280 × 720, 180 frames and 30 FPS. `studies` contains the five original native
   programs under `box`, `sphere`, `cylinder`, `cone` and `pyramid`.
3. Create a fresh sketch using those canvas settings, a stable sketch ID and
   operation ID. Preserve existing artwork. Begin a live drawing session if
   actual ordered construction should be displayed; retain its session ID.
4. Send `sketch_edit` with the actual current revision and an `add_solid`
   operation: `layerId`, a unique `solidId`, and the chosen native study program.
   Customize dimensions, camera, materials and pose keys through the same API.
5. Read the native completion receipt and revision. Inspect `sketch_preview`,
   then modify and retest when needed. End the live session with its session ID.
6. Use `sketch_render` for native app export. Check actual job completion and
   published result; a source test or encoder success does not prove quality.

No cloud frame construction script is required for this workflow. The native
study programs are small structured requests; Android expands their geometry.
The server rejects extension fields before queuing them to older APKs.

## Manual parity

Open **Sketch Studio → Brush / Text → Solid construction**. Choose a shape,
then **Load motion study**. The preset replaces geometry/motion settings while
preserving the selected sketch frame count and FPS. Set 180 frames and 30 FPS
explicitly for the six-second test timing. Edit pose keys or camera settings,
then construct a new editable layer. Timing changes apply to the current sketch.

The same form exposes world-axis squash, volume preservation, rolling radius,
ground lock, lighting, soft shadows, foot pivots and held-cel sharing. Pose frames
remain absolute; changing duration does not silently stretch authored keys.

## Motion semantics and limits

- `flightHeight` adds `4 × height × t × (1-t)` to a segment's authored centre
  and clearance channels. This is authored motion, not rigid-body simulation.
- `scaleInterpolation` is independent of position/rotation timing.
  `scaleSpace: world` keeps squash vertical after turns. `preserveVolume`
  corrects intermediate width/depth to the interpolated key volumes.
- `rollingRadius` derives extra Z roll from world-X travel. Choose the radius
  and pose orientation deliberately; this is a motion constraint, not a general
  friction/contact solver.
- `groundLock` rejects elevation/flight keys and keeps the lowest mesh vertex
  on the floor. `rockingPivot` additionally anchors the active foot to its neutral
  X position; it supports grounded boxes/pyramids without distance rolling.
- `holdLast` extends the final pose to the sketch end. `mergeHolds` shares exact
  consecutive sampled poses using exposure. A 1,200-frame held-pose test verifies
  this storage behavior; it is not proof of 1,200 distinct moving drawings.
- Each sketch remains bounded to 64 solid keys, 1,200 frames, 4,096 objects,
  131,072 points and 4 MiB. Curved baked cels consume more space. The complete
  candidate is validated before storage mutation; use separate shots/books for
  longer work. These studies do not certify an hour-long film pipeline.

## Evidence boundaries

Five separate six-second studies were exported through native Android Canvas in
Robolectric, with actual editor screenshots, native frame hashes and pixel checks
after editable-archive restoration. Private evidence is outside this public repo.
The silent MP4s package those native PNGs using workspace FFmpeg. Phone export
uses the app's encoder and requires its own execution check. Reference frames,
reference motion, backgrounds and audio were not reused.

Native/manual regressions, strict MCP schema/version tests, full suites and APK
identity checks accompany the change. Keep exact run results with the private
evidence. Do not infer phone installation, live connector rollout, sleep/reconnect
success, film quality or character animation acceptance from those checks.
