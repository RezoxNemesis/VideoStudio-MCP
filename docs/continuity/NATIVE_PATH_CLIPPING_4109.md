# Native path clipping — separate from the unfinished neck repair

This feature branch extends frozen4.10.8/code511 commit103d5e6. The new explicit
`clipOut` field requires native4.10.9; root owns the combined version/code512
integration, full Android validation and signing. There is no new canonical action.

`clipOut` is an array of1..128 convex exclusion polygons. Each polygon contains
3..32 finite normalized `[x,y]` vertices in object-local canvas coordinates0..1.
The total limit is4096 vertices per path, charged against the existing131072
stored-point and4MiB document budgets. Empty, non-array, nonnumeric, repeated-edge,
self-intersecting, concave and zero-area inputs reject. Every polygon describes
an excluded filled region; multiple overlapping polygons form a union.

The native renderer draws the complete original path into a temporary object-only
layer and applies the exclusion there, then composites it. This preserves the
original stroke rasterization outside the mask and cannot erase existing artwork
below the object. Direct Canvas clipOutPath changed faint stroke pixels outside
the mask in an actual native regression, so that failed attempt is preserved.
There is no persisted bitmap, copied reference artwork or skin-colour repaint.

Object/layer transforms and transform keys move the path and its mask together.
Inverse-transform picking excludes hidden regions. Ordinary `update_object`
patches set polygons; `clipOut:null` explicitly clears them with normal Undo,
redo, replay and revision checks. Source-free components and editable local
archives retain the mask. Archive inputs remain opaque to Node, so their legacy
minimum versions do not change; use native4.10.9 when opening masked artwork.

Initially shape keys, linked masters and vertex-weight/region deformation reject
masked paths. Rigid bindings are allowed. Contour-paint and trace-consolidation
helpers reject/skip masks rather than silently ignoring their geometry. Manual
Brush / Text > Path clipping exposes editable polygons, Apply, inspect, Cancel
and Clear through the same undoable native operation as MCP. No Android SVG
exporter is claimed or added.

Root-observed RED evidence:
- path-clip-red-v1:4 actual native tests failed on missing clip fields/picking.
- path-clip-ui-red-v1:1 actual manual-flow test failed on missing menu control.
- head-neck-clip-green-v1: outside-stroke and frontal-chin failures preserved.
- head-neck-clip-green-v2: missing import compilation failure; no fresh test result.
- head-neck-clip-green-v3: all5 standalone clip/manual tests pass, while4 automatic
  neck integration cases fail. Entire failed generation source/tests remain in
  VideoStudio-Head-Neck-Occlusion and native-character-structure-20261021 evidence.

The automatic depth-derived neck mask is intentionally NOT part of this branch.
Its budget fragmentation and exposed-chin coverage remain unfinished. The strict
head regressions have not been weakened, deleted or called passing. This useful
standalone clipping surface does not establish an anatomical fix, character
likeness, acceptable drawings, mastered viewpoints or a finished animation film.

Fresh Node validation on this standalone source passed768 tests and306 smoke
checks (path-clip-core-node-full.log, exit0). Root reran this exact standalone
source:5 tests/0 failures/0 errors/0 skips, evidence path-clip-standalone-green-v1.
Full combined Android regression, CI and signing remain pending/root-owned;
these focused checks are not a release verification or artistic acceptance.
