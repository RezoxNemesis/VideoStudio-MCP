# Video studio 4.4.0 — prepared-sketch character animation

Prepared Sketch Studio drawings can now become editable articulated 2D animation.
The human animation workspace and ChatGPT save/get/list/bake actions share the
same deterministic compiler, app-owned source snapshots and Canvas renderer.

## What is implemented

- Parent/child joints, object bindings, pose keys and linear/smooth/hold timing.
- Two-bone IK contact intervals, reach diagnostics and optional foot orientation locks.
- Rigid vector/transparent raster parts and per-vertex weighted vector deformation.
- Camera keys with selected layers, exposure/replacement cels, timed dialogue and mouth shapes.
- Visual joint dragging, numeric controls, a human weight editor, playback and local drafts.
- Revision-bound saved scenes and a new editable baked sketch; source artwork is preserved.
- Hash-verified image/paint dependency copying and crash-recoverable, idempotent publication.
- An original bundled **Rooftop Relay** example that opens as independently editable artwork.

Start in **Sketch Studio → More → 2D animation → Example**. Press **Play**,
scrub the contact frame, adjust a pose, then **Save scene → Bake animation**.
The baked sketch uses the existing native video export path.

Read the [human guide](SKETCH_2D_ANIMATION_GUIDE.md) or the
[ChatGPT guide](SKETCH_2D_ANIMATION_CHATGPT_GUIDE.md) for preparation and exact commands.

## Reproducible original-art exercise

`scripts/render-sketch-action-proof.sh` authors the fixture, runs the actual
Android `SketchFrameRenderer` under Robolectric native graphics, then encodes
those exact ordered PNG frames with workspace FFmpeg. It does not use image
generation, inserted photographs, optical-flow frame invention or a browser mockup.

The 1280×720 sequence contains exactly 100 frames at 24 FPS (4.166667 seconds):
a coloured courier deflects a drone bolt, followed by a separately authored
foreground POV and a medium recovery/dialogue shot. Body/prop articulation,
deforming cloth and replacement mouth drawings accompany the camera framing.
The final dialogue is visible lettering and mouth timing; the film has no audio.

Tests check actual joint motion and contact constraints as well as native frame
output. The scene moves its front elbow through 54° relative to the upper arm;
the wide-shot wrist follows a 545.59-pixel path. The final planted ankle samples
have negligible numerical position error and zero orientation error. Native
contact sheets and individual full-size frames are also inspected visually.

The result is a stylized articulated cutout/cel animation demonstration. The
100 output frames are not 100 independently redrawn full pictures. Pixel
changes, successful compilation and contact metrics do not certify artistic
quality or equivalence to feature-film animation.

## Defects caught by the exercise and review

- An unreachable back foot was rejected; the action was re-authored within reach.
- Position-only foot locking allowed a tilted sole; orientation locking was added.
- Expanded weighted and repeated cels could move to the top of the painter stack;
  expansion now preserves their source drawing order.
- Unsupported spatial layer transforms could invalidate world contact reports;
  they now fail before saving, rather than silently reporting misleading contacts.
- Portrait aspect ratios, camera overlays/inverse dragging, inserted pose keys,
  draft recreation, bake retries and later human edits have regression coverage.

## Limits and verification boundary

A rig requires separated, mapped artwork. It does not infer missing anatomy or
invent a new viewpoint from a flattened image. Prepare alternate-angle and
replacement drawings explicitly. Vector weights deform vector geometry;
painted/image parts use rigid attachments. Weighted gradient fills are rejected;
use rigid gradient parts or flat-colour vector regions.

Existing phone budgets remain: at most 1200 frames, 4096 objects, 131072 geometry
points, 4096 final transform keys and a 4 MiB native sketch per baked clip. The
compiler also bounds intermediate sampling and its program to 512 KiB. Dense
deformation expands editable cels, so divide long productions into shots and
assemble their native exports in the video editor.

The screenshots and MP4 in this exercise are **workspace Android Canvas evidence**,
not screenshots or exports from the physical phone. The phone reported 4.3.0 at
the start of this work. The new controls require the compatible 4.4.0 APK to be
installed before real-device acceptance. Package, owner connection identity and
pinned APK signing certificate are retained.

New relay schemas and typed tools are implemented and tested in source. Production
Worker deployment remains dependent on the owner's Cloudflare deployment credentials;
the app's encrypted backup route accepts the same native actions after the APK
upgrade. Transport publication must never be reported as completed native execution.

## Verified checks

The local full Android run passed **1054 tests across 198 suites**, with no
failures, errors or skipped tests. It includes 52 new animation tests: compiler,
persistence, independent mathematical/UI review, touch authoring, actual Activity
screenshots and the 100-frame native Canvas film. The final screenshot setup was
also run separately after its button-interaction adjustment.

`npm test` passed **236 Node tests and 306 smoke checks**. It exercises the actual
relay handlers, strict animation programs, native version gates, aliases,
revision contracts and existing privacy/transport paths.

These checks establish workspace implementation and regression evidence. APK
installation, physical-device performance and final artistic approval remain
separate from those results.

## Published update and proof

- [Compatible 4.4.0 APK](https://github.com/RezoxNemesis/VideoStudio-MCP/releases/download/videostudio-v4.4.0-preview/Video-studio-4.4.0.apk)
- [100-frame film](https://github.com/RezoxNemesis/VideoStudio-MCP/releases/download/videostudio-v4.4.0-preview/Rooftop-Relay-100-frames-workspace.mp4)
- [Actual animation Activity screenshot](https://github.com/RezoxNemesis/VideoStudio-MCP/releases/download/videostudio-v4.4.0-preview/animation-studio-frame-16.png)
- [Complete editable proof bundle](https://github.com/RezoxNemesis/VideoStudio-MCP/releases/download/videostudio-v4.4.0-preview/Video-studio-2D-animation-proof.zip)
- [PR #78](https://github.com/RezoxNemesis/VideoStudio-MCP/pull/78)

The APK was built from `8b366ae09a178b3bfce73e75eb762c06f2d6ea9c` in
[Android run 38566109168](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/38566109168).
That clean CI run also passed all 1054 Android tests. The package remains
`com.rezoxnemesis.videostudio.next`, label **Video studio**, version code **440**.
The 130070996-byte APK has SHA-256
`8c1fe5ed0b7bb01328bf91fd66e38828364483a651dd1ee0ce84b6867d5ba197`.
Its original signing certificate was independently verified:
`7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`.

[Proof publication run 38567136817](https://github.com/RezoxNemesis/VideoStudio-MCP/actions/runs/38567136817)
used the already verified APK and original native PNG artifacts, without
rebuilding the app. It verified every PNG checksum and encoded exactly 100 frames.
The release MP4 SHA-256 is
`6944d6844633529f537b8862fff695c80364d4f1aa844ce3b6fdb2b56484bd52`;
the 12194825-byte proof ZIP SHA-256 is
`f607551bee67bce403951a3e9b7745156a6414305cda8ef47d6071608bb15c47`.
Public APK, MP4, screenshot and ZIP downloads were retrieved and checked after
publication; the MP4 again decoded as 100 frames, 24 FPS, 1280×720, 4.166667 seconds.
The Activity screenshot was inspected again from the public release asset.
