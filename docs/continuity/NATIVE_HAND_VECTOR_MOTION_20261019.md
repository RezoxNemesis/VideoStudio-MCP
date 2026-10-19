# Native cel-hand vector motion repair

This repair follows committed4.8.1 source fcbad2d952ad4ca449678f8762e636becf48b669.
The original source and its signed CI candidate are preserved with known motion
failures; static-pose acceptance did not establish complete animation quality.

Native motion exposed errors at right-profile frame19 and high frame75, plus
successful-but-wrong filled regions at left frames24/76/91. Coordinate conditioning
repaired some cases but later full motion found another flood at left frame17.
Failed generations remain under engineering-review/hand-cel-20261018.

Cel vector Boolean operations now use bundled Clipper2-java2.0.1b with fixed
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

Official focused Android verification passed66tests, zero failures/errors,
one skip,6m33s; archived focused-motion-v6b-junit. A test capture helper used an
unavailable Files.writeString API in the first run; the compatible byte-writing
fix is included and the failed log is retained. Standalone21core tests, eight
regressions, the independent appearance oracle and384native motion frames also
passed. All24 retained/editable comparisons have zero deep-interior errors;
the original7%boundary limit and24legacy pixel cases remain unchanged.

All384 originals across front/left/right/high were captured; independent front/
high review still returned NEEDS_REWORK for intermittent interior dark ticks.
Diagnostics identify outline colour on microscopic skin-hole/sliver contours.
Low-open enlarged views have the same issue. That visual cleanup is a separate
pending gate; this technical repair is not final animation acceptance or release
readiness. Preserve the exact preceding generations rather than overwriting them.

Manual Render style and the four shared app-owned hand study examples remain
unchanged. Any explicit renderStyle requires4.8.1; omission keeps4.8.0. The native
study harness now records partial frames, failure frame/stack traces, actual
product-class resource/hash provenance and source/hash/file-set/HEAD stability.
Explicit expected release variables allow the same unchanged study inputs in a
combined4.8.2candidate. Full48x96motion/336legacy/96archive checks remain pending.

No signing-key replacement, app-data reset, phone/live-MCP claim, stable release,
merge or worker deployment. The broader voiced124secfilm remains NEEDS_REWORK;
the1000+scenario programme follows current quality acceptance.
