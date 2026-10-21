# Native review and correction workflow

Owner request: an app-owned system which helps ChatGPT and the user coordinate animation work, remember mistakes, review drawings/joints/movement, propose redraws and corrections, and run when manually or remotely operated. Preserve existing work. No reference footage reuse, external artwork substitute, Gallery, or automatic claim of artistic quality.

This extends existing SketchVisualReview, SketchQualityAudit, SketchEditOps and transactional sketch history. It does not install an artistic-judgment model or promise correct artwork for arbitrary poses. The user or ChatGPT inspects native frames/crops and records visual decisions. Pixel metrics remain separate from artistic acceptance.

## First retained vertical slice

Target4.8.4/code487; existing anatomy fields keep their4.8.3 floors. New actions all require4.8.4. App-private durable review sessions capture an immutable source document, revision and SHA, target and actual existing native audit. Notes identify a frame range, optional object/region and outcome. Up to four alternative proposals per review store native vector/pose edits and validated detached candidate documents. All current drawing/animation timing and resolution stay unchanged. Source art is never changed by start/note/propose/preview/get.

A proposal is applied only against its exact source revision/hash through SketchProjectStore.mutate, preserving the existing operation receipt, history and live-event transaction. Replays are idempotent. Concurrent source changes reject. Applying one candidate closes that review; later changes use a new review. Normal sketch_undo/sketch_redo provide undo. Review history remains historical even after undo. A failed/invalid candidate never changes source. Current original-drawing policy applies during both proposal admission and atomic apply.

Initial supported correction operations: add/update/delete layers and vector/native retained objects; transforms, transform/shape keys, exposure and retained animation constructors. Exclude raster paint/dependency copies, reference tracing and imports, assets, canvas/clock changes and live/remote operations. Public paint pointers remain forbidden. Those authoring tools continue outside this coordinator. Existing native speech expansion is used where required. Invalid/unsupported operations fail explicitly; no private arbitrary-code execution. An optional manual Relax arms recipe uses existing human pose channels and changes all retained keys without changing other data; the user must inspect resulting contacts. This is a bounded mechanical recipe, not automatic anatomy evaluation.

## API shared by touch and MCP

`SketchCorrectionOps.execute(Context,String,JSONObject)` handles:

- sketch_correction_start: sketchId, reviewId, expectedRevision, target (1..500 chars), optional frameOffset/frameLimit/frameStep/motionRequired. Existing audit limits:24 frames,step1..16. Captures existing native SketchQualityAudit result. Same reviewId and identical start request replay idempotently; conflicting identities reject.
- sketch_correction_get: sketchId,reviewId. Returns {ok,review,currentSourceRevision,sourceMatches,sourceModified:false}. review contains reviewId,sketchId,revision (review CAS),sourceRevision,sourceDocumentSha256,target,status OPEN|APPLIED,audit,notes,proposals. No source geometry in responses; use existing paged sketch_get. Global review ownership checked on every call.
- sketch_correction_note: sketchId,reviewId,expectedReviewRevision,noteId,outcome ACCEPTABLE|NEEDS_REWORK|UNREVIEWED,note (1..1000),frameStart,frameEnd; optional objectId,bounds,proposalId. Bounds are normalized validated source bounds. Notes are immutable; exact note replay idempotent. Optional proposalId identifies candidate evidence; otherwise note describes captured source. At most32 notes/review. An acceptable note is an explicit reviewer statement, never an automated quality certificate.
- sketch_correction_propose: sketchId,reviewId,expectedReviewRevision,proposalId,summary (1..500),noteIds (1..16),operations (1..64). References existing notes and validates operations/candidate on a detached copy. Stores input/request hash, actual resolved ops, candidate doc hash and metadata. Exact proposal replay idempotent. At most4 proposals/review. No source edits, timing changes or automatic acceptance. Proposals require an OPEN, current source review.
- sketch_correction_preview: sketchId,reviewId,side before|after,frameIndex; proposalId required for after, optional crop. Uses existing SketchVisualReview detail high, returning existing sketchPreviewImage and exact snapshot/region/hash metadata. Preview is explicitly bounded decision evidence; unchanged production canvas and clock remain in metadata. No tiny review thumbnail used to claim art acceptance. Runs off UI thread.
- sketch_correction_apply: sketchId,reviewId,expectedReviewRevision,proposalId,expectedRevision,operationId. Requires OPEN session/exact source, except exact committed-operation replay. Uses existing atomic mutation/history/live-events and original drawing policy; records chosen proposal and edit receipt. Returns review and edit with sourceModified true and undo action+expectedRevision. Crash after source mutation can resume via same operation receipt. A different apply operation cannot silently claim an old result.

Review/state storage is separately transactional; source apply is not falsely advertised as a distributed transaction. Recovery reuses the immutable proposal and ordinary edit receipt. Store bounds:16 OPEN reviews (completed reviews retained),128MiB total review storage,4 candidates and32 notes each, source/candidate <=existing4MiB; no automatic deletion of user work. SQLiteLargeText for large JSON. IDs use existing safe identifiers.

## Manual UI

Sketch Studio > More > Review & correct. Asynchronous native work with cancellation/generation/lifecycle checks. Start/resume a review, enter target and inspected frame range/region, record a note, choose a human for Relax arms or enter existing native vector correction operations, prepare proposal, inspect before/after at selected frame/crop, apply and return to normal editor Undo. Preserve entries after errors and reject stale source. Show actual operation scope and review state; never automatically mark artwork acceptable. Add concise manual/MCP guidance with supported bounds. Existing Visual QA remains available unchanged.

## Verification

Real native SQLite source/session persistence, detached preview and edit output tests. Before implementation, demonstrate missing workflow via a reflection-based RED. Verify unchanged source on proposals and failed/invalid edits; strict ownership, IDs, field/range types, note references, replay conflicts, source/review CAS, stale changes, active original policy, one atomic history step, undo/redo, restart persistence, crash-receipt recovery, unchanged FPS/canvas/frames and actual before/after PNG/hash differences. Separate numerical/technical PASS from visual decision. UI and Node schemas/version/admission checks share actual native contract. No local Gradle until existing hand matrix releases its process; isolated native execution must be labeled. Preserve every generation/evidence. No merge/deploy/stable publish or device/live-MCP/Android export claim without evidence.

## Owner refinement: executable recipe library

The user additionally requests integrated code/assets which offer many repeatable style and animation choices and encode expected results. Add a bundled declarative recipe catalogue, not runtime code execution or a claim of model training. Actual native authored presets/controls are reused; no external artwork assets are substituted. Each recipe has a stable ID, compatible object kinds/style conditions, label, expected change, actual native edit scope and required checks. Durable review notes record decisions rather than claiming automatic visual learning.

- New seventh action sketch_correction_options: sketchId,reviewId,objectId. Returns bounded app-owned applicable/disabled recipe metadata and source binding, no source edit.
- Propose permits EXACTLY one route: operations OR recipeId+objectId. The native recipe helper emits the actual update_object operations against the frozen source, then the normal detached proposal flow validates them. Persist resolved operations and recipe/library identity with the candidate; replay never silently recomputes a changed recipe.
- SketchCorrectionRecipes static options(Context,JSONObject document,String objectId), static operations(Context,JSONObject document,String recipeId,String objectId). Bundled asset animation_recipes/corrections-v1.json. Limit catalogue32 entries. Pure bounded interpretation of trusted bundled settings/pose patches; reject unknown kinds, keys, asset versions, incompatible styles and no-ops explicitly. Always preserve canvas/FPS/frames and unspecified settings/poses/expressions/speech.
- Initial catalogue reuses real features: neutral_arms; tailored_clothing; angular_face; soft_face; soft_shading; long_hair; natural_blinks; graphite_palette (anatomy only); cel_hands; smooth_hands; anatomical_hand_detail; contour_hand_detail. Mark unchanged/incompatible choices disabled with reasons. Human face/shading/clothing controls that do not affect anatomy_sketch must be disabled there. Recipe expected changes do not certify art/anatomy/acting quality. Manual dialog offers the same native options dynamically and previews candidates before apply. Existing Relax arms button may call neutral_arms recipe.
- Recipe selection data are versioned original app logic, not artificial intelligence inference, motion capture, hidden-side reconstruction or a guarantee of attractive artwork. New actions remain4.8.4.

Resume requirement: add eighth action sketch_correction_list({sketchId,offset?:0..1000000,limit?:1..16}) read-only. Returns {ok,sketchId,reviews:[{reviewId,target,status,revision,sourceRevision,noteCount,proposalCount}],offset,more,sourceModified:false}, newest first. This lets another ChatGPT session discover saved tasks without requiring a remembered ID. Native action remains4.8.4. Manual Saved reviews picker uses it. Completed sessions do not consume the16 OPEN-session slots; all metadata/snapshots remain under an explicit128MiB total budget with no automatic deletion.

Final review found that16 abandoned/stale OPEN reviews could exhaust the slots
permanently. Add ninth action sketch_correction_close({sketchId,reviewId,
expectedReviewRevision}), shared with **Close review (keep history)**. It may
close stale reviews, preserves snapshots/notes/candidates and source artwork,
uses review CAS and exact-request replay, and prevents new proposals/applications.
CLOSED remains readable and discoverable. Existing already-committed apply
receipts can still recover an interrupted acknowledgement without another edit.
Durable reservations OPEN2048/CLOSED1024/APPLIED0 protect both closure and late
receipt recovery at the storage quota. Closure frees a review slot, not retained
snapshot storage. No automatic purge or storage offload is added in this version.
