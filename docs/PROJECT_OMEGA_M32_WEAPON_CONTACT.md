# OMEGA M3.2 — The weapon must meet, not merely the wrists

## Measurable defect in the M3.1 film

The verified 100-frame Rooftop Standoff proof brought Vale and Rook's **wrist joint centers** to about 3.41 source-canvas pixels apart on frame 16, yet the **actual editable vector weapon cores** still pointed away from one another. Independent reconstruction from the native M3.1 joint trace and prepared weapon geometry gives approximately **266.59px tip-to-tip gap** and **50.92px shaft centerline gap** at that same frame. A wrist joint proximity test does not establish any baton contact.

## Technical fix — native, editable artwork

- `SketchAnimationWeaponContact` operates exclusively on the existing, original, project-owned vector paths. It checks four synchronized weapon parts for each actor: baton fill, edge, core and grip. Each must be rigidly bound to the correct authored wrist joint.
- Calculates the 2D projected locations of the actually drawn M/L core paths using `SketchAnimationCompiler.sample` native joints and authored source-canvas coordinates, rather than drawing a decorative impact effect or guessing positions from an MP4.
- Builds 11 editable object rotation keys per part over frames 0–35, smoothly staged through anticipation, strike, hold and recovery. The weapon rotates about its **wrist rest pivot**; authored arm/leg/root/camera poses are unchanged.
- Aims both core tips at a shared physical location above the wrists, within six source pixels, preserving all four native hard foot-lock contacts. The secondary impact flash/rays move to the measured **weapon-tip intersection**, not the wrists.
- The native compiler still independently verifies the editable source and its baked Canvas output. Target is tip separation <=6px and baked vector tip separation <=8px; exact curved/full silhouette collision is a separate unsolved requirement.
- Exports 36 original wide-shot weapon path segment measurements from Android/Robolectric and SHA-256-binds them to the same 100-frame native rig proof.
- `src/omega/weapon-geometry.js` independently reconstructs all 36 original weapon-centerline paths from source art, original rig keys and native joint samples. `scripts/omega-weapon-contact.mjs` checks the trace SHA, source/program SHA, prior M1–M3.1 QA, original verified MP4, planted-foot diagnostics, live impact effect position, geometry frame order and pathological tip jumps.

## Local predictions, not native certification

The deterministic geometry reproduction against the **actual original M3.1 source and native solver trace** predicts a tip-tip gap reduction from roughly 266.59px to ~0.079px at frame 16, with the impact effect centered at x~0.52556, y~0.38956 in the original canvas. The intended native 2D centerline collision point is about (673, 280) pixels. With a staged rotation envelope, the largest predicted wide-shot tip step is ~73.6px, better than the initial naive ~112.7px. These are predictions **until the changed Java source is compiled and CI renders a new film**.

Run focused Node tests:

```bash
node --test scripts/omega-weapon-contact-test.mjs
```

After an explicit CI-owned Android Canvas film proof, and **before PNG cleanup**:

```bash
node scripts/omega-weapon-contact.mjs \
  --root android/app/build/sketch-duel-film-proof
```

The output `omega-weapon-contact.json` must say `status=MEASURED`, `qualityDecision=MANUAL_REVIEW_REQUIRED`, `measured.correctedTipSeparationPx<=6`, `measured.effectToTipContactOffsetPx<=1`, and `measured.maximumWideShotTipStepPx<85` to pass technical geometry evidence. It must not certify professional animation or physical Android device playback. The MP4, original source/program and trace hashes must match the prior M1 receipt and the native SHA manifest; malformed/altered evidence returns BLOCK.

## Security and production boundaries

The feature adds no new paid or network service, model inference, public MCP endpoint, Gallery browse, Android permission or package-signing change. All evidence is generated from synthetic/original bundled training art on GitHub CI. The original VideoStudio main release, owner projects and owner phone are untouched by this draft.

**Still not solved:** exact outline/mass collision, weapon penetration volumes, hand grasp deformation, anatomy/paint-cel realism, better facial expressions and actual voice/audio sync. Passing a solver constraint does not prove cinematic quality. The next iteration should compare actual frame crops and pursue native editable elbow/glove contour deformation only after successful M3.2 render and human evaluation.