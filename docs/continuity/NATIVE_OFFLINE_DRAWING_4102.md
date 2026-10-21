# Native offline drawing, 4.10.2 / code505

This feature extends36c2257 on `feat/native-offline-drawing-v4102`. The signed
4.10.1/code504 product remains frozen at0a4d74c in its own worktree. Other paused
worktrees and the isolated old4.8.8 orientation regression were not modified.

## Implemented capability

VideoStudio now executes real pretrained SD1.5 ONNX inference locally through
ONNX Runtime1.23.2: CLIP byte-pair tokens, reference VAE encoding, bounded DDIM
classifier-free denoising and VAE decoding. Android performs reference decoding,
integer ROI cropping before filtering, aspect fit and PNG encoding. No hosted
inference, external process, procedural-art fallback or Gallery enumeration.
This is integration of a licensed pretrained model, not training a new model.

Model source: `onnx-community/stable-diffusion-v1-5-ONNX`, immutable revision
`90ff1f14e325544b14919d1ee5abc99f19d4e647`. The APK catalogue pins all10 core
files,4,268,238,533bytes total, with SHA256. CreativeML OpenRAIL-M text ships
in APK assets and is retained in the installed pack. Weights download separately
from public HTTPS model storage; that is distinct from inference. Telemetry is
disabled. The CPU adapter requires a64-bit process and6144MiB currently available
RAM. Download/transactional activation needs roughly twice the pack size plus
existing app storage reserve. These are real restrictions, not universal phone
compatibility or measured phone performance.

`Sketch Studio > More > Local AI drawing` selects an explicitly imported image,
instructions and optional source region/settings. Generate creates a separate
project and immutable512x512 opaque raster, preserving the original sketch.
Resume uses saved source/model-bound conditioning and latent checkpoints.
Changed request IDs/settings conflict; completed requests replay without models.
Use draft as a new drawing guide imports it through ordinary native Sketch
operations, retaining its AI-raster provenance rather than pretending to recover
editable artist strokes. Existing layered pen/paint authoring remains available.

MCP and manual controls share `local_drawing_models`, `local_drawing_install`,
`local_drawing_generate` and `local_drawing_get`. New canonical/alias contracts
require4.10.2; older actions keep their floors. Node/native capability JSON agrees.
MCP mutations use existing durable JobManager recovery. Manual Activity work
cancels on destruction and keeps checkpoints for explicit resume; it is not a
claim of continuous background execution after Activity destruction.

## Validation and quality boundaries

The preliminary1.20.0 host/JNI integration study completed inference, independent PNG
hash/preview checks, separate-project registration, no-inference replay, unchanged
original source/document, native archive restore and replay after model removal.
Private evidence: `/workspace/work/native-ai-models/`. Host admission fixtures
simulate Android MemoryInfo and64-bit status and are explicitly labelled. These
runs execute actual app code but do not establish installed APK/phone/GPU speed.
Final committed-source evidence and full CI results must be read from the linked
candidate artifacts; do not copy a historical run as fresh validation.

Preserved RED evidence includes tokenizer legitimate `#` merge rules skipped by
an overbroad comment check; excluded neighboring pixels leaking into a fitted ROI;
and the test fixture attempting cross-filesystem hardlinks. Pixel assertions and
old feature-floor assertions were retained. Integer crop-before-filter fixes the
actual bleed. Expected ORT termination stderr comes from the real cancellation
regression, not an ignored test failure.

Seed controls added denoising noise. This pinned encoder samples a posterior;
its sample is checkpointed, so retry uses the saved sample. The same seed on a
new independent request is NOT promised to reproduce all pixels. Model verification
is a frozen, checksum-verified lease, not trusting arbitrary manifests as engines.
Cancellation terminates an in-flight ORT run, not only between steps.

Actual portraits have richer hair and facial shading but still change likeness,
can interpret speech-bubble pixels as hair and do not reliably achieve pencil
hatching. Visual status remains NEEDS_REWORK. No perfection, photorealism,
correct hands, hidden-view reconstruction, semantic character layers, temporal
consistency or completed animation is certified. The240s film and voice tab remain
unfinished. Model outputs must be reviewed, not accepted through test counts.

Live private app MCP returned HTTP403 from this workspace. No phone installation,
MCP identity reset, stable publication, main merge or Worker deployment is claimed.
The original APK signer must be independently checked on the candidate from the
existing signing-cache workflow. Never generate a fallback signing key.

Native dependency inspection caught1.20.0 JNI ELF LOAD alignment0x1000, despite
the main ORT library using0x4000. Both ARM64 libraries in the selected1.23.2 AAR
use0x4000. Numeric JNI/cancellation tests passed against1.23.2. Final real-weight
receipts record the actual runtime version. This is a library inspection, not
execution on a16KiB-page phone. Tokenizer golden fixtures are public licensed
text resources in tests, not neural weights; routine CI checks them without a
4GiB download. Invalid source attempts no longer create quota-consuming empty
studies. Retained candidate PNG size, SHA256, MIME and dimensions are checked.
