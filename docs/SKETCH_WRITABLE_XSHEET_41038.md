# Sketch Studio 4.10.38 — Writable Xsheet and human-visible agent timeline

## Purpose
The user and ChatGPT edit the same actual source cels in Android Sketch Studio. Stage
navigation is a real foreground UI action, while saving an exposure is a separate,
revision-checked original drawing transaction. Nothing copies pixels from the Gallery.

## Native authoring
- Tap any cell to select the real frame and layer.
- Long-press a source drawing-start cell to author a new exposure. On Ones, On Twos,
  On Threes and exact frame count are available; blank and held cells are not
  misrepresented as new drawings.
- The 12-row/4-layer Xsheet displays actual renderer drawing/hold/blank intervals,
  source object counts and next cel boundary; pages remain virtualized.
- The same optimistic SketchEditorSession submits UI changes to SketchProjectStore,
  journal, Undo and native revision guards. UI labels it queued, not exported.
- Multi-mark hold editing uses a single source-preserving set_exposure_cel operation.
  It can retime up to 256 original marks from one named level and source frame.
- Preflight refuses locked levels, unknown fields and IDs, overlap with the next
  source drawing, and shortened exposures that would conceal transform/shape keys,
  articulated human/hand poses, or source paint-mesh animation.
- Protected layered-drawing stages, reference guides, immutable paint files and
  master-linked appearance retain the original app's safety policy.

## Exact MCP command
Use existing authenticated nativeAction sketch_edit with nativeParameters:
{
  "sketchId": "EXACT_OWNED_SKETCH_ID",
  "operationId": "UNIQUE_STABLE_OPERATION_ID",
  "expectedRevision": 12,
  "operations": [
    {"type":"set_exposure_cel","layerId":"EXACT_OWNED_LEVEL",
     "frameIndex":18,"exposureFrames":3}
  ]
}
IDs and revision above are placeholders; inspect the actual document before editing.
The native database is responsible for idempotent receipts and refusing stale edits.

For real on-screen navigation, sketch_stage_execute supports a final step:
{"action":"xsheet","frameIndex":18,"layerId":"EXACT_OWNED_LEVEL"}.
The foreground Sketch Studio selects the frame/layer and opens the Xsheet.
sketch_stage_status reports queued/applied/rejected, never fake drawing pixels.
The xsheet step does not write any artwork. Use sketch_edit separately.

A paged read-only sketch_xsheet action is now also reachable through the private
native MCP. Its schema is videostudio.sketch-xsheet.v1; the read is not a write.

## Automated verification
- Android target: 4.10.38 / versionCode 541.
- Source branch: feature/sketch-writable-xsheet-v41038-20261025.
- Tests cover actual persisted sketch_edit, duplicate request, stale revision,
  Undo, renderer parity, original object geometry and source preservation.
- UI test long-presses a drawing cell, edits the hold, then checks reopened revision.
- Visible-stage tests refuse foreign/invisible layers, bad frame indexes and
  arbitrary URI parameters. No Gallery or shell escape was added.
- Existing Android app identity, stable MCP and development signing key unchanged.
- Feature branches may not publish the stable latest release.

## Unfinished professional features
1. Timeline drag exposure handles, ripple/slip edits, multi-layer selections,
   cel cut/paste, audio waveforms and exact loop/shot ranges.
2. Human-visible agent dragging actual path knots and curve handles and selecting
   paint mesh control nodes, not just opening a dialog.
3. Real pen pressure, tilt, speed, natural outline/ink texture and responsive
   full-quality brushes; retargetable rigs and physically grounded poses.
4. Real cel-to-cel acting and anatomy checks with clean in-betweens, not slideshow
   zooms or auto-effects that misrepresent character animation.
5. Disk-backed resumable render checkpoints, thermal-safe processing, saved frame
   verification, MP4 integrity checks and Android sleep/resume recovery.
6. Owner-phone proof of active-agent cursor, takeover, preview/export matching,
   nonempty frames and side-by-side visual quality against the supplied references.

Successful CI alone is not full After Effects compatibility or artist-level
cinematic quality. Do not merge or install the new candidate before device QA.
