# Blender cinematic 3D Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Root alone runs shared tests/builds and commits.

**Goal:** Deliver an executable Blender/Rigify animation pipeline and a usable phone/MCP full-scene timeline workflow.

**Architecture:** Share a strict frame-based Action Timeline and verified bundle between Python production and Android replay. Generate genuine portable3D assets; preserve full-scene transforms. Keep optional providers truthfully gated and earlier3.7.3 paths intact.

**Tech Stack:** Python standard library, Blender4.3/Rigify/Cycles/glTF, optional OpenCV/configured model runners, Java/Filament/Media3, existing Worker/MCP SDK.

**Spec:** ../specs/2026-10-12-blender-cinematic-3d.md

## Global Constraints

- Native3.8.0/380; unchanged package/original signer/protocolv3/private owner identity.
- Exact16-joint wxyz local quaternion order; Y-up metres;24/30/60FPS integer clocks.
-1..80 shots,12000 frames,512KiB metadata; imported GLBs16MiB/archive64MiB/expanded256MiB.
- Private originals/order/occurrences preserved; cloud/procedural/estimated/native evidence distinct.
- Full native rendering needs actual skinning/clips; no slideshow or fabricated model readiness.

## Review Focus

- Quaternion antipodes/coordinate conversion and foot anchors after root movement.
- Hit-stop/cut clocks,60FPS export, native preview/export sampling disagreement.
- Changed bundle/program/source bytes, archive traversal/symlinks/zip expansion/foreign ownership.
- Cancellation/thermal waits/lifecycle leaking GPU resources or overwriting accepted generations.
- Procedural/model predictions or integrity hashes falsely presented as exact reconstruction/acceptance.

---

### Task1: Shared timeline/motion
Files `cinematic3d/timeline.py`, `cinematic3d/motion.py`, `tests/cinematic3d/test_timeline.py`, `test_motion.py`.
Interfaces `validate_timeline(dict)->dict`, `compile_timeline(dict)->dict`, `sample_shot(dict,int,int)->dict`; quaternion/IK helpers in motion module.
- [ ] Write fail-first schema/order/80-shot/clock/hit-stop/SLERP/IK fixtures; root observes RED.
- [ ] Implement strict contract, exact sample clocks, damped camera, real shortest-path SLERP and contact locks.
- [ ] Root verifies GREEN and shared golden camera/phase data for Android; review.

### Task2: Blender/Rigify scenes and production output
Files `cinematic3d/blender_scene.py`, optional focused asset/VFX helpers, `tests/cinematic3d/test_blender_contract.py`.
Consumes Task1 timeline/phase/poses. Exposes script CLI `--timeline PATH --output DIR --library-only`;
creates scene.glb, scene.blend, timeline.json, manifest.json, QA/provenance; optional real PNG sequence.
- [ ] Write actual Blender scene/skin/socket/clip/frame/bundle fixtures; root observes RED.
- [ ] Build matte-carbon/white-ceramic mannequins with automatic Rigify/16primary mapping, katana sockets, actual grounded motion/collision/trails/rubble and camera/environment.
- [ ] Bake portable full-scene animation; root renders short CPU sample, checks GLB, pixels/frames and native library size; review.

### Task3: Native timeline/program/bundle
Files new `Cinematic3DTimeline.java`, `Cinematic3DProgramStore.java`, `NativeCinematicBundleStore.java`, focused tests.
Consumes exact spec schema/assets; produces static save/get/list/plan/install/get bundle helpers and guarded project receipts.
- [ ] Fail-first validation/shared clock, revision/CAS/hash/order, malformed/foreign/corrupt/bomb bundle fixtures.
- [ ] Implement atomic immutable staged publication and source-bound independent program store, built-in install and export request.
- [ ] Root focused GREEN; review source/lock/receipts and no reconstruction claim.

### Task4: Native full-scene rendering
Files `NativeCinematicSceneComposer.java`, optional focused renderer helper/tests; root adds render-engine clock role/service cases.
Consumes native program/bundle and shared sampled frame. Exposes compose(Context,ProjectStore,source,output,bundle,program,width,height,Progress).
- [ ] Fail-first exact clip/camera/root/clock/readback/generation guards.
- [ ] Implement genuine full-scene Filament sampling, camera/VFX, rational frame publication, immutable generation, cancellation/cleanup and thermal progress.
- [ ] Root focused GREEN; independent integration review. Real device evidence remains separate.

### Task5: Phone timeline studio
Files `Cinematic3DStudioActivity.java`, faithful preview mode in `FilamentGlbActivity.java`, UI tests; root adds manifest/Main entry.
- [ ] Fail-first editable shot order/duration/hold/camera/VFX save restoration, source-change rollback and preview binding fixtures.
- [ ] Implement built-in/imported bundle selection, human timeline controls, live faithful scene preview, native render and pipeline request export using Tasks3/4.
- [ ] Root verifies focused GREEN; review lifecycle/one-flight background operations.

### Task6: Optional providers/camera and executable CLI
Files `cinematic3d/providers.py`, `camera_solve.py`, `polish.py`, tests; root owns `__main__.py`/artifact orchestration/FFmpeg and CI.
- [ ] Fail-first real child-runner/HTTP history, missing weights,degenerate PnP, timeout/frame-count/cut/hold fixtures.
- [ ] Implement configured real adapters and bounded execution; no arbitrary timeline-executable or model readiness fiction.
- [ ] Root runs camera actual solve and pipeline CLI CPU output; verifies unavailable-provider receipts.

### Task7: MCP, CI and delivery
Root owns Worker schemas/actions, ControlService, NativeRenderEngine, Main/manifest/version, CLI/CI/docs.
- [ ] Fail-first version/owner/schema/exact native queue and program/import/render receipts tests.
- [ ] Wire source-aligned typed tools, native handlers, shared clock role and project/device capability evidence.
- [ ] Run full Python/Android/Worker suites, actual Blender/SDK pipeline and independent review; build unchanged-signer3.8.0APK.
- [ ] Publish reviewed source/artifacts and blueprint coverage with precise external model/device gates; preserve old work.


Implementation status: engine, native stores/composer, human studio, typed MCP, calibrated camera tracks and optional-provider boundaries are implemented. Final source/artifact verification records are in the cinematic system guide and pull request; real-device and original-film acceptance remain separate.
