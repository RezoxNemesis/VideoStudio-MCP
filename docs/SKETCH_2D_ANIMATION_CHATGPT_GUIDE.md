# ChatGPT: animate prepared sketches

Use this guide with the [human workflow](SKETCH_2D_ANIMATION_GUIDE.md). The
prepared-sketch animation actions require Video studio 4.4.0 or later on the
phone. A downloaded APK, repository change or successful cloud test does not
establish the installed version.

## Establish the native session

Call `app_status` first. Confirm the intended device, installed version, native
execution availability and Gallery access disabled. Then use the native actions
through the connector's generic tool:

```json
{
  "action": "sketch_capabilities",
  "parameters": {}
}
```

The wrapper above is the argument to `app_execute`. An older deployed connector
may not expose typed names for every native action. The encrypted app-key route
can also submit the same native action and parameters; follow
[its transport and receipt instructions](VIDEO_STUDIO_APP_KEY.md).

Admission, upload, a command ID or an online heartbeat is not completion. Read
the authenticated terminal result. Do not resubmit an uncertain mutation with
a different identity merely because the first reply is delayed.

## Inspect and prepare the source

Use `sketch_list` and `sketch_get` to identify the correct prepared sketch.
`sketch_get` without a layer/object returns the header and layer summaries;
geometry is deliberately paged. Pin `expectedRevision` while reading layer,
object, point and path-command pages. Follow the returned `moreObjects`,
`morePoints` and `moreCommands` fields until the required geometry is complete.

Record the source revision and document hash. Inspect actual native previews,
not only object counts. Identify independent body parts, their ownership and
their exposure ranges. Separate or redraw missing parts with Sketch Studio
operations. Never substitute a reference image, traced result or clone painting
and call it a from-scratch drawing.

For new original artwork, use an `original_strokes` live drawing session as
described in the [live drawing guide](sketch-live-drawing.md). End that session
before adopting prepared artwork for animation. An animation can reuse existing
drawings; its provenance must describe that reuse accurately.

## Native animation actions

| Action | Purpose |
| --- | --- |
| `sketch_animation_save` | Validate and save an animation program with a frozen source snapshot. |
| `sketch_animation_get` | Read the saved program, hashes, revision and report. It does not return the full frozen source document. |
| `sketch_animation_list` | Discover saved animation metadata. |
| `sketch_animation_bake` | Compile a saved animation into a fresh editable Sketch Studio document. |

Use the saved animation revision for update and bake checks. `sourceRevision`
belongs to the source sketch; it is not the animation revision. A new animation
uses `expectedRevision: 0`. Obtain all later revisions from authoritative native
results.

This complete example raises and lowers an articulated arm over 100 frames.
First prepare an owned sketch with separate vector objects named `upper_arm`
and `forearm`, aligned to the joint positions below. Replace the sketch ID,
revision and coordinates with the inspected source values; the example is not
automatic rig inference. Coordinates are normalized to the canvas and rotations
are in degrees.

```json
{
  "action": "sketch_animation_save",
  "parameters": {
    "animationId": "courier_rooftop_shot_01",
    "sketchId": "courier_prepared_parts",
    "sourceRevision": 12,
    "expectedRevision": 0,
    "operationId": "courier_shot_01_save_001",
    "program": {
      "schema": "videostudio.sketch-animation.v1",
      "frameCount": 100,
      "fps": 24,
      "rigs": [{
        "id": "arm",
        "joints": [
          {"id": "shoulder", "x": 0.45, "y": 0.36},
          {"id": "elbow", "parent": "shoulder", "x": 0.32, "y": 0.49},
          {"id": "wrist", "parent": "elbow", "x": 0.30, "y": 0.63}
        ],
        "bindings": [
          {"objectId": "upper_arm", "jointId": "shoulder"},
          {"objectId": "forearm", "jointId": "elbow"}
        ],
        "poses": [
          {"frame": 0, "interpolation": "smooth", "rotations": {"shoulder": 0, "elbow": 0}},
          {"frame": 24, "interpolation": "smooth", "rotations": {"shoulder": 55, "elbow": -35}},
          {"frame": 60, "interpolation": "smooth", "rotations": {"shoulder": 25, "elbow": -15}},
          {"frame": 99, "interpolation": "hold", "rotations": {"shoulder": 0, "elbow": 0}}
        ],
        "ik": []
      }],
      "camera": [],
      "cameraLayerIds": [],
      "dialogues": [],
      "mouthCels": []
    }
  }
}
```

Empty camera, dialogue and mouth-cel arrays make this a deliberately small rig
example. They do not claim a complete acting shot or artistic acceptance. Joint
IDs are local to this rig; object IDs must already exist in the prepared sketch.
Sparse rotation values carry forward from earlier poses; omitted root offsets
begin at zero. Interpolation belongs to the starting pose of each interval.

To bake a saved revision into a new editable sketch:

```json
{
  "action": "sketch_animation_bake",
  "parameters": {
    "animationId": "courier_rooftop_shot_01",
    "expectedRevision": 1,
    "operationId": "courier_shot_01_bake_001",
    "outputSketchId": "courier_rooftop_shot_01_baked_001"
  }
}
```

Use the actual save revision in that request. The output ID must be fresh. Save
and bake retries with the same `operationId` and canonical request return the
same authoritative receipt. Reusing an operation ID with changed arguments is
an error. A revision conflict requires reading and reviewing the current state,
not trying another random revision.

Save freezes the source drawing, source hash and program hash. Later human edits
to the preparation sketch do not mutate that saved source. Updating with the
same source revision reuses that snapshot; deliberately supplying a newer current
source revision refreshes it after the source check. Bake preserves the
original and copies the owned reference/paint dependencies into the output;
verify the returned `outputSketchId`, `outputRevision`, `documentSha256`,
`report` and `persisted` fields before proceeding.

## Author motion, not only a moving picture

Use hierarchical joints and independent part bindings. Author anticipation,
contact and recovery keys; choose easing by the intended action. Use a two-bone
IK target over each planted-foot interval. Draw replacement poses when a rigid
part or weighted vector bend cannot represent the required silhouette.

IK `hip`, `knee` and `ankle` identify an actual parent chain; `start`/`end` are
inclusive frames and `x`/`y` are the normalized world target. `bend` selects its
bend direction. Unreachable targets fail rather than stretching the limb.
Weighted bindings require one influence map per supported vector vertex and
positive joint weights summing to one; they do not warp raster pixels.

Use separately drawn artwork and exposure ranges for a new angle or POV. A
camera key transforms framing; it does not reconstruct unseen anatomy. Use mouth
cels for expression changes and timed dialogue text for readable captions.
Neither feature synthesizes a voice or proves lip synchronization.
Timed cel ranges are inclusive. Disjoint ranges can reuse the same prepared
object; keep mouth drawings and replacement views explicitly authored.

Do not treat joint counts, pixel changes or a successful compiler report as
artistic acceptance. Inspect native frames around every cut, contact, occlusion
and expression change, then watch the complete native output at its actual FPS.

## Transport and completion discipline

Keep request bodies small. Fetch geometry in pages instead of repeatedly asking
for full state. Large files belong in the supported native media/drawing import
path, not a giant inline command. A repaired APK does not prove that an older
primary relay was redeployed or that its storage is healthy.

Saved programs are limited to 512 KiB and source snapshots to 4 MiB. The store
allows 128 scenes within a 256 MiB budget; split larger work into shots rather
than treating capacity limits as infinite. `sketch_animation_get` returns the
program but omits its full source snapshot to avoid echoing a large drawing in
an acknowledgement. Retain stable
command IDs, operation IDs, revision receipts and hashes in a private work log.
If the transport reports an unavailable or paged result, recover the original
receipt through the documented app-key route rather than repeating the effect.

After bake, use `sketch_get` to verify the output's frame count, frame rate and
hash. Use existing native preview and export actions or open it in Sketch
Studio for **File → Export video**. Report separately: source authored, program
saved, document baked, preview inspected, export completed, and physical-phone
playback observed. Only claim stages supported by their actual evidence.

An IK interval may include `rotation` (−3600…3600 degrees) to lock the end
joint’s world rotation relative to the rest drawing. Use zero for an initially
level planted sole. Inspect both `errorPixels` and `rotationErrorDegrees` in
contact diagnostics. Bound artwork requires identity spatial layer transforms;
unsupported prepared layer transforms are rejected before saving.

Weighted vector bindings require flat-colour geometry. A weighted gradient is
rejected rather than letting its colours slide over the deformed shape. Use
a rigid gradient part or separately authored flat-colour regions instead.
