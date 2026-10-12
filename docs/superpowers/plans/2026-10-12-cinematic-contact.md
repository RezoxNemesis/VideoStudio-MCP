# Source-Faithful Cinematic Contact Implementation Plan

> **For agentic workers:** Use subagent-driven development with independent source ownership and root-orchestrated Gradle verification. Steps below track actual deliverables, not acceptance promises.

**Goal:** Repair the real native storyboard path so source actors and original swords can use authored contact motion, with truthful admission and evidence instead of pixel-only cinematic success.

**Architecture:** Extend the existing PR #67 rig model and native renderer rather than replacing the app or importing the divergent old UI branch. A frozen source/rig manifest feeds admission, source-textured prop rendering and an aspect-correct contact solver. Pixel QA, predicted geometry, observed native evidence and visual acceptance have separate statuses.

**Tech stack:** Java 17, Android API 29–36, Gradle 8.9/AGP 8.7.3, Media3 1.11.1, existing Android Canvas/native source-texture rendering, SQLite/private rig records, MCP v3.

**Spec:** Owner-supplied `VideoStudio_Cinematic_Animation_System_Blueprint_and_QA.pdf`, 20 pages, 2026-10-12; private original and extracted text retained outside Git in `/workspace/engineering-review/cinematic-20261012`. Earlier blueprint remains [ENGINEERING_BLUEPRINT.md](../../continuity/ENGINEERING_BLUEPRINT.md).

## Global constraints

- Preserve package `com.rezoxnemesis.videostudio.next`, original signing key/certificate, projects and native MCP identity; never uninstall or clear data.
- Preserve exact source art and both original swords; no whole-frame dissolve, procedural replacement fighters/weapons, or invented neural-provider claims.
- Native Test 1 originals and device connection are currently unavailable. Synthetic Android fixtures prove code behavior only; no substitution for authentic Test 1 acceptance.
- Required cinematic missing-provider error: `INSUFFICIENT_MOTION_PROVIDER`, with exact missing annotations before heavy work or output project creation.
- Authoritative frames use explicit asset order or persisted clip order. Filenames do not prove duplicate content or anatomical action.
- Model/QA absence is unavailable evidence, not a measured zero or passed cinematic result.
- One focused test before one full Android build; root controls Gradle runs to avoid competing compiler processes.

## Review focus

- Source bytes change while hashing or after accepted recovery; reject before touching prior outputs.
- Duplicate/foreign actor profiles, missing swords and mismatched contact endpoints; return explicit admission failure.
- Portrait aspect, unreachable IK and near-singular blade transforms; preserve physical lengths or reject, never slide silently.
- Partial annotation saves, renderer allocation failure and cancellation; preserve original media, valid records and earlier generations.
- Pixel-only movement, common-affine actor motion and missing decoded evidence; cannot manufacture cinematic acceptance.

## Task 1 — Source provenance and ordering

**Files:** `StoryboardSourceManifest.java` (new), `StoryboardBatchPlanner.java`, focused manifest/planner tests.
**Interface:** `capture(Context, Project, JSONObject selection) -> JSONObject manifest`; `verifyOwnedSources(Context, Project, JSONObject manifest)` before render/recovery. Manifest includes original asset IDs, streamed SHA-256, dimensions/orientation, explicit anchors and deterministic digest; never source credentials/locations.

- [x] Same-name different-byte and Unicode-name/source-order regression cases retained and passing.
- [x] Keep explicit order; remove filename-only identity proof. Exact-byte aliases and explicit cross-format variant groups are distinct.
- [x] Hash bounded project-owned streams, close/cancel safely, record original library without deleting duplicates.
- [x] Persist accepted manifest and verify before audit/render/recovery cleanup; preserve earlier target generations.

## Task 2 — Source annotations and contacts

**Files:** `Rig2DProfile.java`, `NativeRigAuthoring.java`, `Rig2DProjectStore.java`, `StoryboardContactPlan.java` (new), `StoryboardPoseMotion.java`, focused authoring/kinematics tests.
**Interface:** Add optional persisted `swordHilt:[u,v]` and `swordOutline:[[u,v]...]` alongside existing `swordTip`; legacy records stay readable. Shot `contactPlan` has `black`/`white` arrays of `{foot:leadingFoot|trailingFoot,start:0..1,end:0..1,anchor:[u,v]}` bound to accepted endpoint rigs. Source coordinates use one aspect-correct physical metric.

- [x] First reproduce `NativeRigAuthoring.parse` dropping a supplied sword tip.
- [x] Round-trip bounded sword/hilt annotations and reject invalid/foreign ownership before writes.
- [x] Solve explicit planted-foot intervals with two-bone IK, exact authored endpoints and reachability; no inferred stance certificate.
- [x] Make rendered skinning and any predicted landmark sampler share the same actual weighting kernel.

## Task 3 — Original prop rendering and world composition

**Files:** `NativeSourceProp2D.java` (new), `NativeStoryboardArtworkRenderer.java`, `NativeSkinnedSprite2D.java`, `StoryboardCleanPlate.java`, relevant native renderer fixtures.
**Interface:** Original sword polygon/hilt/tip defines a bounded source-textured rigid prop. Existing renderer constructor may gain one overload taking four prop sprites and contact plan; retain legacy call compatibility with explicit non-cinematic scope.

- [x] Add failing native-pixel fixtures for original colored sword contour, hand attachment and portrait-metric rigidity.
- [x] Remove procedural blade substitution from the cinematic source-art path; source masks also remove swords from the world plate.
- [x] Preserve opacity during local appearance handoff and apply camera transform equally to world, actors and props.
- [x] Render dense synthetic clash/stance frames and retain PNG/trace artifacts labelled synthetic Android evidence.

## Task 4 — Admission and human/MCP parity

**Files:** `StoryboardMotionAdmission.java` (new), `ControlService.java`, `StoryboardSegmentComposer.java`, `src/index.js`, `RigEditorActivity.java`, focused admission/route tests.
**Interface:** `inspect(source,plan,batch,profiles,motionIntent) -> Admission`, with `requireAccepted()`, structured missing requirements and frozen rig bindings. `motionIntent` distinguishes `cinematic_action`, `source_deformation`, `environmental`.

- [x] Default action-grade requests without exact authentic actor/weapon/contact annotations fail before output/job mutation.
- [x] Fix actor selection ambiguity; freeze exact rig IDs/digests and revalidate recovery without fallback.
- [x] Expose typed storyboard/rig tools through existing native actions; preserve private v3 endpoint identity.
- [x] Extend existing phone annotation canvas for saved profiles, joint/polygon editing, original hilt/tip/weapon mask and bounded contact input; no unrelated UI rewrite.

## Task 5 — Semantic evidence and causal timing

**Files:** `StoryboardMotionEvidence.java` (new), `StoryboardMotionQualityGate.java`, `StoryboardFoleySynth.java`, relevant metric/audio tests; composer integration by its owner.
**Interface:** Strict evidence ledger distinguishes `MEASURED`, `UNAVAILABLE`, `NOT_APPLICABLE` and `PASS`, `FAIL`, `INCOMPLETE`, with provenance/coverage; predicted traces never certify decoded-output observations.

- [x] Reject translation/common-affine fixtures as proof of distinct articulation.
- [x] Foot drift target `<0.015` actor height; hilt error `<=0.02`; observed audio/contact offset `<=2` video frames, using actual rational PTS.
- [x] Replace generic pixel `qualityGatePassed` claims with separate pixel, rig/provider and cinematic statuses.
- [x] Missing observed contacts, landmarks, PCM peaks, world registration or visual review remain incomplete.

## Task 6 — Verification, signing and review packet

**Files:** `android/app/build.gradle.kts`, `.github/workflows/android.yml`, verification scripts and continuity/evidence status.

- [ ] Focused regressions, full local Android (359) and Worker (306 smoke + 119 executable) suites PASS; protected APK build remains.
- [x] Build fails closed without the original persistent key; never generate fallback signing material.
- [ ] Version 372 / 3.7.2 configured; verify actual protected APK identity/hash/certificate, installed comparison blocked.
- [x] Preserve R01–R12, T01–T21, U01–U04 and M1–M6 status matrix with actual evidence or exact blockers.
- [x] Real Test 1 reproduction/install/render/Drive/thermal acceptance stays blocked until native source/device access exists; do not resume the older broad roadmap before this priority is resolved or explicitly blocked.

### Supplemental original-source handoff

The later nine-frame ZIP is preserved privately, with exact source order/hashes and dimensions verified. Its originals are JPEG-encoded despite PNG filename/format declarations; no bytes were converted. Its eight-second/192-frame reference is separate from source art. Actual native import, verified actor/contact authoring, authentic output and phone QA remain blocked; the earlier 23/13 dataset has not been supplied by this different packet.
