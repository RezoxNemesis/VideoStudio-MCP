# VideoStudio Next 3.7.0 — Multi-Genre Storyboard-to-Animation Engine

## Primary goal
Transform user-imported storyboard artwork into genuine **source-image 2D
motion**, rather than a slideshow, Ken Burns zoom, thin replacement
stickman, procedural character substitution or an incorrectly morphed city.

The design supports comics/manhwa, action choreography, 2D characters,
animals, vehicles, product animations, environmental illustrations, kinetic
typography, diagrams, educational slides, and abstract artwork. It does not
force the visual style, body rig or action grammar of sword fighting onto other
types of source frames.

## Integrated architecture (actual implemented source code)

### 1. Source ownership, order and batch planning
- StoryboardBatchPlanner accepts 2–160 explicitly imported image clip assets.
- Deduplicates parallel JPEG/PNG/WEBP variants by normalized original frame
  name, preferring higher-resolution/larger supplied assets.
- Preserves narrative order; optional explicit assetIds overrides it.
- A 100-frame storyboard is **five batches of 20 new drawings**, with each
  subsequent batch internally also referencing the previous last frame,
  so exactly one boundary keyframe is shared without being duplicated in MP4.
- Each shot carries genre, action, frame count, identity and provenance.
- Every render writes to a separate VideoStudio project. No Gallery,
  MediaStore enumeration, source replacement or unstated cloud transfer.

### 2. Multi-genre action and camera grammar
- StoryboardMotionGrammar supports walking/running, leaping/falling,
  dodge/strike/impact/recoil, sway/float/orbit, title reveal/rotate/pulse,
  product push/pull, splash/explode, pan/drift, hold and deliberate scene cut.
- Action timing is continuous; beginning and ending object positions match
  the original drawings. Genre is kept separate from action.
- A source-derived action can be overridden only with an explicit request;
  labels aren't mistaken for a trained screenplay understanding model.

### 3. Actual automatic subject segmentation and tracking
- StoryboardLayerIsolation is a bounded, CPU-only source-pixel difference
  analyzer. It reconstructs the common background from unchanged source
  pixels, isolates changing foreground components, and matches their color,
  size and position across adjacent drawings.
- It outputs per-object pixel alpha masks, bounding boxes, tracked centroids,
  stable-scene checks, multiple object identities and confidence scores.
- Works best with clean, visually consistent foreground motion. Fast action,
  mixed colors, changed backgrounds and occlusion can produce uncertain masks;
  such shots are blocked rather than declared high quality.
- StoryboardPersonMaskProvider runs actual already-bundled, offline ML Kit
  person segmentation for photographic portraits and checks whether the
  model's foreground confidence is high enough. Stylized black/white figures
  are NOT assumed to be recognized by a selfie model.
- A bounded 1–8-object manual-mask API supports exact, source-traced
  silhouettes or later model-backed masks. The v3.6.4 2D user rig path is
  retained for complicated characters with marked joints/sword tips.

### 4. Character/prop animation using original source texture
- StoryboardLayerRenderer cuts actual source pixels into separate masked
  foreground sprites, each with its own source/target timing, scale,
  position, opacity and angle. Two actor textures are swapped only within
  a narrow local interval, never by whole-image crossfade.
- StoryboardDeformableSprite has a small true Canvas bitmap mesh and
  row-wise observed source silhouette contours for controlled cloth/body/
  organic flex. Objects/typography can keep rigid edges.
- StoryboardInteractionSolver applies bounded nonpenetration/impulse
  corrections for illustrated action contact and keeps exact original
  endpoints. This is 2D physics-inspired motion, not a real 3D ragdoll.
- Existing NativeSkinnedSprite2D and source-rig compositor remain available
  for artist-marked complex characters, without substituting generic
  CombatRigSolver stickmen.

### 5. Different output types have appropriate animation methods
- Source moving objects: independently segmented sprite movement and
  modest mesh deformation.
- Illustrated humans: ML Kit person segmentation when its real confidence
  passes; hand-marked joint art for harder scenes.
- Explainer or titles: progressive reveal of *actual* newly drawn source
  pixels rather than fading the entire screen.
- Landscape/abstract: moving source depth bands and restrained particle
  atmosphere, explicitly labelled environmental/camera animation rather
  than character animation.
- Different camera angles and dramatic perspective cuts are screened.
  Unsupported new anatomy/viewpoints require additional source art or
  a future actual neural model and are not forged.

### 6. Time-continuous film assembly and real sound
- StoryboardSegmentComposer writes independently rendered JPG motion frames
  at 24/30 fps inside a separate native project in protected storage.
- StoryboardSeriesOrchestrator appends batches with single shared boundary,
  checks the expected generated frame count and builds one editable video
  timeline for the whole sequence (at most 1,200 output frames/job).
- StoryboardFoleySynth creates an actual licensed-free WAV audio signal:
  impact, footsteps, swoosh, water splash, reveal/chime and restrained
  atmosphere. It attaches one synchronized Media3 audio lane to the film.
  It is not a full orchestral score or human voice narration.

### 7. Truthful quality checks before export
- StoryboardSequenceAuditor examines real imported pixel pairs and reports
  which need masks, camera separation or neural-capable models.
- StoryboardTemporalCritic checks that the rendered midpoint is not
  identical to either still frame and that the stationary environment is
  not severely warped; failure stops final release instead of marking a
  bad slideshow as complete.
- Per-shot source/provider/identity, mask confidence, scene drift,
  generated frame counts and scene genre are written to native job results.
- Device RAM and actual Android thermal protection remain active.
  The owner can cancel and recover a durable native job.

## MCP & Android interface
The installed plugin's existing durable native bridge remains unchanged.
A new version of VideoStudio Next exposes:

- `storyboard_series_plan` — canonical source order, batch boundaries,
  action labels and intended sample count.
- `storyboard_series_audit` — on-phone scene, mask and tracking risk report;
  no paid inference or Gallery browsing.
- `animate_storyboard_series` — guarded original-artwork render (one
  batch or `batchIndex=-1` for the whole story), optional generated Foley,
  separate output project and native MP4 encoding.
- The Android Editor and Animate Tools show
  **Storyboard → Cinematic Animation (Multi-Genre)**, genre and model
  choices, local source analysis, timed sample density, first-batch versus
  entire-story rendering, and Foley on/off.

## Real boundaries — never hide these
**A newly generated cinematic image-to-video diffusion model is not
installed by this release.** On-device ML Kit human masks, temporal
foreground differencing, sparse 2D deformation and source-motion graphics
do not equal generative reconstruction of unseen swords, hands, backs,
faces, new angles or new poses.

Deeply stylized fantasy fight images may need manually annotated outlines
or a later trained general-object/character-motion model pack. A scene that
fails object isolation, scene reconstruction or motion QA reports exactly
what is missing. The job does not silently use old photo zoom,
generic skeleton or a static fade.

The optional model-pack manager is not yet an executable generalized
video-diffusion runtime. Truly general zero-touch video synthesis will
require an audited on-device/mobile model execution adapter with weights
and quantitative temporal visual validation, or explicit user-authorized
off-device compute where phone RAM is insufficient. No paid services are
silently used.

## Native release QA
1. Run the complete Android Gradle unit suite including automatic mask,
   motion grammar, 100-frame five-batch continuity, Foley WAV, contact
   solver, diagram/landscape render and output-project isolation.
2. Run repository source smoke/JS CI.
3. Install a same-package, same-signed VideoStudio Next v3.7.0 APK without
   uninstalling the existing Next installation. Do not replace legacy
   differently signed VideoStudio by force.
4. Execute `storyboard_series_plan` and `storyboard_series_audit` against
   the user's already-imported 23-image Test 1 project. The canonical
   input will deduplicate parallel 480p JPEG/WEBP variants.
5. Use a simple moving-object pair first, check actual output samples for
   foreground motion and stationary background. Test complex manhwa
   action only if masks/rigs or a compatible model provide enough data.
6. Render and *visually inspect* at least one short genuine motion shot
   through native Android Media3. A green build alone is not acceptance.
7. Test multi-batch time continuity and one Foley lane; verify user
   originals and old project entries have not been overwritten.
8. When image isolation is uncertain, fail with a useful warning and
   use the source-rig/editor pathway; never present uncertain morphing
   as a finished cinematic animation.

Roadmap for general neural interpolation: audited LiteRT/ONNX model pack
adapter, anime/object segmentation, new intermediate pose generation,
occlusion and hidden-region reconstruction, temporal identity consistency,
camera-aware depth scene graph, visual automatic regression and optional
end-to-end soundtrack/orchestration generation. Those are follow-on
engineering work, NOT shipped claims in this prototype.
