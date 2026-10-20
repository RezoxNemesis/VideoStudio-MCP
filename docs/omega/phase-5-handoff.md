# VideoStudio OMEGA — Phase 5 natural acting and temporal motion

**Owned branch:** `phase/omega-5-natural-motion`  
**Draft PR:** [#113](https://github.com/RezoxNemesis/VideoStudio-MCP/pull/113) → `integration/omega-nine-phases-20261020`  
**Master contract:** [NINE_PHASE_MASTER_CONTRACT.md](NINE_PHASE_MASTER_CONTRACT.md)  
**Status:** expanded deterministic **engine**, still **NOT native-integrated, visually accepted or released**.

## Owned implementation (exclusive paths)

- `android/app/src/main/java/com/rezoxnemesis/videostudio/OmegaMotionTimeline.java`
- `android/app/src/test/java/com/rezoxnemesis/videostudio/OmegaMotionTimelineTest.java`
- `docs/omega/phase-5-handoff.md`

No existing `SketchDocument`, `SketchEditOps`, `SketchProjectStore`,
`SketchAnimationCompiler`, editor activity, renderer, signer, recovery guard,
integration branch or other phase's owned files were changed.

### Usable Java integration contract

`OmegaMotionTimeline` is a package-local additive engine (no storage, Android
`Context`, network, image I/O, authorization or phone commands).

1. `Key`, `Pose`, `Track`: finite frame-indexed Hermite rotations,
   **monotone limited** x/y interpolation, no positional overshoot, sorted
   keys, binary-search frame evaluation and endpoint holds. When two successive
   keys lock contact, x/y must be identical and stay pinned throughout; angle
   can rotate smoothly about that stationary pivot.
2. `ActingSpec` and `ActingClip`: editable **anticipation → drive/COM
   arc → impact → recoil → recovery** beats with explicit durations and
   root/angle offset parameters. Impacts trigger independent, exponentially
   damped, release-faded follow-through angle (a candidate joint-local channel).
   `Sample.root` changes in scene space; this is **not** camera motion.
   `authoredRootKeys()` exposes five editable knots.
3. `Contact`: user-authored named joint, normalized world anchor, local-rest
   end-effector coordinate, maximum correction, full-plant frame window,
   optional <=12-frame smooth acquire/release windows (matching the current\n   native compiler limit). A full-plant interval
   preserves the anchor to numerical precision while the root moves/rotates.
   `ContactSample.localTargetX/Y` is the explicitly computed rig-local IK
   target; `worldX/Y` is the actual projected endpoint, `anchorX/Y` is its
   desired plant and `weight` identifies acquire/release vs `planted`.
   Unreachable targets and conflicting windows for the same joint fail closed.
4. `sampleAtPresentationUs`: delegates FPS (24/30/60) conversion to
   `NativeFrameClock`, never a second clock. `sample(double frame)` also
   supports deterministic subframe review.
5. Budgets: 1200 authored frames max (0..1199), 256 keys per track,
   16 contacts per clip, finite bounded positions/velocities/angles, validated
   lengths and unique ordered keys. No project schema or revision change.

### Fixed synthetic fixture (unit evidence, NOT rendered phone frames)

30 FPS; root start (0.30, 0.55, 0°), target (0.70, 0.55, 35°);
10-frame drive with 6-frame anticipation, 6-frame recoil, 10-frame recovery;
0.05 scene-unit anticipation sink and 0.07 scene-unit drive lift.
The expected checkpoints are authored frames **10 / 18 / 26 / 34 / 42**
(0 / 25 / 50 / 75 / 100% of its 32-frame span).
Frame 16 has counter-motion, frame 26 exact target/impact,
frame 32 recoil before frame 42 final recovery. The two-sole test pins
independent world contacts from frames 12..38 despite changing root and
rotation; the eased-contact test checks fade weights at frames
12/14/16/30/32/34. These prove **mathematical scene-space trajectories**,
not source-art anatomy, rendered joint positions or human-visible acting.

## Validation

- Command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace`
- Java 17, Gradle 8.9, Android SDK 36.
- Test class: `com.rezoxnemesis.videostudio.OmegaMotionTimelineTest` (13
  authored regression tests for beats, clocks, world-pin, easing, bounds,
  immutability and rollback-by-rejection).
- CI gate: [OMEGA Nine-Phase Compile and Regression Gate](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/workflows/omega-nine-phase-ci.yml).
- GitHub Actions is authoritative for this branch's actual compile/test
  outcome. **Do not record a pass until that exact commit has a green job.**
- Do not claim real-device p50/p95, a native frame inspection or an APK.
  Source history 4.8.6 versus reportedly installed 4.8.7 remains an
  integration/release compatibility gate.

## Integration-owner change proposal — barrier I1 (NOT implemented here)

Do **not** edit common production files from a phase branch. An integration
owner should review and separately implement the following adapter:

1. Add an opt-in **Natural Acting** action in the existing
   `SketchAnimationMotionLibrary`/native animation editor, not a second
   document store. It must require a prepared rig with distinct bound
   editable parts and approved source revision. Do not guess missing
   joints, hand outlines or unseen anatomical views.
2. Map `ActingClip.authoredRootKeys()` and/or sampled `Sample.root` to
   the current animation program's `poses[].frame/rootX/rootY/rotations`.\n   The five sparse knots alone do **not** retain the root lift arc; bake\n   additional explicit DRIVE midpoint/root samples within the original\n   4096-key document budget. `root.angle` needs an owner-selected bone\n   rotation channel; no undocumented whole-canvas angle.
   Map `Sample.followThroughAngle` to an **explicitly chosen** head/arm
   joint, never to the whole picture or camera. Blend only with the owner's
   approved authored pose interval; reject overlaps, locked layers, key/object
   overflow and any serialization/revision mismatch. Preserve undo/redo,
   per-instance motion and independent master-asset overrides.
3. Translate `ContactSample.localTargetX/Y` into the Phase 4 rig's
   bone-length-checked solver and the native compiler's existing contact
   validation. Preserve foot **world** anchors through the fully planted
   interval and gradually release with `weight`. Reject unreachable IK
   instead of silently stretching, moving the floor, relocating the root or
   substituting camera translation. Retain authentic editable contours.
4. Show five-beat markers, root trajectory, contact holds and
   follow-through controls in native Sketch Studio; preview separately
   before persistent save. Feature flag **OFF** by default until reviewed.
   No schema expansion without `videostudio.sketch.v1` round-trip tests.
5. Integration testing: full Android regression, undo/locked layer/legacy
   project round-trip, Phase 4 contact tracking, multi-character hand/prop
   action, scene-space ROI analysis, 100 linked objects, p50/p95 timing on
   actual device, and 0/25/50/75/100% **native** rendered frames.
   Compare reference posture, weight, foot slide, silhouette, head/hand
   follow-through, anticipation and recovery with human visual review.
   Verify phone build/signer/version and record real final MP4. Camera-only
   image changes must not satisfy this gate.

## Honest exit status / blockers

The pure Phase 5 motion engine and its native-facing contract are developed
on this branch. **The requested full-phase gate is blocked at integration
barrier I1:** no shared-file edit authorization, no integration adapter,
no Android UI, no installed engine, no native film samples, no owner-approved
device-performance/visual review and no 4.8.7 compatibility proof. These
are actionable next steps, **not** implied successes.

Never merge into `main`, bypass `NATIVE_RECOVERY_HELD`, use Gallery,
touch signing identity or install an unverified APK. Preserve draft PR #113
against the integration branch and carry failed CI evidence forward.
