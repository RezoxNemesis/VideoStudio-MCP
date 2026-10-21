# Detailed source-assisted reference layers

Continue from verified 4.8.5 `db383ac8473a52b07a8fbbca80bf147e35cc98e0`.
Branch: `feat/native-detailed-reference-layers-20261019`.
Target: 4.8.6 / versionCode 489. Original package, signer and device identity stay intact.

The owner explicitly requested copying the supplied Temple Realm image using
layers, including its existing structure and details. This authorizes source
pixel copying for this particular test; it is not an independent drawing or
original-animation quality pass. The private source image and mask coordinates
remain outside Git in `/workspace/engineering-review/temple-reference-layers-20261019`.
Earlier original artwork, studies, film goals and all evidence are preserved.

## Reproduced shortcoming and repair

The existing import decoded all 1024×1280 source pixels without differences.
Three nested separation steps also recombined exactly. The next step failed
native document admission: retained hidden source and intermediate cutouts count
toward the old 16-image limit. This prevented a finer editable character project
although the application already supports 32 layers.

`SketchDocument.MAX_IMAGES` now permits 32 image objects, including hidden images.
Full-source selection preparation uses the same count, retaining its independent
8 MiB total bitset ceiling. Source storage remains 128 MiB, each PNG remains
bounded to 8 MiB, decoded sources remain at most 2048×2048, and renderer caching
remains bounded to 12 MiB. This count change does not promise 32 maximal-size
selection maps fit simultaneously; over-budget preparation still rejects with
an explicit preview error and existing Retry recovery.

The manual workflow uses the same public native operations as MCP. Native and
worker capability metadata expose the expanded 4.8.6 floor, new 32-image limit,
legacy 16-image limit and unchanged selection-memory ceiling. Existing separation
and explicit sourceMask request version floors remain 4.8.5; parameters and
fingerprints are unchanged. Agents must inspect the connected native limits;
projects containing more than 16 images require 4.8.6, including archive restore.
Do not give such archives to 4.8.5 as compatible projects.

## Repeatable verification

`SketchImageBudgetTest` checks admission of 32/rejection of 33 images, actual
native rendering/selection of 32 distinct owned references, and rejection of
selection preparation above 8 MiB without publishing the extra mask.

`SketchReferenceFidelityProofTest` is an opt-in private-reference harness. It
registers an explicitly supplied image asset, invokes real Sketch imports,
previews and multi-stage separations, compares every decoded pixel, tests an
independent part edit with Undo/Redo, verifies exact single-layer pixel coverage,
and restores the native project archive. It also draws the actual SketchCanvasView
and taps the selected hand after worker alpha preparation. No image generation,
external drawing/masking renderer, Gallery access, hidden-surface model or source
video reuse is involved. The fixture supplies normalized native mask coordinates,
source filename, canvas, named stages, background layer and editable-part tap.

Run one Gradle process at a time, using the existing absolute-path wrapper:

```
VIDEOSTUDIO_REFERENCE_FIDELITY_FIXTURE=/absolute/private/fixture.json \
VIDEOSTUDIO_REFERENCE_FIDELITY_OUTPUT=/absolute/private/new-evidence-generation \
python /workspace/onboarding/videostudio-gradle.py \
  -p /workspace/VideoStudio-Anatomy-Sketch/android --offline \
  :app:testDebugUnitTest --tests '*SketchReferenceFidelityProofTest' \
  --tests '*SketchImageBudgetTest' --console=plain
```

Without a fixture, only the private-reference test is explicitly skipped; budget
regressions still run in ordinary CI. The output directory must be a new generation
because existing archives are deliberately not overwritten. Local integrated validation passed 48 affected Android tests with no failures,
errors or skips. The completed private reference proof also passed all five
stages, exact pixel coverage, edit/Undo/Redo, native archive restoration and
actual Canvas hand selection. Node passed 652 tests plus 306 smoke checks.
Mask edge refinement, full CI and APK verification are recorded separately in
the external evidence; they are not certified by these local counts.

## Evidence boundaries

A pixel-perfect source-assisted composite preserves the uploaded artwork. It
does not reconstruct original vector strokes, occluded anatomy, the clean
background behind a character, new views, in-between drawings or a complete rig.
Manual masks can include background fringes and need separate silhouette review.
An independent moved hand can expose transparent gaps; those must not be claimed
as successful animation. Workspace Robolectric/native Canvas execution is not
phone execution, live ChatGPT MCP execution, audio or Android video-codec export.
Do not merge, deploy the worker, replace the signing key or publish a stable APK.
