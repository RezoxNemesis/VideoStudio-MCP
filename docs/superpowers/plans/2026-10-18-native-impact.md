# Native impact scene implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline; preserve original evidence.

**Goal:** Build and inspect an original native ball/box interaction with manual/MCP parity.
**Architecture:** Native deterministic one-axis impact model, existing editable cel compiler, atomic scene assembly.
**Tech Stack:** Android Java, org.json, native Canvas, Robolectric, Zod/Node.
**Spec:** docs/superpowers/specs/2026-10-18-native-impact-design.md

## Global constraints
- Preserve application ID, original signer, device identity, databases and earlier artwork.
- No reference tracing/media reuse; no workspace-only animation generation.
- Retain 4 MiB and 4096-object document bounds; require 48..240 frames.
- Source version 4.7.3; new MCP operation gated to 4.7.3.
- No live worker deployment without legacy queue compatibility verification.

## Review focus
- Non-default FPS: contact must occur at an explicit frame without silent retiming.
- High or low masses/restitution: nonpenetration and stopping remain valid.
- Existing artwork: failed scene construction must leave the caller unchanged.
- Heavy scenes: budget errors must preserve stored state and undo history.
- Manual page switch during compile: reuse existing session/generation guards.

### Task 1: Native impact model and editable scene compiler
Files: create SketchSolidCollision.java and SketchSolidCollisionTest.java; modify SketchEditOps.java.
Produces: `SketchSolidCollision.programs(JSONObject doc, JSONObject settings)` and `apply(JSONObject doc, JSONObject op)`.
- [x] Write failing tests for native dispatch, contact, momentum, friction, invalid input and atomicity.
- [x] Run targeted Gradle tests; inspect expected missing-operation failure.
- [x] Implement strict model, bounded pose sampling and atomic two-actor construction.
- [x] Run targeted native tests and inspect output.

### Task 2: Manual and MCP entry points
Files: create SketchSolidCollisionDialog.java; modify SketchStudioActivity, SketchCapabilities, AppProtocol, build.gradle.kts, studio-workbench-contract.js, src/index.js; add native UI and Node tests.
Consumes: Task 1 apply/program semantics. Produces same manual/MCP operation.
- [x] Write failing manual saved-scene test and strict/version-gate contract tests; run red.
- [x] Implement discoverable defaults, manual fields, existing background worker guards and 4.7.3 gates.
- [x] Run targeted UI/native/Node checks.

### Task 3: Visual verification and retention
Files: private review helper/evidence outside Git, continuity guide.
Consumes: saved native scene operation and manual entry.
- [x] Render default and changed-mass scenes via native export; compare PNG hashes and archived roundtrip pixels.
- [x] Inspect motion and actual editor screenshots; repair/retest any defect.
- [x] Run full Android and Node suites, review source, commit and push feature branch.
- [ ] Report clips/screenshots and pending phone/live MCP boundaries. Build an original-signer candidate only after green source verification.
