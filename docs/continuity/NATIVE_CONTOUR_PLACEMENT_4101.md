# Native contour placement, candidate4.10.1/code504

The actual native manga head study exposed fresh face and fringe fills moving to
index0, beneath earlier hair paint. This changes only explicit new placement:
`paint_fill.placement` accepts `under_contour`, `layer_back` or `preserve`.
Omission retains the historical layer-back behavior, inputs and fingerprints.
Under-contour placement inserts a fresh paint cel immediately before its own
closed authored pen contour. Nonempty cels cannot relocate automatically.
Preserve repaints a chosen existing cel without changing its ordering.

The native paint journal binds the exact choice; ordinary Undo/Redo, operation
receipts, replay and archive restoration use the same placement helper. Source
contours and retained tile identities stay source-bound. No new canonical action,
reference sampling or copied original pixels are introduced.

Manual: Brush / Text > Paint studio > Fill closed pen contour. The new-cel
checkbox defaults to under its selected contour; unchecking preserves the old
new-base request. Existing-cel fills explicitly preserve their current order.
Node/MCP exposes the same strict optional field at native4.10.1. Old paint-fill
requests retain4.9.4; opaque legacy archive action floors are unchanged.

Fresh placement-only local checks:2176 Android tests, zero failures/errors,
14skips;685 Node tests and306 smoke checks pass. These results precede the
subsequent fitted-pressure-brush work. Keep each result's source scope explicit.
Private RED JUnit and actual failed/repaired/native-request redraw generations
remain outside Git under work/native-authored-scene. No current source is signed
or CI-verified until its exact final revision is built and independently checked.

The actual native head study now stacks correctly but remains NEEDS_REWORK for
likeness, expression, hair flow, fine modeling and overall quality. It is not a
finished composition or animation. The precise pen also drops the selected
brush/pressure inside layered drawing; the next scoped capability carries native
fitted centerlines through the ordinary pressure brush. Do not claim that either
capability automatically draws an accurate reference or establishes acceptance.

Original signer/package/identity preserved;4.9.9 signed source29f2ad0 is frozen.
504 reserves503 for the separately preserved uncommitted contact worktree.
No Worker/main/stable publication, replacement signer or device/data reset.
