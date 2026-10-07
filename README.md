# VideoStudio v3.1

VideoStudio is a native-first Android video editor controlled from ChatGPT through a private MCP connection.

## v3 identity

- Android app: **3.1.0**
- Android versionCode: **310**
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

## Native still-image animation

VideoStudio 3.1 adds a zero-paid-service portrait animation engine while keeping the connection protocol on **MCP v3**.

The Android app bundles on-device ML for person segmentation and face mesh. For imported still images it can:

- extract a transparent subject layer
- reconstruct the hidden background region while preserving visible environment detail
- anchor framing around the detected face/subject
- generate varied multi-keyframe cinematic camera paths
- animate foreground and background at independent depths for 2.5D parallax
- add restrained whole-subject breathing/sway micro-motion
- reorder wide/medium/close shots for visual rhythm
- add procedural water glints, mist, rain, wind particles or light breathing
- render the layers as a real local MP4 through Media3

The autonomous MCP v3 tools include `app_animate_images` and `app_job_status`.

This is genuine layered motion/compositing, but it does **not** claim full non-rigid generative deformation of facial expressions, hair strands or cloth folds. Those can be added later through optional local model modules without changing the editor/MCP architecture.

## ChatGPT attachment ingest

The v3 MCP tool `app_import_attachment` uses ChatGPT's MCP file-parameter mechanism. ChatGPT supplies an authorised temporary file reference, and the Android Native Agent downloads the bytes directly into VideoStudio's app-private storage.

The signalling Worker does not proxy the media in the primary v3 path.

A short-lived relay remains only as a compatibility fallback.

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
