# Native export raster fidelity — 2026-10-19

Branch `fix/native-export-raster-fidelity-20261019`, base `543982e`, keeps
unreleased4.8.2/code485, existing public requests/defaults and signer identity.
The existing book → silent video → owned project import → Story Audio project
→ Editor H.264/AAC export route remains the product assembly path.

The previous streaming selector could prefer1024x576 hardware over available
1280x720 software at24FPS. It now admits only the **exact existing boundedSize
raster at the requested FPS**, preferring hardware among exact matches. If no
encoder provides that size/rate with flexible YUV input, it fails with
`SKETCH_CODEC_UNAVAILABLE` naming dimensions and FPS. The legacy quality budget,
alignment/aspect calculation and source-no-upscale policy are unchanged.
No source frames, timestamps, frame counts or audio are modified.

Final Editor export also needed repair. Media3's default video selector drops
software candidates whenever hardware exists, and its default format fallback
can negotiate a smaller raster. NativeRenderEngine now supplies all supported
video encoders, ordered hardware first, and disables format fallback through
the existing DefaultEncoderFactory API. Unsupported video-format diagnostics
name the exact requested raster; AAC failures keep their audio diagnosis.
The actual completion callback requires the computed aspect/quality raster,
including display rotation, before accepting the file for publication. Existing
source-raster requests and named-preset dimensions remain unchanged.

Evidence is private under
`/workspace/engineering-review/export-fidelity-20261019`:

- `red-v3`: original product sources compile;7 focused tests run with6 expected
  assertion failures, including the actual1280→1024 selector regression.
- `red-original-complete-v4`: final11-test file against preserved original
  product sources;8 behavior assertions fail. No missing-method failure is
  used as the raster regression proof.
- `green-focused-v2`: **33 standalone javac/Robolectric tests PASS**: new raster
  fidelity11, existing stream encoder6, render geometry4, frame timing7 and
  multitrack5. Commands and exact source/class/dependency hashes are retained.

Tests register synthetic Android codec capabilities, invoke the real product
selector and actual Editor Transformer factory, and exercise its actual
completion/error listeners with injected metadata. They verify reduced-raster
rejection, exact software admission, hardware ties, FPS refusal, existing
720p/1080p preset sizes, portrait display rotation and source-raster preservation.
These are native policy/composition tests, **not actual device encoding**.

No Gradle, deployment, installation or commit was run by this repair agent.
Parent owns official Gradle/CI and integration after source freeze. Real Android
codec/AV export remains **BLOCKED / UNVERIFIED** on this host: no /dev/kvm,
emulator or system images. A paired correctly signed device still needs actual
full-raster MP4, decoded frame/clock, audio-track and audible-sync verification.
No film artistic acceptance or broader quality certification follows this fix.
