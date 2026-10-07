# VideoStudio v3.4.2

## Studio Web 1.0: user-owned Drive + executable generation

The browser editor is now a real fallback execution surface instead of only an MCP dashboard.

**Google Drive storage**

- uses the user's own Google Drive, so VideoStudio itself does not pay for media object storage
- OAuth scope is `drive.file`, not full-Drive browsing
- creates a VideoStudio Studio Web folder, per-project folders, asset files, render files and a project manifest
- uploads large media with resumable 8 MB chunks
- can restore missing local browser media from Drive
- can verify a project sync and then evict local IndexedDB media to free device/browser storage
- keeps Drive file IDs in project metadata for later restore
- OAuth Web Client ID can be supplied through `GOOGLE_DRIVE_CLIENT_ID` on the Worker or stored once in the browser UI
- access tokens stay in browser memory and are not written into VideoStudio project metadata

**Executable browser video generators**

Studio Web renders real MP4/WebM media through Canvas capture + MediaRecorder and registers the result as a generated project asset and timeline clip:

- text → procedural cinematic video
- image → depth-motion video
- multiple images → story video
- video → video restyle
- 2D motion graphics
- procedural 3D scene animation
- audio → visualizer video
- abstract/VFX generation

These are genuine executable renderers, not placeholder cards. They deliberately do **not** claim that procedural browser output is equivalent to neural photoreal text-to-video. Future WebGPU/local model providers can plug into the same project/generation surface without changing the storage model.

**Autonomous website runtime**

A separate Studio Runtime command queue allows ChatGPT to queue generation and Drive operations without colliding with the existing editor command queue. The MCP tools include `generate_studio_video`, `studio_drive_status`, `studio_drive_sync`, `studio_drive_restore`, `studio_drive_offload` and `get_studio_runtime_result`.


See [the independent creative runtime](docs/INDEPENDENT_CREATIVE_RUNTIME.md) for Connection Core 3, executable procedural 2D/3D generation, bridge commands and precise remaining model requirements.

VideoStudio is a native-first Android video editor controlled from ChatGPT through a private MCP connection.

## v3 identity

- Android app: **3.4.1**
- Android versionCode: **341**
- Native protocol: **MCP v3**
- Canonical private endpoint: `/app-mcp-v3/<device-owned-key>`
- Native control API: `/api/v3/app/*`
- Package: `com.rezoxnemesis.videostudio`

The Android pairing message always shares the v3 endpoint.

## Architecture

The Android app is the source of truth. Projects, timeline state, imported media, analysis jobs, animation layers, motion plans, render jobs, recovery state and Activity history live on the phone.

The Worker is limited to authenticated signalling, command leasing, lightweight status and compatibility fallback. It is not the video editor.

See [docs/V3_ARCHITECTURE.md](docs/V3_ARCHITECTURE.md) for the full architecture contract.

## Native project state

v3 stores project/timeline data in app-private SQLite:

`videostudio_v3.db`

Existing v1/v1.1 projects are migrated automatically on first v3 launch.

## Autonomous permission default

VideoStudio 3.2 defaults to **Allow everything except Gallery** so the Native Agent can finish work without repeated permission interruptions. On upgrade, older installs using the previous default are migrated once to this mode.

This mode allows ChatGPT to create/manage VideoStudio projects, import files explicitly shared to ChatGPT, analyse media, apply edits and animation, render/export, inspect job state, retry work, and manage VideoStudio-owned project media.

Two boundaries remain non-negotiable:

- MCP cannot list, browse or enumerate the Android Gallery/media library.
- **STOP CHATGPT CONTROL** immediately pauses autonomous control and cancels active native work.

The narrower `all_tools` and `one_file` modes remain available as deliberate user-selected restrictions. VideoStudio does not automatically switch back to full autonomy after the one-time v3.2 migration if the user later chooses one of those modes.


## v3.4.2 Always-Available Autonomous MCP

VideoStudio 3.4.2 changes the meaning of “offline” so a sleeping Android executor does not make the whole ChatGPT connection disappear.

- the private MCP v3 endpoint stays reachable as the durable cloud control plane while Android sleeps
- `app_status` separates `controlPlaneConnected` from `nativeConnected`
- native-only work becomes durable `waiting_native` work and is claimed automatically after reconnect
- a registered Studio Web device can be bound once through `app_bind_studio_web` from the same private native MCP
- when Android is sleeping and Studio Web is alive, compatible prompt generation, story-video generation, autonomous edits, timeline replacement, analysis, rendering and common clip tools can route to the bound Web project
- unsupported/native-only operations remain queued for Android rather than being dropped or falsely reported as complete
- Android adds a self-rearming Native Agent watchdog for task removal, service destruction, reboot and APK replacement, without requiring exact-alarm permission
- the existing foreground service, network callback, app-generation fencing, owner credential, package ID and Gallery privacy boundary remain unchanged

This architecture cannot perform compute while **every** execution surface is physically unavailable. In that case the MCP still accepts and preserves the job until an executor returns. It does not fake completed editing or generation.

## v3.4.1 Permanent Hybrid Control Foundation

VideoStudio 3.4.1 strengthens the system around a permanent hybrid control model rather than creating a new ChatGPT connection for every APK.

**Connection continuity**

- the existing Android package, signing identity, device ID, owner credential and MCP v3 compatibility lane remain the stable native identity
- Studio Web `/mcp-v06` acts as the persistent plugin-facing control plane
- native authority is granted only through a private hybrid binding, never from a public Web device ID alone
- native-required work can wait for Android as `WAITING_NATIVE` while Studio Web remains available
- compatible APK upgrades re-arm the foreground Native Agent through `MY_PACKAGE_REPLACED` without intentionally rotating the stable endpoint

**Live editor while ChatGPT works**

- the Android editor now uses a reusable Media3 ExoPlayer `LiveEditPlayer`
- playback is separate from autonomous render/generation jobs
- immutable preview snapshots let the current result keep playing while a newer checkpoint is produced
- the editor exposes autonomous activity/progress plus an explicit **Play new result** handoff
- very large video sources can receive preview-only 540p/720p proxies while final export continues to use the untouched original media

**5 GB+ media foundation**

- Android picker media stays URI-backed where the provider grants persistent access
- asset size, transfer offsets, durations and storage arithmetic use 64-bit values
- remote media uses resumable HTTPS range transfer, bounded buffers, durable transfer checkpoints and `.partial` promotion
- a server that ignores a resume range causes a safe restart rather than corrupt append
- storage is checked before heavy transfers, with explicit reserve space
- there is no application-level `5 GB` ceiling for supported URI-backed local media; practical limits come from device storage, provider/filesystem behaviour and codec support

**Crash and recovery hardening**

- autonomous jobs use canonical queued/preparing/running/checkpoint/wait/terminal states
- memory, thermal, network, storage and native-offline waits are non-terminal
- terminal jobs cannot be resurrected by an invalid state transition
- render publication is idempotent: an already committed output is reused after restart instead of intentionally publishing a duplicate
- partial output is never registered as a completed preview/final asset

These changes are the execution foundation for heavier future local/generative engines. They do not claim that storage or connection hardening alone creates semantic neural image-to-video synthesis.

## v3.3.2 Stable MCP Connection Core

VideoStudio now treats the existing `/app-mcp-v3/<owner-key>` URL as a **permanent compatibility endpoint**, not an APK-version URL.

- the Android Keystore owner credential and device ID keep their existing storage namespace across compatible APK updates
- a persistent MCP Connection Core tracks app generation separately from protocol compatibility
- the server negotiates heartbeat, long-poll and timeout parameters without changing the endpoint
- newer APK generations fence stale background processes so an old service cannot overwrite current connection state
- Android automatically re-arms the foreground Native Agent after an in-place APK replacement or device reboot, so the user normally does not need to reconnect or recreate the MCP
- additive native actions continue through `app_execute` / `app_batch`, so new functions do not require recreating the connector
- the app automatically falls back to the stable v3 bootstrap profile if negotiated connection metadata becomes invalid
- Gallery browsing remains permanently blocked

## v3.3.1 Native Agent self-healing

VideoStudio 3.3.1 strengthens upgrade and reconnection behavior:

- opening or resuming the app explicitly requests the foreground Native Agent to start and sync
- a foreground watchdog re-requests the service if its heartbeat is stale or if the running service version does not match the installed app
- the Native Agent heartbeat records its exact app version so stale service processes are visible instead of being mistaken for the current build
- the Home connection card shows the installed/running version state
- repeated heartbeat registrations no longer flood Activity with duplicate connection messages

## v3.3 creative runtime foundation

VideoStudio 3.3 begins the programmable creative-runtime layer without changing the private MCP v3 endpoint.

The first foundation slice includes:

- a safe **MotionScript 0.2** scene language that compiles into versioned CreativeIR and can declare subjects, identity constraints, rigs, depth/pose/hands, lighting, voice, generation nodes and capability requirements
- an app-private creative workspace for scene source, compiled plans, generated artifacts, masks, depth, pose, flow, rigs, meshes, audio, checkpoints, previews and renders
- a capability-first provider registry so future local image, motion, depth, pose, interpolation, voice and 3D model packs can plug into the same runtime
- a transactional optional model-pack installer using explicit VideoStudio-owned assets, guarded ZIP extraction, manifest/license validation, optional SHA-256 verification and atomic activation
- a hardware-aware compute planner that treats RAM as a bounded active workspace, selects tiled inference and small temporal windows, and plans phase-by-phase model swapping instead of assuming an entire future generative pipeline must stay resident
- a folder-scoped cloud workspace archive using Android's system document-tree picker, so Google Drive can be used when exposed by the device without granting VideoStudio broad access to the user's whole Drive
- provider-aware MotionScript planning that resolves requested creative capabilities against currently installed local providers and can fail strictly instead of pretending a missing engine exists
- a provider-agnostic **CreativeIR execution DAG** with explicit dependencies, per-node cache keys, restart checkpoints and hardware-aware resource plans
- durable **targeted regeneration**, where changing or invalidating one node resets only that node and downstream dependants while unaffected completed work stays reusable
- a first built-in CreativeIR executor for bundled portrait segmentation/face analysis, layered depth approximation, articulated 2.5D rigging, native shot composition and Media3 final rendering
- a local technical render critic that checks decode health, black/overexposed frames, freeze-like spans, abrupt visual jumps and luminance instability without pretending to be a semantic vision model
- generated video registration as first-class project media instead of only storing a latest-export URI
- an editor **Media Bin** with generated/source roles, preview and timeline insertion
- live editor refresh when native background work changes project state
- durable heavy-job recovery plans for animation, prompt-video and export work
- thermal and memory waiting states that preserve checkpoints instead of using a fixed timeout
- published-output recovery so a restart does not intentionally render a duplicate when the final MediaStore output was already committed
- dedicated MCP v3 tools for MotionScript, creative workspace and capability-provider inspection, while retaining the generic future-compatible action bridge

The 3.3 foundation is deliberately modular. Optional generative model providers are not bundled merely to inflate the APK; they can be introduced as verified model packs behind the capability registry.

## Native still-image animation

VideoStudio 3.2 strengthens the zero-paid-service portrait animation engine while keeping the connection protocol on **MCP v3**.

The Android app bundles on-device ML for person segmentation and face mesh. For imported still images it can:

- extract a transparent subject layer
- split that subject into feathered head/hair, torso and lower-drape/body layers
- reconstruct the hidden background region while preserving visible environment detail
- anchor framing around the detected face/subject
- generate varied multi-keyframe cinematic camera paths
- animate foreground and background at independent depths for 2.5D parallax
- add independent head drift/nod, torso breathing and lower-drape/body sway
- reorder wide/medium/close shots for visual rhythm
- add procedural water glints, mist, rain, wind particles or light breathing
- render the layers as a real local MP4 through Media3

The autonomous MCP v3 tools include `app_animate_images` and `app_job_status`.

This is genuine articulated layered motion/compositing, but it does **not** claim full generative facial-expression synthesis or per-strand/per-fold non-rigid deformation. Those can be added later through optional local model modules without changing the editor/MCP architecture.

## Autonomy model

VideoStudio 3.2 defaults to **Full Autonomous** control. ChatGPT can use every VideoStudio-native operation without repeated permission prompts: explicit attachment/URL imports, project creation and deletion, analysis, editing, animation, rendering, inspection, retries and cleanup.

There is one optional restrictive mode: **One File Lock**, which the user can enable manually when they want ChatGPT limited to one authorised asset.

Full Autonomous does not weaken the permanent safety boundaries:

- MCP cannot list, browse or enumerate the Android Gallery/media library
- remote imports remain HTTPS-only and reject private/local network destinations
- remote imports use resumable, bounded-memory streaming with storage preflight, 64-bit byte offsets and partial-file checkpoints instead of a legacy small-file ceiling
- heavy rendering remains RAM/thermal guarded
- STOP CHATGPT CONTROL remains immediately available

The former `all_tools` value is accepted only as a backward-compatible alias and is normalised to Full Autonomous.

## ChatGPT attachment ingest

The v3 MCP tool `app_import_attachment` uses ChatGPT's MCP file-parameter mechanism. ChatGPT supplies an authorised temporary file reference, and the Android Native Agent downloads the bytes directly into VideoStudio's app-private storage.

The signalling Worker does not proxy the media in the primary v3 path.

A short-lived relay remains only as a compatibility fallback.

VideoStudio 3.2.1 also adds an **owner-authenticated inline still-frame fallback**. When the current ChatGPT host can read a conversation image but cannot expose a temporary HTTPS download URL to the installed connector schema, ChatGPT can send a bounded PNG/JPEG/WebP payload inside the private MCP command. The Android app verifies the optional SHA-256, validates image bounds, writes the bytes straight into app-private storage, and never publishes the frame to a public host or Gallery.

## Privacy

Gallery browsing is a hard boundary.

The Android manifest does not request Gallery read permissions. MCP cannot enumerate the user's photo/video library.

ChatGPT can work only with:

- user-selected Android picker files
- files explicitly attached/shared through ChatGPT
- VideoStudio-owned media
- explicit HTTPS imports

## Autonomous control

The v3 native tool surface supports:

- status and native self-test
- project create/select/delete
- full native project state
- ChatGPT Activity notes
- direct attachment ingest
- native media analysis
- structured edit plans
- per-clip edit tools
- creator presets
- autonomous edit + export
- prompt-to-video
- batch operations
- native MP4 export
- job cancellation

v3 uses a dedicated queue/cursor namespace and an Android command journal so reconnect/retry does not execute a completed command twice.

### Future-version connector stability

The v3 MCP surface now includes a stable `app_execute` bridge and a future-compatible `app_batch` action field. New Android-native actions can therefore be introduced behind the same private MCP endpoint without forcing the user to recreate the ChatGPT connector for every VideoStudio release. The permanent Gallery/media-library block is enforced server-side before any action is queued, including actions sent through this generic bridge.


## Rendering and stability

- Media3 Transformer native export
- H.264/AAC MP4
- 720p / 1080p
- 9:16 / 16:9 / 1:1 / 4:5
- thermal guard
- memory guard
- one protected heavy-render lane
- bounded light-work lanes
- persistent recovery checkpoints
- foreground Native Agent service
- secure reconnect backoff
- STOP CHATGPT CONTROL

## Activity transparency

The app includes a ChatGPT Activity screen. Imports, analysis, edits, renders, exports, progress, failures and retries are visible there.

Only work executed by VideoStudio itself counts as completed editing.

## Development

Install dependencies:

```bash
npm install
```

Run the Worker locally:

```bash
npm run dev
```

Run smoke tests:

```bash
npm test
```

Cloudflare deploys from `main`. GitHub Actions builds the signed-development Android APK from the same branch.

## Legacy compatibility

Legacy `/mcp`, `/mcp-v06` and `/app-mcp/<key>` routes remain for migration compatibility.

A device registered as protocol 3 is routed to the v3 queue even when a cached legacy connector is used. The canonical v3 pairing endpoint is still `/app-mcp-v3/<key>`.

