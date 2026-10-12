# Source annotation and ordered shot authoring

This extends the existing native `RigEditorActivity`; it does not import the older editor branch. The phone and MCP use `NativeRigAuthoring`, `Rig2DProjectStore`, `StoryboardContactPlan` and `StoryboardProgramStore` for the same source-bound annotations.

## Phone workflow

Open a registered project image in the source actor editor. Frame, Black/White and Profiles choose the exact image, role and saved profile. A unique saved role can reopen directly; ambiguous profiles require a selection. Saving an existing profile updates its ID with an exact profile digest and source-project revision, avoiding a second ambiguous actor.

The canvas edits the sixteen named joints, body-polygon vertices, original sword hilt/tip and original sword-polygon vertices. The sword belongs to the selected actor's `swordHand`. Handles drag directly; Coordinates sets exact source U/V. Vertex insertion/deletion is bounded to 64 vertices. Two fingers pan/zoom, Fit restores the source view, and Undo/Redo retain up to 32 complete draft gestures. Hidden anatomy is never generated.

Image hashing and decoding run on an owner worker. One private encoded snapshot supplies the displayed pixels, EXIF orientation and source SHA-256; handles use the original oriented dimensions rather than sampled-bitmap rounding. New marks bind those displayed bytes. Existing legacy profiles remain editable without a claimed source hash until the owner explicitly reviews the marks against the displayed original. A profile already bound to different bytes cannot be relabelled by reopening it. Saving checks the actual current source bytes and returns the exact published profile receipt.

Contacts opens an ordered program editor. Add frame appends an explicitly selected source occurrence. Sequence reorders/removes occurrences and clears drafts for changed shot positions. Repeated source pairs remain separate shots. Each shot selects exact Black/White profiles at both endpoints and an explicit choreography beat; the initial beat is missing. Foot plants select leading/trailing foot, start/end shot phase and an oriented source U/V anchor. The original-source preview accepts a tapped anchor; Copy From/To foot copies an existing named source point only after that owner action. Interval application uses the shared endpoint/overlap parser. There are at most 32 intervals per actor per shot.

Save program writes a bounded atomic project-private record containing source order, original hashes/geometry, exact actor profile digests, per-shot contacts and choreography. Existing program replacement requires its prior digest; a stale source revision or profile/image change fails while retaining the local draft. Saved programs are selectable in the same dialog. The dialog retains one current unsaved draft per source project for reopening; saved program files are bounded to 64 per project and 512 KiB each.

Saved program editing uses a separate bounded `readForEdit` API. It returns the old saved digest and proofs with explicit stale warnings, without source-byte verification or render readiness. Current rig selection clears affected actor foot drafts; Review/Rebind clears the selected shot's old plants/proof and explicitly binds the current source geometry. Existing geometry is not silently replaced during redraw. Saving still verifies current sources/profiles and exact program CAS. Rendering/recovery keep strict `load`, so reopening an old draft never authorizes its old proof.

If a saved source registration is missing, edit-only reopening retains its ordered occurrences and original proof with a warning. Geometry loading skips the missing registration, keeping Sequence repair controls reachable. The owner can remove or explicitly replace that occurrence with a registered source; changed shot positions reset their plants/beats. Strict saving and rendering continue rejecting an unrepaired missing source.

Render saved program submits the saved program ID/hash directly to the native series service. It requests the whole ordered program at 24 fps with eight samples per source transition and `cinematic_action`/`SOURCE_RIG`. Output dimensions preserve the source aspect exactly within the native even-dimension/pixel budget. Unsupported aspect registration, missing annotations, unreachable plants and other admission failures remain explicit. No annotation-complete flag constitutes cinematic approval.

The existing Storyboard Motion Studio labels automatic/portrait choices as `source_deformation` drafts and the explicit environmental/camera choice as `environmental`. Those intents travel with the actual service request; they do not inherit default cinematic intent. Its cinematic source-rig choice and the old Moving Fighters button now open the saved-program/contact editor instead of sending obsolete six-beat action names without an authored program.

## Scope and evidence limits

- Draft canvas zoom/pan returns to Fit after Activity recreation. Annotation/history/source binding state is retained; accepted worker saves survive Activity teardown. If save completion races recreation, reopen the saved exact profile before another update so the restored draft obtains its current receipt.
- Actor/weapon masks and foot plants are source 2D annotations. General occlusion layers, hidden-anatomy inference, unseen-pose models and comprehensive directorial timing are not claimed.
- Foot and hilt solver predictions are not decoded-output measurements. Native admission, rendered evidence and owner visual acceptance remain separate gates.
- The private Test 1 original thirteen anchors and device are unavailable in this checkout. Synthetic regression images cannot replace that acceptance evidence.

Focused regressions cover reopening/updating one saved actor, preserving weapon anchors, independent repeated-pair shot records, program CAS, external original-image changes and stale source revisions. Root orchestrates Gradle verification; no independent build/test process is launched by the UI worker.
