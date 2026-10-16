# VideoStudio 4.5.3 — two-character 2D action and interaction proof

## What this adds to the real Android app

Open **Sketch Studio → More → 2D animation → Example → Rooftop Standoff**.

This creates **a separate owned, editable animation scene** with two human characters, **Vale** (teal courier) and **Rook** (wine jacket / golden scarf / scar). The exercise comes from locally bundled original vector artwork, with a deliberately reworked/mirrored and recoloured second cutout. It does not pretend that a neural model drew an arbitrary unseen character or that one flat card became a feature film.

The exercise includes three genuinely distinct **prepared** artwork views, independent rigs and heads/arms/scarves, body acting and recoil/recovery keys, a checked baton clash around frame 16, atmospheric perspective, editable timed lettering and several real prepared mouth-cel changes. It uses the existing, shared Android Canvas preview + edit + native export renderer, rather than playing prerecorded video inside a UI.

After opening: press **Play**. Pause, scrub frame 16 (baton contact), frames 36–59 (foreground POV, actually different artwork), and frames 60–99 (medium recovery/counter-angle). Select Vale's or Rook's rig to edit rotations and replace vector cels. **Pose tools → Action contact markers** lets you select two rigs and joints and add/remove an authored contact tolerance at a frame. **Production QA** compiles and checks those physical contacts; if the hand/baton joints are too far apart, the scene fails its QA instead of treating sparks as proof.

**Save scene** preserves the frozen source. **Bake animation** produces a separate fully editable Sketch Studio drawing. **Export video** uses the existing native source-verified encode pipeline. The original Rooftop Relay exercise remains available.

## Shared engine contracts

### Native rig-to-rig interaction checks

The `videostudio.sketch-animation.v1` program accepts an optional `interactionCues` array; older sources remain valid without it. Each cue contains:

```json
{
  "frame": 16,
  "rigA": "wide_rig",
  "jointA": "wrist",
  "rigB": "rook_wide_rig",
  "jointB": "wrist",
  "maxGapPx": 80,
  "description": "Vale and Rook baton contact"
}
```

At most **64** cues are allowed. The source must have two **different valid rigs** and valid joint IDs. Frame, tolerance and description are bounded before compilation. Contact evidence samples the canonical IK/pose solver in **real source-canvas pixel coordinates**, not moving camera-space coordinates; every cue must be no farther apart than its declared tolerance. The compile report includes `interCharacterContactCount`, `maxInterCharacterContactGapPixels` and each audited cue. Save/Bake share these rules, so a badly authored action must be corrected before producing a film. The editor performs lightweight verification on a cancellable compute executor before accepting a new cue, without blocking regular sketch saves and previews.

A valid joint gap does **not** prove skin/weapon meshes actually touch, prevent limb intersection, or establish believable cinematic acting. The contact evidence is precise but intentionally narrower than artistic acceptance.

### Deterministic multi-view two-actor exercise

The first actor uses the previously authored original local vector cutouts; the new actor has its own recoloured, mirrored, revisable cutouts and **separate rigs** for wide, POV and recovery viewpoints. Outfit and scar colors remain identifiable across views; the frame-specific cut uses independently prepared geometries, not a flat whole-image zoom. Original background depth, timing and clip edits remain editable.

A native user-created source and program are persisted via the same checked owner stores and revision-aware animation save as other Sketch Studio projects; the project ID is new, existing projects are never replaced.

### CI visual and frame-order audit

`SketchDuelAnimationExampleTest` tests original source/artwork integrity, two independent actors, three prepared views, the sampled 2D wrist separation during contact, independent arm motion, timed mouth drawings, actual rendered Canvas pixels across camera cuts and separately preserved source data. The CI-only second mode writes **all 100 native Android Canvas PNG frames** plus the editable vector input, pose program, exact frame hashes and motion report.

`scripts/encode-duel-film-proof.py` checks frame order and SHA256, packages exactly those 100 frames with FFmpeg at **24 FPS, 1280×720**, probes the decoded frame count and duration, and uploads the playable MP4 plus contact sheet and source/program. No synthetic interpolation or audio is inserted to fake success. A valid encoded MP4 isn't proof of physical-phone performance or professional animation.

`SketchAnimationInteractionCueTest` checks positive and negative inter-rig distances, unknown references, frame/tolerance bounds and non-mutation on rejection.

## Nonnegotiable boundaries

- No phone Gallery enumeration, no paid model download, no external speech/video cloud service.
- The original package `com.rezoxnemesis.videostudio.next`, stable MCP owner credentials, signing certificate and project data are unchanged.
- Stable update chain: 4.4 → 4.5 → 4.5.1 → 4.5.2 → **4.5.3**, all staged in independent drafts until safe integration.
- The exercise is **not** arbitrary text-to-feature-film. True production-level original artwork, high-quality alternate-angle generation, complete hand-to-hand collision volumes, lip-sync aligned to **recorded speech**, environmental audio and full-shot artistic direction remain open.
- Real-device installation, audio playback, thermal throttling, frame pacing, crash monitoring and human assessment of the actual MP4 are necessary before confidence claims.

See [seven-system engineering acceptance](https://github.com/RezoxNemesis/VideoStudio-MCP/issues/82).