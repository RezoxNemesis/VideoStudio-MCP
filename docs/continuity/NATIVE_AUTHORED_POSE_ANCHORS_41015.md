# Preserve authored animation anchors

The existing compiler allowed its quarter-pixel transform reduction to discard explicit pose and source keys. A source-assisted head study returned to neutral at frame84 in the saved rig but1402 output pixels still differed at that frame. A source-free regression independently reproduced changed authored transforms/native pixels and a lost layer opacity key0.43015→0.43.

Reduction now partitions at explicit rigid-rig pose frames, source object transform keys, camera/source-layer keys, contact blend/lock boundaries, and the first free frame after each contact. It retains the preceding sample for contact boundaries and authored incoming HOLD transitions (including the implicit base before a late first key), so tiny steps do not become early ramps. Smooth and linear predecessors can use the existing bounded reduction; their authored keys themselves remain exact. Approximation remains bounded between these exact anchors. No new operation, input field, minimum version, request normalization, fingerprint or source/program mutation was introduced. Output document hashes can change because the compiled animation is repaired.

Contact intervals include their end frame. Independent review identified the zero-blend release edge: with a subpixel contact correction the old reducer smeared release after end. A separate real regression showed frame61 x=9.70588235295687E-6 instead of0 after contact36..60. The first free sample is now retained explicitly, including with blendOut0.

The existing32768 dense-sample and4096 output-key budgets remain enforced. Mandatory authored anchors cannot be removed by increasing tolerance; a programme hitting the key budget must reduce authored keys/bindings or split its shot. This is a tradeoff for preserving explicit authoring intent. Contact reports still describe sampled rig-space constraints, not rendered silhouette/continuous collision quality.

## Evidence before integration

- Original source-free RED:4 tests,4 failures,2.615s.
- First fix:5 new tests plus22 existing compiler regressions passed,3.881s.
- Actual head study with only this compiler override:1 pass,46.868s; all96 portable frames/outside-head pixels/exact neutral boundaries preserved. Shared head/eye/mouth pivot error0.01318px. Visually NEEDS_REWORK because the old source cutout includes coat/paper scraps. This test is not an accepted character view or finished film.
- Independent zero-blend release RED against the first fix:6 tests,1 failure,2.581s. Earlier five remained passing.
- Final focused run:6 new plus22 existing tests,28 pass,3.639s.

These are focused Java class overrides against the verified517 runtime, not a fresh integrated518 build or signed APK. Root owns integrated Android/Node/CI/original signing. Failed native generations and exact execution evidence remain private outside Git. Manual animation and ordinary MCP use the same compiler automatically. No phone/GPU/Android codec, all-angle anatomy, automatic contacts, new original artwork or completed film is claimed.

## Integrated regression repair

The first full combined518 run executed2401 tests with3 failures,0 errors and28 skips. All three were the existing native duel scene exceeding the unchanged4096-key budget with5043 output keys. Unconditionally retaining predecessors of continuous pose keys consumed unnecessary keys. The repair keeps every authored value exact and limits predecessor retention to incoming HOLD/first-key discontinuities and contact boundaries. The failed source, complete JUnit XML and logs are preserved outside Git; the original scene and its tolerances/budgets are unchanged. Two additional exact-value regressions protect late first rig/source keys. Fresh integrated validation remains pending.

The first reduced predecessor retake preserved authored values but still produced4118 keys in the existing duel. Contact anchors are now restricted to bindings in the modified hip subtree; independent branches cannot be affected by that constraint. The existing duel, numeric persistence, compiler and nine authored-anchor regressions now pass:38 tests,0 failures/errors,1 skip (27seconds). A new independent-limb test verifies identical head keys with and without the leg contact. The failed retake is retained, including an isolated test-fixture correction: source-opacity timing no longer demands exact unrelated non-authored smooth rig motion. No original assertion or scene was weakened.
