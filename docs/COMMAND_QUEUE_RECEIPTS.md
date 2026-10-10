# Durable command queue receipts

Native, compatibility, Studio and runtime command queues share an atomic Durable
Object transaction path. JSON queues are stored in 64 KiB byte chunks with a
generation manifest, exact lengths, fatal UTF-8 decoding and SHA-256 verification.
The queue budget is 4 MiB, with up to 64 newly admitted active commands. Existing
legacy queues can retain up to 160 active commands while they drain. Pending work
is never dropped to make room for a new command. Immutable request JSON and
terminal result JSON each have a separate 512 KiB bound; lease and receipt proof
metadata have an 8 KiB bound. Admission reserves completion and lease space.
Foreign request and result JSON nesting is capped at 64 levels by an iterative
value walk before serialization or canonical hashing. Cycles, non-finite numbers,
serialization hooks and unsupported values reject explicitly. Legacy queues with
invalid retained JSON require recovery; their original bytes are preserved.
Internally authored optional object properties with `undefined` values are
omitted using ordinary JSON semantics; top-level and array `undefined` reject.

Implementation and regression cases are source-only. No Worker, database, app,
build or test execution established these behaviors during this phase.

Terminal acknowledgements accept only `completed`, `failed`, `cancelled`, `denied`
or `expired`. The first terminal transition records `resultSha256`: lowercase
SHA-256 over UTF-8 `JSON.stringify` of recursively key-sorted result objects,
preserving array order. A repeated acknowledgement must match the terminal status
and this original result digest. Conflicting results cannot overwrite or reopen
the command. Full result objects remain immutable while retained in the queue.

When bounded history prunes a terminal row, the same storage transaction retains
a compact receipt containing its ID, sequence, status, completion time, result
digest and safe original action/project identifiers. Compact receipts contain no
parameters, provider/content URI, handoff, inline media or result strings. A lost
ack response can be retried after compaction: matching caller result bytes are
hashed and replayed as a verified receipt, without rerunning native or browser
work. Unknown IDs and mismatched results never become fabricated completions.

The compact ledger retains the newest completion proofs up to 160 entries and
64 KiB. Very old receipts beyond that bounded window may become unavailable.
Queries return `resultExpired:true` when only proof metadata remains, and expose
`resultSha256` so an executor can compare its own durable result. A proof-matched
ack replay adds the caller's `result` and `resultReplayed:true`; the expired flag
still describes server retention. Executors must retain unacknowledged results
locally and report a conflict or unavailable proof instead of rerunning effects.

`readCommandReceipt(storage, queueKey, commandId)` opens a read transaction.
`readCommandReceiptInTransaction` uses an existing transaction; bulk alias
migrations can read `readCommandReceiptsInTransaction` once and map its IDs.
`acknowledgePrunedCommand(transaction, queueKey, commandId, result, status)` runs
inside the completion transaction when the full row is absent.
`terminalCommandUpdate` and `canonicalCommandResultSha256` are asynchronous.
Queue writers store the compact ledger and replacement chunks atomically. Its
checksum, format sentinel and manifest requirement make malformed or missing
proof fail explicitly; corruption is never replaced with an empty ledger.

Owner-alias convergence calls `mergeCommandReceiptsInTransaction` before its
canonical queue rewrite. Both ledgers validate, matching terminal status/result
SHA and original action/project labels are required for duplicate IDs, and a
canonical full row owns its accepted sequence after rebasing. The helper returns
`receiptCount` and `highestSeq`. A union beyond 160 proofs or 64 KiB rejects the
entire migration instead of evicting proof. The caller preserves every full
terminal row during that same queue rewrite and retains the source ledger.
