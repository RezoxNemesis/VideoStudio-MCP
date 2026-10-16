# VideoStudio 4.5.4 — real decoded audio energy controls existing hand-drawn mouth cels

This build continues the native Sketch Studio 2D animation work from 4.5.3. The verified 4.5.3 APK and your earlier projects are not overwritten or merged during development.

## Android phone workflow

1. Record or synthesize a voice line and **explicitly import it into a VideoStudio project**. VideoStudio never searches your Gallery in the background.
2. Sketch Studio → More → 2D animation. Prepare one or more authored dialogue cues with start/end frames, and four separately drawn, anatomically aligned vector mouth parts: closed, open, rounded and teeth.
3. Choose **Cels / dialogue → Audio-waveform mouth timing**. Select the existing VideoStudio audio asset, the dialogue cue and the four mouth drawings. Specify the recording's source offset (milliseconds; 0 by default).
4. Tap **Analyse selected audio**. The app's *native Android MediaExtractor + MediaCodec* decoder samples the explicitly selected recording on a separate cancellable worker at the exact animation-frame count, for at most 30 seconds and 720 frames.
5. The real audio amplitude gates the existing text-based mouth forms. During measurable quiet periods the mouth closes; during waveform activity the text-derived viseme cels are shown. The operation returns an editable new program, preserving the prepared drawing and other dialogue cue ranges. It is fully validated by the same native SketchAnimationCompiler before acceptance.
6. Manually inspect lip movements against the original audio and add/edit phoneme cels as needed. **The same audio asset must still be placed into the final video-editor timeline** after exporting from Sketch Studio; this feature does not automatically mux it into the silent native sketch export.

## What is actually implemented

- `StudioAudioWaveforms.analyzeOwnedDialogue`: checks that a chosen audio asset still belongs to its persisted VideoStudio project; allows only user-imported `content:` or `file:` audio URIs, never HTTP(S) or Gallery enumeration. The decoder only runs off the main thread, verifies playback-window bounds, and never sends raw audio to ChatGPT/cloud. The existing 96-bin UI waveform remains unchanged; higher-resolution analysis has a separate hard bound of 720 dialogue-frame buckets.
- `SketchVoiceActivityTiming.apply`: accepts a real 0–1 decoded RMS activity envelope, verifies one waveform sample for each dialogue frame and four distinct actual prepared mouth objects, gates approximate spelling-derived visemes by measured energy, de-jitters lone silent peaks, closes the final lip position, and retains prior non-overlapping authored cues. Silent input or invalid/NaN data is rejected without touching saved edits.
- `SketchAnimationActivity.audioMouthTiming`: a visible, user-initiated audio/project picker and an asynchronous, cancellable worker separate from the editor's normal preview/save lane. New source edits, closing the app or newer commands invalidate stale results, so the previous animation cannot be overwritten by delayed audio analysis.
- Existing prepared rig, Cel, MP4 and audio timeline storage remain compatible; no new paid dependency or permission is required. Native app code 454, version 4.5.4.

## What this feature is *not*

**This is audio-*activity*-gated mouth animation, not phoneme recognition or forced alignment.** A louder sample can indicate speech but may also be background music, a hit sound, or noise. The text algorithm assigns approximate English grapheme mouth shapes; it does not understand Hindi/Hinglish phonetics, who spoke the line, actual words in the recording or their exact articulation. No AI speech separation, no lip-sync accuracy certification and no synchronized audio export are claimed.

For genuine high-confidence finished animation, the roadmap still requires phoneme-aware forced alignment and source-audio muxing, along with richer facial rigs and player/phone inspection of the actual resulting MP4.

## Regression gates

- Audio-activity and silence regression verifies exactly one visible mouth cel per dialogue frame, a quiet/closed mouth when no activity is measured, unchanged source sketches, separate preserved dialogue ranges, invalid waveform rejection and actual native bake compatibility.
- Owned-audio policy tests refuse forged asset identities, remote HTTPS media URLs, invalid MIME and oversized decode windows *before* media access.
- Existing Android 2D/3D tests, native film sample, signer/package identity, and 4.5.3 two-human animation proof must remain green.
- A physical phone must test multiple voice recordings, decoder behavior on the actual Android device, the editing workflow, and the final video soundtrack separately before this can be declared artistically accepted.