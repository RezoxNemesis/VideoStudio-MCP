# VideoStudio 3.5.6 — Shared 2D/3D Combat Action Director

## Core goal
Create the next generation of *real character animation without hand-creating hundreds of images*. A short action plan specifies what fighters do over time; VideoStudio samples rigged 2D sprites or real 3D skinned GLB models at 24–30 FPS. Every frame is a new timestamped body pose, not a repeated still image.

## New engine modules
- `CombatActionPlan`: eight meaningful phases: GUARD → APPROACH → LEAP → STRIKE → CONTACT → COUNTER → KNOCKBACK → LAND. A nine-number normalized timestamp map drives pacing; a monotone cubic time warp preserves C1 motion continuity across beats and aligns with the existing 2D rigged combat solver's sword-contact timeline.
- `CombatActionClipResolver`: chooses named imported GLB clips per fighter/beat (`run`, `jump`, `slash`, `parry`, `hit`, `land`). It explicitly flags when the user's 3D model lacks a matching clip; it **does not hallucinate new rigged movement** or pretend an idle clip is a sword attack.
- `NativeRiggedCombatComposer`: source-masked 2D sprite meshes are now sampled against the shared action plan. The generated character pixels and metadata carry their source-rig IDs and named action beat, with brief synchronized camera impulse at contact.
- `NativeGlbCombatComposer`: Filament GLB actors use the **same** action clock, named animation-clip selection, short glTF animator crossfades and solver-driven root positioning. A physically meaningful director-driven camera keeps the fight framed through the clash and knockback.
- `ControlService`: both `render_rigged_2d` and `render_gltf_combat` support optional `actionBeatTimes` in native v3 parameters. The passed array is preserved in durable recovery parameters. Native self-test reports action-director and named-motion capabilities separately from missing exact blade physics and audio synthesis.

## Example native action
```json
{
  "nativeAction": "render_gltf_combat",
  "nativeParameters": {
    "sourceProjectId": "<your-source-VideoStudio-project>",
    "blackAssetId": "<approved-imported-GLB-1>",
    "whiteAssetId": "<approved-imported-GLB-2>",
    "actionBeatTimes": [0, 0.12, 0.29, 0.41, 0.52, 0.60, 0.73, 0.89, 1],
    "durationSeconds": 3.2,
    "fps": 24,
    "width": 320,
    "height": 576,
    "storyboardMotion": true,
    "render": true
  }
}
```
The shared action beats can be retimed by changing nine numbers instead of manually creating additional frames. No arbitrary HTTP source URLs, Gallery enumeration or paid API service is needed.

## Explicit limitations
- **No automatic 2D screenshot → full correct 3D model reconstruction.** Real 3D requires two imported, skinned GLB models with actual animation clips.
- 2D requires a one-time manual body outline/joint rig (original character texture preserved in the skinned mesh).
- The GLB renderer does **not** yet retarget an untrained skeleton to synthesize an action absent from all clips. Missing actions are detected as clip fallbacks and recorded for QA.
- No exact GLB sword-to-sword mesh collision; the contact time is aligned to the scripted combat solver and can trigger camera/cue metadata, but detailed blade attachment and 3D physics need a subsequent asset-space joint/weapon solver.
- This milestone exposes a metadata-only Foley cue plan (`swords_clash`, `footsteps_and_rush`, etc.). **Actual audio files or an automatically produced soundtrack are not yet mixed into exports.**
- No one-shot guarantee of reference-level realism: animation polish still depends on character quality, masks, authored clips, lighting and on-device frame-by-frame visual QA.

## Tests and acceptance
Run `CombatActionPlanTest` for the monotone time warp, no frozen frames, phases and clash energy, plus `CombatActionClipResolverTest` for truthful model-clip selection. Keep all 2D masked-rig, GLB offscreen, ARGB orientation, Android privacy and codec tests green.
After a signed development APK is installed, import two animated GLBs or create two masked 2D rigs, use the native action director for a short duel, verify a playable MP4 and inspect at least 12 real decoded frames. A successful CI build is not sufficient to claim full cinematic animation.

## Next engineering blocks
1. Gesture-editable, per-character motion tracks and a source-rig retargeter that can synthesize missing actions reliably.
2. GLB weapon bones, sword colliders, precise timed contact/recoil, independent physical foot planting and configurable camera tracking.
3. Native Foley/audio beat synchronization, impact particles/materials, reflections and shot continuity.
4. Automatic visual QA (body proportions, foot sliding, sword attach, collision, source identity and temporal flicker) with targeted repair of failed segments, mobile thermal-safe previews and Drive-backed recovery.
