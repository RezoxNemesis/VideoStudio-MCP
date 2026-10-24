# VideoStudio Motion Graphics Benchmark — 2026-10-24

## User-supplied reference collection

These are tutorial/screen-recording benchmarks, not licensed footage to copy
into the app. They establish the required editing behaviour, not a claim
that all demonstrated features have shipped.

| Owner reference | Runtime | Native reference | Capability requirement |
|---|---:|---:|---|
| 1000072304.mp4 | 48.270 s | 1080x1920 HEVC/30 | source-aware keyframes and rhythmic edit timing |
| 1000072305.mp4 | 10.837 s | 864x1920 H.264 variable timing | independent video/image layers and comp preview |
| 1000072307.mp4 | 42.300 s | 1080x1920 HEVC/30 | layered environmental composites, occlusion and depth |
| 1000072313.mp4 | 26.272 s | 1080x1920 HEVC/30 | editorial split screens, styled titles and precise cuts |
| 1000072309.mp4 | 25.227 s | 1080x1920 HEVC/30 | densely keyframed elements, simulated 3D perspective and grading |

The samples are held in the owner's conversation, not added to GitHub.

## Engineering contract / no misleading quality claims

A "VideoStudio matches reference quality" claim requires all of these, on
actual Android hardware rather than only synthetic unit tests:

1. Exact user-owned image/video/music assets remain intact, including audio timing.
2. Edits appear in the same position in the interactive preview and exported MP4.
3. Animated text and images are genuinely transformed frame-by-frame (not static
   slides or a screenshot of the source editor).
4. Frame timings are driven by authored markers or a measured audio onset signal
   with explicit provenance. Amplitude onsets cannot be advertised as verified BPM.
5. A 9:16 H.264 MP4 with audio survives a full decode/visual QA sweep at beginning,
   middle, transitions and end. No stuck-at-66%, empty-frame or black-frame success.
6. The project saves/reopens and survives process interruption. Native thermal
   and memory limits are respected.
7. The project's imported materials, original version and stable MCP credentials
   survive the upgrade; Gallery is never scanned.
8. Motion graphics score must be based on 0..100 visible measures:
   20 compositing layers/occlusion, 20 authored motion curves,
   20 text/graphic fidelity, 20 audio-edit synchronicity, 20 output continuity.
   A score above 90 requires human inspection of actual target exports.

## First delivered native engineering slice (not the full suite)

The StudioKineticDirector route brings one-command editing to the Android
runtime and UI. On the latest 4.10.25 source baseline it:

- Accepts a selected project's exact revision, 1..24 authored phrases,
  an editorial/impact/minimal template and explicit beat markers, manual BPM,
  or measured audio-envelope candidates.
- Compiles real native title overlay/keyframe sequences at bounded times;
  keeps existing image/video clips and audio lanes.
- Preflights all outputs through the existing native StudioLayerContract;
  refuses >32 layers and invalid timelines before changing source work.
- Supplies a dry-run JSON receipt showing cue positions and timing provenance.
- Applies to a new editable project with an idempotent operation ID.
  Repeated operations cannot silently overwrite a different program.
- Adds an ordinary Android editor button under Media. Original source project
  remains unchanged. No automatic expensive render.
- Uses native Media3 renderer's existing title transforms. No fake neural motion.
- Contains unit tests for tempo, pulse candidates, marker bounds, cue isolation,
  layer validation, revision guards, conflicts, original preservation and reuse.

Performance objective: author a multi-cue title montage in a single edit
operation instead of sending one MCP action per keyframe. Avoid redundant
asset decoding during program compilation. Device timings still require
benchmarking; no time saving percentage is claimed without measurement.

## Subsequent After Effects-inspired engineering gates

These gates are not yet implemented by the initial Kinetic Director patch:

1. Playback failure gate (P0). Reproduce user Test subject 2 12-clip,
   2-asset failure, expose actual Media3 video-frame exception
   cause/codec/overlay stage, and verify preview recovery before export.
2. Timeline engine. Precompositions, layer-parent transforms and adjustment
   layers with ordering, cache invalidation and undo.
3. Procedural graphics. Editable shape paths, trim paths, per-glyph kinetic
   layout, gradient/soft light and blend-mode compositor, real motion blur.
4. Advanced compositing. Masks/track mattes, keyed opacity, corner pin,
   perspective transforms, tracked subjects, 2.5D depth camera.
5. Audio direction. Accurate onset/tempo inference validated against
   owner-approved music, manual beat corrections, reusable rhythm cache.
6. Render reliability. Chunked compositing, deterministic frame checkpoints,
   preview/export comparison, thermal-safe background resume, MP4 QA.
7. Asset/preset quality. Professionally art-directed reusable templates,
   attribution/licensing, scene-specific rather than canned-looking graphics.

"After Effects-inspired" refers to design goals, not Adobe code/assets, AE
project-file compatibility or identical output quality. The native app remains
its own product.

## Current proof level

- Source-control patch: native Kinetic Director, Android editor entry, MCP actions.
- Static/unit automated verification: GitHub Actions workflow reports pass/fail.
- Real Android visual rendering: not verified yet.
- Reference parity: not claimed.
- APK packaging and installation: not part of this draft until CI passes and
  the pinned owner signing key is restored.
