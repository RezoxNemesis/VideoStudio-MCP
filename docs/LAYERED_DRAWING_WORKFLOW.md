# Layer-by-layer authored drawing

Current work starts from installed4.9.1/code494, source a1a1176, preserving the
unfinished4.8.8 work in its separate worktree. Target4.9.2/code495.

The owner rejects both copied source cutouts and simplified filled-vector
approximations as the result of a hand-drawn layered-sketch request. The supplied
storyboard defines the process: locked reference, construction strokes, refined
features/proportions, clothing/hair/detail, clean linework, separated editable
parts. Software must not equate stage counts or tests with artistic acceptance.

## Product changes

Existing sketch_edit gains four strict operations: layered_drawing_begin,
layered_drawing_stage, layered_drawing_review, layered_drawing_finish. A persistent
layeredDrawing record protects source identity and guide isolation independently
of temporary live sessions. Native touch and ordinary native commands share the
same transaction/history/receipt implementation. Reference artwork remains hidden
and locked in exported artwork; a separate source/comparison preview supports
inspection without placing copied pixels in the drawing.

Stages: construction, refinement, details, clean_line, separation. Advancing
requires an explicit ACCEPTABLE review of the current authored content; changes
invalidate matching review hashes. Review is an observer decision, not an
automatic certificate. Notes, exact revision and content identity are retained.
Construction/refinement/detail layers and named clean part layers remain editable.

The protected mode admits authored pen strokes, unfilled precision contours and
native paint/erase strokes. It refuses reference cutouts, clone sampling, image
tracing, filled geometric substitutes, procedural figure generators and replacing
the guide. No operation invents missing anatomy or original artist stroke order.

Native sketch_layered_drawing_preview returns actual reference/artwork/comparison
PNGs bound to the source revision and source hash. The manual drawing view can
show the reference alongside the canvas. UI decoding and raster work stay on
workers. The precision_contour native operation gains its missing strict public
MCP schema; no external drawing renderer is introduced.

## Verification and limits

Tests must exercise copied-pixel/generator/clone rejection, locked guide identity,
actual source-free native drawing pixels, reopen, normal Undo/Redo, archives,
stale review rejection, edit invalidation, stage progression and manual/MCP
parity. Test the actual supplied scene separately; preserve failed generations.
Matching its detailed drawing quality remains an image-and-motion review task.

One local Gradle process; full Android/Node/MCP checks and original-signer CI
precede candidate delivery. Preserve all projects and application identity; do
not merge main, deploy the Worker or publish stable releases.

## Using the candidate

In Sketch Studio import the chosen image into a new reference-only sketch, then
open More → Layer-by-layer drawing. Name the clean parts once and Begin. Use
Draw selected layer with Pen, Precision pen or Paint. Reference shows a separate
read-only panel. Compare reference and artwork, enter an actual review note,
and accept or request rework. Work through construction, refinement, details,
clean line and separation. Ordinary Undo/Redo retains the editable history.
Finish keeps the preliminary work and guide in the drawing; animation compilation
and reusable component saving use detached copies containing only reviewed clean
parts. Editing a finished part invalidates its matching review.

Ordinary MCP uses sketch_edit with the same four operations, exact
expectedRevision and stable operationId. Control/review operations occupy their
own transaction, so a reviewer cannot approve unpublished marks under an older
revision. sketch_layered_drawing_preview accepts view reference, artwork or
comparison; it requires the same exact sketch revision. precision_contour is
available through the strict public contract from native4.9.1. The persistent
workflow and comparison require native4.9.2. Native/Node capability metadata are
checked for exact agreement.

The Worker source includes these schemas and gates. This task does not deploy
it. A currently deployed older relay may reject the new operation/action until a
separately authorized compatible relay update; source parity is not proof of live
ChatGPT execution. The owner's phone remains4.9.1 until an APK is installed.

This candidate does not automatically redraw a detailed reference. It supplies
and protects the authored drawing process. The earlier supplied app screenshot was identified by the owner as a cutout
result and retired as an authoritative reference. Corrected original
1000071275.jpg is admitted as a hash-bound whole working-source reference with
its native dimensions, without reusing screenshot crop coordinates, and is
archived/restored with empty authored layers. That checkpoint is explicitly
NOT DRAWN and cannot pass an empty drawing review. No completed matching scene,
original artist stroke recovery, accepted animation, audible dialogue or film is
claimed.
