# Verified Segmented Export Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Native implementation is authorized; bounded independent review follows.

**Goal:** Resume long original-media exports from independently verified video windows.

**Architecture:** Five-second video compositions preserve original effect clocks. One continuous audio pass preserves DSP history; verified SQLite checkpoints feed a lossless native mux into the normal publication proof.

**Tech Stack:** Java17, Media3 1.11.1, Android SQLite/MediaExtractor/MediaMuxer, Robolectric and API33 instrumentation.

**Spec:** ../specs/2026-10-09-segmented-render.md

## Global Constraints

- Final originals, no preview proxies or quality reduction; preserve all owner media.
- Five-second video windows, bounded2–10 seconds except the final remainder.
- Full streaming source/output SHA binding, deterministic stages and atomic commits.
- Separate continuous audio; no per-segment DSP restart or assumed Media3 resume support.
- Owner/MCP share execution; STOP prevents stale publication and preserves verified checkpoints.

## Review Focus

- A window starts mid clip: original keyframe/motion/title duration and clock survive.
- Fractional speed/source clipping: microseconds and exact effective Media3 timing survive.
- A process dies between file rename and SQLite commit: deterministic stage/final bytes recover safely.
- Sources/settings change or a checkpoint corrupts: stale bytes are not reused.
- Codec fallback changes initialization data or STOP races final mux: incompatible output never publishes.

### Task1: Original-clock video windows

Files: modify TimelineCompositionFactory.java/TimelineMath.java; create TimelineWindow.java and TimelineWindowTest.java under the existing Android main/test package.

Interfaces: TimelineWindow(startUs,endUs), intersection(startUs,durationUs); TimelineCompositionFactory.buildVideoWindow(Project,String,String,TimelineWindow)->Composition. Effects consume signed origins through TimelineMath.effectLocalMs; ordinary builds remain unchanged.

- [x] Write failing tests for mid-clip source2x trim, gaps/track order/solo/hidden, effect matrix equality with the whole-program clock, original title duration, generated layer durations and tiny remainder, with immutable input assertions.
- [x] Run offline JUnit and observe the specific RED cases.
- [x] Implement the bounded window and factory path retaining original effect clips; output contains no audio processors/sequences.
- [x] Run window plus existing composition/effect/audio tests, inspect GREEN and commit.

### Task2: Durable identity/checkpoint journal

Files: create RenderSessionStore.java/RenderSessionStoreTest.java and RenderSourceIdentity.java/RenderSourceIdentityTest.java.

Interfaces: RenderSourceIdentity.capture(Context,Project,String aspect,String quality,BooleanSupplier cancelled)->Snapshot with immutable boundProject/sessionId/internal manifest. Streaming source hashes bind canonical graph/settings/renderer identity; validated content-addressed local snapshots feed Media3. Store verified windows and audio proof with actual codec metadata and deterministic file paths.

- [x] Write RED tests for source edits after capture, identical-media dedup, corrupt snapshots, changes between hash/copy, cancellation, missing sources, canonical graph/settings mismatch, restart reuse, conflicting writers and death before/after atomic rename.
- [x] Implement SQLite transaction bindings and full proof revalidation; preserve originals and active-session files.
- [x] Run focused tests GREEN, independently inspect identity/fault cases and commit.

### Task3: Continuous audio and encoded mux

Files: modify NativeRenderEngine.java/TimelineCompositionFactory.java; create SegmentMediaMuxer.java and focused tests.

Interfaces: buildAudio(Project)->Composition or explicit absent; native exportComposition accepts requireVideo flag; mux verified segments with global timestamps plus optional verified AAC.

- [x] Add RED tests for absent audio, retained continuous DSP, exact timestamps, incompatible codec headers, missing initial sync sample, oversized buffer bounds and cancellation.
- [x] Implement full audio render and bounded sample copying, strict configuration compatibility and final checksum verification.
- [x] Run native tests and add device assertions for segment-boundary pixels/audio/container duration. Device assertions are added but not yet executed; matching execution is required before Task4 completion.

### Task4: Shared resumable engine and device evidence

Files: create SegmentedRenderEngine.java; modify NativeRenderEngine.java, ControlService.java/MainActivity.java as needed, StudioDeviceTest.java and evidence script/protocol docs.

Interfaces: existing public export/Handle/Listener stay available; long-timeline dispatch creates or resumes a checksum-bound session and each window uses the bounded codec engine.

- [ ] Add RED lifecycle tests for partial completion/restart, verified reuse, individual corrupt rerender, route incompatibility repair, active STOP and stale callbacks.
- [ ] Implement serialized stages and per-session writer exclusion; keep immutable recovery graph/scopes/owner independence.
- [ ] Run fresh full tests/build/Worker checks, bounded independent review and publish the exact tree.
- [ ] Inspect matching APK and actual device window/reuse/corruption/mux evidence; fix failures before any completion claim.
- [ ] Append measured continuity and continue the full roadmap.

Self-review: each spec requirement maps to one of the four tasks. Audio pass may restart after death; checkpoint reuse covers video. Actual power loss, provider accounts and universal device support need separate evidence and are not inferred from unit fault tests.

Task1 evidence: RED8/8; review timing/normalization RED4/13; solo RED2/15; final81 main/41 test classes GREEN286, Node282/20/22, core40/52/14/510 and Worker dry-run. Independent review closes corrections. Factory path only; shared export/checkpoint integration and actual window-device evidence remain pending.

Task2 source component: RED10, bound/MIME RED3/14, review orphan/wait cancellation RED2/16; focused37/final302 native cases and Node282/20/22 pass with independent source-helper review closed. Content-addressed source snapshots are implemented; durable SQLite session/segment rows and caller integration remain pending.

Task2 journal: RED12 precedes implementation; review stage-leak/mutable-identity RED3/18 and paired redirect/corrupt-pin RED2/20 precede fixes. Private frozen graph/manifest, canonical original-graph/session recomputation and full size/SHA validation of managed pinned files run before any lease mutation. Generation-specific stages prevent stale codec writes; durable encoded intent recovers before/after atomic rename. Missing/corrupt rows rerender individually, transient verification errors preserve valid checkpoints, duplicate completion is idempotent and retired writers cannot clear a newer lease. Focused51 and final83 main/43 test Java classes GREEN322; Node282/20/22 GREEN. Evidence is SQLite/filesystem/byte-stream unit fault injection, not new device process-death or shared engine integration. Cache references/eviction remain Task4.

Task3 source: audio RED6 and mux RED10 precede implementation. Review owner-output/EOS RED4/14, selected duration/chunk cancellation RED11/21 and held-FD proof RED2/6 precede fixes. Focused42, held-FD focused29 and final84 main/46 test Java classes GREEN351; Node282/20/22 GREEN. Bounded review closes four original Important findings plus the residual proof inode race. Dedicated ninth device case is added for real2x5s encoding, encoded-stage fault recovery, independent corruption repair, continuous AAC packet/time identity, five sampled frame comparisons, original/inode protection and a synthetic native sync-frame1001us tail duration. These assertions still need matching APK/device execution. Public exports retain ordinary native dispatch until Task4.
