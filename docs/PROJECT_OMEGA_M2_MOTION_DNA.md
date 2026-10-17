# Project OMEGA M2 — Motion DNA (cloud-side bounded diagnostic)

**Status:** M2 prototype. No new Android editing capability, APK release, animator autonomy, native phone command, phone Gallery access, network upload or new privileged MCP action. Stacked on M1 draft branch.

## Purpose

M0/M1 verified all 100/240 native-rendered PNGs and the generated MP4s, but their distinct frame hashes cannot demonstrate meaningful acting. M2 operates on those **same already-verified CI MP4s**, reports localized brightness/pixel motion in manually defined character-interest areas compared to image-background changes, and estimates a small uniform background shift. This is *not* semantic body motion detection or quantitative art-quality certification.

## Workflow (GitHub Actions and local)

```bash
node --test scripts/omega-cinemabench-test.mjs scripts/omega-native-proof-test.mjs scripts/omega-motion-dna-test.mjs
node scripts/omega-motion-dna.mjs --kind story --root android/app/build/sketch-story-film-proof
node scripts/omega-motion-dna.mjs --kind duel --root android/app/build/sketch-duel-film-proof
```

The existing film proof encoders call M1 then M2 sequentially **before deleting source PNGs**. M2 writes `omega-motion-dna.json`, with the approved MP4 SHA, M1 report SHA, named shots, every analyzed pair and warnings. Any invalid or altered MP4/source receipt, FFmpeg failure or invalid shot map writes a blocked report and fails the CI proof rather than silently returning success. There is no automatic publish or import.

The frame decoder runs FFmpeg with a bounded explicit local filename, produces 160×90 grayscale at 6 FPS, limits to 72 decoded samples/20 MiB input, and has a 30-second timeout. No runtime image search, arbitrary file discovery or external requests occur. `omega-motion-shot-plan.json` declares *rough human-selected foreground interest rectangles* from the two original public example contact sheets. The coordinator will need explicitly owned equivalent geometry for any future film.

## Technical approach and labels

For each pair inside the same named shot, M2 compares the background of the previous frame with translated versions of the next frame (maximum ±6 downscaled pixels). It then reports luminance differences **after** the best uniform translation across the background, measured separately inside and outside character-interest regions.

- `NEAR_STATIC`: little grayscale movement in either region.
- `GLOBAL_TRANSLATION_CANDIDATE`: matched background translation, little residual localized movement. It could be camera motion or a moving background plate.
- `LOCAL_MOTION_CANDIDATE`: interest-region changes exceed residual background changes. It could be a limb, moving shadow, effect or another foreground change; no human/rig inference.
- `LOCAL_MOTION_OVER_CAMERA`: local changes remain after aligning translated background.
- `BACKGROUND_ONLY_MOTION_CANDIDATE`: motion appears more in the background than in coarse interest regions.
- `CUT_OR_MAJOR_TRANSITION`: strong global mismatch; may be a cut, lighting change or unmodeled camera motion.
- `INDETERMINATE`: ambiguous. No automatic positive quality conclusion.

Declared shot boundary pairs are not scored. Reports preserve each candidate classification and numeric fractions, the two frame indexes, camera shift estimate and honest uncertainty. Every completed analysis sets `qualityDecision: MANUAL_REVIEW_REQUIRED`, `semanticActingQualityVerified: false`, `ownerPhonePlaybackVerified: false`.

## Engineering regression acceptance

The tests deliberately distinguish a static drawing, rigid synthetic camera pan, character rectangle motion without camera, combined pan and independently moving rectangle, full-frame lighting pulse, distinct cuts, malformed ROIs, forged frame count, altered MP4 and a rejected M1 proof. Nothing calls real device media or uploads video. Both original project film proofs must remain playable and CinemaBench-verified.

## Explicit limitations

This measures luminance change on 6 FPS/160×90 proxies, **not** all 24 FPS native frames. It cannot identify hands, knees, facial expression, body balance, camera zoom/rotation, sophisticated parallax or continuity, and does not measure actual rig joint kinematics. The coarse rectangles and shot cut points can misattribute moving text, shadows, rain or VFX to a person. A uniform light change can be mistaken for a major transition. Its proposed follow-on M2.1 is an opt-in rig-joint trajectory export from the existing editable Android animation compiler; M3 physics work then uses authored joints, planted foot contacts and collision geometry rather than assuming pixel changes demonstrate animation quality.

**Privacy:** only CI-owned example MP4s are read, using verified local filenames. No Gallery enumeration, private-device endpoint, owner keys or cloud media upload. No automatic APK build/merge/deploy.
