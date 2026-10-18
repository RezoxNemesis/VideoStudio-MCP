# Native solid animation capability — 2026-10-18

The owner requires original capability tests through VideoStudio, followed by
independent inspection, repository repairs and retests. Reference clips are
benchmarks, never frame/motion data to present as independently authored output.
The earlier traced cube clip is not an accepted capability test.

## Retained product workflow

Native 4.7.1 (code 474) adds `add_solid` to the existing `sketch_edit` operation
pipeline. Eight shared model corners produce perspective-correct, culled faces
and deduplicated outlines as ordinary editable vector cels. Manual controls use
the same operation under **Sketch Studio → Brush / Text → Solid construction**.
Users can author pose frames, position, elevation, depth, rotation, squash,
camera settings and independent pose/elevation/depth timing. Optional floor
guides and shadows are native paths. Undo, revision checks and editable archives
use the existing storage pipeline.

An MCP client should read `sketch_capabilities.solidConstruction`, create the
example canvas, then send its `exampleProgram` as an `add_solid` operation.
The Android app performs geometry construction; a cloud geometry script, image
generator, reference asset, CV library or FFmpeg installation is not needed to
author this animation. Worker validation rejects unknown source fields and
routes the operation only to native versions 4.7.1 or newer. This is an existing
tool extension, not a new MCP transport or protocol implementation.

Size, horizontal position and depth use world pixels. Elevation means clearance
at authored keys. Key centre heights interpolate independently of rotation;
intermediate geometry is clamped above the floor. This is authored animation,
not rigid-body physics. Outlines respect the document minimum of 0.0005 times
canvas height in pixels. Absolute pose frame positions survive construction;
duration changes do not silently stretch them. Loading a study preserves the
selected frame count and FPS. Deliberately editing timing changes the current
sketch and is undoable.

Limits remain 64 pose keys, 1200 frames and 4096 objects per sketch. Dense baked
solids can reach the object limit before the frame limit; the operation rejects
the complete candidate without modifying the drawing. Longer films require
shot/book assembly. This feature does not establish hour-long film capacity or
professional character artwork, acting, voices, fight choreography or 3D film
quality. Earlier jungle and first-love film goals remain unfinished.

## Verification and evidence boundaries

Original eight-second cube and differently proportioned cuboid studies were
authored using the exposed native facades, then exported with VideoStudio's
native Android Canvas renderer in Robolectric. Each subject has 240 frames at
30 FPS, 240 native PNG hash checks and eight pixel-identical archive restoration
comparisons. Actual native editor screenshots accompany the tests. Silent MP4s
package only those PNGs using workspace FFmpeg; that packaging is not a phone
MediaCodec export claim. No reference geometry, background, motion or audio was
used. Private evidence stays outside this public repository.

Inspection and regression tests found and repaired rotation-induced airborne
centre bobbing, abrupt depth timing, held-key endpoint timing, invalid narrow
paths on tall canvases, hidden manual key retiming and preset clock changes.
Compact presets also retain strictly ordered frames after resampling.

Focused tests exercise native edits, shared corners and face identity,
proportions/depth, failure atomicity, ground/shadows, undo, independent timing,
capability examples, tall canvases and actual manual construction. Worker tests
exercise strict operation validation and old-APK rejection. Full-suite results
are recorded with the private engineering evidence after execution.

Workspace tests establish native source execution and manual/agent routing.
They do not establish execution by ordinary ChatGPT, installation on the owner's
phone, sleep/reconnect behavior or artistic acceptance. The new APK and worker
must both be available before the new operation can be used through the live
connection. Preserve the package, pinned signing identity, durable MCP device
identity and all user projects. Do not bypass a connector access rejection.
