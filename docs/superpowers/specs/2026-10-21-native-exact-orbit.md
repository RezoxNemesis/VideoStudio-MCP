# Exact degree camera orbits for existing native3D scenes

The owner requires structure-consistent angles; current single-image AI studies
fail likeness and are not a360-degree model. This slice fixes actual camera
control, not character reconstruction or drawing quality. Hidden geometry must
exist in an authored scene. Do not use proxy camera math as artistic acceptance.

Add optional strict camera.orbit to the existing timeline, requiring mode orbit,
zero shake and no Cartesian keyframes. Fields: target[3] in Y-up metres, radius
0.5..30m, start/end azimuth degrees -3600..3600 and start/end elevation -90..90.
Azimuth0 is+Z,90 is+X; elevation positive is above the subject. Camera position
and up follow the same analytic spherical basis. Interpolate unwrapped angles,
not XYZ chords. For this explicit feature only, camera phase frame/(N-1) includes
both selected endpoint angles; preserve the existing actor clock and all legacy
sampling exactly. Normalize only trigonometry so0 and360 close exactly.

Use existing cinematic_3d_edit_program operations set_camera_orbit and
clear_camera_orbit, normal immutable revisions/CAS and unchanged actor/source
proof. Other camera paths require explicitly clearing the orbit. Add a worker
validated native manual degree form and retain settings on Activity recreation.
Camera edits remain blocked on authored-camera-locked scenes.

Add optional source-bound sampleFrame on cinematic_3d_get_program, requiring the
exact expectedProgramSha256. Return actual camera and source/shot metadata without
rendering or editing. Reject editOnly sampling. No new canonical actions. Two closed versioned aliases to the same edit/get
operations let the existing future-compatible app_execute bridge carry new
parameters through older relays. They do not permit arbitrary nested actions.

Native/Node capabilities and strict schemas agree; explicit new orbit fields,
operations or sampled reads require4.10.3. Omission preserves older feature floors
and opaque import floors. Bound validation, cancellation and every output frame.
Test requested25/30/40/45/60/65/70/75 and through360, negative turns, poles, radius,
endpoints, old clocks, strict admission, source/pose immutability, CAS/save/reopen,
manual controls and normal MCP contracts. Run one local Gradle. Full art, Android
GPU replay and phone execution remain unverified until independently exercised.
