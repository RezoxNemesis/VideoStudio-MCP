# VideoStudio OMEGA — 10 GiB media reliability contract (engineering candidate)

**Scope:** video/audio ingest and long-form native VideoStudio rendering; not a blanket override of device storage, Android decode, 3D GPU or Gallery permissions. This contract is tied to the implementation in `ProjectAssetImport`, `ResumableTransferManager`, `NativeMediaWorkBudget`, and `ControlService`.

## Usable lanes and exact limits

| Native route | Media | New source limit | Resume behavior |
|---|---|---:|---|
| `project_import_asset` (source from app-owned project) | Video/audio | **10 GiB** | Stable private `.partial` verified byte-for-byte against source, then atomic rename |
| `import_url` (HTTPS) | Video/audio | **10 GiB** | 64-bit Range/If-Range; 8 MiB fsynced checkpoints, revert at most unjournaled suffix |
| `import_attachment` (direct app URL) | Video/audio | **10 GiB** | Same resumable HTTPS engine |
| `import_chat_file` (one-time private handoff) | Video/audio | **10 GiB** | Disk-backed bounded buffer, but the one-time handoff does **not** guarantee Range/resume |
| `import_mailbox_media` (old encrypted manifest v1) | General media | 256 MiB | Old authenticated 512-chunk manifest; needs distinct paginated v2 protocol to support 10 GiB |
| `import_inline_base64` | Small image/MP4 | 12 MiB | In-message data; intentionally not appropriate for gigabyte-sized video |
| `append_frame_chunk` | PNG/JPEG frame | 12 MiB | Image-only chunks; not 10 GiB video |
| Existing app-owned image | PNG/JPEG | 256 MiB | Cannot decode 10 GiB images as ordinary Android bitmaps |
| GLB 3D model import | GPU geometry | Separate model/resource budget | Not upgraded by large-video streaming |

Only the actual native execution and verified final asset receipt count as successful imports. A queued job is never represented as completed.

## Rendering and recovery engineering
- The prior hard 30-minute project-export timeout is replaced by a 30-minute to 24-hour **bounded adaptive window** based on video duration and source media sizes. This avoids rejecting multi-gigabyte work solely because it needs more than 30 minutes.
- Native export workspaces are app-owned, not in Android's evictable cache; disk reserve checks prevent uncontrolled device exhaustion. H.264/AAC encoding still uses the device's native Media3/MediaCodec path.
- Output profiles are bounded at 10 GiB and must pass the original export publisher and media receipt checks. Real 5–10 GiB decoded-input/MP4-output tests have not yet been performed.
- Insufficient storage is a named `waiting_storage`/safe-capacity failure, not permission to overwrite another project's data or allow a corrupt partial. Free storage must account for an independent input copy and, when publishing to Movies, an additional output copy.

## Command truth and user control
- Capability lookups had been falsely labeled failures because `CapabilityRegistry.resolve` returns `resolved` rather than a top-level `ok`. `NativeCommandResultPolicy` recognizes only known read-only capability payloads and adds an honest success flag.
- A missing provider is a successful lookup with `resolved:false`, not a command-engine crash.
- All mutations (editing, rendering, importing, installing a model, deleting, cancelling) continue to require explicit native `ok:true`. No transport silence, unverified model/asset, or queued job is a success receipt.
- Gallery browsing/enumeration stays prohibited. VideoStudio remains the only editor/importer/renderer. Original signing identity, revision conflict checks, durable operation IDs, atomic output publication and Stop All remain intact.

## Acceptance
1. Java 17, SDK 36: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace`.
2. On-device 5 GiB and 10 GiB source test through a verified owned project or HTTPS Range server; inspect exact bytes and metadata, throttle progress to avoid 40,000 disk journal writes, pause/retry midway, and demonstrate no duplicate clips.
3. Corrupt one partial byte and prove atomic refusal, no output publication and no change to user's source asset.
4. Time actual Media3 render from the 5/10 GiB input at target size; inspect content/decoding, actual audio, output hash, frame continuity, peak RAM, thermals, free storage and restart/recovery.
5. Separate signed update with monotonic versionCode and pinned original owner certificate. No install or claim of 10 GiB production acceptance until results exist.

**Status:** Implementation branch with CI gate; real 5–10 GiB device acceptance pending.
