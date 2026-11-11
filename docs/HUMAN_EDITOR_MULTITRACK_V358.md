# VideoStudio 3.5.8 — First working multitrack and touch timeline slice

## Scope and honest status
This is a **human-editable project multitrack** milestone layered atop 3.5.7's functional editor, native render library and MCP connection. The existing sequential V1 timeline remains intact, so 3.5.7 source projects and ChatGPT-generated MP4s survive APK upgrade.

## Implemented features
1. ProjectStore Project stores optional `trackLayout` as backward-compatible JSON inside app-private SQLite (no new database migration). Existing projects load safely with no tracks; they are presented using default VIDEO, AUDIO, TEXT, OVERLAY and EFFECT lanes.
2. `StudioTrackLayout` represents unique clip occurrences with explicit imported source asset IDs, start position, source trim, duration and gain metadata. Clips are sorted per track, reject collisions within the same lane, and can overlap across separate lanes. The layout persists in SQLite and Undo/Redo restores it.
3. **Native real audio tracks**: Media3 Transformer 1.11.1 builds additional AUDIO-only `EditedMediaItemSequence` lanes with microsecond silence gaps, verified imported assets, source clipping and native composition mixing. Two sound lanes can genuinely overlap in the resulting MP4. Audio from videos can be used as separate soundtrack sources.
4. Human Editor shows the lanes directly below the main image/video timeline. Users may add sound from VideoStudio's imported assets, add extra overlapping lanes, move clips to the playhead, edit start/duration, remove a sound non-destructively, Undo and Redo changes.
5. Main clip strip supports **Android long-press drag-and-drop reorder**; the clip Trim tool has touch-controlled IN and OUT handles, with the previous precise numeric trim dialog still available.
6. MCP native v3 exposes `set_track_layout` and `get_state.trackLayout`: ChatGPT can edit the same validated track model as the human editor, with optional optimistic version check `expectedProjectUpdatedAt`, strict source project asset validation and no Gallery enumeration.
7. Multi-lane AUDIO exports fail explicitly when a source is missing, its duration cannot be read, or a non-default gain value is configured. They never report a successful render while silently dropping the requested sound.

## Limits and next steps
- This milestone actually exports overlapping **audio** lanes; the extra TEXT/OVERLAY/EFFECT rows are labeled AUTHORING ONLY, **not** represented as functional overlapping video compositing. Media3 overlay compositing, speech/dialogue lanes, keyframe authoring and true multi-track image playback still need their own engineering/test milestones.
- Sound gain metadata is validated (1x only). A real per-track gain AudioProcessor and automatic ducking are not implemented in this slice; UI must not advertise gain editing as complete.
- Audio lane export currently requires a primary video/image timeline, and layered 2.5D rendering must first be flattened for extra audio mixing. It refuses incompatible cases rather than silently losing work.
- Android drag-and-drop reorders the primary timeline, but per-frame source media animation still requires its proper character rig; no static still frame should be called true animation.
- Heat/work governor remains active. Render queue and thermal status are shown in the human Editor; deliberately never bypass Android THERMAL_STATUS_SEVERE.
- Off-app remote ChatGPT commands require a reachable authenticated MCP Worker. The public web editor UI may be deleted without changing Android private project storage, but deleting the Worker transport cannot be fixed with an in-app key alone.

## On-device acceptance
1. Update APK over 3.5.7; don't uninstall. Choose previous source project, drag clips to reorder, Undo/Redo; drag trim handles and export once.
2. Import one audio song and one clip with usable media duration. Add one on AUDIO-1 at 1 s and a second sound on AUDIO-2 at 2 s, export; verify resulting MP4's **audible mix** and timing on device. Compare before/after waveform and volume manually until hardware QA is available.
3. Check MCP get_state returns optional track layout and `set_track_layout` edits show in native Editor; ensure wrong/unregistered asset IDs fail safely.
4. Verify actual screenshot appearance, touch targets, 320dp phone width, no Gallery listing, background thermal queue and app wake/recovery. GitHub green does **not** replace these physical checks.

Developer milestone: #58 parent human-first PR; this 3.5.8 milestone remains a separate draft until native device testing succeeds.