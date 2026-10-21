# Self-hosted open-source generation

VideoStudio sends inert `.vsjob` archives to an owner-controlled computer. The
runner executes local Blender, CogVideoX-2B with Diffusers, PNG-sequence encoding,
or a configured RIFE/FILM adapter. It uses no Veo, Kling, Runway, paid generation
API, or subscription. A Drive mount stores requests and results; it does not
perform inference or establish OAuth access.

Use Python 3.10 or later and FFmpeg/FFprobe. Blender needs the existing
`cinematic3d` requirements, including genuine Rigify. For CogVideoX, install a
compatible local Torch/Diffusers/Transformers/Accelerate/SentencePiece runtime.
The verified cloud environment uses Torch 2.8.0, Diffusers 0.35.2,
Transformers 4.57.1, Accelerate 1.10.1 and SentencePiece 0.2.1. Model downloads are
an operator setup action; jobs cannot supply URLs, executables, model paths,
Python code, or remote-code authorization.

An operator configuration, outside Git, can contain:

Hindi/English dialogue is an optional additional `kokoro` backend. Its native
manual form, MCP contract, pinned model preparation and PCM project import are
documented in [Character dialogue](CHARACTER_DIALOGUE.md). The video model
configuration below remains independent of the speech model.

```json
{
  "schema": "videostudio.openpipeline.config.v1",
  "root": "/srv/videostudio/jobs",
  "pythonExecutable": "/srv/videostudio/venv/bin/python",
  "blenderExecutable": "/usr/bin/blender",
  "host": "127.0.0.1",
  "port": 8787,
  "tokenEnv": "VIDEOSTUDIO_RUNNER_TOKEN",
  "jobTimeoutSeconds": 7200,
  "cogvideox": {
    "modelPath": "/srv/videostudio/models/cogvideox-2b",
    "manifestSha256": "REPLACE_WITH_ACTUAL_MANIFEST_SHA256",
    "revision": "1137dacfc2c9c012bed6a0793f4ecf2ca8e7ba01",
    "device": "cpu",
    "dtype": "bfloat16",
    "cpuThreads": 4,
    "minAvailableRamGiB": 12,
    "maxWidth": 720,
    "maxHeight": 480,
    "maxFrames": 49,
    "maxInferenceSteps": 50
  }
}
```

The model is the public Apache-2.0 `zai-org/CogVideoX-2b`. Its local
`videostudio-model-manifest.json` must contain schema
`videostudio.openpipeline.model.v1`, the exact `modelId`, pinned `revision`,
`license`, and `files: [{path, bytes, sha256}]`. Every regular model file must be
declared. Symlinks, pickle weights, scripts, missing files, unbound files and
changed checksums are rejected. Only local Safetensors weights are loaded;
network access and remote code are disabled for the worker. The operator pins
the manifest SHA-256 in trusted configuration. Full file hashes are verified
before model loading and again after inference. The configured RAM threshold is
an admission policy, not a guarantee that every requested resolution fits.
Available RAM accounts for reclaimable inactive file cache inside the cgroup.

Set a strong bearer token in the configured environment variable, then run from
the checkout:

```bash
python -m openpipeline capabilities --config /srv/videostudio/runner.json
python -m openpipeline serve --config /srv/videostudio/runner.json
```

The HTTP service binds loopback by default. Expose it to the phone through an
owner-configured HTTPS reverse proxy or private secure tunnel. Never put the
bearer token in the repository, request archives, command arguments, or logs.
The native application stores its owner-configured endpoint and bearer locally.

HTTP endpoints are:

| Method and path | Contract |
| --- | --- |
| `GET /v1/capabilities` | Actual configured admission states; execution evidence remains false until a job executes. |
| `POST /v1/jobs` | Raw `application/vnd.videostudio.vsjob`, `Idempotency-Key: requestId`, exact archive `X-VideoStudio-SHA256`, one `Content-Length`. |
| `GET /v1/jobs/{jobId}` | Durable status with exact request ID and request JSON hash. |
| `POST /v1/jobs/{jobId}/cancel` | Empty body; terminates the managed worker process group. |
| `GET /v1/jobs/{jobId}/result` | Completed `.vsresult`, exact `Content-Length` and mandatory lowercase `X-VideoStudio-SHA256`. |

All endpoints require `Authorization: Bearer …`. Uploads have size and time
budgets, and the service permits at most sixteen concurrent HTTP connections.
Downloads currently return the complete archive; native interrupted transfers
safely restart when Range is unavailable.

An exact request is:

```json
{
  "schema": "videostudio.openpipeline.request.v1",
  "requestId": "cpu-cog-smoke-001",
  "projectId": "cloud-smoke",
  "sourceProjectUpdatedAt": 0,
  "backend": "cogvideox",
  "controls": {
    "prompt": "A red cube slowly rotates.",
    "width": 64,
    "height": 64,
    "numFrames": 9,
    "fps": 8,
    "seed": 17,
    "numInferenceSteps": 2,
    "guidanceScale": 6
  },
  "sources": []
}
```

This tiny request is a runtime probe; it does not imply production video quality.
CogVideoX dimensions must be multiples of sixteen, frame count must be `8k+1`,
and configured limits apply. Optional `negativePrompt` is supported.
`mode` defaults to `text_to_video`, which requires an empty source list and
rejects reference sources with `REFERENCE_CONDITIONING_UNAVAILABLE`.
The pinned 2B VAE decodes exact clocks for odd latent frame counts, so 9, 17 and
49 output frames are admitted while 13 is rejected before inference. Decoded
output must match the requested count exactly; the runner never pads or silently
trims a mismatch, and its failure reports both requested and observed counts.

For reference video conditioning, set `mode: "video_to_video"`, add exactly one
hash-bound `video/mp4` source, and provide `strength` from 0.05 to 1. The actual
`CogVideoXVideoToVideoPipeline` loads the same pinned local 2B weights and takes
the prepared source frames. The adapter does not route this mode through the
text-to-video pipeline. Strength is forbidden in text-to-video mode.
`floor(strength * numInferenceSteps)` must be at least one; zero denoising is
rejected with `V2V_ZERO_DENOISING_STEPS`, and the execution receipt also records
the actual denoising callback count.

The runner probes and decodes the owned MP4 locally. Source duration must be
positive and at most 600 seconds, its decoded raster must fit 4096 by 2160 pixels
with neither dimension above 4096, and measured source FPS must be at most 120.
Requested timestamps are `i / fps` for `i = 0 .. numFrames-1`; the last timestamp
must precede the measured source duration, and the actual decoded source frame
count must be at least the requested count. Missing timing, a short clip, bad
hashes, or incomplete decoding fails the job before model inference.

FFprobe measures decoded presentation timestamps. The preparation stage selects
the nearest measured source frame for each requested timestamp, chooses the
earlier frame on ties, and records any repeated frame identities when upsampling.
FFmpeg decodes those exact selected indices and resizes the full oriented raster
with Lanczos to the explicit requested dimensions. It does not extend a short
reference clip. `actualExecution` retains mode, strength, `sourceClipSha256`, and
`inputSampling` with the measured source duration/raster/FPS/frame count and every
ordered requested timestamp, selected source frame index/timestamp, and prepared
PNG byte count/hash. Prepared reference PNGs remain in the runner's private job
generation. Source conditioning does not establish exact reconstruction or
cinematic quality.

Blender controls contain `timeline` and optional `renderFrames` and
`conditioningFrames`. Conditioning is an ordered prefix array `[0,1,…,N-1]`,
up to 1200 frames. Genuine conditioning sidecars stay under the local generation
directory and remain outside the phone archive. The native application may
apply a smaller metadata limit. Sources referenced by timeline anchors must
have matching asset IDs and SHA-256 values. Procedural or estimated geometry
retains its existing provider and reconstruction labels.

`frame_sequence` uses `{fps,width,height}` and an ordered PNG source array.
`interpolation` uses `{inputFps,outputFps,cuts?,hitStopRanges?}` with ordered PNGs,
strict interior cut boundaries, and end-exclusive nonoverlapping hold ranges.
Configure `interpolationConfig` to point to a trusted existing
`videostudio.cinematic3d.local-config.v1` file with its RIFE/FILM command and
hash-pinned weights. The existing adapter executes actual configured inference
and protects original samples, cuts and holds. Missing configuration or weights
reports `waiting_model`. ComfyUI's existing image adapter is separate; this
runner does not claim sequence-conditioned ComfyUI video support.

Every source record is `{assetId,path,bytes,sha256,mime}`. Archives contain
exactly `request.json` and its declared files, at most 64 sources. Request hashes
refer to the exact JSON bytes, including formatting; archive hashes refer to
the exact ZIP bytes. The budgets are 256 MiB compressed, 512 MiB expanded,
512 KiB JSON and 256 entries. Traversal, symlinks, duplicate keys/entries,
undeclared files, encrypted ZIPs and executable artifacts fail admission.

For synchronous local execution:

```bash
python -m openpipeline pack --request request.json --sources ./inputs --output request.vsjob
python -m openpipeline run --config /srv/videostudio/runner.json --job request.vsjob
python -m openpipeline status --config /srv/videostudio/runner.json --job-id JOB_ID
python -m openpipeline cancel --config /srv/videostudio/runner.json --job-id JOB_ID
```

The job root retains SQLite state, immutable inputs, stage/progress checkpoints,
heartbeats, bounded private worker diagnostics, local backend outputs and an
immutable `result.vsresult`. A repeated request ID with identical request bytes
returns the same job; changed bytes produce `IDEMPOTENCY_CONFLICT`. One worker
executes at a time. Deadlines and cancellation terminate its entire process
group. Restart kills a surviving authenticated worker and resumes a verified
completed backend output checkpoint, or restarts an interrupted backend from
its immutable inputs. CogVideoX diffusion latents are not resumable mid-step;
stage checkpoints report progress, not a claimed latent-resume capability.
Waiting model/compute jobs retain their original deadline.

Completed result manifests use `videostudio.openpipeline.result.v1` and bind
`jobId`, `state: completed`, `requestId`, exact `requestSha256`, `projectId`,
`sourceProjectUpdatedAt`, `files: [{path,role,mime,bytes,sha256}]` and
`actualExecution`. Video is published only after FFprobe checks decoded count,
FPS, dimensions and duration. Native import accepts video MP4 and cinematic
bundles into a fresh output project. Cloud output is labelled
`nativeRendered: false`; a hash, inference run, or encoder result does not prove
Android rendering, cinematic acceptance, original-image reconstruction, or
model quality.

To use a mounted Drive/rclone exchange, configure both `driveInbox` and
`driveResults` to existing mounted directories. Upload an immutable
`requestId.vsjob`; the watcher freezes and verifies it before submission and
publishes the native-compatible flat `requestId.vsresult`. Copies stream within
the archive byte budget, verify source stability, and converge through staged
and final SHA-256 readback. Directory traversal uses file descriptors with
`NOFOLLOW`, including ancestor directories; symlinks cannot redirect a copy.

Publication first tries a create-only hard link. On a mount that lacks hard-link
support, it falls back to Linux `renameat2(RENAME_NOREPLACE)`, which atomically
promotes the staged file without overwriting a competing generation. Matching
existing bytes are reused; different existing bytes produce a conflict and stay
intact. If the mount supports neither primitive, the verified partial remains
under a deterministic result-hash-bound generation filename. Retries reuse that
one partial, and no final result is claimed. The private job
`mount-exchange.json` receipt, also exposed through status `mountedExchange`,
reports `waiting_storage`, `MOUNT_ATOMIC_PROMOTION_UNAVAILABLE`, the retained
partial, and `published: false`. A later supported promotion can finish it.

Mount readback proves bytes visible to this runner. Remote Drive persistence
remains explicitly unverified, and unsupported directory fsync is reported
separately. The native folder-scoped Drive workflow performs its own
storage/import checks. HTTP result downloads retain their existing immutable
archive behavior independently of mounted publication.

Focused checks are `python -m unittest discover -s tests/openpipeline -v`.
They cover real FFmpeg encoding and reference-video sampling, exact byte
bindings, durable idempotency, interpreter selection, malicious archives,
deadlines, HTTP authentication, real process-group cancellation, atomic rename
fallback, competing publication, symlink guards and unsupported mount recovery.
The FUSE fallback tests simulate its unavailable hard-link primitive while
executing the real Linux no-replace rename; they do not establish access to a
particular Drive mount. Actual Blender/model execution requires its own observed
runtime run; unit fixtures do not stand in for inference.
