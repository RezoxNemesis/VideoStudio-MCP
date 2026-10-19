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

## Verified scope and pending gates

Official focused Android run: 73 tests, zero failures/errors, two skips; archived
focused-v5-junit/Counts.json. Standalone native development also passed 67 tests,
including 72 actual comparisons to 36 pre-edit PNG baselines. Current contact
checks exercise hand-only ink, transformed prop pinches and knee-ground contact.
All five manual clothing UI tests passed against a pinned earlier native
revision; the official final focused run covers those controls again.

Independent review inspected all 36 v5 native stills, both builds and all six
views, and accepted the bounded continuous-garment target. This is not full-motion
or professional human-animation acceptance. Full Node working-tree verification
passed 306 smoke checks and 610 tests; relevant Node bytes remained unchanged
through final native verification. Worker dry run passed; no deployment.

The prepared native study harness will test existing reach/kneel/balance/lift
presets × two builds × six views: 48 parameterized requests, all 96 frames each,
168 original legacy comparisons and 96 archive-restored comparisons. Start with
short native previews; full motion remains pending. Frames, assertions, archives
and retakes add no creative scenarios. The owner increased the broader programme
to 1,000 distinct scenarios and more if necessary after current quality passes.

The separate hand motion repair is still pending; combine its verified source
before a final 4.8.2 signed candidate and complete motion matrices. The original
124-second voiced story remains NEEDS_REWORK for overall standard-2D quality.
No stable release, deployment, phone/live-MCP parity, audio audition or native
Android video-codec acceptance is claimed by these workspace checks.
