# Portable saved animation scenes —4.10.9/code512

Existing sketch archives retain baked keys and owned assets, but previously omitted the saved animation database recipe. This optional extension carries one selected scene, including its exact frozen source artwork, rig, pose keys, weights, timing, contacts, camera and cel choices. It adds no canonical action.

`sketch_archive` optionally accepts both `animationId` and `animationRevision`, alongside the ordinary source sketch/revision/operation identifiers. Both fields are strict, and explicit selection requires native4.10.9. Omission preserves v1 archive bytes, fingerprints and the old4.1.0 floor. Restore arguments remain opaque, with their existing floor; a v2 archive requires a capable native reader.

The v2 inventory hashes `animation.json` separately from `document.json` and all original owned paint/reference dependencies. The bounded recipe binds source identity, revision and hash, selected scene revision and complete canonical program hash. Programs are validated before publication. It does not include a raw database or unrelated scenes.

A private atomic snapshot freezes the scene before the first export artifact. Retry uses that snapshot rather than a later saved program. Restore creates a new sketch and deterministic new scene identity. Stable receipts bridge both database commits; recovery uses the validated frozen source even if a person edits the restored sketch before scene publication. It never overwrites those later edits or a later saved scene revision.

Manual:2D animation > Save scene > Archive scene. File > Restore archive and local Import editable animation use the same validator and offer Open animation scene afterward. Transfers use the app-linked workspace; preparation alone does not claim remote upload. Failed transfers retain their exact request for retry.

Fresh focused native33/0/0/0 tests pass, including seven new scene-package regressions. Actual selected-actor native package study adds one technical pass: all96rebaked frames are pixel-identical after restore, with one self-contained editable motion archive. Native/Node capabilities match on actual native output. Fresh Node766/0/0 plus306smoke pass after preserving and repairing two capability-registration VM failures. Full combined native/CI/signing remains pending. Actual manual archive-button flow also passes: the native writer includes the saved recipe, while missing linked-project transfer retains its exact request and makes no upload claim.

The study remains one source-assisted cuff gesture, visually NEEDS_REWORK; it is not newly drawn artwork, accurate alternate views, natural full-body acting or the240second film. Renderer is actual VideoStudio native app code under SDK33 Robolectric; no phone, GPU or Android codec execution is claimed. Failed studies and previous archives remain preserved outside Git. No main/Worker/stable publication, signer or device/data/MCP identity reset.
