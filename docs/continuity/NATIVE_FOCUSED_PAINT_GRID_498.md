# Source-focused owned paint grids (4.9.8/code501)

Native 90-degree elbow studies on the owned actor exposed two distinct limits:
regional recipes can pull linework and pigment apart, and a full-canvas16-cell
grid spends most vertices outside a small painted sleeve. Failed generations
remain in private evidence. Focusing a grid does not make an invalid sharp bend
anatomically correct: focused90-degree recipes also failed unchanged triangle
checks. Separate authored forearm/upper-arm cels are the next actual art study.
The240-second film and expressive voice work remain unfinished; voice stays last.

The existing read-only worker `sketch_inspect.paintMeshPreparation` accepts an
optional strict boolean `focusToInk`. Explicit true or false requires4.9.8;
omission keeps the original4.9.5 request and output. True scans all native
nonzero paint alpha, including alpha1, and returns a normal revision/hash-bound
`update_object` patch. The source paint snapshot, files, hash, object bounds and
transforms are unchanged. No source pixels are removed, recoloured or resampled.

The new optional `paintMesh.sourceRect` contains four integer full-source pixels
`[left,top,right,bottom]`, with exclusive right/bottom. The worker includes
`ceil(longestSourceAxis/128)+2` pixels of transparent sampling support, clamped
to the original source. All retained alpha and this support must fit inside the
rectangle. Native transaction/history/import validation and worker rendering
reject forged intervals that exclude them. The original axis/vertex/key/point,
source-pixel, shared12MiB raster and8MiB alpha-selection budgets remain.
Frame-unspecified preparation warms both complete-source and focused texture
variants so later mesh keys cannot trigger UI-thread decoding or a blank preview.
A real API29/33 regression preserves the previous texture-not-ready failure.

Coordinates stay normalized to the full source canvas. Grid points now span the
source interval, not a replacement image. Bent rendering takes the exact integer
source crop before resizing a disposable cache texture and uses native bitmap
mesh triangles. Full-source alpha picking inverts the same triangles and source
interval. All cached variants share the existing raster budget. A regular grid
uses the old complete source drawing path for exact RGBA neutrality; an exactly
uniform translation also uses the complete source, with no tolerance hiding an
edited vertex. Mesh keys retain one fixed source interval. Static sampling,
timeline editing, components and editable archives preserve it.

Manual Paint deformation grid offers Focus grid on retained paint and Fit grid,
with the same native preparation, detached draft, worker-only texture processing,
backdrops, numeric/drag editing, keys, ordinary Apply/Undo and retry receipts.
Changing focus invalidates the prepared draft. Existing meshes restore their own
focus setting. Node schemas/capabilities match native metadata and recursively
gate nested object, story, correction and animation requests. Older mesh requests
and opaque archive minimums stay unchanged. No canonical action is added.

A discarded full-texture BitmapShader prototype was not published. Its original
UV test first omitted the pixel-centre offset; the corrected analytic expectation
keeps the same1.5 colour-quantization tolerance and adds an exact independent
native bitmap oracle. That stronger check then exposed an API29 cached-shader
mismatch. A clipped-bitmap experiment split interior alpha (111 versus128), and
per-triangle affine rounding altered translated semitransparent edges. All failed
tests, source versions and native evidence are preserved. The final integer-crop
bitmap-mesh approach retains exact oracle checks, per-pixel interior alpha checks
on API29/33, and exact native/cached zero-pose and translation tests. These are
not the separate historical IMAGE mirror/trim alpha0x24-to0x25 regression, which
remains unresolved in the isolated4.8.8 worktree.

Fresh check results, committed source, CI, original-signer candidate verification
and real art review belong in external evidence after execution. No phone,
installed-APK, GPU or Android codec execution is inferred from host native Canvas.
Private artwork stays outside Git. No Worker/main/stable/signing identity changes.
