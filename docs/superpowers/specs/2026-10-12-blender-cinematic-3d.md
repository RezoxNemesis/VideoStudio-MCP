# Blender cinematic 3D production system

The owner's new three-page blueprint calls for an executable Blender/Rigify pipeline and
phone integration: carbon/ceramic mannequins, sixteen-joint quaternion motion, foot IK,
2–5-frame impact holds, damped camera shake, rigid wrist-attached katanas, measured blade
contacts, six-frame ribbons, contact debris, camera solving and optional pose/neural/frame
interpolation providers. Preserve the existing 3.7.3 source-artwork paths and all projects.
The owner already authorized autonomous design, implementation and GitHub/workspace actions.

## Architecture

Add `cinematic3d/`, an executable Python/Blender pipeline with a strict Action Timeline.
Blender 4.3.2, Rigify, glTF export and CPU Cycles are observed available. Generate real
skinned actors, portable full-scene GLB clips, a .blend authoring file, native-importable
bundle and optional cloud MP4. Blender is an additional production engine, not an APK
dependency. CPU rendering is the available default; no GPU/model installation is invented.

The phone imports a verified private bundle, edits shot order/duration/impact hold/camera/VFX,
previews the actual full-scene geometry without unit-cube/root normalization, and renders
the same scene/clock through Filament and Media3. A bundled procedural action library makes
this usable offline. Custom pose reconstruction remains explicitly authored/estimated and
requires a rebuilt bundle; native controls cannot pretend to recompile Blender constraints.

Blender output and Android output have separate execution evidence. Checksums verify
integrity/registration, not the authenticity of an arbitrary external producer. Imported
bundles are inert data; no downloaded Python or .blend scripts run on the phone.

## Shared contracts

Timeline schema `videostudio.cinematic3d.timeline.v1`: `name`, `fps` (24/30/60),
`motionProvider` (`procedural_mannequin` or `authored_3d`), `shots` (1..80).
Each shot: stable `id`, uppercase `action`, integer `durationFrames` (8..600),
`hitStopFrames` (0 or2..5; nonzero only impact actions), `camera`
(`mode` wide/orbit/macro/top_down, `fovDegrees`35..100, `shakeIntensity`0..1),
`vfx` (`sparkBurst`, `slashTrail`, `rubble` booleans), optional `sourceAnchors`
ordered `{assetId,sha256}` occurrences, optional `poseKeyframes` and `footContacts`.
Reject unknown fields, nonfinite numbers, oversized/deep JSON, unsafe IDs, impossible
intervals and unordered/duplicate key phases. Cap total frames at12000, metadata512KiB.
World units are metres, right-handed Y-up, local quaternions are normalized wxyz.
Explicitly convert Blender Z-up/glTF xyzw. A flip >180° needs intermediate keys.

Actions: STANDOFF, CHARGE, CLASH, BLADE_LOCK, FOLLOW_THROUGH, KNOCKBACK, LANDING,
RECOVERY, AERIAL_FLIP, TUMBLE, MACRO_CLASH. Impact actions are CLASH, BLADE_LOCK,
MACRO_CLASH. Sample output n at n/fps, never endpoint-inclusive retiming. Impact hold
pauses actor/prop motion for the exact declared frame count while camera shake continues.
`sample_shot(shot, frame, fps)` produces normalized `phase`, `motionSeconds`, `hitStop`,
`camera` `{position,target,fovDegrees}`, and `impactFrame`. Cuts never blend.

For duration N and hold H>0 set I=floor((N-H)/2). Before I, phase=0.5*n/I;
frames [I,I+H) have phase0.5; afterwards phase=0.5+0.5*(n-(I+H-1))/(N-(I+H-1)).
Exactly H samples share the contact pose; the next sample advances. H0 uses phase=n/N
and I=floor(N/2). motionSeconds=phase*(N-H)/fps; cameras use wall time n/fps.
Pose keys are `{phase,actors:{black:{root,rotations},white:{root,rotations}}}` with
sixteen rotations per actor. Foot contacts are `{actor,foot,startPhase,endPhase,anchor}`.
Library manifests explicitly set `generator.actionLibrary:true` to permit exact action
binding across edited shot IDs; regular bundles require exact shot ID and action binding.

Bundle manifest schema `videostudio.cinematic3d.bundle.v1`: `timelineFile`,
`files` entries `{path,role,sha256,bytes}`, `scenes` entries
`{shotId,action,path,animationName,cameraName,clipDurationSeconds,impactPhase}`,
`generator`, `evidence`. Exact SHA hashes refer to artifact bytes. The timeline file
and every scene are required; .blend/video/provenance/QA files are optional inert artifacts.
Scene clips encode both weighted actors, world roots, wrist-parented swords and portable
VFX; exact manifest-declared names, never fuzzy animation fallback. Multiple entries may
bind the same action-library GLB file. Editing baked pose inputs requires rebuild.

Private importer bounds: archive64MiB, expanded256MiB, individual GLB16MiB, files256,
JSON512KiB. Reject duplicate/traversal/absolute/symlink archive entries, zip expansion,
wrong magic/version, external GLB resource URIs, changed hashes and foreign source bindings.
Stage fully, then publish one immutable project generation under source revision CAS.
Source hash verification occurs outside project writer locks; revalidate registration on
publication. Preserve duplicates and order; never search Gallery or sort panels by name.

## Integration

Native minimum version3.8.0 / versionCode380, existing package and original signer.
New helpers: `Cinematic3DTimeline`, `Cinematic3DProgramStore`,
`NativeCinematicBundleStore`, `NativeCinematicSceneComposer`.
Phone `Cinematic3DStudioActivity` plus faithful `FilamentGlbActivity` preview mode.
Native actions: cinematic_3d_capabilities, cinematic_3d_install_builtin,
cinematic_3d_import_bundle, cinematic_3d_save_program, cinematic_3d_get_program,
cinematic_3d_list_programs, cinematic_3d_plan, cinematic_3d_render,
cinematic_3d_export_request. MCP typed tools use the same helpers and result receipts.

Store save request `{projectId,sourceProjectUpdatedAt,programId?,expectedProgramSha256?,
bundleId,timeline}`. Render request `{projectId,programId,expectedProgramSha256?,
width?,height?,render?,fileName?}`. Bundle install request
`{projectId,sourceProjectUpdatedAt,bundleAssetId}`; built-in install only needs
projectId/source revision. Programs are independent of earlier source-rig programs.
Get/list/plan are read-only. Rendering creates a fresh output project and immutable frame
generation, uses rational frame metadata accepted by NativeRenderEngine, existing thermal
checkpoints and bounded GPU capture. Source/program/bundle changes reject before effects.
No native GPU/device or cinematic acceptance is claimed from JVM tests/cloud rendering.

## Optional providers and verification

Pose adapters execute configured real model runners and preserve prediction confidence,
source hashes and ambiguity; missing code/weights/runtime return unavailable. Camera PnP
uses supplied3D/2D correspondences and intrinsics, records residuals, and rejects degenerate
or inaccurate solves. ComfyUI submits a vetted configured workflow to a ready endpoint and
retrieves actual history/outputs with timeouts. RIFE/FILM executes configured checked
runners and validates output frame count/FPS/duration; cuts and hit-stop frames are protected.
No model download/GPU charge or arbitrary executable path comes from timeline JSON.

Verification: meaningful Python/native/Worker RED then GREEN, actual Blender scene/GLB/CPU
MP4 and visual inspection, bundle/hash/clock/provider regressions, independent reviews,
full existing suites then original-signer APK CI. GitHub workflow runs the free CPU path
and publishes verified artifacts. The owner's exact80 panels were not attached to this
new request; generated demonstration art is never substituted for those source originals.

## Final camera and packaging integration

Camera objects may include 1..128 strictly ordered `keyframes` containing
`phase`, `position`, `target`, and optional `up`. Their phases use output wall
fraction n/N, independently of the actor's impact hold. Coordinates are bounded
metres in [-10000,10000]; the view/up basis must remain finite and nondegenerate.
The calibrated OpenCV adapter emits this exact camera patch and vertical FOV for
a centered, zero-skew pinhole lens with square pixels. Its declared viewport
aspect must match to retain the measured projection. Unsupported lens models
are rejected. Fixed FOV remains a shot control; changing a preset explicitly
removes the authored camera track.

Portable phone archives omit the large Blender authoring file by default.
The original `.blend` remains in the cloud generation with its provenance;
Android receives the embedded scene, timeline and checked metadata only. The
built-in eleven-action phone archive is approximately600KiB and retains both
weighted actors, original world roots, actual sword sockets and named clips.
