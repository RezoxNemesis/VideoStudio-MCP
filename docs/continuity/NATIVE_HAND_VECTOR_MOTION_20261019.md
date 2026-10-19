# Native cel-hand vector motion repair

This repair follows committed 4.8.1 source fcbad2d952ad4ca449678f8762e636becf48b669.
The original source and its signed CI candidate are preserved with known motion
failures; static-pose acceptance did not establish complete animation quality.

Native motion exposed errors at right-profile frame19 and high frame75, plus
successful-but-wrong filled regions at left frames24/76/91. Coordinate conditioning
repaired some cases but later full motion found another flood at left frame17.
Failed generations remain under engineering-review/hand-cel-20261018.

Cel vector Boolean operations now use bundled Clipper2-java 2.0.1b with fixed
micro-pixel integers, proper winding normalization and mathematical result-bound
checks. Curve sampling .0001px is finer than the prior .001px conversion policy;
input/output grid is one million units per pixel. Native Android Path/Canvas
remain the renderer. No outside artwork or runtime service is used. Legacy
smooth drawing, old settings omission and request fingerprints remain unchanged.

The unchanged public artifact is vendored for offline builds, with Boost1.0
license/provenance assets. Upstream source tag2.0.1b, commit
33f876605afc9021e11411cf1bbc17a4fccdee99. JAR SHA256:
a525004bae007dec9b2254ea690adbf7b235dda7fc2aa02b88cef5f349e357ac.
Runtime classes target Java8; the archive also carries Java9 module metadata.
Independent Android D8 conversion at minimumAPI29 passed. That is build
compatibility, not phone runtime proof.

Iterative crease cleanup preserves the existing .028span/.065perimeter policy
and genuine holes/gaps/strokes, accounting for new islands disconnected during
subtraction. Cel-only internal path conversion preserves precise contours and
normalized coordinates; smooth command bytes continue through the old encoder.
This internal conversion remains unexposed as a public per-stroke command.

Official focused Android verification passed 66 tests, zero failures/errors,
one skip, 6m33s; archived focused-motion-v6b-junit. A test capture helper used an
unavailable Files.writeString API in the first run; the compatible byte-writing
fix is included and the failed log is retained. Standalone 21 core tests, eight
regressions, the independent appearance oracle and 384 native motion frames also
passed. All 24 retained/editable comparisons have zero deep-interior errors;
the original 7% boundary limit and 24 legacy pixel cases remain unchanged.

All 384 originals across front/left/right/high were captured; independent front/
high review still returned NEEDS_REWORK for intermittent interior dark ticks.
Diagnostics identify outline colour on microscopic skin-hole/sliver contours.
Low-open enlarged views have the same issue. The bounded v7 repair below addresses
those outline defects; complete motion and artistic acceptance remain separate
gates. Preserve the exact preceding generations rather than overwriting them.

Manual Render style and the four shared app-owned hand study examples remain
unchanged. Any explicit renderStyle requires 4.8.1; omission keeps 4.8.0. The native
study harness now records partial frames, failure frame/stack traces, actual
product-class resource/hash provenance and source/hash/file-set/HEAD stability.
Explicit expected release variables allow the same unchanged study inputs in a
combined 4.8.2 candidate. Full 48×96 motion / 336 legacy / 96 archive checks remain pending.

No signing-key replacement, app-data reset, phone/live-MCP claim, stable release,
merge or worker deployment. The broader voiced 124-second film remains NEEDS_REWORK;
the 1000+ scenario programme follows current quality acceptance.


## V7: microscopic internal skin contours

The union could retain closed internal holes narrower than one projected float
spacing. Their filled area was negligible, but the ordinary skin-outline stroke
made them visible as dark dashes. This differed from legitimate palm creases.
The production correction normalizes unresolved internal holes before insets,
detail clipping and outline construction, at both original and normalized-float
coordinate representations. It preserves original mesh vertices, normals,
anatomical joints and all user controls.

A candidate must fit both an average-width bound and the minimum strip enclosing
its entire convex hull. Precision is one directional float spacing plus the
existing one-micropixel Boolean grid, capped at .00025 pixels. Local double
coordinates avoid global-coordinate cancellation. Winding, odd nesting depth,
absence of nested islands and non-touching boundaries are required. Outer
contours, open gaps, representably wide holes, bent thin holes, nested islands
and filled strokes are retained. This is a native geometry repair with no
frame-specific painting, coordinate lookup or larger export tolerance.

Frozen Geometry SHA-256:
3354b6e54abd45f04571f8e87498c44c723358a32e30cca74c8d41647b7fa76e.
Hand Animation remains unchanged from the preceding backend commit.
Private evidence: `motion-fix-v7/verification` under the existing evidence root.
Valid pre-repair RED logs distinguish outline failures from passing native
filled-underlay and mesh controls; earlier harness/trial failures are also kept.

Standalone verification passed 38 native JUnit method runs: seven new contour
checks, 21 unchanged core methods, eight motion regressions, one independent
appearance oracle and one enabled retake capture. There are 132 actual PNGs:
84 known-case retakes (42 retained/editable pairs, at 720 and 2048 heights) and
48 core roundtrip images. All 42 retake pairs and 24 core pairs have zero deep
interior errors. The original 7% boundary limit is unchanged; the front grasp
case changes 3.22144%. All 24 default/smooth legacy cases remain exact.
All 490 earlier v6 PNG hashes were independently preserved.

Official affected Android verification passed in 5m31s: 39 tests, zero failures,
errors or skips (`focused-skin-v7-junit`). The optional capture methods are also
counted by JUnit when output is disabled; the standalone enabled method counts
above are separate evidence, not additional scenarios.

Independent review accepted all 48 core images and four enlarged low-view
retakes for the bounded simple cel target. Known front/high retake review is
still pending. Read-only code review found no material correctness blocker.
Hull and contour-edge scans are quadratic; cheap filters bound typical work,
but matched performance measurements and complete motion remain pending.
Selected retakes do not establish all 48 cases, professional hand drawing,
phone playback or live MCP parity.
