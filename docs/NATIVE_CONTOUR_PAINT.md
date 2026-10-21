# Native contour paint, 4.9.4

The first separated drawing exposed large transparent gaps between hand-painted
opaque bases and the authored ink contours. Build a reusable paint tool that
fills an existing closed native pen contour under the linework, without reading
reference image pixels or introducing prefab figure geometry.

Existing sketch_edit gains paint_fill: objectId (an existing native paint cel),
strokeId (stable receipt identity), contourObjectId (an existing unfilled path),
expectedContourSha256 (source pin) and color (#AARRGGBB). The selected visible,
unlocked contour and paint cel must belong to the same layer and exposure.
Initially require identity transforms/no keys/full-canvas paint bounds; reject
unsupported transforms instead of silently changing coordinate spaces. Every
subpath must close explicitly. Use native even-odd area rendering with Android
antialiasing. Empty/degenerate regions fail. Limit axes to2048 and total pixels
to1,048,576, with cancellation checks and no downsampling.

The native immutable paint journal retains the exact authored contour and hash.
Normal worker preparation, atomic commit, Undo/Redo, operation replay, stale
revision checks and archive dependencies stay in the existing paint lane. A fill
is an explicit area operation, not an invented pen gesture. Saved-session replay
shows the before/after transition without claiming recovered artist strokes.

Manual Paint studio exposes closed contour selection, current paint color and
target paint cel. MCP uses the same operation/helper with a4.9.4 floor. Omission
keeps older requests and feature floors. No new canonical MCP action. Reference
images, clone pixels and generators remain unavailable in authored workflows.

Verify actual native pixels for curves, concavities, holes and retained existing
paint; strict inputs, ownership, closure, transforms, hashes, bounds, cancellation,
transaction rollback, Undo, replay/archive and manual/MCP parity. Then redraw
closed anatomical contours and fill the actual clean scene through the tool;
inspect native output before accepting it. Voice work stays deferred.

The curved semitransparent regression initially failed: the whole-canvas pixel
at (98,66) was 0x435f4c9c, while drawing the same contour separately into a tile
produced 0x435f50a0. PNG encoding preserved the discrepancy; clipped Skia
rasterization caused it. Draw once on the bounded native canvas and copy raw
premultiplied pixels into immutable tiles. The original complete pixel-array
comparison now passes without changing its expected values. Preserve both
generations outside Git. This does not address the separate historical 4.8.8
mirrored/trimmed/rotated source-image alpha regression.

Fresh integrated local checks: 2107 Android tests, zero failures/errors, 13 skips;
663 Node tests and 306 smoke checks passed. The opt-in authored scene and motion
runners are skipped without private manifests. Actual native executions also
produced separated clean drawings and one 96-frame/24FPS quiet-reaction study.
Those drawings remain NEEDS_REWORK for final likeness/detail. The motion study
uses authored blink cels, small native joint rotations and a 2D camera push-in;
it is silent and does not demonstrate an orbit, unseen views or the 240s film.
Host rendering is Android native Canvas under Robolectric, not phone execution.

Actual motion preflight also found existing Android animation settings missing
from the ordinary MCP schema. Restore strict hiddenArtwork, interactionCues and
keyframeTolerancePixels inputs with their original native floors (4.5.0, 4.5.2,
4.5.3). Omitted fields preserve old behavior/fingerprints. Contact cues measure
joint distance only, not complete physical contact or artistic correctness.

Optional workflow inputs native_study_blob_sha/native_study_sha256 attach an
exact-source, source-free native evidence ZIP to a candidate build. The receiving
script checks ZIP/inventory bounds and every digest, and never runs its contents.
This is evidence transport, not a claim that CI created the drawings. Keep the
original reference and comparison previews private. artifact_run_id stays empty;
no stable release or Worker deployment is part of this candidate workflow.

Archives containing paint_fill journals require 4.9.4. Archive references are
opaque at the relay, so legacy archive action floors remain unchanged. Older
readers reject the new journal instead of silently losing its provenance.
