# Native reference-analysis coverage repair — focused root checks passed

Worktree `VideoStudio-Native-Analysis-Coverage-v41023`, branch
`fix/native-analysis-coverage-v41023`, starts combined525 source
`2d100d9da66ea03eafd84e84b2ae8d979d4477a3`. Root owns version selection,
all native/JVM/Gradle/ADB execution and actual signed-APK retakes. Root executed
the ten prepared public-API tests against unchanged FULL525:10run/8fail in2.792s.
The genuine failures include exact8expected/6returned, absent coverage,
cancellation and bitmap ownership. Root retained source/launcher/logs under
`film-production-20261022/native-analysis-coverage-525-red-v1-*`.

After that gate, the agent changed the analyser and this documentation/AGENTS
header, and added the separate small-frame control described below. The ten
original assertions and existing orientation tests remain byte-identical. No version, dependency or canonical action changes were made here.
Root's exact production override then passed14/14 tests in3.471s: the ten new
coverage cases, three unchanged orientation cases and one small-frame control.
The source SHA256 is
`9e490035460218f19a4cad4d6d05bfebcd7575b400ef52395716b7252023b7af`.
These are focused checks on retained FULL525, not integrated526 or actual HEVC
decoder/phone/GPU validation. Root logs are
`film-production-20261022/native-analysis-525-green-v1-*`.

## Actual failure to retain

The original-signed Android524 app imported the user's
`VID_20261023_193912_774.mp4`, SHA256
`d4b9ef9433ed15324bbd8f378b04a5bf721f78ccee4aed105c6128f5ed7d772a`.
Native `analyse(asset,8,0,26272)` produced six contact frames and `ok:true`.
The source hash was retained. Actual source-bound app evidence is outside Git:
`/workspace/work/editing-nine-references-20261024/actual-latest-reference-524/results`.

Requested times were `0,3753,7506,11259,15012,18765,22518,26272` ms. Returned
sample rows begin at7506 ms; the opening0/3753 samples are missing. The outer
private QA caller correctly recorded PARTIAL6/8. The published v1 analyser
silently skips each null bitmap and supplies no completeness/failure inventory.
The successful later samples do not establish how the opening was edited.

## Small shared repair contract

Keep the public request, existing6..16 frame clamp, requested-time schedule,
metadata fields, native JPEG contact sheet, `samplingMode:"closest_sync"` and
`exactFrameTimes:false`. Retrying a requested slot must use the SAME time and
`OPTION_CLOSEST_SYNC`; do not borrow a neighbor, invent actual PTS, silently
switch decoders/sampling semantics, downsample the source contract or synthesize
artwork. A first bounded repair allows at most3 attempts per planned sample.

Add response `coverage`:

```
{requestedFrames, decodedFrames, failedFrames, complete,
 failures:[{requestedTimeMs, attempts, reason}]}
```

`requestedFrames` counts the effective, already-clamped sample plan. Every slot
ends in one decoded frame or one failure. Reasons distinguish
`decoder_returned_no_frame` and `decoder_exception`. Partial success retains
legacy request-level `ok:true`, with `coverage.complete:false`; zero successful
frames retain the existing actionable exception behavior. Source/data-source
permission denial propagates without retrying denied access. Decoder-call
runtime failures can exhaust the finite per-time budget and be represented as
failed samples while other slots continue.

Cancellation checks before opening and around each decode must preserve the
thread interrupt flag and throw `InterruptedIOException`. Every decoder-returned
bitmap, scaled thumbnail, temporary signature/contact bitmap and retriever must
be released on normal, partial, permission/error and cancelled paths. No source
asset field or source file byte is changed. No published content is removed.

## Prepared test scope

`NativeMediaAnalyzerCoverageTest` has10 public-API unit cases: initial two-null
warmup; permanent-null coverage; bounded recoverable exception coverage; already
cancelled admission; cancellation after a returned bitmap; permission failure
cleanup; bounded all-null failure; unchanged healthy metadata/time behavior;
the existing6-frame minimum/window; and invalid-asset admission.

Its explicitly synthetic MMR boundary returns tiny test bitmaps, and the actual
app's contact-sheet Canvas/JPEG code still executes. These are decoder-protocol,
coverage and resource-ownership tests, never uploaded-video decoding, GPU output,
creative scenarios or artistic acceptance. Original orientation tests remain
byte-identical. Root executed/froze RED against FULL525 before the production
repair. Root still needs to repeat the actual uploaded HEVC request on the
correctly signed candidate. A bounded retry may still fail on a device; preserve
that result and explicit coverage before considering a separately labelled
native decoder fallback.

## Prepared production repair and one extra ownership regression

The analyser now retries each slot at the same native closest-sync time at most3
times. Null and recoverable decoder exceptions receive explicit finite coverage;
denied access and interruption propagate. Source scheduling,6..16 clamping,
metadata, thumbnail geometry and JPEG quality76 stay unchanged. Every owned
bitmap and retriever is released in failure/cancel paths as well as success.

Inspection identified a defensive SDK ownership boundary: Android may return its
input from `Bitmap.createScaledBitmap(input,24,14,true)` when input already has
that size. The old signature code then recycled the still-retained contact-sheet
frame if an alias is returned. The repair releases only distinct signature scratch.
The separate one-case `NativeMediaAnalyzerSmallFrameTest` checks native contact
JPEG pixels with synthetic mutable24×14 green decoder frames. Root ran it against
unchanged FULL525 and it PASSED1/1 in2.359s, so this runtime did not reproduce an
alias failure. It remains an unchanged baseline control and also passed in the
14-case repaired-source run. Do not label it RED or claim the potential alias
was reproduced. It is not an uploaded-media/GPU/creative acceptance test.

Full integration/in-place/signed-APK/actualHEVC validation remains pending. This
agent has performed only source inspection/editing and whitespace checks, with
no Java compilation, JVM/native execution, Gradle, ADB or model calls.

Complete8/8 sampling remains sparse closest-sync inspection. It cannot certify
exact source frame times, every cut, speech synchronization, natural motion,
new editing output, a drawing, voices or the requested240-second film.
