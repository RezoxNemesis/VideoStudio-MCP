# VideoStudio OMEGA — Phase 1: Canonical master asset and propagation

**Branch:** `phase/omega-1-linked-art`  
**Integration target:** `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** initial additive engine prototype; phase incomplete

## Engineering slice currently delivered
OmegaMasterDependencyIndex supplies hashed/revision-pinned deterministic impact preview across sketches. PR #107 already supplies native one-sketch linked path edits, rollback, override and detach.

## Verification to run
OmegaMasterDependencyIndexTest and SketchMasterAssetsTest, plus 100-instance mixed-frame replay, source hash mismatches, locked-layer atomicity.

Android build and regression command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17, Gradle 8.9, Android SDK 36. Check `.github/workflows/omega-nine-phase-ci.yml` on integration branch for exact workflow evidence. Never interpret a passing test as native visual acceptance.

## Mandatory next integration / full-phase gates
1. Design compound master artwork with related shapes, groups and local transforms.
2. Add persistent cross-shot dependency index tied to source/target revisions and an all-or-none project transaction or fail-closed staging.
3. Maintain local keys, exposure, overrides, user locks and undo through propagation.
4. Visual on-device artwork update across at least three practical shots and rollback proof.

## Current production boundary
Cross-shot atomic patch is NOT wired; additive preview model only.

## Handoff checklist for next ChatGPT agent
- Read the live repository contract and draft PR for this branch before modifying anything.
- Preserve branch ownership, source hashes, frame clock, undo, atomic project revisions, privacy and owner safety gates.
- Implement in small tested increments; do not edit another phase's owned files.
- Post exact commit, tests, CI checks and remaining unverified native work in the phase draft PR.
- Do not merge to main, claim release, browse Gallery, change signing key or bypass NATIVE_RECOVERY_HELD.
