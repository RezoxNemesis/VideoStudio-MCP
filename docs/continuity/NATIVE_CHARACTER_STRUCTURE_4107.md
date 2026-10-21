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

## Fresh integrated source verification

Combined source includes preserved persistenceb0857c5, paired graph auditfee01ad,
providerae7628a, version90832da and manual snapshot/provenance repair3f199e6.
Fresh full local Android: 2281 tests,0failures,0errors,22skips,
407 suites;763 Node tests and306smoke checks pass. One local Gradle ran
for7m51s, original RED evidence retained. Source, logs and individual XML remain
under private integrated evidence. CI/signing remain separate pending checks.

The actual new native paired CPU/JNI profile generation finished successfully
(178,904ms operation), with frozen input hashes, archive/replay and unchanged
original. It still fails artistic acceptance: elongated neck, weak eye/detail,
changed lower face/hair/clothing. It is a512px model-generated raster, not recovered
artist layers. Actual head-grid and wrist source-assisted studies preserve texture
identity and fixed-neck constraints, but paper fringe and planar deformation still
need review. No accepted360degree actor, thousands of actions or240second film.

Matching native and Node schemas are in this branch; the remote Worker was not
deployed and new private MCP/phone operation is unverified. Optional paired weights
are about4.58GB and require64-bit/8GiB available RAM. No installed phone, GPU or
Android-codec performance is implied by workspace native execution.
