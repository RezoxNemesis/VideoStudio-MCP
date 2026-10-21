# Cranial construction-edge occlusion — native guide repair

This dependent feature extends path clipping f3a5aa2, intended for root's4.10.9/
code512 integration. Root owns the version bump, full combined checks and signing.

The head's closed construction shell previously inked its bottom cap through the
already-filled neck. Native anatomy now shares the actual torso surface vertices
and triangles with the head helper. Its actual facing triangles, barycentric
coverage and projected depth distinguish a hidden side cap from a nearer frontal
chin; mere2D overlap is not sufficient.

Original head cubic commands remain unchanged. Bounded cubic visibility roots
identify hidden intervals, whose stroke ribbons intersect actual torso triangles.
Conservative visible-stroke envelopes protect neighboring exposed tips and their
antialias coverage. Exact convex-intersection preflight avoids fragmenting
nonintersecting masks. Final128-ring/4096-vertex limits and4096 partition scratch
limit reject rather than downsample. These vector exclusion masks use the shared
object-private clipping renderer, never skin recolouring or layer-wide erasure.
Figure placement maps the mask vertices with the same staging transform as ink.

Preserved actual native evidence is under native-character-structure-20261021:
- head-neck-red-v1 reproduces158 left/265 right internal cap pixels.
- head-neck-green-v1/v2/v3 preserve failed coarse/split/conservative repairs.
- head-neck-clip-green-v1 preserves outside-AA and frontal-tip failures.
- head-neck-clip-green-v2 is compilation failure only; no test success claimed.
- head-neck-clip-green-v3 preserves chin-AA and mask-fragmentation failures.
- head-neck-clip-green-v4: root observed21 tests/0failures/0errors/0skips in33s,
  including the unchanged strict3-case regression, shared clipping/manual tests,
  head/contour regressions and actual native8view guide study. Root reviewed
  native front/left/right/three-quarter head outputs.

The strict regression retains every original frontal-chin pixel and every ink
pixel outside the actual neck mask plus its fixed2pixel guard. It also checks
that the reproduced internal profile cap disappears. Test crop transport was
extended to move new mask vertices alongside commands; assertions were not
weakened. Every failed source/fixture/XML generation remains preserved.

This is a technical construction-seam fix. It is NOT actor likeness or general
per-pixel3D hair compositing. Root identified a separate remaining visual defect:
the long rear-hair curtain is emitted after the torso and can cover the neck like
a beard. That painter/depth-order defect remains NEEDS_REWORK and is reserved
for the separate center-part/fringe513 work. Generic native guides, AI likeness,
artistic accuracy and the240second animation film remain unfinished.


## Accepted-camera failures found before integration

Actual emitted/validated guides were checked across60 accepted yaw/pitch/tilt
inputs. This is one static guide family, not60 animation scenarios or accepted
character views. The first run failed three inputs: rear/high225degrees hit an
existing Skia skin-union failure, and high360degrees with neutral/tilted heads
exceeded the128-polygon neck-mask budget. The baseline without neck clipping
reproduced the first failure; all failed inputs/logs remain private evidence.

Skin union now preserves both operands, tries Skia on a copy, and uses the
existing bounded native vector Boolean backend only on failure. Successful
old paths are unchanged. Neck masks coalesce touching/overlapping pieces only
when their exact union equals the convex hull area; concavities and holes stay
separate. Caps remain128 rings/32 vertices/4096 points per object. New native
union/hole tests and the previously failing camera inputs pass. The combined
focused run has23tests, zero failures/errors/skips. Full integrated validation
and signing remain separate gates.
