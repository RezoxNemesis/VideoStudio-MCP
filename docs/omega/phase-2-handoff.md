# VideoStudio OMEGA — Phase 2: Reference breakdown and guide extraction

**Branch:** `phase/omega-2-reference-guides`  
**Integration target:** `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** initial additive engine prototype; phase incomplete

## Engineering slice currently delivered
OmegaReferenceGeometry aligns user-authored landmarks by weighted similarity transform; no semantic understanding or hidden-view hallucination.

## Verification to run
OmegaReferenceGeometryTest; source-bound normalized points, zero/NaN and degenerate alignment rejection.

Android build and regression command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17, Gradle 8.9, Android SDK 36. Check `.github/workflows/omega-nine-phase-ci.yml` on integration branch for exact workflow evidence. Never interpret a passing test as native visual acceptance.

## Mandatory next integration / full-phase gates
1. Bind guide landmarks to exact imported reference hash, dimensions, EXIF orientation and provenance.
2. Expose manual correctable landmarks, guides and contour placement in native Sketch Studio.
3. Compare visible full-resolution result to the reference; no pasted source image masquerading as drawn frames.
4. No unconsented Gallery enumeration, source copying into original-strokes policy or unseen-view invention.

## Current production boundary
Pure guide alignment only; Android guide UI and source binding remain to integrate.

## Handoff checklist for next ChatGPT agent
- Read the live repository contract and draft PR for this branch before modifying anything.
- Preserve branch ownership, source hashes, frame clock, undo, atomic project revisions, privacy and owner safety gates.
- Implement in small tested increments; do not edit another phase's owned files.
- Post exact commit, tests, CI checks and remaining unverified native work in the phase draft PR.
- Do not merge to main, claim release, browse Gallery, change signing key or bypass NATIVE_RECOVERY_HELD.
