# VideoStudio full animation upgrade — Phase 0 engineering audit
**Status:** Evidence-based baseline and first incremental implementation. **Not** a finished film or installed APK.
**Base:** release/v486-source-base-20261019 (installed reported version 4.8.6).
**Scope:** nine phases 0–8 with separate tests and visually reviewed production checkpoints.

## Why the previous results failed
Across the user-provided VideoStudio animation clips and the previously reviewed 16 s horror scene, repeated visual failures include:
- A 1280×720/30 FPS film can have a low percentage of meaningful moving pixels despite nominal frame rate.
- Large regular shapes and flat fill gradients read as boxes instead of human/proper scenery form.
- Hand and door contact, hinge pivots, center-of-mass motion and detached shadow anatomy did not convey physical weight.
- Script-generated narration existed in the Media Bin but the displayed MP4 lacked audible narration. Its separate editor and sketch timing made it invisible to the normal authoring workflow.
- Native motion audits measured pixel changes and could pass despite poor character acting or camera-only movement.
- The recovery guard retained interrupted render state across upgrades. It protected data, but ordinary audiovisual publishing remained blocked with NATIVE_RECOVERY_HELD.

These are **observations**, not a claim of measured percentage improvement.

## Current 4.8.6 inventory (already built)
- SketchDocument: 4 MiB bounded serialized document, 32 layers, 4096 objects, 131072 points, 4096 keys, 1200 frames, 256–2048 even pixel dimensions.
- Vector paths with cubic/quadratic Beziers, controllable line width, gradient and texture-style paint tools.
- SketchShapeKeys: topology-preserving curved shape keyframes and easing.
- SketchPaletteStyles: linked palette colors, but not geometry linking.
- SketchComponentLibrary: immutable SQLite component snapshots / copies; deliberate **non-propagating** source changes.
- SketchHumanAnimation and SketchHandAnimation: retained articulated procedural body/finger state; published quality still requires visual acceptance.
- SketchReferenceLayers: source-bound pixel cutouts and local edge guidance, not semantic landmark recognition, painterly reconstruction or new hidden views.
- SketchQualityAudit: sampled pixel changes, region thresholds; explicitly no anatomy/acting certification.
- NativeSpeechEngine: Android local/offline TTS with generated WAV in project Media Bin. StudioLayer tracks can play sound in the Editor, but Sketch Studio does not yet have a unified synced narration lane.
- SketchNativeExport/SketchFrameStreamEncoder: local persistent captures/MediaCodec frame stream; NativeStartupGuard safely blocks old interrupted heavy recovery until an explicit owner retry.

## Dependency map / delivery gates
| Phase | Build next | Gate (do not progress merely on successful command) |
|---|---|---|
| 0 | Failure audit; baseline reproducibility | Capture evidence and gaps; publish this audit |
| 1 | Master vector + linked instances, atomic propagation and override/undo | Test edit-once update-many; frame motion keys survive; no stale master; phone demo pending |
| 2 | Reference-to-guides with user-verified landmarks | Compare against source target on full-size drawing; no false semantic claims |
| 3 | Contour-first curve/node tools; reusable anatomical design lines | Demonstrate user-preferred character sketch, hands, and material vs primitive baseline |
| 4 | Hybrid IK rig + deformable 2D overlays | Show joint lengths, floor contacts, prop grips in 3 camera angles |
| 5 | Spacing, anticipation, recovery, weight-driven motion | Local-subject pixel metrics **and** human visual acceptance |
| 6 | Texture, materials, layered atmosphere, lighting/shadows | Distinct depth planes and actual surface-detail inspection |
| 7 | Visible narration/audio in Sketch + Editor, WAV align, SFX lanes | Scrub shows waveform, audible synced video, not a silent MP4 |
| 8 | Cache, selective re-render, safe recoverable jobs, QA pipeline | Reproducible p50/p95 timing and safe crash/restart without lost originals |

## Phase 1, first functional slice: vector masters (this PR)
File: SketchMasterAssets.java. Persistent document field masterAssets; normal path masterLink metadata; no new external storage database or Gallery.
- master_create converts one original Bezier path into a master-backed instance.
- master_instance creates an additional linked path at another frame/layer with **its own transform, keyframes and exposure**.
- sketch_master_preview is read-only, returns all affected object IDs, local override count and locked-layer blockers at the exact source revision.
- master_patch applies a new vector path/color/material appearance to *all linked instances within the same sketch* transactionally. One native edit receipt and one Undo step.
- master_override / master_clear_override preserve per-instance color/width/fill/gradient differences.
- master_detach converts a linked object to independent ordinary vector art.
- Locked linked layers reject whole transactions; direct linked-object appearance edits are rejected with a useful message rather than silently diverging.
- Component-library snapshots deliberately detach links, preserving the existing immutable-copy contract.
- Max 64 master assets within existing 4 MiB and object/point budget. All writes revision- and operation-ID-pinned and eligible for Undo.

## Explicit missing features in this slice
- Cross-sketch/project/shot propagation requires versioned shared store, dependency index and safe multi-sketch transactions in later Phase 1 work.
- Complex character rigs, bitmap/paint strokes, vector shape-key programme masters, and audio links are **not** silently supported.
- A linked instance does not manufacture an accurate drawing: actual artistic guide, contour, anatomy and texture upgrades are future gates.
- This branch is **not installed on the Android owner device**. Only GitHub Actions build/testing and on-device testing after a compatible upgrade can verify it.
- NATIVE_RECOVERY_HELD must not be bypassed by any feature or automation.

## Quality and performance benchmarks — methods, not invented numbers
1. First benchmark the base phone/repository on a 100-instance multi-frame vector scene; record p50/p95 update latency, document bytes, frame-cache invalidations and rerender latency.
2. On the same 100-instance scene, perform one master_patch versus 100 manually repeated updates; count source operations and actual time, and compare all native pixels at 0/25/50/75/100%.
3. Cross-check untouched transform keys, exposure, local overrides, original source hashes and Undo history.
4. Use a character's arm/hand/lantern shape in three practical camera shots; reject ugly anatomy, flat texture and visible discontinuities regardless of fast timings.
5. Inspect complete final MP4 audio streams and audible playback when safe recovery permits, not just successful audio synthesis receipts.

## Security / release discipline
- Never enumerate or browse Gallery.
- No change to signing keys; retain pinned continuity certificate.
- Draft PR; user review before merging, APK installation, automatic feature rollout or final film.
- Avoid destructive operations on existing animation/projects and keep baseline test clips as read-only benchmark fixtures.
- Any claim that artwork is accurate or a film is finished requires real produced native frames and review.
