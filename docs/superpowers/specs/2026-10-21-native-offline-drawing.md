# Offline neural drawing assistance — first executable slice

The owner authorizes a native AI engine, rejects hosted inference and requires
all artwork execution through VideoStudio. Existing projects, source art, APK
identity, signing key, undo/history and prior failed studies must survive.

## Outcome and limits

Integrate actual pretrained diffusion inference inside Android using ONNX Runtime,
not another procedural figure generator. First support bounded reference-conditioned
512px raster draft studies: original reference + explicit crop + prompt + seed +
denoising settings. A model-generated raster draft is labelled as such. It is not
recovered artist strokes, an accepted likeness, separated character layers,
photorealism, correct unseen anatomy or a finished animation. Those require their
own actual tests; this slice must not claim them. No hosted endpoint or credentials.

Use an optional app-private, checksum-pinned SD1.5 ONNX model pack. Model weights
are not bundled into Git or the APK. Model download is distinct from inference;
after installation, inference must work with networking absent. Keep license and
source revision with the pack. Do not execute model-supplied Python/code. Loading
a model manifest alone never establishes executable readiness.

## Native execution

The Java engine loads CLIP, VAE encoder, UNet and VAE decoder sequentially through
ONNX Runtime CPU sessions. Validate actual tensor names, types and dimensions.
Use bounded CLIP byte-pair tokenization, SD1.5 scaled-linear training alphas and
deterministic DDIM denoising with classifier-free guidance. Preserve aspect ratio
when preparing the EXIF-oriented, explicitly selected source crop; never enumerate
Gallery or accept arbitrary caller paths. Read/hash source bytes before acceptance.

One inference at a time. Bound canvas, prompts, tokens, steps, inputs, tensor shapes,
model files, native memory admission, storage and saved intermediates. Check
cancellation between phases and terminate an in-flight ORT run when interrupted.
Release every tensor, result and session. Keep Android UI and sketch workers free.

## Editing and persistence

Generation prepares a separate immutable candidate, never replacing reference or
existing artwork. Save accepted request, source identity, model identity, actual
settings, progress, output hash and provider/location metadata. An identical
request ID reuses its result; changed parameters reject. Save native checkpoints
so retry avoids replaying completed phases. Cancellation preserves evidence and
candidate bytes but does not publish a success. Archive/edit import remains an
explicit subsequent step through existing VideoStudio surfaces.

Manual UI and ordinary MCP call the same native operations: inspect model/runtime
readiness, install the pinned optional pack, generate a draft, and inspect requests.
Expose actual progress and cancellation; never label an uninstalled model ready.
Status reads must not load gigabytes or do file hashing on the UI thread. A source
revision change does not silently retarget an already frozen accepted study.

## Verification

Test tokenizer against the pinned vocabulary and known CLIP token sequences,
DDIM math against independent expected numeric values, strict admission, stale and
changed sources, retry/identity, cancellation and publication. Execute real ORT
using real model weights in app code; mocks only test failure boundaries and do
not prove neural inference. Inspect actual native reference-conditioned output.
Capture real Android editor review where practical. Record host JNI versus phone
execution separately. A CPU host pass does not prove Android phone speed or RAM.

Target a feature candidate4.10.2/code505;4.10.1/code504 is the signed baseline.
New native and Node contracts require4.10.2; retain earlier feature floors. Run
one local Gradle process, focused checks then combined regression and signed
candidate CI with artifact_run_id empty. No main/stable/Worker deployment or new
signer. Model/output quality remains unverified until actual evidence exists.
