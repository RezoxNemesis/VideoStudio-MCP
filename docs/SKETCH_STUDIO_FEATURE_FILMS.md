# Sketch Studio 4.7.0: feature-length production foundation

This is a source upgrade, not a claim that the installed Android app or live MCP
server has been updated. Device rendering, video decoding, playback speed and the
signed in-place upgrade require the original signer and an accessible device.

## Implemented

* Books support **1,000,000 editable cels / exposed output frames**. One hour is
  86,400 frames at 24 fps, 108,000 at 30 fps, or 216,000 at 60 fps. A cel can be held
  for 1–600 frames. Standalone sketch documents and individual animation programs
  retain their 1,200-frame bounds; use books and scene ranges for long films.
* Long exports freeze an on-disk SQLite index of allocated documents and copy their
  owned dependencies. Native Canvas renders each required cel into MediaCodec
  without retaining a PNG for every output frame. Only the current cel renderer
  and bounded raster buffers remain live. Unopened pages stay blank and unallocated.
* The native virtual filmstrip and exact navigator reach the final cel of a
  feature-length book. Playback follows elapsed time and exposure holds; a slow
  phone may skip preview frames. This is not a measured realtime playback claim.
* `sketch_book_timeline` returns up to 128 global-frame rows and 128 direction cues
  per call. `sketch_book_edit` accepts up to 128 exposure changes / cues per batch.
  Cue types: shot, pose, turn, expression, camera, contact, impact and sound.
  **Cues are authored directions; they do not draw, move a camera or verify contact.**
* `sketch_import_animation` imports a registered private project PNG-sequence ZIP
  or native-decoded video into a new book. Stable operation IDs resume after the
  last saved cel and preserve existing cel edits. Source hashes and project
  revisions bind admission. A frozen copy permits recovery without newer source
  bytes. Gallery enumeration is never used.
* `sketch_component_save/list/apply` store and place reusable native artwork in
  character, pose, turn, expression, mouth, hand, prop, background, effect and
  construction categories. Selected cels retain editable layers, vector geometry
  and owned pixels. Shape animation is sampled at the selected frame, and palette
  colours are resolved so later source edits cannot recolour the saved component.
  Copied components cannot enter an `original_strokes` live session.
* Optional manually authored `headOutline` masks preserve face/hair texture under
  rigid head motion in source-deformation rigs. This does not generate expressions,
  correct anatomy or previously unseen angles.
* The Open Production Workbench now has manual Hindi/English character dialogue
  controls matching the autonomous MCP contract. Real local Kokoro inference
  produces timed voice recordings and a master audio lane in a separate editable
  project copy. Saved MCP requests can be reopened manually. See
  [Character dialogue](CHARACTER_DIALOGUE.md) for setup, operation and limits.

Native UI: **File → Sketch books**, **File → Import animation**, **File → Resume
animation import**, **File → Component library**, and **Frames / Cels → Film
 direction**. Existing onion skin, drawing layers, pressure brushes, editable Bezier
paths, paint, palettes, shape keys and artist-prepared character animation remain.

## MCP operation examples

Use `app_status` first and inspect `sketch_capabilities`. New operations and larger
ranges require Android **4.7.0** plus the corresponding server deployment. The
existing app identity, stable endpoint and private owner credentials are unchanged.
Use the named `app_sketch_*` tool when exposed, or the corresponding native
`app_execute` action / `studio_sketch_*` alias. Do not put endpoint credentials into
artwork manifests, project files or chat prompts.

Create a blank one-hour sequence (no drawings allocated yet):

```json
{"bookId":"film_01","operationId":"create_film_01","name":"Film 01","pageCount":86400,"width":1280,"height":720,"fps":24,"background":"#00000000"}
```

Open cel 60 with `sketch_book_open_page`:

```json
{"bookId":"film_01","page":60}
```

Draw on the returned sketch using ordinary revision-bound `sketch_edit` operations.
Keep stable IDs for layers and features; split edits into at most 64 operations and
512 KiB per request. No single command should contain the entire film. Use
`original_strokes` only when the session starts blank and every drawing step is
actually authored; library placement and imported pixels are source-assisted.

Plan a contact using `sketch_book_edit` (global frames are zero-based):

```json
{"bookId":"film_01","expectedRevision":1,"operationId":"plan_clash_01","cues":[{"id":"clash_01","type":"contact","startFrame":98,"endFrame":100,"actorId":"kael","targetId":"sera","label":"Draw blade contact; check both edges and hand grips in the rendered cels"}]}
```

Inspect `sketch_book_timeline` with its updated book revision:

```json
{"bookId":"film_01","expectedRevision":2,"frameOffset":90,"frameLimit":24,"cueLimit":64}
```

Export a scene using `sketch_book_render`:

```json
{"bookId":"film_01","expectedRevision":2,"operationId":"scene_01_export_v1","pageStart":0,"pageCount":240,"aspect":"original","quality":"720p"}
```

Omit `pageStart/pageCount` to export the entire book. An operation ID identifies an
immutable generation: reuse the same request to retry it; choose a new ID for a new
export after edits. Poll the returned job ID. A queued command is not a finished
MP4. Recoverable jobs use the existing native mailbox and safe checkpoints.

## Import details and practical budgets

PNG ZIPs contain files such as `frames/cel1.png`, `frames/cel2.png`,
`frames/cel10.png`. A common filename prefix and unique numeric suffix determine
order, independent of ZIP entry order. Mixed image sizes and unsafe paths are
rejected. PNG entries are bounded to 8 MiB and decoded with a 2,048-pixel maximum
working edge. Imported video is sampled at the selected 24/30/60 fps; this does not
create new motion between source frames. Audio is not imported into the cel book.

`sketch_import_animation` requires `projectId`, `sourceProjectUpdatedAt`, `assetId`,
`expectedSha256`, a fresh `bookId`, and stable `operationId`. Optional `frameStart`
and `frameCount` select a scene range. `fps`, `name`, `width` and `height` are
optional. Imported frames are flattened raster cels with editable overlays;
original layers/strokes are recovered only from an editable sketch archive.

Long snapshots and each animation import have an 8 GiB source/owned-pixel budget,
with a 128 MiB free-space reserve. Capacity is not unlimited storage. Render/import
scene ranges when source artwork exceeds the budget or restart cost is too high.
The current MP4 encoder restarts an interrupted encoding from its frozen snapshot;
it does not splice an unfinished MP4 checkpoint. Import resumes at a saved cel.

## Engineering still needed for reference-quality hand-drawn motion

The frame engine is infrastructure. The hand-drawn examples require consistent
redraws through rotation, occlusion, expressions and contact. The remaining art
pipeline needs:

1. Character model sheets and artist-approved front/profile/three-quarter/back
   views, expression sheets, hand poses and costume details.
2. Key poses, breakdowns and in-betweens authored with line of action, motion arcs,
   spacing, weight, anticipation, follow-through and deliberate holds.
3. An app-owned drawing/motion provider that can produce coherent redraws from
   scene context and character identity, or actual prepared drawings. No verified
   provider of that quality is configured by this source change.
4. Rough → cleanup → ink → colour → shadow/effects layers with editable corrections.
5. Visual review of anatomy, line consistency, foot planting, expressions and blade
   contact before expanding each accepted shot into the full film.

Use short representative shots to prove that provider. Keep separately authored
turnaround cels for unseen angles. Coordinate interpolation and image deformation
are useful controls, but cannot establish that a new anatomical drawing is correct.
The earlier 20-second sword duel has **not** passed its cinematic motion/art gate.
