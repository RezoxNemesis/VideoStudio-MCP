# Native Preview and Reference Layers Implementation Plan

**Goal:** Repair blank playback and add persistent source-assisted named parts.
**Architecture:** Keep existing native render/edit/history/asset boundaries.
**Tech Stack:** Android Canvas/Region, Media3, Filament, SQLite, strict Node MCP.
**Spec:** docs/superpowers/specs/2026-10-19-native-reference-layers-design.md

User has already chosen autonomous execution; no repeated approval gates.

## Ownership and interfaces

- Parent: SketchCanvasView/SketchStudioActivity playback, new SketchSourceMask
  validation and SketchReferenceLayers core, SketchDocument/SketchEditOps/store
  integration and native source/mask/animation studies.
- Editor worker: NativeRenderEngine, LiveEditPlayer, MainActivity and focused
  duration/admission/edited-preview regression tests.
-3D worker: FilamentGlbActivity, focused readiness/lifecycle helper/tests.
- Manual worker: new SketchReferenceLayersDialog and mask editor/tests; parent
  adds the SketchStudioActivity menu hook after interface freeze.
- Interface worker: strict action/sourceMask schemas, capabilities, aliases,
  dispatch/background/version integration and contract tests.

Native core exposes `SketchReferenceLayers.execute(Context,SketchProjectStore,
JSONObject,String fingerprint)` returning the ordinary mutation receipt plus
deterministic source-assisted/part-count metadata. Facade owns refresh signals.
Manual entry exposes `SketchReferenceLayersDialog.show(Activity,JSONObject,int,
Consumer<JSONObject>)` and lifecycle close, using exact action arguments in spec.

## Checks and tasks

- [x] Preserve4.8.4 source/resources/APK and inspect current instructions/state.
- [x] Reproduce canvas fallback/starvation and Media3 video-duration failures.
- [ ] Repair preview flows; verify current-source publication and source clocks.
- [ ] Write failing mask extraction/provenance/history/policy tests; implement
  strict geometry, sequential native pixel extraction and transactional layers.
- [ ] Add usable manual mask controls and shared strict MCP/version4.8.5 schema.
- [ ] Verify component/archive dependency and provenance retention, bad masks,
  stale sources, unsupported transforms, failed import and interrupted work.
- [ ] Capture short native2D source/before/after/keyframes; inspect visual quality.
- [ ] Probe Android runtime availability for actual3D pixels; retain explicit
  BLOCKED result if no supported runtime. Fix measured readiness failure paths.
- [ ] Run affected official Android tests, full Node and worker dry run once the
  final interfaces freeze; one local Gradle process at a time.
- [ ] Review changes, commit coherent fixes/features, build via original-signer
  CI and verify APK/source/assets. Preserve failed runs and all generations.

Review focus: stale worker publications, empty/overlapping/holey masks, invisible
source alpha, source/caller CAS and receipt collisions, missing/corrupted owned
dependencies, viewport lifecycle and truthful decode/GPU readiness. These receive
native regression tests in their owning task; synthetic transport/state tests
cannot substitute for actual artwork/playback or device rendering acceptance.
