# VideoStudio 3.6.2 — Adaptive native scheduling without unnecessary thermal queueing

## Objective
Keep the owner's VideoStudio image-to-animation pipeline responsive on a phone.
Do not treat low-cost cloud storage operations like GPU video rendering. Do not
alter Android's true hardware status or bypass critical thermal protection.

## What changed
- Separate, dedicated single-worker queues for **HEAVY** rendering and
  **TRANSFER** project/Drive operations, while retaining two interactive LIGHT
  workers and a shared exclusive media/project lane.
- Native `JobManager` uses Android `PowerManager.getCurrentThermalStatus()`
  and a pure, testable `ThermalQueuePolicy`:
  - Level 0–2 (none, light, moderate): normal native render and file transfers.
  - Level 3 (severe): GPU/encoder/model-heavy work pauses at a checkpoint;
    Drive/project archive and restore can continue on the independent transfer
    worker, subject to available RAM.
  - Level 4+ (critical, emergency, shutdown): heavy render and protected
    transfers pause, respecting Android's system limits.
- At an active heavy-render safe checkpoint, the renderer **releases the
  exclusive media lane while waiting**; a safe transfer job can proceed.
  When it cools, the render reacquires the lane and rechecks thermal status.
- A half-restored cloud project retains its exclusive lane during thermal
  waits to avoid exposing partially copied media to concurrent rendering.
- Cloud project backup, restore, offload, and model-pack **archive** run through
  the TRANSFER queue; compute-intensive model-pack activation/install remains
  HEAVY. Cloud progress callbacks provide periodic thermal/RAM checkpoints.
- The recovery journal preserves the TRANSFER kind across service restarts.
- `get_state.workload.thermal`, hardware planning and the human editor show
  separate policies for GPU work versus safe storage transfers.
- Repeated thermal waits no longer persist redundant journal writes every
  1–2 seconds when the hold reason has not changed.

## Important constraints
This DOES NOT disable Android throttling or its severe/critical protection.
A phone that feels cool may report internal GPU/battery throttling. Very heavy
work still waits at level 3. A single Media3 encode cannot be arbitrarily
preempted inside the codec; checks occur at existing safe job checkpoints.
At low memory all guarded queues continue to pause.

No changes to device identity, MCP endpoint, file permissions, Google Drive
authorization scope, project migrations or media content. No Gallery access.

## Verification checklist
- Run existing Android JUnit and new `ThermalQueuePolicyTest` plus
  `JobRecoveryTest`; check source smoke and APK CI.
- On phone at thermal level 3: start image analysis and a project backup and
  confirm progress without occupying or blocking the render lane.
- Confirm an intensive animation render waits safely at 3 and resumes below 3.
- At 4+: verify protected archive waits at a safe progress checkpoint and
  resume preserves original files, integrity and checkpoint ID.
- Verify cancelling a waiting job releases its lane and terminal state stays
  terminal; verify mixed ongoing editor playback remains responsive.
- Test no duplicates after service restart and that original source frames
  remain untouched. Installation/update requires existing signing continuity.

The future storyboard-style AI motion synthesis engine is a separate research
and implementation track. This patch improves scheduling but does NOT itself
produce new source-faithful action frames or claim solved video-generation QA.
