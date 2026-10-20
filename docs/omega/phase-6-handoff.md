# VideoStudio OMEGA — Phase 6: Surface detail, atmosphere and visual QA

**Branch:** `phase/omega-6-visual-quality`  
**Integration target:** `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** initial additive engine prototype; phase incomplete

## Engineering slice currently delivered
OmegaVisualAudit separately counts subject-ROI pixel differences and background changes. These metrics never certify beauty.

## Verification to run
OmegaVisualAuditTest; subject-specific movement, background-dominated motion, identical frames and invalid dimensions.

Android build and regression command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17, Gradle 8.9, Android SDK 36. Check `.github/workflows/omega-nine-phase-ci.yml` on integration branch for exact workflow evidence. Never interpret a passing test as native visual acceptance.

## Mandatory next integration / full-phase gates
1. Add animated multilayer parallax, depth effects, texture imperfections, shadow/material and lighting tools.
2. Combine numeric evidence with named human-evaluated drawing quality criteria.
3. Check full-size hands, silhouettes, edges, palette and lens continuity across shots.
4. Reject camera-only fake movement and static backgrounds even with nominal 24/30 FPS.

## Current production boundary
No new native graphics compositing path installed; visual audit is independent core.

## Handoff checklist for next ChatGPT agent
- Read the live repository contract and draft PR for this branch before modifying anything.
- Preserve branch ownership, source hashes, frame clock, undo, atomic project revisions, privacy and owner safety gates.
- Implement in small tested increments; do not edit another phase's owned files.
- Post exact commit, tests, CI checks and remaining unverified native work in the phase draft PR.
- Do not merge to main, claim release, browse Gallery, change signing key or bypass NATIVE_RECOVERY_HELD.
