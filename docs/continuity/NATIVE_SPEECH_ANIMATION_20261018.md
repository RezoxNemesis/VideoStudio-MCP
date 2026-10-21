# Speech timing continuation — design and evidence

Continue from clean `4b043a886e2e8b03b63ea5ea5e483c3564c2fb6c`, app source
`def08743637c2ff6f8e8c52e0d33726f84e77a79` (4.7.6/code479).
The owner authorizes autonomous, reversible repository improvements and testing.
Preserve previous studies, original signer, application ID and device identity.

Implement optional `speech` on the existing native mouth/face operations. Explicit
half-open millisecond cue intervals select rest, pressed lips, open, wide, rounded,
teeth or tongue shapes, with strength and bounded coarticulation. Gaps rest; pressed
lip cues remain closed at their centres. Evaluate in physical time, then bake normal
editable native cels at the document's unchanged resolution/FPS/frame count. Reject
overlap, out-of-range cues and ambiguous simultaneous `poses` and `speech` atomically.
Face speech replaces mouth channels while preserving independently authored gaze,
lids and brows. All six existing 2D views remain supported, with rear occlusion.

Manual users edit timed cue rows without JSON and preview with the native renderer;
MCP requests share precisely the same operation/schema and version gate. A production
converter will map the existing speech runner's predicted phoneme duration records to
editable cues. Prediction is not forced alignment or recorded-speech recognition.
Preserve explicit authored timing as the fallback for existing recorded audio.

First write/run native dispatch and contract regressions, then implement timing and
manual controls. Test boundaries, bilabial closure, quiet gaps, 30/60 FPS agreement,
archive restoration, retries and pre-existing art. Run real English/Hindi synthesis
through the existing app-owned openpipeline pack/run surfaces when the pinned Kokoro
runtime/model is available. Retain actual audio, requests, timing and inference proofs.
This is self-hosted speech, not evidence of Android system-TTS execution.

Render isolated speaking mouths before complete faces. Inspect native screenshots
and full 4–8 second clips in front/left/right/rear/high/low views. Keep earlier failed
generations; fix shortcomings in native production code and retest affected scenes.
Review video may package unchanged native frames and the exact real speech master
with FFmpeg; this does not prove Android video export. Sketch authoring remains vector
cel authoring; audio is kept in the existing project editor's separate audio lane.

After focused checks, run the relevant full regressions once, review changes, commit
and build a signed candidate through the existing key cache. No merge, worker deploy,
stable release, Gallery access, fabricated device/MCP success or silent speech pass.
Workspace currently has no connected adb device and no callable VideoStudio connector.

Evidence root: `/workspace/engineering-review/speech-animation-20261018`.

## First measured results and repairs

The original app-owned pack/run surfaces generated real English “Maybe we should
go home.” (am_adam, 2.125-second stem) and Hindi “माँ, मुझे पानी दो।” (hf_alpha,
2.350-second stem), speed1, inside separate six-second 24kHz PCM masters with
1000ms start offsets. Immutable request/result archives, model/voice hashes and
PCM checks are retained in `audio-runner`. Checked-in original test fixtures
preserve exact master/stem bytes and raw product timing. Fixture import envelopes
are labelled test fixtures; they are not represented as fresh model/phone receipts.

The native converter consumes the original Unicode phoneme tokens and40Hz model
duration units, including boundary/stress/length/nasal/aspiration entries. It
rejects filtered/unsupported tokens or a mismatched PCM clock rather than stretching
timing. Stress anticipates its following sound; modifiers extend their preceding
sound. Native `speech.generated` selects an already-imported result by requestId
and lineIndex, verifies result/timing/audio hashes and current project asset
ownership, and expands to ordinary cue data before live-event recording. Manual
users load the same line into numeric cue controls. No inference credential is
read or copied by this loader.

First generation `mouth-v1`: twelve separate180-frame/30FPS/1280×720 studies,
2160 full native frames, nine archive pixel comparisons per scene, and actual
English/Hindi master audio. Native speech/manual/import tests passed; one PCM
content-provider fixture initially failed its authority/descriptor-close counting
setup and was retained for correction. Visual NEEDS_REWORK: narrowed O mouths
retained pointed smile corners. A native sampled-contour regression measured the
side at360px instead of the expected rounded midpoint394.65px. The repair blends
exactly split native Beziers toward an elliptical contour, keeping unrounded
geometry intact and retaining distinct authored profile anatomy. Later generations
must be inspected rather than relabeling this first generation as accepted.

Manual generated-line testing also reproduced missing optional cue-strength
defaults. The shared native resolver now supplies strength1 and the cue editor
reads optional values safely. The existing Android codec remains available for
compressed formats; PCM16 WAV has a bounded native path with real sample-clock
RMS analysis, stereo energy, descriptor offsets, strict malformed-file rejection
and no invented silent success on missing audio tracks/samples.

## Review repairs and reproduction

Three source-review findings were reproduced by native regressions: mixed paint /
cel-copy batches bypassed generated-line expansion; prediction annotations could
agree with themselves while disagreeing with the actual PCM stem; real concurrent
native artwork left a manual constructor retry pinned to a stale revision. Both
dispatch paths now expand to pure cue events, optional predictions are checked
against actual PCM sample frames, and clean compatible sessions can adopt the
authoritative drawing after a CAS conflict without dropping the dialog's edits.
Pending/recovery drafts remain protected. Changed resolution/FPS/frame count
requires reopening controls rather than silently changing the authored shot.
Older audio-only results and unavailable/null model durations remain importable;
missing predictions cannot invent automatic speaking cues.

The composed `face-v1` profile review reproduced a detached mouth during rounded
vowels: the leading lip sat451.79px instead of the409.34px facial landmark. Internal
native face anchors now retain leading-lip contact independently of mouth width,
roundness and size; left/right regression covers0.8/1.0/1.2 mouth scales. Isolated
mouth position remains explicitly controlled. The failed generation is retained.

Manual reproduction: generate/import a completed six-second English/Hindi dialogue
in the existing **Open Pipeline** form (one line starts1000ms,4000ms slot, speed1).
The imported output project already owns the exact master in its audio lane at0ms.
In Sketch Studio, create a short drawing and use **Frames / Cels → Timing / canvas**
to set1280×720,180frames at30FPS. Open **Brush / Text → Speech animation → Load generated
dialogue**, select that imported line and edit the numeric cue times/shapes/strengths.
Choose isolated mouth first, then complete face, anatomy and explicit view. Native
preview uses the selected cue midpoint and is silent. Save creates ordinary editable
cels on a new layer. Export the animation and place it at0ms with that same audio
master in the project editor; this continuation does not add an audio mixer to
Sketch's video-only exporter. Listening/timing refinement remains necessary.

With a configured connector, call `app_status`, require native/server4.7.7 support,
read `sketch_capabilities.speechAnimation`, and use a real imported request identity:

```json
{"type":"add_face_animation","layerId":"layer_1","faceId":"actor","settings":{"view":"left","eyeStyle":"almond"},"speech":{"generated":{"requestId":"YOUR_IMPORTED_REQUEST","lineIndex":0},"transitionMs":60}}
```

For owned recorded dialogue or refined timing, supply authored `speech.cues` with
half-open `startMs`/`endMs`, one of `rest,closed,open,wide,round,teeth,tongue`, optional
strength0..1, and optional transition0..200ms. Cues do not synthesize audio. A raw
`speech.prediction` may copy the original line startMs and timing segments without
workspace conversion. Do not combine these three sources or mix `speech` with
`poses`. Native operations preserve exact document timing and reject unsupported
symbols, insufficient bounds/budgets or changed source identity.

## Final contour review

Follow-up review checked fully open profiles at0.8/1.0/1.2 mouth scales rather than
only the recorded speech's narrower state. The first anchor approximation failed:
native distance from the actual head outline was3.43/5.89/8.35px. Generation
`profile-extreme-v1` preserves all18 native extreme-pose screenshots across six
views. `profile-extreme-v2` follows the actual native head contour at upper-lip
height, including the quadratic above the straight lip segment. Measured gaps are
0.025/0.087/0.053px or less in both profiles. Held head states share the same anchor;
mouth width/roundness changes retain attachment. The final read-only source review
reported no remaining P1/P2 findings in that fix. A fresh final animation generation
and signed-candidate checks follow; these extreme screenshots alone do not certify
speech intelligibility or lip-sync performance.

Full local generation `final-v3` passed1376 Android tests (zero failures/errors,
three optional skips), with24 complete six-second speech studies/4320 native PNGs
and216 archive pixel comparisons. Its source was unchanged during that run. This
was before the last curved-anchor extreme repair and remains separately labelled.
348 Node tests and306 smoke checks passed; worker dry-run passed without deployment.
Final-source focused results and original-signer CI/APK identity will be recorded
after they complete, without relabeling older evidence as newly rendered output.

Final-source focused check:74 tests, zero failures/errors/skips, including all
speech/import/PCM/manual regressions and the affected mouth/face native/UI tests.
No model inference or phone execution is substituted by these tests. Final full
speech captures will be rerun from the committed application source while CI
builds the original-signer4.7.7/code480 candidate.

## Verified candidate and final evidence

Application source: `e78ef4d0b8ae6c7e85ba1e1a0fbebbbd9d777eac`.
[CI39143965356](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/39143965356)
passed1377 Android tests with zero failures/errors and two optional skips. The
downloaded APK independently matched package,4.7.7/code480, source proof and the
original pinned signer. SHA-256:
`96c5e7c7fea71d2dc0d7dced04ab62b09e4966baf40478349e5d76d0c87eead1`.
Bytes:130857428. Original signing key remained in the existing CI cache.

Private candidate below the evidence root:
`candidate-4.7.7/VideoStudio-4.7.7-Speech-Candidate.apk`.
`final-v4/review.html` contains all24 committed-source speaking studies:180frames
at30FPS/1280×720/six seconds,4320 exact native-export PNGs and216 archive pixel
comparisons. This final batch passed35 native tests with zero failures/errors/skips,
and no source hashes changed while it ran. Compared with the preceding full
generation,4303 native frame hashes stayed identical and17 changed under the final
profile-contour repair. All earlier failures and generations remain retained.

The source-review findings are verified fixed. New manual controls and strict MCP
source/relay admission are tested; live ChatGPT/MCP and phone installation/playback
remain unverified. The existing self-hosted speech runner generated real audio;
Android system-TTS execution and native Android video encoding are not proven here.
Model duration timing remains an estimate, with pronunciation/intelligibility and
performance lip sync requiring listening review. Mechanical/anatomical visuals are
acceptable stylized studies, not professional acting approval or genuine3D.

`Final-Study-Index.json`, `Full-Verification.json`, per-scene assessments, source
snapshots, original model/audio proofs and checksums retain the exact evidence.
The complete review bundle is `VideoStudio-4.7.7-Speech-Evidence.zip`, with the APK
linked separately. No main merge, stable publication or worker deployment occurred.
