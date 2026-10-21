# Native articulated hand study

Continue verified 4.7.8 source c3d422c without replacing earlier character work.
Owner requests visual and animation targets before implementation, systematic
native render/inspect/repair/retest, reusable manual/MCP controls and all views.

## Acceptance targets

Visual: an enlarged anatomically readable hand/forearm, five distinct digits,
tapered phalanges, knuckle volumes, thumb base, palm pads, restrained skin shading
and nails on dorsal surfaces. Front/rear/left/right/high/low must show real
foreshortening and hidden-surface occlusion. No mitten silhouettes, detached
joints or uniform tubes accepted. This is an anatomy study, not a claim of
photorealistic skin, painterly reference quality or a genuine3D renderer.

Animation: six-second relaxed/preparation/curl/staggered unfurl/open hold/close/
relaxed finish. Fixed bone lengths, continuous joints, coordinated thumb, small
wrist preparation. Inspect in-betweens and motion, not only endpoint geometry.
Technical PASS cannot override visual NEEDS_REWORK. Keep every generation.

## Product design

Use native numeric joint chains in 3D, native surface projection/occlusion and
Android Canvas paths baked to editable 2D cels. No external renderer or reference
pixels. A continuous tapered finger surface follows each three-bone chain;
thumb has independent basal opposition and two bending joints. Native shading,
creases and dorsal nails support readable anatomy. This is projected2D output,
not an Android GPU3D pass. No new dependencies.

Operation add_hand_animation(layerId, handId max20, settings, optional poses).
Settings: view front/left/right/back/high/low; handedness right/left;
detail anatomical/contour; skinColor #FFD6A078; nailColor #FFE5C1AC;
centerX .5, centerY .55 each .2.. .8; size 1 (.4..1.2);
motion 1 (0..1); wristMotion .6 (0..1).
Custom poses: first frame0, strictly increasing, 1..32 poses, optional native
easing/curve. Channels indexCurl/middleCurl/ringCurl/littleCurl/thumbCurl/
thumbOpposition/spread 0..1; wristFlex/wristTurn -25..25 degrees. Omitted
channels inherit neutral defaults, not the preceding pose. Constructor exposes
static defaults/settings/preset(frames,fps,settings)/validatePoses/poseAt helpers.

4..8seconds,24..240frames,24/30/60FPS; preserve exact timing/resolution and all
4MiB/4096object/131072point/4096key limits. Identical adjacent frames share
exposure, never claim unique drawings. Invalid input, locked/duplicate identity,
layer-transformed bounds and budget failures reject atomically. Cancellation
checked during construction. Uniform fit supports landscape/portrait/square.

Manual: Brush/Text > Hand animation, all settings/pose channels, native async
preview and create on new layer through existing SketchConstructionSubmit and
CAS/lifecycle protection (construction kind8). Shared constructor is the source
for preview/manual/MCP. Native capability handAnimation and strict Node schema
minimum4.8.0. App version4.8.0/code483; preserve original signing cache.

## Evidence

Focused RED/GREEN regressions for dispatch, anatomy/lengths/independent digits,
view occlusion, custom poses, validation/atomicity/budgets and UI/native parity.
Six native180frame30FPS1280x720 studies, plus a60FPS bounded shot and changed
hand/skin/control variations. Exact source/diff, request, renderer, hashes,
archive restoration, screenshots, reviewMP4 and honest assessment. Pure hand
mechanics are intentionally silent; retain previous genuine speech studies,
then compose hand acting with audio in a later focused study after this target.
Full Node/native CI checks and independently verified original-signer candidate;
no merge/deploy/stable, phone/liveMCP/native encoder claims without evidence.
