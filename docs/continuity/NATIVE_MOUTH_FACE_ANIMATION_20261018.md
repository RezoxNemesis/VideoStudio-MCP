# Native mouth and face continuation — design and acceptance

Owner: isolated mouth motion first; add eyes, nose and a structured face only after the mouth passes visual inspection. Preserve existing studies and app identity. User authorizes autonomous reversible engineering, native render/repair/retest and candidate updates.

Extend existing eye-style editable cel construction rather than introduce a separate renderer or workspace authoring. `add_mouth_animation` constructs original soft/cartoon/fang lips, cavity, teeth and tongue using Android Paths. Independent open, width, smile, jaw side shift, tongue protrusion, upper/lower teeth and roundness channels are sampled with existing easing. Native intersections occlude internal anatomy. Teeth attach to their respective jaws, tongue attaches inside the cavity, and closed lips hide the interior. Manual pose editor and MCP dispatch share precisely the same native operation. No speech is inferred from silent motion presets.

First gate: three separate 4-second 1280x720/30FPS studies (articulation, tongue, chewing); every native frame, expression extremes, in-betweens and archive restoration checked. Preserve every render generation, source snapshot/diff and hashes. Assess visuals separately from constraints. Inspect failures and fix the app, not an export-only helper.

Second gate after mouth review: original stylized face with jaw-aware silhouette, nose, ears, eyebrows and the existing clipped eye controller. Preserve anatomical placement and editable native geometry. Expose shared face settings and expression controls manually/MCP. Test the mouth and combined face separately; do not call a frontal 2D face actual 3D or claim unimplemented views.

Owner subsequently requires view coverage for every animation: front, left,
right and rear, with high/low where meaningful. All six are in this study.
Side mouth apertures have a leading lip and rear corner, side-directed tongue,
and narrower anatomy; high/low views change foreshortening and tooth visibility.
Rear mouths emit no visible geometry. Combined face views must show the head
that explains this occlusion, a single near eye and profile nose in side views,
and no frontal facial features on the rear skull. These are authored stylized
2D views with explicit view selection, not a continuous 3D turnaround.

Face operation: `add_face_animation`, unique `faceId` up to 28 characters,
`layerId`, strict `settings`, preset `reaction`/`playful`/`chew`, optional
unified pose keys. Six eye channels and eight mouth channels retain their
individual semantics; `browRaise` and `browTilt` add expression control.
Settings cover head shape/proportion, jaw, nose, eye/mouth scale and style,
colours, optional hair, view and static roll. A detached candidate protects
existing artwork and atomically enforces the native document budgets.

Isolated frontal gate: v1 reproduced missing upper teeth and frame-rounded
timing drift. v2 corrected the cubic control ordinate and evaluated cues in
physical seconds. v3 rounded/shaded the protruding tongue root. All 11 native
mouth/manual tests passed at v3. Visual assessment: acceptable for isolated
stylized mechanics; silent articulation is not verified speaking. Android
speech currently delegates to the installed OS TTS engine; workspace native
graphics tests do not establish that engine or any audible phone output.

Final gate: full regression, source contract/version consistency, native manual parity, signed original-key candidate CI and independent APK verification. No merge, worker deployment, Gallery, replacement signing key, uninstall or data reset. Record phone/live MCP as unverified unless actually observed. Native PNGs may be encoded unchanged into review MP4; workspace FFmpeg does not prove Android video encoding.

## Reusable manual and agent controls

Create a short sketch with the intended resolution, FPS and frame count. Open
**Brush / Text → Mouth animation** for the isolated technique or **Face animation**
for the composition. Select the view and anatomy, adjust dimensions/colours,
select a pose and move sliders. Add/select/delete keys with one-based frame
numbers. **Preview pose** rasterizes a detached native candidate without saving.
Creation adds a new layer; failed persistence retains the dialog and edited keys
for retry. Preview tasks are cancelled on replacement/dismissal. Back/cancel and
double submission are disabled only while a save is in progress.

With an installed VideoStudio connector, call `app_status`, require native and
server support for 4.7.6, read `sketch_capabilities.mouthAnimation` and
`faceAnimation`, and use `sketch_edit` / `studio_sketch_edit` with a stable
operation ID and the actual current revision. The installed Android app expands
the following compact requests; no workspace is required for frame calculation:

```json
{"type":"add_mouth_animation","layerId":"layer_1","mouthId":"mouth_study","settings":{"style":"cartoon","view":"left"},"preset":"tongue"}
```

```json
{"type":"add_face_animation","layerId":"layer_1","faceId":"original_actor","settings":{"view":"right","shape":"oval","eyeStyle":"almond","mouthStyle":"soft","skinColor":"#FFD6A078","hair":true},"preset":"playful"}
```

Other mouth presets: `articulation`, `chew`; face presets: `reaction`, `chew`.
For an existing drawing, use a new layer and unused identity. `poses` replace
preset keys and accept inherited channels with existing easing/Bezier timing;
API keys are zero based. Front/rear are `front`/`back`; other views are `left`,
`right`, `high`, `low`. The rear correctly contains no visible mouth/eyes/nose;
unchanging or hidden frames are not unique drawings.

Mouth settings: centerX/Y .05..95, width .08..65, height .05..60, outlinePx 1..8,
tilt -35..35, anatomy and five colours. Face settings: centerX .15..85,
centerY .2..75, width .28..50, height .45..85, jawWidth .45..90,
noseWidth .08..25, noseLength .08..22, eyeSize/mouthSize .8..1.2,
outlinePx 1..6, tilt -20..20, head/anatomy style, five colours and hair visibility.
All dimensional fractions use the canvas except jaw/nose proportions within the
head. Geometry leaving the canvas or exhausting native budgets rejects atomically.

Face presets evaluate eye cues on a native centisecond clock and sample every
requested output frame; the output FPS/count/resolution are preserved. Independent
held eye cels share unchanged white/lid/rim geometry in explicit paint order. A
120-frame pixel-equality regression compares before/after storage optimization,
including a closed starting eyelid. Signed zero is normalized during sampling so
JSON transport cannot alter held-cel boundaries. Artist ink and original drawings,
package, signer, device identity and databases remain intact.

## Evidence method

Private root: `/workspace/engineering-review/mouth-face-animation-20261018`.
Earlier mouth v1..v4 and face-v1 renders are retained. Each source generation
records base commit, complete working diff, untracked source copies and hashes.
Full visual generations export all 120 frames at 1280x720/30FPS for three mouth
techniques and three face presets in all six views (36 independent clips).
Final review PNGs are copied byte-for-byte from `SketchNativeExport` and checked
against its native frame manifest. FFmpeg packages these unchanged PNGs into
silent MP4s; it is not evidence of Android video encoding or audible speaking.
Routine CI checks all six views through selected native frames and full archive
recovery, plus complete native export preparation for the six frontal studies.
Private full visual runs export every frame of all 36 scenes. Sampling verification
does not change the authored animation resolution, FPS or frame count.
Nine archive-restored frames per scene are compared exactly with native rendering.

Technical tests, visual assessment, source/manual geometry parity and live MCP
execution remain separate. Android installed offline TTS and phone execution are
unavailable in workspace Robolectric; no generated speech, phoneme alignment,
phone installation/playback or live ChatGPT invocation is claimed by these tests.
Worker deployment remains blocked pending configured bindings and retained legacy
queued-payload migration verification. Do not deploy or bypass an access rejection.

## Final review repairs

The combined profile review moved the near ear onto the visible skull, attached
leading lips to the silhouette, and made profile nose length/jaw/head shape
controls affect actual geometry. A nearly closed-mouth tongue root is occluded
at the upper lip while its exposed portion can cover the lower lip. Native
regressions first reproduced the missing teeth, FPS drift, small-face rejection,
root leak, JSON signed-zero hold drift and drawing-capacity failures.

The 240-frame/60FPS face shares independent eye components before validating
native drawing budgets, including when an existing artist drawing already uses
1,400 objects. Requested frames/resolution/FPS are not reduced and the budgets
are not raised. The final composed document is still validated atomically.
Save callbacks retain settings/pose keys until persistence succeeds; generation-
scoped construction tracking prevents a Home/resume reload from stranding retries
and leaves unrelated interrupted-load recovery intact.

Rear eyes/nose/mouth stay occluded, but visible rear chin geometry must still
follow jaw motion. The original final-v1 rear face clips are retained as
NEEDS_REWORK for their held jaw; final-v2 contains their corrected full-frame
replacement studies. Other views retain their separate full-frame evidence and
are retested on the final source. All per-scene records keep their actual source
generation; no earlier frames are relabelled as newly rendered.

Fresh final local verification: 1,342 Android tests, zero failures/errors, three optional proof skips; 344 Node tests and306 smoke checks passed. Worker dry-run passed without deployment. All360 accepted eye-study PNG hashes matched the previous4.7.5 generation. Candidate signing/CI verification is recorded below when complete.

## Verified signed candidate

Application source `def08743637c2ff6f8e8c52e0d33726f84e77a79`; [CI run39136900186](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/39136900186) passed1,342 Android tests with zero failures/errors and two optional proof skips. Independent downloaded-APK verification matched package, version4.7.6/code479, source proof and original pinned signing certificate. APK SHA-256: `ea216ac14811355bd3efe5c985301f1cc777d6430690fe6af761862ffecea269`.

Private candidate: `candidate-4.7.6/VideoStudio-4.7.6-Mouth-Face-Candidate.apk` below the evidence root. `review/review.html` selects the36 accepted studies, their native screenshots, full silent review clips and editable archives. `Final-Study-Index.json` preserves actual source generations; final-v2 replaces the three held rear jaws. Final-source non-rear checks matched141 native screenshot hashes from the complete earlier capture. The APK DEX contains the new operation/manual-control strings; functionality is established by native/manual tests, not those strings alone. No merge, stable publication, worker deployment, phone installation or live ChatGPT execution occurred.
