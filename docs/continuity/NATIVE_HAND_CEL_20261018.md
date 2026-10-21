# Native cel-hand quality continuation

This worktree, `feat/native-hand-cel-polish-20261018`, starts at
`d76edec280cab5b222accc1002c28ee4729af9f0`. Source targets **4.8.1 / code 484**.
The preserved 4.8.0 candidate and its original signer are independently verified;
that does not establish this new candidate, phone execution or artistic quality.

## Drawing target and reusable controls

The isolated hand must read as a coherent drawn palm, with broad skin tones,
rounded fingertips and inset nails, restrained creases, connected wrist contours
and correct hidden surfaces. Inspect both hands in front, left, right, back,
high and low views, including motion between poses. Technical assertions do not
replace a standard-2D drawing and animation review.

Manual: **Sketch Studio > Brush / Text > Hand animation > Render style**.
`smooth` preserves the original drawing algorithm; `cel` selects the new native
Canvas surface treatment. **Hand detail** (`anatomical` / `contour`) remains an
independent setting. Existing finger curls, thumb opposition, spread and wrist
controls remain available. This is the isolated hand feature; it does not yet
replace the full human figure's hands.

The four app-owned study recipes are `open_close`, `grasp`, `finger_cascade`
and `thumb_opposition`. In the manual dialog, select **Study example** and use
**Load study** to fill the existing custom pose editor. For the original study
clock, first set Timing / canvas to 1280×720, 96 frames at 24 FPS, then choose
hand size .95, Render style `cel`, the desired hand and view. Loading a recipe
adapts its keys to an already selected valid clock; it does not change the
canvas, FPS or render style. Inspect the native preview and create normally.

Ordinary ChatGPT: call `app_status`, require connected native **4.8.1** for any
explicit `settings.renderStyle` (including `smooth`), then read
`sketch_capabilities.handAnimation`. Use the existing `sketch_edit` operation
`add_hand_animation` with `settings.renderStyle: "cel"`; native requests and
manual controls share the same settings, authoring and rendering implementation.
Preview actual native frames and retain editable archives before judging quality.
Omitting the field preserves old request fingerprints and the original **4.8.0**
hand API floor. Style-bearing archives require 4.8.1; opaque archive references
cannot be inspected by the worker's version gate.

Native `sketch_capabilities.handAnimation.studyExamples` returns four fresh
entries `{id,width,height,frameCount,fps,operation}` containing the actual
app-owned requests. Create a fresh sketch with the returned canvas and clock,
copy the chosen operation, select `settings.view` / `settings.handedness`, and
use the destination layer and a fresh hand identity. Submit it through the
existing revision-protected `sketch_edit` route. No private workspace recipe is
required. The matrix checks those requests against the historical study inputs
before authoring the actual native examples.

The public result is an editable retained hand programme. Sampled book pages
retain native hand state. Internal retained-to-path conversion tests do not
establish an exposed manual/MCP command for editing individual hand ink strokes.

## Preserved review evidence

Private evidence root: `/workspace/engineering-review/hand-cel-20261018`.
Earlier generations remain intact. v1 removed the triangle grid but retained
heel shade fragments and exposed folded nails. v3 repaired coherent palm/root
lighting and used local triangle-depth comparisons to hide details behind nearer
skin. Independent review still returned **NEEDS_REWORK**: collapsed cap faces
could be culled after left-hand reversal, profile joins had abrupt ledges, and
oblique palm creases left small fragments. Keep these failures visible when
assessing subsequent retakes.

v4 passed 39 focused Android tests but still failed independent static review:
the opposite finger-root shoulder retained a 9-pixel shelf, and crease fragments
remained. v6 normalizes collapsed cap polygons before handedness reversal,
tapers both sides of the distal knuckle overlap, buries the thumb construction
root, and shares coherent lighting across exposed proximal digit roots. Joint
centres, bone lengths, camera transforms, central palm/pads and finger contact
surfaces stay fixed. Both edges of all four open profiles now move at most one
pixel between adjacent rows in the measured junction window.

Palm crease fragments are seated on actual supporting skin facets, including
their interiors. Exact local triangle-depth clipping protects visible lines and
folded nails from nearer skin, including nearer skin belonging to the same
finger. Insignificant isolated crease islands are removed without filling holes
or bridging occluded gaps. A failed intermediate owner-mask union was rejected;
its source and logs remain preserved separately. The final implementation keeps
the original masks for nails/finger creases and uses exact depth clipping for the
precisely seated palm lines.

The editable-cel retest also reproduced a fill-rule defect: native Boolean paths
could contain even–odd cutouts, while serialized ordinary paths use winding
fill. Cel contours now preserve those cutouts when sampled to editable paths.
The expanded 24-case targeted comparison permits zero solid-interior palette or
skin/background coverage errors and checks nail interiors and crease contrast.
The original front-grasp 7% boundary-pixel limit was retained; the observed
v7 result was 3118/53927 (5.78%). This is a rendering comparison, not artistic
certification. Final source hashes, targeted test logs and actual
retained/editable PNGs are in `v7/verification/` and `v7/export/` under the
evidence root. Earlier v5 images retain their original bytes and URLs.

Final v7 removes the last measured detached crease remnants by increasing the
maximum insignificant-component span from .025 to .028 model units. Perimeter
remains .065; every other production byte matches v6. Of 50 native captures,
42 are byte-identical to v6 and eight contain only localized crease cleanup;
skin/silhouette coverage is unchanged. All 24 internal retained/editable pairs
have zero deep-interior errors. Earlier generations remain intact.

Independent static review accepted the bounded simple-cel hand study after
examining both hands at normal and enlarged sizes. Full-motion acceptance is
still pending. Official v6 combined Android checks passed 53 tests with zero
failures/errors/skips; the affected v7 cleanup and manual study UI rerun passed
seven tests with zero failures/errors/skips. Archives are `focused-v6-junit/`
and `focused-v7-junit/`. These overlapping suites are not 60 distinct scenarios.
Full Node verification passed 306 smoke checks and 593 tests. Worker dry run
passed; no deployment occurred.

The 48-case full-motion matrix and signed full-regression CI candidate are
pending separately. The matrix renders all 96 frames of each case at 1280×720
and 24 FPS, compares 336 actual historical legacy PNG probes and 96
archive-restored frames, and preserves requests, source snapshots, hashes and
independent visual statuses. Source must remain frozen during capture.

The separate 124-second human story remains a working cut with a
**NEEDS_REWORK** artistic verdict. Its 240 distinct technical scenarios are
historical baseline evidence, not 240 artistic passes. The new hand-style matrix
is planned to add 48 different native operation inputs; repeated renders, frames, assertions
and retakes do not increase that count.

No Gallery, reference tracing, external artwork renderer, worker deployment,
stable publication, replacement signing key or app-data reset is involved.
Workspace native rendering is separate from phone/live-MCP verification and
Android video encoding. Listening acceptance must be recorded independently.

## Expanded programme

The owner increased the target to 1,000 distinct animation scenarios and more
if necessary, after accepting the current result. Broaden character types,
styles, situations, environments, motion, viewpoints and audio systematically.
Report scenario families and parameter variations separately; frames, assertions,
retries and identical reruns do not increase the creative-scenario count. The
original voiced two-minute human story and standard-2D acceptance goal remain.
