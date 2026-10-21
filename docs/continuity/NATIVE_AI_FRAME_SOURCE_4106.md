# Native AI source preparation — provisional 4.10.6

Agent3 extends frozen 4.10.5 commit8e5c3625506088c839fd61b1eec81fbf1affc966.
This slice adds an explicit authored sketch-frame input and a read-only input
preview to existing local_drawing_generate. It does not change the SD1.5 provider,
train character identity, recover artist layers, or establish accurate unseen views.

## Shared source controls

Legacy imported-image requests retain their exact fields, canonical request hash
and minimum4.10.2. Instead of objectId/sourceSha256/sourceRect, a caller can choose:

```json
{"frameSource":{"frameIndex":4,"documentSha256":"<sketch_get documentSha256>","layerIds":["layer_1"],"crop":{"x":0.1,"y":0.2,"width":0.4,"height":0.4}}}
```

Keep requestId, sketchId, expectedRevision and settings as usual. Explicit unique
layer IDs (1..32) and frameIndex use the source sketch's real timeline. Native
rendering preserves document layer order and includes only selected visible layers;
locked layers remain read-only. Hidden layers reject. Crop uses the full authored
canvas, not an image object's coordinates. Full native resolution (axes<=2048) is
rendered transparently, followed by integer pixel cropping, alpha-emptiness checks,
and the existing512px aspect fit with opaque white letterbox. Background/checkerboard
is excluded from the emptiness test. Crop axes below8pixels reject.

Get current revision and documentSha256 through ordinary sketch_get. Stale
revision/hash, missing layers, out-of-clock frames and empty selected crops reject
before a study is admitted. Native source PNG generation, hash/render/decode and
preview decoding run on workers. Interruption is checked before/after rendering
and between scan rows. Authored source documents are never edited.

Add previewOnly:true to either source mode to inspect the exact prepared model
input without settings/model installation/inference. The response includes
localDrawingSourcePreviewImage with PNG MIME/bytes/dimensions/SHA256 plus source
revision, document identity, frame/layers/crop/native bounds. No study, receipt or
project files are created. A source preview is not a generated draft and cannot
be recorded as portable local_ai_raster provenance.

Manual path: Sketch Studio > More > Local AI drawing > choose Imported reference
image or Selected sketch frame > explicit source frame > Choose frame layers >
Advanced settings crop > Preview source > Generate new draft. Manual preview
and MCP call the identical native operation. Previewing input does not replace the
saved generation request or last study. Show a completed draft before placing it
as an animation key.

New explicit frameSource or previewOnly fields require4.10.6, including nested AI
provenance. Their omission retains older floors. AI key import continues through
ordinary sketch_import_reference (4.10.5); portable request/model/source provenance
persists the exact frame source. Retries use the frozen hashed source.png and model
checkpoints even if the source document later changes. Reusing requestId with changed
settings/source conflicts. Source-frame conditioning remains a single-image refinement
input, not a separate character identity or semantic pose model.

## Evidence and remaining limits

Frozen core RED:6tests,4expected failures/0errors (frameSource unknown). Frozen
manual RED:1test,1expected failure (Preview source absent). The first GREEN
compile exposed two Java API mistakes (void bool helper and JSONObject.keys iterator),
preserved in green-focused.log; no tests executed in that failed compile. After
repair, root ran the frozen5selectors: LocalDrawingFrameSourceTest,
LocalDrawingFrameKeyframeTest, LocalDrawingBoundariesTest, LocalDrawingKeyframeTest
and LocalDrawingCoreTest. Fresh focused GREEN2:29tests/0failures/0errors/0skips.
JUnit is preserved at /workspace/work/native-ai-frame-source-20261021/green-junit.
The3 frame-keyframe fixtures use synthetic completed receipts and exercise Undo,
editable archives, component reuse and tamper rejection; they are not inference.
Root owns the only Gradle lane and still must perform final integrated validation.
Agent3 full npm regression:702Node tests plus306smoke
checks,0failures, saved outside Git in native-ai-frame-source-evidence/node-integrated.log.

Parent's actual native SD1.5 portrait-to-profile probes at strength0.55/0.9 stayed
frontal and drifted identity. Corrected native construction-guide input at0.45 kept
profile geometry but remained flat/template-like;0.85 produced a different3/4face.
All are NEEDS_REWORK. Source input preview solves wrong-frame/crop admission and
makes actual authoring accessible; it does not solve likeness or360degree drawing.
A separate optional dual-conditioning provider requires independent graph/weight,
license, resource, native execution and output validation before advertising it.
