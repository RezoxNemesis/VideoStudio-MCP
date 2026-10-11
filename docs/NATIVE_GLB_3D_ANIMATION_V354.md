# VideoStudio 3.5.4 — Genuine 3D skinned GLB model viewer

## What this actually implements
**Real Android GPU 3D rendering** through open-source Google Filament 1.76.0 (Filament Android + gltfio Android + Android utilities). User-owned monolithic glTF 2.0 GLB assets have true depth, textured materials/PBR, lighting, a perspective camera, skeletal skinning, embedded glTF animation clips and actual interpolated bone matrices. Unlike rotating/stretches of 2D screenshots, this runtime has genuine renderable 3D geometry.

## How to use (Android only, no laptop)
1. Open any VideoStudio project, then Editor → **Real 3D GLB Model • Skinned Motion Preview** or AI Tools → **Open Real 3D Animation Studio**.
2. **Import GLB** opens Android's explicit `ACTION_OPEN_DOCUMENT` picker. The app reads only the user-selected URI; it never enumerates Gallery. The selected file must have a valid GLB v2 header, be <=64 MiB, and its bytes are stored app-privately at `creative_workspace/projects/<id>/models/<id>.glb` and registered as a project asset.
3. **Existing model** loads one previously imported app-owned GLB without requiring a picker.
4. Tap **Play/Pause**, **Next motion**, **Orbit camera** to explore actual skinned animation clips from the GLB. Native Filament PBR renders with a built-in light and frame pacing capped at 30 FPS for thermal safety.

## Privacy and model safety
- No extra broad storage or Gallery permissions, and no phone-media-library search.
- All 3D assets remain project-private and the linked SAF Drive project archive can include them.
- Input file size limit and GLB magic/version/length preflight prevent accidental huge unsupported inputs. Filament gltfio remains the final model parser.
- No paid inference API, no fabricated neural model. User's 3D file is rendered locally on compatible Android GPU devices.

## What it does NOT yet do
- **Does not** turn a flat PNG/JPEG into a complete, correct unseen 3D model. That requires actual modeling/reconstruction, rigging and animation authoring (or a verified local generative model).
- Does not yet perform service-owned headless *MP4 export* from the Filament GPU surface. At this stage it is a real-time 3D skeletal **preview**; MP4 requires an offscreen render target / GPU readback + Media3 pipeline and thermal-aware job checkpoints.
- Does not yet synchronize two different GLB characters' imported action clips with one shared collision-driven combat graph. That is the next cinematic action engine milestone.
- Not yet tested visually on the user's actual GLB model; JUnit + CI passing cannot prove material/animation appearance.

## Engineering follow-up
Implement GPU offscreen frame capture to VideoStudio app-private native media frame projects and verified 24/30 FPS Media3 export; import and map multiple model characters to one scene graph; extract rig/node joints and blend clips with timed collision events; add motion QA/foot lock and PBR shadow/reflection controls. Preview will accept GLB animated models first, then optionally GLTF with external images from explicitly selected project assets (never arbitrary URLs).

## Native smoke validation
Check the 3.5.4 version and stable protocol v3, run `FilamentGlbValidationTest`, keep existing 3.5.3 2D rig tests, and use actual on-device GLB with an embedded skeleton + animation to verify rotation, materials, camera, and proper preview lifecycle. No automatic full cinematic-3D quality claims.
