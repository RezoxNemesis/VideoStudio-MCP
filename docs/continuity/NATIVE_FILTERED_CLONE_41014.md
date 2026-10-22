# Native filtered clone sampling — 4.10.14 / code517

Clone-painted eye texture could change before any animation moved it. The old
clone sampler floors source UVs to one pixel, whereas the native image renderer
filters neighboring pixels at fractional placement. The private actor reference
has a half-pixel horizontal placement; all182 measured fully opaque iris pixels
followed the old nearest sampler rather than the displayed reference.

Optional `paint_stroke.cloneSource.sampling` accepts `nearest` or `bilinear`.
Omission preserves old normalized requests, journals, pixels and version floors.
Both explicit choices require native4.10.14 through ordinary and nested MCP
envelopes. Clone source remains forbidden for paint/erase modes.

`bilinear` uses Android Canvas/Skia with the same antialias/filter flags as native
images. It freezes the renderer's integral source crop (floor left/top, ceil
right/bottom), the affine source mapping and original PNG identity. One bounded
native raster supplies paint-pixel samples. Source and layer opacity are ignored,
as in the older clone brush; this samples source texture, not a composited scene.
Fractional crop mapping follows the actual integer crop only in the explicit
filtered mode. Existing nearest mapping remains unchanged.

The source retains its original PNG/hash and2048-axis limit. Filtered target
canvas size is at most1048576pixels; larger requests fail without downsampling.
The single source-pixel cache holds either a legacy raw source or one filtered
variant. Its filtered identity includes source hash, mapping, integer crop and
target size. Native drawing/readback checks cancellation; journals validate the
request/pin sampling agreement and crop declaration. Archive/copy/replay use the
retained source rather than a later mutable guide.

Manual: choose an owned Clone source, then Paint studio > Paint settings > Clone
sampling. Legacy default omits the field; Nearest and Bilinear are explicit.
The active source settings survive reopening the dialog, Cancel leaves them
unchanged, and the selected source is transmitted through the ordinary canvas
stroke path. This does not claim a new process-restart preferences system.

Native/Node capabilities expose the same `painting.cloneSampling` object. No
new canonical action or worker deployment is needed for the Android feature;
availability through an unchanged live relay remains a separate concern.

## Validation checkpoints

The old native516 runtime fails the new supported-field tests. The first test
fixture also attempted paint rendering on the UI thread; only worker execution
was corrected, preserving assertions and failed evidence. Manual RED confirms
the missing control. Changed-class focused execution passed33tests including
the9new core tests, new manual control and old brush/store regressions. Node
passed790tests plus306smoke. A later12test focused pass (5.013s) includes
the11new core tests and manual control, adding journal-tamper and cancellation
coverage and checking the clarified UI label. There are35distinct focused
native tests across these overlapping runs. Fresh full local Android validation
passed2383tests, zero failures/errors and28skips across
441suites in9m1s. Functional source hashes matched the tested snapshot.
CI and original-signer candidate APK verification are pending.

An actual private source diagnostic passed six bounded source cases (half-pixel,
fractional crop,180-degree rotation,17-degree/nonuniform transform, alpha edge
and retained soft mask). The measured filtered opaque/alpha/premultiplied errors
were zero at the native target grid in those particular regions, with omitted and explicit nearest exact
to the old behavior. This is not a claim of exact identity for every affine
transform or GPU backend. Enlarged preview crops differ because the frozen paint
raster is filtered again when enlarged; camera zoom can also soften it. The
manual choice is labelled Bilinear (native pixels). This is a native-grid
sampling option, not zoom-independent source reconstruction. Private source/
crops/recipes/logs remain outside Git.

This repairs sampling, not hand anatomy, hidden viewpoints or finished acting.
Copied pixels remain source-assisted. The actor eye-motion retest, detailed
hand/banana performance and240-second film are still unfinished. Native Java/
Skia workspace execution does not establish phone, GPU or Android codec use.
