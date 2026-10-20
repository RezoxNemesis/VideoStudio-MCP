# VideoStudio OMEGA — Phase 3 precision sketch construction

**Contract:** NINE_PHASE_MASTER_CONTRACT.md (VS-OMEGA-2026-10-20-v1)  
**Owned branch:** phase/omega-3-sketch-construction  
**Target:** integration/omega-nine-phases-20261020  
**Draft PR:** https://github.com/RezoxNemesis/VideoStudio-MCP/pull/111  
**Status:** extended engine prototype, NOT native-integrated, visually accepted, or released.

## Scoped, implemented engineering

Phase 3 owns only OmegaBezierFitter.java, OmegaBezierFitterTest.java and this phase-specific handoff. It must not touch shared Android production surfaces or other agents' files.

- Pure package-local Java 17 contour engine, no Android or network/phone/owner-data effects. Legacy fit(List<Point>,closed,tension) remains compatible.
- Finite normalized immutable Point(x,y,pressure,corner), Anchor(in/out), Contour and Cubic structures. Open/closed contours; independently adjustable corner handles; mirrored smooth editing; immutable anchor translation; exact shape-preserving de Casteljau node subdivision; stable smooth C1 joins.
- Bounded fitStroke converts ordered authored stylus samples (up to 4096) to editable contours (up to 512 anchors), with pixel-space tolerance .25–12px, corner and pressure retention, simplification and iterative deviation-based refinement. If the budget or tolerance cannot be met, it fails closed rather than approximating artwork using primitive boxes.
- Native-schema export nativeCommands yields normalized M/C/Z path command data compatible with SketchDocument.validateCommands, SketchFrameRenderer.path and existing Sketch shape key operations. pressureOutline yields an editable round-capped, pressure-width **filled path** with M/L/Z commands. It is not a flattened raster image.
- Validate 256–2048 even canvas sizes, 4096 path command maximum, normalized finite coordinates/pressure, stroke length and handle geometry. Reject invalid shapes and edge clipping without silent clamping or modifying caller state.
- 15 regression tests: curve continuity and hand-shaped 19-anchor fixture, closed seam, corner and edge behavior, immutable edits, handle modes, geometry-preserving subdivisions, sampled pixel deviations, closed ellipse, authored pressure, native command immutability, pressure-width outlines, input/budget failures and deterministic fuzz.

## Verification evidence and explicit non-evidence

- Offline local Java source test: OpenJDK javac --release 17 with a minimal standalone JUnit-style reflection harness, 15/15 test methods passed. This is NOT a native Android Robolectric or Gradle test run.
- GitHub CI job: https://github.com/RezoxNemesis/VideoStudio-MCP/actions/workflows/omega-nine-phase-ci.yml runs the real Gradle compile and unit suite on Java17/SDK36. Cite each actual run result in PR #111, not a green local source check alone.
- An isolated, synthetic 19-anchor hand-shaped fixture was rendered with this engine's commands to compare polyline versus smooth curve. It is NOT human-anatomy certification, photographic reference matching, user image verification, a native Sketch Studio screenshot or phone demonstration.
- Feature flag/wiring: **OFF / no existing native touch controls invoke this new engine**. No live-device telemetry or latency sample, APK, 4.8.7 signer compatibility evidence, source-bound reference proof, end-to-end Android drawing or motion QA.

## Integration-owner-only proposal: native Sketch Studio adapter

The master contract protects SketchDocument, SketchEditOps, SketchProjectStore, SketchCanvasView, SketchStudioActivity and render entrypoints, and the CI ownership gate rejects unapproved shared-file edits from this branch. Native integration requires a separately authorized integration-owner change set. Do NOT attempt it on the Phase 3 branch.

1. Add optional Precision Contour Pen to the existing SketchCanvasView / SketchStudioActivity tool controls, default OFF. Collect ordered normalized stylus x/y/pressure input under existing frame clock, layer transform and pointer cancellation rules. Show a preview comparing source points and editable smooth contour; preserve the old pen as a reversible fallback.
2. Invoke OmegaBezierFitter.fitStroke with actual document width/height and user-selected bounded tolerance/tension. Convert Command.kind and Command.values() into the already-approved JSONArray M=[M,x,y], C=[C,c1x,c1y,c2x,c2y,x,y], L=[L,x,y], Z=[Z]. Reject over-budget paths. Commit as a revision-checked SketchEditOps add_object with completeObject metadata: kind path, frame, exposure, color, width, fill, id, transform, keys. This preserves v1 schema and existing SQLite undo/redo/operation IDs. Use fill=true with pressureOutline when the user chooses vector ink silhouette, not an overdrawn, second stroked line.
3. Add explicit anchor, in/out handle and split controls to existing zoom/node tool using immutable moveAnchor, moveHandle and split operations. Integrate shape keys only via the existing shape animation operations, respect locked layers and linked-master overrides. Apply symmetry only after every reflected output is inside canvas and remains a valid path; avoid silently changing master-owned appearance.
4. Hand off only visible, user-authored landmark positions from Phase 2's exact source hash / source orientation / dimension-bound reference fixture. Overlay guides; do not paste a reference image as a finished sketch or infer unobserved camera angles.
5. Before enabling flag, run schema v1 round-trip / revision-conflict / undo-redo / locked-layer rollback / background-permission / old curve pen regression suite; vet owner-authorized native MCP tool exposure separately. Do not bypass native recovery or signing.

The adapter is a proposal, not an implemented change, because a phase agent has no authority over these shared production files.

## Acceptance still OPEN

- Integrate user-facing precision drawing and corrected reference guides with an integration-owner-reviewed adapter without breaking old tools or serialization.
- Human-visible before/after on a reference-grounded hand, face/head and full-body contour at three views, including fingers and expression, with separately editable layers; blinded human review. Synthetic fixture does not satisfy this.
- Native project and review evidence across five fixed temporal positions (0/25/50/75/100%), correct undo/redo and locked-layer atomicity, linked-master propagation behavior at 100 objects, actual p50/p95 on device. Measure instead of guessing.
- Full Android Gradle CI success, safe Sketch↔Editor render tests, approved feature rollout, actual owner's installed app 4.8.7 source ancestry/revision/signing reconciliation, then separately approved APK. No main/integration direct edits, no Gallery enumeration, no recovery hold bypass, no signer changes.

**Gate: engine prototype only, not full Phase 3 completion.**
