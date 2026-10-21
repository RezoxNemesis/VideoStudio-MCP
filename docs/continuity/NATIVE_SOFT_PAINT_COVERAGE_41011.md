# Native raster maximum coverage — candidate4.10.11

This isolated feature extends frozen4.10.10/code513 source8ab009f. Root owns
the future integrated4.10.11/code514 version bump, all local Gradle, full Android
regression, candidate CI and original-signing checks. None is inferred here.

The real source-assisted blink v6 retained strict source/iris/Undo/archive checks
but failed visual review. Its alpha-locked soft shadow became an obvious flat
painted band. The existing native brush accumulates each overlapping dab's flow
into its stroke mask, so dense soft sweeps approach the whole-stroke opacity cap.
This is existing flow behavior, not evidence of corrupt rendering. Preserve every
failed artistic generation and its native requests. Native maximum coverage is a
bounded shading control; it does not supply anatomy, match tones or heal seams.

## Shared control

`paint_stroke.brush.coverage` is optional, strict `maximum` or `accumulate`.
Explicit choices require native4.10.11 in ordinary and nested MCP requests.
Omission preserves the old accumulation branch, normalized journal bytes,
fingerprints and old feature floors. Explicit accumulate produces the same
pixels while retaining its explicit choice in the normal paint journal.

Maximum uses the per-pixel maximum of native dab coverage times flow inside a
single stroke. Whole-stroke opacity still applies against the pre-stroke paint.
Separate strokes composite normally. Pressure, soft/flat tips, deterministic
grain, clipping, alpha lock, paint/erase/owned clone sources, cancellation and
existing point/dab/pixel/tile budgets remain shared. Grain is indexed per dab;
maximum does not promise that additional grain dabs cannot reveal new coverage.

Manual: Sketch Studio > Paint studio > Paint settings > Stroke coverage.
Brush default (build up) leaves the field omitted. Keep soft profile selects
maximum. Build up selects accumulate. Reopening the dialog preserves
the current choice and the other brush settings. The default remains omitted.

Vector `precision_contour.brush` and its fitted pressure presets use another
renderer. They still reject this raster-only field strictly and retain their
old schemas, rendering and version floor. No canonical action is added. Opaque
archive/component actions retain their old floors; their native dependency and
journal validators carry the selected mode when actually restoring artwork.

## Evidence boundaries

Observed pre-implementation RED:6native tests,5failures/0errors/0skips; missing
coverage admission and manual control, with strict-invalid/vector rejection
passing. Node RED:4tests,3failures/1pass. An initial missing-zod setup failure is
recorded separately and is not feature RED. Focused native tests additionally
exercise the legacy plateau, exact single-stamp feather, flow/opacity/alpha lock,
clone/erase, replay, Undo/Redo, components, archive pixels and source provenance.

Fresh root focused Android GREEN:73tests/0failures/0errors/0skips across9suites
(11new and62existing paint regressions). Full Node:777tests/0failures plus
306smoke checks. GREEN1 preserved two fixture-only rawDouble-versus-wireInt
comparison failures: the test JSON-copied only one side before comparing exact
canonical strings. The correction strips the field directly from the fresh owned
comparison object, retaining exact canonical-byte assertions; production
canonicalization and unrelated fingerprints were not changed. The initial low-alpha
clone RGB fixture was also corrected before GREEN to saturated green, with its
original bytes and rationale preserved; no observed assertion was erased.

Full integrated Android, actual upgraded-app blink review, CI and signing remain
pending at this documentation checkpoint. A passing
brush regression is not an accepted blink or a complete240secondfilm. Runtime
art authoring and rendering belong to actual VideoStudio capabilities; no external
art generator, bitmap helper or Gallery is used. Do not claim unseen anatomy,
independently drawn source pixels, phone execution, native video codec or artistic
acceptance without the corresponding actual evidence.
