# VideoStudio engineering continuity

Use `docs/continuity/ENGINEERING_BLUEPRINT.md` as the user-supplied product design and
`docs/continuity/IMPLEMENTATION_STATUS.md` for implemented work and exact next steps.
The owner authorized ordinary safe workspace and GitHub engineering decisions,
including editing, refactoring, commits and branch pushes, without approval prompts.
Do not ask routine layout, naming, implementation or library-selection questions.
Each cloud task already has an isolated checkout; do not create a worktree unless
explicitly requested. Preserve user data and stable device-owned MCP identity.

## Current execution phase

The owner's latest instruction overrides the blueprint's test-first workflow:
complete product implementation before entering the testing phase. Do not execute
unit/integration suites, emulator/device tests, or trigger CI workflows during this
implementation phase. Do not hide this verification gap or claim fixes, builds,
deployment, APK installation or full product completion without their evidence.
Source review and writing future regression coverage remain useful. Do not publish
an unverified release or push to `main` merely to produce an APK during this phase.

The owner's latest steering prioritizes the blueprint's animation capabilities.
Implement real articulated 2D rendering, mesh deformation, inverse kinematics,
poses and authored curves with working human controls and matching MCP operations.
Both interfaces must use the same persisted edits and preview/export evaluator.
Do not label these source implementations as usable, verified industry tooling or
completed 3D/generative animation before the corresponding implementation and
verification evidence exists.

## Product boundaries

Human editing and MCP must operate on the same persistent project transactions.
Owner selection, preview, trim and split should be immediate; heavy exports use a
visible owner session with priority over queued autonomous work and safe render
arbitration. A queued job is not a completed edit or a verified playable output.
MCP may use explicitly imported/project-owned media, never enumerate Gallery or
arbitrary Files. Keep image/video attachment bytes private and streaming. Preserve
TLS, checksum verification, owner revocation and original media. Model installation
alone is not proof of executable inference; label procedural generation honestly.

Continue from the highest-priority unfinished blueprint item and checkpoint exact
remaining scope when a session boundary is reached. Do not treat the presence of
menus or provider metadata as a completed feature.
