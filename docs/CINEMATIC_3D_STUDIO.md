# Cinematic 3D Studio — VideoStudio 3.8.0

The new workflow adds articulated 3D scenes alongside the original artwork editor.
It preserves the existing Android package, signer, MCP v3 identity and project data.

## Use it on the phone

Open **AI → Open Cinematic 3D Studio** in a project. Install the bundled scenes or
import an explicit `.vscinematic` file. The offline library includes STANDOFF,
CHARGE, CLASH, BLADE_LOCK, FOLLOW_THROUGH, KNOCKBACK, LANDING, RECOVERY, AERIAL_FLIP,
TUMBLE and MACRO_CLASH.

Add, duplicate, remove or reorder library shots; choose an action; edit duration,
2–5 frame impact holds, camera framing, shake and effects; choose 24, 30 or60 FPS.
Save the timeline, then open the live preview. Play/pause, restart and scrub the
actual scene. Native MP4 export creates a separate output project using the same
sampling code and named scene clips. Existing originals remain intact.

Custom scenes retain their baked character roots, skins and sword sockets. Human
controls can retime shots and change cameras/effects; changed character poses or
foot-contact inputs require a rebuilt Blender bundle. Authored camera keys survive
control edits until you explicitly switch to preset controls. Unsaved edits,
pending saves and interrupted jobs have bounded private recovery state.

## Use it through MCP

The additive application-v3 tools require native app version3.8.0 or newer:

| Tool | Effect |
| --- | --- |
| `app_cinematic_3d_capabilities` | Read native availability and limits |
| `app_cinematic_3d_install_builtin` | Install the owned offline scene library |
| `app_cinematic_3d_import_bundle` | Install an explicitly registered bundle asset |
| `app_cinematic_3d_save_program` | Save a timeline with source revision and program CAS |
| `app_cinematic_3d_get_program` | Read a strict executable binding; `editOnly:true` reads a non-executable stale draft |
| `app_cinematic_3d_list_programs` | List saved identities and stale revisions |
| `app_cinematic_3d_plan` | Verify source/program/bundle bindings and compile the frame plan |
| `app_cinematic_3d_render` | Queue full-scene native capture and optional MP4 export |
| `app_cinematic_3d_export_request` | Return the inert cloud authoring request |

Use `projectId` and its current `sourceProjectUpdatedAt` for installation/save.
Keep the returned `programId` and SHA for preview/render/export. Render requests
may supply even `width`/`height` within256..1280, with the shorter dimension≤720
and at most720×1280 pixels. A calibrated camera's declared viewport aspect must
match when retaining its measured projection. These native actions wait for the
phone; they have no browser rendering substitute.

## Build custom scenes

See [the CLI guide](../cinematic3d/README.md). Real headless Blender generates
Rigify mannequins, weighted geometry, local quaternion motion, grounded IK,
wrist-attached swords, impact timing, camera tracks and portable VFX. The phone
archive contains checked inert scene data; the `.blend` authoring file remains in
the cloud generation. Calibrated OpenCV camera output can be merged directly into
a shot. Model inference, ComfyUI and RIFE/FILM use separately configured real
runners/weights and retain confidence, hashes, timeouts and availability evidence.

## Evidence boundaries

The bundled actors are procedural mannequins. They do not reconstruct the owner's
original drawings. The contract accepts up to80 shots/12000 output frames within
file and metadata budgets; this does not mean an unavailable80-panel source has
been reconstructed or accepted. Models/weights and ComfyUI are not installed by
this update. Calibrated PnP requires supplied correspondences and camera intrinsics.

Cloud Blender checks and MP4 inspection are separate from Android GPU execution.
Native results retain actual encoder reports, while encoded/decoded timing and
cinematic acceptance remain unverified until measured on the device. APK/source
verification does not claim the app was installed or the live MCP Worker deployed.
