# VideoStudio OMEGA Phase 4 — Hybrid skeletal / deformable 2D rig handoff

**Owner branch:** `phase/omega-4-hybrid-rig`  
**PR:** [#112](https://github.com/RezoxNemesis/VideoStudio-MCP/pull/112) → `integration/omega-nine-phases-20261020`  
**Authority:** [Nine-phase master contract](NINE_PHASE_MASTER_CONTRACT.md), §2/§3.  
**Classification:** Engine implementation with a read-only native SketchDocument preview adapter; **NOT** a persisted, touch-editable Android production feature, artistically accepted phase or APK release.

## Implemented in owned files

- `OmegaTwoBoneIK.java`: retained the original non-stretching two-bone IK API; added pixel-aspect-correct hierarchical FK, typed `FOOT_PLANT` / `PROP_GRIP` hard locks (fail closed if unreachable) and explicit unreachable `AIM` evidence. Distinct authored `front`, `three_quarter`, `profile` view rigs have stable IDs and are never fabricated from unseen geometry.
- Bounded per-view anatomy: ≤64 bones (ordered parent chain with connected endpoints), ≤128 skinned vector objects, ≤8192 total vertex controls, ≤4 nonduplicate weights/vertex, finite normalized positions, parent-relative local rotations. Local 2D linear-blend skinning uses pixel-space rigid bone transforms, checks triangle winding and rejects clipped/invalid geometry. Preserves unrelated limbs/other roots and recomputes descendants after IK.
- Every sample is pure / deterministic, pinned to frame, original `NativeFrameClock`, source document ID, `revision` **and native canonical SHA-256**; does not mutate source state, file system, Gallery, network or secrets. No automatic motion interpolation (Phase 5).
- `previewNativeChanges(source,rig,frameRequest)` validates the **actual** `SketchDocument`, native bounds, hash/revision, layer ownership/locks, frame exposure and vector rest geometry before returning editable `line`/`stroke`/`polygon`/`eraser` points or Bézier `path` controls. Verifies control topology, retains pressure and output object identity, never commits a file. It **rejects** animated/layer transforms, shape keys and master-linked geometry until a reviewed coordinate-space / atomic master adapter exists.
- `OmegaTwoBoneIKTest.java` covers original IK, unreachable and coincident targets, invalid numerics, pixel aspect, FK articulation, 5 explicit temporal positions (0/25/50/75/99), two hard planted feet, wrist/prop grip, IK conflicts, skin weighting, stable IDs, triangle validation, layer locks, 3 explicitly authored camera views, 100 bound objects and actual native SketchDocument preview/nonmutation/hash pins.

## Accepted use cases and deliberate restrictions

All inputs are authored normalized scene coordinates. Bone lengths and contacts use **source raster pixels**, not distorted normalized distances. Parent bones must precede children; a contact must use a direct two-bone chain. Hard planted soles and prop grips cannot be clamped or silently reported successful if out of reach. `AIM` can clamp for preview but returns `reachable=false` and a measured residual. Duplicate or ancestral competing chains reject. Out-of-canvas posed bones or folded/collapsed triangles reject. Skin blending is not a realistic cloth simulator.

Stable per-angle views are user-authored distinct assets, **not** inferred or generated from a flat front view. Isolated sampling of three supplied views **does not** prove artistic reference fidelity or filmed three-angle continuity on the device.

## Integration-owner-only production change proposal (not applied by Phase 4)

Only the integration owner should modify `SketchAnimationCompiler`, `SketchAnimationUiModel`, `SketchEditOps`, `SketchProjectStore`, native Sketch touch UI, shared schema and renderer paths. Proposed adapter:

1. Add an explicit feature-flagged hybrid-rig preview/editor command, obtain current sketch from `SketchProjectStore`, pin exact SHA-256/revision/frames, check layer/master locks and hard contacts, and invoke this sampler. No second authoritative store.
2. Reconcile authored scene geometry with object/layer keys and master-linked drawing space. Support local-to-world mapping **without** overwriting per-instance transforms, keys, exposures or overrides. Until proven, deny unsupported transforms.
3. Stage all edits; validate; commit using existing `SketchEditOps` and `SketchProjectStore` atomic revision transactions and undo/redo. Include lock, rollback and native restart/replay verification. Preview is never a committed edit.
4. Add touch handles for joints, contact pins, prop grip and skin weight paint. Preserve owner privacy, feature flag OFF, `NATIVE_RECOVERY_HELD` and original signing controls. Coordinate shared adapters with Phase 1/2/3/5 owners.
5. Capture **real** native animated frames at 0/25/50/75/100% across front/three-quarter/profile with hand/object interaction, foot plant and editable deformation; inspect visual acting and silhouettes, native MP4 continuity and actual owner Android playback. Do not browse Gallery.

## Reproducible gates

- CI command: `cd android && gradle :app:compileDebugJavaWithJavac :app:testDebugUnitTest --stacktrace` with Java 17/Android SDK 36. Focus: `gradle :app:testDebugUnitTest --tests 'com.rezoxnemesis.videostudio.OmegaTwoBoneIKTest'`. The integration branch's `.github/workflows/omega-nine-phase-ci.yml` includes ownership gate.
- Existing `videostudio.sketch.v1` schema and budgets remain authoritative. This phase introduces **no persisted schema**, key/signature change, APK packaging or version bump.
- **Phase completion blocked** until approved production adapter, touch/undo/replay, real-device p50/p95 and visual tests, genuine three-angle anatomy/prop/foot footage and user acceptance. A passing engine JUnit is not a release or professional-animation quality pass.
