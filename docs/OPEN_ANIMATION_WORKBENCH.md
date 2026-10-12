# VideoStudio 3.9 open animation workbench

This update builds on the 3.8.1 native-command recovery repair. It preserves the
application ID, MCP v3 identity, databases, original media and original signing
certificate. Install a verified 3.9 APK over the existing app. Keep app data.

## Phone and ChatGPT authoring

Open **Sketch Studio** from the editor. Draw pressure strokes, erase, place
shapes or owned image references, arrange layers and edit exposure/cel timing.
Onion skins and playback use the same Canvas frame renderer as export. Sketch
transforms and keys use normalized coordinates. Export creates a separate
project and a native Media3 MP4; frame capture alone does not certify encoding.

ChatGPT uses `app_sketch_create`, `app_sketch_edit`, `app_sketch_get`,
`app_sketch_preview`, undo/redo, reference import and render. Keep the returned
revision and a stable operation ID. Geometry reads are paged; preview returns
an MCP image of the actual stored frame. Conflicting revisions fail before a
save, so a stale command cannot replace newer drawing work.

The main editor previews the actual edited composition, including mixed
images/video/audio, titles and image overlays. Numeric transforms, opacity and
timed keys share the export effect builder. Chroma key, feathered rectangle and
ellipse masks and thresholded glow render pixels. `app_precision_edit` applies
atomic edits to stable clip IDs with `expectedUpdatedAt`; `app_set_studio_layers`
uses the same guarded layer save as the phone. Splitting preserves the existing
keyframe clock. Unsupported tools fail clearly rather than storing an effect
name that the renderer ignores.

## 3D actions, models and mapping

In **Cinematic 3D**, install the extended offline library for 21 actions: the
original eleven combat actions plus idle, walk, run, jump, dodge, duck, punch,
kick, dance and wave. The original starter pack remains available. The extended
pack contains two skinned fighters and actual named animation clips, within a
bounded 3.2 MB GLB. New actions require native 3.9.0 or newer.

Human controls include athletic, slender, stocky, chibi and robot body presets,
bounded proportions and materials, grounded/anime/martial/cartoony styles,
16 named joint quaternion keys, Euler conversion, actor roots and ordered foot
contacts. Changes to baked geometry or motion require a rebuilt scene. **Build
updated scene** transfers the validated draft and exact source identities into
Open Pipeline. It preserves the accepted program until a matching new bundle
is built and imported; it cannot pretend an old GLB contains the edited motion.

The Blender producer supports evaluated pose/projection, camera, beauty, depth,
normal, actor-mask and prop-mask conditioning passes with frame hashes. Explicit
reference guides retain source/profile hashes and depth uncertainty. These are
controllable mappings and procedural actors, not exact inverse reconstruction
of an illustrated character. See [cinematic producer](../cinematic3d/README.md).

## Generation and Google Drive pipeline

There are no Veo, Kling or Runway APIs or subscriptions. The self-hosted runner
uses local open-source CogVideoX-2B/Diffusers, Blender and FFmpeg, plus configured
RIFE/FILM interpolation adapters. Text-to-video and video-to-video use the same
verified public model weights. Video conditioning accepts one owned MP4 and
explicit strength; source samples, timestamps and hashes are recorded.

The phone and `app_open_pipeline_*` tools use the same request/job/result facade:

1. Capture owned inputs and exact project revision into an immutable `.vsjob`.
2. Submit to an owner-controlled HTTPS runner, or archive the request to the
   app's already linked Drive folder for a mounted-folder runner.
3. Run generation on the configured computer with durable status, deadlines,
   bounded resources and cancellation of the complete worker process group.
4. Publish a `.vsresult` only after artifact hashes and decoded video clock pass.
5. Download or restore the result, verify it against the original request, then
   import into a fresh project. Every cinematic bundle must be installed and
   revalidated before the native app reports an imported usable result.

Drive stores inputs, model archives and results. It supplies no inference
compute. Project offload verifies immutable remote generations by readback,
rechecks local identities and holds the mutation guard through eviction. Local
originals and final exports remain protected. A mounted runner uses create-only
publication; unsupported mounts retain a verified pending file and report the
storage limitation. Readback on a mount does not certify remote Drive syncing.

See [runner setup and wire contract](OPEN_PIPELINE.md) and
[native operations](OPEN_PIPELINE_NATIVE.md). Runner endpoint credentials remain
Keystore protected on Android and are removed from persisted command diagnostics
and MCP result views. These are credentials for your own server, not a paid
generation provider.

## Evidence and limits

The engineering workspace executed the real pinned Apache-2.0 CogVideoX-2B on
CPU and encoded decoded-count-verified MP4 output. Low resolution and low-step
probes establish runtime execution; their visual quality does not meet an
industry cinematic target. Production generation needs sufficient compute and
quality settings. The 2B VAE requires `8k+1` frame counts for the exact output
clock; incompatible counts and zero effective denoising fail admission.

Linux Mesa executed the exact native effect shaders and passed twelve measured
pixel checks. JVM/native-Canvas tests and local MCP SDK tests provide further
implementation evidence. They do not establish a phone GPU run, installed APK,
live ChatGPT host behavior or access to a specific Google Drive account. Those
claims require actual device/backend evidence. Source/CI verification never
certifies original-image reconstruction or cinematic quality.
