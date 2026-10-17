# Project OMEGA — Architecture and milestone plan

OMEGA extends, rather than replaces, VideoStudio's Android-first filmmaking architecture. Status: design in progress; first executable slice is CinemaBench frame-evidence QA. Not a shipped Android capability.

## Responsibilities and trust boundaries

- **Android authority:** Current VideoStudio owns editable source sketches, rigs, Media3 previews, app-private SQLite, project revision, protected media imports and the actual native export. No Gallery enumeration.
- **Cloudflare MCP v3 control relay:** Authenticate the device and lease bounded, durable commands; it must never pretend that waiting_native means a completed render. The relay is not a GPU/video transcoder.
- **Isolated cloud workers (GitHub Actions initially):** Deterministic tests, user-approved reference-motion measurements, optional Blender/FFmpeg animation proofs, and authorized diagnostics. Do not upload private media without explicit selection.
- **OMEGA agent coordinator:** Versioned task schemas, expected project revision, job idempotency, compute budgets, receipt/evidence checks, STOP/cancel support, and separate reviewable feature branches.
- **Review/acceptance:** Correct frame hashes or a green APK build do not establish convincing animation. Require readable MP4, evidence-based motion tests, manual artistic review and later physical-owner-phone playback checks.

## Subsystems

1. **Motion DNA:** Opt-in analysis of user-owned/licensed reference scenes; region-level motion, shot boundaries, pose landmarks, camera-versus-character movement. Reconstruct *original* animation parameters, not proprietary assets.
2. **PhysicsBrain:** Improve the existing native rig/compiler: IK and planted feet, body weight shifts, hand/weapon contact evidence, secondary movement and continuity.
3. **Ghost Director:** Story beats -> shot graph -> original editable character pose keys, camera views, mouth cels, audio cues -> native renderer.
4. **CinemaBench:** Ordered native PNG/MP4 integrity, SHA-256 verification, exact repeated frame runs, contact constraints; later perceptual motion, character continuity, sound sync and visual acceptance.
5. **APK Surgeon:** Reproduce code defects, capture sanitized logs, run native tests and optionally analyze authorized APKs with JADX/Ghidra/REA in a separate least-privilege sandbox.

## Implementation milestones and acceptance

| Stage | Deliverable | Evidence |
| --- | --- | --- |
| M0 | Independent CinemaBench JS audit + JSON report | Reject corrupt/out-of-order or nearly static frame proofs; never certify visual quality from hashes alone |
| M1 | Native film-proof adapter | QA JSON from the 100/240-frame Android Canvas proof before PNG cleanup, with source revision and MP4 probe |
| M2 | Motion DNA optical-flow and pose baseline | Differentiate camera pan, articulated action and frozen shot on labeled fixtures |
| M3 | PhysicsBrain contact and grounded motion | Quantified foot slip and multi-actor joint/contact errors in native frames |
| M4 | Ghost Director three-shot animation | Editable native characters and real MP4 with continuity and dialogue/audio review |
| M5 | APK Surgeon triage workflow | Failing reproduction, isolated fix, regression and signer continuity proof |
| M6 | Controlled owner-device release | Signed staged APK with original package, certificate and MCP identity plus phone playback acceptance |

## Initial stack

Node.js 22 ESM + node:test for zero-dependency audits; existing Android Java/Canvas/Media3/SQLite for native authoring; Cloudflare Worker for authenticated command queue; FFmpeg/FFprobe for exact native frame encoding verification; Blender Python in a bounded cloud container if 3D is required; OpenCV/pose-landmark tools for opt-in M2; optional OpenTimelineIO editorial adapters.

Do not merge stacked draft animation PRs, rotate the owner key or signer, deploy the private relay, or auto-install an APK as part of M0. High-quality animation is an iterative benchmarked engineering task, not an immediate result from APK/source inspection.
