# VideoStudio Sketch Studio 4.10.40 — Human-visible native replay deck

## Purpose

Sketch Studio already offers private ChatGPT tool/frame/layer/camera/curve navigation (4.10.37),
editable native cel exposure (4.10.38), and exact source Bézier node coordinates (4.10.39
development). This upgrade fixes a separate gap: the owner could see a pen briefly draw,
but could not **pause its actual saved stroke**, slow it down, or advance one real accepted
operation at a time. A default 80ms native stroke journal made serious drawing difficult to inspect.

## Original native features

- **Watch HUD in Sketch Studio:** Shows the actual source frame, current accepted stroke
  percentage and operation label. Buttons pause/resume, next saved stroke and speed.
- **Human-visible replay speed:** 0.1x, 0.25x, 0.5x, 1x, 2x, 4x, 8x.
  New UI sessions default to 0.25x to expose real drawing positions while retaining
  a fast 4x/8x catch-up mode.
- **Deterministic rate-aware clock:** Pausing freezes the currently published stroke prefix
  and pen cursor. Changing rate neither restarts a stroke nor jumps it ahead.
- **Actual source pixels only:** Canvas presentation replays native persisted vector/paint
  operations from SketchLivePlayer and SketchProjectStore; it never invents pen positions,
  copies a Gallery image, creates fake in-between cels, or rewrites source timing.
- **Next operation:** Advances exactly one accepted original operation, then pauses
  after it. A step receipt acknowledges that the viewer started the operation, not that
  all asynchronous stroke preparation has completed.
- **MCP command channel:** \`sketch_live_view_capabilities\`,
  \`sketch_live_view_control\`, and \`sketch_live_view_status\` are admitted by the
  existing private native Action whitelist and background-work policy.
  The only allowed control intents are \`pause\`, \`resume\`, \`next_operation\`, and
  \`speed\`. No arbitrary Android clicks or access to Gallery/storage APIs.
- **Foreground proof:** Enqueue responds \`queued/foregroundApplied=false\`.
  Only the real resumed SketchStudioActivity, playing exactly the pinned session,
  can settle a command as \`applied\`. Failed/expired/uncertain steps are never falsely
  advertised as successful. SQLite keeps pending intents while Android sleeps.

## Existing authoring remains unchanged

The same native \`sketch_edit\` source-revision transaction still owns geometry changes;
the independent stage queue still owns visible tool/frame/layer/Xsheet/curve controls.
This replay upgrade changes neither the saved drawing revision nor the actual FPS,
cel exposure, brush/paint bytes, motion interpolation, project export or original signing identity.

### Example native control request via the existing private MCP

Command: \`sketch_live_view_control\` (or \`studio_sketch_live_view_control\`)

- \`sketchId\`: exact existing sketch ID
- \`sessionId\`: exact saved live session ID
- \`operationId\`: new stable unique command ID
- \`command\`: \`pause\`, \`resume\`, \`next_operation\`, or \`speed\`
- \`speed\`: required only when \`command = speed\` (numeric preset 0.1..8)

A separate \`sketch_live_view_status\` call with sketchId,sessionId,operationId confirms
actual foreground effects, or reports a precise queued/expired/uncertain outcome.
The playback controls never themselves draw, retime, animate or render source media.

## Engineering tests

- Exact fractional clock, pause/resume continuity, speed changes, supported presets.
- Genuine two-stroke saved-journal player test: pause before painting, advance one
  saved operation, pause again, resume and confirm the second; original sketch
  revision must be unchanged.
- Native SQLite/relay test: pending is not applied, source-session scoping, command
  collision, invalid speed rejection, return-only-after-foreground ACK and
  safe release on editor navigation.
- Closed MCP action inventory and existing full Android test suite.

## Work still necessary for full human-like agent control

1. **Direct drawing interaction** across multi-object drags, real pressure/tilt pen
   semantics and precise eraser passes instead of relying solely on request JSON.
2. **Editable curve/tween and acting paths** with multi-selection, IK/rigging and
   visible per-handle trajectories, temporal easing and frame-accurate interpolation QA.
3. **Multiple-layer Xsheet and animatic** editing with source-bound onion-skin,
   frame/audio sync, exposure ranges, long-duration memory and cross-shot continuity.
4. **True device-screen interaction** with actual verified pointers, drag gestures,
   selected tool/highlight and immediate preview correspondence; no synthetic
   mock screen events or claim of full app-wide computer control.
5. **Artistic QA:** compare anatomy, identity, source master, pose consistency,
   overlap/collision, lighting, full colour and real MP4 results against references.
6. **Android reliability:** safe live overlay resume after process death, GPU
   resource bounds, frame-memory limits, failed render recovery and checkpointed export.

## Status and release gate

The branch targets VideoStudio 4.10.40/versionCode 543 and a draft stacked PR.
Do not merge, claim installation, or publish a stable APK unless the Android
build, native tests, signing continuity and actual user-device stroke
preview/MP4 QA all pass. The original 4.10.38 drawing and all files remain
unchanged while the feature branch is being validated.
