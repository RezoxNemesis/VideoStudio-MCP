# Preserve the selected editable archive across the Android Files return

An actual Android Files import restored the editable animation and its assets,
but the editor remained on its previous drawing. Opening the restored drawing
through File → Open worked. This repair addresses that native viewer lifecycle;
it does not change archive contents, drawing tools or animation rendering.

The isolated feature starts from the verified 4.10.17/code520 source at
`eceead08442624f03e6ee457911cd9ba71313296`. No version bump belongs to this feature
commit. Integration, the next candidate version and full CI/signing are root-owned.

## Cause and behavior

A follow poll can start after the user launches Files but before Android pauses
the editor. Pausing invalidates the live generation and marks the editor for
reload. The Files result then begins an asynchronous archive restoration before
resume. The normal resume reload replaced the editor session, so the successful
archive result failed its session guard even though the user had not navigated
or edited. Simply deferring that reload exposed a second stale follow callback
which could select a previously signalled drawing after resume.

The result now holds a scoped token for its source session, sketch, book/page,
viewer and follow generations, and source revision. It defers only that token's
pause-induced reload. Automatic follow polls wait while the token owns the
editor; callbacks from an earlier lifecycle generation cannot select another
drawing. This does not suppress deliberate navigation or an explicit new Follow
choice. Navigation and new local drawing invalidate automatic selection.

After restoration, the transfer worker also checks the original sketch's stored
revision. A native edit completed outside the UI session must not be hidden by a
late import selection. An unreadable, missing or changed source conservatively
declines automatic selection. This is read-time admission: an external writer
can still commit after the revision read. The check does not hold a transaction
across Android's UI event queue, and no stronger cross-runtime exclusion is
claimed. Stored edits and the separately restored archive remain preserved.

A successful current manual Files choice takes over this viewer using the
existing watch preference, just as other deliberate manual work does. It opens
the imported drawing and pauses automatic following. **Watch ChatGPT** remains
available to explicitly resume following the existing work journal. Failed,
cancelled, stale or background imports do not disable following. A deferred
reload resumes on failure or on a result that can no longer select the editor.
The completed import remains discoverable through **File → Open**.

All archive decoding, restoration, writer waiting and stored-revision reading
remain off the UI thread. There is no new MCP action, schema, capability floor,
request fingerprint, archive format or hidden follow watermark. The repair does
not rewrite the shared workspace signal, clear data, reset MCP identity or
alter source assets and signing identity.

## Regression evidence

Root executed the original three native lifecycle tests on retained full520:
three tests, one genuine failure in 4.993 seconds. The ordinary result and
deliberate navigation cases passed; a poll beginning between picker launch and
pause reproduced the stale editor. These tests invoke the actual activity result
and lifecycle with a real native editable archive, menus and content URI. Latches
control worker ordering rather than setting private lifecycle flags.

The first repair still failed the same test in 4.006 seconds: the deferred reload
exposed the stale poll callback, which selected a different drawing. The second
repair passed the unchanged original three in 4.132 seconds. A broader focused
run then passed 53 tests in 11.293 seconds: those three, seven additional guard
tests and 43 existing lifecycle, archive, drawing and manual cel-copy tests.

The seven guards cover completed and pending UI edits, book-page navigation,
invalid archive recovery with following retained, delayed follow after manual
success, explicit Watch ChatGPT resumption and a fresh Follow choice during the
transfer. Separate native writing then exposed an admission gap: the old UI
session remained at revision1 while an ordinary native edit committed revision2.
Root executed that additional regression on the second repair: one genuine
failure in 3.837 seconds. Its assertion remained unchanged for the worker revision
check. Root then executed the final broader focused suite: 54 tests, zero
failures/errors/skips in 11.627 seconds. This comprises the original three
assertions, seven additional guards, the external-edit regression and 43 existing
tests. The final run used retained full520 plus the exact single production
activity override; it is not a full integrated521 build. The permanent lifecycle
test differs from the private original only by its public class name.

Exact sources, failed and passed logs, launchers and SHA records are retained
outside Git under `native-editable-import-lifecycle-20261022`. Permanent tests
retain the original assertions; the private lifecycle class is renamed only for
the repository. No native assertion was weakened after its behavioral failure.

No Node contract changed; root owns the full integration and Node checks with
the next release metadata. These focused checks do not establish a fresh full candidate build, signing,
installed-device execution, live MCP, artistic acceptance or a finished film.
