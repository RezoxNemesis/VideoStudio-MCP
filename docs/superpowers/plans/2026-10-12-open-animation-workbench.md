# Open Animation Workbench Implementation Plan

> For agentic workers: use independent subagents for the owned subsystems; root integrates shared entrypoints and runs the final builds.

**Goal:** Deliver real native precision editing/sketch animation plus open-source generation and verified artifact storage, accessible equally by touch and MCP.

**Architecture:** Add focused operation facades around existing ProjectStore, native rendering and Drive workspace. Add an inert self-hosted job protocol and keep commercial generation APIs absent.

**Tech stack:** Java/Android Canvas, Media3, Filament, Python/Blender/OpenCV/Diffusers, Worker/Zod/MCP.

**Spec:** `docs/superpowers/specs/2026-10-12-open-animation-workbench.md`

## Global constraints

- Start from `68e3b50`; retain ACK recovery and all original project/media/signing state.
- Application v3 identity stays unchanged; native feature floor follows the new APK version.
- Agent file ownership is exclusive. Root owns ControlService, AppProtocol, manifest, version, publication and final builds.
- MainActivity is owned by the precision-editor implementer; other agents send it their activity entrypoint bindings.
- Optional runtimes report actual readiness; no commercial API/subscription integration.
- No private user media, credentials, model weights, generated large artifacts or signing key in Git.

## Review focus

1. Process death during save/transfer: old complete revision remains usable and retry does not duplicate output.
2. Foreign/stale asset or project revision: reject before disk/network/render effects.
3. Huge strokes, zips, models or frame buffers: bounded streaming/capture and actionable refusal.
4. Authored timing, holds and edited effects: preview/export share semantics and exact clocks.
5. Missing compute/model/Drive permission: explicit unavailable result, never fake generated media.

## Tasks

- [ ] Sketch domain/store/Canvas renderer/native Activity: add meaningful geometry, CAS, undo, cel/exposure and real pixel tests; expose static operation facade for service integration and frame rasterization for separate native export.
- [ ] Precision editor: shared keyframe/effect operations, actual edited playback, numeric controls and revision-bound atomic batches; preserve MainActivity behavior and connect new studios.
- [ ] Motion/models: mirrored optional actor/style contracts, new action sampling and selective packs, actual evaluated conditioning passes and explicit mapping provenance; tests reject changed baked bindings and invalid geometry.
- [ ] Open runner: inert `.vsjob`/`.vsresult`, authenticated bounded self-hosted jobs, durable idempotency/checkpoints/cancel, real Blender/CogVideoX/ComfyUI/interpolation adapters; prove unavailable states and actual child execution separately.
- [ ] Native cloud pipeline/Drive: one operation facade and Activity, verified streaming artifact exchange, generation-specific archive paths and result imports; no removal of originals or assumptions about OAuth grants.
- [ ] Root MCP/native integration: strict typed tools, minimum-version gates, shared native facades, durable heavy jobs/recovery, resource checks and captured encoder truth.
- [ ] Verification/publication: read all diffs; run focused then full required checks; verify CPU Blender/neural outputs where available, same-signer CI APK and current-source proofs; publish branch/PR and document unavailable live/device prerequisites.
