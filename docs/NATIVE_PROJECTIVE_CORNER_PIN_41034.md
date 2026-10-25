# VideoStudio 4.10.34 — Projective Corner Pin

## Actual native capability

An original static 2D four-corner perspective pinning tool for a previously imported image or native vector layer. Android Skia `Matrix.setPolyToPoly` computes the projective transform from source TL/TR/BR/BL to four ordered target points. The result is a genuine 2D homography, not a 3D scene, 3D camera, optical-flow tracker, or screenshot zoom.

Four normalized [0,1] x/y pairs are stored only on the selected visual layer. The schema rejects NaN, Infinity, out-of-bounds, crossed, reversed, concave, and nearly degenerate quadrilaterals. Old unpinned image/vector JSON remains unchanged.

Once per original native Media3 overlay generation, the already-bounded source pixels (<=1 MP) are projected to a separate ARGB_8888 bitmap at the original bitmap dimensions. Native preview and MP4 export consume the same warped source, then apply existing translation, rotation, opacity, Bézier and parent-group keys. Source image files are never overwritten or scanned from Gallery.

## Human editing

In the Inspector for an image or original shape, tap **Corner Pin · 2D perspective**. Drag four cyan handles, or enter exact TL/TR/BR/BL decimal values. Validity checks run before accepting any shape. **Apply corner pin** saves one revision-guarded project edit; Cancel discards the detached draft. Reset restores the unwarped source without changing original media assets.

## ChatGPT efficiency

The existing private native agent accepts `corner_pin_capabilities`, `corner_pin_plan` (read-only), `corner_pin_edit` (one guarded edit), with projectId, expectedUpdatedAt, layerId, operation (apply/clear), and eight corner numbers. Invalid or unowned layer IDs are rejected without writing project changes.

## Verification and limits

Robolectric native Skia tests confirm cropped alpha outside a trapezoid, preserved center pixels, original bitmap immutability, convex guard, touch dragging and source-safe MCP edit transactions. A signed Android build and full native unit suite are mandatory. Real-phone editor/preview/export and parity against the user's six reference clips still require hands-on visual proof. Full Adobe AEP, 3D, dynamic depth tracking, automatic rotoscoping, animated track mattes, volumetric particles and GPU motion blur remain outside this completed slice.
