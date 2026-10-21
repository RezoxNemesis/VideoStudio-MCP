# Native preview reliability and reference layers — 4.8.5

The owner authorizes autonomous implementation, investigation and tests. Their
reference boards describe accurate reference-guided layered artwork, persistent
characters/assets, editable masks, drawing/detail passes, rigs/inbetweens, effects,
camera work and native playback/export. Preserve the verified4.8.4 source and
all earlier projects/studies. Target4.8.5/code488. No Gallery, external artwork
substitute, replacement signer, app data reset, deployment or stable publication.

## First priority: visible and truthful playback

Sketch playback must not invalidate an in-flight render on every timeline tick.
Coalesce work and retain the latest completed current-source bitmap while the
next requested frame renders. Keep the document clock, FPS, canvas and production
frames unchanged. Continue rejecting source/context-stale publications. Preserve
bounded12MiB viewport ownership and onion preferences. A failed or still-loading
preview needs a visible state and retry, not an indefinite empty surface.

The main editor must supply full source video duration before Media3 composition
admission, preserve trims/speeds, retain a useful preview on failure, and route
preview_project through the same edited composition path as manual Play Edit.
3D preview must distinguish asset existence/resource loading/frame submission
from visible pixel acceptance, report persistent failures and handle pause/resume.
CPU tests cannot establish a genuine GPU/device pass.

## First reference-layer slice

New action `sketch_import_reference_layers` shares SketchEditOps with the manual
**Separate reference into parts** dialog. Exact arguments:

```text
{sketchId,expectedRevision,operationId,sourceObjectId,
 expectedReferenceSha256,expectedSourceSha256,frame,exposure,hideSource,
 masks:[{maskId,layerId,objectId,name,rings:[[[x,y],...],...]}],edgeSnapRadius?:0..16,
 background?:{layerId,objectId,name}}
```

1..8 masks,1..8 rings per mask,3..64 finite normalized point pairs per ring,
implicit closure, even-odd fill. Reject duplicate/degenerate/self-intersecting or
intersecting rings, empty visible parts, duplicate IDs, and overlapping selected
visible source pixels. The source is a verified existing owned image reference;
both expected hashes are mandatory. Coordinates refer to the full imported,
EXIF-oriented working image. Use hard pixel masks and preserve source pixel alpha.
No extra downsampling, canvas/clock/exposure changes or synthesis is allowed.

Owner additionally requests an easier and more precise layer workflow while
playback work continues in parallel. Add read-only action
`sketch_reference_layers_preview` with the same argument shape. It validates
source/geometry/budgets, returns accepted masks and source/hash binding plus an
actual native `sketchPreviewImage` selection overlay, and never publishes files
or edits. Optional edgeSnapRadius uses deterministic local source-pixel contrast
to suggest nearby boundary positions within the stated radius. Default0 leaves
coordinates exact. Validate the adjusted rings; ambiguous/invalid suggestions
must fail or retain explicitly reported original geometry, never silently make
bad masks. This is local edge assistance, not semantic object recognition.

Manual users get source selection, named parts, native tap/drag polygon editing,
zoom/pan, point undo, ring holes and optional edge assist without typing IDs or
JSON. Preview changes before creation and invalidate stale preview acceptance
when the draft changes. Ordinary ChatGPT can request the same nonmutating preview
and submit the same accepted request. Preserve exact source pixels in the final
parts; a bounded overlay does not lower source extraction resolution.

V1 requires an unlocked, visible, normal, opaque source layer containing only
this image, identity geometric source/layer transforms, no transform keys or
referenceCrop. Source image opacity0/1 is allowed. Reject other arrangements
explicitly. Copy the original bounds exactly. Source image remains stored;
hideSource optionally hides it. Insert background then ordered part layers
immediately above the source layer, leaving unrelated layers ordered as before.
Generated layers have identity transforms and ordinary image objects, ready for
existing manual transforms, rigs, component save/apply and native render/export.
An optional background contains unselected source pixels; moving a part can expose
transparent missing artwork, not a fabricated clean plate.

Each output reference retains sourceProjectId/sourceAssetId and original import
metadata, plus optional strict `sourceMask` provenance:
`{version:1,method:'source_assisted',sourceReferenceSha256,sourceSha256,
operationId,maskId,complement,rings,maskSha256}`. Complement/background can retain
up to64 rings; ordinary parts up to8. Hash canonical version/coordinates/working
dimensions/complement to bind the accepted mask. Metadata and independent PNG
dependencies survive component copying, undo/redo and archive restoration.
No claim of recovered original production passes, automatic semantic analysis,
new character views, vector editability or professional artistic quality.

Use existing native transaction/history/live-event/receipt machinery. Enforce
original_strokes policy before copying. Validate IDs/geometry/source/budgets
before publishing:32 layers,16 image objects,4MiB document,2048 reference long
edge,8MiB PNG,128MiB retained reference files,512KiB request. Process parts
sequentially. Publish hash-owned files atomically; clean temporary staging only,
never delete a content-addressed file another transaction/history may own.
Reject interrupted work before commit; acknowledge an already committed result
truthfully. Same operation replay returns its receipt; changed fingerprints or
stale revisions fail. All new action and sourceMask input paths require4.8.5;
old action floors, omitted fields and fingerprints remain unchanged.

## Verification and expansion

First reproduce real black-render/source-admission failures. Verify repairs and
native screenshots. Test masks with asymmetric RGBA source, holes, background,
alpha, provenance, source/CAS conflicts, original-drawing policy, budgets,
undo/redo, components and archive restoration. Manual polygon controls and MCP
must call the same native action. Use native-authored test sources, registered
imports and actual native rendering; preserve all generations and hashes.

Run separate short2D cutout/rig and genuine3D studies before long productions.
Record technical/visual/manual-MCP/device/codec evidence independently. Actual
3D pixels require Android Filament execution and compositor/readback screenshots.
The4–5minute voiced film follows accepted short scenes, continuity/audio and
long-timeline recovery; schema capacity or automated test counts do not pass it.
