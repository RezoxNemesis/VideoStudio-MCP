# VideoStudio OMEGA — Phase 2: Source-bound reference breakdown and editable guide alignment

**Branch:** `phase/omega-2-reference-guides`  
**PR:** [#110](https://github.com/RezoxNemesis/VideoStudio-MCP/pull/110), draft targeting `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** **Phase 2 engine + native rendering/edit transaction adapter implemented and regression-tested; full phase acceptance remains BLOCKED on integration-owned UI and owner-device visual evidence.** Do not claim an installed, user-accessible guide tool.

## Owned implementation

Only `android/app/src/main/java/com/rezoxnemesis/videostudio/OmegaReferenceGeometry.java` and `android/app/src/test/java/com/rezoxnemesis/videostudio/OmegaReferenceGeometryTest.java` are changed, plus this handoff. The existing `align` weighted similarity transform is preserved. No shared native edit/document/rendering source, other phases, signing, release, recovery, or Gallery code is modified.

### Reference input and provenance

`Source.fromSketch(doc, sourceImageId, userDeclaredView)` reads an existing native `image` object's registered reference metadata, requires an explicitly declared **visible** source view, and pins:
- Native sketch ID and current revision, source image ID.
- Exact SHA-256 of owned, oriented PNG **and** original imported image; source project/asset ID.
- PNG dimensions, original image dimensions and source EXIF orientation 1..8.
- Canvas dimensions. Original-to-working normalized point mapping for all eight EXIF rotations/mirrors is explicit; the existing Android importer has already oriented the owned PNG.

`captureNative(context, sketchId, imageId, view)` loads the current document and verifies the actual app-owned PNG via `SketchProjectStore.referenceFile`. It neither enumerates Gallery nor reads external media. Any source replacement, revision change or user-declared view change invalidates a draft. Only points visible in the supplied view may be traced; there is no invented back/side view.

### Artist-correctable guides / contours

`GuideDraft` is an immutable, copy-on-write in-memory revision. `withLandmark` / `withoutLandmark` and `withContour` / `withoutContour` permit explicit correction by stable IDs. At least 3 non-collinear source/target correspondence pairs and one user-specified contour are required. Up to 128 landmarks, 32 contours and 4096 aggregate contour vertices are admitted; per-contour 2..2048 for open and 3..2048 for closed. All coordinates are finite normalized [0,1], weights bounded and positive.

`project` computes an aspect-ratio-safe weighted scale/rotation/translation fit in **source and target pixel metrics** instead of applying similarity to mismatched normalized axes. It rejects degenerate/collinear input, inconsistent landmarks exceeding caller-specified RMS or worst-case **pixel** limits, stale bindings and out-of-canvas projected contour vertices. Rejects unsafe guidance; never silently fabricates or clips artwork.

### Existing native Android pipeline — no second store

`prepareEdit` generates real `add_object` native `polygon`/`stroke` commands, not a pasted PNG. It respects frame index and exposure within the document, actual target layer visibility/locking, object IDs, colour, stroke width, count and document budgets. A copy is validated before proposing changes.

`previewNative` verifies the registered reference PNG, renders the **actual** original and proposed native `SketchFrameRenderer` Canvas images at the selected frame, checks actual decoded pixel differences and returns a bounded PNG with SHA-256, source binding and weighted landmark-error evidence. **It never mutates the saved drawing.** A changed pixel count shows a vector appeared, not that the anatomy, contour, acting or movie is professionally accurate.

`commitNative` reopens and hash-checks the owned source, checks the pinned original document revision, and sends the already-supported `sketch_edit` action through `SketchEditOps.execute`. The existing transaction, active drawing-policy guard, lock/revision checks, stable operation ID, history and undo remain authoritative. This class does **not** independently write serialized project data.

### Exact tested commands and evidence

Android CI workflow on phase PR/push:
```bash
cd android
gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace
```
Environment: Java 17, Gradle 8.9, Android SDK 36. [Run 39372284620](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/39372284620) **PASS** for engine/adapter/native-test commit `b5ea38b`. TDD [RED run 39371184223](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/39371184223) failed specifically because new guide APIs were missing; intermediate test-helper checked-exception error was corrected. Current later native-visual-artifact commit must pass independently before being called verified.

`OmegaReferenceGeometryTest` covers scale/rotation/translation, duplicates, NaNs, normalization bounds, eight EXIF orientations, exact source binding, stale revisions/hashes, source/canvas aspect ratios, non-collinear minimum, outliers, out-of-frame contours, immutable correction, native polygon validation, locked target and stale document rejection. Its Robolectric native test creates an **app-owned synthetic Android Canvas** reference, imports it via the actual native asset route, invokes production `previewNative` and `commitNative`, verifies changed decoded native pixels, owner hash integrity, atomic native revision/undo and no preview mutation. Test-created PNG triptych and JSON manifest are written under `android/app/build/test-results/testDebugUnitTest/omega-phase2-native-evidence/`; the workflow already uploads the parent JUnit artifact directory. It is **not** footage or a screenshot from the physical phone.

## Explicit failure behaviour and budgets

- Invalid/non-finite landmarks, unknown duplicate/corrupt reference, unsupported EXIF and mismatched provenance: reject with error; no sketch edit.
- Fewer than 3 non-collinear correspondences, poor fit / off-canvas guide: require manual correction, no invented geometry.
- Stale revision, locked/hidden target layer, unsafe frame/exposure, native drawing-policy conflicts or depleted sketch budgets: existing native edit path fails closed; no bypass.
- Source PNG hash/source file missing or modified: `SketchProjectStore.referenceFile` rejects before render or commit.
- Preview rendering exception or no visually changed decoded pixels: reject; saved doc unchanged.
- Native bitmap preview uses the actual canvas and max image dimension 720 / output PNG 1 MiB. Runtime frame rendering and true phone p50/p95 are **unmeasured**; no fabricated 50x performance numbers.
- No alternate project database or schema migration. Vector results serialize only through the existing `videostudio.sketch.v1`; original stored image reference hash and object overrides are preserved. Undo reverts the vector edit via native history. Draft landmarks themselves are **not** yet a persisted first-class guide/handle object.

## Integration-owner-only gate (must be separately reviewed)

**Missing production UI entrypoint:** Add a Sketch Studio tool/dialog for selecting a registered reference, explicitly declaring the visible view, placing/correcting paired source and scene landmarks, tracing contours, reviewing the source/target overlay, confirming the preview, committing and undoing. This requires shared `SketchStudioActivity` / touch-view changes **outside this phase's exclusive ownership**. An integration-only PR must preserve owner drawing policy and explicitly distinguish reference-assisted strokes from original authored strokes. If persistent editable landmark handles/source provenance are required in the sketch JSON, obtain a separately reviewed additive `SketchDocument` schema extension; do not silently insert undocumented keys.

**Missing visual/device gate:** Supply an owner-approved real target reference (not private Gallery traversal), render full-resolution native source-vs-drawing overlays and frames at the required 0/25/50/75/100% times, have a human inspect hand/head/body shape continuity and corrections, inspect stored frame/undo/reopen behaviour, and validate on owner's Android phone. Device currently reportedly runs 4.8.7 while this branch is based on 4.8.6 source; reconcile PR #106 and version/signature compatibility first. Neither signed APK release nor installation is authorized by this PR.

## Handoff to another phase / integration owner

Phase 3 may take the `Projection.contours` normalized point lists for smooth editable Bézier fitting; Phase 2 must never modify Phase 3 files. The integration owner should connect the approved native UI and persist provenance without circumventing atomic sketch edits. Leave PR #110 a **draft** and do not merge into main, bypass `NATIVE_RECOVERY_HELD`, touch the signing identity or claim final artistic acceptance without owner-device evidence.
