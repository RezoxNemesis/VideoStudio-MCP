# Authored layer workflow implementation plan

Goal: replace ambiguous cutout/drawing admission with a reusable, explicitly
reviewed authored-stroke workflow on4.9.1, yielding4.9.2/code495.
Spec: ../../LAYERED_DRAWING_WORKFLOW.md

- [x] Add failing native workflow-policy and persistence regressions.
- [x] Implement strict persistent stage metadata, authored-operation admission,
  immutable hidden reference, review content hashes, progression and finalization.
- [x] Add actual native reference/artwork/comparison preview and image tests.
- [x] Add simple manual stage/review controls and a separate reference panel;
  test workers, lifecycle, exact normal edit persistence and Undo.
- [x] Expose strict stage operations and authored precision contours through the
  existing MCP contract; add the read-only preview action and consistent floors.
- [x] Run focused then full Android and Node/MCP regressions; inspect actual
  native screenshots. Retain technical/artistic outcomes separately.
- [ ] Commit/push the feature SHA, build with original-key Actions, verify exact
  source/package/version/signer/hash and deliver a candidate with evidence.

All source4.8.8 WIP stays separate. Parent owns Gradle. Core and UI edits use
disjoint files. No external art model, pixel-copy reveal, new signer, Gallery
access, automatic quality certificate, main merge or Worker deployment.
