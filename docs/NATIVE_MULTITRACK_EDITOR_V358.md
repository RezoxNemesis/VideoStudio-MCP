# VideoStudio 3.5.8 — Real Music & Caption Tracks + Responsive Job Lanes

## What changes for the human editor
VideoStudio's new Audio & Titles panel sits immediately below the video timeline. It contains a seekable two-row lane overview (audio/music + titles), Add Music, Add Caption, and editable track rows.
- Music: select an explicitly imported audio asset from the app's Media Bin; set timeline start, track length, source in-point and independent volume 0–100%. No Gallery enumeration or music URL scraping.
- Captions: author text and exact start/end in seconds and select top, center or bottom position. Titles appear as independent colored lane bars in the editor.
- Timeline projects store these tracks in the existing SQLite serialized project record, preserve originals, and support Undo/Redo with the previously introduced StudioEditHistory.
- The video renderer now creates multiple **real Media3 sequences**: primary video/image track plus parallel audio sequences containing silence gaps for exact starting offset, audio source clipping and 0–100% sample gain via Media3 GainProcessor. Caption timing is split into actual source-file-preserving EditedMediaItem segments, each with a Media3 TextOverlay effect when active. This is functional export-layer editing, not cosmetic metadata.
- Export reports number of independent audio/title layers; existing VideoStudio animation and non-track project export pathways remain untouched. Existing all-video source audio is retained when mixing separate music. Mixed image/video source audio behavior still requires more complete sound-bed handling and full Android QA.

## Human-first safety and responsiveness
- Editor and native agent job journals remain independent (3.5.7 fix). Heavy GPU/encoder work now uses its own single-thread work queue; light image/audio previews and cloud tasks keep two distinct workers. Heavy work does not monopolize global render permits while it waits for Android thermal or RAM recovery.
- VideoStudio still obeys **Android THERMAL_STATUS_SEVERE and higher**; the UI reports this estimate honestly. Disabling severe OS protection would jeopardize device safety and job integrity.
- No new Android permissions, full phone Gallery enumeration, paid inference or remote rendering required.

## Restrictions
- This is a **first working two-lane layer editor** (up to four simultaneous music sequences plus timed caption layers), not yet a fully general keyframed NLE with draggable resizing, arbitrary overlays, audio waveforms or multitrack Foley effects.
- Independent music and timed captions are burned/mixed into **exported MP4**; real-time live preview of complex overlays is not yet implemented. Play Edit previews source clips. Play Latest MP4/All Renders shows the actual finished mix.
- Sources with special layered portrait animation graphs currently reject extra tracks explicitly instead of silently dropping new audio. Audio/title tracks need a normal video/image clip timeline and must end within the project duration.
- Native Media3 composition build success does not prove an encoded video's sound is audible or captions positioned perfectly on every Android driver. Post-installation video+audio inspection remains mandatory.

## Native regression checklist
1. Import video and an audio file using Android Files picker. Add audio at 0.5s, 1.5s duration, 60% gain; add caption at 1.0–2.0s. Confirm independent colored timeline lanes show the correct positions.
2. Tap Edit then remove. Use Undo/Redo; save, close and reopen; verify both layers remain in the SQLite project.
3. Render through native Media3; verify a playable MP4 contains mixed audio that begins at the specified time and caption is only visible in its correct interval.
4. Test original video-source audio remains alongside imported music for all-video timeline; mixed photo/video audio needs further care.
5. Check native heavy thermal wait doesn't consume light editor workers; never override severe OS thermal threshold.
6. Regression: source assets, project opener, Media Bin, All Renders, Android MCP v3 pairing, text overlay effects, generated 2D/3D rig playback, Gallery isolation.
