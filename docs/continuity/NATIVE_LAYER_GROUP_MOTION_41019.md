# Native shared layer motion, 4.10.19 candidate

Feature branch `feat/native-layer-group-motion-v41019` starts verified521
`3cb74cf01a638187e29b4c29637ac02616460310`. Root owns tentative4.10.19/code522
metadata, integrated Android/full CI and original signing. Core implementation
is frozen after root's focused17-case native PASS; manual/Node integration and
full522 gates are separate outcomes, recorded after their actual execution.

## Concrete behavior

The already-held older source-assisted hand/banana study required480ordinary
layer transform keys in8public edit requests to preserve registration across
five differently anchored layers. Reusing identical rotation/translation fields
at their different pivots would drift. Existing independent Sketch transforms
edited one target; camera/rig workflows were separate controls.

One new operation under existing `sketch_edit` now performs that native planning:

```json
{
  "type":"set_layer_group_motion",
  "layerIds":["rear_hand","banana","front_digits"],
  "pivot":[0.52734375,0.546875],
  "poses":[
    {"frame":0,"x":0,"y":0,"rotation":0,"interpolation":"hold"},
    {"frame":12,"x":0,"y":0,"rotation":0,"interpolation":"smooth"},
    {"frame":36,"x":0.01171875,"y":-0.04296875,"rotation":-3,"interpolation":"hold"},
    {"frame":60,"x":0.01171875,"y":-0.04296875,"rotation":-3,"interpolation":"smooth"},
    {"frame":84,"x":0,"y":0,"rotation":0,"interpolation":"hold"},
    {"frame":95,"x":0,"y":0,"rotation":0,"interpolation":"hold"}
  ]
}
```

Pivot is normalized canvas world0..1; x/y are normalized world DELTAS in−4..4,
rotation unwrapped degrees in−3600..3600. They are not absolute stored per-layer
channels. Select2..32 distinct visible unlocked nonzero-layer-opacity layers.
Whole current shot only,1..240frames,1..32strictly ordered poses: first0 and last
frameCount−1, one pose only for a one-frame shot. Existing native outgoing timing
supports linear, hold, smooth, ease_in, ease_out, ease_in_out and Bezier curve.
Omitted interpolation stays omitted in request normalization and defaults natively
to linear. There is no referenceFrame, partial interval, group scale or shear.

`SketchLayerGroupMotion.apply(document,operation)` is the shared native planner.
It detaches every selected layer's original `transformAt(layer,frame)` BEFORE
modifying any track, then composes common rigid world matrix G over source B:
`M_i(frame)=G(frame)·B_i(frame)`. Document width/height convert normalized deltas
to pixel coordinates; distinct original anchors are compensated explicitly.
Each source frame retains its own animated anchor, scale and opacity. Original
base transform, object transforms/keys, art/style, source PNG/paint descriptors,
provenance, canvas and shot clock remain unchanged. Layer key tracks become
ordinary dense integer-frame HOLD keys; sparse outgoing timing is resolved once.

Identity group samples copy the original sampled transform directly for exact
neutral RGBA. Only sampled composition angles are reduced modulo360; authored
pose endpoints stay unwrapped. Thus a0→360degree track still spins in between and
returns to exact neutral native pixels. Another new operation composes over the
current source animation; ordinary Undo restores previous tracks.

The complete candidate is preflighted before any selected-track assignment.
Actual key accounting includes untouched layer/object/shape/paint-mesh/retained
hand/human pose keys plus selectedLayers×frameCount, subtracting replaced selected
old layer keys. Existing4096keys,4MiB JSON and transform bounds are unchanged.
Invalid ranges, budgets, cancellation or any selected layer failure reject the
whole operation. Valid input deltas may still overflow a composed stored
transform; those reject rather than clamping, reanchoring or dropping frames.

## Existing ownership and persistence protections

Native store CAS, canonical request fingerprints, immutable receipt replay,
history and one ordinary Undo step remain authoritative. Live events retain the
compact operation; native replay recomputes from the prior immutable source
snapshot. No thousands-of-operations event or raised512KiB/500KiB ceiling is used.
The saved document contains ordinary layer keys, with no persisted group state.

Every selected protected-workflow layer must belong to the current active stage.
Mixed active/guide/prior-stage groups reject. Frozen source/reference/clock and
original-drawing policy stay authoritative, with normal stage review invalidation.
Authored keyed opacity zero is preserved; only hidden/locked/zero layer-opacity
selection is rejected. A book uses the page sketch's local frame clock, not book
index or exposure. No pages/exposures/cues are extended by this operation.

New request type requires4.10.19; old transforms/keys retain old omission,
fingerprints and floors. `sharedLayerMotion` capability is a shared literal
advertising that native floor and scope. No new canonical action/alias, document
schema, archive field or archive floor was introduced. Correction proposal
operations remain a separate unchanged allowlist and do not admit this operation
in v1. Manual and MCP must call the same native planner, not client-side key math.

## Manual workflow

Open **More → Frames / Cels → Group motion** in Sketch Studio. The **Move layers
together** dialog shows the current drawing's visible, unlocked, nonzero-opacity
layers. Choose at least two parts, then enter their shared pivot in canvas pixels.
This works with the existing separate part layers; it does not require replacing
their artwork or setting up a rig.

The initial first/last poses have zero motion. Select a pose and enter its
one-based shot frame, X/Y displacement in pixels and rotation in degrees. Choose
the outgoing easing, including four explicit0..1 control values for Bezier.
**Add / update pose** inserts or updates that frame; **Remove pose** removes an
interior pose. The first and last poses must remain. The whole shot stays within
its existing1..240-frame clock, with at most32 authored poses.

Choose a preview frame and press **Preview group motion**. The dialog passes the
same compact native operation to `SketchLayerGroupMotion.apply` on its worker,
then uses the existing native renderer for a bounded640-edge preview. It does
not decode images or plan per-frame tracks on the UI thread. Editing a meaningful
setting invalidates the displayed preview. **Close** cancels preview work and
discards this unsaved recipe without changing the drawing.

**Apply group motion** queues one ordinary prepared editor intent. It does not
optimistically mutate the source document on the UI thread. The originating
session, source revision and navigation generation must still match; otherwise
the draft stays open with an explanation. The native writer then performs normal
CAS, preflight, policy, atomic publication and receipt handling. One normal Undo
restores the previous tracks. The established editor failure/recovery/retry
controls retain a failed intent; closing a dialog never deletes a queued save.
Navigating to another drawing or destroying its activity closes the old dialog
and its read-only preview worker.

Manual UI regression evidence first had a fixture failure:200×100 was below the
native256-pixel canvas minimum. That exact generation is preserved separately.
Only fixture dimensions/pixel units changed; the normalized-motion assertions
remained unchanged. Root's genuine RED retake then executed three tests, all
failing at the absent **Group motion** menu in3.972s. The same three assertions
passed with the implementation as part of a20-test scoped native run in7.679s
(17 core/boundary plus3 UI). Those UI tests exercise actual menus, numeric fields,
read-only native preview/Close, source-revision rejection, prepared-writer queuing
and ordinary Undo. The passing UI source gained an optional screenshot capture
only; its original three assertions remain unchanged. A capture, when emitted,
shows the synthetic two-mark manual fixture under native Robolectric graphics,
not actor artwork or installed-device execution.

Exact RED, fixture failure and GREEN source/log/launcher snapshots are retained
outside Git under `native-layer-group-motion-20261022`.

## Executed evidence and honest limits

Private evidence root:
`/workspace/work/film-production-20261022`.

1. Root genuine native RED on retained FULL521:14tests,10FAIL, each positive case
   reaches missing `set_layer_group_motion`. Original test SHA256
   `971203f06b4660ad0cb596d4b51e413a3b6685eb03371543dbc020d1e6be7a72`.
   Exact source/log/launcher/runtime proof: `native-layer-group-motion-red-v1-frozen`.
2. Root first native implementation pass:17tests,2FAIL,4.475s. Full source/log/
   launcher preserved at `native-layer-group-motion-core-green-v1-frozen`.
   The3900-key fixture attempted one>512KiB setup request; a semantic JSON test
   compared transient Double1.0/12.0 with native wire Integer1/12. These were
   fixture failures exposed after unsupported-operation admission was repaired.
3. Only test setup batching and test-only wire-roundtrip comparison changed.
   Five revision-bound four-object add batches retain20×195=3900existing keys;
   no limit changed. Production canonicalization/fingerprints were untouched.
   No assertions, actual native pixel comparisons or tolerances were weakened.
   The current14-case file is therefore no longer byte-identical to historical
   RED; exact differences/hashes are in `Fixture-Corrections-v2.json`.
4. Root retake PASS17tests/0failures/errors in5.03s against retained FULL521 plus
   four exact production overrides. Current core test SHA256
   `1e2df3e462606c0416b461dfc370b92c7b7724e6aac7c19973b6e09d361e2460`.
   Additional three boundary tests retain SHA256
   `728cffd3b5634a1774f495c88d3224a12ca883f795e02876ef1d95736baf01d2`.

Focused tests exercise all96native synthetic contact-coverage frames, distinct
anchors/scales/rotations on a nonsquare canvas, exact neutral/returned and
all-zero-over-original-animation RGBA, animated source anchor/scale/opacity,
actual object-key budget, direct preflight nonmutation, strict shape/clock/locks,
workflow/book admission, CAS/receipts/Undo/Redo, portable ordinary keys/native
pixels, legacy single-layer key omission, full360spin, one-frame boundary and
overflow/cancellation rollback. This is scoped native source verification, not
a full integrated522build, CI, APK, phone or live hosted-MCP execution claim.

The actual older hand remains source-assisted; shared rigid placement does not
articulate fingers, author a pickup, infer hidden anatomy, solve collision/forces,
certify grip quality or complete the film. A fresh actual-input public-operation
retake and visual review must be recorded separately, preserving all earlier
failed/static sources and the original contact gates/tolerances. Root owns every
native/JUnit/Gradle/model/ADB lane and original signer; no main merge, Worker
deployment, stable publication or data/device/MCP-identity reset is authorized.

Root's broader native run passed122tests/0failures/errors in17.731s, including
the20new tests and22existing classes covering protected drawing, live events,
books, archives, renderer caches, cel reuse and editable-import lifecycle. The
optional screenshot is the actual synthetic native dialog, not character art.

The actual held-prop retake passed1test/26.51s on FULL521 plus4production
overrides. One public group operation and one Undo step reproduce all96earlier
native PNGs pixel exactly; the earlier planner used480keys in8edits. Source
objects/references, replay, portable archive and the existing contact-coverage
checks pass unchanged. This simplifies an existing study, adding no creative
scenario or accepted film seconds. The older man's borrowed hand remains
source-assisted, coarse and visibly cropped; final visual status NEEDS_REWORK.

CI521 run39748302796 retained a full2438/0failures/0errors/27skips native
report and BUILD SUCCESSFUL, but its20minute job was cancelled before APK
signing verification/upload. No signed521candidate was downloaded or verified.
The next candidate increases that job allowance to45minutes without removing
checks, changing signing inputs or enabling publication.
