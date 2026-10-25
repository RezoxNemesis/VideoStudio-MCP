# VideoStudio Sketch Studio 4.10.37 — Visible Agent Director

## Purpose

This is an **original Android native editing workflow** for human-visible control of Sketch Studio, not an arbitrary external Android control service or a claim that AI can animate anatomy autonomously at film quality. The owner sees ChatGPT choose the current tool, current frame, selected layer/object, zoom/pan the *actual* native canvas, toggle onion skin and grid, preview play/pause, and open the real exact-curve editing dialogs. The underlying image/cel drawing uses the existing native `sketch_edit` and `sketch_live_*` actions, which journal real accepted stroke geometry and show the actual Agent Hand.

## Native methods (all within VideoStudio)

- `sketch_stage_capabilities`: discover the limited, allowed visible actions. No source pixels, media or Gallery are touched.
- `sketch_stage_execute`: enqueue a bounded script for the currently owned Sketch Studio document; includes `sketchId`, `operationId` (stable UUID-like ID), `expectedRevision`, and 1..16 named `steps`.
- `sketch_stage_status`: exact `sketchId` + `operationId`. Returns `queued`, `running`, `applied`, `rejected`, or `expired`, a completed-step count, and the latest **actual foreground UI** proof. This status is *not* a pixel quality certification.

Use the existing VideoStudio MCP `autonomous_request` bridge; e.g. select the exact active project and set `nativeAction: "sketch_stage_execute"`, `nativeParameters` to the stage program. Do not browse Gallery or replace the private stable endpoint.

Example:

```json
{
  "sketchId": "exact-active-sketch-id",
  "operationId": "director-cut-0001",
  "expectedRevision": 9,
  "steps": [
    {"action": "tool", "tool": "curve"},
    {"action": "layer", "layerId": "background"},
    {"action": "frame", "frameIndex": 12},
    {"action": "camera", "zoom": 3, "centerX": 0.66, "centerY": 0.39, "animationMs": 320},
    {"action": "wait", "durationMs": 400},
    {"action": "shape_curve", "objectId": "editable-bezier-path"}
  ]
}
```

This is a **template**: inspect `sketch_get` for a real existing revision, layer and path ID before execution. Do not invent identifiers or treat the template as working IDs.

## Truthful live UI behavior

1. The private native service validates IDs, current revision, selected owned layers, camera range, tool names and step limits, then records a durable SQLite queue. Enqueue is **not** editing completion.
2. If Sketch Studio is not open or the human has taken over, the accepted program is retained rather than claiming phantom gestures. It expires safely after 24 hours. No future notification is guaranteed.
3. Sketch Studio in active **Watch ChatGPT** mode consumes one step at a time on the UI thread. A small HUD reports the actual selected tool, frame or camera and the current step count.
4. `SketchCanvasView` executes viewport zoom and pan via bounded ValueAnimator on the actual camera geometry, reusing pixels during movement and recomputing the cropped raster once after arrival. An interrupted gesture yields a rejected receipt.
5. Every completed step's proof records the actual frame, layer/object selection, camera and source revision, without reproducing the source artwork in the receipt.
6. A human tapping **Draw myself**, touching the canvas or Android backgrounding cancels the agent camera. No Gallery browsing, hidden root shell or UI automation of unrelated apps is added.
7. Curves are the existing artist-authored `SketchEasing` interpolation in SketchDocument. **The new touch graph uses the exact SketchEasing evaluator**, and the user or ChatGPT can adjust/save the real keyframe via existing `sketch_edit` `set_keyframe` or `set_shape_key`. Opening a dialog by stage action does not itself write a keyframe.
8. Drawing/painting remain in the real `sketch_live_begin` / `sketch_edit` / `sketch_live_end` pipeline. For visible animation, use authored cels, meaningful breakdown/in-betweens, shape deformation, layered rig components and the same revision checks; *a slideshow zoom does not count as character animation*.

## Performance improvements

- Fixed 16-step programs reduce repeated network round trips while keeping per-step visible progress and UI receipts.
- A shared one-second idle polling throttle reduces unnecessary SQLite opens; while a batch is active, the next real step is claimed immediately upon successful UI acknowledgment.
- Camera navigation transforms the existing viewport without source file decode per in-between animation frame. Final raster refresh is one bounded worker job.
- The curve graph computes one source snapshot for 72 displayed samples instead of allocating a JSONArray for each sample.
- Source projects, layer structure, paint file hashes, immutable backing media, and the private MCP identity remain untouched.

## What still needs engineering before "human-like full control" is a valid claim

**Highest priority:**
1. On-phone validation with screenshot/video evidence of `sketch_stage_execute` drawing controls, hand pointer, frame switching and curve edits, including simultaneous human takeover.
2. Reliable rendering/export continuation; prevent empty frames, stuck render progress, thermal crashes, and interrupted recovery in long books.
3. A full writable Xsheet and multitrack timeline with drag exposures, retime, onion, loop range, scrubbing, audio-waveform synchronisation and visible agent cursor. Current Xsheet is read-only.
4. Multi-layer editable rig transformations and partial-body anatomy/hand constraints, automatic inbetween interpolation **with actual frame-to-frame articulation** and pose review. Neither generated strokes nor references certify correct hands or anatomy.
5. Richer painted brush strokes, editable paint mesh and contour quality, optional color management, material layering and a node/curve graph editor with draggable control points.
6. A native creative scene command compiler that plans drawing → keys → breakdowns → inbetweens → inspect → repair → export with operation IDs, bounded retries and frame-level evidence. Its output must remain editable; external generated images cannot masquerade as hand-drawn strokes.
7. Optional native on-screen remote cursor for authorized object nodes and canvas gestures, linked directly to proven state changes instead of cosmetically animating a fake cursor.

### Acceptance criteria

- Gradle Java compilation, full native Robolectric tests, regression JavaScript contracts and existing signing identity pass on source code.
- On-device sample proves 30+ supported visible actions executed in order across multiple 16-step native scripts; every step has true foreground receipt, otherwise rejected/queued.
- A 24 FPS authored test sequence remains geometrically consistent with real cel-to-cel movement, not just whole-frame pan.
- No crashes across portrait ↔ landscape changes, Android sleep/wake, and human takeover; 99th percentile UI input latency to be measured on phone, not fabricated.
- Exact app-version upgrade preserves the existing private MCP identity and saved projects. No stable APK release overwrite from feature branch.
- Quality benchmark must compare the owner-supplied clips with *actual rendered* scene frames, not merely successful source compilation.

## Development status

Implemented on `feature/sketch-visible-agent-director-v41037-20261025`; gated by Android full unit/build workflows and on-device visual testing. Do not merge into main or claim installation before CI, pinned-signature and device checks succeed.
