# Native tailored body drawing

Source targets 4.8.2 / code 485. The optional human setting `clothingStyle`
selects `legacy` or `tailored`, independently of face structure and shading.
Omission remains absent in old canonical requests/retained programmes and uses
unchanged legacy drawing. Any explicit value, including legacy, requires 4.8.2.
Existing human requests keep their 4.8.0 floor. Native and Node validation,
retained object/layer/replacement patches and nested story routes share this
contract. Opaque archives retain their existing action floor; style-bearing
contents require 4.8.2. No new command aliases or identity/signing changes.

## Reproduce inside VideoStudio

Manual: Sketch Studio > Brush / Text > Human acting > Clothing > tailored.
Choose either build and one of six Human views; retain existing presets, custom
poses, motion, speech, ground, face and shading controls. Selection invalidates
old preview work. Legacy selection omits the new field in manual requests.

Ordinary ChatGPT: call app_status, require connected native 4.8.2, and read
sketch_capabilities.humanAnimation. For a short isolated study, create a sketch
at 1280×720, 96 frames, 24 FPS, then submit through the existing revision-bound
sketch_edit route:

```json
{"type":"add_human_animation","layerId":"layer_1","actorId":"actor","settings":{"view":"front","build":"slender","clothingStyle":"tailored","faceStyle":"angular","shading":"soft","size":1.25},"preset":"reach","fit":true}
```

Inspect actual native previews and motion; retain editable archives. The public
result remains an editable retained programme. No new per-stroke conversion
command is implied. No private workspace pose recipe is needed.

## Drawing change and preserved invariants

Native continuous Bezier envelopes replace segmented sleeve/trouser construction
only in the tailored branch. Physical flexion controls inner compression and
folds, with shared edge tangents, local depth ordering and semantic coat,
trouser and skin lighting fields, including equal user colours. Solved joints,
bones, grips, hands, shoes and clocks stay fixed. The coat responds at its
armholes and compression region; this is garment drawing, not cloth simulation
or an independent chest/pelvis twist rig.

The v2 static review rejected a background notch at the sleeve-to-coat shoulder
join. The final drawing extends the continuous sleeve root into its armhole,
preserving the armpit gap without a cover disk. The exact notch fixture failed
on preserved v2 and passes on v5. Additional leg-gap and actual depth fixtures
were investigated; unrelated nearer limbs are isolated from the sleeve/coat
comparison rather than counted as erroneous coat occlusion.

Private evidence: engineering-review/tailored-body-20261019. Earlier generations,
including rejected v2 and intermediate v3/v4, remain intact. Final frozen v5
hashes: Figure f9091fa57f5eafa22b0b45de8b5df0382140a85d3960d09f2e8c1ea49ce06c16;
helper 1468d94b72f9f709087fd14251f3839419f550c47f1cecc340d3b313ae8e3150;
geometry test 485983e63ba0e5a38fb0e67fea4c3194762221263640e99d4ccb19ed861ab64f.

## Fresh lift preset repair: B45

Newly generated `lift` poses now bring both wrists forward to a full-strength
peak `nearHandZ`/`farHandZ` of 72 in the torso frame, with elbow swivels
`45*a` / `-45*a` following the existing action envelope. World coordinates still
depend on body lean and root transforms. The elbows lower beside the body and
the forearms rise toward the hands. This changes the native preset generator,
independently of clothingStyle; it is not a garment mask or painter-order repair.
The existing nine-key clock, fixed bones and sole anchors remain unchanged.

The original lift sent arm segments through the torso and produced a vest-like
silhouette. Candidate A cleared the torso with forward wrists and zero swivel,
but its broad horizontal elbows were rejected for carrying/lifting staging.
Candidate B45 was selected after actual native comparisons. Independent review
accepted the bounded basic carrying pose and garment continuity across all 192
high-view frames, both builds, plus two front frame-48 stills. It did not certify
the remaining motion angles, load/weight acting or professional human animation.
A's 1,152 native PNGs/source snapshots, the rejected original lift and all earlier
generations remain preserved.

The other 23 presets, including tie/explain, remain unchanged. Saved retained
poses and archives keep their original geometry/pixels; explicit custom poses
and pose overrides remain authoritative without hidden anchor correction.
Canonical request fingerprints and existing operation-ID receipts are unchanged:
retries return the original result. Use fresh operation identities to author a
new repaired lift.

Manual Human acting and MCP `add_human_animation` use the same native generator,
with no new request fields or aliases. Native and Node capability metadata expose
`generatedLiftMinimumNativeVersion: 4.8.2` and describe torso-frame wrist depth,
exterior routing, override authority and retained compatibility. Require a
connected native 4.8.2 for the repaired preset; the existing old-request floor
remains unchanged. This shared source/contract verification is not live-host or
phone execution evidence.

Frozen final lift hashes, also archived in
`focused-lift-B45-junit/Source-Hashes.json` under the private evidence root:

- Presets: `1358bb2f3fceece4eed6d32aa8f170dece3427c347ba251e37b159c80cbaebe5`.
- Anatomy test: `1b7d7cb7ce13c1696e1a4a44a8ea17384b39efe8914becb8b1a65afd10c28928`.
- Native metadata: `77a7259f2918bdbc6269e39bb5b505cfa2d40dbf479d5370639ccbf0e9994191`.
- Node metadata: `b2a308f8f6ee3d8e54e90f43b9adae11d4aa49185140c3328cabe73f1ef15d13`.

## Verified scope and pending gates

Earlier garment-focused Android run: 73 tests, zero failures/errors, two skips; archived
focused-v5-junit/Counts.json. Standalone native development also passed 67 tests,
including 72 actual comparisons to 36 pre-edit PNG baselines. Current contact
checks exercise hand-only ink, transformed prop pinches and knee-ground contact.
All five manual clothing UI tests passed against a pinned earlier native
revision; the official final focused run covers those controls again.

Independent review inspected all 36 v5 native stills, both builds and all six
views, and accepted the bounded continuous-garment target. This is not full-motion
or professional human-animation acceptance. Final B45 official Android focused
verification passed in 2m26s: 81 tests, zero failures/errors, two skips, archived
in `focused-lift-B45-junit/Counts.json`. The physical staging oracle failed on A
and passes on B45; native checks cover torso clearance, continuous routing,
both builds/six views at 24/30/60 FPS, fixed bones/soles, recomputed grips and
actual prop contact, old retained pixels/archives and receipt compatibility.
The final combined standalone run also passed 75 tests. Its product classes and
nested classes are byte-identical to those used for B45's captured native frames.
Final full Node verification passed 306 smoke checks and 610 tests against the
frozen metadata; see `node-final-B45.log`. Worker dry run passed; no deployment.

The native study harness will test existing reach/kneel/balance/lift
presets × two builds × six views: 48 parameterized requests, all 96 frames each,
168 original legacy comparisons and 96 archive-restored comparisons. Four full
preview captures passed technically with 384 PNGs, 28 historical comparisons and
eight archive comparisons; three were visually acceptable and the old lift
failure remains preserved in `motion-preview-v1`. B45's separate two complete
96-frame high-view sequences are in `lift-candidate-b45-motion-v1`, and its
front stills are in `lift-candidate-b45-stills-v1`; the independent verdict is in
`independent-lift-b45-review/REVIEW.md`.

The full 48-case motion matrix across all six angles is still pending after hand
cleanup. Preview completion does not imply fullMatrixComplete. Frames,
assertions, archives and retakes add no creative scenarios. The owner increased
the broader programme to 1,000 distinct scenarios and more if necessary after
current quality passes.

The verified hand vector backend and bounded contour cleanup are now integrated.
Both study harnesses bind source provenance to the actual compiled native product
artifact and recheck its bytes at completion, alongside source hashes/file sets
and HEAD. Final 4.8.2 signing/CI and complete motion matrices remain pending. The original
124-second voiced story remains NEEDS_REWORK for overall standard-2D quality.
No stable release, deployment, phone/live-MCP parity, audio audition or native
Android video-codec acceptance is claimed by these workspace checks.
