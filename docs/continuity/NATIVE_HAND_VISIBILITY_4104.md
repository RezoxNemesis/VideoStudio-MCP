# Native hand visibility repair and movement audit

Target4.10.4/code507 extends frozen4.10.3 product
`ea33220a3df31370c17dacb8169383a9d0e76af3` and Temple study commit
`2bd6906d29f03c051b7cb5e73beeaf71965edccb`. Package, signing identity, private
MCP identity, existing projects, source artwork and all other worktrees survive.

## Reproduced native failure and repair

The owner's new movement request triggered a fresh90-case native hand/body audit:
seven body actions in six views and four hand patterns, both handednesses, in six
views. It failed one case: default smooth left-hand open/close in the right view,
size0.95,96frames/24FPS, at frame40. Android Skia rejected a triangle/visible-region
intersection with `INVALID_SKETCH: Native triangle visibility failed`.

`SketchSmoothHandVisibilityTest` reproduced that exact failure before the fix.
Failed test XML, logs, requests, source document and frames0..39 remain under
`/workspace/work/movement-review-20261021/generations`; nothing was deleted or
weakened. The renderer now attempts the identical legacy Skia intersection on a
private candidate path. Only a failed intersection retries through the existing
bounded native fixed-point vector Boolean implementation. It neither drops the
triangle nor changes the silhouette, poses, source geometry or render style.

The exact failing sequence subsequently rendered all96frames. All40 actual
previously successful frames stayed pixel-identical. The fresh full90-case retake
passed and compared930 prior successful native screenshots pixel-for-pixel.
Frame/assertion counts and retries do not create additional animation scenarios.
The body/hand cases are procedural diagnostic figures, not accepted Temple art.

Android versionName/versionCode, AppProtocol and current-release assertions are
updated together. Old feature minimums, omitted settings, fingerprints and actions
are unchanged. This bugfix needs no new MCP action or Worker deployment.

## Further actual native studies

Explicit ordinary native pose requests generated five4s studies: jump with
anticipation/airborne/landing keys, jab/recovery, chair sit/stand, four facial
expressions and silent mouth shapes. All96frames of each rendered; native archive
restoration preserved four sampled frames exactly. The first jump's crossed arms
were visually rejected and corrected with new explicit elbow/hand keys and timing.
Both generations remain. The chair is ordinary authored native pen strokes.

These studies do not establish Temple likeness, measured real-world dynamics,
natural voice, forced audio alignment or final artistic acceptance. In particular,
sleeping is not a current general-human preset. Fifteen requested numeric angle
inputs are correctly rejected because that figure tool has named views, not a
numeric camera-yaw control. Those checks represent two unmet capability families,
not fifteen successful angle animations. Existing exact3D orbit math is separate:
it cannot invent hidden views of the supplied flat Temple drawing.

A separate Temple study authors closed/half/open/half/closed hand cels through
ordinary native pen/paint and `duplicate_cel`, without imported pixels or rigs.
The48-frame2s render changes no pixel outside its two declared hand regions and
returns exactly to the original closed hands. The face/body/costume are held.
Visual review still finds the fingers flat and stiff. This is NEEDS_REWORK, not
an accepted character model or satisfactory final movement.

All drawing/editing/analysis/rendering executes actual VideoStudio code through
native Android Canvas under Robolectric. Workspace scripts serialize literal app
requests, collect receipts or package unchanged frames. No external artwork
generator, Gallery, hosted inference, phone/APK/GPU, listening or Android codec
execution is claimed. Silent MP4s use unchanged native PNGs packaged by FFmpeg.

## Validation and delivery boundaries

Fresh `npm test`:698 Node tests plus306 smoke checks PASS. The four updated
release-contract suites add50 passing focused checks. The isolated hand regression,
appearance, clothing contact, exact camera and90-case retake pass. Consult the
final local validation receipt and candidate CI for the full Android result;
neither historical results nor a focused run proves full integration.

The new conditional native study drivers preserve request/renderer/version/source
proofs, immutable generations, editable archives and honest BLOCKED/NEEDS_REWORK
statuses. Manual/native operations are shared; these host runs do not establish a
live ordinary ChatGPT/MCP round trip or phone installation.

Voice stays last. Sleeping, accepted detailed arbitrary-angle drawings and the
proper240s film remain unfinished. Do not pad a held or failed study to240s and
call it the requested film. Deliver actual reviewed studies and the verified
candidate only; no main merge, Worker deployment, stable publication, replacement
signer, uninstall, data clear or MCP reset. One local Gradle process only.

Fresh full local Android result: 2217 tests /0failures/0errors/20skips across392 suites,7m46s. The complete XML evidence is retained outside Git. Candidate CI/signing remains a separate gate.


## Additional native editor failure found before pushing

Actual SketchStudioActivity preview of the new Temple hand-cel archive failed
with `Part selection exceeds its8 MiB bound`. Full-source alpha bitsets charged
transparent top/left padding once per paint cel; editor prefetch then accumulated
the masks of multiple timed cels. This was reproduced in the real editor and a
new native bitmap regression before repair. Failed UI/test generations remain.

ImageSelection now keeps immutable full source width/height and an ink-rectangle
origin/stride. Only the alpha-support rectangle consumes bit storage. Native
lookup still accepts full-source coordinates, includes every nonzero alpha,
preserves holes, and rejects padding/out-of-bounds coordinates. Sources/paint,
UVs/transforms, texture caches, rendering, reuse-by-hash and the8MiB limit are
unchanged. Both worker scans check cancellation. Opaque sources retain their
original bit cost; empty sources retain zero support.

Focused selection/mesh tests and actual editor captures at0/24/42 pass after the
repair. The preceding2217-test full result predates this selection repair; a new
full run is required for the final candidate. Do not call the earlier full run
verification of the new code. The first Temple open-hand review also showed
invented circular skin panels on the gloves; a new authored native generation
removes those panels rather than altering the supplied source or accepting them.

Final combined full local Android validation after BOTH repairs: 2219 tests/0failures/0errors/20skips across393suites,7m41s. Exact JUnit evidence and product diff SHA are recorded in the private local verification receipt.
