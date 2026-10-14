# Sketch character animation implementation plan

**Goal:** Prepared drawings become editable articulated2D animation with a shared human/agent workflow and an inspected100-frame workspace film.

**Architecture:** Pure program compiler/kinematics → existing SketchDocument/SketchFrameRenderer; native frozen-source animation store; visual Android authoring activity; canonical MCP/mailbox actions.

**Spec:** docs/superpowers/specs/2026-10-14-sketch-character-animation.md

## Global constraints

Preserve existing application ID, signer and source sketches. No Gallery. No new paid services. Retain document resource bounds and atomic CAS/idempotency. Label workspace artifacts and phone evidence separately.

## Tasks and verification

- [ ] Engine: new SketchAnimationCompiler/kinematics tests. Fail first on missing articulation; verify hierarchical rotations, smooth/hold endpoints, IK reach/contact errors, invalid hierarchies/bindings, finite bounds, weighted geometry and complete source immutability. Compile and sample APIs share a model.
- [ ] Store: new SketchAnimationOps tests. Save/get/list/bake use frozen source revisions, durable operation receipts and new-only publication through existing importOwnedDocument/copyOwnedDependencies. Test retries/conflicts, concurrent edits, raster dependency integrity and interrupted publication.
- [ ] UI: SketchAnimationActivity plus SketchStudio entry. Test actual entry/activity form, joint drag/key operations, background previews, save/bake and safe local draft recovery. No rawJSON-only authoring surface.
- [ ] Integration: closed action lists, background dispatch, strict relay schemas/version gates, capability metadata and4.4.0 package version. Test canonical/alias admission, version rejection and schema bounds.
- [ ] Original film: new fixture and native-graphics render test. Draw source vectors, animate exactly100frames24FPS, publish editable source/program/output JSON plus per-frame numeric checks, contact sheet and exact-frame movie. Inspect actual visual artifacts and correct measured flaws.
- [ ] Guides: human walkthrough, native ChatGPT JSON workflow and in-app help; describe limitations of unsegmented raster artwork and authored viewpoint changes honestly.
- [ ] Independent review, focused/full checks, original-signer APK, public artifact verification and evidence report.

## Review focus

Source/reference transforms must not be silently lost. Root-only or camera-only movement must not pass articulation checks. Contact IK must use pixel geometry on portrait canvases. Human pose edits must survive save/reopen and avoid stale preview results. Frozen raster dependencies must resolve under the new output ID. Budget/cancellation failures must not publish partial drawings. Native GPU/phone acceptance cannot be inferred from workspace Canvas output.
