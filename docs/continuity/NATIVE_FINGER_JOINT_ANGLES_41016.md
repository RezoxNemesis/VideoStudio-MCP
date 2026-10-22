# Explicit finger flexion — focused native implementation verified; full519 pending

Isolated `feat/native-finger-joint-angles-v41016` starts exact 518 source
`b3b8d36db4684128d98bd4fee56e246abf564627`. Root genuine RED executed11tests/10FAIL22.118s; the pinned nine-case old geometry/native pixels passed, while new fields/manual controls failed. Root nativeGREENv1 executes11/11 PASS32.07s on retained FULL518 plus the exact five production-class overrides. All three frozen native tests remain byte-exact. Root owns
native RED/GREEN, one Gradle lane, future 4.10.16/code519 integration and signing.

The actual native isolated hand/banana v1 passes provenance, editable archive,
split recomposition and projected alpha checks but is visually NEEDS_REWORK.
Its proximal fingers read as long parallel columns and its thumb as a button
below the fruit. Projected cap overlap does not prove a physical grasp. Existing
finger curl fixes local MCP/PIP/DIP to `[60,92,48] * curl` degrees: even maximum
curl cannot exceed 60 degrees at MCP. The proposed control authors those three
local flexions independently. It does not add contact IK or recover actor anatomy.

## Shared optional pose contract

`pose.fingerJointAngles` is an optional strict object with 1..4 entries. Allowed
keys are `index`, `middle`, `ring`, `little`. Each value is exactly three finite
numeric degrees `[MCP,PIP,DIP]`, respectively bounded `0..110`, `0..120`, `0..90`.
Reject unknown/empty maps, null, booleans, strings, nonfinite values, wrong tuple
sizes and out-of-range degrees. `thumb` is not an allowed key. Existing thumb
channels, bone lengths/radii, wrist/view/handedness, materials and part ownership
remain unchanged. Explicit map presence requires future native4.10.16, recursively
through ordinary operations, retained hand objects, components and story plans.
No new canonical action. Node must not insert optional maps or default triples.

An explicit triple overrides only that finger's scalar curl at that endpoint.
For each finger explicit anywhere in the programme, resolve each interval
endpoint to its explicit triple or that endpoint's legacy curl-derived degrees;
interpolate resolved degrees using the outgoing key's existing easing. Do not
switch geometries abruptly at a key or interpolate scalar curl before applying
one endpoint's override. A finger never explicit anywhere must retain the exact
old `Math.toRadians(factor * curl)` arithmetic, even when a different finger is explicit.
Omitted entire programmes retain original nine-channel arrays and old pixels.

Internal option: retain existing `double[]` signatures. Explicit programmes can
append twelve degree slots; only fingers explicit somewhere use resolved slots,
while inactive slots signal the old arithmetic path. An internal NaN sentinel
is acceptable only if never serialized, accepted as user input, or used in mesh
math. A mask/container is another internal option. Tests constrain geometry and
input behavior, not one internal representation. Computed legacy-equivalent
triples must be distinguished from decimal-rounded approximations in identity
claims; avoid changing omitted arithmetic to simplify the new path.

Apply angles in local 3D before projection/facing/normals/lighting. The resulting
same joints must drive skin tubes, nails, creases, bounds and visible ownership.
Sampled retained cels must serialize resolved triples for fingers explicit in
the programme, preserving frozen native pixels. Keep legacy unspecific fingers
on their original scalar path; never serialize internal sentinels.

## Manual authoring

Inside existing Custom poses, add blank optional fields per finger:
`index MCP (degrees)`, `index PIP (degrees)`, `index DIP (degrees)` and equivalent
middle/ring/little controls. Three blanks omit that finger; partial triples
reject. Selection/apply/add/preview/create save pending edits through the ordinary
constructor, and selecting keys reloads their explicit maps. Blank legacy poses
remain omitted. Retain all old reset/study/custom/easing behavior. No independent
style or ownership control is part of this scope.

## Focused tests and evidence

`SketchFingerJointAnglesTest` has eight native tests: original nine-case pinned
legacy geometry/native pixels; true local MCP90 versus old60 ceiling; all four
independent triples and unchanged lengths/thumb; mixed endpoint interpolation
across easing; untouched fingers/thumb exact double bits; computed equivalent
triples across six views; strict constructor/retained/nonfinite validation; and
sampled/split native-pixel exactness.

`SketchFingerJointAnglesIntegrationTest` binds atomic retained patch rejection,
Undo/Redo, ordinary native component and portable archive/pixel preservation.
`SketchFingerJointAnglesUiTest` has two real-dialog tests for blank omission,
explicit triples and validation without submission. Root executed the exact11 tests against unchanged518 and observed genuine feature RED. All three native test files remain byte-exact through implementation. Exact test/source/launcher/log evidence is frozen outside Git at `film-production-20261022/finger-joint-angles-red-v1-executed`. Focused nativeGREENv1 passes11/11 in32.07s; full519 remains root-owned and pending. Compile failures are not genuine feature RED.

Shared native helper validates strict maps, resolves explicitly addressed fingers at both endpoints, and appends only internal inactive-slot markers for unchanged fingers. Complete omission returns the original nine-channel array. Computed legacy-equivalent explicit programmes retain the scalar path too. Sampled cels emit finite resolved triples only for relevant fingers. No new cache, global state, source mutation, bone lengths or owner split was introduced. Node includes strict constructor/retained schema, recursive4.10.16 gates, exact native capability JSON parity and ordinary MCP help. Manual controls read/save/reload each key through the same operation.

Focused Node9/9 passed. Full Node first attempt exposed an existing source-parity VM fixture that needed the new helper binding; the failing log is retained. The parity assertion remains exact and now loads helper JSON from the native source. Fresh full Node retake passes799/799 plus all306 smoke checks. Node source syntax and git diff checks pass. Focused nativeGREENv1 passes11/11; full519/version/CI/signing and actual improved grip artwork remain pending root. No agent Java, Gradle, native render or model run occurred.

Native art review remains separate after full integration. The first actual
grip must keep the original fruit and an explicit authored pose, with no physical
cylinder, automatic thumb opposition, graphite finish or acting acceptance claim.
