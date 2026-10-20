# VideoStudio OMEGA — Phase 1: Compound Master Assets + Cross-Shot Propagation

**Owner branch:** \`phase/omega-1-linked-art\`  
**Target:** \`integration/omega-nine-phases-20261020\`  
**Blueprint:** \`docs/omega/NINE_PHASE_MASTER_CONTRACT.md\`  
**Status:** **Algorithm and staging engine implemented; native multi-sketch commit, visual on-device acceptance and release NOT complete.**

## Verified dependency baseline
- Draft PR #107 is the existing in-sketch \`SketchMasterAssets\` engine. It supports canonical linked Bézier paths, \`master_create\`, \`master_instance\`, \`master_patch\`, local overrides, detach, locking and existing SQLite single-sketch undo/receipts. Do not rewrite it or merge blindly.
- Draft PR #106 represents the 4.8.7 source path and has shared \`SketchStudioActivity\` changes. PR #104 has Animate timeline changes in \`SketchEditOps\`, capabilities and activity. They require integration-owner reconciliation.
- Android device is reportedly 4.8.7; the P1 linked master base comes from 4.8.6. Current P1 changes are not installed on the owner device.

## What this phase agent implemented in the owned modules
\`OmegaMasterDependencyIndex.java\` now has:
- Existing \`Link\` / \`Preview\` retained for backwards compatibility.
- \`PartChange\`: exact master ID, expected version, canonical source artwork SHA-256 and defensive copy of a bounded path/material patch.
- \`CompoundRequest\`: group ID, project ID, global operation ID, ordered distinct parts and the exact registered-sketch membership ledger. Missing membership is a blocker, not a reason to partially update.
- \`previewCompound\`: read-only scan of existing native \`SketchDocument\` payloads with source hash/version/lock checks and deterministic affected-object counts. Maximum 32 master parts, 24 sketches, 4096 affected links and 16 MiB total snapshot bytes.
- \`stageCompound\`: clones all affected documents, reuses \`SketchMasterAssets.apply\` for every part, validates \`SketchDocument\`, pins original sketch hashes/revisions, checks no-op patches and verifies local frame, exposure, transform, keyframes and override continuity. The operation is not committed by preview/staging.
- \`PreparedBatch\`: defensive immutable boundaries and deterministic global group fingerprint. \`commitAtomically(null)\` refuses with \`OMEGA_ATOMIC_NATIVE_ADAPTER_REQUIRED\`, deliberately prohibiting an unsafe loop of per-sketch mutations.
- \`AtomicBatchCommitter\`: a narrow, unimplemented native integration boundary for a store-managed single-SQLite-transaction commit, exact-idempotent group history and atomic multi-shot undo.

\`OmegaMasterDependencyIndexTest.java\` now exercises:
- Former single-master dependency index tests.
- Three independent native-format sketches with a two-part rose: **106** total linked paths; different frame exposure, local motion keys, and an intentional shot-specific petal color.
- Compound source changes, local override retention, 0/25/50/75/100% frame sampling through the real \`SketchFrameRenderer\` under Robolectric.
- Locked layer refusal across shots, missing membership, version and SHA mismatches, duplicate parts, malformed patch rejection, staged copy immutability and a simulated atomic CAS adapter.
- The simulated CAS is only a deterministic test double; it is **not** a substitute for persisted SQLite multi-sketch undo or Android device verification.

## Shared-code integration approval is a hard blocker
**Integration-owner proposal:** #117 \`OMEGA P1 integration review: atomic multi-sketch master edits + safe compound UI\`.

An authorized integration owner must first approve the proposals for **\`SketchProjectStore\`, \`SketchDocument\`, \`SketchEditOps\`, capabilities, and \`SketchStudioActivity\`**. The actual production adapter must take one SQLite write lock, pin *all* sketch membership/revisions/source hashes, preserve drawing sessions and owned references, stage each target, then save all documents/history/live events with one group receipt and commit once. Any error must roll back every sketch. Group undo/redo must also be atomic. Never call \`mutate()\` one by one across sketches.

Cross-shot **placement** requires durable registration of group member paths/masters in new sketch documents, explicit user-controlled local transforms, and serialized import/export linkage, not an untracked per-frame clone. No new independent source-of-truth database.

## Android acceptance gates before declaring full Phase 1 complete
1. Integration-owner-reviewed native adapter and UI entrypoints; safe reconciliation with #104/#106.
2. Full \`cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace\`, Android SDK 36, Java 17 and the focused \`OmegaMasterDependencyIndexTest\` suite passing on integration source.
3. Real SQLite test: edit 100+ compound instances over ≥3 distinct sketches, introduce a later-target injected fault and prove **all-or-none rollback**, then retry exact op ID and group undo/redo.
4. Real-device visual proof on owner-approved test project at exact frames 0/25/50/75/100%; confirm distinct posed character geometry and local overrides; show that changing one master updates all linked shots.
5. Preserve pre-existing sketches, signing continuity, recovery hold and no Gallery access; validate compatibility with Android 4.8.7 before release candidate.

## Phase-agent boundary
Only the two owned \`OmegaMasterDependencyIndex*.java\` files and this handoff document were edited in this phase PR. No shared native production file was touched. Do not claim a phase-ready APK, native UX, or improved drawing quality until reviewed integration and real on-device evidence exist.
