# Native storyboard kinetic continuity — batch 2 (experimental)

## Real-world baseline

The user's real VideoStudio Next 3.7.0 \`Test 1\` source project contains 23
image assets with 13 distinct story drawings (black and white source-painted
swordsmen). A native 121-frame, 24-fps, 5.08-second exported MP4 with Foley
was sampled on-device. This remains **visually rejected** as a cinematic
sword fight: actors freeze within shots; joints and sword tips are not
independently animated. The user additionally attached that video for QA.

## Implemented on this branch

1. **Phrase-safe director.** Original filename \`White_Advance.webp\`
   contained substring \`hit\`, causing false IMPACT classification.
   \`White_Knockback\` was also misclassified; landing and follow-through
   lost their action beats. The parser now uses normalized whole phrases,
   specific impact/strike/recoil precedence, and all 12 regression labels.
2. **Source-contour motion.** The 2D bitmap mesh now continuously approaches
   the contour observed in the next *actual* drawing instead of applying a
   sine-envelope warp that returns toward the original pose at the handoff.
   This is bounded 2D source-pixel deformation, not newly synthesized limbs.
3. **Time-distributed pose handoff.** Actor-local image texture transition
   spans meaningful portions of the action instead of 8% of the pair, with
   narrower sword-contact blending, and applies the computed limited source
   actor tilt. No full-frame crossfades or replacement stickmen.
4. **Stricter native shot acceptance.** The renderer samples early/middle/
   late motion, rejects hold-jump-hold temporal patterns, and checks EACH
   tracked original subject at three real generated moments rather than
   midpoint only. Stores diagnostics in per-shot reports.
5. **Originals, privacy, compatibility.** Generated content stays in separate
   VideoStudio output projects. No Gallery listing, paid cloud inference,
   new app ID, signing-key rotation or silent fallback.

## Explicit limitations / next acceptance gate

These changes are **not** full 3D IK, learned pose generation, accurate sword
attachment, hand/wrist choreography, clean occlusion completion or camera
parallax recovery. Pixel-level changes passing automated tests are not
evidence of convincing combat. Some masks of complex drawings may still
fail and request source-character rigs. Proper cinematic acceptance requires:

- Unit/integration tests and an Android-native build, with a same-signed
  update installed on the user's device.
- New VideoStudio test render using the original \`Test 1\` images, independently
  sampled frames and a critical playback evaluation by a human.
- Quantified **two-actor** identity and actual hand/blade, torso, hip,
  knee and foot trajectories through strikes, parries and impact.
- If original rig annotations or a verified anime motion-model pack are
  missing, **report this blocker** instead of calling a 2D mesh
  interpolation a finished professional animation.

## Update packaging

The fix build uses the existing VideoStudio Next application ID, updates
versionCode from 370 to 371 and versionName to 3.7.1, and preserves the same
cached, pinned development signing identity. Installing the resulting APK
must perform an **in-place upgrade** over compatible 3.7.0 (never uninstall).
Full user-device rendering must be repeated after the update.
