# Cinematic3D production CLI

Run from the repository root with Python 3.11+. Blender/Rigify production and CPU
Cycles output are cloud evidence. Android Filament/Media3 output requires actual
device execution. Procedural mannequins and model predictions do not establish
original-image reconstruction or cinematic acceptance.

Optional timeline `actors` declares both `black` and `white` with one humanoid
`preset`: `athletic`, `slender`, `stocky`, `chibi`, or `robot`. Optional
`heightScale`, `buildScale`, and `headScale` each use 0.8..1.2. An optional
`material` supplies linear RGB `color`, `metallic` (0..1), and `roughness`
(0.1..1). Real rest bones, geometry, skin weights, and IK change together.
Leaving `actors` absent retains the existing carbon/ceramic mannequins.

Each shot can add `motionStyle: {"preset":"anime","intensity":0.8}`. Presets are
`grounded`, `anime`, `martial`, and `cartoony`; intensity is 0..1. The ten added
actions are IDLE/WALK/RUN/JUMP/DODGE/DUCK/PUNCH/KICK/DANCE/WAVE. Styles change
sampled rotations and roots; custom model/style inputs require rebuilding the
matching portable clips. The old eleven-action library remains the default.
For a smaller imported action pack, declare exactly its actions in the timeline
and use `--library-only --actions WAVE,WALK,PUNCH`. No expanded built-in APK asset
is required for an imported pack.

`build --conditioning-frames 8` also creates `conditioning/manifest.json` and an
ordered prefix of the exact output clock, bounded to 1200 frames. Each frame has
evaluated pose/camera JSON, beauty PNG, metric depth EXR, raw world Y-up normals,
and separate actor/katana object masks. Every sidecar file has a size and SHA-256;
conditioning remains in the cloud generation and is omitted from the phone
archive. This exports real rendering guides for configured local open-source
generation workflows; it does not itself execute video diffusion.

`python -m cinematic3d guide --request reference-guide.json --output guide.json`
maps sixteen original-image normalized joint marks into a scaled orthographic
3D guide. Request schema is `videostudio.cinematic3d.reference-guide-request.v1`
with `sourceSha256`, `profileSha256`, `joints`, oriented `imageWidth`/`imageHeight`,
`heightMeters` (0.3..3), sixteen explicitly authored `depthMeters` (-10..10),
and sixteen `uncertaintyMeters` (0..10). The receipt preserves those assumptions
and declared hashes. It does not observe the original source/profile bytes or
recover hidden depth, identity, texture, or motion.

```bash
python -m cinematic3d capabilities
python -m cinematic3d validate --timeline timeline.json
python -m cinematic3d validate timeline.json --output normalized-timeline.json
python -m cinematic3d validate cinematic3d-authoring-request.json
python -m cinematic3d build cinematic3d-authoring-request.json --output /tmp/phone-request-generation-01
python -m cinematic3d build timeline.json --output /tmp/cinematic-generation-01 --library-only
python -m cinematic3d build --timeline timeline.json --output /tmp/cinematic-generation-02 --render-frames 30
python -m cinematic3d bundle --generation /tmp/cinematic-generation-01 --output /tmp/demo.vscinematic
```

`build` invokes real Blender with factory startup, script auto-execution disabled,
and bounded subprocess execution. It writes the scene GLB, inert `.blend`, timeline,
QA/provenance, verified `.vscinematic` archive and execution receipt. The phone
archive omits `.blend` authoring data by default; that file remains available
separately in the cloud generation, avoiding unused authoring data on the phone. Requested CPU
frames are encoded with FFmpeg and checked with FFprobe. `--render-frames` accepts
0..12000 and renders at most the validated timeline length. It defaults to zero;
full CPU renders can exceed the configured execution deadline. Use a new output
directory for every generation. Receipt files and CLI bundle paths are create-only.

`validate` and `build` also accept the phone's inert `export-request.v1` wrapper.
They freeze the input bytes, check the declared ordered source record hashes, and
record `exportRequestSha256` with the source project/program/bundle bindings in the
CLI result. The cloud has not measured those original image bytes or revalidated
the phone's live project revision; both verification flags remain false. No command,
model path or endpoint may come from the exported request.
The verified generation retains the exact inert wrapper as `source-request.json`
with its artifact hash; it supplies provenance and cannot execute code.

The timeline requires schema `videostudio.cinematic3d.timeline.v1`, 24/30/60 FPS,
1..80 shots and at most 12000 frames. Each shot includes its stable ID, uppercase
action, 8..600 `durationFrames`, impact-only 0 or 2..5 `hitStopFrames`, camera and
VFX settings. See the shared clock fixture in
`tests/cinematic3d/fixtures/timeline_clock_golden.json`. Source anchors preserve
literal ordered `{assetId,sha256}` occurrences. Pose keys use sixteen normalized
local `w,x,y,z` quaternions and right-handed Y-up metre roots.

Optional model runners are unavailable until the operator supplies real code,
weights and their exact SHA-256. Nothing downloads models or allocates a paid GPU.
CLI exit status is 0 for completion, 69 for an unavailable provider, 1 for execution
failure, and 2 for invalid input. Every result is JSON; child logs remain private.
`capabilities` probes Blender's version and reports optional providers as configured
or unavailable. Configuration alone never claims model, Rigify, GPU or endpoint readiness.

Local configuration is a separate trusted file with schema
`videostudio.cinematic3d.local-config.v1`; timelines and provider request JSON cannot
contain executable commands. Relative configuration paths resolve beside the config.
For example, replace the paths and zero hashes below with real checked artifacts:

```json
{
  "schema": "videostudio.cinematic3d.local-config.v1",
  "blenderExecutable": "/trusted/blender/blender",
  "pose": {
    "family": "vitpose",
    "command": ["python3", "./wrappers/pose_runner.py"],
    "weights": "./models/vitpose.bin",
    "weightsSha256": "0000000000000000000000000000000000000000000000000000000000000000",
    "timeoutSeconds": 120
  },
  "interpolation": {
    "family": "rife",
    "command": ["python3", "./wrappers/rife_runner.py"],
    "weights": "./models/rife.bin",
    "weightsSha256": "0000000000000000000000000000000000000000000000000000000000000000",
    "timeoutSeconds": 120
  }
}
```

Pose families are `4dhumans`, `vitpose`, or `smplx`; interpolation families are
`rife` or `film`. Wrappers receive `--input REQUEST --output OUTPUT --weights FILE`.
They must implement the schemas described in `providers.py` and `polish.py`, and
exit 69 when their model runtime is unavailable. Pose receipts preserve source hashes,
per-joint confidence and ambiguity; predictions require reviewed authoring/rebuilding
before they become a new phone bundle.

```bash
python -m cinematic3d pose --config local-config.json --source original.png --source-sha256 EXACT_SHA256 --output pose-receipt.json
python -m cinematic3d camera --request camera-correspondences.json --output camera-receipt.json
python -m cinematic3d interpolate --config local-config.json --request frames.json --output /tmp/interpolated-01 --receipt interpolation-receipt.json
```

Camera requests contain `points3d`, `points2d`, `intrinsics` and optional
`maxReprojectionErrorPx` (default 2). Optional paired `imageWidth` and
`imageHeight`, plus `phase` (default0), produce a directly usable `camera` patch.
Merge its `fovDegrees` and `keyframes` into a shot's existing camera object,
retaining `mode` and `shakeIntensity`. This patch requires a centered pinhole
lens with square pixels, no distortion and the declared viewport aspect.
Camera key phases use wall fraction n/N and continue through actor hit stops.
Use one key for a fixed calibrated camera or1..128 ordered keys for a camera path.
The phone preserves that track until you explicitly choose preset controls. Supply at least six distinct non-collinear
measured correspondences; OpenCV/NumPy absence returns unavailable. Completed solves
record actual per-point residuals and camera transforms, including glTF camera axes.

Interpolation requests preserve frame path order. Relative paths resolve beside the
request. Cuts and hit-stop ranges refer to input indices; ranges are end-exclusive:

```json
{
  "frames": ["frames/frame_000000.png", "frames/frame_000001.png", "frames/frame_000002.png"],
  "inputFps": 30,
  "outputFps": 60,
  "cuts": [2],
  "hitStopRanges": [[0, 2]]
}
```

ComfyUI requires an additional local `comfyui` configuration object with `endpoint`,
`workflowFile`, `workflowSha256`, `allowedNodeTypes`, `models`, `imageInputBinding`
and `outputNodes`. Each model declares `path`, `sha256`, `nodeId`, `inputName` and
optional `modelName`. For example, a vetted graph's loader binding can be:

```json
{
  "endpoint": "http://127.0.0.1:8188",
  "workflowFile": "./workflows/vetted-api-graph.json",
  "workflowSha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "allowedNodeTypes": ["LoadImage", "CheckpointLoaderSimple", "SaveImage"],
  "models": [{
    "path": "./models/checked-model.safetensors",
    "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
    "nodeId": "2",
    "inputName": "ckpt_name",
    "modelName": "checked-model.safetensors"
  }],
  "imageInputBinding": ["1", "image"],
  "outputNodes": ["9"],
  "timeoutSeconds": 120,
  "pollIntervalSeconds": 0.25
}
```

The node IDs, allowlist and model names must match the actual connected workflow;
this example is a binding shape, not a working inference graph. Place it under
`comfyui` in the full local config, then run:

```bash
python -m cinematic3d comfyui --config local-config.json --source original.png --source-sha256 EXACT_SHA256 --output /tmp/comfy-output-01 --receipt comfy-receipt.json
```

The adapter checks local model/workflow hashes, endpoint readiness, actual prompt
history and downloaded PNG outputs. Stock ComfyUI exposes loaded model names rather
than runtime weight hashes, so those evidence scopes remain distinct. A client
timeout leaves remote server execution unmeasured and does not interrupt other jobs.
