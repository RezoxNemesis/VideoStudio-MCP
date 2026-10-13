# Photo reference and backup delivery 4.0.1

User goal: reproduce the supplied photograph through Sketch Studio, let the user watch real drawing operations and show the actual output. Preserve the delivered minimal UI and simple app-key workflow.

Native implementation scope: preserve bounded source detail and all EXIF orientations, retain source provenance, atomically import a hidden reference, begin live drawing from a truthful baseline, and respect a newer manual/selected-sketch state. Repair the independent backup's stale-index and cached-404 delivery path without changing authentication or replay rules.

Constraints: no Gallery, no key in source/logs/artifacts, original package and signer, native media ownership, no imported reference displayed as a claimed redraw. The latest inline photograph has no downloadable source handle; source fixtures verify engineering behavior, not likeness. The existing 384-pixel tracer is not a photographic reconstruction engine.

1. Identify source fidelity and live-state failures; reproduce them using native Canvas and actual source/import transactions.
2. Implement bounded ImageDecoder import and source metadata, shared hidden-guide import semantics and generation-bound UI callbacks. Add regressions for EXIF, pixel detail, atomic baseline, explicit guide and user takeover/navigation races. Root owns Gradle; photo implementer owns the four Sketch Java classes and new test.
3. Model stale CDN snapshots and negative packet cache responses with a deterministic fake transport. Add a fresh non-secret query to fixed-origin reads after confirming failing tests. Mailbox implementer owns its Java class and test.
4. Keep the Node schema/version gate aligned with native 4.0.1 semantics. Complete independent source reviews and one full Android/Node verification after focused checks pass.
5. Commit exact reviewed source, produce one original-signer CI APK, verify package/version/label/certificate/checksum, and publish that existing artifact through the publisher-only workflow. Update the PR with final behavior and evidence.
6. Import the supplied original through a native surface once its file bytes are accessible. Verify actual live drawing and likeness before showing the native output. Keep this acceptance gate explicit if the source remains unavailable.
