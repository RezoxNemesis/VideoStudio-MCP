# Frozen storyboard source evidence

`StoryboardSourceManifest.capture(Context, Project, JSONObject selection)` runs
on a native worker and reads only image IDs already registered in the selected
project. The original project, files and duplicate library entries are unchanged.
It streams each image through a 64 KiB buffer into a private temporary probe,
hashes the exact encoded bytes, and reads dimensions/EXIF from that same probe.
No full-resolution bitmap is allocated. The probe is released in `finally`.

`withVerifiedSource` supplies a read-only private encoded snapshot and its measured
entry to a native callback. The complete-entry variant checks all source fields;
the SHA-only compatibility variant requires a lowercase 64-hex accepted digest.
The callback decodes those exact copied bytes, rather than reopening a provider
after hashing. The file exists only during the callback and is deleted even if
decoding fails. A returned bitmap may outlive that file. Provider content changing
later cannot alter the snapshot; the next verified read rejects the changed bytes.
If private-file cleanup fails after a completed callback, `VerifiedSource.discard`
releases the undeliverable result before the helper reports failure. Its default
closes `AutoCloseable` results; bitmap callbacks override it to recycle their owned
bitmap. A callback that throws before returning still owns its intermediate resources.

`withOwnedSourceSnapshot(Context, Project, assetId, callback)` supports first-time
owner authoring with a registered image, including a single-image project. The
editor records `sourceSha256` from the same snapshot it displayed. Existing
annotations must retain their authored hash and reject mismatch; measuring new
bytes must not silently rebind old geometry. `captureSource(Context, Asset)` is
an internal worker measurement helper whose caller resolves the registered asset.
`withFrozenSource` is the explicitly unbound legacy variant and supplies no prior
annotation attestation.

Limits are 160 original images and ordered anchor occurrences, 32 MiB per encoded
image, 512 MiB in aggregate and a 256 KiB manifest. Cancellation, revoked grants,
bad image bounds, unsupported URI schemes and byte-limit failures reject before
the caller creates or clears an output generation.

Source order is explicit:

- `assetIds` is the literal ordered list, including deliberate repeated anchors.
- Without an explicit selection, persisted image clip order remains literal.
- `anchorGroups` is mutually exclusive with `assetIds`. Each ordered group has
  `representativeAssetId` and `assetIds`; every original image must be covered.
  These are declared variants, including cross-format copies, not a claim of
  visual equivalence inferred from names. Representative occurrences can repeat.

Filename normalization, extensions and larger file sizes never prove identity.
The separate `exactByteAliases` inventory records equal SHA-256 bodies under
different native asset IDs; this evidence does not erase story beats or assets.
An illustrative 23-record fixture with 13 declared groups demonstrates the
contract. The actual private Test 1 originals remain unavailable here.

The v1 artifact includes source project ID, `library`, `originalImageCount`,
`orderOrigin`, `orderedAssetIds`, optional normalized `anchorGroups`,
`exactByteAliases` and `manifestSha256`. Library records contain `assetId`, `mime`,
`metadataSha256`, `sourceSha256`, `bytes`, `codedWidth`, `codedHeight`,
`exifOrientation`, `orientedWidth` and `orientedHeight`. Native source labels and
roles contribute to the metadata hash but are not exported. URIs, file paths,
credentials and timestamps never enter the artifact. Its digest uses recursively
sorted object keys and preserves array order.

`verifyOwnedSources(Context, Project, JSONObject manifest)` checks the saved
shape/order and rereads registered source bytes. Source or metadata changes fail
explicitly. Recovery must keep its accepted manifest and verify it before any
output allocation or cleanup; silently recapturing changed sources is not retry.
`StoryboardBatchPlanner.plan(Project, JSONObject, JSONObject acceptedManifest)`
uses that exact sequence. A structurally bound manifest is distinct from current
owned-byte verification, rig admission, decoded-output evidence and visual approval.
The sequence auditor reads each image through the same verified snapshot decoder,
applies EXIF orientation and explicit contain mapping, and records the mapping in
its advisory report. It rejects changed source bytes instead of auditing a later
unattested provider stream. This audit remains separate from rendered-output QA.

`orientedDimensions(manifest, assetId)` returns the oriented width/height. Source
rigs, masks and original sword annotations use oriented source UV. This metadata
helper does not fix existing portrait stretching: a renderer needs one registered
aspect-preserving transform for all masks, joints, props and contact endpoints,
or must reject aspect mismatch. Legacy annotations cannot silently be reinterpreted.

Optional `choreography` supplies one typed beat per exact ordered transition,
including repeated asset pairs and cross-batch boundaries. Missing beats remain
absent rather than inferred from filenames. Cinematic admission requires authored
beats; explicitly labelled source deformation may retain generic filename hints.
