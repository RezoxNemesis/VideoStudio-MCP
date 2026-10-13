# Responsive Sketch Studio Implementation Plan

> Execution: inline in this session, under the owner's standing autonomous authorization.

**Goal:** Make phone and ChatGPT sketch authoring precise, responsive and useful for flipbook animation.
**Architecture:** Extend the shared revision-bound facade and Canvas renderer; separate immediate canvas presentation from serial background persistence. Keep owned references and native export recovery.
**Tech Stack:** Java 17, Android Canvas, SQLite, Filament 1.74, Zod, Robolectric.
**Spec:** ../specs/2026-10-12-sketch-studio-design.md

## Global constraints
- Preserve applicationId com.rezoxnemesis.videostudio.next and pinned signer 7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd.
- No Gallery browsing or claimed exact/neural image reconstruction.
- Ordinary drawing/frame edits do not enter JobManager; export remains asynchronous and cancellable.
- Existing document schema and defaults remain readable.

## Tasks
- [x] Add failing native pixel/transaction tests for brush/path/crop/blend and whole-cel operations; add gesture and serial-save conflict coverage.
- [x] Implement SketchBrushes, SketchCelOps, SketchReferenceTrace and validated shared document fields; update native and Zod operation contracts together.
- [x] Add cached previews, pinch/pan, eyedropper, stabilized stylus strokes and configurable onion skins to SketchCanvasView.
- [x] Move SQLite authoring off the UI thread; retain optimistic draft and conflict recovery. Build palette, brush, cel and reference controls.
- [x] Fix all three native 3D initialization paths and error boundaries; defer thermal control to Android with no app-level thermal holds.
- [x] Run focused regressions and existing Node/Android suites, run the existing Android CI to build the compatible signed 3.9.1 update, verify its artifact metadata and update PR #70. See docs/SKETCH_STUDIO_VERIFICATION.md for the distinction between local checks, packaging and phone acceptance.

## Review focus
- Rapid consecutive strokes and lifecycle shutdown retain all successful edits.
- Agent revision conflicts retain a private local draft and never overwrite newer source.
- Reference crops preserve aspect and originals; image changes remain hash rejected.
- Cel insertion/deletion shifts exposure/key clocks without corrupting unrelated layers.
- Low RAM/GPU initialization failure has bounded allocations and a visible recoverable state.
