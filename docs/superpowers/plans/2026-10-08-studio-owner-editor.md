# VideoStudio Owner Editor Implementation Plan

> For agentic workers: use superpowers:executing-plans to implement this plan in the current session. Steps use checkbox syntax for tracking.

**Goal:** Continue the full engineering bundle, beginning with a persistent owner-operated editor, working preview and immediate export.

**Architecture:** Additive SQLite revisions/history underpin a shared EditorEngine. A time-scaled Android timeline and media monitor expose the same operations as MCP. Manual export has an independent foreground session; verified output uses existing publication/recovery mechanisms.

**Tech Stack:** Java 17, Android API 29+, SQLite, Media3 1.11.1, Robolectric/JUnit, Node 22, Cloudflare Workers.

**Spec:** `docs/superpowers/specs/2026-10-08-studio-owner-editor.md`; complete product authority is the continuity bundle.

## Global Constraints

- Preserve `com.rezoxnemesis.videostudio`, SQLite `videostudio_v3.db`, stable owner identity and compatible v3 endpoint.
- Never enumerate Gallery/arbitrary Documents through MCP.
- 64-bit times and sizes; never load full media into memory.
- No success for queued work, unimplemented effects or unverified media.
- Preserve original assets and owner changes on conflicts; migrate additively.
- Continuous execution is authorized; do not pause for routine decisions.

## Review Focus

- Owner edits during generation/export: preserve edits and register outputs against the latest revision.
- Process death between transaction commit and command acknowledgement: do not duplicate mutations.
- Still image/orientation and inaccessible content URI: show media or a precise recoverable error.
- Clip move/trim/split with speed, gaps, audio and locked tracks: maintain source/timeline mapping.
- Cancel/revoke while exporting: retain manual access and do not publish partial output.

## Task 1: Revision-safe project graph and recoverable history

Files: ProjectStore.java, EditorEngine.java, TimelineMath.java, ProjectStoreEditorTest.java, EditorEngineTest.java.
Interfaces: `Project.revision`, `Project.tracks`, `Clip.startMs/trackId/keyframes`; `ProjectStore.transact(id, expectedRevision, actor, commandId, description, mutation)` returns the committed Project. `undo/redo/snapshot/restore` return new authoritative revisions.

- [ ] Write baseline behavioral regressions for explicit track duration and stale save.
- [ ] Run Android regressions in CI and verify the intended failures.
- [ ] Add additive migration, atomic revisions, bounded history and snapshots.
- [ ] Implement timeline operations with source bounds, locked-track checks, keyframe interpolation and idempotent mutation IDs.
- [ ] Run all Android unit tests; commit graph and tested editor engine.

## Task 2: Human editor, preview and inspector

Files: MainActivity.java, StudioTimelineView.java, StudioPreviewMonitor.java, LiveEditPlayer.java, TimelineCompositionFactory.java, StudioEditorUiTest.java.
Interfaces: timeline callbacks invoke EditorEngine operations; preview consumes the committed Project and playhead. NativeRenderEngine and program preview consume the same composition factory.

- [ ] Add workflow regressions for image selection, visible preview and immediate owner edits.
- [ ] Replace fixed cards with ruler/tracks, selection, drag/trim, playhead, zoom and snapping.
- [ ] Wire undo/redo, inspector, keyframes, bin actions and bounded background metadata import.
- [ ] Share composited timeline evaluation with render and surface actual unsupported capabilities.
- [ ] Run unit/build/UI checks and commit the integrated editor.

## Task 3: Foreground export session and render parity

Files: ExportSessionStore.java, ExportSessionView.java, ControlService.java, JobManager.java, NativeRenderEngine.java, ExportSessionTest.java.
Interfaces: durable session ID immediately appears in the export screen; manual render lane runs independently from autonomous jobs; verified output merges into latest Project.

- [ ] Add behavioral tests for preparing/progress/cancel/retry and immutable export revision.
- [ ] Implement settings, immediate session handoff, separate lane and status persistence.
- [ ] Preserve source audio, clip volume, mute, transforms and visible supported effects in final output.
- [ ] Verify output before publishing, test cancellation/recovery and commit.

## Task 4: Stable MCP editor parity and capability truth

Files: ControlService.java, AppProtocol.java, src/index.js, scripts/editor-protocol-test.mjs.
Interfaces: compatible v3 calls and additive v4 schema use EditorEngine with command ID and expected revision. Query APIs expose actual capabilities and revision.

- [ ] Add executable protocol tests for conflict/idempotency/privacy/owner controls.
- [ ] Wire the shared editor actions and schema introspection without replacing stable endpoint identity.
- [ ] Advertise only working capabilities and retain unavailable model/device states.
- [ ] Run Node and Android suites; commit.

## Task 5: Continue the remaining product roadmap

- [ ] Phase B: Vault range/chunk/checksum tests, external storage and five authorized profiles.
- [ ] Phase C: mirror/executor with offline placeholders and conflict reconciliation.
- [ ] Phase D: deterministic articulated 2D authoring and real preview/render integration.
- [ ] Phase E: verified model pack execution, controlled generation and temporal processing.
- [ ] Phase F: real 3D runtime, import, compositor/tracking and render passes.
- [ ] Phase G: working audio DSP, ASR/TTS/captions, lip sync and recap workflows.
- [ ] Phase H: performance/accessibility, emulator regression, hardware checks, APK/evidence.

Each subsystem receives its concrete tests/interfaces before product code. Missing external credentials or model weights are recorded as blockers, never disguised as implemented functions.

## Task 6: Release evidence and continuity

- [ ] Run fresh full Node and Android tests and a source/build review.
- [ ] Save APK, SHA-256, CI IDs, test matrix, media/UI evidence and known limitations.
- [ ] Publish an isolated reviewable branch/draft PR; do not merge/deploy without authority.
- [ ] Update the original continuity bundle with exact completed/pending requirements and next commands.
