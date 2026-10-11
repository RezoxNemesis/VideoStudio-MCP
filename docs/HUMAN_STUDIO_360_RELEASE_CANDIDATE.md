# VideoStudio 3.6.0 — Unified human editor and native animation integration

## Human-editor acceptance target
The Android owner can open a project and exported MP4 directly, play or scrub a mixed image/video sequence, arrange clips by dragging, edit precise trims and effects, drag independent MUSIC and TEXT tracks, preview synced audio and captions, and export verified MP4 without manual per-frame uploads. ChatGPT can edit the *same* project lanes through private native MCP v3. No Gallery enumeration.

## Implemented in this branch
- Built upon the already tested 3.5.9 human editor with direct color/transform sliders, touch trimming, source-faithful caption preview, Media3 mix for multiple imported audio files, caption/timeline persistence and all legacy rig/3D animation tools.
- Video clips: touch-and-hold thumbnail to drag-reorder. Stable clip IDs, Undo/Redo and SQLite save preserve original imported file bytes. The old Move Left/Right buttons remain.
- Audio and caption bars: drag to change global start time, touch the right edge to trim. Actual saved edit is validated against the VideoStudio project and participates in Undo/Redo. Edits run on touch release, not each frame of dragging.
- Real imported audio waveform: bounded MediaExtractor/MediaCodec decoding on a separate executor, 96 RMS buckets for up to 30 seconds of the selected source, and a visible waveform coverage fraction if the audio is longer. No fake/random visual waveform.
- Real-time audition: up to three simultaneous imported audio layers follow the current editing playhead using native ExoPlayer instances; automatically stop when preview stops. More than three overlapping stems are exported but not all previewed. A source video clip's volume is applied both during preview and Media3 export.
- Timeline playback begins at the user's actual scrub location, respecting clip speed and trim; images and videos share the same global timeline clock.
- Timed TextOverlay caption effects operate on the final Media3 Composition without splitting original shots or causing artificial visual seams at subtitle boundaries.
- Music/audio tracks and timed captions are now eligible for articulated 2.5D Media3 composition as well as ordinary linear videos, using independent mixer sequences and composition-time overlays. Explicit source/time validation fails on missing or out-of-range imports.
- Native MCP v3 `set_studio_layers`: revision-checked, strictly project-bound array of Music/Caption records plus `get_state.studioLayers` and `activeProjectUpdatedAt`. Human edits are not silently replaced by stale assistant actions. This remains the installed Android native agent; the public web front-end is optional but an authenticated cloud relay is still necessary for remote commands.
- Thermal safety remains based on Android PowerManager; the pre-existing independent native heavy-render and human lightweight preview lanes are maintained. It is never safe to bypass a genuine OS SEVERE thermal reading.

## Tests to pass
- GitHub source smoke and Android debug compile/JUnit regression suite including `StudioTouchTimelineTest`, `StudioLayerContractTest`, `StudioAudioWaveformsTest`, `StudioTimedTitleOverlayTest`, existing multitrack, 2D skinned-sprite, GLB rendering and native pairing tests.
- Build valid installable debug APK with original signing key. No Gallery permission, no installer identity or durable pairing changes.
- REAL-device gate (not implied by GitHub green): open Editor and All Renders; drag 2 video clips; add and drag one audio stem and caption; scrub midway and hear music; export layered and nonlayered MP4; verify video orientation, audible mix, timed subtitles and imported-media continuity; reconnect native MCP while screen closed; inspect a genuine severe-thermal pause.

## Remaining professional-level features beyond this release
Arbitrary overlapping video-video compositing with masks and blend modes, unlimited decoded live audio stems, multi-camera/keyframe curve editing, advanced automatic subtitle generation and professional audio mastering are not supplied by this first release. Some legacy animation presets remain experimental and still require rendered-output inspection; free-form AI reconstruction from stills is not guaranteed. New features must pass on-device tests before anyone claims industry-level parity.
