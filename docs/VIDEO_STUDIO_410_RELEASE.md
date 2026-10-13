# Video studio 4.1.0 — native painting and the ChatGPT hand

This compatible update retains `com.rezoxnemesis.videostudio.next`, the pinned original signing identity, device-owned connection key and existing projects. Install it over the existing app; preserve app data.

## Shared drawing tools

Human touch controls and native agent edits use the same paint cels and real brush engine. Paint, erase and owned-reference clone modes support round/flat tips, pressure, hardness, flow, stroke opacity, spacing, grain, angle, alpha lock and clipping. Clone samples the source texture across the actual footprint, with precise affine mapping and captured animation-frame transforms. It does not reduce a photograph to one sampled brush colour.

Immutable 256-pixel tiles retain detailed pixels separately from the document. Even canvases up to 2048 × 2048 use bounded interactive previews and onion skins. The UI prepares image/paint resources on workers, presents actual brush previews before finger lift, preserves unfinished gestures through saved chunks and cancels unsaved preview pixels when navigation starts. Long gestures split into bounded accepted strokes; chunks are individually undoable.

HSV/hex colour controls, palettes, revision-bound paint/reference/composite sampling, clone-source settings and paint cels are available in the native studio. Snapshot undo/redo, atomic revisions, durable receipts and accepted stroke IDs prevent retries, stale edits or recovery from silently duplicating work. The stroke journal is paged; replacing individual earlier strokes is deferred. Paint storage is bounded to 512 MiB per sketch, with 8192 strokes per cel ancestry and free-space reserve; no destructive garbage collection is introduced.

Lazy books support up to 10,000 pages. Only the active and existing neighbouring pages supply interactive onion skins. Native animation exports run in segments of at most 1200 frames, with immutable owned dependencies and exact authored frame timing.

## Visible agent hand

The small native hand follows the actual displayed stroke prefix, including paint dab positions, pressure, keyed poses, curves and newly appended travel. Appending points keeps existing ink visible. The hand holds its last displayed position while the next brush result prepares; it never invents drawing movement ahead of the pixels.

Actual tool labels and the matching dropdown show accepted tool choices. Global actions such as layers, timing and canvas settings use a stationary hand beside their actual status caption. Workspace provides a display toggle that preserves live watching. Completion, cancellation and human takeover clear the agent overlay. The hand is a viewing aid and is excluded from artwork exports.

## Editable archives and connection surfaces

`.vssketch` archives contain a checked document and its reachable references, paint tiles, journals and copied clone sources. Folder-scoped native Drive storage remains the cloud route. Restore verifies entry paths, hashes, dimensions and complete PNG pixel decoding, then creates a new document. Exact operation receipts recover interrupted completion without requiring the transferred archive to remain available. Limits are 256 MiB compressed, 672 MiB expanded and 32,768 entries.

New paint/read/archive commands are admitted through the native encrypted app-key route and run outside the UI thread. Drive transfer commands use a separate executor from interactive authoring. Strict Node schemas and version gates reject incompatible APKs before relay admission. Source changes do not activate the deployed primary Worker: its existing deployment failed because Cloudflare account/token bindings were absent. The encrypted backup route already has authenticated native import receipts; installing this APK is a separate requirement for new paint commands.

## Evidence and acceptance still required

The complete Android suite passed **795 tests in 159 suites**, with no failures, errors or skips. The integrated focused pass covered 175 tests, including all 14 human-painting cases and the real hand playback checks. Node checks passed 195 tests and 306 smoke assertions; the final changed-contract subset was rerun after frame mapping/schema changes. Independent source review found no further scoped release blockers.

The three supplied images are preserved as verified imported native assets. The earlier native 777-object vector study was rejected for insufficient detail. This update supplies substantially stronger authoring tools; build success is not evidence that the requested reference drawings or imagined follow-up frames meet the visual target. Actual updated-phone drawing, likeness, continuity, latency and screenshots remain acceptance work. Clone painting and geometry tools do not recover an original artist's stroke order or constitute a learned image-generation model.

Signed artifact verification and the direct APK download are published separately with the exact source/build proof.
