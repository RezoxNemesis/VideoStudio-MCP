# VideoStudio owner editor and continuity design

Authority: `docs/continuity/VideoStudio_Complete_Engineering_Continuity_Bundle_v1.md`.
The owner requests continuous implementation of its entire roadmap and authorizes routine architecture, UI, testing, migrations, and CI decisions. This spec describes the first integrated deliverable; it does not redefine the complete app as Phase A.

## Baseline and boundaries

Upstream main is `d652066ce428e4f128514da4d1caee919974554e`, Android 3.4.7, stable MCP v3. No open PRs at discovery. All 94 files were recovered through GitHub and checked against their Git blob SHA. Preserve the application ID, existing SQLite database, owner identity, stable v3 endpoint, import privacy, native model analysis, render verification, recovery, proxies, and procedural engines.

## Shared editor model

Evolve ProjectStore's app-private SQLite database additively. Keep legacy clip/asset JSON readable. Add stable typed tracks, explicit 64-bit start positions, revision, project settings, markers, keyframes and clip transforms. Legacy clips migrate to sequential positions on V1. Asset references remain stable independently of file paths. Add transactional history and named snapshots. UI and MCP use one EditorEngine for structural timeline operations; existing producers persist through revision-checked ProjectStore.save.

Each mutation compares the project revision inside the same SQLite transaction that commits the graph and journal. Reject stale overwrites with a recoverable conflict snapshot. Undo/redo are new revisions; imported media and published outputs must remain accessible through history. Autonomous command IDs are idempotent and audited. Owner controls remain independent of manual editing.

## Editor and preview

Replace timeline cards with a time-scaled custom multitrack surface: ruler, playhead, selectable clips, drag movement, head/tail trim, snapping, zoom, track mute/visibility/lock, razor, ripple delete, and markers. Expose the inspector, transform/keyframes, media preview and reusable workspace actions directly. Load the selected clip after project open/import. Images use bounded decoding; source videos use the reusable Media3 player. Program playback uses the same evaluated track layout and effects as export, with visible failure and unavailable-source states. Do not require a final render to view imported stills.

Use existing native effects only when they execute in preview/export. Unsupported retiming, generative temporal synthesis and advanced VFX require an honest capability state rather than a success toast or metadata-only edit.

## Manual export

Provide explicit export settings and a persistent export session with stage, progress, cancel, retry and playable result. Start the service with a manual session ID immediately; reserve a foreground render lane separately from autonomous heavy jobs, subject to device resource guards. Snapshot the chosen revision for deterministic export. Register only verified readable/playable outputs. Editing remains usable during export. Cancellation never publishes partial media.

## Following deliverables

Continue the bundle's order: bounded large-file I/O and Vault, five authorized storage profiles, additive v4 commands and mirror reconciliation, deterministic 2D animation, actual model backends, real 3D/VFX, audio and recap, and release validation. Model packs, OAuth, paid GPU access, and device codec/thermal verification cannot be invented; record these requirements explicitly when unavailable. Menus, disconnected interfaces, and queue acceptance do not count as completed capabilities.

## Verification

Run existing Node source/connection checks and behavioral editor/protocol tests. Add Robolectric tests for migrations, persistence, conflict handling, undo/redo, timeline timing, keyframes, locked tracks, and export session states. Use GitHub Android CI for Java compilation, unit tests and installable APK while the local executor lacks JDK/Android SDK. Capture emulator workflow evidence when an emulator is available and label physical-device checks pending until exercised. Save logs, artifact hashes, requirement status and precise continuation in the repository.
