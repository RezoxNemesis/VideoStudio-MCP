# Live Editor and Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the Android editor playable and inspectable while autonomous analysis, animation and rendering continue in background lanes.

**Architecture:** Replace the editor's simple `VideoView` lifecycle with a reusable Media3 ExoPlayer-backed `LiveEditPlayer`. Separate immutable preview snapshots from mutable project state so the user can keep watching snapshot N while background work prepares N+1.

**Tech Stack:** Android Media3 ExoPlayer/UI, Java, current programmatic Android UI, ProjectStore, JobManager, NativeRenderEngine.

**Spec:** `docs/superpowers/specs/2026-10-07-videostudio-permanent-hybrid-control-design.md`

## Global Constraints

- Playback must not own or cancel background render/generation jobs.
- Project refresh should preserve playback state when the active preview remains valid.
- Heavy timelines may use proxies, but final rendering always uses original media.
- A failed proxy/preview must never invalidate source media.
- Preserve the existing VideoStudio visual language.

## Review Focus

- Background project update during playback must not reset to 0 unexpectedly: Task 1.
- Render start while user is playing a preview must not stop playback: Task 2.
- Proxy-generation failure must fall back to playable source when possible: Task 3.
- New checkpoint arrival must not force-switch while user is actively watching unless explicitly enabled: Task 2.
- Activity destruction/recreation must release/recreate player resources without cancelling native jobs: Task 1.

---

### Task 1: Introduce LiveEditPlayer with persistent playback state

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/rezoxnemesis/videostudio/LiveEditPlayer.java`
- Create: `android/app/src/main/java/com/rezoxnemesis/videostudio/LivePlaybackState.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/MainActivity.java:75-165,394-610`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/LiveEditPlayerTest.java`

**Interfaces:**
- Produces: `LiveEditPlayer.attach(ViewGroup host)`, `play(Uri, long positionMs)`, `setPlaylist(List<MediaItem>)`, `snapshotState()`, `restoreState(LivePlaybackState)`, `release()`.
- Consumes Media3 `ExoPlayer` and `PlayerView`.

- [ ] **Step 1: Add Media3 player dependencies and failing state tests**

Add `androidx.media3:media3-exoplayer:1.11.1` and `androidx.media3:media3-ui:1.11.1`. Test playback-state preservation across project refresh and Activity recreation seam.

- [ ] **Step 2: Verify RED**

Run: `cd android && gradle :app:testDebugUnitTest --tests '*LiveEditPlayerTest*'`  
Expected: FAIL because player/state classes do not exist.

- [ ] **Step 3: Implement LivePlaybackState and LiveEditPlayer**

Keep playback lifecycle separate from JobManager/render handles. Preserve position, selected clip/media URI, playWhenReady and preview snapshot ID.

- [ ] **Step 4: Replace MainActivity VideoView usage**

Create the player once for the editor lifecycle; stop rebuilding the entire playback object when `scheduleEditorRefresh` detects metadata changes.

- [ ] **Step 5: Verify**

Run focused tests and Android unit suite. Expected: PASS.

- [ ] **Step 6: Commit**

`git commit -am "feat: add persistent Media3 live edit player"`

### Task 2: Add immutable preview snapshots and in-editor autonomous activity

**Files:**
- Create: `android/app/src/main/java/com/rezoxnemesis/videostudio/PreviewSnapshotStore.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ProjectStore.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/MainActivity.java:394-610`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/PreviewSnapshotTest.java`

**Interfaces:**
- Produces: immutable snapshot record `id, projectId, projectRevision, mediaRevision, uri, qualityTier, sourceType, jobId, createdAt`.
- MainActivity exposes `Play new result` without interrupting current playback.

- [ ] **Step 1: Write failing snapshot selection tests**

Test N continues playing while N+1 is published; auto-switch occurs only when configured and safe; stale snapshots remain addressable until cleanup.

- [ ] **Step 2: Verify RED**

Run focused test. Expected: FAIL.

- [ ] **Step 3: Implement snapshot store and publication hooks**

Publish snapshots only after a playable output/checkpoint exists. Never point a snapshot at `*.partial`.

- [ ] **Step 4: Improve editor UI**

Before modifying the non-trivial editor surface, use the required 12ui-design workflow on the existing editor to derive an improvement kit. Apply only the approved/selected direction while preserving app identity. Add player controls, source badge, current job stage/progress, waiting reason, stop/cancel entry point and `Play new result`.

- [ ] **Step 5: Verify UI/state behavior**

Run Robolectric tests plus APK assembly. Manually inspect the built editor layout on the connected device during final integration.

- [ ] **Step 6: Commit**

`git commit -am "feat: add live preview snapshots and activity strip"`

### Task 3: Add optional proxy previews for heavy media

**Files:**
- Create: `android/app/src/main/java/com/rezoxnemesis/videostudio/ProxyManager.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ProjectStore.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/LiveEditPlayer.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/NativeRenderEngine.java`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/ProxyManagerTest.java`

**Interfaces:**
- Produces: `ProxyManager.request(ProjectStore.Asset asset, Tier tier) -> JobManager.Job`; proxy metadata references original asset ID and never replaces original URI.
- LiveEditPlayer chooses proxy only for preview; NativeRenderEngine continues to resolve originals for final export.

- [ ] **Step 1: Write failing proxy/source-selection tests**

Assert final render resolves original media, failed/missing proxy falls back to source, and proxy cleanup does not remove source assets.

- [ ] **Step 2: Verify RED**

Run focused test. Expected: FAIL.

- [ ] **Step 3: Implement proxy metadata and background generation**

Use Media3 Transformer for 540p/720p H.264 proxy where supported. Make generation cancellable and publish only completed proxy files.

- [ ] **Step 4: Wire player selection**

Prefer completed proxy for interactive playback when the asset is marked heavy; otherwise use source.

- [ ] **Step 5: Verify**

Run Android tests and APK assembly. Expected: PASS.

- [ ] **Step 6: Commit**

`git commit -am "feat: add non-destructive preview proxies"`
