# Reference Edit Foundation Implementation Plan

**Goal:** Continue the interrupted shared-edit work with settings-preserving timeline application and truthful verification.

**Architecture:** Reuse the browser's clip normalizer for every timeline command and save before adopting replacement state. Add a native revision-bound plan operation that uses the existing precision validator, project transaction, and renderer.

**Spec:** `docs/superpowers/specs/2026-10-17-reference-edit-foundation.md`

**Constraints:** Preserve native MCP v3, application ID and original signing identity. No private reference media in Git. No deployment or release. Implement directly as explicitly requested by the owner.

## Review focus

- Settings and 0.25/4× speeds survive autonomous apply, read-back, and later edits.
- Invalid final entries, duplicate IDs, and oversized plans leave the original timeline intact.
- Nonfinite numbers cannot enter saved JSON.
- Image holds and trimmed video durations have distinct source limits.
- Stale native revisions and failed browser persistence produce honest failures.

## Tasks

- [x] Add behavioral regressions against the actual browser command function; run and confirm the existing state-loss failures.
- [x] Route browser plan, speed, and effect operations through validated normalization and adopt replacement timelines only after persistence succeeds.
- [x] Add native plan tests for revision conflict, deep effect ownership, invalid batches, image holds, trim limits, IDs, and renderer consumption; confirm failure before implementation.
- [x] Implement `StudioEditPlan.apply(ProjectStore, JSONObject)` and route `ControlService.applyEditPlan` through it. Reuse precision validation.
- [x] Run focused browser/Node and native checks, then the existing suites. Re-run affected checks after review fixes.
- [x] Exercise actual Chromium editing and export, inspect screenshots and encoded output, and save evidence plus a continuation record.

The complete reference review and private artifacts live at `/workspace/engineering-review/reference-rebuild-20261017/`; they are not shipped with the app.
