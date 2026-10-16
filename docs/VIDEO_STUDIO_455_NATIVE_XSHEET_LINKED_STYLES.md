# VideoStudio 4.5.5 — Native Xsheet + linked palette styles

This is the first actual engineering milestone from [OpenToonz/Grease Pencil ADR](ADR_2D_OPEN_TOONZ_GREASE_PENCIL_PIPELINE.md), based on 4.5.4. It preserves all existing projects, sketch schema identity, native 3D/2D renderers and app signing. The previous APK remains unchanged until this branch passes CI.

## Phone operation

### Multi-column Xsheet
In **Sketch Studio → More → Frames / Cels → Xsheet (read-only)**:
- Rows are source frames (at 24/30/60fps according to the sketch); columns are existing named layers/levels. Four levels and 12 frames show per mobile screen. Buttons page 12 frames or four layers.
- A **●** cell begins an actual drawing, **=** holds the same drawing, **−** means no drawing (blank). **\*** indicates an authored layer/object transform key at that frame, since a held object can move through affine keys without changing its cel ID.
- Tap any row/cell to move the native editor playhead and corresponding prepared layer. A stale revision cannot retarget a different sketch.
- The Xsheet is a **read-only projection** of the exact existing `frame` and `exposure` intervals. It does not create fake artwork, duplicate frames, or copy image pixels; it uses the exact `SketchFrameRenderer.exposed` predicate.
- Agent/native tooling can query `sketch_xsheet` with `sketchId`, optional exact `expectedRevision`, `frameOffset` and `frameLimit` (1–48 frames), returning columns, drawing-ID dictionaries, hold/blank/drawing flags, transform-key markers and `nextFrameOffset`. Queries remain under 500 KiB.

**This is intentionally not yet a writable professional Xsheet.** A later version must design atomic level/cel matrix edits, exposure conflicts, audio tracks and undo/redo, while preserving existing frame-shift behavior.

### Shared named color styles
In **Sketch Studio → Brush / Text → Linked palette styles** (also linked from **Colours**):
- **Create/update named style**: add a stable `styleId`, display name and `#AARRGGBB` color. Changing the color updates **all linked** flat-color vector or text drawings, including alternate frames/layers, without rewriting geometry.
- **Apply style to selected drawing**: bind a single vector shape/text drawing to the selected style. The current linked color is cached as a literal `color` fallback.
- **Link all matching unlinked colors**: supply a literal original pigment and a chosen style. All unlinked vector/text objects across *unlocked* levels that match the exact ARGB pigment become linked. If a locked matching level exists, the batch aborts with no partial changes.
- **Unlink selected drawing**: bakes the **current** shared pigment into the object's original literal color; subsequent changes to the style no longer affect it.
- **Delete unused style**: rejects deletion of a referenced style; no silent orphan references.
- Directly recoloring a linked object through the existing `update_object` action unlinks it explicitly so the new literal pigment is visible; there is no invisible shared override.

The palette is an optional `paletteStyles` array at the root of existing `videostudio.sketch.v1`. Each element is `{id,name,color}`; maximum 128 styles. Objects may optionally contain `styleId` while retaining their required literal `color`. All other existing source schemas work with zero migration and identical rendering behavior. Native `SketchFrameRenderer` indexes styles once when loading/updating a drawing, then resolves RGBA at render time.

**Limit:** Raster **paint** and imported photo/image pixels, erasers, gradients and existing image references cannot be globally recolored via this field. Linking them is explicitly rejected. Neither Xsheet nor named palette alone generates new poses or cinematic artwork.

## Native API and architecture

- `SketchXsheetProjection.project`: bounded, deterministic, read-only layer-column dictionary with unique object-set drawing IDs; page boundaries retain correct HOLD/BLANK status. Hidden levels remain inspection columns without becoming visible.
- `SketchEditOps.execute(..., "sketch_xsheet", ...)`: same owner and revision admission as `sketch_get`. No workspace wake signal on read-only inspection.
- `SketchPaletteStyles`: validates style IDs, names, exact AARRGGBB pigments, duplicate references, object-kind restrictions and stale linkage; all palette mutations occur via existing `sketch_edit` operation batches and the source document's original atomic/revision-safe `SketchProjectStore`.
- `SketchDocument.validate`: optional fields only. Old drawings missing the palette load as before and keep unchanged geometry/hash.
- `SketchFrameRenderer`: continues native Android Canvas/Skia drawing, now resolving linked flat-color styles with an immutable per-renderer style map. All other types use unchanged rendering.
- Android app identity `com.rezoxnemesis.videostudio.next`, package version 4.5.5 / code 455, original signing key, device-owned MCP owner identity and Gallery access boundaries remain unchanged.

## Required evidence

1. 20-level × 240-frame read-only Xsheet test compares every cell to the real source object `SketchFrameRenderer.exposed` intervals. Tests cover held exposures across page boundaries, blank intervals, hidden levels, transform-key markers, exact source immutability, invalid pages and cell counts.
2. Linked palette tests verify actual native Canvas ARGB pixels (including multiple exposed frames), 500 linked cels recolored from **one** stable palette entry, unchanged unrelated colors, preserved fallback, direct-color opt-out, safe unlink/delete and atomic rejection of locked-layer/unknown styles.
3. Native facade test checks read-only queries do not mutate persisted documents and that style edits use version-guarded native `sketch_edit` commits.
4. Full Android unit suite, signing/certificate and package continuity, native 2D animation film proof and prior 4.5.4 audio/mouth regressions must all stay green.

**Still required before production:** test Xsheet UI on the owner's Realme Android device for touch hit-targets and fast scrolling, test shared palette editing with dense real character artwork and actual exported MP4 frames, measure frame-rate/memory, then gain owner acceptance. No claim of phone-tested results from desktop Robolectric CI. No OpenToonz/Blender code has been embedded, and optional external 2.5D rendering remains a later isolated milestone.

See [seven-system acceptance issue #82](https://github.com/RezoxNemesis/VideoStudio-MCP/issues/82) and [architecture ADR](ADR_2D_OPEN_TOONZ_GREASE_PENCIL_PIPELINE.md).
