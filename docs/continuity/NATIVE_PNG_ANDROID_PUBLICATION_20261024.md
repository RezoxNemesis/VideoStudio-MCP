# Native Android PNG publication repair

Status: SCOPED ROOT VERIFICATION PASSED; SOURCE FROZEN FOR INTEGRATION. The
publication agent has run no native/JVM/Gradle/ADB process. Root genuine baseline
coordination RED was six failures in six cases,2.997s, on unchanged FULL525.
Original test assertions/source SHA remain unchanged. First scoped root GREEN
attempt ran39 cases with19 failures: every failure is Robolectric ShadowLinux
opening a directory through host RandomAccessFile and returning EIO. The actual
V3 public-Os directory open/fsync passed. Preserve
`film-production-20261022/png-publication-lock-525-green-v1-JUnitCore.log`; do not
ignore fsync failures, skip native durability calls or weaken any assertion.
V4 prepared probe tested the universal standard FileChannel directory force
alternative before a product change; source SHA256
7314d99d3f15bad7b149386a4e4b63b0e6ed60cd2df12e90c9b7f325532eb4c3.
Root actual V4 passed the same normal-save checks plus universal standard
FileChannel READ/NOFOLLOW directory force. Evidence:
`actual-android-rename-probe-525-v4/Summary.json` and `results/Completed.json`.
Production now universally uses that exact standard API, without a host/test
branch, ignored durability error or changed old test. Subsequent scoped GREEN
passed39/39 in11.729s on the frozen universal FileChannel product source.
Root evidence is retained at
`film-production-20261022/png-publication-lock-525-green-v2-*`. All original test
assertions remain unchanged. This is scoped native JVM verification, not full
integrated526, a signed changed APK or actual product PNG export.
Root owns actual Android probes, frozen RED/GREEN evidence, all native execution,
versioning, integration, signing and exact-revision CI.

## Actual failure and preserved evidence

Original-signed VideoStudio4.10.22/code525 admitted a real-photo Save PNG request
and then failed at progress5 while publishing snapshot.json. The failed operation
remains untouched. Readback is retained in
`/workspace/work/editing-nine-references-20261024/actual-photo-png-525-v1/`.
Root's first registered-Application probe in the actual APK reproduced
`Files.createLink` -> `AccessDeniedException` and public `Os.link` -> EACCES13
against independently fresh app-private source files. Existing-target collisions
returned normal FileAlreadyExists/EEXIST and all original scratch bytes survived.
Both failed primitive generations and complete traces are retained at
`actual-android-publication-probe-525-v1/results/Completed.json`. No further
hardlink primitive, hidden API, policy change or permission bypass is proposed.

A second ordinary-save probe initially failed SDK compilation because
O_DIRECTORY is not public. V2 source/logs remain preserved. V3 replaces it with
public O_RDONLY|O_CLOEXEC and public fstat/S_ISDIR before directory fsync.
V3 prepared source SHA256:
`46c911b1b2727385caa90ae70eb9c16c7dd16bc2b4fbddd39cb80402ced2b9cb`.
Root compiled and ran V3 in the actual original-signed525 APK. Its preserved
`actual-android-rename-probe-525-v3/results/Completed.json` reports
ordinarySaveProtocolSupported:true: exclusive lock, synced staging/atomic rename
with exact bytes/inode, unchanged collisions, cancellable waiting with retained
flag, directory fsync and stable lock reacquisition. This proves those ordinary
Android scratch primitives, not product PNG export or cross-process concurrency.

## Implemented guarantee and scope

Use ordinary Android private file saves: fsync a fresh private same-directory
staging file, revalidate source/parents/target while holding one stable
cooperating-generation lock, then rename atomically to a previously absent name.
No copy into a public name, no partial target, no non-atomic fallback and no
replace-on-conflict behavior. The private generated project/asset becomes visible
only after verified image files and the atomic database ownership witness commit.
Process-crash/replay boundaries are explicit below; no power-loss/fault-injection
success is inferred from ordinary fsync calls or the host tests.

The no-overwrite guarantee applies to cooperating app-private readers/writers
that all acquire the stable lock. ATOMIC_MOVE itself is not an OS no-replace
guarantee. This does not protect against a malicious same-UID writer ignoring the
protocol. That boundary must remain explicit in code/docs and validation.

## Stable coordination

Within the existing `synchronized(SketchNativeExport.class)` monitor, acquire the
exclusive FileChannel lock associated with canonical generation identity:

`files/.sketch_export_locks/<SHA256(UTF8 canonical generation path)>.lock`

The lock directory/file is private coordination state, not authored/export media.
Never unlink, replace, truncate or put it under generation cleanup: all processes
must continue opening the same inode after failures/retries. Validate trusted
Context root aliases, containment and absence of descendant symlinks, including
the lock file. Open CREATE/WRITE/NOFOLLOW_LINKS. Revalidate after acquisition.

Use interruptible bounded-delay tryLock waiting so both another process and an
independent same-process channel are treated as busy; OverlappingFileLockException
is not success. Check cancellation before IO and between attempts. Restore the
interrupt flag when Thread.sleep is interrupted, and surface the ordinary export
InterruptedException while retaining the cause for interrupted channel failures.
Release FileLock/channel in finally. No wait may publish any generation file.
The helper supports same-thread reentrant leases for the same generation.
Nested different-generation acquisition rejects rather than introducing an
undocumented cross-process lock-order cycle.

PNG `admit`, `prepare` and `verify` hold the same lock through all generation
reads, recovery checks, immutable publications and DB-witness verification. The
legacy `SketchNativeExport.prepare`, `complete`, `recordPublished` paths must
cooperate for the same `sketch_studio/.../exports/...` namespace: PNG and MP4
share operation IDs/snapshot names. PNG-only locking leaves a real race.
Book namespaces are separate; do not silently claim all historical book readers/
writers were made cross-process safe unless their entry points are integrated
and validated too. No schema, old request fingerprint, floor or source change.

## Publication and crash boundaries

Under the held lock, validate source and target as owned nonsymlink paths with
the same canonical parent. Verify staged length/hash. If a target exists, require
a regular file with exactly the expected bytes/hash and reuse it; reject a
different target without modifying it. Otherwise check cancellation and perform
`Files.move(staging,target,ATOMIC_MOVE)`. Fail if atomic move is unsupported; never
fall back to ordinary move/copy/replacement. Verify the renamed file and sync its
parent directory using the actual-Android-probed standard
FileChannel.open(directory,READ,NOFOLLOW_LINKS).force(true), with owned-directory
validation before and after open, try-with closure and strict failure propagation.

Staging FD is already synced by the writer. A successful rename may consume its
private staging name; finally deletes only that operation's still-private temp
if present. Never remove published targets after releasing a failed transaction.

Crash before rename leaves private staging only. Crash after immutable metadata
publication but before image keeps the frozen source/receipt for exact retry.
Crash after PNG publication but before DB commit retains the pinned image and
frozen retry, as existing cancellation/rollback tests require. Crash after DB
commit but before completion receipt uses the existing atomic DB witness to
recover receipt only, without resurrecting removed human project/assets.

AtomicFile is retained only for the existing completed.json recovery receipt.
Its finishWrite may replace and it is not the immutable PNG/snapshot publication
primitive. Existing equality checks, witness-first replay and backup guards stay
inside the lock; sync the receipt directory before returning verified success.

## Prepared checks and honest limits

`SketchStillExportPublicationLockTest` contains six public-existing-API cases:
lock symlink rejection, waiting preparation/exact replay, admission after a prior
writer's conflicting snapshot, receipt verification after changed bytes, waiting
cancellation/flag/retry and legacy MP4 preparation sharing the same lock. It uses
no new production symbols and can compile against old525. These are deterministic
coordination boundary tests in one JVM, not actual Android cross-process proof.
Original source SHA256:
`09d44a07fe72c395b863b6e7372fbb0474aa2b7422a2259002ea67f6903f4ddc`.
Root must preserve and classify every run, including any baseline control PASS.
The real525 export failure is already genuine RED; no fabricated NIO-denial
shadow or weakened original test is needed.

V3 actual-APK scratch probe checks normal FileChannel lock/exclusion, synced
staging/atomic rename, exact bytes/inode identity, retained different/identical
collisions, waiter cancellation, stable lock reacquisition and public directory
fsync. It does not itself export a PNG or prove actual cross-process concurrency.
The source implementation is now frozen: new SketchExportLock plus narrowly
changed SketchStillExport and SketchNativeExport entry shims. No version, schema,
format, floor, action, source bytes or old fingerprint changed. Product source
SHA256s at the universal-directory-sync GREEN handoff:

- SketchExportLock: be5fd8b062d33aefe68923fe792e83dcad4aecefb5b85b07dcbe1b717c0fd597
  (first public-Os version adc1c98d remains preserved with failed39-run evidence)
- SketchStillExport: fa59d05c9560a8f211cb58e1ef3ea70106a1b14b82db9ce23211e2ec9858eff5
- SketchNativeExport: cdd7b911f68f30ec2b56e8a8945a05e1b940ac31140131fe450a86b8b7885e99

Root scoped39-case retake passed11.729s with all assertions and the original
lock-test SHA unchanged. The former19-failure generation remains preserved and
no old test fixture was changed. Fresh full integration remains required. Finally
build an original-signed candidate and repeat the real-photo
manual save/verify/import/replay in the installed APK. No native or visual success
may be inferred from host tests/probe source or a successful debugger entry.
