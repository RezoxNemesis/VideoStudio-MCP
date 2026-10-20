# Native authored deformation, 4.9.5/code498

Branch `feat/native-authored-deformation-v495` starts from signed4.9.4
84f587cb87cc35329737ab390728ab7ba27302c4. This slice adds native owned paint
mesh preparation/editing, retained pen/paint regional bindings, source-alpha
selection and same-layer depth editing. See LAYERED_DRAWING_WORKFLOW.md for
shared manual and ordinary MCP steps and exact limits.

Actual private profile drawing and elbow-bend execution exposed additional
failures missed by small fixtures. The576×1280 source exceeded the viewport's
1024px limit; worker-only renderSource now retains exact source dimensions while
viewport limits stay unchanged. A45-degree bend with a narrow110px influence
region correctly rejected an inverted triangle; a broader retained recipe passes.
The guard remains intact. Native pencil stroke expansion then exposed generated
opacity1.0 versus persisted1 causing SKETCH_ANIMATION_PUBLICATION_CHANGED. The
compiler now serializes generated output before publication hashing; source,
program and legacy request fingerprints are unchanged. Public regional pencil
save/bake/exact-retry and tall-source regression tests cover these failures.

Other retained failed evidence includes cached regular-grid RGBA neutrality
(pixel275,436, expected0x1255559c versus0x115a5aa5), sparse Multiply erasing empty/
separated artwork, and a late-cancellation hook lost by copying unrelated edit
arguments. The fixed paths preserve exact RGBA, API29+source-over Multiply and
original unrelated request objects. Failed outputs and JUnit remain outside Git.
The older isolated IMAGE mirror/trim0x24→0x25 regression remains unresolved.

Private evidence lives at /workspace/work/native-authored-scene/generations.
profile-owned-construction-v2, profile-owned-refinement-v1r3 and
profile-clean-parts-v1r2 are successive actual app drawing passes, not scenarios.
profile-arm-bend-v1..v6 retain failures and rerenders of ONE elbow-bend scenario.
Only native Canvas under Robolectric executed. No phone/GPU/Android codec or
voice playback is established. FFmpeg may package the unchanged native PNGs.
All artwork pixels come from app-owned paint/pen. The original manga is a locked
hidden guide; film output excludes it and has zero imported image objects.
Unseen profile surfaces are authored interpretations, not extracted hidden views.

The240-second action film, accepted detailed likeness, full view rotation and
expressive voice tab remain unfinished. Voice comes last. Do not treat passing
engineering checks, repeated renders or frame counts as artistic acceptance.
No Worker deployment, main merge or stable publication belongs to this slice.
Full integrated validation and exact candidate signing evidence will be recorded
in external evidence after actual execution; source status alone is not PASS.

Fresh local integrated validation:2125 Android tests,0 failures/errors,13 skips;
671 Node tests and306 smoke checks pass. Exact source/signing/CI evidence is
pending. The opt-in private runner also preserves an owned pre-animation source
archive and the native rig program alongside baked editable keys.
