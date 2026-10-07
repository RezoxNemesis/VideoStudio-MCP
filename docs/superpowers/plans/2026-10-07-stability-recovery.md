# Stability and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make autonomous VideoStudio work survive crashes, resource pressure, network loss, reboot and compatible APK replacement without corrupting projects or published output.

**Architecture:** Extend the existing JobManager/recovery-plan model into explicit resumable states, keep heavy work off the UI thread, atomically publish outputs, and make ControlService the single owner of remote transport and recovery dispatch.

**Tech Stack:** Android foreground Service, JobManager, SQLite/atomic files, RecoveryPlanStore, NativeAgentRecoveryReceiver, Media3, Node control-plane tests, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-07-videostudio-permanent-hybrid-control-design.md`

## Global Constraints

- UI destruction must not cancel the Native Agent service.
- Heavy work must never execute on the Android main thread.
- Partial output must never replace a previously valid final output.
- Pending commands must not be discarded when queues fill.
- Reboot/APK replacement must re-arm the same stable connection identity.
- Thermal/memory/storage pressure should become explicit waiting states rather than unexplained crashes.

## Review Focus

- Process death after output bytes exist but before metadata commit must recover/publish once: Task 2.
- Reboot with waiting work must not duplicate the command: Task 3.
- Thermal wait must preserve checkpoint/progress and resume: Task 1.
- Queue saturation must reject new work without dropping old commands: Task 3.
- Activity destruction during render must not cancel service-owned work: Task 1.

---

### Task 1: Make job states explicit and separate UI lifecycle from heavy work

**Files:**
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/JobManager.java:21-260`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/MainActivity.java:150-165`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java:80-180`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/JobRecoveryTest.java`

**Interfaces:**
- Produces canonical job states `queued, preparing, running, checkpointed, waiting_network, waiting_storage, waiting_memory, waiting_thermal, waiting_native, completed, failed, cancelled`.
- ControlService owns long-lived heavy jobs; MainActivity owns only user-initiated local preview/player resources.

- [ ] **Step 1: Write failing state-transition tests**

Cover thermal/memory wait and resume, cancellation, invalid terminal-to-running transition, and Activity destruction not calling service job shutdown/cancel.

- [ ] **Step 2: Verify RED**

Run focused Android tests. Expected: FAIL for the new canonical state model.

- [ ] **Step 3: Implement state machine and ownership cleanup**

Centralise allowed transitions in JobManager. Remove any Activity lifecycle path that cancels service-owned jobs. Keep Activity-local preview/export handles distinct.

- [ ] **Step 4: Verify**

Run Android unit tests. Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -am "feat: harden autonomous job lifecycle"`

### Task 2: Atomically publish renders and recover interrupted publication

**Files:**
- Create: `android/app/src/main/java/com/rezoxnemesis/videostudio/AtomicMediaPublisher.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/NativeRenderEngine.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/RecoveryPlanStore.java`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/AtomicMediaPublisherTest.java`

**Interfaces:**
- Produces: `AtomicMediaPublisher.publish(File partial, PublishTarget target, RecoveryPlan plan) -> PublishResult`.
- Recovery plan records `prepared, encoded, validated, published` stages plus final URI/name.

- [ ] **Step 1: Write failing publication/restart tests**

Cover crash after encode before publish, crash after MediaStore insert before final metadata commit, invalid/zero-byte output, and rerun after already-published output.

- [ ] **Step 2: Verify RED**

Run focused tests. Expected: FAIL.

- [ ] **Step 3: Implement atomic publication**

Never overwrite project latest-export metadata until validation/publication succeeds. Recovery detects an already-published committed output and returns it instead of rerendering.

- [ ] **Step 4: Verify**

Run focused and full Android tests. Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -am "feat: atomically publish and recover renders"`

### Task 3: Re-arm the stable agent after reboot/update and reconcile durable queues

**Files:**
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/NativeAgentRecoveryReceiver.java:1-51`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java:80-180`
- Modify: `android/app/src/main/java/com/rezoxnemesis/videostudio/AppProtocol.java:140-245`
- Modify: `scripts/connection-test.mjs`
- Test: `android/app/src/test/java/com/rezoxnemesis/videostudio/JobRecoveryTest.java`

**Interfaces:**
- Receiver re-arms ControlService on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- Protocol reconciliation returns unfinished/expired-leased work regardless of cursor while preserving idempotent terminal results.

- [ ] **Step 1: Write failing reboot/update and queue-reconciliation tests**

Cover repeated receiver invocation, stale generation process, expired command lease behind cursor, queue saturation and duplicate completion receipt.

- [ ] **Step 2: Verify RED where behavior is not already covered**

Run Node and Android focused tests. Existing passing behaviors should remain as regression evidence; new receiver/recovery assertions should fail until implemented.

- [ ] **Step 3: Implement missing recovery guarantees**

Make service start idempotent, force sync after package replacement, reload recovery plans before new heavy work, and retain unfinished commands during queue trimming.

- [ ] **Step 4: Verify**

Run: `npm test && cd android && gradle :app:testDebugUnitTest :app:assembleDebug`  
Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -am "feat: reconcile autonomous work after restart and upgrade"`

### Task 4: Final integrated verification and release evidence

**Files:**
- Modify: `scripts/smoke-test.mjs`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/android.yml`
- Modify: `README.md`
- Modify: `docs/V3_ARCHITECTURE.md`

**Interfaces:**
- Consumes all previous plan deliverables.
- Produces CI evidence and updated operational documentation without changing the stable MCP URL.

- [ ] **Step 1: Add smoke assertions for the permanent contract**

Assert stable `/mcp-v06`, stable native `/app-mcp-v3/`, hybrid auth/binding symbols, 64-bit media metadata, resumable transfer, LiveEditPlayer, explicit waiting states, and no Gallery read permissions.

- [ ] **Step 2: Run complete local verification**

Run:
`npm test`
`cd android && gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace`
`npx wrangler deploy --dry-run`

Expected: all exit 0.

- [ ] **Step 3: Update architecture/readme**

Document the permanent plugin endpoint, native compatibility lane, large-file strategy, live player, recovery states and one-time hybrid authorization setup.

- [ ] **Step 4: Push branch/PR and require CI green**

GitHub Actions must pass both VideoStudio CI and Android APK workflows.

- [ ] **Step 5: Device verification**

On the connected Android device, install the upgrade through the existing signed development path, then call `app_status` and `app_self_test`. Confirm the device/owner identity and projects remain, Native Agent is current, and the same hybrid plugin routes status/control.

- [ ] **Step 6: Interactive acceptance check**

While a representative background autonomous job is running, play/scrub in the editor. Import/probe a synthetic or real >5 GB URI-backed asset when available without copying it into memory. Verify waiting/recovery behavior under at least one safe simulated resource condition.

- [ ] **Step 7: Commit final documentation/evidence**

`git commit -am "docs: document permanent hybrid VideoStudio foundation"`
