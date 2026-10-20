# VideoStudio OMEGA — Phase 7: Sketch and Editor audio workflow integration

**Branch:** `phase/omega-7-audio-timeline`  
**Integration target:** `integration/omega-nine-phases-20261020`  
**Contract:** `docs/omega/NINE_PHASE_MASTER_CONTRACT.md`  
**Status:** initial additive engine prototype; phase incomplete

## Engineering slice currently delivered
OmegaAudioTimeline converts bounded PCM samples into per-frame peak/RMS waveform bins and silence flags.

## Verification to run
OmegaAudioTimelineTest; 24/30/60 FPS mapping, silence, out-of-range timestamps and invalid clocks.

Android build and regression command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17, Gradle 8.9, Android SDK 36. Check `.github/workflows/omega-nine-phase-ci.yml` on integration branch for exact workflow evidence. Never interpret a passing test as native visual acceptance.

## Mandatory next integration / full-phase gates
1. Expose visible and scrubbable waveform lanes in Sketch Studio and Editor using existing WAV and Media Bin references.
2. Timeline dialogue → phoneme/viseme timing with editable correction and no unsupported inference claims.
3. Verify export pipeline actually mixes narration and SFX into final MP4.
4. Audibly inspect owner-approved native video, prove sync against mouth motion and accurate clock.

## Current production boundary
Only waveform timing algorithm; audio engine and MP4 export untouched.

## Handoff checklist for next ChatGPT agent
- Read the live repository contract and draft PR for this branch before modifying anything.
- Preserve branch ownership, source hashes, frame clock, undo, atomic project revisions, privacy and owner safety gates.
- Implement in small tested increments; do not edit another phase's owned files.
- Post exact commit, tests, CI checks and remaining unverified native work in the phase draft PR.
- Do not merge to main, claim release, browse Gallery, change signing key or bypass NATIVE_RECOVERY_HELD.
