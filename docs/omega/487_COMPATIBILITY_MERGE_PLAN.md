# OMEGA Integration Blocker: 4.8.6 Phase-1 work vs installed 4.8.7
**Recorded:** 2026-10-20. No source merged, APK installed or native project modified by this plan.

## Verified evidence
- Owner's VideoStudio Android native v3 agent is online and reports `appVersion=4.8.7`.
- PR #107 `feat/video-studio-master-linked-artwork-p1-20261019` is draft, unmerged, based on `release/v486-source-base-20261019`. Implements one-sketch linked vector paths, versioned masters, undo and preview.
- PR #106 `feat/sketch-3d-cel-v487-20261019` is draft, unmerged, based on the same 4.8.6 source branch. Its `android/app/build.gradle.kts` sets `versionName="4.8.7"`, `versionCode=490` and brings GLB→editable Sketch cels.
- Both PRs modify `SketchStudioActivity.java`; direct blind merge would risk losing either the linked-master menu or GLB capture controls. PR #104 (native Animate timeline) also modifies `SketchEditOps.java`, `SketchCapabilities.java` and `SketchStudioActivity.java`.
- Separate new OMEGA phase branches are based on PR #107 ancestry; **none** modifies the shared Android production files in this first slice.

## Strict safe reconciliation procedure
1. Record current owner device app versionCode/certificate fingerprint and a project backup/snapshot through authorized native app surfaces; do not browse Gallery or bypass recovery guard.
2. Create a fresh integration-owner-only `integration/omega-487-compatibility` branch from a reviewed merge base (or an approved 4.8.7 source commit). Never fast-forward `main` merely to resolve the backlog.
3. Three-way compare PR #106, PR #107 and PR #104. Preserve each independent native feature:
   - `SketchStudioActivity.java`: real GLB-to-cel screens + linked-master actions + Animate timeline actions without overwriting touch menu IDs or action dispatch.
   - `SketchEditOps.java`: maintain idempotent revisioned edit protocol, shape/timeline ops and all `master_*` commands with a single authoritative switch/dispatch.
   - `SketchCapabilities.java`: combine capability additions without malformed JSON, duplicate keys or false installed-version claims.
   - `SketchDocument.java`: validate optional master metadata while preserving older documents and source/reference provenance.
   - `build.gradle.kts`: preserve the pinned signing configuration; update monotonic versionCode only after owner approval.
4. Reject any merge causing duplicate native operations, silent source hash changes, broken rollback, invalid note ordering or changed old-sketch serialization.
5. Run full Android compilation and unit tests for all prior PRs plus the combined OMEGA engine smoke test.
6. In a native disposable test sketch, demonstrate master patch propagation and local animation retention alongside GLB cels and timeline edits. Test Undo/Redo, locked-layer refusal, source hash and operation replay.
7. Only after passing CI and real-device visual/audio review, create a **separate** owner-approved installation candidate with original signing identity and a strictly increasing code (greater than 490 and any installed versionCode).

## How other agents must behave
Phase 0–8 agents continue building exclusively in their own branches. They must not solve this compatibility blocker by modifying shared files or pushing arbitrary APKs. The integration owner alone controls the eventual reviewed reconciliation PR. Current OMEGA docs and engine implementations are **not** the same as shipped Android features.
