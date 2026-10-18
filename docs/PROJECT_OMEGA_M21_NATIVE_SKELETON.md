# OMEGA M2.1 — Native rig intelligence (diagnostics, not automatic animation)

M2.1 adds read-only source-canvas joint-trajectory diagnostics for VideoStudio's **authored** Rooftop Standoff duel. This does not estimate skeletons from video images, create smoother poses, improve hands automatically, or certify film-quality acting. The synthetic 10-second story has no matching compiled multi-rig `sampleFrames` source, so it is intentionally marked **not supported** rather than assigned invented skeleton positions.

## Native source of truth

`SketchDuelAnimationExampleTest.fullCinematicActionFilmCanBeRenderedToExactly100NativePngsForCi` already compiles the original editable artwork and 100 source-frame animation. M2.1 writes `native-rig-tracks.json` from the exact `SketchAnimationCompiler.sampleFrames(source,program,0,99)` solver, with the ordered 100 frame snapshots, all authored joint positions, parent IDs and hard-contact error receipts. It includes the exact original sketch/program SHA-256 hashes and is itself hashed in `native-duel-proof.json`.

The CI-only extractor runs in Android Robolectric, **not a real phone**; the original Android app/project identifiers, Keystore signer, stable private MCP credential, media library and release branch remain unchanged. No Gallery permissions, remote private-device commands, neural model subscriptions or media scanning are needed.

## What the independent inspector actually measures

- Validates **every** ordered full native frame and authored rig hierarchy, requiring known rigs and joints, one root per rig, valid parents and finite world-space coordinates.
- Uses a **reviewed visibility plan** for three shots (wide contact, foreground POV, recovery) so unexposed/out-of-shot rigs cannot inflate acting-motion metrics. Never treats a hard cut as a joint velocity.
- Measures pixel travel of rig roots, gross world-space joints and **root-relative joints** separately, plus per-frame maximum displacement, acceleration, and authored parent-child bone-length drift.
- Measures exact hard foot-contact slip against authored `ik` targets, **only** where the source has a real planted-foot constraint. No IK = not measured, not zero confirmed sliding.
- Checks two-actor wrist-to-wrist **joint proximity** against the authored max distance; this does not prove blade or glove silhouette contact.
- Adds warnings for suspicious large displacement, bone-length variation, slip, loose action contact and unchanging root-relative acting. These measurements are *advisory* and never automatically certify a final film.

Source pixels are calculated with the original 1280×720 space **before camera transforms**. The three-camera-shot plan is human-authored from the known prepared scene, not inferred. Continuous curves of head/hand movement remain ungraded: good anatomy and convincing acting still require an animator.

## Run on an authorized CI-owned native duel proof

After the existing M1 and M2 audits and **before the script clears the native PNG folder**:

```bash
node scripts/omega-skeleton.mjs --root android/app/build/sketch-duel-film-proof
```

The report is `omega-skeleton-dna.json`. Valid structural and provenance checks produce `status=MEASURED`, `qualityDecision=MANUAL_REVIEW_REQUIRED`; malformed, mismatched or missing rig/provenance evidence writes `status=BLOCKED` with a failure reason and makes the film proof CI job fail. Source 100-frame joint snapshots are kept as `native-rig-tracks.json` in the bounded 7-day CI artifact alongside actual M1/M2 reports and MP4.

The earlier film SHA-256 and `omega-native-report.json` (M1) must match `omega-motion-dna.json` (M2), and the original source and animation program must match their M1 hashes. M2.1 checks the original Android test's SHA-256 of the rig track and recomputes the exported trace SHA-256. This is reproducible CI integrity, not adversarial cryptographic attestation by a physical device.

## Acceptance and remaining work

`node --test scripts/omega-*-test.mjs` exercises synthetic rooted/independent motion, camera/root movement, incomplete frames, cycles, missing/altered evidence, planted slips, false contact receipts and shot-boundary exclusion. The full native CI job additionally compiles a real 100-frame trace and measures its original artwork and video. **Green Node tests alone are insufficient**: require an actual native CI job and inspect the preserved report and frame contact sheet.

M3 will need improvements to native acting/physics and demonstrated output changes, not just more metrics: physically credible anticipation, momentum, footwork, recoveries, actual hand/weapon intersection, consistent posed anatomy, aural performance and reproducible owner-phone playback. M2.1 is a diagnostic foundation and does not implement those changes.
