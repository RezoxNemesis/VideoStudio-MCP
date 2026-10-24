# Native preview graph and still-image MIME admission — future4.10.25/code528

Worktree `VideoStudio-Preview-Admission-v41025`, branch
`fix/native-preview-admission-v41025`, starts from original-signed527 source
`4517b8d23faa9689ed9a495e67525a487089a20e`. Candidate527 remains frozen in its
own worktree. Root owns all version metadata, commits, integration, signing,
ONE Gradle process and every Java/JVM/native/ADB/model execution. This source
does not change the package, signer, MCP identity, protocol actions or Worker.

## Reproduced gaps and unchanged tests

Root ran16 new behavior tests on FULL527:9failures,7positive controls,4.845s.
Five failures observed the missing actual Media3 multi-input factory request;
four observed lost known image MIME or opaque-image export classification.
Evidence remains at
`film-production-20261022/preview-graph-mime-527-red-v1-*`.

Independent review caught a regression in the initial MIME repair: blindly
forwarding catalog `image/*` or `image/x-unknown` suppresses working JPEG filename
fallback. Root ran the two additional unchanged regressions against that first
repair:2tests,2failures,2.881s. Preserve the failed first production generation
and `film-production-20261022/still-mime-fallback-528-red-v1-*` evidence.

Frozen test sources:

- `LiveEditPlayerGraphSelectionTest.java`:9cases,
  SHA256 `2831780e184b4ac2605257902edf0266eba2f0dc333fdb0312f8f6338a2870f3`.
- `StudioStillImageMimeAdmissionTest.java`:7cases,
  SHA256 `85111eda1f528a8d46907262b11306f56bb687dad078039414ae100a75e59dc6`.
- `StudioStillImageMimeFallbackTest.java`:2cases,
  SHA256 `9ceede0e145b88cc54d097b30120932baea32b20d35489212d1efc3d1fd2ef5f`.

No original test assertions, cases, fixture values or tolerances were changed.
Root ran the combined18 cases against the final guarded production:18passed,
0failures,4.522s. Evidence remains at
`film-production-20261022/preview-graph-mime-528-green-v2-*`.

Root separately ran23 relevant legacy classes:146passed,0failures,21.314s,
using FULL527 plus the exact two production overrides. Evidence remains at
`film-production-20261022/preview-graph-mime-528-legacy-green-v1-*`; the source/
class manifest is outside Git at
`editing-preview-admission-20261024/legacy-scoped-source-list-v1/`. These are
separate scoped unit results, not creative scenarios or native GPU/codec proof.
Full integration and actual updated-APK preview/export remain pending.

## Narrow product repairs

`LiveEditPlayer` examines the actual composition after existing duration admission.
It selects the real pinned `MultipleInputVideoGraph.Factory` only when more than
one sequence declares a VIDEO track, or video compositor settings differ from
the SDK's DEFAULT instance. Ordinary single-video previews, independent audio
sequences and final IMAGE/TITLE effects retain the existing SDK default factory.
Candidate admission, fallback ownership, playback intent, seeking, clocks and
release behavior remain unchanged.

Pinned `SingleInputVideoGraph` permits only one video input and DEFAULT compositor
settings. The new selection supports ordinary VIDEO overlays and existing two-
or four-plate image compositions without pretending that still foreground URIs
are independent video inputs. The graph-selection tests observe the actual
public `CompositionPlayer.Builder` request and deliberately stop before graph
construction. They verify selection only; they never render, decode or establish
GPU/codec/playback parity.

`NativeRenderEngine.buildItem` forwards the already-known image MIME only when
the public pinned `Util.isBitmapFactorySupportedMimeType` accepts it. This stays
inside the existing image branch, whose callers classify nonnull `image/*` asset
MIME. Unknown/wildcard types retain the prior provider/filename fallback. Other
media and AUDIO sequences retain their existing omitted MIME. No source bytes,
URI, imageDuration, frame rate, trim, speed, effects or source clock are replaced.

Preview and export use different image admission paths. Preview imageDuration
enables header-sniffing image extractors, so readable extensionless JPEGs can
already preview. Export's `TransformerUtil.getImageMimeType` instead uses explicit
MIME, provider type or filename suffix. The shared MediaItem repair gives an
opaque owned image the known supported format without weakening unknown-type
fallback. The MIME tests create real native JPEG/PNG fixtures; one explicitly
labelled private pinned-SDK probe checks classification only, not export execution.

## Phone failure and actual-retake boundaries

The owner's4.10.22 phone screenshot reported `Error processing video frames` for
a source JPG plus MP3 project. Its exact editable recipe, JPG bytes and nested
phone error are unavailable. Two visible assets do not establish the absence
of IMAGE/TITLE lanes or other effects. The message identifies a frame-processing
wrapper; it does not establish a particular shader, codec, URI or device cause.

Root's controlled similar latest527 JPEG+MP3 preview reached8seconds without
that error. An apparent black preview was withdrawn: decoding unchanged actual
Android screenshots independently showed the same opaque photo pixels before
and after playback and on526. Corrective evidence is retained at
`editing-music-text-20261024/user-preview-error-41022-v1/` in
`Screenshot-Readback-Discrepancy-Pixel-Verification-v1.json`. Do not introduce a
speculative surface/bitmap fix or infer failure from missing first-frame analytics
for the image renderer. Root reported the separate8-second native UI export
completed100%; output collection and verification were still pending here.

These two admission fixes do not diagnose the historical phone error. Reusable
typed error/cause/PTS diagnostics are planned separately through existing UI,
`get_state` and `job_status`, after meaningful behavior RED cases. No new helper-
existence assertion counts as a reproduced diagnostic failure.

## Remaining validation

The unchanged18 tests and146 relevant legacy preview/composition/UI controls
passed separately. Root must still run full integration and exact-revision CI.
Actual updated-APK native VIDEO
preview, play/seek/retry and export must establish backend and codec behavior;
unit factory/classifier assertions do not. Preserve original signer/package and
in-place user data. Do not claim a finished edit, artistic acceptance, live MCP
execution or the requested240-second character film from these repairs.
