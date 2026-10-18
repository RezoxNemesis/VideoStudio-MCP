# Native character performance implementation plan

> **For agentic workers:** Use executing-plans for the native core; independent manual and MCP integration may run in parallel.

**Goal:** Retain a more detailed, articulated original character study in VideoStudio.
**Architecture:** A native constructor combines authored editable body/material paths,
shared arm/hand attachment transforms, and existing native facial/speech construction.
**Tech Stack:** Existing Java Android Canvas/JSON, Robolectric, Zod/Node. No dependency.
**Spec:** docs/superpowers/specs/2026-10-18-native-character-performance-design.md

## Global constraints

Preserve resolution/FPS/frame count, current budgets, original signer and all earlier
work. No Gallery, external animation author, worker deployment, main merge or stable release.

## Review focus

- Portrait/square canvas keeps human proportions and bounds.
- Locked/occupied layers and invalid settings reject without changing art.
- Manual cancellation/conflict retries retain current drawing and inputs.
- Rear/profile occlusion hides front-only anatomy and ornaments.
- Arm, wrist, fingers and held prop retain attachment at animation extremes.

### Task 1: Native constructor and evidence

Files: SketchCharacterAnimation.java, SketchCharacterArt.java, SketchEditOps.java;
tests SketchCharacterAnimationTest.java, SketchCharacterStudyTest.java.
Interface: static void SketchCharacterAnimation.apply(JSONObject doc,JSONObject op).
- [x] Write native dispatch tests for retained anatomy, attachment, bounds and invalid input; run RED.
- [x] Implement strict settings, native artwork and physical-time transform keys; run GREEN.
- [x] Render 6-second studies in all six views with real product audio, preserve screenshots/archives.
- [x] Inspect, reproduce visual defects, repair in product, rerender affected studies.

### Task 2: Manual and MCP parity

Files: SketchCharacterDialog.java, SketchStudioActivity.java, native capabilities,
src/studio-workbench-contract.js, src/index.js, relevant Node/native UI tests.
Consumes Task1 operation exactly as specified in the design; no private helper API.
- [x] Write/run failing strict contract and manual dispatch tests.
- [x] Implement numeric/style/view/gesture controls, native preview and async save.
- [x] Verify manual/MCP settings and source version admission.

### Task 3: Verified update

- [x] Fresh code review and focused/full regressions with exact source evidence.
- [x] Version4.7.8/code481, commit/push feature branch and one original-key CI candidate.
- [x] Independently verify APK package/source/signer and retain evidence/manual recipe.
