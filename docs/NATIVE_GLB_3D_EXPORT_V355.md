# VideoStudio 3.5.5 — 3D glTF skeletal combat → MP4 (first export milestone)

## What is actually implemented
VideoStudio now has a native offscreen Filament glTF rendering path that produces per-frame pixels from **two real, imported skinned GLB fighters** with authored animation clips, a shared fixed 3D combat stage, PBR materials, a perspective camera, directional light and solver-directed root movement. Readback is explicitly RGBA UBYTE from a readable headless swapchain; origin and format are converted for Android bitmaps. Frames are registered as private `generated_skinned_3d` assets, then VideoStudio's existing Media3 exporter produces a verified MP4. This is **actual 3D geometry and skinned animation**, not image morphing.

## Phone-only workflow
1. Import TWO rigged, animated GLB models using `VideoStudio → Real 3D Model Viewer → Import GLB` (Android's user-directed Open Document picker). GLB model sizes limited to 64MB each.
2. Go to Editor → `Export Real 3D Sword Fight • GLB`, or AI Tools → `Render 3D Fight → Native MP4`.
3. Choose the black fighter GLB and white fighter GLB separately, then confirm a short 3D render.
4. VideoStudio queues `render_gltf_combat` into the protected native heavy job lane. It creates a NEW output project. Default 3.2 seconds, 24 FPS, 320×576; supports 2–4 seconds, 24/30 FPS and up to 540×960 for now.
5. Monitor VideoStudio Activity. Once the Android GPU can render, the job returns a playable MP4 in VideoStudio's outputs. Native exports are not considered complete until actually published and readable. The original models remain untouched.

## Direct native MCP v3 API
Action `render_gltf_combat` with parameters `{sourceProjectId, blackAssetId, whiteAssetId, durationSeconds, fps, width, height, storyboardMotion, render, quality, fileName}`. Both asset IDs must be registered, distinct VideoStudio-owned GLBs stored under `creative_workspace/projects/<project>/models/`. File provider URLs and arbitrary local paths are rejected. Optional `render:false` creates editable frame samples only.

## Important limitations and truth
- This is the **FIRST** GPU 3D video export path. A real Android GPU/render and media sample inspection is still mandatory; Android CI cannot prove that the device's driver supports headless readback or that the chosen models look right.
- Input GLBs must include skeleton skins and at least one **real glTF animation clip**. Selecting ordinary PNG/JPEG images does NOT produce unseen 3D surfaces.
- The engine currently plays model-authored animation clip 0, optionally switches to clip 1 during the latter half, and drives each actor's root from CombatRigSolver. **It does not automatically infer attacking choreography, exact weapon-to-weapon GLB collisions or track character hands.** Model clip mapping and synchronized 3D sword events are follow-up work.
- Environmental visuals currently use a simple stable PBR stage. Real ruined-city meshes, camera cuts, physically coupled debris, advanced lighting and surface reflections need additional scene assets and integration.
- The offscreen readback uses GPU→CPU transfers, which are expensive; safe short clips and thermal checkpoints are intentional. On devices without this backend, the native job fails truthfully rather than substituting slideshow frames.
- Files are output privately within VideoStudio's selected project; no Gallery enumeration, paid inference or browser editor.

## Engineering validation
- `NativeGpuReadbackTest`: byte order, vertical row flipping, offset and allocation bound.
- `NativeGlbCombatSecurityTest`: source ownership, import folder, MIME, Gallery provider rejection and unregistered file refusal.
- `FilamentGlbValidationTest`: GLB header/version/length.
- Existing 2D rig tests and native Media3 regression suite must remain green.
- Native device acceptance: load 2 user-selected valid skinned GLBs with at least one animation each, sample and inspect ≥12 output frames, verify actual MP4 plays in correct 9:16 orientation and that source meshes visibly change with meaningful action. Do not mark cinematic target complete until mesh contact and movement quality pass.

## Next milestone
Implement the shared editable action graph: character retargeting, stance/anticipation/run/leap/sword-collision/recoil/knockback phases, clip crossfading, root-motion foot locking, precise weapon attachment via model joint hierarchy, camera and particle timeline, sound synchronization, motion-quality gates, and mobile gesture/timeline UI. Build an optional separate scene/environment GLB input, then realistic sparks and debris tied to 3D collision. Keep expensive retries targeted.
