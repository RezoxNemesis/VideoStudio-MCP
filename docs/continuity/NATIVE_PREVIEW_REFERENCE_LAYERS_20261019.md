# Native playback recovery and reference layers — 4.8.5

Working branch: `feat/native-preview-layer-workflow-20261019`, based on verified
4.8.4 commit `88748ebab67299066ca92527b8e279b5cd06a284`. Target version4.8.5,
code488. Original signer and existing saved programme defaults must remain.
Evidence: `/workspace/engineering-review/preview-layer-workflow-20261019`.
Final commit/build and integrated verification are recorded in that evidence root.

## Why this change

Sketch playback could invalidate every unfinished viewport render as its clock
advanced. With no cached target frame, it showed black. It now coalesces work,
keeps completed artwork from the current source visible, rejects stale source
publications, and shows persistent loading/failure/buffering status. More →
Refresh preview / Retry preview pauses playback and retries without changing
artwork. Source FPS and displayed buffered frames are explicitly distinct.

Media3 CompositionPlayer rejects video compositions without full source duration
metadata. Native video and audio items now supply known full source duration
before trims and speed. Unknown metadata produces actionable preview admission
failure. Play Edit, initial edited preview and `preview_project` share the edited
composition path; preparation failure preserves a working preview. A bounded
previous player supports rollback of asynchronous candidate failure. This does
not itself prove decoder, hardware effects or phone playback.

Filament preview now distinguishes loading, completed resources and successful
frame submission, suspends readiness deadlines in the background, and exposes
retry. CPU state/UI tests do not prove visible GPU pixels. Existing authored
world roots, cameras, clip bindings and JNI initialization order remain.

## Use the new layer workflow manually

1. Import an explicitly chosen image into a VideoStudio project. In Sketch
   Studio, create an empty layer and copy that project reference onto it using
   the existing reference control. Reference import initially hides its guide.
2. Choose **More → Separate reference into parts**. Select the owned image.
   The source must be alone in an unlocked, visible, opaque normal layer, with
   identity spatial transforms, no transform keys and no crop. Image opacity
   may be zero or one. Other layers remain intact.
3. Name a part, tap its outline, and **Accept ring**. Drag draft vertices,
   zoom/pan, undo points or edit accepted rings. Add holes and further named
   parts. Up to eight parts, eight rings per part and64 vertices per ring.
4. Optionally enable local edge assistance, with radius0–16 source pixels.
   This follows contrast near supplied points; it is not semantic recognition.
5. Select **Preview parts** and inspect the native overlay. The source remains
   unchanged. Any changed name, geometry or option requires a fresh preview.
6. **Create layers** commits ordinary image objects in named layers. Normal
   Undo restores the previous drawing in one history step. Existing transforms,
   keyframes, prepared rigs, component save/apply and archive tools can use them.
   Set static connectors/background artwork to the shot exposure; a new drawing
   cel normally lasts one frame.

Keep unselected pixels as background preserves the visible remainder. It does
not create a clean background behind objects. Moving a cutout can expose missing
art; author it explicitly with app drawing tools or supply owned hidden-view
artwork. Parts are hard raster masks, not recovered vectors or production layers.
Touch selection uses the final cutout's full-source alpha, including inherited
holes and transparent padding. Immutable bit support is prepared on the raster
worker, transferred with source-generation guards, and cleared on source changes
or disposal. It uses at most8 MiB per support map; UI and in-flight worker maps
may temporarily coexist. Touch handling does not decode PNGs or read files.
Selection waits for preparation instead of selecting the wrong part. Existing
unmasked image selection remains unchanged.

Their full imported working raster is retained, without another extraction
resolution reduction. Original source imports retain the existing2048-edge limit.

## Ordinary ChatGPT / MCP

Call `app_status`, require connected native4.8.5 and read
`sketch_capabilities.referenceLayers`. No live endpoint is assumed in this
workspace. Read a fresh drawing revision and the owned image's `reference.sha256`
(working PNG) and `reference.sourceSha256` (original imported source).

`sketch_reference_layers_preview` and `sketch_import_reference_layers` share:

```json
{
  "sketchId": "drawing",
  "expectedRevision": 2,
  "operationId": "named_parts_1",
  "sourceObjectId": "source_image",
  "expectedReferenceSha256": "<working PNG sha256>",
  "expectedSourceSha256": "<original source sha256>",
  "frame": 0,
  "exposure": 96,
  "hideSource": true,
  "masks": [{
    "maskId": "hand",
    "layerId": "hand_layer",
    "objectId": "hand_image",
    "name": "Hand",
    "rings": [[[0.2,0.3],[0.5,0.3],[0.5,0.7],[0.2,0.7]]]
  }],
  "background": {
    "layerId": "remainder_layer",
    "objectId": "remainder_image",
    "name": "Visible remainder"
  }
}
```

The placeholders must be replaced with real source hashes. Coordinates address
the complete EXIF-oriented working image. Optional `edgeSnapRadius` defaults
semantically to zero without injecting a request field. Preview returns accepted
masks and a `sketchPreviewImage`, bound to the source revision/hashes. Inspect it
before committing the identical request. When submitting already-adjusted rings,
set radius0 or omit it to avoid applying assistance twice. Both canonical actions
also have explicit `studio_` aliases. Normal manual operation generates safe IDs;
users need not type JSON or hashes.

Import is receipt/revision bound and one atomic drawing/history mutation. Exact
retries return the receipt. Changed requests with the same operation ID conflict.
Invalid, crossing, empty or visibly overlapping parts are rejected. Global IDs,
32-layer/16-image, request, document, PNG, storage and reference hash bounds apply.
Original-strokes sessions reject source-pixel copying before preparation. A strict
optional `reference.sourceMask` records assisted provenance and accepted geometry;
it is not an unforgeable authenticity certificate. Components and archives retain
independent owned PNG dependencies and this metadata.

## Visual evidence and limits

A portable native-operation template is in
`docs/examples/reference-layers-lantern-robot.json`. Source creation, mask requests
and editable motion keys use public app operations. Fill source identity/hash
placeholders from fresh native responses. Workspace evidence executes the real
source export preparation, not the final Android video encoder.

One original silent mechanical study was authored using native Sketch shapes and
strokes, imported through the owned reference surface, separated, and animated
using ordinary editable transform keys. All4-second/96-frame/24FPS generations
are retained. V1 exposed disconnected shoulders and edge crowding. V2/V3 add authored connectors and framing, but a one-frame connector exposure
left shoulder gaps during motion. Their earlier narrow acceptance was withdrawn.
V4 explicitly holds the native connectors across all96frames and checks42
attachment samples in each frame. Its initial/final neutral raster hashes match. Extraction still retains512×512
source pixels; the final native canvas remains512×512. V4 has95 distinct raster
hashes; it is not95 unique drawings or95 creative scenarios. Four sampled frames
match after native archive restoration. Review MP4 packaging uses unchanged native
PNGs through FFmpeg; this does not establish Android-native codec export.

The study demonstrates front-view rigid cutout mechanics. It is not detailed
human anatomy, deforming elbow/finger motion, arbitrary new views, realistic
texture, speech or an accepted production film. The larger human art/acting
quality target remains NEEDS_REWORK. Earlier human studies, correction recipes,
hand matrices and voiced story work remain preserved.

Two bounded API33 software-emulator attempts reached ADB and guest SwiftShader
GLES3, but full boot did not complete (nine minutes, then nine-and-a-half minutes
reusing existing AVD data without a reset). Both processes were stopped;
no APK, MCP identity or user data was installed/reset. Actual app GPU playback,
physical-phone execution, native codec output and live MCP parity remain unverified.

## Follow-up quality gates

Advance through short native multi-view character, joint, occlusion, camera,
effects and audible dialogue tests before long-film expansion. Source-assisted
cutouts cannot infer unseen surfaces: combine owned alternate views, original
editable redraws, rigged deformation and genuine3D assets as appropriate. The
4–5-minute film and1000+ varied creative-scenario goals remain unfinished. Existing
3D mannequin GLBs are real weighted geometry with reusable motion clips, not a
high-detail character asset pack. Do not blindly deploy the worker or alter
legacy queued payload compatibility. No merge, deployment or stable publication
is part of this branch.

## Verification before source commit

Official local Android checks:95 initial integrated checks,46 final feature/UI/
editor checks, then103 final affected checks after store/receipt/notification and
scene-exposure repairs; all final listed checks have0 failures/errors/skips.
These overlapping runs are not additive distinct test counts. The full CI suite
and original-signer APK build are tracked separately in the evidence root.
Node:652 tests plus306 smoke checks passed. Worker bundle dry-run passed; nothing
was deployed. Independent bounded reviews prompted the cancellation/storage,
draft-preservation, player intent/error and notification-takeover repairs.

The final official native visual proof records protocol4.8.5,96 frames,24FPS,
512×512,95 distinct hashes, all-frame attachment/framing checks, matching neutral
endpoints and sampled archive restoration. It includes an actual `.vssketch`
archive with owned image dependencies and editable keys, native operation JSON,
screenshots and a review MP4 packaged from unchanged app-rendered PNGs. Earlier
failed/withdrawn generations and failed verification attempts remain retained.
No APK/phone/live-MCP/actual3D GPU/native-codec pass is implied by these checks;
final APK identity/signature and CI status are recorded after the build completes.

A final source follow-up fixes manual selection of full-canvas cutouts. Rectangle
picking first failed3 native cases; geometric-mask picking then failed the new
inherited-hole case. The final implementation reads actual PNG alpha on the worker
and transfers immutable bit support to Canvas. All19 native cases pass, including
real touch gestures, unavailable-data gating, source reset, fractional source
crop, animated matrices, legacy-image compatibility and ownership after bitmap
release. The final affected official alpha/Canvas/renderer run passed96 checks;
all96 animation PNG hashes remain identical to the reviewed final study. The full
Node suite was rerun successfully. These checks overlap earlier counts. The first
CI run was intentionally canceled to build the corrected source in a new full run.
