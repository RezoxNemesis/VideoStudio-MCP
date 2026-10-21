# Native human story continuation — 2026-10-18

Work continues in `/workspace/VideoStudio-MCP` on
`feat/native-articulated-hands-20261018`. The new hand, human, staging, props,
grip links, speech rebasing, phrase pacing and story APIs first ship in source
**4.8.0 / code 483**. There was no published 4.7.9 APK. Existing older feature
minimums remain unchanged. No deployment, installation or live ChatGPT/device
execution is established by these workspace checks.

## Reproduce through native manual controls

1. Open the native saved-animation surface and choose **Story plan**. Choose
   **Load original example** to obtain a fresh-ID copy of *The Last Lantern*.
   This reads the same bundled example helper used by `sketch_story_example`.
   It authors a plan; it does not generate or embed speech.
2. Keep the displayed **Story plan JSON**. Select **Preview shot (1 based)** and
   **Preview frame (1 based)**, then choose **Preview shot**. This samples the
   selected shot through the native compiler/Canvas renderer. Preview is silent
   and does not publish a book. Building validates every shot, not just the
   previewed one.
3. Choose **Build / resume**, then **Open book** when ready. Each output frame
   becomes an editable native book page at the original canvas size and FPS.
   **Cancel work** preserves completed pages. Resume with the identical JSON,
   book ID and operation ID; **Load saved request** restores that exact request.
   Loading the original example again intentionally creates new IDs and is not
   the way to resume. Conflicting existing pages, including human edits, must
   remain protected.
4. For individual actors, use **Sketch Studio > Brush / Text > Human acting**.
   Choose the view, build, hair, colors, action, pose channels or custom keys.
   **Face structure** selects `graphic` (default), `angular` or `soft`.
   **Surface shading** selects `cel` (default) or `soft`; numeric `lightAngle`
   accepts -180..180 degrees and defaults to 135. Soft shading uses native
   material gradients on body/skin/clothing; it excludes eyes, mouth and ink.
   These settings preserve geometry and the animation clock.
5. Choose **Optional scenery** (`forest_path`, `bridge`, `shelter`, `village`).
   **Follow scene ground (preset actions)** defaults checked. For presets with
   scenery it supplies `ground`, derives the floor anchor and both world-plane
   slopes from the shared native stage/camera helper, and omits solo fit and
   explicit slope fields. Custom poses omit `ground`. Uncheck it to author
   slope motion yourself. `groundSlope` is world-Y rise per world-Z;
   `groundSlopeX` is world-Y rise per world-X. Each accepts -.5.. .5 and defaults
   to native zero. The plane applies after strength and pose overrides; authored
   lift is preserved. No actor, canvas, FPS or duration reduction is performed.
   In **Motion controls (editable JSON)**, optional `phase` 0..1 (native default
   0) offsets the motion cycle clock while leaving the root travel start
   unchanged. Use separate phases for cast members; phase can accompany scene
   ground and other preset motion controls, but not explicit custom poses.
   Omitted phase remains omitted in Node requests and old fingerprints.

## Real dialogue and the unchanged master

The speech backend is the existing **Kokoro / hexgrad/Kokoro-82M** model, using
an explicitly configured self-hosted OpenPipeline runner with the actual model
weights and selected voice files installed and verified. Merely loading a story,
viewing a preview or receiving dialogue controls does not configure that runner
or generate voices. The app's OpenPipeline surface can pack/run/import owned
dialogue jobs; offline request/result exchange remains available. Native story
music/ambience generation does not replace the speech model.

Use a new immutable request ID for revised voices; preserve previous jobs and
results. Dialogue lines may have 1..8 phrases whose texts, joined by one space,
exactly equal the original line text. **Add phrase** exposes optional speed
.75..1.25 (omitted inherits the line speed) and integer `pauseAfterMs` 0..1500,
including after the final phrase. **Use whole line pacing** removes phrases.
The real CPU engine synthesizes each phrase and inserts PCM silence; predicted
segment offsets follow actual samples. This is per-phrase pacing, not a new
model, emotional acting certification or time-stretched audio.

After importing the generated result, load the owned generated line in Human
acting or the character/speech dialogs. Set **Shot start in master (ms)** to the
shot's global film start (integer 0..600000, default 0). The loader subtracts
this explicit value from the selected line's original predicted start before
resolving the unchanged shot duration. A negative local start or any line not
fully inside the shot rejects; no clipping or timewarp is allowed. Original
master ownership, source hashes and audio bytes remain bound to the import.
Returned `audioTimelineOffsetMs` is the supplied shot start, and source line
timing remains available. Place the visual shot at that global offset while
keeping the unchanged master at its original audio-lane origin. Do not shift
the master a second time. Sketch preview/export itself remains silent.

The optional **Story audio** native controls / `sketch_story_audio_create` create
a separate owned audio mix using the imported master and native pad/ambience/
event controls. Supply the actual source project timestamp and asset identity;
the master is not overwritten. Keep the resulting separate audio lane aligned
with the complete film.

Each event may now supply integer `durationMs` from 1 through its native kind's
maximum: footstep 180, wood 240, metal 600, cloth 350, chime 1200 ms. Omission
keeps the original full duration and leaves old request fingerprints unchanged.
The native envelope uses the selected duration; the complete event must fit the
actual source PCM. For the last step at 123833 ms, explicit `durationMs:167`
ends at exactly 124000 ms without extending the film or altering source samples.
Manual Story audio exposes **Event duration (ms, optional)** and editable event
JSON; blank omits the field. This changes the event envelope, not the master clock.

## Ordinary ChatGPT / MCP reproduction

Call `app_status` first and require a connected native app version **4.8.0 or
newer**. Read `sketch_capabilities`, including `humanAnimation`, `handAnimation`,
`storyStage`, `actorProp`, `gripLink`, `storyPlan` and `storyAudio`. These describe
strict source APIs; a source capability response alone does not prove that an
older connected app supports them.

Call `sketch_story_example` with `{"exampleId":"last_lantern"}`. It returns the
full fresh-ID `plan`, `dialogueControls` and native-validated `audioSettings`,
with `readOnly:true`, `audioEmbedded:false` and
`dialogueGenerationNeedsConfiguredRunner:true`. No private workspace, client
asset path or drawing helper is required. Retrieve all retained result pages
when a tool result is paginated; keep the exact returned IDs for retries.

Use `sketch_story_preview` with `{plan,shotId,frame}`; `frame` is zero based.
The admitted native route returns a bounded Canvas PNG that the MCP handler
exposes as an image. Submit the unchanged plan to `sketch_story_create`.
Story creation, `sketch_story_audio_create` and
`sketch_animation_place_in_book` enqueue cancellable native JobManager work:
`ok:true, queued:true, jobId` acknowledges admission, not completed output.
Follow job status / deferred completion to inspect the actual result. Stop All
interrupts work; identical operation retries resume owned publications without
replacing user edits. Bounded saved-animation placement accepts 1..64 frames
per request with explicit source/book revisions and destination page start.

For direct `add_human_animation`, optional `ground` accepts the four scene
locations above. Reject `ground` with `fit:true`, explicit `poses`, or the
presence of either `motion.groundSlope` or `motion.groundSlopeX`, even zero.
Other motion controls, `poseOverrides`, speech and the independent blink track
can accompany ground. Retained human payloads store actual editable poses,
not unused `ground` or `motion` constructor arguments. Old retained settings
may omit `faceStyle`, `shading` and `lightAngle`; Node inserts no appearance or
motion defaults into old request fingerprints.

Retained hand/human programmes render exact native frames and sample to ordinary
editable native cels for books. Walk/run retain one exact pose per output frame,
at most 240 frames; longer gait shots reject rather than resample fixed contacts.
Other human actions support 24..1200 frames, at most 40 seconds. Existing 4 MiB,
4096-object, 131072-point, 4096-key and 16-retained-actor budgets stay unchanged.
Held props require the actual actor/grip on its layer and may retain a separate
hand overlay for occlusion; rebake props/links after actor/transform edits.

The native story helper accepts plans up to 2 MiB, but transport limits still
apply: the chunked queue supports 2 MiB records, while normal Node parameters
and encrypted mailbox payloads are limited to 512 KiB. There is no new story
archive/reference-input bypass. The bundled 16-shot example fits the smaller
route; do not promise that every 2 MiB helper plan fits every MCP transport.

## Finish picture and audio inside VideoStudio

The shared `ProjectAssetImport` API and **Import from project** dialog copy
already-owned media between native projects. The destination receives separate
verified bytes, its own asset ID and source provenance; its audio lanes retain
their original origin. No Files-picker handoff, FFmpeg or shared source-file
lifetime is needed for this owned-media workflow. Call `app_status`, require
native 4.8.0, and read `app_capabilities.projectAssetImport` first.

1. Read the completed book's current revision with `app_sketch_book_get`, then
   call `app_sketch_book_render` with its `bookId`, `expectedRevision`, a stable
   `operationId`, `pageStart:0`, the full `pageCount`, `aspect:"original"` and
   `quality:"720p"`. For this story the full count is 2976 pages. Wait for the
   terminal job result using `app_job_status` / `app_get_command_result`.
   Admission is not render success. Successful completion publishes the silent
   picture and returns its output project, hash and publication metadata. Keep
   this separate from the final picture-plus-audio export.
2. Use `app_select_project` to select the render output project, wait for that
   command, then request `app_state` (or `app_execute` with `action:"get_state"`).
   Read its completed result for `activeProjectUpdatedAt` and `activeAssets`.
   Select the generated `video/mp4` asset (`sketch_encoded_output` in the current
   render implementation). Call `app_project_asset_info` with that `projectId`,
   `expectedUpdatedAt` and `assetId`. It returns the exact current `updatedAt`,
   `sha256`, `bytes`, MIME and duration without publishing a copy. Confirm its
   hash matches the completed book-render result.
3. The completed `app_sketch_story_audio_create` result's `projectId` is the
   **destination**. It owns the mixed WAV and an audio lane at `startMs:0`,
   `sourceInMs:0`, full master duration and `gain:1`. Select that project and read
   fresh `app_state` for its `activeProjectUpdatedAt`. Do not add the unmixed
   master or create another audio lane.
4. Call `app_project_import_asset` with the following fields (substitute actual
   native IDs/revisions/hash, never private paths):

   ```js
   {
     projectId: AUDIO_OUTPUT_PROJECT_ID,
     expectedUpdatedAt: CURRENT_AUDIO_PROJECT_UPDATED_AT,
     sourceProjectId: BOOK_RENDER_OUTPUT_PROJECT_ID,
     sourceProjectUpdatedAt: INFO_UPDATED_AT,
     assetId: RENDER_VIDEO_ASSET_ID,
     expectedSha256: INFO_SHA256,
     operationId: STABLE_COPY_OPERATION_ID,
     appendToTimeline: true
   }
   ```

   The equivalent generic route is `app_execute` with
   `action:"project_import_asset"` and that object in `parameters`. Source and
   destination must differ. Copying uses a cancellable recoverable transfer job;
   wait beyond `ok:true, queued:true, jobId` to its actual `jobResult`. That
   result returns the **destination** `assetId`, `updatedAt`, SHA-256 and byte
   count. `appendToTimeline:true` inserts one unchanged full-length video clip;
   do not also call `app_insert_asset_timeline` for the same copy. Identical
   operation retries verify and reuse the result without duplicate clips or
   replacing subsequent human edits. Stop All interrupts the job. A changed
   request needs a fresh operation ID.
5. Read destination `app_state` and verify the video and existing audio lane
   have the intended duration/origin (124000 ms for this film). If an exact
   timeline replacement is needed, use `app_apply_edit_plan` with that
   `projectId`, fresh `expectedUpdatedAt` and one clip containing
   `clipId:"last_lantern_picture"`, the **destination** `assetId`, `inMs:0`,
   `outMs:124000`, `speed:1`, `volume:0`, `transition:"none"` and `title:""`.
   Use actual verified duration for other films. This replaces sequential video
   clips while retaining independent audio lanes; the lanes must fit the new
   duration. Do not replace an unrelated project's edited timeline.
6. Keep the destination selected and call `app_preview_project` to review
   picture/audio alignment, then `app_export_project` with `aspect:"16:9"`,
   `quality:"720p"` and a fresh `fileName`, for example
   `The_Last_Lantern_picture_audio.mp4`. A named-project alternative is
   `app_execute` with `action:"export_project"` and
   `parameters:{projectId,aspect:"16:9",quality:"720p",fileName}`. Follow actual
   job completion and inspect the final result and playback before claiming an
   audible finished film.

For the manual route, open **Editor > project-name dropdown (My projects)**,
choose **Story audio • <source project name>**, then **Media Bin > Import from
project**. Select the render project and video, choose **Inspect selected
asset**, verify the displayed SHA-256, enable **Append video/image to timeline**,
and choose **Copy asset**. Follow **Jobs / Activity** to completion, then reopen
**Editor** to refresh the project. The same shared service job performs the copy.
The dialog atomically saves the exact JSON before dispatch. After cancellation,
closing or reopening it, use **Retry saved import**: it sends the original
source/destination revisions, operation ID, hash and append flag unchanged,
regardless of current form values. Do not mint a new operation or rebuild a
request from current revisions to retry an already-published copy. The verified
receipt preserves later human clip edits and prevents duplicate assets/clips,
even if the original source project/file has since been removed.
The editor's **Export** now opens `StudioExportDialog`: choose **Canvas shape >
16:9 · landscape**, **Quality > 720p**, then **Export**. The parent-owned export
repair preserves the selected aspect; the old hardcoded portrait route is gone
from the inspected source. These controls use the existing project export path
that combines the video timeline and independent audio lanes.

Owned-copy input is limited to explicit app-private regular video/image/audio
files of 1 byte..256 MiB. It rejects caller-supplied paths, external provider
links and symlinks, verifies exact source/destination revisions before initial
publication, and rejects changed source/output hashes. Audio can also be copied,
but append does not silently add it as another lane. `app_insert_asset_timeline`
and `app_apply_edit_plan` still require destination-local assets; the new import
makes that requirement satisfiable without a user file round trip.

This is a source/API and integration-test reproduction recipe, not evidence of
a live connector, codec run, phone playback or audio audition. No deployment or
external encoder was performed for this workflow check.

## Observed evidence and outstanding review

Final sidecar freeze checks:

- `npm --ignore-scripts test`: **306 smoke checks + 584 Node tests PASS**,
  including 300 integrated animation tests. The new asset aliases explicitly
  require 4.8.0 in both generic alias fixtures; older feature floors are intact.
  No generated-client pretest rewrite was run.
- `ProjectAssetImportTest` **12**, `ProjectAssetImportDialogTest` **4**, and
  `ProjectAssetImportJobTest` **2**: **18 PASS** through direct
  javac/JUnit/Robolectric with cached Android dependencies, without Gradle.
  Tests cover exact independent bytes, metadata/hash provenance, stale revisions,
  source removal, changed outputs, interruption/partial cleanup, interruptible
  lock waiting, existing timeline-plan compatibility, exact manual saved retry,
  later human edits and actual Stop All plus identical retry.
- Six owned-asset Node contracts and sixteen story/audio contracts passed
  together (**22 tests**, included above). Event-duration acceptance, strict
  type/range checks, the final 167 ms step and omitted-field fingerprint tests
  were all observed RED before implementation. Manual saved-request retry first
  failed both new UI cases, then passed without removing the cancellation or
  published-result assertions.
- Human/phase contracts: **126 PASS**, including native defaults parity; the
  optional appearance/motion fields preserve old request fingerprints. The
  earlier separate appearance/ground UI class passed five checks without
  editing the parent's shared HumanDialog test class.
- Final worker dry-run passed with no upload:
  `wrangler deploy --config /tmp/project-asset-final-wrangler.json --dry-run
  --outdir /tmp/project-asset-final-worker-dist`. The temporary config preserves
  the repository entry point, compatibility and Durable Object bindings, uses
  an absolute entry path and omits the redundant custom client-bake command.
  Output was **1998.50 KiB / gzip 406.49 KiB**.

Proof logs and exact final source hashes are retained under
`/workspace/engineering-review/human-story-20261018/project-asset-import-final/`.
`Source-Hashes.json` links the current source to the parent's
`final-native-g09-source/Native-Source-Hashes.json`: **747 baseline entries,
including 306 main Java files; only ProjectAssetImportDialog.java differs among
those Java files**, for the exact saved-request retry. All art, actor, core and
ControlService Java bytes match that snapshot. The two alias-test fixture files
also differ. During final capture the parent separately advanced the bundled
plan to g10 IDs/name and changed two s07 pose overrides (`footLeftZ:10`,
`nearHandY:-205`); its data hash is recorded separately from the g09 plan. Do
not identify the new plan as the one used for an earlier g09 render.
The final Node/contract/doc hashes and worker bundle identity are recorded
separately; this source mapping does not certify g09 output or on-device encoding.

The parent's earlier JobTest XML failure used ControlService.class compiled at
22:04:43 UTC, before the cancelled-state guard added at 22:05:24 UTC. The test
pauses the actual streaming worker before publication. Checkpoint logging could
consume its interrupt flag, so the service now checks the durable cancelled
state before allowing copying/publication to continue. The unchanged Stop All
regression passes against the fixed source; it was not relaxed into a timing race.

Workspace evidence root:
`/workspace/engineering-review/human-story-20261018/`.

- `full-story-g06/Book-Receipt.json` records 16 native shots, 2976 frames at
  24 FPS, 1280×720, duration 124000000 microseconds. `Book-Progress.json` records
  2976/2976 completed native pages. Source documents, programmes, sampled
  documents, cancellation/retry evidence and source snapshots remain retained.
- `full-story-g06/The-Last-Lantern-g06-Native-Review.mp4` exists.
  `MP4-Verification.json` reports H.264 1280×720 at 24 FPS with 2976 frames and
  124 seconds, plus a 124-second AAC stream. This is workspace review packaging
  of native artwork; it does not prove Android on-device video encoding.
- `audio-v2/receipt.json` and `audio-v2/g01/verification.json` record completed
  actual Kokoro inference: 13 lines, 25 phrases, 124000 ms mono 24 kHz master,
  preserved v1 inputs/jobs and verified model/sample timing. The receipt marks
  listening **UNREVIEWED**, voice acting and lip-sync acceptance false.
- Parent reports the focused appearance-v2 native matrix passed 68 tests and
  upright-gait-v4 passed its gait/all-view/ground regressions. A fresh g09 story
  and 240-case matrix were launched; their full-film/visual acceptance results
  are still pending at this handoff. Earlier g06 completion is not proof of
  completion or acceptance for a later generation.
- Independent visual review rejected the synchronized, crouched shuffle in
  story shot s16. Shared optional preset `motion.phase` now allows separate cast
  phases. Native gait lift/upright-stance repairs and their visual review remain
  parent-owned work; neither the completed g06 book nor contract passes certify
  that this visual finding is fixed.
- `native-audio-v3/g02/proof.json` records a native 124000 ms, mono 24 kHz mix
  with 2976000 samples and 56 cues, including the explicit final event duration.
  This PCM evidence does not establish listening or audible-sync acceptance.

No live connector run, phone installation/playback, audio audition, forced
alignment, genuine 3D rendering or artistic-quality certification is claimed.
Keep subsequent generations and their failures/retests separate. The broader
200–500 varied-case and coherent human-story artistic goals remain subject to
actual visual and listening review.
