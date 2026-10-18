# Project OMEGA M3.1 — Bounded Full-Body Impact Acting

**Status:** Experimental source change for review; not an Android release, physics simulation, autonomous scene director, or owner-device test.

## Native scene upgrade

After the previously verified M3 wrist-contact correction, `SketchAnimationImpactDynamics.shape` modifies only five **existing** pose keys for the two wide-shot Rooftop Standoff rigs. It introduces a pre-impact brace, a short post-impact knee/hip compression through native IK, delayed head/scarf response, and a settling phase. The impact pose at frame 16, all camera keys, all original vector shapes and both actors' horizontal world positions remain unchanged. The standard editable `videostudio.sketch-animation.v1` program is returned, so the existing Android `SketchAnimationCompiler` and Canvas renderer remain the sole rendering authority.

Corrections at source frames 5, 11, 20, 25, and 30 are intentionally small: added root Y shifts of at most 0.012 normalized canvas units; arm/torso/scarf changes of up to 8 degrees. The old 8-pixel source-canvas contact cue remains enabled. No new proprietary animation system or cloud model is required.

## Independent QA

`src/omega/impact-dynamics.js` checks the **original native solver's actual 100-frame rig trajectory**, not prose labels:

- wrist contact is no worse than M3 at the original impact key;
- max wrist motion in frames 11–20 is at most 55 source pixels/frame, and at least 5 pixels/frame less than the pre-acting M3 version;
- both actors retain four hard planted-sole diagnostic entries on each of frames 5–30, with <=0.02 source-pixel error;
- the post-impact root-Y response is 5–20 source pixels, without horizontal teleportation;
- additional scarf joint rotation is measured as a secondary movement indicator, but does **not** prove simulated fabric;
- all prior M1 through M3 hashes/receipts remain required; reported output is `MANUAL_REVIEW_REQUIRED`.

The native class also samples and compares the source solver **before and after** changing any pose program. If any constraint fails, it throws and does not modify the caller's source/program. Integration with the existing film encoder means a failed QA preserves the rendered PNG sequence for investigation instead of publishing a success receipt.

## Limits

This is controlled procedural **pose choreography and IK**, not full rigid-body human dynamics. The joint-center alignment from M3 cannot prove weapon-blade collision, silhouettes can still interpenetrate, and anatomy, character proportions, clothing folds, eye line, expression, dialogue and audio sync still need artistic production. The owner's Android Realme installation and playback remain untested. No Gallery enumeration, app key changes, keystore rotation, Cloudflare production deployment or paid inference APIs.

## How to verify on GitHub cloud

```bash
node --test scripts/omega-cinemabench-test.mjs scripts/omega-native-proof-test.mjs scripts/omega-motion-dna-test.mjs scripts/omega-skeleton-test.mjs scripts/omega-physics-brain-test.mjs scripts/omega-impact-dynamics-test.mjs
```

The narrowly targeted Android/Robolectric `omega.yml` workflow renders the 100-frame duel and 240-frame original story (to prevent regressions), encodes their MP4s and checks M0–M3.1 evidence. This GitHub cloud test requires no laptop, but does **not** install an APK on the owner's phone. Review the actual MP4 and compare original M3 before making any merge decision.