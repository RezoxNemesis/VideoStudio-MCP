# Native character structure repairs — 4.10.7 in progress

Extends frozen4.10.6 e16a050, without rewriting earlier branches or evidence.
Actual native guide rendering exposed filled black anatomy silhouettes in both
profiles and both three-quarter views. Android Boolean/stroke paths used EVEN_ODD;
ordinary editable ink uses nonzero winding. Export now alternates nested native
contour direction while retaining already-WINDING paths. It preserves editable
vector output and native outline holes, rather than substituting raster artwork.

The original and corrected-fixture RED evidence remains private at
`/workspace/work/native-character-structure-20261021/red`. The corrected regression
observed23,221filled pixels versus2,076native outline pixels for left profile,
and corresponding failures for right and both three-quarter views. A first fixture
incorrectly recomputed the union after screen projection; that failed generation
and logs also remain. The corrected reference uses the actual model-space union.
The separate long-hair profile regression observed zero visible rear hair pixels
in either profile: the prior flat curtain was inside the projected cranium.
A closed native head-mesh curtain retains depth across side views.

Fresh focused53 Android tests pass,0failures/errors/skips, including both original
render regressions, exact native polygon comparisons for nested holes/islands and
separate parts, existing anatomy/head/camera/human/lift checks. Actual side render
now shows body interiors and rear hair, but is a generic construction figure,
not an accepted reconstruction of the owner's character. Model-space camera
math and this repair do not establish unseen artwork, mastered360views or actions.

Combined4.10.7 integration, full checks and original-signer candidate remain
pending. Keep local paired-provider failed generations and source-assisted cutout
provenance. One native4second study is one action, not96distinct actions.
