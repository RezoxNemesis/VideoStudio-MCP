# Native paired drawing — provisional4.10.7/code510

This feature extends source-frame slice df33dce and separately committed real
public-model audit2d76204. Root integrates the app version; this isolated branch
has not changed versionName/versionCode/AppProtocol. Prototype outputs are4.10.7
working-source studies, not a signed4.10.7 APK or accepted character views.

## Actual shared capabilities

Existing local_drawing_generate accepts an optional paired mode:

```json
{
 "requestId":"actor_profile",
 "sketchId":"owned_source",
 "expectedRevision":5,
 "frameSource":{"frameIndex":32,"documentSha256":"<exact sketch_get SHA>","layerIds":["layer_1"],"crop":{"x":0.565,"y":0.14,"width":0.1,"height":0.195}},
 "appearanceSource":{"objectId":"owned_appearance","sourceSha256":"<working PNG SHA>","sourceRect":{"x":0.17,"y":0.23,"width":0.34,"height":0.21}},
 "conditioning":{"appearanceStrength":1,"structureStrength":0.8},
 "settings":{"prompt":"A detailed graphite portrait in strict right profile","seed":41718,"steps":24,"strength":0.85,"guidance":7}
}
```

appearanceSource and conditioning require each other and frameSource. Appearance
is an owned, unmeshed/unflipped full image in the same source sketch, read without
editing even when its layer is hidden/locked. An actively cropped/flipped/meshed
appearance guide rejects. Source crop coordinates use the full EXIF-oriented
working PNG. Original-upload/working-source/processed-input/generated-output SHA
identities remain distinct. The frame uses explicit visible layers, real authored
clock, document/revision hash and native rendering at authored resolution.

Manual path: More > Local AI drawing > Frame + appearance reference. Choose the
source frame/layers and existing image, edit the two independent crop regions,
then Preview source. Check/download uses the separate optional model. Generate,
resume/get and ordinary animation-key placement share the exact native/MCP logic.
No new canonical action was added. Existing image-only omission paths retain
old fields, pinned catalogue identity and4.10.2 floor; frame-only/preview remains
4.10.6. New paired fields and nested provenance require4.10.7. Explicit packId
on newly parameterized local_drawing_models requires4.10.7, including the legacy
pack value; omitted{} remains4.10.2. Existing legacy install selection remains
4.10.2. Both model identifiers are closed enums.

## Exact native inputs and inference

previewOnly:true returns three real bounded PNGs, with MIME, bytes, dimensions
and SHA checked by manual decoding and transported as ordinary MCP image blocks:

- localDrawingSourcePreviewImage:512px opaque aspect-fitted initial frame.
- localDrawingAppearancePreviewImage:224px RGB appearance input. Native alpha-over-
  white Catmull-Rom bicubic shortest-side resize and center crop precedes actual
  CLIP normalization. PNG pixels are RGB, not normalized floating-point tensors.
- localDrawingStructurePreviewImage:512px binary opaque Canny edge input. Native
  bounded five-tap binomial smoothing, L2 Sobel gradients, nonmaximum suppression,
  fixed100/200 thresholds and enqueue-once8-neighbor hysteresis. No semantic pose
  or body-part understanding is claimed.

All source decode/render/hash/preprocessing and preview decode occur on workers.
Source axes<=2048, image decode<=8MiB, integer crops>=8px, source-layer1..32,
Canny512square and CLIP224square keep work/scratch bounded. Transparent source
RGB composites over white. Integer appearance cropping precedes filtering, so
excluded neighboring pixels cannot leak into the preview/tensor.

The optional sd15_ipadapter_controlnet_v1 catalogue pins all8 actual files:
text encoder, VAE encoder/decoder, tokenizer files, CLIP image encoder, ControlNet
and fused IP-Adapter UNet. Total4581902259bytes; SHA identities/revisions/URLs,
preprocessing contract and contribution semantics are in the canonical catalogue.
Original catalogue is untouched. Optional installation uses existing resumable
checksummed download and transactional activation; full OpenRAIL-M/Apache2.0/MIT
notices are retained. Inference requires a64-bit process and8192MiB currently
available RAM. No automatic provider fallback or hosted inference exists.

Text, CLIP and VAE encoder sessions close sequentially. ControlNet and fused UNet
coexist only for denoising; they close before VAE decoding. The provider uses real
FP16 JNI tensors and the existing cancellation-aware ORT runner/shared inference
semaphore. All13 actual ControlNet residual outputs multiply by structureStrength.
The graph has no attention-scale input: appearanceStrength interpolates the actual
conditional noise-prediction difference against a zero-image-embedding prediction.
Endpoint strengths need2UNet passes/step; fractional strengths need3. ControlNet
needs at most2passes/step. Maximum30requested denoising steps remains unchanged;
actual strength-adjusted schedule length may be smaller. Receipt performance
records real elapsed time and forward-pass counts.

Admission freezes source.png, appearance.png and structure.png with individual
SHA identities; request/model identity binds both source selections and settings.
Saved text/image conditioning, encoded source and per-step latent checkpoints use
that request identity. Completed replay verifies published output and needs no
model installation. Ordinary AI key import, Undo/components/archives preserve the
full request/model/output provenance. This remains an AI-generated opaque raster
cel, not recovered hand-drawn strokes or automatically separated artist layers.

## Fresh evidence and exact limits

- Production core focused34tests PASS; deterministic Canny/CLIP4 +paired portable
  provenance3 PASS. No model required for these cases.
- Paired Node fresh final full705tests +306smoke PASS after the explicit legacy-
  model-selector gate repair and localized numeric fix; focused13PASS also retained.
  Log: /workspace/work/native-ai-paired-models/production-node-final-integrated.log.
  Relevant RED logs are retained.
- Initial E2E preview-v1 failed before rendering because the driver confused the
  archive restore operationId with returned sketchId. Failed generation/log/XML
  remain. Fixed driver uses actual returned IDs; preview-v2 PASS13seconds.
- Root reviewed all3 actual source previews. Exact prepared PNG SHA256:
  source6e40c8bca71321f9f78c005ffd9109b87c03c448651a913081366252f2270bc3;
  appearance3ed3a9ff844896fb9da1c9517c8b4c82c9d465bb29eb5b09840cc359fd923a45;
  structuredbaff6de83535fd7774d1a290f7f449b11317e0133e3d4f79ff7a264f3b4d70d.
- Root real E2E inference-v1 checked those three hashes before native cached-weight
  install/inference. PASS3m22s Gradle;174483ms operation;166274ms engine;
  20actual denoising steps from24requested/.85strength,40UNet +40ControlNet passes.
  Real checksum/license/transactional activation, shared source preparation,
  generated asset/replay, ordinary AI-key import/archive restore, source/upload
  retention and replay after model uninstall passed. Model network download and
  phone execution were not exercised by this cached-weight host test.
- Actual draft SHAeeebb4c9a8815ad9101fc75d2f595b9762537957e2547784261a76125d074269.
  Files at /workspace/work/native-ai-paired-models/character-profile-inference-v1:
  native-paired-draft.png, paired-model-key.vssketch, paired-input-source.vssketch,
  three input PNGs, native requests/receipts and evidence. Failed studies remain.

Technical routing improves over the old prompt-only frontal failure: actual output
is right-facing and pencil-like with long dark hair. Root and Agent3 independently
reviewed it as NEEDS_REWORK: elongated generic neck, simplified eye/anatomy, blunt
hair silhouette, blur and likeness drift. The source guide itself is a generic
legacy diagnostic with wrong neck/mass. These inputs are accepted only as a provider
control diagnostic; no actor-angle/anatomical acceptance is established. Root is
improving actual native anatomy guides before the next quality judgment.

The model does not guarantee exact likeness, unseen-view reconstruction, temporal
consistency,360degree mastery or a finished240second film. One portrait study is
not an animation scenario; denoising passes are not frames or scenarios. Neural
raster keys do not recover editable artist stroke layers. No phone installation,
live private MCP execution, Android GPU/codec work, speech or audible soundtrack
is claimed. Graph host memory4.29GiB is not a phone RAM benchmark.

## Numeric identity repair and legacy continuity

Root identified an existing manual-admission bug after direct JSON E2E passed:
Java Double7.0/0.0 participates in SketchDocument.canonical as7.0/0.0, while
JSONObject copy/save collapses it to integer7/0. The old ledger rejects its own
persisted request. Manual source preview alone did not cover generation admission.
LocalDrawingNumericIdentityTest freezes actual Double inputs through no-model
admission/read/retry, valid legacy integer replay via Double, historical manual
ledger SHA retention through portable import/Undo/archive and tamper rejection.
The frozen RED observed4cases/3failures/0errors: manual admission/read failed
RECORD_IDENTITY_DIFFERS, integer receipt replay via UI Doubles failed CONFLICT,
and historical UI-Double receipt read failed RECORD_IDENTITY_DIFFERS. The tamper
rejection case already passed. All RED XML/logs remain preserved.

LocalDrawingRequestIdentity now hashes the JSON wire representation actually
persisted. This repair is local to LocalDrawing; global document/request canonical
behavior and the old pinned model catalogue are unchanged. Previously valid wire
fingerprints stay unchanged. A historical manual receipt is accepted only if its
complete model+request SHA verifies after reconstructing the exact known manual
Double fields; no arbitrary hash or changed numeric meaning is accepted. Valid
stored request/model identity is compared before numeric-equivalent retry.
Historical receipt SHA, request IDs, checkpoint identities, project IDs and ledger
bytes remain unchanged. New portable AI provenance uses the persisted wire-proof
SHA, which may differ from the retained historical broken manual ledger SHA.

Root's fresh numeric GREEN observed26tests/0failures/0errors/0skips across the six
numeric/paired/source suites. XML is preserved at
/workspace/work/native-ai-frame-source-evidence/numeric-identity-green-junit.
This includes real manual-style whole-number Double admission/read/retry,
wire-equivalent old receipt replay, historical SHA/ledger retention, ordinary
AI-key import/Undo/archive restoration and rejection of changed request meaning.
Final integrated4.10.7 regression, signed APK and real phone validation remain
root-owned work; this focused GREEN does not establish those results.
