# Build and resume VideoStudio

Continue the complete blueprint in `VideoStudio_Complete_Engineering_Continuity_Bundle_v1.md`. Sections 36–38 supersede the former missing-toolchain/publishing ceiling in section 35. The complete product is still unfinished.

## Current verified checkpoint

GitHub review branch `codex/studio-owner-editor`, draft PR #35, published `4129378b4fbfaba80b0e59a078c03abc00081e31` matches local `9d1f92da5dc5afdcd53fd71d46b8717c541986fe` at tree `475e13e8a3671488ff804e1aa12777de3c478cea`.

All matching 4129378 workflows succeeded: Android run 37895158494 reports 200 tests with zero failures/errors/skips, signed APK 98,958,847 bytes / SHA-256 `a3d73795ad4bea97447ada593bb0ad958a2af663cfc100505ac0afe30dc4b676`, certificate `7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`; device run 37895158443 reports OK (6 tests) and 28 retained evidence files; Worker run 37895158717 succeeds. These logs/metadata were inspected; artifact bytes were not independently fetched. Certificate differs from downloaded b3fb evidence, so owner upgrade compatibility remains unproven.

Newest schema 6 / app 3.4.8 Vault storage source freshly compiles 76 main and 35 test Java classes and passes 222 JUnit/Robolectric cases. Node: 282 smoke assertions, 20 connection cases, 21 executable relay cases. Actual Wrangler bundle compilation passes. It replicates verified encrypted/plain Vault objects across existing owner-connected folders, persists upload identity before writes, repairs corruption in place and shares owner/MCP durable jobs. A seventh device case exercises the actual system folder picker and encrypted replication; matching Gradle/APK/device CI is pending. See bundle section 38 and `artifacts/current-2026-10-09/vault-fabric-*`.

Local 20 GiB benchmark (`artifacts/large-media-20GiB-2026-10-09/evidence.json`): every logical byte hashed/packed/restored, 80 × 256 MiB chunks, restart at 2 GiB, exact full restored checksum, -Xmx64m. Sparse synthetic plaintext fixture; this is not cloud transfer, encrypted-large-video or Android device performance evidence.

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

Legacy plans, creator presets and autonomous edits now use atomic shared operations. Foreground remote exports use the service, retain the exact graph revision and resume a command-bound job. Their matching-source CI is verified. Next: publish and verify storage-fabric APK/device CI, implement cloud-backed hydration/range reads, then continue remaining editor depth and full phase A–H requirements. Direct cloud-provider OAuth, cloud execution/mirror reconciliation, full articulated 2D, model-backed video, complete 3D/VFX, ASR/captions/lip-sync/recap, accessibility/tablet/performance and large cloud benchmarks remain unfinished. Keep unavailable providers explicit and preserve original media.
