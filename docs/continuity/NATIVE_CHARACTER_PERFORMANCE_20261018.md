# Native character performance — 2026-10-18

Continue from `0400ba62da41ecc9e923a6e4a4d1f0fd9a325adb` (verified speech
4.7.7). Work branch: `feat/native-character-performance-20261018`. Preserve all
previous studies, original signer, package and device-owned MCP identity.

The owner supplied a detailed gothic portrait as a quality benchmark for more
difficult anatomy, clothing, materials, poses and audible performance. No pixels
from it are imported, traced or used as animation footage. The original native
travelling scholar is a stylized vector bust. Painterly realism, full-body acting,
arbitrary continuous turns and genuine3D remain unfinished targets.

## Retained app capabilities

`add_character_animation` constructs native editable body/neck planes, a tailored
coat, collars/lapels, shirt, folds, wool marks or leather highlights, layered hair,
individual gloved fingers, a two-segment moving arm and an attached pendant.
Front/profile/rear anatomy and hidden features differ; high/low facial views use
the existing authored facial anatomy. These are six authored2D views, not a3D rig
or proof of complete perspective-consistent body rotation.

Settings: `view` front/left/right/back/high/low; `build` slender/broad; `material`
wool/leather; `detail` contour/tailored; `gesture` pendant/explain; `armLift`0..1;
`headTilt`-12..12; ARGB `skinColor`, `hairColor`, `coatColor`. Character identity
maximum20characters. Existing optional `speech` accepts authored cues, original
predictions, or a verified imported generated-line reference. Strict MCP schema and
native metadata advertise the same settings and native minimum4.7.8.

The constructor preserves canvas, frame count and24/30/60FPS. It requires4..8seconds
and24..240frames, maps its1280×720 design uniformly to landscape/portrait/square,
and preserves current4MiB/4096objects/131072points/4096keys limits. It rejects
invalid settings, duplicate identity, locked layers, insufficient budgets and
out-of-canvas composed destination-layer/object poses atomically.

Every changing output arm pose has an exact native transform key; stationary
holds share endpoints. This reduces data without reducing output FPS or inventing
unique drawings. Elbow and wrist centres are computed from two fixed-length
segments. Fingers follow the wrist; the pendant's held link follows the actual
pinch point while its flexible chain hangs in world-down orientation. This is a
geometric attachment/gravity-direction constraint, not a cloth/chain simulation.
Construction output is baked editable artwork; subsequent arbitrary artist edits
do not automatically preserve the original constraints. Reconstruct using the
same high-level operation to change its settings.

## Reproduce without a workspace

In **Sketch Studio → Frames / Cels → Timing / canvas**, set1280×720,
180frames,30FPS and background `#FFF2DFCB` for the neutral studies. Open
**Brush / Text → Character performance**. Choose view/build/material/gesture,
arm lift, head tilt and colors; use **Preview character**, then **Create character**.
The native constructor saves on a new layer with ordinary editable curves/cels.
Preview/save run asynchronously. Compatible concurrent artwork can be retained;
changed canvas/timing requires reopening controls rather than silently changing
what was previewed. Cancellation does not submit a drawing.

For sound, generate/import a six-second master in existing **Open Pipeline**:
one line at1000ms with4000ms slot, speed1. English voice`am_adam`:
“This pendant belonged to my father.” Hindi voice`hm_omega`:
“यह मेरे पिता की निशानी है।” Then enable **Timed speech → Load generated dialogue**,
select the imported line, inspect cue frames and adjust the numeric intervals,
shapes/strengths if necessary. This uses the same hash/ownership-bound loader as
existing native speech. Native preview and Sketch export remain silent. The
imported dialogue project's existing audio lane owns the exact master; place the
exported animation at0ms beside it in the main editor. Android encoding/playback
and speech intelligibility require actual device/listening verification.

For the night explaining variation use background`#FF151E32`, gesture`explain`,
material`leather`, armLift`.55`, headTilt`-8`, coatColor`#FF54455F`,
skinColor`#FFE0B49B`, hairColor`#FF343A50`. No workspace drawing helper is needed.

With a configured compatible connector, first call`app_status`, require native
4.7.8 and read`sketch_capabilities.characterPerformance`. Create an ordinary
180frame/30FPS1280×720 sketch and issue a revision-bound`sketch_edit` with this
operation (replace the imported request identity with an actual owned result):

```json
{"type":"add_character_animation","layerId":"layer_1","characterId":"scholar","settings":{"view":"front","material":"wool","gesture":"pendant","armLift":0.8},"speech":{"generated":{"requestId":"YOUR_IMPORTED_REQUEST","lineIndex":0}}}
```

Omit speech for a silent gesture. Existing authored`cues` are an editable fallback
for owned recorded audio; automatic recognition/forced alignment is not provided.
The operation does not synthesize or mix audio. Manual/generated-reference/native
source parity tests do not prove deployment or live ordinary-ChatGPT execution.

## Inspect–repair–retest record

Evidence root: `/workspace/engineering-review/character-performance-20261018`.
`audio-runner` retains original product pack/run requests, immutable result archives,
PCM/timing/model proofs. Both new six-second masters are non-silent24kHz PCM from
the existing self-hosted Kokoro CPU backend. They are not Android system-TTS
receipts. Pronunciation/intelligibility and performance lip sync are unreviewed.
Checked-in original output fixtures support repeatable native/CI tests without
representing CI as a new inference run.

The real English output exposed reduced-schwa`ᵊ` rejection. Dedicated native and
Node regressions failed before adding its open-vowel mapping; duration tokens and
the actual sample clock remain intact. Unknown symbols still reject.

`v1` screenshots: NEEDS_REWORK. Native fill/outline command arrays shared mutable
coordinates; transforming both twice distorted portrait canvases and detached
profile details. The constructor now deep-copies its editable path arrays before
transformation. The source helper's unrelated earlier films remain unchanged.
The same review found a large head, exposed scalp arc and flat materials.

`v2` screenshots: corrected aspect/outline alignment, smaller head/eyes and native
coat gradients. Further review exposed a neck gap and incorrect rear hair shape;
`v3` retained their repairs and a separate near-facing profile collar/lapel.
The flexible pendant still rotated rigidly above the wrist, so that generation
remains NEEDS_REWORK. A native grip/vertical-hang regression failed before the
separate world-down pendant attachment. Final rendered evidence follows separately.

Code review also reproduced out-of-canvas destination-layer transforms, ignored
broad profile build, sparse-key wrist drift and a same-session preview/save timing
race. Regression tests cover every actual output frame at30/60FPS, animated layers,
profile proportions, real live-session adoption, generated dialogue selection,
native preview parity, cancellation and retry protection. The advertised frame
rates were corrected to the existing native24/30/60FPS rather than claiming1..60.

Candidate4.7.8/code481 and exact CI/source/artifact checks are recorded only after
verification completes. No phone/live-MCP success, merge, worker deployment or
stable publication is inferred from workspace native renders or FFmpeg packaging.

The first full batch `final-v4` passed31native tests and captured8scenes/1440PNGs,
with32archive pixel comparisons. It remains NEEDS_REWORK: final source review found
that right-profile garment gradient endpoints were not reflected with the sleeve.
A focused native regression reproduced this before the endpoint/centre repair.
Final committed-source captures must be recorded as a new generation.

Focused affected-path validation passed90Android tests, zero failures/errors/skips.
360Node tests plus306smoke checks passed; worker dry-run passed without deployment.
The full final-source CI and signed-candidate identity are pending at this checkpoint.

## Verified candidate and final evidence

Final application/test source: `a2b1154fa18e6befb467def65160bceebf024c08`.
The character runtime was introduced at`fa835c1`; the follow-up changes only the
existing shape-key test's durable-save wait. First CI39150136276 recorded1400tests,
one shape-key test failure and two optional skips. Its evidence remains retained.
The corrected test helper awaits the actual session writer, checks pending/error
and drains the UI callback;17affected tests then passed without assertion removal.

[CI39151059017](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/39151059017)
passed1400Android tests across270suites: zero failures/errors, two optional skips.
The independently checked4.7.8/code481APK matches package/source proof and the
original signer. SHA-256:
`6c0e9e24931b0e9fcd20eca598ab2a696af95efe2295ade3b5364b084d0191a3`.
Bytes:130922964. Private key stayed in the existing CI cache. Candidate:
`candidate-4.7.8/VideoStudio-4.7.8-Character-Candidate.apk` under the evidence root.

`final-v6/review.html` contains all eight six-second180frame/30FPS/1280×720studies:
front/left/right/rear/high/low plus two alternate-palette explaining gestures,
using the two original English/Hindi speech masters. All1440native PNGs were
captured through VideoStudio's native exporter.49selected native/editor tests
passed with zero failures/errors/skips. All677source-file hashes stayed unchanged
during capture;32sampled archive-restoration frames matched native pixels.
All1440frame hashes also match the preceding successful visual generation, proving
that the test-only follow-up left its runtime artwork unchanged. Compared with
the earlier gradient-failure generation,1080frames are unchanged and360right-view
frames reflect the corrected material gradients. Earlier generations remain intact.

Visual acceptance is limited to a stylized vector anatomy/material/attachment
study. Reference-level painterly realism, full-body acting, continuous perspective
and genuine3D remain unfinished. The six authored views include high/low facial
anatomy; they do not prove a complete perspective-consistent3Dbody turnaround.
Native manual/generated-reference preview parity, settings and source MCP admission
are verified. Live ordinary-ChatGPT/MCP, phone installation/playback, Android system
TTS and Android video encoding remain unverified. Speech is genuine self-hosted
Kokoro output, with model-estimated timing and no listening/forced-alignment claim.
No worker deployment, main merge or stable publication occurred.

`Full-Verification.json`, `Final-Study-Index.json`, source snapshots, JUnit/CI records,
audio/model proofs and checksums retain exact evidence. The complete review ZIP
links the APK separately; the source continuity guide provides the manual/MCP recipe.
