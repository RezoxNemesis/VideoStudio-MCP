# Articulated 2D animation

The native 2D rig deforms the registered source texture using a triangulated mesh,
hierarchical bone transforms, inverse bind matrices and linear blend skinning.
The same compiled geometry and authored clock feed the GPU monitor and export.
This implementation is deterministic animation. It does not segment a character,
infer joints, inpaint uncovered regions or synthesize a new view. A transparent
character cutout or owner-supplied regions provide the best isolation; a full image
mesh can deform its background too.

Implementation is source-only. The regression cases have been authored but have
not run, and neither the native app nor its GPU pass has been exercised in this
implementation phase.

`effects.rig2d` version 1 contains:

| Field | Meaning |
| --- | --- |
| `enabled` | Boolean, default true. Disabled payloads still validate. |
| `sourceAspect` | Oriented source width/height used for authoring. Renderer compile adapts the UV bind geometry to the actual input aspect. |
| `bones` | 1–24 entries with `id`, `name`, `parentId`, `x`, `y`, `endX`, `endY`. Empty parent means root. |
| `pose` | Baseline transform channels keyed by bone ID. |
| `keyframes` | Up to 2048 total bone and IK keys. Bone entries contain `boneId`, `atMs` and sparse transform channels. |
| `mesh` | 3–512 vertices and 1–1024 triangles. Each vertex has `u`, `v` and 1–4 distinct positive bone influences. |
| `poses` | Up to 64 named FK pose snapshots with `id`, `name`, `pose`. |
| `ik` | Up to 12 analytic two-link constraints and their target/mix keys. |

Bind coordinates and texture UVs use a top-left origin: X increases right, Y down.
Bind endpoints and texture UVs lie in `[0,1]`. Sampled positions can leave this
canvas and are clipped by the source texture output. Bone rotation works in the
source image's physical aspect, rather than stretching a rotation in UV space.
The rest pose preserves every original texture vertex.

Bone channels are `rotation` in degrees, `x`/`y` in the bone's local metric axes
with one unit equal to source image height,
and `scaleX`/`scaleY`. Bounds are ±36000 degrees, ±2 local translation units and
0.05–20 scale. A conservative analytic bound covers curve overshoot, composed
hierarchical scale, joints and weighted vertices before a rig commits. Extreme
deformation that may exceed ±10000 source UV units rejects explicitly.
Sparse channel curves interpolate from the baseline at authored zero to the first
key, then between keys and hold the last value. Easing names are `linear`,
`ease_in`, `ease_out`, `ease_in_out`, `step`, `hold`, `cubic_bezier`. A cubic key
includes `bezier:[x1,y1,x2,y2]` and uses the shared solve-X easing implementation.
Custom easing may overshoot between keys; sampled channels clamp to their declared
bounds, including positive scale and the IK target/mix ranges.

All keys use integer authored milliseconds. `compileForClip` / `sampleClip`
convert output-local time using the common signed `animationOffsetMs` and
`animationDurationMs`. Split, trim and range export retain that clock; speed edits
retime bone and IK keys by the exact duration ratio. Rounded time collisions merge
sparse channels, with later authored values winning the same channel.

Two-link IK entries use `id`, `rootBoneId`, `childBoneId`, `targetX`, `targetY`,
`bend` (-1 or 1), `mix` (0–1), and optional `keyframes` with `atMs`, target/mix
channels and easing. The child must be an immediate descendant whose bind head
meets the parent's bind tail. A chain whose mix can become positive and its
ancestors must have unit scale; child local translation must remain zero. A chain
whose entire mix curve is zero leaves FK squash/stretch unrestricted.
Unreachable targets clamp to actual
chain reach and report that clamp. FK remains available for squash and stretch.
Constraints cannot share their two controlled bones.

`AnimationRigEdits.apply(project, operation, args)` stages complete rig changes
inside `ProjectStore.edit` with the caller's expected revision. It checks the
selected track lock and registered still source, then compiles the result before
assigning any clip effects. Human and MCP actions use these same mutations:

- create/apply/clear/enable a rig;
- add/edit/remove bind bones;
- generate smooth capsule-distance weights or paint selected vertex influences;
- set/remove FK and IK keys;
- set baseline pose, capture/apply/remove pose-library entries;
- add/edit/remove connected two-link IK constraints.

Active rigs require a textured source. Title-only and procedural overlays redraw
after the source mesh, so those combinations reject until an explicit generated
image replaces the overlay source. Disabled rigs retain their authored settings
and remain subject to schema validation.

The default mesh is a 16×16 grid (256 vertices, 450 triangles). Automatic weights
normalize the four nearest capsule distances. Editing bind bones regenerates
weights by default; `autoWeights:false` preserves painted influence assignments.
`set_bone` with `preserveConnections:true` moves the original touching parent tail
and child heads as one joint in the same staged edit. It does not move unrelated
nearby points or the other endpoint of any bone. Monitor bind drags opt into this
behavior while preserving painted weights.
Removing a bone retains and renormalizes surviving influences. A vertex with no
surviving influence receives new distance weights. Parents with remaining children
must be reparented or removed in child-first order.

`describe(project,clipId,outputLocalMs)` returns actual UV-space sampled joints,
key/mesh counts, poses, IK targets/clamps and the converted `authoredTimeMs`.
Key controls read `AnimationRig2D.authoredPose(authoredMs)` or
`authoredPoseClip(outputLocalMs)`: a bone-ID map of the five FK channels sampled
before IK. Saving a key from these values preserves authored FK without baking
the IK contribution a second time. Clip sampling uses the same signed held clock.
`sample`, `describe` and `Frame.poseJson` retain solved geometry and pose values
for rendering and intentional pose capture.
Graph curves use `authoredChannel(boneId, channel, authoredMs)` and
`authoredIkChannel(ikId, channel, authoredMs)` for direct compiled scalar sampling.
They apply the same easing, time holds and channel clamps without mesh/JSON
allocation; unknown IDs and channel names reject explicitly. IK graph targets
remain authored target coordinates before reach clamping.
`clipAuthoredTimeMs(outputLocalMs)` exposes that same signed held conversion;
`authoredChannelClip` and `authoredIkChannelClip` combine it with scalar sampling
for native/MCP output-local graph queries. The compiled rig is reused across all
bounded query samples.
`channelBounds(kind, channel)` returns a detached minimum/maximum pair from the
same scalar renderer limits (`kind` is `bone` or `ik`).
For a value-only key drag, `authoredChannelSample(..., authoredMs, keyAtMs)` and
`authoredIkChannelSample` return immutable `ChannelSample.rawValue` and
`keyInfluence` from the exact interpolation segment. Cache these samples and
preview `clamp(rawValue + keyInfluence * valueDelta)` with the channel's declared
bounds. Clamping occurs after the contribution, including cubic overshoot.
`keyAtMs=-1` samples without a draft key; other times require a key on that exact
channel. The key's time and easing stay fixed. Time moves use the shared whole-row
transaction and rebuild the graph cache.
Sampled `Frame.ikIds` identifies the corresponding target/mix/clamp array entries;
constraints evaluate in hierarchy order, which can differ from JSON array order.
`apply_pose` with `atMs` writes FK keys at that authored point and zeroes IK mix
there, so the captured pose is visible. Without `atMs`, it updates baseline pose;
existing key curves remain authoritative. Total rig JSON is bounded to 256 KiB,
and unknown fields, duplicate IDs/times, invalid weights and hierarchy cycles fail
before a native transaction commits.
