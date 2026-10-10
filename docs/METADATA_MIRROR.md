# Private project metadata mirror

Opaque native rigs, motion paths and cel exposure provenance remain on their
original clips during mirror application; deleting or rebinding them is refused.
Native frame cadence, markers and rehearsal ranges are preserved. Use the shared
native animation tools to change this state.

The permanent `/app-mcp-v3/<owner-key>` endpoint exposes an explicit metadata-only
mirror through `app_metadata_mirror_sync`, `get`, `edit`, `reconcile`, and `revoke`.
These tool names are prefixed with `app_metadata_mirror_`. No protocol v4 migration
or replacement owner credential is required.

`sync` requires `enabled: true`, a project ID, the native project graph and its
actual `sourceRevision`. A new mirror starts at revision 1; an existing mirror
requires its current `expectedMirrorRevision`. `get` returns the stored graph,
native source revision, mirror revision, pending reconciliation flag, audit and
conflict snapshots. `edit` requires the current mirror revision and supports only
`patch_clip`, `insert_clip`, `delete_clip` and `track_flags`. Clip edits use only
asset and track IDs explicitly synced into that project, validate timing and
same-track overlap, and respect track locks.
Existing linked A/V group IDs, embedded-audio detach state and
`audioExtractionDetached` ownership provenance round-trip exactly, including each
flag's presence and boolean value.
Changing linked membership, timing, source ranges or placement, and manufacturing
detached-audio state require the shared native timeline operations; metadata-only
edits reject them instead of independently changing a linked peer. Deleting or
replacing a provenance-bearing clip also requires the native timeline operation,
including after the owner unlinks its A/V group.

Native markers and editor rehearsal ranges are preserved privately when a
mirrored timeline is applied. The Worker does not edit or upload those fields.
Shortening the program retains marker positions and disables a rehearsal range
that no longer fits; exports continue to render the complete accepted program.

All effects require the existing private owner identity and are blocked while
ChatGPT control is paused or One File Lock is active. Mutations use a Durable
Object storage transaction. Revision conflicts preserve the current graph and
retain both the current and incoming conflict graphs in separate bounded values.
Graphs are limited to 64 KB, 200
assets, 32 tracks and 120 clips; the last 40 audit entries and 8 conflict snapshots
are stored separately so one Durable Object value does not contain the history.
Local file/content URIs, signed source URLs, inline bytes and credentials are
excluded from mirrored metadata. Export locators and source bytes are not copied.

After offline metadata edits, `sync` refuses to silently overwrite the pending
graph. `reconcile` requires the current mirror revision, `baseSourceRevision`, a
current actual native revision and native graph readback. `acknowledge_mirror`
requires that readback to match the edited mirrored graph. An exact no-op
acknowledgement may retain the same native revision, including edit-then-revert;
it never invents a native history entry. `keep_native`
explicitly keeps the native graph and retains any displaced offline graph as a
conflict snapshot. Resolving a dirty mirror can retain an unchanged native
revision; only the mirror revision advances. `revoke` disables future edits while retaining the graph and
audit for owner review.

Connected Android has explicit `app_metadata_mirror_sync_native`,
`apply_native`, `retry_native`, `status_native`, `revoke_native` and `keep_native` tools under
the same `app_metadata_mirror_` prefix. Sync requires `enabled: true` and sends
the actual compact native graph/revision. Apply requires current native and
mirror revisions, uses the shared SQLite project transaction, retains original
asset URIs and generation metadata, and rejects missing synced sources,
unsupported effects and destructive changes on locked tracks. Metadata authoring
limits fail explicitly instead of silently truncating titles or prompts.

`AppProtocol` calls the owner-authenticated `/api/v3/app/metadata-mirror` REST
route with the device identity and generation. Actual request bytes are bounded
to 128 KB. Native graph effects require both local owner permission and the
Worker's current mutation scope; a read allowed during pause cannot authorize an
edit. No new credential or protocol migration is involved.

Applying a graph writes a durable prepared marker before the native transaction
commits, then retains the applied revision and canonical fingerprint until exact
cloud readback acknowledgement succeeds. Native and cloud commits are separate.
A transport error or cloud conflict leaves `pendingAcknowledgement: true` and
the marker available to local status and explicit retry. Retry requires the same
native revision/fingerprint and mirror revision; it does not reapply committed
edits or discard conflicts. Interrupted metadata jobs return an explicit retry
requirement instead of automatically applying work after restart.

If a subsequent native edit makes an applied marker stale, exact retry refuses
it and the marker continues to block sync and revoke. Explicit native
`keep_native` resolution requires `expectedNativeRevision`,
`expectedMirrorRevision`, and exact `expectedPendingId`,
`expectedPendingFingerprint` and `expectedPendingMirrorRevision` from local
status. It preserves a bounded local resolution receipt and the displaced cloud
graph before clearing the matching marker inside a final native readback
transaction. A changed native graph, transport error or cloud conflict retains
the marker. Lost resolution replies can be recovered only from exact retained
evidence and matching cloud readback. Changing the Worker mirror alone cannot
retire native recovery evidence. There is no silent discard or native overwrite.

Standalone native titles support up to 2000 characters. The metadata mirror
supports up to 1000 characters per clip title and explicitly rejects larger
native titles during sync; it never truncates the owner's text.

Automatic background graph upload/pull and reconciliation are **not implemented**.
The owner must deliberately opt in, sync, apply or retry; source asset bytes are
never uploaded by these metadata actions. Manual caller-provided reconciliation
alone cannot prove Android applied an edit. Tools continue to return
`nativeAutoReconciliation: false`.

Every mirrored asset reports `mediaAvailability: metadata_only`. Source media has
not been uploaded or made available to a cloud runtime. Tools return
`executorAvailable: false` and `mediaUploaded: false`; cloud render, generation,
analysis, model inference and durable cloud execution are not provided. An
unsupported metadata operation fails explicitly. A stored graph is not a rendered
video or a verified cloud executor.

This implementation has source review and authored regression coverage only.
Tests, builds, deployment, active Durable Object rollout and named ChatGPT host
verification remain deferred at the owner's instruction.
Native `rig2d` authoring and cubic motion paths remain opaque local animation
state. The mirror does not author them. Native projection excludes these fields,
native apply restores their exact existing values, and deletion/rebinding of an
existing rig/path clip is refused. Worker mirror submissions containing rig/path
effects return an explicit native-authoring error. Use `app_rig_*`,
`app_set_motion_path` and `app_set_animation_easing` for actual shared native
editing; the metadata mirror never claims to deform pixels or execute a model.

