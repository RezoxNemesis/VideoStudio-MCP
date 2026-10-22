# Native character head proportions — focused native GREEN

Worktree: `/workspace/work/VideoStudio-Head-Fit-v41013`.
Branch: `feat/native-character-head-fit-v41013`.
Base: `32faad8a2ed955c72356b3729e5911984ab2158e` (verified 4.10.12/code515).
Root observed the original native baseline and genuine feature RED. The scoped
production implementation passed focused native GREEN. Full integration and
the future version bump remain root-owned and pending.

The current anatomy head uses one local construction volume and arbitrary native
orthographic camera yaw/pitch, but hardcodes all head proportions. Ordinary path
editing can change a projected drawing; it does not carry those edits to another
camera. The proposed optional anatomy-only `settings.headProportions` retains one
editable proportional recipe in the existing `human.settings` payload.

## Frozen proposed contract

- A nonempty strict object with optional `jawWidth`, `chinLength`, `eyeWidth`.
- Each explicit value is a finite actual number in `[0.75, 1.25]`.
- Omitted members remain omitted in normalized requests/settings. Omitted whole
  profile preserves all old geometry, raw native pixels, defaults and floors.
- Explicit identity values `1` preserve old geometry and raw pixels exactly.
- Any explicit profile, including identity, requires future native 4.10.13 on
  ordinary, alias, retained-object, component and recursive story/correction routes.
- Reject legacy/omitted figure style, unknown fields, empty profiles, strings,
  booleans, null, arrays, nonfinite and out-of-range values.
- Jaw width changes the local lower mandibular envelope; chin length scales local
  lower-face geometry below the resting mouth landmark `y=14.3`; eye width scales
  socket geometry in both local x and local z about its actual eye anchor.
- Fitting must precede projection, triangle-facing/normal tests, depth and
  occlusion. Moving only projected ink in `Head.point()` is insufficient.
- Existing world skeleton, joints, pose controls, camera, timing and budgets stay
  fixed. Existing Undo, component and editable archives retain the same profile.
- Manual anatomy opt-in uses the same validated settings. Legacy submissions omit
  the profile, including after switching back from anatomy.

This is an authored proportional construction tool. The single front source can
ground frontal relationships; exact side/back anatomy, hidden ears and nasal
depth remain proposed design. The feature cannot guarantee generated AI identity,
recover unseen views or certify final artwork. Nasal projection, arbitrary lock
topology, new models, automatic fitting and temporal AI coherence are outside this
slice.

## Root-owned baseline then RED execution

Only root runs Gradle/JUnit/native rendering/model inference. Before any product
edit, root runs `SketchHumanHeadFitBaselineStudyTest` with:

```
VIDEOSTUDIO_CAPTURE_HEAD_FIT_BASELINE=/workspace/work/native-head-fit-20261022/baseline-32faad8-v2
```

The source-free helper writes ten original native head cases: eight numeric yaw
positions and two representative posed/legacy-omission cases. Each has ordinary
editable geometry JSON, a native PNG, canonical-layer SHA, raw-pixel SHA and PNG
SHA. The output directory must not already exist. Native graphics execute only
the original product source; no source image/model/hosted/external art is used.

Root then runs `SketchHumanHeadFitTest` and
`SketchHumanHeadFitPersistenceTest` with:

```
VIDEOSTUDIO_HEAD_FIT_BASELINE=/workspace/work/native-head-fit-20261022/baseline-32faad8-v2
```

There are six geometry/settings tests and two public persistence/manual tests.
Expected new-feature failures are old native settings rejecting the new profile
and the missing manual opt-in. The omitted baseline case should pass. Compile,
fixture, archive or renderer errors must be reported separately, corrected in
test fixtures only when appropriate, and preserved; they are not feature RED.

`scripts/human-head-fit-contract-test.mjs` currently has four observed Node RED
tests. Actual failures show the unknown settings key, missing future version
floor and absent capability. The first Node launch lacked a local dependency
link and failed to resolve zod; it was not treated as RED. Reusing the existing
ignored node_modules symlink allowed the genuine RED run, saved separately at
`/workspace/work/native-head-fit-20261022/node-red-v1.log`.

Root baseline v1 failed a test-fixture ownership mistake: fill and outline share
one JungleCelInk commands array, and the close-up fixture mapped that array twice.
The fixture now clones each object's commands before mapping, exactly as the
existing native head test does. Original failed fixture/source/output/log remain
preserved. No production validation, geometry or assertions were changed.

Root baseline v2 passed one test in 2.771 seconds with all ten native cases.
Root then observed eight native tests with seven failures in 3.854 seconds: six
valid-profile cases rejected the unknown key, and manual controls were missing.
The exact old geometry/raw-pixel omission regression passed. These genuine RED
results authorized the production implementation; the tests remain unchanged.

The implementation fits cranial vertices before triangle-facing/normal/depth
calculations. Facial paths share the same local lower-face mapping. Eye socket,
lids, iris and brow share a local x/z scale about the original eye anchor. Hair
cap, part roots and curtain retain their original cranial frame; this slice does
not fit fringe topology. Neither numeric camera nor world joints are modified.
Mesh ink projects its already-fitted vertices once, while ordinary facial paths
apply their local mapping before projection. Identity operations return original
coordinates without added arithmetic. Strict manual/Node/native normalization
and the recursive 4.10.13 floor use existing ordinary actions.

Full Node GREENv3 passed 786 tests and 306 smoke checks. Two earlier full attempts
exposed test-VM contexts missing the new capability-helper binding. Their logs
are preserved. All three fixture contexts now parse the actual native capability
literal and retain exact native/Node deep-equality assertions. No production
Java change was needed for these fixture failures.

Root native GREENv1 passed seven of eight tests. The manual fixture clicked the
initial legacy submit before the dialog's queued onShow handler was installed;
the actual failure included UnExecutedRunnablesException. One initial native
main-looper idle was added, retaining every expected request/profile/omission
assertion; the failure message now includes the real native dialog status. No
production fix was needed. The failed test source, launcher and log are retained.
Root GREENv2 had a launcher classname slash typo and failed initialization; it is
preserved separately, not counted as a product regression. Dotted-class GREENv3
passed all eight native tests in 5.898 seconds, including exact original ten-case
geometry/raw pixels, explicit identity, local jaw/chin/socket changes, continuous
yaw and world-pose preservation, strict settings, public manual opt-in/legacy
omission and Undo/component/archive persistence with four exact native frames.

These are focused technical checks. Source-free native visual view review, full
combined Android regression, future 4.10.13/code516 version integration, CI and
original-signer verification remain root-owned. No accepted actor likeness,
mastered character angles or completed film is established.

Preserve every old or failed native/model generation.
No Gradle from agents, main/Worker/stable publication, replacement signer,
Gallery, external rendering, hosted inference, phone install or film-completion
claim.
