# VideoStudio OMEGA — Phase 5: Natural acting and temporal motion

**Branch:** `phase/omega-5-natural-motion`  
**Integration target:** `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** initial additive engine prototype; phase incomplete

## Engineering slice currently delivered
OmegaMotionTimeline implements frame-pinned cubic Hermite movement, explicit locked contact and secondary damped motion.

## Verification to run
OmegaMotionTimelineTest; start/end, interpolation, foot-lock, damping, malformed key rejection.

Android build and regression command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17, Gradle 8.9, Android SDK 36. Check `.github/workflows/omega-nine-phase-ci.yml` on integration branch for exact workflow evidence. Never interpret a passing test as native visual acceptance.

## Mandatory next integration / full-phase gates
1. Timing controls for anticipation, overlapping action, recoil, follow-through and recovery.
2. Contact-aware locked surfaces + center-of-mass arc with no foot sliding.
3. Native sampled-frame local subject changes, not camera-motion-only counts.
4. Film-level acting acceptance with user-provided video reference and continuity review.

## Current production boundary
Temporal kernel only; cinematic choreography integration pending.

## Handoff checklist for next ChatGPT agent
- Read the live repository contract and draft PR for this branch before modifying anything.
- Preserve branch ownership, source hashes, frame clock, undo, atomic project revisions, privacy and owner safety gates.
- Implement in small tested increments; do not edit another phase's owned files.
- Post exact commit, tests, CI checks and remaining unverified native work in the phase draft PR.
- Do not merge to main, claim release, browse Gallery, change signing key or bypass NATIVE_RECOVERY_HELD.
