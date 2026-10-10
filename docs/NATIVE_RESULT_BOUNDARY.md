# Native result delivery boundary

These changes and regression cases are source-only. Tests, builds, Worker/runtime
execution and ChatGPT host verification were deferred by the user.

Each newly accepted native command reserves a durable completion slot before
effects. The SQLite outbox accepts 16 unfinished reservations/results, reserves
512 KiB plus envelope space per command, and retains every unacknowledged result.
Polling applies backpressure when admission is full. Exact duplicate results are
idempotent; a different result/status cannot overwrite the first receipt. Only a
matching relay command acknowledgement removes a result. Oversized legacy rows
remain retained and explicitly block new admission for recovery.
Acknowledgement validation compares ID, terminal status and the complete decoded
result with the durable outbox entry before advancing the local sequence.

Results have a 512 KiB UTF-8 JSON budget and maximum nesting depth 64. A larger
result becomes an explicit `resultTruncated:true`, `resultDeliveryComplete:false`
outcome receipt with safe IDs and actual operation status. Size overflow includes
the canonical full-result SHA and byte count; an encoding/depth failure explicitly
states that its full digest is unavailable. This does not repeat the operation,
claim full delivery, or claim that a queued job has finished.
Canonical hashing streams through a bounded capture buffer. Hashing itself is
capped at 16 MiB/one million JSON values; exceeding that limit produces an explicit
encoding-limit receipt without a fabricated full digest.

`app_state` accepts project/asset offset and limit fields. Jobs, recovery plans and
the mutation journal are compact diagnostics without embedded prior results.
`app_project_state` returns complete registered assets, clips, tracks or markers in
pages capped at 384 KiB, with `nextOffset` and project revision. Pass the returned
revision as `expectedRevision` across pages to detect concurrent edits.
Registration heartbeats also carry only compact project summaries, capped at
100 entries/32 KiB, with explicit total and truncation fields. Large prompt or
project inventories do not inflate the single device metadata storage value.

`app_rig_describe` includes the validated complete rig definition by default
(maximum 256 KiB), separately from sampled state. `includeDefinition:false` reads
compact diagnostics. `app_cel_describe` returns the real bounded stroke document.
`app_cel_edit_strokes` queues only its strict 32 KiB action batch and returns the
same small verified generation receipt as cel redraw; it does not transport PNG
bytes or duplicate the vector document inside its completion result.
`app_rig_curve` returns at most 256 scalar samples, their authored/request clocks,
and declared/sample bounds. It uses the compiled channel evaluator without
returning mesh geometry or duplicating the complete rig definition.
Rig sampled `authoredPose` contains local FK values before IK, while the sampled
bone/IK diagnostics contain the solved pose. Key edits can set
`replaceKeyframe:true` to remove omitted channels in one complete sparse-row
replacement. Weight painting returns actual changed vertex indices.
Pure inspection pages are excluded from the mutation journal; their exact
unacknowledged results still live in the outbox.

Browser command queues retain the actual bounded result and canonical completion
proof. A project's `latestCommand` is only a small hint; larger results are read
through the command lookup. Failure to update a full/deleted project's hint never
changes the already durable command acceptance or acknowledgement.
