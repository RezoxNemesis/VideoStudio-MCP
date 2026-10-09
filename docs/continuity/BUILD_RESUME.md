# Build and resume the VideoStudio continuation

The delivery contains the newest **source**, an earlier verified **3.4.7 APK**, and separate evidence. The newest Android source is not compiled/device-verified yet. The complete product blueprint remains unfinished; read section 35 of the continuity bundle for the exact matrix.

## Restore and verify

Run `sha256sum -c SHA256SUMS` from the delivery packet directory. `manifest.json` identifies the exact final source commit, tree and earlier APK/CI IDs.

Either extract `VideoStudio-latest-source.zip`, or restore the complete local history in a new directory:

```bash
git clone -b codex/studio-owner-editor VideoStudio-latest-source.bundle VideoStudio-MCP
cd VideoStudio-MCP
git status --short
git rev-parse HEAD
```

Compare HEAD with `manifest.json`. The source ZIP contains tracked files only; it does not include dependencies, credentials, Android SDK, signing keys, build outputs or APKs.

## Build before extending Android features

Use JDK 17, Gradle 8.9, Android SDK platform 36/build tools 35.0.0, and an API 33 emulator with `adb`. No Gradle wrapper exists in this repository.

```bash
npm ci
npm test
bash scripts/core-test.sh
python3 scripts/shader-test.py
gradle --project-dir android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
bash scripts/run-device-evidence.sh
```

The shader check requires EGL/GLES; the final command requires a booted emulator/device. Capture the six authored device cases and retain their files before uninstalling the app. Fix compilation/runtime/recovery failures and collect matching-source evidence before calling the latest features verified. The baseline APK's 117 passing tests do not validate the newer source.

## Publish while preserving upstream history

The local repository was reconstructed from the exact upstream `d652066ce428e4f128514da4d1caee919974554e` file tree. Its local root commit `f8a94cb` has different ancestry. Do not force-push this bundle history over GitHub.

`VideoStudio-local-from-d652066.patch` contains all current tracked changes against that exact baseline. It is **not** an incremental patch against the existing draft PR #35 at `4e30ae1`.

In a new clean upstream checkout with working network:

```bash
git clone https://github.com/RezoxNemesis/VideoStudio-MCP.git VideoStudio-MCP-upstream
cd VideoStudio-MCP-upstream
git switch -c codex/studio-owner-editor-continuation d652066ce428e4f128514da4d1caee919974554e
git apply --check /path/to/VideoStudio-local-from-d652066.patch
git apply --index /path/to/VideoStudio-local-from-d652066.patch
git diff --cached --check
git diff --cached --stat
```

Inspect current main/open PRs first and reconcile any intervening upstream work; this does not authorize resetting other work. Run the build/test commands above, commit the reviewed changes, and push an isolated branch/create or update a draft PR. Main automatically publishes the latest APK, so defer merging/release promotion until matching-source checks pass and the release is authorized.

The earlier isolated draft PR is https://github.com/RezoxNemesis/VideoStudio-MCP/pull/35. GitHub reads worked in this session; writes repeatedly timed out and the remote review head stayed unchanged. The local executor also lacked the Android SDK/Gradle/emulator. These are the concrete continuation blockers, not an owner-approval request.
