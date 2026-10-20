# VideoStudio OMEGA — Phase 0: Audit and contract governance

**Branch:** `phase/omega-0-audit`  
**Integration target:** `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** completed foundation audit / not a film

## Engineering slice currently delivered
Audit already documented in PR #107; this work adds the coherent nine-agent contract and failure-to-quality test matrix.

## Verification to run
Review source inventory, exact baseline refs, API contracts, signed device update constraints and deterministic complete-module CI.

Android build and regression command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17, Gradle 8.9, Android SDK 36. Check `.github/workflows/omega-nine-phase-ci.yml` on integration branch for exact workflow evidence. Never interpret a passing test as native visual acceptance.

## Mandatory next integration / full-phase gates
1. All 9 branch owners unambiguous and no overlapping production-file permissions.
2. Installed 4.8.7 vs 4.8.6-source version gap resolved by the integration owner before any APK.
3. Original signing certificate, native recovery hold and Gallery prohibition preserved.
4. Before/after frame evidence criteria written; reject compile-only film-completion claims.

## Current production boundary
No runtime hooks. Foundation audit is completed, integrated feature state is NOT implied.

## Handoff checklist for next ChatGPT agent
- Read the live repository contract and draft PR for this branch before modifying anything.
- Preserve branch ownership, source hashes, frame clock, undo, atomic project revisions, privacy and owner safety gates.
- Implement in small tested increments; do not edit another phase's owned files.
- Post exact commit, tests, CI checks and remaining unverified native work in the phase draft PR.
- Do not merge to main, claim release, browse Gallery, change signing key or bypass NATIVE_RECOVERY_HELD.
