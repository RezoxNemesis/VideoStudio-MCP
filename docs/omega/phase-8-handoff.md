# VideoStudio OMEGA — Phase 8: Render/recovery hardening handoff

**Branch:** `phase/omega-8-render-recovery`  
**PR:** #116, draft -> `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** **engine implemented + offline core tests; native production integration and device acceptance NOT done**. This is not a new APK and must not be switched on behind the owner's back.

## Additive Phase-8 implementation

`OmegaRenderPlan` is a Java 17 pure-engine decision layer (no file reads/writes, Android UI, network, jobs, secrets or safety-guard writes). It retains the existing `Range`, `Checkpoint`, `invalidated`, `cacheKey` and advisory `mayResume` signatures. New contracts:

1. **Frozen `Snapshot`.** Bounded 1–1200 authored frame hashes, source document SHA-256, exact revision, project ID, renderer/output profile SHA-256 and FPS 24/30/60. The renderer profile must encode the exact native renderer/version, canvas/crop/aspect, color/compositor settings, image resolution and all global scene dependencies. Per-frame SHA-256 inputs must include full canonical rendered visual dependencies (exposures, masters, instance overrides, motion, paint, immutable references, cross-frame interpolation and lighting). If dependency indexing cannot prove coverage, **render full output**. Do not compute frame hashes from just a dirty-frame index or timestamp.
2. **`plan`.** Two independent inputs per old raster: (a) immutable previously *committed* frame manifest proof and (b) freshly SHA-256-hashed bytes from the exact app-private PNG. Missing evidence => rerender. Both present but disagree => reject. Full rerender if ID/FPS/frame count/render profile changes; permit unchanged, independently verified frames across *document* revisions, while the *new output* must be committed against its own full document SHA/revision. Explicit dirty spans always win. Immutable ordered coalesced reuse/render ranges; no destructive edits.
3. **`rasterKey`.** Content-addressed per-frame key (profile and frame-input digest), distinct from existing revision-pinned `cacheKey`; supports safe reuse without pretending the new global document digest matches the old one.
4. **`RecoveryTicket`.** Strictly parsed, bounded/versioned string carrying operation ID, exact snapshot fingerprint, next uncommitted frame, and a hash chain of **contiguous** committed frame proofs. `checkpoint`/`mayResumeVerified` refuse torn or reordered receipts, output replacement, stale snapshot/revision, cross-operation replay, completed-output restart, absent disk evidence, owner recovery hold or lack of *independent* native recovery admission. `Checkpoint` / `mayResume` remain **advisory only** and must never start native jobs.
5. **Rational frame clock.** Proof presentation timestamps are checked exclusively against `NativeFrameClock.presentationTimeUs`, including 24/30/60 FPS, upper bound and five timeline audit positions.

All unit tests are in the exclusively owned `OmegaRenderPlanTest.java` file. No `SketchDocument`, native render entrypoint, guard, manifest schema, signing configuration, shared CI or other phase file was modified.

## Integration-owner adapter proposal (required, NOT merged by Phase 8)

Target existing `SketchNativeExport.prepare`, `SketchFrameRenderer`, `SketchFrameStreamEncoder`, `SketchExportFrameSource`, `RecoveryPlanStore`, `NativeStartupGuard` after a separate integration-owned review. Proposed feature flag: `omegaIncrementalRender=false` **by default**. This proposal is not an authorization to change any protected production entrypoint.

1. Before planning, load existing `snapshot.json` using current 4 MiB schema budget, validate and freeze all immutable reference hashes and documents with existing `SketchPaintArchive.verifyFrozen`. Under original `SketchProjectStore` revision lock, compute canonical full-scene/per-frame dependency SHA-256 (never from only touched object IDs). Any unsupported effect, cross-frame dependency, duplicate/unknown global setting or unverified source => full raster invalidation.
2. Fingerprint the actual selected native canvas compositor, dimensions, quality/aspect, color depth, transparency, raster/export effects and implementation version into `profileSha256`. Audio changes MUST invalidate final encoded MP4, even if video PNGs are reusable; never advertise audio in an MP4 until the native muxer verifies real audio tracks.
3. In an app-private *new* operation directory, parse old `frames.json`; **separately rehash every old PNG on disk** with `SketchProjectStore.fileHash`, verify its declared size and owned canonical path, and pass committed and physical frame proofs to `OmegaRenderPlan.plan`. Never let a manifest be its own proof. Check exact manifest source, prefix bounds and generation immutability before trusting raster reuse. No Gallery access.
4. For each reused frame, copy/stream from verified immutable old PNG into the new operation's `.part` target, bound the 8 MiB/frame and 512 MiB/export budgets, `fsync`, verify target hash **again**, atomically rename, then commit a new-frame manifest entry with **new** snapshot revision and `NativeFrameClock` timestamp. If a file changes between verification and copy, stop rather than publish. Changed frames come only from `SketchFrameRenderer.render` and follow existing cleanup/atomicity. No writes inside the prior operation's generation directory.
5. Persist restart ticket/manifest under the existing app-private generation via `.part` + `fsync` + atomic rename. On cold restart, require accepted identical `snapshot.json`, `RecoveryPlanStore` action/operation/revision and **NativeStartupGuard** admission separately; never call `userRetry()` from code. Rehash every completed PNG. Failed/missing checks must leave the previous operation untouched and prompt a user-safe retry/new render; do not delete old completed MP4. Never infer success from raster-only completion. Media3 audio/video codec output and a committed completion receipt must be verified before exposing the new MP4.
6. For adoption, run real Android force-stop/crash/upgrade recovery tests with source-project hash snapshots *before and after*, test `NATIVE_RECOVERY_HELD` both enabled and disabled through owner-approved actions, 100 linked objects, 1/1200 frame boundaries and random edits, performance p50/p95 on one known device, and native human-visible frames at 0/25/50/75/100%. Inspect decoded MP4 frame continuity, codec/provenance, soundtrack, portrait aspect, actual pixel deltas and preserved source. Launch stays OFF until successful reviewed evidence.

**Critical dependency:** Source tree is 4.8.6 (`versionCode=489`); the owner's connected app reports **4.8.7**. First reconcile PR #106, installed package/certificate hash and monotonically increasing versionCode; never generate/install an unapproved APK or change keystore.

## Verification and traceability

- Local isolated Java 17 syntax compile: `javac --release 17` for this engine plus the existing pure `NativeFrameClock` API.
- Local deterministic unit suite: 11 `@Test` cases invoked with minimal local JUnit signature/assertion stand-ins (not Gradle/Robolectric). Exercises dirty spans, 200 seeded property cases, 1200-frame boundary, revision-scoped/global-key invalidation, lost/corrupt PNG proof, restart/snapshot/operation/hold checks and 24/30/60 FPS. The local stand-ins are **not** committed and are not a replacement for full Android Gradle CI.
- Required repository gate (once GitHub Actions runs): `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17/SDK 36. Record exact run ID/conclusion; no claim of green prior to it.
- No native visual render verification, crash/restart/device screenshot or audio MP4 proof by this branch; missing and must not be inferred from pure unit tests.
- Release status: **BLOCKED pending integration-owned adapter, Android tests, owner-controlled real device validation, signed 4.8.7 reconciliation and explicit release approval**.

### Non-negotiable rollback/ownership

The Phase 8 PR changes only `OmegaRenderPlan.java`, `OmegaRenderPlanTest.java`, and `docs/omega/phase-8-handoff.md`. Runtime remains bit-for-bit unchanged unless an integration owner separately adopts an OFF-by-default adapter. Existing exports, sketches, saved assets and old recovery attempts remain intact. No changes to `main`, `integration/*`, Gallery, `NativeStartupGuard`, signing material, private keys or active recovery policy.
