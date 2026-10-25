# VideoStudio 4.10.32 — Sketch Studio Human-Visible Agent Controls

## Feature slice
Sketch Studio already supported real vector drawing, precise pen and paint strokes, true native brush/cel replay, onion skinning, object/layer transforms, shape keys, linked drawing layers, human body acting and 2D timelines. This release adds a missing **agent-owned visible workspace control plane** and interactive animation-curve editing.

- Existing `sketch_edit` continues to commit real source artwork, with revision checks and the existing true stroke-progress and live-hand replay.
- New `sketch_agent_stage` accepts **1–16 bounded visible workspace steps**, containing only `tool`, `frame`, `layer`, `object`, `viewport`, and `onion`. The user can see ChatGPT change tools, scrub frames, select objects/layers, alter onion skin, and smoothly move/zoom the camera **while the editor is visibly in Watch ChatGPT mode**. No Gallery, file import, external shell, or source modification is permitted.
- An acknowledged `sketch_agent_stage_status` distinguishes staged vs actually applied steps, with a monotonic sequence and source-revision guard. The private command cannot assert UI action completion until Sketch Studio does it. Source revision, image pixels and render status are unchanged.
- `SketchCanvasView` can follow the **actual accepted brush cursor** from the existing `SketchLivePlayer` at a throttled rate; moving the viewport never alters the drawing. The user can disable pen-camera follow or press **Draw myself** to reclaim the editor at any time.
- Native `SketchTimingCurveView` provides draggable Bézier handles on the object's transform and editable path shape-key dialogs. The graph uses `SketchEasing.sample`—the exact interpolation used for saved animation—not a separate generic CSS-like approximation. The source is only changed through ordinary `set_keyframe` / `set_shape_key` edits after Apply.
- Existing timeline, sketch art files, source roles, drawing limits, imported assets, stable MCP connection and Android package/signing remain unchanged.

## One-command example for connected private MCP

Use ordinary native `autonomous_request` with `nativeAction: sketch_agent_stage`. The device call's `nativeParameters` can be structured as:

```json
{
  "sketchId": "<exact existing sketch ID>",
  "expectedRevision": 14,
  "operationId": "shot-camera-001",
  "steps": [
    {"type":"frame","frame":12},
    {"type":"tool","tool":"curve"},
    {"type":"layer","layerId":"layer_1"},
    {"type":"viewport","x":0.72,"y":0.35,"zoom":3},
    {"type":"onion","enabled":true,"range":2,"alpha":64}
  ]
}
```

The revision, sketch ID and selected layer must come from real current Sketch Studio state; values above illustrate shape only. The native receipt says `queued_for_visible_editor` until the **open** Sketch Studio confirms each step. Request `sketch_agent_stage_status` to see count and execution state. The task expires if the phone cannot show it within 180 seconds. While Sketch Studio is closed, no claim of visible action can be made.

For actual drawing and keyframe changes, ChatGPT must continue to use `sketch_edit` and retrieve its acknowledged source revision, real operation history and saved preview. Source drawing operations do not come from the stage protocol and do not constitute fake UI painting.

## Tests

- Exact portrait and landscape viewport mapping plus stable pen-follow camera pan.
- Native pixel and drag/cancel tests for the sketch timing-curve graph.
- Stage admission, JSON and source revision validation, idempotence, busy rejection and applied-step receipts.
- Shared private MCP action admission and explicit Gallery refusal.
- Existing full Android compile, test suite, signed build continuity and public release guard.

## What remains to achieve human-like professional performance

1. **Real-time editing throughput and preview quality:** test animated pen tracking at native 24/30/60fps on the user's device; adaptive GPU/CPU budgeting, actual frame-by-frame evidence, and export/recovery reliability.
2. **True multi-axis camera work:** a full rotated/skewed 2D viewport with inverse touch mapping, trackpad-style gestures and undoable camera keys. This release changes **view-only pan/zoom**, not rendered camera motion.
3. **Drawing ergonomics:** stylus pressure/tilt calibration, transform handles on each rig joint, stabilizer presets and natural, custom brush mixing.
4. **Professional X-sheet:** multi-layer dope sheet with draggable keyframes, audio waveforms, exposures/holds, onion-skin colors and recorded action timing. A displayed frame change is not a new drawing.
5. **Controlled in-betweening:** reliable anatomy-aware interpolation of independently drawn parts with collision and motion checks. Static image zooming is not considered character animation.
6. **Creative autonomy:** user-approved style rules, short human-readable shot operations, resumable multi-stage execution, visual critique, automated fixes, and on-device progress/quality instrumentation. Results need screenshot and MP4 validation.
7. **Advanced compositing:** masks, moving mattes, simulated lighting, blend modes, mesh deformation and coupled After Effects-inspired timeline tools. These are separate modules; not all are in this patch.
8. **Landscape and tablet Sketch layouts:** resizable preview, timeline and inspector panes; the Main VideoStudio editor already has a responsive split foundation, but Sketch Studio's own dock arrangement still needs comparable work.

## Proof policy

Passing GitHub unit tests and APK signing verifies code packaging only. No user-visible claim of perfected pen motion, on-device GPU output or reference parity is justified until the updated APK runs against a real saved sketch, is observed moving/inking live, is resumed from interruption, and exports a correctly decoded MP4. The existing 4.10.31 stable-ish engineering base remains preserved. Do not publish the feature build as `videostudio-latest` without device evidence.
