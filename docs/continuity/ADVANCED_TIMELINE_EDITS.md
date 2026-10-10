# Advanced timeline edits: current implementation scope

The Phase A source now contains real Roll, Slip and Slide controls in the editor's
timeline action strip. Each numeric dialog shows the affected track/clip source
and program ranges before the owner chooses Apply. Positive offsets move later;
negative offsets move earlier. These controls operate on the same revision-checked
project graph and undo history as MCP edits. They require no media render or
autonomous queue admission.

`ProjectAdvancedEdits.apply(Project, operation, JSONObject)` is the shared graph
planner. `describe(...)` returns the same plan's before/after ranges without
mutating the graph or opening a media provider. The intended shared timeline
operations and typed native actions are:

| Operation | Settings | Behavior |
| --- | --- | --- |
| `roll` | `clipId`, signed integer `deltaMs`, `edge: "start" or "end"` (default `end`) | Moves a touching cut, changes the preceding tail and following head, and preserves their outer endpoints. |
| `slip` | `clipId`, signed integer `deltaMs` | Shifts the source window at each linked peer's existing effective playback rate. Program start/end and source span stay fixed. |
| `slide` | `clipId`, signed integer `deltaMs` | Moves the target while keeping its source window and duration. Its touching preceding tails and following heads absorb the move. |

All offsets use **program milliseconds**, including Slip: a +1000 ms slip on a
2× clip chooses source footage 2000 ms later. Each source boundary is rounded once
to source milliseconds. Roll/Slide handle changes retain each clip's authored
speed and existing integer rounding residual, with both source boundaries rounded
against the same authored clock phase. They do not accumulate per-delta rounding,
shift footage after a closed head/tail edit cycle, or amplify a short razor span's
effective-rate rounding. Marginal legacy residuals are projected to the nearest
source millisecond that meets the shared duration tolerance. Exact program spans remain explicit so prior razor-cut
rounding cannot move a Roll or Slide's outer endpoints. An offset below any
affected source's millisecond precision is rejected.

Linked groups remain aligned. Roll includes touching counterparts transitively on
the affected linked lanes. Slide collects touching neighbors on every target
peer's lane; peers in free gaps move together. If an affected neighboring group's
other lane touches an unrelated middle clip, Slide refuses the topology with a
link/unlink instruction. It never silently edits an unrelated middle clip.
Every affected group must be unlocked. Final whole-project validation catches
collisions on all lanes, including lanes with gaps or other independent material.

Audio/video handle edits require a known positive source duration and must remain
inside that source. Still-image/title canvases can be resized by Roll or moved by
Slide, but cannot Slip because they have no alternate temporal source frames.
Collapsed source/program spans, exhausted handles and arithmetic overflow are
rejected before the graph changes.

Roll/Slide source-head edits advance the existing authored animation window;
tail-only edits retain its offset. Slip retains the program's authored animation
window, including its fades, while replacing only the underlying source footage.
Slide retains the target's source and authored animation clock. Signed trim
extensions hold the nearest authored state outside its original animation window.
No speed curves, cross-track compound clips, or range exports are implied.

This is source implementation and review evidence only. No build, tests, emulator,
device checks, parser checks, or CI workflow ran during this implementation phase.
The later verification phase must cover linked lane topologies, source limits,
non-unit speeds and razor quantization, repeated and closed-cycle handle edits,
legacy timing residuals, held animation extensions, undo/revision
conflicts, export pins, and preview/export behavior before marking these paths
verified.
