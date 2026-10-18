# Basic animation benchmark and editable local import

The owner paused further jungle-film cleanup to reproduce a supplied short cube
animation first, compare native renders, repair defects, and retain the working
workflow in the app. Every visual test update must include actual screenshots.

## Retained app workflow

Sketch Studio → File → **Import editable animation** opens Android's Files picker
for a `.vssketch` document. `SketchLocalArchive` stages the selected content URI
in private app storage, enforces the existing 256 MiB archive limit, then uses
`SketchPaintArchive.restoreFile` to validate and restore a new owned sketch.
Paths, layers, exposures, shape keys, linked palette styles, canvas size, FPS and
owned reference dependencies remain editable. Existing projects are preserved.
Imports run away from the UI thread, after pending editor saves. If the active
document changes during transfer, the restored sketch remains available in Open.
The existing linked-workspace archive/MCP workflow consumes the same format.

This source change has not been installed or verified on the owner's phone.

## Private benchmark, outside Git

Evidence is under `/workspace/engineering-review/basic-animation-20261018`.
The supplied video, measurements, vector reconstruction, source-assisted
backgrounds and rendered outputs are private artifacts. This repository is
public: none of that artwork or media is bundled in product source.

The benchmark uses a 1280 × 960 native sketch, 325 frames at 30 FPS. Read-only
reference measurements inform editable path cels for the coloured cube. Native
`SketchNativeExport` freezes the document and dependencies and renders every
frame through `SketchFrameRenderer`. Workspace FFmpeg encodes those native PNGs
and copies the supplied reference audio. The audio is not newly authored.

The closer visual comparison uses two static imported reference images cropped
and composed through native app tools for the paper backdrop. No animated raster
frames are substituted for the cube. The earlier all-vector background pass is
also retained. This is reference-guided vector reconstruction, not proof that
arbitrary stories can be drawn autonomously at the same quality.

Visual review found and repaired white face seams, then a missing light-green
face in small tumbling poses. Private native pixel checks cover these defects.
Final reference measurements and acceptance status are in the private
`Comparison.json` and `Evidence.json`; successful encoding is not an exact-match
acceptance gate. Do not claim whole-frame pixel identity or professional acting.

## Verification and continuation

- Local archive tests retain a 325-frame clock, editable shape keys and five
  native frame comparisons; retry preserves an artist's palette edit; malformed
  input leaves existing sketches intact.
- The native benchmark archive restores with identical pixels at ten sampled
  frames, including both jumps, impact, settle and end-card frames.
- A workspace native Activity review exercises the actual Files result handler,
  opens the imported editable animation, checks the prior project is intact and
  captures the editor. This is not a phone screenshot.
- Full Android suite: 1,248 tests across 248 suites; zero failures/errors, three
  pre-existing skips. Source smoke checks: all 306 pass.

Earlier adult drawing/action/acting studies remain as a separate 480-cel native
book, accessible from File → Drawing and acting study. Their first complete
20-second production run remains paused. The earlier two-minute jungle films
and all original cels are preserved; reference-quality character acting and the
separate 3D first-love film remain unfinished.

The original signing continuity path was found in repository CI. A downloaded
prior APK independently verifies against the pinned signer. No replacement key
was generated and no new APK/device update is claimed. Live app access remains
unverified after the private endpoint returned a Cloudflare access rejection.
