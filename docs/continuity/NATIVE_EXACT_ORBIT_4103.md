# Native exact camera angles — 4.10.3 / code506

This feature extends the frozen signed4.10.2 source
c4e570888796a12681bfee77a334b08e3f18d29f. It does not complete the owner's
layered manga artwork or240-second film. Current offline neural head, hand and
whole-panel generations remain NEEDS_REWORK; they do not recover artist layers
or establish stable anatomy, likeness, hidden views or animation consistency.

## Result

Existing native3D timelines can retain an explicit `camera.orbit` recipe:

```json
{"target":[0,1.1,0],"radius":5.2,"startAzimuthDegrees":25,
 "endAzimuthDegrees":385,"startElevationDegrees":0,"endElevationDegrees":0}
```

The camera samples analytically around the target rather than interpolating
XYZ chords. Azimuth0 is world+Z and90 is+X, not a guarantee of the actor's facing.
Elevation is above/below target in Y-up space. Radius remains constant, the up
basis remains valid at both poles, and negative/multiple turns retain authored
direction. This opt-in camera clock includes the first and last selected angles;
legacy cameras, actor clocks, held poses, source hashes and geometry stay intact.
Equal start/end angles hold an exact view. Shots retain their authored duration.

Camera mode must be `orbit`, shake0, and Cartesian keys absent. Set replaces XYZ
keys explicitly. Clear before choosing Cartesian/reference paths. Existing scenes
that lock their authored camera still reject orbit edits. Finite strict inputs:
target axes±9900m, radius0.5..30m, azimuth±3600°, elevation±90°.

## Shared controls

Manual: **3D Studio > Exact camera angles**, target/radius and start/end angles,
Apply/Clear; worker preflight uses existing immutable draft/CAS save operations.
Settings survive Activity recreation. Invalid edits retain the previous draft.

MCP uses existing `cinematic_3d_edit_program` with `set_camera_orbit` or
`clear_camera_orbit`. Each edit retains project revision and exact program SHA.
`cinematic_3d_get_program` accepts optional `sampleFrame` only with the exact
`expectedProgramSha256`; returned `cameraSample` is read-only, source-verified,
explicitly unrendered and not drawing-quality certified. Authored-camera scenes
reject timeline-camera reads rather than claiming those are their rendered view.

Two closed aliases let a compatible native4.10.3 app receive these shapes via
older future-compatible `app_execute` relays without a Worker deployment:
`studio_cinematic_3d_edit_program_v4103` and
`studio_cinematic_3d_get_program_v4103`. They canonicalize to the two existing
handlers, keep the native worker lane, and add no canonical actions. Node strict
validation/version floors agree with native capabilities. No arbitrary action
prefix is admitted. Omitted features retain previous version floors.

## Verification and limits

Focused native angle, storage and manual UI checks and focused Node contracts
passed during development. Fresh integrated results are recorded below when done.
Tests cover requested25/30/40/45/60/65/70/75 and through360°, both poles,
negative turns, constant radius, exact closure, source/pose preservation,
read-only source-bound sampling, CAS/reopen, strict admission and cancellation.
Assertions/frame samples are not additional animation scenarios.

The initial failing native and Node logs are preserved privately under
`/workspace/work/native-angle-structure`; the initial native failure XML was
not retained before the next test run overwrote it. The saved first-green XML
is named as such. Do not relabel it as failure evidence.

No new character drawings, native GPU replay, phone execution,360-degree neural
reconstruction or cinematic/artistic acceptance are established by these tests.
The existing Python producer currently rejects the new orbit field strictly;
this change targets native Android playback/editing, not that external producer.
Camera orbit requires actual authored3D geometry, which the supplied single-view
manga reference does not provide. It cannot rotate a flat drawing into a correct
rear view or recover the artist's original layers.

GitHub CLI began returningHTTP401 after the signed4.10.2 candidate and native
screenshot artifacts were delivered. The separately authenticated GitHub connector
still works; use verified tree/parent identity when preserving this branch.
Exact workflow_dispatch is unavailable through the current connector tools.
No main merge, Worker deployment, stable release, new signer, phone install,
app data reset or MCP identity reset is authorized by this technical slice.

Frozen4.10.2 candidate (not4.10.3): run39545111418/artifact12321389806,
package com.rezoxnemesis.videostudio.next, code505,
APK SHA256f6219978a2bdbe8f7133e0eef3e671a8cbb1139a5786a3808be86f67e9ff0c18,
original signer7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd.
Its real native AI screenshots and editable raster-guide studies are in
run39546405036/artifact12321309644. These remain artistically NEEDS_REWORK.

Fresh local integrated run:2210 Android tests,0failures,0errors,16skips,
388suites. Fresh Node run:698tests/0failures plus306smoke checks.
Two additional locked-camera/scene-clock tests were then added. The scene-clock
case exposed an overly exact floating-angle assertion (13 represented as
12.999999999999998); its new interior-angle tolerance is1e-12 degrees. Actor
clocks and source equality remain bit/string exact; endpoint closure stays exact.
This is not the unrelated historical alpha-pixel regression, which is untouched.
The failure XML/logs are retained in final-focused-red-results/final-focused.log.
The final focused run passed:40tests/0failures/0errors,
including both additions, UI recreation/invalid editing and closed native aliases.
No creative scenarios or final character views were produced by those tests.
