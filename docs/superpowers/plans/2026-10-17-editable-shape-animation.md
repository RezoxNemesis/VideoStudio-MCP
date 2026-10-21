# Editable Shape Animation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement inline. The owner has authorized autonomous continuation; no repeated design approval is requested.

**Goal:** Add smooth editable path deformation to the existing native drawing workflow.

**Architecture:** A focused `SketchShapeKeys` class validates, samples and mutates path keys. Existing document, edit, renderer, canvas, compiler and MCP boundaries delegate to it. Preserve existing source, identity, archive and revision behavior.

**Tech Stack:** Java 17, Android Canvas, Robolectric native graphics, existing Zod/MCP contract and Node tests.

**Spec:** `docs/superpowers/specs/2026-10-17-editable-shape-animation.md`

## Global Constraints

Native 4.6.1/code 471; path-only shape keys; maximum 240 per object; normalized finite coordinates; existing document budgets; no new dependencies; no live endpoint retries; no supplied portrait use; no replacement signer or phone acceptance claims.

## Review Focus

- Different curve command topology rejects before persistence.
- A node drag at an intermediate frame edits the visible geometry and retains timing.
- Preview caches, selection and focus must follow shape motion.
- Cel/frame operations preserve or intentionally freeze the sampled shape.
- Paged retrieval and shorter prepared-animation timelines must not silently discard shape data.

### Task 1: Validated shape keys and native renderer

Produces: `SketchShapeKeys.validate(JSONObject,int): int[]` (key/point counts), `commandsAt(JSONObject,int): JSONArray`, `sampled(JSONObject,int): JSONObject`, `apply(JSONObject,JSONObject,int): void`, `editOperation(JSONObject,int,JSONArray): JSONObject`, `metadata(JSONObject): JSONArray`.

- [x] Write native behavioral tests for moving curved geometry, timing, malformed topology, revision-bound persistence, and frame/cel behavior; run them and inspect the expected missing-feature failures.
- [x] Implement `SketchShapeKeys`; integrate document validation, edit operations, renderer sampling/hit testing, cel timing and consolidation safety.
- [x] Add paged `shapeFrame` retrieval and prepared-animation shape sampling; test retained/weighted paths, one-frame preview and explicit shortened-duration refusal.
- [x] Run focused native regressions; inspect actual output and fix causal failures.

### Task 2: Touch authoring and MCP contract

Consumes Task 1's `commandsAt`, `sampled`, `editOperation`, `metadata` and validation semantics.

- [x] Write actual native shape-dialog and node-drag regressions and strict MCP schema/version tests; run to observe missing behavior.
- [x] Integrate sampled selection/focus/preview and shape-key node operations in `SketchCanvasView`; add the shape-key timing dialog to `SketchStudioActivity`.
- [x] Add the bounded shape-key schemas, paged lookup field and version guards; advertise native source 4.6.1/code 471 and exact capability limits.
- [x] Run focused UI/schema/transport checks and inspect the final touch path.

### Task 3: Independent visual evidence and source delivery

- [x] Render multiple independently authored curved-shape subjects through `SketchFrameRenderer`; preserve editable documents and inspect the actual frames. Do not use the reserved portrait.
- [x] Run the affected regressions, one final full native suite and `npm test`; preserve execution summaries.
- [x] Review the complete diff, save a local source commit and refreshed downloadable artifacts, and record remaining artistic and device limitations.
