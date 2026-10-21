# Sketch Studio 4.7.0 verification

2026-10-17, workspace source candidate. No phone execution or live owner MCP
endpoint was used for this verification.

* Full Android unit run: **1,239 cases in 246 suites, zero failures/errors**.
  1,236 passed; three existing opt-in film/bundle proof cases were skipped because
  their external fixtures/environment flags were absent.
* Prior full `npm test`: **272 tests plus 306 source smoke checks**, zero failures.
  The Java-only jungle update reran all **306 source smoke checks**, zero failures.
* Open pipeline Python suite: **25 tests**, zero failures/errors.
* Real installed MCP SDK + local workerd: new tools discovered; native 4.6.2
  refused feature-length and dialogue requests before queue mutation; six requests
  retained their exact parameters through the native queue. Replies were synthetic Android
  acknowledgements, not device execution.
* Worker dry-run bundle succeeded. No Worker deployment was performed here.
* Native Skia tests read all 10,000 frozen frames, verified exact ordering and final
  cel pixels, retained old artwork after source edits, and rejected a tampered
  frozen index. No intermediate output PNGs or per-frame project clips were made
  for that long generation.
* One-hour book preparation/timing passed at 24, 30 and 60 fps, with no eager cel
  allocation. The native navigator and virtual strip reached cel 216,000 at 60 fps.
  This is not a rendered/encoded one-hour movie or a phone performance measurement.
* Painted cels streamed from owned frozen dependencies after the original paint
  owner became unavailable. Reusable painted components also retained independent
  pixels and accepted additional native brush strokes after placement.
* PNG ZIP import verified natural numeric ordering and actual rendered cel pixels.
  Interrupted import resumed without replacing an earlier human edit. Unsafe ZIP
  paths and source-hash changes were rejected before creating a book.
* Optional rigid-head tests verified source-pixel placement, alpha, profile and
  native editor roundtrips. Source-deformation body quality remains below the
  cinematic gate.
* Native editor regressions verify that late-cel edits signal their actual book
  page, and imported cel playback presents the new artwork on its first paint.
  Playback renders on a worker before adopting the next cel; cancelled or stale
  preview bitmaps are recycled. This does not establish device realtime speed.
* Real local Kokoro CPU inference completed English and Hindi two-actor auditions
  through native request export and native project import. The app verified WAV
  sample clocks, hashes and pending dialogue text/cast/timing, retained individual
  recordings and created an editable master lane in a separate project copy.
  Manual controls and reopening saved autonomous requests pass native UI tests.
  An actual model overrun failed with `DIALOGUE_EXCEEDS_SLOT` without publishing a
  result. Audible voice acting, pronunciation and final lip sync are unverified.

* The original jungle benchmark renders all 2,880 output frames of the complete
  120-second native book (22 shots, 2,148 editable cels per language), with no
  image/paint references in any saved cel. Real English/Hindi Kokoro performances
  use separate amplitude-timed mouth drawings. Native original PCM supplies the
  score and effects. Final 1280×720/24 fps MP4s contain 120-second stereo audio;
  cloud FFmpeg packages the native Canvas pixels and audio. Sampled cels, an
  actual native-editor screenshot and an editable construction/component sheet
  were inspected. Full playback/listening and professional-quality acceptance
  are not claimed. See [Original jungle film](ORIGINAL_JUNGLE_FILM.md).
* The new future-page regression first reproduced `SKETCH_ALREADY_EXISTS` when
  navigation allocated a cel before the film authoring worker reached it. The
  repaired range preserves opened blank pages and human drawings, then authors
  later pages. Ordinary edited-cel resume, full-film exposure timing, model-sheet
  geometry and actual PCM headroom checks also pass.

Unverified: Android MediaCodec/MediaMuxer on the owner's device, native video
sampling/orientation on that device, thermal/storage behavior of a real feature
film, realtime playback, signed in-place installation, live ChatGPT host tool
refresh, coherent generated in-betweens and professional animation quality.

Signing remains pinned to the original owner certificate. The existing GitHub
Android workflow may produce a signed candidate using its pre-existing cache;
it refuses a missing or changed signing key. No replacement key is generated.
