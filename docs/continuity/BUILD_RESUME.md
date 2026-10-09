# Build and resume VideoStudio

Continue the complete blueprint in `VideoStudio_Complete_Engineering_Continuity_Bundle_v1.md`. Sections 36–47 supersede the former missing-toolchain/publishing ceiling in section 35. The complete product is still unfinished.

## Current verified checkpoint

**Preview correction update:** actual preview Gradle402unit/APK passes; API33 audio fixtures fail because the encoder input report omits PCM encoding. No APK release publishes. Current correction uses only the explicit configured PCM type when the platform report omits it, with actual rate/channels and unsupported-type refusal; fresh suite404 passes after RED1/7. Important service-test gaps are closed: destroy Activity before commands, observe actual command-specific replay. Matching corrected native run is next. Current delivery is still pending, and live external ChatGPT pairing remains unverified.

**Owner preview request (2026-10-09):** finish a usable APK checkpoint3.4.11/version351, deliver a direct GitHub prerelease download, then await the owner’s resume/continue instruction. This does not cancel or complete the original blueprint. Current preview source freshly compiles88 main/50 test sources and passes402 unit cases. Native AAC drain adds8192 PCM frames only for the measured C2 route; bounded MP4 edit metadata removes encoder-only padding without changing raw packets. Review RED no-edit-list and replaced-output cases pass after fixes; writer/reopened SHA+size must match before native/service success. Current instrumentation Java compiles, but matching APK/device execution and release publication are pending. The owner-preview workflow tests the exact signed deliverable, including background autonomous service edit/retry/export, before publishing. See OWNER_PREVIEW.md/request and bundle section50.

Matching coordinator source5c31e39/local4154543/tree61a6e7 passes385 unit cases, APK99,139,091bytes SHA2568a6f2290647a25b2d6bbc34d10d46bbf76447525fda8f8d118b6e065da17d9b1. Android37929321990 and Worker37929321989 succeed; device37929322011 existing8pass/newsegmented1fails on measured missing AAC tail. This is superseded only when the preview’s actual native evidence passes.

Current local shared coordinator compiles85 main/47 test Java classes and passes385 full Android unit cases, including43 focused lifecycle/journal cases. Bounded review closes cleanup, progress, terminal-delivery and durable-route/retirement findings. Public segmented dispatch/cache eviction/native shared execution remain pending. Matching678e633 Android362/APK/Worker pass; API33 emulator existing8 cases pass/new segmented1fails. Whole and audio-only10s AAC both have430 packets, first-36281us,last9925079us,raw9984580us,presentation9948299us. Encoder draining and padding metadata correction are next; do not weaken extent checks.

Matching AAC-preflight source85e3180ce6f4f8eda74f5fc21a1de401ad865cb7/local6db7b84/tree c7619acf88f3ab6d011eca924e0dfd980e8e7b5f: Android37925284877 passes362 unit cases; APK99,106,323 bytes SHA2568d86d9e4bc02dc437b0e213f12d4956e43d39aa5f34c5810eedda0f6153cf779; Worker37925284919 succeeds. Device37925284900 runs9 cases/1 failure; existing8 pass. Private evidence archive now succeeds. Actual continuous10s AAC raw9984580us,44100Hz,delay1600,padding0 => presentation9948299us, so strict input extent rejects the join. Installed Media3 empty encoder EOS and API33 software AAC flush behaviour suggest missing tail; fresh no-seek packet count/lastPTS diagnostics are next. Do not stretch packets or weaken duration validation. Task4 local coordinator/journal34 cases pass using synthetic codec fixtures; public dispatch/cache lifecycle/native shared execution are unfinished, with review findings under regression tests.

Latest matching published preparation source f42d4022597c5fccd3a5eceec21579659855322f, local88ecb4d/tree d062652100f3a4a8e6daf5916bd289142deb66d8: Android37913566915 passes302 unit cases/APK99,073,555 bytes SHA2567ba1a47dcb877ab2dd98af0eea451ecc41af8d8e573f50a68bb0a3f2c2970a8d, certificate7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd. Worker37913566897 and API33device37913566923 succeed (8 existing cases/38files). Codec/Vault/owner baseline proof only: new original-clock window/source-pin helpers are not directly device exercised. Local private journal now passes322 cases; matching APK/device and continuous audio/mux/shared engine remain pending. Follow docs/superpowers/plans/2026-10-09-segmented-render.md. The older checkpoints below retain their measured evidence.

Matching diagnostics b10bdeac13b2b5f2836375b2536110338ab20db9/locald438da2/tree f8f40b526c47c501549f72d37d9c5cd9e212ef4a: Android37922461892 passes351 unit cases/APK99,106,323 bytes SHA256476709a48f323ef7d670f2b0363ab45103fa6736c15615b1e0654610c878f583; Worker37922461915 succeeds. Device37922461742 runs9 cases with1 failure: AAC firstPTS=-36281us was incorrectly rejected; existing8 pass. Android13 MPEG4Writer supports negative preroll via an edit list, but its empty EOS timestamp must include the internal start offset. Four failing regression cases precede the correction; focused33 pass and current instrumentation source compiles. Matching corrected device execution is pending. External-storage app-UID tar produced a truncated archive; move test evidence into app-private files/evidence and archive from the app working directory, keeping0600 output permissions. Shared lifecycle has a RED12-case contract (10 failures), currently untracked/unpublished. Continue matching Task3 device correction, Task4 dispatch/reuse/STOP/cache references and the full roadmap without stopping at a checkpoint.

Final AAC correction has40 focused GREEN after review RED4/32, paired preflight RED1/24 and API29 RED2/25. Presentation duration subtracts AAC gapless sample counts from raw track extent; shifted EOS supports short all-negative programs. API29 negative AAC platform mux is explicitly rejected before output; segmented support there remains unfinished. Bounded review closes these corrections. Journal lazy-access/route-invalidation contract adds RED2/22 locally outside this correction publication. Public exports still use the ordinary native engine; shared lifecycle remains WIP. Matching corrected APK/device evidence is next.

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
