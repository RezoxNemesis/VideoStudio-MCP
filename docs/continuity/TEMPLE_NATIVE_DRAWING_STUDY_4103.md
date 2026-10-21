# Temple native drawing and frame study

Owner requested a faithful editable layered redraw of the Temple Realm warrior,
then frames preserving its structure, costume, mass and style. This study executes
the actual VideoStudio4.10.3/code506 source at
`ea33220a3df31370c17dacb8169383a9d0e76af3`, tree
`2c0110decd8144ced6de9dd32ae806d2028ae676`. Product code/version are unchanged.
The separate test driver and evidence transport are the only code additions.

Original private JPEG SHA256:
`d239369f882ccd8df31ab35fa2da6d4feac15e3ccbbee6edcf49f8259e13c908`.
Original source and native reference crops remain outside Git and outside the
downloadable study. All earlier projects and failed generations are retained.

## Actual execution

The initial pinned local native neural draft executed successfully but failed
face/armor/fabric/lettering likeness. It is preserved as a failed raster draft,
not counted as an editable layered drawing or accepted art.

The subsequent source-free figure uses ordinary `sketch_create`, `sketch_edit`,
`precision_contour`, source-bound `sketch_inspect`, `paint_fill`, `paint_stroke`,
`sketch_preview` and native archive operations. Literal native gestures are
serialized in private input manifests; no workspace rasterizer or external image
generator draws the artwork. Native contour fills and raster paint cels are used;
their existence does not mean artist-quality likeness has been achieved.

Canvas640x1280 represents the original figure window x160..800 at original pixel
scale. The full1024x1280 background/title have not been redrawn. The full canvas
would exceed the existing contour-fill processing bound, which was preserved.
The original is imported separately into a locked native reference workflow and
never appears as an image object in the owned figure. No rigs or transforms are
used for the blink.18 layers contain155 paint objects and229 native path objects.

Fresh focused native run:2 tests, zero failures/errors/skips,25s total Gradle run.
`TempleNativeFrameConsistencyStudyTest` renders48 logical frames at24FPS. One2s
blink has3 unique rendered frames: open, half closed, closed. Frames18..23 change
only inside the predeclared eye region `[328,284,392,309]`. Every other body/costume
pixel is identical, and reopened frames24..47 equal frame0 exactly. Original
upload, source document and input archive remain unchanged. The editable archive
was exported through the app's own archive implementation.

`SketchOwnedArtworkEditorEvidenceTest` restores that archive in the actual
SketchStudioActivity and captures frames0/20/24 through ordinary Go navigation.
Both execute native Android Canvas under Robolectric. This is not a phone, an
installed APK, GPU execution, live ChatGPT/MCP, or Android codec verification.
The silent MP4 packages unchanged native PNGs with FFmpeg, without resize,
interpolation or retiming. All48 PNG hashes are included and independently checked
after lossless archive compression. Held frames are not48 creative scenarios.

## Visual decision: NEEDS_REWORK

Technical frame consistency passes. Exact reference likeness fails. Face/gaze,
jaw, bangs, finger anatomy, shoulders, armor, folds and material textures remain
approximate or simplified. Large flat masses do not reproduce the original's
detail. The scene/background/title, other views, camera rotation, body actions,
audio and240s film are unfinished. This output must not become an accepted model
sheet or be described as exactly matching the asset.

Native operation authoring and actual editor restore/navigation were exercised.
Manual authoring repeated by a user and a live ordinary ChatGPT/MCP round trip
were not exercised. Existing shared operations are retained; parity does not
certify art quality. Do not invent phone installation or artistic acceptance.

The initial20-contour request exceeded the existing16-contour inspection limit;
requests were split, without changing that limit. Two subsequent fixture failures
involved an atomic layered-workflow transaction boundary and background execution;
the fixture was corrected to use the required transactions and native worker.
All failed logs and outputs remain private. Assertions were not relaxed.

## Reproduce and review

The source-free downloadable ZIP binds the product commit, test-driver SHA,
literal app requests, compact receipts, all48 frames, editor screenshots, MP4,
editable `.vssketch`, JUnit evidence and visual rejection. Duplicate preview PNG
base64 is explicitly omitted from public receipts, with exact hashes retained.
Private inputs/generations are in
`/workspace/work/temple-reference-study-20261021`.

Use one local Gradle only:

```sh
VIDEOSTUDIO_AUTHORED_SCENE_MANIFEST=/absolute/path/authored-figure-v3.json \
python3 /workspace/onboarding/videostudio-gradle.py -p /absolute/repo/android \
  --offline :app:testDebugUnitTest --tests '*SketchAuthoredSceneStudyTest'

VIDEOSTUDIO_TEMPLE_FRAME_STUDY=/absolute/path/native-frame-study-v3.json \
VIDEOSTUDIO_OWNED_EDITOR_MANIFEST=/absolute/path/native-editor-v3.json \
python3 /workspace/onboarding/videostudio-gradle.py -p /absolute/repo/android \
  --offline :app:testDebugUnitTest --tests '*TempleNativeFrameConsistencyStudyTest' \
  --tests '*SketchOwnedArtworkEditorEvidenceTest'
```

The feature-only Actions workflow transports the already-rendered study. It
verifies exact ZIP/file hashes, frozen product-source ancestry, unchanged main
Android source/build file and checked-out test-driver identity. It does not run a
new creative test, build/sign an APK, deploy a Worker, publish a release, or merge
main. The courier commit is separate from the recorded product source revision.

ZIP SHA256: `2b52a9c8c933a8e01970f435e676b7f4a9dfbf960d46fd02d2b05b840ab4d76d`.
Opaque evidence blob: `567476948f97f00afa5cb16be90e73acde614706`.
