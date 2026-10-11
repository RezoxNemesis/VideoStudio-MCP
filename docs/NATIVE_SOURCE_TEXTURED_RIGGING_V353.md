# VideoStudio 3.5.3 • Native Source-Textured 2D Character Rig

## Scope
The first actual **source-character** animation vertical slice. A manually traced cutout from a VideoStudio-owned image is bound to a 16-joint skeleton. A 2D skinned textured mesh follows real, timestamped articulated poses from CombatRigSolver. This is **not** an AI reconstruction of hidden anatomy, a neural video model, or a true 3D character renderer.

### Phone-only workflow
1. Import a character picture into VideoStudio via its explicit Android file picker or ChatGPT attached image (Gallery enumeration is forbidden).
2. Open Editor → **Character Rig Builder • Tap Bones + Trace Body** or AI Tools → **Rig My Black & White Fighters**.
3. Select an imported source image. Mark the 16 labeled points in order; trace the character body *without* its sword. If the image is already a genuine transparent sprite, switch to Transparent PNG instead of tracing.
4. Save the BLACK rig. Switch to WHITE and repeat. Each profile is stored under `creative_workspace/projects/<sourceProjectId>/rigs/<profileId>.json` and included in the linked Drive archive.
5. Tap **Render Source-Rigged 2D Duel** in AI Tools or use MCP v3 native action `render_rigged_2d` with `sourceProjectId`, `blackRigId`, `whiteRigId`, `durationSeconds` (2–6), `fps` (24/30), `width`, `height`, `storyboardMotion` and `backgroundMode`.
6. VideoStudio writes unique native JPEG frames into a **separate output project**, then optionally performs an actual Media3 MP4 export. The source project and files remain unchanged.

### API example
```json
{
  "nativeAction": "render_rigged_2d",
  "nativeParameters": {
    "sourceProjectId": "<imported-project-id>",
    "blackRigId": "<saved-rig-id>",
    "whiteRigId": "<saved-rig-id>",
    "backgroundMode": "procedural",
    "storyboardMotion": true,
    "fps": 24,
    "width": 432,
    "height": 768,
    "durationSeconds": 4.6,
    "quality": "720p",
    "render": true,
    "fileName": "Ashes_Source_Rigged2D.mp4"
  }
}
```

Background options: `procedural` is an original stable built-in world without duplicate source fighters; `source_median` estimates a static plate from imported image shots (may retain source ghosts, never claim clean reconstruction); `clean_asset` uses an explicitly selected **separate** source image and requires `backgroundAssetId` that does not match either fighter's source photo. It must actually be a clean plate.

### Engineering and safety
- Rig2DProfile validates bounded normalized landmarks, polygon outline and IDs; Rig2DProjectStore writes atomic project-private files; no Gallery browsing.
- NativeSkinnedSprite2D isolates masked original pixels, computes 3-nearest-bone mesh weights, and GPU/software Canvas `drawBitmapMesh` deforms the original texture at each CombatRigSolver timestamp.
- NativeRiggedCombatRenderer composites TWO independently moving source-image meshes, attached tapered metal blades, timed sparks, grounding shadows and dust from the same combat timeline.
- NativeRiggedCombatComposer creates bounded 2–6-second frame projects at 24/30 FPS with unique generated assets, no still-image transitions, and per-frame rig provenance.
- ControlService uses existing thermal guards, cancellation, checkpointed recovery and native Media3 export; exported clips must have a verified readable published MP4 before success.
- Two authored rigs are a necessary user action. **AI-generated skeleton/mask inference is NOT installed**, and a crude polygon may contain old background pixels that would reduce visual quality. Use precise character cutouts.

### Known remaining implementation milestones (do not claim complete)
**M3 True 3D:** load user-owned skinned glTF/GLB assets with bound bones, animations, materials, perspective cameras and mobile GPU runtime. A flat image cannot provide unseen surfaces automatically.
**M4 Cinematic action graph:** make runs, jumps, attacks, counters, parries, knockback, camera tracks, debris, shadows, reflections and audio time-coded, reusable and editable from the phone.
**M5 QA and hardware:** compare rigged-output frames against reference identity, joint lengths, planted feet, exact blade contact and occlusion; preview proxies, GPU/thermal profiling, drive verification and native device rendering.

### Verification and release discipline
Check `Rig2DProfileTest`, `NativeSkinnedSprite2DTest`, existing combat/pose tests, Android unit suite and APK assembly. Artistic acceptance requires explicit rendered video inspection on the user's Android device. A green CI is never proof that the character cutout/mask or animation looks correct. This is an incremental 3.5.3 developer build, not a final studio-grade 3D release.
