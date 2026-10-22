# Native cel hand part passes — 2026-10-22

Isolated branch `feat/native-hand-part-passes-v41015` starts at exact verified
4.10.14/code517 source `93048e717a25fafdc2fe92f663b9fa2089469876`.
Future4.10.15/code518 integration, native execution, full Gradle/CI and signing
belong to the root lane. This document describes a bounded compositing tool;
it does not establish a believable grasp, actor likeness or accepted film.

## Ordinary shared operation

`add_hand_animation` gains optional top-level `parts`: `all`, `palm_forearm`,
or `digits`. The retained hand programme stores the same optional `hand.parts`.
Omission remains omitted and uses the existing complete hand. Explicit `all`
uses the complete legacy drawing algorithm unchanged, including smooth and
omitted render styles. Partial choices require explicit `settings.renderStyle:
"cel"`. Unknown choices, null, numbers, booleans, arrays and objects fail.
This field is not a hand setting and is not accepted on unrelated objects.
Each partial hand must be the sole object on its layer. Native document validation
rejects any other object on that layer before publication, including failed
constructor/retained patch transactions. Node validates this when a complete
retained layer is supplied; existing layer contents remain native-authoritative.
Capabilities expose `requiresOwnLayer:true`. Omitted/explicit-all hands retain
their existing shared-layer behavior. The manual constructor creates a new layer.

Any explicit choice, including `all`, requires native4.10.15 through the
recursive Node gate, covering constructors and retained/patch routes inside
ordinary component/story/correction/live requests. Legacy floors and normalized
requests remain unchanged. Archive/component references are opaque to Node;
clients must require4.10.15 before restoring a known parts-bearing archive.
An older native validator rejects the unknown retained field.

Use two copies of exactly the same programme and ordinary object/layer keys:
rear `palm_forearm`, ordinary prop, then foreground `digits` (four fingers and
thumb). Bounds remain the complete pose-programme bounds for either pass.
Poses, materials, timing, handedness and view are shared existing controls.
The UI offers Legacy default (complete hand), All, Palm / forearm and Digits.
Selecting a partial pass with smooth produces a validation message; no silent
style/pose change is made. This adds no canonical action.

## Full visibility before owner selection

Native cel geometry first constructs and orders the complete hand surfaces.
Visible skin owner regions are obtained by subtracting all preceding full-hand
skin occluders. Palm and forearm owners form the rear group; four fingers and
thumb form the digit group. No surfaces are deleted before visibility, so a
selected pass cannot reveal a hidden palm/finger face.

Both passes draw the original complete ordered cel ink: same skin, tone bands,
nails, creases and outer silhouette outline. Visible owner coverage classifies
complementary whole device pixels. The classifiers add no artwork, internal cut
outline or independently shaded surface. Owner coverage includes the original
stroke width only to assign its outward silhouette pixels. Ties and uncovered
classifier pixels belong to rear; original ink still controls visible pixels.

Independent antialiased vector masks would leave source-over cracks. A first
implementation used public integer-region boundary clipPath; actual native tests
rejected it for exact recomposition and shading differences (one sampled channel
differed by1). The unavailable publicSDK clipRegion compile attempt and failed
clipPath sources/logs are retained outside Git. No pixel assertion was weakened.

The current candidate draws complete ordered ink into a native ARGB bitmap at
the actual destination matrix/grid, then clears unselected whole pixels in-place
with native non-AA CLEAR rectangles. Surviving native premultiplied storage is
never read through getPixels/setPixels. A density-free, unfiltered identity
Canvas copy places this selected surface into the current native layer. Density
is explicitly DENSITY_NONE, preventing density-driven bitmap scaling.

The candidate must pass no-prop rear+digits equal to complete bit for bit,
including outer AA, lighting, transformed frames and paper. Additional tests
cover cropped output grids, a direct nonidentity/density320 Canvas, partial
object/layer opacity, opaque underpainting both inside the hand layer and on a
separate layer. The offscreen version passed all original core, crop/density, manual and
persistence checks but failed exact semitransparent hand+underpainting sharing
one original layer versus two split layers. The later separate-underpainting
branch was not reached in that failed run and remains a required check.

A public RegionIterator/integer clipRect replay alternative was measured at
56.268s:7/11 pass,4 fail, including AA, shading and crop exactness. It is rejected
and preserved. The offscreen path therefore now has an explicit sole-own-layer
validation bound approved by root. The integration test requires that specific
shared-layer rejection and still executes exact permitted separate-underpainting
recomposition at object opacity.43 and layer opacity.73. Native atomic rejected
constructor/patch transactions must preserve document/revision; explicitall
shared layers retain Undo/Redo. Tests keep Bitmap.sameAs and exact RGBA with no
tolerance or excluded boundary pixels. There is no universal layer-regrouping
pixel-equivalence claim.

The partition is recomputed using the actual destination Canvas matrix and
size, including editor/direct drawing, crop/preview scaling, and object/layer
transforms. It never classifies at512 then scales that classification. Partial
output is bounded to2048² actual destination pixels and a native software Canvas;
unsupported hardware output fails explicitly. Current SketchCanvasView is
software and native render/preview/export routes use bitmap Canvas. Peak new
scratch memory is one ARGB bitmap, one ALPHA_8 mask and two stride-aware byte
buffers: approximately7 bytes/output pixel (about28 MiB at2048²), plus native
paths and the existing renderer. No persistent cache or model/storage
dependency is added. Interruption is checked during surface processing, every
raster row and before/after the final bitmap copy. Native bitmaps are released
and the target matrix restored in finally blocks.

## Existing routes and limits

Sampled cels, animation compilation and component snapshots retain `kind:hand`
and the copied parts-bearing programme. Rendering/portable archives therefore
use the same shared native draw method. Partial-to-ordinary-vector conversion
cannot encode device ownership: the currently unused internal vectorFrame method
rejects that case explicitly. It never silently emits the complete hand.
Complete/all conversion retains its existing command bytes.

The editor's existing procedural-hand hit test uses complete programme bounds;
this change does not add per-part alpha picking. Composite color sampling sees
the actual shared native rendering. A later cached-frame upscale can soften or
reveal a coarse pixel split; contact edges must be reviewed at intended output
and zoom. Device-grid partitions guarantee exact no-prop reconstruction on the
same target grid, not a universal high-resolution contour or physical contact.

Split passes do not solve hand/banana depth, tube penetration, contact IK, thumb
opposition or unseen anatomy. Current curls remain coupled existing channels;
authored pose/contact anchors still require actual native visual review.
`digits` includes the thumb as one group: it cannot independently put one digit
behind a prop. There is no claim of graphite actor-style quality from generic
anatomy templates.

## Evidence and pending checks

Root captured complete-hand baseline before production changes using retained
FULL517 with no source overrides: PASS1/20.913s. Nine source-free cases cover
both hands, six views, grasp/open, legacy omitted style and explicit smooth.
Frames0/36 include nonidentity object/layer keys;36 original transparent/paper
native PNGs remain outside Git. The exact immutable JSON manifest is committed
as `android/app/src/test/resources/native-hand-parts-baseline-93048e7.json`,
SHA256 `4588e92fa969f8b4115879b5c3dd25e6a3f70eda1ab134019fe526d04b382e46`.
No private reference, actor art or native PNG is checked into source.

Actual native RED:5 tests,4 failures/18.008s, unknown parts. The omitted pinned
geometry/raw pixels passed. Frozen tests include strict native AA recomposition
and independent full-mesh visible-owner/shading probes. Preserve these tests
unchanged. Further integration tests cover actual crop/direct Canvas grids,
retained cels, component pixels, Undo and portable native frame restoration.
Root native focused GREENv5 passes12/12 in119.073s using retained FULL517 runtime
plus the exact5 production class overrides. Full future518 integration/build/CI/
signing and actual split-pass visual studies remain pending.
Public-clipPath run:7 tests/4FAIL49.089s (core5/2fail and UI2/2fail).
Omitted/all/strict validation passed. Real UI missing-control RED authorized manual
implementation. Offscreen v3:11 tests/1FAIL119.064s; only mixed-layer underpainting
failed. RegionIterator v4:11 tests/4FAIL56.268s. New v5 bounds partial passes to
sole own layers and adds native atomic add/patch+UndoRedo; all12 pass.
The permitted separate underpainting, object opacity.43 and layer opacity.73
branch actually executed and remained pixel-exact.
Preserve every compile/pixel/UI failure and original frozen5 core assertions.

Node initial RED is preserved outside Git. First GREEN attempt exposed an
incomplete retained-layer fixture (missing ordinary mandatory object fields),
which was corrected without changing assertions/production validation; focused
4 tests then pass. Full Node v6 passes795/795 plus306 smoke, including a fifth focused own-layer
parity test. Full-v1 exposed
an old source-parity evaluator without the new native helper binding; the evaluator
now executes the actual helper builder and retains complete metadata deep equality.
Real manual chooser/default/validation tests are included in the12 native PASS.
Frozen native source/test/launcher/log, baseline and Node proof copies with
SHA256 manifest live outside Git in
`native-hand-part-passes-20261022/green-v5-frozen-proof`.
This scoped class-override proof is not a full integrated future518 build.
All logs/frozen sources live outside Git under
`/workspace/work/native-hand-part-passes-20261022` and the root film-production
native-hand-parts launchers/logs. Old candidate/art generations remain intact.
