# Native Plus graph conversion — provisional4.10.8, feasibility slice

This isolated branch starts from frozen76fb9a0 (4.10.7/code510). No version,
model/provider selection, canonical action or public capability is changed here.
Current app users still use the existing providers. This slice is model-file
engineering, not artwork, inference, stronger likeness or an animation scenario.
Root owns all Gradle and actual model execution across worktrees.

## Public model evidence

The public AbrahamPJ/nightmare-ip-adapter repository at immutable
 a6a155398a39aa398677da3b9674d23b284082ad provides standard CPU ONNX opset17 heads:

- ip_plus_head_w8qdq.onnx,49,514,510bytes,
  SHA52b2ef055e97b473ccb91dedf9e79af7783a4469793f6bcb51d22c3dfd5741c8.
- ip_face_head_w8qdq.onnx,49,514,510bytes,
  SHAd30b57dfb4f90198486901143eae5ba8d8f2179b8677e475fcb225e696826c33.

Both were independently downloaded/hash-verified and inspected without model
execution. FP32 hidden[1,257,1280] maps to32FP32 K/V outputs for16image tokens.
Public cards declare Apache2.0 for adapters and MIT for OpenCLIP. No user artwork
was sent anywhere. No character training or anime likeness result is established.
The matching published Swap-v2 UNet is Snapdragon QNN-only; a bounded HTTP206 zip
central-directory read confirmed .bin contexts, not a drop-in CPU ONNX graph.

Our existing pinned CLIP's extra257x1280 output is final layer31, not Plus's
required penultimate layer30. Full dependency inspection shows28actual old
image-input-dependent UNet nodes, all under encoder_hid_proj, and32replacement
KV cut-points. A prior inference that generic /unet/Shape names existed outside
that prefix was corrected; the generic-name regression remains synthetic. Public
research evidence is under /workspace/work/native-ai-token-conditioning-research.

## Actual native converter

LocalDrawingPlusGraphConverter is app Java code, ready for a normal native model
install worker to call once derived graphs are independently verified and pinned.
It performs no ONNX Runtime or artwork operations. No workspace converter is
required by the intended user workflow; model installation integration is pending.

- Explicit native recipe identifiers clip_penultimate_v1 and sd15_ipkv16_v1.
- Both source and deterministic derived SHA256 are mandatory. The future native
  catalogue must pin them; they are not caller-selected MCP model identities.
- Seekable bounded protobuf scanning skips large tensor payloads. A node has at
  most1MiB metadata; edit storage is bounded4MiB, output writes64KiB, source file
  at most3GiB. No whole-weight file or model runtime is loaded into RAM.
- CLIP adds a named Identity/output for the actual layer30 tensor. Original
  outputs/weights remain, and the original source file is read-only.
- UNet removes image_embeds and prunes its obsolete projection nodes, including
  generic-name transitive dependencies. Explicit16layer order is down/up/mid,
  independent of physical down/mid/up node order.
- Real K transpose [1,inner,16] to[1,16,inner], V Identity, and only the32IP
  reshapes become[1,16,8,inner/8]. New graph has48inputs: original sample,
  timestep,text plus ALL13original FP16/NCHW residuals, and32FP16 K/V inputs.
- Required backbone inputs must remain used; missing/duplicate IP sites, changed
  residual layout/dtype, external tensor storage, malformed protobuf, unsafe paths
  and mismatched pins reject. Opaque original initializer bytes are streamed.
- Private .plus_ files alone are cleaned on cancellation/failure. Existing target
  and source remain until expected derivedSHA passes, then publication uses an
  atomic move. A mismatch reports the observed public model digest for engineering
  discovery but does not publish or accept it as a trusted installation.

## Fresh tests and boundaries

Root observed initial and separately frozen strengthened RED:5tests/5missing-
converter failures/0errors. First GREEN had3failures from the synthetic fixture
omitting any timestep consumer. That useful backbone guard was retained; the
fixture now includes an ordinary retained timestep consumer in source and byte
Golden. Root GREEN2:5tests/0failures/0errors/0skips. All failed logs/source/XML and
GREEN evidence remain under native-ai-token-conditioning-research.

These structural protobuf fixtures are not executable neural models. They test
exact derived bytes/hashes, source/opaque-weight retention, all48typed ports,
explicit KV order/layout, IP-only reshapes, projection pruning, malformed/changed
inputs,64KiB streaming, mid-copy cancellation and old-target preservation.
No actual multiGiB graph conversion or ORT compatibility result is claimed yet.

## Required next gates

1. Opt-in actual native conversion of already pinned public weights: discovery
   without publication, then a separate frozen expectedSHA rerun. Independently
   inspect output signatures and original source hashes; retain both evidence sets.
2. Actual VideoStudio ORT CPU probe must load the converted graphs and verify
   finite image/structure routes and zero-V neutral equivalence. Shape declarations
   and synthetic converters cannot prove runtime validity.
3. Plus unconditional conditioning must execute CLIP on an all-zero NORMALIZED
   pixel tensor and pass actual penultimate tokens through the selected head;
   a zero token array or pooled-vector alias is incorrect. This runtime rule is
   planned, not implemented or proven by the converter.
4. True adapter strength scales per-layer V; keep ordinary text guidance separate.
   Measure actual CPU passes, cancellation and RSS/session lifetimes.
5. Integrate deterministic pinned conversion into existing ordinary native model
   installation, add shared manual/MCP provider selection/provenance and floors,
   and preserve every omitted old request/model/fingerprint behavior.
6. Actual app-native character drawings under frozen pose/reference inputs need
   visual review. No exact likeness, unseen views, temporal consistency or finished
   four-minute film follows from this engineering slice.

## Actual native conversion and CPU routing (root executed)

The opt-in LocalDrawingPlusGraphConversionStudyTest first ran discovery without
publication (native-conversion-v1), then a separate frozen-pin verification into
native-conversion-v2. Original public model hashes remained unchanged. Verified:

- CLIP1,264,856,252bytes,
  SHA8f6c9781e0a6de096ac0e6160e067f8b94001dabef279f7fa70dff150e4e454a.
- UNet1,764,916,898bytes,
  SHAd53c027c7a0ea50f737fe57a2f68ab5f980b0c6199c4980cb96ff66de5da00f6.

Native verification passed36seconds with host-process HWM366944kB, no model
runtime loaded. Independent streamed protobuf/payload hashing then proved ALL
original initializer payloads byte/hash-identical in both derived files, and all13
original residual ValueInfo byte strings unchanged. Actual UNet delta is36deleted
projection nodes (28data-dependent +8Constant),64changed KV/reshape nodes,16added
shape initializers and48inputs. CLIP adds1Identity/output, changes no old nodes.
Both source and derived graphs have zero intermediate value_info records.
Independent evidence: native-conversion-v2-independent-byte-audit.json.

Root then ran LocalDrawingPlusGraphRuntimeProbeTest through the actual shared
VideoStudio ONNX Runtime1.23.2 CPU/JNI runner. Sequential sessions:2CLIP passes,
2PlusFace-head passes,1actual ControlNet pass,5converted-UNet passes. All48typed
ports,13residual ports and32KV outputs were checked. Finite appearance-route mean
absolute delta0.0025244166 and actual structural-route delta0.0377554564 prove
numeric influence. Different real K arrays with allV=0 produced EXACT0difference.
Unconditional tokens came from actual CLIP(all-zero NORMALIZED pixels), whose
nonzero hidden RMS0.80455408 rules out a zero-hidden-array substitute. Host-process
HWM3870288kB; not phone RAM/performance or GPU execution.

This is a numeric graph probe, not image synthesis, likeness acceptance, old-path
equivalence, mastered views or an animation scenario. No VAE decode or full
schedule ran. Evidence/log/XML and source snapshots remain native-runtime-v1.
The optional study drivers explicitly distinguish discovery/verification/runtime
and skip without their dedicated environment variables; their envs bypass Gradle
result caching so changed configs cannot silently reuse a previous probe.

Old-path equivalence remains next: append32original4-token KV outputs to the
otherwise untouched original UNet in an app-native private audit recipe, capture
its real outputs, close that session, repeat each token4times and compare the new
16-token graph. BEFORE measuring, the FP16 budgets were frozen at maxAbs0.02 and
RMS0.003. Nonzero results must be reported as tolerances, never pixel identity.

## Append-only legacy audit graph

A private sd15_legacy_ipkv_probe_v1 recipe now appends32typed FP16[1,4,inner]
outputs for existing to_k_ip/to_v_ip MatMuls, in explicit down/up/mid head order.
It verifies the original17typed ports and projection identities, and preserves
EVERY old graph field unchanged. This is an engineering-only graph, never the
installed generation UNet. It shares the mandatory source/derived hashes,
64KiB copying, cancellation and atomic-publication protections.

Root observed the new structural RED fail on the missing recipe, then6converter/
audit tests pass with zero failures/errors/skips. Exact byte-golden tests cover
all old fields, nodes/weights/inputs/noise output and the32append-only outputs.
RED fixture/XML and GREEN XML remain under legacy-kv-red* /legacy-kv-green-junit.

The opt-in conversion driver has a legacyAuditOnly discovery/verify mode, so it
will produce a separately pinned graph without rewriting the frozen16-token
outputs. A separate opt-in equivalence driver requires its frozen verification
receipt, runs old/new sessions sequentially and archives actual returned noise
buffers. Its predeclared FP16 limits remain maxAbs0.02/RMS0.003. Actual audit
conversion and equivalence execution have not yet been completed at this commit.

## Shared native Plus provider implementation (not yet artistic acceptance)

Explicit optional `conditioning.adapter` chooses `plus_face` or `plus` through
ordinary `local_drawing_generate`; omission retains the existing basic provider,
its model catalogue, fingerprint and4.10.7 floor. Both explicit options select
`sd15_ipadapter_plus_controlnet_v1` and require4.10.8 in ordinary/recursive MCP
requests and portable inline provenance. Manual choices are Basic appearance,
Face details and Overall appearance. The same no-model preview still returns the
three actual prepared source/appearance/Canny PNGs without creating a study.

The new catalogue pins every original source and every derived result. Normal
`local_drawing_install` resumably downloads source graphs into its own private
`.native_sources`, invokes the bounded Java converter, verifies final hashes,
cleans only verified private source files and activates through ModelPackManager's
normal license/checksum/transaction path. Failed/cancelled writers retain source
checkpoints and previous target files. Private leftover sources are explicitly
excluded from pack activation. No workspace-only conversion is required by the
user. The existing base/basic catalogues are byte-unchanged. Optional Plus pack
contains4,680,923,615model bytes and requires64-bit execution and8192MiB currently
available RAM; host numerical probes do not establish phone performance.

New LocalDrawingPlusEngine uses actual CLIP layer30 hidden states, the selected
verified CPU PlusFace/Plus resampler,16K/V tensors in explicit down/up/mid order,
and all13ControlNet residuals. Both CFG branches get their real image-conditioning
head output; the unconditional branch is CLIP(all-zero NORMALIZED pixels) through
the same head. Appearance strength multiplies the actual V tensors in all16
layers, not embedding values or noise prediction differences. It therefore has
different semantics from the preserved basic provider. It uses two UNet passes
and at most two ControlNet passes per denoising step. CLIP/head sessions close
before UNet/ControlNet loading, and these close before VAE decoding. Attention,
text, VAE and denoising checkpoints bind the complete request/model identity.

The same added-noise seed and three input PNGs do not alone produce a controlled
provider-only comparison: the existing VAE encoder posterior is sampled and then
checkpointed independently of that seed. Actual studies must identify this and
retain checkpoint hashes where possible. Do not copy cross-request checkpoints
or claim pixel identity/provider causality from superficially matched settings.

Focused normal native surface tests passed via root: omission, explicit modes,
preview/failed-model admission/retry conflicts, manual selector, source safety,
archive/manual provenance and scoped numeric identity. Local drawing Node suite
15/15 passes. Root then observed14/0failures/0errors/0skips across the five private-install,
three Plus portable-provenance, five normal-provider tests and actual v4 audit
verification. Corrected audit SHA89329b002191ac7f99e761ab4cdd803da9f96be7d0c2d07a8a64862dd69a41ee. Complete
normal installed-model inference and output review remain pending.

The first append-only old-KV runtime study exposed actual rank4 projections
`[1,1,4,inner]`; its rank3 metadata/assertion failed before any numerical comparison.
Both failed actual runtime and wrong-rank audit graphs remain preserved. The
metadata and synthetic golden contract now explicitly retain that leading
singleton axis; new discovery/verification use fresh output directories/pins.
Numerical equivalence budgets remain maxAbs0.02/RMS0.003, unchanged.

Actual equivalence v2 captured all rank4 old K/V tensors, then correctly rejected
the converted graph path because the engineering storage relocation made it a
symlink. It failed before numerical comparison. Preserve this failure; use the
independently hash-verified regular target path for a new runtime trial without
weakening the production anti-symlink policy.
