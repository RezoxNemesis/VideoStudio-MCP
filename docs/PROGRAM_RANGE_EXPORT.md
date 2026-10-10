# Program range export contract

`ProjectRangeExport.prepare(project, acceptedRevision, inMs, outMs)` accepts an
explicit absolute program range. It returns an immutable `Prepared` plan. Its
`project()` method returns a fresh cropped graph and does not modify the owner's
saved project, markers, playback range, revision, original media or prior export.

Each intersecting clip moves to `max(clip.startMs, inMs) - inMs`. Its exact output
span becomes `min(clip.endMs(), outMs) - max(clip.startMs, inMs)`. Source handles
follow each clip's existing effective speed, using integer source milliseconds;
cuts at existing clip edges retain the original source bounds exactly. A cut that
collapses to identical source handles is rejected. Source quantization can change
the effective rate slightly, as with the existing razor edit, while the requested
output span remains exact in the project clock.

The crop retains assets, track visibility/mute/solo, clip identities and aligned
link groups. Motion, title, transition and audio automation keep their authored
duration and keyframes. The signed authored offset advances by the removed output
head, including explicit offsets in effect-stack stages. The export copy clears
markers, playback selection and the previous export receipt.

Use `NativeRenderEngine.export(prepared, outputFile, aspect, quality, listener)` and
`NativeRenderEngine.diagnostics(prepared)`. The renderer uses
`prepared.durationMs`, rather than deriving length from the last cropped clip, for
the primary video clock and actual video/audio sequence gaps. This preserves a
range ending inside a timeline gap. `requiresTerminalGap` describes whether the
cropped graph's final clip precedes the requested end; individual sequences may
also need their own terminal gaps. No placeholder media is inserted.

The accepted range metadata belongs in the durable export request, alongside the
pin of the original accepted source graph. Recovery must prepare the same range
from that pin. It must not read a later live project or infer a later editor
playback selection. A range selection alone is not a request to export it.

Audio-only ranges work when a real enabled audible source stream intersects the
selection. Empty ranges, all-gap ranges and selections containing only disabled
media are rejected. Current native layer/effect/codec restrictions still apply.
When a range starts inside processed audio, gain/fade automation keeps its authored
clock, but filter, dynamics and ambience state starts at the cut: earlier PCM is
not rendered as pre-roll. The plan reports that limitation.

Native output evidence includes the source revision, source In/Out, zero-based
render origin, requested duration and output validation. Encoded frame and AAC
packet boundaries permit up to 100 ms of duration quantization; the composition
uses the exact accepted millisecond span. Plan metadata is not proof of a completed
or playable output.

This implementation is source only. Its future regression sources have not been
run, and no app build, device run or range export has been exercised during the
owner's implementation phase.
