# VideoStudio OMEGA — Phase 4: Hybrid skeletal and deformable 2D rig

**Branch:** `phase/omega-4-hybrid-rig`  
**Integration target:** `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** initial additive engine prototype; phase incomplete

## Engineering slice currently delivered
OmegaTwoBoneIK solves bounded bone lengths and explicit elbow bending, including unreachable target clamping.

## Verification to run
OmegaTwoBoneIKTest; reachable/too-far/coincident targets and bone-length invariants.

Android build and regression command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17, Gradle 8.9, Android SDK 36. Check `.github/workflows/omega-nine-phase-ci.yml` on integration branch for exact workflow evidence. Never interpret a passing test as native visual acceptance.

## Mandatory next integration / full-phase gates
1. Attach deformable mesh/cel attachments to editable bone/joint hierarchy with stable IDs.
2. Correct prop grip, foot plant, shoulder/arm gesture and pose/contact across three camera angles.
3. Prevent discontinuity, joint stretch, clipping and broken layer transforms.
4. Verify actual native frame render and undo/multi-frame preservation.

## Current production boundary
Only 2-bone kinematic engine; no deployed deform rig or UI yet.

## Handoff checklist for next ChatGPT agent
- Read the live repository contract and draft PR for this branch before modifying anything.
- Preserve branch ownership, source hashes, frame clock, undo, atomic project revisions, privacy and owner safety gates.
- Implement in small tested increments; do not edit another phase's owned files.
- Post exact commit, tests, CI checks and remaining unverified native work in the phase draft PR.
- Do not merge to main, claim release, browse Gallery, change signing key or bypass NATIVE_RECOVERY_HELD.
