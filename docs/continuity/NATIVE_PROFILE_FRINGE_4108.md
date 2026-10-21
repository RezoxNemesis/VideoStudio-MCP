# Native profile fringe depth repair — 2026-10-21

This isolated slice extends signed candidate4.10.7/code510 source76fb9a0.
Integration into4.10.8 remains pending. It changes the existing native anatomy
renderer used by manual and MCP human controls; it adds no new wire fields.

Actual profile preparation for the young-man local drawing study exposed a
blank swept forehead. SketchAnatomyHead gated every forelock on a frontal
camera normal before applying its existing per-lock side visibility predicate.
The first regression failed on the left profile, with the original inputs and
JUnit retained privately. Removing only the outer gate passed the first test,
but actual SDK33 native Canvas screenshots exposed thin/floating strips: the
forelocks were almost constant-depth planes.

A second geometric regression failed on that attempted repair. Forelock control
points now follow an ellipsoidal cranial depth envelope while retaining their
existing front-plane coordinates and per-lock visibility. Both side views retain
solid locks; the exact rear view hides them. Native recorded pre-repair front
and back full-frame pixels compare exactly after this depth change. High/low
projection legitimately changes with the new depth.

Fresh focused validation:15tests,0failures,0errors,0skips, including8view public
native authoring/render/archive/reopen studies. These are contracts and one
static guide study across views, not15actions or accepted character views. All
failed attempts, native screenshots, source diff, private driver and JUnit are
outside Git at /workspace/work/native-character-fringe-20261021.

The generic construction still does not match the target young man's bangs,
features, clothing or pencil detail. Its narrow face/neck and style remain
NEEDS_REWORK. Fixed-crop high/low preview framing misses some of the head; full
native frames are preserved, so those closeups are not evidence of acceptance.
This is app Java/native Canvas execution under Robolectric, not phone, installed
APK, GPU or codec execution. No hosted artwork or external drawing substitute.
No full combined4.10.8 validation or signed4.10.8 APK is claimed here.
