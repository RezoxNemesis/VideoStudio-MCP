# Preview graph ownership — unpublished 4.10.25/code528

This isolated `fix/native-preview-graph-ownership-v41025` branch starts at
`ca8c8299e4270eb80d074c544ea58361684a3665`. The explicit export-wait branch
pointer remains intact. Root owns integration, full validation, versions,
signing, pushes and every JVM/Gradle/native/ADB run.

## Resource issue and change

The editor retains a current `CompositionPlayer` and its previous preview for
rollback. Previously, when accepting a third revision, it built, prepared and
bound candidate C before releasing obsolete fallback A; current B stayed alive.

Pinned Media3 1.11.1 allocates its `VideoGraph` lazily when the primary input
initializes on the playback thread. Preparation can therefore initialize C
while A and B still hold their graphs. This is a permitted race, not proof that
every third revision allocates three graphs or that it caused the owner's phone
error.

The narrow change retires only A before constructing the candidate builder,
after duration admission and capture of B's playback intent, watcher and error
callback. B remains the current preview until C is accepted. Candidate failure
releases C and leaves B's clock, context, intent and callbacks intact. Successful
adoption retains B for rollback as before. A is deliberately no longer available
after a later C setup failure. Same-revision seeking and the previously verified
single-/multiple-input graph-selection policy are unchanged.

The SDK's internal release waits at most 500ms. This repair avoids the app's
unnecessary overlap when normal SDK release completes; it cannot guarantee GPU
resource reclamation after an SDK release timeout. It adds no actions, fields,
version floors or Node changes and changes no project or media bytes.

## Genuine tests and limits

The new `LiveEditPlayerGraphQuotaTest` has two cases. Its real SDK builder and
`setComposition` create the actual graph wrapper. A controlled `PlayerView`
binding hook dispatches public primary-sink initialization to the candidate's
public playback looper. A protocol-only `VideoGraph.Factory` allows two live
leases; counters change only at actual factory creation and returned-graph
release. Only source loading in `handlePrepare` is suppressed. The fixture
models a valid eager initialization interleaving; it does not run GL, codecs,
native pixels or device resource limits.

One case admits the third revision without a third live graph. The second
initializes C, then rejects its view binding and checks actual C cleanup while
preserving B's position, context, intent, error callback and completion watcher.
Both first establish two real graph leases, preserve source JSON and require
normal final cleanup. Existing recovery, ordinary-player and graph-selection
controls remain unchanged.

The first fixture source, SHA256
`10307a61cd00ec5ea04fa7b5b0a6df765ffdbd6c0377c452fb36317afe8e4104`,
stopped at compilation: `VideoGraph.Factory.create` does not permit the declared
checked exception. Source/API review missed that throws restriction. The fixture
alone was corrected to use an unchecked quota exception and remove its checked
throws declaration. All test bodies and assertions stayed byte-for-byte intact.
The compile-failed source and logs remain preserved; that run was not a behavior
RED.

Corrected frozen test SHA256:
`24e098fe1449e43042b6373ef41af30cebf3870c8f4808272e837ee2a039f8e7`.
Root's genuine baseline ran two tests with two failures in 2.807s. Both reached
the third actual SDK graph request while A and B held the quota. The deliberate
binding fault could not yet be reached because allocation failed first.

With frozen `LiveEditPlayer` SHA256
`823af10dfd1f2f79fbac6a870b06abb9286e5471e0f10b9af7fc2c3ec76143f5`,
root ran 20 unchanged cases successfully in 5.821s: two new quota cases, six
existing recovery cases, three ordinary player cases and nine graph-selection
cases. Independent source review found no blocker. This is scoped unit/lifecycle
verification, not a full integrated build or a device/GPU/phone-error repair
claim.

## Evidence and remaining work

Private source evidence is under
`/workspace/work/editing-preview-graph-quota-20261024/`: `source-audit-v1`,
`native-red-v1`, `native-red-v2` and `native-production-v1`. Root logs and launcher
evidence are under `/workspace/work/film-production-20261022/` with prefixes
`preview-graph-quota-528-red-v1`, `preview-graph-quota-528-red-v2` and
`preview-graph-quota-528-green-v1`. Preserve all generations.

Post-merge full regression, exact-revision CI/signing and actual updated-APK
preview/export retakes remain required. Root's older c202 full native run passed
2705 tests before the export-wait and graph-quota additions; do not report it as
validation of this source. Only APK527 has actual installed/signature evidence
so far. Original phone-error reproduction and the harder 12.4s export retake
remain separate. The four-minute character film is unfinished with zero accepted
final seconds. Keep the original signer, app data, MCP identity and all assets;
no replacement renderer or artwork, Gallery browsing or stable publication.
