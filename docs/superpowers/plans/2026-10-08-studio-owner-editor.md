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

- [x] Write baseline behavioral regressions for explicit track duration and stale save.
- [x] Run Android regressions in CI and verify the intended failures.
- [x] Add additive migration, atomic revisions, bounded history and snapshots.
- [x] Implement timeline operations with source bounds, locked-track checks, keyframe interpolation and idempotent mutation IDs.
- [x] Run all Android unit tests; commit graph and tested editor engine.

## Task 2: Human editor, preview and inspector

Files: MainActivity.java, StudioTimelineView.java, StudioPreviewMonitor.java, LiveEditPlayer.java, TimelineCompositionFactory.java, StudioEditorUiTest.java.
Interfaces: timeline callbacks invoke EditorEngine operations; preview consumes the committed Project and playhead. NativeRenderEngine and program preview consume the same composition factory.

- [x] Add workflow regressions for image selection, visible preview and immediate owner edits.
- [x] Replace fixed cards with ruler/tracks, selection, drag/trim, playhead, zoom and snapping.
- [x] Wire undo/redo, inspector, keyframes, bin actions and bounded background metadata import.
- [x] Share composited timeline evaluation with render and surface actual unsupported capabilities.
- [x] Run unit/build/UI checks and commit the integrated editor.

## Task 3: Foreground export session and render parity

Files: ExportSessionStore.java, ExportSessionView.java, ControlService.java, JobManager.java, NativeRenderEngine.java, ExportSessionTest.java.
Interfaces: durable session ID immediately appears in the export screen; manual render lane runs independently from autonomous jobs; verified output merges into latest Project.

- [x] Add behavioral tests for preparing/progress/cancel/retry and immutable export revision.
- [x] Implement settings, immediate session handoff, separate lane and status persistence.
- [x] Preserve source audio, clip volume, mute, transforms and visible supported effects in final output. b3fb hardware device CI verified alpha/chroma/mask and mixed audio output.
- [x] Verify output before publishing, test cancellation/recovery and commit.

## Task 4: Stable MCP editor parity and capability truth

Files: ControlService.java, AppProtocol.java, src/index.js, scripts/editor-protocol-test.mjs.
Interfaces: compatible v3 calls and additive v4 schema use EditorEngine with command ID and expected revision. Query APIs expose actual capabilities and revision.

- [x] Add executable protocol tests for conflict/idempotency/privacy/owner controls.
- [x] Wire the shared editor actions and schema introspection without replacing stable endpoint identity.
- [x] Advertise only working capabilities and retain unavailable model/device states.
- [x] Run Node and Android suites; commit.

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

- [x] Run fresh full Node and Android tests and a source/build review. b3fb 145/6 CI; later legacy parity 172 offline Java cases plus Node 282/20/15 and actual Worker dry-run; latest APK/device CI still required.
- [x] Save the earlier verified 4e30 APK, SHA-256, CI IDs, test matrix, retained c285 media/UI evidence and explicit latest-source limitations.
- [x] Publish matching tree eab22 to draft PR #35 as b3fb1ba and verify all three CI jobs. Publish subsequent legacy parity checkpoint without replacing upstream history; user authorizes repository changes. Do not promote an unfinished product to a complete release.
- [x] Continue the original bundle in section 35 with exact completed/pending requirements, source provenance, the external build/publish ceiling and next commands; add BUILD_RESUME.md.

2026-10-09 checkpoint: implementation commit 2c9e684 closes the final focused recovery review. Local suites: 282 static source smoke assertions, 20 executable connection cases, 12 executable relay cases, 40 timeline/52 Vault/14 DSP/510 narration pure-core assertions, 15 executed software GLSL pixel checks and 99 parsed Java sources. Latest Android compilation/device proof remains pending. Phases B–H are incomplete as detailed in bundle section 35; a queued job, menu tile or static assertion is not completion.

2026-10-09 resumed checkpoint: section 36 supersedes the earlier SDK/publishing ceiling. Installed JDK/SDK/Gradle/emulator; b3fb CI verifies 145 unit cases and all six authored device cases. Later atomic legacy tool mapping, public recovery/fingerprint fixes, schema 4 effect providers and delayed-STOP guards pass 172 fresh offline Java cases, Node 282/20/15, and real Worker bundle compilation. Remaining legacy plans/presets and full phase B–H completion stay open. Orez is monitoring issue #36.
