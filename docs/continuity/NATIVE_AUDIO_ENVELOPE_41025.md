# Native bounded audio envelope — future4.10.25/code528

Status: IMPLEMENTED SOURCE WITH SCOPED VERIFICATION; NOT AN ACTUAL528 APK.
Root approved the plan and executed original native6 genuine RED6/6 in3.124s
against FULL526, then focused GREEN6/6 in2.782s against fresh FULL527 plus exact
helper fc18c2de / ControlService79bfca8b overrides. These tests use real PCM-WAV
bytes; they do not execute an MP3 codec or certify editing/artistry. Frozen native
source2096e5af remains unchanged. Root owns all Java/JVM/native/Gradle/ADB/model
execution, approvals, integration, versions/signing/CI and actual APK work.

Node genuine baseline4 had2FAIL/2PASS: missing4.10.25 gate and accepted explicit
null mode; valid/legacy controls passed. First import failure was missing zod, an
infrastructure failure, not RED. All logs/source/dependency identities are kept
in editing-audio-envelope-20261024/node-red-v1 and node-red-v2. Node implementation
now passes858 routine checks plus306smoke (node-full-v3); frozen4 and new10 route
cases are included in package.json test script, preserving version/dependencies.

Root manual4 genuine RED4/4FAIL6.681s against fresh FULL527 found missing Analyze
envelope. Original manual4 then passed4/4 in5.038s at Main187c89c6 and
StudioAudioEnvelopeDialog1ee5dc5a; later Main26529a05 repairs are included in the
combined final checks below. Tests01d1be2 remain unchanged. No installed528 APK,
new-mode actual MP3 decode, render or audition has been verified.

Independent review reproduced two controller defects after first scoped6GREEN:
Service supplemental2 genuine RED2/2FAIL3.436s: strict host underscores were
sanitized, and queued cancellation had no durable job binding/ACK. The source
repair at ControlService1d44da2f keeps legacy/replay behavior, records/rejects
strict host metadata, gates worker admission until durable link/watch registration,
retains a typed flat source-bound recovered result and cancellation code.

Main first supplemental3 run failed3/3 in7.547s, but two failures were a one-page
fixture assumption; do NOT call those product regressions. Original33aab source
and evidence remain preserved. Narrow fixture-only ordered-page/length/SHA256
reassembly correction f8e1a7a5 retained every terminal/source/schema assertion.
Corrected root baseline3/2FAIL7.615s proved host _mcpCommandId acceptance and lost
queued cancellation ACK; ordinary control PASSED. Main repair26529a05 uses strict
public validation with independent once-only completion/polling and close ACKs.
Dialog remains1ee5dc5a. All earlier sources/failures are retained outside Git.

Root combined26 check ran25PASS/1FAIL9.56s on helperfc18c2de,Service1d44da2f,
Main26529a05,Dialog1ee5dc5a: original6, Service2, codec8, deferred2, manual4 and
corrected Main3 all passed. The single genuine config-buffer regression showed
2000 duplicate frames where0 were expected: BUFFER_FLAG_CODEC_CONFIG metadata
was being measured as PCM. All four product and seven test source files were
preserved before repair at
editing-audio-envelope-20261024/native-before-codec-config-fix-v1/Manifest.json.

Narrow helper repair78485e0a excludes codec-config payload from PCM accumulation;
the existing buffer release/EOS/cancellation paths remain. All26 case sources are
unchanged. Root final retake PASSED26/26 in8.077s on exact helper78485e0a,
Service1d44da2f,Main26529a05,Dialog1ee5dc5a. Evidence:
film-production-20261022/audio-envelope-combined-528-green-v2-*. This is scoped
app-code JVM/native logic verification with synthetic codec boundaries; it is
not installed528 or actual MP3 codec execution.

Additional one-case real-WAV rational-clock source53e736b0 independently checks
11025Hz fractional1ms offset/window100ms with275/276/275/276 frame counts, exact
ticks/raw peaks and excluded loud neighbors. Root separately PASSED1/1 in2.749s
on exact test53e736b0/helper78485e0a/Service1d44da2f and final527 runtime. Evidence:
film-production-20261022/audio-envelope-pcm-clock-528-green-v1-*. No original26
case was repeated only for this addition. There are8 new native test classes
with27 cases in total, verified as combined26 plus separate1, not a full528
Gradle run. Synthetic codec cases do NOT decode an actual MP3.

Node routine858+306smoke passed on exact JS/package-script sources. Native agents
ran no Java/JVM/Gradle/ADB/native/model process. Full integration, original-signed
exact-revision candidate and real new-mode user MP3/manual/MCP retakes remain
pending. Do not infer a Media3 preview/playback repair from audio analysis checks.

Branch feat/native-audio-envelope-v41025 starts594fa07. Root527 later factory fix
4517b8d23faa9689ed9a495e67525a487089a20e passed fresh full2646/0FAIL/0ERROR/28SKIP/
489suites and original-signed CI40080448343. Feature528 must integrate that exact
final527 before any candidate. Root527 files and its CI branch were not changed. Preserve every earlier
project/asset/branch/source and failed generation. No production release/version
metadata was changed here.

## Current evidence and actual shortcomings

The explicit user MP3 is11,483,135 bytes, SHA256
627c1c32a1fea562f8b32312aa0f05a43171bae1fee1d084c45e6e2e1ef8054d.
Root reports actual original-signed526 native import and first24s/576 normalized
buckets passed; duration metadata284134ms. This only verifies the old helper's
scope. It is not audition, beat detection, lyric transcription/alignment, text
editing, rendered video or finished animation. Source-Identity.json is an earlier
receipt and must not be overwritten to imply that it is the actual later result.
Actual native results are root-owned; preserve every generation/source hash.

Source audit at594fa07:

- ControlService.queueAnalysis and MainActivity.queueAnalysisCommand handle visual
  sampling only. Explicit missing asset IDs fall back to another video.
- Service resolveProject creates an unknown project and selects it. Strict
  read-only audio analysis must never use this lookup or change active selection.
- analyzeOwnedDialogue is package-private; its float[] is inaccessible through
  ordinary MCP. It validates persisted asset URI/MIME but not caller revision or
  full-source hash, and opens a ProjectStore without closing that local handle.
- Codec decoding samples only the first channel, while PCM-WAV averages energy
  over all channels. Right-only stereo can therefore disappear on the codec path.
- Both paths normalize each window to its maximum and discard absolute amplitude,
  frame counts and timestamp coverage. A zero bucket cannot distinguish silence
  from missing samples. Codec output may be strided and unsupported PCM encodings
  are skipped; quiet-cycle exhaustion can return incomplete data without a flag.
- Timeline request catches errors and caches zero arrays. Its key includes only
  asset ID/source offset/duration, not source/project/hash identity. Do not expose
  this presentation cache as authoritative complete analysis.
- PCM-WAV already has real bounded parser/descriptor/rational-clock tests. Retain
  them. New shared decoding needs its own honest result, not a float[] wrapper.

## Existing action, strict optional mode

Extend existing analyse_media only when an explicit mode is present:

```json
{
  "mode":"audio_envelope",
  "projectId":"EXACT_EXISTING_PROJECT_ID",
  "expectedUpdatedAt":123,
  "assetId":"EXACT_OWNED_AUDIO_ASSET_ID",
  "expectedSha256":"64-lowercase-hex-characters",
  "sourceMs":0,
  "durationMs":24000,
  "buckets":576
}
```

All fields are required in audio mode; no inserted defaults. Strict integers:
sourceMs0..86400000, durationMs100..30000, buckets4..720 and expectedUpdatedAt
0..Number.MAX_SAFE_INTEGER. IDs use the current stable native96-character limit.
Require finite JSON values; reject null, strings/booleans/numeric coercions,
fractions, unknown keys, arbitrary URI/path, visual frames/start/end/startMs/endMs,
and includeAudio. Explicit invalid modes fail rather than falling back to visual.
Internal trusted command metadata is handled outside the detached strict request;
a host cannot inject it. No operationId is needed for a read-only computation.

Omitting mode retains existing visual parsing/defaults, exact request bytes,
fingerprints, routes and minimum versions. Do not insert mode into old commands,
add analyse_media to a second canonical registry, or change unrelated archive
floors. Explicit audio mode requires native4.10.25; native protocol remains3.

## Source ownership and CAS

Use exact store.get(projectId), never active/fallback/create/select. Require the
fresh expectedUpdatedAt and exactly selected AUDIO asset. Read and retain full
asset DTO identity, source byte length and expected SHA256. Obtain the hash through
existing project_asset_info. Initial support is app-private regular owned files
via ProjectAssetImport.sourceFile: no Gallery, remote source, symlink or caller
path. Existing10GiB media ownership cap remains unchanged. Linked SAF content is
explicitly unsupported by this new hash-CAS mode; project_asset_info cannot hash
it today. Do not silently widen URI access to claim manual parity.

Resolve/hash on a cancellable worker before decode. Prefer one opened source file
descriptor for the selected inode; the bounded WAV reader or MediaExtractor uses
that descriptor. Stream hashes in64KiB buffers, never load source into a byte[].
Revalidate exact persisted project revision/asset DTO, file identity/length and
full hash after decode before acknowledging success. Do not hold a SQLite write
transaction while hashing/decoding. Close all ProjectStore/file/codec/extractor
handles on success, stale source, exception, cancellation or timeout.

A new source change yields AUDIO_ENVELOPE_SOURCE_CHANGED, with no result buckets
and no project writes. Existing command journal receipts remain explicitly bound
to their source revision/hash; a durable historical receipt is not a claim that
a subsequently changed source was freshly reverified.

## Shared native decoder and measured quantities

Propose a new native worker/helper with a structured result and a small shared
PCM16 accumulator. WAV and MediaCodec feed the same accumulator. Do not change
source audio or renderer output. Support1..2 decoded channels and8k..192kHz,
consistent with the existing PCM-WAV parser; reject unsupported format changes,
encodings/layouts or channel counts. Request PCM16 from MediaCodec and explicitly
fail unsupported output; do not skip buffers, substitute zeros or silently downmix.

Use every decoded frame in the bounded selected window, all interleaved channels.
Per bucket, energy is the sum of squared channel samples divided by channel-sample
count; rms=sqrt(mean energy). samplePeak is max(abs(PCM16/32768)) over all channels.
It is a sample peak, NOT a reconstructed true peak, LUFS, loudness standard, beat,
pitch or speech result. Keep stereo energy rather than signed channel mixing.

Export raw rms and samplePeak in0..1 plus normalizedRms. Normalization is explicitly
window_max_rms: divide observed bucket RMS by the maximum observed RMS in THIS
window. If all measured buckets are silent, maximumRms=0 and their normalized
values are0. Missing buckets have null metrics, not fabricated silence.

## Coverage and bounded work

Source-time buckets use rational boundaries with exact sourceStartUs/sourceEndUs.
Return sampleFrames, channelSamples and timestamp-derived coverage for each bucket.
For WAV use exact source PCM clock and physical sampleFrames, never duration
metadata to invent a tail. A window outside known actual WAV bounds fails with
AUDIO_ENVELOPE_WINDOW_OUTSIDE_SOURCE before sampling.

For codecs, validate BufferInfo offset/size/frame alignment against the actual
buffer and account for decoded PTS, seek preroll, EOS, duplicate/overlapping ranges
and gaps. Count distinct frames in the requested window; a bounded sample-frame
coverage bitmap can avoid double counting (maximum5,760,000 frames,720000 bytes).
Codec timestamps map ONCE per buffer to
floorDiv(PTS_us*sampleRate+500000,1000000), the nearest source sample-frame
index; subsequent buffer frames increment that exact index. This explicit clock
quantization does not interpolate/fill missing samples. Do not add an unmeasured
tolerance or silently fill missing time. If clock/coverage is
uncertain, mark incomplete. Preserve partial observed audio with missing/partial
bucket flags, reason and actual covered times. complete requires every expected
bucket/frame covered; counts alone must not turn duplicate buffers into complete.

Approved explicit work caps:30s selected window,5,760,000 distinct requested
frames plus bounded preroll (6,144,000 total decoded frames),2 channels,64MiB
compressed data after seek,65536 input
and65536 output buffers,2200 consecutive idle dequeue cycles and120s monotonic
wall deadline including source verification. Fail a budget/timeout explicitly;
never return a silent complete result. The10GiB full-source hash cap is large:
retain streaming progress/cancellation, and document that a deadline may reject a
very large file. Do not silently lower the old import limit. Any future cache
must bind full source identity/revision/hash and retain the actual coverage.

Cancellation checks are required before IO, each hash/decode loop and bounded
PCM chunks. Surface ordinary cancellation with original cause and retained
interrupt flag. Release the current output buffer even if accumulation throws;
stop/release codec, extractor and all descriptors in finally. No retry can borrow
samples from a neighboring time or another asset.

## Result contract proposed for RED sources

Flat source identity: schema=videostudio.audio.envelope.v1, mode=audio_envelope,
ok/readOnly/sourceUnchanged, projectId, sourceProjectUpdatedAt, assetId,
sourceSha256, sourceBytes, sourceMs, durationMs, bucketCount, sampleRate, channels,
pcmEncoding=pcm16. normalization={method:window_max_rms,maximumRms}.

buckets is an ordered array of index, sourceStartUs, sourceEndUs, sampleFrames,
channelSamples, rms, samplePeak, normalizedRms and coverage. Bucket coverage retains
expected/decoded/missing frame counts, actual first/last times and complete.
Top coverage includes requestedBuckets, decodedBuckets (at least one observed
frame), missingBuckets, partialBuckets, requestedFrames, decodedFrames,
missingFrames, complete and any termination reason. Expose actual decoder route
and codec name when known; never invent them.

Measured full silence is successful complete analysis. A partial result is ok:true
with coverage.complete:false and an explicit partial status; UI/log text must say
partial. Zero decoded frames are failed/no-samples with coverage diagnostics and
no metrics array. Strict input/source/window failures do not include buckets.
Stable proposed error codes: AUDIO_ENVELOPE_INPUT, AUDIO_ENVELOPE_SOURCE_CHANGED,
AUDIO_ENVELOPE_WINDOW_OUTSIDE_SOURCE, AUDIO_ENVELOPE_UNSUPPORTED_FORMAT,
AUDIO_ENVELOPE_NO_SAMPLES and AUDIO_ENVELOPE_BUDGET. Root review precedes product.

## Manual, ordinary ChatGPT and timing parity

Add Audio Inspector > Analyze envelope with source start/duration/bucket controls,
worker progress, cancellation and readable coverage. Use exactly the same strict
native request/helper and result as MCP. Enable this on the same eligible owned
sources; explain why linked content needs an owned import. Closing the Inspector
cancels its own pending work without clearing source or other jobs.

Display source-time ticks. For an existing constant-speed AUDIO lane, present
optional timelineMs=layer.startMs+(sourceTimeMs-layer.sourceInMs), clearly labelled.
Seek/copy selected source/timeline intervals; opening the existing Title Inspector
may prefill a chosen interval, but committing titles remains a separate ordinary
revision-guarded edit/Undo. No automatic beat or lyric claim. Current timeline
waveform presentation remains a preview, especially for legacy linked content;
never turn its cached zero array into the authoritative new result.

Ordinary ChatGPT flow: app_status -> require4.10.25/audioAnalysis capability ->
project_asset_info with exact project revision -> app_analyse_media explicit audio
mode -> wait for native command/job result -> inspect coverage -> author ordinary
TITLE keys/source music lane via existing guarded editing APIs -> native render
and real review. request_media_analysis and raw/batched queue routes must apply
the same strict mode validation/version gate. In explicit audio mode the app tool
must not inject its legacy default frames:12; omission keeps that exact old path.
Native/Node capability JSON must agree and no canonical action count is guessed.
No Worker deployment, hosted AI, ASR, beat model, synthetic audio or codec/GPU claim.

## Prepared checks and remaining gates

NativeAudioEnvelopeDispatchTest has6 cases through existing public
ControlService.onCommand and real synthetic PCM-WAV bytes: stereo raw RMS/peak/
coverage and preservation; true silence; strict input; stale revision/hash; no
fallback project/asset creation/selection; actual PCM end versus stale metadata.
No new production symbols or decoder shadows are needed to compile against594fa07.
Source SHA2562096e5af19206d6e2ae14aa79d823dff87d642dd9a55a7cb9b667f7323613cf6.

Node source has4 cases through existing nativeStoryboardParameters and
requireStoryboardNativeVersion: explicit future floor, strict inputs, valid exact
request and omitted-mode visual bytes/floor control. Valid/legacy baseline controls
may PASS; record that honestly. Source SHA256
84b7c039c14fc74fd2ff3b65313c29c7c92b464050fba797619408cbda86e2d3.
Only the Node child executed Node tests. Root executed the native baseline/
focused checks above; the core and manual agents ran no Java/JVM/native process.
Original test hashes remain exact; all test results are scoped as described.

Implemented checks cover strict/source CAS, full/partial/missing/silent metrics,
codec offsets/gaps/duplicates/config metadata, unsupported output, ordinary
cancellation/resource cleanup, queued terminal delivery, recovered receipt binding,
manual Inspector and rational PCM clocks. Full integrated native/Node regression,
original-signed exact-revision CI and actual installed528 manual/MCP/user-MP3
decoder retakes remain required; fixture logic checks do not establish real codec
behavior, playback, audible audition or rendered-video editing. Then use the
actual result for a real music/text edit and native export.
Root's native527 renderer-factory fix and real editing retake remain first priority.
This analysis feature alone does not finish the user's film or certify artistry.
