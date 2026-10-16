# VideoStudio 4.5.7 — startup crash-loop protection, render recovery and lower RAM use

Status: **staged source patch, not a verified physical-device diagnosis**. This incremental patch builds on 4.5.6. The user reported: tapping VideoStudio opens its logo and closes immediately; this followed an on-device `animate_images` portrait render that was repeatedly claimed by MCP but did not acknowledge a completed job. Android logcat was unavailable at the time of the report. **Do not assert that a particular exception or line is the confirmed root cause.**

## Verified architectural risks in the preceding source

- `MainActivity.onCreate` allocated **ExoPlayer** and audio processing infrastructure before any timeline/preview was requested. This increases decoder initialization costs at splash and competes with a resumed Media3 render.
- `ControlService.onCreate` originally initialized many journal/model/workspace providers *before calling startForeground*, then synchronously called `recoverDurablePlans()` and `reattachInflightCommandWatchers()`, potentially replaying interrupted expensive work during the service's lifecycle callback.
- `NativePortraitMotionAnalyzer` simultaneously held multiple full-size ARGB bitmap arrays for hair/head/torso/background compositing; in addition to ML Kit segmentation/face mesh allocations, this worsened peak heap pressure.
- `MainActivity.showHome` used `ProjectStore.list()` which parsed every project JSON in SQLite even though the dashboard showed at most eight.

None of these observations substitutes for the owner's real device `logcat` or Android process-exit reason.

## What this patch implements

### 1. Native startup and crash-loop circuit breaker
- A distinct, app-private `NativeStartupGuard` stores only startup timestamps, hold state, bounded retry timestamps and non-sensitive status. It does **not** rewrite projects, MediaStore, Gallery, cloud credentials, VideoStudio MCP identities, the user Keystore or the original job journal.
- On the first **compatible APK upgrade**, if an interrupted heavy job such as `animate_images` remains in the existing durable recovery plan or command journal, automatic heavy recovery is **held**. This prevents the exact crashing workload from immediately replaying on an unstable app start.
- Two repeated unclean service restarts inside three minutes also quarantine heavy recovery. A repeated attempt to resume the same saved heavy plan within twenty minutes is refused. Read-only commands, small edits and normal project browsing are not blocked.
- The startup guard reports `startupRecovery` through the existing authenticated native `get_state`. When it is held, the new home page shows **Safe startup • previous render paused**, with an **explicit, confirmed** user-controlled retry. It is not automatically retried forever.
- If an unsafe native command is redelivered while recovery is held, it receives a durable `NATIVE_RECOVERY_HELD` failure receipt rather than endlessly re-running the decoder. The *source* and *saved recovery plan* remain present; this is not a successful animation result.

### 2. Foreground-service startup speed
- `ControlService` registers and promotes its original, user-enabled foreground notification **before** SQLite/project jobs and model/runtime initialization. Normal MCP registration follows once the service is prepared.
- Durable plan resumes and in-flight watcher reattachment run in the existing background setup executor after a twelve-second startup grace period, not on `Service.onCreate`. Destroying the service cancels the scheduled startup work.
- If the owner's phone stops the process during heavy work, neither the launcher nor the service should immediately replay the same dangerous operation. This is a circuit-breaker, not a guarantee against all OS/hardware crashes.

### 3. Faster, safer human launcher
- ExoPlayer and preview-audio processors initialize lazily on **actual playback/editor opening**, not during the splash screen. All playback methods continue to call the same Media3 player instance.
- The home page queries only the eight latest project rows, preserving access to the complete library under **View All** and keeping all archived projects in SQLite.
- The launcher has a last-resort, **non-destructive safe-start screen** for synchronous initialization exceptions. It displays a safe class-name diagnostic and offers retry; it does not delete data or silently rotate credentials.

### 4. Bounded portrait animation memory
- Foreground extraction, separate cutout layers and background reconstruction use up to 64-row ARGB array tiles. Original person segmentation, mask thresholds, normalized head/torso/lower overlaps, color math, pixels and outputs are retained.
- This removes multiple simultaneous whole-frame `int[]` allocations without asking Android to allocate more RAM or reducing image resolution. Results still require **native pixel-parity regression and physical-phone thermal/RAM measurement**.

### 5. Actual cause diagnostics
- On Android API 30+, a `recentOwnProcessExit` section in native `get_state` reports only the app's most recent process-exit **category** (Java crash, native crash, low memory, ANR, etc.), timestamp, and RAM metrics. It does not retrieve stack-trace streams, Gallery media or other apps' process data.
- The user can obtain an Android `adb logcat` or system crash trace later for more precise fault classification. If the phone still crashes, do not call this bug fixed merely because CI assembled the APK.

## QA and rollout

- `NativeStartupGuardTest`: repeated unclean starts, post-upgrade interrupted rendering, bounded once-only retry and manual owner acknowledgement; saved project data must survive.
- `NativeColdStartSmokeTest`: cold `MainActivity` launch without ExoPlayer or preview-audio objects and with usable project storage.
- `NativePortraitMemorySafetyTest`: synthetic native graphics checks full foreground/cutout alpha and background dimensions after tile processing.
- `NativeStartupRecentProjectsTest`: recent-only query while keeping every archived project.
- Prior v4.5.6 sketch/render/visual-review tests and 10-second 2D film proof must remain green. Verify existing Android package `com.rezoxnemesis.videostudio.next`, original signing-certificate continuity and versionCode **457** / versionName **4.5.7**.
- Restore draft PR base to the previous 4.5.6 feature when CI passes. Do **not** merge or uninstall the existing app. Install the newly signed APK as a compatible update. On first launch, the interrupted portrait job may be paused by design; use the safe-mode UI to retry only once after checking stability.
- **Acceptance still open** until the real Android phone successfully cold-launches, opens Editor/Sketch Studio, survives several relaunches, keeps the original projects and MCP identity, and reports real renderer memory/thermal behavior. Any outstanding portrait-animation request that ended with `NATIVE_RECOVERY_HELD` needs a new explicit user request after recovery is stable.

This is a recovery and stability upgrade, not proof that one-photo separated hand/finger/hair/smile animation is now anatomically correct.
