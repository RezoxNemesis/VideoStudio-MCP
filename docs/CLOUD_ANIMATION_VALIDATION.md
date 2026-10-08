# Cloud native-app validation and trained-model prerequisite

The cloud ran the 3.6 native code in an Android 10 (API 29), x86_64 AOSP emulator using CPU software emulation and SwiftShader. The local debug APK uses the same app code and version as the ARM64 build, with x86_64 libraries and a local test signing key. This is not a physical Realme test or execution of the public ARM64-only APK.

The explicitly supplied screenshot was imported, cropped using shared native editor commands, displayed in Animate Image, and rendered by a manual native export. Actual PNG captures and a job report are retained under `/workspace/artifacts/cloud-android`. The first export completed in approximately 61 seconds in software emulation; ffmpeg decoded the MP4 and showed the original figure. This is a still-image video, not character animation.

Device validation exposed two issues that are fixed in source:

- Explicit local JPEG URIs without provider metadata were registered as generic data and omitted from the timeline. AssetProbe now falls back to the selected URI filename and standard MIME mappings.
- The editor remained black during initial composition preparation. A bounded source poster, matching the initial presentation/crop, is displayed until the composition reports a rendered frame. The poster does not claim to preview every GPU effect before that frame arrives.

The device instrumentation validates its own project's new export binding, not an older job's completion. It never includes the user's image in source control or release APK assets. The instrumented fixture is supplied to app-private files explicitly. JVM validation now includes the local-JPEG regression.

## Trained model candidate

Official LivePortrait Animals v1.1 weights are available from `KlingTeam/LivePortrait`, revision `82a4fa6735ca58432b6ce39301b4b9ee066dea47`. The four base checkpoints total about 520 MB. The official repository declares MIT; InsightFace detection weights have separate noncommercial restrictions and are excluded from this candidate. No face detector is required for an explicitly selected subject region.

The architecture has source appearance extraction, source motion/keypoint extraction, learned warping and SPADE frame decoding. Animal weights are a candidate for a cartoon portrait, not a guarantee that this drawing will animate well or that limbs, smoke and fire are synthesized by that model. Quality and identity must be assessed on the provided source before selecting it for the app.

The 5D GridSample operation needed by its warping stage was exercised in PyTorch 2.6 CPU and ONNX Runtime 1.20.1 CPU, opset 20, with maximum difference 1.1920929e-7. This is a kernel compatibility check; it is not trained inference or Android model validation.

`scripts/fetch-liveportrait-weights.py` pins the official revision and all four SHA-256 hashes. It streams downloads and accepts them only after checksum verification. Downloaded PyTorch files are not a VideoStudio ONNX model pack.

## Current blocker and continuation

The current cloud network policy denies the actual weight-download redirect host `us.aws.cdn.hf.co`. Metadata being accessible does not make the checkpoint files accessible. No pretrained checkpoint has been downloaded, tested on this drawing, exported, bundled or installed.

The saved environment draft includes `huggingface.co`, `us.aws.cdn.hf.co` and the existing private MCP Worker host, preserving package-manager presets. It must be applied/published in environment settings. This is a network prerequisite; no Hugging Face token is required for these public weights.

After a confirmed runtime policy change:

1. Run the pinned weight fetcher into `/workspace/model-downloads` and verify every checkpoint.
2. Load using tensor-only PyTorch deserialization and the pinned upstream architecture at `/workspace/LivePortrait-upstream`, commit `9b294b3d0536135442ea73cb01e6cb3ca7029dd3`.
3. Test neutral reconstruction and programmed reaction on the explicitly selected character region. Inspect actual frame outputs for identity/style preservation. If the model fails this drawing, select another real image-conditioned model rather than claiming success.
4. Export validated ONNX stages and measure Android peak memory and inference time. Implement a separate native LivePortrait ABI; 3.6's experimental recurrent ABI is not compatible with these weights.
5. Integrate genuine installation/download, manual UI controls and MCP execution, then verify generated animation and publish a signed new version.

No model-enabled APK has been published by this validation task. Existing 3.6 functionality and public release limitations remain in effect until those steps succeed.
