# VideoStudio Sketch Studio 4.10.45 – Visibly Executed Bézier Timing Edits

**Source branch:** `feature/sketch-agent-timing-curve-v41045-20261025`  
**Stack:** Sketch 4.10.43 visibly saved native object/path gestures + the previously unverified 4.10.44 frame-ruler scrub.  
**Android code:** versionName 4.10.45, versionCode 548. ApplicationId, signing and stable MCP credentials remain unchanged.

## Why

The previous visible agent could select tools, change brushes, move around the camera, scrub timeline frames, open curve inspectors, visibly drag an authored object/geometry Bézier node, and play accepted strokes. It could **not** literally drag a *timing Bézier handle* on the original saved animation curve. Pretending to do so from a text-only response would be inaccurate.

## Native implementation

One appended `sketch_stage_execute` step uses action `curve_handle_drag` with:
- `mode`: `transform` or `shape`;
- exactly one `objectId` or `layerId` (shape requires objectId and an original path);
- `frameIndex` of an **already saved Bézier key**; 
- `handleIndex` 0 or 1; 
- normalized `x,y` control point values, each in [0,1];
- optional `durationMs`, 180..1200.

The action must be the last stage step, because it changes the source revision. The original key must be inside an unlocked visible owned Sketch layer and be exposed at that frame. ChatGPT cannot invent an absent author stroke, replace linked master geometry, use Gallery, call shell commands, or alter unselected animation keys.

The original SketchStudioActivity presents an actual on-screen normalized timing curve using the production `SketchEasing.sample` implementation, moving the selected control point and drawing the native ChatGPT agent hand. At its final progress sample, it verifies the UI view truly rendered and reached the requested point. Only then does it save one existing `set_keyframe` or `set_shape_key` through `SketchEditOps.execute`, using exact source revision and deterministic edit ID. The original pose transform or path commands are unchanged. It checks the saved DB's curve against the visible target, adopts the new foreground project revision, then acknowledges the stage receipt.

Cancellation, lifecycle changes, stale source revision, unrendered graph, locked/invisible layer, master-linked art, invalid precision or failed persistence **must not result in false applied status**. It does not claim the edited animation MP4 has been exported or that every frame meets cinematic quality.

## Live human collaboration

The user can still draw, drag nodes, select Xsheet frames, edit normal curves and control the native timeline. The agent's temporary cursor is a non-exporting overlay; the updated authored curve remains genuinely editable through the same Sketch editor. The stage journal survives app restarts, but Android display steps only execute while Sketch Studio is open, not during sleep.

## Proof gates

- Robolectric original document safety, exact source keys, shape-key topology, layer key coverage, invalid input, terminal stage-only sequencing, original graph pixel drawing, and truthful stage capability assertions.
- Full Android unit test suite and pinned-signed debug APK via CI.
- On-device live screen visibility, interrupted-app recovery and exact project export after installation.

**Remaining major unimplemented quality gates:** real on-device screenshot/video proof, frame-varying object rigging from complicated reference artworks, multi-level curve graph editing, per-stroke pressure graphs, fully human-grade automated cleanup/acting, background rendering robustness and exact user-reference fidelity. Do not call these complete based on tests alone.