# VideoStudio Native Motion Graphics — Phase 2 Shape Compositor

**Target candidate:** VideoStudio 4.10.27 (versionCode 530)  
**Stacked PR:** #147, based on Kinetic Director #146  
**Status:** source implementation and CI; no phone-render parity claim.

## Problem this solves

Reference videos 1000072304, 1000072305, 1000072307, 1000072313 and 1000072309 show geometrically animated backgrounds, moving text, stylized grid/ring/line graphic elements, split-screen choreography and precise timing. VideoStudio 4.10.25 previously provided image/title motion but no reusable original vector shape lanes.

## Native implementation

- New `StudioLayer.SHAPE` type: persisted typed geometry with no source URI, no imported file and no Gallery access.
- Six real Canvas-drawn geometric forms: `rect`, `ellipse`, `ring`, `diamond`, `line`, `grid`. The grid is a **2D converging-perspective design**, not physical 3D perspective projection.
- Shape bitmap rasterized **once per Media3 shader generation** into a 640×640 ARGB_8888 bitmap, and reused throughout the shot. Nominal CPU bitmap cost is 1,638,400 bytes per shape, before possible GPU upload/internal surfaces.
- Original pixels enter `StudioCompositionOverlayEffect`, composited into the same preview/export scene as existing TITLE and IMAGE lanes. Layer order is preserved, so accents are behind typography.
- Up to 12 cached shapes within VideoStudio's existing maximum of 32 studio layers. The maximum decoded video overlay count stays 2, decoded image overlay count stays 8. No migration of legacy layer encoding.
- Exact authored 64-key motion curves provide per-frame position, scale, rotation and alpha changes, with source-independent composition-global timing.
- Android editor offers + Animated Shape / Perspective Grid, shape geometry, a motion selector (drift/pulse/spin/slide/hold), and numeric placement/timing in Inspector. Saves go through existing project revision guards and Undo.
- Kinetic Director can optionally add up to four original shapes in one action alongside animated title cues. Manual BPM, measured audio onsets and explicitly authored beat markers retain distinct provenance.

## Claims not supported yet

This slice **does not** offer Adobe's proprietary After Effects engine, Adobe .aep project import, editable bezier paths, per-glyph deformation, mask tracking, rotoscoping, 3D camera and lights, actual corner pin, temporal motion blur, shader glow, auto speech transcription, automatic lyrics alignment, zero-frame export guarantees or artist-certified parity.

## Acceptance gates

1. Android full unit suite and `assembleDebug` must succeed with the project's original pinned signing key.
2. A shape keyframe's start/middle/end preview pixel and native H.264 output must match on the same device.
3. Reopen a source project after adding/deleting and undoing shape lanes; the imported song/video/image must remain bit-identical.
4. Measure 1, 4, 8 and 12 simultaneously active shape layers at 720p/1080p for frame time, GPU memory, thermal load and first-frame error count.
5. `Test subject 2` edited-preview error must not be hidden by showing unrelated media. Diagnosed root cause remains a separate P0.
6. Compare user references against rendered output, not against source code or unit tests, and keep PR draft until device evidence exists.

## Safe staged deployment

The 4.10.26 Kinetic Director APK from the successful prior branch is **not overwritten**. This phase is isolated in its own stacked branch and draft PR. The stable `videostudio-latest` release can only be updated from `main`. The newly built 4.10.27 APK is not for release until signature, tests and device frames pass.
