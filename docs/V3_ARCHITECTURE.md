# VideoStudio v3 Native Agent Architecture

## Release identity

VideoStudio v3 is a protocol and architecture generation, not a cosmetic version label.

- Android app: `3.1.0`
- Android versionCode: `310`
- Native agent protocol: `3`
- Canonical private MCP endpoint: `/app-mcp-v3/<device-owned-owner-key>`
- Native control API namespace: `/api/v3/app/*`
- Android application ID remains `com.rezoxnemesis.videostudio` so a correctly signed v3 build upgrades the installed app rather than creating a second app.

The Android pairing message MUST expose the v3 MCP endpoint. The v1 endpoint exists only for legacy clients and must never be used as the canonical v3 connection.

## Native-first ownership

The Android app is the source of truth.

VideoStudio owns locally:

- project identity and metadata
- imported media references and app-private imported files
- timelines, clips, trims, speed, volume, titles and effect parameters
- creator presets and prompt-video plans
- native analysis jobs
- native Media3 rendering and export state
- job recovery snapshots
- MCP command idempotency journal
- ChatGPT Activity history
- permission mode and Gallery privacy boundary

Project and timeline state is stored in the app-private SQLite database `videostudio_v3.db`.

On first v3 launch, the store migrates the existing v1/v1.1 project JSON from SharedPreferences into SQLite. The old preference copy is intentionally retained as a rollback safety net.

## What the network layer is allowed to do

The Cloudflare Worker is a small authenticated signalling surface.

It may:

- bind a device-owned credential to one native device
- expose the MCP tool schema
- hold lightweight device/project summaries
- queue v3 commands
- lease commands to the phone
- retain compact command results
- return native visual-analysis contact sheets
- provide a short-lived legacy byte relay only as a fallback

It is not the editor.

The primary v3 architecture does not use the Worker to proxy ChatGPT attachment bytes.

## ChatGPT attachment import

The primary v3 MCP tool is `app_import_attachment`.

It declares a ChatGPT file parameter through:

`_meta["openai/fileParams"] = ["file"]`

ChatGPT supplies an authorised file object containing a temporary `download_url` and `file_id`. The MCP server queues only command metadata. VideoStudio's Android Native Agent downloads the file directly from that authorised HTTPS URL into app-private storage and then adds it to the selected local project.

After the v3 import command completes, the Worker removes the temporary source URL from the stored command record.

The older handoff/Worker cache path remains available only as a compatibility fallback.

## Gallery boundary

MCP v3 has no permission to enumerate the user's Gallery.

The Android manifest does not request:

- `READ_MEDIA_IMAGES`
- `READ_MEDIA_VIDEO`
- `READ_EXTERNAL_STORAGE`

ChatGPT can work with only:

- files the user explicitly selects through Android's picker
- files explicitly attached/shared through ChatGPT
- files already owned by VideoStudio
- explicit HTTPS imports allowed by the user

Every permission mode rejects Gallery/media-library enumeration actions.

## Connection security

The device identity is generated on Android and survives an in-place upgrade.

The owner secret:

- is random device-owned key material
- is encrypted at rest with Android Keystore AES-GCM
- is never persisted as plaintext by the app
- remains bound to one native device in the Durable Object

v3 registration includes:

- appVersion `3.0.0`
- protocolVersion `3`
- connection session ID
- local permission mode
- Gallery access = false
- direct attachment ingest capability
- local-engine ownership declaration

The v3 MCP endpoint rejects a device that has not registered as protocol 3.

## Reliable autonomous control

v3 has its own queue and cursor namespace:

- `app-v3-seq:<device>`
- `app-v3-cl:<device>`
- Android cursor `native_v3_last_seq`

Commands use leases. If the app disappears during a claim, the command becomes claimable again after the lease expires.

The Android app also keeps a durable command journal. A repeated command ID that already reached a terminal state is not executed again. VideoStudio returns the durable prior result instead. This prevents reconnects from duplicating operations such as project creation, imports or edits.

A foreground `ControlService` owns the background MCP connection. UI closure does not delete project state or make the Worker the source of truth.

## Native health verification

MCP v3 exposes `app_self_test`.

The on-device self-test checks:

- app version and protocol version
- app-private storage writeability
- SQLite project store
- job engine
- Media3 render engine
- native analysis engine
- prompt-video engine
- permission mode
- Gallery access = false
- direct attachment ingest capability
- foreground control state

Autonomous work should run `app_status`, then `app_self_test`, before a significant editing session.

## v3.1 portrait animation module

v3.1 extends the local creator engine without changing the connection protocol.

- MCP remains protocol `3`
- canonical endpoint remains `/app-mcp-v3/<device-owned-owner-key>`
- bundled person segmentation and face mesh execute on-device
- generated subject/background plates live in app-private storage
- motion plans are persisted on the native timeline
- Media3 composites independent foreground/background video sequences
- procedural atmosphere is rendered per-frame locally
- long animation preparation/rendering runs through the same thermal/RAM guarded heavy lane
- `job_status` exposes durable progress through MCP

The module deliberately distinguishes layered physical-looking motion from future full generative deformation. v3.1 does not pretend to synthesize new facial performances or arbitrary cloth/hair topology.

## Editing and rendering

The Android engine remains local-first and includes the existing native capabilities:

- timeline editing
- trim and split
- speed and slow-motion model
- volume and audio-duck model
- titles, fonts and text animation
- transforms and motion presets
- colour/HSL and blur
- green-screen model
- mask and reframe models
- creator transition/effect catalog
- native sampled-frame analysis
- prompt-video planning
- Media3 MP4 export
- 720p and 1080p outputs
- 9:16, 16:9, 1:1 and 4:5 framing
- thermal and memory guards
- one protected process-wide heavy-render lane
- cancellable jobs and recovery checkpoints

## Activity transparency

Only work actually executed by VideoStudio counts as completed project work.

The Activity screen records:

- received MCP actions
- queued/running/completed/failed status
- import progress
- analysis progress
- edit operations
- render/export progress
- retries and failures
- command/project identifiers

ChatGPT can also post explicit progress notes through the activity-note tool so the user can see the same work stream in ChatGPT and inside VideoStudio.

## Compatibility policy

Legacy routes may remain available so an older cached connector can discover or proxy calls during migration.

When a device is registered as protocol 3, compatibility calls MUST route into the v3 queue. They must not enqueue work into the old v1 native queue.

The canonical pairing message and canonical private MCP URL for a v3 device are always v3.
