# Video studio 4.0.1

This update improves reference import and live drawing, and removes CDN cache delays from the encrypted backup task route. It retains the application ID, pinned original signing certificate, device-owned connection key and existing projects. Install the compatible APK over the current app.

## Sketch reference and live drawing

Owned photo references retain their original pixels up to a 2048-pixel long edge. Larger photos use an exact bounded target size rather than being unnecessarily halved. Android's software image decoder applies all eight EXIF orientations. The owned working copy records the original dimensions, orientation and SHA-256; the imported original remains unchanged. Input, decoded-pixel, encoded-file, aggregate-storage and free-space limits still apply.

Human reference imports start with the guide hidden. Agents can request the same behavior with `sketch_import_reference` and `guideVisible: false`. This commits the import and hidden transform in one revision, so undo or a live baseline cannot briefly reveal the finished reference as artwork. Legacy imports without the new option remain compatible. Reference tools can explicitly show the guide.

Automatic tracing hides the guide before recording its live baseline unless the user explicitly chooses **Show the reference guide while tracing**. Saved drawing events contain editable trace operations. Completing an import cannot reopen a sketch selected earlier; completing automatic trace preparation must respect a newer **Draw myself** selection.

The shared `sketch_edit` operation `set_reference_visibility` updates the selected image's base and keyframe opacity atomically while preserving its motion and interpolation. Hiding sets those opacities to zero; showing preserves positive values and restores zero values to 0.35. The reference settings form scrolls so its crop and trace controls remain reachable on shorter screens.

These changes improve reference fidelity and drawing-state correctness. The existing edge and colour-region tracer still has a 384-pixel detail limit and bounded editable-object budgets. It is not learned photographic reconstruction, and this release does not establish exact likeness to the user's latest photograph.

## Backup connection

Index and packet reads use fresh random request URLs on the existing fixed GitHub origin. This prevents a stale CDN index or cached missing-packet response from delaying new tasks even when the CDN ignores request cache headers. Authentication, byte/time bounds, ETag validation, ordered retries, stable command IDs and native pause/privacy controls remain unchanged. No key or secret appears in the random query.

The prior 4.0.0 encrypted route has completed native health, Sketch capability, Sketch listing and owned-project state commands on the installed phone, with authenticated completed receipts. The primary relay's old command-array storage still requires its separately prepared server repair; the backup avoids that failing store. Complete Cloudflare outages can still delay remote receipt retrieval.

## Verification and remaining acceptance

Focused tests cover pixel retention, bounded larger-photo decode, all eight EXIF orientations, atomic hidden imports, hidden live baselines, explicit guide viewing, selection changes during import and trace preparation, keyed visibility without lost motion, stale indexes and cached missing packets. The final local suite passed **708 Android tests in 149 suites**, **190 Node tests** and **306 smoke checks**, with zero failures, errors or skipped tests. Independent photo and connection reviews found no remaining correctness blockers in this scoped change. The original-signer workflow verifies the exact source APK before the publisher uploads that existing artifact.

The latest reference was supplied inline without a downloadable source file in the coding workspace. Its exact native import, redraw, likeness assessment and output screenshot remain unverified. Test fixtures and successful builds are not substitutes for that artwork acceptance.
