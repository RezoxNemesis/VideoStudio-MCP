# VideoStudio OMEGA — strict nine-phase parallel engineering contract
**Contract ID:** VS-OMEGA-2026-10-20-v1  
**Repository:** `RezoxNemesis/VideoStudio-MCP`  
**Base engineering ancestry:** `feat/video-studio-master-linked-artwork-p1-20261019` (PR #107, 4.8.6-source baseline)  
**Stable integration branch:** `integration/omega-nine-phases-20261020`  
**Current installed device:** reported 4.8.7; compatibility NOT yet verified.  
**This is an implementation contract, not an announcement that all phases are finished or an APK has shipped.**

## 0 — Mission and failure acceptance
Deliver visually credible, reference-guided, editable 2D film authoring through existing Android VideoStudio and its owner-authorized native MCP. Reject the following as completion: box/rectangle placeholder character art, inaccurate hands/poses, static background sold as animation, frozen subjects with camera-only movement, isolated copied objects that drift across frames, narration generated but missing from MP4, and success declared by compile-only tests.

A scene is accepted only with native project/sketch provenance, no Gallery traversal, human-visible frames at multiple temporal positions, continuity/acting review, audio playback/track evidence, and artifact integrity. Real-world artistic accuracy cannot be proven by an algorithmic score alone.

## 1 — Shared architectural contract: freeze before parallel coding
- **Source of truth:** Android `SketchDocument`, `SketchEditOps`, `SketchProjectStore`, `NativeFrameClock`, existing native rendering and storage. Do not introduce a second authoritative project database.
- **Schema stability:** `videostudio.sketch.v1` stays valid; optional extension keys must be validated explicitly and old files round-trip. Preserve 4 MiB, 32 layers, 4096 objects, 131072 points, 4096 keys, 1200 frames, 256–2048 pixel bounds unless a separately reviewed migration proves otherwise.
- **Space/time:** All drawing coordinates normalized to [0,1] unless the API explicitly uses pixel coordinates; 24/30/60 FPS with `NativeFrameClock` as the only conversion authority. Require finite numerics and bounded arrays; no silent NaN, infinity or overflows.
- **Master/instance semantics:** Canonical versioned artwork + unique instance identity. Geometry/material patch never silently changes per-instance transform, frame exposure, motion keys, user overrides or locked layers. A proposed cross-sketch linkage must index the exact content hash and revisions, preview affected projects, and stage all-or-nothing or fail closed. The existing PR #107 linked paths remain the baseline; they do not yet support global compound characters.
- **Rig/animation semantics:** Scene-coordinate landmarks, editable joints, explicit bone lengths, local vs global deformation, unique layer/object references and time-pinned poses. Surface locks, floor contacts, hand/object constraints; avoid geometry changes through hidden inference.
- **Audio:** Store source/provenance/timing, make waveforms and audio lanes visible in both Sketch and Editor before claiming sync. Audible final file + native track inventory are required for a release.
- **Rendering:** Keep owner-controlled `NATIVE_RECOVERY_HELD` and the current signing certificate intact. No bypass, replacement key, Gallery enumeration, secret/key modification, or destructive project rewrite.
- **Performance:** Inspect edit propagation at 100 linked objects and frame sampling across 0/25/50/75/100% temporal positions. Compare p50/p95 on a real device; never invent numbers or assume 50x improvement.
- **Versioning:** The source branch is 4.8.6 while installed version reportedly 4.8.7. DO NOT publish installation APK or bump versionName/versionCode before reconciling PR #106, schema compatibility, preserved signing identity, monotonic versionCode and device-owner approval.

## 2 — Single-owner module boundaries
Each of nine agents receives **one phase branch and owned paths**. No phase agent may directly edit `main`, `integration/*`, signing configuration, `SketchDocument`, `SketchEditOps`, `SketchProjectStore`, `NativeStartupGuard`, `NativeSpeechEngine` or existing production render entrypoints without filing an integration-change proposal and receiving explicit integration-owner review. Experimental Java pure-engine slices are additive and intentionally not yet automatically wired into production.

| Phase | Branch | Exclusive module / tests | Concrete first slice | Full acceptance gate |
|---|---|---|---|---|
| 0 Foundation | `phase/omega-0-audit` | `docs/omega/phase-0-*.md` | Reproducible source inventory and failure-to-test map | Explicit baseline, risks, build constraints and owners recorded |
| 1 Master assets | `phase/omega-1-linked-art` | `OmegaMasterDependencyIndex.java`, `OmegaMasterDependencyIndexTest.java` | Bounded deterministic dependency impact planning beyond single sketch | Compound/cross-shot versioned assets, atomic preview, lock/rollback, original keys preserved, real device |
| 2 Reference guides | `phase/omega-2-reference-guides` | `OmegaReferenceGeometry.java`, `OmegaReferenceGeometryTest.java` | Deterministic normalized landmark alignment and rejection | End-to-end reference trace vs target with user-verified landmarks, source binding and no invented hidden views |
| 3 Precision sketch | `phase/omega-3-sketch-construction` | `OmegaBezierFitter.java`, `OmegaBezierFitterTest.java` | Smooth editable Bézier contours from guided points | Hands, heads, full-body views and material precision visibly better than primitives |
| 4 Hybrid rig | `phase/omega-4-hybrid-rig` | `OmegaTwoBoneIK.java`, `OmegaTwoBoneIKTest.java` | Bounded 2-bone IK with stretch prevention | Editable bones + deformable 2D overlays, planted contacts, 3 angles, prop constraints |
| 5 Natural motion | `phase/omega-5-natural-motion` | `OmegaMotionTimeline.java`, `OmegaMotionTimelineTest.java` | Eased frame interpolator and follow-through motion | Anticipation, weight, arc, recovery and genuine local character motion in native video |
| 6 Visual quality | `phase/omega-6-visual-quality` | `OmegaVisualAudit.java`, `OmegaVisualAuditTest.java` | Deterministic ROI motion/texture signals, no false certification | Lighting/texture/parallax + blinded human review and multiframe visual evidence |
| 7 Audio workflow | `phase/omega-7-audio-timeline` | `OmegaAudioTimeline.java`, `OmegaAudioTimelineTest.java` | Stable PCM waveform bins mapped to video frame clock | Sketch/Editor audio lane, correct scrubbing/lip sync, final audible MP4 |
| 8 Render/recovery | `phase/omega-8-render-recovery` | `OmegaRenderPlan.java`, `OmegaRenderPlanTest.java` | Bounded selective invalidation/canonical cache-key plan | Restart/crash recovery with original project safe, selective frame render, approved signed APK |

For Java classes use `android/app/src/main/java/com/rezoxnemesis/videostudio/`; test classes use matching `android/app/src/test/java/com/rezoxnemesis/videostudio/`. Phase contracts under `docs/omega/`. Each new pure engine must be package-local (unless integration demands API exposure), deterministic, bounded, with no network/phone/keys side effects.

## 3 — PR and merge protocol (hard rules)
1. **Freeze common ancestry.** All 9 phase branches must originate from the same foundation commit that contains this contract and the reviewed PR #107 baseline. No branch re-bases itself onto an unrelated old 4.x release.
2. **Agent ownership.** Each agent touches only its owned files. Shared-file changes are submitted as a separate proposed patch via an integration-owner-only PR. No duplicate symbol names.
3. **Dependency handoff.** Agents coordinate through this stable contract; APIs remain backwards compatible. If a needed API is absent, provide an interface proposal + local adapter rather than modifying someone else's code.
4. **Atomic changes.** One phase = branch + small focused commits + Android unit tests + failure modes documented + draft PR to `integration/omega-nine-phases-20261020`. Commit does not equal deployed feature.
5. **CI.** At minimum `gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest` at Java 17/SDK 36, plus each phase's focused tests, schema/replay/rollback tests for production integration. No installing/signing in ordinary feature CI.
6. **Integration authority.** Merge only reviewed green PRs, then re-run the combined build and existing tests. Reject introduced flakiness, performance regression, serialization drift, unsatisfied prerequisites and unsafe recovery behavior. Feature flags default OFF until native demonstration.
7. **APK release.** Separate candidate release branches after 4.8.7 compatibility and original-keystore certificate validation. Make each candidate from a known integration commit. Require automated Android build + visual proof on owner's device + explicit owner release approval before treating an APK as ready. Never install unverified candidate.
8. **Cross-account agents.** Repository contract and PR artifacts are the source of truth, not separate model memories. Every new chat must read this document and existing related PR state before coding, and post progress/exit criteria in its own draft PR.
9. **Conflict resolution.** Invariant violations and failing CI stop a merge; do not auto-disable tests or bypass `NATIVE_RECOVERY_HELD` to make green.
10. **No false completions.** Mark phase *foundation*, *engine prototype*, *native integrated*, *visually accepted*, or *released*. Completing the first engine slice only earns *engine prototype*.

## 4 — Parallel milestone graph
- **Track A:** Phase 0 gates + Phase 1 master registry and propagation contracts.
- **Track B:** Phase 2 reference landmark alignment → Phase 3 contour construction (parallel against frozen normalized-guide API).
- **Track C:** Phase 4 bone/mesh representation → Phase 5 acting curves (parallel against common scene-clock and rig pose API).
- **Track D:** Phase 6 visual QA, Phase 7 audio timing, Phase 8 render/recovery (independent core slices; native integration is coordinated).
- Integration barrier I0: shared contract + compile of independent modules.
- I1: production adapter review for 1/2/3/4/5 without schema regressions.
- I2: audio/visual/recovery integration with existing native export.
- I3: real-device qualitative acceptance and compatibility proof, then release candidate. The barriers do not force sequential coding of the individual phases.

## 5 — Direct agent work order template
> You own VideoStudio OMEGA Phase N in `RezoxNemesis/VideoStudio-MCP`. Read `docs/omega/NINE_PHASE_MASTER_CONTRACT.md` and PR #107 and #106. Work only on `phase/omega-N-...` owned files, implementing a real bounded deterministic engine plus failure and regression tests. Never edit main, the native safety guard or signing settings. Use this branch's precise test command and post evidence in a draft PR to `integration/omega-nine-phases-20261020`. Do not describe a pure module as an installed feature. Request integration-only adapters through a separate PR proposal. Repeat until scoped gates pass, but do not promise timing, unattended work, or results you have not measured.

## 6 — Required acceptance evidence per phase
- Exact files/commit SHA/diff, test command, workflow URL and job outcome.
- Reference fixture provenance (not Gallery), fixed frame times and deterministic test results.
- User-visible before/after actual native frames; no screenshot-only claim when motion matters.
- Observed caveats, feature-flag state, serialization format, fallbacks, reversibility.
- Audio source/track inspection and real final MP4 when an audio/render phase claims complete.
- Phone model and installed version plus signing/update compatibility before any APK claim.

## 7 — Baseline safety constraints
Treat `release/v486-source-base-20261019` and PR #107 as protected reference points. Do not merge PR #107 merely to get this nine-phase launch. Preserve all owner data. Never access Gallery. Never remove privacy gates or recovery holds. Never touch owner signing material. This contract can be revised only by integration-owner versioned amendment with all open phase agents notified.