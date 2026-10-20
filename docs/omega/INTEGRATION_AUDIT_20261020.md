# OMEGA integration audit — 2026-10-20
**Classification:** PRE-MERGE ENGINEERING REVIEW / NOT A COMPATIBLE APK  
**Review branch:** `review/omega-nine-phase-integration-20261020`  
**Target for a *future* reviewed PR:** `integration/omega-nine-phases-20261020`  
**Protected stable branch:** `main`, unchanged.

## Exact phase-head provenance captured
| Phase | PR | Source commit | Pre-review PR CI |
|---|---|---|---|
| 0 | #108 | `8e32ca55607c2e2782068d522ca763f5aabd8952` | success |
| 1 | #109 | `d57093641ca89f89727ad59a62fba5f83c42fff4` | FAIL — real render-pixel test shotA/frame0 |
| 2 | #110 | `0cd0ada3bfeca47bbc86323e3e0d55ec5d104d6e` | success |
| 3 | #111 | `329badc8ad89cfc89d5e35847067453dbfc8ede2` | success |
| 4 | #112 | `c4571ab18bc78d3cfd7166d039cde9b6d5597ddc` | FAIL — ownership check compared against advancing base, false positive |
| 5 | #113 | `2fae1e71587b43de5e048b6d7adab62a911db431` | success |
| 6 | #114 | `8efdae3e1f527f59d470887eb7d3d197dad87bd6` | success |
| 7 | #115 | `58e98be8deb149f13ed7a32855d9bb72702986cb` | FAIL — same false-positive ownership check |
| 8 | #116 | `15a1302a04cdc6c0e2a6d1c6f3412d198aea4873` | success |

Every phase was forked from a common source that already contained the earlier Phase 1 single-sketch PR #107, not from the installed Android 4.8.7 release. This review branch incorporates the pinned phase snapshots, *not* the mutable latest phase tips and not prior 4.8.7 GLB source PR #106.

## Integration-owner corrections, not bypasses
1. `tools/omega/check_ownership.py` now compares `merge-base(base,head)...head`, avoiding falsely attributing newer base-branch CI files to Phase 4/7. The original allowlist and failure semantics remain.
2. `tools/omega/check_integration_review.py` restricts this review PR to exactly the eight engine classes, eight tests, phase-specific docs, bounded integration audit and its CI; changing SketchProjectStore/SketchEditOps/SketchStudioActivity, signature, recovery or original projects still fails.
3. Phase 1's failing Robolectric visual assertion is **not removed or relaxed**. The integration candidate explicitly enables native Robolectric Canvas graphics mode so the pixel test can measure genuine rendered changes; this hypothesis must be confirmed by the combined Android CI. If it still fails, stop and investigate.
4. Separate unsigned `omega-premerge-native-ci.yml` compiles all eight engines together and runs the complete Android unit test suite. It never signs or installs an APK.

## Native readiness assessment — limitations
- **Phase 0:** audit completed.
- **Phase 1:** single-sketch master path editing exists in the source; compound/cross-sketch work remains read-only planning/staging plus simulated CAS. Cannot commit or undo multiple persisted sketches atomically without approved SketchProjectStore transaction + durable membership schema + UI. Required issue #117.
- **Phase 2:** reference landmark and native-format operation helpers exist, but in-app authoring/preview entrypoints and source-provenance UX need native integration.
- **Phase 3:** robust editable curve fitting exists, but no touch-editor tool routes to it.
- **Phase 4:** IK/skin and read-only native preview helpers exist, but character rig painting/deformation engine not wired.
- **Phase 5:** temporal/contact solver exists, but not applied in a real native animation editor/render sequence.
- **Phase 6:** authored parallax/light/visual metrics exist, but no native composition controls or actual artistic user review.
- **Phase 7:** audio lane/waveform engine exists, but no visible Sketch+Editor audio waveform lane or verified audible exported MP4.
- **Phase 8:** cache invalidation and recovery decision engine exists, but not connected to actual native render job/recovery state machine. NATIVE_RECOVERY_HELD must remain owner controlled.

Thus a green combined Java build is evidence of *source compatibility* only, not proof of nine complete product features.

## Mandatory release blockers
1. All phase snapshots pass a single Android native compile/unit test run, including strict visual assertions.
2. Integration owner must review the shared Android entrypoints, durable cross-sketch SQLite transaction and undo, project serialization, render/audio adapters, and touch UX. No auto-wiring new APIs or bypassing locks.
3. Reconcile PR #106's 4.8.7 source and #107's shared SketchStudioActivity/SketchEditOps changes; verify installed device's real versionCode and pinned signing identity.
4. Real on-device three-shot artwork-propagation test with 100+ linked instances, frame-level visual QA and local override/undo checks, plus actual narrated synced exported MP4.
5. Only after verified Android build, signed continuity, owner consent and native device acceptance create the separately named versioned release APK candidate.

## Decision
**Safe action:** stage the eight engines and their tests in an isolated review branch; run CI; fix actual failures; open a draft PR to integration for owner review.  
**Unsafe action:** merge failing phase PRs blindly, alter production safety guards, replace signing key, mark app updated, install on the owner phone or claim film-quality completion from source-only tests.
