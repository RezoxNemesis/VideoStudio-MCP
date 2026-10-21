# Legacy queued native payload recovery

The historical producer at commit71d9502074d43e347612e3f7c5a49445e8e9057f,
src/native-payload-storage.js, stores six-field __nativePayloadVersion:1 pointers
and64000-unit UTF-16 string chunks under app-v3-blob keys. The current shared
reader returned these pointers without hydrating parameters/results.

The reader now restores inline and manifest-carried field-root references before
normal validation. It enforces enclosing queue/device/command/field namespaces,
registered ownership proof for carried aliases, exact descriptor/chunk geometry,
current UTF-8/depth/per-field/aggregate budgets and existing result checksums.
It joins string chunks before encoding, preserving split surrogate pairs.
Failures retain original storage. New writes keep the current manifest/chunk
format and reject unresolved references. Old blobs remain preserved.

An actual historical-producer reproduction failed before repair and passed after
repair, including migration and completion. Eight new regression tests reproduced
RED; focused store tests now pass24/24 and existing consumer regressions68/68.
Independent bounded source review found no material defects. This source repair
is distinct from animation scenarios and does not count toward the1000goal.

Private proof: engineering-review/native-payload-compat-20261019/v1, including
historical source, exact source hashes, producer reproduction and RED/GREEN logs.
The broader dirty body-development worktree also passed306smoke+610Node tests;
those include unfinished body-control changes and are not a standalone Android
or release claim for this queue commit. No deployment or live migration occurred.
