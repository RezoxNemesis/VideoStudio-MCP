# VideoStudio OMEGA — Phase 3: Contour-first precision sketch tools

**Branch:** `phase/omega-3-sketch-construction`  
**Integration target:** `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** initial additive engine prototype; phase incomplete

## Engineering slice currently delivered
OmegaBezierFitter derives editable cubic Bézier segments from bounded normalized anchor arrays; adds curve handles instead of rectangles.

## Verification to run
OmegaBezierFitterTest; open/closed curves, duplicate control points, limit checks, curvature continuity.

Android build and regression command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17, Gradle 8.9, Android SDK 36. Check `.github/workflows/omega-nine-phase-ci.yml` on integration branch for exact workflow evidence. Never interpret a passing test as native visual acceptance.

## Mandatory next integration / full-phase gates
1. Expose pen/node/handle editing, symmetry, line-weight pressure/color layers, local undo and zoom precision.
2. Use reference guides for anatomy/hand/head/body constraints while leaving output genuinely editable.
3. Reject robotic/box-only results with visual side-by-side target and hand/pose test corpus.
4. Benchmark stroke count, undo integrity and interactive latency on device.

## Current production boundary
Curves not yet accessible through native touch tools or material pipeline.

## Handoff checklist for next ChatGPT agent
- Read the live repository contract and draft PR for this branch before modifying anything.
- Preserve branch ownership, source hashes, frame clock, undo, atomic project revisions, privacy and owner safety gates.
- Implement in small tested increments; do not edit another phase's owned files.
- Post exact commit, tests, CI checks and remaining unverified native work in the phase draft PR.
- Do not merge to main, claim release, browse Gallery, change signing key or bypass NATIVE_RECOVERY_HELD.
