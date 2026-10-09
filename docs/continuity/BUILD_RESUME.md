# Build and resume VideoStudio

Continue the complete blueprint in `VideoStudio_Complete_Engineering_Continuity_Bundle_v1.md`. Sections 36–37 supersede the former missing-toolchain/publishing ceiling in section 35. The complete product is still unfinished.

## Current verified checkpoint

GitHub review branch `codex/studio-owner-editor`, draft PR #35, published `82c600faf7dc18e5f4a772f14d9a42ecdc7a0dfe` matches local `d8ee058d9412f7e89cbd37cb6540c133cf3d9b2c` at tree `6b415c64a2e6fe5656906ee5f8fb188ba6406f26`.

Android run 37890111134, device run 37890111246 and Worker run 37890111099 all succeeded. Device logs report all six API 33 cases passed and 28 evidence files retained. These CI logs/metadata were inspected; 82c artifact bytes and APK certificate were not independently fetched. The earlier b3fb checkpoint has downloaded XML, APK and four fully decoded MP4s, saved in `artifacts/verified-b3fb1ba/verification-manifest.json`.

Current schema 5 bulk editing/export recovery changes freshly compile 73 main and 33 test Java sources and pass 200 JUnit/Robolectric cases. Node: 282 smoke assertions, 20 connection cases, 19 executable relay cases. Actual Wrangler bundle compilation passes. These newest changes still require matching Gradle/APK/device CI before claiming device verification. See section 37 and `artifacts/current-2026-10-09/bulk-recovery-*-final.log`.

## Build and test

Installed in `/workspace/toolchains`: verified JDK 17, Gradle 8.9, Android SDK 36/build tools 35.0.0, platform tools, emulator and API 33 images. `activate.sh` configures writable tool caches without changing the owner's HOME. Free npm dependencies are installed and `package-lock.json` now supports reproducible `npm ci`.

```bash
source /workspace/toolchains/activate.sh
npm ci
npm test
bash scripts/core-test.sh
python3 scripts/shader-test.py
gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
bash scripts/run-device-evidence.sh
```

This managed Terminal sandbox currently refuses Gradle's TCP daemon socket even with `--offline --no-daemon`. Additional Terminal network grants can still prompt despite the user's standing authorization. Do not wait on routine grants: use the connected GitHub CI runner for full Gradle/APK/device builds and the offline runner for fresh Java unit verification:

```bash
source /workspace/toolchains/activate.sh
python3 scripts/android-offline-tests.py
WRANGLER_SEND_METRICS=false XDG_CONFIG_HOME=/workspace/toolchains/xdg-config WRANGLER_LOG_PATH=/workspace/VideoStudio-MCP/artifacts/wrangler-logs npx wrangler deploy --dry-run --outdir artifacts/worker-bundle
```

The offline runner needs a prior successful Gradle test build and cached Robolectric SDKs. Each run compiles all Java sources into an isolated evidence directory, uses the exact cached dependency classpath, refreshes the editor schema in a copied test resource archive, and records source SHA-256 hashes. It does not assemble an APK or rebuild changed Android resources/manifests. Use standard Gradle CI for those changes. No hardware acceleration is available locally; retained hardware CI evidence is the actual device verification.

## Preserve repository history and owner installations

The local repository was reconstructed from the upstream `d652066ce428e4f128514da4d1caee919974554e` tree and has different ancestry. Never force-push local bundle history over GitHub. Inspect the live review head, construct changes on that exact remote parent, require the expected head SHA when advancing the ref, and verify the resulting tree equals the local reviewed source tree.

GitHub connector writes now work. Orez has acknowledged coordination through issue #36 and is monitoring without overlapping edits: https://github.com/RezoxNemesis/VideoStudio-MCP/issues/36. The draft PR is https://github.com/RezoxNemesis/VideoStudio-MCP/pull/35.

The downloaded b3fb APK SHA-256 is `865fa790151d0a87261bcc19ec29f8f981d4b3af9e60eae7af17d0242b056947`. Its signing certificate differs from the archived 4e30 APK. Do not claim upgrade compatibility or instruct uninstalling an owner's app/data. Compare new APK signing certificates before installation; local test APKs use a separate test key. No signing keys are included in source or delivery artifacts.

## Continue work

Legacy `apply_tool` now shares atomic editor transactions, locks, source bounds, history, keyframes and command receipts in both service and foreground paths. Journal replay binds action/arguments; interrupted synchronous edits use SQLite receipts. Schema 4 adds executing effect presets with older operation versions retained. STOP rejects delayed callbacks and old success replay while keeping owner editing available.

Legacy plans, creator presets and autonomous edits now use atomic shared operations. Foreground remote exports use the service, retain the exact graph revision and resume a command-bound job. Next: finish their matching-source CI verification, then continue remaining editor depth and full phase A–H requirements. Direct cloud-provider OAuth, cloud execution/mirror reconciliation, full articulated 2D, model-backed video, complete 3D/VFX, ASR/captions/lip-sync/recap, accessibility/tablet/performance and large cloud benchmarks remain unfinished. Keep unavailable providers explicit and preserve original media.
