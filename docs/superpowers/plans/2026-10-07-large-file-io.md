# Large-File I/O Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make VideoStudio handle 5 GB+ media without 32-bit overflow, whole-file buffering, or unnecessary local duplication.

**Architecture:** Local picker media remains URI-backed with persistable access and probed capabilities. Remote/ChatGPT imports move to a resumable bounded-memory transfer engine with partial-file checkpoints, storage preflight, safe redirects, and atomic promotion.

**Tech Stack:** Android ContentResolver/Storage Access Framework, Java `long`, HttpURLConnection/Range, SQLite or atomic JSON checkpoint store, existing ProjectStore/JobManager.

**Spec:** `docs/superpowers/specs/2026-10-07-videostudio-permanent-hybrid-control-design.md`

## Global Constraints

- No arbitrary 5 GB application ceiling for supported URI-backed local media.
- All byte counts/offsets/storage estimates use Java `long`.
- Local picker imports must not copy the full media merely to register it.
- Remote imports remain HTTPS-only and revalidate every redirect.
- Partial transfers must never be registered as complete media.
- Gallery enumeration remains unavailable.

## Review Focus

- 5 GB+ size must not overflow signed 32-bit values: Task 1 test uses `5L * 1024 * 1024 * 1024`.
- Unknown Content-Length must still stream safely: Task 2 includes unknown-length test.
- Origin without Range support must restart safely rather than append corrupt bytes: Task 2 test.
- Insufficient free storage must stop before transfer/render begins: Task 3 test.
- Revoked persisted URI permission must surface a recoverable asset error, not crash: Task 1 test.

---

### Task 1: Store large-media metadata without copying source bytes

**Files:**
- Create: `android/app/src/main/java/com/rezoxnemesis/videostudio/AssetProbe.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ProjectStore.java:40-95,307-340,484-513`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/LargeMediaTest.java`

**Interfaces:**
- Produces: `AssetProbe.Result probe(ContentResolver resolver, Uri uri)` with `sizeBytes: long`, `seekable: boolean`, `persistedReadAccess: boolean`, `providerAuthority: String`, `mime: String`, `displayName: String`.
- ProjectStore.Asset adds matching persisted fields with backward-compatible defaults.

- [ ] **Step 1: Write failing tests**

Cover serialization/deserialization of `sizeBytes > Integer.MAX_VALUE`, a 5 GB synthetic size, missing SIZE column, and revoked/open-failure capability reporting.

- [ ] **Step 2: Verify RED**

Run: `cd android && gradle :app:testDebugUnitTest --tests '*LargeMediaTest*'`  
Expected: FAIL because AssetProbe/fields do not exist.

- [ ] **Step 3: Implement AssetProbe and ProjectStore migration defaults**

Use descriptor/query metadata only; do not read media bytes to discover size. Persist URI access state and seekability separately from size.

- [ ] **Step 4: Verify GREEN**

Run focused test. Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -am "feat: add 64-bit URI-backed media metadata"`

### Task 2: Replace the 350 MB remote-import path with resumable streaming

**Files:**
- Create: `android/app/src/main/java/com/rezoxnemesis/videostudio/TransferJournal.java`
- Create: `android/app/src/main/java/com/rezoxnemesis/videostudio/ResumableTransferManager.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java:50-55,2280-2370`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/ResumableTransferTest.java`

**Interfaces:**
- Produces: `TransferJournal.Entry` with `id, sourceUrl, partialPath, expectedBytes, completedBytes, etag, lastModified, sha256, state`.
- Produces: `ResumableTransferManager.download(Request, JobManager.Job) -> Result`.
- ControlService remote-import commands delegate transfer bytes to the manager.

- [ ] **Step 1: Write failing transfer tests**

Use a local test HTTP server/fake connection seam to cover >5 GB announced length without allocation, bounded buffer use, Range resume, unknown length, Range ignored (HTTP 200 after resume request), cancellation and redirect revalidation.

- [ ] **Step 2: Verify RED**

Run focused Android tests. Expected: FAIL because transfer manager does not exist.

- [ ] **Step 3: Implement transfer journal**

Checkpoint only metadata and byte offsets. Never persist source credentials or temporary ChatGPT URLs longer than required for the active/recoverable transfer.

- [ ] **Step 4: Implement resumable bounded-memory download**

Write to `*.partial`; append only after a valid `206 Content-Range`; otherwise truncate/restart. Use a fixed buffer <= 512 KiB. Validate final expected length/checksum when known, close/fsync, then atomically promote.

- [ ] **Step 5: Replace legacy ControlService hard cap**

Remove `MAX_REMOTE_IMPORT_BYTES = 350 MB` as the architecture limiter. Keep inline base64 image limits unchanged because that is a separate small-image compatibility path.

- [ ] **Step 6: Verify**

Run focused tests and `cd android && gradle :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 7: Commit**

`git commit -am "feat: add resumable large-media transfer"`

### Task 3: Add storage-aware preflight and transfer recovery

**Files:**
- Create: `android/app/src/main/java/com/rezoxnemesis/videostudio/StorageBudget.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ResumableTransferManager.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/JobManager.java:20-240`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/StorageBudgetTest.java`

**Interfaces:**
- Produces: `StorageBudget.Check check(File targetRoot, long requiredBytes, long reserveBytes)`.
- Job state may enter `waiting_storage` with required/free/reserve values.

- [ ] **Step 1: Write failing storage tests**

Cover enough storage, insufficient storage, overflow-safe arithmetic, unknown remote length with configurable reserve, and resumed partial transfer needing only remaining bytes plus reserve.

- [ ] **Step 2: Verify RED**

Run focused tests. Expected: FAIL.

- [ ] **Step 3: Implement storage preflight and waiting state**

Do not delete user-selected source media. Automatic cleanup may target only VideoStudio-owned disposable cache/proxy files.

- [ ] **Step 4: Wire transfer recovery into ControlService startup**

Recover a valid transfer checkpoint or mark it explicitly restart-required if the temporary URL expired/no longer supports resume.

- [ ] **Step 5: Verify**

Run Android unit suite and APK assembly. Expected: PASS.

- [ ] **Step 6: Commit**

`git commit -am "feat: make large imports storage-aware and recoverable"`
