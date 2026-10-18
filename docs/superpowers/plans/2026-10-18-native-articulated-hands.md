# Native articulated hands implementation plan

> Execute inline using superpowers:executing-plans, with disjoint manual/MCP sidecars.

**Goal:** Meet the explicit visual and motion targets through native product code.
**Architecture:** Native joint chains, surface projection and occlusion compile to editable cels; shared operation powers manual preview/save and MCP.
**Tech Stack:** Existing Java/Android Canvas, JSON, Robolectric and Node/Zod.
**Spec:** docs/superpowers/specs/2026-10-18-native-articulated-hands-design.md

## Global constraints

Preserve source/history/projects/signing; no reference pixels, Gallery, external
artwork renderer, deployment, merge or stable publication. Preserve exact FPS,
resolution, frameCount and existing4MiB/4096object/131072point/4096key budgets.
New native minimum4.7.9/code482. Six authored camera views of projected2D output.

## Review focus

- Joint continuity under extreme curls/opposition and left/right views.
- Hidden nails/creases on front vs rear and curled-palm occlusion.
- Existing animated destination layers must reject out-of-bounds paths atomically.
- Custom numeric inputs, lifecycle cancellation and durable manual preview/save parity.
- Worst-case240frame geometry must fit or reject without truncation/resampling.

## Tasks

### 1: Joint anatomy and native editable output (local)
- [ ] Write SketchHandAnimationTest through real SketchEditOps dispatch; unsupported operation must fail RED.
- [ ] Implement SketchHandAnimation validation, key interpolation/preset and SketchHandGeometry joint/skin projection. Assert invariant bone lengths, digit independence and safe clearance at every30/60FPS frame.
- [ ] Add atomic rejection/bounds/portrait/budget regressions and run focused native tests.
- [ ] Capture first native six-view generation; inspect screenshots, fix reproduced shortcomings and retain/retest each generation.

### 2: Manual controls and MCP parity (disjoint sidecars)
- [ ] Add strict Node schema/version gate/handAnimation metadata and tests using same settings/channels.
- [ ] Add SketchHandDialog and kind8 integration using shared constructor and existing protected submit path. Test settings, editable poses, native preview/save, cancel/invalid inputs.
- [ ] Integrate and verify both paths; inspect actual native editor/preview screenshots.

### 3: Target assessment and signed candidate
- [ ] Capture full six-view180frame30FPS tests and60FPS/control variations with archive/hash evidence; inspect contact/in-between frames and review motion. Never promote a weak generation because tests pass.
- [ ] Review whole diff independently; repair meaningful findings with regressions.
- [ ] Run Node suite, worker dry-run and affected native tests, commit/push verified code, run one full Android CI with original signing continuity.
- [ ] Verify candidate package/version/source/hash/signer, record source evidence/targets/manualMCP recipe and any remaining gap. Deliver actual screenshots, review clips and candidate APK with honest limits.
