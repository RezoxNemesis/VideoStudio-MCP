# VideoStudio implementation ledger

Plan: `docs/superpowers/plans/2026-10-08-studio-owner-editor.md`.
Product authority: `VideoStudio_Complete_Engineering_Continuity_Bundle_v1.md`.

2026-10-08: recovered all 94 upstream files from main d652066 through the GitHub connector; all Git blob hashes matched. Local baseline snapshot f8a94cb is a reconstruction, not the upstream commit; publish changes on top of upstream d652066.

Baseline Node suite: 282 source smoke checks and connection test passed. Local executor has Node, Python, FFmpeg and a Java runtime, but no javac, Gradle, Android SDK or emulator. Git CLI clone could not connect through the sandbox proxy; a network permission call stalled and was cancelled. GitHub connector is working. Use it for isolated branch CI and artifact retrieval.

Ruling: the supplied bundle already defines architecture and explicitly authorizes ordinary decisions and continuous execution; use it as the approved design, implement without redundant design-approval gates.

Findings: image clips never bind to direct preview; preview requires final render for image timelines; timeline cards have no track/time layout; UI export is displayed as queued; ProjectStore.save has no revision check or undo history; renderer drops all source audio in mixed image/video timelines and does not apply clip volume.

Tasks 1–6: in progress; the complete roadmap is not claimed complete.

Android RED evidence: run 37817933116 / job 113451244875 executed 64 tests, with exactly the 3 intended ProjectStoreEditorTest failures (duration, stale save, durable revision). Existing 61 tests passed. Node/Worker CI run 37817932905 succeeded including Wrangler dry run.

The Java runtime includes jdk.compiler despite no javac executable. Direct java com.sun.tools.javac.Main with -source/-target 17 can run pure core tests; --release lacks ct.sym locally. Nested Node spawning of java is blocked by the execution sandbox, so use bash scripts/core-test.sh. Timeline core passes 33 checks.

Core Android GREEN evidence: run 37819049832 succeeded. The integrated time-scaled editor, shared program preview/render factory and foreground export session built and passed unit tests in Android run 37826924864; Worker CI run 37826924950 succeeded. Commit 0bb836b6726ec8f4d47f73406d0f381d9cde1aee is on draft PR #35, not main. These are compilation/unit results, not yet device playback evidence.

Installable debug APK from run 37826924864 is saved locally at `artifacts/editor-0bb836b/VideoStudio-editor-debug.apk` (98,737,203 bytes), SHA-256 `0dfb5fb7071d31b9cfcef5292ad00a8860f3d833e2a2dfba3ecc168594c88c5c`. Artifact 11571856567 is retained by GitHub. Artifact binaries are excluded from source commits.

Next hardening: cross-instance export cancellation transactions; revision-safe background import/output registration; keyframe continuity at splits; Android emulator preview/export proof. Advanced model, cloud, 3D/VFX and audio roadmap remain in progress.

Hardening RED: commit f3c46b266ac481c08a2c128b34e97e195653b950 / Android run 37827936373 / job 113485510278 ran 89 tests. Six expected failures proved late terminal export replacement, stale generated/append merges, and split curve discontinuities. Existing remaining tests passed. Fixes use cross-instance SQLite transactions, narrow current-revision merges, and exact sliced easing intervals.

Hardening GREEN: commit a81517766a7a36e4e2f520f4388d72c683559c60 / Android run 37829621972. Downloaded unit XML proves 91 tests, zero failures/errors/skips. Emulator API 33 run 37829622042 / job 113491272783 ran both StudioDeviceTest cases successfully: source image pixels, split/undo/redo/restart, manual service export with decoded frames, mixed video/image gap and audio export with sampled expected colours. UTP removed app data before adb artifact collection, so screenshots/MP4s were missing from artifact 11573321005. Fix evidence collection by directly installing/instrumenting APKs, retaining the app until files are pulled. Do not claim retained visual artifacts until this rerun succeeds.

Worker editor-protocol RED: local executable relay tests fail exactly three cases (missing revision validation, unknown operation/source URI acceptance, duplicate request IDs). Pause and legacy v3 tests pass. The shared JSON schema is now being wired to both the relay and Android EditorEngine.

Native parity RED: commit 850226 / Android run 37831200096 ran 99 tests, seven intended failures: autonomous revision bypass, different request IDs reused as receipts, missing roll/slide, fragmented PCM frames, and cross-manager checkpoint loss.

Native parity GREEN: commit c28592aac31c988b213b19b2cdaab3298d7f6f2f / Android run 37833966931 passed. Downloaded XML confirms 107 tests with zero failures/errors/skips. Worker run 37833966876 passed. Debug APK saved at `artifacts/c28592a/apk/app-debug.apk`, 98,789,458 bytes, SHA-256 `ab919e9fe09ce189b6795635a6a4016e73a52f48ed3789c187f7836e97796b00`.

Device run 37833966966 / job 113506171407: instrumentation reports OK (2 tests), including mixed video/image gap with audio. Final workflow exit 127 was the evidence check using absent rg, not an app test failure; portable grep fallback is added. Artifact 11574488092 retained 13 files, including screenshots, both MP4 exports, checksums and proof JSON. FFprobe confirms mixed output H.264 1280x720 / 30 fps, AAC mono 44.1 kHz, duration 4.000 seconds; FFmpeg decoded audio mean -19.7 dB / peak -16.6 dB. The Media3 NPE was resolved by probing actual source tracks and separating audio/video sequences. Repeat through the repaired harness before marking the workflow green.

Owner-scope RED: two executable relay tests proved project/selected-assets scopes were dropped and unselected analysis remained accepted. Native and relay scope enforcement, filtered graph queries and owner UI controls are in progress. All ten relay tests now pass locally. Full advanced animation, model-backed video generation, 3D/VFX, speech, five-provider adapters and offline cloud execution remain unfinished.
