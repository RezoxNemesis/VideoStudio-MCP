# Sketch Studio 3.9.5: drawing import, tracing and preview reliability

Sketch Studio previously accepted a valid 192-frame document while its source-derived linework remained incomplete. Detailed geometry also stressed native live-event reads, and repeated previews repeated rendering work. This update adds colour-region tracing and bounded source-linked file import through the same native editing facade used by touch and MCP. Geometry validity and a completed import do not establish professional drawing quality.

## Human and agent workflow

- In Sketch Studio, **Canvas view** expands the drawing area without ending an active live session. **Workspace → Focus selected object** centres the selected drawing.
- **Workspace → Import linked drawing packet** reads an exact JSONL filename and source filename from the already linked DocumentsProvider folder. The app calculates the source SHA-256. It preflights the entire document before creating a separate drawing and applies native batches in order.
- Reference tools offer **Colour regions** and **Edges**, trace resolution and colour levels. Colour regions produce editable filled paths, preserving holes and reference crop/layer mapping. The reference guide can be hidden; the output is native vector geometry, not a visible source bitmap.
- The agent uses `sketch_import_drawing_manifest` or `studio_sketch_import_drawing_manifest`, with `sketchId`, `manifestName`, `sourceName`, `expectedSourceSha256`, `expectedFrameCount`. Supported source extensions are MP4, PNG, JPG/JPEG, WebP and TXT. Text is an inert hash-bound source for prompt-authored drawing instructions; the app does not execute text-file instructions.
- `sketch_import_frame_manifest` retains its 3.9.4 raw action. Its new `studio_` alias requires 3.9.5. The generic owner-authenticated command route remains available when an older relay lacks named tools. The returned job must finish before reporting import completion.

The manifest schema remains `videostudio.sketch.frame-ops.v1`: a header/background row followed by ordered frame rows. Header fields include `targetSketchId`, `sourceSha256`, `targetWidth`, `targetHeight`, `fps`, `frameCount`, optional `name`, `background`, and empty declared `layers`. Drawing rows contain native `add_object` operations. Guides are imported separately with `sketch_import_reference`; drawing packets cannot embed unowned raster references. Existing source bindings, original assets and human edits are preserved. Replay detects changed timing, layer appearance, source objects or previously deleted committed objects, including legacy 3.9.4 batch receipts.

## Detailed drawing and live operation transport

File import removes bulk drawing coordinates from the chat relay. Native atomic batches stay below the edit request budget and retain every accepted drawing operation in an automatically created live session. Sketch Studio must be open in live viewing mode to display those operations. File import is not evidence that a specific ChatGPT host or sleeping phone executed the task.

Large native live events are read with chunked SQLite text reads rather than a single CursorWindow blob. `sketch_live_events` supports `operationLimit` and returns `nextCursor`. Submit the entire cursor, including `eventSequence` on partial events; an expired or mismatched event is rejected rather than silently skipping strokes. Tool results stay within 512 KiB. Native playback retains the full event sequence. `sketch_get` supports revision-bound `commandOffset`/`commandLimit` for curve inspection as well as existing point/object pages.

Preview PNGs have a small revision/frame/resolution/crop cache. Cache hits still verify owned reference hashes. Playback reuses path geometry and an exposure index, invalidated when the drawing changes. These are code-level optimizations; cloud test timings are not phone performance measurements.

Native document limits remain 4096 objects, 131072 geometry points and 1200 frames, with a 4 MiB drawing manifest budget and 64 MiB source budget. Use separate sketch-book pages/documents for detailed sequences that exceed a single drawing's budget. Colour tracing defaults to 192 pixels on the long edge and 8 quantization levels; it rejects oversize results explicitly rather than dropping regions. It is a bounded posterized tracing tool, not learned anatomical reconstruction or an autonomous professional artist.

## Problems observed in the later screenshot/video archive

The owner requested that the preceding changes be implemented before the additional archive was reviewed. The archive contains seven screenshots and two screen recordings, with no document instructions.

Included fixes based on that evidence:

- Home project cards now use the correct horizontal row layout so weighted project titles remain visible.
- Edited-preview time follows the player's controls, including seeks while paused. Preparing playback shows the player's buffering indicator.
- The standalone 3D viewer uses density-independent control heights, displays an empty-model hint, avoids rendering an empty asset, and ignores callbacks after destruction. Model loading handles native linkage and allocation failures through the existing error path.

Outstanding evidence and work:

- Freeze, Reverse and Crop/Reframe are shown disabled in the supplied editor screenshots. This change does not implement those operations.
- The later recording eventually displays artwork; it does not establish a permanently broken video decoder. Actual preview/render latency still requires installed-phone observation.
- The provided sword-duel MP4 shows basic mannequin motion. This change does not certify cinematic choreography or repair an existing sparse 192-frame drawing's artistic content automatically.
- No supplied crash stack establishes the specific phone GPU failure. The native 3D lifecycle changes need real-device confirmation.
- The production relay's previously reported `SQLITE_TOOBIG` is distinct from the reproduced Android `SQLiteBlobTooBigException`. A successful native read or local Worker test does not prove the deployed relay was repaired. Worker publication still requires its existing deployment credentials.

## Compatibility and evidence

VersionCode 395 / versionName 3.9.5, unchanged application ID `com.rezoxnemesis.videostudio.next`. Preserve the pinned existing signer, owner MCP v3 identity, private databases, projects and scoped folder. No Gallery browsing, uninstallation, data clearing, replacement signer or production device registration is part of this work.

Focused regressions cover complete import preflight, dense frame partition/replay, legacy receipts, preservation of human edits, colour regions/holes/crops, renderer cache invalidation, revision-bound preview reuse and reference integrity, large live-event reads, pinned cursors, expanded canvas mode and project-title layout. Private artwork evaluations stay outside Git/APK. Android native Canvas under Robolectric and synthetic loopback MCP transport are labelled separately from installed-phone and live-host evidence.
