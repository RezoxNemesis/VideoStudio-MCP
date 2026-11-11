# VideoStudio 3.5.7 • Human-First Studio

## Reference and problem
The owner provided a polished **human editing UI reference** (project dropdown, playback monitor, multi-lane timeline, purposeful tool tabs) plus screenshots of the actual 3.5.2-era app with giant card stacks, raw numbered assets, hard-to-find generated projects and a job/thermal status that did not correspond to real native MCP renderer work.
The previous editor prioritized ChatGPT autonomy, with a scrolling list of 24 tiles and low discoverability for human editing. This branch changes the default Editor, Home and AI Tools screens **without** removing the existing native renderer, project model or MCP v3 action bridge.

## What this patch actually implements
- Human-centered **Home**: prominent Resume Editor, new/import, All Renders, native connection status and quick access to the full project list. Not a prompt-form landing page.
- Human-centered **Editor**: explicit VideoStudio project chooser, 9:16 preview surface, independent **Play Edit**, **Latest MP4** and **All Renders** actions; seeks through mixed video/image clips with trim- and speed-correct position mapping; real timestamp display and paginated tap-to-select thumbnail timeline.
- Timeline basics: Split, Trim, Duplicate, Delete (does not delete original media), Move Left/Right, bounded Undo and Redo of project edits; proper speed/volume/blur sliders and color/motion/transition picker dialogs. Source images and generated video assets stay in the project Media Bin and can be previewed or added to timeline.
- AI Tools are organized into Create, Animate, Enhance, Text/Audio and Manage drawers, plus search. The animation rig tools and GLB model operations remain available but no longer bury everyday clip edits.
- Project/exports: results in VideoStudio-created separate output projects are accessible via All Renders across *all* known projects. Playback uses the actual Media3 ExoPlayer; video export goes through the existing native Media3 job.
- 3.5.7 Native Work dashboard reads the **native service's** actual job journal, lists queued/running/completed tasks, OS thermal level and state/reason, with a user-confirmed **cancel one specific native job** option. Previously the editor had a second JobManager with the same shared job key, which could overwrite native work snapshots. Its local editor/proxy jobs are now stored under a separate key.
- Android-only Connect/Repair ChatGPT screen opens ChatGPT with the existing device-owned MCP pairing message and can reconnect the foreground native service. App identity survives compatible APK upgrades and does not depend on opening a public website UI.
- Preview thumbnails are decoded asynchronously from VideoStudio-registered asset URIs only (never enumerate Gallery), with bounded image sizes. The editor stays responsive while heavy tasks wait.

## Necessary distinctions
- The public website *front-end* is optional if the authenticated Worker MCP backend remains online. **Deleting the Worker/relay itself will break off-device ChatGPT access**, because a phone behind NAT/Android Doze still requires a reachable authenticated transport.
- Tapping Connect shares the device-owned private pairing context with ChatGPT; it cannot silently install a ChatGPT plugin or grant user permissions. User approval/connection is still required on first pairing.
- Android-reported thermal `SEVERE` is not a simple outer case-temperature sensor. It may report silicon/battery thermal throttling when the phone feels cool. This patch makes the exact Android state and waiting jobs visible; it **does not disable system severe/critical protection**, which would increase risk of failed jobs and device harm.
- Previewing a source clip does not pretend to play unsupported effects. Supported effects are applied by VideoStudio native Media3 export; advanced/partial metadata-only controls are marked experimental and require QA.
- The current underlying native project model is still **linear clips**, not full arbitrary multi-layer compositing. Timeline visibility/playback is functional, but multitrack overlap editing, professional waveforms, full real-time effect preview, draggable keyframes and fine-grained touch gestures are next engineering milestones.
- This patch is not a redesign of the remote cloud Worker or an independent replacement for the MCP transport.

## QA Gates
- Android SDK compile and Robolectric unit tests: separate service/editor job journals and thermal-state rendering; mixed timeline scrub; safe undo/redo; tool catalog search; thumbnail sampling; existing app-native animation/media regression tests.
- Manual device test before release: open 3.5.7 without crashes; choose prior project and its generated result; play a rendered MP4; scrub and trim; apply a supported change, undo/redo and export; switch Home/Editor/AI Tools; inspect a pending render/thermal state; reconnect with existing MCP device identity while app sleeps and validate no Gallery enumeration.
- **Do not claim** on-device visual QA passed until the new APK is installed, capture real screenshots and verify actual touch interactions.
