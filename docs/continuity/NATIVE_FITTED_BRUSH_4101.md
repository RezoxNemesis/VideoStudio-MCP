# Native fitted pressure brush, candidate4.10.1/code504

Actual redraw crops exposed angular sparse Graphite strokes. The existing precise
pen fits smooth editable paths, but loses the selected brush and uses constant
width in a protected layered workflow. Add an explicit optional `brush` to
`precision_contour`, sharing the ordinary native brush schema and renderer.

The existing native fitter prepares an open centerline on the sketch worker.
Native PathMeasure samples that curve, retaining every original pressure knot,
including stationary peaks, at its normalized original stroke-length position.
Endpoint pressures remain exact. Actual source-pixel spacing is adaptively checked
because PathMeasure's arc-length lookup can exceed the requested half-pixel step.
Reject more than8192 retained points; ordinary document point/JSON limits remain.
Cancellation checks precede fitting, sample preparation and publication. Filled,
closed or pressure-outline substitutes reject in this mode. No source pixels,
automatic tracing, prefab anatomy or new canonical actions are introduced.

The result is an ordinary editable stroke/brush object. Its generated points are
the geometry used by rendering, selection, live replay, rig binding and archives.
This mode retains sampled stroke points, rather than editable Bezier handles.
The existing precise pen tool and omitted brush requests keep their old behavior
and4.9.1 floor. Explicit brush fitting requires4.10.1. Opaque archive floors stay
unchanged; compiled objects contain only existing ordinary stroke/brush fields.

Manual: choose Precise pressure brush in the main tool picker or the layered
Draw selected layer picker. Brush and colour selects Graphite or another native
brush. Native activity tests exercise that actual tool and its worker save, plus
the protected workflow picker. Ordinary MCP uses precision_contour with brush.
The matching Node contract is source-implemented; no live Worker deployment or
phone installation is claimed in this workspace.

RED evidence is preserved privately. It includes unknown-brush rejection before
implementation, a lost collinear pressure peak, a stationary pressure peak lost by
coalescing points, endpoint0.1 becoming0.09999999999999998, and a measured0.5235374px
native sample gap exceeding the unchanged0.51px regression tolerance. The sampler
was repaired; those assertions were not relaxed. One initial new style assertion
compared JSON numeric spelling0.0 with0 after serialization; it was corrected to
assert every brush value and unchanged input semantics. Exact endpoint and raster
checks remain. The unrelated old4.8.8 mirrored-image alpha failure is untouched.

Fresh combined full local validation:2187 Android tests/0failures/0errors/14skips
across382 JUnit suites; Node688 tests plus306 smoke checks pass. Focused combined
native checks separately passed51/0/0/0. The earlier placement-only2176/0/0/14 at
2a86e56 remains separately recorded. CI and original-signer candidate verification
are still pending; local checks do not establish either.

Actual native source/artwork comparisons confirm substantial remaining likeness
errors in the authored faces, fringe, facial planes, shoulders, hands and cloth.
A new partial collar/shoulder study exposed a white gap caused by nonoverlapping
authored robe/coat geometry. Corrected ordinary native requests close that gap;
this is an authoring correction, not another renderer fix. The current studies
remain NEEDS_REWORK. This optional fitted brush supplies rendering/authoring
precision, not an image-conditioned anatomical reconstruction provider. Layer
structure, smoother lines and passing tests do not supply missing artistic detail.

Quiet acting/contact/peeling and the240s film remain unfinished. All original
projects, failed artwork and signed4.9.9 remain preserved. Voice last. No live
phone installation, Android codec export or deployed MCP relay update is claimed.
