# VideoStudio 4.10.33 — Composition-Global 2D Camera

## User-facing capability

The After Effects-inspired Camera 2D track creates authored pan, zoom and
rotation over the **finished composition** rather than zooming a single
source image. It moves the combined video, image overlays, authored captions
and original vector shapes together, while leaving the audio unchanged.

- One source-free camera2d timeline track per project. Its clock starts at
  zero and ends at the source timeline's exact output duration.
- Uses StudioClipKeyframes and the same native keyframe graph as clip and
  overlay motion: exact key times, eased/pinned cubic Bézier handles, and
  manual UI editing.
- Media3 `Composition.Builder.setEffects` receives the original overlay
  effect first, followed by the camera matrix effect. This ordering is
  shared by interactive CompositionPlayer and H.264 export.
- A native Android Media-pane button adds the camera using Static, Push-in,
  Lateral Pan, Pull-back, or Gentle Roll defaults. Camera Inspector opens
  the precise keyframe graph and offers guarded removal.
- The camera has **no imported media, Gallery browsing, tracking, 3D meshes,
  depth, physical lens, scene-specific AI inference or camera opacity**.
- Original project assets and music stay owned by the same Android project.

## ChatGPT efficiency workflow

The stable private native MCP gains `camera2d_capabilities`,
`camera2d_plan` and `camera2d_apply`. Plan validates without mutation.
Apply atomically creates, edits, clears or removes one camera track after
checking an exact `projectId` and `expectedUpdatedAt`. It accepts one
strict `motionKeyframes` array for a set operation and returns a bounded,
truthful saved-project receipt. Source files and unrelated layers remain
unchanged.

## Validation / limitations

JUnit/Robolectric tests verify real Media3 composition effect ordering
(overlays before camera), precise camera matrix coefficients at frame times,
source continuity, camera singleton admission, invalid opacity rejection,
stale revisions and closed native MCP routing. Compilation success is NOT
a visual-quality or on-phone performance claim.

No global 3D/2.5D camera with physical depth, true perspective raster,
lens shading, motion-blurred temporal sampling, nested AEP projects, tracked
mattes or optical-flow is implemented here.

## Actual Android acceptance gate

The user-owned `Test subject 2` project must show edited frames and synchronized
audio without displaying stale unrelated source video. Render a 9:16 MP4
and compare frames at 0%, 25%, 50%, 75%, end with the editor preview. Repeat
for 16:9, 1:1 and 4:5 canvases and thermal-safe 720p/1080p output.
This checkpoint requires an installed signed APK and native render evidence,
not only the GitHub test report.

Stable public release remains protected: development branches must never
overwrite videostudio-latest.
