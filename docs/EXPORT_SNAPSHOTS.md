An accepted `export_project` request now retains an immutable project graph and
its exact revision in a separate native SQLite export pin. The pin includes
tracks, clips, effects, markers, rehearsal range and source locators. It does not
copy the referenced provider bytes or promise that an external provider will
keep those bytes unchanged or readable.

The service derives pin ownership from the trusted native command ID or manual
export session. Caller-supplied internal parameters are removed before admission.
An optional `expectedRevision` rejects stale admission. The recovery plan stores
the pin identity, owner and revision; replay and restart reopen that same graph
instead of reading the editor's newer graph. A process interruption between pin
capture and plan persistence keeps the pin available to the same trusted request.
An uncertain pin is retained until terminal ownership can be proven.

Pins remain in the media reference ledger through resumable failures, lifecycle
suspension and native cancellation. Terminal cleanup checks exact plan ownership
and waits for both service work and the native terminal callback to stop. On
success, the publication receipt and generated Media Bin asset carry the accepted
project revision and export snapshot ID. If publication survived a restart, the
service repairs its Media Bin registration before completing the recovery plan.

Older recovery plans without an immutable graph are refused rather than silently
exporting a changed timeline. A new explicit request is required. Native pins are
bounded to 24 entries, 16 MiB per graph and 64 MiB total. Normal export renders
the full accepted program. Explicit `export_range` / `app_export_range` retains
the original accepted graph pin plus exact In/Out bounds. Recovery rebuilds the
same immutable cropped graph through `ProjectRangeExport.Prepared`, preserving
the authored animation clock and requested terminal-gap duration. Editor
rehearsal selection is never inferred. All-gap ranges are refused; stateful audio
DSP starts at the cut without earlier PCM pre-roll.

Publication retains one exact owned MediaStore URI through allocated,
verified-pending and published phases. The allocated URI is durable before
copying, full SHA-256 readback precedes the pending receipt, and visibility follows
that durable receipt. Recovery validates the exact graph/revision, integer
animation cadence, output profile and optional range proof. A verified pending row can become visible without
encoding again; an incomplete allocated row is recopied into that same hidden
row. Cancellation stops streaming work and never deletes a visible output. A
visible output racing cancellation is recorded and exposed as retained media.
Legacy outputs lacking this checksum receipt remain explicit review cases.

This implementation has had source review only. Builds, restart/cancellation
exercise, provider mutation behavior and native rendering remain unverified in
this phase, as requested by the owner.
