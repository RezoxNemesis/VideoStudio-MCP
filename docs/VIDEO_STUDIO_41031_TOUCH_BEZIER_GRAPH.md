# VideoStudio 4.10.31 – Native Editable Bézier Graph

## Directly verifiable engineering

An original Android view plots the same bounded, deterministic cubic Bézier easing function used by the exact native keyframe sampler. Two cyan handles can be dragged by touch to alter the outgoing easing curve; a numeric x1,y1,x2,y2 field remains available for precision.

The editor is integrated into IMAGE, TITLE, VIDEO and GROUP keyframe dialogs. Every adjustment remains a detached draft until the owner selects Apply, which invokes existing project revision guards. Cancel leaves the saved project untouched. The graph and exact numeric fields reflect one another. Dragging a control handle selects cubic_bezier interpolation automatically.

The compositor's temporal X coordinates remain within [0,1]; Y velocity control values remain within [-0.5,1.5], with the final displayed eased output clamped to [0,1]. A live graph follows this same renderer interpolation math, not an approximation made solely for UI.

## Verification

- Robolectric native graphics tests for rendered axes, curve and handles.
- Gesture tests for drag, bounded coordinates, cancellation and numeric roundtrip.
- Full Android build, signing continuity, native test suite and visual QA are independent gates.

## Honest limits

This is not Adobe's proprietary graph editor and does not import AEP compositions. It does not itself create 3D cameras, matte tracking, GPU vector gradients, motion blur or a professional render. User-owned video benchmarks still require actual rendered-frame comparisons after device installation.

## Performance discipline

The graph uses 72 sampled path points and 20 binary-search iterations per evaluated progress. The editor draws only when invalidated; it does not decode source video or rerender a full MP4 during handle dragging. This supports rapid touch authoring on Android but requires device frame-time measurements before claiming quantitative speed improvements.
## WYSIWYG phone layout and export aspect

The existing portrait/landscape resizable workspace gains a separate preview
canvas choice: 9:16, 16:9, square or 4:5. Rotating the phone does not silently
alter authored pixels. Direct overlay touch-position calculations use the
visible letterboxed Media3 composition rectangle. Export defaults to the same
canvas ratio rather than contradicting the preview. User choices persist
locally as editor preferences without changing any original source assets.

The final export quality and GPU frame match still require actual on-phone
verification, including the previously reported Test subject 2 failure.
