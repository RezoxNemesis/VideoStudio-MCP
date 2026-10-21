# Optional native image + structure conditioning: engineering probe

This research branch starts at tested source-control slice
`df33dce0c5c36e23d7bb657303571800f5aee36c`. It does not add a selectable model
provider, activation catalogue, generation command or artistic claim. Existing
three-input SD1.5 generation remains unchanged. No user pixels go to hosted services.

## Independently verified public files

| Repository | Immutable revision | Bytes | SHA256 |
| --- | --- | ---: | --- |
| sona-forge/clip-vit-h-14-image-fp16 | 0f88973ddf2652f08e3b2cf83dd06448ecb5c7a3 | 1264856075 | 038078ba22b67e57ec2ca5b466e0699f30e14a9613054be8c7fb24e537987689 |
| sona-forge/sd15-controlnet-canny-fp16 | b9688cefd7ebfd917edd81490d01dbbae6b4c0f1 | 723055316 | 399358929322eb5bb2f0e141e23486397a29ec871e4efa625c0f2ba4d418c698 |
| sona-forge/sd15-ipadapter-fp16 | 05fa2599c72fd2c237280fe92b6be5ccdba0e26c | 1764924739 | a0287f119d85b8028d9673850322247b5978ed9b504077bc04d433f4c9fadcb7 |

Total additional research files:3752836130bytes. They are outside Git at
`/workspace/work/native-ai-paired-models`. Upstream cards declare MIT for the
image encoder, OpenRAIL-M for ControlNet, and OpenRAIL-M + Apache2.0 components
for the fused UNet. Full licensing notices and upstream provenance must be
retained and verified before distributing any model catalogue. Model weights
are optional and are not part of the APK.

`scripts/inspect-native-paired-onnx.py` streams protobuf metadata and seeks over
weights. It never executes models or processes artwork. A synthetic wire fixture
and truncated-parent rejection passed. Actual graph inspection found opset17,
FP16 inputs/outputs and no external-data weights in all three files:

- CLIP: pixel_values[batch,3,224,224]; image_embeds[batch,1024] AND an additional
  auto-named vision hidden-state output `onnx::Gather_4496` (rank3). The card's
  one-output example is incomplete.
- ControlNet: sample[batch,4,64,64], timestep[batch],
  encoder_hidden_states[batch,77,768], canny_image[batch,3,512,512]; twelve down
  residuals and one mid residual.
- Fused UNet: sample, timestep, encoder_hidden_states, image_embeds[batch,1,1024]
  plus all13 residual tensors; output noise_pred[batch,4,64,64]. Every graph
  input has direct uses and NONE has an initializer default. The card calling
  residuals optional is inaccurate: supply zeros or actual residuals explicitly.

## Preprocessing and claims requiring correction

Immutable upstream LAION CLIP configuration at
`1c2b8495b28150b8a4922ee1c8edee224c284c0c` specifies bicubic(resample3),224px
resize/center crop, mean[.48145466,.4578275,.40821073],
std[.26862954,.26130258,.27577711]. The conversion card's nearest-neighbor
example conflicts. Future implementation needs verified native preprocessing.

ControlNet has nonlinear biased operations. Scaling input edges is not equivalent
to scaling its output residuals; use the latter for explicit control strength.
Card claims about NNAPI/XNNPACK FP16 performance are not this app's evidence.
Canny conditioning supplies structural edges, not semantic body-part intelligence.
IP-Adapter supplies image conditioning, not guaranteed identity or recovered unseen
views. Neither artifact establishes360degree character mastery.

## Native-runtime probe

`LocalDrawingPairedGraphProbeTest` is opt-in and skipped during ordinary tests.
Root alone runs Gradle and inference. Enable with:

```
VIDEOSTUDIO_PAIRED_MODEL_PROBE=/workspace/work/native-ai-paired-models
VIDEOSTUDIO_PAIRED_PROBE_OUTPUT=/workspace/work/native-ai-paired-models/native-probe-v1
```

The test independently rechecks byte hashes, loads actual ONNX sessions with app
CPU options and executes FP16 tensors using the app's cancellation-aware native
runner. It verifies actual graph signatures, finite runtime shapes, image-encoder
response, ControlNet response, and independent UNet responses to image embeddings
and structural residuals. It uses only synthetic numeric inputs, creates no image
output, writes stage evidence, and samples RSS/HWM/MemAvailable once per second.
This is an execution probe, not an artistically meaningful animation scenario.
The initial selector compiled successfully and skipped intentionally without env;
that is not real-runtime evidence. Root then ran the actual real-weight selector:
1test PASS/0failures/0errors,23.88seconds measured inside the test (31seconds
Gradle total), ONNX Runtime1.23.2 CPU/JNI. Actual maximum sampled process VmHWM
was4500316kB (~4.29GiB), including JVM/Robolectric and native sessions. This is
host process memory, not an Android phone requirement or benchmark. CLIP sessions
closed before ControlNet/fused UNet; memory samples during every load and inference
are preserved. Measured mean absolute deltas: image encoder0.45542818; sum of
ControlNet residual deltas2.00590489; independent UNet image delta0.00412361 and
structure delta0.00669461. All outputs were finite and had the asserted runtime
shapes. This establishes real dual input routes, not acceptable artistic quality.
Evidence is committed beside this report; full native logs and1Hz memory samples
remain at /workspace/work/native-ai-paired-models/native-probe-v1.

A production provider would still need safe pinned optional pack activation,
complete license notices, dual source/revision/hash freezing, CPU/RAM/storage gates,
FP16 native preprocessing/tensors, proper residual scaling, bound deterministic
checkpoints/cancellation/retry, ordinary manual/MCP controls, portable provenance,
and actual character/view studies. Do not silently fall back to the old provider or
advertise any new capability before these exist and are tested.
