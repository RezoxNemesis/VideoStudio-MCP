# Sketch Studio 3.9.1

The phone and ChatGPT author the same stored drawing geometry. A reference image is optional. Original portraits, figures, landscapes and invented scenes can start with an empty canvas and a text prompt; ChatGPT plans the artwork and submits measured geometry through native tools. These tools do not themselves provide a trained text-to-image model or guarantee photographic likeness.

## Human workspace

Open Sketch Studio and choose New or Sketch books. Ink, pencil, marker, airbrush, charcoal and calligraphy brushes use actual native pressure/coverage rendering. Brush controls include pixel width, full-spectrum HSV or exact ARGB colour, opacity, hardness, nib angle and stabilisation. Pen, eraser, line, shape, polygon, quadratic curve pen and curve-node editing support original artwork. Pinch zoom reaches 12×; two fingers pan. Workspace offers grid and symmetry guides, eyedropper and coloured onion skins.

Layers have opacity, locking, order and normal/multiply/screen/add blending. Object and layer transforms have frame keys. Agent-authored M/L/Q/C/Z paths and gradient fills use the same preview/export renderer; phone curve nodes can adjust their control points. References are immutable owned copies from VideoStudio projects, fitted without stretching, with normalized crops, opacity and editable Sobel edge tracing. Gallery enumeration is absent.

Frame strips are virtualized. Regular documents have atomic duplicate/clear/insert/delete cel operations. Sketch books have 1–10000 independent, lazily allocated pages, page duplication, exposure holds, playback and 24/30/60 FPS. A native book export freezes source pages and streams raster capture one page/frame at a time into a separate output project and the existing Media3 encoder. One phone export segment holds at most 1200 actual frames; books with more frames use explicit page ranges. Unopened pages are blank. Native encoding still takes processing time.

## ChatGPT prompt-first and live workflow

Use app_status first. Native 3.9.1 is required. The existing stable endpoint and owner identity are unchanged. Read `sketch_capabilities` for the complete workflow and exact supported operations.

1. Interpret the prompt into an original composition, anatomy/pose, face features, background, palette and lighting. Create an empty sketch or book. A target image is not required.
2. Open the owned book page if applicable, then call `sketch_live_begin` before drawing. Supply stable operationId, sketchId and expectedRevision. The default presentation is 120ms per operation; 80–5000ms is explicit. Keep the returned sessionId and current revision.
3. Use `sketch_edit` for actual pressure strokes, paths, fills and layers. Give each feature a stable object ID. `append_points` grows an existing stroke incrementally; `update_object` changes its precise geometry or style. Keep relay batches small (typically 512 points or fewer per stroke step) for older deployed connectors.
4. The open phone workspace displays every accepted operation and the actual leading stroke/curve geometry in order. It does not coalesce rapid batches into a final-image refresh. Complete sessions are stored in order across book pages. Unplayed strokes are protected from pruning; storage failure rejects a new command before acceptance.
5. Inspect `sketch_preview`, refine coordinates, and call `sketch_live_end` with the exact final revision. Close one page before starting the next. The phone follows page sessions in order. In 3.9.2, Draw myself pauses live presentation and adopts the current saved artwork before accepting touch edits. Play and other action buttons can take over directly. Watch ChatGPT resumes ordered drawing steps. Accepted events remain stored; human editing is protected while an older recorded state is being presented.
6. Export through `sketch_render` or `sketch_book_render`. Native MP4 completion is distinct from accepted commands, captured PNGs or a queued job.

If new named tools are absent on an older deployed Worker, use the existing `app_execute` with `action` set to `studio_` plus the canonical name and flat `parameters`. Example:

```json
{"action":"studio_sketch_live_begin","parameters":{"sketchId":"owned_sketch","operationId":"drawing_session_01","expectedRevision":1,"strokeDurationMs":120}}
```

These aliases use the existing generic tool catalog, but the cloud relay still needs the chunked command/device storage implementation for reliable large or accumulated payloads. Installing an APK does not update the Worker. A skipped deployment is source validation only, not an activated relay repair. Retry an identical sketch_edit operationId/payload after uncertain delivery; its receipt prevents duplicate strokes or duplicate live events. Normal source revision conflicts return explicit errors. Conflicting human drafts are retained and restored as separate sketches before the handled draft is archived. No routine approval is required in existing full-autonomous mode.

## Responsiveness and recovery

Touch presentation is immediate in memory. SQLite snapshot/history/receipt writes run on a separate serial writer, outside the render scheduler. Frame caches prevent redrawing all layers/onion frames on every touch move. Drawing and background export have separate execution lanes. All sketch native commands dispatch off the main thread. Success includes an explicit ok flag; memory/native library errors return terminal failure instead of leaving an agent waiting.

VideoStudio imposes no custom thermal waiting queue. Android still controls hardware throttling and shutdown. Ordinary editing does not acquire the GPU render lane. Background GPU/encoder jobs remain bounded, cancellable and checkpointed, and offline native commands remain durable; removing these records would lose work and would not make hardware inference or encoding instantaneous.

3D startup loads Filament, gltfio and utility JNI libraries before model construction. Initialization/frame failures show a recoverable studio screen and preserve project data. Real GPU behaviour and the user's reported crash still need verification on the actual phone.

## Verification limits

Robolectric native Canvas, SQLite, layout/gesture, live-journal, timing and PNG preparation checks exercise actual shared code. The 1000-frame test writes all 1000 PNGs with rational clocks and bounded page loading. It is not a phone MediaCodec encode, portrait-quality acceptance, live ChatGPT/device demonstration or thermal measurement. APK signer/package/version checks establish update compatibility, not successful installation.
