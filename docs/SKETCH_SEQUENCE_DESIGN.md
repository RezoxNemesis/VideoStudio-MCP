# Continuous Sketch Studio sequences

The owner requested feature-length continuous, editable animation frames, broader drawing
tools and direct 2D animation import. Existing native sketch books already allocate
up to 10,000 cels lazily, with adjacent-page onion skin, exposures and cel copying.
The missing production paths are long export, direct raster animation import,
reusable artwork and explicit shot/acting/contact planning.

## Implementation

* Retain the document format, 1,200-frame standalone sketches, application identity,
  database contents and all existing short exports. A book is the long sequence.
* Raise the book ceiling to 1,000,000 cels/output frames (86,400 frames/hour at 24 fps; 216,000 at 60 fps). Export long books through immutable page/dependency
  snapshots and a lazy native Canvas frame source. Feed each bitmap directly to
  Android MediaCodec; retain no intermediate output PNGs or per-frame project clips
  for long exports. Bound source storage and bitmap lifetimes; reject exhausted
  storage explicitly. Retries use the frozen generation, never newer artwork.
* Map exposure holds to global frame numbers. Provide paginated frame sheets with
  authored cues for pose, turn, expression, camera and contact. Cues are direction
  for artists/agents, not automatic drawing or a proof of blade contact.
* Import owner-selected, app-private project PNG-sequence ZIPs and video into new
  books, one cel at a time, with exact source hashes, stable operation identities,
  resumable imports and original media preserved. Decoded pixels become raster
  cels; original vectors, separate limbs and hidden views cannot be recovered.
* Save reusable native artwork components by category and place them as editable
  layers in other sketches. Preserve dependencies, palette appearance and revision
  checks. Keep original-strokes sessions protected against copied artwork.
* Expose the same operations in native UI and MCP contracts. Publish honest
  capabilities and a concise authoring guide covering construction, posing,
  expression changes, shot planning, contact review and native export.

## Verification and acceptance

Use native Skia workspace tests for 10,000-frame ordering and feature-length limits, held cels, immutable
retries, changed-source rejection, ZIP ordering/pixels, import recovery, cue
pagination and reusable-component dependency/revision handling. Run transport
contract tests, then the full Android unit suite once. Device MediaCodec,
real-time playback and in-place signed upgrade require an actual device and the
original signer; they remain unverified here. No phone or artistic acceptance is
implied by these tests. The earlier 20-second fight remains a separate art gate.

## Execution rulings

The owner's standing autonomy instruction and AGENTS.md authorize implementation
without routine design approvals. Work runs inline in the existing isolated
worktree. `.work/fight20-progress.json` is the persistent continuation ledger.

## Tasks

1. Preserve and finish optional rigid head-mask regression repair.
2. Add a disk-indexed lazy frame-source boundary and long book export; test 10,000 rendered frames and one-hour frame mapping without frame-sized allocations.
3. Add global frame mapping and revision-bound direction cues/frame sheets.
4. Add source-bound, resumable ZIP/video import and native UI entry points.
5. Add an owned reusable component library and UI/MCP operations.
6. Verify contracts, native rendering/import, full suite and source build limits;
   package source and the workflow guide for review.

The owner expanded the target to one-hour films during implementation. Long
snapshots use an on-disk SQLite page index instead of a giant JSON inventory.
Requests and frame sheets remain paginated; the existing durable mailbox identity
and job recovery remain unchanged. A duration ceiling is not a promise of realtime
encoding, device availability or professional drawings.
