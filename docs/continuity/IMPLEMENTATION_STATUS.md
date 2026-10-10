# VideoStudio implementation continuity

Updated: 2026-10-10. This is an implementation checkpoint, not a release or a
verification report. The complete product described in
[ENGINEERING_BLUEPRINT.md](ENGINEERING_BLUEPRINT.md) remains unfinished.

## Owner instruction and execution phase

The owner requested autonomous implementation, ordinary safe workspace/GitHub
decisions without approval prompts, and **product implementation before testing**.
Do not execute suites, Android builds, emulators, device runs or CI while this
instruction remains active. Source inspection, API reference inspection and
authoring future regression coverage have been used. No current changes have
been compiled, executed, deployed or installed. Earlier cloud onboarding results
apply to the original checkout, not this implementation.

Repository baseline: `d652066ce428e4f128514da4d1caee919974554e` on `main`.
Implementation branch: `studio/complete-editor-blueprint-20261010`.
Source checkpoints `bf4205f` and `5786eab` implement the editor/animation work.
The branch also incorporates upstream `95e93d4`, preserving its separate personal
cloud workspace and production browser entry wrapper. Existing and new future
regression commands are both retained; none were executed for this integration.
The stable native MCP endpoint and device-owned identity remain v3. App version
metadata is still 3.4.7; a finished, verified release must choose its new version
and preserve signing/identity continuity.

## Source implementation in this checkpoint

### Manual editing and the shared project graph

- A fixed editor layout provides a large reusable source/program monitor,
  transport, a custom multi-track timeline and compact scrolling inspector panels.
- Timeline scrubbing, drag moves, head/tail trims, snapping, pinch zoom, track
  flags, track selection and editable clip parameters use the same project
  transactions as native MCP commands.
- Video audio extraction keeps one owned video source and creates a linked audio
  clip. Link/unlink, move, trim, split, duplicate, ripple/delete and speed changes
  preserve aligned program windows across peers, with all affected locks and
  overlap constraints checked. Embedded audio suppression cannot be defeated by
  authored volume keys. Speed changes retime animation windows and absolute keys.
- Real PCM waveforms decode lazily in aligned ten-minute source windows with up
  to 2048 peak bins. Cache identity, CRC records, decoder time limits and bounded
  memory/disk/worker queues avoid loading a whole large source.
- Roll, slip and slide edits share revision-checked transactions with MCP. They
  preserve exact outer/program spans and source handles, authored clocks and all
  affected linked peers. Ambiguous neighbour layouts are rejected explicitly;
  the owner can inspect a numeric candidate before committing one undoable edit.
- Durable point/range markers have names, colors and notes, timeline glyphs,
  seeking and snapping. Project In/Out bounds drive rehearsal playback/loop and
  signed shuttle; shortening a project disables an invalid range with its reason
  while preserving authored bounds. A separate explicit range-export request pins
  the accepted original graph and reviewed In/Out bounds; recovery reconstructs
  the same rebased graph, authored clocks, enabled layers and final empty span.
- Program preview evaluates absolute clip placement and track visibility,
  mute/solo state. Images use a bounded URI decoder with EXIF orientation;
  videos/audio use bounded ExoPlayer bindings. Frame stepping and source/program
  switching do not require an offline export.
- A monitor index rebuilds once per supplied project revision and locates active
  clips by lane binary search. Asset/track lookups, program duration and unchanged
  audio-processing signatures are cached instead of rescanning/serializing the
  entire graph on every transport tick. This has no profiling evidence yet.
- Preview/export aspect selection uses a centered program canvas. Direct monitor
  drag/pinch/rotation provides temporary transforms and commits one undoable edit
  on release. Motion curves are cached per project binding.
- Fullscreen, loop, frame stepping and J/K/L shuttle are wired to playback.
  Reverse shuttle performs silent frame seeks; it is not reverse export support.
  Automatic preview selection requests cached 360p under thermal/shuttle pressure,
  540p during playback and 720p while paused, with original-source fallback when
  no validated exact-tier proxy exists. It does not automatically enqueue a build.
- SQLite revisions reject stale edits. Split/trim preserve exact output durations
  and animation windows. Persisted undo/redo, named snapshots, restore and project
  duplication preserve stable source references.
- Owner picker metadata work runs off the UI thread, then registers against the
  current graph revision. The media bin and timeline share registered assets.
- Source relinking uses the system picker or an already imported same-project
  replacement through MCP. Stable asset/clip identities and old history references
  survive. Type, duration, audio availability and affected track locks are checked;
  stale proxies are invalidated. Active portrait layers must first be removed and
  rebuilt. Creative node caches now bind source identity and evaluation attempts.
- Portrait layers, procedural scene frames and narration use immutable UUID output
  generations. Portrait reuse checks captured registration/live metadata, bounded
  sampled analysis pixels and each layer's byte/SHA manifest; proof-less legacy
  results require regeneration. This protects older graphs from output overwrite,
  but sampled pixels alone do not prove every byte of an external image is unchanged.
- Standalone transparent title canvases are real owned assets; native title
  rasterization supplies fonts, color, placement, wrapping and supported animated
  reveals. Ordinary clip captions and title-only transform placement are separate.
- Owner export sessions have durable status, progress/cancel controls and result
  playback/share actions. They are separate from ChatGPT pause/cancel.
- Queued project exports persist a separate immutable accepted graph/revision with
  chunked SQLite metadata and SHA-256 integrity. Recovery/replay opens that graph,
  including admission interrupted before the recovery-plan commit. Referenced
  media stays protected across project edits/deletion. Terminal release checks
  exact request ownership and active native work; graph pins do not freeze mutable
  external-provider source bytes or implement segmented encoding.
- Export publication records the exact owned hidden MediaStore row before copying,
  verifies the copied bytes with a complete SHA-256 readback, and persists a
  verified pending receipt before making the row visible. Recovery reuses that
  URI; an interrupted partial copy restarts into the same hidden row. A visible
  output survives cancellation and its exact receipt can still be recorded.
  The cross-system insert-to-first-receipt crash window can leave a hidden orphan;
  this does not provide offset-resumable encoding or copying. Large-output readback
  cost and cancellation behavior have no device evidence yet.
- Process-wide heavy arbitration prioritizes owner jobs before queued autonomous
  work. An autonomous job still waiting before its work starts yields to a queued
  owner job. Active heavy work is not unsafely interrupted or duplicated.
- Service lifecycle interruption preserves resumable work intent rather than
  cancelling it. Recovery considers owner requests first and can rearm unfinished
  owner work while ChatGPT control stays paused. Android foreground-start limits
  still apply; no promise of execution while the OS forbids the service is made.

### Native render, motion and audio

- Preview video and export compile the same supported motion, transition,
  transform, color, title, mask, chroma and procedural pixel effects.
- Still/title GPU preview uses Media3 replayable bitmap input with the same effect
  graph and output-relative clock. Supplied generated portrait layers compose in
  their native role order. Actual surface presentation controls readiness; explicit
  failure falls back to a visible basic image/title preview. There is a four-surface
  process limit, 256 MiB conservative texture reservation (128 MiB on low-RAM
  devices), bounded bitmap decode and first-frame/presentation watchdogs.
- Export composes independent absolute-time visual/audio sequences with transparent
  gaps, a stable clock and track mute/solo handling. Missing sources and unsupported
  operations produce explicit failures rather than silent omissions.
- Supported pixel operations include grain, vignette, scanlines, posterization,
  pixelation, RGB split, sharpen/glow and feathered shape/chroma masks. Authored
  effect stacks and keyframes have bounded budgets and explicit validation.
- PCM gain automation uses the clip output clock, including fades and preserved
  animation offsets after splits. Optional `effects.audio` adds real three-band
  EQ, mono/stereo pan, a linked gate, dynamic high-band de-esser, streaming delay,
  algorithmic room reverb, linked peak compression and a ceiling limiter. Delay
  and room histories share a 16 MiB per-clip budget and clear on seek/cut. Wet tails
  remain inside clip spans; final authored gain/fades control wet and dry output
  before the one final ceiling. This is not measured room convolution or denoising.
- Monitor audio meters show real per-binding post-DSP decoded PCM peaks. They are
  not a master mix, LUFS measurement or oversampled true-peak analysis. Preview
  signal processing precedes playback time stretching; device comparison with the
  export chain remains deferred.
- Native export checks its resulting media container/duration/dimensions and a
  bounded decoded frame before publishing its completion receipt. This validation
  is runtime code; it has **not** been exercised in this session.
- Device software AVC fallback is attempted once only for qualifying encoder
  failures when a software encoder is available. It does not promise that every
  device has a software codec or that every decoder failure can recover.

### Direct ChatGPT media import

- Native attachment tools accept host-supplied HTTPS attachment URLs and additive
  file metadata; a file ID alone is not falsely treated as downloadable bytes.
- Specific tools and generic `app_execute` attachment actions use the private
  authenticated Worker handoff, preserving existing relay RPC compatibility.
- Worker streaming preserves range/validator behavior, validates safe HTTPS
  redirects and bounds inline attachment staging outside durable command payloads.
- Native ingestion validates type, duration, dimensions and a readable sample,
  performs storage preflight and resumes transfers when source validators allow it.
- Import completion refers to committed project-owned asset/clip state, not queue
  admission. Concurrent same-target ingestion is serialized through publication
  and registration. Referenced media is preserved when cleaning rejected imports.

### Storage and offline project metadata

- Storage Hub has five owner-selected SAF folder slots with labels and roles.
  These are owner-granted document-provider folders, **not five implemented OAuth
  account connectors**. Provider quota is unknown unless a real provider can supply
  it; no invented remaining capacity is shown.
- Project and model workspace archives use immutable generations, SHA-256 file
  records, readback and a commit marker published last. Restore plans validate
  complete declared file listings before local writes.
- Vault streams large workspace files in 256 MiB chunks with bounded buffers,
  individual/whole-file checksums and atomic local publication after restore.
- Explicit original-source archives stream one registered asset into immutable
  256 MiB chunks using 256 KiB buffers, with a 64 GiB source limit. An accepted
  source pin survives relinking and history pruning. Durable journals verify
  uploaded prefixes against both provider chunks and actual source bytes before
  resuming; a second full source hash and remote readback precede the commit marker.
  This is real SAF-provider I/O code, with unknown quota and no OAuth adapter.
- Source restore records its exact private target before copying and verifies
  chunk/whole checksums before atomic publication. Completed restored bytes can
  be reused after restart; an incomplete copy restarts. The service either relinks
  against the accepted revision or registers the restored copy with an explicit
  conflict receipt. Originals remain untouched.
- Native source archive/restore controls use the same durable service requests
  as MCP. Cancel retains the exact request, journal and pin until the owner resumes
  the same request or explicitly forgets its local retention. Forget waits for
  workers to stop, needs no lost provider access, and preserves media, catalogs,
  committed remote archives and remote draft chunks.
- Offload persists the exact archive tree/generation before deleting any data and
  removes only matching checksummed intermediates. Originals, generated source
  media, scenes, checkpoints and published renders remain local.
- Referenced private layer/proxy files in live graphs, undo, snapshots and export
  pins stay local. Deletion performs its last reference scan and file removal under
  one SQLite writer transaction. Cleanup rejects symlinks within the owned tree,
  bounds traversal and retains files when metadata cannot establish safety. A
  prior offload marker must be restored before a new archive can replace it.
- Exact URI ownership is cached in SQLite and invalidated by triggers across live
  projects, both undo sides, snapshots, export/source pins and DB-first archive
  records. One bounded rebuild replaces repeated whole-graph parsing during cleanup.
  Corrupt, incomplete, over-budget or low-memory metadata retains all sought files;
  existing archive metadata must finish strict bootstrap before cleanup is allowed.
- Restore/hydration uses the recorded archive capability, so changing the active
  storage slot cannot select another project's archive by accident.
- Private Worker metadata mirrors have bounded graphs, revisions, retained conflict
  candidates and audit records. They exclude source locations/media bytes and
  describe themselves as metadata-only with no cloud media executor.
- Native mirror sync/apply/reconciliation uses explicit actions, revision checks,
  existing-asset references and durable prepared/applied acknowledgement markers.
  Original source locators and private effect fields stay on-device. Exact retry
  acknowledges a committed native edit without reapplying it or discarding an
  offline conflict. Automatic background synchronization remains unfinished.
- Explicit keep-native resolution identifies the exact pending journal and current
  native/mirror revisions, retains bounded local resolution evidence and displaced
  Worker graph snapshots, and checks native readback before clearing the marker.
  Lost acknowledgement recovery avoids manufacturing a new native revision.

### Authored animation for the owner and ChatGPT

- A deterministic articulated 2D engine compiles hierarchical bones, inverse bind
  matrices, triangulated weighted meshes, sparse transform keys and connected
  two-link analytic IK. The shared sampler drives the native GPU image monitor
  and export. This deforms real owned source textures; it does not infer joints,
  synthesize hidden pixels, segment characters or create 3D scenes.
- The owner Animation inspector edits bind joints, FK channels, target/mix IK
  keys, named reusable poses and vertex influences. Native MCP exposes the same
  lock/revision-checked operations. Overlay manipulation uses the actual source
  fit/cover and motion mapping, clips the mesh to the source canvas, and retains
  off-image bone/IK handles. Unsupported crop/stack mappings visibly disable
  direct manipulation while numeric editing remains available.
- Exact sparse key moves preserve authored channels and easing in one undoable
  transaction and reject occupied destination rows. Split/trim/range export keep
  signed authored time; speed changes retime bone and IK keys consistently.
  Explicit sparse-row replacement removes omitted channels in the same staged
  mutation. Key editor defaults sample authored FK before IK; solved pose capture
  deliberately bakes the solver and disables IK at the applied pose time.
- A phone dope sheet groups actual sparse FK/IK key rows, seeks their output
  position and provides add/edit/delete and atomic authored-time moves. Region
  weight painting applies a bounded source-UV brush stroke in one transaction,
  with aspect-correct radius, signed influence changes, explicit falloff and four
  normalized influences per vertex. A separate bind canvas uses the real source
  artwork and rest mesh so posed monitor positions cannot misdirect the brush.
- The selected dope-sheet channel has an actual value graph with bounded
  fit/pan/zoom and vertical key-value editing. At most 256 cached samples use
  the compiled FK-before-IK or target/mix evaluator, including raw Bezier
  overshoot and exact fixed-key influence before channel clamping. Release
  changes one value at its existing time in one captured-revision transaction;
  unrelated sparse channels and easing remain intact. Drafts affect only the
  graph; time moves remain numeric, and dense curves require zoom for detail.
  `app_rig_curve` reads up to 256 distinct scalar samples from that same compiled
  evaluator, with exact held clip/authored clocks, revision and declared bounds.
  It is read-only project inspection; sampled extrema are not analytic extrema.
- Spatial cubic paths have editable anchors and tangent handles, optional tangent
  orientation and additive/replacement positioning. Cubic Bezier easing solves X
  before sampling Y; visual, camera, audio, rig and IK curves use shared controls.
  These are actual evaluators and editing widgets, not motion preset labels.
- Native frame drawing retains editable normalized paint/eraser strokes with
  pressure, brush color/width, onion skins and pinch/pan. Saving creates a new
  immutable PNG generation and atomically registers/redraws an exposure. Owner
  and MCP use the same bounded vector schema, renderer and exact revision checks;
  older exposures, undo and snapshots retain their original image generations.
- Shared cel-vector operations edit style, point position/pressure, whole-stroke
  translation and deletion, or point insertion/removal. Strict sequential batches
  preserve the input on failure and reject unknown IDs, out-of-frame translation
  and unchanged documents. Native MCP publishes a new immutable redraw at the
  exact revision; committed replay is checked before a relative edit reads its
  source, so recovered point moves do not run twice. The owner canvas retains
  edited vectors before replacement raster allocation and bounds its local undo
  history; a failed preview leaves valid vectors available for retry.
- Eight bounded private drawing drafts coalesce writes and durably acknowledge
  Save/Keep before closing. Loading and final retention freeze vector mutations;
  Undo stages a detached drawing before bitmap allocation, and failed previews
  retain valid vector data. Exact committed draft removal shares the writer and
  cannot delete a newer queued drawing. This has no restart/device evidence yet.
- The exposure sheet seeks real cels and supports redraw, new frame, hold,
  duplicate and delete. Cumulative frame boundaries and authored project cadence
  support integer 12/24/30/60 fps. Native export supplies one primary clock and
  matching still-image sample timestamps, explicit mux end time and full output
  sample-PTS inspection code. Mixed millisecond clips and exact cel boundaries
  share one renderer quantization policy with disclosed sub-millisecond changes;
  explicit range endpoints remain authoritative. Preview transport is continuous
  and frame stepping uses cumulative frame boundaries; pixel/cadence parity is
  unverified.
- Phone resource admission bounds rigs to 24 bones/512 vertices/1024 triangles,
  2048 combined keys and 256 KiB JSON. Drawing is bounded to 2048 pixels per edge,
  16 MiB ARGB, 512 strokes/8192 points/128 KiB vector JSON, with heap reserve and
  bounded onion decoding. Rigs validate hierarchy/deformation bounds before
  committing and GPU passes disclose fallback omissions. These limits are
  engineering choices, not crash-free or measured performance evidence.

### Durable command transport

- Native, legacy browser and runtime queues now use atomic checksummed UTF-8
  chunks instead of one Cloudflare Durable Object value. Bounded command/result
  budgets can carry the complete drawing/rig payload; unfinished work survives
  polling cursors, migration and terminal history pruning. Exact terminal
  acknowledgements reject conflicting delayed receipts rather than replacing
  the accepted result.
- Native command admission reserves bounded result storage before effects.
  Unacknowledged receipts are retained; a full outbox stops new dispatch rather
  than deleting earlier results. Payloads have a 512 KiB result bound and JSON
  nesting limit. Oversized responses produce explicit incomplete-delivery proof
  and actual operation/job identifiers; they do not rerun or label queued work
  completed. Revision-consistent native project pages and full single-rig/cel
  descriptions let ChatGPT inspect authored data without one enormous response.
- Browser runtime execution has a separate bounded IndexedDB receipt ledger and
  one same-origin Web Lock. It persists preparation before side effects and the
  exact outcome before acknowledgement. Failed acknowledgement retries do not
  rerun generation or Drive mutations. Captured project/device IDs stay attached
  through nested provider calls; changing owner selection cannot reroute them.
- Browser outcomes whose preparation was interrupted are explicitly uncertain
  and are not automatically repeated: the current browser provider adapters lack
  an end-to-end idempotent publication API. Unpersisted results block new side
  effects until retained; conflicting remote terminal results remain unresolved
  instead of erasing the local outcome. Browser host behavior is unexecuted.
- Browser generation registers only its newly generated metadata using an atomic
  append to the current project, rather than replacing captured asset/timeline
  arrays after a long render. New clips have stable IDs; identical replay does
  not replace newer generation state. Other captured project writes carry a
  content revision and reject stale replacement. Rejected registration retains
  actual browser-owned bytes and their metadata; it does not report Media Bin
  success. Legacy browser project metadata remains bounded to 112 KiB under its
  single-value storage path, with 64 KiB append requests. This is separate from
  the larger chunked native command queue and private metadata mirror.

## Known limits and unfinished product work

| Blueprint phase | Current boundary | Remaining implementation |
| --- | --- | --- |
| A — editor | Main editing paths, linked A/V, roll/slip/slide, markers/play ranges and explicit pinned range export, waveforms, per-binding meters and bounded GPU image/title preview rebuilt in source | General groups, compound/nested clips, retime curves, broad tablet/accessibility layout, full preview/export/device behavior review, robust codec recovery and segmented resumable exports |
| B — storage | Five SAF slots, immutable workspace/Vault archives, explicit original-source chunk archives up to 64 GiB with durable upload/restore intent; validated 360p/540p/720p source proxies | Independent OAuth/cloud connector adapters, quota APIs, multi-location source registry, actual large-media/provider recovery evidence, automatic adaptive proxy policy and storage policy |
| C — MCP/offline | Stable v3 retained; metadata-only mirror and native reconciliation integration | Protocol-v4 negotiation/scope migration, actual cloud executor and media authorization, offline heavy execution, render/model result delivery and comprehensive reconciliation |
| D — 2D animation | Hierarchical textured mesh rigs, connected two-link IK, reusable poses, sparse dope sheet and value graph, spatial paths, Bezier easing, editable paint/eraser vectors, onion skins, exposure edits and explicit integer export cadence with matching owner/MCP source paths | Fill/closed vector paths/regions, sprite-sheet/import tooling, cross-project rig libraries and artwork parts, additional constraints, robust occlusion, optical-flow refinement and interpolation, temporal/style/identity locks, actual phone render/performance evidence |
| E — generative models | Existing pack installer/registry and compute-plan metadata | Real executable model adapters, local/remote inference backends, generative I2V/T2V, continuation/regeneration, real upscaling/interpolation |
| F — 3D/VFX | Existing procedural drawing and new supported 2D pixel effects | Real 3D runtime, glTF/FBX/USD import, rigging, lighting/materials/physics, particles, render passes, tracking/roto and node compositor |
| G — audio/recap | Existing Android TTS plus gain/EQ/pan/gate/de-esser/compression/limiter and bounded delay/algorithmic reverb | ASR, transcript/word timing, voice registry and sentence regeneration, dialogue cleanup/denoise, ducking/sidechain, measured room convolution, master buses/loudness, lip sync and full recap production |
| H — verification/release | Deliberately deferred by latest owner instruction | All current builds/tests, render comparisons, emulator/device evidence, large-media recovery, profiling and release artifacts |

Preview is deliberately bounded to two simultaneous video decoders, eight visual
layers and four audio-only bindings. Export has its own bounded composition budget.
These are implementation constraints, not measured device performance claims.
Still/title GPU previews request supported color/pixel/motion graphs and supplied
portrait layers within the texture budget. Failure uses a basic image/title fallback
with disclosed omissions and approximate HSL color. Four-layer effects may exceed
the texture budget; simultaneous eight visual layers do not imply eight GPU still
pipelines. No pixel-parity or device reliability evidence exists yet. Audio gain up
to 200% and supported DSP use the shared PCM
processor, with source-clock mapping for preview and output-clock mapping for
export. Seek/automation alignment still requires device verification.

Reverse playback, freeze-frame rendering, optical motion blur and sidechain ducking
remain explicitly unsupported. Registered or installed model metadata is not an
inference runtime. Procedural imagery is not photorealistic video generation or a
real 3D scene renderer. No full-product completion claim is justified.

## Continue implementation

1. Continue the remaining Phase A/B paths above before expanding speculative model
   or 3D menus. Preserve honest unsupported errors and existing native endpoints.
   The latest owner steering prioritizes real articulated 2D animation and authored
   curves with both human editor and MCP controls before the remaining broad roadmap.
2. Add actual execution adapters and cloud media capabilities when their required
   runtime dependencies/configuration are available; do not substitute metadata
   registration or fabricated output for execution.
3. Continue through the remaining roadmap without routine approval questions.
4. Only after implementation is complete (or the owner changes the instruction),
   enter the testing phase, fix failures and gather the blueprint's required output,
   recovery, emulator and real-device evidence. Then release/deploy/install.

## Deferred verification inventory

When the testing phase begins, use the repository commands (`npm test`, Worker
bundle checks and Android unit/build tasks) and add targeted coverage for project
revision races, undo/snapshots, split durations, manual priority/cancellation,
attachment range/resume/idempotency, corrupt/missing archives and exact hydration,
proxy sound/fingerprints, title/motion/alpha render comparisons, audio DSP/seek and
mirror revision conflicts/restart acknowledgement, source-relink/cache evaluation
races, linked trim extensions and retime windows, loop/shuttle, real waveforms,
GPU surface release/timeouts,
immutable export admission/replay, output-receipt bin recovery, generation integrity,
atomic media cleanup, roll/slip/slide source handles and neighbour ambiguity,
marker/range persistence/shortening/loop, and ambience/gate/de-esser/final wet gain,
rest/posed mesh and alpha parity, IK reach/sparse keys/overshooting easing,
rig speed/split/range authored clocks, drawing draft recovery and pressure/eraser
parity, immutable cel request replay, frame-grid/ordinary-clip boundaries and
single-frame/final-partial-frame cadence, chunked queue migration and terminal
receipt replay, cross-tab browser execution and lost acknowledgement recovery.
Include pre-IK unchanged key editing, sparse-row replacement, bounded brush
normalization/collision/undo, EXIF bind coordinates and window-detach bitmap/draft
cleanup, outbox reservation/backpressure and exact result acknowledgement,
project paging across revision changes, and optional-field/depth validation.
Include scalar curve inspection and graph cancellation/commit against exact FK/IK
sampling, vector batch failure/replay, point-drag offsets/precision, mixed drawing
undo and low-memory pre/post-install draft/raster recovery.
Device checks must include the blank-monitor flows shown in the owner's recording and thermal/decoder/memory
constraints. Newly authored import/mirror tests have not been run.
