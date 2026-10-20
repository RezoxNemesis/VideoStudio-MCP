# Native posed contact targets — 4.9.9 / code502

Continuation of verified4.9.8/code501, source0a6359d524c5935c90f294b5b794c1b1645a9e4f.
The240-second authored film remains unfinished. The expressive voice tab remains last.

## Problem and change

A manual or ordinary ChatGPT author could configure the existing native two-bone
IK solver, but could not obtain a saved pose's exact pre-camera joint position
through the ordinary read surface. Estimating it from a screenshot makes prop and
hand placement harder and confuses display coordinates with source coordinates.

`sketch_animation_get` now optionally accepts `expectedRevision` and `sampleFrame`.
Sampling requires both; explicit new options require native4.9.9. Omission keeps
the old4.4.0 request and response unchanged. The saved frozen source and program
are verified before sampling. A stale saved revision fails. The response retains
sourceSha256/programSha256 and adds `poseSample`: frame, joints and contactErrors.
Joint x/y are normalized source-canvas coordinates before camera; displayX/Y
include the authored camera. It returns no source document and performs no edit.
Sampling runs on a worker, with cancellation checks before and after compilation.

Example: get `{animationId:"reach",expectedRevision:1,sampleFrame:42}`, choose the
returned target joint's x/y, then save an ordinary rig `ik` entry using that fixed
point. Existing hip/knee/ankle JSON names remain compatible and represent a direct
root/bend/contact chain. No new canonical action, transport or solver is added.

## Manual parity

Animation > Pin contact > Use posed joint target reads the current draft with the
same native helper. Choose any rig/joint and the first contact frame. The worker
copies the pre-camera target into editable X/Y fields; Add contact remains the
ordinary draft edit. Manual coordinates remain available. Sampling does not save,
bake or mutate the drawing. Cancellation, lifecycle pause, changed draft, frame,
joint or manually edited coordinates must not admit a late overwrite. This target
stays fixed until edited or sampled again; it does not follow a moving prop.

## Evidence and limits

Private native generations and failed results remain outside Git under
/workspace/work/native-authored-scene. The initial UI compile failed because two
existing helper methods were accidentally omitted during the contact-form edit;
they were restored. The next native UI run exposed a queued-result/Cancel race.
Immediate Cancel invalidation repairs it. A test fixture's program injection also
needed the existing normal picker-refresh step; its exact joint assertions remain.
The preserved RED and fresh GREEN runs are distinct; integrated checks are pending
until recorded from actual outputs. The native private study driver can record
ordinary revision-bound pose reads while rendering unchanged native frames.

Joint coincidence is not drawn-surface overlap, force simulation, natural acting,
likeness, hidden-view reconstruction, phone execution or artistic acceptance.
Authored2D pan/zoom/roll is not a genuine camera orbit around an unchanged3D actor.
No Worker deployment, main merge, stable publication, alternate signer, Gallery,
external art renderer or device reset is part of this change.

## Actual-study IK easing regression

The two-hand banana study exposed correct final contact but unacceptable ease
frames: a wrapped -326.207-degree upper solution was blended from0, rather than
its equivalent33.793-degree reach. Frames36/64 swing the arm across the face and
offscreen. Those PNGs, video, archives, requests and failed tests are retained.
Three of four focused native angle regressions fail before the solver repair.
The solver now chooses equivalent root/bend local angles nearest the authored
local pose before blending. Authored multiple-turn poses remain the baseline;
explicit contact world rotation is retained. Hard contact, constant bone length,
unreachable-target rejection and existing bounds remain. This repairs rotation
wrapping, not anatomical joint limits, collision forces or artistic quality.

The first integrated run was deliberately terminated after this separate visual
failure was found. It is not a full-suite success; final validation must run on
the repaired combined source. The initial study also exceeded4096 keys at the
old0.25px default. A preserved v2 used explicit supported0.75px simplification,
without increasing limits. Rerender the repaired solver with the default before
choosing any further authored tolerance change.

## Contact-ease manual/MCP parity repair

Actual native study requests revealed that Node omitted blendInFrames and
blendOutFrames although native introduced these in f055246 under4.5.1. Add strict
optional integer0..12 fields, reject ranges outside the shot, and gate explicit
fields (including0) at the existing4.5.1 native floor. Omission keeps4.4.0.
The manual Pin contact form now offers Approach/Release frames with the same
bounds and shot-clock rejection, preserving zero defaults by omitting the fields.
Two new native UI tests and one Node contract test fail before their respective
repairs. Actual generated study requests are also checked through ordinary Node
schemas, rather than inferring parity from isolated assertions. No live Worker
admission or deployed ChatGPT host execution is claimed. The second broad native
run was deliberately terminated to finish these controls before final validation;
it is not reported as a passing suite.

## Independent review and local checkpoint

Independent read-only review found a deferred OnDismiss window for Back/outside
cancellation. Two new native UI cases reproduced late coordinate overwrite;
callback admission now also requires the owner dialog to remain showing. The
reviewer confirmed the fix; pause, joint change and source replacement cases
also retain manual fields. All failed evidence is preserved outside Git.

Fresh local full pre-final-cancel suite:2161 tests/0failures/0errors/14skips,
378 XML files. Its exact source hashes and untouched copied XML are retained;
it is not promoted to final-source verification. Final affected six-class run:
54 tests/0failures/0errors/0skips after the visibility fix. Fresh Node682
tests plus306 smoke checks pass. Final pushed-revision full CI, original-signer
APK download and independent package/version/certificate/hash checks are pending.

Art checkpoint: own two-hand source and4s/96-frame contact study exist, with
NEEDS_REWORK status. Authored staged approach improves framing, but the far hand
is still pre-pinched and elbow/cuff/likeness detail is unfinished. Editable fruit
and peel source exists separately; actual peeling, the240s film and voices are
not completed. No additional creative scenarios are claimed for these revisions.
