# VideoStudio 4.5.2 — native gait constraints, frame-sampling architecture, editor responsiveness

This branch extends the existing **4.5.1** feature branch without merging the older stacked 4.4/4.5 series into `main`. The 4.5.1 signed APK and all existing projects remain unchanged until this patch is separately built and verified.

## How to use

1. In Sketch Studio, draw a humanoid as separated **vector/raster parts**, notably a left foot and a right foot with real heel/sole silhouettes. They should overlap the prepared lower-leg pieces correctly.
2. Choose **More → 2D animation**. In **Rig mode** align the root, hips, knees, feet, arms and hands with the drawing. Bind each foot object to `foot_left` and `foot_right`. Separate matching legs/arms produce more believable articulated motion than a flattened body.
3. In **Acting presets**, choose Walk or Run, then set its first frame and duration. The motion keys remain manually editable and contain actual counter-swing and body settling.
4. Choose **Acting presets → Plant gait feet (walk/run)**. Select the same start, duration and motion style. The native solver samples the actual prepared character and calculates alternating stance intervals, reachable world-space sole targets, and 1–2-frame IK blend ramps.
5. Scrub in **Pose mode**. During hard stance the foot coordinates and sole rotation remain constant even while the root travels; the entry/exit transitions ease into and out of that target. Inspect that the ankle drawing and knee actually look right.
6. Run **Production QA**, fix reported issues, **Save scene**, then **Bake animation** to create a separate editable Sketch Studio output. Export MP4 using the existing protected pipeline.

If a stride is too long, the chosen floor is out of reach, an artist already controls a competing IK chain, or separate foot drawings are missing, the operation rejects with a specific error and keeps the edited source untouched. Automatic contacts operate on at most 240 frames per request. Long films should be split into manageable shots.

## Improvements to architecture

### Single-model batch sampling

`SketchAnimationCompiler.sampleFrames(source, program, first, last)` validates the same author program once, then samples at most 360 consecutive frames with one private rig model. Independent `sample` and live `preview` still use the same solver, so frame coordinates do not diverge. `preview` now reuses its own validated model for overlay sampling instead of reconstructing the JSON rig twice.

### Reach-bounded foot planting

`SketchAnimationFootwork.plant` returns a **new** program and leaves the source untouched. It samples world-space hips from the canonical solver, uses prepared leg lengths in physical source pixels, computes a feasible intersection of ankle-target reach at every hard stance frame, and verifies the result through the real IK solver before returning. Targets never use camera-relative on-screen coordinates.

`blendInFrames` / `blendOutFrames` are backwards-compatible, optional 0–12 frame additions to a foot-contact program. The old hard-contact schema is unchanged when these fields are absent. During transitions the solver smoothly interpolates toward reachable IK. During each active hard-plant frame, **exact zero-slip contacts are still required**; unreachable targets still fail loudly. Ramps on the same chain may not overlap, and cannot exceed animation bounds. Sampling in an easing ramp does not falsely count as a hard-plant contact.

### Responsive async editor operations

The expensive `Production QA` compilation and planted-foot planner run on a separate cancellable compute queue. Drawing preview, source save, local drafts, reopening and non-CPU-heavy editor commands remain on the existing IO lane. Edits, scene change, pause, and destruction cancel or invalidate stale computation results; a delayed result cannot overwrite newer hand edits.

The prior original source snapshot and non-destructive bake/publish protocol are unchanged. No phone Gallery permission, paid inference API, new third-party dependency, package renaming, signing-key replacement or project reset.

## Known remaining limitations

- Foot planting uses the prepared humanoid's actual **rest floor height**. It does not detect terrain, slopes or collisions from a picture.
- Gait keys are still authored templates, not motion-captured 2D acting. An IK constraint can keep a sole planted but cannot invent missing leg outlines, shoe perspectives, anatomy or credible weight transfer.
- Automatic stance target changes can still be artistically awkward for extreme custom proportions or camera shots. Edit the pose/foot contact for the shot and inspect the actual MP4.
- Only an English-text-derived viseme estimate exists in 4.5.1; there is no automatic recorded-audio phoneme alignment.
- No arbitrary-story film generation claim: professionally staged multi-shot artwork, detailed character faces, secondary animation and animation direction remain substantial tasks.

## Verification checklist

- `SketchAnimationSamplingSessionTest`: batched and single-frame samples match; native preview samples remain equivalent; author data remains unchanged; invalid frame ranges reject.
- `SketchAnimationFootworkTest`: independent attached feet, four alternating stance intervals, real exact ground coordinates and sole angles on every hard frame; blend entrance preservation; original sketch/program immutability; camera independence; run mode; duplicate and missing artwork rejection; compiled output verifies.
- `SketchAnimationComputeIsolationTest`: a slow heavy worker does not block the animation editor IO lane.
- Existing 4.5.1 Android unit tests, signed APK assembly, full native 240-frame story render and source-certificate/package-version inspection must pass.
- Physical-phone install, rendered MP4 inspection, frame pace, battery/thermal impact and artistic critique are independent acceptance gates; they have **not** been assumed complete.

Full seven-system film-production roadmap: [issue #82](https://github.com/RezoxNemesis/VideoStudio-MCP/issues/82).