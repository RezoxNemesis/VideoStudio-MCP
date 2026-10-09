# Build and resume VideoStudio

Continue the complete blueprint in `VideoStudio_Complete_Engineering_Continuity_Bundle_v1.md`. Sections 36–45 supersede the former missing-toolchain/publishing ceiling in section 35. The complete product is still unfinished.

## Current verified checkpoint

Latest matching published preparation source f42d4022597c5fccd3a5eceec21579659855322f, local88ecb4d/tree d062652100f3a4a8e6daf5916bd289142deb66d8: Android37913566915 passes302 unit cases/APK99,073,555 bytes SHA2567ba1a47dcb877ab2dd98af0eea451ecc41af8d8e573f50a68bb0a3f2c2970a8d, certificate7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. Worker37913566897 and API33device37913566923 succeed (8 existing cases/38files). Codec/Vault/owner baseline proof only: new original-clock window/source-pin helpers are not directly device exercised. Local private journal now passes322 cases; matching APK/device and continuous audio/mux/shared engine remain pending. Follow docs/superpowers/plans/2026-10-09-segmented-render.md. The older checkpoints below retain their measured evidence.

Latest matching journal cc3450f90b6c9d8cc615769214662950d470cf2b/tree765e32504990b5bc7d97aec2463ac99f8b39d102 passes Android322/APK99,089,939 bytes SHA2563ce848bfdb9477a76213dd7f919553670fc3a081c7f09a0256ec881ab2ac463b, Worker and8 existing device cases/38files. New audio/mux source passes351 local units and bounded review; dedicated ninth device assertions are added but unexecuted. Continue matching Task3 APK/device then Task4 shared dispatch, verified reuse/STOP/cache references and the full roadmap; do not stop at a checkpoint.

GitHub review branch `codex/studio-owner-editor`, draft PR #35, published `806620a9f138ae2928dcafd2962570a7867734d0` matches local `ba07402` at tree `22ece642b2fbed421687f271e399943b32731830`.

All matching storage workflows succeeded: Android run 37901073791 reports 222 tests with zero failures/errors/skips, signed APK 98,991,611 bytes / SHA-256 `1969af653b21a380ddac60fcd8d70cc49bd1352828991285611b99898b165c30`, certificate `7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`; device run 37901073760 reports OK (7 tests) and 31 retained files; Worker run 37901073863 succeeds. The seventh case uses actual DocumentsUI folder grant, encrypted replication and verified restart reuse. Initial fixture failures omitted the naming Connect step and exact-case selector; corrected without weakening the grant. Logs/metadata inspected; artifact bytes not independently fetched. Certificate matches4129378, but owner upgrade compatibility remains unproven.

Recovery schema7 / app3.4.9 source freshly compiles 76 main / 36 test Java classes and passes 242 JUnit/Robolectric cases. Node: 282 smoke assertions / 20 connection / 22 relay cases; actual Wrangler bundle passes. Vault recovery verifies and downloads whole intersecting chunks, recovers binary manifests, falls back between connected replicas and resumes plaintext restoration from verified whole-chunk prefixes. Owner/MCP use one durable source/manifest-bound job; atomic publication preserves later owner source changes. The device case now additionally removes only fixture-owned Vault cache, restores/decrypts/decodes the original PNG and checks the recovered editor preview. Matching recovery publication576098de passes Android37903124572 (242 tests), Worker37903124578 and device37903124755 (7 cases,32 files). APK SHA256ccaba6a8b2719565fcfd88910c1f7ca6c549873bff31369a3ba100238bd3e98e,99,008,011 bytes; certificate7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. Logs/metadata inspected; binaries not independently fetched. See bundle section 39 and `artifacts/current-2026-10-09/vault-recovery-*`.

Local 20 GiB benchmark (`artifacts/large-media-20GiB-2026-10-09/evidence.json`) hashes/packs/restores every logical byte, 80 × 256 MiB chunks, restart at 2 GiB, exact restored checksum, -Xmx64m. Sparse synthetic plaintext/local evidence; no cloud transfer, encrypted-large-video or Android performance claim. Pure Vault/range 52 checks cover the shared parser refactor.

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

Legacy plans, creator presets and autonomous edits now use atomic shared operations. Foreground remote exports use the service, retain the exact graph revision and resume a command-bound job. Their matching-source CI is verified. Storage-fabric CI is verified. Recovery CI is verified; app3.4.10 codec APK/unit270/Worker CI succeeds but its8-case device run fails6 at the unspecified encoder fps check. Corrected190ab4e passes matching271 unit/APK/Worker and8device cases (38files), with real conservative/software route proof. Next: finish matching codec CI and segmented render, then continue editor depth, direct provider adapters, cloud mirror/executor and remaining phases and full phase A–H requirements. Direct cloud-provider OAuth, cloud execution/mirror reconciliation, full articulated 2D, model-backed video, complete 3D/VFX, ASR/captions/lip-sync/recap, accessibility/tablet/performance and large cloud benchmarks remain unfinished. Keep unavailable providers explicit and preserve original media.
