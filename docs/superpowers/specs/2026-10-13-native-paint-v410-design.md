# Native paint increment for Video studio 4.1.0

Status: source-based design, not a claim of implementation or photographic acceptance. The owner has authorized autonomous implementation. The latest source photograph is visible in the conversation but its original bytes are not available to the native importer. The current manually authored native study is not an exact reproduction.

## Outcome and engineering choice

Give humans and the agent the same native pressure-sensitive painting, erasing, color sampling and clone-brush operations, with every authored stroke visible in Sketch Studio. Preserve fine color variation and texture as painted pixels instead of forcing skin, hair and fabric into a few hundred polygons. Keep original vector tools, animation timing, lazy sketch books, package identity, signer and native execution boundaries intact.

Use immutable 256 × 256 PNG tiles and a small hash-bound snapshot manifest for each paint cel. Reject two tempting alternatives: putting tens of thousands of vector marks into the current document breaches its 4 MiB/4,096-object limits, while replacing a whole bitmap after every stroke wastes memory, disk and history. A shader that reveals an imported photograph without actual brush coverage is not authored drawing. Clone painting is an explicit source-sampling technique, not learned or prompt-to-image generation.

## Existing integration facts

* `SketchDocument` currently accepts only vector/text/image kinds; the canvas is limited to even dimensions ≤ 1280 and ≤ 720 × 1280 total pixels. References can be 2048 pixels per edge. A new paint object and larger canvas require native validation, Node schema and minimum-version changes together.
* `SketchProjectStore.mutate` already atomically saves the revision, history, live event and idempotency receipt. Files cannot participate in SQLite rollback. It verifies only `image` dependencies today. Its current history keeps up to 32 entries/16 MiB and its receipt budget is 8,192 operations per sketch.
* `SketchEditorSession.submit` applies pure JSON immediately, then saves on a serial worker. Pixel preparation cannot be added to this UI path as synchronous disk work.
* `SketchLivePlayer` builds a renderer on its worker, then animates one object by fraction. The renderer currently reveals a completed `image` by opacity. Paint replay must have its own stroke branch; fading in the final snapshot would violate the live drawing requirement.
* `SketchCanvasView.frameImage` and ordinary `sketch_preview` render a full-size bitmap first. Enlarging canvas limits alone creates avoidable allocations and loses tile benefits. Cropped previews already draw into a bounded destination bitmap and supply a coordinate map.
* `SketchBookStore` supports 10,000 lazy pages, each an independent sketch. Existing native book exports are bounded to 1,200 frames per segment. This should remain: “thousands of pages” does not require thousands of pages resident in memory.
* Existing project Drive archives enumerate project-owned workspace/original/export files. They do not automatically package editable sketch DB state or all `sketch_studio` dependencies. `SketchNativeExport`/`SketchBookExport` save JSON generations but do not yet make paint dependencies self-contained.
* Vector drawing manifest import currently rejects images. It must also reject arbitrary paint snapshot pointers until a separately validated owned-asset archive route exists.

## Durable data contract

Retain the existing document schema and old objects. Add `kind: "paint"` with ordinary stable `id`, `frame`, `exposure`, `transform`, `keys`, full-canvas `bounds` and a mandatory `paint` descriptor:

```json
{"file":"<sha256>.json","sha256":"<sha256>","width":720,"height":1280,"tileSize":256}
```

Its only directory is `files/sketch_studio/<sketchId>/paint/`. Descriptor width/height must equal the document dimensions. No paths, URLs, base64 images or foreign sketch IDs are accepted. The PNG tile directory is `paint/tiles/`; journal records are `paint/strokes/`. Filenames are hash-derived and verified against file content and PNG dimensions. Use one fixed 256-pixel tile size; unused edge pixels must be transparent.

Snapshot schema `videostudio.sketch.paint.snapshot.v1`:

```json
{"schema":"videostudio.sketch.paint.snapshot.v1","width":720,"height":1280,"tileSize":256,
 "tiles":[{"x":0,"y":0,"sha256":"<tile hash>"}],
 "parentSha256":"<previous snapshot hash>","strokeSha256":"<journal record hash>"}
```

An empty cel omits both parent and stroke. Tiles are unique, sorted by `(y,x)` and only nonempty tiles appear. At most 64 tiles at a 2048 × 2048 canvas, ≤ 64 KiB manifest, ≤ 384 KiB per encoded tile. Journal records pin the exact normalized points, brush settings, source ownership/hash/mapping, clipping and before snapshot hash. Compute the journal hash first and the after snapshot hash second; the after hash belongs in the DB receipt/live event, not inside the journal (which would create a circular hash dependency). Do not put long journals into the 4 MiB document. Expose them through bounded pages; normal undo/redo changes snapshot pointers. Authored stroke settings remain available for replay/editing, unlike a pasted photograph.

Initially retain assets; do not introduce destructive garbage collection in this increment. Enforce a 512 MiB per-sketch paint asset quota and free-space headroom before publication. A later collector must mark current document, retained history, recovery drafts, live baselines/events, journal ancestry and immutable export/archive generations. A hash disappearing while any of these still references it is a data-loss bug.

## Shared authoring operations

Add operations to the existing revision-bound `sketch_edit` surface, rather than a second editing API. Node and native validate strict fields and the same work budgets. Add `add_paint_cel`, `paint_stroke`, `paint_replace_stroke` and `paint_remove_stroke`; read-only `sketch_paint_journal` and `sketch_sample_color` are separate bounded queries. `sketch_edit` stays the existing allowed native command, minimizing relay/mailbox churn. Read-only queries need ControlService dispatch, Node action enumeration and encrypted mailbox allow-list additions.

Minimal brush request:

```json
{"type":"paint_stroke","objectId":"paint_cel","strokeId":"stable-id",
 "points":[[0.2,0.3,0.7],[0.21,0.31,0.8]],
 "brush":{"mode":"paint","color":"#FFAA8877","sizePx":6,"opacity":1,
          "flow":0.4,"hardness":0.7,"spacing":0.2,"texture":"smooth","seed":123},
 "alphaLock":false}
```

Points are normalized in paint-object local coordinates, pressure 0..1, 1..2,048 per request. Size 0.5..256 native pixels; opacity/flow/hardness 0..1, spacing 0.05..1 diameter, texture `smooth` or deterministic `grain`, seed a bounded integer. Preflight at most 8,192 interpolated dabs and 64 affected tiles. Do not let a very small size/spacing imply millions of dabs. Split long finger gestures into bounded chunks sharing a gesture/group ID; pointer-up finalizes an undo group without hiding authored chunks. Idempotency uses existing operationId + complete request fingerprint, and strokeId conflicts must reject a changed stroke.

Modes: paint uses source-over; erase affects only the selected paint cel; clone samples an owned reference under the brush footprint. Clone requests include reference object ID and expected reference PNG hash. Native code freezes its reference file/hash, crop and canvas/object/layer mapping in the journal before rendering. Use the inverse reference world transform to map each covered destination pixel to oriented reference pixels. Outside-source samples are transparent. A single sampled center color is not a clone brush and will not preserve fabric/skin/hair texture.

`sketch_sample_color` accepts current document/revision, frame and normalized point, with source `composite` or an owned reference object/hash. Return #AARRGGBB plus source revision/hash/coordinate evidence. Never browse Gallery. Reference visibility stays off unless the human explicitly enables the guide.

Support optional rectangular clip and alpha lock against the cel's immutable pre-stroke alpha. Reject alpha-lock erasing, since erasing intentionally changes alpha. Full arbitrary path masks and smudge simulation are not required for this increment; do not expose inactive controls. Layer opacity, normal/multiply/screen/add composition, transforms and timing continue to work through existing render logic.

Stroke edit/removal replays the affected tail from its prior immutable snapshot, retaining the same source pins and brush engine. Bound the replay tail to 128 strokes per atomic request and report the bound before mutation. For older edits, a native replay job may prepare immutable assets with cancellation and checkpointing, then revision-check one final swap. Do not relabel a nondurable preview as saved, and do not silently alter already-rendered pixels when a reference is hidden/deleted/moved.

## Native engine and commit sequence

Introduce focused `SketchPaintStore`, `SketchPaintBrush`, `SketchPaintOps` and `SketchPaintReplay` classes. Store owns checked files/manifests/journals; brush owns deterministic interpolation and coverage; ops bridges domain mutations; replay owns presentation. All use Android Bitmap/Canvas, with the same brush engine for touch, agent, live presentation and export.

Prepare on an authoring worker. For each stroke, read the current pinned manifest, spatially index its dabs, decode and modify one affected 256 × 256 tile at a time, and write only changed immutable tiles. Clone may retain one bounded 2048-pixel working reference (≤ 16 MiB) while the stroke runs; never decode its full high-resolution original again. Prefer bounded source patches where practical. Keep tile cache ≤ 12 MiB and bitmap scratch independent of page count. Cache keys include sketch ownership, manifest/tile hashes and output scale, not just object ID.

Commit ordering:

1. Within the serialized revision lane, check existing receipt/fingerprint, expected revision, cel/layer ownership, locks, source pins, work bounds and storage headroom. A retry must return its prior result before rendering new pixels.
2. Prepare immutable tiles/journal/manifest; fsync and atomically publish hash-named files. Never overwrite a hash-named file with different bytes. A crash here may leave harmless unreferenced files.
3. In one `SketchProjectStore.mutate` transaction, recheck revision, validate all dependencies, replace the cel pointer, save history/live event/receipt and commit. A failed transaction keeps the old cel usable and produces no accepted live event.
4. Return persisted=true with revision/document hash/snapshot hash. UI can replace its transient preview only after this receipt.

Avoid holding a SQLite write transaction during all PNG encoding if possible: capture/prepare first, then final revision-checked transaction. A concurrent human edit results in conflict rather than painting over new work. Existing guarded project/reference imports have a different lock order; do not acquire a ProjectStore guard from inside a sketch write transaction. Clone source is an already-owned immutable reference, so no project guard is necessary.

## Immediate human drawing and real live agent presentation

The touch path draws a transient brush preview immediately, captures a stable sketch/page/cel/revision/generation at pointer-down, and sends the same `paint_stroke` operation to its serial writer. It must not call pure `SketchEditOps.apply` with unresolved tile work. Add a prepared-mutation worker path to `SketchEditorSession`, preserving recovery drafts plus pending operation intent; on conflict retain a recoverable copy. Switching page, activity recreation or choosing “Draw myself” invalidates stale presentation callbacks, without cancelling an already-admitted durable save.

Live event records contain the actual brush stroke and native-generated before/after snapshot pins. `SketchLivePreparation` prepares before tiles and any clone source off the UI thread. `SketchPaintReplay` maintains a private viewport-sized accumulator, adds the next bounded prefix of actual dabs on its worker, and hands an immutable viewport bitmap to the UI. The renderer uses that paint-cel override instead of the final snapshot until the stroke completes. Keep only the private accumulator, one pending publication and one adopted viewport image; release the previous adopted image after UI detachment. Zoom/pan changes invalidate and rebuild the viewport on the worker from the same pins and progress, rather than degrading permanent pixels. Only at completion does the pointer switch to the committed after snapshot. The guide and completed picture never appear as a baseline. Normal paint/clone stroke prefixes use actual dabs and shader/mask coverage; a generic vector outline or opacity fade is insufficient. Erasing is isolated to this cel with an object-level saveLayer, revealing lower objects/layers rather than erasing them.

No disk reads, PNG decoding, document scans, brush planning or thousands of dab draw calls belong to the 16 ms UI callback. A slow worker may publish fewer live presentation frames; the UI consumes the newest ordered prefix without manufacturing missing operations or blocking human tools. Measure device frame timing rather than promising a hardware-independent rate. Prepared source/tile resources have explicit ownership and are recycled after UI detachment, never concurrently with drawing. The on-screen renderer uses a bounded viewport and tiles. When idle, bounded preview caching may be used; it must not allocate a full 2048-square page then scale it down.

Raise canvas dimensions to even 256..2048 with total ≤ 4,194,304 pixels only after both ordinary previews and onion skins use destination-sized/cropped rendering. Native export may allocate one full canvas bitmap per frame (≤ 16 MiB), process frames sequentially, recycle promptly and keep existing segment/storage bounds. This differs from allocating one bitmap per page. A 10,000-page book remains lazy, and adjacent onion pages are prepared from a bounded window.

## Ownership, archive and compatibility

Extend dependency verification, page copy, recover-as-copy and immutable export preparation to paint manifests, tiles, journals and pinned clone references. Copy the reachable dependency closure into the target owner directory before publishing a recovered/copied page. Preserve source guide hashes in journal records even when the guide object is subsequently removed.

Add an explicit self-contained editable sketch archive generation: document JSON plus checked paint/reference dependency inventory and hashes, written through native linked workspace surfaces. Reuse existing bounded generation/Drive verification logic, but do not archive a live SQLite file or assume an exported MP4 preserves editable artwork. Restore stages all entries, rejects traversal/symlinks/duplicates/hash or PNG mismatches and unexpected entries, then creates a new sketch identity atomically; old work remains intact. Cloud eviction requires a verified complete generation. Large books archive per-page/segment inventories instead of loading or packaging every page in RAM.

The existing drawing-manifest importer accepts vector operations only; explicitly reject `paint` and new paint edit operations there. A future brush-operation manifest can reuse native authoring semantics, but accepting hash pointers without staged owned dependencies is unsafe.

Gate all paint kinds/operations/queries and > legacy canvas limits to 4.1.0 in `requireStudioVersion`. Mirror validation in encrypted delivery tooling; retain old capability fields and mark `nativeRasterPainting`, `cloneBrush`, `paintJournal` and `largeCanvas` only once installed. Keep `learnedReconstruction:false`. Old vector/reference projects and old API requests remain valid. No database destructive migration, app key rotation, signer change or Gallery permission.

## Focused acceptance tests

Use Robolectric native graphics, then the signed APK and actual native receipts/output. Tests are pixel/behavior assertions, not implementation mirrors:

1. Paint and pressure produce distinct measured pixels; hardness/flow/opacity behave as requested; tile-seam crossings leave continuous coverage and no marks outside the footprint.
2. Seeded grain is stable for replay and changes with seed. Clone preserves a high-frequency synthetic texture only inside real brush coverage, with nontrivial crop/transform/EXIF source mapping; no guide visible at baseline.
3. Erase reveals lower paint/vector layers and never damages them. Alpha lock and rectangle clip prevent marks outside the pre-stroke mask/clip.
4. Undo/redo and process recreation preserve exact snapshot hashes/pixels; same operationId retry produces no additional stroke, changed request rejects.
5. Revision conflict, disk-full, missing/corrupted tile/source and interruption before/after file publication leave the old cel/history/receipt consistent. No accepted live event on failed commit.
6. Replay starts with the before snapshot, shows multiple increasing measured stroke prefixes, ends with the exact durable after pixels and respects manual takeover/page switching/lifecycle cancellation.
7. Crop preview and full native export show the same tile pixels/coordinate mapping; huge canvas preview allocations are bounded to the requested viewport. Tile/reference resources close without use-after-recycle.
8. Copy/recover/archive/restore carries all paint and clone-source dependencies; deleted guides do not break old authored strokes or archives. Traversal/hash mismatch/unowned source is rejected before DB mutation.
9. A 10,000-page book creates no 10,000 bitmaps or documents; a bounded native export preserves page order/exposures. Old vector, lettering, reference, live and book regression suites remain green.
10. Node schemas match native work and ownership bounds; 4.0.x devices reject paint before command submission, while old operations retain compatibility.

Real photographic acceptance is separate: import the original supplied photograph through VideoStudio, author actual paint/clone strokes, inspect full output and detail crops (hair, skin, blouse lacing, sari fabric, wall lettering), and send the real native rendered canvas screenshot. Unit tests or a source-sampled brush alone cannot certify “exactly like the original.” A native canvas PNG must be labelled as canvas output, not a screenshot of the entire Android screen.
