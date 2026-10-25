# VideoStudio Native Offline Video Generator — prototype contract

**Development status (2026-10-25):** Separate Android workspace and native
MCP route exist on an isolated source branch. **No neural video weights are
bundled or verified on the owner's Android phone. This is NOT Kling, Google
Flow or production-quality generative video yet.**

## The hard restriction

No API keys, external AI inference endpoint, pay-as-you-go credits, expiring
free trials, subscriptions, upload of prompts to an inference provider, or
background Gallery enumeration. All generation must happen in the VideoStudio
Android process through the existing, bundled ONNX Runtime. Model downloads
are user-owned explicit file imports, and generated assets stay inside
VideoStudio's project workspace.

## Separate Android workflow

Home / Tools -> **Offline AI Video Generator** opens
`NativeOfflineVideoActivity` (not the legacy procedural prompt scene editor).

1. Import storyboard stills into an ordinary VideoStudio project using its
   existing user-selected import route.
2. Open Native Video Generator, choose Image to video (1..6 explicit owned
   images) or Text to video (no reference image).
3. Enter a motion/camera/acting prompt and a deterministic seed.
4. Import a compatible free perpetual-license ONNX temporal model ZIP from
   Android's user-selected document picker. Installation is transactional
   through the existing ModelPackManager; no Gallery browsing.
5. Generate: validate exact project revision, model hashes, available memory
   and runtime ABI **before starting any new project or costly work**.
6. The actual neural model returns 9 RGB frames per selected storyboard shot.
   Each frame gets its own app-private atomic PNG checkpoint, then a separate
   editable VideoStudio timeline is created, and Media3 export is attempted.
   Failed native MP4 exports leave the owned frame project available.

If model weights are missing or incompatible, **generation refuses** with
`LOCAL_VIDEO_MODEL_NOT_INSTALLED` or `LOCAL_VIDEO_MODEL_ABI_MISMATCH`.
The app must never replace this with pans, color-fades, random generic
characters, or procedural slideshow frames.

## Strict model pack interface v1

Imported `manifest.json` must include:

```json
{
  "id": "native_temporal_video_onnx_v1",
  "version": "1",
  "license": "Apache-2.0",
  "runtimeAdapter": "videostudio.temporal-video-onnx.v1",
  "requiredAvailableRamMb": 2048,
  "requiredResidentMb": 1200,
  "requiredWorkingSetMb": 1800,
  "backend": "onnxruntime-cpu",
  "hostedInference": false,
  "requiresSubscription": false,
  "timeLimited": false,
  "capabilities": [
    "video.image_to_video.neural",
    "video.text_to_video.neural"
  ],
  "files": {
    "video_generator.onnx": "<64-digit-actual-SHA256>",
    "text_encoder/model.onnx": "<64-digit-actual-SHA256>",
    "tokenizer/vocab.json": "<64-digit-actual-SHA256>",
    "tokenizer/merges.txt": "<64-digit-actual-SHA256>"
  }
}
```

All four files must actually exist, match SHA-256 and be present inside the
single ZIP. The ModelPackManager appends `archiveSha256` and installation
evidence after transactional activation. A model may use Apache-2.0, MIT,
or BSD-3-Clause; licensing/redistribution terms must be checked before any
weight conversion or redistribution. The app does **not** download or
redistribute third-party video weights automatically.

### Temporal ONNX interface

- The CLIP `text_encoder/model.onnx` accepts `input_ids` (1×77, INT32
  or INT64) and outputs shape 1×77×768 float.
- `video_generator.onnx` requires precisely:
  - `image` — float32 [1,3,128,128] normalized [-1,1]
  - `text_embeddings` — float32 [1,77,768]
  - `noise` — float32 [1,9,4,16,16] seeded Gaussian latent input
  - `image_mask` — float32 [1], 1 for I2V, 0 for T2V
- The temporal model must generate **its own** `frames` output as float32
  [1,9,3,128,128] normalized [-1,1].
- Full temporal denoising and 3D motion inference happen **inside the supplied
  model**, not via a fake image pan in Java. No generic public LTX/Wan checkpoint
  is claimed to work with this custom conversion ABI today.

Currently this is a **small, experimental local execution format**
(9 FPS, nine independently generated frames per shot, 128×128, up to six
shots). High-end 720p 24 FPS cinematic generation on typical phone hardware
requires substantial additional modeling/quantization, memory optimization
and true Android device tests. Six 9-frame shots are not the full length,
quality or fidelity of a Kling-style final video.

## Existing MCP contract, no new remote app service

Through the owner's existing VideoStudio private native MCP, call:
- `native_offline_video_capabilities` (read-only, no project required)
- `native_offline_video_plan` (strict non-mutating and model-ready admission)
- `native_offline_video_generate` (native job + temporal inference + MP4)
  
Example request for a VideoStudio-owned storyboard:

```json
{
  "sourceProjectId": "<existing-video-studio-project>",
  "sourceUpdatedAt": 1792930000000,
  "operationId": "samurai_i2v_test_20261025",
  "mode": "image_to_video",
  "sourceAssetIds": ["<owned-still-id>"],
  "prompt": "The young warrior steps forward in real rain. His hair, robes and hands move continuously; the camera arcs slowly at eye height.",
  "seed": 42
}
```

The exact project, source revision, model and operation ID are immutable.
A changed source revision or different request using the same operation ID
fails; original media and original project remain untouched.

## Safety and release proof

- New offline neural video generation is classified as heavy native work and
  respects `NATIVE_RECOVERY_HELD`, the single heavy inference lane, Android
  resource admission, interruption checks, and the local model's file hashes.
- The current 4.10.43 phone previously reported Media3 preview error 7001
  and interrupted render recovery. A successful unit test **does not fix**
  or bypass these codec/GPU issues.
- Native unit tests must pass, then a verified signed APK must preserve the
  original certificate, device data, private MCP identity and on-device
  original project data.
- A real compatible ONNX temporal checkpoint must be built and verified with
  the exact schema above. Only then can a decoded native MP4 be reviewed for
  motion quality, frame continuity, character preservation, audio timing and
  real-device performance.

This prototype adds no paid services and **does not yet establish
model availability, photoreal image-to-video quality or actual phone MP4
export**.

## 2026-10-26 hardware and timing correction

The owner's RMX5004 model is a realme P1 Speed 5G (MediaTek Dimensity
7300 Energy). The Snapdragon/Qualcomm Hexagon/QNN engines from other
Android generative video projects are **not interchangeable** with this
SoC. This candidate is **ONNX Runtime CPU-only**. It does not claim
validated MediaTek APU/NNAPI acceleration. The local `DeviceComputeProfile`
determines current free memory, thermal and working-set limits, and
`NativeOfflineVideoHardware` rejects an unsupported backend or model
that exceeds a conservative real-device resident/working-set budget.

The phone's October 25 profile reported about 5907 MiB available RAM,
2480 MiB working-set budget and 1686 MiB resident-model budget. This is
a transient measurement, **not guaranteed future model headroom**.
Neither Wan2.2 5B nor a Qualcomm-only bundle has been shown to run
inside this budget on the device.

The native editor's previous still-image minimum duration was wrong for
generated neural frames. `NativeRenderEngine` now requires generated
asset role and matching neural-frame indices before accepting the special
9 FPS/short-frame clock; generic photos retain normal duration behavior.
`NativeOfflineVideoMotionAudit` rejects blank/static temporal tensor
outputs and records sampled frame delta metrics. **Measurable moving
pixels are not evidence of character consistency, good acting, or
comparable cinematic quality.**

The checkpoint pipeline re-infers on restart and atomically replaces
any prior frame file. A partial, stale or corrupt PNG cannot be silently
accepted as a valid current frame just because its filename exists.
The input reference bytes are rehashed to detect changes during work.
The model must declare `requiredResidentMb` and
`requiredWorkingSetMb` and `backend: "onnxruntime-cpu"`.

The six-short-shot / custom tiny 128×128 interface is a **prototype
research ABI**, not a suitable substitute for the user's 16:9 wuxia
cinematics, high-resolution characters or 30-second films.
