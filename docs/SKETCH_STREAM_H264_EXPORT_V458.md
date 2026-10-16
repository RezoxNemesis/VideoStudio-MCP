# VideoStudio 4.5.8 — native Sketch Studio H.264 stream export

## Owner-reported problem
Crimson Rose — Nocturne is a 1200 × 1500, 24 FPS, 246-frame native Sketch Studio project. Two exports displayed "Sketch frame capture — 246/246 authored frames" at **66%** without producing a verified playable MP4. The source drawing and native frame projects remain preserved.

## Code diagnosis
- Frame capture reports progress from 2% to 66%; that stage precedes encoding and publication.
- SketchDocument supports up to 2048×2048, but the ordinary NativeRenderEngine.sourceRaster only supports axes ≤1280 and area ≤921,600 pixels. It rejects the original source:1200x1500.
- The ordinary Media3 compositor also treats 246 PNG cels as 246 independent short photo clips, an unnecessarily expensive path for one continuous film.

## 4.5.8 source change
- For sketches with out-of-range source geometry or at least 120 frames, use a dedicated Android H.264 encoder, avoiding the multi-photo Media3 composition.
- Decode one already verified, app-private PNG frame at a time. Scale to a codec-supported, 16-aligned, near-aspect-preserving size; convert RGBA to YUV420 using bounded scanline arrays; enqueue through Android MediaCodec; mux H.264 with MediaMuxer.
- For a 720p export, bound encoded geometry to 921,600 pixels / axis ≤1280. Never alter the original vector drawing or captured source PNG frames.
- Use the rational NativeFrameClock for sample timestamps. Protect codec memory, thermal and cancellation checkpoints.
- Explicit encoding progress runs **68–93%**, followed by atomic MP4 publication at 100%.
- Missing PNG, unsupported flexible YUV codec, 30-second no-progress timeout, 20-minute deadline, cancellation, sample-count mismatch and invalid encoded dimensions report named failures, rather than indefinite 66% progress.
- Existing SketchNativeExport completion, output hashing and AtomicMediaPublisher publication remain authoritative; no partial MP4 is registered.

## Identity
Version 4.5.8 / code 458, unchanged application package, original signing key and permanent v3 MCP endpoint. No Gallery enumeration or new external editor.

## Acceptance and remaining limitations
1. Green Android CI, full unit tests, pinned signer and artifact upload.
2. Install over existing VideoStudio without uninstalling it, then check original r60 master, 246-frame film, other projects and private MCP continuity.
3. On phone, use a fresh 720p sketch export and watch progress pass 66% to real encoding and successful MP4 publication.
4. Inspect decoded video (not just 246 captured PNGs): duration around 10.25 seconds, frame rhythm, black/background retention, gravestone composition, animated rain, emotional gaze, POV rose and playback.
5. Report codec name, actual output dimensions, duration and any error. Android CI cannot verify a physical phone codec or artistic film quality.

A separate owner-confirmed abandonment workflow for a crash-held earlier renderer is tracked in GitHub issue #90. Do not bypass startup recovery or automatically retry the earlier heavy job.
