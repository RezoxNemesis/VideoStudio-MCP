# VideoStudio 3.6 native editor and motion workspace

This release is an ARM64 Android APK, package `com.rezoxnemesis.videostudio`, version 3.6.0 (360), signed by an explicitly restored GitHub cache key. Previous 3.4.7 and 3.5.0 APKs were verified to have different certificates. Their original private keys are unavailable. An in-place upgrade may therefore be rejected: independently preserve project manifests, original media and exports before any uninstall. The existing workspace archive is not a complete reinstall migration: it does not automatically restore project database records, app-private imported sources or URI grants. Reinstallation requires reconnecting ChatGPT and reauthorising/reimporting media. Do not uninstall while important material remains only inside the app. Future builds explicitly use the restored key and verify that the APK certificate matches it.

## Implemented

- Native editor with Media3 composition playback at 512p, selected clip, seek/playhead, four tracks, zoom, trim handles, split, duplicate, move, source crop, speed, volume, text, colour controls, masks/chroma key, transform keyframes and undo/redo. Asset thumbnails and bounded PCM waveform analysis run off the UI thread. Available proxies are used; preparing a missing proxy is separate from playback.
- Media Bin import, preview, rename, remove, replacement, add to timeline and asset drag onto tracks. Removing an asset never deletes the original selected file.
- Human and ChatGPT edits use the same transactional project store. Stale edits are rejected instead of overwriting newer work. Human views refresh from the shared store. Undo validates timeline history before applying it.
- Separate manual render lane with priority over waiting autonomous renders. An already-running codec job finishes or must be cancelled; two simultaneous exports do not compete for hardware. Ordinary timeline changes do not enter the autonomous queue.
- Native Export screen: resolution, aspect, codec, bitrate, FPS cap/still-animation FPS, start, cancel, retry, recorded job progress and finished-file playback. A requested higher FPS does not interpolate source video. Hardware codec fallback may change the requested format. Encoded output must contain a decodable video frame and positive duration before publication.
- Service-owned durable jobs, immutable export snapshots, thermal/RAM admission and a bounded render wake lock. Interrupted exports restart from their snapshot; encoded MP4 fragments are not resumed. Recurrent motion generation can resume from frame/state checkpoints.
- Manual, ChatGPT Assist and Full Autonomous. Assist requires a human-approved, exact action/parameter token, valid for ten minutes and usable once. ChatGPT retries the identical command with `_approvedRequestId`. Gallery enumeration stays blocked; only explicitly selected assets are accessible.
- Storage Hub retains multiple explicitly authorised Android document-provider folders. Where an installed provider exposes document trees, its authorised account/folder can be selected. Export copies are hash-verified and renamed from a pending document before being reported as published.
- Animate Image workspace: imported source preview, brush mask, reference anchor option, camera settings, duration, 24/30 FPS, strength, prompt plan, analysis, render job and cancellation. The camera preview is explicitly analytic.
- Native MCP actions: `editor_edit`, `analyse_image_motion`, `generate_motion_plan`, `animate_image`, `refine_motion`, `render_generated_video`, `critique_generated_video`. The updated relay advertises named motion tools and `app_editor_edit`; the existing relay can use `app_execute` in Full Autonomous.

## Experimental learned frame synthesis

No trained temporal model pack is bundled, downloaded automatically, or validated in this release. The recurrent ONNX adapter is infrastructure, not proof of animation quality. Layered camera/portrait motion cannot create a new facial expression or limb pose; requests requiring those capabilities fail with an explicit requirement for a learned engine.

A compatible pack must use backend `onnx-image-to-video-v1`, provide SHA-256 `files` entries and a `motion` object containing `model`, `edge` (256 or 384), `stateChannels` (1–64), `normalization` (`rgb-minus-one-to-one`) and a realistic `workingSetMb` estimate. Existing model-pack installation and license consent apply. Contract changes invalidate recovery.

The custom float32 model ABI is:

| Tensor | Shape |
|---|---|
| reference, previous_frame | [1, 3, edge, edge] |
| recurrent_state | [1, stateChannels, edge/8, edge/8] |
| motion_mask | [1, 1, edge, edge] |
| motion_controls | [1, 12] |
| next_frame output | [1, 3, edge, edge] |
| next_state output | [1, stateChannels, edge/8, edge/8] |

Controls encode time, frame interval, blink, mouth, head, recoil, limb, cloth, smoke, flame, shake and parallax strength. They are deterministic control channels; an arbitrary downloadable video model will not understand them without a compatible trained export. Model sessions close every eight frames to allow resource admission before the next chunk. Unpainted pixels are blended back from the reference. Input is resized to a square; this adapter does not yet preserve arbitrary source geometry through a learned aspect-aware model.

## Remaining work and validation limits

- A compatible trained and licensed temporal model, real depth/pose inference, articulated pose planning, neural upscaling/interpolation and motion blur remain unimplemented or unvalidated. No face-embedding identity guarantee or semantic finger/style critic exists. Video critique reports available measurements and explicitly marks semantic checks unavailable.
- This is a functional initial native editor, not a complete professional NLE. Overlapping clips on one track are rejected. Current transitions are entry/exit transforms, not overlapping crossfades. Full transition/effect libraries, audio automation, free clip dragging/reordering and advanced mask/keyframe editors need further work.
- Storage currently uses Android document providers. Direct OAuth integrations, automatic bidirectional conflict-aware sync, resumable provider uploads and cloud proxy scheduling are not implemented. Providers without document-tree/rename support cannot be treated as fully supported storage backends.
- Restricted modes require the updated relay. Until its registration acknowledges Manual/Assist, Android advertises remote control as paused to prevent an older server from executing fallback writes. Full Autonomous retains the stable private MCP endpoint.
- JVM tests cover transaction/history correctness, import isolation, manual priority, approval matching, model contracts, recovery and relay routing. They do not validate Android GPU shaders, Media3 composition playback, phone-specific codecs, SAF behaviour or ONNX Android inference.

## Realme P1 Speed acceptance checks

1. Save project backups before attempting installation. If Android rejects the update, preserve those backups before replacing the app. Confirm 3.6.0 in Control and check/reconnect the private MCP endpoint.
2. Select a large image using Import. Confirm the native canvas displays it and no Gallery listing is performed.
3. Add a video and audio on separate tracks; trim/split, seek and preview. Inspect thumbnails and waveform when available.
4. Make a ChatGPT edit and a manual edit concurrently. Confirm fresh project revisions appear and stale changes are rejected.
5. Start manual H.264 export, cancel/retry, and play the resulting MP4. Repeat after backgrounding the app and with authorised storage selected.
6. Confirm Assist rejects an unapproved mutation; approve it locally and retry once with the returned token.
7. Try camera-only image motion. Request a blink without a learned pack: expect a clear error, not a false completed video. Validate a compatible model on the phone before enabling learned generation for normal use.
