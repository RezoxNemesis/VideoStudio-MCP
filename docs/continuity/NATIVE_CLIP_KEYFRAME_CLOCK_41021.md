# Clip-local presentation clock repair

Actual original-signed523 (`acf57cd6`) on isolated API29 Android exported the
two supplied videos into an eight-second timeline. Both four-second clips had
local keys from scale1 at0ms to1.04 at4000ms. Independent source/output fits at
the first/middle/last frames measured first-clip scales1/1.02/1.04 but second-clip
scales1.04/1.04/1.04. The unchanged failed MP4 and six-frame comparison remain in
`editing-two-subjects-20261024/native-results523/independent-review-two-subjects-v1/`.

Pinned Media3 1.11.1 source confirms cumulative EditedMediaItem presentation
offsets are added before shader effects. App evaluators consume those timestamps
as local. Correct actual render callbacks while preserving their manual/local
sampling APIs, source-in trimming, authored split offsets and frame PTS. Keep
composition-level StudioTimedImageOverlay/StudioTimedTitleOverlay GLOBAL. Cover
normal and studio-track item sequences and layered image roles. Inspect preset,
opacity, procedural/atmosphere callbacks too. Use real constructed presentation
durations in microseconds; account for image minimums, rational frames and speed.
Do not use TimestampAdjustment pairs: CompositionPlayer deactivates these and
would diverge from export. Preserve original constructors/default-origin0.

Root executes genuine RED first, preserves test/source generations, implements,
then focused/full regressions, exact feature push/CI/original-signature candidate.
Retake the same actual eight-second native edit and independently measure second
clip start/middle/end. Existing source/crop/audio identities remain unchanged.
The old MP4 video ends8.000s; audio/container8.034104s includes nonzero samples.
Do not call that entire container exactly8s or silently describe it as silence.

Current intended release4.10.21/code524. PNG still-frame export remains tests-only
in a separate branch and moves to future525 after this critical repair. No main
merge, Worker deploy, stable publication, new signer, data/identity reset or art/
film/live phone/MCP acceptance. Four-minute character film remains unfinished.

The full2513-case combined run additionally exposed the existing story-audio
interruption race: a channel can close between an explicit cancellation check
and its NIO read, producing ClosedByInterruptException rather than the existing
InterruptedIOException contract. The unchanged old second-pass test failed.
A new real-file regression injects the interrupt at the native stream/header
boundary and deterministically fails against523. Normalize only that precise
exception at the existing render try boundary, retaining its cause and thread
interrupt flag and leaving source/staging cleanup and all other I/O failures
unchanged. Existing16story-audio checks plus the new case pass17/0FAIL6.409s.
The original22clock tests and original cancellation assertions remain byte exact.

Fresh combined local524 validation passed2514tests/0failures/0errors/28skips in
467suites,727.396s. All1085 functional file hashes stayed unchanged during the
run. Fresh final Node verification passed820 plus306smoke checks, with1292
content hashes unchanged. Earlier partial provenance failure and full audio
failure remain preserved privately. Original-signed524 CI/APK/native codec retake
are still required; passing unit checks does not establish phone/MCP execution,
artistic acceptance or a finished four-minute film.
