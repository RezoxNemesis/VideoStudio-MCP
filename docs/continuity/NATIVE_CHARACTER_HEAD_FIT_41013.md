# Native character head proportions — RED-only checkpoint

Worktree: `/workspace/work/VideoStudio-Head-Fit-v41013`.
Branch: `feat/native-character-head-fit-v41013`.
Base: `32faad8a2ed955c72356b3729e5911984ab2158e` (verified 4.10.12/code515).
No production implementation or version bump exists at this checkpoint.

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
VIDEOSTUDIO_CAPTURE_HEAD_FIT_BASELINE=/workspace/work/native-head-fit-20261022/baseline-32faad8
```

The source-free helper writes ten original native head cases: eight numeric yaw
positions and two representative posed/legacy-omission cases. Each has ordinary
editable geometry JSON, a native PNG, canonical-layer SHA, raw-pixel SHA and PNG
SHA. The output directory must not already exist. Native graphics execute only
the original product source; no source image/model/hosted/external art is used.

Root then runs `SketchHumanHeadFitTest` and
`SketchHumanHeadFitPersistenceTest` with:

```
VIDEOSTUDIO_HEAD_FIT_BASELINE=/workspace/work/native-head-fit-20261022/baseline-32faad8
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

No production implementation is authorized until root observes the native
baseline and RED results. Preserve every old or failed native/model generation.
No Gradle from agents, main/Worker/stable publication, replacement signer,
Gallery, external rendering, hosted inference, phone install or film-completion
claim.
