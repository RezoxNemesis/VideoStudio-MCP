# VideoStudio OMEGA Phase 6 — visual-quality engine and native integration handoff

**Repository:** `RezoxNemesis/VideoStudio-MCP`  
**Only working branch:** `phase/omega-6-visual-quality`  
**Only PR target:** `integration/omega-nine-phases-20261020`  
**Master contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Readiness:** engine prototype with independently testable compositing; **NOT native integrated, artistically accepted, or released**.

## Implemented inside exclusively owned files

`OmegaVisualAudit.java` is Android-independent, package-local, side-effect-free, deterministic visual math. It is not a new project database, automation bridge or production render hook.

- `compose(width,height,opaqueBackground,layers,look)` composites user-authored premultiplied-alpha sampled layers back-to-front. Inputs carry unique bounded layer IDs, explicit depth (0..1), local transform, opacity, grain, key-light response, and an optional shadow flag. The `Look` has camera offset, a radial 2D lighting grade, bounded key parameters, and a soft 3x3 drop shadow. Layers are defensively copied at creation. Grain is seeded and anchored to **source** pixels, so camera translation does not resample a fresh random texture. No invisible drawing, 3D lighting or AI reconstruction is implied.
- Render-time budgets: 2..2048 for width and height; at most eight compositing planes and **4,194,304 total input ARGB pixels**. The native Sketch project format still retains its original 32-layer/document limits; the compositor operates on bounded temporary tiles/passes rather than changing schema. User authored local pose and per-instance keys remain native source-of-truth.
- `measure` preserves the original Phase 6 ROI pixel-change signature, ratio and static-subject flags. It compares RGB even for transparent input; **real visible-motion evidence must use opaque composited native output**.
- `measureMotion` searches at most ±8px global translation from **textured background outside the supplied subject ROI**, then measures aligned foreground residual. Texture-poor backgrounds fail closed rather than inventing motion. `cameraOnlyCandidate` warns when raw subject variation disappears after registration; this is a heuristic requiring visual review.
- `measureParallax` measures two **disjoint human-authored background regions** independently. Differential image translation is reported as a *candidate*, not evidence of proper depth staging, nor a claim that anything was animated artistically.
- `appearance` reports region luminance, RMS contrast, edge fraction and near-black/white clipping as technical signals, never an artistic grade.
- `anchorFrames` / `anchorTimesUs` sample 0/25/50/75/100% temporal positions through existing `NativeFrameClock` for 24/30/60fps. Clips shorter than five frames are rejected for five-frame audit rather than duplicating images.

## Tested locally before committing

Source compilation: `javac --release 17` with a minimal *local-only* NativeFrameClock signature mirror and JUnit Test/Assert compatibility shim (not added to GitHub). The production source is compiled in CI with the actual Android `NativeFrameClock` and JUnit.

15 deterministic `OmegaVisualAuditTest` cases cover backwards compatibility, camera-only rejection, residual subject acting, featureless fallback, two-depth differential, edge/luma evidence, original-image immutability, alpha layering, depth parallax, stable texture grain, light gradient, drop shadows, native frame-time anchors and invalid inputs. Synthetic frames are **not native APK screenshots**.

Mandatory CI, running against actual repository with Java 17, Gradle 8.9 and SDK 36: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` via `.github/workflows/omega-nine-phase-ci.yml`. Read resulting run status and JUnit XML before claiming a green Android build.

## Integration-only proposal (DO NOT APPLY from phase branch)

The strict nine-agent contract reserves `SketchDocument`, `SketchEditOps`, `SketchProjectStore`, `SketchFrameRenderer`, `SketchFrameStreamEncoder`, `SketchCanvasView`, native export and manifest/dispatcher/UI wiring for the integration owner. **No such files were changed in this branch.** Suggested small, separately reviewed, feature-flagged adapter:

1. Off UI thread, pin `sketchId` and `expectedRevision`; validate `SketchDocument`; capture authored parallax/material/lighting controls as *optional schema fields only after a reviewed migration*. Keep `videostudio.sketch.v1` round-trip and existing frame/time keys unchanged. Do not read media from Gallery.
2. From the existing Android Canvas/Sketch renderer, select 2–8 owner-authored pixel planes or bounded tiles and preserve layer/object ID, ownership, alpha, exposure, locked status, per-instance transforms, and time-pinned pose. Do not derive hidden anatomy, reference contours or missing background pixels.
3. Call `compose` with per-frame `Look`, using `NativeFrameClock` and camera parameters. Do not compose directly into the source sketch or mutate canonical master assets. Render additional tiles/passes when 4M input pixel cap is reached; fail before output on invalid budgets.
4. For audit, collect **real native source/project revision-bound frames** at `anchorFrames`, record per-frame PNG/pixel hash, compare named subject/background ROIs with `measureMotion`, `measureParallax` and `appearance`, then retain raw samples for human visual QA. Use `SketchVisualReview` for full-resolution eye/hand/edge comparison; never use 64px thumbnails to certify anatomy.
5. Route frames through the existing preview/export pipeline behind a flag **default OFF**. Preserve all existing `NATIVE_RECOVERY_HELD`, command permissions, interruption, signing and release controls. The adapter must separately test fallback/undo/redo, locked-layer rollbacks, old document load/save and native MP4 decode.
6. Submit this work as an **integration-owner** PR after other agent API contracts stabilize. Do not steal another phase's files, silently wire experimental code into release, or bump version code.

## Blinded, human-visible acceptance protocol (not yet performed)

Use consented owner-provided references and actual native drawing/MP4 output, never Gallery enumeration. Show reviewers randomized, blinded before/after stills at **0/25/50/75/100%** plus the entire motion clip at native speed and slow playback. Keep camera motion and figure motion separately observable; include depth-plane toggle views. Two independent reviewers should check hand count/proportions, connected anatomy/contours, silhouette fidelity, material imperfection, light/shadow continuity, background parallax, palette continuity, physical acting, camera-only deception, and absence of repeated/held subject artifacts. Record disagreements; do not replace visual judgment with numeric thresholds. Attach actual unchanged source/revision IDs, frame times, full-resolution proof images, render hash, Android phone/installed version, and acceptance sign-off. Audio/final export quality belongs to Phase 7/8 cross-gate; verify audible tracks before claiming finished cinematic film.

## Live blockers and boundaries

- No production Android visual compositor, feature flag or touch tools installed from this isolated PR: shared-file ownership requires integration-owner review.
- No device-side captured film/reference comparison, blind review, verified parallax lighting controls, on-device p50/p95 performance, or 4.8.7 compatibility/signing verification as a consequence of this PR.
- Quality of an original hand drawing or acting cannot be inferred from edge counts or a synthetic fixture; Phase 2/3/4/5 inputs and I2/I3 device integration remain prerequisites.
- No APK was installed, signed, released, or promised. `NATIVE_RECOVERY_HELD` remains unchanged. Never browse Gallery or change keys.
