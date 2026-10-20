# VideoStudio OMEGA 4.9.0 — on-device reference-film acceptance

**State:** required QA procedure. No real-device pass, achieved similarity score or reference-frame recreation is implied by this document.
**Signed candidate branch:** `candidate/omega-4.9.0-cinematic-reference-20261020`
**Native source PR:** #128
**Original user-provided videos:** `1000071258.mp4` (car 3D viewpoint reference), `1000071196.mp4`, `1000071197.mp4`, `1000071306.mp4` (character/drawing/action cinematography references).
**Source GLB:** use the *existing owner-imported VideoStudio GLB*, not an MP4 falsely treated as a recoverable 3D mesh. Never search Gallery.

## What must actually work
1. **Physical car/GLB:** load a real GLB from its VideoStudio project. From its existing position, play the `360° turntable` preview. Capture front/side/back/other side plus two oblique angles at stable animation time. The actual polygonal geometry, UVs, PBR material and shadows must remain consistent across the complete revolution. Start/end camera pixels should approximately agree when the car animation time is identical.
2. **Camera staging:** in native `Cinematic3DStudioActivity`, select `Reference camera paths`, apply hero dolly, acting track, impact arc and fine-detail reveal to a disposable authored 3D shot. Save the timeline, reopen it, then inspect native preview and render. The shot's 25 camera keys must survive storage and match frame samples at 0/25/50/75/100%. Compare subject scale, perspective and background depth, not a zoomed single still image.
3. **PBR lighting:** toggle `3-point PBR studio lighting` on a compatible GLB. Compare full-size images for key/fill/rim shading, specular response, material color retention, shadows and absence of severe clipping. The GLB's exact source texture/material hashes must be unchanged. Lighting cannot create missing geometry or invent high-resolution texture maps.
4. **Precision hand/character art:** create a throwaway native sketch. Use `Precision Bézier pen` to author a hand/face/garment contour guided by a permitted imported reference. Zoom in and inspect editable cubic handles, continuity and human-checked anatomy. Do **not** call a pasted reference or box primitive a successfully redrawn sketch.
5. **Character acting:** use `apply_acting_motion` via native Sketch Studio. Verify independently animated object pixels for anticipation, drive, recoil, overlap and recovery. A camera pan alone is not acting. Confirm frame time and 2D subject change at 0/25/50/75/100% of the action segment, undo/redo and source master links.
6. **Multiple-angle continuity:** preserve identifiable features and colors across at least front, profile, three-quarter and back views. For flat 2D drawings, require explicitly authored unseen views or a true rigged 3D source; never silently hallucinate the unseen side.
7. **Audio/export and recovery:** produce a native MP4 with verified dimension/fps, frame variation, sound if an actual audio lane exists, and final playable media registration. On restart, the project remains recoverable, original signing identity remains unchanged, and interrupted jobs follow owner-controlled holds.

## Verifiable evidence for approval
- Device APK version **4.9.0**, package `com.rezoxnemesis.videostudio.next`, versionCode `493` and the pinned original signing certificate; confirm via native app_status.
- Native project IDs and read-only revision/source hashes, named GLB asset ID (not Gallery), session timestamp and exact version of each reference clip used.
- Actual **VideoStudio-rendered** MP4 or approved native frame evidence. Five timestamps per shot and at least six true model camera directions for the car. Label preview frames as preview, not final export.
- Source/target comparison by camera angle, silhouette accuracy, material/shadow detail, foot/prop contacts, smoothness (including path start/stop), character motion and temporal sound sync.
- Quality scores and percent improvements **only after** consistent same-device, same-input, same-resolution and repeatable benchmark. A passing unit test or 3-point light declaration does not establish artistic similarity.
- Reviewer signs off on continuity and deliberate imperfections; no artificial quality or device-throughput metrics.

## Current release blockers
- Test suite and owner-signed APK workflow must succeed before first install.
- No on-phone camera-shader visual proof, source-guided anatomy proof, target-video similarity score or new texture-generation fidelity claim has yet been recorded.
- High polygon GLBs may fail memory/thermal admission; clip and GPU limits are separate from the 10 GiB **video/audio** transfer lane.
- Native release must remain opt-in, in a draft PR, preserving every project, privacy control, Gallery prohibition, render hold and the same signing key.
