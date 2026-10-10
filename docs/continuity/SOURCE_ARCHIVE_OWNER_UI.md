# Original-source archive owner controls

The Media Bin's visible Source info action and each imported original's details
open an Archive / restore original bytes panel. Generated media is excluded.
The panel reads the bounded local archive catalog and upload journals on the
owner media worker; it never scans a provider's remote folders. Completed records
show generation identity, provider authority, creation time, actual byte count
and chunk count. Folder quota remains unknown.

Archive captures the exact selected SAF tree and reviewed native project revision.
Restore captures an exact recorded generation and its recorded tree, independently
of the currently selected storage profile. Both dispatch
`ControlService.ACTION_LOCAL_SOURCE_MEDIA` with one stable project/asset identity,
an expected revision and a fresh owner request UUID. Transfer work runs in the
durable owner-priority native lane, with original sources retained.
Cancel current transfer targets only that owner request's native job; verified
chunks and its upload journal remain available for explicit recovery.
Resume retained transfer appears for cancelled or checkpointed owner requests
only when the native service reports that the previous worker has stopped. It
uses the same request UUID and original source/folder admission, with no automatic
resume or replacement by the currently selected storage profile.
Forget cancelled retained transfer is a separate explicit choice after the
worker has stopped. It releases that request's local recovery references;
original media, committed archive records and remote archive files remain.

The panel polls persisted owner transfer receipts and local metadata every two
seconds while open. Restore results distinguish a verified owned copy relinked
to the stable source asset from a verified copy registered separately in Media
Bin because revision, locks or clip constraints blocked the relink. A successful
restore reloads the current editor graph and keeps the selected clip identity.
Archive records alone are not presented as a fresh remote byte verification;
the restore operation verifies actual chunk and whole-source checksums.

Implementation and review are source-only. No build, tests, emulator, device,
parser or CI checks ran. The later verification phase must cover retained SAF
grants, interruption and explicit transfer recovery, source mutation during
upload, full provider disk, missing/corrupt chunks, relink conflicts, Activity
recreation and owner-only cancellation before claiming runtime behavior.
