# Project OMEGA — M3 PhysicsBrain: bounded two-actor arm contact

**Status:** source-only, staged; **not an Android APK release**. This change stacks on the unmerged M2.1 branch. No phone Gallery inspection, existing project writes, user media upload, native MCP command, Cloudflare deployment, signer change or main-branch merge.

## The specific native film problem

The previous Rooftop Standoff film had a measured **32.67 source-canvas pixel** separation between Vale's and Rook's wrist joint centers at frame 16. The existing interaction cue allowed an 80px gap. A green encoder, good PNG hashes and animated pixels did not make that a convincing close-contact moment.

## Actual animation-authoring change

`SketchAnimationContactDirector.refinePairedWristCue()` performs deterministic, bounded contact pose adjustment. It needs an already-authored cue, two independent rigs and an **existing exact pose key** at the requested frame. It reads the trusted native `SketchAnimationCompiler.sample()` world-space joint coordinates and the wrist's parent/grandparent chain. A damped 2D Jacobian solver adjusts **only two arm joint rotation keys per actor**; each joint is bounded to ±22° relative to its artist-authored value, with maximum six-degree per-iteration steps.

All character root offsets, IK foot targets, camera, source vector objects, dialogue and other pose keys remain unchanged. The resulting program is a cloned editable JSON program. The final pose is **independently resampled by the authoritative native compiler**, and original hard-contact diagnostics are checked before the edit is admitted. If the authored arm geometry cannot reach the requested wrist gap inside the stated bounds, the method rejects the change and keeps the original program untouched.

In the existing separate Rooftop Standoff example only, the new director is used to reduce the frame-16 wrist gap to **at most 6px**, and the authored interaction cue is tightened from 80px to **8px** so subsequent compilations cannot silently reintroduce the old problem. The native impact flash midpoint is recomputed from the **new** real joint positions. Smoothly interpolated existing neighboring keys form the approach and recovery; no fake AI interpolation or slideshow motion is inserted.

This is not a general-purpose collision solver. A wrist-center proximity constraint **does not prove baton blades touch, silhouettes avoid intersections, weight transfers look real or that an animator would approve the action**.

## Native CI and reproducible acceptance

On the isolated review branch:

1. Run 50 Node tests across M0/M1/M2/M2.1/M3, including a synthetic 100-frame native-trace guard for fake improvement, foot slip, missing poses, false source checks and unnatural one-frame jumps.
2. Run targeted Android Robolectric tests, including `SketchAnimationContactDirectorTest` and the original duel/story native render tests. The helper tests verify the actual compiler sees a tightened wrist gap while four hard-locked feet remain planted; malformed cues and invalid targets must fail without mutating the source.
3. Native renderer generates all 100 duel frames and 240 story frames, then FFmpeg encodes them. The existing M1/M2/M2.1 audits re-verify artwork, native PNG hashes, the MP4, motion proxies and full authored joint traces.
4. `scripts/omega-physics-brain.mjs --root android/app/build/sketch-duel-film-proof` verifies the M2.1 trace provenance, actual wrist gap at frame 16, authoring report, eight-pixel cue, bounded arm corrections, the real solver's contact errors and wrist step continuity. A failed audit blocks the encoder before it deletes source PNGs.
5. Retain `duel-stage-metrics.json`, `omega-physics-brain.json`, the source and exact pose program, the native trace, joint/film audits, MP4, contact sheet and JUnit logs in the GitHub Actions artifact.

**Accept M3 engineering only** if the independent measured gap is ≤6px, gap reduction ≥15px, the native hard foot error is <0.02px, and original Robolectric and M0–M2.1 tests still pass. `qualityDecision` remains `MANUAL_REVIEW_REQUIRED`; the owner must still watch the actual video and test physical Android playback before release.

## Remaining after M3

More professional hand/weapon geometry, silhouette/mesh collision, physically grounded torso weight, anticipation/follow-through beyond keyed cutout poses, expressive linework, cloth, audio and camera acting, and installed Realme performance remain unfinished. A closer wrist does not solve those on its own.
