# Native owned-art editor evidence

The signed4.9.7/code500 product source is
`9682d9cd37249d38142b1f02db8c5c16c8703dbf` (CI39489685614, draftPR136).
This follow-up changes private evidence drivers, not production artwork/rendering
code or the Android version. Keep the product baseline and driver revision/diff
separate in evidence. Host execution does not prove phone, installed-APK, GPU or
Android codec execution.

`SketchOwnedArtworkEditorEvidenceTest` restores an explicitly supplied owned
archive through `SketchPaintArchive`, opens the actual `SketchStudioActivity`,
waits for its normal `SketchCanvasView` raster worker and navigates frames with
the ordinary Go dialog. It captures native window PNGs and checks that opening
and navigation leave the document unchanged. Optional explicit ink/background
probes verify contrast; they do not certify artistic likeness. The original
two-character study uses a white speech-balloon interior as its background
probe because the surrounding canvas is transparent.

Supply `VIDEOSTUDIO_OWNED_EDITOR_MANIFEST` with `sourceArchive`, `output`,
`frames` and `sourceProof`. Optional `inkProbe` and `backgroundProbe` are
normalized canvas pairs chosen from an actual native view. Reports include the
archive hash, before/after document hashes, native PNG hashes, frame indexes and
an explicit unreviewed visual status. Private assets and generated evidence stay
outside Git. The manifest disables Gradle up-to-date reuse for this private run.

The existing authored-scene driver also accepts explicit `plainSource:true` for
reference-free editable source archives, using ordinary `sketch_edit`, native
contour inspection/paint and `sketch_preview`. It rejects reference image objects
in these owned studies. Generic preview requests respect their720-pixel limit;
layered comparison requests keep their1024-pixel limit. Full-resolution native
renders and editable archives are not reduced. Layered studies additionally save
`native-isolated-stage-preview.png` so adjacent visible construction guides are
not confused with marks in the active stage.

The motion driver accepts plain owned sources and a `sourcePreparationOnly`
mode. Preparation uses the existing native animation save/bake surfaces to make
a reference-free editable working source; it renders zero frames and records
zero animation scenarios. These are driver orchestration options, not new app
commands or external drawing/motion engines. Ordinary ChatGPT can execute the
same recorded native requests through the matching app contract.

Initial driver failures are retained with their original manifests, logs and
screenshots: a null accessibility-label lookup, a contrast probe on transparent
canvas and an out-of-range generic preview dimension. Corrected runs retain the
same preview bounds and contrast assertion. No product guard or artistic
acceptance criterion was weakened.
