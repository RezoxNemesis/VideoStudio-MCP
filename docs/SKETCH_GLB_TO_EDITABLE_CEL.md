# VideoStudio Sketch Studio: real GLB pose to editable 2D cel

**Status: source changes + native QA only. Not an installed update or released APK.**

## What already existed

- `FilamentGlbActivity`: real on-device glTF 2.0/GLB geometry, camera orbit,
  named camera angles, skinned animation clip selection, and timeline scrubbing.
- `SketchStudioActivity`, `SketchEditOps`: real native vector layers, frames/cels,
  revision-safe operations, original reference copying, and visual playback.
- `SketchReferenceTrace` / `SketchColourRegions`: edge strokes and filled
  region vectors derived from an explicit owner reference.

**Gap:** GLB viewport poses were not transferable to a precise Sketch Studio
frame as ordinary editable native paths. Simply inserting a frame screenshot
would be a flattened reference, not editable hand-drawn geometry.

## Proposed authoring workflow

1. Open an existing Sketch Studio drawing and set the target frame.
2. Choose **File → 3D pose → editable cel** or **More → 3D pose → editable cel**.
3. Choose an owned VideoStudio project with a GLB (or the active project, to import
   a GLB through Android's explicit document picker).
4. Select **Coloured editable vector regions** or **Ink outlines**.
5. In the existing Filament viewer, orbit the real 3D model, choose Front,
   Right, Back, Left, Above or Three-quarter, scrub animation motion, and press
   **Capture this 3D pose → editable Sketch cel**.
6. The viewer stops clip playback and PixelCopy copies **only the rendered 3D
   SurfaceView** at a bounded maximum of 768 pixels on either edge.
7. A PNG is written under the owned project's private `pose_cels` directory,
   hashed, and registered with source-GLB ID and camera/clip provenance.
8. The bridge checks source project ownership, GLB presence, PNG hash, bounded
   size, frame range, layer budget and image-reference budget **before any
   Sketch document mutation**.
9. The original captured PNG is imported as an **invisible image guide** on
   its own layer; the editable 2D result is placed on a separate **new vector
   layer**, exposed for exactly the requested cel.
10. Native colour quantisation builds filled paths, or native Sobel tracing
    builds editable strokes. A colour-budget failure can fall back to ink, and
    is disclosed; if tracing itself fails, the hidden source remains available
    for manual recovery. Existing authored layers are never flattened.

This is a **2D-from-real-3D capture and vectorisation pipeline**, not a live
3D mesh embedded in the Sketch database and not an AI reconstruction of a
3D model from a video. The captured result can be redrawn, corrected, cel
sequenced, or combined with other native Sketch layers.

## Safety and limits

- Explicitly selected source GLB only. No Gallery enumeration, upload, or scan.
- At most 64 MiB per GLB (the pre-existing Filament viewer limit).
- GPU readback max 768×768, PNG max 8 MiB, canonical private file parent
  verification, SHA-256 pinned to generated project asset metadata.
- All Sketch changes use existing `SketchEditOps`, revision checks, undo and
  redo. No direct SQLite edits or document schema migration.
- Maximum 32 sketch layers, 16 owned image references, 4096 vector objects.
  Requires two free layers per capture. Complex coloured sources can produce
  simplified, quantised paths or need manual cleanup.
- Real per-wheel control requires the source GLB to provide appropriate
  articulated nodes/animations and is not automatically created here.
- A complete multi-angle animation still needs multiple pose captures and
  animation keys. Automated batch sweeps, mattes/alpha segmentation and
  artist-grade colour cleanup are **not** implemented in this revision.
- Device-specific Filament SurfaceView PixelCopy GPU fidelity and interaction
  are not certified by Robolectric alone.

## Tests

`Sketch3DCelBridgeTest` exercises:
- aspect-preserving GPU capture bounds and bad-viewport rejection;
- native editable trace modes, frame, exposure and resolution caps;
- end-to-end synthetic owned pose PNG → hidden guide + editable ink cel;
- unchanged pre-existing art and absence of flattened image objects in
  editable cel;
- rejection of modified pose bytes before touching the drawing;
- unknown trace mode rejection before mutation.

CI: `.github/workflows/sketch-glb-cel-qa.yml`, compile + targeted Robolectric.
It performs no APK assembly, signing, installation, Worker deployment or
owner-phone editing.

## APK compatibility / release blocker (19 October 2026)

The connected owner device reports **VideoStudio 4.8.6**. The latest
available Sketch/Animate feature source base checked in this PR declares
`versionName=4.8.4`, `versionCode=487`, and
`applicationId=com.rezoxnemesis.videostudio.next`.

**DO NOT** package and install this branch as an upgrade over 4.8.6.
Before requesting any installable 4.8.7+ APK:

1. Obtain the **exact Android source revision** that produced the owner's
   installed 4.8.6, then forward-port this branch to it. Do not silently
   discard its newer features or migrations.
2. Verify the installed app's actual package name, `versionCode`, and
   signer SHA-256 against the build. Select a **strictly higher** versionCode.
3. Use only the original owner development signing key, expected SHA-256:
   `7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`.
   Never generate a replacement signing key or switch applicationId.
4. Pass Android compile, native Robolectric and complete signed APK checks;
   verify app launch, old-project access, GLB camera pose selection, PixelCopy,
   source metadata, vector draw/undo, timing, export and crash recovery on
   the same Android phone (without Gallery).
5. Only then offer/install an APK and distinguish build success from physical
   device QA.

This PR deliberately **does not** change APK version, signing setup,
application ID, source projects, existing user media or installation state.
