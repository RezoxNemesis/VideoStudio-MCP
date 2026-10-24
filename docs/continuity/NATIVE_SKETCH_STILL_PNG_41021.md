# Native still PNG export continuation

The installed original-signed523 candidate can animate image overlays. Actual
Android photo-panel testing exposed an awkward reusable-image workflow: the
private QA caller could render a full Sketch frame and register its PNG, but
ordinary manual/MCP users had only the indirect single-frame MP4 export route.
This feature exposes the shared native operation without requiring video encoding.

Current base: verified original-signed clock repair524,
`7f638afe86173dd03bd0ef3e59e7173798b50490`, version4.10.21/code524.
This PNG feature now targets4.10.22/code525 on
`feat/native-sketch-still-png-v41022`. Its earlier4.10.21 reservation was superseded
by the critical clock repair. The historical filename is retained.

Root scoped native28/28PASS5.613s covers core/job/manual/recovery and trusted-root
alias behavior. Node827 plus306smoke checks pass. Earlier failures remain retained,
including a manual Close/admission race and the top-level capability-placement
defect. The three PNG-owned SQLite stores now close; descendant symlinks remain
rejected. Full combined525, actual APK UI/PNG execution, CI and signing remain
pending. The separately prepared Quiet Canvas editor UI will be integrated before
that full validation. Voice work is isolated and is not part of this candidate.

## Contract and behavior

- Extend existing `sketch_render` with optional literal `format:"png"` and a
  required strict integer `frame` within the source document clock.
- Omitted format follows the exact old MP4 path; preserve request fingerprints,
  old feature minimums and opaque archive floors. Reject other formats, null,
  unknown fields, and PNG combined with aspect/quality/book arguments.
- Freeze the exact source document, revision and referenced dependencies; use
  VideoStudio's full `SketchFrameRenderer.render(frame)`, preserving transparency
  and document dimensions. Never use the bounded preview as the saved asset.
- A deterministic owned output project contains one PNG asset and no clips.
  The result includes project revision, asset ID, MIME, hash, byte size and
  dimensions so existing `project_import_asset` can perform ordinary CAS import
  into a chosen destination. Do not mutate an unrelated active project/clock.
- Keep source, working reference and derived PNG identities distinct. Return
  source provenance conservatively; source-assisted pixels are not original art.
- Share operation-ID intent/conflict protection across PNG/MP4 paths. Check
  cancellation during worker processing; publish files atomically, commit durable
  asset ownership and receipt with recoverable crash ordering, then replay the
  original receipt safely. Preserve source-integrity and stale-revision checks.
- Manual File→Save current frame as PNG captures exact frame/document/revision/
  session/page identity, uses the same worker helper, and protects cancellation,
  stale navigation and exact-request retry. Completion explains Media Bin→Import
  from project. No new MainActivity intent API/automatic attachment in this scope.

## Verification ownership

Agents first prepare meaningful RED tests; root alone executes/freezes native
RED/GREEN, all Gradle/JUnit/ADB/model runs and final integrated verification.
Core/helper, UI, Node and native bridge files have separate owners. Root handles
metadata, coherent commits, feature push, exact-revision CI with all publication
inputs empty, independent original-signer/package/version/APK SHA checks.

Keep genuine failed generations and unchanged assertions. Technical PNG/codec
success is separate from artistic quality. No main/Worker/stable publication,
signer replacement, uninstall, data clear, MCP reset, external artwork substitute
or claimed completed film. Live owner endpoint is blocked by403 owner action;
do not retry or evade it. Isolated Android testing does not prove phone/live MCP.
