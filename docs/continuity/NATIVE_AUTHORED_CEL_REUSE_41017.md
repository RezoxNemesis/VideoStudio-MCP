# Reuse authored drawing cels inside the protected layered workflow

The persistent layer-by-layer workflow previously rejected `duplicate_cel`, even
when its source consisted entirely of the user's own pen or native paint marks.
The existing **Frames / Cels > Copy → next** control therefore could not support
the normal animation workflow of retaining an outline and changing the next cel.

Native 4.10.17 admits that existing operation for protected authored content. It
copies one sampled drawing to an independent cel on the same active stage layer.
The source guide remains hidden and locked. This reuses an authored drawing; it
does not redraw a reference, recover the original artist's layers, invent an
unseen viewpoint or certify anatomy, motion, physical contact or artistic quality.

## Manual and ordinary ChatGPT use

1. Choose the complete shot clock before beginning the layered workflow. The
   reference-only source and its dimensions/FPS/frame count remain frozen.
2. Author marks on the current construction, refinement, detail or named clean
   part layer using the native pen or paint tools.
3. Select the source frame and use **More > Frames / Cels > Copy → next**. The
   destination must already be inside the frozen clock. Existing destination
   contents on that selected layer follow the normal cel replacement behavior.
4. Edit the independent new cel with pen, paint or erase. Its inherited immutable
   paint snapshot and stroke identities remain available; use a new stroke ID
   for each new gesture. Other cels retain their source geometry and pixels.
5. Inspect the result with the existing native preview and onion skin controls.
   Changed stage contents invalidate their earlier accepted review. Normal Undo,
   Redo and editable archive restoration preserve the operation.

Ordinary ChatGPT uses `app_status`, requires native 4.10.17 for this protected
workflow, and reads `sketch_capabilities.layeredDrawing.authoredCelReuse`. The
request is the existing `sketch_edit`, with one operation and no new flags:

```json
{
  "sketchId": "owned",
  "expectedRevision": 7,
  "operationId": "copy_owned",
  "operations": [{
    "type": "duplicate_cel",
    "layerId": "actor_construction",
    "fromFrame": 0,
    "toFrame": 1,
    "idPrefix": "next"
  }]
}
```

Native and Node capability descriptions agree. General legacy duplicate requests,
their 3.9.1 version floor, request normalization and opaque archive action floors
are unchanged. The relay cannot infer protected drawing state from a duplicate
request alone; admission belongs to the native source-bound worker. No new
canonical action or hosted model is needed. Live connector and installed-device
execution require their own evidence.

## Admission and persistence

- Only the same active, visible, unlocked authored stage layer is eligible.
  Layer opacity and sampled opacity at both endpoints must be positive. Source
  and destination layer transforms must agree; the copy does not silently move
  a drawing through a different animated layer transform.
- The destination stays inside the frozen drawing clock. This slice neither
  extends the shot nor inserts frames. Use a separately planned bounded shot.
- Every copied object must be an allowed source-free authored stroke, eraser,
  unfilled path or native paint cel. Images, source references, linked masters,
  procedural actors and other shortcut content remain rejected.
- Before publication, the native worker verifies every distinct copied paint
  snapshot's complete bounded ancestry with the existing native verifier. It
  must start with an empty native snapshot and contain only paint/fill/erase
  history. A clone descendant rejects even if its latest stroke is source-free.
  Mesh snapshots retain that same ancestry requirement.
- A protected copy has its own revision-bound edit. Mixed batches reject before
  mutation. Exact committed retries recover their receipt before current-stage
  admission; replaying an old completed request after stage advancement does not
  perform a new copy. Changed input under an existing operation ID still rejects.
- Sampling preserves native path and paint-mesh poses, then clears the copied
  pose keys. Immutable paint bytes remain owned and unchanged. Subsequent paint
  edits publish new snapshots instead of overwriting the source.
- Protected copies use `idPrefix_tail_<sourceIndex>` for surviving destination
  exposure tails. This makes actual publication and the workflow's replay match.
  ID collisions reject atomically. Legacy unprotected copies retain their prior
  UUID behavior.
- The manual protected copy uses the existing prepared worker save path, avoiding
  UI-thread ancestry reads and display of an unverified optimistic copied draft.

The existing document, key, point, object, source and storage budgets are retained.
No source assets, projects, earlier studies or failed generations are deleted.

## Verification scope

Root executed the genuine pre-fix native suite on retained full 4.10.16/code519:
six tests, five failures. The outline, paint and original-session copies were
rejected; the ordinary manual menu did not persist a new cel. The source guard
case passed. The initial UI test had a missing-import compilation fixture failure;
that generation is preserved separately and is not counted as behavioral RED.

After the focused repair, root executed those same six native assertions with no
changes: six passes in 4.828 seconds, using retained full519 plus six production
class overrides. These are focused checks, not a full candidate520 build or
artistic acceptance. Exact private source/log/launcher generations are retained
outside Git under `native-authored-cel-reuse-20261022`.

Separate boundary tests cover stage-shift receipt recovery, deterministic tails,
ID collisions, transformed/transparent source layers, mixed edits, frozen mesh
poses and archive pixels, cancellation/retry and deep clone ancestry. Root then
executed the broader focused suite: 49 tests, zero failures/errors/skips in
36.697 seconds, comprising 12 new native tests and 37 existing tests. An earlier
broader run exposed one old missing-field exception regression; its exact failure
is preserved, and the unchanged existing test passes after the structural guard.
The actual manual editor screenshot is technical parity evidence, not actor art.

Fresh full Node validation passed 803 checks plus 306 smoke checks. Its first
new contract fixture compared input property order with the parser's established
normalized order; the failure is preserved. The corrected fixture pins exact
pre-feature519 normalized bytes independently observed from the unchanged519
parser, retaining the strict byte assertion. All native RED assertions remain
unchanged after the separately documented missing-import fixture correction.

These results do not establish full candidate520 integration, original-signature
CI, installed APK, live connector execution or artistic acceptance. Root owns
those remaining checks. The detailed all-angle actor and 240-second film are
unfinished.
