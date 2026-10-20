# VideoStudio OMEGA Phase 7 — audio lane / lip-sync engineering handoff

**Contract:** [Nine-phase master contract](NINE_PHASE_MASTER_CONTRACT.md), VS-OMEGA-2026-10-20-v1  
**Branch:** `phase/omega-7-audio-timeline`  
**PR:** [#115](https://github.com/RezoxNemesis/VideoStudio-MCP/pull/115), targeting `integration/omega-nine-phases-20261020`  
**Status:** substantial *pure-engine slice*, **not** native integrated, visually accepted, or released.

## Owned implementation

- `OmegaAudioTimeline.java` retains its legacy bounded mono PCM16 envelope/RMS/peak, 24/30/60 FPS and silence API and protects sample lookup from long overflow.
- Immutable `AudioClip` and `ProjectAudio` retain app-owned asset identity and SHA-256, audio role, source sample trim, 1–8 channels, sample clock, authored microsecond position, gain/mute, FPS, frameCount and project revision. Reject nonfinite input, excess duration, duplicate IDs and stale sources.
- `waveformForClip` yields per-frame peak/RMS bins for an already **verified** PCM asset. Stereo channels contribute independently (no phase cancellation), with offset silence and source trim. The calling native adapter must prove the content hash: comparison to a supplied hash string is not proof of bytes.
- `activeAt` and `frameForTimeUs` support deterministic audio-lane scrubbing anchored to `NativeFrameClock`.
- `Cue` / `CueSheet` support explicit source-relative half-open timed visemes, immutable/CAS manual edits and project/source revision pins. Native mouth shapes remain `rest,closed,open,wide,round,teeth,tongue`. Model-estimated phoneme cues remain explicitly `MODEL_PREDICTED_UNALIGNED` and overlapping cues within one video frame flag manual review.
- `ExportEvidence` / `auditExport` require actual decoded audio samples/energy, MP4 hash, video frames and bounded duration. Even an evidence-present result is labelled `TRACK_EVIDENCE_PRESENT_LISTENING_AND_SYNC_REVIEW_REQUIRED`.
- `OmegaAudioTimelineTest.java` adds 19 focused JUnit tests covering original envelopes, noninteger sample clocks, scrub, source identity, stereo energy, gain/mute, offset trim, cue conflicts/aliasing and absent/silent export audio. Results must be read from current CI, not assumed.

No native track is rendered or played by this pure module; it does not open media or mutate owner project data. There is no new authoritative storage.

## Existing native production assets reviewed (not modified here)

- `SketchSpeechAssets` verifies imported generated-dialogue provenance, asset hashes and original timing; `SketchSpeechTiming` generates editable mouth/face cues, but the sketch export still records `audioMixedIntoSketch=false`.
- `StudioAudioWaveforms.analyzeOwnedDialogue` already decodes specifically owned media without Gallery browsing. Its async fallback can return zeroed bins on decoding failure; a safety-critical waveform lane must distinguish unavailable audio from genuine silence.
- `StudioAudioPreviewEngine` auditions Editor audio tracks; `NativeRenderEngine.buildStudioAudioSequences` assembles Media3 audio-only sequences from `StudioLayer.AUDIO`. These are capabilities to connect and exercise, **not** proof that a Sketch film contains narration.

## Integration-owner-only adapter proposal (required by contract)

**This phase branch may not edit shared Sketch/Editor/render/storage entrypoints.** After review, a separate integration-owned PR must:

1. Bind `ProjectStore`, `StudioAudioWaveforms` and `PcmWaveFile`: select only an app-owned asset, verify its bytes/hash, MIME, original sample clock and revision, decode bounded windows off the UI thread, and instantiate `ProjectAudio`. Reject stale/changed material and never scan Gallery.
2. Extend `SketchStudioActivity`'s Frames/Cels timeline and existing Editor lanes with visible waveform, source/provenance, time cursor, offsets, gain and unavailability state. Keep changes in `SketchDocument` / `SketchEditOps` / `ProjectStore` only, with undo, layer-lock protection, backward-compatible schema validation and a feature flag defaulting OFF.
3. Adapt `SketchSpeechTiming` / `SketchSpeechDialog` manual and predicted cues to `CueSheet`, surface cue collision/unaligned warnings, and commit manual edits through revision-pin checks. The native mouth/face stays editable; do not infer unobserved phonemes or invent alignment.
4. Place the same verified audio asset/timing in an Editor `StudioLayer.AUDIO` for `NativeRenderEngine` Media3 mixing, then **decode and inspect the actual MP4** (audio/video track inventory, samples, peak, duration, hashes). Convert that result to `ExportEvidence` and fail closed if silent or absent. No action may bypass `NATIVE_RECOVERY_HELD` or owner signing.
5. On owner-approved native Android device capture 0/25/50/75/100% audio waveforms and visible lips, test scrub/playback/undo, listen to final spoken MP4, measure p50/p95 where applicable and compare intelligibility and audiovisual sync. No artificial waveform/geometry score equals human acceptance.

## Verification and remaining blockers

- Base branch head `922266eec314ace4c0d7fd1ea595295e752db373` passed [original run 39368719381](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/39368719381); this does not validate new code.
- New code [81812e4](https://github.com/RezoxNemesis/VideoStudio-MCP/commit/81812e4a44ff01e53e585a08535305ff79cba709) and tests [885faf9](https://github.com/RezoxNemesis/VideoStudio-MCP/commit/885faf9aef9dc8327aead5466b9d0e7099d1136c) require [CI 39371712573](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/39371712573) to pass.
- Android command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace`; Java 17, Gradle 8.9, SDK 36. The focused `OmegaAudioTimelineTest` must be green too.
- **Unverified:** native Sketch/Editor waveform UI, project-persisted cue corrections, production audio routing from Sketch to final MP4, true encoded audio inventory and listening, lip-sync and on-device quality, 4.8.7 compatibility, original signer and APK release.
- No edits to other phase modules, current production renderer, `SketchDocument`, `SketchEditOps`, `NativeSpeechEngine`, `NativeStartupGuard`, signing or recovery safety. No main merge or installation.

**Gate:** engine prepared for integration review, not fully implemented or shipped.
## Revalidation after integration ownership-gate correction
The previous Phase 7 GitHub CI failure happened before Java compilation because the former two-dot ownership check attributed integration-only CI files to this branch. The integration owner fixed the checker to compare from the merge base without widening the Phase 7 allowlist. This documentation-only commit re-triggers the latest scoped CI. The waveform/viseme engine is still additive until native UI and audible MP4 verification exist.
