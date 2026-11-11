# VideoStudio Next 3.6.4 — Source Artwork → Moving Illustration (first real engine slice)

## The exact problem this prototype solves
The original Revenge of Ashes images depict a black and a white swordfighter
in the SAME fiery ruined city. The previous two implementations either:

- Rendered **new procedural stickmen** in place of the illustrated fighters,
  or
- Applied **full-image optical flow** and gave blurred/doubled bodies,
  drifting buildings and flash-like motion.

This first native engine increment is specifically designed to avoid both.
It does **not** silently fall back to 3D skeleton characters, zooms, whole-image
crossfades, jump cuts or an unrelated generated battlefield.

## What is actually implemented in v3.6.4
1. **Two authentic storyboard anchors.** The user chooses two adjacent,
   imported images in the VideoStudio editor; both actors remain part of
   those very images, not detached generic geometry.
2. **Touch-first source annotation.** For *each* image, the mobile Rig Builder
   allows annotating BLACK and WHITE actors separately: sixteen hidden 2D
   motion landmarks, a traced body silhouette, and the actual sword tip.
   Both character source textures and sword endpoints are persisted as
   backward-compatible private Rig2DProfiles.
3. **Action-conditioned pose motion.** `StoryboardPoseMotion` interpolates
   the actual marked positions, constrains 2D limb lengths and optionally
   plants feet, keeps sword length/direction attached to the artist's
   hand. The hidden landmarks are NEVER drawn as skeletons in the MP4.
   Actions: ADVANCE, PARRY, STRIKE, LEAP, IMPACT, KNOCKBACK.
4. **World reconstruction and scene-cut rejection.** `StoryboardCleanPlate`
   uses only pixels from the two imported frames outside the source masks;
   covered pixels come from the other drawing or nearby real scenery.
   The renderer does not stretch the city alongside fighters. Big
   camera/lighting mismatches and large occlusions are rejected.
   A user-imported CLEAN background may also be supplied via native MCP.
5. **Source-textured 2D mesh renderer.** `NativeSkinnedSprite2D` now accepts
   a 16-point target pose; four approved source character cutouts (two
   actors × two drawings) are posed per frame. A narrow, actor-only
   7%-of-shot texture handoff avoids entire-frame ghosting.
6. **Native sword/impact VFX.** The sword tip is geometrically attached to
   its annotated 2D hand. Sparking appears only where a real sword contact
   is interpolated, not as random overlay on every frame.
7. **Real generated motion frames.** `NativeStoryboardArtworkComposer`
   writes independent source-pose PNGs in a separate app-private project,
   the source project and its media untouched, then Media3 can export
   the sequence at 24/30 FPS.
8. **No-fake-animation test.** `StoryboardMotionQualityGate` measures
   fighter/body/blade displacement in **output pixels**, rejects static
   lookalikes, and asks for a smaller or clearer shot when the original
   two poses cannot be reconstructed as one continuous action.
9. **AI / Android control path.** Native MCP
   `storyboard_artwork_preflight` lists the exact eligible adjacent shot
   pairs and missing annotations; `animate_storyboard_artwork` queues a
   recoverable guarded native animation job and reports honest details.
   VideoStudio Next Editor and Animate Tools include a clear "Storyboard
   Art → Moving Fighters (2D Beta)" entrypoint.
10. **No Gallery browsing.** Only explicit project asset IDs and the
    user-selected original image streams are accessed. No public upload,
    paid API, or remote image processing is required.

## Real limitations — don't overpromise
- This initial pipeline **does not** contain a trained neural video diffusion,
  flow-completion or unseen-anatomy synthesis model. It does not invent the
  back of a turned character or a completely new camera angle.
- Both fighters must be accurately marked on both source frames. Inaccurate
  polygon masks may leave a fringe or cut off a limb. The existing native
  editor can capture these masks without a PC.
- Frames with wildly different cameras, character scales, or complete
  occlusion are separate shots until a scene reconstruction model exists.
- It is a short 1.5–5 second test shot; the full 100-frame / five-batch
  sequence requires shot segmentation and optional neural synthesis.
- Native sparks are generated; synchronized full cinematic sword Foley,
  music and authored dialogue remain separate work.
- Android/JUnit and smoke checks test code. **Visual acceptance requires
  running the engine on original storyboard art on the real phone or a
  compatible Android emulator.** The old currently installed 3.6.0 cannot
  run this 3.6.4-only action.

## Execution workflow
1. Install VideoStudio Next v3.6.4 by updating an already installed Next
   (same package `com.rezoxnemesis.videostudio.next` and pinned cert).
   This APK cannot update the differently signed legacy 3.6.0 package.
2. Open a project containing the original source image shots; use
   VideoStudio Control → Recover legacy cloud project if applicable.
3. Open Rig Builder; on image A mark BLACK and WHITE source actors,
   including actual sword tips; switch to image B, repeat.
4. Tap **Storyboard Art → Moving Fighters (2D Beta)** in Editor, select
   the contiguous pair and action beat, then Animate + Export.
5. Inspect the source-owned MP4 in the rendered result library.
   Reject if the body deforms unnaturally, the city ghosts, or the
   sword detaches. Correct the source masks/landmarks and render again.
6. Release through the next app version only after this measured visual
   acceptance passes, not only when CI is green.

## Next planned engineering layers (NOT present yet)
- Actual per-object automatic 2D/weapon segmentation and persistent tracking
  model, with confidence maps and hand-correctable outlines.
- Pose-conditioned neural intermediate image generator running on-device
  via a *verified* quantized model package, or optional explicit opt-in
  cloud GPU. No model weights shipped in this PR.
- Optical-flow confidence per actor and explicit occlusion disocclusion
  completion, neural background inpainting, multi-shot camera projection.
- Scene graph and physically constrained contacts based on camera/world
  calibration, not merely interpolated 2D distance.
- Temporal consistency of facial/weapon textures across 100-frame reels,
  original-image conditioning each shot and 30/60 FPS rendering.
- Frame-level QA telemetry and contact-sheet visual regression on actual
  storyboard scenes; synced Foley and cinematic soundtrack.

## Acceptance gates
- Unit tests: original actor joints at endpoints, non-duplicated/still
  motion, sword-hand/tip stability, masks, world pixel identity and
  scene-cut failure, legacy 2D profile compatibility.
- CI Android compile and JUnit.
- Runtime: `storyboard_artwork_preflight` finds four actual annotated rigs;
  sample 2.6 sec 24fps source-conditioned MP4; media-only generated frames
  with no generic CombatRigSolver references.
- Visual: original fighter identity/skin texture intact; background
  fixtures stable; feet not skating; swords remain attached; no stretched
  city, stationary sticker replacement, or actor ghosting. The user should
  be able to reject a rendered shot without modifying the source.
