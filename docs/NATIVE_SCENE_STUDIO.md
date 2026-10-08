# Native Scene Studio — 3.5.0 preview

This APK adds executable native code, not just the proposed architecture document. You and ChatGPT use the same background-service operations. The existing 3.4.7 reliability, live-preview and large-transfer changes are integrated with the earlier cloud preview fixes. The original installed application's signing key is unavailable; this build uses the existing separate preview package.

## Use it directly

Install the ARM64 preview and open **AI Tools → Native Scene Studio**. Open VSL Studio, edit the included demo, save scene memory, build a preview, or render native video. Inspect Saved Memory shows immutable revisions and the last run. Inspect Change Map shows green preserved pixels, yellow reconstruction scope and red missing providers. Follow jobs in Activity and use Stop jobs to cancel. Outputs appear in that project's Media Bin; scene renders preserve the existing timeline.

Cinematic Worlds Portal accepts an explicitly imported image or video. Image aspect is preserved within its plane. Video plates are decoded at at most 512 pixels and sampled at 15 fps, with one video decoder per scene. This visual compositor excludes source-video audio, explicitly reported in the operation result and UI. It is not a reconstructed 3D world or the entire Studio Web portal feature set.

RAFT Temporal Motion needs two observed image anchors and an installed, compatible RAFT pack. Neural World Keyframes needs a compatible SD-Turbo pack. Neural Region Replacement generates a text-conditioned region and preserves pixels outside the selected rectangle in the decoded reference raster; references larger than 1920 pixels are downsampled. This is not identity-conditioned inpainting.

Import a licensed model-pack ZIP with Import, then use Install Model Pack. The base APK contains ONNX Runtime, ML Kit portrait analysis and native adapters, but **no RAFT or diffusion weights**. Standard large SD-Turbo exports can exceed this phone's memory budget; the app rejects those phases. Smaller compatible quantized exports require their own validation.

## What the seven components actually do

| Requested component | Implemented in this preview | Remaining work |
|---|---|---|
| Scene Genome | Persistent entities, transforms, camera, lights, appearance and source bindings | Reconstructed real-world geometry and inferred body/hair structure |
| Delta Generation | Revision deltas and retained raster pixels with conservative dirty rectangles | Model-conditioned sparse diffusion, occlusion reconstruction |
| Neural Compiler | Bounded VSL parser, typed operations, hardware plan, semantic cache keys and model admission | Automatic pose/warp/diffusion selection across trained specialists |
| Generative Memory | Source-image hashes, immutable scene revisions and pinned model contracts | Learned face embeddings, costume descriptors and identity-conditioned synthesis |
| Uncertainty Rendering | Conservative green/yellow/red routing map and raster reuse | Calibrated semantic uncertainty and model-based visibility |
| Progressive Intelligence | Real 320p/512p/720p structural previews, native final 720p/1080p export, sequential neural model sessions | Learned detail restoration/upscaling; repeated raster previews do not invent detail |
| Self-Correcting Loop | Sparse/full pixel comparison, bounded cache repair, native decode/luminance/continuity critique | Face, fingers, cloth and lighting semantic critics and conditioned repair |

Procedural analytic cloth/wind, meshes, camera motion, material appearance and diffuse lighting run on-device. Their quality is procedural. Model-dependent paths remain unverified until actual compatible weights execute on a phone. No result asserts that a preserved file hash proves semantic facial identity. Compile reports unsupported requested pose synthesis through `ready:false` and `missingCapabilities`.

## Executable VSL subset

Start with `vsl 0.1`. Use a `scene NAME { ... }` block with `duration`, `fps`, `canvas`, `background`; `object ID`, `subject ID`, `portal ID`, `cloth ID`; and `camera`, `light`, `generate` blocks. Properties use `name = value`. Newlines or semicolons separate statements. `//` introduces comments. Braces do not require separate lines. There is no arbitrary code, filesystem access or network instruction.

- `duration = 4s`; `fps = 30`; `canvas = 720x1280`; `background = "#081326"`.
- Entity types: cube, pyramid, circle, rectangle, ellipse, line, image, video, cloth. At most 128 entities.
- `position = vector(x,y,z)` and `move = vector(x,y,z)` define eased start/end motion across the scene duration. 2D x/y are normalized screen coordinates; 3D x/y/z use the existing renderer's **+Z-forward** world convention. This executable IR differs from the proposed general Scene Pack's -Z convention; no implicit schema import is provided.
- `size`, `width`, `height`, `color`, `rotation`, `spin`, `roughness`. Spin is degrees per second. Roughness changes the bounded diffuse/ambient appearance; it is not a full PBR material.
- `asset = IMPORTED_ASSET_ID` or `identity = lock(IMPORTED_ASSET_ID)` binds image/video content. Subjects default to image. Video entities require `type = video`. File bytes are hashed at commit and verified before execution.
- Cloth supports `wind`, `turbulence`, `physics = analytic_wind`; this is a deterministic procedural sheet, not clothing simulation on an inferred human body.
- Camera: `orbit = 4deg` (degrees per second), `dolly = 1m` (total displacement across scene duration), `fov = 50deg`. Global camera change conservatively invalidates the full raster.
- Light: `direction = vector(-0.4,0.7,-0.59)`, `intensity = 1`.
- Generate: `only = changed_regions` or `full_frame`, `temporal_consistency = strict`, `progressive = true`, `max_repairs = 0..2`, `semantic_validation = unchecked` or `required`. Required semantic validation currently blocks execution.
- `pose = turn_head(...)` records an unavailable synthesis requirement. It does not secretly approximate a new pose by rotating a flat portrait. The broader language in the architecture specification is still a proposed superset.

## ChatGPT operation contract

The existing connector's future-compatible bridge works without introducing a new endpoint:

```json
{
  "action": "native_scene",
  "parameters": {"operation": "capabilities"}
}
```

Call this through **app_execute**. Then create/select the project and use these operations:

| operation | Inputs |
|---|---|
| compile | projectId, script, optional expectedRevision |
| inspect / uncertainty | projectId, sceneName, optional revision for uncertainty |
| preview / render | projectId, script OR sceneName/revision, optional quality and fileName |
| portal | projectId, assetId, optional durationSeconds, quality |
| temporal | projectId, firstAssetId, secondAssetId, packId, optional durationSeconds, aspect, quality |
| neural | projectId, prompt, packId, optional seed |
| repair_region | neural inputs plus assetId and normalized left/top/right/bottom |
| install_pack | projectId, assetId, optional expected archive sha256 |

Read `app_get_command_result` and native job status until terminal; queueing is not completion. Existing `app_compile_scene` and `app_run_motion_script` recognize a source starting with `vsl 0.1`; `render:false` builds a native preview. The source also registers typed `app_native_scene`, but that new relay tool requires deploying the updated Worker. The existing app_execute bridge is the compatibility path. No production Worker deployment or ChatGPT-to-physical-phone session was performed here.

## Optional model ABI

Use `scripts/build-native-model-pack.py` to package local weights, their licence and SHA-256 file manifest. It does not obtain weights, convert incompatible models or prove inference. All file hashes and executable model contracts are pinned. Replacement of the pack while a durable job is waiting requires replanning.

RAFT uses backend `onnx-raft-v1`, a `raft` manifest with model path, width/height (multiples of 8, max 480×360), firstInput, secondInput and final output tensor name. Inputs are RGB float32 CHW 0..255, `[1,3,H,W]`; output is finite float32 `[1,2,H,W]` or `[1,H,W,2]`. Bidirectional flows drive native mesh interpolation. No occlusion synthesis or validated identity preservation is claimed. Estimated native working set is admitted conservatively; arbitrary tiling of global RAFT correlation is not offered.

SD-Turbo uses backend `onnx-sd-turbo-v1` and a `neural` manifest with textEncoder, unet, vaeDecoder, vocabulary, merges, hiddenSize (normally 1024 for SD-Turbo; compatible exports may use 768), special tokens, and phaseWorkingSetMb. The supported scheduler is one epsilon step at timestep 999, sigma 14.6146, VAE scale .18215. CLIP byte BPE emits 77 token IDs. Text input_ids may be int32/int64; last_hidden_state is float32 `[1,77,hiddenSize]`. UNet takes float32 sample `[1,4,64,64]`, int64 timestep `[1]`, matching hidden states, and produces float32 out_sample. VAE takes float32 latent_sample and produces float32 sample `[1,3,512,512]`. Exports with different I/O types, names or architectures need another adapter/conversion. Each session closes before the next model is loaded. Source-text normalization is the documented native CLIP subset, not an independently validated match to every external tokenizer variant.

Learned face/body descriptors and semantic repair models are not implemented by these two adapters. Their absence remains an explicit capability gap.

## Verification and current blockers

Android JVM/Robolectric tests validate real native Canvas sparse/full pixel equivalence, immutable revision reopening, stale-writer rejection, tamper detection, changed identity inputs, region scope preservation, tokenizer contracts, seeded noise, compilation bounds, import/recovery behavior and the existing native suites. These checks do not exercise phone hardware codecs, Android ONNX inference or actual learned weights. The Worker tests and a local MCP registration/tool-call/native-poll round trip verify control routing. The relay dry-run builds successfully.

AAC export now requests a standard 128000-bit/s profile, enables Media3 format fallback and normalizes video-source audio to 48 kHz. This is a mitigation for the supplied codec exception, not proof that the Realme failure is fixed. A real image import, portal export, audio export, cancellation and process-death recovery need phone validation.

Hugging Face model metadata/download access currently fails at the managed proxy with CONNECT 403. The environment draft preserves existing relay access and adds huggingface.co; review/save and publish/apply it in environment settings before further model-download checks. This does not affect direct procedural native features. A read-only OpenCV Zoo pointer identified its int8 RAFT file's published digest and size, but the actual weight download could not be obtained; no pointer was passed off as a model.
