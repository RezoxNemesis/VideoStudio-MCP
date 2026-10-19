# Native review and reusable correction recipes — 4.8.4

The owner requested an app-owned system operated by touch or ordinary ChatGPT:
remember a drawing/animation target and issues, offer repeatable code/asset
options, prepare a correction, inspect native before/after frames, apply it and
retain undo. This source adds that workflow and preserves the existing anatomy
prototype, older projects, worktrees, generations and original signing identity.

Manual entry: **Sketch Studio > More > Review & correct**. Start a review on the
current source, record a note, select an object and compatible recipe, prepare a
candidate, inspect before/after (crop or tap to inspect), then apply. Saved reviews
resume earlier tasks. Advanced IDs/operations stay collapsed by default. Ordinary
editor Undo restores an applied correction. Asynchronous operations retain error
inputs and protect lifecycle, cancelled work and stale editor callbacks.

For MCP call app_status and require native4.8.4, then read
sketch_capabilities.correctionWorkflow. Eight shared actions:
sketch_correction_start/get/list/note/options/propose/preview/apply.
New actions preserve exact requests and are rejected before queue writes on older
clients. Existing anatomy/style/tool version floors and legacy omission are
unchanged. Preview uses the existing native image channel; no new model service,
Gallery, external artwork renderer or tracing provider is added.

The bundled version1 catalogue has12 bounded native recipes: neutral_arms,
tailored_clothing, angular_face, soft_face, soft_shading, long_hair,
natural_blinks, graphite_palette, cel_hands, smooth_hands,
anatomical_hand_detail, contour_hand_detail. Options explain unavailable or
unchanged controls and expected checks. They patch existing native retained
objects; expressions, unspecified poses/settings, speech and timeline stay
editable. Hand recipes target retained hand objects; they do not silently replace
the simpler hands attached to a human actor. Anatomy-specific controls remain
limited as documented in humanAnimation capabilities.

Reviews capture an immutable source revision/hash, native audit, notes and up to
four detached candidates. Vector redraws, retained poses and placement edits use
existing native operations. Canvas/FPS/frameCount changes, raster paint/imports,
new reference-image insertion and arbitrary code are excluded from this first
coordinator. Source remains unchanged until apply. Native policy and dependency
validation happen before candidate persistence and again inside ordinary source
mutation. Apply adds exactly one normal history step. Stale sources/reviews and
identity collisions fail; exact receipt replay recovers a committed edit after
interruption without a second mutation. OPEN reviews durably reserve terminal
metadata capacity so quota cannot strand a committed edit. Storage admits16
OPEN reviews and128MiB total metadata/snapshots, retaining completed work.

Targets and acceptance notes are reviewer statements. Native pixel metrics and
mechanical checks do not certify anatomy, finger count, attractive drawing,
acting or speech. The procedure is reusable authored recipe logic, not model
training or autonomous artistic judgment. The current anatomy figure and original
124-second voiced film remain NEEDS_REWORK. The1000+scenario programme expands
after current quality is accepted; assertions/frames/reruns are not new scenarios.

## Verification checkpoint

- Official current-source Android Gradle:60 tests across7 focused classes,
  zero failures/errors/skips; full product and test Java compiled, current
  resources/assets packaged. Includes correction core/recipes/UI, anatomy
  head/body/manual controls and11 exact-raster export regressions. Full CI and
  signed APK status are recorded separately; this check did not build an APK.

- Isolated current-source javac/Robolectric native checks:12 core +7 recipe tests
  pass. Covers durable SQLite, actual before/after pixels, source preservation,
  revision conflicts, exact replays, history undo/redo, interruption receipt
  recovery, original-drawing policy and quota reservation. Initial failures and
  independent review findings are preserved.
- Manual native UI:12 tests pass, including actual recipe selection, before/after
  previews, application and undo, stale/cancel/lifecycle behavior and collapsed
  advanced controls. Actual Android view renders are saved; not phone screenshots.
- Node:638 tests and existing smoke checks pass;94 focused checks include15
  correction tests. Native alias/mailbox/background admission compiled/exercised
  separately; no worker deployment or live ChatGPT/device execution is claimed.
- One original human-motion scenario:96 frames/generation,24FPS,1280x720,4seconds;
  192 native PNGs,96 differing frame pairs,22 motion channels preserved on all96
  frames, unchanged source before apply and exact normal-undo artwork restoration.
  This is a silent mechanical study. Selected still inspection remains
  NEEDS_REWORK for character art, not professional animation acceptance.

Exact private source/class/asset hashes, diffs, command logs, failed generations,
UI images and study receipts are under
/workspace/engineering-review/correction-workflow-20261019.
Isolated checks use frozen official4.8.2 Android resources with new source classes
and an explicit private resource-package copy containing the exact new recipe
asset. They do not establish a newly built4.8.4 APK. Official build/signing/phone
status must be recorded separately when executed; never generate a replacement
key or install/uninstall/reset the owner's app as a shortcut.

## Extending the reusable library

The Android asset `animation_recipes/corrections-v1.json` is the versioned
catalogue. Each entry declares a stable ID, readable label, supported retained
object/style, bounded settings or pose patch, expected result and visual checks.
The Java interpreter validates the entire catalogue before exposing any entry.
It rejects unknown controls rather than evaluating arbitrary embedded code.
Adding a new drawing or motion capability requires implementing that capability
in the native renderer/editor first, then exposing matching manual/MCP controls
and a recipe. A catalogue entry alone does not add a renderer or teach anatomy.

For ordinary ChatGPT the sequence is: inspect the sketch and current revision;
start a review with a concrete target; add an observation; request compatible
options for the retained object; propose a selected recipe; inspect before and
after frames; apply the chosen proposal using the returned review revision and
unchanged source revision. Get/list resume saved reviews. Every action uses the
same native coordinator as the manual screen. No workspace helper is required
on a compatible installed app. A live device connection remains separately
unverified in this engineering environment.
