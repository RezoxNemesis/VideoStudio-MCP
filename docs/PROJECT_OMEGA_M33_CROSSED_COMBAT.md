# Project OMEGA M3.3 — Crossed Weapon Staging, Real Drawn Grip Pivot

**Scope:** The original VideoStudio 100-frame Rooftop Standoff exercise. No user media, Gallery scanning, private MCP worker, device build signing, or native runtime deployment. M3.3 is not a general AI choreographer or complete animation engine.

## Why this exists

M3.2 correctly reduced tip-to-tip distance to ~0.079 original-canvas pixels. However, the batons met tip-first and were only ~10° apart; more importantly, rotating artwork about each skeleton wrist pulled the painted shaft away from the original glove. That produced an inverted V with a weak impact silhouette.

M3.3 uses the true **original drawn weapon grip point** as the weapon's rotation pivot, retaining the same native wrist skeleton, source artwork, foot IK and all other movie timings. It aims both batons across a **mid-shaft contact point** around 54% of baton length from each grip. The resulting editable centerlines form an approximately 46° X-shaped intersection; they do *not* need the two tips to touch. The impact ray/flash is moved from the old tip location to the interior intersection. This preserves the M3.2 tip-to-tip proof as a historical stage, not a present-tense claim about the updated drawing.

## Implementation and evidence contract

- `SketchAnimationCombatStaging.cross` accepts only the reviewed 1280×720 / 100-frame native M3.2 editable source/rig combination. It rejects unexpected source geometry, missing foot-contact solver evidence, overwritten/custom weapon keys, and unsafe rotations. Its result is **a new copied editable source** and a non-authoritative report; neither original nor user projects mutate.
- The existing 8 vector weapon objects remain bound to original authored wrist joints. The pivot of each weapon group is moved from wrist to the actual grip point in the original core path; no new flat image layer is inserted. The 11 authored weapon keyframe times and original exposure spans are retained.
- `SketchAnimationWeaponContact.trace` and `src/omega/weapon-geometry.js` share the same correct `jointTransform` pivot contract in both historical M3.2 wrist mode and new M3.3 grip mode.
- A genuine interior line-segment intersection must occur on both baton cores at impact frame 16 with **≥35° acute crossing angle**, normalized intersection fraction **0.35–0.75** on each core, ≥40px grip separation, ≥25px distal-tip separation, and source-visible boundaries. All four native foot locks must remain correct. The prior M3.2 tip-to-tip closeness remains a *previous* result.
- Compiled editable vectors are separately checked after native key reduction, so source-only math cannot substitute for the actual Android Canvas renderer. Independent Node analysis hashes the original source, pose program, native joint and weapon trace and full frame/MP4 receipts.
- The new report `omega-combat-staging.json` is **MANUAL_REVIEW_REQUIRED** even when all geometry checks pass.

## Cloud execution

Run the 82 lightweight Node tests:

```bash
node --test scripts/omega-*-test.mjs
```

The OMEGA GitHub Actions targeted Android/Robolectric job is enabled for M3.3 on a separate draft PR. The job builds only unit/native frame proofs, not an APK. It exports 240 story and 100 duel original Canvas frames, both already-verified MP4s and a new 36-frame weapon trace. The existing M1–M3.1 audits continue to run, followed by revised M3.2 geometry and the new M3.3 interior-crossing audit before the PNGs are cleaned up.

**Hard limit:** A 2D centerline intersection is not evidence of exact sword edge width, collision force, finger grip anatomy, cloth physics, emotionally believable acting, spoken dialogue or physical-phone playback. Real cinematic acceptance requires viewing the MP4 and testing it in an actual Android installation. No merge into main or phone installation is performed automatically.