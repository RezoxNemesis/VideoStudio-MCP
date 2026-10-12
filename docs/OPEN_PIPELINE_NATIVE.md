# Native open-source generation and Drive exchange

Open Animation Workbench connects the native app to an owner-controlled inference computer. It has no commercial generation provider integration. The native Activity and `open_pipeline_*` actions call `OpenPipelineOperations.execute` with the same validation and persistent request journal.

On the phone, open a project and enter Open Animation Workbench. Save your HTTPS runner address and access token, then check readiness. The token uses a separate Android Keystore key; it is absent from operation receipts and artifact archives. A configured endpoint alone does not establish model readiness or successful inference.

CogVideoX-2B supports text-to-video and real Diffusers video-to-video conditioning using the same verified model weights. Text mode has no reference sources. Video mode requires exactly one project-owned MP4 and an explicit strength from 0.05 to 1.0; the runner verifies and samples that clip at the bounded requested frame clock. The native form provides both modes and numeric strength controls. Direct still-image conditioning requires a different model adapter and remains unavailable. Model-generated output does not claim exact original-image reconstruction.

The controls require width and height divisible by 16 and `numFrames = 8k+1`. A clearly labelled 64×64, nine-frame, two-step CPU preset provides an execution probe, not production-quality evidence. Model weights remain on the inference computer or its configured mounted Drive, never copied to the phone by this workflow.

Blender accepts a validated cinematic timeline and optional rendered-frame count. Conditioning indices are a contiguous prefix `[0,1,...,N-1]`, bounded to 512 frames on this native surface. Frame sequencing and interpolation use an explicitly selected, ordered PNG sequence. Interpolation preserves explicit cut boundaries and end-exclusive hold ranges. Configured interpolation models are still required.

## Artifact protocol

An immutable `<requestId>.vsjob` contains `request.json` and exactly its declared input files. Its metadata schema is `videostudio.openpipeline.request.v1`:

```json
{
  "schema": "videostudio.openpipeline.request.v1",
  "requestId": "owner_chosen_unique_id",
  "projectId": "native_project_id",
  "sourceProjectUpdatedAt": 123,
  "backend": "frame_sequence",
  "controls": {"fps": 24, "width": 720, "height": 1280},
  "sources": [{"assetId": "selected_asset_id", "path": "inputs/0.png", "bytes": 1234, "sha256": "<64 lowercase hex characters>", "mime": "image/png"}]
}
```

`requestSha256` hashes the exact `request.json` bytes. `archiveSha256` separately hashes the ZIP bytes. A repeated request ID must keep the same project revision, ordered source selection and generation controls. Changed original bytes or registrations invalidate submission and import.

The authenticated runner endpoints are:

| Operation | Endpoint |
|---|---|
| Read readiness | `GET /v1/capabilities` |
| Submit immutable ZIP | `POST /v1/jobs` |
| Poll job | `GET /v1/jobs/<jobId>` |
| Cancel job | `POST /v1/jobs/<jobId>/cancel` |
| Download completed ZIP | `GET /v1/jobs/<jobId>/result` |

Submission uses `Content-Type: application/vnd.videostudio.vsjob`, `Idempotency-Key: <requestId>` and `X-VideoStudio-SHA256: <archiveSha256>`. Every HTTP request uses the protected bearer credential and refuses redirects. Job receipts retain exact `jobId`, `requestId`, `requestSha256`, state, checkpoint stage, heartbeat and deadline metadata. Result downloads require an exact `Content-Length` and `X-VideoStudio-SHA256`; partial downloads use the existing resumable transfer journal.

A `.vsresult` manifest uses `videostudio.openpipeline.result.v1`, binds the completed job and original request hash/project revision, and declares every passive output file with path, role, MIME, byte count and SHA-256. Native import accepts primary `video`/`video/mp4` MP4s or `cinematic_bundle`/`application/vnd.videostudio.cinematic3d` archives. MP4 container metadata is checked; cinematic archives pass the existing source-bound bundle validator. Hash verification does not authenticate an external producer or certify animation quality.

Verified outputs are imported into a separate deterministic output project. Repeating import preserves an existing matching output rather than creating another project. The source project remains unchanged. Imported outputs carry `nativeRendered:false` and `cinematicAcceptance:false`; the phone must render/export through its native engine to establish native execution evidence.

## Drive exchange and local storage

Link one writable folder through the native Control page. The app retains only that folder's Android SAF capability. It does not infer a remote Drive folder ID, OAuth credential or runner access from the capability URI.

Artifact paths are `Projects/<projectId>/Requests/<requestId>.vsjob` and `Projects/<projectId>/Results/<requestId>.vsresult`. Configure the inference computer separately to watch the same folder through its own authorized local synchronization or mount. Export and archive the request, let that computer publish the result, then restore the result using its exact SHA-256. The result must still match the retained native request and source revision. This supports Drive exchange without an HTTP runner connection on the phone.

Archives and expanded results are limited to 256 MiB and 512 MiB respectively, with at most 64 source assets and 256 ZIP entries. Private request retention has a 64-request/2 GiB admission budget. Transfers and extraction check storage reserves before writing. Original source media and final output files are preserved; this workflow does not delete source media or bulk-download cloud model weights.

Project archives now use immutable `Generations/<generationId>` directories, per-file hashes, full remote readback and a `commit.json` written last. Offload must call `verifyArchiveForEviction` immediately before deleting eligible working intermediates. Restore verifies the entire generation in protected staging and requires the exact current native project revision before replacing files. Legacy archives require explicit recovery and cannot authorize eviction.

## Native operation surface

| Action suffix after `open_pipeline_` | Parameters |
|---|---|
| `configure` | `endpoint`, `bearer` |
| `capabilities`, `list` | none |
| `export_request` | `projectId`, `sourceProjectUpdatedAt`, `requestId`, `backend`, `controls`, ordered `assetIds` |
| `submit`, `status`, `cancel`, `download_result`, `import_result` | `requestId` |
| `archive_artifact` | `requestId`, `kind` (`request` or `result`) |
| `restore_artifact` | `requestId`, matching `<requestId>.vsresult` `name`, `sha256` |

The service must strip its internal recovery/command bookkeeping fields before invoking the strict facade. Credential configuration is a direct operation; bearer values must never enter plaintext command/recovery journals. Queue completion for submission means the runner accepted or retained a job, not that inference or native rendering completed.
