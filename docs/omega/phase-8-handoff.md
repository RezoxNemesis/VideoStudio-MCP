# VideoStudio OMEGA — Phase 8: Incremental render, state recovery and release QA

**Branch:** `phase/omega-8-render-recovery`  
**Integration target:** `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** initial additive engine prototype; phase incomplete

## Engineering slice currently delivered
OmegaRenderPlan hashes content/revision/frame, coalesces changed frame ranges and rejects resume when owner recovery hold is active.

## Verification to run
OmegaRenderPlanTest; stable render keys, dirty range merging, stale source checkpoint, NATIVE_RECOVERY_HELD.

Android build and regression command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17, Gradle 8.9, Android SDK 36. Check `.github/workflows/omega-nine-phase-ci.yml` on integration branch for exact workflow evidence. Never interpret a passing test as native visual acceptance.

## Mandatory next integration / full-phase gates
1. Wire selective cache invalidation to actual native renderer without modifying originals.
2. Crash/force-stop/restart test using owner-controlled resume and protected prior project state.
3. Benchmark p50/p95 render and editing costs on Android; no invented speed multipliers.
4. Reconcile 4.8.7 installation, monotonic versionCode, original signer and final signed APK after explicit approval.

## Current production boundary
No new recovery behavior enabled; guard remains owner-controlled and production renderer unchanged.

## Handoff checklist for next ChatGPT agent
- Read the live repository contract and draft PR for this branch before modifying anything.
- Preserve branch ownership, source hashes, frame clock, undo, atomic project revisions, privacy and owner safety gates.
- Implement in small tested increments; do not edit another phase's owned files.
- Post exact commit, tests, CI checks and remaining unverified native work in the phase draft PR.
- Do not merge to main, claim release, browse Gallery, change signing key or bypass NATIVE_RECOVERY_HELD.
