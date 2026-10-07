# VideoStudio Permanent Hybrid Control Foundation

Date: 2026-10-07  
Status: Design approved in chat, awaiting written-spec review before implementation planning  
Target: Android native app + Studio Web + MCP control plane

## 1. Purpose

VideoStudio must become a stable autonomous editing system where ChatGPT can keep controlling the same product across APK upgrades, Studio Web can assist when the native app is unavailable, the Android editor remains playable and inspectable while background work runs, and large media such as 5 GB+ files can be handled without treating the full file as an in-memory object.

The architecture must preserve the user's existing local-first model. The Android app remains the authoritative editor and render engine. Studio Web is a companion execution/control surface, not a replacement for native ownership.

The implementation must not weaken the existing privacy boundary that prevents MCP from enumerating the Android Gallery.

## 2. Success criteria

The foundation is successful only when all of the following are true:

1. The same user-installed VideoStudio MCP connection continues working after compatible in-place APK updates without creating a new device identity, owner credential, project store, or plugin.
2. New app versions can expose additional native actions without requiring a new MCP URL.
3. Studio Web can remain connected while the Android app is unavailable, queue compatible work, and hand native-required work back to Android when the Native Agent reconnects.
4. Native Android remains the source of truth for native projects, timelines, jobs, renders, project-owned media, and command execution history.
5. The editor can play, pause, scrub, and inspect the active timeline or a safe preview while autonomous editing, animation preparation, analysis, or rendering is running in another job lane.
6. A heavy background job cannot freeze the editor UI merely because the job is active.
7. Imported 5 GB+ local media does not need to be copied into app-private storage merely to become editable.
8. File sizes, byte offsets, resumable transfer positions, durations, and storage accounting use 64-bit values.
9. Remote or ChatGPT-origin media transfers use bounded-memory streaming and resumable checkpoints rather than whole-file buffering.
10. Process death, app restart, network loss, device reboot, and compatible APK replacement do not silently lose durable commands or resumable heavy-job state.
11. A failed render or import never replaces a previously valid published output with a partial file.
12. Full native autonomy is never granted solely from an unauthenticated public Studio Web device ID.

## 3. Architectural decision

VideoStudio will use a **Permanent Hybrid Control Core** with four layers:

```text
ChatGPT VideoStudio plugin
        |
        v
Hybrid MCP Control Plane
        |
        +--------------------+
        |                    |
        v                    v
Studio Web Runtime      Native MCP Compatibility Core
(fallback/queue/UI)              |
                                 v
                        Android Native Agent
                                 |
                                 v
                 Projects / Editor / AI / Renderer
```

The control plane provides one stable product-level connection. The Android app and Studio Web are execution surfaces behind that connection.

The Android app remains authoritative whenever a task changes native project state or requires native-only capabilities. Studio Web may execute browser-capable work and may queue native work while Android is temporarily unavailable.

This is intentionally different from making Studio Web the new project authority.

## 4. Permanent connection contract

### 4.1 Stable identity

The existing Android application ID remains:

`com.rezoxnemesis.videostudio`

Compatible upgrades must continue using the same signing identity. The app must continue storing the device ID and owner credential in the existing persistent namespace and Android Keystore-backed secret storage.

A normal in-place APK upgrade must not rotate:

- device ID
- owner credential
- native MCP owner binding
- SQLite project database
- local permission mode
- command journal
- recovery plans
- durable capability binding

Uninstall/reinstall is explicitly outside the survival guarantee because Android can remove app-private state and Keystore material on uninstall.

### 4.2 Stable protocol lane

Native MCP protocol v3 remains the durable compatibility lane.

The current stable endpoint family remains valid for direct native compatibility:

`/app-mcp-v3/<device-owned-owner-key>`

APK versions must not create paths such as `/app-mcp-v4`, `/app-mcp-v5`, etc. Feature growth happens through:

- capability negotiation
- additive typed actions
- the generic execute bridge
- feature levels
- wire-schema additions that preserve old fields

A future incompatible protocol may exist only if an actual compatibility break becomes unavoidable. App version alone is never sufficient reason.

### 4.3 Hybrid plugin front door

The installed Studio Web plugin becomes the persistent user-facing control plane.

The existing public Studio Web MCP surface must not be allowed to control the native app solely by receiving a native `deviceId`. Native authority requires an explicit private hybrid binding.

The hybrid binding design is:

1. Android owns the existing owner credential.
2. Android can create a short-lived one-time pairing/rebind challenge.
3. Studio Web exchanges that challenge with the Worker.
4. The Worker binds the Studio Web workspace to the existing native owner identity without exposing the owner secret to page JavaScript.
5. Subsequent hybrid calls resolve the authorised native device through this durable binding.
6. The durable binding survives compatible Android app upgrades.
7. Revocation is possible from Android and invalidates native control without deleting projects.

Implementation should prefer an authenticated header/token for the installed plugin if the ChatGPT custom-MCP surface supports secure stored authentication. If that integration surface cannot persist a private header, use an opaque server-issued binding capability that is not derivable from the public device ID.

The control plane must never depend on the human-readable device name.

## 5. Execution routing

Every command is classified before execution.

### Native-required

Examples:

- native timeline edits
- Media3 final render
- local ML analysis
- portrait animation
- large local-file decode
- app-private generated media
- project database mutation
- Android export
- operations requiring persisted Android document URIs

If Android is online, execute there.

If Android is temporarily offline, store the command as durable `WAITING_NATIVE` rather than failing or pretending Studio Web executed it.

### Browser-capable

Examples:

- Studio Web-only procedural generation
- browser-local media operations
- Drive sync owned by Studio Web
- project-independent previews
- control-plane status
- browser fallback editing when explicitly operating a Studio Web project

These can execute in Studio Web.

### Either-surface

The control plane selects using:

1. project authority
2. capability availability
3. media locality
4. resource state
5. explicit user preference
6. recovery continuity

It must never silently copy an Android-native project into an independent Web project just to satisfy a command.

## 6. Command model

### 6.1 Durable lifecycle

Native/hybrid commands use:

```text
QUEUED
CLAIMED
PREPARING
RUNNING
CHECKPOINTED
WAITING_NETWORK
WAITING_STORAGE
WAITING_MEMORY
WAITING_THERMAL
WAITING_NATIVE
COMPLETED
FAILED
CANCELLED
```

Terminal states are `COMPLETED`, `FAILED`, and `CANCELLED`.

Non-terminal states are resumable and must preserve enough state to continue or safely restart.

### 6.2 Idempotency

The existing command journal remains authoritative for duplicate prevention.

Each durable command has:

- command ID
- protocol version
- action
- parameters hash
- project ID where applicable
- created time
- claim lease
- attempt count
- current stage
- checkpoint data
- terminal result or failure reason

Repeating an already-completed command ID returns the durable result rather than executing it again.

### 6.3 Queue protection

Pending work must never be discarded to make room for newer work.

When queue capacity is reached:

- preserve unfinished commands
- reject new work with an explicit backpressure error
- surface queue depth/status to ChatGPT and the app UI

## 7. Native service ownership

A single foreground Native Agent service owns remote control.

The Activity must not create a competing long-lived MCP connection. The Activity can request sync/reconnect, display state, and issue local user commands, but transport ownership stays in the service.

The service owns:

- registration
- heartbeat
- command leasing
- outbox/result delivery
- reconnect/backoff
- recovery dispatch
- native heavy-job scheduling
- command journal coordination

UI destruction must not cancel the MCP connection.

Opening a newer APK generation fences stale service state. A stale process must never register itself as the current generation.

## 8. Live Edit Player

### 8.1 Goal

The editor must remain useful while ChatGPT is working.

The user must be able to:

- play/pause
- scrub
- jump between clips
- preview current timeline state
- inspect newly generated assets
- continue watching an existing preview while background preparation/render work continues
- see job progress without losing playback position

### 8.2 Playback architecture

Replace the simple editor `VideoView` preview path with a Media3 ExoPlayer-based `LiveEditPlayer` component.

The player owns playback only. It does not own background render jobs.

It supports two preview sources:

1. **Direct source/timeline preview** for lightweight edits.
2. **Proxy/checkpoint preview** for heavy timelines and generated work.

The player maintains:

- playback position
- selected clip
- play/pause state
- active preview generation
- source/proxy identity

A project-state refresh must not recreate the whole player when it can update the media item or timeline model in place.

### 8.3 Preview snapshots

Autonomous editing publishes immutable preview snapshots.

A snapshot contains:

- snapshot ID
- project revision
- media/timeline revision
- preview URI or playable timeline description
- creation time
- quality tier
- generation/job ID

The UI may keep playing snapshot N while ChatGPT builds N+1. When N+1 becomes ready, the UI offers or automatically performs a safe switch depending on user settings.

### 8.4 Proxy strategy

For heavy source media, proxy generation is optional and background-safe.

Suggested proxy tiers:

- 540p or 720p H.264 for editing preview
- source audio retained where practical
- frame rate capped only for proxy performance
- original asset always retained for final render

Proxy failure must never invalidate the source asset.

## 9. Large-file architecture

### 9.1 Local imports

Android system-picker imports should remain URI-backed when the content provider grants persistent read access.

VideoStudio should not copy a 5 GB local video into app-private storage merely to register it.

The asset record stores:

- source URI
- persistable-access status
- display name
- MIME type
- sizeBytes as `long`
- durationMs as `long`
- seekability capability
- provider authority
- local/proxy/generated role
- optional integrity metadata

### 9.2 64-bit rules

No media-size path may narrow bytes to 32-bit `int`.

Use `long` for:

- file sizes
- byte offsets
- downloaded bytes
- uploaded bytes
- content lengths
- storage estimates
- resumable range positions
- durations in milliseconds
- large-file progress numerators/denominators

Percentages may be converted to integers only after safe 64-bit calculation.

### 9.3 Remote and ChatGPT imports

Remove the current architecture-level assumption that remote media is limited to a few hundred MB.

Remote ingest becomes a resumable transfer subsystem with:

- HTTPS-only sources
- redirect validation
- private-network rejection
- disk-space preflight
- streamed copy using bounded buffers
- partial-file naming
- transfer journal
- content-length validation when available
- range-resume support when the origin allows it
- checksum support when supplied
- retry with exponential backoff
- cancellation
- atomic promotion from partial to complete
- cleanup of abandoned partial files by policy

If a source does not support range requests, the system may need to restart that transfer, but must still stream rather than buffer.

### 9.4 No arbitrary 5 GB ceiling

The system target is not `MAX_FILE_SIZE = 5 GB`.

The target is no small arbitrary application limit for supported URI-backed local files. Effective limits come from:

- available storage
- provider/file-system behavior
- decoder/container support
- Android APIs
- final output constraints

Remote imports may use configurable safety/storage policy, but that policy must be expressed in storage-aware terms rather than a legacy 350 MB hard cap.

## 10. Heavy-job scheduler

Only resource-compatible heavy operations execute concurrently.

The scheduler considers:

- available memory
- thermal state
- free storage
- decoder/encoder ownership
- GPU/NPU/CPU workload class
- whether the job is foreground-critical
- whether playback is active

The editor playback lane receives interactive priority.

Heavy AI/render jobs use checkpoints and may yield between stages.

Suggested lanes:

- interactive playback/UI
- light metadata/edit commands
- analysis
- generation/preparation
- encode/render
- transfer/storage

Expensive render/generation jobs may be serialized where Android codecs or memory pressure make concurrency unsafe.

## 11. Stability and recovery

### 11.1 Crash containment

Heavy work must not run on the main UI thread.

Uncaught failure in one job must transition that job to a durable failure/retry state instead of corrupting project state.

### 11.2 Atomic project updates

Project changes that span multiple records are committed transactionally.

Generated files use:

`*.partial -> fsync/close -> validate -> atomic/promoted final reference`

Project metadata points to the new output only after the output has been validated enough to be considered committed.

### 11.3 Process death

On restart:

1. load SQLite project state
2. load command journal
3. load recovery plans
4. detect jobs that were running
5. inspect checkpoint/output state
6. resume safe stages or mark explicit recoverable failure
7. reconnect MCP
8. flush durable results/outbox
9. continue accepting new work when safe

### 11.4 Reboot and APK replacement

`BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` re-arm the Native Agent.

An app-generation counter fences stale registration.

No upgrade path may intentionally wipe the stable connection namespace.

## 12. Storage pressure

Before a heavy transfer/render/generation stage:

- estimate required working bytes
- check free bytes
- include safety reserve
- account for partial output and proxy/cache requirements
- fail early with an actionable `WAITING_STORAGE` or explicit insufficient-storage state

Optional cleanup may target only VideoStudio-owned disposable cache/proxy data unless the user explicitly authorises broader deletion.

Source media selected through Android's picker must never be deleted by cleanup.

## 13. Editor UI changes

The Android editor gains:

### Persistent player area

- Media3 playback surface
- play/pause
- scrubber
- current time / duration
- previous/next clip
- preview source badge: SOURCE / PROXY / CHECKPOINT / FINAL
- maintain playback through non-destructive project refreshes

### Autonomous activity strip

Visible without leaving the editor:

- current ChatGPT action
- job stage
- progress
- waiting reason
- cancel/stop entry point
- latest checkpoint availability

### Timeline behavior

Timeline remains usable for selection and playback while background work runs.

Controls whose mutation would conflict with a currently locked render stage may be temporarily disabled individually, rather than freezing the entire editor.

### Render handoff

When a new preview/final render arrives:

- do not force-stop current playback immediately
- preserve current position where meaningful
- expose `Play new result`
- optionally auto-switch after current playback pauses

The design should preserve the existing VideoStudio visual language rather than introduce a separate app aesthetic.

## 14. Hybrid status model

ChatGPT should be able to query one consolidated status containing:

- hybrid binding health
- Studio Web health
- native online/offline
- installed/running app version
- app generation
- native protocol/core version
- active project
- permission mode
- pending native commands
- pending web commands
- active job
- playback-active hint
- resource waits
- large-transfer progress
- last completed checkpoint/render
- whether native capability is required for queued work

This replaces guesswork such as treating a Web heartbeat as proof that Android is online.

## 15. Security boundaries

The following remain mandatory:

- no Gallery enumeration through MCP
- no private/local-network remote downloads
- no unauthenticated native control by device ID
- owner credential encrypted with Android Keystore
- short-lived rebind challenges
- capability binding revocable from Android
- STOP CHATGPT CONTROL remains local and immediate
- remote command parameters validated before execution
- redirects revalidated at every hop
- partial import files are never interpreted as complete media
- no credentials written into project JSON or Activity logs

## 16. Compatibility and migration

The first implementation must migrate without breaking the current user installation.

Migration rules:

1. Keep the existing package ID.
2. Keep the existing signing workflow/key continuity.
3. Keep the existing SharedPreferences namespace containing stable identity.
4. Keep existing SQLite projects.
5. Keep MCP protocol v3.
6. Add fields with defaults so existing projects load.
7. Preserve existing direct native MCP endpoint as a compatibility lane.
8. Add the hybrid binding rather than replacing native identity.
9. Preserve existing `everything`, `all_tools`, and `one_file` permission semantics.
10. Existing generated media and recovery plans remain readable.

## 17. Testing strategy

### Unit tests

Cover:

- 64-bit file-size math above 2 GB and 5 GB
- resumable transfer ranges and checkpoint serialization
- stale app-generation fencing
- hybrid-binding authorization
- command idempotency
- queue backpressure
- storage preflight
- proxy metadata
- project migration defaults
- atomic output publication
- recovery state transitions

### Android/Robolectric tests

Cover:

- persistent URI metadata
- app-upgrade identity retention using the same preference namespace
- Media3 player state model
- project refresh without destructive playback reset
- process-restart recovery model
- large `OpenableColumns.SIZE` values
- no Gallery permissions introduced

### Node/control-plane tests

Extend `scripts/connection-test.mjs` with:

- hybrid binding cannot be resolved by device ID alone
- one-time challenge reuse is rejected
- native-offline command becomes `WAITING_NATIVE`
- reconnect makes waiting commands claimable
- completed command remains idempotent
- plugin-facing status distinguishes Web vs Android health
- APK generation change does not rotate owner binding

### Build verification

Required before completion:

- `npm test`
- Android unit tests
- debug APK assembly
- source smoke tests
- Cloudflare dry-run build
- GitHub Actions green on the implementation branch
- install/upgrade test using the same package/signing identity where the environment permits
- native `app_status` and `app_self_test` after installing the upgraded APK on the user's device

## 18. Performance acceptance targets

These are engineering targets, not guarantees for every codec/device:

- importing URI-backed local media should scale primarily with metadata probing, not file byte size
- editor controls should remain responsive while background jobs are running
- no whole-file byte-array allocation for large media
- transfer buffers remain bounded
- project serialization does not embed media bytes
- preview proxy creation may take time but must be cancellable and resumable where practical
- resource pressure should produce a waiting state rather than an unexplained crash

## 19. Rollout slices

Implementation should proceed in dependency order:

1. hybrid authorization and stable routing contract
2. service ownership/reconnect hardening
3. large-file metadata + URI-backed storage model
4. resumable transfer subsystem
5. LiveEditPlayer foundation
6. proxy/checkpoint preview model
7. heavy-job scheduler/recovery strengthening
8. consolidated hybrid status
9. upgrade/migration tests
10. end-to-end device verification

Each slice must leave the project buildable and testable.

## 20. Explicit non-goals for this foundation

This foundation does not attempt to solve the separate generative-video quality problem discussed earlier.

It prepares the app to safely host heavier future animation/generation engines, but this work does not claim:

- Runway/Kling/Veo-equivalent synthesis
- arbitrary semantic motion from one image
- a new neural video foundation model
- unlimited hardware performance
- guaranteed playback of every possible codec/container
- survival of an uninstall/reinstall without an explicit backup/restore mechanism

Those are separate layers that can build on this foundation.

## 21. Definition of done

This foundation is not complete merely because an APK builds.

It is complete only when fresh evidence shows:

- persistent connection identity survives an in-place upgrade path
- hybrid control cannot gain native authority from public device ID alone
- app service reconnects and drains durable waiting work
- editor playback remains functional during representative background autonomous work
- a synthetic/test media metadata path above 5 GB passes without 32-bit overflow
- large-transfer code is streaming/resumable and does not buffer the full file
- process/restart recovery tests pass
- previous project data remains readable
- no Gallery enumeration permission has been added
- full repository CI and Android build are green
- the upgraded native app passes `app_status` and `app_self_test` on the connected device
