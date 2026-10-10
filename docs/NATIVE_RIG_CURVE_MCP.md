# Native rig curve inspection

`app_rig_curve` / native `rig_curve` reads one actual compiled scalar curve from
the evaluator used by the phone's rig value graph. It does not change the project
or start a render job. The implementation has been reviewed from source only;
tests, builds, device execution and host execution remain deferred.

Supply the owned `clipId`, `kind: bone | ik`, stable bone/IK `id`, `channel`, and
integer `startMs` / `endMs` in 0–604800000, with `endMs > startMs`. `projectId` is
optional under the existing current-project selection. Optional `expectedRevision`
must match the inspected graph revision exactly.

- Bone channels: `rotation`, `x`, `y`, `scaleX`, `scaleY`, sampled before IK.
- IK channels: `targetX`, `targetY`, `mix`, sampled before reach clamping.
- `timeDomain: output_local` (default) converts through the shared signed held
  clip animation clock. `authored` addresses the rig's authored milliseconds.
- `samples` defaults to 128, with a range of 2–256. Sampling includes both
  endpoints. An interval with fewer distinct integer milliseconds returns fewer
  points and reports its actual `sampleCount`.

The response identifies the project/revision, clip, kind, ID, channel and domain.
Each point contains requested-domain `timeMs`, effective `authoredTimeMs`, and
`value`. `bounds.minimum` / `maximum` are the shared evaluator's declared channel
clamps; `sampledMinimum` / `sampledMaximum` describe only the returned samples.
`sampled: true` and the evaluator/clock identities disclose how the values were
obtained. Uniform samples may miss a narrow change or a discontinuity; the tool
does not claim analytic extrema or return geometry. Disabled rigs can still be
inspected and return `rigEnabled: false`.

The service compiles once per request and uses the shared scalar samplers for all
points. Unknown IDs/channels, malformed time domains, stale revisions and missing
rigs reject explicitly. Pause and owner permissions follow `rig_describe`;
One File Lock does not permit this inspection. Repeatable inspection bypasses the
effect journal, while its exact unacknowledged result retains the existing bounded
outbox and acknowledgement proof protections.
